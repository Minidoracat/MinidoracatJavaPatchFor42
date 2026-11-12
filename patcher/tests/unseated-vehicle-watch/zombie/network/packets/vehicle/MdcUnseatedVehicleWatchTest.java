package zombie.network.packets.vehicle;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;

import gnu.trove.map.hash.TShortObjectHashMap;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.physics.CarController;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.Authorization;
import zombie.vehicles.VehicleInterpolationData;

/**
 * W99：helper 的判斷（一段搬動、kind、門檻、累計起點、授權來源、行數額度）＋dist 手術後的真 {@code VehicleCollidePacket.processServer}
 * （經 W46 的 helper 轉來的申請；測試自己設定授權）。{@code VehiclePhysicsPacket.processServer} 的掛點位置（授權與時間檢查之後、setX 之前）由 SmokeCheck
 * 釘住，實機由 E2E {@code unseated-move-mp} 驗。
 *
 * <p>argv：{@code observe}（出貨組態）／{@code off}（配 {@code -Dmdc.unseatedVehicleWatch=off}），自驗旗標。
 * BaseVehicle／IsoPlayer／UdpConnection 以 Unsafe 配置後只補用到的欄位。
 */
public final class MdcUnseatedVehicleWatchTest {
    private static final PrintStream OUT = System.out;
    private static int failed;
    private static long now;
    /** logthrow：日誌串流改拋 OutOfMemoryError（證明 Error 不被吞）。 */
    private static boolean oom;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();   // DebugLog／setNetPlayerAuthorization 經 GameClient → ServerOptions 呼叫 Rand
        GameServer.server = true;
        if ("logthrow".equals(args[0])) {
            logThrow();
            return;
        }
        boolean on = !"off".equals(args[0]);
        expect("自驗：argv=" + args[0] + " 與 -Dmdc.unseatedVehicleWatch=" + System.getProperty("mdc.unseatedVehicleWatch") + " 相符",
                MdcUnseatedVehicleWatch.ENABLED == on);
        expect("enabled：null／observe／亂寫→開、0／off→關；parseTiles：預設 5、≤0／Inf／亂寫回 5、12 照用",
                MdcUnseatedVehicleWatch.enabled(null) && MdcUnseatedVehicleWatch.enabled("observe")
                        && MdcUnseatedVehicleWatch.enabled("x") && !MdcUnseatedVehicleWatch.enabled(" OFF ")
                        && !MdcUnseatedVehicleWatch.enabled("0") && MdcUnseatedVehicleWatch.parseTiles(null) == 5F
                        && MdcUnseatedVehicleWatch.parseTiles("-1") == 5F && MdcUnseatedVehicleWatch.parseTiles("Infinity") == 5F
                        && MdcUnseatedVehicleWatch.parseTiles("abc") == 5F && MdcUnseatedVehicleWatch.parseTiles("12") == 12F);
        ByteArrayOutputStream log = captureLog();
        now = System.currentTimeMillis();

        IsoPlayer attacker = player(5, "attacker", 90F, 200F);
        UdpConnection attackerConn = connection("attacker", 76561190000000005L, attacker);
        IsoPlayer driver = player(6, "driver", 300F, 300F);
        UdpConnection driverConn = connection("driver", 76561190000000006L, driver);
        IsoPlayer other = player(7, "other", 500F, 500F);
        connection("other", 76561190000000007L, other);

        // 1. 真的改道：原版 setClientForce 恰呼叫一次；有 begin 才算這包，沒有或對不上車只計 anomalies
        BaseVehicle suv = vehicle(42, 7001, "Base.SUV", 100F, 200F, Authorization.LocalCollide, 5);
        VehiclePhysicsPacket packet = packet(suv, 101F, 200F);
        MdcUnseatedVehicleWatch.begin(packet, attackerConn);
        MdcUnseatedVehicleWatch.setClientForce(suv, 0.7F);
        expect("setClientForce：原版照樣寫進 CarController", physics(suv).clientForce == 0.7F);
        expect(on ? "observe：有 begin 的包算一包、開一段（起點是車的舊位置）" : "off：不計數、不開段",
                on ? MdcUnseatedVehicleWatch.accepted == 1 && MdcUnseatedVehicleWatch.open.get((short) 42) != null
                        && MdcUnseatedVehicleWatch.open.get((short) 42).sx == 100F
                        : MdcUnseatedVehicleWatch.accepted == 0 && MdcUnseatedVehicleWatch.open.isEmpty());
        MdcUnseatedVehicleWatch.setClientForce(suv, 0.3F);
        BaseVehicle stray = vehicle(43, 7002, "Base.Van", 0F, 0F, Authorization.LocalCollide, 5);
        MdcUnseatedVehicleWatch.begin(packet, attackerConn);
        MdcUnseatedVehicleWatch.setClientForce(stray, 0.2F);
        expect(on ? "沒經過 begin、begin 的封包不是這台車：原版照呼叫、只計 anomalies 2" : "off：anomalies 0",
                physics(suv).clientForce == 0.3F && physics(stray).clientForce == 0.2F
                        && MdcUnseatedVehicleWatch.anomalies == (on ? 2 : 0));

