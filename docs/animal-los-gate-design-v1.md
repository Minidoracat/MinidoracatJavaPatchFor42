# W18 動物 LOS 節流閘（AnimalLosGate）- 設計 v1

> 立案證據（2026-08-25 晚峰實測，本 repo 直接立案）：
> - 60 張 jcmd stack（22:45–22:48，66 人）：`IsoAnimal.updateLOS` 單一 leaf **25/60=41.7%**；
>   併前 15 張＝LOS 家族 36/75≈48% 主執行緒。第二梯隊無 >5% 熱點。raw=`temp/w18-samples-raw.txt`。
> - 情境：67 人破歷史峰值 → 主執行緒單核飽和（99.9%R）→ fps 9.8→5.0 → 吞吐型黑邊。
> - 編號：W16/W17=動物持久化守衛（patches.md 2ad/2ae）；本案 **W18**（patches.md 2af）。

## 0. 一句話

每隻實體動物每 tick 掃 `getCell().getObjectList()` 全表只為找 zombie/player 呼叫
`behavior.spotted()`——~769 動物 × 全表數千項 × 10Hz ≈ 每秒數千萬次迭代、41.7% 主執行緒。
W18 在 caller 側 redirect 加節流閘：observe 量化、enforce 以 stagger 輪轉砍 (N-1)/N 呼叫，
行為代價＝spotted 通知延遲硬上限 (N-1)×windowMs（預設 300ms）。

## 1. vanilla 機制（42.20.3，javap 對 work/projectzomboid.jar 實測）

### 1.1 掛點 callsite（唯一）

`IsoAnimal.updateInternal()V`（private）内 offset 196-197：

```
196: aload_0
197: invokevirtual #496    // Method updateLOS:()V
```

全 class 恰 1 處（javap grep 確認）。前後語境：`tryThump(null)` 之後、
`vehicle4testCollision` 檢查之前、尾部 `invokespecial IsoPlayer.update()`。
receiver=this（IsoAnimal），棧 1→0。

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

### 1.4 相鄰路徑（不受影響）

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

### 2.2 helper 三態（`-Dmdc.animalLosGate`）

| 模式 | 值 | 行為 |
|---|---|---|
| off | 0 | 直通轉呼叫（zero-cost passthrough） |
| enforce | 1 | 幀輪轉：命中幀才轉呼叫 |
| observe | 2（預設） | 計數＋objectList.size 分布＋耗時採樣，照常轉呼叫 |

### 2.3 幀輪轉公式（enforce；grok BLOCKING 修正後 v2）

```java
int phase = (System.identityHashCode(animal) * 0x9E3779B9) >>> 16;   // Fibonacci mix
long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();  // vanilla 每 tick +1
boolean run = Math.floorMod(phase + (int) frame, N) == 0;             // N 預設 2
```

- 幀源＝vanilla `MovingObjectUpdateScheduler.frameCounter`（`startFrame()` 每主迴圈 tick +1；
  `updateLOS` 呼叫鏈正是從該 scheduler 的 bucket 出發 ⇒ **同幀恆定、Δframe 恆 1**）。
- **嚴格每 N tick 輪跑一次**；首次偵測延遲 ≤(N-1) tick；CPU 砍幅恆 (N-1)/N，與 fps 無關。
- **v1 草案否決記錄（grok BLOCKING）**：nanoTime 牆鐘窗口是單點抽樣——tick=k×window 且
  gcd(k,N)>1 時整個剩餘類永久 skip（fps5、N=4 ⇒ 半數動物視覺失明），且恰在本刀要救的
  低 fps 情境發作。幀源 Δframe=1 ⇒ gcd(1,N)=1 恆互質，數學上免疫。副帶修正：mix 防
  `-XX:hashCode` 切換與低位聚集；同 tick 內幀值恆定（無中幀翻轉）；`(int)frame` 溢位
  約 13.6 年@10fps，floorMod 吃負數無失明。
- 參數 property：`-Dmdc.animalLosN`（clamp 1..16；1＝等效全跑）。windowMs 參數隨 v1 廢除。

### 2.3b 行為代價（誠實語意——速率，非單次延遲）

`spotted()` 是速率型副效應（每次掃描對每個同層 zombie/player 各呼一次），skip 把速率 ×1/N，
受影響消費端（grok 審查補全）：玩家/殭屍近距壓力累積、馴養 `playerAcceptanceList` 累加
（dist<10 的 spotted 分支）、野生警戒播報與偷襲判定的 XP 機會（Tracking/Sneak/Nimble/
Lightfoot）、`attackIfStressed` 起手機率、`lastAlerted` 衰減節奏。首次偵測延遲 ≤(N-1) tick
（fps10=100ms、fps5=200ms @N=2）。**故預設 N=2 保守出貨**（速率減半），線上體感驗證後
再考慮 property 上調；`fleeFromChr` 依賴的 `spottedChr` 在 skip 期間保留殘值，逃跑黏性
反而更高（grok 已檢查無虞）。

