package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.util.PZCalendar;

/**
 * W32／W42／W49 AnimalAwayProbe 補算時數驗證（獨立 JVM；無參數＝出貨組態、{@code off}＝觀測 kill switch、
 * {@code nocap}＝W42 kill switch（W49 自身時鐘一併停用）、{@code noown}＝只關 W49 自身時鐘、
 * {@code nocarcass}＝只關屠體守衛）。
 * 鎖：委派恰一次；自身離線時數於委派「前」讀取；W42 zone 路徑取較小值、無紀錄沿用 vanilla、時鐘在未來補 0；
 * W49 chunk 路徑（42.22 原版不補，noown／nocap 不委派）以自身時數補算、野生與無時鐘不補、超過上限時先把時鐘移到
 * 「現在 − 上限」、補算例外不外拋只計數；掛鉤屠體不補算且清 fromMeta；活著與雞舍內每小時刷新時鐘；zone 路徑委派例外穿透；
 * off 完全不計數。
 */
public final class AnimalAwayProbeTest {

    private static int failed;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean off = mode.equals("off");
        boolean cap = !mode.equals("nocap");
        boolean own = cap && !mode.equals("noown");
        boolean carcassGuard = !mode.equals("nocarcass");
        expect("旗標與 argv 相符", AnimalAwayProbe.enabledForTest() == !off && AnimalAwayProbe.capForTest() == cap
                && AnimalAwayProbe.ownForTest() == own && AnimalAwayProbe.carcassGuardForTest() == carcassGuard
                && AnimalAwayProbe.LIMIT == 168);

        // 真 GameTime：getCalender 由年月日＋timeOfDay 重算（未初始化 timeOfDay＝0 點）。
        GameTime gt = (GameTime) raw(GameTime.class);
        gt.setCalender(PZCalendar.getInstance());
        gt.setYear(2026);
        gt.setMonth(5);
        gt.setDay(10);
        GameTime.setInstance(gt);
        PZCalendar cal = gt.getCalender();
        long now = cal.getTimeInMillis();

        // chunk 路徑（fromWorker）：42.22 原版不補算；W49 以動物自身離線時數補算。
        Fake a = fake();
        a.timeSinceLastUpdate = now - 2 * HOUR;
        expect("動物自身離線時數＝2", AnimalAwayProbe.animalHoursAway(a) == 2L);
        AnimalAwayProbe.chunkCatchUp(a);
        expect("chunk：自身時鐘補 2h（noown／nocap 照 42.22 原版不補）", own ? a.calls == 1 && a.lastHours == 2 : a.calls == 0);

        Fake b = fake();
        b.timeSinceLastUpdate = now - 3 * HOUR;
        AnimalAwayProbe.chunkCatchUp(b);
        expect("chunk：補 3h", own ? b.calls == 1 && b.lastHours == 3 : b.calls == 0);

        Fake first = fake();
        first.timeSinceLastUpdate = now - 30 * HOUR;
        AnimalAwayProbe.chunkCatchUp(first);
        expect("chunk：離線 30h 補 30h", own ? first.calls == 1 && first.lastHours == 30 : first.calls == 0);

        // W49：長離線上限，動物離線 200h：先把時鐘移到「現在 − 168h」，補 168h，跑完時鐘恰為現在。
        Fake away = fake();
        away.timeSinceLastUpdate = now - 200 * HOUR;
        AnimalAwayProbe.chunkCatchUp(away);
        expect("長離線：補 168h 並從「現在 − 168h」起算（noown／nocap 不補、時鐘不動）",
                own ? away.lastHours == 168 && away.startClock == now - 168 * HOUR && away.timeSinceLastUpdate == now
                        : away.calls == 0 && away.timeSinceLastUpdate == now - 200 * HOUR);

        // 野生、無時鐘：chunk 路徑不補（留給 doMeta，與 42.22 原版相同）。
        Fake wild = fake();
        wild.wild = true;
        wild.timeSinceLastUpdate = now - 30 * HOUR;
        AnimalAwayProbe.chunkCatchUp(wild);
        Fake c = fake();
        c.timeSinceLastUpdate = -1L;
        expect("無時間戳回 NO_RECORD", AnimalAwayProbe.animalHoursAway(c) == AnimalAwayProbe.NO_RECORD);
        AnimalAwayProbe.chunkCatchUp(c);
        expect("野生、無時鐘：chunk 路徑不補", wild.calls == 0 && c.calls == 0);

        // zone 路徑（doMeta）維持 W42 的較小值；超過上限同樣截斷。
        Fake z = fake();
        z.timeSinceLastUpdate = now - HOUR;
        AnimalAwayProbe.updateStatsAwayZone(z, 1);
        expect("zone 路徑委派", z.calls == 1 && z.lastHours == 1);
        Fake zoneStale = fake();
        zoneStale.timeSinceLastUpdate = now - 300 * HOUR;
        AnimalAwayProbe.updateStatsAwayZone(zoneStale, 250);
        expect("zone 路徑 min(250, 300)＝250，上限截成 168（noown 250、nocap 250）",
                zoneStale.lastHours == (own ? 168 : 250));
        expect("zone 路徑截斷時時鐘同樣移到「現在 − 168h」", !own || zoneStale.startClock == now - 168 * HOUR);
        // 陳舊 zone：zone 算出 200h，但動物 2h 前才卸載。
        Fake stale = fake();
        stale.timeSinceLastUpdate = now - 2 * HOUR;
        AnimalAwayProbe.updateStatsAwayZone(stale, 200);
        expect("陳舊 zone：補 2h（nocap 補 200h）", stale.calls == 1 && stale.lastHours == (cap ? 2 : 200));

