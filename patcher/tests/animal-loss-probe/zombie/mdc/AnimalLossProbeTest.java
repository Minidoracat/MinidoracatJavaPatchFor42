package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * W60 動物遺失／複製觀測與 W109 長大撞同號只刪同物種的行為驗證（獨立 JVM；{@code ENABLED}／{@code SPARE} 是 static final）。
 * 用法：{@code AnimalLossProbeTest on|off|spare-off}（off 需 {@code -Dmdc.animalLossProbe=off}、spare-off 需
 * {@code -Dmdc.animalGrowIdClash=off}，測試自驗）。
 *
 * <p>放下動物：照 SmokeCheck 鎖住的 bytecode 順序呼叫 dropCopy → dropLookup → dropRegister，用真
 * {@link AnimalInstanceManager}。鎖：原方法各恰一次、參數與回傳原樣；手上的動物就是前持有者（從地上撿起再放下）
 * 不記；前持有者是別隻（雞舍載入的動物沒有登記 onlineID，查到持有同一個 ID 的動物）記 drop-evict，帶被移走那隻
 * 的種類、ID、位置；手上的動物放下後仍在世界上記 drop-dup；描述時例外只計 anomalies；原方法例外原樣上拋。
 *
 * <p>（chunk 卸載存檔中重載的觀測 2026-11-10 隨 42.22.2 退役，測試一併移除。）
 *
 * <p>長大：checkForChickenpocalypse 回 true 時記 grow-dup，帶 ±4 格內同 ID 的另一隻（真 IsoCell／IsoGridSquare，
 * ServerMap 替身回傳格子）；回 false 不記。off：原方法照常呼叫，不記帳；W60 觀測零 log 行。
 *
 * <p>W109：替身以 {@code realCheck} 走真原版 checkForChickenpocalypse（delete 只記次數），每個佈局先直呼原版當負對照，
 * 再經 helper：on／off 時同號者全是確定異種就放行（回 false、原版 0 次、delete 0、grow-spared 一行），其餘與原版
 * 逐案相同；spare-off 時一律與原版相同。另以固定種子 200 組隨機佈局差分。
 */
public final class AnimalLossProbeTest {
    private static final String TAG = "[MinidoracatJavaPatch][AnimalLossProbe]";

