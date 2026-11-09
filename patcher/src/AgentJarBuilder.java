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
 * 建置期：把 dist\java（Patcher 手術後的 class＋runtime helper）與 MdcAgent 打成正式服用的 agent jar。
 *
 * <p>jar 內容（{@code zombie.mdc.MdcAgent} 讀）：
 * <ul>
 *   <li>{@code zombie/mdc/MdcAgent*.class} 與「我方 package」的 helper：一般 entry，JVM 把 agent jar 接在系統
 *       classpath 尾端，照常載入（這些 package 不在遊戲 jar 裡，沒有簽章衝突）。</li>
 *   <li>{@code mdc/patched/<class>.class}：手術後的 bytes，由 transformer 在官方 class 載入時換上。</li>
 *   <li>{@code mdc/define/<class>.class}：放在原版 package 的 helper（要讀 package-private 成員），開服時以同
 *       package 的原版 class 為錨用 {@code Lookup.defineClass} 定義。路徑刻意不是 class 路徑：當成一般 entry
 *       會被系統 class loader 以「沒有簽章者」載入而丟 SecurityException。</li>
 *   <li>{@code mdc/agent-index.tsv}：建置基準 jar 的 SHA-256、每一項的 SHA、錨點。</li>
 * </ul>
 *
 * <p>原版 package 的 helper 依父型別排序（父型別先定義）。錨點挑同 package 的原版頂層 class（不挑被修補的，少載一個
 * 大 class）；agent 先註冊 transformer 才定義 helper，所以錨點與 helper 的父型別就算是被修補的 class 也換得到。
 * jar 內容排序、時間戳固定，同樣輸入產生同樣的 jar（SHA 可重現）。
 *
 * <p>用法：{@code AgentJarBuilder <基準 jar> <dist\java> <manifest> <MdcAgent class 目錄> <version> <built>
 * <輸出 jar> <輸出 agent-manifest.txt>}
 */
public final class AgentJarBuilder {

