package zombie.mdc;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.SafeHouse;
import zombie.iso.objects.IsoCompost;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.zones.Zone;

/**
 * 窄範圍 patch helper（隨 patch 以 loose class 出貨；由被改道的呼叫點進入）。
 * 原則：只攔「已知噪音」，其餘一律轉發原始 log 呼叫——寧漏不誤。
 * 比對策略：能拿到完整字面值的用 equals（零誤攔），動態尾綴的用 startsWith。
 * 樣式出處：docs/specs/*.json（javap 證據）；docs/patches.md 有對照表。
 */
public final class LogFilter {

    /** 每個 LootRespawn 執行緒各自重用一個只供 gate 判斷的合格 Zone，避免週期掃描時大量配置 UUID。 */
    private static final ThreadLocal<Zone> LOOT_RESPAWN_ZONE = ThreadLocal.withInitial(
            () -> new Zone("", "TownZone", 0, 0, 0, 1, 1));

    /** warn(String format, Object[]) 呼叫點——比對「格式化前」的完整 format 常數（equals）。 */
    private static final String[] FMT_EXACT = {
        "AnimState not found: %s",                                                       // AnimationSet
        "SkeletonBone not resolved for bone: %s, defaulting to SkeletonBone.None",       // SkinningBoneHierarchy
        "The packet %s is not consistent: %s",                                           // INetworkPacket.logInconsistentPacket
    };

    /** warn(Object) 呼叫點——訊息已組字串：完整訊息用 equals、動態長串用 startsWith。 */
    private static final String[] OBJ_EXACT = {
        "Invalid SpriteConfig object! scripted object = MetalBigWireFence",              // SpriteConfig（選擇性）
        "Invalid SpriteConfig object! scripted object = WoodFloorLvl3",
        "Invalid SpriteConfig object! scripted object = Wooden_Windows",
        // 42.20 新增噪音（2026-07-30 正式服實測 5.5h：510/266/246/69/64/28 筆，全為原版物件必刷）
        "Invalid SpriteConfig object! scripted object = DoubleWireGate",
        "Invalid SpriteConfig object! scripted object = BrickWallLvl2",
        "Invalid SpriteConfig object! scripted object = MetalSmallWireFence",
        "Invalid SpriteConfig object! scripted object = BrickWindowFrameLvl2",
        "Invalid SpriteConfig object! scripted object = Piano",
        "Invalid SpriteConfig object! scripted object = WoodenWallLvl3",
        // 42.20.3 新增噪音（2026-08-19 正式服實測 8 session／約 26h 聚合；行尾＝該名筆數）。
        // 入列門檻：聚合 ≥4 筆/h（本窗 26h ⇒ ≥104 筆）才收——低量名寧可留在 log 裡當破損訊號，
        // 勿為近零收益永久吞掉該物件唯一的診斷線（本輪 17 個 ≤86 筆的名字因此不收）。
        "Invalid SpriteConfig object! scripted object = SandFloor",                      // 6237
        "Invalid SpriteConfig object! scripted object = WoodenDarkWallLvl3",             // 5501
        "Invalid SpriteConfig object! scripted object = GravelFloor",                    // 3342
        "Invalid SpriteConfig object! scripted object = Floor_SpringGrass",              // 2636
        "Invalid SpriteConfig object! scripted object = DoubleDoor",                     // 2204
        "Invalid SpriteConfig object! scripted object = WoodenWindowFrameLvl3",          // 1441
        "Invalid SpriteConfig object! scripted object = WoodFloorLvl2",                  // 1233
        "Invalid SpriteConfig object! scripted object = Wood_DoubleDoorDark",            // 476
        "Invalid SpriteConfig object! scripted object = WoodDoorFrameLvl3",              // 297
        "Invalid SpriteConfig object! scripted object = Fences_MetalFarmGate",           // 150
    };
    private static final String[] OBJ_PREFIX = {
        "No packet handler for type:",                                                   // PacketsCache <init>
    };

