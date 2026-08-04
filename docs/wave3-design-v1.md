# Wave 3 設計 v2——三主題四刀（42.20）

> v2（2026-08-05 定稿）：吸收三稜鏡對抗審查面板（MP 行為／位元碼併發／生態維運）處方後的
> 實作版。與 v1 的差異見文末「審查修正記錄」。codex 深審 job 第三度卡死（1h43m 無工具活動）
> 後取消，改由三個獨立 context 的 Claude 審查者替代——此為 P5 時代已驗證的 fallback。

來源：2026-08-04 深夜三線並行分析（殭屍封裝／LOS／車輛 per-tick），兩晚 thread dump 佐證
（8/3 基線 30 dumps、8/4 尖峰 9 個工作樣本）。本文件是 codex 對抗審查的標的；完整分析原文見
workflow `wave3-triple-analysis` 輸出。

## 背景與約束

- 42.20 jar baseline SHA-256 `e4661ca9…ea54b8`；70–85 人尖峰、主迴圈 ~10fps。
- 只允許三種手術形狀：(a) 呼叫改道（stack shape 不變）、(b) 方法內常數替換（語境鎖定）、
  (c) 頭部 null 守衛。嚴禁中段 early-return、改簽名、加欄位。
- 行為紅線：不改殭屍/動物上限；玩家可感知行為不變；「新鮮度降級」需論證上界且備 vanilla fallback＋計數器。
- 既有 patch 不得相撞：IsoAnimal 三處 const-change（updateStress 5500／respondToSound 0.05f／killed 30.0）、
  VehicleManager ConstChange(512→256)、IsoZombie VehicleIntersectPrefilter、IsoCell/IsoObject/IsoDeadBody
  的 CellListMembership 15 處改道。

## 本波四刀（皆 GO）

### W3-1 殭屍 ownership 重選舉錯峰節流（本波最大收益）

- **座標**：`NetworkZombiePacker.updateAuth()V` offset 33 的
  `INVOKEVIRTUAL NetworkZombieManager.updateAuth:(Lzombie/characters/IsoZombie;)V`（全方法唯一，line 175）
  → `INVOKESTATIC zombie.mdc.ZombieAuthThrottle.updateAuth(Lzombie/popman/NetworkZombieManager;Lzombie/characters/IsoZombie;)V`
- **根因**：`lastChangeOwner` 只在實際換手時寫入 → owner 穩定的殭屍每 tick 都全額重選舉
  （grapple 檢查＋2 次 ECS 查找＋O(C×P) 距離掃描）。Z≈2500、C≈80 → 每 tick ~20 萬次距離/相關性計算。
  2000L 是換手後冷卻，**不是**週期節流。
- **Helper 語意**（v2——審查後定稿）：
  - 即刻放行三類（零延遲、與 vanilla 相同）：`owner==null`（新載入/孤兒）、`isDead()`（屍體
    落地/釋放/die() 備援鏈，審查補列）、`SwitchZombiesOwnershipEachUpdate=true`（選項語意
    為每 update 輪轉，節流與其矛盾；每次 live 讀，reloadoptions 熱改即時生效）
  - 否則 `(tick + z.onlineId) % 3 == 0` 才 delegate——**tick 為 helper 內部 pass 計數器**
    （雙欄位版：距上次呼叫 ≥50ms＝新 pass；快 tick 保底距上次推進 ≥250ms 亦推進），保證
    每隻已擁有殭屍每 3 個 pass 必選舉一次。PERIOD 取質數 3：長 pass（迴圈本體 ≥50ms）
    步進變 2 時 gcd(2,4)=2 會鎖死半數殘差（code review MAJOR-1），gcd(2,3)=1 免疫。
    全程 long 算術；負 onlineId 在整數倍時 % 仍為 0，節奏一致
  - stagger 判斷包 try/catch，任何例外一律放行；delegate 在 try 外，絕無 double-call
  - 計數器 delegated/skipped/anomalies 週期印行
- **共振分析（v1 wall-clock 版的否決依據，三稜鏡一致 major）**：`millis/100 % 4` 的桶每
  100ms 前進 1；tick 週期退化為 200ms 時桶步進 2（gcd=2 → 半數殘差不可達）、400ms 時
  gcd=4（3/4 殭屍鎖死）——設計要治的 dip 場景反而讓大半殭屍整段 dip 不重選舉。pass 計數器
  的步進恆為 1，殘差輪轉與 tick 週期無關。
