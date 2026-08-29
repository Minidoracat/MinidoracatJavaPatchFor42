# IsoAnimal.updateLOS 迴圈殼優化 — 設計 v1（2026-08-29 observe 落地）

> 狀態：**W18-2 observe 已實作並通過全建置**。原草案 callsite redirect 方案因 W18
> AnimalLosGate 已佔用同一 callsite，依 `docs/animal-los-gate-design-v1.md` §5 銜接條款改為
> 「Gate forward 時 delegate 給 Scan」，不新增 bytecode 手術。峰值證據由 W18 60 張@66人
> 補齊（updateLOS 41.7%）；Gate enforce 後正式服 counter 換算殘餘疑似 >8%，但是否開 on
> 仍以 observe `ms/s`＋高峰 jstack 雙管道互驗為準（§4.6），不得以手算直接出貨。
> 基準已重驗至 42.20.4（jar SHA `80e405a4…442f44`）。

## 0. 摘要

- **閘一（峰值已補、離峰改由 observe 連續量測）**：40 人層 60 份 jstack 命中
  **11/60 = 18.3%（全）／11/45 = 24.4%（RUNNABLE）**；W18 66 人峰值批命中 41.7%。
  Gate enforce 後正式服 counter 換算殘餘疑似 >8%，但只作解封 observe 的量級證據；
  on 前仍須用 observe `ms/s`＋高峰 jstack 重驗殘餘（不能拿 Gate 前 41.7% 當 Gate 後結論）。
- **閘二**：javap 級證據鏈證明 `IsoAnimal.updateLOS` 在 MP 是 **server-only 執行路徑**——
  vanilla 自帶 client 分流（與受精蛋案「client 執行同判定但無守衛」本質不同：client 根本不執行）。
  desync 前提不存在，**維持 server-only patch**，v0 的三選題（連改/設定層/放棄）不觸發。
- **熱點**：11/11 命中樣本 leaf 全在 updateLOS 迴圈殼自身（safepoint 落於迴圈回邊），
  0 份在 spotted/prefilter 內——W3-3 已把 spotted 便宜化，剩餘成本是**每 pair 的迴圈殼白繳**。
- **實作形狀（W18-2）**：`IsoAnimal.updateInternal` 的單 callsite 仍只 redirect 到
  `AnimalLosGate.updateLOS`；Gate forward 路徑改 delegate
  `AnimalLosScan.updateLOS(IsoAnimal)`。Scan observe 純 timing、on 才跑保守裕度平方預篩，
  消除遠距 pair 的 sqrt／tryCastTo×3／prefilter 呼叫成本；邊界帶與近距全額 delegate
  `AnimalSpottedPrefilter`。**與現行（W3-3 後）行為 bit-exact，含 RNG 流。**
- **收益採兩階段量測**（§4.6）：observe 量真實成本，on 獨立 canary A/B；手算 ns 不作依據。

## 1. 閘一：佔比重驗（凍結資料）

### 1.1 方法（可重現）

- 取樣腳本 `temp/ulos-sample.sh`（stdout-only，遠端零寫入）：動態 PID＋唯一性 fail-closed 閘
  ＋cmdline 驗證；60 份/批，間隔 2–6s 隨機（去 tick phase-locking，主迴圈 10Hz）；每份完整
  main thread stack＋`valid` 標記＋時間戳；`pipefail`＋bash 內建比對（避免 SIGPIPE 假失敗）。
