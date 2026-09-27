package zombie.mdc;

import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;

import zombie.GameTime;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.AnimalSpottedPrefilter;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.IsoUtils;
import zombie.vehicles.BaseVehicle;

/**
 * W47 動物視線空間預篩（2026-09-28；docs/patches.md 2bj）。
 *
 * <p>W18-2 之後，每次視線檢查仍依序走完整份 {@code objectList}（正式服約 4,300 個物件），只為找出
 * 門檻距離（約 12 格）內的殭屍與玩家。本類在每幀第一次視線檢查時建一份快照：目標（殭屍、非動物玩家）
 * 依 {@code objectList} 迭代順序排好，殭屍再依位置分入 16 格網格。之後每隻動物只處理
 * 「網格範圍（門檻＋{@link #MARGIN}）內的殭屍＋全部玩家」，依原順序逐一走 W18-2 同一套判斷。
 *
 * <p><b>等價依據</b>（對照現行 W18-2 迴圈）：
 * <ol>
 * <li>遠距目標唯一的效果是 spotted 前綴：{@code spottedChr=null}、{@code lastAlerted} 衰減。
 * 本類只在呼叫開始時 {@code lastAlerted==0} 才走快速路徑，衰減恆為無效果；每個有效目標
 * （遠距前綴或 {@code spotted()}，後者開頭同樣先重放前綴）都會先把 {@code spottedChr} 清掉，
 * 故候選之前的遠距目標可省略；最後只需確認「最後一個有效候選之後是否還有有效目標」，
 * 有就補一次前綴。</li>
 * <li>候選的 {@code spotted()} 若讓 {@code lastAlerted≠0} 或改了門檻，從該目標之後改走完整順序
 * （仍只看目標，非目標在原迴圈本來就零效果）。</li>
 * <li>快照綁定 {@code objectList} 的（加入次數, 大小）：IsoCell 建構子把 {@code objectList} 換成 {@link ObjectSet}
 * （HashSet 子類，迭代順序與原版相同，只記成功加入的次數）。原版有十多處經 {@code getObjectList()} 直接增刪，
 * 不能假設更新期間成員不變；成員或迭代順序的任何改變都會改到這兩者之一而重建。清單不是 {@link ObjectSet} 就不走快速路徑。</li>
 * <li>{@code spottedList} 只放自己（原版 {@code spotted()} 從不讀寫它，SmokeCheck 釘住）。快照記下自己在迭代
 * 順序中的位置，處理到該位置才加入，中途例外時的殘留狀態與完整掃描相同。</li>
 * </ol>
 * 唯一假設：快照後到檢查當下，殭屍移動不超過 {@link #MARGIN} 格（玩家不受此限，一律逐一檢查）。
 * 以兩道檢查監看：快速路徑最後掃描遇到落在門檻內的非候選即判違反；on 模式每 64 次改跑
 * 完整路徑並比對候選是否涵蓋所有門檻內目標（observe 每次都比對）。任一違反即本次啟動永久停用
 * 快速路徑、全部回到 W18-2 完整掃描，並記錄一行。
 *
 * <p>{@code -Dmdc.animalLosIndex}：{@code 1|on}（預設）、{@code 2|observe}（只比對、不改行為）、
 * {@code 0|off}；需重啟。只在 {@code AnimalLosScan} 為 on 時經其呼叫。主執行緒單寫。
 */
public final class AnimalLosIndex {
    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.animalLosIndex"));

