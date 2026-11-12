package zombie.mdc;

import zombie.debug.DebugLog;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;

/**
 * W100 沒有模型名稱的武器類物品讓畫地上物品的程式 NPE（client；docs/patches.md 2dh）。
 *
 * <p>{@code ItemModelRenderer.itemHasModel} 對沒有 WorldStaticModel 的 {@code HandWeapon} 直接查
 * {@code getModelScript(item.getStaticModel())}；StaticModel 與 WeaponSprite 都沒設時名稱是 null，
 * {@code ScriptBucketCollection.getScript} 對它呼叫 {@code contains} 就 NPE。這件物品在視野內時每次重畫都丟一次。
 * 名稱是 null 時直接回 null（原版的呼叫端把 null 當成「沒有模型」，改畫圖示）；其餘照原版。
 *
 * <p>{@code -Dmdc.itemModelNullGuard=off}（或 {@code 0}）一律委派原版。
 */
public final class ItemModelNullGuard {

    static final String RAW_MODE = System.getProperty("mdc.itemModelNullGuard");
    static final boolean ENABLED = !BoneReparentFastPath.isOff(RAW_MODE);
    /** 擋下的次數（同一件物品每次重畫都算一次）。 */
    static int guarded;

    private ItemModelNullGuard() {}

    /** 取代 {@code scripts.getModelScript(name)}（itemHasModel 的兩個呼叫點）。 */
    public static ModelScript getModelScript(ScriptManager scripts, String name) {
        if (name == null && ENABLED) {
            if (guarded++ == 0) {
                log("an item without a model name is in view; drawing its icon instead of throwing");
            }
            return null;
        }
        return scripts.getModelScript(name);
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][ItemModelNullGuard] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
