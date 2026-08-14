package zombie.mdc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import zombie.ZomboidFileSystem;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoChunk;
import zombie.network.ChunkChecksum;

/**
 * W8 chunk 寫入閘（2026-08-14；CRC-blam 家族 43 筆資料損失的止血＋蒐證）。
 *
 * <p><b>問題</b>：正式服累計 43 個 chunk 因 {@code SANITY CHECK FAIL}（CRC／長度不符）
 * 在載入時被 vanilla 的 {@code Blam + LoadBrandNew} 抹除重生（8/14 單日 8 筆，Player-B 基地
 * 實案 28,401→5,470 bytes）。逐位元組鑑識定案：<b>載入側完全無辜</b>——43/43 筆 log 的
 * {@code load=} 等於對磁碟檔自算的 body CRC、{@code save=} 等於檔案 header 欄位；
 * 遊戲讀到的就是檔案裡的東西。壞的是<b>寫進磁碟的那一刻</b>：
 * <ul>
 *   <li>A 組 16 筆：header CRC=0、len 正確、body 完整自洽——被捕捉在 {@code Save()}
 *       尾端「回填 len」與「回填 crc」兩行之間的狀態；</li>
 *   <li>B 組 27 筆：header CRC 有值但屬於別份 body——buffer 在寫檔與重填之間被撕裂。</li>
 * </ul>
 *
 * <p><b>根因狀態：機制未定罪</b>。兩個假說已被自己的證據推翻並記錄於 docs/patches.md 2t
 * （載入側共用 static 競態＝43/43 對帳證偽；ChunkSaveWorker 池化 buffer＝簽名吻合但
 * hot-save 是 {@code !GameServer.server} 單機專屬）。現行首嫌是
 * {@code ClientChunkRequest.Chunk} 共用物件池的 pending-write 與重填競賽，但未逐行證實。
 * <b>本閘的設計正是為了不依賴根因</b>：所有 chunk 寫檔都收斂到
 * {@code IsoChunk.SafeWrite}，在唯一的橋上驗證，不論上游是誰弄髒的。
 *
 * <p><b>為什麼掛在呼叫端而非 SafeWrite 內部</b>：SafeWrite 的
 * {@code new FileOutputStream(outFile)} 在建構當下就 truncate 舊檔——內部任何攔截點
 * 都來不及「決定不寫」，舊的好檔案已經沒了。只有在進入 SafeWrite <b>之前</b>擋下，
 * 磁碟上一版才得以保留。
 *
 * <p><b>語意</b>（enforce 模式，預設）：
 * <ol>
 *   <li><b>快照</b>：把活 buffer 複製進執行緒私有陣列——驗證與寫入之間的 TOCTOU 由此關閉，
 *       verify 過的位元組就是寫進磁碟的位元組；</li>
 *   <li><b>驗證</b>：header len 欄位 == 實際長度，且 header CRC == body 自算 CRC
 *       （{@code Save()} 正常收尾時兩者必然成立——蓋指紋是它的最後兩行——故不符＝100% 上游損毀，
 *       零合法誤判空間）；</li>
 *   <li><b>通過</b> → 把驗證過的快照交給 vanilla {@code SafeWrite}（鎖、sanityCheck、目錄
 *       建立全走原版）；</li>
 *   <li><b>失敗</b> → <b>跳過寫入</b>（磁碟保留上一版好檔案）＋完整 stack log（兇手路徑
 *       直接寫在 log 裡＝蒐證）＋損毀 buffer 傾印到 {@code blamguard/}（供離線比對）＋
 *       {@code ChunkChecksum.setChecksum(wx,wy,0)} 邀請下個存檔週期用乾淨的序列化重寫（自癒重試）。</li>
 * </ol>
 *
 * <p><b>取捨（誠實記錄）</b>：跳過寫入代表該 chunk 磁碟版本暫時停留在上一版——若它在
 * 下次內容變更前一直閒置，磁碟就是舊資料。「舊而有效」勝過「被 Blam 全滅」；且 checksum
 * 歸零保證下次存檔週期必然重試。另一個閘門攔不住的殘餘情境：buffer 被<b>完整地</b>填成
 * 另一塊 chunk 的自洽資料（header 沒有座標欄位，CRC 驗不出「內容對但主人錯」）——
 * 已觀測的 43 筆全是不自洽型，全數會被本閘攔下。
 *
 * <p><b>不涵蓋的兩條寫檔路徑</b>（全 jar 共 5 個 SafeWrite 呼叫點，SmokeCheck census 釘死）：
 * {@code ChunkSaveWorker.WriteQueuedSave}——唯一入列點 {@code AddHotSave} 被
 * {@code !GameServer.server} 閘死（SmokeCheck pin 該閘），伺服器不走；
 * {@code WorldGenerate}——只寫「首次生成」的 chunk（method-local buffer 無池共用），
 * 就算寫壞，該塊本來就沒有玩家資料可失。
 *
 * <p><b>失敗紀律</b>：守衛自身故障（非預期 buffer 狀態、快照失敗）＝anomaly 計數＋
 * 回退 vanilla 寫入（fail-open）——只有「驗證明確失敗」才擋。verify 對 len≤17 的
 * header-only 寫入回 MALFORMED（空 body 的 CRC=0 會與 crc 欄位 0 假相符，必須先擋）。
 *
 * <p><b>Kill switch</b>：{@code -Dmdc.chunkWriteGuard=0} 完全停用（零開銷 passthrough）、
 * {@code =2} 觀察模式（照常驗證＋log＋傾印，但一律照 vanilla 寫入活 buffer）、
 * {@code =1}／未設＝enforce。成本：CRC32 為硬體加速，≤64KB 約 30µs/塊；
 * 最壞 200 塊/s 佔單核 &lt;1%。
 */
