package zombie.mdc;

import java.util.concurrent.ConcurrentHashMap;

import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.scripting.objects.Item;
import zombie.scripting.objects.ItemType;

/**
 * evolved-recipe 食材重量查詢的觀測與（尚未啟用的）per-item-type 記憶化。
 *
 * <p><b>原版在做什麼</b>：{@code InventoryItem.getExtraItemsWeight()}（offset 35 的唯一
 * {@code CreateItem} 呼叫點）對 {@code extraItems} 內的每個 fullType 字串
 * <b>建構一個完整的 InventoryItem 只為讀它的 getActualWeight()，讀完立刻丟棄</b>——
 * 區域變數在迴圈下一圈就被覆寫，物件從不逃逸出那 73 bytes。單次建構包含
 * {@code ScriptManager.FindItem}（兩次 hash 查找，miss 時退化為 moduleList 線性掃描）＋
 * {@code Item.InstanceItem}（codeLen=4064，遠超 FreqInlineSize=325 故<b>永不 inline</b>）＋
 * 4 個 ArrayList＋10 次 Translator.getText＋synchWithVisual＋ConfigureItemOnCreate，
 * 保守估 ~1.6-2.0 KB 配置、1.5-5 µs。
 *
 * <p><b>呼叫頻率</b>：{@code Moodle.Update} 的 HEAVY_LOAD 分支（無節流，連
 * {@code getBodyDamage().getHealth()} 檢查都在它之後）→ {@code ItemContainer.getCapacityWeight}
 * → {@code IsoGameCharacter.getInventoryWeight} → {@code getUnequippedWeight} 與
 * {@code ItemContainer.getContentsWeight} <b>相互遞迴走訪整棵巢狀背包樹</b>，每玩家每 tick 一次。
 * dedicated server 上這條路徑是 vanilla 刻意要跑的：{@code IsoGameCharacter.updateInternal}
 * 有兩個 Moodles.Update callsite，:9103 在 {@code GameClient.client} 為真的分支、
 * <b>:9129（jstack 命中的那個）在 offset 589 {@code ifne} 開啟的 !client 分支</b>；
 * HEAVY_LOAD 的 moodleLevel 被 {@code calculateBaseSpeed}（speed -= level*0.15）、
 * {@code Fitness.reduceEndurance}、{@code getClimbingFailChanceFloat} 等 server 權威 gameplay
 * 消費——<b>所以不能做死工消除</b>。
 *
 * <h2>三態旋鈕 {@code -Dmdc.itemWeightMemo}（預設 observe）</h2>
 * <ul>
 *   <li><b>{@code observe}（預設，第一版唯一要上線的模式）</b>——<b>完全不保留任何
 *       InventoryItem 實例</b>。{@code SEEN} 只存字串，且只收「通得過五道門的型別」，
 *       因為只有它們在 on 模式下真的進得了 CACHE。三個決策數字：
 *       {@code misses}＝首見且可快取的型別數、{@code hits}＝重複且可快取的呼叫數
 *       （＝啟用 on 之後真正會命中的次數）、{@code uncacheable}＝null 或被門擋下的呼叫
 *       （on 模式下每次都會重新建構，<b>不計入命中率</b>）、{@code vanillaNsAvg}＝原版單次建構耗時。
 *       第二輪 review 抓到早期版本把 null 與被門擋下的型別也算 hit，會讓唯一的上線決策依據灌水。
 *       三份獨立 review（Claude／codex／grok）一致認為第一版不該在預設模式養一份活物件快取——這裡照辦。</li>
 *   <li>{@code on}——<b>尚未經正式服量測驗證，預設不啟用</b>。命中即回傳共用實例，跳過整個建構。
 *       啟用前提：observe 的 {@code hits/(hits+misses)} 明顯偏高、{@code uncacheable} 佔比不高、
 *       且 {@code vanillaNsAvg} 乘上呼叫頻率確實佔得到 tick 預算。行為差異見下節。</li>
 *   <li>{@code off}——純轉發，連觀測都不做。</li>
 * </ul>
 * 旋鈕在 class 初始化時讀取一次，<b>改值需要重啟伺服器才生效</b>（與本專案其他旋鈕同性質，
 * 不是「線上即時回退」）。
 *
 * <h2>factory 恰好呼叫一次（observe 的唯一硬保證）</h2>
 * {@link #createItem} 刻意切成三段：呼叫前觀測、原版 factory、呼叫後觀測。
 * <b>factory 不在任何 try 之內</b>——它拋什麼就原樣往外傳，與原版逐位元相同。
 * 前後兩段觀測各自吞掉 {@code RuntimeException} 與 {@code LinkageError}（log 基礎設施故障
 * 不該升級成 caller 的例外，與 {@code ChunkWriteGuard} 同一慣例）。
 * 早期版本用「跨越 factory 的單一 catch ＋ created 旗標」，被三方 review 同時指出
 * 會在「factory 自己拋例外」時重跑一次 factory——那會讓已發生的副作用
 * （{@code Rand.Next}、{@code initialiseItem} 的 Lua OnCreate、MOVEABLE 的 script 寫回）
 * 執行兩次，且第二次若成功會回傳一個原版根本拿不到的物件。現在的結構讓那條路徑不存在。
 *
 * <h2>{@code on} 模式的行為差異（啟用前必須重讀）</h2>
 * <ol>
 *   <li><b>全域 RNG 序列位移</b>：命中時少抽的 {@code Rand.Next} <b>至少兩次</b>
 *       （{@code createItemInternal:139} 的 id、{@code Item.InstanceItem:1911} 的 OutfitRNG 種子），
 *       實際次數依型別分支而增加（KEY 的 keyId、CLOTHING/ALARM_CLOCK_CLOTHING 的 palette、
 *       MAP 的 pickRandom、RADIO 的 setRandomChannel、synchWithVisual 的 modelIndex）。
 *       PZ MP 不是 lockstep 決定論（server 權威＋client 預測），所以不會 desync，但後續抽樣
 *       序列與原版不同——仍然隨機、仍然公平，只是不同。<b>不做補抽補償</b>：次數隨分支而異，
 *       猜錯次數比序列位移更糟。</li>
 *   <li><b>建構期副作用不再重跑</b>。{@link #cacheable} 的五道門擋掉已知會改寫實例或寫出
 *       實例之外的型別，其中 <b>MOVEABLE 是三方 review 抓到的實質風險</b>：
 *       {@code Item.InstanceItem:1801-1805} 對 MOVEABLE 會執行
 *       {@code this.actualWeight = moveable.getActualWeight()}——寫回<b>共享的 script 單例</b>，
 *       而所有 {@code Moveables.<sprite>} 共用同一份 script（{@code InventoryItemFactory} 的
 *       Moveables 映射）。跳過建構就等於讓 script 的 actualWeight 停在上一個 sprite 的值。</li>
 *   <li><b>ECS component 建立</b>：{@code Item.InstanceItem:1909} 無條件呼叫
 *       {@code GameEntityFactory.CreateInventoryItemEntity}，而它內部正是以
 *       {@code itemScript.hasComponents()} 決定要不要 {@code createEntity}
 *       （GameEntityFactory.java:114-117）。第五道門用的就是同一個謂詞，所以跳過建構
 *       不會漏掉任何 component 建立／連接。</li>
 * </ol>
 *
 * <p><b>不做 null 負快取</b>：原版找不到 script item 時會在
 * {@code InventoryItemFactory.createItemInternal:113} 印出 {@code Couldn't find item} 並回 null。
 * 那是「有 recipe 引用了不存在的 item」的訊號，快取 null 會讓它只出現第一次、其餘靜默——
 * 本專案的抑噪一律只攔<b>已知</b>噪音，不該順手抹掉未知的配置錯誤；而且若 mod 在之後才把
 * 該型別註冊回來，負快取會讓它永遠取不到。{@code nullResults} 計數追蹤它。
 *
 * <p><b>共用實例的安全性（僅 on 模式相關）</b>：{@code getExtraItemsWeight} 內該區域變數
 * （slot 3）只被 {@code ifnull}、兩次 {@code getActualWeight()} 讀取，迴圈下一圈即覆寫，
 * 從不逃逸。這是對 vanilla 當前實作的依賴，由 {@code SmokeCheck} 的語境指紋
 * （factory 呼叫後緊接 {@code ASTORE 3}、slot 3 僅供 {@code IFNULL} 與兩次
 * {@code getActualWeight}、另外四個 {@code CreateItem(String)} callsite 必須維持原版）
 * ＋{@code expectedHits=1} 在版本漂移時讓建置失敗，而非默默出錯。
 *
 * <p><b>觀測計數是近似值</b>：{@code getExtraItemsWeight} 主要由
 * {@code GameServer.mainThread} 的 Moodle 路徑進入，但 {@code InventoryItem.save} 對
 * custom-weight item 也會呼叫 virtual {@code getActualWeight()}，故序列化路徑可能併發進來。
 * 這些 {@code long} 刻意不同步——為了計數去加鎖會把觀測本身變成成本
 * （ForwardVectorGuard 的教訓）。代價是 avg 可能小幅失真，log 已標 {@code ~}。
 */
