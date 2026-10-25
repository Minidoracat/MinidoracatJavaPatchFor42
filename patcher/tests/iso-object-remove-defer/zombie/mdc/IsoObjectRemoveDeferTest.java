package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import zombie.iso.IsoCell;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * W75 IsoObjectRemoveDefer 行為驗證（argv：{@code on}／{@code off}）。跑 dist 內手術後的真 {@code IsoCell.ProcessIsoObject}／
 * {@code addToProcessIsoObject}／{@code addToProcessIsoObjectRemove}，兩份 Set 換成記錄「哪條執行緒碰過」的 HashSet 子類：
 * <ul>
 *   <li>主執行緒呼叫照原版（當下進 Remove）；mainThread 為 null 時照原版。</li>
 *   <li>背景執行緒呼叫：on 不碰任何集合、排進佇列，下一次 ProcessIsoObject 頭部生效（物件當幀不 update，與原版同幀移除相同）；
 *       off（原版負對照）背景執行緒直接讀寫兩份 Set。</li>
 *   <li>「背景移除→主執行緒加回」：on 取消待移除，結果與原版循序執行相同。</li>
 *   <li>mutant（不取消、排空放在 removeAll 之後、在背景執行緒排空）各至少一個情境與原版循序結果不同。</li>
 * </ul>
 */
public final class IsoObjectRemoveDeferTest {

    private static int failed;
    private static Thread main;
    private static Method process;

    /** 記錄是否有主執行緒以外的執行緒碰過這份集合。 */
    public static final class Recording<E> extends HashSet<E> {
        volatile boolean offMain;

        private void touch() {
            if (Thread.currentThread() != main) {
                offMain = true;
            }
        }

        @Override public boolean contains(Object o) { touch(); return super.contains(o); }
        @Override public boolean add(E e) { touch(); return super.add(e); }
        @Override public boolean remove(Object o) { touch(); return super.remove(o); }
        @Override public boolean isEmpty() { touch(); return super.isEmpty(); }
        @Override public boolean removeAll(Collection<?> c) { touch(); return super.removeAll(c); }
        @Override public void clear() { touch(); super.clear(); }
    }

    public static class Probe extends IsoObject {
        int updates;

        @Override
        public void update() {
            updates++;
        }
    }

    /** 一個 cell 加上它的兩份記錄集合。 */
    record World(IsoCell cell, ArrayList<IsoObject> list, Recording<IsoObject> set, Recording<IsoObject> remove) {
        boolean offMain() { return set.offMain || remove.offMain; }
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String arg = args.length > 0 ? args[0] : "";
        boolean on = switch (arg) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("argv 必須是 on|off：" + arg);
        };
        expect("旗標與 argv 相符：argv=" + arg + " ENABLED=" + IsoObjectRemoveDefer.ENABLED + " raw=" + IsoObjectRemoveDefer.RAW
                + "（W73 isoObjectGate=" + IsoObjectUpdateGate.modeName() + "）", IsoObjectRemoveDefer.ENABLED == on);
        expect("開關解析：null→on、0/off→off、其他→on", IsoObjectRemoveDefer.parse(null) && !IsoObjectRemoveDefer.parse("0")
                && !IsoObjectRemoveDefer.parse(" OFF ") && IsoObjectRemoveDefer.parse("1") && IsoObjectRemoveDefer.parse("bogus"));
        main = Thread.currentThread();
        process = IsoCell.class.getDeclaredMethod("ProcessIsoObject");
        process.setAccessible(true);

        // mainThread 還是 null：照原版（背景執行緒也直接寫）。
        GameServer.mainThread = null;
        World w0 = world();
        Probe p0 = add(w0);
        bg(() -> IsoObjectRemoveDefer.addToProcessIsoObjectRemove(w0.cell(), p0));
        expect("mainThread 為 null：照原版直接寫 Remove", w0.remove().contains(p0) && IsoObjectRemoveDefer.pendingForTest() == 0);
        GameServer.mainThread = main;

        // 主執行緒呼叫照原版。
        World w1 = world();
        Probe p1 = add(w1);
        IsoObjectRemoveDefer.addToProcessIsoObjectRemove(w1.cell(), p1);
        expect("主執行緒呼叫照原版：當下進 Remove、不排隊", w1.remove().contains(p1) && IsoObjectRemoveDefer.pendingForTest() == 0);

        // 情境 A：背景移除，下一輪生效。
        World oracleA = world();
        Probe oa = add(oracleA), ka = add(oracleA);
        oracleA.cell().addToProcessIsoObjectRemove(oa);   // 原版循序：同一呼叫在主執行緒執行
        frame(oracleA);
        World wa = world();
        Probe a = add(wa), keep = add(wa);
        long d0 = IsoObjectRemoveDefer.deferredForTest();
        bg(() -> IsoObjectRemoveDefer.addToProcessIsoObjectRemove(wa.cell(), a));
        if (on) {
            expect("on：背景執行緒不碰兩份 Set、排進佇列", !wa.offMain() && wa.remove().isEmpty()
                    && IsoObjectRemoveDefer.pendingForTest() == 1 && IsoObjectRemoveDefer.deferredForTest() == d0 + 1);
        } else {
            expect("off（原版負對照）：背景執行緒直接讀寫 Set", wa.offMain() && wa.remove().contains(a)
                    && IsoObjectRemoveDefer.pendingForTest() == 0);
        }
        frame(wa);
        expect("情境 A：下一次 ProcessIsoObject 移除且當幀不 update，與原版循序結果相同（a.updates=" + a.updates + "）",
                same(oracleA, wa) && a.updates == oa.updates && a.updates == 0 && keep.updates == ka.updates
                && IsoObjectRemoveDefer.pendingForTest() == 0);

