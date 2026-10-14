package zombie.mdc;

import java.util.ArrayList;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * W62／W63 行為驗證（裸 JVM）。
 *
 * <p>W62：update() 開頭 {@code this.chunk.vehicles.contains(this)} 改道後，只有「車自己的 chunk 已卸載（refs 空）、車上有
 * 本機乘客」回 false（原版接著走 chunk 已回收的分支，照常往下跑）；其餘一律與 {@code ArrayList.contains} 相同。原版負對照：
 * 關掉時回 true，原版接著呼叫 removeFromWorld（遇本機乘客提早 return）並跳過整個 update（SmokeCheck 鎖住這段）。
 *
 * <p>W63：doRemove 的 {@code player.setPosition(伺服器 x, y, 0)} 改道後，車由本機模擬（Local／LocalCollide）時不移動玩家，
 * 其餘照原版。原版負對照：關掉時玩家被移到伺服器位置。
 *
 * <p>參數 {@code on}：出貨組態；{@code updateOff}：{@code -Dmdc.vehicleOrphanUpdate=off}，W62 必須與原版相同、W63 照常；
 * {@code recallOff}：{@code -Dmdc.driverRecallGuard=off}，W63 必須與原版相同、W62 照常。
 */
public final class VehicleOrphanGuardBehaviorTest {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean updateOn = !mode.equals("updateOff");
        boolean recallOn = !mode.equals("recallOff");
        int failed = 0;
        VehicleClampGuardBehaviorTest.world();
        GameServer.server = false;
        IsoPlayer local = TreeRoomGuardBehaviorTest.alloc(IsoPlayer.class);
        IsoPlayer other = TreeRoomGuardBehaviorTest.alloc(IsoPlayer.class);
        IsoPlayer.players[0] = local;

        // ---- W62 ----
        IsoChunk unloaded = new IsoChunk(IsoWorld.instance.currentCell);
        BaseVehicle driven = vehicle(unloaded, local);
        unloaded.vehicles.add(driven);
        boolean first = VehicleOrphanGuard.contains(unloaded.vehicles, driven);
        boolean again = VehicleOrphanGuard.contains(unloaded.vehicles, driven);
        if (updateOn) {
            failed += check("W62 chunk 已卸載、車上有本機乘客：回 false，原版改走「已回收」分支照常更新；同一段只記一次",
                    !first && !again && VehicleOrphanGuard.orphanEpisodes == 1);
        } else {
            failed += check("W62 kill switch off：回 true（原版接著 removeFromWorld 並跳過整個 update），不計數",
                    !VehicleOrphanGuard.UPDATE_ENABLED && first && again && VehicleOrphanGuard.orphanEpisodes == 0);
        }

        BaseVehicle parked = vehicle(unloaded, other);
        unloaded.vehicles.add(parked);
        failed += check("W62 負對照：車上只有別人（或沒人）→ 回 true，照原版移除",
                VehicleOrphanGuard.contains(unloaded.vehicles, parked));

        IsoChunk loaded = new IsoChunk(IsoWorld.instance.currentCell);
        loaded.refs.add(VehicleClampGuardBehaviorTest.map);
        BaseVehicle nearby = vehicle(loaded, local);
        loaded.vehicles.add(nearby);
        failed += check("W62 負對照：chunk 仍被 ChunkMap 參照（refs 非空）→ 回 true",
                VehicleOrphanGuard.contains(loaded.vehicles, nearby));

        ArrayList<Object> otherList = new ArrayList<>();
        otherList.add(driven);
        failed += check("W62 負對照：不是車自己 chunk 的清單（例：animals、別的清單）→ 回原值",
                VehicleOrphanGuard.contains(otherList, driven) && !VehicleOrphanGuard.contains(new ArrayList<>(), driven)
                && VehicleOrphanGuard.contains(otherList, driven) == otherList.contains(driven));