public final class ItemWeightMemo {

    private static final int MODE_OFF = 0;
    private static final int MODE_OBSERVE = 1;
    private static final int MODE_ON = 2;

    /** 天然上界是 evolved-recipe 食材型別數（數百量級）；這道上限只防 mod 意外餵進無界字串。 */
    private static final int MAX_ENTRIES = 4096;

    /** 計時取樣：每 64 次呼叫量一次，把 nanoTime 開銷壓在雜訊以下。 */
    private static final int TIMING_MASK = 0x3F;

    /** on 模式的實例快取。observe 模式<b>不會</b>寫入它——第一版不保留任何 InventoryItem。 */
    private static final ConcurrentHashMap<String, InventoryItem> CACHE = new ConcurrentHashMap<>(512);

    /** observe 模式的型別出現簿：只存字串，不保留實例。用來預測 on 模式的命中率。 */
    private static final ConcurrentHashMap<String, Boolean> SEEN = new ConcurrentHashMap<>(512);

    private static final int MODE = readMode();
    private static final String MODE_NAME = MODE == MODE_ON ? "on" : (MODE == MODE_OFF ? "off" : "observe");

    private static long hits;
    private static long misses;
    private static long nullResults;
    private static long uncacheable;
    /** 每次 non-off 呼叫都推進：取樣與週期 log 的唯一時鐘（不受 cacheability 偏置）。 */
    private static long attempts;
    private static long overflow;
    private static long anomalies;
    /** 原版 factory 的實際呼叫次數——「恰一次」這條契約的唯一可測探針。 */
    private static long factoryCalls;
    private static long vanillaNs;
    private static long vanillaSamples;
    private static long memoNs;
    private static long memoSamples;
    private static boolean announced;

