package zombie.vehicles;

import java.lang.reflect.Field;
import java.util.ArrayList;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import zombie.core.physics.Transform;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameServer;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle.Authorization;

/**
 * W65 本機模擬的車掉到地板下時拉回地面：行為驗證（裸 JVM）。
 *
 * <p>車的腳本照 2026-10-14 卡進地下的 '82 Oshkosh M911（停放高度 0.5666；卡住時原點 −0.599）；翻覆的情況照 2026-10-15
 * 四輪朝天掉到 −32 m 的車。查格子走真
 * IsoCell.getGridSquare → IsoChunkMap，chunk 與格子是真物件，只有車所在那一格有地板。原生的 Bullet.teleportVehicle
 * 在測試裡不能呼叫：放車（place）那段以 GameServer.server＝true 執行，setWorldTransform 就只寫 jniTransform。
 *
 * <p>參數 {@code on}：出貨組態；{@code off}：以 {@code -Dmdc.vehicleSinkGuard=off} 啟動，必須與原版相同。
 */
public final class MdcVehicleSinkGuardBehaviorTest {

    static final float X = 1995.5F;
    static final float Y = 3003.5F;
    static final float REST = -(0.7667F - 0.7333F - 0.6F);
    static final float SUNK = -0.599F;
    static final float DEEP = -32.0F;
    /** 翻覆的車的車頭方向（繞 Y 軸，弧度）。 */
    static final float YAW = 1.1F;

    private static int failed;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        world();
        VehicleScript m911 = script(0.7667F, -0.7333F, 0.6F, 0.3667F, 0.5778F);
        GameServer.server = false;
        check("啟用狀態與參數一致", MdcVehicleSinkGuard.ENABLED == !off);

        BaseVehicle sunk = vehicle(m911, SUNK, Authorization.Local);
        if (off) {
            MdcVehicleSinkGuard.updateTransform(sunk);
            BaseVehicle deepOff = flipped(m911, DEEP);
            MdcVehicleSinkGuard.updateTransform(deepOff);
            check("kill switch off：照原版只呼叫 updateTransform，車留在地板下 0.6 m、翻覆的 −32 m 也不動（原版負對照）",
                    sunk.jniTransform.origin.y == SUNK && deepOff.jniTransform.origin.y == DEEP
                            && deepOff.getUpVectorDot() < 0.0F && MdcVehicleSinkGuard.lifted == 0);
            finish(off);
            return;
        }

        check("駕駛（Local）的車原點在地板下 0.6 m：拉回停放高度 0.5666",
                near(MdcVehicleSinkGuard.target(sunk), REST));
        check("撞到別人的車（LocalCollide，本機在算它）：同樣拉回",
                near(MdcVehicleSinkGuard.target(vehicle(m911, SUNK, Authorization.LocalCollide)), REST));
        check("別人在開的車（Remote）：不動，物理由別人的客戶端算",
                Float.isNaN(MdcVehicleSinkGuard.target(vehicle(m911, SUNK, Authorization.Remote))));
        check("伺服器管的車（Server）：不動",
                Float.isNaN(MdcVehicleSinkGuard.target(vehicle(m911, SUNK, Authorization.Server))));
        check("正常行駛（0.506，懸吊壓縮）：不動",
                Float.isNaN(MdcVehicleSinkGuard.target(vehicle(m911, 0.506F, Authorization.Local))));
        check("原點在地板下 0.109 m（車頭著地的半掛拖車）：不動",
                Float.isNaN(MdcVehicleSinkGuard.target(vehicle(m911, -0.109F, Authorization.Local))));
        check("原點在地板下 0.24 m（門檻 0.25 m 內）：不動",
                Float.isNaN(MdcVehicleSinkGuard.target(vehicle(m911, -0.24F, Authorization.Local))));
        check("翻覆、原點在地板下 0.6 m（不到一層）：不動，留給玩家翻正",
                Float.isNaN(MdcVehicleSinkGuard.target(flipped(m911, SUNK))));
        check("翻覆、原點在地板下 2.4 m（一層 2.449 m 以內）：不動",
                Float.isNaN(MdcVehicleSinkGuard.target(flipped(m911, -2.4F))));
        check("翻覆、車頂著地（原點在地板上）：不動", Float.isNaN(MdcVehicleSinkGuard.target(flipped(m911, 1.0F))));
        check("翻覆掉到 −32 m（2026-10-15）：拉回停放高度", near(MdcVehicleSinkGuard.target(flipped(m911, DEEP)), REST));
        BaseVehicle flippedNoFloor = flipped(m911, DEEP);
        flippedNoFloor.setX(X + 2.0F);
        check("翻覆掉很深但那一格沒有地板：不動", Float.isNaN(MdcVehicleSinkGuard.target(flippedNoFloor)));
        BaseVehicle noFloor = vehicle(m911, SUNK, Authorization.Local);
        noFloor.setX(X + 2.0F);
        check("那一格沒有地板：不動", Float.isNaN(MdcVehicleSinkGuard.target(noFloor)));

