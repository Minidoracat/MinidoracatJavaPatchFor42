package zombie.network.packets.sound;

import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.network.PacketTypes;

/**
 * WorldSoundPacket 派送觀測刀（純觀測，零遊戲行為改動）。放在
 * {@code zombie.network.packets.sound} 是因為 {@link WorldSoundPacket} 的 {@code radius}／
 * {@code volume} 是 package-private 欄位——同 package 才能零反射直讀（正式路徑<b>不用反射</b>）。
 *
 * <p>量測完整 {@code WorldSoundPacket.processServer}：建立聲音（含 Lua 事件與魚群格點掃描），
 * 然後依原版條件轉送給其他連線。魚群掃描成本隨半徑平方成長；本刀不把整段耗時直接歸因於
 * 魚群或廣播其中一項。量測分成：
 * <ul>
 *   <li><b>單次慢呼叫</b>：單個封包的 {@code processServer} 耗時 &ge; {@value #SLOW_MS}ms；</li>
 *   <li><b>單批累計</b>：兩次 {@link #onTick()} 之間（＝主迴圈一圈）所有 WorldSoundPacket
 *       的累計耗時 &ge; {@value #BATCH_MS}ms——單發都不慢但一圈塞了幾百發的情形只有這個看得到。</li>
 * </ul>
 * 只記錄原始 {@code radius}（不修改、不 clamp）、{@code volume}、批次筆數與耗時；
 * <b>不記玩家名稱、不記座標</b>。本刀不過濾、不延後、不丟棄任何音效。
 *
 * <p><b>批次語意</b>：{@code batchSeq} 是「本刀的觀測批次序號」＝{@link #onTick()} 被呼叫的次數，
 * <em>不是</em>遊戲本體的幀計數器（本刀不宣稱知道 vanilla 的 frame 號）。{@link #onTick()}
 * 在主迴圈掛點上每圈一次，負責結算<b>上一批</b>——所以最後一批不會因為之後再也沒有音效
 * 而卡著不結算，而且結算失敗（含 logger 自身爆掉）也保證在 finally 裡把批次累加器歸零。
 *
 * <p><b>巢套／外來執行緒</b>（outer-inclusive 語意）：owner 執行緒由第一次 {@link #onTick()}
 * 捕獲並終身不變。只有 owner 執行緒上<b>最外層</b>那次派送會計時，巢套派送的耗時歸給外層那筆
 * （不重複計，記在 {@code nested}）；owner 尚未捕獲前（{@code unarmed}）與其他執行緒
 * （{@code foreign}）的派送一律純直通、不計時、不動批次狀態。
 *
 * <p><b>例外紀律</b>：原版 {@code processServer} 恰好被呼叫一次，例外照常上拋；
 * 診斷的 RuntimeException 只累計 {@code logErrors}，不取代原版結果、永不重試原版。
 * 診斷 Error 刻意 fail-fast；若與原版例外同時發生，finally 的 Error 可能取代原例外。
 * 計時包含原版呼叫與少量發佈指令，不含後續記錄與寫 log。
 *
 * <p><b>log 預算</b>：慢呼叫與慢批次共用 {@value #WINDOW_CAP} 行／{@value #WINDOW_SECONDS}s 的
 * 明細額度（超出只累計 {@code suppressed}），與首次生效 banner。週期 heartbeat 已於 2026-09-27
 * 移除（9/23–9/26 共 40 個 session 零慢呼叫）；累計計數改由看門狗凍結快照的 {@link #describeActive()} 帶出。正常運轉零輸出。
 *
 * <p><b>凍結中取狀態</b>：{@link #describeActive()} 供看門狗的 stack dump 附掛，用 seq 旗標
 * ＋重讀校驗（volatile 發佈／取得）讀出「此刻正在處理的那個封包」的原始數據；只保存原生型別，
 * <b>不留 packet／connection 參考</b>，讀到不一致就回 {@code changed}。本刀不開任何背景執行緒。
 *
 * <p>kill switch：{@code -Dmdc.worldSoundProbe=0|off} 完全停用（processServer 純直通、
 * onTick 早退）；其餘值（含未設定）＝observe。正式路徑沒有任何測試用 setter。
 */
public final class MdcWorldSoundProbe {

    private static final String TAG = "[MinidoracatJavaPatch][WorldSoundProbe]";

    private static final int MODE_OFF = 0;
    private static final int MODE_OBSERVE = 1;

    private static final int MODE = parseMode();

    /** 單次呼叫慢門檻（ms，固定）。 */
    private static final long SLOW_MS = 100L;
    /** 單批累計慢門檻（ms，固定）。 */
    private static final long BATCH_MS = 100L;

