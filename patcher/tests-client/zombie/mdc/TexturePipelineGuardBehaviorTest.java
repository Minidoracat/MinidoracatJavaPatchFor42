package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.WrappedBuffer;

/**
 * TexturePipelineGuard 行為驗證（裸 JVM）。
 * 第一段用真實 DirectBufferAllocator 配置驗 passthrough 恆等與 50MB 門檻跨越
 * （1MB＋64MB，成本低）；第二段以反射直呼 observe(long) 合成水位值＋操縱時間欄位，
 * 覆蓋 4GB 門檻分類、floor 窗最低值、periodic 重置與行優先序（stall＞hwm＞periodic）
 * ——不需大額真實 native 配置（codex 差量審查要求）。
 * observe() 內的 DebugLog 呼叫在裸 JVM 失敗時由 helper 外層吞掉，不影響本測試。
 */
public final class TexturePipelineGuardBehaviorTest {

    static final long MB = 1024L * 1024L;

    public static void main(String[] args) throws Exception {
        int failed = 0;

        // ---- 第一段：真實配置 ----
        failed += check("初始水位為零且 passthrough 一致",
                TexturePipelineGuard.bytesAllocatedObserved() == 0L
                && DirectBufferAllocator.getBytesAllocated() == 0L);

        WrappedBuffer small = DirectBufferAllocator.allocate((int)MB);
        failed += check("1MB 配置後 passthrough 反映真實水位",
                TexturePipelineGuard.bytesAllocatedObserved() == DirectBufferAllocator.getBytesAllocated()
                && DirectBufferAllocator.getBytesAllocated() == MB);
        failed += check("低於 50MB 不計 stall",
                readLong("vanillaStallSamples") == 0L && readLong("patchedStallSamples") == 0L);
        failed += check("未超標時不在超標區間", readLong("aboveVanillaSinceNs") == 0L);

        WrappedBuffer big = DirectBufferAllocator.allocate((int)(64L * MB));
        long observed = TexturePipelineGuard.bytesAllocatedObserved();
        failed += check("越過 50MB：vanilla stall 計數、patched 不計",
                observed == 65L * MB
                && readLong("vanillaStallSamples") == 1L
                && readLong("patchedStallSamples") == 0L);
        failed += check("超標區間起點已記錄", readLong("aboveVanillaSinceNs") != 0L);

        small.dispose();
        big.dispose();
        failed += check("全部 dispose 後水位歸零",
                TexturePipelineGuard.bytesAllocatedObserved() == 0L);
        failed += check("回到門檻下後超標區間重置", readLong("aboveVanillaSinceNs") == 0L);

        // ---- 第二段：合成水位（反射 observe(long)，不做真實配置）----
        long now = System.nanoTime();
        setLong("floorWindowBytes", Long.MAX_VALUE);
        setLong("highWaterBytes", 0L);
        setLong("nextHwmReportBytes", 8L * MB);
        setLong("lastStallLogNs", now);
        setLong("lastPeriodicLogNs", now);

        String line = obs(10L * MB);
        failed += check("hwm 跨階發行 hwm 行",
                line != null && line.endsWith("hwmBytes=" + 10L * MB));

        line = obs(5L * MB);
        failed += check("無事件時不發行且 floor 追蹤窗內最低值",
                line == null && readLong("floorWindowBytes") == 5L * MB);

        setLong("lastPeriodicLogNs", now - 61_000_000_000L);
        line = obs(6L * MB);
        failed += check("periodic 行帶窗內最低 floor 並重置窗",
                line != null && line.contains("periodic")
                && line.contains("floorBytes=" + 5L * MB)
                && readLong("floorWindowBytes") == 6L * MB);

        setLong("lastStallLogNs", now - 6_000_000_000L);
        setLong("lastPeriodicLogNs", now - 61_000_000_000L);
        line = obs(60L * MB);
        failed += check("stall 行優先於 hwm/periodic 且 periodic tick 仍重置 floor 窗",
                line != null && line.contains("aboveVanillaMs=")
                && line.contains("vanillaStallSamples=2")
                && !line.contains("periodic")
                && readLong("floorWindowBytes") == 60L * MB);

        obs(5L * 1024L * MB);
        failed += check("合成越過 4GB：vanilla 與 patched（標準 effective 門檻）計數都遞增",
                readLong("vanillaStallSamples") == 3L
                && readLong("patchedStallSamples") == 1L);

        // ---- lowmem 對照：同一水位、effective 門檻＝50MB → patched 計數也遞增 ----
        // （effective 門檻烘進入口而非版本字串——lowmem 包的 stall 分類不說謊）
        setLong("lastStallLogNs", System.nanoTime());
        obsLow(60L * MB);
        failed += check("lowmem 入口：50MB<bytes<4GB 也計 patched（effective=50MB）",
                readLong("vanillaStallSamples") == 4L
                && readLong("patchedStallSamples") == 2L);
        long std = TexturePipelineGuard.bytesAllocatedObserved();
        long low = TexturePipelineGuard.bytesAllocatedObservedLowMem();
        failed += check("兩入口 passthrough 一致（真實水位 0）", std == 0L && low == 0L
                && readLong("aboveVanillaSinceNs") == 0L);

        obs(0L);
        failed += check("合成回到門檻下後超標區間重置", readLong("aboveVanillaSinceNs") == 0L);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("TexturePipelineGuard 行為驗證全數通過");
    }

    static String obs(long bytes) throws Exception {
        return obsWith(bytes, TexturePipelineGuard.PATCHED_LIMIT_BYTES);
    }

    static String obsLow(long bytes) throws Exception {
        return obsWith(bytes, TexturePipelineGuard.VANILLA_LIMIT_BYTES);
    }

    static String obsWith(long bytes, long effectiveLimit) throws Exception {
        Method m = TexturePipelineGuard.class.getDeclaredMethod("observe", long.class, long.class);
        m.setAccessible(true);
        return (String)m.invoke(null, bytes, effectiveLimit);
    }

    static long readLong(String field) throws Exception {
        Field f = TexturePipelineGuard.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.getLong(null);
    }

    static void setLong(String field, long value) throws Exception {
        Field f = TexturePipelineGuard.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setLong(null, value);
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "behavior OK   " : "behavior FAIL ") + what);
        return ok ? 0 : 1;
    }

    private TexturePipelineGuardBehaviorTest() {}
}
