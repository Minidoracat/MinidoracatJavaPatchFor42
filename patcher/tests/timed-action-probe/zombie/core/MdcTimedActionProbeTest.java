package zombie.core;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
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

/**
 * W10-C（B）＋動作封包身分檢查 MdcTimedActionProbe 行為驗證（獨立 JVM；MODE／OWNER_CHECK 是 static final，
 * 各組態由 build.ps1 分開驅動並以 argv 自驗）。放在 zombie.core 以直讀 Action 的 protected 欄位。
 *
 * <p>覆蓋：(B) 打斷偵測（Accept 才算／同 id 重送分流／enforce 補送在無連線時安全跳過）、bridge 的 Request
 * 上下文綁定／nested／例外清除、四種 Action 封包的 owner 檢查（真 wire：他人 onlineID 以 index -1 解析成他人，
 * 或以本連線 index 帶他人 onlineID）、合法取消與 Fishing Reject 後續事件照原版執行、同機多人、過期 owner、
 * 空連線。{@code -Dmdc.actionOwnerCheck=0} 另驗原版真的會讓偽造封包取消／改寫他人動作。
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
        boolean wantOwnerCheck = !(args.length > 1 && "owner-off".equals(args[1]));
        expect("actionOwnerCheck 與 argv 一致（" + (wantOwnerCheck ? "on" : "off") + "）",
                MdcTimedActionProbe.OWNER_CHECK == wantOwnerCheck);

        testInterrupt(wantMode);
        testDispatchBinding();
        testSafeName();
        testForgedCancel(wantMode);
        testLegitCancelRunsVanilla();
        testForgedRequest();
        if (MdcTimedActionProbe.OWNER_CHECK) {
            testConnectionMembership(wantMode);
        }

        if (failed != 0) {
            System.out.println("timed-action-probe FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("timed-action-probe OK mode=" + MdcTimedActionProbe.MODE
                + " ownerCheck=" + (MdcTimedActionProbe.OWNER_CHECK ? "on" : "off"));
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
        a.endTime = a.startTime + duration;
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> queue() {
        return (Collection<Object>) MdcTimedActionProbe.actionsQueueForTest();
    }

    private static void testInterrupt(int mode) throws Exception {
        Collection<Object> queue = queue();
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

        // 舊動作 A（Accept 中）＋ 舊動作 R（仍 Request，不算被打斷）
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

        long calls0 = MdcTimedActionProbe.interruptCallsForTest();
        long acc0 = MdcTimedActionProbe.interruptedAcceptedForTest();
        long same0 = MdcTimedActionProbe.sameIdResendForTest();
        long sent0 = MdcTimedActionProbe.rejectsSentForTest();
        long skip0 = MdcTimedActionProbe.rejectsSkippedNoConnForTest();
        MdcTimedActionProbe.processServer(incoming, null, connection);
        expect("新 Request 經 bridge 派送，原版停止流程確實移出該玩家的舊動作", incoming.dispatched && queue.isEmpty());
        if (mode == MdcTimedActionProbe.MODE_OFF) {
            expect("off：打斷零計數", MdcTimedActionProbe.interruptedAcceptedForTest() == acc0
                    && MdcTimedActionProbe.interruptCallsForTest() == calls0);
        } else {
            expect("observe/enforce：只有 Accept 中的舊動作算被打斷（恰 +1，Request 態不算）",
                    MdcTimedActionProbe.interruptCallsForTest() == calls0 + 1
                    && MdcTimedActionProbe.interruptedAcceptedForTest() == acc0 + 1
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

    /**
     * processServer bridge：非 Action 封包照樣委派且不綁定；NetTimedAction 封包派送期間綁定為 Request 上下文，
     * nested dispatch 各看到自己的並逐層恢復；封包處理拋例外時外逃且不留殘餘。
     */
    private static void testDispatchBinding() throws Exception {
        IsoPlayer outerOwner = player((short) 41, "outer");
        IsoPlayer innerOwner = player((short) 42, "inner");
        UdpConnection outer = conn(outerOwner);
        UdpConnection inner = conn(innerOwner);

        PlainPacket plain = new PlainPacket();
        MdcTimedActionProbe.processServer(plain, null, outer);
        expect("非 Action 封包：照樣委派，但不綁定 Request（零成本直通）",
                plain.dispatched && plain.seen == null
                && MdcTimedActionProbe.boundRequestForTest() == null);

        InterruptRequest nested = own(new InterruptRequest(), innerOwner);
        nested.recordOnly = true;
        InterruptRequest top = own(new InterruptRequest(), outerOwner);
        top.recordOnly = true;
        top.dispatch = () -> MdcTimedActionProbe.processServer(nested, null, inner);
        MdcTimedActionProbe.processServer(top, null, outer);
        expect("NetTimedAction 封包：dispatch 期間看得到自己；nested 各看到自己的、逐層恢復；結束清空",
                top.seen == top && nested.seen == nested
                && top.seenAfterNested == top
                && MdcTimedActionProbe.boundRequestForTest() == null);

        InterruptRequest boom = own(new InterruptRequest(), outerOwner);
        boom.recordOnly = true;
        boom.dispatch = () -> {
            throw new IllegalStateException("packet blew up");
        };
        boolean escaped = false;
        try {
            MdcTimedActionProbe.processServer(boom, null, outer);
        } catch (IllegalStateException e) {
            escaped = "packet blew up".equals(e.getMessage());
        }
        expect("封包處理例外：原例外外逃且 finally 已清空 Request 上下文",
                escaped && MdcTimedActionProbe.boundRequestForTest() == null);
        expect("dispatch bridge 零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    /** 玩家名是 client 給的字串，會進 log 行：不能注入新行、方括號欄位或連線成員分隔符，超長截斷。 */
    private static void testSafeName() {
        String logged = MdcTimedActionProbe.safeName("eve\r\n[FAKE] admin|login" + "x".repeat(40));
        expect("玩家名不能注入新行、方括號欄位或連線成員分隔符，且截斷",
                logged.chars().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)
                        || c == '[' || c == ']' || c == '|')
                && logged.endsWith("..."));
    }

    /**
     * 偽造取消：bob 的連線送出帶 alice onlineID 的 Reject（真 write→parse）。四種 Action 封包：
     * GeneralAction 以 setReject(id, alice) 產生（index 為本連線 slot 0，server 解析成 bob，但 wire onlineID 是 alice 的）；
     * 其餘以 index -1 送出（server 以 IDToPlayerMap 解析成 alice 本人）。檢查開著時整包不派送；
     * 關著時原版以 (alice onlineID, id) 為鍵取消 alice 的動作——原版信任邊界的負對照。
     */
    private static void testForgedCancel(int mode) throws Exception {
        Collection<Object> queue = queue();
        IsoPlayer alice = player((short) 5, "alice");
        IsoPlayer bob = player((short) 7, "bob");
        UdpConnection bobConn = conn(bob);
        zombie.network.GameServer.IDToPlayerMap.put((short) 5, alice);
        zombie.network.GameServer.IDToPlayerMap.put((short) 7, bob);
        try {
            for (Class<?> kind : new Class<?>[]{GeneralActionPacket.class, NetTimedActionPacket.class,
                    BuildActionPacket.class, EventCapturingFishingPacket.class}) {
                queue.clear();
                NetTimedActionPacket victim = own(newAction(30_000L), alice);
                victim.id = 66;
                victim.state = Transaction.TransactionState.Accept;
                victim.endTime = zombie.GameTime.getServerTimeMills() + 30_000L;
                NetTimedActionPacket mine = own(newAction(30_000L), bob);
                mine.id = victim.id;
                mine.state = Transaction.TransactionState.Accept;
                mine.endTime = victim.endTime;
                queue.add(victim);
                queue.add(mine);

                Action packet = decoded(kind, alice, victim.id, bobConn);
                boolean viaSlot = packet instanceof GeneralActionPacket;
                expect(kind.getSimpleName() + "：真 wire 帶 alice 的 onlineID，解析出的 owner 為 "
                        + (viaSlot ? "本連線 slot 0（bob）" : "alice"),
                        packet.playerId.getID() == alice.getOnlineID()
                        && packet.playerId.getPlayer() == (viaSlot ? bob : alice));
                long refused0 = MdcTimedActionProbe.unknownRefusedForTest();
                MdcTimedActionProbe.processServer((INetworkPacket) packet, null, bobConn);
                ActionManager.update();
                if (MdcTimedActionProbe.OWNER_CHECK) {
                    expect(kind.getSimpleName() + "：偽造取消不派送，alice 與 bob 的同 id 動作都原封不動",
                            queue.size() == 2 && queue.contains(victim) && queue.contains(mine)
                            && victim.state == Transaction.TransactionState.Accept);
                    expect(kind.getSimpleName() + "：拒絕計數（MODE_OFF 不記）",
                            MdcTimedActionProbe.unknownRefusedForTest()
                                    == refused0 + (mode == MdcTimedActionProbe.MODE_OFF ? 0 : 1));
                    if (packet instanceof EventCapturingFishingPacket fishing) {
                        expect("偽造 Fishing Reject＋bobber flag 不為 alice 產生 Lua 更新事件資料",
                                fishing.eventPlayer == null);
                    }
                } else {
                    expect(kind.getSimpleName() + "：owner-off 負對照＝原版以 alice 的 onlineID 取消 alice 的動作",
                            !queue.contains(victim) && queue.contains(mine));
                }
            }
        } finally {
            queue.clear();
            zombie.network.GameServer.IDToPlayerMap.remove((short) 5);
            zombie.network.GameServer.IDToPlayerMap.remove((short) 7);
        }
        expect("偽造取消路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    /**
     * 合法取消：bob 自己的 Reject 交回原版——只移除 bob 的同 id 動作（42.21 原版已以 owner 分鍵），
     * Fishing Reject 後續的 Lua 事件資料照原版為 bob 產生（bridge 不再自行 stop 後早退）。
     */
    private static void testLegitCancelRunsVanilla() throws Exception {
        Collection<Object> queue = queue();
        IsoPlayer alice = player((short) 5, "alice");
        IsoPlayer bob = player((short) 7, "bob");
        UdpConnection bobConn = conn(bob);
        zombie.network.GameServer.IDToPlayerMap.put((short) 5, alice);
        zombie.network.GameServer.IDToPlayerMap.put((short) 7, bob);
        try {
            for (Class<?> kind : new Class<?>[]{GeneralActionPacket.class, NetTimedActionPacket.class,
                    BuildActionPacket.class, EventCapturingFishingPacket.class}) {
                queue.clear();
                NetTimedActionPacket other = own(newAction(30_000L), alice);
                other.id = 77;
                other.state = Transaction.TransactionState.Accept;
                other.endTime = zombie.GameTime.getServerTimeMills() + 30_000L;
                NetTimedActionPacket mine = own(newAction(30_000L), bob);
                mine.id = other.id;
                mine.state = Transaction.TransactionState.Accept;
                mine.endTime = other.endTime;
                queue.add(other);
                queue.add(mine);

                Action packet = decoded(kind, bob, mine.id, bobConn);
                long refused0 = MdcTimedActionProbe.unknownRefusedForTest();
                MdcTimedActionProbe.processServer((INetworkPacket) packet, null, bobConn);
                expect(kind.getSimpleName() + "：合法取消交回原版，只移除 bob 自己的同 id 動作，零拒絕",
                        queue.size() == 1 && queue.contains(other) && !queue.contains(mine)
                        && other.state == Transaction.TransactionState.Accept
                        && MdcTimedActionProbe.unknownRefusedForTest() == refused0);
                if (packet instanceof EventCapturingFishingPacket fishing) {
                    expect("Fishing Reject＋bobber flag 的原版後續照常執行（事件資料屬於 bob）",
                            fishing.eventPlayer == bob);
                }
            }
        } finally {
            queue.clear();
            zombie.network.GameServer.IDToPlayerMap.remove((short) 5);
            zombie.network.GameServer.IDToPlayerMap.remove((short) 7);
        }
        expect("合法取消路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    /**
     * 偽造 Request：bob 的連線送出 alice onlineID 的 NetTimedAction Request（action=null 走原版 initial Reject）。
     * 原版 getAction 以 (alice onlineID, id) 找到 alice 的動作並 copyFrom／setState(Reject) 改寫它；
     * 檢查開著時在派送前拒絕。
     */
    private static void testForgedRequest() throws Exception {
        Collection<Object> queue = queue();
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
                    MdcTimedActionProbe.processServer(request, null, bobConn);
                } catch (RuntimeException e) {
                    escaped = true;   // owner-off：原版改寫 victim 後在測試連線的 startPacket 失敗
                }
                if (MdcTimedActionProbe.OWNER_CHECK) {
                    expect("不可信 Request index=" + playerIndex + " 在 getAction/copyFrom 前拒絕，旁人動作不變",
                            !escaped && queue.size() == 1 && queue.contains(victim)
                            && victim.state == Transaction.TransactionState.Accept
                            && victim.playerId.getPlayer() == alice && victim.action != null);
                } else {
                    expect("owner-off 負對照 index=" + playerIndex + "：原版把 alice 的動作改成 Reject",
                            victim.state == Transaction.TransactionState.Reject);
                }
            }
        } finally {
            queue.clear();
            zombie.network.GameServer.IDToPlayerMap.remove((short) 5);
        }
    }

    /** 同機多人、過期 owner、wire onlineID 不符、空連線與缺連線：只認連線上實際的 owner 物件。 */
    private static void testConnectionMembership(int mode) throws Exception {
        IsoPlayer host = player((short) 4, "host");
        IsoPlayer couch = player((short) 5, "couch");
        UdpConnection splitConn = conn(host, couch);

        InterruptRequest couchReq = own(new InterruptRequest(), couch);
        couchReq.recordOnly = true;
        MdcTimedActionProbe.processServer(couchReq, null, splitConn);
        expect("同機多人：index 1 的實際 owner 也算本連線（派送）", couchReq.dispatched);

        long refused0 = MdcTimedActionProbe.unknownRefusedForTest();
        InterruptRequest stale = own(new InterruptRequest(), player((short) 5, "couch-old-object"));
        stale.recordOnly = true;
        MdcTimedActionProbe.processServer(stale, null, splitConn);
        expect("過期 owner 物件與新玩家同 onlineID：不得跨實例認領（不派送）", !stale.dispatched);

        InterruptRequest mismatched = own(new InterruptRequest(), couch);
        mismatched.recordOnly = true;
        mismatched.playerId.setID((short) 4);   // owner 物件屬本連線，但 wire onlineID 是 host 的
        MdcTimedActionProbe.processServer(mismatched, null, splitConn);
        expect("owner 屬本連線但 wire onlineID 是同機另一人：不派送（原版會以 host 的 id 為鍵）", !mismatched.dispatched);

        InterruptRequest noPlayers = own(new InterruptRequest(), couch);
        noPlayers.recordOnly = true;
        MdcTimedActionProbe.processServer(noPlayers, null, conn());
        UdpConnection nullArray = alloc(UdpConnection.class);
        MdcTimedActionProbe.processServer(noPlayers, null, nullArray);
        MdcTimedActionProbe.processServer(noPlayers, null, null);
        expect("空連線／players 陣列 null／缺連線：一律不派送、零例外", !noPlayers.dispatched);
        if (mode != MdcTimedActionProbe.MODE_OFF) {
            expect("計數：過期 owner、onlineID 不符與三種空連線各 +1",
                    MdcTimedActionProbe.unknownRefusedForTest() == refused0 + 5);
        } else {
            expect("off：拒絕照做但零計數", MdcTimedActionProbe.unknownRefusedForTest() == refused0);
        }
        expect("身分檢查路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    // ---- fixture ----

    /** client 端建出 owner 的 Reject 封包，走真 write，再以 server 端同型別真 parse 解回。 */
    private static Action decoded(Class<?> kind, IsoPlayer owner, byte id, UdpConnection connection) throws Exception {
        Action client;
        if (kind == GeneralActionPacket.class) {
            GeneralActionPacket cancel = new GeneralActionPacket();
            cancel.setReject(id, owner);   // 原版 client 取消：PlayerID.set(owner)＝本地 slot index
            client = cancel;
        } else {
            client = own((Action) kind.getDeclaredConstructor().newInstance(), owner);
            client.id = id;
            client.state = Transaction.TransactionState.Reject;
            if (client instanceof FishingAction fishing) fishing.contentFlag = FishingAction.flagUpdateBobberParameters;
        }
        ByteBuffer wire = ByteBuffer.allocate(128);
        ((INetworkPacket) client).write(new ByteBufferWriter(wire));
        wire.flip();
        Action packet = (Action) kind.getDeclaredConstructor().newInstance();
        ((INetworkPacket) packet).parse(new ByteBufferReader(wire), connection);
        return packet;
    }

    /** 讓動作的 owner 是真的 IsoPlayer（onlineID ＋ player 參照；playerIndex 維持預設 -1）。 */
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

    /** 最小連線：只補 bridge 會讀的 players（其餘 index 留 null）。 */
    private static UdpConnection conn(IsoPlayer... players) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        c.players = new IsoPlayer[4];
        for (int i = 0; i < players.length; i++) {
            c.players[i] = players[i];
        }
        return c;
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

    /**
     * 隔離 Request 後續的 Lua start/send：預設只跑 W10-C 的 stopPlayerActions 改道（真原版停止流程）；
     * recordOnly 時只記錄派送與綁定中的 Request。
     */
    private static final class InterruptRequest extends NetTimedActionPacket {
        boolean recordOnly;
        boolean dispatched;
        Runnable dispatch;
        NetTimedActionPacket seen;
        NetTimedActionPacket seenAfterNested;

        @Override
        public void processServer(PacketTypes.PacketType type, UdpConnection connection) {
            this.dispatched = true;
            this.seen = MdcTimedActionProbe.boundRequestForTest();
            if (this.dispatch != null) {
                this.dispatch.run();
            }
            this.seenAfterNested = MdcTimedActionProbe.boundRequestForTest();
            if (!this.recordOnly) {
                MdcTimedActionProbe.stopPlayerActions(playerId);
            }
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

    /** 非 Action 的封包：bridge 必須照樣委派、且不綁定 Request。 */
    private static final class PlainPacket implements INetworkPacket {
        boolean dispatched;
        NetTimedActionPacket seen;

        @Override
        public void parse(ByteBufferReader b, IConnection connection) {
        }

        @Override
        public void write(ByteBufferWriter b) {
        }

        @Override
        public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
            this.dispatched = true;
            this.seen = MdcTimedActionProbe.boundRequestForTest();
        }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) failed++;
    }

    private MdcTimedActionProbeTest() {}
}
