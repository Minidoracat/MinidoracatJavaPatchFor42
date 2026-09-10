package zombie.mdc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import static zombie.mdc.ProfilerTestSupport.*;

public final class MdcProfilerBehaviorTest {
    public static void main(String[] args) throws Exception {
        Path cache = cache();
        try {
            idleStopAnswersCompletely();
            passthroughAndSingleBoundary();
            failedCallbackKeepsItsOwnFailure();
            delegateFailurePassesThroughUnchanged();
            nestedAndRecursiveAccounting();
            identitiesAndFilterQuota();
            threadsAndNamedTokens();
            deadlineWithoutPolling();
            stopDuringCallbackIsIncomplete();
            oldCaptureCannotConsumeNewSamples();
            require(!Boolean.TRUE.equals(MdcProfiler.start(Double.NaN, "").get("ok")), "NaN duration accepted");
            require(!Boolean.TRUE.equals(MdcProfiler.start(301, "").get("ok")), "unbounded duration accepted");
            System.out.println("PASS profiler behavior: real Kahlua return/failure/nesting, identity/filter quotas, thread/token ownership, autonomous deadline, incomplete and next-capture isolation");
        } finally { clean(cache); }
    }

    /** 沒有任何 capture 時，Lua 端仍須拿到完整欄位，不能是 nil。 */
    private static void idleStopAnswersCompletely() {
        Map<String, Object> idle = MdcProfiler.stop();
        require(Boolean.TRUE.equals(idle.get("ok")) && "already-stopped".equals(idle.get("code"))
                && "idle".equals(idle.get("state")) && "".equals(idle.get("outputDir"))
                && "".equals(idle.get("error")), "idle stop answered with missing fields: " + idle);
    }

    /** Lua 失敗的回傳形狀必須與未錄製時逐位相同，且不得讓 capture 變成不完整。 */
    private static void failedCallbackKeepsItsOwnFailure() throws Exception {
        LuaContext lua = context();
        LuaClosure boom = lua.compile("/mods/Probe/42/media/lua/client/boom.lua", "error('boom')");
        Object[] before = lua.call(boom);
        require(before.length == 4 && Boolean.FALSE.equals(before[0]) && before[3] instanceof Throwable,
                "vanilla failure boundary unexpected: " + before.length);
        start(30, "Probe");
        Object[] after = lua.call(boom);
        require(after.length == before.length && Boolean.FALSE.equals(after[0])
                && String.valueOf(before[1]).equals(String.valueOf(after[1]))
                && after[3] instanceof Throwable, "recording changed the Lua failure result");
        require(MdcProfilerHooks.pcallBoolean(lua.thread, boom, new Object[0]) == null, "failed callback stopped returning null");
        require(number(row("boom.lua"), "calls") == 2, "failed callbacks were not measured");
        stop();
        require("complete".equals(snapshot().get("state")), "a failing Lua callback poisoned the capture");
    }

    /**
     * 委派拋出的 Java 例外必須原物件穿透，且該次呼叫要記成 thrown。
     * 真 Kahlua 會把 callback 內的錯誤轉成 false tuple，所以「同一 Throwable 穿透」只能用替身
     * 委派驗身分；後半段再用真 KahluaThread 走 pcall(Object,Object[]) 在保護區外讀 args.length
     * 的那條真實路徑，證明 callback 跑完才拋的例外同樣不被採樣層改寫。
     */
    private static void delegateFailurePassesThroughUnchanged() throws Exception {
        LuaContext lua = context();
        LuaClosure thrower = lua.compile("/mods/Probe/42/media/lua/client/thrower.lua", "return 1");
        LuaClosure nullArgs = lua.compile("/mods/Probe/42/media/lua/client/nullargs.lua", "calls = (calls or 0) + 1; return 1");
        IllegalStateException sentinel = new IllegalStateException("delegate failed");
        KahluaThread failing = new KahluaThread(System.out, J2SEPlatform.getInstance(), lua.env) {
            @Override public Object[] pcall(Object function, Object[] args) { throw sentinel; }
        };
        start(30, "Probe");
        Throwable seen = null;
        try {
            MdcProfilerHooks.pcall(failing, thrower, new Object[0]);
        } catch (Throwable t) { seen = t; }
        require(seen == sentinel, "hook did not pass the delegate's own exception object: " + seen);
        Map<String, Object> thrown = row("thrower.lua");
        require(number(thrown, "calls") == 1 && number(thrown, "thrown") == 1, "thrown callback was not accounted: " + thrown);

        NullPointerException vanilla = null;
        try {
            MdcProfilerHooks.pcall(lua.thread, nullArgs, null);
        } catch (NullPointerException e) { vanilla = e; }
        require(vanilla != null, "real Kahlua stopped throwing after the callback returned");
        require(((Double) lua.env.rawget("calls")) == 1d, "callback did not run exactly once before the delegate failed");
        require(number(row("nullargs.lua"), "thrown") == 1, "post-callback Java failure was not recorded as thrown");
        stop();
        require("complete".equals(snapshot().get("state")), "an exception-carrying callback poisoned the capture");
    }

