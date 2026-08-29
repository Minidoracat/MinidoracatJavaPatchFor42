package zombie.mdc;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;

/** W18-2 行為測試：off/observe/on 各獨立 JVM，真 objectList 覆蓋 fast/delegate/fallback。 */
public final class AnimalLosScanTest {
    public static void main(String[] args) throws Exception {
        String arg = args.length == 0 ? "observe" : args[0];
        int want = switch (arg) {
            case "off" -> AnimalLosScan.MODE_OFF;
            case "on" -> AnimalLosScan.MODE_ON;
            default -> AnimalLosScan.MODE_OBSERVE;
        };
        require(AnimalLosScan.MODE == want, "mode got=" + AnimalLosScan.MODE + " want=" + want);
        switch (want) {
            case AnimalLosScan.MODE_OFF -> testOff();
            case AnimalLosScan.MODE_OBSERVE -> testObserve();
            default -> testOn();
        }
        System.out.println("AnimalLosScanTest OK mode=" + AnimalLosScan.MODE);
    }

    private static void testOff() throws Exception {
        TestAnimal a = animal(null);
        AnimalLosScan.updateLOS(a);
        require(a.vanillaCalls == 1, "off 必須直通一次");
        require(AnimalLosScan.callsForTest() == 0, "off 計數凍結");
    }

    private static void testObserve() throws Exception {
        TestAnimal a = animal(cell(new LinkedHashSet<>()));
        AnimalLosScan.updateLOS(a);
        require(a.vanillaCalls == 1, "observe 必須直通一次");
        require(AnimalLosScan.callsForTest() == 1, "observe calls=1");
        require(AnimalLosScan.elapsedNsForTest() >= 0, "observe elapsed 非負");
        require(AnimalLosScan.sumObjectsForTest() == 0, "空 list size=0");
    }

