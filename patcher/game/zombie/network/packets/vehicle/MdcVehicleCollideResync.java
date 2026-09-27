package zombie.network.packets.vehicle;

import java.util.Locale;

import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.vehicles.BaseVehicle;

/**
 * W46 VehicleCollide 歸還後強制重送授權（2026-09-27；docs/patches.md 2bi）。
 *
 * <p><b>原版缺陷</b>：client 撞到伺服器管的車時先在本機自設 {@code LocalCollide}
 * （{@code BaseVehicle.authorizationClientCollide}，不等伺服器），送 {@code VehicleCollide(true)}；
 * 車 1 秒沒動就每幀送 {@code VehicleCollide(false)}，直到伺服器傳來新授權才停。伺服器授權同步只送
 * 「與上次送給該連線不同」的差異（{@code ServerVehicleState.shouldSend}）。申請與歸還若在伺服器同一幀
 * 處理（卡頓 ≥1 秒），或申請被別人每幀的歸還立刻蓋回 {@code Server}，淨變化為零，伺服器永不糾正，
 * client 每幀送歸還到該車離開載入範圍為止（9/27 抓包：單一 client 237 包/秒、全為 {@code collide=0}）。
 *
 * <p><b>手術</b>：{@code VehicleCollidePacket.processServer} 頭部 headCall 收 (packet, connection)。
 * 歸還包讓「該連線對這台車的授權快取」失效（netPlayerId 設成原版不可能出現的值），下一輪
 * {@code shouldSend} 必帶 8192，把伺服器當下真正的授權送回；client 收到即停止。正常歸還本來就會送，
 * 不多送；只改既有快取，不替沒看過這台車的連線新建（新建會觸發額外的完整同步）。
 *
 * <p>{@code -Dmdc.vehicleCollideResync=0|off} 回原版，其他值（含未設）啟用；需重啟。
 * 在主執行緒（封包處理）呼叫；RuntimeException 只計數，不擋原版處理。
 */
public final class MdcVehicleCollideResync {

    static final boolean ENABLED = enabled(System.getProperty("mdc.vehicleCollideResync"));
    /** 原版 netPlayerId 只會是 -1 或 onlineID（≥0），此值永不相等 → shouldSend 必加授權旗標。 */
    static final short STALE = Short.MIN_VALUE;

    private static final String TAG = "[MinidoracatJavaPatch][VehicleCollideResync] ";
    private static final long BEAT_NS = 300_000_000_000L;

    private static long releases;
    private static long invalidated;
    private static long noState;
    private static long noVehicle;
    private static long anomalies;
    private static long lastBeatNs;
    private static boolean announced;

    static boolean enabled(String raw) {
        if (raw == null) {
            return true;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !v.equals("0") && !v.equals("off");
    }

    /** {@code VehicleCollidePacket.processServer} 頭部 headCall（slots 0, 2）。 */
    public static void onProcessServer(VehicleCollidePacket packet, UdpConnection connection) {
        if (!ENABLED || packet.isCollide) {
            return;
        }
        try {
            releases++;
            BaseVehicle vehicle = packet.vehicleId.getVehicle();
            if (vehicle == null || connection == null) {
                noVehicle++;
            } else {
                BaseVehicle.ServerVehicleState state = connection.vehicleStates.get(vehicle.getId());
                if (state == null) {
                    noState++;
                } else {
                    state.netPlayerId = STALE;
                    invalidated++;
                }
            }
            maybeBeat();
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        String head = announced ? "" : "首次生效（-Dmdc.vehicleCollideResync=0|off 回原版）";
        announced = true;
        DebugLog.log(TAG + head + "releases=" + releases + " invalidated=" + invalidated
                + " noState=" + noState + " noVehicle=" + noVehicle + " anomalies=" + anomalies);
    }

    // ---- 測試存取器 ----

    static long invalidatedForTest() {
        return invalidated;
    }

    private MdcVehicleCollideResync() {}
}
