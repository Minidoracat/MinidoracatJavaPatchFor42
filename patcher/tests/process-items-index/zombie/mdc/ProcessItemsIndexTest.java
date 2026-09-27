package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * W45 ProcessItemsIndex 行為驗證（{@code on}／{@code observe}／{@code off}；{@code collisions} 另以
 * {@code -XX:hashCode=2} 讓所有 identity hash 相同）：
 * <ul>
 * <li>真 {@code IsoCell(int,int)} 建構子：欄位初始化後 {@code processItems} 是索引清單（off 為原版 ArrayList）。</li>
 * <li>dist 內手術後的真 {@code IsoCell} 方法：同一組操作在原版清單與索引清單上結果逐一相同。</li>
 * <li>20 萬步隨機操作與參照 ArrayList 逐步差分（重複元素、null、self addAll、iterator／listIterator）。</li>
 * <li>subList 改回線性並釋放索引、其他執行緒修改後重建、clone 不共用索引。</li>
 * <li>批次操作與別名（審查反例）：replaceAll／sort 中途例外與回呼重入、removeAll／retainAll 以自身或 view 為參數、
 *     非 identity 集合、空集合例外語意、無效 index、空清單 ensureCapacity——逐一與原版 ArrayList 比對。</li>
 * <li>最後：人為弄壞索引，抽驗察覺並重建，累計 3 次全域停用（observe 只記錄、結果恆為原版）。</li>
 * </ul>
 */
