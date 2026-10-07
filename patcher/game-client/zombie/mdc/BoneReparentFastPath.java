package zombie.mdc;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;

import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.debug.DebugLog;

/**
 * W55 {@code isBoneReparented} 快速路徑（client；docs/patches.md 2bs）。
 *
 * <p>AnimationPlayer.updateMultiTrackBoneTransformsInternal 對每根骨頭呼叫一次
 * {@code isBoneReparented(I)Z}，原版每次都 {@code Integer.valueOf} 加 {@code Lambda.predicate}
 * （從池配置）再掃 {@code reparentedBoneBindings}。清單是空的（車輛、殭屍、多數角色）時原版一定回
 * false，本方法直接回 false；清單非空時照原版呼叫。結果與原版逐位相同。
 *
 * <p>{@code -Dmdc.boneReparentFast=off}（或 {@code 0}）一律委派原版。欄位找不到（TIS 改了結構）時記一行並委派原版。
 */
public final class BoneReparentFastPath {

    static final String RAW_MODE = System.getProperty("mdc.boneReparentFast");
    static final boolean ENABLED = !isOff(RAW_MODE);
    private static final VarHandle BINDINGS = bindings();

    static {
        log("active=" + (ENABLED && BINDINGS != null)
                + (RAW_MODE == null ? "" : " (-Dmdc.boneReparentFast=" + RAW_MODE + ")"));
    }

    private BoneReparentFastPath() {}

    static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String mode = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return mode.equals("off") || mode.equals("0");
    }

    private static VarHandle bindings() {
        try {
            return MethodHandles.privateLookupIn(AnimationPlayer.class, MethodHandles.lookup())
                    .findVarHandle(AnimationPlayer.class, "reparentedBoneBindings", ArrayList.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("field lookup failed (" + e + "); vanilla isBoneReparented");
            return null;
        }
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][BoneReparentFastPath] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照原版運作。
        }
    }

    /** 快速路徑是否生效（開關打開且欄位找得到）。 */
    static boolean active() {
        return ENABLED && BINDINGS != null;
    }

    /** 取代 {@code player.isBoneReparented(boneIdx)}（updateMultiTrackBoneTransformsInternal offset 115）。 */
    public static boolean isBoneReparented(AnimationPlayer player, int boneIdx) {
        if (ENABLED && BINDINGS != null && ((ArrayList<?>) BINDINGS.get(player)).isEmpty()) {
            return false;
        }
        return player.isBoneReparented(boneIdx);
    }
}
