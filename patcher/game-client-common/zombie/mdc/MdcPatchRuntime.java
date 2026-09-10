package zombie.mdc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.logger.ExceptionLogger;
import zombie.gameStates.MainScreenState;
import zombie.network.GameServer;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

/** 共用 patch 的啟動驗證與可見狀態；不載入第三方 Java MOD。 */
public final class MdcPatchRuntime {
    private static final Set<String> MODULE_IDS = Set.of(
            "core", "profiler", "client-fixes-standard", "client-fixes-lowmem");
    private static final long MAX_STATE_BYTES = 262_144;
    private static final int MAX_FILES = 256;
    private static final MdcLuaBridge BRIDGE = new MdcLuaBridge();
    private static volatile boolean initialized;
    private static volatile boolean fixesObserved;
    private static volatile boolean compatible;
    private static String stateError = "not_initialized";
    private static List<Map<String, Object>> installedModules = List.of();
    private static long overlayStartedNs;
    private static long overlayUpdatedNs;
    private static boolean overlayFinished;
    private static boolean overlayErrorLogged;
    private static List<String> overlayLines = List.of();

    private MdcPatchRuntime() {}

    /** 每次 Lua 環境重建都重新註冊，不能保留舊 environment 的函式表。 */
    public static void register(LuaManager.Exposer exposer) {
        if (!initialized) {
            initialize();
        }
        exposer.exposeGlobalFunctions(BRIDGE);
    }

