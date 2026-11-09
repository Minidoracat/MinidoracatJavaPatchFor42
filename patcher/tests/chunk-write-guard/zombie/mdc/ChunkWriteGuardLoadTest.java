package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import zombie.iso.IsoChunk;
import zombie.network.ChunkChecksum;

/**
 * W8 載入側（{@code ServerChunkLoader$LoaderThread.run} 的 {@code LoadChunk} 改道）行為測試，三個模式各跑一次
 * （{@code MODE} 是 static final）。argv[0]＝呼叫端期望的模式（off／observe／enforce），與 helper 實際讀到的不符就失敗，
 * property 名稱打錯不會把同一個模式跑三遍假綠。
 *
 * <p><b>為什麼不跑真 {@code IsoChunk.LoadChunk}</b>：{@code IsoChunk} 是 final（不能做替身子類），真 {@code LoadChunk}
 * 失敗路徑的 {@code BackupBlam} 會在存檔目錄的 {@code blam/} 寫錯誤檔並複製原檔、成功路徑要 {@code IsoWorld}／{@code ChunkMapFilenames}
 * 等世界狀態，測試 JVM 跑不起來，且會碰本機存檔（SmokeCheck 記過同類事故）。所以拆成兩半：{@code loadChunk} 只是
 * {@code chunk.LoadChunk(wx, wy, loadSource(wx, wy, pending))}（SmokeCheck 釘結構），這裡測 {@code loadSource} 決定交給原版的
 * buffer；原版負對照用原版自己的兩個判斷——載入時真正會拋的 {@code IsoChunk$SanityCheck.checkLength／checkCRC}，與同一判斷的
 * 純函式 {@code IsoChunk.validateByteBufferHeader}——證明被擋的 buffer 原版載入必然失敗（→ setChecksum(0)＋Blam＋LoadBrandNew）。
 */
public final class ChunkWriteGuardLoadTest {

