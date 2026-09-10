package zombie.mdc;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.entity.ComponentType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PZNetKahluaTableImpl;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10 卡讀條根治的行為驗證：用真實的線路位元組驅動<b>已手術的</b>
 * {@code NetTimedAction.parse}（classpath 上 {@code dist\java} 先於遊戲 jar），
 * 重現正式服的 sbyt 36 缺席 component，確認整包不再中斷而是被明確拒絕。
 *
 * <p>argv：無參數＝三刀啟用；{@code guard-off}／{@code state-off}／{@code args-off} 各對應一個
 * kill switch。測試反射自驗 helper 的實際旗標與 argv 相符——property 名稱打錯時炸在測試裡，
 * 不會默默把 enabled 版跑四遍假綠。
 *
 * <p>以可控 LuaCaller 觀察是否誤執行建構子；parse 與回覆序列化都使用遊戲真類別。
 * 本測試不開網路 socket，不將成功序列化當作 client 已收到回覆。
 */
public final class NetTimedActionGuardTest {

    /** 缺席元件參照；不預設它是搬移、登錄遺漏或其他身分問題造成。 */
    private static final long MISSING_NET_ID = 1_234_567_890L;
    private static final String ACTION_TYPE = "ISEatFoodAction";
    private static final byte SBYT_STRING = 1;
    private static final byte SBYT_INTEGER = 0;
    private static final byte SBYT_CRAFTBENCH = 36;

    /** Lua 建構子替身的回傳值（parse 成功時會被塞進 packet.action）。 */
    private static final KahluaTable MADE = table();
    private static final StubCaller CALLER = new StubCaller(LuaReturn.createReturn(new Object[]{ Boolean.TRUE, MADE }));

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "both";
        boolean wantGuard = !"guard-off".equals(mode);
        boolean wantState = !"state-off".equals(mode);
        boolean wantArgs = !"args-off".equals(mode);

        boolean guard = flag("CALL_GUARD");
        boolean state = flag("STATE_FIX");
        boolean argsGuard = flag("ARGS_GUARD");
        boolean argsActive = wantArgs && wantState;
        expect("自驗：argv=" + mode + " 與 helper 實際旗標相符（guard=" + guard + " state=" + state
                + " args=" + argsGuard + "）",
                guard == wantGuard && state == wantState && argsGuard == wantArgs);

        GameServer.server = true;
        LuaManager.env = table();
        LuaManager.caller = CALLER;
        zombie.characters.IsoPlayer player = testPlayer();
        GameServer.IDToPlayerMap.put((short) 3, player);

        testVanillaDecoderUntouched();
        testReferenceDiagnostics();
        ProbePacket reused = testMissingComponentPacket(argsActive);
        testCleanPacketAfterFailure(reused);
        testCauseBinding(argsActive);
        testProtectedCall(guard);
        testLoadArgsUnit(argsActive);

        expect("零 anomalies（診斷路徑自身沒有失敗）", NetTimedActionGuard.anomaliesForTest() == 0);

        if (failed > 0) {
            System.out.println("net-timed-action FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("net-timed-action OK  mode=" + mode
                + "：共用 decoder 未改道／缺 component 的封包／下一包乾淨／原因綁單一封包／"
                + "Error 穿透（各 kill switch 走對應分支）全數通過");
    }

    /**
     * D2 退役的負對照：共用 table decoder 必須維持原版。同一段位元組在
     * {@code NetTimedAction.parse} 之外（任何其他封包共用這個 decoder）照樣從
     * {@code PZNetKahluaTableImpl} 自己拋 NPE，且半成品條目留在表上——helper 一個字節都沒碰它。
     */
    private static void testVanillaDecoderUntouched() {
        PZNetKahluaTableImpl raw = newArgsTable();
        NullPointerException npe = null;
        try {
            raw.load(argsWire(true), null);
        } catch (NullPointerException e) {
            npe = e;
        }
        expect("原版共用 table parser 未被改道：sbyt 36 缺 entity 仍由 PZNetKahluaTableImpl 自己 NPE，"
                + "半成品條目照原版留著",
                npe != null && raw.size() == 1
                && "zombie.network.PZNetKahluaTableImpl".equals(npe.getStackTrace()[0].getClassName()));
    }

