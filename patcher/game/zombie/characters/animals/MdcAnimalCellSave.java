package zombie.characters.animals;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugLog;
import zombie.iso.SliceY;

/**
 * W37 apop 存檔先序列化再開檔（2026-09-26；docs/patches.md 2az）。
 *
 * <p>原版 {@code AnimalCell.save()} 先 {@code new FileOutputStream(apop_X_Y.bin)}（當場截斷），
 * 才序列化；序列化拋 RuntimeException（9/16、9/24、9/26 皆為 data=null 的動物）時檔案留成 0 bytes，
 * 下次載入 {@code newLimit < 0} 失敗→{@code spawnAnimalsInCell} 重生野生動物，原 cell 動物全滅；
 * 例外還一路打斷 {@code QueuedSaveAll} 後段與關機 hook。
 *
 * <p>本 helper 取代全 jar 僅有的兩個 {@code save()} 呼叫點（worker.save、cell.unload）：同一把
 * {@code SliceBufferLock}、同一個檔名，先序列化到 SliceBuffer，成功才開檔寫入。序列化失敗
 * （RuntimeException，或 W50 保不住掛鉤狀態時拋出的 IOException）時保留舊檔、標回 dataChanged
 * 讓下次重試、不外拋。需在 {@code zombie.characters.animals} 套件內以使用 package-private API。
 * kill switch {@code -Dmdc.animalCellSave=0}（W50 的掛鉤存檔依賴本刀，關閉時一併停用）。
 *
 * <p><b>重試不重複寫出世界中動物</b>（2026-10-01，docs/patches.md 2bn）：{@code saveRealAnimals} 把世界中
 * 動物包成暫存清單 {@code saveRealAnimalHack}，原版只在 {@code AnimalCell.save(ByteBuffer)} 整份成功後才清。
 * 失敗後清單留著，下一輪 {@code saveRealAnimals} 又把同一批加進去，{@code AnimalChunk.save} 不去重＝同一隻寫兩次。
 * {@code AnimalManagerWorker.saveRealAnimals} 頭部先清掉上一輪殘留的清單（此時必然是失敗或未寫出的舊快照，
 * 本輪會重新收集）。失敗當下不清：在下一輪收集之前的重試仍帶著世界中動物，不會把牠們漏寫。
 */
public final class MdcAnimalCellSave {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalCellSave"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalCellSave] ";

    private static long saves, failures, staleSnapshots;

    public static void save(AnimalCell cell) {
        if (!ENABLED) {
            cell.save();
            return;
        }
        if (!cell.isLoaded() || Core.getInstance().isNoSave()) {
            return;
        }
        synchronized (SliceY.SliceBufferLock) {
            String fileName = ZomboidFileSystem.instance.getFileNameInCurrentSave(
                    "apop", "apop_" + cell.x + "_" + cell.y + ".bin");
            ByteBuffer out = SliceY.SliceBuffer;
            out.clear();
            try {
                cell.save(out);
            } catch (IOException e) {
                failures++;
                cell.dataChanged = true;
                DebugLog.log(TAG + "serialize failed (io), kept previous file " + fileName + " failures=" + failures
                        + " saves=" + saves + " error=" + e);
                return;
            } catch (RuntimeException e) {
                failures++;
                cell.dataChanged = true;
                DebugLog.log(TAG + "serialize failed, kept previous file " + fileName + " failures=" + failures
                        + " saves=" + saves + " error=" + e);
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(fileName);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                bos.write(out.array(), 0, out.position());
                saves++;
            } catch (IOException e) {
                ExceptionLogger.logException(e);
            }
        }
    }

    /** {@code AnimalManagerWorker.saveRealAnimals} 頭部：清掉上一輪失敗留下的世界中動物快照。 */
    public static void clearStaleRealSnapshots(AnimalManagerWorker worker) {
        if (!ENABLED) {
            return;
        }
        for (int i = 0; i < worker.loadedCells.size(); i++) {
            AnimalCell cell = worker.loadedCells.get(i);
            if (cell.saveRealAnimalHack != null) {
                staleSnapshots += cell.saveRealAnimalHack.size();
                cell.saveRealAnimalHack.clear();
                cell.saveRealAnimalHack = null;
                DebugLog.log(TAG + "cleared stale real-animal snapshots cell=" + cell.x + "," + cell.y
                        + " staleSnapshots=" + staleSnapshots);
            }
        }
    }

    static boolean enabled() { return ENABLED; }

    static boolean enabledForTest() { return ENABLED; }
    static long failuresForTest() { return failures; }
    static long staleSnapshotsForTest() { return staleSnapshots; }

    private MdcAnimalCellSave() {}
}
