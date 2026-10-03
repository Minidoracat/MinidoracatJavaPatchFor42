package zombie.network.packets.connection;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.random.RandStandard;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.ServerWorldDatabase;
import zombie.popman.LoadedAreas;
import zombie.popman.ZombiePopulationManager;

/**
 * W52：走 dist 手術後的真 {@code ConnectCoopPacket.parse}，帳號名以真 {@code ServerWorldDatabase}
 * 查詢（in-memory SQLite 的 whitelist 表）判定。
 *
 * <p>用法：{@code MdcCoopNameGuardTest enforce|observe|off}（observe／off 須配
 * {@code -Dmdc.coopNameGuard=observe|off}）。off 是原版負對照：冒名照樣成功。
 *
 * <p>只在 JNI 邊界收手：連線以 Unsafe 配置、只覆寫送封包的兩個端點；{@code GameServer.Players}
 * 塞 64 個同位置假玩家把 {@code LoadedAreas} 填到上限，原版 {@code updateLoadedAreas} 判定「沒有變化」，
 * 不呼叫測試 JVM 沒有的 popman native。
 */
public final class MdcCoopNameGuardTest {

    private static final String WORLD = "w52test";
    private static int failed;
    private static List<UdpConnection> connections;

    public static void main(String[] args) throws Exception {
        RandStandard.INSTANCE.init();
        GameServer.server = true;
        String want = args.length > 0 ? args[0] : "enforce";
        int wantMode = switch (want) {
            case "off" -> MdcCoopNameGuard.OFF;
            case "observe" -> MdcCoopNameGuard.OBSERVE;
            default -> MdcCoopNameGuard.ENFORCE;
        };
        check("自驗：argv=" + want + " 與 MODE 相符（MODE=" + MdcCoopNameGuard.MODE + "）",
                MdcCoopNameGuard.MODE == wantMode);
        boolean enforce = wantMode == MdcCoopNameGuard.ENFORCE;
        boolean judges = wantMode != MdcCoopNameGuard.OFF;
        setUpWorld("Victim", "host", "host2", "attacker", "test2");

        // 0 號以別人的帳號名重生：enforce 改回登入名，重生照常 granted。
        long renamed = MdcCoopNameGuard.renamedCount();
        CaptureConnection a = conn("attacker");
        a.usernames[0] = "attacker";
        Reply ra = stage1(a, 0, "Victim");
        check("0 號送別人的名字：" + (enforce ? "改用登入名" : "原版採用封包名稱"),
                (enforce ? "attacker" : "Victim").equals(a.usernames[0]));
        check("0 號改名不擋重生（granted）", ra.granted(0));
        check("0 號改名計數 " + (judges ? "+1" : "不變"),
                MdcCoopNameGuard.renamedCount() == renamed + (judges ? 1 : 0));

        // 0 號正常重生送的就是登入名：任何模式都照原版、不計數。
        renamed = MdcCoopNameGuard.renamedCount();
        long rejected = MdcCoopNameGuard.rejectedCount();
        CaptureConnection b = conn("host");
        b.usernames[0] = "host";
        Reply rb = stage1(b, 0, "host");
        check("0 號送登入名：照原版 granted、名稱不變、不計數",
                rb.granted(0) && "host".equals(b.usernames[0])
                        && MdcCoopNameGuard.renamedCount() == renamed
                        && MdcCoopNameGuard.rejectedCount() == rejected);

        // 連線沒有登入名：enforce 不信封包名稱，在任何副作用前拒絕。
        rejected = MdcCoopNameGuard.rejectedCount();
        CaptureConnection c = conn(null);
        Reply rc = stage1(c, 0, "Orphan");
        check("0 號連線沒有登入名：" + (enforce ? "拒絕且沒有配位置" : "原版採用封包名稱"),
                enforce ? rc.denied(0, "No username given") && c.usernames[0] == null && c.connectArea[0] == null
                        : rc.granted(0) && "Orphan".equals(c.usernames[0]));
        check("沒有登入名的計數 " + (judges ? "+1" : "不變"),
                MdcCoopNameGuard.rejectedCount() == rejected + (judges ? 1 : 0));

        // 1 號分割畫面用帳號名（大小寫不同）：enforce 在 setUserName、配 ID、送 granted 之前拒絕。
        rejected = MdcCoopNameGuard.rejectedCount();
        int addresses = GameServer.IDToAddressMap.size();
        CaptureConnection d = conn("host2");
        d.usernames[0] = "host2";
        Reply rd = stage1(d, 1, "victim");
        if (enforce) {
            check("1 號用帳號名（不分大小寫）：拒絕，沒有任何副作用",
                    rd.denied(1, "No username given") && d.usernames[1] == null && d.connectArea[1] == null
                            && d.releventPos[1] == null && d.playerIds[1] == -1
                            && GameServer.IDToAddressMap.size() == addresses);
        } else {
            check("1 號用帳號名：原版照樣 granted，冒用成立",
                    rd.granted(1) && "victim".equals(d.usernames[1]) && d.connectArea[1] != null);
        }
        check("帳號名計數 " + (judges ? "+1" : "不變"),
                MdcCoopNameGuard.rejectedCount() == rejected + (judges ? 1 : 0));

        // 1 號自由取名：任何模式都照原版。
        rejected = MdcCoopNameGuard.rejectedCount();
        Reply re = stage1(d, 2, "Couch");
        check("2 號自由名稱：照原版 granted、不計數",
                re.granted(2) && "Couch".equals(d.usernames[2]) && MdcCoopNameGuard.rejectedCount() == rejected);

        // 空名稱：原版自己拒絕，helper 不介入。
        rejected = MdcCoopNameGuard.rejectedCount();
        Reply rf = stage1(d, 3, "");
        check("空名稱：照原版拒絕、不計數",
                rf.denied(3, "No username given") && d.usernames[3] == null
                        && MdcCoopNameGuard.rejectedCount() == rejected);

        // stage 2 只綁定不讀名稱；它留下的綁定不能被下一個連線的 stage 1 拿去用。
        CaptureConnection g1 = conn("stale");
        Reply rg1 = stage2(g1, 3);
        check("stage 2 沒有 stage 1 紀錄：原版拒絕", rg1.denied(3, "Coop player login wasn't received"));
        CaptureConnection g2 = conn("bob");
        g2.usernames[0] = "bob";
        stage1(g2, 0, "carol");
        check("上一包的綁定不外洩：" + (enforce ? "用本連線的登入名" : "原版採用封包名稱"),
                (enforce ? "bob" : "carol").equals(g2.usernames[0]));

        check("anomalies 恆 0", MdcCoopNameGuard.anomalyCount() == 0);
        System.out.println(failed == 0 ? "MdcCoopNameGuardTest OK (" + want + ")"
                : "MdcCoopNameGuardTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------ fixture

    private static void setUpWorld(String... accounts) throws Exception {
        Core.gameSaveWorld = WORLD;
        Class.forName("org.sqlite.JDBC");
        Connection db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = db.createStatement()) {
            st.executeUpdate("CREATE TABLE whitelist (id INTEGER PRIMARY KEY NOT NULL, world TEXT, username TEXT)");
        }
        try (PreparedStatement ins = db.prepareStatement("INSERT INTO whitelist (world, username) VALUES (?, ?)")) {
            for (String account : accounts) {
                ins.setString(1, WORLD);
                ins.setString(2, account);
                ins.executeUpdate();
            }
        }
        Field conn = ServerWorldDatabase.class.getDeclaredField("conn");
        conn.setAccessible(true);
        conn.set(ServerWorldDatabase.instance, db);

        UdpEngine engine = alloc(UdpEngine.class);
        connections = new ArrayList<>();
        setDeclared(UdpEngine.class, engine, "connections", connections);
        GameServer.udpEngine = engine;

        for (int i = 0; i < LoadedAreas.MAX_AREAS; i++) {
            GameServer.Players.add(alloc(IsoPlayer.class));
        }
        Field areas = ZombiePopulationManager.class.getDeclaredField("loadedAreas");
        areas.setAccessible(true);
        LoadedAreas loaded = (LoadedAreas) areas.get(ZombiePopulationManager.instance);
        loaded.set();
        check("前置：LoadedAreas 已滿，之後 set() 判定沒有變化（不進 native）",
                loaded.count == LoadedAreas.MAX_AREAS && !loaded.set());
    }

    private static Reply stage1(CaptureConnection c, int playerIndex, String name) {
        ByteBuffer bb = ByteBuffer.allocate(512);
        ByteBufferWriter w = new ByteBufferWriter(bb);
        w.putByte((byte) 1);
        w.putByte((byte) playerIndex);
        w.putUTF(name);
        w.putFloat(10_776.5f);
        w.putFloat(9_764.5f);
        return parse(c, bb);
    }

    private static Reply stage2(CaptureConnection c, int playerIndex) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        ByteBufferWriter w = new ByteBufferWriter(bb);
        w.putByte((byte) 2);
        w.putByte((byte) playerIndex);
        return parse(c, bb);
    }

