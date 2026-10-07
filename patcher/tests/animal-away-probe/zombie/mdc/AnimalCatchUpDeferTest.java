package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.objects.IsoFeedingTrough;
import zombie.util.PZCalendar;

/**
 * W56 補算等畜牧區就緒（獨立 JVM；無參數＝出貨組態、{@code nodefer}＝{@code -Dmdc.animalCatchUpDefer=0}、
 * {@code noown}＝{@code -Dmdc.animalOwnClock=0}（延後依賴自身時鐘，一併停用）、{@code notrough}＝{@code -Dmdc.troughZoneRegister=0}）。
 * 畜牧區用 serialization 配置的真 {@code DesignationZoneAnimal}（getZoneF／getAllDZones 走原版），「chunk 已載入」與
 * 「check() 重建」以替身傳進 drain：重建時往槽清單放一個槽，委派時記下當下看不看得到槽。
 * 原版負對照（nodefer）：chunk 路徑補算時槽清單是空的，之後 zone 路徑被 W42 截成 0 小時——就是玩家回報的「當成沒吃沒喝」。
 */
public final class AnimalCatchUpDeferTest {

    private static int failed;
    private static final long HOUR = 3_600_000L;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean defer = mode.equals("on") || mode.equals("notrough") || mode.equals("deferms0");
        boolean trough = !mode.equals("notrough");
        long wantDeferNs = mode.equals("deferms0") ? 0L : 10_000_000_000L;
        expect("旗標與 argv 相符", AnimalAwayProbe.deferForTest() == defer
                && AnimalAwayProbe.troughRegisterForTest() == trough && AnimalAwayProbe.DEFER_NS == wantDeferNs);

        GameTime gt = (GameTime) raw(GameTime.class);
        gt.setCalender(PZCalendar.getInstance());
        gt.setYear(2026);
        gt.setMonth(5);
        gt.setDay(10);
        GameTime.setInstance(gt);
        long now = gt.getCalender().getTimeInMillis();
        if (mode.equals("deferms0")) {
            // 正式入口：IsoWorld.update 改道到 zoneUpdate；測試環境沒有世界，zoneLoaded 判未載入、check() 失敗只計數，
            // 逾時 0 時同一次 zoneUpdate 就補完。
            DesignationZoneAnimal pen = zone(100, 100, 10, 10);
            Fake cow = animal(102, 102, now - 7 * HOUR);
            AnimalAwayProbe.updateStatsAway(cow, 7);
            expect("deferms0：先排隊", cow.calls == 0 && cow.fromMeta && AnimalAwayProbe.pendingForTest() == 1);
            AnimalAwayProbe.zoneUpdate();
            expect("deferms0：zoneUpdate 照原版更新後排空（逾時路徑）、補 7h、解凍",
                    cow.calls == 1 && cow.lastHours == 7 && !cow.fromMeta && AnimalAwayProbe.pendingForTest() == 0
                            && AnimalAwayProbe.deferTimeoutsForTest() == 1 && pen.troughs.isEmpty());
            finish(mode);
            return;
        }

        // A、B 相鄰（同一組相連畜牧區），C、D 各自獨立。
        DesignationZoneAnimal a = zone(100, 100, 10, 10);
        DesignationZoneAnimal b = zone(110, 100, 10, 10);
        DesignationZoneAnimal c = zone(300, 300, 10, 10);
        DesignationZoneAnimal d = zone(500, 300, 10, 10);
        Set<DesignationZoneAnimal> loadedZones = Collections.newSetFromMap(new IdentityHashMap<>());
        List<DesignationZoneAnimal> refreshed = new ArrayList<>();
        Predicate<DesignationZoneAnimal> loaded = loadedZones::contains;
        Consumer<DesignationZoneAnimal> refresh = z -> {
            refreshed.add(z);
            if (z == d) {
                throw new IllegalStateException("check boom");
            }
            z.troughs.add(rawTrough());
        };

        // fromWorker 在畜牧區已知時傳入 zone 時數（這裡 30h，與動物自身離線時數相同）。
        Fake cow = animal(102, 102, now - 30 * HOUR);
        AnimalAwayProbe.updateStatsAway(cow, 30);
        if (!defer) {
            // 原版負對照：chunk 路徑當場補算，槽清單還是空的；zone 路徑（doMeta，check() 之後）被 W42 截成 0 小時。
            expect("不延後：chunk 路徑當場補 30h，當下看不到槽", cow.calls == 1 && cow.lastHours == 30 && !cow.sawTroughs);
            a.troughs.add(rawTrough());
            AnimalAwayProbe.updateStatsAwayZone(cow, 30);
            expect("不延後：check() 之後的 zone 路徑補 0h（W42 取 min(zone, 自身時鐘)）",
                    cow.calls == 2 && cow.lastHours == 0);
            expect("不延後：不排隊、排空不做事", AnimalAwayProbe.pendingForTest() == 0
                    && AnimalAwayProbe.deferredForTest() == 0 && !AnimalAwayProbe.awaitingCatchUp(cow));
            troughCase(trough);
            finish(mode);
            return;
        }

