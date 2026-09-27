package zombie.mdc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import zombie.debug.DebugLog;

/**
 * W45 物品處理清單身分索引（2026-09-27；docs/patches.md 2bh）。
 *
 * <p><b>原版</b>：{@code IsoCell.addToProcessItems} 兩個多載在加入前對 {@code processItems}（ArrayList）做
 * {@code contains}——線性掃描。chunk 載入時 {@code IsoObject.addToWorld → ItemContainer.addItemsToProcessItems}
 * 對容器裡每件物品各掃一次整份清單。伺服器 {@code DaysForRottenFoodRemoval ≠ -1} 時 {@code Food.finishupdate()}
 * 讓所有會腐壞的食物常駐清單（本服 2–3 萬件），估計吃掉主執行緒約 5%，chunk 載入尖峰更多。
 * {@code ProcessRemoveItems} 每幀兩次 {@code removeAll(processItemsRemove)} 也不先檢查空集合，每次都掃完整份清單。
 *
 * <p><b>本刀</b>：IsoCell 建構子唯一 {@code PUTFIELD processItems} 之前由 {@link #wrap} 把新建的空 ArrayList 換成本類別
 * （Patcher FieldPutWrap）。清單本身仍是順序與內容的權威；另以 IdentityHashMap 記每個元素的出現次數，
 * {@code contains} 查表 O(1)，空集合的 {@code removeAll} 直接返回。原版 {@code InventoryItem} 全繼承鏈沒有覆寫
 * {@code equals}／{@code hashCode}（SmokeCheck 全 jar 釘住），identity 查表與 {@code ArrayList.contains} 的 equals
 * 比對結果相同；IdentityHashMap 不呼叫元素的任何方法，移除也不留墓碑。
 *
 * <p><b>不變量與自癒</b>：
 * <ul>
 * <li>只有建立清單的執行緒（伺服器主執行緒）更新與使用索引。其他執行緒照原版寫清單並把索引標成待重建；
 *     其他執行緒的 {@code contains} 走原版線性掃描。</li>
 * <li>會改內容的 public 方法全部覆寫並同步索引；經未覆寫路徑造成的修改由 {@code modCount} 比對察覺，
 *     下次查詢前整份重建。取過 {@code subList} 的清單永久改回線性（view 的 set 不動 modCount，無從察覺）。</li>
 * <li>每 4096 次查詢抽一次原版線性結果比對；不一致即記錄並重建，累計 3 次全域停用，所有清單退回原版。</li>
 * </ul>
 * observe 模式照常維護索引，但一律回傳原版線性結果（逐次比對，只記錄不改變行為）。
 * {@code -Dmdc.processItemsIndex=0|off}：{@link #wrap} 原樣回傳原版 ArrayList。
 */
public final class ProcessItemsIndex extends ArrayList<Object> {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.processItemsIndex"));

    private static final String TAG = "[MinidoracatJavaPatch][ProcessItemsIndex] ";
    private static final long HEARTBEAT_MS = 300_000L;
    private static final long AUDIT_MASK = 4095L;
    private static final long DISABLE_AFTER = 3L;
    private static final long DETAIL_LIMIT = 20L;

    private static volatile boolean disabled;
    private static final AtomicLong offOwner = new AtomicLong();
    // 以下只在擁有者執行緒（伺服器主執行緒）更新。
    private static boolean announced;
    private static long lookups, hits, audits, divergences, rebuilds, rebuildItems;
    private static long emptyRemoveAll, views, anomalies, lastBeat;

    private final transient Thread owner;
    private final transient IdentityHashMap<Object, Integer> counts = new IdentityHashMap<>();
    private transient volatile boolean dirty;
    private transient volatile boolean viewIssued;
    private transient int expectedModCount;

    private ProcessItemsIndex(Collection<?> initial) {
        super(initial);
        owner = Thread.currentThread();
        reindex();
    }

    /** IsoCell 建構子 {@code PUTFIELD processItems} 之前：把新建的 ArrayList 換成帶索引的清單。 */
    public static ArrayList<Object> wrap(ArrayList<Object> vanilla) {
        if (MODE == MODE_OFF || vanilla == null) {
            return vanilla;
        }
        ProcessItemsIndex list = new ProcessItemsIndex(vanilla);
        if (!announced) {
            announced = true;
            log("首次生效 mode=" + MODE + " owner=" + list.owner.getName()
                    + "（-Dmdc.processItemsIndex=0 回原版線性 contains；2=observe 只比對不改）");
        }
        return list;
    }

    // ---------------------------------------------------------------- 查詢

