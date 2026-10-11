package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.PlayerCheats;
import zombie.characters.Role;
import zombie.characters.SurvivorDesc;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoMovingObject;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.worldMap.WorldMapRemotePlayer;
import zombie.worldMap.WorldMapRemotePlayers;

/**
 * W59 世界地圖隊友點錯位行為驗證（獨立 JVM；{@code ENABLED} 與 {@code BASE} 是 static final）。
 * 用法：{@code WorldMapChangeCountTest on|off}（off 需 {@code -Dmdc.worldMapChangeCount=off}，測試自驗）。
 *
 * <p>伺服器端跑手術後的真 {@code GameServer.sendWorldMapPlayerPosition(UdpConnection)} 與
 * {@code receiveWorldMapPlayerPosition}，客戶端跑真 {@code GameClient.receiveWorldMapPlayerPosition}；封包由真 writer
 * 與真 {@code PacketType} 寫進只覆寫 buffer 端點的連線（同 HutchSyncGateTest），由測試決定送達或丟掉。客戶端每幀的
 * {@code IsoPlayer.resetDisplayName → setPlayer}（IsoPlayer.java:7933-7935）直接呼叫真 {@code setPlayer} 重現。
 * 同一個 JVM 內伺服器與客戶端共用 {@code WorldMapRemotePlayers.instance}，所以每次換邊就把它的兩個表換成該邊的表。
 * 可見規則不在本刀範圍：觀看者連線給 SeeWorldMap 能力，讓 {@code shouldSendWorldMapPlayerPosition} 直接放行。
 *
 * <p>鎖：掉一次回覆或索取後，原版（off，以及兩種模式都跑的原版計數負對照）不再索取、{@code hasFullData} 一直是 false；
 * on 每秒索取，送達後 {@code hasFullData=true}；沒掉包時畫面上／畫面外的索取次數兩種模式相同；伺服器條目的計數不被改；
 * 送出值＝BASE+2×原計數，是至少 1024 的偶數；off 送出原值、零 log 行。
 */
public final class WorldMapChangeCountTest {
    private static final String TAG = "[MinidoracatJavaPatch][WorldMapChangeCount]";
    private static final short ID = 148;
    private static final long OWNER_GUID = 4242L;

