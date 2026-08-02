# 優化項目與原理詳解（42.20.0）

> 本文檔是給維運者看的完整說明：每一項優化「為什麼做、動了什麼、為什麼安全、怎麼驗證」。
> bytecode 層的逐項原始證據（javap 反組譯摘錄）在 [specs/](specs/) JSON。

## 0. 總機制：為什麼「裝了就生效」

PZ 伺服器啟動 classpath 是 `java/.` 排在 `java/projectzomboid.jar` 之前——JVM 找 class
先找散檔（loose class）再找 jar。所以把修改過的 `.class` 按原路徑放進 `serverfiles/java/`，
**下次重啟就以我們的版本取代原版**，不動 jar 本體；刪掉散檔即完全回退。

修改不是重編譯反編譯原始碼（那條路充滿反編譯器假象），而是**bytecode 手術**：
用 ASM 讀 jar 內的原版 class，只做兩種「堆疊形狀與指令長度都不變」的修改，
原 class 的 StackMapFrames／max stack 原樣保留，JVM 驗證器看到的結構與原版同構：

1. **log 呼叫改道（redirect）**：把 `INVOKEVIRTUAL DebugType.warn(...)` 這類指令
   原地換成 `INVOKESTATIC zombie/mdc/LogFilter.warnFmt(...)`（receiver 變第一參數，
   淨堆疊效果相同）。過濾邏輯在 `LogFilter.java`——普通 Java 類、javac 對遊戲 jar
   編譯、隨 patch 一起出貨。**只攔已知噪音（完整字面值 equals／前綴 startsWith），
   其餘一律轉發原始呼叫——寧漏不誤**。
2. **方法內常數替換（const-change）**：只在指定方法內把某個 `LDC`/`BIPUSH` 常數
   換值（新常數進新的常數池條目，其他方法共用的原條目不動）。

### 防呆體系（每一層都實測過）

| 防線 | 擋什麼 |
|---|---|
| 逐方法命中數守門 | PZ 更新後呼叫點增減／方法改名→建置直接失敗，不會產出錯位 patch |
| LoadCheck 連結驗證＋helper 簽名斷言 | 改道目標方法缺失／簽名不符 |
| CheckClassAdapter JVMS 資料流驗證 | 任何堆疊／frame 層面的錯誤（JVM verifier 等級，離線先跑） |
| install.sh 三道閘 | 產物損壞（payload SHA）、遊戲已更新（jar 原版 SHA 不同源即拒裝）、檔案衝突 |
| uninstall.sh | 依 manifest 精確移除，一鍵回退 |

---

## 1. 抑噪類（7 項）——為什麼值得做

正式伺服器 78 張地圖＋多人環境下，console.txt 每分鐘被數十到數百行無意義警告刷屏：
(a) 真正的錯誤被噪音淹沒（EchoCreek、OOM 事件的診斷都因此變難）；(b) log I/O 與
檔案膨脹是實際開銷；(c) DebugLog 寫檔在高頻呼叫路徑上有同步成本。

| # | 位置 | 攔掉的訊息 | 觸發原因 | 保留了什麼 |
|---|---|---|---|---|
| 1 | AnimationSet.GetState | `AnimState not found: X` | MOD 動畫集缺 state，引擎本就回傳空 fallback | fallback 行為不變；Load 路徑 log 不動 |
| 2 | SkinningBoneHierarchy.buildBoneHierarchy | `SkeletonBone not resolved for bone: X` | MOD 模型骨架非標準骨名，開機刷屏 | 骨架建構結果完全不變 |
| 3 | SpriteConfig.initObjectInfo | `Invalid SpriteConfig object!` **僅九個已知名**（MetalBigWireFence／WoodFloorLvl3／Wooden_Windows；42.20 起加 DoubleWireGate／BrickWallLvl2／MetalSmallWireFence／BrickWindowFrameLvl2／Piano／WoodenWallLvl3，完整訊息 equals） | 特定物件載入必刷（42.20 六個新名為正式服 5.5h 實測 1183 筆的最大宗噪音） | **其他名稱（含 null）照常警告**；resetObjectInfo 清理照跑 |
| 4 | ItemPickInfo.GetPickInfo | 前綴 `ItemPickInfo -> cannot get ID for `（container/room/tile/zone 四變體） | MOD 地圖自訂容器/房間未註冊 ItemConfigurator，每次 loot roll 觸發且**不受 debug 閘控** | 4 條 debug 模式診斷訊息前綴不同、照常輸出；loot fallback 行為不變 |
| 5 | NetworkZombieManager.moveZombie | `moveZombie: There are no zombies in nz.zombies.`（完整字串 equals） | 殭屍擁有權轉移競態，MP 常態 | 擁有權轉移邏輯照舊 |
| 6 | PacketsCache.\<init\> | 前綴 `No packet handler for type:` | vanilla 本就有多個 PacketType 走內建 switch 而非 handler class，**每個玩家連線必刷一長串** | printException（真錯誤）與 `Packets limit has exceeded`（真限流）不動 |
| 7 | INetworkPacket.logInconsistentPacket | format 常數 `The packet %s is not consistent: %s`（equals） | 載具類封包 desync 常態訊息 | **`sync` 自我修復照跑（重要）**；反作弊 `The packet %s is not valid` 留在 `onServerPacket`，**完全不經我方程式碼** |

> **42.20 變更**：`ActionStateContainer.tryInsertChildState` 的抑噪已移除——TIS 自己把那兩個
> `DebugType.warn` 降級為 `trace`（全 class warn 8→6、trace 1→3），噪音源由官方修掉。
> 第 7 項的 consistency log 也從 `PacketTypes$PacketType.onServerPacket` 搬到
> `INetworkPacket.logInconsistentPacket`（interface default method，訊息文字未變），改道目標隨之搬家；
> `PlayerHitZombiePacket` 的 override 只多一層前置過濾，最後仍呼叫 super，兩條路徑都涵蓋。

代價（誠實揭露）：這些訊息從 log 消失。若日後要診斷「正是這些訊息描述的問題」，
先 `uninstall.sh` 還原再觀察。每份 spec 的 `verification` 段都寫了正反向驗證法。

---

## 2. 行為類（1 項）——動了什麼、為什麼是安全的

### 2a.（42.20 移除，重新分析後決定不恢復）殭屍超額回收加速 `10 → 6`

TIS 在 42.20 重寫了整個 class。重新分析結論：**這個手術在新模型下已與原始目標脫鉤，不恢復。**

