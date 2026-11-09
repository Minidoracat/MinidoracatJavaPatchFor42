package zombie.core;

import java.lang.reflect.Field;
import java.util.Collection;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.NetTimedActionPacket;
import zombie.network.packets.sound.MdcWorldSoundProbe;
import zombie.network.packets.sound.WorldSoundPacket;

/**
 * W10-C 靜默打斷觀測（B）＋派送 bridge（docs/patches.md 2aj）。放在 {@code zombie.core}
 * 是因為 {@link Action} 是 package-private class、欄位 protected——同 package 才能零反射直讀；
 * 唯一的反射是 {@code ActionManager.actions}（private static），class init 時一次快取，找不到即外逃＝fail-fast。
 *
 * <p><b>W10-C（B）</b>：{@code NetTimedActionPacket.processServer} 對每個新 Request 先
 * {@code stopPlayerActions}，而 server 端 {@code ActionManager.remove} 只移出清單＋{@code stop()}、
 * 不送任何封包——Accept 中的舊動作在 client 等不到 Done／Reject。三態 {@code -Dmdc.timedActionProbe}：
 * {@code 0|off}（純直通）／{@code 1|enforce}（打斷時以同一物件補送 Reject）／{@code 2|observe}（預設；未知值落回 observe）。
 *
 * <p><b>派送 bridge</b>（{@link #processServer}）：NetTimedAction 封包派送期間綁定為 W10-C 的 Request 上下文
 * （以它的 id 區分「同 id 重送」與「新動作打斷」）；{@code WorldSoundPacket} 交給 §2at 觀測器；其餘原樣交回原版。
 * 42.22.2 對版退役了這裡的動作封包 owner 檢查與 W78 執行者檢查：原版 {@code ConnectionPlayer.parse} 在伺服器只從送出的連線
 * 解析玩家（解析不到就 {@code isConsistent} 為 false、不派送），{@code NetTimedActionPacket.processServer} 自己比
 * {@code action.character == player}。
 *
 * <p>例外紀律：簿記 catch RuntimeException（anomalies++，不擋 vanilla）；vanilla 委派原樣上拋；
 * LinkageError 外逃 fail-fast。
 */
public final class MdcTimedActionProbe {
    private static final String TAG = "[MinidoracatJavaPatch][TimedActionProbe]";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();

    private static final long BEAT_NS = 300_000_000_000L;

    /** ActionManager.actions（private static final ConcurrentLinkedQueue<Action>）——class init 一次快取。 */
    private static final Field ACTIONS_FIELD = resolveActionsField();

    /** 目前正在派送的 NetTimedAction 封包（W10-C 以它的 id 區分「同 id 重送」與「新動作打斷」）。 */
    private static final ThreadLocal<NetTimedActionPacket> CURRENT_REQUEST = new ThreadLocal<>();

    // 主迴圈單寫（封包處理在主迴圈）；觀測刀容忍罕見交錯。
    private static long interruptCalls;
    private static long interruptedAccepted;
    private static long sameIdResend;
    private static long rejectsSent;
    private static long rejectsSkippedNoConn;
    private static long anomalies;
    private static long lastBeatNs;
    private static boolean bannerShown;

    private MdcTimedActionProbe() {
    }

    // ---- W10-C（B）：stopPlayerActions redirect ----

