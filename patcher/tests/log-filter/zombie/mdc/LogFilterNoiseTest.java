package zombie.mdc;

/**
 * LogFilter warnObj 攔截判定行為鎖（全名單雙向鏡像）——OBJ_EXACT 從 9 名放大到
 * 19 名後，人工核對錯誤不再有建置期防線（LoadCheck 只驗簽名、SmokeCheck 只驗
 * 命中數），本測試鎖住：
 * <ul>
 *   <li>雙向鏡像：19 個保留名逐條必攔、17 個依門檻剔除名逐條必放行——鏡像是刻意的
 *       雙重記帳：改 OBJ_EXACT 必須同步改本測試，一換一貼錯／誤刪／誤復活都會爆；</li>
 *   <li>equals 紀律：延伸名／截斷名／未知名／空串必須放行（寧漏不誤）；</li>
 *   <li>名單防呆：反作弊訊息不經 warnObj（真正的路徑保護是 SmokeCheck 的 patch
 *       surface 結構斷言），此處僅斷言「名單不得含反作弊字串」——防未來誤加。</li>
 * </ul>
 * 只測 pure 判定函式 {@link LogFilter#suppressesObj(String)}——DebugType 轉發
 * 路徑是 3 行 if-return，形狀由 code review 守；測試 JVM 不初始化 DebugLog 體系。
 */
public final class LogFilterNoiseTest {

    private static final String P = "Invalid SpriteConfig object! scripted object = ";

    /** OBJ_EXACT 的 SpriteConfig 段完整鏡像（3 初版＋6 42.20＋10 42.20.3）。 */
    private static final String[] SUPPRESSED_NAMES = {
        "MetalBigWireFence", "WoodFloorLvl3", "Wooden_Windows",
        "DoubleWireGate", "BrickWallLvl2", "MetalSmallWireFence",
        "BrickWindowFrameLvl2", "Piano", "WoodenWallLvl3",
        "SandFloor", "WoodenDarkWallLvl3", "GravelFloor", "Floor_SpringGrass",
        "DoubleDoor", "WoodenWindowFrameLvl3", "WoodFloorLvl2",
        "Wood_DoubleDoorDark", "WoodDoorFrameLvl3", "Fences_MetalFarmGate",
    };

    /** 42.20.3 依入列門檻（≥4 筆/h）刻意剔除的 17 名（86～2 筆/26h）——必須放行。 */
    private static final String[] REJECTED_BY_THRESHOLD = {
        "BrickDoorFrameLvl2", "ComposterShoddy", "Composter", "MetalFloorLvl1",
        "LogGate", "BrickFloorLvl1", "Wood_FancyBookCase", "WoodenDarkDoorFrameLvl3",
        "Wood_Crate_Lvl2", "Commercial_GridGlassRedWall", "Commercial_FullGlassBlackWall",
        "WoodenPole", "Commercial_HalfGlassBlackWall", "Commercial_HalfGlassRedWall",
        "Commercial_GridGlassBlackWall", "Commercial_FullGlassRedWall", "Campfire",
    };

    public static void main(String[] args) {
        // 規模鎖：OBJ_EXACT 收了鏡像之外的名字時，19 條 suppressed 全過也會在此爆
        if (LogFilter.objExactCountForTest() != SUPPRESSED_NAMES.length) {
            throw new AssertionError("OBJ_EXACT 應為 " + SUPPRESSED_NAMES.length
                    + " 名（3 初版＋6 42.20＋10 42.20.3），實得 " + LogFilter.objExactCountForTest()
                    + "——名單增減必須同步本測試與 docs");
        }
        // 雙向鏡像：保留名逐條必攔、門檻剔除名逐條必放行
        for (String name : SUPPRESSED_NAMES) {
            requireSuppressed(P + name);
        }
        for (String name : REJECTED_BY_THRESHOLD) {
            requireForwarded(P + name);
        }
        // equals 紀律：延伸名／截斷名／未知名／null 字面／空串全部放行
        requireForwarded(P + "SandFloorX");
        requireForwarded(P + "SandFloo");
        requireForwarded(P + "NotInAnyList");
        requireForwarded(P + "null");
        requireForwarded("");
        // 名單防呆：反作弊訊息不經 warnObj；若未來有人誤加進名單，此處必爆
        requireForwarded("The packet Login is not valid");
        requireForwarded("packet.sync recovered zombie 123");
        // OBJ_PREFIX 既有行為鎖（PacketsCache 動態尾綴）
        requireSuppressed("No packet handler for type: 999");
        requireForwarded("No packet handler for typo: 999");
        // 抑噪 #9 println 名單：只攔 IsoThumpable 的 not-found；同方法的 square-is-null、
        // 其他 class 的 not-found（破損訊號）、null 一律放行
        require(LogFilter.suppressesPrintln("ERROR: IsoThumpable not found on square 8769,15252,0"),
                "IsoThumpable not-found 必須被攔");
        require(!LogFilter.suppressesPrintln("ERROR: IsoThumpable square is null"),
                "square-is-null 必須放行");
        require(!LogFilter.suppressesPrintln("ERROR: IsoDoor not found on square 1,2,0"),
                "其他 class 的 not-found 必須放行");
        require(!LogFilter.suppressesPrintln("ERROR: IsoThumpable not found on squar"),
                "截斷前綴必須放行");
        require(!LogFilter.suppressesPrintln(null), "null 必須放行（不得 NPE）");
        System.out.println("log-filter OK  雙向鏡像 19+17／equals 紀律／名單防呆／prefix 行為／println 名單全數通過");
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

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private LogFilterNoiseTest() {}
}
