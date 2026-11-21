package zombie.entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import zombie.entity.util.Array;
import zombie.entity.util.SingleThreadPool;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * W105／W106 MdcEntityEngineGuard 行為驗證（argv[0]＝on｜off｜defer-off｜guard-off，須與
 * {@code -Dmdc.entityEngineDefer}／{@code -Dmdc.entityOpGuard} 相符）。跑 dist 內手術後的真
 * {@code GameEntityManager.UnregisterEntity}、真 {@code Engine}、真 {@code EngineEntityManager.processPendingOperations}。
 *
 * <p>事故重現（各組態都跑）：主執行緒 free 一個操作物件做到一半（已放回池、還沒 reset），背景執行緒在 engine 處理中
 * Unregister 一個從沒進過 engine 的 entity，之後主執行緒 reset——原版（off）佇列留下 entity 為 null 的 Remove，
 * 之後每次 processPendingOperations 都拋 {@code key cannot be null}、那筆永遠留著；W106 略過那筆、佇列清空；W105 背景碰不到池與佇列。
 */
public final class MdcEntityEngineGuardTest {

    private static int failed;
    private static Engine engine;
    private static EngineEntityManager mgr;
    private static Array<Object> pendingOps;
    private static SingleThreadPool<Object> pool;
    private static Array<Object> freeObjects;
    private static Field processing;
    private static Class<?> opClass;
    private static Class<?> typeClass;

    /** 記錄 engine 加入／移除順序；可指定在某個 entity 上拋例外。 */
    static final class Listener implements Engine.EntityListener {
        final List<String> events = new ArrayList<>();
        GameEntity throwOnRemove;
        GameEntity errorOnAdd;

        @Override
        public void onEntityAddedToEngine(GameEntity e) {
            events.add("+" + name(e));
            if (e == errorOnAdd) {
                throw new AssertionError("test error");
            }
        }

        @Override
        public void onEntityRemovedFromEngine(GameEntity e) {
            events.add("-" + name(e));
            if (e == throwOnRemove) {
                throw new IllegalStateException("test listener");
            }
        }
    }

    private static final java.util.Map<GameEntity, String> NAMES = new java.util.IdentityHashMap<>();
    private static final Listener listener = new Listener();

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "on";
        boolean defer = mode.equals("on") || mode.equals("guard-off");
        boolean guard = mode.equals("on") || mode.equals("defer-off");
        expect("開關與 argv 相符（" + mode + "）", MdcEntityEngineGuard.DEFER == defer && MdcEntityEngineGuard.GUARD == guard);
        GameServer.server = true;
        Thread main = Thread.currentThread();
        GameServer.mainThread = main;
        engine = new Engine();
        set(GameEntityManager.class, null, "engine", engine);
        engine.setEntityListener(listener);
        mgr = (EngineEntityManager) get(Engine.class, engine, "entityManager");
        pendingOps = (Array<Object>) get(EngineEntityManager.class, mgr, "pendingOperations");
        pool = (SingleThreadPool<Object>) get(EngineEntityManager.class, mgr, "entityOperationPool");
        freeObjects = (Array<Object>) get(SingleThreadPool.class, pool, "freeObjects");
        processing = Engine.class.getDeclaredField("processing");
        processing.setAccessible(true);
        opClass = Class.forName("zombie.entity.EngineEntityManager$EntityOperation");
        typeClass = Class.forName("zombie.entity.EngineEntityManager$EntityOperation$Type");

        incidentReplay(defer, guard);
        guardCases(guard);
        if (defer) {
            deferCases();
        } else {
            noDeferCases();
        }

