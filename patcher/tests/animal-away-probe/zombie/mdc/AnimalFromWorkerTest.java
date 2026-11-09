package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zombie.GameTime;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionGroup;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.AnimalManagerMain;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.VirtualAnimal;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimationSet;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.gameStates.IngameState;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.popman.animal.AnimalInstanceManager;
import zombie.util.PZCalendar;

/**
 * W32／W49／W56 chunk 路徑整合測試：以反射呼叫 classpath 上的真 {@code AnimalManagerMain.fromWorker}（獨立 JVM）。
 * {@code on}／{@code nodefer}／{@code noown} 跑 dist/java 的手術版（{@code -Dmdc.animalCatchUpDefer=0}、
 * {@code -Dmdc.animalOwnClock=0}）；{@code vanilla} 的 classpath 不放 dist/java，跑測試 jar 的 42.22.2 原版
 * （這個模式不碰任何 helper）。
 *
 * <p>世界替身：Unsafe 配置的真 {@code IsoWorld}／{@code IsoCell}（{@code objectList} 換成空集合）、伺服器模式
 * {@code IsoCell.getGridSquare} 轉給 {@code ServerMap} 替身、真 {@code IsoGridSquare}／{@code IsoChunk}、
 * 真 {@code DesignationZoneAnimal}（同時登記在 {@code DesignationZone.allZones} 與畜牧區清單）、
 * 真 {@code ActionContext}／{@code ActionGroup}／{@code AdvancedAnimator}（animset 預先放進兩張快取表，不讀檔）、
 * 真 {@code AnimalInstanceManager}。動物替身只覆寫位置設定、{@code addToWorld} 與 {@code updateStatsAway}（記次數、
 * 時數、當下是否已在 objectList；模擬原版清 fromMeta 與推進時鐘）。
 *
 * <p>一批四群：畜牧區外（自身時鐘、野生、無時鐘、補算時丟例外、例外之後一隻）、畜牧區 A（已載入）、畜牧區 B（一直
 * 沒載入）、格子未載入的一群。鎖：每隻都完成原版登記（addToWorld、setGroup、OnAnimDataChanged 各一次、objectList、
 * AnimalInstanceManager），補算恰一次且發生在 OnAnimDataChanged 之後、objectList 登記之前；畜牧區內的延後到排空
 * （A 就緒、B 逾時）才補，期間 doMeta（zone 路徑）略過；補算例外不外拋、後面的動物照常；原版（vanilla／noown）不補。
 */
public final class AnimalFromWorkerTest {

    private static final long HOUR = 3_600_000L;
    private static final String ANIMSET = "mdc-fromworker-test";
    private static int failed;
    private static int sequence;
    private static IsoCell cell;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean vanilla = mode.equals("vanilla");
        boolean own = mode.equals("on") || mode.equals("nodefer");
        boolean defer = mode.equals("on");
        boolean patched = !vanilla;
        // 防 classpath 寫錯：手術版的 AnimalManagerMain 來自 dist\java 目錄，原版來自測試 jar。
        boolean fromJar = AnimalManagerMain.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                .endsWith(".jar");
        expect("AnimalManagerMain 來自 " + (vanilla ? "測試 jar（原版）" : "dist\\java（手術版）"), fromJar == vanilla);
        if (patched) {
            expect("旗標與 argv 相符", Probe.own() == own && Probe.defer() == defer);
        }

        zombie.core.random.RandStandard.INSTANCE.init();
        GameTime gt = (GameTime) alloc(GameTime.class);
        gt.setCalender(PZCalendar.getInstance());
        gt.setYear(2026);
        gt.setMonth(5);
        gt.setDay(10);
        GameTime.setInstance(gt);
        long now = gt.getCalender().getTimeInMillis();

        GameServer.server = true;
        IngameState.loading = false;
        ServerMap.instance = (ServerMap) alloc(TestServerMap.class);
        IsoWorld world = (IsoWorld) alloc(IsoWorld.class);
        cell = (IsoCell) alloc(IsoCell.class);
        set(IsoCell.class, cell, "objectList", new HashSet<IsoMovingObject>());
        world.currentCell = cell;
        IsoWorld.instance = world;
        ActionGroup group = new ActionGroup();
        cache(ActionGroup.class, "s_actionGroupMap").put(ANIMSET, group);
        cache(AnimationSet.class, "setMap").put(ANIMSET, new AnimationSet());

        DesignationZoneAnimal penA = zone(100, 100, 10, 10);
        DesignationZoneAnimal penB = zone(500, 500, 10, 10);

