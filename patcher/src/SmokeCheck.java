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

            // ---- W9 存檔管線隔離：CRC 執行緒私有性＋機制錨＋私有池行為（根治刀的可測核心）----
            Class<?> csi = Class.forName("zombie.mdc.ChunkSaveIsolation", true, patched);
            Method hcM = csi.getMethod("headerCrc", java.util.zip.CRC32.class);
            Method dcM = csi.getMethod("dedupCrc", java.util.zip.CRC32.class);
            java.util.zip.CRC32 sharedCrc = new java.util.zip.CRC32();
            Object hc1 = hcM.invoke(null, sharedCrc);
            Object hc2 = hcM.invoke(null, sharedCrc);
            Object dc1 = dcM.invoke(null, sharedCrc);
            Object[] hcOther = new Object[1];
            Thread crcWorker = new Thread(() -> {
                try {
                    hcOther[0] = hcM.invoke(null, sharedCrc);
                } catch (ReflectiveOperationException e) {
                    hcOther[0] = e;
                }
            }, "W9-smoke-worker");
            crcWorker.start();
            crcWorker.join();
            failed += check("W9 headerCrc：非共享實例、同緒穩定、跨緒相異（指紋競態消失的充要條件）",
                    hc1 != null && hc1 != sharedCrc && hc1 == hc2
                    && hcOther[0] != null && !(hcOther[0] instanceof Throwable)
                    && hcOther[0] != hc1 && hcOther[0] != sharedCrc);
            failed += check("W9 dedupCrc：與 headerCrc 分族隔離（同緒序列化中途做去重不互踩）",
                    dc1 != null && dc1 != sharedCrc && dc1 != hc1);
            // 機制錨（定罪的最小重演，單緒確定性模擬交錯）：外部 reset 插在 update 與
            // getValue 之間 → 指紋 0（A 組 16 筆歷史＋W8 首晚 3 筆的簽名）；外部 update
            // 疊入 → 垃圾值（B 組 27 筆＋5 筆）。body/len 由各自序列化者完整寫入，故
            // len 恆正確——與 8/8 現行犯觀測相容的唯一機制。
            java.util.zip.CRC32 anchor = new java.util.zip.CRC32();
            byte[] anchorBody = new byte[]{1, 2, 3, 4, 5, 6, 7};
            anchor.update(anchorBody);
            long anchorCorrect = anchor.getValue();
            anchor.reset();
            anchor.update(anchorBody);
            anchor.reset();
            long anchorZero = anchor.getValue();
            anchor.reset();
            anchor.update(anchorBody);
            anchor.update(anchorBody);
            long anchorGarbage = anchor.getValue();
            failed += check("W9 機制錨：共用 CRC32 遭外部 reset→0（A 組簽名）、遭疊 update→垃圾（B 組簽名）",
                    anchorZero == 0L && anchorGarbage != anchorCorrect && anchorCorrect != 0L);
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

        // 退役（2026-09-02）：登入／join 卡頓量測的全部結構斷言（LoginPacket 三個 DB
        // wrapper、CreatePlayerPacket 四個重活、REJOIN_TOTAL／REJOIN_LOAD_CHARACTER）。
        // 歸因任務已完成、正式服 REJOIN_TOTAL 常態 5–13ms，量測刀隨斷言一併移除。
        // 詳見 docs/patches.md 2i；復活方式：從退役前最後一版 1e637fc 取回（`git checkout 1e637fc -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

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

        // ---- W5-2 容器環門口 observe：同一個 ItemContainer ClassPatch 上疊加 ----
        String addProbeCls = "zombie/mdc/ContainerAddCycleProbe";
        String invItemCls = "zombie/inventory/InventoryItem";
        String w52AddDesc = "(L" + invItemCls + ";)L" + invItemCls + ";";
        String containsDesc = "(I)Z";
        String probeContainsDesc = "(L" + icCls + ";I)Z";
        MethodNode vAddItem = methodFromJar(jar, icCls, "AddItem", w52AddDesc);
        MethodNode pAddItem = method(distJava, icCls, "AddItem", w52AddDesc);
        MethodNode vAddBlind = methodFromJar(jar, icCls, "AddItemBlind", w52AddDesc);
        MethodNode pAddBlind = method(distJava, icCls, "AddItemBlind", w52AddDesc);
        failed += check("W5-2 vanilla：AddItem containsID 恰1、Blind無probe掛點",
                countExactCalls(vAddItem, Opcodes.INVOKEVIRTUAL,
                        icCls, "containsID", containsDesc) == 1);
        failed += check("W5-2 patched：AddItem 1→1 observe改道；Blind刻意保持vanilla",
                countExactCalls(pAddItem, Opcodes.INVOKESTATIC,
                        addProbeCls, "containsID", probeContainsDesc) == 1
                && countExactCalls(pAddItem, Opcodes.INVOKEVIRTUAL,
                        icCls, "containsID", containsDesc) == 0
                && realInsnCount(pAddItem) == realInsnCount(vAddItem)
                && realInsnCount(pAddBlind) == realInsnCount(vAddBlind));
        MethodNode addProbeEntry = method(distJava, addProbeCls, "containsID", probeContainsDesc);
        failed += check("W5-2 wrapper：vanilla containsID恰1、probeAndLog恰1",
                countExactCalls(addProbeEntry, Opcodes.INVOKEVIRTUAL,
                        icCls, "containsID", containsDesc) == 1
                && countExactCalls(addProbeEntry, Opcodes.INVOKESTATIC,
                        addProbeCls, "probeAndLog", "(L" + icCls + ";I)V") == 1);
        MethodNode addProbe = method(distJava, addProbeCls, "probe", "(L" + icCls + ";I)I");
        failed += check("W5-2 helper：probe零NEW零Rand；catch只RuntimeException",
                countOpcode(addProbe, Opcodes.NEW) == 0
                && classNode(distJava, addProbeCls).methods.stream()
                        .mapToInt(m -> countCallsToOwner(m, "zombie/core/random/Rand")).sum() == 0
                && classNode(distJava, addProbeCls).methods.stream()
                        .flatMap(m -> m.tryCatchBlocks.stream())
                        .allMatch(tcb -> "java/lang/RuntimeException".equals(tcb.type)));


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

        // 退役（2026-09-02）：W4-1 chunk 供給併包（PlayerDownloadServer 掛點）的全部
        // vanilla 前提與手術後斷言。42.20.3 官方 pending 機制上線後 packed 只剩
        // 47–82 次/session、skip[short] 99.3%＝效益≈0，刀與斷言一併移除。
        // 詳見 docs/patches.md 2p；復活方式：從退役前最後一版 1e637fc 取回（`git checkout 1e637fc -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

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

        // ---- W9 存檔管線隔離（addLoadedJob 的指紋 CRC＋池租借；SaveLoadedTask 的去重 CRC＋歸還）----
        String sctCls = "zombie/network/ServerChunkLoader$SaveChunkThread";
        String csiCls = "zombie/mdc/ChunkSaveIsolation";
        String ccrRef = "zombie/network/ClientChunkRequest";
        String chunkRef = "zombie/network/ClientChunkRequest$Chunk";
        String getChunkDesc = "()L" + chunkRef + ";";
        String chunkArgDesc = "(L" + chunkRef + ";)V";
        String addLoadedDesc = "(Lzombie/iso/IsoChunk;)V";
        MethodNode vAdd = methodFromJar(jar, sctCls, "addLoadedJob", addLoadedDesc);
        failed += check("W9 vanilla 前提：addLoadedJob 內 crc32 讀 x1、getChunk/getByteBuffer/releaseChunk 各 x1",
                countInstanceFieldReads(vAdd, sctCls, "crc32") == 1
                && countExactCalls(vAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getChunk", getChunkDesc) == 1
                && countExactCalls(vAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getByteBuffer", chunkArgDesc) == 1
                && countExactCalls(vAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 1);
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
        failed += check("W9 vanilla 前提：SaveLoadedTask.save 內 crcSave 讀 x4（reset/update/getValue×2 四連讀）",
                countInstanceFieldReads(vSlt, "zombie/network/ServerChunkLoader", "crcSave") == 4);
        MethodNode vRel = methodFromJar(jar, sltCls, "release", "()V");
        failed += check("W9 vanilla 前提：SaveLoadedTask.release 內 releaseChunk x1",
                countExactCalls(vRel, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 1);
        // 耦合鎖（codex 對抗審查改為全 jar fail-closed：硬編類別清單掃不到新增的
        // nestmate 讀者）：兩顆共用 CRC32 的讀者全 jar 總數＝已釘位置的數量——
        // TIS 在任何 class 新增讀者＝總數超標＝建置失敗強制重新分析
        failed += check("W9 耦合鎖：全 jar crcSave 讀者恰 4（全在 SaveLoadedTask.save）、crc32 讀者恰 1（全在 addLoadedJob）",
                jarWideFieldReadCensus(jar, Opcodes.GETFIELD, "zombie/network/ServerChunkLoader", "crcSave") == 4
                && countInstanceFieldReads(vSlt, "zombie/network/ServerChunkLoader", "crcSave") == 4
                && jarWideFieldReadCensus(jar, Opcodes.GETFIELD, sctCls, "crc32") == 1
                && countInstanceFieldReads(vAdd, sctCls, "crc32") == 1);
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
        // 手術後：同形替換緊鄰性（GETFIELD 之後必須緊接 helper——隔開＝吃錯堆疊值）＋原呼叫歸零
        MethodNode pAdd = method(distJava, sctCls, "addLoadedJob", addLoadedDesc);
        failed += check("W9 手術後：addLoadedJob crc32→headerCrc 緊鄰 x1、三呼叫改道、原 invokevirtual 歸零",
                swapAdjacency(pAdd, sctCls, "crc32", csiCls, "headerCrc") == 1
                && countExactCalls(pAdd, Opcodes.INVOKESTATIC, csiCls, "getChunk",
                        "(L" + ccrRef + ";)L" + chunkRef + ";") == 1
                && countExactCalls(pAdd, Opcodes.INVOKESTATIC, csiCls, "getByteBuffer",
                        "(L" + ccrRef + ";L" + chunkRef + ";)V") == 1
                && countExactCalls(pAdd, Opcodes.INVOKESTATIC, csiCls, "releaseChunk",
                        "(L" + ccrRef + ";L" + chunkRef + ";)V") == 1
                && countExactCalls(pAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getChunk", getChunkDesc) == 0
                && countExactCalls(pAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "getByteBuffer", chunkArgDesc) == 0
                && countExactCalls(pAdd, Opcodes.INVOKEVIRTUAL, ccrRef, "releaseChunk", chunkArgDesc) == 0);
        failed += check("W9 手術後：save() crcSave→dedupCrc 緊鄰 x4",
                swapAdjacency(pSlt, "zombie/network/ServerChunkLoader", "crcSave", csiCls, "dedupCrc") == 4);
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
        // 詳見 docs/patches.md 2w；復活方式：從退役前最後一版 1e637fc 取回（`git checkout 1e637fc -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

        // ---- W10 卡讀條根治（NetTimedAction.parse 例外攔截 ＋ processServer 回覆 state 補正）----
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
        String writeHelperDesc = "(L" + ntaPktCls + ";Lzombie/core/network/ByteBufferWriter;)V";
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

        // A 刀的存在理由，釘成結構事實：processServer 對 act（slot 3）設 state，卻用 this（slot 0）
        // 送出。TIS 修好這個 bug（receiver 換成 act）時本條會紅——提醒撤刀，而不是讓兩份修正疊加。
        MethodNode vNtaProcess = methodFromJar(jar, ntaPktCls, "processServer", psDesc);
        int writeOnThis = 0;
        int writeTotal = 0;
        for (AbstractInsnNode in = vNtaProcess.instructions.getFirst(); in != null; in = in.getNext()) {
            if (!(in instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !ntaPktCls.equals(mi.owner) || !"write".equals(mi.name) || !bbwDesc.equals(mi.desc)) {
                continue;
            }
            writeTotal++;
            AbstractInsnNode receiver = prevReal(prevReal(in));   // receiver, bbw, write
            if (receiver instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
                writeOnThis++;
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
        failed += check("W10 vanilla 前提（A 刀存在理由）：processServer 的 write 兩處 receiver 皆 this，"
                + "而 setState 兩處 receiver 皆非 this ＝ 該方法的初始回覆必帶 Request state",
                writeTotal == 2 && writeOnThis == 2 && setStateOnAct == 2);

        // 手術後：兩處改道、原呼叫歸零、真指令數不變（1:1 同形替換）
        MethodNode pNtaParse = method(distJava, ntaCls, "parse", ntaParseDesc);
        failed += check("W10 手術後：parse 改道 x1、原 protectedCall 歸零、真指令數不變",
                countExactCalls(pNtaParse, Opcodes.INVOKESTATIC, ntaGuardCls, "protectedCall", pcHelperDesc) == 1
                && countExactCalls(pNtaParse, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc) == 0
                && realInsnCount(pNtaParse) == realInsnCount(vNtaParse));
        MethodNode pNtaProcess = method(distJava, ntaPktCls, "processServer", psDesc);
        failed += check("W10 手術後：processServer 改道 x2、原 write 歸零、真指令數不變",
                countExactCalls(pNtaProcess, Opcodes.INVOKESTATIC, ntaGuardCls, "write", writeHelperDesc) == 2
                && countExactCalls(pNtaProcess, Opcodes.INVOKEVIRTUAL, ntaPktCls, "write", bbwDesc) == 0
                && realInsnCount(pNtaProcess) == realInsnCount(vNtaProcess));

        // helper 契約 1：catch 型別鎖定 RuntimeException——Error（SOE／OOM）必須穿透，
        // 與 W6 同紀律。放寬成 Throwable 會把致命錯誤變成「靜默 reject」。
        MethodNode guardCall = method(distJava, ntaGuardCls, "protectedCall", pcHelperDesc);
        failed += check("W10 helper 契約：protectedCall 的 catch 恰一個且型別為 RuntimeException（Error 穿透）",
                guardCall.tryCatchBlocks != null && guardCall.tryCatchBlocks.size() == 1
                && "java/lang/RuntimeException".equals(guardCall.tryCatchBlocks.get(0).type));
        // helper 契約 2：委派回原方法恰 2 處（kill switch 直通＋try 內正常路徑），且 caller 只被呼叫這兩次
        failed += check("W10 helper 契約：protectedCall 委派原呼叫恰 2 處（off 直通＋on 正常路徑）",
                countExactCalls(guardCall, Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc) == 2);
        // helper 契約 3：state 補正與線路寫入都不得被診斷邏輯吞掉——兩者恰一次且在 try 外
        MethodNode guardWrite = method(distJava, ntaGuardCls, "write", writeHelperDesc);
        String setStateDesc = "(Lzombie/core/Transaction$TransactionState;)V";
        failed += check("W10 helper 契約：setState／write 各恰 1 次且不在 try 範圍內（A 刀 fail-fast）",
                countExactCalls(guardWrite, Opcodes.INVOKEVIRTUAL, ntaPktCls, "setState", setStateDesc) == 1
                && callsInsideTryRange(guardWrite, Opcodes.INVOKEVIRTUAL, ntaPktCls,
                        "setState", setStateDesc) == 0
                && countExactCalls(guardWrite, Opcodes.INVOKEVIRTUAL, ntaPktCls, "write", bbwDesc) == 1
                && callsInsideTryRange(guardWrite, Opcodes.INVOKEVIRTUAL, ntaPktCls, "write", bbwDesc) == 0);

        // 負對照（相對 vanilla 差值，避免絕對零在 PZ 新增同名呼叫時誤報）：
        // NetTimedAction 只少一個 protectedCall（getDuration/start/stop/perform 逐一未動），
        // NetTimedActionPacket 只少兩個 write。
        failed += check("W10 負對照：NetTimedAction 全 class protectedCall 恰少 1（其餘方法未動）",
                classWideCalls(classNode(distJava, ntaCls), Opcodes.INVOKEVIRTUAL, luaCaller, "protectedCall", pcDesc)
                        == classWideCalls(classNodeFromJar(jar, ntaCls), Opcodes.INVOKEVIRTUAL, luaCaller,
                                "protectedCall", pcDesc) - 1
                && classWideCalls(classNode(distJava, ntaCls), Opcodes.INVOKESTATIC, ntaGuardCls,
                        "protectedCall", pcHelperDesc) == 1);
        failed += check("W10 負對照：NetTimedActionPacket 全 class write 恰少 2、改道恰 2",
                classWideCalls(classNode(distJava, ntaPktCls), Opcodes.INVOKEVIRTUAL, ntaPktCls, "write", bbwDesc)
                        == classWideCalls(classNodeFromJar(jar, ntaPktCls), Opcodes.INVOKEVIRTUAL, ntaPktCls,
                                "write", bbwDesc) - 2
                && classWideCalls(classNode(distJava, ntaPktCls), Opcodes.INVOKESTATIC, ntaGuardCls,
                        "write", writeHelperDesc) == 2);

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
        // 詳見 docs/patches.md 2ad；復活方式：從退役前最後一版 1e637fc 取回（`git checkout 1e637fc -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

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
        String cipCls = "zombie/mdc/ContainerIdProbe";
        String cidCls = "zombie/network/fields/ContainerID";
        String scpCls = "zombie/network/packets/SyncClothingPacket";
        String idCls = "zombie/network/packets/SyncClothingPacket$ItemDescription";
        String svpCls = "zombie/network/packets/SyncVisualsPacket";
        String ivCls = "zombie/core/skinnedmodel/visual/ItemVisual";
        String w20Ic = "zombie/core/ImmutableColor";
        String pidCls = "zombie/network/fields/character/PlayerID";
        String cidSetDesc = "(Lzombie/inventory/ItemContainer;Lzombie/iso/IsoObject;)V";
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
        // vanilla 前提 (a)：雙參 set 直讀 raw square（GETFIELD square=6：頭部守衛塊 4＋
        // ObjectContainer/IsoObject 分支各 1；零 getSquare()）且 getObjects 恰 2（NPE 點）；
        // 單參 set 呼叫雙參恰 1（stack 兩層 set 的結構）。
        MethodNode vCidSet2 = methodFromJar(jar, cidCls, "set", cidSetDesc);
        MethodNode vCidSet1 = methodFromJar(jar, cidCls, "set", "(Lzombie/inventory/ItemContainer;)V");
        failed += check("W20 vanilla (a)：雙參 set raw square=6／getSquare=0／getObjects=2；單參呼叫雙參=1",
                countExactFields(vCidSet2, Opcodes.GETFIELD, "zombie/iso/IsoObject", "square",
                        "Lzombie/iso/IsoGridSquare;") == 6
                && countExactCalls(vCidSet2, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoObject",
                        "getSquare", "()Lzombie/iso/IsoGridSquare;") == 0
                && countExactCalls(vCidSet2, Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoGridSquare",
                        "getObjects", "()Lzombie/util/list/PZArrayList;") == 2
                && countExactCalls(vCidSet1, Opcodes.INVOKEVIRTUAL, cidCls, "set", cidSetDesc) == 1);
        // 手術後：三個 headCall（含多 slot 首用）全序＋redirect 歸零／真指令數對帳。
        MethodNode pCidSet2 = method(distJava, cidCls, "set", cidSetDesc);
        failed += check("W20 手術後 (a)：雙參 set 頭部 aload_1→aload_2→onSet、真指令恰 +3",
                headCallSlotsOk(pCidSet2, cipCls, "onSet", cidSetDesc, 1, 2)
                && realInsnCount(pCidSet2) == realInsnCount(vCidSet2) + 3);
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
        // （唯一出口，off/observe 同一 sink）；parsePlayer 的 getPlayer 委派恰 1；
        // ContainerIdProbe.onSet 純觀測（getStackTrace 恰 1、零 KahluaTable 觸碰由 import 面保證）。
        MethodNode gTintOf = method(distJava, csgCls, "tintOf", "(L" + ivCls + ";)L" + w20Ic + ";");
        MethodNode gMismatch = method(distJava, csgCls, "onVisualsMismatch",
                "(Lzombie/debug/DebugType;Ljava/lang/Object;)V");
        MethodNode gParsePlayer = method(distJava, csgCls, "parsePlayer",
                "(L" + pidCls + ";)Lzombie/characters/IsoPlayer;");
        MethodNode gOnSet = method(distJava, cipCls, "onSet", cidSetDesc);
        failed += check("W20 helper 契約：tintOf 委派2/white 引用2；mismatch error 出口1；parsePlayer 委派1；onSet getStackTrace 1",
                countExactCalls(gTintOf, Opcodes.INVOKEVIRTUAL, ivCls, "getTint", "()L" + w20Ic + ";") == 2
                && countExactFields(gTintOf, Opcodes.GETSTATIC, w20Ic, "white", "L" + w20Ic + ";") == 2
                && countExactCalls(gMismatch, Opcodes.INVOKEVIRTUAL, "zombie/debug/DebugType",
                        "error", "(Ljava/lang/Object;)V") == 1
                && countExactCalls(gParsePlayer, Opcodes.INVOKEVIRTUAL, pidCls, "getPlayer", getPlayerDesc) == 1
                && countExactCalls(gOnSet, Opcodes.INVOKEVIRTUAL, "java/lang/Thread",
                        "getStackTrace", "()[Ljava/lang/StackTraceElement;") == 1);

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

        // ---- 抑噪 #9：IsoObject.syncIsoObject 的兩個 System.out.println 改道 ----
        String ioCls = "zombie/iso/IsoObject";
        String syncDesc = "(ZBLzombie/core/raknet/UdpConnection;Lzombie/core/network/ByteBufferReader;)V";
        String printlnDesc = "(Ljava/lang/String;)V";
        String filterPrintlnDesc = "(Ljava/io/PrintStream;Ljava/lang/String;)V";
        MethodNode vSync = methodFromJar(jar, ioCls, "syncIsoObject", syncDesc);
        // vanilla 前提：恰 2 個 println(String)、恰 1 個 getObjectIndex（not-found 分支的判定源）、
        // 封包段存在（send ≥1）——TIS 把 println 換成 DebugLog 或加第三句時建置紅，重驗語境。
        failed += check("抑噪#9 vanilla 前提：syncIsoObject println(String)=2、getObjectIndex=1、封包段存在",
                countExactCalls(vSync, Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", printlnDesc) == 2
                && countExactCalls(vSync, Opcodes.INVOKEVIRTUAL, ioCls, "getObjectIndex", "()I") == 1
                && countCalls(vSync, "zombie/network/PacketTypes$PacketType", "send") >= 1);
        MethodNode pSync = method(distJava, ioCls, "syncIsoObject", syncDesc);
        failed += check("抑噪#9 手術後：println 改道 x2、原 println 歸零、封包段與真指令數未變",
                countExactCalls(pSync, Opcodes.INVOKESTATIC, "zombie/mdc/LogFilter", "println", filterPrintlnDesc) == 2
                && countExactCalls(pSync, Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", printlnDesc) == 0
                && countCalls(pSync, "zombie/network/PacketTypes$PacketType", "send")
                        == countCalls(vSync, "zombie/network/PacketTypes$PacketType", "send")
                && countCalls(pSync, "zombie/network/PacketTypes$PacketType", "doPacket")
                        == countCalls(vSync, "zombie/network/PacketTypes$PacketType", "doPacket")
                && realInsnCount(pSync) == realInsnCount(vSync));
        // 負對照：IsoObject 其餘方法的 println（含 Object 多載）一律 vanilla；method-scope 鎖。
        ClassNode vIo = classNodeFromJar(jar, ioCls);
        ClassNode pIo = classNode(distJava, ioCls);
        failed += check("抑噪#9 負對照：IsoObject 其餘 println 保持 vanilla（class-wide 改道恰 2）",
                classWideCalls(pIo, Opcodes.INVOKESTATIC, "zombie/mdc/LogFilter", "println", filterPrintlnDesc) == 2
                && classWideCalls(pIo, Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", printlnDesc)
                        == classWideCalls(vIo, Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", printlnDesc) - 2);
        // helper 契約：println 恰一個 PrintStream.println 出口（不翻倍、不換 sink）。
        MethodNode gPrintln = method(distJava, "zombie/mdc/LogFilter", "println", filterPrintlnDesc);
        failed += check("抑噪#9 helper 契約：LogFilter.println 委派 PrintStream.println 恰 1",
                countExactCalls(gPrintln, Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", printlnDesc) == 1);

        // ---- W22 面向物件 sprite-grid null 守衛（IsoGameCharacter.faceThisObject）----
        String fogCls = "zombie/mdc/FaceObjectGuard";
        String faceDesc = "(Lzombie/iso/IsoObject;)V";
        String closestDesc = "(FF)Lzombie/iso/IsoObject;";
        String fogDesc = "(Lzombie/iso/IsoObject;FF)Lzombie/iso/IsoObject;";
        MethodNode vFace = methodFromJar(jar, igcCls, "faceThisObject", faceDesc);
        MethodNode vFaceAlt = methodFromJar(jar, igcCls, "faceThisObjectAlt", faceDesc);
        // vanilla 前提：faceThisObject 內 getClosestSpriteGridObject 恰 1 且緊接 astore_1 →
        // aload_1 → getFacingPosition（「無條件解參考」的結構事實＝本刀存在理由；TIS 補 null
        // 檢查時 IFNULL/IFNONNULL 數會變，建置紅提醒撤刀）；faceThisObjectAlt 另 1（負對照）。
        failed += check("W22 vanilla 前提：faceThisObject getClosestSpriteGridObject=1、getFacingPosition(Vector2)=1、IFNULL/IFNONNULL 合計 5；Alt 另 1",
                countExactCalls(vFace, Opcodes.INVOKEVIRTUAL, ioCls, "getClosestSpriteGridObject", closestDesc) == 1
                && countExactCalls(vFace, Opcodes.INVOKEVIRTUAL, ioCls, "getFacingPosition",
                        "(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;") == 1
                && countOpcode(vFace, Opcodes.IFNULL) + countOpcode(vFace, Opcodes.IFNONNULL) == 5
                && countExactCalls(vFaceAlt, Opcodes.INVOKEVIRTUAL, ioCls, "getClosestSpriteGridObject", closestDesc) == 1
                && classWideCalls(vIgcNode, Opcodes.INVOKEVIRTUAL, ioCls, "getClosestSpriteGridObject", closestDesc) == 2);
        failed += check("W22 vanilla 前提：closest 結果緊接 astore_1→aload_1→getFacingPosition（無條件解參考）",
                callFollowedByStoreLoadCall(vFace, ioCls, "getClosestSpriteGridObject", closestDesc,
                        ioCls, "getFacingPosition"));
        MethodNode pFace = method(distJava, igcCls, "faceThisObject", faceDesc);
        MethodNode pFaceAlt = method(distJava, igcCls, "faceThisObjectAlt", faceDesc);
        failed += check("W22 手術後：faceThisObject 改道 x1、原呼叫歸零、真指令不變；Alt 未動；class-wide 改道恰 1",
                countExactCalls(pFace, Opcodes.INVOKESTATIC, fogCls, "closestSpriteGridObject", fogDesc) == 1
                && countExactCalls(pFace, Opcodes.INVOKEVIRTUAL, ioCls, "getClosestSpriteGridObject", closestDesc) == 0
                && realInsnCount(pFace) == realInsnCount(vFace)
                && countExactCalls(pFaceAlt, Opcodes.INVOKEVIRTUAL, ioCls, "getClosestSpriteGridObject", closestDesc) == 1
                && realInsnCount(pFaceAlt) == realInsnCount(vFaceAlt)
                && classWideCalls(pIgcNode, Opcodes.INVOKESTATIC, fogCls, "closestSpriteGridObject", fogDesc) == 1);
        // helper 契約：恰一次 vanilla 委派、零 NEW（熱路徑零配置；診斷路徑的字串拼接是 indy 不是 NEW）。
        MethodNode gClosest = method(distJava, fogCls, "closestSpriteGridObject", fogDesc);
        failed += check("W22 helper 契約：closestSpriteGridObject 委派 vanilla 恰 1、零 NEW、零 DebugLog（診斷在獨立方法）",
                countExactCalls(gClosest, Opcodes.INVOKEVIRTUAL, ioCls, "getClosestSpriteGridObject", closestDesc) == 1
                && countOpcode(gClosest, Opcodes.NEW) == 0
                && countCallsToOwner(gClosest, "zombie/debug/DebugLog") == 0);

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
        MethodNode vRcp = methodFromJar(jar, wsCls, "receiveChunkPart", bbrDesc);
        failed += check("receiveChunkPart 原體保留（sentRequests 觸碰數未變＝head-call 未破壞原邏輯）",
                countFieldTouches(pRcp, wsCls, "sentRequests")
                == countFieldTouches(vRcp, wsCls, "sentRequests"));
        // helper 反射依賴的八個私有欄位契約：名稱＋descriptor 逐一鎖進建置期
        //（漂移時 helper 會 fail-quiet 降級僅計數——這道守門把「默默降級」變成建置失敗）
        ClassNode vWs = classNodeFromJar(jar, wsCls);
        failed += check("ChunkStream 反射欄位契約（8 欄位名稱＋型別）",
                hasField(vWs, "pendingRequests", "Ljava/util/ArrayList;")
                && hasField(vWs, "pendingRequests1", "Ljava/util/ArrayList;")
                && hasField(vWs, "chunkRequests0", "Ljava/util/concurrent/ConcurrentLinkedQueue;")
                && hasField(vWs, "chunkRequests1", "Ljava/util/ArrayList;")
                && hasField(vWs, "sentRequests", "Ljava/util/concurrent/ConcurrentLinkedQueue;")
                && hasField(vWs, "requestingLargeArea", "Z")
                && hasField(vWs, "largeAreaDownloads", "I")
                && hasField(vWs, "requestNumber", "I"));
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

    /** GETFIELD 版欄位讀取計數（countFieldReads 是 GETSTATIC 版；W9 的兩顆共用 CRC32 都是 instance 欄位）。 */
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

    /** 全 jar 欄位讀取普查（opcode 指定 GETFIELD／GETSTATIC）——W9 耦合鎖的 fail-closed 版。 */
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

    /**
     * 同形替換緊鄰性：GETFIELD owner.name 之後<b>緊接</b> INVOKESTATIC helperOwner.helperName
     * 的配對數（W9）。FieldGetSwap 的插入語意就是緊鄰——中間隔任何指令＝helper 吃錯堆疊值。
     */
    static int swapAdjacency(MethodNode m, String owner, String name, String helperOwner, String helperName) {
        int count = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                    && fi.owner.equals(owner) && fi.name.equals(name)) {
                AbstractInsnNode next = nextReal(in);
                if (next instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(helperOwner) && mi.name.equals(helperName)) {
                    count++;
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

    /**
     * W22 語境錨：{@code call} 之後的真指令序列必須是 ASTORE s → ALOAD s →（一條 ALOAD，
     * Vector2 參數）→ INVOKEVIRTUAL nextOwner.nextName——「結果存回同 slot 後立刻無條件解參考」。
     * 任何一步不符（TIS 插入 null 檢查、換 slot、改成直接鏈式呼叫）都回 false 讓建置紅。
     */
    static boolean callFollowedByStoreLoadCall(MethodNode m, String owner, String name, String desc,
                                               String nextOwner, String nextName) {
        MethodInsnNode call = findExactCall(m, Opcodes.INVOKEVIRTUAL, owner, name, desc);
        if (call == null) {
            return false;
        }
        AbstractInsnNode store = nextReal(call);
        if (!(store instanceof VarInsnNode s) || s.getOpcode() != Opcodes.ASTORE) {
            return false;
        }
        AbstractInsnNode load = nextReal(store);
        if (!(load instanceof VarInsnNode l) || l.getOpcode() != Opcodes.ALOAD || l.var != s.var) {
            return false;
        }
        AbstractInsnNode arg = nextReal(load);
        if (!(arg instanceof VarInsnNode a) || a.getOpcode() != Opcodes.ALOAD) {
            return false;
        }
        AbstractInsnNode next = nextReal(arg);
        return next instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && mi.owner.equals(nextOwner) && mi.name.equals(nextName);
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
