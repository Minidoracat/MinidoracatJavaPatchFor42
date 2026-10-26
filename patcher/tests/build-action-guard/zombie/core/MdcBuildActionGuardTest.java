package zombie.core;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.GameServer;
import zombie.network.PZNetKahluaTableImpl;
import zombie.network.PacketTypes;
import zombie.network.packets.BuildActionPacket;

/**
 * W76 建造類別白名單＋W77 建造距離（MdcBuildActionGuard）行為驗證。走 dist 內手術後的真 {@code BuildAction.parse}：
 * 真 wire（Action 標頭、座標、sprite、{@code PZNetKahluaTableImpl.save} 的 Type／Arguments），Lua 環境換成最小替身
 * （{@code LuaManager.env} 放原版 derive 形狀的類別表、建構子是計次的 JavaFunction、{@code LuaManager.caller} 計次）。
 * 拒絕的請求再跑原版 {@code processServer}，驗客戶端收到 Reject。argv：{@code both}／{@code type-off}／{@code range-off}，自驗旗標。
 */
public final class MdcBuildActionGuardTest {

    private static final String TAG = "[MinidoracatJavaPatch][BuildActionGuard]";
    private static final short OWNER_ID = 3;
    private static final short ADMIN_ID = 4;
    private static final KahluaTable MADE = new KahluaTableImpl(new LinkedHashMap<>());
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream（擷取 log 時一起被收走）：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    private static int failed;
    private static int ctorCalls;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "both";
        boolean wantType = !"type-off".equals(mode);
        boolean wantRange = !"range-off".equals(mode);
        expect("自驗：argv=" + mode + " 與實際旗標相符（type=" + MdcBuildActionGuard.TYPE_CHECK
                        + " range=" + MdcBuildActionGuard.RANGE_CHECK + "）",
                MdcBuildActionGuard.TYPE_CHECK == wantType && MdcBuildActionGuard.RANGE_CHECK == wantRange);

        GameServer.server = true;
        LuaManager.env = table();
        LuaManager.caller = new CountingCaller();
        KahluaTable base = classTable("ISBaseObject", null);
        KahluaTable building = classTable("ISBuildingObject", base);
        classTable("ISWoodenWall", building);
        KahluaTable cursor = classTable("ISPickCharacterCursor", building);
        classTable("ISAnimalPickMateCursor", cursor);   // 原版唯一的二層 derive
        classTable("ISUIElement", base);                 // 有 new、但不是建造類別
        KahluaTable loopA = classTable("LoopA", null);
        KahluaTable loopB = classTable("LoopB", loopA);
        loopA.setMetatable(loopB);                       // metatable 循環：不能無限迴圈

        IsoPlayer owner = player(OWNER_ID, "builder", 100.5F, 200.5F, 0.0F, false);
        IsoPlayer admin = player(ADMIN_ID, "admin", 100.5F, 200.5F, 0.0F, true);
        ByteArrayOutputStream log = captureLog();

        // ---- 正常建造（兩個開關都不擋）----
        expectBuilt("原版類別、玩家旁邊（相鄰格）", OWNER_ID, "ISWoodenWall", 101, 200, 0);
        expectBuilt("二層 derive（ISAnimalPickMateCursor → ISPickCharacterCursor → ISBuildingObject）",
                OWNER_ID, "ISAnimalPickMateCursor", 100, 200, 0);
        expectBuilt("4 格寬雙門的原點格（每軸差 5 格上下）", OWNER_ID, "ISWoodenWall", 105, 195, 0);
        expectBuilt("每軸差恰 8 格（邊界）", OWNER_ID, "ISWoodenWall", 108.5F, 192.5F, 0);
        expectBuilt("樓層差 1（爬梯時游標 z 與伺服器 z 差一層）", OWNER_ID, "ISWoodenWall", 100, 200, 1);

        // ---- W76：類別不是 ISBuildingObject 子類 ----
        long type0 = MdcBuildActionGuard.typeRefusedForTest();
        expectTypeCase(wantType, "有 new 的非建造全域（ISUIElement）", "ISUIElement");
        expectTypeCase(wantType, "ISBuildingObject 本身（不是子類）", "ISBuildingObject");
        expectTypeCase(wantType, "metatable 循環的全域表", "LoopA");
        expectTypeCase(wantType, "不存在的全域（Type 亂填、含點號）", "ISWoodenWall.SuperType");
        if (wantType) {
            expect("W76 計數恰 +4", MdcBuildActionGuard.typeRefusedForTest() == type0 + 4);
            expect("W76 log：refused reason=type 帶客戶端給的 Type",
                    rows(log).stream().anyMatch(r -> r.contains(TAG + " refused reason=type type=ISUIElement owner=3:builder")));
        } else {
            expect("type-off：零計數", MdcBuildActionGuard.typeRefusedForTest() == type0);
        }

