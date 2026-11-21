package zombie.entity;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.network.GameServer;

/**
 * W105 非主執行緒的 engine 登記／移除排回主執行緒、W106 待處理佇列單筆壞資料不再卡死主迴圈（docs/patches.md 2dm、2dn）。
 *
 * <p><b>原版缺陷</b>：42.22.2 的 {@code GameEntityManager.UnregisterEntity(GameEntity, boolean)} 開頭不看是否登記過，
 * 一律 {@code engine.removeEntity}。{@code ServerPlayersVehicles} 執行緒載車時把拖車動物移出世界
 * （{@code BaseVehicle.load → addAnimalInTrailer → removeFromWorld}），就在背景執行緒寫 {@code EngineEntityManager} 的
 * {@code pendingOperations}（主執行緒 {@code Engine.update} 期間）或 {@code entitySet}；操作物件池是 {@code SingleThreadPool}，
 * {@code free} 先放回再 reset。兩條執行緒交錯時佇列會留下 entity 為 null 的 Remove，{@code processPendingOperations}
 * 每 tick 在 {@code clear()} 之前拋 {@code key cannot be null}，主迴圈此後每個 tick 都在 {@code GameEntityManager.Update} 中斷。
 *
 * <p><b>W105 手術</b>：{@code RegisterEntity} 的 {@code Engine.addEntity}、{@code UnregisterEntity(GameEntity,Z)} 的
 * {@code Engine.removeEntity}、{@code Update} 的 {@code Engine.update} 同形改道，{@code Save} 頭部 {@link #beforeSave}。
 * 非主執行緒的 engine 呼叫照呼叫順序排進 FIFO；主執行緒每次直接呼叫前、每 tick {@code engine.update()} 前、{@code Save} 前
 * 依序呼叫原版（先排空才直接呼叫，結果等於原版循序；不用 W75 的取消規則，否則「背景移除→主執行緒加回」會撞
 * {@code Entity is already registered}）。背景移除入列時取消佇列裡同一 engine＋entity 的待加入（原版延遲路徑同樣是移除取消待加入）。
 * 只延後 engine 那一步，{@code idToEntityMap} 與 {@code addedToEntityManager} 照原版即時（ID 在排空時可能已變）。
 *
 * <p><b>W106 手術</b>：{@code processPendingOperations} 內三個 {@code addEntityInternal}／{@code removeEntityInternal}
 * 改道 {@link #applyAdd}／{@link #applyRemove}：entity 為 null 略過並記錄；單筆拋 RuntimeException 時收好旗標、記錄後繼續，
 * 迴圈照原版 {@code free} 與 {@code clear}，壞的那筆不再留在佇列。Error 照常外拋。
 *
 * <p>開關各自獨立：{@code -Dmdc.entityEngineDefer}（W105）、{@code -Dmdc.entityOpGuard}（W106），預設 on，{@code 0|off} 純委派。
 */
public final class MdcEntityEngineGuard {

    static final String DEFER_RAW = System.getProperty("mdc.entityEngineDefer");
    static final boolean DEFER = parse(DEFER_RAW);
    static final String GUARD_RAW = System.getProperty("mdc.entityOpGuard");
    static final boolean GUARD = parse(GUARD_RAW);

    private static final String TAG = "[MinidoracatJavaPatch][EntityEngineGuard] ";
    private static final long HEARTBEAT_MS = 300_000L;
    private static final int CAP = 100_000;
    private static final long DETAIL_LIMIT = 20L;
    private static final int SOURCE_LIMIT = 8;

    record Entry(Engine engine, GameEntity entity, boolean add) {}

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pending = new AtomicInteger();
    private static final AtomicInteger maxPending = new AtomicInteger();
    private static final AtomicLong deferredAdd = new AtomicLong();
    private static final AtomicLong deferredRemove = new AtomicLong();
    private static final AtomicLong live = new AtomicLong();
    private static final AtomicLong drained = new AtomicLong();
    private static final AtomicLong cancelled = new AtomicLong();
    private static final AtomicLong overflow = new AtomicLong();
    private static final AtomicLong drainFailed = new AtomicLong();
    private static final AtomicLong nullAdd = new AtomicLong();
    private static final AtomicLong nullRemove = new AtomicLong();
    private static final AtomicLong failedAdd = new AtomicLong();
    private static final AtomicLong failedRemove = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();
    private static final Map<String, AtomicLong> sources = new ConcurrentHashMap<>();
    private static final AtomicLong sourcesOther = new AtomicLong();

    // 以下只在主執行緒。
    private static long lastBeat;
    private static boolean started;
    static String lastBeatLine;

    private MdcEntityEngineGuard() {
    }

    static boolean parse(String raw) {
        if (raw == null) {
            return true;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("0") || v.equals("off"));
    }

