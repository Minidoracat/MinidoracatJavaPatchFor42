package zombie.characters.animals.behavior;

import zombie.GameTime;
import zombie.debug.DebugType;
import zombie.iso.IsoMovingObject;

/**
 * 動物 spotted() 距離預過濾（W3-3，2026-08-05）——VehicleIntersectPrefilter 範式復刻。
 *
 * 原版 IsoAnimal.updateLOS 對同層所有移動物件（殭屍數千＋玩家）無水平距離過濾
 * 直接呼叫 behavior.spotted(o,false,dist)；spotted() 所有持久效果都有近距門檻
 * （acceptance<10、殭屍壓力≤10、逃跑≤6/＜3、alert 與 XP 需 dist≤spottingDist≈10）。
 * 100 動物 × 1500 同層物件 ≈ 15 萬次白繳呼叫/tick。
 *
 * 遠距（> max(12, spottingDist+2)）呼叫在 vanilla 中的效果僅有無條件前綴：
 *   parent.spottedChr = null；lastAlerted>0 則減 GameTime multiplier；lastAlerted<0 則歸 0
 * ——本 helper 逐句重放（兩個 if 為獨立序列，與原版同構），其餘全 skip。
 * 已接受的非可感知差異：遠距 wild 分支的共享 RNG 流消耗被移除（MP 無決定性依賴，
 * 對抗審查 note 級判定；該分支的 getPerkLevel/向量計算正是本刀要省的白繳工作）。
 *
 * must-keep 紀律（生態稜鏡處方）：
 * - threshold 每呼叫 live 讀 b.parent.adef.spottingDist，禁快取（mod 可隨時改）；
 * - 加法在 float domain（spottingDist 為 int，+2.0F 不溢位，MAX_VALUE 亦安全）；
 * - NaN dist 或 threshold 取值異常 → 比較恆 false → 自動全額委派；
 * - delegate 在 try 之外，vanilla 例外原樣上拋，絕無 double-call。
 *
 * 本類置於 behavior 套件以取得 BaseAnimalBehavior.parent（protected）存取權。
 * 主執行緒單執行緒呼叫，計數不需同步。
 */
public final class AnimalSpottedPrefilter {

    private static long skipped;
    private static long delegated;
    private static long anomalies;
    private static float lastThreshold = Float.NaN;

    /** IsoAnimal.updateLOS 內兩處 behavior.spotted 呼叫點（殭屍分支＋玩家分支）的改道目標。 */
    public static void spotted(BaseAnimalBehavior b, IsoMovingObject other, boolean bForced, float dist) {
        if (!bForced) {
            float threshold;
            try {
                threshold = thresholdOf(b.parent.adef.spottingDist);
            } catch (RuntimeException ignored) {
                anomalies++;
                threshold = Float.NaN;      // 比較恆 false → 委派
            }
            lastThreshold = threshold;
            if (dist > threshold) {
                // vanilla spotted() 無條件前綴逐句重放（順序與巢狀結構同原版）
                b.parent.spottedChr = null;
                if (b.lastAlerted > 0.0F) {
                    b.lastAlerted = b.lastAlerted - GameTime.getInstance().getMultiplier();
                }
                if (b.lastAlerted < 0.0F) {
                    b.lastAlerted = 0.0F;
                }
                skipped++;
                maybeLog();
                return;
            }
        }
        delegated++;
        maybeLog();
        b.spotted(other, bForced, dist);
    }

    /**
     * 純函式：預過濾門檻。spotted() 內所有持久效果門檻的上包絡（10）＋2 格裕度，
     * 下限 12 對抗 mod 把 spottingDist 調小；mod 調大時動態跟隨。
     * public：W18-2 AnimalLosScan 直呼同一真相源（單一定義防漂移，SmokeCheck 另釘語意）。
     */
    public static float thresholdOf(int spottingDist) {
        return Math.max(12.0F, spottingDist + 2.0F);
    }

    private static void maybeLog() {
        long total = skipped + delegated;
        if ((total & 0x3FFFFFF) == 0L && total != 0L) {
            DebugType.Multiplayer.println("[MinidoracatJavaPatch][AnimalSpotted] skipped=" + skipped
                    + " delegated=" + delegated + " anomalies=" + anomalies
                    + " threshold=" + lastThreshold);
        }
    }

    private AnimalSpottedPrefilter() {}
}
