package zombie.network.packets;

import java.lang.reflect.Field;
import java.util.ArrayList;

import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.WornItems;
import zombie.core.MdcTimedActionProbe;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.inventory.ItemContainer;
import zombie.network.fields.character.Player;

/**
 * W103 穿戴同步的伺服器端驗證（docs/patches.md 2dk）。
 *
 * <p>{@code SyncClothingPacket.processServer} 頭部 {@link #begin} 逐項看封包列的穿戴物：伺服器那位玩家的背包裡沒有這個 ID
 * 的那幾項（下稱無對應項）各記一行；{@code enforce} 另把它們從封包的清單拿掉，原版就不會處理、也不會轉發。只看背包、
 * 不看穿戴欄：原版處理一包的途中會改穿戴欄，背包不會變，事先查到的結果和原版輪到那一項時查到的一樣。
 *
 * <p>開關：{@code -Dmdc.wornItemGuard=observe|enforce|off}，預設 observe（只記），要重啟。整包判斷完才動清單；判斷途中出
 * 例外時清單原封不動、這一包照原版處理（兩種模式都是），記 {@code anomaly} 行。
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
            ItemContainer bag = player.getInventory();
            WornItems wornItems = player.getWornItems();
            ArrayList<SyncClothingPacket.ItemDescription> found = null;
            for (SyncClothingPacket.ItemDescription d : items) {
                if (bag.getItemWithID(d.itemId) == null) {
                    if (found == null) {
                        found = new ArrayList<>();
                    }
                    found.add(d);
                }
            }
            if (found == null) {
                return;
            }
            boolean[] worn = new boolean[found.size()];
            for (int i = 0; i < worn.length; i++) {
                worn[i] = wornItems.getItemById(found.get(i).itemId) != null;
            }
            // 判斷到這裡都沒出例外才動清單與計數
            if (MODE == ENFORCE) {
                items.removeAll(found);
            }
            for (int i = 0; i < worn.length; i++) {
                unbacked++;
                report(connection, found.get(i), worn[i]);
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

    private static void report(UdpConnection connection, SyncClothingPacket.ItemDescription d, boolean worn) {
        try {
            if (!allowLine()) {
                return;
            }
            DebugLog.log(TAG + " unbacked type=" + clean(d.itemType) + " id=" + d.itemId + " loc=" + clean(String.valueOf(d.location))
                    + " worn=" + (worn ? 1 : 0) + " action=" + (MODE == ENFORCE ? "removed" : "logged")
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
