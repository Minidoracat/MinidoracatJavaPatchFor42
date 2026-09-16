package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.network.GameClient;

/**
 * BulkItemRegistration 對<b>真 {@code IsoCell.addToProcessItems(ArrayList)}</b> 的差分測試：
 * 同一組 fixture 複製兩份，一份跑原方法、一份跑 helper，比對元素順序／identity、移除集
 * 撤銷結果、例外型別（必要時例外 identity）與清單物件是否仍是同一個。
 *
 * <p>只保護有可觀察語意的差分與錯誤邊界。閘是否真的開過（配置量、分支計數）不在此斷言：
 * 那屬於實作細節，一次性證據留在 {@code work/item-batch-validation-20260916/}
 * （含四個拿掉閘的 mutant 必紅）。
 *
 * <p>{@code IsoCell} 是 final、兩個 getter 是純 getfield，所以 fixture 用
 * {@code Unsafe.allocateInstance} 取得空殼再反射寫 {@code processItems}／
 * {@code processItemsRemove}（JDK25 對非 static final instance field 的 setAccessible 寫
 * 已由既有 probe 驗證），不需要也不可能用子類替身。
 *
 * <p>{@code collisions} 以 {@code -XX:+UnlockExperimentalVMOptions -XX:hashCode=2} 獨立 JVM
 * 演練 Comparable tree-bin 回呼；{@code off} 只標示原版回退組態。
 * 臨時索引 OOM 的注入證據留在 {@code work/item-batch-validation-20260916/}。
 */
public final class BulkItemRegistrationTest {

    private static final Error HASH_CALLBACK = new AssertionError("hashCode callback");

    private static sun.misc.Unsafe unsafe;

    /** hashCode 回呼注入的目標與元素；每一側呼叫前重設。 */
    private static ArrayList<InventoryItem> mutationTarget;
    private static InventoryItem injected;
    private static boolean comparisonArmed;
    private static boolean comparisonMutated;

    public static void main(String[] args) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        // InventoryItem 的靜態鏈會取用 Rand；固定 seed 讓 fixture 建立是確定性的。
        Field rand = zombie.core.random.RandAbstract.class.getDeclaredField("rand");
        rand.setAccessible(true);
        rand.set(zombie.core.random.RandStandard.INSTANCE, new Random(1));
        if (args.length > 0 && args[0].equals("collisions")) {
            comparableCollision();
            return;
        }

        eligibleBatch();
        thresholds();
        passthroughShapes();
        itemClassGates();
        collectionSubclasses();
        nullCell();
        noPersistentIndex();
        randomizedBatches();

