package zombie.mdc;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.iso.Vector3;
import zombie.vehicles.BaseVehicle;

/**
 * W13 動物同步範圍對齊的行為驗證。
 *
 * <p>argv：{@code enforce}（預設出貨）／{@code observe}／{@code off}。測試自驗
 * {@code MODE} 與 argv 相符——property 名稱打錯會炸在測試裡，不會默默把 enforce
 * 版跑三遍假綠（與 ItemWeightMemoTest 同紀律）。
 *
 * <pre>
 *   GameServer.java:2771-2772,2788  chunkGridWidth = range; relevantRange = range/2 + 2
 *   vanilla 動物半徑                 (relevantRange-2)*10 == (range/2)*10   // 整數除法
 *   可信奇數載入下界                 (range/2)*8   // stored ∈ {13,15,17,19}
 *   IsoChunkMap.CalcChunkWidth      grid width 強制奇數（自動上限 19）
 *   IsoChunkMap.java:868-878        載具把 chunk 中心沿行進方向前移 speed/5
 * </pre>
 * 因此可信奇數的環帶是 {@code (range/2)*8 .. (range/2)*10}，且必須用<b>整數除法</b>算——
 * {@code range*4}／{@code range*5} 對奇數 range 會算錯（range=13：48/60，不是 52/65）。
 * 偶數 14/16/18 的 loaded rectangle 不對稱，{@code (g/2)*8} 會 over-reach，測試驗證 passthrough。
 *
 * <p>真實 {@code UdpConnection.RelevantTo} 直接參與（不 mock）：判定只讀
 * {@code connectArea}／{@code releventPos}。連線與玩家實例都用
 * {@code Unsafe.allocateInstance} 取得而<b>不跑建構子</b>——vanilla 的
 * {@code PacketsCache} 建構子會拉進 {@code PacketTypes} → {@code AntiCheat}／
 * {@code ServerOptions}（需要先 init 的全域 Rand）整條靜態初始化鏈。繞過後
 * 欄位為 null，由測試自行建立（含 {@code players}，否則載具檢查會 NPE→passthrough）。
 */
public final class AnimalRelevancyGateTest {

    private static int failed;
    /** vanilla 動物半徑：(relevantRange-2)*10，relevantRange = range/2+2 ⇒ (range/2)*10。 */
    private static float vanillaRadius(int range) {
        return (range / 2) * 10.0f;
    }

    /**
     * 對所有連續 player offset {@code p ∈ [0,8)} 都安全的共同半徑下界。刻意不呼叫
     * production 公式；直接從 {@code minChunk = center - range/2} 與
     * {@code maxExclusive = minChunk + range} 的連續 square 邊界推導。
     */
    private static float loadedFloor(int range) {
        final int center = 0;
        final int minChunk = center - range / 2;
        final int maxExclusive = minChunk + range;
        double lowAtP0 = center * 8.0 - minChunk * 8.0;
        double highAsPApproaches8 = maxExclusive * 8.0 - (center * 8.0 + 8.0);
        return (float) Math.min(lowAtP0, highAsPApproaches8);
    }

    /** 玩家在 center chunk 內 offset p 時，到低側 inclusive 邊界的距離。 */
    private static double loadedHalfWidthLow(int range, double p) {
        final int center = 0;
        final int minChunk = center - range / 2;
        return center * 8.0 + p - minChunk * 8.0;
    }

    /** 玩家在 center chunk 內 offset p 時，到高側 exclusive 邊界的距離。 */
    private static double loadedHalfWidthHighExclusive(int range, double p) {
        final int center = 0;
        final int minChunk = center - range / 2;
        final int maxExclusive = minChunk + range;
        return maxExclusive * 8.0 - (center * 8.0 + p);
    }

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "enforce";
        int mode = AnimalRelevancyGate.mode();
        expect("自驗：argv=" + want + " 與 MODE 相符（mode=" + mode + "）", mode == expectedMode(want));

        // 主 enforce 幾何只跑可信奇數 13/15/17/19。
        // 12/20 clamp 多解、14/16/18 偶數不對稱，另段驗 passthrough。
        final int[] ranges = {13, 15, 17, 19};

