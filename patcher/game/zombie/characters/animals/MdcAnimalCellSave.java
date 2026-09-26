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
 * {@code SliceBufferLock}、同一個檔名，先序列化到 SliceBuffer，成功才開檔寫入。序列化
 * RuntimeException 時保留舊檔、標回 dataChanged 讓下次重試、不外拋。IOException 照原版只記錄。
 * 需在 {@code zombie.characters.animals} 套件內以使用 package-private API。
 * kill switch {@code -Dmdc.animalCellSave=0}。
 */
public final class MdcAnimalCellSave {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalCellSave"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalCellSave] ";

    private static long saves, failures;

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
                ExceptionLogger.logException(e);
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

    static boolean enabledForTest() { return ENABLED; }
    static long failuresForTest() { return failures; }

    private MdcAnimalCellSave() {}
}
