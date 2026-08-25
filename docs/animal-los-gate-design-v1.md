# W18 動物 LOS 節流閘（AnimalLosGate）- 設計 v1

> 立案證據（2026-08-25 晚峰實測，本 repo 直接立案）：
> - 60 張 jcmd stack（22:45–22:48，66 人）：`IsoAnimal.updateLOS` 單一 leaf **25/60=41.7%**；
>   併前 15 張＝LOS 家族 36/75≈48% 主執行緒。第二梯隊無 >5% 熱點。raw=`temp/w18-samples-raw.txt`。
> - 情境：67 人破歷史峰值 → 主執行緒單核飽和（99.9%R）→ fps 9.8→5.0 → 吞吐型黑邊。
> - 編號：W16/W17=動物持久化守衛（patches.md 2ad/2ae）；本案 **W18**（patches.md 2af）。
> - 審查歷程：v1 草案牆鐘窗口被 grok 判 BLOCKING（gcd 失明）改為幀輪轉；三 lane deep review
>   （claude/codex/grok）再抓出「幀輪轉的承重前提未釘」等 2 blocking＋7 important，本檔為
>   全部修正後版本。

## 0. 一句話

每隻實體動物每 tick 掃 `getCell().getObjectList()` 全表只為找 zombie/player 呼叫
`behavior.spotted()`——~769 動物 × 全表數千項 × 10Hz ≈ 每秒數千萬次迭代、41.7% 主執行緒。
W18 在 caller 側 redirect 加節流閘：observe 量化、enforce 以幀輪轉砍 (N-1)/N 呼叫。
行為代價＝spotted **速率 ×1/N**＋首次偵測延遲 ≤(N-1) tick（預設 N=2：速率減半、延遲
≤1 tick；fps10≈100ms、fps5≈200ms 牆鐘，隨 fps 變動，無固定牆鐘上限）。

## 1. vanilla 機制（42.20.3，javap 對 work/projectzomboid.jar 實測）

### 1.1 掛點 callsite（唯一）

`IsoAnimal.updateInternal()V`（private）内 offset 196-197：

```
196: aload_0
197: invokevirtual #496    // Method updateLOS:()V
```

全 class 恰 1 處（javap grep 確認）。前後語境：`tryThump(null)` 之後、
`vehicle4testCollision` 檢查之前、尾部 `invokespecial IsoPlayer.update()`。
receiver=this（IsoAnimal），棧 1→0。**offset 23 的 `GameClient.client` 短路（雙 return
封死 client 分支）位於 callsite 之前**——client 不執行本路徑（前案七呼叫點表 #1），
此支配關係已由 SmokeCheck 釘死（見 §3）。

### 1.2 updateLOS 本體（IsoAnimal override，public）

```
0-14:   getX/getY/getZ
15-21:  spottedList(Stack).clear()
22-28:  getCell() → IsoCell.getObjectList():Ljava/util/Set; → Set.iterator()
36-299: 迭代迴圈：
        skip IsoPhysicsObject / BaseVehicle / reanimated-grapple IsoZombie
        movingObject==this → spottedList.add(this)
        |Δz|>1 → skip；getCurrentSquare()==null → skip
        IsoZombie → behavior.spotted(zombie, false, dist)
        非動物的 IsoPlayer 且非 invisible/ghost → behavior.spotted(player, false, dist)
```

注意：42.20.3 的 `getObjectList()` 回傳 **`java/util/Set`**（反編譯顯示 ArrayList 為舊語意）。
迴圈唯一輸出＝spotted 通知＋`spottedList={this}`；動物/屍體/載具/物理物件全被丟棄＝白掃。

### 1.3 spottedList 消費端（行為安全性的關鍵）

全反編譯掃描（`grep -r spottedList/getSpottedList`）：
- `IsoPlayer.updateLOS` 尾部 lastSpotted 合併＋numVisibleZombies 統計——**玩家版限定，
  動物版 override 無此段**（javap 確認 IsoAnimal.updateLOS 內零 lastSpotted 引用）。
- `IsoPlayer:1611`（reset 路徑 clear）——寫入者。
- `ScenePanel`（debug 視窗，`IsoPlayer.getInstance()`）——client-only，dedicated server 不跑。

