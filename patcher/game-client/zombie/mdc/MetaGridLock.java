package zombie.mdc;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import zombie.characters.IsoGameCharacter;
import zombie.debug.DebugLog;
import zombie.iso.IsoMetaGrid;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid;

/**
 * W68 metagrid 房間表鎖（client，42.21.0；docs/patches.md 2cd）。
 *
 * <p>World Streamer 執行緒反序列化 chunk 時經 {@code IsoChunk.getRoom} → {@code IsoMetaGrid.getRoomByID} 往
 * {@code IsoMetaCell.isoRooms}／{@code isoBuildings}（一般 HashMap）補房間。附近有人蓋、拆或打壞牆、門、地板時，
 * 主執行緒的 {@code WorldRegionToMetaGrid.clientProcessBuildings} 拆掉重建自建建築，在
 * {@code recalculateBuildingAndRoomIDs} 複製、清空、重填同樣兩張表。兩邊沒有任何同步：複製出來的清單會有 null
 * （{@code NPE: Cannot read field "def" because "isoRoom" is null}）或比記錄的大小多一筆（AIOOBE），
 * IngameState 接住後把玩家斷線送回主選單。
 *
 * <p>jar 內每一個 {@code getRoomByID} 呼叫點與 {@code DataRoot} 對 {@code clientProcessBuildings} 的唯一呼叫都改道到這裡，
 * 共用同一把鎖：重建期間別的執行緒等重建做完才查或補房間，有人在查或補房間時重建等它做完。原版方法照常各呼叫一次，
 * 回傳值與例外都不變。{@code getRoomByID} 在鎖內不拿其他鎖、不等待，streamer 在呼叫點也不持有其他鎖，所以不會死結
 * （依據見 docs/patches.md 2cd）。公平鎖：主執行緒排隊時，streamer 下一次查房間排在它後面，主執行緒最多等一次查房間。
 *
 * <p>{@code -Dmdc.metaGridLock=off}（或 {@code 0}）一律不上鎖，與原版相同。
 */
public final class MetaGridLock {

    static final String RAW_MODE = System.getProperty("mdc.metaGridLock");
    static final boolean ENABLED = !isOff(RAW_MODE);
    /** 每次啟動最多逐筆記錄的等待次數，之後只計數。 */
    private static final int LOG_LIMIT = 20;
    static final ReentrantLock LOCK = new ReentrantLock(true);

    /** 本次啟動有別的執行緒等過鎖的重建次數（只在主執行緒更新）。 */
    static int contended;

    static {
        log("active=" + ENABLED + (RAW_MODE == null ? "" : " (-Dmdc.metaGridLock=" + RAW_MODE + ")"));
    }

    private MetaGridLock() {}

    static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String mode = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return mode.equals("off") || mode.equals("0");
    }

    /** 取代 jar 內每一個 {@code IsoMetaGrid.getRoomByID(roomID)}。 */
    public static IsoRoom getRoomByID(IsoMetaGrid metaGrid, long roomID) {
        if (!ENABLED) {
            return metaGrid.getRoomByID(roomID);
        }
        LOCK.lock();
        try {
            return metaGrid.getRoomByID(roomID);
        } finally {
            LOCK.unlock();
        }
    }

    /** 取代 {@code DataRoot.clientProcessBuildings} 內唯一的 {@code wrtmg.clientProcessBuildings(changedCells)}。 */
    public static void clientProcessBuildings(WorldRegionToMetaGrid rebuild,
                                              ArrayList<IsoGameCharacter.Location> changedCells) {
        if (!ENABLED) {
            rebuild.clientProcessBuildings(changedCells);
            return;
        }
        int cells = changedCells.size();
        LOCK.lock();
        long start = System.nanoTime();
        int waiting;
        try {
            rebuild.clientProcessBuildings(changedCells);
        } finally {
            // 鎖一直在手上：重建期間來要鎖的執行緒此刻都還在排隊。
            waiting = LOCK.getQueueLength();
            LOCK.unlock();
        }
        if (waiting > 0) {
            contended++;
            if (contended <= LOG_LIMIT) {
                log("clientProcessBuildings (" + cells + " changed cells) held the lock "
                        + Math.round((System.nanoTime() - start) / 100_000.0) / 10.0 + " ms; " + waiting
                        + " thread(s) waited instead of touching the room maps mid-rebuild (#" + contended
                        + (contended == LOG_LIMIT ? ", further waits are not logged" : "") + ")");
            }
        }
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][MetaGridLock] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也照常運作。
        }
    }
}
