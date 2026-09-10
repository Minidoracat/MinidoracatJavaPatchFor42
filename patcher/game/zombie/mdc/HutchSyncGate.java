package zombie.mdc;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.Vector3;
import zombie.iso.objects.IsoHutch;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;

/**
 * 僅過濾 IsoHutch.update 的兩個自發 sync；操作、remote relay 與存檔格式保持原版。
 * 接收範圍取整個 chunk-grid 寬度，而非 W13 的共同半寬下界。
 * 送出 teleport 的角色，本次角色生命週期保留全量廣播；目前在載具內或
 * noclip 的角色亦直通。server 沒有 client loaded-set／teleport ACK，不猜轉場時間。
 */
public final class HutchSyncGate {
    static final int OFF = 0;
    static final int ENFORCE = 1;
    static final int OBSERVE = 2;
    static final int MODE = parseMode();

    private static final String TAG = "[MinidoracatJavaPatch][HutchSync] ";
    private static final long BEAT_NS = 300_000_000_000L;
    // 角色物件不被觀測器保留；teleport 的公開入口可能來自非主緒，map 自身負責同步。
    private static final Map<IsoPlayer, Boolean> EXEMPT = MODE == OFF ? null
            : Collections.synchronizedMap(new WeakHashMap<>());

    // syncUpdate 在世界更新主緒；writeTeleportPlayer 不寫這些計數。
    private static long calls, considered, sent, skipped, wouldSkip, sentBytes, wouldSkipBytes;
    private static long passthrough, exempt, scopeErrors, logErrors;
    private static long lastBeatNs;
    private static boolean announced;
    private static volatile boolean disabled;

    private HutchSyncGate() {}

    /** TeleportPacket.write 的 PlayerID.write 改道；先豁免，再原樣寫入玩家 ID。 */
    public static void writeTeleportPlayer(PlayerID id, ByteBufferWriter out) {
        try {
            if (MODE != OFF && GameServer.server && !GameClient.client) {
                IsoPlayer player = id.getPlayer();
                if (player != null) {
                    EXEMPT.put(player, Boolean.TRUE);
                }
            }
        } catch (RuntimeException failure) {
            // 記不住豁免就整把退回原廣播，不可破壞已開始寫入的 teleport。
            disabled = true;
        }
        id.write(out);
    }

    /** IsoHutch.update 內 sync() 的同形改道；不改原有 dirty/size/timer 更新順序。 */
    public static void syncUpdate(IsoHutch hutch) {
        if (MODE == OFF || !GameServer.server || GameClient.client
                || hutch.getClass() != IsoHutch.class) {
            hutch.sync();
            return;
        }
        if (disabled) {
            hutch.sync();
            maybeBeat();
            return;
        }
        IsoGridSquare square = hutch.getSquare();
        if (square == null || hutch.getObjectIndex() == -1) {
            hutch.sync(); // 保留原版 diagnostic，無效物件不標記 hot-save。
            return;
        }
        calls++;
        float x = square.getX();
        float y = square.getY();
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            considered++;
            boolean eligible;
            try {
                eligible = shouldSend(connection, x, y);
            } catch (RuntimeException failure) {
                // 新增的範圍檢查失敗才回原廣播；序列化與送包例外不在 catch 裡。
                scopeErrors++;
                eligible = true;
            }
            if (!eligible) {
                wouldSkip++;
                if (MODE == ENFORCE) {
                    skipped++;
                    continue;
                }
            }
            ByteBufferWriter out = connection.startPacket();
            PacketTypes.PacketType.SyncIsoObject.doPacket(out);
            hutch.syncIsoObjectSend(out);
            int bytes = connection.getBufferPosition();
            PacketTypes.PacketType.SyncIsoObject.send(connection);
            sent++;
            sentBytes += bytes;
            if (!eligible) {
                wouldSkipBytes += bytes; // observe 真正寫出的 bytes，不外推未序列化的資料。
            }
        }
        // 原版即使沒有連線仍會走這裡；不能因所有收件人都被過濾而漏存。
        hutch.flagForHotSave();
        maybeBeat();
    }

    static boolean shouldSend(UdpConnection connection, float x, float y) {
        int width = connection.getChunkGridWidth();
        if (disabled || !connection.isFullyConnected() || width < 13 || width > 19 || (width & 1) == 0
                || connection.players == null || connection.players.length != 4
                || connection.releventPos == null || connection.releventPos.length != 4
                || connection.connectArea == null || connection.connectArea.length != 4) {
            passthrough++;
            return true;
        }
        float radius = width * 8.0f;
        boolean anyPlayer = false;
        boolean actualPositionNear = false;
        boolean uncertain = false;
        boolean hasExempt = false;
        for (int slot = 0; slot < 4; slot++) {
            IsoPlayer player = connection.players[slot];
            Vector3 relevant = connection.releventPos[slot];
            if (connection.connectArea[slot] != null) {
                uncertain = true;
            }
            if (player == null) {
                // 不把尚在換角／加入 split-screen 的 slot 當成無人的安全空間。
                uncertain |= relevant != null;
                continue;
            }
            anyPlayer = true;
            hasExempt |= player.getVehicle() != null || player.isNoClip() || EXEMPT.containsKey(player);
            float px = player.getX();
            float py = player.getY();
            if (player.isDead() || player.getCurrentSquare() == null || relevant == null
                    || !Float.isFinite(px) || !Float.isFinite(py)
                    || !Float.isFinite(relevant.x) || !Float.isFinite(relevant.y)) {
                uncertain = true;
            }
            actualPositionNear |= Math.abs(px - x) <= radius && Math.abs(py - y) <= radius;
        }
        if (hasExempt) {
            exempt++;
            return true;
        }
        if (!anyPlayer || uncertain) {
            passthrough++;
            return true;
        }
        // 原版 relevancy 保留，加上 server 實體位置聯集；不把預測位置當作唯一中心。
        return actualPositionNear || connection.RelevantTo(x, y, radius);
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        announced = true;
        lastBeatNs = now;
        try {
            DebugLog.log(TAG + "mode=" + MODE + " calls=" + calls + " considered=" + considered
                    + " sent=" + sent + " skipped=" + skipped + " wouldSkip=" + wouldSkip
                    + " sentBytes=" + sentBytes + " wouldSkipBytes=" + wouldSkipBytes
                    + " passthrough=" + passthrough + " exempt=" + exempt
                    + " scopeErrors=" + scopeErrors + " logErrors=" + logErrors + " disabled=" + disabled);
        } catch (RuntimeException failure) {
            logErrors++;
        }
    }

    private static int parseMode() {
        String raw = System.getProperty("mdc.hutchSyncGate");
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
