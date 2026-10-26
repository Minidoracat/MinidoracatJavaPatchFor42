package zombie.mdc;

import zombie.characters.IsoPlayer;
import zombie.core.MdcTimedActionProbe;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;

/**
 * W79 {@code UpdateItemSprite} 距離檢查（docs/patches.md 2cn）。
 *
 * <p>伺服器端 {@code UpdateItemSprite} 請求驗證。合法來源是客戶端 {@code IsoObject.transmitUpdatedSprite()}：
 * 搬運動作的 scrap／repair（玩家身旁）與駕駛撞圍籬（{@code BentFences}／{@code BrokenFences}，車旁沿圍籬幾格）。
 * 頭部 {@link #begin} 綁定連線，方法內唯一的 {@code IsoCell.getGridSquare(III)} 改道 {@link #getGridSquare}：
 * 這條連線上沒有任何玩家在水平每軸 ≤ {@link #MAX_RANGE}、樓層差 ≤ {@link #MAX_DZ} 內時回 null，
 * 原版 {@code sq == null} 分支什麼都不做（不改、不轉發）。
 *
 * <p>開關：{@code -Dmdc.spriteUpdateRange=0|off} 回原版，預設開，需重啟。
 */
public final class SpriteUpdateGuard {
    private static final String TAG = "[MinidoracatJavaPatch][SpriteUpdateGuard]";

    static final boolean ENABLED = flag("mdc.spriteUpdateRange");
    /** 撞圍籬時駕駛離撞擊點數格、smashFence 再沿圍籬改幾格、長拖車與伺服器位置落後的餘量。 */
    static final float MAX_RANGE = 20.0F;
    static final float MAX_DZ = 1.5F;

    private static final ThreadLocal<UdpConnection> CONNECTION = new ThreadLocal<>();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;

    // 封包處理在主迴圈單執行緒；計數與時間窗容忍罕見交錯。
    private static long refused;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;

    private SpriteUpdateGuard() {
    }

    /** {@code receiveUpdateItemSprite} 頭部：綁定送封包的連線。 */
    public static void begin(UdpConnection connection) {
        CONNECTION.set(connection);
    }

    /** {@code receiveUpdateItemSprite} 內唯一 {@code IsoCell.getGridSquare(III)} 的改道；連線上下文取用即清。 */
    public static IsoGridSquare getGridSquare(IsoCell cell, int x, int y, int z) {
        UdpConnection connection = CONNECTION.get();
        CONNECTION.remove();
        if (ENABLED && connection != null) {
            boolean near;
            try {
                near = nearAnyPlayer(connection, x, y, z);
            } catch (RuntimeException e) {
                anomalies++;
                near = true;
            }
            if (!near) {
                refused++;
                report(connection, x, y, z);
                return null;
            }
        }
        return cell.getGridSquare(x, y, z);
    }

    /** 封包不帶玩家：同機多人時任一位在範圍內即放行。 */
    static boolean nearAnyPlayer(UdpConnection connection, int x, int y, int z) {
        if (connection.players == null) {
            return false;
        }
        for (IsoPlayer p : connection.players) {
            if (p != null && Math.abs(p.getX() - x) <= MAX_RANGE && Math.abs(p.getY() - y) <= MAX_RANGE
                    && Math.abs(p.getZ() - z) <= MAX_DZ) {
                return true;
            }
        }
        return false;
    }

    private static void report(UdpConnection connection, int x, int y, int z) {
        try {
            if (!allowLine()) {
                return;
            }
            DebugLog.log(TAG + " refused target=" + x + "," + y + "," + z
                    + " connectionPlayers=" + MdcTimedActionProbe.connectionPlayers(connection)
                    + " at=" + positions(connection)
                    + " maxRange=" + MAX_RANGE + " refused=" + refused
                    + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static String positions(UdpConnection connection) {
        if (connection.players == null) {
            return "?";
        }
        StringBuilder s = new StringBuilder();
        for (IsoPlayer p : connection.players) {
            if (p == null) {
                continue;
            }
            if (s.length() > 0) {
                s.append('|');
            }
            s.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        return s.length() == 0 ? "?" : s.toString();
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

    private static boolean flag(String name) {
        String raw = System.getProperty(name);
        if (raw == null) {
            return true;
        }
        String v = raw.trim();
        return !("0".equals(v) || "off".equalsIgnoreCase(v));
    }

    // ---- 測試存取器 ----

    static long refusedForTest() {
        return refused;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static boolean boundForTest() {
        return CONNECTION.get() != null;
    }
}
