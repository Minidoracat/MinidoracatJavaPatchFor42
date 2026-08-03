package zombie.mdc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugType;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;

/**
 * P5：IsoCell 三清單（processIsoObject／processIsoObjectRemove／staticUpdaterObjectList）
 * 的 identity membership sidecar（設計 docs/p5-chunk-unload-design-v2.md，雙審定稿）。
 *
 * chunk 卸載 burst 對每個物件做 contains O(P)＋remove O(S) 幾乎全 miss 全掃——sidecar
 * 集合把 miss 降為 O(1)；清單本身仍是迭代與順序的權威（保序語意不動），hit 保留
 * vanilla O(N)。removeAll 由 O(P×R) 改單趟保序壓實＋尾端逐刪 O(P+R)。
 *
 * 不變量：set == list 中所有「不同 identity」的集合（非 size 對等——清單可含重複元素，
 * 移除一份後若仍有另一份，membership 保留；remove 成功後以 list.contains 複核才除名）。
 *
 * 生命週期＝IsoCell generation bundle（codex 定案，取代 weak registry）：cell 換代整組
 * 替換，無 GC 猜測；舊 cell 清單的呼叫自動降級 vanilla。kill 為 process-lifetime
 * terminal：僅 audit divergence（抽驗不一致）走門檻，size 對帳 rebuild 只觀測不計
 * （GO-WITH-FIXES：重度 MOD 環境的良性旁路自癒不該累積成 kill）。
 *
 * 執行緒模型：全部呼叫點都在主執行緒（chunk 卸載、cell update、server 側屍體管理）；
 * 監視鎖僅為防禦。鎖序固定 P→R（removeAll 巢狀），CONTROL 只包 gen 換代與 kill 轉換，
 * log 一律在 monitor 外。
 */
public final class CellListMembership {

    /** 抽驗週期（(opCount & mask)==0 觸發）；非 final 供測試設 0＝每 op 抽驗。 */
    static int auditMask = 0xFFF;
    private static final int KILL_THRESHOLD = 8;
    private static final long LOG_MASK = (1L << 20) - 1;

    static final class State {
        final Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        int expectedSize = -1;   // -1 = 未初始化／已毒化，下次 rebuild 不計觀測
        int opCount;
    }

    static final class Gen {
        final IsoCell cell;      // 測試注入時為 null（testMode 下不做 cell 比對）
        final ArrayList<IsoObject> p;
        final ArrayList<IsoObject> r;
        final ArrayList<IsoObject> s;
        final State pState = new State();
        final State rState = new State();
        final State sState = new State();

        Gen(IsoCell cell) {
            this.cell = cell;
            this.p = cell.getProcessIsoObjects();
            this.r = cell.getProcessIsoObjectRemove();
            this.s = cell.getStaticUpdaterObjectList();
        }

        @SuppressWarnings("unchecked")
        Gen(ArrayList<?> p, ArrayList<?> r, ArrayList<?> s) {
            this.cell = null;
            this.p = (ArrayList<IsoObject>) p;
            this.r = (ArrayList<IsoObject>) r;
            this.s = (ArrayList<IsoObject>) s;
        }
    }

    private static volatile Gen gen;
    private static volatile boolean killed;
    private static boolean testMode;
    private static final AtomicInteger auditDivergence = new AtomicInteger();  // 走 kill 門檻
    private static final AtomicInteger rebuildTotal = new AtomicInteger();     // 僅觀測
    private static final AtomicLong opTotal = new AtomicLong();
    private static final Object CONTROL = new Object();

