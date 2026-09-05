package zombie.network;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.characters.Roles;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;

/**
 * 每 Steam 身分帳號上限的「登入期」執法（W23，2026-09-06）。
 *
 * vanilla {@code MaxAccountsPerUser} 只在 {@code ServerWorldDatabase.authClient} 的「whitelist 查無此
 * 帳號名」分支呼叫 {@code isNewAccountAllowed}（javap offset 685）；既有帳號分支在 offset 643 直接
 * {@code areturn}，完全不數。加上計數 key 是連線的 {@code getSteamId()}（家庭共享時是子帳號 ID，
 * whitelist 存的卻是 ownerid），以及 whitelist.steamid 每次登入都被 {@code setUserSteamID} 改寫成
 * 「最後登入者」——三者疊加讓正式服 636 個 Steam 身分累積出 1284 個帳號。
 *
 * 本刀改道 LoginPacket／GoogleAuthKeyPacket 的 {@code authClient} 呼叫：先跑 vanilla，authorized 才
 * 追加一道以 {@code lastConnection} 排序的名額判定——同一身分底下，只有最近登入的前 max 個帳號名
 * 能登入，其餘回 {@code MaxAccountsReached}（沿用 vanilla dcReason，client 顯示既有翻譯）。
 * 不刪任何資料、拔掉 loose class 即回 vanilla。
 *
 * 身分 key 預設 = 連線的 {@code getSteamId()}（與 vanilla 計數同語意：家庭共享子帳號各算各的，
 * 使用者 2026-09-06 決定——真有兩個人共用一套家庭庫）。{@code -Dmdc.accountGate.key=owner} 改為
 * {@code UdpConnection.getOwnerId()}（LoginPacket 在 authClient 之前已 setOwnerId），whitelist 側取
 * {@code ownerid} 非空者優先、否則 {@code steamid}——整個家庭只給 max 個帳號。
 * vanilla 的 PriorityLogin（role≥priority）豁免照舊保留。
 * kill switch：{@code -Dmdc.accountGate=0}。
 * 例外一律 fail-open（回 vanilla 結果並記 log）——登入閘門壞掉不該把全服鎖在門外。
 */
public final class MdcAccountGate {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.accountGate"));
    private static final boolean KEY_BY_OWNER = "owner".equals(System.getProperty("mdc.accountGate.key"));
    private static final String DC_REASON = "MaxAccountsReached";

    private static final AtomicLong passed = new AtomicLong();
    private static final AtomicLong denied = new AtomicLong();
    private static final AtomicLong failedOpen = new AtomicLong();

    /** whitelist 一列：帳號名＋是否具 PriorityLogin（依 lastConnection 由新到舊）。 */
    record Row(String username, boolean priority) {}

    /** LoginPacket／GoogleAuthKeyPacket 內 authClient(String,String,String,long,int) 的改道目標。 */
    public static ServerWorldDatabase.LogonResult authClient(ServerWorldDatabase db, String user, String pass,
                                                             String ip, long steamID, int authType) {
        ServerWorldDatabase.LogonResult r = db.authClient(user, pass, ip, steamID, authType);
        if (!ENABLED || !r.authorized) {
            return r;
        }
        try {
            int max = ServerOptions.instance.maxAccountsPerUser.getValue();
            if (max <= 0 || !SteamUtils.isSteamModeEnabled()) {
                return r;
            }
            long key = KEY_BY_OWNER ? ownerOf(steamID) : steamID;
            String keyStr = SteamUtils.convertSteamIDToString(key);
            List<Row> rows = load(db, keyStr);
            int rank = rankOf(rows, user);
            if (allowed(rows, user, max)) {
                passed.incrementAndGet();
                return r;
            }
            r.authorized = false;
            r.dcReason = DC_REASON;
            long n = denied.incrementAndGet();
            DebugLog.log("[MinidoracatJavaPatch][AccountGate] deny user=\"" + user + "\" key=" + keyStr
                    + " rank=" + rank + " accounts=" + rows.size() + " max=" + max
                    + " (denied=" + n + " passed=" + passed.get() + ")");
        } catch (Throwable t) {
            failedOpen.incrementAndGet();
            DebugLog.log("[MinidoracatJavaPatch][AccountGate] fail-open user=\"" + user + "\": " + t);
        }
        return r;
    }

    /**
     * 純判定：rows 已依 lastConnection 由新到舊。任一列具 PriorityLogin → 放行（vanilla 豁免）；
     * user 在清單內 → 名次 &lt; max 才放行；不在清單（新帳號）→ 既有數 &lt; max 才放行。
     */
    static boolean allowed(List<Row> rows, String user, int max) {
        for (Row row : rows) {
            if (row.priority()) {
                return true;
            }
        }
        int rank = rankOf(rows, user);
        return rank < 0 ? rows.size() < max : rank < max;
    }

    static int rankOf(List<Row> rows, String user) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).username().equals(user)) {
                return i;
            }
        }
        return -1;
    }

    private static List<Row> load(ServerWorldDatabase db, String keyStr) throws Exception {
        String where = KEY_BY_OWNER
                ? "(CASE WHEN ownerid IS NOT NULL AND ownerid <> '' THEN ownerid ELSE steamid END) = ?"
                : "steamid = ?";
        String sql = "SELECT username, role FROM whitelist WHERE " + where
                + " ORDER BY (lastConnection IS NULL), lastConnection DESC, id DESC";
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement stat = db.conn.prepareStatement(sql)) {
            stat.setString(1, keyStr);
            try (ResultSet rs = stat.executeQuery()) {
                while (rs.next()) {
                    Role role = Roles.getRoleById(rs.getInt(2));
                    rows.add(new Row(rs.getString(1), role != null && role.hasCapability(Capability.PriorityLogin)));
                }
            }
        }
        return rows;
    }

    /** 由 steamId 反查連線的 ownerId；找不到（或 udpEngine 未建）就退回 steamId。 */
    private static long ownerOf(long steamID) {
        if (GameServer.udpEngine == null) {
            return steamID;
        }
        List<UdpConnection> conns = GameServer.udpEngine.connections;
        for (int i = 0; i < conns.size(); i++) {
            UdpConnection c = conns.get(i);
            if (c != null && c.getSteamId() == steamID) {
                long owner = c.getOwnerId();
                return owner != 0L ? owner : steamID;
            }
        }
        return steamID;
    }

    private MdcAccountGate() {}
}