        // ---- W77：位置離 owner 太遠 ----
        long range0 = MdcBuildActionGuard.rangeRefusedForTest();
        expectRangeCase(wantRange, "x 差 9 格", OWNER_ID, 109.5F, 200, 0);
        expectRangeCase(wantRange, "y 差 9 格（另一軸）", OWNER_ID, 100, 191.5F, 0);
        expectRangeCase(wantRange, "遠處（地圖另一頭）", OWNER_ID, 9000, 9000, 0);
        expectRangeCase(wantRange, "樓層差 2", OWNER_ID, 100, 200, 2);
        expectRangeCase(wantRange, "座標 NaN", OWNER_ID, Float.NaN, 200, 0);
        if (wantRange) {
            expect("W77 計數恰 +5", MdcBuildActionGuard.rangeRefusedForTest() == range0 + 5);
            expect("W77 log：refused reason=range 帶 owner 位置與目標",
                    rows(log).stream().anyMatch(r -> r.contains(TAG + " refused reason=range type=ISWoodenWall owner=3:builder"
                            + " at=100.5,200.5,0.0 target=109.5,200.0,0.0")));
        } else {
            expect("range-off：零計數", MdcBuildActionGuard.rangeRefusedForTest() == range0);
        }
        long cheat0 = MdcBuildActionGuard.cheatExemptForTest();
        expectBuilt("role 有 UseBuildCheat 的管理員遠距建造（作弊建造跳過走路）", ADMIN_ID, "ISWoodenWall", 9000, 9000, 0);
        expect("管理員豁免計數（range-off 時不走到判斷）",
                MdcBuildActionGuard.cheatExemptForTest() == cheat0 + (wantRange ? 1 : 0));

        // ---- 拒絕後原版 processServer 回 Reject（客戶端不卡）----
        if (wantType) {
            BuildActionPacket p = parse(OWNER_ID, "ISUIElement", 100, 200, 0);
            CaptureConnection connection = captureConnection();
            p.processServer(PacketTypes.PacketType.BuildAction, connection);
            byte[] wire = connection.packets.size() == 1 ? connection.packets.get(0) : new byte[0];
            ByteBufferReader received = new ByteBufferReader(ByteBuffer.wrap(wire));
            boolean ok = wire.length > 3
                    && received.getByte() == (byte) 134
                    && received.getShort() == PacketTypes.PacketType.BuildAction.getId()
                    && received.getByte() == 7
                    && received.getEnum(Transaction.TransactionState.class) == Transaction.TransactionState.Reject
                    && received.getShort() == OWNER_ID;
            expect("拒絕的請求：原版 processServer 恰送 1 包 Reject 給送封包的人", ok);
        }

        // ---- 上下文 ----
        expect("parse 結束後上下文已清（protectedCall 取用即清）", !MdcBuildActionGuard.parseBoundForTest());
        Object fn = LuaManager.getFunctionObject("ISUIElement.new");
        int before = ctorCalls;
        MdcBuildActionGuard.protectedCall(LuaManager.caller, null, fn, new Object[]{LuaManager.get("ISUIElement")});
        expect("沒有 parse 上下文（不是從 BuildAction.parse 來）：照原版呼叫", ctorCalls == before + 1);
        GameServer.server = false;
        int beforeClient = ctorCalls;
        parse(OWNER_ID, "ISUIElement", 9000, 9000, 0);
        expect("非伺服器：照原版呼叫（不檢查）", ctorCalls == beforeClient + 1);
        GameServer.server = true;
        expect("零 anomalies", MdcBuildActionGuard.anomaliesForTest() == 0);

