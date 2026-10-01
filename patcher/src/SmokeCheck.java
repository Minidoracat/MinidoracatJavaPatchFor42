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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
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


        if (args.length > 2 && (args[2].equals("client") || args[2].equals("client-lowmem"))) {
            if (clientChecks(distJava, jar, args[2].equals("client-lowmem")) > 0) {
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

            // ---- W7 朝向暫存執行緒隔離：helper 的執行緒私有性（本刀的全部價值所在）----
            // 修的是跨執行緒競態，故「跨執行緒拿到不同實例」是充要條件，必須真的開執行緒驗。
            Class<?> fwdGuard = Class.forName("zombie.mdc.ForwardVectorGuard", true, patched);
            Class<?> vec2Cls = Class.forName("zombie.iso.Vector2", true, patched);
            Method swapM = fwdGuard.getMethod("swap", vec2Cls);
            Object sharedSentinel = vec2Cls.getDeclaredConstructor().newInstance();
            Object mainFirst = swapM.invoke(null, sharedSentinel);
            Object mainSecond = swapM.invoke(null, sharedSentinel);
            Object[] otherThread = new Object[1];
            Thread worker = new Thread(() -> {
                try {
                    otherThread[0] = swapM.invoke(null, sharedSentinel);
                } catch (ReflectiveOperationException e) {
                    otherThread[0] = e;
                }
            }, "W7-smoke-worker");
            worker.start();
            worker.join();
            failed += check("W7 helper：回傳非 null，且不是傳入的共享實例（確實換掉了）",
                    mainFirst != null && mainFirst != sharedSentinel);
            failed += check("W7 helper：同執行緒兩次呼叫回傳同一實例（① 寫 ② 讀語意與原版等價）",
                    mainFirst == mainSecond);
            failed += check("W7 helper：跨執行緒回傳不同實例（競態消失的充要條件）",
                    otherThread[0] instanceof Object v && !(otherThread[0] instanceof Throwable)
                            && v != mainFirst && v != sharedSentinel);

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

        // ---- W5 容器環防崩潰守衛（ItemContainer.getCharacter 遞迴點改道）----
        String icCls = "zombie/inventory/ItemContainer";
        String guardCls = "zombie/mdc/ContainerCycleGuard";
        String getCharDesc = "()Lzombie/characters/IsoGameCharacter;";
        String guardDesc = "(Lzombie/inventory/ItemContainer;)Lzombie/characters/IsoGameCharacter;";
        MethodNode vGetChar = methodFromJar(jar, icCls, "getCharacter", getCharDesc);
        // vanilla 前提：方法內恰一個自我遞迴呼叫（就是要攔的那個），且尚未被改道
        failed += check("vanilla 前提：getCharacter 內恰一個自身遞迴呼叫、零 guard 呼叫",
                countExactCalls(vGetChar, Opcodes.INVOKEVIRTUAL, icCls, "getCharacter", getCharDesc) == 1
                && countExactCalls(vGetChar, Opcodes.INVOKESTATIC, guardCls, "getCharacter", guardDesc) == 0);
        // vanilla 前提：無限遞迴的兩個前置條件仍在（getParent instanceof 分支＋containingItem 欄位）
        failed += check("vanilla 前提：getCharacter 仍讀 containingItem 且有 getParent 分支",
                countFieldTouches(vGetChar, icCls, "containingItem") >= 1
                && countExactCalls(vGetChar, Opcodes.INVOKEVIRTUAL, icCls,
                        "getParent", "()Lzombie/iso/IsoObject;") >= 1);
        MethodNode pGetChar = method(distJava, icCls, "getCharacter", getCharDesc);
        failed += check("W5 遞迴點改道恰一次且原自身遞迴歸零",
                countExactCalls(pGetChar, Opcodes.INVOKESTATIC, guardCls, "getCharacter", guardDesc) == 1
                && countExactCalls(pGetChar, Opcodes.INVOKEVIRTUAL, icCls, "getCharacter", getCharDesc) == 0);
        // 原體保留：非遞迴的兩條返回路徑（角色 parent／null）未被動
        failed += check("W5 原體保留（getParent 呼叫數與 containingItem 觸碰數未變）",
                countExactCalls(pGetChar, Opcodes.INVOKEVIRTUAL, icCls,
                        "getParent", "()Lzombie/iso/IsoObject;")
                == countExactCalls(vGetChar, Opcodes.INVOKEVIRTUAL, icCls,
                        "getParent", "()Lzombie/iso/IsoObject;")
                && countFieldTouches(pGetChar, icCls, "containingItem")
                == countFieldTouches(vGetChar, icCls, "containingItem"));
        // 指令總數不變＝redirect 是嚴格 1:1 替換（把「沒有增刪任何指令」變成結構事實）
        failed += check("W5 getCharacter 指令總數未變（1:1 替換）",
                pGetChar.instructions.size() == vGetChar.instructions.size());
        // 第二刀：isInCharacterInventory（Transaction.getDuration 會走的那條）
        String inCharDesc = "(Lzombie/characters/IsoGameCharacter;)Z";
        String guardInvDesc = "(Lzombie/inventory/ItemContainer;Lzombie/characters/IsoGameCharacter;)Z";
        MethodNode vInChar = methodFromJar(jar, icCls, "isInCharacterInventory", inCharDesc);
        MethodNode pInChar = method(distJava, icCls, "isInCharacterInventory", inCharDesc);
        failed += check("vanilla 前提：isInCharacterInventory 內恰一個自身遞迴呼叫",
                countExactCalls(vInChar, Opcodes.INVOKEVIRTUAL, icCls,
                        "isInCharacterInventory", inCharDesc) == 1);
        failed += check("W5 isInCharacterInventory 改道恰一次、原遞迴歸零、指令總數未變",
                countExactCalls(pInChar, Opcodes.INVOKESTATIC, guardCls,
                        "isInCharacterInventory", guardInvDesc) == 1
                && countExactCalls(pInChar, Opcodes.INVOKEVIRTUAL, icCls,
                        "isInCharacterInventory", inCharDesc) == 0
                && pInChar.instructions.size() == vInChar.instructions.size());
        // 負對照：兩把刀都只在自己的方法內改道（method-scope 鎖定）
        ClassNode pIc = classNode(distJava, icCls);
        int guardCallsWholeClass = 0;
        int guardInvCallsWholeClass = 0;
        for (MethodNode m : pIc.methods) {
            guardCallsWholeClass += countExactCalls(m, Opcodes.INVOKESTATIC, guardCls, "getCharacter", guardDesc);
            guardInvCallsWholeClass += countExactCalls(m, Opcodes.INVOKESTATIC, guardCls,
                    "isInCharacterInventory", guardInvDesc);
        }
        failed += check("W5 負對照：全 class 各僅一處改道（其他呼叫端保持 vanilla）",
                guardCallsWholeClass == 1 && guardInvCallsWholeClass == 1);

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
        failed += check("抑噪#10 同形改道到 LogFilter.log，其餘指令與 frames 保留",
                vChunkUnloadText.replace("INVOKESTATIC zombie/debug/DebugLog.log (Ljava/lang/String;)V",
                        "INVOKESTATIC zombie/mdc/LogFilter.log (Ljava/lang/String;)V")
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

        // ---- W12 車輛 DB chunk 索引一致性（VehiclesDB2$VehicleBuffer.set）----
        String vbCls = "zombie/vehicles/VehiclesDB2$VehicleBuffer";
        String vciCls = "zombie/mdc/VehicleChunkIndexGuard";
        String vbSetDesc = "(L" + vehCls + ";)V";
        String vciCoordDesc = "(L" + vehCls + ";FI)I";
        MethodNode vVbSet = methodFromJar(jar, vbCls, "set", vbSetDesc);
        failed += check("W12 vanilla 前提：chunk/wx/wy 讀取各為 2/1/1，且零 guard 呼叫",
                countInstanceFieldReads(vVbSet, vehCls, "chunk") == 2
                && countInstanceFieldReads(vVbSet, "zombie/iso/IsoChunk", "wx") == 1
                && countInstanceFieldReads(vVbSet, "zombie/iso/IsoChunk", "wy") == 1
                && countExactCalls(vVbSet, Opcodes.INVOKESTATIC, vciCls, "wx", vciCoordDesc) == 0
                && countExactCalls(vVbSet, Opcodes.INVOKESTATIC, vciCls, "wy", vciCoordDesc) == 0);
        MethodNode pVbSet = method(distJava, vbCls, "set", vbSetDesc);
        failed += check("W12 手術後：captured x/y＋vanilla wx/wy 餵 helper、各覆寫正確欄位、只追加 16 條真指令",
                countExactCalls(pVbSet, Opcodes.INVOKESTATIC, vciCls, "wx", vciCoordDesc) == 1
                && countExactCalls(pVbSet, Opcodes.INVOKESTATIC, vciCls, "wy", vciCoordDesc) == 1
                && countInstanceFieldReads(pVbSet, vehCls, "chunk") == 2
                && countInstanceFieldReads(pVbSet, "zombie/iso/IsoChunk", "wx") == 1
                && countInstanceFieldReads(pVbSet, "zombie/iso/IsoChunk", "wy") == 1
                && realInsnCount(pVbSet) == realInsnCount(vVbSet) + 16
                && vehicleChunkRepairSequence(pVbSet, vbCls, vehCls, vciCls, vciCoordDesc));
        ClassNode pVbNode = classNode(distJava, vbCls);
        failed += check("W12 負對照：VehicleBuffer 全 class 僅 set() 的兩個 guard 呼叫",
                classWideCalls(pVbNode, Opcodes.INVOKESTATIC, vciCls, "wx", vciCoordDesc) == 1
                && classWideCalls(pVbNode, Opcodes.INVOKESTATIC, vciCls, "wy", vciCoordDesc) == 1);
        MethodNode vciCoord = method(distJava, vciCls, "chunkCoord", "(F)I");
        failed += check("W12 helper 契約：chunkCoord 唯一 floor sink＝PZMath.fastfloor(F)I",
                countExactCalls(vciCoord, Opcodes.INVOKESTATIC,
                        "zombie/core/math/PZMath", "fastfloor", "(F)I") == 1);

        // ---- W7 朝向暫存執行緒隔離（IsoGameCharacter.setForwardDirectionFromIsoDirection）----
        String igcCls = "zombie/characters/IsoGameCharacter";
        String fwdGuardCls = "zombie/mdc/ForwardVectorGuard";
        String vec2Desc = "Lzombie/iso/Vector2;";
        String swapDesc = "(" + vec2Desc + ")" + vec2Desc;
        MethodNode vFwd = methodFromJar(jar, igcCls, "setForwardDirectionFromIsoDirection", "()V");
        // vanilla 前提：方法體恰為 8 條無分支指令。PZ 若改寫此方法（例如自己修了競態、
        // 或改用別的暫存），全序不符即建置失敗，而非默默把刀插到錯的地方。
        failed += check("W7 vanilla 前提：方法體全序＝aload/getstatic/invokevirtual/pop ×2 收 return",
                matchOpcodeSeq(vFwd, new int[]{
                        Opcodes.ALOAD, Opcodes.GETSTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.POP,
                        Opcodes.ALOAD, Opcodes.GETSTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.RETURN}));
        failed += check("W7 vanilla 前提：方法內 getstatic tempVector2_2 恰 2 次",
                countFieldReads(vFwd, igcCls, "tempVector2_2") == 2);
        // matchOpcodeSeq 只比 opcode，operand-blind（codex 審查發現的 fail-closed 缺口）：
        // PZ 若保留相同 opcode 形狀與兩個 tempVector2_2 讀取、只把 call target 換掉，
        // 上面的全序閘仍會全綠，違反「任何方法改寫都讓建置失敗」的契約。故把兩個
        // invokevirtual 的 owner/name/desc 一併鎖住——手術本身不動它們，前後都該恰 1 次。
        String getVecDesc = "(" + vec2Desc + ")" + vec2Desc;
        String setFwdDesc = "(" + vec2Desc + ")V";
        failed += check("W7 vanilla 前提：兩個 invokevirtual 目標鎖定（getVectorFromDirection／setForwardDirection 各 1）",
                countExactCalls(vFwd, Opcodes.INVOKEVIRTUAL, igcCls, "getVectorFromDirection", getVecDesc) == 1
                && countExactCalls(vFwd, Opcodes.INVOKEVIRTUAL, igcCls, "setForwardDirection", setFwdDesc) == 1);
        MethodNode pFwd = method(distJava, igcCls, "setForwardDirectionFromIsoDirection", "()V");
        failed += check("W7 手術後：全序＝兩組 getstatic→swap→invokevirtual（swap 緊接 getstatic）",
                matchOpcodeSeq(pFwd, new int[]{
                        Opcodes.ALOAD, Opcodes.GETSTATIC, Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL,
                        Opcodes.POP,
                        Opcodes.ALOAD, Opcodes.GETSTATIC, Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL,
                        Opcodes.RETURN}));
        failed += check("W7 手術後：swap 改道 x2，且 getstatic 保留 x2（吃掉共享值而非刪除讀取）",
                countExactCalls(pFwd, Opcodes.INVOKESTATIC, fwdGuardCls, "swap", swapDesc) == 2
                && countFieldReads(pFwd, igcCls, "tempVector2_2") == 2);
        failed += check("W7 手術後：兩個 invokevirtual 目標未被動到（手術只插入，不改 call target）",
                countExactCalls(pFwd, Opcodes.INVOKEVIRTUAL, igcCls, "getVectorFromDirection", getVecDesc) == 1
                && countExactCalls(pFwd, Opcodes.INVOKEVIRTUAL, igcCls, "setForwardDirection", setFwdDesc) == 1);
        ClassNode pIgcNode = classNode(distJava, igcCls);
        failed += check("W7 負對照：IsoGameCharacter 其餘方法零 swap 改道",
                pIgcNode.methods.stream()
                        .filter(m -> !m.name.equals("setForwardDirectionFromIsoDirection"))
                        .allMatch(m -> countExactCalls(m, Opcodes.INVOKESTATIC,
                                fwdGuardCls, "swap", swapDesc) == 0));
        // 耦合鎖：本刀移除了「setForwardDirectionFromIsoDirection 會在共享實例留下值」這個
        // 副作用。原版其餘 10 個讀取點（processHitDamage x2／renderlast x4／isObjectBehind／
        // isBehind／updateMovementStatistics x2）已逐一核對皆為先寫後讀，無人依賴該遺留值。
        // 把類別內 getstatic 總數釘住——TIS 新增任何讀者都得重新做這份核對。
        ClassNode vIgcNode = classNodeFromJar(jar, igcCls);
        int vanillaReads = vIgcNode.methods.stream()
                .mapToInt(m -> countFieldReads(m, igcCls, "tempVector2_2")).sum();
        int patchedReads = pIgcNode.methods.stream()
                .mapToInt(m -> countFieldReads(m, igcCls, "tempVector2_2")).sum();
        failed += check("W7 耦合鎖：tempVector2_2 類別內 getstatic 總數＝12 且手術前後一致",
                vanillaReads == 12 && patchedReads == 12);

        // ---- W8 chunk 寫入閘（IsoChunk.Save(Z) ×2＋ServerChunkLoader$SaveLoadedTask.save ×1）----
        String w8IcCls = "zombie/iso/IsoChunk";
        String cwgCls = "zombie/mdc/ChunkWriteGuard";
        String swDesc = "(IILjava/nio/ByteBuffer;)V";
        String sltCls = "zombie/network/ServerChunkLoader$SaveLoadedTask";
        // vanilla 前提：兩個手術方法內的 SafeWrite 呼叫數與 checksum 互動形狀
        MethodNode vSaveB = methodFromJar(jar, w8IcCls, "Save", "(Z)V");
        failed += check("W8 vanilla 前提：IsoChunk.Save(Z) 內 SafeWrite x2、setChecksum x1",
                countExactCalls(vSaveB, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 2
                && countExactCalls(vSaveB, Opcodes.INVOKESTATIC, "zombie/network/ChunkChecksum",
                        "setChecksum", "(IIJ)V") == 1);
        MethodNode vSlt = methodFromJar(jar, sltCls, "save", "()V");
        failed += check("W8 vanilla 前提：SaveLoadedTask.save 內 SafeWrite x1、setChecksum x1（歸零重試假設的錨）",
                countExactCalls(vSlt, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1
                && countExactCalls(vSlt, Opcodes.INVOKESTATIC, "zombie/network/ChunkChecksum",
                        "setChecksum", "(IIJ)V") == 1);
        // 順序鎖（codex 審查修正）：guard 的 checksum 歸零假設「caller 先 setChecksum 再
        // SafeWrite」；PZ 重排即建置失敗，而非讓歸零默默覆寫錯的時序
        failed += check("W8 順序鎖：兩方法內 setChecksum 皆先於 SafeWrite（歸零重試假設的時序錨）",
                firstCallIndex(vSaveB, Opcodes.INVOKESTATIC, "zombie/network/ChunkChecksum", "setChecksum", "(IIJ)V")
                        < firstCallIndex(vSaveB, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc)
                && firstCallIndex(vSlt, Opcodes.INVOKESTATIC, "zombie/network/ChunkChecksum", "setChecksum", "(IIJ)V")
                        < firstCallIndex(vSlt, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc));
        // 全 jar census（總數）＋逐類分佈（堵「舊點消失＋新點出現」互相抵銷的 false-green）
        failed += check("W8 census：全 jar SafeWrite 呼叫點恰 5 個（新增即代表有未設閘的寫檔路徑）",
                jarWideCallsiteCensus(jar, w8IcCls, "SafeWrite", swDesc) == 5);
        failed += check("W8 census 分佈：IsoChunk=2／ChunkSaveWorker=1／WorldGenerate=1／SaveLoadedTask=1",
                classWideCalls(classNodeFromJar(jar, w8IcCls), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 2
                && classWideCalls(classNodeFromJar(jar, "zombie/iso/ChunkSaveWorker"), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1
                && classWideCalls(classNodeFromJar(jar, "zombie/iso/WorldGenerate"), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1
                && classWideCalls(classNodeFromJar(jar, sltCls), Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 1);
        // 排除論證的錨 1：ChunkSaveWorker 唯一入列點 AddHotSave 被 GameServer.server 閘住，
        // 且方向鎖定（getstatic server 緊接 ifne＝server 為真即跳離；codex 審查補強）
        MethodNode vIcmUpd = methodFromJar(jar, "zombie/iso/IsoChunkMap", "updateInternal", "()V");
        failed += check("W8 排除前提：IsoChunkMap.updateInternal 有 AddHotSave x1 且 GameServer.server→ifne 方向鎖",
                countExactCalls(vIcmUpd, Opcodes.INVOKEVIRTUAL, "zombie/iso/ChunkSaveWorker",
                        "AddHotSave", "(Lzombie/iso/IsoChunk;)V") == 1
                && existsFieldReadThenJump(vIcmUpd, "zombie/network/GameServer", "server", Opcodes.IFNE));
        // 格式前提：helper 硬編 offset 的語境鎖（非僅常數存在）——17 必須是 CRC32.update 的
        // offset 引數、5 必須是 position() 的引數；版本常數 249 恰 1（codex 審查補強）
        MethodNode vSaveBuf = methodFromJar(jar, w8IcCls, "Save",
                "(Ljava/nio/ByteBuffer;Ljava/util/zip/CRC32;Z)Ljava/nio/ByteBuffer;");
        failed += check("W8 格式前提：249 恰 1、17→CRC32.update 語境、5→ByteBuffer.position 語境",
                countIntConst(vSaveBuf, 249) == 1
                // 17 與 update 之間隔著 len-1-4-4-8 的四次 isub 展開（javap 實測 9 條真指令）
                && existsConstThenCall(vSaveBuf, 17, "java/util/zip/CRC32", "update", 12)
                && existsConstThenCall(vSaveBuf, 5, "java/nio/ByteBuffer", "position", 2));
        // 手術後：改道到位、原呼叫歸零
        MethodNode pSaveB = method(distJava, w8IcCls, "Save", "(Z)V");
        failed += check("W8 手術後：Save(Z) 改道 x2、原 SafeWrite 呼叫歸零",
                countExactCalls(pSaveB, Opcodes.INVOKESTATIC, cwgCls, "safeWrite", swDesc) == 2
                && countExactCalls(pSaveB, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 0);
        MethodNode pSlt = method(distJava, sltCls, "save", "()V");
        failed += check("W8 手術後：SaveLoadedTask.save 改道 x1、原呼叫歸零",
                countExactCalls(pSlt, Opcodes.INVOKESTATIC, cwgCls, "safeWrite", swDesc) == 1
                && countExactCalls(pSlt, Opcodes.INVOKESTATIC, w8IcCls, "SafeWrite", swDesc) == 0);
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
        // 釘住官方修法：addLoadedJob、SaveLoadedTask.save 各自 new CRC32（區域變數），且
        // ServerChunkLoader／SaveChunkThread／SaveLoadedTask／IsoChunk 沒有任何 CRC32 型別欄位。
        // TIS 若退回共用實例＝這條紅，重新評估是否復活兩刀（git checkout 8d2bee8）。
        String crcDesc = "Ljava/util/zip/CRC32;";
        boolean noSharedCrc = true;
        for (String c : new String[]{"zombie/network/ServerChunkLoader", sctCls, sltCls, w8IcCls}) {
            for (var fn : classNodeFromJar(jar, c).fields) {
                if (crcDesc.equals(fn.desc)) {
                    noSharedCrc = false;
                }
            }
        }
        failed += check("W9 退役前提：addLoadedJob／SaveLoadedTask.save 各 new CRC32 x1，存檔管線四類零 CRC32 欄位",
                noSharedCrc
                && countNew(vAdd, "java/util/zip/CRC32") == 1
                && countNew(vSlt, "java/util/zip/CRC32") == 1);
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
        failed += check("W9 vanilla 前提：SaveLoadedTask.release 內 releaseChunk x1",
                countExactCalls(vRel, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 1);
        // 序列化者清冊：全 jar SaveLoadedChunk 呼叫者恰 2 且逐類分佈釘死（codex 修正：
        // 只比總數會讓「舊點消失＋新點出現」互抵通過）。addLoadedJob＝本刀隔離；
        // PlayerDownloadServer.update 用 per-connection CRC32 且僅主緒＝分析上安全。
        String slcDesc = "(L" + chunkRef + ";Ljava/util/zip/CRC32;)V";
        failed += check("W9 census：全 jar SaveLoadedChunk 呼叫點恰 2 且分佈＝SaveChunkThread 1／PlayerDownloadServer 1",
                jarWideCallsiteCensus(jar, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoChunk", "SaveLoadedChunk", slcDesc) == 2
                && classWideCalls(classNodeFromJar(jar, sctCls), Opcodes.INVOKEVIRTUAL,
                        "zombie/iso/IsoChunk", "SaveLoadedChunk", slcDesc) == 1
                && classWideCalls(classNodeFromJar(jar, "zombie/network/PlayerDownloadServer"), Opcodes.INVOKEVIRTUAL,
                        "zombie/iso/IsoChunk", "SaveLoadedChunk", slcDesc) == 1);
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
        failed += check("W9 負對照：SaveLoadedTask.save 零 ChunkSaveIsolation 呼叫（只剩 W8 改道）",
                countCallsToOwner(pSlt, csiCls) == 0);
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
    static int clientChecks(Path distJava, Path jar, boolean lowmem) throws Exception {
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
        failed += check("PatchInfo 版本指紋已生成且四個常數非空（client）",
                patchInfoOk(distJava, "client"));
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
        return failed;
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

    /** 鎖定 W12 在 captured y PUTFIELD 後的完整 operand/order，避免 helper 結果寫錯欄位。 */
    static boolean vehicleChunkRepairSequence(MethodNode method, String bufferOwner,
                                              String vehicleOwner, String helperOwner,
                                              String helperDesc) {
        int anchors = 0;
        int matches = 0;
        for (AbstractInsnNode in : method.instructions) {
            if (!isField(in, Opcodes.PUTFIELD, bufferOwner, "y", "F")) {
                continue;
            }
            anchors++;
            AbstractInsnNode[] s = new AbstractInsnNode[16];
            AbstractInsnNode cursor = in;
            for (int i = 0; i < s.length; i++) {
                cursor = nextReal(cursor);
                s[i] = cursor;
            }
            boolean ok =
                    isVar(s[0], Opcodes.ALOAD, 0)
                    && isVar(s[1], Opcodes.ALOAD, 1)
                    && isVar(s[2], Opcodes.ALOAD, 0)
                    && isField(s[3], Opcodes.GETFIELD, bufferOwner, "x", "F")
                    && isVar(s[4], Opcodes.ALOAD, 0)
                    && isField(s[5], Opcodes.GETFIELD, bufferOwner, "wx", "I")
                    && isCall(s[6], Opcodes.INVOKESTATIC, helperOwner, "wx", helperDesc)
                    && isField(s[7], Opcodes.PUTFIELD, bufferOwner, "wx", "I")
                    && isVar(s[8], Opcodes.ALOAD, 0)
                    && isVar(s[9], Opcodes.ALOAD, 1)
                    && isVar(s[10], Opcodes.ALOAD, 0)
                    && isField(s[11], Opcodes.GETFIELD, bufferOwner, "y", "F")
                    && isVar(s[12], Opcodes.ALOAD, 0)
                    && isField(s[13], Opcodes.GETFIELD, bufferOwner, "wy", "I")
                    && isCall(s[14], Opcodes.INVOKESTATIC, helperOwner, "wy", helperDesc)
                    && isField(s[15], Opcodes.PUTFIELD, bufferOwner, "wy", "I");
            if (ok) {
                matches++;
            }
        }
        return anchors == 1 && matches == 1;
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

    private SmokeCheck() {}
}
