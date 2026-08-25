package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.random.RandStandard;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoHutch;

/**
 * W17 hutch load 回傳守衛行為測試。argv：enforce（預設出貨）／observe／off；MODE 是
 * static final，三組態各跑獨立 JVM並自驗，property 拼錯不得默默把 enforce 跑三次假綠。
 *
 * <p><b>確定性觸發「有空槽但 vanilla false」</b>：先照 W14 坑解呼
 * {@link RandStandard#init()}，再把 RandAbstract.rand 反射換成 {@link ZeroRandom}
 * （{@code nextInt(bound)} 恆 0）。maxAnimals=2、slot0 已佔、slot1 空時，vanilla 101 次
 * 全骰 slot0，最後查 animalInside[0] 仍佔用 → false；helper 必須順序找到 slot1。
 * 這比「滿舍 20 隻最後一隻有 0.59% 機率 miss」穩定：不靠機率、不靠主機速度。
 *
 * <p>IsoHutch/IsoAnimal/AnimalData 以 Unsafe.allocateInstance 取得，繞過 sprite/world/Lua
 * 建構鏈；測試只填 addAnimalInside/forceInto 真正會讀的欄位。forceInto 的六步狀態
 * （map/backlink/preferred/hutchPosition/itemID/tryRemove）用真 game class 驗證。
 */
public final class HutchLoadGuardTest {

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "enforce" : args[0];
        int wantMode = switch (mode) {
            case "observe" -> HutchLoadGuard.MODE_OBSERVE;
            case "off" -> HutchLoadGuard.MODE_OFF;
            default -> HutchLoadGuard.MODE_ENFORCE;
        };
        if (HutchLoadGuard.MODE != wantMode) {
            throw new AssertionError("mode property 未生效：got=" + HutchLoadGuard.MODE
                    + " want=" + wantMode);
        }

        // W14 的真 IsoAnimal 注入坑解：先讓 RandStandard 完成 clinit/init，再換確定性 Random。
        RandStandard.INSTANCE.init();
        Field rand = findField(RandStandard.class, "rand");
        rand.setAccessible(true);
        rand.set(RandStandard.INSTANCE, new ZeroRandom());

        Counts before = new Counts();

        // A. 空舍正常路徑：vanilla 自己成功，helper 不介入；狀態逐項對齊。
        IsoHutch normal = hutch(1);
        IsoAnimal normalAnimal = animal(41);
        if (!HutchLoadGuard.addInside(normal, normalAnimal, false)) {
            throw new AssertionError("空舍正常路徑應成功");
        }
        assertPlaced("空舍正常路徑", normal, normalAnimal, 0);

        // B. 有空槽但 vanilla 確定 false（ZeroRandom 恆骰已佔 slot0）。
        IsoHutch available = hutch(2);
        IsoAnimal occupant = animal(42);
        available.animalInside.put(0, occupant);
        IsoAnimal candidate = animal(43);
        boolean rescued = HutchLoadGuard.addInside(available, candidate, false);
        if (wantMode == HutchLoadGuard.MODE_ENFORCE) {
            if (!rescued) {
                throw new AssertionError("enforce 應救回有空槽但 vanilla false 的動物");
            }
            assertPlaced("enforce force-put", available, candidate, 1);
        } else {
            if (rescued || available.animalInside.containsValue(candidate)) {
                throw new AssertionError(mode + " 必須保留 vanilla false、不強制入位");
            }
        }

        // C. 真滿：無槽可救，所有模式都回 false；observe/enforce 必須有 CRITICAL 計數。
        IsoHutch full = hutch(1);
        full.animalInside.put(0, animal(44));
        if (HutchLoadGuard.addInside(full, animal(45), false)) {
            throw new AssertionError("真滿 hutch 不可能救回第 2 隻");
        }

        // D. forceInto 六步直接驗：這是 force-put 的可測核心；Mutation 拿掉 put/backlink/
        // preferred/hutchPosition/itemID 任一條，此段即紅。
        IsoHutch direct = hutch(2);
        IsoAnimal directAnimal = animal(99);
        HutchLoadGuard.forceInto(direct, directAnimal, 1);
        assertPlaced("forceInto 六步", direct, directAnimal, 1);

        // D2. 純選槽契約：先 clean，再 dead-body fallback；key→null 與 vanilla get()==null 同義。
        IsoHutch cleanPriority = hutch(2);
        cleanPriority.deadBodiesInside.put(0, alloc(IsoDeadBody.class));
        if (HutchLoadGuard.findSlot(cleanPriority) != 1) {
            throw new AssertionError("應優先選無 dead body 的 clean slot1");
        }
        cleanPriority.deadBodiesInside.put(1, alloc(IsoDeadBody.class));
        if (HutchLoadGuard.findSlot(cleanPriority) != 0) {
            throw new AssertionError("全為 dead-body slot 時應 fallback 到第一個 animalInside 空槽");
        }
        IsoHutch nullValue = hutch(2);
        nullValue.animalInside.put(0, null);
        nullValue.animalInside.put(1, animal(47));
        if (HutchLoadGuard.findSlot(nullValue) != 0) {
            throw new AssertionError("key→null 必須與 vanilla 一樣視為空槽");
        }

        // E. 重複 add：動物已在 map，vanilla warn+false；enforce 也不可塞第二槽。
        IsoHutch duplicate = hutch(2);
        IsoAnimal same = animal(46);
        duplicate.animalInside.put(0, same);
        if (HutchLoadGuard.addInside(duplicate, same, false)
                || duplicate.animalInside.size() != 1) {
            throw new AssertionError("重複 add 不得被 force 成雙槽同體");
        }

