package zombie.mdc;

import java.nio.ByteBuffer;

import zombie.debug.DebugType;

/**
 * ZombiePopulationManager add-zombie 讀取路徑的 buffer 隔離（v3，root fix）＋分頁上限 clamp（保險）。
 *
 * <p>根因（codex 對抗審查證實＋v2 clamp 線上取得位元組級 overlap 證據
 * <code>pageCount=35 &gt; readable=28 (remainingBytes=814)</code>，1024−814=210=10×21 恰為
 * 寫側 10 筆記錄）：vanilla 的 <code>this.byteBuffer</code> 由 MapCollisionData 背景執行緒的
 * 寫側（writeCellSnapshot，saveLock 互斥）與主執行緒 updateMain 讀側共用，而讀側無鎖——
 * 共享 position 併發亂跳 → 隨機欄位 BufferUnderflowException＋混讀損毀的殭屍資料。</p>
 *
 * <p>v3 修法：updateMain 內全部 10 處 <code>getfield byteBuffer</code> 之後插入
 * {@link #updateMainBuffer}，把讀側（clear、n_getAddZombieData 寫入目標、8 個欄位讀取）
 * 全部改用本類的 {@link #UPDATE_MAIN_BUFFER} 專用 buffer——讀寫徹底隔離、零鎖零死鎖。
 * native 只有 n_getAddZombieData/n_saveRealZombies 收 ByteBuffer 且皆為逐呼叫傳參
 * （n_init 無 buffer 註冊），無快取位址假設。寫側（beginSaveRealZombies 主執行緒、
 * writeCellSnapshot MCD 執行緒）維持 vanilla 的 this.byteBuffer＋saveLock 紀律，不動。</p>
 *
 * <p>{@link #clampAddZombieCount} 為隔離後的保險絲：正常情況 native 回報數不會超過
 * remaining/29，clamp 永不觸發；若觸發即代表仍有未知失配並記 log。
 * UPDATE_MAIN_BUFFER 僅主執行緒觸碰，不做同步。</p>
 */
@SuppressWarnings("removal")
public final class PopmanBufferGuard {

    /** updateMain 每筆讀取：getFloat×3＋get×1＋getInt×4 ＝ 29 bytes；SmokeCheck 有可執行守門。 */
    static final int RECORD_BYTES = 29;

    /** 容量鏡射 vanilla 的 allocateDirect(1024)；SmokeCheck 以 <init> 實參與此值連動守門。 */
    static final ByteBuffer UPDATE_MAIN_BUFFER = ByteBuffer.allocateDirect(1024);

    private static long droppedTotal;

    /** getfield byteBuffer 之後的同形替換點（1→1，吃掉共享 buffer、回傳專用 buffer）。 */
    public static ByteBuffer updateMainBuffer(ByteBuffer sharedIgnored) {
        return UPDATE_MAIN_BUFFER;
    }

    public static int clampAddZombieCount(int count) {
        int readable = UPDATE_MAIN_BUFFER.remaining() / RECORD_BYTES;
        if (count <= readable) {
            return count;
        }
        droppedTotal += count - readable;
        safeLog(count, readable, UPDATE_MAIN_BUFFER.remaining());
        return readable;
    }

    private static void safeLog(int count, int readable, int remaining) {
        try {
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][PopmanBufferGuard] pageCount=" + count
                    + " > readable=" + readable + " (remainingBytes=" + remaining
                    + ") clamped; droppedRecordsTotal=" + droppedTotal);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (failure instanceof ThreadDeath fatal) {
                throw fatal;
            }
            if (failure instanceof LinkageError fatal) {
                throw fatal;
            }
            // 非致命的 log sink 失敗不得影響 popman 解析。
        }
    }

    private PopmanBufferGuard() {}
}
