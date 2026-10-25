package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoThumpable;
import zombie.network.GameServer;
import zombie.util.list.PZArrayList;

/**
 * W73 IsoObjectUpdateGate 行為驗證（argv：{@code observe}／{@code on}／{@code off}／{@code unknown}；
 * {@code bench} 另外量 observe 每件成本，不進建置）。跑原版 jar 的真 {@code IsoThumpable.update} 與 dist 內手術後的真
 * {@code IsoCell.ProcessIsoObject}／{@code addToProcessIsoObject}／{@code addToProcessIsoObjectRemove}：
 * <ul>
 *   <li>差分：伺服器／客戶端 × lifeLeft（-1、-2、-1 的下一個 float、-Inf、NaN、-0.5、-0、0、5、+Inf）× 格子（無、不在
 *       objects、在 objects）× 精確類別／子類別，原版 update 與閘門的結果逐欄位相同；跳過次數＝預期（只有 on）。</li>
 *   <li>原版負對照：有燃料的燈（lifeLeft &gt; -1）原版 update 會寫欄位——證明差分抓得到錯誤的跳過。</li>
 *   <li>mutant：{@code >=}、{@code <= -1}（NaN 不跳）、拿掉 server 檢查、改 instanceof、拿掉 lifeLeft 判斷，各至少一格與預期不同。</li>
 *   <li>on：每 4096 次跳過抽一次照原版跑；比對不一致永久停用、之後全照原版。</li>
 *   <li>真 ProcessIsoObject：null 略過、其他物件照常更新、removeAll 計數、心跳欄位、非主執行緒寫入計數；off 不計數。</li>
 * </ul>
 */
public final class IsoObjectUpdateGateTest {

    private static int failed;
    private static final float[] LIFE = {-1f, -2f, Math.nextDown(-1f), Float.NEGATIVE_INFINITY, Float.NaN,
            -0.5f, -0.0f, 0f, 5f, Float.POSITIVE_INFINITY};

    /** 與 IsoThumpable.update 寫入路徑無關的測試子類別：精確類別比對必須讓它照原版跑。 */
    public static class SubThumpable extends IsoThumpable {
        int updates;

        SubThumpable() {
            super(null);
        }

        @Override
        public void update() {
            updates++;
        }
    }

    /** 一般 IsoObject 成員（其他類別照原版每幀 update）。 */
    public static class Probe extends IsoObject {
        int updates;

        @Override
        public void update() {
            updates++;
        }
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String arg = args.length > 0 ? args[0] : "";
        if (arg.equals("bench")) {
            bench();
            return;
        }
        int expected = switch (arg) {
            case "on" -> IsoObjectUpdateGate.ON;
            case "off" -> IsoObjectUpdateGate.OFF;
            case "observe", "unknown" -> IsoObjectUpdateGate.OBSERVE;
            default -> throw new IllegalArgumentException("argv 必須是 observe|on|off|unknown：" + arg);
        };
        expect("旗標與 argv 相符：argv=" + arg + " MODE=" + IsoObjectUpdateGate.modeName() + " raw=" + IsoObjectUpdateGate.RAW,
                IsoObjectUpdateGate.MODE == expected);
        boolean on = expected == IsoObjectUpdateGate.ON;
        boolean off = expected == IsoObjectUpdateGate.OFF;
        GameServer.mainThread = Thread.currentThread();

        parseModes();
        differential(on);
        vanillaNegativeControl();
        mutants();
        processIsoObject(on, off);
        if (on) {
            auditAndDisable();   // 會永久停用，必須最後跑
        }

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("IsoObjectUpdateGateTest 全數通過（" + arg + "）");
    }

