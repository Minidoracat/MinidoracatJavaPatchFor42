package zombie.pathfind.nativeCode;

import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.hash.TLongHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.vehicles.BaseVehicle;

/**
 * W85 只把動物附近的載具送進 native 尋路（2026-10-29；docs/patches.md 2ct）。
 *
 * <p><b>原版</b>：每輛加入世界的車都送進 native（{@code PathfindNative.addVehicle}），native 在動物搜尋碰到它時懶建立
 * 可見圖，下一次重建時再以 O(圖節點 × 全域 map) 釋放。失敗的動物搜尋會洪泛整片相連的世界，碰到的遠處車都付這筆成本
 * （本機 M1：遠處 300 輛 rb 547 ms、nr 51 ms；沒車或只有牧場旁 20 輛 rb 14–29 ms、nr 9–12 ms）。
 *
 * <p><b>手術</b>（同形 1:1 改道，receiver 前置）：{@code BaseVehicle.scriptReloaded} 的 remove＋add、{@code addToWorld} 的
 * add、{@code removeFromWorld} 的 remove、{@code PathfindNative.checkUseNativeCode} 啟動迴圈的 add → {@link #addVehicle}／
 * {@link #removeVehicle}（各恰一次呼叫原版或不呼叫）；{@code PathfindNative.updateMain} 唯一的 {@code IsoCell.getVehicles()}
 * → {@link #getVehicles}（主執行緒的範圍更新器，回傳原版同一個 Set；原版迴圈本來就略過沒有 native 狀態的車）。
 *
 * <p><b>模式</b> {@code -Dmdc.pathfindVehicleScope}：{@code 0|off} 全部照原版轉送、不計數；{@code 2|observe}（程式預設）
 * 照原版轉送，只記 M2 觀測欄位；{@code 1|on}：
 * <ul>
 *   <li>車只在離活著、在世界上的動物 R 格內才送進 native；範圍外的放進延後集合，不送任何任務。</li>
 *   <li>距離以 chunk（8×8 格）的 Chebyshev 距離算：進入 ≤ ceil(R/8)、離開 &gt; ceil(1.5R/8)（遲滯）。R 格內的車一定落在
 *       ceil(R/8) chunk 內（保守多送）。{@code -Dmdc.pathfindVehicleScopeR}：未設 64，8–1024 照用，其他值與亂碼 64。</li>
 *   <li>更新器約每秒一次：延後的車進入範圍就照原版 {@code addVehicle}，送過的車離開遲滯半徑就照原版 {@code removeVehicle}。
 *       沒送過的車被移除或重新加入（scriptReloaded）都不送任務；送過的車重新加入時用離開半徑判斷。</li>
 *   <li>自動回原版（本次啟動永久）：W74 探針看到非動物的尋路請求者（player、aggroZombie、zombie、other）、本類任何例外、
 *       沒有伺服器主執行緒時的呼叫，或探針被關掉（{@code -Dmdc.pathfindProbe=0}，看不到請求者）。回原版時先排空佇列，
 *       再把延後集合裡還在世界上的車各補送一次 {@code addVehicle}，記一行 log。這是保險，不是屏障：探針在第一個非動物
 *       請求的 native {@code findPath} 之前就記下，但那個請求與補送套用之前的請求仍在少了延後載具的世界裡搜尋。</li>
 * </ul>
 *
 * <p>簿記只在伺服器主執行緒。非主執行緒（例如 {@code Threading.World} 開著時 worker 執行 {@code VehiclesDB2.updateMain}
 * → {@code addToWorld}）的呼叫計 {@code offThread}、照發生順序排進並行佇列：on（還沒回原版）現在不呼叫原版，由主執行緒
 * 下一次入口或更新器排空時照同一套規則決定（所以 native 載具呼叫只在主執行緒）；observe 與回原版之後照原版時機直接送，
 * 佇列只用來同步簿記。動物來源是 {@code IsoCell.getObjectList()} 裡的 {@link IsoAnimal}（{@code AnimalInstanceManager}
 * 含不在世界上的動物、漏掉從雞舍載入的動物）。每次更新走一次物件清單與載具清單：動物 chunk 去重後放進邊長 {@link #GRID}
 * chunk 的格網，每輛車看 3×3 格、在每格裡線性走那格的動物 chunk（動物很集中時一格的 chunk 數就是比對次數）。
 *
 * <p>每 60 秒一行 beat（observe／on）：{@code animals animalChunks scopeChunks@32/64/96 loadedChunks vehicles
 * vehNearAnimals@32/64/96 vehNative}，on 再加 {@code deferred enter leave adds removes skipAdd skipRemove fallback}，
 * 最後 {@code scopeUsAvg scopeUsMax offThread anomalies beatUs}（{@code beatUs}＝組這一行本身的耗時）。{@code scopeChunks}
 * 是動物 chunk 各自 ±K 方塊的聯集面積（含沒載入的 chunk，是上界）；{@code vehNative} 是送進 native、還沒移除的車數
 * （＝原版 {@code vehicleState} 大小）。不支援執行中切換 {@code Pathfind.UseNativeCode}（{@code PathfindNative.stop} 不重設本類）。
 */