| 面向 | 42.19 | 42.20 |
|---|---|---|
| 進入點 | `startCount()` ＋ 主 loop 逐隻呼叫 `incrementZombie(IsoZombie)` | 併成 `prepareZombiesForDeletion()`，自行迴圈（`MovingObjectUpdateScheduler.startFrame`，仍是每 frame 一次） |
| 掃描來源 | `IsoWorld cell.getObjectList()`——**全世界已載入殭屍** | 各連線的 `zombiesToSend` |
| 進入 `zombiesToSend` 的條件 | — | `getOwner() != null && getOwner() != connection` ＋ `connection.RelevantTo(...)`；母集合是 `zombiesReceived`（**client 回報的殭屍**） |
| 觸發門檻 | 全域殭屍總數 vs `zombiesCountBeforeDeletion` | **per-connection** `zombiesToSend.size()` vs 同一個設定值 |
| 每 frame 刪除量 | 無上限 | 每連線最多 `size - threshold`（`zombiesCountForDelete--`） |
| 視野保護 | 遍歷所有玩家 `GameServer.IDToPlayerMap` | 只遍歷 `connection.players[]`（該連線 ≤4 人） |
| 安全距離 | `(range-2)*10/2` | `(range-2)*10`（**半徑 ×2＝更不容易刪**） |

**為什麼不恢復**：`zombiesToSend` 只收「有主且主人不是本連線」的殭屍，**無主殭屍永遠不進這個列表**，
因此 42.20 的 culling 完全碰不到它們。而遠離所有玩家、堆在世界各處的無主殭屍正是 78 張圖大世界的
記憶體壓力來源，也是當初做這個手術的理由。在新模型下把取樣從 1/3 調到 1/2，只會加快刪除
「有主、在某連線 relevant 區內、但歐氏距離超出保護半徑」的一小撮殭屍——保護半徑還放大了 2 倍、
又多了 per-connection 額度上限。要處理殭屍堆積得換切入點，不是這個常數。

**常數語意本身沒變**（供將來參考）：`RandInterface.AdjustForFramerate` 伺服器端是
`(int)(chance * 0.33333334f)`——`10`→`(int)3.333`=3→`Rand.Next(3)==0`＝1/3；`6`→2→1/2；
`5`→1→`Rand.Next(1)` 恆為 0＝**100% 全刪**。所以 6 仍會是最保守的一階加速，若日後決定恢復。

**附帶風險（原版行為，非我方引入）**：`canBeDeletedUnnoticed` 只檢查該連線的玩家，不檢查其他
連線的玩家，理論上 B 玩家可能目擊 A 連線判定「無人看見」的殭屍消失。不加速就不會放大它。

> 證據等級：作用域與條件為反編譯語意判讀（`NetworkZombiePacker`、`UdpConnection.RelevantTo`），
> 常數換算為 `AdjustForFramerate` default method ＋算術驗證。結論是不動 bytecode，故未做 javap 逐指令驗證。

### 2b. 動物壓力模型三調（IsoAnimal）

**背景原理**：動物的 `stressLevel`（0-100）進出全走 `changeStress`（含基因放大與
clamp）。MP 的結構性問題是「**進水快、出水慢**」：多玩家的槍聲/喊叫密度高（每發
+radius/20，槍聲 radius 70-150 → 單發 +3.5~7.5）、例行屠宰對同圈全體 +Rand(10,30)，
而唯一的自然衰減只有閒置時的 `-multiplier/5500`。結果動物長期滯留高壓區間：
≥80 開始撞毀圍籬（thump，MP 最痛損失）、>40 誘導失敗率飆升。

| 手術 | 值 | 效果 |
|---|---|---|
| updateStress 閒置衰減除數 | `5500 → 2750` | 無壓力源時恢復速度 ×2（唯一自然出水管道） |
| respondToSound 聲音壓力係數 | `0.05f → 1/60f` | 單次聲音壓力 ÷3（尖峰主因） |
| killed 屠宰連鎖上限 | `Rand(10,30) → Rand(10,15)` | 平均 20→12.5，群體受驚語意保留 |

**為什麼安全**：全部是既有常數的幅度調整——無新路徑、無指令增刪；`changeStress`
的 [0,100] clamp、基因/缺陷放大、逃跑行為（動物照樣被嚇跑）、防虐待機制
（被攻擊的高壓直寫路徑）全部不動。手術只改指定方法內的指令指向新常數池條目，
其他方法實測原樣。伺服器權威、數值經同步覆蓋 client，**只裝伺服器即全域生效**。

> **42.20 座標變更（重要陷阱）**：TIS 把壓力算式從 `changeStress(sound.radius / 20.0F)`
> 改寫成 `changeStress(sound.radius * 0.05F)`（數學等價，但常數池條目從除數換成乘數），
> 同時在 wild 分支新增 `fleeDistance = sound.radius * 3.0F + 20.0F`。
> 結果是 `respondToSound` 內**仍剛好只有一個 `20.0f`**——舊的 `ConstChange(20.0f, 60.0f)`
> 會通過逐方法命中數守門（1 == 1），卻把野生動物逃跑距離改成 `radius*3+60`，壓力完全沒調。
> **命中數守門只數數量、不驗語境**；常數手術每次更新都要用 `javap` 確認前後指令
> （正確的壓力點在 offset 347 附近：`ldc 0.05f; fmul; invokevirtual changeStress:(F)V`）。

**刻意不做的**（分析過並否決）：PacketsCache 的封包速率常數（正路是 ini 的
`MaxPacketsPerSecond`）；動物 heldBy 安撫速率（已是主動手段）；culling 的安全距離
（縮小會被玩家目擊消失）；早退 updateStress（會連衰減一起殺掉，反效果）。

---

## 2c. 防崩潰頭部守衛（2 項，codex 對抗審查定案）

**原理**：MP 的 hit 封包用 CharacterID 延遲解析目標角色；stale／型別混淆的參照會讓
`getZombie()`（=tryCastTo，可回 null）或傳入的 `character` 為 null，而原版 setter 鏈無任何檢查
→ NPE。手術＝方法最前插入 4 條指令的 null 守衛（`aload; [invokevirtual]; ifnonnull L; return; L:[F_SAME]`），
堆疊峰值 1、locals 不變、原 frames 照舊。

| 位置 | 守衛 | 順序關鍵 |
|---|---|---|
| hit/Zombie.process()V | `getZombie()==null → return` | **在 `super.process()` 之前**——否則 character-null 先在父類 NPE、type-confusion 會把殭屍狀態先寫進錯誤角色 |
| hit/Fall.process(IsoGameCharacter)V | `character==null → return` | 縱深防禦定位：封包 pipeline 後續仍會用 target，不宣稱端到端防崩 |

