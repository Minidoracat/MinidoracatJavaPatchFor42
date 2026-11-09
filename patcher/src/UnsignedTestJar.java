import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 建置期：把簽章的 projectzomboid.jar 複製成「拿掉簽章檔」的測試 jar。
 *
 * <p>42.22 起 jar 簽章：同一個 package 裡 loose class 和 jar 內的 class 簽章者不同，JVM 丟
 * {@code SecurityException}（{@code ClassLoader.checkCerts}）。行為測試與 LoadCheck／SmokeCheck 用
 * 「dist\java 優先＋遊戲 jar」的 classpath 載入手術後的 class，所以改讀這份測試 jar；class bytes 與原 jar
 * 逐位元相同，只少了 {@code META-INF/*.SF|.EC|.RSA|.DSA}（沒有簽章檔就沒有 JarVerifier、沒有簽章者）。
 * 正式服不用這份 jar：上線走 Java agent 在官方簽章 jar 載入時換 bytes（{@code zombie.mdc.MdcAgent}）。
 *
 * <p>用法：{@code java UnsignedTestJar <簽章 jar> <輸出 jar>}
 */
public final class UnsignedTestJar {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("用法：UnsignedTestJar <簽章 jar> <輸出 jar>");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        int copied = 0;
        int dropped = 0;
        try (ZipFile zf = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                ZipEntry src = e.nextElement();
                if (isSignatureFile(src.getName())) {
                    dropped++;
                    continue;
                }
                ZipEntry dst = new ZipEntry(src.getName());
                dst.setTime(src.getTime());
                zos.putNextEntry(dst);
                if (!src.isDirectory()) {
                    try (InputStream is = zf.getInputStream(src)) {
                        is.transferTo(zos);
                    }
                }
                zos.closeEntry();
                copied++;
            }
        }
        if (dropped == 0) {
            Files.delete(tmp);
            throw new IllegalStateException(in + " 沒有簽章檔——不是 42.22 起的簽章 jar？");
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("測試 jar -> " + out + "（" + copied + " 個 entry，拿掉 " + dropped + " 個簽章檔）");
    }

    /** 與 JarFile 判斷要不要建 JarVerifier 的副檔名相同（MANIFEST.MF 保留：沒有 .SF 就不會驗它的摘要）。 */
    static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".EC") || upper.endsWith(".RSA") || upper.endsWith(".DSA");
    }

    private UnsignedTestJar() {}
}
