package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.util.PZCalendar;

/**
 * W49 累積小時語意（A4）與直接呼叫上限端到端驗證：直接呼叫 dist 內手術後的 {@code IsoAnimal.updateStatsAway}，
 * 走真的頭部時數過濾與四個改道（{@code on}＝出貨組態、{@code off}＝{@code -Dmdc.animalCatchUpAccrual=0} 原版語意）。
 * 鎖：on 時餘數不丟、每累積 24 小時成長一次（age 跳 mod、growUp(true) 一次）、補 0 小時完全不動；
 * off 時照原版一次性 age += floor(h/24)×mod、hoursSurvived 設成 age×24、日曆午夜 growUp。
 * 兩者 hourGrow 都恰為補算時數、時鐘都推進補算時數；不經 AnimalAwayProbe 的直接呼叫（牲畜拖車 Lua）同樣截在上限。
 */
public final class AnimalCatchUpAccrualTest {

    private static int failed;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) throws Exception {
        boolean on = !(args.length > 0 && args[0].equals("off"));
        expect("旗標與 argv 相符", AnimalAwayProbe.accrualForTest() == on);

        GameTime gt = (GameTime) raw(GameTime.class);
        gt.setCalender(PZCalendar.getInstance());
        gt.setYear(2026);
        gt.setMonth(5);
        gt.setDay(10);
        GameTime.setInstance(gt);

        // 1. 載入中累積了 20 小時，補 10 小時（03:00 起，不跨午夜）。
        Farm a = farm(10, 260, at(9, 3));
        a.updateStatsAway(10);
        check("餘數 20＋10：on 成長一次（age 15、剩 6 小時）；off 丟掉餘數、不成長", a,
                on ? 15 : 10, on ? 366 : 240, on ? 1 : 0, 10, at(9, 13));

        // 2. 補 0 小時（doMeta 對一直載入中的動物）：on 完全不動；off 丟掉 20 小時餘數。
        Farm b = farm(10, 260, at(9, 3));
        b.updateStatsAway(0);
        check("補 0 小時：on 保留餘數；off 歸零", b, 10, on ? 260 : 240, 0, 0, at(9, 3));

        // 3. 無餘數、補 48 小時（跨兩個午夜）：兩種語意結果相同。
        Farm c = farm(10, 240, at(8, 0));
        c.updateStatsAway(48);
        check("無餘數 48 小時：兩者皆 age 20、成長兩次", c, 20, 480, 2, 48, at(10, 0));

        // 4. 餘數 10＋補 40 小時（12:00 起，跨兩個午夜）：on 累積 50 小時＝兩次成長、剩 2；off 只加一次 mod。
        Farm d = farm(10, 250, at(8, 12));
        d.updateStatsAway(40);
        check("餘數 10＋40：on age 20、剩 2 小時；off age 15、午夜 growUp 兩次", d,
                on ? 20 : 15, on ? 482 : 360, 2, 40, at(10, 4));

        // 5. 直接呼叫（牲畜拖車 Lua TrailerAnimalFood 以零件上次更新推算的 200 小時）：頭部過濾同樣截成 168 小時，
        //    兩種語意都成長 7 次；時鐘照原版從目前時鐘往後推進 168 小時。
        Farm trailer = farm(10, 240, at(1, 0));
        trailer.updateStatsAway(200);
        check("直接呼叫 200 小時：截成 168、age 45、成長 7 次", trailer, 45, 1080, 7, 168, at(8, 0));
        expect("直接呼叫計數：5 次、截斷 1 次", AnimalAwayProbe.directCallsForTest() == 5
                && AnimalAwayProbe.directLimitedForTest() == 1);

        expect("on 時由累積觸發的成長計數＝" + (on ? 12 : 0),
                AnimalAwayProbe.accrualGrowthsForTest() == (on ? 12 : 0));
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalCatchUpAccrualTest 全數通過（" + (on ? "on" : "off") + "）");
    }

    private static void check(String what, Farm f, int age, double hours, int growUps, int hourGrows, long clock) {
        FarmData d = (FarmData) f.getData();
        boolean ok = d.getAge() == age && f.getHoursSurvived() == hours && d.growUps == growUps
                && d.hourGrows == hourGrows && d.metaOnly && f.timeSinceLastUpdate == clock;
        expect(what + "（age=" + d.getAge() + " hours=" + f.getHoursSurvived() + " growUp=" + d.growUps
                + " hourGrow=" + d.hourGrows + "）", ok);
    }

    /** 2026-05-{day} {hour}:00:00.000（預設時區，與 PZCalendar.getInstance 相同）的毫秒。 */
    private static long at(int day, int hour) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.clear();
        cal.set(2026, java.util.Calendar.MAY, day, hour, 0, 0);
        return cal.getTimeInMillis();
    }

    private static Farm farm(int age, double hours, long clock) throws Exception {
        Farm f = (Farm) raw(Farm.class);
        FarmData d = (FarmData) raw(FarmData.class);
        d.parent = f;
        d.setAge(age);
        d.metaOnly = true;
        f.data = d;
        f.setHoursSurvived(hours);
        f.timeSinceLastUpdate = clock;
        Field zones = IsoAnimal.class.getDeclaredField("connectedDZone");
        zones.setAccessible(true);
        zones.set(f, new ArrayList<>());
        return f;
    }

    /** 替身資料：只記錄補算迴圈呼叫到的成長方法，其餘改成無副作用。 */
    public static class FarmData extends AnimalData {
        int hourGrows;
        int growUps;
        boolean metaOnly;

        /** 僅供編譯；實例由 serialization 分配器產生。 */
        FarmData() {
            super(null, null);
        }

        @Override
        public void hourGrow(boolean meta) {
            hourGrows++;
            metaOnly &= meta;
        }

        @Override
        public void growUp(boolean meta) {
            growUps++;
            metaOnly &= meta;
        }

        @Override
        public float getAgeGrowModifier() {
            return 5.0F;
        }

        @Override
        public void tryInseminateInMeta(PZCalendar realCal) {
        }

        @Override
        public void checkEggs(PZCalendar realCal, boolean meta) {
        }

        @Override
        public void init() {
        }
    }

    /** 替身動物：不覆寫 updateStatsAway，讓 dist 內手術後的原版方法實際執行。 */
    public static class Farm extends IsoAnimal {
        AnimalData data;

        /** 僅供編譯；實例由 serialization 分配器產生。 */
        Farm() {
            super((zombie.iso.IsoCell) null);
        }

        @Override
        public AnimalData getData() {
            return data;
        }

        @Override
        public boolean isWild() {
            return false;
        }

        @Override
        public boolean checkKilledByMetaPredator(int hour) {
            return false;
        }
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "acu pass  " : "acu FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalCatchUpAccrualTest() {}
}
