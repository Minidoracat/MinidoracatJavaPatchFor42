package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;

import org.joml.Vector3f;
import zombie.core.physics.CarController;
import zombie.core.physics.Transform;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.Authorization;
import zombie.vehicles.BaseVehicle.ServerVehicleState;

/**
 * W64：走 dist 手術後的真 {@code BaseVehicle.setNetPlayerAuthorization}，以真 {@code ServerVehicleState.shouldSend}
 * 判定下一輪是否送完整更新。
 *
 * <p>用法：{@code VehicleSinkRestoreTest on|off}（off 須配 {@code -Dmdc.vehicleSinkRestore=off}）。
 * 車的腳本照 2026-10-14 卡進地下的 '82 Oshkosh M911：模型偏移 y 0.7667、輪子偏移 y −0.7333、半徑 0.6、
 * 重心偏移 y 0.3667、車身高 0.5778，停放高度＝0.5666（伺服器 vehicles.db 另外兩台 M911 存的是 0.506）；
 * 卡住那台存的是 −0.599。翻覆的情況照 2026-10-15 四輪朝天掉到 −32 m 的車。查格子走客戶端的 chunk map 路徑
 * （GameServer.server＝false），與伺服器 ServerMap 是同一個 IsoCell.getGridSquare 呼叫。BaseVehicle、IsoWorld、IsoCell、
 * IsoChunkMap 以 Unsafe 配置後補欄位。
 */
public final class VehicleSinkRestoreTest {

    static final float X = 1995.5F;
    static final float Y = 3003.5F;
    /** M911 的輪底：模型偏移＋輪子偏移－半徑；停放高度＝−min(輪底, 車身底)＝0.5666。 */
    static final float WHEEL_BOTTOM = 0.7667F - 0.7333F - 0.6F;
    static final float SUNK = -0.599F;
    /** 2026-10-15 翻覆的車在伺服器存的物理高度。 */
    static final float DEEP = -32.0F;
    /** 翻覆的車的車頭方向（繞 Y 軸）。 */
    static final float YAW = (float) Math.toRadians(30.0);

    private static int failed;
    static IsoChunk chunk;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean on = args.length == 0 || args[0].equals("on");
        world();
        VehicleScript m911 = script(0.7667F, -0.7333F, 0.6F, 0.3667F, 0.5778F, true);
        float rest = -WHEEL_BOTTOM;
        check("啟用狀態與參數一致", VehicleSinkRestore.ENABLED == on);
        check("停放高度與原版 CarController 生成車輛的算法相同（M911＝0.5666）",
                Math.abs(VehicleSinkRestore.restHeight(m911) - rest) < 1.0E-4F);

        Fixture sunk = new Fixture(m911, SUNK, Authorization.Local, 5);
        sunk.handTo(Authorization.Server, -1);
        if (!on) {
            check("off：交回伺服器時照原版，高度留在 −0.599、不送完整更新（原版負對照）",
                    sunk.height() == SUNK && !sunk.sendsFull() && VehicleSinkRestore.restored == 0);
            Fixture deepOff = flipped(m911, DEEP);
            deepOff.handTo(Authorization.Server, -1);
            check("off：翻覆掉到 −32 m 也照原版，不扶正、不抬回（原版負對照）",
                    deepOff.height() == DEEP && deepOff.vehicle.getUpVectorDot() < 0.0F && !deepOff.sendsFull());
            finish(args);
            return;
        }
        check("駕駛下車（Local→Server）：原點在地板下 0.6 m，抬回停放高度、速度歸零、下一輪送完整更新",
                near(sunk.height(), rest) && sunk.vehicle.jniLinearVelocity.length() == 0.0F && sunk.sendsFull()
                        && VehicleSinkRestore.restored == 1);
        check("授權照原版改成 Server(-1)",
                sunk.vehicle.netPlayerAuthorization == Authorization.Server && sunk.vehicle.netPlayerId == -1);
        sunk.afterSend();
        check("送出一次後不再重送", !sunk.sendsFull());

