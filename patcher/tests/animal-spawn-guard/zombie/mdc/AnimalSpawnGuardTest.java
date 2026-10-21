package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.ServerMap;
import zombie.popman.animal.AnimalInstanceManager;
import zombie.util.list.PZArrayList;

/**
 * W37 AnimalSpawnGuard 行為驗證（無參數＝啟用、{@code off}＝kill switch）。
 * 鎖：建構失敗（data null、帶座標）從 addList／objectList（依 isSafeToAdd）撤出，並撤出格子的
 * movingObjects（IsoGameCharacter 建構子的 setMovingSquareNow；留著會被 getAnimals 看到）；(IsoCell) 載入用
 * 0,0,0 與正常建構不碰；grow／addBaby 只吞「本次呼叫有建構失敗」的 NPE，其餘例外穿透；水格放下的動物（adef null）
 * 不放回格子、註銷 AnimalInstanceManager、不送 DropAnimal；chunk 卸載只吞 adef null 動物的例外；off 全直通。
 * 放下到水格旁的陸地格（真 IsoGridSquare：水格＝地板 FloorMaterial=Water，鄰格查 ServerMap 替身，碰撞讀 collideMatrix）：
 * 鄰格是水、被圍欄擋住、有實心物都跳過，選第一個能放的；不是水格、四周沒有陸地都回原格；dropPlace 把原版指回水格的
 * current 換回陸地格，dropSend 送陸地格。
 */