        // 畜牧區外：自身時鐘、野生、無時鐘、補算丟例外、例外之後一隻。
        VirtualAnimal outside = herd(902.5f, 902.5f, true);
        Fake out = animal(outside, now - 5 * HOUR);
        Fake wild = animal(outside, now - 5 * HOUR);
        wild.wild = true;
        Fake noClock = animal(outside, -1L);
        Fake broken = animal(outside, now - 4 * HOUR);
        broken.toThrow = new IllegalStateException("boom");
        Fake after = animal(outside, now - 6 * HOUR);
        // 畜牧區 A（之後就緒）、B（一直沒載入，逾時才補）。
        VirtualAnimal herdA = herd(102.5f, 102.5f, true);
        Fake pen1 = animal(herdA, now - 3 * HOUR);
        Fake pen2 = animal(herdA, now - 7 * HOUR);
        VirtualAnimal herdB = herd(503.5f, 503.5f, true);
        Fake far = animal(herdB, now - 8 * HOUR);
        // 格子未載入：原版整群略過。
        VirtualAnimal unloaded = herd(1500.5f, 1500.5f, false);
        Fake ghost = animal(unloaded, now - 9 * HOUR);

        List<Fake> registered = List.of(out, wild, noClock, broken, after, pen1, pen2, far);
        ArrayList<VirtualAnimal> batch = new ArrayList<>(List.of(outside, herdA, unloaded, herdB));
        Method fromWorker = AnimalManagerMain.class.getDeclaredMethod("fromWorker", ArrayList.class);
        fromWorker.setAccessible(true);
        Method getInstance = AnimalManagerMain.class.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Throwable escaped = null;
        try {
            fromWorker.invoke(getInstance.invoke(null), batch);
        } catch (java.lang.reflect.InvocationTargetException e) {
            escaped = e.getCause();
            escaped.printStackTrace(System.out);
        }
        expect("fromWorker 整批跑完、沒有例外外拋", escaped == null);

        AnimalInstanceManager aim = AnimalInstanceManager.getInstance();
        Set<IsoMovingObject> objects = cell.getObjectList();
        boolean allRegistered = true;
        for (Fake f : registered) {
            allRegistered &= f.addToWorldCalls == 1 && f.animSetNameCalls == 1 && f.ctx.getGroup() == group
                    && objects.contains(f) && aim.get(f.getOnlineID()) == f && f.current == f.square && f.last == f.square;
        }
        expect("八隻都完成原版登記：addToWorld／setGroup／OnAnimDataChanged 各一次、進 objectList 與 AnimalInstanceManager",
                allRegistered && objects.size() == registered.size());
        expect("格子未載入的一群照原版略過", ghost.addToWorldCalls == 0 && !objects.contains(ghost) && ghost.calls == 0);
        expect("畜牧區外不設 fromMeta、畜牧區內照原版設 fromMeta（手術版延後也設）",
                !out.fromMetaAtAdd && !after.fromMetaAtAdd && pen1.fromMetaAtAdd && pen2.fromMetaAtAdd && far.fromMetaAtAdd);

        if (!own) {
            int calls = 0;
            for (Fake f : registered) {
                calls += f.calls;
            }
            expect(mode + "：42.22.2 原版 chunk 路徑不補算、畜牧區內留著 fromMeta 等 doMeta",
                    calls == 0 && pen1.fromMeta && pen2.fromMeta && far.fromMeta && !out.fromMeta);
            if (patched) {
                expect(mode + "：helper 不計數、不排隊", Probe.calls() == 0 && Probe.pending() == 0
                        && Probe.chunkFailures() == 0);
            }
            finish(mode);
            return;
        }

        expect("畜牧區外：自身時鐘補 5h／6h 各恰一次，在 OnAnimDataChanged 之後、objectList 登記之前",
                once(out, 5, now) && once(after, 6, now));
        expect("野生、無時鐘：不補", wild.calls == 0 && noClock.calls == 0);
        expect("補算例外：不外拋、計 chunkFailures、那隻仍登記，後面的照常補",
                broken.calls == 1 && Probe.chunkFailures() == 1 && objects.contains(broken)
                        && after.order > broken.order);

        if (!defer) {
            expect("nodefer：畜牧區內當場補 3h／7h／8h 各恰一次、解凍", once(pen1, 3, now) && once(pen2, 7, now)
                    && once(far, 8, now) && !pen1.fromMeta && Probe.pending() == 0);
            expect("nodefer：calls=5（例外那筆不計）", Probe.calls() == 5);
            finish(mode);
            return;
        }