        // 2. 不坐上車把車移 20 格（每包 1 格），5 秒沒新包才收段
        move(suv, attackerConn, 100F, 200F, 120F, 200F, 20, 100L);
        String moving = line(log, "moving kind=unseated vehicle=7001/42 Base.SUV");
        expect(on ? "沒在開車的人搬到 5 格那一包：立刻記一行 moving（只一次）" : "off：沒有 moving 行",
                on ? moving != null && moving.contains("to=105.0,200.0 dist=5.0") && count(log, "moving ") == 1
                        && MdcUnseatedVehicleWatch.movingNow == 1
                        : moving == null);
        MdcUnseatedVehicleWatch.sweep(now + 4_000L);
        expect(on ? "最後一包後 4 秒：還沒收段、沒記行" : "off：沒有行", count(log, "moved ") == 0);
        MdcUnseatedVehicleWatch.sweep(now + 6_000L);
        String moved = line(log, "moved kind=unseated vehicle=7001/42 Base.SUV");
        expect(on ? "5 秒沒新包：記一行 moved kind=unseated（起訖、距離、帳號、SteamID、授權、沒坐車、授權來源 other）" : "off：沒有行",
                on ? moved != null && moved.contains("from=100.0,200.0 to=120.0,200.0 dist=20.0")
                        && moved.contains("user=\"attacker\" steamid=76561190000000005") && moved.contains("auth=LocalCollide pid=5")
                        && moved.contains("seat=none") && moved.contains("playerDist=10.0/30.0") && moved.contains("grant=other")
                        && MdcUnseatedVehicleWatch.unseated == 1
                        : moved == null && MdcUnseatedVehicleWatch.unseated == 0);

        // 3. 經真 VehicleCollidePacket.processServer（W46 helper 轉來）的申請：沒在開車 → 可疑申請一行；測試設定授權後搬 30 格，記 grant=collide
        BaseVehicle beetle = vehicle(50, 3047, "Base.63beetle", 200F, 200F, Authorization.Server, -1);
        collide(beetle, attacker, attackerConn, true);
        beetle.netPlayerAuthorization = Authorization.LocalCollide;
        beetle.netPlayerId = 5;
        String req = line(log, "collide-request suspect=notDriving");
        expect(on ? "可疑申請（沒在開車）：一行，帶帳號、SteamID、車、位置、授權、requester=none" : "off：沒有行",
                on ? req != null && req.contains("user=\"attacker\" steamid=76561190000000005 pid=5")
                        && req.contains("vehicle=3047/50 Base.63beetle at=200.0,200.0 auth=Server requester=none")
                        : req == null);
        collide(beetle, attacker, attackerConn, true);
        expect(on ? "同一個帳號 5 秒內第二次可疑申請：只計數不記行" : "off：不計數",
                count(log, "collide-request") == (on ? 1 : 0) && MdcUnseatedVehicleWatch.requestsSuspect == (on ? 2 : 0));
        move(beetle, attackerConn, 200F, 200F, 200F, 230F, 30, 100L);
        MdcUnseatedVehicleWatch.sweep(now + 6_000L);
        String m2 = line(log, "moved kind=unseated vehicle=3047/50");
        expect(on ? "申請之後的搬動：grant=collide(by=\"attacker\" … own=yes)" : "off：沒有行",
                on ? m2 != null && m2.contains("dist=30.0") && m2.contains("grant=collide(by=\"attacker\" ago=") && m2.contains("own=yes)")
                        : m2 == null);

        // 4. 申請別人的玩家 id（別條連線）
        BaseVehicle van = vehicle(51, 3048, "Base.Van", 400F, 400F, Authorization.Server, -1);
        collide(van, other, driverConn, true);
        expect(on ? "申請的玩家不在送出的連線上：suspect=notOwnPlayer" : "off：沒有行",
                (line(log, "collide-request suspect=notOwnPlayer user=\"driver\"") != null) == on);

