package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkCharacterAI;
import zombie.characters.NetworkPlayerAI;
import zombie.characters.Role;
import zombie.characters.component.NetworkPlayerComponent;
import zombie.core.raknet.UdpConnection;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.characters.ecs.ECSComponent;
import zombie.iso.Vector2;
import zombie.network.anticheats.AntiCheatSpeed;
import zombie.network.packets.character.PlayerPacket;
import zombie.network.packets.character.PlayerPacketReliable;

/**
 * W96 步行瞬移觀測（TeleportWatch）行為驗證：跑真的 {@code PlayerPacket.resetMovable()}（observe／kick／off 是 dist 手術後，
 * vanilla 是 jar 原版，classpath 不含 dist），玩家是配置出來的 {@code IsoPlayer}，取樣器是真的 {@code SpeedChecker}。
 *
 * <p>取樣時間用反射控制（{@code updateLimit.last}、{@code lastTime}），不睡眠：{@link #arm} 讓下一包取樣、相隔約 dt 毫秒；
 * {@link #quiet} 讓下一包不取樣。每一步同時用同樣的位置與時間直接呼叫另一個取樣器的原版 {@code set}（雙胞胎），比對位置、
 * 是否在車上與速度，證明改道不改原版取樣結果。
 *
 * <p>回歸表（2026-11-06 正式服 6 個帳號，「距登入秒數」換成距基準點＝登入後 15 秒）：Player-O（外掛）6174.87／591.90 要 would-kick、
 * 149.71 是 below；Player-P 2808（27 s）、Player-Q 563（16 s）要 exempt(grace)；Player-R 12048 在 74 s 是 exempt(grace)、199 s／613 s
 * would-kick；Player-S 2974 在 233 s／594 s would-kick；Player-T 856 被車撞（原版 limit=150）用判斷函式驗 exempt(hitVehicle)。
 * 補充兩筆（正式服的位移與時間，座標平移成假的）：Player-R 10-04 的「跳到遠處、幾秒內又跳回原地」要 would-kick 並成對標
 * {@code back=}；Player-U 09-26 上線那一秒的來回（約 4,557 格、又跳回）在原版重設後 15 秒內，不取樣、不記。
 * <p>身分：每位玩家有自己的配置連線（擁有他、沒完全連上，kick 模式因此記 {@code would-kick(notConnected)}；真的踢人在 E2E kick 輪驗），
 * 權限與登入時間都看送出這包的連線。另一條連線送了這位玩家的封包（走真的 {@code AntiCheatSpeed.validate}，證明頭部掛點有接上）時記
 * {@code foreign}、不算在他頭上、也不當他的起點；沒經過 {@code begin} 的取樣不判。
 * argv：{@code observe}／{@code kick}／{@code off}／{@code vanilla}，自驗旗標。
 */
public final class TeleportWatchTest {
    private static final PrintStream OUT = System.out;
    private static int failed;

    private static Field limitLast;
    private static Field scUpdateLimit;
    private static Field scLastTime;
    private static Field scPosition;
    private static Field scInVehicle;
    private static Field scSpeed;
    private static String modeArg;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();   // DebugLog 第一次寫行時經 GameClient → ServerOptions 呼叫 Rand
        String mode = args[0];
        modeArg = mode;
        Class<?> sc = NetworkCharacterAI.SpeedChecker.class;
        scUpdateLimit = field(sc, "updateLimit");
        scLastTime = field(sc, "lastTime");
        scPosition = field(sc, "position");
        scInVehicle = field(sc, "isInVehicle");
        scSpeed = field(sc, "speed");
        limitLast = field(UpdateLimit.class, "last");
        boolean fromJar = PlayerPacket.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar");
        boolean acsFromJar = AntiCheatSpeed.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar");
        expect("自驗：PlayerPacket 與 AntiCheatSpeed 來源（vanilla＝jar、其餘＝dist）", fromJar == "vanilla".equals(mode) && acsFromJar == fromJar);
        if (!"vanilla".equals(mode)) {
            int want = switch (mode) {
                case "kick" -> TeleportWatch.KICK;
                case "off" -> TeleportWatch.OFF;
                default -> TeleportWatch.OBSERVE;
            };
            expect("自驗：argv=" + mode + " 與 -Dmdc.teleportWatch=" + System.getProperty("mdc.teleportWatch") + " 相符",
                    TeleportWatch.MODE == want);
            expect("parseMode：null／亂寫→observe、0/off→off、1/kick/on/enforce→kick",
                    TeleportWatch.parseMode(null) == TeleportWatch.OBSERVE && TeleportWatch.parseMode("x") == TeleportWatch.OBSERVE
                            && TeleportWatch.parseMode(" OFF ") == TeleportWatch.OFF && TeleportWatch.parseMode("0") == TeleportWatch.OFF
                            && TeleportWatch.parseMode("1") == TeleportWatch.KICK && TeleportWatch.parseMode("Kick") == TeleportWatch.KICK
                            && TeleportWatch.parseMode("on") == TeleportWatch.KICK && TeleportWatch.parseMode("enforce") == TeleportWatch.KICK);
            expect("parseLimit：預設 150、≤20／NaN／亂寫回 150、300 照用；parseGraceSec：預設 60、負數／亂寫回 60、0 照用",
                    TeleportWatch.parseLimit(null) == 150F && TeleportWatch.parseLimit("20") == 150F
                            && TeleportWatch.parseLimit("NaN") == 150F && TeleportWatch.parseLimit("abc") == 150F
                            && TeleportWatch.parseLimit("300") == 300F && TeleportWatch.parseGraceSec(null) == 60L
                            && TeleportWatch.parseGraceSec("-1") == 60L && TeleportWatch.parseGraceSec("x") == 60L
                            && TeleportWatch.parseGraceSec("0") == 0L);
            expect("出貨預設：limit=150、grace=60 s",
                    TeleportWatch.LIMIT == 150F && TeleportWatch.GRACE_MS == 60_000L);
            verdictTable();
        }

