package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.characters.WornItems.WornItem;
import zombie.core.ImmutableColor;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * W20 ClothingSyncGuard＋ContainerIdProbe 行為驗證（獨立 JVM；模式是 static final，
 * 三組態由 build.ps1 分開驅動並以 argv 自驗，property 拼錯不得假綠）。
 * 覆蓋：(b) tintOf 三態（observe 拋 NPE 保 vanilla 語意／enforce null→white／off 直通）、
 * (c) mismatch 訊息解析與 signed diff 分佈、ThreadLocal 捕獲、(a) square-null 分解計數、
 * rate-limit 與 off 純早退。
 */
public final class ClothingSyncGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "observe";
        int wantTint = switch (want) {
            case "off" -> ClothingSyncGuard.MODE_OFF;
            case "enforce" -> ClothingSyncGuard.MODE_ENFORCE;
            default -> ClothingSyncGuard.MODE_OBSERVE;
        };
        int wantAux = "off".equals(want) ? ClothingSyncGuard.MODE_OFF : ClothingSyncGuard.MODE_OBSERVE;
        expect("property 與測試模式一致（" + want + "）",
                ClothingSyncGuard.TINT_MODE == wantTint
                && ClothingSyncGuard.MISMATCH_MODE == wantAux
                && ContainerIdProbe.MODE == (wantAux == ClothingSyncGuard.MODE_OFF
                        ? ContainerIdProbe.MODE_OFF : ContainerIdProbe.MODE_OBSERVE));

        testParseCounts();
        testTint(wantTint);
        testMismatch(wantAux);
        testContainerProbe(wantAux);

        if (failed != 0) {
            System.out.println("clothing-sync-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("clothing-sync-guard OK tint=" + ClothingSyncGuard.TINT_MODE
                + " mismatch=" + ClothingSyncGuard.MISMATCH_MODE
                + " probe=" + ContainerIdProbe.MODE);
    }

    private static void testParseCounts() {
        long[] c = ClothingSyncGuard.parseCounts(
                "Player has 14 itemVisuals but server tries to sync 15 ones");
        expect("parseCounts：local=14/wire=15", c != null && c[0] == 14 && c[1] == 15);
        long[] c2 = ClothingSyncGuard.parseCounts(
                "Player has 17 itemVisuals but server tries to sync 3 ones");
        expect("parseCounts：local=17/wire=3", c2 != null && c2[0] == 17 && c2[1] == 3);
        expect("parseCounts：格式不符回 null（TIS 改字串的訊號）",
                ClothingSyncGuard.parseCounts("some other error") == null);
    }

    private static void testTint(int mode) throws Exception {
        ClothingSyncGuard.onClothingSet(null);
        expect("onClothingSet(null) → ThreadLocal=?",
                "?".equals(ClothingSyncGuard.clothingPlayerForTest()));

        // W20-2：ctor headCall 捕獲 WornItem（nullVisual 歸因）。null 照存；描述任一環節缺
        // 一律 "?"，半初始化 item（fullType 為 null）與 Registries 未就緒的 location 都不得炸。
        ClothingSyncGuard.onItemDescription(null);
        expect("onItemDescription(null) → ThreadLocal=null、描述=?",
                ClothingSyncGuard.currentWornForTest() == null
                && "?".equals(ClothingSyncGuard.describeWornForTest(null)));
        WornItem worn = (WornItem) rawInstance(WornItem.class);
        ClothingSyncGuard.onItemDescription(worn);
        expect("onItemDescription 捕獲同一 WornItem 實例",
                ClothingSyncGuard.currentWornForTest() == worn);
        String bareDesc = ClothingSyncGuard.describeWornForTest(worn);
        expect("半初始化 WornItem（item/location 皆 null）描述不炸＝?@?", "?@?".equals(bareDesc));
        WornItem typed = (WornItem) rawInstance(WornItem.class);
        InventoryItem item = (InventoryItem) rawInstance(InventoryItem.class);
        // getFullType 有 assert fullType.equals(module+"."+type)——三欄一起設，-ea 下也成立
        for (String[] kv : new String[][]{{"fullType", "Base.Shirt_Lumberjack"}, {"module", "Base"}, {"type", "Shirt_Lumberjack"}}) {
            java.lang.reflect.Field f = InventoryItem.class.getDeclaredField(kv[0]);
            f.setAccessible(true);
            f.set(item, kv[1]);
        }
        java.lang.reflect.Field itemField = WornItem.class.getDeclaredField("item");
        itemField.setAccessible(true);
        itemField.set(typed, item);
        expect("fullType 可得時描述以 fullType 開頭（location 缺→?）",
                "Base.Shirt_Lumberjack@?".equals(ClothingSyncGuard.describeWornForTest(typed)));

        // 正常路徑：tint 非 null，三態一律回原值。
        ItemVisual visual = (ItemVisual) rawInstance(ItemVisual.class);
        visual.tint = ImmutableColor.red;
        expect("tint 非 null：回原值（三態一致）",
                ClothingSyncGuard.tintOf(visual) == ImmutableColor.red);

        // null visual：vanilla 在此 NPE。observe＝記錄後拋 NPE（保語意）；enforce＝white；
        // off＝直通（helper 內對 null receiver 呼叫 getTint 同樣 NPE）。
        long nullVisual0 = ClothingSyncGuard.nullVisualForTest();
        long repaired0 = ClothingSyncGuard.repairedForTest();
        if (mode == ClothingSyncGuard.MODE_ENFORCE) {
            expect("enforce：null visual → white、repaired+1",
                    ClothingSyncGuard.tintOf(null) == ImmutableColor.white
                    && ClothingSyncGuard.nullVisualForTest() == nullVisual0 + 1
                    && ClothingSyncGuard.repairedForTest() == repaired0 + 1);
        } else {
            boolean threw = false;
            try {
                ClothingSyncGuard.tintOf(null);
            } catch (NullPointerException e) {
                threw = true;
                if (mode == ClothingSyncGuard.MODE_OBSERVE) {
                    expect("observe：NPE 訊息帶刀名（可歸因）",
                            e.getMessage() != null && e.getMessage().contains("ClothingSyncGuard"));
                }
            }
            expect(mode == ClothingSyncGuard.MODE_OFF
                            ? "off：null visual 直通 NPE（vanilla 語意）"
                            : "observe：null visual 拋 NPE（vanilla 語意保留）", threw);
            expect("null visual 計數" + (mode == ClothingSyncGuard.MODE_OFF ? "凍結" : "+1"),
                    ClothingSyncGuard.nullVisualForTest()
                            == nullVisual0 + (mode == ClothingSyncGuard.MODE_OFF ? 0 : 1));
            expect("非 enforce：零 repaired", ClothingSyncGuard.repairedForTest() == repaired0);
        }

        // tint null（visual 非 null）：write 側第二 NPE 點的前身。enforce→white；其餘回 null 保語意。
        ItemVisual bare = (ItemVisual) rawInstance(ItemVisual.class);
        long nullTint0 = ClothingSyncGuard.nullTintForTest();
        ImmutableColor got = ClothingSyncGuard.tintOf(bare);
        if (mode == ClothingSyncGuard.MODE_ENFORCE) {
            expect("enforce：tint null → white", got == ImmutableColor.white
                    && ClothingSyncGuard.nullTintForTest() == nullTint0 + 1);
        } else if (mode == ClothingSyncGuard.MODE_OBSERVE) {
            expect("observe：tint null → 回 null（保 vanilla 語意）＋計數",
                    got == null && ClothingSyncGuard.nullTintForTest() == nullTint0 + 1);
        } else {
            expect("off：tint null → 回 null、計數凍結",
                    got == null && ClothingSyncGuard.nullTintForTest() == nullTint0);
        }
        expect("tint 路徑全程零 anomalies", ClothingSyncGuard.anomaliesForTest() == 0);
    }

    private static void testMismatch(int mode) {
        long m0 = ClothingSyncGuard.mismatchesForTest();
        long plus0 = ClothingSyncGuard.wirePlusForTest();
        long minus0 = ClothingSyncGuard.wireMinusForTest();
        long other0 = ClothingSyncGuard.wireOtherForTest();
        ClothingSyncGuard.onVisualsMismatch(DebugType.General,
                "Player has 14 itemVisuals but server tries to sync 15 ones");
        ClothingSyncGuard.onVisualsMismatch(DebugType.General,
                "Player has 15 itemVisuals but server tries to sync 14 ones");
        ClothingSyncGuard.onVisualsMismatch(DebugType.General, "unparseable format");
        if (mode == ClothingSyncGuard.MODE_OFF) {
            expect("off：mismatch 計數凍結（error 直通）",
                    ClothingSyncGuard.mismatchesForTest() == m0
                    && ClothingSyncGuard.wirePlusForTest() == plus0);
        } else {
            expect("observe：mismatch+3、plus/minus/other 各+1",
                    ClothingSyncGuard.mismatchesForTest() == m0 + 3
                    && ClothingSyncGuard.wirePlusForTest() == plus0 + 1
                    && ClothingSyncGuard.wireMinusForTest() == minus0 + 1
                    && ClothingSyncGuard.wireOtherForTest() == other0 + 1);
        }
        expect("mismatch 全程零 anomalies", ClothingSyncGuard.anomaliesForTest() == 0);
    }

    private static void testContainerProbe(int mode) throws Exception {
        long calls0 = ContainerIdProbe.callsForTest();
        long sq0 = ContainerIdProbe.squareNullForTest();
        long on0 = ContainerIdProbe.objectNullForTest();

        ItemContainer container = (ItemContainer) rawInstance(ItemContainer.class);
        IsoObject bare = (IsoObject) rawInstance(IsoObject.class);
        ContainerIdProbe.onSet(container, null);
        ContainerIdProbe.onSet(container, bare);
        IsoObject placed = (IsoObject) rawInstance(IsoObject.class);
        placed.square = (IsoGridSquare) rawInstance(IsoGridSquare.class);
        ContainerIdProbe.onSet(container, placed);

        if (mode == ClothingSyncGuard.MODE_OFF) {
            expect("off：probe 計數凍結（純早退）",
                    ContainerIdProbe.callsForTest() == calls0
                    && ContainerIdProbe.squareNullForTest() == sq0);
        } else {
            expect("observe：calls+3、objectNull+1、squareNull+1（square 非 null 不記詳情）",
                    ContainerIdProbe.callsForTest() == calls0 + 3
                    && ContainerIdProbe.objectNullForTest() == on0 + 1
                    && ContainerIdProbe.squareNullForTest() == sq0 + 1);
        }
        expect("probe 全程零 anomalies", ContainerIdProbe.anomaliesForTest() == 0);

        StackTraceElement[] stack = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("zombie.mdc.ContainerIdProbe", "onSet", "ContainerIdProbe.java", 1),
                new StackTraceElement("zombie.network.fields.ContainerID", "set", "ContainerID.java", 245),
                new StackTraceElement("zombie.network.fields.ContainerID", "set", "ContainerID.java", 156),
                new StackTraceElement("zombie.network.packets.RemoveInventoryItemFromContainerPacket",
                        "setData", "RemoveInventoryItemFromContainerPacket.java", 50),
        };
        expect("probe caller 分類：跳過兩層 ContainerID.set、落在 packet.setData",
                ContainerIdProbe.firstForeignFrame(stack)
                        .startsWith("zombie.network.packets.RemoveInventoryItemFromContainerPacket.setData"));
    }

    /** 以 serialization 建構子分配未初始化實例，避開世界依賴（W12 慣例）。 */
    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "csg pass  " : "csg FAIL  ") + what);
        if (!ok) failed++;
    }

    private ClothingSyncGuardTest() {}
}