**⇒ 動物的 spottedList 零 server 消費者；skip 時內容保持 `{this}`（動物版每輪 clear+add(this)
恆為此值）零行為差。** Lua 可達性（前案 §2 已記）：`IsoAnimal` 標 `@UsedFromLua`、
`getSpottedList()`/`updateLOS()` 皆 public——mod Lua 讀 spottedList 看到的 `{this}` 與 vanilla
每 tick 重建後相同值（零差）；直呼 `updateLOS()` 落在本體（掛點在 caller 側），未節流但正確。
正式服 77 個 mod 的 Lua 未逐包掃描（residual risk，Java 側已驗清）。

### 1.4 排程結構（幀輪轉的承重前提——review B1 補全）

`updateLOS` 的呼叫鏈：`IsoCell.ProcessObjects → MovingObjectUpdateScheduler.startFrame()
（frameCounter+1）→ bucket.update((int)frameCounter) → IsoAnimal.update → updateInternal`。
關鍵結構（javap 實測）：

- `MovingObjectUpdateSchedulerUpdateBucket.add`：`o.getID() % frameMod` 分**子桶**；
  `update(int)`：`buckets[frameCounter % frameMod]` **每幀只跑一個子桶**——vanilla 自己
  就在用幀輪轉分流物件。
- `UpdateSchedulerSimulationLevel.getFrameMod() = 1 << getUpdateOrderIndex()`：
  FULL=1、HALF=2、QUARTER=4、EIGHTH=8、SIXTEENTH=16。
- `getUpdateSchedulerSimulationLevelForObject` offset 0-16：`!isEnabled || GameServer.server
  ⇒ return FULL`——**dedicated server 上所有物件恆 FULL ⇒ frameMod=1 ⇒ 每 tick 全跑**。

**⇒ 「每隻動物 Δframe 恆 1」是這條前提鏈的結果，不是無條件數學事實。** TIS 若在 server
開 LOD 分級（frameMod>1），動物被 update 的幀集合變成 `frame ≡ getID() (mod frameMod)`，
與 N 有公因數時 gate 的可達殘餘類只剩 `gcd(frameMod,N)` 的倍數 ⇒ 剩餘類永久失明
（frameMod 全是 2 的冪、N=2 ⇒ 半數動物失明）。防護＝雙保險（§2.3、§3）。

### 1.5 相鄰路徑（不受影響）

- 聽覺：`respondToSound()`（updateInternal offset 137）獨立於 LOS——殭屍腳步聲照常觸發反應。
- `ServerLOS`/`LOSThread`：只管玩家（`addPlayer` 僅玩家連線時註冊），格子 raycast 已在
  獨立執行緒；主執行緒玩家側 `IsoPlayer.updateLOS`（3/60=5%）本案不動（消費端複雜，
  獨立語意，收益小一個量級）。
- `AnimalSpottedPrefilter`（既有刀）：砍 spotted() 內部成本；W18 砍迴圈次數。上下游疊加，
  無衝突。W18 enforce 後 prefilter 計數下降屬預期。

## 2. 手術設計

### 2.1 redirect（同形 1:1）

```
IsoAnimal.updateInternal 內：
  invokevirtual IsoAnimal.updateLOS:()V
→ invokestatic  zombie/mdc/AnimalLosGate.updateLOS:(Lzombie/characters/animals/IsoAnimal;)V
```

expectedHits=1。helper 轉呼叫 `animal.updateLOS()`（public，跨套件合法）。

### 2.2 helper 三態（`-Dmdc.animalLosGate`，文字別名比照家族 parseMode 慣例）

| 模式 | 值 | 行為 |
|---|---|---|
| off | `0` 或 `off` | 直通轉呼叫（zero-cost passthrough） |
| enforce | `1` 或 `enforce` | 幀輪轉：命中幀才轉呼叫 |
| observe | `2` 或 `observe`（預設；未知值落回 observe） | 計數＋objectList.size 分布＋耗時採樣，照常轉呼叫 |

kill switch 解析是 `parseMode()` switch 而非數值 clamp——`=off` 必須真的停刀、打錯字
落回 observe（安全預設），不得把 `-1` 之類 clamp 成 off（review I2）。

### 2.3 幀輪轉公式（enforce；grok BLOCKING 修正後 v2 ＋ 三 lane review 補強）

```java
int phase = (System.identityHashCode(animal) * 0x9E3779B9) >>> 16;   // Fibonacci mix
long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();  // vanilla 每 tick +1
boolean run = Math.floorMod((long) phase + frame, N) == 0;            // N 預設 2；long 全程無截斷
```

