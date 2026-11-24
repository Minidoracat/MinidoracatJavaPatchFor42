import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * macOS 版 client 修補管理器（由 Install-Patches.command 以遊戲自帶的 JRE 執行）。
 *
 * <p>遊戲根＝{@code <Project Zomboid.app>/Contents/Java}（啟動器 chdir 到這裡，MdcPatchRuntime.gameRoot 也找到這裡）。
 * 只碰三個位置：{@code .mdc-patches/MinidoracatClientPatches.jar}、{@code .mdc-patches/state.json}（schemaVersion 2，
 * 和 Windows／MdcPatchRuntime 同一份格式），以及 {@code Contents/Info.plist} 的 JVMOptions 裡自家那一行
 * {@code -javaagent:.mdc-patches/MinidoracatClientPatches.jar=modules=…}（Mac 的啟動器走 JLI_Launch，-javaagent 直接可用，
 * 不需要 Windows 的 agent 載入器 DLL）。唯一例外：玩家同意時改 JVMOptions 裡 -Xmx 的值，移除時不改回；主選單 [5] 只改這個值，
 * 沒裝修補也能改，不碰安裝紀錄。
 *
 * <p>沒有 journal：所有新內容先寫成同資料夾的暫存檔並驗 SHA，再連續原子改名。啟用順序 jar → state → plist、
 * 停用順序 plist → state → jar，任何中斷點都不會留下「plist 指著不存在的 jar」；中斷後重跑安裝或移除即可收斂。
 * 退出碼 0–8 與 Windows 的 Manage-Patches.ps1 相同；12＝寫不進遊戲 app。
 */
public final class MacInstaller {
    static final int OK = 0, INTERNAL = 1, USAGE = 2, GAMEDIR = 3, RUNNING = 4, JAR = 5, PACKAGE = 6, CONFLICT = 7,
            TAMPERED = 8, WRITE = 12;
    static final String AGENT_NAME = "MinidoracatClientPatches.jar";
    static final String DIR_REL = ".mdc-patches";
    static final String AGENT_REL = DIR_REL + "/" + AGENT_NAME;
    static final String STATE_REL = DIR_REL + "/state.json";
    static final String WORKDIR = "$APP_ROOT/Contents/Java";
    static final Set<String> MODULE_IDS = Set.of("core", "profiler", "client-fixes-standard", "client-fixes-lowmem",
            "client-fixes-compat");
    static final String COMPAT = "client-fixes-compat";
    /** 遊戲原版 Info.plist 的 -Xmx3072m（patcher/tests-installer-mac/Info.plist.42.22.2）；--memory-mb 不得低於它。 */
    static final int VANILLA_MEMORY_MB = 3072;

    static final class Fail extends RuntimeException {
        final int code;

