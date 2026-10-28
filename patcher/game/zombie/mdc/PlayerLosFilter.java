package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
 */
public final class PlayerLosFilter {
    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.playerLosFilter"));

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
        return new Filter(it, p, px, py, pz, px - HALF, py - HALF, pz - sizeZ / 2, sizeZ, audit);
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
        private final long t0 = System.nanoTime();
        private Object next;
        private boolean has;
        private boolean done;
        private int objs;
        private int keeps;

        Filter(Iterator<?> it, IsoPlayer p, int px, int py, int pz, int minX, int minY, int minZ, int sizeZ, boolean audit) {
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
                int k = keep(o);
                if (k == KEEP) {
                    keeps++;
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

        /** 順序照研究報告：非 IsoMovingObject 放行、physics／車輛略過、自己放行、沒格子略過、視窗內放行。 */
        private int keep(Object o) {
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
                            + "原版計時；reflect=" + REFLECT + "）";
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
                + " anomalies=" + anomalies;
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
}
