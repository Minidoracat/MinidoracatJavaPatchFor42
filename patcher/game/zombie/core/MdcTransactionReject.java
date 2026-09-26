package zombie.core;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.ContainerID;
import zombie.network.packets.ItemTransactionPacket;

/**
 * W44 物品搬移失敗即時回報（2026-09-27；docs/patches.md 2bg）。
 *
 * <p><b>vanilla 缺陷</b>：{@code TransactionManager.update()} 在 Accept 到期後執行
 * {@code transaction.update()}；成功時 {@code setState(Done)} 並把封包送回 client，失敗
 * （回 false 或拋例外）卻只 {@code setState(Reject)}、<b>不送任何封包</b>。client 端的交易停在
 * Accept，要等 {@code 時長+10 秒} 逾時被移除，空清單上 {@code isDone} 成立 ⇒
 * {@code ISInventoryTransferAction} 走 {@code forceComplete}：讀條走滿、空等約 10 秒、東西沒拿到。
 * 撿幽靈物品（W43 的症狀）每件都卡一輪。
 *
 * <p><b>手術</b>：{@code update()} 內三個 {@code Transaction.setState} 1:1 改道 {@link #setState}；
 * 先照原樣設狀態，只有 Reject 且為 {@code ItemTransactionPacket} 時，比照原版 Done 分支以
 * <b>同一物件</b>送給該玩家的連線（Reject 狀態的 write 只帶 id＋state）。client 收到後
 * {@code isRejected} 成立 ⇒ {@code forceStop}，立刻中斷。
 *
 * <p><b>刻意不補送</b>：任一 entry 來源是 {@code Floor}。原版 {@code updateItem} 在
 * 「地面→地面」時先搬完物品才以距離 &gt;1.1 回 false（反編譯 :302-341），此時物品其實已移動；
 * 維持原版行為，不讓 Reject 打斷後續排隊的搬移。
 *
 * <p>例外紀律：送包失敗只計 anomalies（狀態已設好，等同原版）；{@code Error} 外逃。
 * kill switch：{@code -Dmdc.transactionReject=0|off}（純委派 setState）。
 */
public final class MdcTransactionReject {

    private static final String TAG = "[MinidoracatJavaPatch][TransactionReject] ";
    private static final long LOG_INTERVAL_NS = 300_000_000_000L;
    private static final int MAX_ANOMALY_LOGS = 3;

    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.transactionReject"));

    private static long rejects;
    private static long sent;
    private static long skippedFloor;
    private static long skippedNoConn;
    private static long anomalies;
    private static long lastLogNs;
    private static boolean bannerShown;

    private MdcTransactionReject() {}

    /** {@code TransactionManager.update()} 內 {@code Transaction.setState} 的改道目標。 */
    public static void setState(Transaction transaction, Transaction.TransactionState state) {
        transaction.setState(state);
        if (!ENABLED || state != Transaction.TransactionState.Reject
                || !(transaction instanceof ItemTransactionPacket packet)) {
            return;
        }
        rejects++;
        try {
            if (hasFloorSource(packet)) {
                skippedFloor++;
            } else {
                IsoPlayer player = packet.playerId.getPlayer();
                UdpConnection connection = player == null ? null : GameServer.getConnectionFromPlayer(player);
                if (connection == null || !connection.isFullyConnected()) {
                    skippedNoConn++;
                } else {
                    ByteBufferWriter bbw = connection.startPacket();
                    PacketTypes.PacketType.ItemTransaction.doPacket(bbw);
                    packet.write(bbw);
                    PacketTypes.PacketType.ItemTransaction.send(connection);
                    sent++;
                }
            }
        } catch (RuntimeException e) {
            anomalies++;
            if (anomalies <= MAX_ANOMALY_LOGS) {
                DebugLog.log(TAG + "anomaly#" + anomalies + " " + e);
            }
        }
        long now = System.nanoTime();
        if (!bannerShown) {
            bannerShown = true;
            lastLogNs = now;
            DebugLog.log(TAG + "首次生效（-Dmdc.transactionReject=0|off 回原版）");
        } else if (now - lastLogNs >= LOG_INTERVAL_NS) {
            lastLogNs = now;
            DebugLog.log(TAG + "beat rejects=" + rejects + " sent=" + sent + " skippedFloor=" + skippedFloor
                    + " skippedNoConn=" + skippedNoConn + " anomalies=" + anomalies + ".");
        }
    }

    static boolean hasFloorSource(Transaction transaction) {
        for (Transaction.TransactionEntry entry : transaction.entries) {
            if (entry.sourceId.containerType == ContainerID.ContainerType.Floor) {
                return true;
            }
        }
        return false;
    }

    static boolean parseEnabled(String v) {
        return v == null || !("0".equals(v.trim()) || "off".equalsIgnoreCase(v.trim()));
    }
}