        ByteArrayOutputStream log = captureLog();
        Rig rig = new Rig();
        Rig twin = new Rig();

        // 1. 玩家物件剛建立：原版 15 秒不取樣（Player-U 上線那一秒的來回也在這裡：不記）
        step(rig, twin, 3000F, 3000F, -1, false);
        step(rig, twin, 4865F, 7158F, -1, false);
        step(rig, twin, 3000F, 3000F, -1, false);
        step(rig, twin, 10850F, 9950F, -1, false);
        step(rig, twin, 10851F, 9950F, -1, false);
        // 2. 基準點（原版重設後第一次取樣只記位置）
        step(rig, twin, 10852F, 9950F, 15000, false);
        expect("基準點：兩邊位置都是 10852,9950、速度 0", pos(rig.sc).x == 10852F && speed(rig.sc) == 0F
                && pos(twin.sc).x == 10852F);
        // 3. 寬限期內 600 格
        step(rig, twin, 11452F, 9950F, 1000, false);
        expect("寬限期內 600 格：兩邊速度約 600（原版照算）", near(speed(rig.sc), 600F) && near(speed(twin.sc), 600F));
        if (on(mode)) {
            expect("寬限期內 600 格有 state、基準點已記", TeleportWatch.stateForTest(rig.sc) != null
                    && TeleportWatch.stateForTest(rig.sc).baselineMs != 0L);
            shiftBaseline(rig, 61_000L);
        }
        // 4. 寬限期後正常走路（5 格／秒）、20 格／秒以下不記
        step(rig, twin, 11457F, 9950F, 1000, false);
        step(rig, twin, 11472F, 9950F, 1000, false);
        // 5. 回歸：Player-O
        regress(rig, twin, mode, 378, 6174.87F);
        regress(rig, twin, mode, 419, 149.71F);
        regress(rig, twin, mode, 942, 591.90F);
        // 6. 25 格／秒（below）
        step(rig, twin, pos(rig.sc).x + 25F, 9950F, 1000, false);
        // 7. 下車那一次：上一個取樣點在車上（VehiclePhysicsPacket 的 set 設的），步行第一次取樣 60 格（不超過原版車輛上限 SpeedLimit＝70）；
        //    再一次下車後立刻跳 6000 格：超過車輛上限，不靠下車豁免，照門檻判
        scInVehicle.setBoolean(rig.sc, true);
        scInVehicle.setBoolean(twin.sc, true);
        step(rig, twin, pos(rig.sc).x + 60F, 9950F, 1000, false);
        expect("下車那一次之後取樣器回到步行（isInVehicle=false）", !scInVehicle.getBoolean(rig.sc) && !scInVehicle.getBoolean(twin.sc));
        scInVehicle.setBoolean(rig.sc, true);
        scInVehicle.setBoolean(twin.sc, true);
        step(rig, twin, pos(rig.sc).x + 6000F, 9950F, 1000, false);
        // 8. 其餘回歸：Player-P、Player-Q、Player-R、Player-S
        regress(rig, twin, mode, 27, 2808F);
        regress(rig, twin, mode, 16, 563F);
        regress(rig, twin, mode, 74, 12048F);
        regress(rig, twin, mode, 199, 12048F);
        regress(rig, twin, mode, 613, 12048F);
        regress(rig, twin, mode, 233, 2974F);
        regress(rig, twin, mode, 594, 2974F);
        // 8b. Player-R 10-04 的來回（另一個玩家物件，距登入 613 s）：原地 → 遠處 → 遠處 → 原地附近 → 走回原地 → 遠處 → 原地
        Rig snow = new Rig();
        Rig snowTwin = new Rig();
        step(snow, snowTwin, 2000F, 5000F, -1, false);
        step(snow, snowTwin, 2000F, 5000F, 15000, false);
        if (on(mode)) {
            shiftBaseline(snow, 598_000L);
        }
        step(snow, snowTwin, 10779F, 2022F, 1000, false);
        step(snow, snowTwin, 10971F, 2150F, 1000, false);
        step(snow, snowTwin, 1947F, 5030F, 1000, false);
        step(snow, snowTwin, 1947F, 4966F, 6000, false);
        step(snow, snowTwin, 2000F, 5000F, 6000, false);
        step(snow, snowTwin, 10779F, 1958F, 1000, false);
        step(snow, snowTwin, 2000F, 5000F, 1000, false);
        // 9. 伺服器傳送（GameServer.sendTeleport 的 resetSpeedLimiter）：重設、15 秒後基準點、之後寬限期內的跳動
        if (!"vanilla".equals(mode)) {
            TeleportWatch.resetSpeedLimiter(rig.ai);
        } else {
            rig.ai.resetSpeedLimiter();
        }
        twin.ai.resetSpeedLimiter();
        expect("伺服器傳送後取樣器重設（位置 0,0）", pos(rig.sc).getLength() == 0F && pos(twin.sc).getLength() == 0F);
        step(rig, twin, 12000F, 9000F, -1, false);
        step(rig, twin, 12001F, 9000F, 15000, false);
        step(rig, twin, 12401F, 9000F, 1000, false);
        // 9b. 同一次重設內跳到 (0,0) 再跳開：客戶端送的 (0,0) 不是基準點、不重開寬限期（原版起點 (0,0) 不重算速度、沿用上一次），兩下都判
        step(rig, twin, 0F, 0F, 1000, false);
        step(rig, twin, 12401F, 9000F, 1000, false);
        // 9c. 還沒送過步行封包就被伺服器傳送（state 由傳送建立）：第一包的封包間隔不能從 1970 年算
        if (on(mode)) {
            Rig fresh = new Rig();
            Rig freshTwin = new Rig();
            TeleportWatch.resetSpeedLimiter(fresh.ai);
            freshTwin.ai.resetSpeedLimiter();
            step(fresh, freshTwin, 3000F, 3000F, -1, false);
            expect("state 由伺服器傳送建立：第一包不算封包間隔（maxGapMs=" + TeleportWatch.stateForTest(fresh.sc).maxGapMs + "）",
                    TeleportWatch.stateForTest(fresh.sc).maxGapMs == 0L);
        }
        // 10. 送出的連線有原版免檢權限：只計數、不記
        Role admin = new Role("mdc-test-admin");
        admin.addCapability(Capability.TeleportToCoordinates);
        setRole(rig.conn, admin);
        if (on(mode)) {
            shiftBaseline(rig, 61_000L);
        }
        step(rig, twin, pos(rig.sc).x + 5000F, 9000F, 1000, false);
        setRole(rig.conn, new Role("mdc-test-user"));
        // 11. 另一條連線（spoofer）送了這位玩家的封包（走真的 AntiCheatSpeed.validate，頭部記下送出的連線）：記 foreign、不算在他頭上，
        //     也不當他的起點；他自己的下一次取樣從他上一次自己的取樣點算
        Rig vic = new Rig();
        Rig vicTwin = new Rig();
        step(vic, vicTwin, 4000F, 4000F, -1, false);
        step(vic, vicTwin, 4000F, 4000F, 15000, false);
        if (on(mode)) {
            shiftBaseline(vic, 300_000L);
        }
        UdpConnection spoofer = connection("spoofer", alloc(IsoPlayer.class));
        // 11a. 假位置在遠處、他自己的下一包回原處：foreign 一行（達門檻，照最後一步處置 spoofer）；他自己 0 格不記
        String verdictOfVanilla = spoof(vic, vicTwin, spoofer, 8000F, 4000F);
        expect("foreign：原版 AntiCheatSpeed.validate 照樣回超速（" + verdictOfVanilla + "）、取樣與雙胞胎相同",
                verdictOfVanilla != null && verdictOfVanilla.startsWith("speed=") && pos(vic.sc).x == 8000F && pos(vicTwin.sc).x == 8000F
                        && near(speed(vic.sc), 4000F));
        step(vic, vicTwin, 4000F, 4000F, 1000, false);
        // 11b. 假位置在旁邊（20 格／秒以下，不記）、他自己接著跳 6000 格：從他自己的起點算（自己的兩包相隔 2 秒），照判
        ownAgo(vic, 2000L);
        spoof(vic, vicTwin, spoofer, 4005F, 4000F);
        step(vic, vicTwin, 10000F, 4000F, 1000, false);
        // 11c. 假位置跳到遠處、他自己就停在那裡：foreign 一行；他自己這一包從他自己的起點算 6000 格，照判
        ownAgo(vic, 2000L);
        spoof(vic, vicTwin, spoofer, 16000F, 4000F);
        step(vic, vicTwin, 16000F, 4000F, 1000, false);
        // 12. 慢慢走到 (0,0) 再跳開：原版起點長度 0 不重算速度（沿用上一次的約 10），本刀自己算，照判
        Rig zero = new Rig();
        Rig zeroTwin = new Rig();
        step(zero, zeroTwin, 10F, 0F, -1, false);
        step(zero, zeroTwin, 10F, 0F, 15000, false);
        if (on(mode)) {
            shiftBaseline(zero, 300_000L);
        }
        step(zero, zeroTwin, 0F, 0F, 1000, false);
        step(zero, zeroTwin, 6000F, 0F, 1000, false);
        expect("走到 (0,0) 再跳開：原版速度沿用上一次（約 10）", near(speed(zero.sc), 10F) && near(speed(zeroTwin.sc), 10F));
        // 13. 登入寬限看送出連線的 connectionTimestamp：基準點早就過了、30 秒前登入 → 第一次跳動 exempt(grace)
        Rig late = new Rig();
        Rig lateTwin = new Rig();
        step(late, lateTwin, 5000F, 5000F, -1, false);
        step(late, lateTwin, 5000F, 5000F, 15000, false);
        if (on(mode)) {
            shiftBaseline(late, 300_000L);
        }
        late.conn.connectionTimestamp = System.currentTimeMillis() + 15_000L - 30_000L;
        step(late, lateTwin, 8000F, 5000F, 1000, false);
        // 14. 沒經過 begin（不知道送出的連線）：不判、計 anomalies
        Rig anon = new Rig();
        Rig anonTwin = new Rig();
        anon.noSender = true;
        step(anon, anonTwin, 6000F, 6000F, -1, false);
        step(anon, anonTwin, 6000F, 6000F, 15000, false);
        if (on(mode)) {
            shiftBaseline(anon, 300_000L);
        }
        step(anon, anonTwin, 12000F, 6000F, 1000, false);

