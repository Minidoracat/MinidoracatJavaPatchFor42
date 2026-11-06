package zombie.network;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;

import zombie.characters.Roles;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;

/**
 * W97 Steam 模式也擋 {@code bannedip}（docs/patches.md 2df）。
 *
 * <p><b>原版</b>：{@code ServerWorldDatabase.authClient(String,String,String,long,int)} 只在 {@code !SteamUtils.isSteamModeEnabled()}
 * 時查 {@code bannedip}，Steam 模式完全跳過。{@code /banip}（{@code BanSystem.BanIP}）只寫 {@code bannedip}；{@code /banuser … -ip}
 * （{@code BanUserByIP}）另外封鎖並踢掉當下那個帳號，但寫進 {@code bannedip} 的位址同樣不擋之後的登入：Steam 模式的伺服器封了 IP，
 * 同一個 IP 換一個 Steam 帳號照樣進得來。原版自己知道 Steam Relay 連線拿不到真 IP：{@code BanIP} 拒絕封鎖正在被 Relay 連線使用的位址，
 * {@code BanUserByIP} 拒絕封鎖 Relay 連線的玩家。
 *
 * <p><b>做法</b>：W23 已把兩個登入封包（{@code LoginPacket}、{@code GoogleAuthKeyPacket}）的 {@code authClient} 呼叫改道到
 * {@link MdcAccountGate#authClient}，那裡先呼叫 {@link #check}。Steam 模式、位址不是 {@code 127.0.0.1}／{@code localhost}
 * （與原版同樣的豁免），而且這條連線是直連（{@code ConnectionType.UDPRakNet}）時，照原版同一句 SQL 查 {@code bannedip}；
 * 查到就回原版同樣的封鎖結果（{@code authorized=false}、{@code bannedReason}、{@code Roles.getDefaultForBanned()}），
 * 之後由原版 {@code LoginPacket} 記 {@code access denied: user "…" is banned}、送 {@code AccessDenied} 並斷線。
 * Relay 連線（{@code ConnectionType.Steam}）的位址是共用的轉送位址，不查（同原版 {@code BanSystem} 的判斷）；找不到這條連線也不查。
 * 非 Steam 模式照原版（原版自己查）。
 *
 * <p>開關 {@code -Dmdc.steamIpBan}：預設 on；{@code 0|off} 照原版（需重啟）。查詢失敗一律放行並記 log（登入閘門壞掉不該把全服鎖在門外，同 W23）。
 */
public final class MdcSteamIpBan {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.steamIpBan"));
    private static final String TAG = "[MinidoracatJavaPatch][SteamIpBan] ";

    // 登入封包在主執行緒處理：計數單執行緒寫。
    private static boolean announced;
    private static long checked;
    private static long denied;
    private static long relaySkipped;
    private static long noConnection;
    private static long otherType;
    private static long failedOpen;

    private MdcSteamIpBan() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** 要不要查：Steam 模式、非本機位址、直連才查（非 Steam 模式原版自己查）。 */
    static boolean shouldCheck(boolean steamMode, String ip, UdpConnection.ConnectionType type) {
        return steamMode && ip != null && !ip.equals("127.0.0.1") && !ip.equals("localhost")
                && type == UdpConnection.ConnectionType.UDPRakNet;
    }

    /** {@code null}＝放行，照原版 {@code authClient}；否則是原版同樣的封鎖結果。 */
    static ServerWorldDatabase.LogonResult check(ServerWorldDatabase db, String user, String ip, long steamID) {
        if (!ENABLED) {
            return null;
        }
        boolean steamMode;
        UdpConnection.ConnectionType type = null;
        try {
            steamMode = SteamUtils.isSteamModeEnabled();
            if (!announced) {
                announced = true;
                log("首次生效（-Dmdc.steamIpBan=0|off 照原版）mode=on steam=" + steamMode);
            }
            if (steamMode) {
                UdpConnection conn = findConnection(GameServer.udpEngine == null ? null : GameServer.udpEngine.connections, steamID, ip);
                type = conn == null ? null : conn.getConnectionType();
                if (conn == null) {
                    noConnection++;
                } else if (type == UdpConnection.ConnectionType.Steam) {
                    relaySkipped++;
                } else if (type != UdpConnection.ConnectionType.UDPRakNet) {
                    // native 還沒回報型別（Disconnected）：不查、放行，記下來，免得直連其實都落在這裡而看不出來
                    otherType++;
                    log("skip type=" + type + " user=\"" + user + "\" (otherType=" + otherType + ")");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return failOpen(user, e);
        }
        return decide(db, user, ip, steamID, steamMode, type);
    }

    /** 判斷與查表：要查而且 {@code bannedip} 有這個位址時回封鎖結果；查詢失敗放行。 */
    static ServerWorldDatabase.LogonResult decide(ServerWorldDatabase db, String user, String ip, long steamID,
                                                  boolean steamMode, UdpConnection.ConnectionType type) {
        if (!shouldCheck(steamMode, ip, type)) {
            return null;
        }
        String reason;
        try {
            checked++;
            reason = bannedReason(db, ip);
        } catch (Exception | LinkageError e) {
            return failOpen(user, e);
        }
        if (reason == null) {
            return null;
        }
        ServerWorldDatabase.LogonResult r = db.new LogonResult();
        r.authorized = false;
        r.bannedReason = reason;
        r.role = Roles.getDefaultForBanned();
        denied++;
        try {
            log("deny user=\"" + user + "\" ip=" + ip + " steamid=" + SteamUtils.convertSteamIDToString(steamID) + " type=" + type
                    + String.format(Locale.ROOT, " (denied=%d checked=%d relaySkipped=%d noConnection=%d otherType=%d)",
                    denied, checked, relaySkipped, noConnection, otherType));
        } catch (RuntimeException | LinkageError e) {
            // 只是 log：照樣擋
        }
        return r;
    }

    private static ServerWorldDatabase.LogonResult failOpen(String user, Throwable e) {
        failedOpen++;
        log("fail-open user=\"" + user + "\": " + e + " (failedOpen=" + failedOpen + ")");
        return null;
    }

    /** log 失敗不改變結果（擋或放都照判斷）。 */
    private static void log(String text) {
        try {
            DebugLog.log(TAG + text);
        } catch (RuntimeException | LinkageError e) {
            // 只是 log
        }
    }

    /** 正在登入的連線：SteamID 與位址都相同的那一條（LoginPacket 在 authClient 之前已設好兩者）。 */
    static UdpConnection findConnection(List<UdpConnection> conns, long steamID, String ip) {
        if (conns == null) {
            return null;
        }
        for (int i = 0; i < conns.size(); i++) {
            UdpConnection c = conns.get(i);
            if (c != null && c.getSteamId() == steamID && ip.equals(c.getIP())) {
                return c;
            }
        }
        return null;
    }

    /** 原版同一句 SQL（{@code ServerWorldDatabase.authClient} 非 Steam 分支）：沒有這一列回 null；reason 是 NULL 回空字串。 */
    static String bannedReason(ServerWorldDatabase db, String ip) throws Exception {
        try (PreparedStatement stat = db.conn.prepareStatement("SELECT reason FROM bannedip WHERE ip = ?")) {
            stat.setString(1, ip);
            try (ResultSet rs = stat.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String reason = rs.getString("reason");
                return reason == null ? "" : reason;
            }
        }
    }

    // ---- 測試存取器 ----

    static long[] countersForTest() {
        return new long[]{checked, denied, relaySkipped, noConnection, failedOpen, otherType};
    }
}