- 統計腳本 `temp/ulos-stats.py`（本機、可重跑）：佔比同時給全樣本與 RUNNABLE 兩種分母。
- 原始資料：`temp/jstack-batch1.txt`（935 行，60/60 valid）。
- 教訓（本輪新踩）：**進行中 session 的 log 在 `Logs/` 根層，session 結束才歸檔進
  `logs_YYYY-MM-DD/`**；`find | sort | tail` 字典序會把根層（`2026-…`）排在子目錄（`logs_…`）
  前面而永遠選到舊 session。人數/fps 的 meta 必須取根層檔案（`temp/ulos-meta2.sh`）。
  另 `ps -o etimes` 在該容器回傳異常值（4.1e9s），JVM 啟動時刻改以 jstack `elapsed=` 反推。
  **sampler 已修正**（補批用同一支）：meta 改鎖 `Logs/` 根層 active 檔＋取樣起訖各印一次
  人數/fps 原始行（START/END 雙 meta），batch1 的 meta 欄位為舊格式、其人數 40/fps 9.73 以
  `temp/ulos-meta2.sh` 事後修正為準（batch1 檔內的 18 人/9.46 是已歸檔舊 session 殘值，勿引用）。

### 1.2 batch1 結果（40 人層，週日晚 18:56–19:01 +08:00）

| 項 | 值 |
|---|---|
| 取樣窗 | 2026-08-17T18:56:50 – 19:00:58（+08:00），249s |
| session | 18:12:32 啟動（jstack elapsed 2657.8s 反推），patch 指紋 `5594e0e+dirty built=00:16 jar=09a80a46` |
| 在線/fps | estimated_online=40（根層 connections 檔）；fps 9.73（f=28915..29207/30s，原始行見 meta2 輸出） |
| 樣本 | 60/60 valid；45 RUNNABLE＋15 TIMED_WAITING（15/15 皆 `GameServer.main:959` 補償式節流 sleep——javap offset 3354–3394：`clamp((5ms−elapsed)/1ms, 0, 100)`，`UpdateLimit.Check()` 為 true 時不睡；標籤有 bytecode 證據，非印象） |
| **updateLOS 命中** | **11/60 = 18.3%（全）；11/45 = 24.4%（RUNNABLE）** |
| 命中樣本 | #4,9,15,19,34,36,44,45,46,51,57（時間平均分佈，非單一 burst） |
| 命中 leaf | 全部 = updateLOS 自身：`IsoAnimal.java:4013`×8、`:3974`×3 |
| 主題第 2/3 名 | UsingPlayerUpdateSystem.update 10.0%；IsoPlayer.updateLOS（ServerLOS 路徑）6.7% |

呼叫鏈（11/11 一致）：`GameServer.main → IngameState → IsoWorld → IsoCell.ProcessObjects →
MovingObjectUpdateScheduler → IsoAnimal.update:452 → updateInternal:533 → updateLOS`。

### 1.3 判定與待補

- 40 人層 18.3% ≫ 5%；**合併下界**：離峰批 60 份即使 0 命中，11/120 = 9.2% > 5%。
  「掉到 5% 以下就重排序」在數學上已不可能，閘一放行閘二（使用者裁定：先做閘二，兩批照排補測）。
- **待補**（設計定稿前必須齊，同腳本同統計）：
  - 峰值批：60+ 人時段（週末晚 21:00–23:00），驗證高負載下佔比與熱點結構不變。
  - 離峰批：≤15 人時段（清晨），供收益模型的全時段加權。
- 取樣對主迴圈的影響：jstack 每次觸發 safepoint（ms 級停頓）×60 次/249s，對 10fps 主迴圈
  可忽略；與先前 46 份（remote-check-10/11，2026-08-16）做法一致。

## 2. 閘二：client/server 守衛檢查（javap 級證據鏈）

**結論：`IsoAnimal.updateLOS` 在 MP 語境是 server-only 執行路徑。** 全 jar 反編譯掃描
`\.updateLOS\(` 得 7 個呼叫點，逐一排除：

