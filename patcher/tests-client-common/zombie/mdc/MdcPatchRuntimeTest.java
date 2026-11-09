package zombie.mdc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import se.krka.kahlua.vm.LuaClosure;

/**
 * 啟動狀態不得把缺檔、變造、版本錯誤、路徑越界或與 agent 實際啟用的模組不符標成已安裝。
 * 遊戲 jar 取 {@code -Dmdc.baseJar}（建置基準；行為測試的 classpath 是拿掉簽章的測試 jar，SHA 不同），沒給就用 classpath 上那份。
 */
public final class MdcPatchRuntimeTest {
    private static final Set<String> CORE = Set.of("core");

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mdc-runtime-state-");
        Path outside = Files.createTempFile(root.getParent(), "mdc-outside-", ".jar");
        try {
            String base = System.getProperty("mdc.baseJar");
            Path jar = base != null ? Path.of(base)
                    : Path.of(LuaClosure.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Files.copy(jar, root.resolve("projectzomboid.jar"));
            byte[] payload = {0x50, 0x4b, 3, 4, 42};
            Path agentJar = root.resolve(MdcPatchRuntime.AGENT_REL);
            Files.createDirectories(agentJar.getParent());
            Files.write(agentJar, payload);
            Files.write(outside, payload);
            JSONObject good = state(MdcPatchRuntime.AGENT_REL, hash(payload));
            save(root, good);
            if (MdcPatchRuntime.readInstallation(root, agentJar, CORE).size() != 1) throw new AssertionError("valid install rejected");

            Path journal = root.resolve(".mdc-patches/journal.json");
            Files.writeString(journal, new JSONObject().put("schemaVersion", 1).put("txId", "0123456789ab")
                    .put("committed", false).put("entries", new JSONArray()).toString());
            reject(root, agentJar, CORE, good, "pending transaction");
            Files.delete(journal);

            Files.write(agentJar, new byte[]{1, 2, 3});
            reject(root, agentJar, CORE, good, "modified agent jar");
            Files.delete(agentJar);
            reject(root, agentJar, CORE, good, "missing agent jar");
            Files.write(agentJar, payload);

            JSONObject wrongJar = new JSONObject(good.toString());
            wrongJar.put("jarSha256", "0".repeat(64));
            reject(root, agentJar, CORE, wrongJar, "wrong game fingerprint");
            JSONObject oldSchema = new JSONObject(good.toString()).put("schemaVersion", 1);
            reject(root, agentJar, CORE, oldSchema, "0.2.x loose-class schema");

            reject(root, agentJar, CORE, state("zombie/../../" + outside.getFileName(), hash(payload)), "path traversal");
            reject(root, agentJar, CORE, state(outside.toString(), hash(payload)), "absolute agent path");
            reject(root, agentJar, CORE, state(".mdc-patches/Other.jar", hash(payload)), "agent jar at another path");
            reject(root, outside, CORE, good, "helpers loaded from another agent jar");
            reject(root, agentJar, Set.of("core", "profiler"), good, "agent modules differ from the record");

            JSONObject noCore = new JSONObject(good.toString());
            noCore.getJSONArray("modules").getJSONObject(0).put("id", "profiler");
            reject(root, agentJar, Set.of("profiler"), noCore, "missing core dependency");
            JSONObject unknown = new JSONObject(good.toString());
            unknown.getJSONArray("modules").getJSONObject(0).put("id", "foreign-module");
            reject(root, agentJar, Set.of("foreign-module"), unknown, "unknown module");
            JSONObject duplicate = new JSONObject(good.toString());
            duplicate.getJSONArray("modules").put(new JSONObject(duplicate.getJSONArray("modules").getJSONObject(0).toString()));
            reject(root, agentJar, CORE, duplicate, "duplicate module");
            JSONObject oversized = new JSONObject(good.toString()).put("extra", "x".repeat(270_000));
            reject(root, agentJar, CORE, oversized, "oversized installation state");

            JSONObject withCompat = new JSONObject(good.toString());
            withCompat.getJSONArray("modules").put(module("client-fixes-compat"));
            save(root, withCompat);
            Set<String> compat = Set.of("core", "client-fixes-compat");
            if (MdcPatchRuntime.readInstallation(root, agentJar, compat).size() != 2) throw new AssertionError("core + compat variant rejected");
            JSONObject twoVariants = new JSONObject(withCompat.toString());
            twoVariants.getJSONArray("modules").put(module("client-fixes-standard"));
            reject(root, agentJar, Set.of("core", "client-fixes-compat", "client-fixes-standard"), twoVariants, "two client-fixes variants");

            if (!MdcPatchRuntime.gameRoot(agentJar).equals(root) || !MdcPatchRuntime.gameRoot(root).equals(root)) {
                throw new AssertionError("game folder not found from the agent jar inside it");
            }

            // agent 健康狀態：只有 armed 且沒有換不上的 class 才回模組；其餘一律拒絕（compatible=false）
            if (!MdcPatchRuntime.agentModules("armed", "core,client-fixes-compat", 0).equals(compat)) {
                throw new AssertionError("armed agent modules not parsed");
            }
            for (Object[] bad : new Object[][]{{"disarmed", "-", 0}, {"partial", "core", 0}, {"off", "-", 0},
                    {"not loaded", "-", 0}, {"armed", "core", 1}}) {
                try {
                    MdcPatchRuntime.agentModules((String) bad[0], (String) bad[1], (Integer) bad[2]);
                } catch (IllegalStateException expected) {
                    continue;
                }
                throw new AssertionError("accepted agent state " + bad[0] + " mismatched=" + bad[2]);
            }

            save(root, good);
            if (MdcPatchRuntime.readInstallation(root, agentJar, CORE).size() != 1) throw new AssertionError("restored install rejected");
            System.out.println("PASS runtime installation: valid/restored install, core+compat, game folder from the agent jar; 15 fail-closed boundaries; agent must be armed with nothing unpatched");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(outside);
        }
    }

    private static JSONObject state(String agentPath, String sha) {
        return new JSONObject().put("schemaVersion", 2).put("jarSha256", MdcPatchBuildInfo.JAR_SHA256)
                .put("packageVersion", "test").put("agent", new JSONObject().put("path", agentPath).put("sha256", sha))
                .put("modules", new JSONArray().put(module("core")));
    }

    private static JSONObject module(String id) {
        return new JSONObject().put("id", id).put("name", id).put("version", "test").put("requires", new JSONArray());
    }

    private static void save(Path root, JSONObject state) throws IOException {
        Path file = root.resolve(".mdc-patches/state.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, state.toString());
    }

    private static void reject(Path root, Path agentJar, Set<String> agentModules, JSONObject state, String scenario) throws Exception {
        save(root, state);
        try {
            MdcPatchRuntime.readInstallation(root, agentJar, agentModules);
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("accepted " + scenario);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