        String text = log.toString(StandardCharsets.UTF_8);
        int announce = count(text, "[TeleportWatch] 首次生效");
        int wouldKick = count(text, "[TeleportWatch] would-kick ");
        int notConn = count(text, "[TeleportWatch] would-kick(notConnected) ");
        int grace = count(text, "[TeleportWatch] exempt(grace) ");
        int vehicle = count(text, "[TeleportWatch] exempt(vehicle) ");
        int below = count(text, "[TeleportWatch] below ");
        int foreignLines = count(text, "[TeleportWatch] foreign ");
        if (on(mode)) {
            boolean kick = "kick".equals(mode);
            String wk = "would-kick(\\(notConnected\\))? user=\"tw\" ";
            expect("首次生效行恰一次、mode／limit／grace／reflect=ok", announce == 1 && text.contains("mode=" + mode)
                    && text.contains("limit=150.0 graceSec=60 reflect=ok"));
            // would-kick：Player-O 6174.87／591.90、下車後 6000、Player-R 199／613、Player-S 233／594＝7；Player-R 來回 5；同一次重設內進出 (0,0) 2；
            // foreign 之後自己的跳動 2；慢慢走到 (0,0) 再跳開 1
            expect((kick ? "kick：連線沒完全連上時記 would-kick(notConnected)" : "observe：would-kick")
                            + " 恰 17 行（Player-O 2、下車後 6000 格 1、Player-R 2＋來回 5、Player-S 2、進出 (0,0) 2、foreign 之後 2、走到 (0,0) 再跳開 1）",
                    (kick ? notConn : wouldKick) == 17 && (kick ? wouldKick : notConn) == 0
                            && Pattern.compile(wk + "[^\\n]* from=12401\\.0,9000\\.0 to=0\\.0,0\\.0 [^\\n]* tp=\\ds ").matcher(text).find()
                            && Pattern.compile(wk + "[^\\n]* from=0\\.0,0\\.0 to=12401\\.0,9000\\.0 [^\\n]* tp=\\ds ").matcher(text).find()
                            && Pattern.compile(wk + "steamid=0 speed=\\d+\\.\\d dist=6000\\.0 dtMs=\\d+ from=0\\.0,0\\.0 to=6000\\.0,0\\.0 ").matcher(text).find());
            // exempt(grace)：步驟 3、Player-P、Player-Q、Player-R 74 s、伺服器傳送後、30 秒前登入＝6（進出 (0,0) 不重開寬限期）
            expect("exempt(grace) 恰 6 行（登入後、Player-P、Player-Q、Player-R 74 s、伺服器傳送後、30 秒前登入 login=30s）", grace == 6
                    && Pattern.compile("exempt\\(grace\\) user=\"tw\" [^\\n]* to=8000\\.0,5000\\.0 [^\\n]* login=30s ").matcher(text).find());
            expect("exempt(vehicle) 恰 1 行（下車那一次 60 格；下車後 6000 格不豁免）", vehicle == 1
                    && Pattern.compile("exempt\\(vehicle\\) [^\\n]* dist=60\\.0 ").matcher(text).find()
                    && Pattern.compile(wk + "[^\\n]* dist=6000\\.0 [^\\n]* veh=1s ").matcher(text).find());
            String action = kick ? "would-kick(notConnected)" : "would-kick";
            expect("foreign：恰 2 行、帶送出的連線（spoofer）與被冒用的玩家、達門檻 action=" + action + "；20 格／秒以下那包不記",
                    foreignLines == 2 && Pattern.compile("\\[TeleportWatch\\] foreign sender=\"spoofer\" steamid=0 player=\"tw\" speed=(39|40)\\d\\d\\.\\d "
                            + "[^\\n]* to=8000\\.0,4000\\.0 packet=PlayerPacketReliable action=" + Pattern.quote(action) + " ").matcher(text).find()
                            && Pattern.compile("foreign sender=\"spoofer\" [^\\n]* to=16000\\.0,4000\\.0 packet=PlayerPacketReliable action=" + Pattern.quote(action) + " ")
                            .matcher(text).find()
                            && !text.contains("to=4005.0,4000.0"));
            expect("foreign 之後他自己的取樣從他自己的起點算：回原處 0 格不記、跳 6000 格兩次照判（別人送的位置不當起點）",
                    !Pattern.compile("user=\"tw\" [^\\n]* from=8000\\.0,4000\\.0 ").matcher(text).find()
                            && Pattern.compile(wk + "[^\\n]* dist=6000\\.0 dtMs=\\d+ from=4000\\.0,4000\\.0 to=10000\\.0,4000\\.0 ").matcher(text).find()
                            && Pattern.compile(wk + "[^\\n]* dist=6000\\.0 dtMs=\\d+ from=10000\\.0,4000\\.0 to=16000\\.0,4000\\.0 ").matcher(text).find());
            expect("沒經過 begin 的取樣不判", !text.contains("to=12000.0,6000.0"));
            expect("below 恰 2 行（Player-O 149.71、25 格／秒）；20 格／秒以下、有權限的、Player-U 上線那一秒都不記", below == 2
                    && count(text, "[TeleportWatch] ") == announce + 17 + 6 + 1 + 2 + 2 && !text.contains("to=4865.0,7158.0"));
            expect("Player-O 6174.87 那行帶欄位：speed／dist／dtMs／from／to／packet／login／spawn／base=363s／tp=-／veh=-／back=-／gapMs／ping",
                    Pattern.compile(wk + "steamid=0 speed=\\d+\\.\\d dist=6174\\.9 dtMs=10\\d\\d "
                            + "from=11472\\.0,9950\\.0 to=17646\\.9,9950\\.0 packet=PlayerPacketReliable login=36\\d\\ds spawn=\\d+s base=363s tp=- veh=- back=- gapMs=\\d+ ping=0 "
                            + "mode=" + mode + " limit=150\\.0 \\(#\\d+\\)").matcher(text).find());
            expect("伺服器傳送後那行 tp=0s、下車那行 veh=1s", Pattern.compile("exempt\\(grace\\) [^\\n]* tp=\\ds ").matcher(text).find()
                    && Pattern.compile("exempt\\(vehicle\\) [^\\n]* veh=1s ").matcher(text).find());
            String out1 = lineNo(text, "to=10779.0,2022.0 ");
            String out2 = lineNo(text, "to=10779.0,1958.0 ");
            expect("Player-R 來回：跳回原地附近那行標 back=#<起跳那行>、遠處到遠處不標、走回原地不記、第二次起跳另起一對；恰 2 行有 back=#",
                    out1 != null && out2 != null && !out1.equals(out2)
                            && Pattern.compile("to=1947\\.0,5030\\.0 [^\\n]* back=#" + out1 + "/\\d+s ").matcher(text).find()
                            && Pattern.compile("to=10971\\.0,2150\\.0 [^\\n]* back=- ").matcher(text).find()
                            && Pattern.compile("to=10779\\.0,2022\\.0 [^\\n]* back=- ").matcher(text).find()
                            && Pattern.compile("from=10779\\.0,1958\\.0 to=2000\\.0,5000\\.0 [^\\n]* back=#" + out2 + "/\\d+s ").matcher(text).find()
                            && !text.contains("to=1947.0,4966.0") && count(text, " back=#") == 2);
            long[] c = TeleportWatch.countersForTest();
            expect("計數：wouldKick 17、kicked 0（連線沒完全連上不踢）、exempt 7、adminSkipped 1、anomalies 1（沒經過 begin）、foreign 2（" + java.util.Arrays.toString(c) + "）",
                    c[1] == 17 && c[2] == 0 && c[3] == 7 && c[4] == 1 && c[5] == 1 && c[7] == 2);
        } else {
            expect(mode + "：零 [TeleportWatch] 行", count(text, "[TeleportWatch]") == 0);
            if ("off".equals(mode)) {
                expect("off：不建 state", TeleportWatch.stateForTest(rig.sc) == null);
            }
        }

