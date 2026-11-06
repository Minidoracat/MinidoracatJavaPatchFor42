package zombie.mdc;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.WeakHashMap;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkCharacterAI;
import zombie.characters.NetworkPlayerAI;
import zombie.characters.Role;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.iso.Vector2;
import zombie.network.GameServer;
import zombie.network.ServerOptions;
import zombie.network.anticheats.AntiCheat;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.PlayerPacket;
import zombie.vehicles.BaseVehicle;

/**
 * W96 步行瞬移觀測與踢人（docs/patches.md 2de）。
 *
 * <p><b>原版</b>：{@code AntiCheatSpeed.validate} 每收到一包 {@code PlayerPacketReliable／Unreliable} 先呼叫
 * {@code PlayerPacket.resetMovable()}，它把封包位置交給玩家的 {@code NetworkCharacterAI.SpeedChecker.set}。{@code set} 每秒最多取樣一次：
 * 速度＝上一個取樣點到這一包位置的距離 × 1000 ÷ 兩次取樣相隔毫秒；兩次取樣之間的封包沿用上一次的速度。{@code reset()}（玩家物件建立、
 * {@code GameServer.sendTeleport}）之後 15 秒不取樣，第一次取樣只記位置（基準點）。步行上限 20 格／秒，被車撞（{@code hitVehicle}）或
 * 坐在車上改用 {@code SpeedLimit}。超過只依 {@code AntiCheatSpeed} 選項記 log／踢／封，而且逐包累計 counter：一次瞬移後一秒內的每一包都算，
 * 正式服的 3（只記 log）擋不住瞬移，改成 2（踢）又會踢到登入後的位置修正與網路延遲後的追趕。
 *
 * <p><b>手術</b>：{@code AntiCheatSpeed.validate(connection, packet)} 頭部 {@link #begin} 記下送出這包的連線與封包（全 jar 只有它呼叫
 * {@code resetMovable}）；{@code PlayerPacket.resetMovable()} 唯一的 {@code SpeedChecker.set} 同形改道 {@link #set}：
 * 先讀取樣器的上一個取樣點、時間與是否在車上，原樣呼叫一次原版 {@code set}，有新取樣才判斷。{@code GameServer.sendTeleport} 唯一的
 * {@code resetSpeedLimiter()} 改道 {@link #resetSpeedLimiter}，原樣呼叫後記下伺服器傳送的時間。只看步行封包：
 * {@code VehiclePhysicsPacket} 自己的 {@code set} 不經過這裡。
 *
 * <p><b>算在誰頭上</b>：權限、踢人、log 的帳號一律用送出這包的連線（原版 {@code AntiCheatSpeed} 也是看 {@code connection.getRole()}）。
 * 封包裡的玩家不是這條連線自己的玩家時（{@code foreign}），這次取樣不算在那位玩家頭上，也不當他的起點：記一行 {@code foreign}
 * （帶送出的連線與那位玩家）；速度達 {@link #LIMIT} 時 {@code action=} 照最後一步處置送出的連線（不看車輛與寬限期：正常客戶端只送自己的玩家）。
 * 那位玩家自己的下一次取樣改從他上一次自己的取樣點與時間算速度；在那之前沒有自己的取樣點（基準點是別人送的）就只記起點、不判。
 * 沒經過 {@link #begin}（不知道送出的連線）的取樣不判，計 anomalies。
 *
 * <p><b>判斷</b>（只看速度超過 {@link #LOG_FLOOR} 的取樣，依序）：
 * <ol>
 * <li>送出的連線有原版免檢的傳送／快速移動權限（TeleportToPlayer、TeleportToCoordinates、TeleportPlayerToAnotherPlayer、UseFastMoveCheat）：只計數。
 * <li>這一包在車上，或上一個取樣點是車上的取樣（下車那一次），而且速度不超過原版的車輛上限 {@code SpeedLimit}：{@code exempt(vehicle)}
 * （原版下車那一次比的是步行上限 20：取樣器先換成這一包的步行狀態；本刀放寬到車輛上限）。超過就往下判（下車後立刻瞬移不能靠這條過）。
 * <li>被車撞而原版改用車輛上限：{@code exempt(hitVehicle)}，不看速度（正式服有被出錯的車撞飛 856 格／秒的紀錄）。
 * <li>速度低於 {@link #LIMIT}：{@code below}。
 * <li>在寬限期內：原版重設後的基準點、登入（{@code setFullyConnected}）或伺服器傳送後 15 秒（原版不取樣）＋{@link #GRACE_MS} 內，
 * 而且是這次重設後第一次達門檻的跳動：{@code exempt(grace)}。登入後的位置修正只跳一次；寬限期內第二次跳動照樣判
 * （重連或被伺服器傳送後只能白跳一次）。
 * <li>其餘：observe 記 {@code would-kick}；kick 以原版 {@code AntiCheat.doKickUser} 踢送出的連線並記 {@code kick}
 * （連線沒有完全連上時記 {@code would-kick(notConnected)}；角色有 CantBeKickedByAnticheat 時記 {@code would-kick(cantBeKicked)}，
 * 兩者原版 {@code AntiCheat.act} 同樣不踢）。
 * </ol>
 * 每次取樣最多一行（原版逐包記）。踢人行一律印；其他行每次啟動最多 {@link #LOG_CAP} 行。速度達門檻的跳動，若 {@link #PAIR_MS} 內落回
 * 上一個還沒配對的起跳點附近（半徑＝起跳距離的 10%，至少 {@link #PAIR_MIN_TILES} 格），那一行標 {@code back=#<起跳那行>/<秒>}：
 * 客戶端回報假位置時常是「跳到遠處、幾秒內又跳回原地」，兩下都會很大，成對標出來方便人看。
 *
 * <p>開關 {@code -Dmdc.teleportWatch}：{@code 2|observe}（預設，未知值也是）只記 log；{@code 1|kick|on} 踢人；{@code 0|off} 照原版。
 * {@code -Dmdc.teleportWatchSpeed}（格／秒，預設 150，須大於 20）、{@code -Dmdc.teleportWatchGraceSec}（預設 60）。都要重啟。
 * 依賴 {@code AntiCheatSpeed} 不是 4（停用時原版不呼叫 {@code resetMovable}，本刀不會有任何輸出）。
 *
 * <p>封包與 RCON 指令都在主執行緒處理（{@code GameServer.mainLoopDealWithNetData}、{@code RCONServer.toMain}），狀態不加鎖。
 * 原版 {@code set}／{@code resetSpeedLimiter} 在 try 外恰呼叫一次；判斷與 log 只 catch {@code RuntimeException|LinkageError} 計 anomalies，
 * Error 照常外拋。反射欄位找不到時本次啟動整刀照原版。
 */