        // 5. 正常撞車：駕駛在 10 格內申請，不記；撞開 2 格不記；推 8 格記 kind=pushed；開的車離 40 格算 far
        BaseVehicle car = vehicle(60, 8000, "Base.CarNormal", 300F, 300F, Authorization.Local, 6);
        seat(car, 0, driver);
        BaseVehicle parked = vehicle(61, 8001, "Base.PickUpTruck", 310F, 300F, Authorization.Server, -1);
        int reqLines = count(log, "collide-request");
        collide(parked, driver, driverConn, true);
        expect("駕駛撞到 10 格內的車：不記申請", count(log, "collide-request") == reqLines);
        move(parked, driverConn, 310F, 300F, 312F, 300F, 4, 100L);
        BaseVehicle pushedFar = vehicle(62, 8002, "Base.SmallCar", 305F, 300F, Authorization.LocalCollide, 6);
        move(pushedFar, driverConn, 305F, 300F, 313F, 300F, 8, 100L);
        MdcUnseatedVehicleWatch.sweep(now + 6_000L);
        String pushLine = line(log, "moved kind=pushed vehicle=8002/62");
        expect(on ? "撞開 2 格：只計數；推 8 格：moved kind=pushed（沒有 moving 行），seat=driving 8000/60 Base.CarNormal d=5.0" : "off：沒有行",
                on ? line(log, "vehicle=8001/61") == null && pushLine != null && line(log, "moving kind=pushed") == null
                        && pushLine.contains("seat=driving 8000/60 Base.CarNormal d=5.0") && MdcUnseatedVehicleWatch.pushed == 2
                        : pushLine == null);
        BaseVehicle farCar = vehicle(63, 8003, "Base.SmallCar", 340F, 300F, Authorization.Server, -1);
        collide(farCar, driver, connection("driver2", 1L, driver), true);
        expect(on ? "開的車離目標 40 格：suspect=far … d=40.0" : "off：沒有行",
                on == (line(log, "collide-request suspect=far") != null && line(log, "collide-request suspect=far").contains("d=40.0")));

        // 6. 拖車：被同一位駕駛的車拖著，搬 50 格只計數
        BaseVehicle trailer = vehicle(70, 9000, "Base.Trailer", 290F, 300F, Authorization.Local, 6);
        field(BaseVehicle.class, "vehicleTowedBy").set(trailer, car);
        move(trailer, driverConn, 290F, 300F, 340F, 300F, 50, 100L);
        MdcUnseatedVehicleWatch.sweep(now + 6_000L);
        expect(on ? "被同一位駕駛拖著的拖車：towed 計數、不記行" : "off：不計數",
                line(log, "vehicle=9000/70") == null && MdcUnseatedVehicleWatch.towed == (on ? 1 : 0));

        // 7. 車上有駕駛：不開段
        move(car, driverConn, 300F, 300F, 340F, 300F, 40, 100L);
        expect("有駕駛的車：不開段", MdcUnseatedVehicleWatch.open.get((short) 60) == null);

        // 8. 兩包相隔 6 秒＝兩段
        BaseVehicle split = vehicle(80, 9100, "Base.Van", 600F, 600F, Authorization.LocalCollide, 5);
        long ep0 = MdcUnseatedVehicleWatch.episodes;
        MdcUnseatedVehicleWatch.observe(split, 606F, 600F, 600F, 600F, attackerConn, now);
        split.setX(606F);
        MdcUnseatedVehicleWatch.observe(split, 612F, 600F, 606F, 600F, attackerConn, now + 6_000L);
        expect(on ? "相隔 6 秒的兩包：兩段（第一段 6 格收段記一行）" : "off：沒有段",
                on ? MdcUnseatedVehicleWatch.episodes == ep0 + 2 && line(log, "from=600.0,600.0 to=606.0,600.0") != null
                        : MdcUnseatedVehicleWatch.episodes == 0);

        // 9. 歸還帶玩家 id：他不是這台車的駕駛就記一行
        BaseVehicle lent = vehicle(90, 9200, "Base.SUV", 700F, 700F, Authorization.LocalCollide, 5);
        collide(lent, attacker, connection("attacker2", 76561190000000005L, attacker), false);
        String rel = line(log, "release-to-player");
        expect(on ? "歸還帶的玩家不是這台車的駕駛：release-to-player 一行（driver=none）" : "off：沒有行",
                on ? rel != null && rel.contains("pid=5 own=yes vehicle=9200/90 Base.SUV") && rel.contains("driver=none") : rel == null);
        BaseVehicle own = vehicle(91, 9201, "Base.SUV", 710F, 700F, Authorization.LocalCollide, 6);
        seat(own, 0, driver);
        long rtp = MdcUnseatedVehicleWatch.releasesToPlayer;
        collide(own, driver, connection("driver3", 6L, driver), false);
        collide(vehicle(92, 9202, "Base.SUV", 720F, 700F, Authorization.LocalCollide, 5), null, connection("x", 9L, null), false);
        expect("駕駛歸還自己開的車、帶 -1 的一般歸還：不記", MdcUnseatedVehicleWatch.releasesToPlayer == rtp);
        long seen = MdcUnseatedVehicleWatch.requestsSeen;
        collide(vehicle(93, 9203, "Base.SUV", 730F, 700F, Authorization.Local, 6), attacker, connection("y", 5L, attacker), true);
        expect("車是 Local：不計", MdcUnseatedVehicleWatch.requestsSeen == seen);

