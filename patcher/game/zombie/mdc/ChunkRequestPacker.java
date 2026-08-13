package zombie.mdc;

import java.util.List;

import zombie.debug.DebugLog;
import zombie.network.ClientChunkRequest;
import zombie.network.PlayerDownloadServer;

/**
 * W4-1 chunk 供給併包（黑邊根因修復，2026-08-13 Wayne 案定罪）。
 *
 * <p><b>問題</b>：vanilla 的 chunk 供給只跑到設計值的 15%。三段鏈路：
 * <ol>
 *   <li>client {@code WorldStreamer.updateMain} 每幀送一次 RequestZipList，每包僅約 3 個 chunk；</li>
 *   <li>{@code RequestZipListPacket.parse} <b>每個封包無條件 new 一個 ClientChunkRequest</b> 入列，
 *       從不併入佇列尾端未滿的 ccr（只有同一封包內超過 20 個才換新 ccr）；</li>
 *   <li>{@code PlayerDownloadServer.update()} 每個 worker 週期<b>只處理一個 ccr</b>（10Hz 節流）。</li>
 * </ol>
 * 結果：實際約 30 chunk/s，而 {@code NON_LARGE_AREA_CHUNKS_LIMIT}=20 × 10Hz = 200 chunk/s
 * 的預算被浪費 85%。積壓越過 client 端逾時後 client 丟棄已送達資料並重發且不通知 server
 * 取消，形成自我維持的 livelock（實測 pending 恆＝請求率×8s＝240、18 分鐘燒 105MB 全丟棄、
 * 零 chunk 載入＝永久黑邊）。詳見 docs/patches.md 2p 與 docs/chunk-throughput-design-v1.md。
 *
 * <p><b>掛點（安全關鍵，審查修正）</b>：headCall 掛在 {@code removeOlderDuplicateRequests()V}
 * 而非 {@code update()V}。理由：{@code update()} 對 {@code ccrWaiting} 的所有存取都包在
 * {@code if (workerThread.ready)} 內，那是 vanilla 用來與 <b>WorkerThread</b> 互斥的唯一機制
 * ——worker 的 {@code sendArray} 會對 {@code ccrWaiting} 加入 {@code ccrForRetries} 並持續
 * {@code chunks.add()}。插在 {@code update()} 頭部（offset 0）會落在該閘<b>之外</b>，與 worker
 * 同時改同一個 plain ArrayList；最壞情況是同一個 {@code Chunk} 實例同時掛在兩個 ccr、
 * 被雙重 {@code releaseChunk} 進 <b>static</b> 的 {@code freeChunks} 池，造成跨玩家汙染。
 * {@code removeOlderDuplicateRequests()} 全 class 僅被 {@code update()} 呼叫一次（javap 實證）
 * 且就在 ready 閘內、vanilla 去重之前——正是本刀需要的位置。
 *
 * <p><b>修法</b>：把後續 ccr 的 chunk 搬進隊首 ccr，直到隊首達批次上限。不新增任何 chunk、
 * 不改變處理順序、不碰 largeArea 路徑（它有自己的 20/40 擁塞窗）。
 *
 * <p><b>去重語意保留</b>：vanilla 的去重只偵測「跨 ccr」重複（對每個 chunk 掃索引更小的 ccr），
 * 同一 ccr 內看不見。因此併包時遇到隊首已含相同 (wx,wy) 的 chunk 一律<b>跳過不搬</b>，
 * 讓它留在原 ccr，vanilla 去重照原樣運作（含其 sendNotRequired 取消路徑）。
 *
 * <p><b>回收</b>：被搬空的 ccr 留在原位——本 helper 之後緊接執行的 vanilla 去重本體
 * {@code if (ccr1.chunks.isEmpty()) { ccrWaiting.remove(i); freeRequests.add(ccr1); }}
 * 會把它移除並回收進物件池（同一次呼叫內，因為我們就掛在該方法頭部）。
 *
 * <p><b>成本閘（審查修正）</b>：併包會讓 vanilla 在同一幀內序列化更多 chunk
 * （{@code SaveLoadedChunk} 在主執行緒，成本由格數與物件數決定）。因此
 * (a) 批次上限預設保守的 {@link #DEFAULT_BATCH}（非 vanilla 上限 20），
 * (b) 全域每 100ms 視窗有「額外搬移 chunk 數」預算，超出即整段退回 vanilla 行為。
 * 兩者皆可用 JVM 參數調整，不需重新建置：
 * {@code -Dmdc.chunkPacker.batch=N}、{@code -Dmdc.chunkPacker.windowBudget=N}（0＝停用本刀）。
 */