        System.out.println("bulk-item-registration OK  差分（順序／identity／移除撤銷／例外）、閘"
                + "、Comparable 元素差分、回呼與無常駐索引全數通過"
                + (args.length > 0 ? "（組態：" + args[0] + "）" : ""));
    }

    private static void comparableCollision() throws Exception {
        require(System.identityHashCode(new Object()) == System.identityHashCode(new Object()),
                "collisions 模式需要 -XX:+UnlockExperimentalVMOptions -XX:hashCode=2");
        ArrayList<InventoryItem> initial = items(5000);
        ArrayList<InventoryItem> incoming = items(300);
        ComparableItem query = alloc(ComparableItem.class);
        query.id = 999;
        incoming.set(0, query);
        injected = incoming.get(1);
        ArrayList<InventoryItem> vanilla = new ArrayList<>(initial);
        ArrayList<InventoryItem> patched = new ArrayList<>(initial);
        HashSet<InventoryItem> vanillaRemoving = new HashSet<>();
        HashSet<InventoryItem> patchedRemoving = new HashSet<>();
        for (int i = 0; i < 80; i++) {
            ComparableItem item = alloc(ComparableItem.class);
            item.id = i;
            vanillaRemoving.add(item);
            patchedRemoving.add(item);
        }
        IsoCell vanillaCell = cell(vanilla, vanillaRemoving);
        IsoCell patchedCell = cell(patched, patchedRemoving);
        comparisonArmed = true;
        try {
            comparisonMutated = false;
            mutationTarget = vanilla;
            vanillaCell.addToProcessItems(incoming);
            require(comparisonMutated, "原版必須真的觸發 tree-bin 回呼");
            comparisonMutated = false;
            mutationTarget = patched;
            BulkItemRegistration.addToProcessItems(patchedCell, incoming);
            require(comparisonMutated, "helper 必須保留 tree-bin 回呼");
        } finally {
            comparisonArmed = false;
        }
        requireSameOrder(vanilla, patched, "tree-bin 回呼插入後續批次元素，不得重複加入");
        require(vanillaRemoving.equals(patchedRemoving), "tree-bin 回呼後移除集一致");
        System.out.println("bulk-item-registration OK  Comparable 碰撞回呼與最終順序一致");
    }

    /** 主線：大批次含 null 洞、批次內重複、以及一個已在既有清單且待移除的元素。 */
    private static void eligibleBatch() throws Exception {
        ArrayList<InventoryItem> live = items(5000);
        ArrayList<InventoryItem> incoming = items(300);
        incoming.set(1, null);
        incoming.set(2, incoming.get(0));
        incoming.set(3, live.get(0));
        compare("大批次：順序、identity、null、重複、撤銷移除", live, incoming, false);
    }

    /** 閘的臨界值：4096／256 剛好成立，各差一就必須回原版（差分在兩側都必須成立）。 */
    private static void thresholds() throws Exception {
        compare("臨界成立：既有 4096、批次 256 個非 null", items(4096), items(256), false);
        compare("既有 4095（差一）", items(4095), items(256), false);
        ArrayList<InventoryItem> holed = items(256);
        holed.set(7, null);
        compare("批次非 null 僅 255（差一）", items(4096), holed, false);
        compare("小批次", items(5000), items(10), false);
    }

    /** 形狀類直通：client、null 批次、批次／既有清單別名、輸入為 ArrayList 子類。 */
    private static void passthroughShapes() throws Exception {
        ArrayList<InventoryItem> live = items(5000);
        compare("client 端直通", live, items(300), true);
        compare("null 批次（原版靜默 return）", live, null, false);
        compare("輸入為 ArrayList 子類", live, new InputList(items(300)), false);

        // 別名：批次就是既有清單本身。原版逐元素 contains 全中、不增長；helper 必須整通直通。
        ArrayList<InventoryItem> vanilla = new ArrayList<>(live);
        ArrayList<InventoryItem> patched = new ArrayList<>(live);
        IsoCell vanillaCell = cell(vanilla, new HashSet<>());
        IsoCell patchedCell = cell(patched, new HashSet<>());
        vanillaCell.addToProcessItems(vanilla);
        BulkItemRegistration.addToProcessItems(patchedCell, patched);
        requireSameOrder(vanilla, patched, "批次與既有清單別名");
        require(patched.size() == 5000, "別名時不得增長");
        System.out.println("diff OK      批次與既有清單別名");
    }

    /** 元素類別閘：自訂 equals/hashCode、拋例外的 hashCode、會改動清單的 hashCode、Comparable。 */
    private static void itemClassGates() throws Exception {
        ArrayList<InventoryItem> live = items(5000);
        compare("自訂 equals/hashCode 元素", live, withItem(items(300), 150, EqualityItem.class), false);
        compare("hashCode 拋 Error：例外 identity 保留", live,
                withItem(items(300), 150, ThrowingHash.class), false);
        compare("含 Comparable 元素的結果差分", live,
                withItem(items(300), 150, ComparableItem.class), false);

        injected = alloc(InventoryItem.class);
        compare("hashCode 回呼改動既有清單：兩路徑結果一致", live,
                withItem(items(300), 150, MutatingHash.class), false);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArrayList<InventoryItem> raw = new ArrayList(items(300));
        ((ArrayList) raw).set(150, "raw 非 InventoryItem 元素");
        compare("raw 壞元素：前綴副作用後 ClassCastException", live, raw, false);
    }

    /** 既有清單或移除集不是 exact ArrayList／HashSet 時直通；以回呼計數直接觀測走了原版。 */
    private static void collectionSubclasses() throws Exception {
        ArrayList<InventoryItem> initial = items(5000);
        ArrayList<InventoryItem> incoming = items(300);

        LiveList vanillaLive = new LiveList(initial);
        LiveList patchedLive = new LiveList(initial);
        cell(vanillaLive, new HashSet<>()).addToProcessItems(incoming);
        BulkItemRegistration.addToProcessItems(cell(patchedLive, new HashSet<>()), incoming);
        requireSameOrder(vanillaLive, patchedLive, "既有清單為 ArrayList 子類");
        require(vanillaLive.containsCalls == 300 && patchedLive.containsCalls == 300,
                "既有清單子類必須走原版的逐元素 contains（vanilla=" + vanillaLive.containsCalls
                        + " patched=" + patchedLive.containsCalls + "）");

        RemovingSet vanillaRemoving = new RemovingSet();
        RemovingSet patchedRemoving = new RemovingSet();
        ArrayList<InventoryItem> vanillaPlain = new ArrayList<>(initial);
        ArrayList<InventoryItem> patchedPlain = new ArrayList<>(initial);
        cell(vanillaPlain, vanillaRemoving).addToProcessItems(incoming);
        BulkItemRegistration.addToProcessItems(cell(patchedPlain, patchedRemoving), incoming);
        requireSameOrder(vanillaPlain, patchedPlain, "移除集為 HashSet 子類");
        require(vanillaRemoving.removeCalls == 300 && patchedRemoving.removeCalls == 300,
                "移除集子類必須走原版（vanilla=" + vanillaRemoving.removeCalls
                        + " patched=" + patchedRemoving.removeCalls + "）");
        System.out.println("gate OK      既有清單／移除集子類以回呼計數觀測到走原版");
    }

    /** cell 為 null 時與原 callsite 一樣是 NullPointerException。 */
    private static void nullCell() throws Exception {
        ArrayList<InventoryItem> incoming = items(300);
        Throwable vanilla = call(false, null, incoming);
        Throwable patched = call(true, null, incoming);
        require(vanilla instanceof NullPointerException && patched instanceof NullPointerException,
                "null cell 必須是 NullPointerException（vanilla=" + vanilla + " patched=" + patched + ")");
    }

    /**
     * 無常駐索引：外部以 subList 做同大小替換（size 不變、內容變），下一批仍必須與原版同結果。
     * 任何跨呼叫快取的 membership 索引都會在這裡與原版分岔。
     */
    private static void noPersistentIndex() throws Exception {
        ArrayList<InventoryItem> initial = items(5000);
        ArrayList<InventoryItem> vanilla = new ArrayList<>(initial);
        ArrayList<InventoryItem> patched = new ArrayList<>(initial);
        IsoCell vanillaCell = cell(vanilla, new HashSet<>());
        IsoCell patchedCell = cell(patched, new HashSet<>());
        ArrayList<InventoryItem> first = items(300);
        vanillaCell.addToProcessItems(first);
        BulkItemRegistration.addToProcessItems(patchedCell, first);
        requireSameOrder(vanilla, patched, "第一批");

        InventoryItem evicted = vanilla.get(0);
        InventoryItem replacement = alloc(InventoryItem.class);
        vanilla.subList(0, 1).set(0, replacement);
        patched.subList(0, 1).set(0, replacement);
        ArrayList<InventoryItem> second = items(300);
        second.set(0, evicted);
        vanillaCell.addToProcessItems(second);
        BulkItemRegistration.addToProcessItems(patchedCell, second);
        requireSameOrder(vanilla, patched, "外部同大小替換後的下一批");
        require(patched.get(5300) == evicted, "被替換掉的元素必須被當成不在場而重新加入");
        System.out.println("index OK     外部 subList 同大小替換後仍與原版同步（無常駐索引）");
    }

    /** 100 組隨機批次（含 null 與跨批重複）的差分。 */
    private static void randomizedBatches() throws Exception {
        ArrayList<InventoryItem> initial = items(5000);
        ArrayList<InventoryItem> pool = items(1000);
        ArrayList<InventoryItem> vanilla = new ArrayList<>(initial);
        ArrayList<InventoryItem> patched = new ArrayList<>(initial);
        IsoCell vanillaCell = cell(vanilla, new HashSet<>());
        IsoCell patchedCell = cell(patched, new HashSet<>());
        ArrayList<InventoryItem> batch = new ArrayList<>();
        Random random = new Random(53);
        for (int round = 0; round < 100; round++) {
            vanilla.clear();
            vanilla.addAll(initial);
            patched.clear();
            patched.addAll(initial);
            batch.clear();
            for (int i = 0; i < 300; i++) {
                batch.add(random.nextInt(8) == 0 ? null : pool.get(random.nextInt(pool.size())));
            }
            vanillaCell.addToProcessItems(batch);
            BulkItemRegistration.addToProcessItems(patchedCell, batch);
            requireSameOrder(vanilla, patched, "隨機批次 round=" + round);
        }
        System.out.println("diff OK      100 組隨機批次與原版逐元素一致");
    }

    /**
     * 核心差分：fixture 複製兩份（既有清單、移除集各自獨立，移除集預先放入 initial 第一個元素
     * 以覆蓋「撤銷待移除」），一份跑原方法、一份跑 helper，比對全部可觀察結果。
     */
    private static void compare(String label, ArrayList<InventoryItem> initial,
            ArrayList<InventoryItem> incoming, boolean client) throws Exception {
        ArrayList<InventoryItem> vanilla = new ArrayList<>(initial);
        ArrayList<InventoryItem> patched = new ArrayList<>(initial);
        Set<InventoryItem> vanillaRemoving = new HashSet<>();
        Set<InventoryItem> patchedRemoving = new HashSet<>();
        if (!initial.isEmpty()) {
            vanillaRemoving.add(initial.get(0));
            patchedRemoving.add(initial.get(0));
        }
        IsoCell vanillaCell = cell(vanilla, vanillaRemoving);
        IsoCell patchedCell = cell(patched, patchedRemoving);

        GameClient.client = client;
        Throwable vanillaError;
        Throwable patchedError;
        try {
            mutationTarget = vanilla;
            vanillaError = call(false, vanillaCell, incoming);
            mutationTarget = patched;
            patchedError = call(true, patchedCell, incoming);
        } finally {
            GameClient.client = false;
        }

        require((vanillaError == null) == (patchedError == null),
                label + "：例外有無不一致（vanilla=" + vanillaError + " patched=" + patchedError + ")");
        if (vanillaError != null) {
            require(vanillaError.getClass() == patchedError.getClass(),
                    label + "：例外型別不一致（" + vanillaError.getClass() + " vs " + patchedError.getClass() + ")");
            if (vanillaError == HASH_CALLBACK) {
                require(patchedError == HASH_CALLBACK, label + "：例外 identity 未保留");
            }
        }
        requireSameOrder(vanilla, patched, label);
        require(vanillaRemoving.equals(patchedRemoving), label + "：移除集撤銷結果不一致");
        require(vanillaCell.getProcessItems() == vanilla && patchedCell.getProcessItems() == patched,
                label + "：既有清單物件必須是同一個（不得替換實例）");
        System.out.println("diff OK      " + label);
    }

    private static Throwable call(boolean patched, IsoCell cell, ArrayList<InventoryItem> incoming) {
        try {
            if (patched) {
                BulkItemRegistration.addToProcessItems(cell, incoming);
            } else {
                cell.addToProcessItems(incoming);
            }
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static ArrayList<InventoryItem> withItem(ArrayList<InventoryItem> incoming, int index,
            Class<? extends InventoryItem> type) throws Exception {
        incoming.set(index, alloc(type));
        return incoming;
    }

    private static ArrayList<InventoryItem> items(int count) throws Exception {
        ArrayList<InventoryItem> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(alloc(InventoryItem.class));
        }
        return list;
    }

    /** {@code IsoCell} 是 final：空殼＋反射寫兩個 private final 清單欄位。 */
    private static IsoCell cell(ArrayList<InventoryItem> live, Set<InventoryItem> removing) throws Exception {
        IsoCell cell = alloc(IsoCell.class);
        field(cell, "processItems", live);
        field(cell, "processItemsRemove", removing);
        return cell;
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        Field target = owner.getClass().getDeclaredField(name);
        target.setAccessible(true);
        target.set(owner, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T alloc(Class<T> type) throws Exception {
        return (T) unsafe.allocateInstance(type);
    }

    private static void requireSameOrder(List<?> vanilla, List<?> patched, String label) {
        require(vanilla.size() == patched.size(),
                label + "：size 不一致（" + vanilla.size() + " vs " + patched.size() + ")");
        for (int i = 0; i < vanilla.size(); i++) {
            require(vanilla.get(i) == patched.get(i), label + "：第 " + i + " 個元素順序／identity 不一致");
        }
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    /** 輸入清單子類：exact-class 閘必須擋下（{@code get} 可回呼使用者程式碼）。 */
    static final class InputList extends ArrayList<InventoryItem> {
        InputList(Collection<InventoryItem> values) {
            super(values);
        }

        @Override
        public InventoryItem get(int index) {
            if (index == 0) mutationTarget.add(null);
            return super.get(index);
        }
    }

    /** 既有清單子類；{@code contains} 計數＝走了原版路徑的直接證據。 */
    static final class LiveList extends ArrayList<InventoryItem> {
        int containsCalls;

        LiveList(Collection<InventoryItem> values) {
            super(values);
        }

        @Override
        public boolean contains(Object value) {
            containsCalls++;
            return super.contains(value);
        }
    }

    /** 移除集子類；{@code remove} 計數＝走了原版路徑的直接證據。 */
    static final class RemovingSet extends HashSet<InventoryItem> {
        int removeCalls;

        @Override
        public boolean remove(Object value) {
            removeCalls++;
            return super.remove(value);
        }
    }

    public static class EqualityItem extends InventoryItem {
        public EqualityItem() {
            super(null, null, null, (String) null);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof InventoryItem;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    public static class ThrowingHash extends InventoryItem {
        public ThrowingHash() {
            super(null, null, null, (String) null);
        }

        @Override
        public int hashCode() {
            throw HASH_CALLBACK;
        }
    }

    public static class MutatingHash extends InventoryItem {
        public MutatingHash() {
            super(null, null, null, (String) null);
        }

        @Override
        public int hashCode() {
            mutationTarget.add(injected);
            return System.identityHashCode(this);
        }
    }

    public static class ComparableItem extends InventoryItem implements Comparable<ComparableItem> {
        int id;
        public ComparableItem() {
            super(null, null, null, (String) null);
        }

        @Override
        public int compareTo(ComparableItem other) {
            if (comparisonArmed && !comparisonMutated) {
                mutationTarget.add(injected);
                comparisonMutated = true;
            }
            return Integer.compare(id, other.id);
        }
    }

    private BulkItemRegistrationTest() {}
}
