# MinidoracatJavaPatchFor42

PZ B42 專用伺服器 loose-class 優化 patch——自製、可重生、帶同源防護。

> **2026-09-02 歷史改寫**：為去識別化（玩家暱稱改代號、移除主機／本機路徑與他人分享文件），
> 全部 commit 已用 `git filter-repo` 重寫。此日期前的 commit sha——包括文件裡引用的
> 與正式服 log 橫幅 `server patch <sha>` 印出的——在本 repo 已不存在，屬歷史事實原樣保留。

## 原理

PZ 伺服器 classpath 為 `java/.` 優先於 `java/projectzomboid.jar`：同路徑的 loose `.class`
會覆蓋 jar 內的原版。本專案**不重編譯反編譯原始碼**，而是用 ASM 直接對 jar 內的原版
bytecode 做「堆疊形狀不變」的呼叫改道／方法內常數替換，另有兩個窄範圍 null 頭部守衛，
StackMapFrames 原樣保留；改道 helper 寫成普通 Java 類並由 javac 對遊戲 jar 編譯，隨 patch 出貨。

## 內容

> **42.21.0（2026-09-28）**：自動更新換 jar 後，殘留的舊 loose class 讓伺服器起不來
> （`Failed to find class: zombie/network/GameServer`），先整批移走、以原版恢復，再對 42.21 逐項重驗。
> 官方已修而退役：抑噪 AnimationSet 與 IsoThumpable not found、砸窗無限迴圈保險絲、W22、W24、W27、W28、
> W10-A、W10-E 與 W9 的兩個 CRC 刀；早已停用的安全屋修復殘留程式碼一併刪除。依 42.21 改寫：W10 的 Lua 建構子
> 保險絲與參數解析拒絕改為獨立運作、W10-C 只留「被打斷時補送 Reject」、動作封包改為只檢查發送者身分、
> W37 補撤格子殘留、W29／W31 指紋不再受行號影響。客戶端包重建為 `0.2.0`。規模：57 patched＋58 helper＝115 class、147 命中點。
>
> **42.20.2 里程碑**：官方在此版收編了我方三組 patch——P5 IsoCell sidecar（官方伴生 Set）、
> popman buffer 隔離（官方 readByteBuffer）、VehicleManager 512→256（官方改 per-connection
> HashMap）。三組已光榮退役，詳見 docs/optimization-summary.md 第四節。
>
> **42.20.3（2026-08-17）**：TIS 重構 chunk 供給管線（pending＋ChunkNotReady、重試機制刪除）；
> 29 刀逐指令重驗全數存續、僅 SmokeCheck retriesCount 斷言退場。client 包 **v3.0 重建**
> （W4-2 撤刀＝目標方法被 vanilla 刪除；觀測擴充第 4 headCall 計數 ChunkNotReady，獨立基準
> 分型生成瓶頸/全斷流；**v3.0-lowmem** 供 ≤8GB RAM 機器——不做 4GB constChange）。
> 見 docs/report/pz-42.20.3-update-analysis.md。
>
> **2026-08-08**：受精蛋清除豁免（`IsoGridSquare`）退役——server 端運作正常，但 client 沒有
> 對應改道且清單由 server 完整同步，玩家看不到也撿不起被豁免的蛋。改回原版行為（蛋照清），
> 受精蛋請養在雞舍孵化。

- **抑噪 8 項**：SkinningBoneHierarchy／SpriteConfig（exact 白名單 37 名）／ItemPickInfo／
  NetworkZombieManager／PacketsCache／INetworkPacket.logInconsistentPacket／GameServer.sendToxicBuilding／
  IsoChunk.removeFromWorld（車輛卸載正常路徑）——只攔已知噪音樣式，未知警告與反作弊警告照常輸出。
  AnimationSet 與 IsoObject.syncIsoObject 兩項於 42.21.0 由官方從源頭修掉，已退役。
- **防崩潰守衛 2 項**：hit/Zombie（guard-before-super）與 hit/Fall（縱深防禦）的 null 頭部守衛。
- **行為 1 項**：IsoAnimal（動物壓力三調：閒置衰減×2、聲音壓力÷3、屠宰連鎖上限減半，
  clamp 與行為路徑不動）。
