package zombie.mdc;

import java.lang.reflect.Field;

import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.WrappedBuffer;

/**
 * TexturePipelineGuard 行為驗證（裸 JVM）：passthrough 恆等、水位計數與門檻分類。
 * DirectBufferAllocator 為純 static 工具類（無遊戲相依），可直接配置/釋放真實 buffer；
 * observe() 內的 DebugLog 呼叫在裸 JVM 失敗時由 helper 自行吞掉，不影響本測試斷言。
 */
public final class TexturePipelineGuardBehaviorTest {

    public static void main(String[] args) throws Exception {
        int failed = 0;

        failed += check("初始水位為零且 passthrough 一致",
                TexturePipelineGuard.bytesAllocatedObserved() == 0L
                && DirectBufferAllocator.getBytesAllocated() == 0L);

        WrappedBuffer small = DirectBufferAllocator.allocate(1024 * 1024);
        failed += check("1MB 配置後 passthrough 反映真實水位",
                TexturePipelineGuard.bytesAllocatedObserved() == DirectBufferAllocator.getBytesAllocated()
                && DirectBufferAllocator.getBytesAllocated() == 1024 * 1024);
        failed += check("低於 50MB 不計 stall",
                readLong("vanillaStallSamples") == 0L && readLong("patchedStallSamples") == 0L);

        failed += check("未超標時不在超標區間", readLong("aboveVanillaSinceNs") == 0L);

        WrappedBuffer big = DirectBufferAllocator.allocate(64 * 1024 * 1024);
        long observed = TexturePipelineGuard.bytesAllocatedObserved();
        failed += check("越過 50MB：vanilla stall 計數、patched 不計",
                observed == 65L * 1024L * 1024L
                && readLong("vanillaStallSamples") == 1L
                && readLong("patchedStallSamples") == 0L);
        failed += check("超標區間起點已記錄", readLong("aboveVanillaSinceNs") != 0L);

        WrappedBuffer huge = DirectBufferAllocator.allocate(256 * 1024 * 1024);
        observed = TexturePipelineGuard.bytesAllocatedObserved();
        failed += check("越過 256MB：vanilla 與 patched 計數都遞增",
                observed == 321L * 1024L * 1024L
                && readLong("vanillaStallSamples") == 2L
                && readLong("patchedStallSamples") == 1L);
        failed += check("高水位追蹤到峰值", readLong("highWaterBytes") == 321L * 1024L * 1024L);

        small.dispose();
        big.dispose();
        huge.dispose();
        failed += check("全部 dispose 後水位歸零",
                TexturePipelineGuard.bytesAllocatedObserved() == 0L);
        failed += check("回到門檻下後超標區間重置", readLong("aboveVanillaSinceNs") == 0L);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("TexturePipelineGuard 行為驗證全數通過");
    }

    static long readLong(String field) throws Exception {
        Field f = TexturePipelineGuard.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.getLong(null);
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "behavior OK   " : "behavior FAIL ") + what);
        return ok ? 0 : 1;
    }

    private TexturePipelineGuardBehaviorTest() {}
}
