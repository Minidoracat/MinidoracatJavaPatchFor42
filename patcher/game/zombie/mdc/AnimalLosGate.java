package zombie.mdc;

import zombie.MovingObjectUpdateScheduler;
import zombie.UpdateSchedulerSimulationLevel;
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
 * 每主迴圈 tick +1）。<b>Δframe 恆 1 是條件性事實，不是數學免疫</b>：它成立於「dedicated server
 * 上 {@code getUpdateSchedulerSimulationLevelForObject} 因 {@code GameServer.server} 恆回 FULL
 * ⇒ {@code frameMod==1} ⇒ 動物每 tick 都被 bucket update」這條 42.20.3 前提鏈（SmokeCheck 以
 * 五支結構釘鎖住：server⇒FULL 短路、{@code getFrameMod=1<<idx}、{@code startFrame} 的 +1、
 * bucket 的 {@code getID()%frameMod}、MOUS.update 每幀全桶掃描）。
 * TIS 若在 server 開 LOD 分級（{@code frameMod>1}），動物被 update 的幀集合變成
 * {@code frame ≡ getID() (mod frameMod)}，與 N 有公因數時會產生剩餘類永久失明——除了建置期
 * 釘死，runtime 亦有 {@link #gateApplies fail-open}：{@code getCurrentSimulationLevel().getFrameMod()
 * != 1} 的動物直接 forward 不 gate（計 {@code lodPassthrough}），寧可失去節流也不失明。
 * 相位用 Fibonacci mix（{@code h*0x9E3779B9 >>> 16}）防 -XX:hashCode 切換與低位聚集；
 * {@code floorMod(long,int)} 全程 long 累加，無 {@code (int)} 截斷不連續。
 *
 * <p>行為代價（誠實語意，非單次延遲）：首次偵測延遲 ≤(N-1) tick；spotted() 驅動的速率型
 * 效果全部 ×1/N——玩家/殭屍壓力累積、馴養 acceptance 累加（dist&lt;10 的 spotted 分支）、
 * 野生警戒與偷襲 XP 機會、attackIfStressed 起手機率、lastAlerted 衰減；並沿 W3-3 已接受的
 * 結論承擔全域 Rand 序列位移（MP 無決定性依賴）。故預設 N=2（速率減半、延遲 ≤1 tick），
 * 確認體感後可用 property 上調。聽覺 {@code respondToSound} 不經 LOS，不受影響。skip 時
 * {@code spottedList} 保留上輪 {@code {this}}（動物版恆此值，含 Lua 讀取者在內零差）。
 *
 * <p>例外語意（家族慣例）：主 try 只 catch {@code RuntimeException}——簿記自身可恢復錯誤
 * fail-open（anomalies++ 後照常轉呼叫）；{@code LinkageError} 一律外逃＝fail-fast（新 jar＋
 * 舊 loose class 的二進位不相容必須炸得可見，比照 ChunkRequestPacker 的 rethrow 紀律與
 * 2026-08-17 NoSuchFieldError 事故的處置）。vanilla 委派在 try 外，例外原樣上拋。
 * {@link #maybeBeat} 在簿記完成後才執行（log 故障不再讓 forward 被記成 skip），內部自包
 * {@code RuntimeException}（log 基礎設施故障不外逃、不擋主流程——W8 慣例）。
 *
 * <p>三態 {@code -Dmdc.animalLosGate}：{@code 0|off}、{@code 1|enforce}、{@code 2|observe}
 * （預設；未知值落回 observe）——文字別名與落回方向比照家族四把三態刀的 {@code parseMode()}。
 * 參數：{@code -Dmdc.animalLosN}（clamp 1..16，預設 2；1＝等效全跑）。計數為主執行緒單寫
 * 普通 long。
 */
public final class AnimalLosGate {
    private static final String TAG = "[MinidoracatJavaPatch][AnimalLosGate]";

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();
    static final int N = readInt("mdc.animalLosN", 2, 1, 16);
    private static final long BEAT_NS = 60_000_000_000L;

    // 主執行緒單寫單讀（updateInternal 只在主迴圈跑；行為測試亦單執行緒），普通 long 即可。
    private static long calls;
    private static long forwarded;
    private static long skipped;
    private static long lodPassthrough;
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
        boolean skip = false;
        try {
            calls++;
            if (!bannerShown) {
                showBanner();
            }
            if (MODE == MODE_ENFORCE && gateApplies(animal)
                    && Math.floorMod((long) mixPhase(animal)
                            + MovingObjectUpdateScheduler.instance.getFrameCounter(), N) != 0) {
                skip = true;
                skipped++;
            } else {
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
            }
        } catch (RuntimeException e) {
            // 簿記自身可恢復錯誤：fail-open 照常轉呼叫。LinkageError 刻意不接（外逃=fail-fast）。
            anomalies++;
            sample = false;
            skip = false;
        }
        maybeBeat();
        if (skip) {
            return;
        }
        long t0 = sample ? System.nanoTime() : 0L;
        try {
            animal.updateLOS();
        } finally {
            if (sample) {
                long dt = System.nanoTime() - t0;
                losSamples++;
                losNsSum += dt;
                if (dt > losNsMax) {
                    losNsMax = dt;
                }
            }
        }
    }

    /**
     * runtime fail-open：動物不在 frameMod==1 的 bucket（TIS 開 LOD 分級）時不 gate，
     * 直接 forward——寧可失去節流也不產生 gcd 剩餘類失明。null（測試注入/初始化前）視同適用。
     * 讀寫同源（javap 實證）：bucket.update 於呼叫物件 update() 前先
     * setCurrentSimulationLevel(bucket.simulationLevel)，故此處讀到的正是本 tick 分桶用的
     * level，與排程端不可分叉；vanilla 建構子亦將初值設為 FULL（production 永不 null）。
     */
    private static boolean gateApplies(IsoAnimal animal) {
        UpdateSchedulerSimulationLevel lvl = animal.getCurrentSimulationLevel();
        if (lvl != null && lvl.getFrameMod() != 1) {
            lodPassthrough++;
            return false;
        }
        return true;
    }

    private static int mixPhase(IsoAnimal animal) {
        return (System.identityHashCode(animal) * 0x9E3779B9) >>> 16;
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE + " n=" + N
                + "（-Dmdc.animalLosGate=0|off 停用；1|enforce/2|observe；-Dmdc.animalLosN 調參，"
                + "enforce 每動物每 N tick 掃一次、spotted 速率 ×1/N）.");
    }

    /**
     * heartbeat：每 4096 次呼叫才讀一次時鐘（熱路徑不無條件讀 nanoTime——比照
     * AnimalRelevancyGate/ChunkWriteGuard 的計數器節流慣例），60s 節流一行。
     * 自包 RuntimeException：log 基礎設施故障計 anomalies、不外逃、不擋主流程。
     */
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
            DebugLog.log(TAG + " beat calls=" + calls + " forwarded=" + forwarded
                    + " skipped=" + skipped + " lodPassthrough=" + lodPassthrough
                    + " sizeAvg=" + (sizeSamples > 0L ? sizeSum / sizeSamples : 0L)
                    + " sizeMin=" + Math.max(sizeMin, 0L) + " sizeMax=" + sizeMax
                    + " losAvgUs=" + (losSamples > 0L ? losNsSum / losSamples / 1000L : 0L)
                    + " losMaxUs=" + (losNsMax / 1000L)
                    + " anomalies=" + anomalies
                    + " mode=" + MODE + " n=" + N + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 三態解析：文字別名＋未知值落回預設 observe（家族 parseMode 慣例；clamp 會抹掉打錯訊號）。 */
    private static int parseMode() {
        String raw = System.getProperty("mdc.animalLosGate");
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

    static long lodPassthroughForTest() {
        return lodPassthrough;
    }

    static long sizeSamplesForTest() {
        return sizeSamples;
    }

    static long sizeSumForTest() {
        return sizeSum;
    }

    static long sizeMinForTest() {
        return sizeMin;
    }

    static long sizeMaxForTest() {
        return sizeMax;
    }

    static long losSamplesForTest() {
        return losSamples;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