    private static void testOn() throws Exception {
        TestAnimal a = animal(null);
        TestBehavior b = new TestBehavior(a);
        b.lastAlerted = -1.0F; // fast skip 必須執行第二個獨立 if，clamp 回 0。
        a.behavior = b;

        IsoZombie far = zombie(100.0F, 0.0F);
        IsoZombie near = zombie(1.0F, 0.0F);
        Set<IsoMovingObject> objects = new LinkedHashSet<>();
        objects.add(a);
        objects.add(far);
        objects.add(near);
        a.cell = cell(objects);
        a.spottedChr = far;

        AnimalLosScan.updateLOS(a);
        require(a.vanillaCalls == 0, "on 成功路徑不得 fallback");
        require(a.spotted.size() == 1 && a.spotted.peek() == a, "self 必須加入 spottedList");
        require(b.lastAlerted == 0.0F, "遠距 fast skip 必須把負 lastAlerted clamp 回0");
        require(a.spottedChr == null, "遠距 fast skip 必須重放 spottedChr=null");
        require(b.spottedCalls == 1 && b.lastOther == near, "近距殭屍必須 delegate 一次");
        require(AnimalLosScan.animalsScannedForTest() == 1, "scanned=1");
        require(AnimalLosScan.fastSkippedForTest() == 1, "fastSkipped=1");
        require(AnimalLosScan.delegatedForTest() == 1, "delegated=1");
        require(AnimalLosScan.fallbacksForTest() == 0, "成功路徑 fallback=0");


        initRandForGameStatics();
        float multiplier = zombie.GameTime.getInstance().getMultiplier();
        require(multiplier > 0.0F, "測試前置：GameTime multiplier>0");
        TestAnimal decayA = animal(null);
        TestBehavior decayB = new TestBehavior(decayA);
        decayB.lastAlerted = multiplier * 2.0F;
        decayA.behavior = decayB;
        decayA.cell = cell(new LinkedHashSet<>(List.of(zombie(100.0F, 0.0F))));
        AnimalLosScan.updateLOS(decayA);
        require(Float.floatToRawIntBits(decayB.lastAlerted)
                        == Float.floatToRawIntBits(multiplier),
                "正 lastAlerted 必須逐 float 減一次 live multiplier");
        TestAnimal crossA = animal(null);
        TestBehavior crossB = new TestBehavior(crossA);
        crossB.lastAlerted = multiplier * 0.5F;
        crossA.behavior = crossB;
        crossA.cell = cell(new LinkedHashSet<>(List.of(zombie(100.0F, 0.0F))));
        AnimalLosScan.updateLOS(crossA);
        require(crossB.lastAlerted == 0.0F, "正值跨零必須由第二個獨立 if clamp 0");

        // 保守裕度邊界：threshold=12, g=12.25；12.2 必須 delegate，12.3 才可 fast skip。
        TestAnimal edgeA = animal(null);
        TestBehavior edgeB = new TestBehavior(edgeA);
        edgeB.lastAlerted = 0.0F;
        edgeA.behavior = edgeB;
        IsoZombie edge = zombie(12.2F, 0.0F);
        edgeA.cell = cell(new LinkedHashSet<>(List.of(edge)));
        long edgeDelegated = AnimalLosScan.delegatedForTest();
        AnimalLosScan.updateLOS(edgeA);
        require(AnimalLosScan.delegatedForTest() == edgeDelegated + 1,
                "12.2 邊界帶必須全額 delegate 至 W3-3 prefilter");
        require(edgeB.spottedCalls == 0,
                "prefilter 對 dist=12.2>threshold=12 應自行 skip，不進 behavior");

        // invisible/ghost 玩家在 vanilla 玩家分支零效果：遠距不可重放 prefilter 前綴。
        TestAnimal invA = animal(null);
        TestBehavior invB = new TestBehavior(invA);
        invB.lastAlerted = 0.0F;
        invA.behavior = invB;
        TestPlayer invisible = player(100.0F, 0.0F, true, false);
        invA.spottedChr = invisible;
        invA.cell = cell(new LinkedHashSet<>(List.of(invisible)));
        long fastBefore = AnimalLosScan.fastSkippedForTest();
        AnimalLosScan.updateLOS(invA);
        require(invA.spottedChr == invisible, "隱形玩家 pair 應零效果，不清 spottedChr");
        require(invB.spottedCalls == 0, "隱形玩家不得 delegate");
        require(AnimalLosScan.fastSkippedForTest() == fastBefore, "隱形玩家不得算 fast skip");

        // 每 pair live threshold：第一個 delegate 將 spottingDist 10→100；後一個距50必須
        // 依新 threshold=102 delegate。若 Scan 每隻只讀一次，第二 pair 會被錯誤 fast-skip。
        TestAnimal liveA = animal(null);
        TestBehavior liveB = new TestBehavior(liveA);
        liveB.mutateSpottingDist = true;
        liveA.behavior = liveB;
        IsoZombie trigger = zombie(1.0F, 0.0F);
        IsoZombie afterMutation = zombie(50.0F, 0.0F);
        liveA.cell = cell(new LinkedHashSet<>(List.of(trigger, afterMutation)));
        long liveFastBefore = AnimalLosScan.fastSkippedForTest();
        AnimalLosScan.updateLOS(liveA);
        require(liveB.spottedCalls == 2 && liveB.lastOther == afterMutation,
                "spottingDist 動態改大後，後一 pair 必須 live 讀並 delegate");
        require(AnimalLosScan.fastSkippedForTest() == liveFastBefore,
                "動態 threshold pair 不得使用舊 gate fast-skip");

        // 前置取值失敗：spotted.clear 前 fail-open，恰一次 vanilla、無 double scan。
        TestAnimal fallback = animal(null);
        fallback.behavior = b;
        long fbBefore = AnimalLosScan.fallbacksForTest();
        AnimalLosScan.updateLOS(fallback);
        require(fallback.vanillaCalls == 1, "null cell fallback 恰一次 vanilla");
        require(AnimalLosScan.fallbacksForTest() == fbBefore + 1, "fallback 計數+1");
        require(AnimalLosScan.anomaliesForTest() == 0, "anomalies=0");
    }

