package zombie.mdc;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.Vector3;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.fields.character.PlayerID;

/**
 * 遠距收件人排除規則；W26 雞舍自發 sync 與 W36 GameEntity 廣播共用同一份判定。
 * 收件窗取整個 chunk-grid 寬度 {@code W*8}，而非 W13 的共同半寬下界；server 實體位置與
 * 原版 {@code RelevantTo} 取聯集。送出 teleport 的角色，本次角色生命週期不被排除；
 * 目前在載具內或 noclip 的角色亦不被排除。server 沒有 client loaded-set／teleport ACK，
 * 不猜轉場時間。
 */
public final class RecipientWindow {
    /** 窗內：照送。 */
    static final int SEND = 0;
    /** 可信狀態且所有角色都在窗外：可略過。 */
    static final int SKIP = 1;
    /** 狀態不可信：保守照送。 */
    static final int PASSTHROUGH = 2;
    /** 載具／noclip／teleport 角色：保守照送。 */
    static final int EXEMPT = 3;

    /** 任一使用者啟用時才記 teleport 豁免；兩把都關時 TeleportPacket.write 與原版等價。 */
    static final boolean ACTIVE = HutchSyncGate.MODE != HutchSyncGate.OFF
            || GameEntityBroadcastGate.MODE != GameEntityBroadcastGate.OFF;
    // 角色物件不被判定器保留；teleport 的公開入口可能來自非主緒，map 自身負責同步。
    private static final Map<IsoPlayer, Boolean> EXEMPT_PLAYERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean disabled;

    private RecipientWindow() {}

    /** 豁免簿記故障後，所有使用者回原版廣播。 */
    static boolean disabled() {
        return disabled;
    }

    /** TeleportPacket.write 的 PlayerID.write 改道；先豁免，再原樣寫入玩家 ID。 */
    public static void writeTeleportPlayer(PlayerID id, ByteBufferWriter out) {
        try {
            if (ACTIVE && GameServer.server && !GameClient.client) {
                IsoPlayer player = id.getPlayer();
                if (player != null) {
                    EXEMPT_PLAYERS.put(player, Boolean.TRUE);
                }
            }
        } catch (RuntimeException failure) {
            // 記不住豁免就整把退回原廣播，不可破壞已開始寫入的 teleport。
            disabled = true;
        }
        id.write(out);
    }

    static int verdict(UdpConnection connection, float x, float y) {
        int width = connection.getChunkGridWidth();
        if (disabled || !connection.isFullyConnected() || width < 13 || width > 19 || (width & 1) == 0
                || connection.players == null || connection.players.length != 4
                || connection.releventPos == null || connection.releventPos.length != 4
                || connection.connectArea == null || connection.connectArea.length != 4) {
            return PASSTHROUGH;
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
            hasExempt |= player.getVehicle() != null || player.isNoClip() || EXEMPT_PLAYERS.containsKey(player);
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
            return EXEMPT;
        }
        if (!anyPlayer || uncertain) {
            return PASSTHROUGH;
        }
        // 原版 relevancy 保留，加上 server 實體位置聯集；不把預測位置當作唯一中心。
        return actualPositionNear || connection.RelevantTo(x, y, radius) ? SEND : SKIP;
    }
}
