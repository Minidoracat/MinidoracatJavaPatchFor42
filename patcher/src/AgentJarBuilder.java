import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 建置期：把 Patcher 手術後的 class＋runtime helper 與 MdcAgent 打成 agent jar（{@code zombie.mdc.MdcAgent} 讀）。
 *
 * <p><b>伺服器（單一模組）</b>：{@code AgentJarBuilder <基準 jar> <dist\java> <manifest> <MdcAgent class 目錄> <version>
 * <built> <輸出 jar> <輸出 agent-manifest.txt>}
 * <ul>
 *   <li>{@code zombie/mdc/MdcAgent*.class} 與「我方 package」的 helper：一般 entry，JVM 把 agent jar 接在系統
 *       class loader 的搜尋路徑尾端，照常載入（這些 package 不在遊戲 jar 裡，沒有簽章衝突）。</li>
 *   <li>{@code mdc/patched/<class>.class}：手術後的 bytes，由 transformer 在官方 class 載入時換上。</li>
 *   <li>{@code mdc/define/<class>.class}：放在原版 package 的 helper（要讀 package-private 成員），開服時以同
 *       package 的原版 class 為錨用 {@code Lookup.defineClass} 定義。路徑刻意不是 class 路徑：當成一般 entry
 *       會被系統 class loader 以「沒有簽章者」載入而丟 SecurityException。</li>
 *   <li>{@code mdc/agent-index.tsv}：建置基準 jar 的 SHA-256、每一項的 SHA、錨點。</li>
 * </ul>
 *
 * <p><b>client 包（多模組）</b>：{@code AgentJarBuilder --modules <基準 jar> <模組表 tsv> <MdcAgent class 目錄> <version>
 * <built> <輸出 jar> <輸出 agent-manifest.txt>}。模組表每行 {@code id<TAB>group<TAB>requires<TAB>payload 目錄<TAB>manifest}
 * （group／requires 沒有寫 {@code -}，requires 以逗號分隔且必須排在前面）。三個 client-fixes 變體換同一批 class、helper
 * 同名，所以 helper 一律放 {@code mdc/m/<id>/define/}、以 Lookup 定義（我方 package {@code zombie/mdc} 以 MdcAgent 為錨），
 * 手術後的 class 放 {@code mdc/m/<id>/patched/}；jar 裡除了 MdcAgent 沒有一般 entry 的 class，玩家沒選的模組不會載入。
 * 不同 group 的模組 class 名稱不得重疊（同時裝時會互相蓋掉）。
 *
 * <p>原版 package 的 helper 依父型別排序（父型別先定義）。錨點挑同 package 的原版頂層 class（不挑被修補的，少載一個
 * 大 class）；agent 先註冊 transformer 才定義 helper，所以錨點與 helper 的父型別就算是被修補的 class 也換得到。
 * jar 內容排序、時間戳固定，同樣輸入產生同樣的 jar（SHA 可重現）。
 */
public final class AgentJarBuilder {

    static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    static final String OWN_PACKAGE = "zombie/mdc";
    static final String OWN_ANCHOR = "zombie/mdc/MdcAgent";

    record Header(String superName, String[] interfaces) {
    }

    /** 一個模組：manifest 列的 helper（origSha=-）與手術後的 class，bytes 已對過 SHA。 */
    static final class Mod {
        final String id;
        final String group;
        final List<String> requires;
        final Map<String, String> patchedOrig = new TreeMap<>();
        final Map<String, String> patchedSha = new TreeMap<>();
        final Map<String, String> helperSha = new TreeMap<>();
        final Map<String, byte[]> bytes = new HashMap<>();

        Mod(String id, String group, List<String> requires) {
            this.id = id;
            this.group = group;
            this.requires = requires;
        }