| # | 呼叫點 | 證據 | 判定 |
|---|---|---|---|
| 1 | `IsoAnimal.updateInternal`（唯一直呼，原始行 533） | javap：offset 23 `getstatic GameClient.client` + `ifeq 86`；client 分支 offset 29–85 **雙 return（80/85）封死**；`invokevirtual #496 updateLOS` 在 offset 197（else 側） | client 不可達 ✔ |
| 2 | `IsoPlayer.updateInternal1` offset 67 虛呼叫（`if (!remote) this.updateLOS()`） | javap：offset 1 `isAnimal()` + `ifeq 50`——動物走 offset 7–49 專屬分支（`invokespecial IsoLivingCharacter.update` 直接跳過 IsoPlayer 層），**offset 49 return 唯一出口**；client 動物在 offset 29 被強制 `remote=true` | 動物不可達 ✔ |
| 3 | `IsoPlayer.updateRemotePlayer` → `ServerLOS.instance.updateLOS(this)` | javap offset 25740：`getstatic GameServer.server; ifeq 272`——client 端不進；server 端動物不呼叫 updateRemotePlayer（#2 的分流） | 雙向封死 ✔ |
| 4 | `ServerLOS.java:108 player.updateLOS()` | 佇列成員只來自 #3（server 端 remote 真玩家）；jstack 5/5 樣本鏈皆 `updateRemotePlayer:7362 → ServerLOS.updateLOS:390` 真玩家 | 非動物 ✔ |
| 5 | `IsoZombie.java:3098 player.updateLOS()` | 外層 `if (!GameServer.server)`（client/SP 專用）；對象是殭屍掛載的 reanimated 玩家實體，非動物 | server 不執行 ✔ |
| 6/7 | `CloseWindowState:65` / `OpenWindowState:77` | `(IsoPlayer)owner` 玩家開關窗 state（`pressedMovement` 玩家輸入 API），動物不進此 state | 非動物 ✔ |

與受精蛋案（docs/patches.md 2n）的本質差異：該案是「client 執行同一判定但無守衛」；本案
client **根本不執行**（vanilla `isAnimal()`＋`GameClient.client` 雙層分流）。client 所見動物
行為完全由 server 同步驅動——server 側行為變化會一致呈現，與 IsoAnimal 現有三把常數刀同一
安全類別。**v0 三選題不觸發，維持 server-only patch。**

Lua 可達性：`IsoAnimal` 標 `@UsedFromLua` 且 `updateLOS` 為 public——mod Lua 理論可直呼。
此路徑不經 v1 redirect（打在 caller 端），落在 patched `updateLOS` 本體上，行為＝W3-3 現行
（prefilter 全額生效），正確但無加速。**故 W3-3 兩處 callsite patch 保留不拆**（防禦深度）。

## 3. 熱點結構（為什麼是迴圈殼）

- jstack 命中 leaf 行號 `3974`/`4013` 經 LineNumberTable 對應 **offset 77 / 296——皆為
  `goto 36` 迴圈回邊**。JIT 後迴圈只在 backedge safepoint 可停，證明主執行緒在 for 迴圈
  本體打轉；0/11 落在 spotted/prefilter 內＝W3-3 後 spotted 成本已趨零。
- 每 pair 迴圈殼成本（javap offset 36–296）：iterator `hasNext/next`＋checkcast＋
  `instanceof`×3＋grapple 虛呼（殭屍 pair）＋`getX/getY/getZ`×3 虛呼＋`this.getZ()` 虛呼＋
  `PZMath.abs`＋**`IsoUtils.DistanceTo`（含 sqrt）**＋`getCurrentSquare` 虛呼＋
  **`Type.tryCastTo`×3（其中 `movingPlayer`＝死存儲：offset 213 `astore 12` 後 slot 12 無讀取）**＋
  spotted 呼叫（→prefilter：threshold try/catch＋比較＋計數器＋`maybeLog`）。
- 量級：per-animal 全掃 `IsoCell.objectList`（Set，殭屍為大宗）。W3-3 spec 記載
  100 動物 × 1500 物件 ≈ 15 萬 pair/tick；prefilter 攔截率 99.94–99.98% ⇒ 幾乎全部 pair
  只為執行 3 個 float op 的前綴而付整份迴圈殼。

## 4. v1 手術設計

### 4.1 形狀（W18 合併後定稿）

- `IsoAnimal.updateInternal()V` offset 197 的唯一 `updateLOS()` callsite 已由 W18 redirect 至
  `AnimalLosGate.updateLOS(IsoAnimal)`（1→1、3 bytes 不變），**本刀不再新增 Patcher 手術**。
