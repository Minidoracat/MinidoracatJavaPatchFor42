package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;

import zombie.core.random.RandStandard;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.iso.MetaObject;
import zombie.iso.RoomDef;
import zombie.iso.RoomID;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoLightSwitch;

/**
 * W57 自建房 chunk 載入斷線修補的行為驗證（裸 JVM）。
 *
 * <p>世界是真的：IsoMetaGrid 一個 cell、IsoMetaCell／IsoMetaChunk 的房間查詢、getRoomByID 建出的 IsoRoom 與
 * IsoBuilding、IsoChunk 與 IsoGridSquare 的建構子；只有 IsoWorld、IsoCell、IsoMetaGrid 以 Unsafe 配置後補欄位。
 * chunk (2,2) 涵蓋 16..23；自建房 RoomDef 佔 16..19（chunk 內 x,y 0..3），地圖資料帶一個牆面開關（MetaObject 7）。
 * 「過期」格子＝綁在已 clear() 的 IsoRoom 上，與 clientProcessBuildings 重建後、還在排隊的 chunk 相同。
 *
 * <p>參數 {@code on}：出貨組態；{@code off}：以 {@code -Dmdc.staleRoomHeal=off} 啟動，必須與原版相同。
 */
public final class StaleRoomGuardBehaviorTest {

    static final long LIVE_ID = RoomID.makeID(0, 0, 0);
    static final long OLD_ID = RoomID.makeID(0, 0, 5);
    static final String VANILLA_NPE = "Cannot read field \"objects\" because \"this.def\" is null";

    static IsoCell cell;
    static BuildingDef building;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        RandStandard.INSTANCE.init();
        int failed = 0;

        IsoChunk stale = staleChunk(true);
        failed += check("原版負對照：過期格子讓 IsoLightSwitch.chunkLoaded 拋出與玩家 log 相同的 NPE",
                VANILLA_NPE.equals(npeMessage(() -> IsoLightSwitch.chunkLoaded(stale))));

        IsoChunk freshHelper = freshChunk();
        StaleRoomGuard.chunkLoaded(freshHelper);
        String helperDigest = digest(freshHelper);
        IsoChunk freshVanilla = freshChunk();
        IsoLightSwitch.chunkLoaded(freshVanilla);
        failed += check("沒有過期格子：結果與原版逐項相同（" + helperDigest + "）",
                helperDigest.equals(digest(freshVanilla)) && StaleRoomGuard.heals == 0);

