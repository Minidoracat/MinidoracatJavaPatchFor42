package zombie.network.packets;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.BodyLocationGroup;
import zombie.characters.WornItems.WornItems;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.network.fields.character.Player;
import zombie.scripting.objects.ItemBodyLocation;

/**
 * W103 穿戴同步驗證（MdcWornItemGuard）行為驗證。玩家的穿戴欄（真 {@code WornItems}）、背包與封包清單直接設好，走 classpath 上的真
 * {@code processServer}（dist 內手術後的，或 vanilla 模式的 jar 原版）與它呼叫的真 {@code process}。測試環境沒有物品腳本，原版替
 * 背包沒有的 ID 準備物品時會印「Couldn't find item 種類」：用這一行當原版處理了那一項的證據。
 * argv：{@code observe}（出貨組態）／{@code enforce}／{@code off}（kill switch）／{@code vanilla}（classpath 不含 dist，原版負對照）／
 * {@code logthrow}（enforce，日誌串流一寫就拋例外：照做、Error 照常外拋），自驗。
 */
public final class WornItemGuardTest {

    private static final String TAG = "[MinidoracatJavaPatch][WornItemGuard]";
    private static final String GUARD = "zombie.network.packets.MdcWornItemGuard";
    private static final String VANILLA_MISS = "Couldn't find item ";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    private static int failed;
    private static boolean oom;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "observe";
        String packetFrom = SyncClothingPacket.class.getProtectionDomain().getCodeSource().getLocation().getPath();
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
            int want = "observe".equals(mode) ? 1 : "off".equals(mode) ? 0 : 2;
            expect("自驗：argv=" + mode + " 與實際模式 " + guardInt("MODE") + " 相符、封包類別來自 dist（" + packetFrom + "）",
                    guardInt("MODE") == want && !packetFrom.endsWith(".jar"));
        }
        if ("logthrow".equals(mode)) {
            logThrow();
            return;
        }
        boolean observe = "observe".equals(mode);
        boolean enforce = "enforce".equals(mode);
        ByteArrayOutputStream log = captureLog();

        World w = world();
        w.packet.processServer(null, w.conn);

        // 背包沒有的四項：15、16（已在穿戴欄）、99、98（哪裡都沒有）。原版處理其中三項（16 已穿在同一位置，這一包它不動）。
        List<String> missed = rows(log, VANILLA_MISS);
        List<String> unbacked = rows(log, TAG + " unbacked ");
        expect("原版處理無對應項的次數（Couldn't find item 行）＝" + (enforce ? 0 : 3) + "（實際 " + missed.size() + "）", missed.size() == (enforce ? 0 : 3));
        expect("unbacked 行數＝" + (observe || enforce ? 4 : 0) + "（實際 " + unbacked.size() + "）", unbacked.size() == (observe || enforce ? 4 : 0));
        expect("封包清單 " + (enforce ? "少掉 4 項無對應項、其餘 3 項順序不變" : "7 項不動") + "（實際 " + w.ids() + "）",
                w.ids().equals(enforce ? "[10, 11, 14]" : "[10, 11, 14, 15, 16, 99, 98]"));
        // 背包裡有的照穿（10 從背包穿上、11 已穿不動、14 不是衣物從背包重設）；enforce 下 15、16 不在清單，原版把它們從穿戴欄卸下。
        expect("穿戴欄＝" + (enforce ? "[10, 11, 14]" : "[10, 11, 14, 15, 16]") + "（實際 " + w.worn() + "）",
                w.worn().equals(enforce ? "[10, 11, 14]" : "[10, 11, 14, 15, 16]"));
        expect("背包裡的眼鏡穿在封包指定的位置", w.player.getWornItems().getItem(ItemBodyLocation.EYES) == w.glasses);

        if (observe) {
            expect("observe：原版處理的每一項都有記到 " + types(missed, true) + " ⊆ " + types(unbacked, false),
                    types(unbacked, false).containsAll(types(missed, true)) && types(unbacked, false).size() == 4);
        }
        if (observe || enforce) {
            String action = enforce ? "removed" : "logged";
            expect("log：種類、ID、位置、是否已穿、動作、連線玩家、計數（" + unbacked.get(0) + "）",
                    unbacked.get(0).contains(TAG + " unbacked type=Base.GhostA id=15 loc=") && unbacked.get(0).contains(" worn=1 action=" + action
                            + " connectionPlayers=7:tester unbacked=1 suppressed=0 anomalies=0."));
            expect("哪裡都沒有的那一項記 worn=0", unbacked.get(2).contains(" id=99 ") && unbacked.get(2).contains(" worn=0 "));
            expect("客戶端給的種類字串清過：空白與等號變成 ?、整行不含原字串",
                    unbacked.get(3).contains(" type=Evil?type?x?id?1 id=98 ") && !unbacked.get(3).contains("Evil type"));
            Method clean = Class.forName(GUARD).getDeclaredMethod("clean", String.class);
            clean.setAccessible(true);
            String cleaned = (String) clean.invoke(null, "A\nB\tC\u0000" + "x".repeat(100));
            expect("種類字串的換行與控制字元清掉、截成 64 字元（" + cleaned.length() + "）",
                    cleaned.length() == 64 && cleaned.startsWith("A?B?C?x") && cleaned.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f));
            expect("unbacked 計數 4、anomalies 0", guardLong("unbackedForTest") == 4 && guardLong("anomaliesForTest") == 0);
            expect("首次生效行恰 1 行", rows(log, TAG + " 首次生效 mode=" + mode + " reflect=ok.").size() == 1);
        } else if ("off".equals(mode)) {
            expect("off：零計數、首次生效行寫 mode=off",
                    guardLong("unbackedForTest") == 0 && rows(log, TAG + " 首次生效 mode=off reflect=ok.").size() == 1);
        }

        // 同一包前面的項目把後面那一項原本穿著的東西擠掉：背包裡的 10 改穿到 16 的位置，原版輪到 16 時它已經不在穿戴欄。
        int missedBefore = missed.size();
        int unbackedBefore = unbacked.size();
        World shifted = world();
        shifted.items.get(0).location = ItemBodyLocation.BANDAGE;
        shifted.packet.processServer(null, shifted.conn);
        List<String> missedShift = rows(log, VANILLA_MISS).subList(missedBefore, rows(log, VANILLA_MISS).size());
        List<String> unbackedShift = rows(log, TAG + " unbacked ").subList(unbackedBefore, rows(log, TAG + " unbacked ").size());
        expect("擠掉：原版處理 " + (enforce ? "0 項" : "4 項，含被擠掉的 GhostB") + "（實際 " + types(missedShift, true) + "）",
                enforce ? missedShift.isEmpty() : missedShift.size() == 4 && types(missedShift, true).contains("Base.GhostB"));
        if (observe || enforce) {
            expect("擠掉：被擠掉的那一項有記到" + (enforce ? "、清單只剩背包裡有的（" + shifted.ids() + "）" : ""),
                    types(unbackedShift, false).contains("Base.GhostB") && (!enforce || shifted.ids().equals("[10, 11, 14]")));

            // 清單尾端有壞項目（null）：前面已經找到四項無對應，判斷仍然整包作廢——不外拋、清單一項都不動、記 anomaly
            World broken = world();
            broken.items.add(null);
            List<Object> before = new ArrayList<>(broken.items);
            Class.forName(GUARD).getMethod("begin", SyncClothingPacket.class, UdpConnection.class).invoke(null, broken.packet, broken.conn);
            expect("判斷途中出例外：不外拋、清單項目與順序都不動、anomalies 1、記一行 anomaly、unbacked 計數不變",
                    before.equals(new ArrayList<Object>(broken.items)) && guardLong("anomaliesForTest") == 1
                            && rows(log, TAG + " anomaly reason=NullPointerException ").size() == 1
                            && guardLong("unbackedForTest") == 8);
        }

        if (failed > 0) {
            OUT.println("worn-item-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("worn-item-guard OK mode=" + mode);
    }

    /** 日誌鏈拋例外（enforce）：首次生效與 unbacked 記錄爆掉時照樣拿掉無對應項、不外拋、只計 anomalies；改拋 OutOfMemoryError 時照常外拋。 */
    private static void logThrow() throws Exception {
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
            DebugLog.log("[WornItemGuardTest] self-check");
        } catch (Throwable e) {
            t = e;
        }
        expect("自驗：DebugLog.log 真的拋例外（" + t + "）", t instanceof IllegalStateException);
        t = null;
        World w = world();
        try {
            w.packet.processServer(null, w.conn);
        } catch (Throwable e) {
            t = e;
        }
        expect("首次生效與 unbacked 記錄拋例外：真 processServer 照常跑完、不外拋、無對應項已拿掉（" + w.ids() + "）、anomalies 5（" + t + "）",
                t == null && w.ids().equals("[10, 11, 14]") && guardLong("anomaliesForTest") == 5);
        oom = true;
        t = null;
        World w2 = world();
        try {
            w2.packet.processServer(null, w2.conn);
        } catch (Throwable e) {
            t = e;
        }
        expect("Error 照常外拋：日誌鏈拋 OutOfMemoryError 時不被吞，從 processServer 拋出（" + t + "）", t instanceof OutOfMemoryError);
        OUT.println(failed == 0 ? "worn-item-guard OK mode=logthrow" : "worn-item-guard FAIL " + failed + " 項");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void explode() {
        if (oom) {
            throw new OutOfMemoryError("<test> logger oom");
        }
        throw new IllegalStateException("<test> logger exploded");
    }

    private static final class World {
        IsoPlayer player;
        UdpConnection conn;
        SyncClothingPacket packet;
        ArrayList<SyncClothingPacket.ItemDescription> items;
        InventoryItem glasses;

        String ids() {
            List<Integer> out = new ArrayList<>();
            for (SyncClothingPacket.ItemDescription d : items) {
                out.add(d.itemId);
            }
            return out.toString();
        }

        String worn() {
            TreeSet<Integer> out = new TreeSet<>();
            player.getWornItems().forEach(x -> out.add(x.getItem().getID()));
            return out.toString();
        }
    }

    /**
     * 背包：眼鏡 10（衣物）、帽子 11（衣物）、背包 14（不是衣物）。穿戴欄：帽子 11、背包 14、不是衣物的 15（背包沒有）、
     * 衣物 16（背包沒有，已穿在它自己的位置）。封包依序列 10、11、14、15、16、99、98。
     */
    @SuppressWarnings("unchecked")
    private static World world() throws Exception {
        World w = new World();
        BodyLocationGroup group = new BodyLocationGroup("Test");
        ItemBodyLocation[] used = {ItemBodyLocation.EYES, ItemBodyLocation.BACK, ItemBodyLocation.BELT, ItemBodyLocation.EARS,
                ItemBodyLocation.BANDAGE, ItemBodyLocation.DRESS, ItemBodyLocation.CUIRASS};
        for (ItemBodyLocation loc : used) {
            group.getOrCreateLocation(loc);
        }
        w.player = alloc(IsoPlayer.class);
        w.player.onlineId = 7;
        w.player.username = "tester";
        set(w.player, "ecsComponentMap", new HashMap<>());
        WornItems wornItems = new WornItems(group);
        set(w.player, "wornItems", wornItems);
        ItemContainer bag = alloc(ItemContainer.class);
        ArrayList<InventoryItem> bagItems = new ArrayList<>();
        set(bag, "items", bagItems);
        set(w.player, "inventory", bag);

        w.glasses = clothing(10, ItemBodyLocation.EYES);
        InventoryItem hat = clothing(11, ItemBodyLocation.EARS);
        InventoryItem backpack = plain(14);
        bagItems.add(w.glasses);
        bagItems.add(hat);
        bagItems.add(backpack);
        wornItems.setItem(ItemBodyLocation.EARS, hat);
        wornItems.setItem(ItemBodyLocation.BACK, backpack);
        wornItems.setItem(ItemBodyLocation.BELT, plain(15));
        wornItems.setItem(ItemBodyLocation.BANDAGE, clothing(16, ItemBodyLocation.BANDAGE));

        w.conn = alloc(UdpConnection.class);
        w.conn.players = new IsoPlayer[4];
        w.conn.players[0] = w.player;
        w.packet = new SyncClothingPacket();
        ((Player) get(w.packet, "player")).setPlayer(w.player);
        w.items = (ArrayList<SyncClothingPacket.ItemDescription>) get(w.packet, "items");
        w.items.add(desc(10, "Base.Glasses", ItemBodyLocation.EYES));
        w.items.add(desc(11, "Base.Hat", ItemBodyLocation.EARS));
        w.items.add(desc(14, "Base.Bag", ItemBodyLocation.BACK));
        w.items.add(desc(15, "Base.GhostA", ItemBodyLocation.BELT));
        w.items.add(desc(16, "Base.GhostB", ItemBodyLocation.BANDAGE));
        w.items.add(desc(99, "Base.GhostC", ItemBodyLocation.DRESS));
        w.items.add(desc(98, "Evil type=x id=1", ItemBodyLocation.CUIRASS));
        return w;
    }

    private static SyncClothingPacket.ItemDescription desc(int id, String type, ItemBodyLocation loc) {
        SyncClothingPacket.ItemDescription d = new SyncClothingPacket.ItemDescription();
        d.itemId = id;
        d.itemType = type;
        d.location = loc;
        return d;
    }

    /** Unsafe 配置的衣物沒有 script：位置由測試給。 */
    public static class StubClothing extends Clothing {
        public ItemBodyLocation loc;

        public StubClothing() {
            super(null, null, null, (String) null, null, null);
        }

        @Override public ItemBodyLocation getBodyLocation() { return loc; }
    }

    public static class StubItem extends InventoryItem {
        public StubItem() {
            super(null, null, null, (String) null);
        }
    }

    private static InventoryItem clothing(int id, ItemBodyLocation loc) throws Exception {
        StubClothing c = alloc(StubClothing.class);
        c.id = id;
        c.loc = loc;
        return c;
    }

    private static InventoryItem plain(int id) throws Exception {
        StubItem item = alloc(StubItem.class);
        item.id = id;
        return item;
    }

    private static int guardInt(String name) throws Exception {
        Field f = Class.forName(GUARD).getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static long guardLong(String name) throws Exception {
        Method m = Class.forName(GUARD).getDeclaredMethod(name);
        m.setAccessible(true);
        return (long) m.invoke(null);
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    /** log 行裡的物品種類集合：原版的「Couldn't find item 種類」行（照 helper 的規則把空白與等號換成 ?），或 unbacked 行的 type=。 */
    private static TreeSet<String> types(List<String> rows, boolean vanilla) {
        TreeSet<String> out = new TreeSet<>();
        for (String row : rows) {
            out.add(vanilla ? row.substring(row.indexOf(VANILLA_MISS) + VANILLA_MISS.length()).trim().replace(' ', '?').replace('=', '?')
                    : row.replaceAll(".* unbacked type=(\\S+) .*", "$1"));
        }
        return out;
    }

    private static List<String> rows(ByteArrayOutputStream log, String needle) {
        List<String> out = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(needle)) {
                out.add(line);
            }
        }
        return out;
    }

    private static Object get(Object target, String name) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
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
        OUT.println((ok ? "wig pass  " : "wig FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private WornItemGuardTest() {}
}
