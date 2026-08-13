package zombie.mdc;

import java.lang.reflect.Constructor;
import java.util.List;

import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/**
 * W5 容器環守衛行為測試——核心是<b>真的造一個環</b>（袋 A 的物品住在容器 B 裡、
 * 袋 B 的物品住在容器 A 裡），對照 vanilla 爬升邏輯必爆 StackOverflowError、
 * 修補版安靜回 null，並驗證診斷能指出環的閉合點。
 *
 * <p>物件建構：{@code InventoryItem} 的公開建構子會拉起 ZomboidFileSystem（測試環境無法初始化），
 * 而本測試只在乎 {@code containingItem}／{@code container} 構成的<b>拓撲</b>，
 * 故以 serialization 建構子分配未初始化實例再手動接線。這也順帶驗證了診斷路徑對
 * 半初始化物件的健壯性（safeType 等包裝必須吞掉 NPE 而非炸掉）。
 */
public final class ContainerCycleGuardTest {

    public static void main(String[] args) throws Exception {
        if (ContainerCycleGuard.maxDepthForTest() <= 0) {
            killSwitchIsPassthrough();       // -Dmdc.cycleGuard.maxDepth=0 模式
            return;
        }
        vanillaCycleBlowsStack();
        guardBreaksCycleAndReturnsNull();
        returnsRealOwnerOnNormalPath();
        normalNestingUnaffected();
        chainDiagnosticsFindsClosure();
        depthCounterUnwinds();
        depthBoundary();
        System.out.println("cycle-guard OK  原版必爆/守衛切斷/正向回傳擁有者/正常巢狀/診斷定位/深度歸零/門檻邊界全數通過");
    }

    /**
     * 正向主路徑：守衛必須把<b>真實的</b>擁有者傳回去。
     * 沒有這條，一個「永遠回 null」的假 helper 會通過其他所有測試——而那會讓
     * GameServer 的五個庫存廣播對所有巢狀容器全部失效（審查抓到的假綠通道）。
     */
    private static void returnsRealOwnerOnNormalPath() throws Exception {
        ContainerCycleGuard.resetForTest();
        Object owner = rawInstance(zombie.characters.IsoPlayer.class);
        ItemContainer inner = chainOwnedBy(owner, 4);
        Object got = ContainerCycleGuard.getCharacter(inner);
        require(got == owner, "必須回傳真實擁有者，實得 " + got);
        require(ContainerCycleGuard.statsForTest()[0] == 0, "正向路徑不得觸發守衛");
    }

    /** 門檻邊界：MAX_DEPTH-1 不得觸發、MAX_DEPTH+2 必須觸發。 */
    private static void depthBoundary() throws Exception {
        int max = ContainerCycleGuard.maxDepthForTest();
        ContainerCycleGuard.resetForTest();
        ContainerCycleGuard.getCharacter(chain(max - 1));
        require(ContainerCycleGuard.statsForTest()[0] == 0,
                "深度 " + (max - 1) + " 不得觸發守衛");

        ContainerCycleGuard.resetForTest();
        ContainerCycleGuard.getCharacter(chain(max + 2));
        require(ContainerCycleGuard.statsForTest()[0] > 0,
                "深度 " + (max + 2) + " 必須觸發守衛");
    }

    /** kill switch：maxDepth=0 時完全 passthrough（正常鏈照走，不觸發守衛）。 */
    private static void killSwitchIsPassthrough() throws Exception {
        ContainerCycleGuard.resetForTest();
        Object owner = rawInstance(zombie.characters.IsoPlayer.class);
        ItemContainer inner = chainOwnedBy(owner, 4);
        require(ContainerCycleGuard.getCharacter(inner) == owner, "停用時仍須回傳擁有者");
        require(ContainerCycleGuard.statsForTest()[0] == 0, "停用時不得有 trips");
        System.out.println("cycle-guard OK  kill switch 模式：passthrough 且零 trips");
    }

    /**
     * 負對照：用 vanilla 的爬升邏輯（不經守衛）走同一個環必拋 StackOverflowError。
     * 沒有這條，「守衛有效」的斷言可能只是因為環根本沒造成功。
     */
    private static void vanillaCycleBlowsStack() throws Exception {
        ItemContainer[] cycle = buildCycle();
        boolean blew = false;
        try {
            walkVanilla(cycle[0]);
        } catch (StackOverflowError e) {
            blew = true;
        }
        require(blew, "前置：環必須真的能造成無限遞迴（否則後面的斷言無意義）");
    }

    /** 逐句複刻 vanilla getCharacter 的爬升（不含守衛），用來證明環是真的。 */
    private static Object walkVanilla(ItemContainer c) {
        if (c == null) {
            return null;
        }
        InventoryItem item = c.containingItem;
        if (item == null || item.getContainer() == null) {
            return null;
        }
        return walkVanilla(item.getContainer());
    }

