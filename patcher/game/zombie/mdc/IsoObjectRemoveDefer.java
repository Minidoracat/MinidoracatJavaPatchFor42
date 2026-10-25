package zombie.mdc;

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
 * W75／W75-2 非主執行緒的 IsoObject 每幀更新清單寫入排回主執行緒（docs/patches.md 2cj、2ck）。
 *
 * <p><b>背景</b>：W73 觀測在正式服記到兩條背景寫入：{@code ServerPlayersVehicles} 載車時把拖車動物移出世界
 * （{@code IsoObject.removeFromWorld → IsoCell.addToProcessIsoObjectRemove}，W75），以及 {@code LoadChunk} 執行緒讀檔時
 * 建構屠宰鉤（{@code IsoGridSquare.load → IsoObject.factoryFromFileInput → IsoButcherHook.<init>(IsoCell) →
 * IsoCell.addToProcessIsoObject}，W75-2）。原版在背景執行緒讀寫 {@code processIsoObjectSet}／{@code processIsoObjectRemove}
 * （HashSet）與 {@code processIsoObject}（ArrayList），主執行緒同時在 {@code ProcessIsoObject} 頭部 removeAll／clear。
 *
 * <p><b>手術</b>（比照 W41）：{@code IsoObject.removeFromWorld()} 唯一的 {@code addToProcessIsoObjectRemove} 呼叫點與
 * {@code IsoButcherHook} 三個 {@code addToProcessIsoObject} 呼叫點 1:1 改道到這裡。非主執行緒的呼叫照呼叫順序排進同一條 FIFO 佇列
 * （op＝ADD／REMOVE），{@code ProcessIsoObject} 頭部 {@link #beginPass} 在原版 removeAll 之前依序呼叫原版；主執行緒或
 * {@code mainThread} 尚未設定時照原版。
 *
 * <p><b>與原版循序結果相同</b>：同一 cell＋物件的最終狀態只取決於最後一次呼叫（加入＝在清單；移除＝下一次 removeAll 後不在）。
 * 直接執行的原版呼叫（主執行緒，或開關 off 時的背景執行緒）一定晚於佇列裡同一物件的所有呼叫，所以先取消佇列裡同一 cell＋物件的
 * 相反操作，剩下的同類操作排空時是冪等的：加入頭部 {@link #beforeAdd} 取消待移除（對應原版 {@code processIsoObjectRemove.remove}），
 * 直接的移除取消待加入。排空本身呼叫原版加入時不取消（否則背景「先加後移」會被排空的加入吃掉移除）。
 *
 * <p>開關各自獨立：{@code -Dmdc.isoObjectRemoveDefer}（W75）、{@code -Dmdc.isoObjectAddDefer}（W75-2），預設 on，
 * {@code 0|off} 該操作純委派；與 W73 {@code isoObjectGate} 無關。
 */
public final class IsoObjectRemoveDefer {

    static final String RAW = System.getProperty("mdc.isoObjectRemoveDefer");
    static final boolean ENABLED = parse(RAW);
    static final String ADD_RAW = System.getProperty("mdc.isoObjectAddDefer");
    static final boolean ADD_ENABLED = parse(ADD_RAW);

    private static final String TAG = "[MinidoracatJavaPatch][IsoObjectRemoveDefer] ";
    private static final long HEARTBEAT_MS = 300_000L;
    private static final int CAP = 100_000;
    private static final long DETAIL_LIMIT = 20L;
    private static final int SOURCE_LIMIT = 8;

    record Entry(IsoCell cell, IsoObject object, boolean add) {}

    /** 每種操作（REMOVE＝0、ADD＝1）的計數與來源。 */
    private static final class Stats {
        final AtomicLong deferred = new AtomicLong();
        final AtomicLong drained = new AtomicLong();
        final AtomicLong cancelled = new AtomicLong();
        final AtomicLong overflow = new AtomicLong();
        final Map<String, AtomicLong> sources = new ConcurrentHashMap<>();
        final AtomicLong sourcesOther = new AtomicLong();
    }

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pending = new AtomicInteger();
    private static final AtomicInteger maxPending = new AtomicInteger();
    private static final AtomicLong anomalies = new AtomicLong();
    private static final Stats REMOVE = new Stats();
    private static final Stats ADD = new Stats();

    // 以下只在主執行緒。
    private static boolean draining;
    private static long lastBeat;
    private static boolean started;
    static String lastBeatLine;

    static boolean parse(String raw) {
        if (raw == null) {
            return true;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("0") || v.equals("off"));
    }

    /** 非主執行緒（mainThread 已設定）回 true。 */
    private static boolean offMain() {
        Thread main = GameServer.mainThread;
        return main != null && Thread.currentThread() != main;
    }

    /** {@code IsoObject.removeFromWorld()} 內唯一 {@code IsoCell.addToProcessIsoObjectRemove} 的改道（receiver 前置）。 */
    public static void addToProcessIsoObjectRemove(IsoCell cell, IsoObject object) {
        if (ENABLED && cell != null && object != null && offMain() && enqueue(cell, object, false)) {
            return;
        }
        cancel(cell, object, true);                 // 直接的移除晚於佇列裡同一物件的待加入
        cell.addToProcessIsoObjectRemove(object);   // 原版（cell 為 null 時照原版 NPE）
    }

    /** {@code IsoButcherHook} 三個 {@code IsoCell.addToProcessIsoObject} 的改道（receiver 前置）。 */
    public static void addToProcessIsoObject(IsoCell cell, IsoObject object) {
        if (ADD_ENABLED && cell != null && object != null && offMain() && enqueue(cell, object, true)) {
            return;
        }
        cell.addToProcessIsoObject(object);         // 原版；取消待移除由原版方法頭部的 beforeAdd 處理
    }

    /** 排進佇列；超過上限回 false（呼叫端照原版直接跑）。 */
    private static boolean enqueue(IsoCell cell, IsoObject object, boolean add) {
        Stats s = add ? ADD : REMOVE;
        int n = pending.incrementAndGet();
        if (n > CAP) {
            pending.decrementAndGet();
            s.overflow.incrementAndGet();
            return false;
        }
        QUEUE.add(new Entry(cell, object, add));
        maxPending.accumulateAndGet(n, Math::max);
        long d = s.deferred.incrementAndGet();
        if (d <= DETAIL_LIMIT || d % 1000 == 0) {
            try {
                String via = callers();
                AtomicLong c = s.sources.get(via);
                if (c == null && s.sources.size() < SOURCE_LIMIT) {
                    c = s.sources.computeIfAbsent(via, k -> new AtomicLong());
                }
                if (c != null) {
                    c.incrementAndGet();
                } else {
                    s.sourcesOther.incrementAndGet();
                }
                DebugLog.log(TAG + (add ? "deferred add n=" : "deferred n=") + d + " thread=" + Thread.currentThread().getName()
                        + " via=" + via);
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
        return true;
    }

    /** {@code IsoCell.ProcessIsoObject} 頭部：排空佇列（原版 removeAll 之前），再交給 W73 的 beginPass。 */
    public static void beginPass(IsoCell cell) {
        drain();
        if (ENABLED || ADD_ENABLED) {
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                lastBeat = now;
                DebugLog.log(TAG + "首次生效 remove=" + (ENABLED ? "on" : "off") + " add=" + (ADD_ENABLED ? "on" : "off")
                        + " raw=" + RAW + "/" + ADD_RAW
                        + "（-Dmdc.isoObjectRemoveDefer／-Dmdc.isoObjectAddDefer=0|off 各自回原版；心跳每 5 分鐘一行）");
            } else if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        }
        IsoObjectUpdateGate.beginPass(cell);
    }

    /** 依 FIFO 對每筆呼叫原版；排空期間原版加入的頭部不取消（見 {@link #beforeAdd}）。 */
    static void drain() {
        if (pending.get() == 0) {
            return;
        }
        draining = true;
        try {
            Entry e;
            while ((e = QUEUE.poll()) != null) {
                pending.decrementAndGet();
                if (e.add()) {
                    ADD.drained.incrementAndGet();
                    e.cell().addToProcessIsoObject(e.object());
                } else {
                    REMOVE.drained.incrementAndGet();
                    e.cell().addToProcessIsoObjectRemove(e.object());
                }
            }
        } finally {
            draining = false;
        }
    }

    /** {@code IsoCell.addToProcessIsoObject} 頭部：W73 計數，再取消同一 cell＋物件的待移除（對應原版 Remove.remove）。 */
    public static void beforeAdd(IsoCell cell, IsoObject object) {
        IsoObjectUpdateGate.touchAdd(cell);
        if (draining && !offMain()) {
            return;   // 排空自己呼叫的原版加入：照 FIFO，不取消後面的待移除
        }
        cancel(cell, object, false);
    }

    /** 取消佇列裡同一 cell＋物件的待加入（{@code add}=true）或待移除。 */
    private static void cancel(IsoCell cell, IsoObject object, boolean add) {
        if (object == null || pending.get() == 0) {
            return;
        }
        Stats s = add ? ADD : REMOVE;
        for (Entry q : QUEUE) {
            if (q.add() == add && q.cell() == cell && q.object() == object && QUEUE.remove(q)) {
                pending.decrementAndGet();
                s.cancelled.incrementAndGet();
            }
        }
    }

    private static void beat() {
        String line = "beat deferred=" + REMOVE.deferred.get() + " drained=" + REMOVE.drained.get()
                + " cancelled=" + REMOVE.cancelled.get() + " pending=" + pending.get() + " maxPending=" + maxPending.get()
                + " overflow=" + REMOVE.overflow.get() + " anomalies=" + anomalies.get() + " sources=[" + sources(REMOVE) + "]"
                + " add=deferred:" + ADD.deferred.get() + ",drained:" + ADD.drained.get() + ",cancelled:" + ADD.cancelled.get()
                + ",overflow:" + ADD.overflow.get() + " addSources=[" + sources(ADD) + "]";
        lastBeatLine = line;
        DebugLog.log(TAG + line);
    }

    private static String sources(Stats s) {
        StringBuilder src = new StringBuilder();
        s.sources.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(en -> src.append(src.isEmpty() ? "" : " | ").append(en.getValue().get()).append('×').append(en.getKey()));
        if (s.sourcesOther.get() > 0) {
            src.append(src.isEmpty() ? "" : " | ").append("other×").append(s.sourcesOther.get());
        }
        return src.toString();
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
    static long deferredForTest() { return REMOVE.deferred.get(); }
    static long drainedForTest() { return REMOVE.drained.get(); }
    static long cancelledForTest() { return REMOVE.cancelled.get(); }
    static long addDeferredForTest() { return ADD.deferred.get(); }
    static long addDrainedForTest() { return ADD.drained.get(); }
    static long addCancelledForTest() { return ADD.cancelled.get(); }
    static int pendingForTest() { return pending.get(); }
    static int maxPendingForTest() { return maxPending.get(); }

    private IsoObjectRemoveDefer() {}
}
