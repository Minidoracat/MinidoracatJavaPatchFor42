import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
        int failed = 0;
        int clampCeiling = 0;

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

            Class<?> guard = Class.forName("zombie.mdc.PopmanBufferGuard", true, patched);
            Method clamp = guard.getMethod("clampAddZombieCount", int.class);
            clampCeiling = (Integer)clamp.invoke(null, Integer.MAX_VALUE);
            failed += check("popman clamp 行為（35 內原值、36+ 夾 35、負值不動）",
                    (Integer)clamp.invoke(null, 10) == 10
                    && (Integer)clamp.invoke(null, 35) == 35
                    && (Integer)clamp.invoke(null, 36) == 35
                    && clampCeiling == 35
                    && (Integer)clamp.invoke(null, -3) == -3);
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

        // ---- popman clamp：鎖定「native 分頁 → offset 推進（原值）→ clamp 迴圈上限」全序 ----
        // 命中數守門只保證插入發生一次；這裡連 slot 一致性一起鎖：clamp 必須在 istore O 之後、
        // 且 iload/istore 的是同一個 count slot——插錯位置（例如 clamp 跑到 offset += count 之前，
        // 會改變分頁消耗語意）或鎖錯變數都在此擋下。
        String popmanCls = "zombie/popman/ZombiePopulationManager";
        String popmanGuard = "zombie/mdc/PopmanBufferGuard";
        MethodNode popman = method(distJava, popmanCls, "updateMain", "()V");
        MethodInsnNode nativePage = findExactCall(popman, Opcodes.INVOKESTATIC,
                popmanCls, "n_getAddZombieData", "(ILjava/nio/ByteBuffer;)I");
        boolean clampSeq = false;
        if (nativePage != null) {
            // w[0..7]＝clamp 全序；w[8..12]＝解析迴圈頭（iconst_0; istore i; iload i; iload C; if_icmpge）——
            // 鎖住「被 clamp 的變數就是迴圈比較上限」且 loop-index slot 與 count/offset slot 相異，
            // 避免 TIS 改用另一份拷貝或另一個變數時 clamp 變死碼（codex 對抗審查發現）
            AbstractInsnNode[] w = new AbstractInsnNode[13];
            AbstractInsnNode cursor = nativePage;
            boolean full = true;
            for (int i = 0; i < w.length; i++) {
                cursor = nextReal(cursor);
                if (cursor == null) { full = false; break; }
                w[i] = cursor;
            }
            clampSeq = full
                    && w[0] instanceof VarInsnNode s0 && s0.getOpcode() == Opcodes.ISTORE
                    && w[1] instanceof VarInsnNode s1 && s1.getOpcode() == Opcodes.ILOAD && s1.var != s0.var
                    && w[2] instanceof VarInsnNode s2 && s2.getOpcode() == Opcodes.ILOAD && s2.var == s0.var
                    && w[3].getOpcode() == Opcodes.IADD
                    && w[4] instanceof VarInsnNode s4 && s4.getOpcode() == Opcodes.ISTORE && s4.var == s1.var
                    && w[5] instanceof VarInsnNode s5 && s5.getOpcode() == Opcodes.ILOAD && s5.var == s0.var
                    && w[6] instanceof MethodInsnNode c6 && c6.getOpcode() == Opcodes.INVOKESTATIC
                            && c6.owner.equals(popmanGuard)
                            && c6.name.equals("clampAddZombieCount") && c6.desc.equals("(I)I")
                    && w[7] instanceof VarInsnNode s7 && s7.getOpcode() == Opcodes.ISTORE && s7.var == s0.var
                    && w[8].getOpcode() == Opcodes.ICONST_0
                    && w[9] instanceof VarInsnNode s9 && s9.getOpcode() == Opcodes.ISTORE
                            && s9.var != s0.var && s9.var != s1.var
                    && w[10] instanceof VarInsnNode s10 && s10.getOpcode() == Opcodes.ILOAD && s10.var == s9.var
                    && w[11] instanceof VarInsnNode s11 && s11.getOpcode() == Opcodes.ILOAD && s11.var == s0.var
                    && w[12] instanceof JumpInsnNode j12 && j12.getOpcode() == Opcodes.IF_ICMPGE;
        }
        failed += check("popman clamp 插在 offset 推進之後、count slot 即 if_icmpge 迴圈上限", clampSeq);
        failed += check("popman clamp 恰一次、native 分頁呼叫未增減",
                countExactCalls(popman, Opcodes.INVOKESTATIC, popmanGuard, "clampAddZombieCount", "(I)I") == 1
                && countExactCalls(popman, Opcodes.INVOKESTATIC,
                        popmanCls, "n_getAddZombieData", "(ILjava/nio/ByteBuffer;)I") == 1);

        // MAX_RECORDS＝1024/29 的兩個上游前提做成可執行守門（codex 對抗審查發現）：
        // capacity 取自 <init> 的 allocateDirect 實參、每筆 bytes 由 updateMain 的 buffer 讀取組成計出，
        // 再與 helper 實際 clamp ceiling 連動——PZ 只改 buffer 大小或 record 欄位時建置失敗而非默默錯上限
        MethodNode popmanInit = method(distJava, popmanCls, "<init>", "()V");
        int popmanCapacity = -1;
        int popmanAllocCount = 0;
        for (AbstractInsnNode in : popmanInit.instructions) {
            if (in instanceof MethodInsnNode alloc && alloc.getOpcode() == Opcodes.INVOKESTATIC
                    && alloc.owner.equals("java/nio/ByteBuffer") && alloc.name.equals("allocateDirect")) {
                popmanAllocCount++;
                AbstractInsnNode prev = prevReal(alloc);
                AbstractInsnNode next = nextReal(alloc);
                if (prev instanceof IntInsnNode cap && cap.getOpcode() == Opcodes.SIPUSH
                        && next instanceof FieldInsnNode pf && pf.getOpcode() == Opcodes.PUTFIELD
                        && pf.owner.equals(popmanCls) && pf.name.equals("byteBuffer")) {
                    popmanCapacity = cap.operand;
                }
            }
        }
        int floatReads = countExactCalls(popman, Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "getFloat", "()F");
        int byteReads = countExactCalls(popman, Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "get", "()B");
        int intReads = countExactCalls(popman, Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "getInt", "()I");
        int recordBytes = floatReads * 4 + byteReads + intReads * 4;
        failed += check("popman buffer 容量與每筆組成未漂移（allocateDirect(1024)、3F+1B+4I）",
                popmanAllocCount == 1 && popmanCapacity == 1024
                && floatReads == 3 && byteReads == 1 && intReads == 4);
        failed += check("helper clamp ceiling ＝ 容量/每筆 bytes（上限與前提連動）",
                recordBytes > 0 && clampCeiling == popmanCapacity / recordBytes);

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

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("守衛語意驗證全數通過");
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
