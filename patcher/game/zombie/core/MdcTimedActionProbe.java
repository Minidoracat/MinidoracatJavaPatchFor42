package zombie.core;

import java.lang.reflect.Field;
import java.util.Collection;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.NetTimedActionPacket;

/**
 * W10-C 卡讀條第二波觀測刀（立案 2026-08-28、落地 2026-09-02；docs/patches.md 2aj）。放在 {@code zombie.core}
 * 是因為 {@link Action} 是 package-private class、欄位 protected、{@code perform()} 是
 * package-private——同 package 才能零反射直讀（比照 {@code zombie.network.MinidoracatLoginMetrics}
 * 落在目標 package 的慣例）；唯一的反射是 {@code ActionManager.actions}（private static），
 * class init 時一次快取，找不到即 {@code ExceptionInInitializerError} 外逃＝fail-fast。
 *
 * <p>背景：W10（8/23）根治「server 建構動作就炸 → 既不 Accept 也不 Reject」後，玩家仍回報
 * 「讀條走滿不完成」（8/28 晚峰：製作／做奶油／拆除，間歇、排隊多條卡一條）。兩份 client log
 * 在卡住當下完全安靜（無 error、無 Reject）。反編譯定案三條 W10 未覆蓋的路徑，且在 server
 * log 上<b>全部零指紋</b>——本刀就是把它們量出來：
 * <ul>
 *   <li><b>B 靜默打斷</b>：{@code NetTimedActionPacket.processServer} 對每個新 Request 先
 *       {@code ActionManager.stopPlayerActions(playerId)}（javap offset 48），而 server 端
 *       {@code ActionManager.remove} 只移出清單＋{@code stop()}、<b>不送任何封包</b>
 *       （反編譯 :188-199；只有 client 分支會送 GeneralAction Reject）。被打斷的 Accept 中
 *       舊動作在 client 端永遠等不到 Done/Reject。觀測＝redirect 該 stopPlayerActions，掃
 *       同玩家 Accept 中的動作記 type／已等 ms／新 Request type；enforce＝對它們補送 Reject
 *       （模仿 ActionManager :87-96 的 vanilla 形狀），同 id 重送不補（那是 client retry）。</li>
 *   <li><b>C 30 分鐘路徑</b>：client 讀條用 client Lua 自算的 maxTime，server 何時 perform
 *       用 server 自算的 endTime（{@code NetTimedAction.getDuration} ×20ms＋server 專屬
 *       {@code adjustMaxTime}）；{@code getDuration()} 回 -1 時 endTime 直接退到
 *       {@code AnimEventEmulator.getDurationMax()}＝1,800,000ms。{@code ISHandcraftAction}
 *       在 server 端 craftRecipe 為 nil 時就回 -1（ctor 不炸、零 log）。觀測＝TailCall
 *       {@code NetTimedAction.start} 尾部（setTimeData 之後）記 duration，負值逐筆列出。</li>
 *   <li><b>Reject 是否真送出</b>：{@code ActionManager.update} 的 perform 出口——redirect
 *       {@code Action.perform()}（恰 1）記 true/false 分佈，redirect
 *       {@code GameServer.getConnectionFromPlayer}（恰 2：Done／Reject 分支）記 null 次數
 *       （null＝封包不送）。</li>
 * </ul>
 *
 * <p>例外紀律：簿記 catch RuntimeException（anomalies++，不擋 vanilla）；vanilla 委派在 try 外
 * 原樣上拋；LinkageError 外逃 fail-fast。enforce 補送 Reject 走 vanilla 同一組 API，任何
 * 例外只計 anomalies、不影響後續的原 stopPlayerActions 委派。
 *
 * <p>三態 {@code -Dmdc.timedActionProbe}：{@code 0|off}（三點純直通）／{@code 1|enforce}
 * （B 補送 Reject；C 與 perform 仍只觀測）／{@code 2|observe}（預設；未知值落回 observe）。
 */
public final class MdcTimedActionProbe {
    private static final String TAG = "[MinidoracatJavaPatch][TimedActionProbe]";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    private static final long BEAT_NS = 60_000_000_000L;

    /** ActionManager.actions（private static final ConcurrentLinkedQueue<Action>）——class init 一次快取。 */
    private static final Field ACTIONS_FIELD = resolveActionsField();

    /** processServer 進入時捕獲的 Request（供 stopPlayerActions 判同 id 重送與記新動作型別）。 */
    private static final ThreadLocal<NetTimedActionPacket> CURRENT_REQUEST = new ThreadLocal<>();

