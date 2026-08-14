package zombie.mdc;

import java.util.ArrayList;
import java.util.List;

import zombie.characters.IsoGameCharacter;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/**
 * W5 容器環防崩潰守衛（2026-08-13 21:31:10 正式服全服假死實案）。
 *
 * <p><b>事故</b>：主迴圈死於 {@code java.lang.StackOverflowError}，堆疊 1024 層全部是
 * {@code ItemContainer.getCharacter} 自我遞迴。伺服器假死 13 分鐘、graceful {@code quit}
 * 收不進去、看門狗強制重啟，玩家損失約 13 分鐘操作。
 *
 * <p><b>vanilla 缺陷</b>：{@code getCharacter()} 沿「容器→裝著它的物品→該物品所在的容器」
 * 往上爬找擁有者，<b>完全沒有迴圈偵測</b>：
 * <pre>
 * return this.containingItem != null &amp;&amp; this.containingItem.getContainer() != null
 *      ? this.containingItem.getContainer().getCharacter() : null;
 * </pre>
 * 而 {@code ItemContainer} 全類別零防環檢查——{@code AddItem} 只擋同 ID 重複，
 * <b>不阻止把容器放進自己的子孫</b>。只要 MP 的封包驅動搬移造出「A 裝在 B 裡、B 又裝在 A 裡」，
 * 這個方法就會無限遞迴撐爆 stack。
 *
 * <p><b>環只可能是執行期產物</b>：{@code containingItem} 只在 {@code InventoryContainer}
 * 建構時設定一次且不序列化；{@code InventoryContainer.save} 是巢狀遞迴寫入，若存檔裡有環，
 * 存檔本身就會先爆——而崩潰前 60 秒的 {@code World saved} 是成功的。因此掃存檔找不到兇手，
 * 只能在執行期攔截。
 *
 * <p><b>手術</b>：改道 {@code getCharacter()} 內<b>唯一</b>的遞迴呼叫點
 * （javap offset 42：{@code invokevirtual ItemContainer.getCharacter()}）到本 helper。
 * 堆疊形狀不變（[ItemContainer] → [IsoGameCharacter]），method-scope 鎖定。
 * 於是每一層遞迴都會經過這裡，helper 得以計深度並在失控前切斷。
 *
 * <p><b>語意</b>：超過深度上限即回傳 {@code null}。這與 vanilla 一致——放在地上的容器
 * （parent 非角色、containingItem 為 null）本來就回 null，全 jar 呼叫端普遍做 null 檢查
 * （如 {@code TransactionManager} 的 {@code getCharacter() != null}）。對一條損壞的環狀鏈，
 * 「查不出屬於哪個角色」正是誠實答案。效果是把「全服假死」降級為「該容器的歸屬查詢失敗」。
 *
 * <p><b>診斷</b>：切斷時走一次鏈（visited 上限保護）印出環上的物品 id 與 fullType，
 * 讓事後能定位是哪些袋子；log 節流避免洗版。這是目前唯一能抓到兇手的手段。
 *
 * <p>旋鈕（不需重新部署）：{@code -Dmdc.cycleGuard.maxDepth=N}（0＝停用守衛，回到 vanilla）。
 */
public final class ContainerCycleGuard {

    /** 正常巢狀（背包→袋→小包）實務上個位數；64 給足餘裕又遠低於 stack 上限。 */
    static final int DEFAULT_MAX_DEPTH = 64;
    /**
     * 硬上限 256（codex 審查）：{@code Integer.getInteger} 本身無上界，
     * 若有人把它設成 100000 就等於恢復 StackOverflow 風險——旋鈕不該能關掉保險絲。
     */
    static final int HARD_MAX_DEPTH = 256;
    private static final int MAX_DEPTH = clamp(
            Integer.getInteger("mdc.cycleGuard.maxDepth", DEFAULT_MAX_DEPTH), 0, HARD_MAX_DEPTH);