public final class ChunkRequestPacker {

    /** vanilla {@code ClientChunkRequest.NON_LARGE_AREA_CHUNKS_LIMIT}（SmokeCheck 對帳 bipush 20）。 */
    static final int VANILLA_LIMIT = 20;
    /** 首發保守值：先取 vanilla 上限的 40%，觀測一週再決定是否上調。 */
    static final int DEFAULT_BATCH = 8;
    /** 每個 server tick 全域可額外搬移的 chunk 數（≈vanilla 每 tick 240 次序列化的 50%）。 */
    static final int DEFAULT_WINDOW_BUDGET = 120;
    /** 上一個 tick 週期超過此值＝server 已落後，本 tick 完全不發配額（自我節流）。 */
    static final long DEFAULT_OVERRUN_MS = 150L;

    /**
     * tick 邊界偵測門檻。同一 tick 內各連線的 packQueue 是連續呼叫（微秒級間隔），
     * 下一個 tick 相隔約 100ms（GameServer 的 UpdateLimit(100L)）。取 50ms：
     * 誤判方向永遠保守——若某連線自身耗時逼近門檻導致「同 tick 被當成同一個」，
     * 結果是<b>少發</b>配額而非多發。
     */
    private static final long TICK_GAP_NS = 50_000_000L;
    private static final long LOG_INTERVAL_NS = 300_000_000_000L;
    private static final int MAX_ANOMALY_TRACES = 3;

    private static final int BATCH = clamp(
            Integer.getInteger("mdc.chunkPacker.batch", DEFAULT_BATCH), 0, VANILLA_LIMIT);
    private static final int WINDOW_BUDGET = Math.max(0,
            Integer.getInteger("mdc.chunkPacker.windowBudget", DEFAULT_WINDOW_BUDGET));
    private static final long OVERRUN_NS = Math.max(0L,
            Long.getLong("mdc.chunkPacker.overrunMs", DEFAULT_OVERRUN_MS)) * 1_000_000L;

    private static long calls;           // 進入 packList 的總次數（含早退，審查修正：不再有偏）
    private static long skipShort;       // 佇列 <2
    private static long skipLarge;       // 隊首 largeArea
    private static long skipFull;        // 隊首已達批次上限
    private static long skipBudget;      // 視窗預算用罄
    private static long packedCcrs;
    private static long mergedChunks;
    private static long headSizeSum;     // 僅在實際走完併包時累計
    private static long headSizeSamples;
    private static long anomalies;
    private static long anomalyTraces;

    private static long skipDupAbort;    // 掃描範圍內存在重複→整次放棄併包（順序安全）
    private static long overrunTicks;    // 偵測到上個 tick 超時而停發配額的次數

    private static long lastCallNs;
    private static long tickStartNs;
    private static int budgetLeftThisTick;
    private static boolean tickPrimed;

    private static long lastLogNs;
    private static boolean logPrimed;

    private ChunkRequestPacker() {}

    /**
     * {@code removeOlderDuplicateRequests()} 頭部掛點（在 vanilla 的 ready 閘內）。
     * 非 fatal 例外一律吞掉——最佳化絕不改變 vanilla 的執行結果。
     */
    public static void packQueue(PlayerDownloadServer pds) {
        try {
            PatchInfo.announceOnce();     // 版本橫幅（冪等；此路徑每 tick 每連線必經）
            packList(pds.ccrWaiting);
            maybeLog();
        } catch (Throwable t) {
            rethrowFatal(t);
            anomalies++;
            if (anomalyTraces < MAX_ANOMALY_TRACES) {
                anomalyTraces++;
                DebugLog.log("[MinidoracatJavaPatch][ChunkPacker] anomaly #" + anomalies + ": " + t);
                for (StackTraceElement e : t.getStackTrace()) {
                    DebugLog.log("[MinidoracatJavaPatch][ChunkPacker]     at " + e);
                }
            }
        }
    }

