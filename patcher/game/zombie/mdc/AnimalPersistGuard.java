package zombie.mdc;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import gnu.trove.set.hash.TIntHashSet;

import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/**
 * W16 動物卸載接手守衛 observe 階段（docs/patches.md 2ad）。本版只量化，不做 enforce；
 * 數據回來後才按設計決策表選刀。
 *
 * <p><b>per-wave 帳</b>：`IsoChunk.removeFromWorld` 內動物接手先於清場，但接手後動物仍留在
 * movingObjects。全域 {@code droppedAtClear - handedOff} 會被例外與跨 wave 正負差抵銷，
 * 不能作 S2 證據；本 helper 用 ThreadLocal 重用單一 {@link Wave}，由
 * {@link #unloadEnter} 開帳、{@link #chunkUnloadExit} 在 IsoChunk 的唯一 RETURN 前結帳：
 * <ul>
 *   <li>{@code scanSeen}：掃描看見動物，<b>在</b>委派 {@code unloaded()} 前增加；即使
 *       unloaded 拋錯，也不會把「掃描有看見」誤成 S2。</li>
 *   <li>{@code handedOff}：{@code n_addAnimal()} 成功返回後增加；n_addAnimal 拋錯不會假記成功。
 *       其內 Worker.addAnimal 的靜默 null return 仍會正常返回，分別由 cellNull/chunkNull 計數。</li>
 *   <li>{@code s2Missed}：只累加<b>完整 wave</b>的正差 {@code cleared - seen}；負差另進
 *       {@code clearShortfall}，永不互相抵銷。掃描／清場中途例外使 tail 不達時，下一個 enter
 *       記 {@code unpairedWaves}；掃描未正常到尾則記 {@code abortedWaves}。</li>
 *   <li>{@code queueFailures}：完整 wave 的正差 {@code seen - handedOff}。</li>
 * </ul>
 *
 * <p><b>來源帳</b>：Worker.addAnimal 不只來自 unload。probe 在真正上游邊界另計
 * {@code virtualized/zoneAdds/movedAdds}；heartbeat 的
 * {@code sourceGap = attempts - handedOff - virtualized - zoneAdds - movedAdds}。SmokeCheck
 * 釘 jar-wide 全域 caller：Main.addAnimal 只來自 APM.n_addAnimal＋AnimalZones，各 1；
 * Worker.addAnimal 只來自 Main.addAnimal＋Worker.moveAnimal，各 1。
 *
 * <p><b>掛點</b>（42.20.3 javap）：
 * <ul>
 *   <li>APM.removeChunkFromWorld：headCall 開帳；n_unloadChunk redirect 成功後開始計時；
 *       unloaded redirect 記 scanSeen；n_addAnimal redirect 記 handedOff；尾部 TIntHashSet.remove
 *       redirect 在委派前截 end、委派成功後結算 scanNs。</li>
 *   <li>APM.virtualizeAnimal、AnimalZones.spawnAnimalsOnZone、Worker.moveAnimal：redirect
 *       package-private add 邊界，委派成功後分來源計數。</li>
 *   <li>Worker.addAnimal：cell/chunk null probe；兩個 S3 ArrayList.remove(int) redirect 計
 *       duplicateRemoved。Worker.saveRealAnimals：cell null probe。Main.saveRealAnimals：
 *       getObjectList redirect 計 world-save 實體動物分母。</li>
 *   <li>IsoChunk.removeFromWorld：兩個 IsoMovingObject.removeFromWorld redirect 計 clear；
 *       TailCall 在唯一 RETURN 前結帳。</li>
 * </ul>
 *
 * <p><b>三態</b>：{@code -Dmdc.animalPersistGuard=0} off（head/tail 早退、redirect 純委派）；
 * `2`／未設 observe；`1` 保留給階段二，本版行為仍與 observe 相同。
 */
public final class AnimalPersistGuard {

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode();

    private static final String TAG = "[MinidoracatJavaPatch][AnimalPersistGuard] ";
    private static final long DETAIL_MIN_INTERVAL_MS = 5_000L;
    private static final long HEARTBEAT_EVERY_UNLOADS = 256L;

