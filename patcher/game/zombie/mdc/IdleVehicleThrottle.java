package zombie.mdc;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

import zombie.MovingObjectUpdateScheduler;
import zombie.MovingObjectUpdateSchedulerUpdateBucket;
import zombie.core.physics.CarController;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleAlarm;

/**
 * W89 閒置車降頻（2026-11-01；docs/patches.md 2cx）。
 *
 * <p><b>背景</b>：伺服器上 {@code MovingObjectUpdateScheduler.getUpdateSchedulerSimulationLevelForObject} 一律回 FULL，
 * 每輛載入的車每幀都跑 preupdate／frameStep／update／postupdate。2026-11-01 晚峰約 2,090 輛車，{@code BaseVehicle.update}
 * 占主執行緒約 20%，大多是沒人、熄火、沒在動的車，每幀重算同樣的格子與作物查詢。
 *
 * <p><b>改什麼</b>：{@code startFrame()} 唯一的 {@code MovingObjectUpdateSchedulerUpdateBucket.add}（javap offset 132）同形改道到
 * {@link #add}。伺服器主執行緒上、{@code vehicleId >= 0} 而且 {@link #idle} 成立的車，只在
 * {@code ((frameCounter + vehicleId) & (N-1)) == 0} 的幀放進 bucket，其他幀整台跳過四段（同一個 bucket，伺服器 frameMod＝1）。
 *
 * <p><b>模式</b>（{@code -Dmdc.idleVehicleThrottle}，需重啟）：{@code 2|observe}（預設）一律放行，對「會跳」的車記這一幀與下一幀的指紋；
 * {@code 1|on} 跳過，每 4096 次跳過抽一次放行並比對下一幀，{@code pos} 不一致就本次啟動永久回原版；{@code 0|off} 直接 {@code add}；
 * 未知值當 observe。{@code -Dmdc.idleVehicleThrottleN}：2 的次方 1–64，預設 8。helper 例外照原版 {@code add} 並停用。
 *
 * <p><b>指紋</b>：{@code pos}＝x、y、z、{@code jniTransform.origin}、{@code current}／{@code chunk} 的身分；{@code state}＝閒置條件用到的
 * 欄位（見 {@link #STATE_NAMES}）加上是否還在 {@code cell.vehicles}；{@code parts}＝每 64 次抽一次電瓶電量。下一幀 state 變了就算
 * {@code stateDiff}（下一幀判斷不成立、最多延後 1 幀），state 沒變而 pos 變了才算 {@code posDiff}（on 會延後最多 N−1 幀的位置同步）。
 *
 * <p>只在主執行緒（{@code startFrame} 所在）讀寫，計數欄位不加鎖。
 */
public final class IdleVehicleThrottle {

    static final int OFF = 0;
    static final int ON = 1;
    static final int OBSERVE = 2;
    static final String RAW = System.getProperty("mdc.idleVehicleThrottle");
    static final int MODE = parseMode(RAW);
    static final String RAW_N = System.getProperty("mdc.idleVehicleThrottleN");
    static final int N = parseN(RAW_N);
    private static final int MASK = N - 1;

    private static final String TAG = "[MinidoracatJavaPatch][IdleVehicleThrottle] ";
    private static final long HEARTBEAT_MS = 300_000L;
    static final int AUDIT_MASK = 4095;
    private static final int PRED_SAMPLE_MASK = 255;
    private static final int PARTS_SAMPLE_MASK = 63;
    private static final int TOP = 5;

    /** state 指紋各位元的名字（位元 0–3 引擎狀態序號、4–6 授權序號，其餘一位一個）。 */
    static final String[] STATE_NAMES = {"engine", "engine", "engine", "engine", "auth", "auth", "auth", "isEnable",
            "physicActiveCheck", "constraintChangedTime", "alarm", "needPartsUpdate", "lightbar", "siren", "inCell",
            "mechanicUI", "jniIsCollide", "passenger", "tow", "removed", "animals", "noSquare"};

    private static volatile boolean disabled;
    private static boolean started;
    private static long curFrame = Long.MIN_VALUE;
    private static long lastBeat;
    private static long frames, visits, idles, actives, wouldSkip, skipped, skipSeen, audits;
    private static long compared, posDiff, stateDiff, partsDiff, predNs, predN, anomalies, fallbacks, records;
    private static final long[] STATE_BITS = new long[STATE_NAMES.length];
    private static final StringBuilder posTop = new StringBuilder();
    private static int posTopN;
    private static Field towingIdField;
    static String lastBeatLine;