**驗證（build 第 6 步）**：行為 smoke＋負對照（原版必拋 NPE、修補版必須安靜返回——行為級證明
guard 位置正確）＋ASM 結構斷言（guard 在最前、super 恰一次、9 setter 未增減）。

**將來評估**（更根本的修復點，未做）：`Zombie.isConsistent()` 加 `getZombie()!=null`（現只驗 ID
存在不驗型別）；`hit/Player` 有對稱風險應一起審。

---

## 2d. 安全屋 room/building 綁定修復

**症狀**：B42.19 正式服擴充大型 `Map=` 後，玩家申請安全屋時大量出現
`SafehouseClaimPacket.isConsistent > building not found`。失敗座標的原始 lotheader 仍有合法
`RoomDef`／`BuildingDef`，但執行期 `IsoGridSquare.getBuilding()` 回傳 null。

**手術**只改 `SafehouseClaimPacket` 兩個呼叫點：

1. `isConsistent` 的 `IsoGridSquare.getBuilding()` 改道 helper。正常有 building 時直接回傳；只有 null
   才掃描目前 metacell 與相鄰八格的 authoritative `roomList`，依原版 `IsoMetaChunk.getRoomAt`
   規則（反向順序、user-defined 優先、排除 emptyoutside）找房間，呼叫 `setRoomID` 並重新確認 building。
   找不到或補回後仍為 null，就回復舊 roomId，讓原版照常拒絕。
2. `processServer` 的 `SafeHouse.canBeSafehouse` 改道 wrapper，先同樣修復 claim square 與玩家目前
   square，再呼叫原版方法。既有「已擁有安全屋、存活天數、住宅類型、屋內角色、範圍重疊、戰爭、
   權限與反作弊」檢查一項都沒有移除。

這個 patch **不直接建立安全屋、不修改 `map_meta.bin`、不接受沒有 RoomDef 的座標**。掃描是 O(n)，
但只發生在原本會失敗的安全屋 claim，不是 frame 熱路徑。成功修復會記錄：
`[MinidoracatJavaPatch] repaired safehouse room binding at x,y,z roomId=...`。

舊安全屋資料另由 `scripts/map_meta_safehouses.py` 解析與選擇性合併；工具強制輸出新檔、逐座標指定、
拒絕 owner／範圍衝突，且讀取與輸出都必須通過 byte-for-byte round-trip 驗證，不會直接覆蓋正式存檔。

---

## 2e. 原生固定容器週期刷新修復

**根因**：B42.19 的 `LootRespawn.respawnInChunk` 先用地面格的 Zone 擋整個 `(x,y)` 垂直欄位；
只有精確名稱 `TownZone`、`TownZones`、`TrailerPark` 會進入掃描。正式服實測的 Yanghu 醫院只有
`Region`／`FarmLand`，該地圖 `objects.lua` 也沒有任何上述 Zone，因此原生藥櫃永遠不會進到刷新流程。
另一類案例雖位於 `TownZone`，但玩家任何建造／搬家具都可能把整個 Zone 的
`haveConstruction` 永久標為 true；原版沒有對應的解除路徑，之後整區固定容器都被擋住。

**手術**只改 `LootRespawn.respawnInChunk` 兩個呼叫點，週期 marker、loot table 與其他 gate 不動：

1. `IsoGridSquare.getZone()` 改道 `getLootRespawnZone`。原版 Zone 已合格且沒有 construction 時直接回傳，
   零行為差異；否則先掃同一 chunk 的同一垂直欄位，只有找到「非屍體、非 `IsoThumpable`、非 compost、
   `movedThumpable=false` 且確有 container」的物件，才回傳只供本次 gate 判斷的合格 Zone。
   有原 Zone 時會複製 `hourLastSeen`，所以 `SeenHoursPreventLootRespawn` 照常生效。
2. `IsoObject.getContainerCount()` 改道 `getLootRespawnContainerCount`：搬動過的原生家具回傳 0；
   玩家製容器原本多為 `IsoThumpable`，仍在原版 `instanceof` gate 被排除。未搬動固定物件原樣回傳
   真實 container count。

**安全屋語意完整保留**：bytecode 中 `SafeHouse.getSafeHouse(square)` 沒有改道，每個刷新週期都重新查
目前有效的安全屋。安全屋存在時仍不刷新；解除後不會立刻補貨（避免 claim/unclaim 洗物資），而是在該
chunk 的**下一個正常 `HoursForLootRespawn` 週期**恢復。`explored`、`hasBeenLooted`、
`MaxItemsForLootRespawn`、`SeenHoursPreventLootRespawn` 與 `ItemPickerJava.fillContainer` 全部保持原版。

**已知邊界**：若 square 完全沒有 Zone，就沒有可保存的原版 `hourLastSeen`，fallback 使用 0；正式服目前
`SeenHoursPreventLootRespawn=0`，不影響現行行為。此 patch 修的是週期刷新，不強制未探索容器立刻生成，
也不重寫既有 `lootRespawnHour`。

---

## 2f. 玩家登入同步 DB 寫入耗時量測（觀測 patch）

**目的**：玩家登入時曾伴隨 `Server is too busy`，但目前證據只能確認主執行緒在登入流程中做同步工作，
尚不能把尖峰歸因到單一 DB operation。本項先建立可歸因的 server-side timing，不改登入並行度或拒絕策略。

`LoginPacket.processServer` 只有三個呼叫點被改道：

| 原呼叫 | helper op | 原版 bytecode |
|---|---|---|
| `ServerWorldDatabase.setPassword(String,String)` | `SET_PASSWORD` | offset 1273，caller 既有 `catch Exception` 保留 |
| `ServerWorldDatabase.updateLastConnectionDate(String,String)` | `UPDATE_LAST_CONNECTION` | offset 1303 |
| `ServerWorldDatabase.setUserSteamID(String,String)` | `SET_USER_STEAM_ID` | offset 1330，後續 `POP` 保留 |

helper 對原 receiver method **delegate exactly once**，`System.nanoTime()` 只包住 delegate，成功時輸出：

```text
[MinidoracatJavaPatch][LoginMetrics] op=<固定列舉> elapsedNs=<十進位數字>
```