        BaseVehicle parked = vehicle(m911, 0.506F, Authorization.Local);
        MdcVehicleSinkGuard.updateTransform(parked);
        check("沒掉下去：updateTransform 照原版，高度與速度都不變",
                parked.jniTransform.origin.y == 0.506F && parked.jniLinearVelocity.y == -2.5F && MdcVehicleSinkGuard.lifted == 0);

        GameServer.server = true;   // 以下不呼叫原生 teleportVehicle
        BaseVehicle onServer = vehicle(m911, SUNK, Authorization.Local);
        MdcVehicleSinkGuard.updateTransform(onServer);
        check("在伺服器 JVM 裡（自建伺服器也會載入 client 包）：不動", onServer.jniTransform.origin.y == SUNK);

        BaseVehicle turned = vehicle(m911, SUNK, Authorization.Local);
        turned.jniTransform.basis.rotationY(1.1F);
        turned.jniTransform.origin.x = 95.5F;
        turned.jniTransform.origin.z = 103.5F;
        Matrix3f before = new Matrix3f(turned.jniTransform.basis);
        MdcVehicleSinkGuard.place(turned, REST);
        check("放車：高度改成停放高度，水平位置與方向不變，速度歸零",
                near(turned.jniTransform.origin.y, REST) && turned.jniTransform.origin.x == 95.5F
                        && turned.jniTransform.origin.z == 103.5F && turned.jniTransform.basis.equals(before, 1.0E-6F)
                        && turned.jniLinearVelocity.length() == 0.0F && MdcVehicleSinkGuard.lifted == 1);
        BaseVehicle upsideDown = flipped(m911, DEEP);
        upsideDown.jniTransform.origin.x = 95.5F;
        upsideDown.jniTransform.origin.z = 103.5F;
        MdcVehicleSinkGuard.place(upsideDown, REST);
        Vector3f forward = upsideDown.getForwardVector(new Vector3f());
        check("放翻覆的車：扶正、車頭方向不變、高度改成停放高度，水平位置不變，速度歸零",
                upsideDown.getUpVectorDot() > 0.9999F && Math.abs(forward.x - (float) Math.sin(YAW)) < 1.0E-4F
                        && Math.abs(forward.y) < 1.0E-4F && Math.abs(forward.z - (float) Math.cos(YAW)) < 1.0E-4F
                        && near(upsideDown.jniTransform.origin.y, REST) && upsideDown.jniTransform.origin.x == 95.5F
                        && upsideDown.jniTransform.origin.z == 103.5F && upsideDown.jniLinearVelocity.length() == 0.0F
                        && MdcVehicleSinkGuard.lifted == 2 && MdcVehicleSinkGuard.righted == 1);
        finish(off);
    }

    private static void finish(boolean off) {
        System.out.println(failed == 0 ? "MdcVehicleSinkGuard 行為驗證全數通過（" + (off ? "off" : "on") + "）"
                : "MdcVehicleSinkGuard 行為驗證失敗 " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 進入 updateTransform 改道點時的車：x、y 已從物理回寫，z 是原版判出的樓層（地板下沒有地下室＝0）。 */
    static BaseVehicle vehicle(VehicleScript script, float physicsY, Authorization auth) throws Exception {
        BaseVehicle car = allocate(BaseVehicle.class);
        set(BaseVehicle.class, car, "jniTransform", new Transform());
        set(BaseVehicle.class, car, "jniLinearVelocity", new Vector3f(0.0F, -2.5F, 0.0F));
        set(BaseVehicle.class, car, "script", script);
        car.sprite = allocate(IsoSprite.class);   // modelSlot＝null：原版 updateTransform 直接結束
        car.vehicleId = 42;
        car.setX(X);
        car.setY(Y);
        car.setZ(0.0F);
        car.jniTransform.origin.set(0.0F, physicsY, 0.0F);
        car.netPlayerAuthorization = auth;
        return car;
    }

    /** 四輪朝天、車頭朝 {@link #YAW}：basis＝繞 Y 轉 YAW，再繞自身 Z（車頭軸）轉 180°。 */
    static BaseVehicle flipped(VehicleScript script, float physicsY) throws Exception {
        BaseVehicle car = vehicle(script, physicsY, Authorization.Local);
        car.jniTransform.basis.rotationY(YAW).rotateZ((float) Math.PI);
        return car;
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

        IsoChunk chunk = new IsoChunk(cell);
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

    @SuppressWarnings("unchecked")
    static VehicleScript script(float modelY, float wheelY, float radius, float comY, float height) throws Exception {
        VehicleScript script = new VehicleScript();
        VehicleScript.Model model = new VehicleScript.Model();
        model.offset.set(0.0F, modelY, 0.0F);
        ((ArrayList<VehicleScript.Model>) get(VehicleScript.class, script, "models")).add(model);
        VehicleScript.Wheel wheel = new VehicleScript.Wheel();
        wheel.offset.set(0.8889F, wheelY, 2.6667F);
        wheel.radius = radius;
        ((ArrayList<VehicleScript.Wheel>) get(VehicleScript.class, script, "wheels")).add(wheel);
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
    static <T> T allocate(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private MdcVehicleSinkGuardBehaviorTest() {}
}
