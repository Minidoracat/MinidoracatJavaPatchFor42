package zombie.mdc;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.iso.WorldStreamer;

/**
 * Client 端 chunk 串流觀測 v2.1（黑邊事件鑑識；2026-08-11 兩起實案）。
 *
 * 症狀：玩家（尤以管理員高速跑圖後）周邊 chunk 永不載入（黑邊）、大地圖打不開，
 * 輕則數分鐘自癒、重則直到重開遊戲。server 端同時段實測全綠（tick 正常、廣播照送、
 * 該 client 也持續收到 Toxic 廣播）→ 卡點在 client 串流管線，但事故 log 被翻譯警告
 * 洪水沖掉，無法定位是哪一環。本 patch 只做觀測，不改任何行為。
 *
 * 觀測目標（WorldStreamer 的請求生命週期）：
 *   sendRequests→pendingRequests1＋mainThreadRequestQueue → updateMain 發
 *   RequestZipList 封包＋sentRequests → receiveChunkPart/receiveNotRequired 配對
 *   requestNumber → loadReceivedChunks 完成。已知風險機制（觀測要驗證的假說）：
 *   (a) requestingLargeArea 期間 pendingRequests1>20 會完全停送新請求（sendRequests
 *       頭部 gate）；(b) server 端 ClientChunkRequest.getRetryChunk 重試≥3 次回 null＝
 *       永久放棄該 requestNumber——兩者疊加＝pending 永遠清不掉、新請求全面停擺。
 *
 * 執行緒模型（codex 對抗審查修正）：receiveChunkPart/receiveNotRequired 由
 * **UdpEngine 網路執行緒**（經 GameClient.addIncoming）呼叫，updateMain 在主執行緒
 * ——兩側**不得共用鎖**（主執行緒持鎖做反射/組字串會延後封包處理＝改變行為）。
 * 因此 receive 路徑只做 lock-free 的 AtomicLong 遞增＋volatile 時戳；其餘決策狀態
 * 全部只由主執行緒觸碰（單執行緒、無鎖）。併發讀取邊界：pendingRequests 由
 * 網路執行緒增刪、pendingRequests1/chunkRequests1 由 streamer 執行緒增刪，
 * 本類只讀 size()（int 讀取，值可髒不會壞），CLQ.size() 為 O(n) 但佇列小
 * 且讀取被 10 秒視窗節流。
 *
 * STALL 判定（codex 對抗審查修正）：以「outstanding 上升沿」與「最後接收」雙基準——
 * 兩者都超過 30 秒才算卡滯，避免「閒置數分鐘後剛發新請求」的假報。
 * 基準污染防護（Claude 審查修正）：不持有 WorldStreamer 參考（static 強參考會把
 * 退役實例的 IsoChunk 串列＋native Inflater 整張圖釘住＝改變行為）——改用
 * 「心跳斷檔」偵測：updateMain 只在連線中每幀跑，斷檔 >30 秒＝觀測器沒在跑
 * （relog／in-place 重連／主迴圈凍結三情境同治）→ 基準全部重置。
 *
 * 輸出（console.txt）：active 宣告一次；STALL 行每 10 秒至多一行（含全部佇列水位＋
 * largeArea 旗標，黑邊定罪的關鍵行）；periodic 每 60 秒一行（有活動才報，單機安靜）；
 * 反射欄位漂移→一次性 disabled 宣告後永久降級僅計數（fail-quiet；另有 SmokeCheck
 * 欄位契約守門在建置期擋漂移）。
 */
public final class ChunkStreamObserver {

    private static final long PERIODIC_INTERVAL_NS = 60_000_000_000L;
    private static final long STALL_AFTER_NS = 30_000_000_000L;
    private static final long STALL_INTERVAL_NS = 10_000_000_000L;
    private static final long READ_INTERVAL_NS = 10_000_000_000L;

    // ---- 網路執行緒可觸碰的狀態（lock-free；絕不做 I/O/反射/鎖）----
    private static final AtomicLong partsReceived = new AtomicLong();
    private static final AtomicLong notRequiredReceived = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();
    private static volatile long lastReceiveNs;      // 0＝尚無基準（主執行緒首心跳初始化）