public final class MdcPathfindVehicleScope {
    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final String MODE_RAW = System.getProperty("mdc.pathfindVehicleScope");
    static final int MODE = parseMode(MODE_RAW);
    static final int DEFAULT_R = 64;
    static final String R_RAW = System.getProperty("mdc.pathfindVehicleScopeR");
    static final int R = parseRadius(R_RAW);
    static final int K_IN = kIn(R);
    static final int K_OUT = kOut(R);
    /** M2 觀測半徑（格）與對應 chunk 數。 */
    static final int[] OBS_K = {4, 8, 12};
    /** 格網邊長（chunk）：不小於任何查詢半徑，每次查詢最多 3×3 格。 */
    static final int GRID = Math.max(K_OUT, OBS_K[OBS_K.length - 1]);
    static final long UPDATE_NS = 1_000_000_000L;
    static final long BEAT_NS = 60_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][PathfindVehicleScope] ";
    private static final long BIAS = 1L << 24;

    // ---- 主執行緒單寫 ----
    /** 經本類送進 native、還沒移除的車（＝原版 vehicleState 的 key）。 */
    private static final HashSet<BaseVehicle> SENT = new HashSet<>();
    /** on：範圍外、沒送進 native 的車（插入順序＝回原版時的補送順序）。 */
    private static final LinkedHashSet<BaseVehicle> DEFERRED = new LinkedHashSet<>();
    private static final TLongHashSet ANIMAL_CHUNKS = new TLongHashSet();
    private static final TLongObjectHashMap<TLongArrayList> BUCKETS = new TLongObjectHashMap<>();
    private static BaseVehicle justRemoved;
    private static volatile boolean fallen = MODE == MODE_ON && !MdcPathfindProbe.ENABLED;
    private static String fallbackReason = fallen ? "probeOff" : null;
    private static boolean falling;
    private static volatile boolean disabled;
    /** 有呼叫發生在 {@code GameServer.mainThread} 還是 null 的時候（照原版、沒簿記）；on 在第一次更新時回原版。 */
    private static volatile boolean unbound;
    private static boolean announced;
    private static long lastUpdateNs;
    private static long windowStartNs;
    private static int animals;
    /** 測試用替身；正式服是 {@code PathfindNative.instance}。 */
    static PathfindNative nativeForTest;

    // 視窗（每次 beat 歸零）
    private static long enter;
    private static long leave;
    private static long adds;
    private static long removes;
    private static long skipAdds;
    private static long skipRemoves;
    private static long updates;
    private static long scopeNsSum;
    private static long scopeNsMax;

    // 累計
    private static final AtomicLong OFF_THREAD = new AtomicLong();
    private static long anomalies;

    private MdcPathfindVehicleScope() {
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

    /** 半徑（格）：未設 64；8–1024 照用；其他值與無法解析 64。 */
    static int parseRadius(String raw) {
        if (raw == null) {
            return DEFAULT_R;
        }
        try {
            int r = Integer.parseInt(raw.trim());
            return r >= 8 && r <= 1024 ? r : DEFAULT_R;
        } catch (NumberFormatException e) {
            return DEFAULT_R;
        }
    }

    /** 進入半徑（chunk）＝ceil(R/8)。 */
    static int kIn(int r) {
        return (r + 7) / 8;
    }

    /** 離開半徑（chunk）＝ceil(1.5R/8)。 */
    static int kOut(int r) {
        return (r * 3 + 15) / 16;
    }

    // ---- 改道入口 ----

    static final int OP_ADD = 0;
    static final int OP_REMOVE = 1;
    /** 原版已經在別的執行緒照時呼叫過，只差主執行緒補簿記（observe、on 回原版之後）。 */
    static final int NOTE_ADD = 2;
    static final int NOTE_REMOVE = 3;

    record Op(int kind, BaseVehicle vehicle) {
    }

    /** 非主執行緒的呼叫，照發生順序排隊；主執行緒的下一次入口或更新器先排空（同 W75 IsoObjectRemoveDefer 的做法）。 */
    private static final ConcurrentLinkedQueue<Op> PENDING = new ConcurrentLinkedQueue<>();

    /** 取代 {@code PathfindNative.instance.addVehicle(vehicle)}（BaseVehicle 兩處、checkUseNativeCode 一處）。 */
    public static void addVehicle(PathfindNative pathfind, BaseVehicle vehicle) {
        if (route(OP_ADD, vehicle)) {
            pathfind.addVehicle(vehicle);
        }
    }

    /** 取代 {@code PathfindNative.instance.removeVehicle(vehicle)}（BaseVehicle 兩處）。 */
    public static void removeVehicle(PathfindNative pathfind, BaseVehicle vehicle) {
        if (route(OP_REMOVE, vehicle)) {
            pathfind.removeVehicle(vehicle);
        }
    }

    /** 取代 {@code PathfindNative.updateMain} 內唯一的 {@code IsoCell.getVehicles()}：先跑範圍更新器，再回原版同一個 Set。 */
    public static Set<BaseVehicle> getVehicles(IsoCell cell) {
        Set<BaseVehicle> vehicles = cell.getVehicles();
        if (MODE != MODE_OFF) {
            if (disabled) {
                drainDirect();
            } else {
                try {
                    tick(cell, vehicles);
                } catch (RuntimeException | LinkageError e) {
                    fail("update", e);
                }
            }
        }
        return vehicles;
    }

    /**
     * 回傳 true＝呼叫端現在就照原版呼叫。非主執行緒：on（還沒回原版）排進佇列、現在不送，由主執行緒照同一套規則決定；
     * observe 與回原版之後照原版時機直接送，另排一筆簿記事件。主執行緒：先排空佇列（保持發生順序），再決定這一筆。
     */
    static boolean route(int op, BaseVehicle vehicle) {
        if (MODE == MODE_OFF) {
            return true;
        }
        Thread main = GameServer.mainThread;
        if (main == null) {
            // 沒有伺服器主執行緒（不是專用伺服器，或開服前）：照原版、不簿記；on 在第一次更新時回原版
            OFF_THREAD.incrementAndGet();
            unbound = true;
            return true;
        }
        if (Thread.currentThread() != main) {
            OFF_THREAD.incrementAndGet();
            if (disabled) {
                return true;
            }
            if (MODE == MODE_ON && !fallen) {
                PENDING.add(new Op(op, vehicle));
                return false;
            }
            PENDING.add(new Op(op + NOTE_ADD, vehicle));
            return true;
        }
        if (disabled) {
            drainDirect();
            return true;
        }
        try {
            drain();
            return decide(op, vehicle);
        } catch (RuntimeException | LinkageError e) {
            fail(op == OP_ADD ? "addVehicle" : "removeVehicle", e);
            return true;
        }
    }

    /**
     * 主執行緒：決定這個 add／remove 要不要交給原版，並更新簿記。on（還沒回原版）時範圍外的 add 延後、沒送過的車的 remove
     * 不送；送過的車緊接著重新加入時用離開半徑。判斷位置丟例外時這輛車照原版送、本次啟動回原版。
     */
    private static boolean decide(int op, BaseVehicle vehicle) {
        boolean scoped = MODE == MODE_ON && !fallen;
        if (op == OP_REMOVE) {
            justRemoved = null;
            boolean sent = SENT.remove(vehicle);
            if (scoped && !sent) {
                DEFERRED.remove(vehicle);
                skipRemoves++;
                return false;
            }
            removes++;
            justRemoved = vehicle;
            return true;
        }
        BaseVehicle reAdd = justRemoved;
        justRemoved = null;
        if (scoped && !SENT.contains(vehicle)) {
            int k = vehicle == reAdd ? K_OUT : K_IN;
            int d;
            try {
                d = nearest(chunkOf(vehicle.getX()), chunkOf(vehicle.getY()), k);
            } catch (RuntimeException | LinkageError e) {
                DEFERRED.remove(vehicle);
                SENT.add(vehicle);
                adds++;
                fail("addVehicle", e);
                return true;
            }
            if (d > k) {
                DEFERRED.add(vehicle);
                skipAdds++;
                return false;
            }
        }
        DEFERRED.remove(vehicle);
        SENT.add(vehicle);
        adds++;
        return true;
    }

    /** 主執行緒：照順序處理排隊的呼叫（需要時呼叫原版）與簿記事件。 */
    static void drain() {
        for (Op op = PENDING.poll(); op != null; op = PENDING.poll()) {
            BaseVehicle v = op.vehicle();
            switch (op.kind()) {
                case OP_ADD -> {
                    if (decide(OP_ADD, v)) {
                        pathfind().addVehicle(v);
                    }
                }
                case OP_REMOVE -> {
                    if (decide(OP_REMOVE, v)) {
                        pathfind().removeVehicle(v);
                    }
                }
                case NOTE_ADD -> {
                    justRemoved = null;
                    DEFERRED.remove(v);
                    SENT.add(v);
                    adds++;
                }
                default -> {
                    justRemoved = null;
                    SENT.remove(v);
                    removes++;
                }
            }
        }
    }

    /** 停止簿記之後：還在排隊的 add／remove 照順序交給原版，簿記事件丟掉。 */
    private static void drainDirect() {
        for (Op op = PENDING.poll(); op != null; op = PENDING.poll()) {
            try {
                if (op.kind() == OP_ADD) {
                    pathfind().addVehicle(op.vehicle());
                } else if (op.kind() == OP_REMOVE) {
                    pathfind().removeVehicle(op.vehicle());
                }
            } catch (RuntimeException | LinkageError e) {
                anomalies++;
            }
        }
    }

    static boolean onMainThread() {
        Thread main = GameServer.mainThread;
        return main != null && Thread.currentThread() == main;
    }

    // ---- 範圍更新器（主執行緒，約每秒一次） ----

    private static void tick(IsoCell cell, Set<BaseVehicle> vehicles) {
        if (!onMainThread()) {
            OFF_THREAD.incrementAndGet();
            return;
        }
        drain();
        long now = System.nanoTime();
        if (!announced) {
            announced = true;
            windowStartNs = now;
            lastUpdateNs = now - UPDATE_NS;
            DebugLog.log(TAG + "首次生效 mode=" + modeName() + " R=" + R + "（進入 " + K_IN + " chunk、離開 " + K_OUT
                    + " chunk；-Dmdc.pathfindVehicleScope=" + MODE_RAW + "、-Dmdc.pathfindVehicleScopeR=" + R_RAW
                    + "）" + (fallen ? " 已回原版：" + fallbackReason : "") + "；每 " + BEAT_NS / 1_000_000_000L + " 秒一行");
        }
        if (MODE == MODE_ON && !fallen) {
            int cat = MdcPathfindProbe.nonAnimalCat;
            if (cat >= 0) {
                fallback("nonAnimal:" + MdcPathfindProbe.CAT_NAMES[cat]);
            } else if (unbound) {
                fallback("noMainThread");
            }
        }
        if (now - lastUpdateNs < UPDATE_NS) {
            return;
        }
        lastUpdateNs = now;
        buildIndex(cell);
        if (MODE == MODE_ON && !fallen) {
            PathfindNative pathfind = pathfind();
            for (BaseVehicle v : vehicles) {
                boolean deferred = DEFERRED.contains(v);
                if (!deferred && !SENT.contains(v)) {
                    continue;
                }
                int d = nearest(chunkOf(v.getX()), chunkOf(v.getY()), K_OUT);
                if (deferred && d <= K_IN) {
                    DEFERRED.remove(v);
                    SENT.add(v);
                    enter++;
                    adds++;
                    pathfind.addVehicle(v);
                } else if (!deferred && d > K_OUT) {
                    SENT.remove(v);
                    DEFERRED.add(v);
                    leave++;
                    removes++;
                    pathfind.removeVehicle(v);
                }
            }
        }
        long ns = System.nanoTime() - now;
        updates++;
        scopeNsSum += ns;
        if (ns > scopeNsMax) {
            scopeNsMax = ns;
        }
        if (now - windowStartNs >= BEAT_NS) {
            String line = formatBeat(now, vehicles);
            resetWindow(now);
            DebugLog.log(line);
        }
    }

    private static PathfindNative pathfind() {
        return nativeForTest != null ? nativeForTest : PathfindNative.instance;
    }

    /**
     * 回原版（本次啟動永久）：先照原規則排空佇列，再把延後集合裡還在世界上的車各補送一次 addVehicle；之後排進來的照原版送。
     */
    static void fallback(String reason) {
        if (fallen || falling) {
            return;
        }
        falling = true;
        try {
            drain();
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        fallen = true;
        fallbackReason = reason;
        ArrayList<BaseVehicle> pending = new ArrayList<>(DEFERRED);
        DEFERRED.clear();
        justRemoved = null;
        PathfindNative pathfind = pathfind();
        int sent = 0;
        int gone = 0;
        int thrown = 0;
        for (BaseVehicle v : pending) {
            try {
                if (v.isRemovedFromWorld()) {
                    gone++;
                    continue;
                }
                SENT.add(v);
                pathfind.addVehicle(v);
                sent++;
            } catch (RuntimeException | LinkageError e) {
                thrown++;
                anomalies++;
            }
        }
        try {
            drain();
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        DebugLog.log(TAG + "回原版（" + reason + "）：補送 " + sent + " 輛延後的載具"
                + (gone > 0 ? "，略過已離開世界 " + gone + " 輛" : "") + (thrown > 0 ? "，補送丟例外 " + thrown + " 輛" : "")
                + "；本次啟動之後全部照原版轉送");
    }

    private static void fail(String where, Throwable e) {
        anomalies++;
        if (MODE == MODE_ON && !fallen) {
            fallback("exception@" + where + ": " + e);
        } else {
            DebugLog.log(TAG + "例外（" + where + "）：" + e + "；本次啟動停止簿記，全部照原版轉送");
        }
        disabled = true;
        drainDirect();
    }

    // ---- 動物 chunk 格網 ----

    static int chunkOf(float tile) {
        return (int) Math.floor(tile) >> 3;
    }

    static long key(int a, int b) {
        return (long) a << 32 | b & 0xFFFFFFFFL;
    }

    private static void buildIndex(IsoCell cell) {
        ANIMAL_CHUNKS.clear();
        BUCKETS.clear();
        int n = 0;
        for (IsoMovingObject o : cell.getObjectList()) {
            if (o instanceof IsoAnimal a && !a.isDead()) {
                n++;
                int cx = chunkOf(a.getX());
                int cy = chunkOf(a.getY());
                long chunk = key(cx, cy);
                if (ANIMAL_CHUNKS.add(chunk)) {
                    long bucket = key(Math.floorDiv(cx, GRID), Math.floorDiv(cy, GRID));
                    TLongArrayList list = BUCKETS.get(bucket);
                    if (list == null) {
                        list = new TLongArrayList(4);
                        BUCKETS.put(bucket, list);
                    }
                    list.add(chunk);
                }
            }
        }
        animals = n;
    }

    /** 到最近動物 chunk 的 Chebyshev 距離（chunk）；超過 {@code cap}（≤ GRID）回 {@code cap + 1}。 */
    static int nearest(int cx, int cy, int cap) {
        int best = cap + 1;
        int bx1 = Math.floorDiv(cx + cap, GRID);
        int by0 = Math.floorDiv(cy - cap, GRID);
        int by1 = Math.floorDiv(cy + cap, GRID);
        for (int bx = Math.floorDiv(cx - cap, GRID); bx <= bx1; bx++) {
            for (int by = by0; by <= by1; by++) {
                TLongArrayList list = BUCKETS.get(key(bx, by));
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    long c = list.getQuick(i);
                    int d = Math.max(Math.abs((int) (c >> 32) - cx), Math.abs((int) c - cy));
                    if (d < best) {
                        if (d == 0) {
                            return 0;
                        }
                        best = d;
                    }
                }
            }
        }
        return best;
    }

    /** 每個動物 chunk ±k 方塊的聯集面積（chunk 數）：逐列合併等長區間，O(n·k·log)。 */
    static long scopeChunks(int k) {
        int span = 2 * k + 1;
        long[] rows = new long[ANIMAL_CHUNKS.size() * span];
        int i = 0;
        for (long c : ANIMAL_CHUNKS.toArray()) {
            int ax = (int) (c >> 32);
            int ay = (int) c;
            for (int dy = -k; dy <= k; dy++) {
                rows[i++] = (ay + dy + BIAS) << 32 | (ax - k + BIAS);
            }
        }
        Arrays.sort(rows);
        long area = 0L;
        long row = Long.MIN_VALUE;
        long end = 0L;
        for (long r : rows) {
            long y = r >>> 32;
            long x0 = r & 0xFFFFFFFFL;
            long x1 = x0 + span;
            if (y != row) {
                row = y;
                area += span;
                end = x1;
            } else if (x1 > end) {
                area += x1 - Math.max(x0, end);
                end = x1;
            }
        }
        return area;
    }

    private static int loadedChunks() {
        int n = 0;
        for (ServerMap.ServerCell c : ServerMap.instance.loadedCells) {
            if (c != null && c.isLoaded) {
                for (int x = 0; x < 8; x++) {
                    for (int y = 0; y < 8; y++) {
                        if (c.chunks[x][y] != null) {
                            n++;
                        }
                    }
                }
            }
        }
        return n;
    }

    // ---- beat ----

    private static String modeName() {
        return MODE == MODE_ON ? "on" : MODE == MODE_OFF ? "off" : "observe";
    }

    static String formatBeat(long now, Set<BaseVehicle> vehicles) {
        long t0 = System.nanoTime();
        int[] near = new int[OBS_K.length];
        for (BaseVehicle v : vehicles) {
            int d = nearest(chunkOf(v.getX()), chunkOf(v.getY()), OBS_K[OBS_K.length - 1]);
            for (int j = 0; j < OBS_K.length; j++) {
                if (d <= OBS_K[j]) {
                    near[j]++;
                }
            }
        }
        StringBuilder sb = new StringBuilder(320);
        sb.append(TAG).append("beat sec=").append(String.format(Locale.ROOT, "%.1f", (now - windowStartNs) / 1e9))
                .append(" mode=").append(modeName())
                .append(" animals=").append(animals)
                .append(" animalChunks=").append(ANIMAL_CHUNKS.size())
                .append(" scopeChunks@32/64/96=").append(scopeChunks(OBS_K[0])).append('/').append(scopeChunks(OBS_K[1]))
                .append('/').append(scopeChunks(OBS_K[2]))
                .append(" loadedChunks=").append(loadedChunks())
                .append(" vehicles=").append(vehicles.size())
                .append(" vehNearAnimals@32/64/96=").append(near[0]).append('/').append(near[1]).append('/').append(near[2])
                .append(" vehNative=").append(SENT.size());
        if (MODE == MODE_ON) {
            sb.append(" deferred=").append(DEFERRED.size())
                    .append(" enter=").append(enter)
                    .append(" leave=").append(leave)
                    .append(" adds=").append(adds)
                    .append(" removes=").append(removes)
                    .append(" skipAdd=").append(skipAdds)
                    .append(" skipRemove=").append(skipRemoves)
                    .append(" fallback=").append(fallen ? "1(" + fallbackReason + ")" : "0");
        }
        sb.append(" scopeUsAvg=").append(updates == 0L ? 0L : scopeNsSum / updates / 1000L)
                .append(" scopeUsMax=").append(scopeNsMax / 1000L)
                .append(" offThread=").append(OFF_THREAD.get())
                .append(" anomalies=").append(anomalies)
                .append(" beatUs=").append((System.nanoTime() - t0) / 1000L);
        return sb.toString();
    }

    private static void resetWindow(long now) {
        windowStartNs = now;
        enter = 0L;
        leave = 0L;
        adds = 0L;
        removes = 0L;
        skipAdds = 0L;
        skipRemoves = 0L;
        updates = 0L;
        scopeNsSum = 0L;
        scopeNsMax = 0L;
    }

    // ---- 測試存取器 ----
    static void expireForTest() {
        lastUpdateNs -= UPDATE_NS;
    }

    static String beatForTest(Set<BaseVehicle> vehicles) {
        return formatBeat(System.nanoTime(), vehicles);
    }

    static Set<BaseVehicle> sentForTest() {
        return SENT;
    }

    static Set<BaseVehicle> deferredForTest() {
        return DEFERRED;
    }

    static boolean fallenForTest() {
        return fallen;
    }

    static String fallbackReasonForTest() {
        return fallbackReason;
    }

    static boolean disabledForTest() {
        return disabled;
    }

    static long offThreadForTest() {
        return OFF_THREAD.get();
    }

    static int pendingForTest() {
        return PENDING.size();
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static long[] countsForTest() {
        return new long[] {enter, leave, adds, removes, skipAdds, skipRemoves, updates};
    }

    static int animalsForTest() {
        return animals;
    }
}
