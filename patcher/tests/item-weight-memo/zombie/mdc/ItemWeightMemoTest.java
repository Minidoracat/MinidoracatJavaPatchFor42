package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import zombie.inventory.InventoryItem;
import zombie.scripting.objects.Item;
import zombie.scripting.objects.ItemType;

/**
 * ItemWeightMemo 行為測試。核心不變式各自都能真的失敗：
 *
 * <ol>
 *   <li><b>factory 恰好呼叫一次</b>——用 helper 的 {@code factoryCalls} 探針直接斷言。
 *       這條是三份獨立 review（Claude／codex／grok）同時指出的缺陷的回歸鎖：早期版本用
 *       「跨越 factory 的單一 catch ＋ created 旗標」，factory 自己拋例外時會重跑一次 factory，
 *       讓 {@code Rand.Next}／Lua OnCreate／MOVEABLE 的 script 寫回執行兩次。
 *       現在 factory 在任何 try 之外，結構上不可能重試。</li>
 *   <li><b>observe 絕不保留任何 InventoryItem 實例</b>——{@code cacheSizeForTest() == 0}。
 *       第一版的整個安全論證都建立在這條上：沒有共用實例，就沒有 MOVEABLE script 寫回、
 *       ConfigureItemOnCreate、CreateInventoryItemEntity 這些等價性問題。</li>
 *   <li><b>observe 的 hits/misses 是可信的命中率預測</b>——同一型別第二次呼叫必須記成 hit
 *       且<b>不</b>同時記成 miss（否則 hits/(hits+misses) 失真，而那是決定是否啟用 on 的唯一依據）。</li>
 *   <li><b>on 模式命中時 factory 呼叫數為 0</b>。</li>
 *   <li><b>{@code store()} 真的套用五道門</b>——不只測謂詞，走正式 store 路徑驗證
 *       帶 OnCreate／itemConfig／MOVEABLE 的 item 進不了快取。</li>
 * </ol>
 *
 * <p>物件建構：{@code InventoryItem} 與 {@code Item} 的公開建構子會拉起 ZomboidFileSystem／
 * ScriptManager（測試環境無法初始化），故以 serialization 建構子分配未初始化實例再手動接線。
 * 測試環境的 {@code ScriptManager} 找不到任何 item，所以 {@code CreateItem} 一律走
 * {@code createItemInternal} 的 {@code Couldn't find item} 分支回 null 而不拋——這讓
 * {@code nullResults} 成為 {@code factoryCalls} 的獨立交叉驗證。
 *
 * <p>模式是 {@code static final}，一個 JVM 只能驗一種——期望模式由 argv[0] 傳入並自驗，
 * 避免 property 名稱打錯時「把 observe 版再跑一遍、照樣 exit 0」的假綠。
 */
public final class ItemWeightMemoTest {

    private static final String MISSING_TYPE = "Base.NoSuchItemForMemoTest";
    private static final String OTHER_TYPE = "Base.AnotherMissingItemForMemoTest";

    // statsForTest() 的索引
    private static final int HITS = 0;
    private static final int MISSES = 1;
    private static final int NULL_RESULTS = 2;
    private static final int UNCACHEABLE = 3;
    private static final int ANOMALIES = 5;
    private static final int FACTORY_CALLS = 6;

    public static void main(String[] args) throws Exception {
        String expect = args.length > 0 ? args[0] : "observe";
        require(ItemWeightMemo.modeNameForTest().equals(expect),
                "期望 mode=" + expect + "，實得 " + ItemWeightMemo.modeNameForTest()
                        + "（-Dmdc.itemWeightMemo 沒生效？）");

        cacheableGates();
        storeAppliesGates();
        nullResultIsNeverCached();
        factoryCalledExactlyOncePerMiss();

        switch (expect) {
            case "off" -> offModeIsPurePassthrough();
            case "on" -> onModeHitSkipsFactory();
            default -> observeRetainsNothingAndPredictsHitRate();
        }
        System.out.println("item-weight-memo OK  mode=" + expect
                + "：五道門／store 套門／null 不入快取／factory 恰一次／模式語意全數通過");
    }

