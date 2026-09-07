package zombie.core;

import java.util.Collection;
import java.util.HashMap;

import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10-C MdcTimedActionProbe 行為驗證（獨立 JVM；MODE 是 static final，三組態由 build.ps1
 * 分開驅動並以 argv 自驗）。放在 zombie.core 以直讀 Action 的 protected 欄位。
 * 覆蓋：(C) 負 duration 記錄、(B) 打斷偵測（Accept 才算／同 id 重送分流／enforce 補送
 * 在無連線時安全跳過／原委派仍執行使 action 被移除）、(R) perform false 分佈、
 * (W10-E) 同 id 撞號 victim 分類（active／anim、同人不算、發起者為臨時物件）、off 純直通。
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
        boolean wantScopePlayer = !(args.length > 1 && "scope-vanilla".equals(args[1]));
        expect("actionRemoveScope 與 argv 一致（" + (wantScopePlayer ? "player" : "vanilla") + "）",
                MdcTimedActionProbe.SCOPE_PLAYER == wantScopePlayer);

        testStart(wantMode);
        testInterrupt(wantMode);
        testPerform(wantMode);
        testCrossPlayerRemove(wantMode);
        testScopedRemove(wantMode);

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

    /**
     * W10-E：同 id 撞號。清單內甲（player 1）正 duration Accept 製作、乙（player 2）掛著的 -1
     * 動作、丙（player 3）同 id 的正 duration——乙 stop 自己的動作時 vanilla remove 會把三個都刪。
     * 走偵測入口不委派 vanilla remove（同 testInterrupt 的理由）。
     */
    @SuppressWarnings("unchecked")
    private static void testCrossPlayerRemove(int mode) {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        if (queue == null) {
            return;
        }
        queue.clear();
        try {
            byte sharedId = 77;
            NetTimedActionPacket craft = newAction(30_000L);
            craft.id = sharedId;
            craft.playerId.setID((short) 1);
            craft.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket idle = newAction(-1L);
            idle.id = sharedId;
            idle.playerId.setID((short) 2);
            idle.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket other = newAction(5_000L);
            other.id = sharedId;
            other.playerId.setID((short) 3);
            other.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket unrelated = newAction(5_000L);   // 不同 id，不得被算進去
            unrelated.playerId.setID((short) 4);
            queue.add(craft);
            queue.add(idle);
            queue.add(other);
            queue.add(unrelated);

            long calls0 = MdcTimedActionProbe.removeCallsForTest();
            long cross0 = MdcTimedActionProbe.crossPlayerRemovalsForTest();
            long active0 = MdcTimedActionProbe.crossVictimsActiveForTest();
            long anim0 = MdcTimedActionProbe.crossVictimsAnimForTest();

            // 乙取消自己的 idle 動作 → 甲的製作與丙的正 duration 是 victim（2 active、0 anim）
            MdcTimedActionProbe.inspectRemoveForTest(idle, sharedId, true);
            if (mode == MdcTimedActionProbe.MODE_OFF) {
                expect("off：remove 觀測零計數", MdcTimedActionProbe.removeCallsForTest() == calls0
                        && MdcTimedActionProbe.crossPlayerRemovalsForTest() == cross0);
            } else {
                expect("撞號：乙取消 → crossPlayerRemovals+1、active victim 恰 +2（甲的製作＋丙）、anim victim +0",
                        MdcTimedActionProbe.removeCallsForTest() == calls0 + 1
                        && MdcTimedActionProbe.crossPlayerRemovalsForTest() == cross0 + 1
                        && MdcTimedActionProbe.crossVictimsActiveForTest() == active0 + 2
                        && MdcTimedActionProbe.crossVictimsAnimForTest() == anim0);
            }

            // 甲取消自己的製作 → 乙的 -1 是 anim victim（+1）、丙是 active victim（+1）
            long cross1 = MdcTimedActionProbe.crossPlayerRemovalsForTest();
            long active1 = MdcTimedActionProbe.crossVictimsActiveForTest();
            long anim1 = MdcTimedActionProbe.crossVictimsAnimForTest();
            MdcTimedActionProbe.inspectRemoveForTest(craft, sharedId, true);
            if (mode != MdcTimedActionProbe.MODE_OFF) {
                expect("撞號：甲取消 → crossPlayerRemovals+1、anim victim +1（乙的 -1）、active victim +1（丙）",
                        MdcTimedActionProbe.crossPlayerRemovalsForTest() == cross1 + 1
                        && MdcTimedActionProbe.crossVictimsAnimForTest() == anim1 + 1
                        && MdcTimedActionProbe.crossVictimsActiveForTest() == active1 + 1);
            }

            // 無撞號：只有發起者自己持有該 id → 零 cross
            queue.clear();
            NetTimedActionPacket solo = newAction(5_000L);
            solo.playerId.setID((short) 9);
            solo.state = Transaction.TransactionState.Accept;
            queue.add(solo);
            queue.add(unrelated);
            long cross2 = MdcTimedActionProbe.crossPlayerRemovalsForTest();
            long calls2 = MdcTimedActionProbe.removeCallsForTest();
            MdcTimedActionProbe.inspectRemoveForTest(solo, solo.id, true);
            if (mode != MdcTimedActionProbe.MODE_OFF) {
                expect("無撞號：removeCalls+1、crossPlayerRemovals 不變",
                        MdcTimedActionProbe.removeCallsForTest() == calls2 + 1
                        && MdcTimedActionProbe.crossPlayerRemovalsForTest() == cross2);
            }

            // 發起者是臨時物件（GeneralActionPacket.getAction 的 copyFrom 產物）：以 playerId 判同人，不算 victim
            NetTimedActionPacket ghost = newAction(5_000L);
            ghost.id = solo.id;
            ghost.playerId.setID((short) 9);
            long cross3 = MdcTimedActionProbe.crossPlayerRemovalsForTest();
            MdcTimedActionProbe.inspectRemoveForTest(ghost, solo.id, true);
            if (mode != MdcTimedActionProbe.MODE_OFF) {
                expect("臨時發起者同 playerId：不算 victim", MdcTimedActionProbe.crossPlayerRemovalsForTest() == cross3);
            }
            expect("remove 觀測零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            queue.clear();
        }
    }

    /**
     * W10-E enforce：scope=player 時 removeById 只刪同 id＋同 playerId；取消已完成 id（清單內只有別人）
     * ＝什麼都不刪；身分不明退回 vanilla；scope=vanilla 完全不接手。走 removeScopedForTest（不委派 vanilla）。
     */
    @SuppressWarnings("unchecked")
    private static void testScopedRemove(int mode) {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        if (queue == null) {
            return;
        }
        queue.clear();
        MdcTimedActionProbe.identityByIdForTest = true;
        try {
            byte sharedId = 66;
            NetTimedActionPacket craft = newAction(30_000L);   // 甲的製作
            craft.id = sharedId; craft.playerId.setID((short) 1); craft.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket idle = newAction(-1L);        // 乙掛著的 -1
            idle.id = sharedId; idle.playerId.setID((short) 2); idle.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket idle2 = newAction(-1L);       // 乙的第二個同 id（理論上可存在）
            idle2.id = sharedId; idle2.playerId.setID((short) 2); idle2.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket unrelated = newAction(5_000L);
            unrelated.playerId.setID((short) 4);
            queue.add(craft); queue.add(idle); queue.add(idle2); queue.add(unrelated);

            long scoped0 = MdcTimedActionProbe.scopedRemovalsForTest();
            long fb0 = MdcTimedActionProbe.scopeFallbacksForTest();

            // 1. 乙 stopPlayerActions 路徑（發起者＝清單內真物件）：只刪乙的兩個，甲的製作與 unrelated 保留
            boolean took = MdcTimedActionProbe.removeScopedForTest(idle, sharedId);
            if (MdcTimedActionProbe.SCOPE_PLAYER) {
                expect("scope=player：接手、乙的同 id 兩筆被刪、甲的製作保留、不同 id 保留",
                        took && queue.contains(craft) && queue.contains(unrelated)
                        && !queue.contains(idle) && !queue.contains(idle2) && queue.size() == 2
                        && MdcTimedActionProbe.scopedRemovalsForTest() == scoped0 + 1);
            } else {
                expect("scope=vanilla：不接手、清單不動", !took && queue.size() == 4
                        && MdcTimedActionProbe.scopedRemovalsForTest() == scoped0);
            }

            // 2. GeneralActionPacket 路徑（臨時物件、同 playerId、server 已無該 id 的自己動作）：什麼都不刪
            NetTimedActionPacket ghost = newAction(5_000L);
            ghost.id = sharedId; ghost.playerId.setID((short) 3);   // 丙取消一個已完成的 id 66
            int before = queue.size();
            took = MdcTimedActionProbe.removeScopedForTest(ghost, sharedId);
            if (MdcTimedActionProbe.SCOPE_PLAYER) {
                expect("scope=player：取消已完成的 id → 接手且零移除（vanilla 在此會刪甲的製作）",
                        took && queue.size() == before && queue.contains(craft));
            } else {
                expect("scope=vanilla：不接手", !took && queue.size() == before);
            }

            // 3. 身分不明（seam 關閉＝getPlayer()==null）→ 不接手（退回 vanilla）
            MdcTimedActionProbe.identityByIdForTest = false;
            took = MdcTimedActionProbe.removeScopedForTest(ghost, sharedId);
            expect("身分不明：不接手（退回 vanilla）", !took && queue.contains(craft));
            MdcTimedActionProbe.identityByIdForTest = true;

            // 4. 甲自己取消自己的製作 → 只刪甲的
            took = MdcTimedActionProbe.removeScopedForTest(craft, sharedId);
            if (MdcTimedActionProbe.SCOPE_PLAYER) {
                expect("scope=player：發起者自己的動作被刪、其他保留", took && !queue.contains(craft) && queue.contains(unrelated));
            }
            expect("scope 路徑零 fallback、零 anomalies",
                    MdcTimedActionProbe.scopeFallbacksForTest() == fb0 && MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            MdcTimedActionProbe.identityByIdForTest = false;
            queue.clear();
        }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) failed++;
    }

    private MdcTimedActionProbeTest() {}
}
