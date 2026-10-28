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
 *   <li>{@link #update} 結算這一輪，再以 {@code now - lastOpen >= hold} 決定下一輪開不開；開服第一輪開。</li>
 * </ul>
 *
 * <p><b>自適應計時（預設）</b>：開閘那輪的重建 {@code findPath} 在 {@link #update} 之後才跑，所以關閘時間從開閘輪結束
 * （下一次結算）才起算，長度 {@code hold = max(T, 2 × 開閘輪耗時)}；開閘輪耗時＝開閘輪結算到下一次結算之間 W74 量到的 native
 * {@code findPath} 累計耗時（含重建，不含輪間 {@code notifier.wait()} 閒置；用牆鐘的話閒置 47 秒就把 hold 撐到 95 秒）。
 * 探針 off 時量不到，hold 固定為 T（仍從開閘輪結束起算）。{@code -Dmdc.pathfindGateAdaptive=0|off}：回到 v1，
 * {@code hold = T}、從決定開閘那一刻起算。
 *
 * <p><b>關閘上限（v2.1）</b>：{@code hold = max(T, min(上限, 2 × 開閘輪耗時))}。{@code -Dmdc.pathfindGateHoldMaxMs=<ms>}：
 * 未設＝10000；{@code 0|off}＝不封頂（＝v2）；1000–600000 照用，其他值與無法解析＝10000；低於 T 等於 T。fixed 計時不起作用。
 *
 * <p>只有尋路執行緒呼叫 {@link #poll}／{@link #update}，狀態單寫者。佇列以物件身分比對（同 package 讀得到 protected 欄位）。
 * {@code -Dmdc.pathfindGateMs=<T>}：未設＝1000；{@code 0|off}＝每輪都開，兩個入口只委派 W74 探針（與原版逐指令等價，
 * 自適應開關不起作用）；0–5000 以外或無法解析＝1000。與 {@code -Dmdc.pathfindProbe} 各自獨立。
 */
public final class MdcPathfindGate {
    static final long DEFAULT_MS = 1000L;
    static final String RAW = System.getProperty("mdc.pathfindGateMs");
    static final long GATE_MS = parseMs(RAW, DEFAULT_MS, 0L, 5000L);
    static final long GATE_NS = GATE_MS * 1_000_000L;
    static final String HOLD_MAX_RAW = System.getProperty("mdc.pathfindGateHoldMaxMs");
    static final long HOLD_MAX_MS = parseMs(HOLD_MAX_RAW, 10_000L, 1000L, 600_000L);
    static final boolean ADAPTIVE = MdcPathfindProbe.parseEnabled(System.getProperty("mdc.pathfindGateAdaptive"));
    private static final String TAG = "[MinidoracatJavaPatch][PathfindGate] ";

    // ---- 尋路執行緒單寫 ----
    static boolean open = true;
    static long lastOpenNs = System.nanoTime();
    static long holdNs = GATE_NS;
    static long holdMaxNs = HOLD_MAX_MS == 0L ? Long.MAX_VALUE : HOLD_MAX_MS * 1_000_000L; // 測試可改
    static boolean lastRoundOpen;
    private static long lastCloseBusyNs;
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
    static long holdNsMax;

    private MdcPathfindGate() {
    }

    /** 未設＝def；{@code 0|off}＝0；[min, max] 照用；其他值與無法解析＝def。 */
    static long parseMs(String raw, long def, long min, long max) {
        if (raw == null) {
            return def;
        }
        String s = raw.trim();
        if ("off".equalsIgnoreCase(s)) {
            return 0L;
        }
        try {
            long v = Long.parseLong(s);
            return v == 0L || v >= min && v <= max ? v : def;
        } catch (NumberFormatException e) {
            return def;
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

    static void closeRound(long now) {
        long roundStart = roundPolled ? roundStartNs : now;
        roundPolled = false;
        if (lastRoundOpen) {
            // 上一輪開閘：它在 update() 之後跑的 findPath（含重建）到這裡已結束
            if (ADAPTIVE) {
                // ponytail: k=2 寫死；hold 有上限，因為 rb 會隨上次重建以來的呼叫數變貴（約 15 ms／呼叫），2×rb 會自我放大
                // （rb 變大→hold 變長→累積呼叫變多→rb 更大）。要更細的話改成依累積呼叫數、而不是時間決定開閘。
                lastOpenNs = now;
                holdNs = Math.max(GATE_NS, Math.min(holdMaxNs, 2L * (MdcPathfindProbe.busyNsTotal - lastCloseBusyNs)));
            }
            if (holdNs > holdNsMax) {
                holdNsMax = holdNs;
            }
        }
        lastRoundOpen = open;
        lastCloseBusyNs = MdcPathfindProbe.busyNsTotal;
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
        open = (!ADAPTIVE || !lastRoundOpen) && decide(now);
        if (!announced) {
            announced = true;
            DebugLog.log(TAG + "首次生效（世界變動每 " + GATE_MS + " ms 最多套用一次；-Dmdc.pathfindGateMs=" + RAW
                    + "；0|off 每輪都套用＝原版）；計時 " + (ADAPTIVE
                    ? "adaptive（關閘 max(T, min(上限, 2×開閘輪耗時))，從開閘輪結束起算）"
                    : "fixed（關閘 T，從決定開閘起算＝v1）") + "；-Dmdc.pathfindGateAdaptive=0|off 回到 fixed；關閘上限 "
                    + (HOLD_MAX_MS == 0L ? "不封頂" : HOLD_MAX_MS + " ms") + (ADAPTIVE ? "" : "（fixed 不起作用）")
                    + "（-Dmdc.pathfindGateHoldMaxMs=" + HOLD_MAX_RAW + "；0|off 不封頂）");
        }
    }

    static boolean decide(long now) {
        if (now - lastOpenNs >= holdNs) {
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
        holdNsMax = 0L;
    }

    // ---- 測試存取器 ----
    static long[] statsForTest() {
        return new long[] {opens, heldRounds, drainedSum, drainedMax, staleNsMax};
    }
}
