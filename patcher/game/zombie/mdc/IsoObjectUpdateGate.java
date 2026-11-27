package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoThumpable;
import zombie.network.GameServer;

/**
 * W73 IsoObject 每幀更新閘（2026-10-25；docs/patches.md 2ch）。
 *
 * <p><b>背景</b>：{@code IsoCell.ProcessIsoObject} 每幀對 {@code processIsoObject} 的每件物件呼叫 {@code update()}，
 * 沒有 server 守衛。清單大宗推測是玩家建造的 {@code IsoThumpable}（牆、地板、門、家具、搬過的家具）。伺服器上
 * {@code IsoThumpable.update()} 只要 {@code lifeLeft} 不是 {@code > -1}（不是有燃料的燈）就只讀
 * {@code getObjectIndex()}（{@code square.objects.indexOf}）與 {@code getLifeLeft()} 然後返回，沒有任何寫入
 * （javap：bci 1 getObjectIndex、bci 12 {@code GameServer.server} ifne 298、bci 299–305 getLifeLeft／ldc -1.0f／
 * fcmpl／ifle 420＝return）。2026-10-25 晚峰 60 份 dump 有 5 份停在 ProcessIsoObject 本身（約 8% 主執行緒）。
 *
 * <p><b>模式</b>（{@code -Dmdc.isoObjectGate}，需重啟）：{@code 2|observe}（預設）照原版逐件 update、只量；
 * {@code 1|on} 在 {@code GameServer.server}、精確類別 {@code IsoThumpable}、每幀即時讀的 {@code lifeLeft}
 * 不是 {@code > -1}（含 NaN）時跳過；每 4096 次跳過抽一次照原版跑並比對 IsoThumpable 全部欄位，不一致即本次啟動
 * 永久回原版；{@code 0|off} 純委派、不計數；未知值當 observe。
 *
 * <p><b>observe 成本上限</b>：一般幀每件只多一次靜態旗標判斷，每幀兩次 {@code nanoTime}／一次
 * {@code currentTimeMillis}；每 5 分鐘挑一幀逐件計時（每件兩次 {@code nanoTime}＋一次 HashMap 查詢），同一幀結束時
 * 走一遍清單做普查並寫一行心跳。
 *
 * <p><b>第二刀：活躍子清單</b>（{@code -Dmdc.isoObjectActiveList}，2026-11-27；只有 isoObjectGate 是 on 且沒停用時才動作）：
 * {@code ProcessIsoObject} 的 {@code ArrayList.size}／{@code get} 與 {@code addToProcessIsoObject} 的 {@code ArrayList.add}
 * 同形改道到這裡，{@code IsoThumpable.setLifeLeft} 尾部呼叫 {@link #afterLifeLeft}。子清單＝真正清單裡不可跳過的物件
 * （依真正清單的相對順序，保留重複）；真正清單的內容與順序完全不動。{@code 2|observe}（預設）每輪照走完整清單、只比對子清單；
 * {@code 1|on} 平常只走子清單，每 {@value #FULL_EVERY} 輪、W73 計時幀、清單被繞過掛點改過或有非主執行緒 add 之後走完整輪；
 * 比對不一致本次啟動永久回完整輪；{@code 0|off} 不動作。
 *
 * <p>只有主執行緒呼叫 ProcessIsoObject；計數欄位只在主執行緒讀寫，跨執行緒寫入計數用 Atomic。
 */
public final class IsoObjectUpdateGate {

    static final int OFF = 0;
    static final int ON = 1;
    static final int OBSERVE = 2;
    static final String RAW = System.getProperty("mdc.isoObjectGate");
    static final int MODE = parseMode(RAW);

    private static final String TAG = "[MinidoracatJavaPatch][IsoObjectGate] ";
    private static final long HEARTBEAT_MS = 300_000L;
    static final int AUDIT_MASK = 4095;
    private static final int TOP_CLASSES = 12;
    private static final long DETAIL_LIMIT = 20L;

