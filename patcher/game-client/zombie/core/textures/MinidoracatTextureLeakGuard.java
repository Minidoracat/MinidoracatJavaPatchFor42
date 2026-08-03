package zombie.core.textures;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.WrappedBuffer;
import zombie.core.znet.SteamFriends;

/**
 * Client 貼圖 DirectBuffer 洩漏根治 helper v2.0（invisible-entities 根因鏈第二環，
 * 四路 retention trace＋對抗評審定罪；docs/patches.md 2j）。
 *
 * 定罪結論：1096MB 洩漏地板＝(1) APNG frames 永不釋放（ImageData.dispose 只放
 * data＋mipMaps，40-60%）＋(2) getData() 固定 64MB fallback 與 mip-flag APNG 的
 * AIOOBE 跳過 dispose（20-35%）＋reload 競態等次要項。本類承載第一波四刀
 * （S1/S2/S4/S6）的 helper 邏輯；**必須位於 zombie.core.textures package——
 * ImageData.frames 是 package-private**。
 *
 * 落地鐵則（對抗評審抓到的最重要約束）：WrappedBuffer 對雙重 dispose 拋
 * IllegalStateException——所有釋放一律過 isDisposed() 冪等閘。觀測紀律與
 * TexturePipelineGuard 相同：非 fatal 例外吞掉（fallback 到原版行為），
 * fatal 三件套照拋。
 */
public final class MinidoracatTextureLeakGuard {

    private MinidoracatTextureLeakGuard() {}

    /**
     * S1（root fix 本體）：ImageData.dispose() 方法頭呼叫——補原版漏掉的 frames 釋放。
     * 安全論證：frames 的讀取者全 codebase 只有 AnimatedTextureID.setImageData
     * （在任何 dispose 之前執行、轉移後 frame.data=null，本方法的 null 閘使其成 no-op）
     * 與 ImageData(ImageDataFrame) ctor；一般貼圖管線的幀 buffer 無人上傳、無人引用。
     * 冪等：重複呼叫安全（isDisposed 閘＋清空後 frames.isEmpty()）。
     */
    public static void disposeFrames(ImageData img) {
        try {
            ArrayList<ImageDataFrame> frames = img.frames;
            for (int i = 0; i < frames.size(); i++) {
                ImageDataFrame f = frames.get(i);
                if (f == null) {
                    continue;
                }
                MipMapLevel lvl = f.data;
                if (lvl != null) {
                    if (!lvl.isDisposed()) {
                        lvl.dispose();
                    }
                    f.data = null;
                }
            }
            frames.clear();
        } catch (Throwable t) {
            rethrowFatal(t);
        }
    }

    /**
     * S2：getData()/getMipMapCount() 方法頭呼叫——data 為空時以實際尺寸配置，
     * 取代原版固定 67108864（64MB）fallback；APNG（frames-only 建構）主層以第一幀
     * 內容填充——原版此類貼圖上傳全零＝隱形，本修補使其顯示第一幀（嚴格不劣化）。
     * 效果鏈：data 就位後 getData 的 64MB 分支成死碼；getMipMapCount 回真值，
     * mip-flag APNG 不再走 getMipMapData(-1) 的 AIOOBE（該例外原本會跳過
     * generateHwId 尾端的 dispose，一次漏 64MB＋mip 鏈＋全部幀）。
     * 壞檔（尺寸非正）不動——退回原版行為。
     */
    public static void ensureData(ImageData img) {
        try {
            if (img.data != null) {
                return;
            }
            int w = img.getWidthHW();
            int h = img.getHeightHW();
            if (w <= 0 || h <= 0) {
                return;
            }
            MipMapLevel lvl = new MipMapLevel(w, h);
            // 先提交再填充（codex 審查修正）：之後任何失敗都只是主層維持全零＝原版行為，
            // 不會洩漏未提交的 lvl、也不會落回 64MB 分支
            img.data = lvl;
            ArrayList<ImageDataFrame> frames = img.frames;
            if (!frames.isEmpty()) {
                ImageDataFrame f0 = frames.get(0);
                if (f0 != null && f0.data != null && !f0.data.isDisposed()
                        && f0.widthHw == w && f0.heightHw == h) {
                    // duplicate() 不動原 buffer 的 position/limit
                    ByteBuffer src = f0.data.getBuffer().duplicate();
                    src.clear();
                    ByteBuffer dst = lvl.getBuffer();
                    dst.clear();
                    if (src.remaining() == dst.remaining()) {
                        dst.put(src);
                    }
                    dst.clear();
                }
            }
        } catch (Throwable t) {
            rethrowFatal(t);
        }
    }

    /**
     * S4：ImageData.createSteamAvatar 的逐語意重實作＋失敗路徑補 dispose
     * （原版 avatarWidth<=0 直接 return null，65536 bytes 永久滯留且 UI 重試即重漏）。
     * 呼叫點：TextureID.createSteamAvatar 內的 INVOKESTATIC redirect（全 codebase 唯一）。
     * SteamFriends.CreateSteamAvatar 為同步複製語意；例外時釋放後照原版傳播。
     */
    public static ImageData createSteamAvatarFixed(long steamID) {
        WrappedBuffer data = DirectBufferAllocator.allocate(65536);
        int avatarWidth;
        try {
            avatarWidth = SteamFriends.CreateSteamAvatar(steamID, data.getBuffer());
        } catch (Throwable t) {
            disposeQuiet(data);
            throw t;
        }
        if (avatarWidth <= 0) {
            disposeQuiet(data);
            return null;
        }
        int avatarHeight = data.getBuffer().position() / (avatarWidth * 4);
        data.getBuffer().flip();
        return new ImageData(avatarWidth, avatarHeight, data);
    }

    /**
     * S6（防禦性堵口）：TextureID.freeMemory() 方法頭呼叫——原版只斷引用不 dispose
     * （「假釋放」footgun）。42.20 全 codebase 零呼叫者（唯一鏈 setData(null) 亦零呼叫者），
     * 立即冪等 dispose 即可；若未來出現與上傳併發的呼叫者，最壞情境＝該貼圖上傳讀到
     * 已釋放 buffer 拋 ISE 被 render queue 捕捉落 log——有界、不 crash。
     */
    public static void onFreeMemory(TextureID tid) {
        try {
            ImageData old = tid.getImageData();
            if (old != null) {
                old.dispose();   // dispose 冪等（欄位 null 閘＋S1 的 isDisposed 閘）
            }
        } catch (Throwable t) {
            rethrowFatal(t);
        }
    }

    private static void disposeQuiet(WrappedBuffer wb) {
        try {
            if (wb != null && !wb.isDisposed()) {
                wb.dispose();
            }
        } catch (Throwable t) {
            rethrowFatal(t);
        }
    }

    private static void rethrowFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof ThreadDeath || t instanceof LinkageError) {
            throw (Error)t;
        }
        // 其他一律吞掉——helper 失敗時退回原版行為，不放大故障
    }
}
