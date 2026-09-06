package zombie.mdc;

import java.io.IOException;
import java.util.List;
import java.util.zip.CRC32;

import zombie.debug.DebugLog;
import zombie.iso.IsoChunk;
import zombie.network.ClientChunkRequest;
import zombie.network.PlayerDownloadServer;

/**
 * W4-1 v2 chunk 供給併包（2026-09-07 復活；docs/patches.md 2p）。
 *
 * <p><b>問題</b>：vanilla 的 chunk 供給是「主迴圈每幀、每連線只交付一個 ccr」——
 * {@code GameServer} 主迴圈對每條連線呼叫一次 {@code PlayerDownloadServer.update()}，而
 * {@code update()} 只在 {@code workerThread.ready} 時 {@code ccrWaiting.remove(0)} 一次。
 * client 每跨一個 chunk 邊界就把整列 {@code chunkGridWidth}（1080p 以上＝19）個 chunk 打成
 * 一包 {@code RequestZipList}，server 端 {@code parse} 每包無條件配一個新 ccr（只在同一包內
 * 滿 20 才換下一個，從不併入上一包未滿的 ccr）。於是每玩家的供給上限＝主迴圈 fps × 一列：
 * 74 人時主迴圈 3.2 fps ⇒ 每秒 ~61 chunk，時速 100 直行需 ~66、斜行 ~93 ⇒ 前緣永遠補不上
 * （2026-09-06 22:50 正式服實案，log 幀計數差分 3.18 fps）。
 *
 * <p><b>為什麼 v1 被誤退役</b>：v1 的 {@code BATCH} 預設 8，而一列就是 19 ⇒ 隊首永遠
 * ≥BATCH ⇒ 走 {@code skipFull}、從未真正併包；overrun 閘 150ms 以 10 fps 設計，3–5 fps 下
 * 幾乎每 tick 觸發。退役統計（9/1 session：{@code full=66,823 packed=82 overrunTicks=2,405}）
 * 其實證明「佇列 ≥2 個 ccr」一個晚上發生近 7 萬次＝積壓是常態，只是刀沒開。
 *
 * <p><b>v2 修法</b>：批次上限突破 vanilla 的 20（{@code chunks} 是無上限 ArrayList，消費端
 * {@code update()}／{@code sendArray} 都依 {@code chunks.size()} 迴圈；20 只是 parse／pending
 * 的分割門檻，不需要改 {@code isChunksFilled}），預設 38＝兩列、上限 60＝三列；overrun 閘預設
 * 停用（改以每 tick 全域搬移預算節流）。掛點與 v1 相同（審查定案）：
 * {@code removeOlderDuplicateRequests()V} 頭部 headCall——在 {@code update()} 的 ready 閘
 * <b>之內</b>、vanilla 去重之前。閘外插入會與 worker 共用 {@code bb/sb/bbw} 與 {@code cancelled}
 * HashSet 競爭，故絕不拆 ready 閘、也不在 {@code update()} 頭部動 {@code ccrWaiting}。
 *
 * <p><b>觀測（observe 與 enforce 皆做）</b>：
 * <ul>
 *   <li>{@code update()} 頭部 headCall {@link #onUpdate}（閘外，<b>只計數與 tick 邊界偵測，
 *       不碰 pds 任何欄位</b>）：{@code updateCalls}；與 {@code readyCalls} 相減即 ready=false
 *       （worker 忙碌跨幀）的比例。</li>
 *   <li>dedupe 頭部 {@link #packQueue}（閘內）：佇列深度分佈（≥2＝上一幀來的包還沒處理完）、
 *       隊首 chunk 數分佈（＝client 每包大小）、observe 下「若 enforce 會併多少」。</li>
 *   <li>{@code update()} 內 {@code IsoChunk.SaveLoadedChunk} 1:1 redirect {@link #saveLoadedChunk}：
 *       主執行緒序列化耗時（enforce 會讓這個成本 ×2–3，是開 enforce 前必須先知道的代價）。</li>
 * </ul>
 *
 * <p>三態 {@code -Dmdc.chunkPacker}：{@code 0|off}／{@code 1|enforce}／{@code 2|observe}（預設，
 * 未知值落回 observe）。enforce 參數：{@code -Dmdc.chunkPacker.batch}（clamp 1..60，預設 38）、
 * {@code -Dmdc.chunkPacker.windowBudget}（每 tick 全域額外搬移上限，預設 200；0＝不併包）、
 * {@code -Dmdc.chunkPacker.overrunMs}（上一 tick 超過此長度即本 tick 不發配額；預設 0＝停用）。
 * 全部狀態主執行緒單寫（update／dedupe／SaveLoadedChunk 都在主迴圈），普通 long 即可。
 */