payload 不含 username、password、Steam ID、IP 或 token。只使用既有 `DebugType.Multiplayer.println`
sink，不新增檔案 writer、thread、flush、cache、queue、retry、SQL 或 transaction。非致命的 log
格式化／sink failure 不得改變登入結果；`VirtualMachineError`、`ThreadDeath`、`LinkageError` 原樣外拋。
若 delegate 已拋 fatal，wrapper 只完成 elapsed 計算後直接重拋，不再嘗試 log；若 delegate 是 nonfatal
而 logging 是 fatal，logging fatal 優先，且不修改任一 exception 的 suppressed list。

這不是「登入優化」本身，而是下一輪 A/B 判斷依據。正式服部署後要把三種 op 的 elapsed 分布與
`Server is too busy` 時段對齊；證據指出哪一項形成長尾後，才評估 transaction／批次化等行為改動。
原生 70ms busy 判斷、`LoginQueueEnabled`、`DenyLoginOnOverloadedServer`、auth/protocol 與登入順序均未修改。

---

## 2g. chunk unload entity removal 索引化

**正式服根因證據**：玩家回報卡頓與黑邊的時段，主機仍有約 85% CPU idle、低 I/O、充足可用記憶體，
也沒有 OOM、`VehicleCollide` 或持續登入尖峰；但伺服器實際 FPS 曾降到約 2–5。五次 matching-JAR
thread dump 中四次主執行緒都落在：

```text
Array.removeValue
  -> EntityBucket.updateMembership
  -> EngineEntityManager.removeEntityInternal
  -> IsoChunk.removeFromWorld
  -> ServerMap$ServerCell.Unload
  -> ServerMap.postupdate
```

`EngineEntityManager.entities` 與每個 `EntityBucket.entities` 都是 `new Array<>(false, 16)`。原版
`removeValue(entity, true)` 每次都從 index 0 線性掃描；大量 chunk 在同一波卸載時，k 個 entity
反覆掃描逐漸縮小的全域／bucket 陣列，總工作量是 O(k×N)，最壞接近 O(N²)。這也解釋了為何整台
主機不滿載：PZ 權威 world loop 的單一主執行緒先吃滿一個核心，其餘核心閒置，玩家仍看到 chunk
供應延遲與黑邊。

**手術範圍只有四個 callsite**：

| class.method | 原呼叫 | 改道 |
|---|---|---|
| `EngineEntityManager.addEntityInternal` | `Array.add` ×1 | `FastIdentityArrayRemoval.add` |
| `EngineEntityManager.removeEntityInternal` | `Array.removeValue` ×1 | `FastIdentityArrayRemoval.remove` |
| `EntityBucket.updateMembership` | `Array.add` ×1、`removeValue` ×1 | 同上 |

沒有改 `ServerCell.Unload`、chunk 判定、entity callback、bucket bit、listener 順序、登入佇列或 busy
保護。helper 在 patched add 時同步建立 sidecar：以 `System.identityHashCode(entity)` 映射 index，
尾端 swap-remove 後只更新搬入元素的 index，因此正常 add/remove 都是常態 O(1)。

**安全與生命週期**：

- registry 是 `WeakHashMap<Array<?>, State>`，且只接受 `ordered=false`；`State` 本身只含
  `TIntIntHashMap`／`TIntHashSet` 與 primitive counter，不持有 Array 或 entity 強參照。
- `TIntIntHashMap` 關閉 remove 時的 auto-compaction，避免同一波 unload 反覆配置／rehash；
  sidecar 會保留該 Array 的峰值容量，直到 weak key 回收。這是以少量可界定記憶體換掉 hot-path GC。
- identity hash 碰撞或同一 identity 重複時，該 hash 改走原版「由前往後、第一個 identity match」
  的線性語意；每次 fast remove 前仍用 `items[index] == value` 驗證。
- 偵測到 size/index 漂移時最多 rebuild 一次；再次不一致就失效 sidecar 並呼叫原版
  `removeValue`。`ordered=true`、`identity=false`、null 全都直接保留原版路徑。
- 鎖粒度是每個 Array；全域 weak registry lock 只包短暫查找／登記，不包 O(N) rebuild 或原始 add。

**驗證**：build 會確認四個原呼叫歸零且四個 helper 呼叫各恰一、helper/inner class 不含
`IdentityHashMap` 或 entity 強參照；另跑 tail-swap 等價性、missing、ordered/equality/null、
size/same-size/index 漂移、duplicate/hash collision、不同 Array 並行與 deterministic stats。
尺度 benchmark 固定 N=1024/2048/4096/8192、3 輪 warmup＋7 輪中位數，輸出 add、first remove、
full remove、ns/entity、倍增比與可用時的 thread allocation；時間只作報告，不設機器相依 pass/fail
門檻。

---

## 2h. popman 共享 buffer 執行緒競爭修復（v3 buffer 隔離）

**正式服根因證據**：2026-07-30 全日 11 個 log set 共 77 筆
`IngameState.UpdateStuff> Exception thrown`＝`java.nio.BufferUnderflowException` at
`ZombiePopulationManager.updateMain`（`:611` getFloat 與 `:614` getInt 兩種越界點）。下午高峰
（12:12–18:11）6 小時內 34 筆；01:04:47 的例外正落在玩家 client 端 229 筆
`removing stale zombie 5000` 清除視窗內，與「殭屍/實體消失」回報時段吻合。

**機制（定案根因＝執行緒競爭）**：`updateMain`（主執行緒）以
`n_getAddZombieData(offset, byteBuffer)` 分頁讀取 native 的 add-zombie 佇列，buffer 是
`allocateDirect(1024)`、每筆 29 bytes（getFloat×3＋get×1＋getInt×4）。**同一個
`this.byteBuffer` 也是存檔寫側的工作區**：`writeCellSnapshot`（MCD 背景執行緒，每筆
21 bytes）與 `beginSaveRealZombies`（主執行緒）——寫側之間有 `saveLock` 互斥，
**讀側 `updateMain` 卻沒拿鎖（vanilla 遺漏）**。MCD 寫側與主執行緒讀側併發時，共享的
Buffer position 被兩邊同時推進：輕則讀取越界（隨機欄位 `BufferUnderflowException`），
重則**無聲混讀**寫側的 21-byte 記錄當成 29-byte 生成資料（殭屍資料損毀）。例外一路拋出
`IngameState.UpdateStuff` 的共用 try 區塊，把該 tick 的 popman 剩餘解析、
`updateLoadedAreas`、`MapCollisionData.notifyThread`、`playerSpawns.update` 與
**`PathfindNative.updateMain` 泵送**全部帶掉——高流量區殭屍密度被抽乾＋殭屍尋路瞬間定格；
例外集中在 chunk 存檔高峰（寫側活躍時段）也由此解釋。

