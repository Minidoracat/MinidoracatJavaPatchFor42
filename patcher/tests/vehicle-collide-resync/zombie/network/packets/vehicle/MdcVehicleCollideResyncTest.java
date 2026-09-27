package zombie.network.packets.vehicle;

import java.lang.reflect.Field;

import gnu.trove.map.hash.TShortObjectHashMap;
import zombie.core.physics.CarController;
import zombie.core.raknet.UdpConnection;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.Authorization;
import zombie.vehicles.BaseVehicle.ServerVehicleState;

/**
 * W46：走 dist 手術後的真 {@code VehicleCollidePacket.processServer}，以真
 * {@code ServerVehicleState.shouldSend} 判定下一輪同步是否帶授權（8192）。
 *
 * <p>用法：{@code MdcVehicleCollideResyncTest on|off}（off 須配 {@code -Dmdc.vehicleCollideResync=0}）。
 * BaseVehicle／UdpConnection 以 {@code Unsafe.allocateInstance} 取得、不跑建構子，只補同步判定用到的欄位。
 */
public final class MdcVehicleCollideResyncTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        // setNetPlayerAuthorization 讀 GameClient.client → ServerOptions 靜態初始化需要已播種的全域 Rand；
        // helper 的 beat 經 DebugLog 格式化鏈，需要 GameServer.server。
        zombie.core.random.RandStandard.INSTANCE.init();
        zombie.network.GameServer.server = true;
        boolean on = args.length == 0 || args[0].equals("on");
        check("啟用狀態與參數一致", MdcVehicleCollideResync.ENABLED == on);

        // 卡住情境：伺服器與連線快取都是 Server(-1)，client 本機自以為 LocalCollide，送來歸還。
        Fixture stuck = new Fixture(Authorization.Server, -1, true);
        stuck.process(false);
        check("伺服器已是 Server 時收到歸還：" + (on ? "下一輪帶授權" : "原版不送"),
                stuck.sendsAuthorization() == on);
        stuck.afterSend();
        check("送出一次後不再重送（非永久旗標）", !stuck.sendsAuthorization());

        // 伺服器因他人駕駛（Local）忽略歸還：啟用時同樣把 Local(5) 送回，client 會改成 Remote。
        Fixture ignored = new Fixture(Authorization.Local, 5, true);
        ignored.process(false);
        check("歸還被忽略（Local）：" + (on ? "把伺服器授權送回" : "原版不送"),
                ignored.sendsAuthorization() == on);
        check("歸還被忽略時車輛授權維持 Local(5)",
                ignored.vehicle.netPlayerAuthorization == Authorization.Local && ignored.vehicle.netPlayerId == 5);

        // 申請包不動快取：伺服器忽略申請（Local）時照原版不送。
        Fixture claim = new Fixture(Authorization.Local, 5, true);
        claim.process(true);
        check("申請包（collide=true）不動連線快取", !claim.sendsAuthorization());

        // 連線從未同步過這台車：不得新建快取（新建會觸發額外的完整同步）。
        Fixture unseen = new Fixture(Authorization.Server, -1, false);
        unseen.process(false);
        check("連線沒有這台車的快取時不新建", unseen.connection.vehicleStates.isEmpty());

        System.out.println(failed == 0 ? "MdcVehicleCollideResyncTest OK (" + args0(args) + ")"
                : "MdcVehicleCollideResyncTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String args0(String[] args) {
        return args.length == 0 ? "on" : args[0];
    }

    private static final class Fixture {
        final BaseVehicle vehicle;
        final UdpConnection connection;
        final ServerVehicleState state = new ServerVehicleState();

        Fixture(Authorization auth, int playerId, boolean synced) throws Exception {
            vehicle = allocate(BaseVehicle.class);
            vehicle.vehicleId = 42;
            vehicle.netPlayerAuthorization = auth;
            vehicle.netPlayerId = (short) playerId;
            Field physics = BaseVehicle.class.getDeclaredField("physics");
            physics.setAccessible(true);
            physics.set(vehicle, allocate(CarController.class));
            connection = allocate(UdpConnection.class);
            Field states = UdpConnection.class.getDeclaredField("vehicleStates");
            states.setAccessible(true);
            states.set(connection, new TShortObjectHashMap<ServerVehicleState>());
            if (synced) {
                state.setAuthorization(vehicle);
                connection.vehicleStates.put(vehicle.vehicleId, state);
            }
        }

        void process(boolean collide) {
            VehicleCollidePacket packet = new VehicleCollidePacket();
            packet.set(vehicle, null, collide);
            packet.processServer(null, connection);
        }

        boolean sendsAuthorization() {
            return state.shouldSend(vehicle) && (state.flags & 8192) != 0;
        }

        void afterSend() {
            state.setAuthorization(vehicle);
            state.flags = 0;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private MdcVehicleCollideResyncTest() {}
}