        Set<String> names() {
            return concat(patchedSha.keySet(), helperSha.keySet());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 8 && args[0].equals("--modules")) {
            buildModules(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4], args[5], Path.of(args[6]),
                    Path.of(args[7]));
        } else if (args.length == 8) {
            buildServer(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4], args[5],
                    Path.of(args[6]), Path.of(args[7]));
        } else {
            System.err.println("用法：AgentJarBuilder <基準 jar> <dist\\java> <manifest> <agent classes> <version> <built> <out jar> <out agent-manifest>");
            System.err.println("      AgentJarBuilder --modules <基準 jar> <模組表 tsv> <agent classes> <version> <built> <out jar> <out agent-manifest>");
            System.exit(2);
        }
    }

    static void buildServer(Path baseJar, Path distJava, Path manifestPath, Path agentClasses, String version, String built,
                            Path outJar, Path outManifest) throws Exception {
        Map<String, Header> jarHeaders = new HashMap<>();
        Map<String, Set<String>> jarPackages = new TreeMap<>();
        readJar(baseJar, jarHeaders, jarPackages);
        Mod mod = readModule(new Mod("", "", List.of()), distJava, manifestPath);
        Set<String> patched = mod.patchedSha.keySet();

        Set<String> defineSet = new TreeSet<>();
        Set<String> ownSet = new TreeSet<>();
        Map<String, Header> all = new HashMap<>(jarHeaders);
        for (String helper : mod.helperSha.keySet()) {
            all.put(helper, header(mod.bytes.get(helper)));
            (jarPackages.containsKey(packageOf(helper)) ? defineSet : ownSet).add(helper);
        }
        List<String> defineOrder = topoOrder(defineSet, all);
        Map<String, String> anchors = anchors(defineSet, jarPackages, patched);

        Map<String, byte[]> entries = agentEntries(agentClasses);
        for (String own : ownSet) {
            if (entries.put(own + ".class", mod.bytes.get(own)) != null) {
                throw new IllegalStateException("helper 與 agent class 同名：" + own);
            }
        }
        String jarSha = sha256(Files.readAllBytes(baseJar));
        StringBuilder index = new StringBuilder();
        index.append("version\t").append(version).append('\n');
        index.append("built\t").append(built).append('\n');
        index.append("jar\t").append(jarSha).append('\n');
        for (String p : patched) {
            entries.put("mdc/patched/" + p + ".class", mod.bytes.get(p));
            index.append("patched\t").append(p).append('\t').append(mod.patchedOrig.get(p)).append('\t')
                    .append(mod.patchedSha.get(p)).append('\n');
        }
        for (String d : defineOrder) {
            entries.put("mdc/define/" + d + ".class", mod.bytes.get(d));
            index.append("define\t").append(d).append("\t-\t").append(mod.helperSha.get(d)).append('\n');
        }
        appendAnchors(index, anchors);
        entries.put("mdc/agent-index.tsv", index.toString().getBytes(StandardCharsets.UTF_8));
        String agentSha = writeJar(outJar, entries, "MinidoracatJavaPatch server agent", version);

        String text = "version\t" + version + "\n"
                + "built\t" + built + "\n"
                + "agent\t" + agentSha + "\n"
                + "jar\t" + jarSha + "\n"
                + "patched\t" + patched.size() + "\n"
                + "define\t" + defineOrder.size() + "\n"
                + "own\t" + ownSet.size() + "\n";
        Files.writeString(outManifest, text, StandardCharsets.UTF_8);
        System.out.printf("agent jar -> %s（patched %d、原版 package helper %d（錨點 %d 個 package）、我方 helper %d；sha256 %s）%n",
                outJar, patched.size(), defineOrder.size(), anchors.size(), ownSet.size(), agentSha.substring(0, 8));
    }

    static void buildModules(Path baseJar, Path spec, Path agentClasses, String version, String built, Path outJar,
                             Path outManifest) throws Exception {
        Map<String, Header> jarHeaders = new HashMap<>();
        Map<String, Set<String>> jarPackages = new TreeMap<>();
        readJar(baseJar, jarHeaders, jarPackages);

        List<Mod> mods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : Files.readAllLines(spec, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length != 5 || !f[0].matches("[a-z0-9-]+") || !seen.add(f[0])) {
                throw new IllegalStateException("模組表格式不符或 id 重複：" + line);
            }
            List<String> requires = f[2].equals("-") ? List.of() : List.of(f[2].split(","));
            for (String req : requires) {
                if (!seen.contains(req) || req.equals(f[0])) {
                    throw new IllegalStateException("模組 " + f[0] + " 依賴的 " + req + " 要排在它前面");
                }
            }
            mods.add(readModule(new Mod(f[0], f[1].equals("-") ? "" : f[1], requires), Path.of(f[3]), Path.of(f[4])));
        }
        if (mods.isEmpty()) {
            throw new IllegalStateException("模組表是空的");
        }
        for (int i = 0; i < mods.size(); i++) {
            for (int j = i + 1; j < mods.size(); j++) {
                Mod a = mods.get(i);
                Mod b = mods.get(j);
                if (!a.group.isEmpty() && a.group.equals(b.group)) {
                    continue;   // 同一組只會選一個
                }
                Set<String> both = new TreeSet<>(a.names());
                both.retainAll(b.names());
                if (!both.isEmpty()) {
                    throw new IllegalStateException("模組 " + a.id + " 與 " + b.id + " 可以同時裝，卻有同名 class：" + both);
                }
            }
        }

        Set<String> patchedAnywhere = new HashSet<>();
        Set<String> helpersAnywhere = new TreeSet<>();
        for (Mod m : mods) {
            patchedAnywhere.addAll(m.patchedSha.keySet());
            for (String helper : m.helperSha.keySet()) {
                String pkg = packageOf(helper);
                if (!jarPackages.containsKey(pkg) && !pkg.equals(OWN_PACKAGE)) {
                    throw new IllegalStateException("client helper 只能放在原版 package 或 " + OWN_PACKAGE + "（Lookup 要有錨點）：" + helper);
                }
                if (helper.startsWith(OWN_ANCHOR)) {
                    throw new IllegalStateException("helper 與 agent class 同名：" + helper);
                }
                if (jarPackages.containsKey(pkg)) {
                    helpersAnywhere.add(helper);
                }
            }
        }
        Map<String, String> anchors = anchors(helpersAnywhere, jarPackages, patchedAnywhere);
        for (Mod m : mods) {
            for (String helper : m.helperSha.keySet()) {
                if (packageOf(helper).equals(OWN_PACKAGE)) {
                    anchors.put(OWN_PACKAGE, OWN_ANCHOR);
                }
            }
        }

        Map<String, byte[]> entries = agentEntries(agentClasses);
        String jarSha = sha256(Files.readAllBytes(baseJar));
        StringBuilder index = new StringBuilder();
        index.append("version\t").append(version).append('\n');
        index.append("built\t").append(built).append('\n');
        index.append("jar\t").append(jarSha).append('\n');
        StringBuilder counts = new StringBuilder();
        for (Mod m : mods) {
            index.append("module\t").append(m.id).append('\t').append(m.group.isEmpty() ? "-" : m.group).append('\t')
                    .append(m.requires.isEmpty() ? "-" : String.join(",", m.requires)).append('\n');
        }
        for (Mod m : mods) {
            String base = "mdc/m/" + m.id + "/";
            for (String p : m.patchedSha.keySet()) {
                entries.put(base + "patched/" + p + ".class", m.bytes.get(p));
                index.append("patched\t").append(p).append('\t').append(m.patchedOrig.get(p)).append('\t')
                        .append(m.patchedSha.get(p)).append('\t').append(m.id).append('\n');
            }
            Map<String, Header> all = new HashMap<>(jarHeaders);
            for (String helper : m.helperSha.keySet()) {
                all.put(helper, header(m.bytes.get(helper)));
            }
            List<String> order = topoOrder(new TreeSet<>(m.helperSha.keySet()), all);
            for (String d : order) {
                entries.put(base + "define/" + d + ".class", m.bytes.get(d));
                index.append("define\t").append(d).append("\t-\t").append(m.helperSha.get(d)).append('\t')
                        .append(m.id).append('\n');
            }
            counts.append("module\t").append(m.id).append('\t').append(m.patchedSha.size()).append('\t')
                    .append(order.size()).append('\n');
        }
        appendAnchors(index, anchors);
        entries.put("mdc/agent-index.tsv", index.toString().getBytes(StandardCharsets.UTF_8));
        String agentSha = writeJar(outJar, entries, "MinidoracatJavaPatch client agent", version);

        String text = "version\t" + version + "\n"
                + "built\t" + built + "\n"
                + "agent\t" + agentSha + "\n"
                + "jar\t" + jarSha + "\n"
                + counts;
        Files.writeString(outManifest, text, StandardCharsets.UTF_8);
        System.out.printf("agent jar -> %s（%d 個模組、錨點 %d 個 package；sha256 %s）%n%s", outJar, mods.size(),
                anchors.size(), agentSha.substring(0, 8), counts);
    }

    /** 基準 jar：每個 class 的父型別與所在 package。 */
    static void readJar(Path baseJar, Map<String, Header> headers, Map<String, Set<String>> packages) throws IOException {
        try (ZipFile zf = new ZipFile(baseJar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String n = entry.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) {
                    continue;
                }
                String internal = n.substring(0, n.length() - ".class".length());
                headers.put(internal, header(zf.getInputStream(entry).readAllBytes()));
                packages.computeIfAbsent(packageOf(internal), k -> new TreeSet<>()).add(internal);
            }
        }
    }

    static Mod readModule(Mod mod, Path payload, Path manifestPath) throws Exception {
        for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t");
            String internal = f[0].substring(0, f[0].length() - ".class".length());
            if (f[1].equals("-")) {
                mod.helperSha.put(internal, f[2]);
            } else {
                mod.patchedOrig.put(internal, f[1]);
                mod.patchedSha.put(internal, f[2]);
            }
        }
        for (String internal : mod.names()) {
            byte[] b = Files.readAllBytes(payload.resolve(internal + ".class"));
            String expected = mod.patchedSha.containsKey(internal) ? mod.patchedSha.get(internal) : mod.helperSha.get(internal);
            if (!sha256(b).equals(expected)) {
                throw new IllegalStateException(internal + " 的 SHA 與 manifest 不符（payload 與 manifest 不是同一次建置？）");
            }
            mod.bytes.put(internal, b);
        }
        return mod;
    }

    /** 錨點：同 package 的原版 class，先挑沒被修補的頂層 class。 */
    static Map<String, String> anchors(Set<String> vanillaPackageHelpers, Map<String, Set<String>> jarPackages,
                                       Set<String> patched) {
        Map<String, String> anchors = new TreeMap<>();
        for (String helper : vanillaPackageHelpers) {
            String pkg = packageOf(helper);
            if (anchors.containsKey(pkg)) {
                continue;
            }
            String anchor = null;
            for (String candidate : jarPackages.get(pkg)) {
                if (!patched.contains(candidate) && !candidate.contains("$") && !candidate.endsWith("/package-info")) {
                    anchor = candidate;
                    break;
                }
            }
            anchors.put(pkg, anchor != null ? anchor : jarPackages.get(pkg).iterator().next());
        }
        return anchors;
    }

    static void appendAnchors(StringBuilder index, Map<String, String> anchors) {
        for (Map.Entry<String, String> a : anchors.entrySet()) {
            index.append("anchor\t").append(a.getKey()).append('\t').append(a.getValue()).append('\n');
        }
    }

    /** MdcAgent 自己的 class（一般 entry）。 */
    static Map<String, byte[]> agentEntries(Path agentClasses) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (var walk = Files.walk(agentClasses)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".class"))::iterator) {
                String rel = agentClasses.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith(OWN_ANCHOR)) {
                    throw new IllegalStateException("agent class 目錄有意外的檔案：" + rel);
                }
                entries.put(rel, Files.readAllBytes(p));
            }
        }
        if (!entries.containsKey(OWN_ANCHOR + ".class")) {
            throw new IllegalStateException("缺 " + OWN_ANCHOR + ".class");
        }
        return entries;
    }

    /** MANIFEST 先寫，其餘依名稱排序、時間戳固定；回傳 jar 的 SHA-256。 */
    static String writeJar(Path outJar, Map<String, byte[]> entries, String title, String version) throws Exception {
        Manifest mf = new Manifest();
        Attributes main = mf.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(new Attributes.Name("Premain-Class"), "zombie.mdc.MdcAgent");
        main.put(new Attributes.Name("Can-Redefine-Classes"), "false");
        main.put(new Attributes.Name("Can-Retransform-Classes"), "false");
        main.put(Attributes.Name.IMPLEMENTATION_TITLE, title);
        main.put(Attributes.Name.IMPLEMENTATION_VERSION, version);

        Files.createDirectories(outJar.toAbsolutePath().getParent());
        try (OutputStream os = Files.newOutputStream(outJar); ZipOutputStream zos = new ZipOutputStream(os)) {
            put(zos, "META-INF/", null);
            java.io.ByteArrayOutputStream mfBytes = new java.io.ByteArrayOutputStream();
            mf.write(mfBytes);
            put(zos, JarFile.MANIFEST_NAME, mfBytes.toByteArray());
            Set<String> dirs = new TreeSet<>();
            for (String name : entries.keySet()) {
                for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
                    dirs.add(name.substring(0, i + 1));
                }
            }
            for (String dir : dirs) {
                put(zos, dir, null);
            }
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                put(zos, e.getKey(), e.getValue());
            }
        }
        return sha256(Files.readAllBytes(outJar));
    }

    static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(FIXED_TIME);
        zos.putNextEntry(entry);
        if (data != null) {
            zos.write(data);
        }
        zos.closeEntry();
    }

    static Header header(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        return new Header(cr.getSuperName(), cr.getInterfaces());
    }

    /** helper 依父型別排序：同一組裡的父型別先出現。 */
    static List<String> topoOrder(Set<String> set, Map<String, Header> all) {
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String s : set) {
            visit(s, set, all, done, visiting, out);
        }
        return out;
    }

    static void visit(String s, Set<String> set, Map<String, Header> all, Set<String> done, Set<String> visiting,
                      List<String> out) {
        if (done.contains(s)) {
            return;
        }
        if (!visiting.add(s)) {
            throw new IllegalStateException("helper 父型別循環：" + s);
        }
        Header h = all.get(s);
        if (h != null) {
            List<String> supers = new ArrayList<>();
            if (h.superName() != null) {
                supers.add(h.superName());
            }
            supers.addAll(List.of(h.interfaces()));
            for (String sup : supers) {
                if (set.contains(sup)) {
                    visit(sup, set, all, done, visiting, out);
                }
            }
        }
        visiting.remove(s);
        done.add(s);
        out.add(s);
    }

    static String packageOf(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    static Set<String> concat(Set<String> a, Set<String> b) {
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return s;
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private AgentJarBuilder() {
    }
}
