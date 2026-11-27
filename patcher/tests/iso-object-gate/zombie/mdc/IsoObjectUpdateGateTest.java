package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;

import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoThumpable;
import zombie.network.GameServer;

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
 *   <li>第二刀活躍子清單（argv {@code al-off}／{@code al-observe}／{@code al-on}，isoObjectGate=on）：真 IsoCell 與原版迴圈的
 *       模擬跑同一份腳本 1300 輪（加入、移除、別的執行緒點燈、燈燒完、非主執行緒 add、繞過掛點改清單、迴圈中途加入／移除），
 *       每輪比對「有作用的 update 序列」與清單內容順序；外加兩項已知差別的確切形狀，以及子清單漏一件／多一件時的偵測與停用。</li>
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
            case "on", "al-off", "al-observe", "al-on" -> IsoObjectUpdateGate.ON;
            case "off" -> IsoObjectUpdateGate.OFF;
            case "observe", "unknown" -> IsoObjectUpdateGate.OBSERVE;
            default -> throw new IllegalArgumentException("argv 必須是 observe|on|off|unknown|al-off|al-observe|al-on：" + arg);
        };
        expect("旗標與 argv 相符：argv=" + arg + " MODE=" + IsoObjectUpdateGate.modeName() + " raw=" + IsoObjectUpdateGate.RAW,
                IsoObjectUpdateGate.MODE == expected);
        boolean on = expected == IsoObjectUpdateGate.ON;
        boolean off = expected == IsoObjectUpdateGate.OFF;
        GameServer.mainThread = Thread.currentThread();

        parseModes();
        if (arg.startsWith("al-")) {
            int al = arg.equals("al-off") ? IsoObjectUpdateGate.OFF : arg.equals("al-on") ? IsoObjectUpdateGate.ON : IsoObjectUpdateGate.OBSERVE;
            expect("第二刀旗標與 argv 相符：AL_MODE=" + IsoObjectUpdateGate.alName() + " alRaw=" + IsoObjectUpdateGate.AL_RAW,
                    IsoObjectUpdateGate.AL_MODE == al);
            activeList(al);
        } else {
            differential(on);
            vanillaNegativeControl();
            mutants();
            processIsoObject(on, off);
            activeListInert(on);
            if (on) {
                auditAndDisable();   // 會永久停用，必須最後跑
            }
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
        boolean heldBefore = !IsoObjectUpdateGate.activeForTest().isEmpty();
        Object[] before = IsoObjectUpdateGate.snapshot(wall);
        setField(IsoThumpable.class, wall, "lastUpdateHours", 123f);
        IsoObjectUpdateGate.auditAfter(wall, before);
        expect("on：抽樣比對不一致即停用、fallback=1", IsoObjectUpdateGate.disabledForTest()
                && IsoObjectUpdateGate.fallbacksForTest() == 1);
        boolean heldAtDisable = !IsoObjectUpdateGate.activeForTest().isEmpty();
        IsoCell cell = raw(IsoCell.class);
        IsoObjectUpdateGate.beginPass(cell);
        boolean released = IsoObjectUpdateGate.activeForTest().isEmpty();
        IsoObjectUpdateGate.endPass(cell);
        expect("on：停用前子清單有物件（" + heldBefore + "）、停用當下不放（可能在子清單輪中途，" + heldAtDisable + "），下一輪開始時放掉（" + released + "）",
                heldBefore && heldAtDisable && released);
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

    /** 非 al-* 執行：第二刀預設 observe；isoObjectGate 不是 on 時完全不動作，on 時每輪都是完整輪且比對一致。 */
    private static void activeListInert(boolean on) {
        IsoThumpable t = thumpable(-1f, 2);
        long q0 = IsoObjectUpdateGate.lifeQueuedForTest();
        t.setLifeLeft(5f);   // dist 手術後的 setLifeLeft（尾部 afterLifeLeft）
        long queued = IsoObjectUpdateGate.lifeQueuedForTest() - q0;
        if (on) {
            expect("第二刀預設 observe（isoObjectGate=on）：真 ProcessIsoObject 每輪完整輪、沒有子清單輪、比對一致、點燈有入列（fullPasses="
                            + IsoObjectUpdateGate.fullPassesForTest() + " queued=" + queued + "）",
                    IsoObjectUpdateGate.AL_MODE == IsoObjectUpdateGate.OBSERVE && IsoObjectUpdateGate.fullPassesForTest() >= 2
                    && IsoObjectUpdateGate.subPassesForTest() == 0 && IsoObjectUpdateGate.mismatchesForTest() == 0 && queued == 1);
        } else {
            expect("第二刀：isoObjectGate 不是 on 時不動作（無完整輪計數、子清單空、點燈不入列）",
                    IsoObjectUpdateGate.fullPassesForTest() == 0 && IsoObjectUpdateGate.activeForTest().isEmpty() && queued == 0
                    && IsoObjectUpdateGate.alName().equals("inactive"));
        }
    }

    // ---- 第二刀：活躍子清單 ----

    private static Method processMethod;

    /** 會記錄自己被 update 的一般物件；onUpdate 在下一次 update 時跑一次（迴圈中途加入／移除／點燈）。 */
    public static class Rec extends IsoObject {
        World w;
        int id;
        Runnable onUpdate;

        @Override
        public void update() {
            w.trace.add(id);
            Runnable r = onUpdate;
            if (r != null) {
                onUpdate = null;
                r.run();
            }
        }
    }

    /** 測試用：某物件的 update 丟出、把整個 ProcessIsoObject 打斷的例外。 */
    static final class Boom extends RuntimeException {
        Boom() {
            super("測試：update 中斷", null, false, false);
        }
    }

    /** 兩邊各跑一輪，兩邊都必須被 Boom 打斷，且打斷前的 update 序列與清單相同。 */
    private static boolean abortedRound(World a, World b) throws Exception {
        a.trace.clear();
        b.trace.clear();
        boolean ta = false, tb = false;
        try {
            a.process();
        } catch (java.lang.reflect.InvocationTargetException e) {
            ta = e.getCause() instanceof Boom;
        }
        try {
            b.process();
        } catch (Boom e) {
            tb = true;
        }
        return ta && tb && a.trace.equals(b.trace) && a.ids().equals(b.ids());
    }

    private static int firstRec(World w, boolean last) {
        int found = -1;
        for (IsoObject o : w.list) {
            if (o instanceof Rec rec) {
                found = rec.id;
                if (!last) break;
            }
        }
        return found;
    }

    /** IsoThumpable 所在格子的 objects：原版 update 第一步 getObjectIndex＝objects.indexOf；只記有作用的（燈亮著）那次。 */
    static final class RecList extends ArrayList<IsoObject> {
        final World w;

        RecList(World w) {
            super(2);
            this.w = w;
        }

        @Override
        public int indexOf(Object o) {
            if (o instanceof IsoThumpable t && t.getLifeLeft() > -1f) {
                w.trace.add(w.idOf.get(t));
            }
            return super.indexOf(o);
        }
    }

    /** real＝真 IsoCell（dist 手術後的 ProcessIsoObject／addToProcessIsoObject*）；否則＝原版迴圈與兩個寫入方法的模擬。 */
    static final class World {
        final boolean real;
        final IsoCell cell = raw(IsoCell.class);
        final ArrayList<IsoObject> list = new ArrayList<>();
        final Set<IsoObject> set = new HashSet<>();
        final Set<IsoObject> remove = new HashSet<>();
        final List<Integer> trace = new ArrayList<>();
        final Map<Integer, IsoObject> byId = new HashMap<>();
        final Map<IsoObject, Integer> idOf = new IdentityHashMap<>();

        World(boolean real) throws Exception {
            this.real = real;
            setField(IsoCell.class, cell, "processIsoObject", list);
            setField(IsoCell.class, cell, "processIsoObjectSet", set);
            setField(IsoCell.class, cell, "processIsoObjectRemove", remove);
        }

        void add(IsoObject o) {
            if (real) {
                cell.addToProcessIsoObject(o);
            } else {
                remove.remove(o);
                if (set.add(o)) {
                    list.add(o);
                }
            }
        }

        void removeReq(IsoObject o) {
            if (real) {
                cell.addToProcessIsoObjectRemove(o);
            } else if (set.contains(o)) {
                remove.add(o);
            }
        }

        /** 原版 ProcessIsoObject（javap：removeAll 三步 → 快取 size → get／null 略過／update → 清單變短時 n--、size--）。 */
        void process() throws Exception {
            if (real) {
                processMethod.invoke(cell);
                return;
            }
            if (!remove.isEmpty()) {
                list.removeAll(remove);
                set.removeAll(remove);
                remove.clear();
            }
            int size = list.size();
            for (int n = 0; n < size; n++) {
                IsoObject o = list.get(n);
                if (o == null) {
                    continue;
                }
                o.update();
                if (size > list.size()) {
                    n--;
                    size--;
                }
            }
        }

        Rec rec(int id) {
            Rec r = raw(Rec.class);
            r.w = this;
            r.id = id;
            byId.put(id, r);
            idOf.put(r, id);
            return r;
        }

        IsoThumpable thump(int id, float life) throws Exception {
            IsoThumpable t = raw(IsoThumpable.class);
            setField(IsoThumpable.class, t, "lifeLeft", life);
            IsoGridSquare sq = raw(IsoGridSquare.class);
            setField(IsoGridSquare.class, sq, "objects", new RecList(this));
            sq.chunk = raw(IsoChunk.class);
            sq.getObjects().add(t);
            t.square = sq;
            byId.put(id, t);
            idOf.put(t, id);
            return t;
        }

        List<Integer> ids() {
            List<Integer> out = new ArrayList<>();
            for (IsoObject o : list) {
                out.add(o == null ? -1 : idOf.get(o));
            }
            return out;
        }
    }

    interface Op {
        void run(World w) throws Exception;
    }

    private static void both(World a, World b, Op op) throws Exception {
        op.run(a);
        op.run(b);
    }

    private static void offThread(Runnable r) throws InterruptedException {
        Thread t = new Thread(r, "LoadChunk");
        t.start();
        t.join();
    }

    private static boolean lit(IsoObject o) {
        return o instanceof IsoThumpable t && t.getLifeLeft() > -1f;
    }

    /** 第一個 index ≥ from、不是最後一件的可跳過牆。 */
    private static int wallAfter(World w, int from) {
        for (int i = Math.max(0, from); i < w.list.size() - 1; i++) {
            IsoObject o = w.list.get(i);
            if (o != null && o.getClass() == IsoThumpable.class && !lit(o)) {
                return w.idOf.get(o);
            }
        }
        throw new IllegalStateException("找不到可點亮的牆");
    }

    private static List<Integer> moveToEnd(List<Integer> xs, int id) {
        List<Integer> out = new ArrayList<>(xs);
        if (out.remove((Integer) id)) {
            out.add(id);
        }
        return out;
    }

    private static List<Integer> minus(List<Integer> xs, int id) {
        List<Integer> out = new ArrayList<>(xs);
        out.remove((Integer) id);
        return out;
    }

    interface Expect {
        /** phase：0＝還沒遇到完整輪、1＝完整輪那一輪、2＝完整輪之後一輪；i＝第幾輪（0 起）。 */
        boolean ok(List<Integer> a, List<Integer> b, int phase, int i);
    }

    /** 跑到出現完整輪、再多跑一輪；每輪清單內容順序必須相同、update 序列符合 Expect。 */
    private static String untilFull(World a, World b, Expect e) throws Exception {
        int phase = 0;
        for (int i = 0; i <= IsoObjectUpdateGate.FULL_EVERY + 1; i++) {
            long f0 = IsoObjectUpdateGate.fullPassesForTest();
            a.trace.clear();
            b.trace.clear();
            a.process();
            b.process();
            if (phase == 0 && IsoObjectUpdateGate.fullPassesForTest() > f0) {
                phase = 1;
            } else if (phase == 1) {
                phase = 2;
            }
            if (!a.ids().equals(b.ids()) || !e.ok(a.trace, b.trace, phase, i)) {
                return "第 " + i + " 輪 phase=" + phase + " A=" + a.trace + " B=" + b.trace;
            }
            if (phase == 2) {
                return null;
            }
        }
        return "沒有遇到完整輪";
    }

    private static void activeList(int al) throws Exception {
        GameServer.server = true;
        processMethod = IsoCell.class.getDeclaredMethod("ProcessIsoObject");
        processMethod.setAccessible(true);
        boolean on = al == IsoObjectUpdateGate.ON, observe = al == IsoObjectUpdateGate.OBSERVE;
        World a = new World(true), b = new World(false);
        int[] next = {1};
        for (int i = 0; i < 60; i++) {
            int id = next[0]++;
            int kind = i % 6;
            boolean lamp = kind == 5 && i % 12 == 5;
            both(a, b, w -> w.add(kind == 4 ? w.rec(id) : w.thump(id, lamp ? 5f : -1f)));
        }

        // 主腳本：每輪最多一個操作，兩個世界做同一件事，接著各跑一輪。
        Random rnd = new Random(73);
        int rounds = 1300, bad = 0;
        String firstBad = null;
        List<Integer> fullRounds = new ArrayList<>();
        long resync0 = IsoObjectUpdateGate.resyncsForTest();
        boolean dirtyFull = false, resyncFull = true;
        for (int r = 0; r < rounds; r++) {
            String op = switch (r) {
                case 50 -> "addWall";
                case 51 -> "lightTail";
                case 100 -> "offThreadAdd";
                case 200 -> "bypassAdd";
                case 201 -> "bypassNull";
                case 300 -> "spawn";
                case 301 -> "removeMidPass";
                default -> rnd.nextInt(10) < 3
                        ? List.of("addRec", "addWall", "addLamp", "remove", "remove", "lightTail", "burn", "spawn", "removeMidPass")
                                .get(rnd.nextInt(9))
                        : "none";
            };
            int id = next[0]++;
            List<IsoObject> alive = new ArrayList<>();
            for (IsoObject o : b.list) {
                if (o != null && !b.remove.contains(o)) alive.add(o);
            }
            IsoObject pick = alive.isEmpty() ? null : alive.get(rnd.nextInt(alive.size()));
            int pickId = pick == null ? -1 : b.idOf.get(pick);
            switch (op) {
                case "addRec" -> both(a, b, w -> w.add(w.rec(id)));
                case "addWall" -> both(a, b, w -> w.add(w.thump(id, -1f)));
                case "addLamp" -> both(a, b, w -> w.add(w.thump(id, 5f)));
                case "remove" -> {
                    if (pick != null) both(a, b, w -> w.removeReq(w.byId.get(pickId)));
                }
                case "lightTail" -> {
                    // 尾端的牆被別的執行緒點亮：排進尾端與原版順序相同（非尾端的差別在下面另驗）。
                    IsoObject tail = b.list.isEmpty() ? null : b.list.get(b.list.size() - 1);
                    if (tail != null && tail.getClass() == IsoThumpable.class && !lit(tail)) {
                        int tid = b.idOf.get(tail);
                        both(a, b, w -> offThread(() -> ((IsoThumpable) w.byId.get(tid)).setLifeLeft(5f)));
                    }
                }
                case "burn" -> {
                    if (lit(pick)) both(a, b, w -> ((IsoThumpable) w.byId.get(pickId)).setLifeLeft(-1f));
                }
                case "offThreadAdd" -> both(a, b, w -> {
                    Rec rec = w.rec(id);
                    offThread(() -> w.add(rec));
                });
                case "bypassAdd" -> both(a, b, w -> w.list.add(w.rec(id)));
                case "bypassNull" -> both(a, b, w -> w.list.add(null));
                case "spawn" -> {
                    if (pick instanceof Rec) both(a, b, w -> {
                        Rec spawned = w.rec(id);
                        ((Rec) w.byId.get(pickId)).onUpdate = () -> w.add(spawned);
                    });
                }
                case "removeMidPass" -> {
                    IsoObject target = alive.isEmpty() ? null : alive.get(rnd.nextInt(alive.size()));
                    if (pick instanceof Rec && target != null) {
                        int targetId = b.idOf.get(target);
                        both(a, b, w -> ((Rec) w.byId.get(pickId)).onUpdate = () -> w.removeReq(w.byId.get(targetId)));
                    }
                }
                default -> { }
            }
            long f0 = IsoObjectUpdateGate.fullPassesForTest();
            a.trace.clear();
            b.trace.clear();
            a.process();
            b.process();
            boolean full = IsoObjectUpdateGate.fullPassesForTest() > f0;
            if (full) fullRounds.add(r);
            if (r == 100) dirtyFull = full;
            if (r == 200 || r == 201) resyncFull &= full;
            if (!a.trace.equals(b.trace) || !a.ids().equals(b.ids())) {
                bad++;
                if (firstBad == null) firstBad = "第 " + r + " 輪 op=" + op + " A=" + a.trace + " B=" + b.trace;
            }
        }
        long full = IsoObjectUpdateGate.fullPassesForTest(), sub = IsoObjectUpdateGate.subPassesForTest();
        long resyncs = IsoObjectUpdateGate.resyncsForTest() - resync0;
        String counters = " full=" + full + " sub=" + sub + " resyncs=" + resyncs + " mismatches=" + IsoObjectUpdateGate.mismatchesForTest()
                + " lifeQueued=" + IsoObjectUpdateGate.lifeQueuedForTest() + " lifeAdded=" + IsoObjectUpdateGate.lifeAddedForTest()
                + " active=" + IsoObjectUpdateGate.activeForTest().size() + " 清單=" + a.list.size();
        System.out.println("  " + IsoObjectUpdateGate.alName() + counters + (firstBad == null ? "" : " firstBad=" + firstBad));
        expect("第二刀 " + IsoObjectUpdateGate.alName() + "：" + rounds + " 輪腳本，每輪有作用的 update 序列與原版逐輪相同、getProcessIsoObjects() 內容與順序相同（不同 " + bad + " 輪）",
                bad == 0);
        if (!on && !observe) {
            expect("第二刀 off：不建子清單、不計數、點燈不入列" + counters,
                    full == 0 && sub == 0 && resyncs == 0 && IsoObjectUpdateGate.lifeQueuedForTest() == 0
                    && IsoObjectUpdateGate.activeForTest().isEmpty());
            return;
        }
        boolean periodic = false;
        for (int i = 1; i < fullRounds.size(); i++) {
            periodic |= fullRounds.get(i) - fullRounds.get(i - 1) == IsoObjectUpdateGate.FULL_EVERY;
        }
        if (on) {
            expect("第二刀 on：平常走子清單、每 " + IsoObjectUpdateGate.FULL_EVERY + " 輪一次完整輪、非主執行緒 add 與繞過掛點改清單的下一輪走完整輪、別的執行緒點燈加進子清單、比對一致不停用"
                            + counters,
                    sub > 1000 && periodic && dirtyFull && resyncFull && resyncs == 2 && IsoObjectUpdateGate.lifeAddedForTest() >= 1
                    && IsoObjectUpdateGate.mismatchesForTest() == 0 && !IsoObjectUpdateGate.alDisabledForTest());
        } else {
            expect("第二刀 observe：每輪都是完整輪（交給原版迴圈的序列與 W73 相同）、每輪比對一致" + counters,
                    sub == 0 && full == rounds && resyncs == 2 && IsoObjectUpdateGate.lifeAddedForTest() >= 1
                    && IsoObjectUpdateGate.mismatchesForTest() == 0 && !IsoObjectUpdateGate.alDisabledForTest());
        }
        for (World w : new World[]{a, b}) {
            for (IsoObject o : w.byId.values()) {
                if (o instanceof Rec rec) rec.onUpdate = null;
            }
        }

        // 已知差別一：兩輪之間被點亮、不在尾端的燈——on 的子清單輪排在尾端（只差順序），完整輪後回原位；observe 完全相同。
        int mid = wallAfter(b, b.list.size() / 2);
        both(a, b, w -> offThread(() -> ((IsoThumpable) w.byId.get(mid)).setLifeLeft(5f)));
        String d1 = untilFull(a, b, (x, y, phase, i) -> x.equals(on && phase == 0 ? moveToEnd(y, mid) : y));
        expect("第二刀已知差別一（兩輪之間點亮的燈）：" + (on ? "子清單輪排在尾端、完整輪後回原位" : "observe 與原版相同") + (d1 == null ? "" : " " + d1),
                d1 == null && IsoObjectUpdateGate.mismatchesForTest() == 0);

        // 已知差別二：迴圈中途被排在前面的物件點亮的燈——on 子清單輪那一輪不 update、下一輪起在尾端；完整輪的比對不算它缺（不誤停用）。
        int lighter = -1;
        for (IsoObject o : b.list) {
            if (o instanceof Rec) {
                lighter = b.idOf.get(o);
                break;
            }
        }
        int lighterId = lighter;
        int late = wallAfter(b, b.list.indexOf(b.byId.get(lighterId)) + 1);
        both(a, b, w -> ((Rec) w.byId.get(lighterId)).onUpdate = () -> ((IsoThumpable) w.byId.get(late)).setLifeLeft(5f));
        String d2 = untilFull(a, b, (x, y, phase, i) -> x.equals(!on || phase > 0 ? y : i == 0 ? minus(y, late) : moveToEnd(y, late)));
        expect("第二刀已知差別二（迴圈中途點亮的燈）：" + (on ? "當輪不 update、下一輪起在尾端、完整輪後回原位" : "observe 與原版相同")
                        + "，比對不誤判（mismatches=" + IsoObjectUpdateGate.mismatchesForTest() + "）" + (d2 == null ? "" : " " + d2),
                d2 == null && IsoObjectUpdateGate.mismatchesForTest() == 0 && !IsoObjectUpdateGate.alDisabledForTest());

        // 心跳欄位（計時幀一定是完整輪）。普查會呼叫 getObjectIndex，這一輪只比清單、不比 update 序列。
        long f0 = IsoObjectUpdateGate.fullPassesForTest();
        IsoObjectUpdateGate.forceTimedFrameForTest();
        IsoObjectUpdateGate.lastBeatLine = null;
        a.process();
        b.process();
        String beat = IsoObjectUpdateGate.lastBeatLine;
        System.out.println("  beat: " + beat);
        expect("第二刀心跳：計時幀走完整輪，帶 activeList 模式與子清單計數",
                beat != null && IsoObjectUpdateGate.fullPassesForTest() == f0 + 1 && a.ids().equals(b.ids())
                && beat.contains(" activeList=" + (on ? "on" : "observe") + " active=" + IsoObjectUpdateGate.activeForTest().size() + " fullPasses=")
                && beat.contains(" mismatches=0 resyncs=2 aborted=0 lifeQueued=") && beat.contains(" lifeAdded="));

        if (on) {
            // 完整輪被例外打斷：中途經 add 掛點加進來的物件只算進預期長度、沒經輪尾補進子清單，下一輪必須重建。
            int spawner = firstRec(b, false), thrower = next[0]++, born = next[0]++;
            both(a, b, w -> {
                Rec x = w.rec(thrower);
                x.onUpdate = () -> { throw new Boom(); };
                w.list.add(x);   // 繞過掛點 → 這一輪 resync 走完整輪；它排在最後、在 born 之前丟例外
                Rec n = w.rec(born);
                ((Rec) w.byId.get(spawner)).onUpdate = () -> w.add(n);
            });
            long full0 = IsoObjectUpdateGate.fullPassesForTest(), ab0 = IsoObjectUpdateGate.abortedForTest();
            boolean abortFull = abortedRound(a, b) && IsoObjectUpdateGate.fullPassesForTest() == full0 + 1;
            String e1 = null;
            List<Integer> fulls = new ArrayList<>();
            for (int i = 0; i <= IsoObjectUpdateGate.FULL_EVERY + 1 && e1 == null; i++) {
                long f1 = IsoObjectUpdateGate.fullPassesForTest();
                a.trace.clear();
                b.trace.clear();
                a.process();
                b.process();
                if (IsoObjectUpdateGate.fullPassesForTest() > f1) fulls.add(i);
                if (!a.trace.equals(b.trace) || !a.ids().equals(b.ids()) || !a.trace.contains(born)) {
                    e1 = "第 " + i + " 輪 A=" + a.trace + " B=" + b.trace;
                }
            }
            expect("第二刀 on 完整輪被例外打斷：下一輪走完整輪重建，中途加入的物件之後每輪都 update、到下一個定期完整輪都與原版相同、比對不誤判"
                            + " fulls=" + fulls + (e1 == null ? "" : " " + e1),
                    abortFull && e1 == null && fulls.size() == 2 && fulls.get(0) == 0 && fulls.get(1) == IsoObjectUpdateGate.FULL_EVERY
                    && IsoObjectUpdateGate.abortedForTest() == ab0 + 1 && IsoObjectUpdateGate.mismatchesForTest() == 0
                    && !IsoObjectUpdateGate.alDisabledForTest());

            // 子清單輪被例外打斷：下一輪走完整輪，序列與原版相同。
            int spawner2 = firstRec(b, false), thrower2 = firstRec(b, true), born2 = next[0]++;
            both(a, b, w -> {
                Rec n = w.rec(born2);
                ((Rec) w.byId.get(spawner2)).onUpdate = () -> w.add(n);
                ((Rec) w.byId.get(thrower2)).onUpdate = () -> { throw new Boom(); };
            });
            long sub1 = IsoObjectUpdateGate.subPassesForTest(), ab1 = IsoObjectUpdateGate.abortedForTest();
            boolean abortSub = abortedRound(a, b) && IsoObjectUpdateGate.subPassesForTest() == sub1 + 1;
            String e2 = untilFull(a, b, (x, y, phase, i) -> (i > 0 || phase == 1) && x.equals(y) && x.contains(born2));
            expect("第二刀 on 子清單輪被例外打斷：下一輪走完整輪、序列與原版相同" + (e2 == null ? "" : " " + e2),
                    abortSub && spawner2 != thrower2 && e2 == null && IsoObjectUpdateGate.abortedForTest() == ab1 + 1
                    && IsoObjectUpdateGate.mismatchesForTest() == 0 && !IsoObjectUpdateGate.alDisabledForTest());
        }

        // 先熄、完整輪輪到時被跳過、同一輪後面的物件再點亮的燈：原版這輪也沒 update 它，比對不能算「多」。
        int relighter = firstRec(b, true);
        int lamp = wallAfter(b, 0);
        boolean ahead = b.list.indexOf(b.byId.get(lamp)) < b.list.indexOf(b.byId.get(relighter));
        both(a, b, w -> offThread(() -> ((IsoThumpable) w.byId.get(lamp)).setLifeLeft(5f)));
        a.process();   // on：輪首排空時加進子清單尾端；observe：完整輪重建後在原位
        b.process();
        boolean held = IsoObjectUpdateGate.activeForTest().contains(a.byId.get(lamp)) && a.ids().equals(b.ids());
        both(a, b, w -> ((IsoThumpable) w.byId.get(lamp)).setLifeLeft(-1f));
        both(a, b, w -> ((Rec) w.byId.get(relighter)).onUpdate = () -> ((IsoThumpable) w.byId.get(lamp)).setLifeLeft(5f));
        long m0 = IsoObjectUpdateGate.mismatchesForTest(), fr0 = IsoObjectUpdateGate.fullPassesForTest();
        IsoObjectUpdateGate.forceTimedFrameForTest();   // 計時幀＝有比對的完整輪（on、observe 都是）；普查會記燈，只比清單
        a.process();
        b.process();
        ArrayList<IsoObject> act = IsoObjectUpdateGate.activeForTest();
        boolean relit = IsoObjectUpdateGate.fullPassesForTest() == fr0 + 1 && a.ids().equals(b.ids())
                && !act.isEmpty() && act.get(act.size() - 1) == a.byId.get(lamp);
        String d5 = untilFull(a, b, (x, y, phase, i) -> x.equals(on && phase == 0 ? moveToEnd(y, lamp) : y));
        expect("第二刀先熄、被跳過、同一輪後面的物件再點亮的燈：比對不算多出來（mismatches=" + IsoObjectUpdateGate.mismatchesForTest()
                        + "）、之後在子清單尾端" + (on ? "、下一個完整輪後回原位" : "") + (d5 == null ? "" : " " + d5),
                ahead && held && relit && d5 == null && IsoObjectUpdateGate.mismatchesForTest() == m0
                && !IsoObjectUpdateGate.alDisabledForTest());

        // 偵測：子清單漏一件（模擬 lifeLeft 從沒掛到的地方被改）。
        Rec victimA = null;
        for (IsoObject o : a.list) {
            if (o instanceof Rec rec) victimA = rec;
        }
        int victim = victimA.id;
        IsoObjectUpdateGate.dropActiveForTest(victimA);
        boolean[] diffSeen = {false};
        String d3 = untilFull(a, b, (x, y, phase, i) -> {
            if (on && phase == 0) {
                diffSeen[0] |= !x.equals(y);
                return x.equals(minus(y, victim));
            }
            return x.equals(y);
        });
        if (on) {
            long sub0 = IsoObjectUpdateGate.subPassesForTest();
            a.trace.clear();
            b.trace.clear();
            a.process();
            b.process();
            expect("第二刀 on 漏一件：子清單輪看得到差異（負對照）、完整輪比對抓到並永久停用、放掉子清單，之後每輪完整輪且與原版相同"
                            + (d3 == null ? "" : " " + d3),
                    d3 == null && diffSeen[0] && IsoObjectUpdateGate.mismatchesForTest() == 1 && IsoObjectUpdateGate.alDisabledForTest()
                    && IsoObjectUpdateGate.alName().equals("disabled") && IsoObjectUpdateGate.subPassesForTest() == sub0
                    && IsoObjectUpdateGate.activeForTest().isEmpty() && a.trace.equals(b.trace));
        } else {
            expect("第二刀 observe 漏一件：下一輪比對計數、不停用、行為與原版相同" + (d3 == null ? "" : " " + d3),
                    d3 == null && IsoObjectUpdateGate.mismatchesForTest() == 1 && !IsoObjectUpdateGate.alDisabledForTest());
            Rec ghost = a.rec(next[0]++);   // 不在清單裡
            IsoObjectUpdateGate.injectActiveForTest(ghost);
            String d4 = untilFull(a, b, (x, y, phase, i) -> x.equals(y));
            expect("第二刀 observe 多一件：比對計數、不停用、行為與原版相同（ghost 沒被 update）" + (d4 == null ? "" : " " + d4),
                    d4 == null && IsoObjectUpdateGate.mismatchesForTest() == 2 && !IsoObjectUpdateGate.alDisabledForTest()
                    && !a.trace.contains(ghost.id));
        }
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
                setField(IsoGridSquare.class, sq, "objects", new ArrayList<IsoObject>(2));
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
