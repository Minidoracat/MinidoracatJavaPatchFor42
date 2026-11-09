import java.lang.instrument.Instrumentation;

/** 建置期測試用：排在 MdcAgent 前面的另一個 agent，premain 時先載入參數指定的 class（AgentLoadCheck disarmed）。 */
public final class PreloadAgent {

    public static void premain(String className, Instrumentation inst) throws ClassNotFoundException {
        Class.forName(className, false, ClassLoader.getSystemClassLoader());
    }

    private PreloadAgent() {
    }
}