    /** -1＝mainThread 未設定（照原版）、0＝主執行緒、1＝其他執行緒。 */
    private static int where() {
        Thread main = GameServer.mainThread;
        return main == null ? -1 : Thread.currentThread() == main ? 0 : 1;
    }

    // ---- W105 ----

    /** {@code RegisterEntity} 內 {@code Engine.addEntity(GameEntity)Z} 的改道（receiver 前置）；原版恆回 true。 */
    public static boolean addEntity(Engine engine, GameEntity entity) {
        if (DEFER && engine != null) {
            int w = where();
            if (w == 1 && enqueue(engine, entity, true)) {
                return true;
            }
            if (w == 0) {
                drain();
            }
        }
        return engine.addEntity(entity);
    }

    /** {@code UnregisterEntity(GameEntity,Z)} 內 {@code Engine.removeEntity(GameEntity)V} 的改道（receiver 前置）。 */
    public static void removeEntity(Engine engine, GameEntity entity) {
        if (DEFER && engine != null) {
            int w = where();
            if (w == 1 && enqueue(engine, entity, false)) {
                return;
            }
            if (w == 0) {
                drain();
            }
        }
        engine.removeEntity(entity);
    }

    /** {@code GameEntityManager.Update} 內唯一 {@code Engine.update()} 的改道：主執行緒先排空、心跳，再跑原版（原版在 try 外）。 */
    public static void update(Engine engine) {
        if ((DEFER || GUARD) && where() == 0) {
            if (DEFER) {
                drain();
            }
            beatIfDue();
        }
        engine.update();
    }

    /** {@code GameEntityManager.Save} 頭部：存 MetaEntity 前把背景執行緒排進來的登記補上。 */
    public static void beforeSave() {
        if (DEFER && where() == 0) {
            drain();
        }
    }