        Fail(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final PrintStream out;
    private final BufferedReader in;
    private boolean zh;
    private boolean interactive = true;
    private int ramGb;
    private Integer memoryMb;
    private Path app, contents, root, plistPath;
    private Path pkgDir;
    private Map<String, Object> manifest;

    private MacInstaller(InputStream in, PrintStream out) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    public static void main(String[] args) {
        PrintStream o = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.exit(run(args, System.in, o));
    }

    static int run(String[] args, InputStream in, PrintStream out) {
        MacInstaller m = new MacInstaller(in, out);
        try {
            m.main0(args);
            return OK;
        } catch (Fail f) {
            out.println();
            m.bad(f.getMessage());
            out.println(m.L("結束代碼：", "Exit code: ") + f.code);
            return f.code;
        } catch (Exception e) {
            out.println();
            m.bad(e.toString());
            e.printStackTrace(out);
            out.println(m.L("結束代碼：", "Exit code: ") + INTERNAL);
            return INTERNAL;
        }
    }

    // ------------------------------------------------------------------ 輸出
    String L(String zhText, String en) {
        return zh ? zhText : en;
    }

    private void info(String m) {
        out.println(m);
    }

    private void head(String m) {
        out.println();
        out.println("== " + m);
    }

    private void good(String m) {
        out.println(m);
    }

    private void warn(String m) {
        out.println(L("[警告] ", "[Warning] ") + m);
    }

    private void bad(String m) {
        out.println(L("[錯誤] ", "[Error] ") + m);
    }

    private String ask(String prompt) {
        out.print(prompt + ": ");
        out.flush();
        try {
            String line = in.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 參數
    private void main0(String[] args) throws Exception {
        String action = "menu", appArg = null, pkgArg = null, lang = "auto";
        List<String> requested = new ArrayList<>();
        boolean all = false;
        for (int k = 0; k < args.length; k++) {
            String a = args[k];
            switch (a) {
                case "menu", "status", "install", "uninstall", "quick" -> action = a;
                case "--all" -> all = true;
                case "--non-interactive" -> interactive = false;
                case "--app", "--package-dir", "--modules", "--ram-gb", "--lang", "--memory-mb" -> {
                    if (k + 1 >= args.length) throw new Fail(USAGE, "missing value for " + a);
                    String v = args[++k];
                    switch (a) {
                        case "--app" -> appArg = v;
                        case "--package-dir" -> pkgArg = v;
                        case "--modules" -> {
                            for (String p : v.split(",")) if (!p.isBlank()) requested.add(p.trim());
                        }
                        case "--ram-gb" -> {
                            try {
                                ramGb = Integer.parseInt(v);
                            } catch (NumberFormatException e) {
                                throw new Fail(USAGE, "--ram-gb must be a number");
                            }
                        }
                        case "--memory-mb" -> {
                            if (!v.matches("\\d{1,7}") || Integer.parseInt(v) < VANILLA_MEMORY_MB) {
                                throw new Fail(USAGE, "--memory-mb must be a number of MB, at least " + VANILLA_MEMORY_MB);
                            }
                            memoryMb = Integer.parseInt(v);
                        }
                        default -> lang = v;
                    }
                }
                default -> throw new Fail(USAGE, "unknown argument: " + a
                        + "\nusage: [menu|status|install|quick|uninstall] --app <Project Zomboid.app> [--package-dir <dir>]"
                        + " [--modules id,...] [--all] [--non-interactive] [--ram-gb N] [--memory-mb N] [--lang auto|zh|en]");
            }
        }
        if (!Set.of("auto", "zh", "en").contains(lang)) throw new Fail(USAGE, "--lang must be auto, zh or en");
        zh = lang.equals("auto") ? Locale.getDefault().getLanguage().equals("zh") : lang.equals("zh");
        if (action.equals("menu") && !interactive) {
            throw new Fail(USAGE, L("--non-interactive 必須搭配 status、install 或 uninstall。", "--non-interactive requires status, install or uninstall."));
        }
        resolveApp(appArg);
        pkgDir = pkgArg != null ? Path.of(pkgArg).toAbsolutePath().normalize() : defaultPackageDir();
        if (pkgDir != null && Files.isRegularFile(pkgDir.resolve("manifest.json"))) manifest = readManifest(pkgDir);
        if ((action.equals("install") || action.equals("quick")) && manifest == null) {
            throw new Fail(PACKAGE, L("找不到 manifest.json：請從解壓後的安裝包資料夾執行。", "manifest.json not found: run this from the extracted package folder."));
        }
        switch (action) {
            case "status" -> status();
            case "quick" -> install(quickTargets(), false);
            case "install" -> {
                List<String> req = requested;
                if (!all && req.isEmpty() && interactive) {
                    req = selectInstall();
                    if (req.isEmpty()) {
                        info(L("已取消。", "Cancelled."));
                        return;
                    }
                }
                install(req, all);
            }
            case "uninstall" -> {
                List<String> req = requested;
                if (!all && req.isEmpty()) {
                    if (!interactive) throw new Fail(USAGE, L("沒有指定要移除的模組（--modules id,... 或 --all）。", "No modules given (--modules id,... or --all)."));
                    List<String> ids = moduleIds(readState());
                    if (ids.isEmpty()) {
                        all = true;
                    } else {
                        req = selectUninstall(ids);
                        if (req.isEmpty()) {
                            info(L("已取消。", "Cancelled."));
                            return;
                        }
                    }
                }
                uninstall(req, all);
            }
            default -> menu();
        }
    }

    private static Path defaultPackageDir() {
        try {
            Path p = Path.of(MacInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /** 接受 .app 本身，或放著 Project Zomboid.app 的遊戲資料夾。 */
    private void resolveApp(String arg) throws IOException {
        if (arg == null || arg.isBlank()) throw new Fail(USAGE, "--app is required");
        Path p = Path.of(arg).toAbsolutePath().normalize();
        if (!p.getFileName().toString().endsWith(".app") && Files.isDirectory(p.resolve("Project Zomboid.app"))) {
            p = p.resolve("Project Zomboid.app");
        }
        Path c = p.resolve("Contents");
        if (!Files.isRegularFile(c.resolve("Java/projectzomboid.jar")) || !Files.isRegularFile(c.resolve("Info.plist"))
                || !Files.isRegularFile(c.resolve("MacOS/JavaAppLauncher"))) {
            throw new Fail(GAMEDIR, L("不是 Project Zomboid 的遊戲 app（找不到 Contents/Java/projectzomboid.jar、Contents/Info.plist 或 Contents/MacOS/JavaAppLauncher）：",
                    "Not a Project Zomboid game app (Contents/Java/projectzomboid.jar, Contents/Info.plist or Contents/MacOS/JavaAppLauncher is missing): ") + p);
        }
        app = p;
        contents = c;
        root = c.resolve("Java");
        plistPath = c.resolve("Info.plist");
    }

    // ------------------------------------------------------------------ 安裝包
    private Map<String, Object> readManifest(Path dir) {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(Files.readString(dir.resolve("manifest.json"))), "manifest.json");
            if (!Long.valueOf(2).equals(m.get("schemaVersion"))) throw new IllegalArgumentException("schemaVersion is not 2");
            if (!Json.str(m, "jarSha256").matches("[0-9a-f]{64}")) throw new IllegalArgumentException("bad jarSha256");
            Map<String, Object> agent = Json.obj(m.get("agent"), "agent");
            if (!AGENT_NAME.equals(Json.str(agent, "source")) || !Json.str(agent, "sha256").matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("bad agent entry");
            }
            List<Object> mods = Json.arr(m.get("modules"), "modules");
            if (mods.isEmpty()) throw new IllegalArgumentException("no modules");
            for (Object o : mods) {
                Map<String, Object> mod = Json.obj(o, "module");
                if (!MODULE_IDS.contains(Json.str(mod, "id"))) throw new IllegalArgumentException("unknown module " + mod.get("id"));
                Json.str(mod, "name");
                Json.str(mod, "version");
                for (Object r : Json.arr(mod.get("requires"), "requires")) {
                    if (!(r instanceof String)) throw new IllegalArgumentException("bad requires");
                }
                for (Object c : Json.arr(mod.get("patchedClasses"), "patchedClasses")) {
                    if (!(c instanceof String)) throw new IllegalArgumentException("bad patchedClasses");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new Fail(PACKAGE, L("安裝包的 manifest.json 有問題：", "The package's manifest.json is broken: ") + e.getMessage());
        }
        return m;
    }

    private Path packageAgent() throws IOException {
        Path a = pkgDir.resolve(AGENT_NAME);
        String want = Json.str(Json.obj(manifest.get("agent"), "agent"), "sha256");
        if (!Files.isRegularFile(a) || !sha256(a).equals(want)) {
            throw new Fail(PACKAGE, L("安裝包裡的 " + AGENT_NAME + " 缺少或內容不符（請重新下載並整包解壓）。",
                    AGENT_NAME + " in the package is missing or does not match (download again and extract the whole zip)."));
        }
        return a;
    }

    private List<Map<String, Object>> manifestModules() {
        List<Map<String, Object>> r = new ArrayList<>();
        if (manifest != null) for (Object o : Json.arr(manifest.get("modules"), "modules")) r.add(Json.obj(o, "module"));
        return r;
    }

    private Map<String, Object> manifestModule(String id) {
        for (Map<String, Object> m : manifestModules()) if (id.equals(m.get("id"))) return m;
        return null;
    }

    String label(String id) {
        return switch (id) {
            case "core" -> L("核心元件", "Core components");
            case "profiler" -> L("開發者工具 DevProfiler", "DevProfiler developer tool");
            case "client-fixes-standard" -> L("客戶端修復（標準版）", "Client fixes (standard)");
            case "client-fixes-lowmem" -> L("客戶端修復（省記憶體版）", "Client fixes (low-memory)");
            case COMPAT -> L("客戶端修復（相容版）", "Client fixes (compatible)");
            default -> id;
        };
    }

    private String labels(List<String> ids) {
        List<String> r = new ArrayList<>();
        for (String id : ids) r.add(label(id));
        return String.join(L("、", ", "), r);
    }

    // ------------------------------------------------------------------ 安裝紀錄
    private Map<String, Object> readState() throws IOException {
        Path p = root.resolve(STATE_REL);
        if (Files.isSymbolicLink(root.resolve(DIR_REL)) || Files.isSymbolicLink(p)) {
            throw new Fail(CONFLICT, L(DIR_REL + " 或 state.json 是符號連結，不會處理。", DIR_REL + " or state.json is a symbolic link; it will not be touched."));
        }
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null;
        Map<String, Object> s;
        try {
            s = Json.obj(Json.parse(Files.readString(p)), "state.json");
            if (!Long.valueOf(2).equals(s.get("schemaVersion"))) throw new IllegalArgumentException("schemaVersion is not 2");
            if (!AGENT_REL.equals(Json.str(Json.obj(s.get("agent"), "agent"), "path"))) {
                throw new Fail(CONFLICT, L(STATE_REL + " 記的修補檔位置不是 " + AGENT_REL + "，不會處理。", STATE_REL + " records a patch file location other than " + AGENT_REL + "; it will not be touched."));
            }
            for (Object o : Json.arr(s.get("modules"), "modules")) {
                if (!MODULE_IDS.contains(Json.str(Json.obj(o, "module"), "id"))) throw new IllegalArgumentException("unknown module");
            }
        } catch (IllegalArgumentException e) {
            throw new Fail(INTERNAL, L(STATE_REL + " 這個安裝器不認得（" + e.getMessage() + "），請用對應版本的安裝包處理。",
                    STATE_REL + " is not understood by this installer (" + e.getMessage() + "); use the matching package version."));
        }
        return s;
    }

    static List<String> moduleIds(Map<String, Object> state) {
        List<String> r = new ArrayList<>();
        if (state != null) for (Object o : Json.arr(state.get("modules"), "modules")) r.add(Json.str(Json.obj(o, "module"), "id"));
        return r;
    }

    private static String stateAgentSha(Map<String, Object> state) {
        return state == null ? null : Json.str(Json.obj(state.get("agent"), "agent"), "sha256", null);
    }

    // ------------------------------------------------------------------ 模組選擇
    private List<String> resolveInstall(List<String> installed, List<String> requested, List<String> replaced) {
        for (String r : requested) {
            if (manifestModule(r) == null) {
                List<String> ids = new ArrayList<>();
                for (Map<String, Object> m : manifestModules()) ids.add((String) m.get("id"));
                throw new Fail(USAGE, L("未知的模組 id：", "Unknown module id: ") + r + " (" + String.join(", ", ids) + ")");
            }
        }
        // 新安裝包不再提供的已裝模組（例如日後拿掉某個變體）一併換掉
        for (String id : installed) if (manifestModule(id) == null) replaced.add(id);
        Set<String> target = new LinkedHashSet<>(installed);
        target.removeAll(replaced);
        target.addAll(requested);
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map<String, Object> m : manifestModules()) {
            String g = Json.str(m, "exclusiveGroup", null);
            if (g != null) groups.computeIfAbsent(g, x -> new ArrayList<>()).add((String) m.get("id"));
        }
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            List<String> req = new ArrayList<>();
            for (String r : new LinkedHashSet<>(requested)) if (g.getValue().contains(r)) req.add(r);
            if (req.size() > 1) {
                throw new Fail(USAGE, L("同一組只能選一個變體：", "Only one variant of a group can be chosen: ") + String.join(", ", req));
            }
            if (req.size() == 1) {
                for (String other : g.getValue()) if (!other.equals(req.get(0)) && target.remove(other)) replaced.add(other);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : List.copyOf(target)) {
                for (Object r : Json.arr(manifestModule(id).get("requires"), "requires")) changed |= target.add((String) r);
            }
        }
        List<String> ordered = new ArrayList<>();
        for (Map<String, Object> m : manifestModules()) if (target.contains(m.get("id"))) ordered.add((String) m.get("id"));
        return ordered;
    }

    /** --all：沒有變體的模組全裝；互斥組沿用已裝的變體，沒裝過才取 manifest 裡的第一個。 */
    private List<String> allTargets(List<String> installed) {
        List<String> r = new ArrayList<>();
        Map<String, String> chosen = new LinkedHashMap<>();
        for (Map<String, Object> m : manifestModules()) {
            String g = Json.str(m, "exclusiveGroup", null);
            if (g != null && installed.contains(m.get("id"))) chosen.putIfAbsent(g, (String) m.get("id"));
        }
        for (Map<String, Object> m : manifestModules()) {
            String g = Json.str(m, "exclusiveGroup", null);
            if (g == null) r.add((String) m.get("id"));
            else chosen.putIfAbsent(g, (String) m.get("id"));
        }
        r.addAll(chosen.values());
        return r;
    }

    static String agentArg(List<String> ids) {
        return "-javaagent:" + AGENT_REL + "=modules=" + String.join(",", ids);
    }

    /** 自家那一條：-javaagent: 指向（結尾是）.mdc-patches/MinidoracatClientPatches.jar，相對或絕對路徑都算。 */
    static boolean isOurArg(String a) {
        if (!a.regionMatches(true, 0, "-javaagent:", 0, 11)) return false;
        String path = a.substring(11);
        int eq = path.indexOf('=');
        if (eq >= 0) path = path.substring(0, eq);
        path = path.replace("\"", "").replace('\\', '/').toLowerCase(Locale.ROOT);
        String rel = AGENT_REL.toLowerCase(Locale.ROOT);
        return path.equals(rel) || path.endsWith("/" + rel);
    }

    static List<String> otherAgents(List<String> options) {
        List<String> r = new ArrayList<>();
        for (String o : options) if (o.matches("(?i)^-(javaagent|agentlib|agentpath):.*") && !isOurArg(o)) r.add(o);
        return r;
    }

    // ------------------------------------------------------------------ 遊戲資料夾檢查
    private InfoPlist readPlist() throws IOException {
        if (Files.isSymbolicLink(plistPath)) throw new Fail(CONFLICT, L("Info.plist 是符號連結，不會修改。", "Info.plist is a symbolic link; it will not be changed."));
        return InfoPlist.parse(Files.readAllBytes(plistPath));
    }

    private String restoreHint() {
        return L("請用 Steam 驗證遊戲檔案的完整性（Project Zomboid → 內容 → 已安裝的檔案 → 驗證遊戲檔案的完整性）後再執行一次。",
                "Verify the game files with Steam (Project Zomboid > Properties > Installed Files > Verify integrity of game files), then run this again.");
    }

    /** 安裝前提：Info.plist 可以只改自家那行、啟動器會 chdir 到 Contents/Java、classpath 上 projectzomboid.jar 前面不會有別的 jar。 */
    private void plistGate(InfoPlist p) throws IOException {
        if (p.problem != null) {
            throw new Fail(CONFLICT, L("Info.plist 的格式和遊戲原本的不同（" + p.problem + "），不知道怎麼安全地加上啟動參數。",
                    "Info.plist does not look like the game's own file (" + p.problem + "), so the launch option cannot be added safely. ") + restoreHint());
        }
        if (!WORKDIR.equals(p.workingDirectory)) {
            throw new Fail(CONFLICT, L("Info.plist 的 WorkingDirectory 不是 " + WORKDIR + "（目前：" + p.workingDirectory + "），修補的相對路徑會找不到。",
                    "WorkingDirectory in Info.plist is not " + WORKDIR + " (now: " + p.workingDirectory + "), so the patch's relative path would not resolve. ") + restoreHint());
        }
        List<String> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.jar")) {
            for (Path j : ds) if (!j.getFileName().toString().equals("projectzomboid.jar")) jars.add(j.getFileName().toString());
        }
        if (p.hasClassPath || !jars.isEmpty()) {
            throw new Fail(CONFLICT, L("遊戲的 classpath 不是原本的形狀（Info.plist 有 JVMClassPath，或 Contents/Java 裡多了 jar："
                    + String.join(", ", jars) + "），別的 jar 裡的 class 可能先被載入，修補無法確認遊戲版本。",
                    "The game's classpath is not the original (Info.plist has JVMClassPath, or Contents/Java has extra jars: "
                    + String.join(", ", jars) + "); classes from another jar may load first, so the patches cannot check the game version. ") + restoreHint());
        }
    }

    private static Set<String> jarPackages(Path jar) {
        Set<String> set = new TreeSet<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                String n = e.nextElement().getName();
                int s = n.lastIndexOf('/');
                if (s > 0 && n.endsWith(".class") && !n.startsWith("META-INF/")) set.add(n.substring(0, s));
            }
        } catch (IOException e) {
            throw new Fail(JAR, "cannot read projectzomboid.jar: " + e.getMessage());
        }
        if (set.isEmpty()) throw new Fail(JAR, "projectzomboid.jar has no classes");
        return set;
    }

    /** jar 裡有的頂層 package 目錄底下的 .class（遊戲根相對、正斜線）。Mac 沒出過 loose class 版，全部都是外來的。 */
    private List<String> looseClasses(Set<String> packages) throws IOException {
        Set<String> tops = new TreeSet<>();
        for (String p : packages) tops.add(p.split("/")[0]);
        List<String> found = new ArrayList<>();
        for (String t : tops) {
            Path d = root.resolve(t);
            if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                s.filter(f -> f.toString().endsWith(".class") && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                        .forEach(f -> found.add(root.relativize(f).toString().replace('\\', '/')));
            }
        }
        found.sort(null);
        return found;
    }

    /** PZ_Optimization（42.22 起是 Java agent）換掉的 class：agent jar 在場時 pzopt/overrides/ 底下的 .class（遊戲根相對、正斜線）；agent jar 不在就當沒有。 */
    private List<String> pzoptOverrides() throws IOException {
        Path d = root.resolve(PZOPT_OVERRIDES);
        if (!Files.isRegularFile(root.resolve("pzopt/agent/pzopt-agent.jar"), LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(d)) {
            s.filter(f -> f.toString().endsWith(".class") && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .forEach(f -> found.add(root.relativize(f).toString().replace('\\', '/')));
        }
        found.sort(null);
        return found;
    }

    static final String PZOPT_OVERRIDES = "pzopt/overrides/";

    /** 蓋到這些模組要換的 class（X.class、X$*.class，大小寫不分）或落在 zombie/mdc/ 的外來 class，加上 PZ_Optimization 也換的這類 class。 */
    private List<String> conflicts(List<String> loose, List<String> targetIds) throws IOException {
        Set<String> patched = new TreeSet<>();
        for (String id : targetIds) {
            for (Object c : Json.arr(manifestModule(id).get("patchedClasses"), "patchedClasses")) {
                patched.add(((String) c).split("\\$")[0].toLowerCase(Locale.ROOT));
            }
        }
        List<String> r = new ArrayList<>();
        for (String f : loose) {
            String lower = f.toLowerCase(Locale.ROOT);
            String outer = lower.substring(0, lower.length() - ".class".length()).split("\\$")[0];
            if (lower.startsWith("zombie/mdc/") || patched.contains(outer)) r.add(f);
        }
        for (String f : pzoptOverrides()) {
            String lower = f.substring(PZOPT_OVERRIDES.length()).toLowerCase(Locale.ROOT);
            if (patched.contains(lower.substring(0, lower.length() - ".class".length()).split("\\$")[0])) r.add(f);
        }
        return r;
    }

    /** 測試用開關（不從命令列或系統屬性讀，出貨時改不到）：建置機上別的 session 開著的 PZ 不該讓測試失敗。 */
    static boolean checkProcesses = true;
    /** 測試用：jar|state|plist，在那一步換檔前中斷。 */
    static String failAt;

    private void assertNotRunning() {
        if (!checkProcesses) return;
        List<String> hits = new ArrayList<>();
        Set<Long> skip = new LinkedHashSet<>();
        for (ProcessHandle h = ProcessHandle.current(); h != null; h = h.parent().orElse(null)) skip.add(h.pid());
        try (Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
            for (ProcessHandle h : (Iterable<ProcessHandle>) all::iterator) {
                if (skip.contains(h.pid())) continue;
                ProcessHandle.Info i = h.info();
                // 讀不到資訊的多半是別的使用者（root）的行程；遊戲以目前使用者執行，讀得到
                if (isGameProcess(i.command().orElse(""), i.commandLine().orElse(""), app)) hits.add(i.command().orElse("?") + " (PID " + h.pid() + ")");
            }
        } catch (RuntimeException e) {
            throw new Fail(RUNNING, L("無法列舉系統行程，無法確認遊戲已關閉：", "Cannot list processes, so cannot confirm the game is closed: ") + e.getMessage());
        }
        if (!hits.isEmpty()) {
            throw new Fail(RUNNING, L("Project Zomboid（或另一個修補管理器）還在執行，請先完全關閉：", "Project Zomboid (or another copy of this manager) is still running; close it completely first: ") + String.join(", ", hits));
        }
    }

    /** 遊戲（或用遊戲 JRE 跑的另一個管理器）：執行檔在這個 app 的 MacOS／PlugIns 底下，或是別份 PZ 的啟動器，或命令列帶遊戲主類別。 */
    static boolean isGameProcess(String exe, String cmdline, Path app) {
        if (!exe.isEmpty()) {
            String e = exe.replace('\\', '/');
            String a = app.toString().replace('\\', '/');
            if (e.startsWith(a + "/Contents/MacOS/") || e.startsWith(a + "/Contents/PlugIns/")) return true;
            Path p = Path.of(exe);
            if (p.getFileName() != null && p.getFileName().toString().equals("JavaAppLauncher") && p.getParent() != null
                    && Files.isRegularFile(p.getParent().resolveSibling("Java").resolve("projectzomboid.jar"))) {
                return true;
            }
        }
        return cmdline.contains("zombie.gameStates.MainScreenState") || cmdline.contains("zombie.network.GameServer");
    }

    private FileLock lock() throws IOException {
        Path lock = root.resolve(".mdc-patches.lock");
        FileChannel ch;
        try {
            ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw writeFail(e);
        }
        FileLock l;
        try {
            l = ch.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            l = null;
        }
        if (l == null) {
            ch.close();
            throw new Fail(RUNNING, L("另一個修補管理器正在處理這個遊戲。", "Another copy of this manager is working on this game."));
        }
        return l;
    }

    private void unlock(FileLock l) {
        try {
            Path p = root.resolve(".mdc-patches.lock");
            l.release();
            l.channel().close();
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 鎖檔留著無害：下次 tryLock 照樣拿得到
        }
    }

    private Fail writeFail(IOException e) {
        return new Fail(WRITE, L("寫不進遊戲 app（" + e + "）。macOS 可能擋下了終端機修改其他 app：請到「系統設定 → 隱私權與安全性 → App 管理」允許「終端機」後再試。",
                "Cannot write into the game app (" + e + "). macOS may block Terminal from changing other apps: allow Terminal under System Settings > Privacy & Security > App Management, then try again."));
    }

    private void writeProbe() {
        for (Path d : List.of(contents, root)) {
            try {
                Files.delete(Files.createTempFile(d, ".mdc-probe", null));
            } catch (IOException e) {
                throw writeFail(e);
            }
        }
    }

    // ------------------------------------------------------------------ 寫檔
    private static final String NEW = ".mdc-new";

    /** 暫存檔（同資料夾）寫入並驗 SHA，再原子改名；權限照原檔。 */
    private void publish(Path target, byte[] data, String step) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + NEW);
        Files.deleteIfExists(tmp);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) ch.write(b);
            ch.force(true);
        }
        if (Files.exists(target) && Files.getFileAttributeView(target, PosixFileAttributeView.class) != null) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
            Files.setPosixFilePermissions(tmp, perms);
        }
        if (!sha256(tmp).equals(sha256(data))) throw new IOException("verification failed for " + tmp);
        injectFailure(step);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void remove(Path target, String step) throws IOException {
        injectFailure(step);
        Files.deleteIfExists(target);
    }

