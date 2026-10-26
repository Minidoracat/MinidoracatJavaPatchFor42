package zombie.mdc;

import zombie.characters.IsoZombie;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.popman.NetworkZombieManager;

/**
 * W80 回收的殭屍物件清掉上一輪的網路擁有者（docs/patches.md 2co）。
 *
 * <p><b>原版缺陷</b>：殭屍死後擁有者 X 留在物件上（{@code NetworkZombieManager.moveZombie} 的已死分支只在
 * {@code realState == OnGround} 時清），{@code removeFromWorld} 把 onlineId 設成 −1，{@code getZombieAuth} 隨即把牠從
 * X 的擁有清單拿掉。5 秒後 {@code VirtualZombieManager.reuseZombie} 把物件放進回收池，{@code resetForReuse()} 不碰擁有者，
 * {@code createZombieOutsideWorld} 把牠交給下一隻殭屍：新殭屍出生時擁有者＝X，X 的清單卻沒有牠。{@code updateAuth} 選到 X 時
 * {@code moveZombie} 因 {@code getOwner() == to} 什麼都不做，伺服器不送給任何人、也沒人模擬，所有客戶端都看不到；
 * 改歸別人時才印 {@code moveZombie: There are no zombies in nz.zombies.}（抑噪 #5）並修好。
 *
 * <p><b>手術</b>：{@code reuseZombie} 內唯一 {@code invokevirtual IsoZombie.resetForReuse()} 改道 {@link #resetForReuse}：
 * 先呼叫原方法，再 {@code setOwner(null)}、{@code setOwnerPlayer(null)}，接著呼叫原版 {@code NetworkZombieManager.recheck(X)}
 * （持 {@code owns.lock}）把擁有者不是 X 的物件從 X 的清單拿掉。X 的清單通常已沒有牠（{@code getZombieAuth} 每輪拿掉
 * onlineId −1 的），但死亡與回收落在同一輪、中間主迴圈停超過 5 秒（同步存檔）時還留著；只清欄位的話，新殭屍會同時在
 * X 與新擁有者的清單裡，或在 X 的清單裡出現兩次。出生時沒有擁有者，{@code updateAuth} 照原版規則指派並加進對方的清單。
 *
 * <p>心跳（首次呼叫一行、之後每 30 分鐘一行）：{@code reused} 回收次數、{@code stale} 回收時還帶擁有者的次數（on 時已清掉）、
 * {@code nzMissing} 抑噪 #5 的累計（{@link LogFilter#moveZombieMissCount()}，與本刀開關無關）。
 *
 * <p>kill switch {@code -Dmdc.zombieReuseOwner=0|off}：不清擁有者（照樣計數與心跳），需重啟。
 */
public final class ZombieReuseOwner {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.zombieReuseOwner"));
    private static final String TAG = "[MinidoracatJavaPatch][ZombieReuseOwner] ";
    private static final long BEAT_NS = 1_800_000_000_000L;

    // VirtualZombieManager.update 在主執行緒：計數單執行緒寫。
    private static long reused;
    private static long stale;
    private static long anomalies;
    private static long lastBeatNs;
    private static boolean announced;

    private ZombieReuseOwner() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** {@code VirtualZombieManager.reuseZombie} 內 {@code IsoZombie.resetForReuse()} 的改道目標；原方法的例外原樣上拋。 */
    public static void resetForReuse(IsoZombie z) {
        z.resetForReuse();
        reused++;
        try {
            UdpConnection owner = z.getOwner();
            if (owner != null || z.getOwnerPlayer() != null) {
                stale++;
                if (ENABLED) {
                    z.setOwner(null);
                    z.setOwnerPlayer(null);
                    if (owner != null) {
                        NetworkZombieManager.getInstance().recheck(owner);
                    }
                }
            }
        } catch (RuntimeException e) {
            anomalies++;
        }
        maybeBeat();
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        String head = announced ? "beat " : "首次生效（-Dmdc.zombieReuseOwner=0|off 不清擁有者）";
        announced = true;
        try {
            DebugLog.log(TAG + head + "mode=" + (ENABLED ? "on" : "off") + " reused=" + reused + " stale=" + stale
                    + " nzMissing=" + LogFilter.moveZombieMissCount() + " anomalies=" + anomalies);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies++;
        }
    }

    // ---- 測試存取器 ----

    static long reusedForTest() {
        return reused;
    }

    static long staleForTest() {
        return stale;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