        IsoChunk recycled = new IsoChunk(IsoWorld.instance.currentCell);
        BaseVehicle stale = vehicle(recycled, local);
        failed += check("W62 負對照：chunk 物件已回收（清單已清空）→ 回 false，與原版相同",
                !VehicleOrphanGuard.contains(recycled.vehicles, stale));

        GameServer.server = true;
        boolean onServer = VehicleOrphanGuard.contains(unloaded.vehicles, driven);
        GameServer.server = false;
        failed += check("W62 負對照：伺服器端 → 回 true（原版伺服器不走這段）", onServer);

        // ---- W63 ----
        BaseVehicle own = vehicle(null, local);
        own.netPlayerAuthorization = BaseVehicle.Authorization.Local;
        seat(local, own);
        local.setPosition(100.0F, 200.0F, 0.0F);
        VehicleOrphanGuard.setPosition(local, 900.0F, 950.0F, 0.0F);
        if (recallOn) {
            failed += check("W63 本機模擬（Local）的車：不把駕駛移到伺服器位置",
                    at(local, 100.0F, 200.0F) && VehicleOrphanGuard.recallsHeld == 1);
            own.netPlayerAuthorization = BaseVehicle.Authorization.LocalCollide;
            VehicleOrphanGuard.setPosition(local, 900.0F, 950.0F, 0.0F);
            failed += check("W63 本機模擬（LocalCollide）的車：同樣不移動",
                    at(local, 100.0F, 200.0F) && VehicleOrphanGuard.recallsHeld == 2);
        } else {
            failed += check("W63 kill switch off：照原版把駕駛移到伺服器位置，不計數",
                    !VehicleOrphanGuard.RECALL_ENABLED && at(local, 900.0F, 950.0F) && VehicleOrphanGuard.recallsHeld == 0);
        }

        int heldBefore = VehicleOrphanGuard.recallsHeld;
        for (BaseVehicle.Authorization authorization
                : new BaseVehicle.Authorization[] {BaseVehicle.Authorization.Remote, BaseVehicle.Authorization.Server}) {
            own.netPlayerAuthorization = authorization;
            local.setPosition(100.0F, 200.0F, 0.0F);
            VehicleOrphanGuard.setPosition(local, 900.0F, 950.0F, 0.0F);
            failed += check("W63 負對照：" + authorization + " 授權（不是本機模擬）→ 照原版移動",
                    at(local, 900.0F, 950.0F) && VehicleOrphanGuard.recallsHeld == heldBefore);
        }
        seat(local, null);
        local.setPosition(100.0F, 200.0F, 0.0F);
        VehicleOrphanGuard.setPosition(local, 900.0F, 950.0F, 0.0F);
        failed += check("W63 負對照：玩家不在車上 → 照原版移動",
                at(local, 900.0F, 950.0F) && VehicleOrphanGuard.recallsHeld == heldBefore);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("VehicleOrphanGuard 行為驗證全數通過（" + mode + "）");
    }

    /** 掛在 chunk 上、0 號座位坐著 rider 的車。 */
    static BaseVehicle vehicle(IsoChunk chunk, IsoPlayer rider) throws Exception {
        BaseVehicle car = TreeRoomGuardBehaviorTest.alloc(BaseVehicle.class);
        BaseVehicle.Passenger seat = TreeRoomGuardBehaviorTest.alloc(BaseVehicle.Passenger.class);
        seat.character = rider;
        BaseVehicle.Passenger empty = TreeRoomGuardBehaviorTest.alloc(BaseVehicle.Passenger.class);
        TreeRoomGuardBehaviorTest.set(BaseVehicle.class, car, "passengers", new BaseVehicle.Passenger[] {seat, empty});
        car.chunk = chunk;
        return car;
    }

    static void seat(IsoPlayer player, BaseVehicle car) throws Exception {
        TreeRoomGuardBehaviorTest.set(IsoGameCharacter.class, player, "vehicle", car);
    }

    static boolean at(IsoPlayer player, float x, float y) {
        return player.getX() == x && player.getY() == y;
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private VehicleOrphanGuardBehaviorTest() {}
}
