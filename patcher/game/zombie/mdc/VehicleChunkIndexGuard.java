package zombie.mdc;

import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.vehicles.BaseVehicle;

/**
 * 保證 vehicles.db header 的 (wx,wy) 與同一份 VehicleBuffer (x,y) snapshot 一致。
 *
 * <p>Vanilla VehicleBuffer.set() 從 vehicle.chunk 取 wx/wy、從 vehicle 取 x/y；
 * chunk 被 pool reset/reuse 而 vehicle 仍持有參考時，會寫出 0,0 或其他任意 chunk。
 * enabled 模式只使用已捕捉進 buffer 的 primitive；kill switch 回傳同一 snapshot 的 vanilla 值。
 */
public final class VehicleChunkIndexGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.vehicleChunkIndexGuard"));
    private static long writes;
    private static long correctedAxes;
    private static long invalidAxes;

    public static int wx(BaseVehicle vehicle, float capturedX, int vanillaWx) {
        writes++;
        return repair(vehicle, "x", capturedX, vanillaWx);
    }

    public static int wy(BaseVehicle vehicle, float capturedY, int vanillaWy) {
        return repair(vehicle, "y", capturedY, vanillaWy);
    }

    public static int chunkCoord(float coordinate) {
        return PZMath.fastfloor(coordinate / 8.0F);
    }

    private static int repair(BaseVehicle vehicle, String axis, float coordinate, int vanillaChunk) {
        if (!ENABLED) {
            return vanillaChunk;
        }
        if (!Float.isFinite(coordinate)) {
            invalidAxes++;
            if (invalidAxes <= 8 || (invalidAxes & 63L) == 0L) {
                log("invalidAxes=" + invalidAxes + " axis=" + axis
                        + " sqlId=" + sqlId(vehicle) + " coordinate=" + coordinate
                        + " vanilla=" + vanillaChunk);
            }
            return vanillaChunk;
        }

        int derived = chunkCoord(coordinate);
        if (derived == vanillaChunk) {
            return vanillaChunk;
        }
        correctedAxes++;
        if (correctedAxes <= 8 || (correctedAxes & 63L) == 0L) {
            log("correctedAxes=" + correctedAxes + " writes=" + writes + " axis=" + axis
                    + " sqlId=" + sqlId(vehicle) + " vanilla=" + vanillaChunk
                    + " derived=" + derived + " coordinate=" + coordinate
                    + " chunkIdentity=" + chunkIdentity(vehicle));
        }
        return derived;
    }

    private static int sqlId(BaseVehicle vehicle) {
        return vehicle == null ? -1 : vehicle.sqlId;
    }

    private static int chunkIdentity(BaseVehicle vehicle) {
        return vehicle == null || vehicle.chunk == null ? 0 : System.identityHashCode(vehicle.chunk);
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][VehicleChunkIndex] " + message);
        } catch (RuntimeException | LinkageError ignored) {
            // 診斷不得破壞 persistence boundary。
        }
    }

    private VehicleChunkIndexGuard() {}
}