    private static final AtomicLong unloads = new AtomicLong();
    private static final AtomicLong scanSeen = new AtomicLong();
    private static final AtomicLong handedOff = new AtomicLong();
    private static final AtomicLong virtualized = new AtomicLong();
    private static final AtomicLong zoneAdds = new AtomicLong();
    private static final AtomicLong movedAdds = new AtomicLong();
    private static final AtomicLong attempts = new AtomicLong();
    private static final AtomicLong cellNullAtAdd = new AtomicLong();
    private static final AtomicLong chunkNullAtAdd = new AtomicLong();
    private static final AtomicLong cellNullAtSave = new AtomicLong();
    private static final AtomicLong duplicateRemoved = new AtomicLong();
    private static final AtomicLong droppedAtClear = new AtomicLong();
    private static final AtomicLong saveWaves = new AtomicLong();
    private static final AtomicLong lastSaveReal = new AtomicLong(-1L);

    private static final AtomicLong completedWaves = new AtomicLong();
    private static final AtomicLong abortedWaves = new AtomicLong();
    private static final AtomicLong unpairedWaves = new AtomicLong();
    private static final AtomicLong skippedWaves = new AtomicLong();
    private static final AtomicLong s2Missed = new AtomicLong();
    private static final AtomicLong clearShortfall = new AtomicLong();
    private static final AtomicLong queueFailures = new AtomicLong();

    private static final AtomicLong scanNsSum = new AtomicLong();
    private static final AtomicLong scanNsCount = new AtomicLong();
    private static final AtomicLong scanNsMax = new AtomicLong();
    private static final AtomicLong detailSuppressed = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();

    /** 主執行緒重用，不在每次 chunk unload 配置新物件。 */
    private static final ThreadLocal<Wave> CURRENT = ThreadLocal.withInitial(Wave::new);
    private static volatile long nextDetailMs;

    private static final class Wave {
        boolean active;
        boolean scanStarted;
        boolean scanCompleted;
        long startNs;
        long seen;
        long queued;
        long cleared;

        void begin() {
            active = true;
            scanStarted = false;
            scanCompleted = false;
            startNs = 0L;
            seen = 0L;
            queued = 0L;
            cleared = 0L;
        }

        void close() {
            active = false;
            scanStarted = false;
            scanCompleted = false;
            startNs = 0L;
        }
    }

    private AnimalPersistGuard() {}

    private static int parseMode() {
        String raw = System.getProperty("mdc.animalPersistGuard");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        return switch (raw.trim()) {
            case "0", "off" -> MODE_OFF;
            case "1", "enforce" -> MODE_ENFORCE;
            default -> MODE_OBSERVE;
        };
    }

    /** APM.removeChunkFromWorld 頭部：開 per-wave 帳。receiver 只滿足 HeadCall 形狀。 */
    public static void unloadEnter(AnimalPopulationManager manager) {
        if (MODE == MODE_OFF) {
            return;
        }
        Wave wave = CURRENT.get();
        if (wave.active) {
            unpairedWaves.incrementAndGet();
        }
        wave.begin();
        unloads.incrementAndGet();
    }

    /** n_unloadChunk 委派成功後呼叫：排除 native unload 與 heartbeat，只量後續 Java 掃描。 */
    public static void scanStarted() {
        if (MODE == MODE_OFF) {
            return;
        }
        Wave wave = CURRENT.get();
        if (wave.active) {
            wave.startNs = System.nanoTime();
            wave.scanStarted = true;
        }
    }

    /** 掃描看見動物時在原 unloaded() 之前記帳；原例外照樣穿透。 */
    public static void scanAnimal(IsoAnimal animal) {
        if (MODE != MODE_OFF) {
            scanSeen.incrementAndGet();
            Wave wave = CURRENT.get();
            if (wave.active) {
                wave.seen++;
            }
        }
        animal.unloaded();
    }

    /** n_addAnimal 成功返回後記 unload 接手；由同套件 probe 呼叫。 */
    public static void recordHandedOff() {
        if (MODE == MODE_OFF) {
            return;
        }
        handedOff.incrementAndGet();
        Wave wave = CURRENT.get();
        if (wave.active) {
            wave.queued++;
        }
    }

    public static void recordVirtualized() {
        if (MODE != MODE_OFF) {
            virtualized.incrementAndGet();
        }
    }

