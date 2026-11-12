package zombie.mdc;

import zombie.debug.DebugLog;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;
import zombie.util.Type;

/**
 * W100 沒有模型名稱的武器類物品讓畫地上物品的程式 NPE（client；docs/patches.md 2dh）。
 *
 * <p>{@code ItemModelRenderer.itemHasModel} 對沒有 WorldStaticModel 的 {@code HandWeapon} 直接查
 * {@code getModelScript(item.getStaticModel())}；StaticModel 與 WeaponSprite 都沒設時名稱是 null，
 * {@code ScriptBucketCollection.getScript} 對它呼叫 {@code contains} 就 NPE。這件物品在視野內時每次重畫都丟一次。
 * 名稱是 null 時直接回 null（原版的呼叫端把 null 當成「沒有模型」，改畫圖示）；其餘照原版。
 *
 * <p>W101（docs/patches.md 2di）：同一種物品拿在手上時，{@code ModelManager.addEquippedModelInstance} 把 null 的名稱交給
 * {@code addStatic}，在 {@code ScriptManager.getItemName} 就 NPE，而且每幀重試。方法開頭的 {@code Type.tryCastTo(item,
 * HandWeapon.class)} 改道到這裡：沒有模型名稱的武器回 null，原版改走非武器物品的分支（沒有模型就不掛手持模型）。
 *
 * <p>{@code -Dmdc.itemModelNullGuard=off}（W100）、{@code -Dmdc.heldModelNullGuard=off}（W101）（或 {@code 0}）各自委派原版。
 */
public final class ItemModelNullGuard {

    static final String RAW_MODE = System.getProperty("mdc.itemModelNullGuard");
    static final boolean ENABLED = !BoneReparentFastPath.isOff(RAW_MODE);
    /** 擋下的次數（同一件物品每次重畫都算一次）。 */
    static int guarded;

    static final String RAW_HELD_MODE = System.getProperty("mdc.heldModelNullGuard");
    static final boolean HELD_ENABLED = !BoneReparentFastPath.isOff(RAW_HELD_MODE);
    /** W101 擋下的次數（每次重設手持模型算一次）。 */
    static int heldGuarded;

    private ItemModelNullGuard() {}

    /** 取代 {@code Type.tryCastTo(item, HandWeapon.class)}（addEquippedModelInstance offset 4，方法內唯一）。 */
    @SuppressWarnings("unchecked")
    public static Object tryCastTo(Object item, Class<?> type) {
        Object cast = Type.tryCastTo(item, (Class<Object>) type);
        if (HELD_ENABLED && cast instanceof HandWeapon weapon && weapon.getStaticModel() == null) {
            if (heldGuarded++ == 0) {
                log("a held weapon has no model name; no hand model for it instead of throwing every frame");
            }
            return null;
        }
        return cast;
    }

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
