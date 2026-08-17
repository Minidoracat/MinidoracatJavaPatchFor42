package zombie.mdc;

/**
 * ChunkStreamObserver.decide() 狀態機測試：STALL 雙基準（outstanding 上升沿×最後接收）、
 * 節流、periodic、靜默抑制、閒置後新請求不假報、心跳斷檔重置。時間全部注入。
 * 取樣點依 Claude 審查的突變分析選定：235s 取樣讓「接收基準」單獨擋不住，
 * 只有「上升沿基準」能擋——鎖住斷檔重置的回歸保護。
 */
public final class ChunkStreamObserverBehaviorTest {

    private static final long S = 1_000_000_000L;

    public static void main(String[] args) {
        quietSuppressed();
        stallDetectionAndThrottle();
        idleThenNewRequestsNoFalseStall();
        recoveryAfterReceive();
        periodicCadence();
        heartbeatGapResetsBaselines();
        reflectionOffCountersOnly();
        notReadyIndependentBaseline();
        notReadyOnlyPeriodicActivity();
        productionWiringOrder();
        System.out.println("chunk-stream OK  STALL 雙基準/節流、periodic、閒置不假報、斷檔重置、"
                + "ChunkNotReady 獨立基準/分型、notReady-only periodic、production 接線全數通過");
    }

    private static String decide(long nowNs, int pending, int pending1, int reqQ1, boolean largeArea) {
        return ChunkStreamObserver.decide(nowNs,
                ChunkStreamObserver.partsForTest(), 0, ChunkStreamObserver.notReadyForTest(), 0,
                ChunkStreamObserver.lastReceiveForTest(), ChunkStreamObserver.lastNotReadyForTest(),
                pending, pending1, 0, reqQ1, 0, largeArea, 0, 0);
    }

    private static String decideReflectionOff(long nowNs) {
        return ChunkStreamObserver.decide(nowNs, 1, 0, 0, 0,
                ChunkStreamObserver.lastReceiveForTest(), 0,
                -1, -1, -1, -1, -1, false, -1, -1);
    }

