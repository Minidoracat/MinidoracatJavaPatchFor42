package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/** W5-2 observe 行為測試：正常鏈、即時/深層命中、-1、64層 cap、wrapper 原值與計數。 */
public final class ContainerAddCycleProbeTest {
    public static void main(String[] args) throws Exception {
        cleanChain();
        immediateCycle();
        deepCycle();
        containerIdCycle();
        invalidId();
        depthCap();
        wrappersPreserveBehavior();
        System.out.println("ContainerAddCycleProbeTest OK");
    }

    private static void cleanChain() throws Exception {
        ItemContainer c = chain(5, -999);
        require(ContainerAddCycleProbe.probe(c, 42) == 0, "正常鏈應 CLEAN");
    }

    private static void immediateCycle() throws Exception {
        ItemContainer target = new ItemContainer();
        InventoryItem holder = rawItem(42);
        target.containingItem = holder;
        holder.setContainer(new ItemContainer());
        require(ContainerAddCycleProbe.probe(target, 42) == 1,
                "target 的 containingItem 就是待加入物品時應 WOULD_CYCLE");
    }

    private static void deepCycle() throws Exception {
        ItemContainer outer = new ItemContainer();
        ItemContainer cur = outer;
        for (int i = 0; i < 7; i++) {
            ItemContainer next = new ItemContainer();
            InventoryItem link = rawItem(i == 5 ? 777 : 100 + i);
            next.containingItem = link;
            link.setContainer(cur);
            cur = next;
        }
        require(ContainerAddCycleProbe.probe(cur, 777) == 1, "深層祖先 item 應命中");
    }

    private static void invalidId() throws Exception {
        require(ContainerAddCycleProbe.probe(chain(70, -1), -1) == 0,
                "itemId=-1 必須 CLEAN（vanilla 述詞同語意）");
    }

    private static void containerIdCycle() {
        ItemContainer target = new ItemContainer();
        target.id = 4242;
        require(ContainerAddCycleProbe.probe(target, 4242) == 1,
                "c.id == itemId 分支應 WOULD_CYCLE");
    }

    private static void depthCap() throws Exception {
        require(ContainerAddCycleProbe.probe(
                chain(ContainerAddCycleProbe.MAX_DEPTH + 2, -999), 999999) == 2,
                "超過 MAX_DEPTH 應 DEPTH_CAPPED");
    }

    private static void wrappersPreserveBehavior() throws Exception {
        ItemContainer clean = new ItemContainer();
        require(!ContainerAddCycleProbe.containsID(clean, 1234), "containsID false 原值保留");
        require(clean.getItems().isEmpty(), "observe wrapper 不得加入/刪除物品");
        if (ContainerAddCycleProbe.MODE == ContainerAddCycleProbe.MODE_OFF) {
            require(ContainerAddCycleProbe.callsAddForTest() == 0, "off 計數凍結");
            return;
        }
        require(ContainerAddCycleProbe.callsAddForTest() == 1, "clean false 候選 callsAdd=1");
        require(ContainerAddCycleProbe.wouldCycleForTest() == 0, "clean false 不報環");

        // wrapper 的 WOULD-CYCLE 正例：刪 probeAndLog 即會紅。
        ItemContainer cyc = new ItemContainer();
        InventoryItem candidate = rawItem(7777);
        cyc.containingItem = candidate;
        candidate.setContainer(new ItemContainer());
        long wc = ContainerAddCycleProbe.wouldCycleForTest();
        require(!ContainerAddCycleProbe.containsID(cyc, 7777), "cycle candidate containsID 原值仍 false");
        require(ContainerAddCycleProbe.wouldCycleForTest() == wc + 1, "wrapper 應記 wouldCycle+1");

        // containsID=true 時 vanilla 不加入，不能污染 candidate/wouldCycle。
        ItemContainer dup = new ItemContainer();
        InventoryItem existing = rawItem(8888);
        dup.getItems().add(existing);
        dup.containingItem = existing; // 若錯誤 probe 會命中，專殺 false-positive mutant。
        long callsBeforeDup = ContainerAddCycleProbe.callsAddForTest();
        long wcBeforeDup = ContainerAddCycleProbe.wouldCycleForTest();
        require(ContainerAddCycleProbe.containsID(dup, 8888), "containsID true 原值保留");
        require(ContainerAddCycleProbe.callsAddForTest() == callsBeforeDup,
                "containsID=true 非加入候選，不計 callsAdd");
        require(ContainerAddCycleProbe.wouldCycleForTest() == wcBeforeDup,
                "containsID=true 不得污染 wouldCycle");

        // 直接呼叫 dist 中 patched AddItem：observe 不拒絕、不改最終 vanilla 集合/backlink。
        ensureIsoWorld();
        ItemContainer target = new ItemContainer();
        ItemContainer old = new ItemContainer();
        InventoryItem moving = rawItem(9999);
        target.containingItem = moving; // 真 would-cycle 候選
        moving.setContainer(old);
        old.getItems().add(moving);
        long beforeDirect = ContainerAddCycleProbe.wouldCycleForTest();
        InventoryItem got = target.AddItem(moving);
        require(got == moving, "patched AddItem observe 必須回原 item");
        require(target.getItems().contains(moving) && !old.getItems().contains(moving),
                "patched AddItem 維持 vanilla remove→add");
        require(moving.getContainer() == target, "patched AddItem 維持 container backlink");
        require(ContainerAddCycleProbe.wouldCycleForTest() == beforeDirect + 1,
                "dist patched AddItem 必須真的經 probe");
        require(ContainerAddCycleProbe.anomaliesForTest() == 0, "anomalies=0");
    }

    /** depth 層線性巢狀；matchId>=0 時可指定某 link id，本測試 clean/cap 傳負值避免命中。 */
    private static ItemContainer chain(int depth, int matchId) throws Exception {
        ItemContainer outer = new ItemContainer();
        ItemContainer cur = outer;
        for (int i = 0; i < depth; i++) {
            ItemContainer next = new ItemContainer();
            InventoryItem link = rawItem(matchId >= 0 && i == depth / 2 ? matchId : 10_000 + i);
            next.containingItem = link;
            link.setContainer(cur);
            cur = next;
        }
        return cur;
    }

    private static InventoryItem rawItem(int id) throws Exception {
        InventoryItem item = (InventoryItem) rawInstance(InventoryItem.class);
        Field f = InventoryItem.class.getDeclaredField("id");
        f.setAccessible(true);
        f.setInt(item, id);
        return item;
    }

    private static void ensureIsoWorld() throws Exception {
        Field f = zombie.iso.IsoWorld.class.getDeclaredField("instance");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, rawInstance(zombie.iso.IsoWorld.class));
        }
    }

    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