    /** 只讀真 decoder 已消費的欄位；不改 buffer，也不替其他 parser 的錯誤猜 netID。 */
    private static void testReferenceDiagnostics() {
        PZNetKahluaTableImpl args = newArgsTable();
        ByteBufferReader reader = argsWire(true);
        NullPointerException missing = missingComponent(args, reader);
        int position = reader.bb.position();
        int limit = reader.bb.limit();
        ByteOrder order = reader.bb.order();
        String detail = NetTimedActionGuard.componentReference(args, reader, missing);
        expect("診斷記錄真 wire netID／componentId，沒有把它解碼成替代物件",
                detail.contains("netId=" + MISSING_NET_ID)
                && detail.contains("componentId=" + ComponentType.CraftBench.GetID()));
        expect("診斷不改 reader position／limit／byte order",
                reader.bb.position() == position && reader.bb.limit() == limit && reader.bb.order() == order);

        ByteBuffer parent = ByteBuffer.allocateDirect(64);
        parent.position(7);
        ByteBuffer slice = parent.slice().order(ByteOrder.LITTLE_ENDIAN);
        slice.position(3);
        slice.putLong(MISSING_NET_ID).putShort(ComponentType.CraftBench.GetID()).put((byte) 0x5a);
        slice.flip().position(3);
        ByteBufferReader readOnly = new ByteBufferReader(slice.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
        NullPointerException viewFailure = null;
        try {
            PZNetKahluaTableImpl.loadComponent(readOnly.bb, null);
        } catch (NullPointerException e) {
            viewFailure = e;
        }
        String viewDetail = NetTimedActionGuard.componentReference(args, readOnly, viewFailure);
        expect("direct／唯讀 slice 使用原 byte order，且保留下一個未消費 byte",
                viewDetail.contains("netId=" + MISSING_NET_ID)
                && viewDetail.contains("componentId=" + ComponentType.CraftBench.GetID())
                && readOnly.bb.limit() == 14 && readOnly.bb.order() == ByteOrder.LITTLE_ENDIAN
                && readOnly.bb.position() == 13 && readOnly.bb.get() == (byte) 0x5a);

        expect("其他 NPE 不猜 netID",
                !NetTimedActionGuard.componentReference(args, reader, new NullPointerException()).contains("netId="));
        expect("自訂 table 不假設沿用原 decoder 的 buffer",
                !NetTimedActionGuard.componentReference(
                        new PZNetKahluaTableImpl(new LinkedHashMap<>()) {}, reader, missing).contains("netId="));
        expect("截斷資料不回讀成識別碼",
                !NetTimedActionGuard.componentReference(args, new ByteBufferReader(ByteBuffer.allocate(9)),
                        missing).contains("netId="));
        missing.setStackTrace(new StackTraceElement[0]);
        expect("沒有 stack 的 fast-throw 例外不猜 netID",
                !NetTimedActionGuard.componentReference(args, reader, missing).contains("netId="));
    }

    private static NullPointerException missingComponent(PZNetKahluaTableImpl args, ByteBufferReader reader) {
        try {
            args.load(reader, null);
        } catch (NullPointerException failure) {
            return failure;
        }
        throw new AssertionError("missing component must fail in vanilla decoder");
    }

    /**
     * 正式服症狀的完整重現：header／type／args 都合法，只有 sbyt 36 的 component 在 server 查無此物。
     * vanilla 在此中斷 parse → {@code processServer} 從未執行 → 既不 Accept 也不 Reject。
     */
    private static ProbePacket testMissingComponentPacket(boolean argsActive) {
        ProbePacket p = new ProbePacket();
        CALLER.calls = 0;
        long failed0 = NetTimedActionGuard.argsFailedForTest();
        long rejected0 = NetTimedActionGuard.argsRejectedForTest();
        long caught0 = NetTimedActionGuard.caughtForTest();
        long sent0 = NetTimedActionGuard.rejectedForTest();

        if (!argsActive) {
            NullPointerException npe = null;
            try {
                p.parse(packetWire(true), null);
            } catch (NullPointerException e) {
                npe = e;
            }
            expect("D kill switch：args／state 任一關閉時 parse 照原版在 decoder 中斷（無回覆），零計數",
                    npe != null && CALLER.calls == 0
                    && NetTimedActionGuard.argsFailedForTest() == failed0
                    && NetTimedActionGuard.argsRejectedForTest() == rejected0);
            return p;
        }
        p.parse(packetWire(true), null);
        expect("D：缺 component 的封包 parse 走完，保留合法玩家 header，action=null 進拒絕分支",
                p.isConsistent(null) && p.action == null && ACTION_TYPE.equals(p.type) && "test".equals(p.name));
        expect("D：partial args 清空後不呼叫 Lua 建構子，argsFailed／argsRejected 各 +1，B 刀計數不動",
                CALLER.calls == 0
                && NetTimedActionGuard.argsFailedForTest() == failed0 + 1
                && NetTimedActionGuard.argsRejectedForTest() == rejected0 + 1
                && NetTimedActionGuard.caughtForTest() == caught0);
        expect("D：失敗原因留給本封包的 write（尚未被消費）", NetTimedActionGuard.causeForTest() != null);

        ByteBufferWriter reply = writer();
        NetTimedActionGuard.write(p, reply);
        reply.bb.flip();
        ByteBufferReader received = new ByteBufferReader(reply.bb);
        expect("D→A：真回覆 bytes 保留動作/玩家編號，state=Reject，原因取用即清",
                received.getByte() == 7
                && received.getEnum(Transaction.TransactionState.class) == Transaction.TransactionState.Reject
                && received.getShort() == 3 && received.getByte() == -1 && !reply.bb.hasRemaining()
                && NetTimedActionGuard.rejectedForTest() == sent0 + 1
                && NetTimedActionGuard.causeForTest() == null);
        return p;
    }

    /**
     * 下一包不受污染。刻意重用<b>同一個</b> packet 物件——正式服的
     * {@code connection.getPacket(type)} 就是每條連線一個重用實例，identity 比對不足以區分前後兩包，
     * 靠的是 {@code beginParse} 在每次 parse 開頭清掉殘留。
     */
    private static void testCleanPacketAfterFailure(ProbePacket p) {
        CALLER.calls = 0;
        long sent0 = NetTimedActionGuard.rejectedForTest();

        p.parse(packetWire(false), null);
        expect("下一包正常：同一個（池化）封包重新 parse 成功，action 由 Lua 建構子回傳、ctor 恰呼叫 1 次",
                p.action == MADE && CALLER.calls == 1);
        expect("下一包不受污染：上一包的失敗原因沒有殘留", NetTimedActionGuard.causeForTest() == null);

        ByteBufferWriter reply = writer();
        NetTimedActionGuard.write(p, reply);
        reply.bb.flip();
        ByteBufferReader received = new ByteBufferReader(reply.bb);
        expect("A：成功 parse 的回覆維持原版 Request state，不誤計為 Reject",
                received.getByte() == 7
                && received.getEnum(Transaction.TransactionState.class) == Transaction.TransactionState.Request
                && received.getShort() == 3 && received.getByte() == -1 && !reply.bb.hasRemaining()
                && NetTimedActionGuard.rejectedForTest() == sent0);
    }

    /** 原因只屬於它自己那一個封包：錯的封包拿不到，序列化拋錯也不留給下一包。 */
    private static void testCauseBinding(boolean argsActive) {
        if (!argsActive) {
            return;   // 沒有 D 刀就不會有原因可綁；kill switch 路徑由上面的直通斷言覆蓋
        }
        ProbePacket owner = new ProbePacket();
        owner.parse(packetWire(true), null);
        expect("綁定前提：失敗的封包確實留下原因", NetTimedActionGuard.causeForTest() != null);
        expect("原因綁定單一封包：另一個封包取不到，且狀態一併清空（不殘留給下一包）",
                NetTimedActionGuard.takeCause(new ProbePacket()) == null
                && NetTimedActionGuard.causeForTest() == null);

        ProbePacket boom = new ProbePacket();
        boom.explode = true;
        boom.parse(packetWire(true), null);
        RuntimeException thrown = null;
        try {
            NetTimedActionGuard.write(boom, writer());
        } catch (RuntimeException e) {
            thrown = e;
        }
        expect("A：packet.write 序列化拋錯時例外原樣外傳（fail-fast），且原因不殘留給下一包",
                thrown != null && NetTimedActionGuard.causeForTest() == null);
    }

    /** B 刀：Lua 建構子例外攔截、kill switch、Error 穿透。 */
    private static void testProtectedCall(boolean guard) {
        NetTimedActionGuard.beginParse(new ProbePacket());   // 乾淨的 parse 語境（無殘留原因）
        LuaReturn ok = LuaReturn.createReturn(new Object[]{ Boolean.TRUE, "ok" });
        StubCaller okCaller = new StubCaller(ok);
        expect("B：成功路徑原樣委派（回傳同一個 LuaReturn 實例、恰委派 1 次）",
                NetTimedActionGuard.protectedCall(okCaller, null, null, new Object[0]) == ok
                && okCaller.calls == 1);

        RuntimeException luaErr = new RuntimeException("attempted index: getContainer of non-table: null");
        StubCaller bad = new StubCaller(luaErr);
        if (guard) {
            LuaReturn r = NetTimedActionGuard.protectedCall(bad, null, null, argsWithNull());
            expect("B：Lua 建構子例外被攔下，回 isSuccess()==false（落進 vanilla 的 action=null 路徑）",
                    r != null && !r.isSuccess());
        } else {
            boolean rethrown = false;
            try {
                NetTimedActionGuard.protectedCall(bad, null, null, argsWithNull());
            } catch (RuntimeException e) {
                rethrown = e == luaErr;
            }
            expect("B kill switch：guard=0 時例外原樣外傳（vanilla 行為）", rethrown);
        }

        // 上一段留下的 luaCtor 原因會讓 protectedCall 短路（那正是 D 刀的語意）——
        // 要驗 Error 穿透就得先回到乾淨的 parse 語境。
        clearCause();
        boolean errorEscaped = false;
        try {
            NetTimedActionGuard.protectedCall(new StubCaller(new StackOverflowError("boom")),
                    null, null, new Object[0]);
        } catch (StackOverflowError e) {
            errorEscaped = true;
        }
        expect("B：Error 穿透（catch 型別必須是 RuntimeException，不得放寬成 Throwable）", errorEscaped);
        clearCause();   // 清掉本節留下的 luaCtor 原因
    }

    /** D 刀改道點本身：正常委派、失敗清空半成品、Error 穿透。 */
    private static void testLoadArgsUnit(boolean argsActive) {
        clearCause();
        PZNetKahluaTableImpl unscoped = newArgsTable();
        boolean unscopedFailed = false;
        try {
            NetTimedActionGuard.loadArgs(unscoped, argsWire(true), null);
        } catch (NullPointerException e) {
            unscopedFailed = true;
        }
        expect("D：缺 parse 上下文時仍原樣拋錯，不清空共用 decoder 資料、不留拒絕原因",
                unscopedFailed && unscoped.size() == 1 && NetTimedActionGuard.causeForTest() == null);
        NetTimedActionGuard.beginParse(new ProbePacket());
        PZNetKahluaTableImpl good = newArgsTable();
        NetTimedActionGuard.loadArgs(good, argsWire(false), null);
        expect("D：正常 load 原樣委派（真位元組解出 1 個條目）、零原因",
                good.size() == 1 && NetTimedActionGuard.causeForTest() == null);

        PZNetKahluaTableImpl partial = newArgsTable();
        if (argsActive) {
            NetTimedActionGuard.loadArgs(partial, argsWire(true), null);
            expect("D：decoder 拋 RuntimeException 後半成品條目被清空（parse 的 (byte)(size+1) 與迭代都在空表上）"
                    + "、原因就位",
                    partial.size() == 0 && NetTimedActionGuard.causeForTest() != null);
        } else {
            NullPointerException npe = null;
            try {
                NetTimedActionGuard.loadArgs(partial, argsWire(true), null);
            } catch (NullPointerException e) {
                npe = e;
            }
            expect("D kill switch：直通原版（例外外傳、半成品照原版留著、零原因）",
                    npe != null && partial.size() == 1 && NetTimedActionGuard.causeForTest() == null);
        }

        boolean errorEscaped = false;
        try {
            NetTimedActionGuard.loadArgs(new ExplodingTable(new StackOverflowError("boom")), null, null);
        } catch (StackOverflowError e) {
            errorEscaped = true;
        }
        expect("D：Error 穿透（catch 型別 RuntimeException）", errorEscaped);
        clearCause();
    }

    // ---- 線路位元組（對照 vanilla 的 Action.parse／NetTimedAction.parse 讀取順序）----

    /** {@code Action.parse} 的 header ＋ type／name ＋ actionArgs。 */
    private static ByteBufferReader packetWire(boolean withComponent) {
        ByteBuffer bb = ByteBuffer.allocate(512);
        bb.put((byte) 7);                                                   // Action.id
        bb.put((byte) Transaction.TransactionState.Request.ordinal());      // Action.state
        bb.putShort((short) 3);                                             // PlayerID.id
        bb.put((byte) -1);                                                  // PlayerID.playerIndex
        putUTF(bb, ACTION_TYPE);
        putUTF(bb, "test");
        putArgs(bb, withComponent);
        bb.flip();
        return new ByteBufferReader(bb);
    }

    /** 只有 {@code PZNetKahluaTableImpl.load} 讀的那一段。 */
    private static ByteBufferReader argsWire(boolean withComponent) {
        ByteBuffer bb = ByteBuffer.allocate(256);
        putArgs(bb, withComponent);
        bb.flip();
        return new ByteBufferReader(bb);
    }

    private static void putArgs(ByteBuffer bb, boolean withComponent) {
        bb.putInt(withComponent ? 2 : 1);
        bb.put(SBYT_STRING);
        putUTF(bb, "recipe");
        bb.put(SBYT_INTEGER);
        bb.putInt(7);
        if (withComponent) {
            bb.put(SBYT_STRING);
            putUTF(bb, "bench");
            // 正式服的第三條靜默路徑：GameEntityManager.GetEntity(netID) 回 null → vanilla NPE
            bb.put(SBYT_CRAFTBENCH);
            bb.putLong(MISSING_NET_ID);
            bb.putShort(ComponentType.CraftBench.GetID());
        }
    }

    private static void putUTF(ByteBuffer bb, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        bb.putShort((short) raw.length);
        bb.put(raw);
    }

    // ---- 替身 ----

    /** 只覆寫 write 的封包替身；parse 走的是真正動過刀的 {@code NetTimedAction.parse}。 */
    private static final class ProbePacket extends NetTimedActionPacket {
        private boolean explode;

        @Override
        public void write(ByteBufferWriter b) {
            if (explode) {
                throw new IllegalStateException("serialize boom");
            }
            super.write(b);
        }

    }

    /** 輕量真玩家實例；只設定本測試會使用的身分欄位，不加入世界。 */
    private static zombie.characters.IsoPlayer testPlayer() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        zombie.characters.IsoPlayer player = (zombie.characters.IsoPlayer)
                unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, zombie.characters.IsoPlayer.class);
        player.setOnlineID((short) 3);
        player.remote = true;
        return player;
    }

    /** 可控的 LuaCaller 替身：回固定值或拋固定 Throwable，並計被呼叫次數。 */
    private static final class StubCaller extends LuaCaller {
        private final LuaReturn result;
        private final Throwable throwable;
        private int calls;

        StubCaller(LuaReturn result) {
            this(result, null);
        }

        StubCaller(Throwable throwable) {
            this(null, throwable);
        }

        private StubCaller(LuaReturn result, Throwable throwable) {
            super(new KahluaConverterManager());
            this.result = result;
            this.throwable = throwable;
        }

        @Override
        public LuaReturn protectedCall(KahluaThread thread, Object fn, Object... args) {
            calls++;
            if (throwable instanceof RuntimeException re) {
                throw re;
            }
            if (throwable instanceof Error err) {
                throw err;
            }
            return result;
        }
    }

    /** Error 穿透用的 actionArgs 替身（真 decoder 只會拋 RuntimeException）。 */
    private static final class ExplodingTable extends PZNetKahluaTableImpl {
        private final Error error;

        ExplodingTable(Error error) {
            super(new LinkedHashMap<>());
            this.error = error;
        }

        @Override
        public void load(ByteBufferReader input, IConnection connection) {
            throw error;
        }
    }

    // ---- 小工具 ----

    /** 丟一個「不是 owner」的封包給 takeCause，把本節留下的原因清乾淨。 */
    private static void clearCause() {
        NetTimedActionGuard.takeCause(new ProbePacket());
    }

    private static PZNetKahluaTableImpl newArgsTable() {
        return new PZNetKahluaTableImpl(new LinkedHashMap<>());
    }

    /** Kahlua table 需要一個後備 Map（j2se 實作沒有無參建構子）。 */
    private static KahluaTable table() {
        return new KahluaTableImpl(new LinkedHashMap<>());
    }

    /** 模擬 loadInventoryItem 靜默回 null 的參數形狀（index 2 為 null）。 */
    private static Object[] argsWithNull() {
        return new Object[]{ table(), "chr", null };
    }

    private static ByteBufferWriter writer() {
        return new ByteBufferWriter(ByteBuffer.allocate(4096));
    }

    private static boolean flag(String name) throws Exception {
        Field f = NetTimedActionGuard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "nta pass  " : "nta FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private NetTimedActionGuardTest() {}
}