        // 9b. 一般歸還（帶 -1）：原版把授權收回伺服器，這一段當場收段
        BaseVehicle back = vehicle(94, 9300, "Base.Van", 800F, 800F, Authorization.LocalCollide, 5);
        move(back, attackerConn, 800F, 800F, 808F, 800F, 8, 100L);
        collide(back, null, connection("attacker4", 5L, attacker), false);
        expect(on ? "一般歸還：當場記 moved（不等 5 秒）" : "off：沒有行",
                on == (line(log, "moved kind=unseated vehicle=9300/94") != null)
                        && MdcUnseatedVehicleWatch.open.get((short) 94) == null
                        && back.netPlayerAuthorization == Authorization.Server);

        // 11. 分類每包重算：同一位玩家的拖車段，拆掉拖車後（同一個 pid、沒有空窗）照樣搬 → 換成 unseated 一段、記 moving
        BaseVehicle hitch = vehicle(100, 9400, "Base.Trailer", 900F, 900F, Authorization.Local, 6);
        field(BaseVehicle.class, "vehicleTowedBy").set(hitch, car);
        move(hitch, driverConn, 900F, 900F, 902F, 900F, 2, 100L);
        field(BaseVehicle.class, "vehicleTowedBy").set(hitch, null);
        field(IsoGameCharacter.class, "vehicle").set(driver, null);
        ((BaseVehicle.Passenger[]) field(BaseVehicle.class, "passengers").get(car))[0] = null;
        long towedBefore = MdcUnseatedVehicleWatch.towed;
        MdcUnseatedVehicleWatch.observe(hitch, 903F, 900F, 902F, 900F, driverConn, now + 100L);
        hitch.setX(903F);
        MdcUnseatedVehicleWatch.observe(hitch, 909F, 900F, 903F, 900F, driverConn, now + 200L);
        hitch.setX(909F);
        expect(on ? "拆掉拖車、下了車：拖車段收掉（towed 計數）、改開 unseated 段，搬到 6 格記 moving" : "off：沒有行",
                on ? MdcUnseatedVehicleWatch.towed == towedBefore + 1
                        && line(log, "moving kind=unseated vehicle=9400/100 Base.Trailer from=902.0,900.0 to=909.0,900.0") != null
                        : line(log, "vehicle=9400/100") == null);

        // 12. 申請對拖車夥伴一起記：夥伴的搬動記 grant=collide；一般歸還一起收段
        BaseVehicle head = vehicle(110, 9500, "Base.PickUpTruck", 1000F, 1000F, Authorization.Server, -1);
        BaseVehicle tail = vehicle(111, 9501, "Base.Trailer", 995F, 1000F, Authorization.Server, -1);
        field(BaseVehicle.class, "vehicleTowing").set(head, tail);
        field(BaseVehicle.class, "vehicleTowedBy").set(tail, head);
        UdpConnection a5 = connection("attacker5", 5L, attacker);
        collide(head, attacker, a5, true);
        tail.netPlayerAuthorization = Authorization.LocalCollide;
        tail.netPlayerId = 5;
        move(tail, attackerConn, 995F, 1000F, 989F, 1000F, 6, 100L);
        collide(head, null, a5, false);
        String tailLine = line(log, "moved kind=unseated vehicle=9501/111");
        expect(on ? "夥伴的搬動：grant=collide(by=\"attacker5\" …)，車頭的一般歸還當場收掉夥伴這一段" : "off：沒有行",
                on ? tailLine != null && tailLine.contains("grant=collide(by=\"attacker5\"")
                        && MdcUnseatedVehicleWatch.open.get((short) 111) == null
                        : tailLine == null);

        // 13. 帳號帶換行與引號：記成一行（換成 ?）
        IsoPlayer odd = player(8, "odd", 1200F, 1200F);
        UdpConnection oddConn = connection("odd\nINJECT \"x\"", 8L, odd);
        BaseVehicle oddCar = vehicle(120, 9600, "Base.Van", 1210F, 1200F, Authorization.LocalCollide, 8);
        move(oddCar, oddConn, 1210F, 1200F, 1220F, 1200F, 10, 100L);
        String oddLine = line(log, "vehicle=9600/120");
        expect(on ? "帳號裡的換行、引號換成 ?，整筆仍在同一行" : "off：沒有行",
                on ? oddLine != null && oddLine.contains("user=\"odd?INJECT ?x?\"") && !log.toString().contains("\nINJECT")
                        : oddLine == null);