    private static void injectFailure(String step) throws IOException {
        if (step.equals(failAt)) throw new IOException("injected failure at " + step);
    }

    private void clearLeftovers() throws IOException {
        Files.deleteIfExists(plistPath.resolveSibling("Info.plist" + NEW));
        Files.deleteIfExists(root.resolve(AGENT_REL + NEW));
        Files.deleteIfExists(root.resolve(STATE_REL + NEW));
    }

    private byte[] stateBytes(String jarSha, String packageVersion, String agentSha, List<Map<String, Object>> modules) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("schemaVersion", 2L);
        s.put("jarSha256", jarSha);
        s.put("packageVersion", packageVersion);
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("path", AGENT_REL);
        agent.put("sha256", agentSha);
        s.put("agent", agent);
        s.put("modules", modules);
        return Json.write(s).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readOrNull(Path p) throws IOException {
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(p) : null;
    }

    private boolean confirm(String prompt) {
        if (!interactive) return true;
        String a = ask(prompt);
        return a != null && (a.equals("Y") || a.equals("y"));
    }

    // ------------------------------------------------------------------ quick 與記憶體
    /** quick 動作（明確帶 quick 時才用；一行指令預設開選單）：沿用已裝的模組（更新到這個包）；沒有任何客戶端修復變體就補建議的那個；完整版裝不上時改相容版。 */
    private List<String> quickTargets() throws IOException {
        List<String> req = new ArrayList<>(moduleIds(readState()));
        req.removeIf(id -> manifestModule(id) == null);
        if (req.stream().noneMatch(id -> id.startsWith("client-fixes-"))) req.add(ramGb >= 32 ? "client-fixes-standard" : "client-fixes-lowmem");
        if (!compatAdvice(req).isEmpty()) {
            req.replaceAll(id -> id.startsWith("client-fixes-") ? COMPAT : id);
            info(L("  遊戲裡已有其他 MOD 替換的遊戲 class，改裝相容版（只含車輛的修補）。", "  Another mod already replaces game classes; installing the compatible version (vehicle fixes only)."));
        }
        return req;
    }

