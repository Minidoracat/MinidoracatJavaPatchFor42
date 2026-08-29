package zombie.mdc;

import java.util.Set;
import java.util.Stack;

import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.AnimalSpottedPrefilter;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.IsoUtils;
import zombie.vehicles.BaseVehicle;

/**
 * W18-2 動物 LOS 迴圈殼優化（AnimalLosScan；docs/isoanimal-updatelos-design-v1.md 定稿、
 * docs/patches.md 2af 後記）。
 *
 * <p>掛法（與設計 v1 草案的差異）：v1 原設計為 updateInternal callsite redirect，但該 callsite
 * 已由 W18 {@link AnimalLosGate} 佔用——依 docs/animal-los-gate-design-v1.md §5 銜接條款，
 * 本刀以「<b>Gate forward 時 delegate 給 Scan</b>」形式疊加：{@code AnimalLosGate} 的 forward
 * 路徑改呼叫 {@link #updateLOS(IsoAnimal)}，bytecode 手術零新增（redirect 已在）。Gate off 時
 * 整段直通 vanilla、不經本類（kill switch 分層：gate 外層、scan 內層）。
 *
 * <p>問題（jstack 證據）：W3-3 之後 updateLOS 的殘餘成本全在<b>迴圈殼自身</b>（11/11 命中 leaf
 * 皆迴圈回邊、0 份在 prefilter 內）——每 pair 白繳 iterator＋checkcast＋instanceof×3＋虛呼×多＋
 * {@code IsoUtils.DistanceTo}（含 sqrt）＋{@code Type.tryCastTo}×3（其一死存儲），而 99.9%+ pair
 * 的結局是被 prefilter 丟棄。本類以<b>保守裕度平方預篩</b>（d² &gt; (t+0.25)²，免 sqrt）先殺
 * 幾何上不可能 spotted 的遠距 pair；邊界帶與近距 pair 全額 delegate 給
 * {@link AnimalSpottedPrefilter}（W3-3 現行路徑原樣）。
 *
 * <p><b>行為承諾：與現行（W3-3 後）bit-exact，含 RNG 流</b>（設計 §4.3 七條等價性義務）：
 * (A) 安全域內（t ≤ 65536）0.25F 裕度 ≥ 32 ULP 輾壓 d²/sqrt/g² 的合計舍入誤差 ⟹ fast skip
 * 觸發 ⟹ prefilter 必走 skip 路徑，前綴逐句重放（{@code spottedChr=null}＋{@code lastAlerted}
 * 衰減，每次即呼 {@code GameTime.getInstance().getMultiplier()} 不快取）；域外
 * {@code fastEnabled=false} 整刀退化為全額 delegate。(C) 判定次序重排只發生在「無效果 skip」
 * 之間（square-null 與 d² 前移）；所有有效果動作前該 pair 的全部 vanilla 排除條件均已等價完成。
 * (D) {@code Type.tryCastTo} ≡ instanceof 分派；vanilla {@code movingPlayer} 為死存儲（42.20.4
 * javap 重驗仍成立），消除無可觀測差異。(F) fallback（直通 vanilla patched 本體）僅存在於前置
 * 取值段（{@code spotted.clear()} 之前，零副作用）；掃描段例外原樣上拋，絕無 double-scan。
 *
 * <p>三態 {@code -Dmdc.animalLosScan}：{@code 0|off}（直通）、{@code 1|on|enforce}（fast path）、
 * {@code 2|observe}（<b>預設首發</b>：純 timing wrapper 零行為差，量 vanilla 真實單次成本——
 * §4.6 兩階段量測紀律，「省多少」只能由 on canary 實測回答，開 on 前需 observe 數據與 jstack
 * 佔比互驗）。on 與 observe 共用 calls/elapsedNs/sumObjects 對稱計時（A/B 分子分母）。
 *
 * <p>觀測連續性警告（§4.5）：on 生效後 W3-3 {@code AnimalSpotted} 週期 log 的 skipped 增速會
 * 驟降（遠距 pair 改計 fastSkipped）——巡檢時勿誤判 W3-3 失效；
 * {@code delegated}（本類）≈ AnimalSpotted 的新增 delegated＋邊界帶 skipped 可交叉對帳。
 *
 * <p>例外語意（家族慣例）：前置取值段 catch {@code RuntimeException} → fallback 直通（fallback
 * 不計 elapsed）；{@code LinkageError} 一律外逃 fail-fast；掃描段與 vanilla 委派不包 try。
 * {@code maybeBeat} 自包 RuntimeException（log 故障不外逃）。計數為主執行緒單寫普通 long。
 */
