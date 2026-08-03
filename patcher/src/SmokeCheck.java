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
import org.objectweb.asm.tree.LabelNode;
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

            failed += checkCellListMembership(patched);
            failed += checkFertilizedEggGuard(patched);

            Class<?> guard = Class.forName("zombie.mdc.PopmanBufferGuard", true, patched);
            Method clamp = guard.getMethod("clampAddZombieCount", int.class);
            Method swapBuffer = guard.getMethod("updateMainBuffer", ByteBuffer.class);
            ByteBuffer priv = (ByteBuffer)swapBuffer.invoke(null, (ByteBuffer)null);
            ByteBuffer privAgain = (ByteBuffer)swapBuffer.invoke(null, ByteBuffer.allocate(1));
            failed += check("popman v3 專用 buffer（同一實例、direct、容量 1024、無視傳入值）",
                    priv != null && priv == privAgain && priv.isDirect() && priv.capacity() == 1024);
            priv.clear();
            clampCeiling = (Integer)clamp.invoke(null, Integer.MAX_VALUE);
            boolean fullOk = (Integer)clamp.invoke(null, 10) == 10
                    && (Integer)clamp.invoke(null, 35) == 35
                    && (Integer)clamp.invoke(null, 36) == 35
                    && clampCeiling == 35
                    && (Integer)clamp.invoke(null, -3) == -3;
            priv.limit(58);          // 2 筆整＝58 bytes
            boolean shortOk = (Integer)clamp.invoke(null, 20) == 2;
            priv.limit(57);          // 1 筆整＋28 bytes 殘尾
            boolean partialOk = (Integer)clamp.invoke(null, 2) == 1;
            priv.clear();
            priv.position(29);       // remaining=995→34 筆；誤改成 limit()/29 會得 35（codex 語境鎖）
            boolean advancedOk = (Integer)clamp.invoke(null, 35) == 34;
            priv.clear();
            failed += check("popman clamp 行為（容量上限 35、limit-short/position 依 remaining、負值不動）",
                    fullOk && shortOk && partialOk && advancedOk);
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
            // w[0..7]＝clamp 全序（v3 回 (I)I——上限計算移入 helper 的專用 buffer）；
            // w[8..12]＝解析迴圈頭（iconst_0; istore i; iload i; iload C; if_icmpge）——
            // 鎖住「被 clamp 的變數就是迴圈比較上限」且 loop-index slot 與 count/offset slot 相異
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
                countExactCalls(popman, Opcodes.INVOKESTATIC, popmanGuard,
                        "clampAddZombieCount", "(I)I") == 1
                && countExactCalls(popman, Opcodes.INVOKESTATIC,
                        popmanCls, "n_getAddZombieData", "(ILjava/nio/ByteBuffer;)I") == 1);

        // ---- v3 buffer 隔離：updateMain 內每個 getfield byteBuffer 必須緊接 swap，10/10 無漏 ----
        int popmanGetfields = 0;
        int popmanSwapped = 0;
        for (AbstractInsnNode in : popman.instructions) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                    && fi.owner.equals(popmanCls) && fi.name.equals("byteBuffer")
                    && fi.desc.equals("Ljava/nio/ByteBuffer;")) {
                popmanGetfields++;
                AbstractInsnNode next = nextReal(in);
                if (next instanceof MethodInsnNode sw && sw.getOpcode() == Opcodes.INVOKESTATIC
                        && sw.owner.equals(popmanGuard) && sw.name.equals("updateMainBuffer")
                        && sw.desc.equals("(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;")) {
                    popmanSwapped++;
                }
            }
        }
        failed += check("popman v3 隔離：updateMain 10 處 getfield byteBuffer 全部緊接 swap、無多餘 swap",
                popmanGetfields == 10 && popmanSwapped == 10
                && countExactCalls(popman, Opcodes.INVOKESTATIC, popmanGuard, "updateMainBuffer",
                        "(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;") == 10);

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

        // ---- 效能第一波（載具預篩＋VehicleManager 512→256）----
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

        // VehicleManager <init>：sipush 512→256，語境鎖＝256 後緊接 anewarray UdpConnection；
        // 負對照＝bipush 27 與 100L/1000L 節流常數原樣（守門盲點教訓：數量對不代表改對地方）
        MethodNode vmInit = method(distJava, "zombie/vehicles/VehicleManager", "<init>", "()V");
        int sipush256Ctx = 0, sipush512 = 0, bipush27 = 0;
        boolean throttle100 = false, throttle1000 = false;
        for (AbstractInsnNode in : vmInit.instructions) {
            if (in instanceof IntInsnNode ii && ii.getOpcode() == Opcodes.SIPUSH) {
                if (ii.operand == 512) {
                    sipush512++;
                } else if (ii.operand == 256) {
                    AbstractInsnNode next = nextReal(ii);
                    if (next instanceof TypeInsnNode t && t.getOpcode() == Opcodes.ANEWARRAY
                            && t.desc.equals("zombie/core/raknet/UdpConnection")) {
                        sipush256Ctx++;
                    }
                }
            }
            if (in instanceof IntInsnNode ii && ii.getOpcode() == Opcodes.BIPUSH && ii.operand == 27) {
                bipush27++;
            }
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Long l) {
                if (l == 100L) {
                    throttle100 = true;
                }
                if (l == 1000L) {
                    throttle1000 = true;
                }
            }
        }
        failed += check("VehicleManager connected 陣列 256（語境鎖 anewarray UdpConnection）",
                sipush256Ctx == 1 && sipush512 == 0);
        failed += check("VehicleManager 節流常數未誤中（bipush 27、100L、1000L 原樣）",
                bipush27 == 1 && throttle100 && throttle1000);

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

        // ---- P5 CellListMembership（15 呼叫點；docs/p5-chunk-unload-design-v2.md §4）----
        String clmCls = "zombie/mdc/CellListMembership";
        String alCls = "java/util/ArrayList";
        String objZ = "(Ljava/lang/Object;)Z";
        String clmObjZ = "(Ljava/util/ArrayList;Ljava/lang/Object;)Z";

        MethodNode p5Process = method(distJava, "zombie/iso/IsoCell", "ProcessIsoObject", "()V");
        failed += check("P5 ProcessIsoObject：removeAll/clear 改道恰一、原呼叫歸零、size×2/get×1 原樣(S3)",
                countExactCalls(p5Process, Opcodes.INVOKESTATIC, clmCls, "removeAll",
                        "(Ljava/util/ArrayList;Ljava/util/Collection;)Z") == 1
                && countExactCalls(p5Process, Opcodes.INVOKESTATIC, clmCls, "clear",
                        "(Ljava/util/ArrayList;)V") == 1
                && countExactCalls(p5Process, Opcodes.INVOKEVIRTUAL, alCls, "removeAll",
                        "(Ljava/util/Collection;)Z") == 0
                && countExactCalls(p5Process, Opcodes.INVOKEVIRTUAL, alCls, "clear", "()V") == 0
                && countExactCalls(p5Process, Opcodes.INVOKEVIRTUAL, alCls, "size", "()I") == 2
                && countExactCalls(p5Process, Opcodes.INVOKEVIRTUAL, alCls, "get",
                        "(I)Ljava/lang/Object;") == 1);

        MethodNode p5Add = method(distJava, "zombie/iso/IsoCell", "addToProcessIsoObject",
                "(Lzombie/iso/IsoObject;)V");
        MethodNode p5AddRemove = method(distJava, "zombie/iso/IsoCell", "addToProcessIsoObjectRemove",
                "(Lzombie/iso/IsoObject;)V");
        MethodNode p5Static = method(distJava, "zombie/iso/IsoCell", "addToStaticUpdaterObjectList",
                "(Lzombie/iso/IsoObject;)V");
        failed += check("P5 addToProcessIsoObject：remove/contains/add 各恰一且原呼叫歸零",
                countExactCalls(p5Add, Opcodes.INVOKESTATIC, clmCls, "remove", clmObjZ) == 1
                && countExactCalls(p5Add, Opcodes.INVOKESTATIC, clmCls, "contains", clmObjZ) == 1
                && countExactCalls(p5Add, Opcodes.INVOKESTATIC, clmCls, "add", clmObjZ) == 1
                && countExactCalls(p5Add, Opcodes.INVOKEVIRTUAL, alCls, "remove", objZ) == 0
                && countExactCalls(p5Add, Opcodes.INVOKEVIRTUAL, alCls, "contains", objZ) == 0
                && countExactCalls(p5Add, Opcodes.INVOKEVIRTUAL, alCls, "add", objZ) == 0);
        failed += check("P5 addToProcessIsoObjectRemove：contains×2＋add×1 且原呼叫歸零",
                countExactCalls(p5AddRemove, Opcodes.INVOKESTATIC, clmCls, "contains", clmObjZ) == 2
                && countExactCalls(p5AddRemove, Opcodes.INVOKESTATIC, clmCls, "add", clmObjZ) == 1
                && countExactCalls(p5AddRemove, Opcodes.INVOKEVIRTUAL, alCls, "contains", objZ) == 0
                && countExactCalls(p5AddRemove, Opcodes.INVOKEVIRTUAL, alCls, "add", objZ) == 0);
        failed += check("P5 addToStaticUpdaterObjectList：contains＋add 各恰一且原呼叫歸零",
                countExactCalls(p5Static, Opcodes.INVOKESTATIC, clmCls, "contains", clmObjZ) == 1
                && countExactCalls(p5Static, Opcodes.INVOKESTATIC, clmCls, "add", clmObjZ) == 1
                && countExactCalls(p5Static, Opcodes.INVOKEVIRTUAL, alCls, "contains", objZ) == 0
                && countExactCalls(p5Static, Opcodes.INVOKEVIRTUAL, alCls, "add", objZ) == 0);

        MethodNode p5ObjRemove = method(distJava, "zombie/iso/IsoObject", "removeFromWorld", "()V");
        failed += check("P5 IsoObject.removeFromWorld：S.remove 改道恰一且原呼叫歸零",
                countExactCalls(p5ObjRemove, Opcodes.INVOKESTATIC, clmCls, "remove", clmObjZ) == 1
                && countExactCalls(p5ObjRemove, Opcodes.INVOKEVIRTUAL, alCls, "remove", objZ) == 0);

        MethodNode p5Reanimate = method(distJava, "zombie/iso/objects/IsoDeadBody", "setReanimateTime", "(F)V");
        failed += check("P5 IsoDeadBody.setReanimateTime：contains×2＋add＋remove 且原呼叫歸零",
                countExactCalls(p5Reanimate, Opcodes.INVOKESTATIC, clmCls, "contains", clmObjZ) == 2
                && countExactCalls(p5Reanimate, Opcodes.INVOKESTATIC, clmCls, "add", clmObjZ) == 1
                && countExactCalls(p5Reanimate, Opcodes.INVOKESTATIC, clmCls, "remove", clmObjZ) == 1
                && countExactCalls(p5Reanimate, Opcodes.INVOKEVIRTUAL, alCls, "contains", objZ) == 0
                && countExactCalls(p5Reanimate, Opcodes.INVOKEVIRTUAL, alCls, "add", objZ) == 0
                && countExactCalls(p5Reanimate, Opcodes.INVOKEVIRTUAL, alCls, "remove", objZ) == 0);

        // S4 負對照：ProcessStaticUpdaters（javap 定案的誤報站點）不得有任何改道
        MethodNode p5StaticUpd = method(distJava, "zombie/iso/IsoCell", "ProcessStaticUpdaters", "()V");
        int clmInStaticUpd = 0;
        for (AbstractInsnNode in : p5StaticUpd.instructions) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(clmCls)) {
                clmInStaticUpd++;
            }
        }
        failed += check("P5 S4 負對照：ProcessStaticUpdaters 零改道（原誤報站點）", clmInStaticUpd == 0);

        // S5：六個 contains 改道點的下一條真指令必為 IFNE/IFEQ（分支消費者後綴鎖）
        int containsBranchOk = 0, containsTotal = 0;
        for (MethodNode mn : new MethodNode[]{p5Add, p5AddRemove, p5Static, p5Reanimate}) {
            for (AbstractInsnNode in : mn.instructions) {
                if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(clmCls) && mi.name.equals("contains")) {
                    containsTotal++;
                    AbstractInsnNode next = nextReal(in);
                    if (next != null && (next.getOpcode() == Opcodes.IFNE
                            || next.getOpcode() == Opcodes.IFEQ)) {
                        containsBranchOk++;
                    }
                }
            }
        }
        failed += check("P5 S5：六個 contains 改道點後綴皆為 IFNE/IFEQ（" + containsBranchOk + "/6）",
                containsTotal == 6 && containsBranchOk == 6);

        failed += check("P5 helper 含觀測 log 前綴",
                containsUtf8(distJava, clmCls, "[MinidoracatJavaPatch][CellList]"));

        // ---- 受精蛋清除豁免（2026-08-04）----
        // 命中數守門只保證改道發生一次，擋不住「改到別的呼叫點」——這裡把位置鎖進清除判定鏈：
        // 改道呼叫的前一條必為 aload（worldItem），後一條必為 ifne（true＝跳過清除），
        // 再下一條必為 getstatic GameTime.instance（原版清除鏈的下一段）。TIS 改寫 load 時
        // 建置失敗而非默默把豁免掛到別的地方。
        String eggGuardCls = "zombie/mdc/FertilizedEggGuard";
        String squareCls = "zombie/iso/IsoGridSquare";
        String wioCls = "zombie/iso/objects/IsoWorldInventoryObject";
        String loadDesc = "(Ljava/nio/ByteBuffer;IZ)V";
        String ignoreDesc = "()Z";
        String eggRedirectDesc = "(L" + wioCls + ";)Z";

        MethodNode vanillaLoad = methodFromJar(jar, squareCls, "load", loadDesc);
        failed += check("vanilla 前提：load 內 isIgnoreRemoveSandbox 恰一、清除鏈 4×listContains＋1×getWorldAgeHours",
                countExactCalls(vanillaLoad, Opcodes.INVOKEVIRTUAL, wioCls,
                        "isIgnoreRemoveSandbox", ignoreDesc) == 1
                && countExactCalls(vanillaLoad, Opcodes.INVOKEVIRTUAL, "zombie/SandboxOptions",
                        "worldItemRemovalListContains", "(Ljava/lang/String;)Z") == 4
                && countExactCalls(vanillaLoad, Opcodes.INVOKEVIRTUAL, "zombie/GameTime",
                        "getWorldAgeHours", "()D") == 1);

        MethodNode patchedLoad = method(distJava, squareCls, "load", loadDesc);
        failed += check("受精蛋豁免改道恰一次且原呼叫歸零",
                countExactCalls(patchedLoad, Opcodes.INVOKESTATIC, eggGuardCls,
                        "isIgnoreRemoveSandbox", eggRedirectDesc) == 1
                && countExactCalls(patchedLoad, Opcodes.INVOKEVIRTUAL, wioCls,
                        "isIgnoreRemoveSandbox", ignoreDesc) == 0);
        failed += check("清除鏈其餘判定原樣（4×listContains＋1×getWorldAgeHours 未被動）",
                countExactCalls(patchedLoad, Opcodes.INVOKEVIRTUAL, "zombie/SandboxOptions",
                        "worldItemRemovalListContains", "(Ljava/lang/String;)Z") == 4
                && countExactCalls(patchedLoad, Opcodes.INVOKEVIRTUAL, "zombie/GameTime",
                        "getWorldAgeHours", "()D") == 1);

        MethodInsnNode eggCall = findExactCall(patchedLoad, Opcodes.INVOKESTATIC, eggGuardCls,
                "isIgnoreRemoveSandbox", eggRedirectDesc);
        boolean eggSeq = false;
        JumpInsnNode eggIfne = null;
        if (eggCall != null) {
            AbstractInsnNode before = prevReal(eggCall);
            AbstractInsnNode after = nextReal(eggCall);
            AbstractInsnNode after2 = after == null ? null : nextReal(after);
            eggSeq = before != null && before.getOpcode() == Opcodes.ALOAD
                    && after instanceof JumpInsnNode ifne && ifne.getOpcode() == Opcodes.IFNE
                    && after2 instanceof FieldInsnNode gs && gs.getOpcode() == Opcodes.GETSTATIC
                            && gs.owner.equals("zombie/GameTime") && gs.name.equals("instance");
            if (eggSeq) {
                eggIfne = (JumpInsnNode)after;
            }
        }
        failed += check("受精蛋豁免落在清除判定鏈（aload→guard→ifne→GameTime.instance）", eggSeq);

        // 分支方向鎖：形狀對不代表方向對。guard 回 true 的去處，必須等於清除鏈尾端
        // 「還沒過期＝保留」的去處（vanilla 594:ifne 625 與 619:ifle 625 同一 label）。
        // 若 TIS 把語意反轉成「要清除」，指令形狀完全不變、命中數也不變，只有這條擋得住。
        MethodInsnNode worldAgeCall = findExactCall(patchedLoad, Opcodes.INVOKEVIRTUAL,
                "zombie/GameTime", "getWorldAgeHours", "()D");
        JumpInsnNode expiryIfle = null;
        for (AbstractInsnNode in = worldAgeCall; in != null; in = nextReal(in)) {
            if (in instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFLE) {
                expiryIfle = jump;
                break;
            }
        }
        failed += check("受精蛋豁免的分支方向正確（guard=true 的去處＝未過期保留的去處）",
                eggIfne != null && expiryIfle != null && eggIfne.label == expiryIfle.label);

        // 匯流鎖：四個 listContains 分支中，判定為「命中清單」的三條必須全部落到 guard 前的
        // aload。PZ 重排分支讓某個模式繞過 guard 時（例如白名單分支直接 goto 保留），
        // 命中數與全序鎖都無感，只有這條會紅。
        AbstractInsnNode eggAload = eggCall == null ? null : prevReal(eggCall);
        int intoGuard = 0;
        if (eggAload instanceof LabelNode) {
            eggAload = nextReal(eggAload);
        }
        LabelNode guardLabel = null;
        for (AbstractInsnNode in = patchedLoad.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof LabelNode label && nextReal(label) == eggAload) {
                guardLabel = label;
                break;
            }
        }
        for (AbstractInsnNode in : patchedLoad.instructions) {
            if (in instanceof JumpInsnNode jump && jump.label == guardLabel) {
                intoGuard++;
            }
        }
        failed += check("四個 listContains 分支中命中清單的三條全部匯流到 guard（" + intoGuard + "/3）",
                guardLabel != null && intoGuard == 3);

        MethodNode eggRedirect = method(distJava, eggGuardCls, "isIgnoreRemoveSandbox", eggRedirectDesc);
        failed += check("EggGuard 先取 vanilla 豁免值恰一次（原語意保留）",
                countExactCalls(eggRedirect, Opcodes.INVOKEVIRTUAL, wioCls,
                        "isIgnoreRemoveSandbox", ignoreDesc) == 1);
        ClassNode eggNode = classNode(distJava, eggGuardCls);
        failed += check("EggGuard static 欄位僅 primitive 且含觀測 log 前綴",
                !eggNode.fields.isEmpty()
                && eggNode.fields.stream().allMatch(f -> f.desc.length() == 1 && "ZBCSIJFD".contains(f.desc))
                && containsUtf8(distJava, eggGuardCls, "[MinidoracatJavaPatch][EggGuard]"));

        failed += checkIdentityDomain(jar);

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

    /** P5 CellListMembership 行為測試：differential／重複元素／ghost 自癒／kill／降級／removeAll／重入黃金比對。 */
    static int checkCellListMembership(ClassLoader patched) throws Exception {
        int failed = 0;
        Class<?> clm = Class.forName("zombie.mdc.CellListMembership", true, patched);
        Method inject = clm.getDeclaredMethod("testInject", ArrayList.class, ArrayList.class, ArrayList.class);
        Method reset = clm.getDeclaredMethod("testReset");
        Method killedQ = clm.getDeclaredMethod("testKilled");
        inject.setAccessible(true);
        reset.setAccessible(true);
        killedQ.setAccessible(true);
        Field mask = clm.getDeclaredField("auditMask");
        mask.setAccessible(true);
        Method mContains = clm.getMethod("contains", ArrayList.class, Object.class);
        Method mAdd = clm.getMethod("add", ArrayList.class, Object.class);
        Method mRemove = clm.getMethod("remove", ArrayList.class, Object.class);
        Method mRemoveAll = clm.getMethod("removeAll", ArrayList.class, Collection.class);

        // 1) differential：400 個決定性偽隨機 op（含重複與 null）與 vanilla 全等
        reset.invoke(null);
        ArrayList<Object> p = new ArrayList<>(), r = new ArrayList<>(), s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        ArrayList<Object> ref = new ArrayList<>();
        Object oa = new Object(), ob = new Object(), oc = new Object();
        Object[] pool = {oa, ob, oc, null, oa, ob};
        boolean diffOk = true;
        long seed = 20260803L;
        for (int i = 0; i < 400; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            Object o = pool[(int) ((seed >>> 16) % pool.length)];
            switch ((int) ((seed >>> 33) % 3)) {
                case 0 -> diffOk &= ((Boolean) mAdd.invoke(null, p, o)) == ref.add(o);
                case 1 -> diffOk &= ((Boolean) mRemove.invoke(null, p, o)) == ref.remove(o);
                default -> diffOk &= ((Boolean) mContains.invoke(null, p, o)) == ref.contains(o);
            }
        }
        failed += check("CellList differential：400 op（重複/null）回傳與內容順序全等 vanilla",
                diffOk && p.equals(ref));

        // 2) 重複元素感知：[x,x] 移除一份後 membership 必須保留
        reset.invoke(null);
        p = new ArrayList<>(); r = new ArrayList<>(); s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        Object x = new Object();
        mAdd.invoke(null, p, x);
        mAdd.invoke(null, p, x);
        mRemove.invoke(null, p, x);
        boolean dupKeep = (Boolean) mContains.invoke(null, p, x);
        mRemove.invoke(null, p, x);
        boolean dupGone = !((Boolean) mContains.invoke(null, p, x)) && p.isEmpty();
        failed += check("CellList 重複元素：移除一份仍在、移除兩份才除名", dupKeep && dupGone);

        // 3) 等大小換血 ghost：audit 全開時 contains 與 remove-miss 都必須自癒
        reset.invoke(null);
        p = new ArrayList<>(); r = new ArrayList<>(); s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        Object y = new Object(), z = new Object();
        mAdd.invoke(null, p, y);
        p.set(0, z);                       // 旁路等大小換血：y→z
        mask.setInt(null, 0);              // 每 op 抽驗
        boolean ghostContains = (Boolean) mContains.invoke(null, p, z);   // 假陰性→audit→rebuild→true
        p.set(0, y);                       // 再換回：set 此刻認 z 不認 y
        boolean ghostRemove = (Boolean) mRemove.invoke(null, p, y);       // fast-miss audit→修復→真移除
        failed += check("CellList 等大小換血：contains 假陰性與 remove-miss ghost 皆被 audit 自癒",
                ghostContains && ghostRemove && p.isEmpty() && !((Boolean) killedQ.invoke(null)));

        // 4) size 漂移自癒不計 kill（GO-WITH-FIXES：良性旁路只 rebuild）
        reset.invoke(null);
        p = new ArrayList<>(); r = new ArrayList<>(); s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        for (int i = 0; i < 20; i++) {
            p.add(new Object());                                   // 旁路 append（size 漂移）
            if (!((Boolean) mContains.invoke(null, p, p.get(p.size() - 1)))) {
                failed += check("CellList size 漂移 rebuild 後應答正確", false);
                break;
            }
        }
        failed += check("CellList 20 次 size 漂移 rebuild 全部自癒且未 kill",
                !((Boolean) killedQ.invoke(null)));

        // 5) audit divergence 達 8 → 永久 kill → 直通 vanilla
        reset.invoke(null);
        p = new ArrayList<>(); r = new ArrayList<>(); s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        mask.setInt(null, 0);
        Object probe = new Object();
        // 每輪：helper add（set 記錄）→ 旁路換血同格（size 不變、set 與 list 分歧）→ audit 必偵測。
        // 不可只換血一次：首次 audit 會 rebuild 使兩者一致，之後不再分歧（測試自身的教訓）。
        for (int i = 0; i < 12 && !((Boolean) killedQ.invoke(null)); i++) {
            mAdd.invoke(null, p, probe);
            p.set(p.size() - 1, new Object());
            mContains.invoke(null, p, probe);
        }
        boolean nowKilled = (Boolean) killedQ.invoke(null);
        ArrayList<Object> after = new ArrayList<>();
        mAdd.invoke(null, after, probe);   // killed 後任何清單都直通 vanilla
        failed += check("CellList audit divergence 達門檻後永久 kill 且直通 vanilla",
                nowKilled && after.size() == 1 && after.get(0) == probe);

        // 6) generation 降級：未知清單一律 vanilla 行為
        reset.invoke(null);
        p = new ArrayList<>(); r = new ArrayList<>(); s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        ArrayList<Object> unknown = new ArrayList<>();
        boolean downgrade = !((Boolean) mContains.invoke(null, unknown, oa))
                && (Boolean) mAdd.invoke(null, unknown, oa)
                && unknown.size() == 1;
        failed += check("CellList 未知清單（舊 cell/降級路徑）純 vanilla 直通", downgrade);

        // 7) removeAll：重複、R⊄P、空 R、subclass gate 全對照 vanilla
        reset.invoke(null);
        p = new ArrayList<>(); r = new ArrayList<>(); s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        for (Object o : new Object[]{oa, ob, oa, oc}) {
            mAdd.invoke(null, p, o);
        }
        r.add(oa);
        r.add(new Object());               // R⊄P
        ArrayList<Object> refP = new ArrayList<>(java.util.List.of(oa, ob, oa, oc));
        ArrayList<Object> refR = new ArrayList<>(r);
        boolean raRet = (Boolean) mRemoveAll.invoke(null, p, r);
        boolean raRef = refP.removeAll(refR);
        boolean removeAllOk = raRet == raRef && p.equals(refP);
        boolean raEmpty = !((Boolean) mRemoveAll.invoke(null, p, new ArrayList<>() {
        }));                                // 匿名子類 → gate → 原生（空集合回 false）
        failed += check("CellList removeAll：重複全清、R⊄P、保序、回傳值與 subclass gate 全等 vanilla",
                removeAllOk && raEmpty);

        // 8) B10 黃金比對：補償迭代（n--/size--）中重入 remove，訪問序列與 vanilla 全等
        reset.invoke(null);
        p = new ArrayList<>(); r = new ArrayList<>(); s = new ArrayList<>();
        inject.invoke(null, p, r, s);
        Object[] objs = new Object[6];
        ArrayList<Object> refS = new ArrayList<>();
        for (int i = 0; i < objs.length; i++) {
            objs[i] = new Object();
            mAdd.invoke(null, s, objs[i]);
            refS.add(objs[i]);
        }
        Set<Object> doomed = new HashSet<>(java.util.List.of(objs[1], objs[3]));
        ArrayList<Object> visitPatched = new ArrayList<>(), visitRef = new ArrayList<>();
        for (int n = 0; n < s.size(); n++) {
            Object o = s.get(n);
            visitPatched.add(o);
            if (doomed.contains(o)) {
                mRemove.invoke(null, s, o);
                n--;
            }
        }
        for (int n = 0; n < refS.size(); n++) {
            Object o = refS.get(n);
            visitRef.add(o);
            if (doomed.contains(o)) {
                refS.remove(o);
                n--;
            }
        }
        failed += check("CellList 補償迭代重入 remove：訪問序列與最終清單全等 vanilla",
                visitPatched.equals(visitRef) && s.equals(refS));

        reset.invoke(null);
        return failed;
    }

    /** P5 identity domain：全 jar hierarchy walk，IsoObject 全後代不得覆寫 equals/hashCode。 */
    /**
     * 受精蛋豁免的行為驗證：委派 vanilla 值、判定四象限、以及整條改道路徑端到端。
     * Food 的建構子會解析 texture 路徑（需要遊戲檔案系統，headless 必 NPE），
     * 故以 allocateInstance 取裸實例後只設受精相關欄位——本 helper 讀的就只有這些。
     */
    static int checkFertilizedEggGuard(ClassLoader patched) throws Exception {
        int failed = 0;
        Class<?> wioClass = Class.forName("zombie.iso.objects.IsoWorldInventoryObject", true, patched);
        Class<?> cellClass = Class.forName("zombie.iso.IsoCell", false, patched);
        Class<?> itemClass = Class.forName("zombie.inventory.InventoryItem", false, patched);
        Class<?> foodClass = Class.forName("zombie.inventory.types.Food", true, patched);
        Class<?> timeClass = Class.forName("zombie.GameTime", true, patched);
        Class<?> guard = Class.forName("zombie.mdc.FertilizedEggGuard", true, patched);
        Method redirect = guard.getMethod("isIgnoreRemoveSandbox", wioClass);
        Method hatchable = guard.getMethod("isHatchableEgg", itemClass);
        Method inWindow = guard.getMethod("withinHatchWindow", wioClass, foodClass);
        Method keptLoadsObserved = guard.getMethod("keptLoadsObserved");
        Method expiredLoadsObserved = guard.getMethod("expiredLoadsObserved");
        Method anomaliesObserved = guard.getMethod("anomaliesObserved");
        Method setIgnore = wioClass.getMethod("setIgnoreRemoveSandbox", boolean.class);
        double windowMultiplier = guard.getDeclaredField("HATCH_WINDOW_MULTIPLIER").getDouble(null);

        // 世界時鐘在建置環境可控：getWorldAgeHours() = nightsSurvived*24 + timeOfDay 修正
        Object gameTime = timeClass.getMethod("getInstance").invoke(null);
        Method setNights = timeClass.getMethod("setNightsSurvived", int.class);
        Method worldAgeHours = timeClass.getMethod("getWorldAgeHours");
        setNights.invoke(gameTime, 0);
        double youngWorld = (Double)worldAgeHours.invoke(gameTime);

        // 1) 委派 vanilla：沒有物品時回原值 false；vanilla 豁免（ItemSpawner）原樣 true
        Object wio = wioClass.getConstructor(cellClass).newInstance(new Object[]{ null });
        boolean noItemIsVanillaFalse = !(Boolean)redirect.invoke(null, wio);
        setIgnore.invoke(wio, true);
        boolean vanillaTruePassThrough = (Boolean)redirect.invoke(null, wio);
        failed += check("EggGuard 委派 vanilla：無物品＝false、vanilla 豁免＝true",
                noItemIsVanillaFalse && vanillaTruePassThrough);

        // 2) 判定五種狀態＋null＋非 Food：只有「受精且帶得出物種」才進入豁免候選
        Object egg = allocateInstance(foodClass);
        Method setFertilized = foodClass.getMethod("setFertilized", boolean.class);
        Method setHatch = foodClass.getMethod("setAnimalHatch", String.class);
        // 直接寫欄位而非 setTimeToHatch()——後者會讀 SandboxOptions.animalEggHatch 的乘數表，
        // 而 SandboxOptions 的 clinit 需要遊戲檔案系統（headless 必炸）。helper 只讀 getTimeToHatch()。
        Field timeToHatchField = foodClass.getDeclaredField("timeToHatch");
        timeToHatchField.setAccessible(true);
        boolean plainFood = !(Boolean)hatchable.invoke(null, egg);
        setFertilized.invoke(egg, true);
        boolean noHatchType = !(Boolean)hatchable.invoke(null, egg);
        setHatch.invoke(egg, "");
        boolean emptyHatchType = !(Boolean)hatchable.invoke(null, egg);
        setHatch.invoke(egg, "hen");
        boolean fertilizedHatchable = (Boolean)hatchable.invoke(null, egg);
        setFertilized.invoke(egg, false);
        boolean cooledBackToRemovable = !(Boolean)hatchable.invoke(null, egg);
        boolean nullSafe = !(Boolean)hatchable.invoke(null, new Object[]{ null });
        // 非 Food 的 InventoryItem：擋住「判定被放寬成跨型別字串比對」這類改寫
        boolean nonFoodItem = !(Boolean)hatchable.invoke(null, allocateInstance(itemClass));
        failed += check("EggGuard 判定：未受精/無物種/空物種/失去受精/null/非 Food 皆不豁免，受精＋物種才豁免",
                plainFood && noHatchType && emptyHatchType && fertilizedHatchable
                && cooledBackToRemovable && nullSafe && nonFoodItem);

        // 3) 孵化視窗天花板——豁免有界的唯一保證（審查發現：原版三條去受精路徑對地上物品全不可達）
        setFertilized.invoke(egg, true);
        Field dropTime = wioClass.getField("dropTime");
        timeToHatchField.setInt(egg, 0);
        boolean noHatchTimeNotExempt = !(Boolean)inWindow.invoke(null, wio, egg);
        int hatchHours = 1260;   // 母雞 504×2.5（AnimalEggHatch=5）＝正式服實際值
        timeToHatchField.setInt(egg, hatchHours);
        dropTime.setDouble(wio, -1.0);
        boolean unknownDropTimeNotExempt = !(Boolean)inWindow.invoke(null, wio, egg);
        dropTime.setDouble(wio, 0.0);
        boolean freshEggInWindow = (Boolean)inWindow.invoke(null, wio, egg);
        // 世界時鐘推到剛好超過 dropTime + 倍率×timeToHatch 即應失效
        setNights.invoke(gameTime, (int)Math.ceil(windowMultiplier * hatchHours / 24.0) + 1);
        double agedWorld = (Double)worldAgeHours.invoke(gameTime);
        boolean expiredOutOfWindow = !(Boolean)inWindow.invoke(null, wio, egg);
        setNights.invoke(gameTime, 0);
        boolean backInWindow = (Boolean)inWindow.invoke(null, wio, egg);
        failed += check("EggGuard 孵化視窗：timeToHatch<=0 與 dropTime=-1 不豁免、視窗內豁免、"
                + "超過 " + windowMultiplier + "× 後失效（world " + youngWorld + "→" + agedWorld + "h）",
                noHatchTimeNotExempt && unknownDropTimeNotExempt && freshEggInWindow
                && expiredOutOfWindow && backInWindow && agedWorld > youngWorld);

        // 4) 端到端：非 vanilla 豁免的地上受精蛋，經改道必須回傳豁免。preserved 必須恰為 1，
        //    證明 log 分支（(preserved & 0x1F)==1）確實被執行過而不是被節流跳過
        setIgnore.invoke(wio, false);
        wioClass.getField("item").set(wio, egg);
        boolean endToEnd = (Boolean)redirect.invoke(null, wio);
        boolean logBranchTaken = (Long)keptLoadsObserved.invoke(null) == 1L;
        setFertilized.invoke(egg, false);
        boolean nonEggStillRemovable = !(Boolean)redirect.invoke(null, wio);
        // 過期蛋走 expired 計數而非 preserved，且同樣交還原版清除
        setFertilized.invoke(egg, true);
        setNights.invoke(gameTime, (int)Math.ceil(windowMultiplier * hatchHours / 24.0) + 1);
        boolean expiredRemovable = !(Boolean)redirect.invoke(null, wio);
        boolean expiredCounted = (Long)expiredLoadsObserved.invoke(null) >= 1L;
        setNights.invoke(gameTime, 0);
        failed += check("EggGuard 端到端：受精蛋豁免且走過 log 分支、失去受精與過期皆恢復可清除",
                endToEnd && logBranchTaken && nonEggStillRemovable
                && expiredRemovable && expiredCounted);

        // 全程零 anomalies——期望 false 的斷言在「helper 全程吞例外」時也會通過，
        // 沒有這條就分不出「正確回傳 false」與「fail-open 蓋掉一切」（審查發現）
        failed += check("EggGuard 全程零 anomalies（證明上述 false 是判定結果而非吞例外）",
                (Long)anomaliesObserved.invoke(null) == 0L);
        return failed;
    }

    /** 取得不經建構子的裸實例（Food 的 ctor 需要遊戲檔案系統，headless 不可用）。 */
    static Object allocateInstance(Class<?> type) throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    static int checkIdentityDomain(Path jar) throws Exception {
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
                    if ((m.name.equals("equals") && m.desc.equals("(Ljava/lang/Object;)Z"))
                            || (m.name.equals("hashCode") && m.desc.equals("()I"))) {
                        overrides.add(cn.name);
                        break;
                    }
                }
            }
        }
        int bad = 0;
        for (String cls : superOf.keySet()) {
            String cur = cls;
            while (cur != null && !cur.equals("zombie/iso/IsoObject")) {
                cur = superOf.get(cur);
            }
            if (cur != null && overrides.contains(cls)) {
                System.out.println("  !! equals/hashCode 覆寫: " + cls);
                bad++;
            }
        }
        return check("P5 identity domain：IsoObject 全後代零 equals/hashCode 覆寫（全 jar walk）", bad == 0);
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
