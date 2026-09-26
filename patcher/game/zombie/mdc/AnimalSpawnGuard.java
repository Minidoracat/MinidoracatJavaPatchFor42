package zombie.mdc;

import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;

/**
 * W37 動物半建構物件守衛（2026-09-26；docs/patches.md 2az）。
 *
 * <p><b>事故</b>（9/16 22:00、9/24 18:03、9/26 10:04 三次同型）：{@code IsoAnimal} 建構子的
 * {@code super()}（{@code IsoGameCharacter}）在座標非 0 時先把物件放進 cell 的 objectList／addList，
 * 之後才跑 {@code checkForChickenpocalypse()}／{@code checkForWater()}；任一為真就跳過 {@code init()}，
 * 留下 adef／data 皆 null 的物件。water 分支不呼叫 delete()，物件照樣進世界；
 * {@code IsoAnimal.update} 每 tick 在 {@code this.adef.turnDelta} NPE，打斷整個
 * {@code IngameState.updateInternal}（時間凍結、OnTick 全停）；它被虛擬化後 apop 存檔也 NPE。
 * 呼叫端（{@code AnimalData.grow}、{@code IsoAnimal.addBaby}）緊接著 {@code getData()} NPE，
 * 且 grow 在 parent.delete() 之前拋出，小雞留在世界每幀重試、每次再漏一個。
 *
 * <p><b>手術</b>：(1) 四個帶座標建構子每個 RETURN 前 {@link #afterCtor}：data 為 null 且物件是
 * 以座標建構時，做 {@code IsoGameCharacter} 建構子加入動作的逆操作（同一個 isSafeToAdd 分支）。
 * (2) {@code checkStages} 的 grow 與 W33 的 addBaby 委派包在 {@link #grow}／{@link #addBaby}：
 * 呼叫期間有建構失敗且拋 NPE 時吞掉這次（小雞維持原狀，下次 update 由原版重試），
 * 其餘例外原樣穿透。成功路徑行為不變。kill switch {@code -Dmdc.animalSpawnGuard=0}。
 */
public final class AnimalSpawnGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalSpawnGuard"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalSpawnGuard] ";
    private static final long DETAIL_LIMIT = 64L;

    // 帶座標的動物建構與 grow／addBaby 都在主執行緒。
    private static long ctorFailures, removed, swallowed, anomalies;

    /** IsoAnimal 帶座標建構子每個 RETURN 前。 */
    public static void afterCtor(IsoAnimal animal) {
        if (!ENABLED || animal.getData() != null) {
            return;
        }
        // IsoGameCharacter 建構子只在 (x,y,z) 非 0 時加入 cell；(IsoCell) 載入用建構子走 0,0,0。
        float x = animal.getX(), y = animal.getY(), z = animal.getZ();
        if ((int) x == 0 && (int) y == 0 && (int) z == 0) {
            return;
        }
        ctorFailures++;
        try {
            IsoCell cell = animal.getCell();
            boolean hit = false;
            if (cell != null) {
                if (cell.isSafeToAdd()) {
                    hit = cell.getObjectList().remove(animal);
                }
                hit |= cell.getAddList().remove(animal);
            }
            if (hit) {
                removed++;
            }
            if (ctorFailures <= DETAIL_LIMIT || ctorFailures % 1000 == 0) {
                IsoGridSquare sq = animal.getCurrentSquare();
                String reason = sq != null && sq.isWaterSquare() ? "water"
                        : cell != null && cell.getRemoveList().contains(animal) ? "chickenpocalypse"
                        : "noInit";
                DebugLog.log(TAG + "ctor failed reason=" + reason + " pos=" + (int) x + "," + (int) y + "," + (int) z
                        + " removedFromWorld=" + hit + " failures=" + ctorFailures + " removed=" + removed
                        + " swallowed=" + swallowed + "（原版會留下 adef/data 為 null 的動物，每 tick 打斷世界更新）");
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** {@code AnimalData.checkStages} 內唯一 grow 呼叫。 */
    public static void grow(AnimalData data, String newType) {
        long before = ctorFailures;
        try {
            data.grow(newType);
        } catch (NullPointerException e) {
            if (!swallow(before, "grow", data.parent, newType)) {
                throw e;
            }
        }
    }

    /** W33 BabyBreedGuard 委派原版 addBaby 的出口。 */
    public static IsoAnimal addBaby(IsoAnimal mother) {
        long before = ctorFailures;
        try {
            return mother.addBaby();
        } catch (NullPointerException e) {
            if (!swallow(before, "addBaby", mother, null)) {
                throw e;
            }
            return null;
        }
    }

    private static boolean swallow(long before, String site, IsoAnimal parent, String newType) {
        if (!ENABLED || ctorFailures == before) {
            return false;
        }
        swallowed++;
        if (swallowed <= DETAIL_LIMIT || swallowed % 1000 == 0) {
            try {
                DebugLog.log(TAG + "skip " + site + " parent=" + parent.getAnimalType() + "#" + parent.getAnimalID()
                        + (newType == null ? "" : " newType=" + newType)
                        + " pos=" + (int) parent.getX() + "," + (int) parent.getY() + "," + (int) parent.getZ()
                        + " swallowed=" + swallowed + "（新動物建構失敗，原版會在此 NPE 打斷該 tick；下次 update 重試）");
            } catch (RuntimeException | LinkageError e) {
                anomalies++;
            }
        }
        return true;
    }

    static boolean enabledForTest() { return ENABLED; }
    static long ctorFailuresForTest() { return ctorFailures; }
    static long removedForTest() { return removed; }
    static long swallowedForTest() { return swallowed; }
    static long anomaliesForTest() { return anomalies; }

    private AnimalSpawnGuard() {}
}
