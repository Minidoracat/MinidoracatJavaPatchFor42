package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.entity.ComponentType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PZNetKahluaTableImpl;
import zombie.network.PacketTypes;
import zombie.network.packets.NetTimedActionPacket;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * W10 卡讀條根治的行為驗證：用真實的線路位元組驅動<b>已手術的</b>
 * {@code NetTimedAction.parse}（classpath 上 {@code dist\java} 先於遊戲 jar），
 * 重現正式服的 sbyt 36 缺席 component，確認整包不再中斷而是被明確拒絕。
 *
 * <p>argv：無參數＝全部啟用；{@code guard-off}／{@code args-off}／{@code animal-off} 各對應一個 kill switch。
 * 測試反射自驗 helper 的實際旗標與 argv 相符——property 名稱打錯時炸在測試裡，
 * 不會默默把 enabled 版跑四遍假綠。
 *
 * <p>以可控 LuaCaller 觀察是否誤執行建構子；parse 與回覆（42.21 原版 {@code processServer} 的
 * initial Reject 分支）都使用遊戲真類別，只以擷取連線替代 RakNet 送出端。
 * 本測試不開網路 socket，不將成功序列化當作 client 已收到回覆。
 */
public final class NetTimedActionGuardTest {

    /** 缺席元件參照；不預設它是搬移、登錄遺漏或其他身分問題造成。 */
    private static final long MISSING_NET_ID = 1_234_567_890L;
    private static final String ACTION_TYPE = "ISEatFoodAction";
    private static final byte SBYT_STRING = 1;
    private static final byte SBYT_INTEGER = 0;
    private static final byte SBYT_CRAFTBENCH = 36;
    /** client 的 {@code PZNetKahluaTableImpl.save} 對 IsoAnimal 值寫 type 17 ＋ online ID（short）。 */
    private static final byte SBYT_ANIMAL = 17;
    private static final short KNOWN_ANIMAL = 4242;
    private static final short MISSING_ANIMAL = 4243;
    private static final String ANIMAL_TAG = "[MinidoracatJavaPatch][AnimalIdMiss] ";

    /** Lua 建構子替身的回傳值（parse 成功時會被塞進 packet.action）。 */
    private static final KahluaTable MADE = table();
    private static final StubCaller CALLER = new StubCaller(LuaReturn.createReturn(new Object[]{ Boolean.TRUE, MADE }));

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "both";
        boolean wantGuard = !"guard-off".equals(mode);
        boolean wantArgs = !"args-off".equals(mode);
        boolean wantAnimal = !"animal-off".equals(mode);

        boolean guard = flag("CALL_GUARD");
        boolean argsGuard = flag("ARGS_GUARD");
        boolean animalLog = flag("ANIMAL_ID_LOG");
        expect("自驗：argv=" + mode + " 與 helper 實際旗標相符（guard=" + guard + " args=" + argsGuard
                + " animalIdLog=" + animalLog + "）",
                guard == wantGuard && argsGuard == wantArgs && animalLog == wantAnimal);

        GameServer.server = true;
        LuaManager.env = table();
        LuaManager.caller = CALLER;
        zombie.characters.IsoPlayer player = testPlayer();
        GameServer.IDToPlayerMap.put((short) 3, player);

        testVanillaDecoderUntouched();
        testReferenceDiagnostics();
        NetTimedActionPacket reused = testMissingComponentPacket(argsGuard, player);
        testCleanPacketAfterFailure(reused);
        testCauseBinding(argsGuard);
        testProtectedCall(guard);
        testLoadArgsUnit(argsGuard);
        testAnimalIdMiss(animalLog);

        expect("零 anomalies（診斷路徑自身沒有失敗）", NetTimedActionGuard.anomaliesForTest() == 0);

        if (failed > 0) {
            System.out.println("net-timed-action FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("net-timed-action OK  mode=" + mode
                + "：共用 decoder 的 component 路徑未改道／缺 component 的封包由原版送出 Reject／下一包乾淨／"
                + "原因綁單一 parse／Error 穿透／W51 動物 ID 紀錄（各 kill switch 走對應分支）全數通過");
    }

