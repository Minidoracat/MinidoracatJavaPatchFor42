package zombie.mdc;

import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalBreed;
import zombie.debug.DebugLog;

/**
 * W33 分娩品種守衛（2026-09-25；docs/patches.md 2av）。
 *
 * <p><b>事故</b>（正式服 9/24 18:03:06）：{@code AnimalData.checkPregnancy → IsoAnimal.addBaby}
 * NPE「getData() is null」。原版以母獸品種名查幼崽定義，查不到就把 null 品種交給建構子。
 * <b>2026-09-26 更正</b>：null 品種本身不會造出 data 為 null 的幼崽（{@code AnimalData} 建構子會
 * 改抽隨機品種）；data 為 null 的真因是建構子的 chickenpocalypse／water 檢查失敗，由 W37
 * {@link AnimalSpawnGuard} 處理。本刀保留為品種不符的保守跳過。
 * <p><b>手術</b>：{@code checkPregnancy} 內唯一 {@code addBaby()} 呼叫 1:1 改道。先做原版同一組
 * 查詢，任一環為 null 就不生這一隻（回 null，caller 丟棄回傳值）並記母獸資訊；否則委派原版，
 * 行為逐位元不變。Lua／其他 caller 不經此路徑。kill switch {@code -Dmdc.babyBreedGuard=0}。
 */
public final class BabyBreedGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.babyBreedGuard"));
    private static final String TAG = "[MinidoracatJavaPatch][BabyBreedGuard] ";
    private static final long DETAIL_LIMIT = 64L;

    // 只在主執行緒（動物 update）呼叫。
    private static long births, blocked, anomalies;

    public static IsoAnimal addBaby(IsoAnimal mother) {
        if (!ENABLED) {
            return AnimalSpawnGuard.addBaby(mother);
        }
        births++;
        String reason = missingBreed(mother);
        if (reason == null) {
            return AnimalSpawnGuard.addBaby(mother);
        }
        if (++blocked <= DETAIL_LIMIT) {
            report(mother, reason);
        }
        return null;
    }

    /** null＝原版查得到（或原版自己會提早返回）；否則回缺失原因。 */
    static String missingBreed(IsoAnimal mother) {
        AnimalDefinitions adef = mother.adef;
        if (adef == null || adef.babyType == null) {
            return null; // 原版：adef null 會自己拋；babyType null 原版回 null，不造物件
        }
        AnimalDefinitions babyDef = AnimalDefinitions.getDef(adef.babyType);
        if (babyDef == null) {
            return "babyDef=null";
        }
        AnimalBreed breed = mother.getBreed();
        if (breed == null) {
            return "motherBreed=null";
        }
        if (babyDef.getBreedByName(breed.getName()) == null) {
            return "babyBreed=null";
        }
        return null;
    }

    private static void report(IsoAnimal mother, String reason) {
        try {
            AnimalBreed breed = mother.getBreed();
            DebugLog.log(TAG + "skip birth reason=" + reason + " n=" + blocked + " births=" + births
                    + " mother=" + mother.getAnimalType() + "#" + mother.getAnimalID()
                    + " breed=" + (breed == null ? "null" : breed.getName())
                    + " babyType=" + mother.adef.babyType
                    + " pos=" + (int) mother.getX() + "," + (int) mother.getY() + "," + (int) mother.getZ()
                    + "（幼崽定義查不到母獸品種，原版會改抽隨機品種）");
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    static boolean enabledForTest() { return ENABLED; }
    static long birthsForTest() { return births; }
    static long blockedForTest() { return blocked; }
    static long anomaliesForTest() { return anomalies; }

    private BabyBreedGuard() {}
}