        expect("計數恆等式：deferred＝drained＋cancelled＋pending",
                MdcEntityEngineGuard.deferredAddForTest() + MdcEntityEngineGuard.deferredRemoveForTest()
                        == MdcEntityEngineGuard.drainedForTest() + MdcEntityEngineGuard.cancelledForTest() + MdcEntityEngineGuard.pendingForTest());
        if (!defer) {
            expect("defer off：沒有任何排隊", MdcEntityEngineGuard.deferredAddForTest() + MdcEntityEngineGuard.deferredRemoveForTest() == 0);
        }
        if (!guard) {
            expect("guard off：W106 計數全 0", MdcEntityEngineGuard.nullAddForTest() + MdcEntityEngineGuard.nullRemoveForTest()
                    + MdcEntityEngineGuard.failedAddForTest() + MdcEntityEngineGuard.failedRemoveForTest() == 0);
        }
        MdcEntityEngineGuard.beatForTest();
        String beat = MdcEntityEngineGuard.lastBeatLine;
        expect("心跳欄位齊全：" + beat, beat != null && beat.contains("deferred=") && beat.contains(" live=") && beat.contains(" drained=")
                && beat.contains(" cancelled=") && beat.contains(" pending=0 ") && beat.contains(" overflow=0 ") && beat.contains(" nullOps=")
                && beat.contains(" opFailed=") && beat.contains(" anomalies=0 "));

