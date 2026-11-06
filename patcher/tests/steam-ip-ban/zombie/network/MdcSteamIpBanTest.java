package zombie.network;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import zombie.characters.Roles;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;

/**
 * W97 Steam 模式也擋 bannedip（MdcSteamIpBan）行為驗證：真的 SQLite（記憶體，建表與原版 DDL 相同）＋配置出來的
 * {@code ServerWorldDatabase}。判斷表（Steam 模式 × 位址 × 連線型別）、查表（有理由、理由是 NULL、沒有這一列）、查詢失敗放行、
 * 找連線（SteamID 與位址都要相同）；封鎖結果與原版非 Steam 模式 {@code authClient} 對同一個位址回的結果逐欄相同（authorized、
 * bannedReason、role）；原版 Steam 模式不查（負對照）。argv：{@code on}／{@code off}，自驗旗標。
 */
public final class MdcSteamIpBanTest {
    private static final PrintStream OUT = System.out;
    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean on = "on".equals(args[0]);
        expect("自驗：argv=" + args[0] + " 與 -Dmdc.steamIpBan=" + System.getProperty("mdc.steamIpBan") + " 相符", MdcSteamIpBan.ENABLED == on);
        expect("parseEnabled：null／1／亂寫→on、0／off／ OFF →off",
                MdcSteamIpBan.parseEnabled(null) && MdcSteamIpBan.parseEnabled("1") && MdcSteamIpBan.parseEnabled("x")
                        && !MdcSteamIpBan.parseEnabled("0") && !MdcSteamIpBan.parseEnabled(" OFF "));
        UdpConnection.ConnectionType direct = UdpConnection.ConnectionType.UDPRakNet;
        UdpConnection.ConnectionType relay = UdpConnection.ConnectionType.Steam;
        expect("shouldCheck：只有 Steam 模式＋非本機位址＋直連才查",
                MdcSteamIpBan.shouldCheck(true, "203.0.113.7", direct)
                        && !MdcSteamIpBan.shouldCheck(false, "203.0.113.7", direct)
                        && !MdcSteamIpBan.shouldCheck(true, "127.0.0.1", direct)
                        && !MdcSteamIpBan.shouldCheck(true, "localhost", direct)
                        && !MdcSteamIpBan.shouldCheck(true, "203.0.113.7", relay)
                        && !MdcSteamIpBan.shouldCheck(true, "203.0.113.7", UdpConnection.ConnectionType.Disconnected)
                        && !MdcSteamIpBan.shouldCheck(true, "203.0.113.7", null)
                        && !MdcSteamIpBan.shouldCheck(true, null, direct));

        ServerWorldDatabase db = alloc(ServerWorldDatabase.class);
        Connection sql = DriverManager.getConnection("jdbc:sqlite::memory:");
        db.conn = sql;
        try (Statement st = sql.createStatement()) {
            st.executeUpdate("CREATE TABLE [bannedip] ([ip] TEXT NOT NULL,[username] TEXT NULL, [reason] TEXT NULL)");
            st.executeUpdate("INSERT INTO bannedip (ip, username, reason) VALUES ('203.0.113.7', 'cheater', 'teleport hack')");
            st.executeUpdate("INSERT INTO bannedip (ip, username, reason) VALUES ('203.0.113.8', NULL, NULL)");
        }
        long sid = 76561190000000001L;
        ServerWorldDatabase.LogonResult r = MdcSteamIpBan.decide(db, "alt", "203.0.113.7", sid, true, direct);
        expect("直連、位址在 bannedip：authorized=false、bannedReason=teleport hack、role=預設封鎖角色",
                r != null && !r.authorized && "teleport hack".equals(r.bannedReason) && r.role == Roles.getDefaultForBanned());
        ServerWorldDatabase.LogonResult nullReason = MdcSteamIpBan.decide(db, "alt", "203.0.113.8", sid, true, direct);
        expect("reason 是 NULL：照樣擋、bannedReason 是空字串（LoginPacket 同樣送 Banned）",
                nullReason != null && !nullReason.authorized && "".equals(nullReason.bannedReason));
        expect("位址不在 bannedip：放行（null）", MdcSteamIpBan.decide(db, "ok", "203.0.113.9", sid, true, direct) == null);
        expect("Relay 連線、找不到連線、本機位址、非 Steam 模式：位址在 bannedip 也不查（null）",
                MdcSteamIpBan.decide(db, "alt", "203.0.113.7", sid, true, relay) == null
                        && MdcSteamIpBan.decide(db, "alt", "203.0.113.7", sid, true, null) == null
                        && MdcSteamIpBan.decide(db, "alt", "127.0.0.1", sid, true, direct) == null
                        && MdcSteamIpBan.decide(db, "alt", "203.0.113.7", sid, false, direct) == null);

