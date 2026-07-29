package zombie.mdc;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import zombie.entity.util.Array;

/**
 * 尺度報告，不用 wall-clock 閾值決定成功／失敗。
 *
 * 正確性由每輪 remove 結果與最終 size 守門；時間與 allocation 只作為同機 A/B 證據輸出。
 */
public final class FastIdentityArrayRemovalBenchmark {

    private static final int[] SIZES = {1024, 2048, 4096, 8192};
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 7;
    private static final AllocationProbe ALLOCATIONS = AllocationProbe.create();

    public static void main(String[] args) {
        List<Object> warmupValues = uniqueObjects(SIZES[0]);
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            measure(Mode.VANILLA, warmupValues);
            measure(Mode.INDEXED, warmupValues);
        }

        System.out.println("benchmark INFO wall-clock 僅供比較，不作 pass/fail 閾值");
        System.out.println("benchmark INFO allocation=" + ALLOCATIONS.description());
        System.out.println("mode,N,addMedianMs,firstRemoveMedianUs,fullRemoveMedianMs,"
                + "fullNsPerEntity,doublingRatio,fullRemoveAllocatedBytesMedian");

        for (Mode mode : Mode.values()) {
            long previousFull = -1L;
            for (int size : SIZES) {
                List<Object> values = uniqueObjects(size);
                Sample[] samples = new Sample[MEASURE_ROUNDS];
                for (int round = 0; round < MEASURE_ROUNDS; round++) {
                    samples[round] = measure(mode, values);
                }

                long add = median(samples, Metric.ADD);
                long first = median(samples, Metric.FIRST_REMOVE);
                long full = median(samples, Metric.FULL_REMOVE);
                long allocated = median(samples, Metric.ALLOCATED);
                double ratio = previousFull < 0L ? Double.NaN : (double)full / previousFull;
                System.out.printf(
                        "%s,%d,%.3f,%.3f,%.3f,%.1f,%s,%s%n",
                        mode.label,
                        size,
                        add / 1_000_000.0,
                        first / 1_000.0,
                        full / 1_000_000.0,
                        (double)full / size,
                        Double.isNaN(ratio) ? "-" : String.format("%.2f", ratio),
                        allocated < 0L ? "unsupported" : Long.toString(allocated));
                previousFull = full;
            }
        }
    }

    private static Sample measure(Mode mode, List<Object> values) {
        long start = System.nanoTime();
        Array<Object> addArray = build(mode, values);
        long addNanos = System.nanoTime() - start;
        require(addArray.size == values.size(), mode.label + " add size");

        Array<Object> firstArray = build(mode, values);
        start = System.nanoTime();
        boolean firstRemoved = mode.remove(firstArray, values.get(0));
        long firstRemoveNanos = System.nanoTime() - start;
        require(firstRemoved && firstArray.size == values.size() - 1, mode.label + " first remove");

        Array<Object> fullArray = build(mode, values);
        long allocationStart = ALLOCATIONS.currentThreadBytes();
        start = System.nanoTime();
        for (Object value : values) {
            if (!mode.remove(fullArray, value)) {
                throw new AssertionError(mode.label + " full remove result");
            }
        }
        long fullRemoveNanos = System.nanoTime() - start;
        long allocationEnd = ALLOCATIONS.currentThreadBytes();
        require(fullArray.size == 0, mode.label + " final size");

        long allocated = allocationStart < 0L || allocationEnd < 0L
                ? -1L
                : allocationEnd - allocationStart;
        return new Sample(addNanos, firstRemoveNanos, fullRemoveNanos, allocated);
    }

    private static Array<Object> build(Mode mode, List<Object> values) {
        Array<Object> array = new Array<>(false, Math.max(1, values.size()));
        for (Object value : values) {
            mode.add(array, value);
        }
        return array;
    }

    private static long median(Sample[] samples, Metric metric) {
        long[] values = new long[samples.length];
        for (int i = 0; i < samples.length; i++) {
            values[i] = metric.read(samples[i]);
        }
        Arrays.sort(values);
        return values[values.length / 2];
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
                throw new AssertionError("無法建立足量互異 identity hash benchmark 資料");
            }
        }
        return values;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private enum Mode {
        VANILLA("vanilla") {
            @Override
            void add(Array<Object> array, Object value) {
                array.add(value);
            }

            @Override
            boolean remove(Array<Object> array, Object value) {
                return array.removeValue(value, true);
            }
        },
        INDEXED("indexed") {
            @Override
            void add(Array<Object> array, Object value) {
                FastIdentityArrayRemoval.add(array, value);
            }

            @Override
            boolean remove(Array<Object> array, Object value) {
                return FastIdentityArrayRemoval.remove(array, value, true);
            }
        };

        final String label;

        Mode(String label) {
            this.label = label;
        }

        abstract void add(Array<Object> array, Object value);

        abstract boolean remove(Array<Object> array, Object value);
    }

    private enum Metric {
        ADD {
            @Override
            long read(Sample sample) {
                return sample.addNanos;
            }
        },
        FIRST_REMOVE {
            @Override
            long read(Sample sample) {
                return sample.firstRemoveNanos;
            }
        },
        FULL_REMOVE {
            @Override
            long read(Sample sample) {
                return sample.fullRemoveNanos;
            }
        },
        ALLOCATED {
            @Override
            long read(Sample sample) {
                return sample.allocatedBytes;
            }
        };

        abstract long read(Sample sample);
    }

    private record Sample(long addNanos, long firstRemoveNanos, long fullRemoveNanos, long allocatedBytes) {}

    private static final class AllocationProbe {
        private final com.sun.management.ThreadMXBean bean;
        private final String description;

        static AllocationProbe create() {
            java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
            if (!(base instanceof com.sun.management.ThreadMXBean bean)
                    || !bean.isThreadAllocatedMemorySupported()) {
                return new AllocationProbe(null, "unsupported");
            }
            try {
                if (!bean.isThreadAllocatedMemoryEnabled()) {
                    bean.setThreadAllocatedMemoryEnabled(true);
                }
                return new AllocationProbe(bean, "supported");
            } catch (RuntimeException unavailable) {
                return new AllocationProbe(null, "unsupported:" + unavailable.getClass().getSimpleName());
            }
        }

        AllocationProbe(com.sun.management.ThreadMXBean bean, String description) {
            this.bean = bean;
            this.description = description;
        }

        long currentThreadBytes() {
            return bean == null ? -1L : bean.getCurrentThreadAllocatedBytes();
        }

        String description() {
            return description;
        }
    }

    private FastIdentityArrayRemovalBenchmark() {}
}
