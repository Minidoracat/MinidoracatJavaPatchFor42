import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
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
        if (args.length > 2 && (args[2].equals("client-core") || args[2].equals("client-profiler"))) {
            ClientModuleCheck.verify(distJava, jar, args[2]);
            System.out.println(args[2] + " 模組掛點驗證全數通過");
            return;
        }


        if (args.length > 2 && (args[2].equals("client") || args[2].equals("client-lowmem") || args[2].equals("client-compat"))) {
            if (clientChecks(distJava, jar, args[2].equals("client-lowmem"), args[2].equals("client-compat")) > 0) {
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

            // ---- W8 chunk 寫入閘：verify()/resolveMode() 純函式行為（不碰磁碟的可測核心）----
            Class<?> cwg = Class.forName("zombie.mdc.ChunkWriteGuard", true, patched);
            Method verifyM = cwg.getDeclaredMethod("verify", byte[].class, int.class);
            verifyM.setAccessible(true);
            Method modeM = cwg.getDeclaredMethod("resolveMode", String.class);
            modeM.setAccessible(true);
            // 依 42.20.2 格式手工組一個自洽 chunk buffer
            java.nio.ByteBuffer tb = java.nio.ByteBuffer.allocate(256);
            tb.put((byte) 0);
            tb.putInt(249);
            tb.putInt(0);      // len 佔位
            tb.putLong(0L);    // crc 佔位
            byte[] bodyBytes = new byte[64];
            for (int i = 0; i < bodyBytes.length; i++) {
                bodyBytes[i] = (byte) (i * 7 + 3);
            }
            tb.put(bodyBytes);
            int tlen = tb.position();
            java.util.zip.CRC32 tcrc = new java.util.zip.CRC32();
            tcrc.update(tb.array(), 17, tlen - 17);
            tb.position(5);
            tb.putInt(tlen);
            tb.putLong(tcrc.getValue());
            byte[] tArr = tb.array();
            failed += check("W8 verify：自洽 buffer → OK",
                    (Integer) verifyM.invoke(null, tArr, tlen) == 0);
            // A 組實案簽名：crc 欄位歸零（len 正確、body 完整）——必須被抓到
            byte[] aSig = tArr.clone();
            for (int i = 9; i < 17; i++) {
                aSig[i] = 0;
            }
            failed += check("W8 verify：A 組簽名（crc=0、len 正確）→ CRC_MISMATCH",
                    (Integer) verifyM.invoke(null, aSig, tlen) == 3);
            // len 欄位竄改 → LEN_MISMATCH（等價 vanilla checkLength）
            byte[] lSig = tArr.clone();
            lSig[8] ^= 1;
            failed += check("W8 verify：len 欄位不符 → LEN_MISMATCH",
                    (Integer) verifyM.invoke(null, lSig, tlen) == 2);
            // header-only／截斷寫入 → MALFORMED（len<=17 時空 body CRC=0 會與 crc 欄位 0 假相符，須先擋）
            failed += check("W8 verify：len<=17 → MALFORMED（防空 body 假相符）",
                    (Integer) verifyM.invoke(null, tArr, 17) == 1
                    && (Integer) verifyM.invoke(null, tArr, 10) == 1);
            failed += check("W8 resolveMode：null→enforce、0→off、2→observe、垃圾→enforce",
                    (Integer) modeM.invoke(null, (Object) null) == 1
                    && (Integer) modeM.invoke(null, "0") == 0
                    && (Integer) modeM.invoke(null, "2") == 2
                    && (Integer) modeM.invoke(null, "junk") == 1);
            // safeWrite 本體執行級 smoke（codex 審查補強：先前只測 verify() 純函式，
            // 擋下/委派/不可寫三條決策路徑從未真正跑過）。測試 JVM 未設 property → enforce。
            Method swExec = cwg.getDeclaredMethod("safeWrite", int.class, int.class, java.nio.ByteBuffer.class);
            swExec.setAccessible(true);
            Class<?> ckCls = Class.forName("zombie.network.ChunkChecksum", true, patched);
            Method setCk = ckCls.getMethod("setChecksum", int.class, int.class, long.class);
            Method getCk = ckCls.getMethod("getChecksumIfExists", int.class, int.class);
            // (1) A 組簽名 buffer → 擋下：無例外返回、不觸 vanilla 寫入、checksum 歸零
            setCk.invoke(null, 4242, 4242, 424242L);
            java.nio.ByteBuffer badBb = java.nio.ByteBuffer.wrap(aSig.clone());
            badBb.position(tlen);
            boolean blockedQuietly;
            try {
                swExec.invoke(null, 4242, 4242, badBb);
                blockedQuietly = true;
            } catch (java.lang.reflect.InvocationTargetException e) {
                blockedQuietly = false;
            }
            failed += check("W8 safeWrite 執行：損毀 buffer 靜默擋下（log 基礎設施故障不外逃）且 checksum 歸零",
                    blockedQuietly && (Long) getCk.invoke(null, 4242, 4242) == 0L);
            // (2) null buffer → UNWRITABLE 拒寫（vanilla 會先 truncate 舊檔再炸＝毀檔，拒寫是唯一不毀檔選項）
            boolean nullQuietly;
            try {
                swExec.invoke(null, 4243, 4243, (Object) null);
                nullQuietly = true;
            } catch (java.lang.reflect.InvocationTargetException e) {
                nullQuietly = false;
            }
            failed += check("W8 safeWrite 執行：null buffer → UNWRITABLE 拒寫（不進 vanilla 的 truncate 路徑）",
                    nullQuietly);
            // (3) 委派證明改用結構斷言（初版「必拋」設計實測會把垃圾寫進本機真實
            //     Zomboid 存檔目錄——ZomboidFileSystem 在測試 JVM 能完整初始化並寫檔成功。
            //     絕不可在測試中執行 OK 路徑）。safeWrite 恰 5 個 vanilla 委派點：
            //     MODE_OFF／anomaly fail-open／OK-observe／OK-enforce（快照）／flagged-observe。
            MethodNode gSw = method(distJava, "zombie/mdc/ChunkWriteGuard", "safeWrite",
                    "(IILjava/nio/ByteBuffer;)V");
            failed += check("W8 safeWrite 結構：恰 5 個 IsoChunk.SafeWrite 委派點（off/anomaly/ok-obs/ok-enf/flag-obs）",
                    countExactCalls(gSw, Opcodes.INVOKESTATIC, "zombie/iso/IsoChunk", "SafeWrite",
                            "(IILjava/nio/ByteBuffer;)V") == 5);

            // ---- W9 存檔管線隔離：私有池行為（42.21 起 CRC 兩刀隨官方修正退役，只剩私有池）----
            Class<?> csi = Class.forName("zombie.mdc.ChunkSaveIsolation", true, patched);
            // 私有池行為：殼每次全新（不入池）、buffer 歸還後重用、release 後 bb=null，
            // 且全程不動 ClientChunkRequest 全域池（隔離的定義本身）。
            // 42.20.3 起 vanilla 刪除整個重試機制（Chunk.retriesCount／MAX_CHUNK_SEND_TRIES／
            // getRetryChunk 移除），且 getChunk 不再重置任何欄位（池回收殼帶舊值直接出租）。
            // 零值新殼安全的真正依據不是「欄位預設值＝vanilla 重置後狀態」，而是消費端
            // 先寫後讀：addLoadedJob 使用前寫 wx/wy、getByteBuffer 指派 bb，存檔路徑只讀
            // wx/wy/bb——該依據由下方 W9 vanilla 前提的 PUTFIELD census 釘死。
            Class<?> ccrClsR = Class.forName("zombie.network.ClientChunkRequest", true, patched);
            Class<?> chunkClsR = Class.forName("zombie.network.ClientChunkRequest$Chunk", true, patched);
            Method gcM = csi.getMethod("getChunk", ccrClsR);
            Method gbM = csi.getMethod("getByteBuffer", ccrClsR, chunkClsR);
            Method rcM = csi.getMethod("releaseChunk", ccrClsR, chunkClsR);
            java.lang.reflect.Field fcField = ccrClsR.getDeclaredField("freeChunks");
            fcField.setAccessible(true);
            java.lang.reflect.Field fbField = ccrClsR.getDeclaredField("freeBuffers");
            fbField.setAccessible(true);
            int fcBefore = ((java.util.Collection<?>) fcField.get(null)).size();
            int fbBefore = ((java.util.Collection<?>) fbField.get(null)).size();
            Object pc1 = gcM.invoke(null, new Object[]{null});
            gbM.invoke(null, new Object[]{null, pc1});
            java.lang.reflect.Field bbField = chunkClsR.getField("bb");
            Object pb1 = bbField.get(pc1);
            rcM.invoke(null, new Object[]{null, pc1});
            boolean bbNulled = bbField.get(pc1) == null;
            Object pc2 = gcM.invoke(null, new Object[]{null});
            boolean freshBbNull = bbField.get(pc2) == null;   // 新殼 bb 必為欄位預設 null（在 getByteBuffer 指派前探測）
            gbM.invoke(null, new Object[]{null, pc2});
            // codex 對抗審查修正：殼不入池（fresh shell）——vanilla update() 的無同步
            // savedChunks 可雙重 release，入池殼會被二次出租；buffer 則重用
            failed += check("W9 私有池：殼每次全新（不入池）、新殼 bb 預設 null、buffer 重用、歸還 null bb",
                    pb1 != null && bbNulled && pc2 != pc1
                    && freshBbNull && bbField.get(pc2) == pb1);
            failed += check("W9 隔離定義：私有池全程往返後 ClientChunkRequest 全域池計數不變",
                    ((java.util.Collection<?>) fcField.get(null)).size() == fcBefore
                    && ((java.util.Collection<?>) fbField.get(null)).size() == fbBefore);
            // 雙重歸還冪等：同一殼 release 兩次，buffer 只入池一次（synchronized 原子摘取）
            java.lang.reflect.Field privBufsField = csi.getDeclaredField("BUFFERS");
            privBufsField.setAccessible(true);
            int privBefore = ((java.util.Collection<?>) privBufsField.get(null)).size();
            rcM.invoke(null, new Object[]{null, pc2});
            rcM.invoke(null, new Object[]{null, pc2});
            failed += check("W9 雙重歸還冪等：release 兩次只入池一次、bb 維持 null",
                    ((java.util.Collection<?>) privBufsField.get(null)).size() == privBefore + 1
                    && bbField.get(pc2) == null);

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

            // W23 名額判定純函式：名次 < max 放行、超額拒絕、新帳號看既有數、PriorityLogin 豁免、max=1 邊界
            Class<?> gate = Class.forName("zombie.network.MdcAccountGate", true, patched);
            Class<?> rowCls = Class.forName("zombie.network.MdcAccountGate$Row", true, patched);
            java.lang.reflect.Constructor<?> rowCtor = rowCls.getDeclaredConstructor(String.class, boolean.class);
            rowCtor.setAccessible(true);
            Method allowed = gate.getDeclaredMethod("allowed", java.util.List.class, String.class, int.class);
            allowed.setAccessible(true);
            java.util.List<Object> two = java.util.List.of(rowCtor.newInstance("newest", false),
                    rowCtor.newInstance("older", false));
            java.util.List<Object> withAdmin = java.util.List.of(rowCtor.newInstance("newest", false),
                    rowCtor.newInstance("admin", true));
            boolean gateOk = (Boolean) allowed.invoke(null, two, "newest", 1)
                    && !(Boolean) allowed.invoke(null, two, "older", 1)
                    && (Boolean) allowed.invoke(null, two, "older", 2)
                    && !(Boolean) allowed.invoke(null, two, "brand-new", 2)
                    && (Boolean) allowed.invoke(null, java.util.List.of(), "brand-new", 1)
                    && (Boolean) allowed.invoke(null, withAdmin, "admin", 1)
                    && (Boolean) allowed.invoke(null, withAdmin, "third", 1);
            failed += check("W23 名額判定：最近登入前 max 名放行、其餘拒、新帳號依既有數、PriorityLogin 豁免", gateOk);

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

        // W48 動物聽覺掃描量測（docs/patches.md 2bk）。原版前提：伺服器分支掃全域 soundList（client 才用 chunk 清單），
        // 全 jar 只有 respondToSound 呼叫 getSoundAnimal——TIS 改成 chunk 清單或新增呼叫點時紅，量測要重新評估。
        String wsm = "zombie/WorldSoundManager";
        String soundDesc = "(L" + animal + ";)L" + wsm + "$WorldSound;";
        String animalSoundProbe = "zombie/mdc/AnimalSoundProbe";
        String animalSoundProbeDesc = "(L" + wsm + ";L" + animal + ";)L" + wsm + "$WorldSound;";
        MethodNode vGetSoundAnimal = methodFromJar(jar, wsm, "getSoundAnimal", soundDesc);
        MethodNode vSound = methodFromJar(jar, animal, "respondToSound", "()V");
        failed += check("W48 原版前提：getSoundAnimal 讀 GameServer.server 一次、全域 soundList 一次；全 jar 呼叫點恰 1",
                countExactFields(vGetSoundAnimal, Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z") == 1
                && countExactFields(vGetSoundAnimal, Opcodes.GETFIELD, wsm, "soundList", "Ljava/util/List;") == 1
                && jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, wsm, "getSoundAnimal", soundDesc) == 1
                && countExactCalls(vSound, Opcodes.INVOKEVIRTUAL, wsm, "getSoundAnimal", soundDesc) == 1);
        MethodNode probeGet = method(distJava, animalSoundProbe, "getSoundAnimal", animalSoundProbeDesc);
        String animalSoundIndex = "zombie/mdc/AnimalSoundIndex";
        failed += check("W48 改道：respondToSound 原呼叫歸零、改道恰 1、真指令數不變；probe 只經索引查詢一次、不直接呼叫原版、零 Rand",
                countExactCalls(sound, Opcodes.INVOKEVIRTUAL, wsm, "getSoundAnimal", soundDesc) == 0
                && countExactCalls(sound, Opcodes.INVOKESTATIC, animalSoundProbe, "getSoundAnimal", animalSoundProbeDesc) == 1
                && realInsnCount(sound) == realInsnCount(vSound)
                && countExactCalls(probeGet, Opcodes.INVOKESTATIC, animalSoundIndex, "getSoundAnimal", animalSoundProbeDesc) == 1
                && countExactCalls(probeGet, Opcodes.INVOKEVIRTUAL, wsm, "getSoundAnimal", soundDesc) == 0
                && probeGet.tryCatchBlocks.stream().allMatch(tcb -> "java/lang/RuntimeException".equals(tcb.type))
                && classNode(distJava, animalSoundProbe).methods.stream()
                        .mapToInt(m -> countCallsToOwner(m, "zombie/core/random/Rand")).sum() == 0);
        // W48-2 空間索引：索引照抄原版的逐聲音算式，原版 getSoundAnimal 任何改動（算式、條件、比較方向）都要重新核對等價，
        // 故釘整個方法的指令文字雜湊；soundList 只由建構子寫入一次且包裝緊接在 new ArrayList() 之後。
        MethodNode vWsmInit = methodFromJar(jar, wsm, "<init>", "()V");
        MethodNode pWsmInit = method(distJava, wsm, "<init>", "()V");
        String soundListWrapDesc = "(Ljava/util/List;)Ljava/util/List;";
        failed += check("W48-2 原版 getSoundAnimal 指令文字與核對時相同（sha256 " + GET_SOUND_ANIMAL_SHA.substring(0, 12) + "…）",
                sha256Hex(methodText(vGetSoundAnimal)).equals(GET_SOUND_ANIMAL_SHA));
        failed += check("W48-2 建構子唯一 PUTFIELD soundList：原版前為 new ArrayList()，手術後緊接 wrap、真指令恰 +1、移除 wrap 後逐字不變",
                jarWideFieldReadCensus(jar, Opcodes.PUTFIELD, wsm, "soundList") == 1
                && putWrapOk(vWsmInit, pWsmInit, wsm, "soundList", "java/util/ArrayList",
                        animalSoundIndex, "wrapSoundList", soundListWrapDesc)
                && wrapsStripToVanilla(vWsmInit, pWsmInit, new String[][]{{animalSoundIndex, "wrapSoundList", soundListWrapDesc}})
                && realInsnCount(pWsmInit) == realInsnCount(vWsmInit) + 1);
        MethodNode indexConsider = method(distJava, animalSoundIndex, "consider", "(I)V");
        MethodNode indexGet = method(distJava, animalSoundIndex, "getSoundAnimal", animalSoundProbeDesc);
        MethodNode indexLocked = classNode(distJava, animalSoundIndex).methods.stream()
                .filter(m -> m.name.equals("lockedQuery")).findFirst().orElseThrow();
        failed += check("W48-2 helper：逐聲音算式呼叫原版同一個 DistanceToSquared(FFFFFF)F 一次；查詢只接 RuntimeException、入口只有鎖的 handler；零 Rand",
                countExactCalls(indexConsider, Opcodes.INVOKESTATIC, "zombie/iso/IsoUtils", "DistanceToSquared", "(FFFFFF)F") == 1
                && countExactCalls(vGetSoundAnimal, Opcodes.INVOKESTATIC, "zombie/iso/IsoUtils", "DistanceToSquared", "(FFFFFF)F") == 1
                && indexLocked.tryCatchBlocks.stream().allMatch(tcb -> "java/lang/RuntimeException".equals(tcb.type))
                && indexGet.tryCatchBlocks.stream().allMatch(tcb -> tcb.type == null)
                && countOpcode(indexGet, Opcodes.MONITORENTER) == 1
                && classNode(distJava, animalSoundIndex).methods.stream()
                        .mapToInt(m -> countCallsToOwner(m, "zombie/core/random/Rand")).sum() == 0);
        // 索引依賴「聲音在清單中時位置／半徑／音量不被原地改寫、旗標只可能被關掉」。(1) 這些欄位的全 jar PUTFIELD 全部位於
        // WorldSound 的 init 多載內（逐方法加總等於全 jar 總數）；唯一例外是 BodyDamage.TriggerSneezeCough 在 addSound 後把
        // stressAnimals 設 false——consider() 每次即時重查旗標，關掉只會少一個候選，與原版一致。(2) 物件池呼叫數：全 jar getNew 2
        // （addSound、IsoAnimal 自己的複本）、release 3（update、IsoAnimal 複本兩處）。「init 只作用在剛取出的物件、update 先 remove 再
        // release、KillCell releaseAll 後立刻 clear」是 42.20.4 人工查核的前提，這裡只釘呼叫數；數字一變即紅，需重新人工核對。
        String wsound = wsm + "$WorldSound";
        ClassNode vWorldSound = classNodeFromJar(jar, wsound);
        boolean soundFieldsInitOnly = true;
        for (String f : new String[]{"x", "y", "z", "radius", "volume", "stresshumans", "stressAnimals"}) {
            String fDesc = f.startsWith("stress") ? "Z" : "I";
            int inInit = vWorldSound.methods.stream().filter(m -> m.name.equals("init"))
                    .mapToInt(m -> countExactFields(m, Opcodes.PUTFIELD, wsound, f, fDesc)).sum();
            int census = jarWideFieldReadCensus(jar, Opcodes.PUTFIELD, wsound, f);
            soundFieldsInitOnly &= inInit == 2 && census == (f.equals("stressAnimals") ? 3 : 2);
        }
        MethodNode sneeze = methodFromJar(jar, "zombie/characters/BodyDamage/BodyDamage", "TriggerSneezeCough", "()V");
        String soundRet = ")L" + wsound + ";";
        failed += check("W48-2 原版前提：WorldSound 位置／半徑／音量／旗標只在 init 多載內寫入（stressAnimals 另只有 TriggerSneezeCough 寫 false）；"
                        + "getNew 呼叫 2、release 呼叫 3",
                soundFieldsInitOnly
                && countExactFields(sneeze, Opcodes.PUTFIELD, wsound, "stressAnimals", "Z") == 1
                && prevReal(onlyPutField(sneeze, wsound, "stressAnimals")).getOpcode() == Opcodes.ICONST_0
                && jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, wsm, "getNew", "(" + soundRet) == 2
                && jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, wsm, "release", "(L" + wsound + ";" + soundRet) == 3);

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

        // 42.20：consistency log 位於 interface default method；派送 bridge 雖接管 PacketType 的
        // 最後派送，anticheat warn／sync 仍須保持原版，於下方 dispatch 斷言直接驗證。
        MethodNode inconsistent = method(distJava, "zombie/network/packets/INetworkPacket",
                "logInconsistentPacket", "(Lzombie/network/IConnection;Lzombie/network/PacketTypes$PacketType;)V");
        failed += check("consistency log 改道恰一次且原 warn 歸零",
                countExactCalls(inconsistent, Opcodes.INVOKESTATIC, "zombie/mdc/LogFilter", "warnFmt",
                        "(Lzombie/debug/DebugType;Ljava/lang/String;[Ljava/lang/Object;)V") == 1
                && countExactCalls(inconsistent, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType", "warn",
                        "(Ljava/lang/String;[Ljava/lang/Object;)V") == 0);

        // 退役（2026-09-02）：登入／join 卡頓量測的全部結構斷言（LoginPacket 三個 DB
        // wrapper、CreatePlayerPacket 四個重活、REJOIN_TOTAL／REJOIN_LOAD_CHARACTER）。
        // 歸因任務已完成、正式服 REJOIN_TOTAL 常態 5–13ms，量測刀隨斷言一併移除。
        // 詳見 docs/patches.md 2i；復活方式：從退役前最後一版 13650e1 取回（`git checkout 13650e1 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

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

        // ---- W5 容器環防崩潰守衛：2026-11-10 隨 42.22.2 官方修正退役（docs/patches.md 2q）----
        // 釘住官方修法：兩個方法都改成 `depth < 16` 的有界迴圈，零自身遞迴＝不會再 StackOverflowError。
        // 轉紅＝原版又回到遞迴寫法，W5 要復活（git checkout 3f79700 -- <PatchConfig 註解列的檔案>）。
        String icCls = "zombie/inventory/ItemContainer";
        String getCharDesc = "()Lzombie/characters/IsoGameCharacter;";
        String inCharDesc = "(Lzombie/characters/IsoGameCharacter;)Z";
        MethodNode vGetChar = methodFromJar(jar, icCls, "getCharacter", getCharDesc);
        MethodNode vInChar = methodFromJar(jar, icCls, "isInCharacterInventory", inCharDesc);
        failed += check("W5 退役前提：getCharacter／isInCharacterInventory 零自身遞迴、各有 bipush 16 迴圈上限",
                countExactCalls(vGetChar, Opcodes.INVOKEVIRTUAL, icCls, "getCharacter", getCharDesc) == 0
                && countExactCalls(vInChar, Opcodes.INVOKEVIRTUAL, icCls, "isInCharacterInventory", inCharDesc) == 0
                && countIntConst(vGetChar, 16) >= 1 && countIntConst(vInChar, 16) >= 1);

        // ---- W41 跨執行緒 processItems 寫入改道（W5-2 已於 2026-09-27 退役）----
        // 存在理由：BaseVehicle.setCurrentKey（VehiclesDB2 在 ServerPlayersVehicles 執行緒載車時呼叫）
        // 經 ItemContainer.addItem → AddItem 直接呼叫 IsoCell.addToProcessItems。
        String invItemCls = "zombie/inventory/InventoryItem";
        String piGuardCls = "zombie/mdc/ProcessItemsGuard";
        String addOneDesc = "(L" + invItemCls + ";)V";
        boolean keyViaAddItem = classNodeFromJar(jar, "zombie/vehicles/BaseVehicle").methods.stream()
                .filter(m -> m.name.equals("setCurrentKey"))
                .anyMatch(m -> countCallsToOwner(m, icCls) > 0);
        failed += check("W41 vanilla BaseVehicle.setCurrentKey 經 ItemContainer 加入鑰匙", keyViaAddItem);
        for (String w41Desc : new String[]{"(L" + invItemCls + ";)L" + invItemCls + ";",
                "(Ljava/lang/String;)L" + invItemCls + ";"}) {
            MethodNode vAdd = methodFromJar(jar, icCls, "AddItem", w41Desc);
            failed += check("W41 AddItem" + w41Desc + " 唯一 addToProcessItems 同形改道，其餘指令與 frames 保留",
                    countExactCalls(vAdd, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "addToProcessItems", addOneDesc) == 1
                    && methodText(vAdd).replace(
                            "INVOKEVIRTUAL zombie/iso/IsoCell.addToProcessItems " + addOneDesc,
                            "INVOKESTATIC " + piGuardCls + ".addToProcessItems (Lzombie/iso/IsoCell;L" + invItemCls + ";)V")
                            .equals(methodText(method(distJava, icCls, "AddItem", w41Desc))));
        }
        MethodNode vBlind = methodFromJar(jar, icCls, "AddItemBlind", "(L" + invItemCls + ";)L" + invItemCls + ";");
        failed += check("W41 AddItemBlind 不經 processItems、保持 vanilla",
                countExactCalls(vBlind, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "addToProcessItems", addOneDesc) == 0
                && methodText(vBlind).equals(methodText(method(distJava, icCls, "AddItemBlind",
                        "(L" + invItemCls + ";)L" + invItemCls + ";"))));


        // ---- W6 地圖格載入捕手（IsoChunk.doLoadGridsquare 的 addToWorld 改道）----
        String chunkCls = "zombie/iso/IsoChunk";
        String isoObjCls = "zombie/iso/IsoObject";
        String loadGuardCls = "zombie/mdc/ChunkLoadGuard";
        String loadGuardDesc = "(Lzombie/iso/IsoObject;)V";
        String movingObjCls = "zombie/iso/IsoMovingObject";
        String vehicleCls = "zombie/vehicles/BaseVehicle";
        String movingGuardDesc = "(Lzombie/iso/IsoMovingObject;)V";
        MethodNode vLoadSquare = methodFromJar(jar, chunkCls, "doLoadGridsquare", "()V");
        // vanilla 前提要列**全部三個** owner。初版只數 IsoObject，於是「僅此一處」這句話
        // 是 countExactCalls 的 owner 過濾造成的假象——兩道獨立審查都由此抓到 blocking：
        // IsoMovingObject 那處派送到同一個方法體，卻整個沒被守衛蓋到。
        failed += check("vanilla 前提：doLoadGridsquare 的三處 addToWorld（IsoObject／IsoMovingObject／BaseVehicle 各 1）",
                countExactCalls(vLoadSquare, Opcodes.INVOKEVIRTUAL, isoObjCls, "addToWorld", "()V") == 1
                && countExactCalls(vLoadSquare, Opcodes.INVOKEVIRTUAL, movingObjCls, "addToWorld", "()V") == 1
                && countExactCalls(vLoadSquare, Opcodes.INVOKEVIRTUAL, vehicleCls, "addToWorld", "()V") == 1
                && countExactCalls(vLoadSquare, Opcodes.INVOKESTATIC, loadGuardCls, "addToWorld",
                        loadGuardDesc) == 0);
        // IsoMovingObject 必須「不自己宣告 addToWorld」，否則 offset 947 派送到的就不是同一個
        // 方法體，本刀的等價性論證（包住它零額外語意風險）即失效
        failed += check("vanilla 前提：IsoMovingObject 未自行宣告 addToWorld（故派送到 IsoObject 同一方法體）",
                classNodeFromJar(jar, movingObjCls).methods.stream()
                        .noneMatch(m -> "addToWorld".equals(m.name) && "()V".equals(m.desc)));
        MethodNode pLoadSquare = method(distJava, chunkCls, "doLoadGridsquare", "()V");
        failed += check("W6 兩處改道各一次、原呼叫歸零、指令總數未變（1:1 替換）",
                countExactCalls(pLoadSquare, Opcodes.INVOKESTATIC, loadGuardCls, "addToWorld",
                        loadGuardDesc) == 1
                && countExactCalls(pLoadSquare, Opcodes.INVOKESTATIC, loadGuardCls, "addToWorld",
                        movingGuardDesc) == 1
                && countExactCalls(pLoadSquare, Opcodes.INVOKEVIRTUAL, isoObjCls, "addToWorld", "()V") == 0
                && countExactCalls(pLoadSquare, Opcodes.INVOKEVIRTUAL, movingObjCls, "addToWorld", "()V") == 0
                && pLoadSquare.instructions.size() == vLoadSquare.instructions.size());
        // BaseVehicle 是刻意留下的活凍結路徑（它自帶 addedToWorld 早退守衛、方法體含 parts／
        // engine 掛載）。把它釘成一個**可見的數字**而非過濾器假象：出現第四處即建置失敗，
        // 強迫下一個人重新做這個取捨，而不是無聲地繼承它。
        failed += check("W6 範圍宣告：BaseVehicle 那處刻意保持 vanilla（恰 1 處，多一處即重新決定）",
                countExactCalls(pLoadSquare, Opcodes.INVOKEVIRTUAL, vehicleCls, "addToWorld", "()V") == 1);
        // 排除 BaseVehicle 的**真正**理由是順序（審查更正了本節初稿的弱版理由）：
        // BaseVehicle.addToWorld(Z) 在 offset 47 就把 addedToWorld 設為 true，而 super 呼叫
        // （即拋出點）在 offset 56——所以拋出後旗標已是 true，下一圈 doLoadGridsquare 會走
        // offset 26 的早退，**每個 vehicle 實體最多只能拋一次**＝掉一個 frame 而非 114 分鐘活鎖。
        // IsoObject 沒有任何旗標（offset 0 就是 super），所以永遠拋——這才是兩者的差別。
        // 若 TIS 哪天把旗標賦值移到 super 之後（看起來像 bug fix 的改動），這條刻意排除就會
        // 無聲變成活的凍結路徑，而其他所有斷言全綠。故把順序本身釘成結構事實。
        // 未守衛的 callsite 是 addToWorld()V，但旗標邏輯在 (Z)V 裡——所以必須先釘住
        // ()V 真的委派到 (Z)V，否則整條斷言驗的是一個與該 callsite 無關的方法（codex 抓到）。
        MethodNode vehAdd0 = methodFromJar(jar, vehicleCls, "addToWorld", "()V");
        failed += check("W6 排除前提(1)：BaseVehicle.addToWorld()V 恰委派到 (Z)V",
                countExactCalls(vehAdd0, Opcodes.INVOKEVIRTUAL, vehicleCls, "addToWorld", "(Z)V") == 1);
        MethodNode vehAdd = methodFromJar(jar, vehicleCls, "addToWorld", "(Z)V");
        int vehFlagIdx = -1;
        int vehSuperIdx = -1;
        int vehFlagWrites = 0;
        int vehSupers = 0;
        boolean vehFlagStoresTrue = false;
        int vehIdx = 0;
        for (AbstractInsnNode in : vehAdd.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                    && vehicleCls.equals(f.owner) && "addedToWorld".equals(f.name)) {
                vehFlagWrites++;
                if (vehFlagIdx < 0) {
                    vehFlagIdx = vehIdx;
                    // 必須是存 true。存 false 一樣通過「順序」檢查，卻讓早退永遠不觸發，
                    // 於是排除前提失效而所有計數斷言全綠（codex 點名的 mutation 之一）。
                    AbstractInsnNode prev = prevReal(f);
                    vehFlagStoresTrue = prev != null && prev.getOpcode() == Opcodes.ICONST_1;
                }
            }
            if (in instanceof MethodInsnNode m && m.getOpcode() == Opcodes.INVOKESPECIAL
                    && movingObjCls.equals(m.owner) && "addToWorld".equals(m.name)) {
                vehSupers++;
                if (vehSuperIdx < 0) {
                    vehSuperIdx = vehIdx;
                }
            }
            vehIdx++;
        }
        // 唯一性是 dominance 的窮人版：只有一處寫入、只有一處 super，且寫入在前，
        // 就沒有「另一條分支繞過旗標直達 super」的空間。完整 CFG dominance 分析過重，
        // 此處刻意停在這個強度，殘留記於 docs/patches.md 2r。
        failed += check("W6 排除前提(2)：(Z)V 內 addedToWorld=true 唯一、super 唯一、且賦值在 super 之前",
                vehFlagWrites == 1 && vehSupers == 1 && vehFlagStoresTrue
                && vehFlagIdx >= 0 && vehSuperIdx >= 0 && vehFlagIdx < vehSuperIdx);
        // 位置錨：計數相同但「改到另一個 callsite」會讓所有計數檢查全綠。tile 迴圈的改道點
        // 後面緊接著 getSprite()（燃料判定），staticMovingObjects 迴圈沒有——用它釘住位置。
        MethodInsnNode w6Anchor = findExactCall(pLoadSquare, Opcodes.INVOKESTATIC, loadGuardCls,
                "addToWorld", loadGuardDesc);
        AbstractInsnNode afterW6 = w6Anchor == null ? null : nextReal(w6Anchor);
        while (afterW6 != null && !(afterW6 instanceof MethodInsnNode)) {
            afterW6 = nextReal(afterW6);
        }
        failed += check("W6 位置錨：IsoObject 改道點之後最近的呼叫是 getSprite()（釘住是 tile 迴圈而非屍體迴圈）",
                afterW6 instanceof MethodInsnNode m6
                && m6.getOpcode() == Opcodes.INVOKEVIRTUAL
                && isoObjCls.equals(m6.owner) && "getSprite".equals(m6.name)
                && "()Lzombie/iso/sprite/IsoSprite;".equals(m6.desc));
        // 原體保留：例外發生後 vanilla 仍要用同一個 local 讀 sprite／燃料，這些不能被動到。
        // vanilla 側同時斷言 > 0，否則 PZ 拿掉該呼叫後這條會退化成 0 == 0 的空檢查。
        int vSprite = countExactCalls(vLoadSquare, Opcodes.INVOKEVIRTUAL, isoObjCls,
                "getSprite", "()Lzombie/iso/sprite/IsoSprite;");
        int vFuel = countExactCalls(vLoadSquare, Opcodes.INVOKEVIRTUAL, isoObjCls,
                "getPipedFuelAmount", "()I");
        failed += check("W6 原體保留（getSprite 與 getPipedFuelAmount 呼叫數未變且非零）",
                vSprite > 0 && vFuel > 0
                && countExactCalls(pLoadSquare, Opcodes.INVOKEVIRTUAL, isoObjCls,
                        "getSprite", "()Lzombie/iso/sprite/IsoSprite;") == vSprite
                && countExactCalls(pLoadSquare, Opcodes.INVOKEVIRTUAL, isoObjCls,
                        "getPipedFuelAmount", "()I") == vFuel);
        // 負對照用「相對 vanilla 的差」而非絕對零：PZ 任何版本在 IsoChunk 其他方法新增一個
        // IsoObject.addToWorld 都會讓絕對值檢查誤報成「改道外洩」。
        ClassNode pChunk = classNode(distJava, chunkCls);
        ClassNode vChunk = classNodeFromJar(jar, chunkCls);
        failed += check("W6 負對照：改道恰好各發生一次，其餘呼叫端逐一未動（相對 vanilla 差值）",
                classWideCalls(pChunk, Opcodes.INVOKESTATIC, loadGuardCls, "addToWorld", loadGuardDesc) == 1
                && classWideCalls(pChunk, Opcodes.INVOKESTATIC, loadGuardCls, "addToWorld", movingGuardDesc) == 1
                && classWideCalls(pChunk, Opcodes.INVOKEVIRTUAL, isoObjCls, "addToWorld", "()V")
                        == classWideCalls(vChunk, Opcodes.INVOKEVIRTUAL, isoObjCls, "addToWorld", "()V") - 1
                && classWideCalls(pChunk, Opcodes.INVOKEVIRTUAL, movingObjCls, "addToWorld", "()V")
                        == classWideCalls(vChunk, Opcodes.INVOKEVIRTUAL, movingObjCls, "addToWorld", "()V") - 1);
        // 攔截型別必須釘在 exception table 上。舊版用 containsUtf8 找 VirtualMachineError 字串，
        // 但那兩個常數是 rethrowFatal（診斷 getter 用）帶進常數池的——把主 catch 放寬成
        // Throwable 也照樣通過，等於這條斷言完全擋不住它自稱要擋的那個 mutation。
        MethodNode guardBody = method(distJava, loadGuardCls, "addToWorld", loadGuardDesc);
        failed += check("W6 主 catch 型別鎖定為 RuntimeException（Error 必須穿透）",
                guardBody.tryCatchBlocks != null && guardBody.tryCatchBlocks.size() == 1
                && "java/lang/RuntimeException".equals(guardBody.tryCatchBlocks.get(0).type));
        // 抑噪 #10：IsoChunk.removeFromWorld 唯一 DebugLog.log(String) 同形改道；存在理由＝印完隨即再呼叫
        // BaseVehicle.removeFromWorld（正常卸載路徑），且乘客早退只比對 IsoPlayer.players[]（server 端不常駐）。
        MethodNode vChunkUnload = methodFromJar(jar, chunkCls, "removeFromWorld", "()V");
        String vChunkUnloadText = methodText(vChunkUnload);
        int unloadLog = vChunkUnloadText.indexOf("INVOKESTATIC zombie/debug/DebugLog.log (Ljava/lang/String;)V");
        failed += check("抑噪#10 vanilla：removeFromWorld 內 DebugLog.log 恰 1，其後再呼叫 BaseVehicle.removeFromWorld；"
                        + "乘客早退比對 IsoPlayer.players",
                countExactCalls(vChunkUnload, Opcodes.INVOKESTATIC, "zombie/debug/DebugLog", "log", "(Ljava/lang/String;)V") == 1
                && vChunkUnloadText.indexOf("INVOKEVIRTUAL " + vehicleCls + ".removeFromWorld ()V", unloadLog) > unloadLog
                && countExactFields(methodFromJar(jar, vehicleCls, "removeFromWorld", "()V"), Opcodes.GETSTATIC,
                        "zombie/characters/IsoPlayer", "players", "[Lzombie/characters/IsoPlayer;") == 1);
        failed += check("抑噪#10 同形改道到 LogFilter.log（W37 另改道兩個 removeFromWorld），其餘指令與 frames 保留",
                vChunkUnloadText.replace("INVOKESTATIC zombie/debug/DebugLog.log (Ljava/lang/String;)V",
                        "INVOKESTATIC zombie/mdc/LogFilter.log (Ljava/lang/String;)V")
                        .replace("INVOKEVIRTUAL zombie/iso/IsoMovingObject.removeFromWorld ()V",
                                "INVOKESTATIC zombie/mdc/AnimalSpawnGuard.chunkRemove (Lzombie/iso/IsoMovingObject;)V")
                        .equals(methodText(method(distJava, chunkCls, "removeFromWorld", "()V"))));

        // ---- W4-1 v2 chunk 供給併包（2026-09-07 復活，預設 observe；PlayerDownloadServer 三掛點）----
        String pdsCls = "zombie/network/PlayerDownloadServer";
        String packerCls = "zombie/mdc/ChunkRequestPacker";
        String packerDesc = "(Lzombie/network/PlayerDownloadServer;)V";
        String slcVanillaDesc = "(Lzombie/network/ClientChunkRequest$Chunk;Ljava/util/zip/CRC32;)V";
        String slcHelperDesc = "(Lzombie/iso/IsoChunk;" + slcVanillaDesc.substring(1);
        MethodNode vPdsUpdate = methodFromJar(jar, pdsCls, "update", "()V");
        MethodNode vPdsDedupe = methodFromJar(jar, pdsCls, "removeOlderDuplicateRequests", "()V");
        // vanilla 前提：三個同簽名 List.remove(I)（1 個 ccrWaiting、2 個 ccr.chunks）——正因無法以
        // owner/name/desc 區分才選 headCall 而非 redirect；1 個 dedupe 呼叫；1 個 SaveLoadedChunk
        // （主執行緒序列化點，本刀 1:1 改道量時）；零既存 helper 呼叫。數量漂移＝重新分析。
        failed += check("W4-1 vanilla 前提：update 有 3 個 List.remove(I)、1 個 dedupe、1 個 SaveLoadedChunk、零 packer 呼叫",
                countExactCalls(vPdsUpdate, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;") == 3
                && countExactCalls(vPdsUpdate, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "removeOlderDuplicateRequests", "()V") == 1
                && countExactCalls(vPdsUpdate, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunk",
                        "SaveLoadedChunk", slcVanillaDesc) == 1
                && countExactCalls(vPdsUpdate, Opcodes.INVOKESTATIC, packerCls, "packQueue", packerDesc) == 0
                && countExactCalls(vPdsUpdate, Opcodes.INVOKESTATIC, packerCls, "onUpdate", packerDesc) == 0);
        // **併包掛點必須在 ready 閘內**：dedupe 全 class 只被 update() 呼叫一次（＝閘內、vanilla
        // 去重之前）。閘外（update 頭部）與 worker 共用 bb/sb/bbw 與 cancelled HashSet——所以
        // update 頭部那個 headCall 只准計數、不准碰 ccrWaiting（helper 契約，見 onUpdate 註解）。
        failed += check("W4-1 vanilla 前提：removeOlderDuplicateRequests 全 class 僅被呼叫 1 次（update 的 ready 閘內）",
                classWideCalls(classNodeFromJar(jar, pdsCls), Opcodes.INVOKEVIRTUAL, pdsCls,
                        "removeOlderDuplicateRequests", "()V") == 1);
        MethodNode pPdsUpdate = method(distJava, pdsCls, "update", "()V");
        MethodNode pPdsDedupe = method(distJava, pdsCls, "removeOlderDuplicateRequests", "()V");
        failed += check("W4-1 v2 掛點：update 頭部 aload_0→onUpdate（閘外）＋dedupe 頭部 aload_0→packQueue（閘內），update 內零 packQueue",
                headCallOk(pPdsUpdate, packerCls, "onUpdate", packerDesc)
                && headCallOk(pPdsDedupe, packerCls, "packQueue", packerDesc)
                && countExactCalls(pPdsUpdate, Opcodes.INVOKESTATIC, packerCls, "packQueue", packerDesc) == 0);
        failed += check("W4-1 v2 主緒序列化改道：update 內 SaveLoadedChunk→saveLoadedChunk x1、原呼叫歸零、真指令 +2（僅 headCall）",
                countExactCalls(pPdsUpdate, Opcodes.INVOKESTATIC, packerCls, "saveLoadedChunk", slcHelperDesc) == 1
                && countExactCalls(pPdsUpdate, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunk",
                        "SaveLoadedChunk", slcVanillaDesc) == 0
                && realInsnCount(pPdsUpdate) == realInsnCount(vPdsUpdate) + 2);
        // 原體保留：update 的三個 List.remove(I) 與 dedupe 呼叫；dedupe 的空 ccr 回收（我們依賴它
        // 清掉被搬空的 ccr）與去重掃描未被破壞（真指令恰 +2＝只有 headCall）
        failed += check("W4-1 原體保留（update 三個 List.remove(I)＋dedupe 呼叫；dedupe remove/cancelDuplicateChunk 數＝vanilla、真指令 +2）",
                countExactCalls(pPdsUpdate, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;") == 3
                && countExactCalls(pPdsUpdate, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "removeOlderDuplicateRequests", "()V") == 1
                && countExactCalls(pPdsDedupe, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;")
                == countExactCalls(vPdsDedupe, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;")
                && countExactCalls(pPdsDedupe, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "cancelDuplicateChunk", "(Lzombie/network/ClientChunkRequest;II)Z")
                == countExactCalls(vPdsDedupe, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "cancelDuplicateChunk", "(Lzombie/network/ClientChunkRequest;II)Z")
                && realInsnCount(pPdsDedupe) == realInsnCount(vPdsDedupe) + 2);
        // helper 依賴的三個 public 成員契約（漂移＝建置失敗而非上線 IllegalAccessError）
        ClassNode vPds = classNodeFromJar(jar, pdsCls);
        ClassNode vCcr = classNodeFromJar(jar, "zombie/network/ClientChunkRequest");
        failed += check("W4-1 欄位契約：ccrWaiting/chunks/largeArea 皆 public 且型別未變",
                hasField(vPds, "ccrWaiting", "Ljava/util/List;")
                && hasField(vCcr, "chunks", "Ljava/util/List;")
                && hasField(vCcr, "largeArea", "Z"));
        // v2 批次超過 vanilla 20 的前提：20 只是 parse／pending 的分割門檻（isChunksFilled 恰一個
        // bipush 20），消費端 update()／WorkerThread.sendArray 依 chunks.size() 迴圈、方法內零 20
        // 常數。TIS 若在消費端加硬上限，這條會紅＝重評 BATCH。
        failed += check("W4-1 v2 批次超過 20 的前提：isChunksFilled 恰一個 bipush 20；update／sendArray 內零 20 常數且各 ≥1 個 List.size()",
                countIntConst(methodFromJar(jar, "zombie/network/ClientChunkRequest", "isChunksFilled", "()Z"), 20) == 1
                && countIntConst(vPdsUpdate, 20) == 0
                && countExactCalls(vPdsUpdate, Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I") >= 1
                && countIntConst(methodFromJar(jar, pdsCls + "$WorkerThread", "sendArray",
                        "(Lzombie/network/ClientChunkRequest;)V"), 20) == 0
                && countExactCalls(methodFromJar(jar, pdsCls + "$WorkerThread", "sendArray",
                        "(Lzombie/network/ClientChunkRequest;)V"), Opcodes.INVOKEINTERFACE,
                        "java/util/List", "size", "()I") >= 1);
        // helper 例外紀律：onUpdate／packQueue 的 catch 是 Throwable（fatal 由 anomaly() 重拋），
        // saveLoadedChunk 零 catch（例外必須透傳給 vanilla 的 catch→sendNotRequired）
        MethodNode packerSave = method(distJava, packerCls, "saveLoadedChunk", slcHelperDesc);
        failed += check("W4-1 v2 helper：saveLoadedChunk 零 catch handler（只有 finally）、兩處委派 SaveLoadedChunk（off 直通＋量測）",
                (packerSave.tryCatchBlocks == null
                        || packerSave.tryCatchBlocks.stream().allMatch(tcb -> tcb.type == null))
                && countExactCalls(packerSave, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunk",
                        "SaveLoadedChunk", slcVanillaDesc) == 2);

        // ---- W43 地面物品過期清除時機同步（helper 內呼叫，無新手術）----
        // 存在理由兩條：client 按序號刪地面物件（set 取 getObjectIndex）、IsoGridSquare.load 的
        // 丟棄條件——helper 逐項照抄，TIS 改動條件時必須紅燈重對，否則兩邊清單又會錯位。
        String expiryCls = "zombie/mdc/WorldItemExpirySync";
        MethodNode vSqLoad = methodFromJar(jar, "zombie/iso/IsoGridSquare", "load", "(Ljava/nio/ByteBuffer;IZ)V");
        failed += check("W43 vanilla 前提：IsoGridSquare.load 丟棄條件 worldItemRemovalListContains=4、getWorldAgeHours=1、isIgnoreRemoveSandbox=1、Item.getObsolete=1、dropTime 讀 2、hoursForWorldItemRemoval 讀 2、blacklistToggle 讀 4",
                countExactCalls(vSqLoad, Opcodes.INVOKEVIRTUAL, "zombie/SandboxOptions",
                        "worldItemRemovalListContains", "(Ljava/lang/String;)Z") == 4
                && countExactCalls(vSqLoad, Opcodes.INVOKEVIRTUAL, "zombie/GameTime", "getWorldAgeHours", "()D") == 1
                && countExactCalls(vSqLoad, Opcodes.INVOKEVIRTUAL, "zombie/iso/objects/IsoWorldInventoryObject",
                        "isIgnoreRemoveSandbox", "()Z") == 1
                && countExactCalls(vSqLoad, Opcodes.INVOKEVIRTUAL, "zombie/scripting/objects/Item", "getObsolete", "()Z") == 1
                && countInstanceFieldReads(vSqLoad, "zombie/iso/objects/IsoWorldInventoryObject", "dropTime") == 2
                && countInstanceFieldReads(vSqLoad, "zombie/SandboxOptions", "hoursForWorldItemRemoval") == 2
                && countInstanceFieldReads(vSqLoad, "zombie/SandboxOptions", "itemRemovalListBlacklistToggle") == 4);
        failed += check("W43 vanilla 前提：RemoveItemFromSquarePacket.set 以 getObjectIndex 定位（按序號刪＝錯位即幽靈）",
                countExactCalls(methodFromJar(jar, "zombie/network/packets/RemoveItemFromSquarePacket", "set",
                        "(Lzombie/iso/IsoObject;)V"), Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoObject",
                        "getObjectIndex", "()I") == 1);
        failed += check("W43 helper：saveLoadedChunk 呼叫 beforeSend 恰 1；purge 經 GameServer.RemoveItemFromMap 恰 1",
                countExactCalls(packerSave, Opcodes.INVOKESTATIC, expiryCls, "beforeSend", "(Lzombie/iso/IsoChunk;)V") == 1
                && countExactCalls(method(distJava, expiryCls, "purge", "(Lzombie/iso/IsoChunk;D)I"),
                        Opcodes.INVOKESTATIC, "zombie/network/GameServer", "RemoveItemFromMap", "(Lzombie/iso/IsoObject;)I") == 1);

        // ---- W44 物品搬移失敗即時回報（TransactionManager.update）----
        String txRejectCls = "zombie/core/MdcTransactionReject";
        String txSetStateDesc = "(Lzombie/core/Transaction$TransactionState;)V";
        MethodNode vTxUpdate = methodFromJar(jar, "zombie/core/TransactionManager", "update", "()V");
        MethodNode pTxUpdate = method(distJava, "zombie/core/TransactionManager", "update", "()V");
        failed += check("W44 vanilla 前提：update 內 setState=3、PacketType.send 恰 1（只有 Done 回送；TIS 補 Reject 回送時紅＝撤刀）",
                countExactCalls(vTxUpdate, Opcodes.INVOKEVIRTUAL, "zombie/core/Transaction", "setState", txSetStateDesc) == 3
                && countExactCalls(vTxUpdate, Opcodes.INVOKEVIRTUAL, "zombie/network/PacketTypes$PacketType",
                        "send", "(Lzombie/network/IConnection;)V") == 1);
        failed += check("W44 手術後：setState 改道 x3、原呼叫歸零、真指令不變",
                countExactCalls(pTxUpdate, Opcodes.INVOKESTATIC, txRejectCls, "setState",
                        "(Lzombie/core/Transaction;Lzombie/core/Transaction$TransactionState;)V") == 3
                && countExactCalls(pTxUpdate, Opcodes.INVOKEVIRTUAL, "zombie/core/Transaction", "setState", txSetStateDesc) == 0
                && realInsnCount(pTxUpdate) == realInsnCount(vTxUpdate));
        failed += check("W44 helper：setState 委派原版 Transaction.setState 恰 1、回送 send 恰 1",
                countExactCalls(method(distJava, txRejectCls, "setState",
                        "(Lzombie/core/Transaction;Lzombie/core/Transaction$TransactionState;)V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/core/Transaction", "setState", txSetStateDesc) == 1
                && countExactCalls(method(distJava, txRejectCls, "setState",
                        "(Lzombie/core/Transaction;Lzombie/core/Transaction$TransactionState;)V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/network/PacketTypes$PacketType",
                        "send", "(Lzombie/network/IConnection;)V") == 1);

        failed += check("PatchInfo 版本指紋已生成且四個常數非空（server）",
                patchInfoOk(distJava, "server"));

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

        // 42.21.0 官方已修：removeGlassAttachments 改反向迴圈，2l GlassAttachmentGuard 結構斷言隨 patch 退役。

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

        // ---- W12 車輛 DB chunk 索引守衛：2026-11-10 隨 42.22.2 官方修正退役（docs/patches.md 2z）----
        // 釘住官方修法：VehicleBuffer.set 零 vehicle.chunk 讀取、wx/wy 由 fastfloor 推導（x/y 各一次
        // fastfloor(F)、FDIV x2），且 SQLStore.init 開檔時呼叫 repairStoredVehicleChunks。轉紅＝重估 W12 復活。
        String vbCls = "zombie/vehicles/VehiclesDB2$VehicleBuffer";
        MethodNode vVbSet = methodFromJar(jar, vbCls, "set", "(L" + vehCls + ";)V");
        failed += check("W12 退役前提：VehicleBuffer.set 零 vehicle.chunk 讀取、wx/wy 各一個 putfield 且有 FDIV x2",
                countInstanceFieldReads(vVbSet, vehCls, "chunk") == 0
                && countInsns(vVbSet, in -> in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                        && f.owner.equals(vbCls) && (f.name.equals("wx") || f.name.equals("wy"))) == 2
                && countInsns(vVbSet, in -> in.getOpcode() == Opcodes.FDIV) == 2
                && countExactCalls(vVbSet, Opcodes.INVOKESTATIC, "zombie/core/math/PZMath", "fastfloor", "(F)I") >= 2);
        failed += check("W12 退役前提：SQLStore.init 開檔時呼叫 repairStoredVehicleChunks",
                countExactCalls(methodFromJar(jar, "zombie/vehicles/VehiclesDB2$SQLStore", "init",
                        "(Lgnu/trove/set/hash/TIntHashSet;Lgnu/trove/set/hash/TIntHashSet;)V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/vehicles/VehiclesDB2$SQLStore", "repairStoredVehicleChunks", "()V") == 1);

        // ---- W7 朝向暫存執行緒隔離：2026-11-10 隨 42.22.2 官方修正退役（docs/patches.md 2s）----
        // 釘住官方修法：方法內零 tempVector2_2 讀取、每次 new Vector2。轉紅＝重估 W7 復活。
        String igcCls = "zombie/characters/IsoGameCharacter";
        MethodNode vFwd = methodFromJar(jar, igcCls, "setForwardDirectionFromIsoDirection", "()V");
        failed += check("W7 退役前提：setForwardDirectionFromIsoDirection 零 getstatic tempVector2_2、new Vector2 x1",
                countFieldReads(vFwd, igcCls, "tempVector2_2") == 0
                && countNew(vFwd, "zombie/iso/Vector2") == 1);
        ClassNode vIgcNode = classNodeFromJar(jar, igcCls);   // W34／W88 段共用
        ClassNode pIgcNode = classNode(distJava, igcCls);

        // ---- W8 chunk 寫入閘（IsoChunk.Save(Z) ×2＋ServerChunkLoader.writeChunk ×1）----
        // 42.22.2：伺服器存檔的 SafeWrite 從 SaveLoadedTask.save 搬進新的 private static writeChunk，
        // SaveLoadedTask／SaveUnloadedTask 的 save() 都在 IsoChunk.WriteLock 內呼叫它。
        String w8IcCls = "zombie/iso/IsoChunk";
        String cwgCls = "zombie/mdc/ChunkWriteGuard";
        String swDesc = "(IILjava/nio/ByteBuffer;)V";
        String sclCls = "zombie/network/ServerChunkLoader";
        String sltCls = "zombie/network/ServerChunkLoader$SaveLoadedTask";
        String wcDesc = "(Lzombie/network/ClientChunkRequest$Chunk;)V";
        String ckCls = "zombie/network/ChunkChecksum";
        // vanilla 前提：兩個手術方法內的 SafeWrite 呼叫數與 checksum 互動形狀
        MethodNode vSaveB = methodFromJar(jar, w8IcCls, "Save", "(Z)V");
        failed += check("W8 vanilla 前提：IsoChunk.Save(Z) 內 SafeWrite x2、setChecksum x1",
                countExactCalls(vSaveB, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 2
                && countExactCalls(vSaveB, Opcodes.INVOKESTATIC, ckCls, "setChecksum", "(IIJ)V") == 1);
        MethodNode vWc = methodFromJar(jar, sclCls, "writeChunk", wcDesc);
        failed += check("W8 vanilla 前提：writeChunk 內 SafeWrite x1、setChecksum x1，且兩個存檔 task 的 save() 各呼叫 writeChunk x1",
                countExactCalls(vWc, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1
                && countExactCalls(vWc, Opcodes.INVOKESTATIC, ckCls, "setChecksum", "(IIJ)V") == 1
                && countExactCalls(methodFromJar(jar, sltCls, "save", "()V"), Opcodes.INVOKESTATIC, sclCls, "writeChunk", wcDesc) == 1
                && countExactCalls(methodFromJar(jar, "zombie/network/ServerChunkLoader$SaveUnloadedTask", "save", "()V"),
                        Opcodes.INVOKESTATIC, sclCls, "writeChunk", wcDesc) == 1);
        // 順序鎖：IsoChunk.Save(Z) 先 setChecksum 再 SafeWrite（helper 歸零最後生效）；42.22.2 的 writeChunk
        // 反過來先 SafeWrite 再 setChecksum(本次 buffer 的 CRC)——擋下時 checksum 記成「從沒寫進磁碟的壞 buffer」，
        // 下一輪乾淨序列化 bytes 必不同照樣重寫（相同就是同一份壞資料，dedup 跳過＝等同再擋一次）。
        // PZ 再改順序或改 dedup 依據即建置失敗，重新分析。
        failed += check("W8 順序鎖：Save(Z) setChecksum 先於 SafeWrite；writeChunk SafeWrite 先於 setChecksum、dedup 比 getChecksumIfExists",
                firstCallIndex(vSaveB, Opcodes.INVOKESTATIC, ckCls, "setChecksum", "(IIJ)V")
                        < firstCallIndex(vSaveB, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc)
                && firstCallIndex(vWc, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc)
                        < firstCallIndex(vWc, Opcodes.INVOKESTATIC, ckCls, "setChecksum", "(IIJ)V")
                && firstCallIndex(vWc, Opcodes.INVOKESTATIC, ckCls, "getChecksumIfExists", "(II)J")
                        < firstCallIndex(vWc, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc));
        // 全 jar census（總數）＋逐類分佈（堵「舊點消失＋新點出現」互相抵銷的 false-green）
        failed += check("W8 census：全 jar SafeWrite 呼叫點恰 5 個（新增即代表有未設閘的寫檔路徑）",
                jarWideCallsiteCensus(jar, w8IcCls, "SafeWrite", swDesc) == 5);
        failed += check("W8 census 分佈：IsoChunk=2／ChunkSaveWorker=1／WorldGenerate=1／ServerChunkLoader=1",
                classWideCalls(classNodeFromJar(jar, w8IcCls), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 2
                && classWideCalls(classNodeFromJar(jar, "zombie/iso/ChunkSaveWorker"), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1
                && classWideCalls(classNodeFromJar(jar, "zombie/iso/WorldGenerate"), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1
                && classWideCalls(classNodeFromJar(jar, sclCls), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1);
        // 排除論證的錨 1：ChunkSaveWorker 唯一入列點 AddHotSave 被 GameServer.server 閘住，
        // 且方向鎖定（getstatic server 緊接 ifne＝server 為真即跳離；codex 審查補強）
        MethodNode vIcmUpd = methodFromJar(jar, "zombie/iso/IsoChunkMap", "updateInternal", "()V");
        failed += check("W8 排除前提：IsoChunkMap.updateInternal 有 AddHotSave x1 且 GameServer.server→ifne 方向鎖",
                countExactCalls(vIcmUpd, Opcodes.INVOKEVIRTUAL, "zombie/iso/ChunkSaveWorker",
                        "AddHotSave", "(Lzombie/iso/IsoChunk;)V") == 1
                && existsFieldReadThenJump(vIcmUpd, "zombie/network/GameServer", "server", Opcodes.IFNE));
        // 格式前提：helper 硬編 offset 的語境鎖（非僅常數存在）——17 必須是 CRC32.update 的
        // offset 引數、5 必須是 position() 的引數；版本常數恰 1（codex 審查補強）。42.22.2 版本 249→251，
        // 該方法其餘指令與 42.21 逐條相同（header：debug byte、ver int、len int、crc long 版面不變）。
        MethodNode vSaveBuf = methodFromJar(jar, w8IcCls, "Save",
                "(Ljava/nio/ByteBuffer;Ljava/util/zip/CRC32;Z)Ljava/nio/ByteBuffer;");
        failed += check("W8 格式前提：251 恰 1、17→CRC32.update 語境、5→ByteBuffer.position 語境",
                countIntConst(vSaveBuf, 251) == 1
                // 17 與 update 之間隔著 len-1-4-4-8 的四次 isub 展開（javap 實測 9 條真指令）
                && existsConstThenCall(vSaveBuf, 17, "java/util/zip/CRC32", "update", 12)
                && existsConstThenCall(vSaveBuf, 5, "java/nio/ByteBuffer", "position", 2));
        // 手術後：改道到位、原呼叫歸零
        MethodNode pSaveB = method(distJava, w8IcCls, "Save", "(Z)V");
        failed += check("W8 手術後：Save(Z) 改道 x2、原 SafeWrite 呼叫歸零",
                countExactCalls(pSaveB, Opcodes.INVOKESTATIC, cwgCls, "safeWrite", swDesc) == 2
                && countExactCalls(pSaveB, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 0);
        MethodNode pWc = method(distJava, sclCls, "writeChunk", wcDesc);
        failed += check("W8 手術後：writeChunk 改道 x1、原呼叫歸零、其餘指令不變",
                countExactCalls(pWc, Opcodes.INVOKESTATIC, cwgCls, "safeWrite", swDesc) == 1
                && countExactCalls(pWc, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 0
                && methodText(vWc).replace("INVOKESTATIC " + w8IcCls + ".SafeWrite", "INVOKESTATIC " + cwgCls + ".safeWrite")
                        .equals(methodText(pWc)));
        failed += check("W8 負對照：ServerChunkLoader 除 writeChunk 外零 safeWrite 改道",
                classNode(distJava, sclCls).methods.stream()
                        .filter(m -> !(m.name.equals("writeChunk") && m.desc.equals(wcDesc)))
                        .allMatch(m -> countCallsToOwner(m, cwgCls) == 0));
        // 負對照：SafeWrite 本體必須保持 vanilla（helper 委派回它——改道到它自己＝無限遞迴）。
        // 排除條件鎖到精確簽名 Save(Z)V——其他 Save 多載也在受檢範圍（codex 審查修正）
        ClassNode pIcNode = classNode(distJava, w8IcCls);
        boolean safeWriteClean = pIcNode.methods.stream()
                .filter(m -> !(m.name.equals("Save") && m.desc.equals("(Z)V")))
                .allMatch(m -> countExactCalls(m, Opcodes.INVOKESTATIC, cwgCls, "safeWrite", swDesc) == 0);
        failed += check("W8 負對照：IsoChunk 除 Save(Z)V 外零改道（含其他 Save 多載；SafeWrite 本體無遞迴）",
                safeWriteClean);

        // ---- W9 存檔管線隔離（42.21 起只剩私有池：addLoadedJob 租借＋SaveLoadedTask.release 歸還）----
        String sctCls = "zombie/network/ServerChunkLoader$SaveChunkThread";
        String csiCls = "zombie/mdc/ChunkSaveIsolation";
        String ccrRef = "zombie/network/ClientChunkRequest";
        String chunkRef = "zombie/network/ClientChunkRequest$Chunk";
        String getChunkDesc = "()L" + chunkRef + ";";
        String chunkArgDesc = "(L" + chunkRef + ";)V";
        String addLoadedDesc = "(Lzombie/iso/IsoChunk;)V";
        MethodNode vAdd = methodFromJar(jar, sctCls, "addLoadedJob", addLoadedDesc);
        failed += check("W9 vanilla 前提：addLoadedJob 內 getChunk/getByteBuffer/releaseChunk 各 x1",
                countExactCalls(vAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getChunk", getChunkDesc) == 1
                && countExactCalls(vAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getByteBuffer", chunkArgDesc) == 1
                && countExactCalls(vAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 1);
        // 退役前提（2026-09-28，42.21.0）：W9 之一／之二（共用 CRC32 → ThreadLocal）因官方修正退役。
        // 釘住官方修法：addLoadedJob、writeChunk（42.22.2 起存檔 dedup CRC 在這裡）各自 new CRC32（區域變數），且
        // ServerChunkLoader／SaveChunkThread／SaveLoadedTask／IsoChunk 沒有任何 CRC32 型別欄位。
        // TIS 若退回共用實例＝這條紅，重新評估是否復活兩刀（git checkout 8d2bee8）。
        String crcDesc = "Ljava/util/zip/CRC32;";
        boolean noSharedCrc = true;
        for (String c : new String[]{sclCls, sctCls, sltCls, w8IcCls}) {
            for (var fn : classNodeFromJar(jar, c).fields) {
                if (crcDesc.equals(fn.desc)) {
                    noSharedCrc = false;
                }
            }
        }
        failed += check("W9 退役前提：addLoadedJob／writeChunk 各 new CRC32 x1，存檔管線四類零 CRC32 欄位",
                noSharedCrc
                && countNew(vAdd, "java/util/zip/CRC32") == 1
                && countNew(vWc, "java/util/zip/CRC32") == 1);
        // 之三存在理由：SaveChunkThread.update() 仍以欄位 savedChunks 逐一 release、全程無鎖
        // （主迴圈與 shutdown hook 並行 updateSaved 可雙重 release），且 ClientChunkRequest
        // 的 freeChunks／freeBuffers 仍是 static 全域池。TIS 若加鎖或改成 per-instance 池＝這條紅，
        // 重新評估之三是否可退役。
        MethodNode vSctUpdate = methodFromJar(jar, sctCls, "update", "()V");
        boolean updateUnlocked = (vSctUpdate.access & Opcodes.ACC_SYNCHRONIZED) == 0;
        for (AbstractInsnNode in : vSctUpdate.instructions) {
            if (in.getOpcode() == Opcodes.MONITORENTER) {
                updateUnlocked = false;
            }
        }
        ClassNode vCcrNode = classNodeFromJar(jar, ccrRef);
        boolean ccrPoolsStatic = vCcrNode.fields.stream()
                .filter(fn -> fn.name.equals("freeChunks") || fn.name.equals("freeBuffers"))
                .filter(fn -> (fn.access & Opcodes.ACC_STATIC) != 0)
                .count() == 2;
        failed += check("W9 之三存在理由：SaveChunkThread.update 無鎖且讀 savedChunks、ClientChunkRequest 兩池仍為 static",
                updateUnlocked
                && countInstanceFieldReads(vSctUpdate, sctCls, "savedChunks") >= 1
                && countCalls(vSctUpdate, "zombie/network/ServerChunkLoader$SaveTask", "release") == 1
                && ccrPoolsStatic);
        // 零值新殼安全的機械依據（42.20.3 起 vanilla getChunk 不再重置任何欄位）：
        // addLoadedJob 對租出殼「先寫後讀」——wx/wy 各恰一次 PUTFIELD，且兩者都
        // 位於 getByteBuffer 呼叫之前。PZ 若讓存檔路徑讀取未寫欄位或改寫此順序，
        // 這條會失敗強制重新分析，而不是讓私有池新殼與 vanilla 回收殼靜默分歧。
        int wxWrites = 0;
        int wyWrites = 0;
        int wxPutIdx = -1;
        int wyPutIdx = -1;
        int gbCallIdx = -1;
        int addIdx = 0;
        for (AbstractInsnNode in : vAdd.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                    && chunkRef.equals(f.owner)) {
                if ("wx".equals(f.name)) {
                    wxWrites++;
                    if (wxPutIdx < 0) {
                        wxPutIdx = addIdx;
                    }
                } else if ("wy".equals(f.name)) {
                    wyWrites++;
                    if (wyPutIdx < 0) {
                        wyPutIdx = addIdx;
                    }
                }
            } else if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && ccrRef.equals(mi.owner) && "getByteBuffer".equals(mi.name)
                    && chunkArgDesc.equals(mi.desc) && gbCallIdx < 0) {
                gbCallIdx = addIdx;
            }
            addIdx++;
        }
        failed += check("W9 vanilla 前提：addLoadedJob 先寫後讀（Chunk.wx/wy PUTFIELD 各 x1、兩者皆早於 getByteBuffer）",
                wxWrites == 1 && wyWrites == 1
                && wxPutIdx >= 0 && wyPutIdx >= 0 && gbCallIdx >= 0
                && wxPutIdx < gbCallIdx && wyPutIdx < gbCallIdx);
        MethodNode vRel = methodFromJar(jar, sltCls, "release", "()V");
        // 42.22.2 的 release 變 synchronized、先設 isReleased，但不讀它就歸還＝雙重 release 仍會雙重入池
        failed += check("W9 vanilla 前提：SaveLoadedTask.release 內 releaseChunk x1，且不讀 isReleased（雙重 release 不擋）",
                countExactCalls(vRel, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 1
                && countInstanceFieldReads(vRel, sltCls, "isReleased") == 0);
        // 序列化者清冊：全 jar SaveLoadedChunk 呼叫者逐類分佈釘死（codex 修正：只比總數會讓
        // 「舊點消失＋新點出現」互抵通過）。addLoadedJob＝本刀隔離；PlayerDownloadServer.update 用
        // per-connection CRC32 且僅主緒＝分析上安全；42.22.2 新增 SaveUnloadedTask.serialize（卸載存檔不再經
        // IsoChunk.Save(Z)），它向全域池租殼，但 serialize／release 同為 synchronized、release 歸還後把
        // chunkData 設 null＝恰好歸還一次，不需要私有池。
        String slcDesc = "(L" + chunkRef + ";Ljava/util/zip/CRC32;)V";
        String sutCls = "zombie/network/ServerChunkLoader$SaveUnloadedTask";
        MethodNode vSutRel = methodFromJar(jar, sutCls, "release", "()V");
        failed += check("W9 census：全 jar SaveLoadedChunk 呼叫點恰 3＝SaveChunkThread 1／PlayerDownloadServer 1／SaveUnloadedTask 1，"
                        + "且 SaveUnloadedTask.release synchronized、歸還後清 chunkData",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunk", "SaveLoadedChunk", slcDesc) == 3
                && classWideCalls(classNodeFromJar(jar, sctCls), Opcodes.INVOKEVIRTUAL,
                        "zombie/iso/IsoChunk", "SaveLoadedChunk", slcDesc) == 1
                && classWideCalls(classNodeFromJar(jar, "zombie/network/PlayerDownloadServer"), Opcodes.INVOKEVIRTUAL,
                        "zombie/iso/IsoChunk", "SaveLoadedChunk", slcDesc) == 1
                && classWideCalls(classNodeFromJar(jar, sutCls), Opcodes.INVOKEVIRTUAL,
                        "zombie/iso/IsoChunk", "SaveLoadedChunk", slcDesc) == 1
                && (vSutRel.access & Opcodes.ACC_SYNCHRONIZED) != 0
                && countInsns(vSutRel, in -> in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                        && f.owner.equals(sutCls) && f.name.equals("chunkData")) == 1);
        // 手術後：三呼叫改道到位、原呼叫歸零；addLoadedJob 對 helper 的呼叫恰 3（CRC 改道已退役）
        MethodNode pAdd = method(distJava, sctCls, "addLoadedJob", addLoadedDesc);
        failed += check("W9 手術後：addLoadedJob 三呼叫改道（helper 呼叫恰 3）、原 invokevirtual 歸零",
                countCallsToOwner(pAdd, csiCls) == 3
                && countExactCalls(pAdd, Opcodes.INVOKESTATIC, csiCls, "getChunk",
                        "(L" + ccrRef + ";)L" + chunkRef + ";") == 1
                && countExactCalls(pAdd, Opcodes.INVOKESTATIC, csiCls, "getByteBuffer",
                        "(L" + ccrRef + ";L" + chunkRef + ";)V") == 1
                && countExactCalls(pAdd, Opcodes.INVOKESTATIC, csiCls, "releaseChunk",
                        "(L" + ccrRef + ";L" + chunkRef + ";)V") == 1
                && countExactCalls(pAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getChunk", getChunkDesc) == 0
                && countExactCalls(pAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getByteBuffer", chunkArgDesc) == 0
                && countExactCalls(pAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 0);
        failed += check("W9 負對照：SaveLoadedTask.save 零 ChunkSaveIsolation 呼叫",
                countCallsToOwner(method(distJava, sltCls, "save", "()V"), csiCls) == 0);
        MethodNode pRel = method(distJava, sltCls, "release", "()V");
        failed += check("W9 手術後：release() releaseChunk 改道 x1、原呼叫歸零",
                countExactCalls(pRel, Opcodes.INVOKESTATIC, csiCls, "releaseChunk",
                        "(L" + ccrRef + ";L" + chunkRef + ";)V") == 1
                && countExactCalls(pRel, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 0);
        // 負對照：SaveChunkThread 其餘方法（addUnloadedJob/run/update/saveNow/saveLater/quit）零波及
        ClassNode pSctNode = classNode(distJava, sctCls);
        boolean sctClean = pSctNode.methods.stream()
                .filter(m -> !(m.name.equals("addLoadedJob") && m.desc.equals(addLoadedDesc)))
                .allMatch(m -> countCallsToOwner(m, csiCls) == 0);
        failed += check("W9 負對照：SaveChunkThread 除 addLoadedJob 外零 ChunkSaveIsolation 呼叫", sctClean);
        // helper off 路徑保真：kill switch（-Dmdc.chunkSaveIsolation=0）的委派路徑必須是原味
        // vanilla 呼叫——三個池 helper 各含恰 1 個對應 invokevirtual
        failed += check("W9 helper off 路徑：getChunk/getByteBuffer/releaseChunk 各含 vanilla 委派 x1",
                countExactCalls(method(distJava, csiCls, "getChunk", "(L" + ccrRef + ";)L" + chunkRef + ";"),
                        Opcodes.INVOKEVIRTUAL, ccrRef, "getChunk", getChunkDesc) == 1
                && countExactCalls(method(distJava, csiCls, "getByteBuffer", "(L" + ccrRef + ";L" + chunkRef + ";)V"),
                        Opcodes.INVOKEVIRTUAL, ccrRef, "getByteBuffer", chunkArgDesc) == 1
                && countExactCalls(method(distJava, csiCls, "releaseChunk", "(L" + ccrRef + ";L" + chunkRef + ";)V"),
                        Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 1);

        // ---- 抑噪：GameServer.sendToxicBuilding 的 log 改道（只攔 log，封包段不得被動到）----
        String gsCls = "zombie/network/GameServer";
        String dlCls = "zombie/debug/DebugLog";
        String dlTypeDesc = "(Lzombie/debug/DebugType;Ljava/lang/String;)V";
        MethodNode vToxic = methodFromJar(jar, gsCls, "sendToxicBuilding", "(IIZ)V");
        // vanilla 前提：方法內恰一個 DebugLog.log(DebugType,String)，且封包段確實存在
        //（endPacket 是「真的在送封包」的錨——若 TIS 改寫成不送封包，抑噪的前提說明就過時了）
        failed += check("抑噪 vanilla 前提：sendToxicBuilding 恰一個 DebugLog.log(DebugType,String)＋封包段存在",
                countExactCalls(vToxic, Opcodes.INVOKESTATIC, dlCls, "log", dlTypeDesc) == 1
                && countCalls(vToxic, "zombie/network/PacketTypes$PacketType", "send") == 1);
        MethodNode pToxic = method(distJava, gsCls, "sendToxicBuilding", "(IIZ)V");
        failed += check("抑噪手術後：log 改道 x1、原 DebugLog.log 歸零、封包段逐項未變",
                countExactCalls(pToxic, Opcodes.INVOKESTATIC, "zombie/mdc/LogFilter", "logType", dlTypeDesc) == 1
                && countExactCalls(pToxic, Opcodes.INVOKESTATIC, dlCls, "log", dlTypeDesc) == 0
                && countCalls(pToxic, "zombie/network/PacketTypes$PacketType", "send")
                        == countCalls(vToxic, "zombie/network/PacketTypes$PacketType", "send")
                && countCalls(pToxic, "zombie/network/PacketTypes$PacketType", "doPacket")
                        == countCalls(vToxic, "zombie/network/PacketTypes$PacketType", "doPacket")
                && countCalls(pToxic, "zombie/core/network/ByteBufferWriter", "putInt")
                        == countCalls(vToxic, "zombie/core/network/ByteBufferWriter", "putInt")
                && countCalls(pToxic, "zombie/core/network/ByteBufferWriter", "putBoolean")
                        == countCalls(vToxic, "zombie/core/network/ByteBufferWriter", "putBoolean")
                && realInsnCount(pToxic) == realInsnCount(vToxic));
        // 負對照：GameServer 全 class 的其他 DebugLog.log(DebugType,String) 一律保持 vanilla。
        // 全 class 有 21 個同 descriptor 呼叫點，class-wide 誤改會誤攔另外 20 個。
        ClassNode vGs = classNodeFromJar(jar, gsCls);
        ClassNode pGs = classNode(distJava, gsCls);
        int vGsLog = classWideCalls(vGs, Opcodes.INVOKESTATIC, dlCls, "log", dlTypeDesc);
        failed += check("抑噪負對照：GameServer 其餘 DebugLog.log(DebugType,String) 全數保持 vanilla（21→20）",
                vGsLog == 21
                && classWideCalls(pGs, Opcodes.INVOKESTATIC, dlCls, "log", dlTypeDesc) == vGsLog - 1
                && classWideCalls(pGs, Opcodes.INVOKESTATIC, "zombie/mdc/LogFilter", "logType", dlTypeDesc) == 1);

        // 退役（2026-09-02）：食材重量記憶化（InventoryItem.getExtraItemsWeight 的
        // CreateItem 改道）的全部 vanilla 前提、負對照與 helper 契約斷言。observe 實測
        // 收益僅 0.06–0.18%，「永不啟用 on」已定案，刀與斷言一併移除。
        // 詳見 docs/patches.md 2w；復活方式：從退役前最後一版 13650e1 取回（`git checkout 13650e1 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

        // ---- W10 卡讀條根治（NetTimedAction.parse 例外攔截；W10-A 已於 42.21 官方修正後退役）----
        String ntaCls = "zombie/core/NetTimedAction";
        String ntaPktCls = "zombie/network/packets/NetTimedActionPacket";
        String ntaGuardCls = "zombie/mdc/NetTimedActionGuard";
        String luaCaller = "se/krka/kahlua/integration/LuaCaller";
        String pcDesc = "(Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;[Ljava/lang/Object;)"
                + "Lse/krka/kahlua/integration/LuaReturn;";
        String pcHelperDesc = "(L" + luaCaller + ";Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;"
                + "[Ljava/lang/Object;)Lse/krka/kahlua/integration/LuaReturn;";
        String ntaParseDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        String bbwDesc = "(Lzombie/core/network/ByteBufferWriter;)V";
        String psDesc = "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V";
        String tsCls = "Lzombie/core/Transaction$TransactionState;";

        // B 刀的錨：parse 內恰一個 protectedCall（其餘同名呼叫在 getDuration/start/stop/perform，
        // 不在本方法；method-scope 鎖定＋下面的 class-wide 差值負對照一起堵住外洩）
        MethodNode vNtaParse = methodFromJar(jar, ntaCls, "parse", ntaParseDesc);
        failed += check("W10 vanilla 前提：NetTimedAction.parse 內 LuaCaller.protectedCall 恰 1 處",
                countExactCalls(vNtaParse, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc) == 1);
        // B 刀不新增失敗語意——它讓 vanilla 既有的 `action = null; return;` 真正被走到。
        // 那條路徑必須存在（ACONST_NULL → PUTFIELD action），否則本刀的前提就沒了。
        boolean vanillaNullsAction = false;
        for (AbstractInsnNode in = vNtaParse.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.ACONST_NULL && nextReal(in) instanceof FieldInsnNode fi
                    && fi.getOpcode() == Opcodes.PUTFIELD && ntaCls.equals(fi.owner) && "action".equals(fi.name)) {
                vanillaNullsAction = true;
                break;
            }
        }
        failed += check("W10 vanilla 前提：parse 內存在 action=null 失敗路徑（B 刀的著力點，非新增語意）",
                vanillaNullsAction);

        // D 刀／B 刀的 Reject 出口：42.21 原版 processServer 對 act（slot 3）setState 後也以 act 序列化
        // （42.20.4 用 this.write 送出 Request state，當時由 W10-A 補正；官方修正後 A 刀退役）。
        // TIS 退回 this.write 時本條紅——那時 action=null 的 Reject 又會帶錯 state，需重估 A 刀。
        MethodNode vNtaProcess = methodFromJar(jar, ntaPktCls, "processServer", psDesc);
        int writeOnAct = 0;
        int writeTotal = 0;
        for (AbstractInsnNode in = vNtaProcess.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!(in instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !"write".equals(mi.name) || !bbwDesc.equals(mi.desc)) {
                continue;
            }
            writeTotal++;
            AbstractInsnNode receiver = prevReal(prevReal(in));   // receiver, bbw, write
            if (ntaCls.equals(mi.owner) && receiver instanceof VarInsnNode v
                    && v.getOpcode() == Opcodes.ALOAD && v.var != 0) {
                writeOnAct++;
            }
        }
        int setStateOnAct = 0;
        for (AbstractInsnNode in = vNtaProcess.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!(in instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !ntaCls.equals(mi.owner) || !"setState".equals(mi.name) || !("(" + tsCls + ")V").equals(mi.desc)) {
                continue;
            }
            AbstractInsnNode receiver = prevReal(prevReal(in));   // receiver, getstatic state, setState
            if (receiver instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var != 0) {
                setStateOnAct++;
            }
        }
        failed += check("W10 vanilla 前提（Reject 出口，W10-A 退役依據）：processServer 的 write 兩處與 setState 兩處"
                + " receiver 皆為 act（非 this）＝ action=null 的初始回覆帶 Reject state",
                writeTotal == 2 && writeOnAct == 2 && setStateOnAct == 2);

        // 手術後：兩處改道、原呼叫歸零、真指令數不變（1:1 同形替換）
        MethodNode pNtaParse = method(distJava, ntaCls, "parse", ntaParseDesc);
        failed += check("W10/D 手術後：parse headCall＋protectedCall 改道 x1、原呼叫歸零、真指令恰 +2",
                headCallOk(pNtaParse, ntaGuardCls, "beginParse", "(L" + ntaCls + ";)V")
                && countExactCalls(pNtaParse, Opcodes.INVOKESTATIC, ntaGuardCls, "protectedCall", pcHelperDesc) == 1
                && countExactCalls(pNtaParse, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc) == 0
                && realInsnCount(pNtaParse) == realInsnCount(vNtaParse) + 2);
        MethodNode pNtaProcess = method(distJava, ntaPktCls, "processServer", psDesc);
        failed += check("W10-A 退役：processServer 不經 NetTimedActionGuard、兩個 act.write 原樣保留",
                countCalls(pNtaProcess, ntaGuardCls, "write") == 0
                && countExactCalls(pNtaProcess, Opcodes.INVOKEVIRTUAL, ntaCls, "write", bbwDesc) == 2);

        // helper 契約 1：catch 型別鎖定 RuntimeException——Error（SOE／OOM）必須穿透，
        // 與 W6 同紀律。放寬成 Throwable 會把致命錯誤變成「靜默 reject」。
        MethodNode guardCall = method(distJava, ntaGuardCls, "protectedCall", pcHelperDesc);
        failed += check("W10 helper 契約：protectedCall 的 catch 恰一個且型別為 RuntimeException（Error 穿透）",
                guardCall.tryCatchBlocks != null && guardCall.tryCatchBlocks.size() == 1
                && "java/lang/RuntimeException".equals(guardCall.tryCatchBlocks.get(0).type));
        // helper 契約 2：委派回原方法恰 2 處（kill switch 直通＋try 內正常路徑），且 caller 只被呼叫這兩次
        failed += check("W10 helper 契約：protectedCall 委派原呼叫恰 2 處（off 直通＋on 正常路徑）",
                countExactCalls(guardCall, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc) == 2);

        // 負對照（相對 vanilla 差值，避免絕對零在 PZ 新增同名呼叫時誤報）：
        // NetTimedAction 只少一個 protectedCall（getDuration/start/stop/perform 逐一未動）。
        failed += check("W10 負對照：NetTimedAction 全 class protectedCall 恰少 1（其餘方法未動）",
                classWideCalls(classNode(distJava, ntaCls), Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc)
                        == classWideCalls(classNodeFromJar(jar, ntaCls), Opcodes.INVOKEVIRTUAL, luaCaller,
                                "protectedCall", pcDesc) - 1
                && classWideCalls(classNode(distJava, ntaCls), Opcodes.INVOKESTATIC, ntaGuardCls,
                        "protectedCall", pcHelperDesc) == 1);

        // W10-D：只在本次 NetTimedAction.parse 內處理參數解析失敗，不改共用 table decoder。
        String netTableCls = "zombie/network/PZNetKahluaTableImpl";
        String argsLoadDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        String argsLoadHelperDesc = "(L" + netTableCls + ";Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        failed += check("W10-D vanilla：parse 的 load 恰 1 且先於 ctor，解析沒有自身 catch",
                countExactCalls(vNtaParse, Opcodes.INVOKEVIRTUAL, netTableCls, "load", argsLoadDesc) == 1
                && firstCallIndex(vNtaParse, Opcodes.INVOKEVIRTUAL, netTableCls, "load", argsLoadDesc)
                        < firstCallIndex(vNtaParse, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc)
                && vNtaParse.tryCatchBlocks.isEmpty());
        failed += check("W10-D：loadArgs 改道恰 1、原 load 歸零、beginParse 先於 loadArgs",
                countExactCalls(pNtaParse, Opcodes.INVOKESTATIC, ntaGuardCls, "loadArgs", argsLoadHelperDesc) == 1
                && countExactCalls(pNtaParse, Opcodes.INVOKEVIRTUAL, netTableCls, "load", argsLoadDesc) == 0
                && firstCallIndex(pNtaParse, Opcodes.INVOKESTATIC, ntaGuardCls, "beginParse", "(L" + ntaCls + ";)V")
                        < firstCallIndex(pNtaParse, Opcodes.INVOKESTATIC, ntaGuardCls, "loadArgs", argsLoadHelperDesc));
        MethodNode gLoadArgs = method(distJava, ntaGuardCls, "loadArgs", argsLoadHelperDesc);
        failed += check("W10-D：loadArgs 只攔 RuntimeException，Error 不降級",
                !gLoadArgs.tryCatchBlocks.isEmpty()
                && gLoadArgs.tryCatchBlocks.stream().allMatch(t -> "java/lang/RuntimeException".equals(t.type)));
        // W51（docs/patches.md 2bo）：共用 table decoder 只為觀測重新出貨。存在理由：原版 type 17 查不到 online ID
        // 就把 null 交給呼叫端、不留紀錄（TIS 補上紀錄或拒絕時紅＝重估）。手術：唯一 AnimalID.parse 同形改道，
        // 原呼叫在 helper 的 try 之外；其餘方法逐指令不變——W10-D2 的猜測改道不得回來。
        String animalIdCls = "zombie/network/fields/character/AnimalID";
        String typedLoadDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;B)Ljava/lang/Object;";
        String parseAnimalIdDesc = "(L" + animalIdCls + ";" + argsLoadDesc.substring(1);
        MethodNode vTypedLoad = methodFromJar(jar, netTableCls, "load", typedLoadDesc);
        AbstractInsnNode vAnimalParse = null;
        for (AbstractInsnNode in : vTypedLoad.instructions) {
            if (isCall(in, Opcodes.INVOKEVIRTUAL, animalIdCls, "parse", argsLoadDesc)) vAnimalParse = in;
        }
        AbstractInsnNode vAfterParse = vAnimalParse == null ? null : nextReal(nextReal(vAnimalParse));
        failed += check("W51 vanilla：AnimalID.parse 只以 AnimalInstanceManager.get 查 online ID；load(…B) 恰 1 處 parse，之後直接回 getAnimal()",
                methodText(methodFromJar(jar, animalIdCls, "parse", argsLoadDesc)).contains(
                        "INVOKEVIRTUAL zombie/popman/animal/AnimalInstanceManager.get (S)Lzombie/characters/animals/IsoAnimal;")
                && countExactCalls(vTypedLoad, Opcodes.INVOKEVIRTUAL, animalIdCls, "parse", argsLoadDesc) == 1
                && isCall(vAfterParse, Opcodes.INVOKEVIRTUAL, animalIdCls, "getAnimal", "()Lzombie/characters/animals/IsoAnimal;")
                && nextReal(vAfterParse).getOpcode() == Opcodes.ARETURN);
        failed += check("W51 load(…B) 唯一 AnimalID.parse 同形改道 NetTimedActionGuard.parseAnimalId，其餘指令與 frames 保留",
                methodText(vTypedLoad).replace("INVOKEVIRTUAL " + animalIdCls + ".parse " + argsLoadDesc,
                        "INVOKESTATIC " + ntaGuardCls + ".parseAnimalId " + parseAnimalIdDesc)
                        .equals(methodText(method(distJava, netTableCls, "load", typedLoadDesc))));
        int netTableDiffs = 0;
        for (MethodNode original : classNodeFromJar(jar, netTableCls).methods) {
            if ((original.name + original.desc).equals("load" + typedLoadDesc)) continue;
            if (!methodText(original).equals(methodText(method(distJava, netTableCls, original.name, original.desc)))) netTableDiffs++;
        }
        failed += check("W51 PZNetKahluaTableImpl 其餘方法逐指令不變（W10-D2 的 loadComponent 猜測改道不得回來）", netTableDiffs == 0);
        MethodNode gParseAnimal = method(distJava, ntaGuardCls, "parseAnimalId", parseAnimalIdDesc);
        failed += check("W51 helper 契約：parseAnimalId 恰委派 1 次原 parse，且不在任何 try 內（解析例外照原版外傳）",
                countExactCalls(gParseAnimal, Opcodes.INVOKEVIRTUAL, animalIdCls, "parse", argsLoadDesc) == 1
                && callsInsideTryRange(gParseAnimal, Opcodes.INVOKEVIRTUAL, animalIdCls, "parse", argsLoadDesc) == 0);

        // D1 診斷只能回讀已確定由 loadComponent 消費的 long＋short；不是猜 payload 內容。
        String componentLoadDesc = "(Ljava/nio/ByteBuffer;Lzombie/network/IConnection;)Lzombie/entity/Component;";
        MethodNode componentLoad = methodFromJar(jar, netTableCls, "loadComponent", componentLoadDesc);
        AbstractInsnNode[] componentWire = firstReal(componentLoad, 14);
        failed += check("W10-D 診斷：loadComponent 恰先讀 long/short，再查 entity/type 並解參考，無其他 buffer 操作",
                realInsnCount(componentLoad) == 14 && componentLoad.tryCatchBlocks.isEmpty()
                && isVar(componentWire[0], Opcodes.ALOAD, 0)
                && isCall(componentWire[1], Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "getLong", "()J")
                && isVar(componentWire[2], Opcodes.LSTORE, 2)
                && isVar(componentWire[3], Opcodes.ALOAD, 0)
                && isCall(componentWire[4], Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "getShort", "()S")
                && isVar(componentWire[5], Opcodes.ISTORE, 4)
                && isVar(componentWire[6], Opcodes.LLOAD, 2)
                && isCall(componentWire[7], Opcodes.INVOKESTATIC, "zombie/entity/GameEntityManager",
                        "GetEntity", "(J)Lzombie/entity/GameEntity;")
                && isVar(componentWire[8], Opcodes.ASTORE, 5)
                && isVar(componentWire[9], Opcodes.ALOAD, 5)
                && isVar(componentWire[10], Opcodes.ILOAD, 4)
                && isCall(componentWire[11], Opcodes.INVOKESTATIC, "zombie/entity/ComponentType",
                        "FromId", "(S)Lzombie/entity/ComponentType;")
                && isCall(componentWire[12], Opcodes.INVOKEVIRTUAL, "zombie/entity/GameEntity",
                        "getComponent", "(Lzombie/entity/ComponentType;)Lzombie/entity/Component;")
                && componentWire[13].getOpcode() == Opcodes.ARETURN);
        failed += check("W10-D 診斷：parse 配置原版 table，上拋鏈沒有 catch/finally 改動 reader",
                countNew(vNtaParse, netTableCls) == 1
                && methodFromJar(jar, netTableCls, "load", argsLoadDesc).tryCatchBlocks.isEmpty()
                && methodFromJar(jar, netTableCls, "load",
                        "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;B)Ljava/lang/Object;")
                        .tryCatchBlocks.isEmpty());

        // ---- W11 動物聲音排序活鎖捕手 ----
        String basCls = "zombie/characters/BaseAnimalSoundManager";
        String asgCls = "zombie/mdc/AnimalSortGuard";
        String sortDesc = "(Ljava/util/Comparator;)V";
        String sortHelperDesc = "(Ljava/util/ArrayList;Ljava/util/Comparator;)V";
        // vanilla 前提 1：update()V 內恰 1 個 ArrayList.sort callsite
        MethodNode vBasUpdate = methodFromJar(jar, basCls, "update", "()V");
        failed += check("W11 vanilla 前提：update()V 內 ArrayList.sort 恰 1 處",
                countExactCalls(vBasUpdate, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "sort", sortDesc) == 1);
        // vanilla 前提 2（活鎖機制的錨）：sort 在 clear 之前——TIS 若把 clear 移進 finally
        // 或移到 sort 前，活鎖機制消失，本刀該重新評估（此條會紅提醒）。
        failed += check("W11 vanilla 前提：sort 先於 characters.clear()（活鎖機制的順序錨）",
                firstCallIndex(vBasUpdate, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "sort", sortDesc)
                        < firstCallIndex(vBasUpdate, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V"));
        // 手術後：改道 x1、原 sort 歸零、真指令數不變（1:1 同形替換）
        MethodNode pBasUpdate = method(distJava, basCls, "update", "()V");
        failed += check("W11 手術後：update 改道 x1、原 sort 歸零、真指令數不變",
                countExactCalls(pBasUpdate, Opcodes.INVOKESTATIC, asgCls, "sort", sortHelperDesc) == 1
                && countExactCalls(pBasUpdate, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "sort", sortDesc) == 0
                && realInsnCount(pBasUpdate) == realInsnCount(vBasUpdate));
        // helper 契約：catch 恰 1 個且型別鎖 IllegalArgumentException（其他 RuntimeException
        // 與 Error 必須穿透——放寬成 RuntimeException 會把未知錯誤降級成安靜的順序退化）
        MethodNode guardSort = method(distJava, asgCls, "sort", sortHelperDesc);
        failed += check("W11 helper 契約：catch 恰 1 個且型別為 IllegalArgumentException",
                guardSort.tryCatchBlocks != null && guardSort.tryCatchBlocks.size() == 1
                && "java/lang/IllegalArgumentException".equals(guardSort.tryCatchBlocks.get(0).type));
        // helper 契約：委派原 sort 恰 2 處（kill switch 直通＋try 內正常路徑）
        failed += check("W11 helper 契約：sort 委派恰 2 處（off 直通＋on 正常路徑）",
                countExactCalls(guardSort, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "sort", sortDesc) == 2);
        // 負對照：全 class 只少這一個 sort callsite
        failed += check("W11 負對照：BaseAnimalSoundManager 全 class ArrayList.sort 恰少 1、改道恰 1",
                classWideCalls(classNode(distJava, basCls), Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "sort", sortDesc)
                        == classWideCalls(classNodeFromJar(jar, basCls), Opcodes.INVOKEVIRTUAL, "java/util/ArrayList",
                                "sort", sortDesc) - 1
                && classWideCalls(classNode(distJava, basCls), Opcodes.INVOKESTATIC, asgCls, "sort", sortHelperDesc) == 1);

        // ---- W13 動物同步範圍對齊 ----
        String asmCls = "zombie/popman/animal/AnimalSynchronizationManager";
        String argCls = "zombie/mdc/AnimalRelevancyGate";
        String udpCls = "zombie/core/raknet/UdpConnection";
        String sendDesc = "(L" + udpCls + ";ZLjava/util/HashSet;)V";
        String relDesc = "(FFF)Z";
        String relHelperDesc = "(L" + udpCls + ";FFF)Z";
        MethodNode vSend = methodFromJar(jar, asmCls, "sendUpdateToClient", sendDesc);
        MethodNode vOnScreen = methodFromJar(jar, asmCls, "isAnimalOnScreen",
                "(L" + udpCls + ";Lzombie/characters/animals/IsoAnimal;)Z");
        // vanilla 前提 1：sendUpdateToClient 內恰 1 個 RelevantTo callsite（offset 242）
        failed += check("W13 vanilla 前提：sendUpdateToClient 內 RelevantTo 恰 1 處",
                countExactCalls(vSend, Opcodes.INVOKEVIRTUAL, udpCls, "RelevantTo", relDesc) == 1);
        // vanilla 前提 2（缺陷的結構事實）：relevancy 半徑由 getRelevantRange 導出，
        // 而 client 實際載入範圍是 chunkGridWidth ——vanilla 在這個方法裡從不讀後者。
        // TIS 若改用 chunkGridWidth（或把常數對齊）本條會紅，提醒重估／撤刀。
        failed += check("W13 vanilla 前提：半徑源自 getRelevantRange 恰 1、且不讀 getChunkGridWidth",
                countExactCalls(vSend, Opcodes.INVOKEVIRTUAL, udpCls, "getRelevantRange", "()B") == 1
                && countExactCalls(vSend, Opcodes.INVOKEVIRTUAL, udpCls, "getChunkGridWidth", "()I") == 0);
        // vanilla 前提 3：isAnimalOnScreen 有同形的 (relevantRange-2)*10 幾何但不經 RelevantTo
        // ——確認 redirect 不會誤改 800/1000ms 節拍判定（constChange 的取捨理由見 patches.md 2aa）。
        failed += check("W13 vanilla 前提：isAnimalOnScreen 不呼叫 RelevantTo（redirect 不會誤改節拍）",
                countExactCalls(vOnScreen, Opcodes.INVOKEVIRTUAL, udpCls, "RelevantTo", relDesc) == 0
                && countExactCalls(vOnScreen, Opcodes.INVOKEVIRTUAL, udpCls, "getRelevantRange", "()B") == 1);
        // 手術後：改道 x1、原 RelevantTo 歸零、真指令數不變（1:1 同形替換）
        MethodNode pSend = method(distJava, asmCls, "sendUpdateToClient", sendDesc);
        failed += check("W13 手術後：sendUpdateToClient 改道 x1、原 RelevantTo 歸零、真指令數不變",
                countExactCalls(pSend, Opcodes.INVOKESTATIC, argCls, "relevantTo", relHelperDesc) == 1
                && countExactCalls(pSend, Opcodes.INVOKEVIRTUAL, udpCls, "RelevantTo", relDesc) == 0
                && realInsnCount(pSend) == realInsnCount(vSend));
        // 手術後：節拍判定完全未被碰到（isAnimalOnScreen 逐指令與 vanilla 相同）
        MethodNode pOnScreen = method(distJava, asmCls, "isAnimalOnScreen",
                "(L" + udpCls + ";Lzombie/characters/animals/IsoAnimal;)Z");
        failed += check("W13 手術後：isAnimalOnScreen 未被改動（真指令數與 getRelevantRange 皆同）",
                realInsnCount(pOnScreen) == realInsnCount(vOnScreen)
                && countExactCalls(pOnScreen, Opcodes.INVOKEVIRTUAL, udpCls, "getRelevantRange", "()B") == 1
                && countExactCalls(pOnScreen, Opcodes.INVOKESTATIC, argCls, "relevantTo", relHelperDesc) == 0);
        // helper 契約：半徑必須來自 server 保存的 client-reported chunk-grid width，
        // 且三條 vanilla 委派路徑都在
        MethodNode gateEntry = method(distJava, argCls, "relevantTo", relHelperDesc);
        MethodNode gateAligned = method(distJava, argCls, "alignedRadius", "(L" + udpCls + ";)F");
        MethodNode gateVanilla = method(distJava, argCls, "vanilla", "(L" + udpCls + ";FFF)Z");
        failed += check("W13 helper 契約：對齊半徑讀 getChunkGridWidth 恰 1（幾何唯一來源）",
                countExactCalls(gateAligned, Opcodes.INVOKEVIRTUAL, udpCls, "getChunkGridWidth", "()I") == 1);
        failed += check("W13 helper 契約：入口 3 條 vanilla 委派＋2 次夾過半徑判定，vanilla() 內恰 1 次原呼叫",
                countExactCalls(gateEntry, Opcodes.INVOKESTATIC, argCls, "vanilla", "(L" + udpCls + ";FFF)Z") == 3
                && countExactCalls(gateEntry, Opcodes.INVOKEVIRTUAL, udpCls, "RelevantTo", relDesc) == 2
                && countExactCalls(gateVanilla, Opcodes.INVOKEVIRTUAL, udpCls, "RelevantTo", relDesc) == 1);
        // helper 契約：載具排除必須存在且真的走 vanilla（W13 blocking 修正的核心——
        // IsoChunkMap.ProcessChunkPos 在載具內把 chunk 中心前移，server 無從得知，
        // 任何以玩家為中心的半徑在載具情境都會同時誤擋前側、誤放後側）
        MethodNode gateVehicle = method(distJava, argCls, "anyPlayerInVehicle", "(L" + udpCls + ";)Z");
        failed += check("W13 helper 契約：載具排除讀 getPlayerAt 與 getVehicle 各恰 1",
                countExactCalls(gateVehicle, Opcodes.INVOKEVIRTUAL, udpCls, "getPlayerAt",
                        "(I)Lzombie/characters/IsoPlayer;") == 1
                && countExactCalls(gateVehicle, Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoPlayer",
                        "getVehicle", "()Lzombie/vehicles/BaseVehicle;") == 1);
        failed += check("W13 helper 契約：入口在夾取前呼叫載具排除恰 1 次",
                countExactCalls(gateEntry, Opcodes.INVOKESTATIC, argCls, "anyPlayerInVehicle",
                        "(L" + udpCls + ";)Z") == 1);

        // 負對照：全 class 只少這一個 RelevantTo callsite
        failed += check("W13 負對照：AnimalSynchronizationManager 全 class RelevantTo 恰少 1、改道恰 1",
                classWideCalls(classNode(distJava, asmCls), Opcodes.INVOKEVIRTUAL, udpCls, "RelevantTo", relDesc)
                        == classWideCalls(classNodeFromJar(jar, asmCls), Opcodes.INVOKEVIRTUAL, udpCls,
                                "RelevantTo", relDesc) - 1
                && classWideCalls(classNode(distJava, asmCls), Opcodes.INVOKESTATIC, argCls,
                        "relevantTo", relHelperDesc) == 1);

        // ---- W14 動物 requested 冷卻＋範圍閘 ----
        String reqCls = "zombie/mdc/AnimalRequestGate";
        String aupCls = "zombie/network/packets/character/AnimalUpdatePacket";
        String aimCls = "zombie/popman/animal/AnimalInstanceManager";
        String getPacketDesc = "(Lzombie/network/PacketTypes$PacketType;)Lzombie/network/packets/INetworkPacket;";
        String getPacketHelperDesc = "(L" + udpCls + ";Lzombie/network/PacketTypes$PacketType;)Lzombie/network/packets/INetworkPacket;";
        String mapGetDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
        String filterDesc = "(Ljava/util/HashMap;Ljava/lang/Object;)Ljava/lang/Object;";
        // vanilla 前提 1：sendUpdateToClient 內 HashMap.get 恰 3（offset 83 requests.get(Long guid)
        // ＋ offset 370/419 timerUpdateAnimal.get(Short)）、UdpConnection.getPacket 恰 2
        // （reliable/unreliable 分支）。任一數目漂移＝TIS 改了填充邏輯，runtime 分流假設要重估。
        failed += check("W14 vanilla 前提：sendUpdateToClient 內 HashMap.get 恰 3、getPacket 恰 2",
                countExactCalls(vSend, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", mapGetDesc) == 3
                && countExactCalls(vSend, Opcodes.INVOKEVIRTUAL, udpCls, "getPacket", getPacketDesc) == 2);
        // vanilla 前提 1b（ThreadLocal 捕獲的時序錨）：兩個 getPacket 分支都必須先於
        // requests.get(Long)。若 TIS 保留呼叫數卻把 requested 填充移到 getPacket 之前，
        // 捕獲就會缺失——本刀的設計是「缺失＝null＝跳過範圍檢查」（fail-open 到保守側），
        // 但那等於範圍閘靜默失效，故釘成前提讓建置紅、而不是默默降級。
        failed += check("W14 vanilla 前提：getPacket（兩分支最晚者）先於 requests.get(Long)（捕獲時序錨）",
                lastCallIndex(vSend, Opcodes.INVOKEVIRTUAL, udpCls, "getPacket", getPacketDesc)
                        < firstCallIndex(vSend, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", mapGetDesc));
        // vanilla 前提 2：client 端 sendRequestToServer 走 invokeinterface IConnection.getPacket
        // （不同 opcode＋owner）——這是「redirect 不會誤中 client 路徑」的結構事實。
        MethodNode vSendReq = methodFromJar(jar, asmCls, "sendRequestToServer",
                "(Lzombie/network/IConnection;)V");
        failed += check("W14 vanilla 前提：sendRequestToServer 用 invokeinterface IConnection.getPacket（redirect 不會誤中）",
                countExactCalls(vSendReq, Opcodes.INVOKEINTERFACE, "zombie/network/IConnection", "getPacket", getPacketDesc) == 1
                && countExactCalls(vSendReq, Opcodes.INVOKEVIRTUAL, udpCls, "getPacket", getPacketDesc) == 0);
        // vanilla 前提 3：AnimalUpdatePacket.write 的 requested 區對 animal==null 直接跳過、
        // requestedCount 由實際寫入數回填（AnimalInstanceManager.get 恰 2：requested＋updated 迴圈）
        // ——這是「過濾 requested 集合 wire-safe」的結構依據。
        MethodNode vWrite = methodFromJar(jar, aupCls, "write", "(Lzombie/core/network/ByteBufferWriter;)V");
        failed += check("W14 vanilla 前提：AnimalUpdatePacket.write 內 AnimalInstanceManager.get 恰 2（null 跳過＝wire-safe 依據）",
                countExactCalls(vWrite, Opcodes.INVOKEVIRTUAL, aimCls, "get", "(S)Lzombie/characters/animals/IsoAnimal;") == 2);
        // 手術後：getPacket 改道 x2、HashMap.get 改道 x3、原呼叫歸零、真指令數不變（1:1 x6 含 W13）
        failed += check("W14 手術後：sendUpdateToClient getPacket 改道 x2、HashMap.get 改道 x3、原呼叫歸零、真指令數不變",
                countExactCalls(pSend, Opcodes.INVOKESTATIC, reqCls, "getPacket", getPacketHelperDesc) == 2
                && countExactCalls(pSend, Opcodes.INVOKEVIRTUAL, udpCls, "getPacket", getPacketDesc) == 0
                && countExactCalls(pSend, Opcodes.INVOKESTATIC, reqCls, "filterRequests", filterDesc) == 3
                && countExactCalls(pSend, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", mapGetDesc) == 0
                && realInsnCount(pSend) == realInsnCount(vSend));
        // 手術後：AnimalUpdatePacket 與 sendRequestToServer 逐項未被碰（wire 格式與 client 路徑零改動）
        failed += check("W14 手術後：AnimalUpdatePacket.write 與 sendRequestToServer 未被改動",
                realInsnCount(method(distJava, asmCls, "sendRequestToServer", "(Lzombie/network/IConnection;)V"))
                        == realInsnCount(vSendReq)
                && classWideCalls(classNode(distJava, asmCls), Opcodes.INVOKESTATIC, reqCls, "getPacket", getPacketHelperDesc) == 2
                && classWideCalls(classNode(distJava, asmCls), Opcodes.INVOKESTATIC, reqCls, "filterRequests", filterDesc) == 3);
        // helper 契約：filterRequests 恰 1 次原 HashMap.get 委派（timer 直通與 raw 讀共用同一次）
        // ＋恰 1 次存在性查詢（`AnimalInstanceManager.get(S)`）——後者刻意與 RANGE_MODE 無關，
        // 是「不存在的 ID 一律不 mark」的實作依據（堵大量假 ID 灌爆 bucket 清冷卻的路徑）；
        // 範圍閘本身只做幾何（animal 由呼叫端解析），故 RelevantTo／getRelevantRange 各恰 1。
        MethodNode gateFilter = method(distJava, reqCls, "filterRequests", filterDesc);
        MethodNode gateRange = method(distJava, reqCls, "allowRange",
                "(L" + udpCls + ";Lzombie/characters/animals/IsoAnimal;)Z");
        failed += check("W14 helper 契約：filterRequests 內原 HashMap.get 委派恰 1、存在性查詢恰 1",
                countExactCalls(gateFilter, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", mapGetDesc) == 1
                && countExactCalls(gateFilter, Opcodes.INVOKEVIRTUAL, aimCls, "get",
                        "(S)Lzombie/characters/animals/IsoAnimal;") == 1);
        failed += check("W14 helper 契約：範圍閘只做幾何（RelevantTo／getRelevantRange 各恰 1、零存在性查詢）",
                countExactCalls(gateRange, Opcodes.INVOKEVIRTUAL, udpCls, "RelevantTo", relDesc) == 1
                && countExactCalls(gateRange, Opcodes.INVOKEVIRTUAL, udpCls, "getRelevantRange", "()B") == 1
                && countExactCalls(gateRange, Opcodes.INVOKEVIRTUAL, aimCls, "get",
                        "(S)Lzombie/characters/animals/IsoAnimal;") == 0);
        // helper 契約：連線捕獲恰 1 次 ThreadLocal.set ＋ 恰 1 次原 getPacket 委派
        MethodNode gateCapture = method(distJava, reqCls, "getPacket", getPacketHelperDesc);
        failed += check("W14 helper 契約：getPacket 捕獲恰 1 次 ThreadLocal.set＋恰 1 次原委派",
                countExactCalls(gateCapture, Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V") == 1
                && countExactCalls(gateCapture, Opcodes.INVOKEVIRTUAL, udpCls, "getPacket", getPacketDesc) == 1);
        // 負對照：全 class 的 getPacket/HashMap.get 差額全部落在 sendUpdateToClient
        failed += check("W14 負對照：全 class getPacket 恰少 2、HashMap.get 恰少 3（其餘方法未動）",
                classWideCalls(classNode(distJava, asmCls), Opcodes.INVOKEVIRTUAL, udpCls, "getPacket", getPacketDesc)
                        == classWideCalls(classNodeFromJar(jar, asmCls), Opcodes.INVOKEVIRTUAL, udpCls,
                                "getPacket", getPacketDesc) - 2
                && classWideCalls(classNode(distJava, asmCls), Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", mapGetDesc)
                        == classWideCalls(classNodeFromJar(jar, asmCls), Opcodes.INVOKEVIRTUAL, "java/util/HashMap",
                                "get", mapGetDesc) - 3);

        // ---- W15 主迴圈凍結看門狗 ----
        String wdCls = "zombie/mdc/MainLoopWatchdog";
        String smCls = "zombie/network/ServerMap";
        String wdTickDesc = "(L" + smCls + ";)V";
        // vanilla 前提：GameServer.main 主迴圈對 preupdate 恰 1 個 callsite——「幀齡」語意
        // 建立在「每圈恰一次」上；TIS 改成多處呼叫或移除時建置紅、重選掛點而非默默失真。
        MethodNode vGsMain = methodFromJar(jar, "zombie/network/GameServer", "main",
                "([Ljava/lang/String;)V");
        failed += check("W15 vanilla 前提：GameServer.main 內 ServerMap.preupdate 恰 1 處（每幀恰一次）",
                countExactCalls(vGsMain, Opcodes.INVOKEVIRTUAL, smCls, "preupdate", "()V") == 1);
        // 手術後：preupdate 頭部全序 aload_0→tick（helper 呼叫全方法恰一次），真指令數恰 +2
        MethodNode pPreupdate = method(distJava, smCls, "preupdate", "()V");
        MethodNode vPreupdate = methodFromJar(jar, smCls, "preupdate", "()V");
        failed += check("W15 手術後：preupdate 頭部 headCall 全序、真指令數恰 +2（原體未動）",
                headCallOk(pPreupdate, wdCls, "tick", wdTickDesc)
                && realInsnCount(pPreupdate) == realInsnCount(vPreupdate) + 2);
        // helper 契約：tick 熱路徑恰 1 次 nanoTime、零快照呼叫；快照走單執行緒 getStackTrace
        // （恰 1 處、且全 class 零 getAllStackTraces——全執行緒快照貴一個量級，釘死不許誤用）。
        MethodNode wdTick = method(distJava, wdCls, "tick", wdTickDesc);
        failed += check("W15 helper 契約：tick 恰 1 次 nanoTime、快照只用單執行緒 getStackTrace",
                countExactCalls(wdTick, Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J") == 1
                && countExactCalls(wdTick, Opcodes.INVOKEVIRTUAL, "java/lang/Thread",
                        "getStackTrace", "()[Ljava/lang/StackTraceElement;") == 0
                && classWideCalls(classNode(distJava, wdCls), Opcodes.INVOKEVIRTUAL, "java/lang/Thread",
                        "getStackTrace", "()[Ljava/lang/StackTraceElement;") == 1
                && classWideCalls(classNode(distJava, wdCls), Opcodes.INVOKESTATIC, "java/lang/Thread",
                        "getAllStackTraces", "()Ljava/util/Map;") == 0);
        // 版本橫幅唯一入口（2026-09-02 退役 W4-1 時橫幅隨 ChunkRequestPacker 一起消失的回歸鎖）：
        // tick 內 announceOnce 恰 1，且位於 MODE 檢查之前（首條真指令），kill switch 不影響橫幅。
        AbstractInsnNode[] tickHead = firstReal(wdTick, 1);
        failed += check("W15 版本橫幅：tick 首條真指令＝PatchInfo.announceOnce（恰 1，先於 kill switch）",
                countExactCalls(wdTick, Opcodes.INVOKESTATIC, "zombie/mdc/PatchInfo", "announceOnce", "()V") == 1
                && tickHead[0] instanceof MethodInsnNode th && th.name.equals("announceOnce"));

        // 退役（2026-09-02）：W16 動物卸載接手守衛 observe 的全部 census、掛點與 helper
        // 契約斷言。8 天正式服全零遺失 ⇒ vanilla 卸載接手鏈無辜、觀測結論已達；
        // heartbeat 每 256 unload 一行佔正式服 log 7.3%，刀與斷言一併移除。
        // 詳見 docs/patches.md 2ad；復活方式：從退役前最後一版 13650e1 取回（`git checkout 13650e1 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

        // ---- W17 hutch 載入回傳檢查 ----
        String isoAnimalCls = "zombie/characters/animals/IsoAnimal";
        String hlgCls = "zombie/mdc/HutchLoadGuard";
        String hutchCls = "zombie/iso/objects/IsoHutch";
        String addInsideDesc = "(L" + isoAnimalCls + ";Z)Z";
        String hlgDesc = "(L" + hutchCls + ";L" + isoAnimalCls + ";Z)Z";
        // vanilla 前提：load 實參必須仍是 hutch(this), animal(slot7), false；下一條 POP
        // 才是「忽略回傳」缺陷本體。成功路徑整體與六步順序亦 fail-closed 鎖住。
        MethodNode vHutchLoad = methodFromJar(jar, hutchCls, "load", "(Ljava/nio/ByteBuffer;IZ)V");
        MethodNode vAddInside = methodFromJar(jar, hutchCls, "addAnimalInside", addInsideDesc);
        failed += check("W17 vanilla：load ALOAD0/ALOAD7/ICONST0/call/POP；成功路徑完整契約",
                hutchLoadCallShape(vHutchLoad, Opcodes.INVOKEVIRTUAL,
                        hutchCls, "addAnimalInside", addInsideDesc)
                && hutchSuccessContract(vAddInside, hutchCls, isoAnimalCls));

        // patched load 只把 call 1:1 換成 static helper；實參 false/POP/真指令數／class 差額不變。
        MethodNode pHutchLoad = method(distJava, hutchCls, "load", "(Ljava/nio/ByteBuffer;IZ)V");
        failed += check("W17 patched：同一實參形狀改道1、原call歸零、真指令不變、class差1",
                hutchLoadCallShape(pHutchLoad, Opcodes.INVOKESTATIC,
                        hlgCls, "addInside", hlgDesc)
                && countExactCalls(pHutchLoad, Opcodes.INVOKEVIRTUAL,
                        hutchCls, "addAnimalInside", addInsideDesc) == 0
                && realInsnCount(pHutchLoad) == realInsnCount(vHutchLoad)
                && classWideCalls(classNode(distJava, hutchCls), Opcodes.INVOKEVIRTUAL,
                        hutchCls, "addAnimalInside", addInsideDesc)
                        == classWideCalls(classNodeFromJar(jar, hutchCls), Opcodes.INVOKEVIRTUAL,
                                hutchCls, "addAnimalInside", addInsideDesc) - 1);

        // helper：委派1、全 class 零 Rand；forceInto 六步各1且 backlink 是精確 PUTFIELD。
        MethodNode gAddInside = method(distJava, hlgCls, "addInside", hlgDesc);
        MethodNode gForceInto = method(distJava, hlgCls, "forceInto",
                "(L" + hutchCls + ";L" + isoAnimalCls + ";I)V");
        failed += check("W17 helper：委派1、零Rand、forceInto六步各1",
                countExactCalls(gAddInside, Opcodes.INVOKEVIRTUAL,
                        hutchCls, "addAnimalInside", addInsideDesc) == 1
                && classNode(distJava, hlgCls).methods.stream()
                        .mapToInt(m -> countCallsToOwner(m, "zombie/core/random/Rand")).sum() == 0
                && countExactCalls(gForceInto, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") == 1
                && countExactFields(gForceInto, Opcodes.PUTFIELD, isoAnimalCls,
                        "hutch", "L" + hutchCls + ";") == 1
                && countExactCalls(gForceInto, Opcodes.INVOKEVIRTUAL,
                        "zombie/characters/animals/datas/AnimalData",
                        "setPreferredHutchPosition", "(I)V") == 1
                && countExactCalls(gForceInto, Opcodes.INVOKEVIRTUAL,
                        "zombie/characters/animals/datas/AnimalData",
                        "setHutchPosition", "(I)V") == 1
                && countExactCalls(gForceInto, Opcodes.INVOKEVIRTUAL,
                        isoAnimalCls, "setItemID", "(I)V") == 1
                && countExactCalls(gForceInto, Opcodes.INVOKEVIRTUAL, hutchCls,
                        "tryRemoveAnimalFromWorld", "(L" + isoAnimalCls + ";)V") == 1);

        // ---- W26 雞舍自發同步收件人過濾（IsoHutch.update 的兩個 sync）----
        String hsgCls = "zombie/mdc/HutchSyncGate";
        // 收件判定與 teleport 豁免簿記自 W36 起移到共用的 RecipientWindow。
        String rwCls = "zombie/mdc/RecipientWindow";
        String tpCls = "zombie/network/packets/TeleportPacket";
        String w26Iso = "zombie/iso/IsoObject";
        String w26Udp = "zombie/core/raknet/UdpConnection";
        String w26Pkt = "zombie/network/PacketTypes$PacketType";
        String w26Pid = "zombie/network/fields/character/PlayerID";
        String w26BbwDesc = "(Lzombie/core/network/ByteBufferWriter;)V";
        String w26UpdDesc = "(L" + hutchCls + ";)V";
        String w26TpHelperDesc = "(L" + w26Pid + ";Lzombie/core/network/ByteBufferWriter;)V";
        String w26StartDesc = "()Lzombie/core/network/ByteBufferWriter;";
        String w26SendDesc = "(Lzombie/network/IConnection;)V";
        String w26SyncIsoDesc = "(ZBL" + w26Udp + ";Lzombie/core/network/ByteBufferReader;)V";
        String w26UpdInsideDesc = "(L" + isoAnimalCls + ";Z)V";
        String w26ReleaseDesc = "(Lzombie/iso/IsoGridSquare;L" + isoAnimalCls + ";)V";
        String w26NestBoxDesc = "(L" + isoAnimalCls + ";)Z";
        MethodNode vHutchUpd = methodFromJar(jar, hutchCls, "update", "()V");
        MethodNode pHutchUpd = method(distJava, hutchCls, "update", "()V");
        ClassNode vHutchNode = classNodeFromJar(jar, hutchCls);
        ClassNode pHutchNode = classNode(distJava, hutchCls);
        // vanilla 前提①：本刀只認 update 內那兩個「server 自發」sync——一個在 hutchDirt
        // 累加之後（髒污即刻廣播），一個在 sendUpdate 分支內且先於 animalInsideSize 回寫
        // （週期／隻數變動廣播）。語境是「哪些 sync 可以過濾收件人」的唯一依據。
        failed += check("W26 vanilla：update 內 sync 恰 2，語境為 hutchDirt putfield 後／sendUpdate 分支內且 size 回寫前",
                hutchSyncSites(vHutchUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V", hutchCls));
        // 其餘 5 個 sync 包含操作、下蛋與動物狀態更新，一律不過濾。
        // 總數＋逐方法分佈雙鎖，堵「舊點消失＋新點出現」互抵。
        failed += check("W26 vanilla census：IsoHutch 全 class sync 恰 7（update 2／updateAnimalInside 2／releaseAnimal 1／addAnimalInNestBox 1／addAnimalInside 1）",
                classWideCalls(vHutchNode, Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 7
                && countExactCalls(methodFromJar(jar, hutchCls, "updateAnimalInside", w26UpdInsideDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 2
                && countExactCalls(methodFromJar(jar, hutchCls, "releaseAnimal", w26ReleaseDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 1
                && countExactCalls(methodFromJar(jar, hutchCls, "addAnimalInNestBox", w26NestBoxDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 1
                && countExactCalls(methodFromJar(jar, hutchCls, "addAnimalInside", addInsideDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 1);
        // vanilla 前提③：helper 重寫的是 sync()→sync(0)→syncIsoObject 的 server 廣播分支。
        // client 分支在 server 分支之前（故 helper 必須先讓 GameClient.client 直通）、
        // square/getObjectIndex 早退在兩者之前、每個分支恰一組 startPacket→doPacket→
        // syncIsoObjectSend→send（共 3 組）、flagForHotSave 恰 1 且在所有 send 之後。
        // 下方另鎖 server 非 remote 分支的完整指令形狀；上游變更時必須重驗。
        MethodNode vIsoSync = methodFromJar(jar, w26Iso, "sync", "()V");
        MethodNode vIsoSyncI = methodFromJar(jar, w26Iso, "sync", "(I)V");
        MethodNode vSyncIso = methodFromJar(jar, w26Iso, "syncIsoObject", w26SyncIsoDesc);
        failed += check("W26 vanilla 前提：sync()→sync(0)→syncIsoObject；廣播三組四步、hot-save 恰 1 且在最後、client 分支先於 server",
                countExactCalls(vIsoSync, Opcodes.INVOKEVIRTUAL, w26Iso, "sync", "(I)V") == 1
                && countExactCalls(vIsoSyncI, Opcodes.INVOKEVIRTUAL, w26Iso, "syncIsoObject", w26SyncIsoDesc) == 1
                && countExactCalls(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Udp, "startPacket", w26StartDesc) == 3
                && countExactCalls(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Pkt, "doPacket", w26BbwDesc) == 3
                && countExactCalls(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Iso, "syncIsoObjectSend", w26BbwDesc) == 3
                && countExactCalls(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Pkt, "send", w26SendDesc) == 3
                && countExactCalls(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Iso, "flagForHotSave", "()V") == 1
                && countExactCalls(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Iso, "getObjectIndex", "()I") == 1
                && lastCallIndex(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Pkt, "send", w26SendDesc)
                        < firstCallIndex(vSyncIso, Opcodes.INVOKEVIRTUAL, w26Iso, "flagForHotSave", "()V")
                && firstFieldIndex(vSyncIso, Opcodes.GETSTATIC, "zombie/network/GameClient", "client", "Z")
                        < firstFieldIndex(vSyncIso, Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z")
                && hutchServerBroadcast(vSyncIso));

        // 手術後：兩處在同一語境 1:1 改道、原 sync 歸零、真指令數不變。
        failed += check("W26 patched：update 兩處同語境改道 x2、原 sync 歸零、真指令數不變",
                hutchSyncSites(pHutchUpd, Opcodes.INVOKESTATIC, hsgCls, "syncUpdate", w26UpdDesc, hutchCls)
                && countExactCalls(pHutchUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 0
                && realInsnCount(pHutchUpd) == realInsnCount(vHutchUpd));
        // 負對照：全 class sync 恰少 2、改道恰 2 且全在 update；操作觸發的 4 個 caller 逐一
        // 保留原呼叫；全 class 真指令總數與 vanilla 相同（load 的 W17 改道亦 1:1），
        // 且 load／save／syncIsoObjectSend／syncIsoObjectReceive 零 HutchSyncGate 呼叫
        // ——初次載入、存檔與 wire 序列化都不在本刀範圍。
        failed += check("W26 負對照：全 class sync 恰少 2、改道恰 2 全在 update、其餘 4 caller 原樣、真指令總數不變、load/save/sync 收發零 helper",
                classWideCalls(pHutchNode, Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V")
                        == classWideCalls(vHutchNode, Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") - 2
                && classWideCalls(pHutchNode, Opcodes.INVOKESTATIC, hsgCls, "syncUpdate", w26UpdDesc) == 2
                && countExactCalls(pHutchUpd, Opcodes.INVOKESTATIC, hsgCls, "syncUpdate", w26UpdDesc) == 2
                && countExactCalls(method(distJava, hutchCls, "updateAnimalInside", w26UpdInsideDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 2
                && countExactCalls(method(distJava, hutchCls, "releaseAnimal", w26ReleaseDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 1
                && countExactCalls(method(distJava, hutchCls, "addAnimalInNestBox", w26NestBoxDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 1
                && countExactCalls(method(distJava, hutchCls, "addAnimalInside", addInsideDesc),
                        Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 1
                && classRealInsnCount(pHutchNode) == classRealInsnCount(vHutchNode)
                && countCallsToOwner(pHutchLoad, hsgCls) == 0
                && countCallsToOwner(method(distJava, hutchCls, "save", "(Ljava/nio/ByteBuffer;Z)V"), hsgCls) == 0
                && countCallsToOwner(method(distJava, hutchCls, "syncIsoObjectSend", w26BbwDesc), hsgCls) == 0
                && countCallsToOwner(method(distJava, hutchCls, "syncIsoObjectReceive",
                        "(Lzombie/core/network/ByteBufferReader;)V"), hsgCls) == 0);
        // 本刀不得在 IsoObject 留下任何呼叫，四步廣播與 hot-save 的形狀也必須與 vanilla 逐項相同——
        // 否則「只改誰收、不改怎麼送」的承諾就破了，其他上萬個 IsoObject 的 wire 一起被牽動。
        // 42.21 抑噪 #9 退役後 IsoObject 不再出貨（＝逐位元 vanilla）；日後若有刀重新改 IsoObject，改驗 dist 版。
        boolean w26IsoShipped = Files.exists(distJava.resolve(w26Iso + ".class"));
        MethodNode pSyncIso = w26IsoShipped ? method(distJava, w26Iso, "syncIsoObject", w26SyncIsoDesc) : vSyncIso;
        ClassNode pIsoObjNode = w26IsoShipped ? classNode(distJava, w26Iso) : classNodeFromJar(jar, w26Iso);
        failed += check("W26 負對照：IsoObject 全 class 零 HutchSyncGate／RecipientWindow 改道、syncIsoObject 四步／hot-save 與 vanilla 同、sync 鏈真指令數不變",
                classWideCalls(pIsoObjNode, Opcodes.INVOKESTATIC, hsgCls, "syncUpdate", w26UpdDesc) == 0
                && classWideCalls(pIsoObjNode, Opcodes.INVOKESTATIC, rwCls,
                        "writeTeleportPlayer", w26TpHelperDesc) == 0
                && countExactCalls(pSyncIso, Opcodes.INVOKEVIRTUAL, w26Udp, "startPacket", w26StartDesc) == 3
                && countExactCalls(pSyncIso, Opcodes.INVOKEVIRTUAL, w26Pkt, "doPacket", w26BbwDesc) == 3
                && countExactCalls(pSyncIso, Opcodes.INVOKEVIRTUAL, w26Iso, "syncIsoObjectSend", w26BbwDesc) == 3
                && countExactCalls(pSyncIso, Opcodes.INVOKEVIRTUAL, w26Pkt, "send", w26SendDesc) == 3
                && countExactCalls(pSyncIso, Opcodes.INVOKEVIRTUAL, w26Iso, "flagForHotSave", "()V") == 1
                && realInsnCount(pSyncIso) == realInsnCount(vSyncIso)
                && realInsnCount(w26IsoShipped ? method(distJava, w26Iso, "sync", "()V") : vIsoSync)
                        == realInsnCount(vIsoSync)
                && realInsnCount(w26IsoShipped ? method(distJava, w26Iso, "sync", "(I)V") : vIsoSyncI)
                        == realInsnCount(vIsoSyncI));

        // helper 契約①：被選中的連線仍走原版四步（startPacket→doPacket→syncIsoObjectSend→send）
        // 各恰 1；直通委派涵蓋 off/非 server/client/子類、簿記故障、無效物件。
        // hot-save 必須在 send 之後（收件人全被過濾也不能漏存），且不得再觸發通用廣播。
        MethodNode gSyncUpd = method(distJava, hsgCls, "syncUpdate", w26UpdDesc);
        failed += check("W26 helper：原版四步各 1、vanilla 委派 3（off/子類・簿記故障・無效物件）、flagForHotSave 1 且在 send 後、零 IsoObject 廣播",
                countExactCalls(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Udp, "startPacket", w26StartDesc) == 1
                && countExactCalls(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "doPacket", w26BbwDesc) == 1
                && countExactCalls(gSyncUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "syncIsoObjectSend", w26BbwDesc) == 1
                && countExactCalls(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "send", w26SendDesc) == 1
                && countExactCalls(gSyncUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "sync", "()V") == 3
                && countExactCalls(gSyncUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "flagForHotSave", "()V") == 1
                && firstCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Udp, "startPacket", w26StartDesc)
                        < firstCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "doPacket", w26BbwDesc)
                && firstCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "doPacket", w26BbwDesc)
                        < firstCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "syncIsoObjectSend", w26BbwDesc)
                && firstCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "syncIsoObjectSend", w26BbwDesc)
                        < firstCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "send", w26SendDesc)
                && lastCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "send", w26SendDesc)
                        < firstCallIndex(gSyncUpd, Opcodes.INVOKEVIRTUAL, hutchCls, "flagForHotSave", "()V")
                && countCallsToOwner(gSyncUpd, w26Iso) == 0);
        // 只有新增的範圍判定可 fail-open；送包例外仍由原 caller 處理。
        // 實際失敗行為另由 HutchSyncGateTest 的序列化／送出失敗案例驗證。
        failed += check("W26 helper：shouldSend 在 try 內恰 1、四步送包零 try 保護、catch 型別全為 RuntimeException",
                callsInsideTryRange(gSyncUpd, Opcodes.INVOKESTATIC, hsgCls, "shouldSend",
                        "(L" + w26Udp + ";FF)Z") == 1
                && callsInsideTryRange(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Udp, "startPacket", w26StartDesc) == 0
                && callsInsideTryRange(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "doPacket", w26BbwDesc) == 0
                && callsInsideTryRange(gSyncUpd, Opcodes.INVOKEVIRTUAL, hutchCls,
                        "syncIsoObjectSend", w26BbwDesc) == 0
                && callsInsideTryRange(gSyncUpd, Opcodes.INVOKEVIRTUAL, w26Pkt, "send", w26SendDesc) == 0
                && gSyncUpd.tryCatchBlocks != null && !gSyncUpd.tryCatchBlocks.isEmpty()
                && gSyncUpd.tryCatchBlocks.stream()
                        .allMatch(t -> "java/lang/RuntimeException".equals(t.type)));
        // 幾何、載具／noclip／teleport 與全域降級由行為測試驗證，不釘 helper 私有欄位讀取次數。
        String mapPutDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
        // 共用 helper 契約④：豁免名單必須是 weak-key（強引用會把退役角色永久釘在堆上、豁免永不
        // 到期）且自帶同步（writeTeleportPlayer 的公開入口可能不在世界更新緒）；
        // 全 class 只有一個寫入點＝送出 teleport，判定端恰 1 次 containsKey。
        MethodNode gRwClinit = method(distJava, rwCls, "<clinit>", "()V");
        failed += check("W26 共用 helper：EXEMPT＝WeakHashMap＋synchronizedMap 各 1、全 class put 恰 1（唯一寫入＝送出 teleport）、containsKey 恰 1；HutchSyncGate 不再自帶名單（唯一 put 在 payload 變化量測）",
                countNew(gRwClinit, "java/util/WeakHashMap") == 1
                && countExactCalls(gRwClinit, Opcodes.INVOKESTATIC, "java/util/Collections",
                        "synchronizedMap", "(Ljava/util/Map;)Ljava/util/Map;") == 1
                && classWideCalls(classNode(distJava, rwCls), Opcodes.INVOKEINTERFACE,
                        "java/util/Map", "put", mapPutDesc) == 1
                && classWideCalls(classNode(distJava, rwCls), Opcodes.INVOKEINTERFACE,
                        "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z") == 1
                && classWideCalls(classNode(distJava, hsgCls), Opcodes.INVOKEINTERFACE,
                        "java/util/Map", "put", mapPutDesc) == 1
                && countExactCalls(method(distJava, hsgCls, "samePayload",
                        "(Lzombie/iso/objects/IsoHutch;Lzombie/core/network/ByteBufferWriter;I)Z"),
                        Opcodes.INVOKEINTERFACE, "java/util/Map", "put", mapPutDesc) == 1);

        // ---- W26-2 teleport 豁免掛點（TeleportPacket.write 的唯一 PlayerID.write）----
        // vanilla 前提：write 只有一個 PlayerID.write，且在 3 個 putFloat 之前（wire 順序）。
        MethodNode vTpWrite = methodFromJar(jar, tpCls, "write", w26BbwDesc);
        MethodNode pTpWrite = method(distJava, tpCls, "write", w26BbwDesc);
        String putFloatDesc = "(F)V";
        String w26Bbw = "zombie/core/network/ByteBufferWriter";
        failed += check("W26-2 vanilla：TeleportPacket.write PlayerID.write 恰 1 且先於 3 個 putFloat",
                countExactCalls(vTpWrite, Opcodes.INVOKEVIRTUAL, w26Pid, "write", w26BbwDesc) == 1
                && countExactCalls(vTpWrite, Opcodes.INVOKEVIRTUAL, w26Bbw, "putFloat", putFloatDesc) == 3
                && lastCallIndex(vTpWrite, Opcodes.INVOKEVIRTUAL, w26Pid, "write", w26BbwDesc)
                        < firstCallIndex(vTpWrite, Opcodes.INVOKEVIRTUAL, w26Bbw, "putFloat", putFloatDesc));
        // 手術後：改道 1、原呼叫歸零、真指令數不變、XYZ 三個 putFloat 仍在改道之後（wire 不變）。
        failed += check("W26-2 patched：write 改道 x1、原 PlayerID.write 歸零、真指令不變、XYZ putFloat 仍在其後",
                countExactCalls(pTpWrite, Opcodes.INVOKESTATIC, rwCls, "writeTeleportPlayer", w26TpHelperDesc) == 1
                && countExactCalls(pTpWrite, Opcodes.INVOKEVIRTUAL, w26Pid, "write", w26BbwDesc) == 0
                && realInsnCount(pTpWrite) == realInsnCount(vTpWrite)
                && countExactCalls(pTpWrite, Opcodes.INVOKEVIRTUAL, w26Bbw, "putFloat", putFloatDesc) == 3
                && lastCallIndex(pTpWrite, Opcodes.INVOKESTATIC, rwCls, "writeTeleportPlayer", w26TpHelperDesc)
                        < firstCallIndex(pTpWrite, Opcodes.INVOKEVIRTUAL, w26Bbw, "putFloat", putFloatDesc));
        // 負對照：全 class PlayerID.write 恰少 1、改道恰 1（只此一處）、真指令總數不變；
        // parse（wire 的對稱另一半）逐項未動——豁免簿記不得改變任何線路位元。
        ClassNode vTpNode = classNodeFromJar(jar, tpCls);
        ClassNode pTpNode = classNode(distJava, tpCls);
        String tpParseDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        failed += check("W26-2 負對照：全 class PlayerID.write 恰少 1、改道恰 1、真指令總數不變、parse 未被改動",
                classWideCalls(pTpNode, Opcodes.INVOKEVIRTUAL, w26Pid, "write", w26BbwDesc)
                        == classWideCalls(vTpNode, Opcodes.INVOKEVIRTUAL, w26Pid, "write", w26BbwDesc) - 1
                && classWideCalls(pTpNode, Opcodes.INVOKESTATIC, rwCls, "writeTeleportPlayer", w26TpHelperDesc) == 1
                && classRealInsnCount(pTpNode) == classRealInsnCount(vTpNode)
                && realInsnCount(method(distJava, tpCls, "parse", tpParseDesc))
                        == realInsnCount(methodFromJar(jar, tpCls, "parse", tpParseDesc))
                && countExactCalls(method(distJava, tpCls, "parse", tpParseDesc),
                        Opcodes.INVOKEVIRTUAL, w26Pid, "parse", tpParseDesc) == 1);
        // 豁免簿記與原 ID 寫入的例外邊界不能混合；全域降級由故障後的收件行為驗證。
        MethodNode gWriteTp = method(distJava, rwCls, "writeTeleportPlayer", w26TpHelperDesc);
        failed += check("W26-2 helper：PlayerID.write 委派 1 且在 try 外、RETURN 恰 1、catch 僅 RuntimeException、put 1",
                countExactCalls(gWriteTp, Opcodes.INVOKEVIRTUAL, w26Pid, "write", w26BbwDesc) == 1
                && callsInsideTryRange(gWriteTp, Opcodes.INVOKEVIRTUAL, w26Pid, "write", w26BbwDesc) == 0
                && countOpcode(gWriteTp, Opcodes.RETURN) == 1
                && gWriteTp.tryCatchBlocks != null && !gWriteTp.tryCatchBlocks.isEmpty()
                && gWriteTp.tryCatchBlocks.stream()
                        .allMatch(t -> "java/lang/RuntimeException".equals(t.type))
                && countExactCalls(gWriteTp, Opcodes.INVOKEINTERFACE,
                        "java/util/Map", "put", mapPutDesc) == 1);

        MethodNode vPidSet = methodFromJar(jar, w26Pid, "set", "(Lzombie/characters/IsoPlayer;)V");
        MethodNode vPidGet = methodFromJar(jar, w26Pid, "getPlayer", "()Lzombie/characters/IsoPlayer;");
        AbstractInsnNode[] vPidGetBody = firstReal(vPidGet, 3);
        failed += check("W26-2 身分前提：PlayerID.set 保存原 player 實例，getPlayer 直接回傳而非依 ID 反查",
                countExactFields(vPidSet, Opcodes.PUTFIELD, w26Pid, "player", "Lzombie/characters/IsoPlayer;") == 1
                && realInsnCount(vPidGet) == 3
                && isVar(vPidGetBody[0], Opcodes.ALOAD, 0)
                && isField(vPidGetBody[1], Opcodes.GETFIELD, w26Pid, "player", "Lzombie/characters/IsoPlayer;")
                && vPidGetBody[2].getOpcode() == Opcodes.ARETURN);

        // ---- W18 動物 LOS 節流閘 ----
        String algCls = "zombie/mdc/AnimalLosGate";
        // vanilla 前提：updateInternal 內 updateLOS 呼叫恰 1（掛點）；updateLOS 本體
        // getObjectList():Set 恰 1（資料來源，TIS 改型別/來源時此條紅=撤刀重估）＋
        // 零 lastSpotted 引用（動物版無玩家尾段——TIS 若下放玩家消費邏輯到動物版，
        // skip 的 spottedList 陳舊語意不再零差，此條紅=撤刀重估）。
        MethodNode vAniUpdInt = methodFromJar(jar, isoAnimalCls, "updateInternal", "()V");
        MethodNode vAniLos = methodFromJar(jar, isoAnimalCls, "updateLOS", "()V");
        failed += check("W18 vanilla：updateInternal 掛點1、updateLOS getObjectList(Set)1、零 lastSpotted",
                countExactCalls(vAniUpdInt, Opcodes.INVOKEVIRTUAL,
                        isoAnimalCls, "updateLOS", "()V") == 1
                && countExactCalls(vAniLos, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell",
                        "getObjectList", "()Ljava/util/Set;") == 1
                && countFieldTouches(vAniLos, isoAnimalCls, "lastSpotted") == 0
                && countFieldTouches(vAniLos, "zombie/characters/IsoPlayer", "lastSpotted") == 0);

        // patched：updateInternal 原呼叫歸零、改道 1、真指令數不變、class 差恰 1
        // （updateLOS 本體的 a4 spotted 改道與本刀不同方法，互不影響差額）。
        MethodNode pAniUpdInt = method(distJava, isoAnimalCls, "updateInternal", "()V");
        failed += check("W18 patched：改道1、原call歸零、真指令不變、class差1",
                countExactCalls(pAniUpdInt, Opcodes.INVOKESTATIC, algCls, "updateLOS",
                        "(L" + isoAnimalCls + ";)V") == 1
                && countExactCalls(pAniUpdInt, Opcodes.INVOKEVIRTUAL,
                        isoAnimalCls, "updateLOS", "()V") == 0
                && realInsnCount(pAniUpdInt) == realInsnCount(vAniUpdInt)
                && classWideCalls(classNode(distJava, isoAnimalCls), Opcodes.INVOKEVIRTUAL,
                        isoAnimalCls, "updateLOS", "()V")
                        == classWideCalls(classNodeFromJar(jar, isoAnimalCls), Opcodes.INVOKEVIRTUAL,
                                isoAnimalCls, "updateLOS", "()V") - 1);

        // helper 契約（W18-2 疊加後）：Gate off 仍直通 vanilla 一次；forward 路徑改為
        // AnimalLosScan 靜態委派恰 1。幀源/LOD fail-open/零配置/例外型別紀律原樣。
        MethodNode gAlgUpd = method(distJava, algCls, "updateLOS", "(L" + isoAnimalCls + ";)V");
        String scanCls = "zombie/mdc/AnimalLosScan";
        String aniRecvDesc = "(L" + isoAnimalCls + ";)V";
        failed += check("W18 helper：vanilla直通1＋Scan委派1、幀源1、LOD fail-open、零NEW零Rand",
                countExactCalls(gAlgUpd, Opcodes.INVOKEVIRTUAL,
                        isoAnimalCls, "updateLOS", "()V") == 1
                && countExactCalls(gAlgUpd, Opcodes.INVOKESTATIC,
                        scanCls, "updateLOS", aniRecvDesc) == 1
                && countExactCalls(gAlgUpd, Opcodes.INVOKEVIRTUAL,
                        "zombie/MovingObjectUpdateScheduler", "getFrameCounter", "()J") == 1
                && classNode(distJava, algCls).methods.stream()
                        .mapToInt(m -> countExactCalls(m, Opcodes.INVOKEVIRTUAL,
                                isoAnimalCls, "getCurrentSimulationLevel",
                                "()Lzombie/UpdateSchedulerSimulationLevel;")).sum() == 1
                && classNode(distJava, algCls).methods.stream()
                        .mapToInt(m -> countExactCalls(m, Opcodes.INVOKEVIRTUAL,
                                "zombie/UpdateSchedulerSimulationLevel", "getFrameMod", "()I")).sum() == 1
                && countOpcode(gAlgUpd, Opcodes.NEW) == 0
                && classNode(distJava, algCls).methods.stream()
                        .mapToInt(m -> countCallsToOwner(m, "zombie/core/random/Rand")).sum() == 0
                && gAlgUpd.tryCatchBlocks != null
                && gAlgUpd.tryCatchBlocks.stream().allMatch(
                        tcb -> tcb.type == null || "java/lang/RuntimeException".equals(tcb.type)));

        // W18-2 Scan：vanilla 迴圈殼語境指紋＋jar-wide caller census＋helper delegate 契約。
        // 任一紅＝TIS 改了 updateLOS，fast-path 等價性必須重證，不能只改命中數放行。
        failed += check("W18-2 vanilla指紋：objectList1/DistanceTo1/tryCastTo3/prefilter2/caller1",
                countExactCalls(vAniLos, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell",
                        "getObjectList", "()Ljava/util/Set;") == 1
                && countCallsToOwner(vAniLos, "zombie/iso/IsoUtils") == 1
                && countCallsToOwner(vAniLos, "zombie/util/Type") == 3
                && countExactCalls(method(distJava, isoAnimalCls, "updateLOS", "()V"),
                        Opcodes.INVOKESTATIC,
                        "zombie/characters/animals/behavior/AnimalSpottedPrefilter",
                        "spotted",
                        "(Lzombie/characters/animals/behavior/BaseAnimalBehavior;"
                                + "Lzombie/iso/IsoMovingObject;ZF)V") == 2
                && jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL,
                        isoAnimalCls, "updateLOS", "()V") == 1);
        MethodNode scanUpd = method(distJava, scanCls, "updateLOS", aniRecvDesc);
        failed += check("W18-2 Scan helper：fallback3/prefilter2/live-threshold/DistanceTo/前綴/零Rand/catch型別",
                countExactCalls(scanUpd, Opcodes.INVOKEVIRTUAL,
                        isoAnimalCls, "updateLOS", "()V") == 3
                && countExactCalls(scanUpd, Opcodes.INVOKESTATIC,
                        "zombie/characters/animals/behavior/AnimalSpottedPrefilter",
                        "spotted",
                        "(Lzombie/characters/animals/behavior/BaseAnimalBehavior;"
                                + "Lzombie/iso/IsoMovingObject;ZF)V") == 2
                && countExactCalls(scanUpd, Opcodes.INVOKESTATIC,
                        "zombie/characters/animals/behavior/AnimalSpottedPrefilter",
                        "thresholdOf", "(I)F") == 1
                && countExactCalls(scanUpd, Opcodes.INVOKESTATIC,
                        "zombie/iso/IsoUtils", "DistanceTo", "(FFFF)F") == 1
                && countExactCalls(scanUpd, Opcodes.INVOKESTATIC,
                        "zombie/GameTime", "getInstance", "()Lzombie/GameTime;") == 1
                && countExactCalls(scanUpd, Opcodes.INVOKEVIRTUAL,
                        "zombie/GameTime", "getMultiplier", "()F") == 1
                && countFieldTouches(scanUpd,
                        "zombie/characters/animals/behavior/BaseAnimalBehavior", "lastAlerted") >= 4
                && countFieldTouches(scanUpd, isoAnimalCls, "spottedChr") >= 1
                && classNode(distJava, scanCls).methods.stream()
                        .mapToInt(m -> countCallsToOwner(m, "zombie/core/random/Rand")).sum() == 0
                && scanUpd.tryCatchBlocks.stream()
                        .allMatch(tcb -> "java/lang/RuntimeException".equals(tcb.type)));

        // W47 動物視線空間預篩（docs/patches.md 2bj）。原版前提（任一紅＝等價論證要重做）：
        // ① spotted() 開頭先清 spottedChr（候選前的遠距前綴可省略的依據）；② BaseAnimalBehavior
        // 全類不讀寫 spottedList（只放自己）；③ addMovingObject 在更新期間延後加入（快照順序＝當下迭代順序）。
        String babCls = "zombie/characters/animals/behavior/BaseAnimalBehavior";
        String spottedPrefilterCls = "zombie/characters/animals/behavior/AnimalSpottedPrefilter";
        AbstractInsnNode[] spHead = firstReal(methodFromJar(jar, babCls, "spotted", "(Lzombie/iso/IsoMovingObject;ZF)V"), 4);
        boolean noSpottedList = classNodeFromJar(jar, babCls).methods.stream().allMatch(m -> {
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof FieldInsnNode fi && fi.name.equals("spottedList")
                        || in instanceof MethodInsnNode mi && mi.name.equals("getSpottedList")) {
                    return false;
                }
            }
            return true;
        });
        MethodNode vAddMoving = methodFromJar(jar, "zombie/iso/IsoCell", "addMovingObject", "(Lzombie/iso/IsoMovingObject;)V");
        failed += check("W47 原版前提：spotted() 先清 spottedChr、BaseAnimalBehavior 不碰 spottedList、addMovingObject 更新期間延後加入",
                spHead[0] instanceof VarInsnNode v0 && v0.getOpcode() == Opcodes.ALOAD && v0.var == 0
                && spHead[1] instanceof FieldInsnNode f1 && f1.getOpcode() == Opcodes.GETFIELD && f1.name.equals("parent")
                && spHead[2].getOpcode() == Opcodes.ACONST_NULL
                && spHead[3] instanceof FieldInsnNode f3 && f3.getOpcode() == Opcodes.PUTFIELD
                        && f3.owner.equals(isoAnimalCls) && f3.name.equals("spottedChr")
                && noSpottedList
                && countExactCalls(vAddMoving, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "isSafeToAdd", "()Z") == 1
                && countFieldTouches(vAddMoving, "zombie/iso/IsoCell", "addList") == 1);
        String losIdxCls = "zombie/mdc/AnimalLosIndex";
        String tryDesc = "(L" + isoAnimalCls + ";L" + babCls + ";Ljava/util/Set;Ljava/util/Stack;)Z";
        MethodNode idxTry = method(distJava, losIdxCls, "handle", tryDesc); // tryHandle 只做重入保護後轉呼叫 handle
        MethodNode idxStep = method(distJava, losIdxCls, "step", "(L" + isoAnimalCls + ";L" + babCls + ";Lzombie/iso/IsoMovingObject;ZFF)I");
        int idxClear = firstCallIndex(idxTry, Opcodes.INVOKEVIRTUAL, "java/util/Stack", "clear", "()V");
        failed += check("W47 helper：快照／候選／比對都在清 spottedList 之前；單一目標處理與 W18-2 同一組委派；零 Rand",
                idxClear != Integer.MAX_VALUE
                && firstCallIndex(idxTry, Opcodes.INVOKESTATIC, losIdxCls, "ensureSnapshot", "(Ljava/util/Set;)Z") < idxClear
                && firstCallIndex(idxTry, Opcodes.INVOKESTATIC, losIdxCls, "collect", "(FFF)I") < idxClear
                && firstCallIndex(idxTry, Opcodes.INVOKESTATIC, losIdxCls, "audit", "(L" + isoAnimalCls + ";FFF)V") < idxClear
                && countExactCalls(idxStep, Opcodes.INVOKESTATIC, spottedPrefilterCls, "spotted",
                        "(L" + babCls + ";Lzombie/iso/IsoMovingObject;ZF)V") == 2
                && countExactCalls(idxStep, Opcodes.INVOKESTATIC, spottedPrefilterCls, "thresholdOf", "(I)F") == 1
                && countExactCalls(idxStep, Opcodes.INVOKESTATIC, "zombie/iso/IsoUtils", "DistanceTo", "(FFFF)F") == 1
                && countExactCalls(idxStep, Opcodes.INVOKEVIRTUAL, "zombie/GameTime", "getMultiplier", "()F") == 1
                && classNode(distJava, losIdxCls).methods.stream()
                        .mapToInt(m -> countCallsToOwner(m, "zombie/core/random/Rand")).sum() == 0);
        failed += check("W47 掛點：AnimalLosScan 恰 1 次 tryHandle，且在完整掃描清 spottedList 之前",
                countExactCalls(scanUpd, Opcodes.INVOKESTATIC, losIdxCls, "tryHandle", tryDesc) == 1
                && firstCallIndex(scanUpd, Opcodes.INVOKESTATIC, losIdxCls, "tryHandle", tryDesc)
                        < firstCallIndex(scanUpd, Opcodes.INVOKEVIRTUAL, "java/util/Stack", "clear", "()V"));

        // 承重前提釘（review B1；grok 前輪 BLOCKING 的失效類）：enforce 的「Δframe 恆 1 ⇒
        // 無 gcd 剩餘類失明」不是數學免疫，而是「server ⇒ FULL ⇒ frameMod==1 ⇒ 每 tick 全跑」
        // 這條 42.20.3 前提鏈。五支結構釘＋helper 端 runtime fail-open 雙保險；任一紅＝
        // TIS 動了排程結構，重驗 gcd 面再出貨。
        // 註（review r2）：這些是「存在性＋計數＋位置」錨，不含分支語意——ifeq 反轉之類的
        // 語意改寫抓不到；該失效面由 fail-open（frameMod≠1 直接 forward）與 client 側的
        // desync 防線（釘⑦）分別兜底，釘的角色是「結構變了就逼人重看」而非證明語意。
        // ① server ⇒ FULL 短路存在性：getUpdateSchedulerSimulationLevelForObject 內
        //    GETSTATIC GameServer.server 恰 1、GETSTATIC FULL ≥ 2（短路回傳＋比較各一）。
        MethodNode vLevelFor = methodFromJar(jar, "zombie/MovingObjectUpdateScheduler",
                "getUpdateSchedulerSimulationLevelForObject",
                "(Lzombie/iso/IsoMovingObject;F)Lzombie/UpdateSchedulerSimulationLevel;");
        // ② 分級節拍：getFrameMod 仍為 1 << getUpdateOrderIndex（真指令恰 5：ICONST_1/ALOAD_0/呼叫/ISHL/IRETURN）。
        MethodNode vGetFrameMod = methodFromJar(jar, "zombie/UpdateSchedulerSimulationLevel",
                "getFrameMod", "()I");
        // ③ 幀計數增量：startFrame 的 frameCounter 更新仍為 lconst_1/ladd（增量改 2 ⇒ gcd(2,N)>1）。
        MethodNode vStartFrame = methodFromJar(jar, "zombie/MovingObjectUpdateScheduler",
                "startFrame", "()V");
        // ④ 子桶分派：bucket.add 仍以 getID() % frameMod 入桶（失明剩餘類的來源形狀）。
        MethodNode vBucketAdd = methodFromJar(jar, "zombie/MovingObjectUpdateSchedulerUpdateBucket",
                "add", "(Lzombie/iso/IsoMovingObject;)V");
        failed += check("W18 承重前提：server⇒FULL 短路、frameMod=1<<idx、startFrame +1、bucket getID%mod",
                countExactFields(vLevelFor, Opcodes.GETSTATIC,
                        "zombie/network/GameServer", "server", "Z") == 1
                && countExactFields(vLevelFor, Opcodes.GETSTATIC,
                        "zombie/UpdateSchedulerSimulationLevel", "FULL",
                        "Lzombie/UpdateSchedulerSimulationLevel;") >= 2
                && realInsnCount(vGetFrameMod) == 5
                && countOpcode(vGetFrameMod, Opcodes.ICONST_1) == 1
                && countOpcode(vGetFrameMod, Opcodes.ISHL) == 1
                && countOpcode(vStartFrame, Opcodes.LCONST_1) == 1
                && countOpcode(vStartFrame, Opcodes.LADD) == 1
                && countExactCalls(vBucketAdd, Opcodes.INVOKEVIRTUAL,
                        "zombie/iso/IsoMovingObject", "getID", "()I") == 1
                && countOpcode(vBucketAdd, Opcodes.IREM) == 1);

        // ⑤ 每幀全桶掃描（review r2 residual——雙保險的共同盲區）：MOUS.update() 每幀對
        //    simulationLevels 全長迴圈各呼叫一次 bucket.update((int)frameCounter)。TIS 若改成
        //    隔幀呼叫，Δframe 變 2 而 frameMod 仍 1 ⇒ fail-open 不觸發、gcd 失明重現——
        //    釘住「update() 內 bucket.update 恰 1（迴圈體）＋getfield simulationLevels 恰 1
        //    ＋getfield frameCounter 恰 1」的迴圈形狀。誠實標記（r3）：這是計數錨——以
        //    frameCounter 取模的隔幀改法會多讀 frameCounter（抓得到），但獨立 boolean toggle
        //    式隔幀（三計數全不變）抓不到，且該失效面 fail-open 依定義不觸發（frameMod 仍 1）
        //    ＝雙保險的殘餘共同盲區；接受理由：TIS 動排程節奏大概率碰 frameCounter/桶結構。
        MethodNode vMousUpdate = methodFromJar(jar, "zombie/MovingObjectUpdateScheduler",
                "update", "()V");
        failed += check("W18 承重前提⑤：MOUS.update 每幀全桶掃描形狀",
                countExactCalls(vMousUpdate, Opcodes.INVOKEVIRTUAL,
                        "zombie/MovingObjectUpdateSchedulerUpdateBucket", "update", "(I)V") == 1
                && countExactFields(vMousUpdate, Opcodes.GETFIELD,
                        "zombie/MovingObjectUpdateScheduler", "simulationLevels",
                        "[Lzombie/MovingObjectUpdateSchedulerUpdateBucket;") == 1
                && countExactFields(vMousUpdate, Opcodes.GETFIELD,
                        "zombie/MovingObjectUpdateScheduler", "frameCounter", "J") == 1);

        // client 支配釘（review I3；前案 §2 表 #1 的不變式落實）：updateInternal 的
        // GameClient.client 短路必須存在且位於 redirect callsite 之前——TIS 把 updateLOS
        // 移出守衛區時此條紅（server-only enforce 會產生 client desync，2n 受精蛋案教訓）。
        failed += check("W18 client 支配：GameClient.client 恰 1 且在 callsite 前",
                countExactFields(vAniUpdInt, Opcodes.GETSTATIC,
                        "zombie/network/GameClient", "client", "Z") == 1
                && firstFieldIndex(vAniUpdInt, Opcodes.GETSTATIC,
                        "zombie/network/GameClient", "client", "Z")
                        < firstCallIndex(vAniUpdInt, Opcodes.INVOKEVIRTUAL,
                                isoAnimalCls, "updateLOS", "()V"));

        // 完備性回歸釘（前案 docs/isoanimal-updatelos-design-v1.md §2 七呼叫點表 #2）：
        // IsoPlayer.updateInternal1 的 isAnimal 短路是「動物走不到玩家版 updateLOS」的結構
        // 前提——isAnimal 恰 1、IsoLivingCharacter.update 恰 2（動物分支＋非動物分支）、
        // IsoPlayer.updateLOS 恰 1（非動物側）。TIS 拆掉分流時此條紅＝W18 只剩半套，重估。
        MethodNode vUpdInt1 = methodFromJar(jar, "zombie/characters/IsoPlayer",
                "updateInternal1", "()V");
        failed += check("W18 完備性：IsoPlayer.updateInternal1 isAnimal 短路仍在",
                countExactCalls(vUpdInt1, Opcodes.INVOKEVIRTUAL,
                        "zombie/characters/IsoPlayer", "isAnimal", "()Z") == 1
                && countExactCalls(vUpdInt1, Opcodes.INVOKESPECIAL,
                        "zombie/characters/IsoLivingCharacter", "update", "()V") == 2
                && countExactCalls(vUpdInt1, Opcodes.INVOKEVIRTUAL,
                        "zombie/characters/IsoPlayer", "updateLOS", "()V") == 1);

        // ---- W19 車輛永久移除授權守衛（observe）----
        String vrgCls = "zombie/mdc/VehicleRemoveGuard";
        String bvCls = "zombie/vehicles/BaseVehicle";
        // vanilla census：全 jar permanentlyRemove 呼叫點恰 4 且逐類分佈釘死（總數＋分佈
        // 雙鎖堵「舊點消失＋新點出現」互抵）。TIS 新增 caller＝observe 分類器過時＝建置紅。
        failed += check("W19 census：全 jar permanentlyRemove 呼叫點恰 4（GlobalObject/RWB/VehicleManager/setSmashed 各 1）",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, bvCls, "permanentlyRemove", "()V") == 4
                && classWideCalls(classNodeFromJar(jar, "zombie/Lua/LuaManager$GlobalObject"),
                        Opcodes.INVOKEVIRTUAL, bvCls, "permanentlyRemove", "()V") == 1
                && classWideCalls(classNodeFromJar(jar, "zombie/randomizedWorld/RandomizedWorldBase"),
                        Opcodes.INVOKEVIRTUAL, bvCls, "permanentlyRemove", "()V") == 1
                && classWideCalls(classNodeFromJar(jar, "zombie/vehicles/VehicleManager"),
                        Opcodes.INVOKEVIRTUAL, bvCls, "permanentlyRemove", "()V") == 1
                && classWideCalls(classNodeFromJar(jar, bvCls),
                        Opcodes.INVOKEVIRTUAL, bvCls, "permanentlyRemove", "()V") == 1);
        // vanilla 前提：GlobalObject.removeVehicle 的 server 死路徑守衛（!GameServer.server
        // 才直呼 permanentlyRemove）。TIS 拿掉守衛＝該路徑在 server 復活，caller 分類重驗。
        MethodNode vGoRemove = methodFromJar(jar, "zombie/Lua/LuaManager$GlobalObject",
                "removeVehicle", "(Lzombie/characters/IsoPlayer;Lzombie/vehicles/BaseVehicle;)V");
        failed += check("W19 vanilla 前提：GlobalObject.removeVehicle 有 GameServer.server 守衛（server 死路徑）",
                countExactFields(vGoRemove, Opcodes.GETSTATIC,
                        "zombie/network/GameServer", "server", "Z") == 1
                && countExactCalls(vGoRemove, Opcodes.INVOKEVIRTUAL,
                        bvCls, "permanentlyRemove", "()V") == 1);
        // 手術後：permanentlyRemove 頭部 headCall 全序、真指令恰 +2（原體未動）。
        MethodNode pPermRemove = method(distJava, bvCls, "permanentlyRemove", "()V");
        MethodNode vPermRemove = methodFromJar(jar, bvCls, "permanentlyRemove", "()V");
        failed += check("W19 手術後：permanentlyRemove 頭部 headCall 全序、真指令恰 +2",
                headCallOk(pPermRemove, vrgCls, "onRemove", "(L" + bvCls + ";)V")
                && realInsnCount(pPermRemove) == realInsnCount(vPermRemove) + 2);
        // helper 契約：onRemove 零 permanentlyRemove 呼叫（防遞迴）、getStackTrace 恰 1、
        // 觀測唯讀——onRemove 與 claimStateOf 皆零 KahluaTable.rawset（不寫 modData）。
        MethodNode gVrgOnRemove = method(distJava, vrgCls, "onRemove", "(L" + bvCls + ";)V");
        MethodNode gVrgClaim = method(distJava, vrgCls, "claimStateOf",
                "(Lse/krka/kahlua/vm/KahluaTable;)Ljava/lang/String;");
        failed += check("W19 helper 契約：零遞迴、getStackTrace 恰 1、claim/onRemove 零 rawset（唯讀）",
                countExactCalls(gVrgOnRemove, Opcodes.INVOKEVIRTUAL, bvCls, "permanentlyRemove", "()V") == 0
                && countExactCalls(gVrgOnRemove, Opcodes.INVOKEVIRTUAL, "java/lang/Thread",
                        "getStackTrace", "()[Ljava/lang/StackTraceElement;") == 1
                && countExactCalls(gVrgOnRemove, Opcodes.INVOKEINTERFACE,
                        "se/krka/kahlua/vm/KahluaTable", "rawset",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V") == 0
                && countExactCalls(gVrgClaim, Opcodes.INVOKEINTERFACE,
                        "se/krka/kahlua/vm/KahluaTable", "rawset",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V") == 0);

        // ---- W20 衣物同步守衛 ----
        String csgCls = "zombie/mdc/ClothingSyncGuard";
        String scpCls = "zombie/network/packets/SyncClothingPacket";
        String idCls = "zombie/network/packets/SyncClothingPacket$ItemDescription";
        String svpCls = "zombie/network/packets/SyncVisualsPacket";
        String ivCls = "zombie/core/skinnedmodel/visual/ItemVisual";
        String w20Ic = "zombie/core/ImmutableColor";
        String pidCls = "zombie/network/fields/character/PlayerID";
        // (a) ContainerIdProbe 已於 2026-09-27 退役（每 session 0–3 次 square-null，原版低頻現象）。
        String wornCtorDesc = "(Lzombie/characters/WornItems/WornItem;)V";
        String svpParseDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        String getPlayerDesc = "()Lzombie/characters/IsoPlayer;";
        // vanilla 前提 (b)：ctor 對 baseTexture/textureChoice 有守衛（IFNONNULL 恰 2）、
        // getVisual 恰 5、getTint 恰 1 且無守衛＝TIS 自己防兩行漏第三行的結構事實。
        // TIS 補上守衛（IFNONNULL 變 3）＝本刀 (b) 撤刀訊號，建置紅提醒。
        MethodNode vIdCtor = methodFromJar(jar, idCls, "<init>", wornCtorDesc);
        failed += check("W20 vanilla (b)：ctor getVisual=5、getTint=1、IFNONNULL=2（tint 獨漏守衛）",
                countExactCalls(vIdCtor, Opcodes.INVOKEVIRTUAL, "zombie/inventory/InventoryItem",
                        "getVisual", "()L" + ivCls + ";") == 5
                && countExactCalls(vIdCtor, Opcodes.INVOKEVIRTUAL, ivCls, "getTint",
                        "()L" + w20Ic + ";") == 1
                && countOpcode(vIdCtor, Opcodes.IFNONNULL) == 2);
        // vanilla 前提 (b)：write 無條件解參考 tint（GETFIELD tint 恰 4）＝ctor 若被繞過
        // （tint 存成 null），write 是第二個 NPE 點——enforce white 保序列化的存在理由。
        MethodNode vIdWrite = methodFromJar(jar, idCls, "write",
                "(Lzombie/core/network/ByteBufferWriter;)V");
        failed += check("W20 vanilla (b)：write 內 GETFIELD tint 恰 4（第二 NPE 點）",
                countExactFields(vIdWrite, Opcodes.GETFIELD, idCls, "tint", "L" + w20Ic + ";") == 4);
        // vanilla 前提（禁止過濾的行為錨）：process 會把封包未列出的 worn item 從遠端
        // WornItems.remove——「lambda 過濾整件」＝遠端脫裝，此錨紅時重估該結論。
        MethodNode vScpProcess = methodFromJar(jar, scpCls, "process", "()V");
        failed += check("W20 vanilla：process 內 WornItems.remove(InventoryItem) 恰 1（過濾＝脫裝的行為錨）",
                countExactCalls(vScpProcess, Opcodes.INVOKEVIRTUAL,
                        "zombie/characters/WornItems/WornItems", "remove",
                        "(Lzombie/inventory/InventoryItem;)V") == 1);
        // vanilla 前提 (c)：parse 內 getPlayer 恰 3、error(Object) 恰 1、getItemVisuals 恰 1
        // （server 本地重建 vs wire count 的比對結構）。
        MethodNode vSvpParse = methodFromJar(jar, svpCls, "parse", svpParseDesc);
        failed += check("W20 vanilla (c)：parse getPlayer=3、DebugType.error(Object)=1、getItemVisuals=1",
                countExactCalls(vSvpParse, Opcodes.INVOKEVIRTUAL, pidCls, "getPlayer", getPlayerDesc) == 3
                && countExactCalls(vSvpParse, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType",
                        "error", "(Ljava/lang/Object;)V") == 1
                && countExactCalls(vSvpParse, Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoPlayer",
                        "getItemVisuals", "(Lzombie/core/skinnedmodel/visual/ItemVisuals;)V") == 1);
        MethodNode vScpSet = methodFromJar(jar, scpCls, "set", "(Lzombie/characters/IsoPlayer;)V");
        MethodNode pScpSet = method(distJava, scpCls, "set", "(Lzombie/characters/IsoPlayer;)V");
        failed += check("W20 手術後 (b)：SyncClothingPacket.set 頭部 aload_1→onClothingSet、真指令恰 +2",
                headCallSlotsOk(pScpSet, csgCls, "onClothingSet", "(Lzombie/characters/IsoPlayer;)V", 1)
                && realInsnCount(pScpSet) == realInsnCount(vScpSet) + 2);
        MethodNode pIdCtor = method(distJava, idCls, "<init>", wornCtorDesc);
        failed += check("W20 手術後 (b)：ctor tintOf 改道 x1、原 getTint 歸零（真指令對帳併入 W20-2 的 +2）",
                countExactCalls(pIdCtor, Opcodes.INVOKESTATIC, csgCls, "tintOf",
                        "(L" + ivCls + ";)L" + w20Ic + ";") == 1
                && countExactCalls(pIdCtor, Opcodes.INVOKEVIRTUAL, ivCls, "getTint",
                        "()L" + w20Ic + ";") == 0);
        MethodNode pSvpParse = method(distJava, svpCls, "parse", svpParseDesc);
        failed += check("W20 手術後 (c)：parse parsePlayer x3＋onVisualsMismatch x1、原呼叫歸零、真指令不變",
                countExactCalls(pSvpParse, Opcodes.INVOKESTATIC, csgCls, "parsePlayer",
                        "(L" + pidCls + ";)Lzombie/characters/IsoPlayer;") == 3
                && countExactCalls(pSvpParse, Opcodes.INVOKESTATIC, csgCls, "onVisualsMismatch",
                        "(Lzombie/debug/DebugType;Ljava/lang/Object;)V") == 1
                && countExactCalls(pSvpParse, Opcodes.INVOKEVIRTUAL, pidCls, "getPlayer", getPlayerDesc) == 0
                && countExactCalls(pSvpParse, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType",
                        "error", "(Ljava/lang/Object;)V") == 0
                && realInsnCount(pSvpParse) == realInsnCount(vSvpParse));
        // 負對照：write() 也讀 getPlayer/getItemVisuals，redirect 是 method-scope——write 未動。
        MethodNode vSvpWrite = methodFromJar(jar, svpCls, "write",
                "(Lzombie/core/network/ByteBufferWriter;)V");
        MethodNode pSvpWrite = method(distJava, svpCls, "write",
                "(Lzombie/core/network/ByteBufferWriter;)V");
        failed += check("W20 負對照：SyncVisualsPacket.write 未被改動（getPlayer 數與真指令數同）",
                countExactCalls(pSvpWrite, Opcodes.INVOKEVIRTUAL, pidCls, "getPlayer", getPlayerDesc)
                        == countExactCalls(vSvpWrite, Opcodes.INVOKEVIRTUAL, pidCls, "getPlayer", getPlayerDesc)
                && realInsnCount(pSvpWrite) == realInsnCount(vSvpWrite));
        // helper 契約：tintOf 的 getTint 委派恰 2（off 直通＋非 null 主路徑）、white 引用恰 2
        // （nullVisual/nullTint 兩個 enforce 出口）；onVisualsMismatch 的 error 委派恰 1
        // （唯一出口，off/observe 同一 sink）；parsePlayer 的 getPlayer 委派恰 1。
        MethodNode gTintOf = method(distJava, csgCls, "tintOf", "(L" + ivCls + ";)L" + w20Ic + ";");
        MethodNode gMismatch = method(distJava, csgCls, "onVisualsMismatch",
                "(Lzombie/debug/DebugType;Ljava/lang/Object;)V");
        MethodNode gParsePlayer = method(distJava, csgCls, "parsePlayer",
                "(L" + pidCls + ";)Lzombie/characters/IsoPlayer;");
        failed += check("W20 helper 契約：tintOf 委派2/white 引用2；mismatch error 出口1；parsePlayer 委派1",
                countExactCalls(gTintOf, Opcodes.INVOKEVIRTUAL, ivCls, "getTint", "()L" + w20Ic + ";") == 2
                && countExactFields(gTintOf, Opcodes.GETSTATIC, w20Ic, "white", "L" + w20Ic + ";") == 2
                && countExactCalls(gMismatch, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType",
                        "error", "(Ljava/lang/Object;)V") == 1
                && countExactCalls(gParsePlayer, Opcodes.INVOKEVIRTUAL, pidCls, "getPlayer", getPlayerDesc) == 1);

        // ---- W20-2：ItemDescription ctor 頭部 headCall 捕 WornItem（nullVisual 歸因）----
        // ctor 頭部 aload_1 只碰參數不碰 uninitializedThis；真指令 +2；tintOf 改道不受影響。
        failed += check("W20-2 手術後：ctor 頭部 aload_1→onItemDescription、真指令恰 +2、tintOf 仍 x1",
                headCallSlotsOk(pIdCtor, csgCls, "onItemDescription", wornCtorDesc, 1)
                && realInsnCount(pIdCtor) == realInsnCount(vIdCtor) + 2
                && countExactCalls(pIdCtor, Opcodes.INVOKESTATIC, csgCls, "tintOf",
                        "(L" + ivCls + ";)L" + w20Ic + ";") == 1);
        MethodNode gOnItemDesc = method(distJava, csgCls, "onItemDescription", wornCtorDesc);
        failed += check("W20-2 helper 契約：onItemDescription 純 ThreadLocal.set（零 NEW、零 DebugLog、零 invokevirtual on WornItem）",
                countOpcode(gOnItemDesc, Opcodes.NEW) == 0
                && countCallsToOwner(gOnItemDesc, "zombie/debug/DebugLog") == 0
                && countCallsToOwner(gOnItemDesc, "zombie/characters/WornItems/WornItem") == 0
                && countExactCalls(gOnItemDesc, Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal",
                        "set", "(Ljava/lang/Object;)V") == 1);

        // 42.21.0 官方已修：IsoThumpable.setHealth 加 getObjectIndex 守衛，抑噪 #9（syncIsoObject println 改道）
        // 與其結構斷言隨 patch 退役。

        // ---- W34 伺服器角色聲音參數跳過（IsoGameCharacter.updateEmitter）----
        // vanilla 前提：updateEmitter 內 FMODParameterList.update 恰 1 且為 class 唯一呼叫點。
        // （server emitter 為 Dummy＝跳過無讀者的依據，由 docs/patches.md 2aw 反編譯出處記錄。）
        String fplCls = "zombie/audio/FMODParameterList";
        String epgCls = "zombie/mdc/EmitterParamGate";
        String epgDesc = "(L" + fplCls + ";)V";
        MethodNode vEmit = methodFromJar(jar, igcCls, "updateEmitter", "()V");
        failed += check("W34 vanilla 前提：updateEmitter 內 FMODParameterList.update=1、class-wide=1",
                countExactCalls(vEmit, Opcodes.INVOKEVIRTUAL, fplCls, "update", "()V") == 1
                && classWideCalls(vIgcNode, Opcodes.INVOKEVIRTUAL, fplCls, "update", "()V") == 1);
        MethodNode pEmit = method(distJava, igcCls, "updateEmitter", "()V");
        failed += check("W34 手術後：updateEmitter 改道 x1、原呼叫歸零、真指令不變；class-wide 改道恰 1",
                countExactCalls(pEmit, Opcodes.INVOKESTATIC, epgCls, "update", epgDesc) == 1
                && countExactCalls(pEmit, Opcodes.INVOKEVIRTUAL, fplCls, "update", "()V") == 0
                && realInsnCount(pEmit) == realInsnCount(vEmit)
                && classWideCalls(pIgcNode, Opcodes.INVOKESTATIC, epgCls, "update", epgDesc) == 1);
        MethodNode gEmit = method(distJava, epgCls, "update", epgDesc);
        failed += check("W34 helper 契約：update 零 NEW、零 DebugLog（心跳在獨立方法）、跳過條件讀 GameServer.server",
                countOpcode(gEmit, Opcodes.NEW) == 0
                && countCallsToOwner(gEmit, "zombie/debug/DebugLog") == 0
                && countExactFields(gEmit, Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z") == 1);

        // ---- W35 使用中玩家索引（GameEntity usingPlayer 寫入點＋UsingPlayerUpdateSystem.update）----
        // vanilla 前提（索引完整性的根據）：usingPlayer 是 private，全 class 恰 7 個 putfield——
        // setUsingPlayer 1、receiveUpdateUsingPlayer 3、receiveSyncEntity 2、reset 1（寫 null）。
        // TIS 新增寫入點時此條紅＝索引會漏，必須補追蹤點。
        String geCls = "zombie/entity/GameEntity";
        String upiCls = "zombie/entity/MdcUsingPlayerIndex";
        String upDesc = "Lzombie/characters/IsoPlayer;";
        String rcvDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        ClassNode vGe = classNodeFromJar(jar, geCls);
        int vPut = vGe.methods.stream().mapToInt(m -> countExactFields(m, Opcodes.PUTFIELD, geCls, "usingPlayer", upDesc)).sum();
        failed += check("W35 vanilla 前提：usingPlayer 為 private、全 class putfield 恰 7（set 1／receiveUpdate 3／receiveSync 2／reset 1）",
                vGe.fields.stream().anyMatch(f -> f.name.equals("usingPlayer") && (f.access & Opcodes.ACC_PRIVATE) != 0)
                && vPut == 7
                && countExactFields(methodFromJar(jar, geCls, "setUsingPlayer", "(" + upDesc + ")V"), Opcodes.PUTFIELD, geCls, "usingPlayer", upDesc) == 1
                && countExactFields(methodFromJar(jar, geCls, "receiveUpdateUsingPlayer", rcvDesc), Opcodes.PUTFIELD, geCls, "usingPlayer", upDesc) == 3
                && countExactFields(methodFromJar(jar, geCls, "receiveSyncEntity", rcvDesc), Opcodes.PUTFIELD, geCls, "usingPlayer", upDesc) == 2
                && countExactFields(methodFromJar(jar, geCls, "reset", "()V"), Opcodes.PUTFIELD, geCls, "usingPlayer", upDesc) == 1);
        MethodNode vUpsUpd = methodFromJar(jar, "zombie/entity/UsingPlayerUpdateSystem", "update", "()V");
        MethodNode pUpsUpd = method(distJava, "zombie/entity/UsingPlayerUpdateSystem", "update", "()V");
        failed += check("W35 update：getEntities vanilla 恰 1→改道 x1、原呼叫歸零、真指令不變",
                countExactCalls(vUpsUpd, Opcodes.INVOKEVIRTUAL, "zombie/entity/EntityBucket", "getEntities", "()Lzombie/entity/util/ImmutableArray;") == 1
                && countExactCalls(pUpsUpd, Opcodes.INVOKEVIRTUAL, "zombie/entity/EntityBucket", "getEntities", "()Lzombie/entity/util/ImmutableArray;") == 0
                && countExactCalls(pUpsUpd, Opcodes.INVOKESTATIC, upiCls, "entities", "(Lzombie/entity/EntityBucket;)Lzombie/entity/util/ImmutableArray;") == 1
                && realInsnCount(pUpsUpd) == realInsnCount(vUpsUpd));
        ClassNode pGe = classNode(distJava, geCls);
        failed += check("W35 GameEntity 追蹤點：setUsingPlayer headCall 1、receiveUpdate tail 1、receiveSync tail 2、class-wide 合計 4",
                countExactCalls(method(distJava, geCls, "setUsingPlayer", "(" + upDesc + ")V"), Opcodes.INVOKESTATIC, upiCls, "onSetUsingPlayer", "(L" + geCls + ";" + upDesc + ")V") == 1
                && countExactCalls(method(distJava, geCls, "receiveUpdateUsingPlayer", rcvDesc), Opcodes.INVOKESTATIC, upiCls, "afterReceive", "(L" + geCls + ";)V") == 1
                && countExactCalls(method(distJava, geCls, "receiveSyncEntity", rcvDesc), Opcodes.INVOKESTATIC, upiCls, "afterReceive", "(L" + geCls + ";)V") == 2
                && classWideCalls(pGe, Opcodes.INVOKESTATIC, upiCls, "onSetUsingPlayer", "(L" + geCls + ";" + upDesc + ")V")
                   + classWideCalls(pGe, Opcodes.INVOKESTATIC, upiCls, "afterReceive", "(L" + geCls + ";)V") == 4);

        // ---- W36 GameEntity 廣播收件範圍＋CraftLogic 同步變化閘 ----
        String genCls = "zombie/entity/GameEntityNetwork";
        String inpCls = "zombie/network/packets/INetworkPacket";
        String gebCls = "zombie/mdc/GameEntityBroadcastGate";
        String clCls = "zombie/entity/components/crafting/CraftLogic";
        String clsCls = "zombie/entity/components/crafting/CraftLogicSystem";
        String dclCls = "zombie/entity/components/crafting/DryingCraftLogic";
        String craftSyncCls = "zombie/entity/components/crafting/MdcCraftSyncGate";
        String crdCls = "zombie/entity/components/crafting/recipe/CraftRecipeData";
        String sendAllDesc = "(Lzombie/network/PacketTypes$PacketType;Lzombie/network/IConnection;[Ljava/lang/Object;)V";
        String sendOneDesc = "(Lzombie/network/IConnection;Lzombie/network/PacketTypes$PacketType;[Ljava/lang/Object;)V";
        String spdDesc = "(Lzombie/entity/network/EntityPacketData;Lzombie/entity/GameEntity;Lzombie/entity/Component;"
                + "Lzombie/network/IConnection;Z)V";
        String clUpdDesc = "(L" + crdCls + ";)V";
        String clStopDesc = "(L" + clCls + ";L" + crdCls + ";ZLzombie/entity/components/resources/ResourceGroup;)V";
        String clHelperDesc = "(L" + clCls + ";)V";
        MethodNode vSpd = methodFromJar(jar, genCls, "sendPacketData", spdDesc);
        MethodNode pSpd = method(distJava, genCls, "sendPacketData", spdDesc);
        // vanilla 前提：廣播分支唯一 sendToAll 的 values＝{data, entity, component}、排除連線＝參數 3、
        // 型別 GameEntity；helper 以 values[1] 取實體座標、values[0] 量 payload。release 在其後。
        failed += check("W36 vanilla：sendPacketData 的 sendToAll 恰 1（class-wide 1）、args＝GameEntity／slot3／{0,1,2}、release 在其後",
                countExactCalls(vSpd, Opcodes.INVOKESTATIC, inpCls, "sendToAll", sendAllDesc) == 1
                && classWideCalls(classNodeFromJar(jar, genCls), Opcodes.INVOKESTATIC, inpCls, "sendToAll", sendAllDesc) == 1
                && gameEntitySendToAllArgs(vSpd, inpCls, sendAllDesc)
                && lastCallIndex(vSpd, Opcodes.INVOKESTATIC, inpCls, "sendToAll", sendAllDesc)
                        < firstCallIndex(vSpd, Opcodes.INVOKESTATIC, "zombie/entity/network/EntityPacketData",
                                "release", "(Lzombie/entity/network/EntityPacketData;)V"));
        // helper 複製的是原版 sendToAll 迴圈的收件條件：排除 GUID、fully-connected、逐連線 send，原版不看距離。
        MethodNode vSendAll = methodFromJar(jar, inpCls, "sendToAll", sendAllDesc);
        failed += check("W36 vanilla：INetworkPacket.sendToAll 只有 GUID 排除＋isFullyConnected＋send、零 relevancy 判定",
                countExactCalls(vSendAll, Opcodes.INVOKEVIRTUAL, "zombie/core/raknet/UdpConnection", "getConnectedGUID", "()J") == 1
                && countExactCalls(vSendAll, Opcodes.INVOKEINTERFACE, "zombie/network/IConnection", "getConnectedGUID", "()J") == 1
                && countExactCalls(vSendAll, Opcodes.INVOKEVIRTUAL, "zombie/core/raknet/UdpConnection", "isFullyConnected", "()Z") == 1
                && countExactCalls(vSendAll, Opcodes.INVOKESTATIC, inpCls, "send", sendOneDesc) == 1
                && countCalls(vSendAll, "zombie/core/raknet/UdpConnection", "isRelevantTo") == 0
                && countCalls(vSendAll, "zombie/core/raknet/UdpConnection", "RelevantTo") == 0);
        failed += check("W36 patched：sendPacketData 改道 x1、原 sendToAll 歸零、client／單連線 send 原樣、真指令不變",
                countExactCalls(pSpd, Opcodes.INVOKESTATIC, gebCls, "sendToAll", sendAllDesc) == 1
                && countExactCalls(pSpd, Opcodes.INVOKESTATIC, inpCls, "sendToAll", sendAllDesc) == 0
                && countExactCalls(pSpd, Opcodes.INVOKESTATIC, inpCls, "send", "(Lzombie/network/PacketTypes$PacketType;[Ljava/lang/Object;)V") == 1
                && countExactCalls(pSpd, Opcodes.INVOKESTATIC, inpCls, "send", sendOneDesc) == 1
                && realInsnCount(pSpd) == realInsnCount(vSpd)
                && classRealInsnCount(classNode(distJava, genCls)) == classRealInsnCount(classNodeFromJar(jar, genCls)));
        // helper：逐連線 send 恰 1 且不在 try 內（送包例外語意同原版）；原版委派 3（off／簿記停用／位置不可信）；
        // 收件條件與原版同（GUID 排除＋fully-connected），範圍判定在 try 內且 catch 僅 RuntimeException。
        MethodNode gGeb = method(distJava, gebCls, "sendToAll", sendAllDesc);
        failed += check("W36 helper：send 1 不在 try、sendToAll 委派 3、GUID／fully-connected 條件同原版、verdict 在 try 內、catch 僅 RuntimeException",
                countExactCalls(gGeb, Opcodes.INVOKESTATIC, inpCls, "send", sendOneDesc) == 1
                && callsInsideTryRange(gGeb, Opcodes.INVOKESTATIC, inpCls, "send", sendOneDesc) == 0
                && countExactCalls(gGeb, Opcodes.INVOKESTATIC, inpCls, "sendToAll", sendAllDesc) == 3
                && countExactCalls(gGeb, Opcodes.INVOKEVIRTUAL, "zombie/core/raknet/UdpConnection", "getConnectedGUID", "()J") == 1
                && countExactCalls(gGeb, Opcodes.INVOKEINTERFACE, "zombie/network/IConnection", "getConnectedGUID", "()J") == 1
                && countExactCalls(gGeb, Opcodes.INVOKEVIRTUAL, "zombie/core/raknet/UdpConnection", "isFullyConnected", "()Z") == 1
                && callsInsideTryRange(gGeb, Opcodes.INVOKESTATIC, rwCls, "verdict", "(Lzombie/core/raknet/UdpConnection;FF)I") == 1
                && gGeb.tryCatchBlocks.stream().allMatch(t -> "java/lang/RuntimeException".equals(t.type)));
        // 「遠方玩家進入範圍時的狀態」前提：chunk 下載以 live 序列化（SaveLoadedChunk）送出，
        // IsoObject.save→saveEntity→Component.save；CraftLogic.save 含 in-progress 清單、
        // DryingCraftLogic.save 追加濕度。TIS 改掉任一環，被略過的連線就可能拿到舊狀態＝撤刀重估。
        failed += check("W36 vanilla：chunk 帶 live CraftLogic 狀態（SaveLoadedChunk／saveEntity／Component.save／in-progress／濕度）",
                countExactCalls(methodFromJar(jar, "zombie/network/PlayerDownloadServer", "update", "()V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunk", "SaveLoadedChunk",
                        "(Lzombie/network/ClientChunkRequest$Chunk;Ljava/util/zip/CRC32;)V") == 1
                && countExactCalls(methodFromJar(jar, "zombie/iso/IsoObject", "save", "(Ljava/nio/ByteBuffer;Z)V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoObject", "saveEntity", "(Ljava/nio/ByteBuffer;)V") == 1
                && countExactCalls(methodFromJar(jar, "zombie/entity/GameEntity", "saveEntity", "(Ljava/nio/ByteBuffer;)V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/entity/Component", "save", "(Ljava/nio/ByteBuffer;)V") == 1
                && countExactCalls(methodFromJar(jar, clCls, "save", "(Ljava/nio/ByteBuffer;)V"),
                        Opcodes.INVOKEVIRTUAL, clCls, "saveInProgessCraftData", "(Ljava/nio/ByteBuffer;)V") == 1
                && countExactCalls(methodFromJar(jar, dclCls, "save", "(Ljava/nio/ByteBuffer;)V"),
                        Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "putDouble", "(D)Ljava/nio/ByteBuffer;") == 1);
        // CraftLogic 前提：週期同步只在 onUpdate（limit.Check 之後），明確同步只在 CraftLogicSystem.stop
        // （finaliseRecipe 之後）；全 jar Java 呼叫點恰 2。同步內容＝整份 save＋server 廣播出口。
        // DryingCraftLogic 不自送、onUpdate 走 super；濕度只在 private temporaryWetnesses（helper 反射讀）。
        MethodNode vClUpd = methodFromJar(jar, clCls, "onUpdate", clUpdDesc);
        MethodNode vClStop = methodFromJar(jar, clsCls, "stop", clStopDesc);
        MethodNode vClSync = methodFromJar(jar, clCls, "sendCraftLogicSync", "()V");
        failed += check("W36 vanilla：sendCraftLogicSync 全 jar 2 處（onUpdate 在 Check 後／stop 在 finalise 後）、內容＝save＋sendServerPacket、Drying 不自送且有 temporaryWetnesses",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 2
                && countExactCalls(vClUpd, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 1
                && firstCallIndex(vClUpd, Opcodes.INVOKEVIRTUAL, "zombie/core/utils/UpdateLimit", "Check", "()Z")
                        < firstCallIndex(vClUpd, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V")
                && countExactCalls(vClStop, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 1
                && firstCallIndex(vClStop, Opcodes.INVOKEVIRTUAL, clCls, "finaliseRecipe", clUpdDesc)
                        < firstCallIndex(vClStop, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V")
                && countExactCalls(vClSync, Opcodes.INVOKEVIRTUAL, clCls, "save", "(Ljava/nio/ByteBuffer;)V") == 1
                && countExactCalls(vClSync, Opcodes.INVOKEVIRTUAL, clCls, "sendServerPacket",
                        "(Lzombie/entity/network/EntityPacketData;Lzombie/core/raknet/UdpConnection;)V") == 1
                && classWideCalls(classNodeFromJar(jar, dclCls), Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 0
                && classWideCalls(classNodeFromJar(jar, dclCls), Opcodes.INVOKEVIRTUAL, dclCls, "sendCraftLogicSync", "()V") == 0
                && countExactCalls(methodFromJar(jar, dclCls, "onUpdate", clUpdDesc), Opcodes.INVOKESPECIAL, clCls, "onUpdate", clUpdDesc) == 1
                && hasField(classNodeFromJar(jar, dclCls), "temporaryWetnesses", "Ljava/util/HashMap;"));
        MethodNode pClUpd = method(distJava, clCls, "onUpdate", clUpdDesc);
        MethodNode pClStop = method(distJava, clsCls, "stop", clStopDesc);
        failed += check("W36 patched：onUpdate→periodicSync、stop→explicitSync 各 x1、原呼叫歸零、兩 class 真指令總數不變",
                countExactCalls(pClUpd, Opcodes.INVOKESTATIC, craftSyncCls, "periodicSync", clHelperDesc) == 1
                && countExactCalls(pClUpd, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 0
                && countExactCalls(pClStop, Opcodes.INVOKESTATIC, craftSyncCls, "explicitSync", clHelperDesc) == 1
                && countExactCalls(pClStop, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 0
                && classRealInsnCount(classNode(distJava, clCls)) == classRealInsnCount(classNodeFromJar(jar, clCls))
                && classRealInsnCount(classNode(distJava, clsCls)) == classRealInsnCount(classNodeFromJar(jar, clsCls)));
        // helper：週期路徑的原版送出 3 處（off／簽章失敗／內容變化）；明確同步先送再記基準；
        // 基準表是 WeakHashMap（不釘住已釋放的 component），類內零 HashMap 配置。
        MethodNode gPeriodic = method(distJava, craftSyncCls, "periodicSync", clHelperDesc);
        MethodNode gExplicit = method(distJava, craftSyncCls, "explicitSync", clHelperDesc);
        MethodNode gCsgClinit = method(distJava, craftSyncCls, "<clinit>", "()V");
        failed += check("W36 helper：periodicSync 送出 3、explicitSync 送出 1 且先於 signature、基準表 WeakHashMap 1、零 HashMap",
                countExactCalls(gPeriodic, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 3
                && countExactCalls(gExplicit, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V") == 1
                && firstCallIndex(gExplicit, Opcodes.INVOKEVIRTUAL, clCls, "sendCraftLogicSync", "()V")
                        < firstCallIndex(gExplicit, Opcodes.INVOKESTATIC, craftSyncCls, "signature", "(L" + clCls + ";)J")
                && countNew(gCsgClinit, "java/util/WeakHashMap") == 1
                && classNode(distJava, craftSyncCls).methods.stream().allMatch(m -> countNew(m, "java/util/HashMap") == 0));

        // ---- W10-C 靜默打斷觀測（processServer 的 stopPlayerActions）；C／R 兩點於 42.21 對版退役 ----
        String taProbeCls = "zombie/core/MdcTimedActionProbe";
        String amCls = "zombie/core/ActionManager";
        String pidCls2 = "zombie/network/fields/character/PlayerID";
        String stopDesc = "(L" + pidCls2 + ";)V";
        String amRemoveDesc = "(L" + pidCls2 + ";BZ)V";
        // vanilla 前提 (B)：processServer 內 stopPlayerActions 恰 1；ActionManager.remove 內
        // startPacket 恰 1（＝client 分支的 GeneralAction Reject；server 分支零封包＝「不通知
        // client」的結構事實）。TIS 在 server 分支補通知時此條紅＝enforce 補送 Reject 撤刀訊號。
        MethodNode vAmRemove = methodFromJar(jar, amCls, "remove", amRemoveDesc);
        failed += check("W10-C vanilla (B)：processServer stopPlayerActions=1；ActionManager.remove startPacket 恰 1（server 分支零封包）",
                countExactCalls(vNtaProcess, Opcodes.INVOKESTATIC, amCls, "stopPlayerActions", stopDesc) == 1
                && countCalls(vAmRemove, "zombie/core/raknet/UdpConnection", "startPacket") == 1
                && countExactFields(vAmRemove, Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z") == 1);
        failed += check("W10-C 手術後：stopPlayerActions 改道 x1、原呼叫歸零",
                countExactCalls(pNtaProcess, Opcodes.INVOKESTATIC, taProbeCls, "stopPlayerActions", stopDesc) == 1
                && countExactCalls(pNtaProcess, Opcodes.INVOKESTATIC, amCls, "stopPlayerActions", stopDesc) == 0
                && realInsnCount(pNtaProcess) == realInsnCount(vNtaProcess));
        // 退役（42.21 對版）：C 的 start tailCall、R 的 update 三改道；W10-E 的 stop 手術（官方已修）。
        // ActionManager 因此不再出貨；NetTimedAction.start 回到原版。
        failed += check("W10-C (C)/(R)＋W10-E 退役：ActionManager 不出貨、NetTimedAction.start 與原版同指令數且不呼叫 helper",
                !Files.exists(distJava.resolve(amCls + ".class"))
                && realInsnCount(method(distJava, ntaCls, "start", "()V"))
                        == realInsnCount(methodFromJar(jar, ntaCls, "start", "()V"))
                && countCalls(method(distJava, ntaCls, "start", "()V"), taProbeCls, "onStart") == 0);
        // helper 契約：stopPlayerActions 委派恰 1；enforce 補送 Reject 的 write 恰 1（sendReject）且與 vanilla
        // 同一組 doPacket/send API。
        MethodNode gStop = method(distJava, taProbeCls, "stopPlayerActions", stopDesc);
        MethodNode gSendReject = method(distJava, taProbeCls, "sendReject", "(Lzombie/core/Action;)Z");
        failed += check("W10-C helper 契約：stopPlayerActions 委派 1；sendReject write=1/doPacket=1/send=1",
                countExactCalls(gStop, Opcodes.INVOKESTATIC, amCls, "stopPlayerActions", stopDesc) == 1
                && countExactCalls(gSendReject, Opcodes.INVOKEVIRTUAL, "zombie/core/Action", "write",
                        "(Lzombie/core/network/ByteBufferWriter;)V") == 1
                && countCalls(gSendReject, "zombie/network/PacketTypes$PacketType", "doPacket") == 1
                && countCalls(gSendReject, "zombie/network/PacketTypes$PacketType", "send") == 1);

        // W10-E 退役依據（42.21 官方修正）：stop 以 (PlayerID,id) 呼叫 remove；remove 的兩個 lambda 同時比 id 與
        // PlayerID.getID；GeneralActionPacket.setReject 帶 IsoPlayer 並寫入 playerId。退回只比 id 時本條紅＝重估 W10-E。
        String actionCls = "zombie/core/Action";
        String amStopDesc = "(L" + actionCls + ";)V";
        MethodNode vAmStop = methodFromJar(jar, amCls, "stop", amStopDesc);
        ClassNode vAmNode = classNodeFromJar(jar, amCls);
        int removeLambdas = 0;
        boolean removeLambdaOwnerKeyed = true;
        for (MethodNode m : vAmNode.methods) {
            if (!m.name.startsWith("lambda$remove$")) {
                continue;
            }
            removeLambdas++;
            removeLambdaOwnerKeyed &= countExactFields(m, Opcodes.GETFIELD, actionCls, "id", "B") == 1
                    && countExactFields(m, Opcodes.GETFIELD, actionCls, "playerId", "L" + pidCls2 + ";") == 1
                    && countExactCalls(m, Opcodes.INVOKEVIRTUAL, pidCls2, "getID", "()S") == 2;
        }
        String generalPacketCls = "zombie/network/packets/GeneralActionPacket";
        MethodNode vSetReject = methodFromJar(jar, generalPacketCls, "setReject", "(BLzombie/characters/IsoPlayer;)V");
        failed += check("W10-E 退役依據：stop→remove(PlayerID,B,Z) 恰 1、remove lambda 以 (id, PlayerID.getID) 分鍵、setReject 寫入發送者",
                countExactCalls(vAmStop, Opcodes.INVOKESTATIC, amCls, "remove", amRemoveDesc) == 1
                && removeLambdas == 2 && removeLambdaOwnerKeyed
                && countExactCalls(vSetReject, Opcodes.INVOKEVIRTUAL, pidCls2, "set", "(Lzombie/characters/IsoPlayer;)V") == 1);

        // 動作封包身分檢查的存在理由：原版以 wire PlayerID.getID 當查詢／取消鍵，但 PlayerID.isConsistent
        // 只驗 id != -1 與解析得到 player，不比對該 player 的 onlineID 或所屬連線。TIS 補上比對時本條紅＝重估 bridge 的 owner 檢查。
        MethodNode vPidConsistent = methodFromJar(jar, pidCls2, "isConsistent", "(Lzombie/network/IConnection;)Z");
        failed += check("動作封包身分 vanilla 前提：PlayerID.isConsistent 不讀 player 的 onlineID、不查連線",
                countCalls(vPidConsistent, "zombie/characters/IsoPlayer", "getOnlineID") == 0
                && countExactFields(vPidConsistent, Opcodes.GETFIELD, "zombie/characters/IsoPlayer", "onlineId", "S") == 0
                && countCalls(vPidConsistent, "zombie/network/GameServer", "getPlayerFromConnection") == 0);
        MethodNode vGapProcess = methodFromJar(jar, generalPacketCls, "processServer", psDesc);
        failed += check("動作封包身分：GeneralActionPacket 取消恰一個 ActionManager.stop（原版取消入口）",
                countExactCalls(vGapProcess, Opcodes.INVOKESTATIC, amCls, "stop", amStopDesc) == 1);
        for (String cancelPacket : new String[]{"BuildActionPacket", "FishingActionPacket", "NetTimedActionPacket"}) {
            MethodNode cancel = methodFromJar(jar, "zombie/network/packets/" + cancelPacket, "processServer", psDesc);
            failed += check("動作封包身分：" + cancelPacket + " 取消恰一個 ActionManager.stop（原版取消入口）",
                    countExactCalls(cancel, Opcodes.INVOKESTATIC, amCls, "stop", amStopDesc) == 1);
        }

        String packetTypeCls = "zombie/network/PacketTypes$PacketType";
        String networkPacketCls = "zombie/network/packets/INetworkPacket";
        String dispatchDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V";
        String dispatchHelperDesc = "(L" + networkPacketCls + ";" + psDesc.substring(1);
        MethodNode vDispatch = methodFromJar(jar, packetTypeCls, "onServerPacket", dispatchDesc);
        MethodNode pDispatch = method(distJava, packetTypeCls, "onServerPacket", dispatchDesc);
        failed += check("派送 bridge 負對照：anticheat warn 不經 LogFilter，兩個 sync 出口保留",
                countCalls(vDispatch, "zombie/debug/DebugType", "warn") == 1
                && countCalls(pDispatch, "zombie/debug/DebugType", "warn") == 1
                && countCalls(pDispatch, "zombie/mdc/LogFilter", "warnFmt") == 0
                && countCalls(vDispatch, networkPacketCls, "sync") == 2
                && countCalls(pDispatch, networkPacketCls, "sync") == 2);
        failed += check("派送 bridge：原版唯一 processServer，在授權、parse、一致性與反作弊檢查之後",
                countExactCalls(vDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "processServer", psDesc) == 1
                && countCalls(vDispatch, "zombie/network/PacketTypes$PacketAuthorization", "isAuthorized") == 1
                && countCalls(vDispatch, networkPacketCls, "parseServer") == 1
                && countCalls(vDispatch, networkPacketCls, "isConsistent") == 1
                && countCalls(vDispatch, "zombie/network/anticheats/AntiCheat", "isValid") == 1
                && firstCallIndex(vDispatch, Opcodes.INVOKESTATIC, "zombie/network/PacketTypes$PacketAuthorization",
                        "isAuthorized", "(Lzombie/core/raknet/UdpConnection;L" + packetTypeCls + ";)Z")
                        < firstCallIndex(vDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "processServer", psDesc)
                && firstCallIndex(vDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "parseServer", dispatchDesc)
                        < firstCallIndex(vDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "processServer", psDesc)
                && firstCallIndex(vDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls,
                        "isConsistent", "(Lzombie/network/IConnection;)Z")
                        < firstCallIndex(vDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "processServer", psDesc)
                && firstCallIndex(vDispatch, Opcodes.INVOKEVIRTUAL, "zombie/network/anticheats/AntiCheat",
                        "isValid", "(Lzombie/core/raknet/UdpConnection;L" + networkPacketCls + ";)Z")
                        >= 0
                && firstCallIndex(vDispatch, Opcodes.INVOKEVIRTUAL, "zombie/network/anticheats/AntiCheat",
                        "isValid", "(Lzombie/core/raknet/UdpConnection;L" + networkPacketCls + ";)Z")
                        < firstCallIndex(vDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "processServer", psDesc));
        failed += check("派送 bridge：receiver-first bridge 恰 1、原呼叫歸零、真指令數不變",
                countExactCalls(pDispatch, Opcodes.INVOKESTATIC, taProbeCls, "processServer", dispatchHelperDesc) == 1
                && countExactCalls(pDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "processServer", psDesc) == 0
                && realInsnCount(pDispatch) == realInsnCount(vDispatch));
        MethodNode gDispatch = method(distJava, taProbeCls, "processServer", dispatchHelperDesc);
        failed += check("派送 bridge：保留原封包虛擬派送、Request 上下文以 finally 收尾，且不自行停止動作（取消與 Reject 後續全交原版）",
                countExactCalls(gDispatch, Opcodes.INVOKEINTERFACE, networkPacketCls, "processServer", psDesc) >= 1
                && gDispatch.tryCatchBlocks.stream().anyMatch(t -> t.type == null)
                && countCalls(gDispatch, amCls, "stop") == 0
                && countCalls(gDispatch, amCls, "remove") == 0);
        String clientDispatchDesc = "(Lzombie/core/network/ByteBufferReader;)V";
        failed += check("派送 bridge 負對照：client 派送方法不改動",
                realInsnCount(method(distJava, packetTypeCls, "onClientPacket", clientDispatchDesc))
                        == realInsnCount(methodFromJar(jar, packetTypeCls, "onClientPacket", clientDispatchDesc)));

        // ---- W23 帳號上限登入期執法：兩個登入封包各改道 x1、原呼叫歸零、真指令不變；helper 委派 vanilla 恰 1 ----
        String swdbCls = "zombie/network/ServerWorldDatabase";
        String authDesc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JI)L" + swdbCls + "$LogonResult;";
        String gateCls = "zombie/network/MdcAccountGate";
        String pktDesc = "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V";
        for (String pkt : new String[]{"LoginPacket", "GoogleAuthKeyPacket"}) {
            String cls = "zombie/network/packets/connection/" + pkt;
            MethodNode vPs = methodFromJar(jar, cls, "processServer", pktDesc);
            MethodNode pPs = method(distJava, cls, "processServer", pktDesc);
            failed += check("W23 " + pkt + "：vanilla authClient=1；手術後改道 x1、原呼叫歸零、真指令不變",
                    countExactCalls(vPs, Opcodes.INVOKEVIRTUAL, swdbCls, "authClient", authDesc) == 1
                    && countExactCalls(pPs, Opcodes.INVOKESTATIC, gateCls, "authClient", "(L" + swdbCls + ";" + authDesc.substring(1)) == 1
                    && countExactCalls(pPs, Opcodes.INVOKEVIRTUAL, swdbCls, "authClient", authDesc) == 0
                    && realInsnCount(pPs) == realInsnCount(vPs));
        }
        MethodNode gAuth = method(distJava, gateCls, "authClient", "(L" + swdbCls + ";" + authDesc.substring(1));
        failed += check("W23 helper 契約：委派 vanilla authClient 恰 1",
                countExactCalls(gAuth, Opcodes.INVOKEVIRTUAL, swdbCls, "authClient", authDesc) == 1);

        // ---- W25 序列化物件池執行緒隔離：BitHeader 四池＋ByteBlock 一池的 poll/offer/contains 全數改道 ----
        String ioPool = "zombie/mdc/IoPoolIsolation";
        String cld = "java/util/concurrent/ConcurrentLinkedDeque";
        String bhCls = "zombie/util/io/BitHeader";
        String bbCls = "zombie/core/utils/ByteBlock";
        String pollDesc = "()Ljava/lang/Object;";
        String objBoolDesc = "(Ljava/lang/Object;)Z";
        String hPollDesc = "(L" + cld + ";)Ljava/lang/Object;";
        String hObjBoolDesc = "(L" + cld + ";Ljava/lang/Object;)Z";
        String getHeaderDesc = "(L" + bhCls + "$HeaderSize;Ljava/nio/ByteBuffer;Z)L" + bhCls + "$BitHeaderBase;";
        ClassNode vBh = classNodeFromJar(jar, bhCls);
        ClassNode pBh = classNode(distJava, bhCls);
        MethodNode vGetHeader = methodFromJar(jar, bhCls, "getHeader", getHeaderDesc);
        MethodNode pGetHeader = method(distJava, bhCls, "getHeader", getHeaderDesc);
        // vanilla 前提：getHeader 內 CLD.poll 恰 4，且四個池欄位各讀 1（poll 的 receiver 就是它們）；
        // class-wide poll 全在 getHeader（其他方法無 poll／offer 可漏改）
        failed += check("W25 vanilla 前提：BitHeader.getHeader CLD.poll=4、pool_byte/short/int/long 各 getstatic 1；class-wide poll=4、offer=0",
                countExactCalls(vGetHeader, Opcodes.INVOKEVIRTUAL, cld, "poll", pollDesc) == 4
                && countFieldReads(vGetHeader, bhCls, "pool_byte") == 1
                && countFieldReads(vGetHeader, bhCls, "pool_short") == 1
                && countFieldReads(vGetHeader, bhCls, "pool_int") == 1
                && countFieldReads(vGetHeader, bhCls, "pool_long") == 1
                && classWideCalls(vBh, Opcodes.INVOKEVIRTUAL, cld, "poll", pollDesc) == 4
                && classWideCalls(vBh, Opcodes.INVOKEVIRTUAL, cld, "offer", objBoolDesc) == 0);
        failed += check("W25 手術後：getHeader 改道 x4、原 poll 歸零、真指令不變；getstatic 池欄位保留 x4（helper 吃 receiver 做 identity 分槽）",
                countExactCalls(pGetHeader, Opcodes.INVOKESTATIC, ioPool, "poll", hPollDesc) == 4
                && classWideCalls(pBh, Opcodes.INVOKEVIRTUAL, cld, "poll", pollDesc) == 0
                && realInsnCount(pGetHeader) == realInsnCount(vGetHeader)
                && countFieldReads(pGetHeader, bhCls, "pool_byte") + countFieldReads(pGetHeader, bhCls, "pool_short")
                        + countFieldReads(pGetHeader, bhCls, "pool_int") + countFieldReads(pGetHeader, bhCls, "pool_long") == 4);
        String[] hdrKinds = {"Byte", "Short", "Int", "Long"};
        String[] hdrPools = {"pool_byte", "pool_short", "pool_int", "pool_long"};
        for (int i = 0; i < hdrKinds.length; i++) {
            String cls = bhCls + "$BitHeader" + hdrKinds[i];
            MethodNode vHdrRel = methodFromJar(jar, cls, "release", "()V");
            MethodNode pHdrRel = method(distJava, cls, "release", "()V");
            failed += check("W25 " + hdrKinds[i] + ".release：vanilla 全序 reset→getstatic " + hdrPools[i]
                            + "→aload_0→offer→pop；手術後 offer 改道 x1、原呼叫歸零、真指令不變",
                    matchOpcodeSeq(vHdrRel, new int[]{Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.GETSTATIC,
                            Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.POP, Opcodes.RETURN})
                    && countFieldReads(vHdrRel, bhCls, hdrPools[i]) == 1
                    && countExactCalls(vHdrRel, Opcodes.INVOKEVIRTUAL, cld, "offer", objBoolDesc) == 1
                    && countExactCalls(pHdrRel, Opcodes.INVOKESTATIC, ioPool, "offer", hObjBoolDesc) == 1
                    && countExactCalls(pHdrRel, Opcodes.INVOKEVIRTUAL, cld, "offer", objBoolDesc) == 0
                    && realInsnCount(pHdrRel) == realInsnCount(vHdrRel));
            // 全 jar 池欄位讀取普查：getHeader＋release＋debug_print.size 各 1＝3；多出來的就是
            // TIS 新增的共用池消費者（會與私有池不一致），建置紅提醒重評
            failed += check("W25 全 jar " + hdrPools[i] + " getstatic 普查=3（getHeader/release/debug_print）",
                    jarWideFieldReadCensus(jar, Opcodes.GETSTATIC, bhCls, hdrPools[i]) == 3);
        }
        String bbStartDesc = "(Ljava/nio/ByteBuffer;L" + bbCls + "$Mode;)L" + bbCls + ";";
        String bbEndDesc = "(Ljava/nio/ByteBuffer;L" + bbCls + ";)V";
        ClassNode vBb = classNodeFromJar(jar, bbCls);
        ClassNode pBb = classNode(distJava, bbCls);
        MethodNode vBbStart = methodFromJar(jar, bbCls, "Start", bbStartDesc);
        MethodNode vBbEnd = methodFromJar(jar, bbCls, "End", bbEndDesc);
        MethodNode pBbStart = method(distJava, bbCls, "Start", bbStartDesc);
        MethodNode pBbEnd = method(distJava, bbCls, "End", bbEndDesc);
        failed += check("W25 vanilla 前提：ByteBlock.Start poll=1、End contains=1+offer=1；class-wide CLD poll/offer/contains 恰 1/1/1；全 jar pool_data_block getstatic=3",
                countExactCalls(vBbStart, Opcodes.INVOKEVIRTUAL, cld, "poll", pollDesc) == 1
                && countExactCalls(vBbEnd, Opcodes.INVOKEVIRTUAL, cld, "contains", objBoolDesc) == 1
                && countExactCalls(vBbEnd, Opcodes.INVOKEVIRTUAL, cld, "offer", objBoolDesc) == 1
                && classWideCalls(vBb, Opcodes.INVOKEVIRTUAL, cld, "poll", pollDesc) == 1
                && classWideCalls(vBb, Opcodes.INVOKEVIRTUAL, cld, "offer", objBoolDesc) == 1
                && classWideCalls(vBb, Opcodes.INVOKEVIRTUAL, cld, "contains", objBoolDesc) == 1
                && jarWideFieldReadCensus(jar, Opcodes.GETSTATIC, bbCls, "pool_data_block") == 3);
        failed += check("W25 手術後：ByteBlock Start 改道 x1、End 改道 x2、class-wide 原 CLD 呼叫歸零、真指令不變",
                countExactCalls(pBbStart, Opcodes.INVOKESTATIC, ioPool, "poll", hPollDesc) == 1
                && countExactCalls(pBbEnd, Opcodes.INVOKESTATIC, ioPool, "contains", hObjBoolDesc) == 1
                && countExactCalls(pBbEnd, Opcodes.INVOKESTATIC, ioPool, "offer", hObjBoolDesc) == 1
                && classWideCalls(pBb, Opcodes.INVOKEVIRTUAL, cld, "poll", pollDesc) == 0
                && classWideCalls(pBb, Opcodes.INVOKEVIRTUAL, cld, "offer", objBoolDesc) == 0
                && classWideCalls(pBb, Opcodes.INVOKEVIRTUAL, cld, "contains", objBoolDesc) == 0
                && realInsnCount(pBbStart) == realInsnCount(vBbStart)
                && realInsnCount(pBbEnd) == realInsnCount(vBbEnd));
        // helper 契約：三個改道目標各恰一處委派 vanilla（off／分槽溢位路徑）、熱路徑零 NEW、零 DebugLog
        MethodNode gPoll = method(distJava, ioPool, "poll", hPollDesc);
        MethodNode gOffer = method(distJava, ioPool, "offer", hObjBoolDesc);
        MethodNode gContains = method(distJava, ioPool, "contains", hObjBoolDesc);
        failed += check("W25 helper 契約：poll/offer/contains 各委派 CLD 恰 1、零 NEW、零 DebugLog",
                countExactCalls(gPoll, Opcodes.INVOKEVIRTUAL, cld, "poll", pollDesc) == 1
                && countExactCalls(gOffer, Opcodes.INVOKEVIRTUAL, cld, "offer", objBoolDesc) == 1
                && countExactCalls(gContains, Opcodes.INVOKEVIRTUAL, cld, "contains", objBoolDesc) == 1
                && countOpcode(gPoll, Opcodes.NEW) + countOpcode(gOffer, Opcodes.NEW) + countOpcode(gContains, Opcodes.NEW) == 0
                && countCallsToOwner(gPoll, "zombie/debug/DebugLog") + countCallsToOwner(gOffer, "zombie/debug/DebugLog")
                        + countCallsToOwner(gContains, "zombie/debug/DebugLog") == 0);

        // 42.21.0 官方已修：ACKWasReceived 迴圈改 `i < size`（offset 15 if_icmpge），W27 兩條斷言隨 patch 退役。

        // W29：接收入口整包驗證；依賴的上游協定一變就重驗，不猜新格式。
        String animalIngress = "zombie/mdc/AnimalUpdateGuard";
        String netDataDesc = "(Lzombie/network/ZomboidNetData;)V";
        String ingressDesc = "(L" + packetTypeCls + ";" + dispatchDesc.substring(1);
        MethodNode vNetData = methodFromJar(jar, gsCls, "mainLoopDealWithNetData", netDataDesc);
        MethodNode pNetData = method(distJava, gsCls, "mainLoopDealWithNetData", netDataDesc);
        failed += check("W29 原版入口：全 jar 恰一個 onServerPacket，位於 GameServer 網路處理方法",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, packetTypeCls, "onServerPacket", dispatchDesc) == 1
                && countExactCalls(vNetData, Opcodes.INVOKEVIRTUAL, packetTypeCls, "onServerPacket", dispatchDesc) == 1);
        failed += check("W29 改道：單一接收呼叫替換；其他指令、frames、例外處理與封包回收完全保留",
                countExactCalls(pNetData, Opcodes.INVOKESTATIC, animalIngress, "onServerPacket", ingressDesc) == 1
                && countExactCalls(pNetData, Opcodes.INVOKEVIRTUAL, packetTypeCls, "onServerPacket", dispatchDesc) == 0
                && methodText(vNetData).replace("INVOKEVIRTUAL " + packetTypeCls + ".onServerPacket " + dispatchDesc,
                        "INVOKESTATIC " + animalIngress + ".onServerPacket " + ingressDesc).equals(methodText(pNetData)));
        MethodNode gIngress = method(distJava, animalIngress, "onServerPacket", ingressDesc);
        failed += check("W29 正常委派：原 onServerPacket 恰一次、不捕獲其例外、入口零配置且不自行踢人",
                countExactCalls(gIngress, Opcodes.INVOKEVIRTUAL, packetTypeCls, "onServerPacket", dispatchDesc) == 1
                && gIngress.tryCatchBlocks.isEmpty()
                && countOpcode(gIngress, Opcodes.NEW) == 0
                && countCalls(gIngress, "zombie/core/raknet/UdpConnection", "forceDisconnect") == 0);
        // 固定共用 wire 實作及兩個側別宣告；TIS 修改時人工判定是否撤刀或更新契約。
        // 指紋取去 debug（行號／區域變數／SourceFile）的整類文字：含 @PacketSetting、欄位與全部方法，
        // 只因上游插碼造成的行號位移不再誤報。42.21 對版：AnimalUpdatePacket 只差 client 分支的
        // setSquare(null)，上行 wire 與伺服器 parse 逐指令未變（docs/patches.md 2aq）。
        String[][] animalProtocol = {
            {"zombie/network/packets/character/AnimalUpdatePacket", "e24d87ca814a5fa8fa477e91f282baff7bbcb2460a306d503831ba41148b0264"},
            {"zombie/network/packets/character/AnimalUpdateReliablePacket", "1e4a0964828ef29a3cc76129f7d0e9cd3bd38ec2424e3848458b077d3af420af"},
            {"zombie/network/packets/character/AnimalUpdateUnreliablePacket", "4938c2a522682c53724cd30db5fb98b178dbffffa1bebf032c882889699deefe"},
        };
        for (String[] entry : animalProtocol) {
            failed += check("W29 上游協定及側別未漂移：" + entry[0],
                    sha256Hex(debuglessClassText(jar, entry[0])).equals(entry[1]));
            failed += check("W29 不覆寫動物同步協定類別：" + entry[0],
                    !Files.exists(distJava.resolve(entry[0] + ".class")));
        }
        failed += check("W29 client 接收入口不改動",
                methodText(methodFromJar(jar, packetTypeCls, "onClientPacket", clientDispatchDesc))
                        .equals(methodText(method(distJava, packetTypeCls, "onClientPacket", clientDispatchDesc))));

        // W31：不只數命中；原方法、lambda body、側別與 decoder 漂移即要求重新驗證。
        // 指紋取去 debug 的 methodText（行號位移不算漂移；42.20.4 與 42.21 值相同）。
        // （W30 的 IsoCell 三條契約隨 W30 退役移除；processItems 的前提改由下方 W45 守門。）
        String[][] batchContracts = {
            {"zombie/network/GameServer", "transmitFishingData", "(IILgnu/trove/map/hash/TLongIntHashMap;Lgnu/trove/map/hash/TLongObjectHashMap;)V", "967b94d94daac05f9d641d6db44d4e1525636e9cf94c8495722ced523ae78c96"},
            {"zombie/network/GameServer", "lambda$transmitFishingData$0", "(Lzombie/core/network/ByteBufferWriter;J)Z", "d3590ace917c3ccaac257f757dd4053fd9eeb1fe0fdb1c7a3f085dcd36ba5c11"},
            {"zombie/network/GameServer", "lambda$transmitFishingData$1", "(Lzombie/core/network/ByteBufferWriter;JLzombie/iso/FishSchoolManager$ChumData;)Z", "56d9129cae1b88469fb451272015db02bdbda617d311decbd731e4e2db7bd1c4"},
            {"zombie/iso/FishSchoolManager", "updateSeed", "()V", "fe255e44ad29d16664f273b46b09ea392a3e2bc9e71e44083c2e432316065829"},
            {"zombie/iso/FishSchoolManager", "updateFishingData", "()V", "5ecdda31a5f3ba4666b080fc93ced5e1f33ec38d098691e93f4599e03ffece4a"},
            {"zombie/iso/FishSchoolManager", "receiveFishingData", "(Lzombie/core/network/ByteBufferReader;)V", "bc09f1e14dbd2710effe11f49f2a7536534fb670d48b28244df9a0dd879cefd1"}
        };
        for (String[] contract : batchContracts) {
            failed += check("W31 上游契約未漂移：" + contract[0] + "." + contract[1],
                    sha256Hex(debuglessMethodText(jar, contract[0], contract[1], contract[2])).equals(contract[3]));
        }
        failed += check("W31 ByteBufferWriter 維持 final，無自訂 writer 回呼改變批次資料",
                (classNodeFromJar(jar, "zombie/core/network/ByteBufferWriter").access & Opcodes.ACC_FINAL) != 0);
        String fishCls = "zombie/iso/FishSchoolManager";
        String fishHelper = "zombie/mdc/FishingDataBroadcast";
        String fishDesc = "(IILgnu/trove/map/hash/TLongIntHashMap;Lgnu/trove/map/hash/TLongObjectHashMap;)V";
        for (String caller : new String[]{"updateSeed", "updateFishingData"}) {
            MethodNode original = methodFromJar(jar, fishCls, caller, "()V");
            MethodNode patched = method(distJava, fishCls, caller, "()V");
            failed += check("W31 " + caller + " 唯一廣播改道，側別、更新順序與 frames 保留",
                    countExactCalls(original, Opcodes.INVOKESTATIC, gsCls, "transmitFishingData", fishDesc) == 1
                    && countExactCalls(patched, Opcodes.INVOKESTATIC, fishHelper, "transmitFishingData", fishDesc) == 1
                    && methodText(original).replace(
                            "INVOKESTATIC " + gsCls + ".transmitFishingData " + fishDesc,
                            "INVOKESTATIC " + fishHelper + ".transmitFishingData " + fishDesc).equals(methodText(patched)));
        }
        failed += check("W31 廣播入口全 jar 恰兩處，原方法保留供停用直通",
                jarWideCallsiteCensus(jar, Opcodes.INVOKESTATIC, gsCls, "transmitFishingData", fishDesc) == 2
                && methodText(methodFromJar(jar, gsCls, "transmitFishingData", fishDesc))
                        .equals(methodText(method(distJava, gsCls, "transmitFishingData", fishDesc))));
        for (MethodNode original : classNodeFromJar(jar, fishCls).methods) {
            if (original.name.equals("updateSeed") || original.name.equals("updateFishingData")) continue;
            failed += check("W31 非目標方法不變：" + original.name + original.desc,
                    methodText(original).equals(methodText(method(distJava, fishCls, original.name, original.desc))));
        }

        // W32：動物離線補算觀測。唯一 callsite 同形改道；vanilla 以 zone.hourLastSeen 推算時數的
        // 事實一併釘住（TIS 改用動物自身時間戳時此條紅＝重估本刀）。
        String awayMainCls = "zombie/characters/animals/AnimalManagerMain";
        String awayHelper = "zombie/mdc/AnimalAwayProbe";
        String awayDesc = "(Lzombie/characters/animals/IsoAnimal;I)V";
        MethodNode vFromWorker = methodFromJar(jar, awayMainCls, "fromWorker", "(Ljava/util/ArrayList;)V");
        MethodNode pFromWorker = method(distJava, awayMainCls, "fromWorker", "(Ljava/util/ArrayList;)V");
        failed += check("W32 updateStatsAway 全 jar 恰三個呼叫點（fromWorker 1＋doMeta 2）",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, "zombie/characters/animals/IsoAnimal",
                        "updateStatsAway", "(I)V") == 3);
        String zoneCls = "zombie/iso/areas/DesignationZoneAnimal";
        MethodNode vMeta = methodFromJar(jar, zoneCls, "doMeta", "(I)V");
        MethodNode pMeta = method(distJava, zoneCls, "doMeta", "(I)V");
        failed += check("W32 doMeta 兩處改道、原 updateStatsAway 呼叫歸零",
                countExactCalls(pMeta, Opcodes.INVOKESTATIC, awayHelper, "updateStatsAwayZone", awayDesc) == 2
                && countExactCalls(pMeta, Opcodes.INVOKEVIRTUAL, "zombie/characters/animals/IsoAnimal",
                        "updateStatsAway", "(I)V") == 0);
        // W42：存在理由＝vanilla 只在 unloaded()／存檔載入／補算迴圈寫動物時鐘，活著的每小時不刷新
        // （AnimalData 全 class 零寫入）；update() 內唯一 hourGrow 同形改道。
        String animalDataCls = "zombie/characters/animals/datas/AnimalData";
        int clockWrites = 0;
        for (MethodNode m : classNodeFromJar(jar, animalDataCls).methods) {
            clockWrites += countExactFields(m, Opcodes.PUTFIELD, "zombie/characters/animals/IsoAnimal",
                    "timeSinceLastUpdate", "J");
        }
        MethodNode vDataUpdate = methodFromJar(jar, animalDataCls, "update", "()V");
        failed += check("W42 vanilla AnimalData 不寫動物時鐘，update 內 hourGrow 恰 1",
                clockWrites == 0
                && countExactCalls(vDataUpdate, Opcodes.INVOKEVIRTUAL, animalDataCls, "hourGrow", "(Z)V") == 1);
        failed += check("W42 AnimalData.update 唯一 hourGrow 同形改道，其餘指令與 frames 保留",
                methodText(vDataUpdate).replace("INVOKEVIRTUAL " + animalDataCls + ".hourGrow (Z)V",
                        "INVOKESTATIC " + awayHelper + ".liveHourGrow (L" + animalDataCls + ";Z)V")
                        .equals(methodText(method(distJava, animalDataCls, "update", "()V"))));
        // ---- W72 雞舍內動物產蛋不搜尋畜牧區雞舍（docs/patches.md 2cg）----
        // 等價依據：checkEggs 第 1 個 getRegionHutch 後的 IFNULL、第 2 個之後 isDoorClosed 的 IFNE、讀 parent.hutch 的 IFNONNULL
        // 三個跳轉同一個目標（addEgg(false) 那段），所以 parent.hutch != null 時搜尋結果不影響走向；搜尋一路沒有欄位寫入、
        // 只呼叫清單與字串方法，略過不改任何狀態。任一條紅＝TIS 改了流程或搜尋，先重估再談，不改斷言。
        String w72Data = "zombie/characters/animals/datas/AnimalData";
        String w72Animal = "zombie/characters/animals/IsoAnimal";
        String w72HutchCls = "zombie/iso/objects/IsoHutch";
        String w72Zone = "zombie/iso/areas/DesignationZoneAnimal";
        String w72Helper = "zombie/mdc/HutchEggRegion";
        String w72Desc = "(Lzombie/util/PZCalendar;Z)V";
        String w72Region = "()L" + w72HutchCls + ";";
        MethodNode w72Vanilla = methodFromJar(jar, w72Data, "checkEggs", w72Desc);
        List<AbstractInsnNode> w72Sites = new ArrayList<>();
        for (AbstractInsnNode in : w72Vanilla.instructions) {
            if (isCall(in, Opcodes.INVOKEVIRTUAL, w72Data, "getRegionHutch", w72Region)) {
                w72Sites.add(in);
            }
        }
        LabelNode w72Target = null;
        boolean w72SameTarget = false;
        if (w72Sites.size() == 4) {
            AbstractInsnNode a = nextReal(w72Sites.get(0));
            AbstractInsnNode door = nextReal(w72Sites.get(1));
            AbstractInsnNode b = door == null ? null : nextReal(door);
            AbstractInsnNode load = b == null ? null : nextReal(b);
            AbstractInsnNode parentGet = load == null ? null : nextReal(load);
            AbstractInsnNode hutchGet = parentGet == null ? null : nextReal(parentGet);
            AbstractInsnNode c = hutchGet == null ? null : nextReal(hutchGet);
            if (a instanceof JumpInsnNode ja && ja.getOpcode() == Opcodes.IFNULL
                    && isCall(door, Opcodes.INVOKEVIRTUAL, w72HutchCls, "isDoorClosed", "()Z")
                    && b instanceof JumpInsnNode jb && jb.getOpcode() == Opcodes.IFNE
                    && load instanceof VarInsnNode lv && lv.getOpcode() == Opcodes.ALOAD && lv.var == 0
                    && parentGet instanceof FieldInsnNode pf && pf.getOpcode() == Opcodes.GETFIELD
                    && pf.owner.equals(w72Data) && pf.name.equals("parent")
                    && hutchGet instanceof FieldInsnNode hf && hf.getOpcode() == Opcodes.GETFIELD
                    && hf.owner.equals(w72Animal) && hf.name.equals("hutch")
                    && c instanceof JumpInsnNode jc && jc.getOpcode() == Opcodes.IFNONNULL
                    && ja.label == jb.label && jb.label == jc.label
                    && w72Vanilla.instructions.indexOf(jc) < w72Vanilla.instructions.indexOf(w72Sites.get(2))) {
                w72Target = ja.label;
                AbstractInsnNode t0 = nextReal(w72Target);
                AbstractInsnNode t1 = t0 == null ? null : nextReal(t0);
                AbstractInsnNode t2 = t1 == null ? null : nextReal(t1);
                AbstractInsnNode t3 = t2 == null ? null : nextReal(t2);
                w72SameTarget = t0 instanceof VarInsnNode v0 && v0.getOpcode() == Opcodes.ALOAD && v0.var == 0
                        && t1 instanceof FieldInsnNode f1 && f1.name.equals("parent")
                        && t2 != null && t2.getOpcode() == Opcodes.ICONST_0
                        && isCall(t3, Opcodes.INVOKEVIRTUAL, w72Animal, "addEgg", "(Z)Z");
            }
        }
        failed += check("W72 vanilla 前提：checkEggs 恰 4 個 getRegionHutch；第 1 個後 IFNULL、第 2 個後 isDoorClosed 的 IFNE、"
                        + "讀 parent.hutch 的 IFNONNULL 跳同一個目標（addEgg(false)），且第 3、4 個在 IFNONNULL 之後",
                w72Sites.size() == 4 && w72SameTarget);
        String[][] w72Pure = {
                {w72Data, "getRegionHutch", w72Region},
                {w72Zone, "getHutchsConnected", "()Ljava/util/ArrayList;"},
                {w72Zone, "getAllDZones", "(Ljava/util/ArrayList;L" + w72Zone + ";L" + w72Zone + ";)Ljava/util/ArrayList;"},
                {w72Zone, "getZone", "(III)L" + w72Zone + ";"},
                {w72Animal, "getConnectedDZone", "()Ljava/util/ArrayList;"},
                {w72HutchCls, "isDoorClosed", "()Z"}};
        Set<String> w72Allowed = Set.of("java/util/ArrayList.<init>", "java/util/ArrayList.size", "java/util/ArrayList.get",
                "java/util/ArrayList.isEmpty", "java/util/ArrayList.add", "java/util/ArrayList.addAll",
                "java/util/ArrayList.contains", "java/util/ArrayList.clear", "java/lang/String.equals",
                w72Animal + ".getConnectedDZone", w72Zone + ".getHutchsConnected", w72Zone + ".getAllDZones",
                w72Zone + ".getZone");
        List<String> w72Impure = new ArrayList<>();
        for (String[] p : w72Pure) {
            for (AbstractInsnNode in : methodFromJar(jar, p[0], p[1], p[2]).instructions) {
                int op = in.getOpcode();
                if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC || op == Opcodes.INVOKEDYNAMIC
                        || op == Opcodes.INVOKEINTERFACE
                        || in instanceof MethodInsnNode mi && !w72Allowed.contains(mi.owner + "." + mi.name)) {
                    w72Impure.add(p[1] + ":" + op);
                }
            }
        }
        failed += check("W72 vanilla 前提：getRegionHutch→getHutchsConnected→getAllDZones→getZone、getConnectedDZone、"
                        + "isDoorClosed 沒有欄位寫入，只呼叫清單與 String.equals（" + w72Impure + "）",
                w72Impure.isEmpty());
        failed += check("W72 手術後：checkEggs 4 個 getRegionHutch 同形改道 HutchEggRegion.regionHutch，其餘指令與 frames 保留",
                methodText(w72Vanilla).replace("INVOKEVIRTUAL " + w72Data + ".getRegionHutch " + w72Region,
                        "INVOKESTATIC " + w72Helper + ".regionHutch (L" + w72Data + ";)L" + w72HutchCls + ";")
                        .equals(methodText(method(distJava, w72Data, "checkEggs", w72Desc))));
        MethodNode w72HelperMethod = method(distJava, w72Helper, "regionHutch", "(L" + w72Data + ";)L" + w72HutchCls + ";");
        failed += check("W72 helper 契約：regionHutch 只讀 AnimalData.parent 與 IsoAnimal.hutch、沒有 PUTFIELD、委派原版 getRegionHutch",
                countExactFields(w72HelperMethod, Opcodes.GETFIELD, w72Animal, "hutch", "L" + w72HutchCls + ";") == 1
                        && countFieldTouchesOfOwner(w72HelperMethod, w72Animal) == 1
                        && countFieldTouchesOfOwner(w72HelperMethod, w72Data) == 1
                        && java.util.stream.StreamSupport.stream(w72HelperMethod.instructions.spliterator(), false)
                                .noneMatch(in -> in.getOpcode() == Opcodes.PUTFIELD)
                        && countExactCalls(w72HelperMethod, Opcodes.INVOKEVIRTUAL, w72Data, "getRegionHutch", w72Region) >= 1);
        // W38：存在理由＝updateStatsAway 內 checkZone→setDZone 會 remove＋add（移到清單尾端）。
        String metaSnap = "zombie/mdc/AnimalMetaSnapshot";
        String zoneArg = "(L" + zoneCls + ";)V";
        MethodNode vStatsAway = methodFromJar(jar, "zombie/characters/animals/IsoAnimal", "updateStatsAway", "(I)V");
        MethodNode vSetDZone = methodFromJar(jar, "zombie/characters/animals/IsoAnimal", "setDZone", zoneArg);
        failed += check("W38 vanilla updateStatsAway 呼叫 checkZone，setDZone 先 removeAnimal 再 addAnimal",
                countExactCalls(vStatsAway, Opcodes.INVOKEVIRTUAL, "zombie/characters/animals/IsoAnimal", "checkZone", "()V")
                + countExactCalls(vStatsAway, Opcodes.INVOKESPECIAL, "zombie/characters/animals/IsoAnimal", "checkZone", "()V") == 1
                && methodText(vSetDZone).indexOf(zoneCls + ".removeAnimal") >= 0
                && methodText(vSetDZone).indexOf(zoneCls + ".removeAnimal") < methodText(vSetDZone).indexOf(zoneCls + ".addAnimal"));
        int swapped = 0;
        boolean swapShape = true;
        for (AbstractInsnNode in : pMeta.instructions) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                    && fi.owner.equals(zoneCls) && fi.name.equals("animals")) {
                AbstractInsnNode next = in.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                swapShape &= next instanceof MethodInsnNode mi && mi.owner.equals(metaSnap) && mi.name.equals("animals");
                swapped++;
            }
        }
        failed += check("W38 doMeta：begin 頭部、end 在 RETURN 前、8 個 GETFIELD animals 各接快照、真指令恰 +12",
                countFieldTouches(vMeta, zoneCls, "animals") == 8 && swapped == 8 && swapShape
                && countExactCalls(pMeta, Opcodes.INVOKESTATIC, metaSnap, "animals",
                        "(Ljava/util/ArrayList;)Ljava/util/ArrayList;") == 8
                && headCallSlotsOk(pMeta, metaSnap, "begin", zoneArg, 0)
                && tailCallOk(pMeta, metaSnap, "end", zoneArg)
                && realInsnCount(pMeta) == realInsnCount(vMeta) + 12);
        // W39：OnDeath 頭部帳本（純觀測）。
        MethodNode vOnDeath = methodFromJar(jar, "zombie/characters/animals/IsoAnimal", "OnDeath", "()V");
        MethodNode pOnDeath = method(distJava, "zombie/characters/animals/IsoAnimal", "OnDeath", "()V");
        failed += check("W39 OnDeath 頭部 aload_0→AnimalDeathLedger.onDeath、真指令恰 +2",
                headCallSlotsOk(pOnDeath, "zombie/mdc/AnimalDeathLedger", "onDeath",
                        "(Lzombie/characters/animals/IsoAnimal;)V", 0)
                && realInsnCount(pOnDeath) == realInsnCount(vOnDeath) + 2);
        failed += checkAnimalCatchUpAndHookSave(jar, distJava);
        failed += checkCatchUpDefer(jar, distJava);
        // W40：存在理由＝原版 ProcessItems 對 processItems.get(n) 不做 null 檢查（TIS 補檢查時會紅＝撤刀）。
        String isoCellCls = "zombie/iso/IsoCell";
        String piGuard = "zombie/mdc/ProcessItemsGuard";
        MethodNode vProcessItems = methodFromJar(jar, isoCellCls, "ProcessItems", "(Ljava/util/Iterator;)V");
        int nullChecks = 0;
        for (AbstractInsnNode in : vProcessItems.instructions) {
            if (in.getOpcode() == Opcodes.IFNULL || in.getOpcode() == Opcodes.IFNONNULL) nullChecks++;
        }
        failed += check("W40 vanilla ProcessItems 無 null 檢查，InventoryItem.update／finishupdate 各恰 1",
                nullChecks == 0
                && countExactCalls(vProcessItems, Opcodes.INVOKEVIRTUAL, "zombie/inventory/InventoryItem", "update", "()V") == 1
                && countExactCalls(vProcessItems, Opcodes.INVOKEVIRTUAL, "zombie/inventory/InventoryItem", "finishupdate", "()Z") == 1);
        MethodNode pProcessItems = method(distJava, isoCellCls, "ProcessItems", "(Ljava/util/Iterator;)V");
        String cellDesc = "(L" + isoCellCls + ";)V";
        failed += check("W40／W41 ProcessItems 兩處 1:1 改道、頭部 beginPass、唯一 RETURN 前 endPass、真指令恰 +4",
                countExactCalls(pProcessItems, Opcodes.INVOKESTATIC, piGuard, "update", "(Lzombie/inventory/InventoryItem;)V") == 1
                && countExactCalls(pProcessItems, Opcodes.INVOKESTATIC, piGuard, "finishupdate", "(Lzombie/inventory/InventoryItem;)Z") == 1
                && countExactCalls(pProcessItems, Opcodes.INVOKEVIRTUAL, "zombie/inventory/InventoryItem", "update", "()V") == 0
                && countExactCalls(pProcessItems, Opcodes.INVOKEVIRTUAL, "zombie/inventory/InventoryItem", "finishupdate", "()Z") == 0
                && headCallSlotsOk(pProcessItems, piGuard, "beginPass", cellDesc, 0)
                && tailCallOk(pProcessItems, piGuard, "endPass", cellDesc)
                && realInsnCount(pProcessItems) == realInsnCount(vProcessItems) + 4);
        String[][] piWriters = {
                {"addToProcessItems", "(Lzombie/inventory/InventoryItem;)V", "touchAdd", cellDesc},
                {"addToProcessItems", "(Ljava/util/ArrayList;)V", "touchAddAll",
                        "(L" + isoCellCls + ";Ljava/util/ArrayList;)V"},
                {"addToProcessItemsRemove", "(Lzombie/inventory/InventoryItem;)V", "touch", cellDesc},
                {"addToProcessItemsRemove", "(Ljava/util/ArrayList;)V", "touch", cellDesc}};
        java.util.Set<String> piTargets = new java.util.HashSet<>();
        piTargets.add("ProcessItems(Ljava/util/Iterator;)V");
        piTargets.add("<init>(II)V");   // W45 FieldPutWrap；形狀由下方 W45 守門鎖住
        // W73：ProcessIsoObject 與兩個 addToProcessIsoObject* 的形狀由下方 W73 守門（checkIsoObjectGate）鎖住。
        piTargets.add("ProcessIsoObject()V");
        piTargets.add("addToProcessIsoObject(Lzombie/iso/IsoObject;)V");
        piTargets.add("addToProcessIsoObjectRemove(Lzombie/iso/IsoObject;)V");
        // W92：ProcessObjects 的 Set.iterator 改道由 checkAnimalCuts 鎖住。
        piTargets.add("ProcessObjects(Ljava/util/Iterator;)V");
        for (String[] w : piWriters) {
            piTargets.add(w[0] + w[1]);
            MethodNode vW = methodFromJar(jar, isoCellCls, w[0], w[1]);
            MethodNode pW = method(distJava, isoCellCls, w[0], w[1]);
            int[] slots = w[2].equals("touchAddAll") ? new int[]{0, 1} : new int[]{0};
            failed += check("W40 " + w[0] + w[1] + " 頭部 " + w[2] + "、真指令恰 +" + (slots.length + 1),
                    headCallSlotsOk(pW, piGuard, w[2], w[3], slots)
                    && realInsnCount(pW) == realInsnCount(vW) + slots.length + 1);
        }
        int piUntouchedDiffs = 0;
        for (MethodNode original : classNodeFromJar(jar, isoCellCls).methods) {
            if (piTargets.contains(original.name + original.desc)) continue;
            if (!methodText(original).equals(methodText(method(distJava, isoCellCls, original.name, original.desc)))) piUntouchedDiffs++;
        }
        failed += check("W40 IsoCell 其餘方法逐指令不變", piUntouchedDiffs == 0);
        // W45：processItems 身分索引（docs/patches.md 2bh）。存在理由：原版 addToProcessItems 兩個多載各恰 1 個
        // ArrayList.contains（線性）、IsoCell 沒有 processItems 的伴生集合、ProcessRemoveItems 不先檢查 isEmpty 就
        // removeAll——TIS 補上伴生 Set（如同 processIsoObjectSet）時這幾條會紅＝撤刀。
        String piIndex = "zombie/mdc/ProcessItemsIndex";
        String listDesc = "Ljava/util/ArrayList;";
        ClassNode vCellNode = classNodeFromJar(jar, isoCellCls);
        org.objectweb.asm.tree.FieldNode piField = null;
        int piCompanions = 0;
        for (org.objectweb.asm.tree.FieldNode f : vCellNode.fields) {
            if (f.name.equals("processItems")) {
                piField = f;
            } else if (f.name.startsWith("processItems") && !f.name.equals("processItemsRemove")) {
                piCompanions++;
            }
        }
        int privateFinal = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
        failed += check("W45 原版 processItems 為 private final ArrayList，且無伴生索引欄位",
                piField != null && piField.desc.equals(listDesc)
                && (piField.access & privateFinal) == privateFinal && piCompanions == 0);
        for (String d : new String[]{"(Lzombie/inventory/InventoryItem;)V", "(Ljava/util/ArrayList;)V"}) {
            failed += check("W45 原版 addToProcessItems" + d + " 恰 1 個線性 ArrayList.contains",
                    countExactCalls(methodFromJar(jar, isoCellCls, "addToProcessItems", d), Opcodes.INVOKEVIRTUAL,
                            "java/util/ArrayList", "contains", "(Ljava/lang/Object;)Z") == 1);
        }
        MethodNode vRemoveItems = methodFromJar(jar, isoCellCls, "ProcessRemoveItems", "(Ljava/util/Iterator;)V");
        failed += check("W45 原版 ProcessRemoveItems 不檢查 isEmpty 就對兩份清單 removeAll（每幀全掃）",
                countExactCalls(vRemoveItems, Opcodes.INVOKEINTERFACE, "java/util/Set", "isEmpty", "()Z") == 0
                && countExactCalls(vRemoveItems, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "removeAll",
                        "(Ljava/util/Collection;)Z") == 2);
        int piInnerGets = 0;
        for (MethodNode m : vCellNode.methods) {
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                        && fi.owner.equals(isoCellCls) && fi.name.equals("processItems")) {
                    piInnerGets++;
                }
            }
        }
        failed += check("W45 全 jar processItems 欄位：PUTFIELD 恰 1、GETFIELD 全在 IsoCell 內（其他類別只能經 getter 拿到同一個清單物件）",
                jarWideFieldReadCensus(jar, Opcodes.PUTFIELD, isoCellCls, "processItems") == 1
                && jarWideFieldReadCensus(jar, Opcodes.GETFIELD, isoCellCls, "processItems") == piInnerGets);
        MethodNode vCellInit = methodFromJar(jar, isoCellCls, "<init>", "(II)V");
        MethodNode pCellInit = method(distJava, isoCellCls, "<init>", "(II)V");
        String wrapDesc = "(" + listDesc + ")" + listDesc;
        String setDesc = "Ljava/util/Set;";
        String objWrapDesc = "(" + setDesc + ")" + setDesc;
        failed += check("W45／W47 建構子唯一 PUTFIELD processItems／objectList：原版前為 new ArrayList()／new HashSet()，"
                        + "手術後其間各緊接 wrap、真指令恰 +2、移除兩個 wrap 後逐字不變",
                countFieldTouches(vCellInit, isoCellCls, "processItems") == 1
                && countFieldTouches(vCellInit, isoCellCls, "objectList") == 1
                && putWrapOk(vCellInit, pCellInit, isoCellCls, "processItems", "java/util/ArrayList", piIndex, "wrap", wrapDesc)
                && putWrapOk(vCellInit, pCellInit, isoCellCls, "objectList", "java/util/HashSet",
                        "zombie/mdc/AnimalLosIndex", "wrapObjectList", objWrapDesc)
                && wrapsStripToVanilla(vCellInit, pCellInit, new String[][]{{piIndex, "wrap", wrapDesc},
                        {"zombie/mdc/AnimalLosIndex", "wrapObjectList", objWrapDesc}})
                && realInsnCount(pCellInit) == realInsnCount(vCellInit) + 2);
        failed += check("W47 全 jar objectList 欄位：PUTFIELD 恰 1（包裝後的集合就是唯一一份）",
                jarWideFieldReadCensus(jar, Opcodes.PUTFIELD, isoCellCls, "objectList") == 1);
        failed += check("W45 守門負對照：NEW 與 PUTFIELD 間有合流點（部分路徑沿用既有清單）時拒絕",
                !putWrapOk(mergeShape(null, null, null), mergeShape(piIndex, "wrap", wrapDesc),
                        "T", "f", "java/util/ArrayList", piIndex, "wrap", wrapDesc));
        failed += checkInventoryItemIdentity(jar);

        // W46：VehicleCollide 歸還後強制重送授權（docs/patches.md 2bi）。存在理由（TIS 修好即紅＝撤刀）：
        // shouldSend 只在授權與該連線快取不同時才帶 8192；processServer 只改車輛授權、不碰任何連線快取；
        // client 撞車時在本機自設 LocalCollide（不等伺服器）。
        String svsCls = "zombie/vehicles/BaseVehicle$ServerVehicleState";
        String vcpCls = "zombie/network/packets/vehicle/VehicleCollidePacket";
        String vcrCls = "zombie/network/packets/vehicle/MdcVehicleCollideResync";
        String vcpDesc = "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V";
        MethodNode vShouldSend = methodFromJar(jar, svsCls, "shouldSend", "(Lzombie/vehicles/BaseVehicle;)Z");
        MethodNode vVcProcess = methodFromJar(jar, vcpCls, "processServer", vcpDesc);
        MethodNode vClientCollide = methodFromJar(jar, "zombie/vehicles/BaseVehicle",
                "authorizationClientCollide", "(Lzombie/characters/IsoPlayer;)V");
        failed += check("W46 原版：shouldSend 以連線快取 netPlayerId 比對才帶 8192；processServer 只呼叫 authorizationServerCollide、不碰連線快取；client 本機自設 LocalCollide",
                countExactFields(vShouldSend, Opcodes.GETFIELD, svsCls, "netPlayerId", "S") == 1
                && methodText(vShouldSend).contains("SIPUSH 8192")
                && countExactCalls(vVcProcess, Opcodes.INVOKEVIRTUAL, "zombie/vehicles/BaseVehicle",
                        "authorizationServerCollide", "(SZ)V") == 1
                && !methodText(vVcProcess).contains("ServerVehicleState")
                && !methodText(vVcProcess).contains("vehicleStates")
                && methodText(vClientCollide).contains("GETSTATIC zombie/vehicles/BaseVehicle$Authorization.LocalCollide"));
        MethodNode pVcProcess = method(distJava, vcpCls, "processServer", vcpDesc);
        String vcrDesc = "(L" + vcpCls + ";Lzombie/core/raknet/UdpConnection;)V";
        failed += check("W46 手術後：processServer 頭部 aload_0／aload_2／invokestatic 全序、真指令恰 +3",
                headCallSlotsOk(pVcProcess, vcrCls, "onProcessServer", vcrDesc, 0, 2)
                && realInsnCount(pVcProcess) == realInsnCount(vVcProcess) + 3);
        MethodNode gVcr = method(distJava, vcrCls, "onProcessServer", vcrDesc);
        failed += check("W46 helper 契約：只改既有快取的 netPlayerId 恰 1 處、不經 getVehicleState 新建快取",
                countExactFields(gVcr, Opcodes.PUTFIELD, svsCls, "netPlayerId", "S") == 1
                && countExactCalls(gVcr, Opcodes.INVOKEVIRTUAL, "zombie/vehicles/VehicleManager", "getVehicleState",
                        "(Lzombie/core/raknet/UdpConnection;Lzombie/vehicles/BaseVehicle;)L" + svsCls + ";") == 0);

        // W64：卡進地下的車交回伺服器時抬回地面（docs/patches.md 2ca）。存在理由（TIS 修好即紅＝撤刀）：
        // VehiclePhysicsPacket.processServer 把客戶端回報的高度直接寫進 setZ／jniTransform，不夾樓層；
        // 原版只有 BaseVehicle.load 以 PZMath.clamp 把存檔高度夾回樓層；伺服器授權交回 Server 都經 setNetPlayerAuthorization；
        // shouldSend 併入 vehicle.updateFlags（helper 以 Full 要求完整更新）。helper 的停放高度照 CarController 建構子。
        // 翻覆而且低於地板一層以上的車另外扶正：原版 load 照存檔旋轉放車，save 與完整更新從 jniTransform 取旋轉。
        String w64Veh = "zombie/vehicles/BaseVehicle";
        String w64AuthDesc = "(Lzombie/vehicles/BaseVehicle$Authorization;I)V";
        String w64Helper = "zombie/mdc/VehicleSinkRestore";
        String w64HelperDesc = "(Lzombie/vehicles/BaseVehicle;Lzombie/vehicles/BaseVehicle$Authorization;)V";
        MethodNode w64Physics = methodFromJar(jar, "zombie/network/packets/vehicle/VehiclePhysicsPacket", "processServer", vcpDesc);
        MethodNode w64Load = methodFromJar(jar, w64Veh, "load", "(Ljava/nio/ByteBuffer;IZ)V");
        MethodNode w64Car = methodFromJar(jar, "zombie/core/physics/CarController", "<init>", "(L" + w64Veh + ";)V");
        String w64Script = "zombie/scripting/objects/VehicleScript";
        failed += check("W64 vanilla 前提：VehiclePhysicsPacket.processServer 直接 setZ 客戶端高度、不夾樓層；只有 load 以 PZMath.clamp 夾回；"
                        + "CarController 停放高度讀模型偏移、輪子、重心與車身高",
                countExactCalls(w64Physics, Opcodes.INVOKEVIRTUAL, w64Veh, "setZ", "(F)F") == 1
                && countExactCalls(w64Physics, Opcodes.INVOKESTATIC, "zombie/core/math/PZMath", "clamp", "(FFF)F") == 0
                && countExactCalls(w64Load, Opcodes.INVOKESTATIC, "zombie/core/math/PZMath", "clamp", "(FFF)F") >= 1
                && countExactCalls(w64Car, Opcodes.INVOKEVIRTUAL, w64Script, "getModelOffset", "()Lorg/joml/Vector3f;") == 1
                && countExactCalls(w64Car, Opcodes.INVOKEVIRTUAL, w64Script, "getCenterOfMassOffset", "()Lorg/joml/Vector3f;") == 1
                && countExactCalls(w64Car, Opcodes.INVOKEVIRTUAL, w64Script, "getExtents", "()Lorg/joml/Vector3f;") == 1
                && countExactCalls(w64Car, Opcodes.INVOKEVIRTUAL, w64Script, "getWheel",
                        "(I)Lzombie/scripting/objects/VehicleScript$Wheel;") == 2);
        failed += check("W64 vanilla 前提：下車／換座（authorizationServerOnSeat）、碰撞歸還（authorizationServerCollide）、斷線（disconnectPlayer）"
                        + "都經 setNetPlayerAuthorization 交回；shouldSend 併入 vehicle.updateFlags",
                countExactCalls(methodFromJar(jar, w64Veh, "authorizationServerOnSeat", "(Lzombie/characters/IsoPlayer;Z)V"),
                        Opcodes.INVOKEVIRTUAL, w64Veh, "setNetPlayerAuthorization", w64AuthDesc) >= 1
                && countExactCalls(methodFromJar(jar, w64Veh, "authorizationServerCollide", "(SZ)V"),
                        Opcodes.INVOKEVIRTUAL, w64Veh, "setNetPlayerAuthorization", w64AuthDesc) >= 1
                && countExactCalls(methodFromJar(jar, "zombie/network/GameServer", "disconnectPlayer",
                        "(Lzombie/characters/IsoPlayer;Lzombie/network/IConnection;)V"),
                        Opcodes.INVOKEVIRTUAL, w64Veh, "setNetPlayerAuthorization", w64AuthDesc) == 1
                && countExactFields(vShouldSend, Opcodes.GETFIELD, w64Veh, "updateFlags", "S") == 1);
        MethodNode vW64Auth = methodFromJar(jar, w64Veh, "setNetPlayerAuthorization", w64AuthDesc);
        MethodNode pW64Auth = method(distJava, w64Veh, "setNetPlayerAuthorization", w64AuthDesc);
        failed += check("W64 手術後：setNetPlayerAuthorization 頭部 aload_0／aload_1／invokestatic 全序、真指令恰 +3",
                headCallSlotsOk(pW64Auth, w64Helper, "onSetAuthorization", w64HelperDesc, 0, 1)
                && realInsnCount(pW64Auth) == realInsnCount(vW64Auth) + 3);
        failed += check("W64 helper 契約：只在 BaseVehicle 上寫 updateFlags 一處（Full），不改授權欄位",
                countExactFields(method(distJava, w64Helper, "restore", "(L" + w64Veh + ";)Z"), Opcodes.PUTFIELD,
                        w64Veh, "updateFlags", "S") == 1
                && countFieldTouches(method(distJava, w64Helper, "restore", "(L" + w64Veh + ";)Z"), w64Veh,
                        "netPlayerAuthorization") == 0);
        String w64Xfrm = "zombie/core/physics/Transform";
        String w64GetWt = "(L" + w64Xfrm + ";)L" + w64Xfrm + ";";
        String w64GetRot = "(Lorg/joml/Quaternionf;)Lorg/joml/Quaternionf;";
        MethodNode w64Save = methodFromJar(jar, w64Veh, "save", "(Ljava/nio/ByteBuffer;Z)V");
        MethodNode w64Interp = methodFromJar(jar, "zombie/vehicles/VehicleInterpolationData", "set", "(L" + w64Veh + ";)V");
        MethodNode w64FullWrite = methodFromJar(jar, "zombie/network/packets/vehicle/VehicleFullUpdatePacket", "write",
                "(Lzombie/core/network/ByteBufferWriter;)V");
        failed += check("W64 vanilla 前提（扶正翻覆深陷的車）：load 照存檔旋轉放車、不呼叫 flipUpright；save 與 VehicleInterpolationData.set "
                        + "都從 getWorldTransform 取旋轉，VehicleFullUpdatePacket.write 經 save 送出（伺服器改 jniTransform 的旋轉會進存檔與完整更新）",
                countExactCalls(w64Load, Opcodes.INVOKEVIRTUAL, w64Xfrm, "setRotation", "(Lorg/joml/Quaternionf;)V") == 1
                && countExactCalls(w64Load, Opcodes.INVOKEVIRTUAL, w64Veh, "flipUpright", "()V") == 0
                && countExactCalls(w64Save, Opcodes.INVOKEVIRTUAL, w64Veh, "getWorldTransform", w64GetWt) == 1
                && countExactCalls(w64Save, Opcodes.INVOKEVIRTUAL, w64Xfrm, "getRotation", w64GetRot) == 1
                && countExactCalls(w64Interp, Opcodes.INVOKEVIRTUAL, w64Veh, "getWorldTransform", w64GetWt) == 1
                && countExactCalls(w64Interp, Opcodes.INVOKEVIRTUAL, w64Xfrm, "getRotation", w64GetRot) == 1
                && countExactCalls(w64FullWrite, Opcodes.INVOKEVIRTUAL, w64Veh, "save", "(Ljava/nio/ByteBuffer;)V") == 1);

        // W52：分割畫面與重生的名稱冒用守衛（docs/patches.md 2bp）。存在理由（TIS 修好即紅＝撤刀）：stage 1 唯一 getUTF
        // 的名稱存進 slot 4，兩個 setUserName 都直接拿它；parse 不比對連線登入名、不查帳號；receivePlayerConnect 把名稱參數
        // 設成 player.username；首次進場的 ConnectPacket 傳的是連線登入名（0 號改用登入名的依據）。
        String ccpCls = "zombie/network/packets/connection/ConnectCoopPacket";
        String ccgCls = "zombie/network/packets/connection/MdcCoopNameGuard";
        String bbrCls = "zombie/core/network/ByteBufferReader";
        String connItf = "zombie/network/IConnection";
        String swdCls = "zombie/network/ServerWorldDatabase";
        String ccpDesc = "(L" + bbrCls + ";L" + connItf + ";)V";
        String utfDesc = "()Ljava/lang/String;";
        MethodNode vCoopParse = methodFromJar(jar, ccpCls, "parse", ccpDesc);
        failed += check("W52 原版：parse 唯一 getUTF 存入 slot 4，兩個 setUserName 都直接載入它，slot 4 另外只有 stage 2 寫一次；"
                        + "不比對連線登入名、不查帳號",
                coopNameFlowOk(vCoopParse, bbrCls, connItf)
                && countExactCalls(vCoopParse, Opcodes.INVOKEINTERFACE, connItf, "getUserName", utfDesc) == 0
                && !methodText(vCoopParse).contains(swdCls));
        MethodNode vReceiveConnect = methodFromJar(jar, "zombie/network/GameServer", "receivePlayerConnect",
                "(L" + bbrCls + ";L" + connItf + ";Ljava/lang/String;)V");
        AbstractInsnNode connectCall = null;
        for (AbstractInsnNode in : methodFromJar(jar, "zombie/network/packets/connection/ConnectPacket", "parse",
                ccpDesc).instructions) {
            if (in instanceof MethodInsnNode mi && mi.name.equals("receivePlayerConnect")) {
                connectCall = in;
            }
        }
        failed += check("W52 原版：receivePlayerConnect 把名稱參數設成 player.username；首次進場 ConnectPacket 傳連線登入名",
                putFieldFromLocal(vReceiveConnect, "zombie/characters/IsoPlayer", "username", 2)
                && connectCall != null && prevReal(connectCall) instanceof MethodInsnNode login
                && login.owner.equals(connItf) && login.name.equals("getUserName") && login.desc.equals(utfDesc));
        MethodNode pCoopParse = method(distJava, ccpCls, "parse", ccpDesc);
        String beginDesc = "(L" + ccpCls + ";L" + connItf + ";)V";
        String readDesc = "(L" + bbrCls + ";)Ljava/lang/String;";
        failed += check("W52 手術後：頭部 aload_0／aload_2／begin 全序、唯一 getUTF 改道 readName，其餘指令與 frames 不變",
                headCallSlotsOk(pCoopParse, ccgCls, "begin", beginDesc, 0, 2)
                && methodText(pCoopParse).equals("    ALOAD 0\n    ALOAD 2\n    INVOKESTATIC " + ccgCls + ".begin "
                        + beginDesc + "\n" + methodText(vCoopParse).replace(
                                "INVOKEVIRTUAL " + bbrCls + ".getUTF " + utfDesc,
                                "INVOKESTATIC " + ccgCls + ".readName " + readDesc))
                && classRealInsnCount(classNode(distJava, ccpCls))
                        == classRealInsnCount(classNodeFromJar(jar, ccpCls)) + 3);
        ClassNode gCoop = classNode(distJava, ccgCls);
        MethodNode gReadName = method(distJava, ccgCls, "readName", readDesc);
        int coopDbCalls = 0;
        int coopNameWrites = 0;
        for (MethodNode m : gCoop.methods) {
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof MethodInsnNode mi) {
                    coopDbCalls += mi.owner.equals(swdCls) ? 1 : 0;
                    coopNameWrites += mi.name.equals("setUserName") ? 1 : 0;
                }
            }
        }
        failed += check("W52 helper 契約：readName 恰讀 1 次名稱且不在 try 內；資料庫只查 containsCaseinsensitiveUser；不寫任何名稱",
                countExactCalls(gReadName, Opcodes.INVOKEVIRTUAL, bbrCls, "getUTF", utfDesc) == 1
                && callsInsideTryRange(gReadName, Opcodes.INVOKEVIRTUAL, bbrCls, "getUTF", utfDesc) == 0
                && coopDbCalls == 1
                && classWideCalls(gCoop, Opcodes.INVOKEVIRTUAL, swdCls, "containsCaseinsensitiveUser",
                        "(Ljava/lang/String;)Z") == 1
                && coopNameWrites == 0);

        // W53：步行時縮小車輛相關範圍（docs/patches.md 2bq）。存在理由（TIS 改即紅＝重估）：sendVehicles 與
        // VehicleRequestPacket.processServer 各只有一個 isRelevantTo(FF)Z，前者決定送不送、後者判 false 就送
        // VehicleRemove；範圍是 relevantRange 的正方形（isRelevantTo 讀 relevantRange 兩次）。
        String vmCls = "zombie/vehicles/VehicleManager";
        String vrpCls = "zombie/network/packets/vehicle/VehicleRequestPacket";
        String relGateCls = "zombie/mdc/VehicleRelevancyGate";
        String w53Udp = "zombie/core/raknet/UdpConnection";
        String sendVehDesc = "(L" + w53Udp + ";)V";
        String vrpDesc = "(Lzombie/network/PacketTypes$PacketType;L" + w53Udp + ";)V";
        String isRelDesc = "(FF)Z";
        String gateDesc = "(L" + w53Udp + ";FF)Z";
        String isRelText = "INVOKEVIRTUAL " + w53Udp + ".isRelevantTo " + isRelDesc;
        MethodNode vSendVeh = methodFromJar(jar, vmCls, "sendVehicles", sendVehDesc);
        MethodNode vVrp = methodFromJar(jar, vrpCls, "processServer", vrpDesc);
        MethodInsnNode sendSite = null;
        MethodInsnNode keepSite = null;
        for (AbstractInsnNode in : vSendVeh.instructions) {
            if (in instanceof MethodInsnNode mi && mi.name.equals("isRelevantTo")) {
                sendSite = mi;
            }
        }
        for (AbstractInsnNode in : vVrp.instructions) {
            if (in instanceof MethodInsnNode mi && mi.name.equals("isRelevantTo")) {
                keepSite = mi;
            }
        }
        failed += check("W53 原版：sendVehicles 唯一 isRelevantTo（車 getX／getY 為參數）後接 IFEQ 跳過送出，方法內送 VehicleFullUpdate",
                countExactCalls(vSendVeh, Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo", isRelDesc) == 1
                && classWideCalls(classNodeFromJar(jar, vmCls), Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo",
                        isRelDesc) == 1
                && sendSite != null && nextReal(sendSite).getOpcode() == Opcodes.IFEQ
                && prevReal(sendSite) instanceof MethodInsnNode sendY && sendY.name.equals("getY")
                && sendY.owner.equals("zombie/vehicles/BaseVehicle")
                && methodText(vSendVeh).contains("GETSTATIC zombie/network/PacketTypes$PacketType.VehicleFullUpdate"));
        failed += check("W53 原版：processServer 唯一 isRelevantTo 在 16384 分支，false 才送 VehicleRemove",
                countExactCalls(vVrp, Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo", isRelDesc) == 1
                && classWideCalls(classNodeFromJar(jar, vrpCls), Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo",
                        isRelDesc) == 1
                && keepSite != null && nextReal(keepSite).getOpcode() == Opcodes.IFNE
                && nextReal(nextReal(keepSite)) instanceof VarInsnNode keepLoad && keepLoad.var == 2
                && nextReal(keepLoad) instanceof FieldInsnNode removeType && removeType.name.equals("VehicleRemove")
                && methodText(vVrp).contains("SIPUSH 16384"));
        failed += check("W53 原版：isRelevantTo 的半徑來自 relevantRange（兩軸各讀一次），不讀 chunkGridWidth",
                countExactFields(methodFromJar(jar, w53Udp, "isRelevantTo", isRelDesc), Opcodes.GETFIELD, w53Udp,
                        "relevantRange", "B") == 2
                && countExactFields(methodFromJar(jar, w53Udp, "isRelevantTo", isRelDesc), Opcodes.GETFIELD, w53Udp,
                        "chunkGridWidth", "I") == 0);
        MethodNode pSendVeh = method(distJava, vmCls, "sendVehicles", sendVehDesc);
        MethodNode pVrp = method(distJava, vrpCls, "processServer", vrpDesc);
        failed += check("W53 手術後：sendVehicles 唯一 isRelevantTo 改道 sendRelevant，其餘指令與 frames 不變；全 class 真指令數不變",
                methodText(pSendVeh).equals(methodText(vSendVeh).replace(isRelText,
                        "INVOKESTATIC " + relGateCls + ".sendRelevant " + gateDesc))
                && countExactCalls(pSendVeh, Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo", isRelDesc) == 0
                && realInsnCount(pSendVeh) == realInsnCount(vSendVeh)
                && classRealInsnCount(classNode(distJava, vmCls)) == classRealInsnCount(classNodeFromJar(jar, vmCls)));
        failed += check("W53 手術後：processServer 唯一 isRelevantTo 改道 keepRelevant，其餘指令與 frames 不變；全 class 真指令數不變",
                methodText(pVrp).equals(methodText(vVrp).replace(isRelText,
                        "INVOKESTATIC " + relGateCls + ".keepRelevant " + gateDesc))
                && countExactCalls(pVrp, Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo", isRelDesc) == 0
                && realInsnCount(pVrp) == realInsnCount(vVrp)
                && classRealInsnCount(classNode(distJava, vrpCls)) == classRealInsnCount(classNodeFromJar(jar, vrpCls)));
        failed += check("W53 負對照：兩個 class 的 isRelevantTo 都恰少 1（歸零）、各自只改道到自己的 helper 入口",
                classWideCalls(classNode(distJava, vmCls), Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo", isRelDesc) == 0
                && classWideCalls(classNode(distJava, vrpCls), Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo", isRelDesc) == 0
                && classWideCalls(classNode(distJava, vmCls), Opcodes.INVOKESTATIC, relGateCls, "sendRelevant", gateDesc) == 1
                && classWideCalls(classNode(distJava, vmCls), Opcodes.INVOKESTATIC, relGateCls, "keepRelevant", gateDesc) == 0
                && classWideCalls(classNode(distJava, vrpCls), Opcodes.INVOKESTATIC, relGateCls, "keepRelevant", gateDesc) == 1
                && classWideCalls(classNode(distJava, vrpCls), Opcodes.INVOKESTATIC, relGateCls, "sendRelevant", gateDesc) == 0);
        String decideDesc = "(L" + w53Udp + ";FFLjava/util/concurrent/atomic/AtomicLong;Ljava/util/concurrent/atomic/AtomicLong;)Z";
        MethodNode gDecide = method(distJava, relGateCls, "decide", decideDesc);
        MethodNode gInVehicle = method(distJava, relGateCls, "vehicleState", "(L" + w53Udp + ";)I");
        MethodNode gStill = method(distJava, relGateCls, "isStill",
                "(Lzombie/characters/IsoPlayer;Lzombie/vehicles/BaseVehicle;J)Z");
        failed += check("W53 helper 契約：兩個入口共用同一個 decide；decide 先呼叫原版 isRelevantTo 恰 1 次（只縮不放），"
                        + "並各查一次 connectArea／半徑／車內；停車判定只讀一次車速",
                countExactCalls(method(distJava, relGateCls, "sendRelevant", gateDesc), Opcodes.INVOKESTATIC, relGateCls,
                        "decide", decideDesc) == 1
                && countExactCalls(method(distJava, relGateCls, "keepRelevant", gateDesc), Opcodes.INVOKESTATIC, relGateCls,
                        "decide", decideDesc) == 1
                && classWideCalls(classNode(distJava, relGateCls), Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo",
                        isRelDesc) == 1
                && countExactCalls(gDecide, Opcodes.INVOKEVIRTUAL, w53Udp, "isRelevantTo", isRelDesc) == 1
                && countExactCalls(gDecide, Opcodes.INVOKESTATIC, relGateCls, "anyConnectArea", "(L" + w53Udp + ";)Z") == 1
                && countExactCalls(gDecide, Opcodes.INVOKESTATIC, relGateCls, "withinRadius", gateDesc) == 1
                && countExactCalls(gDecide, Opcodes.INVOKESTATIC, relGateCls, "vehicleState",
                        "(L" + w53Udp + ";)I") == 1
                && countExactCalls(gInVehicle, Opcodes.INVOKEVIRTUAL, w53Udp, "getPlayerAt",
                        "(I)Lzombie/characters/IsoPlayer;") == 1
                && countExactCalls(gInVehicle, Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoPlayer", "getVehicle",
                        "()Lzombie/vehicles/BaseVehicle;") == 1
                && countExactCalls(gStill, Opcodes.INVOKEVIRTUAL, "zombie/vehicles/BaseVehicle",
                        "getCurrentAbsoluteSpeedKmHour", "()F") == 1);
        failed += check("W32 vanilla 以 zone.hourLastSeen 推算離線時數",
                methodText(vFromWorker).contains("GETFIELD zombie/iso/areas/DesignationZone.hourLastSeen"));
        failed += check("W32 唯一改道同形，其餘指令與 frames 保留",
                methodText(vFromWorker).replace(
                        "INVOKEVIRTUAL zombie/characters/animals/IsoAnimal.updateStatsAway (I)V",
                        "INVOKESTATIC " + awayHelper + ".updateStatsAway " + awayDesc).equals(methodText(pFromWorker)));


        // W33：分娩品種守衛。原版 addBaby 以 getBreedByName 結果直接建構（無 null 檢查）是本刀存在理由；
        // TIS 補上檢查時該條會紅＝撤刀。
        String adCls = "zombie/characters/animals/datas/AnimalData";
        String babyDesc = "()Lzombie/characters/animals/IsoAnimal;";
        String babyHelper = "zombie/mdc/BabyBreedGuard";
        failed += check("W33 addBaby 全 jar 恰 4 個呼叫點（分娩 1＋生成故事 3，後者刻意不動）",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, "zombie/characters/animals/IsoAnimal",
                        "addBaby", babyDesc) == 4);
        String vAddBaby = methodText(methodFromJar(jar, "zombie/characters/animals/IsoAnimal", "addBaby", babyDesc));
        failed += check("W33 vanilla addBaby 將 getBreedByName 結果直接交給建構子",
                java.util.regex.Pattern.compile(
                        "INVOKEVIRTUAL zombie/characters/animals/AnimalDefinitions\\.getBreedByName [^\\n]*\\n\\s*"
                        + "INVOKESPECIAL zombie/characters/animals/IsoAnimal\\.<init>").matcher(vAddBaby).find());
        failed += check("W33 checkPregnancy 唯一改道同形，其餘指令與 frames 保留",
                methodText(methodFromJar(jar, adCls, "checkPregnancy", "()V")).replace(
                        "INVOKEVIRTUAL zombie/characters/animals/IsoAnimal.addBaby " + babyDesc,
                        "INVOKESTATIC " + babyHelper + ".addBaby (Lzombie/characters/animals/IsoAnimal;)Lzombie/characters/animals/IsoAnimal;")
                        .equals(methodText(method(distJava, adCls, "checkPregnancy", "()V"))));

        // W37：動物半建構物件守衛＋apop 先序列化再開檔。存在理由三條（TIS 修好時會紅＝撤刀）：
        // super() 先把角色加進 cell 與格子、IsoAnimal 建構子之後才做兩項會跳過 init 的檢查、AnimalCell.save
        // 先開檔（截斷）才序列化。42.21 起加入 cell 改經 IsoCell.addMovingObject、chickenpocalypse 多一個
        // replacingAnimal 參數（建構子傳 null）；afterCtor 撤的是 addMovingObject 兩分支＋setMovingSquareNow。
        String animalCls = "zombie/characters/animals/IsoAnimal";
        String spawnHelper = "zombie/mdc/AnimalSpawnGuard";
        String afterCtorDesc = "(L" + animalCls + ";)V";
        MethodNode vCharCtor = methodFromJar(jar, "zombie/characters/IsoGameCharacter", "<init>", "(Lzombie/iso/IsoCell;FFF)V");
        failed += check("W37 vanilla IsoGameCharacter 建構子先把物件加入 cell（addMovingObject 恰 1）與格子（setMovingSquareNow 恰 1）",
                countExactCalls(vCharCtor, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "addMovingObject",
                        "(Lzombie/iso/IsoMovingObject;)V") == 1
                && countExactCalls(vCharCtor, Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoGameCharacter",
                        "setMovingSquareNow", "()V") == 1);
        // afterCtor 的逆操作依據：addMovingObject 只有 isSafeToAdd→objectList.add／addList.add 兩分支，別無登記。
        // vAddMoving 沿用 W47 段已載入的同一個 vanilla 方法。
        failed += check("W37 vanilla IsoCell.addMovingObject 只有 isSafeToAdd→objectList／addList 兩分支",
                countExactCalls(vAddMoving, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "isSafeToAdd", "()Z") == 1
                && countExactFields(vAddMoving, Opcodes.GETFIELD, "zombie/iso/IsoCell", "objectList", "Ljava/util/Set;") == 1
                && countExactFields(vAddMoving, Opcodes.GETFIELD, "zombie/iso/IsoCell", "addList", "Ljava/util/Set;") == 1
                && countExactCalls(vAddMoving, Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z") == 2
                && countOpcode(vAddMoving, Opcodes.INVOKEVIRTUAL) + countOpcode(vAddMoving, Opcodes.INVOKEINTERFACE)
                        + countOpcode(vAddMoving, Opcodes.INVOKESTATIC) + countOpcode(vAddMoving, Opcodes.INVOKESPECIAL) == 3);
        String[] w37Ctors = {
                "(Lzombie/iso/IsoCell;IIILjava/lang/String;Ljava/lang/String;)V",
                "(Lzombie/iso/IsoCell;IIILjava/lang/String;Ljava/lang/String;Z)V",
                "(Lzombie/iso/IsoCell;IIILjava/lang/String;Lzombie/characters/animals/datas/AnimalBreed;)V",
                "(Lzombie/iso/IsoCell;IIILjava/lang/String;Lzombie/characters/animals/datas/AnimalBreed;Z)V"};
        for (String ctorDesc : w37Ctors) {
            MethodNode vCtor = methodFromJar(jar, animalCls, "<init>", ctorDesc);
            MethodNode pCtor = method(distJava, animalCls, "<init>", ctorDesc);
            int returns = 0;
            for (AbstractInsnNode in : vCtor.instructions) {
                if (in.getOpcode() == Opcodes.RETURN) returns++;
            }
            failed += check("W37 vanilla 建構子 " + ctorDesc + " 含 chickenpocalypse＋water 兩項檢查",
                    countExactCalls(vCtor, Opcodes.INVOKEVIRTUAL, animalCls, "checkForChickenpocalypse", "(L" + animalCls + ";)Z") == 1
                    && countExactCalls(vCtor, Opcodes.INVOKEVIRTUAL, animalCls, "checkForWater", "()Z") == 1);
            failed += check("W37 建構子 " + ctorDesc + " 每個 RETURN 前 afterCtor、真指令恰 +2×RETURN",
                    tailCallOk(pCtor, spawnHelper, "afterCtor", afterCtorDesc)
                    && realInsnCount(pCtor) == realInsnCount(vCtor) + 2 * returns);
        }
        failed += check("W37 checkStages 唯一 grow 同形改道，其餘指令與 frames 保留",
                methodText(methodFromJar(jar, adCls, "checkStages", "()V")).replace(
                        "INVOKEVIRTUAL " + adCls + ".grow (Ljava/lang/String;)V",
                        "INVOKESTATIC " + spawnHelper + ".grow (L" + adCls + ";Ljava/lang/String;)V")
                        .equals(methodText(method(distJava, adCls, "checkStages", "()V"))));
        String cellCls = "zombie/characters/animals/AnimalCell";
        String cellHelper = "zombie/characters/animals/MdcAnimalCellSave";
        String vCellSave = methodText(methodFromJar(jar, cellCls, "save", "()V"));
        int fosAt = vCellSave.indexOf("INVOKESPECIAL java/io/FileOutputStream.<init>");
        int serAt = vCellSave.indexOf("INVOKEVIRTUAL " + cellCls + ".save (Ljava/nio/ByteBuffer;)V");
        failed += check("W37 vanilla AnimalCell.save 先開檔（截斷）才序列化",
                fosAt >= 0 && serAt > fosAt);
        failed += check("W37 AnimalCell.save() 全 jar 恰 2 個呼叫點（worker.save＋cell.unload）",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, cellCls, "save", "()V") == 2);
        String[][] cellSaveCallers = {{cellCls, "unload"}, {"zombie/characters/animals/AnimalManagerWorker", "save"}};
        for (String[] caller : cellSaveCallers) {
            failed += check("W37 " + caller[1] + " 的 AnimalCell.save 同形改道，其餘指令與 frames 保留",
                    methodText(methodFromJar(jar, caller[0], caller[1], "()V")).replace(
                            "INVOKEVIRTUAL " + cellCls + ".save ()V",
                            "INVOKESTATIC " + cellHelper + ".save (L" + cellCls + ";)V")
                            .equals(methodText(method(distJava, caller[0], caller[1], "()V"))));
        }
        // 聲音觀測只包既有已認證派送，不修改 wire class 或任何聲音／魚群方法。
        String soundProbe = "zombie/network/packets/sound/MdcWorldSoundProbe";
        String soundPacket = "zombie/network/packets/sound/WorldSoundPacket";
        String soundDispatchDesc = "(L" + soundPacket
                + ";Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V";
        MethodNode observedDispatch = method(distJava, "zombie/core/MdcTimedActionProbe", "processServer",
                "(Lzombie/network/packets/INetworkPacket;Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V");
        failed += check("WorldSound 觀測只由既有派送器呼叫一次",
                countExactCalls(observedDispatch, Opcodes.INVOKESTATIC, soundProbe,
                        "processServer", soundDispatchDesc) == 1);
        failed += check("WorldSound 原 wire class 未被覆蓋",
                !Files.exists(distJava.resolve(soundPacket + ".class")));
        failed += check("WorldSound 批次在既有主迴圈掛點收尾",
                countExactCalls(method(distJava, wdCls, "tick", wdTickDesc),
                        Opcodes.INVOKESTATIC, soundProbe, "onTick", "()V") == 1);
        failed += check("WorldSound 進行中呼叫由既有看門狗取樣",
                countExactCalls(method(distJava, wdCls, "dump", "(JI)V"),
                        Opcodes.INVOKESTATIC, soundProbe, "describeActive", "()Ljava/lang/String;") == 1);

        // ---- W58 SyncIsoObject 洪流觀測（docs/patches.md 2bv）----
        // receiveSyncIsoObject 內唯一的 IsoObject.syncIsoObject 同形改道；helper 照原版呼叫原方法恰一次。
        String w58Gs = "zombie/network/GameServer";
        String w58Iso = "zombie/iso/IsoObject";
        String w58Probe = "zombie/mdc/SyncIsoObjectProbe";
        String w58Desc = "(ZBLzombie/core/raknet/UdpConnection;Lzombie/core/network/ByteBufferReader;)V";
        String w58ProbeDesc = "(L" + w58Iso + ";" + w58Desc.substring(1);
        String w58RecvDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;S)V";
        MethodNode w58VRecv = methodFromJar(jar, w58Gs, "receiveSyncIsoObject", w58RecvDesc);
        MethodNode w58PRecv = method(distJava, w58Gs, "receiveSyncIsoObject", w58RecvDesc);
        failed += check("W58 vanilla 前提：receiveSyncIsoObject 內恰 1 個 IsoObject.syncIsoObject，且是 GameServer 全 class 唯一",
                countExactCalls(w58VRecv, Opcodes.INVOKEVIRTUAL, w58Iso, "syncIsoObject", w58Desc) == 1
                && classWideCalls(classNodeFromJar(jar, w58Gs), Opcodes.INVOKEVIRTUAL, w58Iso, "syncIsoObject", w58Desc) == 1);
        failed += check("W58 手術後：唯一 syncIsoObject 同形改道 SyncIsoObjectProbe.syncIsoObject，其餘指令與 frames 保留",
                methodText(w58VRecv).replace("INVOKEVIRTUAL " + w58Iso + ".syncIsoObject " + w58Desc,
                        "INVOKESTATIC " + w58Probe + ".syncIsoObject " + w58ProbeDesc).equals(methodText(w58PRecv))
                && classWideCalls(classNode(distJava, w58Gs), Opcodes.INVOKEVIRTUAL, w58Iso, "syncIsoObject", w58Desc) == 0
                && classWideCalls(classNode(distJava, w58Gs), Opcodes.INVOKESTATIC, w58Probe, "syncIsoObject", w58ProbeDesc) == 1);
        failed += check("W58 helper 契約：原方法在 helper 內恰呼叫 1 次",
                countExactCalls(method(distJava, w58Probe, "syncIsoObject", w58ProbeDesc),
                        Opcodes.INVOKEVIRTUAL, w58Iso, "syncIsoObject", w58Desc) == 1
                && classWideCalls(classNode(distJava, w58Probe), Opcodes.INVOKEVIRTUAL, w58Iso, "syncIsoObject", w58Desc) == 1);

        // ---- W59 世界地圖隊友點錯位（docs/patches.md 2bw）----
        // GameServer 送出計數的兩個 getChangeCount 同形改道；客戶端唯一讀點是 receiveWorldMapPlayerPosition 的相等比較。
        String w59Gs = "zombie/network/GameServer";
        String w59Wm = "zombie/worldMap/WorldMapRemotePlayer";
        String w59Helper = "zombie/mdc/WorldMapChangeCount";
        String w59HelperDesc = "(L" + w59Wm + ";)S";
        String[][] w59Methods = {
                {"sendWorldMapPlayerPosition", "(Lzombie/core/raknet/UdpConnection;)V"},
                {"receiveWorldMapPlayerPosition", "(Lzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;S)V"}};
        boolean w59Vanilla = classWideCalls(classNodeFromJar(jar, w59Gs), Opcodes.INVOKEVIRTUAL, w59Wm, "getChangeCount", "()S") == 2
                && classWideCalls(classNodeFromJar(jar, "zombie/network/GameClient"),
                        Opcodes.INVOKEVIRTUAL, w59Wm, "getChangeCount", "()S") == 1
                && countExactCalls(methodFromJar(jar, "zombie/network/GameClient", "receiveWorldMapPlayerPosition",
                        "(Lzombie/core/network/ByteBufferReader;S)V"), Opcodes.INVOKEVIRTUAL, w59Wm, "getChangeCount", "()S") == 1;
        boolean w59Patched = classWideCalls(classNode(distJava, w59Gs), Opcodes.INVOKEVIRTUAL, w59Wm, "getChangeCount", "()S") == 0
                && classWideCalls(classNode(distJava, w59Gs), Opcodes.INVOKESTATIC, w59Helper, "getChangeCount", w59HelperDesc) == 2;
        for (String[] w59M : w59Methods) {
            MethodNode w59V = methodFromJar(jar, w59Gs, w59M[0], w59M[1]);
            boolean w59ToWire = false;
            for (AbstractInsnNode in : w59V.instructions) {
                if (isCall(in, Opcodes.INVOKEVIRTUAL, w59Wm, "getChangeCount", "()S")) {
                    w59ToWire = isCall(nextReal(in), Opcodes.INVOKEVIRTUAL, "zombie/core/network/ByteBufferWriter", "putShort", "(S)V");
                }
            }
            w59Vanilla &= countExactCalls(w59V, Opcodes.INVOKEVIRTUAL, w59Wm, "getChangeCount", "()S") == 1 && w59ToWire;
            w59Patched &= methodText(w59V).replace("INVOKEVIRTUAL " + w59Wm + ".getChangeCount ()S",
                    "INVOKESTATIC " + w59Helper + ".getChangeCount " + w59HelperDesc)
                    .equals(methodText(method(distJava, w59Gs, w59M[0], w59M[1])));
        }
        failed += check("W59 vanilla 前提：GameServer 恰 2 個 getChangeCount（兩個送出方法各 1，下一條是 putShort），GameClient 只在 receiveWorldMapPlayerPosition 讀 1 次",
                w59Vanilla);
        failed += check("W59 手術後：兩個 getChangeCount 同形改道 WorldMapChangeCount.getChangeCount，其餘指令與 frames 保留",
                w59Patched);
        failed += check("W59 helper 契約：原計數在 helper 內恰讀 1 次",
                countExactCalls(method(distJava, w59Helper, "getChangeCount", w59HelperDesc),
                        Opcodes.INVOKEVIRTUAL, w59Wm, "getChangeCount", "()S") == 1);

        // ---- W60 動物遺失／複製觀測（docs/patches.md 2bx）----
        // 四個方法各自的原呼叫同形改道 AnimalLossProbe；放下動物的三個呼叫必須依序 copyFrom → get → add（helper 靠這個順序配對）。
        String w60Probe = "zombie/mdc/AnimalLossProbe";
        String w60Animal = "zombie/characters/animals/IsoAnimal";
        String w60Aim = "zombie/popman/animal/AnimalInstanceManager";
        String w60Chunk = "zombie/iso/IsoChunk";
        String w60AnimalArg = "(L" + w60Animal + ";)";
        // {class, method, desc, owner, callee, calleeDesc, helper, helperDesc}
        String[][] w60Sites = {
                {"zombie/iso/IsoGridSquare", "AddWorldInventoryItem", "(Lzombie/inventory/InventoryItem;FFFZZ)Lzombie/inventory/InventoryItem;",
                        w60Animal, "copyFrom", w60AnimalArg + "V", "dropCopy", "(L" + w60Animal + ";L" + w60Animal + ";)V"},
                {"zombie/iso/IsoGridSquare", "AddWorldInventoryItem", "(Lzombie/inventory/InventoryItem;FFFZZ)Lzombie/inventory/InventoryItem;",
                        w60Aim, "get", "(S)L" + w60Animal + ";", "dropLookup", "(L" + w60Aim + ";S)L" + w60Animal + ";"},
                {"zombie/iso/IsoGridSquare", "AddWorldInventoryItem", "(Lzombie/inventory/InventoryItem;FFFZZ)Lzombie/inventory/InventoryItem;",
                        w60Aim, "add", "(L" + w60Animal + ";S)V", "dropRegister", "(L" + w60Aim + ";L" + w60Animal + ";S)V"},
                {"zombie/network/ServerChunkLoader$LoaderThread", "run", "()V",
                        w60Chunk, "LoadChunk", "(IILjava/nio/ByteBuffer;)Z", "loadChunk", "(L" + w60Chunk + ";IILjava/nio/ByteBuffer;)Z"},
                {"zombie/network/ServerChunkLoader$SaveUnloadedTask", "save", "()V",
                        w60Chunk, "Save", "(Z)V", "saveUnloaded", "(L" + w60Chunk + ";Z)V"},
                {"zombie/characters/animals/datas/AnimalData", "grow", "(Ljava/lang/String;)V",
                        w60Animal, "checkForChickenpocalypse", w60AnimalArg + "Z", "growDuplicate", "(L" + w60Animal + ";L" + w60Animal + ";)Z"}};
        boolean w60Vanilla = true;
        boolean w60Helper = true;
        // 方法 → {class, name, desc, 預期手術後文字}；同一個方法的多個改道依序套到同一份文字上
        java.util.Map<String, String[]> w60Expected = new java.util.LinkedHashMap<>();
        for (String[] s : w60Sites) {
            MethodNode v = methodFromJar(jar, s[0], s[1], s[2]);
            w60Vanilla &= countExactCalls(v, Opcodes.INVOKEVIRTUAL, s[3], s[4], s[5]) == 1;
            String[] exp = w60Expected.computeIfAbsent(s[0] + "." + s[1] + s[2], k -> new String[]{s[0], s[1], s[2], methodText(v)});
            exp[3] = exp[3].replace("INVOKEVIRTUAL " + s[3] + "." + s[4] + " " + s[5], "INVOKESTATIC " + w60Probe + "." + s[6] + " " + s[7]);
            w60Helper &= countExactCalls(method(distJava, w60Probe, s[6], s[7]), Opcodes.INVOKEVIRTUAL, s[3], s[4], s[5]) == 1;
        }
        // 放下動物的順序，以及 LoadChunk 緊接在 saveNow 之後
        int w60Copy = -1, w60Get = -1, w60Add = -1, w60SaveNow = -1, w60Load = -1, w60I = 0;
        for (AbstractInsnNode in : methodFromJar(jar, "zombie/iso/IsoGridSquare", "AddWorldInventoryItem",
                "(Lzombie/inventory/InventoryItem;FFFZZ)Lzombie/inventory/InventoryItem;").instructions) {
            w60I++;
            if (isCall(in, Opcodes.INVOKEVIRTUAL, w60Animal, "copyFrom", w60AnimalArg + "V")) w60Copy = w60I;
            if (isCall(in, Opcodes.INVOKEVIRTUAL, w60Aim, "get", "(S)L" + w60Animal + ";")) w60Get = w60I;
            if (isCall(in, Opcodes.INVOKEVIRTUAL, w60Aim, "add", "(L" + w60Animal + ";S)V")) w60Add = w60I;
        }
        w60I = 0;
        for (AbstractInsnNode in : methodFromJar(jar, "zombie/network/ServerChunkLoader$LoaderThread", "run", "()V").instructions) {
            w60I++;
            if (isCall(in, Opcodes.INVOKEVIRTUAL, "zombie/network/ServerChunkLoader$SaveChunkThread", "saveNow", "(II)V")) w60SaveNow = w60I;
            if (isCall(in, Opcodes.INVOKEVIRTUAL, w60Chunk, "LoadChunk", "(IILjava/nio/ByteBuffer;)Z")) w60Load = w60I;
        }
        failed += check("W60 vanilla 前提：四個方法各恰 1 個被觀測的呼叫；放下動物依序 copyFrom → get → add；LoaderThread 先 saveNow 再 LoadChunk",
                w60Vanilla && 0 < w60Copy && w60Copy < w60Get && w60Get < w60Add && 0 < w60SaveNow && w60SaveNow < w60Load);
        // W37 (3)(4)：同一個 AddWorldInventoryItem 另有兩個改道（dropPlace、dropSend）與兩個欄位改道（dropX、dropY），
        // 一起算進手術後的預期文字。存在理由（TIS 改掉時紅＝重估）：動物分支先在放下的格子 new IsoAnimal（座標參數是
        // 這一格的 x、y 欄位，方法內各恰 1 個 GETFIELD、都在 NEW 與 <init> 之間、都讀 this），之後才 setMovingSquareNow
        // 放回格子、sendToRelative 告訴客戶端，各恰 1；IsoGameCharacter.removeFromWorld 呼叫 releaseRagdollController（沒建構
        // 完成的動物在這裡 NPE）；IsoChunk.removeFromWorld 有兩個 IsoMovingObject.removeFromWorld（movingObjects／staticMovingObjects）。
        String w37Helper = "zombie/mdc/AnimalSpawnGuard";
        String w37Net = "zombie/network/packets/INetworkPacket";
        String w37Sq = "zombie/iso/IsoGridSquare";
        String w37RelDesc = "(Lzombie/network/PacketTypes$PacketType;FF[Ljava/lang/Object;)V";
        String w37DropDesc = "(Lzombie/inventory/InventoryItem;FFFZZ)Lzombie/inventory/InventoryItem;";
        MethodNode vDrop = methodFromJar(jar, w37Sq, "AddWorldInventoryItem", w37DropDesc);
        int w37NewObj = -1, w37GetX = -1, w37GetY = -1, w37New = -1, w37Place = -1, w37Send = -1;
        boolean w37CoordsFromThis = true;
        w60I = 0;
        for (AbstractInsnNode in : vDrop.instructions) {
            w60I++;
            if (in instanceof TypeInsnNode w37Type && w37Type.getOpcode() == Opcodes.NEW && w37Type.desc.equals(w60Animal)) w37NewObj = w60I;
            if (isField(in, Opcodes.GETFIELD, w37Sq, "x", "I")) w37GetX = w60I;
            if (isField(in, Opcodes.GETFIELD, w37Sq, "y", "I")) w37GetY = w60I;
            if (isField(in, Opcodes.GETFIELD, w37Sq, "x", "I") || isField(in, Opcodes.GETFIELD, w37Sq, "y", "I")) {
                w37CoordsFromThis &= isVar(prevReal(in), Opcodes.ALOAD, 0);
            }
            if (isCall(in, Opcodes.INVOKESPECIAL, w60Animal, "<init>",
                    "(Lzombie/iso/IsoCell;IIILjava/lang/String;Lzombie/characters/animals/datas/AnimalBreed;)V")) w37New = w60I;
            if (isCall(in, Opcodes.INVOKEVIRTUAL, w60Animal, "setMovingSquareNow", "()V")) w37Place = w60I;
            if (isCall(in, Opcodes.INVOKESTATIC, w37Net, "sendToRelative", w37RelDesc)) w37Send = w60I;
        }
        failed += check("W37 vanilla 前提：放下動物先 new IsoAnimal（座標參數讀這一格的 x、y 欄位，各恰 1），再 setMovingSquareNow、"
                        + "sendToRelative 各恰 1；IsoGameCharacter.removeFromWorld 呼叫 releaseRagdollController；"
                        + "IsoChunk.removeFromWorld 有 2 個 removeFromWorld",
                countExactFields(vDrop, Opcodes.GETFIELD, w37Sq, "x", "I") == 1
                && countExactFields(vDrop, Opcodes.GETFIELD, w37Sq, "y", "I") == 1
                && w37CoordsFromThis && 0 < w37NewObj && w37NewObj < w37GetX && w37GetX < w37GetY && w37GetY < w37New
                && countExactCalls(vDrop, Opcodes.INVOKEVIRTUAL, w60Animal, "setMovingSquareNow", "()V") == 1
                && countExactCalls(vDrop, Opcodes.INVOKESTATIC, w37Net, "sendToRelative", w37RelDesc) == 1
                && 0 < w37New && w37New < w37Place && w37Place < w37Send
                && countExactCalls(methodFromJar(jar, "zombie/characters/IsoGameCharacter", "removeFromWorld", "()V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoGameCharacter", "releaseRagdollController", "()V") == 1
                && countExactCalls(methodFromJar(jar, w60Chunk, "removeFromWorld", "()V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoMovingObject", "removeFromWorld", "()V") == 2);
        String[] w37Drop = w60Expected.get(w37Sq + ".AddWorldInventoryItem" + w37DropDesc);
        w37Drop[3] = w37Drop[3]
                .replace("GETFIELD " + w37Sq + ".x : I", "INVOKESTATIC " + w37Helper + ".dropX (L" + w37Sq + ";)I")
                .replace("GETFIELD " + w37Sq + ".y : I", "INVOKESTATIC " + w37Helper + ".dropY (L" + w37Sq + ";)I")
                .replace("INVOKEVIRTUAL " + w60Animal + ".setMovingSquareNow ()V",
                        "INVOKESTATIC " + w37Helper + ".dropPlace (L" + w60Animal + ";)V")
                // 原呼叫是介面靜態方法（Textifier 標 (itf)），改道後是一般類別的 INVOKESTATIC
                .replace("INVOKESTATIC " + w37Net + ".sendToRelative " + w37RelDesc + " (itf)",
                        "INVOKESTATIC " + w37Helper + ".dropSend " + w37RelDesc);
        failed += check("W37 helper 契約：dropPlace／dropSend／chunkRemove 各委派原呼叫恰 1 次；dropX／dropY 各經 landFor 讀 getX／getY 恰 1 次",
                countExactCalls(method(distJava, w37Helper, "dropPlace", "(L" + w60Animal + ";)V"),
                        Opcodes.INVOKEVIRTUAL, w60Animal, "setMovingSquareNow", "()V") == 1
                && countExactCalls(method(distJava, w37Helper, "dropSend", w37RelDesc),
                        Opcodes.INVOKESTATIC, w37Net, "sendToRelative", w37RelDesc) == 1
                && countExactCalls(method(distJava, w37Helper, "chunkRemove", "(Lzombie/iso/IsoMovingObject;)V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoMovingObject", "removeFromWorld", "()V") == 1
                && countExactCalls(method(distJava, w37Helper, "dropX", "(L" + w37Sq + ";)I"),
                        Opcodes.INVOKESTATIC, w37Helper, "landFor", "(L" + w37Sq + ";)L" + w37Sq + ";") == 1
                && countExactCalls(method(distJava, w37Helper, "dropX", "(L" + w37Sq + ";)I"),
                        Opcodes.INVOKEVIRTUAL, w37Sq, "getX", "()I") == 1
                && countExactCalls(method(distJava, w37Helper, "dropY", "(L" + w37Sq + ";)I"),
                        Opcodes.INVOKESTATIC, w37Helper, "landFor", "(L" + w37Sq + ";)L" + w37Sq + ";") == 1
                && countExactCalls(method(distJava, w37Helper, "dropY", "(L" + w37Sq + ";)I"),
                        Opcodes.INVOKEVIRTUAL, w37Sq, "getY", "()I") == 1);
        boolean w60Patched = true;
        for (String[] exp : w60Expected.values()) {
            w60Patched &= exp[3].equals(methodText(method(distJava, exp[0], exp[1], exp[2])));
        }
        failed += check("W60 手術後：六個呼叫同形改道 AnimalLossProbe（AddWorldInventoryItem 另含 W37 兩個呼叫、兩個欄位改道），其餘指令與 frames 保留", w60Patched);
        failed += check("W60 helper 契約：每個原方法在 helper 內恰呼叫 1 次", w60Helper);

        // ---- W66 畜牧區同步修補（docs/patches.md 2cb）----
        // 存在理由：SyncZonePacket 的 Zone 分支（setData／parse 非畜牧區）不清畜牧區欄位、write 先看 designationZone；
        // 全 jar 只有 removeGrass 用 SyncZone 送草地再生；DesignationZone 建構子以暫時 id 查重。TIS 修掉任一條時紅＝重估撤刀。
        String w66Packet = "zombie/network/packets/SyncZonePacket";
        String w66Zone = "zombie/iso/areas/DesignationZone";
        String w66ZoneDesc = "L" + w66Zone + ";";
        String w66Net = "zombie/network/packets/INetworkPacket";
        String w66PacketType = "zombie/network/PacketTypes$PacketType";
        String w66RelDesc = "(L" + w66PacketType + ";FF[Ljava/lang/Object;)V";
        String w66LoadDesc = "(Ljava/nio/ByteBuffer;I)" + w66ZoneDesc;
        String w66SendDesc = "(L" + w66PacketType + ";Lzombie/core/raknet/UdpConnection;)V";
        String w66Guard = "zombie/mdc/ZoneSyncGuard";
        String w66Log = "zombie/network/packets/MdcSyncZoneLog";
        MethodNode w66SetData = methodFromJar(jar, w66Packet, "setData", "([Ljava/lang/Object;)V");
        MethodNode w66Parse = methodFromJar(jar, w66Packet, "parse",
                "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V");
        MethodNode w66Write = methodFromJar(jar, w66Packet, "write", "(Lzombie/core/network/ByteBufferWriter;)V");
        int w66LastZonePut = -1, w66FirstXPut = Integer.MAX_VALUE, w66ZonePuts = 0, w66I = 0;
        for (AbstractInsnNode in : w66Parse.instructions) {
            if (in.getOpcode() < 0) continue;
            w66I++;
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(w66Packet)) {
                if (f.name.equals("designationZone")) { w66ZonePuts++; w66LastZonePut = w66I; }
                if (f.name.equals("x")) w66FirstXPut = Math.min(w66FirstXPut, w66I);
            }
        }
        int w66SetDataZonePuts = 0;
        for (AbstractInsnNode in : w66SetData.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(w66Packet)
                    && f.name.equals("designationZone")) w66SetDataZonePuts++;
        }
        AbstractInsnNode w66WriteFirstGet = null;
        for (AbstractInsnNode in : w66Write.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD) { w66WriteFirstGet = in; break; }
        }
        failed += check("W66 vanilla 前提：SyncZonePacket 的 Zone 分支不清畜牧區欄位（parse 兩個 designationZone 寫入都在 x 之前、setData 只在畜牧區分支寫一次），write 先讀 designationZone",
                w66ZonePuts == 2 && w66LastZonePut < w66FirstXPut && w66SetDataZonePuts == 1
                        && w66WriteFirstGet instanceof FieldInsnNode w66F && w66F.name.equals("designationZone"));
        MethodNode w66Grass = methodFromJar(jar, "zombie/iso/IsoGridSquare", "removeGrass", "()Z");
        java.util.List<String> w66GrassTypes = new java.util.ArrayList<>();
        String w66LastType = null;
        for (AbstractInsnNode in : w66Grass.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(w66PacketType)) w66LastType = f.name;
            if (isCall(in, Opcodes.INVOKESTATIC, w66Net, "sendToRelative", w66RelDesc)) w66GrassTypes.add(w66LastType);
        }
        java.util.Map<String, Integer> w66SyncZoneRefs = jarWideFieldRefs(jar, Opcodes.GETSTATIC, w66PacketType, "SyncZone");
        w66SyncZoneRefs.keySet().removeIf(w66Key -> w66Key.startsWith(w66PacketType + "."));
        failed += check("W66 vanilla 前提：removeGrass 恰 2 個 sendToRelative，依序 RegisterZone、SyncZone；全 jar 用 SyncZone 的只有 removeGrass×1、DesignationZone.sync×2、SyncZonePacket.processServer×1（實際 "
                        + w66SyncZoneRefs + "）",
                w66GrassTypes.equals(java.util.List.of("RegisterZone", "SyncZone"))
                        && w66SyncZoneRefs.equals(java.util.Map.of("zombie/iso/IsoGridSquare.removeGrass", 1,
                                w66Zone + ".sync", 2, w66Packet + ".processServer", 1)));
        MethodNode w66Ctor = methodFromJar(jar, w66Zone, "<init>", "(Ljava/lang/String;Ljava/lang/String;IIIIIZ)V");
        MethodInsnNode w66ById = findExactCall(w66Ctor, Opcodes.INVOKESTATIC, w66Zone, "getZoneById", "(Ljava/lang/Double;)" + w66ZoneDesc);
        boolean w66AnimalCtorStraight = true;
        for (AbstractInsnNode in : methodFromJar(jar, "zombie/iso/areas/DesignationZoneAnimal", "<init>",
                "(Ljava/lang/String;IIIIIZ)V").instructions) {
            if (in instanceof JumpInsnNode) w66AnimalCtorStraight = false;
        }
        failed += check("W66 vanilla 前提：DesignationZone 建構子先 Rand.Next 抽暫時 id、getZoneById 不為 null 就跳過 allZones；DesignationZoneAnimal 建構子無條件加進 designationAnimalZoneList",
                countExactCalls(w66Ctor, Opcodes.INVOKESTATIC, "zombie/core/random/Rand", "Next", "(I)I") == 1
                        && firstCallIndex(w66Ctor, Opcodes.INVOKESTATIC, "zombie/core/random/Rand", "Next", "(I)I")
                                < firstCallIndex(w66Ctor, Opcodes.INVOKESTATIC, w66Zone, "getZoneById", "(Ljava/lang/Double;)" + w66ZoneDesc)
                        && w66ById != null && nextReal(w66ById).getOpcode() == Opcodes.IFNONNULL
                        && w66AnimalCtorStraight);
        MethodNode w66Process = methodFromJar(jar, w66Packet, "processServer", "(L" + w66PacketType + ";Lzombie/core/raknet/UdpConnection;)V");
        failed += check("W66 vanilla 前提：全 jar 恰 3 個 DesignationZone.load（IsoMetaGrid.load、SyncZonePacket.parse、client 的 MetaDataPacket.parse）；processServer 恰 1 個 sendToClients 且在 removeDesignationZone 之前",
                jarWideCallsiteCensus(jar, w66Zone, "load", w66LoadDesc) == 3
                        && countExactCalls(methodFromJar(jar, "zombie/iso/IsoMetaGrid", "load", "(Ljava/nio/ByteBuffer;)V"),
                                Opcodes.INVOKESTATIC, w66Zone, "load", w66LoadDesc) == 1
                        && countExactCalls(w66Parse, Opcodes.INVOKESTATIC, w66Zone, "load", w66LoadDesc) == 1
                        && countExactCalls(methodFromJar(jar, "zombie/network/packets/connection/MetaDataPacket", "parse",
                                "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V"),
                                Opcodes.INVOKESTATIC, w66Zone, "load", w66LoadDesc) == 1
                        && countExactCalls(w66Process, Opcodes.INVOKEVIRTUAL, w66Packet, "sendToClients", w66SendDesc) == 1
                        && firstCallIndex(w66Process, Opcodes.INVOKEVIRTUAL, w66Packet, "sendToClients", w66SendDesc)
                                < firstCallIndex(w66Process, Opcodes.INVOKEVIRTUAL, w66Packet, "removeDesignationZone", "()V"));
        // {class, method, desc, 原呼叫, 改道後}
        String[][] w66Sites = {
                {w66Packet, "parse", "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V",
                        "INVOKESTATIC " + w66Zone + ".load " + w66LoadDesc, "INVOKESTATIC " + w66Log + ".load " + w66LoadDesc},
                {w66Packet, "processServer", "(L" + w66PacketType + ";Lzombie/core/raknet/UdpConnection;)V",
                        "INVOKEVIRTUAL " + w66Packet + ".sendToClients " + w66SendDesc,
                        "INVOKESTATIC " + w66Log + ".sendToClients (L" + w66Packet + ";L" + w66PacketType + ";Lzombie/core/raknet/UdpConnection;)V"},
                {"zombie/iso/IsoGridSquare", "removeGrass", "()Z",
                        // 原呼叫是介面靜態方法（Textifier 標 (itf)），改道後是一般類別的 INVOKESTATIC
                        "INVOKESTATIC " + w66Net + ".sendToRelative " + w66RelDesc + " (itf)", "INVOKESTATIC " + w66Guard + ".sendToRelative " + w66RelDesc},
                {"zombie/iso/IsoMetaGrid", "load", "(Ljava/nio/ByteBuffer;)V",
                        "INVOKESTATIC " + w66Zone + ".load " + w66LoadDesc, "INVOKESTATIC " + w66Guard + ".load " + w66LoadDesc}};
        boolean w66Patched = true;
        for (String[] s : w66Sites) {
            w66Patched &= methodText(methodFromJar(jar, s[0], s[1], s[2])).replace(s[3], s[4])
                    .equals(methodText(method(distJava, s[0], s[1], s[2])));
        }
        failed += check("W66 手術後：parse、processServer、removeGrass×2、IsoMetaGrid.load 同形改道，其餘指令與 frames 保留", w66Patched);
        failed += check("W66 helper 契約：每個原方法在 helper 內恰呼叫 1 次；封包載入經 ZoneSyncGuard.load、不直接呼叫原 load",
                countExactCalls(method(distJava, w66Guard, "sendToRelative", w66RelDesc), Opcodes.INVOKESTATIC, w66Net, "sendToRelative", w66RelDesc) == 1
                        && countExactCalls(method(distJava, w66Guard, "load", w66LoadDesc), Opcodes.INVOKESTATIC, w66Zone, "load", w66LoadDesc) == 1
                        && countExactCalls(method(distJava, w66Log, "load", w66LoadDesc), Opcodes.INVOKESTATIC, w66Guard, "load", w66LoadDesc) == 1
                        && countExactCalls(method(distJava, w66Log, "load", w66LoadDesc), Opcodes.INVOKESTATIC, w66Zone, "load", w66LoadDesc) == 0
                        && countExactCalls(method(distJava, w66Log, "sendToClients",
                                "(L" + w66Packet + ";L" + w66PacketType + ";Lzombie/core/raknet/UdpConnection;)V"),
                                Opcodes.INVOKEVIRTUAL, w66Packet, "sendToClients", w66SendDesc) == 1);

        // ---- W67 安全屋內的畜牧區只有成員能刪（docs/patches.md 2cc；掛在 W66 改道的 processServer.sendToClients 上）----
        // 拒絕靠的是原版 processServer 在轉發之後才讀 zoneAdded：helper 設成 true，原版就不呼叫 removeDesignationZone。
        String w67Guard = "zombie/mdc/ZoneDeleteGuard";
        String w67House = "zombie/iso/areas/SafeHouse";
        int w67ZoneAddedGets = 0, w67GetAt = -1, w67I = 0;
        boolean w67Ifne = false;
        for (AbstractInsnNode in : w66Process.instructions) {
            if (in.getOpcode() < 0) continue;
            w67I++;
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && f.owner.equals(w66Packet) && f.name.equals("zoneAdded")) {
                w67ZoneAddedGets++;
                w67GetAt = w67I;
                w67Ifne = nextReal(in) != null && nextReal(in).getOpcode() == Opcodes.IFNE;
            }
        }
        failed += check("W67 vanilla 前提：processServer 恰一個 zoneAdded 讀取，在 sendToClients 之後、removeDesignationZone 之前，緊接 ifne",
                w67ZoneAddedGets == 1 && w67Ifne
                        && firstCallIndex(w66Process, Opcodes.INVOKEVIRTUAL, w66Packet, "sendToClients", w66SendDesc) < w67GetAt
                        && w67GetAt < firstCallIndex(w66Process, Opcodes.INVOKEVIRTUAL, w66Packet, "removeDesignationZone", "()V"));
        boolean w67Vanilla;
        try {
            MethodNode w67Allowed = methodFromJar(jar, w67House, "playerAllowed", "(Ljava/lang/String;)Z");
            MethodNode w67SetData = methodFromJar(jar, w66Packet, "setData", "([Ljava/lang/Object;)V");
            int w67AddedPuts = 0;
            boolean w67BoolCast = false;
            for (AbstractInsnNode in : w67SetData.instructions) {
                if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("zoneAdded")) w67AddedPuts++;
                if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.CHECKCAST && t.desc.equals("java/lang/Boolean")) w67BoolCast = true;
            }
            methodFromJar(jar, "zombie/network/chat/ChatServer", "sendMessageToServerChat", "(Lzombie/core/raknet/UdpConnection;Ljava/lang/String;)V");
            w67Vanilla = countExactCalls(w67Allowed, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "contains", "(Ljava/lang/Object;)Z") == 1
                    && countExactCalls(w67Allowed, Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z") == 1
                    && w67AddedPuts == 1 && w67BoolCast
                    && countExactCalls(w66Write, Opcodes.INVOKEVIRTUAL, w66Zone, "save", "(Ljava/nio/ByteBuffer;)V") == 1;
        } catch (java.util.NoSuchElementException | NullPointerException e) {
            w67Vanilla = false;
        }
        failed += check("W67 vanilla 前提：SafeHouse.playerAllowed(String) 只看成員名單與屋主；setData 以 values[1] 設 zoneAdded、write 用 DesignationZone.save 送整筆；ChatServer.sendMessageToServerChat(連線, 文字) 存在",
                w67Vanilla);
        MethodNode w67Send = method(distJava, w66Log, "sendToClients",
                "(L" + w66Packet + ";L" + w66PacketType + ";Lzombie/core/raknet/UdpConnection;)V");
        int w67AddedWrites = 0;
        for (AbstractInsnNode in : w67Send.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(w66Packet) && f.name.equals("zoneAdded")) w67AddedWrites++;
        }
        String w67DenialDesc = "(L" + w66Zone + ";Lzombie/core/raknet/UdpConnection;)Ljava/lang/String;";
        String w67RejectDesc = "(L" + w66Zone + ";Lzombie/core/raknet/UdpConnection;)V";
        ClassNode w67GuardClass = classNode(distJava, w67Guard);
        MethodNode w67Reject = method(distJava, w67Guard, "reject", w67RejectDesc);
        int w67Caps = 0;
        for (AbstractInsnNode in : method(distJava, w67Guard, "denial", w67DenialDesc).instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals("zombie/characters/Capability")
                    && f.name.equals("CanSetupSafehouses")) w67Caps++;
        }
        failed += check("W67 helper 契約：轉發前恰一次 denial、拒絕時恰一次寫 zoneAdded 與一次 reject；reject 逐塊 setData＋sendToClient 補送、一次聊天提示；成員判斷只走 playerAllowed、管理員看 CanSetupSafehouses",
                countExactCalls(w67Send, Opcodes.INVOKESTATIC, w67Guard, "denial", w67DenialDesc) == 1
                        && countExactCalls(w67Send, Opcodes.INVOKESTATIC, w67Guard, "reject", w67RejectDesc) == 1
                        && w67AddedWrites == 1
                        && firstCallIndex(w67Send, Opcodes.INVOKESTATIC, w67Guard, "denial", w67DenialDesc)
                                < firstCallIndex(w67Send, Opcodes.INVOKEVIRTUAL, w66Packet, "sendToClients", w66SendDesc)
                        && countExactCalls(w67Reject, Opcodes.INVOKEVIRTUAL, w66Packet, "setData", "([Ljava/lang/Object;)V") == 1
                        && countExactCalls(w67Reject, Opcodes.INVOKEVIRTUAL, w66Packet, "sendToClient",
                                "(L" + w66PacketType + ";Lzombie/network/IConnection;)V") == 1
                        && countExactCalls(w67Reject, Opcodes.INVOKEVIRTUAL, "zombie/network/chat/ChatServer", "sendMessageToServerChat",
                                "(Lzombie/core/raknet/UdpConnection;Ljava/lang/String;)V") == 1
                        && classWideCalls(w67GuardClass, Opcodes.INVOKEVIRTUAL, w67House, "playerAllowed", "(Ljava/lang/String;)Z") == 1
                        && w67Caps == 1);

        // ---- W69 實體 ID 撞號時照常移除（docs/patches.md 2ce；掛在 W35 的 GameEntity ClassPatch）----
        // 存在理由：getEntityNetID 地板一律算序號 0；伺服器 UnregisterEntity 拿到別的實體就丟 Stored entity mismatch、
        // ID 表沒有它就直接 return（不離開 engine）。TIS 修掉任一條時紅＝重估撤刀。
        String w69Mgr = "zombie/entity/GameEntityManager";
        String w69Ge = "zombie/entity/GameEntity";
        String w69Guard = "zombie/entity/MdcEntityIdGuard";
        String w69Desc = "(L" + w69Ge + ";Z)V";
        MethodNode w69Unreg = methodFromJar(jar, w69Mgr, "UnregisterEntity", w69Desc);
        int w69Mismatch = 0;
        boolean w69Throws = false;
        for (AbstractInsnNode in : w69Unreg.instructions) {
            if (in instanceof LdcInsnNode l && "Stored entity mismatch".equals(l.cst)) {
                w69Mismatch++;
                AbstractInsnNode w69Next = nextReal(nextReal(in));
                w69Throws = isCall(nextReal(in), Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V")
                        && w69Next != null && w69Next.getOpcode() == Opcodes.ATHROW;
            }
        }
        failed += check("W69 vanilla 前提：UnregisterEntity(GameEntity,Z) 恰一處 new RuntimeException(\"Stored entity mismatch\") 並 athrow，兩條分支各一次 LongMap.remove",
                w69Mismatch == 1 && w69Throws
                        && countExactCalls(w69Unreg, Opcodes.INVOKEVIRTUAL, "zombie/entity/util/LongMap", "remove", "(J)Ljava/lang/Object;") == 2);
        MethodNode w69NetId = methodFromJar(jar, "zombie/iso/IsoObject", "getEntityNetID", "()J");
        failed += check("W69 vanilla 前提：IsoObject.getEntityNetID 以 isFloor 決定序號（地板一律 0）並經 checkEntityIDChange 換號",
                countExactCalls(w69NetId, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoObject", "isFloor", "()Z") == 1
                        && countExactCalls(w69NetId, Opcodes.INVOKESTATIC, w69Mgr, "checkEntityIDChange", "(L" + w69Ge + ";JJ)V") == 1);
        List<String> w69Callers = jarWideCallers(jar, Opcodes.INVOKESTATIC, w69Mgr, "UnregisterEntity", w69Desc);
        failed += check("W69 呼叫點清冊：全 jar 對 UnregisterEntity(GameEntity,Z) 只有 GameEntity.removeFromWorld(Z) 與單參數多載各 1（實際 " + w69Callers + "）",
                w69Callers.equals(List.of(w69Ge + ".removeFromWorld(Z)V", w69Mgr + ".UnregisterEntity(L" + w69Ge + ";)V")));
        failed += check("W69 手術後：removeFromWorld(Z) 的 UnregisterEntity 同形改道 MdcEntityIdGuard.unregister，其餘指令與 frames 保留",
                methodText(methodFromJar(jar, w69Ge, "removeFromWorld", "(Z)V"))
                        .replace("INVOKESTATIC " + w69Mgr + ".UnregisterEntity " + w69Desc, "INVOKESTATIC " + w69Guard + ".unregister " + w69Desc)
                        .equals(methodText(method(distJava, w69Ge, "removeFromWorld", "(Z)V"))));
        MethodNode w69Helper = method(distJava, w69Guard, "unregister", w69Desc);
        failed += check("W69 helper 契約：unregister 內呼叫原版 UnregisterEntity(GameEntity,Z)（直通與修補兩條路），不呼叫單參數多載、不自己丟例外",
                countExactCalls(w69Helper, Opcodes.INVOKESTATIC, w69Mgr, "UnregisterEntity", w69Desc) == 3
                        && countExactCalls(w69Helper, Opcodes.INVOKESTATIC, w69Mgr, "UnregisterEntity", "(L" + w69Ge + ";)V") == 0
                        && countNew(w69Helper, "java/lang/RuntimeException") == 0);

        // ---- W70 Bandit Raid 劇情無限迴圈（docs/patches.md 2cf）----
        // 存在理由：randomizeDeadSurvivor 唯一 Math.floor 算出 size（d2i／istore s），之後的外層迴圈 iload i／iload s／if_icmpge，
        // 迴圈體內又有 iconst_0／istore i（內層放屍體迴圈重用同一個計數器）＝50–100 間房永遠跑不完。TIS 修掉時紅＝重估撤刀。
        String w70Raid = "zombie/randomizedWorld/randomizedDeadSurvivor/RDSBanditRaid";
        String w70Guard = "zombie/mdc/BanditRaidLoopGuard";
        String w70Desc = "(Lzombie/iso/BuildingDef;)V";
        MethodNode w70Vanilla = methodFromJar(jar, w70Raid, "randomizeDeadSurvivor", w70Desc);
        AbstractInsnNode w70Floor = firstCall(w70Vanilla, Opcodes.INVOKESTATIC, "java/lang/Math", "floor", "(D)D");
        AbstractInsnNode w70D2i = w70Floor == null ? null : nextReal(w70Floor);
        AbstractInsnNode w70Store = w70D2i == null ? null : nextReal(w70D2i);
        int w70SizeSlot = w70D2i != null && w70D2i.getOpcode() == Opcodes.D2I && w70Store instanceof VarInsnNode w70S
                && w70S.getOpcode() == Opcodes.ISTORE ? w70S.var : -1;
        int w70Counter = -1;
        LabelNode w70Exit = null;
        boolean w70Reused = false;
        for (AbstractInsnNode in = w70Store; in != null; in = in.getNext()) {
            if (w70Exit == null && in instanceof VarInsnNode a && a.getOpcode() == Opcodes.ILOAD
                    && nextReal(a) instanceof VarInsnNode b && b.getOpcode() == Opcodes.ILOAD && b.var == w70SizeSlot
                    && nextReal(b) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IF_ICMPGE) {
                w70Counter = a.var;
                w70Exit = j.label;
                in = j;
            } else if (w70Exit != null && in == w70Exit) {
                break;
            } else if (w70Exit != null && in.getOpcode() == Opcodes.ICONST_0
                    && nextReal(in) instanceof VarInsnNode s && s.getOpcode() == Opcodes.ISTORE && s.var == w70Counter) {
                w70Reused = true;
            }
        }
        failed += check("W70 vanilla 前提：randomizeDeadSurvivor 恰一個 Math.floor 算出 size，之後的外層迴圈體內有內層迴圈把同一個計數器歸零（slot "
                        + w70Counter + "）",
                countExactCalls(w70Vanilla, Opcodes.INVOKESTATIC, "java/lang/Math", "floor", "(D)D") == 1
                        && w70SizeSlot >= 0 && w70Counter >= 0 && w70Reused);
        failed += check("W70 手術後：唯一 Math.floor 同形改道 BanditRaidLoopGuard.floor，其餘指令與 frames 保留；RDSBanditRaid 其餘方法不變",
                methodText(w70Vanilla).replace("INVOKESTATIC java/lang/Math.floor (D)D", "INVOKESTATIC " + w70Guard + ".floor (D)D")
                        .equals(methodText(method(distJava, w70Raid, "randomizeDeadSurvivor", w70Desc)))
                        && classNodeFromJar(jar, w70Raid).methods.stream()
                                .filter(m -> !(m.name + m.desc).equals("randomizeDeadSurvivor" + w70Desc))
                                .allMatch(m -> {
                                    try {
                                        return methodText(m).equals(methodText(method(distJava, w70Raid, m.name, m.desc)));
                                    } catch (Exception e) {
                                        return false;
                                    }
                                }));
        failed += check("W70 helper 契約：floor 內恰一次 Math.floor",
                countExactCalls(method(distJava, w70Guard, "floor", "(D)D"), Opcodes.INVOKESTATIC, "java/lang/Math", "floor", "(D)D") == 1);
        failed += checkIsoObjectGate(jar, distJava);
        failed += checkIsoObjectRemoveDefer(jar, distJava);
        failed += checkActionValidation(jar, distJava);
        failed += checkZombieReuseOwner(jar, distJava);
        failed += checkLightSettings(jar, distJava);
        failed += checkPlayerLosFilter(jar, distJava);
        failed += checkVehicleScope(jar, distJava);
        failed += checkWorldMapVisibilityIndex(jar, distJava);
        failed += checkMapFolderGuard(jar, distJava);
        failed += checkPveDeathLog(jar, distJava);
        failed += checkVehicleUpdateCuts(jar, distJava);
        failed += checkAnimalCuts(jar, distJava);
        failed += checkTeleportWatch(jar, distJava);
        failed += checkSteamIpBan(jar, distJava);

        // ---- W74 尋路執行緒觀測（docs/patches.md 2ci）----
        // 存在理由：判讀依賴原版每輪先跑 chunk／格子／載具／請求四個任務佇列（8 個 poll）、再 PathfindNative.update()、
        // 之後最多 2 個請求（iconst_2／istore_2 緊接 update），Sync 週期 50 ms；findPath(PathFindRequest) 是唯一 native
        // 尋路入口，PathRequestTask.execute 是唯一把請求放進優先佇列的地方。任一前提變了，beat 的輪次與重建分組要重新解讀。
        String w74Pkg = "zombie/pathfind/nativeCode/";
        String w74Thread = w74Pkg + "PathfindNativeThread";
        String w74Task = w74Pkg + "PathRequestTask";
        String w74Native = w74Pkg + "PathfindNative";
        String w74Probe = w74Pkg + "MdcPathfindProbe";
        String w74Req = "L" + w74Pkg + "PathFindRequest;";
        String w74Clq = "java/util/concurrent/ConcurrentLinkedQueue";
        String w74FindDesc = "(" + w74Req + "Ljava/nio/ByteBuffer;Z)I";
        String w74AddDesc = "(" + w74Req + "I)V";
        MethodNode w74UpdateVanilla = methodFromJar(jar, w74Thread, "updateThread", "()V");
        MethodNode w74FindVanilla = methodFromJar(jar, w74Thread, "findPath", "(" + w74Req + ")V");
        MethodNode w74ExecVanilla = methodFromJar(jar, w74Task, "execute", "()V");
        AbstractInsnNode w74UpdateCall = firstCall(w74UpdateVanilla, Opcodes.INVOKESTATIC, w74Native, "update", "()V");
        AbstractInsnNode w74Two = w74UpdateCall == null ? null : nextReal(w74UpdateCall);
        AbstractInsnNode w74Store = w74Two == null ? null : nextReal(w74Two);
        failed += check("W74 vanilla 前提：updateThread 恰 8 個 ConcurrentLinkedQueue.poll、1 個 PathfindNative.update 緊接 requestsPerUpdate = 2"
                        + "（iconst_2／istore），每輪恰 1 個 RequestQueue.isEmpty 迴圈",
                countExactCalls(w74UpdateVanilla, Opcodes.INVOKEVIRTUAL, w74Clq, "poll", "()Ljava/lang/Object;") == 8
                        && countExactCalls(w74UpdateVanilla, Opcodes.INVOKESTATIC, w74Native, "update", "()V") == 1
                        && w74Two != null && w74Two.getOpcode() == Opcodes.ICONST_2
                        && w74Store instanceof VarInsnNode w74S && w74S.getOpcode() == Opcodes.ISTORE
                        && countExactCalls(w74UpdateVanilla, Opcodes.INVOKEVIRTUAL, w74Pkg + "RequestQueue", "isEmpty", "()Z") == 1);
        failed += check("W74 vanilla 前提：Sync.endFrame 週期 50,000,000 ns（20 輪/s）",
                countLongConst(methodFromJar(jar, w74Thread + "$Sync", "endFrame", "()V"), 50_000_000L) == 1);
        failed += check("W74 vanilla 前提：findPath(PathFindRequest) 恰 1 個 PathfindNative.findPath；PathRequestTask.execute 恰 1 個 addRequest",
                countExactCalls(w74FindVanilla, Opcodes.INVOKEVIRTUAL, w74Native, "findPath", w74FindDesc) == 1
                        && countExactCalls(w74ExecVanilla, Opcodes.INVOKEVIRTUAL, w74Thread, "addRequest", w74AddDesc) == 1);
        String w74PollVanilla = "INVOKEVIRTUAL " + w74Clq + ".poll ()Ljava/lang/Object;";
        String w81Gate = w74Pkg + "MdcPathfindGate";
        String w74PollGate = "INVOKESTATIC " + w81Gate + ".poll (L" + w74Clq + ";)Ljava/lang/Object;";
        Set<String> w74Touched = Set.of(w74Thread + ".updateThread()V", w74Thread + ".findPath(" + w74Req + ")V",
                w74Task + ".execute()V");
        boolean w74OthersSame = true;
        for (String w74Cls : List.of(w74Thread, w74Task)) {
            for (MethodNode w74M : classNodeFromJar(jar, w74Cls).methods) {
                if (!w74Touched.contains(w74Cls + "." + w74M.name + w74M.desc)) {
                    w74OthersSame &= methodText(w74M).equals(methodText(method(distJava, w74Cls, w74M.name, w74M.desc)));
                }
            }
        }
        failed += check("W74／W81 手術後：updateThread 8 個 poll 與 update 同形改道 MdcPathfindGate，findPath 的 native findPath、"
                        + "execute 的 addRequest 同形改道 MdcPathfindProbe，其餘指令與 frames 保留；兩個 class 其餘方法不變",
                methodText(w74UpdateVanilla).replace(w74PollVanilla, w74PollGate)
                        .replace("INVOKESTATIC " + w74Native + ".update ()V", "INVOKESTATIC " + w81Gate + ".update ()V")
                        .equals(methodText(method(distJava, w74Thread, "updateThread", "()V")))
                        && methodText(w74FindVanilla).replace("INVOKEVIRTUAL " + w74Native + ".findPath " + w74FindDesc,
                                "INVOKESTATIC " + w74Probe + ".findPath (L" + w74Native + ";" + w74FindDesc.substring(1))
                        .equals(methodText(method(distJava, w74Thread, "findPath", "(" + w74Req + ")V")))
                        && methodText(w74ExecVanilla).replace("INVOKEVIRTUAL " + w74Thread + ".addRequest " + w74AddDesc,
                                "INVOKESTATIC " + w74Probe + ".addRequest (L" + w74Thread + ";" + w74AddDesc.substring(1))
                        .equals(methodText(method(distJava, w74Task, "execute", "()V")))
                        && w74OthersSame);
        failed += check("W74 helper 契約：poll／update／addRequest 各恰一次呼叫原版，findPath 的 on／off 兩條路各一次",
                countExactCalls(method(distJava, w74Probe, "poll", "(L" + w74Clq + ";)Ljava/lang/Object;"),
                        Opcodes.INVOKEVIRTUAL, w74Clq, "poll", "()Ljava/lang/Object;") == 1
                        && countExactCalls(method(distJava, w74Probe, "update", "()V"), Opcodes.INVOKESTATIC, w74Native, "update", "()V") == 1
                        && countExactCalls(method(distJava, w74Probe, "findPath", "(L" + w74Native + ";" + w74FindDesc.substring(1)),
                                Opcodes.INVOKEVIRTUAL, w74Native, "findPath", w74FindDesc) == 2
                        && countExactCalls(method(distJava, w74Probe, "addRequest", "(L" + w74Thread + ";" + w74AddDesc.substring(1)),
                                Opcodes.INVOKEVIRTUAL, w74Thread, "addRequest", w74AddDesc) == 1);

        // ---- W81 尋路世界變動批次化閘門（docs/patches.md 2cp）----
        // 存在理由：閘門開的輪次在 helper 裡照原版 chunk 迴圈本體（execute、taskReturnQueue.add）排空、拿掉每輪 10 個
        // ChunkUpdateTask 的上限；關閉時讓 shouldWait 回 false、以 Sync 20 Hz 空轉。任一前提變了，排空與空轉成本要重估。
        AbstractInsnNode[] w81Head = firstReal(w74UpdateVanilla, 2);
        AbstractInsnNode w81Store2 = w74Store;
        failed += check("W81 vanilla 前提：updateThread offset 0 是 bipush 10／istore_1（每輪 10 個 ChunkUpdateTask），"
                        + "chunk 迴圈恰 1 個 instanceof ChunkUpdateTask 與 iinc 1 -1；請求迴圈 iconst_2／istore_2",
                w81Head[0] instanceof IntInsnNode w81B && w81B.getOpcode() == Opcodes.BIPUSH && w81B.operand == 10
                        && w81Head[1] instanceof VarInsnNode w81S1 && w81S1.getOpcode() == Opcodes.ISTORE && w81S1.var == 1
                        && countInsns(w74UpdateVanilla, in -> in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.INSTANCEOF
                                && t.desc.equals(w74Pkg + "ChunkUpdateTask")) == 1
                        && countInsns(w74UpdateVanilla, in -> in instanceof org.objectweb.asm.tree.IincInsnNode ii
                                && ii.var == 1 && ii.incr == -1) == 1
                        && w81Store2 instanceof VarInsnNode w81S2 && w81S2.var == 2);
        MethodNode w81SyncInit = methodFromJar(jar, w74Thread + "$Sync", "<init>", "()V");
        AbstractInsnNode w81Period = null;
        for (AbstractInsnNode in = w81SyncInit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("period")) {
                w81Period = prevReal(in);
            }
        }
        failed += check("W81 vanilla 前提：PathfindNativeThread$Sync.period 仍是 50000000L（閘門關閉時空轉 20 Hz）",
                w81Period instanceof LdcInsnNode w81L && Long.valueOf(50_000_000L).equals(w81L.cst));
        failed += check("W81 vanilla 前提：shouldWait 只看 stop、四個任務佇列與 RequestQueue（閘門關閉而佇列非空時不睡）",
                countExactCalls(methodFromJar(jar, w74Thread, "shouldWait", "()Z"), Opcodes.INVOKEVIRTUAL, w74Clq, "isEmpty", "()Z") == 4);
        String w81PollDesc = "(L" + w74Clq + ";)Ljava/lang/Object;";
        MethodNode w81Poll = method(distJava, w81Gate, "poll", w81PollDesc);
        MethodNode w81Update = method(distJava, w81Gate, "update", "()V");
        failed += check("W81 helper 契約：gate.poll 只經 MdcPathfindProbe.poll 取任務（不直接 CLQ.poll）、排空恰 1 個 execute＋1 個 add，"
                        + "沒有 catch；gate.update 恰 1 次 MdcPathfindProbe.update、不直接呼叫 native update",
                countExactCalls(w81Poll, Opcodes.INVOKEVIRTUAL, w74Clq, "poll", "()Ljava/lang/Object;") == 0
                        && countExactCalls(w81Poll, Opcodes.INVOKESTATIC, w74Probe, "poll", w81PollDesc) == 4
                        && countExactCalls(w81Poll, Opcodes.INVOKEINTERFACE, w74Pkg + "IPathfindTask", "execute", "()V") == 1
                        && countExactCalls(w81Poll, Opcodes.INVOKEVIRTUAL, w74Clq, "add", "(Ljava/lang/Object;)Z") == 1
                        && w81Poll.tryCatchBlocks.isEmpty()
                        && countExactCalls(w81Update, Opcodes.INVOKESTATIC, w74Probe, "update", "()V") == 1
                        && countExactCalls(w81Update, Opcodes.INVOKESTATIC, w74Native, "update", "()V") == 0);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("守衛語意驗證全數通過");
    }

    /**
     * Client patch 驗證。相容版（client-compat，PatchConfig.clientCompat）只含 IsoTree、BaseVehicle、VehiclePacket 的修補，
     * 所以只跑樹與車輛那幾段；PZ_Optimization 也換的 class（貼圖、WorldStreamer、AnimationPlayer、IsoChunk 等）只在完整版驗。
     */
    static int clientChecks(Path distJava, Path jar, boolean lowmem, boolean compat) throws Exception {
        int failed = compat ? 0 : textureAndStreamChecks(distJava, jar, lowmem);
        failed += check("PatchInfo 版本指紋已生成且四個常數非空（client）",
                patchInfoOk(distJava, "client"));
        failed += treeAndVehicleChecks(distJava, jar, compat);
        if (!compat) {
            failed += metaGridLockChecks(distJava, jar);
            failed += bloodSplatChecks(distJava, jar);
        }
        return failed;
    }

    /**
     * TextureIDAssetManager.waitFileTask 門檻＋觀測改道、貼圖洩漏根治、chunk 串流觀測（完整版）：
     * vanilla 前提守門（jar 內恰一個 getBytesAllocated＋恰一個 52428800L，PZ 改寫此方法
     * 時建置失敗而非默默錯位）、patched 全序鎖、helper 常數與 bytecode 常數連動、
     * 以及 helper passthrough 對真實 DirectBufferAllocator 水位的行為 smoke。
     */
    static int textureAndStreamChecks(Path distJava, Path jar, boolean lowmem) throws Exception {
        int failed = 0;
        String texCls = "zombie/core/textures/TextureIDAssetManager";
        String guardCls = "zombie/mdc/TexturePipelineGuard";
        String dba = "zombie/core/utils/DirectBufferAllocator";
        // variant 分流（顯式 mode，與 Patcher 的 client/client-lowmem 同源）：
        // lowmem＝不做 constChange（門檻維持 vanilla 50MB）＋redirect 指向 LowMem 入口
        // （effective 門檻烘進 helper，橫幅/stall 分類以實際生效值計）。
        String observedName = lowmem ? "bytesAllocatedObservedLowMem" : "bytesAllocatedObserved";
        long effectiveConst = lowmem ? 52428800L : 4294967296L;

        MethodNode vanillaWait = methodFromJar(jar, texCls, "waitFileTask", "()V");
        failed += check("vanilla 前提：waitFileTask 恰一個 getBytesAllocated 與 52428800L",
                countExactCalls(vanillaWait, Opcodes.INVOKESTATIC, dba, "getBytesAllocated", "()J") == 1
                && countLongConst(vanillaWait, 52428800L) == 1
                && countLongConst(vanillaWait, 4294967296L) == 0);

        MethodNode wait = method(distJava, texCls, "waitFileTask", "()V");
        failed += check("觀測改道恰一次（" + observedName + "）且原 getBytesAllocated 歸零",
                countExactCalls(wait, Opcodes.INVOKESTATIC, guardCls, observedName, "()J") == 1
                && countExactCalls(wait, Opcodes.INVOKESTATIC, dba, "getBytesAllocated", "()J") == 0);
        failed += check(lowmem ? "lowmem：門檻維持 50MB 且無 4GB" : "門檻常數已改 4GB 且 50MB 歸零",
                countLongConst(wait, effectiveConst) == 1
                && countLongConst(wait, lowmem ? 4294967296L : 52428800L) == 0);

        AbstractInsnNode[] w = firstReal(wait, 4);
        boolean seq = w[0] instanceof MethodInsnNode m0 && m0.getOpcode() == Opcodes.INVOKESTATIC
                && m0.owner.equals(guardCls) && m0.name.equals(observedName) && m0.desc.equals("()J")
                && w[1] instanceof LdcInsnNode l1 && l1.cst instanceof Long lv && lv == effectiveConst
                && w[2] != null && w[2].getOpcode() == Opcodes.LCMP
                && w[3] != null && w[3].getOpcode() == Opcodes.IFLE;
        failed += check("waitFileTask 全序鎖（observed→effective 門檻→lcmp→ifle）", seq);
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

        // ---- v3.0 chunk 串流觀測（WorldStreamer 四 headCall；42.20.3 起含 ChunkNotReady）----
        String wsCls = "zombie/iso/WorldStreamer";
        String csoCls = "zombie/mdc/ChunkStreamObserver";
        String csoDesc = "(Lzombie/iso/WorldStreamer;)V";
        String bbrDesc = "(Lzombie/core/network/ByteBufferReader;)V";
        MethodNode vUm = methodFromJar(jar, wsCls, "updateMain", "()V");
        failed += check("vanilla 前提：updateMain 錨定（觸碰 GameClient.connection）且無既存 observer 呼叫",
                countFieldTouches(vUm, "zombie/network/GameClient", "connection") >= 1
                && countExactCalls(vUm, Opcodes.INVOKESTATIC, csoCls, "onUpdateMain", csoDesc) == 0);
        MethodNode pUm = method(distJava, wsCls, "updateMain", "()V");
        MethodNode pRcp = method(distJava, wsCls, "receiveChunkPart", bbrDesc);
        MethodNode pRnr = method(distJava, wsCls, "receiveNotRequired", bbrDesc);
        MethodNode pRnrd = method(distJava, wsCls, "receiveChunkNotReady", "(I)V");
        failed += check("ChunkStream 四個 head-call 全序（aload_0→helper 恰一次）",
                headCallOk(pUm, csoCls, "onUpdateMain", csoDesc)
                && headCallOk(pRcp, csoCls, "onReceiveChunkPart", csoDesc)
                && headCallOk(pRnr, csoCls, "onReceiveNotRequired", csoDesc)
                && headCallOk(pRnrd, csoCls, "onReceiveChunkNotReady", csoDesc));
        MethodNode vRnrd = methodFromJar(jar, wsCls, "receiveChunkNotReady", "(I)V");
        failed += check("vanilla 前提：receiveChunkNotReady 存在（42.20.3 新協定）且無既存 observer 呼叫",
                vRnrd != null
                && countExactCalls(vRnrd, Opcodes.INVOKESTATIC, csoCls, "onReceiveChunkNotReady", csoDesc) == 0);
        // 42.21 起 sentRequests→pendingRequests 的 drain 移到 udpUpdate()，receive 兩方法觸碰
        // sentRequests=0（原釘退化成 0==0）——改釘兩方法都仍在配對的 pendingRequests。
        MethodNode vRcp = methodFromJar(jar, wsCls, "receiveChunkPart", bbrDesc);
        MethodNode vRnr = methodFromJar(jar, wsCls, "receiveNotRequired", bbrDesc);
        failed += check("receiveChunkPart/receiveNotRequired 原體保留（pendingRequests 觸碰數未變且非零）",
                countFieldTouches(vRcp, wsCls, "pendingRequests") > 0
                && countFieldTouches(pRcp, wsCls, "pendingRequests")
                        == countFieldTouches(vRcp, wsCls, "pendingRequests")
                && countFieldTouches(vRnr, wsCls, "pendingRequests") > 0
                && countFieldTouches(pRnr, wsCls, "pendingRequests")
                        == countFieldTouches(vRnr, wsCls, "pendingRequests"));
        // sendGate 標示的前提：42.21 sendRequests 頭部無條件 pendingRequests1.size() <= 20 才送
        //（getfield pendingRequests1 → size → bipush 20 → if_icmple），helper 門檻常數連動
        MethodNode vSend = methodFromJar(jar, wsCls, "sendRequests", "()V");
        boolean gateOk = false;
        for (AbstractInsnNode in : vSend.instructions) {
            if (in instanceof IntInsnNode push && push.getOpcode() == Opcodes.BIPUSH && push.operand == 20) {
                AbstractInsnNode size = prevReal(push);
                AbstractInsnNode owner = size == null ? null : prevReal(size);
                AbstractInsnNode cmp = nextReal(push);
                gateOk = size instanceof MethodInsnNode mi && mi.name.equals("size")
                        && owner instanceof FieldInsnNode fi && fi.owner.equals(wsCls)
                        && fi.name.equals("pendingRequests1")
                        && cmp != null && cmp.getOpcode() == Opcodes.IF_ICMPLE;
            }
        }
        ClassNode pCso = classNode(distJava, csoCls);
        boolean helperGate = pCso.fields.stream().anyMatch(f -> f.name.equals("SEND_GATE_PENDING1")
                && f.value instanceof Integer v && v == 20);
        failed += check("vanilla 前提：sendRequests 停送 gate＝pendingRequests1.size()<=20（恰一個 20）且 helper 常數連動",
                gateOk && countIntConst(vSend, 20) == 1 && helperGate);
        // helper 反射依賴的六個私有欄位契約：名稱＋descriptor 逐一鎖進建置期
        //（漂移時 helper 會 fail-quiet 降級僅計數——這道守門把「默默降級」變成建置失敗）。
        // 42.21 刪除 requestingLargeArea／largeAreaDownloads，helper 同步不再反射。
        ClassNode vWs = classNodeFromJar(jar, wsCls);
        failed += check("ChunkStream 反射欄位契約（6 欄位名稱＋型別）",
                hasField(vWs, "pendingRequests", "Ljava/util/ArrayList;")
                && hasField(vWs, "pendingRequests1", "Ljava/util/ArrayList;")
                && hasField(vWs, "chunkRequests0", "Ljava/util/concurrent/ConcurrentLinkedQueue;")
                && hasField(vWs, "chunkRequests1", "Ljava/util/ArrayList;")
                && hasField(vWs, "sentRequests", "Ljava/util/concurrent/ConcurrentLinkedQueue;")
                && hasField(vWs, "requestNumber", "I"));

        return failed;
    }

    /** 自建房間 XL 樹與車輛（W54、W61–W63、W65）：完整版與相容版都有；W55、W57 只在完整版。 */
    static int treeAndVehicleChecks(Path distJava, Path jar, boolean compat) throws Exception {
        int failed = 0;
        // ---- 42.21.0 自建房間 XL 樹例外（docs/patches.md 2bl）----
        // 存在理由兩條：isPlayerInsideARoom 對 getRoom() 的結果不驗 null（TIS 補上檢查時紅＝撤刀），
        // 以及 IsoGridSquare.isInARoom() 仍含 IsoRegions isPlayerRoom 分支（格子沒有 IsoRoom 也回 true）。
        String treeCls = "zombie/iso/objects/IsoTree";
        String playerCls = "zombie/characters/IsoPlayer";
        String squareCls = "zombie/iso/IsoGridSquare";
        String playerArgDesc = "(L" + playerCls + ";)Z";
        String treeRenderDesc = "(FFFLzombie/core/textures/ColorInfo;ZZLzombie/core/opengl/Shader;)V";
        MethodNode vInside = methodFromJar(jar, treeCls, "isPlayerInsideARoom", playerArgDesc);
        boolean unguardedRoom = false;
        for (AbstractInsnNode in : vInside.instructions) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(squareCls) && mi.name.equals("getRoom")) {
                unguardedRoom = nextReal(in) instanceof MethodInsnNode next
                        && next.owner.equals("zombie/iso/areas/IsoRoom") && next.name.equals("getRectsBounds");
            }
        }
        failed += check("自建房間 XL 樹 vanilla 前提：isPlayerInsideARoom 恰一個 isInARoom，getRoom 後直接 getRectsBounds",
                countExactCalls(vInside, Opcodes.INVOKEVIRTUAL, playerCls, "isInARoom", "()Z") == 1
                && countExactCalls(vInside, Opcodes.INVOKEVIRTUAL, squareCls, "getRoom",
                        "()Lzombie/iso/areas/IsoRoom;") == 1
                && unguardedRoom);
        failed += check("自建房間 XL 樹 vanilla 前提：IsoGridSquare.isInARoom 含 IsoRegions isPlayerRoom 分支",
                countExactCalls(methodFromJar(jar, squareCls, "isInARoom", "()Z"), Opcodes.INVOKEINTERFACE,
                        "zombie/iso/areas/isoregion/regions/IWorldRegion", "isPlayerRoom", "()Z") == 1);
        MethodNode pInside = method(distJava, treeCls, "isPlayerInsideARoom", playerArgDesc);
        AbstractInsnNode[] insideHead = firstReal(pInside, 3);
        failed += check("自建房間 XL 樹改道：aload_1→TreeRoomGuard.isInARoom→ifne，原呼叫歸零、真指令數不變",
                insideHead[0] instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1
                && insideHead[1] instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("zombie/mdc/TreeRoomGuard") && call.name.equals("isInARoom")
                        && call.desc.equals("(L" + playerCls + ";)Z")
                && insideHead[2] != null && insideHead[2].getOpcode() == Opcodes.IFNE
                && countExactCalls(pInside, Opcodes.INVOKEVIRTUAL, playerCls, "isInARoom", "()Z") == 0
                && realInsnCount(pInside) == realInsnCount(vInside));
        failed += check("自建房間 XL 樹負對照：isPlayerCloseToARoom 與 render 未改動",
                methodText(method(distJava, treeCls, "isPlayerCloseToARoom", playerArgDesc))
                        .equals(methodText(methodFromJar(jar, treeCls, "isPlayerCloseToARoom", playerArgDesc)))
                && methodText(method(distJava, treeCls, "render", treeRenderDesc))
                        .equals(methodText(methodFromJar(jar, treeCls, "render", treeRenderDesc))));

        // ---- W54 車輛靜止姿勢跳過（docs/patches.md 2br）----
        // 前提：updateAnimationPlayer 只由 postupdate 呼叫（2 次，車身＋models 迴圈），方法內恰一個 Update 且在
        // getModelInfoForPart（窗時間、playPartAnim 的善後）之前；沒在播放的 track 時間不前進；renderer 只在
        // SkinTransformData.dirty 時才重算蒙皮矩陣（不跑 Update 就沿用上一次）。
        String vehCls = "zombie/vehicles/BaseVehicle";
        String apCls = "zombie/core/skinnedmodel/animation/AnimationPlayer";
        String trackCls = "zombie/core/skinnedmodel/animation/AnimationTrack";
        String uapDesc = "(L" + apCls + ";Lzombie/vehicles/VehiclePart;)V";
        String gateCls = "zombie/mdc/VehicleAnimGate";
        MethodNode vUap = methodFromJar(jar, vehCls, "updateAnimationPlayer", uapDesc);
        ClassNode vVeh = classNodeFromJar(jar, vehCls);
        int uapCallers = 0;
        int uapCallersInPostupdate = 0;
        for (MethodNode m : vVeh.methods) {
            int n = countExactCalls(m, Opcodes.INVOKEVIRTUAL, vehCls, "updateAnimationPlayer", uapDesc);
            uapCallers += n;
            if (m.name.equals("postupdate") && m.desc.equals("()V")) {
                uapCallersInPostupdate += n;
            }
        }
        failed += check("W54 vanilla 前提：updateAnimationPlayer 只在 postupdate 被呼叫 2 次（車身＋models 迴圈）",
                uapCallers == 2 && uapCallersInPostupdate == 2 && (vUap.access & Opcodes.ACC_PRIVATE) != 0);
        failed += check("W54 vanilla 前提：updateAnimationPlayer 恰一個 Update(F)V，且在 getModelInfoForPart 之前",
                countExactCalls(vUap, Opcodes.INVOKEVIRTUAL, apCls, "Update", "(F)V") == 1
                && firstCallIndex(vUap, Opcodes.INVOKEVIRTUAL, apCls, "Update", "(F)V")
                        < firstCallIndex(vUap, Opcodes.INVOKEVIRTUAL, vehCls, "getModelInfoForPart",
                                "(Lzombie/vehicles/VehiclePart;)Lzombie/vehicles/BaseVehicle$ModelInfo;"));
        failed += check("W54 vanilla 前提：tickCurrentTimeInternal 讀 isPlaying（沒在播放時間不前進）、"
                        + "getSkinTransforms 只在 dirty 時重算",
                countFieldTouches(methodFromJar(jar, trackCls, "tickCurrentTimeInternal", "(F)V"),
                        trackCls, "isPlaying") == 1
                && countFieldTouches(methodFromJar(jar, apCls, "getSkinTransforms",
                        "(Lzombie/core/skinnedmodel/model/SkinningData;)[Lorg/lwjgl/util/vector/Matrix4f;"),
                        apCls + "$SkinTransformData", "dirty") == 2);
        MethodNode pUap = method(distJava, vehCls, "updateAnimationPlayer", uapDesc);
        failed += check("W54 updateAnimationPlayer 唯一 Update 同形改道 VehicleAnimGate.update，其餘指令與 frames 保留",
                countExactCalls(pUap, Opcodes.INVOKEVIRTUAL, apCls, "Update", "(F)V") == 0
                && countExactCalls(pUap, Opcodes.INVOKESTATIC, gateCls, "update", "(L" + apCls + ";F)V") == 1
                && methodText(vUap).replace("INVOKEVIRTUAL " + apCls + ".Update (F)V",
                        "INVOKESTATIC " + gateCls + ".update (L" + apCls + ";F)V").equals(methodText(pUap)));
        failed += check("W54 負對照：postupdate 與 playPartAnim 未改動",
                methodText(method(distJava, vehCls, "postupdate", "()V"))
                        .equals(methodText(methodFromJar(jar, vehCls, "postupdate", "()V")))
                && methodText(method(distJava, vehCls, "playPartAnim", "(Lzombie/vehicles/VehiclePart;Ljava/lang/String;)V"))
                        .equals(methodText(methodFromJar(jar, vehCls, "playPartAnim",
                                "(Lzombie/vehicles/VehiclePart;Ljava/lang/String;)V"))));

        if (!compat) {
            failed += boneReparentChecks(distJava, jar);
            failed += staleRoomChecks(distJava, jar);
        }
        String chunkCls = "zombie/iso/IsoChunk";

        // ---- W61 車輛被夾回過期 chunk 的瞬移（docs/patches.md 2by）----
        // 存在理由：update() 在 sq == null 時只看 this.chunk.refs 非空就夾回（不看 loaded、不看車在不在它的清單、不看距離）；
        // chunk 卸載與 resetForStore 回收都不解除車上的 chunk，LoadChunk 從 chunkStore 取回物件後先 refs.add 才排入載入。
        // TIS 補上任一檢查、或在卸載時解除車的參照＝轉紅、重估撤刀。helper 的 EDGE 與原版夾回常數連動。
        String clampCls = "zombie/mdc/VehicleClampGuard";
        String transformCls = "zombie/core/physics/Transform";
        String stwDesc = "(L" + transformCls + ";)V";
        MethodNode vVehUpdate = methodFromJar(jar, vehCls, "update", "()V");
        List<AbstractInsnNode> vehUpdateReal = new ArrayList<>();
        for (AbstractInsnNode in : vVehUpdate.instructions) {
            if (in.getOpcode() >= 0) {
                vehUpdateReal.add(in);
            }
        }
        int clampFrom = -1;
        int clampTo = -1;
        for (int i = 0; i < vehUpdateReal.size() && clampTo < 0; i++) {
            AbstractInsnNode in = vehUpdateReal.get(i);
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD && fi.owner.equals(chunkCls)
                    && fi.name.equals("refs")) {
                clampFrom = i;
            } else if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && mi.owner.equals(vehCls) && mi.name.equals("setWorldTransform") && mi.desc.equals(stwDesc)) {
                clampTo = i;
            }
        }
        boolean clampOnlyRefs = clampFrom >= 3 && clampTo > clampFrom + 2
                && vehUpdateReal.get(clampFrom - 3).getOpcode() == Opcodes.IFNONNULL
                && vehUpdateReal.get(clampFrom - 2) instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0
                && vehUpdateReal.get(clampFrom - 1) instanceof FieldInsnNode own && own.owner.equals(vehCls) && own.name.equals("chunk")
                && vehUpdateReal.get(clampFrom + 1) instanceof MethodInsnNode empty && empty.owner.equals("java/util/ArrayList")
                        && empty.name.equals("isEmpty")
                && vehUpdateReal.get(clampFrom + 2).getOpcode() == Opcodes.IFNE;
        int clampEdges = 0;
        int clampChunkBounds = 0;
        int clampExtraGuards = 0;
        for (int i = clampFrom + 3; clampOnlyRefs && i < clampTo; i++) {
            AbstractInsnNode in = vehUpdateReal.get(i);
            if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Float edge && edge == 5.0E-4F) {
                clampEdges++;
            } else if (in instanceof FieldInsnNode fi && fi.owner.equals(chunkCls)) {
                if (fi.name.equals("wx") || fi.name.equals("wy")) {
                    clampChunkBounds++;
                } else {
                    clampExtraGuards++;
                }
            } else if (in instanceof MethodInsnNode mi && mi.owner.equals("java/util/ArrayList")) {
                clampExtraGuards++;
            }
        }
        failed += check("W61 vanilla 前提：update 恰一個 setWorldTransform，在 sq==null 後只看 chunk.refs.isEmpty 的夾回區塊內，"
                        + "夾法只讀 wx、wy 與 5.0E-4f（不讀 loaded、vehicles），且與 helper EDGE 連動",
                countExactCalls(vVehUpdate, Opcodes.INVOKEVIRTUAL, vehCls, "setWorldTransform", stwDesc) == 1
                && clampOnlyRefs && clampChunkBounds == 2 && clampExtraGuards == 0 && clampEdges >= 4
                && classNode(distJava, clampCls).fields.stream().anyMatch(f -> f.name.equals("EDGE")
                        && f.value instanceof Float v && v == 5.0E-4F));
        boolean resetClearsVehicles = false;
        boolean resetClearsRefs = false;
        for (AbstractInsnNode in : methodFromJar(jar, chunkCls, "resetForStore", "()V").instructions) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD && fi.owner.equals(chunkCls)
                    && nextReal(in) instanceof MethodInsnNode clear && clear.owner.equals("java/util/ArrayList")
                    && clear.name.equals("clear")) {
                resetClearsVehicles |= fi.name.equals("vehicles");
                resetClearsRefs |= fi.name.equals("refs");
            }
        }
        int vehicleChunkWrites = 0;
        boolean onlyRepointsToSelf = true;
        for (String owner : List.of(chunkCls, "zombie/iso/IsoChunkMap")) {
            for (MethodNode m : classNodeFromJar(jar, owner).methods) {
                for (AbstractInsnNode in : m.instructions) {
                    if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD && fi.owner.equals(vehCls)
                            && fi.name.equals("chunk")) {
                        vehicleChunkWrites++;
                        onlyRepointsToSelf &= owner.equals(chunkCls) && m.name.equals("update")
                                && prevReal(in) instanceof VarInsnNode value && value.getOpcode() == Opcodes.ALOAD && value.var == 0;
                    }
                }
            }
        }
        MethodNode vLoadChunk = methodFromJar(jar, "zombie/iso/IsoChunkMap", "LoadChunk", "(IIII)V");
        int loadAddJob = firstCallIndex(vLoadChunk, Opcodes.INVOKEVIRTUAL, "zombie/iso/WorldStreamer", "addJob",
                "(L" + chunkCls + ";IIZ)V");
        int loadLastRefsAdd = Integer.MAX_VALUE;
        int loadIndex = 0;
        for (AbstractInsnNode in : vLoadChunk.instructions) {
            if (in.getOpcode() >= 0) {
                loadIndex++;
                if (in instanceof MethodInsnNode add && add.owner.equals("java/util/ArrayList") && add.name.equals("add")
                        && prevReal(in) instanceof VarInsnNode && prevReal(prevReal(in)) instanceof FieldInsnNode refs
                        && refs.owner.equals(chunkCls) && refs.name.equals("refs") && loadIndex < loadAddJob) {
                    loadLastRefsAdd = loadIndex;
                }
            }
        }
        failed += check("W61 vanilla 前提：resetForStore 清空 vehicles 與 refs；IsoChunk／IsoChunkMap 只在 IsoChunk.update 把 vehicle.chunk "
                        + "指回自己（卸載、回收都不解除）；LoadChunk 從 chunkStore 取物件、先 refs.add 再 WorldStreamer.addJob",
                resetClearsVehicles && resetClearsRefs && vehicleChunkWrites == 1 && onlyRepointsToSelf
                && countFieldTouches(vLoadChunk, "zombie/iso/IsoChunkMap", "chunkStore") >= 1
                && loadAddJob != Integer.MAX_VALUE && loadLastRefsAdd < loadAddJob);
        // 規則 3（相鄰都沒載入就不放車）依賴原版自己停掉物理：每個物理子步對每台車 checkSurroundingChunks，
        // hasChunksAllAround 以 IsoCell.getChunk＋loaded 查周圍 3×3，不齊就設旗標並 setPhysicsActive；setPhysicsActive 讀旗標強制 false。
        String freezeFlag = "disableSimulationDueToLackOfSurroundingChunks";
        MethodNode vSurround = methodFromJar(jar, vehCls, "checkSurroundingChunks", "()V");
        MethodNode vAllAround = methodFromJar(jar, vehCls, "hasChunksAllAround", "()Z");
        failed += check("W61 vanilla 前提：WorldSimulation.updateVehiclePhysics 每子步呼叫 checkSurroundingChunks；周圍 3×3 有未載入的 chunk 就停掉車的物理",
                countExactCalls(methodFromJar(jar, "zombie/core/physics/WorldSimulation", "updateVehiclePhysics", "()V"),
                        Opcodes.INVOKEVIRTUAL, vehCls, "checkSurroundingChunks", "()V") == 1
                && countExactCalls(vAllAround, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "getChunk", "(II)L" + chunkCls + ";") == 1
                && countFieldTouches(vAllAround, chunkCls, "loaded") == 1
                && countExactCalls(vSurround, Opcodes.INVOKEVIRTUAL, vehCls, "hasChunksAllAround", "()Z") == 1
                && countFieldTouches(vSurround, vehCls, freezeFlag) >= 2
                && countExactCalls(vSurround, Opcodes.INVOKEVIRTUAL, vehCls, "setPhysicsActive", "(Z)V") >= 1
                && countFieldTouches(methodFromJar(jar, vehCls, "setPhysicsActive", "(ZZ)V"), vehCls, freezeFlag) == 1);
        String clampDesc = "(L" + vehCls + ";L" + transformCls + ";)V";
        String orphanCls = "zombie/mdc/VehicleOrphanGuard";
        String listContainsDesc = "(Ljava/lang/Object;)Z";
        String orphanContainsDesc = "(Ljava/util/ArrayList;Ljava/lang/Object;)Z";
        MethodNode pVehUpdate = method(distJava, vehCls, "update", "()V");
        // W65 存在理由：update 在從物理回寫 x、y 並設好樓層 z（最後一個 setZ）、夾回區塊之後，唯一一次呼叫 updateTransform；
        // 整個方法只用物理高度推算樓層，不把物理高度放回地板以上（唯一的 setWorldTransform 在 sq==null 的夾回區塊）。
        // TIS 在這段補上地板夾回＝重估撤刀。helper 放在 zombie/vehicles 才能呼叫 protected 的 updateTransform。
        String sinkCls = "zombie/vehicles/MdcVehicleSinkGuard";
        String sinkDesc = "(L" + vehCls + ";)V";
        int vUpdateTransformAt = firstCallIndex(vVehUpdate, Opcodes.INVOKEVIRTUAL, vehCls, "updateTransform", "()V");
        failed += check("W65 vanilla 前提：update 恰一個 updateTransform，在最後一個 setZ 與夾回的 setWorldTransform 之後；"
                        + "updateTransform 是 protected",
                countExactCalls(vVehUpdate, Opcodes.INVOKEVIRTUAL, vehCls, "updateTransform", "()V") == 1
                && lastCallIndex(vVehUpdate, Opcodes.INVOKEVIRTUAL, vehCls, "setZ", "(F)F") < vUpdateTransformAt
                && firstCallIndex(vVehUpdate, Opcodes.INVOKEVIRTUAL, vehCls, "setWorldTransform", stwDesc) < vUpdateTransformAt
                && (methodFromJar(jar, vehCls, "updateTransform", "()V").access & Opcodes.ACC_PROTECTED) != 0);
        failed += check("W61／W62／W65 update：setWorldTransform 1 個、ArrayList.contains 4 個、updateTransform 1 個同形改道，其餘指令與 frames 保留",
                countExactCalls(pVehUpdate, Opcodes.INVOKEVIRTUAL, vehCls, "setWorldTransform", stwDesc) == 0
                && countExactCalls(pVehUpdate, Opcodes.INVOKESTATIC, clampCls, "setWorldTransform", clampDesc) == 1
                && countExactCalls(vVehUpdate, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "contains", listContainsDesc) == 4
                && countExactCalls(pVehUpdate, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "contains", listContainsDesc) == 0
                && countExactCalls(pVehUpdate, Opcodes.INVOKESTATIC, orphanCls, "contains", orphanContainsDesc) == 4
                && countExactCalls(pVehUpdate, Opcodes.INVOKEVIRTUAL, vehCls, "updateTransform", "()V") == 0
                && countExactCalls(pVehUpdate, Opcodes.INVOKESTATIC, sinkCls, "updateTransform", sinkDesc) == 1
                && methodText(vVehUpdate)
                        .replace("INVOKEVIRTUAL " + vehCls + ".setWorldTransform " + stwDesc,
                                "INVOKESTATIC " + clampCls + ".setWorldTransform " + clampDesc)
                        .replace("INVOKEVIRTUAL java/util/ArrayList.contains " + listContainsDesc,
                                "INVOKESTATIC " + orphanCls + ".contains " + orphanContainsDesc)
                        .replace("INVOKEVIRTUAL " + vehCls + ".updateTransform ()V",
                                "INVOKESTATIC " + sinkCls + ".updateTransform " + sinkDesc)
                        .equals(methodText(pVehUpdate)));
        failed += check("W61 負對照：setWorldTransform 本體與 flipUpright（另一個呼叫點）未改動",
                methodText(method(distJava, vehCls, "setWorldTransform", stwDesc))
                        .equals(methodText(methodFromJar(jar, vehCls, "setWorldTransform", stwDesc)))
                && methodText(method(distJava, vehCls, "flipUpright", "()V"))
                        .equals(methodText(methodFromJar(jar, vehCls, "flipUpright", "()V"))));

        // ---- W62 車掛在已卸載 chunk 上時 update 每幀提早結束（docs/patches.md 2bz）----
        // 存在理由：update 開頭第一個 ArrayList.contains 是 this.chunk.vehicles.contains(this)；不含時（chunk 物件已回收）
        // 客戶端送 VehicleRequest(2) 後跳到續行點，含時若 !server 且 chunk.refs 空就 removeFromWorld 後 RETURN。
        // removeFromWorld 遇到本機乘客在 RETURN 之前就離開（不設 removedFromWorld），所以整個 update 每幀被跳過。
        // 另 3 個 contains（animals 一個、assert 兩個）位置釘住，helper 對它們回傳原值。TIS 改寫這段＝轉紅、重估撤刀。
        int headContains = -1;
        for (int i = 0; i < vehUpdateReal.size() && headContains < 0; i++) {
            if (isCall(vehUpdateReal.get(i), Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "contains", listContainsDesc)) {
                headContains = i;
            }
        }
        String chunkDesc = "L" + chunkCls + ";";
        boolean orphanHead = headContains >= 4 && headContains + 20 < vehUpdateReal.size();
        if (orphanHead) {
            AbstractInsnNode[] h = new AbstractInsnNode[25];
            for (int i = 0; i < h.length; i++) {
                h[i] = vehUpdateReal.get(headContains - 4 + i);
            }
            // h[4] 是 contains；h[24] 是續行點（分支全部跳到這裡）
            AbstractInsnNode resume = h[24];
            orphanHead = isVar(h[0], Opcodes.ALOAD, 0)
                    && isField(h[1], Opcodes.GETFIELD, vehCls, "chunk", chunkDesc)
                    && isField(h[2], Opcodes.GETFIELD, chunkCls, "vehicles", "Ljava/util/ArrayList;")
                    && isVar(h[3], Opcodes.ALOAD, 0)
                    && h[5] instanceof JumpInsnNode notListed && notListed.getOpcode() == Opcodes.IFNE
                    && isField(h[6], Opcodes.GETSTATIC, "zombie/network/GameClient", "client", "Z")
                    && h[7] instanceof JumpInsnNode notClient && notClient.getOpcode() == Opcodes.IFEQ
                    && nextReal(notClient.label) == resume
                    && isField(h[8], Opcodes.GETSTATIC, "zombie/vehicles/VehicleManager", "instance", "Lzombie/vehicles/VehicleManager;")
                    && isVar(h[9], Opcodes.ALOAD, 0)
                    && isField(h[10], Opcodes.GETFIELD, vehCls, "vehicleId", "S")
                    && h[11].getOpcode() == Opcodes.ICONST_2
                    && isCall(h[12], Opcodes.INVOKEVIRTUAL, "zombie/vehicles/VehicleManager", "sendVehicleRequest", "(SS)V")
                    && h[13] instanceof JumpInsnNode toResume && toResume.getOpcode() == Opcodes.GOTO
                    && nextReal(toResume.label) == resume
                    && nextReal(notListed.label) == h[14]
                    && isField(h[14], Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z")
                    && h[15] instanceof JumpInsnNode isServer && isServer.getOpcode() == Opcodes.IFNE
                    && nextReal(isServer.label) == resume
                    && isVar(h[16], Opcodes.ALOAD, 0)
                    && isField(h[17], Opcodes.GETFIELD, vehCls, "chunk", chunkDesc)
                    && isField(h[18], Opcodes.GETFIELD, chunkCls, "refs", "Ljava/util/ArrayList;")
                    && isCall(h[19], Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "isEmpty", "()Z")
                    && h[20] instanceof JumpInsnNode refsLeft && refsLeft.getOpcode() == Opcodes.IFEQ
                    && nextReal(refsLeft.label) == resume
                    && isVar(h[21], Opcodes.ALOAD, 0)
                    && isCall(h[22], Opcodes.INVOKEVIRTUAL, vehCls, "removeFromWorld", "()V")
                    && h[23].getOpcode() == Opcodes.RETURN;
        }
        // 另 3 個 contains：animals.contains(animal)（getAnimals 之後）與兩個 assert（$assertionsDisabled 之後讀 chunk.vehicles）
        int animalContains = 0;
        int assertContains = 0;
        for (int i = headContains + 1; orphanHead && i < vehUpdateReal.size(); i++) {
            if (!isCall(vehUpdateReal.get(i), Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "contains", listContainsDesc)) {
                continue;
            }
            if (isCall(vehUpdateReal.get(i - 2), Opcodes.INVOKEVIRTUAL, vehCls, "getAnimals", "()Ljava/util/ArrayList;")) {
                animalContains++;
            } else if (isField(vehUpdateReal.get(i - 2), Opcodes.GETFIELD, chunkCls, "vehicles", "Ljava/util/ArrayList;")
                    && isField(vehUpdateReal.get(i - 6), Opcodes.GETSTATIC, vehCls, "$assertionsDisabled", "Z")) {
                assertContains++;
            }
        }
        MethodNode vVehRemove = methodFromJar(jar, vehCls, "removeFromWorld", "()V");
        int firstReturn = Integer.MAX_VALUE;
        int firstRemovedWrite = Integer.MAX_VALUE;
        int removeIndex = 0;
        for (AbstractInsnNode in : vVehRemove.instructions) {
            if (in.getOpcode() < 0) {
                continue;
            }
            if (in.getOpcode() == Opcodes.RETURN && firstReturn == Integer.MAX_VALUE) {
                firstReturn = removeIndex;
            }
            if (isField(in, Opcodes.PUTFIELD, vehCls, "removedFromWorld", "Z") && firstRemovedWrite == Integer.MAX_VALUE) {
                firstRemovedWrite = removeIndex;
            }
            removeIndex++;
        }
        failed += check("W62 vanilla 前提：update 開頭 chunk.vehicles.contains(this) 不含就送 VehicleRequest(2) 續行，含且 refs 空就 "
                        + "removeFromWorld 後 RETURN；另 3 個 contains 是 animals 1、assert 2；removeFromWorld 遇本機乘客在設旗標前 RETURN",
                orphanHead && animalContains == 1 && assertContains == 2
                && countFieldTouches(vVehRemove, "zombie/characters/IsoPlayer", "players") >= 1
                && firstReturn < firstRemovedWrite
                && countExactCalls(methodFromJar(jar, chunkCls, "removeFromWorld", "()V"),
                        Opcodes.INVOKEVIRTUAL, vehCls, "removeFromWorld", "()V") == 1);

        // ---- W63 doRemove 把本機模擬的車的駕駛移到伺服器舊位置（docs/patches.md 2bz）----
        // 存在理由：doRemove 唯一的 IsoPlayer.setPosition(伺服器 x, y, 0) 在「玩家坐在這台車上」的分支內，之後送 VehicleRequest(2)；
        // 整個方法不看車的授權（只呼叫 BaseVehicle 的 removeFromWorld、removeFromSquare）。TIS 補上授權判斷＝轉紅、重估撤刀。
        String vehiclePacketCls = "zombie/network/packets/vehicle/VehiclePacket";
        String doRemoveDesc = "(Lzombie/network/IConnection;)Z";
        String orphanRecallDesc = "(L" + playerCls + ";FFF)V";
        MethodNode vDoRemove = methodFromJar(jar, vehiclePacketCls, "doRemove", doRemoveDesc);
        List<AbstractInsnNode> doRemoveReal = new ArrayList<>();
        for (AbstractInsnNode in : vDoRemove.instructions) {
            if (in.getOpcode() >= 0) {
                doRemoveReal.add(in);
            }
        }
        int recallAt = -1;
        for (int i = 0; i < doRemoveReal.size(); i++) {
            if (isCall(doRemoveReal.get(i), Opcodes.INVOKEVIRTUAL, playerCls, "setPosition", "(FFF)V")) {
                recallAt = i;
            }
        }
        String positionCls = "zombie/network/fields/Position";
        String vehicleIdCls = "zombie/network/fields/vehicle/VehicleID";
        boolean recallShape = recallAt >= 15 && recallAt + 6 < doRemoveReal.size()
                && isCall(doRemoveReal.get(recallAt - 15), Opcodes.INVOKEVIRTUAL, playerCls, "getVehicle", "()L" + vehCls + ";")
                && isCall(doRemoveReal.get(recallAt - 12), Opcodes.INVOKEVIRTUAL, vehicleIdCls, "getVehicle", "()L" + vehCls + ";")
                && doRemoveReal.get(recallAt - 11).getOpcode() == Opcodes.IF_ACMPNE
                && isCall(doRemoveReal.get(recallAt - 5), Opcodes.INVOKEVIRTUAL, positionCls, "getX", "()F")
                && isCall(doRemoveReal.get(recallAt - 2), Opcodes.INVOKEVIRTUAL, positionCls, "getY", "()F")
                && doRemoveReal.get(recallAt - 1).getOpcode() == Opcodes.FCONST_0
                && doRemoveReal.get(recallAt + 5).getOpcode() == Opcodes.ICONST_2
                && isCall(doRemoveReal.get(recallAt + 6), Opcodes.INVOKEVIRTUAL, "zombie/vehicles/VehicleManager",
                        "sendVehicleRequest", "(SS)V");
        boolean doRemoveNoAuth = true;
        for (AbstractInsnNode in : doRemoveReal) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(vehCls)
                    && !mi.name.equals("removeFromWorld") && !mi.name.equals("removeFromSquare")) {
                doRemoveNoAuth = false;
            }
        }
        failed += check("W63 vanilla 前提：doRemove 唯一的 IsoPlayer.setPosition(伺服器 x, y, 0) 在玩家坐在這台車上的分支內、之後送 "
                        + "VehicleRequest(2)；方法不看車的授權",
                countExactCalls(vDoRemove, Opcodes.INVOKEVIRTUAL, playerCls, "setPosition", "(FFF)V") == 1
                && recallShape && doRemoveNoAuth);
        MethodNode pDoRemove = method(distJava, vehiclePacketCls, "doRemove", doRemoveDesc);
        failed += check("W63 doRemove 的 setPosition 同形改道 VehicleOrphanGuard.setPosition，其餘指令與 frames 保留",
                countExactCalls(pDoRemove, Opcodes.INVOKEVIRTUAL, playerCls, "setPosition", "(FFF)V") == 0
                && countExactCalls(pDoRemove, Opcodes.INVOKESTATIC, orphanCls, "setPosition", orphanRecallDesc) == 1
                && methodText(vDoRemove).replace("INVOKEVIRTUAL " + playerCls + ".setPosition (FFF)V",
                        "INVOKESTATIC " + orphanCls + ".setPosition " + orphanRecallDesc).equals(methodText(pDoRemove)));
        String vehiclePacketParseDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        failed += check("W63 負對照：VehiclePacket.doRequest 與 parse 未改動",
                methodText(method(distJava, vehiclePacketCls, "doRequest", doRemoveDesc))
                        .equals(methodText(methodFromJar(jar, vehiclePacketCls, "doRequest", doRemoveDesc)))
                && methodText(method(distJava, vehiclePacketCls, "parse", vehiclePacketParseDesc))
                        .equals(methodText(methodFromJar(jar, vehiclePacketCls, "parse", vehiclePacketParseDesc))));
        return failed;
    }

    /** W55 isBoneReparented 快速路徑（AnimationPlayer；PZ_Optimization 也換這個 class，相容版不裝）。 */
    static int boneReparentChecks(Path distJava, Path jar) throws Exception {
        int failed = 0;
        String apCls = "zombie/core/skinnedmodel/animation/AnimationPlayer";
        // ---- W55 isBoneReparented 快速路徑（docs/patches.md 2bs）----
        // 存在理由：原版每次呼叫都 Lambda.predicate＋PZArrayUtil.contains（TIS 自己加快速路徑時轉紅＝重估撤刀）；
        // helper 以 VarHandle 讀 private final ArrayList reparentedBoneBindings（名稱與型別鎖進建置期）。
        String reparentCls = "zombie/mdc/BoneReparentFastPath";
        String boneLoopName = "updateMultiTrackBoneTransformsInternal";
        MethodNode vReparented = methodFromJar(jar, apCls, "isBoneReparented", "(I)Z");
        failed += check("W55 vanilla 前提：isBoneReparented 以 Lambda.predicate＋PZArrayUtil.contains 掃 reparentedBoneBindings",
                countExactCalls(vReparented, Opcodes.INVOKESTATIC, "zombie/util/Lambda", "predicate",
                        "(Ljava/lang/Object;Lzombie/util/lambda/Predicates$Params1$ICallback;)Ljava/util/function/Predicate;") == 1
                && countExactCalls(vReparented, Opcodes.INVOKESTATIC, "zombie/util/list/PZArrayUtil", "contains",
                        "(Ljava/util/List;Ljava/util/function/Predicate;)Z") == 1
                && countFieldTouches(vReparented, apCls, "reparentedBoneBindings") == 1
                && hasField(classNodeFromJar(jar, apCls), "reparentedBoneBindings", "Ljava/util/ArrayList;"));
        int reparentCallers = 0;
        for (MethodNode m : classNodeFromJar(jar, apCls).methods) {
            reparentCallers += countExactCalls(m, Opcodes.INVOKEVIRTUAL, apCls, "isBoneReparented", "(I)Z");
        }
        MethodNode vBoneLoop = methodFromJar(jar, apCls, boneLoopName, "(F)V");
        MethodNode pBoneLoop = method(distJava, apCls, boneLoopName, "(F)V");
        failed += check("W55 vanilla 前提：AnimationPlayer 內唯一的 isBoneReparented 呼叫在 " + boneLoopName,
                reparentCallers == 1
                && countExactCalls(vBoneLoop, Opcodes.INVOKEVIRTUAL, apCls, "isBoneReparented", "(I)Z") == 1);
        failed += check("W55 " + boneLoopName + " 唯一 isBoneReparented 同形改道，其餘指令與 frames 保留",
                countExactCalls(pBoneLoop, Opcodes.INVOKEVIRTUAL, apCls, "isBoneReparented", "(I)Z") == 0
                && methodText(vBoneLoop).replace("INVOKEVIRTUAL " + apCls + ".isBoneReparented (I)Z",
                        "INVOKESTATIC " + reparentCls + ".isBoneReparented (L" + apCls + ";I)Z")
                        .equals(methodText(pBoneLoop)));
        failed += check("W55 負對照：isBoneReparented 本體未改動",
                methodText(method(distJava, apCls, "isBoneReparented", "(I)Z")).equals(methodText(vReparented)));
        return failed;
    }

    /** W57 自建房 chunk 載入斷線（IsoChunk；PZ_Optimization 也換這個 class，相容版不裝）。 */
    static int staleRoomChecks(Path distJava, Path jar) throws Exception {
        int failed = 0;
        String squareCls = "zombie/iso/IsoGridSquare";
        // ---- W57 自建房 chunk 載入斷線（docs/patches.md 2bu）----
        // 存在理由：格子在 World Streamer 執行緒反序列化時綁房間（LoadFromDiskOrBufferInternal 唯一的 setRoomID），
        // IsoRoom.clear 把 def 設成 null，hasLightSwitches 讀 def.objects 不檢查 null（TIS 改了任一處就轉紅＝重估撤刀）。
        // helper 以 MethodHandle 呼叫 updateSquares 的兩個 lambda 並照抄 chunk 收尾：名稱、順序與收尾內容鎖進建置期。
        String chunkCls = "zombie/iso/IsoChunk";
        String roomCls = "zombie/iso/areas/IsoRoom";
        String wrtmgCls = "zombie/iso/areas/isoregion/metagrid/WorldRegionToMetaGrid";
        String lightSwitchCls = "zombie/iso/objects/IsoLightSwitch";
        String staleCls = "zombie/mdc/StaleRoomGuard";
        String chunkArg = "(L" + chunkCls + ";)V";
        String squareArg = "(L" + squareCls + ";)V";
        MethodNode vHasSwitches = methodFromJar(jar, roomCls, "hasLightSwitches", "()Z");
        boolean clearNullsDef = false;
        for (AbstractInsnNode in : methodFromJar(jar, roomCls, "clear", "(Z)V").instructions) {
            if (in.getOpcode() == Opcodes.ACONST_NULL && nextReal(in) instanceof FieldInsnNode put
                    && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(roomCls) && put.name.equals("def")) {
                clearNullsDef = true;
            }
        }
        failed += check("W57 vanilla 前提：chunk 在 streamer 執行緒綁房間、IsoRoom.clear 把 def 設 null、hasLightSwitches 讀 def.objects 不檢查 null",
                countExactCalls(methodFromJar(jar, chunkCls, "LoadFromDiskOrBufferInternal", "(Ljava/nio/ByteBuffer;)V"),
                        Opcodes.INVOKEVIRTUAL, squareCls, "setRoomID", "(J)V") == 1
                && clearNullsDef
                && countFieldTouches(vHasSwitches, "zombie/iso/RoomDef", "objects") > 0
                && countOpcode(vHasSwitches, Opcodes.IFNULL) + countOpcode(vHasSwitches, Opcodes.IFNONNULL) == 0);
        MethodNode vUpdateSquares = methodFromJar(jar, wrtmgCls, "updateSquares", "()V");
        List<String> squareSteps = new ArrayList<>();
        for (AbstractInsnNode in : vUpdateSquares.instructions) {
            if (in instanceof InvokeDynamicInsnNode indy && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle step) {
                squareSteps.add(step.getOwner() + "." + step.getName() + step.getDesc()
                        + (step.getTag() == Opcodes.H_INVOKESTATIC ? " static" : ""));
            }
        }
        failed += check("W57 vanilla 前提：updateSquares 只走 ChunkMap，依序以 static lambda$updateSquares$0（setRoomID）、$1（RecalcProperties）走訪，收尾 2112L＋cutaway＋lighting 且與 helper 常數連動",
                countExactCalls(vUpdateSquares, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunkMap", "getChunk", "(II)L" + chunkCls + ";") == 1
                && squareSteps.equals(List.of(wrtmgCls + ".lambda$updateSquares$0" + squareArg + " static",
                        wrtmgCls + ".lambda$updateSquares$1" + squareArg + " static"))
                && countExactCalls(methodFromJar(jar, wrtmgCls, "lambda$updateSquares$0", squareArg),
                        Opcodes.INVOKEVIRTUAL, squareCls, "setRoomID", "(J)V") == 1
                && countExactCalls(methodFromJar(jar, wrtmgCls, "lambda$updateSquares$1", squareArg),
                        Opcodes.INVOKEVIRTUAL, squareCls, "RecalcProperties", "()V") == 1
                && countLongConst(vUpdateSquares, 2112L) == 1
                && countExactCalls(vUpdateSquares, Opcodes.INVOKEVIRTUAL, chunkCls, "invalidateRenderChunkLevels", "(J)V") == 1
                && countExactCalls(vUpdateSquares, Opcodes.INVOKEVIRTUAL,
                        "zombie/iso/fboRenderChunk/FBORenderCutaways$ChunkLevelsData", "invalidateAll", "()V") == 1
                && countExactCalls(vUpdateSquares, Opcodes.INVOKEVIRTUAL, chunkCls, "checkLightingLater_AllPlayers_AllLevels", "()V") == 1
                && classNode(distJava, staleCls).fields.stream().anyMatch(f -> f.name.equals("RENDER_DIRTY_FLAGS")
                        && f.value instanceof Long v && v == 2112L));
        MethodNode vMainLoad = methodFromJar(jar, chunkCls, "loadInMainThread", "()V");
        MethodNode pMainLoad = method(distJava, chunkCls, "loadInMainThread", "()V");
        failed += check("W57 loadInMainThread 唯一 IsoLightSwitch.chunkLoaded 同形改道 StaleRoomGuard.chunkLoaded，其餘指令與 frames 保留",
                countExactCalls(vMainLoad, Opcodes.INVOKESTATIC, lightSwitchCls, "chunkLoaded", chunkArg) == 1
                && countExactCalls(pMainLoad, Opcodes.INVOKESTATIC, lightSwitchCls, "chunkLoaded", chunkArg) == 0
                && methodText(vMainLoad).replace("INVOKESTATIC " + lightSwitchCls + ".chunkLoaded " + chunkArg,
                        "INVOKESTATIC " + staleCls + ".chunkLoaded " + chunkArg).equals(methodText(pMainLoad)));
        failed += check("W57 負對照：IsoChunk.recalcNeighboursNow（另一個 chunkLoaded 呼叫點）未改動",
                methodText(method(distJava, chunkCls, "recalcNeighboursNow", "()V"))
                        .equals(methodText(methodFromJar(jar, chunkCls, "recalcNeighboursNow", "()V"))));
        return failed;
    }

    /**
     * W68 metagrid 房間表鎖（docs/patches.md 2cd）。
     * 存在理由：IsoMetaCell.isoRooms／isoBuildings 是一般 HashMap；getRoomByID（streamer 反序列化 chunk 時經 IsoChunk.getRoom）
     * 沒有鎖就補房間，recalculateBuildingAndRoomIDs 複製兩張表的 values 後清空重填。TIS 換成並行安全的表或自己加鎖＝轉紅、重估撤刀。
     * 不死結的依據也鎖進建置期：getRoomByID 的遞移呼叫不拿 jar 內的鎖、不呼叫 JDK 阻塞 API；streamer 走到 getRoom 的每一層都不持鎖；
     * 重建（DataRoot、WorldRegionToMetaGrid）不碰 WorldStreamer。
     */
    static int metaGridLockChecks(Path distJava, Path jar) throws Exception {
        int failed = 0;
        String lockCls = "zombie/mdc/MetaGridLock";
        String gridCls = "zombie/iso/IsoMetaGrid";
        String cellCls = "zombie/iso/IsoMetaCell";
        String rebuildCls = "zombie/iso/areas/isoregion/metagrid/WorldRegionToMetaGrid";
        String dataRootCls = "zombie/iso/areas/isoregion/data/DataRoot";
        String roomDesc = "(J)Lzombie/iso/areas/IsoRoom;";
        String rebuildDesc = "(Ljava/util/ArrayList;)V";
        String hashPut = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
        MethodNode vGetRoom = methodFromJar(jar, gridCls, "getRoomByID", roomDesc);
        MethodNode vRecalc = methodFromJar(jar, rebuildCls, "recalculateBuildingAndRoomIDs", "(L" + cellCls + ";)V");
        ClassNode vCell = classNodeFromJar(jar, cellCls);
        failed += check("W68 vanilla 前提：isoRooms／isoBuildings 是 HashMap；getRoomByID 沒有鎖、put 兩張表；"
                        + "recalculateBuildingAndRoomIDs 複製兩張表的 values 後清空重填",
                hasField(vCell, "isoRooms", "Ljava/util/HashMap;") && hasField(vCell, "isoBuildings", "Ljava/util/HashMap;")
                && (vGetRoom.access & Opcodes.ACC_SYNCHRONIZED) == 0 && countOpcode(vGetRoom, Opcodes.MONITORENTER) == 0
                && countExactCalls(vGetRoom, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", hashPut) == 2
                && countExactCalls(vRecalc, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "values", "()Ljava/util/Collection;") == 2
                && countExactCalls(vRecalc, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "clear", "()V") == 3
                && countExactCalls(vRecalc, Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", hashPut) == 3
                && countOpcode(vRecalc, Opcodes.MONITORENTER) == 0);
        failed += check("W68 vanilla 前提：streamer 反序列化 chunk 時 setRoomID 經 IsoChunk.getRoom 呼叫 getRoomByID",
                countExactCalls(methodFromJar(jar, "zombie/iso/IsoGridSquare", "setRoomID", "(J)V"),
                        Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunk", "getRoom", roomDesc) == 1);

        // jar 內每一個 getRoomByID 都要經過鎖：呼叫點清冊釘死，新增或搬走都轉紅。
        List<String> roomCallers = jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, gridCls, "getRoomByID", roomDesc);
        List<String[]> roomSites = List.of(
                new String[]{"zombie/iso/IsoChunk", "getRoom", roomDesc},
                new String[]{"zombie/iso/RoomDef", "getIsoRoom", "()Lzombie/iso/areas/IsoRoom;"},
                new String[]{"zombie/iso/areas/IsoBuilding", "CreateFrom", "(Lzombie/iso/BuildingDef;L" + cellCls + ";)V"},
                new String[]{"zombie/iso/fboRenderChunk/FBORenderCutaways", "CalculateBuildingsToCollapse", "()Z"},
                new String[]{"zombie/network/packets/MetaGridPacket", "processClient", "(Lzombie/core/raknet/UdpConnection;)V"});
        List<String> expectedCallers = new ArrayList<>();
        for (String[] site : roomSites) {
            expectedCallers.add(site[0] + "." + site[1] + site[2]);
        }
        java.util.Collections.sort(expectedCallers);
        failed += check("W68 vanilla 前提：jar 內 getRoomByID 恰 5 個呼叫點（IsoChunk.getRoom、RoomDef.getIsoRoom、IsoBuilding.CreateFrom、"
                        + "FBORenderCutaways.CalculateBuildingsToCollapse、MetaGridPacket.processClient），各 1 個",
                roomCallers.equals(expectedCallers));
        String vRoomCall = "INVOKEVIRTUAL " + gridCls + ".getRoomByID " + roomDesc;
        String pRoomCall = "INVOKESTATIC " + lockCls + ".getRoomByID (L" + gridCls + ";J)Lzombie/iso/areas/IsoRoom;";
        boolean roomSitesLocked = true;
        for (String[] site : roomSites) {
            MethodNode vSite = methodFromJar(jar, site[0], site[1], site[2]);
            MethodNode pSite = method(distJava, site[0], site[1], site[2]);
            if (!methodText(vSite).replace(vRoomCall, pRoomCall).equals(methodText(pSite))
                    || countExactCalls(pSite, Opcodes.INVOKEVIRTUAL, gridCls, "getRoomByID", roomDesc) != 0) {
                System.out.println("  !! 未同形改道: " + site[0] + "." + site[1]);
                roomSitesLocked = false;
            }
        }
        failed += check("W68 五個 getRoomByID 呼叫點同形改道 MetaGridLock.getRoomByID，其餘指令與 frames 保留", roomSitesLocked);
        MethodNode vDataRoot = methodFromJar(jar, dataRootCls, "clientProcessBuildings", "()V");
        MethodNode pDataRoot = method(distJava, dataRootCls, "clientProcessBuildings", "()V");
        failed += check("W68 clientProcessBuildings 唯一呼叫點（DataRoot.clientProcessBuildings）同形改道 MetaGridLock，其餘指令與 frames 保留",
                jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, rebuildCls, "clientProcessBuildings", rebuildDesc)
                        .equals(List.of(dataRootCls + ".clientProcessBuildings()V"))
                && methodText(vDataRoot).replace("INVOKEVIRTUAL " + rebuildCls + ".clientProcessBuildings " + rebuildDesc,
                        "INVOKESTATIC " + lockCls + ".clientProcessBuildings (L" + rebuildCls + ";" + rebuildDesc.substring(1))
                        .equals(methodText(pDataRoot)));

        // 不死結的依據（docs/patches.md 2cd）。等鎖的執行緒只會停在上面 6 個改道點；死結要有「拿著鎖的執行緒在等一個等鎖的執行緒」。
        JarIndex index = JarIndex.load(jar);
        List<String> roomWaits = new ArrayList<>();
        Map<String, MethodNode> roomClosure = index.closure(gridCls, "getRoomByID", roomDesc, true, 200);
        if (roomClosure == null) {
            roomWaits.add("閉包超過 200 個方法");
        } else {
            roomClosure.forEach((key, m) -> {
                if ((m.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE)) != 0) {
                    roomWaits.add(key + " synchronized/native");
                }
                if (countOpcode(m, Opcodes.MONITORENTER) != 0) {
                    roomWaits.add(key + " monitorenter");
                }
                roomWaits.addAll(index.jdkWaits(key, m));
            });
        }
        roomWaits.forEach(v -> System.out.println("  !! " + v));
        failed += check("W68 依據：getRoomByID 的遞移呼叫（虛擬呼叫含 jar 內所有覆寫）不進 monitor／synchronized／native，不呼叫 JDK 等待 API",
                roomWaits.isEmpty());
        Map<String, List<String>> streamerFrames = Map.of(
                "zombie/iso/WorldStreamer", List.of("threadLoop", "loadReceivedChunks", "DoChunk", "DoChunkAlways"),
                "zombie/iso/IsoChunk", List.of("LoadChunk", "LoadOrCreate", "LoadFromDisk", "LoadFromBuffer", "LoadFromDiskOrBuffer",
                        "LoadFromDiskOrBufferInternal", "assignRoom", "ensureNotNull", "EnsureSurroundNotNull", "getRoom"),
                "zombie/iso/IsoGridSquare", List.of("setRoomID"),
                "zombie/iso/IsoCell", List.of("PlaceLot"));
        List<String> frameHolds = new ArrayList<>();
        for (var frame : streamerFrames.entrySet()) {
            for (MethodNode m : index.classes().get(frame.getKey()).methods) {
                if (frame.getValue().contains(m.name)) {
                    frameHolds.addAll(frameLocks(frame.getKey() + "." + m.name + m.desc, m));
                }
            }
        }
        for (MethodNode m : index.classes().get("zombie/iso/CellLoader").methods) {
            frameHolds.addAll(frameLocks("zombie/iso/CellLoader." + m.name + m.desc, m));
        }
        frameHolds.forEach(v -> System.out.println("  !! " + v));
        failed += check("W68 依據：streamer 從 threadLoop 到 IsoChunk.getRoom（含 CellLoader／PlaceLot 新 chunk 路徑）每一層都不拿著鎖往下呼叫",
                frameHolds.isEmpty());
        // 重建拿著鎖時可以進別的 monitor（等鎖的 streamer 手上沒有），但不能等 streamer：遞移呼叫（只走宣告的實作）不碰 WorldStreamer；
        // 會等待的 JDK 呼叫釘死成已審過的清單——全在 Lua 除錯器的巢狀畫面迴圈或伺服器送封包，客戶端重建走不到。清單一變就轉紅，重新稽核。
        Map<String, MethodNode> rebuildClosure = index.closure(rebuildCls, "clientProcessBuildings", rebuildDesc, false, 20000);
        Set<String> rebuildWaits = new java.util.TreeSet<>();
        List<String> streamerTouches = new ArrayList<>();
        if (rebuildClosure != null) {
            rebuildClosure.forEach((key, m) -> {
                rebuildWaits.addAll(index.jdkWaits(key, m));
                if (countCallsToOwner(m, "zombie/iso/WorldStreamer") + countFieldTouchesOfOwner(m, "zombie/iso/WorldStreamer") != 0) {
                    streamerTouches.add(key);
                }
            });
        }
        Set<String> reviewedRebuildWaits = Set.of(
                "zombie/core/SpriteRenderer.waitForReadySlotToOpen()V -> java/lang/Object.wait",
                "zombie/core/SpriteRenderer.waitForReadyState(Ljava/util/function/BooleanSupplier;)Z -> java/lang/Object.wait",
                "zombie/core/opengl/RenderContextQueueItem.waitUntilFinished(Ljava/util/function/BooleanSupplier;)V -> java/lang/Object.wait",
                "zombie/core/textures/TextureDraw.GetFutureResultNoThrow()V -> java/util/concurrent/Future.get",
                "zombie/core/textures/TextureDraw.GetFutureResultThrow()V -> java/util/concurrent/Future.get",
                "zombie/core/raknet/UdpConnection.startPacket()Lzombie/core/network/ByteBufferWriter; -> java/util/concurrent/locks/Lock.lock",
                "zombie/ui/UIManager$Sync.endFrame()V -> java/lang/Thread.sleep",
                "zombie/iso/sprite/IsoSpriteInstance.get(Lzombie/iso/sprite/IsoSprite;)Lzombie/iso/sprite/IsoSpriteInstance; -> java/lang/Thread.onSpinWait");
        if (!rebuildWaits.equals(reviewedRebuildWaits)) {
            rebuildWaits.stream().filter(w -> !reviewedRebuildWaits.contains(w)).forEach(w -> System.out.println("  !! 新的等待: " + w));
            reviewedRebuildWaits.stream().filter(w -> !rebuildWaits.contains(w)).forEach(w -> System.out.println("  !! 消失的等待: " + w));
        }
        streamerTouches.forEach(t -> System.out.println("  !! 碰 WorldStreamer: " + t));
        failed += check("W68 依據：重建（clientProcessBuildings 的遞移呼叫）不碰 WorldStreamer，會等待的 JDK 呼叫與已審清單相同（"
                        + (rebuildClosure == null ? "閉包過大" : rebuildClosure.size() + " 個方法") + "）",
                rebuildClosure != null && streamerTouches.isEmpty() && rebuildWaits.equals(reviewedRebuildWaits));

        MethodNode gRoom = method(distJava, lockCls, "getRoomByID", "(L" + gridCls + ";J)Lzombie/iso/areas/IsoRoom;");
        MethodNode gRebuild = method(distJava, lockCls, "clientProcessBuildings", "(L" + rebuildCls + ";" + rebuildDesc.substring(1));
        failed += check("W68 helper：開與關各呼叫原方法一次（共 2 個呼叫點），鎖是公平的 ReentrantLock",
                countExactCalls(gRoom, Opcodes.INVOKEVIRTUAL, gridCls, "getRoomByID", roomDesc) == 2
                && countExactCalls(gRebuild, Opcodes.INVOKEVIRTUAL, rebuildCls, "clientProcessBuildings", rebuildDesc) == 2
                && countExactCalls(method(distJava, lockCls, "<clinit>", "()V"), Opcodes.INVOKESPECIAL,
                        "java/util/concurrent/locks/ReentrantLock", "<init>", "(Z)V") == 1);
        return failed;
    }

    /** W84：42.21.0 原版 FBORenderCell.renderOneLevel_Blood 兩個多載的 debuglessMethodText SHA-256（候選等價性的依據；變了就重新核對）。 */
    static final String W84_BLOOD6_SHA = "cdadbc4821d67d776d5a7d4bd345bd5349c8090700840281b2829be5a173ffb8";
    static final String W84_BLOOD4_SHA = "8e2e806b683dc8fe4771c0b0c33f9952dbeaa0727b96e11cc06c49d96d9b81d6";
    /** W84：42.21.0 原版 BoundedQueue 整類的 debuglessClassText SHA-256（SplatQueue 覆寫的四個方法是全部會改內容的路徑；變了就重新稽核）。 */
    static final String W84_QUEUE_SHA = "806983e235c4608044ee5ad0d1b54ec94fdfeed58a83b9f500cbf28641cb5098";

    /**
     * W84 鄰居 chunk 血跡的位置索引（docs/patches.md 2cs；FBORenderCell 與 IsoChunk，PZ_Optimization 也換，相容版不裝）。
     * 存在理由：renderOneLevel 每畫一層呼叫 6 參數多載 1 次（自己）、4 參數多載 8 次（鄰居），4 參數版只把範圍換成本 chunk
     * 外擴 1 格再呼叫 6 參數版。TIS 改掉這個掃法時轉紅＝重估撤刀。
     * 等價性依據：兩個多載的文字以 SHA 整個釘住（掃描迴圈以 size()／get(i) 依序走 fade 與 floorBloodSplats、範圍以
     * i2f(cx)+b.x 比 i2f(min/max)、掃描時不改清單；這份文字變了就重新核對 BloodSplatIndex 的證明）；BoundedQueue 整類以 SHA
     * 釘住，會改內容的方法正好是 SplatQueue 覆寫的四個；血跡的 x、y 只在 IsoFloorBloodSplat 建構子與 load 寫入，load 全 jar
     * 只在 IsoChunk 讀檔時對剛建好的物件呼叫一次（放進清單前）；兩份清單只在 IsoChunk 兩個建構子以空的原版實作建立。
     */
    static int bloodSplatChecks(Path distJava, Path jar) throws Exception {
        int failed = 0;
        String cellCls = "zombie/iso/fboRenderChunk/FBORenderCell";
        String chunkCls = "zombie/iso/IsoChunk";
        String queueCls = "zombie/core/utils/BoundedQueue";
        String splatCls = "zombie/iso/IsoFloorBloodSplat";
        String index = "zombie/mdc/BloodSplatIndex";
        String desc6 = "(L" + chunkCls + ";IIIII)V";
        String desc4 = "(L" + chunkCls + ";III)V";
        String levelDesc = "(L" + chunkCls + ";ILzombie/iso/IsoCell$PerPlayerRender;IJLzombie/core/opengl/Shader;Lzombie/core/opengl/Shader;)V";
        MethodNode vLevel = methodFromJar(jar, cellCls, "renderOneLevel", levelDesc);
        MethodNode v6 = methodFromJar(jar, cellCls, "renderOneLevel_Blood", desc6);
        MethodNode v4 = methodFromJar(jar, cellCls, "renderOneLevel_Blood", desc4);
        failed += check("W84 存在理由：renderOneLevel 每層呼叫 6 參數多載 1 次、4 參數（鄰居）8 次；4 參數版只呼叫 6 參數版 1 次",
                countExactCalls(vLevel, Opcodes.INVOKEVIRTUAL, cellCls, "renderOneLevel_Blood", desc6) == 1
                && countExactCalls(vLevel, Opcodes.INVOKEVIRTUAL, cellCls, "renderOneLevel_Blood", desc4) == 8
                && countExactCalls(v4, Opcodes.INVOKEVIRTUAL, cellCls, "renderOneLevel_Blood", desc6) == 1
                && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, cellCls, "renderOneLevel_Blood", desc6).size() == 2);
        failed += check("W84 等價性依據：原版兩個 renderOneLevel_Blood 的文字與核對過的 42.21.0 相同（SHA-256）",
                sha256Hex(debuglessMethodText(jar, cellCls, "renderOneLevel_Blood", desc6)).equals(W84_BLOOD6_SHA)
                && sha256Hex(debuglessMethodText(jar, cellCls, "renderOneLevel_Blood", desc4)).equals(W84_BLOOD4_SHA));
        failed += check("W84 等價性依據：IsoFloorBloodSplat 的 x、y 只在建構子與 load 寫入",
                jarWideFieldRefs(jar, Opcodes.PUTFIELD, splatCls, "x").equals(Map.of(splatCls + ".<init>", 1, splatCls + ".load", 1))
                && jarWideFieldRefs(jar, Opcodes.PUTFIELD, splatCls, "y").equals(Map.of(splatCls + ".<init>", 1, splatCls + ".load", 1)));
        String loadDesc = "(Ljava/nio/ByteBuffer;I)V";
        String loaderDesc = "(Ljava/nio/ByteBuffer;)V";
        String loaderText = debuglessMethodText(jar, chunkCls, "LoadFromDiskOrBufferInternal", loaderDesc);
        java.util.regex.Matcher freshLoad = java.util.regex.Pattern.compile("    NEW " + splatCls + "\n    DUP\n    INVOKESPECIAL "
                + splatCls + ".<init> \\(\\)V\n    ASTORE (\\d+)\n    ALOAD \\1\n    ALOAD \\d+\n    ILOAD \\d+\n    INVOKEVIRTUAL "
                + splatCls + ".load \\(Ljava/nio/ByteBuffer;I\\)V\n").matcher(loaderText);
        int freshLoads = 0;
        while (freshLoad.find()) {
            freshLoads++;
        }
        failed += check("W84 等價性依據：IsoFloorBloodSplat.load 全 jar 只有 IsoChunk.LoadFromDiskOrBufferInternal 呼叫一次，"
                        + "對象是前一行 new 出來的物件（還沒放進清單）",
                jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, splatCls, "load", loadDesc)
                        .equals(List.of(chunkCls + ".LoadFromDiskOrBufferInternal" + loaderDesc))
                && freshLoads == 1);
        String queuePut = "    PUTFIELD " + chunkCls + ".floorBloodSplats : L" + queueCls + ";\n";
        String fadePut = "    PUTFIELD " + chunkCls + ".floorBloodSplatsFade : Ljava/util/ArrayList;\n";
        String[] ctorDescs = {"(Lzombie/iso/IsoCell;)V", "(Lzombie/iso/WorldReuserThread;)V"};
        boolean ctorsVanilla = jarWideFieldRefs(jar, Opcodes.PUTFIELD, chunkCls, "floorBloodSplats").equals(Map.of(chunkCls + ".<init>", 2))
                && jarWideFieldRefs(jar, Opcodes.PUTFIELD, chunkCls, "floorBloodSplatsFade").equals(Map.of(chunkCls + ".<init>", 2));
        boolean ctorsPatched = true;
        for (String ctorDesc : ctorDescs) {
            String vText = methodText(methodFromJar(jar, chunkCls, "<init>", ctorDesc));
            ctorsVanilla &= vText.contains("    NEW " + queueCls + "\n    DUP\n    SIPUSH 1000\n    INVOKESPECIAL " + queueCls
                    + ".<init> (I)V\n" + queuePut)
                    && vText.contains("    NEW java/util/ArrayList\n    DUP\n    INVOKESPECIAL java/util/ArrayList.<init> ()V\n" + fadePut);
            ctorsPatched &= methodText(method(distJava, chunkCls, "<init>", ctorDesc)).equals(vText
                    .replace(queuePut, "    INVOKESTATIC " + index + ".wrapQueue (L" + queueCls + ";)L" + queueCls + ";\n" + queuePut)
                    .replace(fadePut, "    INVOKESTATIC " + index + ".wrapFade (Ljava/util/ArrayList;)Ljava/util/ArrayList;\n" + fadePut));
        }
        failed += check("W84 等價性依據：兩份清單只在 IsoChunk 兩個建構子各寫一次，都是剛建好的空 BoundedQueue(1000)／ArrayList", ctorsVanilla);
        ClassNode vQueue = classNodeFromJar(jar, queueCls);
        List<String> mutators = new ArrayList<>();
        for (MethodNode m : vQueue.methods) {
            boolean writes = !m.name.equals("<init>") && (countOpcode(m, Opcodes.AASTORE) > 0 || countOpcode(m, Opcodes.PUTFIELD) > 0);
            if (writes) {
                mutators.add(m.name + m.desc);
            }
        }
        java.util.Collections.sort(mutators);
        ClassNode gQueue = classNode(distJava, index + "$SplatQueue");
        boolean queueOverrides = mutators.stream().allMatch(sig -> gQueue.methods.stream().anyMatch(m -> (m.name + m.desc).equals(sig)));
        failed += check("W84 等價性依據：BoundedQueue 整類與核對過的 42.21.0 相同（SHA-256），會改內容的方法正好是 add／clear／remove／"
                        + "removeFirst，SplatQueue 全部覆寫（多記一次變動）",
                sha256Hex(debuglessClassText(jar, queueCls)).equals(W84_QUEUE_SHA)
                && (vQueue.access & Opcodes.ACC_FINAL) == 0
                && mutators.equals(List.of("add(Ljava/lang/Object;)V", "clear()V", "remove(I)Ljava/lang/Object;", "removeFirst()Ljava/lang/Object;"))
                && queueOverrides);
        ClassNode gFade = classNode(distJava, index + "$FadeList");
        failed += check("W84 helper：FadeList 記 set，replaceAll／sort／removeAll／retainAll／subList／reversed 呼叫前標成不可信",
                List.of("set(ILjava/lang/Object;)Ljava/lang/Object;", "replaceAll(Ljava/util/function/UnaryOperator;)V",
                        "sort(Ljava/util/Comparator;)V", "removeAll(Ljava/util/Collection;)Z", "retainAll(Ljava/util/Collection;)Z",
                        "subList(II)Ljava/util/List;", "reversed()Ljava/util/List;").stream()
                        .allMatch(sig -> gFade.methods.stream().anyMatch(m -> (m.name + m.desc).equals(sig))));

        String v6Text = methodText(v6);
        String expected6 = "    ALOAD 1\n    ILOAD 3\n    ILOAD 4\n    ILOAD 5\n    ILOAD 6\n    INVOKESTATIC " + index
                + ".enter (L" + chunkCls + ";IIII)V\n"
                + v6Text.replace("    INVOKEVIRTUAL java/util/ArrayList.size ()I\n",
                                "    INVOKESTATIC " + index + ".listSize (Ljava/util/ArrayList;)I\n")
                        .replace("    INVOKEVIRTUAL java/util/ArrayList.get (I)Ljava/lang/Object;\n",
                                "    INVOKESTATIC " + index + ".listGet (Ljava/util/ArrayList;I)Ljava/lang/Object;\n")
                        .replace("    INVOKEVIRTUAL " + queueCls + ".size ()I\n",
                                "    INVOKESTATIC " + index + ".queueSize (L" + queueCls + ";)I\n")
                        .replace("    INVOKEVIRTUAL " + queueCls + ".get (I)Ljava/lang/Object;\n",
                                "    INVOKESTATIC " + index + ".queueGet (L" + queueCls + ";I)Ljava/lang/Object;\n");
        boolean cellOthersSame = classNodeFromJar(jar, cellCls).methods.stream()
                .filter(m -> !(m.name.equals("renderOneLevel_Blood") && m.desc.equals(desc6))).allMatch(m -> {
                    try {
                        return methodText(m).equals(methodText(method(distJava, cellCls, m.name, m.desc)));
                    } catch (Exception e) {
                        return false;
                    }
                });
        failed += check("W84 手術後：6 參數 renderOneLevel_Blood 頭部 enter(chunk, minX, minY, maxX, maxY)，6 個 ArrayList.get、3 個 size、"
                        + "BoundedQueue.size／get 同形改道 BloodSplatIndex，其餘指令與 frames 不變；FBORenderCell 其餘方法不變",
                countExactCalls(v6, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;") == 6
                && countExactCalls(v6, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I") == 3
                && countExactCalls(v6, Opcodes.INVOKEVIRTUAL, queueCls, "size", "()I") == 1
                && countExactCalls(v6, Opcodes.INVOKEVIRTUAL, queueCls, "get", "(I)Ljava/lang/Object;") == 1
                && methodText(method(distJava, cellCls, "renderOneLevel_Blood", desc6)).equals(expected6)
                && cellOthersSame);
        Set<String> chunkPatched = Set.of("loadInMainThread()V", "getRoom(J)Lzombie/iso/areas/IsoRoom;",
                "<init>" + ctorDescs[0], "<init>" + ctorDescs[1]);
        boolean chunkOthersSame = classNodeFromJar(jar, chunkCls).methods.stream()
                .filter(m -> !chunkPatched.contains(m.name + m.desc)).allMatch(m -> {
                    try {
                        return methodText(m).equals(methodText(method(distJava, chunkCls, m.name, m.desc)));
                    } catch (Exception e) {
                        return false;
                    }
                });
        failed += check("W84 手術後：IsoChunk 兩個建構子在 PUTFIELD floorBloodSplats／floorBloodSplatsFade 之前各插 wrapQueue／wrapFade，"
                        + "其餘指令不變；IsoChunk 除了 W57、W68、W84 的四個方法都不變", ctorsPatched && chunkOthersSame);
        return failed;
    }

    /** 全 jar 對 owner.name desc 的呼叫點清冊（「class.method desc」，每個呼叫點一筆，排序）。 */
    static List<String> jarWideCallers(Path jar, int opcode, String owner, String name, String desc) throws Exception {
        List<String> callers = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(zf.getInputStream(e)).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode m : cn.methods) {
                    for (int i = countExactCalls(m, opcode, owner, name, desc); i > 0; i--) {
                        callers.add(cn.name + "." + m.name + m.desc);
                    }
                }
            }
        }
        java.util.Collections.sort(callers);
        return callers;
    }

    /** 方法內對某個類別任何欄位的存取次數。 */
    static int countFieldTouchesOfOwner(MethodNode m, String owner) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof FieldInsnNode fi && fi.owner.equals(owner)) {
                count++;
            }
        }
        return count;
    }

    /** 整個 jar 的類別索引（W68 呼叫閉包用）。 */
    record JarIndex(Map<String, ClassNode> classes, Map<String, List<String>> subtypes) {

        static JarIndex load(Path jar) throws Exception {
            Map<String, ClassNode> classes = new HashMap<>();
            Map<String, List<String>> subtypes = new HashMap<>();
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.getName().endsWith(".class")) {
                        ClassNode cn = new ClassNode();
                        new ClassReader(zf.getInputStream(e)).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        classes.put(cn.name, cn);
                    }
                }
            }
            for (ClassNode cn : classes.values()) {
                if (cn.superName != null) {
                    subtypes.computeIfAbsent(cn.superName, k -> new ArrayList<>()).add(cn.name);
                }
                for (String itf : cn.interfaces) {
                    subtypes.computeIfAbsent(itf, k -> new ArrayList<>()).add(cn.name);
                }
            }
            return new JarIndex(classes, subtypes);
        }

        /**
         * owner.name desc 在 jar 內的遞移呼叫閉包（鍵＝「class.method desc」）。cha＝虛擬／介面呼叫再加上 jar 內子型別的覆寫，
         * 否則只走宣告的實作；lambda 本體也算。走到超過 limit 個方法回 null（閉包變大＝要重新稽核）。
         */
        Map<String, MethodNode> closure(String owner, String name, String desc, boolean cha, int limit) {
            Map<String, MethodNode> seen = new java.util.LinkedHashMap<>();
            java.util.ArrayDeque<String[]> todo = new java.util.ArrayDeque<>();
            todo.add(new String[]{owner, name, desc});
            while (!todo.isEmpty()) {
                String[] k = todo.poll();
                String key = k[0] + "." + k[1] + k[2];
                MethodNode m = declaredMethod(classes.get(k[0]), k[1], k[2]);
                if (m == null || seen.containsKey(key)) {
                    continue;
                }
                seen.put(key, m);
                if (seen.size() > limit) {
                    return null;
                }
                for (AbstractInsnNode in : m.instructions) {
                    if (in instanceof MethodInsnNode mi && classes.containsKey(mi.owner)) {
                        String impl = declaringClass(mi.owner, mi.name, mi.desc);
                        if (impl != null) {
                            todo.add(new String[]{impl, mi.name, mi.desc});
                        }
                        if (cha && (mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKEINTERFACE)) {
                            java.util.ArrayDeque<String> subs = new java.util.ArrayDeque<>(subtypes.getOrDefault(mi.owner, List.of()));
                            Set<String> visited = new HashSet<>();
                            while (!subs.isEmpty()) {
                                String sub = subs.poll();
                                if (visited.add(sub)) {
                                    if (declaredMethod(classes.get(sub), mi.name, mi.desc) != null) {
                                        todo.add(new String[]{sub, mi.name, mi.desc});
                                    }
                                    subs.addAll(subtypes.getOrDefault(sub, List.of()));
                                }
                            }
                        }
                    } else if (in instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle h && classes.containsKey(h.getOwner())) {
                                String impl = declaringClass(h.getOwner(), h.getName(), h.getDesc());
                                if (impl != null) {
                                    todo.add(new String[]{impl, h.getName(), h.getDesc()});
                                }
                            }
                        }
                    }
                }
            }
            return seen;
        }

        /** 往父類別與介面找實際宣告這個方法的 jar 內類別。 */
        String declaringClass(String owner, String name, String desc) {
            java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(owner));
            while (!todo.isEmpty()) {
                ClassNode cn = classes.get(todo.poll());
                if (cn == null) {
                    continue;
                }
                if (declaredMethod(cn, name, desc) != null) {
                    return cn.name;
                }
                if (cn.superName != null) {
                    todo.add(cn.superName);
                }
                todo.addAll(cn.interfaces);
            }
            return null;
        }

        /**
         * 方法內對 jar 外（JDK）會讓執行緒等待的呼叫：「key -> owner.name」。涵蓋 Object.wait、Thread.join／sleep／onSpinWait／yield、
         * j.u.c 的 lock／lockInterruptibly／限時 tryLock、await*、park*、acquire*、阻塞佇列的 put／take／限時 offer、poll、
         * Future／ForkJoinTask 的 get、join、Exchanger、invokeAll／Any，與 FileChannel.lock。JDK 同步容器（Vector 等）的方法鎖
         * 只鎖自己、不回呼，不算。
         */
        List<String> jdkWaits(String key, MethodNode m) {
            List<String> waits = new ArrayList<>();
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof MethodInsnNode mi && !classes.containsKey(mi.owner) && waits(mi.owner, mi.name, mi.desc)) {
                    waits.add(key + " -> " + mi.owner + "." + mi.name);
                }
            }
            return waits;
        }

        static boolean waits(String owner, String name, String desc) {
            if (owner.equals("java/lang/Object")) {
                return name.equals("wait");
            }
            if (owner.equals("java/lang/Thread")) {
                return name.equals("join") || name.equals("sleep") || name.equals("onSpinWait") || name.equals("yield");
            }
            if (owner.startsWith("java/nio/channels/")) {
                return name.equals("lock");
            }
            if (!owner.startsWith("java/util/concurrent/")) {
                return false;
            }
            boolean timed = desc.contains("Ljava/util/concurrent/TimeUnit;");
            return name.equals("lock") || name.equals("lockInterruptibly") || (name.equals("tryLock") && timed)
                    || name.startsWith("await") || name.contains("Await") || name.startsWith("park") || name.startsWith("acquire")
                    || name.equals("take") || (name.equals("put") && owner.contains("Blocking"))
                    || ((name.equals("offer") || name.equals("poll")) && timed)
                    || ((name.equals("get") || name.equals("join")) && (owner.contains("Future") || owner.contains("ForkJoinTask")))
                    || name.equals("exchange") || name.equals("invokeAll") || name.equals("invokeAny");
        }
    }

    /**
     * streamer 的一層呼叫框會不會拿著鎖往下呼叫：synchronized、monitorenter，或直接取得 j.u.c 鎖／號誌、FileChannel 鎖。
     * 框裡的 sleep 只是等時間、不拿著東西，不算。
     */
    static List<String> frameLocks(String key, MethodNode m) {
        List<String> holds = new ArrayList<>();
        if ((m.access & Opcodes.ACC_SYNCHRONIZED) != 0 || countOpcode(m, Opcodes.MONITORENTER) != 0) {
            holds.add(key + " synchronized/monitorenter");
        }
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi
                    && (mi.owner.startsWith("java/util/concurrent/") || mi.owner.startsWith("java/nio/channels/"))
                    && (mi.name.equals("lock") || mi.name.equals("lockInterruptibly") || mi.name.equals("tryLock")
                        || mi.name.startsWith("acquire"))) {
                holds.add(key + " -> " + mi.owner + "." + mi.name);
            }
        }
        return holds;
    }

    static MethodNode declaredMethod(ClassNode cn, String name, String desc) {
        if (cn == null) {
            return null;
        }
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    /**
     * PatchInfo 是建置期生成的版本指紋——四個常數必須存在且非空，
     * 且 SIDE 要與本次建置的側別相符（生成失敗或寫錯側別＝log 會說謊，比沒版本號更糟）。
     */
    static boolean patchInfoOk(Path distJava, String expectedSide) throws Exception {
        ClassNode cn = classNode(distJava, "zombie/mdc/PatchInfo");
        for (String name : new String[]{"SIDE", "VERSION", "BUILT", "JAR"}) {
            String v = cn.fields.stream()
                    .filter(f -> f.name.equals(name) && f.desc.equals("Ljava/lang/String;"))
                    .map(f -> f.value instanceof String s ? s : null)
                    .findFirst().orElse(null);
            if (v == null || v.isBlank()) {
                return false;
            }
            if (name.equals("SIDE") && !v.equals(expectedSide)) {
                return false;
            }
        }
        return true;
    }

    static boolean hasField(ClassNode cn, String name, String desc) {
        return cn.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc));
    }

    /** 讀 jar 內 vanilla class 的完整 ClassNode（欄位契約守門用）。 */
    static ClassNode classNodeFromJar(Path jar, String cls) throws Exception {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            java.util.jar.JarEntry entry = jf.getJarEntry(cls + ".class");
            if (entry == null) {
                throw new IllegalStateException("jar 內找不到 " + cls + ".class（遊戲版本結構已變？）");
            }
            byte[] bytes = jf.getInputStream(entry).readAllBytes();
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            return cn;
        }
    }

    /** head-call 全序鎖：方法首兩條真指令＝aload_0；invokestatic helper，且該 helper 呼叫全方法恰一次。 */
    static boolean headCallOk(MethodNode m, String owner, String name, String desc) {
        AbstractInsnNode[] h = firstReal(m, 2);
        return h[0] instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0
                && h[1] instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)
                && countExactCalls(m, Opcodes.INVOKESTATIC, owner, name, desc) == 1;
    }

    /** 多 slot head-call 全序鎖（W20 起）：首 N 條真指令＝依序 aload 各 slot；接 invokestatic helper 恰一次。 */
    static boolean headCallSlotsOk(MethodNode m, String owner, String name, String desc, int... slots) {
        AbstractInsnNode[] h = firstReal(m, slots.length + 1);
        for (int i = 0; i < slots.length; i++) {
            if (!(h[i] instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ALOAD || v.var != slots[i]) {
                return false;
            }
        }
        return h[slots.length] instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)
                && countExactCalls(m, Opcodes.INVOKESTATIC, owner, name, desc) == 1;
    }

    /**
     * W52 名稱資料流：唯一 getUTF 緊接 ASTORE 4；兩個 IConnection.setUserName(I,String) 前一條都是 ALOAD 4；
     * slot 4 只另外被寫一次（stage 2 的 getUserName(I) 結果）——改道 getUTF 就等於決定兩個分支寫入的名稱。
     */
    static boolean coopNameFlowOk(MethodNode m, String readerCls, String connItf) {
        int utf = 0;
        int writes = 0;
        int stores = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(readerCls) && mi.name.equals("getUTF")) {
                utf++;
                if (!(nextReal(in) instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ASTORE || v.var != 4) {
                    return false;
                }
            } else if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEINTERFACE
                    && mi.owner.equals(connItf) && mi.name.equals("setUserName") && mi.desc.equals("(ILjava/lang/String;)V")) {
                writes++;
                if (!(prevReal(in) instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ALOAD || v.var != 4) {
                    return false;
                }
            } else if (in instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE && v.var == 4) {
                stores++;
            }
        }
        return utf == 1 && writes == 2 && stores == 2;
    }

    /** 方法內 PUTFIELD owner.name 恰 1 處，且寫入值直接來自 ALOAD slot。 */
    static boolean putFieldFromLocal(MethodNode m, String owner, String name, int slot) {
        int puts = 0;
        boolean fromSlot = false;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                    && fi.owner.equals(owner) && fi.name.equals(name)) {
                puts++;
                fromSlot = prevReal(in) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == slot;
            }
        }
        return puts == 1 && fromSlot;
    }

    /**
     * tail-call 全序鎖（W10-C 起）：每個 RETURN 的前兩條真指令＝aload_0；invokestatic helper，
     * 且該 helper 呼叫數＝RETURN 數（每個出口恰一次）。
     */
    static boolean tailCallOk(MethodNode m, String owner, String name, String desc) {
        int returns = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            returns++;
            AbstractInsnNode call = prevReal(in);
            AbstractInsnNode load = call == null ? null : prevReal(call);
            if (!(call instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKESTATIC
                    || !mi.owner.equals(owner) || !mi.name.equals(name) || !mi.desc.equals(desc)
                    || !(load instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ALOAD || v.var != 0) {
                return false;
            }
        }
        return returns > 0 && countExactCalls(m, Opcodes.INVOKESTATIC, owner, name, desc) == returns;
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
            } else if (in instanceof IntInsnNode push && push.operand == value
                    && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH)) {
                // 小整數常數編碼成 bipush/sipush 而非 ldc（例：isChunksFilled 的 bipush 20）
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

    /**
     * 全 jar 呼叫點普查：計數整個 jar（不限 zombie/ 前綴）對 owner.name:desc 的
     * INVOKESTATIC 呼叫。W8 用它把 SafeWrite 呼叫點總數釘死——PZ 新增寫檔路徑＝
     * 出現閘門外的寫入＝建置失敗。搭配逐類分佈斷言堵「舊點消失＋新點出現互相抵銷」
     * 的 false-green（codex 審查修正）。
     */
    static int jarWideCallsiteCensus(Path jar, String owner, String name, String desc) throws Exception {
        return jarWideCallsiteCensus(jar, Opcodes.INVOKESTATIC, owner, name, desc);
    }

    /** opcode 參數版（W9 需要 INVOKEVIRTUAL 的 SaveLoadedChunk 序列化者清冊）。 */
    static int jarWideCallsiteCensus(Path jar, int opcode, String owner, String name, String desc) throws Exception {
        int count = 0;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(zf.getInputStream(e)).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode m : cn.methods) {
                    count += countExactCalls(m, opcode, owner, name, desc);
                }
            }
        }
        return count;
    }

    /** 真指令序中第一個符合的呼叫位置（1 起算）；不存在＝MAX_VALUE（順序斷言自然失敗）。 */
    static int firstCallIndex(MethodNode m, int opcode, String owner, String name, String desc) {
        int i = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() < 0) {
                continue;
            }
            i++;
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == opcode
                    && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 真指令序中<b>最後</b>一個符合的呼叫位置（1 起算）；不存在＝MIN_VALUE。
     * 用於「所有分支都必須先於 X」這類時序鎖（取最晚者比較才涵蓋每一條分支）。
     */
    static int lastCallIndex(MethodNode m, int opcode, String owner, String name, String desc) {
        int i = 0;
        int last = Integer.MIN_VALUE;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() < 0) {
                continue;
            }
            i++;
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == opcode
                    && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)) {
                last = i;
            }
        }
        return last;
    }

    /** 是否存在「GETSTATIC owner.name 之後緊接指定跳轉 opcode」的指令對（W8 hot-save 閘方向鎖）。 */
    static boolean existsFieldReadThenJump(MethodNode m, String owner, String name, int jumpOpcode) {
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC
                    && fi.owner.equals(owner) && fi.name.equals(name)) {
                AbstractInsnNode next = nextReal(in);
                if (next != null && next.getOpcode() == jumpOpcode) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否存在「int 常數（bipush/sipush/iconst_N）之後 window 條真指令內出現
     * owner.name 呼叫」的語境（W8 格式 offset 鎖：17→CRC32.update、5→ByteBuffer.position）。
     */
    static boolean existsConstThenCall(MethodNode m, int constVal, String callOwner, String callName, int window) {
        for (AbstractInsnNode in : m.instructions) {
            boolean hit = (in instanceof IntInsnNode ii && ii.operand == constVal)
                    || (constVal >= -1 && constVal <= 5 && in.getOpcode() == Opcodes.ICONST_0 + constVal);
            if (!hit) {
                continue;
            }
            AbstractInsnNode cur = in;
            for (int step = 0; step < window && cur != null; step++) {
                cur = nextReal(cur);
                if (cur instanceof MethodInsnNode mi && mi.owner.equals(callOwner) && mi.name.equals(callName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 方法體全序鎖：真指令（跳過 label／line／frame 等虛節點）的 opcode 序列必須與 want
     * <b>完全一致</b>，長度也要相同——比 matchHead 的前綴比對嚴格，用於本來就只有數條
     * 指令、任何漂移都該讓建置失敗的短方法（W7）。
     */
    static boolean matchOpcodeSeq(MethodNode m, int[] want) {
        int i = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() < 0) {
                continue;
            }
            if (i >= want.length || in.getOpcode() != want[i]) {
                return false;
            }
            i++;
        }
        return i == want.length;
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

    /**
     * W49 離線補算根治＋W50 掛鉤屠體存檔（docs/patches.md 2bm／2bn）。存在理由各自釘在原版 jar 上（TIS 修好時紅＝重估），
     * 手術以「原版文字置換後與 dist 逐字相同」鎖同形改道。
     */
    static int checkAnimalCatchUpAndHookSave(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String animal = "zombie/characters/animals/IsoAnimal";
        String data = "zombie/characters/animals/datas/AnimalData";
        String probe = "zombie/mdc/AnimalAwayProbe";
        String saveHelper = "zombie/characters/animals/MdcAnimalSave";
        String cellHelper = "zombie/characters/animals/MdcAnimalCellSave";
        String cell = "zombie/characters/animals/AnimalCell";
        String chunk = "zombie/characters/animals/AnimalChunk";
        String virtual = "zombie/characters/animals/VirtualAnimal";
        String worker = "zombie/characters/animals/AnimalManagerWorker";
        String hutch = "zombie/iso/objects/IsoHutch";
        String buffer = "java/nio/ByteBuffer";

        // A1：原版 fromWorker 在 getZone() 為 null（connectedDZone 不存檔）時補 0。
        String fromWorker = methodText(methodFromJar(jar, "zombie/characters/animals/AnimalManagerMain", "fromWorker",
                "(Ljava/util/ArrayList;)V"));
        int zoneAt = fromWorker.indexOf("INVOKEVIRTUAL " + animal + ".getZone ()Lzombie/iso/areas/DesignationZone;");
        int nonNullAt = fromWorker.indexOf("IFNONNULL", zoneAt);
        int zeroAt = fromWorker.indexOf("ICONST_0", nonNullAt);
        int lastSeenAt = fromWorker.indexOf("GETFIELD zombie/iso/areas/DesignationZone.hourLastSeen", zeroAt);
        failed += check("W49 vanilla fromWorker：getZone() 為 null 時補 0，否則以 hourLastSeen 推算",
                zoneAt >= 0 && nonNullAt > zoneAt && zeroAt > nonNullAt && lastSeenAt > zeroAt);

        // A3：原版存檔的時鐘欄位寫存檔當下，不讀動物時鐘。
        MethodNode vSave = methodFromJar(jar, animal, "save", "(Ljava/nio/ByteBuffer;ZZ)V");
        MethodNode pSave = method(distJava, animal, "save", "(Ljava/nio/ByteBuffer;ZZ)V");
        failed += check("W49 vanilla IsoAnimal.save 時鐘欄位寫存檔當下（getTimeInMillis 恰 1、不讀 timeSinceLastUpdate）",
                countExactCalls(vSave, Opcodes.INVOKEVIRTUAL, "zombie/util/PZCalendar", "getTimeInMillis", "()J") == 1
                && countFieldTouches(vSave, animal, "timeSinceLastUpdate") == 0);
        failed += check("W49 IsoAnimal.save 唯一 getTimeInMillis 同形改道 clockToWrite，其餘指令與 frames 保留",
                methodText(vSave).replace("INVOKEVIRTUAL zombie/util/PZCalendar.getTimeInMillis ()J",
                        "INVOKESTATIC " + saveHelper + ".clockToWrite (Lzombie/util/PZCalendar;)J")
                        .equals(methodText(pSave)));

        // A4：原版補算一次性把 hoursSurvived 設成 新age×24、只在日曆午夜 growUp；載入中的成長公式是 helper 的複本。
        MethodNode vAway = methodFromJar(jar, animal, "updateStatsAway", "(I)V");
        MethodNode pAway = method(distJava, animal, "updateStatsAway", "(I)V");
        AbstractInsnNode setHours = firstCall(vAway, Opcodes.INVOKEVIRTUAL, animal, "setHoursSurvived", "(D)V");
        AbstractInsnNode i2d = prevRealOrNull(setHours);
        AbstractInsnNode imul = prevRealOrNull(i2d);
        AbstractInsnNode by24 = prevRealOrNull(imul);
        AbstractInsnNode growUp = firstCall(vAway, Opcodes.INVOKEVIRTUAL, data, "growUp", "(Z)V");
        AbstractInsnNode midnight = prevRealOrNull(prevRealOrNull(prevRealOrNull(prevRealOrNull(growUp))));
        failed += check("W49 vanilla updateStatsAway：一次性 setHoursSurvived(新age×24)、growUp 在 realHour==0 分支內、四個呼叫各恰 1",
                i2d != null && i2d.getOpcode() == Opcodes.I2D && imul != null && imul.getOpcode() == Opcodes.IMUL
                && by24 instanceof IntInsnNode bi && bi.operand == 24
                && midnight != null && midnight.getOpcode() == Opcodes.IFNE
                && countExactCalls(vAway, Opcodes.INVOKEVIRTUAL, animal, "setHoursSurvived", "(D)V") == 1
                && countExactCalls(vAway, Opcodes.INVOKEVIRTUAL, data, "setAge", "(I)V") == 1
                && countExactCalls(vAway, Opcodes.INVOKEVIRTUAL, data, "hourGrow", "(Z)V") == 1
                && countExactCalls(vAway, Opcodes.INVOKEVIRTUAL, data, "growUp", "(Z)V") == 1);
        String awayHead = "    ALOAD 0\n    ILOAD 1\n    INVOKESTATIC " + probe + ".entryHours (L" + animal + ";I)I\n"
                + "    ISTORE 1\n";
        String pAwayText = methodText(pAway);
        failed += check("W49 updateStatsAway 頭部 this＋hours→entryHours→istore 1（直接呼叫也受上限約束），"
                        + "四處同形改道累積語意 helper，其餘指令與 frames 保留",
                pAwayText.startsWith(awayHead)
                && realInsnCount(pAway) == realInsnCount(vAway) + 4
                && methodText(vAway)
                        .replace("INVOKEVIRTUAL " + animal + ".setHoursSurvived (D)V",
                                "INVOKESTATIC " + probe + ".accrualSetHoursSurvived (L" + animal + ";D)V")
                        .replace("INVOKEVIRTUAL " + data + ".setAge (I)V",
                                "INVOKESTATIC " + probe + ".accrualSetAge (L" + data + ";I)V")
                        .replace("INVOKEVIRTUAL " + data + ".hourGrow (Z)V",
                                "INVOKESTATIC " + probe + ".accrualHourGrow (L" + data + ";Z)V")
                        .replace("INVOKEVIRTUAL " + data + ".growUp (Z)V",
                                "INVOKESTATIC " + probe + ".accrualGrowUp (L" + data + ";Z)V")
                        .equals(pAwayText.substring(Math.min(awayHead.length(), pAwayText.length()))));
        failed += check("W49 vanilla AnimalData.update 載入中成長：age < daysSurvived 時 age＝daysSurvived＋(mod−1)、"
                        + "hoursSurvived＝age×24（accrualHourGrow 照抄這段）",
                containsRun(callNames(methodFromJar(jar, data, "update", "()V")),
                        data + ".getAge", data + ".getDaysSurvived", data + ".getAgeGrowModifier",
                        data + ".getDaysSurvived", "java/lang/Float.valueOf", "java/lang/Float.intValue",
                        data + ".setAge", data + ".getAge", animal + ".setHoursSurvived"));

        // 雞舍內每小時推進 hoursSurvived 但不刷新動物時鐘。
        MethodNode vInside = methodFromJar(jar, hutch, "updateAnimalInside", "(L" + animal + ";Z)V");
        MethodNode pInside = method(distJava, hutch, "updateAnimalInside", "(L" + animal + ";Z)V");
        failed += check("W49 vanilla 雞舍內 setHoursSurvived 恰 2、不碰動物時鐘",
                countExactCalls(vInside, Opcodes.INVOKEVIRTUAL, animal, "setHoursSurvived", "(D)V") == 2
                && countFieldTouches(vInside, animal, "timeSinceLastUpdate") == 0
                && countExactCalls(vInside, Opcodes.INVOKEVIRTUAL, animal, "updateLastTimeSinceUpdate", "()V") == 0);
        failed += check("W49 雞舍兩處 setHoursSurvived 同形改道 hutchHoursSurvived，其餘指令與 frames 保留",
                methodText(vInside).replace("INVOKEVIRTUAL " + animal + ".setHoursSurvived (D)V",
                        "INVOKESTATIC " + probe + ".hutchHoursSurvived (L" + animal + ";D)V").equals(methodText(pInside)));

        // W50 存在理由：原版只在 hook 參照有效時寫 onHook=1＋座標；尾端恰為 petTimer／wild／onlineID；
        // load 讀到 onHook 後接著讀三個 int 到 attachBackToHook。
        List<String> saveCalls = callNames(vSave);
        int n = saveCalls.size();
        failed += check("W50 vanilla IsoAnimal.save 以 hook 參照決定 onHook，尾端恰為 putFloat(petTimer)→put(wild)→putShort(onlineID)",
                countFieldTouches(vSave, animal, "hook") >= 1
                && countExactCalls(vSave, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoButcherHook", "getSquare",
                        "()Lzombie/iso/IsoGridSquare;") >= 1
                && n >= 5 && saveCalls.subList(n - 5, n).equals(List.of(buffer + ".putFloat", animal + ".isWild",
                        buffer + ".put", animal + ".getOnlineID", buffer + ".putShort"))
                && countExactFields(vSave, Opcodes.GETFIELD, animal, "petTimer", "F") == 1
                && lastReal(vSave).getOpcode() == Opcodes.RETURN);
        MethodNode vLoad = methodFromJar(jar, animal, "load", "(Ljava/nio/ByteBuffer;IZ)V");
        List<String> loadEvents = events(vLoad);
        failed += check("W50 vanilla IsoAnimal.load：setOnHook 後 onHook 為真即讀三個 int 到 attachBackToHookX／Y／Z",
                containsRun(loadEvents, "C:" + animal + ".setOnHook", "C:" + animal + ".isOnHook",
                        "C:" + buffer + ".getInt", "F:" + animal + ".attachBackToHookX",
                        "C:" + buffer + ".getInt", "F:" + animal + ".attachBackToHookY",
                        "C:" + buffer + ".getInt", "F:" + animal + ".attachBackToHookZ"));

        // W50 手術：apop 只經 VirtualAnimal.save 寫動物，其唯一 IsoAnimal.save 改道。
        MethodNode vVirtual = methodFromJar(jar, virtual, "save", "(Ljava/nio/ByteBuffer;)V");
        MethodNode pVirtual = method(distJava, virtual, "save", "(Ljava/nio/ByteBuffer;)V");
        MethodNode vChunk1 = methodFromJar(jar, chunk, "save", "(Ljava/nio/ByteBuffer;)V");
        MethodNode vChunk2 = methodFromJar(jar, chunk, "save", "(Ljava/nio/ByteBuffer;Ljava/util/ArrayList;)V");
        failed += check("W50 apop 只經 VirtualAnimal.save 寫動物（全 jar 呼叫者恰為 AnimalChunk 的 3 處、AnimalChunk 零 IsoAnimal.save）",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, virtual, "save", "(Ljava/nio/ByteBuffer;)V") == 3
                && countExactCalls(vChunk1, Opcodes.INVOKEVIRTUAL, virtual, "save", "(Ljava/nio/ByteBuffer;)V") == 1
                && countExactCalls(vChunk2, Opcodes.INVOKEVIRTUAL, virtual, "save", "(Ljava/nio/ByteBuffer;)V") == 2
                && countCalls(vChunk1, animal, "save") + countCalls(vChunk2, animal, "save") == 0);
        failed += check("W50 VirtualAnimal.save 唯一 IsoAnimal.save 同形改道 MdcAnimalSave.save，其餘指令與 frames 保留",
                countExactCalls(vVirtual, Opcodes.INVOKEVIRTUAL, animal, "save", "(Ljava/nio/ByteBuffer;Z)V") == 1
                && methodText(vVirtual).replace("INVOKEVIRTUAL " + animal + ".save (Ljava/nio/ByteBuffer;Z)V",
                        "INVOKESTATIC " + saveHelper + ".save (L" + animal + ";Ljava/nio/ByteBuffer;Z)V")
                        .equals(methodText(pVirtual)));
        MethodNode vReattach = methodFromJar(jar, animal, "reattachBackToHook", "()V");
        MethodNode pReattach = method(distJava, animal, "reattachBackToHook", "()V");
        failed += check("W50 vanilla reattachBackToHook 掛回成功才清座標（afterReattach 以座標非零判斷未掛回）",
                countExactCalls(vReattach, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoButcherHook", "reattachAnimal",
                        "(L" + animal + ";)V") == 1
                && countExactFields(vReattach, Opcodes.PUTFIELD, animal, "attachBackToHookX", "I") == 1
                && countExactFields(vReattach, Opcodes.PUTFIELD, animal, "attachBackToHookY", "I") == 1
                && countExactFields(vReattach, Opcodes.PUTFIELD, animal, "attachBackToHookZ", "I") == 1
                && firstFieldIndex(vReattach, Opcodes.PUTFIELD, animal, "attachBackToHookX", "I")
                        > firstCallIndex(vReattach, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoButcherHook", "reattachAnimal",
                                "(L" + animal + ";)V"));
        failed += check("W50 reattachBackToHook 每個 RETURN（5）前 afterReattach、真指令恰 +10",
                countOpcode(vReattach, Opcodes.RETURN) == 5
                && tailCallOk(pReattach, saveHelper, "afterReattach", "(L" + animal + ";)V")
                && realInsnCount(pReattach) == realInsnCount(vReattach) + 10);

        // W37 重試不重複寫出：原版 saveRealAnimals 只追加快照，AnimalCell.save(ByteBuffer) 寫完全部 chunk 後才清。
        MethodNode vReal = methodFromJar(jar, worker, "saveRealAnimals", "(Ljava/util/ArrayList;)V");
        MethodNode pReal = method(distJava, worker, "saveRealAnimals", "(Ljava/util/ArrayList;)V");
        MethodNode vCellBuf = methodFromJar(jar, cell, "save", "(Ljava/nio/ByteBuffer;)V");
        failed += check("W50 存在理由：saveRealAnimals 只追加（零 clear）、AnimalCell.save 的清空在最後一次 chunk 寫出之後",
                countExactCalls(vReal, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V") == 0
                && countExactCalls(vReal, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z") >= 1
                && lastFieldIndex(vCellBuf, Opcodes.PUTFIELD, cell, "saveRealAnimalHack", "Ljava/util/ArrayList;")
                        > lastCallIndex(vCellBuf, Opcodes.INVOKEVIRTUAL, chunk, "save",
                                "(Ljava/nio/ByteBuffer;Ljava/util/ArrayList;)V"));
        failed += check("W50 saveRealAnimals 頭部 aload_0→clearStaleRealSnapshots、真指令恰 +2",
                headCallSlotsOk(pReal, cellHelper, "clearStaleRealSnapshots", "(L" + worker + ";)V", 0)
                && realInsnCount(pReal) == realInsnCount(vReal) + 2);
        return failed;
    }

    /**
     * W56 補算等畜牧區就緒（docs/patches.md 2bt）。存在理由釘在原版 jar 上（TIS 修好時紅＝重估）：槽卸載時移出畜牧區、
     * 進世界時不登記，只有 check()（兩角都載入才跑）重建；從離線載入的動物在 fromMeta 期間整段不更新（延後時凍結的依據）；
     * unloaded() 先把時鐘寫成現在（延後中卸載要還原的原因）。手術以「原版文字置換後與 dist 逐字相同」鎖同形改道，
     * 三個 class 其餘方法不變。
     */
    static int checkCatchUpDefer(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String animal = "zombie/characters/animals/IsoAnimal";
        String probe = "zombie/mdc/AnimalAwayProbe";
        String dza = "zombie/iso/areas/DesignationZoneAnimal";
        String troughCls = "zombie/iso/objects/IsoFeedingTrough";
        String worldCls = "zombie/iso/IsoWorld";
        String popCls = "zombie/characters/animals/AnimalPopulationManager";
        String chunkArg = "(Lzombie/iso/IsoChunk;)V";

        MethodNode vTroughRemove = methodFromJar(jar, troughCls, "removeFromWorld", "()V");
        MethodNode vTroughAdd = methodFromJar(jar, troughCls, "addToWorld", "()V");
        MethodNode vTroughCtor = methodFromJar(jar, troughCls, "<init>",
                "(Lzombie/iso/IsoGridSquare;Ljava/lang/String;Lzombie/iso/IsoGridSquare;)V");
        failed += check("W56 vanilla 槽 removeFromWorld 移出 zone.troughs；addToWorld 不登記（checkZone 全 jar 恰 2、都在建構子）",
                countExactFields(vTroughRemove, Opcodes.GETFIELD, dza, "troughs", "Ljava/util/ArrayList;") == 1
                && countExactCalls(vTroughRemove, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "remove",
                        "(Ljava/lang/Object;)Z") == 1
                && countExactCalls(vTroughAdd, Opcodes.INVOKEVIRTUAL, troughCls, "checkZone", "()V") == 0
                && countExactCalls(vTroughCtor, Opcodes.INVOKEVIRTUAL, troughCls, "checkZone", "()V") == 2
                && jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, troughCls, "checkZone", "()V") == 2);
        MethodNode vCheckNode = methodFromJar(jar, dza, "check", "()V");
        AbstractInsnNode[] checkHead = firstReal(vCheckNode, 4);
        failed += check("W56 vanilla check()：開頭 isFullyStreamed() 為假就 return，之後才重建 troughs／foodOnGround／nearWaterSquares",
                checkHead[0] != null && checkHead[0].getOpcode() == Opcodes.ALOAD
                && checkHead[1] instanceof MethodInsnNode fs && fs.name.equals("isFullyStreamed") && fs.desc.equals("()Z")
                && checkHead[2] != null && checkHead[2].getOpcode() == Opcodes.IFNE
                && checkHead[3] != null && checkHead[3].getOpcode() == Opcodes.RETURN
                && countFieldTouches(vCheckNode, dza, "troughs") > 0
                && countFieldTouches(vCheckNode, dza, "foodOnGround") > 0
                && countFieldTouches(vCheckNode, dza, "nearWaterSquares") > 0);

        List<AbstractInsnNode> head = new ArrayList<>();
        for (AbstractInsnNode in : methodFromJar(jar, animal, "updateInternal", "()V").instructions) {
            if (in.getOpcode() >= 0 && head.size() < 4) {
                head.add(in);
            }
        }
        failed += check("W56 vanilla updateInternal 開頭 fromMeta 為真就整段 return（延後期間凍結、也不刷新時鐘）",
                head.size() == 4 && head.get(0).getOpcode() == Opcodes.ALOAD
                && head.get(1) instanceof FieldInsnNode fm && fm.getOpcode() == Opcodes.GETFIELD
                && fm.owner.equals(animal) && fm.name.equals("fromMeta")
                && head.get(2).getOpcode() == Opcodes.IFEQ && head.get(3).getOpcode() == Opcodes.RETURN);
        MethodNode vAnimalUpdate = methodFromJar(jar, animal, "update", "()V");
        failed += check("W56 vanilla IsoAnimal.update 在 updateInternal 外不碰時鐘、不跑 AnimalData.update；AnimalData.update 全 jar 恰 1（updateInternal）",
                countFieldTouches(vAnimalUpdate, animal, "timeSinceLastUpdate") == 0
                && countExactCalls(vAnimalUpdate, Opcodes.INVOKEVIRTUAL, animal, "updateLastTimeSinceUpdate", "()V") == 0
                && countExactCalls(vAnimalUpdate, Opcodes.INVOKEVIRTUAL, "zombie/characters/animals/datas/AnimalData",
                        "update", "()V") == 0
                && jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, "zombie/characters/animals/datas/AnimalData",
                        "update", "()V") == 1);
        String vChunkRemove = methodText(methodFromJar(jar, "zombie/iso/IsoChunk", "removeFromWorld", "()V"));
        int handoffAt = vChunkRemove.indexOf("INVOKEVIRTUAL " + popCls + ".removeChunkFromWorld");
        int objRemoveAt = vChunkRemove.indexOf("INVOKEVIRTUAL zombie/iso/IsoMovingObject.removeFromWorld ()V");
        failed += check("W56 vanilla IsoChunk.removeFromWorld 先交給 AnimalPopulationManager（unloaded）再逐一 removeFromWorld",
                handoffAt >= 0 && objRemoveAt > handoffAt);
        String leaveHead = "    ALOAD 0\n    INVOKESTATIC " + probe + ".leavingWorld (L" + animal + ";)V\n";
        String pRemoveText = methodText(method(distJava, animal, "removeFromWorld", "()V"));
        failed += check("W56 IsoAnimal.removeFromWorld 頭部 aload_0→leavingWorld，其餘指令與 frames 保留",
                pRemoveText.startsWith(leaveHead) && methodText(methodFromJar(jar, animal, "removeFromWorld", "()V"))
                        .equals(pRemoveText.substring(leaveHead.length())));
        String vFromWorker = methodText(methodFromJar(jar, "zombie/characters/animals/AnimalManagerMain", "fromWorker",
                "(Ljava/util/ArrayList;)V"));
        int fromMetaAt = vFromWorker.indexOf("PUTFIELD " + animal + ".fromMeta : Z");
        failed += check("W56 vanilla fromWorker 先設 fromMeta 再呼叫 updateStatsAway",
                fromMetaAt >= 0 && vFromWorker.indexOf("INVOKEVIRTUAL " + animal + ".updateStatsAway (I)V") > fromMetaAt);
        MethodNode vUnloaded = methodFromJar(jar, animal, "unloaded", "()V");
        List<String> unloadedCalls = callNames(vUnloaded);
        failed += check("W56 vanilla unloaded() 一開始就把時鐘寫成現在；全 jar 恰兩處呼叫（removeChunkFromWorld、virtualizeAnimal）",
                unloadedCalls.size() >= 3 && unloadedCalls.subList(0, 3).equals(List.of("zombie/GameTime.getInstance",
                        "zombie/GameTime.getCalender", "zombie/util/PZCalendar.getTimeInMillis"))
                && countExactFields(vUnloaded, Opcodes.PUTFIELD, animal, "timeSinceLastUpdate", "J") == 1
                && jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, animal, "unloaded", "()V") == 2);

        failed += check("W56 DesignationZone.update() 全 jar 恰一處呼叫（IsoWorld.update）",
                jarWideCallsiteCensus(jar, Opcodes.INVOKESTATIC, "zombie/iso/areas/DesignationZone", "update", "()V") == 1);
        failed += check("W56 IsoWorld.update 唯一 DesignationZone.update 同形改道 zoneUpdate，其餘指令與 frames 保留",
                methodText(methodFromJar(jar, worldCls, "update", "()V"))
                        .replace("INVOKESTATIC zombie/iso/areas/DesignationZone.update ()V",
                                "INVOKESTATIC " + probe + ".zoneUpdate ()V")
                        .equals(methodText(method(distJava, worldCls, "update", "()V"))));
        failed += check("W56 removeChunkFromWorld 唯一 unloaded() 同形改道 unloaded，其餘指令與 frames 保留",
                methodText(methodFromJar(jar, popCls, "removeChunkFromWorld", chunkArg))
                        .replace("INVOKEVIRTUAL " + animal + ".unloaded ()V",
                                "INVOKESTATIC " + probe + ".unloaded (L" + animal + ";)V")
                        .equals(methodText(method(distJava, popCls, "removeChunkFromWorld", chunkArg))));
        failed += check("W56 槽 addToWorld 兩個 checkOverlayAfterAnimalEat 同形改道 troughAddedToWorld，其餘指令與 frames 保留",
                countExactCalls(vTroughAdd, Opcodes.INVOKEVIRTUAL, troughCls, "checkOverlayAfterAnimalEat", "()V") == 2
                && methodText(vTroughAdd)
                        .replace("INVOKEVIRTUAL " + troughCls + ".checkOverlayAfterAnimalEat ()V",
                                "INVOKESTATIC " + probe + ".troughAddedToWorld (L" + troughCls + ";)V")
                        .equals(methodText(method(distJava, troughCls, "addToWorld", "()V"))));
        String[][] targets = {{worldCls, "update", "()V"}, {popCls, "removeChunkFromWorld", chunkArg},
                {troughCls, "addToWorld", "()V"}};
        for (String[] t : targets) {
            ClassNode patched = classNode(distJava, t[0]);
            int changed = 0;
            int seen = 0;
            for (MethodNode original : classNodeFromJar(jar, t[0]).methods) {
                // W95 的 a0 也掛在 IsoWorld（updateThread），由 checkAnimalCuts 鎖住
                if (original.name.equals(t[1]) && original.desc.equals(t[2])
                        || t[0].equals(worldCls) && original.name.equals("updateThread") && original.desc.equals("()V")) {
                    continue;
                }
                seen++;
                String want = methodText(original);
                boolean same = patched.methods.stream().anyMatch(m -> m.name.equals(original.name)
                        && m.desc.equals(original.desc) && methodText(m).equals(want));
                if (!same) {
                    changed++;
                }
            }
            failed += check("W56 " + t[0] + " 非目標方法逐字不變（" + seen + " 個）", seen > 0 && changed == 0);
        }
        return failed;
    }

    /** 方法內依序的呼叫（owner.name）。 */
    static List<String> callNames(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi) {
                out.add(mi.owner + "." + mi.name);
            }
        }
        return out;
    }

    /** 方法內依序的呼叫（C:owner.name）與欄位寫入（F:owner.name）。 */
    static List<String> events(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi) {
                out.add("C:" + mi.owner + "." + mi.name);
            } else if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD) {
                out.add("F:" + fi.owner + "." + fi.name);
            }
        }
        return out;
    }

    /** list 內是否有一段連續元素恰為 run。 */
    static boolean containsRun(List<String> list, String... run) {
        return java.util.Collections.indexOfSubList(list, List.of(run)) >= 0;
    }

    static AbstractInsnNode firstCall(MethodNode m, int opcode, String owner, String name, String desc) {
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == opcode
                    && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)) {
                return in;
            }
        }
        return null;
    }

    /** 前一條真指令；輸入為 null 或已到開頭時回 null（順序斷言自然失敗，不會拋例外）。 */
    static AbstractInsnNode prevRealOrNull(AbstractInsnNode in) {
        return in == null ? null : prevReal(in);
    }

    static AbstractInsnNode lastReal(MethodNode m) {
        AbstractInsnNode p = m.instructions.getLast();
        while (p != null && p.getOpcode() < 0) {
            p = p.getPrevious();
        }
        return p;
    }

    /** 真指令序中最後一個符合的欄位存取位置（1 起算）；不存在＝MIN_VALUE。 */
    static int lastFieldIndex(MethodNode method, int opcode, String owner, String name, String desc) {
        int index = 0;
        int last = Integer.MIN_VALUE;
        for (AbstractInsnNode in : method.instructions) {
            if (in.getOpcode() < 0) {
                continue;
            }
            index++;
            if (isField(in, opcode, owner, name, desc)) {
                last = index;
            }
        }
        return last;
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

    /**
     * FieldPutWrap 形狀（W45／W47）：原版目標 PUTFIELD 恰 1 且前三條真指令＝NEW type／DUP／INVOKESPECIAL &lt;init&gt;()V；
     * 手術後 PUTFIELD 前緊接 INVOKESTATIC helper、再往前同樣三條。全文比對由 {@link #wrapsStripToVanilla} 負責。
     */
    static boolean putWrapOk(MethodNode vanilla, MethodNode patched, String owner, String field, String newType,
                             String helperOwner, String helperName, String helperDesc) {
        FieldInsnNode vPut = onlyPutField(vanilla, owner, field);
        FieldInsnNode pPut = onlyPutField(patched, owner, field);
        if (vPut == null || pPut == null || !newBefore(vanilla, vPut, newType)) {
            return false;
        }
        AbstractInsnNode wrap = prevReal(pPut);
        return wrap instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(helperOwner) && call.name.equals(helperName) && call.desc.equals(helperDesc)
                && newBefore(patched, call, newType);
    }

    /** 移除 helpers（owner,name,desc）的 INVOKESTATIC 後與原版逐字相同，且每個 helper 恰被移除一次。 */
    static boolean wrapsStripToVanilla(MethodNode vanilla, MethodNode patched, String[][] helpers) {
        MethodNode copy = new MethodNode(Opcodes.ASM9, patched.access, patched.name, patched.desc,
                patched.signature, patched.exceptions.toArray(new String[0]));
        patched.accept(copy);
        int[] removed = new int[helpers.length];
        for (AbstractInsnNode in : copy.instructions.toArray()) {
            for (int i = 0; i < helpers.length; i++) {
                if (in instanceof MethodInsnNode m && m.getOpcode() == Opcodes.INVOKESTATIC && m.owner.equals(helpers[i][0])
                        && m.name.equals(helpers[i][1]) && m.desc.equals(helpers[i][2])) {
                    copy.instructions.remove(in);
                    removed[i]++;
                }
            }
        }
        for (int n : removed) {
            if (n != 1) {
                return false;
            }
        }
        return methodText(copy).equals(methodText(vanilla));
    }

    private static FieldInsnNode onlyPutField(MethodNode m, String owner, String field) {
        FieldInsnNode found = null;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
                    && fi.owner.equals(owner) && fi.name.equals(field)) {
                if (found != null) {
                    return null;
                }
                found = fi;
            }
        }
        return found;
    }

    /**
     * in 之前三條真指令依序為 NEW type、DUP、INVOKESPECIAL type.&lt;init&gt;()V，
     * 且 NEW 到 in 之間沒有合流點（frame 或跳轉目標 label）——否則另一條路徑可能把既有清單送進 PUTFIELD，
     * wrap 會切斷原本的別名。
     */
    private static boolean newBefore(MethodNode m, AbstractInsnNode in, String type) {
        AbstractInsnNode init = prevReal(in);
        AbstractInsnNode dup = init == null ? null : prevReal(init);
        AbstractInsnNode neu = dup == null ? null : prevReal(dup);
        if (!(init instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL
                && c.owner.equals(type) && c.name.equals("<init>") && c.desc.equals("()V")
                && dup != null && dup.getOpcode() == Opcodes.DUP
                && neu instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(type))) {
            return false;
        }
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode x : m.instructions) {
            if (x instanceof JumpInsnNode j) {
                targets.add(j.label);
            } else if (x instanceof TableSwitchInsnNode ts) {
                targets.add(ts.dflt);
                targets.addAll(ts.labels);
            } else if (x instanceof LookupSwitchInsnNode ls) {
                targets.add(ls.dflt);
                targets.addAll(ls.labels);
            }
        }
        for (TryCatchBlockNode tc : m.tryCatchBlocks) {
            targets.add(tc.handler);
        }
        for (AbstractInsnNode x = neu.getNext(); x != in; x = x.getNext()) {
            if (x instanceof FrameNode || (x instanceof LabelNode l && targets.contains(l))) {
                return false;
            }
        }
        return true;
    }

    /** W45 守門負對照（審查反例）：一條路徑 new ArrayList、另一條讀共用清單，兩者在 PUTFIELD 前合流。 */
    private static MethodNode mergeShape(String helperOwner, String helperName, String helperDesc) {
        MethodNode m = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null);
        LabelNode fresh = new LabelNode();
        LabelNode put = new LabelNode();
        InsnList in = m.instructions;
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new VarInsnNode(Opcodes.ILOAD, 1));
        in.add(new JumpInsnNode(Opcodes.IFEQ, fresh));
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, "T", "shared", "Ljava/util/ArrayList;"));
        in.add(new JumpInsnNode(Opcodes.GOTO, put));
        in.add(fresh);
        in.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        in.add(new InsnNode(Opcodes.DUP));
        in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        in.add(put);
        if (helperOwner != null) {
            in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helperOwner, helperName, helperDesc, false));
        }
        in.add(new FieldInsnNode(Opcodes.PUTFIELD, "T", "f", "Ljava/util/ArrayList;"));
        in.add(new InsnNode(Opcodes.RETURN));
        return m;
    }

    /**
     * W45 前提：InventoryItem 本身、全部後代與 jar 內祖先都沒有宣告 equals(Object)／hashCode()——identity 索引與
     * ArrayList.contains 的 equals 比對結果相同（全 jar walk）。TIS 將來覆寫時會紅，須重新評估索引語意。
     */
    static int checkInventoryItemIdentity(Path jar) throws Exception {
        String base = "zombie/inventory/InventoryItem";
        Map<String, String> superOf = new HashMap<>();
        Set<String> declares = new HashSet<>();
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
                        declares.add(cn.name);
                        break;
                    }
                }
            }
        }
        int family = 0;
        int bad = 0;
        for (String cls : superOf.keySet()) {
            String cur = cls;
            while (cur != null && !cur.equals(base)) {
                cur = superOf.get(cur);
            }
            if (cur == null) {
                continue;
            }
            family++;
            if (declares.contains(cls)) {
                System.out.println("  !! equals/hashCode 覆寫: " + cls);
                bad++;
            }
        }
        for (String cur = superOf.get(base); cur != null && superOf.containsKey(cur); cur = superOf.get(cur)) {
            if (declares.contains(cur)) {
                System.out.println("  !! 祖先 equals/hashCode 覆寫: " + cur);
                bad++;
            }
        }
        return check("W45 InventoryItem 繼承鏈（" + family + " 類＋jar 內祖先）無 equals/hashCode 覆寫（全 jar walk）",
                bad == 0 && family > 1);
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

    /** 統計方法內指定 opcode 的出現次數（W16 clearMoving 熱路徑零配置＝零 NEW）。 */
    static int countOpcode(MethodNode m, int opcode) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() == opcode) {
                count++;
            }
        }
        return count;
    }

    static int countInsns(MethodNode m, java.util.function.Predicate<AbstractInsnNode> match) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (match.test(in)) {
                count++;
            }
        }
        return count;
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

    /** 全 class 累計某個精確 callsite 的出現次數（負對照用差值比對，避免絕對零的脆弱性）。 */
    static int classWideCalls(ClassNode cls, int opcode, String owner, String name, String desc) {
        int total = 0;
        for (MethodNode m : cls.methods) {
            total += countExactCalls(m, opcode, owner, name, desc);
        }
        return total;
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

    /** W36：sendToAll 前的參數建構＝GameEntity 型別、排除連線 slot 3、values＝{slot0, slot1, slot2}。 */
    static boolean gameEntitySendToAllArgs(MethodNode m, String owner, String desc) {
        MethodInsnNode call = findExactCall(m, Opcodes.INVOKESTATIC, owner, "sendToAll", desc);
        if (call == null) {
            return false;
        }
        AbstractInsnNode in = call;
        for (int slot = 2; slot >= 0; slot--) {
            in = prevReal(in);
            if (in == null || in.getOpcode() != Opcodes.AASTORE) {
                return false;
            }
            in = prevReal(in);
            if (!isVar(in, Opcodes.ALOAD, slot)) {
                return false;
            }
            in = prevReal(in);
            if (in == null || in.getOpcode() != Opcodes.ICONST_0 + slot) {
                return false;
            }
            in = prevReal(in);
            if (in == null || in.getOpcode() != Opcodes.DUP) {
                return false;
            }
        }
        in = prevReal(in);
        if (!(in instanceof TypeInsnNode t) || t.getOpcode() != Opcodes.ANEWARRAY || !t.desc.equals("java/lang/Object")) {
            return false;
        }
        in = prevReal(in);
        if (in == null || in.getOpcode() != Opcodes.ICONST_3) {
            return false;
        }
        in = prevReal(in);
        if (!isVar(in, Opcodes.ALOAD, 3)) {
            return false;
        }
        in = prevReal(in);
        return isField(in, Opcodes.GETSTATIC, "zombie/network/PacketTypes$PacketType", "GameEntity",
                "Lzombie/network/PacketTypes$PacketType;");
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

    /** GETFIELD 版欄位讀取計數（countFieldReads 是 GETSTATIC 版）。 */
    static int countInstanceFieldReads(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode in : method.instructions) {
            if (in instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner)
                    && field.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    /** 全 jar 欄位存取普查（opcode 指定 GETFIELD／GETSTATIC／PUTFIELD）——fail-closed 耦合鎖用。 */
    static int jarWideFieldReadCensus(Path jar, int opcode, String owner, String name) throws Exception {
        int count = 0;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(zf.getInputStream(e)).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode in : m.instructions) {
                        if (in instanceof FieldInsnNode fi && fi.getOpcode() == opcode
                                && fi.owner.equals(owner) && fi.name.equals(name)) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** 同上，但逐方法計數（鍵＝「class.method」），新增或消失的使用者都看得出來。 */
    static Map<String, Integer> jarWideFieldRefs(Path jar, int opcode, String owner, String name) throws Exception {
        Map<String, Integer> refs = new java.util.TreeMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(zf.getInputStream(e)).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode in : m.instructions) {
                        if (in instanceof FieldInsnNode fi && fi.getOpcode() == opcode
                                && fi.owner.equals(owner) && fi.name.equals(name)) {
                            refs.merge(cn.name + "." + m.name, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return refs;
    }

    /** W17 load callsite 全序：ALOAD0, ALOAD7, ICONST0, 精確 call（恰 1）, POP。 */
    static boolean hutchLoadCallShape(MethodNode method, int opcode, String owner,
                                       String name, String desc) {
        if (countExactCalls(method, opcode, owner, name, desc) != 1) {
            return false;
        }
        MethodInsnNode target = findExactCall(method, opcode, owner, name, desc);
        AbstractInsnNode boolArg = prevReal(target);
        if (boolArg == null || boolArg.getOpcode() != Opcodes.ICONST_0) {
            return false;
        }
        AbstractInsnNode animalArg = prevReal(boolArg);
        if (!isVar(animalArg, Opcodes.ALOAD, 7)) {
            return false;
        }
        AbstractInsnNode next = nextReal(target);
        return isVar(prevReal(animalArg), Opcodes.ALOAD, 0)
                && next != null && next.getOpcode() == Opcodes.POP;
    }

    /**
     * W17 複製的 vanilla success contract。105 是 42.20.3 此方法的完整真指令數；
     * TIS 新增／刪除必要副作用時先 fail-closed，再重驗而非讓 helper 靜默落後。
     */
    static boolean hutchSuccessContract(MethodNode method, String hutchOwner, String animalOwner) {
        String dataOwner = "zombie/characters/animals/datas/AnimalData";
        String putDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
        String animalDesc = "(L" + animalOwner + ";)V";
        int put = firstCallIndex(method, Opcodes.INVOKEVIRTUAL,
                "java/util/HashMap", "put", putDesc);
        int backlink = firstFieldIndex(method, Opcodes.PUTFIELD, animalOwner,
                "hutch", "L" + hutchOwner + ";");
        int hutchPosition = firstCallIndex(method, Opcodes.INVOKEVIRTUAL,
                dataOwner, "setHutchPosition", "(I)V");
        int itemId = firstCallIndex(method, Opcodes.INVOKEVIRTUAL,
                animalOwner, "setItemID", "(I)V");
        int tryRemove = firstCallIndex(method, Opcodes.INVOKEVIRTUAL,
                hutchOwner, "tryRemoveAnimalFromWorld", animalDesc);
        return realInsnCount(method) == 105
                && countExactCalls(method, Opcodes.INVOKESTATIC,
                        "zombie/core/random/Rand", "Next", "(II)I") == 2
                && countExactCalls(method, Opcodes.INVOKEVIRTUAL,
                        "java/util/HashMap", "put", putDesc) == 1
                && countExactFields(method, Opcodes.PUTFIELD, animalOwner,
                        "hutch", "L" + hutchOwner + ";") == 1
                && countExactCalls(method, Opcodes.INVOKEVIRTUAL,
                        dataOwner, "setPreferredHutchPosition", "(I)V") == 2
                && countExactCalls(method, Opcodes.INVOKEVIRTUAL,
                        dataOwner, "setHutchPosition", "(I)V") == 1
                && countExactCalls(method, Opcodes.INVOKEVIRTUAL,
                        animalOwner, "setItemID", "(I)V") == 1
                && countExactCalls(method, Opcodes.INVOKEVIRTUAL,
                        hutchOwner, "tryRemoveAnimalFromWorld", animalDesc) == 1
                && lastCallIndex(method, Opcodes.INVOKEVIRTUAL,
                        dataOwner, "setPreferredHutchPosition", "(I)V") < put
                && put < backlink && backlink < hutchPosition
                && hutchPosition < itemId && itemId < tryRemove;
    }

    /**
     * W26 兩個「server 自發」sync 的語境全序（vanilla 與手術後同形，只差 opcode／target）：
     *   site1：PUTFIELD hutchDirt → ALOAD0 → call    （髒污累加後的即刻廣播）
     *   site2：GETFIELD sendUpdate → IFEQ → ALOAD0 → call，且唯一的 PUTFIELD
     *          animalInsideSize 在其後             （週期／隻數變動廣播）
     * 只數命中數擋不住「數量對但改到 updateAnimalInside 等操作觸發的 sync」——那些一律
     * 不過濾收件人。TIS 搬走髒污累加、拆掉 sendUpdate 分支、或把 size 回寫移到 sync 之前，
     * 本條就紅：「哪些 sync 可以過濾」的前提沒了，必須重估而非改命中數放行。
     */
    static boolean hutchSyncSites(MethodNode m, int opcode, String owner, String name,
                                  String desc, String hutchOwner) {
        ArrayList<AbstractInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (isCall(in, opcode, owner, name, desc)) {
                sites.add(in);
            }
        }
        if (sites.size() != 2) {
            return false;
        }
        for (AbstractInsnNode site : sites) {
            if (!isVar(prevReal(site), Opcodes.ALOAD, 0)) {
                return false;
            }
        }
        AbstractInsnNode dirty = prevReal(prevReal(sites.get(0)));
        AbstractInsnNode branch = prevReal(prevReal(sites.get(1)));
        return isField(dirty, Opcodes.PUTFIELD, hutchOwner, "hutchDirt", "F")
                && branch != null && branch.getOpcode() == Opcodes.IFEQ
                && isField(prevReal(branch), Opcodes.GETFIELD, hutchOwner, "sendUpdate", "Z")
                && countExactFields(m, Opcodes.PUTFIELD, hutchOwner, "animalInsideSize", "B") == 1
                && lastCallIndex(m, opcode, owner, name, desc)
                        < firstFieldIndex(m, Opcodes.PUTFIELD, hutchOwner, "animalInsideSize", "B");
    }

    /** helper 複製的 server 非 remote 分支；忽略 frames/行號，保留接收者與 buffer slot 關係。 */
    static boolean hutchServerBroadcast(MethodNode method) {
        AbstractInsnNode start = null;
        for (AbstractInsnNode in : method.instructions) {
            if (isField(in, Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z")) {
                start = in;
                break;
            }
        }
        if (start == null || !(nextReal(start) instanceof JumpInsnNode server)
                || server.getOpcode() != Opcodes.IFEQ) {
            return false;
        }
        ArrayList<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode in = start; in != null && in != server.label; in = in.getNext()) {
            if (in.getOpcode() >= 0) code.add(in);
        }
        if (code.size() != 29 || !isVar(code.get(2), Opcodes.ILOAD, 1)
                || !(code.get(3) instanceof JumpInsnNode remote) || remote.getOpcode() != Opcodes.IFNE
                || remote.label != server.label
                || !(code.get(7) instanceof VarInsnNode iterator) || iterator.getOpcode() != Opcodes.ASTORE
                || !(code.get(14) instanceof VarInsnNode connection) || connection.getOpcode() != Opcodes.ASTORE
                || !(code.get(17) instanceof VarInsnNode writer) || writer.getOpcode() != Opcodes.ASTORE
                || !(code.get(10) instanceof JumpInsnNode empty) || empty.getOpcode() != Opcodes.IFEQ
                || !(code.get(27) instanceof JumpInsnNode again) || again.getOpcode() != Opcodes.GOTO
                || !(code.get(28) instanceof JumpInsnNode done) || done.getOpcode() != Opcodes.GOTO) {
            return false;
        }
        String udp = "zombie/core/raknet/UdpConnection";
        String packet = "zombie/network/PacketTypes$PacketType";
        String buffer = "(Lzombie/core/network/ByteBufferWriter;)V";
        return isField(code.get(4), Opcodes.GETSTATIC, "zombie/network/GameServer", "udpEngine",
                        "Lzombie/core/raknet/UdpEngine;")
                && isField(code.get(5), Opcodes.GETFIELD, "zombie/core/raknet/UdpEngine", "connections", "Ljava/util/List;")
                && isCall(code.get(6), Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;")
                && isVar(code.get(8), Opcodes.ALOAD, iterator.var)
                && isCall(code.get(9), Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")
                && isVar(code.get(11), Opcodes.ALOAD, iterator.var)
                && isCall(code.get(12), Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;")
                && code.get(13) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(udp)
                && isVar(code.get(15), Opcodes.ALOAD, connection.var)
                && isCall(code.get(16), Opcodes.INVOKEVIRTUAL, udp, "startPacket", "()Lzombie/core/network/ByteBufferWriter;")
                && isField(code.get(18), Opcodes.GETSTATIC, packet, "SyncIsoObject", "L" + packet + ";")
                && isVar(code.get(19), Opcodes.ALOAD, writer.var)
                && isCall(code.get(20), Opcodes.INVOKEVIRTUAL, packet, "doPacket", buffer)
                && isVar(code.get(21), Opcodes.ALOAD, 0)
                && isVar(code.get(22), Opcodes.ALOAD, writer.var)
                && isCall(code.get(23), Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoObject", "syncIsoObjectSend", buffer)
                && isField(code.get(24), Opcodes.GETSTATIC, packet, "SyncIsoObject", "L" + packet + ";")
                && isVar(code.get(25), Opcodes.ALOAD, connection.var)
                && isCall(code.get(26), Opcodes.INVOKEVIRTUAL, packet, "send", "(Lzombie/network/IConnection;)V")
                && nextReal(empty.label) == code.get(28)
                && nextReal(again.label) == code.get(8)
                && isVar(nextReal(done.label), Opcodes.ALOAD, 0)
                && isCall(nextReal(nextReal(done.label)), Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoObject",
                        "flagForHotSave", "()V");
    }

    /** 方法內 NEW 指定型別的次數（W26：豁免名單必須是 WeakHashMap，強引用＝角色永不退役）。 */
    static int countNew(MethodNode m, String type) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW
                    && ti.desc.equals(type)) {
                count++;
            }
        }
        return count;
    }

    /** 全 class 真指令總數（1:1 手術的整類鎖：任何方法被多改一條都會露出來）。 */
    static int classRealInsnCount(ClassNode cls) {
        int count = 0;
        for (MethodNode m : cls.methods) {
            count += realInsnCount(m);
        }
        return count;
    }

    static int countExactFields(MethodNode method, int opcode, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode in : method.instructions) {
            if (isField(in, opcode, owner, name, desc)) {
                count++;
            }
        }
        return count;
    }

    static int firstFieldIndex(MethodNode method, int opcode, String owner, String name, String desc) {
        int index = 0;
        for (AbstractInsnNode in : method.instructions) {
            if (in.getOpcode() < 0) {
                continue;
            }
            index++;
            if (isField(in, opcode, owner, name, desc)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** 方法內對指定 owner 的呼叫總數（任何方法名／opcode；W9 負對照用）。 */
    static int countCallsToOwner(MethodNode m, String owner) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(owner)) {
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

    static boolean isVar(AbstractInsnNode in, int opcode, int var) {
        return in instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == var;
    }

    static boolean isField(AbstractInsnNode in, int opcode, String owner, String name, String desc) {
        return in instanceof FieldInsnNode f && f.getOpcode() == opcode
                && f.owner.equals(owner) && f.name.equals(name) && f.desc.equals(desc);
    }

    static boolean isCall(AbstractInsnNode in, int opcode, String owner, String name, String desc) {
        return in instanceof MethodInsnNode m && m.getOpcode() == opcode
                && m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(desc);
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

    static String methodText(MethodNode method) {
        var textifier = new org.objectweb.asm.util.Textifier();
        method.accept(new org.objectweb.asm.util.TraceMethodVisitor(textifier));
        var output = new java.io.StringWriter();
        textifier.print(new java.io.PrintWriter(output));
        return output.toString();
    }

    /** W48-2：42.20.4 原版 WorldSoundManager.getSoundAnimal 的 methodText SHA-256（索引等價性的依據；變了就要重新核對）。 */
    static final String GET_SOUND_ANIMAL_SHA = "621614eac1cfd27a800f3bb14dd619a3bba7d4494d6deb305b074b2fe26787e1";

    static String sha256Hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 讀 jar 內 class 並略過 debug 資訊，產生的整類文字不含行號、區域變數表與 SourceFile（上游指紋用）。 */
    static String debuglessClassText(Path jar, String cls) throws Exception {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            byte[] bytes = jf.getInputStream(jf.getJarEntry(cls + ".class")).readAllBytes();
            var textifier = new org.objectweb.asm.util.Textifier();
            new ClassReader(bytes).accept(new org.objectweb.asm.util.TraceClassVisitor(null, textifier, null),
                    ClassReader.SKIP_DEBUG);
            var output = new java.io.StringWriter();
            textifier.print(new java.io.PrintWriter(output));
            return output.toString();
        }
    }

    /** 同 {@link #methodText}，但來源 class 以 SKIP_DEBUG 讀入：行號位移不改變結果（上游指紋用）。 */
    static String debuglessMethodText(Path jar, String cls, String name, String desc) throws Exception {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            byte[] bytes = jf.getInputStream(jf.getJarEntry(cls + ".class")).readAllBytes();
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG);
            return methodText(cn.methods.stream()
                    .filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow());
        }
    }

    /** 方法內「真指令」總數（1:1 替換的手術後必須與 vanilla 相同）。 */
    static int realInsnCount(MethodNode m) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() >= 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 指定呼叫落在任何 try-catch 保護範圍內的次數。
     * 用於鎖住「原版 factory 的例外必須原樣外傳」——helper 若把 factory 包進 try，
     * 就有機會在 catch 裡重試或替換結果，而那是三份 review 同時定罪的缺陷形狀。
     * 行為測試在無 ScriptManager 的環境無法讓 factory 拋例外，只有這條結構鎖擋得住回歸。
     */
    static int callsInsideTryRange(MethodNode m, int opcode, String owner, String name, String desc) {
        if (m.tryCatchBlocks == null || m.tryCatchBlocks.isEmpty()) {
            return 0;
        }
        int inside = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (!(in instanceof MethodInsnNode call) || call.getOpcode() != opcode
                    || !call.owner.equals(owner) || !call.name.equals(name) || !call.desc.equals(desc)) {
                continue;
            }
            int at = m.instructions.indexOf(in);
            for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
                if (at >= m.instructions.indexOf(tcb.start) && at < m.instructions.indexOf(tcb.end)) {
                    inside++;
                    break;
                }
            }
        }
        return inside;
    }

    /** W73：42.21.0 原版 IsoThumpable.update()V／IsoObject.getObjectIndex()I 的 debuglessMethodText SHA-256（跳過等價性的依據）。 */
    static final String W73_THUMP_UPDATE_SHA = "f5e87bcfbb5ebe99d9b9530c424f2395381db0c7beb5e9ee4bc5347c254881ce";
    static final String W73_OBJECT_INDEX_SHA = "f82a5485a7a2e36f101e67cda4189ebd3b3f44a1950683bd1d5cd65dc24817a0";

    /**
     * W73 IsoObject 每幀更新閘（docs/patches.md 2ch；掛在 W40／W45 的 IsoCell ClassPatch）。
     * 存在理由：伺服器上 lifeLeft 不是 &gt; -1 的 IsoThumpable，原版 update 只走 getObjectIndex（無寫入）→
     * GameServer.server → getLifeLeft／ldc -1.0f／fcmpl／ifle → return。TIS 改動這條路徑、getObjectIndex／indexOf
     * 的無副作用、或在 IsoThumpable 家族加 equals／子類別時紅＝重評跳過條件，不要直接改釘。
     */
    static int checkIsoObjectGate(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String cell = "zombie/iso/IsoCell";
        String obj = "zombie/iso/IsoObject";
        String thump = "zombie/iso/objects/IsoThumpable";
        String gate = "zombie/mdc/IsoObjectUpdateGate";
        String cellDesc = "(L" + cell + ";)V";
        MethodNode vProcess = methodFromJar(jar, cell, "ProcessIsoObject", "()V");
        AbstractInsnNode vRemoveAll = firstCall(vProcess, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "removeAll", "(Ljava/util/Collection;)Z");
        int returns = 0;
        int nullChecks = 0;
        for (AbstractInsnNode in : vProcess.instructions) {
            if (in.getOpcode() == Opcodes.RETURN) returns++;
            if (in.getOpcode() == Opcodes.IFNONNULL) nullChecks++;
        }
        failed += check("W73 vanilla 前提：ProcessIsoObject 恰 1 個 IsoObject.update()、1 個 ArrayList.removeAll(processIsoObjectRemove)、1 個 null 檢查、單一 RETURN",
                countExactCalls(vProcess, Opcodes.INVOKEVIRTUAL, obj, "update", "()V") == 1
                && countExactCalls(vProcess, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "removeAll", "(Ljava/util/Collection;)Z") == 1
                && prevReal(vRemoveAll) instanceof FieldInsnNode rf && rf.getOpcode() == Opcodes.GETFIELD
                        && rf.owner.equals(cell) && rf.name.equals("processIsoObjectRemove")
                && nullChecks == 1 && returns == 1);

        MethodNode vUpdate = methodFromJar(jar, thump, "update", "()V");
        AbstractInsnNode[] h = firstReal(vUpdate, 7);
        boolean pathOk = h[0] instanceof VarInsnNode h0 && h0.getOpcode() == Opcodes.ALOAD && h0.var == 0
                && isCall(h[1], Opcodes.INVOKEVIRTUAL, thump, "getObjectIndex", "()I")
                && h[2].getOpcode() == Opcodes.ICONST_M1 && h[3].getOpcode() == Opcodes.IF_ICMPNE
                && h[4].getOpcode() == Opcodes.RETURN
                && h[5] instanceof FieldInsnNode sf && sf.getOpcode() == Opcodes.GETSTATIC
                        && sf.owner.equals("zombie/network/GameServer") && sf.name.equals("server")
                && h[6] instanceof JumpInsnNode toServer && toServer.getOpcode() == Opcodes.IFNE;
        if (pathOk) {
            AbstractInsnNode a = nextReal(((JumpInsnNode) h[6]).label);
            AbstractInsnNode b = a == null ? null : nextReal(a);
            AbstractInsnNode c = b == null ? null : nextReal(b);
            AbstractInsnNode d = c == null ? null : nextReal(c);
            AbstractInsnNode e = d == null ? null : nextReal(d);
            pathOk = a instanceof VarInsnNode av && av.getOpcode() == Opcodes.ALOAD && av.var == 0
                    && isCall(b, Opcodes.INVOKEVIRTUAL, thump, "getLifeLeft", "()F")
                    && c instanceof LdcInsnNode l && Float.valueOf(-1.0f).equals(l.cst)
                    && d != null && d.getOpcode() == Opcodes.FCMPL
                    && e instanceof JumpInsnNode skip && skip.getOpcode() == Opcodes.IFLE
                    && nextReal(skip.label) != null && nextReal(skip.label).getOpcode() == Opcodes.RETURN;
        }
        failed += check("W73 vanilla 前提：IsoThumpable.update 伺服器路徑＝getObjectIndex（-1 即 return）→ GameServer.server ifne → "
                        + "getLifeLeft／ldc -1.0f／fcmpl／ifle → return，且整個方法與 42.21.0 逐指令相同（SHA 釘）",
                pathOk && sha256Hex(debuglessMethodText(jar, thump, "update", "()V")).equals(W73_THUMP_UPDATE_SHA));
        AbstractInsnNode[] g = firstReal(methodFromJar(jar, thump, "getLifeLeft", "()F"), 4);
        failed += check("W73 vanilla 前提：getLifeLeft 是單純 getter（aload_0／getfield lifeLeft／freturn）",
                realInsnCount(methodFromJar(jar, thump, "getLifeLeft", "()F")) == 3
                && g[1] instanceof FieldInsnNode gf && gf.owner.equals(thump) && gf.name.equals("lifeLeft")
                && g[2].getOpcode() == Opcodes.FRETURN);
        MethodNode getObjects = methodFromJar(jar, "zombie/iso/IsoGridSquare", "getObjects", "()Lzombie/util/list/PZArrayList;");
        AbstractInsnNode[] go = firstReal(getObjects, 3);
        int indexWrites = 0;
        String pz = "zombie/util/list/PZArrayList";
        for (MethodNode m : List.of(methodFromJar(jar, pz, "indexOf", "(Ljava/lang/Object;)I"),
                methodFromJar(jar, pz, "indexOf", "(Ljava/lang/Object;Lzombie/util/lambda/Invokers$Params2$Boolean$ICallback;)I"),
                methodFromJar(jar, pz, "objectsEqual", "(Ljava/lang/Object;Ljava/lang/Object;)Z"))) {
            for (AbstractInsnNode in : m.instructions) {
                int op = in.getOpcode();
                if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC || op == Opcodes.ATHROW) indexWrites++;
            }
        }
        failed += check("W73 vanilla 前提：getObjectIndex（SHA 釘）→ IsoGridSquare.getObjects getter → PZArrayList.indexOf／objectsEqual 無寫入、無 throw",
                sha256Hex(debuglessMethodText(jar, obj, "getObjectIndex", "()I")).equals(W73_OBJECT_INDEX_SHA)
                && realInsnCount(getObjects) == 3 && go[1] instanceof FieldInsnNode of && of.name.equals("objects")
                && go[2].getOpcode() == Opcodes.ARETURN && indexWrites == 0);

        Map<String, String> superOf = new HashMap<>();
        Set<String> declaresEquals = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (!ze.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(zf.getInputStream(ze).readAllBytes())
                        .accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                superOf.put(cn.name, cn.superName);
                for (MethodNode m : cn.methods) {
                    if (m.name.equals("equals") && m.desc.equals("(Ljava/lang/Object;)Z")) declaresEquals.add(cn.name);
                }
            }
        }
        List<String> subclasses = new ArrayList<>();
        superOf.forEach((k, v) -> { if (thump.equals(v)) subclasses.add(k); });
        List<String> equalsInChain = new ArrayList<>();
        for (String cur = thump; cur != null && superOf.containsKey(cur); cur = superOf.get(cur)) {
            if (declaresEquals.contains(cur)) equalsInChain.add(cur);
        }
        failed += check("W73 vanilla 前提：全 jar 沒有 IsoThumpable 子類別（實際 " + subclasses + "），IsoThumpable 與 jar 內祖先沒有覆寫 equals（實際 "
                        + equalsInChain + "）",
                subclasses.isEmpty() && equalsInChain.isEmpty() && superOf.containsKey(thump));

        MethodNode pProcess = method(distJava, cell, "ProcessIsoObject", "()V");
        String defer = "zombie/mdc/IsoObjectRemoveDefer";   // W75 起兩個頭部呼叫改經 W75 helper（再轉呼叫 W73）
        failed += check("W73 手術後：ProcessIsoObject 的 update／removeAll 1:1 改道、頭部 beginPass（W75 起經 IsoObjectRemoveDefer）、唯一 RETURN 前 endPass、真指令恰 +4",
                countExactCalls(pProcess, Opcodes.INVOKESTATIC, gate, "update", "(L" + obj + ";)V") == 1
                && countExactCalls(pProcess, Opcodes.INVOKESTATIC, gate, "removeAll", "(Ljava/util/ArrayList;Ljava/util/Collection;)Z") == 1
                && countExactCalls(pProcess, Opcodes.INVOKEVIRTUAL, obj, "update", "()V") == 0
                && countExactCalls(pProcess, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "removeAll", "(Ljava/util/Collection;)Z") == 0
                && headCallSlotsOk(pProcess, defer, "beginPass", cellDesc, 0)
                && tailCallOk(pProcess, gate, "endPass", cellDesc)
                && realInsnCount(pProcess) == realInsnCount(vProcess) + 4);
        MethodNode vAdd = methodFromJar(jar, cell, "addToProcessIsoObject", "(L" + obj + ";)V");
        MethodNode pAdd = method(distJava, cell, "addToProcessIsoObject", "(L" + obj + ";)V");
        failed += check("W73／W75 addToProcessIsoObject 頭部 IsoObjectRemoveDefer.beforeAdd(cell, object)、真指令恰 +3",
                headCallSlotsOk(pAdd, defer, "beforeAdd", "(L" + cell + ";L" + obj + ";)V", 0, 1)
                && realInsnCount(pAdd) == realInsnCount(vAdd) + 3);
        MethodNode vRem = methodFromJar(jar, cell, "addToProcessIsoObjectRemove", "(L" + obj + ";)V");
        MethodNode pRem = method(distJava, cell, "addToProcessIsoObjectRemove", "(L" + obj + ";)V");
        failed += check("W73 addToProcessIsoObjectRemove 頭部 touchRemove、真指令恰 +2",
                headCallSlotsOk(pRem, gate, "touchRemove", cellDesc, 0) && realInsnCount(pRem) == realInsnCount(vRem) + 2);
        MethodNode deferBegin = method(distJava, defer, "beginPass", cellDesc);
        MethodNode deferAdd = method(distJava, defer, "beforeAdd", "(L" + cell + ";L" + obj + ";)V");
        failed += check("W73 helper 鏈：IsoObjectRemoveDefer.beginPass 恰一次轉呼叫 W73 beginPass、beforeAdd 恰一次轉呼叫 W73 touchAdd",
                countExactCalls(deferBegin, Opcodes.INVOKESTATIC, gate, "beginPass", cellDesc) == 1
                && countExactCalls(deferAdd, Opcodes.INVOKESTATIC, gate, "touchAdd", cellDesc) == 1);

        MethodNode skippable = method(distJava, gate, "skippable", "(L" + obj + ";)Z");
        int fcmpl = 0, fcmpg = 0, thumpLdc = 0, serverGets = 0;
        for (AbstractInsnNode in : skippable.instructions) {
            if (in.getOpcode() == Opcodes.FCMPL) fcmpl++;
            if (in.getOpcode() == Opcodes.FCMPG) fcmpg++;
            if (in instanceof LdcInsnNode l && l.cst instanceof org.objectweb.asm.Type t && t.getInternalName().equals(thump)) thumpLdc++;
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals("zombie/network/GameServer")
                    && f.name.equals("server")) serverGets++;
        }
        failed += check("W73 helper 契約：skippable 讀 GameServer.server、精確類別比對 IsoThumpable.class（getClass）、getLifeLeft 以 fcmpl 比 -1（NaN 跳過，不得是 fcmpg）",
                serverGets == 1 && thumpLdc == 1 && fcmpl == 1 && fcmpg == 0
                && countExactCalls(skippable, Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;") == 1
                && countExactCalls(skippable, Opcodes.INVOKEVIRTUAL, thump, "getLifeLeft", "()F") == 1);
        failed += check("W73 helper 契約：removeAll 只呼叫原版 ArrayList.removeAll（off 與量測兩條路各 1）",
                countExactCalls(method(distJava, gate, "removeAll", "(Ljava/util/ArrayList;Ljava/util/Collection;)Z"),
                        Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "removeAll", "(Ljava/util/Collection;)Z") == 2);
        return failed;
    }

    /** 方法內所有 MethodInsn 依序的 owner.name（W75 用來比對呼叫順序）。 */
    static List<String> callOrder(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode mi) out.add(mi.owner + "." + mi.name);
        }
        return out;
    }

    /**
     * W75 非主執行緒 addToProcessIsoObjectRemove 排回主執行緒（docs/patches.md 2cj）。存在理由：原版 addToProcessIsoObjectRemove
     * 直接讀寫兩個 HashSet（contains→add），addToProcessIsoObject 先 Remove.remove 再 Set.add→ArrayList.add，ProcessIsoObject
     * 頭部 isEmpty→removeAll→removeAll→clear；全 jar 只有 IsoObject.removeFromWorld() 一個呼叫點。TIS 改動任一條時紅＝重評。
     */
    static int checkIsoObjectRemoveDefer(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String cell = "zombie/iso/IsoCell";
        String obj = "zombie/iso/IsoObject";
        String defer = "zombie/mdc/IsoObjectRemoveDefer";
        String objDesc = "(L" + obj + ";)V";
        String cellObjDesc = "(L" + cell + ";L" + obj + ";)V";
        failed += check("W75 vanilla 前提：addToProcessIsoObjectRemove＝processIsoObjectSet.contains → processIsoObjectRemove.add（只碰兩個 Set）",
                callOrder(methodFromJar(jar, cell, "addToProcessIsoObjectRemove", objDesc))
                        .equals(List.of("java/util/Set.contains", "java/util/Set.add"))
                && countFieldTouches(methodFromJar(jar, cell, "addToProcessIsoObjectRemove", objDesc), cell, "processIsoObjectSet") == 1
                && countFieldTouches(methodFromJar(jar, cell, "addToProcessIsoObjectRemove", objDesc), cell, "processIsoObjectRemove") == 1);
        MethodNode vAdd = methodFromJar(jar, cell, "addToProcessIsoObject", objDesc);
        failed += check("W75 vanilla 前提：addToProcessIsoObject＝processIsoObjectRemove.remove → processIsoObjectSet.add → processIsoObject.add（取消點）",
                callOrder(vAdd).equals(List.of("java/util/Set.remove", "java/util/Set.add", "java/util/ArrayList.add"))
                && countFieldTouches(vAdd, cell, "processIsoObjectRemove") == 1);
        failed += check("W75 vanilla 前提：ProcessIsoObject 頭部 Set.isEmpty → ArrayList.removeAll → Set.removeAll → Set.clear，之後才進迴圈（size／get／update）",
                callOrder(methodFromJar(jar, cell, "ProcessIsoObject", "()V")).subList(0, 5)
                        .equals(List.of("java/util/Set.isEmpty", "java/util/ArrayList.removeAll", "java/util/Set.removeAll",
                                "java/util/Set.clear", "java/util/ArrayList.size")));
        List<String> callers = jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, cell, "addToProcessIsoObjectRemove", objDesc);
        failed += check("W75 呼叫點清冊：全 jar 對 addToProcessIsoObjectRemove 只有 IsoObject.removeFromWorld() 一處（實際 " + callers + "）",
                callers.equals(List.of(obj + ".removeFromWorld()V")));
        MethodNode vRemove = methodFromJar(jar, obj, "removeFromWorld", "()V");
        failed += check("W75 手術後：IsoObject.removeFromWorld() 的 addToProcessIsoObjectRemove 同形改道 IsoObjectRemoveDefer，其餘指令與 frames 保留；IsoObject 其餘方法不變",
                methodText(vRemove).replace("INVOKEVIRTUAL " + cell + ".addToProcessIsoObjectRemove " + objDesc,
                                "INVOKESTATIC " + defer + ".addToProcessIsoObjectRemove " + cellObjDesc)
                        .equals(methodText(method(distJava, obj, "removeFromWorld", "()V")))
                && classNodeFromJar(jar, obj).methods.stream()
                        .filter(m -> !(m.name + m.desc).equals("removeFromWorld()V"))
                        .allMatch(m -> {
                            try {
                                return methodText(m).equals(methodText(method(distJava, obj, m.name, m.desc)));
                            } catch (Exception e) {
                                return false;
                            }
                        }));
        MethodNode begin = method(distJava, defer, "beginPass", "(L" + cell + ";)V");
        List<String> beginOrder = callOrder(begin);
        failed += check("W75／W75-2 helper 契約：beginPass 先 drain 再 W73 beginPass；drain 各恰一次呼叫原版 addToProcessIsoObjectRemove／addToProcessIsoObject；"
                        + "兩個改道入口各恰一次呼叫原版（直通與溢位共用），移除入口在呼叫原版之前取消待加入",
                beginOrder.indexOf(defer + ".drain") >= 0
                && beginOrder.indexOf(defer + ".drain") < beginOrder.indexOf("zombie/mdc/IsoObjectUpdateGate.beginPass")
                && countExactCalls(method(distJava, defer, "drain", "()V"), Opcodes.INVOKEVIRTUAL, cell, "addToProcessIsoObjectRemove", objDesc) == 1
                && countExactCalls(method(distJava, defer, "drain", "()V"), Opcodes.INVOKEVIRTUAL, cell, "addToProcessIsoObject", objDesc) == 1
                && countExactCalls(method(distJava, defer, "addToProcessIsoObjectRemove", cellObjDesc), Opcodes.INVOKEVIRTUAL, cell,
                        "addToProcessIsoObjectRemove", objDesc) == 1
                && countExactCalls(method(distJava, defer, "addToProcessIsoObject", cellObjDesc), Opcodes.INVOKEVIRTUAL, cell,
                        "addToProcessIsoObject", objDesc) == 1
                && callOrder(method(distJava, defer, "addToProcessIsoObjectRemove", cellObjDesc)).indexOf(defer + ".cancel")
                        < callOrder(method(distJava, defer, "addToProcessIsoObjectRemove", cellObjDesc)).indexOf(cell + ".addToProcessIsoObjectRemove")
                && countExactCalls(method(distJava, defer, "beforeAdd", cellObjDesc), Opcodes.INVOKESTATIC, defer, "cancel", "(L" + cell + ";L" + obj + ";Z)V") == 1);
        failed += checkButcherHookAddDefer(jar, distJava);
        return failed;
    }

    /**
     * W75-2：IsoButcherHook 的 addToProcessIsoObject 排回主執行緒（docs/patches.md 2ck）。存在理由：IsoButcherHook 恰三個
     * invokevirtual IsoCell.addToProcessIsoObject（兩個建構子與 load），其中 &lt;init&gt;(IsoCell) 是 IsoObject 讀檔工廠（classID 40）
     * 呼叫的建構子；工廠在 IsoGridSquare.load 裡呼叫（LoadChunk 執行緒）。TIS 改動時紅＝重評。
     */
    static int checkButcherHookAddDefer(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String hook = "zombie/iso/IsoButcherHook";
        String cell = "zombie/iso/IsoCell";
        String obj = "zombie/iso/IsoObject";
        String defer = "zombie/mdc/IsoObjectRemoveDefer";
        String objDesc = "(L" + obj + ";)V";
        String[][] sites = {{"<init>", "(Lzombie/iso/IsoGridSquare;)V"}, {"<init>", "(L" + cell + ";)V"}, {"load", "(Ljava/nio/ByteBuffer;IZ)V"}};
        List<String> callers = jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, cell, "addToProcessIsoObject", objDesc);
        long hookCallers = callers.stream().filter(c -> c.startsWith(hook + ".")).count();
        failed += check("W75-2 vanilla 前提：IsoButcherHook 恰三個 addToProcessIsoObject 呼叫點（兩個建構子、load 的 GameClient.client 分支），"
                        + "全 jar 共 " + callers.size() + " 個",
                hookCallers == 3
                && callers.contains(hook + ".<init>(Lzombie/iso/IsoGridSquare;)V")
                && callers.contains(hook + ".<init>(L" + cell + ";)V")
                && callers.contains(hook + ".load(Ljava/nio/ByteBuffer;IZ)V"));
        MethodNode vCellCtor = methodFromJar(jar, hook, "<init>", "(L" + cell + ";)V");
        AbstractInsnNode ctorAdd = firstCall(vCellCtor, Opcodes.INVOKEVIRTUAL, cell, "addToProcessIsoObject", objDesc);
        failed += check("W75-2 vanilla 前提：<init>(IsoCell) 在 super(cell) 之後以 aload_1／aload_0 呼叫 addToProcessIsoObject（cell 為 null 時跳過）",
                ctorAdd != null && prevReal(ctorAdd) instanceof VarInsnNode a0 && a0.var == 0
                && prevReal(prevReal(ctorAdd)) instanceof VarInsnNode a1 && a1.var == 1
                && firstCallIndex(vCellCtor, Opcodes.INVOKESPECIAL, obj, "<init>", "(L" + cell + ";)V")
                        < firstCallIndex(vCellCtor, Opcodes.INVOKEVIRTUAL, cell, "addToProcessIsoObject", objDesc));
        int hookFactory = 0;
        for (MethodNode m : classNodeFromJar(jar, "zombie/iso/IsoObject$40").methods) {
            hookFactory += countExactCalls(m, Opcodes.INVOKESPECIAL, hook, "<init>", "(L" + cell + ";)V");
        }
        failed += check("W75-2 vanilla 前提：讀檔工廠 IsoObject$40 建構 IsoButcherHook(IsoCell)；IsoGridSquare.load 呼叫 factoryFromFileInput",
                hookFactory == 1
                && countExactCalls(methodFromJar(jar, "zombie/iso/IsoGridSquare", "load", "(Ljava/nio/ByteBuffer;IZ)V"), Opcodes.INVOKESTATIC, obj,
                        "factoryFromFileInput", "(L" + cell + ";Ljava/nio/ByteBuffer;)L" + obj + ";") >= 1);
        boolean same = true;
        for (String[] s : sites) {
            same &= methodText(methodFromJar(jar, hook, s[0], s[1]))
                    .replace("INVOKEVIRTUAL " + cell + ".addToProcessIsoObject " + objDesc,
                            "INVOKESTATIC " + defer + ".addToProcessIsoObject (L" + cell + ";L" + obj + ";)V")
                    .equals(methodText(method(distJava, hook, s[0], s[1])));
        }
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (String[] s : sites) targets.add(s[0] + s[1]);
        boolean othersSame = classNodeFromJar(jar, hook).methods.stream().filter(m -> !targets.contains(m.name + m.desc)).allMatch(m -> {
            try {
                return methodText(m).equals(methodText(method(distJava, hook, m.name, m.desc)));
            } catch (Exception e) {
                return false;
            }
        });
        failed += check("W75-2 手術後：IsoButcherHook 三個方法除了 addToProcessIsoObject 同形改道 IsoObjectRemoveDefer，文字與 frames 不變；其餘方法不變",
                same && othersSame);
        return failed;
    }

    /**
     * W76–W79 伺服器端請求驗證（docs/patches.md 2cl–2cn）：掛點語境與上游指紋（任一項變了＝重驗或重評撤刀）、
     * 同形手術、helper 契約。
     */
    static int checkActionValidation(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String ba = "zombie/core/BuildAction";
        String bag = "zombie/core/MdcBuildActionGuard";
        String luaCaller = "se/krka/kahlua/integration/LuaCaller";
        String pcDesc = "(Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;[Ljava/lang/Object;)Lse/krka/kahlua/integration/LuaReturn;";
        String pcHelperDesc = "(L" + luaCaller + ";" + pcDesc.substring(1);
        String parseDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        MethodNode vParse = methodFromJar(jar, ba, "parse", parseDesc);
        java.util.Set<String> baFields = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.FieldNode f : classNodeFromJar(jar, ba).fields) baFields.add(f.name + f.desc);
        failed += check("W76/W77 上游指紋：BuildAction.parse 恰 1 個 LuaCaller.protectedCall 且在 getFunctionObject 之後，"
                        + "之後 getFirst()==null 時 item=null；x/y/z（F）與 objectType 欄位存在；isValid 的全 jar 呼叫點數仍為 0",
                countExactCalls(vParse, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc) == 1
                && firstCallIndex(vParse, Opcodes.INVOKESTATIC, "zombie/Lua/LuaManager", "getFunctionObject", "(Ljava/lang/String;)Ljava/lang/Object;")
                        < firstCallIndex(vParse, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc)
                && methodText(vParse).contains("INVOKEVIRTUAL se/krka/kahlua/integration/LuaReturn.getFirst ()Ljava/lang/Object;\n    IFNONNULL")
                && methodText(vParse).contains("ACONST_NULL\n    PUTFIELD " + ba + ".item : Lse/krka/kahlua/vm/KahluaTable;")
                && baFields.containsAll(java.util.List.of("xF", "yF", "zF", "objectTypeLjava/lang/String;"))
                && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, ba, "isValid", "()Z").isEmpty()
                && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, "zombie/network/packets/BuildActionPacket", "isValid", "()Z").isEmpty()
                && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, "zombie/core/Action", "isValid", "()Z").isEmpty());
        MethodNode pParse = method(distJava, ba, "parse", parseDesc);
        boolean baOthersSame = classNodeFromJar(jar, ba).methods.stream()
                .filter(m -> !(m.name.equals("parse") && m.desc.equals(parseDesc))).allMatch(m -> {
                    try {
                        return methodText(m).equals(methodText(method(distJava, ba, m.name, m.desc)));
                    } catch (Exception e) {
                        return false;
                    }
                });
        failed += check("W76/W77 手術後：頭部 aload_0／beginParse、唯一 protectedCall 同形改道 MdcBuildActionGuard，其餘指令與 frames 不變；"
                        + "BuildAction 其餘方法不變",
                headCallOk(pParse, bag, "beginParse", "(L" + ba + ";)V")
                && methodText(pParse).equals("    ALOAD 0\n    INVOKESTATIC " + bag + ".beginParse (L" + ba + ";)V\n"
                        + methodText(vParse).replace("INVOKEVIRTUAL " + luaCaller + ".protectedCall " + pcDesc,
                                "INVOKESTATIC " + bag + ".protectedCall " + pcHelperDesc))
                && baOthersSame);
        MethodNode gCall = method(distJava, bag, "protectedCall", pcHelperDesc);
        failed += check("W76/W77 helper 契約：protectedCall 委派原呼叫恰 2 處（無 parse 上下文＋檢查通過），catch 只有 RuntimeException",
                countExactCalls(gCall, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc) == 2
                && gCall.tryCatchBlocks.stream().allMatch(t -> "java/lang/RuntimeException".equals(t.type)));

        // W78：派送 bridge 在 NetTimedAction 封包交回原版前檢查 character；原版 processServer 在 action==null 時走 Reject。
        String probe = "zombie/core/MdcTimedActionProbe";
        String psDesc = "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V";
        MethodNode vNtaProcess = methodFromJar(jar, "zombie/network/packets/NetTimedActionPacket", "processServer", psDesc);
        failed += check("W78 vanilla 前提：NetTimedActionPacket.processServer 讀 action、null 時跳 Reject 分支",
                methodText(vNtaProcess).contains(".action : Lse/krka/kahlua/vm/KahluaTable;\n    IFNULL"));
        MethodNode bridge = method(distJava, probe, "processServer",
                "(Lzombie/network/packets/INetworkPacket;" + psDesc.substring(1));
        String execDesc = "(Lzombie/network/packets/NetTimedActionPacket;Lzombie/core/raknet/UdpConnection;)V";
        MethodNode exec = method(distJava, probe, "checkExecutor", execDesc);
        failed += check("W78 helper 契約：bridge 在第一次交回原版 processServer 之前呼叫 checkExecutor；checkExecutor 讀 character、"
                        + "不符時寫 action=null，不自行派送",
                countExactCalls(bridge, Opcodes.INVOKESTATIC, probe, "checkExecutor", execDesc) == 1
                && firstCallIndex(bridge, Opcodes.INVOKESTATIC, probe, "checkExecutor", execDesc)
                        < firstCallIndex(bridge, Opcodes.INVOKEINTERFACE, "zombie/network/packets/INetworkPacket", "processServer", psDesc)
                && methodText(exec).contains("LDC \"character\"")
                && methodText(exec).contains("ACONST_NULL\n    PUTFIELD zombie/network/packets/NetTimedActionPacket.action : Lse/krka/kahlua/vm/KahluaTable;")
                && countCalls(exec, "zombie/network/packets/INetworkPacket", "processServer") == 0);

        // W79：receiveUpdateItemSprite 唯一 getGridSquare(III) 之後 null 就 return；頭部綁連線、改道 SpriteUpdateGuard。
        String gs = "zombie/network/GameServer";
        String sug = "zombie/mdc/SpriteUpdateGuard";
        String cell = "zombie/iso/IsoCell";
        String gsqDesc = "(III)Lzombie/iso/IsoGridSquare;";
        String recvDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;S)V";
        MethodNode vRecv = methodFromJar(jar, gs, "receiveUpdateItemSprite", recvDesc);
        AbstractInsnNode gsq = firstCall(vRecv, Opcodes.INVOKEVIRTUAL, cell, "getGridSquare", gsqDesc);
        failed += check("W79 上游指紋：receiveUpdateItemSprite 恰 1 個 IsoCell.getGridSquare(III)，存起來後 null 就跳過；"
                        + "方法內沒有讀 UdpConnection.players",
                countExactCalls(vRecv, Opcodes.INVOKEVIRTUAL, cell, "getGridSquare", gsqDesc) == 1
                && gsq != null && nextReal(gsq) instanceof VarInsnNode st && st.getOpcode() == Opcodes.ASTORE
                && nextReal(nextReal(gsq)) instanceof VarInsnNode ld && ld.var == st.var
                && nextReal(nextReal(nextReal(gsq))) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFNULL
                && !methodText(vRecv).contains("UdpConnection.players"));
        MethodNode pRecv = method(distJava, gs, "receiveUpdateItemSprite", recvDesc);
        String vRecvText = methodText(vRecv).replace("INVOKEVIRTUAL " + cell + ".getGridSquare " + gsqDesc,
                "INVOKESTATIC " + sug + ".getGridSquare (L" + cell + ";III)Lzombie/iso/IsoGridSquare;");
        int recvHead = 0;   // Textifier 先印 TRYCATCHBLOCK 宣告，頭部插入的指令接在它們之後
        while (vRecvText.startsWith("    TRYCATCHBLOCK", recvHead)) recvHead = vRecvText.indexOf('\n', recvHead) + 1;
        failed += check("W79 手術後：頭部 aload_1／begin、唯一 getGridSquare 同形改道 SpriteUpdateGuard，其餘指令與 frames 不變",
                headCallSlotsOk(pRecv, sug, "begin", "(Lzombie/core/raknet/UdpConnection;)V", 1)
                && methodText(pRecv).equals(vRecvText.substring(0, recvHead)
                        + "    ALOAD 1\n    INVOKESTATIC " + sug + ".begin (Lzombie/core/raknet/UdpConnection;)V\n"
                        + vRecvText.substring(recvHead)));
        failed += check("W79 helper 契約：getGridSquare 恰一次呼叫原版 IsoCell.getGridSquare(III)",
                countExactCalls(method(distJava, sug, "getGridSquare", "(L" + cell + ";III)Lzombie/iso/IsoGridSquare;"),
                        Opcodes.INVOKEVIRTUAL, cell, "getGridSquare", gsqDesc) == 1);
        return failed;
    }

    /**
     * W80 回收的殭屍物件清掉上一輪的網路擁有者（docs/patches.md 2co）：存在理由（原版回收路徑不清擁有者；任一項變紅＝官方
     * 可能已修，先重估撤刀，不改斷言）、掛點語境、同形手術、helper 契約。
     */
    static int checkZombieReuseOwner(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String vzm = "zombie/VirtualZombieManager";
        String zombie = "zombie/characters/IsoZombie";
        String helper = "zombie/mdc/ZombieReuseOwner";
        String reuseDesc = "(L" + zombie + ";)V";
        MethodNode vReuse = methodFromJar(jar, vzm, "reuseZombie", reuseDesc);
        AbstractInsnNode reset = firstCall(vReuse, Opcodes.INVOKEVIRTUAL, zombie, "resetForReuse", "()V");
        AbstractInsnNode a0 = reset == null ? null : nextReal(reset);
        AbstractInsnNode a1 = a0 == null ? null : nextReal(a0);
        AbstractInsnNode add = a1 == null ? null : nextReal(a1);
        failed += check("W80 掛點：reuseZombie 恰 1 個 IsoZombie.resetForReuse()（前 aload_1，後 aload_0/aload_1/addToReusable）；"
                        + "全 jar 只有這一個 resetForReuse 呼叫點",
                countExactCalls(vReuse, Opcodes.INVOKEVIRTUAL, zombie, "resetForReuse", "()V") == 1
                && prevRealOrNull(reset) instanceof VarInsnNode r && r.getOpcode() == Opcodes.ALOAD && r.var == 1
                && a0 instanceof VarInsnNode l0 && l0.getOpcode() == Opcodes.ALOAD && l0.var == 0
                && a1 instanceof VarInsnNode l1 && l1.getOpcode() == Opcodes.ALOAD && l1.var == 1
                && add instanceof MethodInsnNode ad && ad.owner.equals(vzm) && ad.name.equals("addToReusable") && ad.desc.equals(reuseDesc)
                && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, zombie, "resetForReuse", "()V")
                        .equals(List.of(vzm + ".reuseZombie" + reuseDesc)));
        String nzc = "zombie/characters/component/NetworkZombieComponent";
        MethodNode vReset = methodFromJar(jar, zombie, "resetForReuse", "()V");
        MethodNode vCreate = methodFromJar(jar, vzm, "createZombieOutsideWorld", "(Lzombie/iso/IsoDirections;ZI)L" + zombie + ";");
        failed += check("W80 存在理由：原版 resetForReuse 與 createZombieOutsideWorld 都不呼叫 setOwner／setOwnerPlayer、不碰 NetworkZombieComponent",
                countCalls(vReset, zombie, "setOwner") + countCalls(vReset, zombie, "setOwnerPlayer") == 0
                && countCalls(vCreate, zombie, "setOwner") + countCalls(vCreate, zombie, "setOwnerPlayer") == 0
                && countCallsToOwner(vReset, nzc) + countCallsToOwner(vCreate, nzc) == 0
                && !methodText(vReset).contains(nzc) && !methodText(vCreate).contains(nzc));
        // 死掉的殭屍會帶著擁有者進池的上游前提：moveZombie 已死分支第一個 setOwner 只在 OnGround 時跑到，removeFromWorld 不直接清
        String nzm = "zombie/popman/NetworkZombieManager";
        MethodNode vMove = methodFromJar(jar, nzm, "moveZombie", "(L" + zombie + ";Lzombie/core/raknet/UdpConnection;Lzombie/characters/IsoPlayer;)V");
        AbstractInsnNode firstSetOwner = firstCall(vMove, Opcodes.INVOKEVIRTUAL, zombie, "setOwner", "(Lzombie/core/raknet/UdpConnection;)V");
        boolean onGroundGate = false;
        for (AbstractInsnNode in = firstSetOwner; in != null; in = in.getPrevious()) {
            if (in instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IF_ACMPNE
                    && prevReal(j) instanceof FieldInsnNode rs && rs.name.equals("realState")
                    && prevReal(prevReal(prevReal(j))) instanceof FieldInsnNode og && og.getOpcode() == Opcodes.GETSTATIC
                    && og.name.equals("OnGround")
                    && vMove.instructions.indexOf(j.label) > vMove.instructions.indexOf(firstSetOwner)) {
                onGroundGate = true;
                break;
            }
        }
        MethodNode vRemove = methodFromJar(jar, zombie, "removeFromWorld", "()V");
        failed += check("W80 存在理由：moveZombie 開頭是 isDead 分支、其第一個 setOwner 只在 realState==OnGround 時執行，全方法恰 3 個 setOwner"
                        + "（已死 OnGround、活著改歸、活著清空；多一個＝官方可能補清了，先重估撤刀）；IsoZombie.removeFromWorld 只經 moveZombie 處理擁有者、不直接 setOwner／setOwnerPlayer",
                firstCallIndex(vMove, Opcodes.INVOKEVIRTUAL, zombie, "isDead", "()Z") == 2 && firstSetOwner != null && onGroundGate
                && countCalls(vMove, zombie, "setOwner") == 3
                && countCalls(vRemove, zombie, "setOwner") + countCalls(vRemove, zombie, "setOwnerPlayer") == 0
                && countCalls(vRemove, nzm, "moveZombie") == 1);
        MethodNode pReuse = method(distJava, vzm, "reuseZombie", reuseDesc);
        boolean vzmOthersSame = classNodeFromJar(jar, vzm).methods.stream()
                .filter(m -> !(m.name.equals("reuseZombie") && m.desc.equals(reuseDesc))).allMatch(m -> {
                    try {
                        return methodText(m).equals(methodText(method(distJava, vzm, m.name, m.desc)));
                    } catch (Exception e) {
                        return false;
                    }
                });
        failed += check("W80 手術後：reuseZombie 唯一 resetForReuse 同形改道 ZombieReuseOwner，其餘指令與 frames 不變；VirtualZombieManager 其餘方法不變",
                methodText(pReuse).equals(methodText(vReuse).replace("INVOKEVIRTUAL " + zombie + ".resetForReuse ()V",
                        "INVOKESTATIC " + helper + ".resetForReuse (L" + zombie + ";)V"))
                && vzmOthersSame);
        MethodNode h = method(distJava, helper, "resetForReuse", reuseDesc);
        String setOwnerDesc = "(Lzombie/core/raknet/UdpConnection;)V";
        String setPlayerDesc = "(Lzombie/characters/IsoPlayer;)V";
        AbstractInsnNode resetCall = firstCall(h, Opcodes.INVOKEVIRTUAL, zombie, "resetForReuse", "()V");
        AbstractInsnNode setOwner = firstCall(h, Opcodes.INVOKEVIRTUAL, zombie, "setOwner", setOwnerDesc);
        AbstractInsnNode setPlayer = firstCall(h, Opcodes.INVOKEVIRTUAL, zombie, "setOwnerPlayer", setPlayerDesc);
        boolean resetOutsideTry = resetCall != null && h.tryCatchBlocks.stream().noneMatch(t ->
                h.instructions.indexOf(t.start) <= h.instructions.indexOf(resetCall)
                        && h.instructions.indexOf(resetCall) < h.instructions.indexOf(t.end));
        AbstractInsnNode recheck = firstCall(h, Opcodes.INVOKEVIRTUAL, nzm, "recheck", "(Lzombie/network/IConnection;)V");
        // recheck 的引數必須是清欄位前存進區域變數的舊擁有者（清完再 getOwner() 會是 null，清單不動）。
        // 只看存入到 recheck 之間沒被改寫：javac 會讓 catch 的例外變數重用同一個 slot。
        AbstractInsnNode getOwnerCall = firstCall(h, Opcodes.INVOKEVIRTUAL, zombie, "getOwner", "()Lzombie/core/raknet/UdpConnection;");
        AbstractInsnNode ownerStore = getOwnerCall == null ? null : nextReal(getOwnerCall);
        int ownerSlot = ownerStore instanceof VarInsnNode saved && saved.getOpcode() == Opcodes.ASTORE ? saved.var : -1;
        boolean ownerKept = ownerSlot >= 0 && recheck != null
                && h.instructions.indexOf(ownerStore) < h.instructions.indexOf(recheck);
        for (AbstractInsnNode in = ownerKept ? ownerStore.getNext() : null; in != null && in != recheck; in = in.getNext()) {
            if (in instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE && v.var == ownerSlot) {
                ownerKept = false;
            }
        }
        boolean recheckOldOwner = ownerKept
                && prevReal(recheck) instanceof VarInsnNode arg && arg.getOpcode() == Opcodes.ALOAD && arg.var == ownerSlot
                && setOwner != null && h.instructions.indexOf(getOwnerCall) < h.instructions.indexOf(setOwner);
        failed += check("W80 helper 契約：恰一次呼叫原版 resetForReuse（在任何 try 之外，例外原樣上拋），之後 setOwner(null)、setOwnerPlayer(null) 各一次，"
                        + "再以清欄位前存下的舊擁有者 recheck 一次（在 try 內）；catch 只有 RuntimeException",
                countExactCalls(h, Opcodes.INVOKEVIRTUAL, zombie, "resetForReuse", "()V") == 1
                && countCalls(h, zombie, "setOwner") == 1 && countCalls(h, zombie, "setOwnerPlayer") == 1
                && countCalls(h, nzm, "recheck") == 1
                && setOwner != null && setPlayer != null && recheck != null && resetOutsideTry && recheckOldOwner
                && callsInsideTryRange(h, Opcodes.INVOKEVIRTUAL, nzm, "recheck", "(Lzombie/network/IConnection;)V") == 1
                && h.instructions.indexOf(resetCall) < h.instructions.indexOf(setOwner)
                && h.instructions.indexOf(setOwner) < h.instructions.indexOf(setPlayer)
                && h.instructions.indexOf(setPlayer) < h.instructions.indexOf(recheck)
                && prevReal(setOwner).getOpcode() == Opcodes.ACONST_NULL
                && prevReal(setPlayer).getOpcode() == Opcodes.ACONST_NULL
                && h.tryCatchBlocks.stream().allMatch(t -> "java/lang/RuntimeException".equals(t.type)));
        return failed;
    }

    /**
     * W82 燈具設定同步驗證（docs/patches.md 2cq）：存在理由（原版 parse 照收全部欄位、轉發與 switchLight 讀封包的
     * activated、客戶端送出點只有打掉燈泡；任一項變紅＝官方行為變了，先重估撤刀或重驗合法來源，不改斷言）、同形手術、helper 契約。
     */
    static int checkLightSettings(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String pkt = "zombie/network/packets/SyncCustomLightSettingsPacket";
        String guard = "zombie/network/packets/MdcLightSettingsGuard";
        String ls = "zombie/iso/objects/IsoLightSwitch";
        String parseDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";
        String[][] setters = {{"setCanBeModified", "(Z)V"}, {"setUseBatteryDirect", "(Z)V"}, {"setHasBattery", "(Z)V"},
                {"setBulbItemRaw", "(Ljava/lang/String;)V"}, {"setPower", "(F)V"}, {"setDelta", "(F)V"},
                {"setPrimaryR", "(F)V"}, {"setPrimaryG", "(F)V"}, {"setPrimaryB", "(F)V"}};
        MethodNode vParse = methodFromJar(jar, pkt, "parse", parseDesc);
        String activatedGet = "GETFIELD " + pkt + ".activated : Z";
        String vText = methodText(vParse);
        boolean settersOnce = true;
        for (String[] s : setters) {
            settersOnce &= countExactCalls(vParse, Opcodes.INVOKEVIRTUAL, ls, s[0], s[1]) == (s[0].equals("setBulbItemRaw") ? 2 : 1);
        }
        failed += check("W82 上游指紋：parse 照收全部欄位——九個 setter 各恰 1 次（setBulbItemRaw 2 次）、setPrimaryB 是最後一個呼叫、"
                        + "唯一 activated 讀取緊接 setActivated；write 與 processClient 讀封包的 activated，processServer 先轉發再 processClient",
                settersOnce
                && vText.split(java.util.regex.Pattern.quote(activatedGet), -1).length == 2
                && vText.contains(activatedGet + "\n    INVOKEVIRTUAL " + ls + ".setActivated (Z)V")
                && vText.lastIndexOf("    INVOKE") == vText.lastIndexOf("    INVOKEVIRTUAL " + ls + ".setPrimaryB (F)V")
                && methodText(methodFromJar(jar, pkt, "write", "(Lzombie/core/network/ByteBufferWriter;)V")).contains(activatedGet)
                && methodText(methodFromJar(jar, pkt, "processClient", "(Lzombie/core/raknet/UdpConnection;)V"))
                        .contains(activatedGet + "\n    INVOKEVIRTUAL " + ls + ".switchLight (Z)V")
                && methodText(methodFromJar(jar, pkt, "processServer",
                        "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V"))
                        .matches("(?s).*INVOKEVIRTUAL " + pkt + "\\.sendToClients .*INVOKEVIRTUAL " + pkt + "\\.processClient .*"));
        failed += check("W82 合法來源清冊：全 jar 呼叫 IsoLightSwitch.syncCustomizedSettings 的只有 CombatManager.shootPlacedItems（客戶端打掉燈泡）"
                        + "與 IsoLightSwitch 自己 6 處（伺服器分支）；多了新呼叫點＝重驗它是不是客戶端送出、會不會被擋",
                jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, ls, "syncCustomizedSettings", "(Lzombie/core/raknet/UdpConnection;)V").equals(java.util.List.of(
                        "zombie/CombatManager.shootPlacedItems(Lzombie/iso/IsoGridSquare;Lzombie/iso/Vector3;Lzombie/iso/objects/IsoBulletTracerEffects$Effect;)Z",
                        ls + ".addBattery(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/InventoryItem;)V",
                        ls + ".addLightBulb(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/InventoryItem;)V",
                        ls + ".removeBattery(Lzombie/characters/IsoGameCharacter;)Lzombie/inventory/types/DrainableComboItem;",
                        ls + ".removeLightBulb(Lzombie/characters/IsoGameCharacter;)Lzombie/inventory/InventoryItem;",
                        ls + ".setUseBattery(Z)V",
                        ls + ".update()V")));
        String expected = vText.replace(activatedGet, "INVOKESTATIC " + guard + ".activated (L" + pkt + ";)Z");
        for (String[] s : setters) {
            expected = expected.replace("INVOKEVIRTUAL " + ls + "." + s[0] + " " + s[1],
                    "INVOKESTATIC " + guard + "." + s[0] + " (L" + ls + ";" + s[1].substring(1));
        }
        MethodNode pParse = method(distJava, pkt, "parse", parseDesc);
        boolean othersSame = classNodeFromJar(jar, pkt).methods.stream()
                .filter(m -> !(m.name.equals("parse") && m.desc.equals(parseDesc))).allMatch(m -> {
                    try {
                        return methodText(m).equals(methodText(method(distJava, pkt, m.name, m.desc)));
                    } catch (Exception e) {
                        return false;
                    }
                });
        failed += check("W82 手術後：頭部 aload_2／begin、唯一 activated 讀取與九個 setter 同形改道 MdcLightSettingsGuard，"
                        + "其餘指令與 frames 不變；SyncCustomLightSettingsPacket 其餘方法不變",
                headCallSlotsOk(pParse, guard, "begin", "(Lzombie/network/IConnection;)V", 2)
                && methodText(pParse).equals("    ALOAD 2\n    INVOKESTATIC " + guard + ".begin (Lzombie/network/IConnection;)V\n" + expected)
                && othersSame);
        boolean delegates = true;
        for (String[] s : setters) {
            delegates &= countExactCalls(method(distJava, guard, s[0], "(L" + ls + ";" + s[1].substring(1)),
                    Opcodes.INVOKEVIRTUAL, ls, s[0], s[1]) == 1;
        }
        MethodNode gAct = method(distJava, guard, "activated", "(L" + pkt + ";)Z");
        failed += check("W82 helper 契約：每個 setter helper 恰一次呼叫同名原 setter（off，以及燈泡改 null 且連線在燈附近時走它）；"
                        + "activated 只寫封包的 activated、不呼叫 setter",
                delegates
                && methodText(gAct).contains("PUTFIELD " + pkt + ".activated : Z")
                && countCallsToOwner(gAct, ls) == 1   // 只有 isActivated
                && countExactCalls(gAct, Opcodes.INVOKEVIRTUAL, ls, "isActivated", "()Z") == 1);
        return failed;
    }

    /**
     * W83 原版本體 SHA 鎖（去 debug 的 methodText SHA-256，42.21）：{owner, name, desc, sha}。第一筆必須是 updateLOS（負對照用）。
     * 範圍＝視窗外物件在原版迴圈裡實際會到達的方法（javap 42.21 逐分支對過）：迴圈本身、ServerLOS 的視窗判斷／找 PlayerData／BusyInMain 包夾、
     * 迴圈頭的讀取（getX/Y/Z、DistanceTo、getCurrentSquare、tryCastTo）、isSeeEveryone、grapple-only 分支（isReanimatedForGrappleOnly、
     * getGrappledBy 經 IGrappleableWrapper default → getWrappedGrappleable → BaseGrappleable）、目標是玩家時讀的 ServerOptions 布林選項、
     * isAsleep、getDetectionRange 與它呼叫的三個讀取、getAttachedAnimals，以及遠處分支與 grapple-only 分支的 alpha 方法。
     */
    static final String[][] PLAYER_LOS_LOCKED = {
        {"zombie/characters/IsoPlayer", "updateLOS", "()V", "854e9828a2204c752405152848b3c9e5e0c5e123009d5d191b4373f3c676f443"},
        {"zombie/network/ServerLOS", "isCouldSee", "(Lzombie/characters/IsoPlayer;Lzombie/iso/IsoGridSquare;)Z", "7c2e5124596645d58413540f8a35d00fa356abede60b0139f564bddf7394a456"},
        {"zombie/network/ServerLOS", "findData", "(Lzombie/characters/IsoPlayer;)Lzombie/network/ServerLOS$PlayerData;", "a37f7ed5bdff485ee61523be7c56aabc3286ea57ebc2fd8a3f6d632ca2525ff6"},
        {"zombie/network/ServerLOS", "updateLOS", "(Lzombie/characters/IsoPlayer;)V", "a7fb1fa4bbcef56b23cb8f1b0b3159e15844e8ef67dd744210f6fa1b25338030"},
        {"zombie/iso/IsoObject", "getCell", "()Lzombie/iso/IsoCell;", "d7e199011eb1c053db4fb34410ab4e7ed4534c41402ce9b160b399bd251f844c"},
        {"zombie/iso/IsoCell", "getObjectList", "()Ljava/util/Set;", "6249da7f730e2a7ff2b3e3519d98248fc69b18312a9666ce75eb2464a9075461"},
        {"zombie/iso/IsoMovingObject", "getX", "()F", "edbc512096331d3265c0e040ca765aa6c20a7c70f3f1ff8f774ff854b0cd7b3c"},
        {"zombie/iso/IsoMovingObject", "getY", "()F", "03571cdb95c00cdc51ab7b673869061dc23a0bd5c745e2e996055cca8331b2bb"},
        {"zombie/iso/IsoMovingObject", "getZ", "()F", "028d6aafaa62a9a568ca08ea3ff8451fc3f7d53e5ba852f89b834f68912fca12"},
        {"zombie/iso/IsoMovingObject", "getCurrentSquare", "()Lzombie/iso/IsoGridSquare;", "0a067d261e92752fd248a434b46b9d5e364d026db227d8b9beb86af3f26bcb20"},
        {"zombie/iso/IsoUtils", "DistanceTo", "(FFFF)F", "320486c281cf7912407ef4fdf18bcd93db619e3735e58957ad27d3918b417525"},
        {"zombie/util/Type", "tryCastTo", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", "7da8245a0ef96bbe1a2305b783c95efe2ca0dc4402a563fac7b76e4684de0f34"},
        {"zombie/characters/IsoPlayer", "isSeeEveryone", "()Z", "b478ec8eb6019eed035cc1bc9f41400172881efbbec96986eb209c8a6c146a6f"},
        {"zombie/characters/IsoPlayer", "getAttachedAnimals", "()Ljava/util/List;", "adfad63f8089a305a49f9a6efa301660bd46bddbef64c3e525f867d2786c7cf8"},
        {"zombie/characters/IsoPlayer", "getPlayerIndex", "()I", "9d52b10d0b82d967144351d75a29162126f00456565c1da587fb88e4c6a368b6"},
        {"zombie/characters/IsoGameCharacter", "isAsleep", "()Z", "421df3d5f552be9ee70370d8da649432e6ca12d9f0c8d43995ce8625539c39cd"},
        {"zombie/characters/IsoGameCharacter", "getDetectionRange", "()F", "d7ee1ff93517bba25e03930aeee9b0dc67739569c490359f5711030d0d5aafbf"},
        {"zombie/characters/IsoGameCharacter", "getWornItemsHearingMultiplier", "()F", "38d562eb28f9f90f39b914c664547765de9b393ebc7a75e0f11a85bdf622077e"},
        {"zombie/characters/traits/CharacterTraits", "get", "(Lzombie/scripting/objects/CharacterTrait;)Z", "a5f86fbbbb7f242d8b7d39340d2b3ee58429aec5aa79e103714f61b87efe7a26"},
        {"zombie/characters/Stats", "get", "(Lzombie/characters/CharacterStat;)F", "3542a17f74d28483eb328bd3fa211f76be9bb880c5a90642721e7cf86ff75d16"},
        {"zombie/characters/IsoZombie", "isReanimatedForGrappleOnly", "()Z", "de8ed6dcc1712907867193ab423eed868f919bd783701fee6dae7cc7b91b0197"},
        {"zombie/core/skinnedmodel/IGrappleableWrapper", "getGrappledBy", "()Lzombie/core/skinnedmodel/IGrappleable;", "30ee0a7eeef585397e5a45c20193a34f56aceace3bbb089a5ed17e9290f05037"},
        {"zombie/characters/IsoGameCharacter", "getWrappedGrappleable", "()Lzombie/core/skinnedmodel/IGrappleable;", "f1667e890f44701d4400c3afb9ab60e88a2f38cfc362b3d04a6ad63c4adffd72"},
        {"zombie/characters/IsoGameCharacter", "getWrappedGrappleable", "()Lzombie/core/skinnedmodel/BaseGrappleable;", "fd8f80972f9500402e00a4a3344c012e18b2655fbe2b803082ce08da3f054bd2"},
        {"zombie/core/skinnedmodel/BaseGrappleable", "getGrappledBy", "()Lzombie/core/skinnedmodel/IGrappleable;", "d6870eb809772ddd7002d4ac35b74b6566cea1aabf4fc1b73ea646bbf06083ba"},
        {"zombie/network/ServerOptions", "getInstance", "()Lzombie/network/ServerOptions;", "ed1c9669aaaab3bcc2574d00de87743a014a9d73491d1db19875001ed619038b"},
        {"zombie/config/BooleanConfigOption", "getValue", "()Z", "4266d7992c9660de67c01cad77064398d1cd32242cdb008275895899daa2a5da"},
        {"zombie/iso/IsoObject", "setAlpha", "(IF)V", "cdc30e75a73b9d37c34b1088f90e245ecba69d833bdee1a23e92c916cdb25a43"},
        {"zombie/iso/IsoObject", "setTargetAlpha", "(IF)V", "9a6de7749cd96465c91a68e17fbd7ea598c245469c3d9a9d4f41384dacc0d402"},
        {"zombie/iso/IsoObject", "setAlphaAndTarget", "(IF)V", "d4fca46bfd25232dd2cdc2f44fd4b31b2ef1ea8869d4a99409ce4502333ec409"},
        {"zombie/iso/IsoObject", "setAlphaAndTarget", "(F)V", "3fbff5c5a623ee2c07fb794125146b5059dbc16aafe867de649a89876ae81672"},
        {"zombie/iso/IsoObject", "getTargetAlpha", "(I)F", "e67c9faf3ea90e3dc28a926cf06c8351b8ea1d594929886d816e4c2eb1ae606b"},
        {"zombie/iso/IsoObject", "getTargetAlpha", "()F", "741df33d8771ab351d6d5290e5e35865a3c4abbfd6e694c99831bab38c91a402"},
    };

    /** W83 覆寫普查：IsoObject 子類（含 IsoObject 自己）中宣告這些方法的類別（42.21），{method, declarer}。 */
    static final String[][] PLAYER_LOS_DECLARERS = {
        {"getX()F", "zombie/iso/IsoObject"}, {"getX()F", "zombie/iso/IsoMovingObject"},
        {"getY()F", "zombie/iso/IsoObject"}, {"getY()F", "zombie/iso/IsoMovingObject"},
        {"getZ()F", "zombie/iso/IsoObject"}, {"getZ()F", "zombie/iso/IsoMovingObject"},
        {"getCurrentSquare()Lzombie/iso/IsoGridSquare;", "zombie/iso/IsoMovingObject"},
        {"setAlpha(IF)V", "zombie/iso/IsoObject"},
        {"setTargetAlpha(IF)V", "zombie/iso/IsoObject"},
        {"setAlphaAndTarget(IF)V", "zombie/iso/IsoObject"},
        {"setAlphaAndTarget(F)V", "zombie/iso/IsoObject"},
        {"getTargetAlpha(I)F", "zombie/iso/IsoObject"},
        {"getTargetAlpha()F", "zombie/iso/IsoObject"},
        {"getCell()Lzombie/iso/IsoCell;", "zombie/iso/IsoObject"},
        {"isReanimatedForGrappleOnly()Z", "zombie/characters/IsoZombie"},
        {"isAsleep()Z", "zombie/characters/IsoGameCharacter"},
        {"getDetectionRange()F", "zombie/characters/IsoGameCharacter"},
        {"getWornItemsHearingMultiplier()F", "zombie/characters/IsoGameCharacter"},
        {"getWrappedGrappleable()Lzombie/core/skinnedmodel/IGrappleable;", "zombie/characters/IsoGameCharacter"},
        // IsoDeadBody 也實作 IGrappleableWrapper，但 grapple-only 分支只對 tryCastTo(IsoZombie) 的結果呼叫 getGrappledBy（IsoZombie 是 final）
        {"getWrappedGrappleable()Lzombie/core/skinnedmodel/IGrappleable;", "zombie/iso/objects/IsoDeadBody"},
        {"isSeeEveryone()Z", "zombie/characters/IsoPlayer"},
        {"getAttachedAnimals()Ljava/util/List;", "zombie/characters/IsoPlayer"},
    };

    /**
     * W83：42.21 原版 updateLOS 迴圈本體（迭代器存入 slot 9 之後到迴圈出口）的呼叫多重集合（opcode 182 virtual／184 static／185 interface）。
     * 只當診斷：本體由 {@link #PLAYER_LOS_LOCKED} 的 SHA 鎖住（呼叫集合不變、多一筆欄位寫入也會紅）。
     */
    static final String PLAYER_LOS_LOOP_INVOKES = """
            182 java/util/Stack.add(Ljava/lang/Object;)Z
            182 java/util/Stack.add(Ljava/lang/Object;)Z
            182 java/util/Stack.contains(Ljava/lang/Object;)Z
            182 zombie/GameTime.setMultiplier(F)V
            182 zombie/characters/IsoGameCharacter.TestIfSeen(ILzombie/characters/IsoPlayer;)Z
            182 zombie/characters/IsoGameCharacter.isInvisible()Z
            182 zombie/characters/IsoGameCharacter.setAlphaAndTarget(IF)V
            182 zombie/characters/IsoGameCharacter.setTargetAlpha(IF)V
            182 zombie/characters/IsoPlayer.DistToProper(Lzombie/iso/IsoObject;)F
            182 zombie/characters/IsoPlayer.DistToProper(Lzombie/iso/IsoObject;)F
            182 zombie/characters/IsoPlayer.TestZombieSpotPlayer(Lzombie/iso/IsoMovingObject;)V
            182 zombie/characters/IsoPlayer.TestZombieSpotPlayer(Lzombie/iso/IsoMovingObject;)V
            182 zombie/characters/IsoPlayer.getAttachedAnimals()Ljava/util/List;
            182 zombie/characters/IsoPlayer.getCurrentSquare()Lzombie/iso/IsoGridSquare;
            182 zombie/characters/IsoPlayer.getDetectionRange()F
            182 zombie/characters/IsoPlayer.getRole()Lzombie/characters/Role;
            182 zombie/characters/IsoPlayer.isAsleep()Z
            182 zombie/characters/IsoPlayer.isGhostMode()Z
            182 zombie/characters/IsoPlayer.isSeeEveryone()Z
            182 zombie/characters/IsoPlayer.setTargetAlpha(IF)V
            182 zombie/characters/IsoZombie.canSeeHeadSquare(Lzombie/characters/IsoPlayer;)Z
            182 zombie/characters/IsoZombie.couldSeeHeadSquare(Lzombie/characters/IsoPlayer;)Z
            182 zombie/characters/IsoZombie.getGrappledBy()Lzombie/core/skinnedmodel/IGrappleable;
            182 zombie/characters/IsoZombie.isCurrentState(Lzombie/ai/State;)Z
            182 zombie/characters/IsoZombie.isCurrentState(Lzombie/ai/State;)Z
            182 zombie/characters/IsoZombie.isCurrentState(Lzombie/ai/State;)Z
            182 zombie/characters/IsoZombie.isFakeDead()Z
            182 zombie/characters/IsoZombie.isReanimatedForGrappleOnly()Z
            182 zombie/characters/IsoZombie.isSceneCulled()Z
            182 zombie/characters/Role.hasCapability(Lzombie/characters/Capability;)Z
            182 zombie/iso/IsoGridSquare.getCanSee(I)Z
            182 zombie/iso/IsoGridSquare.getRoom()Lzombie/iso/areas/IsoRoom;
            182 zombie/iso/IsoGridSquare.getRoom()Lzombie/iso/areas/IsoRoom;
            182 zombie/iso/IsoGridSquare.isCanSee(I)Z
            182 zombie/iso/IsoGridSquare.isCouldSee(I)Z
            182 zombie/iso/IsoMovingObject.getCurrentSquare()Lzombie/iso/IsoGridSquare;
            182 zombie/iso/IsoMovingObject.getTargetAlpha()F
            182 zombie/iso/IsoMovingObject.getTargetAlpha(I)F
            182 zombie/iso/IsoMovingObject.getX()F
            182 zombie/iso/IsoMovingObject.getY()F
            182 zombie/iso/IsoMovingObject.getZ()F
            182 zombie/iso/IsoMovingObject.setAlpha(IF)V
            182 zombie/iso/IsoMovingObject.setAlphaAndTarget(F)V
            182 zombie/iso/IsoMovingObject.setAlphaAndTarget(IF)V
            182 zombie/iso/IsoMovingObject.setTargetAlpha(IF)V
            182 zombie/network/ServerLOS.isCouldSee(Lzombie/characters/IsoPlayer;Lzombie/iso/IsoGridSquare;)Z
            182 zombie/network/ServerOptions$BooleanServerOption.getValue()Z
            182 zombie/ui/SpeedControls.SetCurrentGameSpeed(I)V
            184 zombie/ai/states/LungeState.instance()Lzombie/ai/states/LungeState;
            184 zombie/ai/states/PathFindState.instance()Lzombie/ai/states/PathFindState;
            184 zombie/ai/states/WalkTowardState.instance()Lzombie/ai/states/WalkTowardState;
            184 zombie/core/math/PZMath.fastfloor(F)I
            184 zombie/core/math/PZMath.fastfloor(F)I
            184 zombie/core/math/PZMath.fastfloor(F)I
            184 zombie/core/math/PZMath.fastfloor(F)I
            184 zombie/iso/IsoUtils.DistanceTo(FFFF)F
            184 zombie/network/ServerOptions.getInstance()Lzombie/network/ServerOptions;
            184 zombie/ui/UIManager.getSpeedControls()Lzombie/ui/SpeedControls;
            184 zombie/util/Type.tryCastTo(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;
            184 zombie/util/Type.tryCastTo(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;
            184 zombie/util/Type.tryCastTo(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;
            184 zombie/util/Type.tryCastTo(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;
            184 zombie/util/Type.tryCastTo(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;
            185 java/util/Iterator.hasNext()Z
            185 java/util/Iterator.next()Ljava/lang/Object;
            185 java/util/List.contains(Ljava/lang/Object;)Z""";

    /**
     * W83 玩家 LOS 過濾迭代器（docs/patches.md 2cr）：原版前提（任一項變紅＝TIS 改了 updateLOS／ServerLOS 的語意，先重新分析
     * 等價或撤刀，不改斷言讓它變綠）、同形手術、helper 契約。
     */
    static int checkPlayerLosFilter(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String player = "zombie/characters/IsoPlayer";
        String los = "zombie/network/ServerLOS";
        String pd = los + "$PlayerData";
        String status = los + "$UpdateStatus";
        String helper = "zombie/mdc/PlayerLosFilter";
        String cellDesc = "()Lzombie/iso/IsoCell;";
        String itDesc = "()Ljava/util/Iterator;";
        String iter = "java/util/Iterator";
        MethodNode v = methodFromJar(jar, player, "updateLOS", "()V");
        AbstractInsnNode getCell = firstCall(v, Opcodes.INVOKEVIRTUAL, player, "getCell", cellDesc);
        AbstractInsnNode objList = getCell == null ? null : nextReal(getCell);
        AbstractInsnNode setIt = objList == null ? null : nextReal(objList);
        AbstractInsnNode store9 = setIt == null ? null : nextReal(setIt);
        int astore9 = 0;
        boolean load9Ok = true;
        for (AbstractInsnNode in : v.instructions) {
            if (isVar(in, Opcodes.ASTORE, 9)) {
                astore9++;
            } else if (isVar(in, Opcodes.ALOAD, 9)) {
                AbstractInsnNode use = nextReal(in);
                load9Ok &= isCall(use, Opcodes.INVOKEINTERFACE, iter, "hasNext", "()Z")
                        || isCall(use, Opcodes.INVOKEINTERFACE, iter, "next", "()Ljava/lang/Object;");
            }
        }
        failed += check("W83 vanilla 前提：updateLOS 恰 1 個 IsoPlayer.getCell()、1 個 Set.iterator()，依序 aload_0→getCell→IsoCell.getObjectList"
                        + "→Set.iterator→astore 9（直線、無分支）；迭代器只存 slot 9 一次，每個 aload 9 緊接 Iterator.hasNext／next",
                countExactCalls(v, Opcodes.INVOKEVIRTUAL, player, "getCell", cellDesc) == 1
                && countExactCalls(v, Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", itDesc) == 1
                && isVar(prevRealOrNull(getCell), Opcodes.ALOAD, 0)
                && isCall(objList, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "getObjectList", "()Ljava/util/Set;")
                && isCall(setIt, Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", itDesc)
                && isVar(store9, Opcodes.ASTORE, 9) && astore9 == 1 && load9Ok);
        // 迴圈本體的呼叫多重集合（診斷用：SHA 鎖紅時印出它，看是不是呼叫變了）
        String loopText = playerLosLoopInvokes(v);
        failed += check("W83 vanilla 前提（診斷）：迴圈本體的呼叫多重集合與 42.21 相同（" + loopText.lines().count() + " 個）",
                loopText.equals(PLAYER_LOS_LOOP_INVOKES));
        if (!loopText.equals(PLAYER_LOS_LOOP_INVOKES)) {
            System.out.println("  實際迴圈呼叫：\n" + loopText);
        }
        // 原版本體 SHA 鎖：updateLOS 本身、ServerLOS 三個方法、略過路徑上會走到的讀取與 alpha 方法（去 debug 的 methodText）
        StringBuilder shaDrift = new StringBuilder();
        for (String[] m : PLAYER_LOS_LOCKED) {
            String got = sha256Hex(debuglessMethodText(jar, m[0], m[1], m[2]));
            if (!got.equals(m[3])) {
                shaDrift.append("\n    {\"").append(m[0]).append("\", \"").append(m[1]).append("\", \"").append(m[2])
                        .append("\", \"").append(got).append("\"},");
            }
        }
        failed += check("W83 vanilla 前提：" + PLAYER_LOS_LOCKED.length + " 個原版方法本體（去 debug 的 methodText SHA-256）與 42.21 相同"
                        + "（變了＝重新分析略過的物件是否仍只有純讀取與伺服器 no-op，不改 SHA 讓它變綠）",
                shaDrift.length() == 0);
        if (shaDrift.length() > 0) {
            System.out.println("  漂移的方法（實際 SHA）：" + shaDrift);
        }
        // 負對照：在記憶體裡的原版 updateLOS 迴圈頭（astore 10 之後）插一筆欄位寫入，呼叫集合不變、SHA 鎖必須變紅
        ClassNode vNode = new ClassNode();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            new ClassReader(jf.getInputStream(jf.getJarEntry(player + ".class")).readAllBytes()).accept(vNode, ClassReader.SKIP_DEBUG);
        }
        MethodNode mutated = declaredMethod(vNode, "updateLOS", "()V");
        String lockedSha = PLAYER_LOS_LOCKED[0][3];
        boolean shaBefore = sha256Hex(methodText(mutated)).equals(lockedSha);
        AbstractInsnNode store10 = null;
        for (AbstractInsnNode in : mutated.instructions) {
            if (isVar(in, Opcodes.ASTORE, 10) && store10 == null) {
                store10 = in;
            }
        }
        InsnList write = new InsnList();
        write.add(new VarInsnNode(Opcodes.ALOAD, 0));
        write.add(new InsnNode(Opcodes.ICONST_1));
        write.add(new FieldInsnNode(Opcodes.PUTFIELD, player, "ticksSinceSeenZombie", "I"));
        if (store10 != null) {
            mutated.instructions.insert(store10, write);
        }
        failed += check("W83 SHA 鎖負對照：原版 updateLOS 迴圈頭插一筆 putfield ticksSinceSeenZombie（呼叫多重集合照舊相同）→ SHA 鎖變紅",
                PLAYER_LOS_LOCKED[0][1].equals("updateLOS") && shaBefore && store10 != null
                        && playerLosLoopInvokes(mutated).equals(PLAYER_LOS_LOOP_INVOKES)
                        && !sha256Hex(methodText(mutated)).equals(lockedSha));
        // couldSee（slot 20）在伺服器路徑只來自 ServerLOS.isCouldSee(this, sq)；canSee（slot 21）先等於 couldSee，只在 bClient／!bServer 守衛下重寫
        AbstractInsnNode serverSee = firstCall(v, Opcodes.INVOKEVIRTUAL, los, "isCouldSee",
                "(L" + player + ";Lzombie/iso/IsoGridSquare;)Z");
        List<AbstractInsnNode> st21 = new ArrayList<>();
        for (AbstractInsnNode in : v.instructions) {
            if (isVar(in, Opcodes.ISTORE, 21)) {
                st21.add(in);
            }
        }
        boolean canSeeGuards = st21.size() == 4 && isVar(prevReal(st21.get(0)), Opcodes.ILOAD, 20)
                && guardedBy(v, st21.get(0), st21.get(1), 2, Opcodes.IFEQ, st21.get(1), st21.get(2))
                && guardedBy(v, st21.get(1), st21.get(2), 1, Opcodes.IFNE, st21.get(3), null);
        failed += check("W83 vanilla 前提：伺服器路徑 couldSee＝ServerLOS.isCouldSee(this, current)（恰 1 次，aload_0／aload 15 → istore 20），"
                        + "canSee（slot 21）先取 couldSee、其餘 3 次寫入只在 iload_2（bClient）ifeq 與 iload_1（bServer）ifne 守衛下",
                countCalls(v, los, "isCouldSee") == 1 && serverSee != null
                && isVar(prevReal(serverSee), Opcodes.ALOAD, 15) && isVar(prevReal(prevReal(serverSee)), Opcodes.ALOAD, 0)
                && isVar(nextReal(serverSee), Opcodes.ISTORE, 20) && canSeeGuards);
        // ServerLOS.isCouldSee 視窗指紋
        MethodNode see = methodFromJar(jar, los, "isCouldSee", "(L" + player + ";Lzombie/iso/IsoGridSquare;)Z");
        AbstractInsnNode sizeZ = null;
        for (AbstractInsnNode in : see.instructions) {
            if (isField(in, Opcodes.GETSTATIC, "zombie/iso/LosUtil", "sizeZ", "I") && sizeZ == null) {
                sizeZ = in;
            }
        }
        failed += check("W83 vanilla 前提：ServerLOS.isCouldSee 指紋——findData 1 次、bipush 48 ×2、bipush 96 ×2、LosUtil.sizeZ ×2（第一個接 iconst_2／idiv）、"
                        + "px／py／pz 與格子 x／y／z 各讀 1 次、visible 為 [[[Z",
                countExactCalls(see, Opcodes.INVOKEVIRTUAL, los, "findData", "(L" + player + ";)L" + pd + ";") == 1
                && countIntConst(see, 48) == 2 && countIntConst(see, 96) == 2
                && countExactFields(see, Opcodes.GETSTATIC, "zombie/iso/LosUtil", "sizeZ", "I") == 2
                && sizeZ != null && nextReal(sizeZ).getOpcode() == Opcodes.ICONST_2 && nextReal(nextReal(sizeZ)).getOpcode() == Opcodes.IDIV
                && countExactFields(see, Opcodes.GETFIELD, pd, "px", "I") == 1
                && countExactFields(see, Opcodes.GETFIELD, pd, "py", "I") == 1
                && countExactFields(see, Opcodes.GETFIELD, pd, "pz", "I") == 1
                && countExactFields(see, Opcodes.GETFIELD, "zombie/iso/IsoGridSquare", "x", "I") == 1
                && countExactFields(see, Opcodes.GETFIELD, "zombie/iso/IsoGridSquare", "y", "I") == 1
                && countExactFields(see, Opcodes.GETFIELD, "zombie/iso/IsoGridSquare", "z", "I") == 1
                && countExactFields(see, Opcodes.GETFIELD, pd, "visible", "[[[Z") == 1);
        ClassNode pdNode = classNodeFromJar(jar, pd);
        MethodNode findData = methodFromJar(jar, los, "findData", "(L" + player + ";)L" + pd + ";");
        failed += check("W83 vanilla 前提：PlayerData 欄位 player／status／px／py／pz／visible 型別不變、UpdateStatus.BusyInMain 存在、"
                        + "ServerLOS.playersMain 是 ArrayList；findData 由 0 往上取第一筆 player == p",
                hasField(pdNode, "player", "L" + player + ";") && hasField(pdNode, "status", "L" + status + ";")
                && hasField(pdNode, "px", "I") && hasField(pdNode, "py", "I") && hasField(pdNode, "pz", "I")
                && hasField(pdNode, "visible", "[[[Z")
                && hasField(classNodeFromJar(jar, status), "BusyInMain", "L" + status + ";")
                && hasField(classNodeFromJar(jar, los), "playersMain", "Ljava/util/ArrayList;")
                && hasField(classNodeFromJar(jar, los), "instance", "L" + los + ";")
                && firstReal(findData, 1)[0].getOpcode() == Opcodes.ICONST_0
                && countExactFields(findData, Opcodes.GETFIELD, pd, "player", "L" + player + ";") == 1
                && countOpcode(findData, Opcodes.IF_ACMPNE) == 1 && countOpcode(findData, Opcodes.IINC) == 1);
        // BusyInMain 只在 ServerLOS.updateLOS 呼叫 player.updateLOS() 期間；LOS 執行緒只對 WaitingInLOS 寫 px/py/pz
        MethodNode losUpdate = methodFromJar(jar, los, "updateLOS", "(L" + player + ";)V");
        AbstractInsnNode callLos = firstCall(losUpdate, Opcodes.INVOKEVIRTUAL, player, "updateLOS", "()V");
        AbstractInsnNode beforeCall = callLos == null ? null : prevReal(prevReal(callLos));
        AbstractInsnNode afterCall = callLos == null ? null : nextReal(nextReal(nextReal(callLos)));
        String losThread = los + "$LOSThread";
        MethodNode runInner = methodFromJar(jar, losThread, "runInner", "()V");
        AbstractInsnNode calc = firstCall(runInner, Opcodes.INVOKEVIRTUAL, losThread, "calcLOS", "(L" + pd + ";)V");
        boolean calcAfterWaiting = false;
        for (AbstractInsnNode in = calc; in != null; in = in.getPrevious()) {
            if (in instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IF_ACMPNE) {
                calcAfterWaiting = isField(prevReal(j), Opcodes.GETSTATIC, status, "WaitingInLOS", "L" + status + ";")
                        && runInner.instructions.indexOf(j.label) > runInner.instructions.indexOf(calc);
                break;
            }
        }
        Map<String, Integer> pxWriters = new java.util.TreeMap<>();
        for (String f : new String[]{"px", "py", "pz"}) {
            jarWideFieldRefs(jar, Opcodes.PUTFIELD, pd, f).forEach((k, n) -> pxWriters.merge(k, n, Integer::sum));
        }
        failed += check("W83 vanilla 前提：ServerLOS.updateLOS 在 player.updateLOS() 前把 status 設成 BusyInMain、之後設回 ReadyInMain，"
                        + "全 jar 只有它讀 BusyInMain；px／py／pz 只在 LOSThread.calcLOS 寫，calcLOS 只由 runInner 在 status==WaitingInLOS 時呼叫",
                callLos != null && countExactCalls(losUpdate, Opcodes.INVOKEVIRTUAL, player, "updateLOS", "()V") == 1
                && isField(beforeCall, Opcodes.PUTFIELD, pd, "status", "L" + status + ";")
                && isField(prevReal(beforeCall), Opcodes.GETSTATIC, status, "BusyInMain", "L" + status + ";")
                && isVar(prevReal(callLos), Opcodes.ALOAD, 1)
                && isField(afterCall, Opcodes.PUTFIELD, pd, "status", "L" + status + ";")
                && isField(prevReal(afterCall), Opcodes.GETSTATIC, status, "ReadyInMain", "L" + status + ";")
                && jarWideFieldRefs(jar, Opcodes.GETSTATIC, status, "BusyInMain")
                        .equals(Map.of(los + ".updateLOS", 1, status + ".$values", 1))
                && pxWriters.equals(Map.of(losThread + ".calcLOS", 3))
                && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, losThread, "calcLOS", "(L" + pd + ";)V")
                        .equals(List.of(losThread + ".runInner()V"))
                && calcAfterWaiting);
        // 略過的物件在原版只做純讀取與伺服器 no-op：alpha 系列 server 守衛、getter 純讀取、全 jar IsoObject 子類零覆寫
        String obj = "zombie/iso/IsoObject";
        String mov = "zombie/iso/IsoMovingObject";
        MethodNode sat2 = methodFromJar(jar, obj, "setAlphaAndTarget", "(IF)V");
        MethodNode sat1 = methodFromJar(jar, obj, "setAlphaAndTarget", "(F)V");
        MethodNode gta0 = methodFromJar(jar, obj, "getTargetAlpha", "()F");
        failed += check("W83 vanilla 前提：setTargetAlpha(IF)／setAlpha(IF) 頭部 server guard、getTargetAlpha(I) server→1；setAlphaAndTarget(IF) 只呼叫"
                        + " setAlpha(IF)＋setTargetAlpha(IF)，setAlphaAndTarget(F)／getTargetAlpha() 只經 getPlayerIndex 轉呼叫兩參數版",
                checkServerGuardHead(methodFromJar(jar, obj, "setTargetAlpha", "(IF)V"))
                && checkServerGuardHead(methodFromJar(jar, obj, "setAlpha", "(IF)V"))
                && checkGetTargetAlphaGuard(methodFromJar(jar, obj, "getTargetAlpha", "(I)F"))
                && callNames(sat2).equals(List.of(obj + ".setAlpha", obj + ".setTargetAlpha"))
                && callNames(sat1).equals(List.of(player + ".getPlayerIndex", obj + ".setAlphaAndTarget"))
                && callNames(gta0).equals(List.of(player + ".getPlayerIndex", obj + ".getTargetAlpha")));
        List<String> watched = List.of("getX()F", "getY()F", "getZ()F", "getCurrentSquare()Lzombie/iso/IsoGridSquare;", "setAlpha(IF)V",
                "setTargetAlpha(IF)V", "setAlphaAndTarget(IF)V", "setAlphaAndTarget(F)V", "getTargetAlpha(I)F", "getTargetAlpha()F",
                "isReanimatedForGrappleOnly()Z", "isAsleep()Z", "getDetectionRange()F", "isSeeEveryone()Z",
                "getAttachedAnimals()Ljava/util/List;", "getCell()Lzombie/iso/IsoCell;",
                "getGrappledBy()Lzombie/core/skinnedmodel/IGrappleable;", "getWrappedGrappleable()Lzombie/core/skinnedmodel/IGrappleable;",
                "getWornItemsHearingMultiplier()F");
        JarIndex index = JarIndex.load(jar);
        Map<String, Set<String>> declarers = declarersUnder(index.classes(), obj, watched);
        Map<String, Set<String>> wantDeclarers = new java.util.TreeMap<>();
        for (String[] e : PLAYER_LOS_DECLARERS) {
            wantDeclarers.computeIfAbsent(e[0], k -> new java.util.TreeSet<>()).add(e[1]);
        }
        String grapple = "zombie/core/skinnedmodel/BaseGrappleable";
        Map<String, Set<String>> grappleDecl = declarersUnder(index.classes(), grapple,
                List.of("getGrappledBy()Lzombie/core/skinnedmodel/IGrappleable;"));
        // 迴圈讀的 ServerOptions 選項是 BooleanServerOption（直接繼承 BooleanConfigOption）：它與子類都不覆寫 getValue
        String boolOpt = "zombie/network/ServerOptions$BooleanServerOption";
        ClassNode boolNode = index.classes().get(boolOpt);
        Map<String, Set<String>> boolDecl = declarersUnder(index.classes(), boolOpt, List.of("getValue()Z"));
        boolean subOk = grappleDecl.equals(Map.of(watched.get(16), Set.of(grapple))) && boolDecl.isEmpty()
                && boolNode != null && "zombie/config/BooleanConfigOption".equals(boolNode.superName);
        boolean pureGetters = true;
        for (String[] g : new String[][]{{mov, "getX", "()F", "x"}, {mov, "getY", "()F", "y"}, {mov, "getZ", "()F", "z"},
                {mov, "getCurrentSquare", "()Lzombie/iso/IsoGridSquare;", "current"},
                {"zombie/iso/IsoCell", "getObjectList", "()Ljava/util/Set;", "objectList"}}) {
            MethodNode m = methodFromJar(jar, g[0], g[1], g[2]);
            AbstractInsnNode[] r = firstReal(m, 3);
            pureGetters &= realInsnCount(m) == 3 && isVar(r[0], Opcodes.ALOAD, 0)
                    && r[1] instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD && fi.name.equals(g[3]);
        }
        failed += check("W83 vanilla 前提：全 jar IsoObject 子類（objectList 裡的 IsoMovingObject 都在內）對略過路徑上的讀取與 alpha 方法零覆寫"
                        + "（" + watched.size() + " 個方法的宣告者與 42.21 相同）、BaseGrappleable 子類不覆寫 getGrappledBy、"
                        + "ServerOptions$BooleanServerOption 直接繼承 BooleanConfigOption 且它與子類不覆寫 getValue；"
                        + "IsoMovingObject.getX/Y/Z／getCurrentSquare 與 IsoCell.getObjectList 是純欄位讀取",
                declarers.equals(wantDeclarers) && subOk && pureGetters);
        if (!declarers.equals(wantDeclarers) || !subOk) {
            System.out.println("  實際宣告者：" + declarers + "；BaseGrappleable 系：" + grappleDecl + "；BooleanServerOption 系：" + boolDecl
                    + "（super=" + (boolNode == null ? null : boolNode.superName) + "）");
        }
        // 負對照：多一個 IsoZombie 子類覆寫 setTargetAlpha(IF)V，普查必須抓到
        Map<String, ClassNode> withFake = new HashMap<>(index.classes());
        ClassNode fake = new ClassNode();
        fake.name = "zombie/mdc/W83FakeZombie";
        fake.superName = "zombie/characters/IsoZombie";
        fake.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "setTargetAlpha", "(IF)V", null, null));
        withFake.put(fake.name, fake);
        Map<String, Set<String>> fakeDecl = declarersUnder(withFake, obj, watched);
        failed += check("W83 覆寫普查負對照：多一個 IsoZombie 子類覆寫 setTargetAlpha(IF)V → 宣告者不同、普查變紅",
                !fakeDecl.equals(wantDeclarers) && fakeDecl.get("setTargetAlpha(IF)V").contains(fake.name));
        // 手術後：兩個呼叫同形改道，其餘指令與 frames 不變；IsoPlayer 其餘方法不變（updateInternal2 是 W91、postupdateInternal 是 W94 的命中點，
        // 分別由 checkVehicleUpdateCuts、checkAnimalCuts 鎖）
        MethodNode p = method(distJava, player, "updateLOS", "()V");
        String expected = methodText(v)
                .replace("INVOKEVIRTUAL " + player + ".getCell " + cellDesc,
                        "INVOKESTATIC " + helper + ".getCell (L" + player + ";)Lzombie/iso/IsoCell;")
                .replace("INVOKEINTERFACE java/util/Set.iterator " + itDesc + " (itf)",
                        "INVOKESTATIC " + helper + ".iterator (Ljava/util/Set;)Ljava/util/Iterator;");
        boolean othersSame = classNodeFromJar(jar, player).methods.stream()
                .filter(m -> !(m.name.equals("updateLOS") && m.desc.equals("()V"))
                        && !(m.name.equals("updateInternal2") && m.desc.equals("()Z"))
                        && !(m.name.equals("postupdateInternal") && m.desc.equals("()V"))).allMatch(m -> {
                    try {
                        return methodText(m).equals(methodText(method(distJava, player, m.name, m.desc)));
                    } catch (Exception e) {
                        return false;
                    }
                });
        failed += check("W83 手術後：updateLOS 的 getCell／Set.iterator 同形改道 PlayerLosFilter，其餘指令與 frames 不變；IsoPlayer 其餘方法"
                        + "（W91 的 updateInternal2、W94 的 postupdateInternal 除外）不變",
                !expected.equals(methodText(v)) && methodText(p).equals(expected) && othersSame);
        MethodNode hIt = method(distJava, helper, "iterator", "(Ljava/util/Set;)Ljava/util/Iterator;");
        MethodNode hCell = method(distJava, helper, "getCell", "(L" + player + ";)Lzombie/iso/IsoCell;");
        MethodNode hHas = method(distJava, helper + "$Filter", "hasNext", "()Z");
        MethodNode pHas = method(distJava, helper + "$Plain", "hasNext", "()Z");
        MethodNode pNext = method(distJava, helper + "$Plain", "next", "()Ljava/lang/Object;");
        failed += check("W83 helper 契約：iterator 第一個呼叫就是原版 Set.iterator（恰 1 次）、getCell 第一個呼叫就是 IsoPlayer.getCell（恰 1 次）；"
                        + "Filter.hasNext 只經底層 hasNext／next 各 1 處取元素、不呼叫 remove；Plain（原版計時）的 hasNext／next "
                        + "各只委派底層同名方法 1 次、不呼叫 ServerLOS",
                callNames(hIt).get(0).equals("java/util/Set.iterator") && countCalls(hIt, "java/util/Set", "iterator") == 1
                && callNames(hCell).get(0).equals(player + ".getCell") && countCalls(hCell, player, "getCell") == 1
                && countExactCalls(hHas, Opcodes.INVOKEINTERFACE, iter, "hasNext", "()Z") == 1
                && countExactCalls(hHas, Opcodes.INVOKEINTERFACE, iter, "next", "()Ljava/lang/Object;") == 1
                && countCalls(hHas, iter, "remove") == 0
                && countCallsToOwner(pHas, iter) == 1 && countExactCalls(pHas, Opcodes.INVOKEINTERFACE, iter, "hasNext", "()Z") == 1
                && countCallsToOwner(pNext, iter) == 1 && countExactCalls(pNext, Opcodes.INVOKEINTERFACE, iter, "next", "()Ljava/lang/Object;") == 1
                && countCallsToOwner(pHas, los) + countCallsToOwner(pNext, los) == 0);
        return failed;
    }

    /** W85：42.21 原版 PathfindNative.addVehicle／removeVehicle 本體（去 debug 的 methodText SHA-256），{name, sha}。 */
    static final String[][] VEHICLE_SCOPE_LOCKED = {
            {"addVehicle", "8cd7f6b175570cf59e274145f9510292df03a81298830b3ec7fce72bd7b267a5"},
            {"removeVehicle", "03fa67e7408e4ebae8570b37df83c6b39f90cb02e1e9ffb865417ab55d365db2"},
    };

    /**
     * W85 只把動物附近的載具送進 native（docs/patches.md 2ct）：原版前提（任一項變紅＝TIS 改了載具進出 native 的方式，
     * 先重新分析，不改斷言讓它變綠）、同形手術、helper 契約。
     */
    static int checkVehicleScope(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String pkg = "zombie/pathfind/nativeCode/";
        String pfn = pkg + "PathfindNative";
        String helper = pkg + "MdcPathfindVehicleScope";
        String veh = "zombie/vehicles/BaseVehicle";
        String vehDesc = "(L" + veh + ";)V";
        String cell = "zombie/iso/IsoCell";
        String setDesc = "()Ljava/util/Set;";
        int virt = Opcodes.INVOKEVIRTUAL;
        MethodNode vReload = methodFromJar(jar, veh, "scriptReloaded", "(Z)V");
        MethodNode vAdd = methodFromJar(jar, veh, "addToWorld", "(Z)V");
        MethodNode vRemove = methodFromJar(jar, veh, "removeFromWorld", "()V");
        MethodNode vCheck = methodFromJar(jar, pfn, "checkUseNativeCode", "()V");
        MethodNode vMain = methodFromJar(jar, pfn, "updateMain", "()V");
        // 呼叫點普查：全 jar 只有這 5 個 add／remove，每個方法恰一個，receiver 是 PathfindNative.instance（checkUseNativeCode 是 this）
        AbstractInsnNode reRemove = firstCall(vReload, virt, pfn, "removeVehicle", vehDesc);
        AbstractInsnNode reAdd = firstCall(vReload, virt, pfn, "addVehicle", vehDesc);
        AbstractInsnNode[] instanceSites = {reRemove, reAdd, firstCall(vAdd, virt, pfn, "addVehicle", vehDesc),
                firstCall(vRemove, virt, pfn, "removeVehicle", vehDesc)};
        boolean receivers = true;
        for (AbstractInsnNode site : instanceSites) {
            AbstractInsnNode arg = site == null ? null : prevReal(site);
            receivers &= isVar(arg, Opcodes.ALOAD, 0)
                    && isField(prevReal(arg), Opcodes.GETSTATIC, pfn, "instance", "L" + pfn + ";");
        }
        AbstractInsnNode checkAdd = firstCall(vCheck, virt, pfn, "addVehicle", vehDesc);
        AbstractInsnNode checkArg = checkAdd == null ? null : prevReal(checkAdd);
        receivers &= checkArg instanceof VarInsnNode && checkArg.getOpcode() == Opcodes.ALOAD
                && isVar(prevReal(checkArg), Opcodes.ALOAD, 0);
        failed += check("W85 vanilla 前提：全 jar PathfindNative.addVehicle 恰 3 處（BaseVehicle.scriptReloaded／addToWorld、"
                        + "PathfindNative.checkUseNativeCode 各 1）、removeVehicle 恰 2 處（scriptReloaded／removeFromWorld 各 1）；"
                        + "BaseVehicle 的 receiver 是 PathfindNative.instance、參數 this，scriptReloaded 先 remove 緊接 add",
                jarWideCallsiteCensus(jar, virt, pfn, "addVehicle", vehDesc) == 3
                        && jarWideCallsiteCensus(jar, virt, pfn, "removeVehicle", vehDesc) == 2
                        && countExactCalls(vReload, virt, pfn, "addVehicle", vehDesc) == 1
                        && countExactCalls(vReload, virt, pfn, "removeVehicle", vehDesc) == 1
                        && countExactCalls(vAdd, virt, pfn, "addVehicle", vehDesc) == 1
                        && countExactCalls(vRemove, virt, pfn, "removeVehicle", vehDesc) == 1
                        && countExactCalls(vCheck, virt, pfn, "addVehicle", vehDesc) == 1
                        && receivers && reRemove != null && nextReal(nextReal(reRemove)) != null
                        && nextReal(nextReal(nextReal(reRemove))) == reAdd);
        // updateMain：唯一 getVehicles 接 Set.iterator；迴圈裡 vehicleState.get 為 null 或 check() 為 false 都跳過 updateVehicle
        AbstractInsnNode getVehicles = firstCall(vMain, virt, cell, "getVehicles", setDesc);
        AbstractInsnNode stateGet = firstCall(vMain, virt, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
        AbstractInsnNode[] loop = new AbstractInsnNode[10];
        AbstractInsnNode cur = stateGet;
        for (int i = 0; i < loop.length && cur != null; i++) {
            cur = nextReal(cur);
            loop[i] = cur;
        }
        boolean skipsStateless = stateGet != null
                && isField(prevReal(prevReal(stateGet)), Opcodes.GETFIELD, pfn, "vehicleState", "Ljava/util/HashMap;")
                && loop[0] instanceof TypeInsnNode cast && cast.desc.equals(pkg + "VehicleState")
                && loop[1] instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE
                && isVar(loop[2], Opcodes.ALOAD, store.var)
                && loop[3] instanceof JumpInsnNode ifNull && ifNull.getOpcode() == Opcodes.IFNULL
                && isVar(loop[4], Opcodes.ALOAD, store.var)
                && isCall(loop[5], virt, pkg + "VehicleState", "check", "()Z")
                && loop[6] instanceof JumpInsnNode ifEq && ifEq.getOpcode() == Opcodes.IFEQ && ifEq.label == ifNull.label
                && isVar(loop[7], Opcodes.ALOAD, 0)
                && isCall(loop[9], virt, pfn, "updateVehicle", vehDesc);
        failed += check("W85 vanilla 前提：updateMain 恰 1 個 IsoCell.getVehicles（緊接 Set.iterator）、1 個 vehicleState 讀取、"
                        + "1 個 updateVehicle，沒有 native 狀態（get 回 null）的車跳過 updateVehicle",
                countExactCalls(vMain, virt, cell, "getVehicles", setDesc) == 1 && getVehicles != null
                        && isCall(nextReal(getVehicles), Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;")
                        && countInsns(vMain, in -> isField(in, Opcodes.GETFIELD, pfn, "vehicleState", "Ljava/util/HashMap;")) == 1
                        && countExactCalls(vMain, virt, pfn, "updateVehicle", vehDesc) == 1
                        && skipsStateless);
        // 原版 addVehicle／removeVehicle 本體 SHA 鎖：add 一定建狀態並送 VehicleAddTask；remove 一定丟狀態，執行緒在就送
        // VehicleRemoveTask（沒有狀態也送）。vehNative 的鏡像與「沒送過的車不必 remove」都靠這兩個本體。
        ClassNode vNative = new ClassNode();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            new ClassReader(jf.getInputStream(jf.getJarEntry(pfn + ".class")).readAllBytes()).accept(vNative, ClassReader.SKIP_DEBUG);
        }
        for (String[] lock : VEHICLE_SCOPE_LOCKED) {
            MethodNode body = declaredMethod(vNative, lock[0], vehDesc);
            String got = sha256Hex(methodText(body));
            failed += check("W85 vanilla 前提：PathfindNative." + lock[0] + " 本體（去 debug 的 methodText SHA-256）與 42.21 相同",
                    got.equals(lock[1]));
            if (!got.equals(lock[1])) {
                System.out.println("  實際 SHA：" + got);
            }
            // 負對照：把本體第一個 null 判斷反過來（add：狀態已存在才新建；remove：沒有狀態才 release）→ SHA 鎖必須變紅
            JumpInsnNode firstNullJump = null;
            for (AbstractInsnNode in : body.instructions) {
                if (firstNullJump == null && in instanceof JumpInsnNode j
                        && (j.getOpcode() == Opcodes.IFNULL || j.getOpcode() == Opcodes.IFNONNULL)) {
                    firstNullJump = j;
                }
            }
            boolean flipped = firstNullJump != null;
            if (flipped) {
                body.instructions.set(firstNullJump, new JumpInsnNode(
                        firstNullJump.getOpcode() == Opcodes.IFNULL ? Opcodes.IFNONNULL : Opcodes.IFNULL, firstNullJump.label));
            }
            failed += check("W85 SHA 鎖負對照：PathfindNative." + lock[0] + " 第一個 null 判斷反過來 → SHA 鎖變紅",
                    got.equals(lock[1]) && flipped && !sha256Hex(methodText(body)).equals(lock[1]));
        }
        // 手術後：5 個 add／remove 與 getVehicles 同形改道，其餘指令與 frames 不變；兩個 class 其餘方法不變
        String addOld = "INVOKEVIRTUAL " + pfn + ".addVehicle " + vehDesc;
        String removeOld = "INVOKEVIRTUAL " + pfn + ".removeVehicle " + vehDesc;
        String addNew = "INVOKESTATIC " + helper + ".addVehicle (L" + pfn + ";L" + veh + ";)V";
        String removeNew = "INVOKESTATIC " + helper + ".removeVehicle (L" + pfn + ";L" + veh + ";)V";
        String gvOld = "INVOKEVIRTUAL " + cell + ".getVehicles " + setDesc;
        String gvNew = "INVOKESTATIC " + helper + ".getVehicles (L" + cell + ";)Ljava/util/Set;";
        List<String> drift = new ArrayList<>();
        for (MethodNode v : List.of(vReload, vAdd, vRemove)) {
            String want = methodText(v).replace(addOld, addNew).replace(removeOld, removeNew);
            if (want.equals(methodText(v)) || !want.equals(methodText(method(distJava, veh, v.name, v.desc)))) {
                drift.add(veh + "." + v.name + v.desc);
            }
        }
        // checkUseNativeCode 自己也有一個 getVehicles（offset 192，啟動迴圈的來源），只改 add；updateMain 只改 getVehicles
        for (MethodNode v : List.of(vCheck, vMain)) {
            String want = v == vCheck ? methodText(v).replace(addOld, addNew) : methodText(v).replace(gvOld, gvNew);
            if (want.equals(methodText(v)) || !want.equals(methodText(method(distJava, pfn, v.name, v.desc)))) {
                drift.add(pfn + "." + v.name + v.desc);
            }
        }
        // 同一個 BaseVehicle ClassPatch 上的其他刀：W3-4 update、W19 permanentlyRemove 頭部、W64 setNetPlayerAuthorization 頭部
        Set<String> touched = Set.of(veh + ".scriptReloaded(Z)V", veh + ".addToWorld(Z)V", veh + ".removeFromWorld()V",
                veh + ".update()V", veh + ".permanentlyRemove()V",
                veh + ".setNetPlayerAuthorization(L" + veh + "$Authorization;I)V",
                pfn + ".checkUseNativeCode()V", pfn + ".updateMain()V");
        for (String cls : List.of(veh, pfn)) {
            ClassNode distNode = classNode(distJava, cls);
            for (MethodNode m : classNodeFromJar(jar, cls).methods) {
                if (!touched.contains(cls + "." + m.name + m.desc)) {
                    MethodNode d = declaredMethod(distNode, m.name, m.desc);
                    if (d == null || !methodText(m).equals(methodText(d))) {
                        drift.add(cls + "." + m.name + m.desc);
                    }
                }
            }
        }
        if (!drift.isEmpty()) {
            System.out.println("  不符的方法：" + drift);
        }
        failed += check("W85 手術後：scriptReloaded／addToWorld／removeFromWorld／checkUseNativeCode 的 add／remove 與 updateMain 的 "
                        + "getVehicles 同形改道 MdcPathfindVehicleScope，其餘指令與 frames 不變；BaseVehicle 其餘方法（W3-4、W19、W64 "
                        + "的三個方法除外）與 PathfindNative 其餘方法不變",
                drift.isEmpty());
        MethodNode hAdd = method(distJava, helper, "addVehicle", "(L" + pfn + ";L" + veh + ";)V");
        MethodNode hRemove = method(distJava, helper, "removeVehicle", "(L" + pfn + ";L" + veh + ";)V");
        MethodNode hGet = method(distJava, helper, "getVehicles", "(L" + cell + ";)Ljava/util/Set;");
        MethodNode hRoute = method(distJava, helper, "route", "(IL" + veh + ";)Z");
        MethodNode hDrain = method(distJava, helper, "drain", "()V");
        failed += check("W85 helper 契約：addVehicle／removeVehicle 各只有 1 處呼叫原版同名方法、不呼叫另一個；route（決定送不送、"
                        + "背景呼叫排隊）不直接呼叫原版；drain（主執行緒排空佇列）add／remove 各 1 處；getVehicles 第一個呼叫"
                        + "就是 IsoCell.getVehicles（恰 1 次）",
                countExactCalls(hAdd, virt, pfn, "addVehicle", vehDesc) == 1 && countCalls(hAdd, pfn, "removeVehicle") == 0
                        && countExactCalls(hRemove, virt, pfn, "removeVehicle", vehDesc) == 1 && countCalls(hRemove, pfn, "addVehicle") == 0
                        && countCalls(hRoute, pfn, "addVehicle") + countCalls(hRoute, pfn, "removeVehicle") == 0
                        && countExactCalls(hDrain, virt, pfn, "addVehicle", vehDesc) == 1
                        && countExactCalls(hDrain, virt, pfn, "removeVehicle", vehDesc) == 1
                        && callNames(hGet).get(0).equals(cell + ".getVehicles") && countCalls(hGet, cell, "getVehicles") == 1);
        return failed;
    }

    /** {@code [from, to)} 之間最後一個「iload slot; jumpOp L」守衛，L 落在 {@code skipPast} 之後（且在 {@code before} 之前，null＝不限）。 */
    static boolean guardedBy(MethodNode m, AbstractInsnNode from, AbstractInsnNode to, int slot, int jumpOp,
            AbstractInsnNode skipPast, AbstractInsnNode before) {
        JumpInsnNode guard = null;
        for (AbstractInsnNode in = from; in != null && in != to; in = in.getNext()) {
            if (in instanceof JumpInsnNode j && j.getOpcode() == jumpOp && isVar(prevReal(j), Opcodes.ILOAD, slot)) {
                guard = j;
            }
        }
        int target = guard == null ? -1 : m.instructions.indexOf(guard.label);
        return guard != null && target > m.instructions.indexOf(skipPast)
                && (before == null || target < m.instructions.indexOf(before));
    }

    /** W83：updateLOS 迭代器存入 slot 9 之後到 hasNext 的 ifeq 出口之間的呼叫多重集合（排序後，一行一個「opcode owner.name desc」）。 */
    static String playerLosLoopInvokes(MethodNode v) {
        AbstractInsnNode store9 = null;
        for (AbstractInsnNode in : v.instructions) {
            if (isVar(in, Opcodes.ASTORE, 9) && store9 == null) {
                store9 = in;
            }
        }
        AbstractInsnNode hasNext = store9 == null ? null : nextReal(nextReal(store9));
        AbstractInsnNode exitJump = hasNext == null ? null : nextReal(hasNext);
        List<String> loopInvokes = new ArrayList<>();
        if (exitJump instanceof JumpInsnNode ej && ej.getOpcode() == Opcodes.IFEQ
                && isCall(hasNext, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")) {
            for (AbstractInsnNode in = store9; in != null && in != ej.label; in = in.getNext()) {
                if (in instanceof MethodInsnNode mi) {
                    loopInvokes.add(mi.getOpcode() + " " + mi.owner + "." + mi.name + mi.desc);
                }
            }
        }
        java.util.Collections.sort(loopInvokes);
        return String.join("\n", loopInvokes);
    }

    /**
     * W86：42.21 原版本體（去 debug 的 methodText SHA-256），{class, name, desc, sha}。helper 重做的兩個查詢與它們讀的 getter、
     * 原版每次比對都呼叫的 getUsername，以及等價論證依賴的這一輪呼叫圖（送出入口、逐連線、可見性判斷、派系／安全屋兩層）。
     * 鎖的是 jar 原版；W59 對 sendWorldMapPlayerPosition(UdpConnection) 的手術不影響。
     */
    static final String[][] WORLD_MAP_INDEX_LOCKED = {
            {"zombie/characters/Faction", "getPlayerFaction", "(Lzombie/characters/IsoPlayer;)Lzombie/characters/Faction;",
                    "01dd0bc6b794e50eae4171d629a9ab7260f1115c1630bda56404a55689cedc4e"},
            {"zombie/characters/Faction", "getOwner", "()Ljava/lang/String;",
                    "e8bbe5af870c008a2434a36d63cb2962ffffa893704cc7237ab74f81a1369cf6"},
            {"zombie/characters/Faction", "getPlayers", "()Ljava/util/ArrayList;",
                    "a2888b68e388a3bf3df04e633175f11dcc678fa8a2e43765d998f13abdf14bd3"},
            {"zombie/iso/areas/SafeHouse", "isInSameSafehouse", "(Ljava/lang/String;Ljava/lang/String;)Z",
                    "f143771941a65b757bce357203ddfc750f81d6fce17aabe1d26530c3f5193534"},
            {"zombie/iso/areas/SafeHouse", "playerAllowed", "(Ljava/lang/String;)Z",
                    "f2aa0936cc931a0853eddbf8ee64ec59292490c64014547af967e66509a9e2a7"},
            {"zombie/iso/areas/SafeHouse", "getOwner", "()Ljava/lang/String;",
                    "602c4a1b818a2bd5157578f17b8da7ecbfe6e7f619f8785bac1900bc64bdd1a9"},
            {"zombie/iso/areas/SafeHouse", "getPlayers", "()Ljava/util/ArrayList;",
                    "bcbb756e405fdd452c136288513f1295a36b7f469495f14084b14d6907a97b34"},
            {"zombie/iso/areas/SafeHouse", "getSafehouseList", "()Ljava/util/ArrayList;",
                    "7b349383f7a52cb46add4f1651f9b0aae5a9fd00af11b6602f1812a837bed330"},
            {"zombie/characters/IsoPlayer", "getUsername", "()Ljava/lang/String;",
                    "dfca414979f2d3be6228ec8fcf0267feb39da88f18eb6d95c3fd0bd31817d05a"},
            {"zombie/characters/IsoPlayer", "getUsername", "(Ljava/lang/Boolean;Ljava/lang/Boolean;)Ljava/lang/String;",
                    "1b517fe59ecf84d858d202514e3f772731988ee60fafb0abe18501a7c7b87e3c"},
            {"zombie/network/GameServer", "sendWorldMapPlayerPosition", "()V",
                    "06683b65be8c706cff81ed90459fc72f237bb1d78fdeddb70cdf43dbbb0ea039"},
            {"zombie/network/GameServer", "sendWorldMapPlayerPosition", "(Lzombie/core/raknet/UdpConnection;)V",
                    "422e9505070197da696a60010435b7a75481b6b44081f244edcc5b8c5a7ef30e"},
            {"zombie/network/GameServer", "shouldSendWorldMapPlayerPosition",
                    "(Lzombie/core/raknet/UdpConnection;Lzombie/characters/IsoPlayer;)Z",
                    "36dfb53103ac5814036a644b5caedda454343a023743ea5bb6ff110a8eabdf02"},
            {"zombie/network/GameServer", "isAnyPlayerInSameFaction",
                    "(Lzombie/core/raknet/UdpConnection;Lzombie/characters/IsoPlayer;)Z",
                    "4a69cc43d0646adf2f5ec659fb45730f37e91d6276d1a373ad2283de106fddc4"},
            {"zombie/network/GameServer", "isInSameFaction", "(Lzombie/characters/IsoPlayer;Lzombie/characters/IsoPlayer;)Z",
                    "3f03bf8393fe6e18a2d0bcb45fa9d2b9b9986a7704d474d28ff21bf7f4e7a422"},
            {"zombie/network/GameServer", "isAnyPlayerInSameSafehouse",
                    "(Lzombie/core/raknet/UdpConnection;Lzombie/characters/IsoPlayer;)Z",
                    "8b0b3397ee8560b0dc5fed3c18909d00a85f532fc4fa881b18b3036b71977abf"},
    };

    /** W86（docs/patches.md 2cu）：GameServer 三個方法四處同形改道 WorldMapVisibilityIndex，語境與其餘指令不變。 */
    static int checkWorldMapVisibilityIndex(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String gs = "zombie/network/GameServer";
        String helper = "zombie/mdc/WorldMapVisibilityIndex";
        String player = "zombie/characters/IsoPlayer";
        String faction = "zombie/characters/Faction";
        String safe = "zombie/iso/areas/SafeHouse";
        String facDesc = "(L" + player + ";)L" + faction + ";";
        String shDesc = "(Ljava/lang/String;Ljava/lang/String;)Z";
        String mainDesc = "([Ljava/lang/String;)V";
        // {方法, desc, 原 owner, 原 name, 原 desc（＝helper desc）, helper name}
        String[][] sites = {
                {"main", mainDesc, gs, "sendWorldMapPlayerPosition", "()V", "round"},
                {"isInSameFaction", "(L" + player + ";L" + player + ";)Z", faction, "getPlayerFaction", facDesc, "getPlayerFaction"},
                {"isAnyPlayerInSameSafehouse", "(Lzombie/core/raknet/UdpConnection;L" + player + ";)Z", safe, "isInSameSafehouse",
                        shDesc, "isInSameSafehouse"}};
        int[] hits = {1, 2, 1};
        ClassNode vGs = classNodeFromJar(jar, gs);
        ClassNode pGs = classNode(distJava, gs);
        boolean vanillaOk = jarWideCallers(jar, Opcodes.INVOKESTATIC, gs, "sendWorldMapPlayerPosition", "()V")
                .equals(List.of(gs + ".main" + mainDesc))
                && classWideCalls(vGs, Opcodes.INVOKESTATIC, faction, "getPlayerFaction", facDesc) == 2
                && classWideCalls(vGs, Opcodes.INVOKESTATIC, safe, "isInSameSafehouse", shDesc) == 1
                && classWideCalls(vGs, Opcodes.INVOKESTATIC, gs, sites[1][0], sites[1][1]) == 1
                && classWideCalls(vGs, Opcodes.INVOKESTATIC, gs, sites[2][0], sites[2][1]) == 1;
        boolean patchedOk = true;
        for (int i = 0; i < sites.length; i++) {
            String[] s = sites[i];
            MethodNode v = methodFromJar(jar, gs, s[0], s[1]);
            MethodNode p = method(distJava, gs, s[0], s[1]);
            vanillaOk &= wmIndexContextOk(v, s[2], s[3], s[4], hits[i]);
            patchedOk &= wmIndexContextOk(p, helper, s[5], s[4], hits[i])
                    && countExactCalls(p, Opcodes.INVOKESTATIC, s[2], s[3], s[4]) == 0
                    && classWideCalls(pGs, Opcodes.INVOKESTATIC, helper, s[5], s[4]) == hits[i];
            String expected = methodText(v).replace("INVOKESTATIC " + s[2] + "." + s[3] + " " + s[4],
                    "INVOKESTATIC " + helper + "." + s[5] + " " + s[4]);
            patchedOk &= !expected.equals(methodText(v)) && expected.equals(methodText(p));
        }
        failed += check("W86 vanilla 前提：sendWorldMapPlayerPosition() 全 jar 只有 GameServer.main 呼叫 1 次（前面 limiter.Check→ifeq、後面 goto、"
                        + "恰被一個 Exception 例外區塊包住）；isInSameFaction 兩個 getPlayerFaction 接 astore_2／astore_3；isAnyPlayerInSameSafehouse "
                        + "唯一 isInSameSafehouse 前面是兩個 getUsername、後面 ifeq；兩個私有方法各只有 1 個呼叫點",
                vanillaOk);
        failed += check("W86 手術後：四處同形改道 WorldMapVisibilityIndex（round／getPlayerFaction×2／isInSameSafehouse），語境、例外區塊與其餘指令不變",
                patchedOk);
        MethodNode round = method(distJava, helper, "round", "()V");
        failed += check("W86 helper 契約：round 恰呼叫原版 sendWorldMapPlayerPosition() 1 次，只有 finally、不吞例外",
                countExactCalls(round, Opcodes.INVOKESTATIC, gs, "sendWorldMapPlayerPosition", "()V") == 1
                && !round.tryCatchBlocks.isEmpty() && round.tryCatchBlocks.stream().allMatch(t -> t.type == null));
        // 原版本體 SHA 鎖（任一變紅＝原版查詢或這一輪的呼叫圖改了，先重新核對等價，不改 SHA 讓它變綠）。
        // 負對照：有條件跳轉的方法把第一個條件跳轉反過來，沒有的（getter）把第一條指令換成 NOP，SHA 都必須變紅。
        Map<String, ClassNode> lockClasses = new HashMap<>();
        for (String[] lock : WORLD_MAP_INDEX_LOCKED) {
            ClassNode cn = lockClasses.get(lock[0]);
            if (cn == null) {
                cn = new ClassNode();
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
                    new ClassReader(jf.getInputStream(jf.getJarEntry(lock[0] + ".class")).readAllBytes())
                            .accept(cn, ClassReader.SKIP_DEBUG);
                }
                lockClasses.put(lock[0], cn);
            }
            String what = lock[0].substring(lock[0].lastIndexOf('/') + 1) + "." + lock[1] + lock[2];
            MethodNode body = declaredMethod(cn, lock[1], lock[2]);
            String got = body == null ? "missing" : sha256Hex(methodText(body));
            failed += check("W86 vanilla 前提：" + what + " 本體（去 debug 的 methodText SHA-256）與 42.21 相同", got.equals(lock[3]));
            if (!got.equals(lock[3])) {
                System.out.println("  實際 SHA：" + got);
            }
            String mutation = body == null ? "none" : wmIndexMutate(body);
            failed += check("W86 SHA 鎖負對照：" + what + " " + mutation + " → SHA 鎖變紅",
                    got.equals(lock[3]) && !mutation.equals("none") && !sha256Hex(methodText(body)).equals(lock[3]));
        }
        return failed;
    }

    /** W86 命中點語境；vanilla 與手術後共用，只換被呼叫者。 */
    static boolean wmIndexContextOk(MethodNode m, String owner, String name, String desc, int want) {
        List<AbstractInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (isCall(in, Opcodes.INVOKESTATIC, owner, name, desc)) {
                calls.add(in);
            }
        }
        if (calls.size() != want) {
            return false;
        }
        AbstractInsnNode c = calls.get(0);
        switch (m.name) {
            case "main": {
                AbstractInsnNode ifeq = prevReal(c);
                AbstractInsnNode check = prevReal(ifeq);
                AbstractInsnNode after = nextReal(c);
                int guarded = 0;
                for (TryCatchBlockNode t : m.tryCatchBlocks) {
                    if (nextReal(t.start) == c && nextReal(t.end) == after && "java/lang/Exception".equals(t.type)
                            && isVar(nextReal(t.handler), Opcodes.ASTORE, 26)) {
                        guarded++;
                    }
                }
                return ifeq.getOpcode() == Opcodes.IFEQ
                        && isCall(check, Opcodes.INVOKEVIRTUAL, "zombie/core/utils/UpdateLimit", "Check", "()Z")
                        && isField(prevReal(check), Opcodes.GETSTATIC, "zombie/network/GameServer",
                                "sendWorldMapPlayerPositionLimiter", "Lzombie/core/utils/UpdateLimit;")
                        && after.getOpcode() == Opcodes.GOTO && guarded == 1;
            }
            case "isInSameFaction":
                return isVar(prevReal(c), Opcodes.ALOAD, 0) && isVar(nextReal(c), Opcodes.ASTORE, 2)
                        && isVar(prevReal(calls.get(1)), Opcodes.ALOAD, 1) && isVar(nextReal(calls.get(1)), Opcodes.ASTORE, 3);
            default: {
                String getName = "()Ljava/lang/String;";
                AbstractInsnNode u2 = prevReal(c);
                AbstractInsnNode u1 = prevReal(prevReal(u2));
                return isCall(u2, Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoPlayer", "getUsername", getName)
                        && isVar(prevReal(u2), Opcodes.ALOAD, 1)
                        && isCall(u1, Opcodes.INVOKEVIRTUAL, "zombie/characters/IsoPlayer", "getUsername", getName)
                        && isVar(prevReal(u1), Opcodes.ALOAD, 3)
                        && nextReal(c).getOpcode() == Opcodes.IFEQ;
            }
        }
    }

    /** W86 SHA 鎖負對照：第一個條件跳轉反過來；沒有條件跳轉就把第一條真指令換成 NOP。回傳做了什麼。 */
    static String wmIndexMutate(MethodNode m) {
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof JumpInsnNode j && j.getOpcode() != Opcodes.GOTO && j.getOpcode() != Opcodes.JSR) {
                int op = j.getOpcode();
                int flipped = op == Opcodes.IFNULL ? Opcodes.IFNONNULL : op == Opcodes.IFNONNULL ? Opcodes.IFNULL
                        : (op - Opcodes.IFEQ) % 2 == 0 ? op + 1 : op - 1;
                m.instructions.set(j, new JumpInsnNode(flipped, j.label));
                return "第一個條件跳轉反過來";
            }
        }
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() >= 0) {
                m.instructions.set(in, new InsnNode(Opcodes.NOP));
                return "第一條指令換成 NOP";
            }
        }
        return "none";
    }

    /**
     * W87：42.21 原版本體（去 debug 的 methodText SHA-256），{class, name, desc, sha}。helper 照抄 CreateStep1 略過資料夾的判斷；
     * 改寫後的清單靠 getLotDirectories 的「;」清單分支解析（伺服器與客戶端同一個方法），經 writeGameMap 送出、TestTCP 收下；
     * 存在理由是 higherPriority300x300CellExists 拿 priority 當 IsoLot.MapFiles 的索引。
     */
    static final String[][] MAP_FOLDER_LOCKED = {
            {"zombie/iso/IsoMetaGrid", "CreateStep1", "()V",
                    "25818e049ce08e32f26346125aa09269dc4db3c5a09681769fa2ca3c0d4e8df9"},
            {"zombie/iso/IsoMetaGrid", "getLotDirectories", "()Ljava/util/ArrayList;",
                    "e89dc27e2e584d740b26b04739e396f3c2060f109e012fba04d8efc6ff7f4e1c"},
            {"zombie/iso/IsoMetaGrid", "higherPriority300x300CellExists", "(III)Z",
                    "2fe77364d81adb723fd7137f2ae4a8235f1951060f07847eb2d0f91bdb01d812"},
            {"zombie/network/ConnectionDetails", "writeGameMap", "(Lzombie/core/network/ByteBufferWriter;)V",
                    "c0caf478a9602a97855ea79f32d73bcf9bc8d53acb8fbef0f79cb39d05652450"},
            {"zombie/gameStates/ConnectToServerState", "TestTCP", "()V",
                    "524d1bcbb4146a9797c93cb57eb4af2f63decb0227034f2a79e23bcf42b1bb76"},
    };

    /** W87（docs/patches.md 2cv）：IsoMetaGrid.CreateStep1 唯一 getLotDirectories() 同形改道 MapFolderGuard，其餘指令不變。 */
    static int checkMapFolderGuard(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String grid = "zombie/iso/IsoMetaGrid";
        String helper = "zombie/mdc/MapFolderGuard";
        String gs = "zombie/network/GameServer";
        String listDesc = "()Ljava/util/ArrayList;";
        String helperDesc = "(L" + grid + ";)Ljava/util/ArrayList;";
        MethodNode v = methodFromJar(jar, grid, "CreateStep1", "()V");
        MethodNode p = method(distJava, grid, "CreateStep1", "()V");
        AbstractInsnNode call = null;
        AbstractInsnNode ctor = null;
        boolean counter = false;
        for (AbstractInsnNode in : v.instructions) {
            if (isCall(in, Opcodes.INVOKEVIRTUAL, grid, "getLotDirectories", listDesc)) {
                call = in;
            } else if (isCall(in, Opcodes.INVOKESPECIAL, "zombie/iso/MapFiles", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V")) {
                ctor = in;
            } else if (in instanceof IincInsnNode inc && inc.var == 4 && inc.incr == 1) {
                counter = true;
            }
        }
        failed += check("W87 vanilla 前提：CreateStep1 恰一個 getLotDirectories()（aload_0 → 呼叫 → astore_3），MapFiles 建構子恰一個、"
                        + "priority 參數是迴圈計數 iload 4（iinc 4 1）",
                countExactCalls(v, Opcodes.INVOKEVIRTUAL, grid, "getLotDirectories", listDesc) == 1
                        && isVar(prevReal(call), Opcodes.ALOAD, 0) && isVar(nextReal(call), Opcodes.ASTORE, 3)
                        && countExactCalls(v, Opcodes.INVOKESPECIAL, "zombie/iso/MapFiles", "<init>",
                                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V") == 1
                        && isVar(prevReal(ctor), Opcodes.ILOAD, 4) && counter);
        // 改寫 GameServer.gameMap 等於改送給客戶端的清單：讀者與寫者清冊釘死，新增讀者（例如開服後才讀的）或之後再寫都轉紅。
        Map<String, Integer> gets = jarWideFieldRefs(jar, Opcodes.GETSTATIC, gs, "gameMap");
        Map<String, Integer> puts = jarWideFieldRefs(jar, Opcodes.PUTSTATIC, gs, "gameMap");
        failed += check("W87 vanilla 前提：GameServer.gameMap 的讀者只有 main、IsoMetaGrid／MapFiles 的 getLotDirectories、"
                        + "ConnectionDetails.writeGameMap，寫者只有 <clinit> 與 main（實際 讀 " + gets + " 寫 " + puts + "）",
                gets.equals(Map.of(gs + ".main", 2, grid + ".getLotDirectories", 1, "zombie/iso/MapFiles.getLotDirectories", 1,
                        "zombie/network/ConnectionDetails.writeGameMap", 1))
                        && puts.equals(Map.of(gs + ".<clinit>", 1, gs + ".main", 1)));
        String expected = methodText(v).replace("INVOKEVIRTUAL " + grid + ".getLotDirectories " + listDesc,
                "INVOKESTATIC " + helper + ".getLotDirectories " + helperDesc);
        failed += check("W87 手術後：CreateStep1 唯一 getLotDirectories() 同形改道 MapFolderGuard，其餘指令與 frames 保留",
                !expected.equals(methodText(v)) && expected.equals(methodText(p))
                        && countExactCalls(p, Opcodes.INVOKEVIRTUAL, grid, "getLotDirectories", listDesc) == 0);
        MethodNode h = method(distJava, helper, "getLotDirectories", helperDesc);
        boolean callInTry = false;
        for (AbstractInsnNode in : h.instructions) {
            if (isCall(in, Opcodes.INVOKEVIRTUAL, grid, "getLotDirectories", listDesc)) {
                int at = h.instructions.indexOf(in);
                for (TryCatchBlockNode t : h.tryCatchBlocks) {
                    callInTry |= h.instructions.indexOf(t.start) <= at && at < h.instructions.indexOf(t.end);
                }
            }
        }
        failed += check("W87 helper 契約：改道目標恰呼叫原版 getLotDirectories() 1 次，不在 try 內（例外照原版上拋）",
                countExactCalls(h, Opcodes.INVOKEVIRTUAL, grid, "getLotDirectories", listDesc) == 1 && !callInTry);
        // 原版本體 SHA 鎖（任一變紅＝原版算 priority、解析清單或送清單的方式改了，先重新核對；TIS 修掉 priority 時也會紅＝重估撤刀）。
        Map<String, ClassNode> lockClasses = new HashMap<>();
        for (String[] lock : MAP_FOLDER_LOCKED) {
            ClassNode cn = lockClasses.get(lock[0]);
            if (cn == null) {
                cn = new ClassNode();
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
                    new ClassReader(jf.getInputStream(jf.getJarEntry(lock[0] + ".class")).readAllBytes())
                            .accept(cn, ClassReader.SKIP_DEBUG);
                }
                lockClasses.put(lock[0], cn);
            }
            String what = lock[0].substring(lock[0].lastIndexOf('/') + 1) + "." + lock[1] + lock[2];
            MethodNode body = declaredMethod(cn, lock[1], lock[2]);
            String got = body == null ? "missing" : sha256Hex(methodText(body));
            failed += check("W87 vanilla 前提：" + what + " 本體（去 debug 的 methodText SHA-256）與 42.21 相同", got.equals(lock[3]));
            if (!got.equals(lock[3])) {
                System.out.println("  實際 SHA：" + got);
            }
            String mutation = body == null ? "none" : wmIndexMutate(body);
            failed += check("W87 SHA 鎖負對照：" + what + " " + mutation + " → SHA 鎖變紅",
                    got.equals(lock[3]) && !mutation.equals("none") && !sha256Hex(methodText(body)).equals(lock[3]));
        }
        return failed;
    }

    /**
     * W89 閒置車降頻的原版前提（去 debug 的 methodText SHA-256），{class, name, desc, sha}。研究 §4c 的 1–8 項：startFrame 與伺服器
     * 回 FULL（1、2）、update／postupdate 走同一個 bucket（3）、跳過的四段本體與 ECS frameStep（4）、會動就會讓 isEnable 變 true（5）、
     * 授權是 Server 就沒有客戶端能推（6）、Idle 引擎沒有每幀工作、耗電照遊戲時間（7）、車仍釘在 FULL（8）；再加閒置條件讀的 getter
     * （純讀取、沒有副作用）與 O(全部車) 的拖車重連。
     * 2026-11-10 42.22.2 重核後換值的 6 個：startFrame（新增殭屍追擊計數，車的 add 語境不變）、BaseVehicle.update（新增等著重掛拖車
     * 5 秒放棄的牆鐘計時＝閒置條件加 isWaitingToReattachTowingPartner；constraintChangedTime 在物理沒開時續期＝只會更不閒置）、
     * constraintChanged（setPhysicsActive 改看 CarController.shouldBeActive）、VehiclePhysicsPacket.processServer（hasAuthorization 仍在最前，
     * 只多丟舊包與時差 ≥1 秒的包）、hitCharacter（速度抽成等價的 getImpactVelocity）、tryReconnectToTowedVehicle（周圍 chunk 不足時早退）。
     */
    static final String[][] IDLE_VEHICLE_LOCKED = {
            {"zombie/MovingObjectUpdateScheduler", "startFrame", "()V", "ea171492c27615efbbf09af745e203f974deba65f2ee581cae11735887b3cf36"},
            {"zombie/MovingObjectUpdateScheduler", "getUpdateSchedulerSimulationLevelForObject",
                    "(Lzombie/iso/IsoMovingObject;F)Lzombie/UpdateSchedulerSimulationLevel;",
                    "7d2479cb7a9264fbdfe7b6609be27b9a213c89994cbcdd8f623ca667c52cbe00"},
            {"zombie/UpdateSchedulerSimulationLevel", "getFrameMod", "()I", "f0fe31180d7d5fd02b18765a3f19b03c4d5b8d58bd5e39a2c8386874dcc1b6bd"},
            {"zombie/MovingObjectUpdateScheduler", "update", "()V", "ab998fda2eec1bb15e6f419e5be622277fa5adf469754b15126a46ba9c1705d4"},
            {"zombie/MovingObjectUpdateScheduler", "postupdate", "()V", "8e6908375728f9a6cf89e95a50762f1d57705742e120b9e3fb2e292f19562c0f"},
            {"zombie/MovingObjectUpdateSchedulerUpdateBucket", "update", "(I)V", "81d074ad97fd3acc442dcd0972b4993cc1f58ec16b2f97e25be88605de60c9c1"},
            {"zombie/MovingObjectUpdateSchedulerUpdateBucket", "postupdate", "(I)V",
                    "e7f8937e47e7fa7cbc233d6a33f8ac4a23edcfd0ef0846edfb51bdc1931248c3"},
            {"zombie/MovingObjectUpdateSchedulerUpdateBucket", "add", "(Lzombie/iso/IsoMovingObject;)V",
                    "fadacf1cfbccb4a72b18454fb3efafad0282ddc55a01ca54f9bc93abbc7c03ee"},
            {"zombie/vehicles/BaseVehicle", "update", "()V", "892215e297cbba16deeb35a9cf9593af2b03963da45bbfdb8e4cbf3cf4e84f70"},
            {"zombie/vehicles/BaseVehicle", "postupdate", "()V", "9a0cebc19da0cb45e57b66bb24bbb7b851a357f1e2f73dc0906e4e110a550666"},
            {"zombie/vehicles/BaseVehicle", "shouldAnimRecorderBeActive", "()Z", "6e966828a2229cf8a69cab2b2befef73dd79c4aaea70ea132309e225ecb0eb66"},
            {"zombie/iso/IsoMovingObject", "update", "()V", "608cfb0faa8c2dab03e692960b1773ac130a9241ea1478807e305df0dcfa0ef5"},
            {"zombie/iso/IsoMovingObject", "preupdate", "()V", "4ed2e793ab60e4acaeeb6c836345a39fb76032759bdf9588c8b88c69075ebab4"},
            {"zombie/characters/ecs/ECSEntity", "frameStep", "()V", "678fed6cbfdac91045dc89576aafdcb0975a93cbc09b09cdcd4d70f6b803b1a4"},
            {"zombie/vehicles/BaseVehicle", "setPhysicsActive", "(Z)V", "5e88424e48e11f2f92ca7b0d7948c0d8f921a6f460a6ca4fb663153cb1e6ba03"},
            {"zombie/vehicles/BaseVehicle", "setPhysicsActive", "(ZZ)V", "07017fb998f7a78942cc3df1c16390aedff124039adcc7b7bcb5a8b2b806edf0"},
            {"zombie/vehicles/BaseVehicle", "constraintChanged", "()V", "efad755a5f7981d93d231e387787a31fc6bb33a4ae515e92a4901c15697f668d"},
            {"zombie/vehicles/BaseVehicle", "applyImpulseFromHitObject", "(Lzombie/iso/IsoObject;F)V",
                    "1b99b9eb3692fec3a29c8bbf5d43162d2a79276b0b58dfb91df224e68f421b30"},
            {"zombie/vehicles/BaseVehicle", "applyImpulseFromHitPlant", "(Lzombie/iso/IsoObject;F)V",
                    "926fae758c1b654c1788a16df50f851ca1cce7fab24e4ef8a063e03019c3b361"},
            {"zombie/vehicles/BaseVehicle", "applyImpulseGeneric", "(FFFFFFF)V", "0bbccb865607d384f8642ec61ff6a2efb518b1c078973fdea911c99c931f65b7"},
            {"zombie/network/packets/vehicle/VehiclePhysicsPacket", "processServer",
                    "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V",
                    "b2f68fa0b6c2917d164b67d7c7f2d6f7eda8e6d845a0281ffb5781590f8eb67f"},
            {"zombie/vehicles/BaseVehicle", "hasAuthorization", "(Lzombie/core/raknet/UdpConnection;)Z",
                    "a44e5e352cf853f5bcf3a60c6e2bca4a1c0b1167fac864de59ba46adcae85587"},
            {"zombie/vehicles/VehicleEngine", "update", "()V", "a526764eacec764e5da52ba3a3db1fbc2fec07c46a4a6d8c86a34a497e4cfcaa"},
            {"zombie/vehicles/VehicleAlarm", "update", "()V", "578d26d2b9bb65a8d05eaf11e4682dffbe5b2c722ba2094578c4c53b2c8beb07"},
            {"zombie/vehicles/VehicleAlarm", "isActive", "()Z", "aa0f1b665a3691f8598937d587d6973fc2c10db40af35c88d2ed7b344090c94a"},
            {"zombie/vehicles/BaseVehicle", "drainBatteryUpdateHack", "()V", "93a14c4cd464b8773ee19c7b698db36f251fe024ab73e285c8b8fd23997266a9"},
            {"zombie/vehicles/VehicleParts", "updatePart", "(Lzombie/vehicles/VehiclePart;)Z",
                    "66595b44924398ff1c0f17721775a1f36b1ad9c04150e1cc247724ba084be8da"},
            {"zombie/radio/devices/DeviceData", "update", "(ZZ)V", "4d864f7f62bb3a2a43bdd1a2d0cb0dda6fcc497c7521a3af4e2d788000d465bc"},
            // 收音機／CD 的每幀計數器（開著就不算閒置）與 devicesOn 的零件迴圈
            {"zombie/vehicles/VehiclePart", "updateSignalDevice", "()V", "6e0e9cce1c46794d6cc1d6bfedd498b65301ba28674fb329b69e1988f4fc1318"},
            {"zombie/radio/devices/DeviceData", "updateSimple", "()V", "340bf2968e90cc28c453cbc138048be37a5e127db43a6da6fb9c66e3b70c93a7"},
            {"zombie/radio/devices/DeviceData", "updateMediaPlaying", "()V", "39cbd44cec405416891892428465b72463e5cc6eaf830ce51dba4f365c014aff"},
            {"zombie/radio/devices/DeviceData", "updateStaticSounds", "()V", "9e7b7abbc4ad09cb74cd2d5f8bb863d375052c6c7a4ab63086088179bf917632"},
            {"zombie/vehicles/BaseVehicle", "getParts", "()Lzombie/vehicles/VehicleParts;", "57203f3a4b390214a1c23a4378ecb29977211504113549f6d39abeced0526caf"},
            {"zombie/vehicles/VehicleParts", "size", "()I", "2d272320927ea62dacfd016d2916ba98e540ac5b5cf233e02602071fe65e9dec"},
            {"zombie/vehicles/VehicleParts", "get", "(I)Lzombie/vehicles/VehiclePart;", "662ec47d5652e125749fd3b87ad6c8203a2b2314a3c3c680c181708bac0eb3b5"},
            {"zombie/vehicles/VehicleParts", "getPartCount", "()I", "30a06426e2a35448504665850b78f8b55d911272c779afb8379e82671cedb7e5"},
            {"zombie/vehicles/VehicleParts", "getPartByIndex", "(I)Lzombie/vehicles/VehiclePart;",
                    "56be93115c2be8e832fefb46a7b146508b10feec924d997bfb671a95b7140c05"},
            {"zombie/vehicles/VehiclePart", "getDeviceData", "()Lzombie/radio/devices/DeviceData;",
                    "69f35dd8fb6cb1afc0f366dd05639d114f36f77f14f0e9fe99686b312494fb05"},
            {"zombie/radio/devices/DeviceData", "getIsTurnedOn", "()Z", "5b98df040c85fe11de46a41518a85aad01347bba10fe39ae56209e7fda2a6c04"},
            // 接觸時鐘（暖機 5 秒的理由）與「閒置車速度 < 0.05 不新增接觸」
            {"zombie/vehicles/VehiclePedestrianContactTracking", "postUpdate",
                    "(Lzombie/vehicles/BaseVehicle;Lzombie/util/lambda/Invokers$Params2$ICallback;)V",
                    "31ecce83f7c82fee82519c5e31cfb9e04826ba54e73b14c0f73ba010c23a92d7"},
            {"zombie/vehicles/VehiclePedestrianContactTracking", "updateTimeNow", "()V", "eb8df1319199e2eb80ae6e963e3d3f7381e5b51a09a1f3cf65ea81bd6b8890c1"},
            {"zombie/vehicles/VehiclePedestrianContactTracking", "clearOldContacts", "()V", "44ca74eea8281c7efbfaeabe243ecbdb4b8049c92ce742b0ca7223c6a2ec594f"},
            {"zombie/vehicles/BaseVehicle", "hitCharacter", "(Lzombie/characters/IsoGameCharacter;Lzombie/iso/Vector2;)F",
                    "815b191f43d74aebe0616c95f2969e4bb13be430ab4baa76f23a2ce6857bba06"},
            {"zombie/vehicles/BaseVehicle", "getImpactVelocity", "()F", "1e834402229ec44b5674cdaa9c9ab72e9dba9436b324230653d3c4a9104674d4"},
            {"zombie/vehicles/BaseVehicle", "hitAnimal", "(Lzombie/characters/animals/IsoAnimal;)V",
                    "d93001de17ea33ff1704d5a0214d683b80182427141087cd9c035b62dc0c8273"},
            // 暖機累加的物理秒＝接觸時鐘 updateTimeNow 每次加的值
            {"zombie/GameTime", "getPhysicsSecondsSinceLastUpdate", "()F", "d1bf720cdccc1b1818607c5d0bdffa53e7a54c2ccd24594270505860d3607e99"},
            {"zombie/GameTime", "getRealworldSecondsSinceLastUpdate", "()F", "b7dc25014b2e2b8cb8f7f45d158cc1bdd625b3246b833b7d051992d3e411bae8"},
            {"zombie/vehicles/BaseVehicle", "getMinimumSimulationLevel", "()Lzombie/UpdateSchedulerSimulationLevel;",
                    "8d7cdda40ffacf8be6e3c6239ecb2699f6bbc1a287fe46a0edaa8dee4c895c07"},
            {"zombie/vehicles/BaseVehicle", "getController", "()Lzombie/core/physics/CarController;",
                    "c548763eb88c0c7c3ec9b8b6bc2350687942a18164a6900ac48a3a97bf73a2cf"},
            {"zombie/vehicles/BaseVehicle", "getMaxPassengers", "()I", "2b99195df3cb7bf734f0e254cd46445b2b7dfde00b5ff27466d934aa707e58da"},
            {"zombie/vehicles/BaseVehicle", "getPassenger", "(I)Lzombie/vehicles/BaseVehicle$Passenger;",
                    "682a0d355a613c86c41acc4194d905e1bac1cb8b67db5997f5f6a439881845a0"},
            {"zombie/vehicles/BaseVehicle", "getAnimals", "()Ljava/util/ArrayList;", "20c208c49c4eceadd7aa0278d3e4114525510a4ea05f155a4c1ad1369e27c00c"},
            {"zombie/vehicles/BaseVehicle", "getVehicleTowing", "()Lzombie/vehicles/BaseVehicle;",
                    "15ff74f09b3e701d2dc50d97484d0dc69cd5b45b6caa9d7b81203108ed4ebe9b"},
            {"zombie/vehicles/BaseVehicle", "getVehicleTowedBy", "()Lzombie/vehicles/BaseVehicle;",
                    "587b5b281b793b841a449fb1a1e69d3f0c46e4592479167ae741a6b7a70f917b"},
            {"zombie/vehicles/BaseVehicle", "getEngineState", "()Lzombie/vehicles/BaseVehicle$engineStateTypes;",
                    "1cf6170850acbcc3d3e9dbc8bed5bc4a41dd0b89f53389fc70f40f8759b2e5e4"},
            {"zombie/vehicles/VehiclePartOwner", "getEngine", "()Lzombie/vehicles/VehiclePart;",
                    "bb3aa49f295f9f1df1099d6ebe15c4df847060f2ea1f102e7d9b4b3bcce3b154"},
            {"zombie/vehicles/VehicleParts", "getEngine", "()Lzombie/vehicles/VehiclePart;",
                    "cf76e078351e58f5315d0bdc34b06ab480eec7a4b48c355c05f0ad5602df7cba"},
            {"zombie/vehicles/VehicleEngine", "getState", "()Lzombie/vehicles/BaseVehicle$engineStateTypes;",
                    "6afd9f7b645075bb10709d5413ce6c0745c94fa447ac04f518b09237d510f40c"},
            {"zombie/vehicles/BaseVehicle", "getVehicleAlarmObject", "()Lzombie/vehicles/VehicleAlarm;",
                    "48e3bbde383456e9c4ba5221028a0ad19e5da05c81b1adbd1937390629664e46"},
            {"zombie/vehicles/BaseVehicle", "needPartsUpdate", "()Z", "95b86ed772669b71295bf05a9eba0fd61bcdaedf8025702b4d73e5d59d8e41ee"},
            {"zombie/vehicles/BaseVehicle", "isMechanicUIOpen", "()Z", "f46ffac9ad5498971b0afed79a74aff5c4a40c6c6c2def74366280e6c717ed6e"},
            {"zombie/vehicles/BaseVehicle", "isRemovedFromWorld", "()Z", "c9d27babecec56077ee8d278281d3424b4cb02a408819aa4e8824a5e7ba398d4"},
            {"zombie/vehicles/LightbarLightsMode", "isEnable", "()Z", "1df2c8c528f8d9f9756c207bfac08263f18e16b1bd969d8aecad760f3a8c09e1"},
            {"zombie/vehicles/LightbarSirenMode", "isEnable", "()Z", "34c10999b8dfe5d4224dae3f7b24b3b2c09f21a4a9cc61ae2211b131bb02b644"},
            {"zombie/vehicles/BaseVehicle", "tryReconnectToTowedVehicle", "()V", "84f70866007cfd781904ade985f26e168fe95a822bfdc57d62f31e4d3937d1db"},
            // 42.22.2：等著重掛拖車的放棄計時（閒置條件讀的 getter，與累加牆鐘秒數的那個方法）
            {"zombie/vehicles/BaseVehicle", "isWaitingToReattachTowingPartner", "()Z", "f6a95a383c96dee5fd0e0d8ded3d59468597991c73f8e4779e0bae408be1fa95"},
            {"zombie/vehicles/BaseVehicle", "shouldAbandonReattachingTowingPartner", "()Z",
                    "842ab024698456e4a142585f6b7648b16c0e6c15a9c788698f1e2a22eb194960"},
    };

    /**
     * W90 的原版前提：這一輪的呼叫圖（Manager 三個方法與建構子的 HashSet tempVehicles）、isRelevant 與它讀的六個聲音條件
     * （伺服器上 isListenerInRange 恆 true）、update 期間只讀車的 VehicleState.update／sendNew 與 Connection 兩個方法。
     */
    static final String[][] VEHICLE_SOUND_LOCKED = {
            {"zombie/vehicleNetworkSound/server/Manager", "update", "()V", "d7f27ec73adc58190594eca7814ff3b2db997707fd970fa3457b4fad98ea6712"},
            {"zombie/vehicleNetworkSound/server/Manager", "updateConnection", "(Lzombie/core/raknet/UdpConnection;)V",
                    "c16e6dc0228453a19f6b189679c4c6981f38286120354df13aaa0f969c393da4"},
            {"zombie/vehicleNetworkSound/server/Manager", "getVehiclesRelevantToConnection",
                    "(Lzombie/vehicleNetworkSound/server/Connection;Ljava/util/Set;)V",
                    "e2145883e03c999cea39d67bff5d5dc6bb419eeeaa0622a9193c6fb1dcf73ff8"},
            {"zombie/vehicleNetworkSound/server/Manager", "<init>", "()V", "fb4e08dd216253c417c8dc9fb4f903904f7b1bbcd985119d2fd2ba744e93996a"},
            {"zombie/vehicleNetworkSound/server/Connection", "isRelevant", "(Lzombie/vehicles/BaseVehicle;)Z",
                    "bc7c9e74db623f1c2a68295f2ee61fd08427e45374ca0661269903ea77e04738"},
            {"zombie/vehicleNetworkSound/server/Connection", "updateVehicle", "(Lzombie/vehicles/BaseVehicle;)V",
                    "bc778737d0e201fd98e2be73aab2636e202e3f53d928064b4f0bee63ebab97c6"},
            {"zombie/vehicleNetworkSound/server/Connection", "forgetIrrelevantVehicles", "(Lgnu/trove/set/hash/TShortHashSet;)V",
                    "173800f1d57d9b5851ff34620532e8ea5c51c808df48f93f507a4d36cf78e482"},
            {"zombie/vehicleNetworkSound/server/Connection", "getUdpConnection", "()Lzombie/core/raknet/UdpConnection;",
                    "ccd093e95d0136e6e55e699fb42276fa29a2e9724d5a50ba17d2076816b38e94"},
            {"zombie/vehicleNetworkSound/server/VehicleState", "update", "(Lzombie/vehicles/BaseVehicle;Lzombie/core/raknet/UdpConnection;)V",
                    "68027e00b133010a8f303ed6a5dd3dc78afe637f374f425c0eb66b74deebb4ae"},
            {"zombie/vehicleNetworkSound/server/VehicleState", "sendNew", "(Lzombie/core/raknet/UdpConnection;)V",
                    "e6f1ec186ba255ce86e0acdedaca6b658169b5c61febd7c2c8bb05dcee245de3"},
            {"zombie/vehicles/BaseVehicle", "isAlarmActive", "()Z", "e900e6d12c8278cdc8c5098c0a65626e81f83d79028765d9a20d1e38315eb68c"},
            {"zombie/vehicles/BaseVehicle", "isBackupBeeperSounding", "()Z", "34db9c611ae2dfa721d00b14a90014c2508e11c61251680a620f49ae3e78c2a4"},
            {"zombie/vehicles/BaseVehicle", "isDoorAlarmSounding", "()Z", "09c4d3ff5cb1e48ff247a83cfeebcba320321b3ce9f01ea37bee1e69561b049d"},
            {"zombie/vehicles/BaseVehicle", "getEngineState", "()Lzombie/vehicles/BaseVehicle$engineStateTypes;",
                    "1cf6170850acbcc3d3e9dbc8bed5bc4a41dd0b89f53389fc70f40f8759b2e5e4"},
            {"zombie/vehicles/BaseVehicle", "isHornSounding", "()Z", "046cdca7c3288c5fd80fd07ff1a87dde9c73f17063809487f4e2d67b90ccf88d"},
            {"zombie/vehicles/BaseVehicle", "isSirenSounding", "()Z", "62b29ef6560b475b04b943d743f948458b31939b44a95a196b877f0507a73858"},
            {"zombie/vehicles/BaseVehicle", "isListenerInRange", "(F)Z", "0e665c945e7f673387e598a56d419a7713e65208a307f136097960607e5f42eb"},
            {"zombie/vehicleSound/VehicleSoundOwner", "hasAlarm", "()Z", "9c90656c058721eaea04c7a7a4b3475a7b28ff514162337fa71234e4f2032918"},
            {"zombie/vehicleSound/VehicleSoundOwner", "hasSiren", "()Z", "33c1ec0eb1cf77f4f7bd98d7a7462e21fd4648920473a43ca689f9cab5621ed3"},
            {"zombie/vehicles/BaseVehicle", "isSirenActive", "()Z", "c8115fef0f8f3c1418b6e85f9eb45bce8dbf35ebb38b71071d031c336f6ca2c9"},
            {"zombie/iso/IsoCell", "getVehicles", "()Ljava/util/Set;", "d6336fcf5a324308e46c5271b179cdb91d1cf5d5d42c88040901dec9db072961"},
    };

    /** W91 的原版前提：checkIsNearVehicle 只在 sneaking 時寫 nearWallCrouching，isSneaking／isNearWallCrouching 是純讀取，DistTo 純計算。 */
    static final String[][] NEAR_VEHICLE_LOCKED = {
            {"zombie/characters/IsoGameCharacter", "checkIsNearVehicle", "()Z", "5c0bcde0612b709c59c296661383d3dd8f2598b67114af59357f6eadba771562"},
            {"zombie/characters/IsoGameCharacter", "isSneaking", "()Z", "a3be7234328eafcbc8b0aa212294ded6bdce373a9ee7111f3e4874191c48af45"},
            {"zombie/characters/IsoGameCharacter", "isNearWallCrouching", "()Z", "280ad5eb6afb0840791692f57315e22038553f4c48fff48b61fc591f20aabcdc"},
            {"zombie/iso/IsoMovingObject", "DistTo", "(Lzombie/iso/IsoMovingObject;)F", "87ab4d70da6a28fdd1c19e707f703a18db4e557aa8212a69630c09289b005179"},
            {"zombie/iso/IsoCell", "getVehicles", "()Ljava/util/Set;", "d6336fcf5a324308e46c5271b179cdb91d1cf5d5d42c88040901dec9db072961"},
    };

    /** W89（2cx）／W90（2cy）／W91（2cz）：原版前提、同形手術、helper 契約。任一前提變紅＝先重新分析等價或撤刀，不改斷言讓它變綠。 */
    static int checkVehicleUpdateCuts(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String sched = "zombie/MovingObjectUpdateScheduler";
        String bucket = "zombie/MovingObjectUpdateSchedulerUpdateBucket";
        String mov = "zombie/iso/IsoMovingObject";
        String veh = "zombie/vehicles/BaseVehicle";
        String addDesc = "(L" + mov + ";)V";
        String throttle = "zombie/mdc/IdleVehicleThrottle";
        String throttleDesc = "(L" + bucket + ";L" + mov + ";)V";
        JarIndex index = JarIndex.load(jar);

        // ---- W89 ----
        MethodNode vStart = methodFromJar(jar, sched, "startFrame", "()V");
        AbstractInsnNode add = firstCall(vStart, Opcodes.INVOKEVIRTUAL, bucket, "add", addDesc);
        AbstractInsnNode aa = add == null ? null : prevReal(prevReal(add));
        failed += check("W89 vanilla 前提：bucket.add 全 jar 只有 startFrame 呼叫 1 次（getUpdateOrderIndex→aaload→aload_3→add→goto）",
                jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, bucket, "add", addDesc).equals(List.of(sched + ".startFrame()V"))
                        && add != null && isVar(prevReal(add), Opcodes.ALOAD, 3) && aa.getOpcode() == Opcodes.AALOAD
                        && isCall(prevReal(aa), Opcodes.INVOKEVIRTUAL, "zombie/UpdateSchedulerSimulationLevel", "getUpdateOrderIndex", "()I")
                        && nextReal(add).getOpcode() == Opcodes.GOTO);
        // frameStep 對車只更新 FrameKeeperComponent.frameNo（只有 frameStep 自己讀）並巡訪 ECSFrameStep 元件；全 jar 只有 CharacterInputComponent
        // 實作 ECSFrameStep，BaseVehicle 是 final、它與 IsoMovingObject／IsoObject 都不覆寫 frameStep／registerECSComponents。
        String frameStepIf = "zombie/characters/ecs/componentmods/ECSFrameStep";
        Set<String> frameSteppers = frameStepImplementers(index.classes(), frameStepIf);
        boolean ecsOk = frameSteppers.equals(Set.of("zombie/characters/component/CharacterInputComponent"))
                && (index.classes().get(veh).access & Opcodes.ACC_FINAL) != 0;
        for (String c : List.of(veh, mov, "zombie/iso/IsoObject")) {
            ecsOk &= declaredMethod(index.classes().get(c), "frameStep", "()V") == null
                    && declaredMethod(index.classes().get(c), "registerECSComponents", "()V") == null;
        }
        failed += check("W89 vanilla 前提：ECSFrameStep 只有 CharacterInputComponent 實作（實際 " + frameSteppers + "）；BaseVehicle 是 final，"
                        + "它與 IsoMovingObject／IsoObject 不覆寫 frameStep／registerECSComponents（跳過 frameStep 只讓 frameNo 落後）",
                ecsOk);
        Map<String, ClassNode> withFake = new HashMap<>(index.classes());
        ClassNode fakeStep = new ClassNode();
        fakeStep.name = "zombie/mdc/W89FakeFrameStep";
        fakeStep.superName = "java/lang/Object";
        fakeStep.interfaces.add(frameStepIf);
        withFake.put(fakeStep.name, fakeStep);
        failed += check("W89 ECSFrameStep 普查負對照：多一個實作 ECSFrameStep 的類別 → 普查變紅",
                !frameStepImplementers(withFake, frameStepIf).equals(frameSteppers));
        MethodNode pStart = method(distJava, sched, "startFrame", "()V");
        String startExpected = methodText(vStart).replace("INVOKEVIRTUAL " + bucket + ".add " + addDesc,
                "INVOKESTATIC " + throttle + ".add " + throttleDesc);
        failed += check("W89 手術後：startFrame 唯一的 bucket.add 同形改道 IdleVehicleThrottle.add，其餘指令與 frames 不變；"
                        + "MovingObjectUpdateScheduler 其餘方法不變",
                !startExpected.equals(methodText(vStart)) && startExpected.equals(methodText(pStart))
                        && otherMethodsSame(jar, distJava, sched, "startFrame", "()V"));
        MethodNode hAdd = method(distJava, throttle, "add", throttleDesc);
        failed += check("W89 helper 契約：add 呼叫原版 bucket.add 2 處（off／一般路徑與判斷後），都不在 try 內（例外照原版上拋）",
                countExactCalls(hAdd, Opcodes.INVOKEVIRTUAL, bucket, "add", addDesc) == 2
                        && callsInTry(hAdd, Opcodes.INVOKEVIRTUAL, bucket, "add", addDesc) == 0);
        failed += shaLocks(jar, "W89", IDLE_VEHICLE_LOCKED);
        // 暖機在 startFrame（newFrame）讀一次物理秒，接觸時鐘在同一幀的 postupdate 讀，兩邊必須是同一個值。fpsMultiplier 的寫入者
        // （javap 逐一看過）：GameServer.main 在主迴圈每幀寫一次（世界更新之外）、GameTime.<init>、FPSTracking.frameStep（只有
        // 客戶端 GameWindow 呼叫）；AnimatedModel 三個方法與 IsoDeadBody.reanimate 是「存→設→呼叫動畫更新→finally 還原」，
        // 呼叫結束就還原。寫入者或次數變了就紅，要重新分析。
        Map<String, Integer> fpsWriters = jarWideFieldRefs(jar, Opcodes.PUTFIELD, "zombie/GameTime", "fpsMultiplier");
        List<String> fpsTrackCallers = jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, "zombie/FPSTracking", "frameStep", "()J");
        failed += check("W89 vanilla 前提：GameTime.fpsMultiplier 的寫入者與 42.21 相同（實際 " + fpsWriters + "；FPSTracking.frameStep 的呼叫者 "
                        + fpsTrackCallers + " 都在 GameWindow）；暖機讀的物理秒與同一幀 postupdate 接觸時鐘加的相同",
                fpsWriters.equals(Map.of("zombie/FPSTracking.frameStep", 2, "zombie/GameTime.<init>", 1,
                        "zombie/core/skinnedmodel/advancedanimation/AnimatedModel.renderMain", 3,
                        "zombie/core/skinnedmodel/advancedanimation/AnimatedModel.setModelData", 3,
                        "zombie/core/skinnedmodel/advancedanimation/AnimatedModel.updateInternal", 5,
                        "zombie/iso/objects/IsoDeadBody.reanimate", 3, "zombie/network/GameServer.main", 1))
                        && !fpsTrackCallers.isEmpty() && fpsTrackCallers.stream().allMatch(c -> c.startsWith("zombie/GameWindow.")));

        // ---- W90 ----
        String pkg = "zombie/vehicleNetworkSound/server/";
        String mgr = pkg + "Manager";
        String conn = pkg + "Connection";
        String pre = pkg + "MdcVehicleSoundPrefilter";
        String udp = "zombie/core/raknet/UdpConnection";
        String engine = "zombie/core/raknet/UdpEngine";
        String relDesc = "(L" + conn + ";Ljava/util/Set;)V";
        String updConnDesc = "(L" + udp + ";)V";
        String getUdpDesc = "()L" + udp + ";";
        MethodNode vUpd = methodFromJar(jar, mgr, "update", "()V");
        MethodNode vConn = methodFromJar(jar, mgr, "updateConnection", updConnDesc);
        MethodNode vRel = methodFromJar(jar, mgr, "getVehiclesRelevantToConnection", relDesc);
        AbstractInsnNode connsGet = null;
        int connsGets = 0;
        for (AbstractInsnNode in : vUpd.instructions) {
            if (isField(in, Opcodes.GETFIELD, engine, "connections", "Ljava/util/List;")) {
                connsGet = in;
                connsGets++;
            }
        }
        AbstractInsnNode getUdp = firstCall(vConn, Opcodes.INVOKEVIRTUAL, conn, "getUdpConnection", getUdpDesc);
        AbstractInsnNode getVeh = firstCall(vRel, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "getVehicles", "()Ljava/util/Set;");
        AbstractInsnNode relevant = firstCall(vRel, Opcodes.INVOKEVIRTUAL, conn, "isRelevant", "(L" + veh + ";)Z");
        failed += check("W90 vanilla 前提：Manager.update 恰 1 個 GETFIELD UdpEngine.connections（GameServer.udpEngine→connections→List.iterator）；"
                        + "updateConnection 恰 1 個 getUdpConnection（aload_3→呼叫→aload_1）；getVehiclesRelevantToConnection 恰 1 個 IsoCell.getVehicles"
                        + "（currentCell→呼叫→Set.iterator）、恰 1 個 isRelevant、迴圈先比 vehicleId == -1；呼叫鏈只有 update→updateConnection→它",
                connsGets == 1 && isField(prevReal(connsGet), Opcodes.GETSTATIC, "zombie/network/GameServer", "udpEngine", "L" + engine + ";")
                        && isCall(nextReal(connsGet), Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;")
                        && countExactCalls(vConn, Opcodes.INVOKEVIRTUAL, conn, "getUdpConnection", getUdpDesc) == 1
                        && isVar(prevReal(getUdp), Opcodes.ALOAD, 3) && isVar(nextReal(getUdp), Opcodes.ALOAD, 1)
                        && countExactCalls(vRel, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "getVehicles", "()Ljava/util/Set;") == 1
                        && isField(prevReal(getVeh), Opcodes.GETFIELD, "zombie/iso/IsoWorld", "currentCell", "Lzombie/iso/IsoCell;")
                        && isCall(nextReal(getVeh), Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;")
                        && countExactCalls(vRel, Opcodes.INVOKEVIRTUAL, conn, "isRelevant", "(L" + veh + ";)Z") == 1
                        && relevant != null
                        && isField(prevReal(prevReal(prevReal(prevReal(prevReal(prevReal(relevant)))))), Opcodes.GETFIELD, veh, "vehicleId", "S")
                        && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, mgr, "getVehiclesRelevantToConnection", relDesc)
                                .equals(List.of(mgr + ".updateConnection" + updConnDesc))
                        && jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, mgr, "updateConnection", updConnDesc).equals(List.of(mgr + ".update()V")));
        String updExpected = methodText(vUpd).replace("GETFIELD " + engine + ".connections : Ljava/util/List;",
                "INVOKESTATIC " + pre + ".connections (L" + engine + ";)Ljava/util/List;");
        String connExpected = methodText(vConn).replace("INVOKEVIRTUAL " + conn + ".getUdpConnection " + getUdpDesc,
                "INVOKESTATIC " + pre + ".getUdpConnection (L" + conn + ";)L" + udp + ";");
        String relExpected = methodText(vRel).replace("INVOKEVIRTUAL zombie/iso/IsoCell.getVehicles ()Ljava/util/Set;",
                "INVOKESTATIC " + pre + ".getVehicles (Lzombie/iso/IsoCell;)Ljava/util/Set;");
        failed += check("W90 手術後：三處同形改道 MdcVehicleSoundPrefilter（connections／getUdpConnection／getVehicles），其餘指令與 frames 不變；"
                        + "Manager 其餘方法不變",
                !updExpected.equals(methodText(vUpd)) && updExpected.equals(methodText(method(distJava, mgr, "update", "()V")))
                        && !connExpected.equals(methodText(vConn))
                        && connExpected.equals(methodText(method(distJava, mgr, "updateConnection", updConnDesc)))
                        && !relExpected.equals(methodText(vRel))
                        && relExpected.equals(methodText(method(distJava, mgr, "getVehiclesRelevantToConnection", relDesc)))
                        && otherMethodsSame(jar, distJava, mgr, "update", "()V", "updateConnection", updConnDesc,
                                "getVehiclesRelevantToConnection", relDesc));
        MethodNode hConns = method(distJava, pre, "connections", "(L" + engine + ";)Ljava/util/List;");
        MethodNode hVeh = method(distJava, pre, "getVehicles", "(Lzombie/iso/IsoCell;)Ljava/util/Set;");
        MethodNode hUdp = method(distJava, pre, "getUdpConnection", "(L" + conn + ";)L" + udp + ";");
        int hConnsGets = 0;
        for (AbstractInsnNode in : hConns.instructions) {
            hConnsGets += isField(in, Opcodes.GETFIELD, engine, "connections", "Ljava/util/List;") ? 1 : 0;
        }
        failed += check("W90 helper 契約：connections 讀原版欄位 1 次並回傳它；getVehicles 第一個呼叫就是原版 IsoCell.getVehicles（恰 1 次）；"
                        + "getUdpConnection 恰呼叫原版 1 次",
                hConnsGets == 1 && callNames(hVeh).get(0).equals("zombie/iso/IsoCell.getVehicles")
                        && countExactCalls(hVeh, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoCell", "getVehicles", "()Ljava/util/Set;") == 1
                        && countExactCalls(hUdp, Opcodes.INVOKEVIRTUAL, conn, "getUdpConnection", getUdpDesc) == 1);
        failed += shaLocks(jar, "W90", VEHICLE_SOUND_LOCKED);

        // ---- W91 ----
        String player = "zombie/characters/IsoPlayer";
        String gate = "zombie/mdc/NearVehicleSneakGate";
        MethodNode vInt2 = methodFromJar(jar, player, "updateInternal2", "()Z");
        failed += check("W91 vanilla 前提：updateInternal2 恰 2 個 checkIsNearVehicle()，前後語境都是 checkIsNearWall→pop→aload_0→呼叫→pop→aload_0→updateExt"
                        + "（回傳值沒人用）",
                nearVehicleCtx(vInt2));
        MethodNode int2Mutated = methodFromJar(jar, player, "updateInternal2", "()Z");
        AbstractInsnNode firstNear = firstCall(int2Mutated, Opcodes.INVOKEVIRTUAL, player, "checkIsNearVehicle", "()Z");
        int2Mutated.instructions.set(nextReal(firstNear), new InsnNode(Opcodes.NOP));
        failed += check("W91 語境負對照：第一個 checkIsNearVehicle 後面的 pop 換成 NOP（回傳值被用到）→ 語境鎖變紅",
                !nearVehicleCtx(int2Mutated));
        List<String> nearWatched = List.of("isSneaking()Z", "checkIsNearVehicle()Z", "isNearWallCrouching()Z");
        Map<String, Set<String>> nearDecl = declarersUnder(index.classes(), player, nearWatched);
        failed += check("W91 vanilla 前提：IsoPlayer 與全 jar 子類（含 IsoAnimal）不覆寫 isSneaking／checkIsNearVehicle／isNearWallCrouching"
                        + "（實際 " + nearDecl + "）；BaseVehicle 不覆寫 DistTo(IsoMovingObject)",
                nearDecl.isEmpty() && declaredMethod(index.classes().get(veh), "DistTo", "(L" + mov + ";)F") == null);
        Map<String, ClassNode> withFakePlayer = new HashMap<>(index.classes());
        ClassNode fakePlayer = new ClassNode();
        fakePlayer.name = "zombie/mdc/W91FakeAnimal";
        fakePlayer.superName = "zombie/characters/animals/IsoAnimal";
        fakePlayer.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "isSneaking", "()Z", null, null));
        withFakePlayer.put(fakePlayer.name, fakePlayer);
        failed += check("W91 覆寫普查負對照：多一個 IsoAnimal 子類覆寫 isSneaking()Z → 普查變紅",
                !declarersUnder(withFakePlayer, player, nearWatched).isEmpty());
        String int2Expected = methodText(vInt2).replace("INVOKEVIRTUAL " + player + ".checkIsNearVehicle ()Z",
                "INVOKESTATIC " + gate + ".checkIsNearVehicle (L" + player + ";)Z");
        failed += check("W91 手術後：updateInternal2 兩處 checkIsNearVehicle 同形改道 NearVehicleSneakGate，其餘指令與 frames 不變",
                !int2Expected.equals(methodText(vInt2)) && int2Expected.equals(methodText(method(distJava, player, "updateInternal2", "()Z"))));
        MethodNode hNear = method(distJava, gate, "checkIsNearVehicle", "(L" + player + ";)Z");
        failed += check("W91 helper 契約：原版 checkIsNearVehicle 4 處（off、停用、潛行、抽查），都不在 try 內；跳過前先呼叫 isSneaking",
                countExactCalls(hNear, Opcodes.INVOKEVIRTUAL, player, "checkIsNearVehicle", "()Z") == 4
                        && callsInTry(hNear, Opcodes.INVOKEVIRTUAL, player, "checkIsNearVehicle", "()Z") == 0
                        && countExactCalls(hNear, Opcodes.INVOKEVIRTUAL, player, "isSneaking", "()Z") == 1);
        failed += shaLocks(jar, "W91", NEAR_VEHICLE_LOCKED);
        return failed;
    }

    /**
     * W92 的原版前提：迴圈本體與它呼叫的動物方法、伺服器上 start 到不了（isListenerInRange）、eventInstance 只由 start／stop 寫、
     * 動物 emitter 在伺服器是 Dummy（建構子的選擇與 playVocals 回 0）。
     */
    static final String[][] LOOP_SOUND_LOCKED = {
            {"zombie/iso/IsoCell", "ProcessObjects", "(Ljava/util/Iterator;)V", "482151bcdc4bf7d5855220eb77360ab2e252fa7d09eeed0639fb93285adc1763"},
            {"zombie/characters/animals/IsoAnimal", "isOnHook", "()Z", "40afe5947fa50a1e539164ccc0d19239e1b4c0b259019018a2718cd915545a5c"},
            {"zombie/characters/animals/IsoAnimal", "updateVocalProperties", "()V", "513f9b2b9aec11fcf16426160dc8a5b600fc50d0ec48cd76402d2394911c0836"},
            {"zombie/characters/animals/IsoAnimal", "updateLoopingSounds", "()V", "a01a611a329684939829ebff66b1b55b599175c177a7eebd5a68a46f157128d8"},
            {"zombie/characters/animals/IsoAnimal", "updateRunLoopingSound", "()V", "eb5292f5372e8c4d3ab8b4492b8d407d0520c5bebd944f8716ceb9745e7bfe64"},
            {"zombie/characters/animals/IsoAnimal", "updateWalkLoopingSound", "()V", "b3818a2c1e8eae6e0839bf04b3dd46b9fe883d3c9ac7802f0a86d5e2b64229ee"},
            {"zombie/characters/animals/IsoAnimal", "getAnimalSoundState", "(Ljava/lang/String;)Lzombie/characters/animals/AnimalSoundState;", "ab3bb18a876a70b0399698abc8c256c6b0c423804ac3402c318133255ff54e2a"},
            {"zombie/characters/animals/AnimalSoundState", "isPlaying", "()Z", "caeec2a6255f04240aeadc3c2c94ac9a8fbec79ea0d725bd40f15eda19de6732"},
            {"zombie/characters/animals/AnimalSoundState", "start", "(Ljava/lang/String;I)J", "c8d7ca0925f884b53debe8a3638a9199666bdf7c43726b48db713935c36cd47e"},
            {"zombie/characters/animals/AnimalSoundState", "stop", "()V", "eb9c01e227eacf410e71e0359edd7fd06275b6198043c0296cc89b8420a9fff6"},
            {"zombie/SoundManager", "isListenerInRange", "(FFF)Z", "07f932a50e7f593d3c38da44776b3b88fe5c32677dc9e098d24e5faaf287ad29"},
            {"zombie/characters/DummyCharacterSoundEmitter", "playVocals", "(Ljava/lang/String;)J", "20773656392e552ad141f354903e4a432daae528422f28c91d5ba8608de4e43e"},
            {"zombie/characters/IsoGameCharacter", "<init>", "(Lzombie/iso/IsoCell;FFF)V", "47c757b2c483806c179628f6221d985ea7250d33d3d3b212adb3401cada2ae3d"},
            {"zombie/characters/IsoGameCharacter", "getEmitter", "()Lzombie/characters/BaseCharacterSoundEmitter;", "ca3aa85b43398ac29388493af6ccff4ea4a7cca855c05501c8706f686ae5f7b8"},
    };

    /** W93 的原版前提：sendUpdateToClient 本體（getPacket 在動物迴圈前、迴圈內沒有改連線狀態的呼叫）與 helper 讀的連線／玩家 getter。 */
    static final String[][] RELEVANCY_HOIST_LOCKED = {
            {"zombie/popman/animal/AnimalSynchronizationManager", "sendUpdateToClient",
                    "(Lzombie/core/raknet/UdpConnection;ZLjava/util/HashSet;)V", "e3b49848d8bb321a399243ac0e153bcf7480c11f8082deea4c5b78056145f977"},
            {"zombie/core/raknet/UdpConnection", "RelevantTo", "(FFF)Z", "6ee3d2031937ef6897a9f4513a429039a61a4511403a9cdaffcdca9e9de08273"},
            {"zombie/core/raknet/UdpConnection", "getChunkGridWidth", "()I", "8cfa349a2b067a71ae784c43b887f994081878b67fc483e97fa94bbda180bca3"},
            {"zombie/core/raknet/UdpConnection", "getRelevantRange", "()B", "79615f201e2b67752f474e56e3bf4d6aefba18bd29dd2fa0d2316ac93ed36bd6"},
            {"zombie/core/raknet/UdpConnection", "getPlayerAt", "(I)Lzombie/characters/IsoPlayer;", "d8167c53b7dfcdddf8781bab7a748053411e68931d103ed62b849926791022ec"},
            {"zombie/characters/IsoGameCharacter", "getVehicle", "()Lzombie/vehicles/BaseVehicle;", "57811147b9f5538d8340ee925d432682bf6af61dd1e7094f874075e26c820079"},
    };

    /** W94 的原版前提：呼叫點所在方法、寫入與唯一讀者（伺服器上開頭就回傳）。 */
    static final String[][] BODY_LAST_STATE_LOCKED = {
            {"zombie/characters/IsoPlayer", "postupdateInternal", "()V", "2790cb299fb45877d7e16008d5d0c87d6d65c3798cc2eb901e3df7bde333a2be"},
            {"zombie/characters/BodyDamage/BodyDamage", "setBodyPartsLastState", "()V", "83020399854cb89d462b901e3483ad6186e50c6c47b2032ec1983e4948933278"},
            {"zombie/characters/BodyDamage/BodyDamage", "getBodyPartsLastState",
                    "(Lzombie/characters/BodyDamage/BodyPartType;)Lzombie/characters/BodyDamage/BodyPartLast;", "b9c37d1a8cafc32228660eefc68298ed0c62ca560bef3a708f0d292ae405381d"},
            {"zombie/characters/IsoGameCharacter$Bandages", "update", "(Lzombie/characters/IsoGameCharacter;)V", "3134dc3de8ca7fd6fd023290f8e53dcaaa5563deb5efbeeded7b2dbd64c39fbe"},
    };

    /** W95 探針判讀依據：idle＝沒有遷徙定義就 return；迴圈只對 rainCatcher > 0 做事；sim 用原版送出半徑。 */
    static final String[][] ANIMAL_PROBES_LOCKED = {
            {"zombie/characters/animals/AnimalZones", "updateVirtualAnimals", "()V", "8b6433916ddeba143aac12bb118334cbc4ebf658a04c2f974a7418c230272034"},
            {"zombie/characters/animals/VirtualAnimal", "update", "()V", "4d555295abfeffcbe02bee4f8ab875cce581781efae100eb10f9fa55ce5726dc"},
            {"zombie/entity/components/fluids/FluidContainerUpdateSystem", "updateSimulation", "()V", "96cd16cb8eb068aa74730a841c6612094099b870ddf27cadd713c41574181aa1"},
            {"zombie/entity/components/fluids/FluidContainerUpdateSystem", "updateEntity",
                    "(Lzombie/entity/GameEntity;Lzombie/entity/components/fluids/FluidContainer;Z)V", "dcecab3e7c1207a7c5fe50e49772b06c54030902c927b3ee363b1ca4ee8684a3"},
            {"zombie/entity/components/fluids/FluidContainer", "getRainCatcher", "()F", "f129591e1357f00533b0872bf260f9f84d12b420ca2f36a403bbcb3dbe9f25e1"},
            {"zombie/entity/components/fluids/FluidContainer", "isQualifiesForMetaStorage", "()Z", "a4a193ac443a72866b22e922b9e2800c06d1f78ee7d96c5c45b828fa8180da40"},
    };

    /** W92（2da）／W93（2db）／W94（2dc）／W95（2dd）：原版前提、同形手術、helper 契約。任一前提變紅＝先重新分析等價或撤刀，不改斷言讓它變綠。 */
    static int checkAnimalCuts(Path jar, Path distJava) throws Exception {
        int failed = 0;
        JarIndex index = JarIndex.load(jar);
        String animal = "zombie/characters/animals/IsoAnimal";

        // ---- W92 ----
        String cell = "zombie/iso/IsoCell";
        String loopGate = "zombie/mdc/AnimalLoopSoundGate";
        String itDesc = "()Ljava/util/Iterator;";
        MethodNode vProc = methodFromJar(jar, cell, "ProcessObjects", "(Ljava/util/Iterator;)V");
        AbstractInsnNode setIt = firstCall(vProc, Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", itDesc);
        failed += check("W92 vanilla 前提：ProcessObjects 恰 1 個 Set.iterator（aload_0→getfield objectList→呼叫→astore_2），迴圈只呼叫 "
                        + "isOnHook／updateVocalProperties／updateLoopingSounds",
                countExactCalls(vProc, Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", itDesc) == 1 && setIt != null
                        && isField(prevReal(setIt), Opcodes.GETFIELD, cell, "objectList", "Ljava/util/Set;")
                        && isVar(prevReal(prevReal(setIt)), Opcodes.ALOAD, 0) && isVar(nextReal(setIt), Opcodes.ASTORE, 2)
                        && countExactCalls(vProc, Opcodes.INVOKEVIRTUAL, animal, "isOnHook", "()Z") == 1
                        && countExactCalls(vProc, Opcodes.INVOKEVIRTUAL, animal, "updateVocalProperties", "()V") == 1
                        && countExactCalls(vProc, Opcodes.INVOKEVIRTUAL, animal, "updateLoopingSounds", "()V") == 1);
        List<String> loopWatched = List.of("isOnHook()Z", "updateVocalProperties()V", "updateLoopingSounds()V", "updateRunLoopingSound()V",
                "updateWalkLoopingSound()V", "getAnimalSoundState(Ljava/lang/String;)Lzombie/characters/animals/AnimalSoundState;");
        Map<String, Set<String>> loopDecl = declarersUnder(index.classes(), animal, loopWatched);
        boolean onlyAnimal = loopDecl.size() == loopWatched.size() && loopDecl.values().stream().allMatch(s -> s.equals(Set.of(animal)));
        failed += check("W92 vanilla 前提：IsoAnimal 的子類別不覆寫迴圈呼叫的方法（實際 " + loopDecl + "）", onlyAnimal);
        Map<String, ClassNode> withFakeAnimal = new HashMap<>(index.classes());
        ClassNode fakeAnimal = new ClassNode();
        fakeAnimal.name = "zombie/mdc/W92FakeAnimal";
        fakeAnimal.superName = animal;
        fakeAnimal.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "updateLoopingSounds", "()V", null, null));
        withFakeAnimal.put(fakeAnimal.name, fakeAnimal);
        failed += check("W92 覆寫普查負對照：多一個 IsoAnimal 子類覆寫 updateLoopingSounds → 普查變紅",
                !declarersUnder(withFakeAnimal, animal, loopWatched).equals(loopDecl));
        String ass = "zombie/characters/animals/AnimalSoundState";
        ClassNode vAss = classNodeFromJar(jar, ass);
        int eiStart = countExactFields(declaredMethod(vAss, "start", "(Ljava/lang/String;I)J"), Opcodes.PUTFIELD, ass, "eventInstance", "J");
        int eiStop = countExactFields(declaredMethod(vAss, "stop", "()V"), Opcodes.PUTFIELD, ass, "eventInstance", "J");
        failed += check("W92 vanilla 前提：eventInstance 全 jar 恰 3 個 putfield（start 1、stop 2）",
                jarWideFieldReadCensus(jar, Opcodes.PUTFIELD, ass, "eventInstance") == 3 && eiStart == 1 && eiStop == 2);
        String igc = "zombie/characters/IsoGameCharacter";
        String emDesc = "Lzombie/characters/BaseCharacterSoundEmitter;";
        boolean emitterFinal = classNodeFromJar(jar, igc).fields.stream()
                .anyMatch(f -> f.name.equals("emitter") && (f.access & Opcodes.ACC_FINAL) != 0);
        failed += check("W92 vanilla 前提：IsoGameCharacter.emitter 是 final，全 jar 恰 2 個 putfield、都在 <init>(IsoCell,FFF)（Dummy／真 emitter 二選一）",
                emitterFinal && jarWideFieldReadCensus(jar, Opcodes.PUTFIELD, igc, "emitter") == 2
                        && countExactFields(methodFromJar(jar, igc, "<init>", "(Lzombie/iso/IsoCell;FFF)V"), Opcodes.PUTFIELD, igc, "emitter", emDesc) == 2);
        MethodNode vGsMain = methodFromJar(jar, "zombie/network/GameServer", "main", "([Ljava/lang/String;)V");
        AbstractInsnNode smPut = null;
        for (AbstractInsnNode in : vGsMain.instructions) {
            if (isField(in, Opcodes.PUTSTATIC, "zombie/SoundManager", "instance", "Lzombie/BaseSoundManager;")) {
                smPut = in;
            }
        }
        failed += check("W92 vanilla 前提：SoundManager.instance 全 jar 恰 2 個寫入（GameServer.main 與客戶端 GameWindow），GameServer.main 那個是 new SoundManager()",
                jarWideFieldReadCensus(jar, Opcodes.PUTSTATIC, "zombie/SoundManager", "instance") == 2 && smPut != null
                        && isCall(prevReal(smPut), Opcodes.INVOKESPECIAL, "zombie/SoundManager", "<init>", "()V")
                        && prevReal(prevReal(smPut)).getOpcode() == Opcodes.DUP
                        && prevReal(prevReal(prevReal(smPut))) instanceof TypeInsnNode tNew && tNew.getOpcode() == Opcodes.NEW
                        && tNew.desc.equals("zombie/SoundManager"));
        String procExpected = methodText(vProc).replace("INVOKEINTERFACE java/util/Set.iterator " + itDesc + " (itf)",
                "INVOKESTATIC " + loopGate + ".iterator (Ljava/util/Set;)Ljava/util/Iterator;");
        failed += check("W92 手術後：ProcessObjects 唯一的 Set.iterator 同形改道 AnimalLoopSoundGate.iterator，其餘指令與 frames 不變",
                !procExpected.equals(methodText(vProc))
                        && procExpected.equals(methodText(method(distJava, cell, "ProcessObjects", "(Ljava/util/Iterator;)V"))));
        MethodNode hIt = method(distJava, loopGate, "iterator", "(Ljava/util/Set;)Ljava/util/Iterator;");
        failed += check("W92 helper 契約：先判斷 GameServer.server；回空 iterator 只有 1 處；原版 set.iterator() 2 處（off／其餘，含抽樣量時間），"
                        + "都不在 try 內；回空 iterator 前必經稽核與停用判斷",
                countExactFields(hIt, Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z") == 1
                        && countExactCalls(hIt, Opcodes.INVOKESTATIC, "java/util/Collections", "emptyIterator", itDesc) == 1
                        && countExactCalls(hIt, Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", itDesc) == 2
                        && callsInTry(hIt, Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", itDesc) == 0
                        && firstCallIndex(hIt, Opcodes.INVOKESTATIC, loopGate, "audit", "(Ljava/util/Set;)V")
                                < firstCallIndex(hIt, Opcodes.INVOKESTATIC, "java/util/Collections", "emptyIterator", itDesc));
        failed += shaLocks(jar, "W92", LOOP_SOUND_LOCKED);

        // ---- W93 ----
        String asm = "zombie/popman/animal/AnimalSynchronizationManager";
        String udp = "zombie/core/raknet/UdpConnection";
        String sendDesc = "(L" + udp + ";ZLjava/util/HashSet;)V";
        String getPacketDesc = "(Lzombie/network/PacketTypes$PacketType;)Lzombie/network/packets/INetworkPacket;";
        MethodNode vSend = methodFromJar(jar, asm, "sendUpdateToClient", sendDesc);
        Set<String> udpCalls = new java.util.TreeSet<>();
        for (AbstractInsnNode in : vSend.instructions) {
            if (in instanceof MethodInsnNode mi && mi.owner.equals(udp)) {
                udpCalls.add(mi.name + mi.desc);
            }
        }
        Set<String> wantUdp = Set.of("getConnectedGUID()J", "getPacket" + getPacketDesc, "getRelevantRange()B", "RelevantTo(FFF)Z");
        failed += check("W93 vanilla 前提：sendUpdateToClient 兩個 getPacket 都在 RelevantTo 之前；對連線只呼叫 getConnectedGUID／getPacket／"
                        + "getRelevantRange／RelevantTo（實際 " + udpCalls + "），沒有改連線狀態的呼叫",
                lastCallIndex(vSend, Opcodes.INVOKEVIRTUAL, udp, "getPacket", getPacketDesc)
                        < firstCallIndex(vSend, Opcodes.INVOKEVIRTUAL, udp, "RelevantTo", "(FFF)Z")
                        && udpCalls.equals(wantUdp));
        MethodNode hGetPacket = method(distJava, "zombie/mdc/AnimalRequestGate", "getPacket", "(L" + udp + ";" + getPacketDesc.substring(1));
        String relGate = "zombie/mdc/AnimalRelevancyGate";
        failed += check("W93 helper 契約：AnimalRequestGate.getPacket 恰 1 次 beginConnection，在原版 getPacket 之前",
                countExactCalls(hGetPacket, Opcodes.INVOKESTATIC, relGate, "beginConnection", "(L" + udp + ";)V") == 1
                        && firstCallIndex(hGetPacket, Opcodes.INVOKESTATIC, relGate, "beginConnection", "(L" + udp + ";)V")
                                < firstCallIndex(hGetPacket, Opcodes.INVOKEVIRTUAL, udp, "getPacket", getPacketDesc));
        MethodNode hBegin = method(distJava, relGate, "beginConnection", "(L" + udp + ";)V");
        MethodNode hMatch = method(distJava, relGate, "hoistMatches", "(L" + udp + ";)Z");
        failed += check("W93 helper 契約：beginConnection 與 hoistMatches 都各算一次 alignedRadius／anyPlayerInVehicle（同一份算法，不另寫）",
                countExactCalls(hBegin, Opcodes.INVOKESTATIC, relGate, "alignedRadius", "(L" + udp + ";)F") == 1
                        && countExactCalls(hBegin, Opcodes.INVOKESTATIC, relGate, "anyPlayerInVehicle", "(L" + udp + ";)Z") == 1
                        && countExactCalls(hMatch, Opcodes.INVOKESTATIC, relGate, "alignedRadius", "(L" + udp + ";)F") == 1
                        && countExactCalls(hMatch, Opcodes.INVOKESTATIC, relGate, "anyPlayerInVehicle", "(L" + udp + ";)Z") == 1);
        failed += shaLocks(jar, "W93", RELEVANCY_HOIST_LOCKED);

        // ---- W94 ----
        String player = "zombie/characters/IsoPlayer";
        String bd = "zombie/characters/BodyDamage/BodyDamage";
        String bdDesc = "()L" + bd + ";";
        MethodNode vPost = methodFromJar(jar, player, "postupdateInternal", "()V");
        AbstractInsnNode setLast = firstCall(vPost, Opcodes.INVOKEVIRTUAL, bd, "setBodyPartsLastState", "()V");
        failed += check("W94 vanilla 前提：postupdateInternal 恰 1 個 setBodyPartsLastState（aload_0→getBodyDamage→呼叫→return）",
                countExactCalls(vPost, Opcodes.INVOKEVIRTUAL, bd, "setBodyPartsLastState", "()V") == 1 && setLast != null
                        && isCall(prevReal(setLast), Opcodes.INVOKEVIRTUAL, player, "getBodyDamage", bdDesc)
                        && isVar(prevReal(prevReal(setLast)), Opcodes.ALOAD, 0) && nextReal(setLast).getOpcode() == Opcodes.RETURN);
        String bandages = "zombie/characters/IsoGameCharacter$Bandages";
        String lastDesc = "(Lzombie/characters/BodyDamage/BodyPartType;)Lzombie/characters/BodyDamage/BodyPartLast;";
        List<String> readers = jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, bd, "getBodyPartsLastState", lastDesc);
        List<String> writers = jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, bd, "setBodyPartsLastState", "()V");
        Set<String> fieldUsers = new java.util.TreeSet<>();
        for (MethodNode m : classNodeFromJar(jar, bd).methods) {
            if (countExactFields(m, Opcodes.GETFIELD, bd, "bodyPartsLastState", "Ljava/util/ArrayList;") > 0
                    || countExactFields(m, Opcodes.PUTFIELD, bd, "bodyPartsLastState", "Ljava/util/ArrayList;") > 0) {
                fieldUsers.add(m.name);
            }
        }
        // 寫入者有 4 個（建構子、load、postupdateInternal、BodyDamageSync.startSendingUpdates 對另一份 bdSent）；本刀只跳過 postupdateInternal
        // 那一個，等價只靠「讀者只有伺服器上開頭就回傳的 Bandages.update」。寫入者清冊釘住，多一個新的就重新核對。
        failed += check("W94 vanilla 前提：getBodyPartsLastState 全 jar 只有 Bandages.update 呼叫（實際 " + readers + "）；setBodyPartsLastState 的呼叫者恰為 "
                        + "BodyDamage.<init>／load、IsoPlayer.postupdateInternal、BodyDamageSync.startSendingUpdates（實際 " + writers + "）；"
                        + "bodyPartsLastState 欄位只在建構子與這兩個方法（實際 " + fieldUsers + "）",
                readers.size() >= 1 && readers.stream().allMatch(r -> r.equals(bandages + ".update(Lzombie/characters/IsoGameCharacter;)V"))
                        && writers.equals(List.of(bd + ".<init>(Lzombie/characters/IsoGameCharacter;)V", bd + ".load(Ljava/nio/ByteBuffer;I)V",
                                player + ".postupdateInternal()V", "zombie/network/BodyDamageSync.startSendingUpdates(SS)V"))
                        && fieldUsers.equals(Set.of("<init>", "getBodyPartsLastState", "setBodyPartsLastState")));
        MethodNode vBand = methodFromJar(jar, bandages, "update", "(Lzombie/characters/IsoGameCharacter;)V");
        AbstractInsnNode[] bandHead = firstReal(vBand, 3);
        failed += check("W94 vanilla 前提：Bandages.update 開頭 getstatic GameServer.server→ifeq→return（伺服器上沒有讀者）",
                isField(bandHead[0], Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z")
                        && bandHead[1] != null && bandHead[1].getOpcode() == Opcodes.IFEQ
                        && bandHead[2] != null && bandHead[2].getOpcode() == Opcodes.RETURN);
        String lastGate = "zombie/mdc/BodyLastStateGate";
        String postExpected = methodText(vPost).replace("INVOKEVIRTUAL " + bd + ".setBodyPartsLastState ()V",
                "INVOKESTATIC " + lastGate + ".setBodyPartsLastState (L" + bd + ";)V");
        failed += check("W94 手術後：postupdateInternal 唯一的 setBodyPartsLastState 同形改道 BodyLastStateGate，其餘指令與 frames 不變",
                !postExpected.equals(methodText(vPost)) && postExpected.equals(methodText(method(distJava, player, "postupdateInternal", "()V"))));
        MethodNode hLast = method(distJava, lastGate, "setBodyPartsLastState", "(L" + bd + ";)V");
        failed += check("W94 helper 契約：先判斷 GameServer.server；原版 setBodyPartsLastState 3 處（off／客戶端、observe、抽樣量時間），都不在 try 內",
                countExactFields(hLast, Opcodes.GETSTATIC, "zombie/network/GameServer", "server", "Z") == 1
                        && countExactCalls(hLast, Opcodes.INVOKEVIRTUAL, bd, "setBodyPartsLastState", "()V") == 3
                        && callsInTry(hLast, Opcodes.INVOKEVIRTUAL, bd, "setBodyPartsLastState", "()V") == 0);
        failed += shaLocks(jar, "W94", BODY_LAST_STATE_LOCKED);

        // ---- W95 ----
        String world = "zombie/iso/IsoWorld";
        String zones = "zombie/characters/animals/AnimalZones";
        String probes = "zombie/characters/animals/MdcAnimalProbes";
        MethodNode vThread = methodFromJar(jar, world, "updateThread", "()V");
        AbstractInsnNode va = firstCall(vThread, Opcodes.INVOKESTATIC, zones, "updateVirtualAnimals", "()V");
        failed += check("W95 vanilla 前提：updateThread 恰 1 個 updateVirtualAnimals（\"Update VA\" profile→astore_2→呼叫→aload_2）；全 jar 只有這裡呼叫",
                countExactCalls(vThread, Opcodes.INVOKESTATIC, zones, "updateVirtualAnimals", "()V") == 1 && va != null
                        && isVar(prevReal(va), Opcodes.ASTORE, 2) && isVar(nextReal(va), Opcodes.ALOAD, 2)
                        && prevReal(prevReal(prevReal(va))) instanceof LdcInsnNode ldc && "Update VA".equals(ldc.cst)
                        && jarWideCallers(jar, Opcodes.INVOKESTATIC, zones, "updateVirtualAnimals", "()V").equals(List.of(world + ".updateThread()V")));
        String threadExpected = methodText(vThread).replace("INVOKESTATIC " + zones + ".updateVirtualAnimals ()V",
                "INVOKESTATIC " + probes + ".updateVirtualAnimals ()V");
        failed += check("W95 手術後：updateThread 唯一的 updateVirtualAnimals 同形改道 MdcAnimalProbes，其餘指令與 frames 不變",
                !threadExpected.equals(methodText(vThread))
                        && threadExpected.equals(methodText(method(distJava, world, "updateThread", "()V"))));
        String fus = "zombie/entity/components/fluids/FluidContainerUpdateSystem";
        String bucket = "zombie/entity/EntityBucket";
        String entDesc = "()Lzombie/entity/util/ImmutableArray;";
        MethodNode vSim = methodFromJar(jar, fus, "updateSimulation", "()V");
        AbstractInsnNode ge = firstCall(vSim, Opcodes.INVOKEVIRTUAL, bucket, "getEntities", entDesc);
        failed += check("W95 vanilla 前提：updateSimulation 恰 1 個 getEntities（aload_0→getfield fluidContainerEntities→呼叫→astore_2）",
                countExactCalls(vSim, Opcodes.INVOKEVIRTUAL, bucket, "getEntities", entDesc) == 1 && ge != null
                        && isField(prevReal(ge), Opcodes.GETFIELD, fus, "fluidContainerEntities", "L" + bucket + ";")
                        && isVar(nextReal(ge), Opcodes.ASTORE, 2));
        String simExpected = methodText(vSim).replace("INVOKEVIRTUAL " + bucket + ".getEntities " + entDesc,
                "INVOKESTATIC " + probes + ".fluidEntities (L" + bucket + ";)Lzombie/entity/util/ImmutableArray;");
        failed += check("W95 手術後：updateSimulation 唯一的 getEntities 同形改道 MdcAnimalProbes.fluidEntities，其餘指令與 frames 不變；"
                        + "FluidContainerUpdateSystem 其餘方法不變",
                !simExpected.equals(methodText(vSim)) && simExpected.equals(methodText(method(distJava, fus, "updateSimulation", "()V")))
                        && otherMethodsSame(jar, distJava, fus, "updateSimulation", "()V"));
        MethodNode hVa = method(distJava, probes, "updateVirtualAnimals", "()V");
        MethodNode hFluid = method(distJava, probes, "fluidEntities", "(L" + bucket + ";)Lzombie/entity/util/ImmutableArray;");
        boolean simReadOnly = true;
        for (String[] m : new String[][]{{"sim", "(L" + animal + ";)V"}, {"unseen", "(L" + animal + ";)Z"}, {"still", "(L" + animal + ";)Z"}}) {
            MethodNode h = method(distJava, probes, m[0], m[1]);
            simReadOnly &= countOpcode(h, Opcodes.PUTFIELD) == 0 && callNames(h).stream().noneMatch(n -> n.contains(".set"));
        }
        MethodNode hAdd = method(distJava, "zombie/mdc/IdleVehicleThrottle", "add",
                "(Lzombie/MovingObjectUpdateSchedulerUpdateBucket;Lzombie/iso/IsoMovingObject;)V");
        failed += check("W95 helper 契約：a0 原版 updateVirtualAnimals 2 處、F1 原版 getEntities 1 處，都不在 try 內；sim／unseen／still 不寫物件欄位、"
                        + "不呼叫 setter；W89 add 在第一個 bucket.add 之前呼叫 sim 恰 1 次",
                countExactCalls(hVa, Opcodes.INVOKESTATIC, zones, "updateVirtualAnimals", "()V") == 2
                        && callsInTry(hVa, Opcodes.INVOKESTATIC, zones, "updateVirtualAnimals", "()V") == 0
                        && countExactCalls(hFluid, Opcodes.INVOKEVIRTUAL, bucket, "getEntities", entDesc) == 1
                        && callsInTry(hFluid, Opcodes.INVOKEVIRTUAL, bucket, "getEntities", entDesc) == 0
                        && simReadOnly
                        && countExactCalls(hAdd, Opcodes.INVOKESTATIC, probes, "sim", "(L" + animal + ";)V") == 1
                        && firstCallIndex(hAdd, Opcodes.INVOKESTATIC, probes, "sim", "(L" + animal + ";)V")
                                < firstCallIndex(hAdd, Opcodes.INVOKEVIRTUAL, "zombie/MovingObjectUpdateSchedulerUpdateBucket", "add",
                                        "(Lzombie/iso/IsoMovingObject;)V"));
        failed += shaLocks(jar, "W95", ANIMAL_PROBES_LOCKED);
        // 診斷碼不得把日誌鏈的例外帶進原版流程，也不得吞掉 Error（docs/patches.md「Error 必須保持致命且可見」）：
        // 四個 helper 的每個 DebugLog.log 都在 try 內，涵蓋它的 handler 型別恰為 {RuntimeException, LinkageError}；
        // 四個 helper 全部 try-catch 也只准這兩種（比照 W6／W10 把型別釘在 exception table 上）。
        StringBuilder logViolations = new StringBuilder();
        for (String h : new String[]{loopGate, relGate, lastGate, probes}) {
            logViolations.append(logHandlerViolations(classNode(distJava, h)));
        }
        failed += check("W92–W95 helper 契約：每個 DebugLog.log 都在 try 內、handler 恰為 RuntimeException＋LinkageError，"
                        + "四個 helper 沒有 Throwable／Error／catch-all（Error 穿透）" + logViolations, logViolations.length() == 0);
        ClassNode widened = classNode(distJava, loopGate);
        ClassNode narrowed = classNode(distJava, loopGate);
        MethodNode wLog = widened.methods.stream().filter(m -> m.name.equals("log")).findFirst().orElseThrow();
        MethodNode nLog = narrowed.methods.stream().filter(m -> m.name.equals("log")).findFirst().orElseThrow();
        boolean mutable = wLog.tryCatchBlocks.size() == 2 && nLog.tryCatchBlocks.size() == 2;
        if (mutable) {
            wLog.tryCatchBlocks.get(0).type = "java/lang/Throwable";
            nLog.tryCatchBlocks.removeIf(t -> "java/lang/LinkageError".equals(t.type));
        }
        failed += check("W92–W95 日誌 handler 型別鎖負對照：AnimalLoopSoundGate.log 的 catch 放寬成 Throwable、收窄成只有 RuntimeException 都會紅",
                mutable && logHandlerViolations(widened).length() > 0 && logHandlerViolations(narrowed).length() > 0);
        return failed;
    }

    /** 直接宣告實作 iface 的類別。 */
    static Set<String> frameStepImplementers(Map<String, ClassNode> classes, String iface) {
        Set<String> out = new java.util.TreeSet<>();
        for (ClassNode cn : classes.values()) {
            if (cn.interfaces.contains(iface)) {
                out.add(cn.name);
            }
        }
        return out;
    }

    /** cls 除了 skip（name, desc 成對）以外的方法，手術後與原版逐指令相同。 */
    static boolean otherMethodsSame(Path jar, Path distJava, String cls, String... skip) throws Exception {
        for (MethodNode m : classNodeFromJar(jar, cls).methods) {
            boolean skipped = false;
            for (int i = 0; i < skip.length; i += 2) {
                skipped |= m.name.equals(skip[i]) && m.desc.equals(skip[i + 1]);
            }
            if (!skipped && !methodText(m).equals(methodText(method(distJava, cls, m.name, m.desc)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * W92–W95 日誌 handler 型別鎖：回傳違規描述（空字串＝通過）。每個 {@code DebugLog.log(String)} 至少在一個 try 內，
     * 涵蓋它的 handler 型別集合恰為 {RuntimeException, LinkageError}；整個 class 的 try-catch 只准這兩種型別。
     */
    static String logHandlerViolations(ClassNode cn) {
        Set<String> allowed = Set.of("java/lang/RuntimeException", "java/lang/LinkageError");
        StringBuilder out = new StringBuilder();
        for (MethodNode m : cn.methods) {
            for (TryCatchBlockNode t : m.tryCatchBlocks) {
                if (!allowed.contains(t.type)) {
                    out.append(' ').append(cn.name).append('.').append(m.name).append(" catch=").append(t.type);
                }
            }
            for (AbstractInsnNode in : m.instructions) {
                if (!isCall(in, Opcodes.INVOKESTATIC, "zombie/debug/DebugLog", "log", "(Ljava/lang/String;)V")) {
                    continue;
                }
                int at = m.instructions.indexOf(in);
                Set<String> types = new java.util.TreeSet<>();
                for (TryCatchBlockNode t : m.tryCatchBlocks) {
                    if (m.instructions.indexOf(t.start) <= at && at < m.instructions.indexOf(t.end)) {
                        types.add(String.valueOf(t.type));
                    }
                }
                if (!types.equals(allowed)) {
                    out.append(' ').append(cn.name).append('.').append(m.name).append(" log handlers=").append(types);
                }
            }
        }
        return out.toString();
    }

    /** 落在任一 try 區塊內的精確呼叫數。 */
    static int callsInTry(MethodNode m, int opcode, String owner, String name, String desc) {
        int n = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (isCall(in, opcode, owner, name, desc)) {
                int at = m.instructions.indexOf(in);
                for (TryCatchBlockNode t : m.tryCatchBlocks) {
                    if (m.instructions.indexOf(t.start) <= at && at < m.instructions.indexOf(t.end)) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    /** 原版本體 SHA 鎖，每個鎖配 {@link #wmIndexMutate} 負對照（W86 寫法）。 */
    static int shaLocks(Path jar, String tag, String[][] locks) throws Exception {
        int failed = 0;
        Map<String, ClassNode> cache = new HashMap<>();
        for (String[] lock : locks) {
            ClassNode cn = cache.get(lock[0]);
            if (cn == null) {
                cn = new ClassNode();
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
                    new ClassReader(jf.getInputStream(jf.getJarEntry(lock[0] + ".class")).readAllBytes()).accept(cn, ClassReader.SKIP_DEBUG);
                }
                cache.put(lock[0], cn);
            }
            String what = lock[0].substring(lock[0].lastIndexOf('/') + 1) + "." + lock[1] + lock[2];
            MethodNode body = declaredMethod(cn, lock[1], lock[2]);
            String got = body == null ? "missing" : sha256Hex(methodText(body));
            failed += check(tag + " vanilla 前提：" + what + " 本體（去 debug 的 methodText SHA-256）與 42.21 相同", got.equals(lock[3]));
            if (!got.equals(lock[3])) {
                System.out.println("  實際 SHA：" + got);
            }
            String mutation = body == null ? "none" : wmIndexMutate(body);
            failed += check(tag + " SHA 鎖負對照：" + what + " " + mutation + " → SHA 鎖變紅",
                    got.equals(lock[3]) && !mutation.equals("none") && !sha256Hex(methodText(body)).equals(lock[3]));
        }
        return failed;
    }

    /** W91：updateInternal2 恰 2 個 checkIsNearVehicle()，每個都在 checkIsNearWall→pop→aload_0→呼叫→pop→aload_0→updateExt 之間。 */
    static boolean nearVehicleCtx(MethodNode m) {
        String player = "zombie/characters/IsoPlayer";
        List<AbstractInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (isCall(in, Opcodes.INVOKEVIRTUAL, player, "checkIsNearVehicle", "()Z")) {
                calls.add(in);
            }
        }
        boolean ok = calls.size() == 2;
        for (AbstractInsnNode c : calls) {
            AbstractInsnNode before = prevReal(prevReal(c));
            ok &= isVar(prevReal(c), Opcodes.ALOAD, 0) && nextReal(c).getOpcode() == Opcodes.POP
                    && before.getOpcode() == Opcodes.POP
                    && isCall(prevReal(before), Opcodes.INVOKEVIRTUAL, player, "checkIsNearWall", "()F")
                    && isVar(nextReal(nextReal(c)), Opcodes.ALOAD, 0)
                    && isCall(nextReal(nextReal(nextReal(c))), Opcodes.INVOKEVIRTUAL, player, "updateExt", "()V");
        }
        return ok;
    }

    /** root 本身與它在 classes 裡的所有子類別中，宣告 watched（name+desc）各方法的類別。 */
    static Map<String, Set<String>> declarersUnder(Map<String, ClassNode> classes, String root, List<String> watched) {
        Map<String, Set<String>> out = new java.util.TreeMap<>();
        for (ClassNode cn : classes.values()) {
            boolean under = false;
            for (String s = cn.name; s != null && !under; ) {
                under = s.equals(root);
                ClassNode up = classes.get(s);
                s = up == null ? null : up.superName;
            }
            if (under) {
                for (MethodNode m : cn.methods) {
                    if (watched.contains(m.name + m.desc)) {
                        out.computeIfAbsent(m.name + m.desc, k -> new java.util.TreeSet<>()).add(cn.name);
                    }
                }
            }
        }
        return out;
    }

    /** W88：42.21.0 原版 IsoGameCharacter.DoDeath(HandWeapon, IsoGameCharacter, boolean) 以 SKIP_DEBUG 讀入的 methodText SHA-256。 */
    static final String DO_DEATH_SHA = "8368c451bf729bcaba4241073f862010373c5e8cb47459e864e505a24a0f79e8";

    /**
     * W88 PVP=false 時玩家死亡不歸給玩家（docs/patches.md 2cw）：存在理由（原版 DoDeath 只看 attackedBy 是不是 IsoPlayer、不讀 PVP；
     * 任一項變紅＝官方可能已修，先重估撤刀，不改斷言）、原版本體 SHA 鎖（含負對照）、掛點語境、同形手術、helper 契約。
     */
    static int checkPveDeathLog(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String gc = "zombie/characters/IsoGameCharacter";
        String player = "zombie/characters/IsoPlayer";
        String helper = "zombie/mdc/PveDeathLog";
        String deathDesc = "(Lzombie/inventory/types/HandWeapon;L" + gc + ";Z)V";
        String getDesc = "()L" + gc + ";";
        String logKillDesc = "(L" + player + ";L" + player + ";)V";
        MethodNode vDeath = methodFromJar(jar, gc, "DoDeath", deathDesc);
        List<MethodInsnNode> gets = new ArrayList<>();
        for (AbstractInsnNode in : vDeath.instructions) {
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals(gc)
                    && mi.name.equals("getAttackedBy") && mi.desc.equals(getDesc)) {
                gets.add(mi);
            }
        }
        boolean firstOk = false;
        boolean secondOk = false;
        LabelNode elseLabel = null;
        if (gets.size() == 2) {
            MethodInsnNode g1 = gets.get(0);
            MethodInsnNode g2 = gets.get(1);
            AbstractInsnNode t1 = nextReal(g1);
            AbstractInsnNode j1 = t1 == null ? null : nextReal(t1);
            firstOk = prevReal(g1) instanceof VarInsnNode l && l.getOpcode() == Opcodes.ALOAD && l.var == 0
                    && t1 instanceof TypeInsnNode io && io.getOpcode() == Opcodes.INSTANCEOF && io.desc.equals(player)
                    && j1 instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ;
            if (firstOk) {
                elseLabel = ((JumpInsnNode) j1).label;
            }
            AbstractInsnNode c1 = nextReal(g2);
            AbstractInsnNode a0 = c1 == null ? null : nextReal(c1);
            AbstractInsnNode c2 = a0 == null ? null : nextReal(a0);
            AbstractInsnNode kill = c2 == null ? null : nextReal(c2);
            secondOk = prevReal(g2) instanceof VarInsnNode l2 && l2.getOpcode() == Opcodes.ALOAD && l2.var == 0
                    && c1 instanceof TypeInsnNode cc1 && cc1.getOpcode() == Opcodes.CHECKCAST && cc1.desc.equals(player)
                    && a0 instanceof VarInsnNode l3 && l3.getOpcode() == Opcodes.ALOAD && l3.var == 0
                    && c2 instanceof TypeInsnNode cc2 && cc2.getOpcode() == Opcodes.CHECKCAST && cc2.desc.equals(player)
                    && kill instanceof MethodInsnNode lk && lk.getOpcode() == Opcodes.INVOKESTATIC
                    && lk.owner.equals("zombie/network/PVPLogTool") && lk.name.equals("logKill") && lk.desc.equals(logKillDesc)
                    && vDeath.instructions.indexOf(g2) < vDeath.instructions.indexOf(elseLabel == null ? g2 : elseLabel);
        }
        failed += check("W88 掛點：DoDeath 恰 2 個 invokevirtual getAttackedBy()；第 1 個 aload_0 → instanceof IsoPlayer → ifeq（non pvp 分支），"
                        + "第 2 個在該分支之前、aload_0 → checkcast IsoPlayer → aload_0 → checkcast IsoPlayer → PVPLogTool.logKill",
                gets.size() == 2 && firstOk && secondOk);
        boolean elseOk = false;
        if (elseLabel != null) {
            int from = vDeath.instructions.indexOf(elseLabel);
            boolean user = false;
            boolean write = false;
            boolean announce = false;
            boolean chat = false;
            for (AbstractInsnNode in = elseLabel; in != null; in = in.getNext()) {
                if (in instanceof LdcInsnNode ldc && "user".equals(ldc.cst)) {
                    user = true;
                } else if (in instanceof MethodInsnNode mi && mi.owner.equals("zombie/core/logger/ZLogger") && mi.name.equals("write")) {
                    write = user;
                } else if (in instanceof FieldInsnNode fi && fi.owner.equals("zombie/network/ServerOptions") && fi.name.equals("announceDeath")) {
                    announce = write;
                } else if (in instanceof MethodInsnNode mi && mi.owner.equals("zombie/network/chat/ChatServer")
                        && mi.name.equals("sendMessageToServerChat") && mi.desc.equals("(Ljava/lang/String;)V")) {
                    chat = announce;
                    break;
                }
            }
            elseOk = from > 0 && user && write && announce && chat;
        }
        failed += check("W88 存在理由：ifeq 跳到的 non pvp 分支依序有 ldc \"user\"→ZLogger.write、ServerOptions.announceDeath→sendMessageToServerChat；"
                        + "DoDeath 不讀 ServerOptions.pvp；全 jar 只有 DoDeath 呼叫 PVPLogTool.logKill（多了＝有別的路徑要重估）",
                elseOk && !methodText(vDeath).contains("zombie/network/ServerOptions.pvp")
                && jarWideCallers(jar, Opcodes.INVOKESTATIC, "zombie/network/PVPLogTool", "logKill", logKillDesc)
                        .equals(List.of(gc + ".DoDeath" + deathDesc)));
        // 原版本體鎖：helper 依賴「回 null 就走 non pvp 分支」的整段語意。負對照：把第一個條件跳轉反轉，SHA 必須變。
        String vText = debuglessMethodText(jar, gc, "DoDeath", deathDesc);
        ClassNode vNoDebug = new ClassNode();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            new ClassReader(jf.getInputStream(jf.getJarEntry(gc + ".class")).readAllBytes()).accept(vNoDebug, ClassReader.SKIP_DEBUG);
        }
        MethodNode mutated = vNoDebug.methods.stream().filter(m -> m.name.equals("DoDeath") && m.desc.equals(deathDesc))
                .findFirst().orElseThrow();
        boolean flipped = false;
        for (AbstractInsnNode in : mutated.instructions) {
            if (in instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFEQ) {
                j.setOpcode(Opcodes.IFNE);
                flipped = true;
                break;
            }
        }
        failed += check("W88 原版 DoDeath 本體與核對時相同（sha256 " + DO_DEATH_SHA.substring(0, Math.min(12, DO_DEATH_SHA.length()))
                        + "…，實際 " + sha256Hex(vText).substring(0, 12) + "…）；負對照：反轉第一個 ifeq 後 SHA 不同",
                sha256Hex(vText).equals(DO_DEATH_SHA) && flipped && !sha256Hex(methodText(mutated)).equals(DO_DEATH_SHA));
        MethodNode pDeath = method(distJava, gc, "DoDeath", deathDesc);
        String from = "INVOKEVIRTUAL " + gc + ".getAttackedBy " + getDesc;
        String to = "INVOKESTATIC " + helper + ".getAttackedBy (L" + gc + ";)L" + gc + ";";
        String vDeathText = methodText(vDeath);
        Set<String> patchedHere = Set.of("DoDeath" + deathDesc, "setForwardDirectionFromIsoDirection()V", "updateEmitter()V");
        ClassNode pGc = classNode(distJava, gc);
        boolean othersSame = classNodeFromJar(jar, gc).methods.stream()
                .filter(m -> !patchedHere.contains(m.name + m.desc)).allMatch(m -> pGc.methods.stream()
                        .filter(p -> p.name.equals(m.name) && p.desc.equals(m.desc)).findFirst()
                        .map(p -> methodText(p).equals(methodText(m))).orElse(false));
        failed += check("W88 手術後：DoDeath 兩個 getAttackedBy 同形改道 PveDeathLog，其餘指令與 frames 不變；IsoGameCharacter 除 W7／W34／W88 三個方法外不變",
                vDeathText.split(java.util.regex.Pattern.quote(from), -1).length == 3
                && methodText(pDeath).equals(vDeathText.replace(from, to)) && othersSame);
        MethodNode h = method(distJava, helper, "getAttackedBy", "(L" + gc + ";)L" + gc + ";");
        int nullReturns = 0;
        for (AbstractInsnNode in : h.instructions) {
            if (in.getOpcode() == Opcodes.ACONST_NULL && nextReal(in) != null && nextReal(in).getOpcode() == Opcodes.ARETURN) {
                nullReturns++;
            }
        }
        // 判斷不得包在 try 裡、catch 不得改變回傳值：否則兩個呼叫點可能一個回玩家、一個回 null（logKill 拿到 null）。
        // try 只包 log；全方法恰 2 個 areturn（return attacker、return null），catch 裡多一個 return 就紅。
        boolean onlyLogInTry = !h.tryCatchBlocks.isEmpty()
                && callsInsideTryRange(h, Opcodes.INVOKEVIRTUAL, player, "isAnimal", "()Z") == 0
                && callsInsideTryRange(h, Opcodes.INVOKEVIRTUAL, gc, "getAttackedBy", getDesc) == 0
                && callsInsideTryRange(h, Opcodes.INVOKESTATIC, "zombie/debug/DebugLog", "log", "(Ljava/lang/String;)V") == 1
                && countOpcode(h, Opcodes.ARETURN) == 2;
        failed += check("W88 helper 契約：恰一次呼叫原版 getAttackedBy（不經改道）、讀 ServerOptions.pvp 與 isAnimal、恰一處 return null；"
                        + "唯一的 try 只包 DebugLog.log、判斷在 try 外、全方法恰 2 個 areturn（log 失敗不改變結果）",
                countExactCalls(h, Opcodes.INVOKEVIRTUAL, gc, "getAttackedBy", getDesc) == 1
                && countCalls(h, helper, "getAttackedBy") == 0
                && methodText(h).contains("GETFIELD zombie/network/ServerOptions.pvp")
                && countCalls(h, player, "isAnimal") == 1
                && nullReturns == 1 && onlyLogInTry);
        return failed;
    }

    /** W96：helper 依賴的原版本體（取樣器的取樣、重設與車輛判斷；AntiCheatSpeed 先 resetMovable 再比速度；登入時間；原版踢人）。 */
    static final String[][] TELEPORT_WATCH_LOCKED = {
            {"zombie/characters/NetworkCharacterAI$SpeedChecker", "set", "(FFZLzombie/vehicles/BaseVehicle;)V",
                    "a0e3825c3b82dce5de4ba4d55d03a7898d071964a18209481e32ec979580ecae"},
            {"zombie/characters/NetworkCharacterAI$SpeedChecker", "reset", "()V", "c6b5f922b877407883587366df472669aecf94653b66000c198825d23f07241d"},
            {"zombie/characters/NetworkCharacterAI$SpeedChecker", "isVehicle", "()Z", "dc8c235a6ffc7c6ba383fe669ea329375232dd0d3d21a8d972986c3968221eb5"},
            {"zombie/characters/NetworkCharacterAI", "resetSpeedLimiter", "()V", "2d6e85cd681b0799bdfe9605ac65eaf449bfc8d3266dd7642e2b89232666db12"},
            {"zombie/network/anticheats/AntiCheatSpeed", "validate",
                    "(Lzombie/core/raknet/UdpConnection;Lzombie/network/packets/INetworkPacket;)Ljava/lang/String;",
                    "477bd98fb7c29e198834a74e3bf3674af971e372e0048a3d8bc130ad566bb25c"},
            {"zombie/network/packets/character/PlayerPacket", "resetMovable", "()V", "762956aa378fe8666434b3e33c5cf5f4c25a08cb54545410dd5aecabaec1a7ce"},
            {"zombie/core/raknet/UdpConnection", "setFullyConnected", "()V", "7c44a6e8c5deefcf3c15aebf27240662b3bd241569ead2197d38dc8c655122f2"},
            {"zombie/network/anticheats/AntiCheat", "doKickUser", "(Lzombie/core/raknet/UdpConnection;Ljava/lang/String;Ljava/lang/String;)V",
                    "f6fdffd5675beaa3f93291b40caa7f9e8136498232d5d1cd86c9841a775f367f"},
    };

    /**
     * W96 步行瞬移觀測與踢人（docs/patches.md 2de）：存在理由（取樣器只有步行與車輛兩個呼叫點、伺服器傳送只有 sendTeleport 重設玩家的取樣器、
     * 登入時間只有建構子與 setFullyConnected 寫）、原版本體 SHA 鎖、兩個掛點的同形手術、helper 契約（原版 set／resetSpeedLimiter 在 try 外、
     * 例外型別、每個 log 都在 try 內）。任一前提變紅＝先重新核對判斷，不改斷言。
     */
    static int checkTeleportWatch(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String sc = "zombie/characters/NetworkCharacterAI$SpeedChecker";
        String setDesc = "(FFZLzombie/vehicles/BaseVehicle;)V";
        String pp = "zombie/network/packets/character/PlayerPacket";
        String gs = "zombie/network/GameServer";
        String npa = "zombie/characters/NetworkPlayerAI";
        String helper = "zombie/mdc/TeleportWatch";
        String tpDesc = "(Lzombie/characters/IsoPlayer;FFF)V";
        failed += check("W96 存在理由：全 jar 呼叫 SpeedChecker.set 的只有 PlayerPacket.resetMovable 與 VehiclePhysicsPacket.resetMovable（多了＝新的取樣來源要重估）",
                jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, sc, "set", setDesc).equals(List.of(
                        pp + ".resetMovable()V", "zombie/network/packets/vehicle/VehiclePhysicsPacket.resetMovable()V")));
        failed += check("W96 存在理由：全 jar 對 NetworkPlayerAI.resetSpeedLimiter 的呼叫只有 GameServer.sendTeleport（伺服器傳送都經過它）",
                jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, npa, "resetSpeedLimiter", "()V").equals(List.of(gs + ".sendTeleport" + tpDesc)));
        List<String> stamp = jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, "zombie/core/raknet/UdpConnection", "setConnectionTimestamp", "(J)V");
        failed += check("W96 存在理由：全 jar 呼叫 UdpConnection.setConnectionTimestamp 的只有建構子與 setFullyConnected（login＝connectionTimestamp−15000）" + stamp,
                stamp.size() == 2 && stamp.contains("zombie/core/raknet/UdpConnection.setFullyConnected()V")
                        && stamp.stream().filter(s -> s.startsWith("zombie/core/raknet/UdpConnection.<init>")).count() == 1);
        for (String sub : new String[]{pp + "Reliable", pp + "Unreliable"}) {
            failed += check("W96 存在理由：" + sub.substring(sub.lastIndexOf('/') + 1) + " 沒有覆寫 resetMovable（兩種步行封包都走 PlayerPacket 的掛點）",
                    declaredMethod(classNodeFromJar(jar, sub), "resetMovable", "()V") == null);
        }
        String acs = "zombie/network/anticheats/AntiCheatSpeed";
        String validateDesc = "(Lzombie/core/raknet/UdpConnection;Lzombie/network/packets/INetworkPacket;)Ljava/lang/String;";
        failed += check("W96 存在理由：全 jar 呼叫 resetMovable 的只有 AntiCheatSpeed.validate（begin 掛在它頭部，記下的連線就是送出這包的連線）",
                jarWideCallers(jar, Opcodes.INVOKEINTERFACE, acs + "$IAntiCheat", "resetMovable", "()V").equals(List.of(acs + ".validate" + validateDesc)));
        for (String owner : new String[]{pp, pp + "Reliable", pp + "Unreliable"}) {
            failed += check("W96 存在理由：沒有人以具體型別 " + owner.substring(owner.lastIndexOf('/') + 1) + " 直接呼叫 resetMovable（那種呼叫不經過 begin）",
                    jarWideCallers(jar, Opcodes.INVOKEVIRTUAL, owner, "resetMovable", "()V").isEmpty());
        }
        failed += shaLocks(jar, "W96", TELEPORT_WATCH_LOCKED);

        MethodNode vReset = methodFromJar(jar, pp, "resetMovable", "()V");
        MethodNode pReset = method(distJava, pp, "resetMovable", "()V");
        String fromSet = "INVOKEVIRTUAL " + sc + ".set " + setDesc;
        String toSet = "INVOKESTATIC " + helper + ".set (L" + sc + ";FFZLzombie/vehicles/BaseVehicle;)V";
        failed += check("W96 手術後：resetMovable 唯一的 SpeedChecker.set 同形改道 TeleportWatch.set，其餘指令不變；PlayerPacket 其他方法不變",
                methodText(vReset).split(java.util.regex.Pattern.quote(fromSet), -1).length == 2
                        && methodText(pReset).equals(methodText(vReset).replace(fromSet, toSet))
                        && otherMethodsSame(jar, distJava, pp, "resetMovable", "()V"));
        String beginDesc = "(Lzombie/core/raknet/UdpConnection;Lzombie/network/packets/INetworkPacket;)V";
        MethodNode pValidate = method(distJava, acs, "validate", validateDesc);
        failed += check("W96 手術後：AntiCheatSpeed.validate 頭部 aload_1／aload_2→TeleportWatch.begin，其餘指令不變；AntiCheatSpeed 其他方法不變",
                headCallSlotsOk(pValidate, helper, "begin", beginDesc, 1, 2)
                        && methodText(pValidate).equals("    ALOAD 1\n    ALOAD 2\n    INVOKESTATIC " + helper + ".begin " + beginDesc + "\n"
                        + methodText(methodFromJar(jar, acs, "validate", validateDesc)))
                        && otherMethodsSame(jar, distJava, acs, "validate", validateDesc));
        String fromReset = "INVOKEVIRTUAL " + npa + ".resetSpeedLimiter ()V";
        String toReset = "INVOKESTATIC " + helper + ".resetSpeedLimiter (L" + npa + ";)V";
        String vTp = methodText(methodFromJar(jar, gs, "sendTeleport", tpDesc));
        failed += check("W96 手術後：sendTeleport 唯一的 resetSpeedLimiter 同形改道 TeleportWatch.resetSpeedLimiter，其餘指令不變",
                vTp.split(java.util.regex.Pattern.quote(fromReset), -1).length == 2
                        && methodText(method(distJava, gs, "sendTeleport", tpDesc)).equals(vTp.replace(fromReset, toReset)));

        ClassNode h = classNode(distJava, helper);
        MethodNode hSet = declaredMethod(h, "set", "(L" + sc + ";FFZLzombie/vehicles/BaseVehicle;)V");
        MethodNode hReset = declaredMethod(h, "resetSpeedLimiter", "(L" + npa + ";)V");
        failed += check("W96 helper 契約：set 的三條路徑各呼叫原版 SpeedChecker.set 一次（off、反射不成立、正常）且都不在 try 內；"
                        + "resetSpeedLimiter 呼叫原版恰一次、不在 try 內；全 class 只有 judge 呼叫 AntiCheat.doKickUser 兩次（自己的跳動、foreign 的送出者）",
                hSet != null && countExactCalls(hSet, Opcodes.INVOKEVIRTUAL, sc, "set", setDesc) == 3
                        && callsInTry(hSet, Opcodes.INVOKEVIRTUAL, sc, "set", setDesc) == 0
                        && hReset != null && countExactCalls(hReset, Opcodes.INVOKEVIRTUAL, npa, "resetSpeedLimiter", "()V") == 1
                        && callsInTry(hReset, Opcodes.INVOKEVIRTUAL, npa, "resetSpeedLimiter", "()V") == 0
                        && classWideCalls(h, Opcodes.INVOKESTATIC, "zombie/network/anticheats/AntiCheat", "doKickUser",
                                "(Lzombie/core/raknet/UdpConnection;Ljava/lang/String;Ljava/lang/String;)V") == 2);
        String bad = teleportHandlerViolations(h);
        failed += check("W96 helper 契約：catch 只有 RuntimeException／LinkageError／ReflectiveOperationException／NumberFormatException（沒有 Throwable、Error、catch-all），"
                + "每個 DebugLog.log 都在含 RuntimeException＋LinkageError 的 try 內" + bad, bad.isEmpty());
        ClassNode widened = classNode(distJava, helper);
        ClassNode unguarded = classNode(distJava, helper);
        MethodNode wSet = declaredMethod(widened, "set", "(L" + sc + ";FFZLzombie/vehicles/BaseVehicle;)V");
        MethodNode uBeat = declaredMethod(unguarded, "beat", "(J)V");
        boolean mutable = wSet != null && !wSet.tryCatchBlocks.isEmpty() && uBeat != null && !uBeat.tryCatchBlocks.isEmpty();
        if (mutable) {
            wSet.tryCatchBlocks.get(0).type = "java/lang/Throwable";
            uBeat.tryCatchBlocks.clear();
        }
        failed += check("W96 例外型別鎖負對照：set 的 catch 放寬成 Throwable、beat 的 log 拿掉 try 都會紅",
                mutable && !teleportHandlerViolations(widened).isEmpty() && !teleportHandlerViolations(unguarded).isEmpty());
        return failed;
    }

    /**
     * W96 例外型別：診斷碼只吞 RuntimeException／LinkageError（反射讀欄位另加 ReflectiveOperationException，解析旗標用
     * NumberFormatException），Error 照常外拋；每個 DebugLog.log 都在含 RuntimeException＋LinkageError 的 try 內。回傳違規描述（空字串＝通過）。
     */
    static String teleportHandlerViolations(ClassNode cn) {
        Set<String> allowed = Set.of("java/lang/RuntimeException", "java/lang/LinkageError", "java/lang/ReflectiveOperationException",
                "java/lang/NumberFormatException");
        StringBuilder bad = new StringBuilder();
        for (MethodNode m : cn.methods) {
            for (TryCatchBlockNode t : m.tryCatchBlocks) {
                if (!allowed.contains(t.type)) {
                    bad.append(' ').append(m.name).append(" catch=").append(t.type);
                }
            }
            for (AbstractInsnNode in : m.instructions) {
                if (isCall(in, Opcodes.INVOKESTATIC, "zombie/debug/DebugLog", "log", "(Ljava/lang/String;)V")) {
                    int at = m.instructions.indexOf(in);
                    Set<String> types = new java.util.TreeSet<>();
                    for (TryCatchBlockNode t : m.tryCatchBlocks) {
                        if (m.instructions.indexOf(t.start) <= at && at < m.instructions.indexOf(t.end)) {
                            types.add(String.valueOf(t.type));
                        }
                    }
                    if (!types.contains("java/lang/RuntimeException") || !types.contains("java/lang/LinkageError")) {
                        bad.append(' ').append(m.name).append(" log handlers=").append(types);
                    }
                }
            }
        }
        return bad.toString();
    }

    /** W97：helper 照抄原版 authClient 的 bannedip 查詢、沿用 LoginPacket 對封鎖結果的處理、Relay 判斷同 BanSystem。 */
    static final String[][] STEAM_IP_BAN_LOCKED = {
            {"zombie/network/ServerWorldDatabase", "authClient",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JI)Lzombie/network/ServerWorldDatabase$LogonResult;",
                    "d41f27a0f7404b6de83be1d579fe8220ab3036bd81d5c03b10b2d86e9874851a"},
            {"zombie/network/packets/connection/LoginPacket", "processServer",
                    "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V",
                    "686871358decc3e9b382c6fe957e52214d827ad36108901e0060d3d19946d67a"},
            {"zombie/network/packets/connection/GoogleAuthKeyPacket", "processServer",
                    "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V",
                    "8dd8c7521e3a647461a4b7002cca58a7cd37b4165654681dd94fd6093b450f88"},
            {"zombie/network/BanSystem", "isIpUsedBySteamRelayConnection", "(Ljava/lang/String;)Z",
                    "267464e92c0d3f37d0a152d996a1671aa4787adbd887177d94aae11bf637b291"},
    };

    /**
     * W97 Steam 模式也擋 bannedip（docs/patches.md 2df）：存在理由（原版 authClient 的 bannedip 查詢在 isSteamModeEnabled 為真時跳過）、
     * 原版本體 SHA 鎖、helper 用同一句 SQL、W23 gate 先判 W97 再委派原版。
     */
    static int checkSteamIpBan(Path jar, Path distJava) throws Exception {
        int failed = 0;
        String swdb = "zombie/network/ServerWorldDatabase";
        String authDesc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JI)L" + swdb + "$LogonResult;";
        String sql = "SELECT reason FROM bannedip WHERE ip = ?";
        MethodNode auth = methodFromJar(jar, swdb, "authClient", authDesc);
        MethodInsnNode firstSteam = null;
        LdcInsnNode query = null;
        for (AbstractInsnNode in : auth.instructions) {
            if (firstSteam == null && in instanceof MethodInsnNode mi
                    && isCall(mi, Opcodes.INVOKESTATIC, "zombie/core/znet/SteamUtils", "isSteamModeEnabled", "()Z")) {
                firstSteam = mi;
            } else if (in instanceof LdcInsnNode ldc && sql.equals(ldc.cst)) {
                query = ldc;
            }
        }
        boolean guarded = false;
        if (firstSteam != null && query != null && nextReal(firstSteam) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFNE) {
            int q = auth.instructions.indexOf(query);
            guarded = auth.instructions.indexOf(firstSteam) < q && q < auth.instructions.indexOf(j.label);
        }
        failed += check("W97 存在理由：原版 authClient 第一個 isSteamModeEnabled 接 ifne，跳過「" + sql + "」（Steam 模式不查 IP；官方改成也查就撤刀）",
                guarded);
        failed += shaLocks(jar, "W97", STEAM_IP_BAN_LOCKED);
        MethodNode reason = method(distJava, "zombie/network/MdcSteamIpBan", "bannedReason", "(L" + swdb + ";Ljava/lang/String;)Ljava/lang/String;");
        boolean sameSql = false;
        for (AbstractInsnNode in : reason.instructions) {
            sameSql |= in instanceof LdcInsnNode ldc && sql.equals(ldc.cst);
        }
        failed += check("W97 helper 用原版同一句 SQL", sameSql);
        MethodNode gate = method(distJava, "zombie/network/MdcAccountGate", "authClient", "(L" + swdb + ";" + authDesc.substring(1));
        String checkDesc = "(L" + swdb + ";Ljava/lang/String;Ljava/lang/String;J)L" + swdb + "$LogonResult;";
        failed += check("W97 W23 gate：先呼叫 MdcSteamIpBan.check 恰一次、在委派原版 authClient 之前",
                countExactCalls(gate, Opcodes.INVOKESTATIC, "zombie/network/MdcSteamIpBan", "check", checkDesc) == 1
                        && firstCallIndex(gate, Opcodes.INVOKESTATIC, "zombie/network/MdcSteamIpBan", "check", checkDesc)
                        < firstCallIndex(gate, Opcodes.INVOKEVIRTUAL, swdb, "authClient", authDesc));
        return failed;
    }

    private SmokeCheck() {}
}
