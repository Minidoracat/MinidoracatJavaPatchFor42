package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.LosUtil;
import zombie.network.GameServer;
import zombie.network.ServerLOS;
import zombie.vehicles.BaseVehicle;

/**
 * W83 伺服器端玩家 LOS 迴圈只走視窗內的物件（2026-10-28；docs/patches.md 2cr）。
 *
 * <p>{@code IsoPlayer.updateLOS()} 每位玩家每幀走完整份 {@code objectList}，對每個角色付一次前段判斷（4 次
 * {@code Type.tryCastTo}、{@code ServerLOS.isCouldSee} 內 O(玩家數) 的 {@code findData}、{@code getDetectionRange}）。
 * 伺服器上 {@code couldSee} 只可能在 {@code ServerLOS} PlayerData 的 96×96×{@code LosUtil.sizeZ} 視窗內成立，視窗外物件在原版
 * 只做純讀取與伺服器 no-op（alpha 系列在 {@code GameServer.server} 時開頭就 return）。本類把迴圈的迭代器換成過濾迭代器：
 * 底層仍是原版迭代器（順序、CME 時間點相同），只略過 physics／車輛、{@code current == null} 與視窗外的物件；
 * 自己、非 {@code IsoMovingObject}（含 null，讓原版照樣丟例外）與視窗內的物件一律放行，交回原版判斷。
 *
 * <p>手術：{@code updateLOS} 內唯一 {@code getCell()}（記下待用玩家）與唯一 {@code Set.iterator()} 改道本類。
 * 只在伺服器主執行緒、傳入的 Set 就是該玩家 cell 的 {@code getObjectList()}、找得到該玩家的 PlayerData 且 status 是
 * {@code BusyInMain} 時過濾（{@code ServerLOS.updateLOS} 只在這個狀態呼叫 {@code player.updateLOS()}，LOS 執行緒此時不寫
 * 這筆資料）；其他情況回原版迭代器並計入 fallback。
 *
 * <p>{@code -Dmdc.playerLosFilter}：{@code 2|observe}（程式預設：照原版、只抽樣）、{@code 1|on}（過濾）、{@code 0|off}；需重啟。
 * 兩個模式都對每次過了閘門的呼叫抽樣（私有亂數，各 1/64）：抽到比對就全部放行，對每個本來會因視窗略過的物件呼叫原版
 * {@code isCouldSee}，必須是 false；抽到原版計時就全部放行、不判斷，只量迴圈時間；其餘呼叫 on 過濾、
 * observe 直接回原版迭代器（不包裝）。比對發現不一致即本次啟動永久停用（回原版迭代器）並記一行。主執行緒單寫。
 *
 * <p>刀 3 網格（2026-11-27）：{@code -Dmdc.playerLosGrid}：{@code 2|observe}（預設，未知值也是）、{@code 1|on}、{@code 0|off}；
 * 只在 {@code -Dmdc.playerLosFilter} 是 on 時有作用。每幀第一次過閘門（或 {@code objectList} 的（加入次數, 大小）變了）時建快照：
 * 依迭代順序編索引，有格子的物件依格子放進 16 格網格，非 {@code IsoMovingObject}（含 null）與沒格子的放進「每次都看」；
 * 版本計數共用 W47 的 {@link AnimalLosIndex.ObjectSet}，清單不是它就停用網格。on：不抽樣的呼叫只看視窗外擴 {@link #MARGIN}
 * 格涵蓋的網格＋每次都看＋自己，依索引排序後逐件套用同一個 {@link #keep}，CME 照原版迭代器的時間點丟出。唯一假設：物件從建快照到
 * 這次呼叫移動不超過 {@link #MARGIN} 格。1/64 比對那一次另外確認放行的每個物件都在候選裡，缺一件 on 本次啟動停用網格（回刀 1 過濾器）、
 * observe 只計數。
 */
public final class PlayerLosFilter {
    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.playerLosFilter"));
    static final int GRID = MODE == MODE_ON ? parseMode(System.getProperty("mdc.playerLosGrid")) : MODE_OFF;
    /** 刀 3：物件從建快照到這次呼叫可移動的上限（格）；候選網格是視窗四周各外擴這麼多格。 */
    static final int MARGIN = 32;
    private static final int CELL_SHIFT = 4;
    private static final int COORD_BITS = 21;
    private static final long IDX_MASK = (1L << COORD_BITS) - 1L;
    private static final int COORD_MAX = (1 << COORD_BITS) - 1;

