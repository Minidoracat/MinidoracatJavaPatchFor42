package zombie.mdc;

import java.nio.ByteOrder;

import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;

import zombie.core.logger.ExceptionLogger;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.FishSchoolManager;
import zombie.network.GameServer;
import zombie.network.PacketTypes;

/**
 * W31：兩個 FishSchoolManager 廣播入口共用本批第一份已完成的 body。
 * 不改 GameServer 原方法，停用時可直接委派，也保留每個收件人的鎖、header 與送出順序。
 *
 * <p>只在原版集合／連線型別下共用；自訂回呼可能在收件人之間修改來源。
 * 傳到本層的序列化／送包例外後不再共用；原生 Send 內部吞掉的失敗維持原版行為。
 * order 或容量不同則該封改用原寫入。
 * 快照 OOM 僅放棄額外快取，已完成的當下封包仍照常送出。
 *
 * <p>不改原生 send 拋 Error 後留下 sendLock 的上游行為。
 * 依賴單一主執行緒更新與 final ByteBufferWriter；不保證任意並行修改來源的等價性。
 * {@code -Dmdc.fishingDataBroadcast=0|off} 重啟後回原版，預設啟用。
 */
public final class FishingDataBroadcast {

    private static final String FLAG = System.getProperty("mdc.fishingDataBroadcast");
    private static final boolean ENABLED = !"0".equals(FLAG) && !"off".equals(FLAG);

    /** {@code FishSchoolManager.updateSeed}／{@code updateFishingData} 兩處 callsite 的改道目標。 */
    public static void transmitFishingData(
            int seed, int trashSeed, TLongIntHashMap noiseFishPointDisabler,
            TLongObjectHashMap<FishSchoolManager.ChumData> chumPoints) {
        // 非原版 connections 清單＝迴圈每一步都可能執行任意碼（含原地改動來源 map）；
        // 這種情況不做任何快取判斷，整批交回原版。null 的 NPE 與原版同樣由呼叫端承接。
        if (!ENABLED
                || GameServer.udpEngine.connections.getClass() != java.util.ArrayList.class) {
            GameServer.transmitFishingData(seed, trashSeed, noiseFishPointDisabler, chumPoints);
            return;
        }

        byte[] body = null;
        ByteOrder order = null;
        boolean shareable = noiseFishPointDisabler != null && chumPoints != null
                && noiseFishPointDisabler.getClass() == TLongIntHashMap.class
                && chumPoints.getClass() == TLongObjectHashMap.class;

        for (int n = 0, count; n < (count = GameServer.udpEngine.connections.size()); n++) {
            UdpConnection c = GameServer.udpEngine.connections.get(n);
            if (c != null && c.getClass() != UdpConnection.class) {
                body = null;
                shareable = false;
            }

            try {
                ByteBufferWriter b2 = c.startPacket();
                PacketTypes.PacketType.FishingData.doPacket(b2);
                if (body != null && b2.bb.order() == order && b2.bb.remaining() >= body.length) {
                    b2.bb.put(body);
                } else {
                    int start = b2.bb.position();
                    writeBody(b2, seed, trashSeed, noiseFishPointDisabler, chumPoints);
                    if (shareable && body == null && n + 1 < count) {
                        try {
                            byte[] completed = new byte[b2.bb.position() - start];
                            b2.bb.get(start, completed);
                            order = b2.bb.order();
                            body = completed;
                        } catch (OutOfMemoryError optionalSnapshotFailed) {
                            shareable = false;
                            body = null;
                            System.err.println("[MinidoracatJavaPatch][FishingDataBroadcast] "
                                    + "快照配置失敗；本批改用原版逐鍵寫入");
                        }
                    }
                }
                PacketTypes.PacketType.FishingData.send(c);
            } catch (Throwable t) {
                body = null;
                shareable = false;
                c.cancelPacket();
                ExceptionLogger.logException(t);
            }
        }
    }

    /** 與原版逐位元相同的 body 寫入；供稿路徑與退回路徑共用同一份。 */
    private static void writeBody(
            ByteBufferWriter b2, int seed, int trashSeed, TLongIntHashMap noiseFishPointDisabler,
            TLongObjectHashMap<FishSchoolManager.ChumData> chumPoints) {
        b2.putInt(seed);
        b2.putInt(trashSeed);
        b2.putInt(noiseFishPointDisabler.size());
        noiseFishPointDisabler.forEachKey(l -> {
            b2.putLong(l);
            return true;
        });
        b2.putInt(chumPoints.size());
        chumPoints.forEachEntry((key, chumData) -> {
            b2.putLong(key);
            b2.putInt(chumData.maxForceTime);
            return true;
        });
    }

    private FishingDataBroadcast() {}
}
