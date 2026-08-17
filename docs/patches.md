# 優化項目與原理詳解（42.20.3）

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

## 1. 抑噪類（8 項）——為什麼值得做

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
| 8 | GameServer.sendToxicBuilding | `Send Toxic Building at [ ... , ... Toxic: ... ]`（前綴 startsWith） | MOD PSR（Plysken Solar Revolution）週期呼叫 `IsoBuilding.setToxic` 導致建築隨機刷新毒氣狀態，MP 廣播給全部玩家 | **只攔 log，不動廣播封包**；client 端自會標記室內有 generator 的建築、不通知 server |

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
- `TIntIntHashMap` 維持 Trove 預設 auto-compaction（factor=loadFactor=0.5，每 0.5×size 次
  remove 壓實一次，攤提 O(1)/remove）。**不可停用**：Trove remove 只留 REMOVED 墓碑，壓實是
  唯一主動且有界的回收機制。初版曾設 `setAutoCompactionFactor(0.0F)` 換取「同一波 unload 不
  反覆 rehash」，結果數小時載卸攪動讓墓碑飽和、FREE 槽耗盡，get/put 探測鏈退化成掃全表——
  2026-08-06 正式服主迴圈 15-25s 停頓、「Server is too busy」連發實案，thread dump 定罪後回退。
- identity hash 碰撞或同一 identity 重複時，該 hash 改走原版「由前往後、第一個 identity match」
  的線性語意；每次 fast remove 前仍用 `items[index] == value` 驗證。
- 偵測到 size/index 漂移時最多 rebuild 一次；再次不一致就失效 sidecar 並呼叫原版
  `removeValue`。`ordered=true`、`identity=false`、null 全都直接保留原版路徑。
- 鎖粒度是每個 Array；全域 weak registry lock 只包短暫查找／登記，不包 O(N) rebuild 或原始 add。

**驗證**：build 會確認四個原呼叫歸零且四個 helper 呼叫各恰一、helper/inner class 不含
`IdentityHashMap` 或 entity 強參照；另跑 tail-swap 等價性、missing、ordered/equality/null、
size/same-size/index 漂移、duplicate/hash collision、不同 Array 並行與 deterministic stats，
以及墓碑攪動回歸鎖 `churnKeepsTombstonesBounded`（4096 live×20480 循環，反射計數 Trove
REMOVED 槽，斷言 maxRemoved≤3072＋rebuild/linearScan/fallback=0；負對照實測停壓實時
maxRemoved=6500 必紅）。
尺度 benchmark 固定 N=1024/2048/4096/8192、3 輪 warmup＋7 輪中位數，輸出 add、first remove、
full remove、ns/entity、倍增比與可用時的 thread allocation；時間只作報告，不設機器相依 pass/fail
門檻。

---

## 2h.（42.20.2 官方收編，退役）popman 共享 buffer 執行緒競爭修復（v3 buffer 隔離）

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
2. constChange——門檻 `52428800L`(50MB)→v1 `268435456L`(256MB)→v1.1 `1073741824L`(1GB)→**v1.2 `4294967296L`(4GB)**。**門檻語意(codex 對抗審查實驗修正)**:這是「已解碼未上傳」
   pixel buffer 的水位,但 WrappedBuffer 走 LWJGL native malloc,**不受
   -XX:MaxDirectMemorySize 約束**,且門檻是配置前檢查、多 worker 可同時通過——
   天花板不是硬上限。**v1.1 實測依據(Tester-A 兩場 log,2026-07-31)**:水位「地板」
   因棘輪洩漏單調上升永不下降(50→125→154→263→273MB 釘死),~35 分鐘追上 v1 的
   256MB 天花板→全部載入執行緒永久睡(~194 樣本/s)→隱形回歸。code 級洩漏點=
   ImageData 解碼例外路徑無 dispose(ctor 分支+APNG 迴圈中斷洩 compositeBuffer+
   getData() 64MB 懶配置)。**天花板只買時間,任何上限終被地板追上**;**v1.2 實測依據(Tester-A console(12),2026-08-02)**:開往路易斯的內容洪峰讓地板數分鐘
   暴增 550MB＋、單趟吃掉整個 1GB 天花板(floor 終值 1096MB>1024MB=管線永久死亡,
   只剩輪胎與影子)——**洩漏/常駐量與「看過的新內容量」成正比,非時間**;4GB 亦僅是
   更寬跑道(非硬上限、無時間保證),根治=ImageData dispose 修補(規劃中)。洩漏為 process 級 static,relog 清不掉
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
(本次連續超標已持續毫秒數)才是持續停擺的證據;patchedStallSamples>0＝4GB
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
全序鎖(observed→4GB→lcmp→ifle)、sleep(20) 迴圈保留、helper 門檻常數與 bytecode
常數連動、真實 allocate/dispose passthrough smoke;LoadCheck client 模式(-Xverify:all
＋簽名/常數連動);BytecodeVerify;TexturePipelineGuardBehaviorTest(真實
DirectBufferAllocator 真實配置驗 passthrough/50MB 跨越/dispose 歸零,1GB 門檻與
floor/periodic/優先序狀態機以反射 observe() 合成值驗證——不需 1GB 真實配置)。
部署後觀測:console.txt 搜 `TexPipelineGuard`,`active` 行＝生效;隱形復發時
對照 `vanillaStallSamples` 與 relog 時點即可對帳因果鏈。

### 2j-v2.0 洩漏根治第一波（S1/S2/S4/S6）

**定罪**(四路 retention trace＋對抗評審,全數源碼核實):1096MB 洩漏地板＝
主犯 1(40-60%)**ImageData.dispose() 漏 frames**——APNG 動畫貼圖每幀全尺寸
buffer,dispose 只釋放 data＋mipMaps,零例外零 log 確定性洩漏=110MB 雙機基線主體;
主犯 2(20-35%)**getData() 固定 67108864(64MB)fallback**(不看實際尺寸)＋
mip-flag APNG 因 getMipMapCount()==0→getMipMapData(-1) AIOOBE 跳過上傳尾端
dispose,單發漏 64MB+mip 鏈+全幀(=+64/+99MB 大跳);從犯=cancel 丟棄(5-15%,
掛證據門檻待遙測)＋setImageData 覆寫釘死(3-10%,第二波)。**「上傳後不釋放」
主路徑假說不成立**——generateHwId 尾端有 dispose,地板全來自旁路。

**手術**(新手術型 head-call:visitCode 後插 `aload_0; invokestatic helper` 純線性
無分支,visitMaxs 取 max(原值,1);helper `zombie.core.textures.MinidoracatTextureLeakGuard`
必須同套件——frames 為 package-private):
- **S1** dispose() head-call `disposeFrames`——迭代 frames 逐幀 dispose(isDisposed
  冪等閘;WrappedBuffer 雙重 dispose 拋 ISE)後 clear。安全論證:frames 讀取者全
  codebase 僅 AnimatedTextureID.setImageData(dispose 前執行且轉移後 frame.data=null)
  與 ImageData(ImageDataFrame) ctor。
- **S2** getData()/getMipMapCount() head-call `ensureData`——data==null 時以
  getWidthHW×getHeightHW×4 實際尺寸配置(64MB 分支成死碼),APNG 以第一幀內容填充
  (原版上傳全零=隱形,修後顯示第一幀=紅利);getMipMapCount 回真值→AIOOBE 家族
  歸位走完正常 dispose。壞檔(尺寸非正)不介入退回原版。呼叫者普查:getMipMapCount
  僅 2 內部呼叫者(即修復路徑);getData 讀取全以 w*h*4 為界。
- **S4** TextureID.createSteamAvatar 內 redirect `createSteamAvatarFixed`(唯一
  呼叫點)——逐語意重實作,失敗/例外路徑補 dispose(原版漏 65536B 且 UI 重試重漏)。
- **S6** TextureID.freeMemory() head-call `onFreeMemory`——原版只斷引用不 dispose
  (假釋放 footgun;42.20 零呼叫者,防禦性堵口)。

**驗證**:命中守門 7(head-call×4+redirect×1+v1.2 兩刀);SmokeCheck vanilla 前提
守門(dispose 零觸碰 frames=TIS 未自行修復、getData 恰一個 64MB 常數、freeMemory
純斷引用、avatar 呼叫恰一)+head-call 全序鎖+redirect 歸零+MipMapLevel.dispose
呼叫數不變;行為測試(同套件直接存取 frames):幀對帳歸零、dispose 冪等、實際尺寸
配置、第一幀內容填充、壞檔 fallback,全部對真實 DirectBufferAllocator;
install/uninstall.bat 改為 payload 逐檔生成閘門(5 檔:來源預檢/衝突/回驗/回滾/ownership 移除),fake-gamedir 手動 roundtrip 通過(未進 build gate)。
**預期**:入服基線 110MB→<15MB、路易斯 +550MB→趨近 0、8hr 1096MB→<100MB,
地板穩定低於原版 50MB 閘門=隱形窒息路徑關閉(4GB 門檻降為第二道保險)。
殘餘風險(文件化):未稽核的大量寫入路徑最壞=BufferOverflowException 有界落 log;
createSteamAvatarFixed 裸 JVM 不可測(結構鎖+人工 QA);ensureData 的 lazy-init
競態與原版同形不加劇。

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

### 2k-2.（42.20.2 官方收編，退役）VehicleManager.connected 512→256

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

## 2m.（42.20.2 官方收編，退役）效能第二波 P5：IsoCell 三清單 identity membership sidecar

**立案**：第一波後低谷頻率一度塌陷至 1–2 次/日，但 2026-08-03 晚間人數衝上 80（新高）後
單晚觸發 6 次、FPS 探至 6.1，且 chunk 卸載主題重回榜首（新 dump 3/13＝23%，累計
post-第一波 6/22≈27%）——達到封存時寫死的解封條件（主題 ≥30% 或頻率 >5 次/日）。

**根因**：`IsoCell` 三個排程清單都是 `ArrayList`，熱路徑全是 O(N) 線性掃描且幾乎全 miss：

| 路徑 | vanilla | 改後 |
|---|---|---|
| `addToProcessIsoObjectRemove`（每個卸載物件） | `P.contains` O(P) ＋ `R.contains` O(R) | O(1)＋O(1) |
| `IsoObject.removeFromWorld` 的 `S.remove` | miss 全掃 O(S) | miss O(1)；hit 保留 vanilla |
| `addToProcessIsoObject`（載入側） | `R.remove` O(R)＋`P.contains` O(P) | O(1)＋O(1) |
| `ProcessIsoObject` 每 tick `removeAll` | O(P×R)；R=0 仍 O(P) | O(P+R)；R=0 為 O(1) |