    private static TestAnimal animal(IsoCell cell) throws Exception {
        TestAnimal a = alloc(TestAnimal.class);
        a.cell = cell;
        a.spotted = new Stack<>();
        AnimalDefinitions def = alloc(AnimalDefinitions.class);
        def.spottingDist = 10;
        a.adef = def;
        return a;
    }

    private static IsoZombie zombie(float x, float y) throws Exception {
        IsoZombie z = alloc(IsoZombie.class);
        setMovingState(z, x, y, alloc(IsoGridSquare.class));
        return z;
    }

    private static TestPlayer player(float x, float y, boolean invisible, boolean ghost)
            throws Exception {
        TestPlayer p = alloc(TestPlayer.class);
        p.x = x;
        p.y = y;
        p.invisible = invisible;
        p.ghost = ghost;
        p.square = alloc(IsoGridSquare.class);
        return p;
    }

    private static IsoCell cell(Set<IsoMovingObject> objects) throws Exception {
        IsoCell c = alloc(IsoCell.class);
        Field f = IsoCell.class.getDeclaredField("objectList");
        f.setAccessible(true);
        f.set(c, objects);
        return c;
    }

    private static void setMovingState(IsoMovingObject o, float x, float y, IsoGridSquare square)
            throws Exception {
        Field fx = IsoMovingObject.class.getDeclaredField("x");
        Field fy = IsoMovingObject.class.getDeclaredField("y");
        Field fc = IsoMovingObject.class.getDeclaredField("current");
        fx.setAccessible(true);
        fy.setAccessible(true);
        fc.setAccessible(true);
        fx.setFloat(o, x);
        fy.setFloat(o, y);
        fc.set(o, square);
    }

    private static void initRandForGameStatics() throws Exception {
        Field f = zombie.core.random.RandAbstract.class.getDeclaredField("rand");
        f.setAccessible(true);
        f.set(zombie.core.random.RandStandard.INSTANCE, new java.util.Random(1L));
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    static class TestAnimal extends IsoAnimal {
        IsoCell cell;
        Stack<IsoMovingObject> spotted;
        TestBehavior behavior;
        int vanillaCalls;
        TestAnimal() { super(null); }
        @Override public void updateLOS() { vanillaCalls++; }
        @Override public IsoCell getCell() { return cell; }
        @Override public Stack<IsoMovingObject> getSpottedList() { return spotted; }
        @Override public BaseAnimalBehavior getBehavior() { return behavior; }
        @Override public float getX() { return 0.0F; }
        @Override public float getY() { return 0.0F; }
        @Override public float getZ() { return 0.0F; }
    }

    static class TestBehavior extends BaseAnimalBehavior {
        int spottedCalls;
        IsoMovingObject lastOther;
        final TestAnimal testParent;
        boolean mutateSpottingDist;
        TestBehavior(TestAnimal parent) {
            super(parent);
            this.testParent = parent;
        }
        @Override public void spotted(IsoMovingObject other, boolean forced, float dist) {
            spottedCalls++;
            lastOther = other;
            if (mutateSpottingDist && spottedCalls == 1) {
                testParent.adef.spottingDist = 100;
            }
        }
    }

    static class TestPlayer extends IsoPlayer {
        float x, y;
        boolean invisible, ghost;
        IsoGridSquare square;
        TestPlayer() { super(null); }
        @Override public float getX() { return x; }
        @Override public float getY() { return y; }
        @Override public float getZ() { return 0.0F; }
        @Override public IsoGridSquare getCurrentSquare() { return square; }
        @Override public boolean isInvisible() { return invisible; }
        @Override public boolean isGhostMode() { return ghost; }
    }
}
