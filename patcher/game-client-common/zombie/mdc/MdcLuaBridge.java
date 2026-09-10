package zombie.mdc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

/** 只暴露診斷命令與值快照；不暴露檔案路徑執行、反射或遊戲修改入口。 */
public final class MdcLuaBridge {
    @LuaMethod(name = "mdcPatchStatus", global = true)
    public KahluaTable patchStatus() {
        return table(MdcPatchRuntime.status());
    }

    @LuaMethod(name = "mdcProfilerStart", global = true)
    public KahluaTable start(double seconds, String filter) {
        return table(MdcPatchRuntime.canProfile()
                ? MdcProfiler.start(seconds, filter) : unavailable());
    }

    @LuaMethod(name = "mdcProfilerStop", global = true)
    public KahluaTable stop() {
        return table(MdcProfiler.stop());
    }

    @LuaMethod(name = "mdcProfilerSnapshot", global = true)
    public KahluaTable snapshot(double limit) {
        if (!MdcPatchRuntime.canProfile()) {
            return table(unavailable());
        }
        int count = Double.isFinite(limit) ? (int) Math.max(1, Math.min(100, limit)) : 20;
        return table(MdcProfiler.snapshot(count));
    }

    @LuaMethod(name = "mdcProfilerBegin", global = true)
    public double begin(String label) {
        return MdcPatchRuntime.canProfile() && MdcProfiler.isRecording()
                ? MdcProfiler.beginNamed(label) : 0;
    }

    @LuaMethod(name = "mdcProfilerEnd", global = true)
    public void finish(double token) {
        if (Double.isFinite(token) && token >= 0 && token <= 9_007_199_254_740_991d
                && token == Math.floor(token)) {
            MdcProfiler.endNamed((long) token);
        }
    }

    private static Map<String, Object> unavailable() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("code", "profiler_unavailable");
        result.put("state", "unavailable");
        result.put("error", "Install the compatible profiler module with the game closed, then restart.");
        result.put("rows", List.of());
        return result;
    }

    private static KahluaTable table(Map<?, ?> values) {
        KahluaTable result = LuaManager.platform.newTable();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() != null) {
                result.rawset(key, value(entry.getValue()));
            }
        }
        return result;
    }

    private static Object value(Object value) {
        if (value instanceof Map<?, ?> map) {
            return table(map);
        }
        if (value instanceof List<?> list) {
            KahluaTable result = LuaManager.platform.newTable();
            for (int i = 0; i < list.size(); i++) {
                result.rawset((double) i + 1, value(list.get(i)));
            }
            return result;
        }
        if (value instanceof Number number) {
            double scalar = number.doubleValue();
            return Double.isFinite(scalar) ? scalar : null;
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported diagnostic snapshot value: " + value.getClass().getName());
    }
}
