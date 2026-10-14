package zombie.mdc;

import java.lang.reflect.Field;

import zombie.core.physics.Transform;
import zombie.core.physics.WorldSimulation;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * W61 車輛被夾回過期 chunk 的修補行為驗證（裸 JVM）。
 *
 * <p>查表是真的（IsoCell.getChunk → IsoChunkMap.getChunk），chunk 是真 IsoChunk，放車用真
 * BaseVehicle.setWorldTransform；IsoWorld、IsoCell、IsoChunkMap、BaseVehicle 以 Unsafe 配置後補欄位。
 * GameServer.server 設 true，setWorldTransform 就不呼叫原生的 Bullet.teleportVehicle，只寫 jniTransform。
 * 每個情境先照原版 update() 夾回區塊（SmokeCheck 鎖住它只看 refs、只用 wx、wy 與 5.0E-4f）把 x、y、z 與 t1
 * 準備好，再交給 helper。
 *
 * <p>ChunkMap 涵蓋 chunk 240..252 × 372..384。車的物理位置 (2001.6, 3004.0) 在 chunk (250,375)，這塊沒載入（黑邊）；
 * 車剛離開的 (249,375) 與 (248,375) 已載入。過期 chunk＝回收後被重用到 (242,382) 的物件：已載入、refs 非空，
 * 車不在它的清單裡。
 *
 * <p>參數 {@code on}：出貨組態；{@code off}：以 {@code -Dmdc.vehicleClampGuard=off} 啟動，必須與原版相同。
 */
public final class VehicleClampGuardBehaviorTest {

    static final float OFFSET_X = 1900.0F;
    static final float OFFSET_Y = 2900.0F;
    static final float PX = 2001.6F;
    static final float PY = 3004.0F;

    static IsoChunkMap map;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        int failed = 0;
        world();
        IsoChunk came = chunk(249, 375);
        chunk(248, 375);
        IsoChunk recycled = chunk(242, 382);
        IsoChunk ahead = chunk(251, 376);
        GameServer.server = true;

        BaseVehicle car = vehicle(PX, PY, recycled);
        Transform target = vanillaClamp(car);
        float[] vanilla = {car.getX(), car.getY()};
        failed += check("原版負對照：夾回目標是過期 chunk (242,382) 的角落，離車 77 格",
                near(vanilla[0], 1943.9995F) && near(vanilla[1], 3056.0005F) && distance(vanilla, PX, PY) > 70.0F);
        VehicleClampGuard.setWorldTransform(car, target);
        float[] after = position(car);