        if (failed > 0) {
            OUT.println("build-action-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("build-action-guard OK mode=" + mode);
    }

    private static void expectBuilt(String what, short who, String type, float x, float y, float z) {
        int before = ctorCalls;
        BuildActionPacket p = parse(who, type, x, y, z);
        expect("照常建造：" + what + "（建構子恰 1 次、item 建立）", ctorCalls == before + 1 && p.item == MADE);
    }

    private static void expectTypeCase(boolean typeCheck, String what, String type) {
        int before = ctorCalls;
        BuildActionPacket p = parse(OWNER_ID, type, 100, 200, 0);
        if (typeCheck) {
            expect("W76 拒絕：" + what + "（不呼叫建構子、item=null）", ctorCalls == before && p.item == null);
        } else {
            expect("type-off：檢查關閉，" + what + " 照原版呼叫建構子", ctorCalls == before + 1 && p.item == MADE);
        }
    }

    private static void expectRangeCase(boolean rangeCheck, String what, short who, float x, float y, float z) {
        int before = ctorCalls;
        BuildActionPacket p = parse(who, "ISWoodenWall", x, y, z);
        if (rangeCheck) {
            expect("W77 拒絕：" + what + "（不呼叫建構子、item=null）", ctorCalls == before && p.item == null);
        } else {
            expect("range-off：檢查關閉，" + what + " 照原版呼叫建構子", ctorCalls == before + 1 && p.item == MADE);
        }
    }

    /** 客戶端 {@code createBuildAction} 送出的 Request：Action 標頭＋座標＋sprite＋Type／Arguments 表。 */
    private static BuildActionPacket parse(short who, String type, float x, float y, float z) {
        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put((byte) 7);                                               // Action.id
        bb.put((byte) Transaction.TransactionState.Request.ordinal());  // Action.state
        bb.putShort(who);                                               // PlayerID.id
        bb.put((byte) -1);                                              // PlayerID.playerIndex
        bb.putFloat(x);
        bb.putFloat(y);
        bb.putFloat(z);
        bb.put((byte) 1);                                               // north
        putUTF(bb, "carpentry_01_24");
        PZNetKahluaTableImpl arguments = new PZNetKahluaTableImpl(new LinkedHashMap<>());
        arguments.rawset("sprite", "carpentry_01_24");
        arguments.rawset("northSprite", "carpentry_01_25");
        PZNetKahluaTableImpl item = new PZNetKahluaTableImpl(new LinkedHashMap<>());
        item.rawset("Type", type);
        item.rawset("Arguments", arguments);
        item.save(bb);
        bb.flip();
        BuildActionPacket p = new BuildActionPacket();
        p.parse(new ByteBufferReader(bb), null);
        return p;
    }

    /** 原版 {@code ISBaseObject:derive} 的形狀：類別表的 metatable 是父類別，{@code Type} 與全域名相同；每個類別有計次的 new。 */
    private static KahluaTable classTable(String name, KahluaTable parent) {
        KahluaTable t = table();
        t.rawset("Type", name);
        t.rawset("new", (JavaFunction) (frame, n) -> 0);
        if (parent != null) {
            t.setMetatable(parent);
        }
        LuaManager.env.rawset(name, t);
        return t;
    }

    private static IsoPlayer player(short id, String name, float x, float y, float z, boolean buildCheat) throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        p.onlineId = id;
        p.username = name;
        p.setX(x);
        p.setY(y);
        p.setZ(z);
        Field ecs = findField(p.getClass(), "ecsComponentMap");
        ecs.setAccessible(true);
        ecs.set(p, new HashMap<>());
        if (buildCheat) {
            Role role = new Role("mdc-test-admin");
            role.addCapability(Capability.UseBuildCheat);
            p.role = role;
        }
        GameServer.IDToPlayerMap.put(id, p);
        return p;
    }

    /** {@code <Type>.new} 的替身：計次並回固定 table（原版 protectedCall 成功＝LuaSuccess）。 */
    private static final class CountingCaller extends LuaCaller {
        CountingCaller() {
            super(new KahluaConverterManager());
        }

        @Override
        public LuaReturn protectedCall(KahluaThread thread, Object fn, Object... args) {
            ctorCalls++;
            return LuaReturn.createReturn(new Object[]{Boolean.TRUE, MADE});
        }
    }

    /** 只替換 RakNet 送出端：封包內容仍由原版 processServer／PacketType 寫出。 */
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

    private static CaptureConnection captureConnection() throws Exception {
        CaptureConnection c = alloc(CaptureConnection.class);
        c.buffer = ByteBuffer.allocate(4096);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        return c;
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    private static List<String> rows(ByteArrayOutputStream log) {
        List<String> out = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static KahluaTable table() {
        return new KahluaTableImpl(new LinkedHashMap<>());
    }

    private static void putUTF(ByteBuffer bb, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        bb.putShort((short) raw.length);
        bb.put(raw);
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
        OUT.println((ok ? "bag pass  " : "bag FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private MdcBuildActionGuardTest() {}
}
