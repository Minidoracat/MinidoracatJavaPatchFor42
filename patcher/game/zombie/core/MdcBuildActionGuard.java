package zombie.core;

import java.lang.reflect.Field;

import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/**
 * W76 建造類別白名單＋W77 建造距離（docs/patches.md 2cl）：伺服器端建造請求驗證。
 *
 * <p>掛在 {@code BuildAction.parse} 內唯一的 {@code LuaCaller.protectedCall}（建造物件的建構子呼叫）之前：
 * <ul>
 *   <li><b>W76</b>：{@code arguments[0]}（請求指名的全域類別表）必須是全域 {@code ISBuildingObject} 的子類
 *       （沿 metatable 鏈找，不寫死名單），否則不呼叫建構子。</li>
 *   <li><b>W77</b>：請求座標與 owner 的位置，水平每軸差 ≤ {@link #MAX_RANGE}、樓層差 ≤ {@link #MAX_DZ}；
 *       role 有 {@code UseBuildCheat} 的管理員豁免（作弊建造可遠距建造）。</li>
 * </ul>
 * 不通過時回 {@code getFirst()==null} 的 LuaReturn：原版 parse 設 {@code item = null}，{@code processServer} 照原版回 Reject，
 * 客戶端不會卡住。x／y／z 是 {@code BuildAction} 的 private 欄位，class init 時反射快取，找不到即外逃（fail-fast）。
 *
 * <p>開關：{@code -Dmdc.buildTypeCheck=0|off}（W76）、{@code -Dmdc.buildRangeCheck=0|off}（W77），預設都開，需重啟。
 * 檢查本身拋 {@code RuntimeException} 時計 anomalies、照原版呼叫建構子（不因 helper 自身問題擋掉正常建造）。
 */
public final class MdcBuildActionGuard {
    private static final String TAG = "[MinidoracatJavaPatch][BuildActionGuard]";

    static final boolean TYPE_CHECK = flag("mdc.buildTypeCheck");
    static final boolean RANGE_CHECK = flag("mdc.buildRangeCheck");

    /** 原版合法最大約 5.1 格（4 格寬雙門的原點格），加伺服器端位置落後的餘量。 */
    static final float MAX_RANGE = 8.0F;
    /** 游標 z 就是玩家自己的樓層；爬梯時玩家 z 是小數，留到 1.5。 */
    static final float MAX_DZ = 1.5F;
    /** metatable 鏈上限（防循環）；原版最深兩層 derive。 */
    private static final int MAX_DEPTH = 16;

    /** 拒絕＝原版 {@code result.getFirst() == null} 分支（LuaSuccess 帶一個 nil 回傳值）。 */
    private static final LuaReturn REFUSED = LuaReturn.createReturn(new Object[]{Boolean.TRUE, null});

    private static final Field X = field("x");
    private static final Field Y = field("y");
    private static final Field Z = field("z");
    /** 客戶端給的 Type 字串（原版 parse 先存進這個欄位），只用於 log。 */
    private static final Field OBJECT_TYPE = field("objectType");

    private static final ThreadLocal<BuildAction> PARSING = new ThreadLocal<>();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;

    // 封包處理在主迴圈單執行緒；計數與時間窗容忍罕見交錯。
    private static long typeRefused;
    private static long rangeRefused;
    private static long cheatExempt;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;
    private static boolean bannerShown;

    private MdcBuildActionGuard() {
    }

    /** {@code BuildAction.parse} 頭部：綁定本次 parse 的封包（連線的封包物件會重用）。 */
    public static void beginParse(BuildAction action) {
        PARSING.set(action);
    }

    /** {@code BuildAction.parse} 內唯一 {@code LuaCaller.protectedCall}（{@code <Type>.new}）的改道；上下文取用即清。 */
    public static LuaReturn protectedCall(LuaCaller caller, KahluaThread thread, Object fn, Object[] args) {
        BuildAction action = PARSING.get();
        PARSING.remove();
        if (action == null || !GameServer.server) {
            return caller.protectedCall(thread, fn, args);
        }
        if (!bannerShown) {
            showBanner();
        }
        String reason = null;
        try {
            reason = refuseReason(action, args);
        } catch (RuntimeException e) {
            anomalies++;
        }
        if (reason != null) {
            report(action, args, reason);
            return REFUSED;
        }
        return caller.protectedCall(thread, fn, args);
    }