        if (off) {
            failed += check("kill switch off：ENABLED=false，車照原版被放到過期 chunk 的角落（瞬移 77 格）",
                    !VehicleClampGuard.ENABLED && near(after, vanilla)
                    && VehicleClampGuard.rerouted + VehicleClampGuard.held == 0);
        } else {
            failed += check("過期且遠：改夾進最近的已載入 chunk (249,375)，只往回 1.6 格；x、y 與物理位置一致，高度 0.2",
                    VehicleClampGuard.ENABLED && near(after, 1999.9995F, PY) && consistent(car)
                    && VehicleClampGuard.rerouted == 1 && VehicleClampGuard.held == 0);

            BaseVehicle listed = vehicle(PX, PY, came);
            came.vehicles.add(listed);
            Transform listedTarget = vanillaClamp(listed);
            float[] listedVanilla = {listedTarget.origin.x + OFFSET_X, listedTarget.origin.z + OFFSET_Y};
            VehicleClampGuard.setWorldTransform(listed, listedTarget);
            failed += check("剛離開的 chunk（已載入、在清單裡、就在旁邊）：照原版夾回，結果與原版相同",
                    near(position(listed), listedVanilla) && near(position(listed), 1999.9995F, PY)
                    && VehicleClampGuard.rerouted == 1 && VehicleClampGuard.held == 0);

            BaseVehicle unlisted = vehicle(PX, PY, ahead);
            VehicleClampGuard.setWorldTransform(unlisted, vanillaClamp(unlisted));
            failed += check("旁邊已載入但車不在清單裡（回收物件重用在附近）：改夾進離車最近的 (249,375)",
                    near(position(unlisted), 1999.9995F, PY) && consistent(unlisted)
                    && VehicleClampGuard.rerouted == 2 && VehicleClampGuard.held == 0);

            BaseVehicle stranded = vehicle(2101.0F, PY, recycled);
            VehicleClampGuard.setWorldTransform(stranded, vanillaClamp(stranded));
            failed += check("周圍 2 格內都沒有已載入的 chunk：留在原地（物理位置不變、高度 0.2），不瞬移",
                    near(position(stranded), 2101.0F, PY) && consistent(stranded)
                    && VehicleClampGuard.rerouted == 2 && VehicleClampGuard.held == 1);
        }

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("VehicleClampGuard 行為驗證全數通過（" + (off ? "off" : "on") + "）");
    }

    /** 照原版 update() 夾回區塊（javap offset 1580–1772）：夾進 this.chunk、z＝0.2，t1 取現在的旋轉。 */
    static Transform vanillaClamp(BaseVehicle car) {
        IsoChunk stored = car.chunk;
        int minX = stored.wx * 8;
        int minY = stored.wy * 8;
        int maxX = minX + 8;
        int maxY = minY + 8;
        car.setX(Math.max(car.getX(), minX + 5.0E-4F));
        car.setX(Math.min(car.getX(), maxX - 5.0E-4F));
        car.setY(Math.max(car.getY(), minY + 5.0E-4F));
        car.setY(Math.min(car.getY(), maxY - 5.0E-4F));
        car.setZ(0.2F);
        Transform current = new Transform();
        car.getWorldTransform(current);
        Transform target = new Transform();
        target.basis.set(current.basis);
        target.origin.set(car.getX() - WorldSimulation.instance.offsetX, car.getZ(),
                car.getY() - WorldSimulation.instance.offsetY);
        return target;
    }

    /** 進入夾回區塊前的車：x、y＝物理位置（原版先從 jniTransform 回寫）、z＝0、chunk 指著 stored。 */
    static BaseVehicle vehicle(float x, float y, IsoChunk stored) throws Exception {
        BaseVehicle car = TreeRoomGuardBehaviorTest.alloc(BaseVehicle.class);
        TreeRoomGuardBehaviorTest.set(BaseVehicle.class, car, "jniTransform", new Transform());
        car.jniTransform.origin.set(x - OFFSET_X, 0.35F, y - OFFSET_Y);
        car.chunk = stored;
        car.setX(x);
        car.setY(y);
        car.setZ(0.0F);
        return car;
    }

    static void world() throws Exception {
        IsoCell cell = TreeRoomGuardBehaviorTest.alloc(IsoCell.class);
        IsoWorld world = TreeRoomGuardBehaviorTest.alloc(IsoWorld.class);
        world.currentCell = cell;
        IsoWorld.instance = world;
        map = TreeRoomGuardBehaviorTest.alloc(IsoChunkMap.class);
        int slots = IsoChunkMap.chunkGridWidth * IsoChunkMap.chunkGridWidth;
        TreeRoomGuardBehaviorTest.set(IsoChunkMap.class, map, "chunksSwapA", new IsoChunk[slots]);
        TreeRoomGuardBehaviorTest.set(IsoChunkMap.class, map, "chunksSwapB", new IsoChunk[slots]);
        map.worldX = 246;
        map.worldY = 378;
        IsoChunkMap[] maps = new IsoChunkMap[4];
        maps[0] = map;
        TreeRoomGuardBehaviorTest.set(IsoCell.class, cell, "chunkMap", maps);
        WorldSimulation.instance.offsetX = OFFSET_X;
        WorldSimulation.instance.offsetY = OFFSET_Y;
    }

    /** 已載入、放進 ChunkMap 的 chunk（兩個交換緩衝都放，與讀哪一個無關）。 */
    static IsoChunk chunk(int wx, int wy) throws Exception {
        IsoChunk chunk = new IsoChunk(IsoWorld.instance.currentCell);
        chunk.wx = wx;
        chunk.wy = wy;
        chunk.loaded = true;
        chunk.refs.add(map);
        int slot = IsoChunkMap.chunkGridWidth * (wy - map.getWorldYMin()) + (wx - map.getWorldXMin());
        for (String name : new String[] {"chunksSwapA", "chunksSwapB"}) {
            Field f = IsoChunkMap.class.getDeclaredField(name);
            f.setAccessible(true);
            ((IsoChunk[]) f.get(map))[slot] = chunk;
        }
        return chunk;
    }

    static float[] position(BaseVehicle car) {
        return new float[] {car.jniTransform.origin.x + OFFSET_X, car.jniTransform.origin.z + OFFSET_Y};
    }

    /** IsoMovingObject 的 x、y 與物理位置一致，高度是原版夾回的 0.2。 */
    static boolean consistent(BaseVehicle car) {
        return near(position(car), car.getX(), car.getY()) && car.getZ() == 0.2F && car.jniTransform.origin.y == 0.2F;
    }

    static float distance(float[] p, float x, float y) {
        return (float) Math.hypot(p[0] - x, p[1] - y);
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0E-3F;
    }

    static boolean near(float[] p, float x, float y) {
        return near(p[0], x) && near(p[1], y);
    }

    static boolean near(float[] p, float[] q) {
        return near(p, q[0], q[1]);
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "behavior OK   " : "behavior FAIL ") + what);
        return ok ? 0 : 1;
    }

    private VehicleClampGuardBehaviorTest() {}
}