    /** {@code InventoryItem.getExtraItemsWeight()} offset 35 的唯一 CreateItem 呼叫點的改道目標。 */
    public static InventoryItem createItem(String fullType) {
        if (MODE == MODE_OFF) {
            return InventoryItemFactory.CreateItem(fullType);
        }

        // ---- phase 1：呼叫前觀測。例外不得外逃，否則原版 factory 連一次都跑不到 ----
        boolean timeThis = false;
        boolean seen = false;
        InventoryItem cached = null;
        try {
            announceOnce();
            // cadence 一律用 attempts（每次 non-off 呼叫都推進）。用 hits+misses 會在連續
            // uncacheable 時凍結：那組計數不動 ⇒ 取樣條件恆真（每次 nanoTime）或恆假（永不取樣），
            // 週期 log 甚至可能每次都印。第二輪 review 抓到的 cadence 偏置。
            attempts++;
            timeThis = (attempts & TIMING_MASK) == 0L;
            if (MODE == MODE_ON) {
                long t0 = timeThis ? System.nanoTime() : 0L;
                cached = fullType == null ? null : CACHE.get(fullType);
                if (timeThis) {
                    memoNs += System.nanoTime() - t0;
                    memoSamples++;
                }
            } else {
                // observe：只「查」不「插」。插入延到 phase 3，因為要等 factory 的結果才知道
                // 這個型別在 on 模式下到底進不進得了 CACHE（五道門）。
                seen = fullType != null && SEEN.containsKey(fullType);
            }
        } catch (RuntimeException | LinkageError e) {
            noteAnomaly(e);
            timeThis = false;
        }

        // on 的命中回傳<b>刻意放在 try 外</b>：留在 try 內時，maybeLog 一拋就會跌進 phase 2，
        // 把「不該呼叫 factory」變成「呼叫了一次」——第二輪 review 抓到的縫。
        if (cached != null) {
            hits++;
            maybeLog();
            return cached;
        }

        // ---- phase 2：原版 factory。刻意不在 try 內——它拋什麼就原樣外傳。
        // factoryCalls 在呼叫前遞增：這是唯一能讓測試直接斷言「factory 恰一次」的探針，
        // 也涵蓋 factory 自己拋例外的情形（計數已加、不會有第二次呼叫可加）。----
        long t1 = timeThis ? System.nanoTime() : 0L;
        factoryCalls++;
        InventoryItem fresh = InventoryItemFactory.CreateItem(fullType);

        // ---- phase 3：呼叫後觀測。同樣不得外逃 ----
        try {
            if (timeThis) {
                vanillaNs += System.nanoTime() - t1;
                vanillaSamples++;
            }
            if (fresh == null) {
                nullResults++;
            }
            if (MODE == MODE_ON) {
                misses++;
                store(fullType, fresh);
            } else {
                noteObserved(fullType, fresh, seen);
            }
            maybeLog();
        } catch (RuntimeException | LinkageError e) {
            noteAnomaly(e);
        }
        return fresh;
    }

