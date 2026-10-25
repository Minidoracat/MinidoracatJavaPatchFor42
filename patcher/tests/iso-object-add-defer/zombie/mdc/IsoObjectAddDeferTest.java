package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;

import zombie.iso.IsoButcherHook;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * W75-2 行為驗證（argv：{@code on}／{@code off}／{@code on-removeoff}）。走 dist 內手術後的真 {@code IsoButcherHook(IsoCell)}
 * 建構子（改道後的 addToProcessIsoObject）與真 {@code IsoCell.ProcessIsoObject}／{@code addToProcessIsoObject}／
 * {@code addToProcessIsoObjectRemove}；兩份 Set 換成記錄執行緒的 HashSet 子類，清單換成記錄每件 update 次數的 ArrayList。
 * 每個情境另以原版語意在主執行緒依呼叫時間循序跑一份（oracle），比對清單、兩份 Set 與 update 次數。
 */
public final class IsoObjectAddDeferTest {

    private static int failed;
    private static Thread main;
    private static Method process;

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

    /** 清單的 get(n) 會被 ProcessIsoObject 用來取出要 update 的物件；記錄每個物件被取出的次數（＝被 update 的次數）。 */
    public static final class CountingList extends ArrayList<IsoObject> {
        final java.util.IdentityHashMap<IsoObject, Integer> gets = new java.util.IdentityHashMap<>();

        @Override
        public IsoObject get(int index) {
            IsoObject o = super.get(index);
            gets.merge(o, 1, Integer::sum);
            return o;
        }

        int updates(IsoObject o) {
            return gets.getOrDefault(o, 0);
        }
    }

    record World(IsoCell cell, CountingList list, Recording<IsoObject> set, Recording<IsoObject> remove) {
        boolean offMain() { return set.offMain || remove.offMain; }
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        GameServer.server = true;
        String arg = args.length > 0 ? args[0] : "";
        boolean addOn = !arg.equals("off");
        boolean removeOn = !arg.equals("on-removeoff");
        if (!List.of("on", "off", "on-removeoff").contains(arg)) {
            throw new IllegalArgumentException("argv 必須是 on|off|on-removeoff：" + arg);
        }
        expect("旗標與 argv 相符：argv=" + arg + " add=" + IsoObjectRemoveDefer.ADD_ENABLED + " remove=" + IsoObjectRemoveDefer.ENABLED
                        + " raw=" + IsoObjectRemoveDefer.ADD_RAW + "/" + IsoObjectRemoveDefer.RAW + "（W73 isoObjectGate=" + IsoObjectUpdateGate.modeName() + "）",
                IsoObjectRemoveDefer.ADD_ENABLED == addOn && IsoObjectRemoveDefer.ENABLED == removeOn);
        main = Thread.currentThread();
        process = IsoCell.class.getDeclaredMethod("ProcessIsoObject");
        process.setAccessible(true);

        // mainThread 為 null：照原版（背景建構也直接寫）。
        GameServer.mainThread = null;
        World w0 = world();
        IsoButcherHook[] h0 = new IsoButcherHook[1];
        bg(() -> h0[0] = new IsoButcherHook(w0.cell()));
        expect("mainThread 為 null：照原版直接加入", w0.set().contains(h0[0]) && w0.list().contains(h0[0]) && IsoObjectRemoveDefer.pendingForTest() == 0);
        GameServer.mainThread = main;

        // 主執行緒建構照原版。
        World w1 = world();
        IsoButcherHook h1 = new IsoButcherHook(w1.cell());
        expect("主執行緒建構照原版：當下加入、不排隊", w1.list().contains(h1) && IsoObjectRemoveDefer.pendingForTest() == 0);

        // 情境 1：背景加入，下一輪生效並被更新。
        World o1 = world();
        IsoButcherHook x1 = new IsoButcherHook(o1.cell());
        frame(o1);
        World s1 = world();
        IsoButcherHook[] y1 = new IsoButcherHook[1];
        long ad0 = IsoObjectRemoveDefer.addDeferredForTest();
        bg(() -> y1[0] = new IsoButcherHook(s1.cell()));
        if (addOn) {
            expect("on：背景建構不碰集合與清單、排進佇列", !s1.offMain() && s1.list().isEmpty()
                    && IsoObjectRemoveDefer.pendingForTest() == 1 && IsoObjectRemoveDefer.addDeferredForTest() == ad0 + 1);
        } else {
            expect("off（原版負對照）：背景執行緒直接讀寫 Set 與清單", s1.offMain() && s1.list().contains(y1[0])
                    && IsoObjectRemoveDefer.addDeferredForTest() == 0);
        }
        frame(s1);
        expect("情境 1：背景加入，下一次 ProcessIsoObject 在清單且被 update 一次（與原版循序相同）",
                same(o1, s1) && s1.list().updates(y1[0]) == 1 && o1.list().updates(x1) == 1 && IsoObjectRemoveDefer.pendingForTest() == 0);

