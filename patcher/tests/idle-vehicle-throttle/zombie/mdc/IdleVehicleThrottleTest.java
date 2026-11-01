package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zombie.MovingObjectUpdateScheduler;
import zombie.MovingObjectUpdateSchedulerUpdateBucket;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.core.physics.CarController;
import zombie.core.physics.Transform;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.radio.devices.DeviceData;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.LightbarLightsMode;
import zombie.vehicles.LightbarSirenMode;
import zombie.vehicles.VehicleAlarm;
import zombie.vehicles.VehicleEngine;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleParts;

/**
 * W89：跑真的 {@code MovingObjectUpdateScheduler.startFrame()}（dist 手術後或 jar 原版），看每一幀 FULL bucket 裡有哪些車。
 *
 * <p>用法：{@code IdleVehicleThrottleTest vanilla|off|observe|on|on-wake|on-throw [N]}。vanilla 的 classpath 不含 dist（jar 原版負對照）；
 * 其他模式的開關要和參數一致（測試自驗）。車、IsoWorld、IsoCell、格子與 chunk 以 Unsafe 配置後補欄位，閒置條件用到的欄位逐一填好。
 */
public final class IdleVehicleThrottleTest {

    static final int IDLE = 64;
    static final int ACTIVE = 4;
    private static int failed;
    static IsoCell cell;
    static IsoGridSquare square;
    static IsoChunk chunk;
    static final Set<IsoMovingObject> objects = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args[0];
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        GameServer.server = true;
        GameServer.mainThread = Thread.currentThread();
        world();
        List<BaseVehicle> idle = new ArrayList<>();
        List<BaseVehicle> active = new ArrayList<>();
        for (int i = 0; i < IDLE; i++) {
            idle.add(vehicle((short) i));
        }
        for (int i = 0; i < ACTIVE; i++) {
            BaseVehicle v = vehicle((short) (IDLE + i));
            v.getController().isEnable = true;   // 物理在動
            active.add(v);
        }
        setInt(BaseVehicle.class, idle.get(3), "vehicleTowingId", 77);   // 找不到拖車對象的車（orphanTow）
        if (mode.equals("vanilla")) {
            String src = MovingObjectUpdateScheduler.class.getProtectionDomain().getCodeSource().getLocation().toString();
            check("vanilla：MovingObjectUpdateScheduler 來自 jar（classpath 不含 dist）", src.endsWith("projectzomboid.jar"));
            Map<BaseVehicle, Integer> counts = run(4 * n);
            check("原版負對照：" + 4 * n + " 幀內每台車（閒置 " + IDLE + "、活動 " + ACTIVE + "）都被 add " + 4 * n + " 次",
                    all(counts, idle, 4 * n) && all(counts, active, 4 * n));
            finish(mode);
            return;
        }
        int want = mode.equals("off") ? IdleVehicleThrottle.OFF : mode.startsWith("on") ? IdleVehicleThrottle.ON : IdleVehicleThrottle.OBSERVE;
        check("模式與參數一致（mode=" + IdleVehicleThrottle.modeName() + " N=" + IdleVehicleThrottle.N + "）",
                IdleVehicleThrottle.MODE == want && IdleVehicleThrottle.N == n);
        truthTable();
        warm();
        switch (mode) {
            case "off" -> {
                Map<BaseVehicle, Integer> counts = run(4 * n);
                check("off：每台車每幀都 add、不計數", all(counts, idle, 4 * n) && all(counts, active, 4 * n)
                        && IdleVehicleThrottle.wouldSkipForTest() == 0);
            }
            case "observe" -> observe(idle, active, n);
            case "on" -> on(idle, active, n);
            case "on-wake" -> onWake(idle, n);
            case "on-throw" -> onThrow(idle, active, n);
            default -> throw new IllegalArgumentException(mode);
        }
        finish(mode);
    }

    static void truthTable() throws Exception {
        BaseVehicle v = vehicle((short) 900);
        objects.remove(v);
        cell.vehicles.remove(v);
        check("真值表：全部條件成立＝閒置", idleNow(v));
        Map<String, Flip> flips = new java.util.LinkedHashMap<>();
        flips.put("controller==null", b -> set(BaseVehicle.class, v, "physics", b ? null : alloc(CarController.class)));
        flips.put("isEnable", b -> v.getController().isEnable = b);
        flips.put("physicActiveCheck!=-1", b -> v.physicActiveCheck = b ? 123L : -1L);
        flips.put("constraintChangedTime!=-1", b -> v.constraintChangedTime = b ? 456L : -1L);
        flips.put("授權不是 Server", b -> v.netPlayerAuthorization = b ? BaseVehicle.Authorization.Local : BaseVehicle.Authorization.Server);
        flips.put("第二個座位有人", b -> v.getPassenger(1).character = b ? alloc(zombie.characters.IsoPlayer.class) : null);
        flips.put("拖車裡有動物", b -> {
            if (b) {
                v.getAnimals().add(alloc(zombie.characters.animals.IsoAnimal.class));
            } else {
                v.getAnimals().clear();
            }
        });
        flips.put("在拖別人", b -> set(BaseVehicle.class, v, "vehicleTowing", b ? alloc(BaseVehicle.class) : null));
        flips.put("被拖", b -> set(BaseVehicle.class, v, "vehicleTowedBy", b ? alloc(BaseVehicle.class) : null));
        flips.put("引擎不是 Idle", b -> set(VehicleParts.class, get(BaseVehicle.class, v, "parts"), "engine", b ? runningEngine() : null));
        flips.put("警報在響", b -> setDouble(VehicleAlarm.class, v.getVehicleAlarmObject(), "startTime", b ? 1.0 : 0.0));
        flips.put("燈條開", b -> v.lightbarLightsMode.set(b ? 1 : 0));
        flips.put("警笛開", b -> v.lightbarSirenMode.set(b ? 1 : 0));
        flips.put("needPartsUpdate", b -> setBool(BaseVehicle.class, v, "needPartsUpdate", b));
        flips.put("機修介面開著", b -> setBool(BaseVehicle.class, v, "mechanicUiOpen", b));
        flips.put("jniIsCollide", b -> v.jniIsCollide = b);
        flips.put("沒有格子", b -> set(IsoMovingObject.class, v, "current", b ? null : square));
        flips.put("沒有 chunk", b -> v.chunk = b ? null : chunk);
        flips.put("已離開世界", b -> setBool(BaseVehicle.class, v, "removedFromWorld", b));
        VehiclePart radio = radio(v);
        flips.put("收音機開著", b -> setBool(DeviceData.class, radio.getDeviceData(), "isTurnedOn", b));
        for (Map.Entry<String, Flip> e : flips.entrySet()) {
            e.getValue().apply(true);
            boolean off = !idleNow(v);
            e.getValue().apply(false);
            check("真值表：" + e.getKey() + " → 不閒置；翻回來又閒置", off && idleNow(v));
        }
    }

    /** 閒置判斷（每幀重算的條件）加上裝置（helper 以快取每 N 幀重掃）。 */
    static boolean idleNow(BaseVehicle v) {
        return IdleVehicleThrottle.idle(v) && !IdleVehicleThrottle.devicesOn(v);
    }

    /** 車上加一個關著的收音機零件（DeviceData.isTurnedOn＝false）。 */
    @SuppressWarnings("unchecked")
    static VehiclePart radio(BaseVehicle v) throws Exception {
        VehiclePart part = alloc(VehiclePart.class);
        set(VehiclePart.class, part, "deviceData", alloc(DeviceData.class));
        ((List<VehiclePart>) get(VehicleParts.class, get(BaseVehicle.class, v, "parts"), "parts")).add(part);
        return part;
    }

    /** 全部車先跑一幀（開始暖機），再把牆鐘推進 5 秒：之後的幀暖機都滿了。 */
    static void warm() throws Exception {
        run(1);
        IdleVehicleThrottle.advanceClockForTest(IdleVehicleThrottle.WARMUP_MS);
    }

    static void observe(List<BaseVehicle> idle, List<BaseVehicle> active, int n) throws Exception {
        long r0 = IdleVehicleThrottle.recordsForTest();
        Map<BaseVehicle, Integer> counts = run(16 * n);
        check("observe：每台車每幀都 add", all(counts, idle, 16 * n) && all(counts, active, 16 * n));
        check("observe：會跳的次數＝每台閒置車每 N 幀 N−1 次（" + IDLE * 16 * (n - 1) + "）",
                IdleVehicleThrottle.wouldSkipForTest() == (long) IDLE * 16 * (n - 1) && IdleVehicleThrottle.skippedForTest() == 0);
        long recs = IdleVehicleThrottle.recordsForTest() - r0;
        check("observe 抽樣：16 個輪次只記 1 個輪次（記錄 " + recs + " 次＝會跳次數的 1/16＝" + IDLE * (n - 1) + "）",
                recs == (long) IDLE * (n - 1));
        check("observe：沒動過的車指紋都相同", IdleVehicleThrottle.posDiffForTest() == 0 && IdleVehicleThrottle.stateDiffForTest() == 0
                && IdleVehicleThrottle.comparedForTest() > 0);
        // 找下一幀「會跳」而且抽到記錄的兩台：一台只改位置（posDiff），一台改位置又改狀態（算 stateDiff）
        BaseVehicle moved = null;
        BaseVehicle woke = null;
        for (int f = 0; f < 16 * n && woke == null; f++) {
            long next = frame() + 1;
            moved = firstWouldSkip(idle, next, n, null);
            woke = moved == null ? null : firstWouldSkip(idle, next, n, moved);
            if (woke == null) {
                run(1);
            }
        }
        run(1);
        moved.setX(moved.getX() + 0.5F);
        woke.setX(woke.getX() + 0.5F);
        setBool(BaseVehicle.class, woke, "needPartsUpdate", true);
        run(1);
        check("observe：只有位置變了 → posDiff 1；位置與狀態都變了 → 算 stateDiff 1、不算 posDiff；不停用",
                IdleVehicleThrottle.posDiffForTest() == 1 && IdleVehicleThrottle.stateDiffForTest() == 1 && !IdleVehicleThrottle.disabledForTest());
        IdleVehicleThrottle.forceBeatForTest();
        run(1);
        String line = IdleVehicleThrottle.lastBeatLine;
        check("observe 心跳：mode／N／vehicles／orphanTow／posDiff／stateFields／posTop／disabled=false（" + line + "）",
                line != null && line.contains("mode=observe") && line.contains(" N=" + n) && line.contains(" vehicles=" + (IDLE + ACTIVE))
                        && line.contains(" orphanTow=1") && line.contains(" posDiff=1") && line.contains("needPartsUpdate:1")
                        && line.contains("posTop=" + moved.vehicleId + ":") && line.contains("(x)") && line.contains(" disabled=false")
                        && line.contains(" warmup=") && line.contains(" devOn=0") && line.contains(" obsUsAvg="));
    }

    static void on(List<BaseVehicle> idle, List<BaseVehicle> active, int n) throws Exception {
        int windows = 64 / n;   // 64 幀內還沒抽到稽核（每幀跳過 IDLE×(N−1)/N 次，4096 次才抽一次）
        Map<BaseVehicle, Integer> counts = run(windows * n);
        check("on：" + windows * n + " 幀內每台閒置車恰好 add " + windows + " 次（每 N 幀 1 次）、活動車每幀都 add",
                all(counts, idle, windows) && all(counts, active, windows * n) && IdleVehicleThrottle.auditsForTest() == 0);
        // 跑到第一次稽核：那一幀被放行的閒置車不是自己的輪次；同時改位置與狀態 → 只算 stateDiff、不停用
        BaseVehicle audited = runUntilAudit(idle, n, 1);
        audited.setX(audited.getX() + 0.5F);
        audited.getController().isEnable = true;
        run(1);
        check("on 稽核：放行的車下一幀位置與狀態都變了 → stateDiff、不停用", audited != null && !IdleVehicleThrottle.disabledForTest()
                && IdleVehicleThrottle.stateDiffForTest() >= 1 && IdleVehicleThrottle.posDiffForTest() == 0);
        audited.getController().isEnable = false;
        run(1);   // 它重新開始暖機；推進牆鐘讓暖機滿，下一次稽核找到的才是被抽到的車
        IdleVehicleThrottle.advanceClockForTest(IdleVehicleThrottle.WARMUP_MS);
        BaseVehicle second = runUntilAudit(idle, n, 2);
        second.setX(second.getX() + 0.5F);
        run(1);
        check("on 稽核：放行的車下一幀狀態沒變、位置變了 → 本次啟動永久停用", IdleVehicleThrottle.disabledForTest());
        Map<BaseVehicle, Integer> later = run(2 * n);
        check("停用後照原版：每台車每幀都 add", all(later, idle, 2 * n) && all(later, active, 2 * n));
        IdleVehicleThrottle.forceBeatForTest();
        run(1);
        String line = IdleVehicleThrottle.lastBeatLine;
        check("停用後心跳照印 disabled=true、fallback=1（" + line + "）",
                line != null && line.contains("mode=on") && line.contains(" disabled=true") && line.contains(" fallback=1"));
    }

    static void onThrow(List<BaseVehicle> idle, List<BaseVehicle> active, int n) throws Exception {
        BaseVehicle broken = idle.get(5);
        set(BaseVehicle.class, broken, "passengers", null);   // getMaxPassengers 丟 NPE
        Map<BaseVehicle, Integer> counts = run(n);
        check("on：helper 例外 → 照原版 add 並停用，之後每台車每幀都 add",
                IdleVehicleThrottle.disabledForTest() && IdleVehicleThrottle.anomaliesForTest() == 1 && counts.get(broken) == n
                        && all(counts, active, n) && counts.get(idle.get(IDLE - 1)) >= n - 1);
    }

    /** 跑到第 k 次稽核那一幀為止，回傳那一幀被放行、不是自己輪次的閒置車。 */
    static BaseVehicle runUntilAudit(List<BaseVehicle> idle, int n, int k) throws Exception {
        for (int f = 0; f < 1000; f++) {
            Map<BaseVehicle, Integer> counts = run(1);
            if (IdleVehicleThrottle.auditsForTest() >= k) {
                long fr = frame();
                for (BaseVehicle v : idle) {
                    if (counts.get(v) == 1 && ((fr + v.vehicleId) & (n - 1)) != 0) {
                        return v;
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** 那一幀會跳、而且 observe 抽到記錄（每 16 個輪次一個輪次）的閒置車；沒有回 null。 */
    static BaseVehicle firstWouldSkip(List<BaseVehicle> idle, long frame, int n, BaseVehicle not) {
        int shift = Integer.numberOfTrailingZeros(n);
        for (BaseVehicle v : idle) {
            if (v != not && ((frame + v.vehicleId) & (n - 1)) != 0 && (((frame + v.vehicleId) >> shift) & 15) == 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * on：收音機與暖機。只留三台閒置車（其他改成活動車，稽核抽不到），x 測收音機、y 測暖機、z 是對照。
     */
    static void onWake(List<BaseVehicle> idle, int n) throws Exception {
        BaseVehicle x = idle.get(10);
        BaseVehicle y = idle.get(11);
        BaseVehicle z = idle.get(12);
        for (BaseVehicle v : idle) {
            if (v != x && v != y && v != z) {
                v.getController().isEnable = true;
            }
        }
        check("暖機滿了：三台閒置車每 N 幀 1 次", count(runSeq(4 * n, x)) == 4 && count(runSeq(4 * n, y)) == 4 && count(runSeq(4 * n, z)) == 4);
        // 收音機：閒置中打開 → 最多 N−1 幀內改成每幀更新
        VehiclePart radio = radio(x);
        setBool(DeviceData.class, radio.getDeviceData(), "isTurnedOn", true);
        boolean[] seq = runSeq(3 * n, x);
        int first = firstTrue(seq);
        check("收音機打開：第 " + first + " 幀（≤ N−1）發現，之後每幀都 add", first >= 0 && first <= n - 1 && allTrue(seq, first));
        // 關掉 → 重新暖機：牆鐘不動時每幀都 add，滿 5 秒後回到每 N 幀 1 次
        setBool(DeviceData.class, radio.getDeviceData(), "isTurnedOn", false);
        seq = runSeq(3 * n, x);
        check("收音機關掉：暖機中每幀都 add（" + count(seq) + "/" + 3 * n + "）", allTrue(seq, 0));
        IdleVehicleThrottle.advanceClockForTest(IdleVehicleThrottle.WARMUP_MS);
        check("收音機關掉滿 5 秒：回到每 N 幀 1 次", count(runSeq(4 * n, x)) == 4);
        // 暖機：不閒置一幀 → 未滿 5 秒每幀都 add；中途再不閒置一幀 → 從那一幀重算
        y.getController().isEnable = true;
        run(1);
        y.getController().isEnable = false;
        seq = runSeq(2 * n, y);
        check("暖機：不閒置一幀之後、牆鐘不動時每幀都 add", allTrue(seq, 0));
        IdleVehicleThrottle.advanceClockForTest(3_000L);
        check("暖機：3 秒時仍每幀都 add", allTrue(runSeq(n, y), 0));
        y.getController().isEnable = true;
        run(1);
        y.getController().isEnable = false;
        run(1);   // 重新閒置的第一幀：暖機從這一幀的牆鐘算起
        IdleVehicleThrottle.advanceClockForTest(2_000L);
        check("暖機：中途不閒置一幀 → 從那一幀重算（距第一次 5 秒、距中斷 2 秒仍每幀都 add）", allTrue(runSeq(2 * n, y), 0));
        IdleVehicleThrottle.advanceClockForTest(2_999L);
        check("暖機：距中斷 4.999 秒仍每幀都 add", allTrue(runSeq(n, y), 0));
        IdleVehicleThrottle.advanceClockForTest(1L);
        seq = runSeq(4 * n, y);
        boolean onTurn = count(seq) == 4;
        long f0 = frame() - 4L * n + 1;
        for (int i = 0; i < seq.length; i++) {
            onTurn &= seq[i] == (((f0 + i + y.vehicleId) & (n - 1)) == 0);
        }
        check("暖機：距中斷滿 5 秒後只在自己的輪次 add（每 N 幀 1 次）", onTurn);
        check("對照車全程每 N 幀 1 次、沒有稽核、沒有停用", count(runSeq(4 * n, z)) == 4 && IdleVehicleThrottle.auditsForTest() == 0
                && !IdleVehicleThrottle.disabledForTest());
        IdleVehicleThrottle.forceBeatForTest();
        run(1);
        String line = IdleVehicleThrottle.lastBeatLine;
        check("心跳有 warmup／devOn／devScans（" + line + "）", line != null && !line.contains(" warmup=0 ") && !line.contains(" devOn=0 ")
                && !line.contains(" devScans=0 ") && line.contains(" warmup=") && line.contains(" devOn="));
    }

    /** 跑 frames 幀，回傳 v 每一幀有沒有被放進 bucket。 */
    static boolean[] runSeq(int frames, BaseVehicle v) throws Exception {
        boolean[] seq = new boolean[frames];
        for (int f = 0; f < frames; f++) {
            seq[f] = run(1).get(v) == 1;
        }
        return seq;
    }

    static int count(boolean[] seq) {
        int c = 0;
        for (boolean b : seq) {
            c += b ? 1 : 0;
        }
        return c;
    }

    static int firstTrue(boolean[] seq) {
        for (int i = 0; i < seq.length; i++) {
            if (seq[i]) {
                return i;
            }
        }
        return -1;
    }

    static boolean allTrue(boolean[] seq, int from) {
        for (int i = from; i < seq.length; i++) {
            if (!seq[i]) {
                return false;
            }
        }
        return true;
    }

    /** 跑 frames 幀真的 startFrame，回傳每台車被放進 FULL bucket 的次數。 */
    static Map<BaseVehicle, Integer> run(int frames) throws Exception {
        Map<BaseVehicle, Integer> counts = new HashMap<>();
        for (IsoMovingObject o : objects) {
            counts.put((BaseVehicle) o, 0);
        }
        for (int f = 0; f < frames; f++) {
            MovingObjectUpdateScheduler.instance.startFrame();
            for (IsoMovingObject o : fullBucket()) {
                counts.merge((BaseVehicle) o, 1, Integer::sum);
            }
        }
        return counts;
    }

    @SuppressWarnings("unchecked")
    static List<IsoMovingObject> fullBucket() throws Exception {
        MovingObjectUpdateSchedulerUpdateBucket[] levels = (MovingObjectUpdateSchedulerUpdateBucket[]) get(MovingObjectUpdateScheduler.class,
                MovingObjectUpdateScheduler.instance, "simulationLevels");
        MovingObjectUpdateSchedulerUpdateBucket full = levels[UpdateSchedulerSimulationLevel.FULL.getUpdateOrderIndex()];
        return ((List<IsoMovingObject>[]) get(MovingObjectUpdateSchedulerUpdateBucket.class, full, "buckets"))[0];
    }

    static long frame() {
        return MovingObjectUpdateScheduler.instance.getFrameCounter();
    }

    static boolean all(Map<BaseVehicle, Integer> counts, List<BaseVehicle> vs, int want) {
        for (BaseVehicle v : vs) {
            if (counts.get(v) != want) {
                System.out.println("  vid=" + v.vehicleId + " count=" + counts.get(v) + " want=" + want);
                return false;
            }
        }
        return true;
    }

    static void world() throws Exception {
        cell = alloc(IsoCell.class);
        set(IsoCell.class, cell, "objectList", objects);
        set(IsoCell.class, cell, "vehicles", new HashSet<BaseVehicle>());
        IsoWorld world = alloc(IsoWorld.class);
        world.currentCell = cell;
        IsoWorld.instance = world;
        square = alloc(IsoGridSquare.class);
        chunk = alloc(IsoChunk.class);
    }

    /** 閒置的車：伺服器授權、物理沒開、兩個空座位、沒動物、沒拖、沒引擎零件（Idle）、警報沒響、燈條警笛關、在格子與 chunk 上。 */
    @SuppressWarnings("unchecked")
    static BaseVehicle vehicle(short id) throws Exception {
        BaseVehicle v = alloc(BaseVehicle.class);
        set(BaseVehicle.class, v, "physics", alloc(CarController.class));
        set(BaseVehicle.class, v, "jniTransform", new Transform());
        set(BaseVehicle.class, v, "passengers", new BaseVehicle.Passenger[] {alloc(BaseVehicle.Passenger.class),
                alloc(BaseVehicle.Passenger.class)});
        v.animals = new ArrayList<>();
        VehicleParts parts = alloc(VehicleParts.class);
        set(VehicleParts.class, parts, "parts", new ArrayList<VehiclePart>());
        set(BaseVehicle.class, v, "parts", parts);
        set(BaseVehicle.class, v, "vehicleAlarm", alloc(VehicleAlarm.class));
        set(BaseVehicle.class, v, "lightbarLightsMode", new LightbarLightsMode());
        set(BaseVehicle.class, v, "lightbarSirenMode", new LightbarSirenMode());
        setInt(BaseVehicle.class, v, "vehicleTowingId", -1);
        v.physicActiveCheck = -1L;
        v.constraintChangedTime = -1L;
        v.netPlayerAuthorization = BaseVehicle.Authorization.Server;
        v.vehicleId = id;
        set(IsoMovingObject.class, v, "current", square);
        v.chunk = chunk;
        v.setX(1000.5F + id);
        v.setY(2000.5F);
        v.setZ(0.0F);
        objects.add(v);
        ((Set<BaseVehicle>) get(IsoCell.class, cell, "vehicles")).add(v);
        return v;
    }

    static VehiclePart runningEngine() throws Exception {
        VehiclePart part = alloc(VehiclePart.class);
        VehicleEngine engine = alloc(VehicleEngine.class);
        set(VehicleEngine.class, engine, "state", BaseVehicle.engineStateTypes.Running);
        set(VehiclePart.class, part, "engine", engine);
        return part;
    }

    interface Flip {
        void apply(boolean on) throws Exception;
    }

    private static void finish(String mode) {
        System.out.println(failed == 0 ? "IdleVehicleThrottleTest OK (" + mode + ")" : "IdleVehicleThrottleTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static void setInt(Class<?> owner, Object target, String name, int value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    static void setBool(Class<?> owner, Object target, String name, boolean value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }

    static void setDouble(Class<?> owner, Object target, String name, double value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setDouble(target, value);
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private IdleVehicleThrottleTest() {}
}
