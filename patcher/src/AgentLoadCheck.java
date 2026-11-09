import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 建置期：在帶 {@code -javaagent:dist\mdc-javapatch-agent.jar} 的 JVM 裡、以<b>官方簽章 jar</b> 為 classpath，
 * 驗證正式服的載入路徑。
 *
 * <ul>
 *   <li>{@code armed}：agent 狀態 armed；manifest 每個 class（手術後的與 helper）都載得起來、連結（強制驗證）不出錯；
 *       手術後的 class 與原版 package 的 helper 仍帶官方簽章者（沒有 SecurityException 的前提），且換上的數目等於
 *       手術 class 數；官方 {@code IntegrityReport} 判 jar 為原版、agent 數 1（我們不改 jar、也不藏 agent）。</li>
 *   <li>{@code disarmed}：遊戲 jar 不是建置基準（拿測試 jar 跑）→ 一個 class 都不換，手術 class 以原版載入。</li>
 *   <li>{@code off}：{@code -Dmdc.agent=off} → 同上。</li>
 * </ul>
 *
 * <p>用法：{@code java -Xverify:all -javaagent:<agent jar> -cp <本工具>;<jar> AgentLoadCheck <manifest> armed|disarmed|off}
 */
public final class AgentLoadCheck {

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args[0]);
        String expect = args[1];
        ClassLoader app = ClassLoader.getSystemClassLoader();
        Class<?> agent = Class.forName("zombie.mdc.MdcAgent", true, app);
        Method status = agent.getMethod("status");
        String before = (String) status.invoke(null);
        if (!before.startsWith(expect + " ")) {
            throw new AssertionError("agent 狀態應為 " + expect + "，實得：" + before);
        }

        // 原版 package 的 helper（agent 以 Lookup.defineClass 定義的）從 agent index 讀
        Set<String> defines = new HashSet<>();
        try (var in = app.getResourceAsStream("mdc/agent-index.tsv")) {
            if (in == null) {
                throw new AssertionError("agent jar 沒有 mdc/agent-index.tsv");
            }
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (l.startsWith("define\t")) {
                    defines.add(l.split("\t")[1].replace('/', '.'));
                }
            }
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
            if (expect.equals("armed") && (patched || defines.contains(name))) {
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

        if (expect.equals("armed")) {
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

    private AgentLoadCheck() {
    }
}
