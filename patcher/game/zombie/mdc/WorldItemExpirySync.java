package zombie.mdc;

import java.util.ArrayList;
import java.util.function.Predicate;

import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameServer;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;

/**
 * W43 地面物品過期清除時機同步（2026-09-27；docs/patches.md 2bg）。
 *
 * <p><b>症狀</b>：畜牧場的糞便／羽毛／蛋「撿不起來」——讀條走滿後空等約 10 秒、東西沒進背包，
 * 同一件重試多次都一樣；玩家重登後那些物品就消失。伺服器 log 每次成對印
 * {@code ERROR: sendItemsToContainer: can't find world item with id=…}（parse 一次、
 * {@code Transaction.update} 一次）。這些是 client 畫面上存在、伺服器上已不存在的幽靈物品。
 *
 * <p><b>根因</b>（兩個原版行為疊加）：
 * <ol>
 *   <li>{@code IsoGridSquare.load} 在載入時丟掉過期的地面物品（{@code WorldItemRemovalList}
 *       ＋{@code HoursForWorldItemRemoval}），且<b>沒有 {@code GameClient.client} 守衛</b>
 *       （patches.md 2n 受精蛋案已證實 client 收 chunk 與讀本機快取都走這段）。伺服器只在
 *       自己載入 chunk 時清；chunk 一直載著，過期物品就一直留在記憶體裡。client 每次載入都清
 *       ⇒ 同一格兩邊的 {@code objects} 清單長度不同。</li>
 *   <li>伺服器移除地面物件時送的 {@code RemoveItemFromSquare} 只帶<b>物件序號</b>
 *       （{@code RemoveItemFromSquarePacket.set/processClient}），client 按序號刪 ⇒ 清單錯位時
 *       刪到別的物件或超出範圍直接略過（非 debug 不留 log）；被撿走的那件就留在 client 畫面上。</li>
 * </ol>
 * 正式服 {@code DayLength=3}（1 小時一天）＋ {@code HoursForWorldItemRemoval=24}＝現實約 1 小時
 * 就過期，清單又含全部 {@code Dung_*}、兩種羽毛與 {@code Egg}，畜牧區因此特別容易觸發。
 *
 * <p><b>修法</b>：client 取得「伺服器已載入 chunk」資料的唯一出口是
 * {@code PlayerDownloadServer.update()} 內的 {@code IsoChunk.SaveLoadedChunk}（W4-1 已改道到
 * {@link ChunkRequestPacker#saveLoadedChunk}）。序列化之前，伺服器以與 {@code IsoGridSquare.load}
 * 逐項相同的條件，把這個 chunk 的過期地面物品經原版 {@code GameServer.RemoveItemFromMap}
 * 移除——已載入這格的其他 client 收到的序號與伺服器一致，下載中的 client 拿到的資料本來就
 * 沒有這些物品，自己的載入清除也不會再多刪 ⇒ 兩邊清單一致。方向與受精蛋案相反：伺服器
 * 採用 client 本來就會套用的結果，玩家可見的清除規則不變。
 *
 * <p><b>時鐘邊界</b>：client 載入時間晚於伺服器序列化，途中到期的物品會被 client 多刪，
 * 所以伺服器提前 {@code -Dmdc.worldItemExpiry.marginHours}（預設 1 遊戲小時，clamp 0..24）清。
 *
 * <p>只在主執行緒（{@code PlayerDownloadServer.update}）執行。例外紀律：只吞
 * {@code RuntimeException}（計 anomalies，序列化照常進行，等同原版）；{@code Error}／
 * {@code LinkageError} 外逃＝jar 不相容時 fail-fast。
 * kill switch：{@code -Dmdc.worldItemExpiry=0|off}（回原版：伺服器不清、client 照清）。
 */
public final class WorldItemExpirySync {

    private static final String TAG = "[MinidoracatJavaPatch][WorldItemExpiry] ";
    private static final long LOG_INTERVAL_NS = 300_000_000_000L;
    private static final int MAX_ANOMALY_LOGS = 3;

    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.worldItemExpiry"));
    static final double MARGIN_HOURS = parseMargin(System.getProperty("mdc.worldItemExpiry.marginHours"));

    // ---- 計數（主執行緒單寫）----
    private static long chunks;
    private static long removed;
    private static long removedThisBeat;
    private static int maxPerChunk;
    private static long anomalies;
    private static long lastLogNs;
    private static boolean bannerShown;

    private WorldItemExpirySync() {}

