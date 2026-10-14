package zombie.mdc;

import java.util.ArrayList;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * W62／W63 車被留在已卸載 chunk 上的兩條原版路徑（client，42.21.0；docs/patches.md 2bz）。
 *
 * <p>W62：chunk 卸載時，車上有本機乘客的話 {@code BaseVehicle.removeFromWorld()} 會提早 return，車仍掛在那塊
 * chunk 上。之後 {@code update()} 開頭看到 {@code this.chunk.vehicles} 有這台車、{@code this.chunk.refs} 為空，
 * 每幀呼叫 {@code removeFromWorld()}（又提早 return）就 return：不送位置、引擎聲停、乘客不擺位。本類別的
 * {@link #contains} 取代 {@code update()} 裡的 {@code ArrayList.contains}；只在這個情況回 false，原版就走
 * 「chunk 物件已回收」那條分支：送 {@code VehicleRequest(2)} 後照常往下跑，等車底下的 chunk 載入後原版自己換過去。
 * 其他呼叫一律回傳 {@code list.contains(o)}。
 *
 * <p>W63：{@code VehiclePacket.doRemove()} 在客戶端沒有伺服器位置的格子、車上有本機玩家時，把玩家移到伺服器的位置。
 * 這台車由本機模擬（Local／LocalCollide）時，伺服器的位置只是本機送出去的舊值；{@link #setPosition} 這時不移動玩家，
 * 其他情況照原版。原版接著照常送 {@code VehicleRequest(2)}。
 *
 * <p>{@code -Dmdc.vehicleOrphanUpdate=off}（W62）、{@code -Dmdc.driverRecallGuard=off}（W63），或 {@code 0}，一律照原版。
 */
public final class VehicleOrphanGuard {

    static final String RAW_UPDATE = System.getProperty("mdc.vehicleOrphanUpdate");
    static final boolean UPDATE_ENABLED = !VehicleClampGuard.isOff(RAW_UPDATE);
    static final String RAW_RECALL = System.getProperty("mdc.driverRecallGuard");
    static final boolean RECALL_ENABLED = !VehicleClampGuard.isOff(RAW_RECALL);
    /** 每次啟動最多逐筆記錄的事件數，之後只計數。 */
    private static final int LOG_LIMIT = 20;

    /** W62 介入的段數：同一台車掛在同一塊卸載 chunk 上算一段（只在主執行緒更新）。 */
    static int orphanEpisodes;
    /** W63 沒有移動駕駛的次數。 */
    static int recallsHeld;
    private static BaseVehicle lastVehicle;
    private static IsoChunk lastChunk;

    static {
        log("active orphanUpdate=" + UPDATE_ENABLED + " driverRecall=" + RECALL_ENABLED
                + (RAW_UPDATE == null ? "" : " (-Dmdc.vehicleOrphanUpdate=" + RAW_UPDATE + ")")
                + (RAW_RECALL == null ? "" : " (-Dmdc.driverRecallGuard=" + RAW_RECALL + ")"));
    }

    private VehicleOrphanGuard() {}

    /** 取代 BaseVehicle.update 裡的 {@code ArrayList.contains(Object)}。 */
    public static boolean contains(ArrayList<?> list, Object o) {
        boolean listed = list.contains(o);
        if (listed && UPDATE_ENABLED && o instanceof BaseVehicle vehicle && orphaned(list, vehicle)) {
            if (vehicle != lastVehicle || vehicle.chunk != lastChunk) {
                lastVehicle = vehicle;
                lastChunk = vehicle.chunk;
                orphanEpisodes++;
                if (orphanEpisodes <= LOG_LIMIT) {
                    log("vehicle " + vehicle.vehicleId + " " + vehicle.getScriptName() + " at " + vehicle.getX() + ","
                            + vehicle.getY() + ": its chunk " + lastChunk.wx + "," + lastChunk.wy
                            + " was unloaded with a local passenger aboard; vanilla would skip every update, kept updating (#"
                            + orphanEpisodes + (orphanEpisodes == LOG_LIMIT ? ", further events are not logged" : "") + ")");
                }
            }
            return false;
        }
        return listed;
    }

    /** 就是 update() 開頭那個判斷：車自己的 chunk 清單、那塊已卸載（refs 空）、車上有本機乘客。 */
    static boolean orphaned(ArrayList<?> list, BaseVehicle vehicle) {
        IsoChunk chunk = vehicle.chunk;
        return chunk != null && list == chunk.vehicles && !GameServer.server && chunk.refs.isEmpty()
                && hasLocalPassenger(vehicle);
    }

    /** 與原版 BaseVehicle.removeFromWorld 的提早 return 條件相同：任一座位上是 IsoPlayer.players 裡的人。 */
    static boolean hasLocalPassenger(BaseVehicle vehicle) {
        for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
            IsoGameCharacter character = vehicle.getCharacter(seat);
            if (character == null) {
                continue;
            }
            for (int k = 0; k < IsoPlayer.players.length; k++) {
                if (character == IsoPlayer.players[k]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 取代 VehiclePacket.doRemove 裡的 {@code player.setPosition(伺服器 x, y, 0)}。 */
    public static void setPosition(IsoPlayer player, float x, float y, float z) {
        BaseVehicle vehicle = RECALL_ENABLED ? player.getVehicle() : null;
        if (vehicle != null && vehicle.isLocalPhysicSim()) {
            recallsHeld++;
            if (recallsHeld <= LOG_LIMIT) {
                log("vehicle " + vehicle.vehicleId + " " + vehicle.getScriptName() + " at " + vehicle.getX() + ","
                        + vehicle.getY() + ": server position " + x + "," + y + " is not loaded here and this client"
                        + " simulates the vehicle; vanilla would move the driver there, kept in the vehicle (#" + recallsHeld
                        + (recallsHeld == LOG_LIMIT ? ", further events are not logged" : "") + ")");
            }
            return;
        }
        player.setPosition(x, y, z);
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][VehicleOrphanGuard] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
