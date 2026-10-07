package zombie.mdc;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import gnu.trove.map.hash.TShortObjectHashMap;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.RandStandard;
import zombie.iso.IsoMovingObject;
import zombie.iso.Vector3;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.vehicle.VehicleRemovePacket;
import zombie.network.packets.vehicle.VehicleRequestPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;

/**
 * W53 步行時縮小車輛相關範圍的行為驗證（docs/patches.md 2bq）。
 *
 * <p>argv：{@code observe}（預設出貨）／{@code on}／{@code off}，第二個參數是預期半徑（預設 64）。測試自驗
 * MODE 與 RADIUS 和 argv 相符——property 名稱拼錯會炸在這裡，不會把同一組態跑幾遍假綠。
 *
 * <p>兩層：
 * <ol>
 *   <li>helper 直呼（同 AnimalRelevancyGateTest）：真 {@code UdpConnection.isRelevantTo} 參與判定，
 *       原版結果直接呼叫它做負對照。玩家在 (1000,1000)、{@code relevantRange=11}（1080p，±88 正方形）。</li>
 *   <li>dist 內手術後的真 {@code VehicleRequestPacket.processServer}：真 wire 解析的 Passengers 請求，
 *       連線只覆寫 buffer 端點與 {@code getPacket}，送出的 {@code VehicleRemove} 由真 packet／PacketType 寫出再解碼。</li>
 * </ol>
 * 連線、玩家、車都以 {@code Unsafe.allocateInstance} 取得、不跑建構子（理由同 AnimalRelevancyGateTest），
 * 欄位由測試補齊。
 */
public final class VehicleRelevancyGateTest {

    private static final float PX = 1000.0f;
    private static final float PY = 1000.0f;
    /** 1080p：chunkGridWidth 19 ⇒ relevantRange 19/2+2 = 11 ⇒ ±88。 */
    private static final byte RELEVANT_RANGE = 11;
    private static final float VANILLA_HALF = RELEVANT_RANGE * 8;

    private static int failed;
    private static int mode;
    private static float radius;

