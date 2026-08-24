package zombie.mdc;

import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.network.ServerMap;

/**
 * W15 主迴圈凍結看門狗（2026-08-24 兩波卡頓事件的觀測刀；docs/patches.md 2ac）。
 *
 * <p>背景：2026-08-24 21:27–21:31 主迴圈單幀凍結累計約 216 秒（f:7115→7118 三幀、
 * console 靜默、只有 Steam callback 執行緒在動），RakNet 心跳逾時同幀踢掉 9 條在線連線，
 * 殘留封包以 connection-null 轟了 12.5 分鐘（75,143 行）＋玩家重連 chunk 重串流＝黑邊。
 * 事後鑑識剩三個互不排斥的候選機制（動物路徑重活／ZGC 瞬時 allocation stall／glibc
 * 損毀 heap 上的 malloc 停滯），全部卡在同一個觀測缺口：<b>凍結當下沒有人拿到主執行緒
 * 的 stack</b>。本刀把「下次凍結時抓 jstack」自動化——不改任何 vanilla 行為，純觀測。
 *
 * <p>掛點：{@code ServerMap.preupdate()V} 頭部 headCall。選它的理由：
 * <ul>
 *   <li>GameServer.main 主迴圈每圈恰呼叫一次（javap：main 內 invokevirtual
 *       {@code ServerMap.preupdate:()V} 全方法恰 1 處，經 {@code ServerMap.instance}），
 *       且 W6 事故 stack（GameServer.main:972 → ServerMap.preupdate）實證它在主迴圈上；</li>
 *   <li>public instance 無參方法，符合 headCall 的 {@code ALOAD 0 → INVOKESTATIC} 形狀
 *       （與 W4-1 removeOlderDuplicateRequests 同機制，ClassWriter(0) 下已驗證安全）；</li>
 *   <li>頭部插入＝本刀在 vanilla 任何一行執行前先記時間戳，凍結不管發生在 preupdate
 *       內或主迴圈其他任何位置，都表現為「時間戳停止推進」。</li>
 * </ul>
 *
 * <p>偵測語意：watchdog daemon 每秒輪詢「距上次 tick 多久」。超過門檻（預設 5000ms）
 * 即判定凍結開始，立刻對主執行緒 {@code getStackTrace()} 拍第一張快照，之後每 10 秒
 * 補拍一張、單次凍結最多 12 張（覆蓋 ~2 分鐘；216 秒級事件會拍好拍滿）。恢復時印
 * 總時長、期間 tick 推進數（區分「完全凍結」與「每幀 &gt;5s 的慢幀連發」）與累計統計。
 * 快照行帶 {@code Thread.getState()} 與 heap used/max——三個候選機制的分流指紋：
 * RUNNABLE＋動物/AI frame＝重活；RUNNABLE＋分配點/GC 相關 frame＝allocation stall；
 * RUNNABLE＋native frame（stack 淺或空）＝JNI/malloc 側；BLOCKED/WAITING＝鎖或 park。
 *
 * <p>正常運轉零輸出（不成為新噪音源，符合 log 入列門檻精神）：只有首次生效 banner
 * 與凍結事件本身會寫 console。tick() 熱路徑成本＝一次 volatile write＋一次遞增＋
 * 一次 null 檢查（每幀一次、10Hz，可忽略）；getStackTrace 只在凍結期間發生。
 *
 * <p>執行緒模型：{@code lastTickNanos}／{@code ticks} 由主執行緒單一 writer 寫入
 * （preupdate 只被 GameServer.main 呼叫），watchdog 執行緒唯讀——volatile 足夠，
 * 不需要 CAS。lazy start 由首次 tick 觸發（主執行緒上），{@code start()} 加
 * synchronized 純屬防禦。watchdog 本體全包 try/catch（RuntimeException｜LinkageError）
 * → {@code anomalies} 累計，任何自身故障都不得干擾遊戲或殺死輪詢迴圈。
 *
 * <p>kill switch：{@code -Dmdc.mainLoopWatchdog=0} 停用（tick 早退、執行緒不啟動）。
 * 門檻可調：{@code -Dmdc.mainLoopWatchdogThresholdMs}（clamp 1000..600000，預設 5000
 * ——今天的慢性頓挫是 2–3 秒級，5 秒起跳只抓「玩家一定有感」的事件，避免快照本身
 * 在頓挫風暴中成為額外負擔）。
 */
public final class MainLoopWatchdog {

    private static final int MODE_OFF = 0;
    private static final int MODE_ON = 1;

    private static final int MODE = parseMode();
    private static final long THRESHOLD_MS = parseThresholdMs();
    /** 凍結持續時的補拍間隔。 */
    private static final long REDUMP_INTERVAL_MS = 10_000L;
    /** 單次凍結最多快照數（12 張 × 10s ≈ 覆蓋 2 分鐘）。 */
    private static final int MAX_DUMPS_PER_STALL = 12;
    /** watchdog 輪詢週期。 */
    private static final long POLL_MS = 1_000L;
    private static final String TAG = "[MinidoracatJavaPatch][MainLoopWatchdog] ";

    /** 主執行緒單 writer；0＝尚未收到第一次 tick。 */
    private static volatile long lastTickNanos;
    /** 主執行緒單 writer 的幀計數（volatile 遞增在單 writer 下安全）。 */
    private static volatile long ticks;
    private static volatile Thread mainThread;
    private static volatile boolean started;

