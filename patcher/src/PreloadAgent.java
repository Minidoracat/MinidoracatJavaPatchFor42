import java.lang.instrument.Instrumentation;

/**
 * 建置期測試用的第二個 agent（AgentLoadCheck）。給 class 名稱時在 premain 先載入它（排在 MdcAgent 前面：模擬別的 agent
 * 先碰了要換的 class）；不給名稱時只留下 Instrumentation，讓測試之後對指定的 class 做一次真的 retransform
 * （排在 MdcAgent 後面：模擬別的 agent retransform 已換上的 class）。
 */
public final class PreloadAgent {
    private static Instrumentation inst;

    public static void premain(String className, Instrumentation instrumentation) throws ClassNotFoundException {
        inst = instrumentation;
        if (className != null && !className.isEmpty()) {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
        }
    }

    /** 對這些已載入的 class 做 retransform（JVM 會把原始 bytes 再交給每個 retransformation capable 的 transformer）。 */
    public static void retransform(Class<?>[] classes) throws Exception {
        inst.retransformClasses(classes);
    }

    private PreloadAgent() {
    }
}
