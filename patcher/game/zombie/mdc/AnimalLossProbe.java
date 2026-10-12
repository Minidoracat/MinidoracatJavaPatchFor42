package zombie.mdc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * W60 動物遺失／複製觀測（2026-10-13；docs/patches.md 2bx）。純觀測：每個原方法照常呼叫恰一次，回傳與例外都與原版相同。
 *
 * <p>2026-10-13 一座雞舍的 4 隻公雞（同一個 animalId 的複製體）在玩家搬走一隻後少了一隻，沒有死亡紀錄；
 * 全服 apop 另有 15 群同 ID 複製體。原版有三條會讓動物憑空多出或消失、又不留 log 的路徑，各掛一個觀測點：
 * <ol>
 *   <li><b>放下動物</b>（{@code IsoGridSquare.AddWorldInventoryItem} 的 AnimalInventoryItem 分支）：新建 IsoAnimal、
 *       {@code copyFrom} 手上的動物，再以手上動物的 onlineID 查 {@code AnimalInstanceManager}，查到的「前持有者」
 *       若不是新動物就移出世界。從雞舍載入的動物在伺服器上從未登記 onlineID（{@code IsoAnimal.load} 只在 client
 *       設定，1 參數建構子不走 {@code init}，值停在 IsoPlayer 預設 1），查到的會是別隻剛好持有這個 ID 的動物。
 *       記 {@code drop-evict}（移走的不是手上那隻）與 {@code drop-dup}（手上那隻放下後仍在世界上）。</li>
 *   <li><b>chunk 卸載後立刻重載</b>：{@code LoaderThread} 載入前的 {@code saveNow} 只處理佇列裡的存檔工作，
 *       存檔執行緒正在寫的那份不在佇列裡，載入可能讀到舊檔或寫到一半的檔。雞舍內的動物跟著 chunk 存，
 *       舍外的在 APM 記憶體，讀到舊雞舍就會多一份或少一隻。記 {@code reload-race}（載入時另一條執行緒正在存同一個
 *       chunk 的卸載存檔）與 {@code double-write}（兩條執行緒同時存同一個 chunk）。</li>
 *   <li><b>長大</b>（{@code AnimalData.grow}）：{@code checkForChickenpocalypse} 在 ±4 格內找到同 animalId 的動物時
 *       刪掉長大前的動物，grow 再刪掉新動物，只印 {@code DebugType.Animal.println}（伺服器 Animal 等級 Warning 不印）。
 *       記 {@code grow-dup}。</li>
 * </ol>
 *
 * <p>放下與長大在主執行緒；chunk 載入與存檔在 {@code LoaderThread}／{@code SaveChunk} 執行緒，計數用
 * AtomicLong、存檔中的 chunk 用 ConcurrentHashMap。簿記全部在 try 內，例外只計 {@code anomalies}；
 * 原方法在 try 外呼叫。明細行每 60 秒最多 30 行（其餘計 suppressed），每 5 分鐘一行 beat。
 * kill switch {@code -Dmdc.animalLossProbe=0|off}：只呼叫原方法，不記帳也不印。
 */
public final class AnimalLossProbe {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.animalLossProbe"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalLossProbe] ";
    private static final long WINDOW_NS = 60_000_000_000L;
    private static final int WINDOW_LINES = 30;
    private static final long BEAT_NS = 300_000_000_000L;

    static final AtomicLong drops = new AtomicLong();
    static final AtomicLong evicted = new AtomicLong();
    static final AtomicLong dropDups = new AtomicLong();
    static final AtomicLong loads = new AtomicLong();
    static final AtomicLong unloadedSaves = new AtomicLong();
    static final AtomicLong reloadRaces = new AtomicLong();
    static final AtomicLong doubleWrites = new AtomicLong();
    static final AtomicLong growDups = new AtomicLong();
    static final AtomicLong logged = new AtomicLong();
    static final AtomicLong suppressed = new AtomicLong();
    static final AtomicLong anomalies = new AtomicLong();

    /** 一份進行中的卸載存檔。 */
    record Saving(Long key, long threadId, String threadName, long beginNs) {}

    private static final ConcurrentHashMap<Long, Saving> SAVING = new ConcurrentHashMap<>();

    // ---- 放下動物：copyFrom → get → add 在同一次呼叫裡依序發生（主執行緒） ----
    private static Thread dropThread;
    private static IsoAnimal dropSrc;
    private static IsoAnimal dropPrev;
    private static String dropPrevDesc;

    private static long windowStart;
    private static int windowLines;
    private static volatile long lastBeatNs;
    private static volatile boolean announced;

    private AnimalLossProbe() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    // ================= 放下動物 =================

