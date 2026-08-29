package zombie.mdc;

import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/**
 * W5-2 容器環「門口」偵測（observe 首發；docs/patches.md 2q 根因段、AGENTS.md W5 條目）。
 *
 * <p>W5（{@link ContainerCycleGuard}）只治了「使用層」：環已存在後，爬升查詢切斷不炸——但
 * 切斷語意＝「此容器無擁有者」，GameServer 五個庫存廣播的第三分支 vanilla 為空＝封包不送、
 * 玩家體感物品憑空消失（已知靜默降級）。根治要在「進入層」拒絕成環；本版先覆蓋
 * {@code AddItem} 的可信寫入候選，環根本不進世界狀態才是最終目標。
 *
 * <p>vanilla 自己的述詞 {@code TransactionManager.chainContainsContainingItem} 是 private 且
 * 只爬 2 層（transaction 一致性快篩的量級選擇）；本類自行實作<b>同語意、完整深度</b>的爬升：
 * 從目標容器沿 {@code getContainingItem().getContainer()} 向上，任一層 {@code c.id == itemId}
 * 或 {@code containingItem.getID() == itemId} ⟹ 把該物品加入 target 將造成「物品既是 target
 * 祖先又是其成員」＝環。純深度上限（{@value #MAX_DEPTH}）、零配置、無鎖；既存環上的爬升由
 * 深度上限自然終止（{@code depthCapped}——真實巢套不會 64 層，此計數 &gt;0 本身就是
 * 鏈已異常的訊號，與 W5 捕手的偵測互補）。
 *
 * <p><b>本版純 observe：不改任何回傳值、不拒絕任何加入</b>（W5 捕手繼續兜底）。enforce 的
 * 拒絕語意刻意不做——{@code AddItem} 呼叫端普遍「先從舊容器移除、後加入」，拒絕＝物品憑空
 * 消失（修假死修出物品遺失更糟，patches.md 2q 風險段）；且不可借道 containsID true 分支
 * （其語意是回傳 {@code getItemWithID}＝null＋誤導 error log）。enforce 需 observe 數據
 * （環從哪個入口來、深度分佈、caller 分佈）＋獨立審查循環後另案。
 *
 * <p>掛點：既存 W5 {@code ItemContainer} ClassPatch 上的 {@code AddItem} 方法內唯一
 * {@code invokevirtual containsID(I)Z} redirect → {@link #containsID(ItemContainer, int)}
 * （1→1 同形；回傳 vanilla 原值；僅 containsID=false＝vanilla 真會進加入路徑時旁路 probe）。
 * {@code AddItemBlind} 刻意不掛：headCall 位於 null/容量拒絕之前會造 false positive，且 Blind
 * 不設 item.container backlink，向上 walk 會漏報；42.20.4 Java caller census=0（Lua/reflection
 * 理論可達），由 W5 使用層捕手兜底，待有可信中段掛點/下行圖判定再補。
 *
 * <p>例外語意：probe 全段自包 {@code RuntimeException}（蒐證故障不得影響 AddItem 語意）；
 * {@code LinkageError} 外逃 fail-fast（家族紀律）。log 總量 cap {@value #LOG_CAP} 行後只計數
 * （環是稀有事件，正常運轉零輸出）。計數為主執行緒單寫普通 long（AddItem 亦可能出現在
 * 載入路徑，計數僅診斷用、容忍極端併發下的丟失更新）。
 *
 * <p>三態 {@code -Dmdc.containerAddCycleProbe}：{@code 0|off}、{@code 1}（enforce 未實作，
 * 本版 observe-alias——W19 慣例）、{@code 2|observe} 預設。
 */
public final class ContainerAddCycleProbe {
    private static final String TAG = "[MinidoracatJavaPatch][ContainerAddCycleProbe]";

    static final int MODE_OFF = 0;
    static final int MODE_OBSERVE_ALIAS = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();
    static final int MAX_DEPTH = 64;
    private static final int LOG_CAP = 256;
    private static final long BEAT_NS = 60_000_000_000L;

    private static final int CLEAN = 0;
    private static final int WOULD_CYCLE = 1;
    private static final int DEPTH_CAPPED = 2;

    private static long callsAdd;
    private static long wouldCycle;
    private static long depthCapped;
    private static long logged;
    private static long suppressed;
    private static long anomalies;
    private static long lastBeatNs;
    private static boolean bannerShown;

