package zombie.mdc;

import zombie.core.utils.DirectBufferAllocator;
import zombie.debug.DebugLog;

/**
 * Client 端貼圖載入管線觀測＋門檻修復 v1.1（42.20 invisible-entities 調查）。
 *
 * 原版 TextureIDAssetManager.waitFileTask 以 50MB 的全域 DirectBuffer 水位當硬門檻：
 * 超標時檔案載入執行緒無限 sleep（零 log、無 timeout），2–4 條執行緒全睡即整條資產
 * 管線（含車輛/人物 mesh）停擺——「新進視野的實體有影子沒模型」的第一環。
 *
 * v1.1 實測依據（blue 兩場 log）：水位「地板」單調上升永不下降（棘輪洩漏——ImageData
 * 解碼例外路徑無 dispose），~35 分鐘從 50MB 爬到 273MB 釘死在 v1 的 256MB 天花板→
 * 全部載入執行緒永久睡→隱形回歸。**天花板只買時間，任何上限終被地板追上**；
 * v1.1 把天花板加到 1GB——依 blue 實測斜率約 2–2.5 小時被追上（跑道≈v1 的 4 倍，
 * 無時間保證；且門檻為配置前檢查、多 worker 可同時通過，1GB 非硬上限，低 RAM 機器
 * 仍有 native OOM 風險），並加掛「地板」觀測量測洩漏速率，
 * root fix（ImageData dispose 修補）另行實作。洩漏是 process 級 static，relog
 * 清不掉（只有完全重開遊戲歸零）——玩家端指引以此為準。
 *
 * 手術兩刀都在 waitFileTask 方法範圍內：
 *   1. redirect —— getBytesAllocated() 改道到 {@link #bytesAllocatedObserved()}：回傳值
 *      原樣 passthrough（真實取值的例外照原版傳播）。非 fatal 的觀測例外一律吞掉、
 *      不改變載入行為；fatal（VirtualMachineError/ThreadDeath/LinkageError）照拋。
 *   2. constChange —— 門檻 52428800L（50MB）→ 1073741824L（1GB）。
 *
 * 門檻語意（codex 對抗審查修正）：這是「已解碼未上傳」pixel buffer 的水位，但
 * WrappedBuffer 走 LWJGL native malloc，**不受 -XX:MaxDirectMemorySize 約束**，
 * 門檻也是配置前檢查（多 worker 可同時通過），因此 1GB 不是硬上限——低 RAM
 * 機器請勿使用本 patch（建議 16GB+），部署後以 process RSS 與 hwm/floor log
 * 實測回饋。
 *
 * 觀測輸出（決策在鎖內、log 一律在鎖外，避免慢速 log 串行化載入執行緒）：
 *   - active：首次取樣宣告（log 成功才設旗標；競態下可能重複，無害）。
 *   - hwmBytes：高水位每跨一個 8MB 台階報一次。
 *   - stall 行：水位高於原版 50MB 門檻時每 5 秒至多一行，含 floorBytes（60 秒窗
 *     最低水位＝洩漏地板）與 aboveVanillaMs（連續超標毫秒）。
 *   - periodic 行：每 60 秒一行（無 stall/hwm 行時），追蹤地板爬升速率——
 *     floorBytes 單調上升＝洩漏進行中的直接證據。
 */
public final class TexturePipelineGuard {

    /** 原版 waitFileTask 門檻（觀測分類用；SmokeCheck 與 jar 內 ldc2_w 前提對帳）。 */
    public static final long VANILLA_LIMIT_BYTES = 52428800L;
    /** constChange 後的實際門檻（SmokeCheck 與 patched class 的 ldc2_w 連動對帳）。 */
    public static final long PATCHED_LIMIT_BYTES = 1073741824L;

    private static final long LOG_INTERVAL_NS = 5_000_000_000L;
    private static final long PERIODIC_INTERVAL_NS = 60_000_000_000L;
    private static final long HWM_STEP_BYTES = 8_388_608L;   // 8MB

    private static long highWaterBytes;
    private static long nextHwmReportBytes = HWM_STEP_BYTES;
    private static long floorWindowBytes = Long.MAX_VALUE;   // 60 秒窗最低水位（洩漏地板）
    private static long vanillaStallSamples;
    private static long patchedStallSamples;
    private static long aboveVanillaSinceNs;                 // 0＝目前不在超標區間
    private static long lastStallLogNs;
    private static long lastPeriodicLogNs;
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
     * 只組字串不做 I/O，log 由呼叫端在鎖外送出。每次至多回傳一行
     * （優先序：stall ＞ hwm ＞ periodic）。
     */
    private static synchronized String observe(long bytes) {
        long now = System.nanoTime();
        String line = null;
        if (bytes < floorWindowBytes) {
            floorWindowBytes = bytes;
        }
        if (bytes > highWaterBytes) {
            highWaterBytes = bytes;
            if (bytes >= nextHwmReportBytes) {
                nextHwmReportBytes = (bytes / HWM_STEP_BYTES + 1L) * HWM_STEP_BYTES;
                line = "[MinidoracatJavaPatch][TexPipelineGuard] hwmBytes=" + bytes;
            }
        }
        if (bytes > VANILLA_LIMIT_BYTES) {
            if (aboveVanillaSinceNs == 0L) {
                aboveVanillaSinceNs = now;
            }
            vanillaStallSamples++;
            if (bytes > PATCHED_LIMIT_BYTES) {
                patchedStallSamples++;
            }
            if (now - lastStallLogNs >= LOG_INTERVAL_NS) {
                lastStallLogNs = now;
                // stall 行已含 hwm/floor，覆蓋同回合的 hwm 行
                line = "[MinidoracatJavaPatch][TexPipelineGuard] bytes=" + bytes
                        + " hwmBytes=" + highWaterBytes
                        + " floorBytes=" + floorWindowBytes
                        + " aboveVanillaMs=" + (now - aboveVanillaSinceNs) / 1_000_000L
                        + " vanillaStallSamples=" + vanillaStallSamples
                        + " patchedStallSamples=" + patchedStallSamples;
            }
        } else {
            aboveVanillaSinceNs = 0L;
        }
        if (now - lastPeriodicLogNs >= PERIODIC_INTERVAL_NS) {
            lastPeriodicLogNs = now;
            // 地板爬升＝洩漏速率的直接量測；遊戲載入前的零水位期不報
            if (line == null && highWaterBytes >= HWM_STEP_BYTES) {
                line = "[MinidoracatJavaPatch][TexPipelineGuard] periodic bytes=" + bytes
                        + " floorBytes=" + floorWindowBytes
                        + " hwmBytes=" + highWaterBytes
                        + " vanillaStallSamples=" + vanillaStallSamples
                        + " patchedStallSamples=" + patchedStallSamples;
            }
            floorWindowBytes = bytes;   // 重置 60 秒窗
        }
        return line;
    }
}