public final class TeleportWatch {
    static final int OFF = 0;
    static final int KICK = 1;
    static final int OBSERVE = 2;

    static final int MODE = parseMode(System.getProperty("mdc.teleportWatch"));
    static final float LIMIT = parseLimit(System.getProperty("mdc.teleportWatchSpeed"));
    static final long GRACE_MS = parseGraceSec(System.getProperty("mdc.teleportWatchGraceSec")) * 1000L;
    /** 原版步行上限（{@code AntiCheatSpeed.MAX_SPEED}）：不超過它的取樣原版也放行，不看。 */
    static final float LOG_FLOOR = 20.0F;
    static final int LOG_CAP = 2000;
    static final long BEAT_MS = 600_000L;
    static final long PAIR_MS = 60_000L;
    static final float PAIR_MIN_TILES = 50.0F;
    static final String ISSUED_BY = "MdcTeleportWatch";
    private static final String TAG = "[MinidoracatJavaPatch][TeleportWatch] ";

    private static final Field POSITION;
    private static final Field IN_VEHICLE;
    private static final Field LAST_TIME;
    private static final Field SPEED;
    private static final boolean REFLECT_OK;

    static {
        Field[] f = new Field[4];
        boolean ok;
        try {
            Class<?> c = NetworkCharacterAI.SpeedChecker.class;
            String[] names = {"position", "isInVehicle", "lastTime", "speed"};
            for (int i = 0; i < names.length; i++) {
                f[i] = c.getDeclaredField(names[i]);
                f[i].setAccessible(true);
            }
            ok = f[0].getType() == Vector2.class && f[1].getType() == boolean.class
                    && f[2].getType() == long.class && f[3].getType() == float.class;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ok = false;
        }
        POSITION = f[0];
        IN_VEHICLE = f[1];
        LAST_TIME = f[2];
        SPEED = f[3];
        REFLECT_OK = ok;
    }

