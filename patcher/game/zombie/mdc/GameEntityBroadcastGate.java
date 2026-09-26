package zombie.mdc;

import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityType;
import zombie.entity.network.EntityPacketData;
import zombie.entity.network.EntityPacketType;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

/**
 * W36：GameEntityNetwork.sendPacketData 的 server 廣播分支（{@code isIgnoreConnection=true}）
 * 原版以 {@code INetworkPacket.sendToAll} 不看距離送給全部連線；本 helper 保留原版連線順序、
 * 排除連線與 fully-connected 條件，再以 {@link RecipientWindow}（與 W26 同一份判定）略過遠方連線。
 *
 * <p>只對位置可信的實體過濾：IsoObject（需有所在格）與 VehiclePart（車輛座標）。
 * InventoryItem（原版 getX 在未裝備時回 Float.MAX_VALUE，且 client 端以擁有者背包查找）、
 * MetaEntity 及其他型別一律回原版全服廣播。封包內容、序列化與 {@code INetworkPacket.send}
 * 本身不改；送包例外仍由原版 send 內部處理。
 *
 * <p>{@code -Dmdc.gameEntityRelevancy}：未設定＝1／enforce，2／observe 只記判定、照送，
 * 0／off 回原版；未知值落到 observe。需重啟。
 */
public final class GameEntityBroadcastGate {
    static final int OFF = 0;
    static final int ENFORCE = 1;
    static final int OBSERVE = 2;
    static final int MODE = parseMode();

    private static final String TAG = "[MinidoracatJavaPatch][GameEntityBroadcast] ";
    private static final long BEAT_NS = 300_000_000_000L;
    /** entityNetID(8)＋擁有者 onlineID(2)＋componentID(2)，其後才是 EntityPacketData 內容。 */
    private static final int HEADER_BYTES = 12;

    // 世界更新主緒；與 W26 相同不做原子計數。
    private static long calls, unpositioned, considered, sent, skipped, wouldSkip;
    private static long sentBytes, wouldSkipBytes, craftCalls, craftWouldSkip;
    private static long passthrough, exempt, scopeErrors, logErrors;
    private static long lastBeatNs;
    private static boolean announced;

    private GameEntityBroadcastGate() {}

    /** sendPacketData 內唯一 {@code INetworkPacket.sendToAll} 的同形改道；values＝{data, entity, component}。 */
    public static void sendToAll(PacketTypes.PacketType type, IConnection excluded, Object[] values) {
        if (MODE == OFF || !GameServer.server || GameClient.client) {
            INetworkPacket.sendToAll(type, excluded, values);
            return;
        }
        if (RecipientWindow.disabled()) {
            INetworkPacket.sendToAll(type, excluded, values);
            maybeBeat();
            return;
        }
        float x = Float.NaN;
        float y = Float.NaN;
        try {
            GameEntity entity = (GameEntity) values[1];
            GameEntityType kind = entity.getGameEntityType();
            if (kind == GameEntityType.VehiclePart
                    || kind == GameEntityType.IsoObject && entity.getSquare() != null) {
                x = entity.getX();
                y = entity.getY();
            }
        } catch (RuntimeException failure) {
            scopeErrors++;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            unpositioned++;
            INetworkPacket.sendToAll(type, excluded, values);
            maybeBeat();
            return;
        }
        calls++;
        EntityPacketData data = (EntityPacketData) values[0];
        int bytes = HEADER_BYTES + data.bb.position();
        boolean craft = data.getEntityPacketType() == EntityPacketType.CraftLogicSync;
        if (craft) {
            craftCalls++;
        }
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if ((excluded == null || connection.getConnectedGUID() != excluded.getConnectedGUID())
                    && connection.isFullyConnected()) {
                considered++;
                int verdict;
                try {
                    verdict = RecipientWindow.verdict(connection, x, y);
                } catch (RuntimeException failure) {
                    // 只有新增的範圍判定可 fail-open；送包本身不在 catch 內。
                    scopeErrors++;
                    verdict = RecipientWindow.PASSTHROUGH;
                }
                if (verdict == RecipientWindow.SKIP) {
                    wouldSkip++;
                    wouldSkipBytes += bytes;
                    if (craft) {
                        craftWouldSkip++;
                    }
                    if (MODE == ENFORCE) {
                        skipped++;
                        continue;
                    }
                } else if (verdict == RecipientWindow.PASSTHROUGH) {
                    passthrough++;
                } else if (verdict == RecipientWindow.EXEMPT) {
                    exempt++;
                }
                INetworkPacket.send(connection, type, values);
                sent++;
                sentBytes += bytes;
            }
        }
        maybeBeat();
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        announced = true;
        lastBeatNs = now;
        try {
            DebugLog.log(TAG + "mode=" + MODE + " calls=" + calls + " unpositioned=" + unpositioned
                    + " considered=" + considered + " sent=" + sent + " skipped=" + skipped
                    + " wouldSkip=" + wouldSkip + " sentBytes=" + sentBytes + " wouldSkipBytes=" + wouldSkipBytes
                    + " craftCalls=" + craftCalls + " craftWouldSkip=" + craftWouldSkip
                    + " passthrough=" + passthrough + " exempt=" + exempt
                    + " scopeErrors=" + scopeErrors + " logErrors=" + logErrors
                    + " disabled=" + RecipientWindow.disabled());
        } catch (RuntimeException failure) {
            logErrors++;
        }
    }

    private static int parseMode() {
        String raw = System.getProperty("mdc.gameEntityRelevancy");
        if (raw == null) {
            return ENFORCE;
        }
        return switch (raw.trim()) {
            case "0", "off" -> OFF;
            case "1", "enforce" -> ENFORCE;
            default -> OBSERVE;
        };
    }
}