    private static int failed;
    private static ByteArrayOutputStream logBuf;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        boolean on = !"off".equals(mode);
        boolean spareOn = !"spare-off".equals(mode);
        zombie.core.random.RandStandard.INSTANCE.init();
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);

        expect("自驗：ENABLED、SPARE 與 argv 相符（" + mode + "）",
                AnimalLossProbe.ENABLED == on && AnimalLossProbe.SPARE == spareOn
                        && ("on".equals(mode) || "off".equals(mode) || "spare-off".equals(mode)));
        expect("parseEnabled：null/1/on 開，0/off/OFF 關", AnimalLossProbe.parseEnabled(null)
                && AnimalLossProbe.parseEnabled("1") && AnimalLossProbe.parseEnabled("on")
                && !AnimalLossProbe.parseEnabled("0") && !AnimalLossProbe.parseEnabled(" off")
                && !AnimalLossProbe.parseEnabled("OFF"));

        AnimalInstanceManager aim = AnimalInstanceManager.getInstance();

        // ---- 原版負對照：未改道的放下流程直接呼叫原方法，不經本類 ----
        TestAnimal vSrc = animal("hen", 11, 1f, 2f);
        aim.add(vSrc, (short) 501);
        TestAnimal vDst = animal("hen", 0, 1f, 2f);
        vDst.copyFrom(vSrc);
        IsoAnimal vPrev = aim.get((short) 501);
        aim.add(vDst, (short) 501);
        expect("原版負對照：直接呼叫 copyFrom/get/add，本類 drops=0、零 log 行",
                vDst.copyCalls == 1 && vPrev == vSrc && AnimalLossProbe.drops.get() == 0L && rows().isEmpty());

        // ---- 放下：手上的動物就是前持有者（地上撿起再放下） ----
        TestAnimal src = animal("cockerel", 4321, 10f, 20f);
        aim.add(src, (short) 502);
        TestAnimal dst = animal("cockerel", 0, 10f, 20f);
        IsoAnimal prev = drop(aim, dst, src, (short) 502);
        expect("正常放下：copyFrom 恰一次且參數是手上的動物、get 回前持有者、add 登記新動物",
                dst.copyCalls == 1 && dst.copyArg == src && prev == src && aim.get((short) 502) == dst);

        // ---- 放下：雞舍載入的動物，onlineID 停在 1，被另一隻持有 ----
        TestAnimal bystander = animal("hen", 1357, 1200f, 3400f);
        aim.add(bystander, (short) 1);
        TestAnimal hutched = animal("cockerel", 4321, 0f, 0f);
        hutched.setOnlineID((short) 1);
        TestAnimal dst2 = animal("cockerel", 0, 5600f, 7800f);
        IsoAnimal prev2 = drop(aim, dst2, hutched, (short) 1);
        expect("移走別隻：get 原樣回傳那隻、add 照常登記", prev2 == bystander && aim.get((short) 1) == dst2);

        // ---- 放下：手上的動物放下後仍在世界上 ----
        TestAnimal ghost = animal("hen", 2468, 30f, 40f);
        ghost.inWorld = true;
        TestAnimal dst3 = animal("hen", 0, 31f, 41f);
        IsoAnimal prev3 = drop(aim, dst3, ghost, (short) 503);
        expect("來源仍在世界上：get 回 null、add 照常登記", prev3 == null && aim.get((short) 503) == dst3);

        // ---- 描述時例外：只計 anomalies，原方法照常 ----
        TestAnimal brokenOther = animal("hen", 1, 0f, 0f);
        brokenOther.throwOnType = true;
        aim.add(brokenOther, (short) 504);
        TestAnimal src4 = animal("hen", 2, 0f, 0f);
        TestAnimal dst4 = animal("hen", 0, 0f, 0f);
        IsoAnimal prev4 = drop(aim, dst4, src4, (short) 504);
        expect("描述前持有者時例外：get 照樣回傳、add 照常登記", prev4 == brokenOther && aim.get((short) 504) == dst4);

        // ---- 原方法例外原樣上拋 ----
        TestAnimal throwingDst = animal("hen", 0, 0f, 0f);
        throwingDst.copyThrows = new IllegalStateException("copy");
        Throwable copyEx = null;
        try {
            AnimalLossProbe.dropCopy(throwingDst, src4);
        } catch (IllegalStateException e) {
            copyEx = e;
        }
        expect("copyFrom 的例外原樣上拋（同一個物件）", copyEx == throwingDst.copyThrows);
        AnimalLossProbe.dropRegister(aim, throwingDst, (short) 505);   // 收掉上一個放下的暫存

        // ---- 長大 ----
        TestServerMap map = alloc(TestServerMap.class);
        ServerMap.instance = map;
        IsoWorld world = alloc(IsoWorld.class);
        world.currentCell = alloc(IsoCell.class);
        IsoWorld.instance = world;
        IsoGridSquare home = square(map, 100, 100, 0);
        TestAnimal chick = animal("chick", 9753, 100f, 100f);
        chick.sq = home;
        TestAnimal twin = animal("chick", 9753, 102f, 97f);
        square(map, 102, 97, 0).getMovingObjects().add(twin);
        TestAnimal stranger = animal("chick", 1234, 99f, 99f);
        square(map, 99, 99, 0).getMovingObjects().add(stranger);
        TestAnimal grown = animal("hen", 9753, 100f, 100f);
        chick.chickenResult = true;
        boolean deleted = AnimalLossProbe.growDuplicate(chick, grown);
        expect("長大撞到同 ID：回傳原值 true、原方法恰一次且參數是新動物",
                deleted && chick.chickenCalls == 1 && chick.chickenArg == grown);
        TestAnimal calf = animal("calf", 77, 50f, 50f);
        calf.sq = square(map, 50, 50, 0);
        boolean kept = AnimalLossProbe.growDuplicate(calf, animal("cow", 77, 50f, 50f));
        expect("長大沒撞到：回傳原值 false", !kept && calf.chickenCalls == 1);

        if (on) {
            runOnAsserts();
        } else {
            expect("off：不記帳", AnimalLossProbe.drops.get() == 0L && AnimalLossProbe.growDups.get() == 0L
                    && AnimalLossProbe.anomalies.get() == 0L);
            expect("off：W109 案例之前整個 JVM 零 log 行", rows().isEmpty());
        }

        runGrowIdClash(map, on, spareOn);

        for (String row : rows()) {
            System.out.println("log  " + row.substring(row.indexOf(TAG)));
        }
        if (failed > 0) {
            System.out.println("animal-loss-probe FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("animal-loss-probe OK mode=" + mode);
    }

    private static void runOnAsserts() {
        expect("on：首次生效行恰一行", rowsContaining("首次生效").size() == 1);
        List<String> evicts = rowsContaining("drop-evict#");
        expect("on：drop-evict 兩行：第一行帶放下位置、放下的動物、onlineId=1、被移走那隻的種類 ID 位置；描述失敗那次寫 evicted=?",
                evicts.size() == 2 && evicts.get(0).contains("at=5600,7800,0 dropped=cockerel#4321 onlineId=1")
                        && evicts.get(0).contains("evicted=hen#1357 pos=1200,3400,0 inWorld=0 hutch=0 vehicle=0 dead=0")
                        && evicts.get(1).contains("dropped=hen#2 onlineId=504 evicted=?"));
        List<String> dups = rowsContaining("drop-dup#");
        expect("on：drop-dup 恰一行，帶仍在世界上的來源與 prev=none",
                dups.size() == 1 && dups.get(0).contains("dropped=hen#2468 onlineId=503")
                        && dups.get(0).contains("source=hen#2468 pos=30,40,0 inWorld=1") && dups.get(0).contains("prev=none"));
        List<String> grows = rowsContaining("grow-dup#");
        expect("on：grow-dup 恰一行，帶長大前的動物、新種類與同 ID 的另一隻",
                grows.size() == 1 && grows.get(0).contains("animal=chick#9753 pos=100,100,0")
                        && grows.get(0).contains("newType=hen") && grows.get(0).contains("twin=chick#9753 pos=102,97,0"));
        expect("on：計數 drops=5 evicted=2 dropDups=1 growDups=1 anomalies=1",
                AnimalLossProbe.drops.get() == 5L && AnimalLossProbe.evicted.get() == 2L
                        && AnimalLossProbe.dropDups.get() == 1L && AnimalLossProbe.growDups.get() == 1L
                        && AnimalLossProbe.anomalies.get() == 1L);
    }

    /** {@link #placed} 的 group 參數：不設 adef（null 是「有 adef、group null」）。 */
    private static final String NO_DEF = new String("<no adef>");

    /** W109 案例表、例外隔離、log 行與固定種子隨機差分。每個案例用自己的一塊區域（相距 100 格，掃描只看 ±4）。 */
    private static void runGrowIdClash(TestServerMap map, boolean on, boolean spareOn) throws Exception {
        long sparedBefore = AnimalLossProbe.growSpared.get();

        // ---- 案例表：clash＝同號者全是和長大前後都確定異種（W109 該放行）；vanilla＝真原版直呼的結果 ----
        TestAnimal p1 = placed(map, "chick", "chicken", 5001, 2100, 2000, 0);
        placed(map, "rabkitten", "rabbit", 5001, 2102, 1997, 0);
        growCase("異種同號（chick 對 rabkitten）", p1, grownAt(map, p1, "hen", "chicken"), true, true);

        TestAnimal p2 = placed(map, "chick", "chicken", 5002, 2200, 2000, 0);
        placed(map, "chick", "chicken", 5002, 2201, 2001, 0);
        growCase("同物種同號（chick 對 chick）", p2, grownAt(map, p2, "hen", "chicken"), false, true);

        TestAnimal p3 = placed(map, "lamb", "sheep", 5003, 2300, 2000, 0);
        placed(map, "ewe", "SHEEP", 5003, 2298, 2003, 0);
        growCase("同物種同號（lamb 對 ewe，group 大小寫不同）", p3, grownAt(map, p3, "ewe", "sheep"), false, true);

        TestAnimal p4 = placed(map, "chick", "chicken", 5004, 2400, 2000, 0);
        placed(map, "rabkitten", "rabbit", 5004, 2401, 2000, 0);
        placed(map, "chick", "chicken", 5004, 2397, 1998, 0);
        growCase("同號者一異一同", p4, grownAt(map, p4, "hen", "chicken"), false, true);

        TestAnimal p5 = placed(map, "chick", "chicken", 5005, 2500, 2000, 0);
        placed(map, "rabkitten", "rabbit", 5099, 2501, 2000, 0);
        growCase("沒有同號", p5, grownAt(map, p5, "hen", "chicken"), false, false);

        TestAnimal p6 = placed(map, "chick", NO_DEF, 5006, 2600, 2000, 0);
        placed(map, "rabkitten", "rabbit", 5006, 2601, 2000, 0);
        growCase("長大前的動物沒有 adef", p6, grownAt(map, p6, "hen", "chicken"), false, true);

        TestAnimal p7 = placed(map, "chick", "chicken", 5007, 2700, 2000, 0);
        placed(map, "rabkitten", "rabbit", 5007, 2701, 2000, 0);
        growCase("長大後的動物 group null", p7, grownAt(map, p7, "hen", null), false, true);

        TestAnimal p8 = placed(map, "chick", "chicken", 5008, 2800, 2000, 0);
        placed(map, "rabkitten", "", 5008, 2801, 2000, 0);
        growCase("同號者 group 空字串", p8, grownAt(map, p8, "hen", "chicken"), false, true);

        TestAnimal p9 = placed(map, "chick", "chicken", 5009, 2900, 2000, 0);
        placed(map, "rabkitten", "rabbit", 5009, 2901, 2000, 0);
        growCase("同號者與長大前異種、與長大後同種", p9, grownAt(map, p9, "rabbit", "rabbit"), false, true);

        TestAnimal p10 = placed(map, "chick", "chicken", 5010, 3000, 2000, 0);
        placed(map, "rabkitten", "rabbit", 5010, 2996, 2000, 0);
        growCase("邊界：異種同號在 dx=-4（算）", p10, grownAt(map, p10, "hen", "chicken"), true, true);

        TestAnimal p11 = placed(map, "chick", "chicken", 5011, 3100, 2000, 0);
        placed(map, "chick", "chicken", 5011, 3104, 2000, 0);
        placed(map, "chick", "chicken", 5011, 3100, 2004, 0);
        growCase("邊界：同物種同號在 dx=+4、dy=+4（不算）", p11, grownAt(map, p11, "hen", "chicken"), false, false);

        TestAnimal p12 = placed(map, "chick", "chicken", 5012, 3200, 2000, 0);
        placed(map, "chick", "chicken", 5012, 3200, 1996, 0);
        growCase("邊界：同物種同號在 dy=-4（算）", p12, grownAt(map, p12, "hen", "chicken"), false, true);

        TestAnimal p13 = placed(map, "chick", "chicken", 5013, 3300, 2000, 0);
        placed(map, "chick", "chicken", 5013, 3301, 2000, 1);
        growCase("邊界：同物種同號在不同層（不算）", p13, grownAt(map, p13, "hen", "chicken"), false, false);

        TestAnimal p14 = placed(map, "chick", "chicken", 5014, 3400, 2000, 0);
        placed(map, "chick", "chicken", 5014, 3401, 2000, 0).onHook = true;
        growCase("邊界：同物種同號掛在鉤上（不算）", p14, grownAt(map, p14, "hen", "chicken"), false, false);

        TestAnimal p15 = animal("chick", 5015, 3500f, 2000f);
        growCase("getSquare()==null", p15, animal("hen", 5015, 3500f, 2000f), false, false);

        // ---- 例外隔離（放行開著才有判定）----
        if (spareOn) {
            TestAnimal p16 = placed(map, "chick", "chicken", 5016, 3600, 2000, 0);
            TestAnimal t16 = placed(map, "rabkitten", "rabbit", 5016, 3601, 2000, 0);
            TestAnimal g16 = grownAt(map, p16, "hen", "chicken");
            int[] v16 = grow(p16, g16, false);
            long anom = AnimalLossProbe.anomalies.get();
            t16.idThrows = 1;   // 只讓 W109 的判定讀到例外，原版接著讀就正常
            int[] h16 = grow(p16, g16, true);
            expect("判定拋例外：anomalies+1、交回原版照刪（原版恰 1 次、回 true、delete 1）",
                    v16[0] == 1 && h16[0] == 1 && h16[1] == 1 && h16[2] == 1 && t16.idThrows == 0
                            && AnimalLossProbe.anomalies.get() == anom + 1);

            TestAnimal p17 = placed(map, "chick", "chicken", 5017, 3700, 2000, 0);
            placed(map, "rabkitten", "rabbit", 5017, 3701, 2000, 0);
            TestAnimal g17 = grownAt(map, p17, "hen", "chicken");
            long anom17 = AnimalLossProbe.anomalies.get();
            long spared17 = AnimalLossProbe.growSpared.get();
            p17.throwOnType = true;   // describe(parent) 拋例外
            int[] h17 = grow(p17, g17, true);
            expect("log 拋例外：仍放行（回 false、原版 0 次、delete 0）、growSpared+1、anomalies+1",
                    h17[0] == 0 && h17[1] == 0 && h17[2] == 0 && AnimalLossProbe.growSpared.get() == spared17 + 1
                            && AnimalLossProbe.anomalies.get() == anom17 + 1);
        }

        // ---- log 行（隨機差分之前：差分會用完每分鐘 30 行的額度）----
        List<String> spared = rowsContaining("grow-spared#");
        if (spareOn) {
            expect("放行：grow-spared 兩行，帶長大前的動物、group、新種類、異種同號者與它的 group",
                    spared.size() == 2
                            && spared.get(0).contains("animal=chick#5001 pos=2100,2000,0 inWorld=0 hutch=0 vehicle=0 dead=0 group=chicken newType=hen twin=rabkitten#5001 pos=2102,1997,0")
                            && spared.get(0).contains("twinGroup=rabbit")
                            && spared.get(1).contains("animal=chick#5010 pos=3000,2000,0") && spared.get(1).contains("twin=rabkitten#5010 pos=2996,2000,0"));
            expect("放行：growSpared 計數 3（含 log 拋例外那次）", AnimalLossProbe.growSpared.get() - sparedBefore == 3L);
            expect("首次生效行恰一行且帶 growIdClash=on", rowsContaining("首次生效").size() == 1
                    && rowsContaining("首次生效").get(0).contains("growIdClash=on ")
                    && rowsContaining("首次生效").get(0).contains(" growSpared="));
        } else {
            expect("spare-off：零 grow-spared、growSpared=0", spared.isEmpty() && AnimalLossProbe.growSpared.get() == 0L);
            List<String> dups = rowsContaining("grow-dup#");
            expect("spare-off：異種同號照原版刪並記 grow-dup（twin 是那隻兔子）",
                    dups.stream().anyMatch(r -> r.contains("animal=chick#5001") && r.contains("twin=rabkitten#5001 pos=2102,1997,0")));
            expect("首次生效行恰一行且帶 growIdClash=off",
                    rowsContaining("首次生效").size() == 1 && rowsContaining("首次生效").get(0).contains("growIdClash=off "));
        }
        if (!on) {
            expect("off：W60 觀測仍不記帳（drops、growDups 0）",
                    AnimalLossProbe.drops.get() == 0L && AnimalLossProbe.growDups.get() == 0L);
            expect("off：整個 JVM 只有 grow-spared 與首次生效行", rows().stream()
                    .allMatch(r -> r.contains("grow-spared#") || r.contains("首次生效")) && !rows().isEmpty());
        }

        // ---- 固定種子隨機差分：on／off＝「有同號且全是確定異種就 false（不呼叫原版），否則真原版結果」；spare-off＝真原版 ----
        Random rnd = new Random(109);
        String[] groups = {"chicken", "rabbit", "sheep", "CHICKEN", null, "", NO_DEF};
        int sparedN = 0, deletedN = 0, keptN = 0, bad = 0;
        for (int i = 0; i < 200; i++) {
            int bx = 20000 + 100 * i, by = 20000;
            int id = 6000 + i;
            String pg = groups[rnd.nextInt(3) == 0 ? rnd.nextInt(groups.length) : 0];
            TestAnimal p = placed(map, "chick", pg, id, bx, by, 0);
            TestAnimal g = grownAt(map, p, "hen", rnd.nextInt(8) == 0 ? groups[rnd.nextInt(groups.length)] : "chicken");
            boolean counted = false, allOther = true;
            for (int k = rnd.nextInt(5); k > 0; k--) {
                int dx = rnd.nextInt(11) - 5, dy = rnd.nextInt(11) - 5;
                int z = rnd.nextInt(5) == 0 ? 1 : 0;
                boolean same = rnd.nextBoolean();
                String og = groups[rnd.nextInt(groups.length)];
                TestAnimal o = placed(map, "x", og, same ? id : id + 1000, bx + dx, by + dy, z);
                o.onHook = rnd.nextInt(10) == 0;
                if (same && z == 0 && dx >= -4 && dx < 4 && dy >= -4 && dy < 4 && !o.onHook) {
                    counted = true;
                    allOther &= oracleOther(og, pg) && oracleOther(og, g.adef == null ? NO_DEF : g.adef.group);
                }
            }
            int[] v = grow(p, g, false);
            int[] h = grow(p, g, true);
            boolean spare = spareOn && counted && allOther;
            boolean ok = v[0] == (counted ? 1 : 0) && v[1] == 1 && v[2] == v[0]
                    && (spare ? h[0] == 0 && h[1] == 0 && h[2] == 0 : h[0] == v[0] && h[1] == 1 && h[2] == v[2]);
            if (!ok) {
                bad++;
                System.out.println("  差分不符 #" + i + " vanilla=" + java.util.Arrays.toString(v) + " helper=" + java.util.Arrays.toString(h)
                        + " counted=" + counted + " allOther=" + allOther);
            }
            if (spare) sparedN++;
            else if (v[0] == 1) deletedN++;
            else keptN++;
        }
        System.out.println("  隨機差分：放行 " + sparedN + "、原版刪 " + deletedN + "、沒撞 " + keptN);
        expect("隨機差分 200 組（種子 109）：原版直呼與獨立判讀相符、helper 與預期逐組相同，三類都有樣本",
                bad == 0 && (spareOn ? sparedN >= 10 : sparedN == 0) && deletedN >= 10 && keptN >= 10);
    }

    /** 測試自己的確定異種判讀（與 helper 分開寫）：兩邊都有非空 group 且不分大小寫不同。 */
    private static boolean oracleOther(String a, String b) {
        return a != NO_DEF && b != NO_DEF && a != null && b != null && !a.isEmpty() && !b.isEmpty() && !a.equalsIgnoreCase(b);
    }

    /** 一個佈局：先直呼真原版當負對照，再經 helper，依 SPARE 與 clash 比對。 */
    private static void growCase(String what, TestAnimal parent, TestAnimal grown, boolean clash, boolean vanillaDeletes) {
        int[] v = grow(parent, grown, false);
        long spared = AnimalLossProbe.growSpared.get();
        int[] h = grow(parent, grown, true);
        int del = vanillaDeletes ? 1 : 0;
        expect(what + "：真原版直呼回 " + vanillaDeletes + "、delete " + del, v[0] == del && v[1] == 1 && v[2] == del);
        if (AnimalLossProbe.SPARE && clash) {
            expect(what + "：放行（回 false、原版 0 次、delete 0、growSpared+1）",
                    h[0] == 0 && h[1] == 0 && h[2] == 0 && AnimalLossProbe.growSpared.get() == spared + 1);
        } else {
            expect(what + "：與原版相同（原版恰 1 次、回 " + vanillaDeletes + "、delete " + del + "）",
                    h[0] == del && h[1] == 1 && h[2] == del && parent.chickenArg == grown && AnimalLossProbe.growSpared.get() == spared);
        }
    }

    /** 回 {回傳 0/1, 原版呼叫次數, delete 次數}；viaHelper=false 是直呼真原版。 */
    private static int[] grow(TestAnimal parent, TestAnimal grown, boolean viaHelper) {
        parent.realCheck = true;
        parent.chickenCalls = 0;
        parent.deletes = 0;
        parent.chickenArg = null;
        boolean r = viaHelper ? AnimalLossProbe.growDuplicate(parent, grown) : parent.checkForChickenpocalypse(grown);
        return new int[]{r ? 1 : 0, parent.chickenCalls, parent.deletes};
    }

    /** 長大後的新動物：和長大前同號、同一格（原版建構子已把牠放進格子）。 */
    private static TestAnimal grownAt(TestServerMap map, TestAnimal parent, String type, String group) throws Exception {
        return placed(map, type, group, parent.getAnimalID(), (int) parent.px, (int) parent.py, 0);
    }

    /** 放在 (x,y,z) 那一格的動物；group 為 {@link #NO_DEF} 時不設 adef。 */
    private static TestAnimal placed(TestServerMap map, String type, String group, int id, int x, int y, int z) throws Exception {
        TestAnimal a = animal(type, id, x, y);
        if (group != NO_DEF) {
            a.adef = alloc(AnimalDefinitions.class);
            a.adef.group = group;
        }
        a.sq = square(map, x, y, z);
        a.sq.getMovingObjects().add(a);
        return a;
    }

    /** 照手術後 AddWorldInventoryItem 的順序：copyFrom → get →（原版移走前持有者）→ add。 */
    private static IsoAnimal drop(AnimalInstanceManager aim, TestAnimal dst, IsoAnimal src, short id) {
        AnimalLossProbe.dropCopy(dst, src);
        IsoAnimal prev = AnimalLossProbe.dropLookup(aim, id);
        AnimalLossProbe.dropRegister(aim, dst, id);
        return prev;
    }

    /**
     * 替身：覆寫放下與長大會讀的方法；copyFrom／checkForChickenpocalypse 記次數與參數（原版需要 Lua 與世界）。
     * {@code realCheck} 時 checkForChickenpocalypse 走真原版本體；delete 只記次數，佈局不變，同一佈局可先直呼再經 helper。
     */
    public static class TestAnimal extends IsoAnimal {
        String type;
        float px, py;
        boolean inWorld;
        boolean throwOnType;
        int copyCalls;
        IsoAnimal copyArg;
        RuntimeException copyThrows;
        boolean chickenResult;
        int chickenCalls;
        IsoAnimal chickenArg;
        boolean realCheck;
        int deletes;
        boolean onHook;
        int idThrows;

        IsoGridSquare sq;

        public TestAnimal() {
            super((IsoCell) null);
        }

        @Override
        public IsoGridSquare getCurrentSquare() {
            return sq;
        }

        @Override
        public IsoGridSquare getSquare() {
            return sq;
        }

        @Override
        public int getAnimalID() {
            if (idThrows > 0) {
                idThrows--;
                throw new IllegalStateException("id");
            }
            return super.getAnimalID();
        }

        @Override
        public void delete() {
            deletes++;
        }

        @Override
        public String getAnimalType() {
            if (throwOnType) {
                throw new IllegalStateException("type");
            }
            return type;
        }

        @Override
        public float getX() {
            return px;
        }

        @Override
        public float getY() {
            return py;
        }

        @Override
        public float getZ() {
            return 0f;
        }

        @Override
        public boolean isExistInTheWorld() {
            return inWorld;
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public boolean isOnHook() {
            return onHook;
        }

        @Override
        public void copyFrom(IsoAnimal animal) {
            copyCalls++;
            copyArg = animal;
            if (copyThrows != null) {
                throw copyThrows;
            }
        }

        @Override
        public boolean checkForChickenpocalypse(IsoAnimal replacingAnimal) {
            chickenCalls++;
            chickenArg = replacingAnimal;
            return realCheck ? super.checkForChickenpocalypse(replacingAnimal) : chickenResult;
        }
    }

    /** ServerMap 替身：IsoCell.getGridSquare 在伺服器上轉給 ServerMap.instance。 */
    public static class TestServerMap extends ServerMap {
        static final java.util.Map<Long, IsoGridSquare> SQUARES = new java.util.HashMap<>();

        @Override
        public IsoGridSquare getGridSquare(int x, int y, int z) {
            return SQUARES.get(key(x, y, z));
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) (y & 0xfffff) << 20) | (z & 0xfffff);
    }

    private static IsoGridSquare square(TestServerMap map, int x, int y, int z) throws Exception {
        long k = key(x, y, z);
        IsoGridSquare sq = TestServerMap.SQUARES.get(k);
        if (sq == null) {
            sq = alloc(IsoGridSquare.class);
            sq.x = x;
            sq.y = y;
            sq.z = z;
            Field moving = IsoGridSquare.class.getDeclaredField("movingObjects");
            moving.setAccessible(true);
            moving.set(sq, new ArrayList<IsoMovingObject>());
            TestServerMap.SQUARES.put(k, sq);
        }
        return sq;
    }

    private static TestAnimal animal(String type, int id, float x, float y) throws Exception {
        TestAnimal a = alloc(TestAnimal.class);
        a.type = type;
        a.animalId = id;
        a.px = x;
        a.py = y;
        return a;
    }

    @SuppressWarnings({"unchecked", "deprecation", "removal"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static List<String> rows() {
        List<String> out = new ArrayList<>();
        for (String line : logBuf.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> rowsContaining(String token) {
        List<String> out = new ArrayList<>();
        for (String row : rows()) {
            if (row.contains(token)) {
                out.add(row);
            }
        }
        return out;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalLossProbeTest() {
    }
}