    public static void main(String[] args) throws Exception {
        RandStandard.INSTANCE.init();
        GameServer.server = true;

        String want = args.length > 0 ? args[0] : "observe";
        int wantMode = switch (want) {
            case "off" -> VehicleRelevancyGate.OFF;
            case "on" -> VehicleRelevancyGate.ON;
            default -> VehicleRelevancyGate.OBSERVE;
        };
        float wantRadius = args.length > 1 ? Float.parseFloat(args[1]) : VehicleRelevancyGate.DEFAULT_RADIUS;
        mode = VehicleRelevancyGate.MODE;
        radius = VehicleRelevancyGate.RADIUS;
        expect("自驗：argv=" + want + " 與 MODE 相符（mode=" + VehicleRelevancyGate.modeName() + "）", mode == wantMode);
        expect("自驗：RADIUS=" + radius + " 與預期 " + wantRadius + " 相符", radius == wantRadius);
        expect("幾何前提：R < 原版半寬 " + VANILLA_HALF + "（否則本刀無環帶可縮）", radius < VANILLA_HALF);

        testGeometry();
        testCounters();
        testPassthrough();
        testSplitScreen();
        testParseMode();
        testRealProcessServer();

        expect("anomalies 恆為 0", VehicleRelevancyGate.anomalyCount() == 0);
        System.out.println(failed == 0 ? "VehicleRelevancyGateTest OK (" + want + ", R=" + radius + ")"
                : "VehicleRelevancyGateTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 只縮不放：R 內一律留、原版外一律不留、環帶依模式。兩個入口結果相同。 */
    private static void testGeometry() throws Exception {
        UdpConnection c = conn();
        boolean shrink = mode == VehicleRelevancyGate.ON;

        // 原版負對照：環帶與正方形角落在原版都算相關，原版外不相關
        expect("原版：環帶 dist=R+1 相關", c.isRelevantTo(PX + radius + 1.0f, PY));
        expect("原版：正方形角落 (+80,+80)（平面距離 113）相關", c.isRelevantTo(PX + 80.0f, PY + 80.0f));
        expect("原版：dist=89 不相關", !c.isRelevantTo(PX + VANILLA_HALF + 1.0f, PY));

        for (boolean send : new boolean[] {true, false}) {
            String site = send ? "sendRelevant" : "keepRelevant";
            expect(site + "：R 內（dist=R-1）一律相關", gate(send, c, PX + radius - 1.0f, PY));
            expect(site + "：dist == R 仍相關（含等於）", gate(send, c, PX, PY - radius));
            // 原點避免世界座標的 ULP 吞掉半徑邊界的最小正 float
            UdpConnection origin = conn(0.0f, 0.0f);
            expect(site + "：原點 Math.nextUp(R) 依模式分流（on 不相關）",
                    gate(send, origin, Math.nextUp(radius), 0.0f) == !shrink);
            expect(site + "：環帶 dist=R+1 依模式分流", gate(send, c, PX + radius + 1.0f, PY) == !shrink);
            float diag = (float) Math.ceil(radius / Math.sqrt(2.0)) + 1.0f;
            expect(site + "：對角 (+" + diag + ",+" + diag + ") 在圓外、正方形內，依模式分流（圓形距離）",
                    gate(send, c, PX + diag, PY - diag) == !shrink);
            expect(site + "：原版外 dist=89 一律不相關", !gate(send, c, PX - VANILLA_HALF - 1.0f, PY));
            expect(site + "：正方形角落 (+80,+80) 依模式分流", gate(send, c, PX + 80.0f, PY + 80.0f) == !shrink);
        }
    }

    /** 計數：環帶各入口只動自己的計數；原版外與 R 內不計；off 全不動。 */
    private static void testCounters() throws Exception {
        UdpConnection c = conn();
        long send0 = VehicleRelevancyGate.sendOutsideCount();
        long keep0 = VehicleRelevancyGate.keepOutsideCount();
        VehicleRelevancyGate.sendRelevant(c, PX + radius + 1.0f, PY);
        long send1 = VehicleRelevancyGate.sendOutsideCount();
        long keep1 = VehicleRelevancyGate.keepOutsideCount();
        VehicleRelevancyGate.keepRelevant(c, PX + radius + 1.0f, PY);
        long send2 = VehicleRelevancyGate.sendOutsideCount();
        long keep2 = VehicleRelevancyGate.keepOutsideCount();
        VehicleRelevancyGate.sendRelevant(c, PX + VANILLA_HALF + 1.0f, PY);
        VehicleRelevancyGate.keepRelevant(c, PX + 1.0f, PY);
        boolean counts = mode != VehicleRelevancyGate.OFF;
        expect("sendRelevant 環帶：sendOutside " + (counts ? "+1" : "不動") + "、keepOutside 不動",
                send1 == send0 + (counts ? 1 : 0) && keep1 == keep0);
        expect("keepRelevant 環帶：keepOutside " + (counts ? "+1" : "不動") + "、sendOutside 不動",
                keep2 == keep1 + (counts ? 1 : 0) && send2 == send1);
        expect("原版外與 R 內不計數",
                VehicleRelevancyGate.sendOutsideCount() == send2 && VehicleRelevancyGate.keepOutsideCount() == keep2);
    }

    /** 車內（駕駛或乘客、任一本地玩家）與 connectArea 非 null：照原版。 */
    private static void testPassthrough() throws Exception {
        boolean counts = mode != VehicleRelevancyGate.OFF;
        float ring = PX + radius + 1.0f;

        UdpConnection walking = conn();
        walking.players[0] = player(false);
        expect("步行玩家：環帶依模式分流", VehicleRelevancyGate.sendRelevant(walking, ring, PY)
                == (mode != VehicleRelevancyGate.ON));

        UdpConnection driving = conn();
        driving.players[0] = player(true);
        long pass0 = VehicleRelevancyGate.passVehicleCount();
        long out0 = VehicleRelevancyGate.sendOutsideCount();
        expect("車內玩家：環帶照原版（三模式都相關）", VehicleRelevancyGate.sendRelevant(driving, ring, PY));
        expect("車內玩家：keepRelevant 環帶照原版", VehicleRelevancyGate.keepRelevant(driving, ring, PY));
        expect("車內玩家：passVehicle " + (counts ? "+2" : "不動") + "、不計 outside",
                VehicleRelevancyGate.passVehicleCount() == pass0 + (counts ? 2 : 0)
                && VehicleRelevancyGate.sendOutsideCount() == out0);
        expect("車內玩家：原版外仍不相關（只縮不放）",
                !VehicleRelevancyGate.sendRelevant(driving, PX + VANILLA_HALF + 1.0f, PY));

        // 分割畫面：0 號步行、2 號坐在車上（乘客也一樣，getVehicle 非 null）
        UdpConnection mixed = conn();
        mixed.players[0] = player(false);
        mixed.players[2] = player(true);
        expect("分割畫面 2 號在車內：整條連線照原版", VehicleRelevancyGate.keepRelevant(mixed, ring, PY));

        UdpConnection joining = conn();
        joining.connectArea[1] = new Vector3(PX / 8.0f, PY / 8.0f, 19.0f);
        long area0 = VehicleRelevancyGate.passConnectAreaCount();
        expect("connectArea 非 null：環帶照原版", VehicleRelevancyGate.sendRelevant(joining, ring, PY));
        expect("connectArea 非 null：passConnectArea " + (counts ? "+1" : "不動"),
                VehicleRelevancyGate.passConnectAreaCount() == area0 + (counts ? 1 : 0));
    }

    /** 任一本地玩家的 R 內就相關。 */
    private static void testSplitScreen() throws Exception {
        UdpConnection c = conn();
        c.releventPos[3] = new Vector3(PX + 60.0f, PY, 0.0f);
        float x = PX + 60.0f + radius - 1.0f;
        expect("分割畫面：0 號 R 外、3 號 R 內 ⇒ 相關", c.isRelevantTo(x, PY) && VehicleRelevancyGate.sendRelevant(c, x, PY));
    }

    private static void testParseMode() {
        expect("parseMode：未設定＝observe", VehicleRelevancyGate.parseMode(null) == VehicleRelevancyGate.OBSERVE);
        expect("parseMode：on／1／enforce＝on",
                VehicleRelevancyGate.parseMode(" On ") == VehicleRelevancyGate.ON
                && VehicleRelevancyGate.parseMode("1") == VehicleRelevancyGate.ON
                && VehicleRelevancyGate.parseMode("enforce") == VehicleRelevancyGate.ON);
        expect("parseMode：off／0＝off",
                VehicleRelevancyGate.parseMode("OFF") == VehicleRelevancyGate.OFF
                && VehicleRelevancyGate.parseMode("0") == VehicleRelevancyGate.OFF);
        expect("parseMode：未知值落回 observe（安全方向）",
                VehicleRelevancyGate.parseMode("bogus") == VehicleRelevancyGate.OBSERVE);
    }

    /** dist 內手術後的真 processServer：Passengers 請求（16384）在環帶時，on 才回 VehicleRemove。 */
    private static void testRealProcessServer() throws Exception {
        VehicleManager.instance = new VehicleManager();
        Capture c = capture();
        boolean shrink = mode == VehicleRelevancyGate.ON;

        BaseVehicle near = vehicle(101, PX + radius - 1.0f, PY);
        BaseVehicle ring = vehicle(102, PX + radius + 1.0f, PY);
        BaseVehicle far = vehicle(103, PX + VANILLA_HALF + 1.0f, PY);

        expect("真 processServer：R 內的車不移除", request(c, near, (short) 16384).isEmpty());
        ArrayList<Short> ringRemoved = request(c, ring, (short) 16384);
        expect("真 processServer：環帶的車 " + (shrink ? "回 VehicleRemove(102)" : "照原版留著"),
                shrink ? ringRemoved.size() == 1 && ringRemoved.get(0) == 102 : ringRemoved.isEmpty());
        ArrayList<Short> farRemoved = request(c, far, (short) 16384);
        expect("真 processServer（原版負對照）：原版外的車三模式都回 VehicleRemove(103)",
                farRemoved.size() == 1 && farRemoved.get(0) == 103);

        // Full 請求（flag 1）不經範圍判定：只記在該連線的 state，等車進入範圍時由 sendVehicles 送
        ArrayList<Short> fullReq = request(c, ring, (short) 1);
        BaseVehicle.ServerVehicleState state = c.vehicleStates.get((short) 102);
        expect("真 processServer：Full 請求不送封包、只記 state.flags |= 1",
                fullReq.isEmpty() && state != null && (state.flags & 1) != 0);

        // 同一個 R 外的車，連線上有人開車時照原版留著
        c.players[0] = player(true);
        expect("真 processServer：連線上有人在車內時環帶的車照原版留著", request(c, ring, (short) 16384).isEmpty());
        c.players[0] = null;
    }

    // ------------------------------------------------------------ fixture

    private static boolean gate(boolean send, UdpConnection c, float x, float y) {
        return send ? VehicleRelevancyGate.sendRelevant(c, x, y) : VehicleRelevancyGate.keepRelevant(c, x, y);
    }

    /** 以真 wire 格式送一筆 VehicleRequest，回傳這次送出的 VehicleRemove 車輛 ID。 */
    private static ArrayList<Short> request(Capture c, BaseVehicle vehicle, short flag) {
        ByteBuffer wire = ByteBuffer.allocate(16);
        wire.putShort((short) 1).putShort(vehicle.getId()).putShort(flag).flip();
        VehicleRequestPacket packet = new VehicleRequestPacket();
        packet.parse(new ByteBufferReader(wire), c);
        c.packets.clear();
        packet.processServer(PacketTypes.PacketType.VehicleRequest, c);
        ArrayList<Short> removed = new ArrayList<>();
        for (byte[] bytes : c.packets) {
            ByteBufferReader r = new ByteBufferReader(ByteBuffer.wrap(bytes));
            if (r.getByte() == (byte) 134 && r.getShort() == PacketTypes.PacketType.VehicleRemove.getId()) {
                removed.add(r.getShort());
            } else {
                removed.add(Short.MIN_VALUE);
            }
        }
        return removed;
    }

    private static UdpConnection conn() throws Exception {
        return conn(PX, PY);
    }

    private static UdpConnection conn(float x, float y) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        fill(c, x, y);
        return c;
    }

    private static void fill(UdpConnection c, float x, float y) {
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.players = new IsoPlayer[4];
        c.releventPos[0] = new Vector3(x, y, 0.0f);
        c.setRelevantRange(RELEVANT_RANGE);
    }

    private static Capture capture() throws Exception {
        Capture c = alloc(Capture.class);
        fill(c, PX, PY);
        c.buffer = ByteBuffer.allocate(4096);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        setDeclared(UdpConnection.class, c, "vehicleStates", new TShortObjectHashMap<BaseVehicle.ServerVehicleState>());
        return c;
    }

    private static BaseVehicle vehicle(int id, float x, float y) throws Exception {
        BaseVehicle v = alloc(BaseVehicle.class);
        v.vehicleId = (short) id;
        setDeclared(IsoMovingObject.class, v, "x", x);
        setDeclared(IsoMovingObject.class, v, "y", y);
        VehicleManager.instance.registerVehicle(v);
        return v;
    }

    /** getVehicle() 是純欄位讀取；車內玩家只需該欄位非 null。 */
    private static IsoPlayer player(boolean inVehicle) throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        if (inVehicle) {
            Field vehicle = findField(p.getClass(), "vehicle");
            vehicle.setAccessible(true);
            vehicle.set(p, alloc(BaseVehicle.class));
        }
        return p;
    }

    /** 只覆寫 buffer 端點與 getPacket 的真連線：VehicleRemove 內容由真 packet／PacketType 寫出。 */
    static final class Capture extends UdpConnection {
        ByteBuffer buffer;
        ByteBufferWriter writer;
        ArrayList<byte[]> packets;

        private Capture() {
            super(null, 0L, 0);
        }

        @Override
        public INetworkPacket getPacket(PacketTypes.PacketType type) {
            return type == PacketTypes.PacketType.VehicleRemove ? new VehicleRemovePacket() : null;
        }

        @Override
        public boolean isHashEquals(PacketTypes.PacketType type, Integer hash) {
            return false;
        }

        @Override
        public ByteBufferWriter startPacket() {
            this.writer.clear();
            return this.writer;
        }

        @Override
        public void cancelPacket() {
            this.buffer.clear();
        }

        @Override
        public void endPacket(int priority, int reliability, byte ordering) {
            byte[] wire = new byte[this.buffer.position()];
            this.buffer.position(0);
            this.buffer.get(wire);
            this.buffer.clear();
            this.packets.add(wire);
        }
    }

    private static void setDeclared(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "arg pass  " : "arg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private VehicleRelevancyGateTest() {}
}
