package zombie.mdc;

import java.util.concurrent.atomic.AtomicLong;

import zombie.audio.FMODParameterList;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/**
 * W34 伺服器角色聲音參數跳過（2026-09-25；docs/patches.md 2aw）。
 *
 * <p><b>白工</b>：{@code IsoGameCharacter.updateEmitter} 第一行無條件呼叫
 * {@code getFMODParameters().update()}。server 上實際呼叫它的只有動物
 * （{@code IsoAnimal.update} 每 tick 兩次、{@code AnimalPopulationManager} 卸載後補呼叫；
 * {@code IsoPlayer} 有 {@code !GameServer.server} 守衛、殭屍在 server 不進 update bucket）。
 * 動物在 server 註冊 FootstepMaterial／FootstepMaterial2（{@code IsoPlayer.initFMODParameters}
 * 無 server 守衛），計算時走訪格子全部物件、查屬性、{@code Enum.valueOf}。
 * 但 server 的 emitter 一律是 {@code DummyCharacterSoundEmitter}，沒有 FMOD event instance：
 * {@code FMODLocalParameter.setCurrentValue} 迭代空的 instances、{@code startEventInstance}
 * 永不發生，算出的值沒有任何讀者。2026-09-25 晚峰 JFR：主執行緒取樣 1.6% 落在這條路徑。
 *
 * <p><b>手術</b>：{@code updateEmitter} 內唯一 {@code FMODParameterList.update()} 呼叫點
 * 1:1 改道本類 {@link #update}。只在 {@code GameServer.server} 且 enforce 時跳過；其餘委派。
 *
 * <p>三態 {@code -Dmdc.emitterParamGate}：{@code 2|observe}（預設：照常委派，每 16 次取樣
 * 計時一次，量化可省下的時間）、{@code 1|enforce}（server 跳過）、{@code 0|off}（純直通）。
 * 未知值落回 observe。需重啟生效。
 */
public final class EmitterParamGate {

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode(System.getProperty("mdc.emitterParamGate"));

    private static final String TAG = "[MinidoracatJavaPatch][EmitterParamGate] ";
    private static final long SAMPLE_MASK = 15L;
    private static final long BEAT_CHECK_MASK = 4095L;
    private static final long BEAT_NS = 300_000_000_000L;

    private static final AtomicLong calls = new AtomicLong();
    private static final AtomicLong skipped = new AtomicLong();
    private static final AtomicLong sampled = new AtomicLong();
    private static final AtomicLong sampledNs = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();
    private static volatile long lastBeatNs;
    private static volatile boolean announced;

    static int parseMode(String raw) {
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "enforce":
                return MODE_ENFORCE;
            default:
                return MODE_OBSERVE;
        }
    }

    /** {@code IsoGameCharacter.updateEmitter} 內 {@code FMODParameterList.update()} 的改道目標。 */
    public static void update(FMODParameterList list) {
        if (MODE == MODE_OFF) {
            list.update();
            return;
        }
        long n = calls.incrementAndGet();
        if ((n & BEAT_CHECK_MASK) == 0L) {
            maybeBeat();
        }
        if (MODE == MODE_ENFORCE && GameServer.server) {
            skipped.incrementAndGet();
            return;
        }
        if ((n & SAMPLE_MASK) != 0L) {
            list.update();
            return;
        }
        long t0 = System.nanoTime();
        list.update();
        sampledNs.addAndGet(System.nanoTime() - t0);
        sampled.incrementAndGet();
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        try {
            long s = sampled.get();
            long avgNs = s == 0L ? 0L : sampledNs.get() / s;
            String head = announced ? "" : "首次生效（-Dmdc.emitterParamGate=0|off/1|enforce/2|observe 預設）";
            announced = true;
            DebugLog.log(TAG + head + "mode=" + MODE + " server=" + (GameServer.server ? 1 : 0)
                    + " calls=" + calls.get() + " skipped=" + skipped.get()
                    + " sampled=" + s + " avgNs=" + avgNs
                    + " estSavedMs=" + (calls.get() - skipped.get()) * avgNs / 1_000_000L
                    + " anomalies=" + anomalies.get());
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    // ---- 測試存取器 ----

    static long callsForTest() {
        return calls.get();
    }

    static long skippedForTest() {
        return skipped.get();
    }

    static long sampledForTest() {
        return sampled.get();
    }

    private EmitterParamGate() {}
}
