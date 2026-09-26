package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.iso.areas.DesignationZone;
import zombie.util.PZCalendar;

/**
 * W32／W42 AnimalAwayProbe 行為驗證（獨立 JVM；無參數＝出貨組態、{@code off}＝觀測 kill switch、
 * {@code nocap}＝W42 kill switch）。
 * 鎖：委派恰一次；動物自身離線時數於委派「前」讀取；W42 補算取 zone 與自身時數較小值、無紀錄沿用 vanilla、
 * 時鐘在未來補 0；活著的每小時刷新時鐘；nocap 回 vanilla 時數且不刷新；委派例外穿透；off 完全不計數。
 */
public final class AnimalAwayProbeTest {

    private static int failed;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean off = mode.equals("off");
        boolean cap = !mode.equals("nocap");
        expect("旗標與 argv 相符", AnimalAwayProbe.enabledForTest() == !off && AnimalAwayProbe.capForTest() == cap);

        // 真 GameTime：getCalender 由年月日＋timeOfDay 重算（未初始化 timeOfDay＝0 點）。
        GameTime gt = (GameTime) raw(GameTime.class);
        gt.setCalender(PZCalendar.getInstance());
        gt.setYear(2026);
        gt.setMonth(5);
        gt.setDay(10);
        GameTime.setInstance(gt);
        PZCalendar cal = gt.getCalender();
        long now = cal.getTimeInMillis();

        // 陳舊 zone：vanilla 算出 200h，但動物 2h 前才卸載。
        Fake a = fake();
        a.timeSinceLastUpdate = now - 2 * HOUR;
        a.zone = (DesignationZone) raw(DesignationZone.class);
        a.zone.name = "pen";
        expect("動物自身離線時數＝2", AnimalAwayProbe.animalHoursAway(a) == 2L);
        AnimalAwayProbe.updateStatsAway(a, 200);
        expect("陳舊 zone：委派恰一次，W42 補 2h（nocap 補 200h）", a.calls == 1 && a.lastHours == (cap ? 2 : 200));

        // 正常：兩者一致、未達門檻。
        Fake b = fake();
        b.timeSinceLastUpdate = now - 3 * HOUR;
        AnimalAwayProbe.updateStatsAway(b, 3);
        expect("正常路徑時數不變", b.calls == 1 && b.lastHours == 3);

        // zone 路徑（doMeta）同契約。
        Fake z = fake();
        z.timeSinceLastUpdate = now - HOUR;
        AnimalAwayProbe.updateStatsAwayZone(z, 1);
        expect("zone 路徑委派", z.calls == 1 && z.lastHours == 1);

        // 無時間戳（-1）：沿用 vanilla 時數、不算 mismatch。
        Fake c = fake();
        c.timeSinceLastUpdate = -1L;
        expect("無時間戳回 NO_RECORD", AnimalAwayProbe.animalHoursAway(c) == AnimalAwayProbe.NO_RECORD);
        AnimalAwayProbe.updateStatsAway(c, 500);
        expect("無時間戳沿用 vanilla 500h", c.lastHours == 500);

        // 時鐘在未來（先前多補 38h 且未卸載）：W42 補 0。
        Fake f = fake();
        f.timeSinceLastUpdate = now + 38 * HOUR;
        AnimalAwayProbe.updateStatsAwayZone(f, 48);
        expect("時鐘在未來：W42 補 0h（nocap 補 48h）", f.calls == 1 && f.lastHours == (cap ? 0 : 48));

        Fake d = fake();
        d.toThrow = new IllegalStateException("boom");
        boolean thrown = false;
        try {
            AnimalAwayProbe.updateStatsAway(d, 5);
        } catch (IllegalStateException e) {
            thrown = e == d.toThrow;
        }
        expect("委派例外原樣穿透", thrown);

        // 活著的每小時：刷新動物自身時鐘並照常 hourGrow。
        Fake live = fake();
        live.timeSinceLastUpdate = now - 240 * HOUR;
        FakeData data = (FakeData) raw(FakeData.class);
        data.parent = live;
        AnimalAwayProbe.liveHourGrow(data, false);
        expect("liveHourGrow 委派 hourGrow(false) 恰一次", data.grows == 1 && !data.lastMeta);
        expect("活著的每小時刷新時鐘（nocap 不動）",
                live.timeSinceLastUpdate == (cap ? now : now - 240 * HOUR));

        if (off) {
            expect("off 不計數", AnimalAwayProbe.callsForTest() == 0);
        } else {
            expect("calls=5（例外那筆不計）", AnimalAwayProbe.callsForTest() == 5);
            expect("big=3（200h、500h、48h）", AnimalAwayProbe.bigForTest() == 3);
            expect("mismatch=2（陳舊 zone、時鐘在未來；無時間戳不算）", AnimalAwayProbe.mismatchForTest() == 2);
            expect("capped=" + (cap ? 2 : 0), AnimalAwayProbe.cappedForTest() == (cap ? 2 : 0));
            expect("anomalies=0", AnimalAwayProbe.anomaliesForTest() == 0);
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalAwayProbeTest 全數通過（" + mode + "）");
    }

    public static class FakeData extends AnimalData {
        int grows;
        boolean lastMeta;

        /** 僅供編譯；實例由 serialization 分配器產生。 */
        FakeData() {
            super(null, null);
        }

        @Override
        public void hourGrow(boolean meta) {
            grows++;
            lastMeta = meta;
        }
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
