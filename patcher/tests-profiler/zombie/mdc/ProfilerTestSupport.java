package zombie.mdc;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.json.JSONObject;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.stdlib.TableLib;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.KahluaNumberConverter;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;

/** 使用真時鐘、真 JFR、真 Kahlua；不為測試改寫生產環境探測或 writer。 */
final class ProfilerTestSupport {
    private ProfilerTestSupport() {}

    static Path cache() throws Exception {
        Path root = Files.createTempDirectory("mdc-profiler-test-");
        ZomboidFileSystem.instance.setCacheDir(root.toString());
        zombie.core.random.RandStandard.INSTANCE.init();
        return root;
    }

    static final class LuaContext {
        final KahluaTable env;
        final KahluaThread thread;
        final LuaCaller caller;
        LuaContext() {
            var platform = J2SEPlatform.getInstance();
            env = platform.newTable();
            env.rawset("_G", env);
            BaseLib.register(env);
            TableLib.register(platform, env);
            KahluaTable tables = (KahluaTable) env.rawget("table");
            env.rawset("ipairs", tables.rawget("ipairs"));
            env.rawset("pairs", tables.rawget("pairs"));
            var converters = new KahluaConverterManager();
            KahluaNumberConverter.install(converters);
            caller = new LuaCaller(converters);
            thread = new KahluaThread(System.out, platform, env);
            thread.debugOwnerThread = Thread.currentThread();
        }
        LuaClosure compile(String file, String text) throws Exception {
            LuaClosure closure = LuaCompiler.loadis(new StringReader(text), file, env);
            closure.prototype.filename = file;
            closure.prototype.file = file;
            return closure;
        }
        Object[] call(LuaClosure closure) { return caller.pcall(thread, closure, new Object[0]); }
    }

    static LuaContext context() {
        LuaContext result = new LuaContext();
        LuaManager.thread = result.thread;
        LuaManager.env = result.env;
        LuaManager.caller = result.caller;
        return result;
    }

    static void start(double seconds, String filter) {
        Map<String, Object> reply = MdcProfiler.start(seconds, filter);
        require(Boolean.TRUE.equals(reply.get("ok")), "capture start failed: " + reply);
    }
    static Map<String, Object> snapshot() { return MdcProfiler.snapshot(100); }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows() { return (List<Map<String, Object>>) snapshot().get("rows"); }
    static Map<String, Object> row(String suffix) {
        return rows().stream().filter(row -> row.get("file").toString().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("missing source " + suffix + ": " + rows()));
    }
    static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    static double decimal(Map<String, Object> row, String key) { return ((Number) row.get(key)).doubleValue(); }
    static Path output() { return Path.of(snapshot().get("outputDir").toString()); }
    static void stop() throws Exception {
        MdcProfiler.stop();
        await(() -> !"saving".equals(snapshot().get("state")), "capture writer completion");
    }
    static JSONObject metadata(Path directory) throws Exception { return new JSONObject(Files.readString(directory.resolve("metadata.json"))); }
    static void await(BooleanSupplier predicate, String what) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        require(predicate.getAsBoolean(), "timeout waiting for " + what);
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void clean(Path root) throws Exception {
        if (MdcProfiler.isRecording()) stop();
        await(() -> !"saving".equals(snapshot().get("state")), "final export");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
