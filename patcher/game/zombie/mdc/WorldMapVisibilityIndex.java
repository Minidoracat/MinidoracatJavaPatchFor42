package zombie.mdc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.Faction;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.iso.areas.SafeHouse;
import zombie.network.GameServer;

/**
 * W86 世界地圖玩家位置的派系／安全屋查詢索引（2026-10-31；docs/patches.md 2cu）。
 *
 * <p>伺服器每秒一次 {@code GameServer.sendWorldMapPlayerPosition()}；{@code MapRemotePlayerVisibility=2} 時對每個
 * 「連線×玩家」組合跑兩次 {@code Faction.getPlayerFaction}（派系全表掃描）與一次 {@code SafeHouse.isInSameSafehouse}
 * （安全屋全表掃描），整輪擠在同一幀。本類在這一輪第一次查詢時建兩張表：名字→所在安全屋的 bitset（安全屋清單的位置），
 * 名字→依清單順序第一個 owner 或成員等於它的派系；之後每次查詢只查表。
 *
 * <p>手術：{@code main} 唯一的 {@code sendWorldMapPlayerPosition()} 改道 {@link #round()}，{@code isInSameFaction} 兩個
 * {@code Faction.getPlayerFaction}、{@code isAnyPlayerInSameSafehouse} 唯一的 {@code SafeHouse.isInSameSafehouse} 同形改道
 * 同名方法。等價依據：這一輪的呼叫圖不改安全屋、派系與名單，所以輪內的快照等於每次查詢當下的原版狀態；每輪重建。
 * 只在 {@code round()} 的執行緒、輪內才查表；輪外（{@code receiveWorldMapPlayerPosition}）、其他執行緒、名字是 null、
 * 清單含 null／非預期型別（原版會丟例外）、{@code Faction.factions} 被換掉時都呼叫原版。
 *
 * <p>{@code -Dmdc.worldMapVisibilityIndex}：{@code 2|observe}（程式預設：每次兩邊都算、回原版、計時）、{@code 1|on}
 * （查表，每 256 次比對原版一次）、{@code 0|off}；未知值當 observe，需重啟。不一致即本次啟動永久停用（全走原版）並記一行。
 * 心跳每 5 分鐘一行，只有計數與耗時，不記名字。
 */
public final class WorldMapVisibilityIndex {
    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.worldMapVisibilityIndex"));

