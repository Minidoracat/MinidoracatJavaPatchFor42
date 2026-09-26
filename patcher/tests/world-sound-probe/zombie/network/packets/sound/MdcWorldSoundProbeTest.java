package zombie.network.packets.sound;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.mdc.MainLoopWatchdog;
import zombie.network.PacketTypes;

/**
 * WorldSoundPacket 觀測刀行為驗證（獨立 JVM；MODE 是 static final，各組態由 build.ps1 以不同
 * property／argv 各跑一次）。放在 {@code zombie.network.packets.sound} 是為了用<b>真的</b>
 * {@link WorldSoundPacket} 子類別當受控 delegate——同 package 才能直讀 package-private 的
 * {@code radius}／{@code volume}／座標，驗證本刀沒有改動它們。
 *
 * <p>斷言一律走公開可觀測面：委派次數與它看到的原始欄位（delegate 自己記）、原例外的<b>物件
 * 識別</b>、{@link MdcWorldSoundProbe#describeActive()}（含全部累計計數）、以及真的 DebugLog
 * 輸出行。累積門檻案例以反射注入已完成的計時樣本，避免作業系統排程讓 sleep 超過門檻。
 *
 * <p>log 擷取機制：{@code DebugType.General} 預設 {@code LogSeverity.Off}（裸 JVM 裡 DebugLog
 * 是安靜 no-op），本測試顯式開到 {@code All} 並用公開的
 * {@code DebugLog.getInstance().setStdOut(...)} 把輸出導進 buffer——正式碼不知道測試存在。
 * 前置 {@code RandStandard.init()} ＋ {@code GameServer.server=true} 是 DebugLog 格式化鏈
 * （IsoWorld 幀號／server 時間）在裸 JVM 能跑的必要條件，缺了會 ExceptionInInitializerError。
 *
 * <p>四組態：
 * <ul>
 *   <li>{@code observe}（預設出貨；<b>必須以 {@code -Dmdc.mainLoopWatchdog=0} 執行</b>）：
 *       委派恰一次＋原始 radius/volume 零改動、in-flight 狀態在 delegate 執行中可讀、
 *       任何出口都清乾淨（漏 finally 會被抓）、RuntimeException／Error 物件識別不變、
 *       兩筆各自未達門檻的計時樣本累計超標，會在下一次主迴圈掛點結算（之後沒有任何音效也照樣
 *       結算）、巢套派送不重複計時、外來執行緒純直通、明細行額度 3 行／60s 咬住
 *       （第 4、5 筆只累計 suppressed）、log 行不含座標。此組態的所有結算都走
 *       {@link MainLoopWatchdog#tick} 這條正式路徑：看門狗被關掉時觀測仍須完整運作，
 *       把 {@code onTick} 掛在看門狗 kill switch 之後（線上靜靜失效）會在此被抓。</li>
 *   <li>{@code off}（{@code -Dmdc.worldSoundProbe=off}）：派送與例外完全原樣，
 *       {@code describeActive} 回 off，整個 JVM 零 log 行。</li>
 *   <li>{@code unarmed}：第一次 {@code onTick} 之前的派送純直通不計時（只記 unarmed），
 *       武裝後同執行緒的派送才開始量。</li>
 *   <li>{@code logthrow}：logger 自己拋 RuntimeException（導流到會爆的 OutputStream）——
 *       原例外不得被診斷輸出蓋掉、原版不得重跑、批次累加器仍在 finally 歸零、額度仍咬住。</li>
 * </ul>
 */
public final class MdcWorldSoundProbeTest {

    // ---- 與正式碼的契約（若正式碼改字串／門檻，只需要改這一塊）----
    private static final String TAG = "[MinidoracatJavaPatch][WorldSoundProbe]";
    private static final String BANNER = TAG + " 首次生效 mode=observe";
    private static final String OFF_LINE = TAG + " off";
    private static final int DETAIL_CAP = 3;
    private static final long SLOW_MS = 100L;

    /** 慢呼叫用的 delegate 停留時間：安全超過 100ms 門檻。 */
    private static final long SLOW_SLEEP_MS = 150L;
    /** 單發不慢、兩發累計超標用的停留時間。 */
    private static final long HALF_SLEEP_MS = 60L;
    /** 巢套測試用：內層停留 300ms，累計不得超過外層實測牆鐘時間。 */
    private static final long NESTED_SLEEP_MS = 300L;