    /** DebugLog.log(String) 呼叫點。 */
    private static final String[] LOG_EXACT = {
        "moveZombie: There are no zombies in nz.zombies.",                               // NetworkZombieManager
    };
    private static final String[] LOG_PREFIX = {
        "ItemPickInfo -> cannot get ID for ",                                            // ItemPickInfo（debug 診斷前綴不同、照常轉發）
    };

    /**
     * DebugLog.log(DebugType, String) 呼叫點——訊息由 invokedynamic makeConcatWithConstants
     * 組成（座標與 boolean 都是變數），只能用 startsWith。
     */
    private static final String[] LOG_TYPE_PREFIX = {
        "Send Toxic Building at [ ",                                                      // GameServer.sendToxicBuilding
    };

    /**
     * System.out.println(String) 呼叫點（IsoObject.syncIsoObject 兩處同方法改道）——只攔
     * B42 建造流程每次必印的 IsoThumpable not-found（訊息由 invokedynamic 組成 → startsWith）；
     * 同方法的 "square is null" 與其他 class（IsoDoor／IsoWindow…）的 not-found 是破損訊號，照常印。
     */
    private static final String[] PRINTLN_PREFIX = {
        "ERROR: IsoThumpable not found on square ",                                     // IsoObject.syncIsoObject（8/30–9/2 四天 11,567 行）
    };

    public static void warnFmt(DebugType type, String format, Object[] args) {
        if (format != null) {
            for (String p : FMT_EXACT) {
                if (format.equals(p)) {
                    return;
                }
            }
        }
        type.warn(format, args);
    }

