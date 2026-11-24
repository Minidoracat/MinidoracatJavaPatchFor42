import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 建置期：在帶 {@code -javaagent:<agent jar>}（client 包另帶 {@code =modules=<id>,...}）的 JVM 裡、以<b>官方簽章 jar</b>
 * 為 classpath，驗證實際的載入路徑。manifest 給這次選到的模組（client 包是那幾個模組的 manifest 合併）。
 *
 * <ul>
 *   <li>{@code armed}：agent 狀態 armed；manifest 每個 class（手術後的與 helper）都載得起來、連結（強制驗證）不出錯；
 *       手術後的 class 與原版 package 的 helper 仍帶官方簽章者（沒有 SecurityException 的前提），且換上的數目等於
 *       手術 class 數；官方 {@code IntegrityReport} 判 jar 為原版、agent 數 1（我們不改 jar、也不藏 agent）。</li>
 *   <li>{@code disarmed}：遊戲 jar 不是建置基準（拿測試 jar 跑），或排在前面的另一個 agent 先載入了要換的 class
 *       → 一個 class 都不換，手術 class 以原版載入。</li>
 *   <li>{@code restored}：{@code -Dmdc.agent.testFailDefine=1} 讓 helper 定義完才失敗 → 已換上的 class（至少 1 個）
 *       全部還原，狀態 disarmed、換上數 0，手術 class 都還載得起來、連結不出錯。</li>
 *   <li>{@code off}：{@code -Dmdc.agent=off} → 同 disarmed。</li>
 *   <li>{@code -Dmdc.check.preload=1}（另帶排在後面的 PreloadAgent，不帶 class 名稱）：在本工具載入任何遊戲 class 之前，
 *       本包 premain 載入的遊戲 class 只能是錨點、helper 與 helper 的直接父型別，而且它們的父型別與介面都不是遊戲 class
 *       （排在後面的 agent，例如 PZ_Optimization，看到它要換的 class 已被載入就以原版執行）。</li>
 * </ul>
 *
 * <p>用法：{@code java -Xverify:all -javaagent:<agent jar>[=modules=...] -cp <本工具>;<jar> AgentLoadCheck <manifest> armed|disarmed|restored|off}
 */