- 幀源＝vanilla `MovingObjectUpdateScheduler.frameCounter`。**Δframe 恆 1 是條件性事實**
  （§1.4 前提鏈：server ⇒ FULL ⇒ frameMod=1），成立時每隻動物嚴格每 N tick 輪跑一次、
  首次偵測延遲 ≤(N-1) tick、CPU 砍幅恆 (N-1)/N 與 fps 無關。
- **v1 草案否決記錄（grok BLOCKING）**：nanoTime 牆鐘窗口是單點抽樣——tick=k×window 且
  gcd(k,N)>1 時整個剩餘類永久 skip（fps5、N=4 ⇒ 半數動物視覺失明），且恰在本刀要救的
  低 fps 情境發作。幀源在前提鏈成立時 Δframe=1 ⇒ gcd(1,N)=1 恆互質。
- **前提防護雙保險（review B1）**：① 建置期——SmokeCheck 五支結構釘（server⇒FULL 短路、
  `getFrameMod=1<<idx`、`startFrame` 增量 lconst_1/ladd、bucket `getID()%frameMod` 形狀、
  MOUS.update 每幀全桶掃描——最後者堵「隔幀呼叫 bucket 而 frameMod 仍 1」的雙保險共同盲區），
  TIS 動排程結構＝建置紅、重驗 gcd 面；② runtime——`gateApplies()` fail-open：
  `animal.getCurrentSimulationLevel().getFrameMod() != 1` 的動物直接 forward 不 gate
  （計 `lodPassthrough`），寧可失去節流也不失明。
- mix 防 `-XX:hashCode` 切換與低位聚集；同 tick 內幀值恆定（無中幀翻轉）；
  `floorMod(long,int)` 全程 long 累加無 `(int)` 截斷（無 2^31 相位不連續議題）。
- 參數 property：`-Dmdc.animalLosN`（clamp 1..16；1＝等效全跑）。windowMs 參數隨 v1 廢除。
- **N 上調前必須重驗**：§2.3b 的速率代價與 §1.4 的排程結構都要重秤（「數學免疫」不存在，
  防護是釘＋fail-open，不是公式本身）。

### 2.3b 行為代價（誠實語意——速率，非單次延遲）

`spotted()` 是速率型副效應（每次掃描對每個同層 zombie/player 各呼一次），skip 把速率 ×1/N，
受影響消費端（grok 審查補全）：玩家/殭屍近距壓力累積、馴養 `playerAcceptanceList` 累加
（dist<10 的 spotted 分支）、野生警戒播報與偷襲判定的 XP 機會（Tracking/Sneak/Nimble/
Lightfoot）、`attackIfStressed` 起手機率、`lastAlerted` 衰減節奏。首次偵測延遲 ≤(N-1) tick
（fps10=100ms、fps5=200ms @N=2）。另沿 W3-3 已接受的結論承擔**全域 Rand 序列位移**
（skip 掉的 spotted 內含 Rand 抽樣；W3-3 審查定案「MP 無決定性依賴」，ItemWeightMemo
條目則示範了此因子在其他刀上足以否決——N 上調時要重秤這一項）。**故預設 N=2 保守出貨**
（速率減半），線上體感驗證後再考慮 property 上調；`fleeFromChr` 依賴的 `spottedChr` 在
skip 期間保留殘值，逃跑黏性反而更高（grok 已檢查無虞）。

### 2.4 observe 量測

- `calls`／`forwarded`／`skipped`／`lodPassthrough`；
- `objectList.size` 與單次 updateLOS 耗時：共用同一採樣閘（每 64 次 forward 取 1 次，
  `(forwarded & 63)==1`），採樣幀讀一次 `Set.size()`（O(1)）並以 nanoTime 夾住該次委派
  （`losAvgUs`/`losMaxUs`）——**不是每次 forward 都讀**，非採樣幀零額外呼叫；
- heartbeat：每 4096 次呼叫（`calls & 0xFFF`）才讀一次時鐘、60s 節流一行——熱路徑不
  無條件讀 nanoTime，比照 AnimalRelevancyGate/ChunkWriteGuard 的計數器節流慣例；
- `anomalies` 計 helper 簿記自身的 `RuntimeException`（fail-open 後照常轉呼叫）；
  **`LinkageError` 一律外逃＝fail-fast**（新 jar＋舊 loose class 必須炸得可見，比照
  ChunkRequestPacker rethrow 紀律；review B2）。vanilla 委派在 try 外，例外原樣上拋。
  `maybeBeat()` 在簿記完成後執行（log 故障不再讓 forward 被記成 skip）、內部自包
  RuntimeException（log 基礎設施故障不外逃——W8 慣例）。`anomalies>0` 時 beat 行的
  skipped/forwarded 仍可信（簿記先於 beat）。