    /**
     * OBJ_EXACT／OBJ_PREFIX 的攔截判定——抽成 pure function 供 LogFilterNoiseTest 鎖行為。
     * 前置條件：{@code s} 非 null（唯一呼叫端 warnObj 以 String.valueOf 保證；新增呼叫端須自行保證）。
     */
    static boolean suppressesObj(String s) {
        for (String p : OBJ_EXACT) {
            if (s.equals(p)) {
                return true;
            }
        }
        for (String p : OBJ_PREFIX) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 名單規模（供 LogFilterNoiseTest 擋整段誤刪／誤增；非 runtime 路徑）。 */
    static int objExactCountForTest() {
        return OBJ_EXACT.length;
    }

    public static void warnObj(DebugType type, Object message) {
        if (suppressesObj(String.valueOf(message))) {
            return;
        }
        type.warn(message);
    }

    public static void log(String message) {
        if (message != null) {
            for (String p : LOG_EXACT) {
                if (message.equals(p)) {
                    return;
                }
            }
            for (String p : LOG_PREFIX) {
                if (message.startsWith(p)) {
                    return;
                }
            }
        }
        DebugLog.log(message);
    }

    public static void logType(DebugType type, String message) {
        if (message != null) {
            for (String p : LOG_TYPE_PREFIX) {
                if (message.startsWith(p)) {
                    return;
                }
            }
        }
        DebugLog.log(type, message);
    }

    /** PRINTLN_PREFIX 的攔截判定——pure function 供 LogFilterNoiseTest 鎖行為（{@code s} 可為 null）。 */
    static boolean suppressesPrintln(String s) {
        if (s == null) {
            return false;
        }
        for (String p : PRINTLN_PREFIX) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public static void println(java.io.PrintStream out, String message) {
        if (suppressesPrintln(message)) {
            return;
        }
        out.println(message);
    }

    /**
     * B42.19 只讓 TownZone/TownZones/TrailerPark 通過定期刷新，而且任何一次建造都會把整個 Zone
     * 永久標成 haveConstruction。若同一垂直欄位確有未搬動的原生固定容器，回傳僅供本次 gate
     * 使用的合格 Zone；原版 safehouse、seen-hours、週期與單容器規則仍在 LootRespawn 內執行。
     */
    public static Zone getLootRespawnZone(IsoGridSquare square) {
        if (square == null) {
            return null;
        }

        Zone zone = square.getZone();
        if (isLootRespawnZone(zone) && !zone.haveConstruction) {
            return zone;
        }
        if (!hasFixedLootContainerInColumn(square)) {
            return zone;
        }

        Zone effective = LOOT_RESPAWN_ZONE.get();
        effective.hourLastSeen = zone == null ? 0 : zone.hourLastSeen;
        effective.haveConstruction = false;
        return effective;
    }

    /** 搬動過的原生家具與玩家製 IsoThumpable 都不可因放寬 Zone gate 而取得定期刷新。 */
    public static int getLootRespawnContainerCount(IsoObject object) {
        if (object == null || object instanceof IsoDeadBody || object instanceof IsoThumpable
                || object instanceof IsoCompost || object.isMovedThumpable()) {
            return 0;
        }
        return object.getContainerCount();
    }

    private static boolean hasFixedLootContainerInColumn(IsoGridSquare ground) {
        IsoChunk chunk = ground.getChunk();
        if (chunk == null) {
            return false;
        }

        int localX = Math.floorMod(ground.getX(), 8);
        int localY = Math.floorMod(ground.getY(), 8);
        for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
            IsoGridSquare square = chunk.getGridSquare(localX, localY, z);
            if (square == null) {
                continue;
            }
            for (int i = 0; i < square.getObjects().size(); i++) {
                if (getLootRespawnContainerCount(square.getObjects().get(i)) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLootRespawnZone(Zone zone) {
        if (zone == null) {
            return false;
        }
        String type = zone.getType();
        return "TownZone".equals(type) || "TownZones".equals(type) || "TrailerPark".equals(type);
    }

    /**
     * B42.19 大型 Map= 組合偶爾遺失 IsoGridSquare -> IsoRoom 綁定；只在安全屋申請路徑
     * 回查 authoritative roomList，補回 roomId 後仍交由原版 SafeHouse 規則驗證。
     */
    public static IsoBuilding getBuilding(IsoGridSquare square) {
        if (square == null) {
            return null;
        }
        IsoBuilding building = square.getBuilding();
        if (building != null) {
            return building;
        }

        RoomDef room = findRoom(square);
        if (room == null) {
            return null;
        }

        long oldRoomId = square.getRoomID();
        square.setRoomID(room.getID());
        building = square.getBuilding();
        if (building == null) {
            square.setRoomID(oldRoomId);
            return null;
        }

        DebugLog.log(DebugType.Multiplayer, String.format(
                "[MinidoracatJavaPatch] repaired safehouse room binding at %d,%d,%d roomId=%d",
                square.getX(), square.getY(), square.getZ(), room.getID()));
        return building;
    }

    public static String canBeSafehouse(IsoGridSquare square, IsoPlayer player) {
        getBuilding(square);
        if (player != null) {
            getBuilding(player.getCurrentSquare());
        }
        return SafeHouse.canBeSafehouse(square, player);
    }

    private static RoomDef findRoom(IsoGridSquare square) {
        IsoMetaGrid grid = IsoWorld.instance.getMetaGrid();
        IsoMetaCell center = grid.getMetaGridFromTile(square.getX(), square.getY());
        if (center == null) {
            return null;
        }

        RoomDef fallback = findRoom(center, square);
        if (fallback != null && fallback.userDefined) {
            return fallback;
        }

        // ponytail: claim-time O(n) fallback; add an index only if this ever becomes a hot path.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                RoomDef candidate = findRoom(grid.getCellData(center.getX() + dx, center.getY() + dy), square);
                if (candidate != null && candidate.userDefined) {
                    return candidate;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
            }
        }
        return fallback;
    }

    /** Mirror IsoMetaChunk.getRoomAt precedence: newest room first, user-defined wins. */
    private static RoomDef findRoom(IsoMetaCell cell, IsoGridSquare square) {
        if (cell == null) {
            return null;
        }
        RoomDef fallback = null;
        for (int i = cell.roomList.size() - 1; i >= 0; i--) {
            RoomDef room = cell.roomList.get(i);
            if (room.getBuilding() != null && !room.isEmptyOutside()
                    && room.isInside(square.getX(), square.getY(), square.getZ())) {
                if (room.userDefined) {
                    return room;
                }
                if (fallback == null) {
                    fallback = room;
                }
            }
        }
        return fallback;
    }

    private LogFilter() {}
}
