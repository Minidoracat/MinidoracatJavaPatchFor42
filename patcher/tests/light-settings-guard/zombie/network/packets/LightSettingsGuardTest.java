package zombie.network.packets;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoLightSwitch;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.fields.NetObject;

/**
 * W82 燈具設定同步驗證（MdcLightSettingsGuard）行為驗證。客戶端那份燈用真 {@code SyncCustomLightSettingsPacket.write}
 * 產生 wire，伺服器那份燈走 classpath 上的真 {@code parse}（dist 內手術後的，或 vanilla 模式的 jar 原版），轉發內容用
 * 同一個封包的真 {@code write}（原版 {@code sendToClients} 對每條連線送的就是它），{@code processClient} 讀的
 * {@code activated} 直接看封包欄位。只有找燈的 {@code NetObject} 換成直接回傳那盞燈的替身。
 * argv：{@code on}（出貨組態）／{@code off}（kill switch）／{@code vanilla}（classpath 不含 dist，原版負對照），自驗。
 */
public final class LightSettingsGuardTest {

    private static final String TAG = "[MinidoracatJavaPatch][LightSettingsGuard]";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream（擷取 log 時一起被收走）：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    private static int failed;
    private static UdpConnection conn;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "on";
        boolean on = "on".equals(mode);
        String packetFrom = SyncCustomLightSettingsPacket.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if ("vanilla".equals(mode)) {
            boolean guardAbsent;
            try {
                Class.forName("zombie.network.packets.MdcLightSettingsGuard");
                guardAbsent = false;
            } catch (ClassNotFoundException e) {
                guardAbsent = true;
            }
            expect("自驗：vanilla＝封包類別來自 jar（" + packetFrom + "）、classpath 沒有 helper", guardAbsent && packetFrom.endsWith(".jar"));
        } else {
            expect("自驗：argv=" + mode + " 與實際旗標相符、封包類別來自 dist（" + packetFrom + "）",
                    flag() == on && !packetFrom.endsWith(".jar"));
        }
        GameServer.server = true;
        conn = conn(player((short) 7, "tester"));
        ByteArrayOutputStream log = captureLog();

        // ---- 原版合法來源：打掉燈泡（客戶端的 activated 維持原值）、關燈，三種模式都照原版 ----
        IsoLightSwitch s = light(true, true, true, true, "Base.LightBulb", 0.5F, 0.002F, 1.0F, 0.9F, 0.8F);
        IsoLightSwitch c = light(true, true, true, true, null, 0.5F, 0.002F, 1.0F, 0.9F, 0.8F);
        expectApplied("打掉燈泡：燈泡變 null，其餘不變", sync(c, s), s, state(c), true);

