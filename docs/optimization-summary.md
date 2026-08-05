# 全 Patch 優化原理與效果總結

> 最後更新：2026-08-05（W3 上線後）。本文是**面向營運的總覽**——每項只講三件事：
> 浪費/問題在哪、怎麼修、實測效果。逐項 javap 證據與安全論證見
> [patches.md](patches.md)，各波設計定稿見 `docs/*-design-*.md` 與 [specs/](specs/)。
> 現況：**28 個 patched class、43 個 runtime class、50 處手術、71 個命中點**（42.20）。

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

### W1-2 `VehicleManager` 連線槽 512→256

- **浪費**：serverUpdate 每 tick 無條件掃 512 個連線槽 × 全部車輛，但 RakNet 連線陣列
  只有 256、connection ID 解碼恆 <256——上半 512 槽純空轉。
- **修法**：建構子常數 512→256，掃描直接砍半。
- **實測**：該迴圈行號從 dump 消失（原 5/5 命中）。

### P5 IsoCell 三清單 identity sidecar（2026-08-03）

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

### 基礎-1 popman 共享 buffer 執行緒競爭修復（v3 隔離）

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
- **效果**：benchmark 每 entity 439ns→42ns（8192 尺度）；線上 anomalies=0。

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
| ZombieCountOptimiser 回收加速 | TIS 重寫了整個壓力模型，舊結論失去依據，重新分析前不恢復 |
| SafehouseClaimPacket 修復 | 觸發條件（自訂地圖）已從正式服移除，無症狀不介入驗證路徑 |
| W3-2 ECS memo | microbenchmark 實測淨劣化，撤刀 |

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
| 8/5（W3 上線） | 三刀計數器全綠；**尖峰驗收待今晚四晚對照** |

誠實邊界：主迴圈是單執行緒，Amdahl 定律決定了沒有銀彈——每一波都是「低谷變淺、
變稀」而非平均 FPS 飆升；80+ 人的瀰漫負載（LOS thread 飽和、join chunk 同步、
SaveAll 凍結）仍有結構性成分是三形狀手術範圍外的，已逐項記錄於各設計文件的
「無法以現行手法處理」清單。
