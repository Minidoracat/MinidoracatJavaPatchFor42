# 伺服器修補：原理與營運筆記

[English](server.en.md) | **繁體中文** · [回到 README](../README.zh-TW.md)

建置與部署步驟在 [README](../README.zh-TW.md#伺服器建置與部署)。

## 修補原理

42.22 起官方 `projectzomboid.jar` 是簽章 jar，同一個 package 裡混用沒有簽章的 loose `.class` 會丟 `SecurityException`。所以修補整包是一個 **Java agent**（`mdc-javapatch-agent.jar`）：官方 jar 與簽章不動，agent 在官方類別載入時把 bytes 換成建置期手術好的版本。patcher 用 ASM 直接讀 jar 裡的原版類別並就地修改，不反編譯、也不重新編譯原始碼。

- **只做堆疊形狀不變的手術**：改道到堆疊效果與指令長度相同的 static helper、方法內常數替換、線性的頭部／尾部呼叫。Stack map frame 原樣保留（`ClassWriter(0)`）；helper 是對遊戲 jar 正常編譯的 Java 類別。
- **逐方法命中數**：每個手術點都宣告必須命中幾條指令。遊戲更新後手術點移動或改變，建置直接失敗，而不是改錯地方。
- **原版前提檢查**：`SmokeCheck` 釘住每項修正所依賴的結構事實（例如「這個呼叫沒有檢查 null」）。官方修好 bug 時對應的檢查會轉紅，提醒我們退役該修補。
- **驗證**：`LoadCheck` 以 `-Xverify:all` 載入每個類別，`BytecodeVerify` 跑 ASM 的資料流驗證，行為測試在裸 JVM
  中直接跑遊戲的真實類別。
- **開關**：每項伺服器修補都讀一個 `-Dmdc.*` 屬性（`0`／`off` 回原版；很多項另有只量測、不改行為的 `observe` 模式）。
- **失敗即停的安裝與全有或全無的載入**：安裝要過 agent jar SHA、jar 同源、衝突三道閘；每次開服啟動閘與 agent 都再比對一次兩個 SHA，遊戲更新換了 jar 就整包不啟用、以原版開服。要換的 class 已經被載入、或 classpath 上有會蓋掉修補的 loose class 時 agent 也不啟用；helper 定義失敗時把已換上的 class 還原。開服時印 `[mdc-agent] ARMED` 與版本橫幅（`server patch <commit> … jar=<sha>`）。官方 `IntegrityReport` 照常運作：版本字串會多 `agents=1`，jar 仍判原版。

## 建議的伺服器設定

不是修補，但屬於同一條調校線：

| 設定 | 變更 | 原因 |
|---|---|---|
| `PingLimit` | 400 → 800 | 登入與重連風暴時的 ping 踢人從每天 18 次降到 5 次 |
| `SaveWorldEveryMinutes` | 15 → 60 | 高人數時每次全服存檔會讓 client 暫停 5–6 秒 |
| `BackupsPeriod` | 30 → 120 | 內建備份每次壓縮約 25 秒 |

## 選用工具

- **主迴圈健康檢查**（`scripts/pz-health-watch.py`，Linux／LinuxGSM）：透過本機 RCON 的 `players` 確認主迴圈確實回應，連續三次失敗才重啟：先停服，等舊 JVM 真的結束（300 秒還在就 SIGKILL）才開服。測試：`python3 scripts/test_pz_health_watch.py`。
- **Native 快照**（`scripts/native_snapshot.py`）：每次遊戲更新後對伺服器的 native 函式庫做雜湊與符號差異比對；官方會單獨更新這些函式庫，jar 不變不代表它們沒變。

## 專案結構

```text
build.ps1 / build-client.ps1   伺服器建置／客戶端修補包建置
patcher/src/                   ASM patcher、PatchConfig（所有手術點）、SmokeCheck、LoadCheck、BytecodeVerify
patcher/game/                  伺服器 runtime helper
patcher/game-client*/          客戶端修復 helper 與共用客戶端核心
patcher/game-profiler/         DevProfiler runtime
patcher/tests*/                行為測試（伺服器、客戶端、安裝器、profiler）
deploy/                        伺服器 install.sh／uninstall.sh 與閘門測試
deploy-client/                 客戶端安裝器（Manage-Patches.ps1 與 .bat 啟動檔；mac/Install-Patches.command、mac/install-online.sh）
patcher/installer-mac/         macOS 客戶端安裝器（mdc-mac-installer.jar，用遊戲自帶的 Java 執行）
native-observer/               LD_PRELOAD／LD_AUDIT native 防護、啟動 wrapper、測試
scripts/                       上面列出的選用工具
docs/                          詳細修補文件、設計筆記、給 TIS 的回報
lib/                           ASM 9.8（BSD-3-Clause，見 lib/LICENSE-ASM.txt）
```