- **行為安全**：vanilla 換手後本就凍結重選舉 2000ms（含 grapple/target 轉移路徑）；ZombieList
  廣播節奏 3800–5000ms。錯峰上界 4 個 pass（400ms/tick 最壞 1.6s < 2000ms 冷卻包絡）。
  `clearTargetAuth` 內另一 callsite 不動（斷線清理不節流；SmokeCheck 斷言 NetworkZombieManager
  ——其本就因抑噪 patch 在修補輸出——內部零 throttle 改道）。
- **下游疊加契約**：未來 ping 加權 owner 選舉（改 manager.updateAuth 內部）須以節流後頻率
  （~400ms/隻）為基準頻率重新論證。
- **預期**：跳過 ~75% updateAuth 本體，估回收 tick 預算 5–15%。

### W3-2 ECS getECSClass 純函式 memo——**已撤刀（實測否決）**

> 定稿後 code review 要求先量測（MINOR-4），microbenchmark 結果：vanilla 0.93 ns/call vs
> memo 1.19 ns/call——深度 1 元件（常態）的 superclass walk 是 1 次 getSuperclass intrinsic
> ＋2 次參考比較，比 ClassValue fast path 更便宜，memo 為**淨劣化**。整刀移除（helper／
> PatchConfig／SmokeCheck／manifest／spec 全撤）。教訓入檔：三稜鏡零 finding 只證明「零風險」，
> 不證明「有收益」；收益宣稱低於實作複雜度的刀必須先量測。以下原設計保留供歷史參照。


- **座標**：`ECSEntity.tryGetECSComponent(Ljava/lang/Class;)Lzombie/characters/ecs/ECSComponent;` offset 13 的
  `INVOKESTATIC ECSComponent.getECSClass:(Ljava/lang/Class;)Ljava/lang/Class;`（全介面唯一）
  → 同 descriptor `INVOKESTATIC zombie.mdc.EcsClassCache.getECSClass`
- **形狀註記**：INVOKESTATIC→INVOKESTATIC、無 receiver 搬移、堆疊形狀完全不變——形狀 (a) 的簡化變體，
  維護者已接受；Patcher 需確認支援 static 來源 site（helper desc＝原 desc，不前插 owner）。
- **Helper 語意**：`c==null` → delegate vanilla（保留原 null 語意）；否則 `ClassValue<Class<?>>` 快取，
  computeValue 呼叫 vanilla `ECSComponent.getECSClass(c)`。類階層 runtime 不可變 → 輸出永久 bit-for-bit 相同。
- **預期**：引擎全域每 tick 5–15 萬次 ECS 查找移除 superclass walk，0.5–2%。

### W3-3 動物 spotted 距離預過濾（VehicleIntersectPrefilter 範式復刻）

- **座標**：`IsoAnimal.updateLOS()V` 內兩處
  `INVOKEVIRTUAL BaseAnimalBehavior.spotted:(Lzombie/iso/IsoMovingObject;ZF)V`（offset 242 殭屍分支、
  offset 293 玩家分支；class 內第三處在 `IsoAnimal.spotted` 轉發方法＝bForced=true 路徑，**刻意不動**，
  以 method-scope 鎖定 expectedHits=2）
  → `INVOKESTATIC zombie.characters.animals.behavior.AnimalSpottedPrefilter.spotted(LBaseAnimalBehavior;LIsoMovingObject;ZF)V`
  （helper 置於 behavior 套件取得 protected `parent` 存取權）
- **根因**：updateLOS 對同層所有移動物件（殭屍數千）無水平距離過濾直接呼叫 spotted()；spotted() 所有
  有意義效果都要求近距（acceptance<10、壓力≤10、逃跑≤6、alert<spottingDist≈10）。100 動物 × 1500 物件
  ≈ 15 萬次白繳呼叫/tick。
- **Helper 語意**：
  - `threshold = max(12f, b.parent.adef.spottingDist + 2f)`，取值包 try/catch，異常一律 delegate
  - `bForced==false && dist > threshold`（NaN 比較為 false → 自動 delegate）→ **逐句重放 spotted() 的
    無條件前綴**：`b.parent.spottedChr = null`；`lastAlerted>0` 則減 `GameTime.getMultiplier()` 並 clamp 0
    → skip 計數，return
  - 否則 delegate `b.spotted(other,bForced,dist)`（維持 virtual dispatch）
