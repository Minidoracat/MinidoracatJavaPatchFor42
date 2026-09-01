package zombie.mdc;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferWriter;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10 卡讀條根治的行為驗證（兩刀各自的語意＋兩個 kill switch 的降級路徑）。
 *
 * <p>argv：無參數＝兩刀啟用；{@code guard-off}／{@code state-off} 各對應一個 kill switch。
 * 測試會反射自驗 helper 的實際旗標與 argv 相符——property 名稱打錯時炸在測試裡，
 * 不會默默把 enabled 版跑三遍假綠（沿用 ChunkLoadGuardTest 的紀律）。
 */
public final class NetTimedActionGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "both";
        boolean wantGuard = !"guard-off".equals(mode);
        boolean wantState = !"state-off".equals(mode);

        boolean guard = flag("CALL_GUARD");
        boolean state = flag("STATE_FIX");
        expect("自驗：argv=" + mode + " 與 helper 實際旗標相符（guard=" + guard + " state=" + state + "）",
                guard == wantGuard && state == wantState);

        // ---- B 刀：Lua 建構子例外攔截 ----
        LuaReturn ok = LuaReturn.createReturn(new Object[]{ Boolean.TRUE, "ok" });
        expect("B：成功路徑原樣委派（回傳同一個 LuaReturn 實例）",
                NetTimedActionGuard.protectedCall(new StubCaller(ok, null), null, null, new Object[0]) == ok);

        RuntimeException luaErr = new RuntimeException("attempted index: getContainer of non-table: null");
        if (guard) {
            LuaReturn r = NetTimedActionGuard.protectedCall(
                    new StubCaller(null, luaErr), null, null, argsWithNull());
            expect("B：Lua 建構子例外被攔下，回傳 isSuccess()==false（落進 vanilla 的 action=null 路徑）",
                    r != null && !r.isSuccess());
        } else {
            boolean rethrown = false;
            try {
                NetTimedActionGuard.protectedCall(new StubCaller(null, luaErr), null, null, argsWithNull());
            } catch (RuntimeException e) {
                rethrown = e == luaErr;
            }
            expect("B kill switch：guard=0 時例外原樣外傳（vanilla 行為）", rethrown);
        }

        // Error 必須穿透（SOE／OOM 不得被降級成一個安靜的 reject）——與 W6 同紀律
        boolean errorEscaped = false;
        try {
            NetTimedActionGuard.protectedCall(new StubCaller(null, new StackOverflowError("boom")),
                    null, null, new Object[0]);
        } catch (StackOverflowError e) {
            errorEscaped = true;
        }
        expect("B：Error 穿透（catch 型別必須是 RuntimeException，不得放寬成 Throwable）", errorEscaped);

        // ---- A 刀：回覆封包的 state 補正 ----
        // 用替身封包攔下 write：vanilla 的 NetTimedAction.write 會讀 GameClient.client，
        // 觸發 GameClient→ServerOptions→Rand 的 static 初始化鏈（測試 JVM 沒有那些前置）。
        // 替身讓斷言聚焦在本 helper 真正負責的兩件事：state 是否補正、是否一律委派 write。
        ProbePacket nullAction = packet(null);
        NetTimedActionGuard.write(nullAction, writer());
        if (state) {
            expect("A：action==null（vanilla 的 reject 分支判別）時 state 補成 Reject",
                    nullAction.currentState() == Transaction.TransactionState.Reject);
        } else {
            expect("A kill switch：state=0 時 state 保持 Request（vanilla 行為）",
                    nullAction.currentState() == Transaction.TransactionState.Request);
        }
        expect("A：線路寫入一律委派（不論是否介入 state）", nullAction.written == 1);

        // accept 分支永不介入——補正 Accept 拿不到好處，見 helper javadoc。
        ProbePacket withAction = packet(table());
        NetTimedActionGuard.write(withAction, writer());
        expect("A：action!=null（accept 分支）時 state 一律不動且照樣委派 write",
                withAction.currentState() == Transaction.TransactionState.Request && withAction.written == 1);

        if (failed > 0) {
            System.out.println("net-timed-action FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("net-timed-action OK  mode=" + mode
                + "：委派/攔截/Error 穿透/state 補正/accept 不動/線路寫入全數通過");
    }

    /** 模擬 loadInventoryItem 靜默回 null 的參數形狀（index 2 為 null）。 */
    private static Object[] argsWithNull() {
        return new Object[]{ table(), "chr", null };
    }

    /** Kahlua table 需要一個後備 Map（j2se 實作沒有無參建構子）。 */
    private static KahluaTable table() {
        return new KahluaTableImpl(new java.util.LinkedHashMap<>());
    }

    private static ByteBufferWriter writer() {
        return new ByteBufferWriter(ByteBuffer.allocate(4096));
    }

    /** state=Request 的替身封包（processServer 只在 Request 時走到那兩個 write）。 */
    private static ProbePacket packet(KahluaTable action) {
        ProbePacket p = new ProbePacket();
        p.type = "ISEatFoodAction";
        p.name = "test";
        p.action = action;
        p.setState(Transaction.TransactionState.Request);
        return p;
    }

    /**
     * 只記錄「被委派了幾次」的替身。override 的是 vanilla 的 write——helper 對
     * {@code packet.write(b)} 是虛擬派送，所以會落到這裡，不觸發 GameClient 的 static 初始化。
     */
    private static final class ProbePacket extends NetTimedActionPacket {
        private int written;

        @Override
        public void write(ByteBufferWriter b) {
            written++;
        }

        private Transaction.TransactionState currentState() {
            return state;
        }
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

    /** 可控的 LuaCaller 替身：回固定值或拋固定 Throwable。 */
    private static final class StubCaller extends LuaCaller {
        private final LuaReturn result;
        private final Throwable throwable;

        StubCaller(LuaReturn result, Throwable throwable) {
            super(new KahluaConverterManager());
            this.result = result;
            this.throwable = throwable;
        }

        @Override
        public LuaReturn protectedCall(KahluaThread thread, Object fn, Object... args) {
            if (throwable instanceof RuntimeException re) {
                throw re;
            }
            if (throwable instanceof Error err) {
                throw err;
            }
            return result;
        }
    }

    private NetTimedActionGuardTest() {}
}