    private static final String HEAP_SIZE = "(\\d{1,7}[mM]|\\d{1,5}[gG])";

    /** 恰好一條 -Xmx、格式是 -Xmx&lt;數字&gt;m 或 g、而且沒有 -XX:MaxHeapSize 時回傳它的位置，否則 -1（不認得就不碰）。 */
    static int xmxIndex(List<String> options) {
        int found = -1;
        for (int k = 0; k < options.size(); k++) {
            if (options.get(k).startsWith("-XX:MaxHeapSize")) return -1;
            if (!options.get(k).startsWith("-Xmx")) continue;
            if (found >= 0) return -1;
            found = k;
        }
        return found >= 0 && options.get(found).matches("-Xmx" + HEAP_SIZE) ? found : -1;
    }

    /** -Xmx 或 -Xms 的值換成 MB（呼叫端先確認格式符合 HEAP_SIZE）。 */
    static int xmxMb(String option) {
        int n = Integer.parseInt(option.substring(4, option.length() - 1));
        return Character.toLowerCase(option.charAt(option.length() - 1)) == 'g' ? n * 1024 : n;
    }

    /** 依實體記憶體給的建議上限；只有 8192 在 32 GB 的 Mac 連正式服實測過，6144／4096 是推測。0＝不建議調整。 */
    static int recommendedMb(int ramGb) {
        return ramGb >= 32 ? 8192 : ramGb >= 16 ? 6144 : ramGb >= 12 ? 4096 : 0;
    }

    /** 改成 mb 會讓 JVM 起不來（初始堆積大於上限，或認不得）時回原因，否則 null。 */
    private String memoryBlocker(List<String> options, int mb) {
        for (String o : options) {
            boolean initial = o.startsWith("-Xms") || o.startsWith("-XX:InitialHeapSize") || o.startsWith("-XX:MinHeapSize");
            if (initial && !(o.matches("-Xms" + HEAP_SIZE) && xmxMb(o) <= mb)) {
                return L("Info.plist 的 " + o + " 大於 " + mb + " MB 或認不得，改成這個上限遊戲會開不起來。",
                        "Info.plist has " + o + ", which is larger than " + mb + " MB or not understood; the game would not start with that limit.");
            }
        }
        return null;
    }

    /** 要把 -Xmx 改成多少 MB；null＝不動。--memory-mb 照做；互動時目前值低於建議值才問，Return 或 Y＝調高。 */
    private Integer memoryChoice(InfoPlist plist) {
        int xi = xmxIndex(plist.options);
        if (memoryMb != null) {
            if (xi < 0) {
                throw new Fail(CONFLICT, L("Info.plist 找不到可以調整的記憶體上限（要剛好一條 -Xmx，格式像 -Xmx3072m），沒辦法用 --memory-mb。",
                        "Info.plist has no memory limit this installer can change (exactly one -Xmx, like -Xmx3072m), so --memory-mb cannot be used."));
            }
            String why = memoryBlocker(plist.options, memoryMb); // 調低到初始堆積以下 JVM 會拒絕啟動
            if (why != null) throw new Fail(CONFLICT, why);
            return memoryMb == xmxMb(plist.options.get(xi)) ? null : memoryMb;
        }
        if (!interactive || xi < 0) return null;
        int cur = xmxMb(plist.options.get(xi)), rec = recommendedMb(ramGb);
        if (rec <= cur) return null;
        head(L("遊戲記憶體上限", "Game memory limit"));
        info(L("  遊戲目前最多只用 " + cur + " MB 記憶體（Info.plist 的 " + plist.options.get(xi) + "）。MOD 多的伺服器不夠用，連線幾分鐘後會閃退（console 出現 OutOfMemoryError）。",
                "  The game can use at most " + cur + " MB of memory now (" + plist.options.get(xi) + " in Info.plist). On a server with many mods that is not enough, and the game closes a few minutes after joining (OutOfMemoryError in the console)."));
        info(L("  這台 Mac 約有 " + ramGb + " GB 記憶體，建議調到 " + rec + " MB。這是遊戲本身的設定：移除修補時不會改回；Steam 更新或驗證遊戲檔案後會回到 " + VANILLA_MEMORY_MB + " MB，到時再執行一次安裝會再問。",
                "  This Mac has about " + ramGb + " GB RAM; " + rec + " MB is recommended. It is the game's own setting: removing the patches does not change it back; a Steam update or file verification resets it to " + VANILLA_MEMORY_MB + " MB, and installing again asks again."));
        String a = choice(L("要調高嗎？直接按 Return 或輸入 Y＝調高，輸入 N＝不調", "Raise it? Press Return or type Y to raise it, N to keep it"), List.of("Y", "N"), "Y");
        return a.equals("Y") ? rec : null;
    }

