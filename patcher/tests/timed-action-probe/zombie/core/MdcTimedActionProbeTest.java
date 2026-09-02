package zombie.core;

import java.util.Collection;
import java.util.HashMap;

import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10-C MdcTimedActionProbe 行為驗證（獨立 JVM；MODE 是 static final，三組態由 build.ps1
 * 分開驅動並以 argv 自驗）。放在 zombie.core 以直讀 Action 的 protected 欄位。
 * 覆蓋：(C) 負 duration 記錄、(B) 打斷偵測（Accept 才算／同 id 重送分流／enforce 補送
 * 在無連線時安全跳過／原委派仍執行使 action 被移除）、(R) perform false 分佈、off 純直通。
 */
public final class MdcTimedActionProbeTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        // W14 坑解：GameTime.getServerTimeMills → GameClient → ServerOptions → Rand 靜態鏈
        // 需要已播種的全域 Rand，否則 ExceptionInInitializerError 且該 JVM 內永久 NoClassDefFoundError。
        zombie.core.random.RandStandard.INSTANCE.init();
        String want = args.length > 0 ? args[0] : "observe";
        int wantMode = switch (want) {
            case "off" -> MdcTimedActionProbe.MODE_OFF;
            case "enforce" -> MdcTimedActionProbe.MODE_ENFORCE;
            default -> MdcTimedActionProbe.MODE_OBSERVE;
        };
        expect("property 與測試模式一致（" + want + "）", MdcTimedActionProbe.MODE == wantMode);

        testStart(wantMode);
        testInterrupt(wantMode);
        testPerform(wantMode);

        if (failed != 0) {
            System.out.println("timed-action-probe FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("timed-action-probe OK mode=" + MdcTimedActionProbe.MODE);
    }

    private static byte nextId = 10;

    private static NetTimedActionPacket newAction(long duration) {
        NetTimedActionPacket a = new NetTimedActionPacket();
        a.id = nextId++;   // vanilla 只在 client sendAction 時分配 id；測試顯式給不同 id
        a.action = new KahluaTableImpl(new HashMap<>());   // stop()/perform() 會 rawget，需非 null
        a.type = "ISTestAction";
        a.name = "test";
        a.duration = duration;
        a.startTime = 1_000L;
        a.endTime = duration < 0 ? a.startTime + 1_800_000L : a.startTime + duration;
        return a;
    }

    private static void testStart(int mode) {
        long neg0 = MdcTimedActionProbe.negativeDurationForTest();
        long starts0 = MdcTimedActionProbe.startsForTest();
        MdcTimedActionProbe.onStart(newAction(2_000L));
        MdcTimedActionProbe.onStart(newAction(-1L));
        if (mode == MdcTimedActionProbe.MODE_OFF) {
            expect("off：onStart 零計數", MdcTimedActionProbe.startsForTest() == starts0
                    && MdcTimedActionProbe.negativeDurationForTest() == neg0);
        } else {
            expect("observe/enforce：starts+2、負 duration 恰 +1（30 分鐘路徑指紋）",
                    MdcTimedActionProbe.startsForTest() == starts0 + 2
                    && MdcTimedActionProbe.negativeDurationForTest() == neg0 + 1);
        }
        expect("onStart 零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    @SuppressWarnings("unchecked")
    private static void testInterrupt(int mode) {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        expect("反射取得 ActionManager.actions 成功", queue != null);
        if (queue == null) {
            return;
        }
        // 走 helper 的偵測入口、不委派 vanilla：vanilla remove 讀 GameServer.server/GameClient.client
        // 會觸發它們的 static init（測試 JVM 內會炸）。委派存在性由 SmokeCheck helper 契約釘死。
        try {
            runInterruptCases(queue, mode);
        } finally {
            queue.clear();
        }
    }

    private static void runInterruptCases(Collection<Object> queue, int mode) {
        queue.clear();

        // 舊動作 A（Accept 中、等了 5 秒）＋ 舊動作 R（仍 Request，不算被打斷）
        NetTimedActionPacket old = newAction(20_000L);
        old.state = Transaction.TransactionState.Accept;
        NetTimedActionPacket pendingReq = newAction(20_000L);
        pendingReq.state = Transaction.TransactionState.Request;
        queue.add(old);
        queue.add(pendingReq);

        // 新 Request（不同 id）
        NetTimedActionPacket incoming = newAction(3_000L);
        MdcTimedActionProbe.setCurrentRequestForTest(incoming);

        long acc0 = MdcTimedActionProbe.interruptedAcceptedForTest();
        long same0 = MdcTimedActionProbe.sameIdResendForTest();
        long sent0 = MdcTimedActionProbe.rejectsSentForTest();
        long skip0 = MdcTimedActionProbe.rejectsSkippedNoConnForTest();
        MdcTimedActionProbe.inspectInterruptedForTest(old.playerId);
        queue.clear();
        if (mode == MdcTimedActionProbe.MODE_OFF) {
            expect("off：打斷零計數", MdcTimedActionProbe.interruptedAcceptedForTest() == acc0);
        } else {
            expect("observe/enforce：只有 Accept 中的舊動作算被打斷（恰 +1，Request 態不算）",
                    MdcTimedActionProbe.interruptedAcceptedForTest() == acc0 + 1
                    && MdcTimedActionProbe.sameIdResendForTest() == same0);
            if (mode == MdcTimedActionProbe.MODE_ENFORCE) {
                expect("enforce：無玩家/連線 → 補送安全跳過（rejectsSkippedNoConn+1、零 sent、state 未改）",
                        MdcTimedActionProbe.rejectsSkippedNoConnForTest() == skip0 + 1
                        && MdcTimedActionProbe.rejectsSentForTest() == sent0
                        && old.state == Transaction.TransactionState.Accept);
            } else {
                expect("observe：零補送", MdcTimedActionProbe.rejectsSentForTest() == sent0
                        && MdcTimedActionProbe.rejectsSkippedNoConnForTest() == skip0);
            }
        }

        // 同 id 重送：舊 Accept 動作與新 Request 同 id → 分流為 same-id，enforce 不補送。
        NetTimedActionPacket old2 = newAction(20_000L);
        old2.state = Transaction.TransactionState.Accept;
        queue.add(old2);
        NetTimedActionPacket resend = newAction(3_000L);
        resend.id = old2.id;
        MdcTimedActionProbe.setCurrentRequestForTest(resend);
        long same1 = MdcTimedActionProbe.sameIdResendForTest();
        long skip1 = MdcTimedActionProbe.rejectsSkippedNoConnForTest();
        MdcTimedActionProbe.inspectInterruptedForTest(old2.playerId);
        if (mode != MdcTimedActionProbe.MODE_OFF) {
            expect("同 id 重送：sameIdResend+1 且 enforce 不嘗試補送",
                    MdcTimedActionProbe.sameIdResendForTest() == same1 + 1
                    && MdcTimedActionProbe.rejectsSkippedNoConnForTest() == skip1);
        }
        expect("打斷路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    private static void testPerform(int mode) {
        // 空 Lua 環境下 NetTimedAction.perform 走 vanilla 的 catch(Exception) → false。
        NetTimedActionPacket a = newAction(1_000L);
        long calls0 = MdcTimedActionProbe.performCallsForTest();
        long false0 = MdcTimedActionProbe.performFalseForTest();
        boolean r = MdcTimedActionProbe.perform(a);
        expect("perform 委派回傳 vanilla 結果（此環境為 false）", !r);
        if (mode == MdcTimedActionProbe.MODE_OFF) {
            expect("off：perform 零計數", MdcTimedActionProbe.performCallsForTest() == calls0);
        } else {
            expect("observe/enforce：performCalls+1、performFalse+1",
                    MdcTimedActionProbe.performCallsForTest() == calls0 + 1
                    && MdcTimedActionProbe.performFalseForTest() == false0 + 1);
        }
        expect("perform 路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) failed++;
    }

    private MdcTimedActionProbeTest() {}
}