    /** 併包本體（package-private：行為測試以純 List 直接呼叫，production 只經 packQueue）。 */
    static void packList(List<ClientChunkRequest> queue) {
        calls++;
        if (BATCH <= 0 || WINDOW_BUDGET <= 0) {
            return;                                   // JVM 參數停用
        }
        if (queue == null || queue.size() < 2) {
            skipShort++;
            return;
        }
        ClientChunkRequest head = queue.get(0);
        if (head == null || head.largeArea) {          // largeArea 有自己的擁塞窗，不介入
            skipLarge++;
            return;
        }
        List<ClientChunkRequest.Chunk> headChunks = head.chunks;
        int before = headChunks.size();
        if (before >= BATCH) {
            skipFull++;
            return;
        }

        int budget = takeBudget(BATCH - before);
        if (budget <= 0) {
            skipBudget++;
            return;
        }
        int target = Math.min(BATCH, before + budget);

        // 來源掃描上限：最多搬 BATCH 個 chunk，掃 BATCH 個來源 ccr 必然足夠（審查 I2：
        // 否則隊首填不滿時會走完整條佇列並對每個 chunk 做 O(BATCH) 比對）
        int lastSource = Math.min(queue.size(), 1 + BATCH);

        // **先掃描、後變動**（codex 審查的 blocking）：只要預定搬移範圍內存在任何重複座標
        // 就整次放棄。理由：若遇到重複「跳過後繼續搬」，後面的 chunk 會越過較新的重複項
        // （leapfrog）＝改變處理順序；若「停在重複處」，則同一來源 ccr 內部的重複會被拆成
        // 跨 ccr 重複，觸發 vanilla 額外的 sendNotRequired(false)＝client 刪除本機 chunk 檔。
        // 兩者都改變 vanilla 行為。放棄是安全且自癒的：vanilla 去重本體緊接在本 helper 之後
        // 執行，會清掉跨 ccr 重複，下一個 tick 就能正常併包。
        if (hasDuplicateInRange(queue, head, lastSource, target)) {
            returnBudget(budget);
            skipDupAbort++;
            return;
        }

        for (int i = 1; i < lastSource && headChunks.size() < target; i++) {
            ClientChunkRequest src = queue.get(i);
            if (src == null || src.largeArea) {
                break;
            }
            List<ClientChunkRequest.Chunk> srcChunks = src.chunks;
            while (!srcChunks.isEmpty() && headChunks.size() < target) {
                headChunks.add(srcChunks.remove(0));
            }
        }

        int after = headChunks.size();
        int merged = after - before;
        if (merged > 0) {
            packedCcrs++;
            mergedChunks += merged;
        }
        returnBudget(budget - merged);
        headSizeSum += after;
        headSizeSamples++;
    }

    /**
     * 掃描（唯讀）預定搬移範圍內是否存在重複座標——包含「來源 vs 隊首」與「來源彼此之間」
     * 以及「同一來源 ccr 內部」。只看實際會被搬到的前 (target-head) 個 chunk。
     */
    private static boolean hasDuplicateInRange(List<ClientChunkRequest> queue,
            ClientChunkRequest head, int lastSource, int target) {
        List<ClientChunkRequest.Chunk> seen = new java.util.ArrayList<>(target);
        seen.addAll(head.chunks);
        int room = target - head.chunks.size();
        for (int i = 1; i < lastSource && room > 0; i++) {
            ClientChunkRequest src = queue.get(i);
            if (src == null || src.largeArea) {
                break;
            }
            List<ClientChunkRequest.Chunk> srcChunks = src.chunks;
            for (int j = 0; j < srcChunks.size() && room > 0; j++, room--) {
                ClientChunkRequest.Chunk c = srcChunks.get(j);
                if (c == null || containsCoord(seen, c.wx, c.wy)) {
                    return true;      // null 也視為異常，一律放棄（保守）
                }
                seen.add(c);
            }
        }
        return false;
    }

