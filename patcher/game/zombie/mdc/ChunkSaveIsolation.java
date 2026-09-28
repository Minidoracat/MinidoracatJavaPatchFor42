package zombie.mdc;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import zombie.debug.DebugLog;
import zombie.network.ClientChunkRequest;

/**
 * W9 存檔管線隔離（2026-08-14 上線；2026-09-28 對 42.21.0 縮為「私有池」一刀）。
 *
 * <p><b>原三刀</b>（定罪證據鏈見 docs/patches.md 2u）：
 * <ol>
 *   <li>{@code addLoadedJob} 的共用 {@code SaveChunkThread.crc32}（header 指紋競態）→
 *       ThreadLocal——<b>42.21 官方已修，已退役</b>：欄位刪除，改為每次
 *       {@code new CRC32()} 的區域變數；</li>
 *   <li>{@code SaveLoadedTask.save()} 的共用 {@code ServerChunkLoader.crcSave}（去重競態）→
 *       ThreadLocal——<b>42.21 官方已修，已退役</b>：同上；</li>
 *   <li>{@code getChunk／getByteBuffer／releaseChunk} → 本類私有池（<b>保留</b>）。</li>
 * </ol>
 *
 * <p><b>為什麼第三刀仍需要</b>：42.21 的 {@code ClientChunkRequest} 全域 static 池
 * （{@code freeChunks} private static／{@code freeBuffers} public static）與
 * {@code SaveChunkThread.update()} 的無同步 {@code savedChunks} ArrayList 都未變。主迴圈
 * （{@code ServerMap.postupdate → updateSaved}）與 shutdown hook（{@code QueuedQuit →
 * SaveAll} 輪詢 {@code updateSaved}）並行時，同一 task 可被 release 兩次——全域池就會把
 * 同一顆殼／buffer 同時出租給兩個主人（其一可能是 N 條 PlayerDownloadServer WorkerThread
 * 的發送序列化）。W8 閘攔得住不自洽的寫入，攔不住「buffer 被完整重填成別塊 chunk 的
 * 自洽資料」；存檔管線改用私有池後這條路徑物理上不存在。
 *
 * <p><b>私有池語意</b>（codex 對抗審查後收緊為 exactly-once）：Chunk 殼<b>不入池</b>
 * ——每次 new，雙重歸還的殼自然 GC、物理上無法二次出租；buffer 歸還走
 * {@code synchronized(c)} 原子摘取，雙重 release 的第二次拿到 null＝no-op。
 * getByteBuffer 語意鏡射 vanilla（poll-or-allocate(16384)-else-clear）；
 * {@code Save()} 擴容回傳的長大 buffer 一樣流回私有池（容量 ≤256KB 且池內 &lt;256 顆
 * 才收，否則丟棄給 GC——vanilla 全域池無界，本池反而更緊）。
 *
 * <p><b>驗證閉環</b>：W8 ChunkWriteGuard 的 {@code flagged} 計數器是現成 A/B 儀表——
 * 42.21 官方修掉 CRC 競態後 flagged 應恆 0；不為 0＝還有別的機制，BLOCKED stack 續查。
 *
 * <p><b>Kill switch</b>：{@code -Dmdc.chunkSaveIsolation=0} 完全停用——helper 全部
 * 原樣委派回 vanilla 共用池（redirect 帶著原 receiver，off 路徑就是原始碼）。
 */
public final class ChunkSaveIsolation {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.chunkSaveIsolation"));

