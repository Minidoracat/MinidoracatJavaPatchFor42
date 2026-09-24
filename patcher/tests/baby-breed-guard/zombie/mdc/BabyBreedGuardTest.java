package zombie.mdc;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;

import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalBreed;

/**
 * W33 BabyBreedGuard 行為驗證（無參數＝啟用、{@code off}＝kill switch）。
 * 用真 AnimalDefinitions.getDef／getBreedByName 查詢；鎖：品種查得到才委派原版、查不到不委派且回 null、
 * babyType null 仍交原版（原版自己回 null）、off 一律委派、委派例外穿透。
 */
public final class BabyBreedGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", BabyBreedGuard.enabledForTest() == !off);

        AnimalDefinitions.animalDefs = new HashMap<>();
        AnimalDefinitions piglet = def(null, breed("landrace"));
        AnimalDefinitions.animalDefs.put("piglet", piglet);

        Fake ok = mother("piglet", "landrace");
        IsoAnimal marker = (IsoAnimal) raw(Fake.class);
        ok.result = marker;
        expect("品種相符：委派原版並回傳其結果", BabyBreedGuard.addBaby(ok) == marker && ok.calls == 1);

        Fake badBreed = mother("piglet", "modbreed");
        IsoAnimal r = BabyBreedGuard.addBaby(badBreed);
        expect(off ? "off：品種不符仍委派原版" : "品種不符：不委派、回 null",
                off ? badBreed.calls == 1 : badBreed.calls == 0 && r == null);

        Fake badDef = mother("nosuchbaby", "landrace");
        BabyBreedGuard.addBaby(badDef);
        expect(off ? "off：幼崽定義缺失仍委派" : "幼崽定義缺失：不委派", badDef.calls == (off ? 1 : 0));

        Fake noBaby = mother(null, "landrace");
        BabyBreedGuard.addBaby(noBaby);
        expect("babyType null 交原版處理", noBaby.calls == 1);

        Fake boom = mother("piglet", "landrace");
        boom.toThrow = new IllegalStateException("x");
        boolean thrown = false;
        try {
            BabyBreedGuard.addBaby(boom);
        } catch (IllegalStateException e) {
            thrown = e == boom.toThrow;
        }
        expect("委派例外原樣穿透", thrown);

        if (!off) {
            expect("blocked=2", BabyBreedGuard.blockedForTest() == 2);
            expect("anomalies=0", BabyBreedGuard.anomaliesForTest() == 0);
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("BabyBreedGuardTest 全數通過" + (off ? "（off）" : ""));
    }

    public static class Fake extends IsoAnimal {
        AnimalBreed motherBreed;
        IsoAnimal result;
        RuntimeException toThrow;
        int calls;

        Fake() {
            super((zombie.iso.IsoCell) null);
        }

        @Override
        public IsoAnimal addBaby() {
            calls++;
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }

        @Override
        public AnimalBreed getBreed() {
            return motherBreed;
        }

        @Override
        public String getAnimalType() {
            return "sow";
        }

        @Override
        public int getAnimalID() {
            return 1;
        }
    }

    private static Fake mother(String babyType, String breedName) throws Exception {
        Fake m = (Fake) raw(Fake.class);
        m.adef = def(babyType);
        m.motherBreed = breed(breedName);
        return m;
    }

    private static AnimalDefinitions def(String babyType, AnimalBreed... breeds) throws Exception {
        AnimalDefinitions d = (AnimalDefinitions) raw(AnimalDefinitions.class);
        d.babyType = babyType;
        d.breeds = new ArrayList<>(java.util.List.of(breeds));
        return d;
    }

    private static AnimalBreed breed(String name) throws Exception {
        AnimalBreed b = (AnimalBreed) raw(AnimalBreed.class);
        b.name = name;
        return b;
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "bbg pass  " : "bbg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private BabyBreedGuardTest() {}
}