- **行為安全**（v2 措辭修正）：被 skip 的呼叫在 vanilla 中除前綴外**零玩家可感知效果**（前綴
  逐句重放；接受共享 RNG 流分歧——遠距 wild 分支會消耗全域 Rand，MP 無決定性依賴，該分支的
  getPerkLevel/向量計算正是本刀要省的白繳工作）。時間積分型效果（壓力/馴養）只在近距發生、
  全程 delegate、速率不變——這正是不採「整個 updateLOS 降頻」的原因。與既有 IsoAnimal 三處
  常數手術不同方法，零相撞。try/catch 覆蓋語意：threshold 取值段 catch-and-delegate；前綴
  重放段的例外與 vanilla 同型（等價而非吞掉）。
- **第三 callsite 不動的理由（v1 記載有誤，審查修正）**：IsoAnimal.spotted 轉發方法並非
  「bForced=true 專用」——IsoPlayer.TestAnimalSpotPlayer 以 bForced=false＋Manhattan 距離
  經其進入。不動它的正確理由：外部呼叫者流量可忽略，且 Manhattan ≥ 歐氏使該路徑天然保守。
- **SmokeCheck 四重防護**：(1) 全 jar walk 斷言 BaseAnimalBehavior 後代零 spotted 覆寫
  （去虛擬化前提）；(2) 前綴指紋——GameClient.client 檢查前 putfield 序列恆為
  {spottedChr, lastAlerted×2}、invoke 恆為 {getInstance, getMultiplier}；(3) 常數包絡——
  spotted() 全部 LDC float 值集合凍結於 42.20 快照（30 值，含負值與科學記號），42.21 任何
  漂移即建置失敗強制重新分析；(4) 轉發方法負對照（保持 vanilla）。
- **預期**：攔截率預期 >95%，淨省數 ms 至十餘 ms/tick。

### W3-4 車輛 couldSee 掃描的 server 死工消除（證據最硬）

- **座標**：`BaseVehicle.update()V` offset 2389 的 `INVOKEVIRTUAL couldSeeIntersectedSquare:(I)Z`
  （update()V 內唯一；`render()` 另有同名 callsite **必須不動**——method-scope 鎖定）
  → `INVOKESTATIC zombie.mdc.VehicleCouldSeeGate.couldSeeIntersectedSquare(LBaseVehicle;I)Z`
- **根因**：該布林唯一去處是 `setTargetAlpha(i,0)`——`IsoObject.setTargetAlpha` 開頭
  `if(!GameServer.server)` 才寫入、`getTargetAlpha` 在 server 恆回 1.0F（javap＋源碼雙證）。
  server 上整段 AABB×10–18 次 getGridSquare 掃描是 100% 死工。
- **Helper 語意**：`GameServer.server` → 計數後直接 `return true`（跳過 no-op 的 setTargetAlpha，
  true/false 行為等價取最省）；非 server → 用全 public API（getPoly/getGridSquare/isCouldSee/
  isIntersectingSquare）逐指令複刻原 private 方法當 fallback（部署面只有 server，此路徑實際不可達，
  但 LoadCheck/行為測試要求等價）。
- **預期**：每秒 5 萬–25 萬次 ServerMap.getGridSquare 查找歸零，1–3%，與車數線性。

## 暫緩（GO-WITH-CARE，等本波計數器落地後決策）

- **W3-D1** `ServerLOS.updateLOS` → `IsoPlayer.updateLOS()V`（offset 62，唯一）200ms 節流：
  本叢集最大單點（估 4–15ms/tick），但屬玩家可見性記帳的新鮮度降級，需驗證 sneaking／殭屍 aggro
  移交／admin 隱身三項。vanilla visible[] 網格本就 ≥1s 陳舊，論證成立但先讓四刀落地。
- **W3-D2** `IngameState.UpdateStuff` offset 788 `vehicleNetworkSound server/Manager.update()V` N=2 節流：
  純音效表現層，O(連線×車數) 砍半，部署後需實聽引擎/警報啟停。

## 否決（記錄在案防止重提）

