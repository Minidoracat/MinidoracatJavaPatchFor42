package zombie.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
import zombie.network.server.AnimEventEmulator;

/**
 * W10-C 卡讀條觀測刀 ＋ W10-E 取消範圍執法（docs/patches.md 2aj）。放在 {@code zombie.core}
 * 是因為 {@link Action} 是 package-private class、欄位 protected、{@code perform()} 也是
 * package-private——同 package 才能零反射直讀；唯一的反射是 {@code ActionManager.actions}
 * （private static），class init 時一次快取，找不到即外逃＝fail-fast。
 *
 * <p><b>W10-C 觀測三點</b>（B：{@code NetTimedActionPacket.processServer} 每個新 Request 先
 * {@code stopPlayerActions}，而 server 端 remove 不送任何封包／C：{@code getDuration()} 回 -1 時
 * endTime 退到 {@code durationMax}＝30 分鐘／R：{@code ActionManager.update} 的 perform 出口）。
 * 三態 {@code -Dmdc.timedActionProbe}：{@code 0|off}（純直通）／{@code 1|enforce}（B 打斷時補送
 * Reject）／{@code 2|observe}（預設；未知值落回 observe）。
 *
 * <p><b>W10-E 取消範圍</b>：server 端 {@code ActionManager.remove} 對整份清單只比
 * {@code t.id == id}（反編譯 :189），而 {@code Action.id} 是各 client 自己循環使用的 255 個非零 byte
 * （{@code Action.set:37-45}）——甲取消自己的動作會連帶無聲刪掉乙同 id 的動作，乙永遠等不到
 * Done／Reject。所有 server 端取消（{@code stopPlayerActions}、General／NetTimedAction／Build／
 * Fishing 的 client 取消）都經 {@code ActionManager.stop(Action)}，故 headCall 捕獲發起 action
 * ＋改道其內唯一的 {@code remove(BZ)} 就涵蓋全部。
 *
 * <p>取消者身分<b>不從封包欄位讀</b>：client 的 {@code GeneralActionPacket.setReject} 只填 id，
 * playerId 留在預設值（wire 上是 {@code 0000}／index {@code ff}），server 端
 * {@code PlayerID.parsePlayer} 會把它解析成 onlineID 0——也就是 slot 0 那位玩家
 * （{@code GameServer.receiveClientConnect}：{@code playerID = slot * 4}）。拿它當 sender
 * ＝把所有 GeneralAction 取消都記到 slot 0 頭上。身分只認 {@link #processServer} 在授權、解析、
 * 一致性與反作弊檢查之後綁定的那條連線。
 *
 * <p>{@code -Dmdc.actionRemoveScope}：只有明示 {@code 0|off|vanilla} 才委派原版的整表 id 掃除，
 * 其餘值（預設）＝connection scope。此開關獨立於 MODE：MODE_OFF 時仍然執法，只是不記錄。
 * 身分不明時<b>不做</b>取消（回原版就是全域刪除，比不做危險得多）。
 *
 * <p>例外紀律：簿記 catch RuntimeException（anomalies++，不擋 vanilla）；vanilla 委派、以及取消的
 * 選取／移除／{@code stop()} 原樣上拋（不吞成安靜 no-op，只在 finally 補完 emulator 清理）；
 * LinkageError 外逃 fail-fast。
 */
public final class MdcTimedActionProbe {
    private static final String TAG = "[MinidoracatJavaPatch][TimedActionProbe]";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();
    /** W10-E：取消只動已驗證 owner 的同 id 動作；{@code -Dmdc.actionRemoveScope=0|off|vanilla} 才回原版。 */
    static final boolean SCOPE_ON = parseScope();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    private static final long BEAT_NS = 300_000_000_000L;

    /** ActionManager.actions（private static final ConcurrentLinkedQueue<Action>）——class init 一次快取。 */
    private static final Field ACTIONS_FIELD = resolveActionsField();

