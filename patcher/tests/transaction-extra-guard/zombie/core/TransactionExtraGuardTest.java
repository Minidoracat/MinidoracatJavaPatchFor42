package zombie.core;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.Clothing;
import zombie.scripting.objects.Item;

/**
 * W104 衣物換型種類驗證（MdcTransactionExtraGuard）行為驗證。直接呼叫 helper 的入口（物品交易的兩個改道入口、換型動作的
 * {@code refuseAction}，以及經 {@code NetTimedActionGuard.protectedCall} 的一筆；手術有沒有接上由 SmokeCheck
 * 的逐指令比對負責）。測試環境沒有物品腳本，原版 {@code CreateItem} 找不到種類時印「Couldn't find item 種類」再丟 NPE：
 * 用這一行當「原版真的去建了」的證據（替身的 {@code getVisual} 回 null，讓原版走得到那一步）。
 * argv：{@code on}（出貨組態）／{@code observe}／{@code off}（kill switch）／{@code vanilla}（classpath 不含 dist，原版負對照）／
 * {@code logthrow}（on，日誌串流一寫就拋例外：照做、Error 照常外拋），自驗。
 */
public final class TransactionExtraGuardTest {

    private static final String TAG = "[MinidoracatJavaPatch][TransactionExtraGuard]";
    private static final String GUARD = "zombie.core.MdcTransactionExtraGuard";
    private static final String VANILLA_MISS = "Couldn't find item ";
    private static final String FORGED = "Base.Bag_ALICEpack";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    private static int failed;
    private static boolean oom;
    private static ByteArrayOutputStream log;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "on";
        if ("vanilla".equals(mode)) {
            boolean guardAbsent;
            try {
                Class.forName(GUARD);
                guardAbsent = false;
            } catch (ClassNotFoundException e) {
                guardAbsent = true;
            }
            expect("自驗：vanilla＝classpath 沒有 helper", guardAbsent);
            log = captureLog();
            Throwable t = null;
            try {
                InventoryItemFactory.CreateItem(hat(), FORGED);
            } catch (Throwable e) {
                t = e;
            }
            expect("原版負對照：不在清單的種類照樣去建（" + rows(VANILLA_MISS + FORGED) + "，" + t + "）", rows(VANILLA_MISS + FORGED).size() == 1);
            finish(mode);
            return;
        }
        int want = "observe".equals(mode) ? 1 : "off".equals(mode) ? 0 : 2;
        expect("自驗：argv=" + mode + " 與實際模式 " + guardInt("MODE") + " 相符", guardInt("MODE") == want);
        if ("logthrow".equals(mode)) {
            logThrow();
            return;
        }
        boolean on = "on".equals(mode);
        boolean observe = "observe".equals(mode);
        log = captureLog();

        InventoryItem hat = hat();
        Method allowed = Class.forName(GUARD).getDeclaredMethod("allowed", InventoryItem.class, String.class);
        allowed.setAccessible(true);
        expect("允許：清單裡的短名、短名加原物的 module、清單裡的全名、原物自己的種類",
                (boolean) allowed.invoke(null, hat, "Hat_Reverse") && (boolean) allowed.invoke(null, hat, "Base.Hat_Reverse")
                        && (boolean) allowed.invoke(null, hat, "Other.Hood") && (boolean) allowed.invoke(null, hat, "Base.Hat"));
        expect("不允許：不在清單的種類、別的 module 加清單短名、已經帶 module 的清單項再補一次 module、空字串；腳本沒有換型清單時只允許自己的種類",
                !(boolean) allowed.invoke(null, hat, FORGED) && !(boolean) allowed.invoke(null, hat, "Evil.Hat_Reverse")
                        && !(boolean) allowed.invoke(null, hat, "Base.Other.Hood")
                        && !(boolean) allowed.invoke(null, hat, "") && !(boolean) allowed.invoke(null, item(11, null), FORGED)
                        && (boolean) allowed.invoke(null, item(11, null), "Base.Hat"));
        // observe 那個 JVM 帶 -Dmdc.transactionExtraAllow；其餘不帶（負對照）
        expect("自驗：只有 observe 帶放行清單（" + System.getProperty("mdc.transactionExtraAllow") + "）",
                (System.getProperty("mdc.transactionExtraAllow") != null) == observe);
        expect("服主放行的那一對：有帶才允許、反方向與沒列的照樣不允許",
                (boolean) allowed.invoke(null, hat, "Mod.Hat_Hidden") == observe && !(boolean) allowed.invoke(null, hat, "Mod.Hat_Other")
                        && !(boolean) allowed.invoke(null, hat, "Base.Hat:Mod.Hat_Hidden"));

