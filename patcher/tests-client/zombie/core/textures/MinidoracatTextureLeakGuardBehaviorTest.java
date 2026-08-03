package zombie.core.textures;

import java.nio.ByteBuffer;

import zombie.core.utils.DirectBufferAllocator;

/**
 * v2.0 洩漏根治行為驗證（裸 JVM，classpath 上 dist-client 的 patched class 優先）。
 * 與 helper 同套件——直接存取 package-private 的 frames，不需反射。
 * 尺寸期望值一律由 getWidthHW()/getHeightHW() 動態推導（不假設 HW 取整規則）。
 */
public final class MinidoracatTextureLeakGuardBehaviorTest {

    public static void main(String[] args) throws Exception {
        int failed = 0;
        failed += check("基線水位為零", DirectBufferAllocator.getBytesAllocated() == 0L);

        // ---- S1：frames 對帳（原版 dispose 恆漏幀 buffer）----
        ImageData img = new ImageData(4, 4);
        long mainBytes = (long)img.getWidthHW() * img.getHeightHW() * 4L;
        ImageDataFrame f = new ImageDataFrame();
        f.widthHw = img.getWidthHW();
        f.heightHw = img.getHeightHW();
        f.data = new MipMapLevel(f.widthHw, f.heightHw);
        img.frames.add(f);
        failed += check("主層＋一幀配置後水位＝2×主層",
                DirectBufferAllocator.getBytesAllocated() == mainBytes * 2L);
        img.dispose();
        failed += check("dispose 後 frames 一併歸零（原版恆漏幀 buffer）",
                DirectBufferAllocator.getBytesAllocated() == 0L);
        boolean idempotent = true;
        try {
            img.dispose();
        } catch (Throwable t) {
            idempotent = false;
        }
        failed += check("dispose 冪等（重複釋放不拋例外）", idempotent);

        // ---- S2：ensureData 以實際尺寸配置（取代固定 64MB）----
        ImageData img2 = new ImageData(8, 8);
        long main2 = (long)img2.getWidthHW() * img2.getHeightHW() * 4L;
        img2.data.dispose();
        img2.data = null;
        MipMapLevel lvl = img2.getData();
        failed += check("getData 以實際尺寸配置（原版此處固定配 67108864）",
                lvl != null && DirectBufferAllocator.getBytesAllocated() == main2);
        failed += check("getMipMapCount 回真值（原版 data==null 恆回 0＝AIOOBE 家族源頭）",
                img2.getMipMapCount() > 0);
        img2.dispose();

        // ---- S2：APNG 主層以第一幀內容填充（原版全零＝隱形）----
        ImageData img3 = new ImageData(4, 4);
        img3.data.dispose();
        img3.data = null;
        ImageDataFrame f3 = new ImageDataFrame();
        f3.widthHw = img3.getWidthHW();
        f3.heightHw = img3.getHeightHW();
        f3.data = new MipMapLevel(f3.widthHw, f3.heightHw);
        int frameBytes = f3.widthHw * f3.heightHw * 4;
        ByteBuffer fb = f3.data.getBuffer();
        fb.clear();
        for (int i = 0; i < frameBytes; i++) {
            fb.put((byte)(i % 251 + 1));
        }
        img3.frames.add(f3);
        ByteBuffer got = img3.getData().getBuffer();
        boolean same = true;
        for (int i = 0; i < frameBytes; i++) {
            if (got.get(i) != (byte)(i % 251 + 1)) {
                same = false;
                break;
            }
        }
        failed += check("APNG 主層填充第一幀內容且不動幀 buffer", same);
        img3.dispose();
        failed += check("全部 dispose 後水位歸零（幀＋主層無殘留）",
                DirectBufferAllocator.getBytesAllocated() == 0L);

        // ---- 壞檔退回原版行為：尺寸非正時 ensureData 不介入 ----
        ImageData img4 = new ImageData(4, 4);
        img4.data.dispose();
        img4.data = null;
        java.lang.reflect.Field wf = ImageData.class.getDeclaredField("widthHw");
        wf.setAccessible(true);
        wf.setInt(img4, 0);
        failed += check("壞檔（widthHw=0）ensureData 不介入、getMipMapCount 維持原版回 0",
                img4.getMipMapCount() == 0);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("MinidoracatTextureLeakGuard 行為驗證全數通過");
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "behavior OK   " : "behavior FAIL ") + what);
        return ok ? 0 : 1;
    }

    private MinidoracatTextureLeakGuardBehaviorTest() {}
}