    private static void parseModes() {
        expect("模式解析：null→observe、0/off→off、1/on→on、2/observe→observe、未知→observe",
                IsoObjectUpdateGate.parseMode(null) == IsoObjectUpdateGate.OBSERVE
                && IsoObjectUpdateGate.parseMode("0") == IsoObjectUpdateGate.OFF
                && IsoObjectUpdateGate.parseMode(" OFF ") == IsoObjectUpdateGate.OFF
                && IsoObjectUpdateGate.parseMode("1") == IsoObjectUpdateGate.ON
                && IsoObjectUpdateGate.parseMode("on") == IsoObjectUpdateGate.ON
                && IsoObjectUpdateGate.parseMode("2") == IsoObjectUpdateGate.OBSERVE
                && IsoObjectUpdateGate.parseMode("observe") == IsoObjectUpdateGate.OBSERVE
                && IsoObjectUpdateGate.parseMode("enforce") == IsoObjectUpdateGate.OBSERVE);
    }

    /** 預期的跳過條件（獨立於 helper 寫一次）。 */
    private static boolean oracle(boolean server, Class<?> cls, float life) {
        return server && cls == IsoThumpable.class && !(life > -1f);
    }

    private static void differential(boolean on) throws Exception {
        int combos = 0, mismatches = 0, skipMismatch = 0, expectedSkips = 0;
        for (boolean server : new boolean[]{true, false}) {
            for (float life : LIFE) {
                for (int sqState = 0; sqState < 3; sqState++) {
                    GameServer.server = server;
                    IsoThumpable a = thumpable(life, sqState);
                    IsoThumpable b = thumpable(life, sqState);
                    long skipsBefore = IsoObjectUpdateGate.skipsForTest();
                    a.update();                              // 原版
                    IsoObjectUpdateGate.update(b);           // 閘門
                    String diff = diffAll(a, b);
                    combos++;
                    if (diff != null) {
                        mismatches++;
                        System.out.println("  !! 差分不一致 server=" + server + " life=" + life + " sq=" + sqState + " " + diff);
                    }
                    boolean want = on && oracle(server, IsoThumpable.class, life);
                    expectedSkips += want ? 1 : 0;
                    if ((IsoObjectUpdateGate.skipsForTest() - skipsBefore == 1) != want) {
                        skipMismatch++;
                        System.out.println("  !! 跳過與預期不同 server=" + server + " life=" + life + " sq=" + sqState);
                    }
                }
            }
        }
        GameServer.server = true;
        SubThumpable sub = raw(SubThumpable.class);
        setField(IsoThumpable.class, sub, "lifeLeft", -1f);
        long skipsBefore = IsoObjectUpdateGate.skipsForTest();
        IsoObjectUpdateGate.update(sub);
        expect("差分：" + combos + " 格原版 update 與閘門逐欄位相同（伺服器／客戶端 × lifeLeft × 格子狀態）", mismatches == 0);
        expect("跳過次數＝預期（" + (on ? "on 應跳 " + expectedSkips + " 格" : "非 on 一格都不跳") + "）", skipMismatch == 0);
        expect("子類別（lifeLeft=-1）照原版 update、不跳過", sub.updates == 1 && IsoObjectUpdateGate.skipsForTest() == skipsBefore);
    }

    private static void vanillaNegativeControl() throws Exception {
        GameServer.server = true;
        IsoThumpable lamp = thumpable(5f, 2);
        IsoThumpable copy = thumpable(5f, 2);
        lamp.update();
        expect("原版負對照：有燃料的燈（lifeLeft=5）原版 update 會寫欄位（差分抓得到錯誤的跳過）：" + diffAll(lamp, copy),
                diffAll(lamp, copy) != null);
        IsoThumpable wall = thumpable(-1f, 2);
        IsoThumpable wallCopy = thumpable(-1f, 2);
        wall.update();
        expect("原版：伺服器上 lifeLeft=-1 的 IsoThumpable update 不改任何欄位", diffAll(wall, wallCopy) == null);
    }