    /**
     * observe 的記帳：命中率必須與 on 的實際行為對齊——只有「通得過五道門」的結果才可能
     * 進 CACHE，所以只有它們能算 would-hit。null 與被門擋下的型別在 on 模式下每次都會
     * 重新建構，算成 hit 會讓唯一的上線決策依據灌水（第二輪 review 抓到的核心問題）。
     */
    private static void noteObserved(String fullType, InventoryItem fresh, boolean seen) {
        if (!cacheable(fresh)) {
            uncacheable++;
        } else if (seen) {
            hits++;
        } else {
            misses++;
            noteSeen(fullType);
        }
    }

    /** observe 的型別出現簿：只存字串，且套用與 CACHE 相同的容量上限。 */
    private static void noteSeen(String fullType) {
        if (fullType == null) {
            return;
        }
        if (SEEN.size() >= MAX_ENTRIES) {
            overflow++;
            return;
        }
        SEEN.putIfAbsent(fullType, Boolean.TRUE);
    }

    private static void store(String fullType, InventoryItem fresh) {
        if (fullType == null) {
            return;
        }
        if (!cacheable(fresh)) {
            uncacheable++;
            return;
        }
        if (CACHE.size() >= MAX_ENTRIES) {
            overflow++;
            return;
        }
        CACHE.putIfAbsent(fullType, fresh);
    }

    /**
     * 進快取的五道門，任何一道不過就每次重新建構（＝維持原版的呼叫次數與副作用）。
     * 只有 on 模式會走到這裡。
     * <ol>
     *   <li>拿得到 script——item 非 null 且 {@code getScriptItem() != null}。null 每次重查，
     *       保留原版的 {@code Couldn't find item} 診斷與後續註冊的可恢復性；取不到 script
     *       則無從判斷其餘三道門，保守不快取。</li>
     *   <li>{@code getLuaCreate() == null}——帶 OnCreate 的 item 每次都要跑
     *       {@code initialiseItem()} 的 Lua 回呼（{@code Item.InstanceItem:1916-1918}，
     *       dedicated server 的主執行緒符合那個 thread 條件）。</li>
     *   <li>{@code getItemConfig() == null}——{@code Item.InstanceItem:1915} 無條件呼叫
     *       {@code ItemConfigurator.ConfigureItemOnCreate}，它在 itemConfig 非 null 時執行
     *       {@code ItemConfig.ConfigureEntityOnCreate(item)}（ItemConfigurator.java:124-135），
     *       那是對實例的任意 ECS 配置。</li>
     *   <li>{@code !isItemType(MOVEABLE)}——{@code Item.InstanceItem:1801-1805} 對 MOVEABLE 會
     *       {@code this.actualWeight = moveable.getActualWeight()}，<b>寫回共享的 script 單例</b>；
     *       所有 {@code Moveables.<sprite>} 共用同一份 script，跳過建構會讓 script 的
     *       actualWeight 停在上一個 sprite 的值，汙染其他讀 {@code Item.getActualWeight()} 的路徑。</li>
     *   <li>{@code !hasComponents()}——{@code Item.InstanceItem:1909} 無條件呼叫
     *       {@code GameEntityFactory.CreateInventoryItemEntity}，而它內部正是以
     *       {@code itemScript.hasComponents()} 決定要不要 {@code createEntity}
     *       （GameEntityFactory.java:114-117）。用 vanilla 自己的判斷當門，跳過建構就不會
     *       漏掉任何 ECS component 建立／連接。</li>
     * </ol>
     * <b>順序有意義</b>：{@code InventoryItem.getLuaCreate()} 只是
     * {@code this.scriptItem.getLuaCreate()} 的轉發，scriptItem 為 null 時它自己就 NPE，
     * 所以 scriptItem 的存在必須先驗（測試已鎖住這條）。
     */
    private static boolean cacheable(InventoryItem fresh) {
        if (fresh == null) {
            return false;
        }
        Item script = fresh.getScriptItem();
        return script != null
                && script.getLuaCreate() == null
                && script.getItemConfig() == null
                && !script.isItemType(ItemType.MOVEABLE)
                && !script.hasComponents();
    }

    private static int readMode() {
        String raw = System.getProperty("mdc.itemWeightMemo", "observe").trim().toLowerCase();
        if (raw.equals("on") || raw.equals("1") || raw.equals("true")) {
            return MODE_ON;
        }
        if (raw.equals("off") || raw.equals("0") || raw.equals("false")) {
            return MODE_OFF;
        }
        return MODE_OBSERVE;
    }

