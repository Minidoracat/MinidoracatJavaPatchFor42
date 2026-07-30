package zombie.mdc;

import java.nio.ByteBuffer;

import zombie.debug.DebugType;

/**
 * ZombiePopulationManager.updateMain 的 add-zombie 分頁上限 clamp（v2）。
 *
 * <p>v1（上限固定 1024/29=35）部署後線上否證：BufferUnderflowException 仍發生且 clamp
 * 從未觸發——代表失敗頁面的 count ≤ 35 但 buffer limit &lt; count×29，即 native 會把
 * limit 設為實際寫入量，炸點是「count 與實際寫入筆數不符」而非容量溢位。</p>
 *
 * <p>v2 改以呼叫當下的 {@code buffer.remaining()/29} 為上限（clear() 後 position=0，
 * remaining=limit=實際可讀 bytes）：native 有設 limit 時上限=實際寫入筆數，
 * 沒設 limit 時 remaining=1024、上限=35=v1 行為——兩種語意都涵蓋。仍非 lossless：
 * 超額殘尾與原版 underflow 時同樣讀不到，價值是不再讓整個 tick 的 popman 解析與
 * PathfindNative 泵送陪葬。若部署後例外仍存在，代表每筆 29 bytes 的固定格式假設
 * 也錯了（變長記錄），屆時需改逐筆 remaining 檢查。只在 server 主迴圈執行緒呼叫。</p>
 */
@SuppressWarnings("removal")
public final class PopmanBufferGuard {

    /** updateMain 每筆讀取：getFloat×3＋get×1＋getInt×4 ＝ 29 bytes；SmokeCheck 有可執行守門。 */
    static final int RECORD_BYTES = 29;

    private static long droppedTotal;

    public static int clampAddZombieCount(int count, ByteBuffer buffer) {
        int readable = buffer.remaining() / RECORD_BYTES;
        if (count <= readable) {
            return count;
        }
        droppedTotal += count - readable;
        safeLog(count, readable, buffer.remaining());
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
