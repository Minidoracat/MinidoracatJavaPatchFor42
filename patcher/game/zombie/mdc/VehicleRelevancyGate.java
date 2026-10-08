package zombie.mdc;

import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.Vector3;
import zombie.vehicles.BaseVehicle;

/**
 * W53 步行時縮小車輛相關範圍（2026-10-07；docs/patches.md 2bq）。
 *
 * <p><b>現象</b>：KI5／rSemiTruck 系模組車的門窗是蒙皮模型，client 對每台已載入的車每幀都完整重算骨架
 * （{@code BaseVehicle.postupdate}，沒有距離或可見性閘門），車多的地方每幀數毫秒。client 持有哪些車完全由伺服器決定：
 * client 讀 chunk 不載入車（{@code IsoChunk} 只在 {@code !GameClient.client} 讀 vehicles 區段），只對伺服器
 * {@code VehicleUpdate} 告知過的車要 {@code VehicleFullUpdate}。
 *
 * <p><b>原版範圍</b>：{@code GameServer.receivePlayerConnect} 由 client 回報的 chunk grid width 算
 * {@code relevantRange = clamp(w,12,20)/2 + 2}，{@code UdpConnection.isRelevantTo} 是 {@code ±relevantRange*8} 的正方形；
 * 1080p 以上 w=19 ⇒ ±88 格，比 client 19×19 chunk map（約 −79…+72）還寬，所以 client 載入的車數由 chunk map 決定。
 *
 * <p><b>手術</b>：兩個方法內各自唯一的 {@code UdpConnection.isRelevantTo(FF)Z} 改道到本類：
 * <ul>
 *   <li>{@code VehicleManager.sendVehicles} → {@link #sendRelevant}：判定這台車要不要對該連線送
 *       {@code VehicleUpdate}／{@code VehicleFullUpdate}。</li>
 *   <li>{@code VehicleRequestPacket.processServer} → {@link #keepRelevant}：client 每秒的 Passengers 請求，
 *       判為 false 就回 {@code VehicleRemove}，client 移除該車。</li>
 * </ul>
 * 兩處用同一個判定、同一個半徑、不做遲滯：若「送」比「留」窄，留在 client 的環帶車收不到零件更新，而
 * {@code vehicle.updateFlags} 每個 tick 送完就清，漏掉的變更不會補送。
 *
 * <p><b>判定</b>（結果一律是原版結果的子集，不會放寬）：
 * <ol>
 *   <li>原版 {@code isRelevantTo} 為 false ⇒ false。</li>
 *   <li>{@code off} ⇒ 原版結果。</li>
 *   <li>任一 {@code connectArea[n]} 非 null（握手中／co-op 加入中）或任一本地玩家在車內（駕駛或乘客）⇒ 原版。
 *       車內時 client 的 chunk map 中心往行進方向前移「時速 ÷ 5」格（{@code IsoChunkMap.ProcessChunkPos}），伺服器無從得知；
 *       開了「自動縮放」或「開車時平移鏡頭」選項（預設關閉）時畫面角落可到約 71 格；AutoDrive 之類的感測也要看到前方停著的車。</li>
 *   <li>任一 {@code releventPos[n]} 與車的平面距離 ≤ R ⇒ true；否則 {@code on} 回 false、{@code observe} 回 true，兩者都計數。</li>
 * </ol>
 *
 * <p><b>開關</b>（需重啟）：{@code -Dmdc.vehicleRelevancy}：未設定／{@code 2}／{@code observe}＝只計數照原版（預設；未知值也是 observe）；
 * {@code 1}／{@code on}／{@code enforce}＝縮小；{@code 0}／{@code off}＝原版。{@code -Dmdc.vehicleRelevancyRadius}＝R（格，預設 64，
 * 夾在 32–160）；{@code -Dmdc.vehicleRelevancyBeatSec}＝心跳最短間隔（秒，預設 300，夾在 10–3600）。
 *
 * <p><b>停著的車（只計數，2026-10-09）</b>：車內一律照原版，但車停著時 chunk map 不前移、開車平移鏡頭也不動，
 * 照步行算不會多看到什麼。為了評估「停著的車照步行算」，車內分支另計：連線上每位在車內的玩家，車速（伺服器收駕駛回報的
 * {@code VehiclePhysicsPacket}，沒有駕駛時為 0）低於 {@value #STILL_KMH} km/h 持續 {@code -Dmdc.vehicleRelevancyStillSec}
 * 秒（預設 10，夾在 0–600）才算停著；車一動或換車就重新計時。全部在車內的玩家都停著時，這次 R 外的判定計入
 * {@code stillSend}／{@code stillKeep}；已算停著的玩家再開動計入 {@code stillResumes}（啟用後每次都要補回環帶的車）。
 * 回傳值不變，仍照原版。
 *
 * <p>兩個呼叫點都在伺服器主執行緒；判定的 RuntimeException 計入 anomalies 並回原版結果。
 */