        // 14. 分段搬：每段 4 格、段間停 6 秒（每段都不到門檻），同一台車 60 秒內沿用起點累計 → 累計 5 格以上記 moving，收段記總距離；換帳號記 earlierUsers
        IsoPlayer other2 = player(9, "helper", 1300F, 1300F);
        UdpConnection helperConn = connection("helper", 9L, other2);
        BaseVehicle hop = vehicle(130, 9700, "Base.Van", 1300F, 1310F, Authorization.LocalCollide, 5);
        long t0 = now + 10_000L;
        for (int seg = 0; seg < 4; seg++) {
            if (seg == 3) {
                hop.netPlayerId = 9;
            }
            UdpConnection c = seg == 3 ? helperConn : attackerConn;
            for (int i = 1; i <= 4; i++) {
                float x0 = 1300F + seg * 4 + i - 1;
                MdcUnseatedVehicleWatch.observe(hop, x0 + 1F, 1310F, x0, 1310F, c, t0 + seg * 6_000L + i * 100L);
                hop.setX(x0 + 1F);
            }
        }
        MdcUnseatedVehicleWatch.sweep(t0 + 30_000L);
        String hopMoving = line(log, "moving kind=unseated vehicle=9700/130");
        String hopMoved = null;
        for (String l : log.toString().split("\n")) {
            if (l.contains("moved kind=unseated vehicle=9700/130") && l.contains("dist=16.0")) {
                hopMoved = l;
            }
        }
        expect(on ? "分段搬（每段 4 格、停 6 秒）：第二段第一包累計 5 格記 moving（只一次）；最後一段換帳號，收段記 dist=16.0 與 earlierUsers=\"attacker\"" : "off：沒有行",
                on ? hopMoving != null && hopMoving.contains("from=1300.0,1310.0 to=1305.0,1310.0") && count(log, "moving kind=unseated vehicle=9700/130") == 1
                        && hopMoved != null && hopMoved.contains("user=\"helper\"") && hopMoved.contains("earlierUsers=\"attacker\"")
                        : hopMoving == null);

        // 15. pushed 只算開的車就在旁邊：坐在 100 格外的車上搬另一台車 20 格 → unseated、記 moving
        IsoPlayer far = player(10, "far", 2000F, 2000F);
        UdpConnection farConn = connection("far", 10L, far);
        BaseVehicle farOwn = vehicle(140, 9800, "Base.CarNormal", 2000F, 2000F, Authorization.Local, 10);
        seat(farOwn, 0, far);
        BaseVehicle farTarget = vehicle(141, 9801, "Base.Van", 2100F, 2000F, Authorization.LocalCollide, 10);
        move(farTarget, farConn, 2100F, 2000F, 2120F, 2000F, 20, 100L);
        String farLine = line(log, "moving kind=unseated vehicle=9801/141");
        expect(on ? "開的車在 100 格外：不算 pushed，記 moving kind=unseated（seat=driving 9800/140 …）" : "off：沒有行",
                on ? farLine != null && farLine.contains("seat=driving 9800/140 Base.CarNormal") : farLine == null);

        // 16. 每個帳號的明細額度：spam 帳號 101 台車各搬 6 格，只記 100 行；之後別的帳號照記
        IsoPlayer spam = player(11, "spam", 3000F, 3000F);
        UdpConnection spamConn = connection("spam", 11L, spam);
        for (int i = 0; i < 101; i++) {
            BaseVehicle v = vehicle(200 + i, 20000 + i, "Base.Van", 3000F + i * 10, 3000F, Authorization.LocalCollide, 11);
            move(v, spamConn, 3000F + i * 10, 3000F, 3006F + i * 10, 3000F, 6, 100L);
        }
        IsoPlayer late = player(12, "late", 4000F, 4000F);
        BaseVehicle lateCar = vehicle(400, 30000, "Base.Van", 4000F, 4000F, Authorization.LocalCollide, 12);
        move(lateCar, connection("late", 12L, late), 4000F, 4000F, 4006F, 4000F, 6, 100L);
        expect(on ? "spam 帳號只記 " + MdcUnseatedVehicleWatch.USER_CAP + " 行＋一行到頂通知（userCapped ≥ 1），之後 late 帳號的 moving 照記" : "off：沒有行",
                on ? count(log, "user=\"spam\"") == MdcUnseatedVehicleWatch.USER_CAP + 1 && line(log, "user=\"spam\" 的搬動明細已達 100 行") != null
                        && MdcUnseatedVehicleWatch.userCapped >= 1
                        && line(log, "moving kind=unseated vehicle=30000/400") != null
                        : count(log, "user=\"spam\"") == 0);

        // 17. 一般歸還讓舊申請作廢：之後別的來源給的授權記 grant=other
        IsoPlayer granted = player(14, "granted", 6000F, 6000F);
        UdpConnection grantedConn = connection("granted", 14L, granted);
        BaseVehicle gv = vehicle(510, 41000, "Base.Van", 6000F, 6010F, Authorization.Server, -1);
        collide(gv, granted, grantedConn, true);
        collide(gv, null, grantedConn, false);
        gv.netPlayerAuthorization = Authorization.Local;
        gv.netPlayerId = 14;
        move(gv, grantedConn, 6000F, 6010F, 6010F, 6010F, 10, 100L);
        String gl = line(log, "moving kind=unseated vehicle=41000/510");
        expect(on ? "申請 → 一般歸還 → 別的來源給授權：grant=other（不沿用舊申請）" : "off：沒有行",
                on ? gl != null && gl.contains("grant=other") : gl == null);