        expect("延後：chunk 路徑不當場補算、凍結（fromMeta）並排隊", cow.calls == 0 && cow.fromMeta
                && AnimalAwayProbe.pendingForTest() == 1 && AnimalAwayProbe.awaitingCatchUp(cow));
        AnimalAwayProbe.updateStatsAway(cow, 30);
        expect("同一隻再走 chunk 路徑：不重複排隊", AnimalAwayProbe.pendingForTest() == 1
                && AnimalAwayProbe.deferredForTest() == 1 && cow.calls == 0);
        AnimalAwayProbe.updateStatsAwayZone(cow, 40);
        expect("延後中的 zone 路徑略過", cow.calls == 0 && AnimalAwayProbe.deferZoneSkipsForTest() == 1);
        long t0 = System.nanoTime();
        AnimalAwayProbe.drain(t0, loaded, refresh);
        expect("相連畜牧區還沒全部載入、未逾時：不補、不重建", cow.calls == 0 && refreshed.isEmpty());
        loadedZones.add(a);
        AnimalAwayProbe.drain(t0, loaded, refresh);
        expect("A 載入但相連的 B 還沒：仍等待", cow.calls == 0 && refreshed.isEmpty());
        loadedZones.add(b);
        cow.timeSinceLastUpdate = now;   // 凍結中不該有人動時鐘；排空仍以延後當下的時鐘起算
        AnimalAwayProbe.drain(t0, loaded, refresh);
        expect("就緒：A、B 各重建一次後才補算，補 30h、看得到槽、從延後當下的時鐘起算、解凍",
                refreshed.size() == 2 && refreshed.contains(a) && refreshed.contains(b) && cow.calls == 1
                        && cow.lastHours == 30 && cow.sawTroughs && cow.startClock == now - 30 * HOUR && !cow.fromMeta
                        && AnimalAwayProbe.pendingForTest() == 0 && !AnimalAwayProbe.awaitingCatchUp(cow));

        // 同一批：兩隻共用畜牧區只重建一次，依延後順序補算。
        refreshed.clear();
        Fake first = animal(103, 103, now - 5 * HOUR);
        Fake second = animal(112, 104, now - 6 * HOUR);
        AnimalAwayProbe.updateStatsAway(first, 0);
        AnimalAwayProbe.updateStatsAway(second, 0);
        AnimalAwayProbe.drain(System.nanoTime(), loaded, refresh);
        expect("同一批：相連畜牧區各重建一次、依延後順序補算",
                refreshed.size() == 2 && first.calls == 1 && second.calls == 1 && first.order < second.order
                        && first.lastHours == 5 && second.lastHours == 6);

        // 逾時：C 一直沒載入，逾時後以現況補算。
        refreshed.clear();
        Fake far = animal(303, 303, now - 8 * HOUR);
        AnimalAwayProbe.updateStatsAway(far, 0);
        long t1 = System.nanoTime();
        AnimalAwayProbe.drain(t1, loaded, refresh);
        expect("未載入、未逾時：等待", far.calls == 0);
        AnimalAwayProbe.drain(t1 + AnimalAwayProbe.DEFER_NS, loaded, refresh);
        expect("逾時：照樣重建（check() 自己判斷兩角）並補 8h", far.calls == 1 && far.lastHours == 8
                && refreshed.equals(List.of(c)) && AnimalAwayProbe.deferTimeoutsForTest() == 1 && !far.fromMeta);

        // 延後中被卸載：原版 unloaded() 把時鐘寫成現在，還原成延後當下；之後不再補。
        Fake unloaded = animal(304, 304, now - 12 * HOUR);
        AnimalAwayProbe.updateStatsAway(unloaded, 0);
        AnimalAwayProbe.unloaded(unloaded);
        expect("延後中卸載：原版 unloaded() 照跑、時鐘還原成延後當下、移出佇列",
                unloaded.unloadedCalls == 1 && unloaded.timeSinceLastUpdate == now - 12 * HOUR
                        && AnimalAwayProbe.pendingForTest() == 0 && AnimalAwayProbe.deferUnloadedForTest() == 1);
        Fake plain = animal(700, 700, now - 2 * HOUR);
        AnimalAwayProbe.unloaded(plain);
        expect("不在佇列的卸載照原版（時鐘寫成現在）", plain.unloadedCalls == 1 && plain.timeSinceLastUpdate == now);