    /** on 模式抽樣比對不一致或 helper 自身例外：本次啟動永久回原版。 */
    private static volatile boolean disabled;
    private static final AtomicLong offThreadAdds = new AtomicLong();
    private static final AtomicLong offThreadRemoves = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();

    // 以下只在主執行緒。
    private static boolean started;
    private static boolean timing;
    private static long lastBeat;
    private static long passStartNs;
    private static long frames, passNsTotal, passNsMax;
    private static long removeAllCalls, removeAllRemoved, removeAllNsTotal, removeAllNsMax;
    private static long skipSeen, skips, audits, fallbacks;
    private static final Map<Class<?>, long[]> TIMED = new HashMap<>();
    private static long timedSkipN, timedSkipNs;
    private static Field[] thumpFields;
    private static Field setField;
    static String lastBeatLine;

    // ---- 第二刀：活躍子清單 ----
    static final String AL_RAW = System.getProperty("mdc.isoObjectActiveList");
    static final int AL_MODE = parseMode(AL_RAW);
    static final int FULL_EVERY = 600;
    private static final int NONE = 0, BEGUN = 1, FULL = 2, SUB = 3;
    /** 子清單比對不一致（on）或 helper 例外：本次啟動永久回完整輪。 */
    private static volatile boolean alDisabled;
    /** 非主執行緒 add 過真正清單：下一輪走完整輪重建、不比對。 */
    private static volatile boolean alDirty;
    /** setLifeLeft 之後 lifeLeft &gt; -1 的物件（LoadChunk 執行緒也會寫）；主執行緒在每輪開頭與完整輪結尾排空。 */
    private static final ConcurrentLinkedQueue<IsoThumpable> LIT = new ConcurrentLinkedQueue<>();
    private static final AtomicLong lifeQueued = new AtomicLong();
    // 以下只在主執行緒。
    private static ArrayList<IsoObject> active = new ArrayList<>();
    private static ArrayList<IsoObject> fresh = new ArrayList<>();
    private static Set<IsoObject> activeSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Set<IsoObject> spareSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ArrayList<IsoThumpable> LIT_NOW = new ArrayList<>();
    /** 子清單對應的真正清單；expectedSize＝經過掛點的增減後它應有的長度，對不上＝被繞過掛點改過。 */
    private static ArrayList<?> tracked;
    private static boolean built, collecting, compareValid;
    private static int passState, expectedSize, sizeAtStart, sinceFull;
    private static long fullPasses, subPasses, mismatches, resyncs, lifeAdded, aborted;

