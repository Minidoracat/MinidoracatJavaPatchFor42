package zombie.mdc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import zombie.core.utils.BoundedQueue;
import zombie.debug.DebugLog;
import zombie.iso.IsoChunk;
import zombie.iso.IsoFloorBloodSplat;

/**
 * W84 鄰居 chunk 血跡的位置索引（client；docs/patches.md 2cs）。
 *
 * <p>原版 {@code FBORenderCell.renderOneLevel} 每畫一個 chunk 的一層，先畫自己的血跡，再對 8 個鄰居各呼叫一次
 * {@code renderOneLevel_Blood(鄰居, 層, 本 chunk 外擴 1 格)}：鄰居的 {@code floorBloodSplatsFade} 與
 * {@code floorBloodSplats} 整份逐筆掃過，只留下落在範圍內的。屍潮後每個 chunk 上千筆，一份清單一次重畫被自己與
 * 8 個鄰居各掃一次，大部分是掃完就丟。
 *
 * <p>做法：
 * <ul>
 * <li>IsoChunk 兩個建構子把這兩份清單換成 {@link SplatQueue}／{@link FadeList}（FieldPutWrap）。它們就是原版的
 * {@code BoundedQueue}／{@code ArrayList}，只多記結構變動，並保存一份「靠哪一邊」的索引（{@link Strips}）。</li>
 * <li>6 參數 {@code renderOneLevel_Blood} 的方法頭呼叫 {@link #enter}（chunk 與範圍）。範圍落在這個 chunk 的某一邊或
 * 某一角（8 個鄰居呼叫都是）時，方法內掃描這兩份清單的 {@code size()}／{@code get(i)}（改道到本類別）只走那一邊的
 * 候選，索引由小到大，順序與原版相同。其他呼叫（畫自己那次）照原版。</li>
 * <li>候選一定包含原版範圍判斷會留下的每一筆（證明見 {@link Strips}），原版的判斷照樣逐筆再做一次，所以留下的血跡、
 * 順序、{@code b.chunk} 的設定、之後的顏色、淡出與 {@code floorBloodSplatsFade.remove} 都與原版相同。</li>
 * </ul>
 *
 * <p>每 {@value #SAMPLE_EVERY} 次鄰居呼叫抽 1 次照原版範圍判斷掃整份清單，候選少了任何一筆就本次啟動永久回原版。
 * {@code -Dmdc.bloodSplatIndex=on|verify|off}：verify 每次都比對；off 不換清單、全部照原版。只在第一個呼叫的執行緒
 * （主執行緒）運作。
 */
public final class BloodSplatIndex {

    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_VERIFY = 2;
    static final String RAW_MODE = System.getProperty("mdc.bloodSplatIndex");
    static final int MODE = parseMode(RAW_MODE);
    static final int SAMPLE_EVERY = 256;
    /** chunk 原點與範圍的絕對值都小於它時，(float) 座標加 0.5 的倍數仍是精確值（{@link Strips} 的證明需要）。 */
    static final int COORD_LIMIT = 1 << 22;
    private static final long BEAT_INTERVAL_MS = 300_000L;

    /** 這次 6 參數呼叫的 chunk 與範圍；{@link #enter} 設定，第一次 size() 時才建或查索引。 */
    private static IsoChunk ctxChunk;
    private static int ctxDir = -1;
    private static int ctxMinX;
    private static int ctxMinY;
    private static int ctxMaxX;
    private static int ctxMaxY;
    private static boolean ctxPending;
    /** 這次呼叫改走候選的清單（null＝照原版）。 */
    private static FadeList ctxFade;
    private static int[] ctxFadeIdx;
    private static int ctxFadeN;
    private static SplatQueue ctxQueue;
    private static int[] ctxQueueIdx;
    private static int ctxQueueN;

    private static Thread owner;
    private static boolean disabled;
    private static boolean announced;
    private static long nextBeatMs;
    private static int sampleCountdown = SAMPLE_EVERY;

    static long neighborCalls;
    static long indexedLists;
    static long visited;
    static long scanned;
    static long rebuilds;
    static long sampled;
    static long mismatches;
    static long fallbackPlain;
    static long fallbackUntrusted;
    static long foreignThread;
    static long anomalies;

