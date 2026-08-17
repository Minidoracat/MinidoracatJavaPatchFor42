# 全 Patch 優化原理與效果總結

> 最後更新：2026-08-16（W5–W9 全數上線、PSR v1.72 確認修好我方回報的回歸後）。本文是**面向營運的總覽**——
> 每項只講三件事：浪費/問題在哪、怎麼修、實測效果。逐項 javap 證據與安全論證見
> [patches.md](patches.md)，各波設計定稿見 `docs/*-design-*.md` 與 [specs/](specs/)。
> 現況（以 `PatchConfig.all()` 實數為準）：**29 個 patched class、37 個 patched method、
> 64 個命中點、17 個 runtime helper class 檔**（16 個手寫＋建置期生成的 `zombie.mdc.PatchInfo`）。
> 42.20.2 里程碑：官方收編 P5／popman 隔離／512→256 三組（見第四節），我方對應退役。
> 2026-08-08：受精蛋清除豁免退役（patch 有效但 client 端無對應改道，見 patches.md 2n）。
> 2026-08-13～14 的四起事故（容器環假死、地圖格載入活鎖 114 分鐘、雞舍 chunk 被抹除、
> CRC-blam 家族 43 筆資料損失）催生 W5–W9 五刀，全部是 vanilla 缺陷而非本專案所致。

## 全 Patch 清單（42.20.2 現役）

| 類別 | Patch 項目 | 對象 class | 命中 | Runtime helper | 一句話 |
|---|---|---|---|---|---|
| 效能 | W1-1 車輛視線預篩 | `IsoZombie` | 1 | VehicleIntersectPrefilter | 殭屍→車輛 OBB 相交前先做包圍球預篩，99.87% 拒絕 |
| 效能 | entity removal 索引化 | `EngineEntityManager`＋`EntityBucket` | 2+2 | FastIdentityArrayRemoval(+$State) | 批次卸載的 identity 線性搜尋 O(N)→O(1) |
| 效能 | W3-1 殭屍 ownership 錯峰 | `NetworkZombiePacker` | 1 | ZombieAuthThrottle | owner 穩定殭屍每 3 pass 才重選舉（原每 tick 全額 O(C×P)） |
| 效能 | W3-3 動物 spotted 距離預篩 | `IsoAnimal`（updateLOS） | 2 | AnimalSpottedPrefilter | 遠距（>max(12,視距+2)）呼叫重放前綴後跳過，攔截率 99.94% |
| 效能 | W3-4 車輛 couldSee 死工消除 | `BaseVehicle`（update） | 1 | VehicleCouldSeeGate | server 端結果進 vanilla no-op，直接短路 |
| 效能 | W4-1 chunk 供給併包 | `PlayerDownloadServer`（removeOlderDuplicateRequests） | 1 | ChunkRequestPacker | 供給只跑到設計值 15% 造成黑邊 livelock，佇列前段併包到批次上限 |
| 效能 | 食材重量記憶化（**實測後決定不啟用 on**） | `InventoryItem`（getExtraItemsWeight） | 1 | ItemWeightMemo | Moodle HEAVY_LOAD 每 tick 遞迴走訪整棵背包樹，每個 extraItem 都完整建構一個 InventoryItem 只為讀重量就丟棄。observe 實測（4 session／累計 9.68h）：命中率 99.997% 但呼叫速率僅 271–732/s、單次 2.1µs ⇒ 收益 0.06–0.18% 主迴圈（≈0.006–0.018 fps），不足以承擔 RNG 序列位移與首次執行共用實例的風險；維持 observe |
| 行為 | 動物壓力三調 | `IsoAnimal`（3 常數） | 3 | — | 閒置衰減×2、聲音壓力÷3、屠宰連鎖上限減半 |
| 修復 | 玻璃假死保險絲 | `IsoWindow` | 1 | GlassAttachmentGuard | removeGlassAttachments 無限迴圈改跳過＋定位 log |
| 修復 | 容器刷新修復 | `LootRespawn` | 2 | （LogFilter 兼任） | 自訂地圖無 TownZone 的原生固定容器恢復刷新 |
| 防崩潰 | null 頭部守衛 ×2 | `hit/Zombie`＋`hit/Fall` | 1+1 | — | 損壞封包 NPE 崩潰的 guard-before-super |
| 防崩潰 | W5 容器環守衛 ×2 | `ItemContainer`（getCharacter／isInCharacterInventory） | 1+1 | ContainerCycleGuard | 容器互裝成環的 StackOverflow 假死，identity 路徑偵測環＋深度保險絲切斷 |
| 防凍結 | W6 地圖格載入捕手 | `IsoChunk`（doLoadGridsquare） | 2 | ChunkLoadGuard | 「Entity is already registered」每 0.1 秒重撞的活鎖，記座標＋sprite 後跳過該物件 |
| 防資損 | W7 朝向暫存執行緒隔離 | `IsoGameCharacter` | 2 | ForwardVectorGuard | 共用 static `tempVector2_2` 競態致 chunk 載入失敗被 Blam 抹除，換執行緒私有替身 |
| 防資損 | W8 chunk 寫入閘 | `IsoChunk`（Save）＋`ServerChunkLoader$SaveLoadedTask`（save） | 2+1 | ChunkWriteGuard | 寫入前快照驗 len/CRC，損毀就擋下（磁碟保留上一版）＋蒐證＋checksum 歸零重試 |
| 防資損 | W9 存檔管線隔離 | `ServerChunkLoader$SaveChunkThread`（addLoadedJob）＋`$SaveLoadedTask`（save／release） | 4+4+1 | ChunkSaveIsolation | 共用 CRC32 與全域 chunk 池的存檔競態根治（CRC-blam 家族根因） |
| 抑噪 | 已知噪音樣式過濾 ×8 | `AnimationSet`/`SkinningBoneHierarchy`/`SpriteConfig`/`ItemPickInfo`/`PacketsCache`/`INetworkPacket`/`NetworkZombieManager`＋`GameServer`（sendToxicBuilding） | 1+1+1+9+1+1+1+1 | LogFilter | 只攔已知樣式，未知警告與反作弊照常輸出；toxic 那條抑噪前佔 console **45.5%**（15.41h／8 session 實測 164,176／360,669 行；PSR `suppressToxic` 每 2.5 真實秒逐 powerbank 廣播），**只攔 log 不動封包** |
| 觀測 | LoginMetrics | `LoginPacket` | 3 | MinidoracatLoginMetrics | 登入三個同步 DB 寫入的 elapsedNs |
| 觀測 | JoinMetrics | `CreatePlayerPacket`＋`GameServer`＋`ConnectPacket`＋`ConnectCoopPacket` | 4+2+1+1 | MinidoracatJoinMetrics | join/rejoin 各階段耗時歸因（實測 5.8–11.1s 停頓的證據源） |