    /** 取代 AddWorldInventoryItem 內的 {@code animal.copyFrom(animalItem.getAnimal())}。 */
    public static void dropCopy(IsoAnimal dst, IsoAnimal src) {
        if (ENABLED) {
            try {
                dropThread = Thread.currentThread();
                dropSrc = src;
                dropPrev = null;
                dropPrevDesc = null;
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
        dst.copyFrom(src);
    }

    /** 取代 AddWorldInventoryItem 內的 {@code AnimalInstanceManager.getInstance().get(reuseId)}。 */
    public static IsoAnimal dropLookup(AnimalInstanceManager aim, short id) {
        IsoAnimal prev = aim.get(id);
        if (ENABLED) {
            try {
                if (dropThread == Thread.currentThread()) {
                    dropPrev = prev;
                    if (prev != null && prev != dropSrc) {
                        // 原版接著就把這隻移出世界，先記下牠的位置與狀態；描述失敗時明細寫 ?
                        dropPrevDesc = "?";
                        dropPrevDesc = describe(prev);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
        return prev;
    }

    /** 取代 AddWorldInventoryItem 內的 {@code AnimalInstanceManager.getInstance().add(animal, reuseId)}。 */
    public static void dropRegister(AnimalInstanceManager aim, IsoAnimal animal, short id) {
        if (ENABLED) {
            try {
                onDrop(animal, id);
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            } finally {
                dropThread = null;
                dropSrc = null;
                dropPrev = null;
                dropPrevDesc = null;
            }
        }
        aim.add(animal, id);
    }

    private static void onDrop(IsoAnimal animal, short id) {
        long now = System.nanoTime();
        drops.incrementAndGet();
        if (dropThread != Thread.currentThread()) {
            anomalies.incrementAndGet();
            return;
        }
        IsoAnimal src = dropSrc;
        IsoAnimal prev = dropPrev;
        // 新動物由建構子放在放下的格子上；登記時還沒 setCurrent，所以用座標
        String at = (int) animal.getX() + "," + (int) animal.getY() + "," + (int) animal.getZ();
        if (prev != null && prev != src) {
            long n = evicted.incrementAndGet();
            detail(now, "drop-evict#" + n + " at=" + at + " dropped=" + name(src) + " onlineId=" + id
                    + " evicted=" + dropPrevDesc);
        }
        if (src != null && src != prev && src.isExistInTheWorld()) {
            long n = dropDups.incrementAndGet();
            detail(now, "drop-dup#" + n + " at=" + at + " dropped=" + name(src) + " onlineId=" + id
                    + " source=" + describe(src) + " prev=" + (prev == null ? "none" : name(prev)));
        }
        maybeBeat(now);
    }

    // ================= chunk 卸載存檔與重載 =================

    /** 取代 {@code ServerChunkLoader$SaveUnloadedTask.save} 內的 {@code chunk.Save(false)}。 */
    public static void saveUnloaded(IsoChunk chunk, boolean saveOnly) throws IOException {
        Saving mine = ENABLED ? beginSave(chunk.wx, chunk.wy) : null;
        try {
            chunk.Save(saveOnly);
        } finally {
            endSave(mine);
        }
    }

    static void endSave(Saving mine) {
        if (mine != null) {
            SAVING.remove(mine.key(), mine);
        }
    }

    /** 登記本執行緒開始存這個 chunk；另一條執行緒也正在存同一個 chunk 時記 double-write。失敗回 null。 */
    static Saving beginSave(int wx, int wy) {
        try {
            Thread t = Thread.currentThread();
            Saving mine = new Saving(key(wx, wy), t.threadId(), t.getName(), System.nanoTime());
            unloadedSaves.incrementAndGet();
            Saving other = SAVING.put(mine.key(), mine);
            if (other != null && other.threadId() != mine.threadId()) {
                long n = doubleWrites.incrementAndGet();
                detail(mine.beginNs(), "double-write#" + n + " chunk=" + wx + "," + wy
                        + " threads=" + other.threadName() + "," + mine.threadName()
                        + " otherElapsedMs=" + (mine.beginNs() - other.beginNs()) / 1_000_000L);
            }
            return mine;
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            return null;
        }
    }

    /** 取代 {@code ServerChunkLoader$LoaderThread.run} 內的 {@code chunk.LoadChunk(wx, wy, null)}。 */
    public static boolean loadChunk(IsoChunk chunk, int wx, int wy, ByteBuffer bb) {
        boolean recheck = ENABLED && beforeLoad(wx, wy);
        boolean loaded = chunk.LoadChunk(wx, wy, bb);
        if (recheck) {
            try {
                checkRace(wx, wy, System.nanoTime(), "during");
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
        return loaded;
    }

    /** 載入前檢查；回 true＝還沒記到 race，載入後要再查一次。 */
    static boolean beforeLoad(int wx, int wy) {
        try {
            long now = System.nanoTime();
            loads.incrementAndGet();
            boolean raced = checkRace(wx, wy, now, "before");
            maybeBeat(now);
            return !raced;
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            return false;
        }
    }

    /** 另一條執行緒正在存同一個 chunk 的卸載存檔＝這次載入可能讀到舊檔或寫到一半的檔。 */
    private static boolean checkRace(int wx, int wy, long now, String phase) {
        Saving s = SAVING.get(key(wx, wy));
        if (s == null || s.threadId() == Thread.currentThread().threadId()) {
            return false;
        }
        long n = reloadRaces.incrementAndGet();
        detail(now, "reload-race#" + n + " chunk=" + wx + "," + wy + " phase=" + phase
                + " savingThread=" + s.threadName() + " saveElapsedMs=" + (now - s.beginNs()) / 1_000_000L);
        return true;
    }

    // ================= 長大 =================

    /** 取代 {@code AnimalData.grow} 內的 {@code parent.checkForChickenpocalypse(newAnimal)}。 */
    public static boolean growDuplicate(IsoAnimal parent, IsoAnimal newAnimal) {
        IsoGridSquare sq = null;
        String parentDesc = null;
        if (ENABLED) {
            try {
                sq = parent.getCurrentSquare();
                parentDesc = sq == null ? null : describe(parent);
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
                sq = null;
            }
        }
        boolean deleted = parent.checkForChickenpocalypse(newAnimal);
        if (deleted && sq != null) {
            String twin;
            try {
                twin = findTwin(sq, parent, newAnimal);
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
                twin = "error";
            }
            try {
                long now = System.nanoTime();
                long n = growDups.incrementAndGet();
                detail(now, "grow-dup#" + n + " animal=" + parentDesc + " newType=" + newAnimal.getAnimalType()
                        + " twin=" + twin);
                maybeBeat(now);
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
        return deleted;
    }

    /** 原版的範圍：x、y 各 [-4, +4)、同一層。 */
    private static String findTwin(IsoGridSquare sq, IsoAnimal parent, IsoAnimal newAnimal) {
        IsoCell cell = sq.getCell();
        for (int x = sq.x - 4; x < sq.x + 4; x++) {
            for (int y = sq.y - 4; y < sq.y + 4; y++) {
                IsoGridSquare s = cell.getGridSquare(x, y, sq.z);
                if (s == null) {
                    continue;
                }
                for (IsoAnimal other : s.getAnimals()) {
                    if (other != parent && other != newAnimal && other.getAnimalID() == parent.getAnimalID()) {
                        return describe(other);
                    }
                }
            }
        }
        return "?";
    }

    // ================= 共用 =================

    static long key(int wx, int wy) {
        return ((long) wx << 32) | (wy & 0xffffffffL);
    }

    private static String name(IsoAnimal a) {
        return a == null ? "null" : a.getAnimalType() + "#" + a.getAnimalID();
    }

    static String describe(IsoAnimal a) {
        return name(a) + " pos=" + (int) a.getX() + "," + (int) a.getY() + "," + (int) a.getZ()
                + " inWorld=" + (a.isExistInTheWorld() ? 1 : 0) + " hutch=" + (a.getHutch() != null ? 1 : 0)
                + " vehicle=" + (a.getVehicle() != null ? 1 : 0) + " dead=" + (a.isDead() ? 1 : 0);
    }

    private static synchronized void detail(long now, String line) {
        if (now - windowStart >= WINDOW_NS) {
            windowStart = now;
            windowLines = 0;
        }
        if (windowLines >= WINDOW_LINES) {
            suppressed.incrementAndGet();
            return;
        }
        windowLines++;
        logged.incrementAndGet();
        DebugLog.log(TAG + line);
    }

    /** 每 5 分鐘一行（首次呼叫立即一行）。 */
    private static void maybeBeat(long now) {
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        synchronized (AnimalLossProbe.class) {
            if (announced && now - lastBeatNs < BEAT_NS) {
                return;
            }
            String head = announced ? "beat " : "首次生效（純觀測；-Dmdc.animalLossProbe=0|off 停用）";
            announced = true;
            lastBeatNs = now;
            DebugLog.log(TAG + head + "drops=" + drops + " evicted=" + evicted + " dropDups=" + dropDups
                    + " loads=" + loads + " unloadedSaves=" + unloadedSaves + " reloadRaces=" + reloadRaces
                    + " doubleWrites=" + doubleWrites + " growDups=" + growDups + " logged=" + logged
                    + " suppressed=" + suppressed + " anomalies=" + anomalies);
        }
    }

    static Saving savingForTest(int wx, int wy) {
        return SAVING.get(key(wx, wy));
    }
}