public final class ChunkWriteGuard {

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int VERIFY_OK = 0;
    static final int VERIFY_MALFORMED = 1;
    static final int VERIFY_LEN = 2;
    static final int VERIFY_CRC = 3;

    private static final int HEADER_SIZE = 17;   // byte isDebug + int ver + int len + long crc
    private static final int MAX_DETAIL_LOGS = 10;
    private static final int MAX_DUMPS = 16;

    private static final int MODE = resolveMode(System.getProperty("mdc.chunkWriteGuard"));

    private static final AtomicLong passed = new AtomicLong();
    private static final AtomicLong flagged = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();
    private static final AtomicInteger detailLogs = new AtomicInteger();
    private static final AtomicInteger dumps = new AtomicInteger();

    /** 寫檔執行緒有限（主執行緒＋loader/saver），每緒一份快照陣列與 CRC，無累積疑慮。 */
    private static final ThreadLocal<byte[]> SNAP = ThreadLocal.withInitial(() -> new byte[131072]);
    private static final ThreadLocal<CRC32> LOCAL_CRC = ThreadLocal.withInitial(CRC32::new);

    /** IsoChunk.Save(Z)V ×2 與 ServerChunkLoader$SaveLoadedTask.save()V ×1 的改道目標。 */
    public static void safeWrite(int wx, int wy, ByteBuffer bb) throws IOException {
        if (MODE == MODE_OFF) {
            IsoChunk.SafeWrite(wx, wy, bb);
            return;
        }
        byte[] snap;
        int len;
        int verdict;
        try {
            if (!bb.hasArray()) {
                throw new IllegalStateException("non-array buffer");
            }
            len = bb.position();
            snap = snapshotOf(bb, len);
            verdict = verify(snap, len);
        } catch (RuntimeException e) {
            anomalies.incrementAndGet();
            DebugType.General.printException(e,
                    "[MinidoracatJavaPatch][ChunkWriteGuard] anomaly at chunk " + wx + "," + wy
                            + " — fallback to vanilla write", LogSeverity.Error);
            IsoChunk.SafeWrite(wx, wy, bb);
            return;
        }
        if (verdict == VERIFY_OK) {
            long n = passed.incrementAndGet();
            if ((n & 0x7FF) == 0L) {
                heartbeat();
            }
            if (MODE == MODE_OBSERVE) {
                IsoChunk.SafeWrite(wx, wy, bb);
                return;
            }
            ByteBuffer verified = ByteBuffer.wrap(snap);
            verified.position(len);
            IsoChunk.SafeWrite(wx, wy, verified);
            return;
        }
        long b = flagged.incrementAndGet();
        logBlock(wx, wy, snap, len, verdict, b);
        dumpEvidence(wx, wy, snap, len);
        if (MODE == MODE_OBSERVE) {
            IsoChunk.SafeWrite(wx, wy, bb);
            return;
        }
        // 不寫入：磁碟保留上一版好檔案；checksum 歸零讓下個存檔週期必然重試乾淨序列化
        ChunkChecksum.setChecksum(wx, wy, 0L);
    }