**手術（v3，root fix）**：兩個手術型組合，全部線性插入、無新分支：

1. **field-get-swap（buffer 隔離，根治）**：updateMain 內全部 **10 處**
   `getfield byteBuffer`（clear ×1、native 寫入參數 ×1、欄位讀取 ×8）之後插入
   `invokestatic PopmanBufferGuard.updateMainBuffer(ByteBuffer)ByteBuffer`——吃掉共享
   buffer、回傳主執行緒專用的 `UPDATE_MAIN_BUFFER`（allocateDirect(1024) 鏡射 vanilla）。
   讀側與 MCD 寫側徹底隔離，零鎖零死鎖。堆疊 1→1。前提已驗證：native 只有
   `n_getAddZombieData`/`n_saveRealZombies` 收 ByteBuffer 且皆逐呼叫傳參
   （`n_init` 無 buffer 註冊＝無快取位址假設）；寫側 `beginSaveRealZombies`（主執行緒）
   ／`writeCellSnapshot`（MCD 執行緒）維持 vanilla 的 this.byteBuffer＋saveLock 紀律不動。
2. **count-clamp（保險絲）**：鎖定
   `invokestatic n_getAddZombieData → istore C → iload O → iload C → iadd → istore O` 全序
   （slot 逐步核對，任何一步不符即放棄→守門失敗），在 `istore O` 之後插入
   `iload C; invokestatic clampAddZombieCount(I)I; istore C`——helper 以專用 buffer 的
   `remaining()/29` 為上限。隔離後不應觸發；一旦觸發即記 log＝仍有未知失配的警報器。

**v1→v2→根因修正（2026-07-30 當晚，codex 對抗審查定案）**：v1 上限固定 1024/29=35，
部署重啟後 13 分鐘內仍 2 筆 underflow 且 clamp 觸發 0 次——「容量溢位」假說被線上否證。
v2 改以呼叫當下 `buffer.remaining()/29` 為上限（防禦性保留），但 codex 進一步從
反編譯源碼證實**主嫌是共享 buffer 的執行緒競爭**：`processPendingSaveCells`／
`writeCellSnapshot` 在 MapCollisionData 背景執行緒（`MapCollisionData.runInner:469`）
對**同一個 `this.byteBuffer`** 做 clear＋put 序列，而主執行緒的 `updateMain` 讀同一
buffer **完全沒有同步**——`saveLock` 保護了 `beginSaveRealZombies` 與
`processPendingSaveCells` 的寫側，`updateMain` 卻沒拿鎖（vanilla 遺漏）。兩執行緒共享
同一 Buffer 的 position 指標，並發時 position 亂跳 → 隨機欄位 underflow＋混讀損毀的
殭屍資料；也解釋例外集中在 chunk 存檔高峰。v2 在競爭下只能讀到瞬間快照，
**不能根治**（但無害）。**runtime overlap trace 已於 2026-07-31 00:17 由 v2 clamp 取得**：
`pageCount=35 > readable=28 (remainingBytes=814)`——1024−814=210=**10×21 bytes 恰為寫側
10 筆記錄**，位元組級證實取樣瞬間 MCD 寫側正在同一 buffer 寫入。據此定案 v3 buffer 隔離
（見上方手術），棄用 lock-wrap（native 層死鎖未知數）。

**為什麼 clamp 在 `offset += count` 之後**：offset 推進沿用 native 原回報值，分頁推進行為與
原版逐位元一致——無論 native 是 offset-served 還是 consume-on-read，都不會重讀、推進不足或
死迴圈。若改成在 offset 推進**之前** clamp，consume-on-read 語意下 `while (offset < total)`
可能永不收斂（主執行緒死迴圈）——不可接受，故不採。損失語意：**隔離後的正常路徑 lossless**
（native 回報數不會超過專用 buffer 可讀量，clamp 不觸發）；只有保險絲真的觸發時（＝仍有
未知失配的異常狀況）才會丟棄超額殘尾，遺失範圍與原版 underflow 相同、不新增遺失。

**helper**：`zombie/mdc/PopmanBufferGuard`——`UPDATE_MAIN_BUFFER`（allocateDirect(1024)
專用 buffer，僅主執行緒觸碰）＋`updateMainBuffer(ByteBuffer)`（swap 目標，無視傳入值回傳
專用 buffer）＋`clampAddZombieCount(int)`（`count <= remaining/29` 原值返回；超額時累計
dropped 筆數並經 `DebugType.Multiplayer.println` 記 `[MinidoracatJavaPatch][PopmanBufferGuard]`，
log sink 失敗不外拋）。插入點堆疊安全（swap 1→1、clamp 峰值 1），frames 不需增補
（`ClassWriter(0)` 原樣保留）。

**驗證**：build 守門＝命中恰 **11**（field-get-swap ×10＋count-clamp ×1）；SmokeCheck
專用 buffer 斷言（同一實例、direct、容量 1024、無視傳入值）＋ **10/10 swap 相鄰性**
（updateMain 每個 `getfield byteBuffer` 必須緊接 swap、無多餘 swap）＋
行為 smoke（35 內原值、36+ 夾 35、limit-short/position 依 remaining、負值不動）＋
結構全序鎖（clamp 必須在 `istore O` 之後、count slot 即 `if_icmpge` 迴圈比較上限、
loop-index slot 與 count/offset 相異、native 分頁呼叫未增減）＋ **MAX_RECORDS 前提守門**
（capacity 取自 `<init>` 的 `allocateDirect` 實參、每筆 bytes 由 updateMain 的
getFloat×3/get×1/getInt×4 計出，並與 helper 實際 clamp ceiling 連動——PZ 只改 buffer 大小
或 record 欄位時建置失敗而非默默錯上限）。手術狀態機遇 StackMapFrame（控制流合流點）即放棄。
部署後觀測：`UpdateStuff> Exception thrown`＋`BufferUnderflowException` 應歸零；
若 `[PopmanBufferGuard]` clamp log 出現＝native 超額頁實際發生率的直接量測。

---

## 2i. join 卡頓量測（觀測 patch）

**動機**:正式服主迴圈實測 6–11 秒停頓集中在玩家 join／死亡重生換角(例:17:20:33–17:20:39
的 6.6s 正值 Player-C「replacing dead player」),但無法從 log 分辨時間花在哪一段。
現有 LoginMetrics 只蓋 login 期的三個 DB 寫入,不含 join spawn 段。

