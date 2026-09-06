package zombie.mdc;

import java.util.ArrayList;
import java.util.List;

import zombie.core.random.RandStandard;
import zombie.network.ClientChunkRequest;
import zombie.network.PlayerDownloadServer;

/**
 * W4-1 v2 併包行為測試（四組態各獨立 JVM，argv 自驗 mode）：
 * observe＝佇列一個位元組都不動、只算 would-merge；enforce＝守恆／上限 BATCH／重複放棄／
 * 順序／largeArea／預算閘／tick 重置；enforce-nobudget＝windowBudget=0 時不併包；off＝純 no-op。
 * 併包案例用純 List 呼叫 packList；另以真 PlayerDownloadServer 走一次 packQueue 驗 mode 分流。
 */
public final class ChunkRequestPackerTest {

    private static final int BATCH = ChunkRequestPacker.batchForTest();
    private static int MODE;
    /** 傳給 packList 的 apply（enforce 一律 true，含 windowBudget=0 的組態）。 */
    private static boolean APPLY;
    /** 預期真的會搬（enforce 且預算 >0）；nobudget 組態 apply=true 但預算閘擋下。 */
    private static boolean EXPECT_MERGE;

    public static void main(String[] args) {
        String want = args.length > 0 ? args[0] : "observe";
        int wantMode = switch (want) {
            case "off" -> ChunkRequestPacker.MODE_OFF;
            case "enforce", "enforce-nobudget" -> ChunkRequestPacker.MODE_ENFORCE;
            case "observe" -> ChunkRequestPacker.MODE_OBSERVE;
            default -> throw new AssertionError("未知組態 " + want);
        };
        MODE = ChunkRequestPacker.modeForTest();
        require(MODE == wantMode, "argv=" + want + " 但實際 MODE=" + MODE + "（property 名稱打錯？）");
        boolean noBudget = want.equals("enforce-nobudget");
        require(noBudget == (ChunkRequestPacker.windowBudgetForTest() == 0),
                "enforce-nobudget 組態要求 windowBudget=0，實得 " + ChunkRequestPacker.windowBudgetForTest());
        APPLY = MODE == ChunkRequestPacker.MODE_ENFORCE;
        EXPECT_MERGE = APPLY && !noBudget;

        // 真路徑（packQueue 的 MODE 分流）需要已播種的全域 Rand（PlayerDownloadServer ctor 用 Rand 命名 worker）
        RandStandard.INSTANCE.init();
        realPathModeDispatch();

        if (MODE == ChunkRequestPacker.MODE_OFF) {
            System.out.println("chunk-packer OK  off：packQueue 純 no-op、零統計");
            return;
        }
        twoRows();
        depthStats();
        conservationAndLimit();
        duplicateCoordAborts();
        largeAreaNotTouched();
        headAlreadyFull();
        degenerateInputs();
        orderPreserved();
        if (EXPECT_MERGE) {
            windowBudgetCaps();
            budgetResetsOnNewTick();
        }
        System.out.println("chunk-packer OK  " + want + "：守恆/上限/重複放棄/順序/largeArea/預算閘/tick 重置/深度統計全數通過");
    }

    /** 真 PlayerDownloadServer＋packQueue：off 完全不動也不計數；observe 不動但計數；enforce 併包。 */
    private static void realPathModeDispatch() {
        ChunkRequestPacker.resetForTest();
        PlayerDownloadServer pds = new PlayerDownloadServer(null);
        pds.ccrWaiting.add(ccr(false, row(0, 19)));
        pds.ccrWaiting.add(ccr(false, row(1, 19)));
        int[] before = flatten(pds.ccrWaiting);
        ChunkRequestPacker.onUpdate(pds);
        ChunkRequestPacker.packQueue(pds);
        long[] s = ChunkRequestPacker.statsForTest();
        if (MODE == ChunkRequestPacker.MODE_OFF) {
            require(sameOrder(flatten(pds.ccrWaiting), before) && pds.ccrWaiting.get(0).chunks.size() == 19,
                    "off：佇列完全不動");
            require(s[0] == 0 && s[7] == 0, "off：零統計（calls=" + s[0] + " wouldPack=" + s[7] + "）");
        } else if (EXPECT_MERGE) {
            require(pds.ccrWaiting.get(0).chunks.size() == Math.min(BATCH, 38)
                    && totalChunks(pds.ccrWaiting) == 38, "enforce 真路徑：隊首併到 " + Math.min(BATCH, 38) + "、守恆");
            require(s[1] == 1, "enforce 真路徑：packed=1，實得 " + s[1]);
        } else {
            require(sameOrder(flatten(pds.ccrWaiting), before) && pds.ccrWaiting.get(0).chunks.size() == 19,
                    "observe／nobudget 真路徑：佇列完全不動");
            require(s[0] == 1 && s[7] == 1 && s[8] == Math.min(19, BATCH - 19) && s[1] == 0,
                    "observe／nobudget 真路徑：calls=1 wouldPack=1 wouldMerge=" + Math.min(19, BATCH - 19)
                            + " packed=0，實得 calls=" + s[0] + " wouldPack=" + s[7] + " wouldMerge=" + s[8] + " packed=" + s[1]);
        }
        require(s[4] == 0, "真路徑：零 anomalies");
    }

