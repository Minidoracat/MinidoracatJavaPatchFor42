package zombie.mdc;

import java.nio.ByteBuffer;

import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZone;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

/**
 * W66 畜牧區同步修補之二、之三（2026-10-18；docs/patches.md 2cb）。之一（紀錄）在
 * {@code zombie.network.packets.MdcSyncZoneLog}。
 *
 * <p><b>之二：草地再生通知改送 RegisterZone</b>。每條連線兩端各只有一個 {@code SyncZonePacket} 快取實例
 * （{@code PacketsCache}）：伺服器解析這位玩家送來的畜牧區新增／改名／刪除用它，動物吃草
 * （{@code IsoGridSquare.removeGrass}）時用 {@code INetworkPacket.sendToRelative(SyncZone, …, 草地再生區)}
 * 通知附近玩家也用它。{@code setData(Zone)} 只設 x／y／z／type／時間戳，不清 {@code designationZone}，
 * {@code write} 又先看 {@code designationZone}，所以送出的其實是這位玩家上次送來的畜牧區新增／改名／刪除：
 * 他建立或改名過的畜牧區被別人刪掉後，會在他畫面上一直被加回來（伺服器沒有、動物不認，重登才消失）。
 * 沒送過畜牧區的玩家收到真的草地封包時，客戶端快取實例同樣留著上一次收到的畜牧區欄位，
 * {@code processClient} 會重播上一次收到的刪除。改送原版的 {@code RegisterZone}（{@code FishSchoolManager} 經
 * {@code GameServer.sendZone} 已用它更新區域時間戳；客戶端照型別找到同一塊區域更新時間戳，沒有就登記），
 * 兩端就不再出現非畜牧區的 {@code SyncZone}。kill switch {@code -Dmdc.zoneGrassSync=0|off}。
 *
 * <p><b>之三：載入撞號補回</b>。{@code DesignationZone.load} 以建構子建立畜牧區，建構子先抽隨機暫時 id
 * （{@code Rand.Next(9999999)+100000}），撞到已在 {@code allZones} 的 id 就不加入 {@code allZones}，
 * 但 {@code DesignationZoneAnimal} 建構子照樣加進 {@code designationAnimalZoneList}，之後 {@code load} 才設回真 id。
 * 這一塊這一場還會圈住動物，卻不存檔（{@code IsoMetaGrid.savePart} 走 {@code allZones}）、不送給新連線的玩家
 * （{@code MetaDataPacket}），下次重啟就不見了；664 塊時每次開服約 2.2%。開服讀檔與玩家送來的新增／改名兩個呼叫點
 * 改道到 {@link #load}：照原版載入後，不在 {@code allZones} 的補進去。kill switch {@code -Dmdc.zoneLoadRepair=0|off}。
 */
public final class ZoneSyncGuard {
    static final boolean GRASS = parseEnabled(System.getProperty("mdc.zoneGrassSync"));
    static final boolean REPAIR = parseEnabled(System.getProperty("mdc.zoneLoadRepair"));
    private static final String TAG = "[MinidoracatJavaPatch][ZoneSync] ";

    private static boolean grassAnnounced;

    private ZoneSyncGuard() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** 取代 {@code IsoGridSquare.removeGrass} 內兩個 {@code INetworkPacket.sendToRelative}（RegisterZone、SyncZone 各一）。 */
    public static void sendToRelative(PacketTypes.PacketType type, float x, float y, Object[] values) {
        if (GRASS && type == PacketTypes.PacketType.SyncZone) {
            type = PacketTypes.PacketType.RegisterZone;
            if (!grassAnnounced) {
                grassAnnounced = true;
                log("首次生效：草地再生改送 RegisterZone（-Dmdc.zoneGrassSync=0|off 回原版）");
            }
        }
        INetworkPacket.sendToRelative(type, x, y, values);
    }

    /** 取代 {@code IsoMetaGrid.load(ByteBuffer)} 內唯一的 {@code DesignationZone.load}；封包路徑經 {@code MdcSyncZoneLog.load} 進來。 */
    public static DesignationZone load(ByteBuffer input, int worldVersion) {
        DesignationZone zone = DesignationZone.load(input, worldVersion);
        if (REPAIR && zone != null && !DesignationZone.allZones.contains(zone)) {
            DesignationZone.allZones.add(zone);
            log("載入時暫時 id 撞號，補回存檔清單：zone=" + zone.getId().longValue() + " \"" + zone.getName() + "\" "
                    + zone.getX() + "," + zone.getY() + "," + zone.getZ() + " " + zone.getW() + "x" + zone.getH());
        }
        return zone;
    }

    private static void log(String message) {
        try {
            DebugLog.log(TAG + message);
        } catch (RuntimeException ignored) {
            // 紀錄失敗不得中斷載入或送封包
        }
    }
}
