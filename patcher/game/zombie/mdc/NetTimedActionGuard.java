package zombie.mdc;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.core.MdcTimedActionProbe;
import zombie.core.NetTimedAction;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.IConnection;
import zombie.network.PZNetKahluaTableImpl;
import zombie.network.fields.character.AnimalID;

/**
 * W10 卡讀條根治（server-only 改道，client 不需安裝任何東西）。
 *
 * <p><b>症狀</b>：MP client 進度條走滿、動作不完成，該玩家後續排隊動作一起堵死
 * （{@code ISTimedActionQueue} 是單頭序列）。{@code NetTimedAction.parse} 中斷時
 * {@code processServer} 從未執行，server 既不 Accept 也不 Reject。兩刀讓 parse 走完、落進原版
 * {@code action = null} 分支，由 42.21 原版 {@code processServer} 以正確物件回送 Reject：
 * <ol>
 *   <li><b>B 刀 {@link #protectedCall}</b>：Lua 建構子索引 {@code loadInventoryItem} 靜默回的
 *       null 參數 → {@code RuntimeException}。Kahlua 的 pcall 多半自己吞下（正式服
 *       {@code caught=0}），本刀是保險絲：攔下後回 {@code isSuccess()==false}，走 vanilla 既有的
 *       {@code action = null; return;}。</li>
 *   <li><b>D 刀 {@link #beginParse}＋{@link #loadArgs}</b>：{@code actionArgs.load}（javap offset 68）
 *       比 protectedCall（offset 167）更早拋例外時（例：sbyt 36 CraftBench 的
 *       {@code GameEntityManager.GetEntity} 回 null → NPE），攔下該例外讓 parse 走完，由 protectedCall
 *       改道點拒絕。</li>
 * </ol>
 * 42.20.4 另有 A 刀補正初始 Reject 的 state；42.21 原版改以 {@code act.write} 送出（已修），A 刀退役，
 * D 刀的 Reject 出口從此就是原版本身。
 *
 * <p><b>範圍</b>：只在 {@code NetTimedAction.parse} 這一個請求內把「解析失敗」轉成「明確拒絕」。
 * 共用 table decoder（{@code PZNetKahluaTableImpl}）維持原版：不猜測缺席的物件、不代找替代品
 * ——猜錯會消耗錯誤材料或憑空產出成品。整包已是「丟棄」語意（vanilla 也丟），buffer 殘餘無人再讀。
 *
 * <p><b>失敗原因只屬於單一 parse</b>：{@link #beginParse} 記下本次 parse 的 packet 並清掉上一包殘留
 * （連線的 packet 物件是重用的）；{@link #protectedCall} 取用即清。
 *
 * <p><b>邊界</b>：{@code Error}（SOE／OOM）一律穿透，兩刀的 catch 型別都是
 * {@code RuntimeException}。診斷 log 各自獨立包在自己的方法裡：log 失敗只累計 {@code anomalies}，
 * 絕不能讓 parse 中斷（那正是本刀要修的症狀）。不碰 {@code !isConsistent} 分支。
 *
 * <p><b>kill switch</b>：{@code -Dmdc.netTimedActionGuard=0}（B）、{@code -Dmdc.netTimedActionArgs=0}（D）、
 * {@code -Dmdc.animalIdMiss=0}（W51）。
 *
 * <p><b>W51 動物 ID 解析失敗紀錄</b>（{@link #parseAnimalId}，純觀測）：共用 table decoder 的 type 17（IsoAnimal）
 * 查不到 client 指定的 online ID 時，原版把 null 交給呼叫端（Lua 動作拿到 nil 動物），不留任何紀錄。
 * 本刀只在 null 時記一行；解析結果與例外照原版。
 */
public final class NetTimedActionGuard {

    /** B 刀：Lua 建構子例外攔截。 */
    private static final boolean CALL_GUARD = !"0".equals(System.getProperty("mdc.netTimedActionGuard"));
    /** D 刀：參數反序列化失敗的有聲化。 */
    private static final boolean ARGS_GUARD = !"0".equals(System.getProperty("mdc.netTimedActionArgs"));
    /** W51：伺服器查不到 client 指定的動物時記一行。 */
    private static final boolean ANIMAL_ID_LOG = !"0".equals(System.getProperty("mdc.animalIdMiss"));

    /** beginParse 次數；heartbeat 的節拍。 */
    private static final AtomicLong parses = new AtomicLong();
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
    /** W51：type 17 解析查不到動物的次數。 */
    private static final AtomicLong animalIdMisses = new AtomicLong();

    /** 本次 parse 的封包（{@link #beginParse} 設定）；D 刀只在有 parse 上下文時生效。 */
    private static final ThreadLocal<NetTimedAction> PARSE_OWNER = new ThreadLocal<>();
    /** 本次 parse 的參數解析失敗（{@link #loadArgs} 設定、{@link #protectedCall} 取用即清）。 */
    private static final ThreadLocal<Boolean> ARGS_FAILED = new ThreadLocal<>();

    /** 逐筆 log 的時間窗上限：每 10 秒最多 20 行（病態情況不刷版，但不永久封頂）。 */
    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    /** heartbeat 週期（以 parse 計數為節拍）。 */
    private static final long HEARTBEAT_EVERY = 2048L;
    private static final String TAG = "[MinidoracatJavaPatch][NetTimedAction] ";
    private static final String ANIMAL_TAG = "[MinidoracatJavaPatch][AnimalIdMiss] ";

