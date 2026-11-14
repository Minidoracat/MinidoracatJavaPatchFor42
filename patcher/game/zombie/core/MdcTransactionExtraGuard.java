package zombie.core;

import java.util.ArrayList;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;

/**
 * W104 衣物換型種類的伺服器端驗證（docs/patches.md 2dl）。換型有兩條路把客戶端給的種類交到伺服器，兩條用同一套比對
 *（{@link #allowed}）：
 *
 * <p><b>物品交易</b>：{@code Transaction.updateItem} 的換型分支照交易帶的 {@code extra} 種類建新物品、取代原物。{@link #create}
 * 換掉那一個 {@code InventoryItemFactory.CreateItem(item, extra)}：種類不合時不建，回傳原物——原版接下來把它放回背包，這筆交易
 * 照原版自己的結尾判斷算失敗。{@link #removeFromHands} 換掉同一個方法裡的 {@code IsoPlayer.removeFromHands}，只為了記下是哪位
 * 玩家（原物被移出背包後就查不到了），行為照原版。
 *
 * <p><b>換型動作</b>：{@code ISClothingExtraAction:new(character, item, extra)} 的 {@code extra} 也是客戶端給的，伺服器端的
 * {@code complete()} 照它建新物品。{@link #refuseAction} 由 {@code NetTimedActionGuard.protectedCall}（{@code NetTimedAction.parse}
 * 呼叫 Lua 建構子的地方）先問一次：請求指名的類別是 {@code ISClothingExtraAction} 或它的衍生類別、種類不合時不建構，走原版
 * 既有的 {@code action = null} 拒絕。
 *
 * <p>開關：{@code -Dmdc.transactionExtraGuard=on|observe|off}，預設 on，要重啟，兩條路共用。observe 只記、照原版。判斷出例外時
 * on 擋下、observe 照原版，記 {@code anomaly} 行（辨認是不是換型動作時出的例外一律不管、只計數：不能因此擋到別的動作）。
 * MOD 自己送清單外種類的換型要服主逐對放行：
 * {@code -Dmdc.transactionExtraAllow=原物全名:目標全名,…}。
 */
public final class MdcTransactionExtraGuard {
    private static final String TAG = "[MinidoracatJavaPatch][TransactionExtraGuard]";

    static final int OFF = 0;
    static final int OBSERVE = 1;
    static final int ON = 2;
    static final int MODE = mode();
    /** 服主額外放行的「原物全名:目標全名」。 */
    private static final java.util.Set<String> EXTRA_ALLOW = extraAllow();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    private static final int TEXT_CAP = 64;
    private static final int MAX_DEPTH = 16;
    private static final String ACTION_CLASS = "ISClothingExtraAction";

    // 交易在主迴圈單執行緒處理；計數與時間窗容忍罕見交錯。
    private static boolean announced;
    /** 只留 log 要的兩個值，不留玩家物件。 */
    private static String ownerName;
    private static short ownerId;
    private static long refused;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;

    private MdcTransactionExtraGuard() {
    }

    /** {@code updateItem} 裡的 {@code isoPlayer.removeFromHands(item)}：呼叫照原版，之後記下是哪位玩家。 */
    public static boolean removeFromHands(IsoPlayer player, InventoryItem item) {
        boolean removed = player.removeFromHands(item);
        if (MODE != OFF) {
            ownerName = player.getUsername();
            ownerId = player.getOnlineID();
        }
        return removed;
    }

    /** {@code updateItem} 換型分支的 {@code CreateItem(item, extra)}。不得把自己的例外丟給原版；原版 CreateItem 的例外照常外拋。 */
    public static InventoryItem create(InventoryItem item, String extra) {
        String name = ownerName;
        ownerName = null;
        if (refuse(item, extra, name, ownerId)) {
            return item;
        }
        return InventoryItemFactory.CreateItem(item, extra);
    }

    /**
     * {@code NetTimedAction.parse} 要呼叫 {@code <Type>.new(arguments…)} 之前。{@code args[0]} 是請求指名的全域類別表，後面依序是
     * 建構子參數（{@code character, item, extra}）。回 true＝不要建構、拒絕這個請求。不得拋例外。
     */
    public static boolean refuseAction(Object[] args) {
        boolean ours = false;
        try {
            if (args == null || args.length == 0 || !isExtraAction(args[0])) {
                return false;
            }
            ours = true;
            announce();
            if (MODE == OFF) {
                return false;
            }
            Object item = args.length > 2 ? args[2] : null;
            Object extra = args.length > 3 ? args[3] : null;
            if (item instanceof InventoryItem real && extra instanceof String type && allowed(real, type)) {
                return false;
            }
            refused++;
            IsoPlayer player = args.length > 1 && args[1] instanceof IsoPlayer p ? p : null;
            // reason：item／args 多半是時序（物品剛被上一筆換掉），type 才是種類不在清單
            report("action", !(item instanceof InventoryItem) ? "item" : extra instanceof String ? "type" : "args",
                    item instanceof InventoryItem real ? real : null, String.valueOf(extra),
                    player != null ? player.getUsername() : null, player != null ? player.getOnlineID() : 0);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            if (ours) {
                reportAnomaly(e);
            }
        }
        return ours && MODE == ON;
    }

