package zombie.mdc;

import java.util.concurrent.ThreadLocalRandom;

import zombie.debug.DebugLog;
import zombie.worldMap.WorldMapRemotePlayer;

/**
 * W59 世界地圖隊友點錯位（2026-10-12；docs/patches.md 2bw）。
 *
 * <p><b>原版</b>：伺服器每秒送 {@code onlineId, changeCount, x, y}；客戶端
 * {@code GameClient.receiveWorldMapPlayerPosition} 只在本機計數與伺服器計數不同時索取完整資料，
 * {@code WorldMapRemotePlayer.setFullData} 才把 {@code hasFullData} 設為 true，沒有它的隊友大小地圖都不畫。
 * 客戶端每幀 {@code IsoPlayer.resetDisplayName → setPlayer} 會把新條目從 0 推到 1，伺服器條目第一次
 * {@code setPlayer} 後也是 1；索取或回覆（UNRELIABLE_SEQUENCED）掉一包，兩邊就一直相等，之後不再索取。
 *
 * <p><b>手術</b>：GameServer 送出計數的兩個 {@code getChangeCount} 改道本類，送出值改成
 * {@code BASE + 2 × 原計數}。BASE 每次開機隨機取至少 1024 的偶數，送出值一定是偶數且（原計數 8192 以內）
 * 至少 1024。客戶端沒拿到完整資料前，本機值是條目建立後偵測到的欄位變化次數（通常是 1），對不上，所以每秒
 * 持續索取直到拿到；拿到後兩邊相等的條件與原版相同。伺服器條目本身的計數不動，伺服器也從不讀客戶端的計數。
 *
 * <p>kill switch {@code -Dmdc.worldMapChangeCount=0|off}：回傳原值，送出的 wire 與原版相同。
 */
public final class WorldMapChangeCount {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.worldMapChangeCount"));
    /** 偶數，範圍 [1024, 16382]。 */
    static final int BASE = ThreadLocalRandom.current().nextInt(512, 8192) * 2;
    private static final String TAG = "[MinidoracatJavaPatch][WorldMapChangeCount] ";

    private static boolean announced;

    private WorldMapChangeCount() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** 取代 {@code GameServer} 送出 WorldMapPlayerPosition 時的 {@code remotePlayer.getChangeCount()}。 */
    public static short getChangeCount(WorldMapRemotePlayer remotePlayer) {
        short raw = remotePlayer.getChangeCount();
        if (!ENABLED) {
            return raw;
        }
        if (!announced) {
            announced = true;
            try {
                DebugLog.log(TAG + "首次生效 base=" + BASE + "（-Dmdc.worldMapChangeCount=0|off 回原版）");
            } catch (RuntimeException ignored) {
                // 首次生效行只是部署驗證用，失敗不得中斷封包
            }
        }
        return (short) (BASE + 2 * raw);
    }
}