        // 與原版非 Steam 模式的封鎖結果逐欄相同（原版自己查 bannedip 的那條路徑）
        setSteam(false);
        ServerWorldDatabase.LogonResult vanilla = db.authClient("alt", "", "203.0.113.7", 0L, 1);
        expect("原版非 Steam 模式同一個位址：authorized／bannedReason／role 與本刀相同",
                !vanilla.authorized && "teleport hack".equals(vanilla.bannedReason) && vanilla.role == r.role
                        && vanilla.needSecondFactor == r.needSecondFactor && vanilla.dcReason == r.dcReason);
        // 負對照：原版 Steam 模式不查 bannedip（會往下查 whitelist：本測試沒有這張表，原版 catch 後回未授權但不是封鎖）
        setSteam(true);
        ServerWorldDatabase.LogonResult vanillaSteam = db.authClient("alt", "", "203.0.113.7", sid, 1);
        expect("原版 Steam 模式：沒有 bannedReason（不查 bannedip，這是本刀的存在理由）", vanillaSteam.bannedReason == null);

        // 找連線：SteamID 與位址都要相同
        UdpConnection a = conn(sid, "203.0.113.7");
        UdpConnection b = conn(sid + 1, "203.0.113.7");
        UdpConnection c = conn(sid, "198.51.100.1");
        List<UdpConnection> list = new ArrayList<>(List.of(b, c, a));
        list.add(1, null);
        expect("findConnection：SteamID 與位址都相同的那一條；沒有就 null；清單 null 也是 null",
                MdcSteamIpBan.findConnection(list, sid, "203.0.113.7") == a
                        && MdcSteamIpBan.findConnection(list, sid + 2, "203.0.113.7") == null
                        && MdcSteamIpBan.findConnection(null, sid, "203.0.113.7") == null);

        // check()：Steam 模式但找不到連線（udpEngine 是 null）→ 放行；off 一律放行
        GameServer.udpEngine = null;
        long[] before = MdcSteamIpBan.countersForTest();
        expect("check：找不到連線時放行" + (on ? "並計 noConnection" : "（off 不計數）"),
                MdcSteamIpBan.check(db, "alt", "203.0.113.7", sid) == null
                        && MdcSteamIpBan.countersForTest()[3] == before[3] + (on ? 1 : 0));
        setSteam(false);
        expect("check：非 Steam 模式放行（原版自己查）", MdcSteamIpBan.check(db, "alt", "203.0.113.7", sid) == null);

        // 查詢失敗：放行並計 failedOpen
        sql.close();
        long failBefore = MdcSteamIpBan.countersForTest()[4];
        expect("查詢失敗（連線已關）：放行、failedOpen +1",
                MdcSteamIpBan.decide(db, "alt", "203.0.113.7", sid, true, direct) == null
                        && MdcSteamIpBan.countersForTest()[4] == failBefore + 1);

        if (failed > 0) {
            OUT.println("steam-ip-ban FAIL " + failed);
            System.exit(1);
        }
        OUT.println("steam-ip-ban OK mode=" + args[0]);
    }

    private static void setSteam(boolean enabled) throws Exception {
        Field f = SteamUtils.class.getDeclaredField("steamEnabled");
        f.setAccessible(true);
        f.setBoolean(null, enabled);
        if (SteamUtils.isSteamModeEnabled() != enabled) {
            throw new IllegalStateException("SteamUtils.steamEnabled 沒套用");
        }
    }

    private static UdpConnection conn(long steamId, String ip) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        Field s = UdpConnection.class.getDeclaredField("steamId");
        s.setAccessible(true);
        s.setLong(c, steamId);
        Field i = UdpConnection.class.getDeclaredField("ip");
        i.setAccessible(true);
        i.set(c, ip);
        return c;
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        OUT.println((ok ? "sib pass  " : "sib FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
