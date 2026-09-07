package zombie.mdc;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.characters.IsoPlayer;
import zombie.core.MdcTimedActionProbe;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.entity.Component;
import zombie.entity.ComponentType;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityManager;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.IConnection;
import zombie.network.PZNetKahluaTableImpl;
import zombie.network.ServerMap;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10 卡讀條根治（2026-08-23；玩家回報「製作／搬移／吃／閱讀讀條走滿卻不完成」）
 * ＋ W10-D 參數反序列化失敗的有聲化與 CraftBench 座標救回（2026-09-07）。
 *
 * <p><b>症狀</b>：MP client 的進度條走到 100%、動作動畫持續 loop、成品不產出，
 * 且該玩家後續所有排隊動作一起堵死（{@code ISTimedActionQueue} 是單頭序列）。
 * 正式服 log 實測一個 session 內 12 次，分佈為 {@code ISMoveablesAction.lua:308} ×6、
 * {@code ISReadABook.lua:492} ×3、{@code ISEatFoodAction.lua:298} ×3。
 *
 * <p><b>機制</b>（vanilla，兩個獨立缺陷疊乘）：
 * <ol>
 *   <li><b>例外中斷封包處理</b>：{@code NetTimedAction.parse} 在 server 端用
 *       {@code LuaCaller.protectedCall} 重建 Lua action。參數中的 {@code InventoryItem}
 *       以「容器 ID + item ID」傳輸，{@code PZNetKahluaTableImpl.loadInventoryItem} 在
 *       容器或 item 查不到時<b>靜默回 null</b>；該 null 直接成為 Lua 建構子參數，
 *       建構子首行就索引它（{@code item:getContainer()}／{@code item:getSkillTrained()}／
 *       {@code item:getWorldSprite()}）→ Kahlua 拋 {@code RuntimeException}。
 *       正式服實測（2026-09，{@code caught=0} 而 {@code rejected>0}）：Kahlua 的 pcall
 *       自己吞下這個例外並回 {@code isSuccess()==false}，vanilla 的 {@code action = null; return;}
 *       路徑確實被走到——真正讓 client 卡死的是下一條。B 刀維持為保險絲。</li>
 *   <li><b>initial Request 回覆帶錯 state</b>：{@code NetTimedActionPacket.processServer} 對
 *       中間物件 {@code act} 呼叫 {@code setState(Accept/Reject)}，卻用
 *       {@code this.write(bbw)} 送出，而 {@code this.state} 自 parse 起恆為
 *       {@code Request}（javap 實證：offset 81／142 皆為 {@code aload_0}）。
 *       因此該方法的<b>初始拒絕回覆</b>無法讓 client 的
 *       {@code ActionManager.isRejected} 成立；已接受 action 在
 *       {@code ActionManager.update} 中因 {@code perform()==false} 產生的後續 Reject
 *       是從正確的 action 物件序列化，不受此缺陷影響。同 codebase 的
 *       {@code ItemTransactionPacket.processServer} 也是寫對的對照。</li>
 * </ol>
 *
 * <p><b>W10-D（2026-09-07，正式服 8/31–9/7 共 98 次 log 定罪）</b>：第三條靜默路徑在
 * {@code parse} 的 {@code this.actionArgs.load(b, connection)}（javap offset 68）——比
 * {@code protectedCall}（offset 167）更早。{@code PZNetKahluaTableImpl.load(...,byte)} 的
 * sbyt 36（{@code CraftBench} component）呼叫 {@code loadComponent}，後者對
 * {@code GameEntityManager.GetEntity(netID)} 的回傳<b>不檢查 null</b>就
 * {@code getComponent} → NPE → parse 中斷 → {@code processServer} 從未執行 → 既不 Accept
 * 也不 Reject（正式服 stack：{@code PZNetKahluaTableImpl.loadComponent:531 ←
 * load:689 ← load:546 ← NetTimedAction.parse:155}）。B 刀的掛點根本到不了。
 * 觸發情境＝「搬過的鐵桶／製作台」：{@code IsoObject.getEntityNetID()} 是
 * {@code x + (y<<16) + (z<<32) + (objectIndex<<40)} 算出來的，client/server 各自從自己那格
 * {@code square.getObjects()} 的順序算；搬移後 index 變、兩端順序不一致、server 端 map
 * 只在被讀取時才重算（lazy），client 送來的號碼在 server 找不到。
 *
 * <p><b>本 helper 的改道</b>（皆 server-only 路徑，client 不需安裝任何東西）：
 * <ul>
 *   <li>{@link #protectedCall}（B 刀，{@code -Dmdc.netTimedActionGuard=0} 停用）：
 *       攔下 Lua 建構子的 {@code RuntimeException}，回一個 {@code isSuccess()==false} 的
 *       {@code LuaReturn}，讓 vanilla 既有的 {@code action = null; return;} 真正被走到。
 *       <b>catch 型別鎖定 {@code RuntimeException}</b>——{@code Error}（SOE／OOM）必須穿透。
 *       W10-D 起：若同一封包的參數反序列化已失敗（ThreadLocal 旗標），<b>不呼叫</b> Lua
 *       建構子（{@code arguments[]} 不完整）、直接回 {@code LuaFail}，走同一條 vanilla 路徑。</li>
 *   <li>{@link #write}（A 刀，{@code -Dmdc.netTimedActionState=0} 停用）：
 *       {@code action == null}（即 vanilla 走 reject 分支的判別條件）時把 state 補成
 *       {@code Reject} 再送出，client 收到後 {@code isRejected} 成立 →
 *       {@code forceStop()} → 動作乾淨取消、queue 解除堵塞。log 記玩家名與拒絕原因
 *       （2026-09-07 起，供統計與向玩家索取 client log）。</li>
 *   <li>{@link #loadArgs}（D 刀，{@code -Dmdc.netTimedActionArgs=0} 停用）：
 *       {@code parse} 內 {@code actionArgs.load} 的 1:1 改道；{@code RuntimeException}
 *       （NPE／BufferUnderflow／未知 sbyt）攔下後設旗標，parse 照常走到
 *       {@code protectedCall} 改道點被拒絕。整包已是「丟棄」語意（vanilla 也丟），buffer
 *       殘餘無人再讀。</li>
 *   <li>{@link #loadComponent}（D 刀，同一 kill switch）：{@code PZNetKahluaTableImpl.load}
 *       內 {@code loadComponent} 的 1:1 改道。{@code GetEntity} 命中＝逐語意等價；
 *       未命中時解碼 netID 內含的座標，到該格找<b>恰好一個</b>帶該 component 的
 *       {@code IsoObject} 救回（並呼叫其 {@code getEntityNetID()} 讓 server map 更新），
 *       否則設旗標讓整包被拒絕。救回的三道門：座標在合法範圍、該格在<b>該連線玩家 12 格內</b>
 *       （{@code InventoryItem} 的 netID 是 item id，誤解碼成座標會落在地圖任意處——
 *       距離門把這種誤命中擋掉）、該格恰一個候選（兩個以上不猜）。</li>
 * </ul>
 *
 * <p><b>刻意不做的事</b>：不猜測、不代找那個 null 的 {@code InventoryItem}。
 * 猜錯會消耗錯誤材料或憑空產出成品。本刀的語意是「把靜默的永久卡死變成有聲的失敗」
 * ——玩家看到動作中斷可重試，而非無限讀條。CraftBench 座標救回是唯一例外：
 * 座標＋component 型別＋「該格唯一」三個條件都成立時，物件身分沒有第二種解讀。
 *
 * <p><b>A 刀的範圍界定</b>：只在 {@code action == null} 時介入。reject 分支的另一個
 * 進入條件（{@code !isConsistent}）不在此列——那條路徑的 {@code getAction()} →
 * {@code Action.copyFrom} 會對 null player 呼叫 {@code PlayerID.set} 而先行 NPE，
 * 是既有的獨立問題，維持 vanilla 行為。Accept 分支同樣不介入：{@code Action.write}
 * 在 {@code state == Accept} 時<b>不寫 playerId</b>，而 client 的
 * {@code ActionManager.setStateFromPacket} 要靠 playerId 比對認領封包
 * （{@code IDShort.id} 預設 0，對不上真實 onlineID），故補正 Accept 的 state 只會改變
 * 線路內容而拿不到任何好處——修它需要改 {@code Action.write}／{@code parse} 的線路格式，
 * 那個類 client 與 server 共用，單邊修改會讓對側讀錯位元組。
 */
public final class NetTimedActionGuard {

    /** A 刀：回覆封包的 state 補正。 */
    private static final boolean STATE_FIX = !"0".equals(System.getProperty("mdc.netTimedActionState"));
    /** B 刀：Lua 建構子例外攔截。 */
    private static final boolean CALL_GUARD = !"0".equals(System.getProperty("mdc.netTimedActionGuard"));
    /** D 刀：參數反序列化失敗的有聲化＋CraftBench 座標救回。 */
    private static final boolean ARGS_GUARD = !"0".equals(System.getProperty("mdc.netTimedActionArgs"));

    /** 未補 Reject 的 write 次數（accept，或 A 刀關閉時的全部 write）；heartbeat 的基準。 */
    private static final AtomicLong passthroughWrites = new AtomicLong();
    /** 被補成 Reject 的封包數（A 刀生效次數）。 */
    private static final AtomicLong rejected = new AtomicLong();
    /** 攔下的 Lua 建構子例外數（B 刀生效次數）。 */
    private static final AtomicLong caught = new AtomicLong();
    /** actionArgs.load 拋出的 RuntimeException 數（D 刀攔下）。 */
    private static final AtomicLong argsFailed = new AtomicLong();
    /** loadComponent 在 server 找不到 entity 的次數（含救回與未救回）。 */
    private static final AtomicLong componentMissing = new AtomicLong();
    /** 由座標救回的 component 數。 */
    private static final AtomicLong componentRecovered = new AtomicLong();
    /** 因參數反序列化失敗而在 protectedCall 直接拒絕的封包數。 */
    private static final AtomicLong argsRejected = new AtomicLong();
    /** helper 自身的診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    /** 同一封包內 parse → protectedCall → processServer 的失敗原因傳遞（主執行緒單線）。 */
    private static final ThreadLocal<String> ARGS_FAILURE = new ThreadLocal<>();
    /** protectedCall 拒絕後留給 A 刀 log 的原因（消費即清）。 */
    private static final ThreadLocal<String> LAST_REASON = new ThreadLocal<>();

    /** 逐筆詳細 log 的上限，之後只計數（病態情況不得洪水刷 console）。 */
    private static final long DETAIL_LIMIT = 32L;
    /** 拒絕／救回是玩家層級的統計資料，逐筆上限放寬（正式服每天數十筆）。 */
    private static final long PLAYER_DETAIL_LIMIT = 4096L;
    /** heartbeat 週期（以未補 Reject 的 write 計數為節拍）。 */
    private static final long HEARTBEAT_EVERY = 2048L;
    /** 座標救回時，該格與該連線玩家的最大切比雪夫距離（格）。 */
    private static final int RECOVER_RANGE = 12;
    private static final String TAG = "[MinidoracatJavaPatch][NetTimedAction] ";

    /**
     * B 刀：{@code NetTimedAction.parse} 內唯一 {@code LuaCaller.protectedCall} 呼叫點的改道目標。
     *
     * <p>成功路徑逐指令等價（直接委派）。失敗路徑回傳
     * {@code LuaReturn.createReturn(new Object[]{ Boolean.FALSE, msg })} ——
     * {@code createReturn} 以 {@code returnValues[0]} 的 Boolean 決定產生
     * {@code LuaSuccess} 或 {@code LuaFail}，故此處必得 {@code isSuccess()==false}，
     * 恰好落進 vanilla 的 {@code action = null; return;}。
     *
     * <p>W10-D：{@link #loadArgs}／{@link #loadComponent} 已宣告本封包參數不完整時，
     * 不呼叫 Lua 建構子——直接回 LuaFail。
     */
    public static LuaReturn protectedCall(LuaCaller caller, KahluaThread thread, Object fn, Object[] args) {
        String failure = ARGS_FAILURE.get();
        if (failure != null) {
            ARGS_FAILURE.set(null);
            LAST_REASON.set(failure);
            long n = argsRejected.incrementAndGet();
            if (n <= DETAIL_LIMIT) {
                DebugLog.log(TAG + "args rejected type=" + typeOf(args) + " reason=" + failure + " n=" + n);
            }
            return LuaReturn.createReturn(new Object[]{ Boolean.FALSE, "mdc: " + failure });
        }
        if (!CALL_GUARD) {
            return caller.protectedCall(thread, fn, args);
        }
        try {
            return caller.protectedCall(thread, fn, args);
        } catch (RuntimeException e) {
            long n = caught.incrementAndGet();
            LAST_REASON.set("lua ctor threw: " + e);
            if (n <= DETAIL_LIMIT) {
                report(n, args, e);
            }
            return LuaReturn.createReturn(new Object[]{ Boolean.FALSE, "mdc: rejected timed action (" + e + ")" });
        }
    }

    /**
     * A 刀：{@code NetTimedActionPacket.processServer} 內兩處
     * {@code this.write(bbw)} 的改道目標（accept 與 reject 分支各一）。
     *
     * <p>{@code setState} 與 {@code packet.write(b)} 都在診斷 try 之外：A 刀本體失敗必須
     * fail-fast 交給外層封包錯誤處理，不能吞掉後照送 Request；診斷失敗則不得阻止線路寫入。
     */
    public static void write(NetTimedActionPacket packet, ByteBufferWriter b) {
        boolean reject = STATE_FIX && packet.action == null;
        if (reject) {
            packet.setState(Transaction.TransactionState.Reject);
        }
        packet.write(b);

        try {
            if (reject) {
                long n = rejected.incrementAndGet();
                String reason = LAST_REASON.get();
                LAST_REASON.set(null);
                if (n <= PLAYER_DETAIL_LIMIT) {
                    DebugLog.log(TAG + "reject sent"
                            + " type=" + safe(packet.type) + " name=" + safe(packet.name)
                            + " player=" + MdcTimedActionProbe.playerNameOf(packet)
                            + " reason=" + (reason == null ? "lua ctor returned nil/false (vanilla)" : reason)
                            + " n=" + n);
                }
            } else if (passthroughWrites.incrementAndGet() % HEARTBEAT_EVERY == 0L) {
                heartbeat();
            }
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    /**
     * D 刀：{@code NetTimedAction.parse} 內 {@code this.actionArgs.load(b, connection)}
     * （javap offset 68）的 1:1 改道目標。RuntimeException 攔下後設旗標——parse 接著仍會
     * 走到 {@link #protectedCall}，在那裡被拒絕；{@code Error} 穿透。
     */
    public static void loadArgs(PZNetKahluaTableImpl args, ByteBufferReader b, IConnection connection) {
        ARGS_FAILURE.set(null);
        if (!ARGS_GUARD) {
            args.load(b, connection);
            return;
        }
        try {
            args.load(b, connection);
        } catch (RuntimeException e) {
            long n = argsFailed.incrementAndGet();
            ARGS_FAILURE.set("args.load threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (n <= DETAIL_LIMIT) {
                DebugType.General.printException(e, TAG + "args.load failed player=" + playerOf(connection)
                        + " n=" + n, LogSeverity.Warning);
            }
        }
    }

    /**
     * D 刀：{@code PZNetKahluaTableImpl.load(ByteBufferReader,IConnection,byte)} 內 sbyt 36 的
     * {@code loadComponent(ByteBuffer,IConnection)} 1:1 改道目標。前四步與 vanilla 逐語意相同
     * （getLong／getShort／GetEntity／getComponent；SmokeCheck 釘住 vanilla 形狀），只在
     * {@code GetEntity} 回 null 時多做座標救回，救不回就設旗標（vanilla 是 NPE）。
     */
    public static Component loadComponent(ByteBuffer input, IConnection connection) {
        if (!ARGS_GUARD) {
            return PZNetKahluaTableImpl.loadComponent(input, connection);
        }
        long netId = input.getLong();
        short componentId = input.getShort();
        GameEntity entity = GameEntityManager.GetEntity(netId);
        ComponentType type = ComponentType.FromId(componentId);
        if (entity != null) {
            return entity.getComponent(type);
        }
        long n = componentMissing.incrementAndGet();
        Component recovered = null;
        try {
            recovered = recoverBySquare(netId, type, connection, n);
        } catch (RuntimeException e) {
            anomalies.incrementAndGet();
        }
        if (recovered != null) {
            componentRecovered.incrementAndGet();
            return recovered;
        }
        ARGS_FAILURE.set("component netID=" + netId + " type=" + type + " not found on server ("
                + describeNetId(netId) + ")");
        return null;
    }

    /**
     * 解碼 {@code IsoObject.getEntityNetID()} 的座標部分，到該格找恰好一個帶該 component 的物件。
     * 三道門：座標合法、該格在該連線玩家 {@link #RECOVER_RANGE} 格內、候選唯一。
     */
    private static Component recoverBySquare(long netId, ComponentType type, IConnection connection, long n) {
        if (netId < 0L || type == null || type == ComponentType.Undefined) {
            return null;
        }
        long[] parts = decodeNetId(netId);
        int x = (int) parts[0];
        int y = (int) parts[1];
        int z = (int) parts[2];
        long index = parts[3];
        if (z > 63 || index > 4096L) {
            return null;
        }
        IsoPlayer player = nearestPlayer(connection, x, y, z);
        if (player == null) {
            return null;
        }
        if (ServerMap.instance == null) {
            return null;
        }
        IsoGridSquare square = ServerMap.instance.getGridSquare(x, y, z);
        if (square == null) {
            return null;
        }
        List<IsoObject> objects = square.getObjects();
        IsoObject found = null;
        int candidates = 0;
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o != null && o.hasComponent(type)) {
                candidates++;
                found = o;
            }
        }
        if (candidates != 1) {
            if (n <= DETAIL_LIMIT) {
                DebugLog.log(TAG + "component not recovered netID=" + netId + " type=" + type
                        + " square=" + x + "," + y + "," + z + " clientIndex=" + index
                        + " candidates=" + candidates + " player=" + player.getUsername());
            }
            return null;
        }
        long serverNetId = found.getEntityNetID();   // 觸發 checkEntityIDChange → server map 對齊現況
        Component component = found.getComponent(type);
        if (component == null) {
            return null;
        }
        long r = componentRecovered.get() + 1L;
        if (r <= PLAYER_DETAIL_LIMIT) {
            DebugLog.log(TAG + "component recovered type=" + type
                    + " square=" + x + "," + y + "," + z
                    + " clientIndex=" + index + " serverIndex=" + found.getObjectIndex()
                    + " clientNetID=" + netId + " serverNetID=" + serverNetId
                    + " object=" + found.getClass().getSimpleName()
                    + " player=" + player.getUsername() + " n=" + r);
        }
        return component;
    }

    private static IsoPlayer nearestPlayer(IConnection connection, int x, int y, int z) {
        if (!(connection instanceof UdpConnection udp)) {
            return null;
        }
        for (IsoPlayer p : udp.players) {
            if (p == null) {
                continue;
            }
            if (Math.abs((int) p.getX() - x) <= RECOVER_RANGE
                    && Math.abs((int) p.getY() - y) <= RECOVER_RANGE
                    && (int) p.getZ() == z) {
                return p;
            }
        }
        return null;
    }

    /**
     * {@code IsoObject.getEntityNetID()} 的逆運算：{@code x + (y<<16) + (z<<32) + (objectIndex<<40)}
     * → {x, y, z, index}。呼叫端須先排除負值（InventoryItem 的 netID 是 item id，落在 index=0、
     * z=0 的同一數值空間——靠玩家距離門把誤解碼擋掉）。
     */
    static long[] decodeNetId(long netId) {
        return new long[]{ netId & 0xFFFFL, (netId >>> 16) & 0xFFFFL, (netId >>> 32) & 0xFFL, netId >>> 40 };
    }

    private static String describeNetId(long netId) {
        if (netId < 0L) {
            return "negative";
        }
        long[] p = decodeNetId(netId);
        return "x=" + p[0] + " y=" + p[1] + " z=" + p[2] + " index=" + p[3];
    }

    private static String playerOf(IConnection connection) {
        try {
            if (connection instanceof UdpConnection udp) {
                for (IsoPlayer p : udp.players) {
                    if (p != null) {
                        return p.getUsername();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 只影響 log 內容
        }
        return "?";
    }

    private static String typeOf(Object[] args) {
        try {
            if (args != null && args.length > 0 && args[0] instanceof KahluaTable table) {
                Object t = table.rawget("Type");
                if (t != null) {
                    return String.valueOf(t);
                }
            }
        } catch (RuntimeException ignored) {
            // 只影響 log 內容
        }
        return "?";
    }

    /**
     * 攔截現場的診斷：action 型別（從 {@code arguments[0]} 的 class table 取 {@code Type}）、
     * 哪幾個參數位置是 null（{@code loadInventoryItem} 靜默回 null 的直接指紋）、例外訊息。
     */
    private static void report(long n, Object[] args, RuntimeException e) {
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
            String msg = TAG + "lua ctor failed"
                    + " type=" + typeOf(args)
                    + " args=" + (args == null ? -1 : args.length)
                    + " nullArgs=" + (nulls.length() == 0 ? "none" : nulls.toString())
                    + " n=" + n;
            DebugType.General.printException(e, msg, LogSeverity.Error);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    private static String safe(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }

    private static void heartbeat() {
        try {
            DebugLog.log(TAG + "writes=" + passthroughWrites.get()
                    + " rejected=" + rejected.get() + " caught=" + caught.get()
                    + " argsFailed=" + argsFailed.get() + " argsRejected=" + argsRejected.get()
                    + " componentMissing=" + componentMissing.get()
                    + " componentRecovered=" + componentRecovered.get()
                    + " anomalies=" + anomalies.get()
                    + " stateFix=" + (STATE_FIX ? 1 : 0) + " guard=" + (CALL_GUARD ? 1 : 0)
                    + " args=" + (ARGS_GUARD ? 1 : 0));
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    // ---- 測試存取器 ----

    static long argsFailedForTest() {
        return argsFailed.get();
    }

    static long argsRejectedForTest() {
        return argsRejected.get();
    }

    static long componentMissingForTest() {
        return componentMissing.get();
    }

    static long componentRecoveredForTest() {
        return componentRecovered.get();
    }

    static long anomaliesForTest() {
        return anomalies.get();
    }

    static String argsFailureForTest() {
        return ARGS_FAILURE.get();
    }

    private NetTimedActionGuard() {}
}
