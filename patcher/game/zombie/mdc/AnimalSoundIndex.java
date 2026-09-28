package zombie.mdc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import zombie.WorldSoundManager;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;

/**
 * W48-2 動物聽覺空間索引（2026-09-28；docs/patches.md 2bk）。
 *
 * <p><b>量測</b>（W48，9/28 06:00 後開服 10 分鐘）：伺服器每幀約 525 次 {@code getSoundAnimal}，每次整份掃過約 4,300 個聲音，
 * 其中 98% 會影響動物，但平均只有 12 個在範圍內；兩次心跳間佔主執行緒 4.8%。
 *
 * <p><b>做法</b>：只依位置找「可能在範圍內」的聲音，逐一照原版算式計算，取最大聲者；同音量取清單中較前者（原版以
 * 「嚴格大於才換」依序掃描，等於取最大值中索引最小者），所以不必依序掃描。聲音依影響半徑分三類放入兩張網格：
 * 半徑×3＋2 ≤ 64 的放 64 格網格、≤ 512 的放 512 格網格（查詢時各看動物所在格與周圍 8 格），更大的每次都查。
 * 用 ×3（野生動物加成）與 2 格餘裕：不在周圍格內的聲音，其水平距離已大於「半徑×3＋2」，原版的浮點距離不可能
 * 落在範圍內。會影響動物、音量大於 0、半徑不為 0 才入索引；其餘原版永遠選不到。
 *
 * <p><b>清單變動</b>：{@code WorldSoundManager} 建構子把 {@code soundList} 的新 ArrayList 包成 {@link SoundList}
 * （Patcher FieldPutWrap）。原版只在尾端追加（{@code addSound}）、在 {@code update()} 移除到期者、{@code KillCell} 清空。
 * 索引記下 ArrayList 的 {@code modCount}、追加次數與 {@code set} 次數：只有追加時，把新元素補進索引；其他結構變動或
 * {@code set} 都整份重建；會繞過計數的操作（見 {@link SoundList}）把清單永久標成不可信、改走原版。聲音在清單中時欄位不被
 * 原地改寫、旗標只可能被關掉（寫入位置由 SmokeCheck 釘住，物件池生命週期為 42.20.4 人工查核；查詢時即時重查旗標）；
 * 另每 256 次比對一次原版結果，不一致即本次啟動永久停用並記錄。
 *
 * <p>只在伺服器（{@code GameServer.server}）走索引；client 原版用 chunk 清單。清單含 null、動物座標非有限值或超出範圍時，
 * 該次改呼叫原版（含原版的例外）。{@code -Dmdc.animalSoundIndex}：{@code 1|on}（預設）、{@code 2|observe}（每次比對、
 * 回傳原版）、{@code 0|off}（不包清單、全走原版）；需重啟。主執行緒使用；先讀好動物座標，再於清單鎖內（與 addSound 同一把）
 * 完成索引維護、查詢與比對，其他執行緒的追加不會插在中間。
 */
public final class AnimalSoundIndex {
    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.animalSoundIndex"));