    /** 類別表自己或它的 metatable 鏈經過全域 {@code ISClothingExtraAction}（原版 derive 的形狀）。 */
    static boolean isExtraAction(Object cls) {
        if (!(cls instanceof KahluaTable table) || LuaManager.env == null
                || !(LuaManager.env.rawget(ACTION_CLASS) instanceof KahluaTable base)) {
            return false;
        }
        KahluaTable t = table;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++) {
            if (t == base) {
                return true;
            }
            t = t.getMetatable();
        }
        return false;
    }

    private static boolean refuse(InventoryItem item, String extra, String name, short id) {
        announce();
        if (MODE == OFF) {
            return false;
        }
        try {
            if (allowed(item, extra)) {
                return false;
            }
            refused++;
            report("transaction", "type", item, extra, name, id);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            reportAnomaly(e);
        }
        return MODE == ON;
    }

    /**
     * 原物腳本列的換型種類，或原物自己的種類。清單項照原版客戶端會送的兩種寫法比：清單項原樣；清單項沒有 module 時另允許
     * 補上原物的 module（原版選單的 {@code moduleDotType}）。清單項已經帶 module 的不再補，免得多放一個清單沒列的種類。
     */
    public static boolean allowed(InventoryItem item, String extra) {
        if (extra.equals(item.getFullType()) || EXTRA_ALLOW.contains(item.getFullType() + ":" + extra)) {
            return true;
        }
        ArrayList<String> types = item.getClothingItemExtra();
        if (types == null) {
            return false;
        }
        String module = item.getModule();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            if (extra.equals(type) || type.indexOf('.') < 0 && extra.equals(module + "." + type)) {
                return true;
            }
        }
        return false;
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        try {
            DebugLog.log(TAG + " 首次生效 mode=" + (MODE == ON ? "on" : MODE == OBSERVE ? "observe" : "off") + " extraAllow=" + EXTRA_ALLOW.size() + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void report(String via, String reason, InventoryItem item, String extra, String name, short id) {
        try {
            if (!allowLine()) {
                return;
            }
            DebugLog.log(TAG + " refused via=" + via + " item=" + (item != null ? clean(item.getFullType()) + " id=" + item.getID() : "? id=?")
                    + " extra=" + clean(extra)
                    + " owner=" + (name != null ? id + ":" + clean(name) : "?")
                    + " action=" + (MODE == ON ? "refused" : "logged") + " reason=" + reason
                    + " refused=" + refused + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void reportAnomaly(Throwable cause) {
        try {
            if (!allowLine()) {
                return;
            }
            DebugLog.log(TAG + " anomaly reason=" + cause.getClass().getSimpleName() + " action=" + (MODE == ON ? "refused" : "vanilla")
                    + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 種類與帳號可能來自客戶端：進 log 前截短、只留不會斷行或混淆欄位的字元。 */
    static String clean(String text) {
        if (text == null) {
            return "null";
        }
        int n = Math.min(text.length(), TEXT_CAP);
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean ok = c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '.' || c == '_' || c == '-' || c == ':';
            out.append(ok ? c : '?');
        }
        return out.toString();
    }

    private static boolean allowLine() {
        long now = System.nanoTime();
        if (windowCount == 0 || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed++;
            return false;
        }
        windowCount++;
        return true;
    }

    private static int mode() {
        String v = System.getProperty("mdc.transactionExtraGuard", "on").trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("off") || v.equals("0") || v.equals("false")) {
            return OFF;
        }
        return v.equals("observe") ? OBSERVE : ON;
    }

    private static java.util.Set<String> extraAllow() {
        java.util.HashSet<String> pairs = new java.util.HashSet<>();
        for (String pair : System.getProperty("mdc.transactionExtraAllow", "").split(",")) {
            pair = pair.trim();
            int sep = pair.indexOf(':');
            if (sep > 0 && sep < pair.length() - 1) {
                pairs.add(pair);
            }
        }
        return pairs;
    }

    // ---- 測試存取器 ----

    static long refusedForTest() {
        return refused;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