    private static final int MAX_CHAIN_WALK = 128;      // 診斷走鏈的硬上限
    private static final int MAX_REPORTS = 5;           // 每場次最多印幾次完整鏈
    private static final long REPORT_INTERVAL_NS = 60_000_000_000L;
    /** 完整鏈印完後仍每 10 分鐘印一次 trips 心跳——環不會自己消失，運維不能看不到。 */
    private static final long HEARTBEAT_INTERVAL_NS = 600_000_000_000L;
    private static final int MAX_ANOMALY_TRACES = 3;

    /**
     * 每執行緒的爬升路徑（identity）。用路徑而非單純深度計數，是為了<b>區分兩種失效</b>
     * （codex 審查）：receiver 重複出現＝真環（通常 2-3 層就抓到，不必等到門檻）；
     * 只是很深＝ OVER_DEPTH 保險絲。純深度計數會把合法的深巢狀誤報成環。
     *
     * <p>server 端另有 load/recalc/save/command 執行緒，且這些 public inventory API 可被 mod
     * 呼叫，故不假設單執行緒。ThreadLocal 只隔離「走訪狀態」，不會讓容器圖本身變 thread-safe。
     * finally 清 slot（不 remove 整個 State）避免長壽命執行緒抓住遊戲物件。
     */
    private static final class State {
        final ItemContainer[] path = new ItemContainer[HARD_MAX_DEPTH + 1];
        int size;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    // 以下 static 計數器刻意不加同步：多執行緒下最壞只是多印幾行或計數略偏，
    // 全部後果良性，不值得為觀測欄位付 Atomic 成本。
    private static long trips;          // getCharacter 截斷次數
    private static long tripsInv;       // isInCharacterInventory 截斷次數
    private static long reports;        // 已印出完整鏈的次數
    private static long anomalies;
    private static long anomalyTraces;
    private static long lastReportNs;
    private static long lastHeartbeatNs;
    private static long heartbeatTrips;  // 上次心跳時的 trips 總和（沒動就不印）
    private static boolean reportPrimed;
    private static boolean heartbeatPrimed;

    private ContainerCycleGuard() {}

    /**
     * {@code getCharacter()} 內遞迴呼叫點的改道目標：計深度、超限即切斷。
     * 簽名與被取代的 {@code INVOKEVIRTUAL} 同形（receiver → 回傳值）。
     */
    public static IsoGameCharacter getCharacter(ItemContainer container) {
        if (container == null) {
            return null;                     // vanilla 已先判過，防禦性
        }
        if (MAX_DEPTH <= 0) {
            return container.getCharacter(); // 旋鈕停用：完全等同 vanilla（含原本的爆掉行為）
        }
        State st = STATE.get();
        int slot = enter(st, container, "getCharacter");
        if (slot < 0) {
            return null;
        }
        try {
            return container.getCharacter();
        } finally {
            exit(st, slot);
        }
    }

    /**
     * 進入一層爬升：先查環（identity 重複＝真環，通常 2-3 層就命中）、再查深度保險絲。
     * 回傳佔用的 slot；-1 代表必須切斷。
     */
    private static int enter(State st, ItemContainer container, String site) {
        for (int i = 0; i < st.size; i++) {
            if (st.path[i] == container) {
                trip(site, "CYCLE", container, i, st.size);
                return -1;
            }
        }
        if (st.size >= MAX_DEPTH) {
            trip(site, "OVER_DEPTH", container, -1, st.size);
            return -1;
        }
        st.path[st.size] = container;
        return st.size++;
    }

    /** 離開一層：清 slot（不 remove 整個 State）避免長壽命執行緒抓住遊戲物件。 */
    private static void exit(State st, int slot) {
        st.path[slot] = null;
        st.size = slot;
    }

    private static void trip(String site, String reason, ItemContainer container,
            int repeatAt, int depth) {
        if ("getCharacter".equals(site)) {
            trips++;
        } else {
            tripsInv++;
        }
        onCycleDetected(container, site, reason, repeatAt, depth);
    }