    private static final long WINDOW_SECONDS = 60L;
    /** 慢呼叫＋慢批次共用的明細行額度。 */
    private static final int WINDOW_CAP = 3;
    private static final long WINDOW_NS = WINDOW_SECONDS * 1_000_000_000L;
    /** banner 寫失敗時最多重試幾圈（DebugLog 尚未就緒的情形）。 */
    private static final int BANNER_ATTEMPTS = 8;

    // ---- in-flight 發佈（owner 執行緒唯一 writer；任何執行緒可讀）----
    /** 0＝沒有正在處理的封包；非 0＝該次呼叫的序號。volatile 寫在資料欄位之後＝release。 */
    private static volatile long activeSeq;
    private static long activeStartNs;
    private static int activeRadius;
    private static int activeVolume;
    private static long activeBatchSeq;
    /** owner 執行緒私有的序號產生器。 */
    private static long nextSeq;

    /** 由第一次 {@link #onTick()} 捕獲後終身不變；null＝尚未武裝。 */
    private static volatile Thread ownerThread;

    // ---- 累計計數（owner 執行緒單 writer；跨執行緒讀取僅供診斷，容忍罕見過期值）----
    private static long calls;
    private static long slow;
    private static long failed;
    private static long totalNs;
    private static long maxNs;
    private static int maxRadius;
    private static long batches;
    private static long slowBatches;
    private static long nested;
    private static long logged;
    private static long suppressed;
    private static long logErrors;

    // ---- 目前批次（owner 執行緒單 writer；onTick 結算並歸零）----
    private static long batchSeq;
    private static long batchCalls;
    private static long batchNs;
    private static long batchMaxNs;
    private static int batchMaxRadius;

    // ---- log 節流／banner 狀態（owner 執行緒）----
    private static long windowStartNs;
    private static int windowCount;
    private static boolean bannerShown;
    private static int bannerTries;

    /** 非 owner 執行緒的派送次數（多 writer，故用 AtomicLong；遞增不配置物件）。 */
    private static final AtomicLong foreign = new AtomicLong();
    /** owner 尚未捕獲（第一次 onTick 之前）的派送次數。 */
    private static final AtomicLong unarmed = new AtomicLong();
    /** 非 owner 執行緒呼叫 onTick 的次數；恆應為 0。 */
    private static final AtomicLong foreignTicks = new AtomicLong();

    private MdcWorldSoundProbe() {
    }

    /**
     * WorldSoundPacket 的派送改道點：原版 {@code processServer} 恰呼叫一次，例外原樣上拋。
     * 只有 owner 執行緒的最外層呼叫會計時（outer-inclusive）。
     */
    public static void processServer(WorldSoundPacket packet, PacketTypes.PacketType packetType,
            UdpConnection connection) {
        if (MODE == MODE_OFF) {
            packet.processServer(packetType, connection);
            return;
        }
        Thread owner = ownerThread;
        if (owner == null) {
            unarmed.incrementAndGet();
            packet.processServer(packetType, connection);
            return;
        }
        if (owner != Thread.currentThread()) {
            foreign.incrementAndGet();
            packet.processServer(packetType, connection);
            return;
        }
        if (activeSeq != 0L) {
            // owner 執行緒上的巢套派送：耗時歸最外層那筆，避免重複計
            nested++;
            packet.processServer(packetType, connection);
            return;
        }

        int radius = packet.radius;
        int volume = packet.volume;
        long startNs = System.nanoTime();
        activeStartNs = startNs;
        activeRadius = radius;
        activeVolume = volume;
        activeBatchSeq = batchSeq;
        activeSeq = ++nextSeq;   // volatile 寫＝把上面的純欄位一併發佈出去
        boolean ok = false;
        try {
            packet.processServer(packetType, connection);
            ok = true;
        } finally {
            long elapsedNs = System.nanoTime() - startNs;   // 計時不含下面的記錄與寫 log
            activeSeq = 0L;
            recordCall(elapsedNs, radius, volume, ok);
        }
    }

    /**
     * 主迴圈掛點（每圈一次）：結算上一個觀測批次。與看門狗的 kill switch
     * 無關，只受 {@code -Dmdc.worldSoundProbe} 影響。第一次呼叫捕獲 owner 執行緒並寫 banner。
     */
    public static void onTick() {
        if (MODE == MODE_OFF) {
            return;
        }
        Thread current = Thread.currentThread();
        Thread owner = ownerThread;
        if (owner == null) {
            ownerThread = current;
        } else if (owner != current) {
            foreignTicks.incrementAndGet();
            return;
        }
        if (!bannerShown && bannerTries < BANNER_ATTEMPTS) {
            showBanner();
        }
        flushBatch();
    }

