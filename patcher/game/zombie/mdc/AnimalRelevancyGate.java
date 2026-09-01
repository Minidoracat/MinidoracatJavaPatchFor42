package zombie.mdc;

import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;

/**
 * W13 動物同步範圍對齊（2026-08-24 封包鑑識；docs/patches.md 2aa）。
 *
 * <p><b>現象</b>：正式服穩態出向流量中，帶動物完整快照特徵（{@code maxWeight}／
 * {@code ageToGrow}／{@code fertility} 等基因欄位名）的封包占 38.3–39.8%。雙向 pcap
 * 解碼顯示 8.03 秒內 109 次 client→server 請求對 109 次 server→client 完整快照，但只有
 * <b>14 個</b>相異 {@code (client endpoint, animal onlineID)}——14/14 全部在 5 秒內重複，
 * 每個 ID 收到 5–10 次完整快照，<b>87.2%</b> 是第一次以外的重送，每份約 1.1 KiB。
 * 完整數據表與方法見 docs/patches.md 2aa（此處不重複，避免兩份數字漂移）。
 *
 * <p><b>vanilla 缺陷（幾何不一致）</b>：連線握手時
 * （{@code GameServer.receivePlayerConnect}，反編譯 GameServer.java:2771-2772,2788-2789）
 * server 從 client 送來的 chunk grid width 導出：
 * <pre>
 *   range          = clamp(client chunk grid width, 12, 20)
 *   relevantRange  = range/2 + 2          // 動物半徑用它
 *   chunkGridWidth = range                // server 保存的 clamp 後寬度
 * </pre>
 * 對正常、未被 clamp 的奇數 width，動物 relevancy 半徑是
 * {@code (getRelevantRange()-2)*10 == (range/2)*10}（javap offset 233-242），
 * 大於 chunk 視窗保證安全的半寬下界 {@code (range/2)*8}。環帶中若有 client 尚未載入
 * GridSquare 的動物，client 會因沒有 instance 而要求完整快照；server 回
 * {@code IsoAnimal.save()} 後，client 的 {@code AnimalPacket.isConsistent} 又要求
 * {@code getCell().getGridSquare(...) != null}，失敗就整段跳過、不建 instance，
 * 下一個 800/1000 ms 更新再要求一次。pcap 的重送節拍與此閉環一致，但沒有逐封包
 * client loaded-set 證據，不能宣稱整個環帶每一格都未載入。
 *
 * <p><b>本 helper 的語意</b>：改道 {@code sendUpdateToClient} 內唯一的
 * {@code UdpConnection.RelevantTo(FFF)Z} callsite，把半徑夾到 client 載入矩形的
 * <b>最窄側下界</b> {@code (getChunkGridWidth()/2) * 8}。只對
 * {@code stored ∈ {13,15,17,19}} 生效——12/20 是 clamp 多解，14/16/18 雖是
 * 唯一反函數，但偶數 loaded rectangle 不對稱，同一公式會 over-reach。
 *
 * <p><b>為什麼是整數除法而不是 {@code range * 4}</b>：{@code IsoChunkMap.CalcChunkWidth}
 * 強制 grid width 為<b>奇數</b>（自動計算上限 19；debug 選項 5/7/9/11/13），而
 * {@code GameServer} 只把值 clamp 到 12–20。奇數 range 下 {@code range/2} 會截斷：
 * range=13 時載入矩形是中心 chunk ±6 chunks ＝ 48 squares，但 {@code range*4} 會算成 52，
 * 反而超出載入區 4 squares。用 {@code (range/2)*8} 才與 vanilla 的
 * {@code relevantRange} 推導同源（同一個整數除法）。
 *
 * <p><b>唯一 clamp 反函數仍不足</b>：載入視窗是 {@code minChunk = center - range/2}、
 * {@code maxExclusive = minChunk + range}，不是「左右各 {@code range/2} chunks」。
 * 偶數 {@code stored ∈ {14,16,18}} 高側少 1 chunk，{@code (stored/2)*8} 會超過
 * 至少一個 offset 的真共同下界 1–8 squares，故 parity guard 讓它們回 0、走 passthrough。
 *
 * <p><b>載具排除（必要，不是保守起見）</b>：{@code IsoChunkMap.ProcessChunkPos}
 * （IsoChunkMap.java:868-878）在玩家位於載具時把 chunk-map 中心沿行進方向前移
 * {@code currentSpeedKmHour / 5} squares（乘客為 {@code min(s*2, 20)}，駕駛無上限）。
 * server 端的 {@code releventPos} 是玩家實際座標，<b>完全不知道這個前移</b>，所以載具
 * 情境下任何以玩家為中心的半徑都會同時：前側擋掉已載入格（動物該出現卻不出現）、
 * 後側放行未載入格（重送迴圈照舊）。因此只要該連線任一 player 在載具內就整段
 * passthrough；其餘情境也只有在下列完整前提成立時才 enforce。
 *
 * <p><b>殘留誤差（刻意接受，不是疏漏）</b>：client 載入範圍是 <b>chunk 對齊矩形</b>，
 * 這裡夾的是<b>連續半徑</b>（{@code RelevantTo} 是軸對齊方形判定，非圓形）。奇數
 * grid 下 player 在 chunk 內的連續偏移 {@code p ∈ [0,8)}，讓兩側可用寬度相差小於
 * 8 squares。夾到 {@code (range/2)*8} 是所有 p 的共同安全下界：
 * <ul>
 *   <li><b>over-send 為 0</b>只在完整前提下成立：未被 clamp 的奇數 grid
 *       （{@code stored ∈ {13,15,17,19}}）、該 player 的 server/client center chunk
 *       一致、相關 chunks 已 streaming 完成、且 {@code RelevantTo} 走 radius 分支
 *       而非未確認載入完成的 {@code connectArea} 命中。只在此前提下可說
 *       over-send=0；否則只稱保守 mitigation，不保證閉環完全消失。</li>
 *   <li><b>under-send 小於 8 squares</b>：較寬側那些格子其實已載入，動物卻不同步
 *       ⇒ 視野最外緣不到一格 chunk 的動物可能晚出現。這是本刀付出的代價。</li>
 * </ul>
 *
 * <p><b>中心與 streaming 都是獨立前提</b>：server 的 {@code releventPos} 只在
 * {@code PlayerPacket.processServer} 收包時更新（節拍最長約 600 ms，另加網路延遲），
 * 而 client 的 chunk-map 中心由本機座標即時決定。步行跨 chunk 邊界或 teleport 時，
 * 兩端 center chunk 可能短暫不同；即使中心相同，{@code WorldStreamer} 也可能尚未完成
 * 相關 chunks。兩者都可能留下 under-send 或 over-send 暫態，不能宣稱「步行一律安全」。
 * 要完全消除得由 client 判斷 loaded set，或由 client 明確回報給 server——純 server
 * 半徑做不到。
 *
 * <p><b>coop／split-screen 自動退化</b>：{@code UdpConnection.RelevantTo} 會先比對
 * {@code connectArea[n]}（以 chunk 為單位的精確矩形），命中即回 true 而不看 radius
 * （miss 才落到 radius 比較）。有 connectArea 覆蓋的動物因此不受本刀影響。
 *
 * <p><b>三態</b>（{@code -Dmdc.animalRelevancy}）：{@code 1}／未設 enforce、
 * {@code 2} observe（回 vanilla 結果、只統計 suppressed 判定差集）、{@code 0} off
 * （緊急降級，不需重新部署）。
 *
 * <p><b>刻意不做的事</b>：
 * <ul>
 *   <li>不改 {@code isAnimalOnScreen}——它同樣有 {@code (relevantRange-2)*10} 的形狀
 *       （javap offset 49-56），但語意是「螢幕內？」用來選 800 vs 1000 ms 節拍。
 *       這也是本刀採 redirect 而非 method-scope constChange 的原因之一：常數手術改不出
 *       runtime kill switch，也無法把半徑來源換成 {@code getChunkGridWidth}。</li>
 *   <li>不動 requested 區處理（{@code setRequested} 無 relevancy／無冷卻，每包上限 150）
 *       ——可信奇數、中心一致、streaming 完成且走 radius 分支時，縮掉環帶輕量包可避免
 *       由該路徑觸發新請求；主動要求任意 onlineID 的放大面另案評估。</li>
 *   <li>不改 {@code IsoAnimal.save} 的位元格式（動 wire schema 就必須雙端 patch）。</li>
 * </ul>
 */
