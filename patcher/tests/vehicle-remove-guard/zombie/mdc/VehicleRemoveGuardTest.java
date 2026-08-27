package zombie.mdc;

import java.lang.reflect.Constructor;
import java.util.HashMap;

import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import zombie.vehicles.BaseVehicle;
import zombie.world.moddata.GlobalModData;

/**
 * W19 VehicleRemoveGuard 行為驗證（獨立 JVM；MODE 是 static final，三組態由 build.ps1
 * 分開驅動並以 argv 自驗，property 拼錯不得假綠）。
 * 覆蓋：三態、caller 分類（Lua 指紋／Java 維運 frame）、MVCK 認領狀態機全六路、
 * 空殼 vehicle 整段不炸（觀測刀不得擋刪車）、rate-limit 窗。
 */
public final class VehicleRemoveGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "observe";
        int wantMode = switch (want) {
            case "off" -> VehicleRemoveGuard.MODE_OFF;
            case "enforce" -> VehicleRemoveGuard.MODE_ENFORCE;
            default -> VehicleRemoveGuard.MODE_OBSERVE;
        };
        expect("property 與測試模式一致（" + want + "）", VehicleRemoveGuard.MODE == wantMode);

        testClassifier();
        testClaimStateMachine();
        testOnRemove(wantMode);

        if (failed != 0) {
            System.out.println("vehicle-remove-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("vehicle-remove-guard OK mode=" + VehicleRemoveGuard.MODE);
    }

    private static void testClassifier() {
        StackTraceElement[] luaStack = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("zombie.mdc.VehicleRemoveGuard", "onRemove", "VehicleRemoveGuard.java", 1),
                new StackTraceElement("zombie.vehicles.BaseVehicle", "permanentlyRemove", "BaseVehicle.java", 8041),
                new StackTraceElement("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke", null, 1),
                new StackTraceElement("java.lang.reflect.Method", "invoke", "Method.java", 1),
                new StackTraceElement("se.krka.kahlua.integration.expose.caller.MethodCaller", "call", null, 1),
                new StackTraceElement("se.krka.kahlua.vm.KahluaThread", "callJava", "KahluaThread.java", 1),
                new StackTraceElement("zombie.Lua.Event", "trigger", "Event.java", 1),
        };
        expect("Lua stack：firstForeignFrame 落在 Kahlua 反射鏈第一個具名 frame",
                VehicleRemoveGuard.firstForeignFrame(luaStack)
                        .startsWith("jdk.internal.reflect.DirectMethodHandleAccessor.invoke"));
        expect("Lua stack：luaSeen=true", VehicleRemoveGuard.luaSeen(luaStack));

        StackTraceElement[] javaStack = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("zombie.mdc.VehicleRemoveGuard", "onRemove", "VehicleRemoveGuard.java", 1),
                new StackTraceElement("zombie.vehicles.BaseVehicle", "permanentlyRemove", "BaseVehicle.java", 8041),
                new StackTraceElement("zombie.vehicles.VehicleManager", "removeVehicles", "VehicleManager.java", 50),
                new StackTraceElement("zombie.network.GameServer", "main", "GameServer.java", 996),
        };
        expect("Java stack：firstForeignFrame＝VehicleManager.removeVehicles:50",
                "zombie.vehicles.VehicleManager.removeVehicles:50"
                        .equals(VehicleRemoveGuard.firstForeignFrame(javaStack)));
        expect("Java stack：luaSeen=false", !VehicleRemoveGuard.luaSeen(javaStack));

        StackTraceElement[] setSmashed = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("zombie.mdc.VehicleRemoveGuard", "onRemove", "VehicleRemoveGuard.java", 1),
                new StackTraceElement("zombie.vehicles.BaseVehicle", "permanentlyRemove", "BaseVehicle.java", 8041),
                new StackTraceElement("zombie.vehicles.BaseVehicle", "setSmashed", "BaseVehicle.java", 10442),
        };
        expect("setSmashed 自呼：跳過 permanentlyRemove 自身、保留 setSmashed frame",
                "zombie.vehicles.BaseVehicle.setSmashed:10442"
                        .equals(VehicleRemoveGuard.firstForeignFrame(setSmashed)));
    }

    private static void testClaimStateMachine() {
        GlobalModData saved = GlobalModData.instance;
        try {
            GlobalModData.instance = null;

            expect("null modData → no-moddata",
                    "no-moddata".equals(VehicleRemoveGuard.claimStateOf(null)));

            KahluaTable modData = newTable();
            expect("無 SQLID 印記 → unclaimed（MVCK：從未認領）",
                    "unclaimed".equals(VehicleRemoveGuard.claimStateOf(modData)));

            Double sqlid = 1.7239E12;
            modData.rawset(VehicleRemoveGuard.MVCK_IMPRINT_KEY, sqlid);
            expect("有印記但 GlobalModData 未初始化 → unknown-gmd（不靜默）",
                    "unknown-gmd".equals(VehicleRemoveGuard.claimStateOf(modData)));

            GlobalModData.instance = new GlobalModData();
            expect("有印記但 MVCK 表不存在 → no-mvck-table",
                    "no-mvck-table".equals(VehicleRemoveGuard.claimStateOf(modData)));

            KahluaTable byVehicle = newTable();
            GlobalModData.instance.add(VehicleRemoveGuard.MVCK_TABLE, byVehicle);
            expect("有印記、表無條目 → stale-imprint（unclaim 不清印記的實況）",
                    "stale-imprint".equals(VehicleRemoveGuard.claimStateOf(modData)));

            KahluaTable entry = newTable();
            entry.rawset(VehicleRemoveGuard.MVCK_OWNER_KEY, "Player-F");
            byVehicle.rawset(sqlid, entry);
            expect("有條目 → claimed:<owner>",
                    "claimed:Player-F".equals(VehicleRemoveGuard.claimStateOf(modData)));
        } finally {
            GlobalModData.instance = saved;
        }
    }

    private static void testOnRemove(int mode) throws Exception {
        BaseVehicle vehicle = (BaseVehicle) rawInstance(BaseVehicle.class);

        long calls0 = VehicleRemoveGuard.callsForTest();
        long logged0 = VehicleRemoveGuard.loggedForTest();
        VehicleRemoveGuard.onRemove(vehicle);
        if (mode == VehicleRemoveGuard.MODE_OFF) {
            expect("off：零計數零記錄（純早退）",
                    VehicleRemoveGuard.callsForTest() == calls0
                    && VehicleRemoveGuard.loggedForTest() == logged0);
            return;
        }
        expect("observe/enforce-alias：calls+1、logged+1、零 anomalies（空殼 vehicle 整段不炸）",
                VehicleRemoveGuard.callsForTest() == calls0 + 1
                && VehicleRemoveGuard.loggedForTest() == logged0 + 1
                && VehicleRemoveGuard.anomaliesForTest() == 0);

        // rate-limit：同窗連打 30 次，完整記錄至多 WINDOW_CAP、其餘 suppressed。
        VehicleRemoveGuard.resetWindowForTest();
        long loggedBefore = VehicleRemoveGuard.loggedForTest();
        long suppressedBefore = VehicleRemoveGuard.suppressedForTest();
        for (int i = 0; i < 30; i++) {
            VehicleRemoveGuard.onRemove(vehicle);
        }
        long loggedDelta = VehicleRemoveGuard.loggedForTest() - loggedBefore;
        long suppressedDelta = VehicleRemoveGuard.suppressedForTest() - suppressedBefore;
        expect("rate-limit：30 連打 → 記錄 20、壓制 10（窗上限咬住）",
                loggedDelta == 20 && suppressedDelta == 10);
        expect("rate-limit 全程零 anomalies", VehicleRemoveGuard.anomaliesForTest() == 0);
    }

    private static KahluaTable newTable() {
        return new KahluaTableImpl(new HashMap<>());
    }

    /** 以 serialization 建構子分配未初始化實例，避開世界／SandboxOptions 依賴（W12 慣例）。 */
    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "vrg pass  " : "vrg FAIL  ") + what);
        if (!ok) failed++;
    }

    private VehicleRemoveGuardTest() {}
}
