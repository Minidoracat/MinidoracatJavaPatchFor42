import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Mac 安裝器在假的 .app 樹上跑（路徑含空白、括號、中文）：安裝、切換、部分與全部移除（plist 逐位元組回原樣）、
 * 版本不符、外來 class 衝突與相容版建議、變造、中斷後重跑收斂、Steam 換回原版 plist、重複與其他 agent、
 * 各種不可編輯的 plist、互動選單與記憶體推薦。用法：java -cp <installer classes>:<test classes> MacInstallerTest <work dir>
 */
public final class MacInstallerTest {
    static final String VANILLA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            \t<key>CFBundleExecutable</key>
            \t<string>JavaAppLauncher</string>
            \t<key>NSHumanReadableCopyright</key>
            \t<string></string>
            \t<key>WorkingDirectory</key>
            \t<string>$APP_ROOT/Contents/Java</string>
            \t<key>JVMRuntime</key>
            \t<string>jre-$JRE_ARCH</string>
            \t<key>JVMMainClassName</key>
            \t<string>zombie.gameStates.MainScreenState</string>
            \t<key>JVMOptions</key>
            \t<array>
            \t\t<string>-Djava.awt.headless=true</string>
            \t\t<string>--enable-native-access=ALL-UNNAMED</string>
            \t\t<string>-XstartOnFirstThread</string>
            \t\t<string>-Xmx3072m</string>
            \t\t<string>-XX:+UseZGC</string>
            \t</array>
            \t<key>JVMArguments</key>
            \t<array/>
            </dict>
            </plist>
            """;
    static final String STD = "client-fixes-standard", LOW = "client-fixes-lowmem", COMPAT = "client-fixes-compat";

    static Path work, app, contents, root, plist, pkg;
    static String out;
    static int checks;

    public static void main(String[] args) throws Exception {
        work = Path.of(args[0]).toAbsolutePath();
        eq(MacInstaller.checkProcesses, true, "process check on by default");
        MacInstaller.checkProcesses = false;
        jsonUnit();
        processRule();
        // 解析 plist 不得連網（DOCTYPE 指向 apple.com）
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { throw new AssertionError("network access: " + uri); }
            @Override public void connectFailed(URI uri, SocketAddress sa, IOException e) {}
        });

        fresh();
        expect(0, "status");
        has("no modules installed");
        expect(2, "install", "--bogus");
        expect(2, "install", "--non-interactive");
        expect(2, "menu", "--non-interactive");
        expect(2, "uninstall", "--non-interactive");

        // 裝 profiler 自動帶 core
        expect(0, "install", "--non-interactive", "--modules", "profiler");
        Map<String, Object> st = state();
        eq(List.of("core", "profiler"), MacInstaller.moduleIds(st), "state modules");
        eq(MacInstaller.AGENT_REL, ((Map<?, ?>) st.get("agent")).get("path"), "agent path");
        eq(first(), MacInstaller.agentArg(List.of("core", "profiler")), "first JVMOption");
        eq(Files.readAllBytes(root.resolve(MacInstaller.AGENT_REL)).length, Files.readAllBytes(pkg.resolve(MacInstaller.AGENT_NAME)).length, "agent copied");
        expect(0, "install", "--non-interactive", "--modules", "profiler");
        has("Already up to date");
        expect(0, "status");
        has("Active: yes");

        // 加裝標準版、換省記憶體版；--all 沿用已裝的變體
        expect(0, "install", "--non-interactive", "--modules", STD);
        eq(List.of("core", "profiler", STD), MacInstaller.moduleIds(state()), "add standard");
        expect(0, "install", "--non-interactive", "--modules", LOW);
        eq(List.of("core", "profiler", LOW), MacInstaller.moduleIds(state()), "switch to lowmem");
        has("Replacing");
        expect(2, "install", "--non-interactive", "--modules", STD + "," + LOW);
        expect(0, "install", "--non-interactive", "--all");
        eq(List.of("core", "profiler", LOW), MacInstaller.moduleIds(state()), "--all keeps lowmem");
        eq(first(), MacInstaller.agentArg(List.of("core", "profiler", LOW)), "plist after --all");

        // 部分移除；core 仍被需要就保留
        expect(0, "uninstall", "--non-interactive", "--modules", "profiler");
        eq(List.of("core", LOW), MacInstaller.moduleIds(state()), "remove profiler");
        eq(first(), MacInstaller.agentArg(List.of("core", LOW)), "plist after partial");
        expect(0, "uninstall", "--non-interactive", "--modules", "core");
        has("still needed");
        eq(List.of("core", LOW), MacInstaller.moduleIds(state()), "core kept");
        expect(2, "uninstall", "--non-interactive", "--modules", "profiler");

        // 全部移除：plist 逐位元組回原樣、.mdc-patches 不在
        expect(0, "uninstall", "--non-interactive", "--all");
        vanilla();
        expect(0, "uninstall", "--non-interactive", "--all");
        has("Nothing is installed");

        // 遊戲版本不符：不寫任何東西
        fresh();
        writeJar(root.resolve("projectzomboid.jar"), "zombie/iso/IsoChunk.class", "zombie/Other.class");
        expect(5, "install", "--non-interactive", "--modules", STD);
        vanilla();

        // 安裝包壞掉
        fresh();
        Files.write(pkg.resolve(MacInstaller.AGENT_NAME), new byte[] {9});
        expect(6, "install", "--non-interactive", "--modules", STD);
        fresh();
        Files.writeString(pkg.resolve("manifest.json"), "{\"schemaVersion\": 1}");
        expect(6, "install", "--non-interactive", "--modules", STD);
        Files.writeString(pkg.resolve("manifest.json"), "{oops");
        expect(6, "status");
        vanilla();

        // 外來 class 蓋到要修補的（含巢狀、大小寫不同）→ 7 並指向相容版；相容版裝得上，其他外來 class 只警告
        fresh();
        Files.createDirectories(root.resolve("zombie/iso"));
        Files.write(root.resolve("zombie/iso/isochunk$Inner.class"), new byte[] {1});
        expect(7, "install", "--non-interactive", "--modules", STD);
        has("--modules " + COMPAT);
        vanilla();
        expect(0, "install", "--non-interactive", "--modules", COMPAT);
        has("not from this package");
        // 互動選單：有衝突時預設推薦相容版
        expect(0, "uninstall", "--non-interactive", "--all");
        expectIn(0, "1\n1\n\nN\nY\n\n0\n", "menu", "--ram-gb", "32");
        eq(List.of("core", COMPAT), MacInstaller.moduleIds(state()), "menu recommends compat on conflict");
        expect(0, "uninstall", "--non-interactive", "--all");
        // zombie/mdc 底下的外來 class 連相容版都擋
        Files.createDirectories(root.resolve("zombie/mdc"));
        Files.write(root.resolve("zombie/mdc/PatchInfo.class"), new byte[] {1});
        expect(7, "install", "--non-interactive", "--modules", COMPAT);
        vanilla();

        // PZ_Optimization 的 agent 形態（pzopt/agent/pzopt-agent.jar＋pzopt/overrides/；Mac 上它的 -javaagent 在 JAVA_TOOL_OPTIONS，只能看檔案）
        fresh();
        pzopt("zombie/iso/IsoChunk");
        expect(7, "install", "--non-interactive", "--modules", STD);
        has("pzopt/overrides/zombie/iso/IsoChunk.class");
        has("--modules " + COMPAT);
        vanilla();
        expect(0, "install", "--non-interactive", "--modules", COMPAT);
        eq(List.of("core", COMPAT), MacInstaller.moduleIds(state()), "pzopt: compat installs");
        eq(first(), MacInstaller.agentArg(List.of("core", COMPAT)), "pzopt: ours first");
        eq(Files.readAllBytes(root.resolve("pzopt/overrides/zombie/iso/IsoChunk.class")).length, 1, "pzopt file untouched");
        fresh();
        pzopt("zombie/iso/IsoChunk");
        expectIn(0, "N\nY\n", "quick", "--ram-gb", "32");
        eq(List.of("core", COMPAT), MacInstaller.moduleIds(state()), "pzopt: quick falls back to compat");
        fresh();
        pzopt("zombie/iso/IsoChunk");
        expectIn(0, "1\n1\n\nN\nY\n\n0\n", "menu", "--ram-gb", "32");
        eq(List.of("core", COMPAT), MacInstaller.moduleIds(state()), "pzopt: menu recommends compat");
        // 只有 overrides、沒有 agent jar（卸載殘留）不算衝突
        fresh();
        pzopt("zombie/iso/IsoChunk");
        Files.delete(root.resolve("pzopt/agent/pzopt-agent.jar"));
        expect(0, "install", "--non-interactive", "--modules", STD);
        eq(List.of("core", STD), MacInstaller.moduleIds(state()), "pzopt leftovers do not block");
        // 先裝完整版、之後才裝 pzopt：status 警告
        fresh();
        expect(0, "install", "--non-interactive", "--modules", STD);
        pzopt("zombie/iso/IsoChunk");
        expect(0, "status");
        has("PZ_Optimization also replaces classes the installed patches replace");
        // profiler 的 LuaCaller 也算
        fresh();
        pzopt("se/krka/kahlua/integration/LuaCaller");
        expect(7, "install", "--non-interactive", "--modules", "profiler");
        has("pzopt/overrides/se/krka/kahlua/integration/LuaCaller.class");
        vanilla();

        // 互動選單：記憶體推薦、[L] 切換語言
        fresh();
        expectIn(0, "1\n1\n\nN\nY\n\n0\n", "menu", "--ram-gb", "32", "--lang", "en");
        eq(List.of("core", STD), MacInstaller.moduleIds(state()), "32 GB -> standard");
        expectIn(0, "1\n1\n\nN\nY\n\n0\n", "menu", "--ram-gb", "16", "--lang", "en");
        eq(List.of("core", LOW), MacInstaller.moduleIds(state()), "16 GB -> lowmem");
        expectIn(0, "L\n4\n\n0\n", "menu", "--lang", "en");
        has("安裝狀態");
        expectIn(0, "3\nY\n\n0\n", "menu", "--lang", "zh");
        has("已全部移除");
        vanilla();
        expectIn(0, "1\n0\n0\n", "menu", "--lang", "en");
        vanilla();

        // quick：沒裝 → core＋建議變體；記憶體題按 Return＝調高；全部移除只留下調過的記憶體
        fresh();
        eq(MacInstaller.xmxMb(xmx()), MacInstaller.VANILLA_MEMORY_MB, "fixture -Xmx is the vanilla value");
        expectIn(0, "\nY\n", "quick", "--ram-gb", "32");
        eq(List.of("core", STD), MacInstaller.moduleIds(state()), "quick 32 GB -> standard");
        has("3072 → 8192 MB");
        eq(first(), MacInstaller.agentArg(List.of("core", STD)), "quick plist");
        eq(xmx(), "-Xmx8192m", "memory raised");
        expect(0, "status");
        has("Game memory limit: 8192 MB");
        expect(0, "uninstall", "--non-interactive", "--all");
        has("stays at 8192 MB");
        eq(Files.readString(plist), VANILLA.replace("-Xmx3072m", "-Xmx8192m"), "removal keeps only the memory change");
        // 16 GB 答 N：省記憶體版、記憶體不動
        fresh();
        expectIn(0, "N\nY\n", "quick", "--ram-gb", "16");
        has("6144 MB is recommended");
        eq(List.of("core", LOW), MacInstaller.moduleIds(state()), "quick 16 GB -> lowmem");
        eq(xmx(), "-Xmx3072m", "memory kept on N");
        expect(0, "uninstall", "--non-interactive", "--all");
        vanilla();
        // 在記憶體題就讀到 EOF：什麼都不寫
        fresh();
        expectIn(0, "", "quick", "--ram-gb", "32");
        has("Cancelled");
        vanilla();
        // 已裝省記憶體版＋profiler：32 GB 也沿用，不換成標準版；部分移除只剩 core 時補上變體
        fresh();
        expect(0, "install", "--non-interactive", "--modules", "profiler," + LOW);
        expectIn(0, "N\nY\n", "quick", "--ram-gb", "32");
        eq(List.of("core", "profiler", LOW), MacInstaller.moduleIds(state()), "quick keeps the installed variant");
        expect(0, "uninstall", "--non-interactive", "--modules", "profiler," + LOW);
        eq(List.of("core"), MacInstaller.moduleIds(state()), "only core left");
        expectIn(0, "N\nY\n", "quick", "--ram-gb", "32");
        eq(List.of("core", STD), MacInstaller.moduleIds(state()), "quick adds a variant when only core is left");
        // 已是最新、這次才同意調高：只改記憶體；答 yes 這類字會再問一次
        expectIn(0, "yes\n\nY\n", "quick", "--ram-gb", "32");
        has("Please enter: Y / N");
        eq(List.of("core", STD), MacInstaller.moduleIds(state()), "modules unchanged when only memory changes");
        eq(xmx(), "-Xmx8192m", "memory raised on an up-to-date install");
        // 外來 class 擋住完整版：quick 改裝相容版
        fresh();
        Files.createDirectories(root.resolve("zombie/iso"));
        Files.write(root.resolve("zombie/iso/IsoChunk.class"), new byte[] {1});
        expectIn(0, "N\nY\n", "quick", "--ram-gb", "32");
        eq(List.of("core", COMPAT), MacInstaller.moduleIds(state()), "quick falls back to compat");
        // 已經比建議值高：不問、不降；8 GB 與不知道記憶體：不問
        fresh();
        Files.writeString(plist, VANILLA.replace("-Xmx3072m", "-Xmx12288m"));
        expectIn(0, "Y\n", "quick", "--ram-gb", "32");
        eq(out.contains("Raise it?"), false, "no question above the recommendation");
        eq(xmx(), "-Xmx12288m", "never lowered");
        for (String ram : List.of("8", "0")) {
            fresh();
            expectIn(0, "Y\n", "quick", "--ram-gb", ram);
            eq(out.contains("Raise it?"), false, "no question with " + ram + " GB");
            eq(xmx(), "-Xmx3072m", "kept with " + ram + " GB");
        }
        // 認不得的 -Xmx（沒有、兩條、單位不對、g 太大會溢位、另有 -XX:MaxHeapSize）：照常安裝、不問；--memory-mb 則 7
        for (String odd : List.of(VANILLA.replace("\t\t<string>-Xmx3072m</string>\n", ""),
                VANILLA.replace("\t\t<string>-Xmx3072m</string>\n", "\t\t<string>-Xmx3072m</string>\n\t\t<string>-Xmx4g</string>\n"),
                VANILLA.replace("-Xmx3072m", "-Xmx3145728k"),
                VANILLA.replace("-Xmx3072m", "-Xmx3000000g"),
                VANILLA.replace("\t\t<string>-Xmx3072m</string>\n", "\t\t<string>-Xmx3072m</string>\n\t\t<string>-XX:MaxHeapSize=4g</string>\n"))) {
            fresh();
            Files.writeString(plist, odd);
            expectIn(0, "Y\n", "quick", "--ram-gb", "32");
            eq(out.contains("Raise it?"), false, "no question for an unknown -Xmx");
            eq(first(), MacInstaller.agentArg(List.of("core", STD)), "installed despite an unknown -Xmx");
            expect(7, "install", "--non-interactive", "--modules", STD, "--memory-mb", "8192");
        }
        // -Xmx3g 當成 3072 MB
        fresh();
        Files.writeString(plist, VANILLA.replace("-Xmx3072m", "-Xmx3g"));
        expectIn(0, "\nY\n", "quick", "--ram-gb", "32");
        has("3072 → 8192 MB");
        eq(xmx(), "-Xmx8192m", "3g raised");
        // --memory-mb：非互動照做；太小或不是數字 → 2
        fresh();
        expect(0, "install", "--non-interactive", "--modules", STD, "--memory-mb", "6144");
        eq(xmx(), "-Xmx6144m", "--memory-mb applied");
        eq(first(), MacInstaller.agentArg(List.of("core", STD)), "--memory-mb with the launch option");
        expect(2, "install", "--non-interactive", "--modules", STD, "--memory-mb", "100");
        expect(2, "install", "--non-interactive", "--modules", STD, "--memory-mb", "abc");
        // --memory-mb 低於既有 -Xms（或認不得的初始堆積）：JVM 會拒絕啟動 → 7，plist 不動
        fresh();
        String withXms = VANILLA.replace("\t\t<string>-Xmx3072m</string>\n", "\t\t<string>-Xms4096m</string>\n\t\t<string>-Xmx8192m</string>\n");
        Files.writeString(plist, withXms);
        expect(7, "install", "--non-interactive", "--modules", STD, "--memory-mb", "3072");
        eq(Files.readString(plist), withXms, "plist untouched when -Xms is larger");
        expect(0, "install", "--non-interactive", "--modules", STD, "--memory-mb", "4096");
        eq(xmx(), "-Xmx4096m", "--memory-mb equal to -Xms is fine");
        fresh();
        Files.writeString(plist, VANILLA.replace("\t\t<string>-Xmx3072m</string>\n", "\t\t<string>-XX:InitialHeapSize=1g</string>\n\t\t<string>-Xmx3072m</string>\n"));
        expect(7, "install", "--non-interactive", "--modules", STD, "--memory-mb", "8192");
        // Steam 換回原版 plist 後再跑 quick：補回參數，記憶體再問一次
        fresh();
        expectIn(0, "\nY\n", "quick", "--ram-gb", "32");
        Files.writeString(plist, VANILLA);
        expectIn(0, "\nY\n", "quick", "--ram-gb", "32");
        has("3072 → 8192 MB");
        eq(first(), MacInstaller.agentArg(List.of("core", STD)), "quick restores the launch option");
        // 寫 plist 時中斷：記憶體也沒改，plist 不指向不存在的 jar
        fresh();
        MacInstaller.failAt = "plist";
        expect(12, "install", "--non-interactive", "--modules", STD, "--memory-mb", "8192");
        MacInstaller.failAt = null;
        noDanglingArg("install with memory interrupted at plist");
        eq(xmx(), "-Xmx3072m", "memory untouched when the plist write fails");

        // 主選單 [5] 調整記憶體上限：選單吞掉動作的錯誤、行程仍 0，錯誤看輸出的 (code N)
        fresh();
        expectIn(0, "0\n", "menu", "--ram-gb", "32");
        has("[5] Change the game memory limit (now 3072 MB, recommended 8192 MB)");
        expectIn(0, "0\n", "menu", "--ram-gb", "8");
        has("(now 3072 MB)");
        expectIn(0, "0\n", "menu", "--ram-gb", "32", "--lang", "zh");
        has("[5] 調整遊戲記憶體上限（目前 3072 MB，建議 8192 MB）");
        // 沒裝修補選 [1]：只改 -Xmx、不建 .mdc-patches；[3] 恢復後逐位元組回原版
        expectIn(0, "5\n1\nY\n\n0\n", "menu", "--ram-gb", "32");
        has("3072 → 8192 MB");
        eq(Files.readString(plist), VANILLA.replace("-Xmx3072m", "-Xmx8192m"), "menu [5] raised only -Xmx");
        eq(Files.exists(root.resolve(".mdc-patches")), false, "no .mdc-patches from the memory menu");
        expectIn(0, "5\n1\n\n0\n", "menu", "--ram-gb", "32");
        has("It is already 8192 MB");
        expectIn(0, "5\n3\nN\n\n0\n", "menu", "--ram-gb", "32");
        has("Cancelled");
        expectIn(0, "5\n3\nY\n\n0\n", "menu", "--ram-gb", "32");
        vanilla();
        noLeftovers();
        // 8 GB 沒有 [1]；在子選單或自訂處讀到 EOF：不寫
        expectIn(0, "5\n1\n0\n\n0\n", "menu", "--ram-gb", "8");
        has("Please enter: 0 / 2 / 3");
        expectIn(0, "5\n2\n", "menu", "--ram-gb", "32");
        vanilla();
        // 已裝修補：自己那行原位，state 不變；全部移除只留下 -Xmx
        expect(0, "install", "--non-interactive", "--modules", STD);
        byte[] stateBefore = Files.readAllBytes(root.resolve(MacInstaller.STATE_REL));
        expectIn(0, "5\n1\nY\n\n0\n", "menu", "--ram-gb", "32");
        eq(first(), MacInstaller.agentArg(List.of("core", STD)), "launch option kept by the memory menu");
        eq(xmx(), "-Xmx8192m", "memory menu with patches installed");
        eq(Arrays.equals(stateBefore, Files.readAllBytes(root.resolve(MacInstaller.STATE_REL))), true, "state untouched by the memory menu");
        expect(0, "status");
        has("Active: yes");
        expect(0, "uninstall", "--non-interactive", "--all");
        eq(Files.readString(plist), VANILLA.replace("-Xmx3072m", "-Xmx8192m"), "removal keeps the menu's memory change");
        // [2] 自訂：格式、下限、RAM 上限重問；過半警告；偵測不到 RAM 不設上限；空行取消
        fresh();
        expectIn(0, "5\n2\nabc\n100\n8g\n40000\n10240\nY\n\n0\n", "menu", "--ram-gb", "32");
        has("cannot be larger than that");
        eq(out.contains("more than half"), false, "no warning below half");
        eq(xmx(), "-Xmx10240m", "custom value applied");
        expectIn(0, "5\n2\n12288\nY\n\n0\n", "menu", "--ram-gb", "16");
        has("more than half of this Mac's RAM");
        eq(xmx(), "-Xmx12288m", "custom value above half applied");
        expectIn(0, "5\n2\n65536\nY\n\n0\n", "menu");
        eq(xmx(), "-Xmx65536m", "no upper bound without RAM size");
        expectIn(0, "5\n2\n\n\n0\n", "menu", "--ram-gb", "32");
        has("Cancelled");
        eq(xmx(), "-Xmx65536m", "empty custom value cancels");
        // -Xms：恢復預設被擋（7、plist 不動）；自訂低於 -Xms 重問
        fresh();
        Files.writeString(plist, withXms);
        expectIn(0, "5\n3\n\n0\n", "menu", "--ram-gb", "32");
        has("(code 7)");
        has("-Xms4096m");
        eq(Files.readString(plist), withXms, "restore default blocked by -Xms");
        expectIn(0, "5\n2\n3500\n4096\nY\n\n0\n", "menu", "--ram-gb", "32");
        has("larger than 3500 MB");
        eq(xmx(), "-Xmx4096m", "custom value equal to -Xms");
        // 讀不懂的 plist 與認不得的 -Xmx：無法判斷、選 [5] 得 7；啟動器前提不符（WorkingDirectory）仍可改
        fresh();
        Files.writeString(plist, VANILLA.replace("<dict>", "<dict>\n<!-- c -->"));
        expectIn(0, "5\n\n0\n", "menu", "--ram-gb", "32");
        has("now: unknown");
        has("(code 7)");
        Files.writeString(plist, VANILLA.replace("-Xmx3072m", "-Xmx3145728k"));
        expectIn(0, "5\n\n0\n", "menu", "--ram-gb", "32");
        has("(code 7)");
        String otherWd = VANILLA.replace("$APP_ROOT/Contents/Java</string>", "$APP_ROOT/Contents</string>");
        Files.writeString(plist, otherWd);
        expectIn(0, "5\n1\nY\n\n0\n", "menu", "--ram-gb", "32");
        eq(Files.readString(plist), otherWd.replace("-Xmx3072m", "-Xmx8192m"), "memory menu skips the agent preconditions");
        // 寫入中斷 → 12、plist 不動；持鎖 → 4
        fresh();
        MacInstaller.failAt = "plist";
        expectIn(0, "5\n1\nY\n\n0\n", "menu", "--ram-gb", "32");
        MacInstaller.failAt = null;
        has("(code 12)");
        eq(Files.readString(plist), VANILLA, "plist untouched when the memory write fails");
        try (FileChannel ch = FileChannel.open(root.resolve(".mdc-patches.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock l = ch.lock()) {
            expectIn(0, "5\n\n0\n", "menu", "--ram-gb", "32");
            has("(code 4)");
        }
        expectIn(0, "5\n1\nY\n\n0\n", "menu", "--ram-gb", "32");
        eq(xmx(), "-Xmx8192m", "memory menu works after the failed write");
        noLeftovers();

        // 修補檔被變造：不刪；換回包內那份就能刪
        fresh();
        expect(0, "install", "--non-interactive", "--modules", STD);
        Path agent = root.resolve(MacInstaller.AGENT_REL);
        Files.write(agent, new byte[] {7, 7});
        expect(8, "uninstall", "--non-interactive", "--all");
        expect(8, "install", "--non-interactive", "--modules", STD);
        expect(0, "status");
        has("PROBLEM");
        Files.copy(pkg.resolve(MacInstaller.AGENT_NAME), agent, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        expect(0, "uninstall", "--non-interactive", "--all");
        vanilla();

        // 中斷在各步驟：重跑收斂，暫存檔被清
        for (String step : List.of("jar", "state", "plist")) {
            fresh();
            MacInstaller.failAt = step;
            expect(12, "install", "--non-interactive", "--modules", STD);
            noDanglingArg("install interrupted at " + step);
            MacInstaller.failAt = null;
            if (!step.equals("jar")) {
                expect(0, "status");
                if (step.equals("plist")) has("Installed but not active");
            }
            expect(0, "install", "--non-interactive", "--modules", STD);
            eq(first(), MacInstaller.agentArg(List.of("core", STD)), "converged after " + step);
            noLeftovers();
            MacInstaller.failAt = step;
            expect(12, "uninstall", "--non-interactive", "--all");
            noDanglingArg("uninstall interrupted at " + step);
            MacInstaller.failAt = null;
            expect(0, "uninstall", "--non-interactive", "--all");
            vanilla();
            noLeftovers();
        }

        // Steam 換回原版 plist：status 說沒啟用；部分移除不替玩家加回；重裝補回；全部移除不碰 plist
        fresh();
        expect(0, "install", "--non-interactive", "--modules", "profiler," + STD);
        Files.writeString(plist, VANILLA);
        expect(0, "status");
        has("Installed but not active");
        expect(0, "uninstall", "--non-interactive", "--modules", "profiler");
        eq(Files.readString(plist), VANILLA, "partial uninstall keeps vanilla plist");
        expect(0, "install", "--non-interactive", "--modules", STD);
        eq(first(), MacInstaller.agentArg(List.of("core", STD)), "reinstall restores option");
        Files.writeString(plist, VANILLA);
        expect(0, "uninstall", "--non-interactive", "--all");
        vanilla();

        // 沒有紀錄但 plist 有自家參數：status 提醒、全部移除拿掉
        fresh();
        Files.writeString(plist, VANILLA.replace("\t<array>\n", "\t<array>\n\t\t<string>-javaagent:.mdc-patches/MinidoracatClientPatches.jar=modules=core</string>\n"));
        expect(0, "status");
        has("no install record");
        expect(0, "uninstall", "--non-interactive", "--all");
        vanilla();

        // 自家參數兩條（含絕對路徑）＋別人的 agent：裝完自家剩一條在最前，別人的保留並列出；全部移除回到玩家的版本
        fresh();
        String user = VANILLA.replace("\t\t<string>-Xmx3072m</string>\n",
                "\t\t<string>-javaagent:/x/y/.mdc-patches/MinidoracatClientPatches.jar=modules=core</string>\n"
                        + "\t\t<string>-javaagent:/opt/other.jar</string>\n"
                        + "\t\t<string>-javaagent:.mdc-patches/MinidoracatClientPatches.jar=modules=core,profiler</string>\n"
                        + "\t\t<string>-Xmx3072m</string>\n");
        Files.writeString(plist, user);
        expect(0, "install", "--non-interactive", "--modules", STD);
        has("other Java agents");
        List<String> opts = InfoPlist.parse(Files.readAllBytes(plist)).options;
        eq(opts.get(0), MacInstaller.agentArg(List.of("core", STD)), "ours first");
        eq(opts.stream().filter(MacInstaller::isOurArg).count(), 1L, "duplicates collapsed");
        eq(opts.contains("-javaagent:/opt/other.jar"), true, "other agent kept");
        expect(0, "uninstall", "--non-interactive", "--all");
        eq(Files.readString(plist), VANILLA.replace("\t\t<string>-Xmx3072m</string>\n", "\t\t<string>-javaagent:/opt/other.jar</string>\n\t\t<string>-Xmx3072m</string>\n"),
                "uninstall keeps the other agent and drops ours");

        // CRLF、空白縮排、BOM：往返逐位元組
        for (String variant : List.of("crlf", "spaces", "bom")) {
            fresh();
            byte[] orig = switch (variant) {
                case "crlf" -> VANILLA.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
                case "spaces" -> VANILLA.replace("\t", "    ").getBytes(StandardCharsets.UTF_8);
                default -> concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, VANILLA.getBytes(StandardCharsets.UTF_8));
            };
            Files.write(plist, orig);
            expect(0, "install", "--non-interactive", "--modules", STD);
            eq(first(), MacInstaller.agentArg(List.of("core", STD)), variant + " install");
            if (variant.equals("crlf")) eq(new String(Files.readAllBytes(plist), StandardCharsets.UTF_8).replace("\r\n", "").contains("\n"), false, "crlf kept");
            expect(0, "uninstall", "--non-interactive", "--all");
            eq(Arrays.equals(Files.readAllBytes(plist), orig), true, variant + " round trip");
            // 加上記憶體調整：移除後只差那個數字
            expect(0, "install", "--non-interactive", "--modules", STD, "--memory-mb", "8192");
            expect(0, "uninstall", "--non-interactive", "--all");
            eq(Arrays.equals(Files.readAllBytes(plist), new String(orig, StandardCharsets.UTF_8).replace("-Xmx3072m", "-Xmx8192m").getBytes(StandardCharsets.UTF_8)), true,
                    variant + " round trip with memory");
        }

        // 不可編輯的 plist：安裝 7；全部移除時文字裡有自家參數 → 7，沒有就照常刪檔
        for (String bad : List.of(
                VANILLA.replace("<dict>", "<dict>\n<!-- c -->"),
                VANILLA.replace("\t<array>\n\t\t<string>-Djava", "\t<array><string>-Djava"),
                VANILLA.replace("\t\t<string>-Xmx3072m</string>\n", "\t\t<integer>1</integer>\n"),
                VANILLA.replace("<key>JVMOptions</key>", "<key>JVMOptions</key><key>JVMOptions</key>"),
                VANILLA.replaceAll("(?s)\t<key>JVMOptions</key>.*?</array>\n", "\t<key>JVMOptions</key>\n\t<array/>\n"),
                "bplist00\u0000\u00ff")) {
            fresh();
            Files.write(plist, bad.getBytes(bad.startsWith("bplist") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
            byte[] before = Files.readAllBytes(plist);
            expect(7, "install", "--non-interactive", "--modules", STD);
            eq(Arrays.equals(before, Files.readAllBytes(plist)), true, "not touched");
        }
        fresh();
        String commented = VANILLA.replace("<dict>", "<dict>\n<!-- c -->");
        Files.writeString(plist, commented);
        Files.createDirectories(root.resolve(".mdc-patches"));
        Files.writeString(root.resolve(".mdc-patches/x.txt"), "x");
        expect(0, "uninstall", "--non-interactive", "--all");
        has("still has files");
        Files.writeString(plist, commented.replace("\t\t<string>-Xmx3072m</string>\n", "\t\t<string>-javaagent:.mdc-patches/MinidoracatClientPatches.jar=modules=core</string>\n\t\t<string>-Xmx3072m</string>\n"));
        expect(7, "uninstall", "--non-interactive", "--all");
        // binary plist 讀不出文字：全部移除不得刪 jar 而留下啟動參數
        fresh();
        expect(0, "install", "--non-interactive", "--modules", STD);
        Files.write(plist, "bplist00\u0000\u00ff".getBytes(StandardCharsets.ISO_8859_1));
        expect(7, "uninstall", "--non-interactive", "--all");
        eq(Files.isRegularFile(root.resolve(MacInstaller.AGENT_REL)), true, "jar kept when plist unreadable");
        expect(0, "status");
        has("cannot tell whether active");
        // state 記的修補檔位置不對 → 7，不寫任何東西
        fresh();
        expect(0, "install", "--non-interactive", "--modules", STD);
        Path statePath = root.resolve(MacInstaller.STATE_REL);
        Files.writeString(statePath, Files.readString(statePath).replace(MacInstaller.AGENT_REL, ".mdc-patches/other.jar"));
        byte[] plistBefore = Files.readAllBytes(plist);
        expect(7, "install", "--non-interactive", "--modules", LOW);
        eq(Arrays.equals(plistBefore, Files.readAllBytes(plist)), true, "plist untouched on bad state path");

        // 啟動器前提不符
        fresh();
        Files.writeString(plist, VANILLA.replace("$APP_ROOT/Contents/Java</string>", "$APP_ROOT/Contents</string>"));
        expect(7, "install", "--non-interactive", "--modules", STD);
        Files.writeString(plist, VANILLA.replace("<key>JVMArguments</key>", "<key>JVMClassPath</key>\n\t<string>x</string>\n\t<key>JVMArguments</key>"));
        expect(7, "install", "--non-interactive", "--modules", STD);
        Files.writeString(plist, VANILLA);
        Files.write(root.resolve("aaa.jar"), new byte[] {1});
        expect(7, "install", "--non-interactive", "--modules", STD);
        Files.delete(root.resolve("aaa.jar"));
        expect(0, "install", "--non-interactive", "--modules", STD);

        // 另一個管理器持鎖 → 4
        try (FileChannel ch = FileChannel.open(root.resolve(".mdc-patches.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock l = ch.lock()) {
            expect(4, "uninstall", "--non-interactive", "--all");
        }
        expect(0, "uninstall", "--non-interactive", "--all");
        vanilla();

        // .mdc-patches 是符號連結 → 7（建不了就略過）
        fresh();
        Path target = Files.createDirectories(work.resolve("elsewhere"));
        try {
            Files.createSymbolicLink(root.resolve(".mdc-patches"), target);
            expect(7, "install", "--non-interactive", "--modules", STD);
            Files.delete(root.resolve(".mdc-patches"));
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("SKIP symlink case: " + e);
        }

        // Info.plist 的 POSIX 權限保留（只在 POSIX 檔案系統）
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            fresh();
            Files.setPosixFilePermissions(plist, PosixFilePermissions.fromString("rwxr-xr-x"));
            expect(0, "install", "--non-interactive", "--modules", STD);
            eq(PosixFilePermissions.toString(Files.getPosixFilePermissions(plist)), "rwxr-xr-x", "plist mode kept");
            expect(0, "uninstall", "--non-interactive", "--all");
            eq(PosixFilePermissions.toString(Files.getPosixFilePermissions(plist)), "rwxr-xr-x", "plist mode kept after uninstall");
        } else {
            System.out.println("SKIP posix permission case");
        }

        // 遊戲資料夾（不是 .app）也接受；不是遊戲 → 3
        fresh();
        expect(0, "status", "--app-dir-mode");
        eq(run(new String[] {"status", "--app", work.resolve("nothing").toString(), "--package-dir", pkg.toString()}, ""), 3, "not a game");
        System.out.println("MacInstallerTest OK (" + checks + " checks)");
    }

    // ------------------------------------------------------------------ fixtures
    static void fresh() throws IOException {
        if (Files.exists(work)) {
            try (Stream<Path> s = Files.walk(work)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        Path game = work.resolve("Steam 遊戲 (test)/ProjectZomboid");
        app = game.resolve("Project Zomboid.app");
        contents = app.resolve("Contents");
        root = contents.resolve("Java");
        plist = contents.resolve("Info.plist");
        Files.createDirectories(root);
        Files.createDirectories(contents.resolve("MacOS"));
        Files.write(contents.resolve("MacOS/JavaAppLauncher"), new byte[] {1});
        Files.writeString(plist, VANILLA);
        writeJar(root.resolve("projectzomboid.jar"), "zombie/iso/IsoChunk.class", "zombie/vehicles/BaseVehicle.class", "zombie/core/Core.class");
        pkg = work.resolve("pkg dir");
        Files.createDirectories(pkg);
        byte[] agent = "fake agent jar".getBytes(StandardCharsets.UTF_8);
        Files.write(pkg.resolve(MacInstaller.AGENT_NAME), agent);
        String jarSha = MacInstaller.sha256(root.resolve("projectzomboid.jar"));
        Files.writeString(pkg.resolve("manifest.json"), """
                {"schemaVersion": 2, "gameVersion": "42.22.2", "packageVersion": "9.9.9", "jarSha256": "%s",
                 "agent": {"source": "MinidoracatClientPatches.jar", "sha256": "%s"},
                 "modules": [
                  {"id": "core", "name": "核心", "nameEn": "Core", "version": "c1", "requires": [], "patchedClasses": ["zombie/core/Core"]},
                  {"id": "profiler", "name": "P", "nameEn": "P", "version": "p1", "requires": ["core"], "patchedClasses": ["se/krka/kahlua/integration/LuaCaller"]},
                  {"id": "client-fixes-standard", "name": "S", "nameEn": "S", "version": "s1", "requires": ["core"], "exclusiveGroup": "client-fixes",
                   "patchedClasses": ["zombie/iso/IsoChunk", "zombie/vehicles/BaseVehicle"]},
                  {"id": "client-fixes-lowmem", "name": "L", "nameEn": "L", "version": "l1", "requires": ["core"], "exclusiveGroup": "client-fixes",
                   "patchedClasses": ["zombie/iso/IsoChunk", "zombie/vehicles/BaseVehicle"]},
                  {"id": "client-fixes-compat", "name": "C", "nameEn": "C", "version": "x1", "requires": ["core"], "exclusiveGroup": "client-fixes",
                   "patchedClasses": ["zombie/vehicles/BaseVehicle"]}
                 ]}
                """.formatted(jarSha, MacInstaller.sha256(agent)));
    }

    /** 放 PZ_Optimization agent 形態的檔案：agent jar＋pzopt/overrides/ 底下的 cls.class（內容 1 byte）。 */
    static void pzopt(String cls) throws IOException {
        Path agent = root.resolve("pzopt/agent/pzopt-agent.jar"), over = root.resolve("pzopt/overrides/" + cls + ".class");
        Files.createDirectories(agent.getParent());
        Files.createDirectories(over.getParent());
        Files.write(agent, new byte[] {1});
        Files.write(over, new byte[] {1});
    }

    static void writeJar(Path jar, String... entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write(e.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    static int run(String[] args, String input) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int code = MacInstaller.run(args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(b, true, StandardCharsets.UTF_8));
        out = b.toString(StandardCharsets.UTF_8);
        return code;
    }

    static void expectIn(int code, String input, String... args) {
        String[] full = Arrays.copyOf(args, args.length + 4);
        boolean appDir = Arrays.asList(args).contains("--app-dir-mode");
        if (appDir) full = Arrays.stream(args).filter(a -> !a.equals("--app-dir-mode")).toArray(n -> new String[n + 4]);
        int n = appDir ? args.length - 1 : args.length;
        full[n] = "--app";
        full[n + 1] = (appDir ? app.getParent() : app).toString();
        full[n + 2] = "--package-dir";
        full[n + 3] = pkg.toString();
        if (!Arrays.asList(full).contains("--lang")) {
            full = Arrays.copyOf(full, full.length + 2);
            full[full.length - 2] = "--lang";
            full[full.length - 1] = "en";
        }
        int got = run(full, input);
        if (got != code) throw new AssertionError("exit " + got + " != " + code + " for " + Arrays.toString(args) + "\n" + out);
        checks++;
    }

    static void expect(int code, String... args) {
        expectIn(code, "", args);
    }

    static void has(String text) {
        if (!out.contains(text)) throw new AssertionError("output lacks \"" + text + "\":\n" + out);
        checks++;
    }

    static void eq(Object a, Object b, String what) {
        if (!a.equals(b)) throw new AssertionError(what + ": " + a + " != " + b + "\n" + out);
        checks++;
    }

    static Map<String, Object> state() throws IOException {
        return Json.obj(Json.parse(Files.readString(root.resolve(MacInstaller.STATE_REL))), "state");
    }

    static String first() throws IOException {
        InfoPlist p = InfoPlist.parse(Files.readAllBytes(plist));
        if (p.problem != null) throw new AssertionError("plist problem: " + p.problem);
        return p.options.get(0);
    }

    static String xmx() throws IOException {
        List<String> o = InfoPlist.parse(Files.readAllBytes(plist)).options;
        return o.get(MacInstaller.xmxIndex(o));
    }

    static void vanilla() throws IOException {
        eq(Files.readString(plist), VANILLA, "plist back to vanilla bytes");
        eq(Files.exists(root.resolve(".mdc-patches")), false, ".mdc-patches removed");
        eq(Files.exists(root.resolve(".mdc-patches.lock")), false, "lock file removed");
    }

    /** 不做 journal 的安全論證：任何中斷點上，plist 有本包參數時 jar 一定在。 */
    static void noDanglingArg(String where) throws IOException {
        if (new String(Files.readAllBytes(plist), StandardCharsets.UTF_8).contains(MacInstaller.AGENT_NAME)) {
            eq(Files.isRegularFile(root.resolve(MacInstaller.AGENT_REL)), true, "plist points at an existing jar (" + where + ")");
        }
    }

    static void noLeftovers() {
        for (Path p : List.of(contents.resolve("Info.plist.mdc-new"), root.resolve(MacInstaller.AGENT_REL + ".mdc-new"), root.resolve(MacInstaller.STATE_REL + ".mdc-new"))) {
            eq(Files.exists(p), false, "leftover " + p.getFileName());
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ------------------------------------------------------------------ units
    static void jsonUnit() {
        String text = "{\"a\": [1, 2.5, true, null, \"x\\\"\\u00e9\\n\"], \"b\": {}}";
        Object v = Json.parse(text);
        eq(Json.parse(Json.write(v)), v, "json round trip");
        eq(((List<?>) Json.obj(v, "v").get("a")).get(4), "x\"é\n", "json escapes");
        for (String bad : List.of("{", "{\"a\":1,}", "[1 2]", "{\"a\":1}x", "{\"a\":1,\"a\":2}", "\"\\q\"", "[" .repeat(100) + "]".repeat(100))) {
            try {
                Json.parse(bad);
                throw new AssertionError("accepted bad JSON " + bad);
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
    }

    static void processRule() {
        Path a = Path.of("/Users/x/Steam/ProjectZomboid/Project Zomboid.app");
        eq(MacInstaller.isGameProcess(a + "/Contents/MacOS/JavaAppLauncher", "", a), true, "launcher");
        eq(MacInstaller.isGameProcess(a + "/Contents/PlugIns/jre-aarch64/Contents/Home/bin/java", "", a), true, "game JRE");
        eq(MacInstaller.isGameProcess("/usr/bin/java", "java -cp x zombie.network.GameServer", a), true, "server cmdline");
        eq(MacInstaller.isGameProcess("/usr/bin/java", "java zombie.gameStates.MainScreenState", a), true, "client cmdline");
        eq(MacInstaller.isGameProcess("/Applications/Safari.app/Contents/MacOS/Safari", "Safari", a), false, "other app");
        eq(MacInstaller.isGameProcess("", "", a), false, "unknown");
    }
}
