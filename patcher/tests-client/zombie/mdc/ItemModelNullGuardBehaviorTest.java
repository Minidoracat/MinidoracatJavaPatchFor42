package zombie.mdc;

import java.util.LinkedHashMap;

import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.core.skinnedmodel.model.ItemModelRenderer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;

/**
 * W100 行為驗證（裸 JVM，classpath 以手術後的 ItemModelRenderer 優先）。
 *
 * <p>沒有 WorldStaticModel、StaticModel、WeaponSprite 的 HandWeapon（原版 Base.FISH_DEV_ITEM 的形狀）交給
 * {@code ItemModelRenderer.itemHasModel}：出貨組態回 false（呼叫端改畫圖示）；原版負對照（kill switch off）丟
 * {@code getScript} 的 NPE。有名稱的武器兩個模式都照原版查表。參數 {@code on}｜{@code off}，必須與
 * {@code -Dmdc.itemModelNullGuard} 相符。
 */
public final class ItemModelNullGuardBehaviorTest {

    public static void main(String[] args) throws Exception {
        boolean on = !(args.length > 0 && args[0].equals("off"));
        int failed = check("自驗：argv 與 -Dmdc.itemModelNullGuard 相符", ItemModelNullGuard.ENABLED == on);

        HandWeapon noModel = weapon(null);
        failed += check("前提：這把武器沒有 WorldStaticModel，getStaticModel() 是 null",
                noModel.getWorldStaticItem() == null && noModel.getStaticModel() == null);
        Boolean has = null;
        StackTraceElement thrownAt = null;
        try {
            has = ItemModelRenderer.itemHasModel(noModel);
            ItemModelRenderer.itemHasModel(noModel);
        } catch (NullPointerException e) {
            thrownAt = e.getStackTrace()[0];
        }
        if (on) {
            failed += check("沒有模型名稱的武器：itemHasModel 回 false、不丟例外；兩次呼叫計 2 次",
                    Boolean.FALSE.equals(has) && thrownAt == null && ItemModelNullGuard.guarded == 2);
        } else {
            failed += check("kill switch off（原版負對照）：NPE 丟自 ScriptBucketCollection.getScript，不計數",
                    has == null && thrownAt != null && thrownAt.getClassName().equals("zombie.scripting.ScriptBucketCollection")
                            && thrownAt.getMethodName().equals("getScript") && ItemModelNullGuard.guarded == 0);
        }

        int before = ItemModelNullGuard.guarded;
        failed += check("負對照：有名稱的武器照原版查表（沒有這個模型腳本＝false），不計數",
                !ItemModelRenderer.itemHasModel(weapon("NoSuchModel")) && ItemModelNullGuard.guarded == before
                        && ItemModelNullGuard.getModelScript(ScriptManager.instance, "NoSuchModel")
                                == ScriptManager.instance.getModelScript("NoSuchModel"));
        failed += check("負對照：null 物品回 false", !ItemModelRenderer.itemHasModel(null));

        System.out.println(failed == 0 ? "ItemModelNullGuardBehaviorTest PASS" : "ItemModelNullGuardBehaviorTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Unsafe 配置的武器：空的 modData、腳本沒有 WorldStaticModel、modelIndex -1（建構子的預設值）。 */
    static HandWeapon weapon(String staticModel) throws Exception {
        HandWeapon weapon = TestObjects.alloc(HandWeapon.class);
        TestObjects.set(InventoryItem.class, weapon, "table", new KahluaTableImpl(new LinkedHashMap<>()));
        TestObjects.set(InventoryItem.class, weapon, "scriptItem", TestObjects.alloc(Item.class));
        TestObjects.set(InventoryItem.class, weapon, "modelIndex", -1);
        TestObjects.set(InventoryItem.class, weapon, "staticModel", staticModel);
        return weapon;
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private ItemModelNullGuardBehaviorTest() {}
}
