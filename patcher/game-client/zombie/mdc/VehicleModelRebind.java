package zombie.mdc;

import zombie.debug.DebugLog;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleParts;

/**
 * W108 車在 meta 期間收養零件後，門與引擎蓋的模型還綁著舊零件（client 單機；docs/patches.md 2dp）。
 *
 * <p>引擎運轉（或警報、警笛）時下車走遠，車進 meta 改由 {@code VirtualVehicle} 模擬；走回來時 chunk 重新載入一台新的
 * {@code BaseVehicle}，{@code VirtualVehicle.removeFromMeta} 呼叫 {@code adoptParts} 把 meta 期間的零件換給它。新車的
 * {@code models} 在收養前就照自己的零件建好，{@code ModelInfo.part} 仍指向被換掉的零件：開關門、引擎蓋的動畫與
 * {@code playPartAnim} 都找不到現在的零件，模型停在錯的姿勢、打不開。收養之後把每個 {@code ModelInfo.part} 改指
 * 同一個 id 的現在零件（不增刪 {@code models}、不動模型實例）；沒收養時查到的就是原本的零件，一個都不換。
 *
 * <p>{@code -Dmdc.vehicleModelRebind=off}（或 {@code 0}）委派原版。
 */
public final class VehicleModelRebind {

    static final String RAW_MODE = System.getProperty("mdc.vehicleModelRebind");
    static final boolean ENABLED = !VehicleClampGuard.isOff(RAW_MODE);
    /** 改指現在零件的 {@code ModelInfo} 累計數。 */
    static int rebound;

    private VehicleModelRebind() {}

    /** 取代 {@code vehicle.adoptParts(parts)}（VirtualVehicle.removeFromMeta offset 17，全 jar 唯一）。 */
    public static void adoptParts(BaseVehicle vehicle, VehicleParts parts) {
        vehicle.adoptParts(parts);
        if (!ENABLED) {
            return;
        }
        int count = 0;
        try {
            for (BaseVehicle.ModelInfo info : vehicle.models) {
                if (info.part == null) {
                    continue;
                }
                VehiclePart current = vehicle.getPartById(info.part.getId());
                if (current != null && current != info.part) {
                    info.part = current;
                    count++;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 原版收養已完成；重綁做不完就照原版繼續（catch 範圍照 BloodSplatIndex.log）
            log("rebind failed, keeping vanilla state: " + e);
            return;
        }
        if (count > 0 && rebound == 0) {
            log("rebound " + count + " part model(s) of " + vehicle.getScriptName() + " to the parts adopted from meta (thread "
                    + Thread.currentThread().getName() + ")");
        }
        rebound += count;
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][VehicleModelRebind] " + message);
        } catch (RuntimeException | LinkageError ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