        Fixture parked = new Fixture(m911, 0.506F, Authorization.Local, 5);
        parked.handTo(Authorization.Server, -1);
        check("正常停放（0.506，懸吊壓縮）：不動、不送完整更新", parked.height() == 0.506F && !parked.sendsFull());

        Fixture trailer = new Fixture(m911, -0.109F, Authorization.Local, 5);
        trailer.handTo(Authorization.Server, -1);
        check("原點在地板下 0.109 m（車頭著地的半掛拖車，vehicles.db 最深的正常值）：照原版不動",
                trailer.height() == -0.109F && !trailer.sendsFull());

        Fixture edge = new Fixture(m911, -0.24F, Authorization.Local, 5);
        edge.handTo(Authorization.Server, -1);
        check("原點在地板下 0.24 m（門檻 0.25 m 內）：照原版不動", edge.height() == -0.24F && !edge.sendsFull());

        VehicleScript cistern = script(1.3913F, -0.9826F, 0.4F, 1.8F, 1.2F, true);   // W900 油罐半掛拖車
        Fixture sunkTrailer = new Fixture(cistern, SUNK, Authorization.LocalCollide, 7);
        sunkTrailer.handTo(Authorization.Server, -1);
        check("停放高度算出來是負的（拖車原點在輪底下方 0.009 m）：抬到地板上 0.1 m",
                VehicleSinkRestore.restHeight(cistern) < 0.0F && near(sunkTrailer.height(), 0.1F) && sunkTrailer.sendsFull());

        Fixture collide = new Fixture(m911, SUNK, Authorization.LocalCollide, 7);
        collide.handTo(Authorization.Server, -1);
        check("碰撞歸還（LocalCollide→Server）：同樣抬回", near(collide.height(), rest) && collide.sendsFull());

        Fixture deep = new Fixture(m911, -80.0F, Authorization.Local, 5);
        deep.handTo(Authorization.Server, -1);
        check("掉到 −80 m（樓層超出範圍，原版判成 0 樓）：抬回 0 樓的停放高度", near(deep.height(), rest) && deep.sendsFull());

        Fixture toDriver = new Fixture(m911, SUNK, Authorization.Server, -1);
        toDriver.handTo(Authorization.Local, 5);
        check("交給駕駛（Server→Local）：不動，物理由駕駛的客戶端算", toDriver.height() == SUNK && !toDriver.sendsFull());

        Fixture already = new Fixture(m911, SUNK, Authorization.Server, -1);
        already.handTo(Authorization.Server, -1);
        check("本來就是 Server：不是交回，不動", already.height() == SUNK && !already.sendsFull());

        int restoredBefore = VehicleSinkRestore.restored;
        Fixture noFloor = new Fixture(m911, SUNK, Authorization.Local, 5);
        noFloor.vehicle.setX(X + 2.0F);   // 這一格沒有地板
        noFloor.handTo(Authorization.Server, -1);
        check("那一格沒有地板：照原版不動，記一次 noFloor",
                noFloor.height() == SUNK && !noFloor.sendsFull() && VehicleSinkRestore.noFloor == 1
                        && VehicleSinkRestore.restored == restoredBefore);

        Fixture flippedShallow = flipped(m911, SUNK);
        flippedShallow.handTo(Authorization.Server, -1);
        check("翻覆、原點在地板下 0.6 m（不到一層）：照原版不動，記一次 flippedLeft",
                flippedShallow.height() == SUNK && flippedShallow.vehicle.getUpVectorDot() < 0.0F && !flippedShallow.sendsFull()
                        && VehicleSinkRestore.flippedLeft == 1 && VehicleSinkRestore.restored == restoredBefore);

        Fixture flippedEdge = flipped(m911, -2.4F);
        flippedEdge.handTo(Authorization.Server, -1);
        check("翻覆、原點在地板下 2.4 m（一層 2.449 m 以內）：照原版不動", flippedEdge.height() == -2.4F
                && !flippedEdge.sendsFull() && VehicleSinkRestore.flippedLeft == 2);

