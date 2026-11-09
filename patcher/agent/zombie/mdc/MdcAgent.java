package zombie.mdc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 伺服器修補的載入器（Java agent，{@code -javaagent}）。
 *
 * <p>42.22 起 {@code projectzomboid.jar} 簽章：手術後的 class 不能再以 loose 檔放在 jar 旁——同一個 package 裡
 * loose class 沒有簽章者、jar 內的 class 有，JVM 丟 {@code SecurityException}（{@code ClassLoader.checkCerts}）。
 * 本 agent 在官方 jar 的 class 載入時，把 bytes 換成建置期手術好的版本；官方 jar 與它的簽章不動，換上的 class
 * 保留官方的 ProtectionDomain。放在原版 package 的 helper 用 {@code MethodHandles.Lookup.defineClass} 以同 package
 * 的原版 class 為錨定義（同一個 ProtectionDomain）；其餘 helper 在我們自己的 package，直接從 agent jar 載入。
 *
 * <p><b>全有或全無</b>：遊戲 jar 的 SHA-256 等於建置基準、payload 每一項的 SHA 都對、要換的 class 一個都還沒被載入，
 * 才註冊 transformer；任何一步不符就一個 class 都不換（原版遊戲），且不讓開服失敗——premain 丟例外會讓 JVM 直接結束。
 * 遊戲更新換了 jar 就不會啟用，沒有「新 jar＋舊修補」混跑。transformer 註冊後才定義原版 package 的 helper：錨點與
 * helper 的父型別（例如封包都實作被修補的 {@code INetworkPacket}）會在定義時載入，必須已經換得到。定義失敗時把
 * transformer 切到還原模式，以 {@code retransformClasses} 把已換上的 class 還原成原版並逐個核對原始 bytes 的 SHA，
 * 最後才移除 transformer（手術不改 class 的形狀，建置期 {@code AgentJarBuilder} 逐 class 檢查），回到原版遊戲；
 * 還原不完整才結束 JVM（exit 78），不帶著半套修補開服。
 *
 * <p>和別的 agent 並存：transformer 是 retransformation capable（還原要用），JVM 先跑所有 incapable 的 transformer
 * 才輪到 capable 的，和 {@code -javaagent} 的先後無關；別的 agent 先改過的 class，本 agent 拿到的 bytes 不是建置基準，
 * 那一個不換（{@code NOT APPLIED}）。排在最前面的好處只有 premain 先跑：別的 agent 來不及在本 agent 檢查之前載入要換的 class。
 *
 * <p>kill switch：{@code -Dmdc.agent=off|0}（下次開服生效）。
 *
 * <p>client 包的 agent jar 有多個模組（index 有 {@code module} 行；同一個 class 在三個 client-fixes 變體各有一份），
 * 由 {@code -javaagent:<jar>=modules=<id>,<id>} 選：沒選、選了不存在的、同一組選兩個、缺依賴，一律不啟用。多模組的
 * helper 全部以 Lookup 定義（我方 package {@code zombie.mdc} 以本 class 為錨），jar 裡沒有一般 entry 的 helper，
 * 沒選的模組就一個 class 都不會出現。伺服器的 jar 沒有 {@code module} 行，參數不用給。
 */
public final class MdcAgent {

    static final String INDEX = "/mdc/agent-index.tsv";
    static final String GAME_JAR = "projectzomboid.jar";

    private static volatile String state = "not loaded";
    private static volatile String selectedModules = "-";
    private static volatile int armedClasses;
    private static final AtomicInteger APPLIED = new AtomicInteger();
    private static final AtomicInteger MISMATCHED = new AtomicInteger();
    private static final AtomicInteger RESTORED = new AtomicInteger();