- Gate 的 off 路徑仍直接 `animal.updateLOS()`；forward 路徑改
  `AnimalLosScan.updateLOS(animal)`。Scan off/observe/on 三態在 helper 內分流；因此 Gate 與
  Scan kill switch 可獨立回退，且不會出現同 class 第二個 ClassPatch／同 callsite 雙改道。
- `IsoAnimal.updateLOS` 方法本體不動（Lua/mod 直呼路徑照舊，未節流/未 Scan 但正確）。

### 4.2 helper 規格（`patcher/game/zombie/mdc/AnimalLosScan.java`）

存取需求已全數驗證 public：`getSpottedList()`（IsoPlayer public getter；field 本體 protected）、
`spottedChr`／`adef`（IsoAnimal public field）、`adef.spottingDist`（public int）、
`getBehavior()`／`b.lastAlerted`／`b.spotted`（public）、`getCell().getObjectList()`（public）。
helper 置於 `zombie/mdc/`，**不需**進 behavior 套件。

```
static void updateLOS(IsoAnimal a):
  // 三態 -Dmdc.animalLosScan=observe|on|off（需重啟生效；預設 observe 首發）
  if (MODE == OFF) { a.updateLOS(); return; }              // 直通 vanilla，零副作用點
  if (MODE == OBSERVE) {                                   // timing wrapper（2i 先例同型）：量測不改行為
    sz = sizeOrMinus1(a);                                  //   Set.size()，try 包裹，異常記 -1
    t0 = System.nanoTime(); a.updateLOS();
    elapsedNs += System.nanoTime() - t0; calls++; sumObjects += sz; maybeLog(); return;
  }                                                        // wrapper 自身 ~40ns ≪ 單次掃描 µs 級
  // —— ON 態：與 observe 對稱計時（A/B 分子；同組 calls/elapsedNs/sumObjects，log 印 mode）——
  szOn = sizeOrMinus1(a); t0 = System.nanoTime()
  // —— 前置取值段：任何異常/null → fallback vanilla（此時零可觀測寫入；fallback 不計 elapsed）——
  b = a.getBehavior(); list = a.getCell().getObjectList(); spotted = a.getSpottedList();
  b/list/spotted null 或前置取值異常 → fallbacks++; a.updateLOS(); return;
  // —— 掃描段：無 fallback，例外原樣上拋（與 vanilla 同型；照 prefilter「delegate 在 try 外」紀律）——
  ax = a.getX(); ay = a.getY();
  spotted.clear();                                          // 第一個可觀測寫入（vanilla 同位置）
  for (IsoMovingObject o : list):                           // 同一 Set、同 iterator 語意
    if (o instanceof IsoPhysicsObject) continue;            // vanilla offset 60
    if (o instanceof BaseVehicle)      continue;            // vanilla offset 71
    if (o instanceof IsoZombie z && z.isReanimatedForGrappleOnly()) continue;  // offset 82–100
    if (o == a) { spotted.add(o); continue; }               // offset 106–122
    ox = o.getX(); oy = o.getY(); oz = o.getZ();            // offset 125–144，同序虛呼叫
    if (PZMath.abs(oz - a.getZ()) > 1.0F) continue;         // offset 146–158，per-pair this.getZ() 照抄
    dx = ox - ax; dy = oy - ay; d2 = dx*dx + dy*dy;
    sq = o.getCurrentSquare(); if (sq == null) continue;    // offset 175–187（次序前移見 4.3-C）
    isZ = (o instanceof IsoZombie);
    isP = !isZ && (o instanceof IsoPlayer) && !(o instanceof IsoAnimal);   // 見 4.3-D
    if (!isZ && !isP) continue;                             // 非殭屍非玩家：vanilla 無任何效果
    // W3-3 must-keep：每個候選 pair live 讀；前一 pair 的 behavior/mod 可改 spottingDist。
    try:
      t = AnimalSpottedPrefilter.thresholdOf(a.adef.spottingDist)
      g = t + 0.25F; g2 = g*g
      fastEnabled = t <= 65536.0F && g > t && g2 > 0
    catch RuntimeException: fastEnabled=false               // 本 pair 全額 delegate，不沿用舊 gate
    if (fastEnabled && d2 > g2):                            // —— fast skip：毫無爭議的遠距 ——
      if (isP && (((IsoGameCharacter)o).isInvisible() || ((IsoPlayer)o).isGhostMode())) continue;
      a.spottedChr = null;                                  // prefilter skip 路徑逐句重放
      if (b.lastAlerted > 0.0F) b.lastAlerted -= GameTime.getInstance().getMultiplier();
      if (b.lastAlerted < 0.0F) b.lastAlerted = 0.0F;
      fastSkipped++;
    else:                                                   // —— 邊界帶＋近距：全額 delegate ——
      dist = IsoUtils.DistanceTo(ox, oy, ax, ay);           // 參數順序照 vanilla
      if (isZ) { AnimalSpottedPrefilter.spotted(...); delegated++; }
      else if (visible player) { AnimalSpottedPrefilter.spotted(...); delegated++; }
  animalsScanned++; elapsedNs += System.nanoTime() - t0; calls++; sumObjects += szOn; maybeLog();
```

