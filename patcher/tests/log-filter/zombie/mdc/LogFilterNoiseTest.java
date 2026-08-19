package zombie.mdc;

/**
 * LogFilter warnObj 攔截判定行為鎖——OBJ_EXACT 從 9 名放大到 19 名後，
 * 人工核對錯誤（貼錯名、誤植 OBJ_PREFIX、誤加尾綴）不再有任何建置期防線
 * （LoadCheck 只驗簽名、SmokeCheck 只驗命中數），本測試補上：
 * <ul>
 *   <li>equals 紀律：名單名精確命中，延伸名／截斷名必須放行（寧漏不誤）；</li>
 *   <li>反作弊鐵則：anticheat 訊息任何情況下不得被攔（AGENTS.md 手術鐵則）；</li>
 *   <li>未知訊息（含 "scripted object = null"）照常轉發。</li>
 * </ul>
 * 只測 pure 判定函式 {@link LogFilter#suppressesObj(String)}——DebugType 轉發
 * 路徑是 3 行 if-return，形狀由 code review 守；測試 JVM 不初始化 DebugLog 體系。
 */
public final class LogFilterNoiseTest {

    private static final String P = "Invalid SpriteConfig object! scripted object = ";

    public static void main(String[] args) {
        // 名單抽樣：新舊區塊各取首尾（誤刪整段時至少一條爆）
        requireSuppressed(P + "MetalBigWireFence");          // 初版名單首
        requireSuppressed(P + "WoodenWallLvl3");             // 42.20 區塊尾
        requireSuppressed(P + "SandFloor");                  // 42.20.3 區塊首（6237 筆）
        requireSuppressed(P + "Fences_MetalFarmGate");       // 42.20.3 區塊尾（150 筆，門檻邊界）
        // equals 紀律：延伸名／截斷名／未知名／null 字面全部放行
        requireForwarded(P + "SandFloorX");
        requireForwarded(P + "SandFloo");
        requireForwarded(P + "NotInAnyList");
        requireForwarded(P + "null");
        requireForwarded("");
        // 門檻裁決的刻意不收名（42.20.3 實測 86／80 筆，<4 筆/h）——收錄即違反入列門檻
        requireForwarded(P + "BrickDoorFrameLvl2");
        requireForwarded(P + "ComposterShoddy");
        // 反作弊鐵則：這些訊息不經 warnObj，但若未來有人誤加名單，此處必爆
        requireForwarded("The packet Login is not valid");
        requireForwarded("packet.sync recovered zombie 123");
        // OBJ_PREFIX 既有行為鎖（PacketsCache 動態尾綴）
        requireSuppressed("No packet handler for type: 999");
        requireForwarded("No packet handler for typo: 999");
        System.out.println("log-filter OK  equals 紀律／門檻不收名／反作弊放行／prefix 行為全數通過");
    }

    private static void requireSuppressed(String msg) {
        if (!LogFilter.suppressesObj(msg)) {
            throw new AssertionError("名單內訊息必須被攔：" + msg);
        }
    }

    private static void requireForwarded(String msg) {
        if (LogFilter.suppressesObj(msg)) {
            throw new AssertionError("名單外訊息必須放行：" + msg);
        }
    }

    private LogFilterNoiseTest() {}
}