        if (failed > 0) {
            System.out.println("MdcEntityEngineGuardTest FAIL x" + failed);
            System.exit(1);
        }
        System.out.println("MdcEntityEngineGuardTest 全數通過（" + mode + "）");
    }

    /** 事故重現：free 做到一半時背景執行緒 obtain，主執行緒 reset 把它的 entity 清成 null。 */
    private static void incidentReplay(boolean defer, boolean guard) throws Exception {
        GameEntity animal = entity("trailerAnimal");   // 從沒進過 engine
        processing.setBoolean(engine, true);
        Object x = pool.obtain();
        freeObjects.add(x);                              // SingleThreadPool.free 的前半：放回池
        int freeBefore = freeObjects.size;
        long nullBefore = MdcEntityEngineGuard.nullRemoveForTest();
        Throwable bg = background(() -> GameEntityManager.UnregisterEntity(animal, false));
        ((SingleThreadPool.Poolable) x).reset();         // free 的後半：主執行緒 reset
        expect("事故：背景 Unregister 不拋例外", bg == null);
        if (defer) {
            expect("事故 W105：背景沒碰操作池與待處理佇列，entity 旗標沒被背景寫",
                    freeObjects.size == freeBefore && pendingOps.size == 0 && !animal.scheduledForEngineRemoval
                            && MdcEntityEngineGuard.pendingForTest() == 1);
            processing.setBoolean(engine, false);
            MdcEntityEngineGuard.update(engine);
            expect("事故 W105：下一個 tick 排空，從沒進過 engine 的 entity 移除是 no-op",
                    MdcEntityEngineGuard.pendingForTest() == 0 && pendingOps.size == 0 && !animal.scheduledForEngineRemoval);
            freeObjects.removeValue(x, true);
            return;
        }
        expect("事故（原版前提）：背景從池拿走那個物件、放進佇列，主執行緒 reset 後 entity 是 null",
                freeObjects.size == freeBefore - 1 && pendingOps.size == 1 && opEntity(pendingOps.get(0)) == null
                        && animal.scheduledForEngineRemoval);
        processing.setBoolean(engine, false);
        if (guard) {
            Throwable t = process();
            expect("事故 W106：processPendingOperations 不拋、那筆略過、佇列清空", t == null && pendingOps.size == 0
                    && MdcEntityEngineGuard.nullRemoveForTest() == nullBefore + 1);
        } else {
            boolean stuck = true;
            for (int i = 0; i < 3; i++) {
                Throwable t = process();
                stuck &= t instanceof IllegalArgumentException && String.valueOf(t.getMessage()).startsWith("key cannot be null")
                        && pendingOps.size == 1;
            }
            expect("事故（原版 off）：連續三次 processPendingOperations 都拋 key cannot be null，那筆一直留在佇列", stuck);
            pendingOps.clear();
        }
        animal.scheduledForEngineRemoval = false;
    }

    private static void guardCases(boolean guard) throws Exception {
        // G1：[Remove A、Remove null、Add B]
        GameEntity a = entity("A");
        GameEntity b = entity("B");
        engine.addEntity(a);
        processing.setBoolean(engine, true);
        engine.removeEntity(a);   // 原版延遲路徑：排 Remove A
        inject("Add", b);
        b.scheduledDelayedAddToEngine = true;
        insertAfter(0, null, "Remove");   // 夾在中間：[Remove A, Add B] → [Remove A, Remove null, Add B]
        processing.setBoolean(engine, false);
        expect("G1 前提：佇列 3 筆、第 2 筆 entity 為 null", pendingOps.size == 3 && opEntity(pendingOps.get(1)) == null);
        long n0 = MdcEntityEngineGuard.nullRemoveForTest();
        if (guard) {
            Throwable t = process();
            expect("G1 W106：一次處理不拋，A 移除、B 加入、佇列清空、nullOps+1", t == null && !a.addedToEngine && b.addedToEngine
                    && pendingOps.size == 0 && MdcEntityEngineGuard.nullRemoveForTest() == n0 + 1);
        } else {
            boolean stuck = true;
            for (int i = 0; i < 3; i++) {
                stuck &= process() instanceof IllegalArgumentException && pendingOps.size == 3;
            }
            expect("G1（原版）：每次都拋、佇列 3 筆不動、B 永遠沒加入", stuck && !b.addedToEngine);
            pendingOps.clear();
            b.scheduledDelayedAddToEngine = false;
        }
        cleanup(a, b);

        // G2：Add null
        processing.setBoolean(engine, true);
        inject("Add", null);
        processing.setBoolean(engine, false);
        Throwable t2 = process();
        if (guard) {
            expect("G2 W106：null Add 略過、佇列清空", t2 == null && pendingOps.size == 0 && MdcEntityEngineGuard.nullAddForTest() == 1);
        } else {
            expect("G2（原版）：null Add 拋例外、留在佇列", t2 instanceof RuntimeException && pendingOps.size == 1);
            pendingOps.clear();
        }

        // G3：Add 的 entity 已在 engine（Entity is already registered）
        GameEntity c = entity("C");
        GameEntity d = entity("D");
        engine.addEntity(c);
        processing.setBoolean(engine, true);
        inject("Add", c);
        c.scheduledDelayedAddToEngine = true;
        inject("Add", d);
        d.scheduledDelayedAddToEngine = true;
        processing.setBoolean(engine, false);
        Throwable t3 = process();
        if (guard) {
            expect("G3 W106：already registered 吞掉、旗標清掉、後一筆照加、佇列清空", t3 == null && !c.scheduledDelayedAddToEngine
                    && d.addedToEngine && pendingOps.size == 0 && MdcEntityEngineGuard.failedAddForTest() == 1);
            engine.removeEntity(c);
            expect("G3 W106：之後的 removeEntity 真的把它移出 engine（不走取消分支）", !c.addedToEngine && !engine.getEntities().contains(c, true));
        } else {
            expect("G3（原版）：拋 Entity is already registered、後一筆沒加", t3 instanceof IllegalArgumentException
                    && String.valueOf(t3.getMessage()).startsWith("Entity is already registered") && !d.addedToEngine);
            pendingOps.clear();
            c.scheduledDelayedAddToEngine = false;
            d.scheduledDelayedAddToEngine = false;
        }
        cleanup(c, d);

        // G4：移除時 listener 拋 RuntimeException
        GameEntity e = entity("E");
        GameEntity f = entity("F");
        engine.addEntity(e);
        listener.throwOnRemove = e;
        processing.setBoolean(engine, true);
        engine.removeEntity(e);
        inject("Add", f);
        f.scheduledDelayedAddToEngine = true;
        processing.setBoolean(engine, false);
        Throwable t4 = process();
        listener.throwOnRemove = null;
        if (guard) {
            expect("G4 W106：移除拋例外吞掉、旗標收好、後一筆照加、佇列清空", t4 == null && !e.addedToEngine && !e.removingFromEngine
                    && f.addedToEngine && pendingOps.size == 0 && MdcEntityEngineGuard.failedRemoveForTest() == 1);
        } else {
            expect("G4（原版）：拋例外、後一筆沒加", t4 instanceof IllegalStateException && !f.addedToEngine);
            pendingOps.clear();
            f.scheduledDelayedAddToEngine = false;
        }
        cleanup(e, f);

        // G5：Error 照常外拋
        GameEntity g = entity("G");
        listener.errorOnAdd = g;
        processing.setBoolean(engine, true);
        inject("Add", g);
        g.scheduledDelayedAddToEngine = true;
        processing.setBoolean(engine, false);
        Throwable t5 = process();
        listener.errorOnAdd = null;
        expect("G5：AssertionError 照常外拋（" + (guard ? "W106" : "原版") + "）", t5 instanceof AssertionError);
        pendingOps.clear();
        cleanup(g);
    }

    private static void deferCases() throws Exception {
        // D1：mainThread 為 null 照原版
        GameEntity a = entity("D1");
        engine.addEntity(a);
        Thread main = GameServer.mainThread;
        GameServer.mainThread = null;
        background(() -> GameEntityManager.UnregisterEntity(a, false));
        GameServer.mainThread = main;
        expect("D1：mainThread 為 null 時背景照原版直接移除", !a.addedToEngine && MdcEntityEngineGuard.pendingForTest() == 0);

        // D2：engine 處理中、背景 Unregister 一個在 engine 的 entity
        GameEntity b = entity("D2");
        engine.addEntity(b);
        long live0 = MdcEntityEngineGuard.liveForTest();
        processing.setBoolean(engine, true);
        background(() -> GameEntityManager.UnregisterEntity(b, false));
        expect("D2：背景不寫待處理佇列與旗標、記一筆 live", pendingOps.size == 0 && !b.scheduledForEngineRemoval && b.addedToEngine
                && MdcEntityEngineGuard.pendingForTest() == 1 && MdcEntityEngineGuard.liveForTest() == live0 + 1);
        processing.setBoolean(engine, false);
        MdcEntityEngineGuard.update(engine);   // GameEntityManager.Update 的改道點（接線由 SmokeCheck 驗）
        expect("D2：下一個 tick 的 engine.update 改道後離開 engine", !b.addedToEngine && !engine.getEntities().contains(b, true)
                && MdcEntityEngineGuard.pendingForTest() == 0);

        // D3：不在處理中，背景 Unregister：回來時還在 engine，排空後才離開
        GameEntity c = entity("D3");
        engine.addEntity(c);
        background(() -> GameEntityManager.UnregisterEntity(c, false));
        expect("D3：背景回來時 entity 還在 engine", c.addedToEngine && engine.getEntities().contains(c, true));
        MdcEntityEngineGuard.drain();
        expect("D3：排空後離開 engine", !c.addedToEngine);

        // D4：FIFO
        GameEntity p = entity("P");
        GameEntity q = entity("Q");
        GameEntity r = entity("R");
        engine.addEntity(r);
        listener.events.clear();
        background(() -> {
            MdcEntityEngineGuard.addEntity(engine, p);
            MdcEntityEngineGuard.addEntity(engine, q);
            MdcEntityEngineGuard.removeEntity(engine, r);
        });
        expect("D4：背景三筆都排隊、engine 沒變", listener.events.isEmpty() && MdcEntityEngineGuard.pendingForTest() == 3);
        MdcEntityEngineGuard.drain();
        expect("D4：排空依 FIFO：" + listener.events, listener.events.equals(List.of("+P", "+Q", "-R")));
        cleanup(p, q);

        // D5：背景移除在佇列、主執行緒加回同一個 entity
        GameEntity s = entity("S");
        engine.addEntity(s);
        background(() -> GameEntityManager.UnregisterEntity(s, false));
        Throwable t5 = null;
        try {
            MdcEntityEngineGuard.addEntity(engine, s);
        } catch (RuntimeException ex) {
            t5 = ex;
        }
        int copies = 0;
        for (GameEntity x : engine.getEntities()) {
            copies += x == s ? 1 : 0;
        }
        expect("D5：主執行緒先排空再加回：不拋 already registered、只在 engine 一次", t5 == null && s.addedToEngine && copies == 1);
        cleanup(s);

        // D6：背景先加後移：取消待加入，排空後不在 engine、不留在 bucket
        GameEntity u = entity("U");
        long cancelled0 = MdcEntityEngineGuard.cancelledForTest();
        background(() -> {
            MdcEntityEngineGuard.addEntity(engine, u);
            MdcEntityEngineGuard.removeEntity(engine, u);
        });
        expect("D6：待加入被取消、只剩移除", MdcEntityEngineGuard.cancelledForTest() == cancelled0 + 1 && MdcEntityEngineGuard.pendingForTest() == 1);
        MdcEntityEngineGuard.drain();
        expect("D6：排空後不在 engine、不在 IsoObject bucket", !u.addedToEngine && !engine.getEntities().contains(u, true)
                && !engine.getIsoObjectBucket().getEntities().contains(u, true));

        // D7：排空時某筆拋例外，不外拋、下一筆照做
        GameEntity v = entity("V");
        GameEntity w = entity("W");
        engine.addEntity(v);
        long df0 = MdcEntityEngineGuard.drainFailedForTest();
        background(() -> {
            MdcEntityEngineGuard.addEntity(engine, v);   // already registered
            MdcEntityEngineGuard.addEntity(engine, w);
        });
        Throwable t7 = null;
        try {
            MdcEntityEngineGuard.drain();
        } catch (RuntimeException ex) {
            t7 = ex;
        }
        expect("D7：排空時 already registered 不外拋、下一筆照加", t7 == null && w.addedToEngine
                && MdcEntityEngineGuard.drainFailedForTest() == df0 + 1);
        cleanup(v, w);

        // D8：Save 頭部只在主執行緒排空
        GameEntity y = entity("Y");
        engine.addEntity(y);
        background(() -> {
            GameEntityManager.UnregisterEntity(y, false);
            MdcEntityEngineGuard.beforeSave();
        });
        expect("D8：背景呼叫 beforeSave 不排空", MdcEntityEngineGuard.pendingForTest() == 1 && y.addedToEngine);
        MdcEntityEngineGuard.beforeSave();
        expect("D8：主執行緒 beforeSave 排空", MdcEntityEngineGuard.pendingForTest() == 0 && !y.addedToEngine);
    }

    /** defer off（off／defer-off）：背景直接動 engine（原版）。 */
    private static void noDeferCases() throws Exception {
        GameEntity c = entity("N3");
        engine.addEntity(c);
        background(() -> GameEntityManager.UnregisterEntity(c, false));
        expect("defer off（原版）：背景 Unregister 當下就把 entity 移出 engine", !c.addedToEngine);
        GameEntity b = entity("N2");
        engine.addEntity(b);
        processing.setBoolean(engine, true);
        background(() -> GameEntityManager.UnregisterEntity(b, false));
        expect("defer off（原版）：engine 處理中，背景寫待處理佇列與旗標", pendingOps.size == 1 && b.scheduledForEngineRemoval);
        processing.setBoolean(engine, false);
        process();
        expect("defer off（原版）：之後由處理佇列移除", !b.addedToEngine && pendingOps.size == 0);
    }

    // ---- 工具 ----

    private static Throwable process() {
        try {
            mgr.processPendingOperations();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static Throwable background(Runnable r) throws InterruptedException {
        Throwable[] out = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable ex) {
                out[0] = ex;
            }
        }, "ServerPlayersVehicles");
        t.start();
        t.join();
        return out[0];
    }

    /** 從原版池拿一個操作物件、填好後放到佇列尾（模擬原版延遲路徑）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void inject(String type, GameEntity entity) throws Exception {
        Object op = pool.obtain();
        set(opClass, op, "type", Enum.valueOf((Class) typeClass, type));
        set(opClass, op, "entity", entity);
        pendingOps.add(op);
    }

    /** 在佇列第 after 筆之後插一筆（其後的往後移）。 */
    private static void insertAfter(int after, GameEntity entity, String type) throws Exception {
        inject(type, entity);
        Object injected = pendingOps.pop();
        List<Object> rest = new ArrayList<>();
        while (pendingOps.size > after + 1) {
            rest.add(0, pendingOps.pop());
        }
        pendingOps.add(injected);
        for (Object o : rest) {
            pendingOps.add(o);
        }
    }

    private static GameEntity opEntity(Object op) throws Exception {
        return (GameEntity) get(opClass, op, "entity");
    }

    private static void cleanup(GameEntity... es) {
        for (GameEntity e : es) {
            if (e.addedToEngine) {
                engine.removeEntity(e);
            }
            e.scheduledDelayedAddToEngine = false;
            e.scheduledForEngineRemoval = false;
            e.removingFromEngine = false;
        }
    }

    private static GameEntity entity(String name) throws Exception {
        GameEntity e = (GameEntity) raw(IsoObject.class);
        NAMES.put(e, name);
        return e;
    }

    private static String name(GameEntity e) {
        return NAMES.getOrDefault(e, "?");
    }

    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory().newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "eeg pass  " : "eeg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