    private static void mutants() throws Exception {
        List<String> names = List.of(">= -1", "<= -1（NaN 不跳）", "拿掉 server 檢查", "改 instanceof", "拿掉 lifeLeft 判斷");
        List<Mutant> ms = List.of(
                (s, c, l) -> s && c == IsoThumpable.class && !(l >= -1f),
                (s, c, l) -> s && c == IsoThumpable.class && l <= -1f,
                (s, c, l) -> c == IsoThumpable.class && !(l > -1f),
                (s, c, l) -> s && IsoThumpable.class.isAssignableFrom(c) && !(l > -1f),
                (s, c, l) -> s && c == IsoThumpable.class);
        // 真 helper 判斷必須與預期一致；每個 mutant 至少一格不同。
        boolean helperOk = true;
        for (boolean server : new boolean[]{true, false}) {
            for (float life : LIFE) {
                GameServer.server = server;
                helperOk &= IsoObjectUpdateGate.skippable(thumpable(life, 2)) == oracle(server, IsoThumpable.class, life);
                SubThumpable sub = raw(SubThumpable.class);
                setField(IsoThumpable.class, sub, "lifeLeft", life);
                helperOk &= !IsoObjectUpdateGate.skippable(sub);
            }
        }
        GameServer.server = true;
        expect("helper skippable 與預期逐格相同（含子類別一律不跳）", helperOk);
        for (int i = 0; i < ms.size(); i++) {
            boolean killed = false;
            for (boolean server : new boolean[]{true, false}) {
                for (Class<?> c : new Class<?>[]{IsoThumpable.class, SubThumpable.class}) {
                    for (float life : LIFE) {
                        killed |= ms.get(i).skip(server, c, life) != oracle(server, c, life);
                    }
                }
            }
            expect("mutant 被抓到：" + names.get(i), killed);
        }
    }

    interface Mutant {
        boolean skip(boolean server, Class<?> cls, float life);
    }

    private static void auditAndDisable() throws Exception {
        GameServer.server = true;
        IsoThumpable wall = thumpable(-1f, 2);
        long skips0 = IsoObjectUpdateGate.skipsForTest(), audits0 = IsoObjectUpdateGate.auditsForTest();
        for (int i = 0; i < 2 * (IsoObjectUpdateGate.AUDIT_MASK + 1); i++) {
            IsoObjectUpdateGate.update(wall);
        }
        long audits = IsoObjectUpdateGate.auditsForTest() - audits0, skips = IsoObjectUpdateGate.skipsForTest() - skips0;
        expect("on：8192 次可跳過中抽 2 次照原版跑、比對一致不停用（audits=" + audits + " skips=" + skips + "）",
                audits == 2 && skips == 8190 && !IsoObjectUpdateGate.disabledForTest());

        // 比對不一致（模擬 TIS 讓 update 寫欄位）：永久停用，之後全照原版。
        Object[] before = IsoObjectUpdateGate.snapshot(wall);
        setField(IsoThumpable.class, wall, "lastUpdateHours", 123f);
        IsoObjectUpdateGate.auditAfter(wall, before);
        expect("on：抽樣比對不一致即停用、fallback=1", IsoObjectUpdateGate.disabledForTest()
                && IsoObjectUpdateGate.fallbacksForTest() == 1);
        long skips1 = IsoObjectUpdateGate.skipsForTest();
        Probe p = raw(Probe.class);
        IsoObjectUpdateGate.update(wall);
        IsoObjectUpdateGate.update(p);
        expect("on：停用後可跳過的物件照原版跑（skips 不再增加）、其他物件照常",
                IsoObjectUpdateGate.skipsForTest() == skips1 && p.updates == 1);
    }

