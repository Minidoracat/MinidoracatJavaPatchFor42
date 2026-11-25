# MinidoracatJavaPatchFor42

[English](README.md) | **繁體中文**

**Project Zomboid Build 42**（目前對應 **42.22.2**）的非官方 bytecode 修補，修正我們經營高人數多人伺服器時找到的原版 bug 與效能熱點。分成兩部分：

- **客戶端修補包**（給玩家，Windows 與 macOS）：修隊友和殭屍隱形、幾種斷線和開車瞬移。[修了什麼](#客戶端修補修了什麼)・[安裝](#客戶端修補安裝)
- **伺服器修補**（給 dedicated server 服主）：讓主迴圈不再凍結，chunk、動物、車輛不再遺失，並減少主執行緒耗時與頻寬。[修了什麼](#伺服器修補修了什麼)・[建置與部署](#伺服器建置與部署)

兩者都以 Java agent 載入，官方 `projectzomboid.jar` 不會被修改。遊戲更新後修補會自動停用，等裝上對應新版遊戲的版本才再啟用。每一項修補都有完整的根因分析，見[文件](#文件)。

> **給 The Indie Stone 開發團隊**
>
> 這裡的每一項都是在正式服上用 thread dump、JFR、心跳計數器與封包擷取定位出來的，每個修正都逐指令對照過官方 `projectzomboid.jar`。其中幾項已在 42.20.2–42.22.2 由官方修掉（見[官方已修](docs/catalog.md#官方已修)），謝謝。[修補目錄](docs/catalog.md)的 **TIS** 欄標出哪些已在論壇回報、哪些還沒回報。任何一項都可以提供 log、封包統計或測試版本：請在這裡開 issue，或在論壇聯絡 **Minidoracat**。

## 客戶端修補：修了什麼

- **隊友、殭屍、車輛隱形**：玩一陣子後只剩影子和名牌，要重開遊戲才會恢復。修補包修掉背後的貼圖緩衝洩漏；標準版另外放寬貼圖門檻。（[2j](docs/patches.md#2j)）
- **在自建建築附近斷線**：chunk 載入時附近有人蓋或拆東西，兩個不同的 bug 會把你斷線送回主選單。（[W57](docs/patches.md#2bu)、[W68](docs/patches.md#2cd)）
- **開車瞬移**：開進還沒載入的區域時，車可能被丟到幾十到幾百格外，或駕駛被拉回舊位置，有時掉進水裡。（[W61](docs/patches.md#2by)、[W62–W63](docs/patches.md#2bz)）
- **車被推到地板下**：碰撞後車陷到地板下，畫面全黑。（[W65](docs/patches.md#2ca)）
- **畫面變卡**：門窗會動的模組車（KI5 等）停著也每幀重算動畫；屍潮後地上的血跡逐筆掃描。（[W54](docs/patches.md#2br)、[W55](docs/patches.md#2bs)、[W84](docs/patches.md#2cs)）
- **沒有模型的物品一直跳錯誤**：這種物品放在地上或拿在手上都會。（[W100](docs/patches.md#2dh)、[W101](docs/patches.md#2di)）
- **車門打不開**（單機）：引擎沒熄火就下車走遠，回來後車門和引擎蓋錯位、打不開。（[W108](docs/patches.md#2dp)）

有三個版本，安裝器會建議用哪個：**標準版**（32 GB 以上 RAM）、**省記憶體版**（同一組修正，保留原版貼圖門檻）、**相容版**（只含車輛修正，給 PZ_Optimization 這類替換遊戲 class 的 MOD 一起用）。每一項客戶端修補都列在[修補目錄](docs/catalog.md#客戶端修補發布包)。

## 客戶端修補：安裝

**Windows**：關閉遊戲，打開 PowerShell（按 Windows 鍵，輸入 `PowerShell`，按 Enter），貼上這一行按 Enter：

```powershell
irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1 | iex
```

**macOS**：結束遊戲，打開「終端機」（應用程式 › 工具程式），貼上這一行按 Return：

```sh
/bin/bash -c "$(curl -fsSL https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-mac.sh)"
```

在選單輸入 `1`（安裝或更新）、`1`（客戶端修復），直接按 Enter 選建議的版本，問到遊戲記憶體上限時按 Enter 調到建議值，再輸入 `Y` 確認。要全部移除，貼同一行，選 `3`。

- 遊戲更新前不必先移除：遊戲一更新，修補就自動停用、照原版執行。遊戲更新或 Steam 驗證檔案讓修補停用時，再貼一次這一行（遊戲換了新版本時，要等對應的新包發布）。
- 有裝 PZ_Optimization 或其他替換遊戲 class 的 MOD 時，裝相容版（安裝器會建議）。

不貼指令的下載方式、遊戲記憶體上限、和替換遊戲 class 的 MOD 一起用、Windows 智慧型應用程式控制、macOS 細節與自行建置，見[完整安裝說明](docs/client.md)。

## 伺服器修補：修了什麼

- **凍結與活鎖**：以前會讓主迴圈停幾分鐘到幾小時的錯誤，現在會被攔下或避開：chunk 載入時的「Entity is already registered」、動物聲音排序、建構到一半的動物、Bandit Raid 劇情的無限迴圈，以及背景執行緒寫 engine 與 cell 清單。（[穩定性](docs/catalog.md#穩定性凍結活鎖與崩潰)）
- **資料遺失**：寫入閘在損毀的 chunk 存檔蓋掉玩家建築之前擋下。動物離線補算不再重複、漏算或被截短（以前會讓牲畜無故死亡、長得太慢、回來像沒吃沒喝）。另外修了吊掛的屍體變回活動物、拿不起來的幽靈物品、移不掉的殘留物件，以及 `Map=` 列了不存在的資料夾時整張地圖的建築消失。（[資料完整性](docs/catalog.md#資料完整性chunk動物與車輛遺失)）
- **主執行緒耗時**：動物視線與聽覺、物品登記、玩家視線、停著的車、車輛聲音、尋路重建都大幅變便宜（例：動物聽覺每幀 4.34 → 0.69 ms）。多數項目的結果與原版相同；風險較高的預設是只量測的 observe 模式。（[效能](docs/catalog.md#效能主執行緒耗時)）
- **頻寬**：動物完整快照重送（佔出向流量 38–40%）、製作站廣播（17.5%）、`VehicleCollide` 迴圈（單一 client 每秒 239 包）。（[網路](docs/catalog.md#網路頻寬與重送迴圈)）
- **多人同步 bug**：讀條卡在 100%、殭屍隱形、隊友不在地圖上、車卡在地板下、動物區域消失或重複、PVE 死亡被公告成擊殺。（[多人同步正確性](docs/catalog.md#多人同步正確性)）
- **帳號與濫用**：伺服器端多驗證幾種 client 請求（建造、物件圖、燈光設定、物品資料、衣物），Steam 模式也擋 IP 封鎖，每個 Steam ID 的帳號數上限，並記下步行瞬移和不在駕駛座的人移動車輛。（[帳號與濫用](docs/catalog.md#帳號與濫用)）
- **log 噪音**：已知的洗版訊息不再印出，反作弊與未知警告照常輸出。（[log 噪音](docs/catalog.md#log-噪音)）
- **Linux native 崩潰**：尋路函式庫的崩潰與記憶體洩漏、Steam 網路函式庫的越界讀取。（[Native 防護](docs/catalog.md#native-防護linux-dedicated-server)）

每項伺服器修補都有 `-Dmdc.*` 開關可回原版。每一項修補與它的 TIS 回報狀態都在[修補目錄](docs/catalog.md)。

### 我們伺服器上的成效

我們的伺服器每日尖峰 68–95 人同時在線（254 個名額）。dedicated server 的主迴圈是單執行緒、上限 10 FPS，每一毫秒都很重要。部分成效：

| 項目 | 修補前 | 修補後 |
|---|---|---|
| console 噪音 | 單是 `Send Toxic Building` 就佔全部 console 行數的 45.5% | 歸零；其他已知噪音也過濾，反作弊警告照常輸出 |
| chunk 損毀（Blam 抹除） | 43 個玩家建造的 chunk 在 `SANITY CHECK FAIL` 後被抹除 | 上線第一晚擋下 8 筆損毀寫入、零損失；根因修正後 290 萬次寫入零攔截 |
| 主迴圈凍結 | 13 分鐘與 114 分鐘凍結、全服活鎖、5–16 秒停頓 | 守衛上線後未再發生 |
| 動物聽覺掃描 | 每幀 4.34 ms（主執行緒 4.3%） | 0.69 ms（0.6%） |
| 動物視線 | 約 50 人時每幀約 16 ms（主迴圈約 9%） | 空間預篩、結果與原版相同（基準每幀 4.8 ms → 0.25 ms） |
| `processItems` 登記 | 約 3 萬件的清單，每次登記都線性掃描（約佔主執行緒 6%） | 身分索引（基準 43 µs → 0.3 µs） |
| 動物完整快照 | 佔出向流量 38–40%，其中 87% 是重送 | 半徑對齊 client 已載入範圍、每隻動物冷卻 |
| `VehicleCollide` 迴圈 | 單一 client 每秒 239 包，2 小時 18 萬行警告 | 每秒 9.8 包 |
| 製作站廣播 | `GameEntity` 封包佔出向 UDP 17.5% | 只送附近玩家，且只在畫面狀態改變時送 |

約 63 人在線時，主迴圈維持 9.4–10.1 FPS，所有修補計數器異常數皆為 0。

## 伺服器：建置與部署

需求：JDK 25（42.22 的 jar 是 class file 版本 69）與伺服器的 `projectzomboid.jar`。

```powershell
copy <serverfiles>\java\projectzomboid.jar work\
.\build.ps1        # 手術 → 命中數 → 連結、bytecode、行為檢查 → agent jar → 簽章 jar 下的載入檢查 → dist/
bash patcher/tests/install-roundtrip.sh   # 在暫存 serverfiles 上跑兩輪安裝／移除（WSL 或 Git Bash）
```

```bash
# 把 dist/ 的 mdc-javapatch-agent.jar、agent-manifest.txt、install.sh、uninstall.sh 複製到伺服器同一個資料夾：
bash install.sh    # agent SHA、jar 同源、衝突三道閘；裝到 serverfiles/mdc-javapatch/，下次開服生效
bash uninstall.sh  # 整個目錄搬到 patch-disabled-*；下次開服回到原版
```

開服要經 `native-observer/deploy/run-with-pfguard.sh`：它驗過 agent jar 與遊戲 jar 的 SHA 才把 `-javaagent` 加進 `JAVA_TOOL_OPTIONS`，不符就以原版開服；JVM 設定檔不用改。不用這個啟動閘時，自己在 JVM 參數加 `-javaagent:<serverfiles>/mdc-javapatch/mdc-javapatch-agent.jar`（agent 自己也會比對 jar SHA，不符就不啟用）。

**遊戲更新後**：agent 比對到 jar 換了就不啟用，伺服器照原版開，不會有「新 jar＋舊修補」。對新 jar 重新建置，並用 `javap` 確認每個手術點的語境（命中數對不代表意思沒變），再重新安裝。仍建議得知新版就先 `uninstall.sh`。建置檢查與部署後驗證清單見 [docs/patches.md §3](docs/patches.md#3)。

修補原理、建議的伺服器設定、選用工具與專案結構，見[伺服器說明](docs/server.md)。

## 文件

| 文件 | 內容 |
|---|---|
| [修補目錄](docs/catalog.md) | 依類別列出每一項修補：原版哪裡出錯、修補做什麼、TIS 回報狀態；已查出但沒有修補的原版問題；因官方修好而退役的修補 |
| [客戶端安裝說明](docs/client.md) | 每一種安裝方式、遊戲記憶體上限、和替換遊戲 class 的 MOD 一起用、智慧型應用程式控制、macOS 細節、自行建置 |
| [伺服器說明](docs/server.md) | 修補原理、建議的伺服器設定、選用工具、專案結構 |
| [docs/patches.md](docs/patches.md) | 每一項修補的技術細節：事故、附 bytecode 證據的根因、修正、安全論證、檢查、開關、實測結果 |
| [docs/patches.en.md](docs/patches.en.md) | 上面文件的英文版 |
| [docs/report/](docs/report/) | 為 TIS 論壇撰寫的 bug 回報（英文） |
| [docs/optimization-summary.md](docs/optimization-summary.md) | 營運總覽與時間線 |
| `docs/*-design-*.md` | 較大修補的設計筆記 |
| [docs/specs/](docs/specs/) | 早期修補的 `javap` 證據（JSON） |

## 支持作者

MOD 永遠免費。喜歡的話可以請我喝杯咖啡，贊助會用在伺服器與 MOD 開發上。

[![Ko-fi](https://raw.githubusercontent.com/Minidoracat/workshop-resources/refs/heads/main/badges/badge_kofi.png)](https://ko-fi.com/minidoracat)

## 授權與免責

本專案採 MIT 授權，見 [LICENSE](LICENSE)。

第三方元件：建置使用 OW2 ASM 9.8（BSD-3-Clause），隨 `lib/` 散布，授權聲明見 [lib/LICENSE-ASM.txt](lib/LICENSE-ASM.txt)。任何發布包都不含 ASM。

- **非官方專案**，與 The Indie Stone 無任何隸屬、合作或背書關係。Project Zomboid 與其相關素材之著作權均屬 The Indie Stone 所有。
- repo 本身不含任何遊戲檔案。**客戶端發布包含有 `projectzomboid.jar` 中少數類別的修改版**，由本 repo 的 patcher 從指定版本的官方原版 jar 產生；只能搭配合法持有的同一版本遊戲使用，安裝器也會拒絕其他版本。若 The Indie Stone 要求停止散布，我們會照辦。
- **修改遊戲檔案風險自負。** 伺服器用 `bash uninstall.sh`、客戶端用安裝器的「全部移除」移除修補；安裝前請先備份伺服器。
- **每次 Project Zomboid 更新後都必須重新建置與驗證**，切勿把舊修補套到新版遊戲上。
