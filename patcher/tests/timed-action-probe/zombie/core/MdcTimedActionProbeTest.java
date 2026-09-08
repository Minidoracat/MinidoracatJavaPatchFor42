package zombie.core;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.BuildActionPacket;
import zombie.network.packets.FishingActionPacket;
import zombie.network.packets.GeneralActionPacket;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.NetTimedActionPacket;
import zombie.network.server.AnimEventEmulator;

/**
 * W10-C／W10-E MdcTimedActionProbe 行為驗證（獨立 JVM；MODE／SCOPE 是 static final，各組態由
 * build.ps1 分開驅動並以 argv 自驗）。放在 zombie.core 以直讀 Action 的 protected 欄位、並能
 * 用真的 Action 子類別當 fixture。
 *
 * <p>覆蓋：(C) 負 duration 記錄、(B) 打斷偵測（Accept 才算／同 id 重送分流／enforce 補送在無
 * 連線時安全跳過）、(R) perform false 分佈、(W10-E) 取消範圍——wire 上沒有 sender、
 * processServer 綁定／nested／例外清除、同 id 撞號只刪已驗證 owner 的、空連線與身分不明不做、
 * 同機多人以實際 owner 驗、server 內部停止只信佇列內實例、stop 例外不吞但 emulator 仍清理、
 * serverStop 重入不重複移除。
 *
 * <p>取消案例走的是<b>正式路徑</b>：真的 {@link GeneralActionPacket#setReject} ／
 * {@link MdcTimedActionProbe#processServer} ／dist 內手術後的 {@code ActionManager.stop}
 * （其 headCall onStop ＋ remove 改道 removeById），不人工填發送者 playerId、不用任何正式碼旁路。
 * {@code -Dmdc.actionRemoveScope=0} 另驗原版真的會刪除其他連線同 id 的動作。
 */