    /**
     * 給看門狗 stack dump 附掛的一行狀態；隔離 RuntimeException。只讀原生型別快照，
     * 讀到不一致（快照期間換了封包）就回 {@code active=changed}。
     */
    public static String describeActive() {
        if (MODE == MODE_OFF) {
            return TAG + " off";
        }
        try {
            long seq = activeSeq;   // volatile 讀＝取得下面純欄位的發佈
            String active;
            if (seq == 0L) {
                active = "none";
            } else {
                long startNs = activeStartNs;
                int radius = activeRadius;
                int volume = activeVolume;
                long inBatch = activeBatchSeq;
                // 保證 payload 的讀取完成後，才重讀發佈序號。
                VarHandle.acquireFence();
                if (activeSeq != seq) {
                    active = "changed";
                } else {
                    active = "seq=" + seq + " elapsedMs=" + ((System.nanoTime() - startNs) / 1_000_000L)
                            + " radius=" + radius + " volume=" + volume + " inBatchSeq=" + inBatch;
                }
            }
            return TAG + " active=" + active
                    + " | batchSeq=" + batchSeq + " batchCalls=" + batchCalls
                    + " batchMs=" + (batchNs / 1_000_000L)
                    + " calls=" + calls + " slow=" + slow + " failed=" + failed
                    + " totalMs=" + (totalNs / 1_000_000L) + " maxMs=" + (maxNs / 1_000_000L)
                    + " maxRadius=" + maxRadius + " batches=" + batches
                    + " slowBatches=" + slowBatches + " nested=" + nested
                    + " foreign=" + foreign.get() + " unarmed=" + unarmed.get()
                    + " suppressed=" + suppressed + " logErrors=" + logErrors;
        } catch (RuntimeException e) {
            return TAG + " describeActive failed: " + e.getClass().getName();
        }
    }

    /** 記錄一筆已完成的呼叫；自身 RuntimeException 只累計 logErrors。 */
    private static void recordCall(long elapsedNs, int radius, int volume, boolean ok) {
        try {
            calls++;
            totalNs += elapsedNs;
            if (elapsedNs > maxNs) {
                maxNs = elapsedNs;
            }
            if (radius > maxRadius) {
                maxRadius = radius;
            }
            if (!ok) {
                failed++;
            }
            batchCalls++;
            batchNs += elapsedNs;
            if (elapsedNs > batchMaxNs) {
                batchMaxNs = elapsedNs;
            }
            if (radius > batchMaxRadius) {
                batchMaxRadius = radius;
            }
            if (elapsedNs >= SLOW_MS * 1_000_000L) {
                slow++;
                if (allowLine()) {
                    DebugLog.log(TAG + " slowCall#" + slow + " ms=" + (elapsedNs / 1_000_000L)
                            + " radius=" + radius + " volume=" + volume
                            + " ok=" + ok + " batchSeq=" + batchSeq + " batchCalls=" + batchCalls
                            + " suppressed=" + suppressed + ".");
                }
            }
        } catch (RuntimeException e) {
            logErrors++;
        }
    }

    /** 結算上一批；不論記錄或寫 log 是否爆掉，批次累加器一律歸零並推進 batchSeq。 */
    private static void flushBatch() {
        long count = batchCalls;
        try {
            if (count > 0L) {
                batches++;
                long ms = batchNs / 1_000_000L;
                if (ms >= BATCH_MS) {
                    slowBatches++;
                    if (allowLine()) {
                        DebugLog.log(TAG + " slowBatch#" + slowBatches + " batchSeq=" + batchSeq
                                + " calls=" + count + " ms=" + ms
                                + " maxMs=" + (batchMaxNs / 1_000_000L)
                                + " maxRadius=" + batchMaxRadius
                                + " suppressed=" + suppressed + ".");
                    }
                }
            }
        } catch (RuntimeException e) {
            logErrors++;
        } finally {
            batchCalls = 0L;
            batchNs = 0L;
            batchMaxNs = 0L;
            batchMaxRadius = 0;
            batchSeq++;
        }
    }

    private static boolean allowLine() {
        long now = System.nanoTime();
        if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed++;
            return false;
        }
        windowCount++;
        logged++;
        return true;
    }

    private static void showBanner() {
        bannerTries++;
        try {
            DebugLog.log(TAG + " 首次生效 mode=observe slowCallMs=" + SLOW_MS
                    + " slowBatchMs=" + BATCH_MS + " detailCap=" + WINDOW_CAP + "/" + WINDOW_SECONDS
                    + "s ownerThread="
                    + Thread.currentThread().getName()
                    + "（-Dmdc.worldSoundProbe=0|off 停用；純觀測，不改任何音效行為；"
                    + "觀測 slowCall/slowBatch/maxRadius）.");
            bannerShown = true;
        } catch (RuntimeException e) {
            logErrors++;   // DebugLog 尚未就緒：下一圈再試，最多 BANNER_ATTEMPTS 次
        }
    }

    private static int parseMode() {
        String raw = System.getProperty("mdc.worldSoundProbe");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            default:
                return MODE_OBSERVE;   // 1|observe|未知值＝預設觀測
        }
    }
}