        for (int range : ranges) {
            float vr = vanillaRadius(range);
            float floor = loadedFloor(range);
            expect("range=" + range + " 幾何前提：載入下界 " + floor + " < vanilla 半徑 " + vr,
                    floor < vr);

            // 載入下界內：三態都必須同步（不得因為對齊而少送保證可見的動物）
            expect("range=" + range + "：載入下界內（dist=" + (floor - 1) + "）一律同步",
                    AnimalRelevancyGate.relevantTo(conn(range), 1000.0f + floor - 1.0f, 1000.0f, vr));

            // 邊界恰等於下界：仍須同步（RelevantTo 是 <=）
            expect("range=" + range + "：dist == 載入下界（" + floor + "）仍同步",
                    AnimalRelevancyGate.relevantTo(conn(range), 1000.0f + floor, 1000.0f, vr));

            // 用原點避免世界座標 ULP 吞掉半徑自身的最小正 float 回歸。
            float justOutside = Math.nextUp(floor);
            boolean justOutsideRelevant = AnimalRelevancyGate.relevantTo(
                    conn(range, 0, 0.0f, 0.0f), justOutside, 0.0f, vr);
            expect("range=" + range + "：原點 Math.nextUp(floor) 依 mode 分流",
                    justOutsideRelevant == (mode != 1));

            // vanilla 半徑外：三態都不同步（本刀只縮不放）
            expect("range=" + range + "：vanilla 半徑外（dist=" + (vr + 1) + "）一律不同步",
                    !AnimalRelevancyGate.relevantTo(conn(range), 1000.0f + vr + 1.0f, 1000.0f, vr));

            // 環帶（floor < dist <= vr）：enforce 擋、observe/off 維持 vanilla
            float ring = floor + 1.0f;
            boolean ringRelevant = AnimalRelevancyGate.relevantTo(conn(range), 1000.0f + ring, 1000.0f, vr);
            expect("range=" + range + "：環帶 dist=" + ring + " 於 mode=" + mode
                    + " 同步決定=" + ringRelevant, ringRelevant == (mode != 1));
        }

        // 計數語意：rejected 先取基準再只跑一次「環帶」判定，避免把遠距 miss 混進來。
        // rejected 的定義是「夾過半徑判定為 false」，本身包含遠距動物——這條只驗環帶那一次。
        final int r15 = 15;
        long rejBefore = AnimalRelevancyGate.rejectedCount();
        long supBefore = AnimalRelevancyGate.suppressedCount();
        AnimalRelevancyGate.relevantTo(conn(r15), 1000.0f + loadedFloor(r15) + 1.0f, 1000.0f, vanillaRadius(r15));
        if (mode == 1) {
            expect("enforce：環帶判定使 rejected +1", AnimalRelevancyGate.rejectedCount() == rejBefore + 1);
            expect("enforce：不記 suppressed", AnimalRelevancyGate.suppressedCount() == supBefore);
        } else if (mode == 2) {
            expect("observe：環帶判定使 suppressed +1（判定差集，不是實際浪費率）",
                    AnimalRelevancyGate.suppressedCount() == supBefore + 1);
            expect("observe：不記 rejected（行為未改）", AnimalRelevancyGate.rejectedCount() == rejBefore);
        } else {
            expect("off：兩個計數器都不動",
                    AnimalRelevancyGate.rejectedCount() == rejBefore
                    && AnimalRelevancyGate.suppressedCount() == supBefore);
        }

        // 遠距動物也會讓 rejected 遞增 —— 這正是「rejected 不等於環帶占比」的證明，
        // 文件若把 rejected/(calls) 當成環帶比例就是錯的
        if (mode == 1) {
            long before = AnimalRelevancyGate.rejectedCount();
            AnimalRelevancyGate.relevantTo(conn(r15), 1000.0f + vanillaRadius(r15) + 50.0f, 1000.0f, vanillaRadius(r15));
            expect("enforce：vanilla 也不會送的遠距動物同樣計入 rejected（故非環帶占比）",
                    AnimalRelevancyGate.rejectedCount() == before + 1);
        }

        // chunkGridWidth 未同步（握手前）：夾不出有效半徑 → passthrough 走 vanilla
        long passBefore = AnimalRelevancyGate.passthroughCount();
        expect("chunkGridWidth=0：維持 vanilla 判定（不誤擋）",
                AnimalRelevancyGate.relevantTo(conn(0), 1000.0f + 70.0f, 1000.0f, vanillaRadius(r15)));
        if (mode != 0) {
            expect("chunkGridWidth=0：計為 passthrough",
                    AnimalRelevancyGate.passthroughCount() == passBefore + 1);
        }

