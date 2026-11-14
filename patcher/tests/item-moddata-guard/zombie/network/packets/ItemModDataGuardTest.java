package zombie.network.packets;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.network.GameServer;
import zombie.network.fields.ContainerID;

/**
 * W102 物品資料同步驗證（MdcItemModDataGuard）行為驗證。封包的容器與 modData 直接設好，走 classpath 上的真
 * {@code processServer}（dist 內手術後的，或 vanilla 模式的 jar 原版）與它呼叫的真 {@code processModData}；轉發內容看封包的
 * {@code moddata}（原版轉發的 {@code write} 序列化的就是它）。物品是只蓋掉無關欄位 setter 的替身，modData 走原版實作。
 * argv：{@code on}（出貨組態）／{@code keys}（{@code -Dmdc.itemModDataKeys=Alpha, Beta}）／{@code off}（kill switch）／
 * {@code vanilla}（classpath 不含 dist，原版負對照），自驗。
 */
public final class ItemModDataGuardTest {

    private static final String TAG = "[MinidoracatJavaPatch][ItemModDataGuard]";
    private static final String GUARD = "zombie.network.packets.MdcItemModDataGuard";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    private static int failed;
    private static UdpConnection conn;
    /** logthrow：日誌串流改拋 OutOfMemoryError（證明 Error 不被吞）。 */
    private static boolean oom;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "on";
        boolean guarded = "on".equals(mode) || "keys".equals(mode);
        String key = "keys".equals(mode) ? "Alpha" : "MinidoracatEconomy";
        String packetFrom = SyncItemFieldsPacket.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if ("vanilla".equals(mode)) {
            boolean guardAbsent;
            try {
                Class.forName(GUARD);
                guardAbsent = false;
            } catch (ClassNotFoundException e) {
                guardAbsent = true;
            }
            expect("自驗：vanilla＝封包類別來自 jar（" + packetFrom + "）、classpath 沒有 helper", guardAbsent && packetFrom.endsWith(".jar"));
        } else {
            String want = "on".equals(mode) || "logthrow".equals(mode) ? "[MinidoracatEconomy]" : "keys".equals(mode) ? "[Alpha, Beta]" : "[]";
            expect("自驗：argv=" + mode + " 與實際保留鍵 " + keys() + " 相符、封包類別來自 dist（" + packetFrom + "）",
                    keys().equals(want) && !packetFrom.endsWith(".jar"));
        }
        GameServer.server = true;
        conn = conn(player((short) 7, "tester"));
        if ("logthrow".equals(mode)) {
            logThrow(key);
            return;
        }
        ByteArrayOutputStream log = captureLog();
        int refusedCases = 0;

        // 保留鍵被改寫、其他鍵也改、另加一個鍵：保留鍵留伺服器的值（同一個表物件），其他照封包。
        KahluaTable stamp = table("mark", "server");
        KahluaTable server = table(key, stamp, "other", 1.0);
        KahluaTable client = table(key, table("mark", "client", "durable", true), "other", 2.0, "extra", "x");
        String clientDump = dump(client);
        Run r = run(server, client);
        expectBoth("保留鍵被改寫", r, guarded ? dump(table(key, table("mark", "server"), "other", 2.0, "extra", "x")) : clientDump);
        expect("保留鍵被改寫：伺服器那份保留鍵仍是原本的表物件＝" + guarded, (r.item.getModData().rawget(key) == stamp) == guarded);
        refusedCases++;

        // 封包沒帶保留鍵：原版 wipe 掉，修補後留著。
        r = run(table(key, table("mark", "server"), "other", 1.0), table("other", 3.0));
        expectBoth("封包沒帶保留鍵", r, guarded ? dump(table(key, table("mark", "server"), "other", 3.0)) : dump(table("other", 3.0)));
        refusedCases++;

        // 伺服器沒有保留鍵、封包憑空帶一個：不採用。
        client = table(key, table("mark", "client"), "other", 4.0);
        clientDump = dump(client);
        r = run(table("other", 1.0), client);
        expectBoth("封包憑空帶保留鍵", r, guarded ? dump(table("other", 4.0)) : clientDump);
        refusedCases++;

        // 伺服器那件完全沒有 modData：其他鍵照收，保留鍵不採用。
        client = table(key, table("mark", "client"), "a", 1.0);
        clientDump = dump(client);
        r = run(null, client);
        expectBoth("伺服器沒有 modData", r, guarded ? dump(table("a", 1.0)) : clientDump);
        refusedCases++;

        // 保留鍵內容相同（含巢狀表）：照原版、不記 log。
        int rowsBefore = keptRows(log).size();
        client = table(key, table("mark", "server", "deep", table("n", 1.0)), "other", 5.0);
        clientDump = dump(client);
        r = run(table(key, table("mark", "server", "deep", table("n", 1.0)), "other", 1.0), client);
        expectBoth("保留鍵內容相同", r, clientDump);
        expect("保留鍵內容相同：零新 log", keptRows(log).size() == rowsBefore);

