package zombie.pathfind.nativeCode;

import java.util.concurrent.ConcurrentLinkedQueue;

import zombie.debug.DebugLog;

/**
 * W81 尋路世界變動批次化閘門（2026-10-26；docs/patches.md 2cp）。
 *
 * <p><b>原版</b>：{@code PathfindNativeThread.updateThread} 每輪先套用 chunk（每輪最多 10 個 {@code ChunkUpdateTask}）、
 * 格子、載具任務，再處理請求。native 的六個寫入 JNI 都會設全域 dirty，下一次 {@code findPath} 一進門就重建全部可見圖與
 * 載具 cluster。正式服 45 人時每一輪都有世界任務，所以每一輪都付一次重建（W74 探針 {@code rb} 次數＝輪數）。
 *
 * <p><b>手術</b>：{@code updateThread} 的 8 個 {@code ConcurrentLinkedQueue.poll} → {@link #poll}、唯一
 * {@code PathfindNative.update()} → {@link #update}（同形 1:1 改道；W74 的 {@link MdcPathfindProbe} 改由這裡呼叫）。
 * 閘門每 T ms 最多開一輪：
 * <ul>
 *   <li>關閉的輪次：chunk／格子／載具三個佇列的 poll 回 null，任務留在佇列（FIFO 不變）；{@code requestTaskQueue} 永遠直通。</li>
 *   <li>開的輪次：第一次 poll chunk 佇列時在這裡把整個佇列照原版迴圈本體（{@code execute}、{@code taskReturnQueue.add}）
 *       排空再回 null，不受原版每輪 10 個的上限；格子與載具原版本來就排空。{@code execute} 丟例外時與原版相同：
 *       該任務已取出、不進 {@code taskReturnQueue}、例外原樣上拋、剩下的留在佇列。</li>
 *   <li>{@link #update} 結算這一輪，再以 {@code now - lastOpen >= T} 決定下一輪開不開；開服第一輪開。</li>
 * </ul>
 *
 * <p>只有尋路執行緒呼叫 {@link #poll}／{@link #update}，狀態單寫者。佇列以物件身分比對（同 package 讀得到 protected 欄位）。
 * {@code -Dmdc.pathfindGateMs=<T>}：未設＝1000；{@code 0|off}＝每輪都開，兩個入口只委派 W74 探針（與原版逐指令等價）；
 * 0–5000 以外或無法解析＝1000。與 {@code -Dmdc.pathfindProbe} 各自獨立。
 */
public final class MdcPathfindGate {
    static final long DEFAULT_MS = 1000L;
    static final String RAW = System.getProperty("mdc.pathfindGateMs");
    static final long GATE_MS = parseMs(RAW);
    static final long GATE_NS = GATE_MS * 1_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][PathfindGate] ";

    // ---- 尋路執行緒單寫 ----
    static boolean open = true;
    static long lastOpenNs = System.nanoTime();
    static boolean announced;
    private static boolean roundPolled;
    private static long roundStartNs;
    private static int roundDrained;
    private static boolean roundHeld;
    private static boolean holding;
    private static long heldSinceNs;

    // 視窗（隨 W74 beat 歸零）
    private static long opens;
    private static long heldRounds;
    private static long drainedSum;
    private static long drainedMax;
    private static long staleNsMax;

    private MdcPathfindGate() {
    }

    static long parseMs(String raw) {
        if (raw == null) {
            return DEFAULT_MS;
        }
        String s = raw.trim();
        if ("off".equalsIgnoreCase(s)) {
            return 0L;
        }
        try {
            long v = Long.parseLong(s);
            return v >= 0L && v <= 5000L ? v : DEFAULT_MS;
        } catch (NumberFormatException e) {
            return DEFAULT_MS;
        }
    }

    /** 取代 {@code PathfindNativeThread.updateThread} 內 8 個 {@code ConcurrentLinkedQueue.poll()}。 */
    public static Object poll(ConcurrentLinkedQueue<?> queue) {
        if (GATE_NS <= 0L) {
            return MdcPathfindProbe.poll(queue);
        }
        if (!roundPolled) {
            // 這輪的開頭：第一次 poll，不含上一輪結束後 notifier.wait() 的閒置
            roundPolled = true;
            roundStartNs = System.nanoTime();
        }
        PathfindNativeThread thread = PathfindNativeThread.instance;
        if (queue == thread.requestTaskQueue || queue.peek() == null) {
            return MdcPathfindProbe.poll(queue);
        }
        if (!open) {
            roundHeld = true;
            return null;
        }
        if (queue != thread.chunkTaskQueue) {
            roundDrained++;
            return MdcPathfindProbe.poll(queue);
        }
        // 原版 chunk 迴圈本體（javap updateThread off 18–29），去掉每輪 10 個的上限。
        for (Object task; (task = MdcPathfindProbe.poll(queue)) != null; ) {
            roundDrained++;
            IPathfindTask t = (IPathfindTask) task;
            t.execute();
            thread.taskReturnQueue.add(t);
        }
        return null;
    }

    /** 取代 {@code PathfindNativeThread.updateThread} 內唯一的 {@code PathfindNative.update()}：結算並決定下一輪，再交給 W74。 */
    public static void update() {
        if (GATE_NS > 0L) {
            closeRound(System.nanoTime());
        }
        MdcPathfindProbe.update();
    }

    private static void closeRound(long now) {
        long roundStart = roundPolled ? roundStartNs : now;
        roundPolled = false;
        if (open) {
            opens++;
            drainedSum += roundDrained;
            if (roundDrained > drainedMax) {
                drainedMax = roundDrained;
            }
            if (holding && roundDrained > 0 && roundStart - heldSinceNs > staleNsMax) {
                staleNsMax = roundStart - heldSinceNs;
            }
            holding = false;
        } else if (roundHeld) {
            heldRounds++;
            if (!holding) {
                // ponytail: 以第一個被擋輪次的開頭近似最舊任務的入列時刻，最多少算一輪
                holding = true;
                heldSinceNs = roundStart;
            }
        }
        roundDrained = 0;
        roundHeld = false;
        open = decide(now);
        if (!announced) {
            announced = true;
            DebugLog.log(TAG + "首次生效（世界變動每 " + GATE_MS + " ms 最多套用一次；-Dmdc.pathfindGateMs=" + RAW
                    + "；0|off 每輪都套用＝原版）");
        }
    }

    static boolean decide(long now) {
        if (now - lastOpenNs >= GATE_NS) {
            lastOpenNs = now;
            return true;
        }
        return false;
    }

    /** 併在 W74 beat 行尾的欄位。 */
    static void appendBeat(StringBuilder sb) {
        sb.append(" gate=").append(GATE_MS)
                .append(" opens=").append(opens)
                .append(" heldRounds=").append(heldRounds)
                .append(" drained=").append(opens == 0L ? 0L : drainedSum / opens).append('/').append(drainedMax)
                .append(" staleMsMax=").append(staleNsMax / 1_000_000L);
    }

    static void resetWindow() {
        opens = 0L;
        heldRounds = 0L;
        drainedSum = 0L;
        drainedMax = 0L;
        staleNsMax = 0L;
    }

    // ---- 測試存取器 ----
    static long[] statsForTest() {
        return new long[] {opens, heldRounds, drainedSum, drainedMax, staleNsMax};
    }
}
