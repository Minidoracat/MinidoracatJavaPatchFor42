package zombie.mdc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import se.krka.kahlua.vm.LuaClosure;

/** 啟動狀態不得把缺檔、變造、版本錯誤或路徑越界標成已安裝。 */
public final class MdcPatchRuntimeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mdc-runtime-state-");
        Path outside = Files.createTempFile(root.getParent(), "mdc-outside-", ".class");
        try {
            Path jar = Path.of(LuaClosure.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Files.copy(jar, root.resolve("projectzomboid.jar"));
            byte[] payload;
            try (var in = MdcPatchRuntime.class.getResourceAsStream("MdcPatchRuntime.class")) {
                if (in == null) throw new AssertionError("missing real runtime class");
                payload = in.readAllBytes();
            }
            String relative = "zombie/mdc/MdcPatchRuntime.class";
            Path installed = root.resolve(relative);
            Files.createDirectories(installed.getParent());
            Files.write(installed, payload);
            Files.write(outside, payload);
            JSONObject good = state(relative, hash(payload));
            save(root, good);
            if (MdcPatchRuntime.readInstallation(root).size() != 1) throw new AssertionError("valid package rejected");

            Path hook = root.resolve("se/krka/kahlua/integration/LuaCaller.class");
            Files.createDirectories(hook.getParent());
            try (var in = LuaClosure.class.getClassLoader().getResourceAsStream("se/krka/kahlua/integration/LuaCaller.class")) {
                if (in == null) throw new AssertionError("missing real LuaCaller class");
                Files.copy(in, hook);
            }
            Path journal = root.resolve(".mdc-patches/journal.json");
            Files.writeString(journal, new JSONObject().put("schemaVersion", 1)
                    .put("txId", "20260910_120000-abcdef12").put("committed", false)
                    .put("entries", new JSONArray().put(new JSONObject()
                            .put("path", "se/krka/kahlua/integration/LuaCaller.class").put("backup", JSONObject.NULL)))
                    .put("createdDirs", new JSONArray()).toString());
            reject(root, good, "pending transaction with uncommitted hook");
            Files.delete(journal);
            Files.delete(hook);

            Files.write(installed, new byte[]{1, 2, 3});
            reject(root, good, "modified payload");
            Files.delete(installed);
            reject(root, good, "missing payload");
            Files.write(installed, payload);

            JSONObject wrongJar = new JSONObject(good.toString());
            wrongJar.put("jarSha256", "0".repeat(64));
            reject(root, wrongJar, "wrong game fingerprint");

            JSONObject traversal = state("zombie/../../" + outside.getFileName(), hash(payload));
            reject(root, traversal, "path traversal to existing file");
            JSONObject absolute = state(outside.toString(), hash(payload));
            reject(root, absolute, "absolute payload path");

            JSONObject noCore = new JSONObject(good.toString());
            noCore.getJSONArray("modules").getJSONObject(0).put("id", "profiler");
            reject(root, noCore, "missing core dependency");
            JSONObject unknown = new JSONObject(good.toString());
            unknown.getJSONArray("modules").getJSONObject(0).put("id", "foreign-module");
            reject(root, unknown, "unknown module");
            JSONObject duplicate = new JSONObject(good.toString());
            duplicate.getJSONArray("modules").put(new JSONObject(duplicate.getJSONArray("modules").getJSONObject(0).toString()));
            reject(root, duplicate, "duplicate module");

            JSONObject oversized = new JSONObject(good.toString()).put("extra", "x".repeat(270_000));
            reject(root, oversized, "oversized installation state");
            save(root, good);
            if (MdcPatchRuntime.readInstallation(root).size() != 1) throw new AssertionError("restored package rejected");
            System.out.println("PASS runtime installation: valid/restored package; 10 fail-closed boundaries");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(outside);
        }
    }

    private static JSONObject state(String path, String sha) {
        JSONObject file = new JSONObject().put("path", path).put("sha256", sha);
        JSONObject module = new JSONObject().put("id", "core").put("name", "Core").put("version", "test")
                .put("files", new JSONArray().put(file));
        return new JSONObject().put("schemaVersion", 1).put("jarSha256", MdcPatchBuildInfo.JAR_SHA256)
                .put("packageVersion", "test").put("modules", new JSONArray().put(module));
    }

    private static void save(Path root, JSONObject state) throws IOException {
        Path file = root.resolve(".mdc-patches/state.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, state.toString());
    }

    private static void reject(Path root, JSONObject state, String scenario) throws Exception {
        save(root, state);
        try {
            MdcPatchRuntime.readInstallation(root);
        } catch (Exception expected) {
            System.out.println("PASS rejected " + scenario);
            return;
        }
        throw new AssertionError("accepted " + scenario);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