public final class ChunkRequestPacker {

    private static final String TAG = "[MinidoracatJavaPatch][ChunkPacker] ";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    /** vanilla {@code ClientChunkRequest.NON_LARGE_AREA_CHUNKS_LIMIT}（SmokeCheck 對帳 bipush 20）。 */
    static final int VANILLA_LIMIT = 20;
    /** 預設兩列（1080p 以上 chunkGridWidth=19）。 */
    static final int DEFAULT_BATCH = 38;
    /** 硬上限三列；再大就是主執行緒序列化與 buffer 峰值的無謂風險。 */
    static final int MAX_BATCH = 60;
    static final int DEFAULT_WINDOW_BUDGET = 200;

    /**
     * tick 邊界門檻。同一 tick 內各連線的 update 是連續呼叫（微秒級間隔；主執行緒序列化一批
     * 最多幾十 ms），下一 tick 相隔 ≥100ms（vanilla UpdateLimit 100ms＝10 fps 上限，正式服
     * 3–10 fps）。取 80ms：某連線序列化超過門檻被誤當新 tick 時，結果是配額重發一次
     * （多發 ≤1 個 budget，仍由 budget 封頂）、save 直方圖被拆成兩段（低估）——皆有界。
     */
    private static final long TICK_GAP_NS = 80_000_000L;
    private static final long LOG_INTERVAL_NS = 300_000_000_000L;
    private static final int MAX_ANOMALY_TRACES = 3;
    private static final long MS = 1_000_000L;

    static final int MODE = parseMode();
    private static final int BATCH = readInt("mdc.chunkPacker.batch", DEFAULT_BATCH, 1, MAX_BATCH);
    private static final int WINDOW_BUDGET = readInt("mdc.chunkPacker.windowBudget", DEFAULT_WINDOW_BUDGET, 0, 100_000);
    private static final long OVERRUN_NS = readInt("mdc.chunkPacker.overrunMs", 0, 0, 60_000) * MS;

    // ---- 計數（主執行緒單寫）----
    private static long updateCalls;     // update() 頭部（閘外）
    private static long readyCalls;      // dedupe 頭部（閘內）＝ packList 進入次數
    private static long depth0, depth1, depth2, depth3to4, depth5plus;
    private static int maxDepth;
    private static long headSizeSum, headSizeSamples;
    private static int headSizeMax;
    private static long headFull;        // 隊首 ≥ vanilla 20（parse 切包門檻）
    private static long skipShort;       // 佇列 <2
    private static long skipLarge;       // 隊首 largeArea
    private static long skipFull;        // 隊首已達 BATCH
    private static long skipNoSource;    // 後續來源全空或全 largeArea
    private static long skipBudget;      // enforce：本 tick 配額用罄／overrun
    private static long dupAbort;        // 掃描範圍內有重複座標→整次放棄
    private static long wouldPack;       // 可併包的機會（observe/enforce 皆計，預算檢查前）
    private static long wouldMerge;      // 對應可搬移 chunk 數
    private static long packedCcrs;      // enforce 實際併包次數
    private static long mergedChunks;
    private static long overrunTicks;
    private static long saveCalls, saveNs, saveMaxNs;
    private static long saveNsThisTick;
    private static long saveTickMaxNs;
    private static long saveTicks, saveTickOver5, saveTickOver20, saveTickOver50;
    private static long anomalies;
    private static long anomalyTraces;

    private static long lastCallNs;
    private static long tickStartNs;
    private static int budgetLeftThisTick;
    private static boolean tickPrimed;
    private static long lastLogNs;
    private static boolean bannerShown;