    public static void recordZoneAdd() {
        if (MODE != MODE_OFF) {
            zoneAdds.incrementAndGet();
        }
    }

    public static void recordMovedAdd() {
        if (MODE != MODE_OFF) {
            movedAdds.incrementAndGet();
        }
    }

    /** APM 尾部：在 TIntHashSet.remove 前截 end，委派成功後才標 scanCompleted。 */
    public static boolean unloadScanExit(TIntHashSet set, int key) {
        Wave wave = MODE == MODE_OFF ? null : CURRENT.get();
        long endNs = wave != null && wave.active && wave.scanStarted ? System.nanoTime() : 0L;
        boolean removed = set.remove(key);
        if (endNs != 0L) {
            long dt = endNs - wave.startNs;
            wave.scanCompleted = true;
            scanNsSum.addAndGet(dt);
            scanNsCount.incrementAndGet();
            scanNsMax.accumulateAndGet(dt, Math::max);
        }
        return removed;
    }

    /** S3 兩個 ArrayList.remove(int) 的同形 redirect：原樣委派後計實際被丟棄動物數。 */
    @SuppressWarnings("rawtypes")
    public static Object removeDuplicate(ArrayList list, int index) {
        Object removed = list.remove(index);
        if (MODE != MODE_OFF) {
            duplicateRemoved.incrementAndGet();
        }
        return removed;
    }

    /** O3 熱路徑：零配置、零 log；觀測後仍以 invokevirtual 動態分派原方法。 */
    public static void clearMoving(IsoMovingObject object) {
        if (MODE != MODE_OFF && GameServer.server && object instanceof IsoAnimal) {
            droppedAtClear.incrementAndGet();
            Wave wave = CURRENT.get();
            if (wave.active) {
                wave.cleared++;
            }
        }
        object.removeFromWorld();
    }

    /** IsoChunk.removeFromWorld 唯一 RETURN 前的 TailCall：per-wave 分類結帳。 */
    public static void chunkUnloadExit(IsoChunk chunk) {
        if (MODE == MODE_OFF) {
            return;
        }
        Wave wave = CURRENT.get();
        if (!wave.active) {
            skippedWaves.incrementAndGet();
            return;
        }
        if (!wave.scanCompleted) {
            abortedWaves.incrementAndGet();
        } else {
            completedWaves.incrementAndGet();
            long clearDelta = wave.cleared - wave.seen;
            if (clearDelta > 0L) {
                s2Missed.addAndGet(clearDelta);
                detail("S2 missed=" + clearDelta + " chunk=(" + chunk.wx + "," + chunk.wy + ")");
            } else if (clearDelta < 0L) {
                clearShortfall.addAndGet(-clearDelta);
            }
            long queueDelta = wave.seen - wave.queued;
            if (queueDelta > 0L) {
                queueFailures.addAndGet(queueDelta);
            } else if (queueDelta < 0L) {
                anomalies.incrementAndGet();
            }
        }
        wave.close();
        long n = unloads.get();
        if ((n & (HEARTBEAT_EVERY_UNLOADS - 1L)) == 0L) {
            heartbeat("unload-end");
        }
    }

    /** Main.saveRealAnimals 的 getObjectList redirect；回原 Set，不碰 vanilla 後續 iterate。 */
    public static Set<IsoMovingObject> saveScan(IsoCell cell) {
        Set<IsoMovingObject> objects = cell.getObjectList();
        if (MODE == MODE_OFF) {
            return objects;
        }
        saveWaves.incrementAndGet();
        try {
            int animals = 0;
            for (IsoMovingObject object : objects) {
                if (object instanceof IsoAnimal) {
                    animals++;
                }
            }
            lastSaveReal.set(animals);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            lastSaveReal.set(-1L);
        }
        // O4b 在本方法後半才執行；cellNullSave 是累積至上一個完成 save 的值，不作單 wave 對帳。
        heartbeat("save-start");
        return objects;
    }

    public static void recordAddAttempt(boolean cellNull, int x, int y) {
        if (MODE == MODE_OFF) {
            return;
        }
        attempts.incrementAndGet();
        if (cellNull) {
            cellNullAtAdd.incrementAndGet();
            detail("S1 cellNullAtAdd squares=(" + x + "," + y + ")");
        }
    }