    private static int failed;
    private static boolean on;
    private static ByteArrayOutputStream logBuf;
    private static CaptureConnection viewer;
    private static CaptureConnection clientConn;
    private static IsoPlayer teammateOnClient;
    private static Method sendPosition;
    private static short packetId;
    private static Side server;
    private static Side client;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        on = "on".equals(mode);
        // DebugLog 格式化鏈與 ServerOptions／PacketTypes 靜態鏈的前置條件（同 SyncIsoObjectProbeTest）。
        RandStandard.INSTANCE.init();
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);

        expect("自驗：ENABLED 與 argv 相符（" + mode + "）", WorldMapChangeCount.ENABLED == on);
        expect("parseEnabled：null/1/on 開，0/off/OFF 關", WorldMapChangeCount.parseEnabled(null)
                && WorldMapChangeCount.parseEnabled("1") && WorldMapChangeCount.parseEnabled("on")
                && !WorldMapChangeCount.parseEnabled("0") && !WorldMapChangeCount.parseEnabled(" off")
                && !WorldMapChangeCount.parseEnabled("OFF"));
        expect("BASE 是 [1024, 16382] 的偶數（本次 " + WorldMapChangeCount.BASE + "）",
                WorldMapChangeCount.BASE % 2 == 0 && WorldMapChangeCount.BASE >= 1024 && WorldMapChangeCount.BASE <= 16382);
        checkValues();

        setUp();
        lostOnce("掉一次回覆", true);
        lostOnce("掉一次索取", false);
        noLoss();

        List<String> rows = rows();
        if (on) {
            expect("首次生效行恰一行，含 base 與 kill switch 名稱", rows.size() == 1
                    && rows.get(0).contains("首次生效 base=" + WorldMapChangeCount.BASE)
                    && rows.get(0).contains("-Dmdc.worldMapChangeCount=0|off"));
        } else {
            expect("off：零 log 行", rows.isEmpty());
        }
        for (String row : rows) {
            System.out.println("log  " + row.substring(row.indexOf(TAG)));
        }
        if (failed > 0) {
            System.out.println("world-map-change-count FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("world-map-change-count OK mode=" + mode + " base=" + WorldMapChangeCount.BASE);
    }

    /** helper 單點：on 送 BASE+2×原值（偶數、至少 1024），off 送原值；原計數 0..8192 逐一比對。 */
    private static void checkValues() {
        WorldMapRemotePlayer entry = new WorldMapRemotePlayer(ID);
        boolean ok = true;
        for (int raw = 0; raw <= 8192; raw++) {
            entry.setFullData((short) raw, "u", "f", "s", "None", 0, 0.0f, 0.0f, false, false, false);
            short sent = WorldMapChangeCount.getChangeCount(entry);
            ok &= on ? sent == (short) (WorldMapChangeCount.BASE + 2 * raw) && sent % 2 == 0 && sent >= 1024
                    : sent == raw;
            ok &= entry.getChangeCount() == raw;
        }
        expect(on ? "on：原計數 0..8192 送出 BASE+2×原值，都是至少 1024 的偶數，條目本身不變"
                : "off：原計數 0..8192 原樣送出，條目本身不變", ok);
    }

    /** 事故情境：新條目第一次索取的回覆（或索取本身）掉了，客戶端第一幀把本機計數推到 1。 */
    private static void lostOnce(String label, boolean dropReply) throws Exception {
        reset();
        byte[] request = clientReceive(serverPosition());
        expect(label + "：新條目第一次收到位置就索取", request != null);
        if (dropReply) {
            serverReply(request);   // 回覆沒送到客戶端
        }
        clientFrame();
        expect(label + "：第一幀把客戶端條目推到 1，沒有完整資料",
                clientEntry().getChangeCount() == 1 && !clientEntry().hasFullData());

        expect(label + "：原版負對照——同一個客戶端收到原版計數（伺服器條目原值 1）的位置包，不索取",
                serverEntry().getChangeCount() == 1 && clientReceive(vanillaPosition()) == null);

        int requests = 0;
        boolean wireOk = true;
        for (int second = 0; second < 10; second++) {
            byte[] position = serverPosition();
            wireOk &= wireCount(position) == (on ? (short) (WorldMapChangeCount.BASE + 2) : 1);
            if (clientReceive(position) != null) {
                requests++;
            }
            clientFrame();
        }
        expect(label + "：位置包的計數每秒都是 " + (on ? "BASE+2" : "1（原版）"), wireOk);
        if (on) {
            expect(label + "：on 每秒索取（10 秒 10 次），沒送達前仍無完整資料",
                    requests == 10 && !clientEntry().hasFullData());
            byte[] reply = serverReply(clientReceive(serverPosition()));
            expect(label + "：回覆的計數也是 BASE+2", wireCount(reply) == (short) (WorldMapChangeCount.BASE + 2));
            clientReceive(reply);
            expect(label + "：on 回覆送達後 hasFullData=true，本機計數＝送出值",
                    clientEntry().hasFullData() && clientEntry().getChangeCount() == (short) (WorldMapChangeCount.BASE + 2));
        } else {
            expect(label + "：off（原版）之後 10 秒一次都不索取，一直沒有完整資料",
                    requests == 0 && !clientEntry().hasFullData());
        }
        expect(label + "：伺服器條目計數仍是 1", serverEntry().getChangeCount() == 1);
    }

    /** 沒掉包：畫面上每秒重新索取一次（伺服器送 accessLevel None、客戶端角色名 user），畫面外拿到後不再索取。 */
    private static void noLoss() throws Exception {
        reset();
        clientReceive(serverReply(clientReceive(serverPosition())));
        expect("沒掉包：第一次往返就拿到完整資料", clientEntry().hasFullData());
        int onScreen = 0;
        for (int second = 0; second < 10; second++) {
            clientFrame();
            byte[] request = clientReceive(serverPosition());
            if (request != null) {
                onScreen++;
                clientReceive(serverReply(request));
            }
        }
        expect("沒掉包、畫面上：10 秒索取 10 次（與原版相同）", onScreen == 10 && clientEntry().hasFullData());
        int offScreen = 0;
        for (int second = 0; second < 10; second++) {
            if (clientReceive(serverPosition()) != null) {
                offScreen++;
            }
        }
        expect("沒掉包、畫面外：10 秒索取 0 次（與原版相同）", offScreen == 0);
    }

    // ------------------------------------------------------------ 兩端的一步

    private static byte[] serverPosition() throws Exception {
        use(server);
        int before = viewer.packets.size();
        sendPosition.invoke(null, viewer);
        return single(viewer, before, "sendWorldMapPlayerPosition");
    }

    private static byte[] serverReply(byte[] request) throws Exception {
        use(server);
        int before = viewer.packets.size();
        GameServer.receiveWorldMapPlayerPosition(reader(request), viewer, packetId);
        return single(viewer, before, "receiveWorldMapPlayerPosition");
    }

    /** 回傳客戶端因這個封包送出的索取；沒有索取回 null。 */
    private static byte[] clientReceive(byte[] packet) throws Exception {
        use(client);
        int before = clientConn.packets.size();
        GameClient.receiveWorldMapPlayerPosition(reader(packet), packetId);
        return clientConn.packets.size() == before ? null : single(clientConn, before, "client request");
    }

    private static void clientFrame() throws Exception {
        use(client);
        WorldMapRemotePlayer entry = WorldMapRemotePlayers.instance.getPlayerByID(ID);
        if (entry != null) {
            entry.setPlayer(teammateOnClient);
        }
    }

    /** 原版 sendWorldMapPlayerPosition 會寫出的位置包：計數是伺服器條目原值。 */
    private static byte[] vanillaPosition() {
        WorldMapRemotePlayer entry = serverEntry();
        ByteBuffer bb = ByteBuffer.allocate(64);
        ByteBufferWriter w = new ByteBufferWriter(bb);
        PacketTypes.PacketType.WorldMapPlayerPosition.doPacket(w);
        w.putBoolean(false);
        w.putShort(1);
        w.putShort(entry.getOnlineID());
        w.putShort(entry.getChangeCount());
        w.putFloat(entry.getX());
        w.putFloat(entry.getY());
        byte[] out = new byte[bb.position()];
        bb.flip();
        bb.get(out);
        return out;
    }

    /** 單一隊友的位置包或完整資料包中的計數欄位。 */
    private static short wireCount(byte[] packet) {
        ByteBufferReader r = reader(packet);
        r.getBoolean();
        r.getShort();   // 人數
        r.getShort();   // onlineId
        return r.getShort();
    }

    private static WorldMapRemotePlayer serverEntry() {
        return server.lookup.get(ID);
    }

    private static WorldMapRemotePlayer clientEntry() {
        return client.lookup.get(ID);
    }

    // ------------------------------------------------------------ fixture

    private static final class Side {
        final ArrayList<WorldMapRemotePlayer> list = new ArrayList<>();
        final HashMap<Short, WorldMapRemotePlayer> lookup = new HashMap<>();
    }

    private static void reset() {
        server = new Side();
        client = new Side();
    }

    private static void use(Side side) throws Exception {
        setDeclared(WorldMapRemotePlayers.class, WorldMapRemotePlayers.instance, "playerList", side.list);
        setDeclared(WorldMapRemotePlayers.class, WorldMapRemotePlayers.instance, "playerLookup", side.lookup);
    }

    private static void setUp() throws Exception {
        packetId = PacketTypes.PacketType.WorldMapPlayerPosition.getId();
        sendPosition = GameServer.class.getDeclaredMethod("sendWorldMapPlayerPosition", UdpConnection.class);
        sendPosition.setAccessible(true);

        viewer = capture();
        Role viewerRole = new Role("user");
        viewerRole.addCapability(Capability.SeeWorldMap);
        setDeclared(UdpConnection.class, viewer, "role", viewerRole);
        clientConn = capture();
        GameClient.connection = clientConn;

        UdpConnection owner = alloc(UdpConnection.class);
        setDeclared(UdpConnection.class, owner, "fullyConnected", Boolean.TRUE);
        UdpEngine engine = alloc(UdpEngine.class);
        Map<Long, UdpConnection> connectionMap = new HashMap<>();
        connectionMap.put(OWNER_GUID, owner);
        setDeclared(UdpEngine.class, engine, "connectionMap", connectionMap);
        GameServer.udpEngine = engine;

        IsoPlayer teammate = teammate();
        GameServer.Players.add(teammate);
        GameServer.IDToPlayerMap.put(ID, teammate);
        GameServer.PlayerToAddressMap.put(teammate, OWNER_GUID);
        teammateOnClient = teammate();
    }

    /** 一般玩家（角色名 user）、活著、沒隱形也沒偽裝。 */
    private static IsoPlayer teammate() throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        setAny(p, "health", 1.0f);
        p.cheats = new PlayerCheats();
        p.username = "Player-B";
        p.onlineId = ID;
        p.role = new Role("user");
        SurvivorDesc desc = alloc(SurvivorDesc.class);
        desc.setForename("Tester");
        desc.setSurname("B");
        setAny(p, "descriptor", desc);
        setDeclared(IsoMovingObject.class, p, "x", 100.5f);
        setDeclared(IsoMovingObject.class, p, "y", 200.5f);
        return p;
    }

    /** 只覆寫 buffer 三端點的真連線：內容仍由真 writer／真 PacketType 寫出。 */
    static final class CaptureConnection extends UdpConnection {
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
        public int getBufferPosition() {
            return this.buffer.position();
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

    private static CaptureConnection capture() throws Exception {
        CaptureConnection c = alloc(CaptureConnection.class);
        c.buffer = ByteBuffer.allocate(4096);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        return c;
    }

    private static byte[] single(CaptureConnection c, int before, String what) {
        if (c.packets.size() != before + 1) {
            throw new IllegalStateException(what + " 應送出恰一包，實際 " + (c.packets.size() - before));
        }
        return c.packets.get(before);
    }

    /** 跳過封包標頭（marker byte＋packet id short），停在 payload 起點。 */
    private static ByteBufferReader reader(byte[] packet) {
        ByteBufferReader r = new ByteBufferReader(ByteBuffer.wrap(packet));
        r.getByte();
        r.getShort();
        return r;
    }

    private static List<String> rows() {
        List<String> out = new ArrayList<>();
        for (String line : logBuf.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static void setDeclared(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setAny(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
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
        System.out.println((ok ? "wmcc pass  " : "wmcc FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private WorldMapChangeCountTest() {
    }
}