    private ChunkRequestPacker() {}

    /**
     * {@code PlayerDownloadServer.update()V} 頭部（ready 閘<b>外</b>）：只計數、偵測 tick 邊界、
     * 印 heartbeat。<b>不得讀寫 pds 任何欄位</b>——閘外與 worker 併行。
     */
    public static void onUpdate(PlayerDownloadServer pds) {
        if (MODE == MODE_OFF) {
            return;
        }
        try {
            updateCalls++;
            long now = System.nanoTime();
            if (!tickPrimed || now - lastCallNs >= TICK_GAP_NS) {
                closeTick(now);
            }
            lastCallNs = now;
            if (!bannerShown) {
                bannerShown = true;
                lastLogNs = now;
                DebugLog.log(TAG + "首次生效 mode=" + MODE + " batch=" + BATCH
                        + " windowBudget=" + WINDOW_BUDGET + " overrunMs=" + OVERRUN_NS / MS);
            } else if (now - lastLogNs >= LOG_INTERVAL_NS) {
                lastLogNs = now;
                beat();
            }
        } catch (Throwable t) {
            anomaly(t);
        }
    }

    /**
     * {@code removeOlderDuplicateRequests()V} 頭部（ready 閘<b>內</b>、vanilla 去重之前）：
     * 觀測佇列形狀；enforce 時把後續 ccr 的 chunk 搬進隊首直到 BATCH。非 fatal 例外一律吞掉
     * ——最佳化絕不改變 vanilla 的執行結果。
     */
    public static void packQueue(PlayerDownloadServer pds) {
        if (MODE == MODE_OFF) {
            return;
        }
        try {
            packList(pds.ccrWaiting, MODE == MODE_ENFORCE);
        } catch (Throwable t) {
            anomaly(t);
        }
    }

    /**
     * {@code update()} 內 {@code IsoChunk.SaveLoadedChunk} 的 1:1 改道（receiver 前置）：
     * 只量主執行緒序列化耗時，例外原樣透傳（vanilla 呼叫端自己 catch 後 sendNotRequired）。
     */
    public static void saveLoadedChunk(IsoChunk chunk, ClientChunkRequest.Chunk ccrc, CRC32 crc32)
            throws IOException {
        if (MODE == MODE_OFF) {
            chunk.SaveLoadedChunk(ccrc, crc32);
            return;
        }
        long t0 = System.nanoTime();
        try {
            chunk.SaveLoadedChunk(ccrc, crc32);
        } finally {
            long dt = System.nanoTime() - t0;
            saveCalls++;
            saveNs += dt;
            saveNsThisTick += dt;
            if (dt > saveMaxNs) {
                saveMaxNs = dt;
            }
        }
    }

    /**
     * 併包本體（package-private：行為測試以純 List 直接呼叫）。{@code apply=false}＝observe，
     * 只算「若 enforce 會併多少」，佇列一個位元組都不動。
     */
    static void packList(List<ClientChunkRequest> queue, boolean apply) {
        readyCalls++;
        int depth = queue == null ? 0 : queue.size();
        countDepth(depth);
        if (depth == 0) {
            return;
        }
        ClientChunkRequest head = queue.get(0);
        if (head == null) {
            skipShort++;
            return;
        }
        int before = head.chunks.size();
        headSizeSum += before;
        headSizeSamples++;
        if (before > headSizeMax) {
            headSizeMax = before;
        }
        if (before >= VANILLA_LIMIT) {
            headFull++;
        }
        if (depth < 2) {
            skipShort++;
            return;
        }
        if (head.largeArea) {          // largeArea 有自己的擁塞窗，不介入
            skipLarge++;
            return;
        }
        if (before >= BATCH) {
            skipFull++;
            return;
        }
        int room = BATCH - before;
        // 來源掃描上限：最多搬 room 個 chunk，掃 BATCH 個來源 ccr 必然足夠
        int lastSource = Math.min(depth, 1 + BATCH);

        // **先掃描、後變動**（codex 審查的 blocking）：預定搬移範圍內存在任何重複座標就整次
        // 放棄。「跳過重複後繼續搬」會讓後面的 chunk 越過較新的重複項（leapfrog）＝改變處理
        // 順序；「停在重複處」會把同一來源 ccr 內部的重複拆成跨 ccr 重複，觸發 vanilla 額外的
        // sendNotRequired(false)＝client 刪除本機 chunk 檔。放棄是安全且自癒的：vanilla 去重
        // 本體緊接在本 helper 之後執行，下一個 tick 就能正常併包。
        int available = scanSources(queue, head, lastSource, room);
        if (available < 0) {
            dupAbort++;
            return;
        }
        if (available == 0) {
            skipNoSource++;
            return;
        }
        wouldPack++;
        wouldMerge += available;
        if (!apply) {
            return;
        }

        int budget = takeBudget(available);
        if (budget <= 0) {
            skipBudget++;
            return;
        }
        List<ClientChunkRequest.Chunk> headChunks = head.chunks;
        int target = before + budget;
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
        int merged = headChunks.size() - before;
        if (merged > 0) {
            packedCcrs++;
            mergedChunks += merged;
        }
        returnBudget(budget - merged);
    }

