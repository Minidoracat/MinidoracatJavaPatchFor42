package zombie.mdc;

import java.util.ArrayList;

import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.world.moddata.GlobalModData;

/**
 * W19 車輛永久移除授權守衛（observe 先行；docs/patches.md 2ag）。
 *
 * <p>背景：vanilla {@code VehicleCommands.lua:359-366} 的 {@code Commands.remove} 直呼
 * {@code vehicle:permanentlyRemove()} 且無任何權限檢查（同檔 {@code repairPart} 有
 * {@code checkPermissions(player, Capability.UseMechanicsCheat)}）；Java 側
 * {@code GameServer.receiveClientCommand} 對 vehicle/remove 雖有形式閘，但其中
 * {@code NetworkPlayerAI.isDismantleAllowed()} 恆回 {@code true}＝實質全放行（42.20.4
 * javap 實證）。另一條玩家路徑 {@code ISRemoveBurntVehicle.lua:135} 是 server 端 timed
 * action 的 {@code complete()} 直呼，<b>不經 Commands.remove</b>——2026-08-23 Player-F 案
 * 三輛未認領完好車被永久刪除（vehicles.db 整列 DELETE）走的正是這條。移除拆車 MOD 只
 * 關閉選單、不關閉底層能力，故收斂點只能是 Java 咽喉 {@code permanentlyRemove} 本身。
 *
 * <p>掛點：{@code BaseVehicle.permanentlyRemove()V} 頭部 headCall（{@code ALOAD 0 →
 * INVOKESTATIC onRemove}，與 W15 preupdate 同機制）。jar-wide census（SmokeCheck 釘死）：
 * {@code invokevirtual permanentlyRemove} 全 jar 恰 4 處——{@code LuaManager$GlobalObject
 * .removeVehicle}（有 {@code !GameServer.server} 守衛＝dedicated server 死路徑）、
 * {@code RandomizedWorldBase}（世界事件清理）、{@code VehicleManager.removeVehicles}
 * （admin {@code /remove vehicles} 批次）、{@code BaseVehicle.setSmashed}（換殼重建）。
 * Lua 端另有 Commands.remove（client command：admin onCheatRemove 與任意玩家 command 同路）
 * 與 ISRemoveBurntVehicle（timed action）。
 *
 * <p><b>本版純 observe、不 enforce</b>：授權判定需要 (requester, vehicle) 對，而本咽喉點
 * 只有 vehicle——requester 藏在 Lua 層（client command 的 player／timed action 的
 * character），三方審查（codex/grok lane，2026-08-28）一致認定身分橋未定案前 enforce
 * 必然誤殺（admin 刪完好車與惡意刪車走同一條 command；純車況規則會擋 setSmashed 換殼與
 * admin 批次清理）。observe 目標＝量化「合法刪除頻率與 caller 分佈」，供 enforce 設計。
 *
 * <p>每事件一行 log（低頻刀；rate limit 防未知高頻迴圈刷版）：vehicle id／script／座標／
 * MVCK 認領狀態／Java caller frame／是否經 Kahlua（Lua 驅動）／最近玩家。認領語意
 * （MVCK 42.15 源碼實證）：車輛 modData 的 {@code SQLID} 只是 imprint 印記——
 * <b>unclaim 不清印記</b>，SQLID 存在≠仍認領；owner 真相在 Global ModData 表
 * {@code MVCKByVehicleSQLID}（key=SQLID → {@code OwnerPlayerID}）。故狀態分五類：
 * {@code unclaimed}（無印記＝從未認領）／{@code stale-imprint}（有印記、表無條目＝已解除）／
 * {@code claimed:<owner>}／{@code no-mvck-table}（MOD 未裝或未初始化）／{@code unknown-*}
 * （讀取失敗——記錄而非靜默放行，比照提示詞要求）。
 *
 * <p>例外紀律（家族慣例）：主 try 只 catch {@code RuntimeException}（觀測簿記自身故障
 * 不得擋刪車，anomalies++ 後放行）；{@code LinkageError} 一律外逃＝fail-fast。
 *
 * <p>三態 {@code -Dmdc.vehicleRemoveGuard}：{@code 0|off}／{@code 1|enforce}（<b>本版
 * observe-alias</b>，比照 W16——enforce 條件待 observe 數據定案）／{@code 2|observe}
 * （預設；未知值落回 observe）。
 */
