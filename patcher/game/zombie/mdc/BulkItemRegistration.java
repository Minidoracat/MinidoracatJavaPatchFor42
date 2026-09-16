package zombie.mdc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.network.GameClient;

/**
 * 容器整批登記：{@code ItemContainer.addItemsToProcessItems} 唯一的
 * {@code IsoCell.addToProcessItems(ArrayList)} 呼叫改道至此。
 *
 * <p>vanilla 對批次每個元素做 {@code processItems.contains(item)}（ArrayList 線性掃），
 * 既有清單 P、批次 M 時為 O(P×M)。helper 只在值得時改成單趟：先把批次的 identity 建成
 * 一次性 {@link IdentityHashMap}（僅此呼叫存活，<b>不留常駐索引</b>，外部對清單的任意改動
 * 不需要失效通知），掃一趟既有清單標記已在場，再依原順序 {@code removing.remove} 加必要時
 * {@code live.add}。清單物件本身從不替換。
 *
 * <p>資格檢查全部在任何 mutation 之前完成；任何不確定一律<b>整通呼叫</b>回原方法（含原方法
 * 在壞元素或自訂 callback 之前的前綴副作用），所以 helper 不會半做。閘：既有清單 ≥4096、
 * 批次非 null 元素 ≥256、輸入與既有清單必須是 exact {@code ArrayList}、移除集必須是 exact
 * {@code HashSet}、兩清單不得別名，且批次元素必須是 {@code InventoryItem}、沿用
 * {@code Object} 的 equals/hashCode 且非 {@code Comparable}——否則 {@code contains}／
 * {@code HashSet.remove}／HashMap tree-bin 可能回呼使用者程式碼，次數與順序不可預測。
 *
 * <p>依賴的上游形狀（建置期守門）：{@code IsoCell} 為 {@code final}，兩個 getter 為純
 * {@code getfield}。
 *
 * <p>預設 on；{@code -Dmdc.bulkItemRegistration=0|off} 重啟後回原版直通。
 */
public final class BulkItemRegistration {

    /** 既有清單／批次非 null 元素的下限；低於此線原版的線性 contains 本來就便宜。 */
    private static final int MIN_EXISTING = 4096;
    private static final int MIN_INCOMING = 256;

    private static final String FLAG = System.getProperty("mdc.bulkItemRegistration");

    /** 兩態 kill switch：{@code 0}／{@code off} 回原版，其餘（含未設定）為 on；需重啟。 */
    private static final boolean ENABLED = !"0".equals(FLAG) && !"off".equals(FLAG);

    /**
     * 元素類別是否維持 {@code Object} 的 identity equals/hashCode 且非 {@code Comparable}。
     * {@link ClassValue} 讓每個類別只反射一次；不可判定時一律視為不純（保守回原版）。
     */
    private static final ClassValue<Boolean> PURE_IDENTITY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return !Comparable.class.isAssignableFrom(type)
                        && type.getMethod("equals", Object.class).getDeclaringClass() == Object.class
                        && type.getMethod("hashCode").getDeclaringClass() == Object.class;
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                return false;
            }
        }
    };

    private BulkItemRegistration() {}

    /**
     * {@code INVOKEVIRTUAL zombie/iso/IsoCell.addToProcessItems:(Ljava/util/ArrayList;)V} 的改道目標。
     * {@code cell} 為 null 時委派呼叫本身即拋 {@code NullPointerException}，與原 callsite 一致。
     */
    public static void addToProcessItems(IsoCell cell, ArrayList<InventoryItem> incoming) {
        if (!ENABLED || cell == null || incoming == null || GameClient.client
                || incoming.getClass() != ArrayList.class || incoming.size() < MIN_INCOMING) {
            cell.addToProcessItems(incoming);
            return;
        }
        ArrayList<InventoryItem> live = cell.getProcessItems();
        Set<InventoryItem> removing = cell.getProcessItemsRemove();
        if (live == null || live.getClass() != ArrayList.class || live == incoming
                || live.size() < MIN_EXISTING || removing == null || removing.getClass() != HashSet.class) {
            cell.addToProcessItems(incoming);
            return;
        }
        // 前置檢查不做任何 mutation。未知元素整通重播原方法，
        // 含原方法在壞 raw 元素或自訂 callback 之前的前綴副作用。
        int candidateCount = 0;
        for (int i = 0; i < incoming.size(); i++) {
            Object value = incoming.get(i);
            if (value != null && (!(value instanceof InventoryItem) || !PURE_IDENTITY.get(value.getClass()))) {
                cell.addToProcessItems(incoming);
                return;
            }
            if (value != null) {
                candidateCount++;
            }
        }
        if (candidateCount < MIN_INCOMING) {
            cell.addToProcessItems(incoming);
            return;
        }
        IdentityHashMap<Object, Boolean> present;
        try {
            // 只索引批次的 identity：規模由這個容器決定，不是整個世界。
            present = new IdentityHashMap<>(candidateCount);
            for (int i = 0; i < incoming.size(); i++) {
                Object value = incoming.get(i);
                if (value != null) {
                    present.put(value, Boolean.FALSE);
                }
            }
            int missing = present.size();
            for (int i = 0; i < live.size() && missing > 0; i++) {
                Object value = live.get(i);
                if (present.get(value) == Boolean.FALSE) {
                    present.put(value, Boolean.TRUE);
                    missing--;
                }
            }
        } catch (OutOfMemoryError unavailable) {
            System.err.println("[MinidoracatJavaPatch][BulkItemRegistration] 臨時索引配置失敗，改走原版登記");
            cell.addToProcessItems(incoming);
            return;
        } catch (IllegalArgumentException capacityUnsupported) {
            cell.addToProcessItems(incoming);
            return;
        }
        for (int i = 0; i < incoming.size(); i++) {
            InventoryItem item = incoming.get(i);
            if (item != null) {
                removing.remove(item);
                if (present.get(item) == Boolean.FALSE) {
                    live.add(item);
                    present.put(item, Boolean.TRUE);
                }
            }
        }
    }
}