        Fixture roof = flipped(m911, 1.0F);
        roof.handTo(Authorization.Server, -1);
        check("翻覆、車頂著地（原點在地板上）：照原版不動、不記錄", roof.height() == 1.0F && !roof.sendsFull()
                && VehicleSinkRestore.flippedLeft == 2);

        Fixture flippedDeep = flipped(m911, DEEP);
        flippedDeep.handTo(Authorization.Server, -1);
        Vector3f forward = flippedDeep.vehicle.getForwardVector(new Vector3f());
        check("翻覆掉到 −32 m（2026-10-15）：扶正、車頭仍朝 30°、抬回停放高度、速度歸零、下一輪送完整更新",
                flippedDeep.vehicle.getUpVectorDot() > 0.9999F
                        && Math.abs(forward.x - (float) Math.sin(YAW)) < 1.0E-4F && Math.abs(forward.y) < 1.0E-4F
                        && Math.abs(forward.z - (float) Math.cos(YAW)) < 1.0E-4F
                        && near(flippedDeep.height(), rest) && flippedDeep.vehicle.jniLinearVelocity.length() == 0.0F
                        && flippedDeep.sendsFull() && VehicleSinkRestore.righted == 1
                        && VehicleSinkRestore.restored == restoredBefore + 1);

        Fixture flippedNoFloor = flipped(m911, DEEP);
        flippedNoFloor.vehicle.setX(X + 2.0F);   // 這一格沒有地板
        flippedNoFloor.handTo(Authorization.Server, -1);
        check("翻覆掉很深但那一格沒有地板：照原版不動，記一次 noFloor",
                flippedNoFloor.height() == DEEP && flippedNoFloor.vehicle.getUpVectorDot() < 0.0F
                        && !flippedNoFloor.sendsFull() && VehicleSinkRestore.noFloor == 2 && VehicleSinkRestore.righted == 1);

        VehicleScript sled = script(0.0F, 0.0F, 0.0F, 0.2F, 0.2F, false);
        check("沒有輪子的車：停放高度照原版至少 0.1", Math.abs(VehicleSinkRestore.restHeight(sled) - 0.1F) < 1.0E-6F);