    private MdcAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            arm(args, inst);
        } catch (Throwable t) {
            // 任何意外都退回原版：premain 往外丟會讓 JVM 起不來
            disarm("agent failed before arming: " + t);
        }
    }

    /** 給驗證與診斷用：armed／off／disarmed 的狀態、換上的 class 數與選的模組。 */
    public static String status() {
        return state + " patched=" + armedClasses + " applied=" + APPLIED.get() + " mismatched=" + MISMATCHED.get()
                + " restored=" + RESTORED.get() + " modules=" + selectedModules;
    }

    /** armed＝整套修補已啟用；其餘（off／disarmed／not loaded）＝原版。 */
    public static String state() {
        return state;
    }

    /** 這次啟用的模組（逗號分隔；伺服器的 jar 沒有模組，回空字串）。 */
    public static String modules() {
        return selectedModules;
    }

    /** 該換卻沒換上的 class 數（載入的 bytes 不是建置基準）；不是 0 代表修補不完整。 */
    public static int mismatched() {
        return MISMATCHED.get();
    }

    static void arm(String args, Instrumentation inst) throws IOException, ReflectiveOperationException {
        String mode = System.getProperty("mdc.agent", "on").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("0") || "off".equals(args)) {
            state = "off";
            say("OFF (-Dmdc.agent=" + mode + ") -- running VANILLA Java");
            return;
        }
        Index index = Index.read();
        String problem = index.select(args);
        if (problem != null) {
            disarm(problem);
            return;
        }
        selectedModules = String.join(",", index.selected);
        Path jar = gameJar();
        if (jar == null) {
            disarm(GAME_JAR + " not found on java.class.path");
            return;
        }
        String live = sha256(jar);
        if (!live.equals(index.jarSha)) {
            disarm("game jar sha256 " + live.substring(0, 8) + " differs from build base " + index.jarSha.substring(0, 8)
                    + " (game updated? rebuild the patch for this jar)");
            return;
        }
        Map<String, Payload> patched = new HashMap<>();
        for (Entry e : index.patched) {
            if (!index.selected.contains(e.module())) {
                continue;
            }
            byte[] bytes = resource(e.path("patched"));
            if (bytes == null || !sha256(bytes).equals(e.sha())) {
                disarm("payload missing or SHA mismatch: " + e.name());
                return;
            }
            if (patched.put(e.name(), new Payload(e.origSha(), bytes)) != null) {
                disarm("two selected modules patch " + e.name());
                return;
            }
        }
        List<Entry> defineEntries = new ArrayList<>();
        List<byte[]> defines = new ArrayList<>();
        for (Entry e : index.defines) {
            if (!index.selected.contains(e.module())) {
                continue;
            }
            byte[] bytes = resource(e.path("define"));
            if (bytes == null || !sha256(bytes).equals(e.sha())) {
                disarm("payload missing or SHA mismatch: " + e.name());
                return;
            }
            if (!index.anchors.containsKey(packageOf(e.name()))) {
                disarm("no anchor class for package " + packageOf(e.name()));
                return;
            }
            defineEntries.add(e);
            defines.add(bytes);
        }

        // classpath 的資料夾排在 agent jar 前面：同名的 loose 檔會蓋掉 helper 或讓該換的 class 換不上
        Set<String> names = new java.util.HashSet<>(patched.keySet());
        for (Entry e : defineEntries) {
            names.add(e.name());
        }
        String loose = shadowingLooseClass(names);
        if (loose != null) {
            disarm("loose class " + loose + " on the class path shadows the patch (left over from an older install?"
                    + " move it away)");
            return;
        }
        ClassLoader app = ClassLoader.getSystemClassLoader();
        // 已經載入的 class 換不到（例如排在前面的另一個 -javaagent 先碰了它）：少換一個就不是整套，寧可不啟用
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getClassLoader() == app && patched.containsKey(c.getName().replace('.', '/'))) {
                disarm("class " + c.getName() + " was loaded before the agent (another -javaagent listed first?)");
                return;
            }
        }
        if (!inst.isRetransformClassesSupported()) {
            disarm("this JVM cannot retransform classes (no way back if defining helpers fails)");
            return;
        }
        // transformer 先註冊：錨點與 helper 的父型別在定義 helper 時載入，被修補的要換得到。
        // canRetransform=true：定義 helper 失敗時靠 retransform 還原；別的 agent 之後 retransform 這些 class 時也照樣換上。
        Swap swap = new Swap(app, patched);
        armedClasses = patched.size();
        inst.addTransformer(swap, true);
        Map<String, MethodHandles.Lookup> lookups = new HashMap<>();
        try {
            // 建置期排好的順序：依賴的模組在前、父型別在前。Lookup.defineClass 定義後立刻連結，驗證時要載入的 helper
            // 若還沒定義（定義的 helper 沒有一般 entry，app loader 找不到；例如外層 class 先於它的內部 class）就丟
            // NoClassDefFoundError：這時 class 已經定義、只差連結，第一次使用時 JVM 重新連結（建置期 AgentLoadCheck 以
            // -Xverify:all 連結過每一個）。沒定義成的（父型別還沒定義）留到下一輪；一整輪都沒進展才算失敗。
            List<Integer> pending = new ArrayList<>();
            for (int i = 0; i < defines.size(); i++) {
                pending.add(i);
            }
            while (!pending.isEmpty()) {
                int before = pending.size();
                NoClassDefFoundError last = null;
                for (Iterator<Integer> it = pending.iterator(); it.hasNext(); ) {
                    int i = it.next();
                    String name = defineEntries.get(i).name();
                    String pkg = packageOf(name);
                    MethodHandles.Lookup lookup = lookups.get(pkg);
                    if (lookup == null) {
                        Class<?> anchor = Class.forName(index.anchors.get(pkg).replace('/', '.'), false, app);
                        lookup = MethodHandles.privateLookupIn(anchor, MethodHandles.lookup());
                        lookups.put(pkg, lookup);
                    }
                    try {
                        lookup.defineClass(defines.get(i));
                        it.remove();
                    } catch (NoClassDefFoundError e) {
                        last = e;
                        if (defined(inst, app, name)) {
                            it.remove();
                        }
                    }
                }
                if (pending.size() == before) {
                    throw last;
                }
            }
            if (System.getProperty("mdc.agent.testFailDefine") != null) {
                // 建置期故障注入（AgentLoadCheck restored）：helper 全定義完、換上的 class 最多的時候失敗
                throw new LinkageError("fault injection (-Dmdc.agent.testFailDefine)");
            }
        } catch (Throwable t) {
            // 還原：transformer 切到還原模式後 retransform，JVM 把原始 bytes 再交給它一次；它核對 SHA 是建置基準、回 null
            // （不換），已換上的 class 就回到原版。定義成功的 helper 沒有人引用。核對數不等於換上的數＝還原不完整。
            // beginRestore 與 transform 的判定同一把鎖：切到還原模式那一刻之後不會再有 class 被換上，之前換上的都在清單裡
            // （別的執行緒還在定義中的 class，下面的 Class.forName 會等它定義完）。
            Set<String> swapped = Set.of();
            try {
                swapped = swap.beginRestore();
                List<Class<?>> classes = new ArrayList<>();
                for (String n : swapped) {
                    classes.add(Class.forName(n.replace('/', '.'), false, app));
                }
                if (!classes.isEmpty()) {
                    inst.retransformClasses(classes.toArray(new Class<?>[0]));
                }
                if (!swap.restored().equals(swapped)) {
                    throw new IllegalStateException("restored " + swap.restored().size() + " of " + swapped.size());
                }
            } catch (Throwable r) {
                state = "partial";
                say("FATAL: defining helpers failed (" + t + ") and restoring the swapped classes failed (" + r + "): "
                        + swapped + " -- refusing to run half patched (exit 78); start without the agent"
                        + " (-Dmdc.agent=off or uninstall.sh)");
                System.exit(78);
            }
            inst.removeTransformer(swap);
            RESTORED.set(swapped.size());
            APPLIED.set(0);
            armedClasses = 0;
            selectedModules = "-";
            disarm("defining helpers failed (" + t + "); " + swapped.size() + " swapped classes restored to vanilla");
            return;
        }
        state = "armed";
        say("ARMED: " + patched.size() + " patched classes, " + defines.size() + " helpers defined; "
                + (index.modules.isEmpty() ? "" : "modules " + selectedModules + "; ")
                + "agent " + index.version + " built " + index.built + "; jar sha256 " + live.substring(0, 8));
    }

    /**
     * class 已經定義在 app loader 裡（定義成功但連結失敗的也算）。只看 JVM 已載入的清單，不用 {@code Class.forName}：
     * 那會照 classpath 去找，遊戲資料夾若有同名的舊 loose 檔就會被載入、當成「已定義」。
     */
    private static boolean defined(Instrumentation inst, ClassLoader app, String internalName) {
        String name = internalName.replace('/', '.');
        for (Class<?> c : inst.getInitiatedClasses(app)) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void disarm(String reason) {
        state = "disarmed";
        say("DISARMED: " + reason + " -- running VANILLA Java");
    }

    static void say(String message) {
        System.out.println("[mdc-agent] " + message);
        System.out.flush();
    }

    /**
     * classpath 的資料夾裡會蓋掉修補的 loose class：與要換、要定義的 class 同名的檔，或我方 package {@code zombie/mdc}
     * 底下任何 .class（舊版 loose-class 安裝的殘留）。沒有就回 null。
     */
    static String shadowingLooseClass(Set<String> names) throws IOException {
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (entry.endsWith("*")) {
                continue;   // dir/* 只收 jar
            }
            Path dir;
            try {
                dir = Path.of(entry.isEmpty() ? "." : entry);
            } catch (java.nio.file.InvalidPathException e) {
                continue;
            }
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (String n : names) {
                if (Files.exists(dir.resolve(n + ".class"))) {
                    return dir.resolve(n + ".class").toString();
                }
            }
            Path own = dir.resolve("zombie/mdc");
            if (Files.isDirectory(own)) {
                try (java.util.stream.Stream<Path> s = Files.walk(own)) {
                    java.util.Optional<Path> first = s.filter(p -> p.toString().endsWith(".class")).findFirst();
                    if (first.isPresent()) {
                        return first.get().toString();
                    }
                }
            }
        }
        return null;
    }

    /** classpath 上第一個名為 projectzomboid.jar 的項目（相對路徑以工作目錄為準，同 JVM 的解讀）。 */
    static Path gameJar() {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path p = Path.of(entry);
            Path name = p.getFileName();
            if (name != null && name.toString().equalsIgnoreCase(GAME_JAR) && Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
        }
        return null;
    }

    static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = MdcAgent.class.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        ByteBuffer buffer = ByteBuffer.allocate(1 << 20);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            while (ch.read(buffer) >= 0) {
                buffer.flip();
                md.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Payload(String origSha, byte[] bytes) {
    }

    /** {@code module} 空字串＝伺服器的單一模組 jar。 */
    record Entry(String name, String origSha, String sha, String module) {
        /** jar 內的路徑：單一模組 {@code /mdc/<kind>/…}，多模組 {@code /mdc/m/<module>/<kind>/…}。 */
        String path(String kind) {
            return (module.isEmpty() ? "/mdc/" : "/mdc/m/" + module + "/") + kind + "/" + name + ".class";
        }
    }

    record ModuleSpec(String group, List<String> requires) {
    }

    /** {@code mdc/agent-index.tsv}：由 AgentJarBuilder 寫出，tab 分隔、LF。 */
    static final class Index {
        String version = "?";
        String built = "?";
        String jarSha;
        final Map<String, ModuleSpec> modules = new java.util.LinkedHashMap<>();
        final List<Entry> patched = new ArrayList<>();
        final List<Entry> defines = new ArrayList<>();
        final Map<String, String> anchors = new HashMap<>();
        final java.util.Set<String> selected = new java.util.LinkedHashSet<>();

        static Index read() throws IOException {
            byte[] raw = resource(INDEX);
            if (raw == null) {
                throw new IOException("agent index missing: " + INDEX);
            }
            Index index = new Index();
            for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t");
                switch (f[0]) {
                    case "version" -> index.version = f[1];
                    case "built" -> index.built = f[1];
                    case "jar" -> index.jarSha = f[1];
                    case "module" -> index.modules.put(f[1], new ModuleSpec(f[2].equals("-") ? "" : f[2],
                            f[3].equals("-") ? List.of() : List.of(f[3].split(","))));
                    case "patched" -> index.patched.add(new Entry(f[1], f[2], f[3], f.length > 4 ? f[4] : ""));
                    case "define" -> index.defines.add(new Entry(f[1], f[2], f[3], f.length > 4 ? f[4] : ""));
                    case "anchor" -> index.anchors.put(f[1], f[2]);
                    default -> {
                        // 未知欄位：較新的建置工具才會寫，忽略
                    }
                }
            }
            if (index.jarSha == null || index.jarSha.length() != 64) {
                throw new IOException("agent index has no build base jar sha256");
            }
            return index;
        }

        /**
         * 依 agent 參數選模組，填進 {@link #selected}；不成立時回傳原因（呼叫端不啟用）。單一模組 jar 不看參數。
         * 參數格式 {@code modules=<id>,<id>}。
         */
        String select(String args) {
            if (modules.isEmpty()) {
                selected.add("");
                return null;
            }
            String list = null;
            for (String part : (args == null ? "" : args).split(";")) {
                if (part.trim().startsWith("modules=")) {
                    list = part.trim().substring("modules=".length());
                }
            }
            if (list == null || list.isBlank()) {
                return "no modules selected (-javaagent:<jar>=modules=<id>,...; available: "
                        + String.join(",", modules.keySet()) + ")";
            }
            Map<String, String> groups = new HashMap<>();
            for (String id : list.split(",")) {
                id = id.trim();
                ModuleSpec m = modules.get(id);
                if (m == null) {
                    return "unknown module " + id + " (available: " + String.join(",", modules.keySet()) + ")";
                }
                if (!m.group().isEmpty()) {
                    String other = groups.put(m.group(), id);
                    if (other != null && !other.equals(id)) {
                        return "modules " + other + " and " + id + " are alternatives (group " + m.group() + ")";
                    }
                }
                selected.add(id);
            }
            for (String id : selected) {
                for (String req : modules.get(id).requires()) {
                    if (!selected.contains(req)) {
                        return "module " + id + " requires " + req;
                    }
                }
            }
            return null;
        }
    }

    /** 只換系統 class loader（遊戲 classpath）載入、名稱在表上、原版 bytes SHA 等於建置基準的 class。 */
    static final class Swap implements ClassFileTransformer {
        private final ClassLoader app;
        private final Map<String, Payload> table;

        private final java.util.Set<String> appliedNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> restoredNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** 還原模式：一個都不換；retransform 交來的原始 bytes 對過 SHA 就記為已還原。只在鎖內讀寫。 */
        private boolean restoring;

        Swap(ClassLoader app, Map<String, Payload> table) {
            this.app = app;
            this.table = table;
        }

        /** 切到還原模式，回傳到這一刻為止換上過的 class（內部名稱，排序）；回傳之後 transform 不會再換任何 class。 */
        synchronized Set<String> beginRestore() {
            restoring = true;
            return new java.util.TreeSet<>(appliedNames);
        }

        Set<String> restored() {
            return new java.util.TreeSet<>(restoredNames);
        }

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
                                ProtectionDomain pd, byte[] original) {
            if (className == null || loader != app) {
                return null;
            }
            Payload p = table.get(className);
            if (p == null) {
                return null;
            }
            boolean base;
            try {
                base = sha256(original).equals(p.origSha());
            } catch (Throwable t) {
                base = false;
            }
            synchronized (this) {   // 與 beginRestore 同一把鎖
                if (restoring) {
                    if (redefined != null && base) {
                        restoredNames.add(className);   // 沒記到＝還原數不符，由 arm() 判失敗
                    }
                    return null;
                }
                if (redefined != null) {
                    if (appliedNames.contains(className)) {
                        if (base) {
                            return p.bytes();   // 別的 agent retransform 已換上的 class（JVM 交來原始 bytes）：照樣給同一份
                        }
                        // redefineClasses（別的 agent 或除錯器）給了別的 bytes：不蓋掉對方的定義，但修補從此少一個
                        MISMATCHED.incrementAndGet();
                        say("NOT APPLIED " + className + ": redefined with other bytes by another agent or a debugger");
                    }
                    return null;   // 載入時沒換上的 class 不在事後補換
                }
                if (!base) {
                    // 整個 jar 的 SHA 已經對過，走到這裡代表這個 class 不是從遊戲 jar 來（例如 java/ 底下的殘留 loose 檔）
                    MISMATCHED.incrementAndGet();
                    say("NOT APPLIED " + className + ": loaded bytes differ from the build base (not from "
                            + GAME_JAR + "?)");
                    return null;
                }
                if (appliedNames.add(className)) {
                    APPLIED.incrementAndGet();
                }
                return p.bytes();
            }
        }
    }
}
