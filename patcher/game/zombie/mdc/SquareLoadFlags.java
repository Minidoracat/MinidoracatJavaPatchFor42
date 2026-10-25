package zombie.mdc;

import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.util.list.PZArrayList;

/**
 * W71 格子載入旗標（2026-10-25；docs/patches.md 2cg）。
 *
 * <p><b>用途</b>：Vanilla Foods Expanded 對每個載入的格子判斷「有沒有地上物品、有沒有容器」，ServerPatch 的 VFE load slim
 * 接手後空格子仍要 4 次 Lua→Java 呼叫（{@code getWorldObjects}→{@code isEmpty}、{@code getObjectContainers}→{@code isEmpty}）。
 * W71 在 {@code IsoGridSquare} 新增 {@code public int mdcLoadFlags()}（方法體只委派 {@link #flags}），Kahlua 自動曝露，
 * Lua 每格只要 1 次呼叫。
 *
 * <p><b>回傳</b>：bit 1＝{@code getWorldObjects()} 非空；bit 2＝{@code getObjects()} 裡至少一個物件
 * {@code getContainerCount() > 0}（VFE 上游自己的條件，含次要容器）；其他 bit 一律 0。
 * kill switch {@code -Dmdc.squareLoadFlags=0|off} 回 -1，Lua 把負數當「沒有」，走原本 4 次呼叫的路。需重啟。
 */
public final class SquareLoadFlags {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.squareLoadFlags"));
    public static final int WORLD_OBJECTS = 1;
    public static final int CONTAINERS = 2;
    private static final String TAG = "[MinidoracatJavaPatch][SquareLoadFlags] ";

    private static boolean announced;

    private SquareLoadFlags() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** {@code IsoGridSquare.mdcLoadFlags()} 的委派目標。 */
    public static int flags(IsoGridSquare square) {
        if (!announced) {
            announced = true;
            log("首次生效 enabled=" + ENABLED + "（-Dmdc.squareLoadFlags=0|off 回 -1，Lua 走原本的呼叫）");
        }
        if (!ENABLED) {
            return -1;
        }
        int flags = square.getWorldObjects().isEmpty() ? 0 : WORLD_OBJECTS;
        PZArrayList<IsoObject> objects = square.getObjects();
        for (int i = 0, n = objects.size(); i < n; i++) {
            if (objects.get(i).getContainerCount() > 0) {
                return flags | CONTAINERS;
            }
        }
        return flags;
    }

    private static void log(String msg) {
        try {
            DebugLog.log(TAG + msg);
        } catch (RuntimeException | LinkageError ignored) {
            // log 失敗不影響回傳值
        }
    }
}