    /**
     * 存檔管線私有 buffer 池——與 ClientChunkRequest 的全域 static 池零交集。
     * <b>Chunk 殼刻意不入池</b>（codex 對抗審查 blocking 修正）：vanilla 的
     * {@code SaveChunkThread.update()} 用無同步的 savedChunks ArrayList 歸還，
     * 主迴圈與 shutdown hook 並行 updateSaved 時同一 task 可被 release 兩次——
     * 入池的殼會被二次出租給兩個主人（正是本刀要根絕的競態，在私有池內復刻）。
     * 殼每次 new（~40 bytes × 存檔頻率＝微不足道），雙重歸還的殼自然 GC，
     * 物理上無法二次出租；buffer 則以 {@code synchronized(c)} 原子摘取達成
     * exactly-once 歸還（雙重 release 的第二次拿到 null＝no-op）。
     *
     * <p>池上限（codex 審查 major 修正；vanilla 全域池無界——sendLargeArea 的
     * clear() 經全 jar 普查為死碼，從不執行）：數量 256（軟上限，併發下可微幅
     * 超出）、單顆容量 256KB（Save 擴容以 64KB 倍數成長，超大者為離群值，
     * 直接丟棄給 GC）。典型駐留 ≤16MB，與 vanilla 峰值同量級。
     */
    private static final ConcurrentLinkedQueue<ByteBuffer> BUFFERS = new ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger pooled = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_POOLED_BUFFERS = 256;
    private static final int MAX_POOLED_CAPACITY = 262144;

    private static final AtomicBoolean banner = new AtomicBoolean();

    /**
     * INVOKEVIRTUAL ClientChunkRequest.getChunk 改道目標（receiver 僅 off 路徑使用）。
     * 殼永遠是新的。安全依據＝消費端先寫後讀（addLoadedJob 使用前寫 wx/wy、getByteBuffer
     * 指派 bb；存檔路徑只讀 wx/wy/bb），非欄位重置——42.20.3 起 vanilla getChunk 不重置
     * 任何欄位（回收殼帶舊值出租；retriesCount 與重試機制已整個刪除），42.20.2 也僅重置
     * retriesCount。先寫後讀由 SmokeCheck 的 addLoadedJob PUTFIELD census 釘死。
     */
    public static ClientChunkRequest.Chunk getChunk(ClientChunkRequest ccr) {
        if (!ENABLED) {
            return ccr.getChunk();
        }
        firstUse();
        return new ClientChunkRequest.Chunk();
    }

    /** INVOKEVIRTUAL ClientChunkRequest.getByteBuffer 改道目標。 */
    public static void getByteBuffer(ClientChunkRequest ccr, ClientChunkRequest.Chunk c) {
        if (!ENABLED) {
            ccr.getByteBuffer(c);
            return;
        }
        ByteBuffer b = BUFFERS.poll();
        if (b != null) {
            pooled.decrementAndGet();
            b.clear();
            c.bb = b;
        } else {
            c.bb = ByteBuffer.allocate(16384);
        }
    }

    /**
     * INVOKEVIRTUAL ClientChunkRequest.releaseChunk 改道目標（addLoadedJob 例外路徑＋release()）。
     * {@code synchronized(c)} 原子摘取 bb：vanilla update() 的無同步 savedChunks 可讓
     * 同一 task 被 release 兩次——第二次摘到 null＝no-op，buffer 不會雙重入池。
     */
    public static void releaseChunk(ClientChunkRequest ccr, ClientChunkRequest.Chunk c) {
        if (!ENABLED) {
            ccr.releaseChunk(c);
            return;
        }
        ByteBuffer b;
        synchronized (c) {
            b = c.bb;
            c.bb = null;
        }
        if (b == null) {
            return;
        }
        // 軟上限：cap 檢查與 increment 非原子，併發下可微幅超出 256——可接受，
        // 硬性精確會需要鎖，不值得
        if (b.capacity() <= MAX_POOLED_CAPACITY && pooled.get() < MAX_POOLED_BUFFERS) {
            BUFFERS.add(b);
            pooled.incrementAndGet();
        }
        // 超限或超大：直接丟棄給 GC
    }

    private static void firstUse() {
        if (banner.compareAndSet(false, true)) {
            try {
                DebugLog.log("[MinidoracatJavaPatch][ChunkSaveIsolation] 首次生效"
                        + "（存檔管線私有池；-Dmdc.chunkSaveIsolation=0 停用）");
            } catch (RuntimeException | LinkageError ignored) {
                // 橫幅只是驗證便利，失敗不得影響存檔路徑
            }
        }
    }

    private ChunkSaveIsolation() {}
}
