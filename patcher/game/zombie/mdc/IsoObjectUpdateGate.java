package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        removeAllCalls++;
        removeAllRemoved += before - list.size();
        removeAllNsTotal += dt;
        removeAllNsMax = Math.max(removeAllNsMax, dt);
        return r;
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
                    + "（-Dmdc.isoObjectGate=0|off 原版、1|on 跳過伺服器上 lifeLeft 不是 > -1 的 IsoThumpable、2|observe 只量；"
                    + "心跳每 5 分鐘一行）");
        }
        timing = now - lastBeat >= HEARTBEAT_MS;
        passStartNs = System.nanoTime();
    }

    /** {@code ProcessIsoObject} 唯一 RETURN 前。 */
    public static void endPass(IsoCell cell) {
        if (MODE == OFF) {
            return;
        }
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
                + " anomalies=" + anomalies.get();
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
            if (setField == null) {
                Field f = IsoCell.class.getDeclaredField("processIsoObjectSet");
                f.setAccessible(true);
                setField = f;
            }
            return ((Set<?>) setField.get(cell)).size();
        } catch (RuntimeException | ReflectiveOperationException e) {
            anomalies.incrementAndGet();
            return -1;
        }
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

    private IsoObjectUpdateGate() {}
}
