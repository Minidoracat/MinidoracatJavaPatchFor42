import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建置期測試用的第二個 agent（AgentLoadCheck）。給 class 名稱時在 premain 先載入它（排在 MdcAgent 前面：模擬別的 agent
 * 先碰了要換的 class）；不給名稱時留下 Instrumentation 並註冊一個只看不改的 retransformation capable transformer
 * （排在 MdcAgent 後面：它拿到的是 MdcAgent 處理過的 bytes），讓測試之後對指定的 class 做一次真的 retransform，
 * 並讀回每個 class 這次交出去的 bytes 的 SHA-256。
 */
public final class PreloadAgent implements ClassFileTransformer {   // 自己當 transformer：測試 agent jar 只放這一個 class 檔
    private static Instrumentation inst;
    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();

    public static void premain(String className, Instrumentation instrumentation) throws ClassNotFoundException {
        inst = instrumentation;
        if (className != null && !className.isEmpty()) {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return;
        }
        instrumentation.addTransformer(new PreloadAgent(), true);
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (redefined != null && name != null) {
            try {
                SEEN.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (Exception e) {
                SEEN.put(name, e.toString());
            }
        }
        return null;
    }

    /**
     * 對這些已載入的 class 做 retransform（JVM 會把原始 bytes 再交給每個 retransformation capable 的 transformer）。
     * 回傳：class 的內部名稱 → 排在前面的 transformer 處理後、這次要換上的 bytes 的 SHA-256。
     */
    public static Map<String, String> retransform(Class<?>[] classes) throws Exception {
        SEEN.clear();
        inst.retransformClasses(classes);
        return new HashMap<>(SEEN);
    }

    private PreloadAgent() {
    }
}