        // 情境 B：背景移除→主執行緒加回。
        World oracleB = world();
        Probe ob = add(oracleB);
        oracleB.cell().addToProcessIsoObjectRemove(ob);
        oracleB.cell().addToProcessIsoObject(ob);
        frame(oracleB);
        World wb = world();
        Probe b = add(wb);
        long c0 = IsoObjectRemoveDefer.cancelledForTest();
        bg(() -> IsoObjectRemoveDefer.addToProcessIsoObjectRemove(wb.cell(), b));
        wb.cell().addToProcessIsoObject(b);
        frame(wb);
        expect("情境 B：背景移除後主執行緒加回，物件留在清單且照常 update，與原版循序結果相同",
                same(oracleB, wb) && wb.list().contains(b) && b.updates == 1 && ob.updates == 1);
        if (on) {
            expect("on：取消計數 +1、佇列清空", IsoObjectRemoveDefer.cancelledForTest() == c0 + 1 && IsoObjectRemoveDefer.pendingForTest() == 0);
            // 取消只針對同一 cell＋物件。
            World wc = world(), wd = world();
            Probe c = add(wc), dd = add(wd);
            bg(() -> {
                IsoObjectRemoveDefer.addToProcessIsoObjectRemove(wc.cell(), c);
                IsoObjectRemoveDefer.addToProcessIsoObjectRemove(wd.cell(), dd);
            });
            wd.cell().addToProcessIsoObject(c);   // 別的 cell 加同一物件：不取消 wc 的待移除
            expect("取消只比對同一 cell＋物件", IsoObjectRemoveDefer.pendingForTest() == 2);
            frame(wc);
            expect("排空處理所有 cell 的待移除（各自呼叫原版）", !wc.list().contains(c) && wd.remove().contains(dd)
                    && IsoObjectRemoveDefer.pendingForTest() == 0);
            IsoObjectRemoveDefer.beatForTest();
            String beat = IsoObjectRemoveDefer.lastBeatLine;
            System.out.println("  beat: " + beat);
            expect("心跳欄位：deferred／drained／cancelled／pending／maxPending／overflow／anomalies／sources（含 via）",
                    beat.startsWith("beat deferred=4 drained=3 cancelled=1 pending=0 maxPending=2 overflow=0 anomalies=0 sources=[")
                    && beat.contains("×"));
        } else {
            expect("off：不排隊、不計數", IsoObjectRemoveDefer.deferredForTest() == 0 && IsoObjectRemoveDefer.cancelledForTest() == 0);
        }

        mutants(oracleA, oracleB);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("IsoObjectRemoveDeferTest 全數通過（" + arg + "）");
    }

    /** 三個 mutant 以真 IsoCell 加上測試自己的佇列模擬；各自至少一個情境與原版循序結果不同。 */
    private static void mutants(World oracleA, World oracleB) throws Exception {
        // 不取消：背景移除排隊，主執行緒加回（不取消），下一幀頭部排空。
        World m1 = world();
        Probe x1 = add(m1);
        List<IsoObject> q1 = new ArrayList<>();
        bg(() -> q1.add(x1));
        m1.cell().addToProcessIsoObject(x1);
        q1.forEach(m1.cell()::addToProcessIsoObjectRemove);
        frame(m1);
        expect("mutant 被抓到：不取消（背景移除→加回後物件被移除，原版留著）", !same(oracleB, m1));

        // 排空放在 removeAll 之後：該幀照樣 update 且物件仍在清單。
        World m2 = world();
        Probe x2 = add(m2);
        add(m2);
        List<IsoObject> q2 = new ArrayList<>();
        bg(() -> q2.add(x2));
        frame(m2);
        q2.forEach(m2.cell()::addToProcessIsoObjectRemove);
        expect("mutant 被抓到：排空放在 removeAll 之後（當幀多 update 一次、物件仍在清單）",
                !same(oracleA, m2) && x2.updates == 1);

        // 在背景執行緒排空：等於背景執行緒直接呼叫原版，Set 被背景執行緒碰到。
        World m3 = world();
        Probe x3 = add(m3);
        bg(() -> m3.cell().addToProcessIsoObjectRemove(x3));
        expect("mutant 被抓到：在背景執行緒排空（背景執行緒讀寫兩份 Set）", m3.offMain());
    }

    // ---- 工具 ----

    private static World world() throws Exception {
        IsoCell cell = raw(IsoCell.class);
        ArrayList<IsoObject> list = new ArrayList<>();
        Recording<IsoObject> set = new Recording<>(), remove = new Recording<>();
        setField(cell, "processIsoObject", list);
        setField(cell, "processIsoObjectSet", set);
        setField(cell, "processIsoObjectRemove", remove);
        return new World(cell, list, set, remove);
    }

    private static Probe add(World w) {
        Probe p = raw(Probe.class);
        w.cell().addToProcessIsoObject(p);
        return p;
    }

    private static void frame(World w) throws Exception {
        process.invoke(w.cell());
    }

    /** 清單長度與兩份集合大小相同（兩個世界的物件不同，比結構）。 */
    private static boolean same(World a, World b) {
        return a.list().size() == b.list().size() && a.set().size() == b.set().size() && a.remove().size() == b.remove().size();
    }

    private static void bg(Runnable r) throws InterruptedException {
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "ServerPlayersVehicles");
        t.start();
        t.join();
        if (err[0] != null) {
            throw new IllegalStateException(err[0]);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = IsoCell.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
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
        System.out.println((ok ? "rmd pass  " : "rmd FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private IsoObjectRemoveDeferTest() {}
}