        // 18. earlierUsers 以完整帳號去重：alphabet、alpha 各搬 1 格，third 接著搬到累計 6 格 → 兩個都記
        BaseVehicle sv = vehicle(520, 42000, "Base.Van", 7000F, 7010F, Authorization.LocalCollide, 15);
        String[] names = {"alphabet", "alpha", "third"};
        long t2 = now + 300_000L;
        float sx = 7000F;
        for (int seg = 0; seg < 3; seg++) {
            IsoPlayer p = player(15 + seg, names[seg], 7000F, 7000F);
            UdpConnection c = connection(names[seg], 15L + seg, p);
            sv.netPlayerId = (short) (15 + seg);
            float step = seg == 2 ? 4F : 1F;
            MdcUnseatedVehicleWatch.observe(sv, sx + step, 7010F, sx, 7010F, c, t2 + seg * 6_000L);
            sx += step;
            sv.setX(sx);
        }
        String sl = line(log, "moving kind=unseated vehicle=42000/520");
        expect(on ? "earlierUsers=\"alphabet,alpha\"（alpha 是 alphabet 的子字串也照記）" : "off：沒有行",
                on ? sl != null && sl.contains("user=\"third\"") && sl.contains("earlierUsers=\"alphabet,alpha\"") : sl == null);

        // 19. 額度用帳號原文：清洗後同為 r1?r2 的兩個帳號互不影響
        MdcUnseatedVehicleWatch.userMoveLines.put("r1\nr2", MdcUnseatedVehicleWatch.USER_CAP);
        IsoPlayer tab = player(18, "r1\tr2", 8000F, 8000F);
        BaseVehicle qv = vehicle(530, 43000, "Base.Van", 8000F, 8000F, Authorization.LocalCollide, 18);
        move(qv, connection("r1\tr2", 18L, tab), 8000F, 8000F, 8006F, 8000F, 6, 100L);
        String ql = line(log, "moving kind=unseated vehicle=43000/530");
        expect(on ? "r1\\nr2 額度用完，r1\\tr2 照記（user=\"r1?r2\"）" : "off：沒有行",
                on ? ql != null && ql.contains("user=\"r1?r2\"") : ql == null);

        // 20. 拖曳前後不跨段累計：搬 3 格收段 → 被同一位玩家的車拖 30 格 → 拆掉拖車、下車再搬 3 格，不記行
        now += 100_000L;
        IsoPlayer tower = player(19, "tower", 9000F, 9000F);
        UdpConnection towConn = connection("tower", 19L, tower);
        BaseVehicle tv = vehicle(540, 44000, "Base.Trailer", 9000F, 9010F, Authorization.LocalCollide, 19);
        move(tv, towConn, 9000F, 9010F, 9003F, 9010F, 3, 100L);
        now += 6_000L;
        MdcUnseatedVehicleWatch.sweep(now);
        BaseVehicle towCar = vehicle(541, 44001, "Base.PickUpTruck", 9003F, 9005F, Authorization.Local, 19);
        seat(towCar, 0, tower);
        field(BaseVehicle.class, "vehicleTowedBy").set(tv, towCar);
        long towedBefore2 = MdcUnseatedVehicleWatch.towed;
        move(tv, towConn, 9003F, 9010F, 9033F, 9010F, 30, 100L);
        now += 6_000L;
        MdcUnseatedVehicleWatch.sweep(now);
        field(BaseVehicle.class, "vehicleTowedBy").set(tv, null);
        field(IsoGameCharacter.class, "vehicle").set(tower, null);
        ((BaseVehicle.Passenger[]) field(BaseVehicle.class, "passengers").get(towCar))[0] = null;
        move(tv, towConn, 9033F, 9010F, 9036F, 9010F, 3, 100L);
        now += 6_000L;
        MdcUnseatedVehicleWatch.sweep(now);
        expect(on ? "拖曳前的起點不延續：拖 30 格只計 towed，之後搬 3 格不記行" : "off：沒有行",
                line(log, "vehicle=44000/540") == null && MdcUnseatedVehicleWatch.towed == (on ? towedBefore2 + 1 : 0));

        // 21. 累計起點保留 30 分鐘：每段 4 格、段間停 61 秒，第二段累計 8 格記 moving
        BaseVehicle slow = vehicle(550, 45000, "Base.Van", 10000F, 10010F, Authorization.LocalCollide, 5);
        long t3 = now + 400_000L;
        for (int seg = 0; seg < 2; seg++) {
            float x0 = 10000F + seg * 4;
            MdcUnseatedVehicleWatch.observe(slow, x0 + 4F, 10010F, x0, 10010F, attackerConn, t3 + seg * 61_000L);
            slow.setX(x0 + 4F);
        }
        String slowLine = line(log, "moving kind=unseated vehicle=45000/550");
        expect(on ? "段間停 61 秒仍累計：moving from=10000.0 to=10008.0" : "off：沒有行",
                on ? slowLine != null && slowLine.contains("from=10000.0,10010.0 to=10008.0,10010.0") : slowLine == null);

