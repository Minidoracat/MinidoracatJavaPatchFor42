package zombie.network.packets;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;

import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.CharacterGender;
import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.WornItems;
import zombie.core.MdcTimedActionProbe;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.network.fields.character.Player;
import zombie.scripting.objects.ItemBodyLocation;

/**
 * W103 穿戴同步的伺服器端驗證（docs/patches.md 2dk）。
 *
 * <p>{@code SyncClothingPacket.processServer} 頭部 {@link #begin} 逐項看封包列的穿戴物：伺服器那位玩家的背包裡沒有這個 ID
 * 的那幾項（下稱無對應項）各記一行；{@code enforce} 另把它們從封包的清單拿掉，原版就不會處理、也不會轉發。只看背包、
 * 不看穿戴欄：原版處理一包的途中會改穿戴欄，背包不會變，事先查到的結果和原版輪到那一項時查到的一樣。
 *
 * <p>傷口與繃帶的顯示用物品是例外的一類：原版客戶端在本機建立它們，伺服器的背包本來就沒有，正常遊玩就會出現。種類在
 * {@link #BODY_MODELS} 表內、封包的位置也是原版穿它的那個位置的無對應項不逐項記，只累計，第一次出現與之後每 10 分鐘各記
 * 一行 {@code bodyModels}；兩種模式都留在清單、照原版處理（拿掉的話原版會把這些模型卸下，客戶端就看不到傷口與繃帶）。
 * 位置不對的不算這一類：原版照封包的位置穿，會動到別的穿戴欄。
 *
 * <p>開關：{@code -Dmdc.wornItemGuard=observe|enforce|off}，預設 observe（只記），要重啟。整包判斷完才動清單；判斷途中出
 * 例外時清單原封不動、這一包照原版處理（兩種模式都是），記 {@code anomaly} 行。
 */
public final class MdcWornItemGuard {
    private static final String TAG = "[MinidoracatJavaPatch][WornItemGuard]";

    static final int OFF = 0;
    static final int OBSERVE = 1;
    static final int ENFORCE = 2;
    static final int MODE = mode();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    private static final int TEXT_CAP = 64;
    private static final long BEAT_NS = 600_000_000_000L;

    /**
     * 原版替各身體部位的繃帶（乾淨、帶血）與咬傷、抓傷、割傷（各性別）建的顯示用物品種類 → 原版穿它的位置（物品腳本的
     * BodyLocation：繃帶 base:bandage、傷口 base:wound；腳本不在 jar 裡，換版時用實機 E2E 重驗）。列不出來就是空的（全部逐項記）。
     */
    private static final HashMap<String, ItemBodyLocation> BODY_MODELS = bodyModelTypes();

    /** {@code player}、{@code items} 是 private final；任一個找不到就整刀不動（首次生效行寫 reflect=missing）。 */
    private static final Field PLAYER = field("player");
    private static final Field ITEMS = field("items");

    // 封包處理在主迴圈單執行緒；計數與時間窗容忍罕見交錯。
    private static boolean announced;
    private static long unbacked;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;
    private static long bodyModels;
    private static long bodyModelsAtBeat;
    private static long beatNs;
    private static boolean beaten;

    private MdcWornItemGuard() {
    }