    private static void passthroughAndSingleBoundary() throws Exception {
        LuaContext lua = context();
        LuaClosure callback = lua.compile("/mods/Probe/42/media/lua/client/returns.lua", "calls = (calls or 0) + 1; return 7, nil, 9");
        Object[] before = lua.call(callback);
        require(before.length == 4 && before[2] == null && ((Double) before[1]) == 7d, "vanilla return boundary unexpected");
        start(30, "Probe");
        Object[] after = lua.call(callback);
        require(after.length == 4 && after[2] == null && ((Double) after[3]) == 9d, "profiler changed multiple return values");
        lua.caller.protectedCallVoid(lua.thread, callback, new Object[0]);
        require(number(row("returns.lua"), "calls") == 2, "protectedCallVoid array wrapper was measured twice");
        require(((Double) lua.env.rawget("calls")) == 3d, "underlying callback ran more than once");
        LuaClosure nil = lua.compile("/mods/Probe/42/media/lua/client/nil.lua", "return nil");
        require(MdcProfilerHooks.pcallBoolean(lua.thread, nil, new Object[0]) == null, "nil Boolean changed");
        stop();
    }

    private static void nestedAndRecursiveAccounting() throws Exception {
        LuaContext lua = context();
        LuaClosure inner = lua.compile("/mods/Probe/42/media/lua/client/inner.lua", "local n=0; for i=1,100 do n=n+i end; return n");
        lua.env.rawset("invokeInner", (JavaFunction) (frame, count) -> {
            lua.caller.pcallvoid(lua.thread, inner, new Object[0]);
            return 0;
        });
        LuaClosure outer = lua.compile("/mods/Probe/42/media/lua/client/outer.lua", "invokeInner(); return true");
        start(30, "Probe");
        lua.call(outer);
        Map<String, Object> a = row("outer.lua"), b = row("inner.lua");
        require(number(a, "calls") == 1 && number(b, "calls") == 1, "nested call counts incorrect");
        require(Math.abs(decimal(a, "exclusiveMs") + decimal(b, "inclusiveMs") - decimal(a, "inclusiveMs")) < 0.000001,
                "parent exclusive did not subtract child");
        stop();

        AtomicInteger depth = new AtomicInteger();
        LuaClosure recursive = lua.compile("/mods/Probe/42/media/lua/client/recursive.lua", "reenter(); return true");
        lua.env.rawset("reenter", (JavaFunction) (frame, count) -> {
            if (depth.incrementAndGet() < 3) lua.caller.pcallvoid(lua.thread, recursive, new Object[0]);
            depth.decrementAndGet();
            return 0;
        });
        start(30, "Probe");
        lua.call(recursive);
        Map<String, Object> recursiveRow = row("recursive.lua");
        require(number(recursiveRow, "calls") == 3, "real reentry count incorrect");
        require(Math.abs(decimal(recursiveRow, "exclusiveMs") - decimal(recursiveRow, "maxMs")) < 0.000001,
                "recursive exclusive was double counted");
        stop();
    }

    private static void identitiesAndFilterQuota() throws Exception {
        LuaContext lua = context();
        LuaClosure first = lua.compile("/mods/Probe/42/media/lua/client/same.lua", "return 1");
        LuaClosure second = lua.compile("/mods/Probe/42/media/lua/client/same.lua", "return 1");
        start(30, "Probe");
        lua.call(first);
        lua.call(second);
        lua.call(new LuaClosure(first.prototype, lua.env));
        require(rows().size() == 2 && rows().stream().mapToLong(r -> number(r, "calls")).sum() == 3,
                "Prototype identity was merged or closure instances split incorrectly");
        stop();

        start(30, "TargetMod");
        for (int i = 0; i < MdcProfiler.MAX_SOURCES + 100; i++) {
            lua.call(lua.compile("/mods/Unrelated/42/media/lua/client/file" + i + ".lua", "return 1"));
        }
        LuaClosure target = lua.compile("/mods/TargetMod/42/media/lua/client/target.lua", "return 1");
        for (int i = 0; i < 1000; i++) lua.call(target);
        require(rows().size() == 1 && number(row("target.lua"), "calls") == 1000, "filtered sources starved target or short calls were dropped");
        require(((Number) snapshot().get("dropped")).longValue() == 0, "excluded sources consumed target quota");
        stop();

        start(30, "Target");
        for (int i = 0; i < 128; i++) {
            long token = MdcProfiler.beginNamed("Target:region" + i);
            require(token != 0, "matching named region was not recorded");
            MdcProfiler.endNamed(token);
        }
        require(MdcProfiler.beginNamed("Other:excluded") == 0, "excluded named region was recorded");
        require(((Number) snapshot().get("dropped")).longValue() == 0, "excluded named region counted as a lost sample after quota filled");
        stop();
    }