    /** 偵測到的凍結事件數。 */
    private static final AtomicLong stalls = new AtomicLong();
    /** 累計快照數。 */
    private static final AtomicLong dumps = new AtomicLong();
    /** 歷史最長凍結（ms）；watchdog 執行緒單 writer。 */
    private static volatile long maxStallMs;
    /** helper 自身診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    private static int parseMode() {
        String raw = System.getProperty("mdc.mainLoopWatchdog");
        if (raw == null) {
            return MODE_ON;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            default:
                return MODE_ON;
        }
    }

    private static long parseThresholdMs() {
        String raw = System.getProperty("mdc.mainLoopWatchdogThresholdMs");
        long value = 5_000L;
        if (raw != null) {
            try {
                value = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // 壞值保持預設；不得讓 class init 失敗
            }
        }
        return Math.max(1_000L, Math.min(600_000L, value));
    }

    /**
     * {@code ServerMap.preupdate()V} 頭部 headCall 的目標。參數是 headCall 機制固定
     * 傳入的 {@code this}（slot 0），本刀不使用——收下只為符合插入形狀。
     */
    public static void tick(ServerMap unused) {
        if (MODE == MODE_OFF) {
            return;
        }
        lastTickNanos = System.nanoTime();
        ticks++;
        if (!started) {
            start();
        }
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        try {
            mainThread = Thread.currentThread();
            Thread watchdog = new Thread(MainLoopWatchdog::run, "MinidoracatMainLoopWatchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            DebugLog.log(TAG + "首次生效 threshold=" + THRESHOLD_MS + "ms redump="
                    + REDUMP_INTERVAL_MS + "ms maxDumpsPerStall=" + MAX_DUMPS_PER_STALL
                    + " mainThread=" + mainThread.getName()
                    + "（-Dmdc.mainLoopWatchdog=0 停用；門檻 -Dmdc.mainLoopWatchdogThresholdMs）");
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        } finally {
            // 失敗也標記 started：反覆重試只會反覆失敗，且 tick 熱路徑不該一直進 synchronized
            started = true;
        }
    }

    private static void run() {
        boolean inStall = false;
        int dumpsThisStall = 0;
        long stallStartTicks = 0L;
        long stallFirstSeenNanos = 0L;
        long lastDumpNanos = 0L;
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                long last = lastTickNanos;
                if (last == 0L) {
                    continue;
                }
                long now = System.nanoTime();
                long stallMs = (now - last) / 1_000_000L;
                if (stallMs >= THRESHOLD_MS) {
                    if (!inStall) {
                        inStall = true;
                        dumpsThisStall = 0;
                        stallStartTicks = ticks;
                        stallFirstSeenNanos = now;
                        lastDumpNanos = 0L;
                        stalls.incrementAndGet();
                    }
                    if (dumpsThisStall < MAX_DUMPS_PER_STALL
                            && (lastDumpNanos == 0L
                                || now - lastDumpNanos >= REDUMP_INTERVAL_MS * 1_000_000L)) {
                        dumpsThisStall++;
                        lastDumpNanos = now;
                        dump(stallMs, dumpsThisStall);
                    }
                    if (stallMs > maxStallMs) {
                        maxStallMs = stallMs;
                    }
                } else if (inStall) {
                    inStall = false;
                    long observedMs = (now - stallFirstSeenNanos) / 1_000_000L + THRESHOLD_MS;
                    long ticksDuringStall = ticks - stallStartTicks;
                    DebugLog.log(TAG + "凍結結束 observedMs≈" + observedMs
                            + " ticksDuringStall=" + ticksDuringStall
                            + "（0=完全凍結；>0=慢幀連發）snapshots=" + dumpsThisStall
                            + " | 累計 stalls=" + stalls.get() + " dumps=" + dumps.get()
                            + " maxStallMs=" + maxStallMs + " anomalies=" + anomalies.get());
                }
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
    }

    private static void dump(long stallMs, int dumpNo) {
        Thread target = mainThread;
        if (target == null) {
            anomalies.incrementAndGet();
            return;
        }
        StackTraceElement[] frames = target.getStackTrace();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long maxMb = rt.maxMemory() >> 20;
        StringBuilder sb = new StringBuilder(256 + frames.length * 64);
        sb.append(TAG).append("主迴圈已凍結 ").append(stallMs).append("ms（快照 ")
                .append(dumpNo).append('/').append(MAX_DUMPS_PER_STALL)
                .append("）ticks=").append(ticks)
                .append(" state=").append(target.getState())
                .append(" heapUsedMB=").append(usedMb).append('/').append(maxMb);
        if (frames.length == 0) {
            sb.append("\n    (取不到 stack——執行緒可能整段在 native 中)");
        } else {
            for (StackTraceElement frame : frames) {
                sb.append("\n    at ").append(frame);
            }
        }
        DebugLog.log(sb.toString());
        dumps.incrementAndGet();
    }

    // ---- 測試掛點（package-private，與 AnimalRequestGate 同慣例）----

    static int mode() {
        return MODE;
    }

    static long thresholdMs() {
        return THRESHOLD_MS;
    }

    static long tickCount() {
        return ticks;
    }

    static long lastTickNanosForTest() {
        return lastTickNanos;
    }

    static long stallCount() {
        return stalls.get();
    }

    static long dumpCount() {
        return dumps.get();
    }

    static long anomalyCount() {
        return anomalies.get();
    }

    private MainLoopWatchdog() {}
}