    // ------------------------------------------------------------------ install
    private void install(List<String> requested, boolean all) throws IOException {
        if (!all && requested.isEmpty()) throw new Fail(USAGE, L("沒有指定要安裝的模組（--modules id,... 或 --all）。", "No modules given (--modules id,... or --all)."));
        FileLock lock = lock();
        try {
            assertNotRunning();
            InfoPlist plist = readPlist();
            plistGate(plist);
            Path jar = root.resolve("projectzomboid.jar");
            String jarSha = sha256(jar);
            String want = Json.str(manifest, "jarSha256");
            if (!jarSha.equals(want)) {
                throw new Fail(JAR, L("遊戲版本不符：這個安裝包只適用 ", "Game version mismatch: this package only works with ")
                        + Json.str(manifest, "gameVersion", "?") + "\n  " + L("期望 jar SHA256：", "Expected jar SHA256: ") + want
                        + "\n  " + L("實際 jar SHA256：", "Actual jar SHA256:   ") + jarSha);
            }
            Path pkgAgent = packageAgent();
            String newAgentSha = Json.str(Json.obj(manifest.get("agent"), "agent"), "sha256");
            Map<String, Object> state = readState();
            List<String> installed = moduleIds(state);
            List<String> req = all ? allTargets(installed) : requested;
            List<String> replaced = new ArrayList<>();
            List<String> target = resolveInstall(installed, req, replaced);

            Path agentPath = root.resolve(AGENT_REL);
            if (Files.isSymbolicLink(root.resolve(DIR_REL)) || Files.isSymbolicLink(agentPath)) {
                throw new Fail(CONFLICT, L(DIR_REL + " 是符號連結，不會處理。", DIR_REL + " is a symbolic link; it will not be touched."));
            }
            byte[] curAgent = readOrNull(agentPath);
            String curAgentSha = curAgent == null ? null : sha256(curAgent);
            if (curAgentSha != null && !curAgentSha.equals(newAgentSha) && !curAgentSha.equals(stateAgentSha(state))) {
                throw new Fail(TAMPERED, L(AGENT_REL + " 的內容和安裝紀錄、這個安裝包都不同，不會覆蓋。請手動確認並刪除，之後馬上再執行一次安裝或「全部移除」（Info.plist 還指著它時，刪掉後遊戲會開不起來）。",
                        AGENT_REL + " matches neither the install record nor this package, so it will not be overwritten. Check it and delete it by hand, then run install or \"Remove everything\" again right away (while Info.plist still points to it, the game will not start once it is deleted)."));
            }

            Set<String> packages = jarPackages(jar);
            List<String> loose = looseClasses(packages);
            List<String> conflicts = conflicts(loose, target);

            head(L("安裝計畫", "Install plan"));
            info(L("  遊戲：", "  Game: ") + app);
            info(L("  安裝包版本：", "  Package version: ") + Json.str(manifest, "packageVersion", "?") + L("（遊戲 ", " (game ") + Json.str(manifest, "gameVersion", "?") + L("）", ")"));
            info(L("  安裝後的模組：", "  Modules after install: ") + labels(target));
            if (!replaced.isEmpty()) info(L("  取代變體：", "  Replacing: ") + labels(replaced));
            info(L("  啟動參數（Info.plist 的 JVMOptions 第一條）：", "  Launch option (first entry of JVMOptions in Info.plist): ") + agentArg(target));
            List<String> warnings = new ArrayList<>(loose);
            warnings.removeAll(conflicts);
            if (!warnings.isEmpty()) foreignWarning(warnings);
            otherAgentsNote(otherAgents(plist.options));
            if (!conflicts.isEmpty()) {
                head(L("偵測到衝突，已中止（沒有任何檔案被更動）", "Conflict found; stopped (no files were changed)"));
                for (String c : conflicts) bad(c.startsWith(PZOPT_OVERRIDES)
                        ? L("PZ_Optimization 也換本包要修補的 class，而且它先換，本包換不上：", "PZ_Optimization also replaces a class this package patches, and it does so first, so the patch cannot be applied: ") + c
                        : L("外來的 class 檔會蓋掉本包要修補的 class：", "A class file from elsewhere would replace a class this package patches: ") + c);
                String why = L("Contents/Java 裡有不是本安裝器裝的 class 檔（直接放在 Contents/Java，或在 PZ_Optimization 的 pzopt/overrides 裡），通常來自另一個替換遊戲 class 的 MOD。不要只刪除上面列出的檔案：那個 MOD 的其他檔案還需要它們。",
                        "Contents/Java has class files this installer did not install (directly in Contents/Java, or in PZ_Optimization's pzopt/overrides), usually from another mod that replaces game classes. Do not delete just the files listed above: that mod's other files still need them. ");
                if (compatFits(installed, req, loose)) {
                    throw new Fail(CONFLICT, why + L("要和它一起用，請改裝相容版（只含車輛的修補）：在安裝選單選相容版，或用 --modules " + COMPAT + "。要完整版，請先用那個 MOD 自己的方式把它完整移除。",
                            "To use both, install the compatible version instead (vehicle fixes only): choose it in the install menu, or use --modules " + COMPAT + ". For the full version, remove that mod completely first."));
                }
                throw new Fail(CONFLICT, why + L("請先把那個 MOD 完整移除後再安裝，或保留它、不裝本包。", "Remove that mod completely, then install again; or keep it and do not install this package."));
            }
            Integer newMb = memoryChoice(plist);
            if (newMb != null) info(L("  遊戲記憶體上限：", "  Game memory limit: ") + xmxMb(plist.options.get(xmxIndex(plist.options))) + " → " + newMb + " MB");

            List<Map<String, Object>> entries = new ArrayList<>();
            for (String id : target) {
                Map<String, Object> mod = manifestModule(id);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", id);
                e.put("name", Json.str(mod, "name"));
                e.put("version", Json.str(mod, "version"));
                e.put("requires", new ArrayList<>(Json.arr(mod.get("requires"), "requires")));
                entries.add(e);
            }
            byte[] newState = stateBytes(jarSha, Json.str(manifest, "packageVersion", ""), newAgentSha, entries);
            byte[] newPlist = plist.edit(MacInstaller::isOurArg, agentArg(target));
            if (newMb != null) {
                InfoPlist base = newPlist == null ? plist : InfoPlist.parse(newPlist);
                newPlist = base.replace(xmxIndex(base.options), "-Xmx" + newMb + "m");
            }
            byte[] curState = readOrNull(root.resolve(STATE_REL));
            boolean agentSame = newAgentSha.equals(curAgentSha);
            boolean stateSame = curState != null && java.util.Arrays.equals(curState, newState);
            if (agentSame && stateSame && newPlist == null) {
                good(L("目前狀態已經是目標狀態，不需要任何變更。", "Already up to date; nothing to change."));
                return;
            }
            if (!confirm(L("確定要安裝嗎？輸入 Y 按 Return 開始（輸入 N 取消）", "Install now? Type Y and press Return to start (N to cancel)"))) {
                info(L("已取消。", "Cancelled."));
                return;
            }
            writeProbe();
            try {
                clearLeftovers();
                Files.createDirectories(root.resolve(DIR_REL));
                if (!agentSame) publish(agentPath, Files.readAllBytes(pkgAgent), "jar");
                if (!stateSame) publish(root.resolve(STATE_REL), newState, "state");
                if (newPlist != null) publish(plistPath, newPlist, "plist");
            } catch (IOException e) {
                throw new Fail(WRITE, L("寫入中斷（" + e.getMessage() + "）。已完成的步驟不會讓遊戲開不起來；請再執行一次安裝或移除。",
                        "Writing was interrupted (" + e.getMessage() + "). The steps already done do not stop the game from starting; run install or remove again."));
            }
            good(L("完成：已安裝 " + target.size() + " 個模組。照平常從 Steam 開遊戲即可。",
                    "Done: " + target.size() + " module(s) installed. Start the game from Steam as usual."));
        } finally {
            unlock(lock);
        }
    }

    private boolean compatFits(List<String> installed, List<String> requested, List<String> loose) throws IOException {
        if (manifestModule(COMPAT) == null) return false;
        if (requested.stream().noneMatch(r -> r.startsWith("client-fixes-") && !r.equals(COMPAT))) return false;
        List<String> swapped = new ArrayList<>();
        for (String r : requested) swapped.add(r.startsWith("client-fixes-") ? COMPAT : r);
        List<String> ids = resolveInstall(installed, swapped, new ArrayList<>());
        return conflicts(loose, ids).isEmpty();
    }

    private void foreignWarning(List<String> paths) {
        warn(L("Contents/Java 裡有 " + paths.size() + " 個不是本包的 class 檔（例如 " + paths.get(0) + "）。42.22 起遊戲 jar 有簽章，這類檔案本身就會讓遊戲丟 SecurityException，和本包無關。",
                "Contents/Java has " + paths.size() + " class file(s) that are not from this package (for example " + paths.get(0) + "). Since 42.22 the game jar is signed and such files make the game throw a SecurityException on their own, with or without this package."));
    }

    private void otherAgentsNote(List<String> agents) {
        if (agents.isEmpty()) return;
        warn(L("Info.plist 還有其他 Java agent（照樣保留；不支援和它們修改同一個 class）：", "Info.plist also has other Java agents (kept as they are; changing the same class as them is not supported): ") + String.join("  ", agents));
    }