    // 主迴圈單寫（封包處理與 ActionManager.update 都在主迴圈）；觀測刀容忍罕見交錯。
    private static long starts;
    private static long negativeDuration;
    private static long interruptCalls;
    private static long interruptedAccepted;
    private static long sameIdResend;
    private static long rejectsSent;
    private static long rejectsSkippedNoConn;
    private static long performCalls;
    private static long performFalse;
    private static long connLookups;
    private static long connNull;
    private static long logged;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;
    private static long lastBeatNs;
    private static boolean bannerShown;

    private MdcTimedActionProbe() {
    }

    // ---- 觀測點 C：NetTimedAction.start 尾部（setTimeData 已算出 duration/endTime）----

    public static void onStart(NetTimedAction action) {
        if (MODE == MODE_OFF) {
            return;
        }
        try {
            starts++;
            if (!bannerShown) {
                showBanner();
            }
            if (action.duration < 0L) {
                negativeDuration++;
                if (allowLine()) {
                    DebugLog.log(TAG + " negativeDuration#" + negativeDuration
                            + " type=" + action.type + " name=" + action.name
                            + " player=" + playerName(action)
                            + " duration=" + action.duration
                            + " endTimeDeltaMs=" + (action.endTime - action.startTime)
                            + " (server 將等到 durationMax 才 perform)"
                            + " suppressed=" + suppressed + ".");
                }
            }
        } catch (RuntimeException e) {
            anomalies++;
        }
        maybeBeat();
    }

    // ---- 觀測點 B：processServer 頭部捕獲＋stopPlayerActions redirect ----

