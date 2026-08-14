package zombie.mdc;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import zombie.debug.DebugLog;
import zombie.network.ClientChunkRequest;

/**
 * W9 存檔管線隔離（2026-08-14；CRC-blam 家族根治刀——共用 CRC32 競態＋共用物件池斷絕）。
 *
 * <p><b>定罪證據鏈</b>（bytecode 實證，docs/patches.md 2u）：
 * <ol>
 *   <li>W8 閘上線首晚攔下 8 筆損毀寫入，8/8 呼叫堆疊一致：
 *       {@code SaveChunkThread → SaveLoadedTask.save}；8/8 簽名一致：len 欄位正確、
 *       CRC 欄位＝0（3 筆）或垃圾值（5 筆）——與歷史 43 筆屍體同款兩簽名。</li>
 *   <li>{@code IsoChunk.Save(ByteBuffer,CRC32,Z)} 的 header 指紋用<b>呼叫者傳入的
 *       CRC32</b> 計算（reset → update body → getValue → 回填）。序列化入口
 *       {@code SaveChunkThread.addLoadedJob} 傳入的是 <b>SaveChunkThread.crc32 單一共用
 *       實例</b>。兩執行緒同時序列化：對方 reset() 插在我 update 與 getValue 之間 →
 *       我讀到 <b>0</b>（＝A 組簽名）；update 交錯 → <b>混合垃圾</b>（＝B 組簽名）。
 *       body 與 len 由各自執行緒完整寫入 → <b>len 永遠正確</b>——與 8/8 觀測鐵律唯一相容
 *       的機制（buffer 竊用假說無法解釋 len 恆正確）。</li>
 *   <li>並行序列化實證：{@code QueuedSaveAll}（全圖存檔）可在 {@code GameServer$1}
 *       （shutdown hook 執行緒）執行，與主迴圈的 {@code ServerCell.update → saveChunk}
 *       同時進入 addLoadedJob——歷史 blam 集中於重啟窗口（0.8 筆/重啟）由此解釋。
 *       運行中爆發（8/14 晚 21:50、22:15 兩波）之第二執行緒未逐一指認，但本修法
 *       不依賴指認：任何並行呼叫者都被 ThreadLocal 隔離。</li>
 *   <li>第二處同款競態：{@code SaveLoadedTask.save()} 的去重比對用外層
 *       {@code ServerChunkLoader.crcSave} 共用實例，而 save() 可在 SaveChunkThread 與
 *       LoaderThread（經 {@code saveNow}，載入前沖存檔）並行執行——污染
 *       ChunkChecksum（去重誤判＝陳舊跳寫；客戶端校驗錯亂＝重送風暴，疑與黑邊案
 *       「crc 恆 0」同根）。</li>
 * </ol>
 *
 * <p><b>三刀</b>（全部只動存檔管線，發送路徑一概不碰）：
 * <ol>
 *   <li>{@code addLoadedJob} 的 GETFIELD crc32 → {@link #headerCrc}（ThreadLocal）——
 *       header 指紋競態根絕；</li>
 *   <li>{@code SaveLoadedTask.save()} 的 GETFIELD crcSave ×4 → {@link #dedupCrc}
 *       （ThreadLocal）——去重／ChunkChecksum 競態根絕；</li>
 *   <li>{@code getChunk／getByteBuffer／releaseChunk} → 本類私有池——存檔管線徹底退出
 *       {@code ClientChunkRequest} 的全域 static 共用池（與 N 條 PlayerDownloadServer
 *       WorkerThread、RequestZipListPacket.parse 共用），恢復單一所有權鏈。
 *       同時關閉 W8 閘的理論盲區：池若把同一 buffer 發給兩個主人，「完整重填成別塊
 *       chunk 的自洽資料」可通過 CRC 驗證——私有化後此路徑物理上不存在。</li>
 * </ol>
 *
 * <p><b>私有池語意</b>：逐行鏡射 vanilla（getChunk 重置 retriesCount、getByteBuffer
 * poll-or-allocate(16384)-else-clear、releaseChunk 先還 buffer 再還殼並 null bb）。
 * 池上限＝存檔佇列在途峰值（LinkedBlockingQueue 深度），與 vanilla 同界。
 * {@code Save()} 擴容回傳的長大 buffer 一樣流回私有池，尺寸異質行為與 vanilla 一致。
 *
 * <p><b>驗證閉環</b>：W8 ChunkWriteGuard 的 {@code flagged} 計數器是現成 A/B 儀表——
 * 本刀上線後 flagged 應歸零；不歸零＝機制另有分支，BLOCKED stack 續查。
 * W8 閘不拆，永久保險絲。
 *
 * <p><b>Kill switch</b>：{@code -Dmdc.chunkSaveIsolation=0} 完全停用——helper 全部
 * 原樣委派回 vanilla 共用實例／共用池（redirect 帶著原 receiver，off 路徑就是原始碼）。
 */
