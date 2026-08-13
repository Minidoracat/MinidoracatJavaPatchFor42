import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 建置期生成 {@code zombie.mdc.PatchInfo}——把版本／建置時間／jar 同源指紋編進 class，
 * 讓正式服與玩家的 log 一眼看得出實際運行的是哪一版 patch。
 *
 * <p>刻意用生成而非手寫常數：手寫會忘記更新，而「說謊的版本號」比沒有版本號更糟。
 * 生成來源與出包檔名同源（build 腳本傳入），無法漂移。
 *
 * <p>用法：{@code java PatchInfoGen <outDir> <side> <version> <builtIso> <jarSha8>}
 */
public final class PatchInfoGen {

    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            System.err.println("用法：PatchInfoGen <outDir> <side> <version> <built> <jarSha8>");
            System.exit(2);
        }
        Path dir = Path.of(args[0], "zombie", "mdc");
        Files.createDirectories(dir);
        String src = """
                package zombie.mdc;

                import zombie.debug.DebugLog;

                /**
                 * 建置期生成（PatchInfoGen）——請勿手動編輯。
                 * 版本／建置時間／jar 同源指紋，供 log 辨識實際運行的 patch。
                 */
                public final class PatchInfo {

                    public static final String SIDE = "%s";
                    public static final String VERSION = "%s";
                    public static final String BUILT = "%s";
                    public static final String JAR = "%s";

                    private static volatile boolean announced;

                    private PatchInfo() {}

                    /**
                     * 冪等；log 成功才設旗標（DebugLog 未就緒時下次呼叫重試）。
                     * 由多個 helper 的既有 log 路徑呼叫，任一先到者印出橫幅。
                     */
                    public static void announceOnce() {
                        if (announced) {
                            return;
                        }
                        try {
                            DebugLog.log("[MinidoracatJavaPatch] " + SIDE + " patch " + VERSION
                                    + " built=" + BUILT + " jar=" + JAR);
                            announced = true;
                        } catch (Throwable t) {
                            if (t instanceof VirtualMachineError || t instanceof LinkageError) {
                                throw (Error) t;
                            }
                            // 觀測橫幅不得影響遊戲：非 fatal 一律吞掉，下次重試
                        }
                    }
                }
                """.formatted(esc(args[1]), esc(args[2]), esc(args[3]), esc(args[4]));
        Path out = dir.resolve("PatchInfo.java");
        Files.writeString(out, src, StandardCharsets.UTF_8);
        System.out.println("PatchInfo -> " + out + "（" + args[1] + " " + args[2] + "）");
    }

    /** 生成的是 Java 字面值，來源全是建置腳本可控的短字串；仍做最小逸出以防意外。 */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private PatchInfoGen() {}
}