    public static void onProcessServer(NetTimedActionPacket packet) {
        try {
            CURRENT_REQUEST.set(packet);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    public static void stopPlayerActions(PlayerID playerId) {
        if (MODE != MODE_OFF) {
            try {
                inspectInterrupted(playerId);
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        ActionManager.stopPlayerActions(playerId);
    }

    /** 測試入口：只跑打斷偵測、不委派 vanilla（vanilla remove 會觸發 GameServer/GameClient class init）。 */
    static void inspectInterruptedForTest(PlayerID playerId) {
        if (MODE == MODE_OFF) {
            return;
        }
        try {
            inspectInterrupted(playerId);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static void inspectInterrupted(PlayerID playerId) {
        interruptCalls++;
        Collection<?> actions = actionsQueue();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        NetTimedActionPacket request = CURRENT_REQUEST.get();
        int requestId = request == null ? Integer.MIN_VALUE : request.id;
        String requestType = request == null ? "?" : request.type;
        long now = GameTime.getServerTimeMills();
        for (Object o : actions) {
            if (!(o instanceof Action)) {
                continue;
            }
            Action old = (Action) o;
            if (old.playerId.getID() != playerId.getID()
                    || old.state != Transaction.TransactionState.Accept) {
                continue;
            }
            interruptedAccepted++;
            boolean sameId = old.id == requestId;
            if (sameId) {
                sameIdResend++;
            }
            String oldType = old instanceof NetTimedAction nta ? nta.type : old.getClass().getSimpleName();
            String verdict;
            if (MODE == MODE_ENFORCE && !sameId && old instanceof NetTimedAction) {
                verdict = sendReject(old) ? "reject-sent" : "reject-skipped-no-conn";
            } else {
                verdict = sameId ? "same-id-resend" : "silent-drop(vanilla)";
            }
            if (allowLine()) {
                DebugLog.log(TAG + " interrupted#" + interruptedAccepted
                        + " player=" + playerName(old)
                        + " old=" + oldType + "#" + old.id
                        + " waitedMs=" + (now - old.startTime)
                        + " remainingMs=" + (old.endTime - now)
                        + " new=" + requestType + "#" + (request == null ? "?" : String.valueOf(request.id))
                        + " action=" + verdict
                        + " suppressed=" + suppressed + ".");
            }
        }
    }

    /** 比照 ActionManager.update 的 Reject 分支（反編譯 :87-96）：state→Reject 後以同一物件序列化送出。 */
    private static boolean sendReject(Action action) {
        try {
            IsoPlayer player = action.playerId.getPlayer();
            UdpConnection connection = player == null ? null : GameServer.getConnectionFromPlayer(player);
            if (connection == null || !connection.isFullyConnected()) {
                rejectsSkippedNoConn++;
                return false;
            }
            action.state = Transaction.TransactionState.Reject;
            ByteBufferWriter bbw = connection.startPacket();
            PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
            action.write(bbw);
            PacketTypes.PacketType.NetTimedAction.send(connection);
            rejectsSent++;
            return true;
        } catch (RuntimeException e) {
            anomalies++;
            return false;
        }
    }

    // ---- 觀測點 Reject 出口：ActionManager.update 內 perform 與 connection 查找 ----

    public static boolean perform(Action action) {
        boolean result = action.perform();
        if (MODE == MODE_OFF) {
            return result;
        }
        try {
            performCalls++;
            if (!result) {
                performFalse++;
                if (allowLine()) {
                    DebugLog.log(TAG + " performFalse#" + performFalse
                            + " type=" + (action instanceof NetTimedAction nta ? nta.type : action.getClass().getSimpleName())
                            + " player=" + playerName(action)
                            + " (vanilla 接著送 Reject)" + ".");
                }
            }
        } catch (RuntimeException e) {
            anomalies++;
        }
        return result;
    }

    public static UdpConnection connectionOf(IsoPlayer player) {
        UdpConnection connection = GameServer.getConnectionFromPlayer(player);
        if (MODE != MODE_OFF) {
            connLookups++;
            if (connection == null) {
                connNull++;
            }
        }
        return connection;
    }

    // ---- 內部 ----

    private static Collection<?> actionsQueue() {
        try {
            Object v = ACTIONS_FIELD.get(null);
            return v instanceof Collection<?> c ? c : null;
        } catch (IllegalAccessException e) {
            anomalies++;
            return null;
        }
    }

    private static Field resolveActionsField() {
        try {
            Field f = ActionManager.class.getDeclaredField("actions");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            // 找不到＝TIS 改了 ActionManager 結構；class init 失敗外逃（fail-fast，比照家族紀律）。
            throw new IllegalStateException(TAG + " ActionManager.actions 欄位不存在，jar 不相容", e);
        }
    }

    private static String playerName(Action action) {
        try {
            IsoPlayer p = action.playerId.getPlayer();
            return p == null ? "?" : p.getUsername();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static boolean allowLine() {
        long now = System.nanoTime();
        if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed++;
            return false;
        }
        windowCount++;
        logged++;
        return true;
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE
                + "（-Dmdc.timedActionProbe=0|off/1|enforce(打斷時補送 Reject)/2|observe 預設；"
                + "觀測 negativeDuration/interrupted/performFalse/connNull）.");
    }

    /** heartbeat：每 256 個 start 檢查一次時鐘、60s 一行。 */
    private static void maybeBeat() {
        if ((starts & 0xFFL) != 0L) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (lastBeatNs != 0L && now - lastBeatNs < BEAT_NS) {
                return;
            }
            lastBeatNs = now;
            DebugLog.log(TAG + " beat starts=" + starts + " negativeDuration=" + negativeDuration
                    + " interruptCalls=" + interruptCalls + " interruptedAccepted=" + interruptedAccepted
                    + " sameIdResend=" + sameIdResend + " rejectsSent=" + rejectsSent
                    + " rejectsSkippedNoConn=" + rejectsSkippedNoConn
                    + " performCalls=" + performCalls + " performFalse=" + performFalse
                    + " connLookups=" + connLookups + " connNull=" + connNull
                    + " logged=" + logged + " suppressed=" + suppressed
                    + " anomalies=" + anomalies + " mode=" + MODE + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static int parseMode() {
        String raw = System.getProperty("mdc.timedActionProbe");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "enforce":
                return MODE_ENFORCE;
            case "2":
            case "observe":
            default:
                return MODE_OBSERVE;
        }
    }

    // ---- 測試存取器 ----

    static long startsForTest() {
        return starts;
    }

    static long negativeDurationForTest() {
        return negativeDuration;
    }

    static long interruptedAcceptedForTest() {
        return interruptedAccepted;
    }

    static long sameIdResendForTest() {
        return sameIdResend;
    }

    static long rejectsSentForTest() {
        return rejectsSent;
    }

    static long rejectsSkippedNoConnForTest() {
        return rejectsSkippedNoConn;
    }

    static long performCallsForTest() {
        return performCalls;
    }

    static long performFalseForTest() {
        return performFalse;
    }

    static long connNullForTest() {
        return connNull;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static void setCurrentRequestForTest(NetTimedActionPacket packet) {
        CURRENT_REQUEST.set(packet);
    }

    static Collection<?> actionsQueueForTest() {
        return actionsQueue();
    }
}
