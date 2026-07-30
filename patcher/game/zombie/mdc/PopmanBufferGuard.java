package zombie.mdc;

import zombie.debug.DebugType;

/**
 * ZombiePopulationManager.updateMain 的 add-zombie 分頁上限 clamp。
 *
 * <p>native n_getAddZombieData 偶爾回報超過 1024-byte DirectByteBuffer 容量的筆數
 * （每筆 29 bytes：getFloat×3 座標＋get×1 方向＋getInt×4），Java 端照數讀取即
 * BufferUnderflowException——整個 tick 的 popman 解析與 PathfindNative 泵送被外層
 * catch 一併跳過（正式服單日 77 筆實測）。</p>
 *
 * <p>只約束解析迴圈上限；offset += count 沿用 native 原回報值，分頁推進行為與原版
 * 逐位元一致。超額殘尾（count−MAX_RECORDS 筆）在 offset-served 與 consume-on-read
 * 兩種可能的 native 語意下都會被跳過——與原版 underflow 時的遺失範圍相同，此 patch
 * 不新增遺失、也**不是 lossless**，效果是把「整個 tick 陪葬」縮小為「只丟殘尾」。
 * native 端如何處置殘尾無法由 Java 端單獨證明。只在 server 主迴圈執行緒呼叫，不做同步。</p>
 */
@SuppressWarnings("removal")
public final class PopmanBufferGuard {

    /** ZombiePopulationManager 建構子的 allocateDirect(1024)；SmokeCheck 有可執行守門。 */
    static final int BUFFER_CAPACITY = 1024;
    /** updateMain 每筆讀取：getFloat×3＋get×1＋getInt×4 ＝ 29 bytes；SmokeCheck 有可執行守門。 */
    static final int RECORD_BYTES = 29;
    /** 1024 / 29 ＝ 35。 */
    static final int MAX_RECORDS = BUFFER_CAPACITY / RECORD_BYTES;

    private static long droppedTotal;

    public static int clampAddZombieCount(int count) {
        if (count <= MAX_RECORDS) {
            return count;
        }
        droppedTotal += count - MAX_RECORDS;
        safeLog(count);
        return MAX_RECORDS;
    }

    private static void safeLog(int count) {
        try {
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][PopmanBufferGuard] pageCount=" + count
                    + " > max=" + MAX_RECORDS + " clamped; droppedRecordsTotal=" + droppedTotal);
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