    /**
     * {@code isInCharacterInventory(IsoGameCharacter)} 內遞迴呼叫點的改道目標。
     *
     * <p>同一條 {@code containingItem → getContainer()} 鏈上的第二條無防環遞迴，
     * 而且它是<b>下一個最可能炸的</b>：{@code Transaction.getDuration()} 會呼叫它，
     * 且 {@code getDuration()} 只在 server 端、於 {@code ItemTransactionPacket} 驅動的
     * {@code Transaction} 建構時執行——也就是<b>造出環的同一條封包路徑</b>。
     *
     * <p>截斷回 {@code false}＝「不在該角色的庫存裡」，與 vanilla 自己走完鏈後的
     * fall-through {@code return false} 同值，是保守方向。
     */
    public static boolean isInCharacterInventory(ItemContainer container, IsoGameCharacter chr) {
        if (container == null) {
            return false;
        }
        if (MAX_DEPTH <= 0) {
            return container.isInCharacterInventory(chr);
        }
        State st = STATE.get();
        int slot = enter(st, container, "isInCharacterInventory");
        if (slot < 0) {
            return false;
        }
        try {
            return container.isInCharacterInventory(chr);
        } finally {
            exit(st, slot);
        }
    }

    /** 切斷時的診斷；本身絕不能再拋或再遞迴。 */
    private static void onCycleDetected(ItemContainer container, String site,
            String reason, int repeatAt, int depth) {
        try {
            long now = System.nanoTime();
            if (!reportPrimed) {
                reportPrimed = true;
                lastReportNs = now - REPORT_INTERVAL_NS;
            }
            if (reports < MAX_REPORTS && now - lastReportNs >= REPORT_INTERVAL_NS) {
                lastReportNs = now;
                reports++;
                DebugLog.log("[MinidoracatJavaPatch][CycleGuard] " + reason + " 已切斷遞迴"
                        + "（site=" + site + " depth=" + depth
                        + (repeatAt >= 0 ? " 環起點=第 " + repeatAt + " 層" : "")
                        + " getCharacter=" + trips + " isInCharInv=" + tripsInv
                        + " maxDepth=" + MAX_DEPTH + "）——以下為鏈上物品：");
                for (String line : describeChain(container)) {
                    DebugLog.log("[MinidoracatJavaPatch][CycleGuard]   " + line);
                }
                return;
            }
            // 完整鏈額度用完後仍要有心跳：環不會自己消失，運維不能在第一天之後看不到它。
            // lastHeartbeatNs=0 當哨兵是壞的——System.nanoTime() 原點任意且規格允許為負，
            // 負原點時 (now - 0 >= 10 分鐘) 恆為假＝心跳一輩子不印。上面 lastReportNs 已用
            // primed 模式處理，心跳漏套（審查抓到；W6 同缺陷一併修）。
            long total = trips + tripsInv;
            if (!heartbeatPrimed) {
                heartbeatPrimed = true;
                lastHeartbeatNs = now;
                heartbeatTrips = total;
                return;
            }
            if (now - lastHeartbeatNs >= HEARTBEAT_INTERVAL_NS && total != heartbeatTrips) {
                lastHeartbeatNs = now;
                heartbeatTrips = total;
                DebugLog.log("[MinidoracatJavaPatch][CycleGuard] 環仍存在（getCharacter=" + trips
                        + " isInCharInv=" + tripsInv + " anomalies=" + anomalies + "）");
            }
        } catch (Throwable t) {
            rethrowFatal(t);
            anomalies++;
            // 若剛才拋出的正是 DebugLog.log 本身，用「再 log 一次」回應會讓例外逃出診斷、
            // 逃回遞迴點——把守衛本身變成新的故障源。診斷失敗絕不得升級為當機。
            if (anomalyTraces < MAX_ANOMALY_TRACES) {
                anomalyTraces++;
                try {
                    DebugLog.log("[MinidoracatJavaPatch][CycleGuard] anomaly #" + anomalies + ": " + t);
                    for (StackTraceElement e : t.getStackTrace()) {
                        DebugLog.log("[MinidoracatJavaPatch][CycleGuard]     at " + e);
                    }
                } catch (Throwable ignored) {
                    rethrowFatal(ignored);
                }
            }
        }
    }

