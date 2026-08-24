package zombie.mdc;

/**
 * W15 主迴圈凍結看門狗行為驗證（獨立 JVM；MODE／THRESHOLD 是 static final，
 * 由 build.ps1 以不同 property 各跑一次）：
 * <ul>
 *   <li>{@code on}（-Dmdc.mainLoopWatchdogThresholdMs=500）：clamp 下限咬住（500→1000）、
 *       tick 記錄、凍結偵測＋快照、健康期恢復（不重入）、再凍結重入狀態機、anomalies=0。</li>
 *   <li>{@code off}（-Dmdc.mainLoopWatchdog=0）：kill switch 純早退——零記錄、零偵測、
 *       零快照（watchdog 執行緒根本不啟動）。</li>
 *   <li>{@code clamp}（-Dmdc.mainLoopWatchdogThresholdMs=999999）：上限咬住（→600000）。</li>
 * </ul>
 *
 * <p>時序依據（POLL_MS=1000 為 helper 常數）：tick 之後 watchdog 在 ≤1s 內必有一次輪詢
 * （幀齡 &lt;1000ms ⇒ 恢復判定成立）；停止 tick 後幀齡首次跨過 1000ms 門檻的輪詢最晚發生在
 * 2.0s 內（首輪相位 ≤1s ＋ 下一輪 +1s），故凍結等待取 2.6s（餘裕 0.6s）、健康期以 150ms
 * 間隔連續 tick（幀齡恆 &lt;1000ms，任何相位的輪詢都判健康）。
 */
public final class MainLoopWatchdogTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        switch (mode) {
            case "on":
                runOn();
                break;
            case "off":
                runOff();
                break;
            case "clamp":
                runClamp();
                break;
            default:
                throw new IllegalArgumentException("unknown mode: " + mode);
        }
        check("helper 自身無異常（anomalies=0）", MainLoopWatchdog.anomalyCount() == 0);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("main-loop-watchdog OK  mode=" + mode);
    }

    private static void runOn() throws Exception {
        check("自驗：argv=on ⇒ mode 應為 1（實際 " + MainLoopWatchdog.mode() + "）",
                MainLoopWatchdog.mode() == 1);
        check("clamp 下限：property 500 ⇒ thresholdMs=1000（實際 "
                + MainLoopWatchdog.thresholdMs() + "）", MainLoopWatchdog.thresholdMs() == 1000L);

        MainLoopWatchdog.tick(null);
        check("tick 記錄：tickCount=1", MainLoopWatchdog.tickCount() == 1L);
        check("tick 記錄：lastTickNanos>0", MainLoopWatchdog.lastTickNanosForTest() > 0L);

        // 凍結：停止 tick 2.6s（門檻 1000ms＋輪詢相位最壞 2.0s＋餘裕）
        Thread.sleep(2_600L);
        check("凍結偵測：stalls=1（實際 " + MainLoopWatchdog.stallCount() + "）",
                MainLoopWatchdog.stallCount() == 1L);
        check("凍結快照：dumps>=1（實際 " + MainLoopWatchdog.dumpCount() + "）",
                MainLoopWatchdog.dumpCount() >= 1L);

        // 健康期：150ms 間隔連續 tick 1.2s——幀齡恆 <1000ms，watchdog 必判恢復且不重入
        for (int i = 0; i < 8; i++) {
            MainLoopWatchdog.tick(null);
            Thread.sleep(150L);
        }
        check("恢復期不重入：stalls 仍為 1", MainLoopWatchdog.stallCount() == 1L);

        // 再凍結：狀態機重入（證明恢復判定真的發生過，而非第一次 stall 永久黏著）
        Thread.sleep(2_600L);
        check("再凍結重入：stalls=2（實際 " + MainLoopWatchdog.stallCount() + "）",
                MainLoopWatchdog.stallCount() == 2L);
    }

    private static void runOff() throws Exception {
        check("自驗：argv=off ⇒ mode 應為 0（實際 " + MainLoopWatchdog.mode() + "）",
                MainLoopWatchdog.mode() == 0);
        MainLoopWatchdog.tick(null);
        MainLoopWatchdog.tick(null);
        MainLoopWatchdog.tick(null);
        check("kill switch：tick 早退不記錄（tickCount=0）", MainLoopWatchdog.tickCount() == 0L);
        check("kill switch：lastTickNanos 未寫入", MainLoopWatchdog.lastTickNanosForTest() == 0L);
        Thread.sleep(2_200L);
        check("kill switch：零凍結偵測", MainLoopWatchdog.stallCount() == 0L);
        check("kill switch：零快照", MainLoopWatchdog.dumpCount() == 0L);
    }

    private static void runClamp() {
        check("clamp 上限：property 999999 ⇒ thresholdMs=600000（實際 "
                + MainLoopWatchdog.thresholdMs() + "）",
                MainLoopWatchdog.thresholdMs() == 600_000L);
        MainLoopWatchdog.tick(null);
        MainLoopWatchdog.tick(null);
        check("tick 照常記錄（tickCount=2）", MainLoopWatchdog.tickCount() == 2L);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "wdt pass  " : "wdt FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private MainLoopWatchdogTest() {}
}