    // ---- 15 個呼叫點的六個改道目標（receiver 前插；raw type 對齊 bytecode descriptor）----

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean contains(ArrayList list, Object o) {
        State st = stateFor(list);
        if (st == null) {
            return list.contains(o);
        }
        boolean ans;
        String killMsg = null;
        synchronized (st) {
            if (!reconcile(st, list)) {
                ans = list.contains(o);
            } else {
                st.opCount++;
                ans = st.set.contains(o);
                if ((st.opCount & auditMask) == 0 && list.contains(o) != ans) {
                    killMsg = noteDivergence(st, list, "contains-audit");
                    ans = list.contains(o);
                }
            }
        }
        logOutside(killMsg);
        return ans;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean add(ArrayList list, Object o) {
        State st = stateFor(list);
        if (st == null) {
            return list.add(o);
        }
        synchronized (st) {
            boolean ok = reconcile(st, list);
            boolean r = list.add(o);
            if (ok) {
                st.opCount++;
                st.set.add(o);
                st.expectedSize = list.size();
            } else {
                st.expectedSize = -1;   // 毒化：下次強制 rebuild（含本次 add）
            }
            return r;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean remove(ArrayList list, Object o) {
        State st = stateFor(list);
        if (st == null) {
            return list.remove(o);
        }
        boolean result;
        String killMsg = null;
        synchronized (st) {
            if (!reconcile(st, list)) {
                result = list.remove(o);
                st.expectedSize = -1;
            } else {
                st.opCount++;
                if (!st.set.contains(o)) {
                    // fast-miss 共用 audit（codex 雷 2：等大小換血的 ghost 由此收斂）
                    if ((st.opCount & auditMask) == 0 && list.contains(o)) {
                        killMsg = noteDivergence(st, list, "remove-miss-audit");
                        result = removeMirrored(st, list, o);
                    } else {
                        result = false;
                    }
                } else {
                    result = removeMirrored(st, list, o);
                }
            }
        }
        logOutside(killMsg);
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void clear(ArrayList list) {
        State st = stateFor(list);
        if (st == null) {
            list.clear();
            return;
        }
        synchronized (st) {
            list.clear();
            st.set.clear();
            st.expectedSize = 0;
            st.opCount++;
        }
    }

    /**
     * ProcessIsoObject 的 processIsoObject.removeAll(processIsoObjectRemove)。
     * codex 處方：嚴格 gate（非 ArrayList.class／null 一律原生，NPE 與 subclass 語意 parity
     * 由原生承擔）→ R 快照（固定大小、非 iterator）→ P 單趟保序壓實 → 尾端逐刪
     * （每刪 modCount++，精確還原 JDK batchRemove 的 += removedCount）→ 成功才 commit。
     * 鎖序巢狀 P→R（GO-WITH-FIXES 修 3）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean removeAll(ArrayList list, Collection c) {
        State pSt = stateFor(list);
        Gen g = gen;
        State rSt = (pSt != null && g != null && c == g.r) ? g.rState : null;
        if (pSt == null || rSt == null
                || list.getClass() != ArrayList.class
                || c == null || c.getClass() != ArrayList.class) {
            return list.removeAll(c);
        }
        if (c.isEmpty()) {
            return false;   // 原生等價：batchRemove 掃 P 不刪、回 false、modCount 不變
        }
        synchronized (pSt) {
            synchronized (rSt) {
                if (!reconcile(pSt, list)) {
                    boolean r = list.removeAll(c);
                    pSt.expectedSize = -1;
                    return r;
                }
                ArrayList ca = (ArrayList) c;
                int n = ca.size();
                Set<Object> kill = Collections.newSetFromMap(new IdentityHashMap<>());
                for (int i = 0; i < n; i++) {
                    kill.add(ca.get(i));
                }
                int size = list.size();
                int w = 0;
                try {
                    for (int i = 0; i < size; i++) {
                        Object e = list.get(i);
                        if (!kill.contains(e)) {
                            if (i != w) {
                                list.set(w, e);
                            }
                            w++;
                        }
                    }
                    for (int i = size - 1; i >= w; i--) {
                        list.remove(i);
                    }
                } catch (RuntimeException ex) {
                    pSt.expectedSize = -1;   // 毒化，不半提交 sidecar
                    throw ex;
                }
                rebuildInto(pSt, list);      // 結構成功後才 commit
                return w < size;
            }
        }
    }

    // ---- 內部 ----

    /** killed／未知清單／cell 換代解析；測試模式跳過 cell 比對。回 null＝走 vanilla。 */
    private static State stateFor(ArrayList<?> list) {
        if (killed || list == null) {
            return null;
        }
        maybeLog();
        Gen g = gen;
        if (!testMode) {
            IsoCell cur = IsoCell.getInstance();
            if (cur == null) {
                return null;   // 實證不可達（instance 僅建構子賦值），防禦未來 build
            }
            if (g == null || g.cell != cur) {
                synchronized (CONTROL) {
                    if (killed) {
                        return null;
                    }
                    g = gen;
                    cur = IsoCell.getInstance();
                    if (cur == null) {
                        return null;
                    }
                    if (g == null || g.cell != cur) {
                        g = new Gen(cur);
                        gen = g;
                    }
                }
            }
        } else if (g == null) {
            return null;
        }
        if (list == g.p) {
            return g.pState;
        }
        if (list == g.r) {
            return g.rState;
        }
        if (list == g.s) {
            return g.sState;
        }
        return null;
    }

    /** size 對帳；漂移→rebuild（僅觀測不走 kill）。回 false＝本次 sidecar 不可信，呼叫端走 vanilla。 */
    private static boolean reconcile(State st, ArrayList<?> list) {
        if (st.expectedSize == list.size()) {
            return true;
        }
        if (st.expectedSize != -1) {
            rebuildTotal.incrementAndGet();
        }
        return rebuildInto(st, list);
    }

    /** 暫存重建，成功才 commit；失敗保留舊 set 並計入 kill 門檻（跨執行緒損壞才可能）。 */
    private static boolean rebuildInto(State st, ArrayList<?> list) {
        Set<Object> tmp = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (int i = 0; i < list.size(); i++) {
                tmp.add(list.get(i));
            }
        } catch (IndexOutOfBoundsException e) {
            bumpDivergence("rebuild-ioobe");
            return false;
        }
        st.set.clear();
        st.set.addAll(tmp);
        st.expectedSize = list.size();
        return true;
    }

    /** 移除＋重複元素感知鏡射（codex 雷 1：list 仍有另一份就不除名）。呼叫端持 st 鎖。 */
    private static boolean removeMirrored(State st, ArrayList<?> list, Object o) {
        boolean r = list.remove(o);
        if (r) {
            st.expectedSize = list.size();
            if (!list.contains(o)) {
                st.set.remove(o);
            }
        }
        return r;
    }

    /** audit 不一致：rebuild＋計數；達門檻→kill 轉換（CONTROL 內），訊息由呼叫端在 monitor 外發。 */
    private static String noteDivergence(State st, ArrayList<?> list, String where) {
        rebuildInto(st, list);
        return bumpDivergence(where);
    }

    private static String bumpDivergence(String where) {
        int total = auditDivergence.incrementAndGet();
        if (total >= KILL_THRESHOLD && !killed) {
            synchronized (CONTROL) {
                if (!killed) {
                    killed = true;
                    gen = null;
                }
            }
            return "[MinidoracatJavaPatch][CellList] KILLED at " + where
                    + " divergence=" + total + " rebuilds=" + rebuildTotal.get()
                    + " —— sidecar 永久停用，全數回歸 vanilla";
        }
        return "[MinidoracatJavaPatch][CellList] divergence at " + where
                + " total=" + total + "/" + KILL_THRESHOLD;
    }

    private static void maybeLog() {
        long t = opTotal.incrementAndGet();
        if ((t & LOG_MASK) == 0L) {
            Gen g = gen;
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][CellList] ops=" + t
                    + " P=" + (g != null ? g.p.size() : -1)
                    + " R=" + (g != null ? g.r.size() : -1)
                    + " S=" + (g != null ? g.s.size() : -1)
                    + " rebuilds=" + rebuildTotal.get()
                    + " divergence=" + auditDivergence.get()
                    + " killed=" + killed);
        }
    }

    private static void logOutside(String msg) {
        if (msg != null) {
            DebugType.Multiplayer.println(msg);
        }
    }

    // ---- 測試掛鉤（SmokeCheck 專用；正式路徑 testMode 恆 false）----

    static void testInject(ArrayList<?> p, ArrayList<?> r, ArrayList<?> s) {
        synchronized (CONTROL) {
            testMode = true;
            killed = false;
            gen = new Gen(p, r, s);
        }
    }

    static void testReset() {
        synchronized (CONTROL) {
            testMode = false;
            killed = false;
            gen = null;
            auditDivergence.set(0);
            rebuildTotal.set(0);
            auditMask = 0xFFF;
        }
    }

    static boolean testKilled() {
        return killed;
    }

    private CellListMembership() {}
}
