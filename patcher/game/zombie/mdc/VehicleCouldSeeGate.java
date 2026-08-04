package zombie.mdc;

import zombie.core.math.PZMath;
import zombie.debug.DebugType;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;
import zombie.pathfind.VehiclePoly;
import zombie.vehicles.BaseVehicle;

/**
 * 車輛 couldSeeIntersectedSquare 的 server 死工消除（W3-4，2026-08-05）。
 *
 * BaseVehicle.update() 每車每 tick 對車輛 AABB 的 10–18 格做
 * getGridSquare＋isCouldSee＋多邊形相交掃描，結果唯一去處是 setTargetAlpha——
 * 而 IsoObject.setTargetAlpha 開頭即 if(!GameServer.server) 擋掉寫入、
 * getTargetAlpha 在 server 恆回 1.0F、targetAlpha[] 全 jar 僅四個守衛讀點
 * （對抗審查逐項證偽通過）。server 上整段掃描是可證明的 100% 死工。
 *
 * server → 直接回 true（呼叫端 ifne 跳過的 setTargetAlpha(i,0) 本就是 no-op，
 * true/false 行為等價，取 true 最省且不觸碰 IsoPlayer.players——dedicated
 * server 上恆 null）。注意 server 路徑不解參考 vehicle（見測試）。
 *
 * 非 server → 以全 public API 逐指令複刻 vanilla private 原版（原方法 private
 * 無法直呼；此路徑部署面不可達，僅為 LoadCheck/行為等價完整性）。
 * 對應 SmokeCheck：render() 內同名 callsite 不得被觸碰（負對照）＋
 * setTargetAlpha/getTargetAlpha 的 server guard 結構斷言（no-op 論證的建置期鏈結）。
 */
public final class VehicleCouldSeeGate {

    private static long serverSkipped;
    private static long replicated;

    /** BaseVehicle.update()V 內唯一 couldSeeIntersectedSquare 呼叫點的改道目標。 */
    public static boolean couldSeeIntersectedSquare(BaseVehicle v, int playerIndex) {
        if (GameServer.server) {
            serverSkipped++;
            maybeLog();
            return true;
        }
        replicated++;
        maybeLog();
        // vanilla private couldSeeIntersectedSquare(int) 逐指令複刻（public API 版）
        VehiclePoly poly = v.getPoly();
        float minX = Math.min(poly.x1, Math.min(poly.x2, Math.min(poly.x3, poly.x4)));
        float minY = Math.min(poly.y1, Math.min(poly.y2, Math.min(poly.y3, poly.y4)));
        float maxX = Math.max(poly.x1, Math.max(poly.x2, Math.max(poly.x3, poly.x4)));
        float maxY = Math.max(poly.y1, Math.max(poly.y2, Math.max(poly.y3, poly.y4)));
        int z = PZMath.fastfloor(v.getZ());

        for (int y = PZMath.fastfloor(minY); y < (int) Math.ceil(maxY); y++) {
            for (int x = PZMath.fastfloor(minX); x < (int) Math.ceil(maxX); x++) {
                IsoGridSquare square = v.getCell().getGridSquare(x, y, z);
                if (square != null && square.isCouldSee(playerIndex) && v.isIntersectingSquare(x, y, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void maybeLog() {
        long total = serverSkipped + replicated;
        // 呼叫率比 VehicleIntersectPrefilter 低 1-2 個數量級（每車每 tick 一次而非每殭屍每車），
        // mask 相應調小，確保首日 runbook「缺行即回退」判準在分鐘級可用（code review 處方）
        if ((total & 0x3FFFF) == 0L && total != 0L) {
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][VehicleCouldSee] serverSkipped="
                    + serverSkipped + " replicated=" + replicated);
        }
    }

    private VehicleCouldSeeGate() {}
}