    /**
     * 唯讀掃描：回傳前 {@code room} 個可搬 chunk 數（遇 largeArea 來源即停）；範圍內
     * （隊首 vs 來源、來源彼此、同一來源內部）任何重複座標或 null 回 -1。
     */
    private static int scanSources(List<ClientChunkRequest> queue, ClientChunkRequest head,
            int lastSource, int room) {
        List<ClientChunkRequest.Chunk> seen = new java.util.ArrayList<>(head.chunks.size() + room);
        seen.addAll(head.chunks);
        int taken = 0;
        for (int i = 1; i < lastSource && taken < room; i++) {
            ClientChunkRequest src = queue.get(i);
            if (src == null || src.largeArea) {
                break;
            }
            List<ClientChunkRequest.Chunk> srcChunks = src.chunks;
            for (int j = 0; j < srcChunks.size() && taken < room; j++, taken++) {
                ClientChunkRequest.Chunk c = srcChunks.get(j);
                if (c == null || containsCoord(seen, c.wx, c.wy)) {
                    return -1;
                }
                seen.add(c);
            }
        }
        return taken;
    }

    private static void countDepth(int depth) {
        if (depth > maxDepth) {
            maxDepth = depth;
        }
        if (depth == 0) {
            depth0++;
        } else if (depth == 1) {
            depth1++;
        } else if (depth == 2) {
            depth2++;
        } else if (depth <= 4) {
            depth3to4++;
        } else {
            depth5plus++;
        }
    }

    /** tick 邊界：結算上一 tick 的主執行緒序列化耗時、重發本 tick 配額（overrun 時歸零）。 */
    private static void closeTick(long now) {
        if (tickPrimed) {
            long prevTickNs = now - tickStartNs;
            if (saveNsThisTick > 0) {
                saveTicks++;
                if (saveNsThisTick > saveTickMaxNs) {
                    saveTickMaxNs = saveNsThisTick;
                }
                if (saveNsThisTick > 50 * MS) {
                    saveTickOver50++;
                } else if (saveNsThisTick > 20 * MS) {
                    saveTickOver20++;
                } else if (saveNsThisTick > 5 * MS) {
                    saveTickOver5++;
                }
            }
            boolean overran = OVERRUN_NS > 0L && prevTickNs > OVERRUN_NS;
            if (overran) {
                overrunTicks++;
            }
            budgetLeftThisTick = overran ? 0 : WINDOW_BUDGET;
        } else {
            budgetLeftThisTick = WINDOW_BUDGET;
        }
        tickPrimed = true;
        tickStartNs = now;
        saveNsThisTick = 0;
    }

    private static int takeBudget(int wanted) {
        int take = Math.min(wanted, Math.max(0, budgetLeftThisTick));
        budgetLeftThisTick -= take;
        return take;
    }

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