    static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    record Header(String superName, String[] interfaces) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            System.err.println("用法：AgentJarBuilder <基準 jar> <dist\\java> <manifest> <agent classes> <version> <built> <out jar> <out agent-manifest>");
            System.exit(2);
        }
        Path baseJar = Path.of(args[0]);
        Path distJava = Path.of(args[1]);
        Path manifestPath = Path.of(args[2]);
        Path agentClasses = Path.of(args[3]);
        String version = args[4];
        String built = args[5];
        Path outJar = Path.of(args[6]);
        Path outManifest = Path.of(args[7]);

        // 基準 jar：每個 class 的父型別與所在 package
        Map<String, Header> jarHeaders = new HashMap<>();
        Map<String, Set<String>> jarPackages = new TreeMap<>();
        try (ZipFile zf = new ZipFile(baseJar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String n = entry.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) {
                    continue;
                }
                String internal = n.substring(0, n.length() - ".class".length());
                byte[] bytes = zf.getInputStream(entry).readAllBytes();
                jarHeaders.put(internal, header(bytes));
                jarPackages.computeIfAbsent(packageOf(internal), k -> new TreeSet<>()).add(internal);
            }
        }

        // manifest：helper（origSha=-）與手術後的 class
        Map<String, String> patchedSha = new TreeMap<>();
        Map<String, String> patchedOrig = new TreeMap<>();
        Map<String, String> helperSha = new TreeMap<>();
        for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t");
            String internal = f[0].substring(0, f[0].length() - ".class".length());
            if (f[1].equals("-")) {
                helperSha.put(internal, f[2]);
            } else {
                patchedOrig.put(internal, f[1]);
                patchedSha.put(internal, f[2]);
            }
        }
        Set<String> patched = patchedSha.keySet();

        Map<String, byte[]> bytes = new HashMap<>();
        for (String internal : concat(patched, helperSha.keySet())) {
            byte[] b = Files.readAllBytes(distJava.resolve(internal + ".class"));
            String expected = patched.contains(internal) ? patchedSha.get(internal) : helperSha.get(internal);
            if (!sha256(b).equals(expected)) {
                throw new IllegalStateException(internal + " 的 SHA 與 manifest 不符（dist\\java 與 manifest 不同一次建置？）");
            }
            bytes.put(internal, b);
        }

        Map<String, Header> helperHeaders = new HashMap<>();
        Set<String> defineSet = new TreeSet<>();
        Set<String> ownSet = new TreeSet<>();
        for (String helper : helperSha.keySet()) {
            helperHeaders.put(helper, header(bytes.get(helper)));
            if (jarPackages.containsKey(packageOf(helper))) {
                defineSet.add(helper);
            } else {
                ownSet.add(helper);
            }
        }
        Map<String, Header> all = new HashMap<>(jarHeaders);
        all.putAll(helperHeaders);

        List<String> defineOrder = topoOrder(defineSet, all);

        // 錨點：同 package 的原版 class，先挑沒被修補的頂層 class
        Map<String, String> anchors = new TreeMap<>();
        for (String helper : defineSet) {
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
            if (anchor == null) {
                anchor = jarPackages.get(pkg).iterator().next();
            }
            anchors.put(pkg, anchor);
        }

        // 輸出：MANIFEST 先寫，其餘依名稱排序、時間戳固定
        Map<String, byte[]> entries = new TreeMap<>();
        try (var walk = Files.walk(agentClasses)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".class"))::iterator) {
                String rel = agentClasses.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith("zombie/mdc/MdcAgent")) {
                    throw new IllegalStateException("agent class 目錄有意外的檔案：" + rel);
                }
                entries.put(rel, Files.readAllBytes(p));
            }
        }
        if (!entries.containsKey("zombie/mdc/MdcAgent.class")) {
            throw new IllegalStateException("缺 zombie/mdc/MdcAgent.class");
        }
        for (String own : ownSet) {
            if (entries.put(own + ".class", bytes.get(own)) != null) {
                throw new IllegalStateException("helper 與 agent class 同名：" + own);
            }
        }
        StringBuilder index = new StringBuilder();
        index.append("version\t").append(version).append('\n');
        index.append("built\t").append(built).append('\n');
        index.append("jar\t").append(sha256(Files.readAllBytes(baseJar))).append('\n');
        for (String p : patched) {
            entries.put("mdc/patched/" + p + ".class", bytes.get(p));
            index.append("patched\t").append(p).append('\t').append(patchedOrig.get(p)).append('\t')
                    .append(patchedSha.get(p)).append('\n');
        }
        for (String d : defineOrder) {
            entries.put("mdc/define/" + d + ".class", bytes.get(d));
            index.append("define\t").append(d).append("\t-\t").append(helperSha.get(d)).append('\n');
        }
        for (Map.Entry<String, String> a : anchors.entrySet()) {
            index.append("anchor\t").append(a.getKey()).append('\t').append(a.getValue()).append('\n');
        }
        entries.put("mdc/agent-index.tsv", index.toString().getBytes(StandardCharsets.UTF_8));

        Manifest mf = new Manifest();
        Attributes main = mf.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(new Attributes.Name("Premain-Class"), "zombie.mdc.MdcAgent");
        main.put(new Attributes.Name("Can-Redefine-Classes"), "false");
        main.put(new Attributes.Name("Can-Retransform-Classes"), "false");
        main.put(Attributes.Name.IMPLEMENTATION_TITLE, "MinidoracatJavaPatch server agent");
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

        String agentSha = sha256(Files.readAllBytes(outJar));
        String text = "version\t" + version + "\n"
                + "built\t" + built + "\n"
                + "agent\t" + agentSha + "\n"
                + "jar\t" + sha256(Files.readAllBytes(baseJar)) + "\n"
                + "patched\t" + patched.size() + "\n"
                + "define\t" + defineOrder.size() + "\n"
                + "own\t" + ownSet.size() + "\n";
        Files.writeString(outManifest, text, StandardCharsets.UTF_8);
        System.out.printf("agent jar -> %s（patched %d、原版 package helper %d（錨點 %d 個 package）、我方 helper %d；sha256 %s）%n",
                outJar, patched.size(), defineOrder.size(), anchors.size(), ownSet.size(), agentSha.substring(0, 8));
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
