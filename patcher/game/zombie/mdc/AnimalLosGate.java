package zombie.mdc;

import zombie.MovingObjectUpdateScheduler;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;

/**
 * W18 動物 LOS 節流閘（2026-08-25 立案；docs/patches.md 2af、docs/animal-los-gate-design-v1.md）。
 *
 * <p>背景：60 張 jcmd stack（66 人晚峰）實測 {@code IsoAnimal.updateLOS} 單一 leaf 佔主執行緒
 * 41.7%（另一批 8/17 40 人層 18.3%，docs/isoanimal-updatelos-design-v1.md §1）——每隻實體動物
 * 每 tick 掃 {@code getCell().getObjectList()} 全表（回傳 {@code Set}），迴圈唯一有效輸出是對
 * zombie/player 呼叫 {@code behavior.spotted()}。前案已證：此路徑 server-only（7 呼叫點逐一
 * 封死，client 不執行）、動物版 {@code spottedList} 恆 {@code {this}} 且零 server 消費者。
 *
 * <p>掛點：{@code IsoAnimal.updateInternal()V} 內唯一 {@code invokevirtual updateLOS:()V}
 * （offset 197）redirect 至 {@link #updateLOS(IsoAnimal)}（1:1 同形，receiver 前置）。
 * {@code updateLOS} 本體不動（Lua/mod 直呼路徑照舊，未節流但正確）。
 *
 * <p>stagger（enforce）：{@code floorMod(mix(identityHashCode) + frameCounter, N) == 0} 才轉呼叫。
 * 幀源＝vanilla {@code MovingObjectUpdateScheduler.instance.getFrameCounter()}（{@code startFrame()}
 * 每主迴圈 tick +1；updateLOS 的呼叫鏈正是從該 scheduler 的 bucket 出發＝同幀恆定）。
 * 每隻動物嚴格每 N tick 輪跑一次：Δframe 恆 1 ⇒ 無「牆鐘窗口 × tick 節拍 gcd>1」的剩餘類
 * 失明（review-lane-grok BLOCKING 修正：v1 草案的 nanoTime 窗口在 fps5/N=4 時會讓半數動物
 * 永久 skip）。相位用 Fibonacci mix（{@code h*0x9E3779B9 >>> 16}）防 -XX:hashCode 切換與
 * 低位聚集。CPU 砍幅恆 (N-1)/N，與 fps 無關。
 *
 * <p>行為代價（誠實語意，非單次延遲）：首次偵測延遲 ≤(N-1) tick；spotted() 驅動的速率型
 * 效果全部 ×1/N——玩家/殭屍壓力累積、馴養 acceptance 累加（dist&lt;10 的 spotted 分支）、
 * 野生警戒與偷襲 XP 機會、attackIfStressed 起手機率、lastAlerted 衰減。故預設 N=2
 * （速率減半、延遲 ≤1 tick），確認體感後可用 property 上調。聽覺 {@code respondToSound}
 * 不經 LOS，不受影響。skip 時 {@code spottedList} 保留上輪 {@code {this}}（動物版恆此值，
 * 含 Lua 讀取者在內零差）。
 *
 * <p>三態 {@code -Dmdc.animalLosGate}：0=off 直通、1=enforce、2=observe（預設，只計數照常轉呼叫）。
 * 參數：{@code -Dmdc.animalLosN}（clamp 1..16，預設 2；1＝等效全跑）。計數為主執行緒單寫
 * 普通 long；helper 簿記自身 try/catch fail-open（anomalies++ 後照常轉呼叫），vanilla 例外
 * 一律不吞。
 */
public final class AnimalLosGate {
    private static final String TAG = "[MinidoracatJavaPatch][AnimalLosGate]";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = readInt("mdc.animalLosGate", MODE_OBSERVE, 0, 2);
    static final int N = readInt("mdc.animalLosN", 2, 1, 16);
    private static final long BEAT_NS = 60_000_000_000L;

    // 主執行緒單寫單讀（updateInternal 只在主迴圈跑；行為測試亦單執行緒），普通 long 即可。
    private static long calls;
    private static long forwarded;
    private static long skipped;
    private static long sizeSamples;
    private static long sizeSum;
    private static long sizeMax;
    private static long sizeMin = -1L;
    private static long losSamples;
    private static long losNsSum;
    private static long losNsMax;
    private static long anomalies;
    private static long lastBeatNs;
    private static boolean bannerShown;

    private AnimalLosGate() {
    }

    /** redirect 目標：updateInternal 內的 updateLOS 呼叫（receiver 前置）。 */
    public static void updateLOS(IsoAnimal animal) {
        if (MODE == MODE_OFF) {
            animal.updateLOS();
            return;
        }
        boolean sample = false;
        try {
            calls++;
            if (!bannerShown) {
                showBanner();
            }
            if (MODE == MODE_ENFORCE) {
                int phase = (System.identityHashCode(animal) * 0x9E3779B9) >>> 16;
                long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
                if (Math.floorMod(phase + (int) frame, N) != 0) {
                    skipped++;
                    maybeBeat();
                    return;
                }
            }
            forwarded++;
            sample = (forwarded & 63L) == 1L;
            if (sample) {
                IsoCell cell = animal.getCell();
                if (cell != null) {
                    int sz = cell.getObjectList().size();
                    sizeSamples++;
                    sizeSum += sz;
                    if (sz > sizeMax) {
                        sizeMax = sz;
                    }
                    if (sizeMin < 0L || sz < sizeMin) {
                        sizeMin = sz;
                    }
                }
            }
            maybeBeat();
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            sample = false;
        }
        if (sample) {
            long t0 = System.nanoTime();
            try {
                animal.updateLOS();
            } finally {
                long dt = System.nanoTime() - t0;
                losSamples++;
                losNsSum += dt;
                if (dt > losNsMax) {
                    losNsMax = dt;
                }
            }
        } else {
            animal.updateLOS();
        }
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE + " n=" + N
                + "（-Dmdc.animalLosGate=0 停用；1 enforce/2 observe；-Dmdc.animalLosN 調參，"
                + "enforce 每動物每 N tick 掃一次、spotted 速率 ×1/N）.");
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (lastBeatNs != 0L && now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        DebugLog.log(TAG + " beat calls=" + calls + " forwarded=" + forwarded
                + " skipped=" + skipped
                + " sizeAvg=" + (sizeSamples > 0L ? sizeSum / sizeSamples : 0L)
                + " sizeMin=" + Math.max(sizeMin, 0L) + " sizeMax=" + sizeMax
                + " losAvgUs=" + (losSamples > 0L ? losNsSum / losSamples / 1000L : 0L)
                + " losMaxUs=" + (losNsMax / 1000L)
                + " anomalies=" + anomalies
                + " mode=" + MODE + " n=" + N + ".");
    }

    private static int readInt(String key, int def, int min, int max) {
        try {
            String raw = System.getProperty(key);
            if (raw == null || raw.isEmpty()) {
                return def;
            }
            int v = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (RuntimeException e) {
            return def;
        }
    }

    // ---- 測試存取器（行為測試對帳用；主執行緒單寫故直讀安全）----

    static long callsForTest() {
        return calls;
    }

    static long forwardedForTest() {
        return forwarded;
    }

    static long skippedForTest() {
        return skipped;
    }

    static long sizeSamplesForTest() {
        return sizeSamples;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
