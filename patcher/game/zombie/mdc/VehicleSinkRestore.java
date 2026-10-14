package zombie.mdc;

import java.util.Locale;

import org.joml.Vector3f;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * W64 卡進地下的車交回伺服器時抬回地面（server，42.21.0；docs/patches.md 2ca）。
 *
 * <p>車的物理只在駕駛（或撞到它的人）的客戶端算，伺服器在 {@code VehiclePhysicsPacket.processServer}
 * 照單全收客戶端回報的高度，也不會自己修正。客戶端物理把車推到地板下時（2026-10-14：低速倒車撞到另一台大卡車），
 * 這個高度就存進伺服器，之後每個客戶端載入這台車都拿到同一個高度，重登救不回來。原版只有重新從資料庫載入
 * （{@code BaseVehicle.load}）時才把高度夾回所在樓層。
 *
 * <p>本方法掛在 {@code BaseVehicle.setNetPlayerAuthorization} 頭部（slots 0、1），授權從別人交回伺服器
 * （駕駛下車、換到乘客座、斷線、碰撞歸還）時檢查一次：車是正的（{@code getUpVectorDot} 至少
 * {@code BaseVehicle.MINIMUM_DOT_UPRIGHT}）、物理原點比所在樓層的地板低 {@link #SINK_DEPTH} 以上、而且那一格有地板，
 * 就把高度設回停放高度、速度歸零，並要求下一輪送完整更新，讓所有客戶端把車移回地面。判斷只看地板：原版 load
 * 也把存檔高度夾在地板以上，原點在地板下的車坐進去畫面就全黑；車頭著地的拖車原點約在地板下 0.1 m，照原版。
 * 翻覆的車不動。停放高度的算法與原版生成車輛（{@code CarController} 建構子）相同，樓層判斷與原版
 * {@code BaseVehicle.update} 相同。
 *
 * <p>{@code -Dmdc.vehicleSinkRestore=off}（或 {@code 0}）一律照原版。只在主執行緒（封包處理）呼叫；
 * RuntimeException 只記一次，不擋原版。
 */
public final class VehicleSinkRestore {

    static final String RAW_MODE = System.getProperty("mdc.vehicleSinkRestore");
    static final boolean ENABLED = !isOff(RAW_MODE);
    /** 一層樓的物理高度（原版 BaseVehicle.update 與 load 的 2.44949f）。 */
    static final float LEVEL_HEIGHT = 2.44949F;
    /** 物理原點比地板低多少才算卡進地下。車頭著地的拖車約 0.1 m（2026-10-14 vehicles.db 7,574 台最深 0.109）。 */
    static final float SINK_DEPTH = 0.25F;
    /** 每次啟動最多逐筆記錄的次數，之後只計數。 */
    private static final int LOG_LIMIT = 50;

    /** 本次啟動抬回地面的次數。 */
    static int restored;
    /** 卡在地下但那一格沒有地板、照原版不動的次數。 */
    static int noFloor;
    private static boolean failureLogged;

    static {
        log("active=" + ENABLED + (RAW_MODE == null ? "" : " (-Dmdc.vehicleSinkRestore=" + RAW_MODE + ")"));
    }

    private VehicleSinkRestore() {}

    static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        return mode.equals("off") || mode.equals("0");
    }

    /** {@code BaseVehicle.setNetPlayerAuthorization} 頭部 headCall（slots 0、1）；這時欄位還是舊的授權。 */
    public static void onSetAuthorization(BaseVehicle vehicle, BaseVehicle.Authorization next) {
        if (!ENABLED || GameClient.client || next != BaseVehicle.Authorization.Server
                || vehicle.netPlayerAuthorization == BaseVehicle.Authorization.Server) {
            return;
        }
        try {
            restore(vehicle);
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                log("check failed (" + e + "); vanilla");
            }
        }
    }

    /** 車是正的、原點在地板下而且那一格有地板時抬回停放高度、回 true；其餘什麼都不改、回 false。 */
    static boolean restore(BaseVehicle vehicle) {
        VehicleScript script = vehicle.getScript();
        if (script == null || vehicle.getUpVectorDot() < BaseVehicle.MINIMUM_DOT_UPRIGHT) {
            return false;
        }
        float physicsY = vehicle.jniTransform.origin.y;
        IsoCell cell = IsoWorld.instance.currentCell;
        int level = level(cell, vehicle.getX(), vehicle.getY(), physicsY);
        float floorY = level * LEVEL_HEIGHT;
        if (!(physicsY < floorY - SINK_DEPTH)) {
            return false;
        }
        IsoGridSquare square = cell.getGridSquare(PZMath.fastfloor(vehicle.getX()), PZMath.fastfloor(vehicle.getY()), level);
        if (square == null || !square.hasFloor()) {
            noFloor++;
            report(vehicle, physicsY, level, Float.NaN);
            return false;
        }
        float lifted = floorY + Math.max(restHeight(script), 0.1F);
        vehicle.jniTransform.origin.y = lifted;
        vehicle.jniLinearVelocity.set(0.0F, 0.0F, 0.0F);
        vehicle.updateFlags = (short) (vehicle.updateFlags | BaseVehicle.UpdateFlags.Full);
        restored++;
        report(vehicle, physicsY, level, lifted);
        return true;
    }

    /** 原版 BaseVehicle.update 的樓層判斷：zi＝floor(y / 2.44949 + 0.05)，那一格或下一格有地板才用 zi，否則 0。 */
    static int level(IsoCell cell, float x, float y, float physicsY) {
        int zi = PZMath.fastfloor(physicsY / LEVEL_HEIGHT + 0.05F);
        int sx = PZMath.fastfloor(x);
        int sy = PZMath.fastfloor(y);
        IsoGridSquare square = cell.getGridSquare(sx, sy, zi);
        IsoGridSquare below = cell.getGridSquare(sx, sy, zi - 1);
        return square != null && (square.getFloor() != null || below != null && below.getFloor() != null) ? zi : 0;
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

    private static void report(BaseVehicle vehicle, float physicsY, int level, float lifted) {
        int n = restored + noFloor;
        if (n > LOG_LIMIT) {
            return;
        }
        log("vehicle " + vehicle.vehicleId + " " + vehicle.getScriptName() + " at " + vehicle.getX() + "," + vehicle.getY()
                + " handed back to the server (" + vehicle.netPlayerAuthorization + " pid=" + vehicle.netPlayerId
                + "): physics height " + physicsY + " is " + (level * LEVEL_HEIGHT - physicsY)
                + " m below the floor of level " + level
                + (Float.isNaN(lifted)
                        ? "; no floor on that square, left as is"
                        : "; lifted to " + lifted + " and sent a full update")
                + " (#" + n + (n == LOG_LIMIT ? ", further events are not logged" : "") + ")");
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][VehicleSinkRestore] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