## 3. SmokeCheck 釘死條

1. vanilla 前提：`updateInternal` 內 `invokevirtual updateLOS:()V` 恰 1（掛點存在）；
2. vanilla 前提：`IsoAnimal.updateLOS` 內 `IsoCell.getObjectList:()Ljava/util/Set;` 恰 1
   （TIS 改回傳型別/改資料來源時撤刀重估）；
3. vanilla 前提：`IsoAnimal.updateLOS` 內零 `lastSpotted` field 引用（動物版無玩家尾段——
   TIS 若把玩家消費邏輯下放到動物版，skip 語意就不再零差，此條紅=撤刀重估）；
4. patched：`updateInternal` 內 `invokevirtual updateLOS` 歸 0、`invokestatic
   AnimalLosGate.updateLOS` 恰 1、真指令數不變、class 差恰 1（對已 patched IsoAnimal 的
   updateLOS 呼叫數與 vanilla jar 比較）；
5. helper 契約：`AnimalLosGate.updateLOS` 內 `invokevirtual IsoAnimal.updateLOS:()V` 恰 2
   （off 直通＋主路徑 try/finally 夾測合一）、`getFrameCounter` 恰 1（幀源存在性）、
   `getCurrentSimulationLevel`/`getFrameMod` 各恰 1（fail-open 存在性）、主方法熱路徑零
   NEW、全 class 零 Rand、**具名 exception handler 只允許 `java/lang/RuntimeException`**
   （finally 的 any-handler 允許——它 rethrow 不吞；LinkageError 必須穿透）；
6. **承重前提釘（review B1）**：`getUpdateSchedulerSimulationLevelForObject` 內
   `GETSTATIC GameServer.server` 恰 1 且 `GETSTATIC FULL` ≥2（server⇒FULL 短路在）、
   `getFrameMod` 真指令恰 5（`1<<idx` 全形狀）、`startFrame` 的
   LCONST_1/LADD 各恰 1（增量仍 +1）、`bucket.add` 的 `getID()` 恰 1＋IREM 恰 1
   （子桶分派形狀）、`MOUS.update()` 每幀全桶掃描形狀（bucket.update 恰 1＋
   simulationLevels/frameCounter GETFIELD 各恰 1——堵「隔幀呼叫 bucket 而 frameMod 仍 1」
   的雙保險共同盲區）。任一紅＝TIS 動了排程結構，重驗 gcd 面再出貨。
   註：這些是存在性/計數/位置錨，不含分支語意（ifeq 反轉抓不到）——該面由 fail-open
   與 client desync 防線分別兜底，釘的角色是「結構變了就逼人重看」；
7. **client 支配釘（review I3）**：`updateInternal` 內 `GETSTATIC GameClient.client` 恰 1
   且真指令序位置在 updateLOS callsite 之前——TIS 把 callsite 移出守衛區時紅
   （server-only enforce 會產生 client desync，2n 受精蛋案教訓）；
8. 完備性回歸釘（前案 §2 七呼叫點表 #2）：`IsoPlayer.updateInternal1` 的 isAnimal 短路
   仍在——`isAnimal()Z` 恰 1＋`invokespecial IsoLivingCharacter.update` 恰 2＋玩家版
   updateLOS 恰 1。TIS 拆分流＝動物流入未節流的玩家版 updateLOS，紅則重估。

## 4. 行為測試（獨立 JVM；三 lane review 修正後 v3）

- 注入：`Unsafe.allocateInstance(TestAnimal.class)`，`TestAnimal extends IsoAnimal` 覆寫
  `updateLOS()` 直接計數（forward 證據）＋覆寫 `getCurrentSimulationLevel()` 供 LOD 注入。
  `IsoWorld.instance` 反射塞空殼；observe 的 size 採樣用 `allocateInstance(IsoCell.class)`
  ＋反射寫 private final `objectList`（IsoCell 是 final 不能 extends）提供可控集合。
- off：每呼叫必轉（losCalls 對帳）＋helper 計數全凍結（以 `off` 文字別名跑，順帶驗 parseMode）。
- observe：每呼叫必轉＋calls/forwarded 對帳＋預設 N==2 自驗＋**size 採樣兩分支**
  （非 null cell：sizeSamples/sizeSum/sizeMin/sizeMax/losSamples 與注入集合精確對帳；
  null cell：安全跳過、零 anomaly）＋**錯誤契約**（review I4）：簿記 RuntimeException
  （null animal 於 sample 幀）⇒ anomalies+1 且仍恰好委派一次；vanilla RuntimeException
  與 Error（sentinel）原樣外逃、不計 anomalies。