        if (failed > 0) {
            OUT.println("teleport-watch FAIL " + failed);
            System.exit(1);
        }
        OUT.println("teleport-watch OK mode=" + mode);
    }

    /** 判斷函式的順序：權限 → 車上／下車（不超過車輛上限）→ 被車撞 → 門檻 → 寬限期。Player-T（856、被車撞）是 exempt(hitVehicle)。 */
    private static void verdictTable() {
        expect("verdict：有權限回 null（只計數）", TeleportWatch.verdict(true, false, false, false, false, 9999F, 150F, 70F) == null);
        expect("verdict：車上、下車那一次在車輛上限內 → exempt(vehicle)，先於被車撞與寬限期",
                "exempt(vehicle)".equals(TeleportWatch.verdict(false, true, false, true, true, 70F, 150F, 70F))
                        && "exempt(vehicle)".equals(TeleportWatch.verdict(false, false, true, true, true, 60F, 150F, 70F)));
        expect("verdict：下車那一次超過車輛上限 → 不豁免，6000 格／秒 would-kick、在寬限期內是 exempt(grace)",
                "would-kick".equals(TeleportWatch.verdict(false, false, true, false, false, 6000F, 150F, 70F))
                        && "exempt(grace)".equals(TeleportWatch.verdict(false, false, true, false, true, 6000F, 150F, 70F)));
        expect("verdict：Player-T 856 被車撞 → exempt(hitVehicle)，不看速度、先於寬限期",
                "exempt(hitVehicle)".equals(TeleportWatch.verdict(false, false, false, true, true, 856F, 150F, 150F)));
        expect("verdict：寬限期只豁免達門檻的跳動 → exempt(grace)；門檻以下照樣 below（不吃掉寬限）",
                "exempt(grace)".equals(TeleportWatch.verdict(false, false, false, false, true, 2808F, 150F, 70F))
                        && "below".equals(TeleportWatch.verdict(false, false, false, false, true, 25F, 150F, 70F)));
        expect("verdict：門檻邊界 149.99 below、150 would-kick（原版是 >，本刀 ≥ 門檻踢）",
                "below".equals(TeleportWatch.verdict(false, false, false, false, false, 149.99F, 150F, 70F))
                        && "would-kick".equals(TeleportWatch.verdict(false, false, false, false, false, 150F, 150F, 70F)));
        expect("foreignAction：送出的連線有權限 null、門檻以下 -、達門檻 would-kick（不看車輛與寬限期）",
                TeleportWatch.foreignAction(true, 9999F, 150F) == null && "-".equals(TeleportWatch.foreignAction(false, 149.99F, 150F))
                        && "would-kick".equals(TeleportWatch.foreignAction(false, 150F, 150F)));
        long now = 1_000_000L;
        expect("inGrace：基準點後 59 秒在、61 秒不在；登入或伺服器傳送後 74 秒在（15＋59）、76 秒不在；都沒有就不在",
                TeleportWatch.inGrace(now, now - 59_000L, 0L, 0L, 60_000L) && !TeleportWatch.inGrace(now, now - 61_000L, 0L, 0L, 60_000L)
                        && TeleportWatch.inGrace(now, 0L, now - 74_000L, 0L, 60_000L) && !TeleportWatch.inGrace(now, 0L, now - 76_000L, 0L, 60_000L)
                        && TeleportWatch.inGrace(now, 0L, 0L, now - 74_000L, 60_000L) && !TeleportWatch.inGrace(now, 0L, 0L, now - 76_000L, 60_000L)
                        && !TeleportWatch.inGrace(now, 0L, 0L, 0L, 60_000L));
        expect("kickDecision：沒完全連上、CantBeKickedByAnticheat 都不踢（原版 AntiCheat.act 同樣），其餘 kick",
                "would-kick(notConnected)".equals(TeleportWatch.kickDecision(false, false))
                        && "would-kick(cantBeKicked)".equals(TeleportWatch.kickDecision(true, true))
                        && "kick".equals(TeleportWatch.kickDecision(true, false)));
    }

    /** 回歸一筆：距基準點 (loginSec − 15) 秒，取樣一次速度約 speed。 */
    private static void regress(Rig rig, Rig twin, String mode, int loginSec, float speed) throws Exception {
        if (on(mode)) {
            shiftBaseline(rig, (loginSec - 15) * 1000L);
        }
        step(rig, twin, pos(rig.sc).x + speed, 9950F, 1000, false);
        expect("回歸 " + speed + "（距登入 " + loginSec + " s）：兩邊速度約 " + speed, near(speed(rig.sc), speed) && near(speed(twin.sc), speed));
    }

    /** 含 needle 的那一行 log 的 (#n)；沒有就 null。 */
    private static String lineNo(String text, String needle) {
        int i = text.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int end = text.indexOf('\n', i);
        Matcher m = Pattern.compile("\\(#(\\d+)\\)").matcher(end < 0 ? text.substring(i) : text.substring(i, end));
        return m.find() ? m.group(1) : null;
    }

    /** 把基準點改成 ago 毫秒前、寬限期重新可用（模擬一次新的登入；state 是 TeleportWatch 自己的帳，不影響原版取樣器）。 */
    private static void shiftBaseline(Rig rig, long ago) {
        TeleportWatch.State st = TeleportWatch.stateForTest(rig.sc);
        st.baselineMs = System.currentTimeMillis() - ago;
        st.graceUsed = false;
    }

    /** 他上一次自己的取樣改成 ago 毫秒前（取樣時間是真時鐘，測試的兩包只差幾毫秒）。vanilla 的 classpath 沒有 TeleportWatch，不碰。 */
    private static void ownAgo(Rig rig, long ago) {
        if (on(modeArg)) {
            TeleportWatch.stateForTest(rig.sc).ownMs -= ago;
        }
    }

    /** spoofer 這條連線送了 rig 那位玩家的封包：走真的 AntiCheatSpeed.validate（頭部記下送出的連線），雙胞胎同樣條件直接呼叫原版 set。 */
    private static String spoof(Rig rig, Rig twin, UdpConnection spoofer, float x, float y) throws Exception {
        arm(rig.sc, 1000);
        rig.packet.prediction.position.set(x, y, 0F);
        String v = new AntiCheatSpeed().validate(spoofer, rig.packet);
        arm(twin.sc, 1000);
        twin.sc.set(x, y, false, null);
        return v;
    }

    /** 配置出來的連線：只填 userName、role（沒有任何權限）、players（owned 放第 0 格）與 connectionTimestamp（登入 1 小時前；沒有完全連上）。 */
    private static UdpConnection connection(String name, IsoPlayer owned) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        field(UdpConnection.class, "userName").set(c, name);
        setRole(c, new Role("mdc-test-user"));
        IsoPlayer[] players = new IsoPlayer[4];
        players[0] = owned;
        c.players = players;
        c.connectionTimestamp = System.currentTimeMillis() + 15_000L - 3_600_000L;
        return c;
    }

    private static void setRole(UdpConnection c, Role role) throws Exception {
        field(UdpConnection.class, "role").set(c, role);
    }

    /**
     * 送一包：dt＜0 不取樣；dt＝15000 是重設後的基準點；其餘取樣、相隔約 dt 毫秒。dist 模式先照 {@code AntiCheatSpeed.validate} 頭部掛點
     * 以這位玩家自己的連線呼叫 {@code begin}（{@code noSender} 時傳 null）。雙胞胎同樣條件直接呼叫原版 set；它的時間在 rig 那一包
     * （含判斷與寫 log）之後才起算，兩邊相隔的毫秒一樣（機器忙時寫 log 會慢上幾十毫秒）。
     */
    private static void step(Rig rig, Rig twin, float x, float y, long dt, boolean inVehicle) throws Exception {
        prime(rig, dt);
        rig.packet.prediction.position.set(x, y, 0F);
        if (!"vanilla".equals(modeArg)) {
            TeleportWatch.begin(rig.noSender ? null : rig.conn, rig.packet);
        }
        rig.packet.resetMovable();
        prime(twin, dt);
        twin.sc.set(x, y, inVehicle, null);
        expect("雙胞胎：位置、是否在車上、速度與原版 set 相同（" + x + "," + y + " dt=" + dt + "）",
                pos(rig.sc).x == pos(twin.sc).x && pos(rig.sc).y == pos(twin.sc).y
                        && scInVehicle.getBoolean(rig.sc) == scInVehicle.getBoolean(twin.sc)
                        && Math.abs(speed(rig.sc) - speed(twin.sc)) <= Math.max(0.5F, speed(twin.sc) * 0.01F));
    }

    private static void prime(Rig r, long dt) throws Exception {
        if (dt < 0) {
            quiet(r.sc);
        } else {
            arm(r.sc, dt);
        }
    }

    private static void arm(NetworkCharacterAI.SpeedChecker sc, long dt) throws Exception {
        UpdateLimit ul = (UpdateLimit) scUpdateLimit.get(sc);
        long now = System.currentTimeMillis();
        limitLast.setLong(ul, now - ul.getDelay() - 1L);
        if (ul.getDelay() != 15000L) {
            scLastTime.setLong(sc, now - dt);
        }
    }

    private static void quiet(NetworkCharacterAI.SpeedChecker sc) throws Exception {
        limitLast.setLong(scUpdateLimit.get(sc), System.currentTimeMillis());
    }

    private static boolean on(String mode) {
        return "observe".equals(mode) || "kick".equals(mode);
    }

    private static Vector2 pos(NetworkCharacterAI.SpeedChecker sc) throws Exception {
        return (Vector2) scPosition.get(sc);
    }

    private static float speed(NetworkCharacterAI.SpeedChecker sc) throws Exception {
        return scSpeed.getFloat(sc);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= b * 0.02F;
    }

    /** 配置出來的 IsoPlayer＋真的 NetworkPlayerComponent／NetworkPlayerAI／SpeedChecker＋真的 PlayerPacketReliable＋擁有他的連線。 */
    static final class Rig {
        final IsoPlayer player;
        final NetworkPlayerAI ai;
        final NetworkCharacterAI.SpeedChecker sc;
        final PlayerPacket packet;
        final UdpConnection conn;
        boolean noSender;

        Rig() throws Exception {
            player = alloc(IsoPlayer.class);
            player.username = "tw";
            Field health = field(IsoGameCharacter.class, "health");
            health.setFloat(player, 1.0F);
            field(IsoPlayer.class, "ecsComponentMap").set(player, new HashMap<>());
            ai = alloc(NetworkPlayerAI.class);
            sc = new NetworkCharacterAI.SpeedChecker();
            field(NetworkCharacterAI.class, "speedChecker").set(ai, sc);
            NetworkPlayerComponent component = alloc(NetworkPlayerComponent.class);
            field(NetworkPlayerComponent.class, "ecsClass").set(component, ECSComponent.getECSClass(NetworkPlayerComponent.class));
            field(NetworkPlayerComponent.class, "networkAi").set(component, ai);
            player.setECSComponent(component);
            if (player.getNetworkCharacterAI() != ai || player.isDead() || player.isSeatedInVehicle()) {
                throw new IllegalStateException("配置的玩家不成立");
            }
            packet = new PlayerPacketReliable();
            packet.player.setPlayer(player);   // 42.22：PlayerPacket 的玩家欄位是 ConnectionPlayer
            if (packet.getPlayer() != player || packet.hitVehicleId.getVehicle() != null) {
                throw new IllegalStateException("配置的封包不成立");
            }
            conn = connection("tw", player);
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // 往父類別找
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        OUT.println((ok ? "tw pass  " : "tw FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