### 2.4 observe 量測

- `calls`／`forwarded`／`skipped`；
- `objectList.size`：min/max/累計 avg（每次 forward 前讀一次，Set.size O(1)）；
- 單次 updateLOS 耗時：每 64 次 forward 以 nanoTime 夾一次（`losAvgUs`/`losMaxUs`）；
- heartbeat：每 60s 節流一行（比照 W13/W14 樣式）；`anomalies` 計 helper 自身
  try/catch（**不包轉呼叫**——vanilla 例外照拋不吞）。

## 3. SmokeCheck 釘死條

1. vanilla 前提：`updateInternal` 內 `invokevirtual updateLOS:()V` 恰 1（掛點存在）；
2. vanilla 前提：`IsoAnimal.updateLOS` 內 `IsoCell.getObjectList:()Ljava/util/Set;` 恰 1
   （TIS 改回傳型別/改資料來源時撤刀重估）；
3. vanilla 前提：`IsoAnimal.updateLOS` 內零 `lastSpotted` field 引用（動物版無玩家尾段——
   TIS 若把玩家消費邏輯下放到動物版，skip 語意就不再零差，此條紅=撤刀重估）；
4. patched：`updateInternal` 內 `invokevirtual updateLOS` 歸 0、`invokestatic AnimalLosGate.updateLOS` 恰 1；
5. patched 全 class 差集恰 1（單 callsite 改道，其他方法不動）；
6. helper 契約：`AnimalLosGate.updateLOS` 內 `invokevirtual IsoAnimal.updateLOS:()V` 恰 3
   （off 直通＋sample 夾測＋一般路徑）、`MovingObjectUpdateScheduler.getFrameCounter:()J`
   恰 1（幀源存在性，TIS 刪計數器時建置失敗）、零 `zombie/core/random/Rand` 引用、主方法
   熱路徑零 NEW（banner/beat 拼接均在獨立方法；計數為主執行緒單寫普通 long）；
7. 完備性回歸釘（grok 審查補；前案 §2 七呼叫點表 #2）：`IsoPlayer.updateInternal1` 的
   isAnimal 短路仍在——`isAnimal()Z` 恰 1＋`invokespecial IsoLivingCharacter.update` 恰 2
   ＋`invokevirtual IsoPlayer.updateLOS` 恰 1。TIS 拆掉分流＝動物流入未節流的玩家版
   updateLOS，此條紅＝W18 只剩半套，重估。

## 4. 行為測試（獨立 JVM；grok 審查修正後 v2）

- 注入：`Unsafe.allocateInstance(TestAnimal.class)`，`TestAnimal extends IsoAnimal` 覆寫
  `updateLOS()` 直接計數（forward 證據；v1 草案的 NPE 訊號已否決——allocateInstance 下第一
  個 NPE 其實是 `spottedList.clear()` 非 `getCell()`，且 DebugLog 未初始化的 NPE 會被誤判）。
  `IsoWorld.instance` 反射塞空殼（currentCell=null）⇒ sample 分支 null-cell 安全跳過。
- off：每呼叫必轉（losCalls 對帳）＋helper 計數全凍結。
- observe：每呼叫必轉＋calls/forwarded 對帳＋sizeSamples==0＋anomalies==0＋預設 N==2 自驗。
- enforce（N=4 顯式）：反射寫 `MovingObjectUpdateScheduler.instance.frameCounter` 驅動，
  全確定性無 sleep。三軌斷言：(1) 逐 (animal, frame) 公式 oracle（測試自算 mix+floorMod，
  mutation 靠此殺公式 mutant）；(2) 同幀重複呼叫結果一致（殺「改回 nanoTime 牆鐘」回歸
  ——牆鐘在固定 frame 下時變）；(3) 4N 幀內每動物恰 N 分之一 forward 幀（輪轉硬保證＝
  無失明）。
- mutation 驗證（做完即刪）：恆 forward／判定反轉／`+` 改 `^` 三 mutant 均被「逐幀公式
  不符」殺。


## 5. 部署與驗收

1. 與既有 70 class 無掛點交集（IsoAnimal 已有三常數刀＝同 class 不同方法，ClassPatch 合併）；
   install.sh 三閘照常。
2. 先 observe 一晚：`objectList.size` 分布（決定是否需要第二刀「清單替換」）、
   `losAvgUs×forwarded` 對帳 41.7% 採樣佔比。
3. 切 enforce（property 改 1 重啟；N=2 出貨值）：晚峰 fps 對照（N=2 預期還回 ~20% 主執行緒，
   5.0 → 約 6.5）、行為驗收面（grok 審查擴充）＝殭屍咬雞/動物逃跑體感＋**馴養靠近
   （acceptance 累加速度）＋偷襲 XP 頻率＋高壓動物 attackIfStressed 起手**；
   AnimalSpottedPrefilter 計數下降屬預期。體感全過再評估 N 上調。
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
