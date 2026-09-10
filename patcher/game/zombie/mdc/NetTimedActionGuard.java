package zombie.mdc;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.core.MdcTimedActionProbe;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.IConnection;
import zombie.network.PZNetKahluaTableImpl;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10 卡讀條根治（server-only 改道，client 不需安裝任何東西）。
 *
 * <p><b>症狀</b>：MP client 進度條走滿、動作不完成，該玩家後續排隊動作一起堵死
 * （{@code ISTimedActionQueue} 是單頭序列）。三個獨立缺陷，各一刀：
 * <ol>
 *   <li><b>A 刀 {@link #write}</b>：{@code NetTimedActionPacket.processServer} 對中間物件
 *       {@code act} 呼叫 {@code setState(Reject)}，卻用 {@code this.write(bbw)} 送出，而
 *       {@code this.state} 自 parse 起恆為 {@code Request}（javap 實證）→ client 的
 *       {@code ActionManager.isRejected} 不成立。{@code action == null}（vanilla reject 分支的
 *       判別條件）時把 state 補成 {@code Reject} 再送出。</li>
 *   <li><b>B 刀 {@link #protectedCall}</b>：Lua 建構子索引 {@code loadInventoryItem} 靜默回的
 *       null 參數 → {@code RuntimeException}。Kahlua 的 pcall 多半自己吞下（正式服
 *       {@code caught=0}），本刀是保險絲：攔下後回 {@code isSuccess()==false}，走 vanilla 既有的
 *       {@code action = null; return;}。</li>
 *   <li><b>D 刀 {@link #beginParse}＋{@link #loadArgs}</b>：{@code NetTimedAction.parse} 的
 *       {@code actionArgs.load}（javap offset 68）比 protectedCall（offset 167）更早拋例外時
 *       （正式服 98 次：sbyt 36 CraftBench 的 {@code GameEntityManager.GetEntity} 回 null → NPE），
 *       parse 中斷 → processServer 從未執行 → 既不 Accept 也不 Reject。攔下該例外讓 parse 走完，
 *       由 protectedCall 改道點拒絕。</li>
 * </ol>
 *
 * <p><b>範圍</b>：只在 {@code NetTimedAction.parse} 這一個請求內把「解析失敗」轉成「明確拒絕」。
 * 共用 table decoder（{@code PZNetKahluaTableImpl}）維持原版：不猜測缺席的物件、不代找替代品
 * ——猜錯會消耗錯誤材料或憑空產出成品。整包已是「丟棄」語意（vanilla 也丟），buffer 殘餘無人再讀。
 *
 * <p><b>失敗原因只屬於單一封包</b>：{@link #beginParse} 記下本次 parse 的 packet 並清掉上一包殘留
 * （連線的 packet 物件是重用的）；{@link #write} 只在 receiver 與該 packet 同一實例時採用原因，
 * 且取用即清——序列化拋例外或原因無人消費時，都不會殘留到下一包。
 *
 * <p><b>邊界</b>：{@code Error}（SOE／OOM）一律穿透，三刀的 catch 型別都是
 * {@code RuntimeException}。診斷 log 各自獨立包在自己的方法裡：log 失敗只累計 {@code anomalies}，
 * 絕不能讓封包變成無回覆（那正是本刀要修的症狀）。A 刀不碰 accept 分支
 * （{@code Action.write} 在 {@code Accept} 時不寫 playerId，client 認領不了，補 state 拿不到好處），
 * 也不碰 {@code !isConsistent} 分支（那條 vanilla 先在 {@code copyFrom} NPE，是獨立問題）。
 *
 * <p><b>kill switch</b>：{@code -Dmdc.netTimedActionState=0}（A）、
 * {@code -Dmdc.netTimedActionGuard=0}（B）、{@code -Dmdc.netTimedActionArgs=0}（D）。
 * D 的唯一出口是 A 送出的 Reject，故 A 關閉時 D 一併直通（{@link #ARGS_ACTIVE}）——否則
 * D 只是把「parse 中斷」換成「送出 state=Request 的空回覆」，一樣卡死還多吞一個例外的診斷。
 */
public final class NetTimedActionGuard {

    /** A 刀：回覆封包的 state 補正。 */
    private static final boolean STATE_FIX = !"0".equals(System.getProperty("mdc.netTimedActionState"));
    /** B 刀：Lua 建構子例外攔截。 */
    private static final boolean CALL_GUARD = !"0".equals(System.getProperty("mdc.netTimedActionGuard"));
    /** D 刀：參數反序列化失敗的有聲化。 */
    private static final boolean ARGS_GUARD = !"0".equals(System.getProperty("mdc.netTimedActionArgs"));
    /** D 刀實際生效條件：沒有 A 刀就沒有 Reject 出口，吞下例外只會換一種卡死。 */
    private static final boolean ARGS_ACTIVE = ARGS_GUARD && STATE_FIX;

    /** 失敗原因分類（固定字串，供 log 過濾與統計）。 */
    private static final String CAUSE_ARGS = "argsParse";
    private static final String CAUSE_LUA = "luaCtor";
    private static final String CAUSE_VANILLA = "vanillaNil";

    /** 未補 Reject 的 write 次數（accept，或 A 刀關閉時的全部 write）；heartbeat 的基準。 */
    private static final AtomicLong passthroughWrites = new AtomicLong();
    /** 被補成 Reject 的封包數（A 刀生效次數）。 */
    private static final AtomicLong rejected = new AtomicLong();
    /** 攔下的 Lua 建構子例外數（B 刀生效次數）。 */
    private static final AtomicLong caught = new AtomicLong();
    /** actionArgs.load 拋出的 RuntimeException 數（D 刀攔下）。 */
    private static final AtomicLong argsFailed = new AtomicLong();
    /** 因參數反序列化失敗而在 protectedCall 直接拒絕的封包數。 */
    private static final AtomicLong argsRejected = new AtomicLong();
    /** 被時間窗上限擋掉的逐筆 log 行數。 */
    private static final AtomicLong suppressed = new AtomicLong();
    /** helper 自身的診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    /** 本次 parse 的封包（{@link #beginParse} 設定）；原因只對這個實例有效。 */
    private static final ThreadLocal<NetTimedAction> PARSE_OWNER = new ThreadLocal<>();
    /** 本次 parse 的失敗分類（{@link #CAUSE_ARGS}／{@link #CAUSE_LUA}）。 */
    private static final ThreadLocal<String> PARSE_CAUSE = new ThreadLocal<>();

    /** 逐筆 log 的時間窗上限：每 10 秒最多 20 行（病態情況不刷版，但不永久封頂）。 */
    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    /** heartbeat 週期（以未補 Reject 的 write 計數為節拍）。 */
    private static final long HEARTBEAT_EVERY = 2048L;
    private static final String TAG = "[MinidoracatJavaPatch][NetTimedAction] ";

    // 封包處理是伺服器主執行緒單線；時間窗欄位容忍罕見交錯（只影響 log 節流）。
    private static long windowStartNs;
    private static int windowCount;

    /**
     * D 刀：{@code NetTimedAction.parse} 的 headCall。綁定本次 parse 的封包並清掉上一包的殘留
     * 原因——連線的 packet 物件會重用，identity 比對不足以區分前後兩包。
     */
    public static void beginParse(NetTimedAction packet) {
        PARSE_OWNER.set(packet);
        PARSE_CAUSE.set(null);
    }

    /**
     * D 刀：{@code parse} 內 {@code this.actionArgs.load(b, connection)} 的 1:1 改道。
     *
     * <p>攔下 {@code RuntimeException} 後<b>清空半成品 table</b>：parse 接著會算
     * {@code numParams = (byte)(size + 1)} 並逐一取值，殘留條目會讓長度在 &gt;127 時 i2b 溢位成
     * 負數（{@code NegativeArraySizeException}），或讓迭代取到半解析的值再拋一次——兩者都會讓
     * parse 再次中斷，等於沒修。空表 → {@code numParams == 1} → 只有 classObject，
     * 由 {@link #protectedCall} 直接拒絕。
     */
    public static void loadArgs(PZNetKahluaTableImpl args, ByteBufferReader b, IConnection connection) {
        if (!ARGS_ACTIVE || PARSE_OWNER.get() == null) {
            args.load(b, connection);
            return;
        }
        try {
            args.load(b, connection);
        } catch (RuntimeException e) {
            args.wipe();
            PARSE_CAUSE.set(CAUSE_ARGS);
            long n = argsFailed.incrementAndGet();
            reportArgsFailure(args, b, connection, e, n);
        }
    }

    /**
     * B 刀：{@code parse} 內唯一 {@code LuaCaller.protectedCall} 呼叫點的改道。
     *
     * <p>成功路徑直接委派。失敗路徑回
     * {@code LuaReturn.createReturn(new Object[]{ Boolean.FALSE, msg })}——{@code createReturn}
     * 以 {@code returnValues[0]} 決定 {@code LuaSuccess}／{@code LuaFail}，故必得
     * {@code isSuccess()==false}，落進 vanilla 的 {@code action = null; return;}。
     *
     * <p>本封包參數已解析失敗時<b>不呼叫</b> Lua 建構子（{@code arguments[]} 不完整），直接回
     * LuaFail；該分支與 B 刀的 kill switch 無關（是 D 刀的語意）。
     */
    public static LuaReturn protectedCall(LuaCaller caller, KahluaThread thread, Object fn, Object[] args) {
        if (PARSE_CAUSE.get() != null) {
            argsRejected.incrementAndGet();
            return LuaReturn.createReturn(new Object[]{ Boolean.FALSE, "mdc: timed action args unusable" });
        }
        if (!CALL_GUARD) {
            return caller.protectedCall(thread, fn, args);
        }
        try {
            return caller.protectedCall(thread, fn, args);
        } catch (RuntimeException e) {
            PARSE_CAUSE.set(CAUSE_LUA);
            long n = caught.incrementAndGet();
            reportLuaFailure(args, e, n);
            return LuaReturn.createReturn(new Object[]{ Boolean.FALSE, "mdc: rejected timed action (" + e + ")" });
        }
    }

    /**
     * A 刀：{@code processServer} 內兩處 {@code this.write(bbw)} 的改道（accept 與 reject 各一）。
     *
     * <p>{@code setState}、{@code packet.write} 與計數都在診斷 try 之外：本體失敗必須 fail-fast
     * 交給外層封包錯誤處理，而診斷失敗不得阻止線路寫入。原因在寫線路前就取走並清空，
     * {@code packet.write} 拋例外時也不會殘留給下一包。
     */
    public static void write(NetTimedActionPacket packet, ByteBufferWriter b) {
        boolean reject = STATE_FIX && packet.action == null;
        String cause = takeCause(packet);
        if (reject) {
            packet.setState(Transaction.TransactionState.Reject);
        }
        packet.write(b);

        if (reject) {
            reportReject(packet, cause, rejected.incrementAndGet());
        } else if (passthroughWrites.incrementAndGet() % HEARTBEAT_EVERY == 0L) {
            heartbeat();
        }
    }

    /** 取走本封包的失敗原因；非本封包（或無人設定）回 null，兩種情況都清空狀態。 */
    static String takeCause(NetTimedActionPacket packet) {
        NetTimedAction owner = PARSE_OWNER.get();
        String cause = PARSE_CAUSE.get();
        PARSE_OWNER.set(null);
        PARSE_CAUSE.set(null);
        return owner == packet ? cause : null;
    }

    /** 「serialized」而非「sent」：本刀只負責把 Reject 寫進線路，送達與否不在此處可知。 */
    private static void reportReject(NetTimedActionPacket packet, String cause, long n) {
        if (!allowLine()) {
            return;
        }
        try {
            DebugLog.log(TAG + "reject serialized"
                    + " cause=" + (cause == null ? CAUSE_VANILLA : cause)
                    + " type=" + MdcTimedActionProbe.safeName(packet.type) + " name=" + MdcTimedActionProbe.safeName(packet.name)
                    + " player=" + MdcTimedActionProbe.playerNameOf(packet)
                    + " n=" + n);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    private static void reportArgsFailure(PZNetKahluaTableImpl args, ByteBufferReader reader,
            IConnection connection, RuntimeException e, long n) {
        if (!allowLine()) {
            return;
        }
        try {
            NetTimedAction packet = PARSE_OWNER.get();
            DebugType.General.printException(e, TAG + "args parse failed"
                    + " type=" + MdcTimedActionProbe.safeName(packet == null ? null : packet.type)
                    + " name=" + MdcTimedActionProbe.safeName(packet == null ? null : packet.name)
                    + " connectionPlayers=" + (connection instanceof UdpConnection udp
                        ? MdcTimedActionProbe.connectionPlayers(udp) : "?")
                    + componentReference(args, reader, e) + " n=" + n, LogSeverity.Warning);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    /**
     * 只辨認原版 loadComponent 的 NPE；該方法依序消費 long＋short 後才解參考。
     * SmokeCheck 鎖定十位元組佈局與無 catch 的上拋鏈；絕對讀取不改 reader 的 position/limit。
     * stack 不明、其他 decoder 或自訂 table 一律不猜；netId 是 wire 值，不解碼成替代物件。
     */
    static String componentReference(PZNetKahluaTableImpl args, ByteBufferReader reader, RuntimeException failure) {
        if (!(failure instanceof NullPointerException) || args == null
                || args.getClass() != PZNetKahluaTableImpl.class || reader == null || reader.bb == null) {
            return " componentRef=unavailable";
        }
        StackTraceElement[] stack = failure.getStackTrace();
        if (stack.length == 0 || !"zombie.network.PZNetKahluaTableImpl".equals(stack[0].getClassName())
                || !"loadComponent".equals(stack[0].getMethodName())) {
            return " componentRef=unavailable";
        }
        ByteBuffer wire = reader.bb;
        int end = wire.position();
        if (end < Long.BYTES + Short.BYTES) {
            return " componentRef=unavailable";
        }
        return " componentRef=wire netId=" + wire.getLong(end - Long.BYTES - Short.BYTES)
                + " componentId=" + wire.getShort(end - Short.BYTES) + " readerPos=" + end;
    }

    /**
     * 攔截現場的診斷：action 型別（{@code arguments[0]} 的 class table 的 {@code Type}）、
     * 哪幾個參數位置是 null（{@code loadInventoryItem} 靜默回 null 的直接指紋）、例外訊息。
     */
    private static void reportLuaFailure(Object[] args, RuntimeException e, long n) {
        if (!allowLine()) {
            return;
        }
        try {
            StringBuilder nulls = new StringBuilder();
            if (args != null) {
                for (int i = 1; i < args.length; i++) {
                    if (args[i] == null) {
                        if (nulls.length() > 0) {
                            nulls.append('/');
                        }
                        nulls.append(i);
                    }
                }
            }
            DebugType.General.printException(e, TAG + "lua ctor failed"
                    + " type=" + MdcTimedActionProbe.safeName(typeOf(args))
                    + " args=" + (args == null ? -1 : args.length)
                    + " nullArgs=" + (nulls.length() == 0 ? "none" : nulls.toString())
                    + " n=" + n, LogSeverity.Error);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }


    private static String typeOf(Object[] args) {
        if (args != null && args.length > 0 && args[0] instanceof KahluaTable table) {
            Object t = table.rawget("Type");
            if (t != null) {
                return String.valueOf(t);
            }
        }
        return "?";
    }


    /** 時間窗節流：每 {@link #WINDOW_NS} 最多 {@link #WINDOW_CAP} 行，超限只累計 suppressed。 */
    private static boolean allowLine() {
        long now = System.nanoTime();
        if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed.incrementAndGet();
            return false;
        }
        windowCount++;
        return true;
    }

    private static void heartbeat() {
        try {
            DebugLog.log(TAG + "writes=" + passthroughWrites.get()
                    + " rejected=" + rejected.get() + " caught=" + caught.get()
                    + " argsFailed=" + argsFailed.get() + " argsRejected=" + argsRejected.get()
                    + " suppressed=" + suppressed.get() + " anomalies=" + anomalies.get()
                    + " stateFix=" + (STATE_FIX ? 1 : 0) + " guard=" + (CALL_GUARD ? 1 : 0)
                    + " args=" + (ARGS_GUARD ? 1 : 0) + " argsActive=" + (ARGS_ACTIVE ? 1 : 0));
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    // ---- 測試存取器 ----

    static long rejectedForTest() {
        return rejected.get();
    }

    static long caughtForTest() {
        return caught.get();
    }

    static long argsFailedForTest() {
        return argsFailed.get();
    }

    static long argsRejectedForTest() {
        return argsRejected.get();
    }

    static long anomaliesForTest() {
        return anomalies.get();
    }

    static String causeForTest() {
        return PARSE_CAUSE.get();
    }

    private NetTimedActionGuard() {}
}