    @SuppressWarnings("unchecked")
    private static void processIsoObject(boolean on, boolean off) throws Exception {
        GameServer.server = true;
        IsoCell cell = raw(IsoCell.class);
        ArrayList<IsoObject> list = new ArrayList<>();
        Set<IsoObject> set = new HashSet<>();
        Set<IsoObject> remove = new HashSet<>();
        setField(IsoCell.class, cell, "processIsoObject", list);
        setField(IsoCell.class, cell, "processIsoObjectSet", set);
        setField(IsoCell.class, cell, "processIsoObjectRemove", remove);
        Method process = IsoCell.class.getDeclaredMethod("ProcessIsoObject");
        process.setAccessible(true);

        IsoThumpable wall = thumpable(-1f, 2);
        IsoThumpable orphan = thumpable(-1f, 1);
        IsoThumpable lamp = thumpable(5f, 2);
        IsoThumpable unloaded = thumpable(-1f, 2);
        unloaded.square.chunk = null;
        Probe probe = raw(Probe.class), gone = raw(Probe.class), noSq = raw(Probe.class);
        for (IsoObject o : new IsoObject[]{wall, orphan, lamp, unloaded, probe, gone, noSq}) {
            cell.addToProcessIsoObject(o);
        }
        list.add(null);   // 原版迴圈對 null 略過
        cell.addToProcessIsoObjectRemove(gone);
        expect("主執行緒寫入不計", IsoObjectUpdateGate.offThreadAddsForTest() == 0
                && IsoObjectUpdateGate.offThreadRemovesForTest() == 0);

        long skips0 = IsoObjectUpdateGate.skipsForTest();
        IsoObjectUpdateGate.forceTimedFrameForTest();
        IsoObjectUpdateGate.lastBeatLine = null;
        process.invoke(cell);
        long skipped = IsoObjectUpdateGate.skipsForTest() - skips0;
        expect("真 ProcessIsoObject：removeAll 拿掉移除集合的物件", !list.contains(gone) && !set.contains(gone) && remove.isEmpty());
        expect("真 ProcessIsoObject：其他物件照常更新一次（probe=" + probe.updates + " noSquare=" + noSq.updates + "）",
                probe.updates == 1 && noSq.updates == 1 && gone.updates == 0);
        float lampHours = (float) getField(IsoThumpable.class, lamp, "lastUpdateHours");
        expect("真 ProcessIsoObject：燈照原版 update（lastUpdateHours 7→-1）", lampHours == -1f);
        expect("真 ProcessIsoObject：跳過 " + skipped + " 件（on 應 3：牆、不在格子上、已卸載；其他模式 0）",
                skipped == (on ? 3 : 0));
        String beat = IsoObjectUpdateGate.lastBeatLine;
        if (off) {
            expect("off：不計 removeAll、不出心跳", IsoObjectUpdateGate.removeAllCallsForTest() == 0 && beat == null
                    && IsoObjectUpdateGate.framesForTest() == 0);
        } else {
            System.out.println("  beat: " + beat);
            expect("心跳：模式、清單大小、Set 大小、null、removeAll、thumpable 分類、無格子／已卸載、類別計數、逐件計時欄位",
                    beat != null
                    && beat.startsWith("beat mode=" + (on ? "on" : "observe") + " frames=1 ")
                    && beat.contains(" size=7 setSize=6 nulls=1 ")
                    && beat.contains(" removeAll=calls:1,removed:1,")
                    && beat.contains(" thump=4(skip=3,lit=1,orphan=1) ")
                    && beat.contains(" noSquare=2 noChunk=1 ")
                    && beat.contains(" classes=IsoThumpable:4,Probe:2 ")
                    && beat.contains(" skipN=3 ")
                    && beat.contains(" timed=IsoThumpable(skip):3/")
                    && beat.contains(",IsoThumpable:1/") && beat.contains(",Probe:2/")
                    && beat.contains(" offThreadAdds=0 offThreadRemoves=0 anomalies=0"));
            expect("心跳後計數歸零（下一區間重新累計）", IsoObjectUpdateGate.framesForTest() == 0
                    && IsoObjectUpdateGate.removeAllCallsForTest() == 0);
            process.invoke(cell);
            expect("非逐件計時幀：不出心跳、frames 累計", IsoObjectUpdateGate.framesForTest() == 1 && probe.updates == 2);
        }

        Thread writer = new Thread(() -> {
            cell.addToProcessIsoObject(raw(Probe.class));
            cell.addToProcessIsoObjectRemove(probe);
        }, "LoadChunk");
        writer.start();
        writer.join();
        expect("非主執行緒 add／remove 各記一次（off 不記）",
                IsoObjectUpdateGate.offThreadAddsForTest() == (off ? 0 : 1)
                && IsoObjectUpdateGate.offThreadRemovesForTest() == (off ? 0 : 1));
    }