    private static final int NEAR = 64;
    private static final int MID = 512;
    private static final int MARGIN = 2;
    private static final int AUDIT_MASK = 255;
    private static final float COORD_LIMIT = 1.0e7F;
    private static final int DETAIL_LIMIT = 10;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalSoundIndex] ";

    // ---- 索引（主執行緒單寫）----
    private static WorldSoundManager.WorldSound[] sounds = new WorldSoundManager.WorldSound[1024];
    private static int[] listIndex = new int[1024];
    private static int[] nextInCell = new int[1024];
    private static int entryCount;
    private static int[] far = new int[64];
    private static int farCount;
    private static final CellTable NEAR_CELLS = new CellTable();
    private static final CellTable MID_CELLS = new CellTable();
    private static SoundList indexed;
    private static int indexedMods;
    private static int indexedAppends;
    private static int indexedSets;
    private static int indexedSize;
    private static boolean indexValid;
    private static boolean indexHasNull;

    // ---- 單次查詢的暫存 ----
    private static float qx;
    private static float qy;
    private static float qz3;
    private static float qBonus;
    private static WorldSoundManager.WorldSound best;
    private static float bestVolume;
    private static int bestIndex;
    private static int evaluated;

    // ---- 計數 ----
    private static long calls;
    private static long fast;
    private static long fallbackNotServer;
    private static long fallbackUntrusted;
    private static long fallbackNull;
    private static long fallbackCoords;
    private static long rebuilds;
    private static long rebuildNs;
    private static long tailAppends;
    private static long candidateSum;
    private static long audits;
    private static long auditMisses;
    private static long observeMismatches;
    private static long anomalies;
    private static boolean disabled;
    private static long lastBeatNs;
    private static boolean announced;

    private AnimalSoundIndex() {
    }

    static int parseMode(String raw) {
        if (raw == null) {
            return MODE_ON;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0":
            case "off":
                return MODE_OFF;
            case "2":
            case "observe":
                return MODE_OBSERVE;
            default:
                return MODE_ON;
        }
    }

    /** WorldSoundManager 建構子寫入 {@code soundList} 前的包裝；off 或非原版空 ArrayList 時原樣回傳。 */
    public static List<WorldSoundManager.WorldSound> wrapSoundList(List<WorldSoundManager.WorldSound> vanilla) {
        if (MODE == MODE_OFF || vanilla == null || vanilla.getClass() != ArrayList.class || !vanilla.isEmpty()) {
            return vanilla;
        }
        return new SoundList();
    }

    /** 取代原版 {@code getSoundAnimal}（經 W48 AnimalSoundProbe 呼叫）；回傳值與原版相同。 */
    static WorldSoundManager.WorldSound getSoundAnimal(WorldSoundManager manager, IsoAnimal animal) {
        if (MODE == MODE_OFF || disabled) {
            return manager.getSoundAnimal(animal);
        }
        calls++;
        maybeBeat();
        if (!GameServer.server) {
            fallbackNotServer++;
            return manager.getSoundAnimal(animal);
        }
        if (!(manager.soundList instanceof SoundList list)) {
            fallbackUntrusted++;
            return manager.getSoundAnimal(animal);
        }
        if (animal.getCurrentSquare() == null) {
            return null; // 原版第一步
        }
        float x = animal.getX();
        float y = animal.getY();
        if (!(Math.abs(x) < COORD_LIMIT) || !(Math.abs(y) < COORD_LIMIT)) {
            fallbackCoords++;
            return manager.getSoundAnimal(animal);
        }
        float z3 = animal.getZ() * 3.0F;
        float bonus = animal.isWild() ? 3.0F : 1.0F;
        // 維護索引、查詢、比對都在同一段清單鎖內（與 addSound 相同的鎖），其他執行緒的追加不會插在索引與原版比對之間。
        synchronized (list) {
            return lockedQuery(manager, animal, list, x, y, z3, bonus);
        }
    }

    private static WorldSoundManager.WorldSound lockedQuery(WorldSoundManager manager, IsoAnimal animal, SoundList list,
            float x, float y, float z3, float bonus) {
        if (list.untrusted) {
            fallbackUntrusted++;
            return manager.getSoundAnimal(animal);
        }
        try {
            ensureIndex(list);
        } catch (RuntimeException e) {
            anomalies++;
            indexValid = false;
            return manager.getSoundAnimal(animal);
        }
        if (indexHasNull) {
            fallbackNull++;
            return manager.getSoundAnimal(animal); // 原版在 null 元素上拋出，照原樣
        }
        WorldSoundManager.WorldSound result = query(x, y, z3, bonus);
        fast++;
        if (MODE == MODE_OBSERVE) {
            audits++;
            WorldSoundManager.WorldSound vanilla = manager.getSoundAnimal(animal);
            if (vanilla != result) {
                observeMismatches++;
                report("observe 比對不一致", animal, result, vanilla, list);
            }
            return vanilla;
        }
        if ((fast & AUDIT_MASK) == 0L) {
            audits++;
            WorldSoundManager.WorldSound vanilla = manager.getSoundAnimal(animal);
            if (vanilla != result) {
                auditMisses++;
                disabled = true;
                report("抽樣比對不一致，本次啟動改回原版", animal, result, vanilla, list);
                return vanilla;
            }
        }
        return result;
    }

    // ---- 查詢 ----

    private static WorldSoundManager.WorldSound query(float x, float y, float z3, float bonus) {
        qx = x;
        qy = y;
        qz3 = z3;
        qBonus = bonus;
        best = null;
        bestVolume = 0.0F;
        bestIndex = Integer.MAX_VALUE;
        evaluated = 0;
        int fx = (int) Math.floor(x);
        int fy = (int) Math.floor(y);
        scanCells(NEAR_CELLS, Math.floorDiv(fx, NEAR), Math.floorDiv(fy, NEAR));
        scanCells(MID_CELLS, Math.floorDiv(fx, MID), Math.floorDiv(fy, MID));
        for (int i = 0; i < farCount; i++) {
            consider(far[i]);
        }
        candidateSum += evaluated;
        return best;
    }

    private static void scanCells(CellTable table, int cx, int cy) {
        if (table.size == 0) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int e = table.head(key(cx + dx, cy + dy)); e >= 0; e = nextInCell[e]) {
                    consider(e);
                }
            }
        }
    }

    /** 與原版迴圈內的算式逐項相同（運算元順序與型別一致）。 */
    private static void consider(int e) {
        evaluated++;
        WorldSoundManager.WorldSound sound = sounds[e];
        if (!(sound.stresshumans || sound.stressAnimals)) {
            return;
        }
        float distSq = IsoUtils.DistanceToSquared(qx, qy, qz3, sound.x, sound.y, sound.z * 3.0F);
        float radius = sound.radius * qBonus;
        if (!(distSq > radius * radius)) {
            float delta = 1.0F - distSq / (radius * radius);
            float volume = sound.volume * delta;
            int index = listIndex[e];
            if (volume > bestVolume || volume == bestVolume && best != null && index < bestIndex) {
                bestVolume = volume;
                best = sound;
                bestIndex = index;
            }
        }
    }

    // ---- 索引維護 ----

    /** 呼叫端持有清單鎖。 */
    private static void ensureIndex(SoundList list) {
        int mods = list.mods();
        int appends = list.appends;
        int size = list.size();
        int appended = appends - indexedAppends;
        boolean appendOnly = indexValid && list == indexed && list.sets == indexedSets
                && mods - indexedMods == appended && size - indexedSize == appended && appended >= 0;
        indexValid = false; // 重建或補尾途中拋出時，下次一定整份重建
        if (!appendOnly) {
            rebuild(list, size);
        } else if (appended > 0) {
            for (int i = indexedSize; i < size; i++) {
                add(list.get(i), i);
            }
            tailAppends += appended;
        }
        indexed = list;
        indexedMods = mods;
        indexedAppends = appends;
        indexedSets = list.sets;
        indexedSize = size;
        indexValid = true;
    }

    private static void rebuild(SoundList list, int size) {
        long t0 = System.nanoTime();
        NEAR_CELLS.reset();
        MID_CELLS.reset();
        entryCount = 0;
        farCount = 0;
        indexHasNull = false;
        for (int i = 0; i < size; i++) {
            add(list.get(i), i);
        }
        if (entryCount < sounds.length) {
            Arrays.fill(sounds, entryCount, sounds.length, null);
        }
        rebuilds++;
        rebuildNs += System.nanoTime() - t0;
    }

    /** 原版永遠選不到的聲音（不影響動物、音量 ≤ 0、半徑 0）不入索引。 */
    private static void add(WorldSoundManager.WorldSound sound, int index) {
        if (sound == null) {
            indexHasNull = true;
            return;
        }
        if (!(sound.stresshumans || sound.stressAnimals) || sound.volume <= 0 || sound.radius == 0) {
            return;
        }
        int e = entryCount++;
        if (e == sounds.length) {
            int cap = e * 2;
            sounds = Arrays.copyOf(sounds, cap);
            listIndex = Arrays.copyOf(listIndex, cap);
            nextInCell = Arrays.copyOf(nextInCell, cap);
        }
        sounds[e] = sound;
        listIndex[e] = index;
        long reach = Math.abs((long) sound.radius) * 3L + MARGIN;
        if (reach <= NEAR) {
            NEAR_CELLS.add(key(Math.floorDiv(sound.x, NEAR), Math.floorDiv(sound.y, NEAR)), e);
        } else if (reach <= MID) {
            MID_CELLS.add(key(Math.floorDiv(sound.x, MID), Math.floorDiv(sound.y, MID)), e);
        } else {
            if (farCount == far.length) {
                far = Arrays.copyOf(far, farCount * 2);
            }
            far[farCount++] = e;
        }
    }

    private static long key(int cx, int cy) {
        return (long) cx << 32 | cy & 0xFFFFFFFFL;
    }

    /** 以 long 為鍵的開放定址表：鍵 → 該格最後加入的索引項（以 nextInCell 串起同格各項）。 */
    private static final class CellTable {
        private long[] keys = new long[256];
        private int[] heads = new int[256];
        private int[] stamps = new int[256];
        private int stamp = 1;
        int size;

        void reset() {
            size = 0;
            if (++stamp == 0) {
                Arrays.fill(stamps, 0);
                stamp = 1;
            }
        }

        int head(long key) {
            int mask = keys.length - 1;
            for (int slot = mix(key) & mask; stamps[slot] == stamp; slot = slot + 1 & mask) {
                if (keys[slot] == key) {
                    return heads[slot];
                }
            }
            return -1;
        }

        void add(long key, int entry) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int slot = mix(key) & mask;
            while (stamps[slot] == stamp) {
                if (keys[slot] == key) {
                    nextInCell[entry] = heads[slot];
                    heads[slot] = entry;
                    return;
                }
                slot = slot + 1 & mask;
            }
            stamps[slot] = stamp;
            keys[slot] = key;
            nextInCell[entry] = -1;
            heads[slot] = entry;
            size++;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldHeads = heads;
            int[] oldStamps = stamps;
            int oldStamp = stamp;
            int cap = oldKeys.length * 2;
            keys = new long[cap];
            heads = new int[cap];
            stamps = new int[cap];
            stamp = 1;
            int mask = cap - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldStamps[i] == oldStamp) {
                    int slot = mix(oldKeys[i]) & mask;
                    while (stamps[slot] == stamp) {
                        slot = slot + 1 & mask;
                    }
                    stamps[slot] = stamp;
                    keys[slot] = oldKeys[i];
                    heads[slot] = oldHeads[i];
                }
            }
        }

        private static int mix(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ h >>> 32);
        }
    }

    /**
     * {@code soundList} 本體：與原版同一個 ArrayList 實作，只多記追加與 {@code set} 次數，其餘結構變動由 ArrayList 自己的
     * {@code modCount} 記錄。{@code replaceAll}、{@code sort}、{@code removeAll}、{@code retainAll} 會在回呼途中直接改寫內部陣列、
     * 到最後才遞增 {@code modCount}（回呼拋出時根本不遞增），視圖（{@code subList}／{@code reversed}）的寫入也繞過計數，
     * 所以呼叫前就標記為不可信、從此改走原版（原版從不呼叫它們）。{@code removeIf}／{@code addAll} 在任何改動前完成全部回呼，
     * 不受影響。
     */
    static final class SoundList extends ArrayList<WorldSoundManager.WorldSound> {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        int appends;
        int sets;
        boolean untrusted;

        int mods() {
            return modCount;
        }

        @Override
        public boolean add(WorldSoundManager.WorldSound sound) {
            appends++;
            return super.add(sound);
        }

        @Override
        public WorldSoundManager.WorldSound set(int index, WorldSoundManager.WorldSound sound) {
            sets++;
            return super.set(index, sound);
        }

        @Override
        public List<WorldSoundManager.WorldSound> subList(int fromIndex, int toIndex) {
            untrusted = true;
            return super.subList(fromIndex, toIndex);
        }

        @Override
        public List<WorldSoundManager.WorldSound> reversed() {
            untrusted = true;
            return super.reversed();
        }

        @Override
        public void replaceAll(java.util.function.UnaryOperator<WorldSoundManager.WorldSound> operator) {
            untrusted = true;
            super.replaceAll(operator);
        }

        @Override
        public void sort(java.util.Comparator<? super WorldSoundManager.WorldSound> comparator) {
            untrusted = true;
            super.sort(comparator);
        }

        @Override
        public boolean removeAll(java.util.Collection<?> c) {
            untrusted = true;
            return super.removeAll(c);
        }

        @Override
        public boolean retainAll(java.util.Collection<?> c) {
            untrusted = true;
            return super.retainAll(c);
        }
    }

    // ---- 記錄 ----

    private static void report(String what, IsoAnimal animal, WorldSoundManager.WorldSound ours,
            WorldSoundManager.WorldSound vanilla, List<WorldSoundManager.WorldSound> list) {
        if (auditMisses + observeMismatches > DETAIL_LIMIT) {
            return;
        }
        try {
            DebugLog.log(TAG + what + " animal=" + animal.getX() + "," + animal.getY() + "," + animal.getZ()
                    + " wild=" + animal.isWild() + " list=" + list.size()
                    + " ours=" + describe(ours, list) + " vanilla=" + describe(vanilla, list));
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static String describe(WorldSoundManager.WorldSound sound, List<WorldSoundManager.WorldSound> list) {
        if (sound == null) {
            return "null";
        }
        return "#" + list.indexOf(sound) + "(" + sound.x + "," + sound.y + "," + sound.z + " r=" + sound.radius
                + " v=" + sound.volume + ")";
    }

    private static void maybeBeat() {
        if ((calls & 0xFFFL) != 1L) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (announced && now - lastBeatNs < BEAT_NS) {
                return;
            }
            lastBeatNs = now;
            String head = announced ? "beat " : "首次生效（-Dmdc.animalSoundIndex=0|off 停用、2|observe 只比對）";
            announced = true;
            long f = Math.max(1L, fast);
            DebugLog.log(TAG + head + "mode=" + MODE + " calls=" + calls + " fast=" + fast
                    + " candAvg=" + candidateSum / f + " rebuilds=" + rebuilds
                    + " rebuildUsAvg=" + (rebuilds > 0 ? rebuildNs / rebuilds / 1000L : 0)
                    + " tailAppends=" + tailAppends + " entries=" + entryCount + " far=" + farCount
                    + " fallback[notServer=" + fallbackNotServer + " untrusted=" + fallbackUntrusted
                    + " null=" + fallbackNull + " coords=" + fallbackCoords + "]"
                    + " audits=" + audits + " auditMisses=" + auditMisses + " observeMismatches=" + observeMismatches
                    + " disabled=" + disabled + " anomalies=" + anomalies);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    // ---- 測試存取器 ----
    static long fastForTest() {
        return fast;
    }

    static long rebuildsForTest() {
        return rebuilds;
    }

    static long tailAppendsForTest() {
        return tailAppends;
    }

    static long candidateSumForTest() {
        return candidateSum;
    }

    static long auditsForTest() {
        return audits;
    }

    static long auditMissesForTest() {
        return auditMisses;
    }

    static long observeMismatchesForTest() {
        return observeMismatches;
    }

    static long fallbackUntrustedForTest() {
        return fallbackUntrusted;
    }

    static long fallbackNullForTest() {
        return fallbackNull;
    }

    static boolean disabledForTest() {
        return disabled;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
