package zombie.mdc;

import java.io.File;
import java.util.ArrayList;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.debug.DebugLog;
import zombie.iso.IsoMetaGrid;
import zombie.network.GameServer;

/**
 * W87 {@code Map=} 列了不存在的地圖資料夾時，只略過那一項（2026-10-31；docs/patches.md 2cv）。
 *
 * <p><b>原版缺陷</b>：{@code IsoMetaGrid.CreateStep1} 照資料夾在 {@code Map=} 的序號建
 * {@code new MapFiles(..., i)}，priority＝序號，不存在而被略過的資料夾也佔一號；可是
 * {@code higherPriority300x300CellExists(priority, …)} 拿這個號碼去索引少了那一項的 {@code IsoLot.MapFiles}，
 * 排在缺的資料夾後面的地圖會比到自己，建築全部被當成「有更高優先的地圖蓋住」而略過（房間在、建築不在：
 * 原生開關不能用、燈暗、室內透視壞，{@code map_meta.bin} 讀到的建築狀態找不到對象，下次存檔就掉了）。
 * {@code CellLoader}、{@code LotHeader}、{@code ZoneGenerator} 也都把 priority 當成 {@code IsoLot.MapFiles} 的索引。
 * 客戶端拿伺服器送來的同一份清單（{@code ConnectionDetails.writeGameMap} 寫 {@code GameServer.gameMap}）
 * 跑同一段程式，所以兩邊一起壞。
 *
 * <p><b>手術</b>：{@code CreateStep1} 唯一的 {@code getLotDirectories()} 改道 {@link #getLotDirectories}：
 * 照原版呼叫一次，伺服器上用原版同一個判斷（{@code getDirectoryString("media/maps/<名稱>/")}＋
 * {@code isDirectory()}）找出不存在的資料夾，從回傳的清單拿掉，並把 {@code GameServer.gameMap}
 * （之後送給客戶端的就是它）換成剩下的清單，等於管理員把那一項從 {@code Map=} 拿掉；
 * ini 本身不改，資料夾補回來後下次開服照原樣。有缺的資料夾時印一行警告。
 * 客戶端不必裝東西：它收到的清單沒有那一項，原版程式就算出同樣的 priority。
 *
 * <p>只在 {@code GameServer.server} 時動作；全部都不存在時照原版（沒有剩下的地圖可給）。
 * kill switch {@code -Dmdc.mapFolderGuard=0|off}：照原版保留缺的項目，只印警告。需重啟。
 */
public final class MapFolderGuard {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.mapFolderGuard"));
    private static final String TAG = "[MinidoracatJavaPatch][MapFolderGuard] ";

    private MapFolderGuard() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** {@code IsoMetaGrid.CreateStep1} 內 {@code getLotDirectories()} 的改道目標。 */
    public static ArrayList<String> getLotDirectories(IsoMetaGrid grid) {
        ArrayList<String> dirs = grid.getLotDirectories();   // 原方法恰一次，在 try 外：例外照原版上拋
        if (!GameServer.server) {
            return dirs;
        }
        try {
            return dropMissing(dirs);
        } catch (RuntimeException | LinkageError e) {
            log("檢查地圖資料夾失敗，照原版：" + e);
            return dirs;
        }
    }

    private static ArrayList<String> dropMissing(ArrayList<String> dirs) {
        ArrayList<String> kept = new ArrayList<>(dirs.size());
        ArrayList<String> missing = new ArrayList<>();
        int behind = 0;   // 排在第一個缺的資料夾後面、原版會漏載建築的地圖數
        for (String dir : dirs) {
            if (exists(dir)) {
                kept.add(dir);
                if (!missing.isEmpty()) {
                    behind++;
                }
            } else {
                missing.add(dir);
            }
        }
        if (missing.isEmpty()) {
            log("Map= 的 " + dirs.size() + " 個地圖資料夾都在");
            return dirs;
        }
        String names = "media/maps/" + String.join("/、media/maps/", missing) + "/";
        if (!ENABLED || kept.isEmpty()) {
            log("Map= 有 " + missing.size() + " 個地圖資料夾不存在：" + names + "。"
                    + (kept.isEmpty() ? "沒有任何存在的地圖資料夾，照原版。"
                            : "-Dmdc.mapFolderGuard=off，照原版保留：排在它後面的 " + behind + " 張地圖建築不會載入。"));
            return dirs;
        }
        String before = GameServer.gameMap;
        String after = String.join(";", kept) + (kept.size() == 1 ? ";" : "");   // 一項時留 ';'：仍走原版的清單分支
        GameServer.gameMap = after;
        if (before != null && before.equals(Core.gameMap)) {
            Core.gameMap = after;   // 原版 getLotDirectories 剛把 Core.gameMap 設成 GameServer.gameMap
        }
        log("Map= 有 " + missing.size() + " 個地圖資料夾不存在：" + names + "。已從伺服器與送給客戶端的地圖清單拿掉，其餘 "
                + kept.size() + " 張地圖照常載入建築（其中 " + behind
                + " 張排在缺的資料夾後面，原版會漏載）。補回提供它的 MOD，或從 Map= 拿掉這一項。");
        return kept;
    }

    /** 與 CreateStep1 略過資料夾的判斷相同。 */
    private static boolean exists(String dir) {
        return new File(ZomboidFileSystem.instance.getDirectoryString("media/maps/" + dir + "/")).isDirectory();
    }

    private static void log(String msg) {
        try {
            DebugLog.log(TAG + msg);
        } catch (RuntimeException | LinkageError ignored) {
            // log 失敗不影響回傳值
        }
    }
}
