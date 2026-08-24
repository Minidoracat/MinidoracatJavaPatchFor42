package zombie.mdc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

import gnu.trove.iterator.TLongObjectIterator;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.map.hash.TShortLongHashMap;

import zombie.characters.animals.IsoAnimal;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * W14 動物 requested 冷卻＋範圍閘（2026-08-24 W13 上線後量測的第二刀；docs/patches.md 2ab）。
 *
 * <p><b>動機（實測）</b>：W13 上線後跨 transport 重量測（60 s 穩態、s2c entry coverage 100%）：
 * 步行／direct 族群的 request→full 閉環已消失（0 request），但殘留 598 份 full 之中
 * 96.2% 落在 vanilla 環帶、98.5% 來自單一「全程在載具內」的連線——正是 W13 刻意
 * passthrough 回 vanilla 的分支。172/181 tuple 在收到 full 後 ≥1 s 仍再要、間隔鎖在
 * 800/1000 ms timer。載具情境下 server 半徑天生算不準（client chunk 中心沿行進方向
 * 前移），所以第二刀不再依賴幾何：<b>「同一連線同一動物，冷卻窗內只回一次完整快照」</b>。
 * 完整量測數據見 MinidoracatServerAnalyze
 * {@code reports/ops/2026-08-24-animal-fullgenome-network.md} §7（此處不複誦數字）。
 *
 * <p><b>手術（兩個 redirect，都在 {@code AnimalSynchronizationManager.sendUpdateToClient}）</b>：
 * <ol>
 *   <li><b>連線捕獲</b>：{@code UdpConnection.getPacket(PacketType)} invokevirtual 恰 2 處
 *       （reliable／unreliable 分支，javap offset 12/31）→ {@link #getPacket}。兩處等價
 *       （任一分支都是「本連線本 tick 發送流程的起點」），把 connection 存進 ThreadLocal
 *       供同 tick 稍後的 filter 做範圍判定。{@code sendRequestToServer} 用的是
 *       invokeinterface {@code IConnection.getPacket}（不同 opcode＋owner），redirect
 *       按 (opcode, owner, name, desc) 精確匹配，不會誤中 client 路徑。</li>
 *   <li><b>requested 過濾</b>：{@code HashMap.get(Object)} invokevirtual 恰 3 處
 *       （offset 83 ＝ {@code requests.get(guid)}，requested 填充來源；offset 370/419 ＝
 *       {@code connection.timerUpdateAnimal.get(Short)}）→ {@link #filterRequests}。
 *       三處同簽名無法在 bytecode 層分開，改在 runtime 用 <b>key 型別</b>分流：
 *       目標 map 的 key 是 {@code Long}（connection GUID），timer map 的 key 是
 *       {@code Short}——{@code key instanceof Long} 是完美判別式，timer 路徑
 *       零配置直通（熱路徑，每動物每 tick 兩次）。</li>
 * </ol>
 *
 * <p><b>為什麼在 {@code requests.get} 過濾是 wire-safe 的</b>：vanilla 對回傳集合只做
 * iterate（填進 {@code packet.requested}），不 mutate；send 後的清除動作走另一次
 * {@code computeIfAbsent}（javap offset 541-547），清的是原 map entry，不是這裡的回傳值。
 * 而 {@code AnimalUpdatePacket.write} 的 requested 區本來就把「實際寫入數」回填進
 * requestedCount（write 對 animal==null 直接跳過），所以少放幾個 ID 不改任何線上格式。
 * 濾到全空且 updated/deleted 也空時，該 tick 不送包、原 map entry 不被清。
 * <b>不卡死的真正理由是 server 端保留 map、下一 tick 重新過濾</b>——不是「client 會重送」：
 * client 的 {@code sendRequestToServer} 只在它<b>收到</b> AnimalUpdate 包之後才跑
 * （{@code parse} → {@code processClient} 尾端），我們不送包它就不會重送。因此
 * <b>絕對不可以</b>在濾空時順手清掉 map entry：那會把索取直接丟掉，直到下一份 inbound
 * 包才有機會恢復（可能很久不來），動物就延遲出現或永不出現。
 *
 * <p><b>冷卻語意</b>：
 * <ul>
 *   <li><b>第一次一定放行</b>——動物「永遠不出現」的風險只存在於「丟棄且不回應」的設計，
 *       這裡不存在：首發照answer，只有冷卻窗內的<b>重複</b>索取被暫時擋下。</li>
 *   <li>標記時機＝過濾放行時。放行後同 tick 內必然序列化（同執行緒、無讓出點；
 *       動物在 filter 與 write 之間不會消失——AnimalInstanceManager 只在同一主執行緒變動）。
 *       本 filter 的輸出上限就是 vanilla 自己的 {@code MAX_ANIMALS_PER_PACKET}（150），
 *       所以 vanilla 的填充迴圈不會再截掉任何已 mark 的 ID——<b>mark 與實際送出對齊</b>。
 *       已知殘留例外：{@code AnimalUpdatePacket.write} 的 requested 區若中途拋
 *       {@code IOException}，vanilla 會把 count 回填為 0，但這批 ID 已被 mark，
 *       於是要多等一個冷卻窗（預設 6 秒）才會重送。這是罕見故障路徑，刻意接受。</li>
 *   <li>observe 也標記：observe 不擋任何東西，所以「放行」＝「實際送出」，標記是
 *       正確的實態記錄；日後切 enforce 帶著熱狀態，不會出現切換瞬間的重送尖峰。</li>
 * </ul>
 *
 * <p><b>範圍閘（abuse 面，獨立開關）</b>：已登入 client 可對任意 onlineID 索取完整
 * {@code IsoAnimal.save()}（vanilla 無任何 relevancy 檢查）。這裡以 vanilla 自己的半徑
 * 公式 {@code (getRelevantRange()-2)*10} 加上 {@code +48} squares 的寬裕邊界拒絕明顯
 * 超遠的索取。+48 覆蓋載具 look-ahead（駕駛前移 speedKmH/5：48 格＝240 km/h；乘客上限
 * 20 格）——被拒的 ID 距離之遠，連 vanilla 的 updated 路徑都不會對該連線宣告它，
 * 「看不到」本來就是正確行為；玩家回到範圍內後 updated 恢復、索取即被回應。
 * ThreadLocal 捕獲缺失（理論上不可能：同方法內 getPacket 先於 requests.get）時
 * 跳過範圍檢查、只做冷卻——fail-open 到較保守的那一側。
 *
 * <p><b>狀態與回收</b>：{@code guid → (onlineID → lastSentMs)}，Trove primitive map
 * （無 boxing）。三道自癒界線：(1) 逾 {@code cooldownMs} 的條目天然失效（只比時間差，
 * 不需刪除）；(2) 每 30 s 掃一次、回收 120 s 未觸碰的整個 guid bucket（斷線連線由此
 * 回收，不需要 hook disconnect）；(3) 單 bucket 達 2048 條目即<b>拒記新 ID</b>
 * （{@code markRefused} 計數）——刻意不清空整桶：清空會讓既有冷卻全部失效，等於開了
 * 一條「用大量相異 ID 清掉目標動物冷卻」的路徑。拒記的最壞後果是超額 ID 退化成
 * vanilla 行為，既有條目仍受保護，不會 OOM。
 * 所有狀態變動都在 server 動物同步執行緒上（update() 單執行緒逐連線呼叫，遞迴
 * pending 亦同執行緒）；AtomicLong 計數器只為跨執行緒讀 heartbeat 的正確性。
 *
 * <p><b>三態（兩把獨立 kill switch，不需重新部署）</b>：
 * {@code -Dmdc.animalRequestCooldown}＝{@code 1}／未設 enforce、{@code 2} observe
 * （只計數不過濾）、{@code 0} off；{@code -Dmdc.animalRequestCooldownMs} 冷卻毫秒
 * （預設 6000，夾在 [1000, 30000]）。{@code -Dmdc.animalRequestRange} 同三態、
 * 獨立於冷卻。兩者皆 off 時 {@link #filterRequests} 純委派 {@code map.get(key)}。
 *
 * <p><b>刻意不做的事</b>：
 * <ul>
 *   <li>不動 {@code AnimalUpdatePacket.write}／{@code parse}——wire 格式零改動，
 *       vanilla client 相容。</li>
 *   <li>不改 W13 的半徑對齊（另一把 kill switch，兩刀獨立降級）。</li>
 *   <li>不在 helper 裡讀 client loaded set——做不到也不需要：冷卻對幾何不可知，
 *       這正是它能覆蓋載具族群的原因。</li>
 * </ul>
 */
public final class AnimalRequestGate {

    private static final int MODE_OFF = 0;
    private static final int MODE_ENFORCE = 1;
    private static final int MODE_OBSERVE = 2;

    private static final int COOLDOWN_MODE = parseMode("mdc.animalRequestCooldown");
    private static final int RANGE_MODE = parseMode("mdc.animalRequestRange");
    private static final long COOLDOWN_MS = parseCooldownMs();

    /** vanilla 半徑之外再放寬的邊界（squares）：覆蓋載具 look-ahead（240 km/h）。 */
    private static final float RANGE_MARGIN = 48.0f;
    /**
     * 每 tick 每連線的過濾工作量上界，對齊 vanilla 自己的
     * {@code MAX_ANIMALS_PER_PACKET}（{@code sendUpdateToClient} 的
     * {@code animalsCount >= 150 → break}）。requested 集合大小由 client 決定且無上限。
     */
    private static final int MAX_ANIMALS_PER_PACKET = 150;
    private static final long SWEEP_INTERVAL_MS = 30_000L;
    private static final long BUCKET_TTL_MS = 120_000L;
    private static final int BUCKET_CAP = 2048;
    /**
     * heartbeat 週期。filter 只在 requested 填充來源（每連線每 tick 一次，非每動物）
     * 觸發計數，量級遠低於 W13 的判定熱路徑，故週期取 2^14。
     */
    private static final long HEARTBEAT_EVERY = 1L << 14;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalRequestGate] ";

    /** 同 tick 內由 {@link #getPacket} 捕獲、供 {@link #filterRequests} 做範圍判定。 */
    private static final ThreadLocal<UdpConnection> CURRENT = new ThreadLocal<>();

    /** guid → (onlineID → lastSentMs)。只在 server 動物同步執行緒上變動。 */
    private static final TLongObjectHashMap<Bucket> STATE = new TLongObjectHashMap<>();
    private static long lastSweepMs;

    /** 測試用時鐘偏移：加在 {@code System.currentTimeMillis()} 上（package-private）。 */
    static long clockSkewForTest;

    /** Long-key filter 呼叫數；heartbeat 節拍的唯一來源。 */
    private static final AtomicLong calls = new AtomicLong();
    /**
     * 放行的 ID 數。因為本 filter 的輸出上限＝vanilla 的 150，這個值與「實際進入
     * packet.requested 的 ID 數」一致；唯一例外是 write 期間 {@code IOException}
     * 把 count 回填 0 的故障路徑。<b>不是</b>「新動物數」——冷卻窗過後重送也 +1。
     */
    private static final AtomicLong accepted = new AtomicLong();
    /** enforce 下被冷卻擋下的 ID 數。 */
    private static final AtomicLong cooldownSuppressed = new AtomicLong();
    /** enforce 下被範圍閘擋下的 ID 數。 */
    private static final AtomicLong rangeSuppressed = new AtomicLong();
    /** observe 下「本會被冷卻擋下」的 ID 數（行為未改）。 */
    private static final AtomicLong cooldownObserved = new AtomicLong();
    /** observe 下「本會被範圍閘擋下」的 ID 數（行為未改）。 */
    private static final AtomicLong rangeObserved = new AtomicLong();
    /** bucket 達上限而拒記新 ID 的次數（安全閥，非錯誤；既有冷卻不受影響）。 */
    private static final AtomicLong markRefused = new AtomicLong();
    /** helper 自身診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    private static final class Bucket {
        long touchedMs;
        final TShortLongHashMap sentMs = new TShortLongHashMap();
    }

    private static int parseMode(String property) {
        String raw = System.getProperty(property);
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

    private static long parseCooldownMs() {
        String raw = System.getProperty("mdc.animalRequestCooldownMs");
        long value = 6000L;
        if (raw != null) {
            try {
                value = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // 保持預設；壞值不該讓 class init 失敗
            }
        }
        return Math.max(1000L, Math.min(30_000L, value));
    }

    /**
     * {@code sendUpdateToClient} 兩個 reliability 分支的 {@code connection.getPacket(...)}
     * 改道目標：捕獲本 tick 的 connection 後原樣委派。不 try/catch——委派拋什麼就讓
     * vanilla 拋什麼。
     *
     * <p>捕獲值由 {@link #filterRequests} 消費後<b>立即清除</b>（見該方法 finally）：
     * 這樣「捕獲缺失」永遠退化成 {@code null}→跳過範圍檢查（fail-open 到保守側），
     * 而不是沿用<b>上一個連線</b>的位置去判定——後者會用錯誤玩家座標放行/拒絕快照。
     * 附帶效果是不長期釘住 {@code UdpConnection} 物件圖（含其 1 MB buffer）。
     * <b>這個保證要成立，`filterRequests` 的每一條 Long-key 路徑都必須經過該 finally</b>
     * ——包含兩把 kill switch 都 off 的組態（否則 both-off 下這裡每次 set 都不會被清）。
     */
    public static INetworkPacket getPacket(UdpConnection connection, PacketTypes.PacketType type) {
        CURRENT.set(connection);
        return connection.getPacket(type);
    }

    /**
     * {@code sendUpdateToClient} 內 3 個 {@code HashMap.get(Object)} 的改道目標。
     * timer map（key 是 {@code Short}）與任何非預期形狀一律原樣直通；只有
     * {@code requests.get(Long guid)} 那一處進入過濾。
     */
    public static Object filterRequests(HashMap map, Object key) {
        Object raw = map.get(key);
        // 熱路徑（timer map，每動物每 tick 兩次）：不進 try、不碰 ThreadLocal。
        // 這條也不需要清 CURRENT——timer 的 get 在 bytecode 上晚於 requests.get，
        // 到這裡時同一 tick 的 Long-key 呼叫已經清過了。
        if (!(key instanceof Long) || !(raw instanceof HashSet)) {
            return raw;
        }
        try {
            // both-off 的 early return 刻意放在 try <b>內</b>：放外面會繞過 finally，
            // 於是 getPacket 每次 set 都沒人清，ThreadLocal 長期釘住最後一個連線
            // （getPacket javadoc 宣稱要避免的正是這件事）。這條每連線每 tick 只到一次，
            // 進 try 的成本可忽略。
            if (COOLDOWN_MODE == MODE_OFF && RANGE_MODE == MODE_OFF) {
                return raw;
            }
            HashSet<?> requested = (HashSet<?>) raw;
            tick();
            long now = now();
            // sweep 在 isEmpty fast-path 之前：requests.get 每連線每 tick 都會執行，
            // 即使之後再無任何 requested，殘留 bucket 也會在 120 秒內被回收。
            maybeSweep(now);
            if (requested.isEmpty()) {
                return raw;
            }
            long guid = (Long) key;
            UdpConnection connection = CURRENT.get();
            HashSet<Object> out = new HashSet<>(Math.max(8, MAX_ANIMALS_PER_PACKET * 2));
            int emitted = 0;
            for (Object element : requested) {
                // 工作量上界＝vanilla 自己的上限。requestedCount 是 client 端未設限的 int
                // （AnimalUpdatePacket.parse:146-148 直接照數字讀），vanilla 靠 send 迴圈的
                // `animalsCount >= 150 → break` 把每 tick 工作量壓在 150；若這裡改成全掃，
                // 一包 65,536 個 ID 就能把每 tick 成本從 O(150) 放大到 O(65,536)。
                if (emitted >= MAX_ANIMALS_PER_PACKET) {
                    break;
                }
                if (!(element instanceof Short)) {
                    out.add(element);
                    emitted++;
                    continue;
                }
                short onlineID = (Short) element;
                // 存在性查詢與 RANGE_MODE 無關：不存在的 ID 一律<b>不 mark</b>，否則
                // client 可用大量不存在 ID 灌爆 bucket 觸發淘汰、藉此清掉真動物的冷卻。
                // 仍然保留在 out（vanilla write 對 null 本來就跳過，且集合非空才會送包→
                // 清掉原 map entry，避免同一批 stale ID 每 tick 重掃）。
                IsoAnimal animal = AnimalInstanceManager.getInstance().get(onlineID);
                if (animal == null) {
                    out.add(element);
                    emitted++;
                    continue;
                }
                if (!allowRange(connection, animal) && RANGE_MODE == MODE_ENFORCE) {
                    continue;
                }
                if (!allowCooldown(guid, onlineID, now) && COOLDOWN_MODE == MODE_ENFORCE) {
                    continue;
                }
                if (COOLDOWN_MODE != MODE_OFF) {
                    mark(guid, onlineID, now);
                }
                accepted.incrementAndGet();
                out.add(element);
                emitted++;
            }
            return out;
        } catch (RuntimeException | LinkageError e) {
            // 只印第一次：heartbeat 只給總數，運維看到 anomalies>0 需要知道是 RelevantTo／
            // AnimalInstanceManager.get／Trove 哪一條路壞掉。之後同類例外只累加計數。
            if (anomalies.getAndIncrement() == 0L) {
                try {
                    DebugLog.log(TAG + "fail-open（本次回 vanilla raw）："
                            + e.getClass().getName() + ": " + e.getMessage());
                } catch (RuntimeException | LinkageError ignored) {
                    // log 基礎設施本身壞掉不得再外逃
                }
            }
            return raw;
        } finally {
            // 消費完即清：見 getPacket 的 javadoc（捕獲缺失必須是 null，不能是上一個連線）。
            CURRENT.remove();
        }
    }

    /** 範圍閘。回 true＝放行。animal 由呼叫端解析（存在性檢查與 RANGE_MODE 無關）。 */
    private static boolean allowRange(UdpConnection connection, IsoAnimal animal) {
        if (RANGE_MODE == MODE_OFF || connection == null) {
            return true;
        }
        float vanillaRadius = (connection.getRelevantRange() - 2) * 10;
        if (connection.RelevantTo(animal.getX(), animal.getY(), vanillaRadius + RANGE_MARGIN)) {
            return true;
        }
        (RANGE_MODE == MODE_ENFORCE ? rangeSuppressed : rangeObserved).incrementAndGet();
        return false;
    }

    /** 冷卻。回 true＝放行（含首發）。 */
    private static boolean allowCooldown(long guid, short onlineID, long now) {
        if (COOLDOWN_MODE == MODE_OFF) {
            return true;
        }
        Bucket bucket = STATE.get(guid);
        if (bucket == null) {
            return true;
        }
        long last = bucket.sentMs.get(onlineID);
        if (last != 0L && now - last < COOLDOWN_MS) {
            (COOLDOWN_MODE == MODE_ENFORCE ? cooldownSuppressed : cooldownObserved).incrementAndGet();
            return false;
        }
        return true;
    }

    private static void mark(long guid, short onlineID, long now) {
        Bucket bucket = STATE.get(guid);
        if (bucket == null) {
            bucket = new Bucket();
            STATE.put(guid, bucket);
        }
        bucket.touchedMs = now;
        // cap 是「拒記新 ID」而不是「清空整桶」：清空會讓既有冷卻全部失效，等於給了
        // 一條用大量相異 ID 清掉目標動物冷卻的路徑。拒記的最壞後果是超出上限的那些 ID
        // 退化成 vanilla 行為（不受冷卻保護），既有條目仍受保護；bucket 由 sweep 回收。
        if (bucket.sentMs.containsKey(onlineID) || bucket.sentMs.size() < BUCKET_CAP) {
            bucket.sentMs.put(onlineID, now);
        } else {
            markRefused.incrementAndGet();
        }
    }

    private static void maybeSweep(long now) {
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepMs = now;
        TLongObjectIterator<Bucket> it = STATE.iterator();
        while (it.hasNext()) {
            it.advance();
            if (now - it.value().touchedMs > BUCKET_TTL_MS) {
                it.remove();
            }
        }
    }

    private static long now() {
        return System.currentTimeMillis() + clockSkewForTest;
    }

    private static void tick() {
        if (calls.incrementAndGet() % HEARTBEAT_EVERY == 0L) {
            heartbeat();
        }
    }

    private static void heartbeat() {
        try {
            DebugLog.log(TAG + "cooldown=" + COOLDOWN_MODE + " range=" + RANGE_MODE
                    + " cooldownMs=" + COOLDOWN_MS
                    + " calls=" + calls.get()
                    + " accepted=" + accepted.get()
                    + " cooldownSuppressed=" + cooldownSuppressed.get()
                    + " rangeSuppressed=" + rangeSuppressed.get()
                    + " cooldownObserved=" + cooldownObserved.get()
                    + " rangeObserved=" + rangeObserved.get()
                    + " markRefused=" + markRefused.get()
                    + " anomalies=" + anomalies.get());
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    /** 測試掛點（package-private，與 AnimalRelevancyGate 同慣例）。 */
    static int cooldownMode() {
        return COOLDOWN_MODE;
    }

    static int rangeMode() {
        return RANGE_MODE;
    }

    static long cooldownMs() {
        return COOLDOWN_MS;
    }

    static void setConnectionForTest(UdpConnection connection) {
        CURRENT.set(connection);
    }

    /**
     * 測試用：目前 ThreadLocal 捕獲值。用來驗證「每一條 Long-key 路徑都會清掉捕獲」
     * ——包含兩把 kill switch 都 off 的組態（那條 early return 若跑到 try 外面，
     * getPacket 每次 set 都不會被清，ThreadLocal 會長期釘住最後一個連線）。
     */
    static UdpConnection capturedConnectionForTest() {
        return CURRENT.get();
    }

    static int bucketCountForTest() {
        return STATE.size();
    }

    static long acceptedCount() {
        return accepted.get();
    }

    static long cooldownSuppressedCount() {
        return cooldownSuppressed.get();
    }

    static long rangeSuppressedCount() {
        return rangeSuppressed.get();
    }

    static long cooldownObservedCount() {
        return cooldownObserved.get();
    }

    static long rangeObservedCount() {
        return rangeObserved.get();
    }

    static long markRefusedCount() {
        return markRefused.get();
    }

    static long anomalyCount() {
        return anomalies.get();
    }

    private AnimalRequestGate() {}
}
