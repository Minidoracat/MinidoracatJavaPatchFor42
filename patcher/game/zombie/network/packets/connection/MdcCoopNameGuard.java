package zombie.network.packets.connection;

import java.util.Locale;

import zombie.core.network.ByteBufferReader;
import zombie.debug.DebugLog;
import zombie.network.IConnection;
import zombie.network.ServerWorldDatabase;

/**
 * W52 分割畫面與重生的名稱冒用守衛（2026-10-03；docs/patches.md 2bp）。
 *
 * <p><b>原版缺陷</b>：{@code ConnectCoopPacket.parse} stage 1 從封包讀名稱，只擋空字串與任何連線
 * {@code usernames[]} 裡在線的同名；「接替死亡玩家」與「新的分割畫面玩家」兩個分支都直接
 * {@code connection.setUserName(playerIndex, 名稱)}，stage 2 的 {@code GameServer.receivePlayerConnect}
 * 再把它設成 {@code player.username}。0 號（主玩家重生）不看 {@code AllowCoop}，改過 Lua 的客戶端重生後就能換名；
 * 分割畫面用原版 CoopUserName 面板就能填別人的名字。以名字認人的檢查（安全屋、陣營、MOD）因此把他當成那個帳號，
 * 冒名者在線時本人也登不進來。角色資料與權限取自連線，不受影響。
 *
 * <p><b>手術</b>：parse 頭部 headCall（slots 0, 2）綁本次封包與連線登入名；唯一的 {@code b.getUTF()}
 * （stage 1 名稱）改道 {@link #readName}。helper 照原版讀名稱，回傳原版後續程式要用的名稱：
 * <ul>
 *   <li>0 號：一律用連線登入的帳號 {@code connection.getUserName()}（原版首次進場的 ConnectPacket 也用它；
 *       正常客戶端重生送的就是登入名）。連線沒有登入名時回空字串拒絕。</li>
 *   <li>1–3 號：名稱屬於任何帳號（whitelist 不分大小寫，同 LoginPacket）就回空字串。</li>
 * </ul>
 * 回空字串＝走原版「No username given」拒絕，在 disconnectPlayer、配 ID、送 granted 之前結束。
 * 空名稱照原版拒絕，其餘照原版；helper 不改任何連線狀態，寫入一律由原版完成。
 *
 * <p>{@code -Dmdc.coopNameGuard}：未設定／{@code 1}／{@code enforce}＝執法（預設；未知值也執法）；
 * {@code 2}／{@code observe}＝只記錄、照原版；{@code 0}／{@code off}＝原版。需重啟。
 * 封包處理在伺服器主執行緒；判定的 RuntimeException 計入 anomalies 並照原版放行。
 */
public final class MdcCoopNameGuard {

    static final int OFF = 0;
    static final int ENFORCE = 1;
    static final int OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.coopNameGuard"));

    private static final String TAG = "[MinidoracatJavaPatch][CoopNameGuard] ";
    /** 逐筆 log 的時間窗上限：每 10 秒最多 20 行（客戶端可以連發封包）。 */
    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    private static final int NAME_LOG_MAX = 48;

    /** 本次 parse 的封包與連線登入名：begin 設定、readName 取用即清。 */
    private static final ThreadLocal<ConnectCoopPacket> PACKET = new ThreadLocal<>();
    private static final ThreadLocal<String> LOGIN = new ThreadLocal<>();

    // 以下只供 log；封包處理是主執行緒單線。
    private static long renamed;
    private static long rejected;
    private static long anomalies;
    private static long suppressed;
    private static long windowStartNs;
    private static int windowCount;
    private static boolean announced;

    static int parseMode(String raw) {
        if (raw == null) {
            return ENFORCE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            case "2", "observe" -> OBSERVE;
            default -> ENFORCE;
        };
    }

    /** {@code ConnectCoopPacket.parse} 頭部 headCall（slots 0, 2）。 */
    public static void begin(ConnectCoopPacket packet, IConnection connection) {
        if (MODE == OFF) {
            return;
        }
        PACKET.set(packet);
        LOGIN.set(connection == null ? null : connection.getUserName());
    }

    /** parse 內唯一 {@code b.getUTF()}（stage 1 名稱）的改道目標。 */
    public static String readName(ByteBufferReader b) {
        String sent = b.getUTF();
        if (MODE == OFF) {
            return sent;
        }
        ConnectCoopPacket packet = PACKET.get();
        String login = LOGIN.get();
        PACKET.remove();
        LOGIN.remove();
        announce();
        if (packet == null) {
            anomalies++;
            report("anomaly", -1, login, sent, "unbound");
            return sent;
        }
        int playerIndex = packet.playerIndex;
        String enforced;
        String verdict;
        String reason = null;
        try {
            if (sent.isEmpty()) {
                return sent;                       // 原版自己拒絕
            }
            if (playerIndex == 0) {
                if (login == null || login.isEmpty()) {
                    enforced = "";
                    verdict = "rejected";
                    reason = "noLogin";
                } else if (login.equals(sent)) {
                    return sent;
                } else {
                    enforced = login;
                    verdict = "renamed";
                }
            } else if (ServerWorldDatabase.instance.containsCaseinsensitiveUser(sent)) {
                enforced = "";
                verdict = "rejected";
                reason = "account";
            } else {
                return sent;
            }
        } catch (RuntimeException e) {
            anomalies++;
            report("anomaly", playerIndex, login, sent, e.getClass().getSimpleName());
            return sent;
        }
        boolean rename = verdict.equals("renamed");
        if (rename) {
            renamed++;
        } else {
            rejected++;
        }
        boolean enforce = MODE == ENFORCE;
        report(enforce ? verdict : rename ? "wouldRename" : "wouldReject", playerIndex, login, sent, reason);
        return enforce ? enforced : sent;
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        try {
            DebugLog.log(TAG + "首次生效 mode=" + modeName() + "（-Dmdc.coopNameGuard=observe|off）");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static void report(String verdict, int playerIndex, String login, String sent, String reason) {
        long now = System.nanoTime();
        if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed++;
            return;
        }
        windowCount++;
        try {
            DebugLog.log(TAG + verdict + " player=" + (playerIndex + 1) + "/4 login=" + quote(login)
                    + " sent=" + quote(sent) + (reason == null ? "" : " reason=" + reason)
                    + " mode=" + modeName() + " renamed=" + renamed + " rejected=" + rejected
                    + " suppressed=" + suppressed + " anomalies=" + anomalies);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 名稱來自客戶端：截斷並把控制字元與引號換成 ?，log 行不會被偽造或灌爆。 */
    static String quote(String name) {
        if (name == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(Math.min(name.length(), NAME_LOG_MAX) + 16).append('"');
        for (int i = 0; i < name.length() && i < NAME_LOG_MAX; i++) {
            char c = name.charAt(i);
            sb.append(c < 0x20 || c == 0x7f || c == '"' ? '?' : c);
        }
        sb.append('"');
        if (name.length() > NAME_LOG_MAX) {
            sb.append("(len=").append(name.length()).append(')');
        }
        return sb.toString();
    }

    private static String modeName() {
        return MODE == ENFORCE ? "enforce" : MODE == OBSERVE ? "observe" : "off";
    }

    // ---- 測試存取器 ----

    static long renamedCount() {
        return renamed;
    }

    static long rejectedCount() {
        return rejected;
    }

    static long anomalyCount() {
        return anomalies;
    }

    private MdcCoopNameGuard() {}
}
