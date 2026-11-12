package zombie.network.packets.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * W99 沒人開的車被客戶端搬動的觀測（只寫 log、不擋；docs/patches.md 2dg）。車輛物理同步的伺服器端驗證有缺口，細節不在公開檔描述。
 *
 * <p><b>手術</b>：{@code VehiclePhysicsPacket.processServer} 頭部 {@link #begin} 記下封包與送出的連線；原版通過檢查之後
 * 第一個呼叫、全方法唯一的 {@code BaseVehicle.setClientForce} 同形改道 {@link #setClientForce}：這時車的位置還是舊的，
 * 先原樣呼叫一次原版，再比對封包的新位置。{@code VehicleCollidePacket.processServer} 頭部 W46 的 helper 先把封包交給
 * {@link #onCollide}（記下這次申請；不符合原版客戶端送出條件的記一行）。
 *
 * <p><b>一段搬動</b>：同一台沒有駕駛的車、同一個授權者、封包沒有中斷，算一段；分類每包重算，變了就換一段。同一台車
 * {@link #ORIGIN_MS} 內的下一段沿用上一段的起點累計（分段搬也算總距離；同一個 vid 換了車不算），有人坐上駕駛座或被拖走就清掉。{@code unseated}／{@code noPlayer}
 * 累計離起點達門檻時當場記一行 {@code moving}；收段時累計達門檻、且比上次記的多出門檻以上，記一行 {@code moved}。{@code towed}（同一位玩家開的車拖著）只計數。
 * {@code pushed} 只算那位玩家開的車就在旁邊（{@link #REQ_FAR} 格內）。明細行：每類每次啟動最多 {@link #LOG_CAP} 行、每個帳號每類最多
 * {@link #USER_CAP} 行（到頂時印一行通知），帳號的控制字元、引號與逗號換成 ?。
 *
 * <p>開關 {@code -Dmdc.unseatedVehicleWatch}：{@code observe}（預設，未知值也是）／{@code 0|off} 照原版。都要重啟。
 * 封包在主執行緒處理（{@code GameServer.mainLoopDealWithNetData}），狀態不加鎖。原版 {@code setClientForce} 在 try 外恰呼叫一次；
 * 觀測只 catch {@code RuntimeException|LinkageError} 計 anomalies，Error 照常外拋。
 */
public final class MdcUnseatedVehicleWatch {

    static final boolean ENABLED = enabled(System.getProperty("mdc.unseatedVehicleWatch"));
    static final float MIN_TILES = parseTiles(System.getProperty("mdc.unseatedVehicleWatchTiles"));
    /** 相鄰兩包超過這麼久就當成另一段。 */
    static final long GAP_MS = 5_000L;
    /** 記下的申請多久內還能對到一段搬動。 */
    static final long GRANT_MS = 60_000L;
    /** 累計起點保留多久。 */
    static final long ORIGIN_MS = 30 * 60_000L;
    /** 「就在旁邊」的距離（格）；伺服器看到的位置比客戶端落後，留了餘量。 */
    static final float REQ_FAR = 24.0F;
    static final long REQ_LOG_MS = 5_000L;
    static final long BEAT_MS = 600_000L;
    /** 搬動（moving／moved）與可疑申請（collide-request／release-to-player）各自的行數上限：刷申請不會吃掉搬動的額度。 */
    static final int LOG_CAP = 2000;
    /** 每個帳號每類（搬動、申請）每次啟動的明細行數上限：一個帳號刷不完別人的額度，刷申請也吃不到自己搬動的額度。 */
    static final int USER_CAP = 100;
    private static final String TAG = "[MinidoracatJavaPatch][UnseatedVehicleWatch] ";

    static final class Episode {
        BaseVehicle vehicle;
        short vid;
        String car;
        short pid;
        BaseVehicle.Authorization auth;
        float sx;
        float sy;
        float lx;
        float ly;
        long startMs;
        long lastMs;
        int packets;
        /** 帳號原文（額度、節流、去重的身分）；{@link #user} 是清洗後寫進 log 的。 */
        String userKey;
        String user;
        String steamId;
        String seat;
        float playerDistStart;
        String grant;
        String kind;
        boolean announced;
        /** 這串累計上次記 moved 的距離（0＝還沒記）。 */
        float loggedDist;
        /** 沿用起點的前幾段用過的其他帳號原文（null＝沒有）。 */
        ArrayList<String> earlier;
    }

    /** 收段後保留的累計起點。 */
    static final class Origin {
        BaseVehicle vehicle;
        float sx;
        float sy;
        long startMs;
        long lastMs;
        ArrayList<String> users;
        boolean announced;
        float loggedDist;
    }

    static final class Request {
        long ms;
        short pid;
        String user;
        boolean own;
        boolean collide;
    }

    static final HashMap<Short, Episode> open = new HashMap<>();
    static final HashMap<Short, Request> requests = new HashMap<>();
    static final HashMap<Short, Origin> origins = new HashMap<>();
    static final HashMap<String, Long> reqLogged = new HashMap<>();
    static final HashMap<String, Integer> userMoveLines = new HashMap<>();
    static final HashMap<String, Integer> userRequestLines = new HashMap<>();

    private static VehiclePhysicsPacket curPacket;
    private static UdpConnection curConn;
    private static long lastSweepMs;
    private static long lastBeatMs;
    private static boolean announced;

    static long accepted;
    static long episodes;
    static long towed;
    static long pushed;
    static long unseated;
    static long noPlayer;
    static long logged;
    static long movingNow;
    static long requestsSeen;
    static long requestsSuspect;
    static long releasesToPlayer;
    static long anomalies;
    static long userCapped;
    static int moveLines;
    static int requestLines;

    private MdcUnseatedVehicleWatch() {}

    static boolean enabled(String raw) {
        if (raw == null) {
            return true;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !v.equals("0") && !v.equals("off");
    }

    static float parseTiles(String raw) {
        if (raw != null) {
            try {
                float v = Float.parseFloat(raw.trim());
                if (v > 0.0F && !Float.isInfinite(v)) {
                    return v;
                }
            } catch (RuntimeException e) {
                // 照預設（只准 RuntimeException／LinkageError，SmokeCheck 釘）
            }
        }
        return 5.0F;
    }

    /** {@code VehiclePhysicsPacket.processServer} 頭部 headCall（slots 0, 2）。 */
    public static void begin(VehiclePhysicsPacket packet, UdpConnection connection) {
        curPacket = packet;
        curConn = connection;
    }

    /** {@code processServer} 唯一的 {@code setClientForce} 改道：原版通過授權與時間檢查才走到這裡，車的位置還是舊的。 */
    public static void setClientForce(BaseVehicle vehicle, float force) {
        float oldX = vehicle.getX();
        float oldY = vehicle.getY();
        vehicle.setClientForce(force);
        if (!ENABLED) {
            return;
        }
        VehiclePhysicsPacket packet = curPacket;
        UdpConnection connection = curConn;
        curPacket = null;
        curConn = null;
        try {
            if (packet == null || connection == null || packet.getVehicle() != vehicle) {
                anomalies++;
                return;
            }
            accepted++;
            observe(vehicle, packet.getX(), packet.getY(), oldX, oldY, connection, System.currentTimeMillis());
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    static void observe(BaseVehicle vehicle, float x, float y, float oldX, float oldY, UdpConnection connection, long now) {
        if (!ENABLED) {
            return;
        }
        short vid = vehicle.getId();
        if (vehicle.getDriver() != null) {
            Episode ep = open.isEmpty() ? null : open.remove(vid);
            if (ep != null) {
                close(ep);
            }
            if (!origins.isEmpty()) {
                origins.remove(vid);   // 有人開了：之後的搬動重新起算
            }
        } else {
            Episode ep = open.get(vid);
            if (ep != null && (ep.vehicle != vehicle || ep.pid != vehicle.netPlayerId || now - ep.lastMs > GAP_MS
                    || !ep.kind.equals(kind(vehicle, GameServer.IDToPlayerMap.get(ep.pid))))) {
                open.remove(vid);
                close(ep);
                ep = null;
            }
            if (ep == null) {
                ep = start(vehicle, oldX, oldY, connection, now);
                open.put(vid, ep);
            }
            ep.lx = x;
            ep.ly = y;
            ep.lastMs = now;
            ep.packets++;
            if (!ep.announced && (ep.kind.equals("unseated") || ep.kind.equals("noPlayer"))
                    && dist(ep.sx, ep.sy, x, y) >= MIN_TILES) {
                ep.announced = true;
                movingNow++;
                if (moveLines < LOG_CAP && budget(userMoveLines, ep.userKey, "搬動")) {
                    moveLines = log("moving " + describe(ep, dist(ep.sx, ep.sy, x, y)), moveLines);
                }
            }
        }
        if (now - lastSweepMs >= 1000L) {
            lastSweepMs = now;
            sweep(now);
            beat(now);
        }
    }

    private static Episode start(BaseVehicle vehicle, float x, float y, UdpConnection connection, long now) {
        episodes++;
        Episode ep = new Episode();
        ep.vehicle = vehicle;
        ep.vid = vehicle.getId();
        ep.car = vehicle.getSqlId() + "/" + ep.vid + " " + vehicle.getScriptName();
        ep.pid = vehicle.netPlayerId;
        ep.auth = vehicle.netPlayerAuthorization;
        ep.sx = x;
        ep.sy = y;
        ep.startMs = now;
        ep.userKey = String.valueOf(connection.getUserName());
        ep.user = clean(ep.userKey);
        ep.steamId = SteamUtils.convertSteamIDToString(connection.getSteamId());
        IsoPlayer player = GameServer.IDToPlayerMap.get(ep.pid);
        ep.kind = kind(vehicle, player);
        if (player == null) {
            ep.seat = "?";
            ep.playerDistStart = -1.0F;
        } else {
            ep.playerDistStart = dist(player.getX(), player.getY(), x, y);
            BaseVehicle in = player.getVehicle();
            ep.seat = in == null ? "none" : seatText(in, player);
            if (ep.kind.equals("pushed")) {
                ep.seat += " d=" + fmt(dist(in.getX(), in.getY(), x, y));
            }
        }
        Origin o = ep.kind.equals("towed") ? origins.remove(ep.vid) : origins.get(ep.vid);   // 拖曳前後不跨段累計
        if (o != null && !ep.kind.equals("towed") && o.vehicle == vehicle && now - o.lastMs <= ORIGIN_MS) {
            ep.sx = o.sx;
            ep.sy = o.sy;
            ep.startMs = o.startMs;
            ep.announced = o.announced;
            ep.loggedDist = o.loggedDist;
            ep.earlier = new ArrayList<>(o.users);
            ep.earlier.remove(ep.userKey);
            if (ep.earlier.isEmpty()) {
                ep.earlier = null;
            }
        }
        Request r = requests.get(ep.vid);
        ep.grant = r != null && r.pid == ep.pid && now - r.ms <= GRANT_MS
                ? (r.collide ? "collide" : "release") + "(by=\"" + r.user + "\" ago=" + fmt((now - r.ms) / 1000.0F) + "s own=" + (r.own ? "yes" : "no") + ")"
                : "other";
        return ep;
    }

    /** 每包重算：拆了拖車、下了車就換一段，免記錄的 towed 不能延續到之後的搬動。 */
    static String kind(BaseVehicle vehicle, IsoPlayer player) {
        if (player == null) {
            return "noPlayer";
        }
        if (isTow(vehicle, player)) {
            return "towed";
        }
        BaseVehicle in = player.getVehicle();
        return in != null && in != vehicle && in.isDriver(player)
                && dist(in.getX(), in.getY(), vehicle.getX(), vehicle.getY()) <= REQ_FAR ? "pushed" : "unseated";
    }

    /** 被拖或拖著的那台由同一位玩家開：原版拖車時兩台都交給駕駛。 */
    private static boolean isTow(BaseVehicle vehicle, IsoPlayer player) {
        BaseVehicle partner = vehicle.getVehicleTowedBy() != null ? vehicle.getVehicleTowedBy() : vehicle.getVehicleTowing();
        return partner != null && partner.getDriver() == player;
    }

    private static String seatText(BaseVehicle in, IsoPlayer player) {
        int seat = in.getSeat(player);
        return (seat == 0 ? "driving " : "seat" + seat + " ") + in.getSqlId() + "/" + in.getId() + " " + in.getScriptName();
    }

    static void sweep(long now) {
        if (!open.isEmpty()) {
            Iterator<Episode> it = open.values().iterator();
            while (it.hasNext()) {
                Episode ep = it.next();
                if (now - ep.lastMs > GAP_MS) {
                    it.remove();
                    close(ep);
                }
            }
        }
        if (!requests.isEmpty()) {
            requests.values().removeIf(r -> now - r.ms > GRANT_MS);
        }
        if (!reqLogged.isEmpty()) {
            reqLogged.values().removeIf(ms -> now - ms > REQ_LOG_MS);
        }
        if (!origins.isEmpty()) {
            origins.values().removeIf(o -> now - o.lastMs > ORIGIN_MS);
        }
    }

    private static void close(Episode ep) {
        switch (ep.kind) {
            case "towed" -> towed++;
            case "pushed" -> pushed++;
            case "unseated" -> unseated++;
            default -> noPlayer++;
        }
        if (ep.kind.equals("towed")) {
            return;
        }
        Origin o = new Origin();
        o.vehicle = ep.vehicle;
        o.sx = ep.sx;
        o.sy = ep.sy;
        o.startMs = ep.startMs;
        o.lastMs = ep.lastMs;
        o.users = ep.earlier == null ? new ArrayList<>(2) : new ArrayList<>(ep.earlier);
        o.users.add(ep.userKey);
        o.announced = ep.announced;
        o.loggedDist = ep.loggedDist;
        origins.put(ep.vid, o);
        float d = dist(ep.sx, ep.sy, ep.lx, ep.ly);
        if (d < MIN_TILES || d < ep.loggedDist + MIN_TILES) {
            return;   // 同一串累計：比上次記的多出門檻才再記
        }
        o.loggedDist = d;
        logged++;
        if (moveLines < LOG_CAP && budget(userMoveLines, ep.userKey, "搬動")) {
            moveLines = log("moved " + describe(ep, d), moveLines);
        }
    }

    private static String describe(Episode ep, float d) {
        IsoPlayer player = GameServer.IDToPlayerMap.get(ep.pid);
        String end = player == null ? "?" : fmt(dist(player.getX(), player.getY(), ep.lx, ep.ly));
        return "kind=" + ep.kind + " vehicle=" + ep.car + " from=" + fmt(ep.sx) + "," + fmt(ep.sy)
                + " to=" + fmt(ep.lx) + "," + fmt(ep.ly) + " dist=" + fmt(d)
                + " secs=" + fmt((ep.lastMs - ep.startMs) / 1000.0F) + " packets=" + ep.packets
                + " user=\"" + ep.user + "\" steamid=" + ep.steamId + " auth=" + ep.auth + " pid=" + ep.pid
                + " seat=" + ep.seat + " playerDist=" + fmt(ep.playerDistStart) + "/" + end + " grant=" + ep.grant
                + (ep.earlier == null ? "" : " earlierUsers=\"" + earlierText(ep.earlier) + "\"");
    }

    /** W46 helper（{@code VehicleCollidePacket.processServer} 頭部）先轉來，在原版處理之前。 */
    static void onCollide(VehicleCollidePacket packet, UdpConnection connection) {
        if (!ENABLED) {
            return;
        }
        try {
            BaseVehicle vehicle = packet.vehicleId.getVehicle();
            if (vehicle == null || connection == null
                    || vehicle.isNetPlayerAuthorization(BaseVehicle.Authorization.Local)) {
                return;   // 不看
            }
            long now = System.currentTimeMillis();
            requestsSeen++;
            short pid = packet.player.getId();
            IsoPlayer player = pid == -1 ? null : GameServer.IDToPlayerMap.get(pid);
            boolean own = player != null && onConnection(connection, player);
            String userKey = String.valueOf(connection.getUserName());
            String user = clean(userKey);
            // 拖車夥伴（先看 towing、再看 towedBy）一起記
            BaseVehicle partner = vehicle.getVehicleTowing() != null ? vehicle.getVehicleTowing() : vehicle.getVehicleTowedBy();
            if (pid == -1) {   // 授權收回伺服器：舊申請作廢（帶 −1 的不會通過 hasAuthorization，不必記）
                requests.remove(vehicle.getId());
                if (partner != null) {
                    requests.remove(partner.getId());
                }
            } else {
                Request r = new Request();
                r.ms = now;
                r.pid = pid;
                r.user = user;
                r.own = own;
                r.collide = packet.isCollide;
                requests.put(vehicle.getId(), r);
                if (partner != null) {
                    requests.put(partner.getId(), r);
                }
            }
            if (!packet.isCollide && pid == -1 && !open.isEmpty()) {
                endOnRelease(vehicle);   // 原版這包把授權收回伺服器：這一段到此為止
                if (partner != null) {
                    endOnRelease(partner);
                }
            }
            if (now - lastSweepMs >= 1000L) {
                lastSweepMs = now;
                sweep(now);
                beat(now);
            }
            if (packet.isCollide) {
                BaseVehicle in = player == null ? null : player.getVehicle();
                boolean driving = in != null && in != vehicle && in.isDriver(player);
                float d = driving ? dist(in.getX(), in.getY(), vehicle.getX(), vehicle.getY()) : -1.0F;
                String suspect = !own ? "notOwnPlayer" : !driving ? "notDriving" : d > REQ_FAR ? "far" : null;
                if (suspect != null) {
                    requestsSuspect++;
                    if (requestLines < LOG_CAP && mayLogRequest(userKey, now) && budget(userRequestLines, userKey, "申請")) {
                        requestLines = log("collide-request suspect=" + suspect + " user=\"" + user + "\" steamid="
                                + SteamUtils.convertSteamIDToString(connection.getSteamId()) + " pid=" + pid
                                + " vehicle=" + vehicle.getSqlId() + "/" + vehicle.getId() + " " + vehicle.getScriptName()
                                + " at=" + fmt(vehicle.getX()) + "," + fmt(vehicle.getY()) + " auth=" + vehicle.netPlayerAuthorization
                                + " requester=" + (player == null ? "?" : player.getVehicle() == null ? "none"
                                        : seatText(player.getVehicle(), player)) + (driving ? " d=" + fmt(d) : ""), requestLines);
                    }
                }
            } else if (pid != -1 && (player == null || vehicle.getDriver() != player)) {
                releasesToPlayer++;
                if (requestLines < LOG_CAP && mayLogRequest(userKey, now) && budget(userRequestLines, userKey, "申請")) {
                    requestLines = log("release-to-player user=\"" + user + "\" steamid="
                            + SteamUtils.convertSteamIDToString(connection.getSteamId()) + " pid=" + pid + " own=" + (own ? "yes" : "no")
                            + " vehicle=" + vehicle.getSqlId() + "/" + vehicle.getId() + " " + vehicle.getScriptName()
                            + " at=" + fmt(vehicle.getX()) + "," + fmt(vehicle.getY()) + " auth=" + vehicle.netPlayerAuthorization
                            + " driver=" + (vehicle.getDriver() == null ? "none" : "other"), requestLines);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void endOnRelease(BaseVehicle vehicle) {
        Episode ep = open.remove(vehicle.getId());
        if (ep != null) {
            close(ep);
        }
    }

    private static boolean onConnection(UdpConnection connection, IsoPlayer player) {
        for (IsoPlayer p : connection.players) {
            if (p == player) {
                return true;
            }
        }
        return false;
    }

    /** 申請類每個帳號 {@link #REQ_LOG_MS} 最多一行（重連換連線也一樣）。 */
    private static boolean mayLogRequest(String user, long now) {
        Long last = reqLogged.get(user);
        if (last != null && now - last < REQ_LOG_MS) {
            return false;
        }
        reqLogged.put(user, now);
        return true;
    }

    private static String earlierText(ArrayList<String> users) {
        StringBuilder b = new StringBuilder();
        for (String u : users) {
            b.append(b.length() == 0 ? "" : ",").append(clean(u));
        }
        return b.toString();
    }

    static boolean budget(HashMap<String, Integer> lines, String user, String what) {
        Integer n = lines.get(user);
        if (n != null && n >= USER_CAP) {
            userCapped++;
            return false;
        }
        int next = n == null ? 1 : n + 1;
        lines.put(user, next);
        if (next == USER_CAP) {
            log("user=\"" + clean(user) + "\" 的" + what + "明細已達 " + USER_CAP + " 行，之後只計數（心跳 userCapped）");
        }
        return true;
    }

    private static void beat(long now) {
        if (announced && now - lastBeatMs < BEAT_MS) {
            return;
        }
        lastBeatMs = now;
        String head = announced ? "beat " : "首次生效（-Dmdc.unseatedVehicleWatch=0|off 照原版）minTiles=" + fmt(MIN_TILES) + " ";
        announced = true;
        log(head + "accepted=" + accepted + " episodes=" + episodes + " towed=" + towed + " pushed=" + pushed
                + " unseated=" + unseated + " noPlayer=" + noPlayer + " moving=" + movingNow + " logged=" + logged + " requests=" + requestsSeen
                + " suspect=" + requestsSuspect + " releaseToPlayer=" + releasesToPlayer + " open=" + open.size()
                + " userCapped=" + userCapped + " anomalies=" + anomalies);
    }

    /** 心跳與首次生效行，不限行數。 */
    private static void log(String text) {
        try {
            DebugLog.log(TAG + text);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 明細行：呼叫端先確認 count 小於 {@link #LOG_CAP}；第 LOG_CAP 行改印「之後只計數」。回傳新的行數。 */
    private static int log(String text, int count) {
        count++;
        log(count == LOG_CAP ? "已印 " + LOG_CAP + " 行（" + text.substring(0, text.indexOf(' ')) + " 這一類），之後只計數" : text);
        return count;
    }

    /** 帳號照原版只擋部分符號，控制字元（換行）會把一行拆成兩行：換成 ?；逗號是 earlierUsers 的分隔，也換。 */
    static String clean(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == '"' || c == ',') {
                if (b == null) {
                    b = new StringBuilder(s);
                }
                b.setCharAt(i, '?');
            }
        }
        return b == null ? s : b.toString();
    }

    static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