public final class AnimalSpawnGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", AnimalSpawnGuard.enabledForTest() == !off);

        IsoCell cell = (IsoCell) raw(IsoCell.class);
        Set<IsoMovingObject> objects = new HashSet<>(), adds = new HashSet<>(), removes = new HashSet<>();
        set(cell, "objectList", objects);
        set(cell, "addList", adds);
        set(cell, "removeList", removes);

        // update 中（not safe）：IsoGameCharacter 建構子把物件放進 addList。
        cell.setSafeToAdd(false);
        Fake broken = fake(cell, 101, 103);
        IsoGridSquare sq1 = square();
        adds.add(broken);
        place(broken, sq1);
        AnimalSpawnGuard.afterCtor(broken);
        expect(off ? "off：失敗物件留在 addList（原版洩漏）" : "unsafe：失敗物件自 addList 撤出",
                adds.contains(broken) == off);
        expect(off ? "off：失敗物件留在格子 movingObjects" : "unsafe：失敗物件自格子 movingObjects 撤出",
                sq1.getMovingObjects().contains(broken) == off && (broken.getCurrentSquare() == sq1) == off);

        // update 外（safe）：直接進 objectList。
        cell.setSafeToAdd(true);
        Fake broken2 = fake(cell, 102, 104);
        IsoGridSquare sq2 = square();
        objects.add(broken2);
        place(broken2, sq2);
        AnimalSpawnGuard.afterCtor(broken2);
        expect(off ? "off：失敗物件留在 objectList" : "safe：失敗物件自 objectList 撤出",
                objects.contains(broken2) == off);
        expect(off ? "off：失敗物件留在格子 movingObjects" : "safe：失敗物件自格子 movingObjects 撤出",
                sq2.getMovingObjects().contains(broken2) == off);

        Fake loading = fake(cell, 0, 0);
        objects.add(loading);
        AnimalSpawnGuard.afterCtor(loading);
        expect("(IsoCell) 載入用 0,0,0 不碰", objects.contains(loading));

        Fake healthy = fake(cell, 10, 10);
        set(healthy, "data", raw(AnimalData.class));
        objects.add(healthy);
        AnimalSpawnGuard.afterCtor(healthy);
        expect("正常建構（data 非 null）不碰", objects.contains(healthy));
        if (!off) {
            expect("ctorFailures=2 removed=2 squareRemoved=2", AnimalSpawnGuard.ctorFailuresForTest() == 2
                    && AnimalSpawnGuard.removedForTest() == 2 && AnimalSpawnGuard.squareRemovedForTest() == 2);
        }

        // grow：該次建構失敗造成的 NPE。
        Fake chick = fake(cell, 103, 101);
        FakeData growsBroken = data(chick, () -> {
            AnimalSpawnGuard.afterCtor(fake(cell, 103, 101));
            throw new NullPointerException("getData() is null");
        });
        expect(off ? "off：grow NPE 原樣穿透" : "grow 建構失敗 NPE 被吞", throwsNpe(() -> AnimalSpawnGuard.grow(growsBroken, "hen")) == off);
        expect("grow 確實委派原版", growsBroken.calls == 1);

        FakeData unrelatedNpe = data(chick, () -> { throw new NullPointerException("other"); });
        expect("無建構失敗的 NPE 一律穿透", throwsNpe(() -> AnimalSpawnGuard.grow(unrelatedNpe, "hen")));

        FakeData boom = data(chick, () -> {
            AnimalSpawnGuard.afterCtor(fake(cell, 1, 1));
            throw new IllegalStateException("x");
        });
        boolean ise = false;
        try {
            AnimalSpawnGuard.grow(boom, "hen");
        } catch (IllegalStateException e) {
            ise = true;
        }
        expect("非 NPE 例外穿透", ise);

        FakeData ok = data(chick, () -> {});
        AnimalSpawnGuard.grow(ok, "hen");
        expect("正常 grow 委派一次", ok.calls == 1);

        // addBaby：同一規則，被吞時回 null。
        Fake mother = fake(cell, 101, 100);
        mother.baby = () -> {
            AnimalSpawnGuard.afterCtor(fake(cell, 101, 100));
            throw new NullPointerException("getData() is null");
        };
        boolean npe = false;
        IsoAnimal r = null;
        try {
            r = AnimalSpawnGuard.addBaby(mother);
        } catch (NullPointerException e) {
            npe = true;
        }
        expect(off ? "off：addBaby NPE 穿透" : "addBaby 建構失敗回 null", off ? npe : !npe && r == null);
        IsoAnimal marker = fake(cell, 3, 3);
        mother.baby = null;
        mother.result = marker;
        expect("正常 addBaby 回傳原版結果", AnimalSpawnGuard.addBaby(mother) == marker);

        // 放下動物（2026-10-20）：AddWorldInventoryItem 在放下的格子建新動物，水格上建構失敗（adef null），
        // 原版已 copyFrom、登記 AnimalInstanceManager、setSquare／setCurrent，這一步把它放回格子。
        AnimalInstanceManager aim = AnimalInstanceManager.getInstance();
        Fake dropped = fake(cell, 201, 201);
        dropped.removeNpe = true;   // 原版 removeFromWorld 在 releaseRagdollController 讀 getDef(null) NPE
        IsoGridSquare dropSq = square();
        set(dropped, "current", dropSq);
        set(dropped, "square", dropSq);
        aim.add(dropped, (short) 77);
        AnimalSpawnGuard.dropPlace(dropped);
        expect(off ? "off：水格放下的動物被放回格子 movingObjects（原版）" : "水格放下的動物不放回格子、current 清空",
                dropSq.getMovingObjects().contains(dropped) == off && (dropped.getCurrentSquare() == dropSq) == off);
        expect(off ? "off：AnimalInstanceManager 仍指著它" : "AnimalInstanceManager 註銷它",
                (aim.get((short) 77) == dropped) == off);
        Object[] dropArgs = {null, dropped, dropSq};
        AnimalSpawnGuard.dropSend(PacketTypes.PacketType.AnimalCommand, 201f, 201f, dropArgs);
        expect(off ? "off：DropAnimal 照送" : "不送 DropAnimal", AnimalSpawnGuard.unsentForTest() == (off ? 0 : 1));

        Fake placed = fake(cell, 202, 202);
        set(placed, "adef", raw(AnimalDefinitions.class));
        IsoGridSquare placeSq = square();
        set(placed, "current", placeSq);
        AnimalSpawnGuard.dropPlace(placed);
        AnimalSpawnGuard.dropSend(PacketTypes.PacketType.AnimalCommand, 202f, 202f, new Object[]{null, placed, placeSq});
        expect("建構完成的動物照原版放上格子、照送", placeSq.getMovingObjects().contains(placed)
                && AnimalSpawnGuard.unsentForTest() == (off ? 0 : 1));

        // 放下到水格旁的陸地格（2026-10-21）：建構子座標改讀 dropX／dropY；原版 setSquare／setCurrent 又指回水格，
        // dropPlace 換回陸地格、dropSend 送陸地格。IsoCell.getGridSquare 在伺服器上走 ServerMap.instance。
        GameServer.server = true;
        ServerMap.instance = (ServerMap) raw(TestServerMap.class);
        IsoWorld.instance.currentCell = cell;
        IsoMetaGrid meta = IsoWorld.instance.metaGrid;   // testCollideAdjacent 先查 isValidChunk
        meta.minX = 0;
        meta.minY = 0;
        meta.maxX = 10;
        meta.maxY = 10;
        registerFloorMaterial();
        IsoGridSquare water = grid(300, 400, true);
        grid(300, 399, true);                            // 北：也是水
        IsoGridSquare fenced = grid(300, 401, false);    // 南：中間有圍欄
        water.collideMatrix |= 1 << (1 + 2 * 3 + 9);
        grid(299, 400, false).setCacheIsFree(false);     // 西：牆、樹之類的實心物
        IsoGridSquare east = grid(301, 400, false);      // 東：第一個能放的
        grid(301, 401, false);                           // 東南：也能放，但排在後面
        expect(off ? "off：水格照原版回原格" : "水格：跳過水、圍欄、實心物，選上下左右中第一個能放的陸地格",
                AnimalSpawnGuard.landFor(water) == (off ? water : east));
        expect(off ? "off：建構子座標＝水格" : "建構子座標＝陸地格", AnimalSpawnGuard.dropX(water) == (off ? 300 : 301)
                && AnimalSpawnGuard.dropY(water) == 400);
        expect("陸地格回原格", AnimalSpawnGuard.landFor(fenced) == fenced && AnimalSpawnGuard.dropX(fenced) == 300
                && AnimalSpawnGuard.dropY(fenced) == 401);
        IsoGridSquare lake = grid(320, 420, true);       // 四周沒有載入的格子
        expect("四周沒有陸地：回原格（照舊建構失敗、丟棄）", AnimalSpawnGuard.landFor(lake) == lake
                && AnimalSpawnGuard.dropX(lake) == 320 && AnimalSpawnGuard.dropY(lake) == 420);

        Fake moved = fake(cell, 301, 400);
        set(moved, "adef", raw(AnimalDefinitions.class));
        place(moved, east);                              // 建構子在陸地格建好
        set(moved, "current", water);                    // 原版 setSquare／setCurrent 指回放下的水格
        set(moved, "square", water);
        AnimalSpawnGuard.dropPlace(moved);
        expect(off ? "off：照原版放回水格" : "建好的動物留在陸地格、不在水格",
                (moved.getCurrentSquare() == east) == !off && east.getMovingObjects().contains(moved) == !off
                        && water.getMovingObjects().contains(moved) == off);
        GameServer.server = false;                       // sendToRelative 在非伺服器直接返回
        Object[] movedArgs = {null, moved, water};
        AnimalSpawnGuard.dropSend(PacketTypes.PacketType.AnimalCommand, 300f, 400f, movedArgs);
        expect(off ? "off：DropAnimal 送水格" : "DropAnimal 送陸地格", movedArgs[2] == (off ? water : east));
        if (!off) {
            expect("dropMoved=1", AnimalSpawnGuard.dropMovedForTest() == 1);
        }

        // chunk 卸載：格子上沒建構完成的動物 removeFromWorld 丟例外時，原版中斷整個 cell 卸載。
        Fake stuck = fake(cell, 203, 203);
        stuck.removeNpe = true;
        expect(off ? "off：卸載時 removeFromWorld 的 NPE 穿透（原版中斷 cell 卸載）" : "卸載時吞掉沒建構完成動物的例外",
                throwsNpe(() -> AnimalSpawnGuard.chunkRemove(stuck)) == off && stuck.removes == 1);
        Fake healthyThrows = fake(cell, 204, 204);
        set(healthyThrows, "adef", raw(AnimalDefinitions.class));
        healthyThrows.removeNpe = true;
        expect("建構完成的動物丟的例外照原版穿透", throwsNpe(() -> AnimalSpawnGuard.chunkRemove(healthyThrows)));
        if (!off) {
            expect("dropDiscarded=1 unloadBroken=1", AnimalSpawnGuard.dropDiscardedForTest() == 1
                    && AnimalSpawnGuard.unloadBrokenForTest() == 1);
        }

        if (!off) {
            expect("swallowed=2 anomalies=0", AnimalSpawnGuard.swallowedForTest() == 2
                    && AnimalSpawnGuard.anomaliesForTest() == 0);
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalSpawnGuardTest 全數通過" + (off ? "（off）" : ""));
    }

    public static class Fake extends IsoAnimal {
        IsoCell cell;
        float fx, fy;
        Runnable baby;
        IsoAnimal result;
        boolean removeNpe;
        int removes;

        Fake() {
            super((IsoCell) null);
        }

        @Override public IsoCell getCell() { return cell; }
        @Override public float getX() { return fx; }
        @Override public float getY() { return fy; }
        @Override public float getZ() { return 0f; }
        @Override public String getAnimalType() { return "chick"; }
        @Override public int getAnimalID() { return 7; }

        @Override
        public void removeFromWorld() {
            removes++;
            if (removeNpe) {
                throw new NullPointerException("Cannot read field \"textureSkinned\"");
            }
        }

        @Override
        public IsoAnimal addBaby() {
            if (baby != null) {
                baby.run();
            }
            return result;
        }
    }

    public static class FakeData extends AnimalData {
        Runnable body;
        int calls;

        FakeData() {
            super(null, null);
        }

        @Override
        public void grow(String newType) {
            calls++;
            body.run();
        }
    }

    private static Fake fake(IsoCell cell, float x, float y) throws RuntimeException {
        try {
            Fake f = (Fake) raw(Fake.class);
            f.cell = cell;
            f.fx = x + 0.5f;   // IsoGameCharacter 建構子設 x+0.5；0,0 仍 (int)==0
            f.fy = y + 0.5f;
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FakeData data(IsoAnimal parent, Runnable body) throws Exception {
        FakeData d = (FakeData) raw(FakeData.class);
        d.parent = parent;
        d.body = body;
        return d;
    }

    /** 空格子：只填 removeFromSquare／isWaterSquare 會碰到的集合（無地板＝非水格）。 */
    private static IsoGridSquare square() throws Exception {
        IsoGridSquare sq = (IsoGridSquare) raw(IsoGridSquare.class);
        set(sq, "movingObjects", new ArrayList<IsoMovingObject>());
        set(sq, "staticMovingObjects", new ArrayList<IsoMovingObject>());
        set(sq, "objects", new PZArrayList<>(IsoObject.class, 2));
        set(sq, "specialObjects", new ArrayList<IsoObject>());
        return sq;
    }

    /** IsoGameCharacter 建構子的 current＝格子＋setMovingSquareNow。 */
    private static void place(IsoAnimal a, IsoGridSquare sq) throws Exception {
        set(a, "current", sq);
        set(a, "movingSq", sq);
        sq.getMovingObjects().add(a);
    }

    /** 真 IsoGridSquare：八方可通、無實心物；水格多一個地板物件（solidfloor＋FloorMaterial=Water）。 */
    private static IsoGridSquare grid(int x, int y, boolean water) throws Exception {
        IsoGridSquare sq = square();
        sq.x = x;
        sq.y = y;
        sq.z = 0;
        set(sq, "properties", new PropertyContainer());
        sq.setCachedIsFree(true);
        sq.setCacheIsFree(true);
        sq.collideMatrix = 0;
        if (water) {
            PropertyContainer floorProps = new PropertyContainer();
            floorProps.set(IsoFlagType.solidfloor);
            floorProps.set("FloorMaterial", "Water");
            IsoSprite sprite = (IsoSprite) raw(IsoSprite.class);
            set(sprite, "properties", floorProps);
            IsoObject floor = (IsoObject) raw(IsoObject.class);
            floor.sprite = sprite;
            sq.getObjects().add(floor);
        }
        TestServerMap.SQUARES.put(((long) x << 32) | (y & 0xffffffffL), sq);
        return sq;
    }

    /** 測試 JVM 沒載入 tiledef：登記 FloorMaterial 的值，isWaterSquare 才讀得到 Water。 */
    private static void registerFloorMaterial() throws Exception {
        if (TilePropertyAliasMap.instance.getIDFromPropertyName("FloorMaterial") != -1) {
            return;
        }
        Method register = TilePropertyAliasMap.class.getDeclaredMethod("register", String.class, ArrayList.class);
        register.setAccessible(true);
        register.invoke(TilePropertyAliasMap.instance, "FloorMaterial", new ArrayList<>(List.of("Grass", "Water")));
    }

    /** ServerMap 替身：IsoCell.getGridSquare 在伺服器上轉給 ServerMap.instance。 */
    public static class TestServerMap extends ServerMap {
        static final Map<Long, IsoGridSquare> SQUARES = new HashMap<>();

        @Override
        public IsoGridSquare getGridSquare(int x, int y, int z) {
            return SQUARES.get(((long) x << 32) | (y & 0xffffffffL));
        }
    }

    private static boolean throwsNpe(Runnable r) {
        try {
            r.run();
            return false;
        } catch (NullPointerException e) {
            return true;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 往父類找
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "spg pass  " : "spg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalSpawnGuardTest() {}
}