        // 延後中死亡：不補、解凍。
        Fake dead = animal(105, 105, now - 9 * HOUR);
        AnimalAwayProbe.updateStatsAway(dead, 0);
        dead.dead = true;
        // 延後中被直接呼叫（管理員指令）接手：取消延後。
        Fake direct = animal(106, 106, now - 4 * HOUR);
        AnimalAwayProbe.updateStatsAway(direct, 0);
        expect("直接呼叫接手：時數照傳入值", AnimalAwayProbe.entryHours(direct, 5) == 5
                && AnimalAwayProbe.deferDirectForTest() == 1);
        AnimalAwayProbe.drain(System.nanoTime(), loaded, refresh);
        expect("延後中死亡：不補算、解凍", dead.calls == 0 && !dead.fromMeta && AnimalAwayProbe.deferDeadForTest() == 1);
        expect("被直接呼叫接手的那筆排空時不再補", direct.calls == 0 && AnimalAwayProbe.pendingForTest() == 0);

        // 不延後的三種：畜牧區外、野生、沒有時鐘紀錄。
        Fake outside = animal(900, 900, now - 7 * HOUR);
        AnimalAwayProbe.updateStatsAway(outside, 0);
        Fake wild = animal(107, 107, now - 7 * HOUR);
        wild.wild = true;
        AnimalAwayProbe.updateStatsAway(wild, 0);
        Fake noClock = animal(108, 108, -1L);
        AnimalAwayProbe.updateStatsAway(noClock, 3);
        expect("畜牧區外、野生、無時鐘：當場補算（7h／0h／3h）", outside.calls == 1 && outside.lastHours == 7
                && wild.calls == 1 && wild.lastHours == 0 && noClock.calls == 1 && noClock.lastHours == 3
                && AnimalAwayProbe.pendingForTest() == 0);

        // 例外：重建失敗只計數、照樣補；委派失敗不外拋、解凍、不影響同批下一隻。
        loadedZones.add(d);
        Fake broken = animal(503, 303, now - 3 * HOUR);
        broken.toThrow = new IllegalStateException("boom");
        Fake after = animal(504, 304, now - 3 * HOUR);
        AnimalAwayProbe.updateStatsAway(broken, 0);
        AnimalAwayProbe.updateStatsAway(after, 0);
        boolean escaped = false;
        try {
            AnimalAwayProbe.drain(System.nanoTime(), loaded, refresh);
        } catch (RuntimeException e) {
            escaped = true;
        }
        expect("重建與委派例外都不外拋；壞的那隻解凍、同批下一隻照補",
                !escaped && broken.calls == 1 && !broken.fromMeta && after.calls == 1 && after.lastHours == 3
                        && AnimalAwayProbe.deferFailuresForTest() == 1 && AnimalAwayProbe.anomaliesForTest() == 1);

        expect("計數：延後 9、補完 6、逾時 1、卸載 1、死亡 1、接手 1、成功重建 7",
                AnimalAwayProbe.deferredForTest() == 9 && AnimalAwayProbe.deferDrainedForTest() == 6
                        && AnimalAwayProbe.deferUnloadedForTest() == 1 && AnimalAwayProbe.deferDeadForTest() == 1
                        && AnimalAwayProbe.deferDirectForTest() == 1 && AnimalAwayProbe.zoneRefreshesForTest() == 7);

        // 延後中要離開世界（抱起、放進拖車、被移除）：removeFromWorld 頭部當場補完。C 沒有載入，不等逾時。
        Fake picked = animal(303, 305, now - 4 * HOUR);
        AnimalAwayProbe.updateStatsAway(picked, 4);
        AnimalAwayProbe.leavingWorld(picked);
        AnimalAwayProbe.leavingWorld(plain);
        expect("延後中離開世界：當場補 4h、移出佇列、解凍；不在佇列的不做事",
                picked.calls == 1 && picked.lastHours == 4 && !picked.fromMeta && plain.calls == 0
                        && AnimalAwayProbe.pendingForTest() == 0 && AnimalAwayProbe.deferLeavingForTest() == 1);

