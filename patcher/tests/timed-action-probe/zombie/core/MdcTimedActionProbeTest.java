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

/**
 * W10-C（B）與派送 bridge 的 MdcTimedActionProbe 行為驗證（獨立 JVM；MODE 是 static final，
 * 各組態由 build.ps1 分開驅動並以 argv 自驗）。放在 zombie.core 以直讀 Action 的 protected 欄位。
 *
 * <p>覆蓋：(B) 打斷偵測（Accept 才算／同 id 重送分流／enforce 補送在無連線時安全跳過）、bridge 的 Request
 * 上下文綁定／nested／例外清除。另以真 write→parse 與原版 processServer 釘住 42.22.2 對版退役 owner 檢查與 W78 的依據：
 * 伺服器只從送出連線的 slot 解析玩家（index -1、他人 onlineID、空 slot 都解析不到、isConsistent 為 false），
 * 合法取消只動自己的同 id 動作；NetTimedAction Request 的 {@code character} 不是送出者（含 nil）時原版自己回 Reject。
 */
public final class MdcTimedActionProbeTest {

    private static int failed;
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

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

        testInterrupt(wantMode);
        testDispatchBinding();
        testSafeName();
        testVanillaIdentity();
        testVanillaExecutor();

        if (failed != 0) {
            OUT.println("timed-action-probe FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("timed-action-probe OK mode=" + MdcTimedActionProbe.MODE);
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
     * owner 檢查退役依據（42.22.2 原版）：bob 的連線送出 Reject，wire 上是玩家 index（真 write→parse，四種 Action 封包）。
     * index 0＝bob 自己的 slot：解析成 bob、id 改成 bob 的 onlineID，經 bridge 交給原版只取消 bob 的同 id 動作
     * （Fishing 的 Lua 事件資料屬於 bob）。index -1（42.21 會以 IDToPlayerMap 解析成別人）、alice 的 onlineID、
     * bob 連線上的空 slot：都解析不到玩家、isConsistent 為 false，原版 onServerPacket 不派送，alice 的動作不動。
     */
    private static void testVanillaIdentity() throws Exception {
        Collection<Object> queue = queue();
        IsoPlayer alice = player((short) 5, "alice");
        IsoPlayer bob = player((short) 7, "bob");
        UdpConnection bobConn = conn(bob);
        zombie.network.GameServer.IDToPlayerMap.put((short) 5, alice);
        zombie.network.GameServer.IDToPlayerMap.put((short) 7, bob);
        try {
            for (Class<?> kind : new Class<?>[]{GeneralActionPacket.class, NetTimedActionPacket.class,
                    BuildActionPacket.class, EventCapturingFishingPacket.class}) {
                for (short index : new short[]{0, -1, 5, 1}) {
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

                    Action packet = decoded(kind, alice, index, mine.id, bobConn);
                    String what = kind.getSimpleName() + " index=" + index;
                    if (index == 0) {
                        expect(what + "：解析成本連線 slot 0 的 bob、id 改成 bob 的 onlineID",
                                packet.isConsistent(bobConn) && packet.player.getPlayer() == bob
                                && packet.player.getId() == bob.getOnlineID());
                    } else {
                        expect(what + "：解析不到玩家、isConsistent=false（原版 onServerPacket 不派送）",
                                packet.player.getPlayer() == null && !packet.isConsistent(bobConn));
                    }
                    if (packet.isConsistent(bobConn)) {   // 同原版 onServerPacket：一致才派送
                        MdcTimedActionProbe.processServer((INetworkPacket) packet, null, bobConn);
                    }
                    if (index == 0) {
                        expect(what + "：交回原版只移除 bob 自己的同 id 動作",
                                queue.size() == 1 && queue.contains(other) && !queue.contains(mine)
                                && other.state == Transaction.TransactionState.Accept);
                        if (packet instanceof EventCapturingFishingPacket fishing) {
                            expect("Fishing Reject＋bobber flag 的原版後續照常執行（事件資料屬於 bob）",
                                    fishing.eventPlayer == bob);
                        }
                    } else {
                        expect(what + "：雙方同 id 動作原封不動",
                                queue.size() == 2 && queue.contains(other) && queue.contains(mine)
                                && other.state == Transaction.TransactionState.Accept);
                    }
                }
            }
        } finally {
            queue.clear();
            zombie.network.GameServer.IDToPlayerMap.remove((short) 5);
            zombie.network.GameServer.IDToPlayerMap.remove((short) 7);
        }
        expect("身分路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    /**
     * W78 退役依據（42.22.2 原版）：NetTimedAction Request 重建出的動作表 {@code character} 不是送出者（別的玩家、
     * 不是玩家、nil）時，原版 processServer 自己走 Reject 分支：恰送出 1 包 Reject、不呼叫 stopPlayerActions、
     * 不登錄動作。經 bridge 派送（Request 上下文照綁），只換掉 RakNet 送出端。
     */
    private static void testVanillaExecutor() throws Exception {
        Collection<Object> queue = queue();
        queue.clear();
        IsoPlayer bob = player((short) 7, "bob");
        IsoPlayer alice = player((short) 5, "alice");
        Object[] characters = {alice, "notAPlayer", null};
        for (Object character : characters) {
            CaptureConnection connection = captureConnection(bob);
            NetTimedActionPacket request = own(new NetTimedActionPacket(), bob);
            request.id = nextId++;
            request.state = Transaction.TransactionState.Request;
            request.type = "ISTestAction";
            request.action = new KahluaTableImpl(new HashMap<>());
            request.action.rawset("character", character);
            long calls0 = MdcTimedActionProbe.interruptCallsForTest();
            MdcTimedActionProbe.processServer(request, PacketTypes.PacketType.NetTimedAction, connection);
            byte[] wire = connection.packets.size() == 1 ? connection.packets.get(0) : new byte[0];
            ByteBufferReader received = new ByteBufferReader(ByteBuffer.wrap(wire));
            boolean reject = wire.length == 3 + 4
                    && received.getByte() == (byte) 134
                    && received.getShort() == PacketTypes.PacketType.NetTimedAction.getId()
                    && received.getByte() == request.id
                    && received.getEnum(Transaction.TransactionState.class) == Transaction.TransactionState.Reject
                    && received.getShort() == bob.getOnlineID();
            String what = character instanceof IsoPlayer ? "別的玩家" : character == null ? "nil" : "不是玩家";
            expect("W78 退役依據：character 是" + what + " → 原版恰送 1 包 Reject、不打斷舊動作、不登錄",
                    reject && MdcTimedActionProbe.interruptCallsForTest() == calls0 && queue.isEmpty());
        }
        expect("W78 退役依據路徑零 anomalies", MdcTimedActionProbe.anomaliesForTest() == 0);
    }

    // ---- fixture ----

    /** client 端建出 owner 的 Reject 封包、wire 上的玩家欄位寫 {@code index}，走真 write，再以 server 端同型別真 parse 解回。 */
    private static Action decoded(Class<?> kind, IsoPlayer owner, short index, byte id, UdpConnection connection)
            throws Exception {
        Action client = own((Action) kind.getDeclaredConstructor().newInstance(), owner);
        client.player.setId(index);
        client.id = id;
        client.state = Transaction.TransactionState.Reject;
        if (client instanceof FishingAction fishing) fishing.contentFlag = FishingAction.flagUpdateBobberParameters;
        ByteBuffer wire = ByteBuffer.allocate(128);
        ((INetworkPacket) client).write(new ByteBufferWriter(wire));
        wire.flip();
        Action packet = (Action) kind.getDeclaredConstructor().newInstance();
        ((INetworkPacket) packet).parse(new ByteBufferReader(wire), connection);
        return packet;
    }

    /** 讓動作的 owner 是真的 IsoPlayer（伺服器形態：player 參照＋onlineID）。 */
    private static <T extends Action> T own(T action, IsoPlayer player) {
        action.player.setPlayer(player);
        action.player.setId(player.getOnlineID());
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

    /** 只替換 RakNet 送出端的真連線：封包內容仍由原版 processServer／PacketType 寫出。 */
    private static final class CaptureConnection extends UdpConnection {
        ByteBuffer buffer;
        ByteBufferWriter writer;
        ArrayList<byte[]> packets;

        /** 永不執行：實例一律以 Unsafe 配置，只為了讓子類別能通過編譯。 */
        private CaptureConnection() {
            super(null, 0L, 0);
        }

        @Override
        public ByteBufferWriter startPacket() {
            this.writer.clear();
            return this.writer;
        }

        @Override
        public void endPacket(int priority, int reliability, byte ordering) {
            byte[] wire = new byte[this.buffer.position()];
            this.buffer.position(0);
            this.buffer.get(wire);
            this.buffer.clear();
            this.packets.add(wire);
        }
    }

    private static CaptureConnection captureConnection(IsoPlayer... players) throws Exception {
        CaptureConnection c = alloc(CaptureConnection.class);
        c.buffer = ByteBuffer.allocate(4096);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        c.players = new IsoPlayer[4];
        for (int i = 0; i < players.length; i++) {
            c.players[i] = players[i];
        }
        return c;
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
                MdcTimedActionProbe.stopPlayerActions(player.getPlayer());
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
        OUT.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) failed++;
    }

    private MdcTimedActionProbeTest() {}
}