        // 情境 2：背景「先加後移」：排空後不在清單、也不被 update。
        World o2 = world();
        IsoButcherHook x2 = new IsoButcherHook(o2.cell());
        o2.cell().addToProcessIsoObjectRemove(x2);
        frame(o2);
        World s2 = world();
        IsoButcherHook[] y2 = new IsoButcherHook[1];
        bg(() -> {
            y2[0] = new IsoButcherHook(s2.cell());
            IsoObjectRemoveDefer.addToProcessIsoObjectRemove(s2.cell(), y2[0]);
        });
        frame(s2);
        expect("情境 2：背景先加後移，排空後不在清單、不被 update（與原版循序相同；排空時不取消）",
                same(o2, s2) && !s2.list().contains(y2[0]) && s2.list().updates(y2[0]) == 0 && o2.list().updates(x2) == 0);

        // 情境 3：背景移除後主執行緒加入（W75 原案）。
        World o3 = world();
        IsoButcherHook x3 = new IsoButcherHook(o3.cell());
        o3.cell().addToProcessIsoObjectRemove(x3);
        o3.cell().addToProcessIsoObject(x3);
        frame(o3);
        World s3 = world();
        IsoButcherHook y3 = new IsoButcherHook(s3.cell());
        bg(() -> IsoObjectRemoveDefer.addToProcessIsoObjectRemove(s3.cell(), y3));
        s3.cell().addToProcessIsoObject(y3);
        frame(s3);
        expect("情境 3：背景移除後主執行緒加入，留在清單並被 update（與原版循序相同）",
                same(o3, s3) && s3.list().contains(y3) && s3.list().updates(y3) == 1 && o3.list().updates(x3) == 1);

        // 情境 4：背景加入後主執行緒移除（新取消）。
        World o4 = world();
        IsoButcherHook x4 = new IsoButcherHook(o4.cell());
        o4.cell().addToProcessIsoObjectRemove(x4);
        frame(o4);
        World s4 = world();
        IsoButcherHook[] y4 = new IsoButcherHook[1];
        long ac0 = IsoObjectRemoveDefer.addCancelledForTest();
        bg(() -> y4[0] = new IsoButcherHook(s4.cell()));
        IsoObjectRemoveDefer.addToProcessIsoObjectRemove(s4.cell(), y4[0]);   // 主執行緒：removeFromWorld 改道的主執行緒分支
        frame(s4);
        expect("情境 4：背景加入後主執行緒移除，不在清單、不被 update（與原版循序相同）",
                same(o4, s4) && !s4.list().contains(y4[0]) && s4.list().updates(y4[0]) == 0 && o4.list().updates(x4) == 0);
        if (addOn) {
            expect("on：主執行緒移除取消待加入 +1、佇列清空", IsoObjectRemoveDefer.addCancelledForTest() == ac0 + 1
                    && IsoObjectRemoveDefer.pendingForTest() == 0);
        }

        // 取消只比對同一 cell＋物件；兩個 cell 共用一條佇列各自排空。
        if (addOn) {
            World a = world(), b = world();
            IsoButcherHook[] ha = new IsoButcherHook[1];
            bg(() -> ha[0] = new IsoButcherHook(a.cell()));
            IsoObjectRemoveDefer.addToProcessIsoObjectRemove(b.cell(), ha[0]);   // 別的 cell：不取消
            expect("取消只比對同一 cell＋物件", IsoObjectRemoveDefer.pendingForTest() == 1);
            frame(b);
            expect("排空處理所有 cell（a 的待加入在 b 的那一輪生效）", a.list().contains(ha[0]) && IsoObjectRemoveDefer.pendingForTest() == 0);
            IsoObjectRemoveDefer.beatForTest();
            String beat = IsoObjectRemoveDefer.lastBeatLine;
            System.out.println("  beat: " + beat);
            expect("心跳：W75 欄位不變＋add=deferred/drained/cancelled/overflow＋addSources",
                    beat.startsWith("beat deferred=") && beat.contains(" pending=0 ") && beat.contains(" anomalies=0 ")
                    // W75 off 時情境 2 的背景移除直接跑原版，先取消待加入（cancelled 多 1、drained 少 1）
                    && beat.contains(removeOn ? " add=deferred:4,drained:3,cancelled:1,overflow:0 addSources=["
                            : " add=deferred:4,drained:2,cancelled:2,overflow:0 addSources=[")
                    && beat.contains("IsoButcherHook.<init>"));
        } else {
            expect("off：不排隊、不計數", IsoObjectRemoveDefer.addDeferredForTest() == 0 && IsoObjectRemoveDefer.addCancelledForTest() == 0);
        }

