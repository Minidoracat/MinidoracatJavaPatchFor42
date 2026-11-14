package zombie.network.packets;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;

import zombie.characters.IsoPlayer;
import zombie.core.MdcTimedActionProbe;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.inventory.types.Clothing;
import zombie.network.fields.character.Player;

/**
 * W103 穿戴同步的伺服器端驗證（docs/patches.md 2dk）。
 *
 * <p>{@code SyncClothingPacket.processServer} 頭部 {@link #begin} 逐項看封包列的穿戴物：伺服器的穿戴欄與背包裡都沒有這個
 * ID 的那幾項（下稱無對應項）記一行；{@code enforce} 另把它們從封包的清單拿掉，原版就不會處理、也不會轉發。判斷與原版
 * {@code process()} 走到建立物品那一步的條件相同。
 *
 * <p>開關：{@code -Dmdc.wornItemGuard=observe|enforce|off}，預設 observe（只記），要重啟。判斷途中出例外時這一包照原版
 * 處理（兩種模式都是），記 {@code anomaly} 行。
 */
public final class MdcWornItemGuard {
    private static final String TAG = "[MinidoracatJavaPatch][WornItemGuard]";

    static final int OFF = 0;
    static final int OBSERVE = 1;
    static final int ENFORCE = 2;
    static final int MODE = mode();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    private static final int TEXT_CAP = 64;

    /** {@code player}、{@code items} 是 private final；任一個找不到就整刀不動（首次生效行寫 reflect=missing）。 */
    private static final Field PLAYER = field("player");
    private static final Field ITEMS = field("items");

    // 封包處理在主迴圈單執行緒；計數與時間窗容忍罕見交錯。
    private static boolean announced;
    private static long unbacked;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;

    private MdcWornItemGuard() {
    }

    /** {@code processServer} 頭部。不得把例外丟給原版。 */
    public static void begin(SyncClothingPacket packet, UdpConnection connection) {
        announce();
        if (MODE == OFF || PLAYER == null || ITEMS == null) {
            return;
        }
        try {
            IsoPlayer player = ((Player) PLAYER.get(packet)).getPlayer();
            @SuppressWarnings("unchecked")
            ArrayList<SyncClothingPacket.ItemDescription> items = (ArrayList<SyncClothingPacket.ItemDescription>) ITEMS.get(packet);
            for (Iterator<SyncClothingPacket.ItemDescription> it = items.iterator(); it.hasNext();) {
                SyncClothingPacket.ItemDescription d = it.next();
                // 與原版 process() 相同：已經穿在同一個位置的衣物不動；否則從背包找，背包也沒有才會去建
                boolean worn = player.getWornItems().getItemById(d.itemId) instanceof Clothing c && d.location.equals(c.getBodyLocation());
                if (worn || player.getInventory().getItemWithID(d.itemId) != null) {
                    continue;
                }
                unbacked++;
                if (MODE == ENFORCE) {
                    it.remove();
                }
                report(connection, d);
            }
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            anomalies++;
            reportAnomaly(e);
        }
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        try {
            DebugLog.log(TAG + " 首次生效 mode=" + (MODE == ENFORCE ? "enforce" : MODE == OBSERVE ? "observe" : "off")
                    + " reflect=" + (PLAYER != null && ITEMS != null ? "ok" : "missing") + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void report(UdpConnection connection, SyncClothingPacket.ItemDescription d) {
        try {
            if (!allowLine()) {
                return;
            }
            DebugLog.log(TAG + " unbacked type=" + clean(d.itemType) + " id=" + d.itemId + " loc=" + clean(String.valueOf(d.location))
                    + " action=" + (MODE == ENFORCE ? "removed" : "logged")
                    + " connectionPlayers=" + (connection != null ? MdcTimedActionProbe.connectionPlayers(connection) : "?")
                    + " unbacked=" + unbacked + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void reportAnomaly(Throwable cause) {
        try {
            if (allowLine()) {
                DebugLog.log(TAG + " anomaly reason=" + cause.getClass().getSimpleName()
                        + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 物品種類是客戶端給的字串：進 log 前截短、只留不會斷行或混淆欄位的字元。 */
    static String clean(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), TEXT_CAP));
        for (int i = 0; i < text.length() && i < TEXT_CAP; i++) {
            char ch = text.charAt(i);
            out.append(ch < 128 && (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-' || ch == ':') ? ch : '?');
        }
        return out.toString();
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

    private static Field field(String name) {
        try {
            Field f = SyncClothingPacket.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static int mode() {
        String mode = System.getProperty("mdc.wornItemGuard", "observe").trim();
        if ("0".equals(mode) || "off".equalsIgnoreCase(mode)) {
            return OFF;
        }
        return "enforce".equalsIgnoreCase(mode) ? ENFORCE : OBSERVE;
    }

    // ---- 測試存取器 ----

    static long unbackedForTest() {
        return unbacked;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
