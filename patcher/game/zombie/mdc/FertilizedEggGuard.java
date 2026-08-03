package zombie.mdc;

import zombie.GameTime;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.Food;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.util.StringUtils;

/**
 * 受精蛋世界清除豁免（2026-08-04）。
 *
 * 原版 IsoGridSquare.load 的世界物品清除只比對 {@code getFullType()} 字串
 * （SandboxOptions.worldItemRemovalListContains 是 Set.contains 精確比對），
 * 而「受精」是 Food 的 per-instance 欄位（fertilized/fertilizedTime/timeToHatch/
 * animalHatch/eggGenome）——受精蛋與一般蛋是同一個 item type {@code Base.Egg}，
 * 清單語法上無法區分。正式服 WorldItemRemovalList 含 Base.Egg 且
 * HoursForWorldItemRemoval=24（遊戲小時），而母雞 timeToHatch=21*24=504 小時、
 * 經 AnimalEggHatch=5 的 ×2.5 乘數後為 1260 小時——地上的受精蛋必定在孵化前
 * 被清掉（差 52 倍），等於封死 Food.checkEggHatch 的地上孵化分支
 * （hutch==null 且不在容器時 baby.addToWorld()）。
 *
 * 改道 load 內唯一的 isIgnoreRemoveSandbox 呼叫點（javap：整個 class 恰一處，
 * 位於 ifne→GameTime.getWorldAgeHours 的清除判定鏈上）：先照原樣取 vanilla 值，
 * 為 true 就原樣回傳；為 false 時才追加「可孵化受精蛋且在孵化視窗內」豁免。
 * INVOKEVIRTUAL→INVOKESTATIC 同形替換（receiver 變第一參數），堆疊 1→1、
 * 指令長度不變、frames 不動。因為 vanilla 的四個 listContains 判定都在此點之前
 * 且以 &amp;&amp; 短路，helper 只在「該 item 已命中清除清單」時才被呼叫，不是每個
 * world item 都跑；同路徑對每個 world item 本來就會跑 {@code type.split("_")} 與
 * {@code ScriptManager.FindItem(type)}，成本高出本 helper 數量級。
 *
 * <h2>為什麼需要 HATCH_WINDOW_MULTIPLIER（審查修正，勿刪）</h2>
 * 初版曾主張「冷卻/烹煮/冷凍會由原版把 fertilized 設回 false，因此豁免自動失效」。
 * <b>這個主張對地上物品是錯的</b>，三條路徑全部被容器閘擋住：
 * <ul>
 *   <li>IsoWorldInventoryObject 建構子對 item 做 {@code setContainer(null)}，而
 *       {@code InventoryItem.getOutermostContainer()} 對 null／type=="floor" 的 container
 *       一律回 null ⇒ <b>地上物品的 outermostContainer 恆為 null</b>。</li>
 *   <li>烹煮（Food:384 {@code heat>1.6}）整段包在 {@code if (outermostContainer != null)} 內：不可達。</li>
 *   <li>冷凍（Food:840）需 {@code isInFreezer(outermostContainer) && isPowered()}：不可達。</li>
 *   <li>冷卻（Food:634 {@code heat<0.5}）：{@code temp = outermostContainer == null ? 1.0F : ...}，
 *       地上 temp 恆為 1.0、heat 由兩側 clamp 收斂到剛好 1.0，永遠不會低於 0.5。
 *       只有「剛從冷凍庫拿出來就丟地上」這種邊角會觸發。</li>
 * </ul>
 * 唯一真實的出口是孵化，而 {@code fertilizedTime} 只在 chunk 載入期間推進
 * （processWorldItems 由 addToWorld 填、removeFromWorld 清）——<b>少載入的 chunk 上，
 * 受精蛋既不孵化也不再被清除，就是永久豁免</b>。所以豁免的有界性不能靠推論原版行為，
 * 必須由本 helper 自己保證：以世界時鐘給一道硬上限，超過就交還原版清除。
 *
 * <h2>執行緒</h2>
 * server 端 {@code IsoGridSquare.load} 的呼叫者是 ServerChunkLoader 的單一
 * {@code LoadChunk} daemon thread（非 pool），計數器實務上單寫者；即使有第二個寫者，
 * 計數也只是診斷值——判定是 item 狀態＋世界時鐘的純函數，不讀計數器。
 *
 * <h2>例外紀律</h2>
 * 只攔 RuntimeException 且 fail-open（退回原版可清除行為）。<b>不攔 Error/LinkageError</b>：
 * 那代表 loose class 與 jar 版本漂移（PZ 更新後殘留），本專案所有 patch 都有同樣曝險，
 * 由 install.sh 同源閘＋LoadCheck 擋，在此吞掉只會給假的安全感。log 一律在判定定案之後
 * 才執行，觀測程式碼不得改變判定結果。
 */
public final class FertilizedEggGuard {