    private BloodSplatIndex() {}

    static int parseMode(String raw) {
        if (raw == null) {
            return MODE_ON;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off", "0" -> MODE_OFF;
            case "verify", "2" -> MODE_VERIFY;
            default -> MODE_ON;
        };
    }

    /** IsoChunk 建構子 {@code PUTFIELD floorBloodSplats} 之前（原版 {@code new BoundedQueue<>(1000)}）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BoundedQueue wrapQueue(BoundedQueue queue) {
        if (MODE == MODE_OFF || queue == null || queue.getClass() != BoundedQueue.class || !queue.isEmpty()) {
            return queue;
        }
        return new SplatQueue(queue.capacity());
    }

    /** IsoChunk 建構子 {@code PUTFIELD floorBloodSplatsFade} 之前（原版 {@code new ArrayList<>()}）。 */
    @SuppressWarnings("rawtypes")
    public static ArrayList wrapFade(ArrayList list) {
        if (MODE == MODE_OFF || list == null || list.getClass() != ArrayList.class || !list.isEmpty()) {
            return list;
        }
        return new FadeList();
    }

    /** 6 參數 renderOneLevel_Blood 方法頭：記下這次的 chunk 與範圍（slot 1、3–6）。 */
    public static void enter(IsoChunk chunk, int minX, int minY, int maxX, int maxY) {
        if (!announced) {
            announce();
        }
        if (MODE == MODE_OFF || disabled) {
            return;
        }
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            // 不碰 context：owner 執行緒可能正走到一半，這條執行緒的 size()／get() 也照原版（見 isOwner）。
            foreignThread++;
            return;
        }
        ctxFade = null;
        ctxQueue = null;
        ctxPending = false;
        ctxChunk = null;
        int dir = chunk == null ? -1 : direction(chunk.wx * 8, chunk.wy * 8, minX, minY, maxX, maxY);
        if (dir < 0) {
            return;
        }
        neighborCalls++;
        ctxChunk = chunk;
        ctxDir = dir;
        ctxMinX = minX;
        ctxMinY = minY;
        ctxMaxX = maxX;
        ctxMaxY = maxY;
        ctxPending = true;
    }

    /** 6 參數方法內每個 {@code ArrayList.size()}：fade 清單換成候選數，其餘照原版。 */
    public static int listSize(ArrayList<?> list) {
        if (!isOwner()) {
            return list.size();
        }
        if (ctxPending) {
            resolve();
        }
        FadeList fade = ctxFade;
        return fade != null && list == fade ? ctxFadeN : list.size();
    }

    /** 6 參數方法內每個 {@code ArrayList.get(i)}：fade 清單取第 i 個候選，其餘照原版。 */
    public static Object listGet(ArrayList<?> list, int i) {
        FadeList fade = ctxFade;
        return fade != null && list == fade && isOwner() ? list.get(ctxFadeIdx[i]) : list.get(i);
    }

    /** 6 參數方法內的 {@code BoundedQueue.size()}。 */
    public static int queueSize(BoundedQueue<?> queue) {
        if (!isOwner()) {
            return queue.size();
        }
        if (ctxPending) {
            resolve();
        }
        SplatQueue q = ctxQueue;
        return q != null && queue == q ? ctxQueueN : queue.size();
    }

    /** 6 參數方法內的 {@code BoundedQueue.get(i)}。 */
    public static Object queueGet(BoundedQueue<?> queue, int i) {
        SplatQueue q = ctxQueue;
        return q != null && queue == q && isOwner() ? queue.get(ctxQueueIdx[i]) : queue.get(i);
    }

    /** context 只屬於第一個呼叫 enter 的執行緒；其他執行緒一律照原版（owner 還沒設定時也是）。 */
    private static boolean isOwner() {
        return Thread.currentThread() == owner;
    }

    /**
     * 範圍落在 chunk 的哪一邊（{@link Strips} 的方向）；不是 8 個鄰居方向之一、或座標大到 float 不精確時回 -1。
     * 原版鄰居呼叫的範圍＝本 chunk 外擴 1 格：在鄰居座標裡是 [-8·dwx-1, -8·dwx+9]。
     */
    static int direction(int cx, int cy, int minX, int minY, int maxX, int maxY) {
        if (!within(cx) || !within(cy) || !within(minX) || !within(minY) || !within(maxX) || !within(maxY)) {
            return -1;
        }
        boolean e = minX - cx >= 7;
        boolean w = maxX - cx <= 1;
        boolean s = minY - cy >= 7;
        boolean n = maxY - cy <= 1;
        if (e && w || s && n) {
            return -1;
        }
        if (e) {
            return s ? Strips.ES : n ? Strips.EN : Strips.E;
        }
        if (w) {
            return s ? Strips.WS : n ? Strips.WN : Strips.W;
        }
        return s ? Strips.S : n ? Strips.N : -1;
    }

    private static boolean within(int v) {
        return v > -COORD_LIMIT && v < COORD_LIMIT;
    }

    /** 第一次 size()：取兩份清單這個方向的候選（索引過期就重建），抽樣比對。 */
    private static void resolve() {
        ctxPending = false;
        IsoChunk chunk = ctxChunk;
        try {
            if (chunk.floorBloodSplatsFade instanceof FadeList fade && chunk.floorBloodSplats instanceof SplatQueue queue) {
                boolean check = MODE == MODE_VERIFY || --sampleCountdown <= 0;
                if (check) {
                    sampleCountdown = SAMPLE_EVERY;
                }
                if (fade.untrusted) {
                    fallbackUntrusted++;
                } else if (pick(fade, check, chunk)) {
                    ctxFade = fade;
                    ctxFadeIdx = fade.strips.of(ctxDir);
                    ctxFadeN = fade.strips.count[ctxDir];
                }
                if (!disabled && pick(queue, check, chunk)) {
                    ctxQueue = queue;
                    ctxQueueIdx = queue.strips.of(ctxDir);
                    ctxQueueN = queue.strips.count[ctxDir];
                }
                if (disabled) {
                    ctxFade = null;
                    ctxQueue = null;
                }
            } else {
                fallbackPlain++;
            }
        } catch (RuntimeException e) {
            // 清單裡有讀不到的東西（原版掃描也會在同一筆拋出）：這次照原版跑，讓原版自己拋。
            anomalies++;
            ctxFade = null;
            ctxQueue = null;
        }
        if ((neighborCalls & 4095) == 0) {
            beat();
        }
    }

    /** 確保索引是最新的；需要抽樣時比對。回傳 false＝這份清單這次照原版。 */
    private static boolean pick(Splats list, boolean check, IsoChunk chunk) {
        Strips strips = list.strips();
        if (strips.stale(list)) {
            rebuilds++;
            strips.rebuild(list);
        }
        int n = strips.count[ctxDir];
        if (check) {
            sampled++;
            int cx = chunk.wx * 8;
            int cy = chunk.wy * 8;
            if (!covers(list, strips.of(ctxDir), n, cx, cy, ctxMinX, ctxMinY, ctxMaxX, ctxMaxY)) {
                mismatches++;
                disabled = true;
                log("candidate mismatch chunk=" + chunk.wx + "," + chunk.wy + " dir=" + ctxDir + " size=" + list.count()
                        + " candidates=" + n + "; vanilla scan for the rest of this session " + counters());
                return false;
            }
        }
        indexedLists++;
        visited += n;
        scanned += list.count();
        return true;
    }

    /** 原版範圍判斷（同一個 float 算式）留下的每一筆都在候選裡，候選由小到大且都在清單內。 */
    static boolean covers(Splats list, int[] idx, int n, int cx, int cy, int minX, int minY, int maxX, int maxY) {
        int size = list.count();
        int prev = -1;
        for (int k = 0; k < n; k++) {
            if (idx[k] <= prev || idx[k] >= size) {
                return false;
            }
            prev = idx[k];
        }
        int j = 0;
        for (int i = 0; i < size; i++) {
            IsoFloorBloodSplat b = list.at(i);
            if (!(cx + b.x < minX) && !(cx + b.x > maxX) && !(cy + b.y < minY) && !(cy + b.y > maxY)) {
                while (j < n && idx[j] < i) {
                    j++;
                }
                if (j >= n || idx[j] != i) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void announce() {
        announced = true;
        nextBeatMs = System.currentTimeMillis() + BEAT_INTERVAL_MS;
        log("active mode=" + (MODE == MODE_VERIFY ? "verify" : MODE == MODE_OFF ? "off" : "on")
                + (RAW_MODE == null ? "" : " (-Dmdc.bloodSplatIndex=" + RAW_MODE + ")") + " sampleEvery=" + SAMPLE_EVERY);
    }

    private static void beat() {
        long now = System.currentTimeMillis();
        if (now < nextBeatMs) {
            return;
        }
        nextBeatMs = now + BEAT_INTERVAL_MS;
        log(counters());
    }

    static String counters() {
        return "neighborCalls=" + neighborCalls + " indexedLists=" + indexedLists + " visited=" + visited
                + " scanned=" + scanned + " rebuilds=" + rebuilds + " sampled=" + sampled + " mismatches=" + mismatches
                + " fallback[plain=" + fallbackPlain + " untrusted=" + fallbackUntrusted + "] foreignThread=" + foreignThread
                + " anomalies=" + anomalies + " disabled=" + disabled;
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][BloodSplatIndex] " + message);
        } catch (RuntimeException | LinkageError ignored) {
            // 診斷行寫不出來也不能讓例外回到畫面路徑。
        }
    }

    static boolean isDisabled() {
        return disabled;
    }

    /** 行為測試用：清掉跨情境殘留的狀態與計數。 */
    static void resetForTest() {
        ctxFade = null;
        ctxQueue = null;
        ctxPending = false;
        ctxChunk = null;
        owner = null;
        disabled = false;
        announced = false;
        sampleCountdown = SAMPLE_EVERY;
        neighborCalls = indexedLists = visited = scanned = rebuilds = sampled = mismatches = 0;
        fallbackPlain = fallbackUntrusted = foreignThread = anomalies = 0;
    }

    /** 行為測試用：下一次鄰居呼叫是否抽樣。 */
    static void sampleNextForTest(boolean sample) {
        sampleCountdown = sample ? 1 : Integer.MAX_VALUE;
    }

    /** {@link SplatQueue} 與 {@link FadeList} 共用的讀取介面。 */
    interface Splats {
        int count();

        IsoFloorBloodSplat at(int i);

        Strips strips();

        /** 結構變動計數；與上次建索引時不同＝索引過期。 */
        long version();
    }

    /**
     * 一份清單「靠哪一邊」的候選索引：8 個方向各一個由小到大的 int 陣列。
     *
     * <p>一筆血跡在 chunk 內的座標是 (x, y)。E＝{@code !(x < 6.5f)}、W＝{@code !(x > 1.5f)}、S＝{@code !(y < 6.5f)}、
     * N＝{@code !(y > 1.5f)}（NaN 四邊都算），角落＝兩邊都成立。
     *
     * <p>為什麼是超集合：原版判斷 {@code !(cx + b.x < minX)}（{@code i2f(cx) + b.x} 以 float 相加，與
     * {@code i2f(minX)} 比）。E 方向的範圍滿足 {@code minX - cx >= 7}。若 {@code b.x < 6.5}（不是 NaN），實數上
     * {@code cx + b.x < cx + 6.5}；{@code |cx| < 2^22} 時 {@code cx + 6.5} 是精確的 float，最近捨入單調，所以算出的
     * float 和 {@code <= cx + 6.5 < cx + 7 <= minX}，原版一定丟掉它。W、S、N 同理（{@code maxX - cx <= 1} 時
     * {@code b.x > 1.5} 的和 {@code >= cx + 1.5 > maxX}）。
     */
    public static final class Strips {
        static final int E = 0;
        static final int W = 1;
        static final int S = 2;
        static final int N = 3;
        static final int ES = 4;
        static final int EN = 5;
        static final int WS = 6;
        static final int WN = 7;
        private static final int[] NONE = new int[0];

        final int[][] idx = new int[8][];
        final int[] count = new int[8];
        private boolean built;
        private long builtVersion;

        boolean stale(Splats list) {
            return !built || builtVersion != list.version();
        }

        void rebuild(Splats list) {
            Arrays.fill(count, 0);
            int size = list.count();
            for (int i = 0; i < size; i++) {
                IsoFloorBloodSplat b = list.at(i);
                boolean e = !(b.x < 6.5F);
                boolean w = !(b.x > 1.5F);
                boolean s = !(b.y < 6.5F);
                boolean n = !(b.y > 1.5F);
                if (e) {
                    push(E, i);
                    if (s) {
                        push(ES, i);
                    }
                    if (n) {
                        push(EN, i);
                    }
                }
                if (w) {
                    push(W, i);
                    if (s) {
                        push(WS, i);
                    }
                    if (n) {
                        push(WN, i);
                    }
                }
                if (s) {
                    push(S, i);
                }
                if (n) {
                    push(N, i);
                }
            }
            builtVersion = list.version();
            built = true;
        }

        private void push(int dir, int i) {
            int[] a = idx[dir];
            int c = count[dir];
            if (a == null || c == a.length) {
                a = idx[dir] = a == null ? new int[16] : Arrays.copyOf(a, c * 2);
            }
            a[c] = i;
            count[dir] = c + 1;
        }

        int[] of(int dir) {
            int[] a = idx[dir];
            return a == null ? NONE : a;
        }
    }

    /** {@code IsoChunk.floorBloodSplats} 本體：原版 BoundedQueue，四個會改內容的方法多記一次變動。 */
    public static final class SplatQueue extends BoundedQueue<IsoFloorBloodSplat> implements Splats {
        final Strips strips = new Strips();
        long mods;

        SplatQueue(int numElements) {
            super(numElements);
        }

        @Override
        public void add(IsoFloorBloodSplat e) {
            mods++;
            super.add(e);
        }

        @Override
        public IsoFloorBloodSplat removeFirst() {
            mods++;
            return super.removeFirst();
        }

        @Override
        public IsoFloorBloodSplat remove(int index) {
            mods++;
            return super.remove(index);
        }

        @Override
        public void clear() {
            mods++;
            super.clear();
        }

        @Override
        public int count() {
            return size();
        }

        @Override
        public IsoFloorBloodSplat at(int i) {
            return get(i);
        }

        @Override
        public Strips strips() {
            return strips;
        }

        @Override
        public long version() {
            return mods;
        }
    }

    /**
     * {@code IsoChunk.floorBloodSplatsFade} 本體：原版 ArrayList，多記 {@code set} 次數，其餘結構變動看 ArrayList 自己的
     * {@code modCount}。{@code replaceAll}、{@code sort}、{@code removeAll}、{@code retainAll} 在回呼途中直接改內部陣列、
     * 最後才遞增 modCount（回呼拋出時不遞增），視圖（{@code subList}／{@code reversed}）的寫入繞過計數：呼叫前就標成
     * 不可信，這份清單從此照原版掃（原版從不呼叫它們）。
     */
    public static final class FadeList extends ArrayList<IsoFloorBloodSplat> implements Splats {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        final transient Strips strips = new Strips();
        int sets;
        boolean untrusted;

        @Override
        public IsoFloorBloodSplat set(int index, IsoFloorBloodSplat element) {
            sets++;
            return super.set(index, element);
        }

        @Override
        public List<IsoFloorBloodSplat> subList(int fromIndex, int toIndex) {
            untrusted = true;
            return super.subList(fromIndex, toIndex);
        }

        @Override
        public List<IsoFloorBloodSplat> reversed() {
            untrusted = true;
            return super.reversed();
        }

        @Override
        public void replaceAll(UnaryOperator<IsoFloorBloodSplat> operator) {
            untrusted = true;
            super.replaceAll(operator);
        }

        @Override
        public void sort(Comparator<? super IsoFloorBloodSplat> comparator) {
            untrusted = true;
            super.sort(comparator);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            untrusted = true;
            return super.removeAll(c);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            untrusted = true;
            return super.retainAll(c);
        }

        @Override
        public int count() {
            return size();
        }

        @Override
        public IsoFloorBloodSplat at(int i) {
            return get(i);
        }

        @Override
        public Strips strips() {
            return strips;
        }

        @Override
        public long version() {
            return ((long) modCount << 32) | (sets & 0xFFFFFFFFL);
        }
    }
}
