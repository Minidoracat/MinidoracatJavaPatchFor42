package zombie.vehicleNetworkSound.server;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * W90 車輛聲音網路相關性預篩（2026-11-01；docs/patches.md 2cy）。
 *
 * <p><b>背景</b>：{@code Manager.update} 每幀對每條連線呼叫 {@code getVehiclesRelevantToConnection}，走完整個
 * {@code IsoCell.getVehicles()} 呼叫 {@code Connection.isRelevant}：連線數 × 車數。{@code isRelevant} 的半徑只看車本身
 * （警報、倒車蜂鳴、車門警報、引擎不是 Idle、喇叭、警笛；伺服器上 {@code isListenerInRange} 恆為 true），半徑 &gt; 0 之後才看
 * 連線的 {@code RelevantTo}。
 *
 * <p><b>改什麼</b>：{@code Manager.update} 唯一的 {@code GETFIELD UdpEngine.connections}（offset 3）換成 {@link #connections}：
 * 依 {@code getVehicles()} 的原迭代順序篩出 {@code vehicleId != -1} 而且半徑 &gt; 0 的車；{@code getVehiclesRelevantToConnection}
 * 唯一的 {@code IsoCell.getVehicles()}（offset 12）改道 {@link #getVehicles}，on 時回傳這份清單（原版迴圈的判斷照跑，
 * {@code tempVehicles} 的插入順序與原版相同）；{@code updateConnection} 唯一的 {@code Connection.getUdpConnection()}（offset 17）
 * 改道 {@link #getUdpConnection}，記下這一輪正在處理的連線供比對。{@code Manager.update} 期間車的狀態不變（只讀車、送封包），
 * 所以結果與原版逐連線判斷相同。
 *
 * <p><b>模式</b>（{@code -Dmdc.vehicleSoundPrefilter}，需重啟）：{@code 2|observe}（預設）每 16 輪兩邊都算、比對每條連線的結果
 * （內容與順序），回原版；{@code 1|on} 用篩好的清單，每 256 輪比對一輪；不一致或 helper 例外就本次啟動永久回原版；
 * {@code 0|off} 原版；未知值當 observe。心跳每 5 分鐘一行，停用後照印。
 *
 * <p>在這個 package 是為了呼叫 package-private 的 {@code Connection.isRelevant}／{@code getUdpConnection}。只在主執行緒使用。
 */
public final class MdcVehicleSoundPrefilter {

    static final int OFF = 0;
    static final int ON = 1;
    static final int OBSERVE = 2;
    static final String RAW = System.getProperty("mdc.vehicleSoundPrefilter");
    static final int MODE = parseMode(RAW);
    static final int OBSERVE_MASK = 15;
    static final int AUDIT_MASK = 255;

    private static final String TAG = "[MinidoracatJavaPatch][VehicleSoundPrefilter] ";
    private static final long HEARTBEAT_MS = 300_000L;

    private static final ArrayList<BaseVehicle> candidates = new ArrayList<>();
    private static final ArrayList<BaseVehicle> expected = new ArrayList<>();
    private static final ArrayList<BaseVehicle> got = new ArrayList<>();
    private static final Set<BaseVehicle> CANDIDATES = new AbstractSet<>() {
        @Override
        public Iterator<BaseVehicle> iterator() {
            return candidates.iterator();
        }

        @Override
        public int size() {
            return candidates.size();
        }
    };

    private static volatile boolean disabled;
    private static boolean started, prepared, sampled;
    private static Connection current;
    private static long lastBeat;
    private static long rounds, connSum, built, vehSum, candSum, compares, mismatches, vanillaNs, prefNs, anomalies;
    static String lastBeatLine;

    static int parseMode(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            case "1", "on" -> ON;
            default -> OBSERVE;
        };
    }

    /** {@code Manager.update} 開頭唯一的 {@code GameServer.udpEngine.connections}：一輪開始。 */
    public static List<UdpConnection> connections(UdpEngine engine) {
        List<UdpConnection> list = engine.connections;
        if (MODE != OFF) {
            try {
                beginRound(list.size());
            } catch (RuntimeException | LinkageError e) {
                anomalies++;
                disable("helper exception " + e);
            }
        }
        return list;
    }

    private static void beginRound(int conns) {
        prepared = false;
        current = null;
        rounds++;
        connSum += conns;
        long now = System.currentTimeMillis();
        if (!started) {
            started = true;
            lastBeat = now;
            log("首次生效 mode=" + modeName() + " raw=" + RAW
                    + "（-Dmdc.vehicleSoundPrefilter=0|off 原版、1|on 預篩、2|observe 每 16 輪比對；心跳每 5 分鐘一行）");
        }
        if (now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            beat();
        }
        if (disabled || conns == 0) {   // 沒有連線：getVehiclesRelevantToConnection 不會被呼叫，不建清單
            return;
        }
        sampled = (rounds & (MODE == ON ? AUDIT_MASK : OBSERVE_MASK)) == 0;
        if (MODE == OBSERVE && !sampled) {
            return;
        }
        candidates.clear();
        Set<BaseVehicle> all = IsoWorld.instance.currentCell.getVehicles();
        for (BaseVehicle v : all) {
            if (v.vehicleId != -1 && audible(v)) {
                candidates.add(v);
            }
        }
        built++;
        vehSum += all.size();
        candSum += candidates.size();
        prepared = true;
    }

    /**
     * {@code Connection.isRelevant} 的半徑 &gt; 0：六個條件的半徑都是正數，所以等於任一成立。照原版順序全部呼叫（不短路）。
     */
    static boolean audible(BaseVehicle v) {
        return v.isAlarmActive() | v.isBackupBeeperSounding() | v.isDoorAlarmSounding()
                | v.getEngineState() != BaseVehicle.engineStateTypes.Idle | v.isHornSounding() | v.isSirenSounding();
    }

    /** {@code updateConnection} 唯一的 {@code connection.getUdpConnection()}：記下這一輪正在處理的連線。 */
    public static UdpConnection getUdpConnection(Connection c) {
        if (MODE != OFF) {
            current = c;
        }
        return c.getUdpConnection();
    }

    /** {@code getVehiclesRelevantToConnection} 唯一的 {@code IsoCell.getVehicles()}。 */
    public static Set<BaseVehicle> getVehicles(IsoCell cell) {
        Set<BaseVehicle> all = cell.getVehicles();
        if (MODE == OFF || !prepared || disabled) {
            return all;
        }
        try {
            if (sampled) {
                compare(all);
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            disable("helper exception " + e);
        }
        return MODE == ON && !disabled ? CANDIDATES : all;
    }

    /** 這條連線原版會放進 tempVehicles 的車（依序）與預篩後會放進的車必須逐一相同。 */
    private static void compare(Set<BaseVehicle> all) {
        Connection c = current;
        if (c == null) {
            anomalies++;
            return;
        }
        long t0 = System.nanoTime();
        expected.clear();
        for (BaseVehicle v : all) {
            if (v.vehicleId != -1 && c.isRelevant(v)) {
                expected.add(v);
            }
        }
        long t1 = System.nanoTime();
        got.clear();
        for (BaseVehicle v : candidates) {
            if (v.vehicleId != -1 && c.isRelevant(v)) {
                got.add(v);
            }
        }
        long t2 = System.nanoTime();
        compares++;
        vanillaNs += t1 - t0;
        prefNs += t2 - t1;
        int n = Math.max(expected.size(), got.size());
        for (int i = 0; i < n; i++) {
            BaseVehicle e = i < expected.size() ? expected.get(i) : null;
            BaseVehicle g = i < got.size() ? got.get(i) : null;
            if (e != g) {
                mismatches++;
                disable("mismatch at " + i + " expected=" + (e == null ? "-" : e.vehicleId) + " got="
                        + (g == null ? "-" : g.vehicleId) + " expectedN=" + expected.size() + " gotN=" + got.size());
                break;
            }
        }
        expected.clear();
        got.clear();
    }

    private static void disable(String reason) {
        if (disabled) {
            return;
        }
        disabled = true;
        log("DISABLED（本次啟動永久回原版）reason=" + reason + " rounds=" + rounds + " compares=" + compares);
    }

    private static void beat() {
        String line = "beat mode=" + modeName() + " rounds=" + rounds + " connAvg=" + avg(connSum, rounds)
                + " built=" + built + " vehAvg=" + avg(vehSum, built) + " candAvg=" + avg(candSum, built)
                + " compares=" + compares + " mismatches=" + mismatches
                + " vanillaUsAvg=" + avg(vanillaNs / 1000, compares) + " prefUsAvg=" + avg(prefNs / 1000, compares)
                + " anomalies=" + anomalies + " disabled=" + disabled;
        lastBeatLine = line;
        log(line);
        rounds = connSum = built = vehSum = candSum = compares = vanillaNs = prefNs = 0;
    }

    private static String avg(long sum, long n) {
        return n == 0 ? "0" : String.valueOf(Math.round(sum * 10.0 / n) / 10.0);
    }

    static String modeName() {
        return MODE == OFF ? "off" : MODE == ON ? "on" : "observe";
    }

    private static void log(String s) {
        DebugLog.log(TAG + s);
    }

    static void forceBeatForTest() {
        lastBeat = System.currentTimeMillis() - HEARTBEAT_MS;
    }

    static boolean disabledForTest() { return disabled; }
    static long comparesForTest() { return compares; }
    static long mismatchesForTest() { return mismatches; }
    static long anomaliesForTest() { return anomalies; }
    static int candidatesForTest() { return candidates.size(); }
    static long builtForTest() { return built; }

    private MdcVehicleSoundPrefilter() {}
}