    /** 與 {@code ServerLOS.isCouldSee} 同一套整數視窗：{@code x - (px - 48)} 在 {@code [0, 96)}（SmokeCheck 釘 bipush 48／96）。 */
    private static final int HALF = 48;
    private static final int SPAN = 96;
    /**
     * 抽樣：每次 engaged 從私有 xorshift64* 取 6 bits（0..63），0＝比對、1＝原版計時。不用呼叫序號：每輪玩家的呼叫順序固定，
     * 序號 &63 會和玩家數鎖相（50 人只抽得到一半的位置、64 人永遠抽同一位）。不用遊戲的 Rand，免得改到遊戲的亂數序列。
     */
    private static final int AUDIT_DRAW = 0;
    private static final int PLAIN_DRAW = 1;
    /** 種子：{@code -Dmdc.playerLosFilter.seed} 只給測試重現用（正式服不設），沒設就用開機時的 nanoTime。 */
    static final long SEED = parseSeed(System.getProperty("mdc.playerLosFilter.seed"));
    private static final String TAG = "[MinidoracatJavaPatch][PlayerLosFilter] ";
    private static final long BEAT_NS = 300_000_000_000L;

    private static final int KEEP = 0;
    private static final int SKIP = 1;
    private static final int SKIP_WINDOW = 2;

    // ---- 反射（ServerLOS$PlayerData 是 private nested class）----
    private static final Field PLAYERS_MAIN;
    private static final Field PD_PLAYER;
    private static final Field PD_STATUS;
    private static final Field PD_PX;
    private static final Field PD_PY;
    private static final Field PD_PZ;
    private static final Object BUSY_IN_MAIN;
    private static final String REFLECT;

    static {
        Field[] f = new Field[6];
        Object busy = null;
        String result;
        try {
            Class<?> pd = Class.forName("zombie.network.ServerLOS$PlayerData");
            f[0] = ServerLOS.class.getDeclaredField("playersMain");
            String[] names = {"player", "status", "px", "py", "pz"};
            for (int i = 0; i < names.length; i++) {
                f[i + 1] = pd.getDeclaredField(names[i]);
            }
            for (Field x : f) {
                x.setAccessible(true);
            }
            for (Object c : f[2].getType().getEnumConstants()) {
                if (((Enum<?>) c).name().equals("BusyInMain")) {
                    busy = c;
                }
            }
            result = busy == null ? "fail(UpdateStatus.BusyInMain 不存在)" : "ok";
        } catch (ReflectiveOperationException | RuntimeException e) {
            result = "fail(" + e + ")";
        }
        boolean ok = result.equals("ok");
        PLAYERS_MAIN = ok ? f[0] : null;
        PD_PLAYER = ok ? f[1] : null;
        PD_STATUS = ok ? f[2] : null;
        PD_PX = ok ? f[3] : null;
        PD_PY = ok ? f[4] : null;
        PD_PZ = ok ? f[5] : null;
        BUSY_IN_MAIN = busy;
        REFLECT = result;
    }

    // ---- getCell 記下、iterator 取用（只在主執行緒寫讀）----
    private static IsoPlayer pendingPlayer;
    private static IsoCell pendingCell;

    // ---- 計數（主執行緒單寫；thread 例外）----
    private static long calls;
    private static long engaged;
    private static long notBusy;
    private static long noData;
    private static long reflect;
    private static final AtomicLong THREAD = new AtomicLong();
    private static long loops;
    private static long loopNs;
    private static long auditLoops;
    private static long auditLoopNs;
    private static long plains;
    private static long plainLoops;
    private static long plainLoopNs;
    private static long couldSeeChecks;
    private static long objSum;
    private static long keepSum;
    private static long audits;
    private static long mismatches;
    private static long anomalies;
    private static long rng = SEED;
    private static boolean disabled;
    private static boolean announced;
    private static long lastBeatNs;