public final class AnimalRelevancyGate {

    private static final int MODE_OFF = 0;
    private static final int MODE_ENFORCE = 1;
    private static final int MODE_OBSERVE = 2;

    private static final int MODE = parseMode();

    /** client 每載入 1 chunk ＝ 8 squares。 */
    private static final int SQUARES_PER_CHUNK = 8;
    /** {@code RelevantTo} 對 4 個 split-screen player index 迴圈；載具檢查沿用同一上界。 */
    private static final int MAX_LOCAL_PLAYERS = 4;
    /**
     * 可信任的 grid width 區間上界／下界。{@code GameServer.receivePlayerConnect}
     * 存進 connection 的是 {@code max(12, min(20, raw))}，所以：
     * <ul>
     *   <li>{@code stored == 12} ⟺ {@code raw <= 12}（可能是 debug 的 5/7/9/11 或低解析度
     *       自動值）——多解，無法還原 client 真實載入寬度；</li>
     *   <li>{@code stored == 20} ⟺ {@code raw >= 20}——同樣多解；</li>
     *   <li>{@code 13 <= stored <= 19} ⟺ {@code raw == stored}——clamp 不動，唯一解。</li>
     * </ul>
     * 唯一反函數仍不足：偶數 {@code stored ∈ {14,16,18}} 的 loaded rectangle 不對稱，
     * {@code (stored/2)*8} 會 over-reach 1–8 格。{@code alignedRadius} 另以 parity
     * guard 擋掉它們，只對 {@code stored ∈ {13,15,17,19}} 回非 0。
     */
    private static final int MIN_TRUSTED_GRID = 13;
    private static final int MAX_TRUSTED_GRID = 19;

