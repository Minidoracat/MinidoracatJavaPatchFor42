package zombie.mdc;

import java.util.ArrayList;
import java.util.List;

import zombie.network.ClientChunkRequest;

/**
 * W4-1 併包行為測試：守恆（不新增/不遺失 chunk）、上限、去重語意保留、largeArea 不介入、
 * 順序保留、無效輸入安全。全部用純 List 呼叫 packList，不需要 PlayerDownloadServer 實例。
 */
public final class ChunkRequestPackerTest {

    private static final int BATCH = ChunkRequestPacker.batchForTest();

    public static void main(String[] args) {
        if (ChunkRequestPacker.batchForTest() <= 0 || ChunkRequestPacker.windowBudgetForTest() <= 0) {
            killSwitchHonoured();     // JVM 參數停用模式：只驗「完全 no-op」
            return;
        }
        conservationAndLimit();
        duplicateCoordStaysInSource();
        largeAreaNotTouched();
        headAlreadyFull();
        degenerateInputs();
        orderPreserved();
        noDuplicatePreservesFlattenedOrder();
        batchNeverExceedsVanillaLimit();
        windowBudgetCaps();
        budgetResetsOnNewTick();
        System.out.println("chunk-packer OK  守恆/上限/重複放棄/順序/largeArea/預算閘/tick 重置/kill switch 全數通過");
    }

    /** 10 個 ccr × 3 chunk：隊首應填到 BATCH，且全域 chunk 總數不變（不新增、不遺失）。 */
    private static void conservationAndLimit() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        int coord = 0;
        for (int i = 0; i < 10; i++) {
            q.add(ccr(false, new int[][]{{coord++, 0}, {coord++, 0}, {coord++, 0}}));
        }
        int totalBefore = totalChunks(q);
        require(totalBefore == 30, "前置：30 個 chunk");
        require(q.get(0).chunks.size() == 3, "前置：隊首 3 個");

        ChunkRequestPacker.packList(q);

