package zombie.mdc;

import java.util.ArrayList;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.areas.SafeHouse;
import zombie.network.PacketTypes;
import zombie.network.chat.ChatServer;
import zombie.network.packets.SyncZonePacket;

/**
 * W67 安全屋內的畜牧區只有成員能刪（2026-10-18 使用者裁定；docs/patches.md 2cc）。
 *
 * <p>原版 {@code SyncZonePacket.processServer} 收到刪除就轉發並刪掉整組相連的畜牧區，不看是誰送的。本類由 W66 改道的
 * {@code MdcSyncZoneLog.sendToClients} 在轉發前呼叫：要刪的整組裡只要有一塊和某間安全屋重疊（任何樓層），送封包的連線
 * 就必須是那間的屋主或成員（原版 {@link SafeHouse#playerAllowed(String)}；Safehouse MOD 的成員也在原生名單），
 * 或具 {@code Capability.CanSetupSafehouses}（管理員，與原版安全屋反作弊、Safehouse MOD 同一個判斷）。
 * 不符就拒絕：呼叫端不轉發，並把封包的 {@code zoneAdded} 設成 true，原版接著就不刪；刪除者的客戶端在送出前已先刪掉
 * 整組，{@link #reject} 把伺服器上的整組逐塊補送回去，再用伺服器聊天告訴他原因。判定出錯時照原版刪除。
 * kill switch {@code -Dmdc.zoneDeleteGuard=0|off}。
 */
public final class ZoneDeleteGuard {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.zoneDeleteGuard"));
    static final String MESSAGE = "這塊畜牧區（或和它相連的畜牧區）在別人的安全屋裡，只有那間安全屋的成員能刪除。"
            + " / This livestock zone, or one connected to it, is inside someone else's safehouse. Only its members can remove it.";
    private static final String TAG = "[MinidoracatJavaPatch][ZoneSync] ";

    private static boolean announced;

    private ZoneDeleteGuard() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /**
     * 刪除 {@code zone}（連同相連的整組）是否要拒絕。允許回 null；拒絕回擋下它的安全屋（範圍與屋主），供紀錄用。
     */
    public static String denial(DesignationZone zone, UdpConnection connection) {
        if (!ENABLED || !(zone instanceof DesignationZoneAnimal animalZone) || connection == null) {
            return null;
        }
        try {
            if (!announced) {
                announced = true;
                log("首次生效：安全屋內的畜牧區只有成員能刪（-Dmdc.zoneDeleteGuard=0|off 回原版）");
            }
            Role role = connection.getRole();
            if (role != null && role.hasCapability(Capability.CanSetupSafehouses)) {
                return null;
            }
            ArrayList<String> names = new ArrayList<>();
            names.add(connection.getUserName());
            for (IsoPlayer player : connection.players) {
                if (player != null) {
                    names.add(player.getUsername());
                }
            }
            for (DesignationZoneAnimal member : DesignationZoneAnimal.getAllDZones(null, animalZone, null)) {
                for (SafeHouse house : SafeHouse.getSafehouseList()) {
                    if (overlaps(member, house) && !allowed(house, names)) {
                        return house.getX() + "," + house.getY() + " " + house.getW() + "x" + house.getH()
                                + " owner=" + house.getOwner();
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            log("判定失敗，照原版刪除：" + e);
            return null;
        }
    }

    /** 拒絕之後：把伺服器上的整組逐塊補送給刪除者，再用伺服器聊天告訴他原因。失敗只記一行。 */
    public static void reject(DesignationZone zone, UdpConnection connection) {
        try {
            for (DesignationZoneAnimal member : DesignationZoneAnimal.getAllDZones(null, (DesignationZoneAnimal) zone, null)) {
                SyncZonePacket restore = new SyncZonePacket();
                restore.setData(member, Boolean.TRUE);
                restore.sendToClient(PacketTypes.PacketType.SyncZone, connection);
            }
        } catch (RuntimeException e) {
            log("補送失敗（刪除者重登後會從伺服器重新載入）：" + e);
        }
        try {
            if (ChatServer.isInited()) {
                ChatServer.getInstance().sendMessageToServerChat(connection, MESSAGE);
            }
        } catch (RuntimeException e) {
            log("提示訊息送出失敗：" + e);
        }
    }

    static boolean overlaps(DesignationZone zone, SafeHouse house) {
        return zone.getX() < house.getX2() && zone.getX() + zone.getW() > house.getX()
                && zone.getY() < house.getY2() && zone.getY() + zone.getH() > house.getY();
    }

    private static boolean allowed(SafeHouse house, ArrayList<String> names) {
        for (String name : names) {
            if (name != null && house.playerAllowed(name)) {
                return true;
            }
        }
        return false;
    }

    private static void log(String message) {
        try {
            DebugLog.log(TAG + message);
        } catch (RuntimeException ignored) {
            // 紀錄失敗不得影響封包處理
        }
    }
}