        // 封包沒帶 modData（客戶端那件沒有 modData）：三種模式伺服器那份都不動。
        server = table(key, table("mark", "server"), "other", 1.0);
        String serverDump = dump(server);
        r = run(server, null);
        expect("封包沒帶 modData：伺服器那份不動、封包仍沒有 modData",
                dump(r.item.getModData()).equals(serverDump) && r.packet.moddata == null);
        expect("封包沒帶 modData：零新 log", keptRows(log).size() == rowsBefore);

        // 伺服器那張表自己沒有鍵、保留鍵在 metatable（hasModData() 是 false，rawget 讀得到）：轉發用的封包表要和伺服器讀到的一致。
        KahluaTable hollow = table();
        hollow.setMetatable(table(key, table("mark", "server")));
        r = run(hollow, table(key, table("mark", "client"), "other", 6.0));
        String serverSees = dump(r.item.getModData().rawget(key));
        expect("保留鍵在 metatable：伺服器讀到 " + serverSees + "、轉發用的封包表相同",
                serverSees.equals(guarded ? "{mark=server}" : "{mark=client}") && dump(r.packet.moddata.rawget(key)).equals(serverSees));
        refusedCases++;

        if ("keys".equals(mode)) {
            // 第二個保留鍵（旗標值帶空白）也保留；預設的鍵不在清單裡就照原版被取代。
            r = run(table("Alpha", 1.0, "Beta", "b", "MinidoracatEconomy", table("mark", "server")),
                    table("Alpha", 1.0, "MinidoracatEconomy", table("mark", "client")));
            expectBoth("自訂保留鍵", r, dump(table("Alpha", 1.0, "Beta", "b", "MinidoracatEconomy", table("mark", "client"))));
            refusedCases++;
        }

        if (!"vanilla".equals(mode)) {
            // 判不出物品（容器是 null）：on 把封包的 modData 拿掉並記 anomalies，off 不碰封包。直接呼叫 begin（原版在這種封包會自己 NPE）。
            SyncItemFieldsPacket broken = new SyncItemFieldsPacket();
            broken.moddata = table(key, 1.0);
            Class.forName(GUARD).getMethod("begin", SyncItemFieldsPacket.class, UdpConnection.class).invoke(null, broken, conn);
            expect("判不出物品：封包的 modData " + (guarded ? "被拿掉" : "不動"), (broken.moddata == null) == guarded);
            expect("anomalies 恰 " + (guarded ? 1 : 0) + "、" + (guarded ? "記一行 dropped" : "不記"),
                    (long) accessor("anomaliesForTest") == (guarded ? 1 : 0)
                    && allRows(log).stream().filter(x -> x.contains(" dropped modData reason=NullPointerException ")).count() == (guarded ? 1 : 0));
        }

        List<String> rows = keptRows(log);
        if (guarded) {
            expect("refused 恰 +" + refusedCases + "、每次一行 log（實際 " + rows.size() + "）",
                    (long) accessor("refusedForTest") == refusedCases && rows.size() == refusedCases);
            expect("log：鍵名、物品、連線玩家、計數",
                    rows.get(0).contains(TAG + " kept key=" + key + " item=Base.Stub#42 connectionPlayers=7:tester refused=1 suppressed=0 anomalies=0."));
            String first = TAG + " 首次生效 mode=on keys=" + keys().replaceAll("[\\[\\] ]", "") + " reflect=ok.";
            expect("首次生效行恰 1 行", allRows(log).stream().filter(x -> x.contains(first)).count() == 1);
        } else {
            expect(mode + "：零 kept log", rows.isEmpty());
            if ("off".equals(mode)) {
                expect("off：零計數、首次生效行寫 mode=off",
                        (long) accessor("refusedForTest") == 0
                        && allRows(log).stream().filter(x -> x.contains(TAG + " 首次生效 mode=off keys= reflect=ok.")).count() == 1);
            }
        }

