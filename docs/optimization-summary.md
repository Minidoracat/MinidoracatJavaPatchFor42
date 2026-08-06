# 全 Patch 優化原理與效果總結

> 最後更新：2026-08-05（W3 上線後）。本文是**面向營運的總覽**——每項只講三件事：
> 浪費/問題在哪、怎麼修、實測效果。逐項 javap 證據與安全論證見
> [patches.md](patches.md)，各波設計定稿見 `docs/*-design-*.md` 與 [specs/](specs/)。
> 現況：**23 個 patched class、34 個 runtime class、34 處手術、44 個命中點**（42.20.2）。
> 42.20.2 里程碑：官方收編 P5／popman 隔離／512→256 三組（見第四節），我方對應退役。

## 全 Patch 清單（42.20.2 現役）

| 類別 | Patch 項目 | 對象 class | 命中 | Runtime helper | 一句話 |
|---|---|---|---|---|---|
| 效能 | W1-1 車輛視線預篩 | `IsoZombie` | 1 | VehicleIntersectPrefilter | 殭屍→車輛 OBB 相交前先做包圍球預篩，99.87% 拒絕 |
| 效能 | entity removal 索引化 | `EngineEntityManager`＋`EntityBucket` | 2+2 | FastIdentityArrayRemoval(+$State) | 批次卸載的 identity 線性搜尋 O(N)→O(1) |
| 效能 | W3-1 殭屍 ownership 錯峰 | `NetworkZombiePacker` | 1 | ZombieAuthThrottle | owner 穩定殭屍每 3 pass 才重選舉（原每 tick 全額 O(C×P)） |
| 效能 | W3-3 動物 spotted 距離預篩 | `IsoAnimal`（updateLOS） | 2 | AnimalSpottedPrefilter | 遠距（>max(12,視距+2)）呼叫重放前綴後跳過，攔截率 99.94% |
| 效能 | W3-4 車輛 couldSee 死工消除 | `BaseVehicle`（update） | 1 | VehicleCouldSeeGate | server 端結果進 vanilla no-op，直接短路 |
| 行為 | 動物壓力三調 | `IsoAnimal`（3 常數） | 3 | — | 閒置衰減×2、聲音壓力÷3、屠宰連鎖上限減半 |
| 修復 | 玻璃假死保險絲 | `IsoWindow` | 1 | GlassAttachmentGuard | removeGlassAttachments 無限迴圈改跳過＋定位 log |
| 修復 | 受精蛋清除豁免 | `IsoGridSquare` | 1 | FertilizedEggGuard | 孵化視窗內的受精蛋不被世界清理刪除 |
| 修復 | 容器刷新修復 | `LootRespawn` | 2 | （LogFilter 兼任） | 自訂地圖無 TownZone 的原生固定容器恢復刷新 |
| 防崩潰 | null 頭部守衛 ×2 | `hit/Zombie`＋`hit/Fall` | 1+1 | — | 損壞封包 NPE 崩潰的 guard-before-super |
| 抑噪 | 已知噪音樣式過濾 ×7 | `AnimationSet`/`SkinningBoneHierarchy`/`SpriteConfig`/`ItemPickInfo`/`PacketsCache`/`INetworkPacket`/`NetworkZombieManager` | 1+1+1+9+1+1+1 | LogFilter | 只攔已知樣式，未知警告與反作弊照常輸出 |
| 觀測 | LoginMetrics | `LoginPacket` | 3 | MinidoracatLoginMetrics | 登入三個同步 DB 寫入的 elapsedNs |
| 觀測 | JoinMetrics | `CreatePlayerPacket`＋`GameServer`＋`ConnectPacket`＋`ConnectCoopPacket` | 4+2+1+1 | MinidoracatJoinMetrics | join/rejoin 各階段耗時歸因（實測 5.8–11.1s 停頓的證據源） |

合計：23 個 patched class、44 個命中點、11 個 runtime helper（34 classes）。
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
| 受精蛋清除豁免（`IsoGridSquare.load`，2n） | 世界清理只比對 item type，分不出受精蛋；24hr 清除門檻 << 1260hr 孵化時間，地上孵化被封死 | 改道唯一豁免判定點，只對「可孵化且在孵化視窗內」追加豁免；視窗天花板保證不無界堆積 | 生效中 |
| 原生固定容器刷新修復（`LootRespawn`，2e） | 自訂地圖缺 vanilla TownZone＋黏性 construction 旗標 → 固定容器永不刷新 | 窄範圍 fallback：只放行未搬動的原生固定容器 | 生效中 |
| 安全屋 room/building 綁定修復（2d） | B42.19 自訂大地圖的 binding 遺失 | 從 authoritative roomList 補回 roomId 再走完整原版驗證 | **2026-07-29 停用**——正式服已回歸原版地圖，觸發條件消失；座標已驗 42.20 仍有效，可隨時解註解恢復 |
| Client 貼圖管線（2j，獨立 client 包） | 50MB DirectBuffer 硬門檻讓載入執行緒無限 sleep → 實體隱形；另有四處洩漏根因（S1/S2/S4/S6） | 門檻觀測＋洩漏根治第一波 | v2.0 出貨於 output\（玩家自選安裝） |

## 三、防崩潰與抑噪

- **null 頭部守衛 2 項**（`hit/Zombie`、`hit/Fall`）：惡意/損壞封包導致的 NPE 崩潰，
  guard-before-super 擋下。負對照實測：原版必拋 NPE、修補版安靜返回。
- **抑噪 7 項**（AnimationSet／SkinningBoneHierarchy／SpriteConfig／ItemPickInfo／
  PacketsCache／INetworkPacket／NetworkZombieManager）：只攔已知噪音樣式，未知警告與
  **反作弊警告照常輸出**。價值：console log 從噪音海變成可鑑識的訊號源——後續所有
  低谷/凍結/實體消失的診斷都建立在這之上。
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

誠實邊界：主迴圈是單執行緒，Amdahl 定律決定了沒有銀彈——每一波都是「低谷變淺、
變稀」而非平均 FPS 飆升；80+ 人的瀰漫負載（LOS thread 飽和、join chunk 同步、
SaveAll 凍結）仍有結構性成分是三形狀手術範圍外的，已逐項記錄於各設計文件的
「無法以現行手法處理」清單。