    private static final int WX = 7001;
    private static final int WY = 7002;

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "用法：ChunkWriteGuardLoadTest off|observe|enforce");
        require(System.getProperty("mdc.chunkWriteGuard.dumpDir") != null,
                "必須設 -Dmdc.chunkWriteGuard.dumpDir（擋下時會傾印，不得寫進本機存檔目錄）");
        int want = switch (args[0]) {
            case "off" -> ChunkWriteGuard.MODE_OFF;
            case "observe" -> ChunkWriteGuard.MODE_OBSERVE;
            case "enforce" -> ChunkWriteGuard.MODE_ENFORCE;
            default -> throw new AssertionError("未知模式 " + args[0]);
        };
        require(ChunkWriteGuard.MODE == want, "模式與呼叫端期望不符——-Dmdc.chunkWriteGuard 沒生效？實得 " + ChunkWriteGuard.MODE);

        ByteBuffer good = pending(saved(new Random(42), 300));
        List<ByteBuffer> bad = corruptVariants(good);

        vanillaRejectsWhatGuardRejects(good, bad);
        verifyEqualsVanillaPredicate();

        long flaggedBefore = ChunkWriteGuard.pendingFlagged.get();
        // 正常 buffer：每個模式都原樣送進原版 LoadChunk，且 helper 不動它的 position／limit／內容
        byte[] goodBytes = Arrays.copyOf(good.array(), good.limit());
        require(ChunkWriteGuard.loadSource(WX, WY, good) == good, "正常 pending buffer 必須原樣送進 LoadChunk");
        require(good.position() == 0 && good.limit() == goodBytes.length
                && Arrays.equals(Arrays.copyOf(good.array(), good.limit()), goodBytes), "helper 不得改動 buffer");
        require(ChunkWriteGuard.loadSource(WX, WY, null) == null, "沒有 pending 資料（null）照原樣＝讀磁碟");

        for (int i = 0; i < bad.size(); i++) {
            ByteBuffer b = bad.get(i);
            int wx = WX + 10 + i;
            ChunkChecksum.setChecksum(wx, WY, 4242L);
            ByteBuffer out = ChunkWriteGuard.loadSource(wx, WY, b);
            long ck = ChunkChecksum.getChecksumIfExists(wx, WY);
            switch (want) {
                case ChunkWriteGuard.MODE_OFF -> require(out == b && ck == 4242L, "off＝原版：損毀 buffer 照傳、不動 checksum（#" + i + "）");
                case ChunkWriteGuard.MODE_OBSERVE -> require(out == b && ck == 4242L, "observe 只記錄：照傳、不動 checksum（#" + i + "）");
                default -> require(out == null && ck == 0L, "enforce：損毀 buffer 不得送進 LoadChunk（改以 null 讀磁碟）且 checksum 歸零（#" + i + "）");
            }
            require(b.position() == 0, "helper 不得改動損毀 buffer 的 position（#" + i + "）");
        }
        long flagged = ChunkWriteGuard.pendingFlagged.get() - flaggedBefore;
        require(flagged == (want == ChunkWriteGuard.MODE_OFF ? 0 : bad.size()),
                "pendingFlagged 計數不符：實得 " + flagged + "，損毀樣本 " + bad.size());

        System.out.println("chunk-write-guard load OK  mode=" + args[0] + "  原版負對照（SanityCheck 拋、validateByteBufferHeader=false）/"
                + "verify 與原版判斷逐筆等價（隨機突變）/正常 buffer 原樣/損毀 buffer " + bad.size() + " 種依模式處置/計數");
    }

    /** 依 Save(ByteBuffer,CRC32,Z) 的版面組一份收尾完成的 buffer：debug byte、版本 251、len、crc、body。 */
    static byte[] saved(Random r, int bodyLen) {
        ByteBuffer bb = ByteBuffer.allocate(17 + bodyLen + 64);   // 比內容大，像存檔池的 buffer
        bb.put((byte) 0).putInt(251).putInt(0).putLong(-1L);
        byte[] body = new byte[bodyLen];
        r.nextBytes(body);
        bb.put(body);
        int len = bb.position();
        CRC32 c = new CRC32();
        c.update(bb.array(), 17, len - 17);
        bb.putInt(5, len).putLong(9, c.getValue());
        return Arrays.copyOf(bb.array(), len);
    }

    /** 與原版 ServerChunkLoader.copyData 同形：ByteBuffer.wrap(Arrays.copyOf(array, position))。 */
    static ByteBuffer pending(byte[] saved) {
        return ByteBuffer.wrap(Arrays.copyOf(saved, saved.length));
    }

    /** 正式服見過的損毀型（docs/patches.md 2t）＋長度類。 */
    static List<ByteBuffer> corruptVariants(ByteBuffer good) {
        byte[] g = Arrays.copyOf(good.array(), good.limit());
        List<ByteBuffer> out = new ArrayList<>();
        byte[] a0 = g.clone();
        Arrays.fill(a0, 9, 17, (byte) 0);                  // A 組：crc 欄位 0（42.20）
        out.add(ByteBuffer.wrap(a0));
        byte[] a1 = g.clone();
        Arrays.fill(a1, 9, 17, (byte) 0xFF);               // A 組：crc 欄位 -1（42.21 起的佔位）
        out.add(ByteBuffer.wrap(a1));
        byte[] b = g.clone();
        b[100] ^= 0x5A;                                    // B 組：header 屬於別份 body
        out.add(ByteBuffer.wrap(b));
        byte[] l = g.clone();
        ByteBuffer.wrap(l).putInt(5, g.length + 4);        // len 欄位不符
        out.add(ByteBuffer.wrap(l));
        out.add(ByteBuffer.wrap(Arrays.copyOf(g, g.length - 9)));   // 截斷（len 欄位大於實際長度）
        return out;
    }

    /** 原版負對照：helper 拒絕的每一份，原版載入側的判斷也拒絕（載入時拋），正常那份原版放行。 */
    static void vanillaRejectsWhatGuardRejects(ByteBuffer good, List<ByteBuffer> bad) throws Exception {
        Field f = IsoChunk.class.getDeclaredField("sanityCheck");
        f.setAccessible(true);
        Object sc = f.get(null);
        Method checkLength = sc.getClass().getDeclaredMethod("checkLength", long.class, long.class);
        Method checkCrc = sc.getClass().getDeclaredMethod("checkCRC", long.class, long.class);
        checkLength.setAccessible(true);
        checkCrc.setAccessible(true);
        require(!vanillaLoadThrows(checkLength, checkCrc, sc, good) && IsoChunk.validateByteBufferHeader(good),
                "原版判斷必須放行正常 buffer（否則負對照沒有意義）");
        require(ChunkWriteGuard.verify(good.array(), good.limit()) == ChunkWriteGuard.VERIFY_OK, "verify 必須放行正常 buffer");
        for (int i = 0; i < bad.size(); i++) {
            ByteBuffer b = bad.get(i);
            require(ChunkWriteGuard.verify(b.array(), b.limit()) != ChunkWriteGuard.VERIFY_OK, "verify 必須拒絕損毀樣本 #" + i);
            require(vanillaLoadThrows(checkLength, checkCrc, sc, b), "原版 SanityCheck 對損毀樣本 #" + i + " 必須拋（載入失敗→Blam）");
            require(!IsoChunk.validateByteBufferHeader(b), "原版 validateByteBufferHeader 對損毀樣本 #" + i + " 必須為 false");
        }
    }

    /** 照 LoadFromDiskOrBufferInternal 的取值方式：len 欄位對 limit、crc 欄位對 array[17, limit) 的 CRC。 */
    static boolean vanillaLoadThrows(Method checkLength, Method checkCrc, Object sc, ByteBuffer bb) throws Exception {
        try {
            checkLength.invoke(sc, (long) bb.getInt(5), (long) bb.limit());
            CRC32 c = new CRC32();
            c.update(bb.array(), 17, bb.limit() - 17);
            checkCrc.invoke(sc, bb.getLong(9), c.getValue());
            return false;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) {
                return true;
            }
            throw e;
        }
    }

    /**
     * 誤判風險的證據：對隨機突變（header、body、長度）逐筆比較，verify 通過 ⇔ 原版 validateByteBufferHeader 通過
     * （limit &gt; 17；≤17 時 verify 一律 MALFORMED，原版讀 body 也會越界）。只要有一筆 verify 拒絕而原版放行，就是
     * 「把原版能載入的 pending 資料換成磁碟舊版」，失敗。
     */
    static void verifyEqualsVanillaPredicate() {
        Random r = new Random(7);
        int rejected = 0;
        for (int n = 0; n < 20000; n++) {
            byte[] s = saved(r, 18 + r.nextInt(600));
            switch (r.nextInt(5)) {
                case 0 -> { }                                                        // 原樣
                case 1 -> s[r.nextInt(17)] ^= (byte) (1 << r.nextInt(8));           // header 任一位元
                case 2 -> s[17 + r.nextInt(s.length - 17)] ^= (byte) (1 << r.nextInt(8));   // body 任一位元
                case 3 -> s = Arrays.copyOf(s, 18 + r.nextInt(s.length - 17));      // 截斷
                default -> s = Arrays.copyOf(s, s.length + 1 + r.nextInt(8));       // 尾端多出位元組
            }
            ByteBuffer bb = ByteBuffer.wrap(s);
            boolean guardOk = ChunkWriteGuard.verify(bb.array(), bb.limit()) == ChunkWriteGuard.VERIFY_OK;
            require(guardOk == IsoChunk.validateByteBufferHeader(bb), "verify 與原版判斷不一致（第 " + n + " 筆）");
            rejected += guardOk ? 0 : 1;
        }
        require(rejected > 10000 && rejected < 20000, "隨機樣本必須同時含放行與拒絕，實得拒絕 " + rejected);
    }

    private static void require(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError(msg);
        }
    }

    private ChunkWriteGuardLoadTest() {}
}