    /** 殭屍在快照後到檢查當下可移動的上限（格）。 */
    static final float MARGIN = 16.0F;
    private static final float CELL = 16.0F;
    private static final int AUDIT_MASK = 63;
    private static final float SAFE_DOMAIN_MAX = 65536.0F;
    /** 候選範圍超過這麼多格網格就回完整掃描（大門檻時逐格查表比整份清單更慢）。 */
    private static final long MAX_CELLS = 1024L;
    private static final float GUARD_MARGIN = 0.25F;
    private static final int COORD_BITS = 21;
    private static final long IDX_MASK = (1L << COORD_BITS) - 1L;
    private static final int COORD_MAX = (1 << COORD_BITS) - 1;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalLosIndex] ";
    private static final long BEAT_NS = 300_000_000_000L;

    static final int NONE = 0;
    static final int FAR = 1;
    static final int DELEGATED = 2;

    // ---- 快照 ----
    private static long snapFrame = Long.MIN_VALUE;
    private static ObjectSet snapList;
    private static int snapAdds;
    private static int snapSize = -1;
    private static boolean snapValid;
    /** 動物 → 它在迭代順序中前面有幾個目標。 */
    private static final IdentityHashMap<IsoMovingObject, Integer> selfPos = new IdentityHashMap<>();
    private static IsoMovingObject[] targets = new IsoMovingObject[256];
    private static boolean[] player = new boolean[256];
    private static float[] snapX = new float[256];
    private static float[] snapY = new float[256];
    private static int targetCount;
    private static int[] always = new int[64];
    private static int alwaysCount;
    private static long[] keys = new long[256];
    private static int keyCount;
    private static int[] mark = new int[256];
    private static int stamp;
    /** 整份清單最後一個元素若是目標，其索引；否則 -1。 */
    private static int lastElement = -1;
    private static int[] cand = new int[256];

    // ---- 計數 ----
    private static long calls;
    private static long fast;
    private static long exactAlerted;
    private static long exactDomain;
    private static long exactSnapshot;
    private static long audits;
    private static long auditMisses;
    private static long tailSwitches;
    private static long lateFixes;
    private static long candidateSum;
    private static long targetSum;
    private static long rebuilds;
    private static long rebuildNs;
    private static long anomalies;
    private static long modifiedExits;
    private static long nested;
    private static boolean busy;
    private static long auditTick;
    private static boolean disabled;
    private static String disabledReason = "";
    private static long lastBeatNs;
    private static boolean announced;

    private AnimalLosIndex() {
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

    /**
     * {@code AnimalLosScan} on 路徑在前置取值成功後、清 {@code spottedList} 前呼叫。
     * 回傳 true＝本次視線已處理完；false＝照原完整掃描（本方法未改任何遊戲狀態）。
     * 委派的 {@code spotted()} 可能經回呼同步觸發另一次視線檢查；巢狀呼叫一律走完整掃描，不碰外層正在使用的快照與候選暫存。
     */
    static boolean tryHandle(IsoAnimal a, BaseAnimalBehavior b, Set<IsoMovingObject> list,
            Stack<IsoMovingObject> spotted) {
        if (MODE == MODE_OFF || disabled) {
            return false;
        }
        if (busy) {
            nested++;
            return false;
        }
        busy = true;
        try {
            return handle(a, b, list, spotted);
        } finally {
            busy = false;
        }
    }

    private static boolean handle(IsoAnimal a, BaseAnimalBehavior b, Set<IsoMovingObject> list,
            Stack<IsoMovingObject> spotted) {
        calls++;
        maybeBeat();
        if (b.lastAlerted != 0.0F) {
            exactAlerted++;
            return false;
        }
        float t0;
        float ax;
        float ay;
        int n;
        int selfAt;
        try {
            t0 = AnimalSpottedPrefilter.thresholdOf(a.adef.spottingDist);
            float g = t0 + GUARD_MARGIN;
            ax = a.getX();
            ay = a.getY();
            if (!(t0 <= SAFE_DOMAIN_MAX && g > t0 && g * g > 0.0F) || !Float.isFinite(ax) || !Float.isFinite(ay)
                    || cellSpan(ax, g + MARGIN) * cellSpan(ay, g + MARGIN) > MAX_CELLS) {
                exactDomain++;
                return false;
            }
            if (!ensureSnapshot(list)) {
                exactSnapshot++;
                return false;
            }
            n = collect(ax, ay, g + MARGIN);
            candidateSum += n;
            targetSum += targetCount;
            if (MODE == MODE_OBSERVE || (++auditTick & AUDIT_MASK) == 0L) {
                audit(a, ax, ay, g * g);
                return false;
            }
            Integer sp = selfPos.get(a);
            selfAt = sp == null ? NO_SELF : sp;
        } catch (RuntimeException e) {
            // 快照／候選簿記失敗：尚未改任何遊戲狀態，回完整掃描。
            anomalies++;
            return false;
        }

        // ---- 以下為遊戲邏輯；例外照原完整掃描一樣上拋（無重跑）。----
        spotted.clear();
        int lastEligible = -1;
        for (int i = 0; i < n; i++) {
            int k = cand[i];
            selfAt = self(a, spotted, k, selfAt);
            int r = stepAt(a, b, k, ax, ay);
            if (r == NONE) {
                continue;
            }
            lastEligible = k;
            if (r == DELEGATED && (b.lastAlerted != 0.0F || thresholdChanged(a, t0))) {
                tailSwitches++;
                for (int j = k + 1; j < targetCount; j++) {
                    selfAt = self(a, spotted, j, selfAt);
                    stepAt(a, b, j, ax, ay);
                }
                self(a, spotted, NO_SELF - 1, selfAt);
                fast++;
                return true;
            }
        }
        // 最後一個有效候選之後，只要還有任何有效目標，原版會再清一次 spottedChr。
        for (int j = lastEligible + 1; j < targetCount; j++) {
            if (mark[j] == stamp) {
                continue;
            }
            selfAt = self(a, spotted, j, selfAt);
            int r = stepAt(a, b, j, ax, ay);
            if (r == FAR) {
                break;
            }
            if (r == DELEGATED) {
                lateFixes++;
                disable("非候選殭屍落在門檻內", a, j, ax, ay);
                for (int q = j + 1; q < targetCount; q++) {
                    selfAt = self(a, spotted, q, selfAt);
                    stepAt(a, b, q, ax, ay);
                }
                break;
            }
        }
        // 其餘目標只剩無效果或遠距前綴，自己排在哪裡都不影響結果。
        self(a, spotted, NO_SELF - 1, selfAt);
        fast++;
        return true;
    }

    private static final int NO_SELF = Integer.MAX_VALUE;

    /**
     * 處理第 k 個目標；委派 {@code spotted()}（可經 XP／Lua 回呼改動世界）後若 {@code objectList} 被增刪，比照完整掃描的
     * HashSet iterator：目前元素不是整份清單的最後一個就在取下一個元素時拋 {@link ConcurrentModificationException}；
     * 是最後一個則迴圈自然結束（其後沒有任何目標）。
     */
    private static int stepAt(IsoAnimal a, BaseAnimalBehavior b, int k, float ax, float ay) {
        int r = step(a, b, targets[k], player[k], ax, ay);
        if (r == DELEGATED && (snapList.addCount != snapAdds || snapList.size() != snapSize) && k != lastElement) {
            modifiedExits++;
            throw new ConcurrentModificationException();
        }
        return r;
    }

    /** 處理第 k 個目標前：自己排在它前面就先加入（完整掃描遇到自己時的同一動作）。 */
    private static int self(IsoAnimal a, Stack<IsoMovingObject> spotted, int k, int selfAt) {
        if (k >= selfAt) {
            spotted.add(a);
            return NO_SELF;
        }
        return selfAt;
    }

    private static long cellSpan(float c, float reach) {
        return (long) Math.floor((c + reach) / CELL) - (long) Math.floor((c - reach) / CELL) + 1L;
    }

    /** spotted() 內可能改了 spottingDist；讀取異常也當成已變（改走完整順序，與 W18-2 逐 pair 讀取同方向）。 */
    private static boolean thresholdChanged(IsoAnimal a, float t0) {
        try {
            return AnimalSpottedPrefilter.thresholdOf(a.adef.spottingDist) != t0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 與 {@code AnimalLosScan} 迴圈對單一目標的處理逐句相同（目標必為殭屍或非動物玩家）。 */
    static int step(IsoAnimal a, BaseAnimalBehavior b, IsoMovingObject o, boolean isP, float ax, float ay) {
        if (!isP && ((IsoZombie) o).isReanimatedForGrappleOnly()) {
            return NONE;
        }
        float ox = o.getX();
        float oy = o.getY();
        float oz = o.getZ();
        if (PZMath.abs(oz - a.getZ()) > 1.0F) {
            return NONE;
        }
        float dx = ox - ax;
        float dy = oy - ay;
        float d2 = dx * dx + dy * dy;
        if (o.getCurrentSquare() == null) {
            return NONE;
        }
        float pairGate2 = Float.NaN;
        boolean fastForPair = false;
        try {
            float t = AnimalSpottedPrefilter.thresholdOf(a.adef.spottingDist);
            float g = t + GUARD_MARGIN;
            pairGate2 = g * g;
            fastForPair = t <= SAFE_DOMAIN_MAX && g > t && pairGate2 > 0.0F;
        } catch (RuntimeException ignored) {
            // 同 W18-2：門檻讀取異常＝本 pair 全額委派。
        }
        if (fastForPair && d2 > pairGate2) {
            if (isP && (((IsoGameCharacter) o).isInvisible() || ((IsoPlayer) o).isGhostMode())) {
                return NONE;
            }
            a.spottedChr = null;
            if (b.lastAlerted > 0.0F) {
                b.lastAlerted = b.lastAlerted - GameTime.getInstance().getMultiplier();
            }
            if (b.lastAlerted < 0.0F) {
                b.lastAlerted = 0.0F;
            }
            return FAR;
        }
        float dist = IsoUtils.DistanceTo(ox, oy, ax, ay);
        if (!isP) {
            AnimalSpottedPrefilter.spotted(b, o, false, dist);
            return DELEGATED;
        }
        if (!((IsoGameCharacter) o).isInvisible() && !((IsoPlayer) o).isGhostMode()) {
            AnimalSpottedPrefilter.spotted(b, o, false, dist);
            return DELEGATED;
        }
        return NONE;
    }

    private static boolean ensureSnapshot(Set<IsoMovingObject> list) {
        if (!(list instanceof ObjectSet os)) {
            return false;
        }
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        int size = os.size();
        if (os == snapList && frame == snapFrame && os.addCount == snapAdds && size == snapSize) {
            return snapValid;
        }
        long t0 = System.nanoTime();
        snapList = os;
        snapFrame = frame;
        snapAdds = os.addCount;
        snapSize = size;
        snapValid = false;
        targetCount = 0;
        alwaysCount = 0;
        keyCount = 0;
        selfPos.clear();
        lastElement = -1;
        for (IsoMovingObject o : os) {
            lastElement = -1;
            if (o == null) {
                return false; // 讓完整掃描照原樣在同一位置拋出
            }
            if (o instanceof IsoPhysicsObject || o instanceof BaseVehicle) {
                continue;
            }
            if (o instanceof IsoAnimal) {
                selfPos.put(o, targetCount);
                continue;
            }
            boolean z = o instanceof IsoZombie;
            boolean p = !z && o instanceof IsoPlayer;
            if (!z && !p) {
                continue;
            }
            int idx = targetCount++;
            lastElement = idx;
            grow(idx + 1);
            targets[idx] = o;
            player[idx] = p;
            float x = p ? Float.NaN : o.getX();
            float y = p ? Float.NaN : o.getY();
            snapX[idx] = x;
            snapY[idx] = y;
            if (p || !(x >= 0.0F) || !(y >= 0.0F) || x / CELL >= COORD_MAX || y / CELL >= COORD_MAX) {
                if (alwaysCount == always.length) {
                    always = Arrays.copyOf(always, alwaysCount * 2);
                }
                always[alwaysCount++] = idx;
            } else {
                keys[keyCount++] = ((long) (int) (x / CELL) << (2 * COORD_BITS))
                        | ((long) (int) (y / CELL) << COORD_BITS) | idx;
            }
        }
        if (targetCount >= (1 << COORD_BITS)) {
            return false;
        }
        Arrays.sort(keys, 0, keyCount);
        // 清掉上一份快照留下的參照，避免持有已移出清單的物件。
        Arrays.fill(targets, targetCount, targets.length, null);
        rebuilds++;
        rebuildNs += System.nanoTime() - t0;
        snapValid = true;
        return true;
    }

    /**
     * IsoCell 建構子寫入 {@code objectList} 前的包裝（Patcher FieldPutWrap）。off 或非原版空 HashSet 時原樣回傳。
     */
    public static Set<IsoMovingObject> wrapObjectList(Set<IsoMovingObject> vanilla) {
        if (MODE == MODE_OFF || vanilla == null || vanilla.getClass() != HashSet.class || !vanilla.isEmpty()) {
            return vanilla;
        }
        return new ObjectSet();
    }

    /**
     * 帶加入計數的 {@code objectList}：與原版同一個 HashSet 實作（預設容量、迭代順序相同），只多記成功加入的次數。
     * 快照以（加入次數, 大小）判斷是否仍有效：只有加入會增加大小或觸發擴容（改變迭代順序），任何移除都讓大小變小；
     * 同大小換成員必然經過加入。移除不改其餘元素的相對順序，故不必攔截 remove／iterator，迭代成本與原版相同。
     */
    static final class ObjectSet extends HashSet<IsoMovingObject> {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        int addCount;

        @Override
        public boolean add(IsoMovingObject o) {
            if (super.add(o)) {
                addCount++;
                return true;
            }
            return false;
        }

        @Override
        public boolean addAll(Collection<? extends IsoMovingObject> c) {
            boolean changed = super.addAll(c);
            if (changed) {
                addCount++; // 保守：即使 JDK 日後改寫 addAll 不經 add 也會失效重建
            }
            return changed;
        }
    }

    private static void grow(int need) {
        if (need <= targets.length) {
            return;
        }
        int cap = Math.max(need, targets.length * 2);
        targets = Arrays.copyOf(targets, cap);
        player = Arrays.copyOf(player, cap);
        snapX = Arrays.copyOf(snapX, cap);
        snapY = Arrays.copyOf(snapY, cap);
        keys = Arrays.copyOf(keys, cap);
        mark = new int[cap];
        stamp = 0;
        cand = new int[cap];
    }

    /** 填入候選（依迭代順序排序）並以 stamp 標記；回傳候選數。 */
    private static int collect(float ax, float ay, float reach) {
        if (++stamp == 0) {
            Arrays.fill(mark, 0);
            stamp = 1;
        }
        int n = 0;
        int cx0 = Math.max(0, (int) Math.floor((ax - reach) / CELL));
        int cx1 = Math.min(COORD_MAX, (int) Math.floor((ax + reach) / CELL));
        int cy0 = Math.max(0, (int) Math.floor((ay - reach) / CELL));
        int cy1 = Math.min(COORD_MAX, (int) Math.floor((ay + reach) / CELL));
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                long lo = ((long) cx << (2 * COORD_BITS)) | ((long) cy << COORD_BITS);
                long hi = lo | IDX_MASK;
                int i = lowerBound(lo);
                while (i < keyCount && keys[i] <= hi) {
                    int idx = (int) (keys[i] & IDX_MASK);
                    cand[n++] = idx;
                    mark[idx] = stamp;
                    i++;
                }
            }
        }
        for (int i = 0; i < alwaysCount; i++) {
            int idx = always[i];
            cand[n++] = idx;
            mark[idx] = stamp;
        }
        Arrays.sort(cand, 0, n);
        return n;
    }

    private static int lowerBound(long key) {
        int lo = 0;
        int hi = keyCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 唯讀：確認門檻內的目標全在候選中。不改任何遊戲狀態。 */
    private static void audit(IsoAnimal a, float ax, float ay, float gate2) {
        audits++;
        for (int j = 0; j < targetCount; j++) {
            if (mark[j] == stamp) {
                continue;
            }
            IsoMovingObject o = targets[j];
            if (((IsoZombie) o).isReanimatedForGrappleOnly()
                    || PZMath.abs(o.getZ() - a.getZ()) > 1.0F || o.getCurrentSquare() == null) {
                continue;
            }
            float dx = o.getX() - ax;
            float dy = o.getY() - ay;
            if (dx * dx + dy * dy <= gate2) {
                auditMisses++;
                disable("比對發現門檻內殭屍不在候選中", a, j, ax, ay);
                return;
            }
        }
    }

    private static void disable(String reason, IsoAnimal a, int idx, float ax, float ay) {
        disabled = true;
        IsoMovingObject o = targets[idx];
        disabledReason = reason;
        try {
            DebugLog.log(TAG + "停用快速路徑（本次啟動改回完整掃描）：" + reason
                    + " animal=" + ax + "," + ay + "," + a.getZ()
                    + " target=" + o.getX() + "," + o.getY() + "," + o.getZ()
                    + " snapshot=" + snapX[idx] + "," + snapY[idx] + " margin=" + MARGIN);
        } catch (RuntimeException e) {
            anomalies++;
        }
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
            String head = announced ? "beat " : "首次生效（-Dmdc.animalLosIndex=0|off 停用、2|observe 只比對）";
            announced = true;
            DebugLog.log(TAG + head + "mode=" + MODE + " calls=" + calls + " fast=" + fast
                    + " exactAlerted=" + exactAlerted + " exactDomain=" + exactDomain
                    + " exactSnapshot=" + exactSnapshot + " audits=" + audits + " auditMisses=" + auditMisses
                    + " tailSwitches=" + tailSwitches + " lateFixes=" + lateFixes
                    + " candAvg=" + (calls > 0 ? candidateSum / Math.max(1L, calls - exactAlerted - exactDomain - exactSnapshot) : 0)
                    + " targetAvg=" + (calls > 0 ? targetSum / Math.max(1L, calls - exactAlerted - exactDomain - exactSnapshot) : 0)
                    + " rebuilds=" + rebuilds + " rebuildUsAvg=" + (rebuilds > 0 ? rebuildNs / rebuilds / 1000L : 0)
                    + " disabled=" + disabled + (disabled ? "(" + disabledReason + ")" : "")
                    + " modifiedExits=" + modifiedExits + " nested=" + nested + " anomalies=" + anomalies);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    static long nestedForTest() {
        return nested;
    }

    static long modifiedExitsForTest() {
        return modifiedExits;
    }
    // ---- 測試存取器 ----

    static long fastForTest() {
        return fast;
    }

    static long auditsForTest() {
        return audits;
    }

    static long auditMissesForTest() {
        return auditMisses;
    }

    static long lateFixesForTest() {
        return lateFixes;
    }

    static long tailSwitchesForTest() {
        return tailSwitches;
    }

    static boolean disabledForTest() {
        return disabled;
    }

    static long candidateSumForTest() {
        return candidateSum;
    }

    static long targetSumForTest() {
        return targetSum;
    }
}