public final class AgentLoadCheck {

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args[0]);
        String expect = args[1];
        String expectState = expect.equals("restored") ? "disarmed" : expect;
        ClassLoader app = ClassLoader.getSystemClassLoader();
        Class<?> agent = Class.forName("zombie.mdc.MdcAgent", true, app);
        Method status = agent.getMethod("status");
        String before = (String) status.invoke(null);
        if (!before.startsWith(expectState + " ")) {
            throw new AssertionError("agent 狀態應為 " + expectState + "，實得：" + before);
        }
        int restored = Integer.parseInt(before.replaceAll(".*restored=(\\d+).*", "$1"));
        if (expect.equals("restored") ? restored == 0 : restored != 0) {
            throw new AssertionError("還原的 class 數不符（" + expect + "）：" + before);
        }

        // agent 以 Lookup.defineClass 定義的 helper 從 agent index 讀；錨點是原版 class 的才有官方簽章者
        // （client 包連我方 package 的 helper 也用 Lookup 定義，錨點是 MdcAgent，ProtectionDomain 是 agent jar）
        Set<String> defines = new HashSet<>();
        Set<String> signedPackages = new HashSet<>();
        Set<String> anchors = new HashSet<>();
        try (var in = app.getResourceAsStream("mdc/agent-index.tsv")) {
            if (in == null) {
                throw new AssertionError("agent jar 沒有 mdc/agent-index.tsv");
            }
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = l.split("\t");
                if (f[0].equals("define")) {
                    defines.add(f[1].replace('/', '.'));
                } else if (f[0].equals("anchor")) {
                    anchors.add(f[2].replace('/', '.'));
                    if (!f[2].equals("zombie/mdc/MdcAgent")) {
                        signedPackages.add(f[1].replace('/', '.'));
                    }
                }
            }
        }

        // 要在下面第一次 Class.forName 遊戲 class 之前看：此刻已載入的遊戲 class 全是本包 premain 載入的
        boolean preload = System.getProperty("mdc.check.preload") != null;
        if (preload) {
            // helper 不分 package（zombie/mdc 的以 MdcAgent 為錨、ProtectionDomain 是 agent jar），它的直接父型別都允許
            List<Class<?>> game = new java.util.ArrayList<>();
            Set<String> allowed = new HashSet<>(anchors);
            for (Class<?> c : (Class<?>[]) Class.forName("PreloadAgent", true, app).getMethod("loaded").invoke(null)) {
                if (c.getClassLoader() == app && defines.contains(c.getName())) {
                    if (c.getSuperclass() != null) {
                        allowed.add(c.getSuperclass().getName());
                    }
                    for (Class<?> i : c.getInterfaces()) {
                        allowed.add(i.getName());
                    }
                } else if (isGameClass(c, app)) {
                    game.add(c);
                }
            }
            Set<String> bad = new java.util.TreeSet<>();
            for (Class<?> c : game) {
                if (!allowed.contains(c.getName())) {
                    bad.add(c.getName() + "（不是錨點、helper 或 helper 的父型別）");
                }
                List<Class<?>> parents = new java.util.ArrayList<>(List.of(c.getInterfaces()));
                if (c.getSuperclass() != null) {
                    parents.add(c.getSuperclass());
                }
                for (Class<?> p : parents) {
                    if (p.getClassLoader() == app) {
                        bad.add(c.getName() + "（父型別或介面 " + p.getName() + " 是 app loader 的 class，不是葉 class）");
                    }
                }
            }
            if (!bad.isEmpty()) {
                throw new AssertionError("本包 premain 載入了不該載入的遊戲 class（排在後面的 agent 會以為它要換的 class 已被載入）："
                        + String.join("、", bad));
            }
            List<String> names = new java.util.ArrayList<>();
            for (Class<?> c : game) {
                names.add(c.getName());
            }
            names.sort(null);
            System.out.println("preload 檢查通過：本包 premain 載入的遊戲 class " + names.size() + " 個：" + String.join(" ", names));
        }

        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        int patchedCount = 0;
        int loaded = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t");
            boolean patched = !f[1].equals("-");
            String name = f[0].substring(0, f[0].length() - ".class".length()).replace('/', '.');
            if (patched) {
                patchedCount++;
            } else if (!expect.equals("armed") && defines.contains(name)) {
                continue;   // 沒啟用時原版 package 的 helper 不會被定義，也沒有人引用
            }
            Class<?> c = Class.forName(name, false, app);
            c.getDeclaredMethods();   // 強制連結（-Xverify:all 下就是驗證）
            loaded++;
            if (expect.equals("armed") && (patched || defines.contains(name) && signedPackages.contains(c.getPackageName()))) {
                var cs = c.getProtectionDomain().getCodeSource();
                if (cs == null || cs.getCodeSigners() == null || cs.getCodeSigners().length == 0) {
                    throw new AssertionError(name + " 沒有官方簽章者（不是以官方 jar 的 ProtectionDomain 定義）");
                }
            }
        }
        String after = (String) status.invoke(null);
        int applied = Integer.parseInt(after.replaceAll(".*applied=(\\d+).*", "$1"));
        int mismatched = Integer.parseInt(after.replaceAll(".*mismatched=(\\d+).*", "$1"));
        int expectedApplied = expect.equals("armed") ? patchedCount : 0;
        if (applied != expectedApplied || mismatched != 0) {
            throw new AssertionError("換上的 class 應為 " + expectedApplied + "、不符 0，實得：" + after);
        }

        // -Dmdc.check.retransform=1（另帶排在後面的 PreloadAgent）：別的 agent 對全部已換上的 class 做一次真的 retransform。
        // JVM 交給本 agent 的是原始 bytes，每一個都要再交出同一份修補：由 PreloadAgent 的觀測 transformer 讀回這次要換上的
        // bytes 的 SHA，逐個比對 manifest 的手術後 SHA（沒被 retransform 到、或掉回原版，都對不上）
        boolean retransform = System.getProperty("mdc.check.retransform") != null;
        if (retransform) {
            List<Class<?>> patchedClasses = new java.util.ArrayList<>();
            java.util.Map<String, String> patchedSha = new java.util.HashMap<>();
            for (String line : lines) {
                String[] f = line.split("\t");
                if (!line.isBlank() && !f[1].equals("-")) {
                    String internal = f[0].substring(0, f[0].length() - ".class".length());
                    patchedClasses.add(Class.forName(internal.replace('/', '.'), false, app));
                    patchedSha.put(internal, f[2]);
                }
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> seen = (java.util.Map<String, String>) Class.forName("PreloadAgent", true, app)
                    .getMethod("retransform", Class[].class).invoke(null, (Object) patchedClasses.toArray(new Class<?>[0]));
            for (var e : patchedSha.entrySet()) {
                if (!e.getValue().equals(seen.get(e.getKey()))) {
                    throw new AssertionError(e.getKey() + "：retransform 之後要換上的不是修補版（期望 " + e.getValue()
                            + "，觀測到 " + seen.get(e.getKey()) + "）");
                }
            }
            for (Class<?> c : patchedClasses) {
                c.getDeclaredMethods();   // retransform 之後仍連結得起來
            }
            after = (String) status.invoke(null);
            applied = Integer.parseInt(after.replaceAll(".*applied=(\\d+).*", "$1"));
            mismatched = Integer.parseInt(after.replaceAll(".*mismatched=(\\d+).*", "$1"));
            if (!expect.equals("armed") || patchedClasses.isEmpty() || applied != patchedCount || mismatched != 0) {
                throw new AssertionError("retransform " + patchedClasses.size() + " 個 class 之後應仍是 applied=" + patchedCount
                        + "、不符 0，實得：" + after);
            }
            System.out.println("retransform " + patchedClasses.size() + " 個已換上的 class：觀測到每一個這次換上的都是修補版（SHA 相符）");
        }

        if (expect.equals("armed") && !retransform && !preload) {   // retransform／preload 情境多帶一個測試 agent，agents 不是 1
            // 官方 IntegrityReport（只讀不改）：照 collectInBackground 的順序 locateJar → new → 讀結果
            Class<?> ir = Class.forName("zombie.core.IntegrityReport", false, app);
            Method locate = ir.getDeclaredMethod("locateJar");
            locate.setAccessible(true);
            Path jar = (Path) locate.invoke(null);
            Constructor<?> ctor = ir.getDeclaredConstructor(Path.class);
            ctor.setAccessible(true);
            Object report = ctor.newInstance(jar);
            Object jarStatus = ir.getMethod("getStatus").invoke(report);
            Class<?> problem = Class.forName("zombie.core.IntegrityReport$Problem", false, app);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object agentKind = Enum.valueOf((Class) problem, "AGENT");
            int agents = ((List<?>) ir.getMethod("getProblem", problem).invoke(report, agentKind)).size();
            if (!"ORIGINAL".equals(String.valueOf(jarStatus)) || agents != 1) {
                throw new AssertionError("IntegrityReport 應為 jar=ORIGINAL、agents=1，實得 " + jarStatus + "、" + agents);
            }
            System.out.println("IntegrityReport：jar=" + jarStatus + " agents=" + agents
                    + "（官方檢查照常運作，版本字串只多 agents=1）");
        }
        System.out.println("AgentLoadCheck " + expect + " OK：載入並連結 " + loaded + " 個 class；" + after);
    }

    /** 由 app loader 從遊戲 jar（projectzomboid.jar）定義、或以它的 ProtectionDomain 定義（原版 package 的 helper）。 */
    private static boolean isGameClass(Class<?> c, ClassLoader app) {
        if (c.getClassLoader() != app) {
            return false;
        }
        var cs = c.getProtectionDomain().getCodeSource();
        return cs != null && cs.getLocation() != null && cs.getLocation().getPath().endsWith("/projectzomboid.jar");
    }

    private AgentLoadCheck() {
    }
}
