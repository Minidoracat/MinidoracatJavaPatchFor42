package zombie.mdc;

import java.lang.reflect.Field;
import java.util.Random;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.iso.Vector3;
import zombie.vehicles.BaseVehicle;

/**
 * W93：每條連線的常數提到迴圈外（{@code beginConnection} 快取對齊半徑與載具判定）。真 {@code UdpConnection.RelevantTo}，連線與玩家以
 * Unsafe 配置（同 {@code AnimalRelevancyGateTest}）。
 *
 * <ul>
 *   <li>等價：grid 0／12–20（奇偶、clamp 邊界、未同步）× 步行／載具／沒有玩家 × 有無 coop connectArea，每種 400 個隨機動物座標：
 *       {@code beginConnection} 過的連線 A 與一模一樣但沒 begin 過的連線 B（走每對重算），判定逐筆相同、rejected／passthrough／suppressed
 *       的增量相同。</li>
 *   <li>on 每輪都算快取並真的使用（hoistHits 增加）；observe 只在每 16 次 begin 抽到的那輪比對（60 輪抽 3 輪、audits 恰為抽到輪數 × 400、
 *       hits 0；沒抽到的輪不比對，判定已含在上面逐筆相同）；off 都不動。</li>
 *   <li>換連線：begin(13) 後 begin(19)，19 的判定與重算相同；回頭判 13（不是快取的那條）照原版重算、不算命中。</li>
 *   <li>違反前提（begin 之後同一輪內玩家上車）：observe（抽到的輪）第一對就記 hoistMismatch、判定照重算；on 在每 4096 對的抽查抓到並停用，
 *       之後每對重算；off 不比對。再 begin 一次（下一輪）快取更新。</li>
 * </ul>
 *
 * <p>用法：{@code AnimalRelevancyHoistTest off|observe|on}（{@code -Dmdc.animalRelevancyHoist} 要相符；W13 本身是預設 enforce）。
 */
public final class AnimalRelevancyHoistTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args[0];
        int want = mode.equals("off") ? AnimalRelevancyGate.HOIST_OFF : mode.equals("on") ? AnimalRelevancyGate.HOIST_ON
                : AnimalRelevancyGate.HOIST_OBSERVE;
        check("模式與參數一致（hoist=" + AnimalRelevancyGate.hoistModeName() + "，W13 mode=" + AnimalRelevancyGate.mode() + "）",
                AnimalRelevancyGate.HOIST == want && AnimalRelevancyGate.mode() == 1);
        Random rnd = new Random(93);
        boolean allSame = true;
        int scenarios = 0;
        long hitsBefore = AnimalRelevancyGate.hoistHitsForTest();
        long auditsBefore = AnimalRelevancyGate.hoistAuditsForTest();
        long beginsBefore = AnimalRelevancyGate.hoistBeginsForTest();
        long roundsBefore = AnimalRelevancyGate.hoistRoundsForTest();
        for (int grid : new int[]{0, 12, 13, 14, 15, 16, 17, 18, 19, 20}) {
            for (int who = 0; who < 3; who++) {          // 0 步行、1 載具、2 沒有玩家
                for (int coop = 0; coop < 2; coop++) {
                    UdpConnection a = conn(grid, who, coop == 1);
                    UdpConnection b = conn(grid, who, coop == 1);
                    float vr = (grid / 2) * 10.0F;
                    float[] xs = new float[400];
                    float[] ys = new float[400];
                    for (int i = 0; i < xs.length; i++) {
                        xs[i] = 1000.0F + (rnd.nextFloat() * 2 - 1) * (vr + 20);
                        ys[i] = 1000.0F + (rnd.nextFloat() * 2 - 1) * (vr + 20);
                    }
                    AnimalRelevancyGate.beginConnection(a);
                    long[] c0 = counters();
                    boolean[] ra = new boolean[xs.length];
                    for (int i = 0; i < xs.length; i++) {
                        ra[i] = AnimalRelevancyGate.relevantTo(a, xs[i], ys[i], vr);
                    }
                    long[] c1 = counters();
                    boolean same = true;
                    for (int i = 0; i < xs.length; i++) {
                        same &= AnimalRelevancyGate.relevantTo(b, xs[i], ys[i], vr) == ra[i];
                    }
                    long[] c2 = counters();
                    for (int k = 0; k < c0.length; k++) {
                        same &= c1[k] - c0[k] == c2[k] - c1[k];
                    }
                    if (!same) {
                        System.out.println("  不同：grid=" + grid + " who=" + who + " coop=" + coop);
                    }
                    allSame &= same;
                    scenarios++;
                }
            }
        }
        check(scenarios + " 種情境（grid 0／12–20 × 步行／載具／沒有玩家 × coop）各 400 個動物：begin 過的連線與每對重算的判定逐筆相同、"
                + "rejected／passthrough／suppressed 增量相同", allSame);
        long hits = AnimalRelevancyGate.hoistHitsForTest() - hitsBefore;
        long audits = AnimalRelevancyGate.hoistAuditsForTest() - auditsBefore;
        long begins = AnimalRelevancyGate.hoistBeginsForTest() - beginsBefore;
        long rounds = AnimalRelevancyGate.hoistRoundsForTest() - roundsBefore;
        long every = AnimalRelevancyGate.HOIST_OBSERVE_EVERY;
        long sampled = (beginsBefore + scenarios) / every - beginsBefore / every;
        if (mode.equals("on")) {
            check("on：每輪都算快取（rounds=" + rounds + "／begins=" + begins + "）且真的被用（hits=" + hits + "，每 4096 對抽查 audits=" + audits + "）",
                    begins == scenarios && rounds == scenarios && hits > scenarios * 390L && audits > 0 && audits < 10);
        } else if (mode.equals("observe")) {
            check("observe：每 " + every + " 次 begin 抽一輪（" + begins + " 次 begin 抽 " + rounds + " 輪，預期 " + sampled + "），抽到的輪每對比對"
                            + "（audits=" + audits + "＝輪數×400）、沒抽到的輪不比對、不用快取（hits=" + hits + "）",
                    begins == scenarios && sampled == 3 && rounds == sampled && audits == rounds * 400L && hits == 0);
        } else {
            check("off：不快取不比對（begins=" + begins + " audits=" + audits + " hits=" + hits + "）", begins == 0 && rounds == 0 && hits == 0 && audits == 0);
        }
        check("hoistMismatch 0、沒有停用、anomalies 0", AnimalRelevancyGate.hoistMismatchForTest() == 0
                && !AnimalRelevancyGate.hoistDisabledForTest() && AnimalRelevancyGate.anomalyCount() == 0);

        // 換連線：快取跟著最近一次 begin
        UdpConnection c13 = conn(13, 0, false);
        UdpConnection c19 = conn(19, 0, false);
        UdpConnection f13 = conn(13, 0, false);
        UdpConnection f19 = conn(19, 0, false);
        float ring19 = (19 / 2) * 8.0F + 3.0F;
        float ring13 = (13 / 2) * 8.0F + 3.0F;
        AnimalRelevancyGate.beginConnection(c13);
        AnimalRelevancyGate.beginConnection(c19);
        long h0 = AnimalRelevancyGate.hoistHitsForTest();
        boolean sw = AnimalRelevancyGate.relevantTo(c19, 1000.0F + ring19, 1000.0F, 90.0F)
                == AnimalRelevancyGate.relevantTo(f19, 1000.0F + ring19, 1000.0F, 90.0F);
        long h1 = AnimalRelevancyGate.hoistHitsForTest();
        sw &= AnimalRelevancyGate.relevantTo(c13, 1000.0F + ring13, 1000.0F, 60.0F)
                == AnimalRelevancyGate.relevantTo(f13, 1000.0F + ring13, 1000.0F, 60.0F);
        long h2 = AnimalRelevancyGate.hoistHitsForTest();
        check("換連線：begin(13)→begin(19) 後 19 的判定與重算相同" + (mode.equals("on") ? "且命中快取" : "")
                        + "；不是快取那條的 13 照原版重算、不算命中",
                sw && h1 - h0 == (mode.equals("on") ? 1 : 0) && h2 == h1);

        // 違反前提：begin 之後同一輪內玩家上車
        UdpConnection v = conn(15, 0, false);
        UdpConnection fresh = conn(15, 1, false);
        beginRound(v);
        v.players[0] = fresh.players[0];
        float ring15 = (15 / 2) * 8.0F + 3.0F;
        int pairs = 0;
        boolean firstMatchesFresh = AnimalRelevancyGate.relevantTo(v, 1000.0F + ring15, 1000.0F, 70.0F);
        pairs++;
        while (!AnimalRelevancyGate.hoistDisabledForTest() && pairs < 10_000) {
            AnimalRelevancyGate.relevantTo(v, 1000.0F + ring15, 1000.0F, 70.0F);
            pairs++;
        }
        long mismatch = AnimalRelevancyGate.hoistMismatchForTest();
        if (mode.equals("observe")) {
            check("observe：上車後第一對就記 hoistMismatch、判定照重算（載具內環帶照送）、不停用（mismatch=" + mismatch + "）",
                    mismatch == pairs && firstMatchesFresh && !AnimalRelevancyGate.hoistDisabledForTest());
        } else if (mode.equals("on")) {
            check("on：上車後先用到舊快取（環帶擋下），在抽查抓到並停用（第 " + pairs + " 對，mismatch=" + mismatch + "）",
                    !firstMatchesFresh && mismatch == 1 && pairs <= 4096 && AnimalRelevancyGate.hoistDisabledForTest());
            boolean after = AnimalRelevancyGate.relevantTo(v, 1000.0F + ring15, 1000.0F, 70.0F);
            long hh = AnimalRelevancyGate.hoistHitsForTest();
            AnimalRelevancyGate.beginConnection(v);
            AnimalRelevancyGate.relevantTo(v, 1000.0F + ring15, 1000.0F, 70.0F);
            check("on：停用後每對重算（載具內環帶照送），再 begin 也不再快取", after && AnimalRelevancyGate.hoistHitsForTest() == hh);
        } else {
            check("off：不比對、判定照重算（mismatch=" + mismatch + "）", mismatch == 0 && firstMatchesFresh);
        }
        if (!mode.equals("on")) {
            beginRound(v);
            long m0 = AnimalRelevancyGate.hoistMismatchForTest();
            AnimalRelevancyGate.relevantTo(v, 1000.0F + ring15, 1000.0F, 70.0F);
            check(mode + "：下一輪 begin 後快取更新，不再 mismatch", AnimalRelevancyGate.hoistMismatchForTest() == m0);
        }
        finish(mode);
    }

    /** begin 一輪且確定這輪有算快取：observe 沒抽到就再 begin，直到抽到（最多 16 次）；on／off 只 begin 一次。 */
    static void beginRound(UdpConnection c) {
        long r = AnimalRelevancyGate.hoistRoundsForTest();
        AnimalRelevancyGate.beginConnection(c);
        while (AnimalRelevancyGate.HOIST == AnimalRelevancyGate.HOIST_OBSERVE && AnimalRelevancyGate.hoistRoundsForTest() == r) {
            AnimalRelevancyGate.beginConnection(c);
        }
    }

    static long[] counters() {
        return new long[]{AnimalRelevancyGate.rejectedCount(), AnimalRelevancyGate.passthroughCount(), AnimalRelevancyGate.suppressedCount()};
    }

    /** who：0 步行、1 載具、2 沒有玩家。coop：connectArea[0] 蓋住玩家附近。 */
    static UdpConnection conn(int grid, int who, boolean coop) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.players = new IsoPlayer[4];
        c.releventPos[0] = new Vector3(1000.0F, 1000.0F, 0.0F);
        if (coop) {
            c.connectArea[0] = new Vector3(1000.0F / 8.0F, 1000.0F / 8.0F, 6.0F);
        }
        if (who != 2) {
            IsoPlayer p = alloc(IsoPlayer.class);
            if (who == 1) {
                Field vehicle = zombie.characters.IsoGameCharacter.class.getDeclaredField("vehicle");
                vehicle.setAccessible(true);
                vehicle.set(p, alloc(BaseVehicle.class));
            }
            c.players[0] = p;
        }
        c.setChunkGridWidth(grid);
        return c;
    }

    private static void finish(String mode) {
        System.out.println(failed == 0 ? "AnimalRelevancyHoistTest OK (" + mode + ")" : "AnimalRelevancyHoistTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private AnimalRelevancyHoistTest() {}
}