        // 無時間戳（-1）：zone 路徑沿用 vanilla 時數（W49 開啟時仍受上限約束）。
        AnimalAwayProbe.updateStatsAwayZone(c, 500);
        expect("無時間戳沿用 vanilla（W49 截成 168h，其餘 500h）、時鐘不動",
                c.lastHours == (own ? 168 : 500) && c.startClock == -1L);

        // 時鐘在未來（先前多補 38h 且未卸載）：W42 補 0。
        Fake f = fake();
        f.timeSinceLastUpdate = now + 38 * HOUR;
        AnimalAwayProbe.updateStatsAwayZone(f, 48);
        expect("時鐘在未來：補 0h（nocap 補 48h）", f.calls == 1 && f.lastHours == (cap ? 0 : 48));
        Fake futureChunk = fake();
        futureChunk.timeSinceLastUpdate = now + 5 * HOUR;
        AnimalAwayProbe.chunkCatchUp(futureChunk);
        expect("時鐘在未來（chunk 路徑）：補 0h", own ? futureChunk.calls == 1 && futureChunk.lastHours == 0 : futureChunk.calls == 0);

        // B3：掛鉤屠體不委派、清 fromMeta（nocarcass 照原版委派）。
        Fake carcass = fake();
        carcass.setOnHook(true);
        carcass.fromMeta = true;
        carcass.timeSinceLastUpdate = now - 40 * HOUR;
        AnimalAwayProbe.chunkCatchUp(carcass);
        expect("屠體：不補算且清 fromMeta（nocarcass 委派 40h；noown／nocap 照 42.22 原版不碰）",
                !own ? carcass.calls == 0 && carcass.fromMeta && AnimalAwayProbe.carcassSkipsForTest() == 0
                        : carcassGuard
                        ? carcass.calls == 0 && !carcass.fromMeta && AnimalAwayProbe.carcassSkipsForTest() == 1
                        : carcass.calls == 1 && carcass.lastHours == 40 && AnimalAwayProbe.carcassSkipsForTest() == 0);

        // 委派例外：zone 路徑原樣穿透；chunk 路徑（原版這裡沒有補算）不外拋、計 chunkFailures。
        Fake d = fake();
        d.toThrow = new IllegalStateException("boom");
        d.timeSinceLastUpdate = now - 5 * HOUR;
        boolean thrown = false;
        try {
            AnimalAwayProbe.updateStatsAwayZone(d, 5);
        } catch (IllegalStateException e) {
            thrown = e == d.toThrow;
        }
        expect("zone 路徑委派例外原樣穿透", thrown);
        Fake e = fake();
        e.toThrow = new IllegalStateException("boom");
        e.timeSinceLastUpdate = now - 5 * HOUR;
        AnimalAwayProbe.chunkCatchUp(e);
        expect("chunk 路徑委派例外不外拋、計 chunkFailures＝" + (own ? 1 : 0),
                AnimalAwayProbe.chunkFailuresForTest() == (own ? 1 : 0) && e.calls == (own ? 1 : 0));

        // fromWorker 兩個掛點：getAdvancedAnimator 照原版取 animator 並記下動物；OnAnimDataChanged 照原版呼叫，
        // 原版例外原樣外拋、不補算（補算在原版呼叫之後，順序由 SmokeCheck W32 釘住）。
        Fake glue = fake();
        glue.timeSinceLastUpdate = now - 4 * HOUR;
        glue.animator = (AdvancedAnimator) raw(AdvancedAnimator.class);
        AdvancedAnimator got = AnimalAwayProbe.fromWorkerAnimator(glue);
        boolean vanillaThrew = false;
        try {
            AnimalAwayProbe.fromWorkerAnimDataChanged(got, false);
        } catch (NullPointerException npe) {
            vanillaThrew = true;   // 未初始化的 animator 沒有 character：原版 OnAnimDataChanged 自己丟
        }
        expect("fromWorker 掛點：回傳原 animator、原版例外外拋、不補算",
                got == glue.animator && vanillaThrew && glue.calls == 0);

        // 委派票證只給正在補算的那隻、而且只用一次：委派中（例如 MOD 回呼）對同一隻再呼叫或對別隻直接呼叫，
        // 都走直接呼叫的上限（W49 開啟時 200h 截成 168h）。
        Fake host = fake();
        host.timeSinceLastUpdate = now - 3 * HOUR;
        host.other = fake();
        AnimalAwayProbe.updateStatsAwayZone(host, 3);
        int capped = own ? 168 : 200;
        expect("委派中：本隻第一次入口憑票放行（50）、第二次與別隻都當直接呼叫（" + capped + "）",
                host.nestedSelf == 50 && host.nestedSelfAgain == capped && host.nestedOther == capped
                        && AnimalAwayProbe.directCallsForTest() == 2);