    private static boolean enqueue(Engine engine, GameEntity entity, boolean add) {
        int n = pending.incrementAndGet();
        if (n > CAP) {
            pending.decrementAndGet();
            overflow.incrementAndGet();
            return false;
        }
        if (!add) {
            // 背景「先加後移」：待加入取消，移除照排（移除不在 engine 的 entity 原版是 no-op）
            for (Entry q : QUEUE) {
                if (q.add() && q.engine() == engine && q.entity() == entity && QUEUE.remove(q)) {
                    pending.decrementAndGet();
                    cancelled.incrementAndGet();
                }
            }
        }
        QUEUE.add(new Entry(engine, entity, add));
        maxPending.accumulateAndGet(n, Math::max);
        long d = (add ? deferredAdd : deferredRemove).incrementAndGet();
        // 讀別的執行緒寫的旗標只供診斷：true＝這個 entity 當下在 engine（或排隊加入），背景還在動它的元件
        boolean isLive = !add && entity != null && (entity.addedToEngine || entity.scheduledDelayedAddToEngine);
        long l = isLive ? live.incrementAndGet() : 0L;
        if (d <= DETAIL_LIMIT || d % 1000 == 0 || add || (isLive && l <= DETAIL_LIMIT)) {
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
                if (d <= DETAIL_LIMIT || d % 1000 == 0 || isLive) {
                    DebugLog.log(TAG + "deferred " + (add ? "add" : "remove") + " n=" + d + (isLive ? " live" : "")
                            + " thread=" + Thread.currentThread().getName() + " entity=" + describe(entity) + " via=" + via);
                }
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
        return true;
    }

    /** 依 FIFO 呼叫原版；逐筆接住 RuntimeException（背景呼叫端該收的例外不落到不相干的主執行緒呼叫者）。可重入。 */
    static void drain() {
        if (pending.get() == 0) {
            return;
        }
        Entry q;
        while ((q = QUEUE.poll()) != null) {
            pending.decrementAndGet();
            drained.incrementAndGet();
            try {
                if (q.add()) {
                    q.engine().addEntity(q.entity());
                } else {
                    q.engine().removeEntity(q.entity());
                }
            } catch (RuntimeException ex) {
                long n = drainFailed.incrementAndGet();
                report("drain " + (q.add() ? "add" : "remove") + " failed n=" + n, q.entity(), ex, n);
            }
        }
    }

    // ---- W106 ----

    /** {@code processPendingOperations} 內 {@code addEntityInternal} 的改道（receiver 前置）。 */
    public static void applyAdd(EngineEntityManager manager, GameEntity entity) {
        if (!GUARD) {
            manager.addEntityInternal(entity);
            return;
        }
        if (entity == null) {
            long n = nullAdd.incrementAndGet();
            report("skipped null add n=" + n, null, null, n);
            return;
        }
        try {
            manager.addEntityInternal(entity);
        } catch (RuntimeException ex) {
            // 「already registered」在原版清旗標之前就拋；不清，之後的 removeEntity 會走取消分支而不真的移除
            entity.scheduledDelayedAddToEngine = false;
            long n = failedAdd.incrementAndGet();
            report("add failed n=" + n, entity, ex, n);
        }
    }

    /** {@code processPendingOperations} 內兩個 {@code removeEntityInternal} 的改道（RemoveAll 那個是死碼，SmokeCheck 鎖）。 */
    public static void applyRemove(EngineEntityManager manager, GameEntity entity) {
        if (!GUARD) {
            manager.removeEntityInternal(entity);
            return;
        }
        if (entity == null) {
            long n = nullRemove.incrementAndGet();
            report("skipped null remove n=" + n, null, null, n);
            return;
        }
        try {
            manager.removeEntityInternal(entity);
        } catch (RuntimeException ex) {
            if (entity.removingFromEngine) {
                // 補完原版尾段；不補，這個 entity 永遠加不回 bucket
                entity.setComponentOperationHandler(null);
                entity.removingFromEngine = false;
                entity.addedToEngine = false;
            }
            long n = failedRemove.incrementAndGet();
            report("remove failed n=" + n, entity, ex, n);
        }
    }

    // ---- 記錄 ----

    private static void report(String what, GameEntity entity, RuntimeException ex, long n) {
        if (n > DETAIL_LIMIT && n % 1000 != 0) {
            return;
        }
        try {
            StringBuilder s = new StringBuilder(TAG).append(what).append(" thread=").append(Thread.currentThread().getName());
            if (entity != null) {
                s.append(" entity=").append(describe(entity));
            }
            if (ex != null) {
                s.append(" ex=").append(ex);
                StackTraceElement[] st = ex.getStackTrace();
                for (int i = 0; i < st.length && i < 8; i++) {
                    s.append(i == 0 ? " at " : " < ").append(st[i]);
                }
            } else {
                s.append("（佇列裡的操作 entity 為 null：背景執行緒寫過操作池；那個 entity 的旗標可能卡住，建議下個重啟窗口重啟）");
            }
            DebugLog.log(s.toString());
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    private static String describe(GameEntity entity) {
        return entity == null ? "null" : entity.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(entity));
    }

    private static void beatIfDue() {
        try {
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                lastBeat = now;
                DebugLog.log(TAG + "首次生效 defer=" + (DEFER ? "on" : "off") + " guard=" + (GUARD ? "on" : "off")
                        + " raw=" + DEFER_RAW + "/" + GUARD_RAW
                        + "（-Dmdc.entityEngineDefer／-Dmdc.entityOpGuard=0|off 各自回原版；心跳每 5 分鐘一行）");
            } else if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    private static void beat() {
        String line = "beat defer=" + (DEFER ? "on" : "off") + " guard=" + (GUARD ? "on" : "off")
                + " deferred=" + deferredAdd.get() + "/" + deferredRemove.get() + " live=" + live.get()
                + " drained=" + drained.get() + " cancelled=" + cancelled.get() + " pending=" + pending.get()
                + " maxPending=" + maxPending.get() + " overflow=" + overflow.get() + " drainFailed=" + drainFailed.get()
                + " nullOps=" + nullAdd.get() + "/" + nullRemove.get() + " opFailed=" + failedAdd.get() + "/" + failedRemove.get()
                + " anomalies=" + anomalies.get() + " sources=[" + sources() + "]";
        lastBeatLine = line;
        try {
            DebugLog.log(TAG + line);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    private static String sources() {
        StringBuilder src = new StringBuilder();
        sources.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(en -> src.append(src.isEmpty() ? "" : " | ").append(en.getValue().get()).append('×').append(en.getKey()));
        if (sourcesOther.get() > 0) {
            src.append(src.isEmpty() ? "" : " | ").append("other×").append(sourcesOther.get());
        }
        return src.toString();
    }

    private static String callers() {
        return StackWalker.getInstance().walk(frames -> {
            StringBuilder s = new StringBuilder();
            frames.filter(f -> f.getClassName().startsWith("zombie.") && !f.getClassName().startsWith("zombie.mdc.")
                            && !f.getClassName().equals(MdcEntityEngineGuard.class.getName()))
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
    static long deferredAddForTest() { return deferredAdd.get(); }
    static long deferredRemoveForTest() { return deferredRemove.get(); }
    static long drainedForTest() { return drained.get(); }
    static long cancelledForTest() { return cancelled.get(); }
    static long liveForTest() { return live.get(); }
    static long drainFailedForTest() { return drainFailed.get(); }
    static long nullRemoveForTest() { return nullRemove.get(); }
    static long nullAddForTest() { return nullAdd.get(); }
    static long failedAddForTest() { return failedAdd.get(); }
    static long failedRemoveForTest() { return failedRemove.get(); }
    static long anomaliesForTest() { return anomalies.get(); }
}