    /** 故意好認的座標：任何 log 行含到它們就是洩漏座標。 */
    private static final int PROBE_X = 13579;
    private static final int PROBE_Y = 24680;
    private static final int PROBE_Z = 7;

    /** 從 describeActive() 取單一計數欄位；前置 lookbehind 擋掉 batchCalls／slowBatches 之類的後綴撞名。 */
    private static final String COUNTER = "(?<![A-Za-z])%s=(-?\\d+)";

    private static int failed;
    private static ByteArrayOutputStream logBuf;

    public static void main(String[] args) throws Exception {
        // DebugLog 格式化鏈（IsoWorld 幀號／server 時間戳）在裸 JVM 的前置條件。
        zombie.core.random.RandStandard.INSTANCE.init();
        zombie.network.GameServer.server = true;

        String mode = args.length > 0 ? args[0] : "observe";
        captureLog();

        switch (mode) {
            case "off" -> runOff();
            case "unarmed" -> runUnarmed();
            case "logthrow" -> runLogThrow();
            case "observe" -> runObserve();
            default -> {
                expect("未知 argv（" + mode + "）", false);
            }
        }

        if (failed != 0) {
            System.out.println("world-sound-probe FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("world-sound-probe OK mode=" + mode);
    }

    // ============================ observe（預設出貨組態）============================

    private static void runObserve() throws Exception {
        expect("自驗：argv=observe ⇒ 本刀啟用（describeActive 不是 off 行）",
                !MdcWorldSoundProbe.describeActive().startsWith(OFF_LINE)
                && MdcWorldSoundProbe.describeActive().contains("active="));
        // observe 組態刻意以 -Dmdc.mainLoopWatchdog=0 執行：本刀的 onTick 掛在看門狗
        // kill switch 之前，看門狗被關掉時觀測仍須完整運作（漏掛在 return 後面會被抓）。
        expect("自驗：看門狗 kill switch 已關閉（-Dmdc.mainLoopWatchdog=0，實際 mode="
                + watchdogStat("mode") + "）", watchdogStat("mode") == 0L);

        // 武裝：走正式路徑（ServerMap.preupdate headCall 的目標）捕獲 owner 執行緒並寫 banner。
        mainLoopTick();
        expect("看門狗關閉下，主迴圈掛點仍然武裝本刀並寫出生效 banner（mode／門檻／額度／owner）",
                rows().stream().anyMatch(r -> r.contains(BANNER))
                && rows().stream().anyMatch(r -> r.contains("slowCallMs=" + SLOW_MS)
                        && r.contains("detailCap=" + DETAIL_CAP + "/60s")));
        expect("看門狗本體確實沒運轉（tickCount=0、零 anomalies）",
                watchdogStat("tickCount") == 0L && watchdogStat("anomalyCount") == 0L);

        testDelegationAndRawValues();
        testExceptionIdentity();
        testCumulativeBatchFlushedOnTick();
        testNestedNotDoubleCounted();
        testDetailCap();
        testForeignThreadPassThrough();

        expect("observe 全程 logger 零故障（logErrors=0）", counter("logErrors") == 0L);
        expect("明細行總數恰好等於額度 " + DETAIL_CAP + "（實際 " + detailRows().size() + "）",
                detailRows().size() == DETAIL_CAP);
        expect("不寫週期 beat 行（log 預算：只剩 banner 與限額明細）",
                rows().stream().noneMatch(r -> r.contains(TAG + " beat")));
        testNoCoordinatesLogged();
    }

    /** 委派恰一次、參數原樣、原始 radius/volume 零改動；in-flight 狀態可讀且出口清乾淨。 */
    private static void testDelegationAndRawValues() throws Exception {
        UdpConnection connection = conn();
        PacketTypes.PacketType type = PacketTypes.PacketType.WorldSoundPacket;
        DelegatePacket packet = packet(9_999, 77);

        long calls0 = counter("calls");
        MdcWorldSoundProbe.processServer(packet, type, connection);

        expect("原版 processServer 恰被呼叫一次", packet.calls == 1);
        expect("參數原樣傳遞（同一個 PacketType／同一條連線參考）",
                packet.seenType == type && packet.seenConnection == connection);
        expect("delegate 看到的是未經修改的原始資料（radius 沒被 clamp、volume 沒被改）",
                packet.seenRadius == 9_999 && packet.seenVolume == 77
                && packet.seenX == PROBE_X && packet.seenY == PROBE_Y && packet.seenZ == PROBE_Z);
        expect("回來後封包欄位仍是原值（本刀不 mutate 遊戲欄位）",
                packet.radius == 9_999 && packet.volume == 77
                && packet.x == PROBE_X && packet.y == PROBE_Y && packet.z == PROBE_Z);
        expect("delegate 執行中可讀 in-flight 狀態（seq／原始 radius／volume／所屬批次）",
                packet.activeWhileRunning.contains("active=seq=")
                && packet.activeWhileRunning.contains(" radius=9999")
                && packet.activeWhileRunning.contains(" volume=77")
                && packet.activeWhileRunning.contains(" inBatchSeq="));
        expect("正常出口後 in-flight 狀態已清空（active=none）",
                MdcWorldSoundProbe.describeActive().contains("active=none"));
        expect("calls+1、記錄的是原始 radius（maxRadius=9999）",
                counter("calls") == calls0 + 1 && counter("maxRadius") == 9_999L);
        expect("快呼叫不寫明細行", detailRows().isEmpty());
    }

    /** RuntimeException 與 Error 都原樣上拋（同一個物件），且兩種出口都清乾淨、只記 failed。 */
    private static void testExceptionIdentity() throws Exception {
        long failed0 = counter("failed");

        IllegalStateException boom = new IllegalStateException("sound blew up");
        DelegatePacket rte = packet(11, 3);
        rte.body = () -> {
            throw boom;
        };
        Throwable caught = null;
        try {
            MdcWorldSoundProbe.processServer(rte, null, null);
        } catch (Throwable t) {
            caught = t;
        }
        expect("RuntimeException 原樣上拋：同一個物件（不是被包裝或替換）", caught == boom);
        expect("RuntimeException 出口：原版只跑一次、in-flight 已清空（漏 finally 會在此被抓）",
                rte.calls == 1 && MdcWorldSoundProbe.describeActive().contains("active=none"));

        OutOfMemoryError fatal = new OutOfMemoryError("sound OOM");
        DelegatePacket err = packet(12, 4);
        err.body = () -> {
            throw fatal;
        };
        caught = null;
        try {
            MdcWorldSoundProbe.processServer(err, null, null);
        } catch (Throwable t) {
            caught = t;
        }
        expect("Error 原樣上拋：同一個物件（沒有被 catch(RuntimeException) 吃掉或轉型）",
                caught == fatal);
        expect("Error 出口：原版只跑一次、in-flight 已清空",
                err.calls == 1 && MdcWorldSoundProbe.describeActive().contains("active=none"));
        expect("兩次失敗各記一筆 failed（+2）", counter("failed") == failed0 + 2);
        expect("例外路徑不算 logger 故障（logErrors=0）", counter("logErrors") == 0L);
    }

    /**
     * 核心：注入兩筆各 60ms 的完成樣本，累計 120ms；不用 sleep 的上界假設，
     * 避免忙碌主機把原本預期未達門檻的呼叫拖成慢呼叫。真正派送／計時由其他案例驗證。
     *
     * <p>結算刻意走 {@link MainLoopWatchdog#tick} 這條正式路徑（而非直接呼叫
     * {@code onTick}）：看門狗此組態是關閉的，把 onTick 掛在它的 kill switch 之後
     * 就會讓整個批次觀測在線上靜靜失效——那正是這裡要抓的東西。
     */
    private static void testCumulativeBatchFlushedOnTick() throws Exception {
        mainLoopTick();   // 先切掉前面測試殘留的批次
        long slow0 = counter("slow");
        long batches0 = counter("batches");
        long slowBatches0 = counter("slowBatches");
        long seq0 = counter("batchSeq");

        java.lang.reflect.Method record = MdcWorldSoundProbe.class.getDeclaredMethod(
                "recordCall", long.class, int.class, int.class, boolean.class);
        record.setAccessible(true);
        record.invoke(null, HALF_SLEEP_MS * 1_000_000L, 101, 5, true);
        record.invoke(null, HALF_SLEEP_MS * 1_000_000L, 202, 6, true);

        expect("兩發都未達單次門檻：零 slow、零明細行",
                counter("slow") == slow0 && detailRows().isEmpty());
        expect("結算前批次已累積兩筆", counter("batchCalls") == 2L);

        mainLoopTick();   // 沒有後續音效，照樣由主迴圈掛點結算上一批

        List<String> batchRows = rowsContaining("slowBatch#");
        expect("看門狗關閉下，主迴圈掛點仍結算出恰一行 slowBatch（後面沒有音效也不卡著）",
                batchRows.size() == 1);
        expect("批次行帶筆數／累計／最大耗時／最大原始 radius（radius 未被改動）",
                batchRows.size() == 1 && batchRows.get(0).contains(" calls=2")
                && batchRows.get(0).contains(" maxRadius=202")
                && batchMs(batchRows.get(0)) >= 2 * HALF_SLEEP_MS - 5);
        expect("批次計數：batches+1、slowBatches+1、batchSeq 推進、累加器歸零",
                counter("batches") == batches0 + 1
                && counter("slowBatches") == slowBatches0 + 1
                && counter("batchSeq") > seq0
                && counter("batchCalls") == 0L);
        expect("看門狗本體始終沒被喚醒（tickCount=0）", watchdogStat("tickCount") == 0L);
    }

    /**
     * 巢套派送（owner 執行緒上的 processServer 內又派送一發）：耗時歸最外層那一筆，
     * 不得重複計成兩筆呼叫、也不得把同一段時間加兩次進批次。
     */
    private static void testNestedNotDoubleCounted() throws Exception {
        mainLoopTick();
        long calls0 = counter("calls");
        long nested0 = counter("nested");
        long total0 = counter("totalMs");

        DelegatePacket inner = packet(303, 8);
        inner.body = () -> sleep(NESTED_SLEEP_MS);
        DelegatePacket outer = packet(404, 9);
        outer.body = () -> MdcWorldSoundProbe.processServer(inner, null, null);

        long wallStart = System.nanoTime();
        MdcWorldSoundProbe.processServer(outer, null, null);
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000L;

        expect("巢套：內外層原版各恰一次", outer.calls == 1 && inner.calls == 1);
        expect("巢套只算一筆呼叫、內層記在 nested（calls+1／nested+1）",
                counter("calls") == calls0 + 1 && counter("nested") == nested0 + 1);
        long totalDelta = counter("totalMs") - total0;
        expect("outer-inclusive：累計不超過整個外層呼叫的實測時間（計數 " + totalDelta
                + "ms／牆鐘 " + wallMs + "ms）",
                totalDelta >= NESTED_SLEEP_MS - 5 && totalDelta <= wallMs + 1);

        List<String> before = rowsContaining("slowCall#");
        expect("外層超過單次門檻：寫出一行 slowCall，帶原始 radius 與 volume",
                before.size() == 1 && before.get(0).contains(" radius=404")
                && before.get(0).contains(" volume=9") && before.get(0).contains(" ok=true"));

        mainLoopTick();
        List<String> batchRows = rowsContaining("slowBatch#");
        expect("批次也只收到一筆（巢套沒有被當成第二筆塞進批次）",
                batchRows.size() == 2 && batchRows.get(1).contains(" calls=1"));
    }

    /** 明細額度（slowCall＋slowBatch 共用 3 行／60s）：超出只累計 suppressed，不繼續寫。 */
    private static void testDetailCap() throws Exception {
        int rows0 = detailRows().size();
        expect("此時額度已用滿 " + DETAIL_CAP + " 行", rows0 == DETAIL_CAP);
        long suppressed0 = counter("suppressed");
        long slow0 = counter("slow");

        DelegatePacket slow = packet(505, 10);
        slow.body = () -> sleep(SLOW_SLEEP_MS);
        MdcWorldSoundProbe.processServer(slow, null, null);
        expect("第 4 筆慢呼叫：仍然計數但不再寫行（suppressed+1）",
                counter("slow") == slow0 + 1 && detailRows().size() == rows0
                && counter("suppressed") == suppressed0 + 1);

        mainLoopTick();
        expect("接著的慢批次同樣受同一份額度拘束（suppressed+2、行數不變）",
                counter("suppressed") == suppressed0 + 2 && detailRows().size() == rows0);
        expect("被壓下的觀測仍然算進 slowBatches（統計不失真）", counter("slowBatches") >= 3L);
    }

    /** 外來執行緒：純直通、不計時、不動批次狀態；它呼叫 onTick 也不得結算 owner 的批次。 */
    private static void testForeignThreadPassThrough() throws Exception {
        long calls0 = counter("calls");
        long foreign0 = counter("foreign");
        long seq0 = counter("batchSeq");

        DelegatePacket packet = packet(606, 11);
        Thread t = new Thread(() -> {
            MdcWorldSoundProbe.processServer(packet, null, null);
            MdcWorldSoundProbe.onTick();
        }, "foreign-dispatch");
        t.start();
        t.join();

        expect("外來執行緒：原版照樣恰跑一次、看到的是原始 radius",
                packet.calls == 1 && packet.seenRadius == 606);
        expect("外來執行緒不計時（calls 不變、只記 foreign+1）",
                counter("calls") == calls0 && counter("foreign") == foreign0 + 1);
        expect("外來執行緒的 onTick 不結算 owner 的批次（batchSeq 不動）",
                counter("batchSeq") == seq0);
    }

    /** 本刀的 log 行只准帶 radius／volume／耗時；座標與玩家身分不得出現（只看帶 TAG 的行）。 */
    private static void testNoCoordinatesLogged() {
        String mine = String.join("\n", rows());
        expect("log 行不含座標值（x=" + PROBE_X + "／y=" + PROBE_Y + "／z=" + PROBE_Z + "）",
                !mine.contains(String.valueOf(PROBE_X)) && !mine.contains(String.valueOf(PROBE_Y)));
        expect("log 行不含座標欄位（x=／y=／z=）",
                !mine.contains(" x=") && !mine.contains(" y=") && !mine.contains(" z="));
        expect("log 行不含玩家／連線身分字樣",
                !mine.contains("player") && !mine.contains("username") && !mine.contains("guid"));
    }

    // ============================ off（kill switch）============================

    private static void runOff() throws Exception {
        expect("自驗：argv=off ⇒ describeActive 回 off 行（property 真的生效了）",
                OFF_LINE.equals(MdcWorldSoundProbe.describeActive()));

        UdpConnection connection = conn();
        PacketTypes.PacketType type = PacketTypes.PacketType.WorldSoundPacket;
        DelegatePacket packet = packet(9_999, 77);
        packet.body = () -> sleep(SLOW_SLEEP_MS);   // 就算很慢，off 也不得有任何反應

        MdcWorldSoundProbe.onTick();
        MdcWorldSoundProbe.processServer(packet, type, connection);

        expect("off：原版恰一次、參數原樣、原始欄位零改動",
                packet.calls == 1 && packet.seenType == type && packet.seenConnection == connection
                && packet.seenRadius == 9_999 && packet.seenVolume == 77
                && packet.radius == 9_999 && packet.volume == 77);

        IllegalStateException boom = new IllegalStateException("off path blew up");
        DelegatePacket rte = packet(11, 3);
        rte.body = () -> {
            throw boom;
        };
        Throwable caught = null;
        try {
            MdcWorldSoundProbe.processServer(rte, null, null);
        } catch (Throwable t) {
            caught = t;
        }
        expect("off：例外原樣上拋（同一個物件）、原版只跑一次", caught == boom && rte.calls == 1);

        Thread foreign = new Thread(() -> {
            DelegatePacket p = packet(12, 4);
            MdcWorldSoundProbe.processServer(p, null, null);
            MdcWorldSoundProbe.onTick();
            expect("off：外來執行緒同樣純直通", p.calls == 1);
        }, "off-foreign");
        foreign.start();
        foreign.join();

        MdcWorldSoundProbe.onTick();
        expect("off：整個 JVM 零 log 行（連 banner 都沒有）", rows().isEmpty());
        expect("off：describeActive 仍是 off 行，不吐任何統計",
                OFF_LINE.equals(MdcWorldSoundProbe.describeActive()));
    }

    // ============================ unarmed（owner 尚未捕獲）============================

    private static void runUnarmed() throws Exception {
        expect("自驗：unarmed 組態走 observe 模式",
                MdcWorldSoundProbe.describeActive().contains("active="));
        expect("尚未 onTick：owner 未捕獲，unarmed=0", counter("unarmed") == 0L);

        DelegatePacket early = packet(707, 12);
        early.body = () -> sleep(SLOW_SLEEP_MS);
        MdcWorldSoundProbe.processServer(early, null, null);

        expect("武裝前：原版恰一次、原始 radius 原樣", early.calls == 1 && early.seenRadius == 707);
        expect("武裝前不計時（calls=0／slow=0／unarmed=1）且無 in-flight 殘留",
                counter("calls") == 0L && counter("slow") == 0L && counter("unarmed") == 1L
                && MdcWorldSoundProbe.describeActive().contains("active=none"));
        expect("武裝前零 log 行（連 banner 都還沒寫）", rows().isEmpty());

        MdcWorldSoundProbe.onTick();
        DelegatePacket armed = packet(808, 13);
        armed.body = () -> sleep(SLOW_SLEEP_MS);
        MdcWorldSoundProbe.processServer(armed, null, null);

        expect("武裝後同一執行緒的派送開始計量（calls=1／slow=1／maxRadius=808）",
                counter("calls") == 1L && counter("slow") == 1L && counter("maxRadius") == 808L);
        expect("武裝後才寫 banner 與明細行",
                rows().stream().anyMatch(r -> r.contains(BANNER))
                && rowsContaining("slowCall#").size() == 1);
        expect("unarmed 那筆不回頭補算（unarmed 仍為 1）", counter("unarmed") == 1L);
    }

    // ============================ logthrow（logger 自己爆掉）============================

    private static void runLogThrow() throws Exception {
        expect("自驗：logthrow 組態走 observe 模式",
                MdcWorldSoundProbe.describeActive().contains("active="));
        explodeLog();

        // 武裝：banner 也會踩到爆掉的 logger——不得外逃，只累計 logErrors。
        MdcWorldSoundProbe.onTick();
        expect("banner 寫失敗只累計 logErrors，不外逃", counter("logErrors") >= 1L);

        // (1) Error：recordCall 在 finally 裡跑，logger 又爆——原 Error 不得被蓋掉。
        OutOfMemoryError fatal = new OutOfMemoryError("sound OOM");
        DelegatePacket errPacket = packet(111, 1);
        errPacket.body = () -> {
            sleep(SLOW_SLEEP_MS);
            throw fatal;
        };
        Throwable caught = catching(errPacket);
        expect("logger 爆掉時，原 Error 仍原樣上拋（不是 logger 的 IllegalStateException）",
                caught == fatal);

        // (2) RuntimeException：同上，且原版不得被重跑。
        IllegalStateException boom = new IllegalStateException("sound blew up");
        DelegatePacket rtePacket = packet(222, 2);
        rtePacket.body = () -> {
            sleep(SLOW_SLEEP_MS);
            throw boom;
        };
        caught = catching(rtePacket);
        expect("logger 爆掉時，原 RuntimeException 仍是同一個物件、原版恰一次（永不重試）",
                caught == boom && rtePacket.calls == 1 && errPacket.calls == 1);
        expect("兩筆失敗照樣記帳（calls=2／failed=2／slow=2）",
                counter("calls") == 2L && counter("failed") == 2L && counter("slow") == 2L);

        // (3) 批次結算時 logger 爆掉：onTick 不外逃，累加器仍在 finally 歸零。
        long seq0 = counter("batchSeq");
        MdcWorldSoundProbe.onTick();
        expect("批次行寫失敗：onTick 正常返回，累加器歸零、batchSeq 推進、統計照記",
                counter("batchCalls") == 0L && counter("batchSeq") > seq0
                && counter("batches") == 1L && counter("slowBatches") == 1L);

        // (4) 額度仍然咬住：第 4 筆明細被壓下（logger 壞掉不等於額度失效）。
        long suppressed0 = counter("suppressed");
        DelegatePacket normal = packet(333, 3);
        normal.body = () -> sleep(SLOW_SLEEP_MS);
        MdcWorldSoundProbe.processServer(normal, null, null);
        expect("logger 爆掉也不改變委派結果：正常返回、原版恰一次、in-flight 清空",
                normal.calls == 1 && MdcWorldSoundProbe.describeActive().contains("active=none"));
        expect("額度照舊（第 4 筆 suppressed+1）", counter("suppressed") == suppressed0 + 1L);
        expect("logger 故障全記在 logErrors（>=4：banner＋3 行明細）", counter("logErrors") >= 4L);
        expect("logger 故障不污染 failed（仍為 2）", counter("failed") == 2L);
    }

    // ============================ fixture ============================

    /**
     * 真的 {@link WorldSoundPacket} 子類別：覆寫 {@code processServer} 讓原版的
     * 「逐條連線 RelevantTo ＋ 重送」不執行（測試沒有 udpEngine，也不需要為了大 radius
     * 真的掃全表），行為由測試腳本注入。它自己記錄「被呼叫幾次、看到什麼」——委派次數與
     * 欄位零改動都由這裡證明，不靠正式碼吐任何東西。
     */
    private static final class DelegatePacket extends WorldSoundPacket {
        Runnable body;
        int calls;
        int seenRadius;
        int seenVolume;
        int seenX;
        int seenY;
        int seenZ;
        String activeWhileRunning;
        PacketTypes.PacketType seenType;
        UdpConnection seenConnection;

        @Override
        public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
            this.calls++;
            this.seenType = packetType;
            this.seenConnection = connection;
            this.seenRadius = this.radius;
            this.seenVolume = this.volume;
            this.seenX = this.x;
            this.seenY = this.y;
            this.seenZ = this.z;
            this.activeWhileRunning = MdcWorldSoundProbe.describeActive();
            if (this.body != null) {
                this.body.run();
            }
        }
    }

    private static DelegatePacket packet(int radius, int volume) {
        DelegatePacket p = new DelegatePacket();
        p.radius = radius;
        p.volume = volume;
        p.x = PROBE_X;
        p.y = PROBE_Y;
        p.z = PROBE_Z;
        return p;
    }

    private static Throwable catching(DelegatePacket packet) {
        try {
            MdcWorldSoundProbe.processServer(packet, null, null);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** 最小連線：本刀不得讀它的任何欄位，只驗參考原樣傳下去。 */
    @SuppressWarnings({"deprecation", "removal"})
    private static UdpConnection conn() throws Exception {
        java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (UdpConnection) unsafe.allocateInstance(UdpConnection.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 主迴圈掛點（正式路徑）----

    /**
     * 走 {@code ServerMap.preupdate} headCall 的實際目標。傳 null 與 W15 測試同慣例
     * （參數是 headCall 機制固定傳入的 {@code this}，兩支刀都不使用）。
     */
    private static void mainLoopTick() {
        MainLoopWatchdog.tick(null);
    }

    /** 看門狗的 package-private 測試掛點（跨 package，只能反射讀；不加任何新的正式 API）。 */
    private static long watchdogStat(String accessor) {
        try {
            java.lang.reflect.Method m = MainLoopWatchdog.class.getDeclaredMethod(accessor);
            m.setAccessible(true);
            return ((Number) m.invoke(null)).longValue();
        } catch (ReflectiveOperationException e) {
            expect("讀不到 MainLoopWatchdog." + accessor + "()：" + e, false);
            return Long.MIN_VALUE;
        }
    }

    // ---- log 擷取（只用公開 API；正式碼不知道測試存在）----

    private static void captureLog() {
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);   // 裸 JVM 預設 Off＝安靜 no-op
        DebugLog.getInstance().setStdOut(logBuf);
    }

    /** 讓 logger 自己拋 RuntimeException（PrintStream 只吞 IOException）。 */
    private static void explodeLog() {
        DebugLog.getInstance().setStdOut(new OutputStream() {
            @Override
            public void write(int b) {
                throw new IllegalStateException("logger exploded");
            }

            @Override
            public void write(byte[] b, int off, int len) {
                throw new IllegalStateException("logger exploded");
            }
        });
    }

    private static List<String> rows() {
        List<String> out = new ArrayList<>();
        for (String line : logBuf.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> rowsContaining(String token) {
        List<String> out = new ArrayList<>();
        for (String row : rows()) {
            if (row.contains(token)) {
                out.add(row);
            }
        }
        return out;
    }

    /** 吃額度的明細行＝slowCall＋slowBatch（banner 不算）。 */
    private static List<String> detailRows() {
        List<String> out = new ArrayList<>();
        for (String row : rows()) {
            if (row.contains("slowCall#") || row.contains("slowBatch#")) {
                out.add(row);
            }
        }
        return out;
    }

    /** 從 describeActive() 讀計數：正式碼的公開觀測面，不反射私有欄位。 */
    private static long counter(String name) {
        String line = MdcWorldSoundProbe.describeActive();
        Matcher m = Pattern.compile(String.format(COUNTER, Pattern.quote(name))).matcher(line);
        if (!m.find()) {
            expect("describeActive 應帶計數欄位 " + name + "（實際：" + line + "）", false);
            return Long.MIN_VALUE;
        }
        return Long.parseLong(m.group(1));
    }

    private static long batchMs(String row) {
        Matcher m = Pattern.compile(" ms=(\\d+)").matcher(row);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private MdcWorldSoundProbeTest() {
    }
}