**手術**:與 LoginMetrics 同型的 redirect-call timing wrapper——
`CreatePlayerPacket.processServer` 尾段四個重活各包一層,不改呼叫順序、參數與例外邊界:

| 原呼叫 | op 標籤 | 實際工作(依 42.20 反編譯核實) |
|---|---|---|
| `LuaEventManager.triggerEvent("OnNewGame",…)` | `TRIGGER_ON_NEW_GAME` | 百餘模組的 OnNewGame handler 全跑一遍,頭號嫌疑 |
| `ServerPlayerDB.serverUpdateNetworkCharacter` | `DB_UPDATE_CHARACTER` | 玩家 snapshot 序列化＋enqueue(`player.save` 到 buffer,**非 SQL**) |
| `ServerPlayerDB.process()` | `DB_PROCESS` | **queue drain＋SQL select/insert/update＋commit**,可能含先前 backlog |
| `this.write(b)`(封包序列化) | `WRITE_PACKET` | SurvivorDesc/物品序列化進封包 |

**一般重連(既有角色,join 大宗)**:`CreatePlayerPacket` 只在新角色/死亡換角時走
(40 人重啟湧入只記到 3 筆的原因)。一般重連走 `GameServer.receivePlayerConnect`,補兩個量測:

| op 標籤 | 範圍 |
|---|---|
| `REJOIN_TOTAL` | 整個 `receivePlayerConnect`(兩個呼叫點:ConnectPacket.parse 一般、ConnectCoopPacket.parse 分屏/coop) |
| `REJOIN_LOAD_CHARACTER` | 內層 `ServerPlayerDB.serverLoadNetworkCharacter`(SQL SELECT＋玩家全量反序列化,同方法 if/else 兩點) |

`REJOIN_TOTAL − REJOIN_LOAD_CHARACTER` ＝ 其餘處理(全服廣播、ClientServerMap、
preventIndoorZombies——private static 不可包)。

**量測不到的殘差**:`new IsoPlayer(...)` 建構子無法以 redirect 包
(INVOKESPECIAL `<init>` 的未初始化物件不可傳入 helper,verifier 禁止)——
若各項總和遠小於 join 停頓,殘差＝ctor＋spawn 邏輯＋chunk 載入,屆時再做第二輪定位。

**驗證**:build 守門＝命中恰 4;SmokeCheck 結構斷言(四點改道且原呼叫歸零、wrapper 各
delegate exactly once、單一 sink、無 checked exception、僅用既有 Multiplayer sink);
JoinMetricsBehaviorTest 行為測試——以可 override write() 的 FakePacket 全象限覆蓋
(delegate 成功/receiver 與 argument identity/nonfatal sentinel identity/三種
delegate fatal 均不進 sink/sink nonfatal 不改結果/sink fatal precedence),
其餘 wrapper 以 null-receiver NPE 驗證;triggerEvent 每測試點用唯一事件名
(LuaEventManager 對未知事件先註冊再拋 NPE,同名第二次會成功)。
部署後觀測:join 時四行 `[MinidoracatJavaPatch][JoinMetrics] op=… elapsedNs=…`,
對照停頓長度即可歸因。

---

## 2j. Client 端貼圖管線門檻修復＋觀測（實體隱形，第一個 client patch）

**症狀與根因**:B42 MP 已知未修 bug——受害 client 看到隊友/殭屍/車輛「只剩影子和名牌、
3D 模型不見」,>20 人在線觸發、relog 暫癒、log 全程無錯誤。四路反編譯 trace＋對抗評審
定案的因果鏈第一環:`TextureIDAssetManager.waitFileTask` 以 50MB 的全域 DirectBuffer
水位當硬門檻(`while (getBytesAllocated() > 52428800L) sleep(20)`),超標時 2–4 條檔案
載入執行緒無限 sleep(零 log、無 timeout);貼圖與 mesh 共用 FileSystemImpl 載入池,
管線停滯期間所有新進視野/剛被 Reset 的實體因全有全無 bake 閘門
(`ModelInstanceTextureCreator.render` 任一貼圖未 ready 整隻不烘)完全隱形,而影子
(FBORenderShadows blob 貼花)與名牌(UI batch)走獨立管線照畫。詳見
`docs/specs/zombie_core_textures_TextureIDAssetManager.json`。

**手術**(`PatchConfig.client()`,expectedHits=2,兩刀都在 waitFileTask 方法內):
1. redirect——`DirectBufferAllocator.getBytesAllocated()J` 改道
   `zombie/mdc/TexturePipelineGuard.bytesAllocatedObserved()J`(同形 ()J,回傳值
   原樣 passthrough、真實取值例外照原版傳播;觀測部分 try/catch 全吞、fatal 三件套
   VirtualMachineError/ThreadDeath/LinkageError 照拋,絕不改變載入行為)。
2. constChange——門檻 `52428800L`(50MB)→v1 `268435456L`(256MB)→**v1.1
   `1073741824L`(1GB)**。**門檻語意(codex 對抗審查實驗修正)**:這是「已解碼未上傳」
   pixel buffer 的水位,但 WrappedBuffer 走 LWJGL native malloc,**不受
   -XX:MaxDirectMemorySize 約束**,且門檻是配置前檢查、多 worker 可同時通過——
   天花板不是硬上限。**v1.1 實測依據(Tester-A 兩場 log,2026-07-31)**:水位「地板」
   因棘輪洩漏單調上升永不下降(50→125→154→263→273MB 釘死),~35 分鐘追上 v1 的
   256MB 天花板→全部載入執行緒永久睡(~194 樣本/s)→隱形回歸。code 級洩漏點=
   ImageData 解碼例外路徑無 dispose(ctor 分支+APNG 迴圈中斷洩 compositeBuffer+
   getData() 64MB 懶配置)。**天花板只買時間,任何上限終被地板追上**;1GB 依實測斜率
   約 2–2.5 小時被追上(≈v1 跑道 ×4,無時間保證;1GB 亦非硬上限,可被多 worker 超額),
   根治=ImageData dispose 修補(規劃中)。洩漏為 process 級 static,relog 清不掉
   (解釋社群 relog 時靈時不靈),完全重開遊戲才歸零——玩家指引以此為準。
   低 RAM(≤8GB)機器不適用本 patch。