    /** {@code processServer} 頭部。不得把例外丟給原版。 */
    public static void begin(SyncClothingPacket packet, UdpConnection connection) {
        announce();
        if (MODE == OFF || PLAYER == null || ITEMS == null) {
            return;
        }
        try {
            IsoPlayer player = ((Player) PLAYER.get(packet)).getPlayer();
            @SuppressWarnings("unchecked")
            ArrayList<SyncClothingPacket.ItemDescription> items = (ArrayList<SyncClothingPacket.ItemDescription>) ITEMS.get(packet);
            ItemContainer bag = player.getInventory();
            WornItems wornItems = player.getWornItems();
            ArrayList<SyncClothingPacket.ItemDescription> found = null;
            for (SyncClothingPacket.ItemDescription d : items) {
                if (bag.getItemWithID(d.itemId) == null) {
                    if (found == null) {
                        found = new ArrayList<>();
                    }
                    found.add(d);
                }
            }
            if (found == null) {
                return;
            }
            // 顯示用物品：封包報的種類在表內、位置是原版穿它的位置；那個 ID 已經穿著時，穿著的那件也要是表內的種類
            //（穿著非顯示用物品時，原版留著它或照封包重建，都不算顯示用物品）
            boolean[] worn = new boolean[found.size()];
            boolean[] model = new boolean[found.size()];
            ArrayList<SyncClothingPacket.ItemDescription> refused = MODE == ENFORCE ? new ArrayList<>(found.size()) : null;
            for (int i = 0; i < worn.length; i++) {
                SyncClothingPacket.ItemDescription d = found.get(i);
                InventoryItem wornNow = wornItems.getItemById(d.itemId);
                ItemBodyLocation home = BODY_MODELS.get(d.itemType);
                worn[i] = wornNow != null;
                // 位置物件是登錄表的單例（封包 parse 用 ItemBodyLocation.get 取），所以用 == 比
                model[i] = home != null && home == d.location && (wornNow == null || BODY_MODELS.containsKey(wornNow.getFullType()));
                if (!model[i] && refused != null) {
                    refused.add(d);
                }
            }
            // 判斷到這裡都沒出例外才動清單與計數。顯示用物品不拿掉：少了它原版會把傷口與繃帶的模型卸下
            if (refused != null) {
                items.removeAll(refused);
            }
            for (int i = 0; i < worn.length; i++) {
                if (model[i]) {
                    bodyModels++;
                    continue;
                }
                unbacked++;
                report(connection, found.get(i), worn[i]);
            }
            beat();
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            anomalies++;
            reportAnomaly(e);
        }
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        try {
            DebugLog.log(TAG + " 首次生效 mode=" + (MODE == ENFORCE ? "enforce" : MODE == OBSERVE ? "observe" : "off")
                    + " bodyModelTypes=" + BODY_MODELS.size() + " reflect=" + (PLAYER != null && ITEMS != null ? "ok" : "missing") + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void report(UdpConnection connection, SyncClothingPacket.ItemDescription d, boolean worn) {
        try {
            if (!allowLine()) {
                return;
            }
            DebugLog.log(TAG + " unbacked type=" + clean(d.itemType) + " id=" + d.itemId + " loc=" + clean(String.valueOf(d.location))
                    + " worn=" + (worn ? 1 : 0) + " action=" + (MODE == ENFORCE ? "removed" : "logged")
                    + " connectionPlayers=" + (connection != null ? MdcTimedActionProbe.connectionPlayers(connection) : "?")
                    + " unbacked=" + unbacked + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 顯示用物品只累計：第一次出現記一行，之後有新增時每 10 分鐘最多一行。 */
    private static void beat() {
        if (bodyModels == bodyModelsAtBeat) {
            return;
        }
        long now = System.nanoTime();
        if (beaten && now - beatNs < BEAT_NS) {
            return;
        }
        beaten = true;
        beatNs = now;
        bodyModelsAtBeat = bodyModels;
        try {
            DebugLog.log(TAG + " bodyModels total=" + bodyModels + " action=counted"
                    + " unbacked=" + unbacked + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static HashMap<String, ItemBodyLocation> bodyModelTypes() {
        HashMap<String, ItemBodyLocation> out = new HashMap<>();
        try {
            for (BodyPartType part : BodyPartType.values()) {
                String bandage = part.getBandageModel();
                if (bandage != null && !bandage.isBlank()) {
                    out.put(bandage, ItemBodyLocation.BANDAGE);
                    out.put(bandage + "_Blood", ItemBodyLocation.BANDAGE);
                }
                for (CharacterGender gender : CharacterGender.values()) {
                    for (String wound : new String[] {part.getBiteWoundModel(gender), part.getScratchWoundModel(gender), part.getCutWoundModel(gender)}) {
                        if (wound != null && !wound.isBlank()) {
                            out.put(wound, ItemBodyLocation.WOUND);
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            out.clear();
        }
        return out;
    }

    private static void reportAnomaly(Throwable cause) {
        try {
            if (allowLine()) {
                DebugLog.log(TAG + " anomaly reason=" + cause.getClass().getSimpleName()
                        + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 物品種類是客戶端給的字串：進 log 前截短、只留不會斷行或混淆欄位的字元。 */
    static String clean(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), TEXT_CAP));
        for (int i = 0; i < text.length() && i < TEXT_CAP; i++) {
            char ch = text.charAt(i);
            out.append(ch < 128 && (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-' || ch == ':') ? ch : '?');
        }
        return out.toString();
    }

    private static boolean allowLine() {
        long now = System.nanoTime();
        if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed++;
            return false;
        }
        windowCount++;
        return true;
    }

    private static Field field(String name) {
        try {
            Field f = SyncClothingPacket.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static int mode() {
        String mode = System.getProperty("mdc.wornItemGuard", "observe").trim();
        if ("0".equals(mode) || "off".equalsIgnoreCase(mode)) {
            return OFF;
        }
        return "enforce".equalsIgnoreCase(mode) ? ENFORCE : OBSERVE;
    }

    // ---- 測試存取器 ----

    static long unbackedForTest() {
        return unbacked;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static long bodyModelsForTest() {
        return bodyModels;
    }

    static boolean isBodyModelForTest(String type) {
        return BODY_MODELS.containsKey(type);
    }
}
