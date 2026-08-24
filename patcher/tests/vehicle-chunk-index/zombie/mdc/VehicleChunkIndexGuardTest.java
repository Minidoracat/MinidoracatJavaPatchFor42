package zombie.mdc;

import java.lang.reflect.Constructor;
import zombie.iso.IsoChunk;
import zombie.vehicles.BaseVehicle;

/** VehicleChunkIndexGuard 的 captured snapshot、座標推導與 kill switch 行為。 */
public final class VehicleChunkIndexGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        boolean wantEnabled = args.length == 0 || !"off".equals(args[0]);
        var field = VehicleChunkIndexGuard.class.getDeclaredField("ENABLED");
        field.setAccessible(true);
        boolean enabled = field.getBoolean(null);
        expect("property 與測試模式一致", enabled == wantEnabled);

        expect("0 屬於 chunk 0", VehicleChunkIndexGuard.chunkCoord(0.0F) == 0);
        expect("7.999 屬於 chunk 0", VehicleChunkIndexGuard.chunkCoord(7.999F) == 0);
        expect("8 屬於 chunk 1", VehicleChunkIndexGuard.chunkCoord(8.0F) == 1);
        expect("負座標向下取整", VehicleChunkIndexGuard.chunkCoord(-0.1F) == -1);

        BaseVehicle vehicle = (BaseVehicle) rawInstance(BaseVehicle.class);
        vehicle.sqlId = 152;
        vehicle.chunk = (IsoChunk) rawInstance(IsoChunk.class);
        vehicle.chunk.wx = 1168;
        vehicle.chunk.wy = 969;

        int wx = VehicleChunkIndexGuard.wx(vehicle, 9432.8359375F, 1168);
        int wy = VehicleChunkIndexGuard.wy(vehicle, 11207.0537109375F, 969);
        if (enabled) {
            expect("enabled：wx 由 captured x 推導", wx == 1179);
            expect("enabled：wy 由 captured y 推導", wy == 1400);
        } else {
            expect("off：wx 回傳 captured vanilla 值", wx == 1168);
            expect("off：wy 回傳 captured vanilla 值", wy == 969);
        }

        expect("NaN 回退 captured vanilla", VehicleChunkIndexGuard.wx(vehicle, Float.NaN, 77) == 77);
        expect("+Infinity 回退 captured vanilla",
                VehicleChunkIndexGuard.wy(vehicle, Float.POSITIVE_INFINITY, 88) == 88);
        vehicle.chunk = null;
        int nullChunk = VehicleChunkIndexGuard.wx(vehicle, 16.1F, 0);
        expect("helper 不解參考 chunk；enabled 推導、off 回 captured vanilla",
                nullChunk == (enabled ? 2 : 0));

        if (failed != 0) {
            System.out.println("vehicle-chunk-index FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("vehicle-chunk-index OK enabled=" + enabled);
    }

    /** 以 serialization 建構子分配未初始化實例，避開世界／SandboxOptions 依賴。 */
    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "vci pass  " : "vci FAIL  ") + what);
        if (!ok) failed++;
    }

    private VehicleChunkIndexGuardTest() {}
}
