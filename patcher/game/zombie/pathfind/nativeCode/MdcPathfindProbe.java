package zombie.pathfind.nativeCode;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

import zombie.ai.astar.Mover;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;

/**
 * W74 尋路執行緒觀測（2026-10-25；docs/patches.md 2ci）。純觀測：回傳值、例外、呼叫次數與順序都與原版相同。
 *
 * <p><b>原版</b>：{@code PathfindNativeThread} 每輪（{@code Sync} 週期 50 ms）先執行 chunk／格子／載具任務與
 * 請求任務，再 {@code PathfindNative.update()}，最多處理 2 個請求（{@code requestsPerUpdate = 2}）。native 端
 * 任何 chunk／格子／載具任務都會設起全域重建旗標，下一次 native {@code findPath} 一進門就重建全部可見圖與載具 cluster。
 * 正式服這條執行緒 99–100% CPU，但不知道每次尋路多貴、重建佔多少、誰在排隊、等多久。
 *
 * <p><b>手術</b>（全在 {@code zombie.pathfind.nativeCode}，同形 1:1 改道，receiver 前置）：
 * {@code PathfindNativeThread.updateThread} 的 8 個 {@code ConcurrentLinkedQueue.poll} 與唯一 {@code PathfindNative.update()}
 * 改道到 W81 {@link MdcPathfindGate}，再由它呼叫 {@link #poll}（依取出的任務類別計 chunk／格子／載具）與 {@link #update}
 * （輪次邊界、每分鐘一行，行尾併 W81 欄位）；
 * {@code PathfindNativeThread.findPath(PathFindRequest)} 唯一 native 入口 → {@link #findPath}（計時、成敗、請求者、排隊時間、
 * 同一個 mover 對同一目標格連續失敗 {@code refail}）；
 * {@code PathRequestTask.execute} 唯一 {@code addRequest} → {@link #addRequest}（入隊時間）。
 *
 * <p><b>重建分組</b>：native 旗標在任何世界變動任務後成立、下一次 native findPath 後清除。本類以「上次 native findPath
 * 之後是否執行過世界變動任務」把每次呼叫分成 {@code rb}（付重建）與 {@code nr}（不付）兩組；
 * {@code rebuildPct ≈ rb.n × (rb 平均 − nr 平均) ÷ 總耗時}。native 對過期 chunk（loadId 不符）不設旗標，所以 rb 次數是上界；
 * 高階區域在之後第一次被搜尋碰到時才重算、可能落在 nr，所以兩組平均的差是重建成本的下界。
 *
 * <p>所有簿記只在尋路執行緒（單寫者），log 在同一執行緒以 {@code DebugLog.log} 寫出（與 ChunkSaveIsolation、
 * ChunkWriteGuard 等非主執行緒 helper 相同）。簿記失敗只計 {@code anomalies}。
 * kill switch {@code -Dmdc.pathfindProbe=0|off}：四個入口都只委派原版，不讀時鐘、不計數。
 */
