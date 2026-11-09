package zombie.mdc;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.network.fields.character.ConnectionPlayer;

/**
 * W20 衣物同步觀測（(c) SyncVisuals mismatch；docs/patches.md 2ah）。
 *
 * <p>背景（8/28 當輪 68 分鐘，errors.txt 指紋）：`SyncVisualsPacket.parse > Player h...` ×129。
 * (b) `SyncClothingPacket$ItemDescription` ctor 的 tint NPE 已於 42.22.2 由原版修掉（visual 為 null 時
 * baseTexture/textureChoice 給 -1、tint 為 null 時補 `ImmutableColor.white`，與本刀 enforce 相同），
 * (b)／W20-2 隨之退役（2026-11-10 42.22.2 對版）。
 *
 * <p><b>(c) visuals mismatch</b>：`SyncVisualsPacket.parse` 以 server 本地 player 重建
 * itemVisuals、與 wire count 不符時整包 return（server console 的 "Player has X ... sync Y"
 * ＝ server 本地 X、client 宣稱 Y；129 條全是整輪視覺同步丟棄）。SyncVisuals 是<b>純
 * positional 協定</b>（wire 無 item id/type/location）——「跳過異常項」「clamp 到
 * min(count)」都會把洞/血/condition 套到錯的衣服，vanilla 整包拒絕反而是安全行為，
 * <b>故只觀測不 enforce</b>。觀測＝redirect 該 error callsite 換成資訊超集行
 * （原訊息＋player＋signed diff），並 redirect parse 內唯一的 `ConnectionPlayer.getPlayer()`
 * 捕獲當前 parse 的玩家。假說（可證偽）：server 端 visual 為 null 的 worn item 讓
 * `WornItems.getItemVisuals` 少算 1（該方法跳過 null visual）⇒ wire-local=+1。
 *
 * <p>例外紀律：簿記自身故障 anomalies++ 不擋 vanilla；`LinkageError` 外逃＝fail-fast。
 *
 * <p>kill switch：{@code -Dmdc.visualsMismatchProbe}＝{@code 0|off}（error 直通）／{@code 2|observe}
 * （預設；1 與未知值一律落回 observe）。
 */
public final class ClothingSyncGuard {
    private static final String TAG = "[MinidoracatJavaPatch][ClothingSyncGuard]";

    static final int MODE_OFF = 0;
    static final int MODE_OBSERVE = 2;

    static final int MISMATCH_MODE = parseMode("mdc.visualsMismatchProbe");

    /** parse（SyncVisualsPacket.parse）語境的玩家名。 */
    private static final ThreadLocal<String> PARSE_PLAYER = new ThreadLocal<>();

    // 主迴圈單寫；觀測刀容忍罕見交錯。
    private static long mismatches;
    private static long wirePlus;
    private static long wireMinus;
    private static long wireOther;
    private static long anomalies;
    private static boolean bannerShown;

    private ClothingSyncGuard() {
    }

    /** redirect 目標：SyncVisualsPacket.parse 內唯一的 ConnectionPlayer.getPlayer()（42.22 起存進區域變數）——捕獲 parse 對象。 */
    public static IsoPlayer parsePlayer(ConnectionPlayer connectionPlayer) {
        IsoPlayer player = connectionPlayer.getPlayer();
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

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mismatchMode=" + MISMATCH_MODE
                + "（-Dmdc.visualsMismatchProbe=0|off/2|observe 預設）.");
    }

    /** 二態解析：0|off 關閉，其餘（含 1 與未知值）落回 observe（家族慣例）。 */
    private static int parseMode(String key) {
        String raw = System.getProperty(key);
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            default:
                return MODE_OBSERVE;
        }
    }

    // ---- 測試存取器 ----

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

    static String parsePlayerForTest() {
        return PARSE_PLAYER.get();
    }
}