- **容器刷新修復 1 項**：LootRespawn 對自訂地圖缺少 vanilla TownZone 與黏性 construction flag
  加入窄範圍 fallback；只放行未搬動的原生固定容器，玩家製／搬動容器仍不刷新，安全屋仍由原版動態阻擋。
- **chunk unload entity removal 1 項**：只改道 `EngineEntityManager` 與 `EntityBucket` 的四個
  unordered identity add/remove callsite，以 weak-key＋primitive sidecar index 把批次卸載的重複
  O(N) 搜尋改成常態 O(1)；碰撞、外部 mutation、ordered/equality/null 路徑都保留原版 fallback。
  （42.20.2 覆核：`EngineEntityManager`/`EntityBucket` 位元組未變，patch 續用。）
- **聲音封包慢呼叫觀測**：記錄單包與同批累積耗時，並在既有凍結快照附上處理中的半徑與音量。
  不改聲音、聽覺或魚群規則；`-Dmdc.worldSoundProbe=0`／`off` 可停用，需重啟。
  範圍、限流與判讀方式見 [聲音觀測](docs/patches.md#2at-聲音封包慢呼叫觀測server預設-observe)。

- **效能第三波 W3 三刀**（docs/wave3-design-v1.md v2；三稜鏡對抗審查＋獨立 code review 雙關）：
  (1) 殭屍 ownership 重選舉錯峰——`NetworkZombiePacker.updateAuth` 改道 tick 計數器節流，
  owner 穩定殭屍由每 tick 全額 O(連線×玩家) 掃描降為每 3 pass 一次，isDead／孤兒／
  SwitchZombiesOwnershipEachUpdate 即刻放行；(2) 動物 spotted 距離預過濾——`IsoAnimal.updateLOS`
  兩處改道，遠距（>max(12, spottingDist+2)）呼叫逐句重放無條件前綴後跳過，全 jar walk 斷言
  behavior 子類零覆寫＋前綴指紋＋51 值有序常數包絡鎖 42.21 漂移；(3) 車輛 couldSee server
  死工消除——`BaseVehicle.update` 內掃描結果唯一去處是 server 端 vanilla no-op，直接短路，
  render() 負對照＋targetAlpha guard 指紋雙鎖。另 W3-2（ECS memo）經 microbenchmark 實測
  為淨劣化而撤刀，記錄於設計文件。

- **雞舍同步 W26**：僅過濾雞舍 `update()` 內兩個自發同步點的遠端收件人；玩家操作、地圖初載、
  蛋與孵化資料保持原版。登入、載具、傳送及不確定狀態保守放行；可用
  `-Dmdc.hutchSyncGate=0` 回到原版廣播。詳見 [W26](docs/patches.md#2an-雞舍自發同步收件人過濾w26server預設-enforce)。

- **GameEntity 廣播 W36**：自動工作站（如曬草架）製作中每秒全服廣播整份狀態，改為沿用 W26 判定只送附近
  連線，且進度整數百分比、製作清單或濕度顯示沒變就不送；位置不可信的物品實體維持全服。封包格式不變，
  玩家端進度改為 1% 一跳。`-Dmdc.gameEntityRelevancy=0`／`-Dmdc.craftLogicSyncGate=0` 分別回原版。
  詳見 [W36](docs/patches.md#2ay-gameentity-廣播收件範圍craftlogic-同步變化閘w36server預設-enforce)。

- **動物半建構物件 W37**：原版動物建構檢查失敗時，會把 adef/data 為 null 的物件留在世界，每 tick
  打斷世界更新（時間凍結）並讓動物存檔失敗；存檔又先開檔才序列化，把 apop 檔截成 0 bytes。改為撤出失敗
  物件、只吞該次建構失敗造成的 NPE，並在序列化成功後才寫檔。`-Dmdc.animalSpawnGuard=0`／
  `-Dmdc.animalCellSave=0` 分別回原版。詳見 [W37](docs/patches.md#2az-動物半建構物件守衛apop-先序列化再開檔w37server預設-on)。

- **畜牧區補算 W38／動物死亡帳本 W39**：原版離線補算迴圈會因動物在清單中被移到尾端而重複補算或漏算
  （重複補算＝飢渴與年齡加倍，異常死亡主因），改為本次補算固定讀一份快照。另在動物死亡時記一行狀態與來源
  （純觀測）。`-Dmdc.animalMetaSnapshot=0`／`-Dmdc.animalDeathLedger=0` 分別回原版。
  詳見 [W38](docs/patches.md#2ba-畜牧區離線補算快照w38server預設-on)、[W39](docs/patches.md#2bb-動物死亡帳本w39server純觀測)。

- **物品處理清單 W40／W41**：原版每 5 秒處理一次的物品清單混進 null 時會每次 NPE、清單不再縮減，chunk 載入的線性
  搜尋讓伺服器凍結 5–16 秒。W40 略過並移除 null；W41 修掉來源——背景執行緒載入車輛時直接寫這份清單，
  改為排入佇列由主執行緒補登記。`-Dmdc.processItemsGuard=0`／`-Dmdc.processItemsDefer=0` 分別回原版。
  詳見 [W40](docs/patches.md#2bc-物品處理清單-null-容錯跨執行緒寫入觀測w40server預設-on)、
  [W41](docs/patches.md#2bd-非主執行緒物品登記改道主執行緒w41server預設-on)。

- **物品處理清單身分索引 W45**：原版每登記一件物品都線性搜尋整份清單。本服會腐壞的食物常駐清單（約 3 萬件），
  每次約 30–37 µs，chunk 載入時對容器每件物品各搜一次，約佔主執行緒 6%。改為清單建立時換成自帶身分索引的
  版本，搜尋 O(1)，順序與內容不變；舊的 W30（批次登記加速）同批退役。`-Dmdc.processItemsIndex=0` 回原版。
  詳見 [W45](docs/patches.md#2bh-物品處理清單身分索引w45server預設-onw30-同批退役)。

- **VehicleCollide 歸還後重送授權 W46**：玩家撞到停放的車時，client 先自認取得那台車的碰撞計算權；伺服器同一幀內
  處理完申請與歸還（例如卡頓時），淨結果不變就不會通知 client，client 每幀送歸還直到那台車離開載入範圍
  （單一 client 最多 237 包/秒，伺服器卡頓後一次處理即超過每秒 1000 包上限、洗出大量 log）。伺服器收到歸還時
  把該連線對這台車的授權快取標為失效，下一輪同步必定送回真正的授權。`-Dmdc.vehicleCollideResync=0` 回原版。
  詳見 [W46](docs/patches.md#2bi-vehiclecollide-歸還後強制重送授權w46server預設-on)。

- **動物視線空間預篩 W47**：W18-2 之後每次動物視線檢查仍走完整份物件清單（正式服約 4,300 個）只為找 12 格內的殭屍與玩家。
  改為每幀建一次快照、殭屍依位置分網格，每隻動物只處理附近的殭屍與全部玩家，結果與原版逐位元相同（4,000 個隨機世界差分）；
  前提是殭屍在一幀內移動不超過 16 格，另有兩道監看，一旦違反即自動改回完整掃描。一次性基準每幀 4,780 µs → 約 250 µs。
  `-Dmdc.animalLosIndex=0` 回原版。詳見 [W47](docs/patches.md#2bj-動物視線空間預篩w47server預設-on)。

- **動物聽覺量測 W48＋空間索引 W48-2**：伺服器每隻動物每個 tick 整份掃過全世界的聲音清單（9/28 開服後約 4,300 個，
  平均只有 12 個在範圍內），找會影響動物的最大聲音；兩次心跳間佔主執行緒 4.8%。W48 量測，W48-2 改成依位置只算附近的聲音，
  回傳值與原版相同（每 256 次比對一次原版，不一致即改回）。正式服同規模窗口每幀 4.3 ms → 0.7 ms（每次呼叫 7.8 → 1.2 µs）。
  `-Dmdc.animalSoundIndex=0` 回原版、`-Dmdc.animalSoundProbe=0` 停量測。詳見 [W48](docs/patches.md#2bk-動物聽覺量測w48與空間索引w48-2server預設開)。

- **動物補算時數上限 W42**：原版以圈區上次離開串流的時間推算離線時數，常比動物實際離線多算數天（9/26 一個 session
  38 隻死亡中 25 隻發生在補算後 60 秒內）。改為不超過動物自身離線時間，並在動物活著時每小時更新自身時鐘。`-Dmdc.animalCatchUpCap=0` 回原版。
  詳見 [W42](docs/patches.md#2be-動物補算時數上限w42server預設-on)。

> 本節僅列部分項目；完整清單見 docs/patches.md，啟用項目與逐方法命中數以 `PatchConfig.all()` 為準。

逐項 javap 證據與安全論證：[docs/patches.md](docs/patches.md)；分析原始規格：[docs/specs/](docs/specs/)。

## 建置

```powershell
# 需求：scoop temurin25-jdk（42.19 jar 已是 class file v69）、lib/asm-9.8.jar
scp <your-server>:/home/pzserver/serverfiles/java/projectzomboid.jar work\
.\build.ps1   # 編譯 → 手術 → 命中數 → 連結／bytecode／行為／尺度 benchmark → dist/
wsl bash patcher/tests/install-roundtrip.sh  # 隔離 temp serverfiles 上跑兩輪 install/uninstall
```

**命中數守門**：每處手術帶 expectedCount，PZ 更新後 build 漂移＝建置直接失敗（重新分析而非默默出錯）。

## 部署

```bash
# dist/ 整包丟到伺服器後：
bash install.sh     # 內建同源閘——逐 class 驗 jar hash，遊戲更新過就拒裝
# 下次伺服器重啟生效；移除：bash uninstall.sh
```

## PZ 更新 SOP

0. **更新前先在伺服器 `bash uninstall.sh`**。loose class 不在 Steam depot 內，`app_update`
   只換 jar 不會刪掉它們——殘留的舊 patched class 仍會覆蓋新 jar。
   正式服另有一道保險：`native-observer/deploy/run-with-pfguard.sh` 每次開服先重跑 `install.sh` 的
   閘 1（payload SHA）與閘 2（jar 同源），不符就把 `java/zombie` 與 manifest 原子搬到
   `patch-disabled-<UTC>-autogate/`（不刪檔）並以原版啟動；只有搬不走時才拒絕啟動（exit 78）。
   這道閘只防「舊 patch 配新 jar」，不取代本步驟與下方的重建驗證。
1. 重新拉 jar → `.\build.ps1`——命中數全過＝手術座標仍有效，直接重新部署。
2. 建置失敗＝該 class 已變——重跑對應分析（`work/specs/` 有原始規格與方法論）再更新 `PatchConfig.java`。
3. **命中數守門有盲點：數量對不代表改對地方。** 常數手術尤其要用 `javap` 確認該常數的**語境**
   （前後指令）。42.20 實例：`IsoAnimal.respondToSound` 的壓力算式從 `radius / 20.0F` 改寫成
   `radius * 0.05F`，同時新增了 `fleeDistance = radius * 3.0F + 20.0F`——方法內仍剛好有一個
   `20.0f`，舊座標會**通過守門卻改到逃跑距離**。每次更新都該重跑語境確認，不能只看命中數。

## Linux 主迴圈健康檢查（選用）

`scripts/pz-health-watch.py` 與 loose-class 套件獨立，不會隨 `install.sh` 自動安裝。
它透過本機 RCON 的唯讀 `players` 命令確認主迴圈確實回應，而非只檢查程序或 TCP。
適用於 LinuxGSM 的 `pzserver` 使用者／實例；RCON 憑證直接讀取該實例設定，不列入命令列或日誌。

- `--root <LinuxGSM根目錄> --check-only`：僅檢查，不執行重啟。
- 自動恢復需以 root 執行，另外指定既有 `--update-lock` 與 `--flow-lock`，
  並由既有 monitor 互斥鎖保護整次執行；請先完成唯讀檢查，再整合監控入口。
- 啟動寬限 10 分鐘；連續三次命令查詢失敗才呼叫 LinuxGSM restart。
  人工停服與維護鎖優先，恢復嘗試間隔至少 30 分鐘；認證或設定錯誤不觸發重啟。
  這是故障恢復，不是記憶體洩漏修復；重啟命令完成也不代表遊戲已就緒。

隔離回歸檢查：`python3 scripts/test_pz_health_watch.py`（Linux／WSL，不操作真實遊戲程序）。

## VFE 可退場暫時修補（選用）

`scripts/apply_workshop_compat_patches.py --vfe-temporary <狀態目錄>` 只對已核對的
Vanilla Foods Expanded 3.3.0 **伺服器副本**就地修補兩個檔案：`vfx_agingmanager.lua` 與
`vfx_storydistribution.lua`。兩檔依序處理，各自套用、退場與記錄狀態，一檔退場或出錯不影響另一檔；
結束碼取兩者較差者（3 警告 > 2 待處理 > 0）。另指定 `--root`（伺服器 Workshop 內容目錄）、
`--game-version` 與 `--apply`；不帶 `--apply` 只檢查、不寫檔。
不要指向日常遊玩的 Steam 訂閱原檔。此模式不執行其他相容補丁，也不建立整檔覆蓋。

3.3.0 起作者已收編上一版的空格快篩（沒有地面物品也沒有容器的格子不排隊）。本版只在
`scanGridSquare` 的容器層加前置過濾：容器連同任意深度的背包裡，若沒有任何一件的短型名
屬於追蹤中的型別（替換、乳酪熟成、溫度三張索引），就用一次 `getCountTypeRecurse` 在
Java 端判定後略過，不在 Lua 端逐件讀；有的話照原版逐件走。清單依短型名由短到長排，因為
`ItemContainer.compareType` 只看第一個 indexOf 命中；引擎呼叫失敗（回傳 nil）時照原版走。
其餘排隊、512 上限、滿載即時掃描、取消與卸載流程及老化演算法都不改。

story distribution（9/28 取樣佔主執行緒 3.03%）：原版每次 LoadChunk 都對每個待處理容器做 5 次 Java
呼叫的失效檢查，再對每棟待處理建築呼叫 `isFullyStreamedIn`，成本隨「載入邊緣跨區的建築」累積。
本版把失效檢查改成依「state 所在 chunk 的那次載入」分組，每組每次 LoadChunk 只驗該 chunk 是否仍以
同一個 loadID 登記在原位置，不符才對組內逐一跑原版檢查；建築只在其範圍涵蓋的 64×64 ServerCell 載入時
檢查（使用者自建建築每次都查），多棟同時完成時照原版 `pairs` 的插入順序處理。替換內容、ZombRand
抽取順序、同步與除錯輸出不變。

首次套用前核對 Workshop 更新識別、`mod.info` 版本及原檔 SHA，保存乾淨原檔與指紋。
作者更新、本機版本或來源變更時，管理器會退場：**只有現檔完全等於我方修補版才還原；
作者新內容一律保留。** 退場狀態永久禁止自動重套，手動退場另加 `--vfe-retire --apply`。
退場中斷可重跑補完；備份損毀、狀態混用或不明來源不會被盲目覆寫。

每一版修補用自己的狀態目錄（狀態綁定 Workshop revision，舊目錄的 state 會被拒讀）；
換版時把既有更新／重啟流程中 JVM 啟動前的管理器呼叫改指新目錄，讓它在遊戲下載新版前先
處理退場。不另建排程。網路查詢失敗會回傳錯誤且不改檔；啟動整合應明示警告，不能把檢查
失敗當成作者已更新。已執行中的 Lua 不會因磁碟還原而卸載，仍須重啟載入新內容。
作者更新也不代表必然已修復同一問題，需另行驗收。

狀態目錄保存 `vfe-temporary-state.json`、`vfe-agingmanager-original.lua`、
`vfe-story-temporary-state.json`、`vfe-storydistribution-original.lua`，請保留作退場依據。
啟用 Lua checksum 的環境須循正常 MOD 配發流程，不應為本補丁關閉校驗。
驗證：`python scripts/test_vfe_temporary.py`；
`lua scripts/test_vfe_aging_behavior.lua <修補後副本> <未修改的 Workshop 原檔>`（第二個參數
啟用差分：同一批隨機世界跑兩個版本，物品、mod data、名稱、數值、同步呼叫與錯誤輸出須逐行相同）；
`lua scripts/test_vfe_story_behavior.lua <修補後副本> <未修改的 Workshop 原檔>`（差分含 cell 載入／卸載與
square／chunk／容器回收，物品、旗標、ZombRand、同步呼叫、輸出與錯誤須逐行相同且落在同一個引擎事件）。
Lua 測試為隔離夾具，驗伺服器側物品狀態與同步呼叫，不代表真實客戶端收包或效能百分比；
`getCountTypeRecurse` 與 chunk／cell 的載入順序是依反編譯寫的模型。

## 客戶端模組化安裝包

`build-client.ps1` 只建置 client 產物，不安裝、不啟動遊戲，也不寫入 server manifest：
未壓縮套件在 `dist-client-modular/pkg/`，ZIP 在 `output/MinidoracatClientPatches-42.21.0-0.2.2.zip`。

| 模組 | 用途 |
|---|---|
| `core` | 共用 Lua bridge、安裝指紋驗證、主選單啟動狀態；依賴它的模組會自動帶入 |
| `profiler` | Java→Lua callback 計時、具名區段、CSV／metadata／JFR 匯出；搭配獨立的 `MinidoracatDevProfilerFor42` 介面 MOD |
| `client-fixes-standard` | 貼圖管線修復、chunk 串流觀測、42.21.0 自建房間 XL 樹例外修補（docs/patches.md 2bl），使用較高貼圖門檻 |
| `client-fixes-lowmem` | 同一組修復，保留原版 50 MiB 門檻；與 standard 互斥 |

關閉遊戲後解壓完整套件，執行 `Install-Patches.bat` 選擇模組。
`Uninstall-Patches.bat` 可只移除所選模組；仍被其他模組依賴的 core 會保留。
安裝前驗證遊戲 jar、payload 與安裝記錄；重新安裝可修復已記錄的模組，未記錄的外來 class 拒裝，
卸載不刪內容已變動的檔案。中斷交易只依 SHA 辨認完整的交易前／後影像；無法辨認就保留現況與備份。
SHA 不是數位簽章，安裝器不防範同一使用者同時偽造檔案與管理記錄。
可辨識的舊版修復包會遷移並保留原變體；無法辨識的舊檔須先用原包的 `uninstall.bat` 處理。

啟動面板分開顯示「已安裝」與「本次 JVM 已觀察到 hook」；前者不代表後者。
分析器只記錄使用者啟動的本機 capture，不上傳，也不相容第三方 ZombieBuddy API。
遊戲更新前須移除 loose class，且**不可在 JVM 執行中卸載**。
管理器執行交易或等待確認時會獨占鎖定遊戲 exe；此時啟動遊戲會被系統拒絕，先結束管理器再啟動。
啟動驗證若仍發現 journal，會回報不相容並要求先用管理器復原，不把半套 payload 當成成功安裝。

驗證指令：`build-client.ps1`，接著單獨執行
`powershell -NoProfile -ExecutionPolicy Bypass -File patcher/tests-client-installer/Run-InstallerTests.ps1`。
安裝器測試會檢查 Java 行程，勿與 Java 建置或遊戲啟動同時執行。

## ☕ 支持作者

MOD 永遠免費。喜歡的話可以請我喝杯咖啡，贊助會用在伺服器與 MOD 開發上。

[![Ko-fi](https://raw.githubusercontent.com/Minidoracat/workshop-resources/refs/heads/main/badges/badge_kofi.png)](https://ko-fi.com/minidoracat)

## 授權與免責

本專案採 MIT 授權，見 [LICENSE](LICENSE)。

- **非官方專案**，與 The Indie Stone 無任何隸屬、合作或背書關係。Project Zomboid 與其相關
  素材之著作權均屬 The Indie Stone 所有。
- 本 repo **不散布任何遊戲二進位檔案**。所有手術都在使用者自己合法取得的
  `projectzomboid.jar` 上進行，jar 需自備；本專案只提供 patcher、helper 原始碼與安裝腳本。
- **修改遊戲檔案風險自負。** 伺服器端 `bash uninstall.sh`、客戶端 `Uninstall-Patches.bat`
  可移除對應模組；舊版客戶端包使用其原附 `uninstall.bat`。安裝前請自行備份。
- **每次 PZ 更新後都必須重新建置與驗證**。命中數守門擋得住座標漂移，擋不住語境漂移
  （見上方 SOP 第 3 點）——未重新驗證前，切勿把舊 patch 套到新版遊戲上。