    private static Reply parse(CaptureConnection c, ByteBuffer bb) {
        bb.flip();
        c.packets.clear();
        new ConnectCoopPacket().parse(new ByteBufferReader(bb), c);
        return new Reply(c.packets);
    }

    /** 送給這條連線的 ConnectedCoop 回覆；只接受恰好一包。 */
    private record Reply(List<byte[]> packets) {
        boolean granted(int playerIndex) {
            ByteBufferReader r = only();
            return r != null && r.getBoolean() && r.getByte() == playerIndex;
        }

        boolean denied(int playerIndex, String reason) {
            ByteBufferReader r = only();
            return r != null && !r.getBoolean() && r.getByte() == playerIndex && reason.equals(r.getUTF());
        }

        private ByteBufferReader only() {
            if (packets.size() != 1) {
                return null;
            }
            ByteBufferReader r = new ByteBufferReader(ByteBuffer.wrap(packets.get(0)));
            boolean header = r.getByte() == (byte) 134 && r.getShort() == PacketTypes.PacketType.ConnectedCoop.getId();
            return header ? r : null;
        }
    }

    /** 只替換 RakNet 送出端的真連線：封包內容仍由原版 ConnectCoopPacket／PacketType 寫出。 */
    private static final class CaptureConnection extends UdpConnection {
        ByteBuffer buffer;
        ByteBufferWriter writer;
        ArrayList<byte[]> packets;

        /** 永不執行：實例一律以 Unsafe 配置，只為了讓子類別能通過編譯。 */
        private CaptureConnection() {
            super(null, 0L, 0);
        }

        @Override
        public ByteBufferWriter startPacket() {
            this.writer.clear();
            return this.writer;
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

    private static CaptureConnection conn(String login) throws Exception {
        CaptureConnection c = alloc(CaptureConnection.class);
        c.buffer = ByteBuffer.allocate(4096);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        c.usernames = new String[4];
        c.players = new IsoPlayer[4];
        c.releventPos = new zombie.iso.Vector3[4];
        c.connectArea = new zombie.iso.Vector3[4];
        c.playerIds = new short[4];
        Arrays.fill(c.playerIds, (short) -1);
        c.setChunkGridWidth(13);
        c.setUserName(login);
        connections.add(c);
        return c;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    private static void setDeclared(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private MdcCoopNameGuardTest() {}
}