        // 載具排除：client 的 chunk 中心會沿行進方向前移（server 無從得知），
        // 故任一 player 在載具內時整段退回 vanilla——環帶動物照送
        UdpConnection driving = conn(r15);
        driving.players[0] = playerInVehicle();
        long passBeforeVeh = AnimalRelevancyGate.passthroughCount();
        expect("載具內：環帶動物維持 vanilla 同步（不夾，避免前側已載入格被擋）",
                AnimalRelevancyGate.relevantTo(driving,
                        1000.0f + loadedFloor(r15) + 1.0f, 1000.0f, vanillaRadius(r15)));
        if (mode != 0) {
            expect("載具內：計為 passthrough",
                    AnimalRelevancyGate.passthroughCount() == passBeforeVeh + 1);
        }

        // 步行（players 陣列存在但都沒有載具）：正常夾取
        UdpConnection walking = conn(r15);
        walking.players[0] = playerOnFoot();
        boolean walkRing = AnimalRelevancyGate.relevantTo(walking,
                1000.0f + loadedFloor(r15) + 1.0f, 1000.0f, vanillaRadius(r15));
        expect("步行 player 存在時照常夾取（mode=" + mode + " ⇒ " + (mode != 1) + "）",
                walkRing == (mode != 1));

        // coop／split-screen：connectArea 判定矩形會由 RelevantTo 優先命中；
        // 它不代表區內每個 client chunk 已完成 streaming，本刀只維持 vanilla 判定順序。
        UdpConnection coop = conn(r15);
        coop.connectArea[0] = new Vector3(1000.0f / 8.0f, 1000.0f / 8.0f, 40.0f);
        expect("coop connectArea 命中：不受對齊影響（vanilla 語意）",
                AnimalRelevancyGate.relevantTo(coop,
                        1000.0f + loadedFloor(r15) + 1.0f, 1000.0f, vanillaRadius(r15)));

        // clamp 邊界：GameServer 存的是 max(12, min(20, raw))，所以 stored=12 ⟺ raw<=12、
        // stored=20 ⟺ raw>=20，兩者都無法還原 client 真實寬度 ⇒ 必須 passthrough。
        // 少了這道檢查，raw=11 的 client 會被當成 12（實際共同半寬 40、卻算成 48）。
        for (int boundary : new int[] {12, 20}) {
            long passBefore2 = AnimalRelevancyGate.passthroughCount();
            float vrB = vanillaRadius(boundary);
            boolean sent = AnimalRelevancyGate.relevantTo(conn(boundary),
                    1000.0f + loadedFloor(boundary) + 1.0f, 1000.0f, vrB);
            expect("clamp 邊界 stored=" + boundary + "：環帶動物維持 vanilla（不可夾）", sent);
            if (mode != 0) {
                expect("clamp 邊界 stored=" + boundary + "：計為 passthrough",
                        AnimalRelevancyGate.passthroughCount() == passBefore2 + 1);
            }
        }

        // 偶數 14/16/18：loaded rectangle 不對稱，(g/2)*8 會 over-reach。
        // 三模式都維持 vanilla；非 off 計 passthrough +1。
        // 動物放在 naive+1：parity guard 移除後 enforce 會擋，這條會紅。
        for (int even : new int[] {14, 16, 18}) {
            long passBeforeEven = AnimalRelevancyGate.passthroughCount();
            float vrE = vanillaRadius(even);
            float naive = (even / 2) * 8.0f;
            boolean sent = AnimalRelevancyGate.relevantTo(conn(even),
                    1000.0f + naive + 1.0f, 1000.0f, vrE);
            expect("偶數 stored=" + even + "：環帶動物維持 vanilla（parity guard）", sent);
            if (mode != 0) {
                expect("偶數 stored=" + even + "：計為 passthrough",
                        AnimalRelevancyGate.passthroughCount() == passBeforeEven + 1);
            }
            boolean overReach = false;
            for (double p : new double[] {0.0, 0.5, 7.999}) {
                double low = loadedHalfWidthLow(even, p);
                double highExclusive = loadedHalfWidthHighExclusive(even, p);
                if (naive > low || naive >= highExclusive) {
                    overReach = true;
                    break;
                }
            }
            expect("偶數 stored=" + even + "：(g/2)*8 超出至少一側的連續安全邊界",
                    overReach);
        }

        // 連續 offset 幾何自檢：共同下界不得越過低側 inclusive／高側 exclusive 邊界。
        // 代表點涵蓋 p=0、chunk 中段，以及靠近 8 但仍在本 chunk 內的 7.999。
        for (int range : new int[] {13, 15, 17, 19}) {
            float floor = loadedFloor(range);
            for (double p : new double[] {0.0, 0.5, 7.999}) {
                expect("range=" + range + " p=" + p + "：共同下界留在連續 loaded 邊界內",
                        floor <= loadedHalfWidthLow(range, p)
                        && floor < loadedHalfWidthHighExclusive(range, p));
            }
        }