        Outcome legal = create(hat, "Base.Hat_Reverse");
        expect("清單裡的種類：照原版去建（" + legal + "）、零 refused 行", legal.vanilla && legal.result != hat && rows(TAG + " refused ").isEmpty());

        IsoPlayer player = alloc(IsoPlayer.class);
        player.onlineId = 7;
        player.username = "tester";
        Throwable hands = null;
        try {
            Class.forName(GUARD).getMethod("removeFromHands", IsoPlayer.class, InventoryItem.class).invoke(null, player, hat);
        } catch (InvocationTargetException e) {
            hands = e.getCause();
        }
        expect("removeFromHands 入口照原版呼叫、不拋（" + hands + "）", hands == null);
        Outcome forged = create(hat, FORGED);
        List<String> refused = rows(TAG + " refused ");
        if (on) {
            expect("on：不在清單的種類不建、回傳原物（" + forged + "）", !forged.vanilla && forged.result == hat && forged.thrown == null);
        } else {
            expect(mode + "：不在清單的種類照原版去建（" + forged + "）", forged.vanilla && forged.result != hat);
        }
        expect("refused 行數＝" + (on || observe ? 1 : 0) + "（" + refused + "）", refused.size() == (on || observe ? 1 : 0));
        if (on || observe) {
            expect("log：哪條路、原物種類與 ID、種類、玩家、動作、計數（" + refused.get(0) + "）",
                    refused.get(0).contains(TAG + " refused via=transaction item=Base.Hat id=10 extra=" + FORGED + " owner=7:tester action="
                            + (on ? "refused" : "logged") + " reason=type refused=1 suppressed=0 anomalies=0."));
            create(hat, "Evil type=x\nid=1" + "x".repeat(100));
            String second = rows(TAG + " refused ").get(1);
            expect("沒有先經過 removeFromHands 時玩家記 ?（上一筆的不沿用）；種類字串清過並截短（" + second + "）",
                    second.contains(" owner=? ") && second.contains(" extra=Evil?type?x?id?1xxx") && !second.contains("Evil type")
                            && second.length() < 400);
            Outcome broken = create(item(12, null, true), FORGED);
            expect("判斷出例外：" + (on ? "不建、回傳原物" : "照原版") + "、不外拋自己的例外、記一行 anomaly（" + broken + "）",
                    (on ? !broken.vanilla && broken.thrown == null : broken.vanilla)
                            && rows(TAG + " anomaly reason=NullPointerException action=" + (on ? "refused" : "vanilla") + " ").size() == 1
                            && guardLong("anomaliesForTest") == 1 && guardLong("refusedForTest") == 2);
            expect("首次生效行恰 1 行、寫出放行清單的筆數", rows(TAG + " 首次生效 mode=" + mode + " extraAllow=" + (observe ? 2 : 0) + ".").size() == 1);
        } else {
            expect("off：零計數、首次生效行寫 mode=off", guardLong("refusedForTest") == 0 && rows(TAG + " 首次生效 mode=off extraAllow=0.").size() == 1);
        }