    public static void recordChunkLookup(boolean chunkNull, int x, int y) {
        if (MODE != MODE_OFF && chunkNull) {
            chunkNullAtAdd.incrementAndGet();
            detail("S1b chunkNullAtAdd squares=(" + x + "," + y + ")");
        }
    }

    public static void recordSaveLookup(boolean cellNull, int x, int y) {
        if (MODE != MODE_OFF && cellNull) {
            cellNullAtSave.incrementAndGet();
            detail("S1 cellNullAtSave squares=(" + x + "," + y + ")");
        }
    }

    private static void detail(String message) {
        long now = System.currentTimeMillis();
        if (now < nextDetailMs) {
            detailSuppressed.incrementAndGet();
            return;
        }
        nextDetailMs = now + DETAIL_MIN_INTERVAL_MS;
        try {
            DebugLog.log(TAG + message);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    private static void heartbeat(String beat) {
        try {
            long count = scanNsCount.get();
            long sourceGap = attempts.get() - handedOff.get() - virtualized.get()
                    - zoneAdds.get() - movedAdds.get();
            DebugLog.log(TAG + "beat=" + beat
                    + " unloads=" + unloads.get()
                    + " completed=" + completedWaves.get()
                    + " aborted=" + abortedWaves.get()
                    + " unpaired=" + unpairedWaves.get()
                    + " skipped=" + skippedWaves.get()
                    + " scanSeen=" + scanSeen.get()
                    + " handedOff=" + handedOff.get()
                    + " droppedAtClear=" + droppedAtClear.get()
                    + " s2Missed=" + s2Missed.get()
                    + " clearShortfall=" + clearShortfall.get()
                    + " queueFailures=" + queueFailures.get()
                    + " attempts=" + attempts.get()
                    + " virtualized=" + virtualized.get()
                    + " zoneAdds=" + zoneAdds.get()
                    + " movedAdds=" + movedAdds.get()
                    + " sourceGap=" + sourceGap
                    + " cellNullAdd=" + cellNullAtAdd.get()
                    + " chunkNullAdd=" + chunkNullAtAdd.get()
                    + " duplicateRemoved=" + duplicateRemoved.get()
                    + " cellNullSave=" + cellNullAtSave.get()
                    + " saveWaves=" + saveWaves.get()
                    + " lastSaveReal=" + lastSaveReal.get()
                    + " scanAvgUs=" + (count == 0L ? 0L : scanNsSum.get() / count / 1_000L)
                    + " scanMaxUs=" + scanNsMax.get() / 1_000L
                    + " detailSuppressed=" + detailSuppressed.get()
                    + " anomalies=" + anomalies.get()
                    + " mode=" + MODE);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    // 測試鉤：累積計數取前後差值。
    static long unloadsForTest() { return unloads.get(); }
    static long scanSeenForTest() { return scanSeen.get(); }
    static long handedOffForTest() { return handedOff.get(); }
    static long virtualizedForTest() { return virtualized.get(); }
    static long zoneAddsForTest() { return zoneAdds.get(); }
    static long movedAddsForTest() { return movedAdds.get(); }
    static long attemptsForTest() { return attempts.get(); }
    static long cellNullAtAddForTest() { return cellNullAtAdd.get(); }
    static long chunkNullAtAddForTest() { return chunkNullAtAdd.get(); }
    static long cellNullAtSaveForTest() { return cellNullAtSave.get(); }
    static long duplicateRemovedForTest() { return duplicateRemoved.get(); }
    static long droppedAtClearForTest() { return droppedAtClear.get(); }
    static long completedWavesForTest() { return completedWaves.get(); }
    static long abortedWavesForTest() { return abortedWaves.get(); }
    static long unpairedWavesForTest() { return unpairedWaves.get(); }
    static long s2MissedForTest() { return s2Missed.get(); }
    static long clearShortfallForTest() { return clearShortfall.get(); }
    static long queueFailuresForTest() { return queueFailures.get(); }
    static long saveWavesForTest() { return saveWaves.get(); }
    static long lastSaveRealForTest() { return lastSaveReal.get(); }
    static long scanCountForTest() { return scanNsCount.get(); }
    static long anomaliesForTest() { return anomalies.get(); }
}
