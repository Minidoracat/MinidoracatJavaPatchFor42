package zombie.mdc;

import java.util.LinkedHashMap;

import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.core.skinnedmodel.ModelManager;
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
 * {@code getScript} 的 NPE。有名稱的武器兩個模式都照原版查表。
 *
 * <p>W101：同一把武器交給手術後的 {@code ModelManager.addEquippedModelInstance}：出貨組態回 null（不掛手持模型）；
 * 原版負對照丟 {@code ScriptManager.getItemName} 的 NPE。
 *
 * <p>參數 {@code on}｜{@code off}，必須與 {@code -Dmdc.itemModelNullGuard}、{@code -Dmdc.heldModelNullGuard} 相符（兩個一起開或一起關）。
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

        // ---- W101 ----
        failed += check("自驗：argv 與 -Dmdc.heldModelNullGuard 相符", ItemModelNullGuard.HELD_ENABLED == on);
        var equipped = ModelManager.class.getDeclaredMethod("addEquippedModelInstance", zombie.characters.IsoGameCharacter.class,
                Class.forName("zombie.core.skinnedmodel.ModelManager$ModelSlot"), InventoryItem.class, String.class,
                zombie.scripting.objects.ItemReplacement.class, boolean.class);
        equipped.setAccessible(true);
        ModelManager manager = TestObjects.alloc(ModelManager.class);
        Object model = "unset";
        StackTraceElement heldAt = null;
        try {
            model = equipped.invoke(manager, null, null, noModel, "Bip01_Prop1", null, false);
            equipped.invoke(manager, null, null, noModel, "Bip01_Prop1", null, false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            heldAt = e.getCause() instanceof NullPointerException ? e.getCause().getStackTrace()[0] : null;
        }
        if (on) {
            failed += check("W101 沒有模型名稱的武器拿在手上：addEquippedModelInstance 回 null、不丟例外；兩次呼叫計 2 次",
                    model == null && heldAt == null && ItemModelNullGuard.heldGuarded == 2);
        } else {
            failed += check("W101 kill switch off（原版負對照）：NPE 丟自 ScriptManager.getItemName，不計數",
                    heldAt != null && heldAt.getClassName().equals("zombie.scripting.ScriptManager")
                            && heldAt.getMethodName().equals("getItemName") && ItemModelNullGuard.heldGuarded == 0);
        }
        HandWeapon named = weapon("NoSuchModel");
        failed += check("W101 負對照：有名稱的武器、不是武器的物件、null 都回原版 Type.tryCastTo 的結果，不計數",
                ItemModelNullGuard.tryCastTo(named, HandWeapon.class) == named
                        && ItemModelNullGuard.tryCastTo("not a weapon", HandWeapon.class) == null
                        && ItemModelNullGuard.tryCastTo(null, HandWeapon.class) == null
                        && ItemModelNullGuard.heldGuarded == (on ? 2 : 0));

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
