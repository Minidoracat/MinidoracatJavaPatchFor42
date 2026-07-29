package zombie.mdc;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.hash.TIntHashSet;
import zombie.entity.util.Array;

/**
 * 對已驗證為 unordered、identity-unique 的 Engine／EntityBucket 私有 Array
 * 維護 sidecar identity-hash index。
 *
 * State 只保存 primitive hash/index，不保存 Array 或 entity；registry 使用 weak key，
 * 因此 GameEntityManager.Reset/Init 丟棄舊 Engine 後不會被 helper 留住。
 */
public final class FastIdentityArrayRemoval {

    private static final int NO_INDEX = -1;
    private static final WeakHashMap<Array<?>, State> STATES = new WeakHashMap<>();

    // package-private primitive-only test hook；production 預設永遠為 false。
    private static boolean forcedIdentityHashEnabled;
    private static int forcedIdentityHash;

    public static <T> void add(Array<T> array, T value) {
        synchronized (array) {
            if (array.ordered || value == null) {
                invalidate(array);
                array.add(value);
                return;
            }

            State state = stateFor(array, true);
            if (state.expectedSize != array.size) {
                rebuild(state, array);
            }

            int newIndex = array.size;
            array.add(value);
            if (array.size != newIndex + 1 || array.items[newIndex] != value) {
                rebuild(state, array);
                return;
            }

            addIndex(state, value, newIndex);
            state.expectedSize = array.size;
        }
    }

    public static <T> boolean remove(Array<T> array, T value, boolean identity) {
        synchronized (array) {
            if (!identity || array.ordered || value == null) {
                invalidate(array);
                return array.removeValue(value, identity);
            }

            State state = stateFor(array, true);
            boolean rebuilt = false;
            if (state.expectedSize != array.size) {
                rebuild(state, array);
                rebuilt = true;
            }

            int hash = identityHash(value);
            while (true) {
                if (state.collisions.contains(hash)) {
                    int index = linearIndexOf(state, array, value);
                    return index >= 0 && removeAt(state, array, value, index, false);
                }

                int index = state.indices.get(hash);
                if (index >= 0 && index < array.size && array.items[index] == value) {
                    return removeAt(state, array, value, index, true);
                }

                int linearIndex = linearIndexOf(state, array, value);
                if (linearIndex < 0) {
                    return false;
                }

                if (!rebuilt) {
                    rebuild(state, array);
                    rebuilt = true;
                    hash = identityHash(value);
                    continue;
                }

                state.fallbackCount++;
                invalidate(array);
                return array.removeValue(value, true);
            }
        }
    }

    static void setForcedIdentityHashForTests(boolean enabled, int hash) {
        forcedIdentityHash = hash;
        forcedIdentityHashEnabled = enabled;
    }

    static void resetStats(Array<?> array) {
        synchronized (array) {
            State state = stateFor(array, false);
            if (state != null) {
                state.rebuildCount = 0L;
                state.linearScanCount = 0L;
                state.fastRemoveCount = 0L;
                state.fallbackCount = 0L;
            }
        }
    }

    /** rebuild、linear scan、fast remove、vanilla fallback。 */
    static long[] snapshotStats(Array<?> array) {
        synchronized (array) {
            State state = stateFor(array, false);
            return state == null
                    ? new long[4]
                    : new long[]{
                            state.rebuildCount,
                            state.linearScanCount,
                            state.fastRemoveCount,
                            state.fallbackCount
                    };
        }
    }

    private static State stateFor(Array<?> array, boolean create) {
        synchronized (STATES) {
            State state = STATES.get(array);
            if (state == null && create) {
                state = new State();
                STATES.put(array, state);
            }
            return state;
        }
    }

    /**
     * ordered 是 public；若外部將 false 切為 true，Array.hashCode 也會改變。
     * 因此失效必須以 key identity 掃描，不能只呼叫 WeakHashMap.remove(array)。
     */
    private static void invalidate(Array<?> array) {
        synchronized (STATES) {
            Iterator<Map.Entry<Array<?>, State>> iterator = STATES.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Array<?>, State> entry = iterator.next();
                if (entry.getKey() == array) {
                    iterator.remove();
                    return;
                }
            }
        }
    }

    private static void rebuild(State state, Array<?> array) {
        state.indices.clear();
        state.collisions.clear();
        for (int i = 0; i < array.size; i++) {
            Object value = array.items[i];
            if (value != null) {
                addIndex(state, value, i);
            }
        }
        state.expectedSize = array.size;
        state.rebuildCount++;
    }

    private static void addIndex(State state, Object value, int index) {
        int hash = identityHash(value);
        if (state.collisions.contains(hash)) {
            return;
        }

        int existing = state.indices.get(hash);
        if (existing != NO_INDEX) {
            state.indices.remove(hash);
            state.collisions.add(hash);
            return;
        }

        state.indices.put(hash, index);
    }

    private static int linearIndexOf(State state, Array<?> array, Object value) {
        state.linearScanCount++;
        Object[] items = array.items;
        for (int i = 0; i < array.size; i++) {
            if (items[i] == value) {
                return i;
            }
        }
        return NO_INDEX;
    }

    private static boolean removeAt(
            State state,
            Array<?> array,
            Object value,
            int index,
            boolean fastPath) {
        int lastIndex = array.size - 1;
        Object tail = array.items[lastIndex];
        int removedHash = identityHash(value);
        int tailHash = identityHash(tail);

        array.removeIndex(index);

        if (!state.collisions.contains(removedHash)) {
            state.indices.remove(removedHash);
        }
        if (index != lastIndex && !state.collisions.contains(tailHash)) {
            state.indices.put(tailHash, index);
        }
        state.expectedSize = array.size;
        if (fastPath) {
            state.fastRemoveCount++;
        }
        return true;
    }

    private static int identityHash(Object value) {
        return forcedIdentityHashEnabled ? forcedIdentityHash : System.identityHashCode(value);
    }

    private static final class State {
        final TIntIntHashMap indices = new TIntIntHashMap(16, 0.5F, 0, NO_INDEX);
        final TIntHashSet collisions = new TIntHashSet();
        int expectedSize;
        long rebuildCount;
        long linearScanCount;
        long fastRemoveCount;
        long fallbackCount;

        State() {
            // 大量 chunk unload 是連續 remove；Trove 預設縮容會在這段熱路徑反覆配置／rehash。
            // State 由 weak registry 控制生命週期，保留峰值容量直到 Array 回收即可。
            indices.setAutoCompactionFactor(0.0F);
        }
    }

    private FastIdentityArrayRemoval() {}
}
