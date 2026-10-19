package zombie.entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import zombie.entity.util.LongMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;
import zombie.util.list.PZArrayList;

/**
 * W69 MdcEntityIdGuard 行為驗證（獨立 JVM；argv[0] = on|off，須與 {@code -Dmdc.entityIdGuard} 相符）。
 * 走 dist 內已 patch 的 {@code GameEntity.removeFromWorld(boolean)} 與真 {@code GameEntityManager.UnregisterEntity}、
 * 真 {@code Engine}、真 {@code IsoObject.getEntityNetID}（地板一律算序號 0）。
 *
 * <p>鎖：撞號（殘件排第 0 個、玩家後蓋的地板排後面）on 不丟例外、殘件離開 engine、ID 表仍是地板，之後地板照常移除；
 * 原版丟 {@code Stored entity mismatch}、殘件留在 engine、地板的登記被拿掉，再移除一次也拿不掉。
 * ID 未登記：on 離開 engine，原版留在 engine。正常移除兩邊相同。
 */
public final class MdcEntityIdGuardTest {

    private static int failed;

    /** 玩家後蓋的地板（{@code IsoThumpable} 地板覆寫 isFloor）；getEntityNetID 對它一律算序號 0。 */
    static final class Floor extends IsoObject {
        @Override
        public boolean isFloor() {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean on = !(args.length > 0 && "off".equals(args[0]));
        expect("開關與 argv 相符（" + (on ? "on" : "off") + "）", MdcEntityIdGuard.ENABLED == on);
        expect("反射取得 idToEntityMap", MdcEntityIdGuard.mapResolvedForTest());
        GameServer.server = true;
        Engine engine = new Engine();
        Field engineField = GameEntityManager.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        engineField.set(null, engine);
        LongMap<GameEntity> map = map();

        // 撞號：殘件排第 0 個、地板排第 1 個，兩者同 ID，ID 表存的是地板
        IsoGridSquare sq = square(500, 600, 1);
        IsoObject part = place(sq, (IsoObject) raw(IsoObject.class));
        IsoObject floor = place(sq, (IsoObject) raw(Floor.class));
        register(engine, map, floor);
        long id = floor.getEntityNetID();
        enter(engine, part);
        expect("前提：殘件排第 0 個、地板排第 1 個，兩者同 ID，ID 表存的是地板",
                part.getObjectIndex() == 0 && floor.getObjectIndex() == 1
                        && part.getEntityNetID() == id && map.get(id) == floor);
        RuntimeException thrown = remove(part);
        if (on) {
            expect("on：撞號不丟例外", thrown == null);
            expect("on：殘件離開 engine、取消登記", !part.isAddedToEngine() && !reg(part));
            expect("on：ID 表仍是地板，地板仍在 engine", map.get(id) == floor && floor.isAddedToEngine() && reg(floor));
            expect("on：記一筆撞號、地板已放回", MdcEntityIdGuard.collisionsForTest() == 1 && MdcEntityIdGuard.notRestoredForTest() == 0);
            expect("on：之後移除地板照常", remove(floor) == null && !floor.isAddedToEngine() && map.get(id) == null);
        } else {
            expect("off（原版）：丟 Stored entity mismatch", thrown != null && "Stored entity mismatch".equals(thrown.getMessage()));
            expect("off（原版）：殘件仍登記、仍在 engine", reg(part) && part.isAddedToEngine());
            expect("off（原版）：地板的登記被拿掉", map.get(id) == null && reg(floor));
            expect("off（原版）：再移除一次不丟例外，殘件仍留在 engine",
                    remove(part) == null && part.isAddedToEngine() && reg(part));
        }

        // ID 未登記：登記過、在 engine，但 ID 表沒有它
        IsoGridSquare sq2 = square(100, 200, 0);
        IsoObject lost = place(sq2, (IsoObject) raw(IsoObject.class));
        enter(engine, lost);
        long id2 = lost.getEntityNetID();
        expect("前提：ID 表沒有它", map.get(id2) == null);
        expect("ID 未登記：不丟例外", remove(lost) == null);
        if (on) {
            expect("on：離開 engine、取消登記，ID 表維持空", !lost.isAddedToEngine() && !reg(lost) && map.get(id2) == null);
            expect("on：記一筆 ID 未登記", MdcEntityIdGuard.missingForTest() == 1);
        } else {
            expect("off（原版）：留在 engine、仍登記", lost.isAddedToEngine() && reg(lost));
        }

        // 正常：ID 表存的就是自己
        IsoGridSquare sq3 = square(300, 400, 0);
        IsoObject normal = place(sq3, (IsoObject) raw(IsoObject.class));
        register(engine, map, normal);
        long id3 = normal.getEntityNetID();
        expect("正常移除：不丟例外、離開 engine、取消登記、ID 表清空",
                remove(normal) == null && !normal.isAddedToEngine() && !reg(normal) && map.get(id3) == null);
        expect("計數只含撞號與未登記各一（off 全 0）", on
                ? MdcEntityIdGuard.collisionsForTest() == 1 && MdcEntityIdGuard.missingForTest() == 1
                : MdcEntityIdGuard.collisionsForTest() == 0 && MdcEntityIdGuard.missingForTest() == 0);

        if (failed > 0) {
            System.out.println("MdcEntityIdGuardTest FAIL x" + failed);
            System.exit(1);
        }
        System.out.println("MdcEntityIdGuardTest 全數通過（" + (on ? "on" : "off") + "）");
    }

    /** 走 dist 內已 patch 的 GameEntity.removeFromWorld(boolean)；回傳丟出的例外（沒有就 null）。 */
    private static RuntimeException remove(GameEntity e) {
        try {
            e.removeFromWorld(false);
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    /** 原版 RegisterEntity 的結果：進 engine、ID 表存自己、已登記。 */
    private static void register(Engine engine, LongMap<GameEntity> map, IsoObject o) {
        engine.addEntity(o);
        map.put(o.getEntityNetID(), o);
        ((GameEntity) o).addedToEntityManager = true;
    }

    /** 登記過、在 engine，但 ID 表沒有它（原版 checkEntityIDChange 換號時放不進去）。 */
    private static void enter(Engine engine, IsoObject o) {
        engine.addEntity(o);
        ((GameEntity) o).addedToEntityManager = true;
    }

    /** GameEntity.addedToEntityManager 是 package-private，經 IsoObject 型別讀不到。 */
    private static boolean reg(GameEntity e) {
        return e.addedToEntityManager;
    }

    private static IsoGridSquare square(int x, int y, int z) throws Exception {
        IsoGridSquare sq = (IsoGridSquare) raw(IsoGridSquare.class);
        set(IsoGridSquare.class, sq, "objects", new PZArrayList<>(IsoObject.class, 4));
        sq.x = x;
        sq.y = y;
        sq.z = z;
        return sq;
    }

    private static IsoObject place(IsoGridSquare sq, IsoObject o) throws Exception {
        o.square = sq;
        sq.getObjects().add(o);
        set(IsoObject.class, o, "isoEntityNetId", -1L);
        set(IsoObject.class, o, "lastObjectIndex", -1);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static LongMap<GameEntity> map() throws Exception {
        Field f = GameEntityManager.class.getDeclaredField("idToEntityMap");
        f.setAccessible(true);
        return (LongMap<GameEntity>) f.get(null);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
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
        System.out.println((ok ? "eid pass  " : "eid FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