    /** {@link ChunkRequestPacker#saveLoadedChunk} 序列化前呼叫。 */
    public static void beforeSend(IsoChunk chunk) {
        if (!ENABLED || chunk == null) {
            return;
        }
        try {
            int n = purge(chunk, GameTime.getInstance().getWorldAgeHours() + MARGIN_HOURS);
            chunks++;
            removed += n;
            removedThisBeat += n;
            if (n > maxPerChunk) {
                maxPerChunk = n;
            }
            long now = System.nanoTime();
            if (!bannerShown) {
                bannerShown = true;
                lastLogNs = now;
                DebugLog.log(TAG + "首次生效 marginHours=" + MARGIN_HOURS
                        + "（-Dmdc.worldItemExpiry=0|off 回原版；-Dmdc.worldItemExpiry.marginHours 0..24）");
            } else if (now - lastLogNs >= LOG_INTERVAL_NS) {
                lastLogNs = now;
                DebugLog.log(TAG + "beat chunks=" + chunks + " removed=" + removed
                        + " removedSinceLast=" + removedThisBeat + " maxPerChunk=" + maxPerChunk
                        + " anomalies=" + anomalies + ".");
                removedThisBeat = 0;
            }
        } catch (RuntimeException e) {
            anomalies++;
            if (anomalies <= MAX_ANOMALY_LOGS) {
                DebugLog.log(TAG + "anomaly#" + anomalies + " chunk=" + chunk.wx + "," + chunk.wy + " " + e);
            }
        }
    }

    /** 移除 chunk 內所有會被 client 載入清掉的地面物品，回傳移除數。 */
    static int purge(IsoChunk chunk, double clientNowHours) {
        SandboxOptions sandbox = SandboxOptions.instance;
        double hours = sandbox.hoursForWorldItemRemoval.getValue();
        boolean blacklist = sandbox.itemRemovalListBlacklistToggle.getValue();
        Predicate<String> listed = sandbox::worldItemRemovalListContains;
        int n = 0;
        for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
            for (int i = 0; i < 64; i++) {
                IsoGridSquare sq = chunk.getGridSquare(i & 7, i >> 3, z);
                if (sq == null) {
                    continue;
                }
                ArrayList<IsoWorldInventoryObject> wos = sq.getWorldObjects();
                // 倒序：RemoveItemFromMap→removeFromSquare 會從 worldObjects 移除本項
                for (int j = wos.size() - 1; j >= 0; j--) {
                    if (j >= wos.size()) {
                        continue;
                    }
                    IsoWorldInventoryObject wo = wos.get(j);
                    if (wo != null && droppedOnLoad(wo, hours, blacklist, listed, clientNowHours)) {
                        GameServer.RemoveItemFromMap(wo);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** {@code IsoGridSquare.load} 對地面物品的丟棄條件（反編譯 42.20.4 :3272-3301）。 */
    static boolean droppedOnLoad(IsoWorldInventoryObject wo, double hours, boolean blacklist,
            Predicate<String> listed, double nowHours) {
        InventoryItem item = wo.getItem();
        if (item == null) {
            return true;
        }
        String type = item.getFullType();
        Item script = ScriptManager.instance.FindItem(type);
        return expired(type, script != null && script.getObsolete(), wo.dropTime,
                wo.isIgnoreRemoveSandbox(), hours, blacklist, listed, nowHours);
    }

    /**
     * 純判定（測試直接驗）。逐項照抄原版，包含它的怪處：以 {@code type.split("_")[0]} 比對的
     * 兩個分支<b>不</b>檢查 {@code dropTime > -1} 與 {@code hours > 0}。
     */
    static boolean expired(String type, boolean obsolete, double dropTime, boolean ignoreRemoveSandbox,
            double hours, boolean blacklist, Predicate<String> listed, double nowHours) {
        if (obsolete) {
            return true;
        }
        String prefix = type.split("_")[0];
        boolean inScope = dropTime > -1.0 && hours > 0.0
                        && (!blacklist && listed.test(type) || blacklist && !listed.test(type))
                || !blacklist && listed.test(prefix)
                || blacklist && !listed.test(prefix);
        return inScope && !ignoreRemoveSandbox && nowHours > dropTime + hours;
    }

    static boolean parseEnabled(String v) {
        return v == null || !("0".equals(v.trim()) || "off".equalsIgnoreCase(v.trim()));
    }

    static double parseMargin(String v) {
        if (v == null) {
            return 1.0;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isNaN(d) ? 1.0 : Math.max(0.0, Math.min(24.0, d));
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }
}
