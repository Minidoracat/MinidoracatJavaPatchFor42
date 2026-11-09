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
 * <p><b>全有或全無</b>：遊戲 jar 的 SHA-256 等於建置基準、payload 每一項的 SHA 都對，才註冊 transformer；任何一步不符
 * 就一個 class 都不換（原版遊戲），且不讓開服失敗——premain 丟例外會讓 JVM 直接結束。遊戲更新換了 jar 就不會啟用，
 * 沒有「新 jar＋舊修補」混跑。transformer 註冊後才定義原版 package 的 helper：錨點與 helper 的父型別（例如封包都實作
 * 被修補的 {@code INetworkPacket}）會在定義時載入，必須已經換得到。定義失敗時移除 transformer 並列出已換上的 class
 * （jar 已逐位元比對過建置基準、建置期 {@code AgentLoadCheck} 以同一個 jar 跑過同一段，實際上不會發生）。
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
                + " modules=" + selectedModules;
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

        // transformer 先註冊：錨點與 helper 的父型別在定義 helper 時載入，被修補的要換得到
        ClassLoader app = ClassLoader.getSystemClassLoader();
        Swap swap = new Swap(app, patched);
        armedClasses = patched.size();
        inst.addTransformer(swap, false);
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
                        if (defined(app, name)) {
                            it.remove();
                        }
                    }
                }
                if (pending.size() == before) {
                    throw last;
                }
            }
        } catch (Throwable t) {
            inst.removeTransformer(swap);
            state = "partial";
            say("PARTIAL: defining helpers failed (" + t + "); transformer removed, but " + APPLIED.get()
                    + " classes were already patched: " + swap.appliedNames()
                    + " -- RESTART WITHOUT THE AGENT (-Dmdc.agent=off or uninstall.sh)");
            return;
        }
        state = "armed";
        say("ARMED: " + patched.size() + " patched classes, " + defines.size() + " helpers defined; "
                + (index.modules.isEmpty() ? "" : "modules " + selectedModules + "; ")
                + "agent " + index.version + " built " + index.built + "; jar sha256 " + live.substring(0, 8));
    }

    /** class 已經在 app loader 裡（定義成功但連結失敗的也算）。只載入、不初始化。 */
    private static boolean defined(ClassLoader app, String internalName) {
        try {
            Class.forName(internalName.replace('/', '.'), false, app);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void disarm(String reason) {
        state = "disarmed";
        say("DISARMED: " + reason + " -- running VANILLA Java");
    }

    static void say(String message) {
        System.out.println("[mdc-agent] " + message);
        System.out.flush();
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

        Swap(ClassLoader app, Map<String, Payload> table) {
            this.app = app;
            this.table = table;
        }

        String appliedNames() {
            return new java.util.TreeSet<>(appliedNames).toString();
        }

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
                                ProtectionDomain pd, byte[] original) {
            if (className == null || redefined != null || loader != app) {
                return null;
            }
            Payload p = table.get(className);
            if (p == null) {
                return null;
            }
            try {
                if (!sha256(original).equals(p.origSha())) {
                    // 整個 jar 的 SHA 已經對過，走到這裡代表這個 class 不是從遊戲 jar 來（例如 java/ 底下的殘留 loose 檔）
                    MISMATCHED.incrementAndGet();
                    say("NOT APPLIED " + className + ": loaded bytes differ from the build base (not from "
                            + GAME_JAR + "?)");
                    return null;
                }
            } catch (Throwable t) {
                MISMATCHED.incrementAndGet();
                return null;
            }
            APPLIED.incrementAndGet();
            appliedNames.add(className);
            return p.bytes();
        }
    }
}