    /** 五道門的謂詞層驗證。 */
    private static void cacheableGates() throws Exception {
        require(!ItemWeightMemo.cacheableForTest(null), "null 不得可快取");

        require(!ItemWeightMemo.cacheableForTest(rawItem()), "scriptItem 為 null 時不得可快取");

        require(ItemWeightMemo.cacheableForTest(itemWith(null, false, null, false)),
                "無 OnCreate／itemConfig／MOVEABLE／component 的 item 必須可快取，否則整把刀永遠命中不了");

        require(!ItemWeightMemo.cacheableForTest(itemWith("SomeMod.OnCreateFn", false, null, false)),
                "帶 OnCreate 的 item 不得可快取（initialiseItem 的 Lua 回呼會被跳過）");

        require(!ItemWeightMemo.cacheableForTest(itemWith(null, true, null, false)),
                "帶 itemConfig 的 item 不得可快取（ConfigureItemOnCreate 會改寫實例）");

        require(!ItemWeightMemo.cacheableForTest(itemWith(null, false, ItemType.MOVEABLE, false)),
                "MOVEABLE 不得可快取（InstanceItem:1805 會把重量寫回共享的 script 單例）");

        require(!ItemWeightMemo.cacheableForTest(itemWith(null, false, null, true)),
                "帶 component 的 item 不得可快取（CreateInventoryItemEntity 會建 ECS component 圖）");
    }

    /** store 的行為層驗證：五道門必須在正式路徑上生效，不只存在於謂詞裡。 */
    private static void storeAppliesGates() throws Exception {
        ItemWeightMemo.resetForTest();
        ItemWeightMemo.storeForTest(MISSING_TYPE, itemWith(null, false, null, false));
        require(ItemWeightMemo.cacheSizeForTest() == 1,
                "可快取的 item 必須真的進快取，實得 entries=" + ItemWeightMemo.cacheSizeForTest());

        ItemWeightMemo.resetForTest();
        ItemWeightMemo.storeForTest(MISSING_TYPE, itemWith("SomeMod.OnCreateFn", false, null, false));
        ItemWeightMemo.storeForTest(OTHER_TYPE, itemWith(null, false, ItemType.MOVEABLE, false));
        require(ItemWeightMemo.cacheSizeForTest() == 0,
                "不可快取的 item 不得進快取，實得 entries=" + ItemWeightMemo.cacheSizeForTest());
        require(ItemWeightMemo.statsForTest()[UNCACHEABLE] == 2,
                "兩個被門擋下的 item 應記 uncacheable=2，實得 " + ItemWeightMemo.statsForTest()[UNCACHEABLE]);
    }

    /**
     * script 查不到時原版會印 {@code Couldn't find item} 並回 null。負快取會讓那個診斷
     * 只出現第一次，也讓 mod 之後補註冊該型別時永遠取不到——所以 null 一律不進快取。
     */
    private static void nullResultIsNeverCached() {
        ItemWeightMemo.resetForTest();
        require(ItemWeightMemo.createItem(MISSING_TYPE) == null, "查不到的型別必須回 null");
        require(ItemWeightMemo.createItem(MISSING_TYPE) == null, "第二次仍須回 null");
        require(ItemWeightMemo.cacheSizeForTest() == 0,
                "null 不得進快取，實得 entries=" + ItemWeightMemo.cacheSizeForTest());
        if (!ItemWeightMemo.modeNameForTest().equals("off")) {
            require(ItemWeightMemo.statsForTest()[NULL_RESULTS] == 2,
                    "兩次 null 應記 nullResults=2，實得 " + ItemWeightMemo.statsForTest()[NULL_RESULTS]);
        }
    }

    /**
     * factory 恰一次：兩次 miss ⇒ factoryCalls 恰 2、anomalies 恰 0。
     * 早期版本在觀測失敗時會補呼叫一次 factory，那會讓這裡變成 3。
     */
    private static void factoryCalledExactlyOncePerMiss() {
        if (ItemWeightMemo.modeNameForTest().equals("off")) {
            return;     // off 是純轉發，刻意不計數
        }
        ItemWeightMemo.resetForTest();
        ItemWeightMemo.createItem(MISSING_TYPE);
        ItemWeightMemo.createItem(OTHER_TYPE);
        long[] s = ItemWeightMemo.statsForTest();
        require(s[FACTORY_CALLS] == 2,
                "兩次 miss 應恰好呼叫 factory 兩次，實得 " + s[FACTORY_CALLS]);
        require(s[NULL_RESULTS] == 2,
                "交叉驗證：兩次呼叫都應回 null，實得 nullResults=" + s[NULL_RESULTS]);
        require(s[ANOMALIES] == 0, "正常路徑不得有 anomalies，實得 " + s[ANOMALIES]);
    }

