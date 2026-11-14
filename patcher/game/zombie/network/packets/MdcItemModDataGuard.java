package zombie.network.packets;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.core.MdcTimedActionProbe;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.network.fields.ContainerID;

/**
 * W102 物品資料同步的伺服器端驗證（docs/patches.md 2dj）。
 *
 * <p>物品 modData 裡有些鍵只該由伺服器寫（{@link #KEYS}）。{@code SyncItemFieldsPacket.processServer} 頭部 {@link #begin}
 * 把封包那份表裡的這些鍵改成伺服器那件物品目前的值（伺服器沒有就拿掉），之後原版的套用與轉發照跑——伺服器那份的保留鍵
 * 不變，轉發出去的也是伺服器的值；其他鍵與欄位照原版。
 *
 * <p>開關：{@code -Dmdc.itemModDataGuard=0|off} 回原版，預設開；保留鍵 {@code -Dmdc.itemModDataKeys}（逗號分隔，預設
 * {@code MinidoracatEconomy}）。都要重啟。
 */
public final class MdcItemModDataGuard {
    private static final String TAG = "[MinidoracatJavaPatch][ItemModDataGuard]";

    static final String[] KEYS = keys();
    /** 比較兩份值是否相同時的巢狀上限；超過就當成不同（只影響記不記 log，不影響保留）。 */
    private static final int MAX_DEPTH = 16;

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;

    /** {@code containerId} 是 private；找不到時 {@link #begin} 走 fail-closed。 */
    private static final Field CONTAINER_ID = containerIdField();

    // 封包處理在主迴圈單執行緒；計數與時間窗容忍罕見交錯。
    private static boolean announced;
    private static long refused;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;

    private MdcItemModDataGuard() {
    }

    /** {@code processServer} 頭部：把封包 modData 裡的保留鍵換成伺服器現值。不得把例外丟給原版。 */
    public static void begin(SyncItemFieldsPacket packet, UdpConnection connection) {
        announce();
        if (KEYS.length == 0 || packet.moddata == null) {
            return;   // 封包沒帶 modData 時原版不動伺服器那份
        }
        try {
            InventoryItem item = ((ContainerID) CONTAINER_ID.get(packet)).getContainer().getItemWithID(packet.itemId);
            KahluaTable server = item.hasModData() ? item.getModData() : null;
            for (String key : KEYS) {
                Object kept = server == null ? null : server.rawget(key);
                Object sent = packet.moddata.rawget(key);
                if (kept == null && sent == null) {
                    continue;
                }
                boolean differs = !same(kept, sent, 0);
                packet.moddata.rawset(key, kept);
                if (differs) {
                    refused++;
                    report(connection, item, key);
                }
            }
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            anomalies++;
            packet.moddata = null;   // 判不出來就整份不套用：伺服器那份不動，代價只是這包的 modData 沒生效
        }
    }

    /** 兩份 Lua 值內容相同（表逐鍵遞迴比）。 */
    static boolean same(Object a, Object b, int depth) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (!(a instanceof KahluaTable x) || !(b instanceof KahluaTable y)) {
            return a.equals(b);
        }
        if (depth >= MAX_DEPTH || x.size() != y.size()) {
            return false;
        }
        KahluaTableIterator it = x.iterator();
        while (it.advance()) {
            if (!same(it.getValue(), y.rawget(it.getKey()), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        try {
            DebugLog.log(TAG + " 首次生效 mode=" + (KEYS.length == 0 ? "off" : "on") + " keys=" + String.join(",", KEYS)
                    + " reflect=" + (CONTAINER_ID != null ? "ok" : "missing") + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void report(UdpConnection connection, InventoryItem item, String key) {
        try {
            if (!allowLine()) {
                return;
            }
            DebugLog.log(TAG + " kept key=" + key + " item=" + item.getFullType() + "#" + item.getID()
                    + " connectionPlayers=" + (connection != null ? MdcTimedActionProbe.connectionPlayers(connection) : "?")
                    + " refused=" + refused + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException | LinkageError e) {
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

    private static Field containerIdField() {
        try {
            Field f = SyncItemFieldsPacket.class.getDeclaredField("containerId");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String[] keys() {
        String mode = System.getProperty("mdc.itemModDataGuard", "on").trim();
        if ("0".equals(mode) || "off".equalsIgnoreCase(mode)) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        for (String k : System.getProperty("mdc.itemModDataKeys", "MinidoracatEconomy").split(",")) {
            if (!k.trim().isEmpty()) {
                out.add(k.trim());
            }
        }
        return out.toArray(new String[0]);
    }

    // ---- 測試存取器 ----

    static long refusedForTest() {
        return refused;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