    // ---- 刀 3 網格快照（主執行緒單寫）----
    private static AnimalLosIndex.ObjectSet snapList;
    private static long snapFrame;
    private static int snapAdds;
    private static int snapCount;
    private static Object[] snapObjs = new Object[0];
    private static int[] snapX = new int[0];
    private static int[] snapY = new int[0];
    /** （格 x, 格 y, 索引）排序後的鍵；同一格 x 的一段格 y 在鍵序上連續，每欄一次二分搜尋。 */
    private static long[] keys = new long[0];
    private static int keyCount;
    private static int[] always = new int[0];
    private static int alwaysCount;
    private static int[] scratch = new int[1];
    private static final IdentityHashMap<Object, Integer> PLAYER_AT = new IdentityHashMap<>();
    private static boolean gridDisabled;
    private static boolean gridLogged;
    private static long snapshots;
    private static long snapNs;
    private static long candN;
    private static long candSum;
    private static long gridLoops;
    private static long gridLoopNs;
    private static long gridMismatches;

    private PlayerLosFilter() {
    }

    static long parseSeed(String raw) {
        long s;
        try {
            s = raw == null || raw.isBlank() ? System.nanoTime() : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            s = System.nanoTime();
        }
        return s == 0L ? 0x9E3779B97F4A7C15L : s;
    }

    /** xorshift64*，取最高 6 bits（0..63）。只在主執行緒呼叫。 */
    private static int draw() {
        long x = rng;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rng = x;
        return (int) ((x * 0x2545F4914F6CDD1DL) >>> 58);
    }

