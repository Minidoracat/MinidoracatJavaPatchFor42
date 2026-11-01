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
 * <p>用法：{@code IdleVehicleThrottleTest vanilla|off|observe|on|on-throw [N]}。vanilla 的 classpath 不含 dist（jar 原版負對照）；
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
        switch (mode) {
            case "off" -> {
                Map<BaseVehicle, Integer> counts = run(4 * n);
                check("off：每台車每幀都 add、不計數", all(counts, idle, 4 * n) && all(counts, active, 4 * n)
                        && IdleVehicleThrottle.wouldSkipForTest() == 0);
            }
            case "observe" -> observe(idle, active, n);
            case "on" -> on(idle, active, n);
            case "on-throw" -> onThrow(idle, active, n);
            default -> throw new IllegalArgumentException(mode);
        }
        finish(mode);
    }

    static void truthTable() throws Exception {
        BaseVehicle v = vehicle((short) 900);
        objects.remove(v);
        cell.vehicles.remove(v);
        check("真值表：全部條件成立＝閒置", IdleVehicleThrottle.idle(v));
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
        for (Map.Entry<String, Flip> e : flips.entrySet()) {
            e.getValue().apply(true);
            boolean off = !IdleVehicleThrottle.idle(v);
            e.getValue().apply(false);
            check("真值表：" + e.getKey() + " → 不閒置；翻回來又閒置", off && IdleVehicleThrottle.idle(v));
        }
    }

    static void observe(List<BaseVehicle> idle, List<BaseVehicle> active, int n) throws Exception {
        Map<BaseVehicle, Integer> counts = run(4 * n);
        check("observe：每台車每幀都 add", all(counts, idle, 4 * n) && all(counts, active, 4 * n));
        check("observe：會跳的次數＝每台閒置車每 N 幀 N−1 次（" + IDLE * 4 * (n - 1) + "）",
                IdleVehicleThrottle.wouldSkipForTest() == (long) IDLE * 4 * (n - 1) && IdleVehicleThrottle.skippedForTest() == 0);
        check("observe：沒動過的車指紋都相同", IdleVehicleThrottle.posDiffForTest() == 0 && IdleVehicleThrottle.stateDiffForTest() == 0
                && IdleVehicleThrottle.comparedForTest() > 0);
        // 找下一幀「會跳」的兩台：一台只改位置（posDiff），一台改位置又改狀態（算 stateDiff）
        long next = frame() + 1;
        BaseVehicle moved = firstWouldSkip(idle, next, n, null);
        BaseVehicle woke = firstWouldSkip(idle, next, n, moved);
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
                        && line.contains("posTop=" + moved.vehicleId + ":") && line.contains("(x)") && line.contains(" disabled=false"));
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

    static BaseVehicle firstWouldSkip(List<BaseVehicle> idle, long frame, int n, BaseVehicle not) {
        for (BaseVehicle v : idle) {
            if (v != not && ((frame + v.vehicleId) & (n - 1)) != 0) {
                return v;
            }
        }
        throw new AssertionError("找不到會跳的車");
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
        set(BaseVehicle.class, v, "parts", alloc(VehicleParts.class));
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