    private static synchronized void initialize() {
        if (initialized) {
            return;
        }
        try {
            Path root = Path.of(MdcPatchRuntime.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            installedModules = readInstallation(root);
            compatible = true;
            stateError = "";
        } catch (Exception e) {
            compatible = false;
            stateError = e.getClass().getSimpleName() + ": " + clean(e.getMessage(), 180);
        }
        initialized = true;
        System.out.println("[MinidoracatPatches] core=" + MdcPatchBuildInfo.VERSION
                + " built=" + MdcPatchBuildInfo.BUILT + " jar="
                + MdcPatchBuildInfo.JAR_SHA256.substring(0, 8)
                + " compatible=" + compatible);
        if (!compatible) {
            System.out.println("[MinidoracatPatches] status error: " + stateError);
        }
        for (Map<String, Object> module : installedModules) {
            System.out.println("[MinidoracatPatches] installed=" + module.get("id")
                    + " version=" + module.get("version") + " (hook activity reported separately)");
        }
    }

    /** 只在啟動時讀取、驗證安裝記錄；任何不一致都不能宣稱 compatible。 */
    static List<Map<String, Object>> readInstallation(Path root) throws Exception {
        if (!Files.notExists(root.resolve(".mdc-patches/journal.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unfinished installation transaction; close the game and use the patch manager");
        }
        Path state = safeFile(root, ".mdc-patches/state.json", false);
        if (Files.size(state) > MAX_STATE_BYTES) {
            throw new IOException("installation state is too large");
        }
        JSONObject json = new JSONObject(Files.readString(state));
        if (json.getInt("schemaVersion") != 1) {
            throw new IOException("unsupported installation schema");
        }
        String jarSha = json.getString("jarSha256");
        if (!MdcPatchBuildInfo.JAR_SHA256.equals(jarSha)
                || !jarSha.equals(sha256(safeFile(root, "projectzomboid.jar", false)))) {
            throw new IOException("game version mismatch; close the game and use the patch manager");
        }
        JSONArray modules = json.getJSONArray("modules");
        if (modules.length() == 0 || modules.length() > MODULE_IDS.size()) {
            throw new IOException("invalid installed module count");
        }
        Set<String> seen = new java.util.HashSet<>();
        Set<String> ownedPaths = new java.util.HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.getJSONObject(i);
            String id = module.getString("id");
            if (!MODULE_IDS.contains(id) || !seen.add(id)) {
                throw new IOException("invalid or duplicate module: " + clean(id, 60));
            }
            JSONArray files = module.getJSONArray("files");
            if (files.length() == 0) {
                throw new IOException("module has no payload: " + id);
            }
            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.getJSONObject(j);
                String rel = file.getString("path");
                if (!ownedPaths.add(rel) || ownedPaths.size() > MAX_FILES) {
                    throw new IOException("duplicate or excessive payload entries");
                }
                String expected = file.getString("sha256");
                if (!expected.matches("[0-9a-f]{64}")
                        || !expected.equals(sha256(safeFile(root, rel, true)))) {
                    throw new IOException("payload mismatch: " + clean(rel, 120));
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", clean(module.getString("name"), 80));
            row.put("version", clean(module.getString("version"), 80));
            row.put("installed", true);
            result.add(row);
        }
        if (!seen.contains("core") || (seen.contains("client-fixes-standard") && seen.contains("client-fixes-lowmem"))) {
            throw new IOException("invalid module dependencies or variants");
        }
        return List.copyOf(result);
    }

    private static Path safeFile(Path root, String relative, boolean payload) throws IOException {
        if (relative.isEmpty() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.startsWith("/") || relative.contains("//")) {
            throw new IOException("invalid installation path");
        }
        Path rel = Path.of(relative);
        for (Path part : rel) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IOException("relative traversal is not allowed");
            }
        }
        if (payload && (!(relative.startsWith("zombie/") || relative.startsWith("se/"))
                || !relative.endsWith(".class"))) {
            throw new IOException("unsupported payload path");
        }
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("path escaped installation root");
        }
        Path cursor = root;
        for (Path part : rel) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("symbolic links are not allowed in patch paths");
            }
        }
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing installed file: " + clean(relative, 120));
        }
        return resolved;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32_768];
            for (int n; (n = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String clean(String text, int limit) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public static void observeClientFixes() {
        fixesObserved = true;
    }

    public static boolean canProfile() {
        return compatible && isInstalled("profiler") && MdcProfiler.isHookObserved();
    }

    private static boolean isInstalled(String id) {
        for (Map<String, Object> module : installedModules) {
            if (id.equals(module.get("id"))) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("apiVersion", 1);
        status.put("version", MdcPatchBuildInfo.VERSION);
        status.put("compatible", compatible);
        status.put("error", stateError);
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Map<String, Object> source : installedModules) {
            Map<String, Object> row = new LinkedHashMap<>(source);
            String id = (String) source.get("id");
            row.put("observed", id.equals("core") ? initialized
                    : id.equals("profiler") ? MdcProfiler.isHookObserved() : fixesObserved);
            modules.add(row);
        }
        status.put("modules", modules);
        return status;
    }

    public static void onLuaReset(Core unused) {
        MdcProfiler.onLuaReset();
    }

    /** 原版 exception popup 的委派在觀測保護區外，不能吞掉原版錯誤。 */
    public static void renderEndFrameUI() {
        ExceptionLogger.render();
        if (GameServer.server || !initialized || overlayFinished
                || !(GameWindow.states.current instanceof MainScreenState)) {
            return;
        }
        try {
            TextManager text = TextManager.instance;
            UIFont font = UIFont.NewSmall;
            if (text == null || text.getFontFromEnum(font) == null) {
                return;
            }
            long now = System.nanoTime();
            if (overlayStartedNs == 0) {
                overlayStartedNs = now;
            }
            if (now - overlayStartedNs > 30_000_000_000L) {
                overlayFinished = true;
                return;
            }
            // nanoTime 起點可為任意值（含負值），首幀不能只靠 now-0 的差值決定要不要組行。
            if (overlayLines.isEmpty() || now - overlayUpdatedNs > 1_000_000_000L) {
                List<String> lines = new ArrayList<>();
                lines.add("Minidoracat patches " + MdcPatchBuildInfo.VERSION);
                if (!compatible) {
                    lines.add("CHECK FAILED - run the patch manager before playing");
                    lines.add(clean(stateError, 95));
                } else {
                    for (Map<String, Object> module : installedModules) {
                        String id = (String) module.get("id");
                        boolean observed = id.equals("core") || (id.equals("profiler")
                                ? MdcProfiler.isHookObserved() : fixesObserved);
                        lines.add(id + " " + module.get("version") + (observed
                                ? " [hook observed]" : " [installed; waiting for hook]"));
                    }
                }
                overlayLines = List.copyOf(lines);
                overlayUpdatedNs = now;
            }
            int lineHeight = text.getFontHeight(font) + 4;
            int height = 14 + overlayLines.size() * lineHeight;
            int y = Math.max(12, Core.getInstance().getScreenHeight() - height - 16);
            int width = Math.min(720, Core.getInstance().getScreenWidth() - 32);
            SpriteRenderer.instance.renderRect(16, y, width, height, 0.05f, 0.06f, 0.07f, 0.86f);
            int rowY = y + 7;
            for (String line : overlayLines) {
                text.DrawString(font, 24, rowY, line, 0.88, compatible ? 0.92 : 0.55, compatible ? 0.94 : 0.45, 1.0);
                rowY += lineHeight;
            }
        } catch (RuntimeException e) {
            overlayFinished = true;
            if (!overlayErrorLogged) {
                overlayErrorLogged = true;
                System.out.println("[MinidoracatPatches] startup display disabled: " + clean(e.toString(), 180));
            }
        }
    }
}
