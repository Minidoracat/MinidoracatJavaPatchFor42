package zombie.mdc;

import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
import static zombie.mdc.ProfilerTestSupport.*;

public final class MdcProfilerExportTest {
    public static void main(String[] args) throws Exception {
        Path cache = cache();
        try {
            completeExportPreservesSourceAndVersion();
            outputFailureKeepsRowsAndRejectsCompletion();
            System.out.println("PASS profiler export: real JFR/CSV/metadata, measured duration, fingerprint, and I/O failure without fake completion");
        } finally { clean(cache); }
    }

    private static void completeExportPreservesSourceAndVersion() throws Exception {
        LuaContext lua = context();
        var closure = lua.compile("/mods/ExportProbe/42/media/lua/client/comma,quote.lua", "return true");
        closure.prototype.name = "quoted,\"name\"";
        start(30, "ExportProbe");
        lua.call(closure);
        Path output = output();
        stop();
        var meta = metadata(output);
        require(meta.getBoolean("complete") && meta.getLong("dropped") == 0, "completed callback reported incomplete");
        require(meta.getInt("schemaVersion") == 1 && meta.getInt("rowsWritten") == 1, "metadata cannot describe its CSV");
        require(meta.getDouble("durationSeconds") > 0 && meta.getDouble("durationSeconds") < meta.getDouble("requestedSeconds"),
                "manual stop used requested rather than actual duration");
        require(meta.getString("patchVersion").equals(MdcPatchBuildInfo.VERSION)
                && meta.getString("jarSha256").equals(MdcPatchBuildInfo.JAR_SHA256), "export lost actual build identity");
        String csv = Files.readString(output.resolve("summary.csv"));
        require(csv.contains("\"quoted,\"\"name\"\"\""), "CSV did not escape quote/comma source metadata");
        var events = RecordingFile.readAllEvents(output.resolve("capture.jfr")).stream()
                .filter(event -> event.getEventType().getName().equals("Minidoracat.LuaSourceAggregate")).toList();
        require(events.size() == 1 && events.getFirst().getLong("calls") == 1
                && events.getFirst().getString("name").equals("quoted,\"name\""), "JFR does not contain the captured source");
        require(events.getFirst().getLong("inclusiveNs") >= events.getFirst().getLong("exclusiveNs"), "JFR timing invariants broken");
    }

    private static void outputFailureKeepsRowsAndRejectsCompletion() throws Exception {
        LuaContext lua = context();
        var closure = lua.compile("/mods/ExportProbe/42/media/lua/client/failure.lua", "return 17");
        start(30, "ExportProbe");
        var returned = lua.call(closure);
        require(Boolean.TRUE.equals(returned[0]) && ((Double) returned[1]) == 17d, "recording changed the Lua return");
        Path output = output();
        // 真實的目錄/檔案衝突，不在生產碼加 writer 閘或 I/O 成功替身。
        Files.createDirectory(output.resolve("summary.csv"));
        stop();
        require("error".equals(snapshot().get("state")), "failed export claimed success");
        require(rows().size() == 1 && number(row("failure.lua"), "calls") == 1, "export failure discarded inspectable data");
        require(!Files.exists(output.resolve("metadata.json")), "failed export left a successful completion marker");
        start(30, "ExportProbe");
        lua.call(closure);
        stop();
        require("complete".equals(snapshot().get("state")), "next capture could not recover from an export failure");
    }
}
