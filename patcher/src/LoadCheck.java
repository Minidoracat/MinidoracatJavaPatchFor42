import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 連結驗證：以 dist/java 優先＋遊戲 jar 的 classloader 載入每個 patch class（不觸發 clinit）。 */
public final class LoadCheck {
    public static void main(String[] args) throws Exception {
        Path distJava = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        Path manifest = Path.of(args[2]);
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{ distJava.toUri().toURL(), jar.toUri().toURL() }, null)) {
            List<String> lines = Files.readAllLines(manifest);
            for (String line : lines) {
                String cls = line.split("\t")[0].replace(".class", "").replace('/', '.');
                Class.forName(cls, false, cl);
                System.out.println("load OK  " + cls);
            }
            Class<?> lf = Class.forName("zombie.mdc.LogFilter", false, cl);
            System.out.println("load OK  zombie.mdc.LogFilter");
            // 跨類連結斷言：redirect 目標三個 helper 必須以「與 PatchConfig 改道簽名一致」的形式存在
            // （Class.forName 不會解析 INVOKESTATIC 的符號參照；缺了只會在執行期 NoSuchMethodError）
            Class<?> dt = Class.forName("zombie.debug.DebugType", false, cl);
            lf.getDeclaredMethod("warnFmt", dt, String.class, Object[].class);
            lf.getDeclaredMethod("warnObj", dt, Object.class);
            lf.getDeclaredMethod("log", String.class);
            System.out.println("helper OK warnFmt/warnObj/log 簽名與改道目標一致");
        }
        System.out.println("全部 " + (Files.readAllLines(manifest).size() + 1) + " 個 class 連結驗證通過");
    }
    private LoadCheck() {}
}