        if (failed > 0) {
            OUT.println("item-moddata-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("item-moddata-guard OK mode=" + mode);
    }

    /** 日誌鏈拋例外：首次生效與 kept 記錄爆掉時保護照做、原版照常跑完、只計 anomalies；改拋 OutOfMemoryError 時照常外拋。 */
    private static void logThrow(String key) throws Exception {
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                explode();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                explode();
            }
        });
        Throwable t = null;
        try {
            DebugLog.log("[ItemModDataGuardTest] self-check");
        } catch (Throwable e) {
            t = e;
        }
        expect("自驗：DebugLog.log 真的拋例外（" + t + "）", t instanceof IllegalStateException);
        t = null;
        Run r = null;
        try {
            r = run(table(key, table("mark", "server"), "other", 1.0), table(key, table("mark", "client"), "other", 2.0));
        } catch (Throwable e) {
            t = e;
        }
        expect("首次生效與 kept 記錄拋例外：真 processServer 照常跑完、不外拋、保留鍵仍是伺服器的值、其他鍵照封包、anomalies 2（" + t + "）",
                t == null && r != null && dump(r.item.getModData()).equals(dump(table(key, table("mark", "server"), "other", 2.0)))
                        && dump(r.packet.moddata).equals(dump(r.item.getModData())) && (long) accessor("anomaliesForTest") == 2);
        oom = true;
        t = null;
        try {
            run(table(key, table("mark", "server")), table(key, table("mark", "client")));
        } catch (Throwable e) {
            t = e;
        }
        expect("Error 照常外拋：日誌鏈拋 OutOfMemoryError 時不被吞，從 processServer 拋出（" + t + "）", t instanceof OutOfMemoryError);
        OUT.println(failed == 0 ? "item-moddata-guard OK mode=logthrow" : "item-moddata-guard FAIL " + failed + " 項");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void explode() {
        if (oom) {
            throw new OutOfMemoryError("<test> logger oom");
        }
        throw new IllegalStateException("<test> logger exploded");
    }

    private record Run(SyncItemFieldsPacket packet, InventoryItem item) {
    }

    /** 伺服器那件物品的 modData＝{@code server}（null＝沒有），封包帶 {@code client}（null＝沒帶），跑真 processServer。 */
    private static Run run(KahluaTable server, KahluaTable client) throws Exception {
        StubItem item = alloc(StubItem.class);
        item.id = 42;
        Field table = findField(InventoryItem.class, "table");
        table.setAccessible(true);
        table.set(item, server);
        ItemContainer container = alloc(ItemContainer.class);
        Field items = findField(ItemContainer.class, "items");
        items.setAccessible(true);
        ArrayList<InventoryItem> list = new ArrayList<>();
        list.add(item);
        items.set(container, list);
        SyncItemFieldsPacket packet = new SyncItemFieldsPacket();
        Field cid = SyncItemFieldsPacket.class.getDeclaredField("containerId");
        cid.setAccessible(true);
        Field c = ContainerID.class.getDeclaredField("container");
        c.setAccessible(true);
        c.set(cid.get(packet), container);
        packet.itemId = 42;
        packet.moddata = client;
        packet.processServer(null, conn);
        return new Run(packet, item);
    }

    private static void expectBoth(String what, Run r, String want) {
        String got = dump(r.item.getModData());
        expect(what + "：伺服器那份 " + got + "（預期 " + want + "）", got.equals(want));
        String relayed = dump(r.packet.moddata);
        expect(what + "：轉發用的封包表與伺服器那份相同（" + relayed + "）", relayed.equals(got));
    }

    /** processServer 會動到的無關欄位全部蓋掉（Unsafe 配置的物品沒有 script）；modData 走原版 InventoryItem。 */
    public static class StubItem extends InventoryItem {
        public StubItem() {
            super(null, null, null, (String) null);
        }

        @Override public void setCondition(int condition) { }
        @Override public boolean hasHeadCondition() { return false; }
        @Override public void setRemoteControlID(int id) { }
        @Override public int getCurrentAmmoCount() { return 0; }
        @Override public void setHaveBeenRepaired(int n) { }
        @Override public void setCustomName(boolean b) { }
        @Override public void setFavorite(boolean b) { }
        @Override public void setAttachedSlot(int slot) { }
        @Override public void setAttachedSlotType(String type) { }
        @Override public void setAttachedToModel(String model) { }
        @Override public int getCondition() { return 0; }
        @Override public int getConditionMax() { return 1; }
        @Override public String getFullType() { return "Base.Stub"; }
    }

    private static KahluaTable table(Object... kv) {
        KahluaTable t = new KahluaTableImpl(new LinkedHashMap<>());
        for (int i = 0; i < kv.length; i += 2) {
            t.rawset(kv[i], kv[i + 1]);
        }
        return t;
    }

    /** 內容的正規化文字（鍵排序、表遞迴）；null 與空表都是 {}。 */
    private static String dump(Object v) {
        if (v == null) {
            return "{}";
        }
        if (!(v instanceof KahluaTable t)) {
            return String.valueOf(v);
        }
        TreeMap<String, String> out = new TreeMap<>();
        KahluaTableIterator it = t.iterator();
        while (it.advance()) {
            out.put(String.valueOf(it.getKey()), dump(it.getValue()));
        }
        return out.toString();
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
    private static String keys() throws Exception {
        Field f = Class.forName(GUARD).getDeclaredField("KEYS");
        f.setAccessible(true);
        return java.util.Arrays.toString((String[]) f.get(null));
    }

    private static Object accessor(String name) throws Exception {
        Method m = Class.forName(GUARD).getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    private static List<String> allRows(ByteArrayOutputStream log) {
        List<String> out = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> keptRows(ByteArrayOutputStream log) {
        List<String> out = allRows(log);
        out.removeIf(x -> !x.contains(" kept key="));
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
        OUT.println((ok ? "imdg pass  " : "imdg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private ItemModDataGuardTest() {}
}
