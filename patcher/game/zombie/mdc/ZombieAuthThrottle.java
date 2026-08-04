package zombie.mdc;

import zombie.characters.IsoZombie;
import zombie.debug.DebugType;
import zombie.network.ServerOptions;
import zombie.popman.NetworkZombieManager;

/**
 * 殭屍 ownership 重選舉錯峰節流（W3-1，2026-08-05）。
 *
 * 根因：NetworkZombieManager.updateAuth 的 2000ms gate 以 lastChangeOwner 為基準，
 * 而該欄位只在實際換手時寫入——owner 穩定的殭屍每 tick 都全額重選舉
 * （grapple 檢查＋2 次 ECS 查找＋O(連線×玩家) 距離掃描）。Z≈2500、C≈80 時
 * 每 tick ~20 萬次距離計算，是 8/3–8/4 兩晚 thread dump 的頭部主題之一。
 *
 * 對抗審查（三稜鏡一致 major）否決了初版 wall-clock stagger：millis/100 桶與退化
 * tick 週期共振（200ms/tick 時 gcd=2、400ms 時 gcd=4），dip 期間大半殭屍殘差鎖死、
 * 整段 dip 不重選舉——正是設計要治的場景反而失效。定案改 helper 內部 tick 計數器：
 * 同一個 packer pass 內呼叫間隔為微秒級，跨 pass 間隔 ≥ tick 週期（~100ms），
 * 以 50ms 間隙偵測 pass 邊界推進 tick，保證每隻已擁有殭屍每 PERIOD 個 pass
 * 必重選舉一次，與 fps 零 aliasing。
 *
 * 即刻放行（零延遲、與 vanilla 完全相同）的三類：
 * - owner == null：新載入/孤兒殭屍的首次指派；
 * - isDead()：屍體落地/ownership 釋放/die() 備援鏈不受節流（審查處方）；
 * - SwitchZombiesOwnershipEachUpdate=true：該選項語意為每 update 輪轉，節流與其矛盾，
 *   每次 live 讀（reloadoptions 熱改也即時生效）。
 *
 * 紀律：stagger 判斷整段包 try/catch、任何異常一律放行；delegate 呼叫在 try 之外，
 * 絕無 double-call，vanilla 例外原樣上拋。主執行緒單執行緒呼叫，計數不需同步。
 */
public final class ZombieAuthThrottle {

    /**
     * 每隻已擁有殭屍每 PERIOD 個 pass 重選舉一次；vanilla 換手後冷卻 2000ms 為行為包絡。
     * 取 3（質數）而非 4：長 pass（迴圈本體 ≥50ms）會使步進變 2，gcd(2,4)=2 會鎖死半數
     * 殘差（code review MAJOR-1——與被否決的 wall-clock 共振同型）；gcd(2,3)=1 免疫。
     */
    static final int PERIOD = 3;

    private static long lastCall;
    private static long lastAdvance;
    private static long tick;

    private static long delegated;
    private static long skipped;
    private static long anomalies;

    /** NetworkZombiePacker.updateAuth 內唯一 manager.updateAuth 呼叫點的改道目標。 */
    public static void updateAuth(NetworkZombieManager mgr, IsoZombie z) {
        boolean run = true;
        try {
            // 由廉到貴短路：欄位讀 → option 讀 → ECS 查找（code review 處方）
            if (!z.isDead()
                    && !ServerOptions.getInstance().switchZombiesOwnershipEachUpdate.getValue()
                    && z.getOwner() != null) {
                run = dueThisTick(observe(System.currentTimeMillis()), z.onlineId);
            }
        } catch (RuntimeException ignored) {
            anomalies++;
            run = true;
        }
        if (!run) {
            skipped++;
            maybeLog();
            return;
        }
        delegated++;
        maybeLog();
        mgr.updateAuth(z);
    }

    /**
     * pass 邊界偵測（code review MAJOR-1 定稿的雙欄位版）：
     * - 距上次「呼叫」≥50ms → 新 pass（同 pass 內呼叫間隔為微秒級）；
     * - 快 tick 保底：低人口時主迴圈可達 30-60fps（pass 間隔 <50ms），僅靠呼叫間隔
     *   tick 將永不推進——距上次「推進」≥250ms 亦強制推進，上界 PERIOD×250ms=750ms。
     * 長 pass（迴圈本體 >250ms）下步進 ≤ 1+floor(dur/250)，配合 PERIOD=3（質數）
     * 無殘差鎖死。全程 long 算術（審查紀律：禁止中途 int cast）。
     */
    static long observe(long nowMillis) {
        if (nowMillis - lastCall >= 50L || nowMillis - lastAdvance >= 250L) {
            tick++;
            lastAdvance = nowMillis;
        }
        lastCall = nowMillis;
        return tick;
    }

    /**
     * 純函式：本 tick 是否輪到這隻殭屍。onlineId 可為負（short 全域），
     * Java 的 % 對負被除數回傳負值，但恰在整數倍時仍為 0——每 PERIOD 個
     * tick 必命中一次的節奏對正負 id 一致成立。
     */
    static boolean dueThisTick(long tick, short onlineId) {
        return (tick + onlineId) % PERIOD == 0L;
    }

    private static void maybeLog() {
        long total = delegated + skipped;
        if ((total & 0x1FFFFF) == 0L && total != 0L) {
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][AuthThrottle] delegated="
                    + delegated + " skipped=" + skipped + " anomalies=" + anomalies);
        }
    }

    private ZombieAuthThrottle() {}
}