    /** 每個取樣器（＝每個玩家物件）一份；以取樣器身分當鍵，玩家物件回收就跟著消失。 */
    static final class State {
        long spawnMs;
        long baselineMs;
        long lastPacketMs;
        long maxGapMs;
        long serverTeleportMs;
        long vehicleSampleMs;
        /** 這次重設後的寬限期已經豁免過一次跳動（新取樣器、伺服器傳送時清掉；客戶端送的座標清不掉）。 */
        boolean graceUsed;
        /** 取樣器剛建立或伺服器剛傳送：下一次起點 (0,0) 的取樣是原版的基準點。其餘時候起點 (0,0) 是客戶端自己送的位置，照常判斷。 */
        boolean awaitingBaseline = true;
        /** 他自己上一次取樣的位置與時間（0＝還沒有）；別條連線送的取樣不動它。 */
        float ownX;
        float ownY;
        long ownMs;
        /** 上一次自己的取樣之後有過別條連線送的取樣：下一次自己的取樣改從 own 算速度。 */
        boolean foreignSince;
        /** 還沒配對的起跳：起點、距離、時間（0＝沒有）與那一行的編號（0＝沒印）。 */
        float outX;
        float outY;
        float outDist;
        long outMs;
        long outLine;
    }

    private static final WeakHashMap<NetworkCharacterAI.SpeedChecker, State> STATES = new WeakHashMap<>();
    private static PlayerPacket current;
    private static UdpConnection sender;
    private static boolean announced;
    private static long lines;
    private static long samples;
    private static long wouldKick;
    private static long kicked;
    private static long exempt;
    private static long adminSkipped;
    private static long foreign;
    private static long anomalies;
    private static long lastBeatMs;
    private static long beatSamples;

    private TeleportWatch() {
    }

