package zombie.mdc;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * W75 非主執行緒的 IsoObject 每幀更新清單移除排回主執行緒（2026-10-25；docs/patches.md 2cj）。
 *
 * <p><b>背景</b>：W73 觀測在正式服 50 分鐘記到 20 次 {@code ServerPlayersVehicles} 執行緒呼叫
 * {@code IsoCell.addToProcessIsoObjectRemove}（{@code BaseVehicle.load → addAnimalInTrailer → IsoAnimal.removeFromWorld →
 * … → IsoObject.removeFromWorld}）。原版在背景執行緒讀 {@code processIsoObjectSet}、寫 {@code processIsoObjectRemove}
 * （兩個 HashSet），主執行緒同時在 {@code ProcessIsoObject} 頭部 removeAll／clear、在 {@code addToProcessIsoObject} 改同兩份集合。
 *
 * <p><b>手術</b>（比照 W41）：全 jar 唯一的 {@code addToProcessIsoObjectRemove} 呼叫點（{@code IsoObject.removeFromWorld()}
 * bci 15）1:1 改道 {@link #addToProcessIsoObjectRemove}：非主執行緒時排進佇列，主執行緒或 {@code mainThread} 尚未設定時照原版。
 * {@code ProcessIsoObject} 頭部 {@link #beginPass} 在原版 {@code isEmpty}／{@code removeAll} 之前排空佇列（對每筆呼叫原版
 * {@code addToProcessIsoObjectRemove}），移除在同一輪生效；接著呼叫 W73 的 {@code beginPass}。
 * {@code addToProcessIsoObject} 頭部 {@link #beforeAdd} 先呼叫 W73 的 {@code touchAdd}，再把佇列裡同一 cell＋物件的待移除
 * 拿掉，對應原版那行 {@code processIsoObjectRemove.remove(object)}——「背景移除→主執行緒加回」結果與原版相同。
 *
 * <p>開關 {@code -Dmdc.isoObjectRemoveDefer}：預設 on，{@code 0|off} 純委派；與 W73 {@code isoObjectGate} 無關。
 */
public final class IsoObjectRemoveDefer {

    static final String RAW = System.getProperty("mdc.isoObjectRemoveDefer");
    static final boolean ENABLED = parse(RAW);

    private static final String TAG = "[MinidoracatJavaPatch][IsoObjectRemoveDefer] ";
    private static final long HEARTBEAT_MS = 300_000L;
    private static final int CAP = 100_000;
    private static final long DETAIL_LIMIT = 20L;
    private static final int SOURCE_LIMIT = 8;

    record Entry(IsoCell cell, IsoObject object) {}

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pending = new AtomicInteger();
    private static final AtomicLong deferred = new AtomicLong();
    private static final AtomicLong overflow = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();
    private static final AtomicInteger maxPending = new AtomicInteger();
    private static final Map<String, AtomicLong> SOURCES = new ConcurrentHashMap<>();
    private static final AtomicLong sourcesOther = new AtomicLong();

    // 以下只在主執行緒。
    private static long drained, cancelled, lastBeat;
    private static boolean started;
    static String lastBeatLine;

    static boolean parse(String raw) {
        if (raw == null) {
            return true;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("0") || v.equals("off"));
    }

    /** {@code IsoObject.removeFromWorld()} 內唯一 {@code IsoCell.addToProcessIsoObjectRemove} 的改道（receiver 前置）。 */
    public static void addToProcessIsoObjectRemove(IsoCell cell, IsoObject object) {
        Thread main = GameServer.mainThread;
        if (!ENABLED || main == null || Thread.currentThread() == main || cell == null || object == null) {
            cell.addToProcessIsoObjectRemove(object);   // 原版（cell 為 null 時照原版 NPE）
            return;
        }
        int n = pending.incrementAndGet();
        if (n > CAP) {
            pending.decrementAndGet();
            overflow.incrementAndGet();
            cell.addToProcessIsoObjectRemove(object);
            return;
        }
        QUEUE.add(new Entry(cell, object));
        maxPending.accumulateAndGet(n, Math::max);
        long d = deferred.incrementAndGet();
        if (d <= DETAIL_LIMIT || d % 1000 == 0) {
            try {
                String via = callers();
                AtomicLong c = SOURCES.get(via);
                if (c == null && SOURCES.size() < SOURCE_LIMIT) {
                    c = SOURCES.computeIfAbsent(via, k -> new AtomicLong());
                }
                if (c != null) {
                    c.incrementAndGet();
                } else {
                    sourcesOther.incrementAndGet();
                }
                DebugLog.log(TAG + "deferred n=" + d + " thread=" + Thread.currentThread().getName() + " via=" + via);
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
    }

    /** {@code IsoCell.ProcessIsoObject} 頭部：排空佇列（原版 removeAll 之前），再交給 W73 的 beginPass。 */
    public static void beginPass(IsoCell cell) {
        if (ENABLED) {
            drain();
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                lastBeat = now;
                DebugLog.log(TAG + "首次生效 on raw=" + RAW + "（-Dmdc.isoObjectRemoveDefer=0|off 回原版；心跳每 5 分鐘一行）");
            } else if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        }
        IsoObjectUpdateGate.beginPass(cell);
    }

    static void drain() {
        Entry e;
        while ((e = QUEUE.poll()) != null) {
            pending.decrementAndGet();
            drained++;
            e.cell().addToProcessIsoObjectRemove(e.object());
        }
    }

    /** {@code IsoCell.addToProcessIsoObject} 頭部：W73 計數，再取消同一 cell＋物件的待移除（對應原版 Remove.remove）。 */
    public static void beforeAdd(IsoCell cell, IsoObject object) {
        IsoObjectUpdateGate.touchAdd(cell);
        if (!ENABLED || object == null || pending.get() == 0) {
            return;
        }
        Thread main = GameServer.mainThread;
        if (main != null && Thread.currentThread() != main) {
            return;   // 非主執行緒的加入不在本刀範圍（觀測為 0），維持原版
        }
        for (Iterator<Entry> it = QUEUE.iterator(); it.hasNext(); ) {
            Entry q = it.next();
            if (q.cell() == cell && q.object() == object) {
                it.remove();   // 只有主執行緒會移除佇列元素（drain 與這裡），背景執行緒只加入
                pending.decrementAndGet();
                cancelled++;
            }
        }
    }

    private static void beat() {
        StringBuilder src = new StringBuilder();
        SOURCES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(en -> src.append(src.isEmpty() ? "" : " | ").append(en.getValue().get()).append('×').append(en.getKey()));
        String line = "beat deferred=" + deferred.get() + " drained=" + drained + " cancelled=" + cancelled
                + " pending=" + pending.get() + " maxPending=" + maxPending.get() + " overflow=" + overflow.get()
                + " anomalies=" + anomalies.get() + " sources=[" + src + (sourcesOther.get() > 0 ? " | other×" + sourcesOther.get() : "") + "]";
        lastBeatLine = line;
        DebugLog.log(TAG + line);
    }

    private static String callers() {
        return StackWalker.getInstance().walk(frames -> {
            StringBuilder s = new StringBuilder();
            frames.filter(f -> f.getClassName().startsWith("zombie.") && !f.getClassName().startsWith("zombie.mdc."))
                    .limit(8)
                    .forEach(f -> s.append(s.isEmpty() ? "" : "<")
                            .append(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1))
                            .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber()));
            return s.toString();
        });
    }

    static void beatForTest() { beat(); }
    static long deferredForTest() { return deferred.get(); }
    static long drainedForTest() { return drained; }
    static long cancelledForTest() { return cancelled; }
    static int pendingForTest() { return pending.get(); }
    static int maxPendingForTest() { return maxPending.get(); }

    private IsoObjectRemoveDefer() {}
}
