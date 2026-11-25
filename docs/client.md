# 客戶端修補包：安裝說明

[English](client.en.md) | **繁體中文** · [回到 README](../README.zh-TW.md)

修補包修了哪些問題：重點在 [README](../README.zh-TW.md#客戶端修補修了什麼)，每一項修補在[修補目錄](catalog.md#客戶端修補發布包)。


給玩家使用。關閉遊戲，打開 PowerShell（按 Windows 鍵，輸入 `PowerShell`，按 Enter），貼上這一行按 Enter：

```powershell
irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1 | iex
```

它會從最新的發布下載 `MinidoracatClientPatches-<遊戲版本>-<包版本>.zip`，用腳本內建的值比對 SHA-256，解壓到暫存資料夾再開安裝器的選單；暫存資料夾用完就刪。選單跟著 Windows 的顯示語言或地區格式（任一是中文就用繁體中文，否則英文），按 `L` 切換中英。安裝或更新：輸入 `1`（安裝或更新）、`1`（客戶端修復），直接按 Enter 選建議的版本（32GB 以上 RAM 選標準版，其餘選省記憶體版；遊戲資料夾裡已有其他 MOD 替換完整版要修補的 class 時建議相容版），問到遊戲記憶體上限時按 Enter 調到建議值，再輸入 `Y` 確認；完成後按 Enter，再輸入 `0` 離開。要換其他版本也是選 `1`；要全部移除或看狀態，貼同一行，在選單選 `3`（全部移除）或 `4`（查看狀態）。

**調整遊戲記憶體上限（0.3.7 起）。** 主選單的 `5` 會顯示目前的上限和這台電腦的建議值。選 `5` 之後輸入 `1` 改成建議值、`2` 自己輸入（MB 整數，至少 3072、不能超過電腦的記憶體，超過一半會提醒）、`3` 恢復遊戲預設的 3072 MB，再輸入 `Y` 確認。它只改 `ProjectZomboid64.json`（macOS 是 `Info.plist`）的 `-Xmx`，有沒有裝修補都能用，下次開遊戲生效。移除修補不會改回；Steam 的「驗證遊戲檔案的完整性」會回到 3072 MB。Windows 裝了 PZ_Optimization 時，它可能在遊戲啟動時自己改掉這個值。

要直接執行某個動作而不開選單，改用 `& ([scriptblock]::Create((irm <上面的網址>))) status` 這種寫法，最後的參數原樣交給安裝器（例如 `status`、`uninstall -All`；只加 `-Lang en` 會用英文開選單）。這行指令執行的是本 repo 發布頁上的 `install-windows.ps1`，用它就是信任這個 repo 和 GitHub；比對 SHA-256 只能擋下載損壞或版本不同步，擋不了 repo 被盜用。想先看內容可以只執行 `irm <那個網址>`。要裝舊版，把網址裡的 `releases/latest/download` 換成 `releases/download/client-v<包版本>`（0.3.5 起才有這個檔；0.3.5 不帶參數時會直接安裝，不開選單）。

不想貼指令：從 [Releases](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases) 下載 `MinidoracatClientPatches-<遊戲版本>-<包版本>.zip`，並與 `SHA256SUMS.txt` 比對（`Get-FileHash .\MinidoracatClientPatches-*.zip`）。

1. 關閉遊戲，把整個 zip 解壓縮到任何資料夾（桌面、下載都可以，不必放進遊戲目錄）。安裝程式會自己透過 Steam
   找到遊戲目錄，遊戲裝在別的硬碟也找得到；找不到時才會請你貼上路徑（也可以用 `-GameDir "<路徑>"` 指定）。
2. 執行 `Install-Patches.bat`，輸入 `1`（安裝或更新），再輸入 `1`（客戶端修復）。
3. 直接按 Enter 選建議的版本。問到遊戲記憶體上限時直接按 Enter 調到建議值，再輸入 `Y` 確認。

安裝器跟著 Windows 的顯示語言或地區格式（任一是中文就用繁體中文，否則英文），主選單按 `L` 或加 `-Lang en` 可切換。它只接受對應的遊戲版本，會比對 `projectzomboid.jar` 與包內檔案的 SHA-256。0.3.0 起修補以 Java agent 載入（42.22 起官方 jar 加了簽章，以前把 `.class` 檔放進遊戲資料夾的做法會讓遊戲起不來）：安裝器把 `MinidoracatClientPatches.jar` 和一個小小的載入器 `mdc-agent-loader.dll`（遊戲的啟動器要靠它才載入得了 Java agent，原始碼在 `patcher/agent-loader/`）放進遊戲資料夾的 `.mdc-patches`，並在 `ProjectZomboid64.json` 加一條 `-agentpath:` 啟動參數，遊戲本身的檔案不動；卸載時只拿掉那一條，其他設定（例如自己改過的 `-Xmx`）原樣保留，安裝時同意調高的記憶體上限也一樣保留（要回到 3072 MB 用 Steam 驗證遊戲檔案的完整性），中斷的安裝也能復原。**遊戲更新前不必先移除**：jar 換了，修補會自動停用、照原版執行。Steam 更新常把 `ProjectZomboid64.json` 換回原版，這時修補沒有啟用（安裝器查看狀態會顯示「已安裝但沒有啟用」），等對應新版的包發布後再貼一次一行指令（或重新執行 `Install-Patches.bat`）即可。不要手動刪 `.mdc-patches` 資料夾：啟動參數還在而檔案不在時，遊戲起不來；要移除請再貼一次一行指令選 `3`，或執行 `Uninstall-Patches.bat`（選 `A`）。移除只看遊戲資料夾裡的安裝紀錄，用比較新的安裝器也能移除舊版，所以用 zip 安裝的也不必留著解壓縮的資料夾（刪掉了就貼一行指令）。從 `ProjectZomboid64.bat` 或 `ShowConsole.bat` 啟動不會讀 `ProjectZomboid64.json`，修補不會載入。從 0.2.x 升級：關閉遊戲，直接用一行指令或新版 `Install-Patches.bat` 安裝，舊版放進遊戲資料夾的 `.class` 檔會在同一次安裝裡移除；只附 `uninstall.bat` 的舊版 TexPipeline v3.0 也認得。

| 模組 | 用途 |
|---|---|
| `core` | 共用 Lua bridge、安裝指紋驗證與啟動狀態；自動安裝 |
| `client-fixes-standard` | 貼圖洩漏修復、4 GiB 貼圖等待門檻、chunk 串流紀錄、自建房斷線修正（兩項）、車輛動畫加速、開車瞬移修正（三項）、車被推到地板下修正、地上血跡加速、沒有模型的地上物品修正、沒熄火下車走遠後車門打不開修正（32GB 以上 RAM） |
| `client-fixes-lowmem` | 同一組修正，保留原版 50 MiB 貼圖門檻；與其他版本互斥 |
| `client-fixes-compat` | 只含車輛修正（車輛動畫加速、開車瞬移三項、車被推到地板下、沒熄火下車走遠後車門打不開），給 PZ_Optimization 這類替換其他 class 的 MOD 一起用；與其他版本互斥 |
| `profiler` | 給模組開發者的 DevProfiler（Java → Lua 呼叫計時、CSV／JFR 匯出）；介面是另一個 mod `MinidoracatDevProfilerFor42` |

**和替換遊戲 class 的 MOD 一起用。** 有些 MOD 會替換遊戲的 class：把 `.class` 檔放進遊戲資料夾，或像 PZ_Optimization 在 42.22 起改用 Java agent（啟動參數 `-javaagent:pzopt/agent/pzopt-agent.jar`，換掉 `pzopt/overrides/` 底下的 class）。它替換了本包要修補的 class 時，本包對那個 class 的修補不會生效，安裝器會以 code 7 停止、不改任何檔案。不要只刪除它列出的檔案：那個 MOD 的其他檔案還需要它們，刪掉後遊戲會在進服時直接關閉（[issue #1](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/issues/1)）。二選一：保留那個 MOD、改裝 `client-fixes-compat`（安裝器會建議），或用那個 MOD 自己的卸載器把它移除（PZ_Optimization：遊戲資料夾裡的 `Uninstall-PZ-Optimization.cmd`）後裝完整版。相容版和 PZ_Optimization 的 agent 可以一起用，兩邊的安裝器都把自己排在最前面，誰排前面都可以（0.3.8 起；用 PZ_Optimization revision 3d39148846 在 Java 載入層實測兩種順序，本包排前面的順序另在單機實機跑過）。0.3.7 以前的本包排在它前面時，會讓它整個以原版執行（它的 `agent.log` 寫 `already loaded … zombie.ai.State … the game runs stock`），請更新到 0.3.8。先裝完整版、之後才裝 PZ_Optimization 時，本包會整包不啟用、照原版跑，安裝器的狀態查詢會提醒改裝相容版。PZ_Optimization 的「Warm-start cache (Java AOT)」選項開著時，遊戲從它的快取載入 class，安裝器會拒絕安裝；先關掉它（下次啟動才生效，所以關掉後要再開一次遊戲到主選單、再關閉）。另外，42.22 起和官方 class 同 package 的 `.class` 檔本身就會讓 Java 丟 `SecurityException`（安裝器看到會提醒），為 42.21 做的這類 MOD 要等作者更新。

**Windows 智慧型應用程式控制。** 修補靠一個沒有程式碼簽章的小 DLL（`mdc-agent-loader.dll`）載入。Windows 11 的智慧型應用程式控制在強制模式的電腦上，Windows 會擋下它，遊戲完全開不起來（在一台這樣的電腦上用 0.3.1 實測）。0.3.2 起安裝器會先檢查，遇到就以代碼 11 停止、不變更任何檔案；評估模式照常安裝並警告。安裝後它才轉成強制模式、遊戲開不起來時，再貼一次一行指令選 `3`，或執行 `Uninstall-Patches.bat` 全部移除。我們不會要你去改那個 Windows 設定。

**macOS。** 結束遊戲，打開「終端機」（應用程式 › 工具程式），貼上這一行按 Return：

```sh
/bin/bash -c "$(curl -fsSL https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-mac.sh)"
```

它會從最新的發布下載 `MinidoracatClientPatches-<遊戲版本>-<包版本>-macOS.zip`，用腳本內建的值比對 SHA-256，再開安裝器的選單（和 Windows 版相同）。選單跟著 macOS 的偏好語言（第一個是中文就用繁體中文，否則英文），按 `L` 切換中英。安裝或更新：輸入 `1`、`1`，直接按 Return 選建議的版本，問到遊戲記憶體上限時按 Return 調高，再輸入 `Y` 確認；完成後按 Return，再輸入 `0` 離開。全部移除也是貼同一行，選 `3`。同一行最後加一個空格和 `status` 或 `uninstall --all` 會直接執行那個動作、不開選單；加 `--lang en` 會用英文開選單。只看到一行 `curl:` 開頭的英文就是沒下載成功，再貼一次即可。這行指令執行的是本 repo 發布頁上的 `install-mac.sh`，用它就是信任這個 repo 和 GitHub；比對 SHA-256 只能擋下載損壞或版本不同步，擋不了 repo 被盜用。想先看內容可以執行 `curl -fsSL <那個網址> | less`。要裝舊版，把網址裡的 `releases/latest/download/install-mac.sh` 換成 `releases/download/client-v<包版本>/install-mac.sh`（0.3.5 以前不帶參數時會直接安裝，不開選單）。

主選單的 `5` 可以調整遊戲記憶體上限，用法和 Windows 版相同（見上方）。

不想貼指令：下載 `-macOS.zip`（可用 `shasum -a 256` 比對），雙擊解壓縮，打開「終端機」，輸入 `bash `（後面有一個空格），把解壓出來的 `Install-Patches.command` 拖進視窗再按 Return；之後的選單和 Windows 版相同。直接雙擊 `Install-Patches.command` 不行，macOS 會擋下沒有經過公證的腳本。

安裝器用遊戲自帶的 Java 執行，會透過 Steam 找到 `Project Zomboid.app`（找不到時請你拖進視窗，也可以用 `PZ_APP` 指定），把同一個 `MinidoracatClientPatches.jar` 放進 `Project Zomboid.app/Contents/Java/.mdc-patches`，並在 `Project Zomboid.app/Contents/Info.plist` 的 `JVMOptions` 加一條 `-javaagent:` 啟動參數。Mac 的啟動器可以直接載入 Java agent，所以不需要載入器 DLL。遊戲預設最多只用 3 GB Java 記憶體（`-Xmx3072m`），MOD 多的伺服器不夠用，連線幾分鐘後會以 `OutOfMemoryError` 閃退；上限低於這台 Mac 的建議值時（記憶體 32 GB 以上建議 8 GB、16 GB 以上 6 GB、12 GB 以上 4 GB；只有 8 GB 在遊戲裡實測過），安裝器會問要不要調高。除了記憶體上限，`Info.plist` 只會多那一行啟動參數，全部移除（選單 `3`）後逐位元組回到原本的檔案；調高過的記憶體上限是遊戲本身的設定，移除時不會改回。Steam 更新或「驗證遊戲檔案的完整性」可能把 `Info.plist` 換回原版（記憶體也回到 3 GB），這時狀態會顯示「已安裝但沒有啟用」，再安裝一次就會恢復，也會再問一次記憶體。在 Apple 晶片的 Mac 上測試過（原生，以及用 Rosetta 2 跑 Intel（x86_64）版）；還沒有在 Intel Mac 上實測。

也可以用 `build-client.ps1` 自行建置（另外需要 MinGW-w64 gcc，用來編譯小小的 agent 載入器 DLL；以及 Git for Windows 的 bash，用來測 macOS 的啟動腳本）。安裝器測試：`powershell -NoProfile -ExecutionPolicy Bypass -File patcher/tests-client-installer/Run-InstallerTests.ps1`（Project Zomboid 的 JVM 執行中不要跑）。
