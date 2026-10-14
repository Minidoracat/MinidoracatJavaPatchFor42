package zombie.mdc;

import zombie.core.physics.Transform;
import zombie.core.physics.WorldSimulation;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * W61 車輛被夾回過期 chunk 的瞬移修補（client，42.21.0；docs/patches.md 2by）。
 *
 * <p>原版 {@code BaseVehicle.update} 在車的位置沒有格子時（客戶端還沒載入那一塊，畫面上的黑邊），把座標夾進
 * {@code this.chunk} 的範圍（離邊 0.0005 格），高度設 0.2，再用 {@code setWorldTransform} 把車放過去（速度歸零）。
 * 條件只看 {@code !this.chunk.refs.isEmpty()}。但客戶端卸載 chunk 時不會清掉車上的 {@code chunk}，
 * {@code IsoChunk.resetForStore()} 回收後物件會被重用到別的座標，{@code IsoChunkMap.LoadChunk} 一排入載入就
 * {@code refs.add}。車還指著這種物件時，原版把車夾到新座標的角落，玩家看到車被傳送到幾十到幾百格外。
 *
 * <p>本方法取代夾回區塊內唯一的 {@code this.setWorldTransform(t1)}。呼叫時原版已經把 x、y 改成夾回值、
 * z 設成 0.2，{@code jniTransform.origin} 仍是車真正的物理位置。原版要夾進的 chunk 已載入、車在它的
 * vehicles 清單裡、離車所在的 chunk 不超過 {@link #NEAR_CHUNKS} 格時照原版；否則改夾進車周圍
 * {@link #SEARCH_CHUNKS} 格內最近的已載入 chunk；都沒有就把車留在原地（同樣高度 0.2、速度歸零），等那一塊載入。
 * 之後原版依新的 current 把 {@code this.chunk} 換成車真正所在的 chunk。
 *
 * <p>{@code -Dmdc.vehicleClampGuard=off}（或 {@code 0}）一律照原版。
 */
public final class VehicleClampGuard {

    static final String RAW_MODE = System.getProperty("mdc.vehicleClampGuard");
    static final boolean ENABLED = !isOff(RAW_MODE);
    /** 原版夾回的離邊距離（javap ldc 5.0E-4f；SmokeCheck 與原版連動）。 */
    static final float EDGE = 5.0E-4F;
    /** 原版要夾進的 chunk 與車所在 chunk 的距離上限（以 chunk 計的切比雪夫距離），超過就當成過期。 */
    static final int NEAR_CHUNKS = 2;
    /** 找替代 chunk 的半徑（chunk）。 */
    static final int SEARCH_CHUNKS = 2;
    /** 每次啟動最多逐筆記錄的介入次數，之後只計數。 */
    private static final int LOG_LIMIT = 20;

    /** 本次啟動改夾進附近 chunk 的次數（只在主執行緒更新）。 */
    static int rerouted;
    /** 本次啟動附近沒有已載入 chunk、把車留在原地的次數。 */
    static int held;
    private static boolean failureLogged;

    static {
        log("active=" + ENABLED + (RAW_MODE == null ? "" : " (-Dmdc.vehicleClampGuard=" + RAW_MODE + ")"));
    }

    private VehicleClampGuard() {}

    static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String mode = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return mode.equals("off") || mode.equals("0");
    }

    /** 取代 BaseVehicle.update 夾回區塊內的 {@code this.setWorldTransform(t1)}。 */
    public static void setWorldTransform(BaseVehicle vehicle, Transform target) {
        if (ENABLED) {
            try {
                redirect(vehicle, target);
            } catch (RuntimeException e) {
                if (!failureLogged) {
                    failureLogged = true;
                    log("check failed (" + e + "); vanilla clamp");
                }
            }
        }
        vehicle.setWorldTransform(target);
    }

    /** 原版要夾進的 chunk 不能用時，改寫 x、y 與 target：改放到附近已載入的 chunk，或留在原地。 */
    static void redirect(BaseVehicle vehicle, Transform target) {
        IsoChunk stored = vehicle.chunk;
        float offsetX = WorldSimulation.instance.offsetX;
        float offsetY = WorldSimulation.instance.offsetY;
        float px = vehicle.jniTransform.origin.x + offsetX;
        float py = vehicle.jniTransform.origin.z + offsetY;
        int cwx = chunkIndex(px);
        int cwy = chunkIndex(py);
        if (usable(stored, vehicle, cwx, cwy)) {
            return;
        }
        IsoChunk replacement = nearestLoaded(px, py, cwx, cwy);
        float x = replacement == null ? px : clamp(px, replacement.wx);
        float y = replacement == null ? py : clamp(py, replacement.wy);
        if (replacement == null) {
            held++;
        } else {
            rerouted++;
        }
        report(vehicle, stored, target.origin.x + offsetX, target.origin.z + offsetY, px, py, cwx, cwy, replacement, x, y);
        vehicle.setX(x);
        vehicle.setY(y);
        target.origin.x = x - offsetX;
        target.origin.z = y - offsetY;
    }

    /** 原版要夾進的 chunk 確實是車剛離開的那一塊：已載入、車在它的清單裡、就在車旁邊。 */
    static boolean usable(IsoChunk stored, BaseVehicle vehicle, int cwx, int cwy) {
        return stored != null
                && stored.loaded
                && distance(stored, cwx, cwy) <= NEAR_CHUNKS
                && stored.vehicles.contains(vehicle);
    }

    /** 車周圍 SEARCH_CHUNKS 格內、離車最近的已載入 chunk（ChunkMap 位置與座標一致的才算）；沒有回 null。 */
    static IsoChunk nearestLoaded(float px, float py, int cwx, int cwy) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) {
            return null;
        }
        IsoChunk best = null;
        float bestDist = Float.MAX_VALUE;
        for (int wy = cwy - SEARCH_CHUNKS; wy <= cwy + SEARCH_CHUNKS; wy++) {
            for (int wx = cwx - SEARCH_CHUNKS; wx <= cwx + SEARCH_CHUNKS; wx++) {
                IsoChunk chunk = cell.getChunk(wx, wy);
                if (chunk == null || !chunk.loaded || chunk.wx != wx || chunk.wy != wy) {
                    continue;
                }
                float dx = Math.max(Math.max(wx * 8 - px, 0.0F), px - (wx * 8 + 8));
                float dy = Math.max(Math.max(wy * 8 - py, 0.0F), py - (wy * 8 + 8));
                float dist = dx * dx + dy * dy;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = chunk;
                }
            }
        }
        return best;
    }

    /** 與原版相同的夾法：先 max(座標, 下界＋EDGE)，再 min(…, 上界－EDGE)。 */
    static float clamp(float value, int chunkIndex) {
        int min = chunkIndex * 8;
        return Math.min(Math.max(value, (float) min + EDGE), (float) (min + 8) - EDGE);
    }

    static int chunkIndex(float coordinate) {
        return (int) Math.floor(coordinate / 8.0F);
    }

    private static int distance(IsoChunk chunk, int cwx, int cwy) {
        return Math.max(Math.abs(chunk.wx - cwx), Math.abs(chunk.wy - cwy));
    }

    private static void report(BaseVehicle vehicle, IsoChunk stored, float vanillaX, float vanillaY, float px, float py,
            int cwx, int cwy, IsoChunk replacement, float x, float y) {
        int n = rerouted + held;
        if (n > LOG_LIMIT) {
            return;
        }
        String storedText = stored == null ? "null" : stored.wx + "," + stored.wy + " (loaded=" + stored.loaded
                + ", listed=" + stored.vehicles.contains(vehicle) + ", " + distance(stored, cwx, cwy) + " chunks away)";
        log("vehicle " + vehicle.vehicleId + " " + vehicle.getScriptName() + " at " + px + "," + py
                + ": vanilla would clamp it into chunk " + storedText + " at " + vanillaX + "," + vanillaY
                + (replacement == null
                        ? "; no loaded chunk within " + SEARCH_CHUNKS + ", held in place"
                        : "; clamped into loaded chunk " + replacement.wx + "," + replacement.wy + " at " + x + "," + y)
                + " (#" + n + (n == LOG_LIMIT ? ", further events are not logged" : "") + ")");
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][VehicleClampGuard] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
