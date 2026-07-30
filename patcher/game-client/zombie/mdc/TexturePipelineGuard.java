package zombie.mdc;

import zombie.core.utils.DirectBufferAllocator;
import zombie.debug.DebugLog;

/**
 * Client 端貼圖載入管線觀測＋門檻修復（42.20 invisible-entities 調查）。
 *
 * 原版 TextureIDAssetManager.waitFileTask 以 50MB 的全域 DirectBuffer 水位當硬門檻：
 * 超標時檔案載入執行緒無限 sleep（零 log、無 timeout），2–4 條執行緒全睡即整條資產
 * 管線（含車輛/人物 mesh）停擺——「新進視野的實體有影子沒模型」的第一環。
 *
 * 手術兩刀都在 waitFileTask 方法範圍內：
 *   1. redirect —— getBytesAllocated() 改道到 {@link #bytesAllocatedObserved()}：回傳值
 *      原樣 passthrough（真實取值的例外照原版傳播）。非 fatal 的觀測例外一律吞掉、
 *      不改變載入行為；fatal（VirtualMachineError/ThreadDeath/LinkageError）照拋。
 *   2. constChange —— 門檻 52428800L（50MB）→ 268435456L（256MB）。
 *
 * 門檻語意（codex 對抗審查修正）：這是「已解碼未上傳」pixel buffer 的水位，但
 * WrappedBuffer 走 LWJGL native malloc，**不受 -XX:MaxDirectMemorySize 約束**，
 * 門檻也是配置前檢查（多 worker 可同時通過），因此 256MB 不是硬上限——它把
 * 「vanilla 會 throttle 的時刻」換成「繼續吃 native RAM」。256MB 是針對本次受害
 * client（高 RAM 機器）選的實驗值；低 RAM 機器請勿使用本 patch，部署後需以
 * process RSS 與 hwm log 實測回饋再定案。
 *
 * 觀測輸出（決策在鎖內、log 一律在鎖外，避免慢速 log 串行化載入執行緒）：
 *   - active：首次取樣宣告（log 成功才設旗標，boot 極早期 DebugLog 未就緒時下次
 *     取樣重試；競態下可能重複一行，無害）——此行是安裝驗證契約。
 *   - hwmBytes：高水位每跨一個 8MB 台階報一次。
 *   - stall：水位高於「原版 50MB 門檻」時每 5 秒至多一行，含 aboveVanillaMs＝
 *     本次連續超標已持續毫秒數。vanillaStallSamples 的語意是 would-enter-wait
 *     取樣數（原版在該取樣點會進入至少一次 20ms 等待），連續 stall 行＋
 *     aboveVanillaMs 才是持續饑餓的證據；patchedStallSamples>0＝256MB 仍不夠。
 */
public final class TexturePipelineGuard {

    /** 原版 waitFileTask 門檻（觀測分類用；SmokeCheck 與 jar 內 ldc2_w 前提對帳）。 */
    public static final long VANILLA_LIMIT_BYTES = 52428800L;
    /** constChange 後的實際門檻（SmokeCheck 與 patched class 的 ldc2_w 連動對帳）。 */
    public static final long PATCHED_LIMIT_BYTES = 268435456L;

    private static final long LOG_INTERVAL_NS = 5_000_000_000L;
    private static final long HWM_STEP_BYTES = 8_388_608L;   // 8MB

    private static long highWaterBytes;
    private static long nextHwmReportBytes = HWM_STEP_BYTES;
    private static long vanillaStallSamples;
    private static long patchedStallSamples;
    private static long aboveVanillaSinceNs;                 // 0＝目前不在超標區間
    private static long lastStallLogNs;
    private static volatile boolean announced;

    private TexturePipelineGuard() {}

    /** waitFileTask 的 getBytesAllocated() 改道點：passthrough＋觀測，簽名同形 ()J。 */
    public static long bytesAllocatedObserved() {
        long bytes = DirectBufferAllocator.getBytesAllocated();
        try {
            if (!announced) {
                DebugLog.log("[MinidoracatJavaPatch][TexPipelineGuard] active vanillaLimitBytes="
                        + VANILLA_LIMIT_BYTES + " patchedLimitBytes=" + PATCHED_LIMIT_BYTES);
                // log 成功才設旗標：DebugLog 未就緒（例外被下方吞掉）時下次取樣重試
                announced = true;
            }
            String line = observe(bytes);
            if (line != null) {
                DebugLog.log(line);
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError || t instanceof ThreadDeath || t instanceof LinkageError) {
                throw t;
            }
            // 非 fatal 觀測例外一律吞掉——不改變載入執行緒的行為
        }
        return bytes;
    }

    /**
     * 狀態更新與訊息決策（waitFileTask 由 2–4 條檔案執行緒併發呼叫）；
     * 只組字串不做 I/O，log 由呼叫端在鎖外送出。每次至多回傳一行。
     */
    private static synchronized String observe(long bytes) {
        String line = null;
        if (bytes > highWaterBytes) {
            highWaterBytes = bytes;
            if (bytes >= nextHwmReportBytes) {
                nextHwmReportBytes = (bytes / HWM_STEP_BYTES + 1L) * HWM_STEP_BYTES;
                line = "[MinidoracatJavaPatch][TexPipelineGuard] hwmBytes=" + bytes;
            }
        }
        if (bytes > VANILLA_LIMIT_BYTES) {
            long now = System.nanoTime();
            if (aboveVanillaSinceNs == 0L) {
                aboveVanillaSinceNs = now;
            }
            vanillaStallSamples++;
            if (bytes > PATCHED_LIMIT_BYTES) {
                patchedStallSamples++;
            }
            if (now - lastStallLogNs >= LOG_INTERVAL_NS) {
                lastStallLogNs = now;
                // stall 行已含 hwm，覆蓋同回合的 hwm 行
                line = "[MinidoracatJavaPatch][TexPipelineGuard] bytes=" + bytes
                        + " hwmBytes=" + highWaterBytes
                        + " aboveVanillaMs=" + (now - aboveVanillaSinceNs) / 1_000_000L
                        + " vanillaStallSamples=" + vanillaStallSamples
                        + " patchedStallSamples=" + patchedStallSamples;
            }
        } else {
            aboveVanillaSinceNs = 0L;
        }
        return line;
    }
}