    /**
     * 每個 server tick 的全域配額（codex 審查修正）。
     *
     * <p>不用牆鐘視窗——那會在「tick 本身超時」時於 tick 中途重設並重新發配額，
     * 越 overrun 越放行。改以<b>呼叫間隔偵測 tick 邊界</b>（同 tick 內各連線連續呼叫，
     * 下一個 tick 相隔約 100ms），配額一個 tick 只發一次。
     *
     * <p>另加自我節流：上一個 tick 的週期超過 {@code OVERRUN_NS}（預設 150ms＝已落後 1.5 個
     * tick）時，本 tick 完全不發配額，直接退回 vanilla 行為。這是對「本刀造成主迴圈變慢」
     * 的直接負回饋——比單純的數量上限更貼近真正要防的失效模式。
     */
    private static int takeBudget(int wanted) {
        long now = System.nanoTime();
        if (!tickPrimed || now - lastCallNs >= TICK_GAP_NS) {
            long prevTickNs = tickPrimed ? now - tickStartNs : 0L;
            boolean overran = tickPrimed && OVERRUN_NS > 0L && prevTickNs > OVERRUN_NS;
            if (overran) {
                overrunTicks++;
            }
            tickPrimed = true;
            tickStartNs = now;
            budgetLeftThisTick = overran ? 0 : WINDOW_BUDGET;
        }
        lastCallNs = now;
        int take = Math.min(wanted, Math.max(0, budgetLeftThisTick));
        budgetLeftThisTick -= take;
        return take;
    }

    /** 沒用完的配額歸還（來源不足、全是重複而放棄等）。 */
    private static void returnBudget(int unused) {
        if (unused > 0) {
            budgetLeftThisTick += unused;
        }
    }

    private static boolean containsCoord(List<ClientChunkRequest.Chunk> chunks, int wx, int wy) {
        for (int i = 0; i < chunks.size(); i++) {
            ClientChunkRequest.Chunk c = chunks.get(i);
            if (c != null && c.wx == wx && c.wy == wy) {
                return true;
            }
        }
        return false;
    }

    /** 觀測：avgBatchX10 是本刀成效的直接指標（vanilla 約 30＝3.0，目標接近 BATCH×10）。 */
    private static void maybeLog() {
        long now = System.nanoTime();
        if (!logPrimed) {                    // nanoTime 原點可為負，不能用 0 當哨兵
            logPrimed = true;
            lastLogNs = now;
            return;
        }
        if (now - lastLogNs < LOG_INTERVAL_NS) {
            return;
        }
        lastLogNs = now;
        long avgTimes10 = headSizeSamples == 0L ? 0L : headSizeSum * 10L / headSizeSamples;
        DebugLog.log("[MinidoracatJavaPatch][ChunkPacker] batch=" + BATCH
                + " windowBudget=" + WINDOW_BUDGET
                + " calls=" + calls
                + " packed=" + packedCcrs
                + " mergedChunks=" + mergedChunks
                + " avgBatchX10=" + avgTimes10
                + " skip[short=" + skipShort + " large=" + skipLarge
                + " full=" + skipFull + " budget=" + skipBudget
                + " dupAbort=" + skipDupAbort + "]"
                + " overrunTicks=" + overrunTicks
                + " anomalies=" + anomalies);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void rethrowFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof ThreadDeath || t instanceof LinkageError) {
            throw (Error) t;
        }
    }

    // ---- 測試掛點（package-private；production 不呼叫）----
    static void resetForTest() {
        calls = 0;
        skipShort = 0;
        skipLarge = 0;
        skipFull = 0;
        skipBudget = 0;
        skipDupAbort = 0;
        overrunTicks = 0;
        packedCcrs = 0;
        mergedChunks = 0;
        headSizeSum = 0;
        headSizeSamples = 0;
        anomalies = 0;
        anomalyTraces = 0;
        tickPrimed = false;
        budgetLeftThisTick = 0;
    }

    /** calls, packedCcrs, mergedChunks, skipDupAbort, anomalies, skipBudget, overrunTicks */
    static long[] statsForTest() {
        return new long[]{calls, packedCcrs, mergedChunks, skipDupAbort,
                anomalies, skipBudget, overrunTicks};
    }

    /** 測試用：強制視為新 tick（模擬 tick 邊界）。 */
    static void newTickForTest() {
        tickPrimed = false;
    }

    static int batchForTest() {
        return BATCH;
    }

    static int windowBudgetForTest() {
        return WINDOW_BUDGET;
    }
}