- **LOS_TICK 1s→2s**：主執行緒痛點是 suspend() busy-wait，其 stall 上界＝LOS thread 單玩家整趟
  calcLOS，降頻不縮短上界，白擔陳舊風險。NO-GO。
- **GasTank getContainerContentAmount 快取**：contentAmount 在 Lua modData，mod 可繞過 setter 直寫
  rawset，快取失效不可靠且油量是進 VehicleFullUpdate 的權威同步值。NO-GO。
- **getZombieAuth FNV hash 重入列**：無三形狀切點（需改資料結構）。不可行，W3-1 落地後若
  getZombieData 仍在 dump 出現再議。
- **結構性不可修**：`ServerLOS.suspend` busy-wait（需中途中斷檢查，三形狀無法表達）。列入
  「現行手法無法處理」清單。

## 驗證計畫

1. 每刀計數器週期印 log（AuthThrottle delegated/skipped/anomalies、EcsClassCache calls、
   AnimalSpotted skipped/delegated/anomalies+threshold、VehicleCouldSee serverSkipped/replicated），
   上線首日回收實際攔截率/skip 比。**runbook 鐵則（審查處方）：上線首日四行計數器缺任一行
   ＝該刀未生效，立即單刀回退**；「生效但無收益」用 AnimalSpotted 行印出的 threshold 診斷。
2. SmokeCheck：W3-3 的 behavior 子類零覆寫 jar walk＋前綴指紋＋常數包絡；W3-4 的
   setTargetAlpha/getTargetAlpha server guard 指紋（no-op 論證的建置期鏈結）＋render() 負對照；
   W3-2 的 getECSClass 純度斷言；四刀 callsite 改道×N＋原呼叫歸零斷言。
3. fps-dip-sampler 對照：預期 updateAuth／animal LOS／couldSee 主題幀在後續 dump 消失或大幅下降。
4. install.sh 新增第三方 loose class 巡檢警告（jar walk 前提的現場旁路偵測）。
5. 回退：uninstall.sh 全量回退；單刀回退＝PatchConfig 移除該項重建置。

## 審查修正記錄（v1 → v2）

三稜鏡面板（3 agents × 獨立 context，總計 100 tool calls 對源碼/javap 逐項證偽）判決：
W3-1 GO-WITH-FIXES ×3、W3-2 GO ×3、W3-3 GO-WITH-FIXES ×2/GO ×1、W3-4 GO ×2/GO-WITH-FIXES ×1。

| # | 嚴重度 | 發現 | 處置 |
|---|---|---|---|
| 1 | major×3 | W3-1 wall-clock stagger 與退化 tick 週期共振→dip 期間大半殭屍選舉餓死 | 改 helper 內部 pass 計數器（50ms 間隙偵測），上界確定性 4 pass |
| 2 | minor | W3-1 死亡殭屍收尾（moveZombie isDead 分支）被節流且 2000ms 冷卻論證不適用 | helper 加 isDead() 即刻放行 |
| 3 | minor | W3-1 與 SwitchZombiesOwnershipEachUpdate=true 語意衝突 | helper live 讀選項，true 即全放行 |
| 4 | minor | W3-3 v1 對第三 callsite 的「bForced=true 專用」理由為事實錯誤 | 文件修正（TestAnimalSpotPlayer bForced=false＋Manhattan） |
| 5 | major | W3-3 threshold 與 spotted() 本體無建置期鏈結（42.21 默默漂移） | SmokeCheck 前綴指紋＋常數包絡快照 |
| 6 | major | W3-4 等價性建立在未受守門的 setTargetAlpha/getTargetAlpha guard 上 | SmokeCheck server guard 指紋×2＋render() 負對照 |
| 7 | note | W3-3「零效果」措辭不精確（遠距 RNG 流消耗） | 措辭改「零玩家可感知效果；接受 RNG 分歧」 |
| 8 | note | 計數器無法區分「未生效」與「無流量」 | runbook 鐵則：缺行即單刀回退 |
| 9 | note | SmokeCheck jar walk 不涵蓋現場第三方 loose class | install.sh 巡檢警告 |
| 10 | 證偽 | 負數 onlineId 模運算攻擊、W3-2 interface 載入/ClassValue/<clinit>/null 四項、W3-4 targetAlpha 讀者窮舉——全數不成立 | 無需處置（記錄在案） |
