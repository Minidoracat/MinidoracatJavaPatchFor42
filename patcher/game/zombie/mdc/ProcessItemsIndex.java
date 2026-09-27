package zombie.mdc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
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
 * <p><b>不變量</b>：
 * <ul>
 * <li>只有建立清單的執行緒（伺服器主執行緒）更新與使用索引。其他執行緒照原版寫清單並把索引標成待重建；
 *     其他執行緒的 {@code contains} 走原版線性掃描。</li>
 * <li>單件修改（add／remove／set／clear 及 iterator 經過的同名方法）與原版熱路徑 {@code removeAll(HashSet)} 增量同步。
 *     會回呼外部程式碼或可能中途拋例外留下部分修改的批次操作（replaceAll／sort／removeIf／retainAll／其他集合的
 *     removeAll）視為批次區段：區段內 {@code contains} 走原版線性，結束後（含例外）整份重建。經未覆寫路徑的結構修改由
 *     {@code modCount} 比對察覺。取過 {@code subList} 的清單永久改回線性（view 的 set 不動 modCount，無從察覺），
 *     並釋放索引。</li>
 * <li>每 4096 次查詢抽一次原版線性結果比對，是最後防線而非正確性保證（只察覺剛好抽中的查詢）。不一致即記錄並重建，
 *     累計 3 次全域停用，所有清單退回原版並釋放索引。</li>
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
    private transient int busy;   // 批次區段深度（只在擁有者執行緒增減）

    private ProcessItemsIndex(Collection<?> initial) {
        super();   // 與原版 new ArrayList<>() 相同的預設空容量（ensureCapacity／擴容行為一致）
        owner = Thread.currentThread();
        if (!initial.isEmpty()) {
            super.addAll(initial);
        }
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
        if (busy > 0 || !fresh()) {
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
        if (index < 0 || index > size()) {
            return super.addAll(index, c);   // 無效 index：沿用原版先檢查 index、不碰 c 的例外順序
        }
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
        if (c.getClass() != HashSet.class) {
            // 原版熱路徑以外（本清單或其 view 的別名、比對語意不明的集合）：照原版做，結束後整份重建。
            boolean own = enterBulk();
            try {
                return super.removeAll(c);
            } finally {
                exitBulk(own);
            }
        }
        if (c.isEmpty()) {
            // 原版 batchRemove 會逐一掃完整份清單後回 false；HashSet.contains 不拋例外，內容與 modCount 皆不變。
            if (Thread.currentThread() == owner) {
                emptyRemoveAll++;
                maybeBeat();
            }
            return false;
        }
        boolean t = tracking();
        int before = size();
        boolean changed = super.removeAll(c);
        if (changed && t) {
            // 原版 ProcessRemoveItems 傳入 HashSet<InventoryItem>：HashSet.contains 以清單元素的 equals 比對，物品沒有覆寫
            // equals/hashCode ⇒ identity，移除的正是這些鍵（且不會回呼本清單）。實際移除數對不上就整份重建。
            int expected = 0;
            for (Object o : c) {
                Integer k = counts.remove(o);
                if (k != null) {
                    expected += k;
                }
            }
            if (expected != before - size()) {
                dirty = true;
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
        boolean own = enterBulk();
        try {
            return super.retainAll(c);
        } finally {
            exitBulk(own);
        }
    }

    @Override
    public boolean removeIf(Predicate<? super Object> filter) {
        boolean own = enterBulk();
        try {
            return super.removeIf(filter);
        } finally {
            exitBulk(own);
        }
    }

    @Override
    public void replaceAll(UnaryOperator<Object> operator) {
        boolean own = enterBulk();
        try {
            super.replaceAll(operator);
        } finally {
            exitBulk(own);
        }
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

    /** comparator 中途拋例外時 TimSort 可能留下重複／遺失的元素，故同樣視為批次區段。 */
    @Override
    public void sort(Comparator<? super Object> c) {
        boolean own = enterBulk();
        try {
            super.sort(c);
        } finally {
            exitBulk(own);
        }
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
            release();
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

    /**
     * 會回呼外部程式碼、或可能中途拋例外留下部分修改的批次操作：區段內本清單的 {@code contains} 走原版線性
     * （回呼看到的是正在修改的陣列），結束後（含例外）整份重建。
     */
    private boolean enterBulk() {
        if (Thread.currentThread() != owner) {
            offOwner.incrementAndGet();
            return false;
        }
        busy++;
        return true;
    }

    private void exitBulk(boolean own) {
        if (own) {
            busy--;
        }
        dirty = true;
    }

    /** 永久線性（取過 subList 或全域停用）：擁有者執行緒上清空索引，不再持有已移出清單的物品。 */
    private void release() {
        if (!counts.isEmpty()) {
            counts.clear();
        }
    }

    /** 擁有者執行緒上：索引可用時回 true，必要時先整份重建。 */
    private boolean fresh() {
        if (disabled || viewIssued) {
            release();
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