        finish(args);
    }

    private static void finish(String[] args) {
        String mode = args.length == 0 ? "on" : args[0];
        System.out.println(failed == 0 ? "VehicleSinkRestoreTest OK (" + mode + ")" : "VehicleSinkRestoreTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 四輪朝天、車頭朝 {@link #YAW}：basis＝繞 Y 轉 YAW，再繞自身 Z（車頭軸）轉 180°。 */
    private static Fixture flipped(VehicleScript script, float physicsY) throws Exception {
        Fixture fixture = new Fixture(script, physicsY, Authorization.Local, 5);
        fixture.vehicle.jniTransform.basis.rotationY(YAW).rotateZ((float) Math.PI);
        return fixture;
    }

    private static final class Fixture {
        final BaseVehicle vehicle;
        final ServerVehicleState state = new ServerVehicleState();

        Fixture(VehicleScript script, float physicsY, Authorization auth, int playerId) throws Exception {
            vehicle = allocate(BaseVehicle.class);
            set(BaseVehicle.class, vehicle, "jniTransform", new Transform());
            set(BaseVehicle.class, vehicle, "jniLinearVelocity", new Vector3f(0.0F, -2.5F, 0.0F));
            set(BaseVehicle.class, vehicle, "script", script);
            set(BaseVehicle.class, vehicle, "physics", allocate(CarController.class));
            vehicle.vehicleId = 42;
            vehicle.setX(X);
            vehicle.setY(Y);
            vehicle.jniTransform.origin.set(0.0F, physicsY, 0.0F);
            vehicle.netPlayerAuthorization = auth;
            vehicle.netPlayerId = (short) playerId;
            state.setAuthorization(vehicle);
        }

        void handTo(Authorization auth, int playerId) {
            vehicle.setNetPlayerAuthorization(auth, playerId);
        }

        float height() {
            return vehicle.jniTransform.origin.y;
        }

        boolean sendsFull() {
            return state.shouldSend(vehicle) && (state.flags & BaseVehicle.UpdateFlags.Full) != 0;
        }

        /** VehicleManager.serverUpdate 送完一輪後把 updateFlags 歸零、連線快取清旗標。 */
        void afterSend() {
            vehicle.updateFlags = 0;
            state.setAuthorization(vehicle);
            state.flags = 0;
        }
    }

    /** chunk (249,375) 涵蓋 1992..1999 × 3000..3007；只有車所在的那一格有地板。 */
    static void world() throws Exception {
        IsoCell cell = allocate(IsoCell.class);
        IsoWorld world = allocate(IsoWorld.class);
        world.currentCell = cell;
        IsoWorld.instance = world;
        IsoChunkMap map = allocate(IsoChunkMap.class);
        int slots = IsoChunkMap.chunkGridWidth * IsoChunkMap.chunkGridWidth;
        set(IsoChunkMap.class, map, "chunksSwapA", new IsoChunk[slots]);
        set(IsoChunkMap.class, map, "chunksSwapB", new IsoChunk[slots]);
        set(IsoChunkMap.class, map, "xMinTiles", -1);
        set(IsoChunkMap.class, map, "yMinTiles", -1);
        map.worldX = 246;
        map.worldY = 378;
        IsoChunkMap[] maps = new IsoChunkMap[4];
        maps[0] = map;
        set(IsoCell.class, cell, "chunkMap", maps);

        chunk = new IsoChunk(cell);
        chunk.wx = 249;
        chunk.wy = 375;
        chunk.minLevel = 0;
        chunk.maxLevel = 0;
        chunk.loaded = true;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                IsoGridSquare square = new IsoGridSquare(cell, null, 1992 + x, 3000 + y, 0);
                square.chunk = chunk;
                chunk.squares[0][y * 8 + x] = square;   // setSquare 會查 metaGrid 的建築，這裡不需要
            }
        }
        chunk.getGridSquare((int) X - 1992, (int) Y - 3000, 0).getProperties().set(IsoFlagType.solidfloor);
        int slot = IsoChunkMap.chunkGridWidth * (chunk.wy - map.getWorldYMin()) + (chunk.wx - map.getWorldXMin());
        for (String name : new String[] {"chunksSwapA", "chunksSwapB"}) {
            Field f = IsoChunkMap.class.getDeclaredField(name);
            f.setAccessible(true);
            ((IsoChunk[]) f.get(map))[slot] = chunk;
        }
    }

    /** 只填 restHeight 用到的欄位：第一個模型的偏移、第一個輪子的偏移與半徑、重心偏移、車身尺寸。 */
    @SuppressWarnings("unchecked")
    static VehicleScript script(float modelY, float wheelY, float radius, float comY, float height, boolean wheels)
            throws Exception {
        VehicleScript script = new VehicleScript();
        VehicleScript.Model model = new VehicleScript.Model();
        model.offset.set(0.0F, modelY, 0.0F);
        ((ArrayList<VehicleScript.Model>) get(VehicleScript.class, script, "models")).add(model);
        if (wheels) {
            VehicleScript.Wheel wheel = new VehicleScript.Wheel();
            wheel.offset.set(0.8889F, wheelY, 2.6667F);
            wheel.radius = radius;
            ((ArrayList<VehicleScript.Wheel>) get(VehicleScript.class, script, "wheels")).add(wheel);
        }
        script.getCenterOfMassOffset().set(0.0F, comY, 0.1111F);
        script.getExtents().set(2.1333F, height, 7.8667F);
        return script;
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0E-4F;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private VehicleSinkRestoreTest() {}
}
