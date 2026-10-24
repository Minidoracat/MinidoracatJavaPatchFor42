package zombie.mdc;

import zombie.debug.DebugLog;

/**
 * W70 Bandit Raid 劇情無限迴圈（2026-10-24；docs/patches.md 2cf）。
 *
 * <p><b>原版缺陷</b>：{@code RDSBanditRaid.randomizeDeadSurvivor} 在房間數大於 5 的建築多跑一段
 * 「{@code size = min(floor(房間數 / 10), 10)} 輪，每輪加殭屍與 2–3 具屍體」，但每輪內層放屍體的迴圈
 * 和外層共用同一個計數器（javap：兩層都用 local slot 9）。內層跑完計數器只剩 2 或 3，外層加 1 後回到 3 或 4，
 * 所以 {@code size} ≥ 5（50–100 間房；超過 100 間 {@code isValid} 不選）時外層永遠跑不完：
 * 主執行緒卡在載入那個 cell，一直生殭屍和屍體，直到 heap 用盡或被重啟（正式服 2026-10-24 凍結約 10 分鐘，
 * 關機存檔把約 4 萬隻殭屍和屍體存進世界）。原版地圖的建築多已探索過，新加地圖的建築才會碰到。
 *
 * <p><b>手術</b>：該方法唯一的 {@code Math.floor(D)D}（算 {@code size} 那一行）改道 {@link #floor}：
 * 結果大於 4 時回 4。原版 40–49 間房（{@code size} = 4）的建築迴圈本來就會結束，所以 50 間以上的建築
 * 改照 40–49 間的方式跑；49 間以下行為完全不變。
 *
 * <p>kill switch {@code -Dmdc.banditRaidLoopGuard=0|off}（直通原版）。需重啟。
 */
public final class BanditRaidLoopGuard {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.banditRaidLoopGuard"));
    /** 原版會跑完的最大輪數上限（40–49 間房）。 */
    static final double CAP = 4.0;
    private static final String TAG = "[MinidoracatJavaPatch][BanditRaidLoopGuard] ";

    private static long clamped;

    private BanditRaidLoopGuard() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** {@code RDSBanditRaid.randomizeDeadSurvivor} 內 {@code Math.floor(D)D} 的改道目標。 */
    public static double floor(double value) {
        double rounds = Math.floor(value);
        if (!ENABLED || !(rounds > CAP)) {
            return rounds;
        }
        clamped++;
        // 一次劇情一行；萬一異常頻繁，前 50 次之後每 1000 次記一行
        if (clamped <= 50 || clamped % 1000 == 0) {
            log("攔下 Bandit Raid 無限迴圈：輪數 " + (long) rounds + " → " + (long) CAP
                    + "（房間數約 " + (long) rounds * 10 + " 以上）clamped=" + clamped
                    + "（-Dmdc.banditRaidLoopGuard=0|off 回原版）");
        }
        return CAP;
    }

    private static void log(String msg) {
        try {
            DebugLog.log(TAG + msg);
        } catch (RuntimeException | LinkageError ignored) {
            // log 失敗不影響回傳值
        }
    }

    // ---- 測試存取器 ----
    static long clampedForTest() {
        return clamped;
    }
}