    /**
     * 42.20.3 新協定契約（三 lane 對抗審查定案）：NotReady 走獨立基準——
     * (1) hook 更新 lastNotReadyNs（真 nanoTime，夾範圍驗值）且不碰 payload 基準；
     * (2) STALL 維持「30 秒無 payload」＝生成瓶頸（server 持續回 NotReady）不被靜音；
     * (3) STALL 行帶 notReadyAgoMs 分型（近期有 NotReady＝生成端瓶頸、-1＝全斷流）。
     */
    private static void notReadyIndependentBaseline() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        // (1) hook：計數 +1、寫 lastNotReadyNs 真值（夾範圍）、payload 基準不動
        long payloadBefore = ChunkStreamObserver.lastReceiveForTest();
        long lo = System.nanoTime();
        ChunkStreamObserver.onReceiveChunkNotReady(null);
        long hi = System.nanoTime();
        long stamp = ChunkStreamObserver.lastNotReadyForTest();
        require(ChunkStreamObserver.notReadyForTest() == 1, "notReady 計數 +1");
        require(stamp >= lo && stamp <= hi, "lastNotReadyNs＝hook 當下的真 nanoTime（夾範圍）");
        require(ChunkStreamObserver.lastReceiveForTest() == payloadBefore,
                "payload 基準不受 NotReady 影響（獨立基準）");
        // (2)(3) 注入時間軸：先建立 outstanding 上升沿，payload 凍結 40s、NotReady 39s
        // 才來過 → STALL 照出且分型正確
        require(decide(1 * S, 3, 1, 0, false) == null, "上升沿起算");
        ChunkStreamObserver.primeNotReadyForTest(39 * S);
        String line = decide(40 * S, 3, 1, 0, false);
        require(line != null && line.contains("STALL"),
                "server 持續回 NotReady 仍無 payload → STALL 不被靜音：" + line);
        require(line.contains("noReceiveMs=40000") && line.contains("notReadyAgoMs=1000")
                        && line.contains(" notReady=1"),
                "STALL 行帶 payload 基準與 notReadyAgo 分型：" + line);
        // 全斷流形狀：本生命週期沒收過 NotReady → notReadyAgoMs=-1
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        decide(1 * S, 2, 0, 0, false);
        String dead = decide(45 * S, 2, 0, 0, false);
        require(dead != null && dead.contains("STALL") && dead.contains("notReadyAgoMs=-1"),
                "全斷流（零 NotReady）→ notReadyAgoMs=-1：" + dead);
        // lock-free 時序邊界：nowNs 取樣後網路緒才寫入時戳（lastNotReadyNs > nowNs）
        // → clamp 0（「就在剛剛」），不得輸出負值破壞分型
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        decide(1 * S, 2, 0, 0, false);
        ChunkStreamObserver.primeNotReadyForTest(41 * S);   // 未來時戳（跨緒競態形狀）
        String future = decide(40 * S, 2, 0, 0, false);
        require(future != null && future.contains("STALL") && future.contains("notReadyAgoMs=0"),
                "未來時戳 clamp 0（不得為負）：" + future);
    }

    /** periodic 活動閘的 notReady 項（殺「刪 + notReady 仍全綠」的突變體）：僅 NotReady 活動也要出行。 */
    private static void notReadyOnlyPeriodicActivity() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.onReceiveChunkNotReady(null);   // parts=0、notReq=0、notReady=1
        ChunkStreamObserver.primeNotReadyForTest(1 * S);    // 時戳改注入值（隔離真 nanoTime）
        String line = decide(61 * S, 0, 0, 0, false);       // 無 outstanding、僅 notReady 活動
        require(line != null && line.contains("periodic") && line.contains(" notReady=1"),
                "notReady-only 活動仍出 periodic 行：" + line);
    }

    /**
     * production 接線覆蓋（外部 codex post-fix review）：經 dispatchDecide 走與
     * onUpdateMain 相同的傳參——交換 lastReceiveNs/lastNotReadyNs 的接線突變體
     * 會讓 noReceive=1s<30s 不 STALL 或分型值錯，此案即炸。
     */
    private static void productionWiringOrder() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);       // payload 基準=0
        ChunkStreamObserver.primeNotReadyForTest(39 * S);  // notReady 基準=39s
        require(ChunkStreamObserver.dispatchDecide(1 * S, 3, 1, 0, 0, 0, false, 0, 0) == null,
                "上升沿起算（production 接線）");
        String line = ChunkStreamObserver.dispatchDecide(40 * S, 3, 1, 0, 0, 0, false, 0, 0);
        require(line != null && line.contains("STALL")
                        && line.contains("noReceiveMs=40000") && line.contains("notReadyAgoMs=1000"),
                "production 接線：兩基準各就各位（交換即不 STALL 或值錯）：" + line);
    }

    /** 完全無活動（主選單/單機）：永不出行。 */
    private static void quietSuppressed() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        for (long t = 0; t < 300 * S; t += 10 * S) {
            require(decide(t, 0, 0, 0, false) == null, "quiet 不出行 t=" + t);
        }
    }

    /** outstanding 連續 30 秒＋30 秒無接收→STALL；10 秒節流；持續卡滯持續報。 */
    private static void stallDetectionAndThrottle() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        require(decide(1 * S, 5, 20, 3, true) == null, "上升沿起算");
        require(decide(29 * S, 5, 20, 3, true) == null, "29 秒未達門檻");
        String line = decide(31 * S, 5, 20, 3, true);
        require(line != null && line.contains("STALL") && line.contains("noReceiveMs=31000")
                        && line.contains("outstandingMs=30000")
                        && line.contains("largeArea=true") && line.contains("pending1=20"),
                "31 秒出 STALL 行且含關鍵欄位：" + line);
        require(decide(36 * S, 5, 20, 3, true) == null, "10 秒節流內不重複");
        String line2 = decide(42 * S, 5, 20, 3, true);
        require(line2 != null && line2.contains("STALL") && line2.contains("noReceiveMs=42000"),
                "節流視窗過後再報：" + line2);
    }

    /** codex 修正案例：閒置 5 分鐘後剛發新請求——雖 noReceive 巨大，30 秒內不得假報。 */
    private static void idleThenNewRequestsNoFalseStall() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        for (long t = 10 * S; t <= 300 * S; t += 10 * S) {
            require(notStall(decide(t, 0, 0, 0, false)), "閒置期不得 STALL t=" + t);
        }
        // 301 秒：新請求出現，noReceive=301s 但 outstanding 剛上升→不得 STALL
        require(notStall(decide(301 * S, 4, 0, 0, false)), "上升沿 30 秒內不假報");
        require(notStall(decide(320 * S, 4, 0, 0, false)), "19 秒仍不得 STALL");
        String line = decide(332 * S, 4, 0, 0, false);
        require(line != null && line.contains("STALL") && line.contains("outstandingMs=31000"),
                "上升沿滿 30 秒且無接收才報：" + line);
        // outstanding 歸零→邊沿重置；再出現要重新起算（periodic 行合法，STALL 不得出現）
        require(notStall(decide(340 * S, 0, 0, 0, false)), "清空解除");
        require(notStall(decide(350 * S, 2, 0, 0, false)), "重新上升沿起算");
        require(notStall(decide(370 * S, 2, 0, 0, false)), "邊沿重起後 20 秒不得 STALL");
    }

    /** 接收恢復後不再 STALL，回到 periodic 模式。 */
    private static void recoveryAfterReceive() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        decide(1 * S, 3, 1, 0, false);
        require(decide(40 * S, 3, 1, 0, false) != null, "先進入 STALL");
        ChunkStreamObserver.recordReceiveForTest(45 * S);
        require(decide(50 * S, 3, 1, 0, false) == null, "接收後 STALL 解除");
        String line = decide(61 * S, 3, 1, 0, false);
        require(line != null && line.contains("periodic"), "恢復後出 periodic：" + line);
    }

    /** periodic 每 60 秒至多一行，且只在有活動時報。 */
    private static void periodicCadence() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        String first = decide(61 * S, 0, 0, 0, false);
        require(first != null && first.contains("periodic") && first.contains("parts=1"),
                "60 秒出 periodic：" + first);
        require(decide(90 * S, 0, 0, 0, false) == null, "60 秒內不重複");
        ChunkStreamObserver.recordReceiveForTest(100 * S);
        require(decide(122 * S, 1, 0, 0, false) != null, "下一視窗照報");
    }

    /**
     * 心跳斷檔（relog／in-place 重連／凍結）：lastReceive 與上升沿基準全部重置。
     * 235s 取樣點（審查突變分析）：noReceive=35s 已跨接收基準，只剩上升沿基準能擋
     * ——「斷檔未重置上升沿」的突變體在此必死。
     */
    private static void heartbeatGapResetsBaselines() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.noteGapForTest(0);          // 首心跳＝基準初始化
        ChunkStreamObserver.recordReceiveForTest(0);
        decide(1 * S, 5, 0, 0, false);                  // 上升沿=1s
        ChunkStreamObserver.noteGapForTest(10 * S);     // 連續心跳，不重置
        // 心跳斷檔 190 秒（>30s）於 t=200s 恢復：基準應全部重置為 200s
        ChunkStreamObserver.noteGapForTest(200 * S);
        require(ChunkStreamObserver.lastReceiveForTest() == 200 * S, "lastReceive 重置");
        require(notStall(decide(210 * S, 5, 0, 0, false)), "新生命週期上升沿重新起算，不假報");
        require(notStall(decide(235 * S, 5, 0, 0, false)),
                "noReceive=35s 已跨接收基準，僅上升沿(25s)擋住——斷檔重置的回歸鎖");
        String line = decide(241 * S, 5, 0, 0, false);
        require(line != null && line.contains("STALL"), "新基準滿 30 秒才報：" + line);
    }

    /** 反射停用（佇列=-1）：任何時距都不得 STALL，periodic 照出計數。 */
    private static void reflectionOffCountersOnly() {
        ChunkStreamObserver.resetForTest();
        ChunkStreamObserver.primeForTest(0);
        ChunkStreamObserver.recordReceiveForTest(0);
        require(decideReflectionOff(45 * S) == null, "佇列不明 45s 不誤報 STALL");
        String line = decideReflectionOff(61 * S);
        require(line != null && line.contains("periodic") && line.contains("pending=-1"),
                "僅計數模式 periodic 照出：" + line);
        // 審查突變案例：-1 被誤判為 outstanding 時，此取樣（noReceive=120s>30s）必出假 STALL
        require(notStall(decideReflectionOff(120 * S)), "反射停用跨 30 秒後仍永不 STALL");
        require(notStall(decideReflectionOff(200 * S)), "長時距仍永不 STALL");
    }

    private static boolean notStall(String line) {
        return line == null || !line.contains("STALL");
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private ChunkStreamObserverBehaviorTest() {}
}