### 4.3 等價性證明義務（逐條，對抗審查用）

- **(A) 保守裕度預篩零 false-skip＋live threshold**：每個 zombie/player 候選 pair 都重新讀
  `a.adef.spottingDist`（W3-3 must-keep；前一 pair 可由 mod behavior 動態改值），讀取/計算
  RuntimeException 時該 pair `fastEnabled=false` 全額 delegate，絕不沿用舊 gate。fast skip
  觸發 ⟹ `fastEnabled ∧ d2 > g2`。安全域內（`t ≤ 65536 = 2^16`）ULP(t) ≤ 2⁻⁷ ⟹
  0.25 裕度 ≥ 32 ULP，而 `dx²+dy²` 三次舍入＋IEEE sqrt 正確舍入＋`g*g` 一次舍入的合計
  誤差 ≤ ~3 ULP ⟹ `d2 > g2 ⟹ DistanceTo dist > t` 嚴格成立 ⟹ prefilter 必走 skip。
  fast skip 前綴逐句相同（`GameTime.getInstance().getMultiplier()` 每次即呼）。threshold
  邊界 1 ULP 內落在邊界帶全額 delegate；域外或 `g==t` 亦退化全額 delegate。
- **(B) 邊界帶/近距 bit-exact**：delegate 直接呼叫 `AnimalSpottedPrefilter.spotted`（W3-3
  現行）＝patched updateLOS 的兩個分支原樣，含 RNG 流、`lastThreshold` 側錄、skipped/delegated
  計數（僅計數歸屬變化，見 4.5）。
- **(C) 判定次序重排的合法域**：僅在「無效果 skip」判定之間重排（square-null 前移到分類前、
  d² 前移到 cast 前）；所有**有效果**動作（spotted.add、前綴、delegate）之前，該 pair 的全部
  vanilla 排除條件均已等價完成。逐 pair 可觀測效果（spottedChr/lastAlerted/spottedList/RNG）
  的觸發集合與觸發順序不變。square-null 與 z-cull 的 pair 在 vanilla 同樣零效果（offset
  161/187 直接 goto 36，不觸 spotted）。
- **(D) 型別分派等價**：`Type.tryCastTo(o, C.class)` ≡ `o instanceof C ? o : null`（null 安全）；
  vanilla `movingPlayer`（offset 205–213）為死存儲，消除無可觀測差異。玩家分支條件
  `!(mc instanceof IsoAnimal) && mc instanceof IsoPlayer`——動物 extends IsoPlayer，故
  動物-動物 pair 在 vanilla 被第一條擋下（零效果），helper 的 `isP` 定義等價。殭屍分支與
  玩家分支互斥（IsoZombie extends IsoGameCharacter，非 IsoPlayer）。