        expect("延後：畜牧區內三隻不當場補、凍結並排隊", pen1.calls == 0 && pen2.calls == 0 && far.calls == 0
                && pen1.fromMeta && pen2.fromMeta && far.fromMeta && Probe.pending() == 3);
        Probe.zone(pen1, 30);
        expect("延後中 doMeta（zone 路徑）略過", pen1.calls == 0 && Probe.zoneSkips() == 1);
        List<DesignationZoneAnimal> refreshed = new ArrayList<>();
        long t0 = System.nanoTime();
        Probe.drain(t0, z -> z == penA, refreshed::add);
        expect("A 就緒：A 重建一次後補 3h／7h 各恰一次、從延後當下的時鐘起算、解凍；B 未逾時仍等待",
                refreshed.equals(List.of(penA)) && pen1.calls == 1 && pen1.lastHours == 3
                        && pen1.startClock == now - 3 * HOUR && pen2.calls == 1 && pen2.lastHours == 7
                        && !pen1.fromMeta && !pen2.fromMeta && far.calls == 0 && far.fromMeta && Probe.pending() == 1);
        Probe.drain(t0 + Probe.deferNs(), z -> z == penA, refreshed::add);
        expect("B 逾時：重建 B 後補 8h 恰一次、解凍", refreshed.equals(List.of(penA, penB)) && far.calls == 1
                && far.lastHours == 8 && !far.fromMeta && Probe.pending() == 0 && Probe.deferTimeouts() == 1);
        Probe.drain(t0 + 2 * Probe.deferNs(), z -> true, refreshed::add);
        expect("再排空不重複補", pen1.calls == 1 && pen2.calls == 1 && far.calls == 1 && refreshed.size() == 2);
        expect("計數：calls=5、延後 3、補完 3", Probe.calls() == 5 && Probe.deferred() == 3 && Probe.drained() == 3);
        finish(mode);
    }

    /** 恰補一次、時數對、在 OnAnimDataChanged 之後且尚未進 objectList（42.21 的位置：setGroup 後、登記前）。 */
    private static boolean once(Fake f, int hours, long now) {
        return f.calls == 1 && f.lastHours == hours && f.startClock == now - hours * HOUR && f.animSetNameAtCatchUp == 1
                && !f.inObjectListAtCatchUp;
    }

    /** helper 存取集中在這裡：vanilla 模式 classpath 沒有 helper，不能解析到這些呼叫。 */
    private static final class Probe {
        static boolean own() { return AnimalAwayProbe.ownForTest(); }
        static boolean defer() { return AnimalAwayProbe.deferForTest(); }
        static long calls() { return AnimalAwayProbe.callsForTest(); }
        static int pending() { return AnimalAwayProbe.pendingForTest(); }
        static long chunkFailures() { return AnimalAwayProbe.chunkFailuresForTest(); }
        static long zoneSkips() { return AnimalAwayProbe.deferZoneSkipsForTest(); }
        static long deferTimeouts() { return AnimalAwayProbe.deferTimeoutsForTest(); }
        static long deferred() { return AnimalAwayProbe.deferredForTest(); }
        static long drained() { return AnimalAwayProbe.deferDrainedForTest(); }
        static long deferNs() { return AnimalAwayProbe.DEFER_NS; }
        static void zone(IsoAnimal a, int hours) { AnimalAwayProbe.updateStatsAwayZone(a, hours); }
        static void drain(long nowNs, java.util.function.Predicate<DesignationZoneAnimal> loaded,
                java.util.function.Consumer<DesignationZoneAnimal> refresh) {
            AnimalAwayProbe.drain(nowNs, loaded, refresh);
        }
    }

    private static void finish(String mode) {
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalFromWorkerTest 全數通過（" + mode + "）");
    }

    private static DesignationZoneAnimal zone(int x, int y, int w, int h) throws Exception {
        DesignationZoneAnimal z = (DesignationZoneAnimal) alloc(DesignationZoneAnimal.class);
        z.x = x;
        z.y = y;
        z.w = w;
        z.h = h;
        z.z = 0;
        DesignationZone.allZones.add(z);
        DesignationZoneAnimal.designationAnimalZoneList.add(z);
        return z;
    }

    /** 一群：位置落在一個 chunk 中央；{@code loaded}＝格子在 ServerMap 替身裡。 */
    @SuppressWarnings("unchecked")
    private static VirtualAnimal herd(float x, float y, boolean loaded) throws Exception {
        VirtualAnimal v = new VirtualAnimal();
        v.setX(x);
        v.setY(y);
        v.setZ(0f);
        v.id = 0.0;
        if (loaded) {
            IsoChunk chunk = (IsoChunk) alloc(IsoChunk.class);
            chunk.wx = (int) x / 8;
            chunk.wy = (int) y / 8;
            IsoGridSquare sq = (IsoGridSquare) alloc(IsoGridSquare.class);
            sq.x = (int) x;
            sq.y = (int) y;
            sq.z = 0;
            sq.chunk = chunk;
            TestServerMap.SQUARES.put(key((int) x, (int) y), sq);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Fake animal(VirtualAnimal herd, long clock) throws Exception {
        Fake f = (Fake) alloc(Fake.class);
        f.timeSinceLastUpdate = clock;
        f.startClock = Long.MIN_VALUE;
        f.square = TestServerMap.SQUARES.get(key((int) herd.getX(), (int) herd.getY()));
        AnimalDefinitions def = (AnimalDefinitions) alloc(AnimalDefinitions.class);
        def.animset = ANIMSET;
        f.adef = def;
        f.ctx = new ActionContext(f);
        f.animator = new AdvancedAnimator();
        set(AdvancedAnimator.class, f.animator, "character", f);
        Field animals = VirtualAnimal.class.getDeclaredField("animals");
        animals.setAccessible(true);
        ((ArrayList<IsoAnimal>) animals.get(herd)).add(f);
        return f;
    }

    /** 動物替身：位置設定只記值；addToWorld 記下當下的 fromMeta；updateStatsAway 照原版第一步清 fromMeta 並推進時鐘。 */
    public static class Fake extends IsoAnimal {
        float fx, fy, fz;
        int calls, lastHours, order, addToWorldCalls, animSetNameCalls, animSetNameAtCatchUp;
        long startClock;
        boolean wild, fromMetaAtAdd, inObjectListAtCatchUp;
        RuntimeException toThrow;
        IsoGridSquare square, current, last;
        ActionContext ctx;
        AdvancedAnimator animator;

        /** 僅供編譯；實例一律由 Unsafe 配置，不跑 IsoAnimal 建構子。 */
        Fake() {
            super((IsoCell) null);
        }

        @Override
        public void updateStatsAway(int hours) {
            calls++;
            order = ++sequence;
            lastHours = hours;
            startClock = timeSinceLastUpdate;
            animSetNameAtCatchUp = animSetNameCalls;
            inObjectListAtCatchUp = cell.getObjectList().contains(this);
            if (toThrow != null) {
                throw toThrow;
            }
            fromMeta = false;
            timeSinceLastUpdate += hours * HOUR;
        }

        @Override
        public void addToWorld() {
            addToWorldCalls++;
            fromMetaAtAdd = fromMeta;
        }

        @Override
        public ActionContext getActionContext() {
            return ctx;
        }

        @Override
        public AdvancedAnimator getAdvancedAnimator() {
            return animator;
        }

        /** 原版 OnAnimDataChanged 每次恰讀一次。 */
        @Override
        public String GetAnimSetName() {
            animSetNameCalls++;
            return ANIMSET;
        }

        @Override
        public AnimationPlayer getAnimationPlayer() {
            return null;
        }

        @Override
        public void setForceX(float x) {
            fx = x;
        }

        @Override
        public void setForceY(float y) {
            fy = y;
        }

        @Override
        public float setZ(float z) {
            fz = z;
            return z;
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
            return fz;
        }

        @Override
        public void setDir(IsoDirections dir) {
        }

        @Override
        public void setForwardDirection(float x, float y) {
        }

        @Override
        public void setCurrent(IsoGridSquare sq) {
            current = sq;
        }

        @Override
        public void setLast(IsoGridSquare sq) {
            last = sq;
        }

        @Override
        public void setMovingSquareNow() {
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
        public boolean isOnHook() {
            return false;
        }

        @Override
        public String getAnimalType() {
            return "cow";
        }

        @Override
        public int getAnimalID() {
            return 11;
        }
    }

    /** ServerMap 替身：伺服器上 IsoCell.getGridSquare 轉給 ServerMap.instance。 */
    public static class TestServerMap extends ServerMap {
        static final Map<Long, IsoGridSquare> SQUARES = new HashMap<>();

        @Override
        public IsoGridSquare getGridSquare(int x, int y, int z) {
            return SQUARES.get(key(x, y));
        }
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cache(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, Object>) f.get(null);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Object alloc(Class<?> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "afw pass  " : "afw FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalFromWorkerTest() {}
}