        // 雙軸對稱：RelevantTo 是軸對齊方形、兩軸獨立比較，四個方向的決定必須一致
        for (int range : new int[] {13, 19}) {
            float vrA = vanillaRadius(range);
            float floor = loadedFloor(range);
            for (int axis = 0; axis < 4; axis++) {
                float ux = axis == 0 ? 1.0f : axis == 1 ? -1.0f : 0.0f;
                float uy = axis == 2 ? 1.0f : axis == 3 ? -1.0f : 0.0f;
                boolean ring = AnimalRelevancyGate.relevantTo(conn(range),
                        1000.0f + ux * (floor + 1.0f), 1000.0f + uy * (floor + 1.0f), vrA);
                expect("range=" + range + " 方向" + axis + "：環帶決定與 mode 一致",
                        ring == (mode != 1));
                boolean inside = AnimalRelevancyGate.relevantTo(conn(range),
                        1000.0f + ux * (floor - 1.0f), 1000.0f + uy * (floor - 1.0f), vrA);
                expect("range=" + range + " 方向" + axis + "：下界內一律同步", inside);
            }
        }

        // 可信奇數對角線：同時覆蓋 RelevantTo 兩軸（|dx| 與 |dy| 都在 inside／ring）
        {
            final int diagRange = 15;
            float vrD = vanillaRadius(diagRange);
            float floor = loadedFloor(diagRange);
            boolean diagInside = AnimalRelevancyGate.relevantTo(conn(diagRange),
                    1000.0f + (floor - 1.0f), 1000.0f + (floor - 1.0f), vrD);
            expect("對角線：下界內（兩軸）一律同步", diagInside);
            boolean diagRing = AnimalRelevancyGate.relevantTo(conn(diagRange),
                    1000.0f + (floor + 1.0f), 1000.0f + (floor + 1.0f), vrD);
            expect("對角線：環帶（兩軸）決定與 mode 一致", diagRing == (mode != 1));
        }

        // 非零 releventPos index：RelevantTo 對 4 個 index 做 OR，index 0 為空時仍須命中
        {
            final int idxRange = 15;
            float vrI = vanillaRadius(idxRange);
            float floor = loadedFloor(idxRange);
            UdpConnection split = conn(idxRange, 3, 1000.0f, 1000.0f);
            boolean idxInside = AnimalRelevancyGate.relevantTo(split,
                    1000.0f + (floor - 1.0f), 1000.0f, vrI);
            expect("releventPos[3]：下界內一律同步", idxInside);
            boolean idxRing = AnimalRelevancyGate.relevantTo(split,
                    1000.0f + (floor + 1.0f), 1000.0f, vrI);
            expect("releventPos[3]：環帶決定與 mode 一致", idxRing == (mode != 1));
        }

        expect("helper 自身無異常（anomalies=0）", AnimalRelevancyGate.anomalyCount() == 0L);

        if (failed > 0) {
            System.out.println("animal-relevancy FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("animal-relevancy OK  mode=" + want
                + "：可信奇數／偶數 passthrough／邊界／環帶／遠距計數／載具排除／步行／coop 全數通過");
    }
    private static int expectedMode(String want) {
        switch (want) {
            case "off":
                return 0;
            case "observe":
                return 2;
            default:
                return 1;
        }
    }

    /** 標準連線：玩家 index 0 在 (1000,1000)。 */
    private static UdpConnection conn(int chunkGridWidth) throws Exception {
        return conn(chunkGridWidth, 0, 1000.0f, 1000.0f);
    }

    /**
     * 最小連線：繞過建構子（見類別註解），補齊 RelevantTo 與載具檢查會讀的三個陣列。
     * 玩家在 {@code releventPos[playerIndex]} 的 (x,y)；其餘 index 為 null。
     */
    private static UdpConnection conn(int chunkGridWidth, int playerIndex, float x, float y) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.players = new IsoPlayer[4];
        c.releventPos[playerIndex] = new Vector3(x, y, 0.0f);
        c.setChunkGridWidth(chunkGridWidth);
        return c;
    }

    /** 只需要 getVehicle() 回非 null（IsoGameCharacter.getVehicle 是純欄位讀取）。 */
    private static IsoPlayer playerInVehicle() throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        java.lang.reflect.Field vehicle = findField(p.getClass(), "vehicle");
        vehicle.setAccessible(true);
        vehicle.set(p, alloc(BaseVehicle.class));
        return p;
    }

    private static IsoPlayer playerOnFoot() throws Exception {
        return alloc(IsoPlayer.class);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "arg pass  " : "arg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalRelevancyGateTest() {}
}