    /**
     * 走一次容器鏈並找出環的閉合點。只用不會二次遞迴的 API
     * （{@code containingItem} 是欄位、{@code getContainer()} 是單純 getter，
     * 都不會回頭呼叫 {@code getCharacter}）。
     */
    private static List<String> describeChain(ItemContainer start) {
        List<String> out = new ArrayList<>();
        List<ItemContainer> seen = new ArrayList<>();
        ItemContainer c = start;
        for (int i = 0; i < MAX_CHAIN_WALK && c != null; i++) {
            int repeat = indexOfIdentity(seen, c);
            if (repeat >= 0) {
                out.add("環在此閉合 → 回到第 " + repeat + " 步（環長 " + (i - repeat) + "）");
                return out;
            }
            seen.add(c);
            InventoryItem item = c.containingItem;
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(i).append("] containerId=").append(safeContainerId(c));
            if (item == null) {
                sb.append(" containingItem=null（鏈到此結束）");
                out.add(sb.toString());
                return out;
            }
            sb.append(" itemId=").append(item.id)
                    .append(" type=").append(safeType(item));
            out.add(sb.toString());
            c = safeContainerOf(item);
        }
        out.add("（走鏈達上限 " + MAX_CHAIN_WALK + " 仍未閉合）");
        return out;
    }

    private static int indexOfIdentity(List<ItemContainer> seen, ItemContainer c) {
        for (int i = 0; i < seen.size(); i++) {
            if (seen.get(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private static int safeContainerId(ItemContainer c) {
        try {
            return c.id;
        } catch (Throwable t) {
            rethrowFatal(t);
            return -1;
        }
    }

    private static String safeType(InventoryItem item) {
        try {
            String t = item.getFullType();
            return t == null ? "?" : t;
        } catch (Throwable t) {
            rethrowFatal(t);
            return "?";
        }
    }

    private static ItemContainer safeContainerOf(InventoryItem item) {
        try {
            return item.getContainer();
        } catch (Throwable t) {
            rethrowFatal(t);
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * <b>只重拋 {@link VirtualMachineError}</b>。本方法只用在診斷路徑，而診斷路徑撞到的
     * {@code LinkageError} 100% 是診斷子系統的缺陷、不是「世界不可續跑」的訊號——在已經
     *決定放棄診斷的那一行把它升級成當機，正好是這道網存在的理由的反面（審查抓到；
     * W6 的 {@link ChunkLoadGuard} 同缺陷一併修）。堆真的爆了（OOM／SOE）則必須穿透。
     */
    private static void rethrowFatal(Throwable t) {
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
    }

    // ---- 測試掛點（package-private；production 不呼叫）----
    static void resetForTest() {
        trips = 0;
        tripsInv = 0;
        reports = 0;
        anomalies = 0;
        anomalyTraces = 0;
        lastHeartbeatNs = 0;
        heartbeatTrips = 0;
        reportPrimed = false;
        heartbeatPrimed = false;
        State st = STATE.get();
        java.util.Arrays.fill(st.path, null);
        st.size = 0;
    }

    /** trips, reports, anomalies, currentDepth, tripsInv */
    static long[] statsForTest() {
        return new long[]{trips, reports, anomalies, STATE.get().size, tripsInv};
    }

    static int maxDepthForTest() {
        return MAX_DEPTH;
    }

    static List<String> describeChainForTest(ItemContainer start) {
        return describeChain(start);
    }
}