**手術**：15 個 `INVOKEVIRTUAL java/util/ArrayList` 呼叫點（javap 定案——原稿誤記 12，漏數
`addToProcessIsoObjectRemove` 與 `setReanimateTime` 各自的雙 contains）改道
`CellListMembership` 的六個 helper，跨三個 class：`IsoCell`(10)、`IsoObject`(1)、
`IsoDeadBody`(4，經 getter 的旁路變異者，不鏡射必失同步）。

**關鍵設計決策**（v1→v2 重寫，Claude 6 項 important ＋ codex REDESIGN 五雷）：

- **不變量是 identity 集合而非 size 對等** —— 清單可含重複元素；`remove` 成功後以
  `list.contains` 複核才除名（codex 雷 1：否則 `[x,x]` 移一份就永久 false-negative，
  且 size 對帳永遠抓不到）。
- **generation bundle 取代 weak registry** —— codex 指出 `State.set→IsoObject.table`（Lua table）
  可反向釘住弱鍵使其永不釋放；改以 `IsoCell` identity 錨定，換代整組替換，零 GC 猜測。
- **removeAll 嚴格 gate＋尾端逐刪** —— 非 `ArrayList.class`／null 一律原生（NPE 與 subclass
  的 `c.contains` 副作用 parity 交給原生）；R 用固定大小索引快照（非 iterator，不引入 vanilla
  沒有的 CME 面）；尾端 `remove(i)` 每次 `modCount++` 精確還原 JDK `batchRemove` 語意
  （`subList.clear()` 只加一次，不等價）；例外則毒化 `expectedSize` 後重拋，不半提交。
- **kill 門檻只算 audit divergence** —— size 對帳 rebuild 只觀測不計（GO-WITH-FIXES：
  重度 MOD 環境的 Lua 良性旁路會自癒，不該累積成永久停用）。門檻 8 次，terminal。

**驗證**（18 個斷言）：8 個行為 differential（400 op 隨機序列含重複與 null、重複元素感知、
等大小換血 ghost 自癒、20 次 size 漂移不 kill、divergence 達門檻永久 kill、未知清單降級、
removeAll 四情境、補償迭代重入的訪問序列黃金比對）＋10 個結構斷言（六方法改道計數與原呼叫
歸零、S3 負對照 `size×2/get×1` 原樣、S4 負對照 `ProcessStaticUpdaters` 零改道、S5 六個
contains 後綴必為 IFNE/IFEQ、全 jar hierarchy walk 斷言 IsoObject 全後代零 equals/hashCode 覆寫）。

## 2n.（2026-08-08 退役：client 端無對應改道）受精蛋世界清除豁免（IsoGridSquare.load）

> **退役結論（2026-08-08）**：patch 本身有效，**退役原因是 server-only 改道在此路徑必然
> 產生玩家端 desync**，不是失效。以下原始設計全文保留，作為「動 client 也會跑的判定路徑」
> 的教訓案例。
>
> **生效證據**：正式服 log（`[MinidoracatJavaPatch][EggGuard]`）本次啟動 `keptLoads=3649`、
> `expiredLoads=0`、`anomalies=0`，累計 1678 行 kept、738 顆不同的蛋；單顆蛋（`5713,6446`，
> `dropTime=3770.763916015625`）的 `progress` 跨多次 chunk 卸載／重載由 557 推進到 1121/1260，
> 全服最高 1148/1260 —— server 端一顆都沒被清掉。
>
> **為什麼還是退役**：`IsoGridSquare.load` 的清除區塊沒有 `GameClient.client` 守衛，而
> `SandboxOptions`（含完整 247 項 `WorldItemRemovalList`）由 server 在連線握手時就完整同步給
> client（`ConnectionDetails.writeSandboxOptions` → `GameClient` 端 `SandboxOptions.load`
> 逐項覆蓋）。client 載入 chunk 的兩條路徑（收 server chunk 封包、讀本地 MP 快取）都走同一個
> `IsoChunk.LoadFromDiskOrBufferInternal` → `gs.load()`，判定條件與 server 一模一樣卻沒有
> guard，於是**每次載入都自行把蛋濾掉**。判定是 item 狀態＋世界時鐘的純函數，輸入沒變結果就
> 不變——重連、重開遊戲、清本地快取都一樣，蛋在玩家畫面上永遠不再出現，**也無法撿起**
> （client 的 square 上根本沒有那個物件），只能等它孵成小雞。玩家實際回報的正是這個現象。
>
> **決策**：回歸原版行為（受精蛋照 24 遊戲小時清除），引導玩家把雞養在雞舍（`IsoHutch`）
> 下蛋——雞舍內的蛋不是 `IsoWorldInventoryObject`，本來就不經這條清除路徑。改道、helper、
> LoadCheck 簽名守門與 SmokeCheck 13 條斷言一併移除，`IsoGridSquare` 回歸原版位元組。
>
> **通則**：server-only patch 若落在 client 也會執行、且沒有 `GameClient.client` 守衛的判定
> 路徑上，必然產生視覺／互動 desync。動手前先確認守衛存在，否則只有兩條路——連 client 一起
> 改，或從設定層解決。

**立案**：正式服 `WorldItemRemovalList`（247 項）含 `Base.Egg`。查證後確認清除判定
**無法區分受精蛋**，而現行參數讓地上的受精蛋 100% 在孵化前被刪除。

**根因鏈**（三段都有 javap／原始碼佐證）：

1. 判定只吃字串。`IsoGridSquare.load` offset 423-426 取 `worldItem.getItem().getFullType()`，
   之後全部比對都走 `SandboxOptions.worldItemRemovalListContains`＝`worldItemRemovalSet.contains(type)`
   （SandboxOptions.java:1305-1313），純 exact match，**看不到任何 per-instance 狀態**。
2. 受精是實例欄位不是型別。`Food.java:126-131` 的 `fertilized` / `fertilizedTime` /
   `timeToHatch` / `animalHatch` 全是實例欄位；受精蛋與一般蛋同為 `Base.Egg`——
   `ChickenDefinitions.lua:152` 的原版註解就寫明「can be fertilized or not, depend if a
   rooster mated with the chicken or not」。
3. 時間差 52 倍。

| 參數 | 值 |
|---|---|
| `HoursForWorldItemRemoval` | 24 遊戲小時 |
| 母雞 `timeToHatch`（ChickenDefinitions.lua:153） | 21×24 = 504 小時 |
| `AnimalEggHatch = 5` → `Food.setTimeToHatch` 乘數 | ×2.5 |
| 實際孵化需時 | **1260 小時（52.5 天）** |

`Food.checkEggHatch` 的 `else` 分支（hutch==null 且不在容器時 `baby.addToWorld()`）是原版
明確支援的「地上孵化」路徑，被清單設定切斷。

**手術**：改道 `load(ByteBuffer,int,boolean)` 內**唯一**的
`IsoWorldInventoryObject.isIgnoreRemoveSandbox()Z`（全 class 恰一處，位於
`aload → ifne → getstatic GameTime.instance` 的清除判定鏈上）到 `FertilizedEggGuard`。
INVOKEVIRTUAL→INVOKESTATIC 同形替換，堆疊 1→1、指令長度不變、frames 不動。

**為什麼選這個 callsite**（四路審查獨立驗證，替代方案全部不可行）：

| 替代方案 | 為什麼不行 |
|---|---|
| `getFullType()`（offset 426） | 回傳值同時餵給 `ScriptManager.FindItem`＋`getObsolete` 判定、`type.split("_")` 前綴分支與四個 list 比對。改它等於**偽造 item type**，blast radius 大好幾倍 |
| `worldItemRemovalListContains`（4 處） | receiver 是 `SandboxOptions`、參數是 String，**拿不到 `worldItem`**；且要同時處理黑白名單四個分支才不破壞對稱 |
| `getWorldAgeHours()`（offset 600） | receiver 是 `GameTime`，**拿不到 `worldItem`** |
| head-guard 插入 | `load` 是巨型方法，依手術鐵則需 `EXPAND_FRAMES`＋補 `F_SAME`，本專案明訂保留給防崩潰守衛 |

offset 589 的 `aload 13` 是整條清除鏈上**唯一一個 `worldItem` 在堆疊上、且位於黑白名單分支
之後**的位置。且 `isIgnoreRemoveSandbox` 的語意本來就是「豁免 sandbox 清除」，擴充它是語意
延伸而非行為改寫。代價是：任何有界化邏輯**只能寫在 helper 裡**，因為沒有第二個 callsite
可以表達「延長期限」而非「永不清除」。

**效能**：四個 `listContains` 都在 589 之前且以 `&&` 短路，所以 helper **只在該 item 已命中
清除清單時才被呼叫**，不是每個 world item 都跑；而同路徑對每個 world item 本來就會跑
`type.split("_")`（regex＋陣列配置）＋`ScriptManager.FindItem(type)`，成本高出 helper 數量級。

**豁免範圍刻意收斂**：`fertilized` 且 `animalHatch` 非空（與 `checkEggHatch` 的孵化 gate 用
同一個 `StringUtils.isNullOrEmpty`）——孵不出來的「受精」蛋不佔額度。容器／背包／雞舍
(`IsoHutch`)／動物拖車內的蛋本來就不是 `IsoWorldInventoryObject`，不經此路徑。火雞蛋是
`Base.TurkeyEgg`、不在清除清單內，本節只影響 `Base.Egg`。

### 2n-1. 為什麼一定要有孵化視窗天花板（初版被審查推翻的論證）

初版寫「冷卻／烹煮／冷凍會由原版把 `fertilized` 設回 false，所以豁免自動失效、不會永久堆積」。
**這個論證對地上物品是錯的**——三條路徑全部被容器閘擋住：

| 宣稱的失效路徑 | 實際 gate | 地上物可達？ |
|---|---|---|
| 烹煮 `heat>1.6`（Food:384） | 整段包在 `if (outermostContainer != null)`（Food:377）內 | **否** |
| 冷凍（Food:840） | 需 `isInFreezer(outermostContainer) && isPowered()` | **否** |
| 冷卻 `heat<0.5`（Food:634） | `temp = outermostContainer == null ? 1.0F : ...`，地上 temp 恆為 1.0，heat 被兩側 clamp 收斂到剛好 1.0 | **否**（只有「剛從冷凍庫拿出來就丟地上」的邊角會觸發） |

根因是 `IsoWorldInventoryObject` 建構子對 item 做 `setContainer(null)`，而
`InventoryItem.getOutermostContainer()` 對 null／`type=="floor"` 的 container 一律回 null
⇒ **地上物品的 `outermostContainer` 恆為 null**。補刀：`isRotten()` 對 fertilized 恆回 false，
腐敗也清不掉。

唯一真實的出口是孵化，而 `fertilizedTime` **只在該 chunk 載入期間推進**
（`processWorldItems` 由 `addToWorld` 填、`removeFromWorld` 清）——所以**少載入的 chunk 上，
受精蛋既不孵化也不再被清除，就是永久豁免**。

因此有界性不能靠推論原版行為，改由 helper 自己保證：

```java
// 豁免視窗＝dropTime + HATCH_WINDOW_MULTIPLIER(4.0) × timeToHatch（世界小時）
// dropTime==-1（無法定界）或 timeToHatch<=0 一律不豁免，交還原版清除
```

取 4 倍是因為 `fertilizedTime` 只在載入時推進、世界時鐘遠快於它——常載入的基地 chunk 綽綽有餘，
永不載入的 chunk 則保證被回收。母雞 1260h ⇒ 視窗 5040 世界小時（210 遊戲日）。
這也是本 patch 唯一的可調旋鈕（`LoadCheck` 斷言它必須是有限正數，`SmokeCheck` 與它連動）。

**已知取捨：累積的成本在主執行緒，不在存檔。** 被留下的每顆蛋都成為 `processWorldItems` 的
常駐 updater，而 `IsoCell.addToProcessItems` 是 `ArrayList.contains` 線性掃描——2m 的
`CellListMembership` sidecar **沒有覆蓋** `processItems`/`processWorldItems` 這兩張清單。
天花板把它變成有界，但仍需按下方第 10 項的門檻盯著。

**已知取捨：client 不改。** `IsoGridSquare.load` 的清除區塊沒有 `GameClient.client` 守衛，
而 client 在 MP 也存本地 chunk 快取、`SandboxOptions` 由 server 同步——所以可能出現
「伺服器留著蛋、玩家端本地載入時把它從 square 刪掉」的視覺 desync（小雞看似憑空出現）。
無資料遺失，**但驗證時不能只靠遊戲畫面目視**，見下方第 10 項。

**驗證**（SmokeCheck 13 條＋LoadCheck 簽名/常數守門）：vanilla 前提守門（`isIgnoreRemoveSandbox`
恰一＋4×`listContains`＋1×`getWorldAgeHours`，TIS 改寫 `load` 時建置失敗而非默默錯位）、
改道恰一＋原呼叫歸零、清除鏈其餘判定未被動（負對照）、**位置全序鎖**
（`aload → guard → ifne → getstatic GameTime.instance`）、**分支方向鎖**（guard 回 true 的
去處必須等於清除鏈尾端「未過期＝保留」的去處——若 TIS 把語意反轉成「要清除」，指令形狀與
命中數完全不變，只有這條擋得住）、**匯流鎖**（四個 `listContains` 分支中判定為命中清單的三條
必須全部落到 guard，3/3）、helper 先取 vanilla 值恰一次、static 欄位僅 primitive＋log 前綴，
加五組行為 smoke（委派 vanilla／判定六種輸入／孵化視窗四象限／端到端＋log 分支確實執行／
**全程零 anomalies**——沒有最後這條，期望 `false` 的斷言在「helper 全程吞例外」時也會綠燈）。
逐項 javap 證據見 `docs/specs/zombie_iso_IsoGridSquare.json`。

## 2o. Client chunk 串流觀測（v2.1→v3.0，黑邊事件鑑識）

**動機**：2026-08-11 同一玩家兩起「黑邊」實案（凌晨 3 點卡死 10 分鐘不可恢復需重開遊戲、
早上 7 點卡 5 分鐘自癒）。server 端同時段實測全綠（tick 正常、該 client 持續收到 Toxic
廣播＝網路活著），卡點在 client 串流管線；但事故 log 被翻譯警告洪水沖掉（另案修復於
MinidoracatLangFor42），無法定位是哪一環。本 patch **純觀測不改行為**，為下一次發作留證。

**觀測對象**（`zombie.iso.WorldStreamer`，簽名與私有欄位已 javap 對真 jar 驗證）：
請求生命週期 sendRequests→pendingRequests1＋mainThreadRequestQueue→updateMain 發
RequestZipList→sentRequests→receiveChunkPart/receiveNotRequired 配對 requestNumber→
loadReceivedChunks 完成。**待驗假說**：(a) `requestingLargeArea` 期間 `pendingRequests1>20`
時 sendRequests 頭部 gate 完全停送新請求；(b) server 端 `ClientChunkRequest.getRetryChunk`
重試 ≥3 次回 null＝永久放棄該 requestNumber——兩者疊加＝pending 永遠清不掉、新請求全面
停擺＝黑邊永不恢復。開大地圖觸發 largeArea 模式，與「黑邊時大地圖也打不開」的症狀吻合。
**42.20.3 起假說 (b) 失效**：重試機制（`getRetryChunk`／`retriesCount`／
`MAX_CHUNK_SEND_TRIES`）整個刪除，未生成 chunk 改由 pending 機制＋`ChunkNotReady` 封包
主動告知 client（見 2p 遷移記錄）——重評 W4-2 時勿再以 (b) 推理。

**手術**（三處 headCall，全部 receiver-only `(Lzombie/iso/WorldStreamer;)V`，
helper `zombie.mdc.ChunkStreamObserver`）：
- `updateMain()V`：心跳。每 10 秒視窗才反射讀佇列水位（平時每幀只做一次時間比較）；
  卡滯判定：有未完成請求且 >30 秒零接收→`STALL noReceiveMs=…` 行（每 10 秒至多一行，
  含全部佇列水位＋largeArea 旗標）；常態每 60 秒 periodic 行（無活動不報，單機安靜）。
- `receiveChunkPart`／`receiveNotRequired`：接收計數＋lastReceive 時戳
  （黑邊期間計數凍結＝斷流方向的直接證據）。

**安全論證**（codex 對抗審查修正兩處後定稿）：headCall 於方法首指令前插入
`aload_0; invokestatic`（進入點堆疊為空，參數與 locals 不動）；helper 非 fatal 例外
一律吞（fatal 照拋）。**執行緒模型**：receive 三掛點由 UdpEngine 網路執行緒呼叫
（經 GameClient.addIncoming）——與主執行緒**零共用鎖**（審查抓到初版共用 class
monitor 會讓封包處理被主執行緒的反射/組字串卡住＝改變行為），receive 路徑只做
AtomicLong 遞增＋volatile 時戳，決策狀態全部主執行緒單獨持有。**STALL 雙基準**：
outstanding 上升沿與最後接收都 ≥30 秒才報（審查抓到單基準會在「閒置數分鐘後剛發
新請求」時假報）；基準污染以「心跳斷檔 >30 秒＝重置」防護（Claude 審查修正：
不持有 WorldStreamer 參考——static 強參考會釘住退役實例的 IsoChunk 串列＋native
Inflater＝改變行為；斷檔法同治 relog／in-place 重連／凍結三情境）。反射欄位漂移→
一次性 disabled 宣告後永久降級僅計數。SmokeCheck：vanilla 錨定（updateMain 觸碰
`GameClient.connection`＋零既存 observer 呼叫）＋四 headCall 全序鎖＋receiveChunkPart
原體保留（sentRequests 觸碰數不變）＋**八個反射欄位的名稱/型別契約守門**（漂移＝
建置失敗而非默默降級）。行為測試全時間注入：STALL 雙基準/節流、閒置後新請求不假報、
實例更換重置、靜默抑制、復原。

**判讀指南（42.20.3／v3.0 版；舊版 (a)+(b) 假說判讀已隨重試機制刪除失效）**
（注意 `reqQ0` 計的是 chunk **串列頭**數——每個元素是 `chunk.next` 串起的整條清單，
`reqQ0=1` 可能代表 1 也可能代表 200 個 chunk，展平後才進 `reqQ1`）：
- `STALL … notReadyAgoMs=` 小值（秒級）且 periodic 的 `notReady=` 持續上升 →
  server 活著但一直回「沒生成好」＝**生成端瓶頸**（42.20.3 pending 機制的 30s 生成
  逾時／4096 超限路徑），去 server 端查 `the chunk %d,%d was not generated` 警告與
  世界生成負載；不是斷流。
- `STALL … notReadyAgoMs=-1`（或大值）＋parts 凍結 → 連 NotReady 都沒有＝**全斷流**
  （網路層/連線問題），比對 server 端該連線的發送狀態。
- `STALL … pending1=20 largeArea=true` → 假說 (a)（largeArea 停送 gate）候選，
  另收集 largeDl 序列驗證；假說 (b)（server 重試放棄）42.20.3 起不存在，勿再引用。
- `STALL` 但 parts 持續增加 → 接收活著、載入端（DoChunk/refs）卡住，另闢分析。
- 無 `STALL` 行但玩家見黑邊 → payload 仍在到達：卡點在 WorldStreamer 之外
  （IsoChunkMap/渲染層），或 chunk 被 NotReady 移出追蹤後 client 遲未重請求
  （對照 periodic 的 `notReady=` 與 `sent=` 變化）。

**v3.0（42.20.3 重建＋三 lane 對抗審查修正，2026-08-17）**：三 headCall 錨點與八個反射
欄位逐一重驗健在；**擴充第 4 headCall `receiveChunkNotReady(I)V`**——42.20.3 新協定中
server 對未生成/超限 chunk 的主動回覆。vanilla 完整語意（javap 實證）：drain
sentRequests→pendingRequests 後，把 flagsWs&1 與相符 requestNumber 的 entry **移出
pendingRequests**（flagsUdp|=16/24）＝該請求移出追蹤、pending 水位下降，不是留隊重排。
**獨立基準設計**：hook 只更新 lastNotReadyNs、不碰 payload 基準（lastReceiveNs）——
STALL 維持「30 秒無 payload」語意，生成瓶頸（server 短週期持續回 NotReady）不被靜音；
STALL 行帶 `notReadyAgoMs` 分型（初版「NotReady 也算接收」設計會讓新協定最可能的黑邊
形態永遠不觸發 STALL，Claude lane 抓到後改為雙基準）。四 headCall 全序鎖＋新協定方法
存在性 census 進 SmokeCheck；行為測試補獨立基準/分型與 notReady-only periodic 案例。
**lowmem 變體（v3.0-lowmem）**：≤8GB RAM 機器（42.20.3 隱形實證玩家 8101MB＋Xmx3G）
不適用 4GB native 天花板——Patcher 顯式 `client-lowmem` mode：不做 constChange、redirect
指向 `bytesAllocatedObservedLowMem`（effective 門檻 50MB 烘進 helper，橫幅與 stall 分類
以實際生效值計），觀測與洩漏根治線全保留。

## 2p. chunk 供給併包（W4-1，server）＋請求逾時 8s→15s（W4-2，client）

**根因**（八路鑑識＋對抗驗證；完整設計見 `docs/chunk-throughput-design-v1.md`）：
vanilla 的 chunk 供給只跑到設計值的 15%——client 每幀送一包 `RequestZipList`（約 3 chunk）→
`RequestZipListPacket.parse` 每包無條件 new 一個 `ClientChunkRequest` 入列（從不併入未滿的
ccr）→ `PlayerDownloadServer.update()` 每 worker 週期只處理一個 ccr（10Hz）＝實際約
30 chunk/s，而 `NON_LARGE_AREA_CHUNKS_LIMIT`=20 × 10Hz = 200 chunk/s 的預算浪費 85%。
積壓越過 client 的 8 秒逾時後，`resendTimedOutRequests` 設 `flagsWs|=9` →
`loadReceivedChunks` 丟棄**已送達**的整包資料並重新排隊、且不通知 server 取消 →
自我維持 livelock（實測 pending 恆＝請求率×8s＝240、18 分鐘重發約 141 輪、
燒掉約 105MB 全丟棄、零 chunk 載入＝永久黑邊）。`PlayerDownloadServer` 是
per-UdpConnection daemon thread，故只有該玩家卡、server 全域指標全綠、同安全屋別人正常。

**W4-1 手術**：`PlayerDownloadServer.removeOlderDuplicateRequests()V` 頭部 headCall →
`zombie.mdc.ChunkRequestPacker.packQueue`，把佇列前段併包到批次上限。
**掛點不是 `update()V`**（審查抓到的 blocking；以下為 42.20.2 當時的分析，42.20.3 現況見
下方遷移記錄）：`update()` 對 `ccrWaiting` 的存取全包在 `if (workerThread.ready)` 內，那是
與 WorkerThread（42.20.2 的 `sendArray` 會 add `ccrForRetries` 並持續 `chunks.add`；42.20.3
起 worker 已不寫 `ccrWaiting`）互斥的唯一機制；插在 offset 0 會落在閘外，最壞情況是同一
`Chunk` 實例雙重 `releaseChunk` 進 **static** `freeChunks` 池＝跨玩家汙染。
`removeOlderDuplicateRequests` 全 class 僅被 `update()` 呼叫一次（javap 實證）且就在閘內、
vanilla 去重之前。

去重語意保留：vanilla 只偵測跨 ccr 重複，故隊首已含同 `(wx,wy)` 者跳過不搬，留給 vanilla
去重原樣處理。搬空的 ccr 由同一方法後段的 vanilla 本體移除並回收進物件池。largeArea 不介入。

成本閘：批次上限預設 **8**（vanilla 上限 20 的 40%）、全域每 100ms 視窗「額外搬移」預算
預設 **120**，皆可用 `-Dmdc.chunkPacker.batch` / `-Dmdc.chunkPacker.windowBudget` 調整
（後者設 0 即整刀停用，等同 vanilla，**緊急降級不需重新部署**）。

**W4-2 手術（42.20.3 已撤刀）**：`WorldStreamer.resendTimedOutRequests()V` 的 `8000L`→`15000L`
（方法內常數替換，全 class 僅此一處）。`RequestZipList` 與 `SentChunkPacket` 皆
`reliability=2`（RELIABLE），故此逾時幾乎不是在救真的遺失，而是在懲罰 server 慢。
**42.20.3 起 vanilla 整個刪除該方法**（盲等逾時重發由 `ChunkNotReady` 主動通知根治）——
手術目標不存在，撤刀；SmokeCheck 的 W4-2 雙向常數斷言同步移除。

**驗證**：SmokeCheck——vanilla 前提（`update` 恰 3 個同簽名 `List.remove(I)`＋1 個 dedupe
呼叫；`resendTimedOutRequests` 恰 1 個 8000L）、**掛點在 ready 閘內**（dedupe 頭部全序 ＋
`update()` 內零 packer 呼叫，把 B1 鎖進建置期）、update/dedupe 雙邊原體保留、三個 public
欄位契約、`isChunksFilled` 的 `bipush 20` 綁定（TIS 調小而我們沒跟＝超發）、W4-2 常數雙向
斷言。行為測試 8 案：守恆／批次上限／去重保留／largeArea 雙向／順序／退化輸入／
上限不超過 vanilla／視窗預算封頂。
**42.20.3 遷移記錄（2026-08-17）**：TIS 同戰場重構（修「Loading Map forever」）——
pending 機制（`PendingChunk`≤4096／`OutOfRangeRequest`≤1024／新封包 `ChunkNotReady`）、
**server 重試機制整個刪除**（`Chunk.retriesCount`、`MAX_CHUNK_SEND_TRIES`、`getRetryChunk`
移除）、worker 回填改 `queuedByWorker` concurrent queue＝WorkerThread 不再寫 `ccrWaiting`
（掛點互斥前提更寬鬆，掛點不動）。`update()` 呼叫序變為 ready 閘 → `updatePendingChunks()`
→ dedupe（掛點）；pending 回填的 ccr 是普通 non-largeArea ccr，被併包安全。**吞吐瓶頸未修**
（`RequestZipListPacket` 逐位元相同、每 tick 仍一個 ccr）＝W4-1 存續；官方 changelog 自承
黑邊「additional causes 仍在調查」。client 側：`WorldStreamer` 被實質重構——**W4-2 撤刀**
（目標方法 `resendTimedOutRequests` 已刪除）；v2.2 包全面失效，**已以 v3.0 重建**
（觀測線重驗健在＋擴充第 4 headCall `receiveChunkNotReady(I)V`——獨立基準 lastNotReadyNs
計數新協定回覆、STALL 維持無 payload 語意並以 notReadyAgoMs 分型；texture 線三 class 逐指令
相同原樣沿用；42.20.3 client/server jar 整檔 SHA 實測相同 `bda809fb…`，install 同源閘直接
有效）。SmokeCheck 的 retriesCount 斷言隨 vanilla 刪除。
完整分析：docs/report/pz-42.20.3-update-analysis.md。


## 2q. 容器環防崩潰守衛（W5，server）

**事故**：2026-08-13 21:31:10 正式服主迴圈死於 `java.lang.StackOverflowError`，堆疊 1024 層
全部是 `ItemContainer.getCharacter` 自我遞迴。伺服器假死 13 分鐘（frame 凍在 f:54247）、
21:40 的 graceful `quit` 收不進去、看門狗 21:44 強制重啟。存檔在 21:30:10 成功、凍結在
21:31:10，故世界資料只掉約 1 分鐘，但玩家 21:31–21:44 的操作全部沒被 server 收到。

**vanilla 缺陷**：`getCharacter()` 沿「容器→裝著它的物品→該物品所在容器」爬升找擁有者，
**零迴圈偵測**；`ItemContainer` 全類別無防環檢查，`AddItem` 只擋同 ID 重複、不阻止把容器
放進自己的子孫。MP 封包驅動的搬移即可造出「A 裝在 B 裡、B 又裝在 A 裡」。

**環只可能是執行期產物**（故掃存檔找不到兇手）：`containingItem` 只在 `InventoryContainer`
建構時設定一次且不序列化；`InventoryContainer.save` 是巢狀遞迴寫入，若存檔內有環，存檔本身
就會先爆——而崩潰前 60 秒的 `World saved` 是成功的。

**手術**（兩處，皆為堆疊形狀不變的呼叫改道，method-scope 鎖定）：

| 方法 | 改道點 | 截斷語意 |
|---|---|---|
| `getCharacter()` | 唯一自身遞迴（javap offset 42） | 回 `null`＝查不出擁有者，與 vanilla 對「放在地上的容器」同值 |
| `isInCharacterInventory(IsoGameCharacter)` | 唯一自身遞迴（offset 52） | 回 `false`，與 vanilla 走完鏈的 fall-through 同值 |

第二刀是審查指出的「下一個最會炸」：`Transaction.getDuration()` 會呼叫它，而 `getDuration()`
**只在 server 端、於 `ItemTransactionPacket` 驅動的 `Transaction` 建構時執行**——正是造出環的
同一條封包路徑。helper `zombie.mdc.ContainerCycleGuard` 以 ThreadLocal 計深度（兩刀共用），
超過 `MAX_DEPTH`（預設 64，實際允許鏈長 65：第一層走 vanilla 不經 helper）即切斷。

**診斷**：切斷時走鏈印出環上的 containerId／itemId／fullType 與閉合點（走鏈有 128 步硬上限，
只用欄位讀取與 trivial getter，逐一驗證不會二次遞迴）。完整鏈每 60 秒至多一次、全場最多 5 次；
之後改印 **10 分鐘 trips 心跳**（環不會自己消失，運維不能在第一天後就看不到）。
helper 自身例外有界印出堆疊（前 3 次）。

**旋鈕**：`-Dmdc.cycleGuard.maxDepth=0` 停用兩把刀（回到 vanilla 的爆掉行為，stack 消耗約 2 倍），
免重新部署。

**⚠ 已知降級（必讀）**：`GameServer` 的五個庫存廣播
（`sendAddItemToContainer` / `sendAddItemsToContainer` / `sendReplaceItemInContainer` /
`sendRemoveItemFromContainer` / `sendRemoveItemsFromContainer`）對「巢狀在物品裡的容器」
只有三條分支：`getCharacter() instanceof IsoPlayer` → `getParent() != null` → **vanilla 寫成空的第三條**。
環上容器的 `getCharacter()` 回 null 且 `getParent()` 為 null，於是**封包完全不送、不 log**——
該容器的加/刪/換物品 client 端永遠收不到（玩家體感：東西憑空消失）。
這比 vanilla 的「整台死掉」好，但是一個**新的、靜默的、持久的**降級。同理
`ItemContainer.Remove` 的 `removeFromHands` 會被跳過（物品從容器移除卻留在手上）。
**因此本刀是止血＋捕手，不是根治。**

**根因仍待處理（W5-2，不可進 backlog）**：`AddItem`（`ItemContainer.java:458`）與
`AddItemBlind` 應在加入前拒絕成環。vanilla 自己已有現成述詞
`TransactionManager.chainContainsContainingItem(ItemContainer,int)`，而 `AddItem` 的
`containsID` 呼叫點（javap offset 11）堆疊恰為 `[ItemContainer, itemId]`，與該述詞簽名吻合。
**風險**：拒絕加入會讓 `AddItem` 回 null，若呼叫端已先把物品從舊容器移除，物品會憑空消失
——修 bug 修出物品遺失更糟，故需獨立的分析與審查循環。在 W5-2 落地前，**不可認定此類假死已排除**。
同一條鏈上另有 `isInside` 與 `InventoryContainer.save`（存檔路徑）仍無防環。

**驗證**：SmokeCheck——兩刀各自的 vanilla 前提（恰一個自身遞迴）、改道恰一次且原遞迴歸零、
**指令總數未變**（1:1 替換的結構事實）、原體保留（`getParent` 呼叫數與 `containingItem`
觸碰數不變）、全 class 負對照（其他 27 個呼叫端保持 vanilla）。行為測試 7 案＋kill switch 模式：
**vanilla 必爆負對照**（用 vanilla 爬升邏輯走同一個環必拋 SOE，證明環是真的）、守衛切斷回 null、
**正向回傳真實擁有者**（堵住「永遠回 null」的假 helper 假綠通道）、正常巢狀零觸發、
診斷指出閉合點、深度歸零不污染、門檻邊界（63 不觸發／66 觸發）。
測試以 `sun.reflect.ReflectionFactory` 分配未初始化物件造環（`InventoryItem` 建構子會拉起
ZomboidFileSystem），classpath 中 `dist\java` 排在 jar 前，故測到的是**改道後**的方法。

## 2r. 地圖格載入捕手（W6，server）

**事故**：2026-08-14 01:34:56 正式服主迴圈 frame 永久停在 `f:46186`，直到 03:28 排程的
mod 更新重啟才結束——**凍結 114 分鐘，而且沒有任何人是為了救它而重啟的**。進程活著、
Steam／Discord／網路執行緒照常，玩家連得進來但世界完全靜止（01:48「（玩家回報登不上）」、
01:59「（玩家互勸登出）」，期間 170 次斷線）。同一條**逐行相同**的 stack 在
2026-08-07 18:05 也發生過一次（兇手 sprite `fencing_01_57`；本次 `blends_natural_01_53`）。

**vanilla 缺陷**：`EngineEntityManager` 維護兩份平行結構——`entitySet`（「登記過了嗎」）與
`entities`（每圈要走訪的陣列）。`addEntityInternal` offset 0-8 是 `entitySet.contains(entity)`，
為真就在 offset 11-27 `athrow`：

```
java.lang.IllegalArgumentException: Entity is already registered <sprite>:zombie.iso.IsoObject@…
  EngineEntityManager.addEntityInternal(:137)   ← throw
  Engine.addEntity(:58) → GameEntityManager.RegisterEntity(:253)
  GameEntity.addToWorld(:527) → IsoObject.addToWorld(:4497)
  IsoChunk.doLoadGridsquare(:3973)
  ServerMap$ServerCell.RecalcAll2(:385) → Load2(:224) → ServerMap.preupdate(:969)
  GameServer.main(:972)
```

**為何是永久活鎖而非崩潰**：`GameServer.main` 攔住例外只印出來，但攔截點在迴圈**最上方**
——這一圈剩下的工作（更新世界、處理封包、推進 frame）全數跳過，而那個地圖格**還留在待載入
佇列裡**。下一圈同一個物件、同樣被拒絕，每 0.1 秒一次。log 只印 25 次就靜音（PZ 對重複例外
有抑制），但 frame 從此再沒前進過。**這是活鎖，任何「進程掛掉就重啟」的保護都救不了。**

**事故走的是 `addEntity` 的直通分支，`addedToEngine` 守衛從未被求值**（本節初稿寫成
「vanilla 上一層已經擋了、正式服走 return」，是 bytecode 誤讀，經兩輪審查更正）：

```
addEntity(GameEntity):
   0-21:  if (delayed.value() || bucketsUpdating.value())   →  ifeq 116
  24-47:      if (scheduledForEngineRemoval || removingFromEngine) throw
  48-71:      if (addedToEngine) { if (Core.debug) throw; return; }   ← 只在這個分支內
  72-113:     addedToEngine = true; 排進 pendingOperations
    116:  addEntityInternal(entity)    ← 另一條路，完全不看 addedToEngine
```

**證據是 log 自己給的行號，不是推論**：事故 stack 記錄 `addEntity(EngineEntityManager.java:55)`，
而 `javap -l` 的 LineNumberTable 顯示 **`line 55: 116`**——正是那條直通呼叫。
（`delayed` = `Engine.processing`，只在 `Engine.update()`／`simulationUpdate()`／`renderLast()`
內為 true，而 `ServerMap.preupdate → Load2 → RecalcAll2 → doLoadGridsquare` 不在其中。）

**這件事改變了根因的形狀，也改變了該往哪查**：

- 不需要任何「引擎兩個內部旗標不一致」的不可重現破壞。
  `addedToEngine == true` 且 `entitySet.contains == true` 是**完全自洽的狀態**，在直通分支上
  照樣拋。也就是說最可能的根因就是**有東西對已經在世界裡的物件又呼叫了一次 `addToWorld()`**。
- 搜尋空間從「誰弄壞了 `addedToEngine`」（十餘個 class 會碰）縮成
  「誰重複 add／哪個物件同時在兩個 list」——後者可查得多。
- 也解釋了**為什麼不自癒**：直通分支拋在 `addEntityInternal` 的第一個 statement，
  **沒有任何欄位被寫過**，下一圈狀態位元相同、原樣再拋。

初稿據以宣告「沒有重現條件、不強修」的前提因此不成立。**W6 的診斷特意加了
`isAddedToEngine()`（`GameEntity` 的 `public final` 方法）**：第一次命中就能把假說砍成兩半——
`true` ＝ 單純重複 add（照上面查）；`false` ＝ 才是真的不變量破壞，且 `entitySet.add`
與 `addedToEngine = true` 之間唯一會拋的是 `setComponentOperationHandler`，範圍縮到一個方法。
在拿到那一行之前，**本刀仍是止血＋蒐證，不是治療**。

**非本專案 patch 所致**（javap 實證；不能靠時間相關性——log 只回溯到 7/29，而
`FastIdentityArrayRemoval` 也是 7/29 上線，沒有乾淨的 pre-patch 基準線）：`addEntityInternal`
的 throw 在 offset 27，我方改道的 `entities.add` 在 **offset 38**，拋出時根本執行不到；
`removeEntityInternal` 由 offset 5 的 `entitySet.remove` 決定所有分支，我方改道的
`Array.removeValue` 在 offset 29 而 **offset 32 是 `pop`**，回傳值被丟棄不可能影響判斷。
`entitySet` 全程未被碰過。

**手術**：`doLoadGridsquare` 內共有**三**處 `addToWorld`，全部通往同一個 throw 點。
初版只擋第一處（等於守衛對三分之二觸發路徑失效）——**兩道獨立審查都由此抓到 blocking**，
因為 `countExactCalls` 依 owner 過濾，「全 class 僅此一處」是過濾器造成的假象。

| offset | site owner | 迴圈 | 處置 |
|---|---|---|---|
| 457 | `BaseVehicle` | `vehicles` | **刻意留 vanilla** |
| 737 | `IsoObject` | `square.getObjects()` | 改道（兩次事故的兇手） |
| 947 | `IsoMovingObject` | `getStaticMovingObjects()` | 改道 |

`IsoMovingObject` **自己沒宣告 `addToWorld`**（SmokeCheck 有斷言），offset 947 派送到的是
**同一個方法體**，包住它零額外語意風險；且該迴圈裝屍體（`IsoDeadBody`，正式服 DeadBody id
已發到 287089），是很有可能的下一個兇手。

**`BaseVehicle` 排除的真正理由是「順序」，不是「它有守衛」**（初稿的理由太弱，審查更正）：

```
BaseVehicle.addToWorld(Z):
   0-26: if (addedToWorld) { DebugType.Vehicle.error(...); return; }
  45-47: addedToWorld = true                              ← 旗標在這裡就設了
  55-56: invokespecial IsoMovingObject.addToWorld()       ← 拋出點在這之後
```

旗標賦值（offset 47）**早於** super 呼叫（offset 56），所以拋出後 `addedToWorld` 已是 true，
下一圈走 offset 26 早退——**每個 vehicle 實體最多只能拋一次**，代價是掉一個 frame（約 100 ms），
不是 114 分鐘活鎖。`IsoObject` 沒有任何旗標（offset 0 就是 super），所以永遠拋。這才是兩者
可以不同處置的完整依據。SmokeCheck 因此把**這個順序本身**釘成結構事實：若 TIS 哪天把旗標
賦值移到 super 之後（一個看起來像 bug fix 的改動），這條刻意排除會無聲變成活的凍結路徑，
而其他所有斷言全綠。

**邊界由程式碼保證，不由呼叫者巧合保證**：`BaseVehicle extends IsoMovingObject`，而
`getStaticMovingObjects()` 不是型別同質的（vanilla 自己在 `getDeadBody()`／`getDeadBodys()`
都要 `instanceof IsoDeadBody` 過濾）。若有 vehicle 進到那個 list，就會經由
`addToWorld(IsoMovingObject)` 多載被吞掉——正是本節明文拒絕的那件事。helper 因此加了
`instanceof BaseVehicle` 直通，並有對應行為測試（拿掉直通後測試會失敗）。

helper `zombie.mdc.ChunkLoadGuard` 只攔 **`RuntimeException`**：`Error`（OOM／SOE／
LinkageError）必須保持致命且可見，吞掉 VM 級故障遠比凍結更糟；反過來也不只攔
`IllegalArgumentException`——凍結機制與例外型別無關，同位置換一種 RuntimeException 一樣鎖死
114 分鐘。攔截型別由 SmokeCheck 從 **exception table** 上鎖定（舊版用 `containsUtf8` 找
`VirtualMachineError` 字串，但那是診斷 getter 的 `rethrowFatal` 帶進常數池的，放寬成
`Throwable` 照樣通過——mutation 實測確認新版會 FAIL、舊版不會）。

**降級範圍取決於 runtime class，不是單一方法體**（codex 審查推翻了本節前兩版的核心論證）：
改道點的 site owner 只是**靜態型別**，實際執行的是虛擬派送到的覆寫版本。javap 確認至少四種形狀：

| runtime class | 形狀 | 吞掉之後少了什麼 |
|---|---|---|
| `IsoObject` | offset 0 就是 super（拋出點） | `createContainersFromSpriteProperties()`、各容器 `addItemsToProcessItems()`、`addObjectPoweredByGenerator`，以及 `GameEntity.addToWorld` 自己的 `addedToWorldOrEquipped = true` 與 `sendEntityEvent(AddedToWorld)` |
| `IsoDeadBody` | super 在 offset 1，**side effect 在後** | `CorpseCount.corpseAdded`、`FliesSound.corpseAdded`、**`ObjectIDManager.addObject`**——屍體不進 ID 登記表。**而這正是 offset 947 那個 `getStaticMovingObjects()` 迴圈的主要內容** |
| `IsoWorldInventoryObject` | **side effect 在 super 之前** | `getProcessWorldItems().add()` 已經執行——「拋出時什麼都還沒跑」對這型**是假的**，守衛吞掉的是一個**部分完成**的狀態 |
| `IsoGenerator` | **完全不呼叫 super** | 走不到拋出點，不受影響 |

也就是說「拋出前尚無 side effect」只對 super-first 的形狀成立。**這是有意識接受的
production 風險**：凍結 114 分鐘的代價遠大於單一物件的部分狀態，而診斷的 `class=` 欄位
可讓事後辨識當次是哪一型——但**不能再宣稱降級一律極小**。

**跳過為什麼是安全的，承重的是 identity 而不是「先前做過了」**（審查給出比初稿更強的論證）：
`IsoObject` **沒有覆寫 `equals`／`hashCode`**（javap 確認），所以 `entitySet`（`ObjectSet`）
是 identity 語意。若真的發生過 unload → 從磁碟 reload，那會是一個**全新反序列化的實例**，
identity 不同、`entitySet.contains` 必為 false、**根本不會拋**。

於是「它拋了」本身就蘊含「同一個實例從來沒被 unregister 過」，也就蘊含「沒有發生真正的
unload」，於是先前那次 add 掛上的 ProcessItems 與 generator 註冊**都還活著**——跳過確實無損。

**但這條鏈依賴 `entitySet` 的內容與物件生命週期沒有脫鉤。** 若診斷回報
`addedToEngine=false`（＝真的不變量破壞），這條鏈就斷了，跳過會留下：沒有 container 的容器
家具（開了是空的）、沒進 ProcessItems 的冷藏／腐敗物品（食物永不腐壞或永不冷藏）、
沒掛上發電機的用電物件（發電機在跑但這台沒電）——三者全部靜默、全部持續到重啟、全部無 log。
**這正是把 `isAddedToEngine()` 列為必要診斷欄位的理由：沒有它，我們無法知道自己身處哪個世界。**
log 行本身也直接寫明「該物件的容器處理與供電掛載本次載入未執行」，讓玩家回報進來時對得上。

**診斷**：這是本刀最主要的產出——現況出事只拿得到 25 份一模一樣的 stack 然後靜音，連是哪一格
都不知道。捕手留下**方格座標＋sprite 名＋class＋`addedToEngine`＋identity＋chunk jobType＋
執行緒名＋例外**。三個決定性欄位的用途：

| 欄位 | 它能分開什麼 |
|---|---|
| `addedToEngine`（`GameEntity` 的 `public final` 方法） | `true`＝引擎狀態自洽、單純重複 add（查「誰重複呼叫」）；`false`＝真的不變量破壞（範圍縮到 `setComponentOperationHandler` 一個方法）。**兩者後續調查方向完全不同。** |
| `identityHashCode` | 同一個兇手一直拋（單一物件）vs 一堆不同物件（系統性） |
| `IsoChunk.jobType`（`public` 欄位） | 驗「SoftReset job 對活著的物件重跑 `doLoadGridsquare`」這個假說——`doLoadGridsquare` 自己就有 SoftReset 專屬分支（offset 835-842），這是目前最具體、最可驗的根因方向 |

去重鍵**只有座標**，明細才是全欄位——診斷含 identityHashCode，若拿完整明細當去重鍵，
同一格的每個不同實例都會算成新方格，去重就失效了（實際踩到並修正）。

以下每一條都是審查抓出來的，不是原始設計：

- **明細額度按「相異方格」計，不按事件計**。損壞的方格每次玩家經過都會再觸發，按事件計的話
  一格幾小時就能吃光 20 格額度；之後 B、C、D 格陸續損壞卻只在 `lastSite` 互相覆蓋、座標永久
  遺失——而「是否集中於特定建築」正是本刀唯一想回答的問題，需要的是 20 格的證據而非一格 ×20。
- **心跳用 primed 而非 `lastHeartbeatNs = 0` 哨兵**。`System.nanoTime()` 原點任意且規格明文
  允許為負，負原點時 `now - 0 >= 10 分鐘` 恆為假＝**心跳一輩子不印**。W5 的 `reportPrimed`
  早已是正解，只是同檔的心跳漏套——**兩邊一併修正**。心跳同時印本區間增量（累計值看不出惡化速率）。
- **執行緒名**：「這次是 WorldStreamer 背景執行緒（本來就不凍主迴圈）還是主迴圈（本來凍 114
  分鐘）」是本刀最有運維價值的一個 bit。
- **哨兵值逐種可分辨**：`方格=none`／`方格=getter-threw`／`方格=partial(getter-threw)`、
  `sprite=null-sprite`／`unnamed`／`getter-threw`，並另計 `診斷取值失敗` 次數。否則凌晨三點
  看到 200 行 `方格=null sprite=?` 的人無法分辨「物件本來就怪」與「每次取值都在爆、什麼都沒蒐到」。
  座標改為三軸全成功才印——逐軸退化會讓 `方格=7130,-2147483648,0` 看起來像有個真 X。
- **null receiver 用不同語氣**（`世界資料異常：方格物件清單含 null 項`）：那是與本案無關、
  可能更嚴重的另一種損壞，不能被當成同一個 bug 的第 N 次。
- **啟動橫幅**印出 `enabled` 與 property 原值。沒有它，下次若從未守衛的 vehicles 路徑凍結，
  運維只看得到「已安裝但一行都沒印」，無法分辨「沒蓋到這條路徑」與「守衛壞了」；也順帶抓
  `-D...=0`／`=no` 這類會靜默保持啟用的打錯。
- **`DebugLog` 全壞時退到 `System.err`**：那是本設計唯一的完全靜默失敗（額度被無聲扣光、
  心跳全滅），而 stderr 是獨立通道且 LinuxGSM console log 抓得到——把「完全失明」降級成
  「看得見但簡陋」。
- **anomaly 處理自帶最後一道網**：若拋出的正是 `DebugLog.log` 本身，用「再 log 一次」回應
  會讓例外逃出守衛回到 `doLoadGridsquare`——把捕手變成新的凍結源（**W5 同缺陷一併修正**）。
- **`rethrowFatal` 只重拋 `VirtualMachineError`**。它只用在診斷路徑，而診斷路徑撞到的
  `LinkageError`（例如 `getSimpleName()` 讀不到 InnerClasses——對改寫 bytecode 的專案正是最該
  防的一類）100% 是診斷子系統的缺陷，不是「世界不可續跑」的訊號；在已決定放棄診斷的那一行
  把它升級成凍結，正好是這道網存在的理由的反面（**W5 同缺陷一併修正**）。
- 主路徑刻意**不**呼叫 `rethrowFatal`——它用 `catch (RuntimeException)`，所有 `Error` 自然穿透。
  兩條路徑對 `AssertionError` 的處置因此不同，這是有意的：「遊戲自己的操作失敗」與「我們的
  log 失敗」契約本來就不同。

**共享狀態一律在鎖內**（codex 審查抓到的 blocking，前一版是錯的）：前一版註解宣稱「共享
欄位只有 primitive 或 String，最壞只是少報」——`distinctSites` 是 `LinkedHashSet`，**非
thread-safe**。並行 `add`／`size` 沒有任何定義保證，HashMap 家族在 resize 期間被併發改動
可能讓內部鏈結成環而**空轉**——那正是本刀要防的凍結形態，等於守衛自己變成新的凍結源。
延遲的 `caught++` 寫入也能覆蓋較新值，讓計數倒退、心跳的「本區間 +N」變負，不只是「下限」。

成本論證也站不住：整段只在例外**已經拋出之後**才執行，一次 `fillInStackTrace` 就是數微秒級，
鎖的奈秒級成本在這條路徑上不可觀測。現行做法：診斷取值在鎖**外**（那是遊戲物件的 getter，
持鎖呼叫等於把不可控的第三方程式碼拉進 critical section），共享狀態全部在鎖內，
log 輸出用快照在鎖外做。

**橫幅不是「開機證明」**（codex 更正）：helper 是被 patch 的 `IsoChunk` 在**第一次執行到受
守衛的 callsite** 時才觸發載入的，單純載入 patched `IsoChunk` 不會初始化它；而且 vehicles
迴圈排在兩個 redirect 之前。看不到橫幅只代表還沒有方格走過那兩個 callsite。訊息文字已改為
「首次生效」。另外 `<clinit>` 的 catch 原本連 OOM／SOE 都吞，與本檔「Error 必須致命且可見」
的契約矛盾，已補 `rethrowFatal`。

**`objectChunkJob` 是 best-effort**（codex 更正命名）：讀的是**該物件自己的 chunk**，
不是正在執行載入的那個 `IsoChunk`。若物件掛在錯誤的 square／list 上（本案的可能形態之一），
讀到的會是另一個 chunk 的 job。`identityHashCode` 可能碰撞且不可跨重啟視為唯一 ID；
`isAddedToEngine()` 是同執行緒下的有效快照，不是與 `entitySet` 線性一致的跨執行緒視圖。

**旋鈕**：`-Dmdc.chunkLoadGuard.enabled=false` 完全回到 vanilla（含原本的凍結行為），免重新部署。

**考慮過並拒絕／延後的其他層級**（審查指出初稿完全沒列，等於讓下一個人重做一次功課）：

| 層級 | 判定 |
|---|---|
| **`ServerCell.Load2` 的 `RecalcAll2()` callsite** | **延後為 W7，比值最佳但不塞進本刀**。`Load2` 的 `RecalcAll2()` 在 offset 37、`loaded2.remove(i)` 在 offset 44——**出隊在可失敗工作的下游，這一個順序就是活鎖的全部成因**，與「是哪個呼叫拋的」無關。改道它等於封掉整個活鎖 class（`doLoadGridsquare` 的例外表三格都不覆蓋事故點，所以任何一個虛擬呼叫拋出都會產生同一個 114 分鐘）。**代價**：單次爆炸半徑大兩三個數量級——整個 cell 的 8×8 chunk 全跳過、`loadVehicles()` 跳過，且 cell 被標記處理完。兩者不是替代品而是不同層：per-object 守衛讓常見情況維持小傷口，`Load2` 守衛保證佇列在任何情況下都排空。**獨立成 W7，不擴大本刀範圍。** |
| `EngineEntityManager.addEntityInternal` | **拒絕**。失效模式其實最好（`addToWorld` 會繼續跑完 containers／ProcessItems／generator，W6 全跳過）且覆蓋所有呼叫端；但語意改動最廣，且該方法是 package-private，helper 必須放進 `zombie.entity` 而非 `zombie.mdc`，違反本專案 helper 全部集中於 `zombie/mdc` 的慣例。 |
| `RecalcAll2` 層 | **拒絕**。嚴格劣於 `Load2`——出隊在 `Load2` offset 44，在 `RecalcAll2` 內捕捉**修不好活鎖**。 |
| `GameServer.main` 層 | 已經存在，而且它就是活鎖的產生器：不做出隊的話捕捉再多都沒用。 |
| 外部 frame 停滯 watchdog | 互補不是替代；代價是全服重啟（50-100 人 session 全滅）。對本類的比值最低，但對**未知**停頓仍值得有。 |

**⚠ 已知殘留**：
1. `BaseVehicle` 那處（offset 457）仍是活的凍結路徑——但依上面的順序論證，它**每個實體最多
   拋一次**（掉一個 frame，非活鎖）。附帶損害：那一次拋出後 vehicle 停在 `addedToWorld=true`
   而 `createPhysics()`／`parts.addToWorld()` 全沒跑，**且因旗標已設所以永遠不會重試**
   ——一台永久沒有物理與零件的車。比守衛跳過一個 object 更糟，但有界。
2. **新的穩態成本**：修好之後，壞掉的方格從「拋一次然後凍結」變成「每次載入都拋，永遠」。
   `fillInStackTrace` 在該深度約 1-5 µs，乘上 chunk 載入速率是一筆之前不存在的 CPU 稅。
   淨值仍遠優於凍結，但心跳的「本區間 +N」就是為了讓運維估得出它的量級。
3. 根因未定位。**不可據此認定此類假死已排除**——但下次命中時 `addedToEngine` 那一欄會直接
   指出往哪查（見上）。
4. 本刀只擋 `doLoadGridsquare`。主迴圈若因其他未知原因卡死（如 W5 之前的 SOE）仍會靜默凍到
   下次排程重啟——`Load2` 守衛（W7）與 frame 停滯 watchdog 都是互補而非重複的投資
   （8/14 那 114 分鐘完全是因為沒有東西在看）。

**驗證**：SmokeCheck——vanilla 前提（三個 owner 各 1 處、`IsoMovingObject` 未自行宣告
`addToWorld`）、兩處改道各一次且原呼叫歸零、**指令總數未變**、`BaseVehicle` 範圍宣告釘死、
**`BaseVehicle` 排除前提兩段式**——先釘 `addToWorld()V` 恰委派到 `(Z)V`（未守衛的 callsite 是
`()V`，但旗標邏輯在 `(Z)V`，不先釘住委派就等於驗了一個無關的方法），再釘 `(Z)V` 內
`addedToWorld=true` 唯一、`super` 唯一、賦值在 super 之前，且**存的是 `ICONST_1`**
（存 `false` 一樣通過順序檢查卻讓早退永不觸發）。唯一性是 CFG dominance 的窮人版——
完整支配分析過重，刻意停在此強度，殘留是「理論上仍可能有繞過旗標的分支」、
**位置錨**（改道點之後最近的呼叫必須是 `getSprite()`，釘住是 tile 迴圈而非屍體迴圈——否則
計數相同但改到別的 callsite 會全綠）、原體保留（`getSprite`／`getPipedFuelAmount` 數不變**且非零**）、
負對照改用**相對 vanilla 的差值**（絕對零會在 PZ 於他處新增同名呼叫時誤報）、主 catch 型別鎖定。
行為測試 16 案＋kill switch 模式：替身必拋負對照、守衛吞下、**正向真的入世界**（堵「空 helper」
假綠通道）、屍體迴圈多載、**vehicle 不得被吞**（拿掉 `instanceof BaseVehicle` 直通即失敗，
讓宣告的範圍邊界變成可執行而非註解）、座標定位（含 `addedToEngine`／identity／jobType 三欄）、
**真實 `DebugLogStream` 落地**（堵「刪光 log 仍全綠」——
原本所有鑑識斷言只讀 package-private 測試欄位）、logger 丟 `RuntimeException` 不外逃、
**logger 丟 `LinkageError` 不外逃**（Probe 原本結構上無法注入 `Error`，那行 `rethrowFatal`
對測試而言是死碼）、**心跳真的印出來**（原本只斷言計數器，哨兵壞掉照樣全綠）、
**額度按相異方格**（同一格 50 次只花一格額度、換格仍拿得到）、anomaly 路徑、`Error` 不吞、
半初始化 getter（哨兵逐種釘死＋`診斷取值失敗` 計數）、null receiver（兩模式行為不同，各自釘住）、
明細額度釘死 `MAX_REPORTS`。kill switch 執行傳 `disabled` 參數讓測試自行斷言旋鈕生效
（只看 exit code 的話，property 名稱打錯會變成「把 enabled 版再跑一遍、照樣 exit 0」，
降級路徑其實從未被測到）。

**Mutation 實測**（證明新閘不是裝飾，兩者在修正前都會全綠通過）：
`catch (RuntimeException)` → `catch (Throwable)` ⇒ `struct FAIL 主 catch 型別鎖定`；
刪掉 production log 行 ⇒ `應恰好輸出一行，實得 0`。

## 2s. 朝向暫存執行緒隔離（W7，server）

**事故**：2026-08-13 19:55:03，玩家 Player-A 的雞舍連同旁邊的水桶整組消失。chunk 1160,968
（方格 9280-9287 / 7744-7751）在重啟後 4 秒載入失敗，被原版的 `Blam + LoadBrandNew`
清空重生：**46,142 bytes → 8,549 bytes**，雞舍、32 隻家禽的完整基因組、`Base.Bucket`
全滅，只剩草地。完整鑑識報告在 `temp/report/incident-2026-08-13-hutch-chunk-blam.md`
（`temp/` 已 gitignore，**僅存在於調查者本機**，不隨 repo 散佈；本節已收錄其全部技術結論）。

```
Error loading chunk 1160,968
java.lang.RuntimeException: java.lang.IllegalStateException:
    Forward Direction cannot be zero length vector.
  IsoGameCharacter.setForwardDirection(:2827)   ← throw
  IsoGameCharacter.setForwardDirectionFromIsoDirection(:5104)
  IsoAnimal.load(:1536) → IsoHutch.load(:953)   ← 雞舍內的雞在反序列化
  IsoGridSquare.load(:3275/3281)
  IsoChunk.LoadFromDisk → LoadOrCreate(:2353) → LoadChunk(:2332)
  ServerChunkLoader$LoaderThread.run(:70)       ← 背景執行緒
```

**vanilla 缺陷**：`setForwardDirectionFromIsoDirection()` 用一個 **JVM 全域共用**的
`private static final Vector2 tempVector2_2` 當暫存：

```java
this.getVectorFromDirection(tempVector2_2);   // ① 寫入共用 static
this.setForwardDirection(tempVector2_2);      // ② 讀回來 normalize()
```

而 `IsoMovingObject.getVectorFromDirection(Vector2, IsoDirections)` 的第一件事是
**把 x、y 都歸零**再依方向填回真值。主執行緒（每 tick 為殭屍／動物／玩家呼叫）與
`ServerChunkLoader$LoaderThread` 同時走這段、零同步：一方在歸零空窗期、另一方讀取，
就拿到 (0,0)，`normalize()` 長度 0 → 拋例外。

**存檔本身沒有壞**：`IsoDirections.fromIndex(int)` 是 `VALUES[index & 7]`，8 個方向
全都有非零向量，存進檔案的方向值不可能產生零向量。失敗純屬競態擲骰——**這是判定
`blam/` 備份可直接還原的關鍵前提**。

**非本專案所致**（三重實證，完整推導見事故報告第五節）：
(a) 正式服 jar sha256 `09a80a46…` 與反編譯快照來源逐位元組相同，jar 未被改動；
(b) 崩潰路徑上的 `IsoGameCharacter`／`IsoMovingObject`／`IsoChunk`／`IsoGridSquare`／
`IsoHutch` 全部不在 loose class 覆寫清單內，直接由 jar 載入；
(c) 堆疊上唯一被我方 patch 的 `IsoAnimal`，其 `load()` 經常數池正規化後與原版 411 條
指令逐條相同（本 class 四刀全在 `updateStress`／`respondToSound`／`killed`／`updateLOS`）。

**手術**：`FieldGetSwap`（本次擴充為可吃 `GETSTATIC`，原僅支援 `GETFIELD`）在方法內
兩處 `getstatic tempVector2_2` 之後各插一個 `INVOKESTATIC ForwardVectorGuard.swap`
——吃掉共享實例、回傳執行緒私有替身。vanilla 方法體只有 8 條指令、無分支無 frame：

```
 0: aload_0 / 1: getstatic tempVector2_2 / 4: invokevirtual getVectorFromDirection
 7: pop / 8: aload_0 / 9: getstatic tempVector2_2
12: invokevirtual setForwardDirection / 15: return
```

`getstatic` 與插入的 `invokestatic` 皆 3 bytes、堆疊 1→1，形狀最單純的一類手術。
同一執行緒內 ① 寫和 ② 讀拿到同一個實例，語意與原版逐字相同；跨執行緒互不可見。

**不複製共享實例的內容**（刻意）：站點 ① 之後緊接的 `getVectorFromDirection` 無條件
覆寫 x、y，內容不具意義；站點 ② 要讀的正是站點 ① 寫進私有實例的值。複製共享內容
反而會把別的執行緒的髒值帶進來。

**移除副作用的耦合核對**：本刀讓該方法不再於共享實例留下值。原版 `tempVector2_2`
類別內共 12 個 `getstatic`（另有 1 個 `putstatic` 在 `static {}` 初始化欄位），本方法
佔 2 個，其餘 10 個**逐一核對全部先寫後讀**，無人依賴該遺留值：

| 方法 | 次數 | 用法 |
|---|---|---|
| `processHitDamage` | 2 | `.set(wielder 座標)` → `getVectorFromDirection(tempVector2_2)` |
| `renderlast` | 4 | `getNameCoordForPlayer`／`getNameCoords` 填入後才讀 `.x`/`.y` |
| `isObjectBehind` | 1 | `.set(this 座標)` |
| `isBehind` | 1 | `.set(chr 座標)` |
| `updateMovementStatistics` | 2 | `.set(this 座標)` → `distanceTo(tempVector2_2)` |

SmokeCheck 把類別內 `getstatic` 總數釘在 12——TIS 新增任何讀者都得重新做這份核對。

**範圍界定（刻意不做的部分）**：全 log 保留期共 **67 次**同一例外，落點統計：

| 落點 | 次數 | 外層處理 | 後果 |
|---|---|---|---|
| `IsoAnimal.load` ← `IsoHutch.load`（chunk loader 執行緒） | 1 | `Blam + LoadBrandNew` | **整塊 chunk 抹除** |
| `VirtualZombieManager.createRealZombieAlways`（主執行緒） | 66 | `IngameState.UpdateStuff` try | 掉一個 tick，無資料損失 |

後者走的是 **`IsoDirections.TEMP`** 這條**獨立**競態（`ToVector()` 直接回傳共用 static
實例，呼叫端接著 `temp.x += rand; temp.y += rand; temp.normalize()`），本刀不涵蓋。
`IsoDirections` 是全遊戲高流量核心 enum，爆炸半徑與本刀不同級，待本刀上線觀察後另案評估。

**`ThreadLocal` 有界性**：vanilla 只有一個 static-final `ServerChunkLoader`，其 constructor
只建立**一條**固定 `LoaderThread`（`ServerChunkLoader:34`、`ServerMap:823`），不是逐 chunk
起執行緒——故每條長生命週期執行緒各持有一個 8 bytes 的 `Vector2`，不隨 job 累積。
helper 的 `<clinit>` 也只建立 supplier 與 `ThreadLocal` 本身，`Vector2::new` 要到各執行緒
首次 `get()` 才執行。

**驗證閘**（SmokeCheck 11 項，含 3 項真開執行緒的行為 smoke）：

- vanilla 前提：方法體全序＝8 條指令；`getstatic tempVector2_2` 恰 2 次；
  **兩個 `invokevirtual` 的 owner/name/desc 各鎖 1 次**
- 手術後：全序＝兩組 `getstatic → swap → invokevirtual`；swap 改道 x2 且 getstatic 保留 x2；
  **兩個 `invokevirtual` 目標未被動到**
- 負對照：`IsoGameCharacter` 其餘方法零 swap 改道
- 耦合鎖：類別內 `getstatic` 總數＝12 且手術前後一致
- helper 行為：回傳非 null 且不是傳入實例／同執行緒兩次回同一實例／**跨執行緒回不同實例**

其中兩項 `invokevirtual` 目標鎖是 codex 審查抓出的 fail-closed 缺口：`matchOpcodeSeq`
只比 opcode 是 operand-blind 的，PZ 若保留相同 opcode 形狀與兩個 `tempVector2_2` 讀取、
只把 call target 換掉，原本的全序閘仍會全綠，違反「任何方法改寫都讓建置失敗」的契約。
同輪另修 `Patcher.FieldGetSwap` 的 compact constructor，把 opcode 封死為 GETFIELD／GETSTATIC
——誤傳 PUT 會對「已被消費的值」插入 helper，而那類 bytecode 不保證 verifier 會攔住。

**全類別爆炸半徑實證**：正規化常數池後比對原版與修補版的完整 javap，
**零指令被刪除、恰好新增兩條 `invokestatic ForwardVectorGuard.swap`**，
其餘差異全部是 `ldc`↔`ldc_w` 編碼互換與隨之位移的 offset。

**無重入**（`ThreadLocal` 私有實例在兩站點之間不會被同執行緒覆寫的依據）：兩個站點之間
只有 `getVectorFromDirection(Vector2, IsoDirections)`，它是 static 純 switch 無回呼；
其內部呼叫的 `getForwardIsoDirection()` 全樹**僅一處宣告**（`IsoObject:1920`，零覆寫），
`setForwardIsoDirection(IsoDirections)` 也只有 `IsoObject` 與 `IsoGameCharacter` 兩處
（全樹 grep 實證，非臆測）。另外 `setForwardDirection(Vector2)` 只把 `dir.x`／`dir.y`
複製進 `this.forwardDirection`，**不保留參照**，故私有實例不會被別名進角色狀態。

**降級分析（helper 若載入失敗會怎樣）**：這是本刀最危險的假想面——patch 自己變成
毀存檔的來源。結論是**不會**：`NoClassDefFoundError`／`LinkageError` 屬 `Error`，而
`IsoChunk.LoadOrCreate` 的失敗分支是 `catch (Exception var7)`，**攔不到 Error**。
因此 helper 缺席時例外會穿透 `LoadOrCreate` → `LoadChunk`，`Blam()`／`LoadBrandNew()`／
`BackupBlam()` 一個都不會執行，直接打死 `ServerChunkLoader$LoaderThread`——
**吵鬧的停止載入，而非安靜的大規模抹除**。且此情境已被三道閘擋在上線前：build 的
manifest 完整性守門（dist\java 任何 class 未登記即中止）、install.sh 的 fail-closed
payload preflight、以及開機健檢（驗證清單 11a）。

**效能**：`setForwardIsoDirection`／`setForwardDirectionFromIsoDirection` 全樹 48 個
呼叫點，且**沒有角色的 per-tick 無條件呼叫路徑**（render loop 那幾處是 `IsoMannequin`，
走 `IsoObject` 版不經本方法）——本方法只在轉向、spawn 與載入時被呼叫。
`ThreadLocal.get()` 相對原版 `getstatic` 約多 1–2ns，在此頻率下不可量測。

**沒有計數觀測**（刻意）：本 helper 是唯一會被多執行緒同時呼叫的 helper，靜態計數器
本身就是競態；且驗證訊號現成且更強——見部署後驗證清單第 11 項。

**沒有 kill switch**（刻意，偏離 W4-1／W5／W6 慣例，故在此說明理由）：那三刀都會
**改變行為**（併包改變送出批次、守衛吞掉例外、捕手跳過入世界），旋鈕的價值在於
「懷疑是它造成的就先關掉，不必整包 uninstall」。W7 不同——它是**語意保持**的：
全類別 javap 多重集合比對證明零刪除、僅新增 2 條指令，且同執行緒行為與原版逐字相同。
更關鍵的是，本刀的「關掉」等同於**把共享 static 換回去，也就是把毀存檔的競態放回來**；
提供這種旋鈕是提供一把傷害自己的刀。真要退場就是 `uninstall.sh`（整包），
那才是正確的粒度。唯一無法被旋鈕挽救的情境（helper 載入失敗）也不需要旋鈕——
見上方降級分析，那是吵鬧的執行緒死亡而非資料損失。

## 2t. chunk 寫入閘（W8，server）

**事故家族**：正式服累計 **43 個 chunk** 因 `SANITY CHECK FAIL`（CRC／長度不符）在載入時
被 vanilla 的 `Blam + LoadBrandNew` 抹除重生，累計損失 ~143KB 玩家建造資料，且持續發生
（8/14 單日 8 筆）。實案：玩家 Player-B 的基地箱子（chunk 1009,1428，28,401→5,470 bytes，
8/14 03:38）。與 2s 的 Player-A 案**進同一條毀滅路徑，但成因不同**——W7 治不了這族。

**鑑識定案（三個關鍵事實）**：

1. **載入側完全無辜**：43/43 筆 log 的 `load=` 等於對磁碟檔自算的 body CRC、`save=`
   等於檔案 header 欄位——遊戲讀到的就是檔案裡的東西，SanityCheck 是正確地偵測到
   「檔案真的壞了」。損毀發生在**寫入磁碟的那一刻**。
2. **兩種簽名**：A 組 16 筆 header CRC=0＋len 正確＋body 完整自洽（被捕捉在 `Save()`
   尾端「回填 len」與「回填 crc」相鄰兩行之間的狀態）；B 組 27 筆 header CRC 屬於
   別份 body（寫檔與重填撕裂）。A 組資料 100% 可救（改寫 header 8 bytes 後還原）。
3. **正常 chunk 的 CRC 都是好的**（抽樣 25/25 相符）——這不是系統性「不寫 CRC」，
   是個案級的寫入競態。

**根因狀態：機制未定罪**（誠實記錄，防止未來重查）：
- ~~載入側共用 static（`sliceBufferLoad`/`crcLoad`）競態~~ → 43/43 逐位元組對帳證偽；
- ~~`ChunkSaveWorker` 池化 buffer 與 `AddHotSave` 重填競賽~~ → 簽名完美吻合，但 hot-save
  入列點被 `!GameServer.server` 閘死（`IsoChunkMap.updateInternal`），伺服器不走；
- 現行首嫌：`ClientChunkRequest.Chunk` **跨玩家共用 static 物件池**的 pending-write 與
  重填競賽（`ServerChunkLoader$SaveLoadedTask.save` 寫 `chunk.bb` 時，同一實例可能被
  重新入列填另一份資料）——未逐行證實。
- **W4-1 交互**：W4-1（8/13 17:09）上線後發生率 0.30→0.80 筆/重啟（2.7 倍；樣本僅
  10 次重啟且 8/14 為異常日）。W4-1 是否為放大器未定案；本閘的 BLOCKED stack 會直接
  指認寫入路徑，比關刀對照更快得到答案（使用者決策：W4-1 照跑）。

**閘門設計（不依賴根因）**：所有 chunk 寫檔收斂到 `IsoChunk.SafeWrite`——在唯一的橋上
驗證，不論上游誰弄髒的。**快照 → 驗證 → 放行/擋下**：

1. 活 buffer 複製進執行緒私有陣列（關閉驗證與寫入間的 TOCTOU——驗過的位元組就是寫入的位元組）；
2. 驗 header len == 實際長度、header CRC == body 自算 CRC（`Save()` 正常收尾時兩者必然
   成立，不符＝100% 上游損毀，**零合法誤判空間**）；
3. 通過 → 把驗證過的快照交給 vanilla `SafeWrite`（鎖／sanityCheck／目錄建立全走原版）；
4. 失敗 → **跳過寫入**（磁碟保留上一版好檔案）＋前 10 筆帶完整 stack 的 BLOCKED log
   （兇手路徑蒐證）＋損毀 buffer 傾印 `blamguard/`（上限 16 份，檔名帶序號防同毫秒覆蓋）＋
   `ChunkChecksum.setChecksum(wx,wy,0)` 使下輪存檔的 CRC 比對必然不符而重寫。

**重試語意的誠實界定**（codex 審查修正——「保證自癒」是過度宣稱）：
- **仍載入的 chunk**（SaveLoadedTask 路徑、定期存檔）：live IsoChunk 還在世界裡，
  下輪 `SaveWorldEveryMinutes` 週期重新序列化＋checksum 已歸零 → 必然重寫。真自癒。
- **unload／quit 的最終存檔被擋**：`SaveUnloadedTask.release()` 隨後把 IsoChunk 交給
  reuser、記憶體內容不可恢復——**該 chunk 回退到上次成功落盤的版本，沒有重試**。
  損失上限＝上次成功存檔以來的變更（正常節奏 ≤ SaveWorldEveryMinutes=30 分）。
  對照組是 vanilla 把損毀 buffer 寫進磁碟 → 下次載入 Blam → **自上古以來的一切全滅**。
  回退 30 分鐘 vs 全滅，仍嚴格更好，但要知道它不是零損失。
- **刻意不做 A 簽名 header 修復**：物件池重用下 body 可能屬於別塊 chunk，補上正確
  CRC 等於把跨 chunk 汙染合法化成能通過驗證的檔案。拒寫是唯一保守正確的選擇。

**掛點（安全關鍵）**：redirect 三個呼叫端而非 hook `SafeWrite` 內部——它的
`new FileOutputStream(outFile)` 建構當下就 truncate 舊檔，內部攔截來不及保住上一版。
全 jar 恰 **5 個** SafeWrite 呼叫點（SmokeCheck census 釘死，新增即建置失敗）：

| 呼叫點 | 處置 | 理由 |
|---|---|---|
| `IsoChunk.Save(Z)` ×2 | **改道** | 伺服器世界存檔主路徑 |
| `ServerChunkLoader$SaveLoadedTask.save` ×1 | **改道** | chunk 出貨存檔路徑（首嫌所在） |
| `ChunkSaveWorker.WriteQueuedSave` ×1 | 不改 | 唯一入列點 `AddHotSave` 被 `!GameServer.server` 閘死（SmokeCheck pin） |
| `WorldGenerate` ×1 | 不改 | 只寫首次生成 chunk（method-local buffer），寫壞也沒有玩家資料可失 |

**取捨（誠實記錄）**：
- 跳過寫入 = 該 chunk 磁碟版本暫停在上一版，直到下次內容變更觸發重寫。「舊而有效」勝過
  「被 Blam 全滅」，且 checksum 歸零保證必然重試。
- 閘門攔不住「buffer 被**完整地**填成另一塊 chunk 的自洽資料」（header 無座標欄位）——
  已觀測 43 筆全是不自洽型，全數會被攔下；該殘餘情境窄得多。
- verify 對 len≤17 回 MALFORMED：空 body 的 CRC=0 會與 crc 欄位 0 假相符，必須先擋
  （這同時攔下「truncate 後零位元組寫入」的檔案抹除情境）。

**失敗紀律**（codex 審查後精確分層）：
- **不可寫 buffer（null／非 heap array）＝各模式一律拒寫**：vanilla 對它的行為是
  「FileOutputStream 建構先 truncate 舊檔、之後才炸」＝把好檔案換成空檔。拒寫是唯一
  不毀檔的選項，observe 的「零行為改變」在毀檔面前讓位。
- **守衛內部故障（buffer 已確認合法後的 RuntimeException）＝fail-open 回退 vanilla**
  ——守衛的 bug 不得癱瘓全部存檔。
- **log／傾印基礎設施的 RuntimeException 與 LinkageError 一律吞下**（W6 教訓），
  不得外逃進存檔路徑。

`MODE` 三態：`-Dmdc.chunkWriteGuard=0` 停用（零開銷 passthrough）／`1` enforce（預設）／
`2` observe——照常驗證＋log＋傾印但一律寫入活 buffer。**observe 的兩個誠實限定**：
log 印 `FLAGGED` 而非 `BLOCKED`（沒有擋任何東西）；`blamguard/` 傾印是驗證當下的快照，
實際落盤的活 buffer 之後仍可能被改動，兩者不保證相同。
成本：CRC32 硬體加速 ≤64KB ~30µs，最壞 200 塊/s 佔單核 <1%。

**驗證閘**（SmokeCheck 19 項，codex 審查後補強 5 項堵 false-green）：verify 四情境
行為 smoke（自洽→OK、**A 組實案簽名→CRC_MISMATCH**、len 竄改→LEN_MISMATCH、
截斷→MALFORMED）＋resolveMode 四值＋**safeWrite 本體執行級 smoke 三條決策路徑**
（損毀 buffer 靜默擋下＋checksum 歸零實測、null buffer 拒寫、自洽 buffer 真的委派
vanilla——測試環境必拋＝到達寫入路徑的證明）；vanilla 前提（兩方法 SafeWrite/
setChecksum 計數＋**setChecksum 先於 SafeWrite 的順序鎖**、census 總數 5＋**逐類分佈**
堵新舊呼叫點互抵、hot-save 閘 **getstatic→ifne 方向鎖**、格式 offset **語境鎖**
（17→CRC32.update、5→ByteBuffer.position，非僅常數存在））；手術後改道到位＋原呼叫
歸零；負對照（排除條件鎖到精確簽名 `Save(Z)V`，其他 Save 多載也受檢；SafeWrite 本體
無遞迴）。

**歷史損失的還原路線**（另案執行）：A 組 16 筆改寫 header CRC 後即可還原（Player-B 案優先）；
B 組 27 筆 body 可能為撕裂混合體，需逐筆分析不可批次。還原一律在閘門上線後進行——
否則還原完可能再被同一缺陷吃掉。
（2026-08-14 18:11 已執行：A 組 16/16 全數還原成功，含 Player-B 基地 1009,1428。）

## 2u. 存檔管線隔離（W9，server）

**根治刀**。W8 閘上線首晚攔下 8 筆損毀寫入（零資料損失），現行犯證據把根因從
「嫌疑」推進到「定罪」：

- **8/8 呼叫堆疊一致**：`SaveChunkThread → SaveLoadedTask.save`（`IsoChunk.Save(Z)`
  直接路徑零事件）；
- **8/8 簽名一致**：len 欄位正確、CRC 欄位＝0（3 筆）或垃圾值（5 筆）——與歷史
  43 筆（A 組 16＋B 組 27）同款兩簽名；
- **機制**（bytecode 實證）：`Save(ByteBuffer,CRC32,Z)` 的 header 指紋用**呼叫者傳入的
  CRC32** 計算；序列化入口 `SaveChunkThread.addLoadedJob` 傳入的是**單一共用實例**
  `SaveChunkThread.crc32`。兩執行緒同時序列化：對方 `reset()` 插在我 `update` 與
  `getValue` 之間 → 指紋 **0**（A 組）；`update` 交錯 → **垃圾**（B 組）。body/len
  由各自執行緒完整寫入 → **len 恆正確**——與 8/8 觀測相容的唯一機制（buffer 竊用
  假說解釋不了 len 恆正確，且 unpack 雙重歸還路徑經全 jar 普查證實為死碼）。
- **並行序列化實證**：`QueuedSaveAll` 走 `GameServer$1`（shutdown hook 執行緒），與
  主迴圈的 `ServerCell.update → saveChunk` 同時進 `addLoadedJob`——歷史 blam 集中在
  重啟窗口（0.8 筆/重啟）由此解釋。運行中爆發（21:50、22:15 兩波與 `growing
  ByteBuffer` 擴容事件同叢集）的第二執行緒未逐一指認；本刀不依賴指認——任何並行
  呼叫者都被 ThreadLocal 隔離。
- **第二處同款競態**：`SaveLoadedTask.save()` 的去重比對四連讀外層
  `ServerChunkLoader.crcSave` 共用實例，而 save() 可在 SaveChunkThread 與
  LoaderThread（經 `saveNow`——載入前沖存檔，`LoaderThread.run` 偏移 214 實證存活，
  非死碼）並行——污染 ChunkChecksum（去重誤判＝陳舊跳寫；客戶端校驗錯亂＝重送，
  疑與黑邊案「crc 恆 0」同根）。

**三刀**（helper：`zombie/mdc/ChunkSaveIsolation`，全部只動存檔管線）：

1. `addLoadedJob` 的 GETFIELD `crc32` → `headerCrc`（ThreadLocal）——指紋競態根絕；
2. `SaveLoadedTask.save()` 的 GETFIELD `crcSave` ×4 → `dedupCrc`（ThreadLocal）——
   去重競態根絕；
3. `getChunk`／`getByteBuffer`／`releaseChunk`（addLoadedJob 租用＋例外歸還、
   release() 歸還）→ 私有化——存檔管線徹底退出 `ClientChunkRequest` 的全域 static
   共用池（`freeChunks` private static／`freeBuffers` **public** static，與 N 條
   PlayerDownloadServer WorkerThread、RequestZipListPacket.parse 共用）；同時關閉
   W8 的理論盲區（池雙發同一 buffer 時「完整重填成別塊 chunk 的自洽資料」可通過
   CRC 驗證——私有化後此路徑物理上不存在）。

**私有化語意（codex 對抗審查後收緊為 exactly-once）**：Chunk 殼**不入池**——每次
new（vanilla update() 用無同步的 savedChunks ArrayList 歸還，主迴圈與 shutdown hook
並行 updateSaved 時同一 task 可被 release 兩次；入池殼會被二次出租＝在私有池內
復刻本刀要根絕的競態。順帶：這也揭露 vanilla 全域池本就有同款雙重歸還孔，可能是
運行中爆發的第二機制）。buffer 歸還走 `synchronized(c)` 原子摘取（雙重 release
第二次拿到 null＝no-op）；私有 buffer 池有界（≤256 顆軟上限＋單顆 ≤256KB，超限
丟 GC——vanilla 全域池無界，`sendLargeArea` 的 clear() 經普查為死碼從不執行）。

**驗證閉環**：W8 的 `flagged` 計數器是現成 A/B 儀表——本刀生效後 flagged 應歸零；
不歸零＝機制另有分支，用 BLOCKED stack 續查。W8 不拆，永久保險絲。

**Kill switch**：`-Dmdc.chunkSaveIsolation=0` 完全停用（helper 原樣委派回共用
實例／共用池——off 路徑的 bytecode 就是 vanilla 呼叫，SmokeCheck 釘保真）。

**驗證閘**（SmokeCheck 15 項＋獨立 JVM off 測試；codex 對抗審查後補強 4 項）：
行為 6（headerCrc 跨緒相異／dedupCrc 分族／機制錨——共用 CRC32 遭外部 reset→0、
疊 update→垃圾的最小重演／私有池 fresh-shell＋buffer 重用／隔離定義——全域池計數
不變／**雙重歸還冪等**——release 兩次只入池一次）＋結構 9（vanilla 前提三方法形狀、
**耦合鎖全 jar 版**——兩顆 CRC32 的讀者全 jar 普查總數＝已釘位置數（硬編類別清單
掃不到新增 nestmate，codex 修正）、**序列化者清冊**——全 jar SaveLoadedChunk 恰 2
＋逐類分佈、手術後 **swap 緊鄰性**（GETFIELD 之後必須緊接 helper）＋改道歸零、
SaveChunkThread 負對照、helper off 路徑 bytecode 保真）；build 步驟 9d 以
`-Dmdc.chunkSaveIsolation=0` 獨立 JVM **真的執行** off 分支（CRC identity、全域池
同一性 marker 驗證、私有池零使用——off 分支不該首跑於事故現場）。

**未涵蓋（誠實界定）**：`PlayerDownloadServer.update` 的發送序列化仍用共用 buffer 池
（其 CRC32 為 per-connection 且僅主緒＝分析上安全）——發送方向若有池污染，客戶端
CRC 檢查會擋下並重新請求，不落盤；黑邊／重送觀測歸 W4-1 戰場。`saveNow` 的佇列
重排（同 chunk 新舊存檔順序可顛倒）與 `run()` 例外路徑的 task 洩漏是兩個獨立的
vanilla 缺陷，影響小、暫不動刀，記錄於此供 TIS 回報。

## 2v. 抑噪第 8 項—— toxic log 改道

**根因**：MOD PSR（Plysken Solar Revolution）在每個遊戲分鐘（~2.5 真實秒）無條件呼叫
`IsoBuilding.setToxic(false)` 遍歷所有 powerbank，導致建築毒氣狀態隨機刷新。server 的
`GameServer.sendToxicBuilding` 每次變動都廣播給全部 63 人。抑噪前 15.41 小時／8 session 實測：
**164,176 行毒氣訊息／全 console 360,669 行 ＝ 45.5%**（逐 session 35.5%–80.8%、17–25 個相異座標），
淹沒真正的錯誤。**按 `(frame, building, value)` 去重只能消除 19.8%**——`(frame,building)` 組合
96,451 個只出現一次、30,982 個兩次，主體是同一 building 每 2.5 秒跨 frame 反覆送，不是同 frame 重複。

**為什麼只攔 log、不動封包**：client 的 `WorldRegionToMetaGrid.lambda$updateSquares$0` 自己計算
「該室內有 activated generator」並本地標記 `toxic=true`，**不通知 server**。若 server 端做去重來
減少廣播，會把玩家鎖在會扣血的毒氣室裡——client 認為有毒而 server 說沒有，結果玩家進去直接扣血
但看不到警告。這是本項最有價值的知識：server 的 `isToxic` 只是「上次送了什麼」的殘影，不是
真實狀態。**只有攔 log 才是安全的**。

**手術**：`zombie/network/GameServer` 的 `sendToxicBuilding (IIZ)V`，offset 11 的
`INVOKESTATIC zombie/debug/DebugLog.log:(Lzombie/debug/DebugType;Ljava/lang/String;)V` 改道
`zombie/mdc/LogFilter.logType`，expectedHits = 1。訊息由 offset 6 的 `invokedynamic makeConcatWithConstants`
組成（BSM recipe = `Send Toxic Building at [ \u0001 , \u0001 Toxic: \u0001 ]`），座標與 boolean 都是
變數→ 必須 `startsWith`，不能 `equals`。

**為什麼安全**：

- **method-scoped**：`GameServer` 全 class 有 21 個同 descriptor 的 `DebugLog.log` 呼叫點，本方法內
  恰 1 個。其他 20 個呼叫點保持原版。
- **廣播封包不動**：`sendToxicBuilding` 的 putInt 序列、`udpEngine.connections` 廣播路徑完全保留。
  所有玩家仍會收到最新的毒氣狀態——不是斷網，只是 console 安靜。
- **client 端狀態不變**：client jar 完全未動，`receiveToxicBuilding` 照常執行。

**已知代價**：server console 少了一條「client 還在收廣播」的 liveness 訊號。該訊號的對稱項在 client
端 `GameClient.receiveToxicBuilding` 的 `Receive Toxic Building at [ ... ]` 仍在，本 patch 未動。

**SmokeCheck 斷言**：vanilla 前提（恰 1 個 `DebugLog.log(DebugType,String)` ＋封包段 `PacketType.send` 存在）
／手術後（改道 ×1、原呼叫歸零、`send` 與 `putInt` 數量與 vanilla 相同）／負對照（全 class
21→20 保持 vanilla、helper 恰 1）。

---

## 2w. 食材重量記憶化（**實測後決定不啟用 `on`，維持 observe**）

**浪費**：`InventoryItem.getExtraItemsWeight ()F` 對 `extraItems` 内每個 fullType 字串完整建構
一個 InventoryItem，只為讀 `getActualWeight()` 就丟棄。單次建構含 `ScriptManager.FindItem`（兩次
hash、miss 退化為 moduleList 線性掃描）＋`Item.InstanceItem`（codeLen=4064 ＞ FreqInlineSize=325
故永不 inline）＋4 個 ArrayList＋10 次 `Translator.getText`＋`synchWithVisual`＋`ConfigureItemOnCreate`，
保守估 ~1.6-2.0 KB 配置、1.5-5 µs。

**頻率**：`Moodle.Update` 的 HEAVY_LOAD 分支（無節流）→`ItemContainer.getCapacityWeight`
→`IsoGameCharacter.getInventoryWeight`→`getUnequippedWeight`／`ItemContainer.getContentsWeight`
**相互遞迴走訪整棵巢狀背包樹**，每玩家每 tick 一次。2026-08-16 jstack 46 樣本命中 2 次。

**無法死工消除**：`IsoGameCharacter.updateInternal` 有兩個 `Moodles.Update` callsite——`:9103` 在
`GameClient.client` 為真時、`:9129` 在 `!client` 分支——vanilla 刻意讓 dedicated server 跑。
HEAVY_LOAD 被 `calculateBaseSpeed`（減速）、`Fitness.reduceEndurance`、`testDefense`、
`getClimbingFailChanceFloat` 消費，是 server 權威 gameplay。

**手術**：`zombie/inventory/InventoryItem` 的 `getExtraItemsWeight ()F`，offset 35 的
`INVOKESTATIC zombie/inventory/InventoryItemFactory.CreateItem:(Ljava/lang/String;)Lzombie/inventory/InventoryItem;`
改道 `zombie/mdc/ItemWeightMemo.createItem`，expectedHits = 1。Helper 新檔 `patcher/game/zombie/mdc/ItemWeightMemo.java`。

**三態旋鈕** `-Dmdc.itemWeightMemo`（class 初始化時讀一次，改值需重啟）：

- **`observe`**（預設，第一版唯一上線的模式）：完全不保留任何 InventoryItem 實例。`SEEN` 只存字串，
  且只收「通得過五道門的型別」——因為只有它們在 on 模式下真的進得了 CACHE。`hits` = 重複且可快取的
  呼叫數（＝**啟用 on 之後真正會命中的次數**）、`misses` = 首見且可快取的型別數、`uncacheable` = null
  或被門擋下的呼叫（on 模式下每次仍會重新建構，**不計入命中率**）、`vanillaNsAvg` = 原版單次建構耗時。
  **行為與原版逐位元相同**。（第二輪 review 抓到早期版本把 null 與被門擋下的型別也算 hit，
  會讓唯一的上線決策依據灌水，已修正。）
- **`on`**：命中即回傳共用實例。**尚未經正式服量測驗證，預設不啟用**。
- **`off`**：純轉發。

**factory 恰好呼叫一次**：`createItem` 切成三段（呼叫前觀測／原版 factory／呼叫後觀測），factory
不在任何 try 之内。前後兩段各自吞 `RuntimeException | LinkageError`。拋例外時不重跑 factory
（避免 `Rand.Next`、`initialiseItem` 的 Lua OnCreate、MOVEABLE 的 script 寫回執行兩次）。

**`on` 模式的五道門**（`cacheable`）：非 null 且 `scriptItem != null`／`getLuaCreate() == null`
（`Item.InstanceItem:1916-1918` 的 Lua 回呼）／`getItemConfig() == null`（`:1915` 的 `ConfigureItemOnCreate`
→`ItemConfig.ConfigureEntityOnCreate`）／`!isItemType(MOVEABLE)`／`!hasComponents()`。
**MOVEABLE 是三方 review 抓到的實質風險**：`Item.InstanceItem:1801-1805` 對 MOVEABLE 執行
`this.actualWeight = moveable.getActualWeight()`，寫回共享的 script 單例，而所有 `Moveables.<sprite>`
共用同一份 script。**第五道門用 vanilla 自己的判斷**：`Item.InstanceItem:1909` 無條件呼叫
`GameEntityFactory.CreateInventoryItemEntity`，而它內部正是以 `itemScript.hasComponents()` 決定要不要
`createEntity`（GameEntityFactory.java:114-117），所以拿同一個謂詞當門，跳過建構就不會漏掉任何 ECS
component 建立／連接。

**`on` 模式的已知行為差異**：全域 RNG 序列位移（命中時少抽的 `Rand.Next` 至少兩次——`createItemInternal:139`
的 id、`InstanceItem:1911` 的 OutfitRNG 種子；實際次數依型別分支增加：KEY 的 keyId、CLOTHING／ALARM_CLOCK_CLOTHING
的 palette、MAP 的 pickRandom、RADIO 的 setRandomChannel）。PZ MP 非 lockstep（server 權威＋client 預測），
故不 desync，但抽樣序列不同。不做補抽補償（次數隨分支而異，猜錯更糟）。

**共用實例的安全性**：`getExtraItemsWeight` 內該區域變數（slot 3）只被 `IFNULL` 與兩次
`getActualWeight()` 讀取，迴圈下一圈即覆寫，從不逃逸——由 SmokeCheck 的 `extraWeightNoEscape`
語境指紋鎖住（factory 結果緊接 `ASTORE`、該 slot 恰 3 次 ALOAD 且消費者僅那三處）。

**不做 null 負快取**：`InventoryItemFactory.createItemInternal:113` 找不到 script item 時印 `Couldn't find item`
並回 null，那是「有 recipe 引用不存在的 item」的訊號；快取 null 會讓它只出現第一次、也讓 mod 之後補註冊時
永遠取不到。`nullResults` 計數追蹤它。

**SmokeCheck 斷言**：vanilla 語境指紋（恰 1 個 `CreateItem(String)` ＋2 個 `getActualWeight()` ＋零逃逸
——factory 結果緊接 `ASTORE`，該 slot 只被 1 次 `IFNULL` 與 2 次 `getActualWeight` 讀取，共 3 次 ALOAD）
／手術後（改道 ×1、原呼叫歸零、真指令總數與 vanilla 相同 = 1:1 替換）／負對照（全 class 5→4 保持 vanilla、
`createCloneItem` 未被動到、helper 恰 1）／helper 契約（factory 委派恰 2 處 = off 純轉發＋phase 2，無第三處
重試路徑）／五道門各恰一次。已用 mutation test（把 21／5／3 改成錯值）確認這些斷言真的會紅。

**實測結論（2026-08-17，observe 樣本窗 4 個 session／累計 uptime 9.68 小時，截至 11:12）
——不啟用 `on`，收益不足以承擔風險。**
樣本窗以記憶化實際生效的 session 為界（`01-28` 首次生效 01:30:50 起，含 `04-04`／`04-53`／`06-12`；
**不是** PSR 統計那個 15.01 小時凍結窗——後者從 `20-04` 起算，當時這把刀還沒部署）。四個 session 的
週期行給出三個關鍵量：

- **命中率**：`hits/(hits+misses)` = 99.997%（如 `attempts=4194304 hits=4194250 misses=54 types=54`），
  `uncacheable=0`、`nullResults=0`、`overflow=0`、`anomalies=0`。五道門在真實流量下沒擋掉任何東西，
  型別集合只有 25–54 個。
- **呼叫速率**：兩種算法都做。(a) 相鄰週期行的 `Δattempts / Δt` ＝ **328–732 calls/s**（中位約 520）；
  (b) 全期下界＝已印出的 attempts 總和 9,437,184 ÷ 9.68 h ＝ **271 calls/s**
  （各 session 重啟歸零、未達 2^20 的殘餘未計入，故為下界；比 (a) 低是因為含開服初期的低負載時段）。
- **原版單次建構**：`vanillaNsAvg` 隨樣本增加收斂到約 **2.1 µs**（首次取樣 2521 ns 偏高，
  後續 1980–2239）。

於是收益區間 = `271 × 2.1 µs` ≈ 0.57 ms/s 到 `732 × 2.5 µs` ≈ 1.83 ms/s，相對主迴圈單核預算
（10 fps ⇒ 1000 ms/s）約 **0.06%–0.18%**，換算 **0.006–0.018 fps**。
而 `on` 的代價是上面「已知行為差異」整段（全域 RNG 序列位移）＋**首次真正執行共用實例路徑**
——observe **不走** memo 命中分支（`MODE == MODE_ON` 才查 `CACHE`；observe 只做一次
`SEEN.containsKey` 後照常呼叫 factory），所以 `anomalies=0` **完全沒有演練過共用實例**。
風險遠大於 0.18% 的上限，維持 observe／或轉 `off`。

**方法教訓（值得記）**：命中率是比例、不是收益。99.997% 支撐「快取會命中」，
不支撐「值得啟用」——絕對收益必須是「命中率 × 呼叫速率 × 單次成本」，缺了速率這一項
就會把一把 0.1% 的刀當成主要優化機會。與 W3-2 ECS memo 同構（審查全綠、實測淨劣化而撤刀）：
**只有量測證明有收益**。

**第二個方法教訓：樣本窗不可互借。** 這段結論第一版寫「14 小時 observe 數據」——那是從同一天
PSR 統計的凍結窗（`20-04` 起算、15.01 小時）借來的，但兩者的起點完全不同：PSR 從 `20-04` session 起算（該 session 起
`coverage REMOVE` 帶 `bank=` tag），而這把刀是 `01-28` session 才生效（橫幅 01:30:50）。
前三個 session 根本沒有它。**每個 patch 的樣本窗必須用它自己的「首次生效」訊號界定**
（本專案每把刀都有開機橫幅，就是為此），不能沿用同一天其他分析的時間範圍。
對照組亦同：`18-12` 之所以能當 PSR 1.71 的對照，是因為實測它 0 筆帶 tag。

**`on` 期無法自帶對照組**（設計限制，先記下）：`vanillaNs` 只在 cache miss 走 factory 那條路累加，
而 `on` 模式下 miss ≈ 型別數（數十次）且還須同時撞上 `(attempts & TIMING_MASK)==0` 才取樣，
故 `vanillaSamples` 幾乎必為 0、`~vanillaNsAvg` 會印 0。日後若真要驗證 `on`，對照基準只能用
observe 期的歷史值 2.1 µs 去比 `on` 期的 `memoNsAvg`，不能期待同一份 log 自帶兩側數據。

---

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
10. **受精蛋豁免退役驗證**（2026-08-08，2n 已退役——本項現在是**負向**驗證，確認舊 patch
   已徹底清除、世界清理回歸原版）：
   (a) 兩個退役 class 都不在磁碟上：`ls /home/pzserver/serverfiles/java/zombie/iso/IsoGridSquare.class`
   與 `.../zombie/mdc/FertilizedEggGuard.class` 皆應「No such file」。**只殘留改道版
   `IsoGridSquare.class` 而 helper 已刪＝chunk 載入路徑必爆 `NoClassDefFoundError`**，這是本項
   最重要的一條。（install.sh 的不明 loose class 巡檢已 fail-closed，會在安裝前擋下這種殘留。）
   (b) 新 `patch-manifest.txt` 共 32 筆，`grep -c . patch-manifest.txt` = 32，且
   `grep -E 'IsoGridSquare|FertilizedEggGuard' patch-manifest.txt` 無輸出。
   (c) 開機健檢無 `VerifyError`／`NoSuchMethodError`／`LinkageError`（此路徑跑在
   `ServerChunkLoader` 執行緒上，出現即立刻 uninstall）。
   (d) log 不再出現 `[MinidoracatJavaPatch][EggGuard]` 任何一行（重啟後全新 log 起算）。
   (e) 行為回歸原版：地上的受精蛋與一般蛋一樣，過 24 遊戲小時並讓該 chunk 卸載後重回即消失。
   玩家端與伺服器端此時**行為一致**（退役正是為了消除這個 desync），可直接目視驗證。
   (f) 玩家引導：受精蛋要孵化請放**雞舍**（`IsoHutch`）——雞舍內的蛋不是
   `IsoWorldInventoryObject`，不經 `IsoGridSquare.load` 的清除路徑，本來就不受清單影響。
11. **W7 朝向暫存執行緒隔離驗證**（2s）：
   (a) 開機健檢無 `VerifyError`／`NoSuchMethodError`／`NoClassDefFoundError`——本刀改的是
   `IsoGameCharacter`（全遊戲最熱的 class 之一）且 helper 跑在 chunk loader 執行緒上，
   出現即立刻 uninstall。
   (b) **主驗證訊號＝例外歸零**：`grep -c 'Forward Direction cannot be zero' <DebugLog>`
   對照修前的每日 0–13 次。**注意分母**：修前 67 次裡有 66 次走的是 `IsoDirections.TEMP`
   那條**本刀不涵蓋**的獨立競態（`createRealZombieAlways`，主執行緒），所以正確的預期是
   **「stack 內含 `IsoAnimal.load`／`setForwardDirectionFromIsoDirection` 的那一類歸零」**，
   而不是總數歸零。只看總數會誤判成「patch 沒效」。
   (c) `blam/` 不再新增 Forward Direction 類型的目錄：
   `grep -l 'Forward Direction' /home/pzserver/Zomboid/Saves/Multiplayer/pzserver/blam/*/*_error.txt`
   應只剩 `1160/968_error.txt` 這一筆歷史紀錄。
   (d) 行為不變：角色／殭屍／動物轉向正常，動物出雞舍後朝向不亂跳。
   (e) **還原前置**：本刀確認生效後才把 `blam/1160/968.bin` 複製回 `map/1160/968.bin`
   （必須在 server 進程停止的窗口內，否則記憶體版本會在下次世界存檔打回去）。
12. **W8 chunk 寫入閘驗證**（2t）：
   (a) 開機健檢無 linkage 錯誤（改道方法跑在存檔與 chunk 出貨路徑上，出現即立刻 uninstall）。
   (b) **心跳**：`grep 'ChunkWriteGuard' <DebugLog>` 應出現 `passed=N flagged=0` 週期行
   （每 2048 次通過印一行）——證明閘門真的在驗，而非默默 passthrough。
   (c) **BLOCKED 事件**（enforce 模式）＝雙重訊號：該 chunk 逃過一次抹除（止血生效），
   且 log 內的 stack trace 直接指認寫入路徑（蒐證到手）。出現時把前 10 筆的完整 stack
   與 `blamguard/` 傾印檔一起歸檔分析——這就是根因獵捕的決勝證據。
   **判讀注意**：observe 模式印的是 `FLAGGED` 且照常寫入＝沒有保護，不可誤讀成已擋下；
   若 BLOCKED 發生在 unload/quit 的最終存檔，該 chunk 回退到上次成功落盤版本
   （見 2t 重試語意），玩家可能回報「東西回到半小時前」——那是止血的代價，不是新 bug。
   (d) `flagged` 持續為 0 且 blam/ 不再新增 CRC 類目錄 = 缺陷可能與 W4-1 或特定時序相關，
   繼續觀察；`flagged>0` 且 blam/ 不再新增 = 閘門正在攔截現行損毀。
   (e) **anomalies 增長**＝守衛遇到非預期 buffer 狀態走了 fail-open，需調查。
13. **W9 存檔管線隔離驗證**（2u）：
   (a) 開機健檢無 linkage 錯誤（改道方法跑在主迴圈與存檔執行緒上，出現即立刻 uninstall）。
   (b) **首次生效橫幅**：`grep 'ChunkSaveIsolation' <DebugLog>` 應出現「首次生效」一行
   （第一次 chunk 存檔序列化時印）——證明改道真的被走到。
   (c) **主驗證訊號＝W8 flagged 歸零**：`ChunkWriteGuard` 心跳應變成 `passed=N flagged=0`
   長期維持（修前基線：首晚 2.5 小時 8 筆）。**尤其盯重啟窗口**——關機存檔
   （QueuedSaveAll on shutdown hook）正是定罪的競態場景，連續數次重啟 flagged 仍為 0
   才算根治確認。flagged>0＝機制另有分支，取該筆 BLOCKED stack 續查。
   (d) blam/ 不再新增任何 CRC 類目錄（`SANITY CHECK FAIL` 歸零）。
   (e) 行為不變：chunk 正常存讀、玩家離開區域後重回內容不回退、客戶端 chunk 下載正常
   （發送路徑一概未動）。
   (f) kill switch 演練過（build 步驟 9d 以獨立 JVM 真的執行 off 分支＋步驟 7 的
   bytecode 保真閘）；線上如需停用：JAVA_OPTS 加 `-Dmdc.chunkSaveIsolation=0` 後重啟。
14. **抑噪第 8 項（toxic log）驗證**（2v）：
   (a) 開機健檢無 `VerifyError`／`NoSuchMethodError`／`NoClassDefFoundError`——改道方法跑在
   `sendToxicBuilding` callsite 上，出現即立刻 uninstall。
   (b) **先確認驗的是新版**，再看訊號。一律用**帶時間戳的 per-session** log，不要用
   `server-console.txt`：後者在本伺服器實測是每次重啟覆寫（`server patch` 指紋恰 1 次、
   `LOADING ASSETS: START` 恰 1 次、toxic 行數與 per-session DebugLog 完全相同），但那是
   未文件化的行為——LinuxGSM 或 PZ 改成 append 就會讓總量計數靜默給出跨 session 的錯答案。
   ```bash
   LOG=$(ls -t /home/pzserver/Zomboid/Logs/*DebugLog-server.txt | head -1)
   grep 'server patch' "$LOG"                    # 指紋必須是新版，否則下面的數字沒有意義
   grep -c 'Send Toxic Building' "$LOG"          # 主驗證訊號：應為 0
   ```
   修前基線：`2026-08-17_00-12` session（舊版 `5f5f466`）1.25 小時內 **18,117 行**（≈14,494/h）；
   抑噪前 15.41 小時平均 **10,654/h**。
   (c) 同期其餘 Multiplayer 頻道訊息（`Receive`／`Network`／`Packets` 等）必須照常輸出——
   若一起消失，代表攔錯了（`logType` 只比對 `Send Toxic Building at [ ` 前綴）。
   (d) 廣播封包正常：玩家在毒氣區域仍被扣血、生命值介面正確更新，只是 server 端 log 安靜。
   (e) 行為不變：`GameServer.sendToxicBuilding` 的 `doPacket`／`putInt`×2／`putBoolean`／`send`
   與廣播迴圈完全保留（已由 SmokeCheck 逐項與 vanilla 對數，含真指令總數）。

15. **食材重量記憶化驗證**（2w）：
   (a) 開機健檢無 linkage 錯誤（改道方法跑在 `getExtraItemsWeight` 熱路徑上，出現即立刻 uninstall）。
   (b) **首次生效橫幅**：對同一個 per-session log
   `grep 'ItemWeightMemo' "$LOG"` 應出現「首次生效 mode=observe」一行
   （第一次呼叫 `getExtraItemsWeight` 時印）——證明改道真的被走到。
   (c) **observe 模式判讀**（預設）：`hits` 已與 `on` 的實際行為對齊——只計「通得過五道門」的型別，
   不再是單純的型別重複率（第二輪 review 抓到舊語意會讓命中率灌水）：
      - `attempts` = 所有呼叫次數（取樣與週期 log 的時鐘，不受 cacheability 偏置）
      - `hits` = 重複且可快取的呼叫數 ＝ **啟用 on 之後真正會命中的次數**；
        `hits/attempts` 才是「on 能省下的建構比例」，`hits/(hits+misses)` 是可快取型別內的命中率
      - `misses` = 首見且可快取的型別數（開局成長快，型別集合穩定後放緩）
      - `uncacheable` = null 或被五道門擋下的呼叫。這些在 on 模式下**每次仍會重新建構**，
        故不計入命中率；佔比高就代表這把刀的天花板低
      - `vanillaNsAvg` = 原版單次建構耗時。**單看它沒有意義，必須乘上呼叫速率**
        （由相鄰週期行的 `Δattempts / Δt` 求得）才是 on 能省下的量級——2026-08-17 就是漏了
        這一項才把一把 0.11% 的刀誤判為主要優化機會
      - `anomalies` ≠ 0 或 `overflow` ≠ 0 ＝ 異常，需調查
      - `types`（observe 模式）＝ SEEN 的型別數，等於 `misses`（只有首見且可快取才寫入）
   (d) 行為不變：背包容量計算正常、玩家負重值正確、背包滿時拒絕插入照常工作。
   (e) **`on` 模式已實測否決，不再排程啟用**（2026-08-17 定案，見 2w 的「實測結論」段）：
   命令列不得指定 `-Dmdc.itemWeightMemo=on`，產生的 log 應恆為 `mode=observe`。
   14 小時／4 session 實測：命中率 99.997%、`uncacheable=0`、型別集合 25–54，看似漂亮，
   但呼叫速率僅 **328–732 calls/s**、單次建構約 **2.1 µs** ⇒ 收益上限 ≈ 1.09 ms/s
   ≈ 主迴圈 **0.11%** ≈ 0.011 fps。而 `on` 的代價是全域 RNG 序列位移＋**首次真正執行共用實例
   路徑**（observe 不走 memo 命中分支，故既有 `anomalies=0` 完全沒演練過共用實例）。
   **風險與 0.11% 不成比例，維持 observe。**
   ⚠️ 本項不是「等條件達成再開」——條件已量測且**未達標**。若日後要重啟評估，必須先有
   新的呼叫速率量測（例如遊戲更新改了 `Moodle.Update` 的走訪方式），單憑命中率不足以翻案；
   且 `on` 期**無法自帶對照組**（`vanillaNs` 只在 miss 走 factory 時累加，on 模式 miss ≈ 型別數
   且須撞上 `(attempts & TIMING_MASK)==0` 才取樣 ⇒ `vanillaSamples` 幾乎必為 0），
   對照基準只能用 observe 期歷史值 2.1 µs 比 `on` 期 `memoNsAvg`。
   下次重建若確認仍無收益，可考慮整刀退役（redirect＋helper 一併移除）。

16. **PZ 更新**（順序不可調換）：
   1. **更新前先 `uninstall.sh`**——loose class 不在 Steam depot 內，`app_update` 只換 jar
      **不會刪掉它們**，殘留的舊 patched class 仍會覆蓋新 jar。同源閘只擋重新安裝，擋不住殘留。
      本伺服器的 update／monitor cron 是全自動的，**沒有人工介入視窗**，得知新版就要立刻執行。
   2. 重拉 jar → `build.ps1`。
   3. **命中數守門通過 ≠ 座標仍有效**——守門只數數量、不驗語境。常數手術必須另外用 `javap`
      確認該常數的前後指令（見 2b 的 42.20 實例：`respondToSound` 內仍剛好有一個 `20.0f`，
      舊座標會通過守門卻改到逃跑距離）。redirect 手術則確認 owner／method 未被搬家
      （見第 1 節 42.20 實例：consistency log 搬到 `INetworkPacket.logInconsistentPacket`）。
   4. 逐項語境驗證通過後才重新部署，並回到本清單第 1 項重跑開機健檢。
