package zombie.network.packets;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zombie.GameWindow;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandStandard;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoMovingObject;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.zones.Zone;
import zombie.mdc.ZoneSyncGuard;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.PacketsCache;

/**
 * W66 畜牧區同步修補的行為驗證（docs/patches.md 2cb）。argv：on＝出貨組態；off＝三個 kill switch 全關（原版）。
 *
 * <p>走真的 {@code SyncZonePacket.parse／processServer}（已手術的 class）、真的 {@code INetworkPacket.sendToRelative}
 * 與真的 {@code DesignationZone.load}；連線是只覆寫 buffer 三端點的 {@link UdpConnection} 子類，封包內容由真 writer 寫出，
 * 收件端用真 {@link ByteBufferReader} 解回。原版負對照直接呼叫未改道的原方法，兩種模式都要成立。
 */
public final class ZoneSyncTest {
    private static final String TAG = "[MinidoracatJavaPatch][ZoneSync] ";
    private static final Pattern ZONE_ID = Pattern.compile("zone=(\\d+)");

    private static int failed;
    private static boolean on;
    private static ByteArrayOutputStream logBuf;
    private static List<UdpConnection> connections;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        on = "on".equals(mode);
        RandStandard.INSTANCE.init();
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);
        UdpEngine engine = alloc(UdpEngine.class);
        connections = new ArrayList<>();
        setDeclared(UdpEngine.class, engine, "connections", connections);
        GameServer.udpEngine = engine;

        expect("自驗：三個開關與 argv 相符（" + mode + "）", flag(ZoneSyncGuard.class, "GRASS") == on
                && flag(ZoneSyncGuard.class, "REPAIR") == on && MdcSyncZoneLog.ENABLED == on);

        grassSync();
        clientReplay();
        loadRepair();
        changeLog();
        if (on) {
            rateCap();
        } else {
            expect("off：整個 JVM 零紀錄行", rows().isEmpty());
        }

        if (failed > 0) {
            System.out.println("zone-sync FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("zone-sync OK mode=" + mode);
    }

    /** 之二：動物吃草的 SyncZone 沿用每條連線的快取實例，送出的是這位玩家上次的畜牧區新增。 */
    private static void grassSync() throws Exception {
        reset();
        CaptureConnection creator = conn("Player-A", 11L, 1000.5f, 1000.5f);
        CaptureConnection bystander = conn("Player-B", 12L, 1001.5f, 1001.5f);
        connections.add(creator);
        connections.add(bystander);
        serverReceive(creator, add(700001, "pen", 1000, 1000, 5, 5));
        DesignationZoneAnimal pen = DesignationZoneAnimal.getZoneById(700001);
        byte[] relay = single(bystander, "建立畜牧區的轉發");
        expect("建立：伺服器有 700001，旁人收到它的新增", pen != null && DesignationZone.allZones.contains(pen)
                && designationAdd(relay) == 700001 && creator.wire.isEmpty());
        DesignationZoneAnimal.removeZone(pen, false);   // 別人刪掉（伺服器上已沒有）
        Zone grass = grassZone(980, 980);

        INetworkPacket.sendToRelative(PacketTypes.PacketType.SyncZone, 1000f, 1000f, grass);
        byte[] vanillaCreator = single(creator, "原版草地通知（建立者）");
        byte[] vanillaBystander = single(bystander, "原版草地通知（旁人）");
        expect("原版負對照：草地 SyncZone 送給建立者的是他上次的畜牧區新增（700001 已刪仍送）",
                designationAdd(vanillaCreator) == 700001);
        expect("原版：沒送過畜牧區的旁人收到真的草地 SyncZone", isGrassSync(vanillaBystander, grass));

        ZoneSyncGuard.sendToRelative(PacketTypes.PacketType.SyncZone, 1000f, 1000f, new Object[]{grass});
        byte[] gotCreator = single(creator, "helper 草地通知（建立者）");
        byte[] gotBystander = single(bystander, "helper 草地通知（旁人）");
        if (on) {
            expect("on：兩人都收到同一塊草地區的 RegisterZone，沒有 SyncZone",
                    isGrassRegister(gotCreator, grass) && isGrassRegister(gotBystander, grass));
        } else {
            expect("off：逐位元與原版相同（建立者照樣收到殘留的畜牧區新增）",
                    Arrays.equals(gotCreator, vanillaCreator) && Arrays.equals(gotBystander, vanillaBystander));
        }

        INetworkPacket.sendToRelative(PacketTypes.PacketType.RegisterZone, 1000f, 1000f, grass);
        byte[] registerVanilla = single(creator, "原版 RegisterZone");
        single(bystander, "原版 RegisterZone（旁人）");
        ZoneSyncGuard.sendToRelative(PacketTypes.PacketType.RegisterZone, 1000f, 1000f, new Object[]{grass});
        expect("新建草地區的 RegisterZone 照原版", Arrays.equals(single(creator, "helper RegisterZone"), registerVanilla)
                && isGrassRegister(registerVanilla, grass));
        single(bystander, "helper RegisterZone（旁人）");
        List<String> announced = rows().stream().filter(r -> r.contains("首次生效")).toList();
        expect(on ? "on：首次生效行恰一行" : "off：沒有首次生效行", announced.size() == (on ? 1 : 0));
    }

    /** 原版負對照：客戶端的快取實例留著上一次收到的刪除，收到真的草地 SyncZone 就再刪一次，連帶刪掉之後畫在旁邊的畜牧區。 */
    private static void clientReplay() throws Exception {
        reset();
        SyncZonePacket client = new SyncZonePacket();
        DesignationZoneAnimal old = new DesignationZoneAnimal("old", 1100, 1100, 0, 1105, 1105, false);
        clientProcess(client, remove(old.getId()));
        DesignationZoneAnimal redrawn = new DesignationZoneAnimal("redrawn", 1105, 1100, 0, 1110, 1105, false);
        expect("客戶端：收到的刪除照原版刪掉 old，之後在旁邊畫的 redrawn 還在",
                !DesignationZoneAnimal.designationAnimalZoneList.contains(old)
                        && DesignationZoneAnimal.designationAnimalZoneList.contains(redrawn));
        ByteBufferWriter w = new ByteBufferWriter(ByteBuffer.allocate(256));
        SyncZonePacket grassPacket = new SyncZonePacket();
        grassPacket.setData(grassZone(1090, 1090));
        grassPacket.write(w);
        clientProcess(client, w.bb.flip());
        expect("原版負對照：客戶端收到真的草地 SyncZone 時重播上次的刪除，redrawn 被連帶刪掉",
                !DesignationZoneAnimal.designationAnimalZoneList.contains(redrawn));
    }

    /** 之三：載入時暫時 id 撞號的畜牧區不進 allZones（不存檔、不送給新連線），on 補回。 */
    private static void loadRepair() throws Exception {
        reset();
        ScriptedRandom rnd = new ScriptedRandom();
        Field randField = RandAbstract.class.getDeclaredField("rand");
        randField.setAccessible(true);
        Object saved = randField.get(RandStandard.INSTANCE);
        randField.set(RandStandard.INSTANCE, rnd);
        try {
            rnd.next = 1;
            DesignationZone first = DesignationZone.load(record(800001, "AnimalZone", "first", 2000, 2000, 4, 4), 249);
            rnd.next = 800001 - 100000;   // 暫時 id＝已載入的 800001
            DesignationZone lost = DesignationZone.load(record(800002, "AnimalZone", "lost", 2010, 2000, 4, 4), 249);
            expect("原版負對照：暫時 id 撞號 → 在畜牧區清單、不在 allZones（不存檔）",
                    DesignationZone.allZones.contains(first) && lost != null && lost.getId() == 800002.0
                            && DesignationZoneAnimal.designationAnimalZoneList.contains(lost)
                            && !DesignationZone.allZones.contains(lost));

            rnd.next = 800001 - 100000;
            DesignationZone kept = ZoneSyncGuard.load(record(800003, "AnimalZone", "kept", 2020, 2000, 4, 4), 249);
            expect(on ? "on：撞號的那塊補進 allZones 恰一次" : "off：撞號的那塊照原版不在 allZones",
                    Collections.frequency(DesignationZone.allZones, kept) == (on ? 1 : 0)
                            && DesignationZoneAnimal.designationAnimalZoneList.contains(kept));

            DesignationZone again = ZoneSyncGuard.load(record(800002, "AnimalZone", "lost renamed", 2010, 2000, 4, 4), 249);
            expect(on ? "on：已漏掉的那塊下次被改名或重送時補回" : "off：已漏掉的那塊照原版仍不在 allZones",
                    again == lost && "lost renamed".equals(again.getName())
                            && Collections.frequency(DesignationZone.allZones, lost) == (on ? 1 : 0));

            rnd.next = 7;
            DesignationZone normal = ZoneSyncGuard.load(record(800004, "AnimalZone", "normal", 2030, 2000, 4, 4), 249);
            expect("沒撞號：allZones 恰一次（不重複加入）", Collections.frequency(DesignationZone.allZones, normal) == 1);
            expect("非畜牧區型別照原版回 null", ZoneSyncGuard.load(record(800005, "Other", "x", 2040, 2000, 4, 4), 249) == null);
            int repairRows = (int) rows().stream().filter(r -> r.contains("撞號")).count();
            expect(on ? "on：每次補回各記一行（2 行）" : "off：沒有補回紀錄", repairRows == (on ? 2 : 0));
        } finally {
            randField.set(RandStandard.INSTANCE, saved);
        }
    }

    /** 之一：記下誰新增、改名、刪除了哪一塊，刪除時列出的連帶組＝實際被刪掉的組；轉發與刪除照原版。 */
    private static void changeLog() throws Exception {
        reset();
        CaptureConnection alice = conn("Player-A", 21L, 3000.5f, 3000.5f);
        CaptureConnection bob = conn("Player-B", 22L, 3100.5f, 3100.5f);
        connections.add(alice);
        connections.add(bob);
        int mark = rows().size();
        serverReceive(alice, add(900001, "pen", 3000, 3000, 5, 5));
        serverReceive(alice, add(900001, "Alice pen", 3000, 3000, 6, 5));
        serverReceive(alice, add(900002, "R1", 3020, 3000, 5, 5));
        serverReceive(alice, add(900003, "R2", 3025, 3000, 5, 5));
        serverReceive(alice, add(900004, "R3", 3040, 3000, 5, 5));
        Set<Double> before = ids();
        serverReceive(bob, remove(900002));
        Set<Double> removed = new HashSet<>(before);
        removed.removeAll(ids());
        serverReceive(bob, remove(123));   // 不存在的 id：原版不轉發也不刪

        expect("原版刪除：R1 連帶相連的 R2 一起刪，R3 與 pen 還在", removed.equals(Set.of(900002.0, 900003.0))
                && ids().equals(Set.of(900001.0, 900004.0)));
        expect("轉發照原版：bob 收到 alice 的 5 筆、alice 收到 bob 的 1 筆刪除（不存在的 id 不轉發）",
                bob.wire.size() == 5 && alice.wire.size() == 1 && designationRemove(alice.wire.get(0)) == 900002.0);

        List<String> rows = rows().subList(mark, rows().size());
        if (!on) {
            expect("off：沒有任何紀錄", rows.isEmpty());
            return;
        }
        expect("on：五筆新增或改名＋一筆刪除，共 6 行（不存在的 id 不記）", rows.size() == 6);
        if (rows.size() != 6) {
            rows.forEach(r -> System.out.println("     " + r));
            return;
        }
        expect("新增：zone 900001、名稱、送出者與位置", rows.get(0).contains("新增 zone=900001 \"pen\"")
                && rows.get(0).contains("by=Player-A@3000,3000,0"));
        expect("改名：舊名→新名與寬度變化", rows.get(1).contains("改名 zone=900001 \"pen\"→\"Alice pen\"")
                && rows.get(1).contains("5x5→6x5"));
        String removal = rows.get(5);
        Set<Double> logged = new HashSet<>();
        Matcher m = ZONE_ID.matcher(removal);
        while (m.find()) {
            logged.add(Double.parseDouble(m.group(1)));
        }
        expect("刪除：記錄的整組＝實際刪掉的整組，記下刪除者", removal.contains("移除 zone=900002")
                && removal.contains("連帶 1 塊") && logged.equals(removed) && removal.contains("by=Player-B@3100,3100,0"));
        for (String row : rows) {
            System.out.println("log  " + row.substring(row.indexOf(TAG)));
        }
    }

    /** 每分鐘最多 30 行，超過只計數，下一個時間窗的第一行補上筆數。 */
    private static void rateCap() {
        int mark = rows().size();
        long t0 = System.currentTimeMillis() + 3_600_000L;
        for (int i = 0; i < MdcSyncZoneLog.LINES_PER_MINUTE + 5; i++) {
            MdcSyncZoneLog.emit("cap " + i, t0 + i);
        }
        int inWindow = rows().size() - mark;
        MdcSyncZoneLog.emit("cap next", t0 + 60_000L);
        List<String> rows = rows();
        expect("限頻：同一分鐘只印 30 行，下一分鐘第一行補報 5 筆", inWindow == MdcSyncZoneLog.LINES_PER_MINUTE
                && rows.get(rows.size() - 1).contains("cap next") && rows.get(rows.size() - 1).contains("另有 5 筆"));
    }

    // ---- 封包與世界 ----

    private static void serverReceive(CaptureConnection c, ByteBuffer payload) {
        SyncZonePacket packet = (SyncZonePacket) c.getPacket(PacketTypes.PacketType.SyncZone);
        packet.parse(new ByteBufferReader(payload), c);
        packet.processServer(PacketTypes.PacketType.SyncZone, c);
    }

    private static void clientProcess(SyncZonePacket packet, ByteBuffer payload) {
        packet.parse(new ByteBufferReader(payload), null);
        try {
            packet.processClient(null);
        } catch (Throwable t) {
            // 原版刪除之後才觸發 Lua 事件；測試沒有 Lua 環境，事件失敗不影響已發生的刪除
        }
    }

    private static ByteBuffer add(double id, String name, int x, int y, int w, int h) {
        ByteBuffer bb = ByteBuffer.allocate(512);
        bb.put((byte) 1).put((byte) 1);
        bb.put(record(id, "AnimalZone", name, x, y, w, h));
        return bb.flip();
    }

    private static ByteBuffer remove(double id) {
        return ByteBuffer.allocate(16).put((byte) 1).put((byte) 0).putDouble(id).flip();
    }

    /** {@code DesignationZone.save} 的格式。 */
    private static ByteBuffer record(double id, String type, String name, int x, int y, int w, int h) {
        ByteBuffer bb = ByteBuffer.allocate(512);
        bb.putDouble(id).putInt(x).putInt(y).putInt(0).putInt(h).putInt(w);
        GameWindow.WriteString(bb, type);
        GameWindow.WriteString(bb, name);
        bb.putInt(0);
        return bb.flip();
    }

    private static Zone grassZone(int x, int y) throws Exception {
        Zone zone = alloc(Zone.class);
        zone.name = "";
        zone.type = "GrassRegrowth";
        zone.x = x;
        zone.y = y;
        zone.z = 0;
        zone.w = 40;
        zone.h = 40;
        zone.setLastActionTimestamp(1_234_567);
        return zone;
    }

    private static Set<Double> ids() {
        Set<Double> out = new HashSet<>();
        for (DesignationZoneAnimal zone : DesignationZoneAnimal.designationAnimalZoneList) {
            out.add(zone.getId());
        }
        for (DesignationZone zone : DesignationZone.allZones) {
            out.add(zone.getId());
        }
        return out;
    }

    private static void reset() {
        DesignationZone.allZones.clear();
        DesignationZoneAnimal.designationAnimalZoneList.clear();
        connections.clear();
    }

    /** SyncZone 畜牧區新增的 id；不是就回 -1。 */
    private static double designationAdd(byte[] packet) {
        ByteBufferReader r = body(packet, PacketTypes.PacketType.SyncZone);
        return r != null && r.getBoolean() && r.getBoolean() ? r.getDouble() : -1;
    }

    private static double designationRemove(byte[] packet) {
        ByteBufferReader r = body(packet, PacketTypes.PacketType.SyncZone);
        return r != null && r.getBoolean() && !r.getBoolean() ? r.getDouble() : -1;
    }

    private static boolean isGrassSync(byte[] packet, Zone grass) {
        ByteBufferReader r = body(packet, PacketTypes.PacketType.SyncZone);
        return r != null && !r.getBoolean() && r.getInt() == grass.x && r.getInt() == grass.y && r.getInt() == grass.z
                && grass.type.equals(r.getUTF()) && r.getInt() == grass.getLastActionTimestamp();
    }

    private static boolean isGrassRegister(byte[] packet, Zone grass) {
        ByteBufferReader r = body(packet, PacketTypes.PacketType.RegisterZone);
        return r != null && grass.name.equals(r.getUTF()) && grass.type.equals(r.getUTF()) && r.getInt() == grass.x
                && r.getInt() == grass.y && r.getInt() == grass.z && r.getInt() == grass.w && r.getInt() == grass.h
                && r.getInt() == grass.getLastActionTimestamp() && !r.bb.hasRemaining();
    }

    /** 跳過標頭（marker byte＋packet id short）；型別不符回 null。PacketType 靜態初始化要等 Rand 就緒，不能放在欄位初始值。 */
    private static ByteBufferReader body(byte[] packet, PacketTypes.PacketType type) {
        ByteBufferReader r = new ByteBufferReader(ByteBuffer.wrap(packet));
        r.getByte();
        return r.getShort() == type.getId() ? r : null;
    }

    // ---- 連線、log、反射 ----

    /** 只覆寫 buffer 三端點的真連線：內容仍由真 writer／真 PacketType 寫出。 */
    static final class CaptureConnection extends UdpConnection {
        ByteBuffer buffer;
        ByteBufferWriter writer;
        ArrayList<byte[]> wire;

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
            byte[] bytes = new byte[this.buffer.position()];
            this.buffer.position(0);
            this.buffer.get(bytes);
            this.buffer.clear();
            this.wire.add(bytes);
        }

        @Override
        public boolean isRelevantTo(float x, float y) {
            return true;
        }
    }

    private static CaptureConnection conn(String user, long guid, float x, float y) throws Exception {
        CaptureConnection c = alloc(CaptureConnection.class);
        c.buffer = ByteBuffer.allocate(64 * 1024);
        c.writer = new ByteBufferWriter(c.buffer);
        c.wire = new ArrayList<>();
        c.players = new IsoPlayer[4];
        setDeclared(UdpConnection.class, c, "fullyConnected", Boolean.TRUE);
        setDeclared(UdpConnection.class, c, "connectedGuid", guid);
        setDeclared(UdpConnection.class, c, "userName", user);
        HashMap<PacketTypes.PacketType, INetworkPacket> cache = new HashMap<>();
        cache.put(PacketTypes.PacketType.SyncZone, new SyncZonePacket());
        cache.put(PacketTypes.PacketType.RegisterZone, new RegisterZonePacket());
        setDeclared(PacketsCache.class, c, "packets", cache);
        IsoPlayer player = alloc(IsoPlayer.class);
        setDeclared(IsoMovingObject.class, player, "x", x);
        setDeclared(IsoMovingObject.class, player, "y", y);
        c.players[0] = player;
        return c;
    }

    private static byte[] single(CaptureConnection c, String what) {
        if (c.wire.size() != 1) {
            throw new IllegalStateException(what + " 應送出恰一包，實際 " + c.wire.size());
        }
        return c.wire.remove(0);
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

    static final class ScriptedRandom extends Random {
        int next = -1;

        @Override
        public int nextInt(int bound) {
            if (this.next >= 0) {
                int value = this.next;
                this.next = -1;
                return value;
            }
            return super.nextInt(bound);
        }
    }

    private static boolean flag(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static void setDeclared(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "zone pass  " : "zone FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private ZoneSyncTest() {
    }
}