**觀測輸出**(決策在 synchronized 內、`DebugLog.log` 一律在鎖外送出——避免慢速 log
串行化 2–4 條載入執行緒;非 fatal 觀測例外全吞,fatal 三件套照拋):`active` 宣告
(log 成功才設旗標,boot 極早期 DebugLog 未就緒時自動重試——此行是安裝驗證契約)、
`hwmBytes` 高水位每跨 8MB 台階一行、水位高於原版 50MB 門檻時每 5 秒至多一行
`bytes/hwm/floorBytes/aboveVanillaMs/vanillaStallSamples/patchedStallSamples`、
**v1.1 新增 periodic 行**(每 60 秒,無 stall/hwm 行時):`floorBytes`=60 秒窗最低
水位=洩漏地板——**floorBytes 單調上升=洩漏進行中的直接證據,斜率=洩漏速率**。
**語意精確版**:vanillaStallSamples＝would-enter-wait 取樣數(原版在該取樣點會
進入至少一次 20ms 等待),單獨不證明持續饑餓;連續 stall 行＋`aboveVanillaMs`
(本次連續超標已持續毫秒數)才是持續停擺的證據;patchedStallSamples>0＝1GB
天花板也被地板追上(重開遊戲歸零,並回饋根治版優先度)。

**與 server 部署完全隔離**:獨立 `build-client.ps1` → `work\out-client`＋`dist-client\`
(不進 server manifest;server build 十步全綠回歸驗證過)。安裝機制:client
`ProjectZomboid64.json` classpath 為 `[".", "projectzomboid.jar"]`,遊戲目錄優先於
jar,loose class 直接 shadow。**玩家安裝走 fail-closed `install.bat`**(建置時注入
SHA:先驗 jar SHA-256=42.20.0、再驗目標位置無其他 loose patch 衝突,通過才從
`patch-files\` 複製並回驗兩檔 SHA);移除走 `uninstall.bat`(逐檔比對 SHA 確認
ownership 才刪,非本 patch 版本一律不動並以非零 exit 報警;Steam 驗證檔案完整性
**不會**移除非 depot 的 loose file,不可當移除手段)。僅供受影響玩家個人測試,
不得散布;遊戲版本更新後 install.bat 會自動拒裝,既裝者須先 uninstall。

**驗證**:build 守門＝命中恰 2;SmokeCheck client 模式——vanilla 前提守門(jar 內
waitFileTask 恰一個 getBytesAllocated＋恰一個 52428800L,PZ 改寫時建置失敗)、
全序鎖(observed→1GB→lcmp→ifle)、sleep(20) 迴圈保留、helper 門檻常數與 bytecode
常數連動、真實 allocate/dispose passthrough smoke;LoadCheck client 模式(-Xverify:all
＋簽名/常數連動);BytecodeVerify;TexturePipelineGuardBehaviorTest(真實
DirectBufferAllocator 真實配置驗 passthrough/50MB 跨越/dispose 歸零,1GB 門檻與
floor/periodic/優先序狀態機以反射 observe() 合成值驗證——不需 1GB 真實配置)。
部署後觀測:console.txt 搜 `TexPipelineGuard`,`active` 行＝生效;隱形復發時
對照 `vanillaStallSamples` 與 relog 時點即可對帳因果鏈。

---

## 2k. 效能第一波：載具視線預篩＋VehicleManager 512→256

**立案依據**：fps-dip-sampler（低谷觸發 kill -3）累積 66 份 thread dump 聚合——載具幾何主題佔
~23% 最大宗（`isVehicleBetween → getIntersectPoint` 鏈＋`VehicleManager.serverUpdate`），負載瀰漫
無單點病灶。Claude 五路平行讀碼＋逐項對抗驗證、codex 獨立讀碼（30 分鐘，含對真實 jar javap）
雙審一致後定案。87 人低谷 4.4 FPS，預期回收 6–19% tick 時間（→4.7–5.4 FPS）；Amdahl 上限
明確——全部熱點清光也到不了 10 FPS，**本波改善尾延遲，不是 100 人容量承諾**。

### 2k-1. IsoZombie.isVehicleBetween 保守包圍球預篩

原版對整個已載入 cell 的**每台載具**做完整 OBB 相交（未命中路徑每台 2 次 `Transform.inverse()`
矩陣求逆＋約 6 次向量池借還），無任何距離預篩；追擊中殭屍每 tick 執行。手術＝方法內唯一的
`getIntersectPoint` 呼叫點（javap offset 99，恰 1 處）改道 `VehicleIntersectPrefilter`：

- 先算「視線段到載具中心」平方距離，超出保守包圍球＝幾何上不可能相交＝直接回 null
  （呼叫端只判非 null，**語意嚴格等價**）；球內或任何異常＝原樣委派原版。
- 半徑 per-vehicle：`extents/2＋|centerOfMassOffset|` 的 **L1 上界**（≥ L2 半對角，必然偏大
  ＝零 false-negative）＋1.0F 膨脹（吸收 getX/getY 與 jniTransform 物理原點次格差）、下限 6.0F。
  超長 MOD 載具自動放大。codex 定案否決：固定車長、端點距離、不含旋轉的緊 AABB。
- 雙審一致**否決**第二階段 TTL 結果快取（失效鍵須含旋轉/翻覆/拖曳/mod reload；且 result 是
  池化可變 Vector3f 不可持有）。
- 帶 `rejected/delegated/anomalies` 計數，每 2^24 次呼叫經既有 Multiplayer sink 印一行——
  **reject 率 >0.9 是本刀有效的判準**，也是 codex 設下的人數上限放寬前提之一。
- 只動本方法內呼叫點；`CombatManager`、`BaseVehicle.processRangeHit` 等其他呼叫者原樣。

### 2k-2. VehicleManager.connected 512→256

`serverUpdate` 每 tick 無條件掃 `connected[]` 全部 512 slot × 全部載具做旗標傳播（實際發送
另有 100ms 節流，~83% 呼叫純空轉）；dump 5/5 停在該迴圈回跳邊（LineNumberTable 對映
offset 175 = `goto 124`，發送段 0 命中）。而 RakNet index 上界實證 <256：
`UdpEngine.connectionArray[256]`、ID 一律 `getByte()&255` 解碼、`setIndex` 全 jar 零呼叫者——
**上半 256 slot 從未被寫入**。`<init>` 的 `sipush 512 → 256`（語境鎖：緊接
`anewarray UdpConnection`；負對照：同方法 `bipush 27`／`100L`／`1000L` 節流常數原樣），
掃描成本精確砍半。`BaseVehicle.connectionState[512]` 刻意不動（同 index 界限，多餘槽位無害）。
**失效訊號**：若出現 `ArrayIndexOutOfBoundsException` 且 stack 含 `connectionAdded`＝
index≥256 反例，立即 uninstall 並推翻界限分析。

### 附帶：manifest 完整性守門（本波實踏的坑）

`build.ps1` 的 `$helperEntries` 是手寫清單——本波 helper 編進了 `dist/java` 但漏登記 manifest，
`install.sh` 不會複製、上線即 `NoClassDefFoundError`（SmokeCheck 的 URLClassLoader 吃整個
dist/java 所以測不到）。已補雙向守門：dist/java 與 manifest 不一致＝建置中止。

## 2l. 假死修復：removeGlassAttachments 無限迴圈保險絲

**事故**：2026-08-02 17:48 全服假死（幀計數凍結 f:15924、所有玩家靜止、重登卡驗證、
graceful stop 無效、pkill -9 恢復）。兩份間隔 4 秒的 thread dump 主執行緒皆 RUNNABLE
於同一迴圈的不同指令（活迴圈非死鎖），呼叫鏈：

```text
SmashWindowPacket.processServer（一位玩家砸窗）
→ IsoWindow.smashWindow → IsoGridSquare.removeGlassAttachments
→ 無限迴圈（PropertyContainer.get / stream anyMatch 之間狂轉）
```

**根因**：原版迴圈命中「玻璃附掛物／窗牆電燈開關」時 `RemoveTileObject(o)` 後**無條件
`n--`**，假設移除必使清單縮短；42.20 的 RemoveTileObject 走 `safelyRemoveTileObjectFromSquare`
安全路徑，特定物件狀態下移除不生效 → 同 index 重撞同物件 → 永迴圈。一個砸窗封包鎖死整個
server tick。100 條執行緒堆疊**零我方 patch 類**——純原版 42.20 bug（建議回報 TIS）。

**手術**：`smashWindow(ZZ)V` 內唯一的 `removeGlassAttachments` 呼叫點（javap offset 221，
全 jar 唯一呼叫者）改道 `GlassAttachmentGuard`：逐語意重刻原迴圈，唯一差別＝**清單真的
縮短了才回退 index**；未縮短（原版死鎖分支）跳過該物件＋log
`[MinidoracatJavaPatch][GlassGuard] stuck glass attachment skipped at x,y,z sprite=…`。
正常砸窗逐語意等價（清除、警報順序全不動）；病態案例從全服假死降級為一個物件未清除
＋一行定位 log——**下次觸發直接知道問題物件在哪**。helper 無狀態零欄位，全 public API。
TIS 官方修復後 uninstall 即回歸原版。

## 3. 部署後驗證清單

1. **開機健檢**：console 無 `VerifyError`/`ClassFormatError`/`NoSuchMethodError`（有＝立刻 uninstall）。
2. **抑噪生效**：上表 7 種訊息不再出現（開機幾分鐘內原本必有 2/4/6）。
3. **未誤攔**（反向）：debug 模式下 ItemPickInfo 診斷訊息、SpriteConfig 其他名稱警告、
   anticheat `is not valid` 仍會輸出。
4. **行為觀察**：動物面板（admin cheat）壓力恢復約快一倍、槍聲增量約 1/3。
   （殭屍 `zombiesCulled` 觀察項隨 2a 一併移除。）
5. **安全屋驗證**：在曾回報失敗的房屋重新申請，應先看到 repair log，隨後由原版規則成功建立；
   非房屋座標仍必須被 `building not found` 拒絕。
6. **容器刷新驗證**：在無 TownZone 的自訂地圖與 `haveConstruction=true` 的 vanilla Zone 各選一個
   已探索、已拿取且少於 `MaxItemsForLootRespawn` 的原生固定容器；等下一個正常週期後應可補貨。
   同區玩家製箱、搬動家具及有效安全屋內容器不得補貨；解除安全屋後只在再下一個週期恢復。
7. **登入量測驗證**：controlled Steam login 應出現三個 op 各一行；任何 unknown/duplicate/missing op、
   非十進位或負的 `elapsedNs`、玩家識別資料外洩都視為失敗。先觀察 log，不以本 patch 宣稱 busy 已修復。
8. **chunk unload 驗證**：以相近在線人數與移動速度比較 patch 前後 server FPS、黑邊回報與 thread dump；
   hot stack 不應再長時間停在 `Array.removeValue -> EntityBucket/EngineEntityManager`。若出現
   `VerifyError`／entity membership 異常，先停服執行 `uninstall.sh` 回退，不以單次低負載時段宣稱根治。
9. **效能第一波驗證**：(a) console 出現 `[MinidoracatJavaPatch][VehiclePrefilter]` 統計行，
   `rejected/(rejected+delegated)` 應 >0.9（低於此值＝預篩無效益，考慮回退）；(b) 開機健檢
   無 `ArrayIndexOutOfBoundsException`（含 `connectionAdded`＝512→256 界限分析被推翻，立即
   uninstall）；(c) 載具行為不變：上下車、駕駛、乘客、殭屍隔車不可見；(d) fps-dip-sampler
   新 dump 中載具主題（getIntersectPoint/getLocalPos/releaseVector3f/serverUpdate）佔比應從
   ~29% 顯著塌陷——這是第二波（P2/P3/P5）的立案量測；(e) `anomalies` 持續增長＝script null
   或幾何異常頻繁，需調查。
10. **PZ 更新**（順序不可調換）：
   1. **更新前先 `uninstall.sh`**——loose class 不在 Steam depot 內，`app_update` 只換 jar
      **不會刪掉它們**，殘留的舊 patched class 仍會覆蓋新 jar。同源閘只擋重新安裝，擋不住殘留。
      本伺服器的 update／monitor cron 是全自動的，**沒有人工介入視窗**，得知新版就要立刻執行。
   2. 重拉 jar → `build.ps1`。
   3. **命中數守門通過 ≠ 座標仍有效**——守門只數數量、不驗語境。常數手術必須另外用 `javap`
      確認該常數的前後指令（見 2b 的 42.20 實例：`respondToSound` 內仍剛好有一個 `20.0f`，
      舊座標會通過守門卻改到逃跑距離）。redirect 手術則確認 owner／method 未被搬家
      （見第 1 節 42.20 實例：consistency log 搬到 `INetworkPacket.logInconsistentPacket`）。
   4. 逐項語境驗證通過後才重新部署，並回到本清單第 1 項重跑開機健檢。
