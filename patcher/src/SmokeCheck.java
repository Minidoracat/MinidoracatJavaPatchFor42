import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 守衛語意驗證（codex 要求的驗證門檻）：
 *  1. 行為 smoke＋負對照：原版 new hit.Zombie().process() 必拋 NPE（證明裸跑必炸）；
 *     修補版必須安靜返回（證明 guard 真的在 super 之前生效）。Fall.process(null) 同理。
 *  2. ASM 結構斷言：guard 序列位於方法最前、invokespecial Character.process 恰一次且在 guard 後、
 *     原 9 個 IsoZombie setter 未增減。
 */
public final class SmokeCheck {

    public static void main(String[] args) throws Exception {
        Path distJava = Path.of(args[0]);
        Path jar = Path.of(args[1]);

        if (args.length > 2 && args[2].equals("client")) {
            if (clientChecks(distJava, jar) > 0) {
                System.exit(1);
            }
            System.out.println("client 守衛語意驗證全數通過");
            return;
        }

        int failed = 0;

        // ---- 1. 行為 smoke ----
        try (URLClassLoader patched = new URLClassLoader(
                     new URL[]{ distJava.toUri().toURL(), jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
             URLClassLoader original = new URLClassLoader(
                     new URL[]{ jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {

            failed += expect("原版 Zombie.process() 必拋 NPE（負對照）",
                    invokeProcess(original, "zombie.network.fields.hit.Zombie", false), true);
            failed += expect("修補版 Zombie.process() 安靜返回",
                    invokeProcess(patched, "zombie.network.fields.hit.Zombie", false), false);
            failed += expect("原版 Fall.process(null) 必拋 NPE（負對照）",
                    invokeProcess(original, "zombie.network.fields.hit.Fall", true), true);
            failed += expect("修補版 Fall.process(null) 安靜返回",
                    invokeProcess(patched, "zombie.network.fields.hit.Fall", true), false);
            failed += check("未搬動固定物件保留 container count",
                    invokeLootContainerCount(patched, false) == 1);
            failed += check("搬動物件的 container count 強制為零",
                    invokeLootContainerCount(patched, true) == 0);
            failed += check("非 TownZone 固定容器 fallback 與搬動負對照",
                    checkLootZoneFallback(patched));

            // 載具預篩幾何純函數（點到線段平方距離；零 false-negative 的數學基礎）
            Class<?> prefilter = Class.forName("zombie.mdc.VehicleIntersectPrefilter", true, patched);
            Method distSq = prefilter.getMethod("distSqPointSegment",
                    float.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class);
            float onSeg = (Float)distSq.invoke(null, 5f,0f,0f, 0f,0f,0f, 10f,0f,0f);
            float perp = (Float)distSq.invoke(null, 5f,3f,0f, 0f,0f,0f, 10f,0f,0f);
            float beyond = (Float)distSq.invoke(null, 14f,0f,0f, 0f,0f,0f, 10f,0f,0f);
            float degen = (Float)distSq.invoke(null, 3f,4f,0f, 7f,7f,7f, 7f,7f,7f);
            failed += check("預篩幾何：線段上=0、垂距=9、端點外=16、退化線段=點距",
                    onSeg == 0f && perp == 9f && beyond == 16f
                    && Math.abs(degen - (16f + 9f + 49f)) < 1e-4f);

            // ---- W3 效能第三波行為 smoke（W3-2 已撤刀：microbenchmark 實測 memo 為淨劣化）----
            // W3-1 stagger：任意 onlineId（含負短整數極端）在任意連續 PERIOD(=3) 個 tick 內恰命中一次
            Class<?> throttle = Class.forName("zombie.mdc.ZombieAuthThrottle", true, patched);
            Method due = throttle.getDeclaredMethod("dueThisTick", long.class, short.class);
            due.setAccessible(true);
            boolean staggerOk = true;
            for (short id : new short[]{Short.MIN_VALUE, (short) -1, (short) 0, (short) 1, (short) 7, Short.MAX_VALUE}) {
                for (long base = 0; base < 8 && staggerOk; base++) {
                    int hits = 0;
                    for (long t = base; t < base + 3; t++) {
                        if ((Boolean) due.invoke(null, t, id)) {
                            hits++;
                        }
                    }
                    staggerOk = hits == 1;
                }
            }
            failed += check("W3-1 stagger：任意 onlineId（含負）任意連續 3 tick 恰命中 1 次", staggerOk);
            Method observe = throttle.getDeclaredMethod("observe", long.class);
            observe.setAccessible(true);
            long ob0 = (Long) observe.invoke(null, 1_000_000L);
            long ob1 = (Long) observe.invoke(null, 1_000_010L);
            long ob2 = (Long) observe.invoke(null, 1_000_060L);
            failed += check("W3-1 pass 邊界偵測：<50ms 不推進、>=50ms 推進一格",
                    ob1 == ob0 && ob2 == ob0 + 1);
            // code review MAJOR-1 釘子：100ms 長 pass（呼叫間隔 10ms）只推進進場那一格
            long b0 = (Long) observe.invoke(null, 2_000_000L);
            for (long t = 2_000_010L; t <= 2_000_100L; t += 10L) {
                observe.invoke(null, t);
            }
            failed += check("W3-1 長 pass 內不重複推進（防步進共振餓死）",
                    (Long) observe.invoke(null, 2_000_100L) == b0);
            // 快 tick 保底：呼叫間隔 30ms（<50 永不觸發 pass 邊界）時，250ms fallback 仍推進
            long c0 = (Long) observe.invoke(null, 3_000_000L);
            long cEnd = c0;
            for (long t = 3_000_030L; t <= 3_000_300L; t += 30L) {
                cEnd = (Long) observe.invoke(null, t);
            }
            failed += check("W3-1 快 tick 保底：30ms 間隔跨 300ms 恰推進一次", cEnd == c0 + 1);

            // W3-3 threshold 純函式：下限 12、動態跟隨 spottingDist、MAX_VALUE 在 float domain 安全
            Class<?> spotPre = Class.forName("zombie.characters.animals.behavior.AnimalSpottedPrefilter", true, patched);
            Method th = spotPre.getDeclaredMethod("thresholdOf", int.class);
            th.setAccessible(true);
            boolean thOk = (Float) th.invoke(null, 10) == 12.0F
                    && (Float) th.invoke(null, 50) == 52.0F
                    && (Float) th.invoke(null, 0) == 12.0F
                    && (Float) th.invoke(null, Integer.MAX_VALUE) > 2.0e9F;
            failed += check("W3-3 threshold：下限 12、跟隨 spottingDist、MAX_VALUE 安全", thOk);

            // W3-4 server 短路：null vehicle 亦回 true（證明 server 路徑零解參考）
            Class<?> gameServer = Class.forName("zombie.network.GameServer", true, patched);
            Field srvField = gameServer.getField("server");
            boolean prevSrv = srvField.getBoolean(null);
            srvField.setBoolean(null, true);
            try {
                Class<?> gate = Class.forName("zombie.mdc.VehicleCouldSeeGate", true, patched);
                Class<?> baseVehCls = Class.forName("zombie.vehicles.BaseVehicle", false, patched);
                Method gm = gate.getMethod("couldSeeIntersectedSquare", baseVehCls, int.class);
                failed += check("W3-4 server 短路：null vehicle 亦回 true（零解參考）",
                        (Boolean) gm.invoke(null, null, 0));
            } finally {
                srvField.setBoolean(null, prevSrv);
            }

        }

        // ---- 2. 結構斷言 ----
        MethodNode zp = method(distJava, "zombie/network/fields/hit/Zombie", "process", "()V");
        AbstractInsnNode[] zh = firstReal(zp, 4);
        boolean zGuard = zh[0] instanceof VarInsnNode v0 && v0.getOpcode() == Opcodes.ALOAD && v0.var == 0
                && zh[1] instanceof MethodInsnNode m1 && m1.name.equals("getZombie")
                && zh[2] instanceof JumpInsnNode j2 && j2.getOpcode() == Opcodes.IFNONNULL
                && zh[3].getOpcode() == Opcodes.RETURN;
        failed += check("Zombie.process guard 序列在方法最前", zGuard);
        int superIdx = -1, guardEnd = zp.instructions.indexOf(zh[3]);
        int superCount = 0, setterCount = 0;
        for (AbstractInsnNode in : zp.instructions) {
            if (in instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == Opcodes.INVOKESPECIAL
                        && mi.owner.equals("zombie/network/fields/hit/Character") && mi.name.equals("process")) {
                    superCount++;
                    superIdx = zp.instructions.indexOf(mi);
                }
                if (mi.owner.equals("zombie/characters/IsoZombie") && mi.name.startsWith("set")) {
                    setterCount++;
                }
            }
        }
        failed += check("super.process 恰一次且在 guard 之後", superCount == 1 && superIdx > guardEnd);
        failed += check("IsoZombie setter 恰 9 個（未增減）", setterCount == 9);

        MethodNode fp = method(distJava, "zombie/network/fields/hit/Fall",
                "process", "(Lzombie/characters/IsoGameCharacter;)V");
        AbstractInsnNode[] fh = firstReal(fp, 3);
        boolean fGuard = fh[0] instanceof VarInsnNode fv && fv.getOpcode() == Opcodes.ALOAD && fv.var == 1
                && fh[1] instanceof JumpInsnNode fj && fj.getOpcode() == Opcodes.IFNONNULL
                && fh[2].getOpcode() == Opcodes.RETURN;
        failed += check("Fall.process guard 序列在方法最前", fGuard);

        // 安全屋 patch 已停用（只留原版地圖，觸發條件消失）——確認確實沒出貨。
        // helper 仍保留在 LogFilter 並持續驗證，恢復時只需解除 PatchConfig 的註解。
        failed += check("SafehouseClaimPacket 未出貨（安全屋 patch 已停用）",
                !Files.exists(distJava.resolve("zombie/network/packets/safehouse/SafehouseClaimPacket.class")));

        MethodNode repair = method(distJava, "zombie/mdc/LogFilter", "getBuilding",
                "(Lzombie/iso/IsoGridSquare;)Lzombie/iso/areas/IsoBuilding;");
        failed += check("安全屋修復會補 roomId 並重新讀 building",
                countCalls(repair, "zombie/iso/IsoGridSquare", "setRoomID") == 2
                && countCalls(repair, "zombie/iso/IsoGridSquare", "getBuilding") >= 2);

        MethodNode loot = method(distJava, "zombie/LootRespawn", "respawnInChunk", "(Lzombie/iso/IsoChunk;)V");
        failed += check("LootRespawn zone gate 只改道一次",
                countCalls(loot, "zombie/mdc/LogFilter", "getLootRespawnZone") == 1
                && countCalls(loot, "zombie/iso/IsoGridSquare", "getZone") == 0);
        failed += check("LootRespawn container filter 只改道一次",
                countCalls(loot, "zombie/mdc/LogFilter", "getLootRespawnContainerCount") == 1
                && countCalls(loot, "zombie/iso/IsoObject", "getContainerCount") == 0);
        failed += check("安全屋仍由原版每次動態判斷",
                countCalls(loot, "zombie/iso/areas/SafeHouse", "getSafeHouse") == 1);

        MethodNode containerFilter = method(distJava, "zombie/mdc/LogFilter", "getLootRespawnContainerCount",
                "(Lzombie/iso/IsoObject;)I");
        failed += check("容器 filter 同時檢查 moved flag 並保留原 count",
                countCalls(containerFilter, "zombie/iso/IsoObject", "isMovedThumpable") == 1
                && countCalls(containerFilter, "zombie/iso/IsoObject", "getContainerCount") == 1);

        // 42.20.2 官方收編：popman buffer 隔離與 clamp 斷言隨 patch 退役（readByteBuffer 官方隔離）。

        // ---- 常數手術的語境鎖（回歸測試：命中數守門只數數量，擋不住改到同方法的另一條算式）----
        // 42.20 實例：壓力算式從 changeStress(radius / 20.0F) 改寫成 changeStress(radius * 0.05F)，
        // 同時新增 fleeDistance = radius * 3.0F + 20.0F——respondToSound 內仍剛好有一個 20.0f，
        // 舊的 ConstChange(20.0f, 60.0f) 會通過 expectedHits==1 卻改到野生動物逃跑距離。
        String animal = "zombie/characters/animals/IsoAnimal";
        MethodNode sound = method(distJava, animal, "respondToSound", "()V");
        failed += check("聲音壓力常數落在 FMUL→changeStress 這條路徑",
                countConstContext(sound, 1.0f / 60.0f, Opcodes.FMUL, animal, "changeStress", 1) == 1);
        failed += check("原乘數 0.05f 已不存在（未半改）",
                countConstThen(sound, 0.05f, -1) == 0);
        failed += check("逃跑距離的 20.0f→FADD 未被動（假陽性負對照）",
                countConstThen(sound, 20.0f, Opcodes.FADD) == 1);

        MethodNode stress = method(distJava, animal, "updateStress", "()V");
        failed += check("閒置衰減常數落在 FDIV→FNEG→changeStress 這條路徑",
                countConstContext(stress, 2750.0f, Opcodes.FDIV, animal, "changeStress", 2) == 1);
        failed += check("原除數 5500.0f 已不存在（未半改）",
                countConstThen(stress, 5500.0f, -1) == 0);

        MethodNode killed = method(distJava, animal, "killed", "(Lzombie/characters/IsoPlayer;)V");
        String rand = "zombie/core/random/Rand";
        failed += check("屠宰連鎖上限落在 Rand.Next 的上界",
                countConstContext(killed, 15.0f, -1, rand, "Next", 1) == 1);
        failed += check("Rand.Next 下界 10.0f 保留、DistToProper 的 10.0f 未被誤中",
                countConstContext(killed, 10.0f, -1, rand, "Next", 1) == 1
                && countConstThen(killed, 10.0f, -1) == 2
                && countConstThen(killed, 30.0f, -1) == 0);

        // 42.20：consistency log 的新家是 interface default method；anticheat 的 warn 留在
        // PacketTypes$PacketType.onServerPacket，該 class 已不在 manifest，完全不經我方程式碼。
        MethodNode inconsistent = method(distJava, "zombie/network/packets/INetworkPacket",
                "logInconsistentPacket", "(Lzombie/network/IConnection;Lzombie/network/PacketTypes$PacketType;)V");
        failed += check("consistency log 改道恰一次且原 warn 歸零",
                countExactCalls(inconsistent, Opcodes.INVOKESTATIC, "zombie/mdc/LogFilter", "warnFmt",
                        "(Lzombie/debug/DebugType;Ljava/lang/String;[Ljava/lang/Object;)V") == 1
                && countExactCalls(inconsistent, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType", "warn",
                        "(Ljava/lang/String;[Ljava/lang/Object;)V") == 0);
        failed += check("未把 PacketTypes$PacketType 一起出貨（anticheat warn 保持原版路徑）",
                !Files.exists(distJava.resolve("zombie/network/PacketTypes$PacketType.class")));

        String loginDesc =
                "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V";
        String database = "zombie/network/ServerWorldDatabase";
        String metrics = "zombie/network/MinidoracatLoginMetrics";
        String twoStringsVoid = "(Ljava/lang/String;Ljava/lang/String;)V";
        String twoStringsResult = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        MethodNode login = method(distJava, "zombie/network/packets/connection/LoginPacket",
                "processServer", loginDesc);
        failed += check("登入三個 DB 呼叫逐點改道且原呼叫歸零",
                countExactCalls(login, Opcodes.INVOKESTATIC, metrics, "setPassword",
                        "(Lzombie/network/ServerWorldDatabase;Ljava/lang/String;Ljava/lang/String;)V") == 1
                && countExactCalls(login, Opcodes.INVOKESTATIC, metrics, "updateLastConnectionDate",
                        "(Lzombie/network/ServerWorldDatabase;Ljava/lang/String;Ljava/lang/String;)V") == 1
                && countExactCalls(login, Opcodes.INVOKESTATIC, metrics, "setUserSteamID",
                        "(Lzombie/network/ServerWorldDatabase;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") == 1
                && countExactCalls(login, Opcodes.INVOKEVIRTUAL, database, "setPassword", twoStringsVoid) == 0
                && countExactCalls(login, Opcodes.INVOKEVIRTUAL, database,
                        "updateLastConnectionDate", twoStringsVoid) == 0
                && countExactCalls(login, Opcodes.INVOKEVIRTUAL, database,
                        "setUserSteamID", twoStringsResult) == 0);

        MethodInsnNode steamIDCall = findExactCall(login, Opcodes.INVOKESTATIC, metrics, "setUserSteamID",
                "(Lzombie/network/ServerWorldDatabase;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        AbstractInsnNode afterSteamIDCall = steamIDCall == null ? null : nextReal(steamIDCall);
        failed += check("setUserSteamID 改道後仍保留原 POP stack effect",
                afterSteamIDCall != null && afterSteamIDCall.getOpcode() == Opcodes.POP);

        MethodNode metricPassword = method(distJava, metrics, "setPassword",
                "(Lzombie/network/ServerWorldDatabase;Ljava/lang/String;Ljava/lang/String;)V");
        MethodNode metricUpdate = method(distJava, metrics, "updateLastConnectionDate",
                "(Lzombie/network/ServerWorldDatabase;Ljava/lang/String;Ljava/lang/String;)V");
        MethodNode metricSteam = method(distJava, metrics, "setUserSteamID",
                "(Lzombie/network/ServerWorldDatabase;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        failed += check("LoginMetrics 三個 wrapper 各 delegate exactly once",
                countExactCalls(metricPassword, Opcodes.INVOKEVIRTUAL,
                        database, "setPassword", twoStringsVoid) == 1
                && countExactCalls(metricUpdate, Opcodes.INVOKEVIRTUAL,
                        database, "updateLastConnectionDate", twoStringsVoid) == 1
                && countExactCalls(metricSteam, Opcodes.INVOKEVIRTUAL,
                        database, "setUserSteamID", twoStringsResult) == 1);
        failed += check("LoginMetrics 每個非 fatal outcome 至多一個 metrics sink",
                countExactCalls(metricPassword, Opcodes.INVOKESTATIC,
                        metrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && countExactCalls(metricUpdate, Opcodes.INVOKESTATIC,
                        metrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && countExactCalls(metricSteam, Opcodes.INVOKESTATIC,
                        metrics, "safeLog", "(Ljava/lang/String;J)V") == 1);
        failed += check("LoginMetrics checked exception 只保留 setPassword SQLException",
                metricPassword.exceptions != null
                && metricPassword.exceptions.size() == 1
                && metricPassword.exceptions.contains("java/sql/SQLException")
                && (metricUpdate.exceptions == null || metricUpdate.exceptions.isEmpty())
                && (metricSteam.exceptions == null || metricSteam.exceptions.isEmpty()));

        MethodNode safeLog = method(distJava, metrics, "safeLog", "(Ljava/lang/String;J)V");
        failed += check("LoginMetrics 只使用既有 Multiplayer log sink",
                countFieldReads(safeLog, "zombie/debug/DebugType", "Multiplayer") == 1
                && countExactCalls(safeLog, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType",
                        "println", "(Ljava/lang/String;)V") == 1);

        // ---- join 卡頓量測：四個重活逐點改道且原呼叫歸零、wrapper delegate exactly once ----
        String joinMetrics = "zombie/network/MinidoracatJoinMetrics";
        String createPacket = "zombie/network/packets/character/CreatePlayerPacket";
        String luaEvents = "zombie/Lua/LuaEventManager";
        String playerDb = "zombie/savefile/ServerPlayerDB";
        String triggerDesc = "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V";
        String updateCharDesc = "(Lzombie/characters/IsoPlayer;ILzombie/core/raknet/UdpConnection;)V";
        String writeDesc = "(Lzombie/core/network/ByteBufferWriter;)V";
        MethodNode createProcess = method(distJava, createPacket, "processServer",
                "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V");
        failed += check("join 四個重活逐點改道且原呼叫歸零",
                countExactCalls(createProcess, Opcodes.INVOKESTATIC, joinMetrics, "triggerEvent", triggerDesc) == 1
                && countExactCalls(createProcess, Opcodes.INVOKESTATIC, joinMetrics, "serverUpdateNetworkCharacter",
                        "(Lzombie/savefile/ServerPlayerDB;Lzombie/characters/IsoPlayer;ILzombie/core/raknet/UdpConnection;)V") == 1
                && countExactCalls(createProcess, Opcodes.INVOKESTATIC, joinMetrics, "process",
                        "(Lzombie/savefile/ServerPlayerDB;)V") == 1
                && countExactCalls(createProcess, Opcodes.INVOKESTATIC, joinMetrics, "write",
                        "(Lzombie/network/packets/character/CreatePlayerPacket;Lzombie/core/network/ByteBufferWriter;)V") == 1
                && countExactCalls(createProcess, Opcodes.INVOKESTATIC, luaEvents, "triggerEvent", triggerDesc) == 0
                && countExactCalls(createProcess, Opcodes.INVOKEVIRTUAL, playerDb,
                        "serverUpdateNetworkCharacter", updateCharDesc) == 0
                && countExactCalls(createProcess, Opcodes.INVOKEVIRTUAL, playerDb, "process", "()V") == 0
                && countExactCalls(createProcess, Opcodes.INVOKEVIRTUAL, createPacket, "write", writeDesc) == 0);

        MethodNode jmTrigger = method(distJava, joinMetrics, "triggerEvent", triggerDesc);
        MethodNode jmUpdate = method(distJava, joinMetrics, "serverUpdateNetworkCharacter",
                "(Lzombie/savefile/ServerPlayerDB;Lzombie/characters/IsoPlayer;ILzombie/core/raknet/UdpConnection;)V");
        MethodNode jmProcess = method(distJava, joinMetrics, "process", "(Lzombie/savefile/ServerPlayerDB;)V");
        MethodNode jmWrite = method(distJava, joinMetrics, "write",
                "(Lzombie/network/packets/character/CreatePlayerPacket;Lzombie/core/network/ByteBufferWriter;)V");
        failed += check("JoinMetrics 四個 wrapper 各 delegate exactly once",
                countExactCalls(jmTrigger, Opcodes.INVOKESTATIC, luaEvents, "triggerEvent", triggerDesc) == 1
                && countExactCalls(jmUpdate, Opcodes.INVOKEVIRTUAL, playerDb,
                        "serverUpdateNetworkCharacter", updateCharDesc) == 1
                && countExactCalls(jmProcess, Opcodes.INVOKEVIRTUAL, playerDb, "process", "()V") == 1
                && countExactCalls(jmWrite, Opcodes.INVOKEVIRTUAL, createPacket, "write", writeDesc) == 1);
        failed += check("JoinMetrics 每個 wrapper 至多一個 metrics sink、無 checked exception",
                countExactCalls(jmTrigger, Opcodes.INVOKESTATIC, joinMetrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && countExactCalls(jmUpdate, Opcodes.INVOKESTATIC, joinMetrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && countExactCalls(jmProcess, Opcodes.INVOKESTATIC, joinMetrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && countExactCalls(jmWrite, Opcodes.INVOKESTATIC, joinMetrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && (jmTrigger.exceptions == null || jmTrigger.exceptions.isEmpty())
                && (jmUpdate.exceptions == null || jmUpdate.exceptions.isEmpty())
                && (jmProcess.exceptions == null || jmProcess.exceptions.isEmpty())
                && (jmWrite.exceptions == null || jmWrite.exceptions.isEmpty()));

        MethodNode jmSafeLog = method(distJava, joinMetrics, "safeLog", "(Ljava/lang/String;J)V");
        failed += check("JoinMetrics 只使用既有 Multiplayer log sink",
                countFieldReads(jmSafeLog, "zombie/debug/DebugType", "Multiplayer") == 1
                && countExactCalls(jmSafeLog, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType",
                        "println", "(Ljava/lang/String;)V") == 1);

        // ---- 一般重連（既有角色）量測：REJOIN_TOTAL 兩個呼叫點＋REJOIN_LOAD_CHARACTER ----
        String rpcDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;Ljava/lang/String;)V";
        String loadCharDesc = "(ILjava/lang/String;)Lzombie/characters/IsoPlayer;";
        String parseDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        MethodNode connectParse = method(distJava, "zombie/network/packets/connection/ConnectPacket",
                "parse", parseDesc);
        MethodNode connectCoopParse = method(distJava, "zombie/network/packets/connection/ConnectCoopPacket",
                "parse", parseDesc);
        failed += check("REJOIN_TOTAL 兩個呼叫點各改道一次且原呼叫歸零",
                countExactCalls(connectParse, Opcodes.INVOKESTATIC, joinMetrics, "receivePlayerConnect", rpcDesc) == 1
                && countExactCalls(connectParse, Opcodes.INVOKESTATIC, "zombie/network/GameServer",
                        "receivePlayerConnect", rpcDesc) == 0
                && countExactCalls(connectCoopParse, Opcodes.INVOKESTATIC, joinMetrics, "receivePlayerConnect", rpcDesc) == 1
                && countExactCalls(connectCoopParse, Opcodes.INVOKESTATIC, "zombie/network/GameServer",
                        "receivePlayerConnect", rpcDesc) == 0);
        MethodNode gsReceive = method(distJava, "zombie/network/GameServer", "receivePlayerConnect", rpcDesc);
        failed += check("REJOIN_LOAD_CHARACTER 兩個 if/else 呼叫點改道且原呼叫歸零",
                countExactCalls(gsReceive, Opcodes.INVOKESTATIC, joinMetrics, "serverLoadNetworkCharacter",
                        "(Lzombie/savefile/ServerPlayerDB;ILjava/lang/String;)Lzombie/characters/IsoPlayer;") == 2
                && countExactCalls(gsReceive, Opcodes.INVOKEVIRTUAL, playerDb,
                        "serverLoadNetworkCharacter", loadCharDesc) == 0);
        MethodNode jmRpc = method(distJava, joinMetrics, "receivePlayerConnect", rpcDesc);
        MethodNode jmLoadChar = method(distJava, joinMetrics, "serverLoadNetworkCharacter",
                "(Lzombie/savefile/ServerPlayerDB;ILjava/lang/String;)Lzombie/characters/IsoPlayer;");
        failed += check("重連兩個 wrapper 各 delegate exactly once、單一 sink、無 checked exception",
                countExactCalls(jmRpc, Opcodes.INVOKESTATIC, "zombie/network/GameServer",
                        "receivePlayerConnect", rpcDesc) == 1
                && countExactCalls(jmLoadChar, Opcodes.INVOKEVIRTUAL, playerDb,
                        "serverLoadNetworkCharacter", loadCharDesc) == 1
                && countExactCalls(jmRpc, Opcodes.INVOKESTATIC, joinMetrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && countExactCalls(jmLoadChar, Opcodes.INVOKESTATIC, joinMetrics, "safeLog", "(Ljava/lang/String;J)V") == 1
                && (jmRpc.exceptions == null || jmRpc.exceptions.isEmpty())
                && (jmLoadChar.exceptions == null || jmLoadChar.exceptions.isEmpty()));

        // 回傳契約結構鎖（codex 對抗審查發現）：delegate 結果必須 astore→尾端 aload 同 slot→areturn，
        // 且全方法恰一個 areturn——wrapper 誤改成永遠回傳 null（全部重連被 kick）時建置失敗
        MethodInsnNode loadCharDelegate = findExactCall(jmLoadChar, Opcodes.INVOKEVIRTUAL, playerDb,
                "serverLoadNetworkCharacter", loadCharDesc);
        boolean returnContract = false;
        if (loadCharDelegate != null) {
            AbstractInsnNode afterDelegate = nextReal(loadCharDelegate);
            AbstractInsnNode lastReal = null;
            int areturns = 0;
            for (AbstractInsnNode in : jmLoadChar.instructions) {
                if (in.getOpcode() >= 0) {
                    lastReal = in;
                }
                if (in.getOpcode() == Opcodes.ARETURN) {
                    areturns++;
                }
            }
            returnContract = afterDelegate instanceof VarInsnNode st && st.getOpcode() == Opcodes.ASTORE
                    && areturns == 1
                    && lastReal != null && lastReal.getOpcode() == Opcodes.ARETURN
                    && prevReal(lastReal) instanceof VarInsnNode ld && ld.getOpcode() == Opcodes.ALOAD
                    && ld.var == st.var;
        }
        failed += check("REJOIN_LOAD_CHARACTER 回傳 identity 結構鎖（astore→aload 同 slot→唯一 areturn）",
                returnContract);

        String array = "zombie/entity/util/Array";
        String fastRemoval = "zombie/mdc/FastIdentityArrayRemoval";
        String addDesc = "(Ljava/lang/Object;)V";
        String indexedAddDesc = "(Lzombie/entity/util/Array;Ljava/lang/Object;)V";
        String removeDesc = "(Ljava/lang/Object;Z)Z";
        String indexedRemoveDesc = "(Lzombie/entity/util/Array;Ljava/lang/Object;Z)Z";
        MethodNode engineAdd = method(distJava, "zombie/entity/EngineEntityManager",
                "addEntityInternal", "(Lzombie/entity/GameEntity;)V");
        MethodNode engineRemove = method(distJava, "zombie/entity/EngineEntityManager",
                "removeEntityInternal", "(Lzombie/entity/GameEntity;)V");
        MethodNode bucketMembership = method(distJava, "zombie/entity/EntityBucket",
                "updateMembership", "(Lzombie/entity/GameEntity;)V");
        failed += check("EngineEntityManager add/remove 各只改道一次且原呼叫歸零",
                countExactCalls(engineAdd, Opcodes.INVOKESTATIC,
                        fastRemoval, "add", indexedAddDesc) == 1
                && countExactCalls(engineAdd, Opcodes.INVOKEVIRTUAL,
                        array, "add", addDesc) == 0
                && countExactCalls(engineRemove, Opcodes.INVOKESTATIC,
                        fastRemoval, "remove", indexedRemoveDesc) == 1
                && countExactCalls(engineRemove, Opcodes.INVOKEVIRTUAL,
                        array, "removeValue", removeDesc) == 0);
        failed += check("EntityBucket add/remove 各只改道一次且原呼叫歸零",
                countExactCalls(bucketMembership, Opcodes.INVOKESTATIC,
                        fastRemoval, "add", indexedAddDesc) == 1
                && countExactCalls(bucketMembership, Opcodes.INVOKESTATIC,
                        fastRemoval, "remove", indexedRemoveDesc) == 1
                && countExactCalls(bucketMembership, Opcodes.INVOKEVIRTUAL,
                        array, "add", addDesc) == 0
                && countExactCalls(bucketMembership, Opcodes.INVOKEVIRTUAL,
                        array, "removeValue", removeDesc) == 0);

        ClassNode fastRemovalNode = classNode(distJava, fastRemoval);
        ClassNode stateNode = classNode(distJava, fastRemoval + "$State");
        failed += check("entity helper 不使用 IdentityHashMap",
                !containsUtf8(distJava, fastRemoval, "java/util/IdentityHashMap")
                && !containsUtf8(distJava, fastRemoval + "$State", "java/util/IdentityHashMap"));
        failed += check("entity helper static state 僅 weak registry 或 primitive",
                staticFieldsAreWeakOrPrimitive(fastRemovalNode));
        failed += check("entity State 不持有 Array／GameEntity，欄位僅 primitive Trove",
                stateFieldsArePrimitiveOnly(stateNode)
                && !containsUtf8(distJava, fastRemoval + "$State", "zombie/entity/util/Array")
                && !containsUtf8(distJava, fastRemoval + "$State", "zombie/entity/GameEntity"));
        failed += check("entity helper 未重新引入 setAutoCompactionFactor（2026-08-06 墓碑飽和事故）",
                !containsUtf8(distJava, fastRemoval, "setAutoCompactionFactor")
                && !containsUtf8(distJava, fastRemoval + "$State", "setAutoCompactionFactor"));

        // ---- 效能第一波（載具預篩；VehicleManager 512→256 已於 42.20.2 退役）----
        String prefilterCls = "zombie/mdc/VehicleIntersectPrefilter";
        String intersectDesc = "(Lorg/joml/Vector3f;Lorg/joml/Vector3f;Lorg/joml/Vector3f;)Lorg/joml/Vector3f;";
        MethodNode zvb = method(distJava, "zombie/characters/IsoZombie", "isVehicleBetween", "(FFF)Z");
        failed += check("載具預篩改道恰一次且原呼叫歸零",
                countExactCalls(zvb, Opcodes.INVOKESTATIC, prefilterCls, "getIntersectPoint",
                        "(Lzombie/vehicles/BaseVehicle;" + intersectDesc.substring(1)) == 1
                && countExactCalls(zvb, Opcodes.INVOKEVIRTUAL, "zombie/vehicles/BaseVehicle",
                        "getIntersectPoint", intersectDesc) == 0);
        ClassNode prefilterNode = classNode(distJava, prefilterCls);
        failed += check("預篩 helper static 欄位僅 primitive（無快取、無強參照）",
                prefilterNode.fields.stream().allMatch(
                        f -> f.desc.length() == 1 && "ZBCSIJFD".contains(f.desc)));

        // 42.20.2 官方收編：connected[512] 已刪除改 per-connection HashMap，512→256 斷言退役。

        // ---- 假死修復（removeGlassAttachments 無限迴圈保險絲）----
        String glassGuard = "zombie/mdc/GlassAttachmentGuard";
        MethodNode smashW = method(distJava, "zombie/iso/objects/IsoWindow", "smashWindow", "(ZZ)V");
        failed += check("玻璃附掛清除改道恰一次且原呼叫歸零",
                countExactCalls(smashW, Opcodes.INVOKESTATIC, glassGuard, "removeGlassAttachments",
                        "(Lzombie/iso/IsoGridSquare;Lzombie/iso/objects/IsoWindow;)V") == 1
                && countExactCalls(smashW, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoGridSquare",
                        "removeGlassAttachments", "(Lzombie/iso/objects/IsoWindow;)V") == 0);
        ClassNode glassNode = classNode(distJava, glassGuard);
        failed += check("GlassGuard 無狀態（零欄位）且含定位 log 前綴",
                glassNode.fields.isEmpty()
                && containsUtf8(distJava, glassGuard, "[MinidoracatJavaPatch][GlassGuard]"));

        // 42.20.2 官方收編：P5 全家族 15 站結構斷言隨 patch 退役（官方伴生 Set 原生 O(1)）。


        // 2026-08-08 受精蛋豁免退役：IsoGridSquare.load 的改道與 13 條結構／行為斷言隨 patch
        // 一併移除，IsoGridSquare 回歸原版（server 與 client 行為一致）。詳見 patches.md 2n。


        // ---- W3 效能第三波結構斷言 ----
        String nzmCls = "zombie/popman/NetworkZombieManager";
        String throttleCls = "zombie/mdc/ZombieAuthThrottle";
        MethodNode pkAuth = method(distJava, "zombie/popman/NetworkZombiePacker", "updateAuth", "()V");
        failed += check("W3-1 packer.updateAuth：改道 x1、原呼叫歸零",
                countExactCalls(pkAuth, Opcodes.INVOKESTATIC, throttleCls, "updateAuth",
                        "(L" + nzmCls + ";Lzombie/characters/IsoZombie;)V") == 1
                && countExactCalls(pkAuth, Opcodes.INVOKEVIRTUAL, nzmCls, "updateAuth",
                        "(Lzombie/characters/IsoZombie;)V") == 0);
        // NetworkZombieManager 本就因第一波抑噪 patch 在修補輸出——負對照改為斷言其內部
        // （含 updateAuth 本體與 clearTargetAuth 斷線清理路徑）零 throttle 改道
        ClassNode nzmNode = classNode(distJava, nzmCls);
        boolean nzmClean = nzmNode.methods.stream().allMatch(m ->
                countExactCalls(m, Opcodes.INVOKESTATIC, throttleCls, "updateAuth",
                        "(L" + nzmCls + ";Lzombie/characters/IsoZombie;)V") == 0);
        failed += check("W3-1 負對照：NetworkZombieManager（抑噪 patch 對象）內零 throttle 改道", nzmClean);
        MethodNode ctAuth = methodFromJar(jar, nzmCls, "clearTargetAuth",
                "(Lzombie/network/IConnection;Lzombie/characters/IsoPlayer;)V");
        failed += check("W3-1 前提：clearTargetAuth 確有自身 updateAuth(IsoZombie) 備援呼叫（vanilla 斷線清理）",
                countExactCalls(ctAuth, Opcodes.INVOKEVIRTUAL, nzmCls, "updateAuth",
                        "(Lzombie/characters/IsoZombie;)V") >= 1);

        String behavCls = "zombie/characters/animals/behavior/BaseAnimalBehavior";
        String spotDesc = "(Lzombie/iso/IsoMovingObject;ZF)V";
        String spotPreCls = "zombie/characters/animals/behavior/AnimalSpottedPrefilter";
        String spotPreDesc = "(L" + behavCls + ";Lzombie/iso/IsoMovingObject;ZF)V";
        MethodNode uLos = method(distJava, "zombie/characters/animals/IsoAnimal", "updateLOS", "()V");
        failed += check("W3-3 updateLOS：改道 x2（殭屍＋玩家分支）、原呼叫歸零",
                countExactCalls(uLos, Opcodes.INVOKESTATIC, spotPreCls, "spotted", spotPreDesc) == 2
                && countExactCalls(uLos, Opcodes.INVOKEVIRTUAL, behavCls, "spotted", spotDesc) == 0);
        MethodNode fwd = method(distJava, "zombie/characters/animals/IsoAnimal", "spotted", spotDesc);
        failed += check("W3-3 負對照：IsoAnimal.spotted 轉發方法保持 vanilla（TestAnimalSpotPlayer 路徑）",
                countExactCalls(fwd, Opcodes.INVOKEVIRTUAL, behavCls, "spotted", spotDesc) == 1
                && countExactCalls(fwd, Opcodes.INVOKESTATIC, spotPreCls, "spotted", spotPreDesc) == 0);
        MethodNode vSpotted = methodFromJar(jar, behavCls, "spotted", spotDesc);
        failed += check("W3-3 前綴指紋：無條件前綴（spottedChr＋lastAlerted x2＋GameTime x2）與重放版同構",
                checkSpottedPrefix(vSpotted));
        failed += check("W3-3 常數包絡：spotted() float 常數集與 42.20 快照一致（漂移即重新分析）",
                checkSpottedConstEnvelope(vSpotted));
        failed += checkAnimalBehaviorDomain(jar);

        String vehCls = "zombie/vehicles/BaseVehicle";
        MethodNode vUpd = method(distJava, vehCls, "update", "()V");
        failed += check("W3-4 update：改道 x1、原呼叫歸零",
                countExactCalls(vUpd, Opcodes.INVOKESTATIC, "zombie/mdc/VehicleCouldSeeGate",
                        "couldSeeIntersectedSquare", "(L" + vehCls + ";I)Z") == 1
                && countExactCalls(vUpd, Opcodes.INVOKEVIRTUAL, vehCls, "couldSeeIntersectedSquare", "(I)Z") == 0);
        MethodNode vRender = method(distJava, vehCls, "render",
                "(FFFLzombie/core/textures/ColorInfo;ZZLzombie/core/opengl/Shader;)V");
        failed += check("W3-4 負對照：render() 的同名 callsite 保持 vanilla",
                countExactCalls(vRender, Opcodes.INVOKEVIRTUAL, vehCls, "couldSeeIntersectedSquare", "(I)Z") == 1);
        failed += check("W3-4 no-op 鏈結：setTargetAlpha 頭部 server guard 指紋",
                checkServerGuardHead(methodFromJar(jar, "zombie/iso/IsoObject", "setTargetAlpha", "(IF)V")));
        failed += check("W3-4 no-op 鏈結：getTargetAlpha server→1.0F 指紋",
                checkGetTargetAlphaGuard(methodFromJar(jar, "zombie/iso/IsoObject", "getTargetAlpha", "(I)F")));

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("守衛語意驗證全數通過");
    }

    /**
     * Client patch（TextureIDAssetManager.waitFileTask 門檻＋觀測改道）驗證：
     * vanilla 前提守門（jar 內恰一個 getBytesAllocated＋恰一個 52428800L，PZ 改寫此方法
     * 時建置失敗而非默默錯位）、patched 全序鎖、helper 常數與 bytecode 常數連動、
     * 以及 helper passthrough 對真實 DirectBufferAllocator 水位的行為 smoke。
     */
    static int clientChecks(Path distJava, Path jar) throws Exception {
        int failed = 0;
        String texCls = "zombie/core/textures/TextureIDAssetManager";
        String guardCls = "zombie/mdc/TexturePipelineGuard";
        String dba = "zombie/core/utils/DirectBufferAllocator";

        MethodNode vanillaWait = methodFromJar(jar, texCls, "waitFileTask", "()V");
        failed += check("vanilla 前提：waitFileTask 恰一個 getBytesAllocated 與 52428800L",
                countExactCalls(vanillaWait, Opcodes.INVOKESTATIC, dba, "getBytesAllocated", "()J") == 1
                && countLongConst(vanillaWait, 52428800L) == 1
                && countLongConst(vanillaWait, 4294967296L) == 0);

        MethodNode wait = method(distJava, texCls, "waitFileTask", "()V");
        failed += check("觀測改道恰一次且原 getBytesAllocated 歸零",
                countExactCalls(wait, Opcodes.INVOKESTATIC, guardCls, "bytesAllocatedObserved", "()J") == 1
                && countExactCalls(wait, Opcodes.INVOKESTATIC, dba, "getBytesAllocated", "()J") == 0);
        failed += check("門檻常數已改 4GB 且 50MB 歸零",
                countLongConst(wait, 4294967296L) == 1 && countLongConst(wait, 52428800L) == 0);

        AbstractInsnNode[] w = firstReal(wait, 4);
        boolean seq = w[0] instanceof MethodInsnNode m0 && m0.getOpcode() == Opcodes.INVOKESTATIC
                && m0.owner.equals(guardCls) && m0.name.equals("bytesAllocatedObserved") && m0.desc.equals("()J")
                && w[1] instanceof LdcInsnNode l1 && l1.cst instanceof Long lv && lv == 4294967296L
                && w[2] != null && w[2].getOpcode() == Opcodes.LCMP
                && w[3] != null && w[3].getOpcode() == Opcodes.IFLE;
        failed += check("waitFileTask 全序鎖（observed→4GB→lcmp→ifle）", seq);
        failed += check("sleep(20) 迴圈保留",
                countExactCalls(wait, Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V") == 1
                && countLongConst(wait, 20L) == 1);
        failed += check("InterruptedException handler 區間保留（vanilla 與 patched 各恰一個）",
                vanillaWait.tryCatchBlocks != null && vanillaWait.tryCatchBlocks.size() == 1
                && "java/lang/InterruptedException".equals(vanillaWait.tryCatchBlocks.get(0).type)
                && wait.tryCatchBlocks != null && wait.tryCatchBlocks.size() == 1
                && "java/lang/InterruptedException".equals(wait.tryCatchBlocks.get(0).type));

        try (URLClassLoader patched = new URLClassLoader(
                new URL[]{ distJava.toUri().toURL(), jar.toUri().toURL() },
                ClassLoader.getPlatformClassLoader())) {
            Class<?> guard = Class.forName("zombie.mdc.TexturePipelineGuard", true, patched);
            failed += check("helper 門檻常數與 bytecode 常數連動（50MB/4GB）",
                    guard.getDeclaredField("VANILLA_LIMIT_BYTES").getLong(null) == 52428800L
                    && guard.getDeclaredField("PATCHED_LIMIT_BYTES").getLong(null) == 4294967296L);

            Class<?> alloc = Class.forName("zombie.core.utils.DirectBufferAllocator", true, patched);
            Method observed = guard.getMethod("bytesAllocatedObserved");
            Method direct = alloc.getMethod("getBytesAllocated");
            long before = (Long)observed.invoke(null);
            Object wrapped = alloc.getMethod("allocate", int.class).invoke(null, 1024 * 1024);
            long during = (Long)observed.invoke(null);
            long directDuring = (Long)direct.invoke(null);
            wrapped.getClass().getMethod("dispose").invoke(wrapped);
            long after = (Long)observed.invoke(null);
            failed += check("helper passthrough 與真實水位一致（allocate 1MB → dispose 歸零）",
                    before == 0L && during == directDuring && during >= 1024 * 1024 && after == 0L);
        }

        // ---- v2.0 貼圖洩漏根治：vanilla 前提守門＋head-call 全序＋avatar redirect ----
        String leakGuard = "zombie/core/textures/MinidoracatTextureLeakGuard";
        String imgCls = "zombie/core/textures/ImageData";
        String tidCls = "zombie/core/textures/TextureID";
        String imgHelperDesc = "(Lzombie/core/textures/ImageData;)V";
        String avatarDesc = "(J)Lzombie/core/textures/ImageData;";

        MethodNode vDispose = methodFromJar(jar, imgCls, "dispose", "()V");
        MethodNode vGetData = methodFromJar(jar, imgCls, "getData", "()Lzombie/core/textures/MipMapLevel;");
        MethodNode vFree = methodFromJar(jar, tidCls, "freeMemory", "()V");
        MethodNode vAvatar = methodFromJar(jar, tidCls, "createSteamAvatar",
                "(J)Lzombie/core/textures/TextureID;");
        failed += check("vanilla 前提：dispose 未觸碰 frames（TIS 未自行修復）",
                countFieldTouches(vDispose, imgCls, "frames") == 0);
        failed += check("vanilla 前提：getData 含固定 64MB 配置、freeMemory 僅斷引用、avatar 呼叫恰一",
                countIntConst(vGetData, 67108864) == 1
                && countFieldTouches(vFree, tidCls, "data") == 1
                && countExactCalls(vFree, Opcodes.INVOKEVIRTUAL,
                        "zombie/core/textures/ImageData", "dispose", "()V") == 0
                && countExactCalls(vAvatar, Opcodes.INVOKESTATIC, imgCls,
                        "createSteamAvatar", avatarDesc) == 1);

        MethodNode pDispose = method(distJava, imgCls, "dispose", "()V");
        MethodNode pGetData = method(distJava, imgCls, "getData", "()Lzombie/core/textures/MipMapLevel;");
        MethodNode pMipCount = method(distJava, imgCls, "getMipMapCount", "()I");
        MethodNode pFree = method(distJava, tidCls, "freeMemory", "()V");
        MethodNode pAvatar = method(distJava, tidCls, "createSteamAvatar",
                "(J)Lzombie/core/textures/TextureID;");
        failed += check("dispose/getData/getMipMapCount/freeMemory 四個 head-call 全序（aload_0→helper 恰一次）",
                headCallOk(pDispose, leakGuard, "disposeFrames", imgHelperDesc)
                && headCallOk(pGetData, leakGuard, "ensureData", imgHelperDesc)
                && headCallOk(pMipCount, leakGuard, "ensureData", imgHelperDesc)
                && headCallOk(pFree, leakGuard, "onFreeMemory", "(Lzombie/core/textures/TextureID;)V"));
        failed += check("avatar redirect 恰一次且原呼叫歸零",
                countExactCalls(pAvatar, Opcodes.INVOKESTATIC, leakGuard,
                        "createSteamAvatarFixed", avatarDesc) == 1
                && countExactCalls(pAvatar, Opcodes.INVOKESTATIC, imgCls,
                        "createSteamAvatar", avatarDesc) == 0);
        failed += check("dispose 原體保留（MipMapLevel.dispose 呼叫數未變＝head-call 未破壞原邏輯）",
                countExactCalls(pDispose, Opcodes.INVOKEVIRTUAL,
                        "zombie/core/textures/MipMapLevel", "dispose", "()V")
                == countExactCalls(vDispose, Opcodes.INVOKEVIRTUAL,
                        "zombie/core/textures/MipMapLevel", "dispose", "()V"));
        return failed;
    }

    /** head-call 全序鎖：方法首兩條真指令＝aload_0；invokestatic helper，且該 helper 呼叫全方法恰一次。 */
    static boolean headCallOk(MethodNode m, String owner, String name, String desc) {
        AbstractInsnNode[] h = firstReal(m, 2);
        return h[0] instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0
                && h[1] instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)
                && countExactCalls(m, Opcodes.INVOKESTATIC, owner, name, desc) == 1;
    }

    /** 統計方法內對指定欄位的任何存取（GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC）。 */
    static int countFieldTouches(MethodNode m, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof FieldInsnNode fi && fi.owner.equals(owner) && fi.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    /** 統計方法內 LDC 的指定 int 常數出現次數。 */
    static int countIntConst(MethodNode m, int value) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i && i == value) {
                count++;
            }
        }
        return count;
    }

    /** 回傳 true=拋了 NPE、false=正常返回；其他例外直接失敗拋出。 */
    static boolean invokeProcess(ClassLoader cl, String cls, boolean withArg) throws Exception {
        Class<?> c = Class.forName(cls, true, cl);
        Object o = c.getDeclaredConstructor().newInstance();
        if (withArg) {
            // Fall 座標為 0 時會在碰 character 前短路 return——設非零強制走到解參照路徑，
            // 負對照（原版必 NPE）才成立
            for (String f : new String[]{ "dropPositionX", "dropPositionY" }) {
                var fld = c.getDeclaredField(f);
                fld.setAccessible(true);
                fld.setFloat(o, 1.0f);
            }
        }
        Method m = withArg
                ? c.getMethod("process", Class.forName("zombie.characters.IsoGameCharacter", false, cl))
                : c.getMethod("process");
        try {
            if (withArg) {
                m.invoke(o, new Object[]{ null });
            } else {
                m.invoke(o);
            }
            return false;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof NullPointerException) {
                return true;
            }
            throw e;
        }
    }

    static int expect(String what, boolean gotNpe, boolean wantNpe) {
        boolean ok = gotNpe == wantNpe;
        System.out.println((ok ? "smoke OK   " : "smoke FAIL ") + what);
        return ok ? 0 : 1;
    }

    static int invokeLootContainerCount(ClassLoader cl, boolean moved) throws Exception {
        Class<?> objectClass = Class.forName("zombie.iso.IsoObject", true, cl);
        Class<?> containerClass = Class.forName("zombie.inventory.ItemContainer", true, cl);
        Object object = objectClass.getDeclaredConstructor().newInstance();
        Object container = containerClass.getDeclaredConstructor().newInstance();
        objectClass.getMethod("setContainer", containerClass).invoke(object, container);
        objectClass.getMethod("setMovedThumpable", boolean.class).invoke(object, moved);
        Class<?> filter = Class.forName("zombie.mdc.LogFilter", true, cl);
        return (Integer)filter.getMethod("getLootRespawnContainerCount", objectClass).invoke(null, object);
    }

    static boolean checkLootZoneFallback(ClassLoader cl) throws Exception {
        Class<?> cellClass = Class.forName("zombie.iso.IsoCell", false, cl);
        Class<?> sliceClass = Class.forName("zombie.iso.SliceY", false, cl);
        Class<?> chunkClass = Class.forName("zombie.iso.IsoChunk", true, cl);
        Class<?> squareClass = Class.forName("zombie.iso.IsoGridSquare", true, cl);
        Class<?> objectClass = Class.forName("zombie.iso.IsoObject", true, cl);
        Class<?> containerClass = Class.forName("zombie.inventory.ItemContainer", true, cl);
        Class<?> zoneClass = Class.forName("zombie.iso.zones.Zone", true, cl);
        Class<?> filter = Class.forName("zombie.mdc.LogFilter", true, cl);

        Object chunk = chunkClass.getConstructor(cellClass).newInstance(new Object[]{ null });
        Object square = squareClass.getConstructor(cellClass, sliceClass, int.class, int.class, int.class)
                .newInstance(null, null, 0, 0, 0);
        squareClass.getField("chunk").set(square, chunk);
        chunkClass.getMethod("setSquare", int.class, int.class, int.class, squareClass)
                .invoke(chunk, 0, 0, 0, square);

        Object object = objectClass.getDeclaredConstructor().newInstance();
        Object container = containerClass.getDeclaredConstructor().newInstance();
        objectClass.getMethod("setContainer", containerClass).invoke(object, container);
        Object objects = squareClass.getMethod("getObjects").invoke(square);
        objects.getClass().getMethod("add", Object.class).invoke(objects, object);

        var zoneCtor = zoneClass.getConstructor(String.class, String.class,
                int.class, int.class, int.class, int.class, int.class);
        Object region = zoneCtor.newInstance("", "Region", 0, 0, 0, 1, 1);
        zoneClass.getField("hourLastSeen").setInt(region, 123);
        squareClass.getField("zone").set(square, region);
        Method effectiveZone = filter.getMethod("getLootRespawnZone", squareClass);

        Object fallback = effectiveZone.invoke(null, square);
        boolean fallbackOk = fallback != region
                && "TownZone".equals(zoneClass.getMethod("getType").invoke(fallback))
                && zoneClass.getField("hourLastSeen").getInt(fallback) == 123
                && !zoneClass.getField("haveConstruction").getBoolean(fallback);

        objectClass.getMethod("setMovedThumpable", boolean.class).invoke(object, true);
        boolean movedBlocked = effectiveZone.invoke(null, square) == region;

        objectClass.getMethod("setMovedThumpable", boolean.class).invoke(object, false);
        Object town = zoneCtor.newInstance("", "TownZone", 0, 0, 0, 1, 1);
        squareClass.getField("zone").set(square, town);
        boolean vanillaPassThrough = effectiveZone.invoke(null, square) == town;
        return fallbackOk && movedBlocked && vanillaPassThrough;
    }


    /**
     * W3-3 前綴指紋（code review MINOR-2 強化版）：GameClient.client 檢查之前，
     * putfield 序列（owner 限定）必須恰為 IsoAnimal.spottedChr → BaseAnimalBehavior.lastAlerted ×2、
     * invoke 僅 GameTime.getInstance/getMultiplier、分支恰為 IFLE→IFGE（守衛方向）
     * ——與 AnimalSpottedPrefilter 的重放版逐句同構。42.21 改前綴（含欄位搬家/守衛翻轉）即建置失敗。
     */
    static boolean checkSpottedPrefix(MethodNode m) {
        java.util.List<String> putfields = new ArrayList<>();
        java.util.List<String> invokes = new ArrayList<>();
        java.util.List<Integer> jumps = new ArrayList<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC) {
                FieldInsnNode fi = (FieldInsnNode) in;
                if (fi.owner.equals("zombie/network/GameClient") && fi.name.equals("client")) {
                    return putfields.equals(java.util.List.of(
                                    "zombie/characters/animals/IsoAnimal.spottedChr",
                                    "zombie/characters/animals/behavior/BaseAnimalBehavior.lastAlerted",
                                    "zombie/characters/animals/behavior/BaseAnimalBehavior.lastAlerted"))
                            && invokes.equals(java.util.List.of("getInstance", "getMultiplier"))
                            && jumps.equals(java.util.List.of(Opcodes.IFLE, Opcodes.IFGE));
                }
            } else if (in.getOpcode() == Opcodes.PUTFIELD) {
                FieldInsnNode fi = (FieldInsnNode) in;
                putfields.add(fi.owner + "." + fi.name);
            } else if (in instanceof MethodInsnNode mi) {
                invokes.add(mi.name);
            } else if (in instanceof JumpInsnNode) {
                jumps.add(in.getOpcode());
            }
        }
        return false;       // 找不到 GameClient.client 檢查＝前綴結構已變
    }

    /**
     * W3-3 常數包絡快照（code review MINOR-1 強化版）：spotted() 內全部 LDC float
     * 依指令順序的有序清單凍結於 42.20——「值在既有集合內互換」（如門檻 10→14）也會被抓。
     */
    static boolean checkSpottedConstEnvelope(MethodNode m) {
        java.util.List<Float> found = new ArrayList<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Float f) {
                found.add(f);
            }
        }
        // 42.20 快照：51 個 LDC float 依指令順序（ASM 全量收集，含負值/科學記號/重複）
        java.util.List<Float> expected = java.util.List.of(
                10.0f, 5.0E-4f, 6.0E-4f, 100.0f, 100.0f, 100.0f, 100.0f, 100.0f, 1000.0f, 80.0f,
                30.0f, 3.0f, 8000.0f, 800.0f, 500000.0f, 0.5f, 0.3f, 0.25f, 0.25f, -0.4f,
                16.0f, -0.2f, 3.0f, -0.0f, 1.5f, 0.2f, 1.5f, 0.4f, 3.0f, 0.6f,
                11.0f, 0.8f, 24.0f, 44.0f, 3.0f, 3.0f, 3.0f, 3.0f, 3.0f, 3.0f,
                3.0f, 3.0f, 3.0f, 3.0f, 10.0f, 5.0E-5f, 9.0E-5f, 14.0f, 100.0f, 100.0f, 6.0f);
        if (!found.equals(expected)) {
            System.out.println("  !! spotted() float 常數序列漂移: " + found);
            return false;
        }
        return true;
    }

    /** W3-4 指紋：setTargetAlpha 頭三條真實指令＝getstatic GameServer.server → ifeq → return。 */
    static boolean checkServerGuardHead(MethodNode m) {
        int[] want = { Opcodes.GETSTATIC, Opcodes.IFEQ, Opcodes.RETURN };
        return matchHead(m, want);
    }

    /** W3-4 指紋：getTargetAlpha 頭四條＝getstatic server → ifeq → fconst_1 → freturn。 */
    static boolean checkGetTargetAlphaGuard(MethodNode m) {
        int[] want = { Opcodes.GETSTATIC, Opcodes.IFEQ, Opcodes.FCONST_1, Opcodes.FRETURN };
        return matchHead(m, want);
    }

    private static boolean matchHead(MethodNode m, int[] want) {
        int i = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null && i < want.length; in = in.getNext()) {
            if (in.getOpcode() < 0) {
                continue;
            }
            if (in.getOpcode() != want[i]) {
                return false;
            }
            if (i == 0) {
                FieldInsnNode fi = (FieldInsnNode) in;
                if (!fi.owner.equals("zombie/network/GameServer") || !fi.name.equals("server")) {
                    return false;
                }
            }
            i++;
        }
        return i == want.length;
    }

    /** W3-3 去虛擬化前提：BaseAnimalBehavior 全後代（全 jar walk）零 spotted 覆寫——改道後 static dispatch 等價。 */
    static int checkAnimalBehaviorDomain(Path jar) throws Exception {
        String base = "zombie/characters/animals/behavior/BaseAnimalBehavior";
        Map<String, String> superOf = new HashMap<>();
        Set<String> overrides = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(zf.getInputStream(e).readAllBytes())
                        .accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                superOf.put(cn.name, cn.superName);
                for (MethodNode m : cn.methods) {
                    if (m.name.equals("spotted") && m.desc.equals("(Lzombie/iso/IsoMovingObject;ZF)V")
                            && !cn.name.equals(base)) {
                        overrides.add(cn.name);
                        break;
                    }
                }
            }
        }
        int bad = 0;
        for (String cls : superOf.keySet()) {
            String cur = cls;
            while (cur != null && !cur.equals(base)) {
                cur = superOf.get(cur);
            }
            if (cur != null && overrides.contains(cls)) {
                System.out.println("  !! spotted 覆寫: " + cls);
                bad++;
            }
        }
        return check("W3-3 behavior domain：BaseAnimalBehavior 全後代零 spotted 覆寫（全 jar walk）", bad == 0);
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "struct OK  " : "struct FAIL ") + what);
        return ok ? 0 : 1;
    }

    static MethodNode method(Path distJava, String cls, String name, String desc) throws Exception {
        ClassNode cn = classNode(distJava, cls);
        return cn.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }

    static ClassNode classNode(Path distJava, String cls) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(distJava.resolve(cls + ".class"))).accept(cn, 0);
        return cn;
    }

    /** 讀 jar 內 vanilla class 的方法（前提守門用——與 patched 版分開比對）。 */
    static MethodNode methodFromJar(Path jar, String cls, String name, String desc) throws Exception {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            byte[] bytes = jf.getInputStream(jf.getEntry(cls + ".class")).readAllBytes();
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            return cn.methods.stream()
                    .filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
        }
    }

    /** 統計方法內 ldc2_w 的指定 long 常數出現次數。 */
    static int countLongConst(MethodNode m, long value) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Long l && l == value) {
                count++;
            }
        }
        return count;
    }

    static boolean containsUtf8(Path distJava, String cls, String value) throws Exception {
        byte[] bytes = Files.readAllBytes(distJava.resolve(cls + ".class"));
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }

    static boolean staticFieldsAreWeakOrPrimitive(ClassNode node) {
        for (var field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            boolean primitive = field.desc.length() == 1
                    && "ZBCSIJFD".contains(field.desc);
            boolean weakRegistry = field.name.equals("STATES")
                    && field.desc.equals("Ljava/util/WeakHashMap;");
            if (!primitive && !weakRegistry) {
                return false;
            }
        }
        return true;
    }

    static boolean stateFieldsArePrimitiveOnly(ClassNode node) {
        for (var field : node.fields) {
            boolean primitive = field.desc.length() == 1
                    && "ZBCSIJFD".contains(field.desc);
            boolean primitiveCollection = field.desc.equals("Lgnu/trove/map/hash/TIntIntHashMap;")
                    || field.desc.equals("Lgnu/trove/set/hash/TIntHashSet;");
            if (!primitive && !primitiveCollection) {
                return false;
            }
        }
        return true;
    }

    static int countCalls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode in : method.instructions) {
            if (in instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    static int countExactCalls(MethodNode method, int opcode, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode in : method.instructions) {
            if (in instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) {
                count++;
            }
        }
        return count;
    }

    static MethodInsnNode findExactCall(MethodNode method, int opcode, String owner, String name, String desc) {
        for (AbstractInsnNode in : method.instructions) {
            if (in instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) {
                return call;
            }
        }
        return null;
    }

    static int countFieldReads(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode in : method.instructions) {
            if (in instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner)
                    && field.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 常數手術的語境鎖：找 float 常數 value，要求緊接 arithOpcode（-1＝不檢查），
     * 且其後 window 條真指令內出現 callOwner.callName。
     * 逐方法命中數守門只數常數個數，擋不住「數量對但改到同方法的另一條算式」——這裡連前後指令一起鎖。
     */
    static int countConstContext(MethodNode m, float value, int arithOpcode,
                                 String callOwner, String callName, int window) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (!(in instanceof LdcInsnNode ldc)
                    || !(ldc.cst instanceof Float f)
                    || Float.floatToIntBits(f) != Float.floatToIntBits(value)) {
                continue;
            }
            AbstractInsnNode cursor = nextReal(ldc);
            if (arithOpcode >= 0) {
                if (cursor == null || cursor.getOpcode() != arithOpcode) {
                    continue;
                }
                cursor = nextReal(cursor);
            }
            for (int i = 0; i <= window && cursor != null; i++, cursor = nextReal(cursor)) {
                if (cursor instanceof MethodInsnNode call
                        && call.owner.equals(callOwner) && call.name.equals(callName)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** 統計 float 常數 value 緊接 opcode 的次數；opcode＝-1 時只數該常數出現次數。 */
    static int countConstThen(MethodNode m, float value, int opcode) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (!(in instanceof LdcInsnNode ldc)
                    || !(ldc.cst instanceof Float f)
                    || Float.floatToIntBits(f) != Float.floatToIntBits(value)) {
                continue;
            }
            if (opcode < 0) {
                count++;
                continue;
            }
            AbstractInsnNode next = nextReal(ldc);
            if (next != null && next.getOpcode() == opcode) {
                count++;
            }
        }
        return count;
    }

    static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        for (AbstractInsnNode next = instruction.getNext(); next != null; next = next.getNext()) {
            if (next.getOpcode() >= 0) {
                return next;
            }
        }
        return null;
    }

    static AbstractInsnNode prevReal(AbstractInsnNode instruction) {
        for (AbstractInsnNode prev = instruction.getPrevious(); prev != null; prev = prev.getPrevious()) {
            if (prev.getOpcode() >= 0) {
                return prev;
            }
        }
        return null;
    }

    /** 取前 n 條「真指令」（跳過 label/frame/line）。 */
    static AbstractInsnNode[] firstReal(MethodNode m, int n) {
        AbstractInsnNode[] out = new AbstractInsnNode[n];
        int i = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null && i < n; in = in.getNext()) {
            if (in.getOpcode() >= 0) {
                out[i++] = in;
            }
        }
        return out;
    }

    private SmokeCheck() {}
}