    /**
     * 豁免視窗＝dropTime + 本倍率 × timeToHatch（世界小時）。超過即交還原版清除。
     * 取 4 是因為 fertilizedTime 只在 chunk 載入時推進，世界時鐘會遠快於它——
     * 基地那種常載入的 chunk 綽綽有餘，永不載入的 chunk 則保證會被回收。
     * 母雞 1260h ⇒ 視窗 5040 世界小時（210 遊戲日）；火雞 1680h ⇒ 6720（280 日）。
     * 這是本 patch 唯一的可調旋鈕，改這裡即可收緊/放寬（SmokeCheck 有連動斷言）。
     */
    public static final double HATCH_WINDOW_MULTIPLIER = 4.0;

    private static long keptLoads;
    private static long expiredLoads;
    private static long anomalies;

    /** IsoGridSquare.load(ByteBuffer,int,boolean) 內唯一 isIgnoreRemoveSandbox 呼叫點的改道目標。 */
    public static boolean isIgnoreRemoveSandbox(IsoWorldInventoryObject worldItem) {
        if (worldItem.isIgnoreRemoveSandbox()) {
            return true;   // vanilla 豁免（ItemSpawner 生成物）原樣放行
        }
        Food egg = null;
        try {
            InventoryItem item = worldItem.getItem();
            if (isHatchableEgg(item)) {
                Food candidate = (Food)item;
                if (withinHatchWindow(worldItem, candidate)) {
                    egg = candidate;
                } else {
                    expiredLoads++;   // 過了視窗還沒孵出來＝放棄，交還原版清除
                }
            }
        } catch (RuntimeException e) {
            noteAnomaly(e);   // fail-open：退回原版行為（可被清除）
            return false;
        }
        if (egg == null) {
            return false;
        }
        keptLoads++;
        // log 必須在判定定案之後：觀測程式碼不得有能力把「該保留」翻成「該刪除」
        try {
            maybeLog(worldItem, egg);
        } catch (RuntimeException e) {
            noteAnomaly(e);
        }
        return true;
    }

    /**
     * 可孵化受精蛋＝Food 且 fertilized 且帶得出物種。
     * animalHatch 的空值判定與 Food.checkEggHatch 的孵化 gate 用同一個 StringUtils.isNullOrEmpty，
     * 兩邊同進同退——孵不出來的「受精」蛋不佔豁免額度。
     */
    public static boolean isHatchableEgg(InventoryItem item) {
        return item instanceof Food food
                && food.isFertilized()
                && !StringUtils.isNullOrEmpty(food.getAnimalHatch());
    }

    /**
     * 豁免的硬上界。dropTime 未知（-1，非玩家／動物放置）或 timeToHatch 非正時一律不豁免——
     * 無法定界的東西不給無限期保護，交還原版判定。
     */
    public static boolean withinHatchWindow(IsoWorldInventoryObject worldItem, Food egg) {
        double dropTime = worldItem.dropTime;
        int timeToHatch = egg.getTimeToHatch();
        if (!(dropTime > -1.0) || timeToHatch <= 0) {
            return false;
        }
        return GameTime.getInstance().getWorldAgeHours()
                <= dropTime + HATCH_WINDOW_MULTIPLIER * (double)timeToHatch;
    }

    /**
     * 觀測用（SmokeCheck 斷言與線上排查）；判定不讀這些值。
     *
     * <p><b>語意是「豁免決策次數」，不是蛋的顆數</b>——同一顆蛋每次 chunk 從硬碟載回來
     * 都會再計一次，所以 keptLoads 同時被「蛋變多」與「chunk 反覆載入」推高，不能拿來
     * 推估地上有幾顆蛋（codex 審查發現）。要數實際的蛋請用 log 行裡的座標去重。
     */
    public static long keptLoadsObserved() {
        return keptLoads;
    }

    public static long expiredLoadsObserved() {
        return expiredLoads;
    }

    public static long anomaliesObserved() {
        return anomalies;
    }

    private static void noteAnomaly(RuntimeException e) {
        anomalies++;
        // 自帶觸發條件：不能掛在 keptLoads 的節流上——全面失敗時 keptLoads 恆為 0，
        // 那條 log 永遠不會印，anomalies 就成了 write-only 計數器（審查發現）
        if ((anomalies & 0x1FL) == 1L) {
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][EggGuard] anomaly n=" + anomalies
                    + " " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private static void maybeLog(IsoWorldInventoryObject worldItem, Food egg) {
        if ((keptLoads & 0x1FL) != 1L) {   // 第 1、33、65... 次
            return;
        }
        IsoGridSquare square = worldItem.getSquare();
        DebugType.Multiplayer.println("[MinidoracatJavaPatch][EggGuard] fertilized egg kept at "
                + (square == null ? "?,?,?" : square.getX() + "," + square.getY() + "," + square.getZ())
                + " type=" + egg.getFullType()
                + " hatch=" + egg.getAnimalHatch()
                + " progress=" + egg.getFertilizedTime() + "/" + egg.getTimeToHatch()
                + " dropTime=" + worldItem.dropTime
                + " keptLoads=" + keptLoads + " expiredLoads=" + expiredLoads + " anomalies=" + anomalies);
    }

    private FertilizedEggGuard() {}
}
