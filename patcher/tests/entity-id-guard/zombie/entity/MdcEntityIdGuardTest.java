package zombie.entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;

import zombie.entity.util.LongMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * W69 MdcEntityIdGuard 行為驗證（獨立 JVM；argv[0] = on|off，須與 {@code -Dmdc.entityIdGuard} 相符）。
 * 走 dist 內已 patch 的 {@code GameEntity.removeFromWorld(boolean)} 與真 {@code GameEntityManager.UnregisterEntity}、
 * 真 {@code Engine}、真 {@code IsoObject.getEntityNetID}（42.22 起地板也用實際序號）與真 {@code checkEntityIDChange}。
 *
 * <p>鎖：42.22 地板不再與第 0 個物件同 ID（TIS 修掉 42.21 的撞號根源）；同格前面的物件被移走、序號往前移時，原版
 * {@code checkEntityIDChange} 會先讓佔號的物件自己換號，兩邊都不丟例外。ID 表那一格仍被別的實體佔著時（42.22 已知來源
 * 都會自癒，這裡直接放一個別的實體進那一格）：on 不丟例外、它完整取消登記、佔號實體放回；原版丟
 * {@code Stored entity mismatch}、佔號實體的登記被拿掉、它自己仍算登記中。ID 未登記：兩邊都離開 engine
 * （42.22 原版先 removeEntity），on 另外取消登記，原版仍算登記中。正常移除兩邊相同。
 */
public final class MdcEntityIdGuardTest {

    private static int failed;

    /** 玩家後蓋的地板（{@code IsoThumpable} 地板覆寫 isFloor）；42.21 的 getEntityNetID 對它一律算序號 0。 */
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

        // 42.22 前提：地板用實際序號，不再與第 0 個物件同 ID
        IsoGridSquare sq0 = square(700, 800, 0);
        IsoObject first = place(sq0, (IsoObject) raw(IsoObject.class));
        IsoObject laterFloor = place(sq0, (IsoObject) raw(Floor.class));
        expect("42.22 前提：後蓋的地板（序號 1）與第 0 個物件不同 ID",
                laterFloor.getObjectIndex() == 1 && first.getEntityNetID() != laterFloor.getEntityNetID());

        // 42.22 前提（兩邊相同）：同格 [前, 中, 後] 都登記後移走「前」；後先換號時 checkEntityIDChange 讓中自己換號，不撞
        IsoGridSquare sqShift = square(500, 600, 1);
        IsoObject front = place(sqShift, (IsoObject) raw(IsoObject.class));
        IsoObject mid = place(sqShift, (IsoObject) raw(IsoObject.class));
        IsoObject back = place(sqShift, (IsoObject) raw(IsoObject.class));
        register(engine, map, front);
        register(engine, map, mid);
        register(engine, map, back);
        map.remove(front.getEntityNetID());
        sqShift.getObjects().remove(front);
        expect("42.22 前提：序號往前移後移除排後面的不丟例外、它取消登記、排前面的已換號仍登記",
                remove(back) == null && !reg(back) && map.get(mid.getEntityNetID()) == mid && reg(mid));
        expect("42.22 前提：上面的移除不算撞號", MdcEntityIdGuard.collisionsForTest() == 0);

        // 撞號：ID 表那一格存的是別的實體（沒有格子的 IsoObject）
        IsoGridSquare sq = square(510, 610, 1);
        IsoObject part = place(sq, (IsoObject) raw(IsoObject.class));
        register(engine, map, part);
        long id = part.getEntityNetID();
        IsoObject holder = (IsoObject) raw(IsoObject.class);
        map.put(id, holder);
        expect("前提：ID 表那一格存的是別的實體", map.get(id) == holder && part.getEntityNetID() == id);
        RuntimeException thrown = remove(part);
        if (on) {
            expect("on：撞號不丟例外", thrown == null);
            expect("on：被移除的實體離開 engine、取消登記", !part.isAddedOrScheduledForEngine() && !reg(part));
            expect("on：ID 表放回佔號實體", map.get(id) == holder);
            expect("on：記一筆撞號、佔號實體已放回",
                    MdcEntityIdGuard.collisionsForTest() == 1 && MdcEntityIdGuard.notRestoredForTest() == 0);
        } else {
            expect("off（原版）：丟 Stored entity mismatch",
                    thrown != null && "Stored entity mismatch".equals(thrown.getMessage()));
            expect("off（原版 42.22）：被移除的實體已離開 engine 但仍算登記中", !part.isAddedOrScheduledForEngine() && reg(part));
            expect("off（原版）：佔號實體的登記被拿掉", map.get(id) == null);
        }

        // ID 未登記：登記過、在 engine，但 ID 表沒有它
        IsoGridSquare sq2 = square(100, 200, 0);
        IsoObject lost = place(sq2, (IsoObject) raw(IsoObject.class));
        enter(engine, lost);
        long id2 = lost.getEntityNetID();
        expect("前提：ID 表沒有它", map.get(id2) == null);
        expect("ID 未登記：不丟例外", remove(lost) == null);
        if (on) {
            expect("on：離開 engine、取消登記，ID 表維持空",
                    !lost.isAddedOrScheduledForEngine() && !reg(lost) && map.get(id2) == null);
            expect("on：記一筆 ID 未登記", MdcEntityIdGuard.missingForTest() == 1);
        } else {
            expect("off（原版 42.22）：離開 engine，但仍算登記中", !lost.isAddedOrScheduledForEngine() && reg(lost));
        }

        // 正常：ID 表存的就是自己
        IsoGridSquare sq3 = square(300, 400, 0);
        IsoObject normal = place(sq3, (IsoObject) raw(IsoObject.class));
        register(engine, map, normal);
        long id3 = normal.getEntityNetID();
        expect("正常移除：不丟例外、離開 engine、取消登記、ID 表清空",
                remove(normal) == null && !normal.isAddedOrScheduledForEngine() && !reg(normal) && map.get(id3) == null);
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
        set(IsoGridSquare.class, sq, "objects", new ArrayList<IsoObject>(4));
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