        // 活著的每小時：刷新動物自身時鐘並照常 hourGrow。
        Fake live = fake();
        live.timeSinceLastUpdate = now - 240 * HOUR;
        FakeData data = (FakeData) raw(FakeData.class);
        data.parent = live;
        AnimalAwayProbe.liveHourGrow(data, false);
        expect("liveHourGrow 委派 hourGrow(false) 恰一次", data.grows == 1 && !data.lastMeta);
        expect("活著的每小時刷新時鐘（nocap 不動）",
                live.timeSinceLastUpdate == (cap ? now : now - 240 * HOUR));

        // 雞舍內每小時：委派 setHoursSurvived 並刷新時鐘。
        Fake inHutch = fake();
        inHutch.timeSinceLastUpdate = now - 90 * HOUR;
        AnimalAwayProbe.hutchHoursSurvived(inHutch, 123.0);
        expect("雞舍內 setHoursSurvived 照常寫入", inHutch.getHoursSurvived() == 123.0);
        expect("雞舍內每小時刷新時鐘（nocap 不動）",
                inHutch.timeSinceLastUpdate == (cap ? now : now - 90 * HOUR));

        if (off) {
            expect("off 不計數", AnimalAwayProbe.callsForTest() == 0);
        } else {
            // zone 6 筆（z、zoneStale、stale、c、f、host；例外那筆不計）＋chunk 自身時鐘 5 筆（a、b、first、away、futureChunk）
            int delegated = 6 + (own ? 5 + (carcassGuard ? 0 : 1) : 0);
            expect("calls=" + delegated + "（例外那筆不計、屠體依守衛）", AnimalAwayProbe.callsForTest() == delegated);
            expect("anomalies=0", AnimalAwayProbe.anomaliesForTest() == 0);
        }
        expect("上限截斷次數＝" + (own ? 3 : 0), AnimalAwayProbe.limitedForTest() == (own ? 3 : 0));
        // chunk 路徑 2＋3＋30＋168＋例外那筆 5（時數在委派前就算好）＝208（42.22 原版這條路徑 0），nocarcass 另加屠體 40。
        int gain = own ? 208 + (carcassGuard ? 0 : 40) : 0;
        expect("自身時鐘多補 ownGainHours＝" + gain, AnimalAwayProbe.ownGainHoursForTest() == gain);
        expect("委派例外計入 delegateFailures＝" + (own ? 2 : 1), AnimalAwayProbe.delegateFailuresForTest() == (own ? 2 : 1));

        // 連續補算累計：三筆首尾相接、各 60ms 的補算是同一段 180ms；隔 220ms 之後重新起算。
        long base = System.nanoTime() + 10_000_000_000L;
        long ms = 1_000_000L;
        AnimalAwayProbe.accumulateRun(base, base + 60 * ms);
        AnimalAwayProbe.accumulateRun(base + 60 * ms, base + 120 * ms);
        AnimalAwayProbe.accumulateRun(base + 120 * ms, base + 180 * ms);
        AnimalAwayProbe.accumulateRun(base + 400 * ms, base + 460 * ms);
        expect("相鄰慢補算累計 180ms（不因單筆耗時超過間隔門檻而拆段），間隔後重新起算",
                AnimalAwayProbe.maxRunNsForTest() == 180 * ms);
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
        long startClock = Long.MIN_VALUE;
        boolean wild;
        RuntimeException toThrow;
        /** 設定時，委派中模擬 MOD 回呼：先後以本隻、本隻、別隻走 updateStatsAway 頭部的時數過濾。 */
        IsoAnimal other;
        int nestedSelf;
        int nestedSelfAgain;
        int nestedOther;
        AdvancedAnimator animator;

        /** 僅供編譯；實例一律由 serialization 分配器產生，不跑 IsoAnimal 建構子。 */
        Fake() {
            super((zombie.iso.IsoCell) null);
        }

        @Override
        public void updateStatsAway(int hours) {
            calls++;
            lastHours = hours;
            startClock = timeSinceLastUpdate;
            if (toThrow != null) {
                throw toThrow;
            }
            if (other != null) {
                nestedSelf = AnimalAwayProbe.entryHours(this, 50);
                nestedSelfAgain = AnimalAwayProbe.entryHours(this, 200);
                nestedOther = AnimalAwayProbe.entryHours(other, 200);
            }
            timeSinceLastUpdate += hours * HOUR;
        }

        @Override
        public boolean isWild() {
            return wild;
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

        @Override
        public AdvancedAnimator getAdvancedAnimator() {
            return animator;
        }
    }

    private static Fake fake() throws Exception {
        Fake f = (Fake) raw(Fake.class);
        f.startClock = Long.MIN_VALUE;
        return f;
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
