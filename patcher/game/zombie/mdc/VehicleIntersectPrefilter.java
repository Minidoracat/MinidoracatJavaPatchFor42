package zombie.mdc;

import org.joml.Vector3f;

import zombie.debug.DebugType;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * 殭屍載具視線的 broad-phase 預篩（2026-08-02，dump 佔比 ~23% 的熱點）。
 *
 * 原版 IsoZombie.isVehicleBetween 對整個 cell 的每台載具都做完整 OBB 相交
 * （每台 2 次矩陣求逆＋6 次向量池借還）。本 helper 在委派前先做「線段到載具
 * 保守包圍球」的平方距離測試：球外＝幾何上不可能相交，直接回 null（呼叫端
 * 只判非 null，語意等價）；球內或任何異常＝原樣委派原版精確判定。
 *
 * 零 false-negative 設計（codex 對抗審查定案）：
 * - 半徑用 L1 上界 hx+hy+hz ≥ 半對角 L2 範數，必然偏大（寧多做精確測試、不漏判）。
 * - hx = extents.x/2 + |centerOfMassOffset.x|（與原版 getIntersectPoint 的
 *   extents/COM 計算同構），再 +1.0F 吸收 getX/getY 與 jniTransform 物理原點的
 *   次格級差異；下限 6.0F 覆蓋原版最長載具（半對角 ~3.7）。
 * - 動態 per-vehicle 半徑，超長 MOD 載具自動放大，不賭固定 margin。
 * - script null／非有限值一律委派原版（fail-open；原版此時的 NPE 行為不變）。
 *
 * 觀測：reject/delegate/anomaly 計數每 2^24 次呼叫印一行（同 LoginMetrics
 * 使用既有 Multiplayer sink），供部署後驗證 reject 率——這是 codex 設下的
 * 放寬人數上限前提之一。主執行緒單執行緒呼叫，計數不需同步。
 */
public final class VehicleIntersectPrefilter {

    private static long rejected;
    private static long delegated;
    private static long anomalies;

    /** IsoZombie.isVehicleBetween 內唯一 getIntersectPoint 呼叫點的改道目標。 */
    public static Vector3f getIntersectPoint(BaseVehicle vehicle, Vector3f start, Vector3f end, Vector3f result) {
        try {
            VehicleScript script = vehicle.getScript();
            if (script != null) {
                float bound = boundFor(script);
                float d2 = distSqPointSegment(vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        start.x, start.y, start.z, end.x, end.y, end.z);
                if (d2 > bound * bound) {   // NaN 比較恆 false → 自動落入委派
                    rejected++;
                    maybeLog();
                    return null;
                }
            }
        } catch (RuntimeException ignored) {
            anomalies++;
        }
        delegated++;
        maybeLog();
        return vehicle.getIntersectPoint(start, end, result);
    }

    /** 保守包圍半徑：L1 上界＋1.0F 膨脹，下限 6.0F；非有限值回傳 NaN（比較恆 false＝委派）。 */
    public static float boundFor(VehicleScript script) {
        Vector3f ext = script.getExtents();
        Vector3f com = script.getCenterOfMassOffset();
        float bound = ext.x * 0.5F + Math.abs(com.x)
                + ext.y * 0.5F + Math.abs(com.y)
                + ext.z * 0.5F + Math.abs(com.z)
                + 1.0F;
        if (!(bound >= 6.0F)) {           // 涵蓋 NaN 與過小值
            bound = Float.isFinite(bound) ? 6.0F : Float.NaN;
        }
        return bound;
    }

    /** 點 (px,py,pz) 到線段 (ax..bx) 的平方距離；退化線段（a==b）退為點距。純函數。 */
    public static float distSqPointSegment(float px, float py, float pz,
                                           float ax, float ay, float az,
                                           float bx, float by, float bz) {
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float apx = px - ax, apy = py - ay, apz = pz - az;
        float len2 = abx * abx + aby * aby + abz * abz;
        float t = len2 > 0.0F ? (apx * abx + apy * aby + apz * abz) / len2 : 0.0F;
        if (t < 0.0F) {
            t = 0.0F;
        } else if (t > 1.0F) {
            t = 1.0F;
        }
        float dx = apx - t * abx, dy = apy - t * aby, dz = apz - t * abz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void maybeLog() {
        long total = rejected + delegated;
        if ((total & 0xFFFFFF) == 0L && total != 0L) {
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][VehiclePrefilter] rejected="
                    + rejected + " delegated=" + delegated + " anomalies=" + anomalies);
        }
    }

    private VehicleIntersectPrefilter() {}
}