public final class ProcessItemsIndexTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "on";
        boolean collisions = args.length > 1 && args[1].equals("collisions");
        int want = switch (mode) {
            case "off" -> ProcessItemsIndex.MODE_OFF;
            case "observe" -> ProcessItemsIndex.MODE_OBSERVE;
            default -> ProcessItemsIndex.MODE_ON;
        };
        expect("旗標與 argv 相符 mode=" + ProcessItemsIndex.MODE, ProcessItemsIndex.MODE == want);
        if (collisions) {
            expect("collisions：identity hash 全部相同（-XX:hashCode=2 生效）",
                    System.identityHashCode(new Object()) == System.identityHashCode(new Object()));
        }
        GameServer.mainThread = Thread.currentThread();
        boolean off = want == ProcessItemsIndex.MODE_OFF;

        realConstructor(off);
        realIsoCellDifferential(off);
        randomDifferential(off, collisions ? 40_000 : 200_000);
        bulkAndAliasEdges(off);
        if (!off) {
            viewsThreadsAndClone();
            corruptionSelfHeal(want == ProcessItemsIndex.MODE_OBSERVE);
        } else {
            ArrayList<Object> plain = new ArrayList<>();
            expect("off：wrap 原樣回傳同一個原版 ArrayList", ProcessItemsIndex.wrap(plain) == plain);
        }

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ProcessItemsIndexTest 全數通過（" + mode + (collisions ? "，collisions" : "") + "）");
        System.exit(0);   // 真建構子可能留下遊戲執行緒
    }

    /** 真建構子：欄位初始化（含 processItems）先於第一行 IsoWorld.instance.currentCell = this。 */
    private static void realConstructor(boolean off) throws Exception {
        IsoWorld world = IsoWorld.instance;
        expect("IsoWorld.instance 可用", world != null);
        if (world == null) {
            return;
        }
        IsoCell before = world.currentCell;
        Throwable[] thrown = new Throwable[1];
        Thread ctor = new Thread(() -> {
            try {
                new IsoCell(10, 10);
            } catch (Throwable t) {
                thrown[0] = t;
            }
        }, "cell-ctor");
        ctor.setDaemon(true);
        ctor.start();
        ctor.join(20_000);
        IsoCell cell = world.currentCell;
        System.out.println("  （建構子在測試環境的結束狀態：" + (ctor.isAlive() ? "仍在執行" : thrown[0] == null
                ? "正常完成" : thrown[0].getClass().getSimpleName()) + "；只檢查欄位初始化）");
        expect("真建構子執行到欄位初始化之後（currentCell 指向新 cell）", cell != null && cell != before);
        if (cell == null || cell == before) {
            return;
        }
        Object list = cell.getProcessItems();
        if (off) {
            expect("off：真建構子仍是原版 ArrayList", list.getClass() == ArrayList.class);
        } else {
            expect("on／observe：真建構子的 processItems 是索引清單", list instanceof ProcessItemsIndex);
            expect("索引清單初始為空", ((ArrayList<?>) list).isEmpty());
        }
        world.currentCell = before;
    }

    /** 同一組操作分別跑在原版清單與索引清單的真 IsoCell 上（dist 內手術後的方法，含 W40 hooks）。 */
    private static void realIsoCellDifferential(boolean off) throws Exception {
        Method process = IsoCell.class.getDeclaredMethod("ProcessItems", Iterator.class);
        Method remove = IsoCell.class.getDeclaredMethod("ProcessRemoveItems", Iterator.class);
        process.setAccessible(true);
        remove.setAccessible(true);
        ArrayList<Object> vanillaList = new ArrayList<>();
        ArrayList<Object> indexedList = ProcessItemsIndex.wrap(new ArrayList<>());
        expect(off ? "off：wrap 回原版" : "wrap 回索引清單",
                off ? indexedList.getClass() == ArrayList.class : indexedList instanceof ProcessItemsIndex);
        IsoCell vanilla = cell(vanillaList);
        IsoCell indexed = cell(indexedList);
        FakeItem a = item(false), b = item(false), c = item(true), d = item(false), e = item(true);
        long emptyBefore = ProcessItemsIndex.emptyRemoveAllForTest();

        for (IsoCell cell : new IsoCell[]{vanilla, indexed}) {
            cell.addToProcessItems(a);
            cell.addToProcessItems(b);
            cell.addToProcessItems(a);                          // 重複：原版 contains 擋下
            cell.addToProcessItems((InventoryItem) null);       // null：原版直接 return
            ArrayList<InventoryItem> batch = new ArrayList<>(java.util.Arrays.asList(c, d, c, null, a));
            cell.addToProcessItems(batch);                      // 批次：逐件 contains（c 第二次、a 已在）
            cell.addToProcessItemsRemove(b);
            cell.addToProcessItems(b);                          // 加回＝取消排定的移除
            cell.addToProcessItemsRemove(d);
            remove.invoke(cell, (Object) null);                 // d 移除
            remove.invoke(cell, (Object) null);                 // 空集合 removeAll
        }
        sameSequence("單件／批次／取消移除／ProcessRemoveItems 後內容", vanillaList, indexedList);
        for (Object probe : new Object[]{a, b, c, d, e, null, new Object()}) {
            expect("getProcessItems().contains 與原版相同 probe=" + name(probe),
                    vanilla.getProcessItems().contains(probe) == indexed.getProcessItems().contains(probe));
        }
        for (IsoCell cell : new IsoCell[]{vanilla, indexed}) {
            process.invoke(cell, (Object) null);                // c 的 finishupdate=true → 排入移除
            remove.invoke(cell, (Object) null);
            cell.addToProcessItems(e);
            cell.addToProcessItems(c);                          // 移除後可以再加回
        }
        sameSequence("ProcessItems 移除完成物品後再加入", vanillaList, indexedList);
        expect("W40 ProcessItems 照常處理（兩個 cell 各 update 1 次）",
                a.updates == 2 && b.updates == 2 && c.updates == 2 && d.updates == 0);
        if (!off) {
            expect("ProcessRemoveItems 的空 removeAll 直接返回（計數增加）",
                    ProcessItemsIndex.emptyRemoveAllForTest() > emptyBefore);
        }
    }

    /** 隨機操作與參照 ArrayList 逐步差分（涵蓋每個覆寫的修改方法）。 */
    private static void randomDifferential(boolean off, int steps) {
        Random r = new Random(45);
        Object[] pool = new Object[65];
        for (int i = 0; i < 64; i++) {
            pool[i] = item(false);
        }
        pool[64] = null;
        ArrayList<Object> ref = new ArrayList<>();
        ArrayList<Object> sut = ProcessItemsIndex.wrap(new ArrayList<>());
        long divergencesBefore = ProcessItemsIndex.divergencesForTest();
        int mismatches = 0;
        for (int step = 0; step < steps; step++) {
            Object x = pool[r.nextInt(pool.length)];
            Object y = pool[r.nextInt(pool.length)];
            int op = r.nextInt(100);
            if (ref.size() > 400) {
                op = 49;   // 控制大小：改做 removeAll
            }
            if (op < 20) {
                same(ref, sut, l -> l.add(x));
            } else if (op < 30) {
                both(ref, sut, l -> { if (!l.contains(x)) l.add(x); });   // IsoCell.addToProcessItems 的形狀
            } else if (op < 36) {
                int i = r.nextInt(ref.size() + 1);
                both(ref, sut, l -> l.add(i, x));
            } else if (op < 40) {
                same(ref, sut, l -> l.addAll(new ArrayList<>(java.util.Arrays.asList(x, y, x))));
            } else if (op < 41 && ref.size() < 100) {
                same(ref, sut, l -> l.addAll(l));                              // self addAll
            } else if (op < 43) {
                int i = r.nextInt(ref.size() + 1);
                same(ref, sut, l -> l.addAll(i, java.util.Arrays.asList(y, x)));
            } else if (op < 49) {
                same(ref, sut, l -> l.remove(x));
            } else if (op < 55) {
                HashSet<Object> kill = new HashSet<>();
                int n = op == 49 ? 20 : r.nextInt(4);                       // 0 件＝空集合路徑
                for (int k = 0; k < n; k++) {
                    kill.add(pool[r.nextInt(pool.length)]);
                }
                same(ref, sut, l -> l.removeAll(kill));
            } else if (op < 60) {
                if (!ref.isEmpty()) {
                    int i = r.nextInt(ref.size());
                    same(ref, sut, l -> l.remove(i));
                }
            } else if (op < 62) {
                HashSet<Object> keep = new HashSet<>();
                for (int k = 0; k < 50; k++) {
                    keep.add(pool[r.nextInt(pool.length)]);
                }
                same(ref, sut, l -> l.retainAll(keep));
            } else if (op < 64) {
                same(ref, sut, l -> l.removeIf(o -> o == x));
            } else if (op < 70) {
                if (!ref.isEmpty()) {
                    int i = r.nextInt(ref.size());
                    same(ref, sut, l -> l.set(i, y));
                }
            } else if (op < 73) {
                if (!ref.isEmpty()) {
                    int stop = r.nextInt(ref.size());
                    both(ref, sut, l -> {
                        Iterator<Object> it = l.iterator();
                        for (int k = 0; k <= stop; k++) {
                            it.next();
                        }
                        it.remove();
                    });
                }
            } else if (op < 76) {
                int at = r.nextInt(ref.size() + 1);
                boolean doSet = r.nextBoolean() && at < ref.size();
                both(ref, sut, l -> {
                    ListIterator<Object> it = l.listIterator(at);
                    if (doSet) {
                        it.next();
                        it.set(x);
                    } else {
                        it.add(x);
                    }
                });
            } else if (op < 77) {
                both(ref, sut, l -> l.sort(Comparator.comparingInt(System::identityHashCode)));
            } else if (op < 78) {
                both(ref, sut, l -> l.replaceAll(o -> o == x ? y : o));
            } else if (op < 79) {
                both(ref, sut, l -> { l.trimToSize(); l.ensureCapacity(512); });
            } else if (op < 80 && r.nextInt(20) == 0) {
                both(ref, sut, ArrayList::clear);
            }
            if (ref.size() != sut.size()) {
                mismatches++;   // 內容已分歧（例如條件式加入時查錯）：後續以 ref 大小算的索引會越界，直接停止
                System.out.println("  （第 " + step + " 步後大小分歧 ref=" + ref.size() + " sut=" + sut.size() + "）");
                break;
            }
            for (int k = 0; k < 2; k++) {
                Object p = pool[r.nextInt(pool.length)];
                if (ref.contains(p) != sut.contains(p)) {
                    mismatches++;
                }
            }
            if (step % 1000 == 0 && !sameIdentity(ref, sut)) {
                mismatches++;
            }
        }
        expect("隨機 " + steps + " 步：內容與 contains 與參照 ArrayList 全部一致 mismatches=" + mismatches,
                mismatches == 0 && sameIdentity(ref, sut));
        expect("隨機 " + steps + " 步：各操作回傳值與參照 ArrayList 一致 returnMismatches=" + returnMismatches,
                returnMismatches == 0);
        if (!off) {
            expect("隨機差分期間零 divergence", ProcessItemsIndex.divergencesForTest() == divergencesBefore);
            expect("隨機差分期間有抽驗（audits>0）", ProcessItemsIndex.auditsForTest() > 0);
        }
    }

    /** subList 改回線性、其他執行緒修改、clone 不共用索引。 */
    private static void viewsThreadsAndClone() throws Exception {
        FakeItem a = item(false), b = item(false), c = item(false);
        ArrayList<Object> viewed = ProcessItemsIndex.wrap(new ArrayList<>());
        viewed.addAll(java.util.Arrays.asList(a, b));
        List<Object> view = viewed.subList(0, 1);
        view.set(0, c);                                   // view 的 set 不經過索引、不動 modCount
        expect("subList 後改回線性：view.set 的結果正確反映", viewed.contains(c) && !viewed.contains(a));
        view.clear();
        expect("subList.clear 後正確", !viewed.contains(c) && viewed.contains(b) && viewed.size() == 1);
        expect("取過 subList 後擁有者查詢即釋放索引（不再持有已移出的物品）",
                ((ProcessItemsIndex) viewed).countsForTest().isEmpty());

        ArrayList<Object> shared = ProcessItemsIndex.wrap(new ArrayList<>());
        shared.add(a);
        expect("擁有者執行緒查詢", shared.contains(a) && !shared.contains(b));
        long offBefore = ProcessItemsIndex.offOwnerForTest();
        boolean[] seen = new boolean[3];
        Thread other = new Thread(() -> {
            shared.add(b);
            shared.remove(a);
            seen[0] = shared.contains(b);
            seen[1] = !shared.contains(a);
            seen[2] = true;
        }, "not-owner");
        other.start();
        other.join();
        expect("其他執行緒：寫入照原版、查詢走線性且正確", seen[0] && seen[1] && seen[2]);
        expect("其他執行緒修改後擁有者查詢先重建再回答", shared.contains(b) && !shared.contains(a));
        expect("offOwner 計入其他執行緒的操作", ProcessItemsIndex.offOwnerForTest() >= offBefore + 4);

        ArrayList<Object> source = ProcessItemsIndex.wrap(new ArrayList<>());
        source.add(a);
        @SuppressWarnings("unchecked")
        ArrayList<Object> copy = (ArrayList<Object>) source.clone();
        boolean cloneSame = copy.getClass() == ArrayList.class && sameIdentity(copy, source);
        copy.remove(a);
        copy.add(b);
        expect("clone 是內容相同的原版 ArrayList、修改不影響原清單的索引",
                cloneSame && source.contains(a) && !source.contains(b) && copy.contains(b) && !copy.contains(a));
    }

    /** 人為弄壞索引：抽驗察覺並重建；on 累計 3 次全域停用、observe 只記錄且結果恆為原版。最後執行（會停用全域索引）。 */
    private static void corruptionSelfHeal(boolean observe) {
        FakeItem a = item(false), b = item(false);
        ArrayList<Object> list = ProcessItemsIndex.wrap(new ArrayList<>());
        list.add(a);
        list.add(b);
        ProcessItemsIndex idx = (ProcessItemsIndex) list;
        long base = ProcessItemsIndex.divergencesForTest();
        for (int round = 1; round <= 3; round++) {
            expect("第 " + round + " 輪弄壞前索引正確", list.contains(a));
            idx.countsForTest().remove(a);                 // 索引說 a 不在，清單裡其實在
            boolean wrongSeen = false;
            for (int i = 0; i < 4096 && ProcessItemsIndex.divergencesForTest() < base + round; i++) {
                if (!list.contains(a)) {
                    wrongSeen = true;
                }
            }
            expect("第 " + round + " 輪抽驗察覺 divergence", ProcessItemsIndex.divergencesForTest() == base + round);
            expect(observe ? "observe：結果恆為原版（沒有錯答）" : "on：抽驗前可能錯答（抽驗才察覺）",
                    observe ? !wrongSeen : true);
            expect("第 " + round + " 輪察覺後重建、再查正確", list.contains(a) && list.contains(b));
        }
        if (observe) {
            expect("observe：不因 divergence 停用", !ProcessItemsIndex.disabledForTest());
        } else {
            expect("on：累計 3 次 divergence 後全域停用", ProcessItemsIndex.disabledForTest());
            expect("停用後擁有者查詢即釋放索引", list.contains(a) && idx.countsForTest().isEmpty());
            expect("停用後走原版線性：結果正確", list.contains(a) && list.contains(b));
            ArrayList<Object> fresh = ProcessItemsIndex.wrap(new ArrayList<>());
            fresh.add(a);
            expect("停用後新清單同樣走原版線性", fresh.contains(a) && !fresh.contains(b));
        }
    }

    /** 審查反例：每個情境與原版 ArrayList 比對例外、內容與 contains（索引清單在 on 模式直接回答，不靠抽驗）。 */
    private static void bulkAndAliasEdges(boolean off) {
        FakeItem a = item(false), b = item(false), c = item(false), d = item(false), e = item(false);
        Object[] probes = {a, b, c, d, e, null};
        List<Object> ab = java.util.Arrays.asList(a, b);
        sameAsVanilla("replaceAll 中途拋例外（前段已替換）", off, ab, probes, l -> l.replaceAll(x -> {
            if (x == b) {
                throw new IllegalStateException("op");
            }
            return x == a ? c : x;
        }));
        ArrayList<Object> reentrant = sameAsVanilla("replaceAll 回呼讀本清單（看到已替換的前段）", off, ab, probes,
                l -> l.replaceAll(x -> x == a ? c : l.contains(c) ? d : e));
        expect("  原版結果為 [c,d]（回呼確實看到前段替換）", reentrant.get(0) == c && reentrant.get(1) == d);
        sameAsVanilla("removeIf 回呼讀本清單", off, ab, probes, l -> l.removeIf(x -> x == a && l.contains(b)));
        sameAsVanilla("removeIf 回呼拋例外", off, ab, probes, l -> l.removeIf(x -> {
            throw new IllegalStateException("pred");
        }));
        sameAsVanilla("removeAll(本清單)", off, ab, probes, l -> l.removeAll(l));
        sameAsVanilla("removeAll(本清單的唯讀 view)", off, ab, probes, l -> l.removeAll(Collections.unmodifiableList(l)));
        sameAsVanilla("retainAll(本清單的唯讀 view)", off, ab, probes, l -> l.retainAll(Collections.unmodifiableList(l)));
        TreeSet<Object> allEqual = new TreeSet<>((x, y) -> 0);
        allEqual.add(a);
        sameAsVanilla("removeAll(比較子視全部相等的 TreeSet)", off, ab, probes, l -> l.removeAll(allEqual));
        String s1 = new String("k"), s2 = new String("k");
        HashSet<Object> equalNotSame = new HashSet<>(Set.of(s2));
        sameAsVanilla("removeAll(HashSet 含 equals 相等但非同一物件)", off, java.util.Arrays.asList(a, s1), new Object[]{a, s1, s2},
                l -> l.removeAll(equalNotSame));
        HashSet<Object> hot = new HashSet<>(java.util.Arrays.asList(a, d));
        sameAsVanilla("removeAll(HashSet) 熱路徑：重複元素全部移除", off, java.util.Arrays.asList(a, b, a, c), probes,
                l -> l.removeAll(hot));
        sameAsVanilla("removeAll(Set.of()) 遇 null 元素：沿用原版例外", off, java.util.Arrays.asList(a, null), probes,
                l -> l.removeAll(Set.of()));
        sameAsVanilla("removeAll(空 HashSet)：捷徑回 false", off, ab, probes, l -> {
            if (l.removeAll(new HashSet<>())) {
                throw new AssertionError("回傳 true");
            }
        });
        sameAsVanilla("addAll(-1, null)：先報 index 錯誤", off, ab, probes, l -> l.addAll(-1, null));
        sameAsVanilla("空清單 ensureCapacity 不動 modCount", off, List.of(), probes, l -> {
            Iterator<Object> it = l.iterator();
            l.ensureCapacity(1);
            it.next();
        });

        Object[] ranked = new Object[64];
        IdentityHashMap<Object, Integer> rank = new IdentityHashMap<>();
        for (int i = 0; i < ranked.length; i++) {
            ranked[i] = item(false);
            rank.put(ranked[i], i);
        }
        List<Object> shuffled = new ArrayList<>(java.util.Arrays.asList(ranked));
        Collections.shuffle(shuffled, new Random(45));
        int[] total = {0};
        new ArrayList<>(shuffled).sort((x, y) -> {
            total[0]++;
            return Integer.compare(rank.get(x), rank.get(y));
        });
        int throwAt = total[0] - 3;   // 最後一次合併中途
        ArrayList<Object> torn = sameAsVanilla("sort 比較子在最後合併中途拋例外", off, shuffled, ranked, l -> {
            int[] n = {0};
            l.sort((x, y) -> {
                if (++n[0] == throwAt) {
                    throw new IllegalStateException("cmp");
                }
                return Integer.compare(rank.get(x), rank.get(y));
            });
        });
        IdentityHashMap<Object, Boolean> distinct = new IdentityHashMap<>();
        for (Object o : torn) {
            distinct.put(o, true);
        }
        expect("  原版在例外後確實留下重複／遺失的元素（情境有效）", distinct.size() < ranked.length);
    }

    private interface Scenario {
        void run(ArrayList<Object> list);
    }

    /**
     * 同一情境跑在原版 ArrayList（{@code new ArrayList<>()} 後 addAll，與 IsoCell 相同的建構方式）與索引清單上：
     * 例外型別、最終內容與每個 probe 的 contains 必須相同；on／observe 另驗查詢後索引與內容一致且沒有新增 divergence。
     * 回傳原版清單供呼叫端檢查情境本身。
     */
    private static ArrayList<Object> sameAsVanilla(String name, boolean off, List<Object> initial, Object[] probes,
                                                  Scenario s) {
        ArrayList<Object> ref = new ArrayList<>();
        ref.addAll(initial);
        ArrayList<Object> sut = ProcessItemsIndex.wrap(new ArrayList<>());
        sut.addAll(initial);
        long divergencesBefore = ProcessItemsIndex.divergencesForTest();
        String refOutcome = outcome(s, ref);
        String sutOutcome = outcome(s, sut);
        boolean same = refOutcome.equals(sutOutcome) && sameIdentity(ref, sut);
        for (Object p : probes) {
            same &= ref.contains(p) == sut.contains(p);
        }
        if (!off) {
            same &= indexConsistent((ProcessItemsIndex) sut)
                    && ProcessItemsIndex.divergencesForTest() == divergencesBefore;
        }
        expect(name + "（原版=" + refOutcome + " 索引=" + sutOutcome + "）", same);
        return ref;
    }

    private static String outcome(Scenario s, ArrayList<Object> list) {
        try {
            s.run(list);
            return "ok";
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    /** 擁有者查詢一次（待重建者先重建）後，索引的每鍵次數必須等於清單內容的 identity multiset。 */
    private static boolean indexConsistent(ProcessItemsIndex idx) {
        idx.contains(new Object());
        IdentityHashMap<Object, Integer> want = new IdentityHashMap<>();
        for (Object o : idx) {
            want.merge(o, 1, Integer::sum);
        }
        IdentityHashMap<Object, Integer> got = idx.countsForTest();
        if (got.size() != want.size()) {
            return false;
        }
        for (var entry : want.entrySet()) {
            Integer n = got.get(entry.getKey());
            if (n == null || n.intValue() != entry.getValue().intValue()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 工具

    private interface Op {
        void apply(ArrayList<Object> list);
    }

    private static void both(ArrayList<Object> ref, ArrayList<Object> sut, Op op) {
        op.apply(ref);
        op.apply(sut);
    }

    private interface Ret {
        Object apply(ArrayList<Object> list);
    }

    private static int returnMismatches;

    /** 兩邊各執行一次並比較回傳值（boolean 或被移除／被取代的元素 identity）。 */
    private static void same(ArrayList<Object> ref, ArrayList<Object> sut, Ret op) {
        Object a = op.apply(ref);
        Object b = op.apply(sut);
        if (a != b && (a == null || !a.equals(b))) {
            returnMismatches++;
        }
    }

    private static boolean sameIdentity(List<Object> x, List<Object> y) {
        if (x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            if (x.get(i) != y.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static void sameSequence(String what, List<Object> vanilla, List<Object> indexed) {
        expect(what + " vanilla=" + names(vanilla) + " indexed=" + names(indexed), sameIdentity(vanilla, indexed));
    }

    private static IsoCell cell(ArrayList<Object> items) throws Exception {
        IsoCell cell = (IsoCell) raw(IsoCell.class);
        set(cell, "processItems", items);
        set(cell, "processItemsRemove", new HashSet<InventoryItem>());
        set(cell, "processWorldItems", new ArrayList<>());
        set(cell, "processWorldItemsRemove", new HashSet<>());
        return cell;
    }

    public static class FakeItem extends InventoryItem {
        boolean done;
        int updates;

        FakeItem() {
            super(null, null, null, (String) null);
        }

        @Override
        public void update() {
            updates++;
        }

        @Override
        public boolean finishupdate() {
            return done;
        }
    }

    private static FakeItem item(boolean done) {
        try {
            FakeItem f = (FakeItem) raw(FakeItem.class);
            f.done = done;
            f.updates = 0;
            return f;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String name(Object o) {
        return o == null ? "null" : o instanceof FakeItem ? "item@" + Integer.toHexString(o.hashCode()) : "other";
    }

    private static String names(Collection<Object> c) {
        StringBuilder s = new StringBuilder("[");
        for (Object o : c) {
            s.append(s.length() > 1 ? "," : "").append(name(o));
        }
        return s.append(']').toString();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "idx pass  " : "idx FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private ProcessItemsIndexTest() {}
}
