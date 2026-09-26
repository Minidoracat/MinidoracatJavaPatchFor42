package zombie.entity.components.crafting;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import zombie.characters.IsoPlayer;
import zombie.characters.PlayerCheats;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.core.utils.UpdateLimit;
import zombie.entity.Component;
import zombie.entity.ComponentType;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityNetwork;
import zombie.entity.components.crafting.recipe.CraftRecipeData;
import zombie.entity.network.EntityPacketData;
import zombie.entity.network.EntityPacketType;
import zombie.inventory.types.Food;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.Vector3;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.scripting.entity.components.crafting.CraftRecipe;
import zombie.util.list.PZArrayList;
import zombie.vehicles.BaseVehicle;

/**
 * W36 行為驗證：GameEntity 廣播收件範圍（dist 內手術後的真 {@code GameEntityNetwork.sendPacketData}）
 * 與 CraftLogic 同步變化閘（dist 內手術後的真 {@code CraftLogic.onUpdate}）。argv：
 * {@code enforce}（預設出貨）／{@code observe}／{@code off}；兩把 MODE 都是 static final，
 * 各跑獨立 JVM 並自驗 argv 與實際 MODE 相符。
 *
 * <p>連線只覆寫 buffer 端點與 getPacket，封包內容由真 {@link GameEntityNetwork#write} 寫出。
 * 遊戲物件以 Unsafe 配置不跑建構子（理由同 HutchSyncGateTest），欄位由測試補齊。
 */
public final class GameEntityTrafficTest {

    private static int failed;
    private static int relevancyMode;
    private static List<UdpConnection> connections;
    private static IsoGridSquare playerSquare;