    static int parseMode(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            case "1", "on" -> ON;
            default -> OBSERVE;
        };
    }

    /** 跳過條件：與 javap 的 {@code getLifeLeft; ldc -1.0f; fcmpl; ifle → return} 相同（NaN 也跳過）。 */
    static boolean skippable(IsoObject o) {
        return GameServer.server && o.getClass() == IsoThumpable.class && !(((IsoThumpable) o).getLifeLeft() > -1.0F);
    }

    /** {@code IsoCell.ProcessIsoObject} 內唯一 {@code IsoObject.update()}（呼叫端已排除 null）。 */
    public static void update(IsoObject o) {
        if (MODE == OFF) {
            o.update();
            return;
        }
        if (timing) {
            timedUpdate(o);
            return;
        }
        run(o);
    }

    private static void run(IsoObject o) {
        if (MODE == ON && !disabled && skippable(o)) {
            if ((++skipSeen & AUDIT_MASK) != 0) {
                skips++;
                return;
            }
            audit((IsoThumpable) o);
            return;
        }
        if (collecting) {
            fresh.add(o);   // 完整輪：會交給 update() 的物件（不可跳過），依原版順序
        }
        o.update();
    }

    private static void timedUpdate(IsoObject o) {
        boolean skip = skippable(o);
        long t0 = System.nanoTime();
        run(o);
        long dt = System.nanoTime() - t0;
        if (skip) {
            timedSkipN++;
            timedSkipNs += dt;
        } else {
            long[] b = TIMED.computeIfAbsent(o.getClass(), k -> new long[2]);
            b[0]++;
            b[1] += dt;
        }
    }

    /** {@code ProcessIsoObject} 內唯一 {@code ArrayList.removeAll(processIsoObjectRemove)}。 */
    public static boolean removeAll(ArrayList<?> list, Collection<?> c) {
        if (MODE == OFF) {
            return list.removeAll(c);
        }
        int before = list.size();
        long t0 = System.nanoTime();
        boolean r = list.removeAll(c);
        long dt = System.nanoTime() - t0;
        int removed = before - list.size();
        removeAllCalls++;
        removeAllRemoved += removed;
        removeAllNsTotal += dt;
        removeAllNsMax = Math.max(removeAllNsMax, dt);
        if (removed > 0 && list == tracked && live()) {
            expectedSize -= removed;
            if (built) {
                active.removeIf(c::contains);   // 與 ArrayList.removeAll 同為 c.contains 語意（重複的一併拿掉）
                activeSet.removeIf(c::contains);
            }
        }
        return r;
    }

    private static boolean live() {
        return MODE == ON && !disabled && AL_MODE != OFF && !alDisabled;
    }

    /** {@code ProcessIsoObject} 的兩個 {@code ArrayList.size}：每輪第一次呼叫時決定這輪走完整清單還是子清單。 */
    public static int size(ArrayList<?> list) {
        if (passState == BEGUN) {
            decide(list);
        }
        return passState == SUB ? active.size() : list.size();
    }

    /** {@code ProcessIsoObject} 的 {@code ArrayList.get}。 */
    public static Object get(ArrayList<?> list, int n) {
        return passState == SUB ? active.get(n) : list.get(n);
    }

    private static void decide(ArrayList<?> list) {
        passState = FULL;
        if (!live()) {
            return;
        }
        int n = list.size();
        boolean dirty = alDirty;
        alDirty = false;
        boolean same = built && list == tracked && !dirty;
        if (same && n != expectedSize) {
            resyncs++;
            same = false;
        }
        compareValid = same;
        if (AL_MODE == ON && same && !timing && ++sinceFull < FULL_EVERY) {
            passState = SUB;
            subPasses++;
            return;
        }
        fullPasses++;
        collecting = true;
        fresh.clear();
        tracked = list;
        expectedSize = n;
        sizeAtStart = n;
    }

    /** {@code addToProcessIsoObject} 唯一 {@code ArrayList.add}（Set.add 成功才走到）：照原版加，再登記子清單。 */
    public static boolean add(ArrayList<Object> list, Object o) {
        boolean r = list.add(o);
        if (live()) {
            Thread main = GameServer.mainThread;
            if (main != null && Thread.currentThread() != main) {
                alDirty = true;
            } else if (list == tracked) {
                expectedSize++;
                // 完整輪中途加入的由完整輪結尾補登記（原版這輪也不 update 它；子清單輪 append 到尾端，迴圈用的是快取長度）
                if (built && passState != FULL && o instanceof IsoObject io && !skippable(io)) {
                    active.add(io);
                    activeSet.add(io);
                }
            }
        }
        return r;
    }

    /** {@code IsoThumpable.setLifeLeft} 尾部（寫入之後）：點亮的燈排進佇列，主執行緒下一次排空時加進子清單尾端。 */
    public static void afterLifeLeft(IsoThumpable t) {
        if (live() && t.getLifeLeft() > -1.0F) {
            LIT.add(t);
            lifeQueued.incrementAndGet();
        }
    }

    private static void drainLit(IsoCell cell) {
        try {
            boolean use = live() && built && cell.getProcessIsoObjects() == tracked;
            Set<?> set = use ? (Set<?>) setField().get(cell) : null;
            for (IsoThumpable t; (t = LIT.poll()) != null; ) {
                if (use) {
                    admitLit(t, set);
                }
            }
        } catch (RuntimeException | ReflectiveOperationException e) {
            alFail("drain " + e);
        }
    }

    private static void admitLit(IsoThumpable t, Set<?> set) {
        if (!skippable(t) && set.contains(t) && activeSet.add(t)) {
            active.add(t);
            lifeAdded++;
        }
    }

    private static void finishFull(IsoCell cell) {
        if (!live()) {
            built = false;
            fresh.clear();
            return;
        }
        try {
            rebuild(cell);
        } catch (RuntimeException | ReflectiveOperationException e) {
            alFail("rebuild " + e);
        }
    }

    private static void rebuild(IsoCell cell) throws ReflectiveOperationException {
        LIT_NOW.clear();
        for (IsoThumpable t; (t = LIT.poll()) != null; ) {
            LIT_NOW.add(t);
        }
        ArrayList<?> list = tracked;
        // 這輪中途被繞過掛點改過（長度對不上）：fresh 不可信，照樣重建但下一輪再走一次完整輪、不比對。
        boolean clean = list.size() == expectedSize;
        Set<IsoObject> next = spareSet;
        next.clear();
        next.addAll(fresh);
        if (compareValid && clean) {
            compare(next);
        }
        if (alDisabled) {
            alRelease();
            return;
        }
        ArrayList<IsoObject> old = active;
        active = fresh;
        fresh = old;
        fresh.clear();
        spareSet = activeSet;
        spareSet.clear();
        activeSet = next;
        for (int i = sizeAtStart, n = list.size(); i < n; i++) {
            if (list.get(i) instanceof IsoObject o && !skippable(o)) {
                active.add(o);
                activeSet.add(o);
            }
        }
        Set<?> set = (Set<?>) setField().get(cell);
        for (int i = 0; i < LIT_NOW.size(); i++) {
            admitLit(LIT_NOW.get(i), set);
        }
        LIT_NOW.clear();
        if (!clean) {
            alDirty = true;
        }
        expectedSize = list.size();
        sinceFull = 0;
        built = true;
    }

    /** 缺＝原版這輪 update 了但不在舊子清單；多＝舊子清單有、原版這輪沒 update 且仍不可跳過。兩邊都排除這輪才點亮的燈（輪到它時可能還沒亮、被跳過）。 */
    private static void compare(Set<IsoObject> freshSet) {
        int missing = 0, extra = 0;
        IsoObject firstMissing = null, firstExtra = null;
        for (int i = 0, n = fresh.size(); i < n; i++) {
            IsoObject o = fresh.get(i);
            if (!activeSet.contains(o) && !LIT_NOW.contains(o) && missing++ == 0) {
                firstMissing = o;
            }
        }
        for (int i = 0, n = active.size(); i < n; i++) {
            IsoObject o = active.get(i);
            if (!freshSet.contains(o) && !skippable(o) && !LIT_NOW.contains(o) && extra++ == 0) {
                firstExtra = o;
            }
        }
        if (missing + extra == 0) {
            return;
        }
        mismatches++;
        if (AL_MODE == ON) {
            alDisabled = true;
        }
        if (mismatches > DETAIL_LIMIT) {
            return;   // observe 每個完整輪都可能再不一致：只印前幾次，計數照加（心跳有 mismatches）
        }
        try {
            log("activeList MISMATCH missing=" + missing + (firstMissing == null ? "" : "(" + firstMissing.getClass().getSimpleName() + ")")
                    + " extra=" + extra + (firstExtra == null ? "" : "(" + firstExtra.getClass().getSimpleName() + ")")
                    + (AL_MODE == ON ? " DISABLED（本次啟動永久回完整輪）" : " observe 只計數") + " mismatches=" + mismatches);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    /**
     * 永久停用時放掉子清單持有的物件參照。呼叫點只在輪首（drainLit、beginPass 看到第一刀已停用）與完整輪輪尾（finishFull／rebuild），
     * 都不在子清單輪中途，size／get 不會拿到換掉的清單。
     */
    private static void alRelease() {
        built = false;
        tracked = null;
        active = new ArrayList<>(0);
        fresh = new ArrayList<>(0);
        activeSet = Collections.newSetFromMap(new IdentityHashMap<>());
        spareSet = Collections.newSetFromMap(new IdentityHashMap<>());
        LIT_NOW.clear();
        LIT_NOW.trimToSize();
        LIT.clear();
    }

    private static void alFail(String reason) {
        alDisabled = true;
        alRelease();
        anomalies.incrementAndGet();
        try {
            log("activeList DISABLED（本次啟動永久回完整輪）reason=" + reason);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    static String alName() {
        return AL_MODE == OFF ? "off" : alDisabled ? "disabled" : MODE != ON || disabled ? "inactive" : AL_MODE == ON ? "on" : "observe";
    }

    /** {@code ProcessIsoObject} 頭部。 */
    public static void beginPass(IsoCell cell) {
        if (MODE == OFF) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!started) {
            started = true;
            lastBeat = now;
            log("首次生效 mode=" + modeName() + " raw=" + RAW + " server=" + GameServer.server
                    + " activeList=" + alName() + " alRaw=" + AL_RAW
                    + "（-Dmdc.isoObjectGate=0|off 原版、1|on 跳過伺服器上 lifeLeft 不是 > -1 的 IsoThumpable、2|observe 只量；"
                    + "-Dmdc.isoObjectActiveList=0|off、1|on 只走活躍子清單、2|observe 只比對；心跳每 5 分鐘一行）");
        }
        timing = now - lastBeat >= HEARTBEAT_MS;
        passStartNs = System.nanoTime();
        if (passState == FULL || passState == SUB) {
            // 上一輪被例外打斷、沒經 endPass：完整輪中途 add 的物件沒補進子清單，下一輪重建、不比對
            aborted++;
            alDirty = true;
            fresh.clear();
        }
        // 第一刀永久停用後 live() 恆 false、不會再走到 rebuild：上一輪已結束，在這裡放掉還握著的子清單一次
        if (disabled && (built || tracked != null)) {
            alRelease();
        }
        passState = BEGUN;
        collecting = false;
        if (!LIT.isEmpty()) {
            drainLit(cell);
        }
    }

    /** {@code ProcessIsoObject} 唯一 RETURN 前。 */
    public static void endPass(IsoCell cell) {
        if (MODE == OFF) {
            return;
        }
        if (passState == FULL && collecting) {
            finishFull(cell);
        }
        passState = NONE;
        collecting = false;
        long ns = System.nanoTime() - passStartNs;
        frames++;
        passNsTotal += ns;
        passNsMax = Math.max(passNsMax, ns);
        if (timing) {
            timing = false;
            lastBeat = System.currentTimeMillis();
            try {
                beat(cell);
            } catch (RuntimeException e) {
                anomalies.incrementAndGet();
            }
            resetInterval();
        }
    }

    /** {@code IsoCell.addToProcessIsoObject} 頭部。 */
    public static void touchAdd(IsoCell cell) {
        offThread(offThreadAdds, "add");
    }

    /** {@code IsoCell.addToProcessIsoObjectRemove} 頭部。 */
    public static void touchRemove(IsoCell cell) {
        offThread(offThreadRemoves, "remove");
    }

    private static void offThread(AtomicLong counter, String what) {
        if (MODE == OFF) {
            return;
        }
        Thread main = GameServer.mainThread;
        if (main == null || Thread.currentThread() == main) {
            return;
        }
        long n = counter.incrementAndGet();
        if (n <= DETAIL_LIMIT || n % 1000 == 0) {
            try {
                log("off-main-thread " + what + " n=" + n + " thread=" + Thread.currentThread().getName() + " via=" + callers());
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
    }

    private static void audit(IsoThumpable t) {
        audits++;
        Object[] before;
        try {
            before = snapshot(t);
        } catch (RuntimeException | ReflectiveOperationException e) {
            disable("snapshot failed " + e, t);
            t.update();
            return;
        }
        t.update();
        auditAfter(t, before);
    }

    /** 抽樣比對：原版 update 跑完後 IsoThumpable 宣告的全部欄位必須與跑之前相同（原始型別比值、參照比身分）。 */
    static void auditAfter(IsoThumpable t, Object[] before) {
        try {
            Field[] fields = fields();
            for (int i = 0; i < fields.length; i++) {
                Object now = fields[i].get(t);
                boolean same = fields[i].getType().isPrimitive() ? Objects.equals(now, before[i]) : now == before[i];
                if (!same) {
                    disable("audit mismatch field=" + fields[i].getName() + " before=" + before[i] + " after=" + now, t);
                    return;
                }
            }
        } catch (RuntimeException | ReflectiveOperationException e) {
            disable("audit failed " + e, t);
        }
    }

    static Object[] snapshot(IsoThumpable t) throws ReflectiveOperationException {
        Field[] fields = fields();
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = fields[i].get(t);
        }
        return values;
    }

    private static Field[] fields() {
        if (thumpFields == null) {
            List<Field> list = new ArrayList<>();
            for (Field f : IsoThumpable.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    list.add(f);
                }
            }
            thumpFields = list.toArray(new Field[0]);
        }
        return thumpFields;
    }

    private static void disable(String reason, IsoObject o) {
        if (disabled) {
            return;
        }
        disabled = true;
        fallbacks++;
        String where = "?";
        try {
            where = o.getX() + "," + o.getY() + "," + o.getZ();
        } catch (RuntimeException e) {
            anomalies.incrementAndGet();
        }
        log("DISABLED（本次啟動永久回原版）reason=" + reason + " at " + where + " skips=" + skips + " audits=" + audits);
    }

    private static void beat(IsoCell cell) {
        ArrayList<IsoObject> list = cell.getProcessIsoObjects();
        int size = list.size();
        Map<Class<?>, int[]> counts = new HashMap<>();
        int nulls = 0, thump = 0, skip = 0, lit = 0, orphan = 0, noSquare = 0, noChunk = 0;
        for (int i = 0; i < size; i++) {
            IsoObject o = list.get(i);
            if (o == null) {
                nulls++;
                continue;
            }
            counts.computeIfAbsent(o.getClass(), k -> new int[1])[0]++;
            IsoGridSquare sq = o.getSquare();
            if (sq == null) {
                noSquare++;
            } else if (sq.getChunk() == null) {
                noChunk++;
            }
            if (o.getClass() == IsoThumpable.class) {
                thump++;
                if (((IsoThumpable) o).getLifeLeft() > -1.0F) {
                    lit++;
                } else {
                    skip++;
                }
                if (o.getObjectIndex() == -1) {
                    orphan++;
                }
            }
        }
        long ts = timestampCostNs();
        long timedNs = timedSkipNs;
        for (long[] b : TIMED.values()) {
            timedNs += b[1];
        }
        StringBuilder classes = new StringBuilder();
        counts.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0])).limit(TOP_CLASSES)
                .forEach(e -> classes.append(classes.isEmpty() ? "" : ",").append(e.getKey().getSimpleName())
                        .append(':').append(e.getValue()[0]));
        StringBuilder timed = new StringBuilder();
        if (timedSkipN > 0) {
            timed.append("IsoThumpable(skip):").append(timedSkipN).append('/').append(timedSkipNs / 1000);
        }
        TIMED.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(TOP_CLASSES)
                .forEach(e -> timed.append(timed.isEmpty() ? "" : ",").append(e.getKey().getSimpleName())
                        .append(':').append(e.getValue()[0]).append('/').append(e.getValue()[1] / 1000));
        long skipNetNs = Math.max(0L, timedSkipNs - timedSkipN * ts);
        String line = "beat mode=" + modeName() + (disabled ? "(disabled)" : "")
                + " frames=" + frames + " passAvgMs=" + ms(frames == 0 ? 0 : passNsTotal / frames) + " passMaxMs=" + ms(passNsMax)
                + " size=" + size + " setSize=" + setSize(cell) + " nulls=" + nulls
                + " removeAll=calls:" + removeAllCalls + ",removed:" + removeAllRemoved
                + ",avgUs:" + (removeAllCalls == 0 ? 0 : removeAllNsTotal / removeAllCalls / 1000) + ",maxUs:" + removeAllNsMax / 1000
                + " thump=" + thump + "(skip=" + skip + ",lit=" + lit + ",orphan=" + orphan + ")"
                + " noSquare=" + noSquare + " noChunk=" + noChunk
                + " classes=" + classes
                + " timedMs=" + ms(timedNs) + " skipMs=" + ms(timedSkipNs) + " skipNetMs=" + ms(skipNetNs)
                + " skipN=" + timedSkipN + " tsNs=" + ts
                + " timed=" + timed
                + " skips=" + skips + " audits=" + audits + " fallback=" + fallbacks
                + " offThreadAdds=" + offThreadAdds.get() + " offThreadRemoves=" + offThreadRemoves.get()
                + " anomalies=" + anomalies.get()
                + " activeList=" + alName() + " active=" + active.size() + " fullPasses=" + fullPasses + " subPasses=" + subPasses
                + " mismatches=" + mismatches + " resyncs=" + resyncs + " aborted=" + aborted
                + " lifeQueued=" + lifeQueued.get() + " lifeAdded=" + lifeAdded;
        lastBeatLine = line;
        log(line);
    }

    private static void resetInterval() {
        frames = passNsTotal = passNsMax = 0;
        removeAllCalls = removeAllRemoved = removeAllNsTotal = removeAllNsMax = 0;
        timedSkipN = timedSkipNs = 0;
        TIMED.clear();
    }

    /** 一次 nanoTime 的成本（逐件計時每件多一次，心跳的 skipNetMs 扣掉它）。 */
    private static long timestampCostNs() {
        long a = System.nanoTime();
        for (int i = 0; i < 64; i++) {
            System.nanoTime();
        }
        return (System.nanoTime() - a) / 65;
    }

    private static int setSize(IsoCell cell) {
        try {
            return ((Set<?>) setField().get(cell)).size();
        } catch (RuntimeException | ReflectiveOperationException e) {
            anomalies.incrementAndGet();
            return -1;
        }
    }

    private static Field setField() throws NoSuchFieldException {
        if (setField == null) {
            Field f = IsoCell.class.getDeclaredField("processIsoObjectSet");
            f.setAccessible(true);
            setField = f;
        }
        return setField;
    }

    private static String ms(long ns) {
        return String.valueOf(Math.round(ns / 10_000.0) / 100.0);
    }

    static String modeName() {
        return MODE == OFF ? "off" : MODE == ON ? "on" : "observe";
    }

    private static void log(String s) {
        DebugLog.log(TAG + s);
    }

    private static String callers() {
        return StackWalker.getInstance().walk(frames -> {
            StringBuilder s = new StringBuilder();
            frames.filter(f -> f.getClassName().startsWith("zombie.") && !f.getClassName().startsWith("zombie.mdc."))
                    .limit(8)
                    .forEach(f -> s.append(s.isEmpty() ? "" : "<")
                            .append(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1))
                            .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber()));
            return s.toString();
        });
    }

    static void forceTimedFrameForTest() {
        started = true;
        lastBeat = System.currentTimeMillis() - HEARTBEAT_MS;
    }

    static boolean disabledForTest() { return disabled; }
    static long skipsForTest() { return skips; }
    static long auditsForTest() { return audits; }
    static long fallbacksForTest() { return fallbacks; }
    static long offThreadAddsForTest() { return offThreadAdds.get(); }
    static long offThreadRemovesForTest() { return offThreadRemoves.get(); }
    static long removeAllCallsForTest() { return removeAllCalls; }
    static long removeAllRemovedForTest() { return removeAllRemoved; }
    static long framesForTest() { return frames; }
    static ArrayList<IsoObject> activeForTest() { return active; }
    static void dropActiveForTest(IsoObject o) { active.remove(o); activeSet.remove(o); }
    static void injectActiveForTest(IsoObject o) { active.add(o); activeSet.add(o); }
    static boolean alDisabledForTest() { return alDisabled; }
    static long fullPassesForTest() { return fullPasses; }
    static long subPassesForTest() { return subPasses; }
    static long mismatchesForTest() { return mismatches; }
    static long resyncsForTest() { return resyncs; }
    static long lifeQueuedForTest() { return lifeQueued.get(); }
    static long lifeAddedForTest() { return lifeAdded; }
    static long abortedForTest() { return aborted; }

    private IsoObjectUpdateGate() {}
}
