package zombie.mdc;

import java.util.concurrent.atomic.AtomicLong;

import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10 卡讀條根治（2026-08-23；玩家回報「製作／搬移／吃／閱讀讀條走滿卻不完成」）。
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
 *       {@code item:getWorldSprite()}）→ Kahlua 拋 {@code RuntimeException}，
 *       <b>穿過</b>名為 protected 的 {@code protectedCall}，一路傳到
 *       {@code GameServer.mainLoopDealWithNetData} 被 catch 吞掉。於是
 *       {@code processServer} 從未執行，server 既不回 Accept 也不回 Reject。
 *       諷刺的是 vanilla 本來就寫好了失敗處理（{@code parse} 內
 *       {@code if (!result.isSuccess() || result.getFirst() == null) { action = null; return; }}），
 *       只是例外繞過了它。</li>
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
 * <p>client 端沒有任何自癒：{@code LuaTimedActionNew.start} 在 MP 設
 * {@code setWaitForFinished(true)}，而 {@code BaseAction.finished()} 要求
 * {@code !waitForFinished} → 完成訊號只能來自 server；{@code BaseAction.hasStalled()}
 * 要求 time 為負，卡住時 time 停在 {@code maxTime}（正值）故恆為 false；
 * {@code ActionManager} 的 30 分鐘 timeout 只把項目移出清單、不設 Done/Reject，而
 * {@code isDone}／{@code isRejected} 都有 {@code !actions.isEmpty()} 前綴，清單清空後
 * 兩者同時為 false ＝ 從「等 30 分鐘」升級為「永久」。{@code ISReadABook}／
 * {@code ISResearchRecipe} 的 {@code isUsingTimeout()} 回 false，連移出都不會發生。
 *
 * <p><b>本 helper 的兩個改道</b>（皆 server-only 路徑，client 不需安裝任何東西）：
 * <ul>
 *   <li>{@link #protectedCall}（B 刀，{@code -Dmdc.netTimedActionGuard=0} 停用）：
 *       攔下 Lua 建構子的 {@code RuntimeException}，回一個 {@code isSuccess()==false} 的
 *       {@code LuaReturn}，讓 vanilla 既有的 {@code action = null; return;} 真正被走到。
 *       <b>catch 型別鎖定 {@code RuntimeException}</b>——{@code Error}（SOE／OOM）必須穿透。</li>
 *   <li>{@link #write}（A 刀，{@code -Dmdc.netTimedActionState=0} 停用）：
 *       {@code action == null}（即 vanilla 走 reject 分支的判別條件）時把 state 補成
 *       {@code Reject} 再送出，client 收到後 {@code isRejected} 成立 →
 *       {@code forceStop()} → 動作乾淨取消、queue 解除堵塞。</li>
 * </ul>
 *
 * <p><b>刻意不做的事</b>：不猜測、不代找那個 null 的 {@code InventoryItem}。
 * 猜錯會消耗錯誤材料或憑空產出成品。本刀的語意是「把靜默的永久卡死變成有聲的失敗」
 * ——玩家看到動作中斷可重試，而非無限讀條。item 為何是 null（容器不同步／被前一步消耗）
 * 屬上游問題，由本 helper 的診斷 log 蒐證後另案處理。
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

    /** 未補 Reject 的 write 次數（accept，或 A 刀關閉時的全部 write）；heartbeat 的基準。 */
    private static final AtomicLong passthroughWrites = new AtomicLong();
    /** 被補成 Reject 的封包數（A 刀生效次數）。 */
    private static final AtomicLong rejected = new AtomicLong();
    /** 攔下的 Lua 建構子例外數（B 刀生效次數）。 */
    private static final AtomicLong caught = new AtomicLong();
    /** helper 自身的診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    /** 逐筆詳細 log 的上限，之後只計數（病態情況不得洪水刷 console）。 */
    private static final long DETAIL_LIMIT = 32L;
    /** heartbeat 週期（以未補 Reject 的 write 計數為節拍）。 */
    private static final long HEARTBEAT_EVERY = 2048L;
    private static final String TAG = "[MinidoracatJavaPatch][NetTimedAction] ";

    /**
     * B 刀：{@code NetTimedAction.parse} 內唯一 {@code LuaCaller.protectedCall} 呼叫點的改道目標。
     *
     * <p>成功路徑逐指令等價（直接委派）。失敗路徑回傳
     * {@code LuaReturn.createReturn(new Object[]{ Boolean.FALSE, msg })} ——
     * {@code createReturn} 以 {@code returnValues[0]} 的 Boolean 決定產生
     * {@code LuaSuccess} 或 {@code LuaFail}，故此處必得 {@code isSuccess()==false}，
     * 恰好落進 vanilla 的 {@code action = null; return;}。
     */
    public static LuaReturn protectedCall(LuaCaller caller, KahluaThread thread, Object fn, Object[] args) {
        if (!CALL_GUARD) {
            return caller.protectedCall(thread, fn, args);
        }
        try {
            return caller.protectedCall(thread, fn, args);
        } catch (RuntimeException e) {
            long n = caught.incrementAndGet();
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
                if (n <= DETAIL_LIMIT) {
                    DebugLog.log(TAG + "reject sent"
                            + " type=" + safe(packet.type) + " name=" + safe(packet.name)
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
     * 攔截現場的診斷：action 型別（從 {@code arguments[0]} 的 class table 取 {@code Type}）、
     * 哪幾個參數位置是 null（{@code loadInventoryItem} 靜默回 null 的直接指紋）、例外訊息。
     */
    private static void report(long n, Object[] args, RuntimeException e) {
        try {
            String type = "?";
            StringBuilder nulls = new StringBuilder();
            if (args != null) {
                if (args.length > 0 && args[0] instanceof KahluaTable table) {
                    Object t = table.rawget("Type");
                    if (t != null) {
                        type = String.valueOf(t);
                    }
                }
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
                    + " type=" + type
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
                    + " anomalies=" + anomalies.get()
                    + " stateFix=" + (STATE_FIX ? 1 : 0) + " guard=" + (CALL_GUARD ? 1 : 0));
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    private NetTimedActionGuard() {}
}