    private static void beat() {
        long notReady = updateCalls - readyCalls;
        long headAvgX10 = headSizeSamples == 0L ? 0L : headSizeSum * 10L / headSizeSamples;
        long saveAvgUs = saveCalls == 0L ? 0L : saveNs / saveCalls / 1000L;
        DebugLog.log(TAG + "mode=" + MODE + " batch=" + BATCH + " budget=" + WINDOW_BUDGET
                + " updates=" + updateCalls + " ready=" + readyCalls + " notReady=" + notReady
                + " depth[0=" + depth0 + " 1=" + depth1 + " 2=" + depth2 + " 3-4=" + depth3to4
                + " 5+=" + depth5plus + " max=" + maxDepth + "]"
                + " head[avgX10=" + headAvgX10 + " max=" + headSizeMax + " full20=" + headFull + "]"
                + " would[pack=" + wouldPack + " merge=" + wouldMerge + "]"
                + " packed=" + packedCcrs + " merged=" + mergedChunks
                + " skip[short=" + skipShort + " large=" + skipLarge + " full=" + skipFull
                + " noSource=" + skipNoSource + " budget=" + skipBudget + " dupAbort=" + dupAbort + "]"
                + " save[calls=" + saveCalls + " avgUs=" + saveAvgUs + " maxUs=" + saveMaxNs / 1000L
                + " tickMaxMs=" + saveTickMaxNs / MS + " ticks=" + saveTicks
                + " >5ms=" + saveTickOver5 + " >20ms=" + saveTickOver20 + " >50ms=" + saveTickOver50 + "]"
                + " overrunTicks=" + overrunTicks + " anomalies=" + anomalies);
    }

    private static void anomaly(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof ThreadDeath || t instanceof LinkageError) {
            throw (Error) t;      // 新 jar＋舊 loose class 的二進位不相容必須炸得可見
        }
        anomalies++;
        if (anomalyTraces < MAX_ANOMALY_TRACES) {
            anomalyTraces++;
            DebugLog.log(TAG + "anomaly #" + anomalies + ": " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                DebugLog.log(TAG + "    at " + e);
            }
        }
    }

    /** 三態解析：文字別名＋未知值落回預設 observe（家族 parseMode 慣例）。 */
    private static int parseMode() {
        String raw = System.getProperty("mdc.chunkPacker");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "enforce":
                return MODE_ENFORCE;
            case "2":
            case "observe":
            default:
                return MODE_OBSERVE;
        }
    }

    private static int readInt(String key, int def, int min, int max) {
        try {
            String raw = System.getProperty(key);
            if (raw == null || raw.isEmpty()) {
                return def;
            }
            int v = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (RuntimeException e) {
            return def;
        }
    }

    // ---- 測試掛點（package-private；production 不呼叫）----
    static void resetForTest() {
        updateCalls = 0;
        readyCalls = 0;
        depth0 = depth1 = depth2 = depth3to4 = depth5plus = 0;
        maxDepth = 0;
        headSizeSum = headSizeSamples = 0;
        headSizeMax = 0;
        headFull = 0;
        skipShort = skipLarge = skipFull = skipNoSource = skipBudget = dupAbort = 0;
        wouldPack = wouldMerge = 0;
        packedCcrs = mergedChunks = 0;
        overrunTicks = 0;
        saveCalls = saveNs = saveMaxNs = saveNsThisTick = saveTickMaxNs = 0;
        saveTicks = saveTickOver5 = saveTickOver20 = saveTickOver50 = 0;
        anomalies = anomalyTraces = 0;
        tickPrimed = false;
        budgetLeftThisTick = 0;
        newTickForTest();
    }

    /** calls, packed, merged, dupAbort, anomalies, skipBudget, overrunTicks, wouldPack, wouldMerge, skipFull, maxDepth */
    static long[] statsForTest() {
        return new long[]{readyCalls, packedCcrs, mergedChunks, dupAbort,
                anomalies, skipBudget, overrunTicks, wouldPack, wouldMerge, skipFull, maxDepth};
    }

    /** 測試用：模擬 tick 邊界（重發配額）。 */
    static void newTickForTest() {
        closeTick(System.nanoTime());
    }

    static int batchForTest() {
        return BATCH;
    }

    static int windowBudgetForTest() {
        return WINDOW_BUDGET;
    }

    static int modeForTest() {
        return MODE;
    }
}