    /** off：連觀測都不做，計數全零、快取不動（純轉發）。 */
    private static void offModeIsPurePassthrough() throws Exception {
        ItemWeightMemo.resetForTest();
        ItemWeightMemo.seedForTest(MISSING_TYPE, rawItem());
        require(ItemWeightMemo.createItem(MISSING_TYPE) == null,
                "off 模式必須無視快取、直接回原版結果");
        long[] s = ItemWeightMemo.statsForTest();
        require(s[HITS] == 0 && s[MISSES] == 0 && s[NULL_RESULTS] == 0 && s[FACTORY_CALLS] == 0,
                "off 模式不得累計任何計數，實得 hits=" + s[HITS] + " misses=" + s[MISSES]
                        + " nullResults=" + s[NULL_RESULTS] + " factoryCalls=" + s[FACTORY_CALLS]);
    }

    /**
     * on：命中就回傳共用實例，且<b>不呼叫 factory</b>（factoryCalls 探針直接斷言）。
     * 另驗 miss → store → 下一次 hit 的完整循環。
     */
    private static void onModeHitSkipsFactory() throws Exception {
        ItemWeightMemo.resetForTest();
        InventoryItem shared = itemWith(null, false, null, false);
        ItemWeightMemo.seedForTest(MISSING_TYPE, shared);
        require(ItemWeightMemo.createItem(MISSING_TYPE) == shared, "on 模式命中必須回傳快取的同一實例");
        long[] s = ItemWeightMemo.statsForTest();
        require(s[HITS] == 1, "應記 hits=1，實得 " + s[HITS]);
        require(s[FACTORY_CALLS] == 0, "命中時不得呼叫 factory，實得 factoryCalls=" + s[FACTORY_CALLS]);
        require(s[ANOMALIES] == 0, "命中路徑不得有 anomalies，實得 " + s[ANOMALIES]);

        // miss → store → hit 的完整循環（本環境 factory 回 null，故 store 不會收，改用 storeForTest 補齊）
        ItemWeightMemo.resetForTest();
        require(ItemWeightMemo.createItem(OTHER_TYPE) == null, "miss 必須回原版結果");
        require(ItemWeightMemo.statsForTest()[FACTORY_CALLS] == 1, "miss 應呼叫 factory 一次");
        require(ItemWeightMemo.cacheSizeForTest() == 0, "factory 回 null 時不得進快取");
        ItemWeightMemo.storeForTest(OTHER_TYPE, itemWith(null, false, null, false));
        require(ItemWeightMemo.createItem(OTHER_TYPE) != null, "store 之後必須命中");
        require(ItemWeightMemo.statsForTest()[FACTORY_CALLS] == 1, "命中不得再呼叫 factory");
    }

