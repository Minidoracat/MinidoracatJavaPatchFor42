package zombie.mdc;

import se.krka.kahlua.vm.KahluaThread;

/**
 * patched {@code se.krka.kahlua.integration.LuaCaller} 的採樣轉接層（core module）。
 *
 * 只有 LuaCaller 內部那 15 個對 {@code KahluaThread.pcall/pcallvoid/pcallBoolean} 的
 * 直接呼叫被改寫成呼叫這裡的同形 static 方法：原本 {@code invokevirtual} 的 receiver
 * 直接變成第一個參數（receiver 前置），其餘參數與描述子完全不變，因此 ASM 手術
 * 只是把 opcode 換成 {@code invokestatic}、owner 換成本類，不動堆疊佈局。
 *
 * 15 個站點對應到下列九個 overload（含 42.20.4 真 jar 的實際使用計數）：
 * <pre>
 *   pcall(t, fn, Object[])            ← LuaCaller.pcall(Object...) / pcall(Object)         2 站
 *   pcallvoid(t, fn, Object)          ← pcallvoid(arg) / protectedCallVoid(arg)            2 站
 *   pcallvoid(t, fn, Object, Object)  ← pcallvoid(arg,arg2) / protectedCallVoid(arg,arg2)  2 站
 *   pcallvoid(t, fn, Object×3)        ← pcallvoid(×3) / protectedCallVoid(×3)              2 站
 *   pcallvoid(t, fn, Object[])        ← pcallvoid(Object[])                                1 站
 *   pcallBoolean(t, fn, Object)       ← protectedCallBoolean(arg)                          1 站
 *   pcallBoolean(t, fn, Object×2)     ← pcallBoolean(×2) / protectedCallBoolean(×2)        2 站
 *   pcallBoolean(t, fn, Object×3)     ← pcallBoolean(×3) / protectedCallBoolean(×3)        2 站
 *   pcallBoolean(t, fn, Object[])     ← pcallBoolean(Object[])                             1 站
 * </pre>
 *
 * 語意保證（此層不得有任何自作聰明）：
 * - 完全委派原 thread 方法，回傳值原樣傳回（{@code Boolean} 的 null 也照傳）。
 * - 委派拋出的例外原物件原樣往外傳，只在統計上記一次 thrown；採樣自身的任何失敗
 *   都被 {@link MdcProfiler#exit} 吞掉，絕不影響遊戲。
 * - 停用時 {@link MdcProfiler#enter} 只讀兩個 static volatile 就回 null＝直通，
 *   不讀時鐘、不配置物件、不組字串；框架物件在每執行緒的堆疊上重用，零配置。
 *
 * 是否掛載由 {@link MdcProfiler#isHookObserved()} 回報（首次任一站點被呼叫即為 true），
 * 這是 profiler module 有沒有真的裝上的唯一權威來源。
 */
public final class MdcProfilerHooks {

    private MdcProfilerHooks() {}

    public static Object[] pcall(KahluaThread thread, Object functionObject, Object[] args) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            return thread.pcall(functionObject, args);
        }
        boolean thrown = true;
        try {
            Object[] r = thread.pcall(functionObject, args);
            thrown = false;
            return r;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static void pcallvoid(KahluaThread thread, Object functionObject, Object arg) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            thread.pcallvoid(functionObject, arg);
            return;
        }
        boolean thrown = true;
        try {
            thread.pcallvoid(functionObject, arg);
            thrown = false;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static void pcallvoid(KahluaThread thread, Object functionObject, Object arg, Object arg2) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            thread.pcallvoid(functionObject, arg, arg2);
            return;
        }
        boolean thrown = true;
        try {
            thread.pcallvoid(functionObject, arg, arg2);
            thrown = false;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static void pcallvoid(KahluaThread thread, Object functionObject, Object arg, Object arg2,
            Object arg3) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            thread.pcallvoid(functionObject, arg, arg2, arg3);
            return;
        }
        boolean thrown = true;
        try {
            thread.pcallvoid(functionObject, arg, arg2, arg3);
            thrown = false;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static void pcallvoid(KahluaThread thread, Object functionObject, Object[] args) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            thread.pcallvoid(functionObject, args);
            return;
        }
        boolean thrown = true;
        try {
            thread.pcallvoid(functionObject, args);
            thrown = false;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static Boolean pcallBoolean(KahluaThread thread, Object functionObject, Object arg) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            return thread.pcallBoolean(functionObject, arg);
        }
        boolean thrown = true;
        try {
            Boolean r = thread.pcallBoolean(functionObject, arg);
            thrown = false;
            return r;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static Boolean pcallBoolean(KahluaThread thread, Object functionObject, Object arg,
            Object arg2) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            return thread.pcallBoolean(functionObject, arg, arg2);
        }
        boolean thrown = true;
        try {
            Boolean r = thread.pcallBoolean(functionObject, arg, arg2);
            thrown = false;
            return r;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static Boolean pcallBoolean(KahluaThread thread, Object functionObject, Object arg,
            Object arg2, Object arg3) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            return thread.pcallBoolean(functionObject, arg, arg2, arg3);
        }
        boolean thrown = true;
        try {
            Boolean r = thread.pcallBoolean(functionObject, arg, arg2, arg3);
            thrown = false;
            return r;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }

    public static Boolean pcallBoolean(KahluaThread thread, Object functionObject, Object[] args) {
        MdcProfiler.Frame f = MdcProfiler.enter(functionObject);
        if (f == null) {
            return thread.pcallBoolean(functionObject, args);
        }
        boolean thrown = true;
        try {
            Boolean r = thread.pcallBoolean(functionObject, args);
            thrown = false;
            return r;
        } finally {
            MdcProfiler.exit(f, thrown);
        }
    }
}
