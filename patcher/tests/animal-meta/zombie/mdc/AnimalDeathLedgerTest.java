package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.characters.animals.IsoAnimal;

/**
 * W39 AnimalDeathLedger 行為驗證（{@code on}／{@code off}）：剛補算過的死亡被標記、無補算不標、
 * 補算太久以前不算、每分鐘明細上限、半建構（data null）動物也能記錄不出錯、off 完全不計。
 */
public final class AnimalDeathLedgerTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", AnimalDeathLedger.enabledForTest() == !off);

        IsoAnimal fed = animal();
        AnimalDeathLedger.noteCatchUp(fed, 62);
        AnimalDeathLedger.noteCatchUp(fed, 62);
        AnimalDeathLedger.onDeath(fed);
        AnimalDeathLedger.onDeath(animal());
        if (off) {
            expect("off：不計", AnimalDeathLedger.deathsForTest() == 0);
        } else {
            expect("deaths=2", AnimalDeathLedger.deathsForTest() == 2);
            expect("補算後死亡恰 1（無補算的不算）", AnimalDeathLedger.afterCatchUpForTest() == 1);
            expect("補算資訊寫進明細", AnimalDeathLedger.describe(fed, false, new long[]{0, 2, 124}, 5)
                    .contains("catchUp=2x/124h"));
            for (int i = 0; i < 45; i++) {
                AnimalDeathLedger.onDeath(animal());
            }
            expect("每分鐘明細上限 40，其餘只計數", AnimalDeathLedger.suppressedForTest() == 47 - 40);
            expect("data null 的半建構動物照記、anomalies=0", AnimalDeathLedger.anomaliesForTest() == 0);
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalDeathLedgerTest 全數通過" + (off ? "（off）" : ""));
    }

    private static IsoAnimal animal() throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(IsoAnimal.class, objCtor);
        alloc.setAccessible(true);
        return (IsoAnimal) alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "adl pass  " : "adl FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalDeathLedgerTest() {}
}