    /** 守衛：在環上安靜回 null，不拋不掛，累計 trips，且深度歸零。 */
    private static void guardBreaksCycleAndReturnsNull() throws Exception {
        ContainerCycleGuard.resetForTest();
        ItemContainer[] cycle = buildCycle();
        Object result = ContainerCycleGuard.getCharacter(cycle[0]);
        require(result == null, "環上應回 null");
        long[] s = ContainerCycleGuard.statsForTest();
        require(s[0] > 0, "應累計 trips，實得 " + s[0]);
        require(s[2] == 0, "helper 不得有內部例外，實得 anomalies=" + s[2]);
        require(s[3] == 0, "深度計數應歸零，實得 " + s[3]);
    }

    /** 正常（非環）巢狀不得受影響：鏈到頂端回 null，且完全不觸發守衛。 */
    private static void normalNestingUnaffected() throws Exception {
        ContainerCycleGuard.resetForTest();
        // 五層線性巢狀，最外層 containingItem 為 null（等同放在地上）
        ItemContainer top = chain(5);
        Object r = ContainerCycleGuard.getCharacter(top);
        require(r == null, "無角色擁有者時回 null（與 vanilla 一致）");
        long[] s = ContainerCycleGuard.statsForTest();
        require(s[0] == 0, "正常巢狀不得觸發守衛，實得 trips=" + s[0]);
        require(s[3] == 0, "深度計數歸零");
    }

    /** 診斷：必須指出環的閉合點、含可追查的 itemId，且走鏈有硬上限。 */
    private static void chainDiagnosticsFindsClosure() throws Exception {
        ItemContainer[] cycle = buildCycle();
        List<String> lines = ContainerCycleGuard.describeChainForTest(cycle[0]);
        require(!lines.isEmpty(), "診斷不得為空");
        require(lines.stream().anyMatch(l -> l.contains("環在此閉合")),
                "診斷應指出環的閉合點，實得：" + lines);
        require(lines.size() <= 130, "走鏈必須有上限，實得 " + lines.size() + " 行");
        require(lines.stream().anyMatch(l -> l.contains("itemId=") && l.contains("type=")),
                "診斷需含 itemId 與 type 才追得到兇手：" + lines);
    }

    /** 深度計數在正常返回與截斷後都必須歸零，且不得污染下一次呼叫。 */
    private static void depthCounterUnwinds() throws Exception {
        ContainerCycleGuard.resetForTest();
        ItemContainer normal = chain(3);
        ContainerCycleGuard.getCharacter(normal);
        require(ContainerCycleGuard.statsForTest()[3] == 0, "正常路徑後深度歸零");

        ItemContainer[] cycle = buildCycle();
        ContainerCycleGuard.getCharacter(cycle[0]);
        require(ContainerCycleGuard.statsForTest()[3] == 0, "截斷後深度亦須歸零");

        ContainerCycleGuard.resetForTest();
        Object r = ContainerCycleGuard.getCharacter(normal);
        require(r == null && ContainerCycleGuard.statsForTest()[0] == 0,
                "截斷後不得殘留狀態污染下一次正常呼叫");
    }

    // ---- helpers ----

    /** 造真環：容器 A 的 containingItem 住在容器 B 裡，容器 B 的 containingItem 住在容器 A 裡。 */
    private static ItemContainer[] buildCycle() throws Exception {
        ItemContainer ca = new ItemContainer();
        ItemContainer cb = new ItemContainer();
        InventoryItem ia = rawItem();
        InventoryItem ib = rawItem();
        ca.containingItem = ia;
        cb.containingItem = ib;
        ia.setContainer(cb);
        ib.setContainer(ca);
        return new ItemContainer[]{ca, cb};
    }

    /** 造 depth 層線性巢狀（最外層 containingItem 為 null＝鏈的終點）。 */
    private static ItemContainer chain(int depth) throws Exception {
        ItemContainer outer = new ItemContainer();
        ItemContainer cur = outer;
        for (int i = 0; i < depth; i++) {
            ItemContainer next = new ItemContainer();
            InventoryItem link = rawItem();
            next.containingItem = link;
            link.setContainer(cur);
            cur = next;
        }
        return cur;
    }

    /** 造 depth 層線性巢狀，並讓最外層容器的 parent 是指定角色（＝有真實擁有者）。 */
    private static ItemContainer chainOwnedBy(Object owner, int depth) throws Exception {
        ItemContainer outer = new ItemContainer();
        setParent(outer, owner);
        ItemContainer cur = outer;
        for (int i = 0; i < depth; i++) {
            ItemContainer next = new ItemContainer();
            InventoryItem link = rawItem();
            next.containingItem = link;
            link.setContainer(cur);
            cur = next;
        }
        return cur;
    }

    private static void setParent(ItemContainer c, Object parent) throws Exception {
        var f = ItemContainer.class.getDeclaredField("parent");
        f.setAccessible(true);
        f.set(c, parent);
    }

    /**
     * 以 serialization 建構子分配未初始化的 InventoryItem（繞過 ZomboidFileSystem 依賴）。
     * 註：{@code sun.reflect.ReflectionFactory} 屬 jdk.unsupported，JDK 升級時這裡會先壞。
     */
    private static InventoryItem rawItem() throws Exception {
        return (InventoryItem) rawInstance(InventoryItem.class);
    }

    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private ContainerCycleGuardTest() {}
}