    // ---- 物件建構 ----

    private static IsoThumpable thumpable(float life, int sqState) {
        try {
            IsoThumpable t = raw(IsoThumpable.class);
            setField(IsoThumpable.class, t, "lifeLeft", life);
            setField(IsoThumpable.class, t, "lastUpdateHours", 7f);
            setField(IsoThumpable.class, t, "updateAccumulator", 3f);
            if (sqState > 0) {
                IsoGridSquare sq = raw(IsoGridSquare.class);
                setField(IsoGridSquare.class, sq, "objects", new PZArrayList<>(IsoObject.class, 2));
                sq.chunk = raw(IsoChunk.class);
                if (sqState == 2) {
                    sq.getObjects().add(t);
                }
                t.square = sq;
            }
            return t;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 兩物件全部實例欄位（含祖先）比對：原始型別比值、參照比身分（square 例外：只比 objects 成員關係）。 */
    private static String diffAll(Object a, Object b) throws Exception {
        for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object x = f.get(a), y = f.get(b);
                if (f.getName().equals("square") && c == IsoObject.class) {
                    boolean xs = x != null && ((IsoGridSquare) x).getObjects().indexOf(a) >= 0;
                    boolean ys = y != null && ((IsoGridSquare) y).getObjects().indexOf(b) >= 0;
                    if ((x == null) != (y == null) || xs != ys) {
                        return c.getSimpleName() + "." + f.getName();
                    }
                    continue;
                }
                boolean same = f.getType().isPrimitive() ? Objects.equals(x, y) : x == y;
                if (!same) {
                    return c.getSimpleName() + "." + f.getName() + " " + x + "≠" + y;
                }
            }
        }
        return null;
    }

    // ---- observe 每件成本（手動跑，不進建置）----

    private static void bench() throws Exception {
        GameServer.server = true;
        List<IsoObject> pool = new ArrayList<>();
        for (int i = 0; i < 60_000; i++) {
            pool.add(switch (i % 4) {
                case 0 -> raw(Probe.class);
                case 1 -> raw(B1.class);
                case 2 -> raw(B2.class);
                default -> thumpable(-1f, 2);
            });
        }
        java.util.Collections.shuffle(pool, new java.util.Random(7));
        IsoObject[] arr = pool.toArray(new IsoObject[0]);
        BiPredicate<IsoObject[], Boolean> pass = (xs, gate) -> {
            for (IsoObject o : xs) {
                if (gate) {
                    IsoObjectUpdateGate.update(o);
                } else {
                    o.update();
                }
            }
            return true;
        };
        for (int w = 0; w < 400; w++) {
            pass.test(arr, false);
            pass.test(arr, true);
        }
        long vanilla = 0, gated = 0;
        int reps = 400;
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            pass.test(arr, (r & 1) == 0);
            long t1 = System.nanoTime();
            pass.test(arr, (r & 1) != 0);
            long t2 = System.nanoTime();
            if ((r & 1) == 0) {
                gated += t1 - t0;
                vanilla += t2 - t1;
            } else {
                vanilla += t1 - t0;
                gated += t2 - t1;
            }
        }
        double nv = (double) vanilla / reps / arr.length, ng = (double) gated / reps / arr.length;
        System.out.printf("bench mode=%s 60000 件（1/4 IsoThumpable lifeLeft=-1）：原版 %.2f ns/件、閘門 %.2f ns/件、差 %.2f ns/件%n",
                IsoObjectUpdateGate.modeName(), nv, ng, ng - nv);
    }

    public static class B1 extends IsoObject {
        int n;

        @Override
        public void update() {
            n += 2;
        }
    }

    public static class B2 extends IsoObject {
        int n;

        @Override
        public void update() {
            n ^= 1;
        }
    }

    // ---- 反射工具 ----

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @SuppressWarnings("unchecked")
    private static <T> T raw(Class<T> type) {
        try {
            Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
            Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(type, objCtor);
            alloc.setAccessible(true);
            return (T) alloc.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "pig pass  " : "pig FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private IsoObjectUpdateGateTest() {}
}