    @Override
    public boolean contains(Object o) {
        if (Thread.currentThread() != owner) {
            offOwner.incrementAndGet();
            return super.contains(o);
        }
        if (!fresh()) {
            return super.contains(o);
        }
        long n = ++lookups;
        boolean indexed = counts.containsKey(o);
        if (indexed) {
            hits++;
        }
        if (MODE == MODE_OBSERVE || (n & AUDIT_MASK) == 0L) {
            audits++;
            boolean linear = super.contains(o);
            if (linear != indexed) {
                diverged(o, indexed, linear);
            }
            return linear;
        }
        return indexed;
    }

    // ---------------------------------------------------------------- 修改（每一個都同步索引或標成待重建）

    @Override
    public boolean add(Object e) {
        boolean t = tracking();
        boolean changed = super.add(e);
        if (t) {
            inc(e);
        }
        done(t);
        return changed;
    }

    @Override
    public void add(int index, Object e) {
        boolean t = tracking();
        super.add(index, e);
        if (t) {
            inc(e);
        }
        done(t);
    }

    @Override
    public boolean addAll(Collection<? extends Object> c) {
        boolean t = tracking();
        if (!t) {
            boolean changed = super.addAll(c);
            done(false);
            return changed;
        }
        Object[] a = c.toArray();   // 先快照：c 可能就是本清單
        boolean changed = super.addAll(Arrays.asList(a));
        for (Object e : a) {
            inc(e);
        }
        done(true);
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<? extends Object> c) {
        boolean t = tracking();
        if (!t) {
            boolean changed = super.addAll(index, c);
            done(false);
            return changed;
        }
        Object[] a = c.toArray();
        boolean changed = super.addAll(index, Arrays.asList(a));
        for (Object e : a) {
            inc(e);
        }
        done(true);
        return changed;
    }

    @Override
    public Object remove(int index) {
        boolean t = tracking();
        Object old = super.remove(index);
        if (t) {
            dec(old);
        }
        done(t);
        return old;
    }