- **(E) isInvisible/isGhostMode 呼叫語意**：fast skip 路徑對玩家 pair 保留兩個虛呼叫的
  **判定效果**（invisible/ghost 玩家在 vanilla 玩家分支不觸 spotted ⇒ 無前綴），呼叫次數
  相對 vanilla 不變（vanilla 對每個玩家 pair 也呼叫）；若二者有副作用（未見證據，純 getter
  形狀）亦等價。
- **(F) fail-open 邊界**：fallback（`a.updateLOS()`）僅存在於前置取值段（`spotted.clear()`
  之前，零副作用）；掃描段例外原樣上拋，與 vanilla 同型（NPE/CME 等），**絕無 double-scan**。
  kill switch `-Dmdc.animalLosScan=0` 同一零副作用點。
- **(G) 迭代器語意**：同一 `Set` 實例、同 for-each iterator；掃描中 behavior 反應若併發修改
  objectList，CME 行為與 vanilla 相同。

### 4.4 守門（build 期）

- **無新增 Patcher hit**：沿用 W18 的 updateInternal redirect；SmokeCheck 改釘 Gate→Scan
  靜態委派恰 1、Gate off 的 vanilla 直通恰 1。
- SmokeCheck 新增：
  1. **updateLOS 本體語境指紋**：`getObjectList`×1、`DistanceTo`×1、`Type.tryCastTo`×3、
     W3-3 prefilter×2；TIS 改寫即建置失敗，強制重驗 helper 等價性。
  2. **caller census**：全 jar `IsoAnimal.updateLOS()V` owner 的 caller 恰一處
     `IsoAnimal.updateInternal`（多型 `IsoPlayer.updateLOS` owner 不誤傷）；出現第二 caller 即失敗。
  3. **prefilter 契約指紋**：Scan 對 public `AnimalSpottedPrefilter.thresholdOf(I)F` 恰 1，
     兩處 delegate 精確簽名恰 2。
  4. **updateInternal 分支不變式**：W18 既有 GameClient.client 支配釘與
     IsoPlayer.updateInternal1 `isAnimal` 完備性釘沿用。

### 4.5 觀測與 kill switch

- 計數器：observe 態 `calls`／`elapsedNs`／`sumObjects`（objectList 大小分佈）；on 態
  `animalsScanned`／`fastSkipped`／`delegated`／`fallbacks`（anomaly 級，預期恆 0）；
  週期 log 與既有格式一致（`[MinidoracatJavaPatch][AnimalLosScan] …`），時長/速率均印起訖可驗算。
- **觀測連續性警告**：v1 上線後 `AnimalSpotted`（W3-3）週期 log 的 `skipped` 增速會驟降
  （遠距 pair 改由 fastSkipped 計）——巡檢 SOP 需記入，避免誤判 W3-3 失效。
  `delegated` 兩邊可交叉對帳（AnimalLosScan.delegated ≈ AnimalSpotted 的新增 delegated＋
  邊界帶 skipped）。
- 三態 `-Dmdc.animalLosScan=0|off/1|on|enforce/2|observe`（需重啟生效）：off 直通
  vanilla patched 路徑；**observe 預設首發**（純 timing wrapper）；on 開 fast path。

### 4.6 收益模型（誠實邊界：定量承諾撤下，改兩階段量測）

**已量測**：佔比 18.3%（40 人層 jstack 直接量測——不同於記憶化案「命中率×速率×單次成本」
外推，速率×成本已內含於佔比）。**未量測**：加速比。§3 的殼成本手算（sqrt/tryCastTo/
prefilter 呼叫 ~ns 級拆項）**只是機理推測，不是量測**——C2 inline 後 prefilter 呼叫鏈成本
可能趨零、`movingPlayer` 死存儲大概率已被 DCE 消除，手算高估省項的風險真實存在，
**不得作為部署依據**（ItemWeightMemo 教訓的加速比版）。