    /** ActionManager.stop(Action) 進入時捕獲的發起 action（供 removeById 判「佇列內同一實例」）。 */
    private static final ThreadLocal<Action> CURRENT_STOP = new ThreadLocal<>();
    /** 目前正在處理的 Action 封包所屬的已認證連線（唯一可信的取消者身分來源）。 */
    private static final ThreadLocal<UdpConnection> CURRENT_CONNECTION = new ThreadLocal<>();
    /** 區分「沒有封包上下文」與「封包缺少連線」；後者不得退回 server 內部信任路徑。 */
    private static final ThreadLocal<INetworkPacket> CURRENT_PACKET = new ThreadLocal<>();

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
    private static long removeCalls;
    private static long removeMultiHit;
    /** 本刀接手的取消次數（scope 開著）。 */
    private static long cancelsHandled;
    /** 接手後真正移出清單的動作數。 */
    private static long ownedRemoved;
    /** 接手了但清單裡沒有取消者自己的同 id 動作（vanilla 在此會刪別人的）。 */
    private static long noOwnedMatch;
    /** 因為 owner 不屬於這條連線而被保留下來的同 id 動作數（撞號證據）。 */
    private static long sparedOther;
    /** 沒有已認證連線、發起 action 也不是佇列內實例＝身分不明，拒做。 */
    private static long unknownRefused;
    /** kill switch 明示關閉時委派原版整表刪除的次數。 */
    private static long vanillaRemovals;
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
            }
        } catch (RuntimeException e) {
            anomalies++;
        }
        maybeBeat();
    }

    // ---- 觀測點 B：沿用 dispatch 的 Request 上下文＋stopPlayerActions redirect ----


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

    private static void inspectInterrupted(PlayerID playerId) {
        interruptCalls++;
        Collection<?> actions = actionsQueue();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        NetTimedActionPacket request = CURRENT_PACKET.get() instanceof NetTimedActionPacket p ? p : null;
        int requestId = request == null ? Integer.MIN_VALUE : request.id;
        UdpConnection connection = CURRENT_CONNECTION.get();
        boolean connectionScoped = SCOPE_ON && CURRENT_PACKET.get() != null;
        for (Object o : actions) {
            if (!(o instanceof Action)) {
                continue;
            }
            Action old = (Action) o;
            if (old.playerId.getID() != playerId.getID()
                    || old.state != Transaction.TransactionState.Accept
                    || (connectionScoped && !ownedByConnection(old, connection))) {
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

    // ---- W10-E：已認證連線綁定（PacketTypes$PacketType.onServerPacket 內唯一 processServer 改道）----

    /**
     * vanilla 在授權（{@code PacketAuthorization.isAuthorized}）、{@code parseServer}、
     * {@code isConsistent} 與 anticheat 全部通過之後才呼到這個 callsite，所以 {@code connection}
     * 就是這個封包<b>已認證</b>的來源。Action Request 另核對 wire ID 與連線 owner；Reject
     * 在原派送之前直接進 scoped stop，避免 NetTimedAction.copyFrom 提前改他人 state，以及
     * Fishing 取消後再次以全域 id 產生他人事件。其他狀態／非 Action 封包仍原樣派送。
     * try/finally 恢復先前值，支援 nested dispatch 與例外；不改授權／解析／anticheat 順序。
     */
    public static void processServer(INetworkPacket packet, PacketTypes.PacketType packetType,
            UdpConnection connection) {
        boolean bind = packet instanceof Action;
        UdpConnection previous = null;
        INetworkPacket previousPacket = null;
        if (bind) {
            previous = CURRENT_CONNECTION.get();
            previousPacket = CURRENT_PACKET.get();
            CURRENT_CONNECTION.set(connection);
            CURRENT_PACKET.set(packet);
        }
        try {
            if (SCOPE_ON && packet instanceof Action action) {
                if (action.state == Transaction.TransactionState.Reject) {
                    ActionManager.stop(action);
                    return;
                }
                if (action.state == Transaction.TransactionState.Request
                        && (!ownedByConnection(action, connection)
                            || action.playerId.getID() != action.playerId.getPlayer().getOnlineID())) {
                    refuseUnknown(action, connection, action.id, false);
                    return;
                }
            }
            if (packet instanceof WorldSoundPacket sound) {
                MdcWorldSoundProbe.processServer(sound, packetType, connection);
            } else {
                packet.processServer(packetType, connection);
            }
        } finally {
            if (bind) {
                if (previous == null) CURRENT_CONNECTION.remove();
                else CURRENT_CONNECTION.set(previous);
                if (previousPacket == null) CURRENT_PACKET.remove();
                else CURRENT_PACKET.set(previousPacket);
            }
        }
    }

    // ---- W10-E：ActionManager.stop(Action) 頭部捕獲＋其內 remove(BZ) 改道 ----

    public static void onStop(Action action) {
        try {
            CURRENT_STOP.set(action);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /**
     * {@code ActionManager.stop(Action)} 內唯一 {@code remove(BZ)V} 的改道目標。
     * scope 開著（預設）＝只移除同 id 且 owner 經已認證連線驗證的動作，身分不明就不做；
     * 只有 {@code -Dmdc.actionRemoveScope=0|off|vanilla} 才委派原版的整表 id 掃除。
     */
    public static void removeById(byte id, boolean cancelled) {
        Action initiator = CURRENT_STOP.get();
        CURRENT_STOP.remove();
        boolean observe = MODE != MODE_OFF;
        if (observe) removeCalls++;
        if (!SCOPE_ON) {
            if (observe) vanillaRemovals++;
            ActionManager.remove(id, cancelled);
            return;
        }
        Collection<?> actions = actionsQueue();
        if (actions == null) {
            throw new IllegalStateException(TAG + " action queue unavailable; cancellation refused");
        }
        if (CURRENT_PACKET.get() == null) {
            // server 自己停止的是一個確定的佇列實例，不以可能重複的 id 代替物件身分。
            var iterator = actions.iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == initiator && initiator != null && initiator.id == id) {
                    iterator.remove();
                    if (observe) {
                        cancelsHandled++;
                        ownedRemoved++;
                    }
                    stopRemoved(initiator);
                    return;
                }
            }
            refuseUnknown(initiator, null, id, cancelled);
            return;
        }
        UdpConnection connection = CURRENT_CONNECTION.get();
        if (!hasPlayers(connection)) {
            refuseUnknown(initiator, connection, id, cancelled);
            return;
        }
        if (observe) cancelsHandled++;
        removeForConnection(actions, initiator, connection, id, cancelled);
    }

    /** 網路取消只有一趟掃描；先移出本連線全部命中者，再執行可能重入的 serverStop。 */
    private static void removeForConnection(Collection<?> actions, Action initiator, UdpConnection connection,
            byte id, boolean cancelled) {
        boolean observe = MODE != MODE_OFF;
        List<Action> owned = null;
        int hits = 0;
        for (Object o : actions) {
            if (!(o instanceof Action queued) || queued.id != id) continue;
            hits++;
            if (ownedByConnection(queued, connection)) {
                if (owned == null) owned = new ArrayList<>(2);
                owned.add(queued);
            } else if (observe) {
                sparedOther++;
            }
        }
        if (observe && hits > 1) removeMultiHit++;
        if (owned == null) {
            if (observe) {
                noOwnedMatch++;
            }
            return;
        }
        actions.removeAll(owned);
        if (observe) ownedRemoved += owned.size();
        Throwable failure = null;
        for (Action mine : owned) {
            try {
                stopRemoved(mine);
            } catch (RuntimeException | Error e) {
                // 已移出整批，任何一筆失敗都不能遺留後續動作的 Lua／emulator 狀態。
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
    }

    private static void stopRemoved(Action action) {
        try {
            action.stop();
        } finally {
            if (action instanceof NetTimedAction nta) {
                AnimEventEmulator.getInstance().remove(nta);
            }
        }
    }

    private static boolean hasPlayers(UdpConnection connection) {
        if (connection == null || connection.players == null) return false;
        for (IsoPlayer player : connection.players) {
            if (player != null) return true;
        }
        return false;
    }

    /** 只信任當前連線的實際玩家；不以可重用的 onlineID 認領缺席或過期的 owner。 */
    private static boolean ownedByConnection(Action queued, UdpConnection connection) {
        if (connection == null || connection.players == null) return false;
        IsoPlayer owner = queued.playerId.getPlayer();
        if (owner == null) return false;
        for (IsoPlayer player : connection.players) {
            if (player == owner) return true;
        }
        return false;
    }

    private static void refuseUnknown(Action initiator, UdpConnection connection, byte id, boolean cancelled) {
        if (MODE != MODE_OFF) {
            unknownRefused++;
            logUntrusted(initiator, connection, id, cancelled);
        }
    }

    /** 逐筆明細只留「身分不可信」這個異常訊號（恆 0）；其餘改由 beat 計數（2026-09-27 log 精簡）。 */
    private static void logUntrusted(Action initiator, UdpConnection connection, byte id, boolean cancelled) {
        try {
            if (!allowLine()) return;
            DebugLog.log(TAG + " untrustedAction#" + unknownRefused
                    + " id=" + id + " cancelled=" + cancelled
                    + " state=" + (initiator == null ? "?" : initiator.state)
                    + " connection=" + (connection == null ? "none" : connection.getConnectedGUID())
                    + " connectionPlayers=" + connectionPlayers(connection)
                    + " sourceType=" + safeName(typeOf(initiator))
                    + " source=" + (CURRENT_PACKET.get() == null ? "server-internal" : "packet")
                    + " action=refused suppressed=" + suppressed + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 多人共用連線時列出所有可能 owner，不把其中一人冒充確定的取消發送者。 */
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
        if (action == null) {
            return "?";
        }
        return action instanceof NetTimedAction ? ((NetTimedAction) action).type : action.getClass().getSimpleName();
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

    /** 供 zombie.mdc.NetTimedActionGuard 的 reject log 使用（Action 是 package-private，只能從本 package 讀 playerId）。 */
    public static String playerNameOf(NetTimedAction action) {
        return playerName(action);
    }

    private static String playerName(Action action) {
        if (action == null) {
            return "?";
        }
        try {
            IsoPlayer p = action.playerId.getPlayer();
            return safeName(p == null ? null : p.getUsername());
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
        DebugLog.log(TAG + " 首次生效 mode=" + MODE + " scope=" + scopeName()
                + "（-Dmdc.timedActionProbe=0|off/1|enforce(打斷時補送 Reject)/2|observe 預設；"
                + "-Dmdc.actionRemoveScope 預設 connection(取消只動已驗證 owner 的同 id)/0|off|vanilla 回原版整表刪除；"
                + "觀測 negativeDuration/interrupted/performFalse/connNull/otherOwnerSameId）.");
    }

    private static String scopeName() {
        return SCOPE_ON ? "connection" : "vanilla";
    }

    /** heartbeat：每 256 個 start 檢查一次時鐘、300s 一行。 */
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
                    + " removeCalls=" + removeCalls + " removeMultiHit=" + removeMultiHit
                    + " cancelsHandled=" + cancelsHandled + " ownedRemoved=" + ownedRemoved
                    + " noOwnedMatch=" + noOwnedMatch + " sparedOther=" + sparedOther
                    + " unknownRefused=" + unknownRefused + " vanillaRemovals=" + vanillaRemovals
                    + " logged=" + logged + " suppressed=" + suppressed
                    + " anomalies=" + anomalies + " mode=" + MODE + " scope=" + scopeName() + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static boolean parseScope() {
        String raw = System.getProperty("mdc.actionRemoveScope");
        if (raw == null) {
            return true;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
            case "vanilla":
                return false;
            default:
                return true;   // 1|on|player|connection…＝預設安全 scope（canonical: connection）
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

    static long removeCallsForTest() {
        return removeCalls;
    }

    static long removeMultiHitForTest() {
        return removeMultiHit;
    }

    static long cancelsHandledForTest() {
        return cancelsHandled;
    }

    static long ownedRemovedForTest() {
        return ownedRemoved;
    }

    static long noOwnedMatchForTest() {
        return noOwnedMatch;
    }

    static long sparedOtherForTest() {
        return sparedOther;
    }

    static long unknownRefusedForTest() {
        return unknownRefused;
    }

    static long anomaliesForTest() {
        return anomalies;
    }


    /** 綁定中的連線（processServer 的 try/finally 契約驗證用）。 */
    static UdpConnection boundConnectionForTest() {
        return CURRENT_CONNECTION.get();
    }

    static Collection<?> actionsQueueForTest() {
        return actionsQueue();
    }
}