        require(q.get(0).chunks.size() == BATCH,
                "隊首填到 BATCH=" + BATCH + "，實得 " + q.get(0).chunks.size());
        require(totalChunks(q) == totalBefore, "chunk 總數守恆（不新增不遺失）");
        long[] s = ChunkRequestPacker.statsForTest();
        require(s[1] == 1 && s[2] == BATCH - 3 && s[4] == 0,
                "統計：packed=1 merged=" + (BATCH - 3) + " anomalies=0，實得 packed="
                        + s[1] + " merged=" + s[2]);
    }

    /** 批次上限永不超過 vanilla 的 isChunksFilled 門檻（超發＝違反 vanilla 批次契約）。 */
    private static void batchNeverExceedsVanillaLimit() {
        require(BATCH <= ChunkRequestPacker.VANILLA_LIMIT,
                "BATCH=" + BATCH + " 不得超過 vanilla 上限 " + ChunkRequestPacker.VANILLA_LIMIT);
        require(BATCH > 0, "BATCH 需為正");
    }

    /**
     * 全域視窗預算：連續大量呼叫後應被預算閘擋下（skipBudget 遞增），
     * 且被擋時完全不搬移——這是主迴圈序列化量的硬上界。
     */
    private static void windowBudgetCaps() {
        ChunkRequestPacker.resetForTest();
        int budget = ChunkRequestPacker.windowBudgetForTest();
        int coord = 100000;
        long mergedTotal = 0;
        // 同一個 100ms 視窗內連續灌入，遠超預算
        int iterations = budget / Math.max(1, BATCH) + 40;
        for (int n = 0; n < iterations; n++) {
            List<ClientChunkRequest> q = new ArrayList<>();
            q.add(ccr(false, new int[][]{{coord++, 0}}));
            for (int i = 0; i < 5; i++) {
                q.add(ccr(false, new int[][]{{coord++, 0}, {coord++, 0}, {coord++, 0}}));
            }
            int before = q.get(0).chunks.size();
            ChunkRequestPacker.packList(q);
            mergedTotal += q.get(0).chunks.size() - before;
        }
        long[] s = ChunkRequestPacker.statsForTest();
        require(mergedTotal <= budget,
                "單一視窗內搬移總量不得超過預算 " + budget + "，實得 " + mergedTotal);
        require(s[5] > 0, "預算用罄後應累計 skipBudget，實得 " + s[5]);
        require(s[4] == 0, "無例外");
    }

    /**
     * 範圍內存在重複＝整次放棄併包（佇列一個位元組都不動）。
     * 這是 codex 審查的 blocking 修正：若「跳過重複後繼續搬」，後面的 chunk 會越過較新的
     * 重複項（leapfrog）＝改變處理順序；若「停在重複處」，同一來源內部的重複會被拆成跨
     * ccr 重複，觸發 vanilla 額外的 sendNotRequired(false)＝client 刪除本機 chunk 檔。
     */
    private static void duplicateCoordStaysInSource() {
        // (a) 來源 vs 隊首重複
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, new int[][]{{100, 200}}));
        ClientChunkRequest src = ccr(false, new int[][]{{100, 200}, {101, 200}});
        q.add(src);
        int[] snapshot = flatten(q);

        ChunkRequestPacker.packList(q);

        require(sameOrder(flatten(q), snapshot), "遇重複＝佇列完全不動（順序與歸屬皆不變）");
        require(q.get(0).chunks.size() == 1 && src.chunks.size() == 2, "兩個 ccr 的內容不變");
        require(ChunkRequestPacker.statsForTest()[3] == 1, "skipDupAbort 計數為 1");

        // (b) leapfrog 情境：head=[A_old]、src=[A_new, B]——B 絕不可越過 A_new
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> lf = new ArrayList<>();
        lf.add(ccr(false, new int[][]{{7, 7}}));                       // A_old
        lf.add(ccr(false, new int[][]{{7, 7}, {8, 8}}));               // A_new, B
        int[] lfBefore = flatten(lf);
        ChunkRequestPacker.packList(lf);
        require(sameOrder(flatten(lf), lfBefore), "leapfrog 情境：處理順序完全不變");

        // (c) 同一來源 ccr 內部重複：不得被拆成跨 ccr（否則觸發 vanilla 額外取消）
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> intra = new ArrayList<>();
        intra.add(ccr(false, new int[][]{{1, 1}}));
        ClientChunkRequest dupSrc = ccr(false, new int[][]{{5, 5}, {5, 5}});
        intra.add(dupSrc);
        ChunkRequestPacker.packList(intra);
        require(dupSrc.chunks.size() == 2, "同來源內部重複不得被拆開，實得 " + dupSrc.chunks.size());
        require(intra.get(0).chunks.size() == 1, "隊首不動");
    }

    /** 無重複時：併包是純粹的順序保留重組（與 vanilla 的展平順序逐一相同）。 */
    private static void noDuplicatePreservesFlattenedOrder() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, new int[][]{{1, 0}, {2, 0}}));
        q.add(ccr(false, new int[][]{{3, 0}}));
        q.add(ccr(false, new int[][]{{4, 0}, {5, 0}}));
        int[] before = flatten(q);

        ChunkRequestPacker.packList(q);

        require(sameOrder(flatten(q), before), "展平順序不變（僅重新分組）");
        require(q.get(0).chunks.size() == 5, "全部併入隊首（BATCH=" + BATCH + " 足夠）");
    }

    /** largeArea：隊首是 largeArea 則整體不動；後續遇到 largeArea 即停止併包。 */
    private static void largeAreaNotTouched() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> headLarge = new ArrayList<>();
        headLarge.add(ccr(true, new int[][]{{1, 1}}));
        headLarge.add(ccr(false, new int[][]{{2, 2}}));
        ChunkRequestPacker.packList(headLarge);
        require(headLarge.get(0).chunks.size() == 1 && headLarge.get(1).chunks.size() == 1,
                "隊首 largeArea：完全不動");

        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> tailLarge = new ArrayList<>();
        tailLarge.add(ccr(false, new int[][]{{1, 1}}));
        tailLarge.add(ccr(true, new int[][]{{2, 2}}));
        tailLarge.add(ccr(false, new int[][]{{3, 3}}));
        ChunkRequestPacker.packList(tailLarge);
        require(tailLarge.get(0).chunks.size() == 1, "遇 largeArea 即停，不跨過它取後面的");
        require(tailLarge.get(1).chunks.size() == 1 && tailLarge.get(2).chunks.size() == 1,
                "largeArea 與其後方保持原狀");
    }

    /** 隊首已滿 20：不得再搬（不可超過 vanilla 上限）。 */
    private static void headAlreadyFull() {
        ChunkRequestPacker.resetForTest();
        int[][] full = new int[BATCH][2];
        for (int i = 0; i < full.length; i++) {
            full[i] = new int[]{i, 0};
        }
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, full));
        q.add(ccr(false, new int[][]{{999, 999}}));

        ChunkRequestPacker.packList(q);

        require(q.get(0).chunks.size() == BATCH, "隊首維持 BATCH=" + BATCH);
        require(q.get(1).chunks.size() == 1, "來源不動");
    }

    /** null / 空 / 單一元素：安全 no-op。 */
    private static void degenerateInputs() {
        ChunkRequestPacker.resetForTest();
        ChunkRequestPacker.packList(null);
        ChunkRequestPacker.packList(new ArrayList<>());
        List<ClientChunkRequest> single = new ArrayList<>();
        single.add(ccr(false, new int[][]{{5, 5}}));
        ChunkRequestPacker.packList(single);
        require(single.get(0).chunks.size() == 1, "單一 ccr 不動");
        require(ChunkRequestPacker.statsForTest()[4] == 0, "無例外");
    }

    /** 併包後隊首的 chunk 順序＝原佇列順序（處理順序不得被打亂）。 */
    private static void orderPreserved() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, new int[][]{{1, 0}, {2, 0}}));
        q.add(ccr(false, new int[][]{{3, 0}, {4, 0}}));
        q.add(ccr(false, new int[][]{{5, 0}}));

        ChunkRequestPacker.packList(q);

        List<ClientChunkRequest.Chunk> head = q.get(0).chunks;
        require(head.size() == 5, "全部併入隊首");
        for (int i = 0; i < 5; i++) {
            require(head.get(i).wx == i + 1, "順序保留：index " + i + " 應為 wx=" + (i + 1));
        }
    }

    /** 新 tick 應重新發配額（否則預算耗盡後永遠不再併包）。 */
    private static void budgetResetsOnNewTick() {
        ChunkRequestPacker.resetForTest();
        int budget = ChunkRequestPacker.windowBudgetForTest();
        int coord = 500000;
        // 先把本 tick 的配額用光
        for (int n = 0; n < budget / Math.max(1, BATCH) + 10; n++) {
            List<ClientChunkRequest> q = new ArrayList<>();
            q.add(ccr(false, new int[][]{{coord++, 0}}));
            for (int i = 0; i < 5; i++) {
                q.add(ccr(false, new int[][]{{coord++, 0}, {coord++, 0}, {coord++, 0}}));
            }
            ChunkRequestPacker.packList(q);
        }
        require(ChunkRequestPacker.statsForTest()[5] > 0, "前置：配額已耗盡");

        ChunkRequestPacker.newTickForTest();
        List<ClientChunkRequest> fresh = new ArrayList<>();
        fresh.add(ccr(false, new int[][]{{coord++, 0}}));
        fresh.add(ccr(false, new int[][]{{coord++, 0}, {coord++, 0}}));
        int before = fresh.get(0).chunks.size();
        ChunkRequestPacker.packList(fresh);
        require(fresh.get(0).chunks.size() > before, "新 tick 後應恢復併包");
    }

    /**
     * 緊急 kill switch：-Dmdc.chunkPacker.windowBudget=0（或 batch=0）時完全 no-op。
     * build.ps1 會以該 JVM 參數再跑本測試一次驗證（見 KILL_SWITCH_MODE）。
     */
    private static void killSwitchHonoured() {
        ChunkRequestPacker.resetForTest();
        List<ClientChunkRequest> q = new ArrayList<>();
        q.add(ccr(false, new int[][]{{1, 1}}));
        q.add(ccr(false, new int[][]{{2, 2}, {3, 3}}));
        int[] before = flatten(q);
        ChunkRequestPacker.packList(q);
        require(sameOrder(flatten(q), before), "kill switch 開啟時佇列完全不動");
        require(q.get(0).chunks.size() == 1, "隊首不得被併包");
        System.out.println("chunk-packer OK  kill switch 模式：完全 no-op");
    }

    // ---- helpers ----

    /** 把佇列展平成 (wx,wy) 序列——vanilla 的實際處理順序。 */
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

    private static int countCoord(List<ClientChunkRequest.Chunk> chunks, int wx, int wy) {
        int n = 0;
        for (ClientChunkRequest.Chunk c : chunks) {
            if (c.wx == wx && c.wy == wy) {
                n++;
            }
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