**兩階段量測**：
1. **observe 首發**：timing wrapper 量 vanilla 真實單次成本與呼叫速率——`calls/s`、
   `elapsedNs/call`、`sumObjects/calls`（平均 objectList 大小）。得到
   「updateLOS 總 ms/s」與 jstack 佔比交叉對帳（兩管道互驗）。
2. **on＝獨立 canary**（離峰單獨開、不與其他變更同批——ChargeFreq canary 同紀律）：
   ON 路徑與 observe 對稱計時（§4.2），加速比 ＝ observe 基線 elapsedNs/call ÷ on 實測
   elapsedNs/call（以 sumObjects/calls 校正兩窗的 objectList 規模差）；絕對收益 ＝ 佔比 ×
   (1 − 1/實測加速比)。同法 jstack ≥60 份對照佔比變化。
   **開 on 的前提（observe 可執行判準）**：observe 的總量 `calls/s × elapsedNs/call` 換算後
   與 jstack 佔比對帳一致（管道互驗成立），且量級（ms/s）確認值得一次 canary——observe
   量不出 sqrt/tryCastTo/prefilter 的拆項比例，**「省多少」只能由 on canary 實測回答**；
   若 canary 實測加速比 ≤1.1×（C2 已把殼壓到理論下限），撤 on 回 observe，v1 止步，
   直接評估 v2。

**fps 換算不成立，不承諾 fps 數字**：主迴圈 sleep 是補償式節流（§1.2，javap 證據），省下的
工作大部分轉為 sleep；fps 增益僅來自「原本超過 target 的 tick 縮短」，受 10fps cap 封頂
（基準 9.73 ⟹ 數學上限 +0.27），真實效果＝低谷變淺、對負載尖峰的 headroom 變厚。
fps 與 too-busy 頻率為輔助觀測。峰值/離峰批補齊後更新全時段加權佔比。

## 5. v2 候選（封存，不隨 v1 實作）

砍 pair 類（空間分桶共享掃描／近域 square 清單）收益上限 10×+，但有三個未解的等價性障礙：
(a) `spottedChr` 交錯清除語意——最終值取決於「最後一個觸碰它的 pair」，砍 pair 改變終值的
暴露面真實存在；(b) `lastAlerted` 衰減次數補償迴圈可 bit-exact（`max(0,·)` 逐次語意），但
與 (a) 的順序耦合；(c) per-square 清單與 objectList 的成員資格一致性未經鑑識。
**解封條件**：v1 上線後 updateLOS 殘餘佔比仍 ≥8%（同法 jstack ≥60 份），且 (a)(c) 有 javap
級答案。屆時先讀 `BaseAnimalBehavior.spotted` 全文釘死 spottedChr 的全部寫點。

## 6. 風險與回退

- 回退：uninstall.sh 即回 vanilla；kill switch 不需重部署。遊戲更新鐵則照舊（**更新前先
  uninstall**；updateLOS/updateInternal 語境指紋在 SmokeCheck 失配即擋建置）。
- 已知非目標：不動 `IsoPlayer.updateLOS`（ServerLOS 路徑，6.7%，W3-D1 另案）；不動
  updateLOS 演算法語意（v2 範疇）；client 側零改動。
- 部署位置：僅 server manifest（`PatchConfig.all()`）；與 client 2 刀無交集。

## 7. 現況與下一閘

1. **已完成**：42.20.4 updateLOS 本體逐句重驗；`AnimalLosScan` observe/on/off helper；
   Gate forward delegate；SmokeCheck 語境指紋/caller census/helper 契約；三模式行為測試；
   build 10/10、80 class linkage/JVMS dataflow 全過。
2. **上線先 observe**：取得 `calls/s`、`elapsedNs/call`、`sumObjects/calls`，換算總 ms/s，
   與高峰 jstack ≥60 份的 updateLOS 佔比交叉對帳。
3. **on＝獨立 canary**：不得與其他行為變更同批；以 objectList 平均大小校正 A/B，若實測
   加速比 ≤1.1× 即撤回 observe。on 生效後另驗 AnimalSpotted 計數歸屬變化與 anomalies=0。