public final class VehicleRelevancyGate {

    static final int OFF = 0;
    static final int ON = 1;
    static final int OBSERVE = 2;

    static final int MODE = parseMode(System.getProperty("mdc.vehicleRelevancy"));
    static final int DEFAULT_RADIUS = 64;
    static final int MIN_RADIUS = 32;
    static final int MAX_RADIUS = 160;
    static final float RADIUS = clamp(Integer.getInteger("mdc.vehicleRelevancyRadius", DEFAULT_RADIUS),
            MIN_RADIUS, MAX_RADIUS);
    private static final float RADIUS_SQ = RADIUS * RADIUS;
    private static final long BEAT_NS = clamp(Integer.getInteger("mdc.vehicleRelevancyBeatSec", 300), 10, 3600)
            * 1_000_000_000L;

    /** {@code RelevantTo} 對 4 個 split-screen player index 迴圈；本類沿用同一上界。 */
    private static final int MAX_LOCAL_PLAYERS = 4;
    /** 每這麼多次呼叫才看一次時間（sendVehicles 是每連線 × 每台車 × 10 Hz 的熱路徑）。 */
    private static final int BEAT_CHECK_MASK = 0xFFF;
    private static final String TAG = "[MinidoracatJavaPatch][VehicleRelevancy] ";

    /** {@code sendVehicles}：原版會送、R 外而不送（on）／照送（observe）的判定次數。 */
    private static final AtomicLong sendOutside = new AtomicLong();
    /** {@code processServer}：原版會留、R 外而叫 client 移除（on）／照留（observe）的判定次數。 */
    private static final AtomicLong keepOutside = new AtomicLong();
    /** 原版為真、因連線上有玩家在車內而照原版的判定次數。 */
    private static final AtomicLong passVehicle = new AtomicLong();
    /** 原版為真、因 connectArea 非 null 而照原版的判定次數。 */
    private static final AtomicLong passConnectArea = new AtomicLong();
    /** helper 自身診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();
    /** 車內分支裡，連線上在車內的玩家全部停著時的 R 外判定次數（{@code sendVehicles}／{@code processServer}）。 */
    private static final AtomicLong stillSend = new AtomicLong();
    private static final AtomicLong stillKeep = new AtomicLong();
    /** 已算停著的玩家又開動的次數。 */
    private static final AtomicLong stillResumes = new AtomicLong();

    /** 低於這個時速（絕對值）算停著；原版 {@code BaseVehicle.isStopped} 用 0.8 km/h。 */
    static final float STILL_KMH = 1.0F;
    /** 停多久才算停著；擋紅綠燈這類短暫停車。 */
    static final long STILL_NS = clamp(Integer.getInteger("mdc.vehicleRelevancyStillSec", 10), 0, 600) * 1_000_000_000L;
    private static final int NO_VEHICLE = 0;
    private static final int MOVING = 1;
    private static final int STILL = 2;

    /**
     * 每位玩家目前坐的車與開始停住的時間；只在主執行緒用。IsoPlayer 沒覆寫 equals／hashCode。
     * 只記車輛 ID、不持有車：車的乘客欄位會參考玩家，持有車會讓斷線玩家的 key 回收不了。
     */
    private static final WeakHashMap<IsoPlayer, StillState> STILL_STATES = new WeakHashMap<>();

    private static final class StillState {
        int vehicleId = -1;
        /** 開始停住的 nanoTime；在動時無意義。 */
        long since;
        boolean stopped;
        boolean counted;
    }

    // 主執行緒單線；只用來節流心跳，競態最多讓心跳早晚一次。
    private static int ticks;
    private static long lastBeatNs;
    private static boolean announced;