    // 封包處理是伺服器主執行緒單線；時間窗欄位容忍罕見交錯（只影響 log 節流）。
    private static long windowStartNs;
    private static int windowCount;

    /**
     * D 刀：{@code NetTimedAction.parse} 的 headCall。綁定本次 parse 的封包並清掉上一包的殘留
     * 原因——連線的 packet 物件會重用，identity 比對不足以區分前後兩包。
     */
    public static void beginParse(NetTimedAction packet) {
        PARSE_OWNER.set(packet);
        ARGS_FAILED.remove();
        if (parses.incrementAndGet() % HEARTBEAT_EVERY == 0L) {
            heartbeat();
        }
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
        if (!ARGS_GUARD || PARSE_OWNER.get() == null) {
            args.load(b, connection);
            return;
        }
        try {
            args.load(b, connection);
        } catch (RuntimeException e) {
            args.wipe();
            ARGS_FAILED.set(Boolean.TRUE);
            long n = argsFailed.incrementAndGet();
            reportArgsFailure(args, b, connection, e, n);
        }
    }

    /**
     * B 刀：{@code parse} 內唯一 {@code LuaCaller.protectedCall} 呼叫點的改道；也是本次 parse 上下文的終點。
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
        boolean argsUnusable = ARGS_FAILED.get() != null;
        PARSE_OWNER.remove();
        ARGS_FAILED.remove();
        if (argsUnusable) {
            argsRejected.incrementAndGet();
            return LuaReturn.createReturn(new Object[]{ Boolean.FALSE, "mdc: timed action args unusable" });
        }
        if (!CALL_GUARD) {
            return caller.protectedCall(thread, fn, args);
        }
        try {
            return caller.protectedCall(thread, fn, args);
        } catch (RuntimeException e) {
            long n = caught.incrementAndGet();
            reportLuaFailure(args, e, n);
            return LuaReturn.createReturn(new Object[]{ Boolean.FALSE, "mdc: rejected timed action (" + e + ")" });
        }
    }

    /**
     * W51：{@code PZNetKahluaTableImpl.load(ByteBufferReader, IConnection, byte)} type 17 唯一
     * {@code AnimalID.parse} 的 1:1 改道。原呼叫在 try 之外：解析結果與例外都照原版，查不到動物時才記一行。
     * {@code type}／{@code name} 只在呼叫端確定是 {@code NetTimedAction.parse} 時才取 {@link #PARSE_OWNER}
     * ——其他 parse 中途離開時它會殘留，不能拿來歸因別的封包。
     */
    public static void parseAnimalId(AnimalID id, ByteBufferReader b, IConnection connection) {
        id.parse(b, connection);
        if (!ANIMAL_ID_LOG || id.getAnimal() != null) {
            return;
        }
        long n = animalIdMisses.incrementAndGet();
        if (!allowLine()) {
            return;
        }
        try {
            String src = decoderCaller();
            NetTimedAction packet = "zombie.core.NetTimedAction.parse".equals(src) ? PARSE_OWNER.get() : null;
            DebugLog.log(ANIMAL_TAG + "id=" + id.getID() + " src=" + src
                    + (packet == null ? "" : " type=" + MdcTimedActionProbe.safeName(packet.type)
                            + " name=" + MdcTimedActionProbe.safeName(packet.name))
                    + " connectionPlayers=" + (connection instanceof UdpConnection udp
                        ? MdcTimedActionProbe.connectionPlayers(udp) : "?")
                    + " n=" + n);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    /** 第一個不在 table decoder 與本 helper 內的 frame＝誰在解這張 table；只在要寫 log 時才走 stack。 */
    private static String decoderCaller() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(f -> f.getClassName() + "." + f.getMethodName())
                .filter(n -> !n.startsWith("zombie.network.PZNetKahluaTableImpl.")
                        && !n.startsWith("zombie.mdc.NetTimedActionGuard."))
                .findFirst().orElse("?"));
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
            DebugLog.log(TAG + "parses=" + parses.get()
                    + " caught=" + caught.get()
                    + " argsFailed=" + argsFailed.get() + " argsRejected=" + argsRejected.get()
                    + " suppressed=" + suppressed.get() + " anomalies=" + anomalies.get()
                    + " animalIdMisses=" + animalIdMisses.get()
                    + " guard=" + (CALL_GUARD ? 1 : 0) + " args=" + (ARGS_GUARD ? 1 : 0)
                    + " animalIdLog=" + (ANIMAL_ID_LOG ? 1 : 0));
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    // ---- 測試存取器 ----

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

    static long animalIdMissesForTest() {
        return animalIdMisses.get();
    }

    /** 本次 parse 是否留有未消費的參數解析失敗。 */
    static boolean argsFailedPendingForTest() {
        return ARGS_FAILED.get() != null;
    }

    /** 本次 parse 上下文是否仍綁著封包（protectedCall 後應已釋放）。 */
    static boolean parseBoundForTest() {
        return PARSE_OWNER.get() != null;
    }

    private NetTimedActionGuard() {}
}
