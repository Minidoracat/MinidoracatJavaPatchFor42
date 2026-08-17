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
            // 私有池行為：租→還→再租重用同殼同 buffer、歸還後 bb=null，
            // 且全程不動 ClientChunkRequest 全域池（隔離的定義本身）。
            // 42.20.3 起 vanilla 刪除重試機制（Chunk.retriesCount／MAX_CHUNK_SEND_TRIES／
            // getRetryChunk 全移除），fresh shell 的欄位預設值等價命題只剩 bb=null。
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
            gbM.invoke(null, new Object[]{null, pc2});
            // codex 對抗審查修正：殼不入池（fresh shell）——vanilla update() 的無同步
            // savedChunks 可雙重 release，入池殼會被二次出租；buffer 則重用
            failed += check("W9 私有池：殼每次全新（不入池）、buffer 重用、歸還 null bb",
                    pb1 != null && bbNulled && pc2 != pc1
                    && bbField.get(pc2) == pb1);
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

        // ---- W4-1 chunk 供給併包（PlayerDownloadServer.update headCall）----
        String pdsCls = "zombie/network/PlayerDownloadServer";
        String packerCls = "zombie/mdc/ChunkRequestPacker";
        String packerDesc = "(Lzombie/network/PlayerDownloadServer;)V";
        MethodNode vPdsUpdate = methodFromJar(jar, pdsCls, "update", "()V");
        // vanilla 前提：三個同簽名 List.remove(I)（1 個 ccrWaiting、2 個 ccr.chunks）——
        // 正因無法以 owner/name/desc 區分才選 headCall 而非 redirect；數量漂移＝重新分析
        failed += check("vanilla 前提：update 有 3 個 List.remove(I)、1 個 removeOlderDuplicateRequests、無既存 packer 呼叫",
                countExactCalls(vPdsUpdate, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;") == 3
                && countExactCalls(vPdsUpdate, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "removeOlderDuplicateRequests", "()V") == 1
                && countExactCalls(vPdsUpdate, Opcodes.INVOKESTATIC, packerCls, "packQueue", packerDesc) == 0);
        // **掛點必須在 ready 閘內**：update() 頭部（閘外）會與 WorkerThread.sendArray 同時
        // 改 ccrWaiting。以下兩條把「掛在 removeOlderDuplicateRequests、且 update() 內零 packer
        // 呼叫」鎖進建置期——重打包導致掛點漂移會建置失敗而非靜默回到 race。
        MethodNode pPdsUpdate = method(distJava, pdsCls, "update", "()V");
        MethodNode pPdsDedupe = method(distJava, pdsCls, "removeOlderDuplicateRequests", "()V");
        failed += check("W4-1 掛點在 ready 閘內（dedupe 頭部全序 aload_0→packQueue，且 update 內零 packer 呼叫）",
                headCallOk(pPdsDedupe, packerCls, "packQueue", packerDesc)
                && countExactCalls(pPdsUpdate, Opcodes.INVOKESTATIC, packerCls, "packQueue", packerDesc) == 0);
        failed += check("W4-1 原體保留（update 的三個 List.remove(I) 與 dedupe 呼叫數未變）",
                countExactCalls(pPdsUpdate, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;") == 3
                && countExactCalls(pPdsUpdate, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "removeOlderDuplicateRequests", "()V") == 1);
        // dedupe 原體保留：vanilla 的空 ccr 回收（我們依賴它）與去重掃描未被破壞
        MethodNode vPdsDedupe = methodFromJar(jar, pdsCls, "removeOlderDuplicateRequests", "()V");
        failed += check("W4-1 dedupe 原體保留（List.remove(I) 與 cancelDuplicateChunk 呼叫數未變）",
                countExactCalls(pPdsDedupe, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;")
                == countExactCalls(vPdsDedupe, Opcodes.INVOKEINTERFACE, "java/util/List",
                        "remove", "(I)Ljava/lang/Object;")
                && countExactCalls(pPdsDedupe, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "cancelDuplicateChunk", "(Lzombie/network/ClientChunkRequest;II)Z")
                == countExactCalls(vPdsDedupe, Opcodes.INVOKEVIRTUAL, pdsCls,
                        "cancelDuplicateChunk", "(Lzombie/network/ClientChunkRequest;II)Z"));
        // helper 依賴的三個 public 成員契約（漂移＝建置失敗而非上線 IllegalAccessError）
        ClassNode vPds = classNodeFromJar(jar, pdsCls);
        ClassNode vCcr = classNodeFromJar(jar, "zombie/network/ClientChunkRequest");
        failed += check("W4-1 欄位契約：ccrWaiting/chunks/largeArea 皆 public 且型別未變",
                hasField(vPds, "ccrWaiting", "Ljava/util/List;")
                && hasField(vCcr, "chunks", "Ljava/util/List;")
                && hasField(vCcr, "largeArea", "Z"));
        failed += check("PatchInfo 版本指紋已生成且四個常數非空（server）",
                patchInfoOk(distJava, "server"));
        // 批次上限必須綁回 vanilla 自己的 isChunksFilled 門檻（TIS 調小而我們沒跟＝超發）
        failed += check("W4-1 vanilla 批次上限仍為 20（isChunksFilled 的 bipush）",
                countIntConst(methodFromJar(jar, "zombie/network/ClientChunkRequest",
                        "isChunksFilled", "()Z"), 20) == 1);

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

        // ---- 食材重量記憶化：InventoryItem.getExtraItemsWeight 的 CreateItem 改道 ----
        String iiCls = "zombie/inventory/InventoryItem";
        String iifCls = "zombie/inventory/InventoryItemFactory";
        String memoCls = "zombie/mdc/ItemWeightMemo";
        String createDesc = "(Ljava/lang/String;)L" + iiCls + ";";
        MethodNode vExtra = methodFromJar(jar, iiCls, "getExtraItemsWeight", "()F");
        // vanilla 語境指紋——這才是「共用實例安全」論證的實際錨點：factory 結果存進
        // 區域變數後，只被 IFNULL 與兩次 getActualWeight() 讀取，從不逃逸。
        // PZ 若改寫此方法（例如把結果放進 list），全序不符即建置失敗，而非讓共用實例外洩。
        failed += check("記憶化 vanilla 前提：getExtraItemsWeight 恰一個 CreateItem(String)＋兩個 getActualWeight()＋零逃逸",
                countExactCalls(vExtra, Opcodes.INVOKESTATIC, iifCls, "CreateItem", createDesc) == 1
                && countExactCalls(vExtra, Opcodes.INVOKEVIRTUAL, iiCls, "getActualWeight", "()F") == 2
                && extraWeightNoEscape(vExtra, iifCls, createDesc, iiCls));
        MethodNode pExtra = method(distJava, iiCls, "getExtraItemsWeight", "()F");
        failed += check("記憶化手術後：改道 x1、原 CreateItem 歸零、指令總數未變（1:1 替換）",
                countExactCalls(pExtra, Opcodes.INVOKESTATIC, memoCls, "createItem", createDesc) == 1
                && countExactCalls(pExtra, Opcodes.INVOKESTATIC, iifCls, "CreateItem", createDesc) == 0
                && realInsnCount(pExtra) == realInsnCount(vExtra));
        // 負對照：InventoryItem 另外四個 CreateItem(String) 必須維持原版。
        // createCloneItem 尤其致命——回傳共用實例會讓所有 clone 指向同一物件。
        ClassNode vIi = classNodeFromJar(jar, iiCls);
        ClassNode pIi = classNode(distJava, iiCls);
        int vIiCreate = classWideCalls(vIi, Opcodes.INVOKESTATIC, iifCls, "CreateItem", createDesc);
        boolean cloneClean = countExactCalls(method(distJava, iiCls, "createCloneItem", "()L" + iiCls + ";"),
                Opcodes.INVOKESTATIC, iifCls, "CreateItem", createDesc) == 1
                && countExactCalls(method(distJava, iiCls, "createCloneItem", "()L" + iiCls + ";"),
                        Opcodes.INVOKESTATIC, memoCls, "createItem", createDesc) == 0;
        failed += check("記憶化負對照：全 class CreateItem(String) 5→4 保持 vanilla，createCloneItem 未被動到",
                vIiCreate == 5
                && classWideCalls(pIi, Opcodes.INVOKESTATIC, iifCls, "CreateItem", createDesc) == vIiCreate - 1
                && classWideCalls(pIi, Opcodes.INVOKESTATIC, memoCls, "createItem", createDesc) == 1
                && cloneClean);
        // helper 契約三條：
        //  (1) factory 委派恰 2 處（off 純轉發＋phase 2），沒有第三處＝不存在 fail-open 重試；
        //  (2) 那兩處**都不在任何 try-catch 的保護範圍內**——這才是「factory 例外原樣外傳」的
        //      精準結構鎖。三份 review 同時定罪的缺陷正是「跨越 factory 的 catch 觸發重試」，
        //      而行為測試無法在無 ScriptManager 的環境讓 factory 拋例外，只有這條擋得住回歸；
        //  (3) 四道門的三個 script 判定各恰一次，且 MOVEABLE 那道門讀的真的是
        //      ItemType.MOVEABLE（只數 isItemType 次數的話，改成任何 enum 都會通過）。
        MethodNode memoCreate = method(distJava, memoCls, "createItem", "(Ljava/lang/String;)L" + iiCls + ";");
        failed += check("記憶化 helper 契約：factory 委派恰 2 處（off 純轉發＋phase 2），無第三處重試路徑",
                countExactCalls(memoCreate, Opcodes.INVOKESTATIC, iifCls, "CreateItem", createDesc) == 2);
        failed += check("記憶化 helper 契約：兩個 factory 委派都不在 try-catch 範圍內（例外必須原樣外傳）",
                callsInsideTryRange(memoCreate, Opcodes.INVOKESTATIC, iifCls, "CreateItem", createDesc) == 0);
        // (4) 正式路徑的兩條契約——測試環境的 ScriptManager 沒有任何 item，factory 恆回 null，
        //     所以「回傳的是 factory 結果」與「on 的 miss 真的會 store」無法用行為測試鎖住
        //     （codex 第二輪 Major 2）。這裡用結構鎖補：
        //     a. createItem 內恰一個 store 呼叫——刪掉 phase 3 的 store 就紅；
        //     b. createItem 內恰一個 noteObserved 呼叫——observe 記帳被拔掉就紅；
        //     c. 沒有任何 ACONST_NULL 緊接 ARETURN——把 `return fresh` 改成 `return null` 就紅。
        String storeDesc = "(Ljava/lang/String;L" + iiCls + ";)V";
        failed += check("記憶化 helper 契約：createItem 內 store 恰 1 次、noteObserved 恰 1 次",
                countExactCalls(memoCreate, Opcodes.INVOKESTATIC, memoCls, "store", storeDesc) == 1
                && countExactCalls(memoCreate, Opcodes.INVOKESTATIC, memoCls, "noteObserved",
                        "(Ljava/lang/String;L" + iiCls + ";Z)V") == 1);
        failed += check("記憶化 helper 契約：createItem 沒有 return null（回傳值必須來自 factory 或快取）",
                !hasNullReturn(memoCreate));
        MethodNode memoGate = method(distJava, memoCls, "cacheable", "(L" + iiCls + ";)Z");
        failed += check("記憶化五道門：scriptItem／getLuaCreate／getItemConfig／isItemType(MOVEABLE)／hasComponents 各恰一次",
                countExactCalls(memoGate, Opcodes.INVOKEVIRTUAL, iiCls, "getScriptItem",
                        "()Lzombie/scripting/objects/Item;") == 1
                && countCalls(memoGate, "zombie/scripting/objects/Item", "getLuaCreate") == 1
                && countCalls(memoGate, "zombie/scripting/objects/Item", "getItemConfig") == 1
                && countCalls(memoGate, "zombie/scripting/objects/Item", "isItemType") == 1
                && countCalls(memoGate, "zombie/scripting/objects/Item", "hasComponents") == 1
                && countFieldReads(memoGate, "zombie/scripting/objects/ItemType", "MOVEABLE") == 1);

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

        // ---- v2.1 chunk 串流觀測（WorldStreamer 三 headCall）----
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
        failed += check("ChunkStream 三個 head-call 全序（aload_0→helper 恰一次）",
                headCallOk(pUm, csoCls, "onUpdateMain", csoDesc)
                && headCallOk(pRcp, csoCls, "onReceiveChunkPart", csoDesc)
                && headCallOk(pRnr, csoCls, "onReceiveNotRequired", csoDesc));
        failed += check("PatchInfo 版本指紋已生成且四個常數非空（client）",
                patchInfoOk(distJava, "client"));
        // W4-2 chunk 請求逾時 8s→15s（全 class 僅一處 8000L）
        MethodNode vResend = methodFromJar(jar, wsCls, "resendTimedOutRequests", "()V");
        failed += check("vanilla 前提：resendTimedOutRequests 恰一個 8000L、零個 15000L",
                countLongConst(vResend, 8000L) == 1 && countLongConst(vResend, 15000L) == 0);
        MethodNode pResend = method(distJava, wsCls, "resendTimedOutRequests", "()V");
        failed += check("W4-2 逾時常數已換（8000L 歸零、15000L 恰一個）",
                countLongConst(pResend, 8000L) == 0 && countLongConst(pResend, 15000L) == 1);
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

    /**
     * 方法內是否存在「{@code ACONST_NULL} 緊接 {@code ARETURN}」。
     * 用於鎖住「回傳值必須來自原版 factory 或快取」——把 {@code return fresh} 改成
     * {@code return null} 這種 mutation，在 factory 恆回 null 的測試環境完全測不出來
     * （codex 第二輪 Major 2），只有結構鎖擋得住。
     */
    static boolean hasNullReturn(MethodNode m) {
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() != Opcodes.ACONST_NULL) {
                continue;
            }
            AbstractInsnNode next = in.getNext();
            while (next != null && next.getOpcode() < 0) {
                next = next.getNext();
            }
            if (next != null && next.getOpcode() == Opcodes.ARETURN) {
                return true;
            }
        }
        return false;
    }

    /**
     * 記憶化的共用實例安全性指紋：{@code getExtraItemsWeight} 內 factory 的結果必須
     * 立刻存進區域變數（ASTORE），且該 slot <b>只</b>被 ALOAD 讀出來做 IFNULL 或
     * {@code getActualWeight()}——沒有 PUTFIELD／ARETURN／任何其他呼叫的引數位置。
     * 這正是「共用實例不逃逸」論證的實際錨；PZ 若改寫成把結果放進 list 或存成欄位，
     * 這條會失敗而不是讓共用實例外洩。
     */
    static boolean extraWeightNoEscape(MethodNode m, String factoryOwner, String factoryDesc,
                                       String itemOwner) {
        MethodInsnNode factory = findExactCall(m, Opcodes.INVOKESTATIC, factoryOwner, "CreateItem", factoryDesc);
        if (factory == null) {
            return false;
        }
        AbstractInsnNode next = factory.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
            return false;
        }
        int slot = store.var;
        // 分開計數：只算「三次 ALOAD」不足以鎖住語境——三次 IFNULL 加上別處兩個 weight call
        // 也會通過。要求 1 個寫入、1 個 null 檢查、2 個「對受測 class 自己」的 getActualWeight。
        int stores = 0;
        int nullChecks = 0;
        int weightReads = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (!(in instanceof VarInsnNode v) || v.var != slot) {
                continue;
            }
            if (v.getOpcode() == Opcodes.ASTORE) {
                stores++;
                continue;
            }
            if (v.getOpcode() != Opcodes.ALOAD) {
                return false;           // 該 slot 被當成別的型別使用
            }
            AbstractInsnNode use = v.getNext();
            while (use != null && use.getOpcode() < 0) {
                use = use.getNext();
            }
            if (use != null && use.getOpcode() == Opcodes.IFNULL) {
                nullChecks++;
            } else if (use instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "getActualWeight".equals(mi.name) && "()F".equals(mi.desc)
                    && mi.owner.equals(itemOwner)) {
                weightReads++;
            } else {
                return false;           // 逃逸：被存欄位、回傳、或當成別的呼叫的引數
            }
        }
        return stores == 1 && nullChecks == 1 && weightReads == 2;
    }

    private SmokeCheck() {}
}