public final class MdcTimedActionProbeTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        // W14 坑解：GameTime.getServerTimeMills → GameClient → ServerOptions → Rand 靜態鏈
        // 需要已播種的全域 Rand，否則 ExceptionInInitializerError 且該 JVM 內永久 NoClassDefFoundError。
        zombie.core.random.RandStandard.INSTANCE.init();
        zombie.network.GameServer.server = true;
        String want = args.length > 0 ? args[0] : "observe";
        int wantMode = switch (want) {
            case "off" -> MdcTimedActionProbe.MODE_OFF;
            case "enforce" -> MdcTimedActionProbe.MODE_ENFORCE;
            default -> MdcTimedActionProbe.MODE_OBSERVE;
        };
        expect("property 與測試模式一致（" + want + "）", MdcTimedActionProbe.MODE == wantMode);
        boolean wantScopeOn = !(args.length > 1 && "scope-vanilla".equals(args[1]));
        expect("actionRemoveScope 與 argv 一致（" + (wantScopeOn ? "connection" : "vanilla") + "）",
                MdcTimedActionProbe.SCOPE_ON == wantScopeOn);

        testStart(wantMode);
        testInterrupt(wantMode);
        testPerform(wantMode);
        testCancelWireHasNoSender();
        testDispatchBinding();
        testPlayerNameSafety();
        testCancelOnlyOwnAction(wantMode);
        if (MdcTimedActionProbe.SCOPE_ON) {
            testSlotZeroNotTreatedAsSender(wantMode);
            testNoOwnerAndEmptyConnection(wantMode);
            testSplitScreenUsesRealOwner();
            testTypedCancelCannotMutateOtherOwner();
            testRequestHeaderMustMatchConnection();
            testServerInternalStop(wantMode);
            testStopExceptionEscapesAfterCleanup();
            testReentrantServerStop();
        }

        if (failed != 0) {
            System.out.println("timed-action-probe FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("timed-action-probe OK mode=" + MdcTimedActionProbe.MODE
                + " scope=" + (MdcTimedActionProbe.SCOPE_ON ? "connection" : "vanilla"));
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
    private static void testInterrupt(int mode) throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        expect("反射取得 ActionManager.actions 成功", queue != null);
        if (queue == null) {
            return;
        }
        try {
            runInterruptCases(queue, mode);
        } finally {
            queue.clear();
        }
    }

    private static void runInterruptCases(Collection<Object> queue, int mode) throws Exception {
        queue.clear();
        IsoPlayer owner = player((short) 3, "owner");
        UdpConnection connection = conn(owner);

        // 舊動作 A（Accept 中、等了 5 秒）＋ 舊動作 R（仍 Request，不算被打斷）
        NetTimedActionPacket old = own(newAction(20_000L), owner);
        old.state = Transaction.TransactionState.Accept;
        NetTimedActionPacket pendingReq = own(newAction(20_000L), owner);
        pendingReq.state = Transaction.TransactionState.Request;
        queue.add(old);
        queue.add(pendingReq);

        // 新 Request（不同 id）
        InterruptRequest incoming = own(new InterruptRequest(), owner);
        incoming.id = nextId++;
        incoming.state = Transaction.TransactionState.Request;

        long acc0 = MdcTimedActionProbe.interruptedAcceptedForTest();
        long same0 = MdcTimedActionProbe.sameIdResendForTest();
        long sent0 = MdcTimedActionProbe.rejectsSentForTest();
        long skip0 = MdcTimedActionProbe.rejectsSkippedNoConnForTest();
        MdcTimedActionProbe.processServer(incoming, null, connection);
        expect("新 Request 的停止流程確實移出該玩家的舊動作", queue.isEmpty());
        queue.clear();
        if (mode == MdcTimedActionProbe.MODE_OFF) {
            expect("off：打斷零計數", MdcTimedActionProbe.interruptedAcceptedForTest() == acc0);
        } else {
            expect("observe/enforce：只有 Accept 中的舊動作算被打斷（恰 +1，Request 態不算）",
                    MdcTimedActionProbe.interruptedAcceptedForTest() == acc0 + 1
                    && MdcTimedActionProbe.sameIdResendForTest() == same0);
            if (mode == MdcTimedActionProbe.MODE_ENFORCE) {
                expect("enforce：玩家尚無登錄的網路連線，補送安全跳過且 state 未改",
                        MdcTimedActionProbe.rejectsSkippedNoConnForTest() == skip0 + 1
                        && MdcTimedActionProbe.rejectsSentForTest() == sent0
                        && old.state == Transaction.TransactionState.Accept);
            } else {
                expect("observe：零補送", MdcTimedActionProbe.rejectsSentForTest() == sent0
                        && MdcTimedActionProbe.rejectsSkippedNoConnForTest() == skip0);
            }
        }

        // 同 id 重送：舊 Accept 動作與新 Request 同 id → 分流為 same-id，enforce 不補送。
        NetTimedActionPacket old2 = own(newAction(20_000L), owner);
        old2.state = Transaction.TransactionState.Accept;
        queue.add(old2);
        InterruptRequest resend = own(new InterruptRequest(), owner);
        resend.id = old2.id;
        resend.state = Transaction.TransactionState.Request;
        long same1 = MdcTimedActionProbe.sameIdResendForTest();
        long skip1 = MdcTimedActionProbe.rejectsSkippedNoConnForTest();
        MdcTimedActionProbe.processServer(resend, null, connection);
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
     * W10-E 的前提事實：client 的取消封包 wire 上<b>沒有</b>發送者。
     * {@code setReject} 只填 id，playerId 留在預設值 → 5 bytes：id｜state(Reject=0)｜
     * onlineID 0x0000｜playerIndex 0xff。server 端 {@code PlayerID.parsePlayer} 會把 0x0000／-1
     * 解析成 onlineID 0，也就是 slot 0 那位玩家——所以取消者身分只能來自已認證連線。
     */
    private static void testCancelWireHasNoSender() {
        GeneralActionPacket cancel = new GeneralActionPacket();
        cancel.setReject((byte) 66);
        ByteBuffer bb = ByteBuffer.allocate(32);
        cancel.write(new ByteBufferWriter(bb));
        byte[] wire = new byte[bb.position()];
        bb.flip();
        bb.get(wire);
        expect("GeneralAction 取消 wire＝5 bytes 66|00|0000|ff（Reject ordinal 0、playerId 預設 0/-1）",
                wire.length == 5 && wire[0] == 66 && wire[1] == 0
                && wire[2] == 0 && wire[3] == 0 && wire[4] == (byte) 0xFF);
        expect("Reject 的 TransactionState ordinal 是 0（wire 第 2 byte 的依據）",
                Transaction.TransactionState.Reject.ordinal() == 0);
    }

    /**
     * processServer bridge：只有 Action 封包綁定連線；nested dispatch 各看到自己的連線且
     * 逐層恢復；封包處理拋例外時外逃且不留殘餘連線。與 MODE／SCOPE 無關，四組態都跑。
     */
    private static void testDispatchBinding() throws Exception {
        UdpConnection outer = conn(player((short) 41, "outer"));
        UdpConnection inner = conn(player((short) 42, "inner"));

        PlainPacket plain = new PlainPacket();
        MdcTimedActionProbe.processServer(plain, null, outer);
        expect("非 Action 封包：照樣委派，但不綁定連線（零成本直通）",
                plain.dispatched && plain.seen == null
                && MdcTimedActionProbe.boundConnectionForTest() == null);

        ProbeAction nested = new ProbeAction();
        ProbeAction top = new ProbeAction();
        top.dispatch = () -> MdcTimedActionProbe.processServer(nested, null, inner);
        MdcTimedActionProbe.processServer(top, null, outer);
        expect("Action 封包：dispatch 期間看得到自己的連線；nested 各看到自己的、逐層恢復；結束清空",
                top.seen == outer && nested.seen == inner
                && top.seenAfterNested == outer
                && MdcTimedActionProbe.boundConnectionForTest() == null);

        ProbeAction boom = new ProbeAction();
        boom.dispatch = () -> {
            throw new IllegalStateException("packet blew up");
        };
        boolean escaped = false;
        try {
            MdcTimedActionProbe.processServer(boom, null, outer);
        } catch (IllegalStateException e) {
            escaped = "packet blew up".equals(e.getMessage());
        }
        expect("封包處理例外：原例外外逃且 finally 已清空綁定連線",
                escaped && MdcTimedActionProbe.boundConnectionForTest() == null);
        expect("dispatch bridge 零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    /**
     * 玩家名是 client 給的字串，會進 log 行（也是 NetTimedActionGuard 的 reject 行用的那支
     * public API）：控制字元換點＝一行不會被撕成多行／假造成另一筆 log，超長截斷＝不洗版。
     */
    private static void testPlayerNameSafety() throws Exception {
        NetTimedActionPacket hostile = own(newAction(1_000L), player((short) 3, "eve\r\n[FAKE] admin|login"));
        String loggedName = MdcTimedActionProbe.playerNameOf(hostile);
        expect("玩家名不能注入新行、方括號欄位或連線成員分隔符",
                loggedName.chars().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)
                        || c == '[' || c == ']' || c == '|'));
    }

    /**
     * 撞號主場景：甲（onlineID 5，另一條連線）正在製作、乙（onlineID 7）掛著同 id 的動作。
     * 乙送真的 GeneralAction 取消 → 只有乙自己的被移除，甲的製作原封不動（vanilla 在此會兩個都刪）。
     */
    @SuppressWarnings("unchecked")
    private static void testCancelOnlyOwnAction(int mode) throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        queue.clear();
        try {
            byte sharedId = 66;
            IsoPlayer alice = player((short) 5, "alice");
            IsoPlayer bob = player((short) 7, "bob");
            UdpConnection bobConn = conn(bob);

            NetTimedActionPacket craft = own(newAction(30_000L), alice);
            craft.id = sharedId;
            craft.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket bobIdle = own(newAction(-1L), bob);
            bobIdle.id = sharedId;
            bobIdle.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket bobOther = own(newAction(5_000L), bob);
            bobOther.id = (byte) (sharedId + 1);   // 乙的另一個 id：不得被牽連
            bobOther.state = Transaction.TransactionState.Accept;
            queue.add(craft);
            queue.add(bobIdle);
            queue.add(bobOther);

            long calls0 = MdcTimedActionProbe.removeCallsForTest();
            long handled0 = MdcTimedActionProbe.cancelsHandledForTest();
            long removed0 = MdcTimedActionProbe.ownedRemovedForTest();
            long spared0 = MdcTimedActionProbe.sparedOtherForTest();
            long multi0 = MdcTimedActionProbe.removeMultiHitForTest();

            cancelFromConnection(bobConn, sharedId);

            if (!MdcTimedActionProbe.SCOPE_ON) {
                expect("scope=vanilla 負對照：原版會刪除雙方同 id，其他 id 保留",
                        queue.size() == 1 && queue.contains(bobOther)
                        && !queue.contains(bobIdle) && !queue.contains(craft));
                if (mode != MdcTimedActionProbe.MODE_OFF) {
                    expect("scope=vanilla：只有進入計數增加，不謊稱接手、移除或保護成功",
                            MdcTimedActionProbe.removeCallsForTest() == calls0 + 1
                            && MdcTimedActionProbe.cancelsHandledForTest() == handled0
                            && MdcTimedActionProbe.ownedRemovedForTest() == removed0
                            && MdcTimedActionProbe.sparedOtherForTest() == spared0);
                }
                expect("scope=vanilla 路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
                return;
            }

            expect("乙取消同 id：只有乙自己的被移除，甲的製作與乙的其他 id 全留",
                    !queue.contains(bobIdle) && queue.contains(craft) && queue.contains(bobOther)
                    && queue.size() == 2);
            if (mode == MdcTimedActionProbe.MODE_OFF) {
                expect("off：取消照樣只動自己的（scope 獨立於 MODE），但零計數",
                        MdcTimedActionProbe.removeCallsForTest() == calls0
                        && MdcTimedActionProbe.cancelsHandledForTest() == handled0
                        && MdcTimedActionProbe.ownedRemovedForTest() == removed0
                        && MdcTimedActionProbe.sparedOtherForTest() == spared0);
            } else {
                expect("計數：removeById 進入 +1／接手 +1／實際移除 +1／保留他人同 id +1／同 id 多筆命中 +1",
                        MdcTimedActionProbe.removeCallsForTest() == calls0 + 1
                        && MdcTimedActionProbe.cancelsHandledForTest() == handled0 + 1
                        && MdcTimedActionProbe.ownedRemovedForTest() == removed0 + 1
                        && MdcTimedActionProbe.sparedOtherForTest() == spared0 + 1
                        && MdcTimedActionProbe.removeMultiHitForTest() == multi0 + 1);
            }

            // 再取消同一 id：乙自己的動作已不存在，不得碰甲的動作。
            long noMatch0 = MdcTimedActionProbe.noOwnedMatchForTest();
            int before = queue.size();
            cancelFromConnection(bobConn, craft.id);
            expect("取消只有他人持有的 id：零移除、甲的製作仍在（vanilla 在此會刪掉它）",
                    queue.size() == before && queue.contains(craft));
            if (mode != MdcTimedActionProbe.MODE_OFF) {
                expect("計數：無自己的 matching action +1", MdcTimedActionProbe.noOwnedMatchForTest() == noMatch0 + 1);
            }
            expect("取消路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            queue.clear();
        }
    }

    /**
     * slot 0 陷阱：vanilla parse 會把取消封包的 playerId 解析成 onlineID 0 的玩家。測兩種：
     * 0 號玩家在線（封包帶著他的 IsoPlayer）與不在線（player 為 null、onlineID 0）——
     * 兩種都不得把 0 號的動作當成取消目標，也不得因身分不明退回全域刪除。
     */
    @SuppressWarnings("unchecked")
    private static void testSlotZeroNotTreatedAsSender(int mode) throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        queue.clear();
        try {
            byte sharedId = 88;
            IsoPlayer slotZero = player((short) 0, "slot0");
            IsoPlayer attacker = player((short) 12, "attacker");
            UdpConnection attackerConn = conn(attacker);

            NetTimedActionPacket slotZeroCraft = own(newAction(30_000L), slotZero);
            slotZeroCraft.id = sharedId;
            slotZeroCraft.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket attackerIdle = own(newAction(-1L), attacker);
            attackerIdle.id = sharedId;
            attackerIdle.state = Transaction.TransactionState.Accept;
            queue.add(slotZeroCraft);
            queue.add(attackerIdle);

            // 真序列化再 parse；0 號在場時，原版 decoder 的確會填入錯誤的 0 號玩家。
            zombie.network.GameServer.IDToPlayerMap.put((short) 0, slotZero);
            GeneralActionPacket cancel = decodedCancel(sharedId, attackerConn);
            expect("真取消封包被原版解析成 0 號玩家（不是發送者）", cancel.playerId.getPlayer() == slotZero);
            MdcTimedActionProbe.processServer(cancel, null, attackerConn);
            expect("0 號在線：封包 playerId 指向 0 號也不算 sender —— 只刪送封包那條連線自己的",
                    queue.contains(slotZeroCraft) && !queue.contains(attackerIdle) && queue.size() == 1);

            // (2) 0 號不在線：playerId 解析不到玩家（player=null、onlineID 0）→ 仍不得全域刪
            NetTimedActionPacket attackerIdle2 = own(newAction(-1L), attacker);
            attackerIdle2.id = sharedId;
            attackerIdle2.state = Transaction.TransactionState.Accept;
            queue.add(attackerIdle2);
            long spared0 = MdcTimedActionProbe.sparedOtherForTest();
            zombie.network.GameServer.IDToPlayerMap.remove((short) 0);
            GeneralActionPacket ghostCancel = decodedCancel(sharedId, attackerConn);
            expect("0 號不在場時原版 player 解析為 null", ghostCancel.playerId.getPlayer() == null);
            MdcTimedActionProbe.processServer(ghostCancel, null, attackerConn);
            expect("0 號不在線：身分解析不到也只刪自己連線的，0 號的製作仍在",
                    queue.contains(slotZeroCraft) && !queue.contains(attackerIdle2) && queue.size() == 1);
            if (mode != MdcTimedActionProbe.MODE_OFF) {
                expect("計數：0 號的製作被記成保留他人同 id（兩次各 +1）",
                        MdcTimedActionProbe.sparedOtherForTest() == spared0 + 1);
            }
            expect("slot0 路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            queue.clear();
        }
    }

    /** 連線上沒有任何 player（players 全 null／陣列 null）：一律不刪，不得退回全域刪除。 */
    @SuppressWarnings("unchecked")
    private static void testNoOwnerAndEmptyConnection(int mode) throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        queue.clear();
        try {
            byte sharedId = 99;
            IsoPlayer alice = player((short) 5, "alice");
            NetTimedActionPacket craft = own(newAction(30_000L), alice);
            craft.id = sharedId;
            craft.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket ownerless = newAction(5_000L);   // playerId 從未 set：player=null、id 0
            ownerless.id = sharedId;
            ownerless.state = Transaction.TransactionState.Accept;
            queue.add(craft);
            queue.add(ownerless);

            long unknown0 = MdcTimedActionProbe.unknownRefusedForTest();
            cancelFromConnection(conn(), sharedId);                       // players 全 null
            expect("空連線（players 全 null）：兩筆同 id 全部保留", queue.size() == 2
                    && queue.contains(craft) && queue.contains(ownerless));

            UdpConnection nullArray = alloc(UdpConnection.class);         // players 陣列本身 null
            cancelFromConnection(nullArray, sharedId);
            expect("players 陣列為 null：一樣零移除、零例外", queue.size() == 2);
            if (mode != MdcTimedActionProbe.MODE_OFF) {
                expect("空連線的兩次取消均記為身分不明",
                        MdcTimedActionProbe.unknownRefusedForTest() == unknown0 + 2);
            }
            ProbeAction noConnection = new ProbeAction();
            noConnection.id = sharedId;
            noConnection.dispatch = () -> ActionManager.stop(noConnection);
            queue.add(noConnection);
            MdcTimedActionProbe.processServer(noConnection, null, null);
            expect("封包缺少連線：即使 packet 在 queue 內也不能冒充 server 內部停止",
                    queue.contains(noConnection) && queue.contains(craft) && queue.size() == 3);
            expect("空連線路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            queue.clear();
        }
    }

    /**
     * 同機多人（分割畫面）：一條連線上兩位玩家。要驗的是「這條連線上的<b>任一實際 owner</b>都算
     * 自己的，別條連線的同 id 一律不算」——身分是逐個比對 {@code connection.players[i]} 的真
     * player／onlineID 得來的，不是猜 slot 0，也不是讀封包的 playerId。
     */
    @SuppressWarnings("unchecked")
    private static void testSplitScreenUsesRealOwner() throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        queue.clear();
        try {
            byte sharedId = 55;
            IsoPlayer host = player((short) 4, "host");
            IsoPlayer couch = player((short) 5, "couch");
            IsoPlayer remote = player((short) 9, "remote");
            UdpConnection splitConn = conn(host, couch);

            NetTimedActionPacket couchCraft = own(newAction(30_000L), couch);   // index 1 的玩家
            couchCraft.id = sharedId;
            couchCraft.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket remoteCraft = own(newAction(30_000L), remote); // 別條連線同 id
            remoteCraft.id = sharedId;
            remoteCraft.state = Transaction.TransactionState.Accept;
            queue.add(couchCraft);
            queue.add(remoteCraft);

            cancelFromConnection(splitConn, sharedId);
            expect("同機多人：index 1 的實際 owner 也算自己的（被移除），別條連線的同 id 保留",
                    !queue.contains(couchCraft) && queue.contains(remoteCraft) && queue.size() == 1);

            // 缺席 owner 的數字 id 不能代替實際連線身分。
            NetTimedActionPacket byIdOnly = newAction(30_000L);
            byIdOnly.id = sharedId;
            byIdOnly.playerId.setID((short) 5);   // player 物件為 null，只有 onlineID
            byIdOnly.state = Transaction.TransactionState.Accept;
            queue.add(byIdOnly);
            cancelFromConnection(splitConn, sharedId);
            expect("只有相同 onlineID 但無 owner，不得認領為本連線",
                    queue.contains(byIdOnly) && queue.contains(remoteCraft) && queue.size() == 2);

            // onlineID 被重用時，舊 player 物件不屬於當前連線。
            NetTimedActionPacket stale = own(newAction(30_000L), player((short) 5, "couch-old-object"));
            stale.id = sharedId;
            stale.state = Transaction.TransactionState.Accept;
            queue.add(stale);
            cancelFromConnection(splitConn, sharedId);
            expect("過期 owner 與新玩家同 onlineID，也不得跨實例認領",
                    queue.contains(stale) && queue.contains(byIdOnly) && queue.contains(remoteCraft) && queue.size() == 3);
            expect("同機多人路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            queue.clear();
        }
    }

    /**
     * server 內部停止（沒有封包連線）：只信「佇列裡的同一個 Action 實例」，
     * 其餘一律拒做——不得因為身分不明就退回 vanilla 的整表刪除。
     */
    @SuppressWarnings("unchecked")
    private static void testServerInternalStop(int mode) throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        queue.clear();
        try {
            byte sharedId = 44;
            IsoPlayer alice = player((short) 5, "alice");
            IsoPlayer bob = player((short) 7, "bob");
            NetTimedActionPacket trusted = own(newAction(30_000L), alice);
            trusted.id = sharedId;
            trusted.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket bystander = own(newAction(30_000L), bob);
            bystander.id = sharedId;
            bystander.state = Transaction.TransactionState.Accept;
            queue.add(trusted);
            queue.add(bystander);

            long removed0 = MdcTimedActionProbe.ownedRemovedForTest();
            ActionManager.stop(trusted);   // dist 內手術後的 stop：headCall onStop ＋ remove 改道
            expect("server 內部停止佇列內實例：只有那一個被移除，同 id 的旁人保留",
                    !queue.contains(trusted) && queue.contains(bystander) && queue.size() == 1);

            long unknown0 = MdcTimedActionProbe.unknownRefusedForTest();
            NetTimedActionPacket forged = own(newAction(30_000L), bob);   // 不在佇列內的同 id 物件
            forged.id = sharedId;
            ActionManager.stop(forged);
            expect("身分不明（無連線＋非佇列內實例）：拒做，旁人的同 id 仍在",
                    queue.contains(bystander) && queue.size() == 1);
            if (mode != MdcTimedActionProbe.MODE_OFF) {
                expect("計數：實際移除 +1、身分不明 +1",
                        MdcTimedActionProbe.ownedRemovedForTest() == removed0 + 1
                        && MdcTimedActionProbe.unknownRefusedForTest() == unknown0 + 1);
            }
            expect("server 內部停止零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            queue.clear();
        }
    }
    @SuppressWarnings("unchecked")
    private static void testTypedCancelCannotMutateOtherOwner() throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        IsoPlayer alice = player((short) 5, "alice");
        IsoPlayer bob = player((short) 7, "bob");
        UdpConnection bobConn = conn(bob);
        zombie.network.GameServer.IDToPlayerMap.put((short) 5, alice);
        try {
            for (Class<?> kind : new Class<?>[]{NetTimedActionPacket.class, BuildActionPacket.class, EventCapturingFishingPacket.class}) {
                queue.clear();
                NetTimedActionPacket victim = own(newAction(30_000L), alice);
                victim.id = 66;
                victim.state = Transaction.TransactionState.Accept;
                victim.endTime = zombie.GameTime.getServerTimeMills() + 30_000L;
                NetTimedActionPacket mine = own(newAction(30_000L), bob);
                mine.id = victim.id;
                queue.add(victim);
                queue.add(mine);
                Action client = own((Action) kind.getDeclaredConstructor().newInstance(), alice);
                client.id = victim.id;
                client.state = Transaction.TransactionState.Reject;
                if (client instanceof FishingAction fishing) fishing.contentFlag = 8; // flagUpdateBobberParameters
                ByteBuffer wire = ByteBuffer.allocate(128);
                ((INetworkPacket) client).write(new ByteBufferWriter(wire));
                wire.flip();
                Action packet = (Action) kind.getDeclaredConstructor().newInstance();
                ((INetworkPacket) packet).parse(new ByteBufferReader(wire), bobConn);
                expect(kind.getSimpleName() + "：真 wire 可解析出其他玩家的 header",
                        packet.playerId.getPlayer() == alice);
                MdcTimedActionProbe.processServer((INetworkPacket) packet, null, bobConn);
                ActionManager.update();
                expect(kind.getSimpleName() + "：取消只移出本連線；其他人的 Accept 不被先改成 Reject 後於下幀刪掉",
                        queue.size() == 1 && queue.contains(victim) && !queue.contains(mine)
                        && victim.state == Transaction.TransactionState.Accept);
                if (packet instanceof EventCapturingFishingPacket fishing) {
                    expect("Fishing Reject＋bobber flag 不為其他玩家產生 Lua 更新事件資料",
                            fishing.eventPlayer == null);
                }
            }
        } finally {
            queue.clear();
            zombie.network.GameServer.IDToPlayerMap.remove((short) 5);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testRequestHeaderMustMatchConnection() throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        IsoPlayer alice = player((short) 5, "alice");
        IsoPlayer bob = player((short) 7, "bob");
        UdpConnection bobConn = conn(bob);
        zombie.network.GameServer.IDToPlayerMap.put((short) 5, alice);
        try {
            for (byte playerIndex : new byte[]{0, -1}) {
                queue.clear();
                NetTimedActionPacket victim = own(newAction(30_000L), alice);
                victim.id = 67;
                victim.state = Transaction.TransactionState.Accept;
                queue.add(victim);
                NetTimedActionPacket request = new NetTimedActionPacket();
                request.id = victim.id;
                request.state = Transaction.TransactionState.Request;
                ByteBuffer header = ByteBuffer.allocate(3).putShort(alice.getOnlineID()).put(playerIndex).flip();
                request.playerId.parse(new ByteBufferReader(header), bobConn);
                expect("Request header index=" + playerIndex + "：真 decoder 解析本連線或其他連線 owner",
                        request.playerId.getID() == alice.getOnlineID()
                        && request.playerId.getPlayer() == (playerIndex == 0 ? bob : alice));
                boolean escaped = false;
                try {
                    // action=null 走原版 initial Reject；缺 guard 會先 copyFrom 改壞 victim。
                    MdcTimedActionProbe.processServer(request, null, bobConn);
                } catch (RuntimeException e) {
                    escaped = true;
                }
                expect("不可信 Request index=" + playerIndex + " 在 getAction/copyFrom 前拒絕，旁人動作不變",
                        !escaped && queue.size() == 1 && queue.contains(victim)
                        && victim.state == Transaction.TransactionState.Accept
                        && victim.playerId.getPlayer() == alice && victim.action != null);
            }
        } finally {
            queue.clear();
            zombie.network.GameServer.IDToPlayerMap.remove((short) 5);
        }
    }


    /**
     * {@code stop()}（Lua serverStop）拋例外：原例外必須外逃（不吞成安靜 no-op），
     * 但該動作已移出清單、AnimEventEmulator 也已在 finally 清乾淨，且 bridge 不留殘餘連線。
     * 用 {@code action == null} 的 NetTimedAction 觸發真的 vanilla NPE（{@code action.rawget}）。
     */
    @SuppressWarnings("unchecked")
    private static void testStopExceptionEscapesAfterCleanup() throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        queue.clear();
        ArrayList<?> events = emulatorEvents();
        events.clear();
        try {
            byte sharedId = 33;
            IsoPlayer bob = player((short) 7, "bob");
            IsoPlayer alice = player((short) 5, "alice");
            UdpConnection bobConn = conn(bob);
            NetTimedActionPacket broken = own(newAction(30_000L), bob);
            broken.id = sharedId;
            broken.state = Transaction.TransactionState.Accept;
            broken.action = null;   // vanilla stop() 會 NPE 在 this.action.rawget("serverStop")
            NetTimedActionPacket bystander = own(newAction(30_000L), alice);
            bystander.id = sharedId;
            bystander.state = Transaction.TransactionState.Accept;
            NetTimedActionPacket second = own(newAction(30_000L), bob);
            second.id = sharedId;
            // broken 必須排第一，才能證明它拋例外後仍會清掉後續 owned 動作。
            queue.add(broken);
            queue.add(second);
            queue.add(bystander);
            AnimEventEmulator.getInstance().create(broken, 1_000L, false, "test", "param");
            AnimEventEmulator.getInstance().create(second, 1_000L, false, "test", "param");
            expect("前置：兩筆本連線動作各有 emulator 事件", events.size() == 2);

            boolean escaped = false;
            try {
                cancelFromConnection(bobConn, sharedId);
            } catch (NullPointerException e) {
                escaped = true;
            }
            expect("首筆 stop 例外原樣外逃，但整批已移出者的 emulator 仍全部清掉、旁人不動",
                    escaped && !queue.contains(broken) && !queue.contains(second) && queue.contains(bystander)
                    && events.isEmpty()
                    && MdcTimedActionProbe.boundConnectionForTest() == null);
        } finally {
            queue.clear();
            events.clear();
        }
    }

    /**
     * serverStop 重入：{@code stop()} 內再呼一次 {@code ActionManager.stop(this)}。
     * 因為選取是先快照、再整批 removeAll、最後才逐筆 stop()，重入時該動作已不在清單裡
     * ——不會重複移除也不會遞迴爆掉。
     */
    @SuppressWarnings("unchecked")
    private static void testReentrantServerStop() throws Exception {
        Collection<Object> queue = (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
        queue.clear();
        try {
            ProbeAction reentrant = new ProbeAction();
            reentrant.id = 22;
            reentrant.state = Transaction.TransactionState.Accept;
            reentrant.stopBody = () -> ActionManager.stop(reentrant);
            queue.add(reentrant);

            ActionManager.stop(reentrant);
            expect("serverStop 重入：只移除一次、stop() 只跑一次、無遞迴",
                    queue.isEmpty() && reentrant.stops == 1);
            expect("重入路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
        } finally {
            queue.clear();
        }
    }

    // ---- fixture ----

    /** 走正式路徑：真 GeneralAction 取消封包 ＋ processServer bridge 綁定已認證連線。 */
    private static void cancelFromConnection(UdpConnection connection, byte id) {
        MdcTimedActionProbe.processServer(decodedCancel(id, connection), null, connection);
    }

    private static GeneralActionPacket decodedCancel(byte id, UdpConnection connection) {
        GeneralActionPacket client = new GeneralActionPacket();
        client.setReject(id);
        ByteBuffer wire = ByteBuffer.allocate(32);
        client.write(new ByteBufferWriter(wire));
        wire.flip();
        GeneralActionPacket server = new GeneralActionPacket();
        server.parse(new ByteBufferReader(wire), connection);
        return server;
    }

    /** 讓佇列動作的 owner 是真的 IsoPlayer（比照 PlayerID.set：onlineID ＋ player 參照）。 */
    private static <T extends Action> T own(T action, IsoPlayer player) throws Exception {
        action.playerId.setID(player.getOnlineID());
        Field f = findField(action.playerId.getClass(), "player");
        f.setAccessible(true);
        f.set(action.playerId, player);
        return action;
    }

    private static IsoPlayer player(short onlineId, String username) throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        p.onlineId = onlineId;
        p.username = username;
        Field ecs = findField(p.getClass(), "ecsComponentMap");
        ecs.setAccessible(true);
        ecs.set(p, new HashMap<>());
        return p;
    }

    /** 最小連線：只補 removeById 會讀的 players（其餘 index 留 null）。 */
    private static UdpConnection conn(IsoPlayer... players) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        c.players = new IsoPlayer[4];
        for (int i = 0; i < players.length; i++) {
            c.players[i] = players[i];
        }
        return c;
    }

    private static ArrayList<?> emulatorEvents() throws Exception {
        Field f = AnimEventEmulator.class.getDeclaredField("events");
        f.setAccessible(true);
        return (ArrayList<?>) f.get(AnimEventEmulator.getInstance());
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    /** 隔離 Request 後續的 Lua start/send；真 bridge 與停止流程完整執行，不注入 helper 狀態。 */
    private static final class InterruptRequest extends NetTimedActionPacket {
        @Override
        public void processServer(PacketTypes.PacketType type, UdpConnection connection) {
            MdcTimedActionProbe.stopPlayerActions(playerId);
        }
    }

    /** 只旁聽原版真正交給 Lua event 的資料；getLuaTable 本身仍完整執行原版。 */
    private static final class EventCapturingFishingPacket extends FishingActionPacket {
        Object eventPlayer;

        @Override
        public se.krka.kahlua.vm.KahluaTable getLuaTable() {
            se.krka.kahlua.vm.KahluaTable data = super.getLuaTable();
            if (data != null) eventPlayer = data.rawget("player");
            return data;
        }
    }

    /** 非 Action 的封包：bridge 必須照樣委派、且不綁定連線。 */
    private static final class PlainPacket implements INetworkPacket {
        boolean dispatched;
        UdpConnection seen;

        @Override
        public void parse(ByteBufferReader b, IConnection connection) {
        }

        @Override
        public void write(ByteBufferWriter b) {
        }

        @Override
        public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
            this.dispatched = true;
            this.seen = MdcTimedActionProbe.boundConnectionForTest();
        }
    }

    /** 真的 Action 封包（同 package 才能實作 package-private 的抽象方法），行為由測試腳本注入。 */
    private static final class ProbeAction extends Action implements INetworkPacket {
        Runnable dispatch;
        Runnable stopBody;
        UdpConnection seen;
        UdpConnection seenAfterNested;
        int stops;

        @Override
        public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
            this.seen = MdcTimedActionProbe.boundConnectionForTest();
            if (this.dispatch != null) {
                this.dispatch.run();
            }
            this.seenAfterNested = MdcTimedActionProbe.boundConnectionForTest();
        }

        @Override
        void stop() {
            this.stops++;
            if (this.stopBody != null) {
                this.stopBody.run();
            }
        }

        @Override
        float getDuration() {
            return 0.0F;
        }

        @Override
        void start() {
        }

        @Override
        boolean isValid() {
            return false;
        }

        @Override
        void update() {
        }

        @Override
        boolean perform() {
            return false;
        }

        @Override
        boolean isUsingTimeout() {
            return false;
        }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) failed++;
    }

    private MdcTimedActionProbeTest() {}
}