        // 換型動作那條路：NetTimedAction.parse 要建構 Lua 動作前問一次。類別表照原版 derive 的形狀（metatable 是父類別）。
        LuaManager.env = new KahluaTableImpl(new LinkedHashMap<>());
        KahluaTable baseAction = classTable("ISBaseTimedAction", null);
        KahluaTable extraAction = classTable("ISClothingExtraAction", baseAction);
        KahluaTable modAction = classTable("ModExtraAction", extraAction);
        KahluaTable wearAction = classTable("ISWearClothing", baseAction);
        long refusedBefore = guardLong("refusedForTest");
        int actionRowsBefore = rows(TAG + " refused via=action ").size();
        expect("動作：清單裡的種類放行；不是換型動作的類別不管；空參數不管",
                !refuseAction(extraAction, player, hat, "Base.Hat_Reverse") && !refuseAction(wearAction, player, hat, FORGED)
                        && !refuseAction() && !refuseActionNull() && !refuseAction("ISClothingExtraAction", player, hat, FORGED));
        boolean[] bad = {
                refuseAction(extraAction, player, hat, FORGED),          // 不在清單的種類
                refuseAction(modAction, player, hat, FORGED),            // 衍生類別一樣查
                refuseAction(extraAction, player, null, FORGED),         // 物品解析不到
                refuseAction(extraAction, player, hat, 123.0),           // 種類不是字串
                refuseAction(extraAction),                               // 缺參數
        };
        List<String> actionRows = rows(TAG + " refused via=action ");
        boolean allBad = true;
        for (boolean b : bad) {
            allBad &= b == on;
        }
        expect("動作：不在清單的種類、衍生類別、物品解析不到、種類不是字串、缺參數——" + (on ? "都拒絕" : "都不擋")
                        + "；refused 行多 " + (on || observe ? 5 : 0) + "（實際 " + (actionRows.size() - actionRowsBefore) + "）",
                allBad && actionRows.size() - actionRowsBefore == (on || observe ? 5 : 0)
                        && guardLong("refusedForTest") - refusedBefore == (on || observe ? 5 : 0));
        if (on || observe) {
            expect("動作 log：種類、玩家、動作、原因（" + actionRows.get(actionRowsBefore) + "）；物品解析不到時記 item=? id=? reason=item，種類不是字串記 reason=args",
                    actionRows.get(actionRowsBefore).contains(" refused via=action item=Base.Hat id=10 extra=" + FORGED + " owner=7:tester action="
                            + (on ? "refused" : "logged") + " reason=type ")
                            && actionRows.get(actionRowsBefore + 2).contains(" item=? id=? extra=" + FORGED + " ")
                            && actionRows.get(actionRowsBefore + 2).contains(" reason=item ")
                            && actionRows.get(actionRowsBefore + 3).contains(" reason=args ")
                            && actionRows.get(actionRowsBefore + 4).contains(" reason=item "));
            long anomaliesBefore = guardLong("anomaliesForTest");
            boolean brokenAction = refuseAction(extraAction, player, item(12, null, true), FORGED);
            expect("動作判斷出例外（原物沒有腳本）：" + (on ? "拒絕" : "不擋") + "、不外拋、anomalies 多 1",
                    brokenAction == on && guardLong("anomaliesForTest") - anomaliesBefore == 1);
        }
        // 經 parse 的改道入口：拒絕時不呼叫 Lua 建構子、回失敗；放行時照常呼叫
        CountingCaller caller = new CountingCaller();
        Method guarded = Class.forName("zombie.mdc.NetTimedActionGuard").getMethod("protectedCall", LuaCaller.class, KahluaThread.class,
                Object.class, Object[].class);
        LuaReturn refusedCall = (LuaReturn) guarded.invoke(null, caller, null, "fn", new Object[]{extraAction, player, hat, FORGED});
        int callsAfterForged = caller.calls;
        LuaReturn legalCall = (LuaReturn) guarded.invoke(null, caller, null, "fn", new Object[]{extraAction, player, hat, "Base.Hat_Reverse"});
        expect("NetTimedActionGuard.protectedCall：不在清單的種類" + (on ? "不呼叫建構子、回失敗" : "照常呼叫建構子") + "；清單裡的種類照常呼叫",
                (on ? callsAfterForged == 0 && !refusedCall.isSuccess() : callsAfterForged == 1 && refusedCall.isSuccess())
                        && caller.calls == callsAfterForged + 1 && legalCall.isSuccess());
        finish(mode);
    }

    /** 日誌鏈拋例外（on）：首次生效與 refused 記錄爆掉時照樣不建、不外拋、只計 anomalies；改拋 OutOfMemoryError 時照常外拋。 */
    private static void logThrow() throws Exception {
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                explode();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                explode();
            }
        });
        Throwable t = null;
        try {
            DebugLog.log("[TransactionExtraGuardTest] self-check");
        } catch (Throwable e) {
            t = e;
        }
        expect("自驗：DebugLog.log 真的拋例外（" + t + "）", t instanceof IllegalStateException);
        InventoryItem hat = hat();
        Method create = Class.forName(GUARD).getMethod("create", InventoryItem.class, String.class);
        t = null;
        Object result = null;
        try {
            result = create.invoke(null, hat, FORGED);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        expect("首次生效與 refused 記錄拋例外：不外拋、照樣回傳原物、anomalies 2（" + t + "）",
                t == null && result == hat && guardLong("anomaliesForTest") == 2);
        LuaManager.env = new KahluaTableImpl(new LinkedHashMap<>());
        t = null;
        boolean refusedAction = false;
        try {
            refusedAction = refuseAction(classTable("ISClothingExtraAction", null), null, hat, FORGED);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        expect("換型動作的 refused 記錄拋例外：不外拋、照樣拒絕、anomalies 3（" + t + "）",
                t == null && refusedAction && guardLong("anomaliesForTest") == 3);
        oom = true;
        t = null;
        try {
            create.invoke(null, hat, FORGED);
        } catch (InvocationTargetException e) {
            t = e.getCause();
        }
        expect("Error 照常外拋：日誌鏈拋 OutOfMemoryError 時不被吞（" + t + "）", t instanceof OutOfMemoryError);
        OUT.println(failed == 0 ? "transaction-extra-guard OK mode=logthrow" : "transaction-extra-guard FAIL " + failed + " 項");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void explode() {
        if (oom) {
            throw new OutOfMemoryError("<test> logger oom");
        }
        throw new IllegalStateException("<test> logger exploded");
    }

    /** 原版 {@code ISBaseObject:derive} 的形狀：類別表的 metatable 是父類別，放進全域表。 */
    private static KahluaTable classTable(String name, KahluaTable parent) {
        KahluaTable t = new KahluaTableImpl(new LinkedHashMap<>());
        t.rawset("Type", name);
        if (parent != null) {
            t.setMetatable(parent);
        }
        LuaManager.env.rawset(name, t);
        return t;
    }

    private static boolean refuseAction(Object... args) throws Exception {
        return (boolean) Class.forName(GUARD).getMethod("refuseAction", Object[].class).invoke(null, (Object) args);
    }

    private static boolean refuseActionNull() throws Exception {
        return (boolean) Class.forName(GUARD).getMethod("refuseAction", Object[].class).invoke(null, (Object) null);
    }

    private static final class CountingCaller extends LuaCaller {
        int calls;

        CountingCaller() {
            super(new KahluaConverterManager());
        }

        @Override
        public LuaReturn protectedCall(KahluaThread thread, Object fn, Object... args) {
            calls++;
            return LuaReturn.createReturn(new Object[]{Boolean.TRUE, new KahluaTableImpl(new LinkedHashMap<>())});
        }
    }

    private static void finish(String mode) {
        if (failed > 0) {
            OUT.println("transaction-extra-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("transaction-extra-guard OK mode=" + mode);
    }

    private static final class Outcome {
        Object result;
        Throwable thrown;
        boolean vanilla;

        @Override
        public String toString() {
            return "vanilla=" + vanilla + " thrown=" + thrown;
        }
    }

    /** 經 helper 的 create；原版有沒有去建看「Couldn't find item 種類」行有沒有增加。 */
    private static Outcome create(InventoryItem item, String type) throws Exception {
        Outcome o = new Outcome();
        int before = rows(VANILLA_MISS).size();
        try {
            o.result = Class.forName(GUARD).getMethod("create", InventoryItem.class, String.class).invoke(null, item, type);
        } catch (InvocationTargetException e) {
            o.thrown = e.getCause();
        }
        o.vanilla = rows(VANILLA_MISS).size() == before + 1;
        return o;
    }

    public static class StubClothing extends Clothing {
        public StubClothing() {
            super(null, null, null, (String) null, null, null);
        }

        @Override
        public zombie.core.skinnedmodel.visual.ItemVisual getVisual() {
            return null;
        }
    }

    /** 帽子 10：Base.Hat，腳本列的換型種類是 Hat_Reverse 與 Other.Hood。 */
    private static InventoryItem hat() throws Exception {
        ArrayList<String> extras = new ArrayList<>();
        extras.add("Hat_Reverse");
        extras.add("Other.Hood");
        return item(10, extras);
    }

    private static InventoryItem item(int id, ArrayList<String> extras) throws Exception {
        return item(id, extras, false);
    }

    private static InventoryItem item(int id, ArrayList<String> extras, boolean noScript) throws Exception {
        StubClothing c = alloc(StubClothing.class);
        c.id = id;
        set(c, "module", "Base");
        set(c, "type", "Hat");
        set(c, "fullType", "Base.Hat");
        if (!noScript) {
            Item script = alloc(Item.class);
            script.clothingItemExtra = extras;
            set(c, "scriptItem", script);
        }
        return c;
    }

    private static int guardInt(String name) throws Exception {
        Field f = Class.forName(GUARD).getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static long guardLong(String name) throws Exception {
        Method m = Class.forName(GUARD).getDeclaredMethod(name);
        m.setAccessible(true);
        return (long) m.invoke(null);
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    private static List<String> rows(String needle) {
        List<String> out = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(needle)) {
                out.add(line);
            }
        }
        return out;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        OUT.println((ok ? "teg pass  " : "teg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private TransactionExtraGuardTest() {}
}