public final class AnimalLosScan {
    private static final String TAG = "[MinidoracatJavaPatch][AnimalLosScan]";

    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();
    private static final long BEAT_NS = 60_000_000_000L;

    /** 安全域上限：t ≤ 2^16 時 float ULP ≤ 2^-7，0.25F 裕度 ≥ 32 ULP（§4.3-A）。 */
    private static final float SAFE_DOMAIN_MAX = 65536.0F;
    private static final float GUARD_MARGIN = 0.25F;

    // on 與 observe 對稱計時（A/B）；主執行緒單寫。
    private static long calls;
    private static long elapsedNs;
    private static long sumObjects;
    private static long sizedCalls;
    // on 專屬。
    private static long animalsScanned;
    private static long fastSkipped;
    private static long delegated;
    private static long fallbacks;
    private static long anomalies;
    private static long lastBeatNs;
    private static boolean bannerShown;

    private AnimalLosScan() {
    }

    /** Gate forward 路徑的 delegate 目標（receiver 前置）。 */
    /**
     * Gate 在開始 LOS sample 計時前先呼叫，避免一次性 banner 成本污染 losMaxUs。
     * Scan 自身亦呼叫以涵蓋 Lua/測試直入；RuntimeException 只計 anomaly，不擋行為。
     */
    static void prepare() {
        if (MODE == MODE_OFF || bannerShown) {
            return;
        }
        try {
            showBanner();
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    public static void updateLOS(IsoAnimal a) {
        if (MODE == MODE_OFF) {
            a.updateLOS();
            return;
        }
        prepare();
        if (MODE == MODE_OBSERVE) {
            int sz = sizeOrMinus1(a);
            long t0 = System.nanoTime();
            a.updateLOS();
            recordTiming(System.nanoTime() - t0, sz);
            return;
        }

        // ---- ON：前置取值段（任何異常/null → fallback 直通；此時零可觀測寫入）----
        int sz = sizeOrMinus1(a);
        long t0 = System.nanoTime();
        BaseAnimalBehavior b = null;
        Set<IsoMovingObject> list = null;
        Stack<IsoMovingObject> spotted = null;
        boolean ok = false;
        try {
            b = a.getBehavior();
            IsoCell cell = a.getCell();
            list = cell != null ? cell.getObjectList() : null;
            spotted = a.getSpottedList();
            ok = b != null && list != null && spotted != null;
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            fallbacks++;
            a.updateLOS(); // 委派在 try 外；fallback 不計 elapsed
            maybeBeat();
            return;
        }

        // ---- 掃描段：無 fallback，例外原樣上拋（與 vanilla 同型；絕無 double-scan）----
        float ax = a.getX();
        float ay = a.getY();
        spotted.clear(); // 第一個可觀測寫入（vanilla 同位置）
        for (IsoMovingObject o : list) {
            if (o instanceof IsoPhysicsObject) {
                continue;
            }
            if (o instanceof BaseVehicle) {
                continue;
            }
            if (o instanceof IsoZombie && ((IsoZombie) o).isReanimatedForGrappleOnly()) {
                continue;
            }
            if (o == a) {
                spotted.add(o);
                continue;
            }
            float ox = o.getX();
            float oy = o.getY();
            float oz = o.getZ();
            if (PZMath.abs(oz - a.getZ()) > 1.0F) { // per-pair this.getZ() 照 vanilla
                continue;
            }
            float dx = ox - ax;
            float dy = oy - ay;
            float d2 = dx * dx + dy * dy;
            // 次序前移：無效果 skip 之間合法重排（§4.3-C）
            if (o.getCurrentSquare() == null) {
                continue;
            }
            boolean isZ = o instanceof IsoZombie;
            boolean isP = !isZ && o instanceof IsoPlayer && !(o instanceof IsoAnimal);
            if (!isZ && !isP) {
                continue; // 非殭屍非玩家：vanilla 零效果
            }
            // W3-3 must-keep：spottingDist 每個 spotted 候選 pair live 讀；mod 可在前一 pair
            // 的 spotted() 內改值。讀取/計算異常＝本 pair 禁用 fast path、全額 delegate。
            float pairGate2 = Float.NaN;
            boolean fastForPair = false;
            try {
                float t = AnimalSpottedPrefilter.thresholdOf(a.adef.spottingDist);
                float g = t + GUARD_MARGIN;
                pairGate2 = g * g;
                fastForPair = t <= SAFE_DOMAIN_MAX && g > t && pairGate2 > 0.0F;
            } catch (RuntimeException ignored) {
                // 與 W3-3 threshold 讀取異常 → NaN → delegate 同方向；LinkageError 不接。
            }
            if (fastForPair && d2 > pairGate2) {
                // ---- fast skip：毫無爭議的遠距。prefilter skip 前綴逐句重放。----
                if (isP && (((IsoGameCharacter) o).isInvisible() || ((IsoPlayer) o).isGhostMode())) {
                    continue; // vanilla 玩家分支條件不成立=零效果（不觸 spotted 前綴）
                }
                a.spottedChr = null;
                if (b.lastAlerted > 0.0F) {
                    b.lastAlerted = b.lastAlerted - GameTime.getInstance().getMultiplier();
                }
                if (b.lastAlerted < 0.0F) {
                    b.lastAlerted = 0.0F;
                }
                fastSkipped++;
            } else {
                // ---- 邊界帶＋近距：全額 delegate（W3-3 現行路徑原樣，含 RNG 流）----
                float dist = IsoUtils.DistanceTo(ox, oy, ax, ay); // 參數順序照 vanilla
                if (isZ) {
                    AnimalSpottedPrefilter.spotted(b, o, false, dist);
                    delegated++;
                } else if (!((IsoGameCharacter) o).isInvisible() && !((IsoPlayer) o).isGhostMode()) {
                    AnimalSpottedPrefilter.spotted(b, o, false, dist);
                    delegated++;
                }
            }
        }
        animalsScanned++;
        recordTiming(System.nanoTime() - t0, sz);
    }

    private static int sizeOrMinus1(IsoAnimal a) {
        try {
            IsoCell cell = a.getCell();
            return cell != null ? cell.getObjectList().size() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * on／observe 共用的對稱計時尾段（§4.6 A/B 分子分母）。dt 一律於 callsite 求值，
     * 故量測區間與內聯版逐 ns 相同；累加次序（elapsedNs→calls→sumObjects→maybeBeat）亦同。
     */
    private static void recordTiming(long dt, int sz) {
        elapsedNs += dt;
        calls++;
        if (sz >= 0) {
            sumObjects += sz;
            sizedCalls++;
        }
        maybeBeat();
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE
                + "（-Dmdc.animalLosScan=0|off 直通；1|on fast path；2|observe 預設 timing；"
                + "on 前需 observe 數據＋jstack 佔比互驗——設計 §4.6）.");
    }

    private static void maybeBeat() {
        if ((calls & 0xFFFL) != 0L) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (lastBeatNs != 0L && now - lastBeatNs < BEAT_NS) {
                return;
            }
            lastBeatNs = now;
            DebugLog.log(TAG + " beat calls=" + calls
                    + " avgUs=" + (calls > 0L ? elapsedNs / calls / 1000L : 0L)
                    + " objAvg=" + (sizedCalls > 0L ? sumObjects / sizedCalls : 0L)
                    + " scanned=" + animalsScanned + " fastSkipped=" + fastSkipped
                    + " delegated=" + delegated + " fallbacks=" + fallbacks
                    + " anomalies=" + anomalies + " mode=" + MODE + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 三態解析：文字別名＋未知值落回預設 observe（家族 parseMode 慣例）。 */
    private static int parseMode() {
        String raw = System.getProperty("mdc.animalLosScan");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "on":
            case "enforce":
                return MODE_ON;
            case "2":
            case "observe":
            default:
                return MODE_OBSERVE;
        }
    }

    // ---- 測試存取器（主執行緒單寫故直讀安全）----

    static long callsForTest() {
        return calls;
    }

    static long elapsedNsForTest() {
        return elapsedNs;
    }

    static long sumObjectsForTest() {
        return sumObjects;
    }

    static long animalsScannedForTest() {
        return animalsScanned;
    }

    static long fastSkippedForTest() {
        return fastSkipped;
    }

    static long delegatedForTest() {
        return delegated;
    }

    static long fallbacksForTest() {
        return fallbacks;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