    /** 判定總次數；heartbeat 節拍的唯一來源（<b>不能</b>用來推導 allowed——見 rejected）。 */
    private static final AtomicLong calls = new AtomicLong();
    /**
     * enforce 下夾過半徑判定為 false 的次數。
     * <b>不等於環帶占比、也不等於節省量</b>：vanilla 半徑外、本來就不會送的遠距動物也計在內
     * （{@code toSendList} 未經距離預篩）。而 {@code calls - rejected} <b>不是</b> allowed
     * ——passthrough 分支的 vanilla-false 結果不進 {@code rejected}。
     */
    private static final AtomicLong rejected = new AtomicLong();
    /**
     * observe 下「vanilla 為真而夾過為假」的<b>判定差集</b>。
     * <b>不是</b>實際浪費流量的占比：較寬側小於 8 squares 的區域可能已載入卻被夾掉
     * （under-send，不會產生重送）；中心漂移與 streaming 空窗又使判定矩形不等於
     * client loaded set。真正的浪費率仍要用 pcap 的重複快照指標量。
     */
    private static final AtomicLong suppressed = new AtomicLong();
    /** 走 vanilla 半徑的次數（載具排除／偶數或 clamp 邊界／grid width 未同步／夾不縮小）。 */
    private static final AtomicLong passthrough = new AtomicLong();
    /** helper 自身診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    /**
     * heartbeat 週期。這是熱路徑（每 client × 每 relevant 動物 × 每 tick 各一次），
     * 63 人／上百隻動物時每秒數萬次，故週期遠大於 AnimalSortGuard 的 16384。
     *
     * <p>節拍刻意用獨立的 {@code calls}：若拿結果計數器之和取模，observe 模式下
     * {@code suppressed} 不增加的呼叫會讓和停在同一個值，一旦命中倍數就每次呼叫都印。
     */
    private static final long HEARTBEAT_EVERY = 1L << 20;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalRelevancy] ";

    private static int parseMode() {
        String raw = System.getProperty("mdc.animalRelevancy");
        if (raw == null) {
            return MODE_ENFORCE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "2":
            case "observe":
                return MODE_OBSERVE;
            default:
                return MODE_ENFORCE;
        }
    }

    /**
     * {@code AnimalSynchronizationManager.sendUpdateToClient} 內唯一
     * {@code UdpConnection.RelevantTo(FFF)Z} callsite 的改道目標
     * （receiver 前置為第一參數；堆疊淨效果與指令長度皆與 invokevirtual 相同）。
     *
     * @param connection    目標連線
     * @param x             動物 X（squares）
     * @param y             動物 Y（squares）
     * @param vanillaRadius vanilla 算出的 {@code (relevantRange-2)*10}
     * @return 是否要對該連線同步這隻動物
     */
    public static boolean relevantTo(UdpConnection connection, float x, float y, float vanillaRadius) {
        if (MODE == MODE_OFF || connection == null) {
            return vanilla(connection, x, y, vanillaRadius);
        }
        float aligned = alignedRadius(connection);
        // 夾不縮小就沒有可省的環帶：chunkGridWidth 尚未同步（握手前）、vanilla 已更保守、
        // 載具情境（client chunk 中心前移，server 對不上）、或非有限的 vanillaRadius。
        if (!(aligned > 0.0f) || !(aligned < vanillaRadius) || anyPlayerInVehicle(connection)) {
            passthrough.incrementAndGet();
            tick();
            return vanilla(connection, x, y, vanillaRadius);
        }
        if (MODE == MODE_OBSERVE) {
            boolean vanillaResult = vanilla(connection, x, y, vanillaRadius);
            // 只量測、不改行為：vanilla 為真而對齊後為假的判定差集，不是實際浪費率
            if (vanillaResult && !connection.RelevantTo(x, y, aligned)) {
                suppressed.incrementAndGet();
            }
            tick();
            return vanillaResult;
        }
        boolean gated = connection.RelevantTo(x, y, aligned);
        if (!gated) {
            rejected.incrementAndGet();
        }
        tick();
        return gated;
    }

    /**
     * client 載入矩形兩側的共同下界（squares）：{@code (chunkGridWidth/2) * 8}。
     * 整數除法是刻意的——見類別註解「為什麼是整數除法」。
     * 只接受 {@code stored ∈ {13,15,17,19}}；12／20（clamp 多解）、14／16／18
     * （偶數幾何不對稱）或未同步時回 0 ⇒ 呼叫端走 passthrough。
     */
    private static float alignedRadius(UdpConnection connection) {
        try {
            int stored = connection.getChunkGridWidth();
            if (stored < MIN_TRUSTED_GRID || stored > MAX_TRUSTED_GRID || stored % 2 == 0) {
                return 0.0f;
            }
            return (stored / 2) * SQUARES_PER_CHUNK;
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            return 0.0f;
        }
    }

    /**
     * 該連線是否有任何 local player 在載具內。載具會讓 client 的 chunk-map 中心沿行進
     * 方向前移（IsoChunkMap.java:868-878），server 無從得知，故整段退回 vanilla。
     */
    private static boolean anyPlayerInVehicle(UdpConnection connection) {
        try {
            for (int n = 0; n < MAX_LOCAL_PLAYERS; n++) {
                IsoPlayer player = connection.getPlayerAt(n);
                if (player != null && player.getVehicle() != null) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            // 判不出來就當作在載具：寧可退回 vanilla，不要用可能錯的中心去夾
            return true;
        }
    }

    private static boolean vanilla(UdpConnection connection, float x, float y, float radius) {
        if (connection == null) {
            anomalies.incrementAndGet();
            return false;
        }
        return connection.RelevantTo(x, y, radius);
    }

    private static void tick() {
        if (calls.incrementAndGet() % HEARTBEAT_EVERY == 0L) {
            heartbeat();
        }
    }

    private static void heartbeat() {
        try {
            DebugLog.log(TAG + "mode=" + MODE
                    + " calls=" + calls.get()
                    + " rejected=" + rejected.get()
                    + " suppressed=" + suppressed.get()
                    + " passthrough=" + passthrough.get()
                    + " anomalies=" + anomalies.get());
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    /** 測試掛點（package-private，與 ChunkLoadGuard 同慣例）：目前模式。 */
    static int mode() {
        return MODE;
    }

    /** 測試用：enforce 下夾過半徑判定為 false 的次數（含遠距動物，非環帶占比）。 */
    static long rejectedCount() {
        return rejected.get();
    }

    /** 測試用：observe 下的判定差集（不是實際浪費率）。 */
    static long suppressedCount() {
        return suppressed.get();
    }

    /** 測試用：走 vanilla 半徑的次數。 */
    static long passthroughCount() {
        return passthrough.get();
    }

    /** 測試用：helper 自身診斷失敗數，恆應為 0。 */
    static long anomalyCount() {
        return anomalies.get();
    }

    private AnimalRelevancyGate() {}
}