        // 22. 同一個 vid 換了一台車：不沿用前一台的起點
        BaseVehicle first = vehicle(560, 46000, "Base.Van", 11000F, 11010F, Authorization.LocalCollide, 5);
        long t4 = now + 600_000L;
        MdcUnseatedVehicleWatch.observe(first, 11003F, 11010F, 11000F, 11010F, attackerConn, t4);
        MdcUnseatedVehicleWatch.sweep(t4 + 6_000L);
        BaseVehicle reused = vehicle(560, 46001, "Base.Van", 12000F, 12010F, Authorization.LocalCollide, 5);
        MdcUnseatedVehicleWatch.observe(reused, 12003F, 12010F, 12000F, 12010F, attackerConn, t4 + 7_000L);
        MdcUnseatedVehicleWatch.sweep(t4 + 20_000L);
        expect("vid 被別台車重用：各自起算，3 格不記行", line(log, "vehicle=46001/560") == null && line(log, "vehicle=46000/560") == null);

        // 23. 每個帳號的額度分類計：申請額度用完，搬動照記；搬動額度用到第 100 行時印通知
        MdcUnseatedVehicleWatch.userRequestLines.put("both", MdcUnseatedVehicleWatch.USER_CAP);
        MdcUnseatedVehicleWatch.userMoveLines.put("both", MdcUnseatedVehicleWatch.USER_CAP - 1);
        IsoPlayer both = player(20, "both", 13000F, 13000F);
        BaseVehicle bv = vehicle(570, 47000, "Base.Van", 13000F, 13000F, Authorization.LocalCollide, 20);
        move(bv, connection("both", 20L, both), 13000F, 13000F, 13006F, 13000F, 6, 100L);
        expect(on ? "申請額度用完不影響搬動：moving 照記，並印搬動額度到頂通知" : "off：沒有行",
                on ? line(log, "moving kind=unseated vehicle=47000/570") != null && line(log, "user=\"both\" 的搬動明細已達 100 行") != null
                        : line(log, "vehicle=47000/570") == null);

        // 10. 首次生效行
        expect(on ? "首次生效行（minTiles=5.0）" : "off：沒有任何 UnseatedVehicleWatch 行",
                on ? line(log, "首次生效") != null && line(log, "首次生效").contains("minTiles=5.0") : !log.toString().contains("UnseatedVehicleWatch"));

        OUT.println(failed == 0 ? "MdcUnseatedVehicleWatchTest OK (" + args[0] + ")" : "MdcUnseatedVehicleWatchTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 日誌鏈拋例外：首次生效、可疑申請的記錄爆掉時原版照常完成、只計 anomalies；改拋 OutOfMemoryError 時照常外拋。 */
    private static void logThrow() throws Exception {
        expect("logthrow 只配 observe", MdcUnseatedVehicleWatch.ENABLED);
        explodeLog();
        IsoPlayer p = player(5, "attacker", 90F, 200F);
        UdpConnection conn = connection("attacker", 5L, p);
        BaseVehicle v = vehicle(42, 7001, "Base.SUV", 100F, 200F, Authorization.LocalCollide, 5);
        MdcUnseatedVehicleWatch.begin(packet(v, 101F, 200F), conn);
        Throwable t = null;
        try {
            MdcUnseatedVehicleWatch.setClientForce(v, 0.7F);
        } catch (Throwable e) {
            t = e;
        }
        expect("首次生效記錄拋例外：原版 setClientForce 照常完成、不外拋、記 anomalies（" + t + "）",
                t == null && physics(v).clientForce == 0.7F && MdcUnseatedVehicleWatch.anomalies >= 1);
        long before = MdcUnseatedVehicleWatch.anomalies;
        BaseVehicle w = vehicle(50, 3047, "Base.63beetle", 200F, 200F, Authorization.Server, -1);
        t = null;
        try {
            collide(w, p, conn, true);
        } catch (Throwable e) {
            t = e;
        }
        expect("可疑申請記錄拋例外：真 processServer 照常跑完、不外拋、記 anomalies（" + t + "）",
                t == null && MdcUnseatedVehicleWatch.requestsSuspect == 1 && MdcUnseatedVehicleWatch.anomalies > before);
        oom = true;
        t = null;
        try {
            collide(vehicle(51, 3048, "Base.Van", 300F, 300F, Authorization.Server, -1), p, connection("attacker2", 5L, p), true);
        } catch (Throwable e) {
            t = e;
        }
        expect("Error 照常外拋：日誌鏈拋 OutOfMemoryError 時不被吞，從 processServer 拋出（" + t + "）", t instanceof OutOfMemoryError);
        OUT.println(failed == 0 ? "MdcUnseatedVehicleWatchTest OK (logthrow)" : "MdcUnseatedVehicleWatchTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 真的 DebugLog 開到 All、輸出導到一寫就拋例外的串流；先自驗 logger 真的會拋。 */
    private static void explodeLog() {
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                explode();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                explode();
            }
        });
        Throwable self = null;
        try {
            DebugLog.log("[MdcUnseatedVehicleWatchTest] self-check");
        } catch (Throwable e) {
            self = e;
        }
        expect("自驗：DebugLog.log 真的拋例外（" + self + "）", self instanceof IllegalStateException);
    }