    private ContainerAddCycleProbe() {
    }

    /** AddItem 內 containsID 呼叫點的 redirect 目標：vanilla 原值照回；只有真加入候選才 probe。 */
    public static boolean containsID(ItemContainer c, int id) {
        boolean r = c.containsID(id);
        if (!r && MODE != MODE_OFF) {
            try {
                callsAdd++;
                if (!bannerShown) {
                    showBanner();
                }
                probeAndLog(c, id);
                maybeBeat();
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        return r;
    }


    private static void probeAndLog(ItemContainer target, int itemId) {
        int verdict = probe(target, itemId);
        if (verdict == CLEAN) {
            return;
        }
        if (verdict == WOULD_CYCLE) {
            wouldCycle++;
        } else {
            depthCapped++;
        }
        if (logged >= LOG_CAP) {
            suppressed++;
            return;
        }
        logged++;
        StringBuilder sb = new StringBuilder(256);
        sb.append(TAG).append(verdict == WOULD_CYCLE ? " WOULD-CYCLE " : " DEPTH-CAPPED ")
                .append("entry=AddItem")
                .append(" targetContainerId=").append(target.id)
                .append(" itemId=").append(itemId);
        sb.append(" chain=");
        appendChain(sb, target, itemId);
        sb.append(" caller=").append(callerFrame());
        DebugLog.log(sb.toString());
    }

    /**
     * 完整深度爬升：任一層容器 id 或 containingItem id 命中 ⟹ WOULD_CYCLE；
     * 打到上限 ⟹ DEPTH_CAPPED（既存環或異常深鏈）。零配置。
     */
    static int probe(ItemContainer target, int itemId) {
        if (itemId == -1 || target == null) {
            return CLEAN;
        }
        ItemContainer c = target;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (c == null) {
                return CLEAN;
            }
            if (c.id == itemId) {
                return WOULD_CYCLE;
            }
            InventoryItem holder = c.getContainingItem();
            if (holder == null) {
                return CLEAN;
            }
            if (holder.getID() == itemId) {
                return WOULD_CYCLE;
            }
            c = holder.getContainer();
        }
        return DEPTH_CAPPED;
    }

    private static void appendChain(StringBuilder sb, ItemContainer target, int itemId) {
        ItemContainer c = target;
        for (int depth = 0; depth < 8 && c != null; depth++) {
            if (depth > 0) {
                sb.append('>');
            }
            sb.append(c.id);
            InventoryItem holder = c.getContainingItem();
            if (holder == null) {
                return;
            }
            sb.append('/').append(holder.getID());
            c = holder.getContainer();
        }
        sb.append(">…");
    }

    private static String callerFrame() {
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            String cls = f.getClassName();
            if (cls.startsWith("zombie.") && !cls.startsWith("zombie.mdc.")
                    && !cls.equals("zombie.inventory.ItemContainer")) {
                return cls + "." + f.getMethodName() + ":" + f.getLineNumber();
            }
        }
        return "unknown";
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE + " maxDepth=" + MAX_DEPTH
                + "（-Dmdc.containerAddCycleProbe=0|off 停用；observe 純蒐證不拒絕，"
                + "enforce 待 observe 數據另案）.");
    }

    private static void maybeBeat() {
        if ((callsAdd & 0xFFFL) != 0L) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (lastBeatNs != 0L && now - lastBeatNs < BEAT_NS) {
                return;
            }
            lastBeatNs = now;
            DebugLog.log(TAG + " beat callsAdd=" + callsAdd
                    + " wouldCycle=" + wouldCycle + " depthCapped=" + depthCapped
                    + " logged=" + logged + " suppressed=" + suppressed
                    + " anomalies=" + anomalies + " mode=" + MODE + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 三態解析：1 為 enforce 預留、本版 observe-alias（W19 慣例）；未知值落回 observe。 */
    private static int parseMode() {
        String raw = System.getProperty("mdc.containerAddCycleProbe");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
                return MODE_OBSERVE_ALIAS;
            case "2":
            case "observe":
            default:
                return MODE_OBSERVE;
        }
    }

    // ---- 測試存取器 ----

    static long callsAddForTest() {
        return callsAdd;
    }


    static long wouldCycleForTest() {
        return wouldCycle;
    }

    static long depthCappedForTest() {
        return depthCapped;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