- enforce（N=4 主測＋N=2 出貨組態＋clamp 兩端 0→1、999→16，MODE 與 N 皆自驗）：反射寫
  `MovingObjectUpdateScheduler.instance.frameCounter` 驅動，全確定性無 sleep。四軌斷言：
  (1) 逐 (animal, frame) 公式 oracle（mutation 殺公式 mutant 的主力）；(2) 同幀重複呼叫
  結果一致（輔助訊號——mutation 殺因統計以 track 1 為主，不把 track 2 當牆鐘回歸的單獨
  保證）；(3) 4N 幀內每動物恰 1/N forward 幀（輪轉硬保證＝無失明，與公式形狀無關）；
  (4) 相位分散：無任何一幀 forward 全體動物（mix 退化成常數相位時紅）。
  ＋**LOD fail-open**：frameMod>1 的動物恆 forward 計 lodPassthrough、frameMod==1 照常輪轉。
- 另有 `bogus` 未知值組態（parseMode default 分支 ⇒ observe 安全預設方向）、maybeBeat
  內層覆蓋（反射推 calls 至 4096 閘界執行一次 beat 拼接）、相位分散斷言的決定性化
  （identityHashCode 全同殘餘類時顯式跳過——把機率性建置假紅歸零）。
- mutation 驗證（做完即刪）：恆 forward／判定反轉／`+`改`^`／改回牆鐘 nanoTime／拿掉 mix
  五 mutant 被「逐幀公式不符」殺＋第六隻「拿掉 gateApplies fail-open」被 LOD 段
  「N 幀內全 forward」殺——合計 6/6。

## 5. 部署與驗收

1. 與既有 70 class 無掛點交集（IsoAnimal 已有三常數刀＝同 class 不同方法，ClassPatch 合併）；
   install.sh 三閘照常。
2. 先 observe 一晚：`objectList.size` 分布（決定是否需要第二刀「清單替換」）、
   `losAvgUs×forwarded` 對帳 41.7% 採樣佔比（觀測到的全域 1/64 採樣若與動物數對齊可能
   偏向固定子集——以 jcmd 交叉驗證後再做第二刀決策）。
3. 切 enforce（property 改 `1`/`enforce` 重啟；N=2 出貨值）：晚峰 fps 對照（N=2 預期還回
   ~20% 主執行緒，5.0 → 約 6.5——**推估值，以 observe 實測回填**）、行為驗收面（grok 審查
   擴充）＝殭屍咬雞/動物逃跑體感＋**馴養靠近（acceptance 累加速度）＋偷襲 XP 頻率＋高壓
   動物 attackIfStressed 起手**；AnimalSpottedPrefilter 計數下降屬預期；`lodPassthrough`
   應恆 0（非 0＝TIS 已開 LOD，fail-open 生效中、節流面縮小，回頭看 §1.4）。
   體感全過再評估 N 上調（上調前重驗 §2.3b 速率代價＋Rand 位移）。
4. 生效後 patches.md 補 2af、AGENTS.md 摘要、TIS 回報（objectList 全表掃描 per-animal per-tick）。
5. 前案銜接：docs/isoanimal-updatelos-design-v1.md（AnimalLosScan 迴圈殼 bit-exact 優化，
   草案）閘一的「峰值批」已由本案 60 張@66人補齊（41.7%）。兩案同 callsite 互斥——W18
   先行（手術小、收益大）；若 enforce 後 updateLOS 殘餘佔比仍 ≥8%（觀測條件見該檔 §5），
   AnimalLosScan 以「Gate forward 時 delegate 給 Scan」形式疊加，屆時合併設計。

## 6. 明確不做

- 不動 `IsoPlayer.updateLOS`（玩家側 5%，消費端複雜，另案）。
- 不做清單替換（合成 Set view）——等 observe 的 size 組成數據，若殭屍佔大宗則邊際收益低。
- 不做 LOS 掃描多執行緒化（W7/W9 事故家族已證共享狀態跨執行緒＝毀存檔；TIS 自己也只敢
  抽「快照→背景算」型子系統）。
- 不動 `MovingObjectUpdateScheduler` 的排程結構。
