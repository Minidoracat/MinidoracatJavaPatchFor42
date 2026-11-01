package zombie.mdc;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.network.ServerOptions;

/**
 * W88 PVP=false 時玩家死亡不歸給玩家（docs/patches.md 2cw）。
 *
 * <p><b>原版缺陷</b>：{@code IsoGameCharacter.DoDeath} 只要 {@code getAttackedBy()} 是 {@code IsoPlayer}（且在伺服器、死者是
 * {@code IsoPlayer}）就走 {@code PVPLogTool.logKill}：pvp.txt 記 {@code [IMPORTANT] Kill:}，{@code AnnounceDeath=true} 時全服聊天
 * 公告 {@code Kill: "A" killed "B"}，不看 {@code PVP} 選項。{@code attackedBy} 沒有時效、只會被下一個攻擊者蓋掉；伺服器血量歸零
 * 時 {@code die()} 把它當凶手傳給 {@code Kill}，所以被車碰過（{@code onHitByVehicle} 先設成駕駛或推車的人，之後才檢查 PVP）
 * 之後才失溫、餓死的玩家，也被公告成被那個玩家殺死。
 *
 * <p><b>手術</b>：{@code DoDeath(HandWeapon, IsoGameCharacter, boolean)} 內兩個 {@code invokevirtual getAttackedBy()} 同形改道
 * {@link #getAttackedBy}。PVP=false、死者是非動物的 {@code IsoPlayer}、{@code attackedBy} 是 {@code IsoPlayer} 時回 null，原版就走
 * else 分支：user.txt 記 {@code user <名> died at … (non pvp)}，{@code AnnounceDeath=true} 時聊天公告 {@code <名> is dead.}；
 * 其餘情況（PVP=true、死者是殭屍或動物、攻擊者不是玩家）回原值。第二個呼叫點只在第一個回了 {@code IsoPlayer} 時才執行，兩次
 * 之間沒有別的副作用，回的是同一個值。不改欄位：屍體的 killedBy、送給客戶端的 killerId、{@code onKilled} 的統計照原版。
 *
 * <p>首次呼叫印一行生效狀態；每次改走 non pvp 印一行（玩家死亡不頻繁，不限量）。
 *
 * <p>kill switch {@code -Dmdc.pveDeathLog=0|off}：照原版（需重啟）。
 */
public final class PveDeathLog {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.pveDeathLog"));
    private static final String TAG = "[MinidoracatJavaPatch][PveDeathLog] ";

    // DoDeath 在主執行緒：計數單執行緒寫。
    private static long dropped;
    private static long anomalies;
    private static boolean announced;

    private PveDeathLog() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /**
     * {@code DoDeath} 內 {@code getAttackedBy()} 的改道目標。判斷只讀欄位（{@code isAnimal}、{@code PVP} 選項值），不包 try：
     * 兩個呼叫點必須得到同一個結果（第 1 個回玩家、第 2 個回 null 會讓 {@code logKill} 拿到 null），而原版 {@code DoDeath} 隨後
     * 自己也讀 {@code ServerOptions.instance}。只有 log 包在 try 裡，log 失敗不改變結果。
     */
    public static IsoGameCharacter getAttackedBy(IsoGameCharacter self) {
        IsoGameCharacter attacker = self.getAttackedBy();
        if (!announced) {
            announce();
        }
        if (!ENABLED || !(attacker instanceof IsoPlayer killer) || !(self instanceof IsoPlayer victim)
                || victim.isAnimal() || ServerOptions.instance.pvp.getValue()) {
            return attacker;
        }
        dropped++;
        try {
            DebugLog.log(TAG + "PVP=false: " + victim.getUsername() + " died with attackedBy=" + killer.getUsername()
                    + ", logged as non pvp instead of Kill (#" + dropped + ")");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        return null;
    }

    private static void announce() {
        announced = true;
        try {
            DebugLog.log(TAG + "首次生效（-Dmdc.pveDeathLog=0|off 照原版）mode=" + (ENABLED ? "on" : "off")
                    + " pvp=" + ServerOptions.instance.pvp.getValue());
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    // ---- 測試存取器 ----

    static long droppedForTest() {
        return dropped;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