合計：**29 個 patched class、37 個 patched method、64 個命中點、17 個 runtime helper class 檔**
（16 個手寫＋建置期生成的 `zombie.mdc.PatchInfo`；部署的 `.class` 檔另含 `$State` 等內嵌類別）。
數字以 `patcher/src/PatchConfig.java` 的 `all()` 逐項 `expectedHits` 為準——文件與程式碼衝突以程式碼為準。
另有 **client 端獨立包**（貼圖管線門檻＋洩漏根治，發佈於 `output\`，玩家自選安裝，不在 server manifest）。

### 退役／停用／否決（歷史記錄，詳見第四節）

| 項目 | 結局 |
|---|---|
| P5 IsoCell sidecar（15 站） | 42.20.2 官方收編 |
| popman buffer 隔離 v3（11 站） | 42.20.2 官方收編 |
| VehicleManager 512→256 | 42.20.2 官方收編 |
| W3-2 ECS memo | microbenchmark 實測淨劣化，撤刀 |
| 安全屋 room/building 修復 | 停用（觸發條件已移除，座標保留可隨時恢復） |
| ActionStateContainer 抑噪 | 42.20 官方降級 warn→trace，退役 |
| ZombieCountOptimiser 回收加速 | 42.20 官方重寫壓力模型，定案不恢復 |
| 受精蛋清除豁免（IsoGridSquare） | 2026-08-08 退役——server 端實測有效，但 client 端無對應改道且清單由 server 完整同步，玩家看不到也撿不起被豁免的蛋；改回原版（蛋照清），受精蛋請用雞舍孵 |

## 核心哲學

三波效能 patch 共用同一句話：**找出「算了也白算」的工作，證明它白算，然後不算。**
證明方式分三型：

| 型 | 代表 | 一句話 |
|---|---|---|
| 死工消除 | 車輛 couldSee | 結果直接進垃圾桶（server 端 vanilla 自己丟棄）——不算 |
| 保守預篩 | 車輛視線、動物 spotted | 便宜的幾何/距離檢查攔掉 99%+ 不可能有效果的呼叫，可疑的照舊全額委派 |
| 陳舊度換算力 | ownership 選舉、清單 sidecar | 答案幾百 ms 內不會變的計算，不必每 tick 重算——且延遲上界必須落在 vanilla 自己的容忍包絡內 |

安全底線（每一刀都遵守）：只用三種堆疊形狀不變的手術（呼叫改道／常數替換／頭部
null 守衛）；每個 helper 帶 vanilla fallback＋計數器；命中數＋語境雙守門，PZ 更新
漂移＝建置失敗而非默默錯位。

---

## 一、效能類（三波＋兩項基礎）

### W1-1 殭屍→車輛視線預篩（`IsoZombie.isVehicleBetween`，2026-08-02）

- **浪費**：每隻殭屍檢查視線是否被車擋住時，對整個 cell 的**每台車**做完整 OBB 相交
  （每台 2 次矩陣求逆＋6 次向量池借還）。低谷 dump 佔比 ~23%，第一代榜首。
- **修法**：改道到「線段到車輛保守包圍球」平方距離預篩——球外幾何上不可能相交直接
  回 null；球內或任何異常委派原版精確判定。per-vehicle 動態半徑（L1 上界），零 false-negative。
- **實測**：**拒絕率 99.87%**（rejected 8.9 億 vs delegated 128 萬），車輛碰撞主題從後續
  dump **完全消失**；低谷頻率 12+/日 → 1-2/日（與 W1-2 合併效果）。

### W1-2 `VehicleManager` 連線槽 512→256——**42.20.2 官方收編退役**（官方刪除雙 512 陣列改 per-connection HashMap，比砍半更徹底）

- **浪費**：serverUpdate 每 tick 無條件掃 512 個連線槽 × 全部車輛，但 RakNet 連線陣列
  只有 256、connection ID 解碼恆 <256——上半 512 槽純空轉。
- **修法**：建構子常數 512→256，掃描直接砍半。
- **實測**：該迴圈行號從 dump 消失（原 5/5 命中）。

### P5 IsoCell 三清單 identity sidecar（2026-08-03）——**42.20.2 官方收編退役**（官方原生伴生 Set＋isEmpty 快速路徑，15 站全數 O(1)；changelog 的「chunk unloading 效能修復」即此）

- **浪費**：chunk 卸載時每個物件要對「處理清單 P」「待移除清單 R」做線性 contains
  掃描——**P 實測 1.0～1.5 萬個元素**，幾乎全 miss 全掃；每 tick 的 removeAll 是
  O(P×R)。第二代榜首（dump 佔比 23-27%）。
- **修法**：identity membership sidecar（HashSet 鏡像三清單成員資格），miss 查詢
  O(P)→O(1)、removeAll O(P×R)→O(P+R)。清單本身仍是順序權威；抽驗發現失同步
  即自動整組回歸 vanilla（kill switch）。
- **實測**：chunk 卸載掃描主題 27% → **0**；上線至今 `rebuilds=0 divergence=0
  killed=false` 全綠；尖峰低谷 FPS 2-3 → 4-6、sampler 觸發 9 次/晚（頂格）→ 3 次。

### W3-1 殭屍 ownership 重選舉錯峰（2026-08-05）

- **浪費**：選舉冷卻以 `lastChangeOwner` 為基準，但該欄位只在實際換手時寫入——
  owner 穩定的殭屍**每 tick 全額重選舉**（O(連線×玩家) 距離掃描）。Z≈2500、C≈80
  時每 tick ~20 萬次距離計算，而答案 99% 與上一 tick 相同。
- **修法**：已擁有且存活的殭屍每 3 個 pass 才重選一次（id 錯峰、負載平滑）；無主/
  剛死/特殊選項即刻放行。延遲上界 300ms，遠在 vanilla 換手後 2000ms 冷卻包絡內。
  （此刀歷經三輪打回：wall-clock 版被三稜鏡審查抓到與退化 tick 週期共振、單欄位
  tick 版被 code review 抓到長 pass 步進鎖死，定稿為雙欄位偵測＋質數週期。）
- **實測**：首日白天 skip 27%（無主殭屍多，尖峰 owned 比例升高後上揚）、anomalies=0。
  預估尖峰省 tick 預算 3–10%。

### W3-3 動物 spotted 距離預篩（2026-08-05）

- **浪費**：每隻動物每 tick 掃同層**所有**移動物件（殭屍數千）呼叫完整 spotted()，
  但 spotted() 所有持久效果都要求距離 ≤10 格——對地平線外殭屍的呼叫全是白繳。
- **修法**：距離 > max(12, 該動物視距+2) 只重放無條件前綴（兩行簿記，逐句同構）、
  跳過其餘。動物數量一隻不動（營運約束）。三重 42.21 漂移防護（全 jar 子類走訪＋
  前綴指紋＋51 值有序常數包絡）。
- **實測**：首日 4h45m **攔截 37.6 億次、攔截率 99.94%**、anomalies=0、threshold
  動態跟隨（見過 12.0 與 21.0）。預估省 tick 預算 5–15%。

### W3-4 車輛 couldSee server 死工消除（2026-08-05）

- **浪費**：每車每 tick 掃車身 AABB 10-18 格算「玩家可見性」，結果只餵給
  `setTargetAlpha`（渲染透明度）——而 server 端該方法是 vanilla 自己
  `if(!GameServer.server)` 擋掉的**空操作**。每秒 5-25 萬次格子查找算完即丟。
- **修法**：server 端直接短路回傳；SmokeCheck 以 targetAlpha guard 指紋把「官方
  丟棄結果」的前提鎖進建置期。
- **實測**：首日 8500 萬次短路、`replicated=0`（判定零失誤）。估省 1–3%，與車數線性。
- **附註**：同波的 W3-2（ECS 查找快取）被 microbenchmark 實測否決（vanilla 0.93 vs
  memo 1.19 ns/call，淨劣化）而撤刀——審查證明「無風險」，只有量測證明「有收益」。

### 基礎-1 popman 共享 buffer 執行緒競爭修復（v3 隔離）——**42.20.2 官方收編退役**（官方 readByteBuffer 專用讀 buffer，與 v3 指令級同構）

- **問題**：`ZombiePopulationManager.byteBuffer` 由背景寫側與主執行緒讀側共用、讀側
  無鎖（vanilla 遺漏）→ position 併發亂跳 → BufferUnderflow ＋隨機欄位混讀——
  **實體消失事件的三大根因之一**。
- **修法**：updateMain 全部 10 處 buffer 讀取換成專用隔離 buffer（讀寫分離、零鎖），
  count-clamp 降為保險絲。
- **效果**：上線後 BufferUnderflow 歸零。

### 基礎-2 chunk unload entity removal 索引化（`EngineEntityManager`/`EntityBucket`）

- **浪費**：批次卸載時逐 entity 對全域陣列做 identity 線性搜尋。
- **修法**：4 個 callsite 改道 primitive sidecar index，O(N)→O(1)；碰撞/外部
  mutation/ordered 路徑全走原版 fallback。
- **效果**：benchmark 每 entity 439ns→63ns（8192 尺度；2026-08-06 壓實回退後由 42ns 回升，
  換得墓碑有界——初版停用 Trove auto-compaction 曾致墓碑飽和、主迴圈 15-25s 停頓，見 patches.md 2g）；
  線上 anomalies=0。

## 二、修復類

| 項 | 問題 | 修法 | 效果/狀態 |
|---|---|---|---|
| 玻璃假死保險絲（`IsoWindow.smashWindow`，2l） | vanilla `removeGlassAttachments` 移除失敗時無限迴圈——2026-08-02 全服凍結實案（100 條堆疊零 patch 類） | 重實作迴圈：移除失敗跳過並印出問題物件座標，不再卡死 | 上線後同型凍結零復發 |
| 受精蛋清除豁免（`IsoGridSquare.load`，2n） | 世界清理只比對 item type，分不出受精蛋；24hr 清除門檻 << 1260hr 孵化時間，地上孵化被封死 | 改道唯一豁免判定點，只對「可孵化且在孵化視窗內」追加豁免；視窗天花板保證不無界堆積 | **2026-08-08 退役**——server 端實測有效（keptLoads 3649／expired 0／anomalies 0，單顆蛋 progress 推進至 1148/1260），但清除區塊無 `GameClient.client` 守衛且 SandboxOptions 由 server 完整同步，client 每次 chunk 載入都自行濾掉那顆蛋（玩家看不到也撿不起來）。改回原版：蛋照清，引導玩家用雞舍孵化 |
| 原生固定容器刷新修復（`LootRespawn`，2e） | 自訂地圖缺 vanilla TownZone＋黏性 construction 旗標 → 固定容器永不刷新 | 窄範圍 fallback：只放行未搬動的原生固定容器 | 生效中 |
| 安全屋 room/building 綁定修復（2d） | B42.19 自訂大地圖的 binding 遺失 | 從 authoritative roomList 補回 roomId 再走完整原版驗證 | **2026-07-29 停用**——正式服已回歸原版地圖，觸發條件消失；座標已驗 42.20 仍有效，可隨時解註解恢復 |
| Client 貼圖管線（2j，獨立 client 包） | 50MB DirectBuffer 硬門檻讓載入執行緒無限 sleep → 實體隱形；另有四處洩漏根因（S1/S2/S4/S6） | 門檻觀測＋洩漏根治第一波 | v2.0 出貨於 output\（玩家自選安裝） |

### W4–W9：2026-08-13～14 事故修復六刀（全部是 vanilla 缺陷，非本專案所致）

這六刀的共同性質與前三波效能刀不同：**不是省工，是止血**。每一刀都有正式服實案、
都附「非本專案所致」的 javap／指令級實證，且都留旋鈕可不重新部署即降級回 vanilla。

| 項 | 問題 | 修法 | 效果/狀態 |
|---|---|---|---|
| W4-1 chunk 供給併包（`PlayerDownloadServer`，2p） | vanilla 供給只跑到設計值 15%（每 worker 週期只處理一個 ccr＝約 30 chunk/s，預算 200 chunk/s 浪費 85%）；積壓越過 client 8 秒逾時後 client 丟棄已送達資料並重發且不通知取消 → 自我維持 livelock＝永久黑邊（實測 pending 恆 240、18 分鐘燒 105MB 全丟棄、零 chunk 載入） | headCall 掛在 `removeOlderDuplicateRequests`（**必須在 `workerThread.ready` 閘內**，掛 `update()` offset 0 會與 worker 同改一個 plain ArrayList＝跨玩家池汙染），把佇列前段併包到批次上限；不新增 chunk、不改順序、不碰 largeArea | 生效中。批次上限 8（vanilla 20 的 40%）、每 100ms 額外搬移預算 120，`-Dmdc.chunkPacker.windowBudget=0` 即整刀停用 |
| W4-2 請求逾時 8s→15s（client 端獨立包，2p） | `RequestZipList`／`SentChunkPacket` 皆 `reliability=2`（RELIABLE），8 秒逾時幾乎不是在救真的遺失，而是在懲罰 server 慢並觸發上面那條 livelock | `WorldStreamer.resendTimedOutRequests` 的 `8000L`→`15000L`（方法內常數替換，全 class 僅此一處） | 隨 client 包出貨（玩家自選安裝，不在 server manifest） |
| W5 容器環防崩潰守衛（`ItemContainer`，2q） | 2026-08-13 21:31 主迴圈死於 `StackOverflowError`，1024 層全是 `ItemContainer.getCharacter` 自我遞迴——假死 13 分鐘、graceful `quit` 收不進去、看門狗強制重啟。vanilla 沿「容器→物品→容器」爬升找擁有者且**零迴圈偵測**，`AddItem` 只擋同 ID 重複、不阻止把容器放進自己的子孫，MP 封包驅動搬移即可造環 | 改道兩處唯一自身遞迴（`getCharacter` 回 `null`、`isInCharacterInventory` 回 `false`，皆與 vanilla 同值）；ThreadLocal identity 爬升路徑偵測真環（通常 2-3 層命中）＋`MAX_DEPTH`（預設 64，硬上限 256）保險絲；切斷時印環上 containerId／itemId／fullType 與閉合點 | 生效中，同型假死零復發。**但這是止血＋捕手，不是根治**：環上容器 `getCharacter()` 回 null 且 `getParent()` 為 null 時 `GameServer` 五個庫存廣播的第三條分支是空的＝封包不送不 log（玩家體感東西憑空消失）。根因刀 W5-2（`AddItem` 加入前拒絕成環）未落地前不可認定此類假死已排除 |
| W6 地圖格載入捕手（`IsoChunk.doLoadGridsquare`，2r） | 2026-08-14 01:34 frame 永久停在 `f:46186`，**凍結 114 分鐘**，靠排程 mod 更新重啟才結束（沒人是為了救它而重啟）。`IllegalArgumentException: Entity is already registered` 由 `IsoObject.addToWorld` 拋出，`GameServer.main` 的攔截點在迴圈最上方 → 這一圈剩下的工作（更新世界、處理封包、推進 frame）全跳過，而該地圖格還在待載入佇列，每 0.1 秒重撞一次。**活鎖非崩潰，「進程掛掉就重啟」救不了**；8/07 18:05 有逐行相同的前例 | 改道 `doLoadGridsquare` 內兩處通往同一 throw 點的 `addToWorld`（`IsoObject` ×1、`IsoMovingObject` ×1），catch 後記座標＋sprite 名跳過該物件。降級極小：throw 點在 offset 0，後續 container／items／generator 步驟本來就沒執行，且會拋出正代表先前成功那次已做過 | 生效中。`BaseVehicle.addToWorld` 那處**刻意留 vanilla**（自帶早退守衛、方法體另含 parts/engine 掛載，包住等於吞更大範圍）——有意識取捨，SmokeCheck 把它的呼叫數釘在 1，出現第四處即建置失敗 |
| W7 朝向暫存執行緒隔離（`IsoGameCharacter`，2s） | 2026-08-13 19:55 玩家 Player-A 的雞舍連水桶整組消失：chunk 1160,968 載入失敗被 vanilla `Blam + LoadBrandNew` 抹除重生，46,142 → 8,549 bytes（雞舍＋32 隻家禽的完整基因組全滅，只剩草地）。根因是 `setForwardDirectionFromIsoDirection` 用全域共用 `tempVector2_2` 當暫存，而 `getVectorFromDirection` 開頭無條件歸零再填值——主執行緒與 `LoaderThread` 同時走這段就讀到 (0,0)，`normalize()` 長度 0 → `IllegalStateException` | 方法內兩處 `getstatic tempVector2_2` 各接一個 `invokestatic` 到 helper，回傳執行緒私有替身（3 bytes、堆疊 1→1，形狀最單純的一類手術；vanilla 方法體只有 8 條指令、無分支無 frame） | 生效中。**範圍界定**：只治「毀存檔」那條路徑；全 log 保留期 67 次同一例外中另 66 次走 `IsoDirections.TEMP` → `createRealZombieAlways` 的**獨立**競態（落在主執行緒、被 `IngameState.UpdateStuff` 吞掉、每次只帶掉一個 tick、無資料損失）。`IsoDirections` 是全遊戲高流量核心 enum，爆炸半徑不同級，另案評估 |
| W8 chunk 寫入閘（`IsoChunk.Save`＋`SaveLoadedTask.save`，2t） | 累計 **43 個 chunk** 因 `SANITY CHECK FAIL` 被 Blam 抹除重生，損失約 143KB 玩家建造資料且持續發生。鑑識定案：43/43 筆 log 值與磁碟檔逐位元組相符＝**載入側無辜、檔案寫入時就壞了**（A 組 16 筆 crc=0＋body 自洽＝被捕捉在回填 len 與 crc 兩行之間；B 組 27 筆 header 屬於別份 body＝寫檔與重填撕裂） | 閘門**刻意不依賴根因**：全 jar 恰 5 個 `SafeWrite` 呼叫點（SmokeCheck census 釘死），伺服器實際可達的 3 個全改道到「快照→驗 len/CRC→放行或擋下」的 helper。擋下＝跳過寫入（磁碟保留上一版）＋stack 蒐證＋checksum 歸零自癒重試。掛點必須在**進入 `SafeWrite` 之前**（它的 `new FileOutputStream` 建構當下就 truncate 舊檔） | 生效中，預設 enforce（`-Dmdc.chunkWriteGuard=0` 停用／`=2` observe）。首晚攔下 8 筆損毀寫入、零資料損失，且 BLOCKED stack 直接指認寫入路徑——這 8 筆現行犯就是 W9 定罪的證據 |
| W9 存檔管線隔離（`SaveChunkThread`＋`SaveLoadedTask`，2u） | **CRC-blam 家族根治刀**。W8 首晚 8 筆 BLOCKED 全走 `SaveLoadedTask` 路徑、簽名全為「len 正確＋crc 0/垃圾」——唯一相容機制是 header 指紋競態：`addLoadedJob` 用的 `SaveChunkThread.crc32` 是單一共用實例，而 `addLoadedJob` 可在主迴圈（`ServerCell.update`→`saveChunk`）與 `GameServer$1`（shutdown hook 的 `QueuedSaveAll`）並行；對方 `reset()` 插在我 update 與 getValue 之間 → 指紋 0（A 組），update 交錯 → 垃圾（B 組）。另 `SaveLoadedTask.save` 四連讀外層 `ServerChunkLoader.crcSave` 共用實例，可在 `SaveChunkThread` 與 `LoaderThread` 並行 | 三刀：(1) `crc32` GETFIELD → 執行緒私有（根絕 header 指紋競態）；(2) `crcSave` 四個 GETFIELD 同形替換為執行緒私有（去重誤判＝陳舊跳寫、客戶端校驗錯亂＝重送）；(3) `getChunk`／`getByteBuffer`／`releaseChunk` 改道私有池，讓存檔管線退出與 N 條發送 WorkerThread 共用的 `ClientChunkRequest` 全域 static 池，恢復單一所有權鏈 | 生效中（`-Dmdc.chunkSaveIsolation=0` 停用）。**驗證閉環＝W8 的 `flagged` 計數器應歸零**；不歸零代表機制另有分支，用 BLOCKED stack 續查。W8 閘不拆，永久保險絲 |

## 三、防崩潰與抑噪

- **null 頭部守衛 2 項**（`hit/Zombie`、`hit/Fall`）：惡意/損壞封包導致的 NPE 崩潰，
  guard-before-super 擋下。負對照實測：原版必拋 NPE、修補版安靜返回。
- **遞迴／活鎖／資損守衛 5 項**（W5 `ItemContainer`、W6 `IsoChunk.doLoadGridsquare`、
  W7 `IsoGameCharacter`、W8 `IsoChunk.Save`＋`SaveLoadedTask.save`、W9 存檔管線）：
  全部帶計數器＋不需重新部署的旋鈕，明細與已知降級見第二節「W4–W9」小節。
- **抑噪 8 項**（AnimationSet／SkinningBoneHierarchy／SpriteConfig／ItemPickInfo／
  PacketsCache／INetworkPacket／NetworkZombieManager／GameServer.sendToxicBuilding）：只攔
  已知噪音樣式，未知警告與**反作弊警告照常輸出**。價值：console log 從噪音海變成可鑑識的
  訊號源——後續所有低谷/凍結/實體消失的診斷都建立在這之上。2026-08-16 新增的第 8 項是
  最大單一噪音源：`Send Toxic Building at [ … ]` 抑噪前佔 console **45.5%**（15.41 小時／8 session
  實測 164,176／360,669 行，逐 session 35.5%–80.8%），
  來源是 PSR 的 `PBSystem.suppressToxic` 掛 `Events.EveryOneMinute`（Day Length=1h → 每 2.5
  真實秒）逐 powerbank 無條件 `setToxic`，而 `IsoBuilding.setToxic` 的 putfield 沒有變更比對。
  **只攔 log、不動封包**——封包本身是 client 端 toxic 狀態的來源，攔它會把玩家鎖在毒氣室。
- **觀測 2 項**（LoginMetrics／JoinMetrics）：登入三個同步 DB 寫入與 join 四段重活
  的 elapsedNs 量測，不改任何順序與例外邊界。成果：把「join 造成主迴圈停頓
  5.8/6.6/11.1 秒」從猜測變成實測數字，驅動了 PingLimit 決策。

## 四、42.20 已移除／停用項（誠實記錄）

| 項 | 原因 |
|---|---|
| ActionStateContainer 抑噪 | TIS 官方自己把 warn 降級為 trace，噪音源已消失 |
| ZombieCountOptimiser 回收加速 | 重新分析已完成、定案不恢復：42.20 的 culling 只掃 per-connection 的有主殭屍，碰不到無主殭屍（記憶體壓力主源），加速取樣與原始目標脫鉤（patches.md 2a） |
| SafehouseClaimPacket 修復 | 觸發條件（自訂地圖）已從正式服移除，無症狀不介入驗證路徑 |
| W3-2 ECS memo | microbenchmark 實測淨劣化，撤刀 |
| P5 IsoCell sidecar（15 站） | **42.20.2 官方收編**：官方伴生 Set 原生 O(1)＋isEmpty 快速路徑，優於我方 O(P+R)；IsoDeadBody 旁路變異亦被官方 root fix |
| popman buffer 隔離 v3（11 站） | **42.20.2 官方收編**：官方 readByteBuffer 讀寫隔離與 v3 完全同構，clamp 保險絲失去防護對象 |
| VehicleManager 512→256 | **42.20.2 官方收編**：connected[512]/connectionState[512] 雙陣列整個刪除，改 per-connection vehicleStates HashMap |

## 五、配套 config 調整（非 patch，但屬同一條優化線）

| 設定 | 變更 | 效果 |
|---|---|---|
| `PingLimit` | 400→800（2026-08-04） | 登入誤殺與重連風暴：ping 踢人 18 次/日 → 5 次/日（-72%）；真爛線仍由 20/60 持續性條件把守 |
| `SaveWorldEveryMinutes` | 15→30→60 | 全服存檔凍結（每次 ~5-6s「Pausing clients」）從每 15 分鐘降為每小時 |
| `BackupsPeriod` | 30→120 | PZ 內建備份（25s 壓縮 I/O）從每半小時降為每兩小時；`BackupsOnStart` 保留 |

## 六、累計效果時間線

| 時點 | 狀態 |
|---|---|
| 8/1（無效能 patch） | 低谷 FPS 1-6、sampler 觸發 12+/日、車輛碰撞主題 23% |
| 8/2（W1 上線） | 車輛主題歸零、觸發 1-2/日 |
| 8/3 尖峰（80 人，P5 前基線） | 21:00-23:05 連續 FPS 2-6、觸發 9 次頂格、卸載主題 27% |
| 8/4 尖峰（P5 生效） | 低谷 4-6、觸發 3 次、卸載主題 0、22:00 後 41 分鐘安靜 |
| 8/5（W3 上線） | 三刀計數器全綠：動物攔截 99.94%（4h45m 攔 37.6 億次）、車輛 8500 萬次短路、ownership 節流生效 |
| 8/6（42.20.2） | 官方收編 P5／popman／512→256 三組（changelog 明寫修復 high-pop server 的 chunk unloading lag——與我方 8/3 診斷同源）；全 patch 覆核後 23 class 續用，34 classes 重新部署 |
| 8/13（W4 上線＋版本指紋＋兩起事故） | 黑邊根因修復上線：W4-1 chunk 供給併包（server）＋W4-2 請求逾時 8s→15s（client），供給從約 30 chunk/s 解放、livelock 的自我維持條件被拆掉；同日 patch 版本指紋（建置期生成 `zombie.mdc.PatchInfo`，log 印側別/版本/建置時間/jar 同源指紋）落地。事故：19:55 Player-A 雞舍所在 chunk 因 `tempVector2_2` 競態載入失敗被 Blam 抹除（46,142 → 8,549 bytes）；21:31 容器環 `StackOverflowError` 全服假死 13 分鐘（graceful `quit` 收不進去、看門狗強制重啟）→ W5 容器環守衛當晚落地 |
| 8/14（一起事故＋四刀） | 01:34 `Entity is already registered` 活鎖 **凍結 114 分鐘**（frame 停在 f:46186，靠排程 mod 更新重啟才結束）→ W6 捕手；同日 CRC-blam 家族鑑識定案（43 筆、~143KB 損失，證明寫入側有罪）→ W7 朝向暫存隔離＋W8 寫入閘上線，W8 首晚攔下 8 筆損毀寫入、零資料損失；這 8 筆現行犯把根因從「嫌疑」推進到「定罪」（共用 `CRC32` 指紋競態）→ 同日 W9 三刀根治，CRC-blam 家族收口 |
| 8/15（PSR 回歸回報） | 回報 PSR v1.71 的 CPU 回歸（`docs/report/psr-1.71-server-fps-report.md`）：23 人時 8.8→6.4 fps 且持續下滑、`coverage REMOVE` 1103 行/2.5h（`complete=true` 僅 11/1067）、`Server is too busy` 12 次、12 份 jstack 指向 87,035 squares 的 per-square `RecalcAllWithNeighbours`；我方以 `ChargeFreq=2` 暫時止血 |
| 8/16（巡檢實測＋第 8 把抑噪刀） | 約 **63 人在線**、主迴圈 **9.36–10.10 fps**、**所有 patch 計數器 anomalies=0**。PSR 作者已在 **v1.72** 修掉我方回報的回歸（刪除 `psrSweepRect` 內的 per-square `RecalcAllWithNeighbours`，並在註解引用我方數據）：`coverage REMOVE` **1103 行/2.5h → 20 行/46min**（`complete=true` 從 11/1067 變成 5/8）、`Server is too busy` **12 次 → 0 次**。同日巡檢另抓到最大單一噪音源——`Send Toxic Building at [ … ]`（當時單一時間窗估 34.4%／9512 行；**8/17 以 15.41 小時 8 session 重算為 45.5%／164,176 行**）→ 新增 `GameServer.sendToxicBuilding` 抑噪（第 8 項，只攔 log 不動封包）。`ChargeFreq=2` 尚未回復為 1；PSR 殘留項待回報（8/17 重寫為四項） |
| 8/17（部署生效＋PSR 1.72 對照＋記憶化定案） | 兩刀於 **01:28** 重啟生效（`PatchInfo built=00:16` → 部署後第一次排程重啟；`01-28`／`04-04`／`04-53` 三 session 的 toxic 皆為 0）：`Send Toxic Building` 10,654 行/h → **0**，其餘 Multiplayer 訊息照常；48 個 loose class 在位、SHA 對帳 bad=0。PSR v1.72 **凍結快照 `2026-08-17 11:12:54`／15.01 小時／8 session** 對照：REMOVE **107.7/h → 10.9/h**（平均 **9.9×**，per-session 4.7×–29×）、fps **9.93–10.02 平坦 3.5h**、`too busy` 12 次 → **1 次**（該次前 10 幀無 logged REMOVE；但 ADD／reapply sweep 不印 log，故**不能據此排除 PSR**，只能說該條 log 線上無時間關聯）。殘留四項寫成 `docs/report/psr-1.72-followup.md`。ChunkPacker `overrunTicks` 觀察點結案（`overrunTicks/calls` 恆定 0.14–0.15%＝預算閘正常累計）。**食材重量記憶化實測定案不啟用 `on`**：observe 樣本窗 4 session／9.68h，命中率 99.997% 但呼叫速率僅 271–732/s、單次 2.1µs ⇒ 上限 0.06–0.18% 主迴圈（≈0.006–0.018 fps），不足以承擔 RNG 序列位移＋首次執行共用實例的風險 |

誠實邊界：主迴圈是單執行緒，Amdahl 定律決定了沒有銀彈——每一波都是「低谷變淺、
變稀」而非平均 FPS 飆升；80+ 人的瀰漫負載（LOS thread 飽和、join chunk 同步、
SaveAll 凍結）仍有結構性成分是三形狀手術範圍外的，已逐項記錄於各設計文件的
「無法以現行手法處理」清單。
