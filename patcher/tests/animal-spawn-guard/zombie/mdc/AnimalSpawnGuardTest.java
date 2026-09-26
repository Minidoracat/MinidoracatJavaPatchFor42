package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;

/**
 * W37 AnimalSpawnGuard 行為驗證（無參數＝啟用、{@code off}＝kill switch）。
 * 鎖：建構失敗（data null、帶座標）從 addList／objectList（依 isSafeToAdd）撤出；(IsoCell) 載入用
 * 0,0,0 與正常建構不碰；grow／addBaby 只吞「本次呼叫有建構失敗」的 NPE，其餘例外穿透；off 全直通。
 */
public final class AnimalSpawnGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", AnimalSpawnGuard.enabledForTest() == !off);

        IsoCell cell = (IsoCell) raw(IsoCell.class);
        Set<IsoMovingObject> objects = new HashSet<>(), adds = new HashSet<>(), removes = new HashSet<>();
        set(cell, "objectList", objects);
        set(cell, "addList", adds);
        set(cell, "removeList", removes);

        // update 中（not safe）：IsoGameCharacter 建構子把物件放進 addList。
        cell.setSafeToAdd(false);
        Fake broken = fake(cell, 101, 103);
        adds.add(broken);
        AnimalSpawnGuard.afterCtor(broken);
        expect(off ? "off：失敗物件留在 addList（原版洩漏）" : "unsafe：失敗物件自 addList 撤出",
                adds.contains(broken) == off);

        // update 外（safe）：直接進 objectList。
        cell.setSafeToAdd(true);
        Fake broken2 = fake(cell, 102, 104);
        objects.add(broken2);
        AnimalSpawnGuard.afterCtor(broken2);
        expect(off ? "off：失敗物件留在 objectList" : "safe：失敗物件自 objectList 撤出",
                objects.contains(broken2) == off);

        Fake loading = fake(cell, 0, 0);
        objects.add(loading);
        AnimalSpawnGuard.afterCtor(loading);
        expect("(IsoCell) 載入用 0,0,0 不碰", objects.contains(loading));

        Fake healthy = fake(cell, 10, 10);
        set(healthy, "data", raw(AnimalData.class));
        objects.add(healthy);
        AnimalSpawnGuard.afterCtor(healthy);
        expect("正常建構（data 非 null）不碰", objects.contains(healthy));
        if (!off) {
            expect("ctorFailures=2 removed=2", AnimalSpawnGuard.ctorFailuresForTest() == 2
                    && AnimalSpawnGuard.removedForTest() == 2);
        }

        // grow：該次建構失敗造成的 NPE。
        Fake chick = fake(cell, 103, 101);
        FakeData growsBroken = data(chick, () -> {
            AnimalSpawnGuard.afterCtor(fake(cell, 103, 101));
            throw new NullPointerException("getData() is null");
        });
        expect(off ? "off：grow NPE 原樣穿透" : "grow 建構失敗 NPE 被吞", throwsNpe(() -> AnimalSpawnGuard.grow(growsBroken, "hen")) == off);
        expect("grow 確實委派原版", growsBroken.calls == 1);

        FakeData unrelatedNpe = data(chick, () -> { throw new NullPointerException("other"); });
        expect("無建構失敗的 NPE 一律穿透", throwsNpe(() -> AnimalSpawnGuard.grow(unrelatedNpe, "hen")));

        FakeData boom = data(chick, () -> {
            AnimalSpawnGuard.afterCtor(fake(cell, 1, 1));
            throw new IllegalStateException("x");
        });
        boolean ise = false;
        try {
            AnimalSpawnGuard.grow(boom, "hen");
        } catch (IllegalStateException e) {
            ise = true;
        }
        expect("非 NPE 例外穿透", ise);

        FakeData ok = data(chick, () -> {});
        AnimalSpawnGuard.grow(ok, "hen");
        expect("正常 grow 委派一次", ok.calls == 1);

        // addBaby：同一規則，被吞時回 null。
        Fake mother = fake(cell, 101, 100);
        mother.baby = () -> {
            AnimalSpawnGuard.afterCtor(fake(cell, 101, 100));
            throw new NullPointerException("getData() is null");
        };
        boolean npe = false;
        IsoAnimal r = null;
        try {
            r = AnimalSpawnGuard.addBaby(mother);
        } catch (NullPointerException e) {
            npe = true;
        }
        expect(off ? "off：addBaby NPE 穿透" : "addBaby 建構失敗回 null", off ? npe : !npe && r == null);
        IsoAnimal marker = fake(cell, 3, 3);
        mother.baby = null;
        mother.result = marker;
        expect("正常 addBaby 回傳原版結果", AnimalSpawnGuard.addBaby(mother) == marker);

        if (!off) {
            expect("swallowed=2 anomalies=0", AnimalSpawnGuard.swallowedForTest() == 2
                    && AnimalSpawnGuard.anomaliesForTest() == 0);
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalSpawnGuardTest 全數通過" + (off ? "（off）" : ""));
    }

    public static class Fake extends IsoAnimal {
        IsoCell cell;
        float fx, fy;
        Runnable baby;
        IsoAnimal result;

        Fake() {
            super((IsoCell) null);
        }

        @Override public IsoCell getCell() { return cell; }
        @Override public float getX() { return fx; }
        @Override public float getY() { return fy; }
        @Override public float getZ() { return 0f; }
        @Override public String getAnimalType() { return "chick"; }
        @Override public int getAnimalID() { return 7; }

        @Override
        public IsoAnimal addBaby() {
            if (baby != null) {
                baby.run();
            }
            return result;
        }
    }

    public static class FakeData extends AnimalData {
        Runnable body;
        int calls;

        FakeData() {
            super(null, null);
        }

        @Override
        public void grow(String newType) {
            calls++;
            body.run();
        }
    }

    private static Fake fake(IsoCell cell, float x, float y) throws RuntimeException {
        try {
            Fake f = (Fake) raw(Fake.class);
            f.cell = cell;
            f.fx = x + 0.5f;   // IsoGameCharacter 建構子設 x+0.5；0,0 仍 (int)==0
            f.fy = y + 0.5f;
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FakeData data(IsoAnimal parent, Runnable body) throws Exception {
        FakeData d = (FakeData) raw(FakeData.class);
        d.parent = parent;
        d.body = body;
        return d;
    }

    private static boolean throwsNpe(Runnable r) {
        try {
            r.run();
            return false;
        } catch (NullPointerException e) {
            return true;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 往父類找
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "spg pass  " : "spg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalSpawnGuardTest() {}
}
