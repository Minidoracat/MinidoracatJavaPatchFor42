package zombie.vehicles;

import java.util.Locale;

import org.joml.Vector3f;
import zombie.core.math.PZMath;
import zombie.core.physics.Transform;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.scripting.objects.VehicleScript;

/**
 * W65 本機模擬的車掉到地板下時拉回地面（client，42.21.0；docs/patches.md 2ca）。
 *
 * <p>車的物理在駕駛（或撞到它的人）的客戶端算。原生物理在一次碰撞或地面碰撞體還沒建好時可能把車推到地板下
 * （2026-10-14：低速倒車撞到另一台大卡車，原點到地板下 0.6 m；另一次換駕駛後連人掉到 −36 層）。原版
 * {@code BaseVehicle.update} 只用物理高度推算樓層、不把車放回地面；坐在車上時鏡頭高度直接取車的物理高度，
 * 畫面全黑，下車後車也陷在地下。
 *
 * <p>本方法取代 {@code BaseVehicle.update} 內唯一的 {@code this.updateTransform()}（這時原版已經從物理回寫 x、y，
 * 並把 z 設成所在樓層）：車由本機模擬（Local／LocalCollide）、是正的、物理原點比樓層地板低
 * {@link #SINK_DEPTH} 以上、那一格有地板，就用原版 {@code setWorldTransform}（原生 {@code teleportVehicle}）把車放回
 * 停放高度、速度歸零，再照原版呼叫 {@code updateTransform}。本機回報給伺服器的高度也跟著回到地面。
 * 正的車的判斷與停放高度和伺服器 W64（{@code zombie.mdc.VehicleSinkRestore}）相同；W64 另外扶正翻覆深陷的車，這裡不做。
 *
 * <p>{@code -Dmdc.vehicleSinkGuard=off}（或 {@code 0}）一律照原版。
 */
public final class MdcVehicleSinkGuard {

    static final String RAW_MODE = System.getProperty("mdc.vehicleSinkGuard");
    static final boolean ENABLED = !isOff(RAW_MODE);
    /** 一層樓的物理高度（原版 BaseVehicle.update 與 load 的 2.44949f）。 */
    static final float LEVEL_HEIGHT = 2.44949F;
    /** 物理原點比地板低多少才算掉下去。車頭著地的拖車約 0.1 m。 */
    static final float SINK_DEPTH = 0.25F;
    /** 每次啟動最多逐筆記錄的次數，之後只計數。 */
    private static final int LOG_LIMIT = 20;

    /** 本次啟動拉回地面的次數（只在主執行緒更新）。 */
    static int lifted;
    private static boolean failureLogged;

    static {
        log("active=" + ENABLED + (RAW_MODE == null ? "" : " (-Dmdc.vehicleSinkGuard=" + RAW_MODE + ")"));
    }

    private MdcVehicleSinkGuard() {}

    static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        return mode.equals("off") || mode.equals("0");
    }

    /** 取代 BaseVehicle.update 內的 {@code this.updateTransform()}。 */
    public static void updateTransform(BaseVehicle vehicle) {
        if (ENABLED && !GameServer.server) {
            try {
                float target = target(vehicle);
                if (!Float.isNaN(target)) {
                    place(vehicle, target);
                }
            } catch (RuntimeException e) {
                if (!failureLogged) {
                    failureLogged = true;
                    log("check failed (" + e + "); vanilla");
                }
            }
        }
        vehicle.updateTransform();
    }

    /** 本機模擬、是正的、原點在地板下而且那一格有地板時回停放高度；其餘回 NaN（不動）。 */
    static float target(BaseVehicle vehicle) {
        VehicleScript script = vehicle.getScript();
        if (script == null || !vehicle.isLocalPhysicSim() || vehicle.getUpVectorDot() < BaseVehicle.MINIMUM_DOT_UPRIGHT) {
            return Float.NaN;
        }
        int level = PZMath.fastfloor(vehicle.getZ());
        float floorY = level * LEVEL_HEIGHT;
        if (!(vehicle.jniTransform.origin.y < floorY - SINK_DEPTH)) {
            return Float.NaN;
        }
        IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(
                PZMath.fastfloor(vehicle.getX()), PZMath.fastfloor(vehicle.getY()), level);
        if (square == null || !square.hasFloor()) {
            return Float.NaN;
        }
        return floorY + Math.max(restHeight(script), 0.1F);
    }

    /** 用原版 setWorldTransform 把車放到 target 高度（x、y 與方向不變）、速度歸零。 */
    static void place(BaseVehicle vehicle, float target) {
        float physicsY = vehicle.jniTransform.origin.y;
        Transform t = BaseVehicle.allocTransform();
        vehicle.getWorldTransform(t);
        t.origin.y = target;
        vehicle.setWorldTransform(t);
        BaseVehicle.releaseTransform(t);
        vehicle.jniLinearVelocity.set(0.0F, 0.0F, 0.0F);
        lifted++;
        if (lifted <= LOG_LIMIT) {
            log("vehicle " + vehicle.vehicleId + " " + vehicle.getScriptName() + " at " + vehicle.getX() + "," + vehicle.getY()
                    + " (" + vehicle.netPlayerAuthorization + "): physics height " + physicsY + " is below the floor of level "
                    + PZMath.fastfloor(vehicle.getZ()) + "; lifted to " + target
                    + " (#" + lifted + (lifted == LOG_LIMIT ? ", further events are not logged" : "") + ")");
        }
    }

    /** 車停在樓層地板上時物理原點比地板高多少：與原版 CarController 建構子生成車輛時相同。 */
    static float restHeight(VehicleScript script) {
        float wheelBottom = 0.0F;
        if (script.getWheelCount() > 0) {
            Vector3f modelOffset = script.getModelOffset();
            wheelBottom += modelOffset == null ? 0.0F : modelOffset.y();
            wheelBottom += script.getWheel(0).getOffset().y() - script.getWheel(0).radius;
        }
        float chassisBottom = script.getCenterOfMassOffset().y() - script.getExtents().y() / 2.0F;
        float rest = -Math.min(wheelBottom, chassisBottom);
        return script.getWheelCount() == 0 ? Math.max(rest, 0.1F) : rest;
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][VehicleSinkGuard] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