    // ---- 以下全部只由主執行緒觸碰（單執行緒、無鎖）----
    // 0 哨兵與 nanoTime 合法值理論上可撞（JLS 允許任意原點）；實務上 Windows QPC／
    // Linux CLOCK_MONOTONIC 皆為開機起算大正數，撞上僅致首個視窗靜默，fail-quiet 可接受。
    private static long lastUpdateNs;                // 上次心跳；斷檔 >30s＝基準全重置
    private static long lastReadNs;
    private static long lastStallLogNs;
    private static long lastPeriodicLogNs;
    private static long outstandingSinceNs;          // 0＝目前無未完成請求（上升沿追蹤）
    private static boolean announced;

    // 反射讀態：一次性初始化；任何失敗→永久停用（只剩計數），不影響遊戲
    private static Field fPending;
    private static Field fPending1;
    private static Field fReqQ0;
    private static Field fReqQ1;
    private static Field fSent;
    private static Field fLargeArea;
    private static Field fLargeDl;
    private static Field fReqNum;
    private static int reflectionState;              // 0=未初始化 1=可用 -1=停用

    private ChunkStreamObserver() {}

    /** updateMain 頭部掛點（主執行緒）：心跳＋節流讀態＋卡滯判定。 */
    public static void onUpdateMain(WorldStreamer ws) {
        try {
            long now = System.nanoTime();
            noteGap(now);
            if (!announced) {
                PatchInfo.announceOnce();     // 版本橫幅（冪等，與觀測宣告同時）
                DebugLog.log("[MinidoracatJavaPatch][ChunkStream] active stallAfterMs="
                        + STALL_AFTER_NS / 1_000_000L);
                announced = true;
            }
            if (now - lastReadNs < READ_INTERVAL_NS) {
                return;
            }
            lastReadNs = now;
            int pending = -1;
            int pending1 = -1;
            int reqQ0 = -1;
            int reqQ1 = -1;
            int sent = -1;
            boolean largeArea = false;
            int largeDl = -1;
            int reqNum = -1;
            if (ensureReflection()) {
                pending = sizeOf(fPending, ws);
                pending1 = sizeOf(fPending1, ws);
                reqQ0 = sizeOf(fReqQ0, ws);
                reqQ1 = sizeOf(fReqQ1, ws);
                sent = sizeOf(fSent, ws);
                largeArea = fLargeArea.getBoolean(ws);
                largeDl = fLargeDl.getInt(ws);
                reqNum = fReqNum.getInt(ws);
            }
            String line = decide(now, partsReceived.get(), notRequiredReceived.get(),
                    anomalies.get(), lastReceiveNs, pending, pending1, reqQ0, reqQ1, sent,
                    largeArea, largeDl, reqNum);
            if (line != null) {
                DebugLog.log(line);
            }
        } catch (Throwable t) {
            rethrowFatal(t);
            anomalies.incrementAndGet();
        }
    }

    /** receiveChunkPart 頭部掛點（UdpEngine 網路執行緒）：lock-free 計數，不拋不鎖。 */
    public static void onReceiveChunkPart(WorldStreamer ws) {
        partsReceived.incrementAndGet();
        lastReceiveNs = System.nanoTime();
    }

    /** receiveNotRequired 頭部掛點（UdpEngine 網路執行緒）：lock-free 計數，不拋不鎖。 */
    public static void onReceiveNotRequired(WorldStreamer ws) {
        notRequiredReceived.incrementAndGet();
        lastReceiveNs = System.nanoTime();
    }

    /**
     * 心跳斷檔偵測（僅主執行緒）：updateMain 斷檔 >30 秒＝觀測器沒在跑
     * （relog／in-place 重連／主迴圈凍結）→ 基準全部重置，不殘留舊生命週期。
     * 不持有任何遊戲物件參考（審查修正：static 強參考會釘住退役 WorldStreamer 物件圖）。
     */
    private static void noteGap(long nowNs) {
        if (lastUpdateNs == 0L || nowNs - lastUpdateNs > STALL_AFTER_NS) {
            lastReceiveNs = nowNs;
            outstandingSinceNs = 0L;
            lastStallLogNs = 0L;
        }
        lastUpdateNs = nowNs;
    }