    static int parseMode(String raw) {
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "on":
                return MODE_ON;
            default:
                return MODE_OBSERVE;
        }
    }

    /** {@code updateLOS} offset 128 {@code getCell()} 的改道目標。 */
    public static IsoCell getCell(IsoPlayer p) {
        IsoCell c = p.getCell();
        if (MODE != MODE_OFF && Thread.currentThread() == GameServer.mainThread) {
            pendingPlayer = p;
            pendingCell = c;
        }
        return c;
    }

    /** {@code updateLOS} offset 134 {@code Set.iterator()} 的改道目標；一律先建原版迭代器。 */
    public static Iterator<?> iterator(Set<?> s) {
        Iterator<?> it = s.iterator();
        if (MODE == MODE_OFF) {
            return it;
        }
        Thread main = GameServer.mainThread;
        if (!GameServer.server || main == null || Thread.currentThread() != main) {
            THREAD.incrementAndGet();
            return it;
        }
        IsoPlayer p = pendingPlayer;
        IsoCell c = pendingCell;
        pendingPlayer = null;
        pendingCell = null;
        calls++;
        maybeBeat();
        if (disabled) {
            return it;
        }
        ServerLOS los = ServerLOS.instance;
        if (p == null || c == null || c.getObjectList() != s || los == null) {
            noData++;
            return it;
        }
        if (PLAYERS_MAIN == null) {
            reflect++;
            return it;
        }
        int px;
        int py;
        int pz;
        try {
            ArrayList<?> list = (ArrayList<?>) PLAYERS_MAIN.get(los);
            Object d = null;
            // 與 ServerLOS.findData 同序：第一筆 player == p
            for (int i = 0, n = list.size(); i < n; i++) {
                Object e = list.get(i);
                if (PD_PLAYER.get(e) == p) {
                    d = e;
                    break;
                }
            }
            if (d == null) {
                noData++;
                return it;
            }
            if (PD_STATUS.get(d) != BUSY_IN_MAIN) {
                notBusy++;
                return it;
            }
            px = PD_PX.getInt(d);
            py = PD_PY.getInt(d);
            pz = PD_PZ.getInt(d);
        } catch (IllegalAccessException | RuntimeException e) {
            reflect++;
            return it;
        }
        engaged++;
        // 每次過閘門都確認快照（建快照的時間點 observe 與 on 相同，比對看到的快照新舊才一樣）
        boolean grid = GRID != MODE_OFF && !gridDisabled && gridSnapshot(s);
        int r = draw();
        if (r == PLAIN_DRAW) {
            plains++;
            return new Plain(it);
        }
        boolean audit = r == AUDIT_DRAW;
        if (!audit && MODE == MODE_OBSERVE) {
            return it;
        }
        if (audit) {
            audits++;
        }
        int sizeZ = LosUtil.sizeZ;
        int minX = px - HALF;
        int minY = py - HALF;
        int minZ = pz - sizeZ / 2;
        if (grid && (audit || GRID == MODE_ON)) {
            long t0 = System.nanoTime();
            int[] cand = gridCollect(p, minX, minY);
            if (cand != null) {
                if (!audit) {
                    return new Grid(p, minX, minY, minZ, sizeZ, cand, t0);
                }
                if (GRID == MODE_OBSERVE) {
                    gridLoops++;
                    gridLoopNs += System.nanoTime() - t0;
                }
                return new Filter(it, p, px, py, pz, minX, minY, minZ, sizeZ, true, cand, snapObjs);
            }
        }
        return new Filter(it, p, px, py, pz, minX, minY, minZ, sizeZ, audit, null, null);
    }

    /** 確認（必要時重建）這一幀的快照；回 false＝網格不可用（已停用）。 */
    private static boolean gridSnapshot(Set<?> s) {
        if (!(s instanceof AnimalLosIndex.ObjectSet os)) {
            disableGrid("objectList 不是 W47 的 ObjectSet（-Dmdc.animalLosIndex=off？），沒有版本計數");
            return false;
        }
        try {
            long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
            int size = os.size();
            if (os == snapList && frame == snapFrame && os.addCount == snapAdds && size == snapCount) {
                return true;
            }
            long t0 = System.nanoTime();
            snapList = null;
            build(os, size);
            snapList = os;
            snapFrame = frame;
            snapAdds = os.addCount;
            snapshots++;
            snapNs += System.nanoTime() - t0;
            return true;
        } catch (RuntimeException e) {
            anomalies++;
            disableGrid("建快照丟例外 " + e);
            return false;
        }
    }

    private static void build(Set<?> os, int size) {
        if (size > IDX_MASK) {
            throw new IllegalStateException("物件數 " + size + " 超過網格索引上限");
        }
        if (snapObjs.length < size) {
            int cap = Math.max(size, snapObjs.length * 2);
            snapObjs = new Object[cap];
            snapX = new int[cap];
            snapY = new int[cap];
            keys = new long[cap];
            always = new int[cap];
            scratch = new int[cap + 1];
        }
        PLAYER_AT.clear();
        int n = 0;
        int kc = 0;
        int ac = 0;
        for (Object o : os) {
            if (n == size) {
                throw new IllegalStateException("迭代數超過 size");
            }
            int idx = n++;
            snapObjs[idx] = o;
            if (!(o instanceof IsoMovingObject m)) {
                always[ac++] = idx;
                continue;
            }
            if (m instanceof IsoPhysicsObject || m instanceof BaseVehicle) {
                continue; // keep() 一律略過
            }
            if (m instanceof IsoPlayer) {
                PLAYER_AT.put(m, idx);
            }
            IsoGridSquare sq = m.getCurrentSquare();
            if (sq == null || sq.x < 0 || sq.y < 0 || sq.x >> CELL_SHIFT > COORD_MAX || sq.y >> CELL_SHIFT > COORD_MAX) {
                always[ac++] = idx;
                continue;
            }
            snapX[idx] = sq.x;
            snapY[idx] = sq.y;
            keys[kc++] = (long) (sq.x >> CELL_SHIFT) << 2 * COORD_BITS | (long) (sq.y >> CELL_SHIFT) << COORD_BITS | idx;
        }
        if (n != size) {
            throw new IllegalStateException("迭代數少於 size");
        }
        Arrays.sort(keys, 0, kc);
        // 清掉上一份快照多出來的參照，不留住已移出清單的物件
        Arrays.fill(snapObjs, n, Math.max(n, snapCount), null);
        snapCount = n;
        keyCount = kc;
        alwaysCount = ac;
    }

    /** 視窗外擴 MARGIN 的網格＋每次都看＋自己，依索引（迭代順序）排序去重；丟例外時停用網格並回 null。 */
    private static int[] gridCollect(IsoPlayer p, int minX, int minY) {
        try {
            int x0 = Math.max(0, Math.floorDiv(minX - MARGIN, 1 << CELL_SHIFT));
            int x1 = Math.min(COORD_MAX, Math.floorDiv(minX + SPAN - 1 + MARGIN, 1 << CELL_SHIFT));
            int y0 = Math.max(0, Math.floorDiv(minY - MARGIN, 1 << CELL_SHIFT));
            int y1 = Math.min(COORD_MAX, Math.floorDiv(minY + SPAN - 1 + MARGIN, 1 << CELL_SHIFT));
            int n = 0;
            for (int cx = x0; cx <= x1 && y0 <= y1; cx++) {
                long col = (long) cx << 2 * COORD_BITS;
                long hi = col | (long) y1 << COORD_BITS | IDX_MASK;
                for (int i = lowerBound(col | (long) y0 << COORD_BITS); i < keyCount && keys[i] <= hi; i++) {
                    scratch[n++] = (int) (keys[i] & IDX_MASK);
                }
            }
            System.arraycopy(always, 0, scratch, n, alwaysCount);
            n += alwaysCount;
            Integer self = PLAYER_AT.get(p);
            if (self != null) {
                scratch[n++] = self;
            }
            Arrays.sort(scratch, 0, n);
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (m == 0 || scratch[i] != scratch[m - 1]) {
                    scratch[m++] = scratch[i];
                }
            }
            candN++;
            candSum += m;
            return Arrays.copyOf(scratch, m);
        } catch (RuntimeException e) {
            anomalies++;
            disableGrid("收集候選丟例外 " + e);
            return null;
        }
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

    private static void disableGrid(String reason) {
        if (gridDisabled) {
            return;
        }
        gridDisabled = true;
        try {
            DebugLog.log(TAG + "網格停用（本次啟動改回刀 1 過濾器）：" + reason);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 比對：刀 1 放行的物件不在網格候選裡。on 停用網格，observe 只計數（各記第一次）。 */
    private static void gridMiss(Object o, int idx) {
        gridMismatches++;
        if (GRID != MODE_ON && gridLogged) {
            return;
        }
        String what;
        try {
            IsoGridSquare sq = ((IsoMovingObject) o).getCurrentSquare();
            what = "視窗內物件不在網格候選裡 obj=" + o.getClass().getSimpleName() + " moved=" + (sq.x - snapX[idx]) + ","
                    + (sq.y - snapY[idx]) + " margin=" + MARGIN;
        } catch (RuntimeException e) {
            anomalies++;
            what = "視窗內物件不在網格候選裡（" + e + "）";
        }
        if (GRID == MODE_ON) {
            disableGrid(what);
            return;
        }
        gridLogged = true;
        try {
            DebugLog.log(TAG + "網格比對不一致（observe 只計數，之後不再記）：" + what);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    /** 順序照研究報告：非 IsoMovingObject 放行、physics／車輛略過、自己放行、沒格子略過、視窗內放行。刀 1 與網格共用。 */
    private static int keep(Object o, IsoPlayer p, int minX, int minY, int minZ, int sizeZ) {
        if (!(o instanceof IsoMovingObject m)) {
            return KEEP;
        }
        if (m instanceof IsoPhysicsObject || m instanceof BaseVehicle) {
            return SKIP;
        }
        if (m == p) {
            return KEEP;
        }
        IsoGridSquare sq = m.getCurrentSquare();
        if (sq == null) {
            return SKIP;
        }
        int x = sq.x - minX;
        int y = sq.y - minY;
        int z = sq.z - minZ;
        return x >= 0 && x < SPAN && y >= 0 && y < SPAN && z >= 0 && z < sizeZ ? KEEP : SKIP_WINDOW;
    }

    /**
     * 刀 3 on：只走候選（索引遞增＝原版迭代順序），逐件套用 {@link #keep}。原版迭代器在交出的元素之後清單有增刪時，
     * 下一次 {@code next()} 丟 CME，交出的是整份清單最後一個則迴圈直接結束；這裡在同一個時間點（下一次 {@code hasNext}）照做。
     */
    private static final class Grid implements Iterator<Object> {
        private final IsoPlayer p;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeZ;
        private final int[] cand;
        private final Object[] objs = snapObjs;
        private final AnimalLosIndex.ObjectSet list = snapList;
        private final int adds = snapAdds;
        private final int count = snapCount;
        private final long t0;
        private int at;
        private int last = -1;
        private Object next;
        private boolean has;
        private boolean done;

        Grid(IsoPlayer p, int minX, int minY, int minZ, int sizeZ, int[] cand, long t0) {
            this.p = p;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeZ = sizeZ;
            this.cand = cand;
            this.t0 = t0;
        }

        @Override
        public boolean hasNext() {
            if (has) {
                return true;
            }
            if ((list.addCount != adds || list.size() != count) && last != count - 1) {
                throw new ConcurrentModificationException();
            }
            while (at < cand.length) {
                int k = cand[at++];
                Object o = objs[k];
                if (keep(o, p, minX, minY, minZ, sizeZ) == KEEP) {
                    last = k;
                    next = o;
                    has = true;
                    return true;
                }
            }
            if (!done) {
                done = true;
                gridLoops++;
                gridLoopNs += System.nanoTime() - t0;
            }
            return false;
        }

        @Override
        public Object next() {
            if (!has && !hasNext()) {
                throw new NoSuchElementException();
            }
            has = false;
            Object o = next;
            next = null;
            return o;
        }
    }

    /** 原版計時用：只委派底層 {@code hasNext}／{@code next}，迴圈結束（{@code hasNext()} 回 false）時記時間。 */
    private static final class Plain implements Iterator<Object> {
        private final Iterator<?> it;
        private final long t0 = System.nanoTime();
        private boolean done;

        Plain(Iterator<?> it) {
            this.it = it;
        }

        @Override
        public boolean hasNext() {
            boolean h = it.hasNext();
            if (!h && !done) {
                done = true;
                plainLoops++;
                plainLoopNs += System.nanoTime() - t0;
            }
            return h;
        }

        @Override
        public Object next() {
            return it.next();
        }
    }

    private static final class Filter implements Iterator<Object> {
        private final Iterator<?> it;
        private final IsoPlayer p;
        private final int px;
        private final int py;
        private final int pz;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeZ;
        private final boolean audit;
        /** 比對時這次的網格候選（索引遞增）與快照；null＝不比對網格。 */
        private final int[] gridCand;
        private final Object[] gridObjs;
        private int gp;
        private final long t0 = System.nanoTime();
        private Object next;
        private boolean has;
        private boolean done;
        private int objs;
        private int keeps;

        Filter(Iterator<?> it, IsoPlayer p, int px, int py, int pz, int minX, int minY, int minZ, int sizeZ, boolean audit,
                int[] gridCand, Object[] gridObjs) {
            this.it = it;
            this.p = p;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeZ = sizeZ;
            this.audit = audit;
            this.gridCand = gridCand;
            this.gridObjs = gridObjs;
        }

        @Override
        public boolean hasNext() {
            if (has) {
                return true;
            }
            // 底層 next() 照原版時間點丟 CME：被略過的元素也經它取出。
            while (it.hasNext()) {
                Object o = it.next();
                objs++;
                int k = keep(o, p, minX, minY, minZ, sizeZ);
                if (k == KEEP) {
                    keeps++;
                    if (gridCand != null) {
                        gridCheck(o);
                    }
                } else if (!audit) {
                    continue;
                } else if (k == SKIP_WINDOW) {
                    check((IsoMovingObject) o);
                }
                next = o;
                has = true;
                return true;
            }
            if (!done) {
                done = true;
                finish(this);
            }
            return false;
        }

        @Override
        public Object next() {
            if (!has && !hasNext()) {
                throw new NoSuchElementException();
            }
            has = false;
            Object o = next;
            next = null;
            return o;
        }

        /** 快照有效＝迭代順序與快照相同，所以第 objs 個元素的索引就是 objs-1；放行的必須在候選裡。 */
        private void gridCheck(Object o) {
            int idx = objs - 1;
            if (idx >= gridObjs.length || gridObjs[idx] != o) {
                anomalies++;
                disableGrid("比對時迭代順序與快照不同（第 " + idx + " 個）");
                return;
            }
            while (gp < gridCand.length && gridCand[gp] < idx) {
                gp++;
            }
            if (gp == gridCand.length || gridCand[gp] != idx) {
                gridMiss(o, idx);
            }
        }

        /** 原版本來就會對這個物件呼叫的純讀取；視窗外必須看不見。 */
        private void check(IsoMovingObject o) {
            IsoGridSquare sq = o.getCurrentSquare();
            couldSeeChecks++;
            try {
                if (ServerLOS.instance.isCouldSee(p, sq)) {
                    mismatch(this, o, sq);
                }
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
    }

    private static void finish(Filter f) {
        long ns = System.nanoTime() - f.t0;
        if (f.audit) {
            auditLoops++;
            auditLoopNs += ns;
        } else {
            loops++;
            loopNs += ns;
        }
        objSum += f.objs;
        keepSum += f.keeps;
    }

    private static void mismatch(Filter f, IsoMovingObject o, IsoGridSquare sq) {
        mismatches++;
        if (disabled) {
            return;
        }
        disabled = true;
        try {
            DebugLog.log(TAG + "停用（本次啟動改回原版迭代器）：視窗外物件 isCouldSee=true player=" + f.p.getUsername()
                    + "/" + f.p.getOnlineID() + " px=" + f.px + " py=" + f.py + " pz=" + f.pz
                    + " obj=" + o.getClass().getSimpleName() + "@" + sq.x + "," + sq.y + "," + sq.z);
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
            String head = announced ? "beat "
                    : "首次生效（-Dmdc.playerLosFilter=0|off 停用、1|on 過濾、2|observe 只抽樣（程式預設）；每次呼叫各以 1/64 隨機抽比對、"
                            + "原版計時；-Dmdc.playerLosGrid=0|off、1|on、2|observe（預設），只在過濾 on 時有作用；reflect=" + REFLECT + "）";
            announced = true;
            DebugLog.log(TAG + head + beatLine());
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    static String beatLine() {
        long fin = loops + auditLoops;
        return "mode=" + (MODE == MODE_ON ? "on" : "observe") + " calls=" + calls + " engaged=" + engaged
                + " fallback[notBusy=" + notBusy + " noData=" + noData + " reflect=" + reflect + " thread=" + THREAD.get() + "]"
                + " objAvg=" + avg(objSum, fin) + " keepAvg=" + avg(keepSum, fin) + " skipAvg=" + avg(objSum - keepSum, fin)
                + " loopUsAvg=" + avg(loopNs / 1000L, loops) + "/" + avg(plainLoopNs / 1000L, plainLoops) + "/"
                + avg(auditLoopNs / 1000L, auditLoops)
                + " audits=" + audits + " plains=" + plains + " mismatches=" + mismatches + " disabled=" + disabled
                + " anomalies=" + anomalies
                + " grid=" + (GRID == MODE_OFF ? "off" : gridDisabled ? "disabled" : GRID == MODE_ON ? "on" : "observe")
                + " snapshots=" + snapshots + " snapUsAvg=" + avg(snapNs / 1000L, snapshots) + " candAvg=" + avg(candSum, candN)
                + " gridLoops=" + gridLoops + " gridLoopUsAvg=" + avg(gridLoopNs / 1000L, gridLoops) + " gridMismatches=" + gridMismatches;
    }

    private static long avg(long sum, long n) {
        return n > 0 ? sum / n : 0L;
    }

    // ---- 測試存取器 ----
    static String reflectForTest() {
        return REFLECT;
    }

    static long[] countersForTest() {
        return new long[]{calls, engaged, notBusy, noData, reflect, THREAD.get(), loops, auditLoops, objSum, keepSum, audits,
                mismatches, anomalies, plains, plainLoops, couldSeeChecks, rng};
    }

    static boolean disabledForTest() {
        return disabled;
    }

    /** {snapshots, candN, candSum, gridLoops, gridMismatches}。 */
    static long[] gridCountersForTest() {
        return new long[]{snapshots, candN, candSum, gridLoops, gridMismatches};
    }

    static boolean gridDisabledForTest() {
        return gridDisabled;
    }
}
