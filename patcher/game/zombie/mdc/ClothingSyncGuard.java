package zombie.mdc;

import zombie.characters.IsoPlayer;
import zombie.core.ImmutableColor;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.network.fields.character.PlayerID;

/**
 * W20 衣物同步守衛（(b) tint 修復＋(c) mismatch 觀測；docs/patches.md 2ah）。
 *
 * <p>背景（8/28 當輪 68 分鐘，errors.txt 指紋）：`INetworkPacket.send> Exception thrown`
 * ×362（(a)(b) 合計、per-connection 放大）＋`SyncVisualsPacket.parse > Player h...` ×129。
 *
 * <p><b>(b) tint NPE</b>：`SyncClothingPacket$ItemDescription` 帶參 ctor 對
 * baseTexture/textureChoice 都有 `getVisual()==null ? -1 :` 守衛（javap offset 39-87），
 * <b>唯獨 tint 直呼 `getVisual().getTint()`（offset 91-101）漏守衛</b>——某玩家穿戴一件
 * server 端 `getVisual()` 回 null 的物品（`InventoryItem.getVisual` 於 clothing asset
 * 不存在/未 ready 時清成 null）時，該玩家的每次 SyncClothing 廣播對<b>每條</b> connection
 * 各炸一次（sendToAll per-connection setData）＝該玩家衣物同步黏性全滅。
 * 手術＝redirect `getTint()` → {@link #tintOf}（1:1 同形）。
 * <b>禁止改成「lambda 過濾整件 item」</b>：`SyncClothingPacket.process` 會把封包未列出的
 * worn item 從遠端 `WornItems.remove`（javap offset 88-89 語境）＝把 asset 暫未 ready
 * 解讀成脫衣（codex lane 2026-08-28 review 定案）。enforce 回 `ImmutableColor.white`
 * 只保序列化存活（transport liveness），不宣稱端到端根治——接收端 process 仍有
 * `getVisual().setTint` 假設，asset-null 根因分佈待 observe。
 *
 * <p><b>(c) visuals mismatch</b>：`SyncVisualsPacket.parse` 以 server 本地 player 重建
 * itemVisuals、與 wire count 不符時整包 return（server console 的 "Player has X ... sync Y"
 * ＝ server 本地 X、client 宣稱 Y；129 條全是整輪視覺同步丟棄）。SyncVisuals 是<b>純
 * positional 協定</b>（wire 無 item id/type/location）——「跳過異常項」「clamp 到
 * min(count)」都會把洞/血/condition 套到錯的衣服，vanilla 整包拒絕反而是安全行為，
 * <b>故 (c) 只觀測不 enforce</b>。觀測＝redirect 該 error callsite 換成資訊超集行
 * （原訊息＋player＋signed diff），並 redirect parse 內 3 處 `PlayerID.getPlayer()`
 * 捕獲當前 parse 的玩家。共同根因假說（可證偽）：同一件 null-visual worn item 同時讓
 * (b) ctor 炸、讓 `WornItems.getItemVisuals` 少算 1（該方法跳過 null visual）⇒ (c) 的
 * wire-local=+1——observe 比對 (b) 的 player 與 (c) 的 player＋diff 符號即可定罪或排除
 * （MirageWardrobe 歸因也依此，8/17 起裝服）。
 *
 * <p>例外紀律：簿記自身故障 anomalies++ 不擋 vanilla；`LinkageError` 外逃＝fail-fast。
 * (b) observe 模式記錄後拋 NPE <b>保持 vanilla 失敗語意</b>（同樣被 `INetworkPacket.send`
 * :130 的 per-connection catch 吞掉，行為零差、log 指紋換成可歸因版）。
 *
 * <p>kill switch（兩刀分離、獨立降級，比照 W10）：
 * {@code -Dmdc.clothingTintGuard}＝{@code 0|off}（直通 vanilla NPE）／{@code 1|enforce}
 * （null→white 修復）／{@code 2|observe}（預設：記錄＋拋 NPE 保語意）；
 * {@code -Dmdc.visualsMismatchProbe}＝{@code 0|off}（error 直通）／{@code 2|observe}
 * （預設；1＝observe-alias）。未知值一律落回 observe。
 */
