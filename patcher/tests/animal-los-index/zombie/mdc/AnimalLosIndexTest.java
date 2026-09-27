package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
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
import zombie.iso.IsoPhysicsObject;
import zombie.vehicles.BaseVehicle;

/**
 * W47 差分測試：同一個世界、同一個動物初始狀態，分別跑原版 {@code IsoAnimal.updateLOS}（dist 手術後，
 * 含 W3-3 預過濾）與 {@code AnimalLosScan.updateLOS}（on＋W47），比對 spotted 呼叫序列（對象與距離位元）、
 * {@code spottedChr}、{@code lastAlerted} 位元、{@code spottedList} 與門檻。行為物件照原版
 * {@code spotted()} 開頭先重放前綴，再依距離設定感知／警戒／改門檻，以涵蓋提前改走完整順序的路徑。
 *
 * <p>用法：{@code AnimalLosIndexTest on|observe|off|violation-final|violation-audit}，每種一個 JVM
 * （停用是本次啟動永久生效）。需 {@code -Dmdc.animalLosScan=on}。
 */
public final class AnimalLosIndexTest {
    private static final Field X = field(IsoMovingObject.class, "x");
    private static final Field Y = field(IsoMovingObject.class, "y");
    private static final Field Z = field(IsoMovingObject.class, "z");
    private static final Field CURRENT = field(IsoMovingObject.class, "current");
    private static final Field GRAPPLE = field(IsoZombie.class, "reanimatedForGrappleOnly");
    private static final Field SPOTTED_LIST = findField(IsoAnimal.class, "spottedList");
    private static IsoGridSquare square;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        Field rand = zombie.core.random.RandAbstract.class.getDeclaredField("rand");
        rand.setAccessible(true);
        rand.set(zombie.core.random.RandStandard.INSTANCE, new Random(1L));
        require(zombie.GameTime.getInstance().getMultiplier() > 0.0F, "前置：GameTime multiplier>0");
        require(AnimalLosScan.MODE == AnimalLosScan.MODE_ON, "需 -Dmdc.animalLosScan=on");
        square = alloc(IsoGridSquare.class);
        String mode = args.length == 0 ? "on" : args[0];
        switch (mode) {
            case "on" -> {
                require(AnimalLosIndex.MODE == AnimalLosIndex.MODE_ON, "mode on");
                differential(4000, 42L);
                membershipChanges();
                lastElementEdits();
                reentry();
                require(AnimalLosIndex.modifiedExitsForTest() > 0, "必須涵蓋 spotted() 中改動清單而比照原版拋 CME 的路徑");
                require(AnimalLosIndex.nestedForTest() > 0, "必須涵蓋 spotted() 內巢狀視線檢查");
                require(AnimalLosIndex.fastForTest() > 2000, "快速路徑必須實際承擔大部分呼叫：fast=" + AnimalLosIndex.fastForTest());
                require(AnimalLosIndex.tailSwitchesForTest() > 0, "必須涵蓋 spotted 後改走完整順序的路徑");
                require(AnimalLosIndex.auditsForTest() > 0 && AnimalLosIndex.auditMissesForTest() == 0, "on 模式抽樣比對零缺漏");
                require(!AnimalLosIndex.disabledForTest(), "無位移時不得停用");
                require(AnimalLosIndex.candidateSumForTest() * 4 < AnimalLosIndex.targetSumForTest(),
                        "候選必須遠少於全部目標：cand=" + AnimalLosIndex.candidateSumForTest()
                                + " targets=" + AnimalLosIndex.targetSumForTest());
            }
            case "observe" -> {
                require(AnimalLosIndex.MODE == AnimalLosIndex.MODE_OBSERVE, "mode observe");
                differential(800, 7L);
                require(AnimalLosIndex.fastForTest() == 0, "observe 不得走快速路徑");
                require(AnimalLosIndex.auditsForTest() > 400 && AnimalLosIndex.auditMissesForTest() == 0, "observe 每次比對且零缺漏");
            }
            case "off" -> {
                require(AnimalLosIndex.MODE == AnimalLosIndex.MODE_OFF, "mode off");
                differential(400, 9L);
                require(AnimalLosIndex.fastForTest() == 0 && AnimalLosIndex.auditsForTest() == 0, "off 完全不經 W47");
            }
            case "violation-final" -> violationFinal();
            case "violation-audit" -> violationAudit();
            default -> throw new IllegalArgumentException(mode);
        }
        System.out.println("AnimalLosIndexTest OK mode=" + mode + " fast=" + AnimalLosIndex.fastForTest()
                + " tail=" + AnimalLosIndex.tailSwitchesForTest() + " audits=" + AnimalLosIndex.auditsForTest()
                + " cand=" + AnimalLosIndex.candidateSumForTest() + " targets=" + AnimalLosIndex.targetSumForTest());
    }

    // ---- 差分 ----

    private static void differential(int worlds, long seed) throws Exception {
        Random r = new Random(seed);
        for (int w = 0; w < worlds; w++) {
            World world = World.random(r);
            int calls = 1 + r.nextInt(3);
            for (int c = 0; c < calls; c++) {
                State s0 = State.random(r, world);
                Result vanilla = run(world, s0, true);
                Result patched = run(world, s0, false);
                if (!vanilla.equals(patched)) {
                    throw new AssertionError("world " + w + " call " + c + " 差異\n vanilla=" + vanilla + "\n patched=" + patched
                            + "\n state=" + s0 + " targets=" + world.targets.size());
                }
            }
        }
    }

    private static Result run(World world, State s0, boolean vanilla) throws Exception {
        if (s0.edit != 0) {
            world.install(); // 上一輪的改動不留下來：兩條路徑都從同一份新建的清單開始
        }
        TestAnimal a = world.animal;
        s0.apply(a);
        a.behavior.extra = world.extra;
        String thrown = null;
        try {
            if (vanilla) {
                a.updateLOS();
            } else {
                AnimalLosScan.updateLOS(a);
            }
        } catch (RuntimeException e) {
            thrown = e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        return new Result(a, thrown);
    }

    /**
     * spotted() 內同步觸發另一隻動物的視線檢查（同一份清單）：外層與內層的 spotted 呼叫都必須與原版相同；
     * 內層拋例外後，下一次呼叫仍正常。
     */
    @SuppressWarnings("unchecked")
    private static void reentry() throws Exception {
        for (int w = 0; w < 300; w++) {
            TestAnimal a = animal(200, 200, 0.0F);
            TestAnimal inner = animal(260, 200, 0.0F);
            World world = new World(a);
            for (int i = 0; i < 3; i++) {
                world.add(zombie(201 + i, 200.5F, 0.0F, true), true);
                world.add(zombie(261 + i, 200.5F, 0.0F, true), true);
            }
            world.order.add(a);
            world.order.add(inner);
            world.install();
            inner.cell = a.cell;
            for (int round = 0; round < 2; round++) {
                boolean innerBoom = round == 0 && w % 3 == 0;
                List<String>[] out = new List[4];
                for (int pass = 0; pass < 2; pass++) {
                    boolean vanilla = pass == 0;
                    State.plain(world).apply(a);
                    State.plain(world).apply(inner);
                    inner.behavior.boom = innerBoom;
                    a.behavior.nested = inner;
                    VBehavior.vanillaRun = vanilla;
                    String thrown = null;
                    try {
                        if (vanilla) {
                            a.updateLOS();
                        } else {
                            AnimalLosScan.updateLOS(a);
                        }
                    } catch (RuntimeException e) {
                        thrown = e.getClass().getSimpleName() + ":" + e.getMessage();
                    }
                    out[pass * 2] = new ArrayList<>(a.behavior.calls);
                    out[pass * 2].add("thrown=" + thrown + " chr=" + id(a.spottedChr) + " list=" + a.spotted.size());
                    out[pass * 2 + 1] = new ArrayList<>(inner.behavior.calls);
                    out[pass * 2 + 1].add("chr=" + id(inner.spottedChr) + " list=" + inner.spotted.size());
                }
                require(out[0].equals(out[2]) && out[1].equals(out[3]),
                        "巢狀視線 world " + w + " round " + round + "\n outer vanilla=" + out[0] + "\n outer patched=" + out[2]
                                + "\n inner vanilla=" + out[1] + "\n inner patched=" + out[3]);
            }
        }
        VBehavior.vanillaRun = false;
    }

    /** 被委派的近距殭屍正好是整份清單最後一個元素時，spotted() 內改動清單：原版迴圈自然結束、不拋 CME。 */
    private static void lastElementEdits() throws Exception {
        for (int edit = 1; edit <= 3; edit++) {
            World world = null;
            for (int attempt = 0; attempt < 100_000 && world == null; attempt++) {
                TestAnimal a = animal(200, 200, 0.0F);
                World w = new World(a);
                w.near = zombie(201, 200, 0.0F, true);
                w.extra = zombie(202, 200, 0.0F, true);
                w.add(w.near, true);
                w.order.add(a);
                w.add(zombie(300, 300, 0.0F, true), true);
                w.install();
                IsoMovingObject last = null;
                for (IsoMovingObject o : w.set) {
                    last = o;
                }
                if (last == w.near) {
                    world = w;
                }
            }
            require(world != null, "排不出近距殭屍在最後的迭代順序");
            State s0 = State.plain(world);
            s0.edit = edit;
            Result vanilla = run(world, s0, true);
            Result patched = run(world, s0, false);
            require(vanilla.equals(patched), "最後元素改動清單 edit=" + edit + "\n" + vanilla + "\n" + patched);
            require(vanilla.thrown() == null, "最後元素改動清單原版不拋例外 edit=" + edit);
        }
    }

    /** 快照建立後同一幀內改動清單：同大小換成員、暫時擴容後還原成員（迭代順序可能改變）。 */
    private static void membershipChanges() throws Exception {
        World world = World.line(false);
        State s0 = State.plain(world);
        require(run(world, s0, false).equals(run(world, s0, true)), "改動前一致（快照已建立）");
        require(world.set.remove(world.near), "移除近距殭屍");
        IsoZombie replacement = zombie(202, 201, 0.0F, true);
        world.set.add(replacement);
        require(run(world, s0, true).equals(run(world, s0, false)), "同一幀同大小換成員後必須與原版一致");
        require(world.set.remove(replacement), "只移除近距殭屍");
        require(run(world, s0, true).equals(run(world, s0, false)), "同一幀只移除後必須與原版一致");
        world.set.add(replacement);
        List<IsoMovingObject> extra = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            IsoZombie z = zombie(400 + i, 400, 0.0F, true);
            extra.add(z);
            world.set.add(z);
        }
        world.set.removeAll(extra);
        require(run(world, s0, true).equals(run(world, s0, false)), "擴容後成員還原（順序改變）必須與原版一致");
        Iterator<IsoMovingObject> it = world.set.iterator();
        it.next();
        it.remove();
        require(run(world, s0, true).equals(run(world, s0, false)), "經 iterator 移除後必須與原版一致");
        State wide = State.plain(world);
        wide.spottingDist = 60_000;
        long fastBefore = AnimalLosIndex.fastForTest();
        require(run(world, wide, true).equals(run(world, wide, false)), "超大門檻與原版一致");
        require(AnimalLosIndex.fastForTest() == fastBefore, "候選網格超過上限時必須改走完整掃描");
    }

    // ---- 位移違反 ----

    private static void violationFinal() throws Exception {
        require(AnimalLosIndex.MODE == AnimalLosIndex.MODE_ON, "mode on");
        World world = World.line(false);
        State s0 = State.plain(world);
        require(run(world, s0, true).equals(run(world, s0, false)), "位移前一致");
        long fastBefore = AnimalLosIndex.fastForTest();
        world.moveNear();
        Result vanilla = run(world, s0, true);
        Result patched = run(world, s0, false);
        require(vanilla.equals(patched), "排在最後有效候選之後的位移殭屍：補處理後仍與原版一致\n" + vanilla + "\n" + patched);
        require(AnimalLosIndex.lateFixesForTest() == 1 && AnimalLosIndex.disabledForTest(), "最後掃描必須發現違反並停用");
        require(AnimalLosIndex.fastForTest() == fastBefore + 1, "違反那次仍算快速路徑");
        require(run(world, s0, true).equals(run(world, s0, false)), "停用後回完整掃描仍一致");
        require(AnimalLosIndex.fastForTest() == fastBefore + 1, "停用後不得再走快速路徑");
    }

    private static void violationAudit() throws Exception {
        require(AnimalLosIndex.MODE == AnimalLosIndex.MODE_OBSERVE, "mode observe");
        World world = World.line(true);
        State s0 = State.plain(world);
        require(run(world, s0, true).equals(run(world, s0, false)), "位移前一致");
        world.moveNear();
        require(run(world, s0, true).equals(run(world, s0, false)), "observe 走完整掃描，結果一致");
        require(AnimalLosIndex.auditMissesForTest() == 1 && AnimalLosIndex.disabledForTest(), "比對必須發現候選缺漏並停用");
    }

    // ---- 世界 ----

    static final class World {
        final TestAnimal animal;
        final List<IsoMovingObject> targets = new ArrayList<>();
        final List<IsoMovingObject> order = new ArrayList<>();
        Set<IsoMovingObject> set;

        World(TestAnimal animal) {
            this.animal = animal;
        }

        static World random(Random r) throws Exception {
            TestAnimal a = animal(100 + r.nextFloat() * 200, 100 + r.nextFloat() * 200, r.nextInt(8) == 0 ? 1.0F : 0.0F);
            World w = new World(a);
            w.extra = zombie(a.px + 1.0F, a.py, a.pz, true);
            int zombies = r.nextInt(10) == 0 ? 0 : r.nextInt(500);
            for (int i = 0; i < zombies; i++) {
                boolean near = r.nextInt(10) < 2;
                float x = near ? a.px + (r.nextFloat() - 0.5F) * 34 : r.nextFloat() * 420;
                float y = near ? a.py + (r.nextFloat() - 0.5F) * 34 : r.nextFloat() * 420;
                IsoZombie z = zombie(x, y, zLevel(r, a.pz), r.nextInt(20) != 0);
                if (r.nextInt(25) == 0) {
                    GRAPPLE.setBoolean(z, true);
                }
                w.add(z, true);
            }
            int players = r.nextInt(8);
            for (int i = 0; i < players; i++) {
                boolean near = r.nextBoolean();
                float x = near ? a.px + (r.nextFloat() - 0.5F) * 30 : r.nextFloat() * 420;
                float y = near ? a.py + (r.nextFloat() - 0.5F) * 30 : r.nextFloat() * 420;
                w.add(player(x, y, zLevel(r, a.pz), r.nextInt(6) == 0, r.nextInt(8) == 0, r.nextInt(20) != 0), true);
            }
            for (int i = r.nextInt(20); i > 0; i--) {
                w.add(animal(r.nextFloat() * 420, r.nextFloat() * 420, 0.0F), false);
            }
            if (r.nextBoolean()) {
                w.add(alloc(BaseVehicle.class), false);
            }
            if (r.nextInt(4) == 0) {
                w.add(alloc(IsoPhysicsObject.class), false);
            }
            if (r.nextInt(20) != 0) {
                w.order.add(a);
            }
            Collections.shuffle(w.order, r);
            w.install();
            return w;
        }

        /**
         * 近距殭屍、自己、待位移殭屍、遠距殭屍 ×40；HashSet 迭代順序不可指定，重抽到符合為止：
         * movingFirst＝待位移殭屍排在近距殭屍之前；否則緊接在近距殭屍之後（最後一個有效候選之後的第一個目標，
         * 快速路徑最後的掃描會碰到它）。
         */
        static World line(boolean movingFirst) throws Exception {
            for (int attempt = 0; attempt < 100_000; attempt++) {
                TestAnimal a = animal(200, 200, 0.0F);
                World w = new World(a);
                IsoZombie moving = zombie(200 + 60, 200, 0.0F, true);
                w.near = zombie(203, 200, 0.0F, true);
                w.add(w.near, true);
                w.order.add(a);
                w.add(moving, true);
                for (int i = 0; i < 40; i++) {
                    w.add(zombie(260 + i, 260, 0.0F, true), true);
                }
                w.moving = moving;
                w.install();
                int ni = w.targets.indexOf(w.near);
                int mi = w.targets.indexOf(moving);
                if (movingFirst ? mi < ni : mi == ni + 1) {
                    return w;
                }
            }
            throw new AssertionError("排不出所需的迭代順序");
        }

        IsoZombie near;
        IsoZombie extra;

        IsoZombie moving;

        void moveNear() throws Exception {
            X.setFloat(moving, 202.0F);
        }

        void add(IsoMovingObject o, boolean target) {
            order.add(o);
            if (target) {
                targets.add(o);
            }
        }

        void install() throws Exception {
            // 與正式服相同：IsoCell 建構子的包裝（off 時原樣回傳 HashSet），迭代順序由 HashSet 決定。
            set = AnimalLosIndex.wrapObjectList(new HashSet<>());
            set.addAll(order);
            // targets 依最終迭代順序重排，供錯誤訊息與位移測試使用。
            List<IsoMovingObject> sorted = new ArrayList<>();
            for (IsoMovingObject o : set) {
                if (targets.contains(o)) {
                    sorted.add(o);
                }
            }
            targets.clear();
            targets.addAll(sorted);
            animal.cell = cell(set);
        }

        private static float zLevel(Random r, float base) {
            int k = r.nextInt(20);
            if (k == 0) {
                return base + 2.0F;
            }
            if (k == 1) {
                return base + 1.0F;
            }
            if (k == 2) {
                return base + 0.5F;
            }
            return base;
        }
    }

    static final class State {
        IsoMovingObject spottedChr;
        float lastAlerted;
        int spottingDist;
        boolean alert;
        boolean mutate;
        boolean boom;
        int edit;

        static State random(Random r, World w) {
            State s = new State();
            s.spottedChr = w.targets.isEmpty() || r.nextBoolean() ? null : w.targets.get(r.nextInt(w.targets.size()));
            int k = r.nextInt(20);
            s.lastAlerted = k == 0 ? -1.5F : (k < 3 ? 1.0F + r.nextFloat() * 5 : 0.0F);
            s.spottingDist = r.nextInt(3) == 0 ? 1 + r.nextInt(40) : 10;
            s.alert = r.nextInt(4) == 0;
            s.mutate = r.nextInt(6) == 0;
            s.boom = r.nextInt(8) == 0;
            s.edit = r.nextInt(8) == 0 ? 1 + r.nextInt(3) : 0;
            return s;
        }

        static State plain(World w) {
            State s = new State();
            s.spottingDist = 10;
            return s;
        }

        void apply(TestAnimal a) {
            a.spottedChr = spottedChr;
            a.adef.spottingDist = spottingDist;
            a.behavior.lastAlerted = lastAlerted;
            a.behavior.alert = alert;
            a.behavior.mutate = mutate;
            a.behavior.boom = boom;
            a.behavior.edit = edit;
            a.behavior.calls.clear();
            a.spotted.clear();
            a.spotted.add(null); // 上一輪殘留，驗證兩條路徑都先清掉
        }

        @Override
        public String toString() {
            return "spottedChr=" + id(spottedChr) + " lastAlerted=" + lastAlerted + " dist=" + spottingDist
                    + " alert=" + alert + " mutate=" + mutate + " boom=" + boom;
        }
    }

    record Result(List<String> calls, IsoMovingObject spottedChr, int lastAlertedBits, List<IsoMovingObject> spottedList,
            int spottingDist, String thrown) {
        Result(TestAnimal a, String thrown) {
            this(new ArrayList<>(a.behavior.calls), a.spottedChr, Float.floatToRawIntBits(a.behavior.lastAlerted),
                    new ArrayList<>(a.spotted), a.adef.spottingDist, thrown);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Result r && calls.equals(r.calls) && spottedChr == r.spottedChr
                    && lastAlertedBits == r.lastAlertedBits && identityEquals(spottedList, r.spottedList)
                    && spottingDist == r.spottingDist && Objects.equals(thrown, r.thrown);
        }

        @Override
        public int hashCode() {
            return Objects.hash(calls, lastAlertedBits, spottingDist);
        }

        private static boolean identityEquals(List<IsoMovingObject> x, List<IsoMovingObject> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (x.get(i) != y.get(i)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("calls=" + calls + " spottedChr=" + id(spottedChr)
                    + " lastAlertedBits=" + lastAlertedBits + " spottingDist=" + spottingDist + " thrown=" + thrown + " spottedList=[");
            for (IsoMovingObject o : spottedList) {
                sb.append(id(o)).append(' ');
            }
            return sb.append(']').toString();
        }
    }

    /** 遊戲物件的 toString 會碰 SandboxOptions 等全域狀態，錯誤訊息只印身分雜湊。 */
    private static String id(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName() + "@" + System.identityHashCode(o);
    }

    /** 行為在 spotted() 中途拋出的例外；兩條路徑都必須原樣上拋並留下相同的殘留狀態。 */
    static final class Boom extends RuntimeException {
        Boom(String m) {
            super(m, null, false, false);
        }
    }

    // ---- 測試物件 ----

    static class TestAnimal extends IsoAnimal {
        IsoCell cell;
        Stack<IsoMovingObject> spotted;
        VBehavior behavior;
        float px;
        float py;
        float pz;

        TestAnimal() {
            super(null);
        }

        @Override public IsoCell getCell() { return cell; }
        @Override public Stack<IsoMovingObject> getSpottedList() { return spotted; }
        @Override public BaseAnimalBehavior getBehavior() { return behavior; }
        @Override public float getX() { return px; }
        @Override public float getY() { return py; }
        @Override public float getZ() { return pz; }
    }

    /** 照原版 spotted() 先重放前綴，再依距離做可觀測的感知／警戒／改門檻。 */
    static class VBehavior extends BaseAnimalBehavior {
        final List<String> calls = new ArrayList<>();
        boolean alert;
        boolean mutate;
        boolean boom;
        /** spotted() 中改動 objectList（一次）：1 移除迭代順序最後一個其他元素、2 加入 extra、3 移除 other 本身。 */
        int edit;
        IsoMovingObject extra;
        /** spotted() 中同步觸發這隻動物的視線檢查（一次）；vanillaRun 決定走原版或 AnimalLosScan。 */
        TestAnimal nested;
        static boolean vanillaRun;

        VBehavior(IsoAnimal parent) {
            super(parent);
        }

        @Override
        public void spotted(IsoMovingObject other, boolean forced, float dist) {
            parent.spottedChr = null;
            if (lastAlerted > 0.0F) {
                lastAlerted = lastAlerted - zombie.GameTime.getInstance().getMultiplier();
            }
            if (lastAlerted < 0.0F) {
                lastAlerted = 0.0F;
            }
            calls.add(System.identityHashCode(other) + "@" + Float.floatToRawIntBits(dist));
            if (dist < 4.0F) {
                parent.spottedChr = other;
            }
            if (alert && dist < 2.5F) {
                lastAlerted = 3.0F;
            }
            if (mutate && dist < 1.5F) {
                parent.adef.spottingDist += 30; // 門檻一口氣超過網格範圍（門檻＋16 格），必須改走完整順序
            }
            if (nested != null) {
                TestAnimal n = nested;
                nested = null;
                if (vanillaRun) {
                    n.updateLOS();
                } else {
                    AnimalLosScan.updateLOS(n);
                }
            }
            if (edit != 0 && dist < 4.0F) {
                Set<IsoMovingObject> s = parent.getCell().getObjectList();
                int e = edit;
                edit = 0;
                if (e == 1) {
                    IsoMovingObject victim = null;
                    for (IsoMovingObject o : s) {
                        if (o != parent && o != other) {
                            victim = o;
                        }
                    }
                    if (victim != null) {
                        s.remove(victim);
                    }
                } else if (e == 2) {
                    s.add(extra);
                } else {
                    s.remove(other);
                }
            }
            if (boom && dist < 3.0F) {
                throw new Boom(System.identityHashCode(other) + "@" + Float.floatToRawIntBits(dist));
            }
        }
    }

    static class TestPlayer extends IsoPlayer {
        float px;
        float py;
        float pz;
        boolean invisible;
        boolean ghost;
        IsoGridSquare sq;

        TestPlayer() {
            super(null);
        }

        @Override public float getX() { return px; }
        @Override public float getY() { return py; }
        @Override public float getZ() { return pz; }
        @Override public IsoGridSquare getCurrentSquare() { return sq; }
        @Override public boolean isInvisible() { return invisible; }
        @Override public boolean isGhostMode() { return ghost; }
    }

    private static TestAnimal animal(float x, float y, float z) throws Exception {
        TestAnimal a = alloc(TestAnimal.class);
        a.px = x;
        a.py = y;
        a.pz = z;
        a.spotted = new Stack<>();
        SPOTTED_LIST.set(a, a.spotted);
        AnimalDefinitions def = alloc(AnimalDefinitions.class);
        def.spottingDist = 10;
        a.adef = def;
        a.behavior = new VBehavior(a);
        return a;
    }

    private static IsoZombie zombie(float x, float y, float z, boolean onSquare) throws Exception {
        IsoZombie o = alloc(IsoZombie.class);
        X.setFloat(o, x);
        Y.setFloat(o, y);
        Z.setFloat(o, z);
        CURRENT.set(o, onSquare ? square : null);
        return o;
    }

    private static TestPlayer player(float x, float y, float z, boolean invisible, boolean ghost, boolean onSquare)
            throws Exception {
        TestPlayer p = alloc(TestPlayer.class);
        p.px = x;
        p.py = y;
        p.pz = z;
        p.invisible = invisible;
        p.ghost = ghost;
        p.sq = onSquare ? square : null;
        return p;
    }

    private static IsoCell cell(Set<IsoMovingObject> objects) throws Exception {
        IsoCell c = alloc(IsoCell.class);
        Field f = IsoCell.class.getDeclaredField("objectList");
        f.setAccessible(true);
        f.set(c, objects);
        return c;
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 往父類找
            }
        }
        throw new IllegalStateException("no field " + name);
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private AnimalLosIndexTest() {}
}