    /**
     * D2 退役的負對照：共用 table decoder 的 component 路徑必須維持原版（W51 只改 type 17 的動物）。
     * 同一段位元組在 {@code NetTimedAction.parse} 之外（任何其他封包共用這個 decoder）照樣從
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
        expect("原版共用 table parser 的 component 路徑未被改道：sbyt 36 缺 entity 仍由 PZNetKahluaTableImpl 自己 NPE，"
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
     * D 刀讓 parse 走完、action=null，接著跑 42.21 原版 {@code processServer}：initial Reject 以 act 序列化。
     */
    private static NetTimedActionPacket testMissingComponentPacket(boolean argsGuard,
            zombie.characters.IsoPlayer player) throws Exception {
        NetTimedActionPacket p = new NetTimedActionPacket();
        CALLER.calls = 0;
        long failed0 = NetTimedActionGuard.argsFailedForTest();
        long rejected0 = NetTimedActionGuard.argsRejectedForTest();
        long caught0 = NetTimedActionGuard.caughtForTest();

        if (!argsGuard) {
            NullPointerException npe = null;
            try {
                p.parse(packetWire(true), null);
            } catch (NullPointerException e) {
                npe = e;
            }
            expect("D kill switch：args 關閉時 parse 照原版在 decoder 中斷（無回覆），零計數",
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
        expect("D：失敗原因與 parse 上下文已在 protectedCall 取用即清",
                !NetTimedActionGuard.argsFailedPendingForTest() && !NetTimedActionGuard.parseBoundForTest());

        CaptureConnection connection = captureConnection();
        p.processServer(PacketTypes.PacketType.NetTimedAction, connection);
        byte[] wire = connection.packets.size() == 1 ? connection.packets.get(0) : new byte[0];
        ByteBufferReader received = new ByteBufferReader(ByteBuffer.wrap(wire));
        boolean ok = wire.length == 3 + 5
                && received.getByte() == (byte) 134
                && received.getShort() == PacketTypes.PacketType.NetTimedAction.getId()
                && received.getByte() == 7
                && received.getEnum(Transaction.TransactionState.class) == Transaction.TransactionState.Reject
                && received.getShort() == player.getOnlineID()
                && received.getByte() == (player.isLocal() ? (byte) player.getIndex() : (byte) -1);
        expect("D→原版 processServer：恰送出 1 包，bytes 保留動作／玩家編號且 state=Reject（42.21 以 act.write 序列化）", ok);
        return p;
    }

    /**
     * 下一包不受污染。刻意重用<b>同一個</b> packet 物件——正式服的
     * {@code connection.getPacket(type)} 就是每條連線一個重用實例，identity 比對不足以區分前後兩包，
     * 靠的是 {@code beginParse} 在每次 parse 開頭清掉殘留。
     */
    private static void testCleanPacketAfterFailure(NetTimedActionPacket p) {
        CALLER.calls = 0;
        long rejected0 = NetTimedActionGuard.argsRejectedForTest();
        p.parse(packetWire(false), null);
        expect("下一包正常：同一個（池化）封包重新 parse 成功，action 由 Lua 建構子回傳、ctor 恰呼叫 1 次",
                p.action == MADE && CALLER.calls == 1 && NetTimedActionGuard.argsRejectedForTest() == rejected0);
        expect("下一包不受污染：上一包的失敗原因沒有殘留、上下文已釋放",
                !NetTimedActionGuard.argsFailedPendingForTest() && !NetTimedActionGuard.parseBoundForTest());
    }

    /** 原因只屬於它自己那一次 parse：下一次 beginParse 清掉殘留，protectedCall 取用即清。 */
    private static void testCauseBinding(boolean argsGuard) {
        if (!argsGuard) {
            return;   // 沒有 D 刀就不會有原因可綁；kill switch 路徑由上面的直通斷言覆蓋
        }
        NetTimedActionGuard.beginParse(new NetTimedActionPacket());
        NetTimedActionGuard.loadArgs(newArgsTable(), argsWire(true), null);
        expect("綁定前提：失敗的 load 確實留下原因", NetTimedActionGuard.argsFailedPendingForTest());
        NetTimedActionGuard.beginParse(new NetTimedActionPacket());
        expect("下一次 parse 開頭清掉上一包未消費的原因", !NetTimedActionGuard.argsFailedPendingForTest());
        StubCaller ok = new StubCaller(LuaReturn.createReturn(new Object[]{ Boolean.TRUE, "ok" }));
        NetTimedActionGuard.protectedCall(ok, null, null, new Object[0]);
        expect("清掉後同一 parse 的建構子照常委派（不被殘留原因拒絕）", ok.calls == 1);

        NetTimedActionGuard.beginParse(new NetTimedActionPacket());
        NetTimedActionGuard.loadArgs(newArgsTable(), argsWire(true), null);
        StubCaller never = new StubCaller(LuaReturn.createReturn(new Object[]{ Boolean.TRUE, "ok" }));
        LuaReturn rejected = NetTimedActionGuard.protectedCall(never, null, null, new Object[0]);
        expect("原因取用即清：本次拒絕、不呼叫建構子，之後上下文與原因皆為空",
                !rejected.isSuccess() && never.calls == 0
                && !NetTimedActionGuard.argsFailedPendingForTest() && !NetTimedActionGuard.parseBoundForTest());
    }

    /** B 刀：Lua 建構子例外攔截、kill switch、Error 穿透。 */
    private static void testProtectedCall(boolean guard) {
        NetTimedActionGuard.beginParse(new NetTimedActionPacket());   // 乾淨的 parse 語境（無殘留原因）
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

        NetTimedActionGuard.beginParse(new NetTimedActionPacket());
        boolean errorEscaped = false;
        try {
            NetTimedActionGuard.protectedCall(new StubCaller(new StackOverflowError("boom")),
                    null, null, new Object[0]);
        } catch (StackOverflowError e) {
            errorEscaped = true;
        }
        expect("B：Error 穿透（catch 型別必須是 RuntimeException，不得放寬成 Throwable）", errorEscaped);
    }

    /** D 刀改道點本身：正常委派、失敗清空半成品、Error 穿透。 */
    private static void testLoadArgsUnit(boolean argsGuard) {
        endParse();
        PZNetKahluaTableImpl unscoped = newArgsTable();
        boolean unscopedFailed = false;
        try {
            NetTimedActionGuard.loadArgs(unscoped, argsWire(true), null);
        } catch (NullPointerException e) {
            unscopedFailed = true;
        }
        expect("D：缺 parse 上下文時仍原樣拋錯，不清空共用 decoder 資料、不留拒絕原因",
                unscopedFailed && unscoped.size() == 1 && !NetTimedActionGuard.argsFailedPendingForTest());
        NetTimedActionGuard.beginParse(new NetTimedActionPacket());
        PZNetKahluaTableImpl good = newArgsTable();
        NetTimedActionGuard.loadArgs(good, argsWire(false), null);
        expect("D：正常 load 原樣委派（真位元組解出 1 個條目）、零原因",
                good.size() == 1 && !NetTimedActionGuard.argsFailedPendingForTest());

        PZNetKahluaTableImpl partial = newArgsTable();
        if (argsGuard) {
            NetTimedActionGuard.loadArgs(partial, argsWire(true), null);
            expect("D：decoder 拋 RuntimeException 後半成品條目被清空（parse 的 (byte)(size+1) 與迭代都在空表上）"
                    + "、原因就位",
                    partial.size() == 0 && NetTimedActionGuard.argsFailedPendingForTest());
        } else {
            NullPointerException npe = null;
            try {
                NetTimedActionGuard.loadArgs(partial, argsWire(true), null);
            } catch (NullPointerException e) {
                npe = e;
            }
            expect("D kill switch：直通原版（例外外傳、半成品照原版留著、零原因）",
                    npe != null && partial.size() == 1 && !NetTimedActionGuard.argsFailedPendingForTest());
        }

        boolean errorEscaped = false;
        try {
            NetTimedActionGuard.loadArgs(new ExplodingTable(new StackOverflowError("boom")), null, null);
        } catch (StackOverflowError e) {
            errorEscaped = true;
        }
        expect("D：Error 穿透（catch 型別 RuntimeException）", errorEscaped);
        endParse();
    }

    /**
     * W51：type 17 查不到動物時記一行，解析結果照原版（nil 照位置交給建構子）。歸因只信真的
     * {@code NetTimedAction.parse} 呼叫端；其他 parse 中途離開時殘留的上下文不得冒充別的 decoder 呼叫端。
     */
    private static void testAnimalIdMiss(boolean animalLog) throws Exception {
        ByteArrayOutputStream log = captureLog();
        IsoAnimal animal = alloc(IsoAnimal.class);
        AnimalInstanceManager.getInstance().getAnimals().put(KNOWN_ANIMAL, animal);
        long misses0 = NetTimedActionGuard.animalIdMissesForTest();

        PZNetKahluaTableImpl known = newArgsTable();
        known.load(argsWire(bb -> putAnimalArgs(bb, KNOWN_ANIMAL)), null);
        expect("W51：查得到的動物原樣解析（同一個實例），不記、不計數",
                known.rawget("animal") == animal && animalRows(log).isEmpty()
                && NetTimedActionGuard.animalIdMissesForTest() == misses0);

        NetTimedActionPacket p = new NetTimedActionPacket();
        CALLER.calls = 0;
        p.parse(packetWire(bb -> putAnimalArgs(bb, MISSING_ANIMAL)), null);
        List<String> rows = animalRows(log);
        expect("W51：查不到時照原版把 nil 交給建構子（建構子恰呼叫 1 次、action 照常建立）",
                CALLER.calls == 1 && p.action == MADE);
        if (!animalLog) {
            expect("W51 kill switch：animalIdMiss=0 時不記、不計數",
                    rows.isEmpty() && NetTimedActionGuard.animalIdMissesForTest() == misses0);
            AnimalInstanceManager.getInstance().getAnimals().remove(KNOWN_ANIMAL);
            return;
        }
        expect("W51：NetTimedAction.parse 內查不到動物記恰 1 行，帶 wire ID、動作 type／name 與計數",
                rows.size() == 1 && rows.get(0).contains(ANIMAL_TAG + "id=" + MISSING_ANIMAL
                        + " src=zombie.core.NetTimedAction.parse type=" + ACTION_TYPE
                        + " name=test connectionPlayers=? n=" + (misses0 + 1)));

        NetTimedActionGuard.beginParse(new NetTimedActionPacket());   // 中途離開的 parse 留下的上下文
        newArgsTable().load(argsWire(bb -> putAnimalArgs(bb, MISSING_ANIMAL)), null);
        endParse();
        rows = animalRows(log);
        expect("W51：其他 decoder 呼叫端以真的呼叫者歸因，不借用殘留的 NetTimedAction 上下文",
                rows.size() == 2 && rows.get(1).contains(" src=zombie.mdc.NetTimedActionGuardTest.testAnimalIdMiss ")
                && !rows.get(1).contains(" type="));

        for (int i = 0; i < 30; i++) {
            newArgsTable().load(argsWire(bb -> putAnimalArgs(bb, MISSING_ANIMAL)), null);
        }
        expect("W51：每筆都計數，但逐筆 log 受時間窗上限（30 筆查不到不會寫 30 行）",
                NetTimedActionGuard.animalIdMissesForTest() == misses0 + 32 && animalRows(log).size() - 2 <= 20);
        AnimalInstanceManager.getInstance().getAnimals().remove(KNOWN_ANIMAL);
    }

    // ---- 線路位元組（對照 vanilla 的 Action.parse／NetTimedAction.parse 讀取順序）----

    /** {@code Action.parse} 的 header ＋ type／name ＋ actionArgs。 */
    private static ByteBufferReader packetWire(boolean withComponent) {
        return packetWire(bb -> putArgs(bb, withComponent));
    }

    private static ByteBufferReader packetWire(Consumer<ByteBuffer> args) {
        ByteBuffer bb = ByteBuffer.allocate(512);
        bb.put((byte) 7);                                                   // Action.id
        bb.put((byte) Transaction.TransactionState.Request.ordinal());      // Action.state
        bb.putShort((short) 3);                                             // PlayerID.id
        bb.put((byte) -1);                                                  // PlayerID.playerIndex
        putUTF(bb, ACTION_TYPE);
        putUTF(bb, "test");
        args.accept(bb);
        bb.flip();
        return new ByteBufferReader(bb);
    }

    /** 只有 {@code PZNetKahluaTableImpl.load} 讀的那一段。 */
    private static ByteBufferReader argsWire(boolean withComponent) {
        return argsWire(bb -> putArgs(bb, withComponent));
    }

    private static ByteBufferReader argsWire(Consumer<ByteBuffer> args) {
        ByteBuffer bb = ByteBuffer.allocate(256);
        args.accept(bb);
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

    private static void putAnimalArgs(ByteBuffer bb, short animalId) {
        bb.putInt(1);
        bb.put(SBYT_STRING);
        putUTF(bb, "animal");
        bb.put(SBYT_ANIMAL);
        bb.putShort(animalId);
    }

    private static void putUTF(ByteBuffer bb, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        bb.putShort((short) raw.length);
        bb.put(raw);
    }

    // ---- 替身 ----

    /** 輕量真玩家實例；只設定本測試會使用的身分欄位，不加入世界。 */
    private static zombie.characters.IsoPlayer testPlayer() throws Exception {
        zombie.characters.IsoPlayer player = alloc(zombie.characters.IsoPlayer.class);
        player.setOnlineID((short) 3);
        player.remote = true;
        // 原版 reject 分支的 copyFrom → PlayerID.set → isLocal() 會查 ECS 元件表。
        Field ecs = findField(player.getClass(), "ecsComponentMap");
        ecs.setAccessible(true);
        ecs.set(player, new HashMap<>());
        return player;
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

    private static CaptureConnection captureConnection() throws Exception {
        CaptureConnection c = alloc(CaptureConnection.class);
        c.buffer = ByteBuffer.allocate(4096);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        return c;
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

    /** 以一次成功的 protectedCall 結束 parse 語境（正式路徑上 protectedCall 就是 parse 上下文的終點）。 */
    private static void endParse() {
        NetTimedActionGuard.protectedCall(new StubCaller(LuaReturn.createReturn(new Object[]{ Boolean.TRUE, "ok" })),
                null, null, new Object[0]);
    }

    private static PZNetKahluaTableImpl newArgsTable() {
        return new PZNetKahluaTableImpl(new LinkedHashMap<>());
    }

    /** Kahlua table 需要一個後備 Map（j2se 實作沒有無參建構子）。 */
    private static KahluaTable table() {
        return new KahluaTableImpl(new LinkedHashMap<>());
    }

    /** 只用公開 API 擷取 DebugLog（裸 JVM 預設 Off＝安靜 no-op）。 */
    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    private static List<String> animalRows(ByteArrayOutputStream log) {
        List<String> out = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(ANIMAL_TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    /** 模擬 loadInventoryItem 靜默回 null 的參數形狀（index 2 為 null）。 */
    private static Object[] argsWithNull() {
        return new Object[]{ table(), "chr", null };
    }

    private static boolean flag(String name) throws Exception {
        Field f = NetTimedActionGuard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(null);
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
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "nta pass  " : "nta FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private NetTimedActionGuardTest() {}
}
