package zombie.vehicleNetworkSound.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.DrainableComboItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.iso.Vector3;
import zombie.network.GameServer;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.LightbarSirenMode;
import zombie.vehicles.VehicleAlarm;
import zombie.vehicles.VehicleDoor;
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
 * 另有六種聲源各自成立（警報、倒車蜂鳴、車門警報、引擎、喇叭、警笛）與全部關閉的確定性案例，並以少一個條件的替身預篩確認每個條件都有案例守住。
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
            check("vanilla：Manager 來自 jar（classpath 不含 dist）", src.endsWith(".jar"));
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
        }
        cases(real, empty, vanilla);
        if (mode.equals("on")) {   // 沒有連線的一輪只記計數、不建清單
            long b0 = MdcVehicleSoundPrefilter.builtForTest();
            List<UdpConnection> saved = GameServer.udpEngine.connections;
            set(UdpEngine.class, GameServer.udpEngine, "connections", new ArrayList<UdpConnection>());
            empty.update();
            set(UdpEngine.class, GameServer.udpEngine, "connections", saved);
            check("on：沒有連線的一輪不建清單", MdcVehicleSoundPrefilter.builtForTest() == b0);
        }
        if (!vanilla) {
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

    static final String[] SOURCES = {"警報", "倒車蜂鳴", "車門警報", "引擎不是 Idle", "喇叭", "警笛"};

    /**
     * 六種聲源各自成立＋全部關閉：每個案例跑一輪真的流程，逐條連線等於原版迴圈；再以「少一個條件」的替身預篩重算，
     * 證明拿掉任何一個條件至少一個案例會不同（車門警報例外：它要先 isEngineRunning，引擎條件一定同時成立）。
     */
    @SuppressWarnings("unchecked")
    static void cases(Manager real, Manager empty, boolean vanilla) throws Exception {
        BaseVehicle t = list.get(0);
        VehicleScript script = new VehicleScript();
        script.getSounds().alarmEnable = Boolean.TRUE;   // hasAlarm
        script.getLightbar().enable = true;              // hasSiren
        VehicleScript.Passenger seat = alloc(VehicleScript.Passenger.class);
        seat.door = "DoorFrontLeft";
        ((ArrayList<VehicleScript.Passenger>) get(VehicleScript.class, script, "passengers")).add(seat);
        set(BaseVehicle.class, t, "script", script);
        set(BaseVehicle.class, t, "passengers", new BaseVehicle.Passenger[] {alloc(BaseVehicle.Passenger.class)});
        VehicleParts parts = (VehicleParts) get(BaseVehicle.class, t, "parts");
        VehiclePart doorPart = alloc(VehiclePart.class);
        VehicleDoor door = alloc(VehicleDoor.class);
        set(VehiclePart.class, doorPart, "door", door);
        HashMap<String, VehiclePart> byId = new HashMap<>();
        byId.put("DoorFrontLeft", doorPart);
        set(VehicleParts.class, parts, "partsById", byId);
        VehiclePart battery = alloc(VehiclePart.class);   // 電瓶有電（警報、警笛要 getBatteryCharge > 0）
        DrainableComboItem cell = alloc(DrainableComboItem.class);
        setInt(InventoryItem.class, cell, "uses", 1);
        setFloat(DrainableComboItem.class, cell, "useDelta", 1.0F);
        set(VehiclePart.class, battery, "item", cell);
        set(VehicleParts.class, parts, "battery", battery);
        VehicleAlarm alarm = (VehicleAlarm) get(BaseVehicle.class, t, "vehicleAlarm");
        boolean[] killed = new boolean[SOURCES.length];
        for (int k = -1; k < SOURCES.length; k++) {
            silence();
            set(VehicleDoor.class, door, "open", false);
            setDouble(VehicleAlarm.class, alarm, "startTime", 0.0);
            t.lightbarSirenMode.set(0);
            switch (k) {
                case 0 -> setDouble(VehicleAlarm.class, alarm, "startTime", 1.0);
                case 1 -> setBool(t, "soundBackMoveOn", true);
                case 2 -> {
                    setEngine(t, true);
                    set(VehicleDoor.class, door, "open", true);
                }
                case 3 -> setEngine(t, true);
                case 4 -> setBool(t, "soundHornOn", true);
                case 5 -> t.lightbarSirenMode.set(1);
                default -> { }
            }
            boolean[] on = sources(t);
            boolean alone = true;
            for (int j = 0; j < on.length; j++) {
                alone &= on[j] == (j == k || (k == 2 && j == 3));
            }
            String name = k < 0 ? "全部關閉" : SOURCES[k];
            check("案例「" + name + "」：原版六個條件" + (k == 2 ? "只有車門警報與引擎成立（車門警報要先 isEngineRunning）" : k < 0 ? "都不成立" : "只有它成立"), alone);
            if (!vanilla) {
                check("案例「" + name + "」：audible() " + (k < 0 ? "false" : "true"), MdcVehicleSoundPrefilter.audible(t) == (k >= 0));
            }
            empty.update();
            boolean same = true;
            boolean hit = false;
            for (Connection c : conns) {
                List<BaseVehicle> want = oracle(c);
                same &= new ArrayList<>(relevant(real, c, vanilla)).equals(want);
                hit |= want.contains(t);
                for (int m = 0; m < SOURCES.length; m++) {
                    killed[m] |= !minus(c, m).equals(want);
                }
            }
            check("案例「" + name + "」：每條連線（內容與順序）等於原版迴圈；" + (k < 0 ? "沒有連線收到這台車" : "至少一條連線收到這台車"),
                    same && hit == (k >= 0));
        }
        for (int m = 0; m < SOURCES.length; m++) {
            if (m == 2) {
                System.out.println("INFO 少了「車門警報」的替身：" + (killed[m] ? "有案例不同" : "沒有案例不同（車門警報成立時引擎一定不是 Idle，這個條件被引擎條件涵蓋）"));
            } else {
                check("少了「" + SOURCES[m] + "」的替身預篩：至少一個案例和原版不同", killed[m]);
            }
        }
        silence();
        setDouble(VehicleAlarm.class, alarm, "startTime", 0.0);
        t.lightbarSirenMode.set(0);
    }

    /** 原版六個聲音條件（Connection.isRelevant 的順序）。 */
    static boolean[] sources(BaseVehicle v) {
        return new boolean[] {v.isAlarmActive(), v.isBackupBeeperSounding(), v.isDoorAlarmSounding(),
                v.getEngineState() != BaseVehicle.engineStateTypes.Idle, v.isHornSounding(), v.isSirenSounding()};
    }

    /** 少了第 m 個條件的替身預篩：依 getVehicles() 順序篩車，再照原版迴圈判斷。 */
    static List<BaseVehicle> minus(Connection c, int m) {
        List<BaseVehicle> cand = new ArrayList<>();
        for (BaseVehicle v : cell.getVehicles()) {
            boolean[] on = sources(v);
            on[m] = false;
            boolean any = false;
            for (boolean b : on) {
                any |= b;
            }
            if (v.vehicleId != -1 && any) {
                cand.add(v);
            }
        }
        Set<BaseVehicle> temp = new HashSet<>();
        for (BaseVehicle v : cand) {
            if (v.vehicleId != -1 && c.isRelevant(v)) {
                temp.add(v);
            }
        }
        return new ArrayList<>(temp);
    }

    /** 全部車靜音、id 照索引；第 0 台車與第 0 條連線放在同一點，其他連線放遠。 */
    static void silence() throws Exception {
        for (int i = 0; i < list.size(); i++) {
            BaseVehicle v = list.get(i);
            v.vehicleId = (short) i;
            setEngine(v, false);
            setBool(v, "soundHornOn", false);
            setBool(v, "soundBackMoveOn", false);
            v.setX(100 + 10 * i);
            v.setY(100);
        }
        for (int i = 0; i < conns.size(); i++) {
            Vector3[] pos = (Vector3[]) get(UdpConnection.class, conns.get(i).getUdpConnection(), "releventPos");
            pos[0].set(i == 0 ? 100 : 5000 + 1000 * i, i == 0 ? 100 : 5000, 0.0F);
        }
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

    static void setInt(Class<?> owner, Object target, String name, int value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    static void setFloat(Class<?> owner, Object target, String name, float value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setFloat(target, value);
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

    private MdcVehicleSoundPrefilterTest() {}
}