    static int parseMode(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            case "1", "on", "enforce" -> ON;
            default -> OBSERVE;
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** {@code VehicleManager.sendVehicles} 內唯一 {@code isRelevantTo(FF)Z} 的改道目標。 */
    public static boolean sendRelevant(UdpConnection connection, float x, float y) {
        return decide(connection, x, y, sendOutside, stillSend);
    }

    /** {@code VehicleRequestPacket.processServer} 內唯一 {@code isRelevantTo(FF)Z} 的改道目標。 */
    public static boolean keepRelevant(UdpConnection connection, float x, float y) {
        return decide(connection, x, y, keepOutside, stillKeep);
    }

    private static boolean decide(UdpConnection connection, float x, float y, AtomicLong outside, AtomicLong stillOutside) {
        boolean vanilla = connection.isRelevantTo(x, y);
        if (!vanilla || MODE == OFF) {
            return vanilla;
        }
        tick();
        try {
            if (anyConnectArea(connection)) {
                passConnectArea.incrementAndGet();
                return true;
            }
            if (withinRadius(connection, x, y)) {
                return true;
            }
            int vehicles = vehicleState(connection);
            if (vehicles != NO_VEHICLE) {
                passVehicle.incrementAndGet();
                if (vehicles == STILL) {
                    stillOutside.incrementAndGet();
                }
                return true;
            }
            outside.incrementAndGet();
            return MODE != ON;
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            return true;
        }
    }

    private static boolean anyConnectArea(UdpConnection connection) {
        Vector3[] areas = connection.connectArea;
        for (int n = 0; n < MAX_LOCAL_PLAYERS; n++) {
            if (areas[n] != null) {
                return true;
            }
        }
        return false;
    }

    /** 任一本地玩家的相關位置與車的平面距離 ≤ R（含等於）。 */
    private static boolean withinRadius(UdpConnection connection, float x, float y) {
        Vector3[] positions = connection.releventPos;
        for (int n = 0; n < MAX_LOCAL_PLAYERS; n++) {
            Vector3 p = positions[n];
            if (p != null) {
                float dx = p.x - x;
                float dy = p.y - y;
                if (dx * dx + dy * dy <= RADIUS_SQ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 連線上在車內的玩家（駕駛或乘客）：沒有人 ⇒ {@link #NO_VEHICLE}；有人的車在動或還沒停滿 {@link #STILL_NS} ⇒ {@link #MOVING}；
     * 全部停著 ⇒ {@link #STILL}。只在「R 外」時才查。
     */
    private static int vehicleState(UdpConnection connection) {
        int state = NO_VEHICLE;
        long now = 0L;
        for (int n = 0; n < MAX_LOCAL_PLAYERS; n++) {
            IsoPlayer player = connection.getPlayerAt(n);
            BaseVehicle vehicle = player == null ? null : player.getVehicle();
            if (vehicle == null) {
                continue;
            }
            if (now == 0L) {
                now = System.nanoTime();
            }
            if (!isStill(player, vehicle, now)) {
                state = MOVING;
            } else if (state == NO_VEHICLE) {
                state = STILL;
            }
        }
        return state;
    }

    private static boolean isStill(IsoPlayer player, BaseVehicle vehicle, long now) {
        StillState s;
        synchronized (STILL_STATES) {
            s = STILL_STATES.computeIfAbsent(player, k -> new StillState());
        }
        if (s.vehicleId != vehicle.getId()) {
            s.vehicleId = vehicle.getId();
            s.stopped = false;
            s.counted = false;
        }
        if (vehicle.getCurrentAbsoluteSpeedKmHour() >= STILL_KMH) {
            if (s.counted) {
                stillResumes.incrementAndGet();
            }
            s.stopped = false;
            s.counted = false;
            return false;
        }
        if (!s.stopped) {
            s.stopped = true;
            s.since = now;
        }
        if (now - s.since < STILL_NS) {
            return false;
        }
        s.counted = true;
        return true;
    }

    private static void tick() {
        if (!announced) {
            announced = true;
            lastBeatNs = System.nanoTime();
            log("首次生效 mode=" + modeName() + " radius=" + (int) RADIUS + " stillSec=" + STILL_NS / 1_000_000_000L
                    + "（-Dmdc.vehicleRelevancy=observe|on|off，-Dmdc.vehicleRelevancyRadius，-Dmdc.vehicleRelevancyStillSec）");
            return;
        }
        if ((++ticks & BEAT_CHECK_MASK) != 0) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        log("mode=" + modeName() + " radius=" + (int) RADIUS
                + " sendOutside=" + sendOutside.get()
                + " keepOutside=" + keepOutside.get()
                + " passVehicle=" + passVehicle.get()
                + " passConnectArea=" + passConnectArea.get()
                + " stillSend=" + stillSend.get()
                + " stillKeep=" + stillKeep.get()
                + " stillResumes=" + stillResumes.get()
                + " anomalies=" + anomalies.get());
    }

    private static void log(String line) {
        try {
            DebugLog.log(TAG + line);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    static String modeName() {
        return MODE == ON ? "on" : MODE == OBSERVE ? "observe" : "off";
    }

    // ---- 測試存取器 ----

    static long sendOutsideCount() {
        return sendOutside.get();
    }

    static long keepOutsideCount() {
        return keepOutside.get();
    }

    static long passVehicleCount() {
        return passVehicle.get();
    }

    static long passConnectAreaCount() {
        return passConnectArea.get();
    }

    static long anomalyCount() {
        return anomalies.get();
    }

    static long stillSendCount() {
        return stillSend.get();
    }

    static long stillKeepCount() {
        return stillKeep.get();
    }

    static long stillResumeCount() {
        return stillResumes.get();
    }

    private VehicleRelevancyGate() {}
}