    private static void threadsAndNamedTokens() throws Exception {
        LuaContext main = context();
        LuaClosure shared = main.compile("/mods/Probe/42/media/lua/client/thread.lua", "return 1");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        start(30, "Probe");
        Runnable calls = () -> {
            try {
                LuaContext local = new LuaContext();
                LuaClosure function = new LuaClosure(shared.prototype, local.env);
                for (int i = 0; i < 50; i++) local.call(function);
            } catch (Throwable t) { failure.compareAndSet(null, t); }
        };
        Thread a = new Thread(calls), b = new Thread(calls);
        a.start(); b.start(); a.join(); b.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        require(number(row("thread.lua"), "calls") == 100 && rows().size() == 1, "cross-thread source accounting lost data");
        stop();

        // 超過舊版第32輪失效點，真的經過Lua bridge的double token轉換。
        MdcLuaBridge bridge = new MdcLuaBridge();
        long previous = 0;
        for (int i = 0; i < 36; i++) {
            start(10, "named");
            long token = MdcProfiler.beginNamed("named:sample");
            require(token > previous && (long) (double) token == token && token <= (1L << 53) - 1, "token cannot survive Lua number roundtrip");
            if (previous != 0) bridge.finish((double) previous);
            bridge.finish((double) token);
            require(rows().size() == 1 && number(rows().getFirst(), "calls") == 1, "stale or rounded token closed the wrong region");
            previous = token;
            stop();
        }

        start(30, "named");
        long ownerToken = MdcProfiler.beginNamed("named:owner");
        AtomicReference<Long> otherToken = new AtomicReference<>();
        Thread wrongOwner = new Thread(() -> {
            long own = MdcProfiler.beginNamed("named:other");
            otherToken.set(own);
            MdcProfiler.endNamed(ownerToken);
            MdcProfiler.endNamed(own);
        });
        wrongOwner.start(); wrongOwner.join();
        require(otherToken.get() != ownerToken, "two owners received the same token");
        MdcProfiler.endNamed(ownerToken);
        stop();
        require("error".equals(snapshot().get("state")), "foreign token was accepted");
    }

    private static void deadlineWithoutPolling() throws Exception {
        context();
        start(1, "Probe");
        Path directory = output();
        // 只看磁碟，不呼snapshot/isRecording/hook協助觸發期限。
        await(() -> Files.isRegularFile(directory.resolve("metadata.json")), "autonomous deadline export");
        await(() -> !"saving".equals(snapshot().get("state")), "deadline writer completion");
        require(!MdcProfiler.isRecording(), "deadline left recording active");
        require("timeout".equals(metadata(directory).getString("stopReason")), "deadline did not finish independently");
    }

    private static void stopDuringCallbackIsIncomplete() throws Exception {
        LuaContext main = context();
        LuaClosure template = main.compile("/mods/Probe/42/media/lua/client/blocked.lua", "block(); return true");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        start(1, "Probe");
        Path directory = output();
        Thread blocked = new Thread(() -> {
            try {
                LuaContext local = new LuaContext();
                local.env.rawset("block", (JavaFunction) (frame, count) -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return 0;
                });
                local.call(new LuaClosure(template.prototype, local.env));
            } catch (Throwable t) { failure.set(t); }
        });
        blocked.start();
        try {
            require(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "blocked callback did not start");
            await(() -> Files.isRegularFile(directory.resolve("metadata.json")), "deadline while callback blocked");
            await(() -> !"saving".equals(snapshot().get("state")), "blocked callback writer completion");
            var meta = metadata(directory);
            require(!meta.getBoolean("complete") && meta.getLong("dropped") > 0 && meta.getLong("unfinished") > 0,
                    "in-flight callback was silently exported as complete");
        } finally { release.countDown(); blocked.join(); }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void oldCaptureCannotConsumeNewSamples() throws Exception {
        LuaContext lua = context();
        LuaClosure old = lua.compile("/mods/Probe/42/media/lua/client/old.lua", "return 1");
        LuaClosure newer = lua.compile("/mods/Probe/42/media/lua/client/new.lua", "return 2");
        start(30, "Probe"); lua.call(old); stop();
        Map<String, Object> oldSnapshot = snapshot();
        start(30, "Probe"); lua.call(newer);
        require(rows().size() == 1 && row("new.lua") != null, "next capture mixed old data");
        @SuppressWarnings("unchecked")
        var oldRows = (java.util.List<Map<String, Object>>) oldSnapshot.get("rows");
        require(oldRows.size() == 1 && oldRows.getFirst().get("file").toString().endsWith("old.lua"), "old snapshot mutated into next capture");
        stop();
    }
}
