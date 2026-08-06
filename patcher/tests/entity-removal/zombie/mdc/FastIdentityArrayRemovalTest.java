package zombie.mdc;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import zombie.entity.util.Array;

/** FastIdentityArrayRemoval 與 matching-JAR Array 的等價性及 deterministic stats 測試。 */
public final class FastIdentityArrayRemovalTest {

    public static void main(String[] args) throws Exception {
        tailSwapAndSequentialRemoval();
        eagerUniqueFastPath();
        interleavedAddRemove();
        churnKeepsTombstonesBounded();
        missingValue();
        orderedEqualsAndNullFallbacks();
        sizeMismatchRebuild();
        sameSizeReplacementRebuild();
        indexMismatchRebuild();
        duplicateIdentityFallback();
        forcedHashCollisionFallback();
        differentArraysAreNotGloballySerialized();
        weakRegistryDiagnostic();
        System.out.println("array-index OK  等價性、碰撞、fallback、stats 與 per-array lock 全數通過");
    }

    private static void tailSwapAndSequentialRemoval() {
        List<Object> values = uniqueObjects(256);
        Array<Object> vanilla = directArray(values);
        Array<Object> patched = helperArray(values);

        Object removed = values.get(37);
        require(vanilla.removeValue(removed, true)
                        == FastIdentityArrayRemoval.remove(patched, removed, true),
                "tail swap return");
        requireSame(vanilla, patched, "tail swap");

        for (Object value : values) {
            boolean expected = vanilla.removeValue(value, true);
            boolean actual = FastIdentityArrayRemoval.remove(patched, value, true);
            require(expected == actual, "sequential remove return");
            requireSame(vanilla, patched, "sequential remove");
        }
    }

    private static void eagerUniqueFastPath() {
        int n = 2048;
        List<Object> values = uniqueObjects(n);
        Array<Object> array = helperArray(values);
        FastIdentityArrayRemoval.resetStats(array);

        for (Object value : values) {
            require(FastIdentityArrayRemoval.remove(array, value, true), "unique fast remove");
        }

        long[] stats = FastIdentityArrayRemoval.snapshotStats(array);
        require(stats[0] == 0L, "unique workload rebuild=0");
        require(stats[1] == 0L, "unique workload linearScan=0");
        require(stats[2] == n, "unique workload fastRemove=N");
        require(stats[3] == 0L, "unique workload fallback=0");
    }

