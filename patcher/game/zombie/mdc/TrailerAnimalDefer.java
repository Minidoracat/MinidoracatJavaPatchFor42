package zombie.mdc;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * W107 背景載車時把拖車動物放進車廂的整段排回主執行緒（docs/patches.md 2do）。
 *
 * <p><b>原版缺陷</b>：{@code ServerPlayersVehicles} 執行緒載車（{@code VehiclesDB2$QueueLoadChunk.processWorldStreamer →
 * BaseVehicle.load}）時，對存檔裡每隻拖車動物呼叫 {@code addAnimalInTrailer}：{@code removeFromWorld}（engine 移除、
 * cell 的 removeList／objectList、hutch、dZone、每幀更新清單）、{@code sendExtraUpdateToClients}
 * （{@code AnimalInstanceManager}、{@code AnimalSynchronizationManager} 的 HashMap）都在背景執行緒寫主執行緒的結構；
 * 主迴圈同時在迭代它們（00:41 的 {@code AnimalSynchronizationManager.update} CME 推測來自這裡），
 * 也是 W105 唯一已知的背景 engine 呼叫來源。
 *
 * <p><b>手術</b>：{@code BaseVehicle.load(ByteBuffer,int,boolean)} 兩個 {@code addAnimalInTrailer(IsoAnimal)} 同形改道到
 * {@link #addAnimalInTrailer}；非主執行緒照順序排進 FIFO，{@code VehiclesDB2$QueueLoadChunk.processMain} 頭部
 * （{@link #beforeLoadChunkMain}）在主執行緒依序呼叫原版。背景載完一個 chunk 才把它的 QueueLoadChunk 放進主執行緒佇列，
 * 所以它的動物一定在它的 processMain 之前排空（車加進世界之前動物已在車廂裡，與原版相同）。
 * 主執行緒或 {@code mainThread} 還是 null 時照原版。動物的建構與 {@code load}（讀存檔緩衝）仍在背景執行緒。
 *
 * <p>kill switch {@code -Dmdc.trailerAnimalDefer=0|off}（純委派）。需重啟。
 */
public final class TrailerAnimalDefer {

    static final String RAW = System.getProperty("mdc.trailerAnimalDefer");
    static final boolean ENABLED = parse(RAW);

    private static final String TAG = "[MinidoracatJavaPatch][TrailerAnimalDefer] ";
    private static final long HEARTBEAT_MS = 300_000L;
    private static final int CAP = 100_000;
    private static final long DETAIL_LIMIT = 20L;
    private static final int SOURCE_LIMIT = 8;

    record Entry(BaseVehicle vehicle, IsoAnimal animal) {}

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pending = new AtomicInteger();
    private static final AtomicInteger maxPending = new AtomicInteger();
    private static final AtomicLong deferred = new AtomicLong();
    private static final AtomicLong drained = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static final AtomicLong overflow = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();
    private static final Map<String, AtomicLong> sources = new ConcurrentHashMap<>();
    private static final AtomicLong sourcesOther = new AtomicLong();

    // 以下只在主執行緒。
    private static long lastBeat;
    private static boolean started;
    static String lastBeatLine;

    private TrailerAnimalDefer() {
    }

    static boolean parse(String raw) {
        if (raw == null) {
            return true;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("0") || v.equals("off"));
    }

    private static boolean onMain() {
        return Thread.currentThread() == GameServer.mainThread;
    }

    /** {@code BaseVehicle.load} 內兩個 {@code addAnimalInTrailer(IsoAnimal)} 的改道（receiver 前置）。 */
    public static void addAnimalInTrailer(BaseVehicle vehicle, IsoAnimal animal) {
        Thread main = GameServer.mainThread;
        if (ENABLED && vehicle != null && main != null && Thread.currentThread() != main && enqueue(vehicle, animal)) {
            return;
        }
        vehicle.addAnimalInTrailer(animal);   // 原版（vehicle 為 null 時照原版 NPE）
    }

    private static boolean enqueue(BaseVehicle vehicle, IsoAnimal animal) {
        int n = pending.incrementAndGet();
        if (n > CAP) {
            pending.decrementAndGet();
            overflow.incrementAndGet();
            return false;
        }
        QUEUE.add(new Entry(vehicle, animal));
        maxPending.accumulateAndGet(n, Math::max);
        long d = deferred.incrementAndGet();
        if (d <= DETAIL_LIMIT || d % 1000 == 0) {
            try {
                String via = callers();
                AtomicLong c = sources.get(via);
                if (c == null && sources.size() < SOURCE_LIMIT) {
                    c = sources.computeIfAbsent(via, k -> new AtomicLong());
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
        return true;
    }

    /** {@code VehiclesDB2$QueueLoadChunk.processMain} 頭部：依 FIFO 在主執行緒呼叫原版 addAnimalInTrailer。 */
    public static void beforeLoadChunkMain() {
        if (!onMain()) {
            return;
        }
        drain();
        if (ENABLED) {
            beatIfDue();
        }
    }

    /** 逐筆接住 RuntimeException：原版在背景拋出時整台車被當成讀檔失敗刪掉；排回主執行緒後只記錄、下一筆照做。 */
    static void drain() {
        if (pending.get() == 0) {
            return;
        }
        Entry e;
        while ((e = QUEUE.poll()) != null) {
            pending.decrementAndGet();
            drained.incrementAndGet();
            try {
                e.vehicle().addAnimalInTrailer(e.animal());
            } catch (RuntimeException ex) {
                long n = failed.incrementAndGet();
                if (n <= DETAIL_LIMIT || n % 1000 == 0) {
                    try {
                        DebugLog.log(TAG + "failed n=" + n + " vehicle=" + e.vehicle().getClass().getSimpleName() + "@"
                                + Integer.toHexString(System.identityHashCode(e.vehicle())) + " ex=" + ex);
                    } catch (RuntimeException | LinkageError le) {
                        anomalies.incrementAndGet();
                    }
                }
            }
        }
    }

    private static void beatIfDue() {
        try {
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                lastBeat = now;
                DebugLog.log(TAG + "首次生效 raw=" + RAW + "（-Dmdc.trailerAnimalDefer=0|off 回原版；心跳最多每 5 分鐘一行，只在載入車輛時）");
            } else if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    private static void beat() {
        StringBuilder src = new StringBuilder();
        sources.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(en -> src.append(src.isEmpty() ? "" : " | ").append(en.getValue().get()).append('×').append(en.getKey()));
        if (sourcesOther.get() > 0) {
            src.append(src.isEmpty() ? "" : " | ").append("other×").append(sourcesOther.get());
        }
        String line = "beat deferred=" + deferred.get() + " drained=" + drained.get() + " pending=" + pending.get()
                + " maxPending=" + maxPending.get() + " failed=" + failed.get() + " overflow=" + overflow.get()
                + " anomalies=" + anomalies.get() + " sources=[" + src + "]";
        lastBeatLine = line;
        try {
            DebugLog.log(TAG + line);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
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

    // ---- 測試存取器 ----

    static void beatForTest() { beat(); }
    static int pendingForTest() { return pending.get(); }
    static long deferredForTest() { return deferred.get(); }
    static long drainedForTest() { return drained.get(); }
    static long failedForTest() { return failed.get(); }
}
