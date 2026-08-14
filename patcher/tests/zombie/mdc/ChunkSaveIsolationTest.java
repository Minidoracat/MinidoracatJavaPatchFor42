package zombie.mdc;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;

import zombie.network.ClientChunkRequest;

/**
 * W9 kill switch（-Dmdc.chunkSaveIsolation=0）off 路徑行為驗證（codex 對抗審查修正：
 * SmokeCheck 只釘了 off 路徑的 bytecode 形狀，off 分支從未真的執行過——緊急降級
 * 路徑第一次跑它的時機不該是事故現場）。
 *
 * <p>必須以 {@code -Dmdc.chunkSaveIsolation=0} 啟動（build.ps1 步驟 9d）；測試開頭
 * 自我驗證 property 真的到位——property 名稱打錯會在這裡炸，而不是默默跑 enabled
 * 版再回報綠燈（W6 kill switch 測試的教訓沿用）。
 *
 * <p>斷言：兩個 CRC helper 原樣回傳共用實例；三個池 helper 完全委派 vanilla 全域池
 * （以預埋 marker 實例驗證同一性，非只驗計數）；私有 buffer 池全程零使用。
 */
public final class ChunkSaveIsolationTest {

    public static void main(String[] args) throws Exception {
        if (!"0".equals(System.getProperty("mdc.chunkSaveIsolation"))) {
            System.err.println("csi-off FAIL  測試必須以 -Dmdc.chunkSaveIsolation=0 執行（property 未到位）");
            System.exit(1);
        }

        int failed = 0;

        // CRC helper：off 模式必須原樣回傳共用實例（identity，非 equals）
        CRC32 shared = new CRC32();
        failed += check("off：headerCrc 原樣回傳共用實例", ChunkSaveIsolation.headerCrc(shared) == shared);
        failed += check("off：dedupCrc 原樣回傳共用實例", ChunkSaveIsolation.dedupCrc(shared) == shared);

        // 池 helper：以預埋 marker 驗證「真的走 vanilla 全域池」——
        // vanilla getChunk 是 freeChunks.poll()，預埋的殼必須被原封取回
        ClientChunkRequest ccr = new ClientChunkRequest();
        ClientChunkRequest.Chunk marker = new ClientChunkRequest.Chunk();
        ccr.releaseChunk(marker);   // marker 入全域 freeChunks
        ClientChunkRequest.Chunk rented = ChunkSaveIsolation.getChunk(ccr);
        failed += check("off：getChunk 委派全域池（取回預埋殼）", rented == marker);

        ByteBuffer markerBuf = ByteBuffer.allocate(64);
        ClientChunkRequest.freeBuffers.add(markerBuf);
        ChunkSaveIsolation.getByteBuffer(ccr, rented);
        failed += check("off：getByteBuffer 委派全域池（取回預埋 buffer）", rented.bb == markerBuf);

        ChunkSaveIsolation.releaseChunk(ccr, rented);
        failed += check("off：releaseChunk 委派全域池（buffer 回池、bb 歸 null）",
                rented.bb == null && ClientChunkRequest.freeBuffers.contains(markerBuf));

        // 私有池零使用：off 模式下任何 helper 都不得碰私有 BUFFERS
        java.lang.reflect.Field privBufs = ChunkSaveIsolation.class.getDeclaredField("BUFFERS");
        privBufs.setAccessible(true);
        failed += check("off：私有 buffer 池全程零使用",
                ((ConcurrentLinkedQueue<?>) privBufs.get(null)).isEmpty());

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("csi-off OK  kill switch 委派路徑：CRC identity、全域池同一性、私有池零使用全數通過");
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "csi-off pass  " : "csi-off FAIL  ") + name);
        return ok ? 0 : 1;
    }

    private ChunkSaveIsolationTest() {}
}
