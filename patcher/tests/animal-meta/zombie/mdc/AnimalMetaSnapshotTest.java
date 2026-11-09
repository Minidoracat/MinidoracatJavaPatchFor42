package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import zombie.characters.animals.IsoAnimal;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.objects.IsoHutch;

/**
 * W38 行為驗證（{@code on}／{@code off}），走 dist 內手術後的真 {@code DesignationZoneAnimal.doMeta}（測試 JVM 沒有世界，
 * 原版 {@code check()} 在 {@code isFullyStreamed()} 判 false 後直接返回）。畜牧區 2 隻成年＋3 隻幼體、動物沒有自身時鐘
 * （W42 不截時數）：42.22.2 原版幼體迴圈外層多跑「動物總數」圈，off 重現每隻幼體被補算 5 次；on 每隻恰一次、成年不受影響，
 * 下一次 doMeta 重新起算。
 */
public final class AnimalMetaSnapshotTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", AnimalMetaSnapshot.enabledForTest() == !off);

        DesignationZoneAnimal zone = (DesignationZoneAnimal) raw(DesignationZoneAnimal.class);
        Set<IsoAnimal> animals = new HashSet<>();
        set(zone, "animals", animals);
        set(zone, "hutchs", new ArrayList<IsoHutch>());
        Map<Fake, Integer> before = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            Fake f = (Fake) raw(Fake.class);
            f.baby = i >= 2;
            f.timeSinceLastUpdate = -1L;
            animals.add(f);
        }

        zone.doMeta(6);
        int babies = 0;
        boolean ok = true;
        for (IsoAnimal a : animals) {
            Fake f = (Fake) a;
            int want = f.baby && off ? 5 : 1;
            ok &= f.calls == want && f.hoursTotal == 6 * want && f.wanders == 1;
            babies += f.baby ? 1 : 0;
            before.put(f, f.calls);
        }
        expect(off ? "off：成年各 1 次、幼體各 5 次（42.22.2 原版）、每 6h" : "on：每隻恰補算一次 6h、forceWanderNow 照原版各 1 次",
                ok && babies == 3);
        expect("重複次數計數（on 12、off 0）", AnimalMetaSnapshot.repeatsForTest() == (off ? 0 : 12));

        zone.doMeta(2);
        boolean again = true;
        for (Map.Entry<Fake, Integer> e : before.entrySet()) {
            int want = e.getKey().baby && off ? 5 : 1;
            again &= e.getKey().calls - e.getValue() == want;
        }
        expect("下一次 doMeta 重新起算", again);
        expect("doMeta 之外直接呼叫照常委派", AnimalMetaSnapshot.first(before.keySet().iterator().next()));

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalMetaSnapshotTest 全數通過" + (off ? "（off）" : ""));
    }

    /** 替身：只覆寫 doMeta／補算會派送到的方法；實例由 serialization 分配器產生。 */
    public static class Fake extends IsoAnimal {
        boolean baby;
        int calls, hoursTotal, wanders;

        Fake() {
            super((zombie.iso.IsoCell) null);
        }

        @Override
        public boolean isBaby() {
            return baby;
        }

        @Override
        public void updateStatsAway(int hours) {
            calls++;
            hoursTotal += hours;
        }

        @Override
        public void forceWanderNow() {
            wanders++;
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public String getAnimalType() {
            return "chick";
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = DesignationZoneAnimal.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "ams pass  " : "ams FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalMetaSnapshotTest() {}
}