    /**
     * observe 的兩條硬保證：
     * <ol>
     *   <li><b>零實例保留</b>——`cacheSizeForTest() == 0`，這是第一版安全論證的基礎。</li>
     *   <li><b>命中率與 on 的實際行為對齊</b>——只有「通得過五道門」的結果才算 would-hit。
     *       第二輪 review 抓到舊版把 null 與被門擋下的型別都算 hit，會讓唯一的上線決策依據灌水。</li>
     * </ol>
     * 測試環境的 factory 永遠回 null，所以 would-hit 那條分支走 {@code noteObservedForTest}
     * 直接驗記帳邏輯（與正式路徑同一段程式碼）。
     */
    private static void observeRetainsNothingAndPredictsHitRate() throws Exception {
        // (1) 走完整 createItem：null 結果不可快取 ⇒ 不算 hit、不算 miss、不進 SEEN
        ItemWeightMemo.resetForTest();
        require(ItemWeightMemo.createItem(MISSING_TYPE) == null, "第一次呼叫必須回原版結果");
        require(ItemWeightMemo.createItem(MISSING_TYPE) == null, "第二次呼叫仍須回原版結果");
        long[] s = ItemWeightMemo.statsForTest();
        require(s[UNCACHEABLE] == 2,
                "null 結果在 on 模式下永遠不會命中，兩次都該記 uncacheable，實得 " + s[UNCACHEABLE]);
        require(s[HITS] == 0,
                "null 型別不得算成 would-hit（否則命中率灌水），實得 hits=" + s[HITS]);
        require(s[MISSES] == 0,
                "不可快取的型別也不該算 miss——它根本不參與命中率，實得 misses=" + s[MISSES]);
        require(s[FACTORY_CALLS] == 2,
                "observe 每次都必須實際呼叫 factory，實得 factoryCalls=" + s[FACTORY_CALLS]);
        require(ItemWeightMemo.seenSizeForTest() == 0,
                "不可快取的型別不得進 SEEN，實得 " + ItemWeightMemo.seenSizeForTest());
        require(ItemWeightMemo.cacheSizeForTest() == 0,
                "observe 絕不保留任何 InventoryItem 實例，實得 entries=" + ItemWeightMemo.cacheSizeForTest());

        // (2) 記帳三分支：首見且可快取 ⇒ miss＋進 SEEN；重複且可快取 ⇒ would-hit；被門擋下 ⇒ uncacheable
        ItemWeightMemo.resetForTest();
        InventoryItem ok = itemWith(null, false, null, false);
        ItemWeightMemo.noteObservedForTest(MISSING_TYPE, ok, false);
        long[] a = ItemWeightMemo.statsForTest();
        require(a[MISSES] == 1 && a[HITS] == 0,
                "首見且可快取應記 misses=1 hits=0，實得 misses=" + a[MISSES] + " hits=" + a[HITS]);
        require(ItemWeightMemo.seenSizeForTest() == 1,
                "首見且可快取的型別必須進 SEEN，實得 " + ItemWeightMemo.seenSizeForTest());

        ItemWeightMemo.noteObservedForTest(MISSING_TYPE, ok, true);
        long[] b = ItemWeightMemo.statsForTest();
        require(b[HITS] == 1 && b[MISSES] == 1,
                "重複且可快取才算 would-hit 且不重複記 miss，實得 hits=" + b[HITS] + " misses=" + b[MISSES]);

        ItemWeightMemo.noteObservedForTest(OTHER_TYPE, itemWith("SomeMod.OnCreateFn", false, null, false), true);
        long[] c = ItemWeightMemo.statsForTest();
        require(c[HITS] == 1,
                "被門擋下的型別即使重複也不得算 would-hit，實得 hits=" + c[HITS]);
        require(c[UNCACHEABLE] == 1,
                "被門擋下應記 uncacheable=1，實得 " + c[UNCACHEABLE]);
        require(ItemWeightMemo.cacheSizeForTest() == 0,
                "記帳路徑全程不得寫 CACHE，實得 entries=" + ItemWeightMemo.cacheSizeForTest());
    }

    // ---- 建構未初始化實例（繞過 ZomboidFileSystem／ScriptManager）----

    /** 註：{@code sun.reflect.ReflectionFactory} 屬 jdk.unsupported，JDK 升級時這裡會先壞。 */
    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static InventoryItem rawItem() throws Exception {
        return (InventoryItem) rawInstance(InventoryItem.class);
    }

    /**
     * 造一個帶指定 script 特徵的 InventoryItem（用來逐一驗五道門）。
     *
     * <p>{@code componentScripts} 必須明確初始化：{@code GameEntityScript.hasComponents()} 是
     * {@code !this.componentScripts.isEmpty()}，serialization 建構的 script 該欄位為 null，
     * 不初始化就會在第五道門 NPE。這也順帶決定該 item 有沒有 component。
     */
    private static InventoryItem itemWith(String luaCreate, boolean withItemConfig, ItemType type,
                                          boolean withComponents) throws Exception {
        InventoryItem item = rawItem();
        Item script = (Item) rawInstance(Item.class);
        Field components = Item.class.getSuperclass().getDeclaredField("componentScripts");
        components.setAccessible(true);
        java.util.ArrayList<Object> list = new java.util.ArrayList<>();
        if (withComponents) {
            list.add(null);     // hasComponents() 只看 isEmpty()，內容不參與判定
        }
        components.set(script, list);
        if (luaCreate != null) {
            script.setLuaCreate(luaCreate);
        }
        if (withItemConfig) {
            Field f = Item.class.getDeclaredField("itemConfig");
            f.setAccessible(true);
            f.set(script, rawInstance(zombie.scripting.itemConfig.ItemConfig.class));
        }
        if (type != null) {
            script.setItemType(type);
        }
        item.setScriptItem(script);
        return item;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private ItemWeightMemoTest() {}
}