    private static void interleavedAddRemove() {
        Array<Object> vanilla = new Array<>(false, 16);
        Array<Object> patched = new Array<>(false, 16);
        List<Object> values = uniqueObjects(128);

        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            vanilla.add(value);
            FastIdentityArrayRemoval.add(patched, value);
            if (i >= 3 && i % 3 == 0) {
                Object old = values.get(i - 2);
                require(vanilla.removeValue(old, true)
                                == FastIdentityArrayRemoval.remove(patched, old, true),
                        "interleaved return");
            }
            requireSame(vanilla, patched, "interleaved");
        }
    }

    /**
     * 長時間穩定規模的載卸攪動下，索引 map 的 REMOVED 墓碑必須被 Trove auto-compaction 回收。
     * 停用壓實（setAutoCompactionFactor(0.0F)）時墓碑趨近 capacity−live、FREE 槽耗盡，
     * get/put 探測鏈退化成掃全表——2026-08-06 正式服主迴圈 15-25s 停頓實案的回歸鎖。
     */
    private static void churnKeepsTombstonesBounded() throws Exception {
        int live = 4096;
        int churn = 20480;
        List<Object> values = uniqueObjects(live + churn);
        Array<Object> array = new Array<>(false, live);
        for (int i = 0; i < live; i++) {
            FastIdentityArrayRemoval.add(array, values.get(i));
        }

        FastIdentityArrayRemoval.resetStats(array);
        int maxRemoved = 0;
        for (int i = 0; i < churn; i++) {
            require(FastIdentityArrayRemoval.remove(array, values.get(i), true), "churn remove");
            FastIdentityArrayRemoval.add(array, values.get(live + i));
            maxRemoved = Math.max(maxRemoved, countRemovedSlots(array));
        }

        long[] stats = FastIdentityArrayRemoval.snapshotStats(array);
        require(stats[0] == 0L, "churn rebuild=0");
        require(stats[1] == 0L, "churn linearScan=0");
        require(stats[2] == churn, "churn fastRemove=N");
        require(stats[3] == 0L, "churn fallback=0");
        require(array.size == live, "churn size stable");
        System.out.println("churn INFO   maxRemoved=" + maxRemoved + "／門檻 " + (live * 3 / 4)
                + "（壓實有效性趨勢觀測；rebuild 會清墓碑故 rebuild=0 斷言擋 vacuous pass）");
        // 下界擋儀器失效（Trove 若改 REMOVED 數值而名不變，countRemovedSlots 恆 0 會靜默轉綠）；
        // 上界 3/4×live 隱式耦合 State 的 loadFactor=0.5（壓實間隔=0.5×size→maxRemoved≈2048，
        // 實測 1644）——若調 loadFactor 需同步重推此門檻。
        require(maxRemoved > 0 && maxRemoved <= live * 3 / 4,
                "墓碑未被壓實回收或儀器失效（maxRemoved=" + maxRemoved + "）");
    }

    // 反射 handle 只解析一次（迴圈內 20480 次取樣，重複 getDeclaredField＋撲空例外是純浪費）。
    private static Field statesMapField;
    private static Field stateIndicesField;
    private static Field troveSlotsField;
    private static byte troveRemovedMark;

    /** 反射點數索引 map 的 REMOVED 槽（TPrimitiveHash._states == REMOVED 標記）。 */
    private static int countRemovedSlots(Array<?> array) throws Exception {
        if (statesMapField == null) {
            statesMapField = FastIdentityArrayRemoval.class.getDeclaredField("STATES");
            statesMapField.setAccessible(true);
        }
        Object state = ((Map<?, ?>) statesMapField.get(null)).get(array);
        require(state != null, "churn state present");
        if (stateIndicesField == null) {
            stateIndicesField = state.getClass().getDeclaredField("indices");
            stateIndicesField.setAccessible(true);
        }
        Object trove = stateIndicesField.get(state);
        if (troveSlotsField == null) {
            troveSlotsField = findInheritedField(trove.getClass(), "_states");
            troveRemovedMark = findInheritedField(trove.getClass(), "REMOVED").getByte(null);
        }
        byte[] slots = (byte[]) troveSlotsField.get(trove);
        int removed = 0;
        for (byte slot : slots) {
            if (slot == troveRemovedMark) {
                removed++;
            }
        }
        return removed;
    }

    private static Field findInheritedField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new AssertionError("trove field not found: " + name);
    }

    private static void missingValue() {
        List<Object> values = uniqueObjects(32);
        Array<Object> vanilla = directArray(values);
        Array<Object> patched = helperArray(values);
        Object absent = new Object();
        require(!vanilla.removeValue(absent, true), "vanilla missing");
        require(!FastIdentityArrayRemoval.remove(patched, absent, true), "helper missing");
        requireSame(vanilla, patched, "missing");
    }

    private static void orderedEqualsAndNullFallbacks() {
        EqualValue first = new EqualValue(7);
        EqualValue equal = new EqualValue(7);

        Array<Object> vanillaOrdered = new Array<>(true, 4);
        Array<Object> patchedOrdered = new Array<>(true, 4);
        vanillaOrdered.add(first);
        FastIdentityArrayRemoval.add(patchedOrdered, first);
        require(vanillaOrdered.removeValue(first, true)
                        == FastIdentityArrayRemoval.remove(patchedOrdered, first, true),
                "ordered fallback return");
        requireSame(vanillaOrdered, patchedOrdered, "ordered fallback");

        Array<Object> vanillaEquals = new Array<>(false, 4);
        Array<Object> patchedEquals = new Array<>(false, 4);
        vanillaEquals.add(first);
        FastIdentityArrayRemoval.add(patchedEquals, first);
        require(vanillaEquals.removeValue(equal, false)
                        == FastIdentityArrayRemoval.remove(patchedEquals, equal, false),
                "equals fallback return");
        requireSame(vanillaEquals, patchedEquals, "equals fallback");

        Array<Object> vanillaNull = new Array<>(false, 4);
        Array<Object> patchedNull = new Array<>(false, 4);
        vanillaNull.add(null);
        FastIdentityArrayRemoval.add(patchedNull, null);
        require(vanillaNull.removeValue(null, true)
                        == FastIdentityArrayRemoval.remove(patchedNull, null, true),
                "null fallback return");
        requireSame(vanillaNull, patchedNull, "null fallback");
    }

    private static void sizeMismatchRebuild() {
        Object a = new Object();
        Object b = new Object();
        Object extra = new Object();
        Array<Object> vanilla = new Array<>(false, 4);
        Array<Object> patched = new Array<>(false, 4);
        vanilla.add(a);
        vanilla.add(b);
        patchedAdd(patched, a, b);

        vanilla.add(extra);
        patched.add(extra);
        FastIdentityArrayRemoval.resetStats(patched);
        require(vanilla.removeValue(extra, true)
                        == FastIdentityArrayRemoval.remove(patched, extra, true),
                "size mismatch return");
        requireSame(vanilla, patched, "size mismatch");
        requireStats(patched, 1, 0, 1, 0, "size mismatch stats");
    }

    private static void sameSizeReplacementRebuild() {
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        Object replacement = new Object();
        Array<Object> vanilla = directArray(List.of(a, b, c));
        Array<Object> patched = helperArray(List.of(a, b, c));
        vanilla.set(1, replacement);
        patched.set(1, replacement);

        FastIdentityArrayRemoval.resetStats(patched);
        require(vanilla.removeValue(replacement, true)
                        == FastIdentityArrayRemoval.remove(patched, replacement, true),
                "same-size replacement return");
        requireSame(vanilla, patched, "same-size replacement");
        requireStats(patched, 1, 1, 1, 0, "same-size replacement stats");
    }

    private static void indexMismatchRebuild() {
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        Array<Object> vanilla = directArray(List.of(a, b, c));
        Array<Object> patched = helperArray(List.of(a, b, c));
        vanilla.swap(0, 1);
        patched.swap(0, 1);

        FastIdentityArrayRemoval.resetStats(patched);
        require(vanilla.removeValue(a, true)
                        == FastIdentityArrayRemoval.remove(patched, a, true),
                "index mismatch return");
        requireSame(vanilla, patched, "index mismatch");
        requireStats(patched, 1, 1, 1, 0, "index mismatch stats");
    }

    private static void duplicateIdentityFallback() {
        Object duplicate = new Object();
        Object other = new Object();
        Array<Object> vanilla = directArray(List.of(duplicate, other, duplicate));
        Array<Object> patched = helperArray(List.of(duplicate, other, duplicate));

        FastIdentityArrayRemoval.resetStats(patched);
        require(vanilla.removeValue(duplicate, true)
                        == FastIdentityArrayRemoval.remove(patched, duplicate, true),
                "duplicate return");
        requireSame(vanilla, patched, "duplicate");
        requireStats(patched, 0, 1, 0, 0, "duplicate stats");
    }

    private static void forcedHashCollisionFallback() {
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        Array<Object> vanilla = directArray(List.of(a, b, c));
        Array<Object> patched = new Array<>(false, 4);
        FastIdentityArrayRemoval.setForcedIdentityHashForTests(true, 12345);
        try {
            patchedAdd(patched, a, b, c);
            FastIdentityArrayRemoval.resetStats(patched);
            require(vanilla.removeValue(a, true)
                            == FastIdentityArrayRemoval.remove(patched, a, true),
                    "forced collision return");
            requireSame(vanilla, patched, "forced collision");
            requireStats(patched, 0, 1, 0, 0, "forced collision stats");
        } finally {
            FastIdentityArrayRemoval.setForcedIdentityHashForTests(false, 0);
        }
    }

    private static void differentArraysAreNotGloballySerialized() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        BlockingArray first = new BlockingArray(barrier);
        BlockingArray second = new BlockingArray(barrier);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = executor.submit(() -> FastIdentityArrayRemoval.add(first, new Object()));
            Future<?> two = executor.submit(() -> FastIdentityArrayRemoval.add(second, new Object()));
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        require(first.size == 1 && second.size == 1, "per-array concurrency result");
    }

    private static void weakRegistryDiagnostic() throws InterruptedException {
        WeakReference<?>[] references = weakReferences();
        for (int i = 0; i < 20 && (references[0].get() != null || references[1].get() != null); i++) {
            System.gc();
            byte[][] pressure = new byte[8][];
            for (int j = 0; j < pressure.length; j++) {
                pressure[j] = new byte[128 * 1024];
            }
            Thread.sleep(10L);
        }
        System.out.println("gc INFO      weak array=" + (references[0].get() == null)
                + " weak entity=" + (references[1].get() == null)
                + "（診斷資訊，deterministic gate 由結構檢查負責）");
    }

    private static WeakReference<?>[] weakReferences() {
        Array<Object> array = new Array<>(false, 4);
        Object entity = new Object();
        FastIdentityArrayRemoval.add(array, entity);
        return new WeakReference<?>[]{new WeakReference<>(array), new WeakReference<>(entity)};
    }

    private static Array<Object> directArray(List<Object> values) {
        Array<Object> array = new Array<>(false, Math.max(1, values.size()));
        for (Object value : values) {
            array.add(value);
        }
        return array;
    }

    private static Array<Object> helperArray(List<Object> values) {
        Array<Object> array = new Array<>(false, Math.max(1, values.size()));
        for (Object value : values) {
            FastIdentityArrayRemoval.add(array, value);
        }
        return array;
    }

    private static void patchedAdd(Array<Object> array, Object... values) {
        for (Object value : values) {
            FastIdentityArrayRemoval.add(array, value);
        }
    }

    private static List<Object> uniqueObjects(int count) {
        List<Object> values = new ArrayList<>(count);
        Set<Integer> hashes = new HashSet<>(count * 2);
        int attempts = 0;
        while (values.size() < count) {
            Object value = new Object();
            if (hashes.add(System.identityHashCode(value))) {
                values.add(value);
            }
            if (++attempts > count * 100) {
                throw new AssertionError("無法建立足量互異 identity hash 測試資料");
            }
        }
        return values;
    }

    private static void requireStats(
            Array<?> array,
            long rebuild,
            long linear,
            long fast,
            long fallback,
            String what) {
        long[] stats = FastIdentityArrayRemoval.snapshotStats(array);
        require(stats[0] == rebuild, what + " rebuild");
        require(stats[1] == linear, what + " linear");
        require(stats[2] == fast, what + " fast");
        require(stats[3] == fallback, what + " fallback");
    }

    private static void requireSame(Array<?> expected, Array<?> actual, String what) {
        require(expected.size == actual.size, what + " size");
        require(expected.ordered == actual.ordered, what + " ordered");
        for (int i = 0; i < expected.size; i++) {
            require(expected.items[i] == actual.items[i], what + " item[" + i + "]");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class EqualValue {
        private final int value;

        EqualValue(int value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualValue equal && equal.value == value;
        }

        @Override
        public int hashCode() {
            return value;
        }
    }

    private static final class BlockingArray extends Array<Object> {
        private final CyclicBarrier barrier;

        BlockingArray(CyclicBarrier barrier) {
            super(false, 4);
            this.barrier = barrier;
        }

        @Override
        public void add(Object value) {
            try {
                barrier.await(3, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("different arrays 被 full-operation 全域鎖串行", failure);
            }
            super.add(value);
        }
    }

    private FastIdentityArrayRemovalTest() {}
}