    private static void noteAnomaly(Throwable e) {
        anomalies++;
        if (anomalies <= 3L) {
            say("[MinidoracatJavaPatch][ItemWeightMemo] anomaly #" + anomalies + ": " + e);
        }
    }

    private static void announceOnce() {
        if (!announced) {
            announced = true;
            say("[MinidoracatJavaPatch][ItemWeightMemo] 首次生效 mode=" + MODE_NAME
                    + "（observe＝不保留任何實例、只量測；on＝啟用記憶化，尚未經量測驗證；"
                    + "off＝純轉發。-Dmdc.itemWeightMemo 需重啟生效）");
        }
    }

    /**
     * 週期 log。整段自己吞例外：cached-hit 路徑刻意在所有 catch 之外呼叫它
     * （避免 logger 失敗把「不該呼叫 factory」變成「呼叫了一次」），所以它自己必須不外拋。
     */
    private static void maybeLog() {
        try {
            if ((attempts & 0xFFFFF) != 0L || attempts == 0L) {
                return;
            }
            say("[MinidoracatJavaPatch][ItemWeightMemo] mode=" + MODE_NAME
                    + " attempts=" + attempts + " hits=" + hits + " misses=" + misses
                    + " types=" + (MODE == MODE_ON ? CACHE.size() : SEEN.size())
                    + " nullResults=" + nullResults + " uncacheable=" + uncacheable
                    + " overflow=" + overflow + " anomalies=" + anomalies
                    + " ~vanillaNsAvg=" + avg(vanillaNs, vanillaSamples)
                    + " ~memoNsAvg=" + avg(memoNs, memoSamples)
                    + "（hits/(hits+misses)＝可快取型別的命中率；hits/attempts＝啟用 on 之後真正能省下的"
                    + "建構比例；uncacheable＝null 或被五道門擋下、on 模式下每次仍會重新建構的呼叫；"
                    + "vanillaNsAvg＝原版單次建構耗時；memoNsAvg 僅 on 模式有值＝快取查找耗時；"
                    + "計數未同步，為近似值）");
        } catch (RuntimeException | LinkageError ignored) {
            // 觀測失敗就靜默——它的唯一職責是不要傷到 caller
        }
    }

    /** log 通道故障不該把觀測升級成 caller 的例外（也就不會誤觸 phase 邊界）。 */
    private static void say(String message) {
        try {
            DebugType.Multiplayer.println(message);
        } catch (RuntimeException | LinkageError ignored) {
            // 觀測失敗就靜默——這條路徑的唯一職責是不要傷到 caller
        }
    }

    private static long avg(long sum, long samples) {
        return samples == 0L ? -1L : sum / samples;
    }

    private ItemWeightMemo() {}

    // ---- 測試掛鉤（package-private；正式路徑不呼叫）----

    static void resetForTest() {
        CACHE.clear();
        SEEN.clear();
        hits = 0L;
        misses = 0L;
        nullResults = 0L;
        uncacheable = 0L;
        overflow = 0L;
        anomalies = 0L;
        attempts = 0L;
        factoryCalls = 0L;
        vanillaNs = 0L;
        vanillaSamples = 0L;
        memoNs = 0L;
        memoSamples = 0L;
    }

    /** {hits, misses, nullResults, uncacheable, overflow, anomalies, factoryCalls} */
    static long[] statsForTest() {
        return new long[] { hits, misses, nullResults, uncacheable, overflow, anomalies, factoryCalls };
    }

    static String modeNameForTest() {
        return MODE_NAME;
    }

    static int cacheSizeForTest() {
        return CACHE.size();
    }

    static int seenSizeForTest() {
        return SEEN.size();
    }

    static boolean cacheableForTest(InventoryItem item) {
        return cacheable(item);
    }

    static void seedForTest(String fullType, InventoryItem item) {
        CACHE.put(fullType, item);
    }

    /** 走正式 {@link #store} 路徑（測試五道門是否真的被套用，而非只測謂詞）。 */
    static void storeForTest(String fullType, InventoryItem item) {
        store(fullType, item);
    }

    /**
     * 走正式 {@link #noteObserved} 記帳路徑。測試環境的 ScriptManager 沒有任何 item，
     * factory 永遠回 null，所以 would-hit 那條分支無法靠 {@link #createItem} 走到——
     * 這個掛鉤讓三條分支（不可快取／重複且可快取／首見且可快取）都能被鎖住。
     */
    static void noteObservedForTest(String fullType, InventoryItem fresh, boolean seen) {
        noteObserved(fullType, fresh, seen);
    }
}
