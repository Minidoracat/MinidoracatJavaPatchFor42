package zombie.vehicleNetworkSound.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.iso.Vector3;
import zombie.network.GameServer;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.LightbarSirenMode;
import zombie.vehicles.VehicleAlarm;
import zombie.vehicles.VehicleEngine;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleParts;

/**
 * W90：隨機車與連線，每一輪先跑真的 {@code Manager.update()}（手術後的 {@code GETFIELD connections} 改道＝一輪開始；這個 Manager 沒有
 * 登記連線，所以 updateConnection 在送封包前就返回），再對每條連線照 updateConnection 的順序呼叫 {@code getUdpConnection} 改道與私有的
 * {@code getVehiclesRelevantToConnection}（手術後的 {@code getVehicles} 改道），結果逐條（內容與順序）和原版迴圈的 oracle 比對。
 *
 * <p>用法：{@code MdcVehicleSoundPrefilterTest vanilla|off|observe|on}。vanilla 的 classpath 不含 dist（jar 原版 Manager，證明 oracle
 * 就是原版的結果）。車的半徑條件隨機：引擎 Running／Idle、喇叭、倒車蜂鳴、{@code vehicleId == -1}；連線的 releventPos 隨機。
 */
public final class MdcVehicleSoundPrefilterTest {

