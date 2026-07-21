import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

/** 離線 JVMS 資料流驗證：對每個 patch class 跑 ASM CheckClassAdapter（等價於 JVM verifier 的檢查）。 */
public final class BytecodeVerify {
    public static void main(String[] args) throws Exception {
        Path distJava = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        Path manifest = Path.of(args[2]);
        int failed = 0;
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{ distJava.toUri().toURL(), jar.toUri().toURL() },
                BytecodeVerify.class.getClassLoader())) {
            List<String> lines = Files.readAllLines(manifest);
            for (String line : lines) {
                String entry = line.split("\t")[0];
                byte[] bytes = Files.readAllBytes(distJava.resolve(entry));
                StringWriter sw = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(bytes), cl, false, new PrintWriter(sw));
                if (sw.toString().contains("AnalyzerException") || sw.toString().contains("Exception")) {
                    failed++;
                    System.out.println("VERIFY FAIL  " + entry);
                    System.out.println(sw.toString().lines().limit(12).reduce("", (a, b) -> a + b + "\n"));
                } else {
                    System.out.println("verify OK    " + entry);
                }
            }
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("全部 patch class 通過 JVMS 資料流驗證");
    }
    private BytecodeVerify() {}
}