public final class MdcPathfindProbe {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.pathfindProbe"));
    static final long BEAT_NS = 60_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][PathfindProbe] ";
    /** 入隊時間表上限；被取消、永遠輪不到 native 的請求會留在表裡，滿了整表清掉並計 {@code waitDropped}。 */
    static final int WAIT_CAP = 8192;
    /** {@code refail} 的 mover → 上次失敗目標格表上限；滿了整表清掉。 */
    static final int REFAIL_CAP = 10_000;

    static final int CAT_PLAYER = 0;
    static final int CAT_AGGRO_ZOMBIE = 1;
    static final int CAT_ZOMBIE = 2;
    static final int CAT_ANIMAL = 3;
    static final int CAT_OTHER = 4;
    static final String[] CAT_NAMES = {"player", "aggroZombie", "zombie", "animal", "other"};

    // ---- 尋路執行緒單寫 ----
    private static final IdentityHashMap<PathFindRequest, Long> ENQUEUED = new IdentityHashMap<>();
    private static final IdentityHashMap<Mover, Long> LAST_FAIL = new IdentityHashMap<>();
    private static PathFindRequest lastRequest;
    private static boolean pendingWorld;
    private static int roundChunk;
    private static int roundSquare;
    private static int roundVehicle;
    private static long lastUpdateNs;
    private static boolean announced;
    private static long windowStartNs;

    // 視窗（每次 beat 歸零）
    private static long rounds;
    private static long roundNsSum;
    private static long timedRounds;
    private static long roundNsMax;
    private static long worldRounds;
    private static long chunkTasks;
    private static long squareTasks;
    private static long vehicleTasks;
    private static long chunkUTasks;
    private static long chunkRTasks;
    private static long refails;
    private static long requestTasks;
    private static long otherTasks;
    private static final long[] ENQ = new long[3];
    private static long calls;
    private static long extra;
    private static long fails;
    private static long thrown;
    private static long nsSum;
    private static long nsMax;
    private static final Hist ALL = new Hist();
    private static final Hist RB = new Hist();
    private static final Hist NR = new Hist();
    private static long rbNs;
    private static long nrNs;
    private static final long[] CAT_N = new long[5];
    private static final long[] CAT_FAIL = new long[5];
    private static final long[] CAT_NS = new long[5];
    private static final long[] CAT_WAIT_NS = new long[5];
    private static final Hist[] CAT_WAIT = {new Hist(), new Hist(), new Hist(), new Hist(), new Hist()};
    private static long waitMax;

    // 累計（不歸零）
    private static long totalCalls;
    private static long waitDropped;
    private static long anomalies;

    private MdcPathfindProbe() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** 取代 {@code PathfindNativeThread.updateThread} 內 8 個 {@code ConcurrentLinkedQueue.poll()}。 */
    public static Object poll(ConcurrentLinkedQueue<?> queue) {
        Object task = queue.poll();
        if (ENABLED && task != null) {
            if (task instanceof ChunkUpdateTask) {
                roundChunk++;
                chunkUTasks++;
            } else if (task instanceof ChunkRemoveTask) {
                roundChunk++;
                chunkRTasks++;
            } else if (task instanceof SquareUpdateTask) {
                roundSquare++;
            } else if (task instanceof VehicleUpdateTask || task instanceof VehicleAddTask
                    || task instanceof VehicleRemoveTask) {
                roundVehicle++;
            } else if (task instanceof PathRequestTask) {
                requestTasks++;
            } else {
                otherTasks++;
            }
        }
        return task;
    }

    /** 取代 {@code PathRequestTask.execute} 內的 {@code PathfindNativeThread.addRequest}（請求移入優先佇列的時刻）。 */
    public static void addRequest(PathfindNativeThread thread, PathFindRequest request, int queueNumber) {
        if (ENABLED) {
            try {
                ENQ[queueNumber == 1 ? 0 : queueNumber == 2 ? 1 : 2]++;
                if (ENQUEUED.size() >= WAIT_CAP) {
                    ENQUEUED.clear();
                    waitDropped++;
                }
                ENQUEUED.put(request, System.nanoTime());
                if (request == lastRequest) {
                    lastRequest = null;
                }
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        thread.addRequest(request, queueNumber);
    }

    /** 取代 {@code PathfindNativeThread.updateThread} 內唯一的 {@code PathfindNative.update()}：簿記在前、原版在後。 */
    public static void update() {
        if (ENABLED) {
            try {
                closeRound(System.nanoTime());
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        PathfindNative.update();
    }

    /** 取代 {@code PathfindNativeThread.findPath(PathFindRequest)} 內的 {@code PathfindNative.findPath}。 */
    public static int findPath(PathfindNative pathfind, PathFindRequest request, ByteBuffer pathBb, boolean render) {
        if (!ENABLED) {
            return pathfind.findPath(request, pathBb, render);
        }
        long t0 = System.nanoTime();
        int result;
        try {
            result = pathfind.findPath(request, pathBb, render);
        } catch (Throwable t) {
            thrown++;
            throw t;
        }
        long t1 = System.nanoTime();
        try {
            record(request, result, t0, t1);
        } catch (RuntimeException e) {
            anomalies++;
        }
        return result;
    }

    private static void record(PathFindRequest request, int result, long t0, long t1) {
        long ns = t1 - t0;
        boolean rebuild = pendingWorld;
        pendingWorld = false;
        totalCalls++;
        calls++;
        nsSum += ns;
        if (ns > nsMax) {
            nsMax = ns;
        }
        ALL.add(ns);
        if (rebuild) {
            RB.add(ns);
            rbNs += ns;
        } else {
            NR.add(ns);
            nrNs += ns;
        }
        boolean failed = result != 1;
        if (failed) {
            fails++;
        }
        if (request == lastRequest) {
            // findShortestPathOfMultiple 對同一個請求的第 2 個以後目標
            extra++;
            return;
        }
        lastRequest = request;
        if (failed) {
            countRefail(request);
        }
        int cat = category(request.mover);
        CAT_N[cat]++;
        CAT_NS[cat] += ns;
        if (failed) {
            CAT_FAIL[cat]++;
        }
        Long enqueued = ENQUEUED.remove(request);
        if (enqueued != null) {
            long wait = t0 - enqueued;
            CAT_WAIT_NS[cat] += wait;
            CAT_WAIT[cat].add(wait);
            if (wait > waitMax) {
                waitMax = wait;
            }
        }
    }

    /** 同一個 mover 這次失敗的目標格與它上一次失敗的目標格相同就計一次（只觀測，W81）。 */
    private static void countRefail(PathFindRequest request) {
        long cell = ((long) (int) Math.floor(request.targetX) << 40)
                ^ ((long) ((int) Math.floor(request.targetY) & 0xFFFFFF) << 16)
                ^ ((int) Math.floor(request.targetZ) & 0xFFFF);
        if (LAST_FAIL.size() >= REFAIL_CAP) {
            LAST_FAIL.clear();
        }
        Long previous = LAST_FAIL.put(request.mover, cell);
        if (previous != null && previous == cell) {
            refails++;
        }
    }

    static int category(Mover mover) {
        if (mover instanceof IsoAnimal) {
            return CAT_ANIMAL;
        }
        if (mover instanceof IsoPlayer) {
            return CAT_PLAYER;
        }
        if (mover instanceof IsoZombie zombie) {
            return zombie.target != null ? CAT_AGGRO_ZOMBIE : CAT_ZOMBIE;
        }
        return CAT_OTHER;
    }

    private static void closeRound(long now) {
        int world = roundChunk + roundSquare + roundVehicle;
        if (world > 0) {
            worldRounds++;
            pendingWorld = true;
            chunkTasks += roundChunk;
            squareTasks += roundSquare;
            vehicleTasks += roundVehicle;
        }
        roundChunk = 0;
        roundSquare = 0;
        roundVehicle = 0;
        rounds++;
        if (lastUpdateNs != 0L) {
            long roundNs = now - lastUpdateNs;
            roundNsSum += roundNs;
            timedRounds++;
            if (roundNs > roundNsMax) {
                roundNsMax = roundNs;
            }
        }
        lastUpdateNs = now;
        if (!announced) {
            announced = true;
            windowStartNs = now;
            DebugLog.log(TAG + "首次生效（純觀測，每 " + BEAT_NS / 1_000_000_000L
                    + " 秒一行；-Dmdc.pathfindProbe=0|off 停用）");
        } else if (now - windowStartNs >= BEAT_NS) {
            String line = formatWindow(now);
            resetWindow(now);
            DebugLog.log(line);
        }
    }

    /** 一行視窗統計；時間單位寫在欄位名（us／ms），p95 是四分之一倍頻桶的上界（不超過最大值）。 */
    static String formatWindow(long now) {
        long windowNs = Math.max(1L, now - windowStartNs);
        long timed = Math.max(1L, timedRounds);
        StringBuilder sb = new StringBuilder(640);
        sb.append(TAG).append("beat sec=").append(fixed(windowNs / 1e9, 1))
                .append(" rounds=").append(rounds)
                .append(" roundMsAvg=").append(fixed(roundNsSum / 1e6 / timed, 1))
                .append(" roundMsMax=").append(roundNsMax / 1_000_000L)
                .append(" worldRounds=").append(worldRounds)
                .append(" chunk=").append(chunkTasks)
                .append(" square=").append(squareTasks)
                .append(" vehicle=").append(vehicleTasks)
                .append(" enq=").append(ENQ[0]).append('/').append(ENQ[1]).append('/').append(ENQ[2])
                .append(" calls=").append(calls)
                .append(" extra=").append(extra)
                .append(" fail=").append(fails)
                .append(" thrown=").append(thrown)
                .append(" busyPct=").append(fixed(nsSum * 100.0 / windowNs, 1))
                .append(" usAvg=").append(avgUs(nsSum, calls))
                .append(" usP95=").append(ALL.p95Us(nsMax))
                .append(" usMax=").append(nsMax / 1000L)
                .append(" rb=").append(RB.n).append('/').append(avgUs(rbNs, RB.n)).append('/').append(RB.p95Us(nsMax))
                .append(" nr=").append(NR.n).append('/').append(avgUs(nrNs, NR.n)).append('/').append(NR.p95Us(nsMax))
                .append(" rebuildPct=").append(rebuildPct());
        Hist waitAll = new Hist();
        long waitN = 0L;
        long waitNs = 0L;
        for (int c = 0; c < CAT_NAMES.length; c++) {
            waitAll.addAll(CAT_WAIT[c]);
            waitN += CAT_WAIT[c].n;
            waitNs += CAT_WAIT_NS[c];
        }
        sb.append(" waitMs=").append(avgMs(waitNs, waitN)).append('/').append(waitAll.p95Us(waitMax) / 1000L)
                .append('/').append(waitMax / 1_000_000L);
        for (int c = 0; c < CAT_NAMES.length; c++) {
            sb.append(' ').append(CAT_NAMES[c]).append('=').append(CAT_N[c]).append('/').append(CAT_FAIL[c])
                    .append('/').append(avgUs(CAT_NS[c], CAT_N[c]))
                    .append('/').append(avgMs(CAT_WAIT_NS[c], CAT_WAIT[c].n))
                    .append('/').append(CAT_WAIT[c].p95Us(waitMax) / 1000L);
        }
        sb.append(" otherTasks=").append(otherTasks)
                .append(" waitDropped=").append(waitDropped)
                .append(" anomalies=").append(anomalies)
                .append(" total=").append(totalCalls);
        MdcPathfindGate.appendBeat(sb);
        sb.append(" chunkU=").append(chunkUTasks)
                .append(" chunkR=").append(chunkRTasks)
                .append(" refail=").append(refails);
        return sb.toString();
    }

    private static String rebuildPct() {
        if (RB.n == 0L || NR.n == 0L || nsSum == 0L) {
            return "n/a";
        }
        double extraNs = Math.max(0.0, (double) rbNs / RB.n - (double) nrNs / NR.n) * RB.n;
        return fixed(extraNs * 100.0 / nsSum, 1);
    }

    private static long avgUs(long ns, long n) {
        return n == 0L ? 0L : ns / n / 1000L;
    }

    private static long avgMs(long ns, long n) {
        return n == 0L ? 0L : ns / n / 1_000_000L;
    }

    private static String fixed(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    private static void resetWindow(long now) {
        windowStartNs = now;
        rounds = 0L;
        roundNsSum = 0L;
        timedRounds = 0L;
        roundNsMax = 0L;
        worldRounds = 0L;
        chunkTasks = 0L;
        squareTasks = 0L;
        vehicleTasks = 0L;
        chunkUTasks = 0L;
        chunkRTasks = 0L;
        refails = 0L;
        MdcPathfindGate.resetWindow();
        requestTasks = 0L;
        otherTasks = 0L;
        java.util.Arrays.fill(ENQ, 0L);
        calls = 0L;
        extra = 0L;
        fails = 0L;
        thrown = 0L;
        nsSum = 0L;
        nsMax = 0L;
        ALL.clear();
        RB.clear();
        NR.clear();
        rbNs = 0L;
        nrNs = 0L;
        java.util.Arrays.fill(CAT_N, 0L);
        java.util.Arrays.fill(CAT_FAIL, 0L);
        java.util.Arrays.fill(CAT_NS, 0L);
        java.util.Arrays.fill(CAT_WAIT_NS, 0L);
        for (Hist h : CAT_WAIT) {
            h.clear();
        }
        waitMax = 0L;
    }

    /** 對數直方圖：每倍頻 4 桶（微秒），p95 誤差 ≤ 25%。 */
    static final class Hist {
        static final int BUCKETS = 161;
        final long[] counts = new long[BUCKETS];
        long n;

        static int bucket(long us) {
            if (us < 1L) {
                return 0;
            }
            int e = 63 - Long.numberOfLeadingZeros(us);
            int sub = e >= 2 ? (int) ((us >>> (e - 2)) & 3L) : (int) ((us << (2 - e)) & 3L);
            return Math.min(BUCKETS - 1, 1 + 4 * e + sub);
        }

        static long upperUs(int bucket) {
            if (bucket == 0) {
                return 1L;
            }
            int e = (bucket - 1) / 4;
            int sub = (bucket - 1) % 4;
            return Math.max(1L, ((5L + sub) << e) >> 2);
        }

        void add(long ns) {
            counts[bucket(ns / 1000L)]++;
            n++;
        }

        void addAll(Hist other) {
            for (int i = 0; i < BUCKETS; i++) {
                counts[i] += other.counts[i];
            }
            n += other.n;
        }

        void clear() {
            java.util.Arrays.fill(counts, 0L);
            n = 0L;
        }

        long p95Us(long maxNs) {
            if (n == 0L) {
                return 0L;
            }
            long target = n - n / 20L;
            long seen = 0L;
            for (int i = 0; i < BUCKETS; i++) {
                seen += counts[i];
                if (seen >= target) {
                    return Math.min(upperUs(i), Math.max(1L, maxNs / 1000L));
                }
            }
            return maxNs / 1000L;
        }
    }

    // ---- 測試存取器 ----
    static long callsForTest() {
        return calls;
    }

    static long totalCallsForTest() {
        return totalCalls;
    }

    static long extraForTest() {
        return extra;
    }

    static long failsForTest() {
        return fails;
    }

    static long thrownForTest() {
        return thrown;
    }

    static long roundsForTest() {
        return rounds;
    }

    static long worldRoundsForTest() {
        return worldRounds;
    }

    static long[] worldTasksForTest() {
        return new long[] {chunkTasks, squareTasks, vehicleTasks, requestTasks, otherTasks};
    }

    static long[] chunkSplitForTest() {
        return new long[] {chunkUTasks, chunkRTasks};
    }

    static long refailsForTest() {
        return refails;
    }

    static int refailMapSizeForTest() {
        return LAST_FAIL.size();
    }

    static long[] enqForTest() {
        return ENQ.clone();
    }

    static long rbForTest() {
        return RB.n;
    }

    static long nrForTest() {
        return NR.n;
    }

    static long catForTest(int cat) {
        return CAT_N[cat];
    }

    static long catFailForTest(int cat) {
        return CAT_FAIL[cat];
    }

    static long catWaitsForTest(int cat) {
        return CAT_WAIT[cat].n;
    }

    static int enqueuedForTest() {
        return ENQUEUED.size();
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static boolean announcedForTest() {
        return announced;
    }

    static String windowForTest() {
        return formatWindow(System.nanoTime());
    }

    static void resetWindowForTest() {
        resetWindow(System.nanoTime());
    }
}
