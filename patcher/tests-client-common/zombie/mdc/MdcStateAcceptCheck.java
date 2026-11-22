package zombie.mdc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 另一個安裝器（macOS 版）寫出的安裝紀錄，遊戲裡的 MdcPatchRuntime 要認得：用它自己的 readInstallation 讀，
 * 讀不到或模組數不對就失敗。用法：MdcStateAcceptCheck <遊戲根> <agent jar> <模組,逗號分隔>
 * （名字不以 Test 結尾：build-client.ps1 [5/8] 會把 *Test 不帶參數全跑一遍）
 */
public final class MdcStateAcceptCheck {
    public static void main(String[] args) throws Exception {
        Set<String> modules = Set.of(args[2].split(","));
        List<Map<String, Object>> installed = MdcPatchRuntime.readInstallation(Path.of(args[0]), Path.of(args[1]), modules);
        if (installed.size() != modules.size()) throw new AssertionError("runtime accepted " + installed.size() + " of " + modules.size() + " modules");
        System.out.println("MdcStateAcceptCheck OK: " + installed.size() + " modules accepted");
    }
}