    /**
     * 訊息決策（僅主執行緒；純函式於注入參數＋節流/邊沿內部狀態；只組字串不做 I/O）。
     * 優先序：STALL ＞ periodic。佇列值 -1＝反射停用（僅計數模式，STALL 不判定以免誤報）。
     * STALL 雙基準：outstanding 連續存在 ≥30 秒 **且** 最後接收距今 ≥30 秒。
     */
    static String decide(long nowNs, long parts, long notReq, long anomaliesV, long lastRxNs,
            int pending, int pending1, int reqQ0, int reqQ1, int sent,
            boolean largeArea, int largeDl, int reqNum) {
        boolean outstanding = pending > 0 || pending1 > 0 || reqQ1 > 0 || reqQ0 > 0;
        if (!outstanding) {
            outstandingSinceNs = 0L;
        } else if (outstandingSinceNs == 0L) {
            outstandingSinceNs = nowNs;
        }
        long noReceiveNs = nowNs - lastRxNs;
        String state = " parts=" + parts + " notReq=" + notReq
                + " pending=" + pending + " pending1=" + pending1
                + " reqQ0=" + reqQ0 + " reqQ1=" + reqQ1 + " sent=" + sent
                + " largeArea=" + largeArea + " largeDl=" + largeDl
                + " reqNum=" + reqNum + " anomalies=" + anomaliesV;

        if (outstanding
                && nowNs - outstandingSinceNs >= STALL_AFTER_NS
                && noReceiveNs >= STALL_AFTER_NS) {
            if (nowNs - lastStallLogNs >= STALL_INTERVAL_NS) {
                lastStallLogNs = nowNs;
                return "[MinidoracatJavaPatch][ChunkStream] STALL noReceiveMs="
                        + noReceiveNs / 1_000_000L
                        + " outstandingMs=" + (nowNs - outstandingSinceNs) / 1_000_000L + state;
            }
            return null;
        }

        if (nowNs - lastPeriodicLogNs >= PERIODIC_INTERVAL_NS) {
            lastPeriodicLogNs = nowNs;
            // 完全無活動（主選單/單機無 MP 串流）不報
            if (parts + notReq > 0 || outstanding) {
                return "[MinidoracatJavaPatch][ChunkStream] periodic" + state;
            }
        }
        return null;
    }

    private static boolean ensureReflection() {
        if (reflectionState == 1) {
            return true;
        }
        if (reflectionState == -1) {
            return false;
        }
        try {
            fPending = field("pendingRequests");
            fPending1 = field("pendingRequests1");
            fReqQ0 = field("chunkRequests0");
            fReqQ1 = field("chunkRequests1");
            fSent = field("sentRequests");
            fLargeArea = field("requestingLargeArea");
            fLargeDl = field("largeAreaDownloads");
            fReqNum = field("requestNumber");
            reflectionState = 1;
            return true;
        } catch (Throwable t) {
            rethrowFatal(t);
            reflectionState = -1;
            anomalies.incrementAndGet();
            DebugLog.log("[MinidoracatJavaPatch][ChunkStream] reflection disabled（欄位漂移？僅計數模式）");
            return false;
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field f = WorldStreamer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static int sizeOf(Field f, WorldStreamer ws) throws IllegalAccessException {
        Object v = f.get(ws);
        return v instanceof Collection<?> c ? c.size() : -1;
    }

    private static void rethrowFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof ThreadDeath || t instanceof LinkageError) {
            throw (Error) t;
        }
    }

    // ---- 測試掛點（package-private；production 不呼叫）----
    static void resetForTest() {
        partsReceived.set(0);
        notRequiredReceived.set(0);
        anomalies.set(0);
        lastReceiveNs = 0;
        lastUpdateNs = 0;
        lastReadNs = 0;
        lastStallLogNs = 0;
        lastPeriodicLogNs = 0;
        outstandingSinceNs = 0;
        announced = false;
    }

    static void recordReceiveForTest(long nowNs) {
        partsReceived.incrementAndGet();
        lastReceiveNs = nowNs;
    }

    static long lastReceiveForTest() {
        return lastReceiveNs;
    }

    static long partsForTest() {
        return partsReceived.get();
    }

    static void primeForTest(long nowNs) {
        lastReceiveNs = nowNs;
        lastPeriodicLogNs = nowNs;
    }

    static void noteGapForTest(long nowNs) {
        noteGap(nowNs);
    }
}