    /**
     * 純函式：對快照驗證 header 自洽性。offset 是 42.20.2 格式的固定前綴
     * （[0] debug、[1,5) ver、[5,9) len、[9,17) crc、[17,len) body），與 worldVersion
     * 分支無關（那些都在 body 內）；SmokeCheck 以 Save() 的 249/17 常數 pin 防格式漂移。
     */
    static int verify(byte[] a, int len) {
        if (len <= HEADER_SIZE || len > a.length) {
            return VERIFY_MALFORMED;
        }
        int lenField = ((a[5] & 0xFF) << 24) | ((a[6] & 0xFF) << 16) | ((a[7] & 0xFF) << 8) | (a[8] & 0xFF);
        if (lenField != len) {
            return VERIFY_LEN;
        }
        long crcField = 0L;
        for (int i = 9; i < HEADER_SIZE; i++) {
            crcField = (crcField << 8) | (a[i] & 0xFF);
        }
        CRC32 c = LOCAL_CRC.get();
        c.reset();
        c.update(a, HEADER_SIZE, len - HEADER_SIZE);
        return crcField == c.getValue() ? VERIFY_OK : VERIFY_CRC;
    }

    /** 純函式：-Dmdc.chunkWriteGuard 解析。無法解析＝enforce（守護是預設任務）。 */
    static int resolveMode(String raw) {
        if (raw == null) {
            return MODE_ENFORCE;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return (v >= MODE_OFF && v <= MODE_OBSERVE) ? v : MODE_ENFORCE;
        } catch (NumberFormatException e) {
            return MODE_ENFORCE;
        }
    }

    private static byte[] snapshotOf(ByteBuffer bb, int len) {
        byte[] arr = SNAP.get();
        if (arr.length < len) {
            arr = new byte[Math.max(len, arr.length * 2)];
            SNAP.set(arr);
        }
        System.arraycopy(bb.array(), 0, arr, 0, len);
        return arr;
    }

    private static void logBlock(int wx, int wy, byte[] snap, int len, int verdict, long count) {
        String kind = switch (verdict) {
            case VERIFY_MALFORMED -> "MALFORMED";
            case VERIFY_LEN -> "LEN_MISMATCH";
            default -> "CRC_MISMATCH";
        };
        long lenField = len > HEADER_SIZE
                ? (((snap[5] & 0xFFL) << 24) | ((snap[6] & 0xFFL) << 16) | ((snap[7] & 0xFFL) << 8) | (snap[8] & 0xFFL))
                : -1;
        long crcField = -1;
        if (len > HEADER_SIZE) {
            crcField = 0L;
            for (int i = 9; i < HEADER_SIZE; i++) {
                crcField = (crcField << 8) | (snap[i] & 0xFF);
            }
        }
        String action = MODE == MODE_ENFORCE ? "BLOCKED" : "FLAGGED";   // observe 模式照常寫入，不得謊稱擋下
        String msg = "[MinidoracatJavaPatch][ChunkWriteGuard] " + action + " " + kind
                + " chunk=" + wx + "," + wy + " len=" + len + " lenField=" + lenField
                + " crcField=" + crcField + " mode=" + MODE
                + " flagged=" + count + " thread=" + Thread.currentThread().getName();
        if (detailLogs.incrementAndGet() <= MAX_DETAIL_LOGS) {
            // 兇手蒐證：這條 stack 直接指出是哪條路徑把髒 buffer 送來寫檔
            DebugType.General.printException(new Throwable("write-path capture"), msg, LogSeverity.Error);
        } else {
            DebugLog.log(msg);
        }
    }

    private static void dumpEvidence(int wx, int wy, byte[] snap, int len) {
        if (dumps.incrementAndGet() > MAX_DUMPS) {
            return;
        }
        try {
            File dir = ZomboidFileSystem.instance.getFileInCurrentSave("blamguard");
            File sub = new File(dir, String.valueOf(wx));
            sub.mkdirs();
            File out = new File(sub, wy + "-" + System.currentTimeMillis() + ".bin");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(snap, 0, Math.max(0, Math.min(len, snap.length)));
            }
        } catch (Exception e) {
            // 傾印是輔助蒐證，失敗不得影響主流程
            anomalies.incrementAndGet();
        }
    }

    private static void heartbeat() {
        DebugLog.log("[MinidoracatJavaPatch][ChunkWriteGuard] passed=" + passed.get()
                + " flagged=" + flagged.get() + " anomalies=" + anomalies.get() + " mode=" + MODE);
    }

    private ChunkWriteGuard() {}
}
