package zombie.mdc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import zombie.debug.DebugLog;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid;
import zombie.iso.objects.IsoLightSwitch;

/**
 * W57 自建房 chunk 載入斷線修補（client，42.21.0；docs/patches.md 2bu）。
 *
 * <p>chunk 在 World Streamer 執行緒反序列化時，格子就綁上 IsoRoom（{@code setRoomID}）。主執行緒在
 * {@code IsoChunk.loadInMainThread} 接手之前，若 IsoRegions 變更讓
 * {@code WorldRegionToMetaGrid.clientProcessBuildings} 拆掉重建這一帶的自建建築，舊 IsoRoom 會被
 * {@code clear()}，def 變成 null。原版 {@code updateSquares} 只重綁已在 ChunkMap 裡的 chunk，還在排隊的
 * 這個 chunk 沒被更新；接著 {@code IsoLightSwitch.chunkLoaded} 對這種房間呼叫 {@code hasLightSwitches()}，
 * 讀 {@code def.objects} 就 NPE，IngameState 接住後把玩家斷線送回主選單。
 *
 * <p>本方法取代 loadInMainThread 內唯一的 {@code IsoLightSwitch.chunkLoaded(this)}。沒有格子指向已清空的
 * 房間時直接呼叫原版，結果逐位相同；有的話（原版必定 NPE），先對這個 chunk 補跑原版 updateSquares
 * 的兩段逐格 lambda（同一份 bytecode）與 chunk 收尾，再呼叫原版。
 *
 * <p>{@code -Dmdc.staleRoomHeal=off}（或 {@code 0}）一律直接呼叫原版。lambda 找不到（TIS 改了結構）時記一行並呼叫原版。
 */
public final class StaleRoomGuard {

    static final String RAW_MODE = System.getProperty("mdc.staleRoomHeal");
    static final boolean ENABLED = !isOff(RAW_MODE);
    /** 原版 updateSquares 對每個更新 chunk 的 invalidateRenderChunkLevels 旗標（SmokeCheck 與原版連動）。 */
    private static final long RENDER_DIRTY_FLAGS = 2112L;
    /** 每次啟動最多逐筆記錄的補綁次數，之後只計數。 */
    private static final int LOG_LIMIT = 20;
    private static final MethodHandle REBIND = updateSquaresStep("lambda$updateSquares$0");
    private static final MethodHandle REFRESH = updateSquaresStep("lambda$updateSquares$1");

    /** 本次啟動補綁過的 chunk 數（只在主執行緒更新）。 */
    static int heals;

    static {
        log("active=" + active() + (RAW_MODE == null ? "" : " (-Dmdc.staleRoomHeal=" + RAW_MODE + ")"));
    }

    private StaleRoomGuard() {}

    static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String mode = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return mode.equals("off") || mode.equals("0");
    }

    private static MethodHandle updateSquaresStep(String name) {
        try {
            return MethodHandles.privateLookupIn(WorldRegionToMetaGrid.class, MethodHandles.lookup())
                    .findStatic(WorldRegionToMetaGrid.class, name, MethodType.methodType(void.class, IsoGridSquare.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(name + " lookup failed (" + e + "); vanilla chunkLoaded");
            return null;
        }
    }

    /** 補綁是否生效（開關打開且兩個 lambda 都找得到）。 */
    static boolean active() {
        return ENABLED && REBIND != null && REFRESH != null;
    }

    /** 取代 {@code IsoLightSwitch.chunkLoaded(this)}（IsoChunk.loadInMainThread 內唯一呼叫點）。 */
    public static void chunkLoaded(IsoChunk chunk) {
        if (active()) {
            int stale = clearedRoomSquares(chunk);
            if (stale > 0) {
                heal(chunk, stale);
            }
        }
        IsoLightSwitch.chunkLoaded(chunk);
    }

    /** 與 IsoLightSwitch.chunkLoaded 走訪相同的格子，數出指向 def 已被清空之 IsoRoom 的格子。 */
    static int clearedRoomSquares(IsoChunk chunk) {
        int count = 0;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
                    IsoGridSquare square = chunk.getGridSquare(x, y, z);
                    IsoRoom room = square == null ? null : square.getRoom();
                    if (room != null && room.def == null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 原版 updateSquares 對一個更新 chunk 做的事：兩段逐格 lambda，再做 chunk 收尾。 */
    private static void heal(IsoChunk chunk, int stale) {
        forEachSquare(chunk, REBIND);
        forEachSquare(chunk, REFRESH);
        chunk.invalidateRenderChunkLevels(RENDER_DIRTY_FLAGS);
        chunk.getCutawayData().invalidateAll();
        chunk.checkLightingLater_AllPlayers_AllLevels();
        heals++;
        if (heals <= LOG_LIMIT) {
            log("chunk " + chunk.wx + "," + chunk.wy + ": " + stale
                    + " squares pointed to a cleared IsoRoom; rebound like WorldRegionToMetaGrid.updateSquares (#" + heals
                    + (heals == LOG_LIMIT ? ", further heals are not logged" : "") + ")");
        }
    }

    /** 走訪順序同原版 WorldRegionToMetaGrid.forEachSquare。 */
    private static void forEachSquare(IsoChunk chunk, MethodHandle step) {
        for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
            for (IsoGridSquare square : chunk.getSquaresForLevel(z)) {
                if (square != null) {
                    try {
                        step.invokeExact(square);
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        // 原版 lambda 不宣告 checked 例外；invokeExact 的簽名要求這一層。
                        throw new IllegalStateException(t);
                    }
                }
            }
        }
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][StaleRoomGuard] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
