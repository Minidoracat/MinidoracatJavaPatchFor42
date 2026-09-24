package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.areas.DesignationZone;
import zombie.util.PZCalendar;

/**
 * W32 AnimalAwayProbe 行為驗證（獨立 JVM；無參數＝啟用、{@code off}＝kill switch）。
 * 鎖：委派恰一次且時數原樣；動物自身離線時數於委派「前」讀取；陳舊 zone 時數計入 mismatch；
 * 委派例外穿透；off 完全不計數。
 */
public final class AnimalAwayProbeTest {

    private static int failed;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", AnimalAwayProbe.enabledForTest() == !off);

        // 真 GameTime：getCalender 由年月日＋timeOfDay 重算（未初始化 timeOfDay＝0 點）。
        GameTime gt = (GameTime) raw(GameTime.class);
        gt.setCalender(PZCalendar.getInstance());
        gt.setYear(2026);
        gt.setMonth(5);
        gt.setDay(10);
        GameTime.setInstance(gt);
        PZCalendar cal = gt.getCalender();

        // 陳舊 zone：vanilla 算出 200h，但動物 2h 前才卸載。
        Fake a = fake();
        a.timeSinceLastUpdate = cal.getTimeInMillis() - 2 * HOUR;
        a.zone = (DesignationZone) raw(DesignationZone.class);
        a.zone.name = "pen";
        expect("動物自身離線時數＝2", AnimalAwayProbe.animalHoursAway(a) == 2L);
        AnimalAwayProbe.updateStatsAway(a, 200);
        expect("委派恰一次且時數原樣", a.calls == 1 && a.lastHours == 200);

        // 正常：兩者一致、未達門檻。
        Fake b = fake();
        b.timeSinceLastUpdate = cal.getTimeInMillis() - 3 * HOUR;
        AnimalAwayProbe.updateStatsAway(b, 3);
        expect("正常路徑委派", b.calls == 1 && b.lastHours == 3);

        // zone 路徑（doMeta）同契約。
        Fake z = fake();
        z.timeSinceLastUpdate = cal.getTimeInMillis() - HOUR;
        AnimalAwayProbe.updateStatsAwayZone(z, 1);
        expect("zone 路徑委派", z.calls == 1 && z.lastHours == 1);

        // 無時間戳（-1）不算 mismatch。
        Fake c = fake();
        c.timeSinceLastUpdate = -1L;
        expect("無時間戳回 -1", AnimalAwayProbe.animalHoursAway(c) == -1L);
        AnimalAwayProbe.updateStatsAway(c, 500);

        Fake d = fake();
        d.toThrow = new IllegalStateException("boom");
        boolean thrown = false;
        try {
            AnimalAwayProbe.updateStatsAway(d, 5);
        } catch (IllegalStateException e) {
            thrown = e == d.toThrow;
        }
        expect("委派例外原樣穿透", thrown);

        if (off) {
            expect("off 不計數", AnimalAwayProbe.callsForTest() == 0);
        } else {
            expect("calls=4（例外那筆不計）", AnimalAwayProbe.callsForTest() == 4);
            expect("big=2（200h 與 500h）", AnimalAwayProbe.bigForTest() == 2);
            expect("mismatch=1（僅陳舊 zone；無時間戳不算）", AnimalAwayProbe.mismatchForTest() == 1);
            expect("anomalies=0", AnimalAwayProbe.anomaliesForTest() == 0);
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalAwayProbeTest 全數通過" + (off ? "（off）" : ""));
    }

    /** 替身：只覆寫改道與診斷會派送到的方法；updateStatsAway 模擬 vanilla 推進時間戳。 */
    public static class Fake extends IsoAnimal {
        int calls;
        int lastHours;
        DesignationZone zone;
        RuntimeException toThrow;

        /** 僅供編譯；實例一律由 serialization 分配器產生，不跑 IsoAnimal 建構子。 */
        Fake() {
            super((zombie.iso.IsoCell) null);
        }

        @Override
        public void updateStatsAway(int hours) {
            calls++;
            lastHours = hours;
            if (toThrow != null) {
                throw toThrow;
            }
            timeSinceLastUpdate += hours * HOUR;
        }

        @Override
        public DesignationZone getZone() {
            return zone;
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public String getAnimalType() {
            return "sheep";
        }

        @Override
        public int getAnimalID() {
            return 7;
        }
    }

    private static Fake fake() throws Exception {
        return (Fake) raw(Fake.class);
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "aap pass  " : "aap FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalAwayProbeTest() {}
}