    private static String refuseReason(BuildAction action, Object[] args) {
        if (TYPE_CHECK && !isBuildingClass(args == null || args.length == 0 ? null : args[0])) {
            typeRefused++;
            return "type";
        }
        if (RANGE_CHECK && !inRange(action)) {
            rangeRefused++;
            return "range";
        }
        return null;
    }

    /** 全域表的 metatable 鏈（不含自己）經過全域 {@code ISBuildingObject}；{@code ISBuildingObject} 本身不算。 */
    static boolean isBuildingClass(Object cls) {
        if (!(cls instanceof KahluaTable table) || LuaManager.env == null
                || !(LuaManager.env.rawget("ISBuildingObject") instanceof KahluaTable base)) {
            return false;
        }
        KahluaTable t = table.getMetatable();
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++) {
            if (t == base) {
                return true;
            }
            t = t.getMetatable();
        }
        return false;
    }

    private static boolean inRange(BuildAction action) {
        IsoPlayer owner = action.playerId.getPlayer();
        if (owner == null) {
            return false;
        }
        if (canBuildCheat(owner)) {
            cheatExempt++;
            return true;
        }
        try {
            // NaN／Infinity 的比較一律為 false，所以寫成「≤ 才放行」。
            return Math.abs(owner.getX() - X.getFloat(action)) <= MAX_RANGE
                    && Math.abs(owner.getY() - Y.getFloat(action)) <= MAX_RANGE
                    && Math.abs(owner.getZ() - Z.getFloat(action)) <= MAX_DZ;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 伺服器端 role 的能力（管理面板的建造作弊鈕只改客戶端旗標，{@code isBuildCheat()} 在伺服器上不可靠）。 */
    private static boolean canBuildCheat(IsoPlayer owner) {
        Role role = owner.getRole();
        return role != null && role.hasCapability(Capability.UseBuildCheat);
    }

    private static void showBanner() {
        bannerShown = true;
        try {
            DebugLog.log(TAG + " 首次生效 typeCheck=" + onOff(TYPE_CHECK) + " rangeCheck=" + onOff(RANGE_CHECK)
                    + " maxRange=" + MAX_RANGE + " maxDz=" + MAX_DZ
                    + "（-Dmdc.buildTypeCheck=0|off、-Dmdc.buildRangeCheck=0|off 關閉）.");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static void report(BuildAction action, Object[] args, String reason) {
        try {
            if (!allowLine()) {
                return;
            }
            IsoPlayer owner = action.playerId.getPlayer();
            DebugLog.log(TAG + " refused reason=" + reason
                    + " type=" + MdcTimedActionProbe.safeName((String) OBJECT_TYPE.get(action))
                    + " owner=" + (owner == null ? "?" : owner.getOnlineID() + ":" + MdcTimedActionProbe.safeName(owner.getUsername()))
                    + " at=" + (owner == null ? "?" : owner.getX() + "," + owner.getY() + "," + owner.getZ())
                    + " target=" + X.getFloat(action) + "," + Y.getFloat(action) + "," + Z.getFloat(action)
                    + " typeRefused=" + typeRefused + " rangeRefused=" + rangeRefused
                    + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException | IllegalAccessException e) {
            anomalies++;
        }
    }

    private static boolean allowLine() {
        long now = System.nanoTime();
        if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed++;
            return false;
        }
        windowCount++;
        return true;
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static boolean flag(String name) {
        String raw = System.getProperty(name);
        if (raw == null) {
            return true;
        }
        String v = raw.trim();
        return !("0".equals(v) || "off".equalsIgnoreCase(v));
    }

    private static Field field(String name) {
        try {
            Field f = BuildAction.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            throw new IllegalStateException(TAG + " BuildAction." + name + " 欄位不存在，jar 不相容", e);
        }
    }

    // ---- 測試存取器 ----

    static long typeRefusedForTest() {
        return typeRefused;
    }

    static long rangeRefusedForTest() {
        return rangeRefused;
    }

    static long cheatExemptForTest() {
        return cheatExempt;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static boolean parseBoundForTest() {
        return PARSING.get() != null;
    }
}