public final class ChunkSaveIsolation {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.chunkSaveIsolation"));

    /** 序列化 header 指紋用（addLoadedJob → IsoChunk.SaveLoadedChunk → Save）。 */
    private static final ThreadLocal<CRC32> HEADER_CRC = ThreadLocal.withInitial(CRC32::new);
    /** 去重比對用（SaveLoadedTask.save 的 reset/update/getValue×2 四連讀）。 */
    private static final ThreadLocal<CRC32> DEDUP_CRC = ThreadLocal.withInitial(CRC32::new);

    /** 存檔管線私有池——與 ClientChunkRequest 的全域 static 池零交集。 */
    private static final ConcurrentLinkedQueue<ClientChunkRequest.Chunk> CHUNKS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ByteBuffer> BUFFERS = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean banner = new AtomicBoolean();

    /** GETFIELD SaveChunkThread.crc32 的同形替換目標（吃共用實例、回執行緒私有）。 */
    public static CRC32 headerCrc(CRC32 shared) {
        if (!ENABLED) {
            return shared;
        }
        firstUse();
        return HEADER_CRC.get();
    }

    /** GETFIELD ServerChunkLoader.crcSave 的同形替換目標。 */
    public static CRC32 dedupCrc(CRC32 shared) {
        return ENABLED ? DEDUP_CRC.get() : shared;
    }

    /** INVOKEVIRTUAL ClientChunkRequest.getChunk 改道目標（receiver 僅 off 路徑使用）。 */
    public static ClientChunkRequest.Chunk getChunk(ClientChunkRequest ccr) {
        if (!ENABLED) {
            return ccr.getChunk();
        }
        ClientChunkRequest.Chunk c = CHUNKS.poll();
        if (c == null) {
            c = new ClientChunkRequest.Chunk();
        }
        c.retriesCount = 0;
        return c;
    }

    /** INVOKEVIRTUAL ClientChunkRequest.getByteBuffer 改道目標。 */
    public static void getByteBuffer(ClientChunkRequest ccr, ClientChunkRequest.Chunk c) {
        if (!ENABLED) {
            ccr.getByteBuffer(c);
            return;
        }
        c.bb = BUFFERS.poll();
        if (c.bb == null) {
            c.bb = ByteBuffer.allocate(16384);
        } else {
            c.bb.clear();
        }
    }

    /** INVOKEVIRTUAL ClientChunkRequest.releaseChunk 改道目標（addLoadedJob 例外路徑＋release()）。 */
    public static void releaseChunk(ClientChunkRequest ccr, ClientChunkRequest.Chunk c) {
        if (!ENABLED) {
            ccr.releaseChunk(c);
            return;
        }
        if (c.bb != null) {
            BUFFERS.add(c.bb);
            c.bb = null;
        }
        CHUNKS.add(c);
    }

    private static void firstUse() {
        if (banner.compareAndSet(false, true)) {
            try {
                DebugLog.log("[MinidoracatJavaPatch][ChunkSaveIsolation] 首次生效"
                        + "（header/dedup CRC 執行緒隔離＋存檔管線私有池；-Dmdc.chunkSaveIsolation=0 停用）");
            } catch (RuntimeException | LinkageError ignored) {
                // 橫幅只是驗證便利，失敗不得影響存檔路徑
            }
        }
    }

    private ChunkSaveIsolation() {}
}