    static final int VEHICLES = 300;
    static final int CONNS = 12;
    private static int failed;
    static final Random R = new Random(90L);
    static IsoCell cell;
    static final Set<BaseVehicle> vehicles = new HashSet<>();
    static final List<BaseVehicle> list = new ArrayList<>();
    static final List<Connection> conns = new ArrayList<>();
    static Method relevantMethod;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args[0];
        boolean vanilla = mode.equals("vanilla");
        GameServer.server = true;
        world();
        Manager real = new Manager();
        Manager empty = new Manager();
        relevantMethod = Manager.class.getDeclaredMethod("getVehiclesRelevantToConnection", Connection.class, Set.class);
        relevantMethod.setAccessible(true);
        if (vanilla) {
            String src = Manager.class.getProtectionDomain().getCodeSource().getLocation().toString();
            check("vanilla：Manager 來自 jar（classpath 不含 dist）", src.endsWith("projectzomboid.jar"));
        } else {
            int want = mode.equals("off") ? MdcVehicleSoundPrefilter.OFF : mode.equals("on") ? MdcVehicleSoundPrefilter.ON
                    : MdcVehicleSoundPrefilter.OBSERVE;
            check("模式與參數一致（" + MdcVehicleSoundPrefilter.modeName() + "）", MdcVehicleSoundPrefilter.MODE == want);
        }
        int rounds = 600;
        int mismatchRounds = 0;
        long relevantTotal = 0;
        for (int r = 0; r < rounds; r++) {
            randomize();
            empty.update();
            for (Connection c : conns) {
                Set<BaseVehicle> got = relevant(real, c, vanilla);
                List<BaseVehicle> want = oracle(c);
                relevantTotal += want.size();
                if (!new ArrayList<>(got).equals(want)) {
                    mismatchRounds++;
                }
            }
        }
        check(rounds + " 輪 × " + CONNS + " 條連線逐條（內容與順序）等於原版迴圈（相關車共 " + relevantTotal + " 台次）",
                mismatchRounds == 0 && relevantTotal > 0);
        if (!vanilla) {
            check("沒有不一致、沒有停用", !MdcVehicleSoundPrefilter.disabledForTest() && MdcVehicleSoundPrefilter.mismatchesForTest() == 0
                    && MdcVehicleSoundPrefilter.anomaliesForTest() == 0);
            if (mode.equals("off")) {
                check("off：不比對、不建清單", MdcVehicleSoundPrefilter.comparesForTest() == 0
                        && MdcVehicleSoundPrefilter.candidatesForTest() == 0);
            } else {
                long wantCompares = (long) (mode.equals("on") ? rounds / 256 : rounds / 16) * CONNS;
                check("比對輪數：" + (mode.equals("on") ? "on 每 256 輪" : "observe 每 16 輪") + "一輪、每條連線一次（" + wantCompares + "）",
                        MdcVehicleSoundPrefilter.comparesForTest() == wantCompares);
            }
            if (mode.equals("on")) {
                check("on：篩好的清單比全部車少（" + MdcVehicleSoundPrefilter.candidatesForTest() + " / " + VEHICLES + "）",
                        MdcVehicleSoundPrefilter.candidatesForTest() > 0 && MdcVehicleSoundPrefilter.candidatesForTest() < VEHICLES);
            }
            if (mode.equals("off")) {
                check("off：不印心跳、不記狀態", MdcVehicleSoundPrefilter.lastBeatLine == null);
            } else {
                violation(real, empty, mode);
                MdcVehicleSoundPrefilter.forceBeatForTest();
                empty.update();
                String line = MdcVehicleSoundPrefilter.lastBeatLine;
                check("停用後心跳照印（" + line + "）", line != null && line.contains("mode=" + mode) && line.contains(" compares=")
                        && line.contains(" mismatches=1") && line.contains(" disabled=true"));
            }
        }
        finish(mode);
    }

    /** 前提被打破（Manager.update 期間有車的聲音狀態變了）：抽到比對那一輪抓到、本次啟動永久回原版，之後結果又等於原版。 */
    static void violation(Manager real, Manager empty, String mode) throws Exception {
        int limit = mode.equals("on") ? 256 : 16;
        int r = 0;
        for (; r < limit && !MdcVehicleSoundPrefilter.disabledForTest(); r++) {
            randomize();
            empty.update();
            for (BaseVehicle v : list) {   // 一輪開始之後才把全部車都發動
                if (v.vehicleId != -1) {
                    setEngine(v, true);
                }
            }
            for (Connection c : conns) {
                relevant(real, c, false);
            }
        }
        check("違反前提：" + limit + " 輪內抓到不一致並停用（第 " + r + " 輪）", MdcVehicleSoundPrefilter.disabledForTest()
                && MdcVehicleSoundPrefilter.mismatchesForTest() == 1);
        randomize();
        empty.update();
        for (BaseVehicle v : list) {
            if (v.vehicleId != -1) {
                setEngine(v, true);
            }
        }
        boolean same = true;
        for (Connection c : conns) {
            same &= new ArrayList<>(relevant(real, c, false)).equals(oracle(c));
        }
        check("停用後照原版：同樣中途改狀態，結果等於原版迴圈", same);
    }

    @SuppressWarnings("unchecked")
    static Set<BaseVehicle> relevant(Manager m, Connection c, boolean vanilla) throws Exception {
        if (!vanilla) {
            MdcVehicleSoundPrefilter.getUdpConnection(c);   // updateConnection offset 17 的改道
        }
        Set<BaseVehicle> temp = new HashSet<>();
        relevantMethod.invoke(m, c, temp);
        return temp;
    }

    /** 原版 getVehiclesRelevantToConnection 的迴圈：依 getVehicles() 順序，vehicleId != -1 且 isRelevant 才加進 HashSet。 */
    static List<BaseVehicle> oracle(Connection c) {
        Set<BaseVehicle> temp = new HashSet<>();
        for (BaseVehicle v : cell.getVehicles()) {
            if (v.vehicleId != -1 && c.isRelevant(v)) {
                temp.add(v);
            }
        }
        return new ArrayList<>(temp);
    }

    static void randomize() throws Exception {
        for (int i = 0; i < list.size(); i++) {
            BaseVehicle v = list.get(i);
            v.vehicleId = R.nextInt(20) == 0 ? -1 : (short) i;
            setEngine(v, R.nextInt(8) == 0);
            setBool(v, "soundHornOn", R.nextInt(25) == 0);
            setBool(v, "soundBackMoveOn", R.nextInt(25) == 0);
            v.setX(R.nextInt(2000));
            v.setY(R.nextInt(2000));
        }
        for (Connection c : conns) {
            Vector3[] pos = (Vector3[]) get(UdpConnection.class, c.getUdpConnection(), "releventPos");
            pos[0].set(R.nextInt(2000), R.nextInt(2000), 0.0F);
        }
    }

    static void world() throws Exception {
        cell = alloc(IsoCell.class);
        set(IsoCell.class, cell, "vehicles", vehicles);
        IsoWorld world = alloc(IsoWorld.class);
        world.currentCell = cell;
        IsoWorld.instance = world;
        VehicleScript script = new VehicleScript();
        for (int i = 0; i < VEHICLES; i++) {
            BaseVehicle v = alloc(BaseVehicle.class);
            set(BaseVehicle.class, v, "script", script);
            set(BaseVehicle.class, v, "parts", alloc(VehicleParts.class));
            set(BaseVehicle.class, v, "vehicleAlarm", alloc(VehicleAlarm.class));
            set(BaseVehicle.class, v, "lightbarSirenMode", new LightbarSirenMode());
            set(BaseVehicle.class, v, "passengers", new BaseVehicle.Passenger[0]);
            list.add(v);
            vehicles.add(v);
        }
        UdpEngine engine = alloc(UdpEngine.class);
        List<UdpConnection> udps = new ArrayList<>();
        for (int i = 0; i < CONNS; i++) {
            UdpConnection u = alloc(UdpConnection.class);
            set(UdpConnection.class, u, "index", i);
            set(UdpConnection.class, u, "connectArea", new Vector3[4]);
            Vector3[] pos = new Vector3[4];
            pos[0] = new Vector3();
            set(UdpConnection.class, u, "releventPos", pos);
            udps.add(u);
            conns.add(new Connection(u));
        }
        set(UdpEngine.class, engine, "connections", udps);
        GameServer.udpEngine = engine;
    }

    static void setEngine(BaseVehicle v, boolean running) throws Exception {
        VehicleParts parts = (VehicleParts) get(BaseVehicle.class, v, "parts");
        VehiclePart part = (VehiclePart) get(VehicleParts.class, parts, "engine");
        if (part == null) {
            part = alloc(VehiclePart.class);
            set(VehiclePart.class, part, "engine", alloc(VehicleEngine.class));
            set(VehicleParts.class, parts, "engine", part);
        }
        set(VehicleEngine.class, part.getVehicleEngine(), "state",
                running ? BaseVehicle.engineStateTypes.Running : BaseVehicle.engineStateTypes.Idle);
    }

    private static void finish(String mode) {
        System.out.println(failed == 0 ? "MdcVehicleSoundPrefilterTest OK (" + mode + ")" : "MdcVehicleSoundPrefilterTest FAILED " + failed);
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

    static void setBool(BaseVehicle v, String name, boolean value) throws Exception {
        Field f = BaseVehicle.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(v, value);
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private MdcVehicleSoundPrefilterTest() {}
}
