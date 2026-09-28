package zombie.core;

import java.lang.reflect.Field;
import java.util.Collection;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.NetTimedActionPacket;
import zombie.network.packets.sound.MdcWorldSoundProbe;
import zombie.network.packets.sound.WorldSoundPacket;

/**
 * W10-C 靜默打斷觀測（B）＋動作封包身分檢查（docs/patches.md 2aj）。放在 {@code zombie.core}
 * 是因為 {@link Action} 是 package-private class、欄位 protected——同 package 才能零反射直讀；
 * 唯一的反射是 {@code ActionManager.actions}（private static），class init 時一次快取，找不到即外逃＝fail-fast。
 *
 * <p><b>W10-C（B）</b>：{@code NetTimedActionPacket.processServer} 對每個新 Request 先
 * {@code stopPlayerActions}，而 server 端 {@code ActionManager.remove} 只移出清單＋{@code stop()}、
 * 不送任何封包——Accept 中的舊動作在 client 等不到 Done／Reject。三態 {@code -Dmdc.timedActionProbe}：
 * {@code 0|off}（純直通）／{@code 1|enforce}（打斷時以同一物件補送 Reject）／{@code 2|observe}（預設；未知值落回 observe）。
 *
 * <p><b>動作封包身分檢查</b>：42.21 原版以 wire 上的 {@code PlayerID}（onlineID）＋ action id 查詢與取消動作，
 * 但 {@code PlayerID.isConsistent} 只驗「解析得到玩家」，不驗 onlineID 屬於送封包的連線；
 * {@code playerIndex=-1} 時 server 還會以 {@code IDToPlayerMap} 直接解析成其他連線的玩家。
 * {@link #processServer} 在授權、解析、一致性與反作弊檢查之後、原版派送之前，要求所有 {@link Action}
 * 封包（GeneralAction／NetTimedAction／BuildAction／FishingAction，任何 state）的 owner 物件
 * 屬於本連線、且 wire onlineID 等於 owner 的 onlineID；不符即不派送並計 {@code unknownRefused}。
 * 其餘一律交回原版 {@code processServer}（取消、Reject 後續、Fishing 事件都照原版）。
 * {@code -Dmdc.actionOwnerCheck=0|off} 回原版直通；與 MODE 獨立（MODE_OFF 時照樣檢查，只是不記錄）。
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
    /** 動作封包 owner 必須屬於送封包的連線；{@code -Dmdc.actionOwnerCheck=0|off} 才回原版直通。 */
    static final boolean OWNER_CHECK = parseOwnerCheck();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
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
    /** owner 不屬於本連線或 wire onlineID 不符而拒絕派送的動作封包數。 */
    private static long unknownRefused;
    private static long logged;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;
    private static long lastBeatNs;
    private static boolean bannerShown;

    private MdcTimedActionProbe() {
    }

    // ---- W10-C（B）：stopPlayerActions redirect ----

    public static void stopPlayerActions(PlayerID playerId) {
        if (MODE != MODE_OFF) {
            try {
                inspectInterrupted(playerId);
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        ActionManager.stopPlayerActions(playerId);
        if (MODE != MODE_OFF) {
            maybeBeat();
        }
    }

    private static void inspectInterrupted(PlayerID playerId) {
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
                    || old.playerId.getID() != playerId.getID()
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

    // ---- 派送 bridge（PacketTypes$PacketType.onServerPacket 內唯一 processServer 改道）----

    /**
     * vanilla 在授權、{@code parseServer}、{@code isConsistent} 與 anticheat 全部通過之後才呼到這個
     * callsite，所以 {@code connection} 就是這個封包已認證的來源。{@link Action} 封包先驗 owner，
     * 不符即不派送；通過或非 Action 封包原樣交回原版（WorldSound 另經觀測器，原方法恰執行一次）。
     * NetTimedAction 封包派送期間綁定為 W10-C 的 Request 上下文，try/finally 恢復先前值（可重入）。
     */
    public static void processServer(INetworkPacket packet, PacketTypes.PacketType packetType,
            UdpConnection connection) {
        if (packet instanceof Action action) {
            if (OWNER_CHECK && !ownedByConnection(action, connection)) {
                refuseUntrusted(action, connection);
                return;
            }
            if (packet instanceof NetTimedActionPacket request) {
                NetTimedActionPacket previous = CURRENT_REQUEST.get();
                CURRENT_REQUEST.set(request);
                try {
                    packet.processServer(packetType, connection);
                } finally {
                    if (previous == null) CURRENT_REQUEST.remove();
                    else CURRENT_REQUEST.set(previous);
                }
                return;
            }
            packet.processServer(packetType, connection);
            return;
        }
        if (packet instanceof WorldSoundPacket sound) {
            MdcWorldSoundProbe.processServer(sound, packetType, connection);
        } else {
            packet.processServer(packetType, connection);
        }
    }

    /**
     * owner 物件必須以 identity 屬於本連線的 {@code players}，且 wire onlineID 等於 owner 的 onlineID。
     * 原版以 wire onlineID 為查詢／取消鍵，只認物件或只認數字都不夠。
     */
    private static boolean ownedByConnection(Action action, UdpConnection connection) {
        if (connection == null || connection.players == null) return false;
        IsoPlayer owner = action.playerId.getPlayer();
        if (owner == null || action.playerId.getID() != owner.getOnlineID()) return false;
        for (IsoPlayer player : connection.players) {
            if (player == owner) return true;
        }
        return false;
    }

    private static void refuseUntrusted(Action action, UdpConnection connection) {
        if (MODE == MODE_OFF) {
            return;
        }
        unknownRefused++;
        try {
            if (!bannerShown) {
                showBanner();
            }
            if (!allowLine()) return;
            DebugLog.log(TAG + " untrustedAction#" + unknownRefused
                    + " id=" + action.id + " state=" + action.state
                    + " wireOnlineId=" + action.playerId.getID()
                    + " owner=" + playerName(action)
                    + " connection=" + (connection == null ? "none" : connection.getConnectedGUID())
                    + " connectionPlayers=" + connectionPlayers(connection)
                    + " type=" + safeName(typeOf(action))
                    + " action=refused suppressed=" + suppressed + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 多人共用連線時列出所有可能 owner，不把其中一人冒充確定的發送者。 */
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

    private static String typeOf(Action action) {
        return action instanceof NetTimedAction nta ? nta.type : action.getClass().getSimpleName();
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
            return p == null ? "?" : p.getOnlineID() + ":" + safeName(p.getUsername());
        } catch (RuntimeException e) {
            return "?";
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
        DebugLog.log(TAG + " 首次生效 mode=" + MODE + " ownerCheck=" + (OWNER_CHECK ? "on" : "off")
                + "（-Dmdc.timedActionProbe=0|off/1|enforce(打斷時補送 Reject)/2|observe 預設；"
                + "-Dmdc.actionOwnerCheck=0|off 關閉動作封包 owner 檢查；觀測 interrupted/untrustedAction）.");
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
                    + " unknownRefused=" + unknownRefused
                    + " logged=" + logged + " suppressed=" + suppressed
                    + " anomalies=" + anomalies + " mode=" + MODE + " ownerCheck=" + (OWNER_CHECK ? "on" : "off") + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static boolean parseOwnerCheck() {
        String raw = System.getProperty("mdc.actionOwnerCheck");
        if (raw == null) {
            return true;
        }
        String v = raw.trim();
        return !("0".equals(v) || "off".equalsIgnoreCase(v));
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

    static long unknownRefusedForTest() {
        return unknownRefused;
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