    // ------------------------------------------------------------------ uninstall
    private void uninstall(List<String> requested, boolean all) throws IOException {
        FileLock lock = lock();
        try {
            assertNotRunning();
            InfoPlist plist = readPlist();
            Map<String, Object> state = readState();
            List<String> installed = moduleIds(state);
            for (String r : requested) {
                if (!installed.contains(r)) throw new Fail(USAGE, L("沒有安裝這個模組：", "This module is not installed: ") + r);
            }
            List<String> keep = new ArrayList<>(installed);
            keep.removeAll(requested);
            if (all || keep.isEmpty()) {
                removeAll(plist, state);
                return;
            }
            // 仍被保留模組依賴的（例如 core）自動保留
            List<Map<String, Object>> entries = new ArrayList<>();
            Set<String> need = new LinkedHashSet<>(keep);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Object o : Json.arr(state.get("modules"), "modules")) {
                    Map<String, Object> m = Json.obj(o, "module");
                    if (need.contains(m.get("id"))) for (Object r : Json.arr(m.get("requires"), "requires")) changed |= need.add((String) r);
                }
            }
            List<String> kept = new ArrayList<>();
            for (Object o : Json.arr(state.get("modules"), "modules")) {
                Map<String, Object> m = Json.obj(o, "module");
                if (need.contains(m.get("id"))) {
                    entries.add(m);
                    kept.add((String) m.get("id"));
                }
            }
            for (String r : requested) if (kept.contains(r)) info(L("  " + label(r) + " 仍被其他模組需要，保留。", "  " + label(r) + " is still needed by another module; kept."));
            if (plist.problem != null) {
                throw new Fail(CONFLICT, L("Info.plist 的格式和遊戲原本的不同（" + plist.problem + "），無法改啟動參數。", "Info.plist does not look like the game's own file (" + plist.problem + "), so the launch option cannot be changed. ") + restoreHint());
            }
            boolean active = plist.options.stream().anyMatch(MacInstaller::isOurArg);
            // Steam 換回原版 plist 時（沒有自家參數）維持沒啟用，不替玩家加回去
            byte[] newPlist = active ? plist.edit(MacInstaller::isOurArg, agentArg(kept)) : null;
            byte[] newState = stateBytes(Json.str(state, "jarSha256"), Json.str(state, "packageVersion", ""), stateAgentSha(state), entries);
            head(L("移除計畫", "Removal plan"));
            info(L("  移除：", "  Removing: ") + labels(requested.stream().filter(r -> !kept.contains(r)).toList()));
            info(L("  保留：", "  Keeping: ") + labels(kept));
            if (!confirm(L("確定要移除嗎？輸入 Y 按 Return 開始（輸入 N 取消）", "Remove now? Type Y and press Return to start (N to cancel)"))) {
                info(L("已取消。", "Cancelled."));
                return;
            }
            writeProbe();
            try {
                clearLeftovers();
                if (newPlist != null) publish(plistPath, newPlist, "plist");
                publish(root.resolve(STATE_REL), newState, "state");
            } catch (IOException e) {
                throw new Fail(WRITE, L("寫入中斷（" + e.getMessage() + "）。請再執行一次移除。", "Writing was interrupted (" + e.getMessage() + "). Run remove again."));
            }
            good(L("完成：已移除，剩下 " + labels(kept) + "。", "Done: removed; remaining: " + labels(kept) + "."));
        } finally {
            unlock(lock);
        }
    }

    private void removeAll(InfoPlist plist, Map<String, Object> state) throws IOException {
        Path agentPath = root.resolve(AGENT_REL);
        byte[] cur = readOrNull(agentPath);
        if (cur != null) {
            String sha = sha256(cur);
            String pkgSha = manifest == null ? null : Json.str(Json.obj(manifest.get("agent"), "agent"), "sha256");
            if (!sha.equals(stateAgentSha(state)) && !sha.equals(pkgSha)) {
                throw new Fail(TAMPERED, L(AGENT_REL + " 的內容和安裝紀錄、這個安裝包都不同，不會自動刪除。請手動確認並刪除，之後再執行一次「全部移除」拿掉 Info.plist 的啟動參數。",
                        AGENT_REL + " matches neither the install record nor this package, so it will not be deleted automatically. Check it and delete it by hand, then run \"Remove everything\" again to take the launch option out of Info.plist."));
            }
        }
        byte[] newPlist = null;
        if (plist.problem == null) {
            newPlist = plist.edit(MacInstaller::isOurArg, null);
        } else if (plist.text == null || plist.text.contains(AGENT_NAME)) {
            throw new Fail(CONFLICT, L("Info.plist 的格式和遊戲原本的不同（" + plist.problem + "），裡面可能還有本包的啟動參數，沒辦法安全地拿掉。",
                    "Info.plist does not look like the game's own file (" + plist.problem + ") and may still have this package's launch option, which cannot be removed safely. ") + restoreHint());
        }
        Path dir = root.resolve(DIR_REL);
        boolean any = newPlist != null || state != null || cur != null || Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
        if (!any) {
            good(L("沒有安裝任何修補，不需要移除。", "Nothing is installed; nothing to remove."));
            return;
        }
        head(L("全部移除", "Remove everything"));
        info(L("  拿掉 Info.plist 裡本包的啟動參數，刪除 Contents/Java/" + DIR_REL + "。", "  Removes this package's launch option from Info.plist and deletes Contents/Java/" + DIR_REL + "."));
        if (!confirm(L("確定要全部移除嗎？輸入 Y 按 Return 開始（輸入 N 取消）", "Remove everything now? Type Y and press Return to start (N to cancel)"))) {
            info(L("已取消。", "Cancelled."));
            return;
        }
        writeProbe();
        try {
            clearLeftovers();
            if (newPlist != null) publish(plistPath, newPlist, "plist");
            remove(root.resolve(STATE_REL), "state");
            remove(agentPath, "jar");
            if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> s = Files.list(dir)) {
                    if (s.findAny().isEmpty()) Files.delete(dir);
                    else warn(L(DIR_REL + " 裡還有不是本包的檔案，資料夾保留：", DIR_REL + " still has files that are not from this package; the folder is kept: ") + dir);
                }
            }
        } catch (IOException e) {
            throw new Fail(WRITE, L("寫入中斷（" + e.getMessage() + "）。請再執行一次移除。", "Writing was interrupted (" + e.getMessage() + "). Run remove again."));
        }
        good(L("完成：已全部移除，遊戲會照原版啟動。", "Done: everything was removed; the game starts unpatched."));
        int xi = plist.problem == null ? xmxIndex(plist.options) : -1;
        if (xi >= 0 && xmxMb(plist.options.get(xi)) != VANILLA_MEMORY_MB) {
            info(L("  遊戲記憶體上限維持 " + xmxMb(plist.options.get(xi)) + " MB：這是遊戲本身的設定，移除修補不會改它。要回到原本的 " + VANILLA_MEMORY_MB + " MB，用 Steam 驗證遊戲檔案的完整性。",
                    "  The game memory limit stays at " + xmxMb(plist.options.get(xi)) + " MB: it is the game's own setting, and removing the patches does not change it. To go back to " + VANILLA_MEMORY_MB + " MB, verify the game files with Steam."));
        }
    }

    // ------------------------------------------------------------------ status
    private void status() throws IOException {
        head(L("Minidoracat client patch 狀態", "Minidoracat client patch status"));
        info(L("  遊戲：", "  Game: ") + app);
        InfoPlist plist = readPlist();
        if (plist.problem != null) warn(L("Info.plist 格式和遊戲原本的不同：", "Info.plist does not look like the game's own file: ") + plist.problem);
        Path jar = root.resolve("projectzomboid.jar");
        String jarSha = sha256(jar);
        info(L("  遊戲 jar SHA256：", "  Game jar SHA256: ") + jarSha);
        int xi = plist.problem == null ? xmxIndex(plist.options) : -1;
        info(L("  遊戲記憶體上限：", "  Game memory limit: ") + (xi >= 0 ? xmxMb(plist.options.get(xi)) + " MB" : L("無法判斷", "unknown")));
        Map<String, Object> state = readState();
        List<String> ids = moduleIds(state);
        List<String> ours = plist.problem == null ? plist.options.stream().filter(MacInstaller::isOurArg).toList() : List.of();
        if (ids.isEmpty()) {
            info(L("  安裝狀態：未安裝任何模組", "  Install status: no modules installed"));
            if (!ours.isEmpty()) warn(L("Info.plist 有本包的啟動參數，但沒有安裝紀錄：遊戲可能開不起來，請執行「全部移除」。", "Info.plist has this package's launch option but there is no install record: the game may not start; run \"Remove everything\". ") + String.join(" ", ours));
        } else {
            info(L("  安裝包版本：", "  Package version: ") + Json.str(state, "packageVersion", "?"));
            Path agent = root.resolve(AGENT_REL);
            boolean fileOk = Files.isRegularFile(agent, LinkOption.NOFOLLOW_LINKS) && sha256(agent).equals(stateAgentSha(state));
            boolean jarOk = jarSha.equals(Json.str(state, "jarSha256", ""));
            String expected = agentArg(ids);
            boolean argOk = ours.size() == 1 && ours.get(0).equals(expected);
            String mark = !fileOk ? L("異常（修補檔缺少或被改過）", "PROBLEM (patch file missing or modified)")
                    : plist.problem != null ? L("無法判斷是否啟用（Info.plist 讀不懂）", "cannot tell whether active (Info.plist not readable)")
                    : jarOk && argOk ? L("正常", "OK") : L("沒有啟用", "not active");
            for (String id : ids) info("    [" + L("已安裝", "Installed") + "] " + label(id) + " (" + id + ") — " + mark);
            if (!fileOk) warn(L(AGENT_REL + " 缺少或和安裝紀錄不符。Info.plist 還指著它時遊戲會開不起來；請重新安裝，或全部移除。", AGENT_REL + " is missing or does not match the install record. While Info.plist still points to it the game will not start; install again, or remove everything."));
            if (!jarOk) warn(L("遊戲已經更新（projectzomboid.jar 與安裝時不同）：修補會自己停用、遊戲照原版跑。請安裝對應新版的修補包。", "The game has been updated (projectzomboid.jar differs from install time): the patches switch themselves off and the game runs unpatched. Install the package for the new version."));
            if (ours.isEmpty() && plist.problem == null) {
                warn(L("已安裝但沒有啟用：Info.plist 裡沒有本包的啟動參數（通常是 Steam 更新或驗證遊戲檔案時換回了原版）。版本沒變的話，重新安裝一次就會補回去。", "Installed but not active: Info.plist does not have this package's launch option (usually because a Steam update or file verification put the original back). If the game version did not change, installing again puts it back."));
            } else if (!argOk && plist.problem == null) {
                warn(L("啟動參數和安裝紀錄不符，請重新安裝一次。目前：", "The launch option does not match the install record; install again. Now: ") + String.join(" ", ours));
            } else if (argOk && fileOk && jarOk) {
                info(L("  啟用狀態：已啟用（", "  Active: yes (") + expected + L("）", ")"));
            }
        }
        List<String> loose = looseClasses(jarPackages(jar));
        if (!loose.isEmpty()) foreignWarning(loose);
        otherAgentsNote(otherAgents(plist.options));
        if (manifest != null) {
            List<String> pzopt = conflicts(List.of(), ids.stream().filter(id -> manifestModule(id) != null).toList());
            if (!pzopt.isEmpty()) warn(L("PZ_Optimization 也換已安裝的修補要換的 class（例如 " + pzopt.get(0) + "）：兩個一起用時本包會整包不啟用或部分換不上。請重新執行安裝器，改裝相容版（只含車輛的修補，可以和它一起用）。",
                    "PZ_Optimization also replaces classes the installed patches replace (for example " + pzopt.get(0) + "): with both, this package stays off or is only partly applied. Run the installer again and install the compatible version instead (vehicle fixes only; it works with PZ_Optimization)."));
            info(L("  安裝包可提供：", "  This package offers: ") + Json.str(manifest, "packageVersion", "?") + L("（遊戲 ", " (game ") + Json.str(manifest, "gameVersion", "?") + L("）", ")"));
            for (Map<String, Object> m : manifestModules()) {
                String id = (String) m.get("id");
                info("    [" + (ids.contains(id) ? L("已安裝", "Installed") : L("可安裝", "Available")) + "] " + label(id) + " (" + id + ") " + Json.str(m, "version", ""));
            }
            if (!jarSha.equals(Json.str(manifest, "jarSha256"))) warn(L("目前遊戲版本與這個安裝包不符，無法安裝（請取得對應版本的安裝包）。", "The current game version does not match this package, so it cannot be installed (get the package for your game version)."));
        }
    }

    // ------------------------------------------------------------------ 互動選單
    private String choice(String prompt, List<String> valid, String dflt) {
        while (true) {
            String a = ask(prompt);
            if (a == null) return "0";
            if (a.isEmpty() && dflt != null) return dflt;
            for (String v : valid) if (v.equalsIgnoreCase(a)) return v;
            bad(L("請輸入：", "Please enter: ") + String.join(" / ", valid));
        }
    }

    private List<String> selectInstall() throws IOException {
        List<String> variants = new ArrayList<>();
        boolean hasProfiler = false;
        for (Map<String, Object> m : manifestModules()) {
            String id = (String) m.get("id");
            if (id.startsWith("client-fixes-")) variants.add(id);
            if (id.equals("profiler")) hasProfiler = true;
        }
        head(L("要安裝什麼？", "What do you want to install?"));
        List<String> opts = new ArrayList<>(List.of("0"));
        if (!variants.isEmpty()) {
            info(L("  [1] 客戶端修復：隱形、自建房斷線、開車瞬移等（一般玩家選這個）", "  [1] Client fixes: invisible entities, player-built room disconnects, vehicle teleports and more (choose this)"));
            opts.add("1");
        }
        if (hasProfiler) {
            info(L("  [2] 開發者工具 DevProfiler（一般玩家不用裝）", "  [2] DevProfiler developer tool (not needed for players)"));
            opts.add("2");
        }
        if (hasProfiler && !variants.isEmpty()) {
            info(L("  [3] 兩個都裝（開發者用）", "  [3] Both (developers)"));
            opts.add("3");
        }
        info(L("  [0] 返回", "  [0] Back"));
        String c = choice(L("請輸入數字後按 Return", "Type a number and press Return"), opts, null);
        List<String> picked = new ArrayList<>();
        if (c.equals("0")) return picked;
        if (c.equals("2") || c.equals("3")) picked.add("profiler");
        if (c.equals("1") || c.equals("3")) {
            head(L("要用哪個版本？", "Which version?"));
            List<String> vopts = new ArrayList<>();
            String recommend = null, compatPick = null;
            for (int k = 0; k < variants.size(); k++) {
                String v = variants.get(k), n = String.valueOf(k + 1);
                String text = switch (v) {
                    case "client-fixes-standard" -> L("標準版（電腦記憶體 32GB 以上）", "Standard (32 GB RAM or more)");
                    case "client-fixes-lowmem" -> L("省記憶體版（電腦記憶體 32GB 以下）", "Low-memory (less than 32 GB RAM)");
                    case COMPAT -> L("相容版：只含車輛的修補（遊戲裡已有其他 MOD 替換的遊戲 class 時選這個）", "Compatible: vehicle fixes only (choose this if another mod already replaces game classes)");
                    default -> label(v);
                };
                info("  [" + n + "] " + text);
                vopts.add(n);
                if (v.equals(COMPAT)) compatPick = n;
                if ((ramGb >= 32 && v.equals("client-fixes-standard")) || (ramGb > 0 && ramGb < 32 && v.equals("client-fixes-lowmem"))) recommend = n;
            }
            List<String> blocked = List.of();
            if (compatPick != null) {
                String full = recommend != null ? variants.get(Integer.parseInt(recommend) - 1) : "client-fixes-lowmem";
                List<String> req = new ArrayList<>(picked);
                req.add(full);
                blocked = compatAdvice(req);
            }
            if (!blocked.isEmpty()) {
                recommend = compatPick;
                info(L("  遊戲裡已有其他 MOD 替換的遊戲 class（" + blocked.size() + " 個，例如 " + blocked.get(0) + "），完整版裝不上；建議選 [" + recommend + "] 相容版，直接按 Return 就會選它。",
                        "  Another mod already replaces " + blocked.size() + " game class file(s) the full version needs (for example " + blocked.get(0) + "); option [" + recommend + "], the compatible version, is recommended - just press Return."));
            } else if (recommend != null) {
                info(L("  你的電腦記憶體約 " + ramGb + " GB，建議選 [" + recommend + "]，直接按 Return 就會選它。", "  Your Mac has about " + ramGb + " GB RAM; option [" + recommend + "] is recommended - just press Return."));
            } else {
                info(L("  不知道記憶體多大就選省記憶體版。", "  If unsure about your RAM, choose low-memory."));
            }
            String vc = choice(L("請輸入數字後按 Return", "Type a number and press Return"), vopts, recommend);
            if (vc.equals("0")) return new ArrayList<>();
            picked.add(variants.get(Integer.parseInt(vc) - 1));
        }
        return picked;
    }

    /** 唯讀試算：請求會撞到外來 class、換成相容版就裝得上時回傳衝突清單；算不出來就當沒事。 */
    private List<String> compatAdvice(List<String> requested) {
        try {
            List<String> installed = moduleIds(readState());
            List<String> loose = looseClasses(jarPackages(root.resolve("projectzomboid.jar")));
            List<String> c = conflicts(loose, resolveInstall(installed, requested, new ArrayList<>()));
            if (!c.isEmpty() && compatFits(installed, requested, loose)) return c;
        } catch (IOException | RuntimeException ignored) {
            // 試算失敗不擋選單；真正安裝時會再檢查一次
        }
        return List.of();
    }

    private List<String> selectUninstall(List<String> installed) {
        head(L("要移除哪些修補？", "Which patches do you want to remove?"));
        for (int k = 0; k < installed.size(); k++) info("  [" + (k + 1) + "] " + label(installed.get(k)));
        info(L("  [A] 全部移除", "  [A] Remove everything"));
        info(L("  [0] 返回", "  [0] Back"));
        while (true) {
            String a = ask(L("請輸入 A 或編號後按 Return", "Type A or a number and press Return"));
            if (a == null || a.equals("0")) return new ArrayList<>();
            if (a.equalsIgnoreCase("A")) return new ArrayList<>(installed);
            Set<String> picked = new LinkedHashSet<>();
            boolean ok = true;
            for (String t : a.split(",")) {
                t = t.trim();
                if (!t.matches("\\d+") || Integer.parseInt(t) < 1 || Integer.parseInt(t) > installed.size()) {
                    ok = false;
                    break;
                }
                picked.add(installed.get(Integer.parseInt(t) - 1));
            }
            if (ok && !picked.isEmpty()) return new ArrayList<>(picked);
            bad(L("輸入格式不對，請重新輸入。", "Invalid input, please try again."));
        }
    }

    private void menu() throws IOException {
        while (true) {
            head(L("Minidoracat Project Zomboid 客戶端修補管理（macOS）", "Minidoracat Project Zomboid client patch manager (macOS)"));
            info(L("  遊戲：", "  Game: ") + app);
            info(L("  [1] 安裝或更新修補", "  [1] Install or update patches"));
            info(L("  [2] 移除部分修補", "  [2] Remove some patches"));
            info(L("  [3] 全部移除", "  [3] Remove everything"));
            info(L("  [4] 查看目前安裝狀態", "  [4] Show installation status"));
            String memLabel = memoryLabel();
            info(L("  [5] 調整遊戲記憶體上限（" + memLabel + "）", "  [5] Change the game memory limit (" + memLabel + ")"));
            info(L("  [L] English", "  [L] 中文"));
            info(L("  [0] 離開", "  [0] Exit"));
            String c = choice(L("請輸入數字後按 Return", "Type a number and press Return"), List.of("0", "1", "2", "3", "4", "5", "L"), null);
            if (c.equals("L")) {
                zh = !zh;
                continue;
            }
            if (c.equals("0")) return;
            try {
                switch (c) {
                    case "1" -> {
                        if (manifest == null) {
                            bad(L("找不到 manifest.json：安裝必須從解壓後的安裝包資料夾執行。", "manifest.json not found: run this from the extracted package folder to install."));
                        } else {
                            List<String> picked = selectInstall();
                            if (!picked.isEmpty()) install(picked, false);
                        }
                    }
                    case "2" -> {
                        List<String> ids = moduleIds(readState());
                        if (ids.isEmpty()) uninstall(List.of(), true);
                        else {
                            List<String> picked = selectUninstall(ids);
                            if (!picked.isEmpty()) uninstall(picked, false);
                        }
                    }
                    case "3" -> uninstall(List.of(), true);
                    case "5" -> memory();
                    default -> status();
                }
            } catch (Fail f) {
                // 選單模式下單一動作失敗不關掉視窗，讓玩家看訊息並改選
                bad(f.getMessage());
                info(L("（代碼 " + f.code + "）", "(code " + f.code + ")"));
            }
            info("");
            if (ask(L("按 Return 回到主選單", "Press Return to return to the main menu")) == null) return;
        }
    }

    // ------------------------------------------------------------------ 主選單 [5]：調整遊戲記憶體上限
    /** 主選單那行括號裡的字；讀不到或認不得只說無法判斷（主選單不因此失敗）。 */
    private String memoryLabel() {
        int cur = -1;
        try {
            InfoPlist p = readPlist();
            int xi = p.problem == null ? xmxIndex(p.options) : -1;
            if (xi >= 0) cur = xmxMb(p.options.get(xi));
        } catch (Fail | IOException e) {
            // 照樣顯示主選單
        }
        if (cur < 0) return L("目前：無法判斷", "now: unknown");
        int rec = recommendedMb(ramGb);
        return rec > 0 ? L("目前 " + cur + " MB，建議 " + rec + " MB", "now " + cur + " MB, recommended " + rec + " MB")
                : L("目前 " + cur + " MB", "now " + cur + " MB");
    }

    /** 只改 -Xmx：沒裝修補也能改；不走 plistGate（那些是 agent 的前提）；單檔原子替換，不碰安裝紀錄。 */
    private void memory() throws IOException {
        FileLock lock = lock();
        try {
            assertNotRunning();
            InfoPlist plist = readPlist();
            if (plist.problem != null) {
                throw new Fail(CONFLICT, L("Info.plist 的格式和遊戲原本的不同（" + plist.problem + "），不知道怎麼安全地修改。",
                        "Info.plist does not look like the game's own file (" + plist.problem + "), so it cannot be changed safely. ") + restoreHint());
            }
            int xi = xmxIndex(plist.options);
            if (xi < 0) {
                throw new Fail(CONFLICT, L("Info.plist 找不到可以調整的記憶體上限（要剛好一條 -Xmx，格式像 -Xmx3072m）。要回到原版的 " + VANILLA_MEMORY_MB + " MB，用 Steam 驗證遊戲檔案的完整性。",
                        "Info.plist has no memory limit this installer can change (exactly one -Xmx, like -Xmx3072m). To go back to the game's " + VANILLA_MEMORY_MB + " MB, verify the game files with Steam."));
            }
            int cur = xmxMb(plist.options.get(xi)), rec = recommendedMb(ramGb);
            head(L("調整遊戲記憶體上限", "Change the game memory limit"));
            info(L("  目前最多用 " + cur + " MB（Info.plist 的 " + plist.options.get(xi) + "）。", "  The game can use at most " + cur + " MB now (" + plist.options.get(xi) + " in Info.plist)."));
            if (rec > 0) info(L("  這台 Mac 約有 " + ramGb + " GB 記憶體，建議 " + rec + " MB。", "  This Mac has about " + ramGb + " GB RAM; " + rec + " MB is recommended."));
            else if (ramGb > 0) info(L("  這台 Mac 約有 " + ramGb + " GB 記憶體，沒有建議調高的值。", "  This Mac has about " + ramGb + " GB RAM; raising the limit is not recommended."));
            info(L("  這是遊戲本身的設定：移除修補不會改回；Steam 更新或驗證遊戲檔案後會回到 " + VANILLA_MEMORY_MB + " MB。",
                    "  It is the game's own setting: removing the patches does not change it back; a Steam update or file verification resets it to " + VANILLA_MEMORY_MB + " MB."));
            List<String> opts = new ArrayList<>(List.of("0"));
            if (rec > 0) {
                info(L("  [1] 改成建議值 " + rec + " MB", "  [1] Use the recommended " + rec + " MB"));
                opts.add("1");
            }
            info(L("  [2] 自訂", "  [2] Enter a custom value"));
            info(L("  [3] 恢復預設 " + VANILLA_MEMORY_MB + " MB", "  [3] Restore the default " + VANILLA_MEMORY_MB + " MB"));
            info(L("  [0] 返回", "  [0] Back"));
            opts.add("2");
            opts.add("3");
            String c = choice(L("請輸入數字後按 Return", "Type a number and press Return"), opts, null);
            if (c.equals("0")) return;
            int mb = c.equals("1") ? rec : VANILLA_MEMORY_MB;
            if (c.equals("2")) {
                info(L("  輸入 MB 數：" + VANILLA_MEMORY_MB + " 以上的整數，例如 8192（8 GB）。直接按 Return 取消。",
                        "  Enter a whole number of MB, at least " + VANILLA_MEMORY_MB + ", for example 8192 (8 GB). Press Return alone to cancel."));
                while (true) {
                    String a = ask("MB");
                    if (a == null || a.isEmpty()) {
                        info(L("已取消。", "Cancelled."));
                        return;
                    }
                    int n = a.matches("\\d{1,7}") ? Integer.parseInt(a) : -1;
                    String why = n < VANILLA_MEMORY_MB ? L("請輸入 " + VANILLA_MEMORY_MB + " 以上的整數（單位是 MB，不要加 g 或 m）。", "Enter a whole number of MB, at least " + VANILLA_MEMORY_MB + " (no g or m).")
                            : ramGb > 0 && n > ramGb * 1024 ? L("這台 Mac 只有約 " + ramGb + " GB 記憶體（" + ramGb * 1024 + " MB），上限不能比它大。", "This Mac has only about " + ramGb + " GB of RAM (" + ramGb * 1024 + " MB); the limit cannot be larger than that.")
                            : memoryBlocker(plist.options, n);
                    if (why == null) {
                        mb = n;
                        break;
                    }
                    bad(why);
                }
            } else {
                String why = memoryBlocker(plist.options, mb);
                if (why != null) throw new Fail(CONFLICT, why);
            }
            if (mb == cur) {
                good(L("目前已經是 " + cur + " MB，不需要變更。", "It is already " + cur + " MB; nothing to change."));
                return;
            }
            info(L("  遊戲記憶體上限：", "  Game memory limit: ") + cur + " → " + mb + " MB");
            // shortcut: 「過半」是經驗值（建議值表最高是 RAM 的 37.5%），沒實測；有玩家回報再調
            if (c.equals("2") && ramGb > 0 && mb > ramGb * 512) {
                warn(L(mb + " MB 超過這台 Mac 記憶體（約 " + ramGb + " GB）的一半。遊戲的貼圖和其他程式也要用記憶體，設太高整台 Mac 會變慢。",
                        mb + " MB is more than half of this Mac's RAM (about " + ramGb + " GB). The game's textures and other programs need memory too; setting it too high slows the whole Mac down."));
            }
            if (!confirm(L("確定要修改嗎？輸入 Y 按 Return 開始（輸入 N 取消）", "Change it now? Type Y and press Return to start (N to cancel)"))) {
                info(L("已取消。", "Cancelled."));
                return;
            }
            byte[] newPlist = plist.replace(xi, "-Xmx" + mb + "m");
            writeProbe();
            try {
                clearLeftovers();
                publish(plistPath, newPlist, "plist");
            } catch (IOException e) {
                throw new Fail(WRITE, L("寫不進 Info.plist，沒有變更任何檔案：", "Could not write Info.plist; nothing was changed: ") + e.getMessage());
            }
            good(L("完成：遊戲記憶體上限已改成 " + mb + " MB，下次啟動遊戲生效。", "Done: the game memory limit is now " + mb + " MB. It takes effect the next time the game starts."));
        } finally {
            unlock(lock);
        }
    }

    // ------------------------------------------------------------------ SHA
    static String sha256(Path p) throws IOException {
        MessageDigest d = digest();
        try (InputStream s = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            for (int n; (n = s.read(buf)) > 0;) d.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    static String sha256(byte[] b) {
        return HexFormat.of().formatHex(digest().digest(b));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