public final class VehicleRemoveGuard {
    private static final String TAG = "[MinidoracatJavaPatch][VehicleRemoveGuard]";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();

    /** MVCK 認領庫（Global ModData）表名與車輛 modData 印記 key（MVCKServer.lua:53/90 實證）。 */
    static final String MVCK_TABLE = "MVCKByVehicleSQLID";
    static final String MVCK_IMPRINT_KEY = "SQLID";
    static final String MVCK_OWNER_KEY = "OwnerPlayerID";

    /** 近距玩家清單的半徑（squares）與名單上限（log 行寬控制）。 */
    private static final double NEAR_RADIUS = 32.0;
    private static final int NEAR_CAP = 3;

    /** rate limit：每 10 秒窗最多 20 行完整記錄；超限只累計 suppressed（防未知高頻迴圈刷版）。 */
    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;

    // 主迴圈單寫（command 處理／timed action／世界事件都在主迴圈）；觀測刀容忍罕見交錯。
    private static long calls;
    private static long logged;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;
    private static boolean bannerShown;

    private VehicleRemoveGuard() {
    }

    /** headCall 目標：permanentlyRemove 頭部（vanilla 任何一行執行前）。 */
    public static void onRemove(BaseVehicle vehicle) {
        if (MODE == MODE_OFF) {
            return;
        }
        try {
            calls++;
            if (!bannerShown) {
                showBanner();
            }
            long now = System.nanoTime();
            if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
                windowStartNs = now;
                windowCount = 0;
            }
            if (windowCount >= WINDOW_CAP) {
                suppressed++;
                return;
            }
            windowCount++;
            logged++;

            String script;
            String pos;
            String claim;
            try {
                script = String.valueOf(vehicle.getScriptName());
                pos = Math.round(vehicle.getX()) + "," + Math.round(vehicle.getY());
                claim = claimStateOf(vehicle.getModData());
            } catch (RuntimeException e) {
                script = "?";
                pos = "?";
                claim = "unknown-vehicle:" + e.getClass().getSimpleName();
            }

            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            String caller = firstForeignFrame(stack);
            boolean lua = luaSeen(stack);

            DebugLog.log(TAG + " remove#" + calls
                    + " vid=" + safeId(vehicle)
                    + " script=" + script
                    + " pos=" + pos
                    + " claim=" + claim
                    + " caller=" + caller
                    + " lua=" + lua
                    + " " + nearbyPlayers(vehicle)
                    + " suppressed=" + suppressed
                    + " anomalies=" + anomalies
                    + " mode=" + MODE + ".");
        } catch (RuntimeException e) {
            // 觀測簿記自身故障不得擋 vanilla 刪車；LinkageError 刻意不接（外逃=fail-fast）。
            anomalies++;
        }
    }

    /**
     * MVCK 認領狀態（null 安全、任何一步失敗都回可辨識字串而非拋出）。
     * 印記語意：SQLID 存在只代表「曾被 imprint」；owner 真相在 Global ModData 表。
     */
    static String claimStateOf(KahluaTable modData) {
        try {
            if (modData == null) {
                return "no-moddata";
            }
            Object sqlid = modData.rawget(MVCK_IMPRINT_KEY);
            if (sqlid == null) {
                return "unclaimed";
            }
            GlobalModData gmd = GlobalModData.instance;
            if (gmd == null) {
                return "unknown-gmd";
            }
            KahluaTable byVehicle = gmd.get(MVCK_TABLE);
            if (byVehicle == null) {
                return "no-mvck-table";
            }
            Object entry = byVehicle.rawget(sqlid);
            if (entry == null) {
                return "stale-imprint";
            }
            if (entry instanceof KahluaTable) {
                Object owner = ((KahluaTable) entry).rawget(MVCK_OWNER_KEY);
                return "claimed:" + owner;
            }
            return "claimed:non-table";
        } catch (RuntimeException e) {
            return "unknown-claim:" + e.getClass().getSimpleName();
        }
    }

    /**
     * 第一個「非本刀、非咽喉自身、非 Thread 基礎設施」的 frame——即 permanentlyRemove 的
     * 直接呼叫者。Java 維運 caller（VehicleManager／RandomizedWorldBase／setSmashed）在此
     * 直接可辨；Lua 驅動（command／timed action／MOD server Lua）顯示為 Kahlua 反射鏈的
     * 第一個具名 frame，搭配 {@link #luaSeen} 分類。
     */
    static String firstForeignFrame(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            String cls = f.getClassName();
            if (cls.startsWith("zombie.mdc.")
                    || cls.equals("java.lang.Thread")
                    || (cls.equals("zombie.vehicles.BaseVehicle")
                            && "permanentlyRemove".equals(f.getMethodName()))) {
                continue;
            }
            return cls + "." + f.getMethodName() + ":" + f.getLineNumber();
        }
        return "?";
    }

    /** stack 上出現 Kahlua VM 或 PZ Lua 橋 frame ＝ 本次刪除由 Lua 驅動。 */
    static boolean luaSeen(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            String cls = f.getClassName();
            if (cls.startsWith("se.krka.kahlua.") || cls.startsWith("zombie.Lua.")) {
                return true;
            }
        }
        return false;
    }

    private static String safeId(BaseVehicle vehicle) {
        try {
            return String.valueOf(vehicle.getId());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 近距玩家摘要：「nearest=<dist> near=[a,b,c]」；環境不可用（測試 JVM）時回占位。 */
    private static String nearbyPlayers(BaseVehicle vehicle) {
        try {
            ArrayList<IsoPlayer> players = GameServer.getPlayers();
            if (players == null || players.isEmpty()) {
                return "nearest=- near=[]";
            }
            float vx = vehicle.getX();
            float vy = vehicle.getY();
            double nearest = Double.MAX_VALUE;
            StringBuilder names = new StringBuilder("[");
            int named = 0;
            for (int i = 0; i < players.size(); i++) {
                IsoPlayer p = players.get(i);
                if (p == null) {
                    continue;
                }
                double dx = p.getX() - vx;
                double dy = p.getY() - vy;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < nearest) {
                    nearest = d;
                }
                if (d <= NEAR_RADIUS && named < NEAR_CAP) {
                    if (named > 0) {
                        names.append(',');
                    }
                    names.append(p.getUsername());
                    named++;
                }
            }
            names.append(']');
            if (nearest == Double.MAX_VALUE) {
                return "nearest=- near=[]";
            }
            return "nearest=" + Math.round(nearest * 10.0) / 10.0 + " near=" + names;
        } catch (RuntimeException e) {
            return "nearest=? near=[]";
        }
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE
                + (MODE == MODE_ENFORCE ? "（本版 enforce=observe-alias，授權條件待 observe 數據定案）" : "")
                + "（-Dmdc.vehicleRemoveGuard=0|off 停用；2|observe 預設；每刪除一行"
                + "：vid/script/pos/claim/caller/lua/near）.");
    }

    /** 三態解析：文字別名＋未知值落回預設 observe（家族 parseMode 慣例）。 */
    private static int parseMode() {
        String raw = System.getProperty("mdc.vehicleRemoveGuard");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "enforce":
                return MODE_ENFORCE;
            case "2":
            case "observe":
            default:
                return MODE_OBSERVE;
        }
    }

    // ---- 測試存取器（單執行緒測試直讀）----

    static long callsForTest() {
        return calls;
    }

    static long loggedForTest() {
        return logged;
    }

    static long suppressedForTest() {
        return suppressed;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    /** 測試用：重置 rate-limit 窗（跨案例隔離）。 */
    static void resetWindowForTest() {
        windowStartNs = 0L;
        windowCount = 0;
    }
}