        s = light(true, true, false, false, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        c = light(false, true, false, false, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        expectApplied("關燈：activated true→false", sync(c, s), s, state(c), false);

        // 客戶端電量比伺服器落後一點（伺服器扣電後才同步）：on 留伺服器值且不記 log，off／vanilla 照收。
        s = light(true, true, true, true, "Base.LightBulb", 0.5F, 0.002F, 1.0F, 0.9F, 0.8F);
        c = light(true, true, true, true, null, 0.505F, 0.002F, 1.0F, 0.9F, 0.8F);
        String keptPower = state(light(true, true, true, true, null, 0.5F, 0.002F, 1.0F, 0.9F, 0.8F));
        expectApplied("打掉燈泡時客戶端電量落後 0.005", sync(c, s), s, on ? keptPower : state(c), true);

        // 旁觀者重播同一發子彈的曳光、晚一步送的打燈封包：伺服器燈已關、沒燈泡，封包仍是開的。on 不重新開燈、不記 log。
        s = light(false, true, true, true, null, 0.5F, 0.002F, 1.0F, 0.9F, 0.8F);
        String before = state(s);
        c = light(true, true, true, true, null, 0.5F, 0.002F, 1.0F, 0.9F, 0.8F);
        expectApplied("旁觀者晚到的打燈封包", sync(c, s), s, on ? before : state(c), !on);

        // 上一包在 setPrimaryB 之前讀壞（截掉五個 float），下一包合法打燈不得記上一包的被擋欄位。
        int rowsBefore = rows(log).size();
        try {
            syncCut(light(true, true, true, true, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F),
                    light(false, false, false, false, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F), 20);
            expect("截斷的封包要讀壞", false);
        } catch (java.nio.BufferUnderflowException expected) {
            // parse 中途丟出，setPrimaryB 沒跑
        }
        s = light(true, true, false, false, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        c = light(true, true, false, false, null, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        expectApplied("讀壞的封包之後合法打燈", sync(c, s), s, state(c), true);
        expect("讀壞的封包之後零新 log", rows(log).size() == rowsBefore);

        // ---- 非向下的改動：on 伺服器狀態完全不變；off 與 vanilla 照收（負對照）----
        int refusedCases = 0;
        s = light(false, false, false, false, null, 0.0F, 0.0F, 0.2F, 0.2F, 0.2F);
        before = state(s);
        c = light(true, true, true, true, "Base.LightBulbRed", 1.0F, 0.0001F, 1.0F, 0.0F, 0.0F);
        expectApplied("每個欄位都改（開燈、可改裝、電池、裝燈泡、電量、耗電、顏色）", sync(c, s), s, on ? before : state(c), !on);
        refusedCases++;

        s = light(true, true, false, false, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        before = state(s);
        c = light(true, true, false, false, "Base.LightBulbRed", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        expectApplied("燈泡換成另一種", sync(c, s), s, on ? before : state(c), true);
        refusedCases++;

        s = light(false, true, false, false, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        before = state(s);
        c = light(true, true, false, false, "Base.LightBulb", 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        expectApplied("只有開燈（false→true）", sync(c, s), s, on ? before : state(c), !on);
        refusedCases++;

        List<String> rows = rows(log);
        if (on) {
            expect("W82 計數恰 +" + refusedCases + "（每包一次）、零 anomalies",
                    (long) accessor("refusedForTest") == refusedCases && (long) accessor("anomaliesForTest") == 0);
            expect("W82 log：每個被擋的封包一行，合法來源與電量落後不記", rows.size() == refusedCases);
            expect("W82 log：列出被擋欄位、連線玩家與位置",
                    rows.stream().anyMatch(r -> r.contains(TAG + " refused fields=activated,canBeModified,useBattery,hasBattery,"
                            + "bulbItem,power,delta,primaryR,primaryG,primaryB connectionPlayers=7:tester at=10.0,20.0,0.0 refused=1 "))
                    && rows.stream().anyMatch(r -> r.contains(" refused fields=bulbItem connectionPlayers="))
                    && rows.stream().anyMatch(r -> r.contains(" refused fields=activated connectionPlayers=")));
        } else {
            expect(mode + "：零 log", rows.isEmpty());
            if ("off".equals(mode)) {
                expect("off：零計數", (long) accessor("refusedForTest") == 0);
            }
        }

        if (failed > 0) {
            OUT.println("light-settings-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("light-settings-guard OK mode=" + mode);
    }

    /** 客戶端那盞燈 {@code c} 的設定經真 write → 真 parse 套到伺服器那盞 {@code s}；回伺服器封包（轉發與 processClient 用它）。 */
    private static SyncCustomLightSettingsPacket sync(IsoLightSwitch c, IsoLightSwitch s) {
        return syncCut(c, s, 0);
    }

    /** 同 {@link #sync}，wire 尾端截掉 {@code cut} bytes。 */
    private static SyncCustomLightSettingsPacket syncCut(IsoLightSwitch c, IsoLightSwitch s, int cut) {
        SyncCustomLightSettingsPacket out = new SyncCustomLightSettingsPacket();
        out.netObject = new FixedObject(null);
        out.setData(c);
        ByteBuffer bb = ByteBuffer.allocate(256);
        out.write(new ByteBufferWriter(bb));
        bb.flip();
        bb.limit(bb.limit() - cut);
        SyncCustomLightSettingsPacket in = new SyncCustomLightSettingsPacket();
        in.netObject = new FixedObject(s);
        in.parse(new ByteBufferReader(bb), conn);
        expect("wire 全部讀完", !bb.hasRemaining());
        return in;
    }

    private static void expectApplied(String what, SyncCustomLightSettingsPacket in, IsoLightSwitch s, String want, boolean wantActivated) {
        String got = state(s);
        expect(what + "：伺服器狀態 " + got + "（預期 " + want + "）", got.equals(want));
        String relayed = relayed(in);
        expect(what + "：轉發內容與伺服器狀態相同（" + relayed + "）", relayed.equals(got));
        expect(what + "：processClient 的 switchLight 參數＝" + wantActivated, in.activated == wantActivated);
    }

    /** 原版 sendToClients 對每條連線呼叫的 write（伺服器那份）；格式同 {@link #state}。 */
    private static String relayed(SyncCustomLightSettingsPacket in) {
        ByteBuffer bb = ByteBuffer.allocate(256);
        in.write(new ByteBufferWriter(bb));
        bb.flip();
        ByteBufferReader r = new ByteBufferReader(bb);
        boolean act = r.getBoolean();
        boolean mod = r.getBoolean();
        boolean ub = r.getBoolean();
        boolean hb = r.getBoolean();
        String bulb = r.getBoolean() ? r.getUTF() : null;
        return format(act, mod, ub, hb, bulb, r.getFloat(), r.getFloat(), r.getFloat(), r.getFloat(), r.getFloat());
    }

    private static String state(IsoLightSwitch l) {
        return format(l.isActivated(), l.getCanBeModified(), l.getUseBattery(), l.getHasBattery(), l.getBulbItem(),
                l.getPower(), l.getDelta(), l.getPrimaryR(), l.getPrimaryG(), l.getPrimaryB());
    }

    private static String format(boolean act, boolean mod, boolean ub, boolean hb, String bulb,
                                 float p, float d, float r, float g, float b) {
        return "act=" + act + " mod=" + mod + " ub=" + ub + " hb=" + hb + " bulb=" + bulb
                + " p=" + p + " d=" + d + " rgb=" + r + "," + g + "," + b;
    }

    private static IsoLightSwitch light(boolean act, boolean mod, boolean ub, boolean hb, String bulb,
                                        float p, float d, float r, float g, float b) throws Exception {
        IsoLightSwitch l = alloc(IsoLightSwitch.class);
        Field lights = IsoLightSwitch.class.getDeclaredField("lights");
        lights.setAccessible(true);
        lights.set(l, new ArrayList<>());
        IsoGridSquare sq = alloc(IsoGridSquare.class);
        sq.x = 10;
        sq.y = 20;
        sq.z = 0;
        l.square = sq;
        l.setActivated(act);
        l.setCanBeModified(mod);
        l.setUseBatteryDirect(ub);
        l.setHasBattery(hb);
        l.setBulbItemRaw(bulb);
        l.setPower(p);
        l.setDelta(d);
        l.setPrimaryR(r);
        l.setPrimaryG(g);
        l.setPrimaryB(b);
        return l;
    }

    /** 找燈的 NetObject 替身：wire 不帶物件身分（客戶端那份 write 不寫、伺服器那份 parse 不讀），直接回傳指定的燈。 */
    private static final class FixedObject extends NetObject {
        private IsoObject object;

        FixedObject(IsoObject object) {
            this.object = object;
        }

        @Override
        public NetObject setObject(IsoObject o) {
            object = o;
            return this;
        }

        @Override
        public IsoObject getObject() {
            return object;
        }

        @Override
        public void parse(ByteBufferReader b, IConnection connection) {
        }

        @Override
        public void write(ByteBufferWriter b) {
        }
    }

    private static IsoPlayer player(short id, String name) throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        p.onlineId = id;
        p.username = name;
        Field ecs = findField(p.getClass(), "ecsComponentMap");
        ecs.setAccessible(true);
        ecs.set(p, new HashMap<>());
        return p;
    }

    private static UdpConnection conn(IsoPlayer... players) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        c.players = new IsoPlayer[4];
        System.arraycopy(players, 0, c.players, 0, players.length);
        return c;
    }

    // helper 的開關與計數是 package-private static；vanilla 模式不碰它們。
    private static boolean flag() throws Exception {
        Field f = Class.forName("zombie.network.packets.MdcLightSettingsGuard").getDeclaredField("ENABLED");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static Object accessor(String name) throws Exception {
        Method m = Class.forName("zombie.network.packets.MdcLightSettingsGuard").getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
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
        OUT.println((ok ? "lsg pass  " : "lsg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private LightSettingsGuardTest() {}
}