        // 放下時原版 copyFrom 建立新物件、共用 AnimalData（parent 改指新物件）：補算跟著資料擁有者。
        Fake old = animal(104, 104, now - 6 * HOUR);
        AnimalAwayProbe.updateStatsAway(old, 6);
        Fake placed = animal(104, 104, -1L);
        old.ownerData = (AnimalData) raw(AnimalData.class);
        old.ownerData.parent = placed;
        AnimalAwayProbe.drain(System.nanoTime(), loaded, refresh);
        expect("換成新物件：補算 6h 落在新物件、從延後當下的時鐘起算，舊物件不補、解凍",
                placed.calls == 1 && placed.lastHours == 6 && placed.startClock == now - 6 * HOUR && old.calls == 0
                        && !old.fromMeta && AnimalAwayProbe.deferTransferredForTest() == 1);
        troughCase(trough);
        finish(mode);
    }

    /** 槽進世界：真 checkZone 以槽所在格找畜牧區。原 checkOverlayAfterAnimalEat 對替身會丟例外，只看登記結果。 */
    private static void troughCase(boolean register) throws Exception {
        DesignationZoneAnimal pen = zone(800, 800, 6, 6);
        IsoFeedingTrough t = rawTrough();
        IsoGridSquare sq = (IsoGridSquare) raw(IsoGridSquare.class);
        sq.x = 802;
        sq.y = 803;
        sq.z = 0;
        t.square = sq;
        try {
            AnimalAwayProbe.troughAddedToWorld(t);
        } catch (RuntimeException expected) {
            // 原版 checkOverlayAfterAnimalEat 需要完整的槽物件
        }
        expect("槽進世界：" + (register ? "登記回所在畜牧區" : "照原版不登記"), pen.troughs.contains(t) == register);
    }

    private static void finish(String mode) {
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalCatchUpDeferTest 全數通過（" + mode + "）");
    }

    private static DesignationZoneAnimal zone(int x, int y, int w, int h) throws Exception {
        DesignationZoneAnimal z = (DesignationZoneAnimal) raw(DesignationZoneAnimal.class);
        z.x = x;
        z.y = y;
        z.w = w;
        z.h = h;
        z.z = 0;
        Field troughs = DesignationZoneAnimal.class.getDeclaredField("troughs");
        troughs.setAccessible(true);
        troughs.set(z, new ArrayList<IsoFeedingTrough>());
        DesignationZoneAnimal.designationAnimalZoneList.add(z);
        return z;
    }

    private static IsoFeedingTrough rawTrough() {
        try {
            return (IsoFeedingTrough) raw(IsoFeedingTrough.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Fake animal(float x, float y, long clock) throws Exception {
        Fake f = (Fake) raw(Fake.class);
        f.fx = x;
        f.fy = y;
        f.timeSinceLastUpdate = clock;
        f.startClock = Long.MIN_VALUE;
        return f;
    }

    /** 替身：只覆寫改道與診斷會派送到的方法；updateStatsAway 記下補算當下看不看得到槽並推進時鐘。 */
    public static class Fake extends IsoAnimal {
        float fx, fy;
        int calls, lastHours, order, unloadedCalls;
        long startClock;
        boolean wild, dead, sawTroughs;
        RuntimeException toThrow;
        AnimalData ownerData;

        /** 僅供編譯；實例一律由 serialization 分配器產生，不跑 IsoAnimal 建構子。 */
        Fake() {
            super((zombie.iso.IsoCell) null);
        }

        @Override
        public void updateStatsAway(int hours) {
            calls++;
            order = ++sequence;
            lastHours = hours;
            startClock = timeSinceLastUpdate;
            if (toThrow != null) {
                throw toThrow;   // 在原版清 fromMeta 之前失敗：解凍要靠 finish
            }
            fromMeta = false;   // 原版第一步
            DesignationZoneAnimal z = DesignationZoneAnimal.getZoneF(fx, fy, 0);
            sawTroughs = z != null && !z.troughs.isEmpty();
            timeSinceLastUpdate += hours * HOUR;
        }

        @Override
        public AnimalData getData() {
            return ownerData;
        }

        @Override
        public void unloaded() {
            unloadedCalls++;
            timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis();
        }

        @Override
        public float getX() {
            return fx;
        }

        @Override
        public float getY() {
            return fy;
        }

        @Override
        public float getZ() {
            return 0;
        }

        @Override
        public DesignationZone getZone() {
            return null;
        }

        @Override
        public boolean isWild() {
            return wild;
        }

        @Override
        public boolean isDead() {
            return dead;
        }

        @Override
        public String getAnimalType() {
            return "cow";
        }

        @Override
        public int getAnimalID() {
            return 9;
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
        System.out.println((ok ? "acd pass  " : "acd FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalCatchUpDeferTest() {}
}