    /** 與原版同一個比對迴圈（{@code o.equals(es[i])}）找位置，再經 {@link #remove(int)} 精確遞減實際移除的元素。 */
    @Override
    public boolean remove(Object o) {
        int i = indexOf(o);
        if (i < 0) {
            return false;
        }
        remove(i);
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        if (c.isEmpty()) {
            // 原版 batchRemove 會逐一掃完整份清單後回 false；內容與 modCount 皆不變。
            if (Thread.currentThread() == owner) {
                emptyRemoveAll++;
                maybeBeat();
            }
            return false;
        }
        boolean t = tracking();
        boolean changed = super.removeAll(c);
        if (changed && t) {
            // removeAll 移除所有 c.contains(元素) 為真的元素（全部副本）。原版唯一呼叫端是 HashSet<InventoryItem>，
            // 物品沒有覆寫 equals/hashCode ⇒ identity 比對，移除的正是這些鍵。其他比對語意的集合由抽驗兜底。
            for (Object o : c) {
                counts.remove(o);
            }
        }
        done(t);
        if (t) {
            maybeBeat();
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean t = tracking();
        boolean changed = super.retainAll(c);
        if (changed) {
            dirty = true;
        }
        done(t);
        return changed;
    }

    @Override
    public boolean removeIf(Predicate<? super Object> filter) {
        boolean t = tracking();
        boolean changed = super.removeIf(filter);
        if (changed) {
            dirty = true;
        }
        done(t);
        return changed;
    }

    @Override
    public void replaceAll(UnaryOperator<Object> operator) {
        boolean t = tracking();
        super.replaceAll(operator);
        dirty = true;
        done(t);
    }

    @Override
    public Object set(int index, Object e) {
        boolean t = tracking();
        Object old = super.set(index, e);
        if (t) {
            dec(old);
            inc(e);
        }
        done(t);
        return old;
    }

    @Override
    public void clear() {
        boolean t = tracking();
        super.clear();
        if (t) {
            counts.clear();
        }
        done(t);
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        boolean t = tracking();
        super.removeRange(fromIndex, toIndex);
        dirty = true;
        done(t);
    }

    @Override
    public void sort(Comparator<? super Object> c) {
        boolean t = tracking();
        super.sort(c);
        done(t);
    }

    @Override
    public void trimToSize() {
        boolean t = tracking();
        super.trimToSize();
        done(t);
    }

    @Override
    public void ensureCapacity(int minCapacity) {
        boolean t = tracking();
        super.ensureCapacity(minCapacity);
        done(t);
    }

    @Override
    public List<Object> subList(int fromIndex, int toIndex) {
        if (!viewIssued) {
            viewIssued = true;
            if (Thread.currentThread() == owner) {
                views++;
            }
            log("subList view issued: 本清單改回原版線性 contains（view 的 set 不經過索引） size=" + size());
        }
        return super.subList(fromIndex, toIndex);
    }

    /** ArrayList.clone 是淺拷貝，會讓兩份清單共用同一個索引；改回傳內容相同的原版 ArrayList。 */
    @Override
    public Object clone() {
        return new ArrayList<>(this);
    }

    // ---------------------------------------------------------------- 索引維護

    /** 擁有者執行緒上本次修改要同步索引時回 true；其他執行緒記數後回 false（修改後標成待重建）。 */
    private boolean tracking() {
        if (Thread.currentThread() != owner) {
            offOwner.incrementAndGet();
            return false;
        }
        if (disabled || viewIssued) {
            return false;
        }
        if (modCount != expectedModCount) {
            dirty = true;   // 之前有經未覆寫路徑的修改：本次照常同步，下次查詢前整份重建
        }
        return true;
    }

    private void done(boolean tracked) {
        if (tracked) {
            expectedModCount = modCount;
        } else {
            dirty = true;
        }
    }

    /** 擁有者執行緒上：索引可用時回 true，必要時先整份重建。 */
    private boolean fresh() {
        if (disabled || viewIssued) {
            return false;
        }
        if (dirty || modCount != expectedModCount) {
            dirty = false;   // 先清旗標：重建期間其他執行緒的修改會再次標記
            try {
                reindex();
            } catch (RuntimeException e) {
                // 其他執行緒正在改同一份清單（原版同樣不安全）：本次走線性，下次再重建
                dirty = true;
                anomalies++;
                return false;
            }
            rebuilds++;
        }
        return true;
    }

    private void reindex() {
        counts.clear();
        int n = size();
        for (int i = 0; i < n; i++) {
            inc(get(i));
        }
        expectedModCount = modCount;
        rebuildItems += n;
    }

    private void inc(Object e) {
        counts.merge(e, 1, Integer::sum);
    }

    private void dec(Object e) {
        Integer c = counts.get(e);
        if (c == null) {
            dirty = true;   // 索引與清單不一致：下次查詢前重建
        } else if (c == 1) {
            counts.remove(e);
        } else {
            counts.put(e, c - 1);
        }
    }

    private void diverged(Object o, boolean indexed, boolean linear) {
        long n = ++divergences;
        dirty = true;
        boolean disable = MODE == MODE_ON && n >= DISABLE_AFTER;
        if (disable) {
            disabled = true;
        }
        if (n <= DETAIL_LIMIT || disable) {
            log("divergence #" + n + " indexed=" + indexed + " linear=" + linear
                    + " item=" + (o == null ? "null" : o.getClass().getSimpleName() + "@" + System.identityHashCode(o))
                    + " size=" + size() + " keys=" + counts.size()
                    + (disable ? " → 全域停用索引，所有清單退回原版線性 contains" : "（下次查詢前重建）"));
        }
    }

    private void maybeBeat() {
        long now = System.currentTimeMillis();
        if (now - lastBeat < HEARTBEAT_MS) {
            return;
        }
        lastBeat = now;
        log("beat mode=" + MODE + " size=" + size() + " keys=" + counts.size() + " lookups=" + lookups
                + " hits=" + hits + " audits=" + audits + " divergences=" + divergences
                + " rebuilds=" + rebuilds + " rebuildItems=" + rebuildItems + " emptyRemoveAll=" + emptyRemoveAll
                + " offOwner=" + offOwner.get() + " views=" + views + " anomalies=" + anomalies
                + " disabled=" + disabled);
    }

    private static void log(String message) {
        try {
            DebugLog.log(TAG + message);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 三態解析：0|off、1|on|enforce、2|observe；未設定與未知值落回預設 on（家族 parseMode 慣例）。 */
    private static int parseMode(String raw) {
        if (raw == null) {
            return MODE_ON;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0":
            case "off":
                return MODE_OFF;
            case "2":
            case "observe":
                return MODE_OBSERVE;
            default:
                return MODE_ON;
        }
    }

    // ---------------------------------------------------------------- 測試用

    static long lookupsForTest() { return lookups; }
    static long auditsForTest() { return audits; }
    static long divergencesForTest() { return divergences; }
    static long rebuildsForTest() { return rebuilds; }
    static long emptyRemoveAllForTest() { return emptyRemoveAll; }
    static long offOwnerForTest() { return offOwner.get(); }
    static boolean disabledForTest() { return disabled; }
    IdentityHashMap<Object, Integer> countsForTest() { return counts; }
}