        mutants(o2, o4);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("IsoObjectAddDeferTest 全數通過（" + arg + "）");
    }

    /** mutant：測試自己的佇列＋真 IsoCell 原版方法，模擬三種錯誤寫法；各自至少一個情境與原版循序結果不同。 */
    private static void mutants(World oracle2, World oracle4) throws Exception {
        // 排空時不跳過取消：ADD 會取消後面同物件的 REMOVE。
        World m1 = world();
        IsoButcherHook z1 = hookOn(m1);
        Deque<Object[]> q1 = new ArrayDeque<>(List.of(new Object[]{true, z1}, new Object[]{false, z1}));
        while (!q1.isEmpty()) {
            Object[] e = q1.poll();
            if ((boolean) e[0]) {
                q1.removeIf(r -> !(boolean) r[0] && r[1] == e[1]);
                m1.cell().addToProcessIsoObject((IsoObject) e[1]);
            } else {
                m1.cell().addToProcessIsoObjectRemove((IsoObject) e[1]);
            }
        }
        frame(m1);
        expect("mutant 被抓到：排空時不跳過取消（背景先加後移→留在清單並被 update）", !same(oracle2, m1) && m1.list().updates(z1) == 1);

        // 主執行緒移除不取消待加入。
        World m2 = world();
        IsoButcherHook z2 = hookOn(m2);
        m2.cell().addToProcessIsoObjectRemove(z2);   // 主執行緒直接移除（不取消）
        m2.cell().addToProcessIsoObject(z2);         // 之後排空待加入
        frame(m2);
        expect("mutant 被抓到：主執行緒移除不取消待加入（背景加入後主執行緒移除→留在清單）", !same(oracle4, m2) && m2.list().updates(z2) == 1);

        // ADD／REMOVE 分兩條佇列：先排 REMOVE 再排 ADD（背景先加後移時錯）。
        World m3 = world();
        IsoButcherHook z3 = hookOn(m3);
        m3.cell().addToProcessIsoObjectRemove(z3);
        m3.cell().addToProcessIsoObject(z3);
        frame(m3);
        expect("mutant 被抓到：兩條佇列先 REMOVE 後 ADD（背景先加後移→留在清單）", !same(oracle2, m3));
        // 先排 ADD 再排 REMOVE（背景先移後加時錯；原版循序：留在清單）。
        World o5 = world();
        IsoButcherHook x5 = new IsoButcherHook(o5.cell());
        frame(o5);
        o5.cell().addToProcessIsoObjectRemove(x5);
        o5.cell().addToProcessIsoObject(x5);
        frame(o5);
        World m4 = world();
        IsoButcherHook z4 = new IsoButcherHook(m4.cell());
        frame(m4);
        m4.cell().addToProcessIsoObject(z4);         // 兩條佇列：ADD 先
        m4.cell().addToProcessIsoObjectRemove(z4);   // 再 REMOVE
        frame(m4);
        expect("mutant 被抓到：兩條佇列先 ADD 後 REMOVE（背景先移後加→被移除，原版留著）", !same(o5, m4) && o5.list().contains(x5));
    }

    /** 不經清單建構一個 hook（mutant 用：先建好，再由測試自己的佇列呼叫原版）。 */
    private static IsoButcherHook hookOn(World w) {
        return new IsoButcherHook((IsoCell) null);   // cell 為 null 時原版建構子不加入
    }

    // ---- 工具 ----

    private static World world() throws Exception {
        IsoCell cell = raw(IsoCell.class);
        CountingList list = new CountingList();
        Recording<IsoObject> set = new Recording<>(), remove = new Recording<>();
        setField(cell, "processIsoObject", list);
        setField(cell, "processIsoObjectSet", set);
        setField(cell, "processIsoObjectRemove", remove);
        return new World(cell, list, set, remove);
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
        }, "LoadChunk");
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
        System.out.println((ok ? "add pass  " : "add FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private IsoObjectAddDeferTest() {}
}