    /** 正式服典型形狀：兩列 19（斜行／低谷積壓）。observe 只記 would；enforce 併成 38。 */
    private static void twoRows() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, row(0, 19)));
        q.add(ccr(false, row(1, 19)));
        int[] before = flatten(q);
        ChunkRequestPacker.packList(q, APPLY);
        long[] s = ChunkRequestPacker.statsForTest();
        int expectMerge = Math.min(19, BATCH - 19);
        require(s[7] == 1 && s[8] == expectMerge, "would：pack=1 merge=" + expectMerge + "，實得 " + s[7] + "/" + s[8]);
        if (EXPECT_MERGE) {
            require(q.get(0).chunks.size() == 19 + expectMerge, "enforce：隊首 " + (19 + expectMerge));
            require(sameOrder(flatten(q), before), "enforce：展平順序不變（僅重新分組）");
            require(s[1] == 1 && s[2] == expectMerge, "enforce：packed=1 merged=" + expectMerge);
        } else {
            require(sameOrder(flatten(q), before) && q.get(0).chunks.size() == 19 && q.get(1).chunks.size() == 19,
                    "observe：佇列完全不動");
            require(s[1] == 0 && s[2] == 0, "observe／nobudget：packed=merged=0");
            require((s[5] == 1) == APPLY, "nobudget 才計 skipBudget（apply=true 但預算 0），實得 " + s[5]);
        }
    }

    /** 深度分佈／最大深度：0、1、2、5 各一次。 */
    private static void depthStats() {
        ChunkRequestPacker.resetForTest();
        ChunkRequestPacker.packList(new ArrayList<>(), APPLY);
        List<ClientChunkRequest> one = new ArrayList<>();
        one.add(ccr(false, row(0, 3)));
        ChunkRequestPacker.packList(one, APPLY);
        List<ClientChunkRequest> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(ccr(true, row(i, 1)));      // 隊首 largeArea：不併，只計深度
        }
        int[] before = flatten(five);
        ChunkRequestPacker.packList(five, APPLY);
        long[] s = ChunkRequestPacker.statsForTest();
        require(s[0] == 3 && s[10] == 5, "calls=3 maxDepth=5，實得 " + s[0] + "/" + s[10]);
        require(sameOrder(flatten(five), before), "largeArea 隊首：不動");
    }

    /** 20 個 ccr × 3 chunk：enforce 隊首應填到 BATCH，全域 chunk 總數不變；observe 不動。 */
    private static void conservationAndLimit() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        int coord = 0;
        for (int i = 0; i < 20; i++) {
            q.add(ccr(false, new int[][]{{coord++, 0}, {coord++, 0}, {coord++, 0}}));
        }
        int totalBefore = totalChunks(q);
        int[] before = flatten(q);
        ChunkRequestPacker.packList(q, APPLY);
        require(totalChunks(q) == totalBefore, "chunk 總數守恆（不新增不遺失）");
        long[] s = ChunkRequestPacker.statsForTest();
        if (EXPECT_MERGE) {
            require(q.get(0).chunks.size() == BATCH, "隊首填到 BATCH=" + BATCH + "，實得 " + q.get(0).chunks.size());
            require(s[1] == 1 && s[2] == BATCH - 3 && s[4] == 0, "統計：packed=1 merged=" + (BATCH - 3));
            require(sameOrder(flatten(q), before), "展平順序不變");
        } else {
            require(sameOrder(flatten(q), before) && q.get(0).chunks.size() == 3, "observe：不動");
            require(s[7] == 1 && s[8] == BATCH - 3, "observe would：merge=" + (BATCH - 3) + "，實得 " + s[8]);
        }
    }

    /**
     * 範圍內存在重複＝整次放棄（佇列一個位元組都不動；observe 也記 dupAbort 而非 would）。
     * codex 審查的 blocking：跳過重複繼續搬會 leapfrog 改變處理順序；停在重複處會把同來源
     * 內部重複拆成跨 ccr 重複，觸發 vanilla 額外 sendNotRequired(false)＝client 刪本機 chunk 檔。
     */
    private static void duplicateCoordAborts() {
        // (a) 來源 vs 隊首
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, new int[][]{{100, 200}}));
        ClientChunkRequest src = ccr(false, new int[][]{{100, 200}, {101, 200}});
        q.add(src);
        int[] snapshot = flatten(q);
        ChunkRequestPacker.packList(q, APPLY);
        require(sameOrder(flatten(q), snapshot) && q.get(0).chunks.size() == 1 && src.chunks.size() == 2,
                "遇重複＝佇列完全不動");
        long[] s = ChunkRequestPacker.statsForTest();
        require(s[3] == 1 && s[7] == 0, "dupAbort=1 且不計 would，實得 dupAbort=" + s[3] + " would=" + s[7]);

        // (b) leapfrog：head=[A_old]、src=[A_new, B]——B 絕不可越過 A_new
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> lf = new ArrayList<>();
        lf.add(ccr(false, new int[][]{{7, 7}}));
        lf.add(ccr(false, new int[][]{{7, 7}, {8, 8}}));
        int[] lfBefore = flatten(lf);
        ChunkRequestPacker.packList(lf, APPLY);
        require(sameOrder(flatten(lf), lfBefore), "leapfrog 情境：處理順序完全不變");

        // (c) 同一來源內部重複：不得被拆成跨 ccr
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> intra = new ArrayList<>();
        intra.add(ccr(false, new int[][]{{1, 1}}));
        ClientChunkRequest dupSrc = ccr(false, new int[][]{{5, 5}, {5, 5}});
        intra.add(dupSrc);
        ChunkRequestPacker.packList(intra, APPLY);
        require(dupSrc.chunks.size() == 2 && intra.get(0).chunks.size() == 1, "同來源內部重複不得被拆開");
    }

    /** largeArea：隊首 largeArea 整體不動；後續遇到 largeArea 即停止（不跨過它取後面的）。 */
    private static void largeAreaNotTouched() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> headLarge = new ArrayList<>();
        headLarge.add(ccr(true, new int[][]{{1, 1}}));
        headLarge.add(ccr(false, new int[][]{{2, 2}}));
        ChunkRequestPacker.packList(headLarge, APPLY);
        require(headLarge.get(0).chunks.size() == 1 && headLarge.get(1).chunks.size() == 1, "隊首 largeArea：完全不動");

        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> tailLarge = new ArrayList<>();
        tailLarge.add(ccr(false, new int[][]{{1, 1}}));
        tailLarge.add(ccr(true, new int[][]{{2, 2}}));
        tailLarge.add(ccr(false, new int[][]{{3, 3}}));
        ChunkRequestPacker.packList(tailLarge, APPLY);
        require(tailLarge.get(0).chunks.size() == 1 && tailLarge.get(1).chunks.size() == 1
                && tailLarge.get(2).chunks.size() == 1, "遇 largeArea 即停，不跨過它取後面的");
        long[] s = ChunkRequestPacker.statsForTest();
        require(s[7] == 0 && s[1] == 0, "largeArea 來源：would=packed=0");
    }

    /** 隊首已達 BATCH：不得再搬（skipFull）。 */
    private static void headAlreadyFull() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, row(0, BATCH)));
        q.add(ccr(false, new int[][]{{999, 999}}));
        ChunkRequestPacker.packList(q, APPLY);
        require(q.get(0).chunks.size() == BATCH && q.get(1).chunks.size() == 1, "隊首維持 BATCH、來源不動");
        require(ChunkRequestPacker.statsForTest()[9] == 1, "skipFull=1");
    }

    /** null／空／單一元素：安全 no-op。 */
    private static void degenerateInputs() {
        ChunkRequestPacker.resetForTest();
        ChunkRequestPacker.packList(null, APPLY);
        ChunkRequestPacker.packList(new ArrayList<>(), APPLY);
        List<ClientChunkRequest> single = new ArrayList<>();
        single.add(ccr(false, new int[][]{{5, 5}}));
        ChunkRequestPacker.packList(single, APPLY);
        require(single.get(0).chunks.size() == 1, "單一 ccr 不動");
        require(ChunkRequestPacker.statsForTest()[4] == 0, "無例外");
    }

    /** enforce 併包後隊首的 chunk 順序＝原佇列展平順序（處理順序不得被打亂）。 */
    private static void orderPreserved() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, new int[][]{{1, 0}, {2, 0}}));
        q.add(ccr(false, new int[][]{{3, 0}, {4, 0}}));
        q.add(ccr(false, new int[][]{{5, 0}}));
        int[] before = flatten(q);
        ChunkRequestPacker.packList(q, APPLY);
        require(sameOrder(flatten(q), before), "展平順序不變");
        if (EXPECT_MERGE) {
            List<ClientChunkRequest.Chunk> head = q.get(0).chunks;
            require(head.size() == 5, "全部併入隊首");
            for (int i = 0; i < 5; i++) {
                require(head.get(i).wx == i + 1, "順序保留：index " + i + " 應為 wx=" + (i + 1));
            }
        }
    }

    /** 同一 tick 內連續灌入遠超預算：搬移總量 ≤ 預算且被擋時完全不搬（主緒序列化量的硬上界）。 */
    private static void windowBudgetCaps() {
        ChunkRequestPacker.resetForTest();
        int budget = ChunkRequestPacker.windowBudgetForTest();
        int coord = 100000;
        long mergedTotal = 0;
        int iterations = budget / Math.max(1, BATCH - 1) + 40;
        for (int n = 0; n < iterations; n++) {
            List<ClientChunkRequest> q = new ArrayList<>();
            q.add(ccr(false, new int[][]{{coord++, 0}}));
            for (int i = 0; i < 5; i++) {
                q.add(ccr(false, row(coord, 19)));
                coord += 19;
            }
            int before = q.get(0).chunks.size();
            ChunkRequestPacker.packList(q, true);
            mergedTotal += q.get(0).chunks.size() - before;
        }
        long[] s = ChunkRequestPacker.statsForTest();
        require(mergedTotal <= budget, "單一 tick 內搬移總量不得超過預算 " + budget + "，實得 " + mergedTotal);
        require(s[5] > 0, "預算用罄後應累計 skipBudget，實得 " + s[5]);
        require(s[4] == 0, "無例外");
    }

    /** 新 tick 應重新發配額（否則預算耗盡後永遠不再併包）。 */
    private static void budgetResetsOnNewTick() {
        ChunkRequestPacker.resetForTest();
        int budget = ChunkRequestPacker.windowBudgetForTest();
        int coord = 500000;
        for (int n = 0; n < budget / Math.max(1, BATCH - 1) + 10; n++) {
            List<ClientChunkRequest> q = new ArrayList<>();
            q.add(ccr(false, new int[][]{{coord++, 0}}));
            for (int i = 0; i < 5; i++) {
                q.add(ccr(false, row(coord, 19)));
                coord += 19;
            }
            ChunkRequestPacker.packList(q, true);
        }
        require(ChunkRequestPacker.statsForTest()[5] > 0, "前置：配額已耗盡");

        ChunkRequestPacker.newTickForTest();
        List<ClientChunkRequest> fresh = new ArrayList<>();
        fresh.add(ccr(false, new int[][]{{coord++, 0}}));
        fresh.add(ccr(false, new int[][]{{coord++, 0}, {coord++, 0}}));
        int before = fresh.get(0).chunks.size();
        ChunkRequestPacker.packList(fresh, true);
        require(fresh.get(0).chunks.size() > before, "新 tick 後應恢復併包");
    }

    // ---- helpers ----

    /** 一列 n 個 chunk：wy=row、wx=0..n-1。 */
    private static int[][] row(int rowIndex, int n) {
        int[][] out = new int[n][2];
        for (int i = 0; i < n; i++) {
            out[i] = new int[]{i, rowIndex};
        }
        return out;
    }

    private static int[] flatten(List<ClientChunkRequest> q) {
        int n = totalChunks(q);
        int[] out = new int[n * 2];
        int k = 0;
        for (ClientChunkRequest r : q) {
            for (ClientChunkRequest.Chunk c : r.chunks) {
                out[k++] = c.wx;
                out[k++] = c.wy;
            }
        }
        return out;
    }

    private static boolean sameOrder(int[] a, int[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static ClientChunkRequest ccr(boolean largeArea, int[][] coords) {
        ClientChunkRequest r = new ClientChunkRequest();
        r.largeArea = largeArea;
        for (int[] c : coords) {
            ClientChunkRequest.Chunk chunk = r.getChunk();
            chunk.wx = c[0];
            chunk.wy = c[1];
            r.chunks.add(chunk);
        }
        return r;
    }

    private static int totalChunks(List<ClientChunkRequest> q) {
        int n = 0;
        for (ClientChunkRequest r : q) {
            n += r.chunks.size();
        }
        return n;
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private ChunkRequestPackerTest() {}
}