        // F. 規格的滿舍案例：enforce 下 20 隻全存活（ZeroRandom 讓第 2..20 隻都走
        // force-put，確定性覆蓋 fallback），第 21 隻真滿→CRITICAL。
        if (wantMode == HutchLoadGuard.MODE_ENFORCE) {
            IsoHutch twenty = hutch(20);
            for (int i = 0; i < 20; i++) {
                IsoAnimal a = animal(100 + i);
                if (!HutchLoadGuard.addInside(twenty, a, false)) {
                    throw new AssertionError("第 " + (i + 1) + " 隻未存活");
                }
            }
            if (twenty.animalInside.size() != 20) {
                throw new AssertionError("20 隻載入後 map size=" + twenty.animalInside.size());
            }
            if (HutchLoadGuard.addInside(twenty, animal(121), false)) {
                throw new AssertionError("第 21 隻應為真滿 CRITICAL");
            }
        }

        Counts after = new Counts();
        if (wantMode == HutchLoadGuard.MODE_OFF) {
            // off 純委派：所有 guard 計數零增量；A/B/C/E 的 vanilla 行為仍實際執行。
            before.assertNoDelta(after);
        } else {
            // A/B/C/E 共四次 addInside（forceInto 直接呼叫不算 delegated）。
            assertDelta("delegated", before.delegated, after.delegated,
                    wantMode == HutchLoadGuard.MODE_ENFORCE ? 25 : 4);
            // B、C、E 都是 vanilla false；enforce F 另有 19 次 force＋第21真滿＝20次 false。
            long wantRejects = wantMode == HutchLoadGuard.MODE_ENFORCE ? 23 : 3;
            assertDelta("vanillaRejects", before.vanillaRejects, after.vanillaRejects, wantRejects);
            if (wantMode == HutchLoadGuard.MODE_ENFORCE) {
                // B 1 次＋F 第2..20隻 19次＝20 force。
                assertDelta("forced", before.forced, after.forced, 20);
                assertDelta("wouldForce", before.wouldForce, after.wouldForce, 0);
                // C 1＋F 第21隻 1＝2 critical。
                assertDelta("critical", before.critical, after.critical, 2);
            } else {
                assertDelta("forced", before.forced, after.forced, 0);
                assertDelta("wouldForce", before.wouldForce, after.wouldForce, 1);
                assertDelta("critical", before.critical, after.critical, 1);
            }
            assertDelta("duplicates", before.duplicates, after.duplicates, 1);
        }
        assertDelta("anomalies", before.anomalies, after.anomalies, 0);
        System.out.println("HutchLoadGuardTest OK mode=" + HutchLoadGuard.MODE);
    }

    private static IsoHutch hutch(int maxAnimals) throws Exception {
        IsoHutch hutch = alloc(IsoHutch.class);
        hutch.animalInside = new HashMap<>();
        hutch.deadBodiesInside = new HashMap<Integer, IsoDeadBody>();
        hutch.animalOutside = new ArrayList<>();
        set(hutch, "nestBoxes", new HashMap<>());
        set(hutch, "maxAnimals", maxAnimals);
        // 避免 getMaxNestBox() 的 lazy def.rawgetInt；-1 使 checkNestBoxPrefPosition
        // 的 0..max 迴圈零次（本測試沒有 nest box，與 fixture 語意一致）。
        set(hutch, "maxNestBox", -1);
        return hutch;
    }

    private static IsoAnimal animal(int itemID) throws Exception {
        IsoAnimal animal = alloc(IsoAnimal.class);
        AnimalData data = alloc(AnimalData.class);
        data.setPreferredHutchPosition(-1);
        data.setHutchPosition(-1);
        animal.setData(data);
        animal.setItemID(itemID);
        return animal;
    }

    private static void assertPlaced(String what, IsoHutch hutch, IsoAnimal animal, int slot) {
        if (hutch.animalInside.get(slot) != animal
                || animal.hutch != hutch
                || animal.getData().getPreferredHutchPosition() != slot
                || animal.getData().getHutchPosition() != slot
                || animal.getItemID() != 0) {
            throw new AssertionError(what + " 狀態不完整：map=" + hutch.animalInside.get(slot)
                    + " backlink=" + animal.hutch
                    + " preferred=" + animal.getData().getPreferredHutchPosition()
                    + " hutchPosition=" + animal.getData().getHutchPosition()
                    + " itemID=" + animal.getItemID());
        }
    }

    private static void assertDelta(String what, long before, long after, long want) {
        long got = after - before;
        if (got != want) {
            throw new AssertionError(what + " delta=" + got + " want=" + want);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
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
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    /** 讓 Rand.Next(min,max) 永遠回 min；不依賴 seed 或機率。 */
    private static final class ZeroRandom extends Random {
        @Override
        public int nextInt(int bound) {
            return 0;
        }
    }

    private static final class Counts {
        final long delegated = HutchLoadGuard.delegatedForTest();
        final long vanillaRejects = HutchLoadGuard.vanillaRejectsForTest();
        final long forced = HutchLoadGuard.forcedForTest();
        final long wouldForce = HutchLoadGuard.wouldForceForTest();
        final long critical = HutchLoadGuard.criticalForTest();
        final long duplicates = HutchLoadGuard.duplicatesForTest();
        final long anomalies = HutchLoadGuard.anomaliesForTest();

        void assertNoDelta(Counts after) {
            assertDelta("off delegated", delegated, after.delegated, 0);
            assertDelta("off vanillaRejects", vanillaRejects, after.vanillaRejects, 0);
            assertDelta("off forced", forced, after.forced, 0);
            assertDelta("off wouldForce", wouldForce, after.wouldForce, 0);
            assertDelta("off critical", critical, after.critical, 0);
            assertDelta("off duplicates", duplicates, after.duplicates, 0);
        }
    }
}