    public static void main(String[] args) throws Exception {
        RandStandard.INSTANCE.init();
        GameServer.server = true;

        String want = args.length > 0 ? args[0] : "enforce";
        int wantMode = switch (want) {
            case "off" -> MdcCraftSyncGate.OFF;
            case "observe" -> MdcCraftSyncGate.OBSERVE;
            default -> MdcCraftSyncGate.ENFORCE;
        };
        relevancyMode = staticInt(Class.forName("zombie.mdc.GameEntityBroadcastGate"), "MODE");
        expect("自驗：argv=" + want + " 與兩把 MODE 相符（relevancy=" + relevancyMode
                + " craft=" + MdcCraftSyncGate.MODE + "）",
                relevancyMode == wantMode && MdcCraftSyncGate.MODE == wantMode);

        UdpEngine engine = alloc(UdpEngine.class);
        connections = new ArrayList<>();
        setDeclared(UdpEngine.class, engine, "connections", connections);
        GameServer.udpEngine = engine;
        playerSquare = alloc(IsoGridSquare.class);

        testBroadcastRecipients();
        testUnpositionedEntityStaysGlobal();
        testCraftSyncCadence();
        testExplicitSyncResetsBaseline();
        testSignatureInputs();
        testDryingWetness();
        testSignatureFailureSends();
        testObserveCounters();

        if (failed > 0) {
            System.out.println("game-entity-traffic FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("game-entity-traffic OK  mode=" + want);
    }

    // ------------------------------------------------------------ 廣播收件範圍

    /**
     * 一次真 sendPacketData：窗內照收、窗外（僅 enforce）略過；載具與不可信寬度保守照收；
     * 原版的排除連線與未 fully-connected 條件維持。送出的 wire 與原版 sendToAll 逐位元相同。
     */
    private static void testBroadcastRecipients() throws Exception {
        IsoObject rack = isoObject(100, 200);
        Capture near = conn(15);
        place(near, 0, 110.0f, 190.0f);
        Capture far = conn(15);
        place(far, 0, 9000.0f, 9000.0f);
        Capture driving = conn(15);
        IsoPlayer driver = place(driving, 0, 9000.0f, 9000.0f);
        setAny(driver, "vehicle", alloc(BaseVehicle.class));
        Capture untrusted = conn(12);
        place(untrusted, 0, 9000.0f, 9000.0f);
        Capture sender = conn(15);
        place(sender, 0, 100.0f, 200.0f);
        setDeclared(UdpConnection.class, sender, "connectedGuid", 77L);
        Capture loading = conn(15);
        place(loading, 0, 100.0f, 200.0f);
        setDeclared(UdpConnection.class, loading, "fullyConnected", Boolean.FALSE);

        byte[] payload = {1, 2, 3, 4, 5, 6, 7};
        arm(near, far, driving, untrusted, sender, loading);
        GameEntityNetwork.sendPacketData(data(payload), rack, null, sender, true);

        boolean filters = relevancyMode == 1;
        expect("窗內玩家：收到 1 包", near.packets.size() == 1);
        expect("窗外玩家：" + (filters ? "enforce 略過" : "照收"), far.packets.size() == (filters ? 0 : 1));
        expect("載具內玩家（位置預測不可信）：照收", driving.packets.size() == 1);
        expect("不可信 chunkGridWidth=12：照收", untrusted.packets.size() == 1);
        expect("原版排除連線（發送者）：不收", sender.packets.isEmpty());
        expect("未 fully-connected：不收（原版條件）", loading.packets.isEmpty());

        Capture probe = conn(15);
        place(probe, 0, 100.0f, 200.0f);
        arm(probe);
        INetworkPacket.sendToAll(PacketTypes.PacketType.GameEntity, null,
                new Object[] {data(payload), rack, null});
        expect("原版對照：sendToAll 送出 1 包", probe.packets.size() == 1);
        if (near.packets.size() == 1 && probe.packets.size() == 1) {
            expect("送出的 wire 與原版逐位元相同", Arrays.equals(near.packets.get(0), probe.packets.get(0)));
        }
    }

    /** 未裝備的 InventoryItem 原版 getX 為 Float.MAX_VALUE，client 以擁有者背包查找：維持全服。 */
    private static void testUnpositionedEntityStaysGlobal() throws Exception {
        Food item = alloc(Food.class);
        Capture far = conn(15);
        place(far, 0, 9000.0f, 9000.0f);
        arm(far);
        GameEntityNetwork.sendPacketData(data(new byte[] {9}), item, null, null, true);
        expect("InventoryItem：遠方連線仍收到（位置不可信即不過濾）", far.packets.size() == 1);
    }

    // ------------------------------------------------------------ CraftLogic 同步變化閘

    /** 經真 onUpdate：整數百分比不變就不送（enforce），變了照送；observe／off 每次都送。 */
    private static void testCraftSyncCadence() throws Exception {
        boolean gates = MdcCraftSyncGate.MODE == MdcCraftSyncGate.ENFORCE;
        CountingLogic logic = countingLogic(isoObject(1, 1));
        CraftRecipeData craft = craft(recipe(86400));
        logic.getAllInProgressCraftData().add(craft);

        tick(logic, craft);
        expect("首次同步：送", logic.sends == 1);
        craft.setElapsedTime(100.0);
        tick(logic, craft);
        expect("0.1%（整數仍 0）：" + (gates ? "不送" : "照送"), logic.sends == (gates ? 1 : 2));
        craft.setElapsedTime(900.0);
        tick(logic, craft);
        expect("跨到 1%：送", logic.sends == (gates ? 2 : 3));
        tick(logic, craft);
        expect("同 1% 再一次：" + (gates ? "不送" : "照送"), logic.sends == (gates ? 2 : 4));

        CraftRecipeData second = craft(recipe(60));
        logic.getAllInProgressCraftData().add(second);
        tick(logic, craft);
        expect("in-progress 清單變長：送", logic.sends == (gates ? 3 : 5));
    }

    /**
     * stop 的明確同步必須更新比較基準：[A]@0% 已送 → 清空並明確同步 → 同一個 A 以 0% 重新開始，
     * 週期路徑要送出（client 最後收到的是空清單）。
     */
    private static void testExplicitSyncResetsBaseline() throws Exception {
        CountingLogic logic = countingLogic(isoObject(2, 2));
        CraftRecipeData craft = craft(recipe(86400));
        logic.getAllInProgressCraftData().add(craft);
        tick(logic, craft);
        logic.getAllInProgressCraftData().clear();
        MdcCraftSyncGate.explicitSync(logic);
        expect("明確同步照送", logic.sends == 2);
        logic.getAllInProgressCraftData().add(craft);
        tick(logic, craft);
        expect("同身分同百分比重新開始：仍送（基準已是空清單）", logic.sends == 3);
    }

    /** 簽章只隨 client 看得到的內容改變。 */
    private static void testSignatureInputs() throws Exception {
        CountingLogic logic = countingLogic(isoObject(3, 3));
        CraftRecipeData craft = craft(recipe(1000));
        logic.getAllInProgressCraftData().add(craft);
        craft.setElapsedTime(10.0);
        long base = MdcCraftSyncGate.signature(logic);
        craft.setElapsedTime(19.0);
        expect("1.0%→1.9%：簽章不變", MdcCraftSyncGate.signature(logic) == base);
        craft.setElapsedTime(20.0);
        long twoPercent = MdcCraftSyncGate.signature(logic);
        expect("到 2%：簽章改變", twoPercent != base);

        setDeclared(CraftRecipeData.class, logic.getCraftTestData(), "recipe", recipe(5));
        expect("目前配方（封包尾的配方名）改變：簽章改變", MdcCraftSyncGate.signature(logic) != twoPercent);

        long beforeOwner = MdcCraftSyncGate.signature(logic);
        setDeclared(Component.class, logic, "owner", isoObject(3, 3));
        expect("component 換 owner（pool 重用）：簽章改變", MdcCraftSyncGate.signature(logic) != beforeOwner);
    }

    /** DryingCraftLogic：tooltip 的濕度百分比與「仍濕＝暫停」都要觸發同步。 */
    private static void testDryingWetness() throws Exception {
        DryingCraftLogic drying = alloc(DryingCraftLogic.class);
        fillLogic(drying, isoObject(4, 4));
        HashMap<CraftRecipeData, Double> wetness = new HashMap<>();
        setDeclared(DryingCraftLogic.class, drying, "temporaryWetnesses", wetness);
        CraftRecipeData craft = craft(recipe(86400));
        drying.getAllInProgressCraftData().add(craft);

        wetness.put(craft, 0.0);
        long dry = MdcCraftSyncGate.signature(drying);
        wetness.put(craft, 0.004);
        long damp = MdcCraftSyncGate.signature(drying);
        expect("濕度 0→0.4%（顯示 0% 但已暫停）：簽章改變", damp != dry);
        wetness.put(craft, 0.0049);
        expect("0.4%→0.49%（顯示同為 0%）：簽章不變", MdcCraftSyncGate.signature(drying) == damp);
        wetness.put(craft, 0.304);
        long thirty = MdcCraftSyncGate.signature(drying);
        wetness.put(craft, 0.306);
        expect("30.4%→30.6%（顯示 30%→31%）：簽章改變", MdcCraftSyncGate.signature(drying) != thirty);
    }

    /** 簽章算不出來（資料不完整）就照原版送，不因判定失敗而漏送。 */
    private static void testSignatureFailureSends() throws Exception {
        CountingLogic broken = countingLogic(isoObject(5, 5));
        CraftRecipeData noRecipe = craft(null);
        noRecipe.setElapsedTime(5.0);
        broken.getAllInProgressCraftData().add(noRecipe);
        MdcCraftSyncGate.periodicSync(broken);
        MdcCraftSyncGate.periodicSync(broken);
        expect("簽章失敗：兩次都送", broken.sends == 2);
    }

    /** observe 真的做了判定（計數）；off 完全不判定。 */
    private static void testObserveCounters() throws Exception {
        long wouldSuppress = staticLong(MdcCraftSyncGate.class, "wouldSuppress");
        long wouldSkip = staticLong(Class.forName("zombie.mdc.GameEntityBroadcastGate"), "wouldSkip");
        if (MdcCraftSyncGate.MODE == MdcCraftSyncGate.OFF) {
            expect("off：零判定計數", wouldSuppress == 0 && wouldSkip == 0);
        } else {
            expect("mode=" + MdcCraftSyncGate.MODE + "：有可省判定（wouldSuppress/wouldSkip > 0）",
                    wouldSuppress > 0 && wouldSkip > 0);
        }
    }

    // ------------------------------------------------------------ fixture

    /** 只覆寫 buffer 端點與 getPacket 的真連線；內容由真 GameEntityNetwork.write 寫出。 */
    static final class Capture extends UdpConnection {
        ByteBuffer buffer;
        ByteBufferWriter writer;
        ArrayList<byte[]> packets;

        private Capture() {
            super(null, 0L, 0);
        }

        @Override
        public INetworkPacket getPacket(PacketTypes.PacketType type) {
            return new GameEntityNetwork();
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
        public int getBufferPosition() {
            return this.buffer.position();
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

    /** 送出次數即原版 sendCraftLogicSync 被呼叫的次數。 */
    static final class CountingLogic extends CraftLogic {
        int sends;

        private CountingLogic() {
            super(ComponentType.CraftLogic);
        }

        @Override
        public void sendCraftLogicSync() {
            this.sends++;
        }
    }

    private static void tick(CraftLogic logic, CraftRecipeData craft) {
        logic.limit = new UpdateLimit(0L, 1000L); // 下一次 Check() 必為 true
        logic.onUpdate(craft);
    }

    private static CountingLogic countingLogic(GameEntity owner) throws Exception {
        CountingLogic logic = alloc(CountingLogic.class);
        fillLogic(logic, owner);
        return logic;
    }

    private static void fillLogic(CraftLogic logic, GameEntity owner) throws Exception {
        setDeclared(CraftLogic.class, logic, "craftDataInProgress", new ArrayList<CraftRecipeData>());
        setDeclared(CraftLogic.class, logic, "craftTestData", craft(recipe(1)));
        setDeclared(Component.class, logic, "owner", owner);
    }

    private static CraftRecipe recipe(int time) throws Exception {
        CraftRecipe recipe = alloc(CraftRecipe.class);
        setDeclared(CraftRecipe.class, recipe, "time", time);
        return recipe;
    }

    private static CraftRecipeData craft(CraftRecipe recipe) throws Exception {
        CraftRecipeData data = alloc(CraftRecipeData.class);
        setDeclared(CraftRecipeData.class, data, "recipe", recipe);
        return data;
    }

    private static EntityPacketData data(byte[] payload) {
        EntityPacketData data = EntityPacketData.alloc(EntityPacketType.UpdateUsingPlayer);
        data.bb.put(payload);
        return data;
    }

    private static IsoObject isoObject(int x, int y) throws Exception {
        IsoObject object = alloc(IsoObject.class);
        IsoGridSquare square = alloc(IsoGridSquare.class);
        square.x = x;
        square.y = y;
        square.z = 0;
        PZArrayList<IsoObject> objects = new PZArrayList<>(IsoObject.class, 2);
        objects.add(object);
        setDeclared(IsoGridSquare.class, square, "objects", objects);
        object.square = square;
        return object;
    }

    private static Capture conn(int chunkGridWidth) throws Exception {
        Capture c = alloc(Capture.class);
        c.buffer = ByteBuffer.allocate(64 * 1024);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        c.players = new IsoPlayer[4];
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.setChunkGridWidth(chunkGridWidth);
        setDeclared(UdpConnection.class, c, "fullyConnected", Boolean.TRUE);
        return c;
    }

    private static IsoPlayer place(Capture c, int slot, float x, float y) throws Exception {
        IsoPlayer player = alloc(IsoPlayer.class);
        setAny(player, "health", 1.0f);
        setDeclared(IsoMovingObject.class, player, "current", playerSquare);
        player.cheats = new PlayerCheats();
        setDeclared(IsoMovingObject.class, player, "x", x);
        setDeclared(IsoMovingObject.class, player, "y", y);
        c.players[slot] = player;
        c.releventPos[slot] = new Vector3(x, y, 0.0f);
        return player;
    }

    private static void arm(Capture... conns) {
        connections.clear();
        for (Capture c : conns) {
            c.packets.clear();
            connections.add(c);
        }
    }

    // ------------------------------------------------------------ 反射工具

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

    private static int staticInt(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static long staticLong(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(null);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "get pass  " : "get FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private GameEntityTrafficTest() {}
}