    public static void stopPlayerActions(IsoPlayer player) {
        if (MODE != MODE_OFF) {
            try {
                inspectInterrupted(player);
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        ActionManager.stopPlayerActions(player);
        if (MODE != MODE_OFF) {
            maybeBeat();
        }
    }

    private static void inspectInterrupted(IsoPlayer player) {
        if (!bannerShown) {
            showBanner();
        }
        interruptCalls++;
        Collection<?> actions = actionsQueue();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        NetTimedActionPacket request = CURRENT_REQUEST.get();
        int requestId = request == null ? Integer.MIN_VALUE : request.id;
        for (Object o : actions) {
            if (!(o instanceof Action old)
                    || !sameOnlineId(old, player)
                    || old.state != Transaction.TransactionState.Accept) {
                continue;
            }
            interruptedAccepted++;
            boolean sameId = old.id == requestId;
            if (sameId) {
                sameIdResend++;
            }
            if (MODE == MODE_ENFORCE && !sameId && old instanceof NetTimedAction) {
                sendReject(old);
            }
        }
    }

    /** 比照 ActionManager.update 的 Reject 分支：state→Reject 後以同一物件序列化送出。 */
    private static boolean sendReject(Action action) {
        try {
            IsoPlayer player = action.player.getPlayer();
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

    /** 42.22 原版 {@code ActionManager.isActionOf} 的同一判斷：動作的玩家與 {@code player} 的 onlineID 相同。 */
    private static boolean sameOnlineId(Action action, IsoPlayer player) {
        IsoPlayer actionPlayer = action.player.getPlayer();
        return actionPlayer != null && player != null && actionPlayer.getOnlineID() == player.getOnlineID();
    }

    // ---- 派送 bridge（PacketTypes$PacketType.onServerPacket 內唯一 processServer 改道）----

    /**
     * vanilla 在授權、{@code parseServer}、{@code isConsistent} 與 anticheat 全部通過之後才呼到這個 callsite。
     * NetTimedAction 封包派送期間綁定為 W10-C 的 Request 上下文，try/finally 恢復先前值（可重入）；
     * WorldSound 另經觀測器；原方法恰執行一次。
     */
    public static void processServer(INetworkPacket packet, PacketTypes.PacketType packetType,
            UdpConnection connection) {
        if (packet instanceof NetTimedActionPacket request) {
            NetTimedActionPacket previous = CURRENT_REQUEST.get();
            CURRENT_REQUEST.set(request);
            try {
                packet.processServer(packetType, connection);
            } finally {
                if (previous == null) CURRENT_REQUEST.remove();
                else CURRENT_REQUEST.set(previous);
            }
        } else if (packet instanceof WorldSoundPacket sound) {
            MdcWorldSoundProbe.processServer(sound, packetType, connection);
        } else {
            packet.processServer(packetType, connection);
        }
    }

    /** 多人共用連線時列出所有可能 owner，不把其中一人冒充確定的發送者（其他刀的 log 共用）。 */
    public static String connectionPlayers(UdpConnection connection) {
        if (connection == null || connection.players == null) return "?";
        StringBuilder names = new StringBuilder();
        for (IsoPlayer player : connection.players) {
            if (player == null) continue;
            if (names.length() > 0) names.append('|');
            names.append(player.getOnlineID()).append(':').append(safeName(player.getUsername()));
        }
        return names.length() == 0 ? "?" : names.toString();
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

    /** 共用 log 欄位淨化：控制字元、空白與欄位分隔符換底線並限長，避免偽造新行或欄位。 */
    public static String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        int n = Math.min(name.length(), 32);
        StringBuilder s = new StringBuilder(n + 3);
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            s.append(Character.isISOControl(c) || Character.isWhitespace(c) || c == '[' || c == ']' || c == '|' ? '_' : c);
        }
        if (name.length() > n) {
            s.append("...");
        }
        return s.toString();
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE
                + "（-Dmdc.timedActionProbe=0|off/1|enforce(打斷時補送 Reject)/2|observe 預設；觀測 interrupted）.");
    }

    /** heartbeat：每 256 次 stopPlayerActions 檢查一次時鐘、300s 一行。 */
    private static void maybeBeat() {
        if ((interruptCalls & 0xFFL) != 0L) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (lastBeatNs != 0L && now - lastBeatNs < BEAT_NS) {
                return;
            }
            lastBeatNs = now;
            DebugLog.log(TAG + " beat interruptCalls=" + interruptCalls + " interruptedAccepted=" + interruptedAccepted
                    + " sameIdResend=" + sameIdResend + " rejectsSent=" + rejectsSent
                    + " rejectsSkippedNoConn=" + rejectsSkippedNoConn
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

    // ---- 測試存取器（唯讀計數／狀態，無任何改變正式判定的旁路）----

    static long interruptCallsForTest() {
        return interruptCalls;
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

    static long anomaliesForTest() {
        return anomalies;
    }

    /** 綁定中的 Request（processServer 的 try/finally 契約驗證用）。 */
    static NetTimedActionPacket boundRequestForTest() {
        return CURRENT_REQUEST.get();
    }

    static Collection<?> actionsQueueForTest() {
        return actionsQueue();
    }
}