    static int parseMode(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "0", "off" -> OFF;
            case "1", "kick", "on", "enforce" -> KICK;
            default -> OBSERVE;
        };
    }

    static float parseLimit(String raw) {
        try {
            float v = raw == null ? 150.0F : Float.parseFloat(raw.trim());
            return v > LOG_FLOOR && Float.isFinite(v) ? v : 150.0F;
        } catch (NumberFormatException e) {
            return 150.0F;
        }
    }

    static long parseGraceSec(String raw) {
        try {
            long v = raw == null ? 60L : Long.parseLong(raw.trim());
            return v >= 0L ? v : 60L;
        } catch (NumberFormatException e) {
            return 60L;
        }
    }

    /**
     * {@code AntiCheatSpeed.validate(connection, packet)} 頭部：記下送出這包的連線與步行封包（主執行緒；全 jar 只有 validate 呼叫
     * {@code resetMovable}），{@link #set} 取用即清。車輛封包記 null。
     */
    public static void begin(UdpConnection connection, INetworkPacket packet) {
        sender = connection;
        current = packet instanceof PlayerPacket p ? p : null;
    }

    /** {@code PlayerPacket.resetMovable()} 內 {@code SpeedChecker.set} 的改道目標。 */
    public static void set(NetworkCharacterAI.SpeedChecker sc, float x, float y, boolean inVehicle, BaseVehicle hitVehicle) {
        PlayerPacket packet = current;
        UdpConnection from = sender;
        current = null;
        sender = null;
        if (MODE == OFF) {
            sc.set(x, y, inVehicle, hitVehicle);
            return;
        }
        if (!announced) {
            announce();
        }
        if (!REFLECT_OK) {
            sc.set(x, y, inVehicle, hitVehicle);
            return;
        }
        State st = null;
        long t0 = 0L;
        float px = 0.0F;
        float py = 0.0F;
        boolean wasVehicle = false;
        long now = System.currentTimeMillis();
        try {
            Vector2 pos = (Vector2) POSITION.get(sc);
            px = pos.x;
            py = pos.y;
            t0 = LAST_TIME.getLong(sc);
            wasVehicle = IN_VEHICLE.getBoolean(sc);
            st = STATES.get(sc);
            if (st == null) {
                st = new State();
                st.spawnMs = now;
                STATES.put(sc, st);
            } else if (st.lastPacketMs != 0L && now - st.lastPacketMs > st.maxGapMs) {
                st.maxGapMs = now - st.lastPacketMs;
            }
            st.lastPacketMs = now;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            anomalies++;
            st = null;
        }
        sc.set(x, y, inVehicle, hitVehicle);
        if (st == null) {
            return;
        }
        try {
            long t1 = LAST_TIME.getLong(sc);
            Vector2 now1 = (Vector2) POSITION.get(sc);
            // 原版取樣才會改 lastTime 與 position；基準點與重設可能落在同一毫秒，所以兩者任一改變都算有取樣。
            if (t1 == t0 && now1.x == px && now1.y == py) {
                return;
            }
            // 封包裡的玩家不是送出這包的連線自己的（foreign）：不算在那位玩家頭上，也不當他的起點。
            boolean isForeign = from != null && packet != null && !owns(from, packet.getPlayer());
            boolean baseline = st.awaitingBaseline && px == 0.0F && py == 0.0F;
            st.awaitingBaseline = false;
            if (baseline) {
                st.baselineMs = t1;
                st.maxGapMs = 0L;
                st.ownX = now1.x;
                st.ownY = now1.y;
                st.ownMs = isForeign ? 0L : t1;
                st.foreignSince = isForeign;
                return;
            }
            long gap = st.maxGapMs;
            st.maxGapMs = 0L;
            if (wasVehicle) {
                st.vehicleSampleMs = t0;
            }
            samples++;
            float fromX = px;
            float fromY = py;
            long dtMs = t1 - t0;
            // 原版起點長度 0（不是重設後的基準點，是客戶端自己送的 (0,0)）時不重算速度、沿用上一次：自己算
            float speed = px == 0.0F && py == 0.0F
                    ? (dtMs <= 0L ? 0.0F : (float) (Math.hypot(x, y) * 1000.0 / dtMs))
                    : SPEED.getFloat(sc);
            if (isForeign) {
                st.foreignSince = true;
            } else {
                if (st.foreignSince) {
                    // 上次自己的取樣之後有別人送的取樣：原版的起點是別人送的位置，改從自己上一次的取樣點與時間算；沒有就只記起點
                    st.foreignSince = false;
                    fromX = st.ownX;
                    fromY = st.ownY;
                    dtMs = t1 - st.ownMs;
                    speed = st.ownMs == 0L || dtMs <= 0L ? 0.0F : (float) (Math.hypot(x - fromX, y - fromY) * 1000.0 / dtMs);
                }
                st.ownX = now1.x;
                st.ownY = now1.y;
                st.ownMs = t1;
            }
            if (speed > LOG_FLOOR && packet != null) {
                if (from == null) {
                    anomalies++;
                } else {
                    judge(packet, from, isForeign, sc, st, speed, fromX, fromY, x, y, dtMs, gap, inVehicle, wasVehicle, t1);
                }
            }
            beat(t1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /**
     * {@code GameServer.sendTeleport} 內 {@code resetSpeedLimiter()} 的改道目標：原樣重設，再記下伺服器傳送的時間（寬限期與 log 的 {@code tp=}；
     * 玩家一直坐車、還沒有步行取樣時也建一份狀態，免得基準點由車輛封包取走時漏掉寬限期）。
     */
    public static void resetSpeedLimiter(NetworkPlayerAI ai) {
        ai.resetSpeedLimiter();
        if (MODE == OFF || !REFLECT_OK) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            State st = STATES.get(ai.speedChecker);
            if (st == null) {
                st = new State();
                st.spawnMs = now;
                STATES.put(ai.speedChecker, st);
            }
            st.serverTeleportMs = now;
            st.graceUsed = false;
            st.awaitingBaseline = true;
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** {@code player} 是不是 {@code c} 自己的玩家（同一個物件；原版 {@code AntiCheatPlayer} 用同一份 {@code players} 陣列比 ID）。 */
    static boolean owns(UdpConnection c, IsoPlayer player) {
        for (IsoPlayer p : c.players) {
            if (p != null && p == player) {
                return true;
            }
        }
        return false;
    }

    /**
     * 寬限期：原版重設後的基準點、登入（{@code setFullyConnected}）或伺服器傳送後。後兩者從重設算，多加原版不取樣的 15 秒；
     * 基準點由車輛封包取走（重設時坐在車上）時靠它們補上。
     */
    static boolean inGrace(long now, long baselineMs, long loginMs, long serverTeleportMs, long graceMs) {
        return baselineMs != 0L && now - baselineMs < graceMs
                || loginMs > 0L && now - loginMs < 15_000L + graceMs
                || serverTeleportMs != 0L && now - serverTeleportMs < 15_000L + graceMs;
    }

    /** 判斷結果：{@code null}＝有原版免檢權限（只計數、不記 log）。{@code vehicleLimit} 是原版的車輛上限 {@code SpeedLimit}。 */
    static String verdict(boolean admin, boolean inVehicle, boolean wasVehicle, boolean hitVehicle, boolean graceOpen,
                          float speed, float limit, float vehicleLimit) {
        if (admin) {
            return null;
        }
        if ((inVehicle || wasVehicle) && speed <= vehicleLimit) {
            return "exempt(vehicle)";
        }
        if (hitVehicle) {
            return "exempt(hitVehicle)";
        }
        if (speed < limit) {
            return "below";
        }
        return graceOpen ? "exempt(grace)" : "would-kick";
    }

    /** foreign 取樣對送出的連線的處置：{@code null}＝有原版免檢權限；{@code -}＝門檻以下只記；{@code would-kick}＝達門檻（不看車輛與寬限期）。 */
    static String foreignAction(boolean admin, float speed, float limit) {
        if (admin) {
            return null;
        }
        return speed < limit ? "-" : "would-kick";
    }

    /** kick 模式對 would-kick 的處置：原版 {@code AntiCheat.act} 只踢完全連上、沒有 CantBeKickedByAnticheat 的連線。 */
    static String kickDecision(boolean fullyConnected, boolean cantBeKicked) {
        if (!fullyConnected) {
            return "would-kick(notConnected)";
        }
        return cantBeKicked ? "would-kick(cantBeKicked)" : "kick";
    }

    /** 原版 {@code AntiCheatSpeed} 免檢的傳送／快速移動權限。 */
    private static boolean exemptRole(Role role) {
        return role != null && (role.hasCapability(Capability.TeleportToPlayer)
                || role.hasCapability(Capability.TeleportToCoordinates)
                || role.hasCapability(Capability.TeleportPlayerToAnotherPlayer)
                || role.hasCapability(Capability.UseFastMoveCheat));
    }

    /** 權限、踢人、log 的帳號都用送出這包的連線 {@code conn}（原版 {@code AntiCheatSpeed} 看 {@code connection.getRole()}）。 */
    private static void judge(PlayerPacket packet, UdpConnection conn, boolean isForeign,
                              NetworkCharacterAI.SpeedChecker sc, State st, float speed, float px, float py, float x, float y,
                              long dtMs, long gapMs, boolean inVehicle, boolean wasVehicle, long now) {
        IsoPlayer player = packet.getPlayer();
        Role role = conn.getRole();
        boolean cantBeKicked = role != null && role.hasCapability(Capability.CantBeKickedByAnticheat);
        double dist = Math.hypot(x - px, y - py);
        String text = String.format(Locale.ROOT,
                "speed=%.1f dist=%.1f dtMs=%d from=%.1f,%.1f to=%.1f,%.1f packet=%s",
                speed, dist, dtMs, px, py, x, y, packet.getClass().getSimpleName());
        String steamId = SteamUtils.convertSteamIDToString(conn.getSteamId());
        if (isForeign) {
            String action = foreignAction(exemptRole(role), speed, LIMIT);
            if (action == null) {
                adminSkipped++;
                return;
            }
            foreign++;
            boolean kickNow = false;
            if (!"-".equals(action) && MODE == KICK) {
                action = kickDecision(conn.isFullyConnected(), cantBeKicked);
                kickNow = "kick".equals(action);
            }
            if (kickNow || lines < LOG_CAP) {
                lines++;
                try {
                    DebugLog.log(TAG + "foreign sender=\"" + conn.getUserName() + "\" steamid=" + steamId
                            + " player=\"" + player.getUsername() + "\" " + text + " action=" + action
                            + " mode=" + modeName() + " limit=" + LIMIT + " (#" + lines + ")");
                } catch (RuntimeException | LinkageError e) {
                    anomalies++;
                }
            }
            if (kickNow) {
                kicked++;
                AntiCheat.doKickUser(conn, ISSUED_BY, "foreign " + text);
            }
            return;
        }
        boolean hitVehicle = !inVehicle && sc.isVehicle();
        long loginMs = conn.connectionTimestamp - 15_000L;
        boolean graceOpen = !st.graceUsed && inGrace(now, st.baselineMs, loginMs, st.serverTeleportMs, GRACE_MS);
        String v = verdict(exemptRole(role), inVehicle, wasVehicle, hitVehicle, graceOpen, speed, LIMIT,
                (float) ServerOptions.instance.speedLimit.getValue());
        if (v == null) {
            adminSkipped++;
            return;
        }
        boolean hit = "would-kick".equals(v);
        if (hit) {
            wouldKick++;
        } else if (v.startsWith("exempt")) {
            exempt++;
            st.graceUsed |= "exempt(grace)".equals(v);
        }
        boolean kickNow = false;
        if (hit && MODE == KICK) {
            v = kickDecision(conn.isFullyConnected(), cantBeKicked);
            kickNow = "kick".equals(v);
        }
        boolean print = kickNow || lines < LOG_CAP;
        String back = "-";
        if (speed >= LIMIT) {
            boolean pending = st.outMs != 0L && now - st.outMs <= PAIR_MS;
            if (pending && Math.hypot(x - st.outX, y - st.outY) <= Math.max(PAIR_MIN_TILES, st.outDist * 0.1F)) {
                back = "#" + st.outLine + "/" + secs(now - st.outMs);
                st.outMs = 0L;
            } else if (!pending) {
                st.outX = px;
                st.outY = py;
                st.outDist = (float) dist;
                st.outMs = now;
                st.outLine = print ? lines + 1L : 0L;
            }
        }
        if (print) {
            lines++;
            try {
                DebugLog.log(TAG + v + " user=\"" + player.getUsername() + "\" steamid=" + steamId + " " + text
                        + " login=" + secs(now - loginMs)
                        + " spawn=" + secs(now - st.spawnMs)
                        + " base=" + (st.baselineMs != 0L ? secs(now - st.baselineMs) : "?")
                        + " tp=" + (st.serverTeleportMs != 0L ? secs(now - st.serverTeleportMs) : "-")
                        + " veh=" + (st.vehicleSampleMs != 0L ? secs(now - st.vehicleSampleMs) : "-")
                        + " back=" + back
                        + " gapMs=" + gapMs + " ping=" + conn.getLastPing()
                        + " mode=" + modeName() + " limit=" + LIMIT + " (#" + lines + ")");
                if (lines == LOG_CAP) {
                    DebugLog.log(TAG + "已印 " + LOG_CAP + " 行，之後只印 kick 行");
                }
            } catch (RuntimeException | LinkageError e) {
                anomalies++;
            }
        }
        if (kickNow) {
            kicked++;
            AntiCheat.doKickUser(conn, ISSUED_BY, "teleport " + text);
        }
    }

    private static String secs(long ms) {
        return ms / 1000L + "s";
    }

    static String modeName() {
        return MODE == KICK ? "kick" : MODE == OBSERVE ? "observe" : "off";
    }

    private static void announce() {
        announced = true;
        try {
            DebugLog.log(TAG + "首次生效（-Dmdc.teleportWatch=0|off 照原版、1|kick 踢人）mode=" + modeName() + " limit=" + LIMIT
                    + " graceSec=" + GRACE_MS / 1000L + " reflect=" + (REFLECT_OK ? "ok" : "missing"));
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 每 {@link #BEAT_MS} 一行累計（有新取樣才印；第一個取樣只起算）。 */
    private static void beat(long now) {
        if (lastBeatMs == 0L) {
            lastBeatMs = now;
            return;
        }
        if (now - lastBeatMs < BEAT_MS || samples == beatSamples) {
            return;
        }
        lastBeatMs = now;
        beatSamples = samples;
        try {
            DebugLog.log(TAG + "beat samples=" + samples + " wouldKick=" + wouldKick + " kicked=" + kicked + " exempt=" + exempt
                    + " adminSkipped=" + adminSkipped + " foreign=" + foreign + " lines=" + lines + " players=" + STATES.size() + " anomalies=" + anomalies
                    + " mode=" + modeName());
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    // ---- 測試存取器 ----

    static State stateForTest(NetworkCharacterAI.SpeedChecker sc) {
        return STATES.get(sc);
    }

    static long[] countersForTest() {
        return new long[]{samples, wouldKick, kicked, exempt, adminSkipped, anomalies, lines, foreign};
    }
}
