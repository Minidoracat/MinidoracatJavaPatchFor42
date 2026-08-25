package zombie.characters.animals;

import zombie.mdc.AnimalPersistGuard;

/**
 * W16 的 package-private 薄轉發 probe（docs/patches.md 2ad）。game 的 n_unloadChunk／
 * n_addAnimal／Main.addAnimal／Worker.addAnimal／cell/chunk lookup 都是 package-private；本 class
 * 與它們同套件，只做「原樣委派 → 成功後回報 guard → 原樣回傳」，不包 try、不改例外 precedence。
 */
public final class MdcAnimalPersistProbe {

    private MdcAnimalPersistProbe() {}

    /** APM.removeChunkFromWorld offset 25：native unload 成功後才開始 Java 掃描計時。 */
    public static void unloadChunk(AnimalPopulationManager manager, int wx, int wy) {
        manager.n_unloadChunk(wx, wy);
        AnimalPersistGuard.scanStarted();
    }

    /** unload 掃描內的 n_addAnimal：成功返回才算 handedOff。 */
    public static void queueUnloadedAnimal(AnimalPopulationManager manager, IsoAnimal animal) {
        manager.n_addAnimal(animal);
        AnimalPersistGuard.recordHandedOff();
    }

    /** public virtualizeAnimal 內的 n_addAnimal：與 unload 來源分帳。 */
    public static void queueVirtualizedAnimal(AnimalPopulationManager manager, IsoAnimal animal) {
        manager.n_addAnimal(animal);
        AnimalPersistGuard.recordVirtualized();
    }

    /** AnimalZones.spawnAnimalsOnZone 的 Main.addAnimal：zone spawn 來源。 */
    public static void addZoneAnimal(AnimalManagerMain manager, VirtualAnimal animal) {
        manager.addAnimal(animal);
        AnimalPersistGuard.recordZoneAdd();
    }

    /** Worker.moveAnimal 的 self addAnimal：devirtualize 邊界搬家來源。 */
    public static void addMovedAnimal(AnimalManagerWorker worker, VirtualAnimal animal) {
        worker.addAnimal(animal);
        AnimalPersistGuard.recordMovedAdd();
    }

    /** Worker.addAnimal 的第一道 cell 門；null＝S1。 */
    public static AnimalCell cellForAdd(AnimalManagerWorker worker, int x, int y) {
        AnimalCell cell = worker.getCellFromSquarePos(x, y);
        AnimalPersistGuard.recordAddAttempt(cell == null, x, y);
        return cell;
    }

    /** Worker.addAnimal 的第二道 chunk 門；null＝S1b。 */
    public static AnimalChunk chunkForAdd(AnimalCell cell, int x, int y) {
        AnimalChunk chunk = cell.getOrCreateChunkFromSquarePos(x, y);
        AnimalPersistGuard.recordChunkLookup(chunk == null, x, y);
        return chunk;
    }

    /** Worker.saveRealAnimals 的逐隻 cell 門；null＝該隻本輪 save skip。 */
    public static AnimalCell cellForSave(AnimalManagerWorker worker, int x, int y) {
        AnimalCell cell = worker.getCellFromSquarePos(x, y);
        AnimalPersistGuard.recordSaveLookup(cell == null, x, y);
        return cell;
    }
}