public final class ClothingSyncGuard {
    private static final String TAG = "[MinidoracatJavaPatch][ClothingSyncGuard]";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int TINT_MODE = parseMode("mdc.clothingTintGuard", true);
    static final int MISMATCH_MODE = parseMode("mdc.visualsMismatchProbe", false);

    /** rate limit：每 10 秒窗最多 20 行詳細記錄（(b) 是 per-connection 放大源，必須設限）。 */
    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;

    /** 組包（SyncClothingPacket.set）與 parse（SyncVisualsPacket.parse）語境的玩家名。 */
    private static final ThreadLocal<String> CLOTHING_PLAYER = new ThreadLocal<>();
    private static final ThreadLocal<String> PARSE_PLAYER = new ThreadLocal<>();

    // 主迴圈單寫；觀測刀容忍罕見交錯。
    private static long tintCalls;
    private static long nullVisual;
    private static long nullTint;
    private static long repaired;
    private static long mismatches;
    private static long wirePlus;
    private static long wireMinus;
    private static long wireOther;
    private static long logged;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;
    private static boolean bannerShown;

    private ClothingSyncGuard() {
    }

    /** headCall（slot 1）目標：SyncClothingPacket.set(IsoPlayer) 頭部——記錄組包對象。 */
    public static void onClothingSet(IsoPlayer player) {
        try {
            CLOTHING_PLAYER.set(player == null ? "?" : player.getUsername());
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /**
     * redirect 目標：ItemDescription ctor 內唯一 `ItemVisual.getTint()`（receiver 前置）。
     * off＝直通（visual null 時本行拋 NPE，與 vanilla offset 98 同語意、同樣被 send 的
     * per-connection catch 吞掉）；observe＝記錄後拋 NPE（保 vanilla 失敗語意）；
     * enforce＝visual null 或 tint null 都回 white（保序列化存活）。
     */
    public static ImmutableColor tintOf(ItemVisual visual) {
        if (TINT_MODE == MODE_OFF) {
            return visual.getTint();
        }
        try {
            tintCalls++;
            if (!bannerShown) {
                showBanner();
            }
        } catch (RuntimeException e) {
            anomalies++;
        }
        if (visual == null) {
            String player = "?";
            try {
                nullVisual++;
                player = String.valueOf(CLOTHING_PLAYER.get());
                if (allowLine()) {
                    DebugLog.log(TAG + " nullVisual#" + nullVisual + " player=" + player
                            + (TINT_MODE == MODE_ENFORCE ? " action=white" : " action=vanilla-npe")
                            + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
                }
            } catch (RuntimeException e) {
                anomalies++;
            }
            if (TINT_MODE == MODE_ENFORCE) {
                repaired++;
                return ImmutableColor.white;
            }
            throw new NullPointerException(TAG + " getVisual() null（observe：與 vanilla 等價的"
                    + "序列化失敗，player=" + player + "；-Dmdc.clothingTintGuard=1 可修復）");
        }
        ImmutableColor tint = visual.getTint();
        if (tint == null) {
            try {
                nullTint++;
                if (allowLine()) {
                    DebugLog.log(TAG + " nullTint#" + nullTint + " player=" + CLOTHING_PLAYER.get()
                            + " item=" + describeItem(visual)
                            + (TINT_MODE == MODE_ENFORCE ? " action=white" : " action=vanilla-null")
                            + ".");
                }
            } catch (RuntimeException e) {
                anomalies++;
            }
            if (TINT_MODE == MODE_ENFORCE) {
                repaired++;
                return ImmutableColor.white;
            }
        }
        return tint;
    }

    /** redirect 目標：SyncVisualsPacket.parse 內 3 處 PlayerID.getPlayer()——捕獲 parse 對象。 */
    public static IsoPlayer parsePlayer(PlayerID playerId) {
        IsoPlayer player = playerId.getPlayer();
        try {
            PARSE_PLAYER.set(player == null ? "?" : player.getUsername());
        } catch (RuntimeException e) {
            anomalies++;
        }
        return player;
    }

    /**
     * redirect 目標：SyncVisualsPacket.parse 的 mismatch error callsite。
     * observe＝以資訊超集行取代原行（原訊息＋player＋wire-local signed diff＋序號），
     * error 級別與 DebugType 不變；off＝原樣直通。整包 return 的 vanilla 行為不受影響
     * （本 redirect 只換 log 呼叫本身）。
     */
    public static void onVisualsMismatch(DebugType type, Object message) {
        String out = String.valueOf(message);
        if (MISMATCH_MODE != MODE_OFF) {
            try {
                mismatches++;
                if (!bannerShown) {
                    showBanner();
                }
                long[] counts = parseCounts(out);
                String diffText = "?";
                if (counts != null) {
                    long diff = counts[1] - counts[0];
                    diffText = (diff > 0 ? "+" : "") + diff;
                    if (diff > 0) {
                        wirePlus++;
                    } else if (diff < 0) {
                        wireMinus++;
                    } else {
                        wireOther++;
                    }
                } else {
                    wireOther++;
                }
                out = out + " " + TAG + " mismatch#" + mismatches
                        + " player=" + PARSE_PLAYER.get()
                        + " wireMinusLocal=" + diffText
                        + " plus=" + wirePlus + " minus=" + wireMinus + " other=" + wireOther
                        + " anomalies=" + anomalies + ".";
            } catch (RuntimeException e) {
                anomalies++;
                out = String.valueOf(message);
            }
        }
        type.error(out);
    }

    /**
     * 從 vanilla 訊息「Player has X itemVisuals but server tries to sync Y ones」抽
     * {local X, wire Y}；格式不符回 null（TIS 改字串時 wireOther 計數提醒重驗）。
     */
    static long[] parseCounts(String message) {
        try {
            long local = numberAfter(message, "Player has ");
            long wire = numberAfter(message, " sync ");
            if (local < 0 || wire < 0) {
                return null;
            }
            return new long[]{local, wire};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long numberAfter(String message, String prefix) {
        int at = message.indexOf(prefix);
        if (at < 0) {
            return -1L;
        }
        int i = at + prefix.length();
        long value = -1L;
        while (i < message.length()) {
            char c = message.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = (value < 0 ? 0 : value) * 10 + (c - '0');
            i++;
        }
        return value;
    }

    private static String describeItem(ItemVisual visual) {
        try {
            return visual.getInventoryItem() == null ? "?" : visual.getInventoryItem().getFullType();
        } catch (RuntimeException e) {
            return "?";
        }
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
        logged++;
        return true;
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 tintMode=" + TINT_MODE + " mismatchMode=" + MISMATCH_MODE
                + "（-Dmdc.clothingTintGuard=0|off/1|enforce(null→white)/2|observe 預設；"
                + "-Dmdc.visualsMismatchProbe=0|off/2|observe 預設）.");
    }

    /** 三態解析：文字別名＋未知值落回 observe（家族慣例）。enforceable=false 時 1 為 observe-alias。 */
    private static int parseMode(String key, boolean enforceable) {
        String raw = System.getProperty(key);
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "enforce":
                return enforceable ? MODE_ENFORCE : MODE_OBSERVE;
            case "2":
            case "observe":
            default:
                return MODE_OBSERVE;
        }
    }

    // ---- 測試存取器 ----

    static long tintCallsForTest() {
        return tintCalls;
    }

    static long nullVisualForTest() {
        return nullVisual;
    }

    static long nullTintForTest() {
        return nullTint;
    }

    static long repairedForTest() {
        return repaired;
    }

    static long mismatchesForTest() {
        return mismatches;
    }

    static long wirePlusForTest() {
        return wirePlus;
    }

    static long wireMinusForTest() {
        return wireMinus;
    }

    static long wireOtherForTest() {
        return wireOther;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static long suppressedForTest() {
        return suppressed;
    }

    static String clothingPlayerForTest() {
        return CLOTHING_PLAYER.get();
    }

    static String parsePlayerForTest() {
        return PARSE_PLAYER.get();
    }

    static void resetWindowForTest() {
        windowStartNs = 0L;
        windowCount = 0;
    }
}