    // 指紋，以 vehicleId 為索引；fpFrame＝記錄的幀（沒有記錄是 Long.MIN_VALUE）。
    private static long[] fpFrame = new long[0];
    private static int[] fpIdent = new int[0];
    private static float[] fpPos = new float[0];
    private static int[] fpRef = new int[0];
    private static int[] fpState = new int[0];
    private static float[] fpBattery = new float[0];
    private static boolean[] fpAudit = new boolean[0];

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

    static int parseN(String raw) {
        if (raw == null) {
            return 8;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n >= 1 && n <= 64 && Integer.bitCount(n) == 1 ? n : 8;
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /** {@code MovingObjectUpdateScheduler.startFrame()} 唯一的 {@code bucket.add(obj)}。 */
    public static void add(MovingObjectUpdateSchedulerUpdateBucket bucket, IsoMovingObject o) {
        if (MODE == OFF || !(o instanceof BaseVehicle v) || !GameServer.server || Thread.currentThread() != GameServer.mainThread) {
            bucket.add(o);
            return;
        }
        boolean skip = false;
        try {
            skip = decide(v);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            disable("helper exception " + e, v);
        }
        if (!skip) {
            bucket.add(o);
        }
    }

    private static boolean decide(BaseVehicle v) {
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        if (frame != curFrame) {
            newFrame(frame);
        }
        if (disabled) {
            return false;
        }
        visits++;
        int vid = v.vehicleId;
        if (vid < 0) {
            return false;
        }
        if (vid < fpFrame.length && fpFrame[vid] != Long.MIN_VALUE) {
            if (fpFrame[vid] == frame - 1 && fpIdent[vid] == System.identityHashCode(v)) {
                compare(v, vid);
                if (disabled) {
                    return false;
                }
            }
            fpFrame[vid] = Long.MIN_VALUE;
        }
        boolean idle;
        if ((visits & PRED_SAMPLE_MASK) == 0) {
            long t0 = System.nanoTime();
            idle = idle(v);
            predNs += System.nanoTime() - t0;
            predN++;
        } else {
            idle = idle(v);
        }
        if (!idle) {
            actives++;
            return false;
        }
        idles++;
        if (((frame + vid) & MASK) == 0) {
            return false;
        }
        wouldSkip++;
        if (MODE == OBSERVE) {
            record(v, vid, frame, false);
            return false;
        }
        if ((++skipSeen & AUDIT_MASK) == 0) {
            audits++;
            record(v, vid, frame, true);
            return false;
        }
        skipped++;
        return true;
    }

    /**
     * 閒置條件：全部是 public 欄位或純讀取的 getter（SmokeCheck 鎖本體），任一不成立就照原版每幀跑。
     * 沒有物理活動、沒有客戶端授權、沒人、沒動物、沒拖、熄火、沒警報、燈條與警笛關、不需要零件更新、沒開機修介面、沒碰撞、在世界裡。
     */
    static boolean idle(BaseVehicle v) {
        CarController c = v.getController();
        if (c == null || c.isEnable || v.physicActiveCheck != -1L || v.constraintChangedTime != -1L
                || v.netPlayerAuthorization != BaseVehicle.Authorization.Server) {
            return false;
        }
        for (int i = 0, n = v.getMaxPassengers(); i < n; i++) {
            BaseVehicle.Passenger p = v.getPassenger(i);
            if (p != null && p.character != null) {
                return false;
            }
        }
        if (!v.getAnimals().isEmpty() || v.getVehicleTowing() != null || v.getVehicleTowedBy() != null
                || v.getEngineState() != BaseVehicle.engineStateTypes.Idle) {
            return false;
        }
        VehicleAlarm alarm = v.getVehicleAlarmObject();
        if (alarm != null && alarm.isActive()) {
            return false;
        }
        return !v.lightbarLightsMode.isEnable() && !v.lightbarSirenMode.isEnable() && !v.needPartsUpdate()
                && !v.isMechanicUIOpen() && !v.jniIsCollide && v.getCurrentSquare() != null && v.chunk != null
                && !v.isRemovedFromWorld();
    }

    static int stateBits(BaseVehicle v) {
        int bits = v.getEngineState().ordinal() & 15;
        bits |= (v.netPlayerAuthorization == null ? 7 : v.netPlayerAuthorization.ordinal() & 7) << 4;
        CarController c = v.getController();
        bits |= (c != null && c.isEnable ? 1 : 0) << 7;
        bits |= (v.physicActiveCheck != -1L ? 1 : 0) << 8;
        bits |= (v.constraintChangedTime != -1L ? 1 : 0) << 9;
        VehicleAlarm alarm = v.getVehicleAlarmObject();
        bits |= (alarm != null && alarm.isActive() ? 1 : 0) << 10;
        bits |= (v.needPartsUpdate() ? 1 : 0) << 11;
        bits |= (v.lightbarLightsMode.isEnable() ? 1 : 0) << 12;
        bits |= (v.lightbarSirenMode.isEnable() ? 1 : 0) << 13;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        bits |= (cell != null && cell.vehicles.contains(v) ? 1 : 0) << 14;
        bits |= (v.isMechanicUIOpen() ? 1 : 0) << 15;
        bits |= (v.jniIsCollide ? 1 : 0) << 16;
        boolean passenger = false;
        for (int i = 0, n = v.getMaxPassengers(); i < n && !passenger; i++) {
            BaseVehicle.Passenger p = v.getPassenger(i);
            passenger = p != null && p.character != null;
        }
        bits |= (passenger ? 1 : 0) << 17;
        bits |= (v.getVehicleTowing() != null || v.getVehicleTowedBy() != null ? 1 : 0) << 18;
        bits |= (v.isRemovedFromWorld() ? 1 : 0) << 19;
        bits |= (v.getAnimals().isEmpty() ? 0 : 1) << 20;
        bits |= (v.getCurrentSquare() == null || v.chunk == null ? 1 : 0) << 21;
        return bits;
    }

    private static void record(BaseVehicle v, int vid, long frame, boolean audit) {
        grow(vid);
        records++;
        fpFrame[vid] = frame;
        fpIdent[vid] = System.identityHashCode(v);
        int p = vid * 6;
        fpPos[p] = v.getX();
        fpPos[p + 1] = v.getY();
        fpPos[p + 2] = v.getZ();
        fpPos[p + 3] = v.jniTransform.origin.x;
        fpPos[p + 4] = v.jniTransform.origin.y;
        fpPos[p + 5] = v.jniTransform.origin.z;
        fpRef[vid * 2] = System.identityHashCode(v.getCurrentSquare());
        fpRef[vid * 2 + 1] = System.identityHashCode(v.chunk);
        fpState[vid] = stateBits(v);
        fpBattery[vid] = (records & PARTS_SAMPLE_MASK) == 0 ? v.getBatteryCharge() : Float.NaN;
        fpAudit[vid] = audit;
    }

    private static final String[] POS_NAMES = {"x", "y", "z", "originX", "originY", "originZ"};

    private static void compare(BaseVehicle v, int vid) {
        compared++;
        int state = stateBits(v);
        int changed = state ^ fpState[vid];
        if (changed != 0) {
            stateDiff++;
            for (int b = 0; b < STATE_NAMES.length; b++) {
                if ((changed & (1 << b)) != 0) {
                    STATE_BITS[b]++;
                }
            }
        } else {
            String field = posField(v, vid);
            if (field != null) {
                posDiff++;
                if (posTopN < TOP) {
                    posTopN++;
                    posTop.append(posTop.isEmpty() ? "" : ";").append(vid).append(':').append(v.getScriptName()).append('@')
                            .append(v.getX()).append(',').append(v.getY()).append(',').append(v.getZ()).append('(').append(field)
                            .append(')');
                }
                if (fpAudit[vid]) {
                    disable("audit pos mismatch vid=" + vid + " field=" + field, v);
                    return;
                }
            }
        }
        float before = fpBattery[vid];
        if (!Float.isNaN(before) && Float.floatToIntBits(before) != Float.floatToIntBits(v.getBatteryCharge())) {
            partsDiff++;
        }
    }

    /** 第一個不同的 pos 欄位名，相同回 null。 */
    private static String posField(BaseVehicle v, int vid) {
        int p = vid * 6;
        for (int i = 0; i < 6; i++) {
            float now = switch (i) {
                case 0 -> v.getX();
                case 1 -> v.getY();
                case 2 -> v.getZ();
                case 3 -> v.jniTransform.origin.x;
                case 4 -> v.jniTransform.origin.y;
                default -> v.jniTransform.origin.z;
            };
            if (Float.floatToIntBits(now) != Float.floatToIntBits(fpPos[p + i])) {
                return POS_NAMES[i];
            }
        }
        if (System.identityHashCode(v.getCurrentSquare()) != fpRef[vid * 2]) {
            return "current";
        }
        return System.identityHashCode(v.chunk) != fpRef[vid * 2 + 1] ? "chunk" : null;
    }

    private static void grow(int vid) {
        if (vid < fpFrame.length) {
            return;
        }
        int n = Math.max(256, Integer.highestOneBit(vid) << 1);
        int old = fpFrame.length;
        fpFrame = Arrays.copyOf(fpFrame, n);
        Arrays.fill(fpFrame, old, n, Long.MIN_VALUE);
        fpIdent = Arrays.copyOf(fpIdent, n);
        fpPos = Arrays.copyOf(fpPos, n * 6);
        fpRef = Arrays.copyOf(fpRef, n * 2);
        fpState = Arrays.copyOf(fpState, n);
        fpBattery = Arrays.copyOf(fpBattery, n);
        fpAudit = Arrays.copyOf(fpAudit, n);
    }

    private static void newFrame(long frame) {
        curFrame = frame;
        frames++;
        long now = System.currentTimeMillis();
        if (!started) {
            started = true;
            lastBeat = now;
            log("首次生效 mode=" + modeName() + " raw=" + RAW + " N=" + N + " rawN=" + RAW_N
                    + "（-Dmdc.idleVehicleThrottle=0|off 原版、1|on 閒置車每 N 幀更新一次、2|observe 只量；心跳每 5 分鐘一行）");
        }
        if (now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            try {
                beat();
            } catch (RuntimeException | LinkageError e) {
                anomalies++;
            }
        }
    }

    private static void beat() {
        int vehicles = -1;
        int orphanTow = -1;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell != null) {
            Set<BaseVehicle> set = cell.getVehicles();
            vehicles = set.size();
            orphanTow = orphanTow(set);
        }
        StringBuilder bits = new StringBuilder();
        for (int b = 0; b < STATE_NAMES.length; b++) {
            if (STATE_BITS[b] > 0 && (b == 0 || !STATE_NAMES[b].equals(STATE_NAMES[b - 1]))) {
                long n = 0;
                for (int k = b; k < STATE_NAMES.length && STATE_NAMES[k].equals(STATE_NAMES[b]); k++) {
                    n += STATE_BITS[k];
                }
                bits.append(bits.isEmpty() ? "" : ",").append(STATE_NAMES[b]).append(':').append(n);
            }
        }
        String line = "beat mode=" + modeName() + " N=" + N + " vehicles=" + vehicles + " frames=" + frames + " visits=" + visits
                + " idle=" + idles + " active=" + actives + " wouldSkip=" + wouldSkip
                + " skipPct=" + (visits == 0 ? 0 : Math.round(wouldSkip * 1000.0 / visits) / 10.0)
                + " skipped=" + skipped + " audits=" + audits + " compared=" + compared
                + " posDiff=" + posDiff + " stateDiff=" + stateDiff + " partsDiff=" + partsDiff
                + " stateFields=" + (bits.isEmpty() ? "-" : bits)
                + " predUsAvg=" + (predN == 0 ? 0 : Math.round(predNs / (double) predN / 10.0) / 100.0)
                + " orphanTow=" + orphanTow + " fallback=" + fallbacks + " anomalies=" + anomalies + " disabled=" + disabled
                + " posTop=" + (posTop.isEmpty() ? "-" : posTop);
        lastBeatLine = line;
        log(line);
        frames = visits = idles = actives = wouldSkip = skipped = audits = compared = 0;
        posDiff = stateDiff = partsDiff = predNs = predN = 0;
        Arrays.fill(STATE_BITS, 0);
        posTop.setLength(0);
        posTopN = 0;
    }

    /** {@code vehicleTowingId != -1} 但拖車物件是 null 的車（每幀 O(全部車) 找拖車對象，見 BaseVehicle.tryReconnectToTowedVehicle）。 */
    static int orphanTow(Set<BaseVehicle> set) {
        try {
            if (towingIdField == null) {
                Field f = BaseVehicle.class.getDeclaredField("vehicleTowingId");
                f.setAccessible(true);
                towingIdField = f;
            }
            int n = 0;
            for (BaseVehicle v : set) {
                if (towingIdField.getInt(v) != -1 && v.getVehicleTowing() == null) {
                    n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException | RuntimeException e) {
            anomalies++;
            return -1;
        }
    }

    private static void disable(String reason, BaseVehicle v) {
        if (disabled) {
            return;
        }
        disabled = true;
        fallbacks++;
        String where = "?";
        try {
            where = v.vehicleId + ":" + v.getScriptName() + "@" + v.getX() + "," + v.getY() + "," + v.getZ();
        } catch (RuntimeException e) {
            anomalies++;
        }
        log("DISABLED（本次啟動永久回原版）reason=" + reason + " at " + where + " skipped=" + skipped + " audits=" + audits);
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
    static long wouldSkipForTest() { return wouldSkip; }
    static long skippedForTest() { return skipped; }
    static long auditsForTest() { return audits; }
    static long posDiffForTest() { return posDiff; }
    static long stateDiffForTest() { return stateDiff; }
    static long comparedForTest() { return compared; }
    static long anomaliesForTest() { return anomalies; }

    private IdleVehicleThrottle() {}
}