        if (off) {
            failed += check("kill switch off：active=false", !StaleRoomGuard.active());
            IsoChunk offChunk = staleChunk(true);
            failed += check("kill switch off：過期格子照原版拋同一個 NPE",
                    VANILLA_NPE.equals(npeMessage(() -> StaleRoomGuard.chunkLoaded(offChunk))) && StaleRoomGuard.heals == 0);
        } else {
            failed += check("出貨組態：active=true（兩個 updateSquares lambda 都找得到）", StaleRoomGuard.active());

            IsoChunk rebuilt = staleChunk(true);
            IsoLightSwitch wallSwitch = TreeRoomGuardBehaviorTest.alloc(IsoLightSwitch.class);
            rebuilt.getGridSquare(2, 2, 0).getObjects().add(wallSwitch);
            String thrown = npeMessage(() -> StaleRoomGuard.chunkLoaded(rebuilt));
            IsoRoom live = IsoWorld.instance.getMetaGrid().getRoomByID(LIVE_ID);
            IsoGridSquare inside = rebuilt.getGridSquare(1, 1, 0);
            IsoGridSquare outside = rebuilt.getGridSquare(6, 6, 0);
            failed += check("房間被重建：不拋例外，16 格改綁重建後的 IsoRoom",
                    thrown == null && StaleRoomGuard.heals == 1 && roomSquares(rebuilt, live) == 16
                    && StaleRoomGuard.clearedRoomSquares(rebuilt) == 0);
            failed += check("房間被重建：室內格不是 exterior、室外格是，associatedBuilding 指向重建後的建築",
                    !inside.getProperties().has(IsoFlagType.exterior) && outside.getProperties().has(IsoFlagType.exterior)
                    && inside.associatedBuilding == building);
            failed += check("房間被重建：玩家擺的開關掛到新房間，房間燈建好並登記到 chunk 與 cell",
                    live.lightSwitches.contains(wallSwitch) && !live.roomLights.isEmpty()
                    && rebuilt.roomLights.containsAll(live.roomLights) && cell.roomLights.containsAll(live.roomLights));

            IsoChunk removed = staleChunk(false);
            thrown = npeMessage(() -> StaleRoomGuard.chunkLoaded(removed));
            failed += check("房間被拆掉：不拋例外，格子都沒有房間且是 exterior、chunk 沒有房間燈",
                    thrown == null && StaleRoomGuard.heals == 2 && roomSquares(removed, null) == 64
                    && removed.getGridSquare(1, 1, 0).getProperties().has(IsoFlagType.exterior)
                    && removed.roomLights.isEmpty());
        }

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("StaleRoomGuard 行為驗證全數通過（" + (off ? "off" : "on") + "）");
    }

    /** 新世界＋chunk (2,2)：過期格子綁在已 clear() 的房間；rebuilt=true 時 metagrid 在原處有重建後的自建房。 */
    static IsoChunk staleChunk(boolean rebuilt) throws Exception {
        IsoChunk chunk = world(rebuilt);
        IsoRoom old = new IsoRoom();
        old.def = new RoomDef(OLD_ID, "");
        old.clear(true);
        forRoomSquares(chunk, square -> {
            square.roomId = OLD_ID;
            square.room = old;
        });
        return chunk;
    }

    /** 新世界＋chunk (2,2)：房間格子照原版 setRoomID 綁好（沒有過期參照）。 */
    static IsoChunk freshChunk() throws Exception {
        IsoChunk chunk = world(true);
        forRoomSquares(chunk, square -> square.setRoomID(LIVE_ID));
        return chunk;
    }

    static IsoChunk world(boolean withRoom) throws Exception {
        cell = TreeRoomGuardBehaviorTest.alloc(IsoCell.class);
        TreeRoomGuardBehaviorTest.set(IsoCell.class, cell, "roomLights", new ArrayList<>());
        TreeRoomGuardBehaviorTest.set(IsoCell.class, cell, "roomList", new ArrayList<>());
        IsoWorld world = TreeRoomGuardBehaviorTest.alloc(IsoWorld.class);
        world.currentCell = cell;
        IsoMetaGrid grid = TreeRoomGuardBehaviorTest.alloc(IsoMetaGrid.class);
        IsoMetaCell metaCell = new IsoMetaCell(0, 0);
        TreeRoomGuardBehaviorTest.set(IsoMetaGrid.class, grid, "grid", new IsoMetaCell[][] {{metaCell}});
        TreeRoomGuardBehaviorTest.set(IsoMetaGrid.class, grid, "width", 1);
        TreeRoomGuardBehaviorTest.set(IsoMetaGrid.class, grid, "height", 1);
        TreeRoomGuardBehaviorTest.set(IsoWorld.class, world, "metaGrid", grid);
        IsoWorld.instance = world;

        building = new BuildingDef();
        if (withRoom) {
            RoomDef def = new RoomDef(LIVE_ID, "");
            def.userDefined = true;
            def.level = 0;
            def.rects.add(new RoomDef.RoomRect(16, 16, 4, 4));
            def.CalculateBounds();
            def.building = building;
            building.rooms.add(def);
            def.objects.add(new MetaObject(7, 17, 17, def));
            metaCell.rooms.put(LIVE_ID, def);
            metaCell.roomList.add(def);
            metaCell.addRoom(def, 0, 0);
        }

        IsoChunk chunk = new IsoChunk(cell);
        chunk.wx = 2;
        chunk.wy = 2;
        chunk.minLevel = 0;
        chunk.maxLevel = 0;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                IsoGridSquare square = new IsoGridSquare(cell, null, 16 + x, 16 + y, 0);
                square.chunk = chunk;
                square.roomId = -1L;
                chunk.setSquare(x, y, 0, square);
            }
        }
        return chunk;
    }

    interface SquareAction {
        void apply(IsoGridSquare square) throws Exception;
    }

    static void forRoomSquares(IsoChunk chunk, SquareAction action) throws Exception {
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                action.apply(chunk.getGridSquare(x, y, 0));
            }
        }
    }

    /** 綁在指定房間（null＝沒有房間）的格子數。 */
    static int roomSquares(IsoChunk chunk, IsoRoom room) {
        int count = 0;
        for (IsoGridSquare square : chunk.getSquaresForLevel(0)) {
            if (square != null && square.getRoom() == room) {
                count++;
            }
        }
        return count;
    }

    /** 原版與 helper 結果比對用：每格房間有無、exterior、associatedBuilding，加上房間燈與開關數。 */
    static String digest(IsoChunk chunk) {
        StringBuilder sb = new StringBuilder();
        IsoRoom room = null;
        for (IsoGridSquare square : chunk.getSquaresForLevel(0)) {
            room = square.getRoom() != null ? square.getRoom() : room;
            sb.append(square.getRoom() == null ? '-' : 'R')
                    .append(square.getProperties().has(IsoFlagType.exterior) ? 'e' : 'i')
                    .append(square.associatedBuilding == null ? '0' : 'b');
        }
        return Integer.toHexString(sb.toString().hashCode()) + " roomLights=" + chunk.roomLights.size()
                + " cellLights=" + cell.roomLights.size() + " switches=" + (room == null ? -1 : room.lightSwitches.size());
    }

    interface Call {
        void run() throws Exception;
    }

    /** 執行並回傳 NPE 訊息；沒有拋出回 null，拋出其他例外則印出並回 "other"。 */
    static String npeMessage(Call call) {
        try {
            call.run();
            return null;
        } catch (NullPointerException e) {
            return e.getMessage();
        } catch (Exception e) {
            System.out.println("  threw " + e);
            return "other";
        }
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "behavior OK   " : "behavior FAIL ") + what);
        return ok ? 0 : 1;
    }

    private StaleRoomGuardBehaviorTest() {}
}