    private static final int AUDIT_MASK = 255;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][WorldMapVisibilityIndex] ";

    // ---- 這一輪（只在 roundThread 讀寫；roundThread 非 null＝輪內）----
    private static Thread roundThread;
    private static long roundT0;
    private static boolean built;
    private static boolean shUntrusted;
    private static boolean facUntrusted;
    private static ArrayList<Faction> builtFactions;
    private static int words;
    private static long roundVanillaNs;
    private static long roundIndexNs;
    private static final HashMap<String, long[]> SAFEHOUSES = new HashMap<>();
    private static final HashMap<String, Faction> FACTIONS = new HashMap<>();
    /** bitset 池：每輪重用，不留安全屋參照。 */
    private static final ArrayList<long[]> POOL = new ArrayList<>();
    private static int poolUsed;

    // ---- 計數（round 執行緒單寫；輪外與其他執行緒用 Atomic）----
    private static long rounds;
    private static long roundNs;
    private static long roundNsMax;
    private static long builds;
    private static long buildNs;
    private static long buildNsMax;
    private static long qFaction;
    private static long qSafehouse;
    private static long fast;
    private static long nullArg;
    private static long untrusted;
    private static long swapped;
    private static final AtomicLong OUTSIDE = new AtomicLong();
    private static final AtomicLong OFF_THREAD = new AtomicLong();
    private static long audits;
    private static long mismatches;
    private static long observed;
    private static long vanillaNs;
    private static long vanillaNsMax;
    private static long indexNs;
    private static long indexNsMax;
    private static long anomalies;
    private static boolean disabled;
    private static boolean announced;
    private static long lastBeatNs;

    private WorldMapVisibilityIndex() {
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

    /** {@code GameServer.main} offset 4755 的改道目標：原版這一輪包在 begin／end 之間，例外照樣往外丟。 */
    public static void round() {
        boolean engage = MODE != MODE_OFF && !disabled;
        if (engage) {
            begin();
        }
        try {
            GameServer.sendWorldMapPlayerPosition();
        } finally {
            if (engage) {
                end();
            }
        }
    }

    static void begin() {
        if (MODE == MODE_OFF) {
            return;
        }
        roundThread = Thread.currentThread();
        built = false;
        roundVanillaNs = 0L;
        roundIndexNs = 0L;
        roundT0 = System.nanoTime();
    }

    static void end() {
        if (MODE == MODE_OFF) {
            return;
        }
        long ns = System.nanoTime() - roundT0;
        roundThread = null;
        SAFEHOUSES.clear();
        FACTIONS.clear();
        builtFactions = null;
        poolUsed = 0;
        rounds++;
        roundNs += ns;
        roundNsMax = Math.max(roundNsMax, ns);
        if (MODE == MODE_OBSERVE && built) {
            observed++;
            vanillaNs += roundVanillaNs;
            vanillaNsMax = Math.max(vanillaNsMax, roundVanillaNs);
            indexNs += roundIndexNs;
            indexNsMax = Math.max(indexNsMax, roundIndexNs);
        }
        maybeBeat();
    }

    /** {@code isInSameFaction} offset 1／6 的改道目標。 */
    public static Faction getPlayerFaction(IsoPlayer player) {
        if (MODE == MODE_OFF || !indexable()) {
            return Faction.getPlayerFaction(player);
        }
        qFaction++;
        if (player == null) {
            nullArg++;
            return Faction.getPlayerFaction(player);
        }
        if (facUntrusted) {
            untrusted++;
            return Faction.getPlayerFaction(player);
        }
        if (Faction.factions != builtFactions) {
            swapped++;
            return Faction.getPlayerFaction(player);
        }
        long t0 = MODE == MODE_OBSERVE ? System.nanoTime() : 0L;
        String name = player.getUsername();
        Faction f = name == null ? null : FACTIONS.get(name);
        fast++;
        if (MODE == MODE_OBSERVE) {
            long t1 = System.nanoTime();
            Faction v = Faction.getPlayerFaction(player);
            roundVanillaNs += System.nanoTime() - t1;
            roundIndexNs += t1 - t0;
            audit(v == f, "faction");
            return v;
        }
        if ((fast & AUDIT_MASK) == 0L) {
            Faction v = Faction.getPlayerFaction(player);
            audit(v == f, "faction");
            return v;
        }
        return f;
    }

    /** {@code isAnyPlayerInSameSafehouse} offset 29 的改道目標。 */
    public static boolean isInSameSafehouse(String a, String b) {
        if (MODE == MODE_OFF || !indexable()) {
            return SafeHouse.isInSameSafehouse(a, b);
        }
        qSafehouse++;
        if (a == null || b == null) {
            nullArg++;
            return SafeHouse.isInSameSafehouse(a, b);
        }
        if (shUntrusted) {
            untrusted++;
            return SafeHouse.isInSameSafehouse(a, b);
        }
        long t0 = MODE == MODE_OBSERVE ? System.nanoTime() : 0L;
        boolean r = shared(a, b);
        fast++;
        if (MODE == MODE_OBSERVE) {
            long t1 = System.nanoTime();
            boolean v = SafeHouse.isInSameSafehouse(a, b);
            roundVanillaNs += System.nanoTime() - t1;
            roundIndexNs += t1 - t0;
            audit(v == r, "safehouse");
            return v;
        }
        if ((fast & AUDIT_MASK) == 0L) {
            boolean v = SafeHouse.isInSameSafehouse(a, b);
            audit(v == r, "safehouse");
            return v;
        }
        return r;
    }

    /** 已停用、輪外或其他執行緒時 false（後兩者計入 fallback）；輪內第一次查詢時建表。 */
    private static boolean indexable() {
        if (disabled) {
            return false;
        }
        Thread t = roundThread;
        if (t == null) {
            OUTSIDE.incrementAndGet();
            return false;
        }
        if (t != Thread.currentThread()) {
            OFF_THREAD.incrementAndGet();
            return false;
        }
        if (!built) {
            build();
        }
        return !disabled;
    }

    private static boolean shared(String a, String b) {
        long[] x = SAFEHOUSES.get(a);
        long[] y = x == null ? null : SAFEHOUSES.get(b);
        if (y == null) {
            return false;
        }
        for (int i = 0; i < words; i++) {
            if ((x[i] & y[i]) != 0L) {
                return true;
            }
        }
        return false;
    }

    private static void build() {
        built = true;
        long t0 = System.nanoTime();
        try {
            buildSafehouses();
            buildFactions();
        } catch (RuntimeException e) {
            anomalies++;
            disable("build:" + e.getClass().getName());
            return;
        }
        long ns = System.nanoTime() - t0;
        builds++;
        buildNs += ns;
        buildNsMax = Math.max(buildNsMax, ns);
        roundIndexNs += ns;
    }

    /** {@code playerAllowed(n)}＝{@code players.contains(n) || owner.equals(n)}：owner 與每個字串成員標上這塊的位置。 */
    private static void buildSafehouses() {
        ArrayList<SafeHouse> list = SafeHouse.getSafehouseList();
        int n = list.size();
        words = (n + 63) >>> 6;
        shUntrusted = false;
        for (int i = 0; i < n; i++) {
            Object o = list.get(i);
            if (!(o instanceof SafeHouse h) || h.getOwner() == null || h.getPlayers() == null) {
                shUntrusted = true;   // 原版會在這塊丟例外：整輪交給原版重現
                return;
            }
            mark(h.getOwner(), i);
            ArrayList<String> players = h.getPlayers();
            for (int j = 0, m = players.size(); j < m; j++) {
                Object p = players.get(j);
                if (p instanceof String s) {   // contains(非 null 名字) 對 null 與非字串元素都是 false
                    mark(s, i);
                }
            }
        }
    }

    /** 原版依清單順序回第一個 owner 或成員等於名字的派系：依序 putIfAbsent（owner 先於成員）得到同一個物件。 */
    private static void buildFactions() {
        ArrayList<Faction> list = Faction.factions;
        builtFactions = list;
        facUntrusted = false;
        if (list == null) {
            facUntrusted = true;
            return;
        }
        for (int i = 0, n = list.size(); i < n; i++) {
            Object o = list.get(i);
            if (!(o instanceof Faction f) || f.getOwner() == null || f.getPlayers() == null) {
                facUntrusted = true;
                return;
            }
            FACTIONS.putIfAbsent(f.getOwner(), f);
            ArrayList<String> players = f.getPlayers();
            for (int j = 0, m = players.size(); j < m; j++) {
                Object p = players.get(j);
                if (!(p instanceof String s)) {   // 原版對成員呼叫 equals：null 丟 NPE、非字串丟 ClassCastException
                    facUntrusted = true;
                    return;
                }
                FACTIONS.putIfAbsent(s, f);
            }
        }
    }

    private static void mark(String name, int index) {
        long[] bits = SAFEHOUSES.get(name);
        if (bits == null) {
            if (poolUsed < POOL.size() && POOL.get(poolUsed).length >= words) {
                bits = POOL.get(poolUsed);
                Arrays.fill(bits, 0, words, 0L);
            } else {
                bits = new long[words];
                if (poolUsed < POOL.size()) {
                    POOL.set(poolUsed, bits);
                } else {
                    POOL.add(bits);
                }
            }
            poolUsed++;
            SAFEHOUSES.put(name, bits);
        }
        bits[index >>> 6] |= 1L << index;
    }

    private static void audit(boolean same, String kind) {
        audits++;
        if (!same) {
            mismatches++;
            disable(kind);
        }
    }

    private static void disable(String kind) {
        if (disabled) {
            return;
        }
        disabled = true;
        log("停用（本次啟動改回原版）：kind=" + kind + " " + beatLine());
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        String head = announced ? "beat "
                : "首次生效（-Dmdc.worldMapVisibilityIndex=0|off 停用、1|on 查表（每 256 次比對原版）、"
                        + "2|observe 兩邊都算回原版（程式預設））";
        announced = true;
        log(head + beatLine());
    }

    private static void log(String line) {
        try {
            DebugLog.log(TAG + line);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    static String beatLine() {
        String line = "mode=" + (MODE == MODE_ON ? "on" : MODE == MODE_OFF ? "off" : "observe") + " rounds=" + rounds
                + " queries[faction=" + qFaction + " safehouse=" + qSafehouse + "] fast=" + fast
                + " fallback[outsideRound=" + OUTSIDE.get() + " offThread=" + OFF_THREAD.get() + " null=" + nullArg
                + " untrusted=" + untrusted + " factionsSwapped=" + swapped + "]"
                + " builds=" + builds + " buildUs=" + us(buildNs, builds) + "/" + buildNsMax / 1000L
                + " roundUs=" + us(roundNs, rounds) + "/" + roundNsMax / 1000L;
        if (MODE == MODE_OBSERVE) {
            line += " vanillaUs=" + us(vanillaNs, observed) + "/" + vanillaNsMax / 1000L
                    + " indexUs=" + us(indexNs, observed) + "/" + indexNsMax / 1000L;
        }
        return line + " audits=" + audits + " mismatches=" + mismatches + " disabled=" + disabled + " anomalies=" + anomalies;
    }

    private static long us(long ns, long n) {
        return n > 0L ? ns / n / 1000L : 0L;
    }

    // ---- 測試存取器 ----
    static long[] countersForTest() {
        return new long[]{rounds, qFaction, qSafehouse, fast, nullArg, untrusted, swapped, OUTSIDE.get(), OFF_THREAD.get(),
                audits, mismatches, builds, observed, vanillaNs, indexNs, anomalies};
    }

    static boolean disabledForTest() {
        return disabled;
    }

    static boolean inRoundForTest() {
        return roundThread == Thread.currentThread();
    }
}