    private static void explode() {
        if (oom) {
            throw new OutOfMemoryError("<test> logger oom");
        }
        throw new IllegalStateException("<test> logger exploded");
    }

    /** 照 processServer 的順序：改道點看到舊位置，接著原版 setX／setY。 */
    private static void move(BaseVehicle v, UdpConnection conn, float x0, float y0, float x1, float y1, int n, long stepMs) {
        float px = x0;
        float py = y0;
        for (int i = 1; i <= n; i++) {
            float x = x0 + (x1 - x0) * i / n;
            float y = y0 + (y1 - y0) * i / n;
            MdcUnseatedVehicleWatch.observe(v, x, y, px, py, conn, now + i * stepMs - n * stepMs);
            v.setX(x);
            v.setY(y);
            px = x;
            py = y;
        }
    }

    private static void collide(BaseVehicle v, IsoPlayer p, UdpConnection conn, boolean isCollide) {
        VehicleCollidePacket packet = new VehicleCollidePacket();
        packet.set(v, p, isCollide);
        packet.processServer(null, conn);
    }

    private static BaseVehicle vehicle(int vid, int sqlId, String script, float x, float y, Authorization auth, int pid) throws Exception {
        BaseVehicle v = alloc(BaseVehicle.class);
        v.vehicleId = (short) vid;
        v.sqlId = sqlId;
        field(BaseVehicle.class, "scriptName").set(v, script);
        field(BaseVehicle.class, "passengers").set(v, new BaseVehicle.Passenger[4]);
        field(BaseVehicle.class, "physics").set(v, alloc(CarController.class));
        v.netPlayerAuthorization = auth;
        v.netPlayerId = (short) pid;
        v.setX(x);
        v.setY(y);
        return v;
    }

    private static void seat(BaseVehicle v, int seat, IsoPlayer p) throws Exception {
        BaseVehicle.Passenger passenger = new BaseVehicle.Passenger();
        passenger.character = p;
        ((BaseVehicle.Passenger[]) field(BaseVehicle.class, "passengers").get(v))[seat] = passenger;
        field(IsoGameCharacter.class, "vehicle").set(p, v);
    }

    private static IsoPlayer player(int id, String name, float x, float y) throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        p.onlineId = (short) id;
        p.username = name;
        field(IsoMovingObject.class, "x").setFloat(p, x);
        field(IsoMovingObject.class, "y").setFloat(p, y);
        GameServer.IDToPlayerMap.put((short) id, p);
        return p;
    }

    private static UdpConnection connection(String name, long steamId, IsoPlayer owned) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        field(UdpConnection.class, "userName").set(c, name);
        field(UdpConnection.class, "steamId").setLong(c, steamId);
        field(UdpConnection.class, "vehicleStates").set(c, new TShortObjectHashMap<BaseVehicle.ServerVehicleState>());
        IsoPlayer[] players = new IsoPlayer[4];
        players[0] = owned;
        c.players = players;
        return c;
    }

    private static VehiclePhysicsPacket packet(BaseVehicle v, float x, float y) throws Exception {
        VehiclePhysicsPacket p = new VehiclePhysicsPacket();
        p.vehicleId.set(v);
        field(VehicleInterpolationData.class, "x").setFloat(p, x);
        field(VehicleInterpolationData.class, "y").setFloat(p, y);
        return p;
    }

    private static CarController physics(BaseVehicle v) throws Exception {
        return (CarController) field(BaseVehicle.class, "physics").get(v);
    }

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static String line(ByteArrayOutputStream log, String needle) {
        for (String l : log.toString().split("\n")) {
            if (l.contains("[UnseatedVehicleWatch]") && l.contains(needle)) {
                return l;
            }
        }
        return null;
    }

    private static int count(ByteArrayOutputStream log, String needle) {
        int n = 0;
        for (String l : log.toString().split("\n")) {
            if (l.contains("[UnseatedVehicleWatch]") && l.contains(needle)) {
                n++;
            }
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
        OUT.println((ok ? "uvw pass  " : "uvw FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private MdcUnseatedVehicleWatchTest() {}
}
