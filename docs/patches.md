# 優化項目與原理詳解（42.21.0）

> 本文檔是給維運者看的完整說明：每一項優化「為什麼做、動了什麼、為什麼安全、怎麼驗證」。bytecode 層的逐項原始證據（javap 反組譯摘錄）在 [specs/](specs/) JSON。

<a id="0"></a>
## 0. 總機制：為什麼「裝了就生效」

PZ 伺服器啟動 classpath 是 `java/.` 排在 `java/projectzomboid.jar` 之前——JVM 找 class
先找散檔（loose class）再找 jar。所以把修改過的 `.class` 按原路徑放進 `serverfiles/java/`，**下次重啟就以我們的版本取代原版**，不動 jar 本體；刪掉散檔即完全回退。

修改不是重編譯反編譯原始碼（那條路充滿反編譯器假象），而是**bytecode 手術**：用 ASM 讀 jar 內的原版 class，只做兩種「堆疊形狀與指令長度都不變」的修改，原 class 的 StackMapFrames／max stack 原樣保留，JVM 驗證器看到的結構與原版同構：

1. **log 呼叫改道（redirect）**：把 `INVOKEVIRTUAL DebugType.warn(...)` 這類指令原地換成 `INVOKESTATIC zombie/mdc/LogFilter.warnFmt(...)`（receiver 變第一參數，淨堆疊效果相同）。過濾邏輯在 `LogFilter.java`——普通 Java 類、javac 對遊戲 jar
   編譯、隨 patch 一起出貨。**只攔已知噪音（完整字面值 equals／前綴 startsWith），其餘一律轉發原始呼叫——寧漏不誤**。
2. **方法內常數替換（const-change）**：只在指定方法內把某個 `LDC`/`BIPUSH` 常數換值（新常數進新的常數池條目，其他方法共用的原條目不動）。

### 防呆體系（每一層都實測過）

| 防線 | 擋什麼 |
|---|---|
| 逐方法命中數守門 | PZ 更新後呼叫點增減／方法改名→建置直接失敗，不會產出錯位 patch |
| LoadCheck 連結驗證＋helper 簽名斷言 | 改道目標方法缺失／簽名不符 |
| CheckClassAdapter JVMS 資料流驗證 | 任何堆疊／frame 層面的錯誤（JVM verifier 等級，離線先跑） |
| install.sh 三道閘 | 產物損壞（payload SHA）、遊戲已更新（jar 原版 SHA 不同源即拒裝）、檔案衝突 |
| uninstall.sh | 依 manifest 精確移除，一鍵回退 |

---

<a id="1"></a>
## 1. 抑噪類（現役 8 項：表列 #2–#8＋§2bf 的 #10；#1、#9 已於 42.21.0 退役）——為什麼值得做

正式伺服器 78 張地圖＋多人環境下，console.txt 每分鐘被數十到數百行無意義警告刷屏：(a) 真正的錯誤被噪音淹沒（EchoCreek、OOM 事件的診斷都因此變難）；(b) log I/O 與檔案膨脹是實際開銷；(c) DebugLog 寫檔在高頻呼叫路徑上有同步成本。

| # | 位置 | 攔掉的訊息 | 觸發原因 | 保留了什麼 |
|---|---|---|---|---|
| 1 | ~~AnimationSet.GetState~~（**42.21.0 退役**） | `AnimState not found: X` | MOD 動畫集缺 state，引擎本就回傳空 fallback | 42.21 官方把這行從 `warn` 降級為 `trace`（server 預設門檻 Warning 不輸出），改道點消失 |
| 2 | SkinningBoneHierarchy.buildBoneHierarchy | `SkeletonBone not resolved for bone: X` | MOD 模型骨架非標準骨名，開機刷屏 | 骨架建構結果完全不變 |
| 3 | SpriteConfig.initObjectInfo | `Invalid SpriteConfig object!` **exact 白名單 37 名**（初版 MetalBigWireFence／WoodFloorLvl3／Wooden_Windows；42.20 加 DoubleWireGate／BrickWallLvl2／MetalSmallWireFence／BrickWindowFrameLvl2／Piano／WoodenWallLvl3；42.20.3 加 SandFloor／WoodenDarkWallLvl3／GravelFloor／Floor_SpringGrass／DoubleDoor／WoodenWindowFrameLvl3／WoodFloorLvl2／Wood_DoubleDoorDark／WoodDoorFrameLvl3／Fences_MetalFarmGate；42.20.4 加 Commercial_FullGlassBlackWall／Commercial_GridGlassBlackWall／Commercial_HalfGlassRedWall／Commercial_FullGlassRedWall／Commercial_HalfGlassBlackWall／Commercial_GridGlassRedWall／MetalFloorLvl1／Wood_Crate_Lvl2／Floor_SummerGrass／WoodFloorLvl1／WoodenDarkDoorFrameLvl3／Composter／BrickFloorLvl1／Floor_SummerGrassCorner／ComposterShoddy／DoubleFenceGate／WoodenDarkWindowFrameLvl3／Floor_Concrete，見 §2bf；完整訊息 equals；入列門檻 ≥4 筆/h 見 AGENTS.md 手術鐵則，名單行為由 LogFilterNoiseTest 鎖） | 特定物件載入必刷（42.20 六名為 5.5h 實測 1183 筆；42.20.3 十名為 26h 實測 23,517 筆＝當時最大殘留噪音源，17 個 ≤86 筆低量名依門檻刻意不收）。37 名的生效定義都來自建築 MOD 的 entity 覆寫（其中 22 名與 vanilla 同名、15 名為 MOD 專屬），名單的退役訊號跟著該 MOD 更新走，不跟 PZ 版本走（42.21.0 審計：觸發程式碼位元不變，無名稱可移除） | **其他名稱（含 null 與低量名）照常警告**；resetObjectInfo 清理照跑 |
| 4 | ItemPickInfo.GetPickInfo | 前綴 `ItemPickInfo -> cannot get ID for `（container/room/tile/zone 四變體） | MOD 地圖自訂容器/房間未註冊 ItemConfigurator，每次 loot roll 觸發且**不受 debug 閘控** | 4 條 debug 模式診斷訊息前綴不同、照常輸出；loot fallback 行為不變 |
| 5 | NetworkZombieManager.moveZombie | `moveZombie: There are no zombies in nz.zombies.`（完整字串 equals；**不印、改計數**，累計在 W80 心跳的 `nzMissing`） | 換擁有者時舊擁有者的清單裡沒有這隻殭屍。至少一部分是回收的殭屍物件帶著上一輪的擁有者（原版缺陷，[W80](#2co) 修掉）；其他來源沒查 | 擁有權轉移邏輯照舊 |
| 6 | PacketsCache.\<init\> | 前綴 `No packet handler for type:` | vanilla 本就有多個 PacketType 走內建 switch 而非 handler class，**每個玩家連線必刷一長串** | printException（真錯誤）與 `Packets limit has exceeded`（真限流）不動 |
| 7 | INetworkPacket.logInconsistentPacket | format 常數 `The packet %s is not consistent: %s`（equals） | 載具類封包 desync 常態訊息 | **`sync` 自我修復照跑（重要）**；反作弊 `The packet %s is not valid` 留在 `onServerPacket`，**完全不經我方程式碼** |
| 8 | GameServer.sendToxicBuilding | `Send Toxic Building at [ ... , ... Toxic: ... ]`（前綴 startsWith） | MOD PSR（Plysken Solar Revolution）週期呼叫 `IsoBuilding.setToxic` 導致建築隨機刷新毒氣狀態，MP 廣播給全部玩家 | **只攔 log，不動廣播封包**；client 端自會標記室內有 generator 的建築、不通知 server |
| 9 | ~~IsoObject.syncIsoObject~~（**42.21.0 退役**） | `ERROR: IsoThumpable not found on square x,y,z`（`System.out.println`，訊息 invokedynamic 組成 → 前綴 startsWith；2026-09-02） | B42 建造流程在 IsoThumpable 加進 square 前就呼叫 `setHealth` → server 端 `sync()` → `getObjectIndex()==-1` 必印一行；正式服 8/30–9/2 四天 11,567 行（≈120/h ≫ 門檻 4/h） | 42.21 官方把 `IsoThumpable.setHealth` 改為 `server && getObjectIndex() != -1` 才 sync，噪音源頭消失；剩下的 not-found 是低頻破損訊號，回歸原版照常印 |

> **42.20 變更**：`ActionStateContainer.tryInsertChildState` 的抑噪已移除——TIS 自己把那兩個
> `DebugType.warn` 降級為 `trace`（全 class warn 8→6、trace 1→3），噪音源由官方修掉。第 7 項的 consistency log 也從 `PacketTypes$PacketType.onServerPacket` 搬到
> `INetworkPacket.logInconsistentPacket`（interface default method，訊息文字未變），改道目標隨之搬家；`PlayerHitZombiePacket` 的 override 只多一層前置過濾，最後仍呼叫 super，兩條路徑都涵蓋。

> **42.21.0 變更（2026-09-28）**：第 1 項 `AnimationSet.GetState` 被 TIS 降級為 `trace`（javap 42.21 offset 37
> `invokevirtual DebugType.trace`），第 9 項的噪音源被 `IsoThumpable.setHealth` 的 `getObjectIndex() != -1` 守衛從源頭消除（javap 42.21 setHealth offset 21–25），兩項退役；LogFilter 的 `FMT_EXACT` 第 1 筆與
> `PRINTLN_PREFIX`／`println`／`suppressesPrintln` 一併刪除。其餘 7 項（＋§2bf 的 #10）審計確認觸發程式碼與頻率前提仍成立。復活：`git checkout 8d2bee8 -- patcher/src/PatchConfig.java patcher/game/zombie/mdc/LogFilter.java`。

代價（誠實揭露）：這些訊息從 log 消失。若日後要診斷「正是這些訊息描述的問題」，先 `uninstall.sh` 還原再觀察。每份 spec 的 `verification` 段都寫了正反向驗證法。

---

<a id="2"></a>
## 2. 行為類（1 項）——動了什麼、為什麼是安全的

<a id="2a"></a>
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

**為什麼不恢復**：`zombiesToSend` 只收「有主且主人不是本連線」的殭屍，**無主殭屍永遠不進這個列表**，因此 42.20 的 culling 完全碰不到它們。而遠離所有玩家、堆在世界各處的無主殭屍正是 78 張圖大世界的記憶體壓力來源，也是當初做這個手術的理由。在新模型下把取樣從 1/3 調到 1/2，只會加快刪除「有主、在某連線 relevant 區內、但歐氏距離超出保護半徑」的一小撮殭屍——保護半徑還放大了 2 倍、又多了 per-connection 額度上限。要處理殭屍堆積得換切入點，不是這個常數。

**常數語意本身沒變**（供將來參考）：`RandInterface.AdjustForFramerate` 伺服器端是
`(int)(chance * 0.33333334f)`——`10`→`(int)3.333`=3→`Rand.Next(3)==0`＝1/3；`6`→2→1/2；`5`→1→`Rand.Next(1)` 恆為 0＝**100% 全刪**。所以 6 仍會是最保守的一階加速，若日後決定恢復。

**附帶風險（原版行為，非我方引入）**：`canBeDeletedUnnoticed` 只檢查該連線的玩家，不檢查其他連線的玩家，理論上 B 玩家可能目擊 A 連線判定「無人看見」的殭屍消失。不加速就不會放大它。

> 證據等級：作用域與條件為反編譯語意判讀（`NetworkZombiePacker`、`UdpConnection.RelevantTo`），常數換算為 `AdjustForFramerate` default method ＋算術驗證。結論是不動 bytecode，故未做 javap 逐指令驗證。

<a id="2b"></a>
### 2b. 動物壓力模型三調（IsoAnimal）

**背景原理**：動物的 `stressLevel`（0-100）進出全走 `changeStress`（含基因放大與
clamp）。MP 的結構性問題是「**進水快、出水慢**」：多玩家的槍聲/喊叫密度高（每發
+radius/20，槍聲 radius 70-150 → 單發 +3.5~7.5）、例行屠宰對同圈全體 +Rand(10,30)，而唯一的自然衰減只有閒置時的 `-multiplier/5500`。結果動物長期滯留高壓區間：≥80 開始撞毀圍籬（thump，MP 最痛損失）、>40 誘導失敗率飆升。

| 手術 | 值 | 效果 |
|---|---|---|
| updateStress 閒置衰減除數 | `5500 → 2750` | 無壓力源時恢復速度 ×2（唯一自然出水管道） |
| respondToSound 聲音壓力係數 | `0.05f → 1/60f` | 單次聲音壓力 ÷3（尖峰主因） |
| killed 屠宰連鎖上限 | `Rand(10,30) → Rand(10,15)` | 平均 20→12.5，群體受驚語意保留 |

**為什麼安全**：全部是既有常數的幅度調整——無新路徑、無指令增刪；`changeStress`
的 [0,100] clamp、基因/缺陷放大、逃跑行為（動物照樣被嚇跑）、防虐待機制（被攻擊的高壓直寫路徑）全部不動。手術只改指定方法內的指令指向新常數池條目，其他方法實測原樣。伺服器權威、數值經同步覆蓋 client，**只裝伺服器即全域生效**。

> **42.20 座標變更（重要陷阱）**：TIS 把壓力算式從 `changeStress(sound.radius / 20.0F)`
> 改寫成 `changeStress(sound.radius * 0.05F)`（數學等價，但常數池條目從除數換成乘數），同時在 wild 分支新增 `fleeDistance = sound.radius * 3.0F + 20.0F`。結果是 `respondToSound` 內**仍剛好只有一個 `20.0f`**——舊的 `ConstChange(20.0f, 60.0f)`
> 會通過逐方法命中數守門（1 == 1），卻把野生動物逃跑距離改成 `radius*3+60`，壓力完全沒調。**命中數守門只數數量、不驗語境**；常數手術每次更新都要用 `javap` 確認前後指令（正確的壓力點在 offset 347 附近：`ldc 0.05f; fmul; invokevirtual changeStress:(F)V`）。

**刻意不做的**（分析過並否決）：PacketsCache 的封包速率常數（正路是 ini 的
`MaxPacketsPerSecond`）；動物 heldBy 安撫速率（已是主動手段）；culling 的安全距離（縮小會被玩家目擊消失）；早退 updateStress（會連衰減一起殺掉，反效果）。

---

<a id="2c"></a>
## 2c. 防崩潰頭部守衛（2 項，codex 對抗審查定案）

**原理**：MP 的 hit 封包用 CharacterID 延遲解析目標角色；stale／型別混淆的參照會讓
`getZombie()`（=tryCastTo，可回 null）或傳入的 `character` 為 null，而原版 setter 鏈無任何檢查
→ NPE。手術＝方法最前插入 4 條指令的 null 守衛（`aload; [invokevirtual]; ifnonnull L; return; L:[F_SAME]`），堆疊峰值 1、locals 不變、原 frames 照舊。

| 位置 | 守衛 | 順序關鍵 |
|---|---|---|
| hit/Zombie.process()V | `getZombie()==null → return` | **在 `super.process()` 之前**——否則 character-null 先在父類 NPE、type-confusion 會把殭屍狀態先寫進錯誤角色 |
| hit/Fall.process(IsoGameCharacter)V | `character==null → return` | 縱深防禦定位：封包 pipeline 後續仍會用 target，不宣稱端到端防崩 |

**驗證（build 第 6 步）**：行為 smoke＋負對照（原版必拋 NPE、修補版必須安靜返回——行為級證明
guard 位置正確）＋ASM 結構斷言（guard 在最前、super 恰一次、9 setter 未增減）。

**將來評估**（更根本的修復點，未做）：`Zombie.isConsistent()` 加 `getZombie()!=null`（現只驗 ID
存在不驗型別）；`hit/Player` 有對稱風險應一起審。

---

<a id="2d"></a>
## 2d. 安全屋 room/building 綁定修復

> **退役（2026-09-28）**：本刀 2026-07-29 起停用（觸發條件是自訂大地圖，正式服已只跑原版地圖），42.21 對版時把殘留的 `LogFilter.getBuilding`／`canBeSafehouse`／`findRoom` helper、LoadCheck 簽名檢查與 SmokeCheck 斷言一併刪除。42.21.0 沒有改動 `SafehouseClaimPacket` 與 room 綁定路徑（patch notes 的「Safehouse exploits
> remedied」在 Java 側找不到對應改動），原根因是否仍在無法從程式碼判定。復活：`git checkout 8d2bee8 -- patcher/game/zombie/mdc/LogFilter.java`，並重新驗證兩個座標。

**症狀**：B42.19 正式服擴充大型 `Map=` 後，玩家申請安全屋時大量出現
`SafehouseClaimPacket.isConsistent > building not found`。失敗座標的原始 lotheader 仍有合法
`RoomDef`／`BuildingDef`，但執行期 `IsoGridSquare.getBuilding()` 回傳 null。

**手術**只改 `SafehouseClaimPacket` 兩個呼叫點：

1. `isConsistent` 的 `IsoGridSquare.getBuilding()` 改道 helper。正常有 building 時直接回傳；只有 null
   才掃描目前 metacell 與相鄰八格的 authoritative `roomList`，依原版 `IsoMetaChunk.getRoomAt`
   規則（反向順序、user-defined 優先、排除 emptyoutside）找房間，呼叫 `setRoomID` 並重新確認 building。找不到或補回後仍為 null，就回復舊 roomId，讓原版照常拒絕。
2. `processServer` 的 `SafeHouse.canBeSafehouse` 改道 wrapper，先同樣修復 claim square 與玩家目前
   square，再呼叫原版方法。既有「已擁有安全屋、存活天數、住宅類型、屋內角色、範圍重疊、戰爭、權限與反作弊」檢查一項都沒有移除。

這個 patch **不直接建立安全屋、不修改 `map_meta.bin`、不接受沒有 RoomDef 的座標**。掃描是 O(n)，但只發生在原本會失敗的安全屋 claim，不是 frame 熱路徑。成功修復會記錄：`[MinidoracatJavaPatch] repaired safehouse room binding at x,y,z roomId=...`。

舊安全屋資料另由 `scripts/map_meta_safehouses.py` 解析與選擇性合併；工具強制輸出新檔、逐座標指定、拒絕 owner／範圍衝突，且讀取與輸出都必須通過 byte-for-byte round-trip 驗證，不會直接覆蓋正式存檔。

---

<a id="2e"></a>
## 2e. 原生固定容器週期刷新修復

**根因**：B42.19 的 `LootRespawn.respawnInChunk` 先用地面格的 Zone 擋整個 `(x,y)` 垂直欄位；只有精確名稱 `TownZone`、`TownZones`、`TrailerPark` 會進入掃描。正式服實測的 Yanghu 醫院只有
`Region`／`FarmLand`，該地圖 `objects.lua` 也沒有任何上述 Zone，因此原生藥櫃永遠不會進到刷新流程。另一類案例雖位於 `TownZone`，但玩家任何建造／搬家具都可能把整個 Zone 的
`haveConstruction` 永久標為 true；原版沒有對應的解除路徑，之後整區固定容器都被擋住。

**手術**只改 `LootRespawn.respawnInChunk` 兩個呼叫點，週期 marker、loot table 與其他 gate 不動：

1. `IsoGridSquare.getZone()` 改道 `getLootRespawnZone`。原版 Zone 已合格且沒有 construction 時直接回傳，零行為差異；否則先掃同一 chunk 的同一垂直欄位，只有找到「非屍體、非 `IsoThumpable`、非 compost、`movedThumpable=false` 且確有 container」的物件，才回傳只供本次 gate 判斷的合格 Zone。有原 Zone 時會複製 `hourLastSeen`，所以 `SeenHoursPreventLootRespawn` 照常生效。
2. `IsoObject.getContainerCount()` 改道 `getLootRespawnContainerCount`：搬動過的原生家具回傳 0；玩家製容器原本多為 `IsoThumpable`，仍在原版 `instanceof` gate 被排除。未搬動固定物件原樣回傳真實 container count。

**安全屋語意完整保留**：bytecode 中 `SafeHouse.getSafeHouse(square)` 沒有改道，每個刷新週期都重新查目前有效的安全屋。安全屋存在時仍不刷新；解除後不會立刻補貨（避免 claim/unclaim 洗物資），而是在該
chunk 的**下一個正常 `HoursForLootRespawn` 週期**恢復。`explored`、`hasBeenLooted`、`MaxItemsForLootRespawn`、`SeenHoursPreventLootRespawn` 與 `ItemPickerJava.fillContainer` 全部保持原版。

**已知邊界**：若 square 完全沒有 Zone，就沒有可保存的原版 `hourLastSeen`，fallback 使用 0；正式服目前
`SeenHoursPreventLootRespawn=0`，不影響現行行為。此 patch 修的是週期刷新，不強制未探索容器立刻生成，也不重寫既有 `lootRespawnHour`。

---

<a id="2f"></a>
## 2f. 玩家登入同步 DB 寫入耗時量測（觀測 patch）

**目的**：玩家登入時曾伴隨 `Server is too busy`，但目前證據只能確認主執行緒在登入流程中做同步工作，尚不能把尖峰歸因到單一 DB operation。本項先建立可歸因的 server-side timing，不改登入並行度或拒絕策略。

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
格式化／sink failure 不得改變登入結果；`VirtualMachineError`、`ThreadDeath`、`LinkageError` 原樣外拋。若 delegate 已拋 fatal，wrapper 只完成 elapsed 計算後直接重拋，不再嘗試 log；若 delegate 是 nonfatal
而 logging 是 fatal，logging fatal 優先，且不修改任一 exception 的 suppressed list。

這不是「登入優化」本身，而是下一輪 A/B 判斷依據。正式服部署後要把三種 op 的 elapsed 分布與
`Server is too busy` 時段對齊；證據指出哪一項形成長尾後，才評估 transaction／批次化等行為改動。原生 70ms busy 判斷、`LoginQueueEnabled`、`DenyLoginOnOverloadedServer`、auth/protocol 與登入順序均未修改。

---

<a id="2g"></a>
## 2g. chunk unload entity removal 索引化

**正式服根因證據**：玩家回報卡頓與黑邊的時段，主機仍有約 85% CPU idle、低 I/O、充足可用記憶體，也沒有 OOM、`VehicleCollide` 或持續登入尖峰；但伺服器實際 FPS 曾降到約 2–5。五次 matching-JAR
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
反覆掃描逐漸縮小的全域／bucket 陣列，總工作量是 O(k×N)，最壞接近 O(N²)。這也解釋了為何整台主機不滿載：PZ 權威 world loop 的單一主執行緒先吃滿一個核心，其餘核心閒置，玩家仍看到 chunk
供應延遲與黑邊。

**手術範圍只有四個 callsite**：

| class.method | 原呼叫 | 改道 |
|---|---|---|
| `EngineEntityManager.addEntityInternal` | `Array.add` ×1 | `FastIdentityArrayRemoval.add` |
| `EngineEntityManager.removeEntityInternal` | `Array.removeValue` ×1 | `FastIdentityArrayRemoval.remove` |
| `EntityBucket.updateMembership` | `Array.add` ×1、`removeValue` ×1 | 同上 |

沒有改 `ServerCell.Unload`、chunk 判定、entity callback、bucket bit、listener 順序、登入佇列或 busy
保護。helper 在 patched add 時同步建立 sidecar：以 `System.identityHashCode(entity)` 映射 index，尾端 swap-remove 後只更新搬入元素的 index，因此正常 add/remove 都是常態 O(1)。

**安全與生命週期**：

- registry 是 `WeakHashMap<Array<?>, State>`，且只接受 `ordered=false`；`State` 本身只含
  `TIntIntHashMap`／`TIntHashSet` 與 primitive counter，不持有 Array 或 entity 強參照。
- `TIntIntHashMap` 維持 Trove 預設 auto-compaction（factor=loadFactor=0.5，每 0.5×size 次
  remove 壓實一次，攤提 O(1)/remove）。**不可停用**：Trove remove 只留 REMOVED 墓碑，壓實是唯一主動且有界的回收機制。初版曾設 `setAutoCompactionFactor(0.0F)` 換取「同一波 unload 不反覆 rehash」，結果數小時載卸攪動讓墓碑飽和、FREE 槽耗盡，get/put 探測鏈退化成掃全表——
  2026-08-06 正式服主迴圈 15-25s 停頓、「Server is too busy」連發實案，thread dump 定罪後回退。
- identity hash 碰撞或同一 identity 重複時，該 hash 改走原版「由前往後、第一個 identity match」的線性語意；每次 fast remove 前仍用 `items[index] == value` 驗證。
- 偵測到 size/index 漂移時最多 rebuild 一次；再次不一致就失效 sidecar 並呼叫原版
  `removeValue`。`ordered=true`、`identity=false`、null 全都直接保留原版路徑。
- 鎖粒度是每個 Array；全域 weak registry lock 只包短暫查找／登記，不包 O(N) rebuild 或原始 add。

**驗證**：build 會確認四個原呼叫歸零且四個 helper 呼叫各恰一、helper/inner class 不含
`IdentityHashMap` 或 entity 強參照；另跑 tail-swap 等價性、missing、ordered/equality/null、size/same-size/index 漂移、duplicate/hash collision、不同 Array 並行與 deterministic stats，以及墓碑攪動回歸鎖 `churnKeepsTombstonesBounded`（4096 live×20480 循環，反射計數 Trove
REMOVED 槽，斷言 maxRemoved≤3072＋rebuild/linearScan/fallback=0；負對照實測停壓實時
maxRemoved=6500 必紅）。尺度 benchmark 固定 N=1024/2048/4096/8192、3 輪 warmup＋7 輪中位數，輸出 add、first remove、full remove、ns/entity、倍增比與可用時的 thread allocation；時間只作報告，不設機器相依 pass/fail
門檻。

---

<a id="2h"></a>
## 2h.（42.20.2 官方收編，退役）popman 共享 buffer 執行緒競爭修復（v3 buffer 隔離）

**正式服根因證據**：2026-07-30 全日 11 個 log set 共 77 筆
`IngameState.UpdateStuff> Exception thrown`＝`java.nio.BufferUnderflowException` at
`ZombiePopulationManager.updateMain`（`:611` getFloat 與 `:614` getInt 兩種越界點）。下午高峰（12:12–18:11）6 小時內 34 筆；01:04:47 的例外正落在玩家 client 端 229 筆
`removing stale zombie 5000` 清除視窗內，與「殭屍/實體消失」回報時段吻合。

**機制（定案根因＝執行緒競爭）**：`updateMain`（主執行緒）以
`n_getAddZombieData(offset, byteBuffer)` 分頁讀取 native 的 add-zombie 佇列，buffer 是
`allocateDirect(1024)`、每筆 29 bytes（getFloat×3＋get×1＋getInt×4）。**同一個
`this.byteBuffer` 也是存檔寫側的工作區**：`writeCellSnapshot`（MCD 背景執行緒，每筆
21 bytes）與 `beginSaveRealZombies`（主執行緒）——寫側之間有 `saveLock` 互斥，**讀側 `updateMain` 卻沒拿鎖（vanilla 遺漏）**。MCD 寫側與主執行緒讀側併發時，共享的
Buffer position 被兩邊同時推進：輕則讀取越界（隨機欄位 `BufferUnderflowException`），重則**無聲混讀**寫側的 21-byte 記錄當成 29-byte 生成資料（殭屍資料損毀）。例外一路拋出
`IngameState.UpdateStuff` 的共用 try 區塊，把該 tick 的 popman 剩餘解析、`updateLoadedAreas`、`MapCollisionData.notifyThread`、`playerSpawns.update` 與
**`PathfindNative.updateMain` 泵送**全部帶掉——高流量區殭屍密度被抽乾＋殭屍尋路瞬間定格；例外集中在 chunk 存檔高峰（寫側活躍時段）也由此解釋。

**手術（v3，root fix）**：兩個手術型組合，全部線性插入、無新分支：

1. **field-get-swap（buffer 隔離，根治）**：updateMain 內全部 **10 處**
   `getfield byteBuffer`（clear ×1、native 寫入參數 ×1、欄位讀取 ×8）之後插入
   `invokestatic PopmanBufferGuard.updateMainBuffer(ByteBuffer)ByteBuffer`——吃掉共享
   buffer、回傳主執行緒專用的 `UPDATE_MAIN_BUFFER`（allocateDirect(1024) 鏡射 vanilla）。讀側與 MCD 寫側徹底隔離，零鎖零死鎖。堆疊 1→1。前提已驗證：native 只有
   `n_getAddZombieData`/`n_saveRealZombies` 收 ByteBuffer 且皆逐呼叫傳參（`n_init` 無 buffer 註冊＝無快取位址假設）；寫側 `beginSaveRealZombies`（主執行緒）／`writeCellSnapshot`（MCD 執行緒）維持 vanilla 的 this.byteBuffer＋saveLock 紀律不動。
2. **count-clamp（保險絲）**：鎖定
   `invokestatic n_getAddZombieData → istore C → iload O → iload C → iadd → istore O` 全序（slot 逐步核對，任何一步不符即放棄→守門失敗），在 `istore O` 之後插入
   `iload C; invokestatic clampAddZombieCount(I)I; istore C`——helper 以專用 buffer 的
   `remaining()/29` 為上限。隔離後不應觸發；一旦觸發即記 log＝仍有未知失配的警報器。

**v1→v2→根因修正（2026-07-30 當晚，codex 對抗審查定案）**：v1 上限固定 1024/29=35，部署重啟後 13 分鐘內仍 2 筆 underflow 且 clamp 觸發 0 次——「容量溢位」假說被線上否證。v2 改以呼叫當下 `buffer.remaining()/29` 為上限（防禦性保留），但 codex 進一步從反編譯源碼證實**主嫌是共享 buffer 的執行緒競爭**：`processPendingSaveCells`／`writeCellSnapshot` 在 MapCollisionData 背景執行緒（`MapCollisionData.runInner:469`）對**同一個 `this.byteBuffer`** 做 clear＋put 序列，而主執行緒的 `updateMain` 讀同一
buffer **完全沒有同步**——`saveLock` 保護了 `beginSaveRealZombies` 與
`processPendingSaveCells` 的寫側，`updateMain` 卻沒拿鎖（vanilla 遺漏）。兩執行緒共享同一 Buffer 的 position 指標，並發時 position 亂跳 → 隨機欄位 underflow＋混讀損毀的殭屍資料；也解釋例外集中在 chunk 存檔高峰。v2 在競爭下只能讀到瞬間快照，**不能根治**（但無害）。**runtime overlap trace 已於 2026-07-31 00:17 由 v2 clamp 取得**：`pageCount=35 > readable=28 (remainingBytes=814)`——1024−814=210=**10×21 bytes 恰為寫側
10 筆記錄**，位元組級證實取樣瞬間 MCD 寫側正在同一 buffer 寫入。據此定案 v3 buffer 隔離（見上方手術），棄用 lock-wrap（native 層死鎖未知數）。

**為什麼 clamp 在 `offset += count` 之後**：offset 推進沿用 native 原回報值，分頁推進行為與原版逐位元一致——無論 native 是 offset-served 還是 consume-on-read，都不會重讀、推進不足或死迴圈。若改成在 offset 推進**之前** clamp，consume-on-read 語意下 `while (offset < total)`
可能永不收斂（主執行緒死迴圈）——不可接受，故不採。損失語意：**隔離後的正常路徑 lossless**（native 回報數不會超過專用 buffer 可讀量，clamp 不觸發）；只有保險絲真的觸發時（＝仍有未知失配的異常狀況）才會丟棄超額殘尾，遺失範圍與原版 underflow 相同、不新增遺失。

**helper**：`zombie/mdc/PopmanBufferGuard`——`UPDATE_MAIN_BUFFER`（allocateDirect(1024)
專用 buffer，僅主執行緒觸碰）＋`updateMainBuffer(ByteBuffer)`（swap 目標，無視傳入值回傳專用 buffer）＋`clampAddZombieCount(int)`（`count <= remaining/29` 原值返回；超額時累計
dropped 筆數並經 `DebugType.Multiplayer.println` 記 `[MinidoracatJavaPatch][PopmanBufferGuard]`，log sink 失敗不外拋）。插入點堆疊安全（swap 1→1、clamp 峰值 1），frames 不需增補（`ClassWriter(0)` 原樣保留）。

**驗證**：build 守門＝命中恰 **11**（field-get-swap ×10＋count-clamp ×1）；SmokeCheck
專用 buffer 斷言（同一實例、direct、容量 1024、無視傳入值）＋ **10/10 swap 相鄰性**（updateMain 每個 `getfield byteBuffer` 必須緊接 swap、無多餘 swap）＋行為 smoke（35 內原值、36+ 夾 35、limit-short/position 依 remaining、負值不動）＋結構全序鎖（clamp 必須在 `istore O` 之後、count slot 即 `if_icmpge` 迴圈比較上限、loop-index slot 與 count/offset 相異、native 分頁呼叫未增減）＋ **MAX_RECORDS 前提守門**（capacity 取自 `<init>` 的 `allocateDirect` 實參、每筆 bytes 由 updateMain 的
getFloat×3/get×1/getInt×4 計出，並與 helper 實際 clamp ceiling 連動——PZ 只改 buffer 大小或 record 欄位時建置失敗而非默默錯上限）。手術狀態機遇 StackMapFrame（控制流合流點）即放棄。部署後觀測：`UpdateStuff> Exception thrown`＋`BufferUnderflowException` 應歸零；若 `[PopmanBufferGuard]` clamp log 出現＝native 超額頁實際發生率的直接量測。

**2026-08-29 native 反編譯驗證後記**（libPZPopMan64.so 帶完整 DWARF，Ghidra headless 反編譯
`snapshots/42.20.4-20260829/native/decompiled/libPZPopMan64.so.c`；主分析＋codex 獨立復核一致，skill `pz-native-decompile`）：v3 的四個承重前提全數獲 native 級證實——
(1) **無位址快取**：全 lib 唯一 `GetDirectBufferAddress` 在 `ByteBuffer` wrapper ctor，六個 JNI 入口的 wrapper 全是棧上物件、每呼叫重取，無任何 buffer registry；(2) **position 語意**：wrapper `position=0` 起算、只 `CallIntMethod` 讀 Java
`capacity()`，native 從不讀寫 `java.nio.Buffer.position`——共享 position 競爭純屬 Java 側兩執行緒推進同一欄位，與 swap 專用 buffer 完全相容；(3) **29 bytes/筆對帳**：x/y/z float＋dir byte＋descriptorID/state/pathTargetX/pathTargetY int（`position += 0x1d`）；(4) **21 bytes/筆消費端**：`n_saveRealZombies` 逐 byte 解析 21B 記錄入
`ManagerMain::instance.saveRealZombieHack`，210=10×21 的 overlap 證據獲 native 直接確認。**一項歷史敘述修正**：「native 回報筆數可超過 buffer 容量」不成立——`n_getAddZombieData`
每筆寫完做預測性終止檢查（`length < position*2 - recordStart`），1024 buffer 正常路徑**恆 ≤35 筆/頁**；v2 clamp 觀測到的 `pageCount=35 > readable=28` 實為 Java 側 position
已被寫側推進的直接證據（把執行緒競爭根因定罪得更死），count-clamp 自始就是冗餘保險絲（ceiling 公式與 native 真相精確一致，無害）。官方 42.20.2 `readByteBuffer` 收編與 v3 的同構性由此獲 native 級追認。**備查（native 驗證的新發現，均非 v3 範圍）**：(a) 全 lib direct-buffer writer 恰 4（`n_getAddZombieData` 29B＋MPDebugInfo 三支 12B/9B/8B，後三者用自己的 private buffer、dedicated server 有 `GameClient.client` 守衛）、reader 恰 2（`n_saveRealZombies` 21B＋`MapCollisionData.n_squareUpdateTask` 9B），無 alias 可觸他人 buffer；(b) vanilla 疑似獨立窄窗：`n_beginSaveRealZombies` 清空→`n_saveRealZombies` 填入→worker `saveCell`/`save`
讀取的 `saveRealZombieHack` 全域 vector，主執行緒設 `thread.save=true` 前 pending cell save
可清空/替換同一 vector（靜態可達、中信心、無 runtime 證據，若未來 popman 存檔異常此為候選根因）；(c) `n_pathTask`/`n_getRadarZombieData` 寫 primitive array 後以 `JNI_ABORT`
release——JVM 若回 copy 則寫入被丟棄（HotSpot 實務上回直接指標，可攜性疑點備查）。

---

<a id="2i"></a>
## 2i. join 卡頓量測（觀測 patch）

> **退役（2026-09-02）**：本刀（LoginPacket ×3、CreatePlayerPacket ×4、ConnectPacket／ConnectCoopPacket 的 `receivePlayerConnect` 各 ×1、`GameServer.receivePlayerConnect` 的 `serverLoadNetworkCharacter` ×2）已移除。join 卡頓歸因任務完成：正式服 8/30–9/2 巡檢 REJOIN_TOTAL 常態 5–13ms，已無待答問題，量測 wrapper 不再需要常駐 patch 表面。復活方式：從退役前最後一版 13650e1 取回（`git checkout 13650e1 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

**動機**:正式服主迴圈實測 6–11 秒停頓集中在玩家 join／死亡重生換角(例:17:20:33–17:20:39
的 6.6s 正值 Player-C「replacing dead player」),但無法從 log 分辨時間花在哪一段。現有 LoginMetrics 只蓋 login 期的三個 DB 寫入,不含 join spawn 段。

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

`REJOIN_TOTAL − REJOIN_LOAD_CHARACTER` ＝ 其餘處理(全服廣播、ClientServerMap、preventIndoorZombies——private static 不可包)。

**量測不到的殘差**:`new IsoPlayer(...)` 建構子無法以 redirect 包
(INVOKESPECIAL `<init>` 的未初始化物件不可傳入 helper,verifier 禁止)——若各項總和遠小於 join 停頓,殘差＝ctor＋spawn 邏輯＋chunk 載入,屆時再做第二輪定位。

**驗證**:build 守門＝命中恰 4;SmokeCheck 結構斷言(四點改道且原呼叫歸零、wrapper 各
delegate exactly once、單一 sink、無 checked exception、僅用既有 Multiplayer sink);
JoinMetricsBehaviorTest 行為測試——以可 override write() 的 FakePacket 全象限覆蓋
(delegate 成功/receiver 與 argument identity/nonfatal sentinel identity/三種
delegate fatal 均不進 sink/sink nonfatal 不改結果/sink fatal precedence),
其餘 wrapper 以 null-receiver NPE 驗證;triggerEvent 每測試點用唯一事件名
(LuaEventManager 對未知事件先註冊再拋 NPE,同名第二次會成功)。部署後觀測:join 時四行 `[MinidoracatJavaPatch][JoinMetrics] op=… elapsedNs=…`,
對照停頓長度即可歸因。

---

<a id="2j"></a>
## 2j. Client 端貼圖管線門檻修復＋觀測（實體隱形，第一個 client patch）

**症狀與根因**:B42 MP 已知未修 bug——受害 client 看到隊友/殭屍/車輛「只剩影子和名牌、3D 模型不見」,>20 人在線觸發、relog 暫癒、log 全程無錯誤。四路反編譯 trace＋對抗評審定案的因果鏈第一環:`TextureIDAssetManager.waitFileTask` 以 50MB 的全域 DirectBuffer
水位當硬門檻(`while (getBytesAllocated() > 52428800L) sleep(20)`),超標時 2–4 條檔案載入執行緒無限 sleep(零 log、無 timeout);貼圖與 mesh 共用 FileSystemImpl 載入池,
管線停滯期間所有新進視野/剛被 Reset 的實體因全有全無 bake 閘門
(`ModelInstanceTextureCreator.render` 任一貼圖未 ready 整隻不烘)完全隱形,而影子
(FBORenderShadows blob 貼花)與名牌(UI batch)走獨立管線照畫。詳見
`docs/specs/zombie_core_textures_TextureIDAssetManager.json`。

**手術**(`PatchConfig.client()`,expectedHits=2,兩刀都在 waitFileTask 方法內):
1. redirect——`DirectBufferAllocator.getBytesAllocated()J` 改道
   `zombie/mdc/TexturePipelineGuard.bytesAllocatedObserved()J`(同形 ()J,回傳值原樣 passthrough、真實取值例外照原版傳播;觀測部分 try/catch 全吞、fatal 三件套
   VirtualMachineError/ThreadDeath/LinkageError 照拋,絕不改變載入行為)。
2. constChange——門檻 `52428800L`(50MB)→v1 `268435456L`(256MB)→v1.1 `1073741824L`(1GB)→**v1.2 `4294967296L`(4GB)**。**門檻語意(codex 對抗審查實驗修正)**:這是「已解碼未上傳」pixel buffer 的水位,但 WrappedBuffer 走 LWJGL native malloc,**不受
   -XX:MaxDirectMemorySize 約束**,且門檻是配置前檢查、多 worker 可同時通過——天花板不是硬上限。**v1.1 實測依據(Tester-A 兩場 log,2026-07-31)**:水位「地板」因棘輪洩漏單調上升永不下降(50→125→154→263→273MB 釘死),~35 分鐘追上 v1 的
   256MB 天花板→全部載入執行緒永久睡(~194 樣本/s)→隱形回歸。code 級洩漏點=
   ImageData 解碼例外路徑無 dispose(ctor 分支+APNG 迴圈中斷洩 compositeBuffer+
   getData() 64MB 懶配置)。**天花板只買時間,任何上限終被地板追上**;**v1.2 實測依據(Tester-A console(12),2026-08-02)**:開往路易斯的內容洪峰讓地板數分鐘暴增 550MB＋、單趟吃掉整個 1GB 天花板(floor 終值 1096MB>1024MB=管線永久死亡,
   只剩輪胎與影子)——**洩漏/常駐量與「看過的新內容量」成正比,非時間**;4GB 亦僅是更寬跑道(非硬上限、無時間保證),根治=ImageData dispose 修補(規劃中)。洩漏為 process 級 static,relog 清不掉
   (解釋社群 relog 時靈時不靈),完全重開遊戲才歸零——玩家指引以此為準。低 RAM(≤8GB)機器不適用本 patch。

**觀測輸出**(決策在 synchronized 內、`DebugLog.log` 一律在鎖外送出——避免慢速 log
串行化 2–4 條載入執行緒;非 fatal 觀測例外全吞,fatal 三件套照拋):`active` 宣告
(log 成功才設旗標,boot 極早期 DebugLog 未就緒時自動重試——此行是安裝驗證契約)、`hwmBytes` 高水位每跨 8MB 台階一行、水位高於原版 50MB 門檻時每 5 秒至多一行
`bytes/hwm/floorBytes/aboveVanillaMs/vanillaStallSamples/patchedStallSamples`、**v1.1 新增 periodic 行**(每 60 秒,無 stall/hwm 行時):`floorBytes`=60 秒窗最低水位=洩漏地板——**floorBytes 單調上升=洩漏進行中的直接證據,斜率=洩漏速率**。**語意精確版**:vanillaStallSamples＝would-enter-wait 取樣數(原版在該取樣點會進入至少一次 20ms 等待),單獨不證明持續饑餓;連續 stall 行＋`aboveVanillaMs`
(本次連續超標已持續毫秒數)才是持續停擺的證據;patchedStallSamples>0＝4GB
天花板也被地板追上(重開遊戲歸零,並回饋根治版優先度)。

**與 server 部署完全隔離**：`build-client.ps1` 現輸出 `work/out-client-modular`、`dist-client-modular/pkg` 與 `output/MinidoracatClientPatches-42.21.0-0.2.10.zip`（42.20.4 時為 `-42.20.4-0.1.0`），不寫入 server manifest。client 原有 classpath `[".", "projectzomboid.jar"]` 保持不變，由 loose class 覆蓋對應 class。`Install-Patches.bat` 使用模組 manifest 選裝
`core`、`profiler`、`client-fixes-standard`／`client-fixes-lowmem`／`client-fixes-compat`（後三者互斥），驗 jar／payload SHA 與所有權後才寫入。`Uninstall-Patches.bat` 可只卸載所選模組；保留仍被依賴的 core，不明或被修改的 class 一律拒碰。交易中斷可依原包與 state 備份復原。舊版 TexPipeline v3.0 包（42.20.3／42.20.4 兩版建置，`deploy-client/legacy-packages.json`）只看整組檔案指紋、不看目前的 jar：遊戲已更新而殘留時，安裝會換成新版，卸載會整組移除（0.2.4 起；之前因為綁舊 jar，遊戲一更新就認不出，玩家只能回頭找舊包 `uninstall.bat`）。認不出的殘檔仍拒碰，須先用舊包 `uninstall.bat`。Steam 驗證不會移除非 depot 的 loose class；遊戲更新前須先移除，而且**不可在 JVM 執行中卸載**。所有二進位產物只供合法持有遊戲者本機驗證，不入庫、不散布。

**和其他替換遊戲 class 的 MOD 並存（client 包 0.2.10）**：PZ_Optimization（Workshop 3805285544）也替換 `AnimationPlayer`、`ImageData`、`TextureID`、`TextureIDAssetManager`、`WorldStreamer`、`IsoChunk`、`RoomDef`、`FBORenderCutaways`，同一個 class 只能載入一份。第三個互斥變體 `client-fixes-compat`（`PatchConfig.clientCompat()`，Patcher／LoadCheck／SmokeCheck 的 mode 是 `client-compat`）只含 IsoTree、BaseVehicle、VehiclePacket 三個 class 的手術（自建房間 XL 樹、W54、W61–W63、W65），ClassPatch 直接取自 `client(false)`，手術與完整版逐位相同；貼圖、串流、W55、W57 不在內，W68 要鎖住全部呼叫點，所以 IsoBuilding、DataRoot、MetaGridPacket 也不裝。安裝器另有三道檢查：(1) 完整版和遊戲資料夾裡別人的 class 衝突、相容版不衝突時，互動選單建議相容版，code 7 訊息也指向它，並要求玩家不要只刪列出的檔案。[issue #1](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/issues/1) 的玩家只刪了外層 class，留下的 PZ_Optimization `AnimationPlayer$L_*` 把暫存欄位改成每個實例一份，原版與本包的 `AnimationPlayer` 仍用 `getstatic` 讀，第一次更新角色動畫就丟 `IncompatibleClassChangeError`，主執行緒結束、遊戲直接關閉。(2) 要寫入的 class 在遊戲資料夾已有不屬於本包的同一 class 家族檔案（`X.class` 或 `X$*.class`）時，以 code 7 拒絕。(3) 只讀 `ProjectZomboid64.json`：classpath 沒有把 `.` 排在 `projectzomboid.jar` 前面時，安裝與卸載都拒絕並說明怎麼處理。PZ_Optimization 的 AOT 快取（預設開）把 classpath 換成 `pzopt/aot/pzopt.jar` 並把所有 loose class 打包進去，之後要重建 jar 時 Windows 不讓它覆寫使用中的 jar（`AccessDeniedException`），本包的安裝與卸載都不會生效。`MdcPatchRuntime` 從別人的 jar 載入時，往上找有 `projectzomboid.jar` 的資料夾讀安裝紀錄，不再誤報 `compatible=false`。

舊版 v3.0 包登記的路徑裡有四個是原版 class（`TextureIDAssetManager`、`ImageData`、`TextureID`、`WorldStreamer`），PZ_Optimization 也換它們。只有自家 helper（`zombie/mdc/…`、`Minidoracat…`）或內容等於舊版那一份時，才把這些路徑上的檔案當成認不出的舊版包擋下；否則交給一般衝突檢查，不然相容版也會被誤擋（安裝器測試 57）。

**驗證**:build 守門＝命中恰 2;SmokeCheck client 模式——vanilla 前提守門(jar 內
waitFileTask 恰一個 getBytesAllocated＋恰一個 52428800L,PZ 改寫時建置失敗)、全序鎖(observed→4GB→lcmp→ifle)、sleep(20) 迴圈保留、helper 門檻常數與 bytecode
常數連動、真實 allocate/dispose passthrough smoke;LoadCheck client 模式(-Xverify:all＋簽名/常數連動);BytecodeVerify;TexturePipelineGuardBehaviorTest(真實
DirectBufferAllocator 真實配置驗 passthrough/50MB 跨越/dispose 歸零,1GB 門檻與
floor/periodic/優先序狀態機以反射 observe() 合成值驗證——不需 1GB 真實配置)。部署後觀測:console.txt 搜 `TexPipelineGuard`,`active` 行＝生效;隱形復發時對照 `vanillaStallSamples` 與 relog 時點即可對帳因果鏈。

### 2j-v2.0 洩漏根治第一波（S1/S2/S4/S6）

**定罪**(四路 retention trace＋對抗評審,全數源碼核實):1096MB 洩漏地板＝主犯 1(40-60%)**ImageData.dispose() 漏 frames**——APNG 動畫貼圖每幀全尺寸
buffer,dispose 只釋放 data＋mipMaps,零例外零 log 確定性洩漏=110MB 雙機基線主體;
主犯 2(20-35%)**getData() 固定 67108864(64MB)fallback**(不看實際尺寸)＋mip-flag APNG 因 getMipMapCount()==0→getMipMapData(-1) AIOOBE 跳過上傳尾端
dispose,單發漏 64MB+mip 鏈+全幀(=+64/+99MB 大跳);從犯=cancel 丟棄(5-15%,
掛證據門檻待遙測)＋setImageData 覆寫釘死(3-10%,第二波)。**「上傳後不釋放」主路徑假說不成立**——generateHwId 尾端有 dispose,地板全來自旁路。

**手術**(新手術型 head-call:visitCode 後插 `aload_0; invokestatic helper` 純線性無分支,visitMaxs 取 max(原值,1);helper `zombie.core.textures.MinidoracatTextureLeakGuard`
必須同套件——frames 為 package-private):
- **S1** dispose() head-call `disposeFrames`——迭代 frames 逐幀 dispose(isDisposed
  冪等閘;WrappedBuffer 雙重 dispose 拋 ISE)後 clear。安全論證:frames 讀取者全
  codebase 僅 AnimatedTextureID.setImageData(dispose 前執行且轉移後 frame.data=null)
  與 ImageData(ImageDataFrame) ctor。
- **S2** getData()/getMipMapCount() head-call `ensureData`——data==null 時以
  getWidthHW×getHeightHW×4 實際尺寸配置(64MB 分支成死碼),APNG 以第一幀內容填充
  (原版上傳全零=隱形,修後顯示第一幀=紅利);getMipMapCount 回真值→AIOOBE 家族歸位走完正常 dispose。壞檔(尺寸非正)不介入退回原版。呼叫者普查:getMipMapCount
  僅 2 內部呼叫者(即修復路徑);getData 讀取全以 w*h*4 為界。
- **S4** TextureID.createSteamAvatar 內 redirect `createSteamAvatarFixed`(唯一呼叫點)——逐語意重實作,失敗/例外路徑補 dispose(原版漏 65536B 且 UI 重試重漏)。
- **S6** TextureID.freeMemory() head-call `onFreeMemory`——原版只斷引用不 dispose
  (假釋放 footgun;42.20 零呼叫者,防禦性堵口)。

**驗證**:命中守門 7(head-call×4+redirect×1+v1.2 兩刀);SmokeCheck vanilla 前提守門(dispose 零觸碰 frames=TIS 未自行修復、getData 恰一個 64MB 常數、freeMemory
純斷引用、avatar 呼叫恰一)+head-call 全序鎖+redirect 歸零+MipMapLevel.dispose
呼叫數不變;行為測試(同套件直接存取 frames):幀對帳歸零、dispose 冪等、實際尺寸配置、第一幀內容填充、壞檔 fallback,全部對真實 DirectBufferAllocator;
install/uninstall.bat 改為 payload 逐檔生成閘門(5 檔:來源預檢/衝突/回驗/回滾/ownership 移除),fake-gamedir 手動 roundtrip 通過(未進 build gate)。**預期**:入服基線 110MB→<15MB、路易斯 +550MB→趨近 0、8hr 1096MB→<100MB,
地板穩定低於原版 50MB 閘門=隱形窒息路徑關閉(4GB 門檻降為第二道保險)。殘餘風險(文件化):未稽核的大量寫入路徑最壞=BufferOverflowException 有界落 log;
createSteamAvatarFixed 裸 JVM 不可測(結構鎖+人工 QA);ensureData 的 lazy-init
競態與原版同形不加劇。

### 2026-09-28 42.21 對版

**門檻與五個洩漏點官方都沒修，兩刀原樣保留**（重建即可）：
- `TextureIDAssetManager.waitFileTask()V` bytecode 與 42.20.4 完全相同（javap：`getBytesAllocated` → `ldc2_w 52428800` → `lcmp/ifle` → `sleep(20)`）；`TextureIDAssetManager`、`DirectBufferAllocator`、`zombie/asset/**`、`zombie/fileSystem/**`
  兩版一致。
- `ImageData.dispose`／`getData`／`getMipMapCount`、`TextureID.freeMemory`／`createSteamAvatar`
  五個目標方法 bytecode 全同：dispose 不碰 frames、getData 仍 `ldc 67108864`、avatar 失敗路徑仍直接 return null、freeMemory 仍只斷引用。只載 42.21 jar 的原版實跑重現：dispose 後幀
  buffer 仍在；frames-only 實例 `getMipMapCount()==0`；`getMipMapData(-1)` 拋 AIOOBE 且殘留
  64MB＋mip 鏈。
- 42.21 對 `ImageData` 的變更只有 `ImageData(String)` 改用 stb `NativeImage`（取代 ImageIO／PNGDecoder）與 power-of-two 函式替換，都不在實體貼圖主路徑（`FileTask_LoadImageData → waitFileTask → ImageData(InputStream,boolean)`）上。

**官方修的「只剩影子」是另一條鏈**（依程式碼推論）：`ZomboidFileSystem.getAllModFolders`
改為 `synchronized`＋`volatile`、清單填完才發布，`resetModFolders()` 同時重置 `modFolders` 與
`allowedPrefixes`。舊版競態下 `allowedPrefixes` 可能以半成品清單計算並被快取，worker 緒
`FileTask_LoadMesh` 等的 `validatePrefix` 會對 mod 路徑拋
`IllegalArgumentException("Invalid prefix found …")` → mesh 載入失敗 → 只剩影子。這條鏈**會留
log**，我方這條（水位門檻讓載入執行緒無限 sleep）**零 log**。

**回報分流**：42.21 之後的「只剩影子／名牌」回報，先看 console：
- 有 `Invalid prefix found` → 官方那條（42.21 理應已修；若仍出現，回報官方）。
- 有 `TexPipelineGuard` 的 stall 行（`vanillaStallSamples`／`patchedStallSamples` 上升）→ 我方這條。
- 兩者都沒有 → 另闢分析，不要把「官方說修了」當成本節兩刀可退役的依據。

---

<a id="2k"></a>
## 2k. 效能第一波：載具視線預篩＋VehicleManager 512→256

**立案依據**：fps-dip-sampler（低谷觸發 kill -3）累積 66 份 thread dump 聚合——載具幾何主題佔
~23% 最大宗（`isVehicleBetween → getIntersectPoint` 鏈＋`VehicleManager.serverUpdate`），負載瀰漫無單點病灶。Claude 五路平行讀碼＋逐項對抗驗證、codex 獨立讀碼（30 分鐘，含對真實 jar javap）雙審一致後定案。87 人低谷 4.4 FPS，預期回收 6–19% tick 時間（→4.7–5.4 FPS）；Amdahl 上限明確——全部熱點清光也到不了 10 FPS，**本波改善尾延遲，不是 100 人容量承諾**。

### 2k-1. IsoZombie.isVehicleBetween 保守包圍球預篩

原版對整個已載入 cell 的**每台載具**做完整 OBB 相交（未命中路徑每台 2 次 `Transform.inverse()`
矩陣求逆＋約 6 次向量池借還），無任何距離預篩；追擊中殭屍每 tick 執行。手術＝方法內唯一的
`getIntersectPoint` 呼叫點（javap offset 99，恰 1 處）改道 `VehicleIntersectPrefilter`：

- 先算「視線段到載具中心」平方距離，超出保守包圍球＝幾何上不可能相交＝直接回 null（呼叫端只判非 null，**語意嚴格等價**）；球內或任何異常＝原樣委派原版。
- 半徑 per-vehicle：`extents/2＋|centerOfMassOffset|` 的 **L1 上界**（≥ L2 半對角，必然偏大＝零 false-negative）＋1.0F 膨脹（吸收 getX/getY 與 jniTransform 物理原點次格差）、下限 6.0F。超長 MOD 載具自動放大。codex 定案否決：固定車長、端點距離、不含旋轉的緊 AABB。
- 雙審一致**否決**第二階段 TTL 結果快取（失效鍵須含旋轉/翻覆/拖曳/mod reload；且 result 是池化可變 Vector3f 不可持有）。
- 帶 `rejected/delegated/anomalies` 計數，每 2^24 次呼叫經既有 Multiplayer sink 印一行——
  **reject 率 >0.9 是本刀有效的判準**，也是 codex 設下的人數上限放寬前提之一。
- 只動本方法內呼叫點；`CombatManager`、`BaseVehicle.processRangeHit` 等其他呼叫者原樣。

### 2k-2.（42.20.2 官方收編，退役）VehicleManager.connected 512→256

`serverUpdate` 每 tick 無條件掃 `connected[]` 全部 512 slot × 全部載具做旗標傳播（實際發送另有 100ms 節流，~83% 呼叫純空轉）；dump 5/5 停在該迴圈回跳邊（LineNumberTable 對映
offset 175 = `goto 124`，發送段 0 命中）。而 RakNet index 上界實證 <256：`UdpEngine.connectionArray[256]`、ID 一律 `getByte()&255` 解碼、`setIndex` 全 jar 零呼叫者——**上半 256 slot 從未被寫入**。`<init>` 的 `sipush 512 → 256`（語境鎖：緊接
`anewarray UdpConnection`；負對照：同方法 `bipush 27`／`100L`／`1000L` 節流常數原樣），掃描成本精確砍半。`BaseVehicle.connectionState[512]` 刻意不動（同 index 界限，多餘槽位無害）。**失效訊號**：若出現 `ArrayIndexOutOfBoundsException` 且 stack 含 `connectionAdded`＝index≥256 反例，立即 uninstall 並推翻界限分析。

### 附帶：manifest 完整性守門（本波實踏的坑）

`build.ps1` 的 `$helperEntries` 是手寫清單——本波 helper 編進了 `dist/java` 但漏登記 manifest，`install.sh` 不會複製、上線即 `NoClassDefFoundError`（SmokeCheck 的 URLClassLoader 吃整個
dist/java 所以測不到）。已補雙向守門：dist/java 與 manifest 不一致＝建置中止。

<a id="2l"></a>
## 2l. 假死修復：removeGlassAttachments 無限迴圈保險絲

> **退役（2026-09-28，42.21.0 官方已修）**：官方把 `IsoGridSquare.removeGlassAttachments` 改寫成反向迴圈（`for (n = size-1; n >= 0; n--)`，命中後只呼叫 `RemoveTileObject`、沒有 `n--` 補償；javap 42.21 offset 54
> `iflt` 出口、205 `iinc 3,-1`），不論移除是否生效都在 size 次迭代內結束，無限迴圈從結構上消失。`RemoveTileObject(IsoObject,Z)` 的 DIFF 只是 `specialObjects` 改宣告為 `List`。保留 helper 反而會以舊的正向迴圈蓋掉官方新寫法，故刪除 `GlassAttachmentGuard` 與 SmokeCheck 斷言。復活：`git checkout 8d2bee8 -- patcher/game/zombie/mdc/GlassAttachmentGuard.java`（並回填 PatchConfig／SmokeCheck／build.ps1）。

**事故**：2026-08-02 17:48 全服假死（幀計數凍結 f:15924、所有玩家靜止、重登卡驗證、graceful stop 無效、pkill -9 恢復）。兩份間隔 4 秒的 thread dump 主執行緒皆 RUNNABLE
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

**手術**：`smashWindow(ZZ)V` 內唯一的 `removeGlassAttachments` 呼叫點（javap offset 221，全 jar 唯一呼叫者）改道 `GlassAttachmentGuard`：逐語意重刻原迴圈，唯一差別＝**清單真的縮短了才回退 index**；未縮短（原版死鎖分支）跳過該物件＋log
`[MinidoracatJavaPatch][GlassGuard] stuck glass attachment skipped at x,y,z sprite=…`。正常砸窗逐語意等價（清除、警報順序全不動）；病態案例從全服假死降級為一個物件未清除＋一行定位 log——**下次觸發直接知道問題物件在哪**。helper 無狀態零欄位，全 public API。TIS 官方修復後 uninstall 即回歸原版。

<a id="2m"></a>
## 2m.（42.20.2 官方收編，退役）效能第二波 P5：IsoCell 三清單 identity membership sidecar

**立案**：第一波後低谷頻率一度塌陷至 1–2 次/日，但 2026-08-03 晚間人數衝上 80（新高）後單晚觸發 6 次、FPS 探至 6.1，且 chunk 卸載主題重回榜首（新 dump 3/13＝23%，累計
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
`CellListMembership` 的六個 helper，跨三個 class：`IsoCell`(10)、`IsoObject`(1)、`IsoDeadBody`(4，經 getter 的旁路變異者，不鏡射必失同步）。

**關鍵設計決策**（v1→v2 重寫，Claude 6 項 important ＋ codex REDESIGN 五雷）：

- **不變量是 identity 集合而非 size 對等** —— 清單可含重複元素；`remove` 成功後以
  `list.contains` 複核才除名（codex 雷 1：否則 `[x,x]` 移一份就永久 false-negative，且 size 對帳永遠抓不到）。
- **generation bundle 取代 weak registry** —— codex 指出 `State.set→IsoObject.table`（Lua table）可反向釘住弱鍵使其永不釋放；改以 `IsoCell` identity 錨定，換代整組替換，零 GC 猜測。
- **removeAll 嚴格 gate＋尾端逐刪** —— 非 `ArrayList.class`／null 一律原生（NPE 與 subclass
  的 `c.contains` 副作用 parity 交給原生）；R 用固定大小索引快照（非 iterator，不引入 vanilla
  沒有的 CME 面）；尾端 `remove(i)` 每次 `modCount++` 精確還原 JDK `batchRemove` 語意（`subList.clear()` 只加一次，不等價）；例外則毒化 `expectedSize` 後重拋，不半提交。
- **kill 門檻只算 audit divergence** —— size 對帳 rebuild 只觀測不計（GO-WITH-FIXES：重度 MOD 環境的 Lua 良性旁路會自癒，不該累積成永久停用）。門檻 8 次，terminal。

**驗證**（18 個斷言）：8 個行為 differential（400 op 隨機序列含重複與 null、重複元素感知、等大小換血 ghost 自癒、20 次 size 漂移不 kill、divergence 達門檻永久 kill、未知清單降級、removeAll 四情境、補償迭代重入的訪問序列黃金比對）＋10 個結構斷言（六方法改道計數與原呼叫歸零、S3 負對照 `size×2/get×1` 原樣、S4 負對照 `ProcessStaticUpdaters` 零改道、S5 六個
contains 後綴必為 IFNE/IFEQ、全 jar hierarchy walk 斷言 IsoObject 全後代零 equals/hashCode 覆寫）。

<a id="2n"></a>
## 2n.（2026-08-08 退役：client 端無對應改道）受精蛋世界清除豁免（IsoGridSquare.load）

> **退役結論（2026-08-08）**：patch 本身有效，**退役原因是 server-only 改道在此路徑必然產生玩家端 desync**，不是失效。以下原始設計全文保留，作為「動 client 也會跑的判定路徑」的教訓案例。
>
> **生效證據**：正式服 log（`[MinidoracatJavaPatch][EggGuard]`）本次啟動 `keptLoads=3649`、`expiredLoads=0`、`anomalies=0`，累計 1678 行 kept、738 顆不同的蛋；單顆蛋（`<x>,<y>`，`dropTime=3770.763916015625`）的 `progress` 跨多次 chunk 卸載／重載由 557 推進到 1121/1260，全服最高 1148/1260 —— server 端一顆都沒被清掉。
>
> **為什麼還是退役**：`IsoGridSquare.load` 的清除區塊沒有 `GameClient.client` 守衛，而
> `SandboxOptions`（含完整 247 項 `WorldItemRemovalList`）由 server 在連線握手時就完整同步給
> client（`ConnectionDetails.writeSandboxOptions` → `GameClient` 端 `SandboxOptions.load`
> 逐項覆蓋）。client 載入 chunk 的兩條路徑（收 server chunk 封包、讀本地 MP 快取）都走同一個
> `IsoChunk.LoadFromDiskOrBufferInternal` → `gs.load()`，判定條件與 server 一模一樣卻沒有
> guard，於是**每次載入都自行把蛋濾掉**。判定是 item 狀態＋世界時鐘的純函數，輸入沒變結果就不變——重連、重開遊戲、清本地快取都一樣，蛋在玩家畫面上永遠不再出現，**也無法撿起**（client 的 square 上根本沒有那個物件），只能等它孵成小雞。玩家實際回報的正是這個現象。
>
> **決策**：回歸原版行為（受精蛋照 24 遊戲小時清除），引導玩家把雞養在雞舍（`IsoHutch`）下蛋——雞舍內的蛋不是 `IsoWorldInventoryObject`，本來就不經這條清除路徑。改道、helper、LoadCheck 簽名守門與 SmokeCheck 13 條斷言一併移除，`IsoGridSquare` 回歸原版位元組。
>
> **通則**：server-only patch 若落在 client 也會執行、且沒有 `GameClient.client` 守衛的判定路徑上，必然產生視覺／互動 desync。動手前先確認守衛存在，否則只有兩條路——連 client 一起改，或從設定層解決。

**立案**：正式服 `WorldItemRemovalList`（247 項）含 `Base.Egg`。查證後確認清除判定**無法區分受精蛋**，而現行參數讓地上的受精蛋 100% 在孵化前被刪除。

**根因鏈**（三段都有 javap／原始碼佐證）：

1. 判定只吃字串。`IsoGridSquare.load` offset 423-426 取 `worldItem.getItem().getFullType()`，之後全部比對都走 `SandboxOptions.worldItemRemovalListContains`＝`worldItemRemovalSet.contains(type)`（SandboxOptions.java:1305-1313），純 exact match，**看不到任何 per-instance 狀態**。
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

`Food.checkEggHatch` 的 `else` 分支（hutch==null 且不在容器時 `baby.addToWorld()`）是原版明確支援的「地上孵化」路徑，被清單設定切斷。

**手術**：改道 `load(ByteBuffer,int,boolean)` 內**唯一**的
`IsoWorldInventoryObject.isIgnoreRemoveSandbox()Z`（全 class 恰一處，位於
`aload → ifne → getstatic GameTime.instance` 的清除判定鏈上）到 `FertilizedEggGuard`。INVOKEVIRTUAL→INVOKESTATIC 同形替換，堆疊 1→1、指令長度不變、frames 不動。

**為什麼選這個 callsite**（四路審查獨立驗證，替代方案全部不可行）：

| 替代方案 | 為什麼不行 |
|---|---|
| `getFullType()`（offset 426） | 回傳值同時餵給 `ScriptManager.FindItem`＋`getObsolete` 判定、`type.split("_")` 前綴分支與四個 list 比對。改它等於**偽造 item type**，blast radius 大好幾倍 |
| `worldItemRemovalListContains`（4 處） | receiver 是 `SandboxOptions`、參數是 String，**拿不到 `worldItem`**；且要同時處理黑白名單四個分支才不破壞對稱 |
| `getWorldAgeHours()`（offset 600） | receiver 是 `GameTime`，**拿不到 `worldItem`** |
| head-guard 插入 | `load` 是巨型方法，依手術鐵則需 `EXPAND_FRAMES`＋補 `F_SAME`，本專案明訂保留給防崩潰守衛 |

offset 589 的 `aload 13` 是整條清除鏈上**唯一一個 `worldItem` 在堆疊上、且位於黑白名單分支之後**的位置。且 `isIgnoreRemoveSandbox` 的語意本來就是「豁免 sandbox 清除」，擴充它是語意延伸而非行為改寫。代價是：任何有界化邏輯**只能寫在 helper 裡**，因為沒有第二個 callsite
可以表達「延長期限」而非「永不清除」。

**效能**：四個 `listContains` 都在 589 之前且以 `&&` 短路，所以 helper **只在該 item 已命中清除清單時才被呼叫**，不是每個 world item 都跑；而同路徑對每個 world item 本來就會跑
`type.split("_")`（regex＋陣列配置）＋`ScriptManager.FindItem(type)`，成本高出 helper 數量級。

**豁免範圍刻意收斂**：`fertilized` 且 `animalHatch` 非空（與 `checkEggHatch` 的孵化 gate 用同一個 `StringUtils.isNullOrEmpty`）——孵不出來的「受精」蛋不佔額度。容器／背包／雞舍
(`IsoHutch`)／動物拖車內的蛋本來就不是 `IsoWorldInventoryObject`，不經此路徑。火雞蛋是
`Base.TurkeyEgg`、不在清除清單內，本節只影響 `Base.Egg`。

### 2n-1. 為什麼一定要有孵化視窗天花板（初版被審查推翻的論證）

初版寫「冷卻／烹煮／冷凍會由原版把 `fertilized` 設回 false，所以豁免自動失效、不會永久堆積」。**這個論證對地上物品是錯的**——三條路徑全部被容器閘擋住：

| 宣稱的失效路徑 | 實際 gate | 地上物可達？ |
|---|---|---|
| 烹煮 `heat>1.6`（Food:384） | 整段包在 `if (outermostContainer != null)`（Food:377）內 | **否** |
| 冷凍（Food:840） | 需 `isInFreezer(outermostContainer) && isPowered()` | **否** |
| 冷卻 `heat<0.5`（Food:634） | `temp = outermostContainer == null ? 1.0F : ...`，地上 temp 恆為 1.0，heat 被兩側 clamp 收斂到剛好 1.0 | **否**（只有「剛從冷凍庫拿出來就丟地上」的邊角會觸發） |

根因是 `IsoWorldInventoryObject` 建構子對 item 做 `setContainer(null)`，而
`InventoryItem.getOutermostContainer()` 對 null／`type=="floor"` 的 container 一律回 null
⇒ **地上物品的 `outermostContainer` 恆為 null**。補刀：`isRotten()` 對 fertilized 恆回 false，腐敗也清不掉。

唯一真實的出口是孵化，而 `fertilizedTime` **只在該 chunk 載入期間推進**（`processWorldItems` 由 `addToWorld` 填、`removeFromWorld` 清）——所以**少載入的 chunk 上，受精蛋既不孵化也不再被清除，就是永久豁免**。

因此有界性不能靠推論原版行為，改由 helper 自己保證：

```java
// 豁免視窗＝dropTime + HATCH_WINDOW_MULTIPLIER(4.0) × timeToHatch（世界小時）
// dropTime==-1（無法定界）或 timeToHatch<=0 一律不豁免，交還原版清除
```

取 4 倍是因為 `fertilizedTime` 只在載入時推進、世界時鐘遠快於它——常載入的基地 chunk 綽綽有餘，永不載入的 chunk 則保證被回收。母雞 1260h ⇒ 視窗 5040 世界小時（210 遊戲日）。這也是本 patch 唯一的可調旋鈕（`LoadCheck` 斷言它必須是有限正數，`SmokeCheck` 與它連動）。

**已知取捨：累積的成本在主執行緒，不在存檔。** 被留下的每顆蛋都成為 `processWorldItems` 的常駐 updater，而 `IsoCell.addToProcessItems` 是 `ArrayList.contains` 線性掃描——2m 的
`CellListMembership` sidecar **沒有覆蓋** `processItems`/`processWorldItems` 這兩張清單。天花板把它變成有界，但仍需按下方第 10 項的門檻盯著。

**已知取捨：client 不改。** `IsoGridSquare.load` 的清除區塊沒有 `GameClient.client` 守衛，而 client 在 MP 也存本地 chunk 快取、`SandboxOptions` 由 server 同步——所以可能出現「伺服器留著蛋、玩家端本地載入時把它從 square 刪掉」的視覺 desync（小雞看似憑空出現）。無資料遺失，**但驗證時不能只靠遊戲畫面目視**，見下方第 10 項。

**驗證**（SmokeCheck 13 條＋LoadCheck 簽名/常數守門）：vanilla 前提守門（`isIgnoreRemoveSandbox`
恰一＋4×`listContains`＋1×`getWorldAgeHours`，TIS 改寫 `load` 時建置失敗而非默默錯位）、改道恰一＋原呼叫歸零、清除鏈其餘判定未被動（負對照）、**位置全序鎖**（`aload → guard → ifne → getstatic GameTime.instance`）、**分支方向鎖**（guard 回 true 的去處必須等於清除鏈尾端「未過期＝保留」的去處——若 TIS 把語意反轉成「要清除」，指令形狀與命中數完全不變，只有這條擋得住）、**匯流鎖**（四個 `listContains` 分支中判定為命中清單的三條必須全部落到 guard，3/3）、helper 先取 vanilla 值恰一次、static 欄位僅 primitive＋log 前綴，加五組行為 smoke（委派 vanilla／判定六種輸入／孵化視窗四象限／端到端＋log 分支確實執行／**全程零 anomalies**——沒有最後這條，期望 `false` 的斷言在「helper 全程吞例外」時也會綠燈）。逐項 javap 證據見 `docs/specs/zombie_iso_IsoGridSquare.json`。

<a id="2o"></a>
## 2o. Client chunk 串流觀測（v2.1→v3.0，黑邊事件鑑識）

**動機**：2026-08-11 同一玩家兩起「黑邊」實案（凌晨 3 點卡死 10 分鐘不可恢復需重開遊戲、早上 7 點卡 5 分鐘自癒）。server 端同時段實測全綠（tick 正常、該 client 持續收到 Toxic
廣播＝網路活著），卡點在 client 串流管線；但事故 log 被翻譯警告洪水沖掉（另案修復於
MinidoracatLangFor42），無法定位是哪一環。本 patch **純觀測不改行為**，為下一次發作留證。

**觀測對象**（`zombie.iso.WorldStreamer`，簽名與私有欄位已 javap 對真 jar 驗證）：請求生命週期 sendRequests→pendingRequests1＋mainThreadRequestQueue→updateMain 發
RequestZipList→sentRequests→receiveChunkPart/receiveNotRequired 配對 requestNumber→
loadReceivedChunks 完成。**待驗假說**：(a) `requestingLargeArea` 期間 `pendingRequests1>20`
時 sendRequests 頭部 gate 完全停送新請求；(b) server 端 `ClientChunkRequest.getRetryChunk`
重試 ≥3 次回 null＝永久放棄該 requestNumber——兩者疊加＝pending 永遠清不掉、新請求全面停擺＝黑邊永不恢復。開大地圖觸發 largeArea 模式，與「黑邊時大地圖也打不開」的症狀吻合。**42.20.3 起假說 (b) 失效**：重試機制（`getRetryChunk`／`retriesCount`／`MAX_CHUNK_SEND_TRIES`）整個刪除，未生成 chunk 改由 pending 機制＋`ChunkNotReady` 封包主動告知 client（見 2p 遷移記錄）——重評 W4-2 時勿再以 (b) 推理。

**手術**（三處 headCall，全部 receiver-only `(Lzombie/iso/WorldStreamer;)V`，helper `zombie.mdc.ChunkStreamObserver`）：
- `updateMain()V`：心跳。每 10 秒視窗才反射讀佇列水位（平時每幀只做一次時間比較）；卡滯判定：有未完成請求且 >30 秒零接收→`STALL noReceiveMs=…` 行（每 10 秒至多一行，含全部佇列水位＋largeArea 旗標）；常態每 60 秒 periodic 行（無活動不報，單機安靜）。
- `receiveChunkPart`／`receiveNotRequired`：接收計數＋lastReceive 時戳（黑邊期間計數凍結＝斷流方向的直接證據）。

**安全論證**（codex 對抗審查修正兩處後定稿）：headCall 於方法首指令前插入
`aload_0; invokestatic`（進入點堆疊為空，參數與 locals 不動）；helper 非 fatal 例外一律吞（fatal 照拋）。**執行緒模型**：receive 三掛點由 UdpEngine 網路執行緒呼叫（經 GameClient.addIncoming）——與主執行緒**零共用鎖**（審查抓到初版共用 class
monitor 會讓封包處理被主執行緒的反射/組字串卡住＝改變行為），receive 路徑只做
AtomicLong 遞增＋volatile 時戳，決策狀態全部主執行緒單獨持有。**STALL 雙基準**：outstanding 上升沿與最後接收都 ≥30 秒才報（審查抓到單基準會在「閒置數分鐘後剛發新請求」時假報）；基準污染以「心跳斷檔 >30 秒＝重置」防護（Claude 審查修正：不持有 WorldStreamer 參考——static 強參考會釘住退役實例的 IsoChunk 串列＋native
Inflater＝改變行為；斷檔法同治 relog／in-place 重連／凍結三情境）。反射欄位漂移→
一次性 disabled 宣告後永久降級僅計數。SmokeCheck：vanilla 錨定（updateMain 觸碰
`GameClient.connection`＋零既存 observer 呼叫）＋四 headCall 全序鎖＋receiveChunkPart
原體保留（sentRequests 觸碰數不變）＋**八個反射欄位的名稱/型別契約守門**（漂移＝建置失敗而非默默降級）。行為測試全時間注入：STALL 雙基準/節流、閒置後新請求不假報、實例更換重置、靜默抑制、復原。

**判讀指南（42.20.3／v3.0 版；舊版 (a)+(b) 假說判讀已隨重試機制刪除失效）**\
（注意 `reqQ0` 計的是 chunk **串列頭**數——每個元素是 `chunk.next` 串起的整條清單，`reqQ0=1` 可能代表 1 也可能代表 200 個 chunk，展平後才進 `reqQ1`）：
- `STALL … notReadyAgoMs=` 小值（秒級）且 periodic 的 `notReady=` 持續上升 →
  server 活著但一直回「沒生成好」＝**生成端瓶頸**（42.20.3 pending 機制的 30s 生成逾時／4096 超限路徑），去 server 端查 `the chunk %d,%d was not generated` 警告與世界生成負載；不是斷流。
- `STALL … notReadyAgoMs=-1`（或大值）＋parts 凍結 → 連 NotReady 都沒有＝**全斷流**（網路層/連線問題），比對 server 端該連線的發送狀態。
- `STALL … pending1=20 largeArea=true` → 假說 (a)（largeArea 停送 gate）候選，另收集 largeDl 序列驗證；假說 (b)（server 重試放棄）42.20.3 起不存在，勿再引用。
- `STALL` 但 parts 持續增加 → 接收活著、載入端（DoChunk/refs）卡住，另闢分析。
- 無 `STALL` 行但玩家見黑邊 → payload 仍在到達：卡點在 WorldStreamer 之外（IsoChunkMap/渲染層），或 NotReady 的延後重排循環過長——對照 periodic 的
  `notReady=`、`sent=` 變化與 vanilla 警告
  `the server did not generate the chunk %d,%d in time, requesting it again`。

**v3.0（42.20.3 重建＋三 lane 對抗審查修正，2026-08-17）**：三 headCall 錨點與八個反射欄位逐一重驗健在；**擴充第 4 headCall `receiveChunkNotReady(I)V`**——42.20.3 新協定中
server 對未生成/超限 chunk 的主動回覆。vanilla 完整生命週期（javap＋反編譯實證）：drain
sentRequests→pendingRequests 後，把 flagsWs&1 與相符 requestNumber 的 entry 移出**網路緒**
pendingRequests 並標 flagsUdp|=16/24；同一請求物件仍在 streamer 緒的 pendingRequests1，由 loadReceivedChunks 依 flags 收尾——chunk 仍被引用時重新入列 chunkRequests1（**延後重排**，vanilla 同時印 `the server did not generate the chunk %d,%d in time, requesting it again`），不再需要時歸還 chunkStore 池。**獨立基準設計**：hook 只更新 lastNotReadyNs、不碰 payload 基準（lastReceiveNs）——
STALL 維持「30 秒無 payload」語意，生成瓶頸（server 短週期持續回 NotReady）不被靜音；STALL 行帶 `notReadyAgoMs` 分型（初版「NotReady 也算接收」設計會讓新協定最可能的黑邊形態永遠不觸發 STALL，Claude lane 抓到後改為雙基準）。四 headCall 全序鎖＋新協定方法存在性 census 進 SmokeCheck；行為測試補獨立基準/分型與 notReady-only periodic 案例。**lowmem 變體（v3.0-lowmem）**：≤8GB RAM 機器（42.20.3 隱形實證玩家 8101MB＋Xmx3G）不適用 4GB 等待門檻（gate 為配置前水位檢查、非硬上限——多 worker 可同秒通過、單筆配置不受限，native 最壞用量高於 4GB）——Patcher 顯式 `client-lowmem` mode：不做 constChange、redirect
指向 `bytesAllocatedObservedLowMem`（effective 門檻 50MB 烘進 helper，橫幅與 stall 分類以實際生效值計），觀測與洩漏根治線全保留。

### 2026-09-28 42.21 對版

**官方改寫了請求生命週期**（javap 對 42.21 jar）：`WorldStreamer` 刪除 `requestingLargeArea`、`largeAreaDownloads` 與 `requestLargeAreaZip`；`sentRequests→pendingRequests` 的 drain 與
`flagsWs&1` 取消收割從 `receiveChunkPart`／`receiveNotRequired` 移到新的 `udpUpdate()`，由 `GameClient.addIncoming` 在每個進來的封包前呼叫；`sendRequests` 頭部停送 gate（`pendingRequests1.size() <= 20` 才送，`bipush 20; if_icmple`）改為**無條件**，原本只在
largeArea 期間才有的假說 (a) 變成常態。四個 headCall 的掛點語意不變（`updateMain`／`receiveChunkNotReady` 方法 SAME，兩個 receive 方法頭仍是 payload 到達）。

**改了什麼**：
- helper 不再反射已刪的兩個欄位。舊版在 42.21 會 `NoSuchFieldException` → 反射永久停用 →
  佇列全是 -1 → STALL 永不判定，觀測線被靜默閹割。STALL／periodic 行移除
  `largeArea=`／`largeDl=`，新增 `sendGate=open|closed|?`（`pendingRequests1>20`＝closed；反射停用時 `?`），門檻常數 `SEND_GATE_PENDING1=20` 只用於標示、不改判定。
- 反射讀態抽成 `readAndDecide(now, ws)`（`onUpdateMain` 行為不變），供行為測試走真實路徑。
- SmokeCheck：欄位契約改為 6 欄位；原「receiveChunkPart sentRequests 觸碰數不變」在 42.21
  恆為 0==0，改釘 `receiveChunkPart`／`receiveNotRequired` 的 `pendingRequests` 觸碰數不變且非零；新增 vanilla 前提「sendRequests 停送 gate＝`getfield pendingRequests1 → size →
  bipush 20 → if_icmple`、方法內恰一個 20，且 helper 常數＝20」。
- 行為測試新增 sendGate 標示案例，以及一個對遊戲 jar 真實 `WorldStreamer.instance` 走
  `readAndDecide` 的案例（pendingRequests1 塞 21 筆，31 秒無接收必出 `STALL … pending1=21
  sendGate=closed`）。反射任一已刪欄位的突變體會讓此案變紅。

**判讀更新**：`STALL … sendGate=closed` → client 因在途上限完全停送新請求；搭配 `pending1`
長期卡在 21 以上且 parts 凍結＝在途請求沒收尾（server 未回或回覆被丟）。上方判讀指南中
`largeArea=true` 一條只適用 42.20.x。

<a id="2p"></a>
## 2p. chunk 供給併包（W4-1 v2，server）＋請求逾時 8s→15s（W4-2，client，已撤刀）

> **2026-09-07 復活為 v2（預設 observe）**。9/2 的退役（「packed 47–82/session、skip[short]
> 99.3%＝效益≈0」）是誤判，翻案證據見 2p-1、v2 設計見 2p-2；v1 分析全文保留於 2p-v1。v1 最後一版：13650e1。

### 2026-09-28 42.21 對版（程式碼不動，待重新 observe）

server 端沒變：`PlayerDownloadServer.update()`／`removeOlderDuplicateRequests()` 方法體與 42.20.4
相同，仍是 ready 閘內每幀 `ccrWaiting.remove(0)` 一個 ccr；`ClientChunkRequest.isChunksFilled`
的 20 分割門檻與 `RequestZipListPacket.parse`「每包新配 ccr、同包滿 20 才換」也沒變。三個掛點命中數不變。

**client 端新增在途上限**：42.21 的 `WorldStreamer.sendRequests` 對所有請求都套用「`pendingRequests1` ≤20 才補單、補到 ≥40 就停」（42.20.4 只在 largeArea 下載時才有這兩個門檻，一般請求在途無上限）；`refs` 為空的 chunk 也改成一律剔除、不送出。`pendingRequests1` 要等
chunk 收齊才移除，所以每個玩家同時在途最多 40 chunk，server 端的 `ccrWaiting` 實務上最多 2 個（至多 3 個部分填滿的）ccr。

影響：下方 2p-0 的供給模型（「client 每幀把一列打成一包、在途無上限」）與 42.20.4 期間蒐集的
`[ChunkPacker]` observe 數據（`depth`、`would.merge`）在 42.21 不再適用。server 端「每幀一個 ccr」的上限仍在，enforce 理論上仍可把每幀交付從 20 提到約 38（`batch` >40 在 42.21 用不到），但瓶頸也可能改落在 client 視窗。維持 observe 預設，等 42.21 晚峰重新蒐集 heartbeat 後再決定是否
enforce；判讀時改看 `depth=2` 的比例（`depth≥3` 幾乎不會出現）。另外 `RequestLargeAreaZipPacket`
在 42.21 已刪除；helper 裡的 largeArea 分支在 42.20.4 就已不可達，屬死碼，本次不動。

### 2p-0. 真正的供給模型（2026-09-06 22:50 實案，42.20.4 反編譯）

**事故**：74 人在線、玩家 ping 9ms，公路高速直行時行進方向大片未載入黑區。server log 幀計數差分：22:49:30 4.86 fps → 22:50:00 **3.18** → 22:52:00 **2.69**，22:53 起 `Server is too busy`
連發；該 session 零 `not generated`／零 `stayed outside`／零 `ChunkNotReady`（不是生成路徑）；該路段 chunk 檔 2–3 KB（`map/1425/109x.bin`），頻寬與 ping 不是瓶頸。

**供給鏈**（行號為 42.20.4-20260826 快照）：
1. client `IsoChunkMap.ProcessChunkPos`（:864-955）每幀在中心 chunk 改變時走一步
   `LoadLeft/Right/Up/Down`，對整列 `chunkGridWidth` 個 chunk `LoadChunkForLater`→
   `WorldStreamer.addJob(…, true)`（:344-374）串成同一條 `chunkHeadMain` 鏈；1080p 以上
   `chunkGridWidth=19`（`CalcChunkWidth` :90-122，clamp 19）；載具內中心前移
   `speedKmH/5` squares（:868-878）。
2. `WorldStreamer.threadLoop`（:327-419）有 pending 時每 20ms 一輪，否則 140ms；`sendRequests`（:108-145）非 largeArea 無在途上限，但有 `shouldSendChunk` 距離閘（:147-164，相對任一已知玩家 `|dx|<10 && |dy|<10` chunk）；`updateMain`（:167-215）主執行緒一幀把全部 request
   chain 合成最多一個 `RequestZipList` 封包。
3. server `RequestZipListPacket.parse`（:45-69）每包配一個新 ccr（來自物件池），只在**同一包內**滿 20（`ClientChunkRequest.isChunksFilled` :30-32）才換下一個，從不接到上一包未滿的 ccr。
4. `GameServer` 主迴圈每幀對每連線呼叫一次 `PlayerDownloadServer.update()`（GameServer :1035-1052；PlayerDownloadServer :212-254）：只在 `workerThread.ready` 時 `ccrWaiting.remove(0)` **一次**；已載入 chunk 在主執行緒 `SaveLoadedChunk`（完整序列化）、未載入走 worker `SafeRead` 磁碟、檔案不存在走 pending→生成→30s `ChunkNotReady`。worker `sendArray` 返回才 `ready=true`（不等 ACK）。
5. `SentChunkPacket` 每片 1000 bytes、`reliability=2`＝RELIABLE（**非** ordered）。
6. client `IsoChunkMap.updateInternal`（:175-234）每幀整合 `1+floor(3q/19)` 個已到達 chunk。

⇒ **每玩家供給上限＝主迴圈 fps × 一列**。3.2 fps × 19 ≈ 61 chunk/s；時速 100（若真 27.8
squares/s）直行需 ~66、45° 斜行 ~93（√2 倍）；每批還要等下一幀（平均 +150ms，低谷 +700ms）。車內 auto-zoom 讓可見前端逼近視窗前緣：codex 推算 zoom 2.5＋駕車 pan 時從請求放行到黑列進畫面的餘裕只有 **0.26 s**（zoom 1 時 1.66 s）——不是「載不完」，是「晚幾百 ms 就露出來」。codex gpt-6-astra（ultra）對抗審查修正三點：斜行 √2 倍非 2 倍、`SentChunk` 非 ordered、儀表速度含 `getFakeSpeedModifier`（`120/min(SpeedLimit,120)`）可能高估實際車速；並指出 native
send buffer 未量測、`shouldSendChunk` 距離閘可能讓 look-ahead 延後 ~0.7s 才送出。

### 2p-1. v1 為什麼被誤退役（翻案證據）

舊 helper 統計順序：`queue<2`→`skipShort`，再 `head.largeArea`→`skipLarge`，再
`head.size()>=BATCH`→`skipFull`。BATCH=8 而一列就是 19 ⇒ 只要佇列 ≥2，隊首必 ≥8 ⇒ **永遠
skipFull、從未併包**。9/1 21:21 session：`packed=82 skip[short=4,288,182 full=66,823
budget=2,651] overrunTicks=2,405`——「佇列 ≥2 個 ccr」一個晚上發生近 7 萬次（每 tick 每連線一次呼叫，≈每幀 1.4–2.4 條連線處於積壓）；overrun 閘 150ms 以 10 fps 設計，3–5 fps 下幾乎每 tick 觸發（2,405 ≈ 2,651）。`skip[short]` 99.3% 是 74 連線×每 tick 全呼叫的分母稀釋，沒有資訊量。**「效益≈0」是刀沒開，不是需求不存在。教訓：退役前先確認刀有沒有真的開過。**

### 2p-2. v2 設計（2026-09-07）

- **批次上限突破 vanilla 20**：`chunks` 是無上限 ArrayList，消費端 `update()`／`sendArray`
  依 `chunks.size()` 迴圈；20 只是 parse／pending 的分割門檻 ⇒ **不動 `isChunksFilled`**，helper 直接把上限提高。預設 38＝兩列、`-Dmdc.chunkPacker.batch` clamp 1..60（三列）。
- **overrun 閘預設停用**（`-Dmdc.chunkPacker.overrunMs=0`），改以每 tick 全域額外搬移預算
  `-Dmdc.chunkPacker.windowBudget`（預設 200）節流主執行緒序列化；tick 邊界改在 `update()`
  頭部偵測（每幀每連線必經，不依賴佇列 ≥2），gap 門檻 80ms。
- **三態 `-Dmdc.chunkPacker`**：`0|off`／`1|enforce`／`2|observe`（預設）。observe 佇列一個位元組都不動、只算 would-merge。
- **三掛點（同一 `PlayerDownloadServer` ClassPatch）**：
  1. `removeOlderDuplicateRequests()V` 頭部 headCall `packQueue`——ready 閘內、vanilla 去重之前（與 v1 相同；掛點安全論證見 v1「W4-1 手術」段）。
  2. `update()V` 頭部 headCall `onUpdate`——**閘外，只計數＋tick 邊界＋heartbeat，不碰 pds 任何欄位**（閘外與 worker 共用 `bb/sb/bbw` 與 `cancelled` HashSet）。與 `readyCalls` 相減＝ready=false（worker 跨幀）的比例。
  3. `update()` 內唯一的 `IsoChunk.SaveLoadedChunk(Chunk,CRC32)V` 1:1 改道 `saveLoadedChunk`（receiver 前置，例外原樣透傳給 vanilla 的 catch→`sendNotRequired`）：量主執行緒序列化耗時＝enforce 的代價。
- **絕不拆 ready 閘**（codex 一致）：連塞多個 command 後 worker 每完成一個就 `ready=true`，主執行緒會穿閘與正在跑的 worker 競爭 `bb/sb/bbw` 與 `cancelled`；「純磁碟 ccr 讓 worker
  自行接續」不是可靠分類（已載入未落盤的內容必須主執行緒序列化）且是管線重設。
- heartbeat 每 5 分鐘一行：`updates/ready/notReady`、`depth[0/1/2/3-4/5+/max]`、`head[avgX10/max/full20]`（＝client 每包大小）、`would[pack/merge]`、`packed/merged`、`skip[short/large/full/noSource/budget/dupAbort]`、`save[calls/avgUs/maxUs/tickMaxMs/ticks/>5/>20/>50ms]`、`overrunTicks`、`anomalies`。
- **驗證**：SmokeCheck——vanilla 前提（update 三個 `List.remove(I)`、1 dedupe、1 `SaveLoadedChunk`、零 helper 呼叫；dedupe 全 class 僅被呼叫 1 次＝閘內事實）、兩 headCall 全序＋update 內零
  `packQueue`、改道 x1 原呼叫歸零真指令 +2、dedupe 原體保留真指令 +2、三 public 欄位契約、`isChunksFilled` 恰一個 `bipush 20` 且 `update`／`sendArray` 零 20 常數各 ≥1 `size()`（TIS 若在消費端加硬上限即紅＝重評 BATCH）、helper `saveLoadedChunk` 零 catch。行為測試四組態（observe／enforce／enforce+windowBudget=0／off；含真 `PlayerDownloadServer`
  走 `packQueue` 的 mode 分流）：守恆、上限 BATCH、重複整次放棄（三情境）、largeArea、順序、預算閘、tick 重置、深度統計。
- **開 enforce 的判準**（一個晚峰後看 heartbeat）：`depth≥2` 佔 `ready` 的比例與 `would.merge`
  顯著（＝真的有兩列以上在等）、`save.tickMaxMs` 在 3 fps 幀長（~300ms）的 10% 以內、`notReady` 比例低（worker 沒有跨幀，併大批不會讓 ready=false 更久）。enforce 後驗收＝`packed/merged` 上升、開車玩家黑邊回報下降、`anomalies` 恆 0、主迴圈 fps 不降。
- **效益上限**：每幀交付 2–3 列 ⇒ 3.2 fps 下 120–180 chunk/s，臨界車速 ×2–3；直行單包（佇列恆 ≤1）時收益為零——那時只剩主迴圈 fps 這個乘數。

### 2p-v1. v1 分析（2026-08-13，全文保留）

**根因**（八路鑑識＋對抗驗證；完整設計見 `docs/chunk-throughput-design-v1.md`）：vanilla 的 chunk 供給只跑到設計值的 15%——client 每幀送一包 `RequestZipList`（約 3 chunk）→
`RequestZipListPacket.parse` 每包無條件 new 一個 `ClientChunkRequest` 入列（從不併入未滿的
ccr）→ `PlayerDownloadServer.update()` 每 worker 週期只處理一個 ccr（10Hz）＝實際約
30 chunk/s，而 `NON_LARGE_AREA_CHUNKS_LIMIT`=20 × 10Hz = 200 chunk/s 的預算浪費 85%。積壓越過 client 的 8 秒逾時後，`resendTimedOutRequests` 設 `flagsWs|=9` →
`loadReceivedChunks` 丟棄**已送達**的整包資料並重新排隊、且不通知 server 取消 →
自我維持 livelock（實測 pending 恆＝請求率×8s＝240、18 分鐘重發約 141 輪、燒掉約 105MB 全丟棄、零 chunk 載入＝永久黑邊）。`PlayerDownloadServer` 是
per-UdpConnection daemon thread，故只有該玩家卡、server 全域指標全綠、同安全屋別人正常。

**W4-1 手術**：`PlayerDownloadServer.removeOlderDuplicateRequests()V` 頭部 headCall →
`zombie.mdc.ChunkRequestPacker.packQueue`，把佇列前段併包到批次上限。**掛點不是 `update()V`**（審查抓到的 blocking；以下為 42.20.2 當時的分析，42.20.3 現況見下方遷移記錄）：`update()` 對 `ccrWaiting` 的存取全包在 `if (workerThread.ready)` 內，那是與 WorkerThread（42.20.2 的 `sendArray` 會 add `ccrForRetries` 並持續 `chunks.add`；42.20.3
起 worker 已不寫 `ccrWaiting`）互斥的唯一機制；插在 offset 0 會落在閘外，最壞情況是同一
`Chunk` 實例雙重 `releaseChunk` 進 **static** `freeChunks` 池＝跨玩家汙染。`removeOlderDuplicateRequests` 全 class 僅被 `update()` 呼叫一次（javap 實證）且就在閘內、vanilla 去重之前。

去重語意保留：vanilla 只偵測跨 ccr 重複，故隊首已含同 `(wx,wy)` 者跳過不搬，留給 vanilla
去重原樣處理。搬空的 ccr 由同一方法後段的 vanilla 本體移除並回收進物件池。largeArea 不介入。

成本閘：批次上限預設 **8**（vanilla 上限 20 的 40%）、全域每 100ms 視窗「額外搬移」預算預設 **120**，皆可用 `-Dmdc.chunkPacker.batch` / `-Dmdc.chunkPacker.windowBudget` 調整（後者設 0 即整刀停用，等同 vanilla，**緊急降級不需重新部署**）。

**W4-2 手術（42.20.3 已撤刀）**：`WorldStreamer.resendTimedOutRequests()V` 的 `8000L`→`15000L`（方法內常數替換，全 class 僅此一處）。`RequestZipList` 與 `SentChunkPacket` 皆
`reliability=2`（RELIABLE），故此逾時幾乎不是在救真的遺失，而是在懲罰 server 慢。**42.20.3 起 vanilla 整個刪除該方法**（盲等逾時重發由 `ChunkNotReady` 主動通知根治）——手術目標不存在，撤刀；SmokeCheck 的 W4-2 雙向常數斷言同步移除。

**驗證**：SmokeCheck——vanilla 前提（`update` 恰 3 個同簽名 `List.remove(I)`＋1 個 dedupe
呼叫；`resendTimedOutRequests` 恰 1 個 8000L）、**掛點在 ready 閘內**（dedupe 頭部全序 ＋`update()` 內零 packer 呼叫，把 B1 鎖進建置期）、update/dedupe 雙邊原體保留、三個 public
欄位契約、`isChunksFilled` 的 `bipush 20` 綁定（TIS 調小而我們沒跟＝超發）、W4-2 常數雙向斷言。行為測試 8 案：守恆／批次上限／去重保留／largeArea 雙向／順序／退化輸入／上限不超過 vanilla／視窗預算封頂。**42.20.3 遷移記錄（2026-08-17）**：TIS 同戰場重構（修「Loading Map forever」）——
pending 機制（`PendingChunk`≤4096／`OutOfRangeRequest`≤1024／新封包 `ChunkNotReady`）、**server 重試機制整個刪除**（`Chunk.retriesCount`、`MAX_CHUNK_SEND_TRIES`、`getRetryChunk`
移除）、worker 回填改 `queuedByWorker` concurrent queue＝WorkerThread 不再寫 `ccrWaiting`（掛點互斥前提更寬鬆，掛點不動）。`update()` 呼叫序變為 ready 閘 → `updatePendingChunks()`
→ dedupe（掛點）；pending 回填的 ccr 是普通 non-largeArea ccr，被併包安全。**吞吐瓶頸未修**（`RequestZipListPacket` 逐位元相同、每 tick 仍一個 ccr）＝W4-1 存續；官方 changelog 自承黑邊「additional causes 仍在調查」。client 側：`WorldStreamer` 被實質重構——**W4-2 撤刀**（目標方法 `resendTimedOutRequests` 已刪除）；v2.2 包全面失效，**已以 v3.0 重建**（觀測線重驗健在＋擴充第 4 headCall `receiveChunkNotReady(I)V`——獨立基準 lastNotReadyNs
計數新協定回覆、STALL 維持無 payload 語意並以 notReadyAgoMs 分型；texture 線三 class 逐指令相同原樣沿用；42.20.3 client/server jar 整檔 SHA 實測相同 `bda809fb…`，install 同源閘直接有效）。SmokeCheck 的 retriesCount 斷言隨 vanilla 刪除。完整分析：docs/report/pz-42.20.3-update-analysis.md。


<a id="2q"></a>
## 2q. 容器環防崩潰守衛（W5，server）

**事故**：2026-08-13 21:31:10 正式服主迴圈死於 `java.lang.StackOverflowError`，堆疊 1024 層全部是 `ItemContainer.getCharacter` 自我遞迴。伺服器假死 13 分鐘（frame 凍在 f:54247）、21:40 的 graceful `quit` 收不進去、看門狗 21:44 強制重啟。存檔在 21:30:10 成功、凍結在
21:31:10，故世界資料只掉約 1 分鐘，但玩家 21:31–21:44 的操作全部沒被 server 收到。

**vanilla 缺陷**：`getCharacter()` 沿「容器→裝著它的物品→該物品所在容器」爬升找擁有者，**零迴圈偵測**；`ItemContainer` 全類別無防環檢查，`AddItem` 只擋同 ID 重複、不阻止把容器放進自己的子孫。MP 封包驅動的搬移即可造出「A 裝在 B 裡、B 又裝在 A 裡」。

**環只可能是執行期產物**（故掃存檔找不到兇手）：`containingItem` 只在 `InventoryContainer`
建構時設定一次且不序列化；`InventoryContainer.save` 是巢狀遞迴寫入，若存檔內有環，存檔本身就會先爆——而崩潰前 60 秒的 `World saved` 是成功的。

**手術**（兩處，皆為堆疊形狀不變的呼叫改道，method-scope 鎖定）：

| 方法 | 改道點 | 截斷語意 |
|---|---|---|
| `getCharacter()` | 唯一自身遞迴（javap offset 42） | 回 `null`＝查不出擁有者，與 vanilla 對「放在地上的容器」同值 |
| `isInCharacterInventory(IsoGameCharacter)` | 唯一自身遞迴（offset 52） | 回 `false`，與 vanilla 走完鏈的 fall-through 同值 |

第二刀是審查指出的「下一個最會炸」：`Transaction.getDuration()` 會呼叫它，而 `getDuration()`
**只在 server 端、於 `ItemTransactionPacket` 驅動的 `Transaction` 建構時執行**——正是造出環的同一條封包路徑。helper `zombie.mdc.ContainerCycleGuard` 以 ThreadLocal 計深度（兩刀共用），超過 `MAX_DEPTH`（預設 64，實際允許鏈長 65：第一層走 vanilla 不經 helper）即切斷。

**診斷**：切斷時走鏈印出環上的 containerId／itemId／fullType 與閉合點（走鏈有 128 步硬上限，只用欄位讀取與 trivial getter，逐一驗證不會二次遞迴）。完整鏈每 60 秒至多一次、全場最多 5 次；之後改印 **10 分鐘 trips 心跳**（環不會自己消失，運維不能在第一天後就看不到）。helper 自身例外有界印出堆疊（前 3 次）。

**旋鈕**：`-Dmdc.cycleGuard.maxDepth=0` 停用兩把刀（回到 vanilla 的爆掉行為，stack 消耗約 2 倍），免重新部署。

**⚠ 已知降級（必讀）**：`GameServer` 的五個庫存廣播（`sendAddItemToContainer` / `sendAddItemsToContainer` / `sendReplaceItemInContainer` /
`sendRemoveItemFromContainer` / `sendRemoveItemsFromContainer`）對「巢狀在物品裡的容器」只有三條分支：`getCharacter() instanceof IsoPlayer` → `getParent() != null` → **vanilla 寫成空的第三條**。環上容器的 `getCharacter()` 回 null 且 `getParent()` 為 null，於是**封包完全不送、不 log**——該容器的加/刪/換物品 client 端永遠收不到（玩家體感：東西憑空消失）。這比 vanilla 的「整台死掉」好，但是一個**新的、靜默的、持久的**降級。同理
`ItemContainer.Remove` 的 `removeFromHands` 會被跳過（物品從容器移除卻留在手上）。**因此本刀是止血＋捕手，不是根治。**

**W5-2 門口偵測已於 2026-08-29 落地（observe 首發；enforce 待數據）**：`AddItem(InventoryItem)` 內唯一 `containsID(I)Z` 1→1 改道至
`ContainerAddCycleProbe.containsID(ItemContainer,int)`；vanilla 原值照回，且**只有
containsID=false（vanilla 真會進加入路徑）才旁路 probe**，避免把 duplicate 拒絕污染
wouldCycle。掛在既存 W5 `ItemContainer` ClassPatch 上（同 class 不開第二個 ClassPatch）。vanilla `TransactionManager.chainContainsContainingItem` 是 private 且只爬 2 層；helper
自行實作同語意完整 64 層向上爬升（零配置、depth cap＝既存環/異常深鏈訊號）。**AddItemBlind 刻意不掛**：headCall 在 null/容量拒絕前會造 false positive，且 Blind 只
`items.add(item)`、不設 `item.container` backlink，向上 walk 可漏報真環；42.20.4 Java
外部 caller census=0（Lua/reflection 理論可達），由 W5 使用層捕手兜底，待可信中段掛點/
下行圖判定再補。**本版純 observe、不拒絕**：caller 盤點實證至少四條 remove→add（`ItemContainer.transferItems`、`IsoMannequin`、`GameServer` replace、`EvolvedRecipe`），直接拒絕會讓已移除物品消失；enforce 必須等 `wouldCycle/depthCapped/caller` 實測後設計
rollback，不可借道 containsID=true（誤導 error＋`getItemWithID` null）。`isInside` 與
`InventoryContainer.save` 仍無防環；W5 捕手持續兜底，尚不可宣稱「環不會形成」。

**驗證**：SmokeCheck——兩刀各自的 vanilla 前提（恰一個自身遞迴）、改道恰一次且原遞迴歸零、**指令總數未變**（1:1 替換的結構事實）、原體保留（`getParent` 呼叫數與 `containingItem`
觸碰數不變）、全 class 負對照（其他 27 個呼叫端保持 vanilla）。行為測試 7 案＋kill switch 模式：**vanilla 必爆負對照**（用 vanilla 爬升邏輯走同一個環必拋 SOE，證明環是真的）、守衛切斷回 null、**正向回傳真實擁有者**（堵住「永遠回 null」的假 helper 假綠通道）、正常巢狀零觸發、診斷指出閉合點、深度歸零不污染、門檻邊界（63 不觸發／66 觸發）。測試以 `sun.reflect.ReflectionFactory` 分配未初始化物件造環（`InventoryItem` 建構子會拉起
ZomboidFileSystem），classpath 中 `dist\java` 排在 jar 前，故測到的是**改道後**的方法。

<a id="2r"></a>
## 2r. 地圖格載入捕手（W6，server）

**事故**：2026-08-14 01:34:56 正式服主迴圈 frame 永久停在 `f:46186`，直到 03:28 排程的
mod 更新重啟才結束——**凍結 114 分鐘，而且沒有任何人是為了救它而重啟的**。進程活著、Steam／Discord／網路執行緒照常，玩家連得進來但世界完全靜止（01:48「（玩家回報登不上）」、01:59「（玩家互勸登出）」，期間 170 次斷線）。同一條**逐行相同**的 stack 在
2026-08-07 18:05 也發生過一次（兇手 sprite `fencing_01_57`；本次 `blends_natural_01_53`）。

**vanilla 缺陷**：`EngineEntityManager` 維護兩份平行結構——`entitySet`（「登記過了嗎」）與
`entities`（每圈要走訪的陣列）。`addEntityInternal` offset 0-8 是 `entitySet.contains(entity)`，為真就在 offset 11-27 `athrow`：

```
java.lang.IllegalArgumentException: Entity is already registered <sprite>:zombie.iso.IsoObject@…
  EngineEntityManager.addEntityInternal(:137)   ← throw
  Engine.addEntity(:58) → GameEntityManager.RegisterEntity(:253)
  GameEntity.addToWorld(:527) → IsoObject.addToWorld(:4497)
  IsoChunk.doLoadGridsquare(:3973)
  ServerMap$ServerCell.RecalcAll2(:385) → Load2(:224) → ServerMap.preupdate(:969)
  GameServer.main(:972)
```

**為何是永久活鎖而非崩潰**：`GameServer.main` 攔住例外只印出來，但攔截點在迴圈**最上方**——這一圈剩下的工作（更新世界、處理封包、推進 frame）全數跳過，而那個地圖格**還留在待載入佇列裡**。下一圈同一個物件、同樣被拒絕，每 0.1 秒一次。log 只印 25 次就靜音（PZ 對重複例外有抑制），但 frame 從此再沒前進過。**這是活鎖，任何「進程掛掉就重啟」的保護都救不了。**

**事故走的是 `addEntity` 的直通分支，`addedToEngine` 守衛從未被求值**（本節初稿寫成「vanilla 上一層已經擋了、正式服走 return」，是 bytecode 誤讀，經兩輪審查更正）：

```
addEntity(GameEntity):
   0-21:  if (delayed.value() || bucketsUpdating.value())   →  ifeq 116
  24-47:      if (scheduledForEngineRemoval || removingFromEngine) throw
  48-71:      if (addedToEngine) { if (Core.debug) throw; return; }   ← 只在這個分支內
  72-113:     addedToEngine = true; 排進 pendingOperations
    116:  addEntityInternal(entity)    ← 另一條路，完全不看 addedToEngine
```

**證據是 log 自己給的行號，不是推論**：事故 stack 記錄 `addEntity(EngineEntityManager.java:55)`，而 `javap -l` 的 LineNumberTable 顯示 **`line 55: 116`**——正是那條直通呼叫。（`delayed` = `Engine.processing`，只在 `Engine.update()`／`simulationUpdate()`／`renderLast()`
內為 true，而 `ServerMap.preupdate → Load2 → RecalcAll2 → doLoadGridsquare` 不在其中。）

**這件事改變了根因的形狀，也改變了該往哪查**：

- 不需要任何「引擎兩個內部旗標不一致」的不可重現破壞。`addedToEngine == true` 且 `entitySet.contains == true` 是**完全自洽的狀態**，在直通分支上照樣拋。也就是說最可能的根因就是**有東西對已經在世界裡的物件又呼叫了一次 `addToWorld()`**。
- 搜尋空間從「誰弄壞了 `addedToEngine`」（十餘個 class 會碰）縮成「誰重複 add／哪個物件同時在兩個 list」——後者可查得多。
- 也解釋了**為什麼不自癒**：直通分支拋在 `addEntityInternal` 的第一個 statement，**沒有任何欄位被寫過**，下一圈狀態位元相同、原樣再拋。

初稿據以宣告「沒有重現條件、不強修」的前提因此不成立。**W6 的診斷特意加了
`isAddedToEngine()`（`GameEntity` 的 `public final` 方法）**：第一次命中就能把假說砍成兩半——
`true` ＝ 單純重複 add（照上面查）；`false` ＝ 才是真的不變量破壞，且 `entitySet.add`
與 `addedToEngine = true` 之間唯一會拋的是 `setComponentOperationHandler`，範圍縮到一個方法。在拿到那一行之前，**本刀仍是止血＋蒐證，不是治療**。

**非本專案 patch 所致**（javap 實證；不能靠時間相關性——log 只回溯到 7/29，而
`FastIdentityArrayRemoval` 也是 7/29 上線，沒有乾淨的 pre-patch 基準線）：`addEntityInternal`
的 throw 在 offset 27，我方改道的 `entities.add` 在 **offset 38**，拋出時根本執行不到；`removeEntityInternal` 由 offset 5 的 `entitySet.remove` 決定所有分支，我方改道的
`Array.removeValue` 在 offset 29 而 **offset 32 是 `pop`**，回傳值被丟棄不可能影響判斷。`entitySet` 全程未被碰過。

**手術**：`doLoadGridsquare` 內共有**三**處 `addToWorld`，全部通往同一個 throw 點。初版只擋第一處（等於守衛對三分之二觸發路徑失效）——**兩道獨立審查都由此抓到 blocking**，因為 `countExactCalls` 依 owner 過濾，「全 class 僅此一處」是過濾器造成的假象。

| offset | site owner | 迴圈 | 處置 |
|---|---|---|---|
| 457 | `BaseVehicle` | `vehicles` | **刻意留 vanilla** |
| 737 | `IsoObject` | `square.getObjects()` | 改道（兩次事故的兇手） |
| 947 | `IsoMovingObject` | `getStaticMovingObjects()` | 改道 |

`IsoMovingObject` **自己沒宣告 `addToWorld`**（SmokeCheck 有斷言），offset 947 派送到的是**同一個方法體**，包住它零額外語意風險；且該迴圈裝屍體（`IsoDeadBody`，正式服 DeadBody id
已發到 287089），是很有可能的下一個兇手。

**`BaseVehicle` 排除的真正理由是「順序」，不是「它有守衛」**（初稿的理由太弱，審查更正）：

```
BaseVehicle.addToWorld(Z):
   0-26: if (addedToWorld) { DebugType.Vehicle.error(...); return; }
  45-47: addedToWorld = true                              ← 旗標在這裡就設了
  55-56: invokespecial IsoMovingObject.addToWorld()       ← 拋出點在這之後
```

旗標賦值（offset 47）**早於** super 呼叫（offset 56），所以拋出後 `addedToWorld` 已是 true，下一圈走 offset 26 早退——**每個 vehicle 實體最多只能拋一次**，代價是掉一個 frame（約 100 ms），不是 114 分鐘活鎖。`IsoObject` 沒有任何旗標（offset 0 就是 super），所以永遠拋。這才是兩者可以不同處置的完整依據。SmokeCheck 因此把**這個順序本身**釘成結構事實：若 TIS 哪天把旗標賦值移到 super 之後（一個看起來像 bug fix 的改動），這條刻意排除會無聲變成活的凍結路徑，而其他所有斷言全綠。

**邊界由程式碼保證，不由呼叫者巧合保證**：`BaseVehicle extends IsoMovingObject`，而
`getStaticMovingObjects()` 不是型別同質的（vanilla 自己在 `getDeadBody()`／`getDeadBodys()`
都要 `instanceof IsoDeadBody` 過濾）。若有 vehicle 進到那個 list，就會經由
`addToWorld(IsoMovingObject)` 多載被吞掉——正是本節明文拒絕的那件事。helper 因此加了
`instanceof BaseVehicle` 直通，並有對應行為測試（拿掉直通後測試會失敗）。

helper `zombie.mdc.ChunkLoadGuard` 只攔 **`RuntimeException`**：`Error`（OOM／SOE／LinkageError）必須保持致命且可見，吞掉 VM 級故障遠比凍結更糟；反過來也不只攔
`IllegalArgumentException`——凍結機制與例外型別無關，同位置換一種 RuntimeException 一樣鎖死
114 分鐘。攔截型別由 SmokeCheck 從 **exception table** 上鎖定（舊版用 `containsUtf8` 找
`VirtualMachineError` 字串，但那是診斷 getter 的 `rethrowFatal` 帶進常數池的，放寬成
`Throwable` 照樣通過——mutation 實測確認新版會 FAIL、舊版不會）。

**降級範圍取決於 runtime class，不是單一方法體**（codex 審查推翻了本節前兩版的核心論證）：改道點的 site owner 只是**靜態型別**，實際執行的是虛擬派送到的覆寫版本。javap 確認至少四種形狀：

| runtime class | 形狀 | 吞掉之後少了什麼 |
|---|---|---|
| `IsoObject` | offset 0 就是 super（拋出點） | `createContainersFromSpriteProperties()`、各容器 `addItemsToProcessItems()`、`addObjectPoweredByGenerator`，以及 `GameEntity.addToWorld` 自己的 `addedToWorldOrEquipped = true` 與 `sendEntityEvent(AddedToWorld)` |
| `IsoDeadBody` | super 在 offset 1，**side effect 在後** | `CorpseCount.corpseAdded`、`FliesSound.corpseAdded`、**`ObjectIDManager.addObject`**——屍體不進 ID 登記表。**而這正是 offset 947 那個 `getStaticMovingObjects()` 迴圈的主要內容** |
| `IsoWorldInventoryObject` | **side effect 在 super 之前** | `getProcessWorldItems().add()` 已經執行——「拋出時什麼都還沒跑」對這型**是假的**，守衛吞掉的是一個**部分完成**的狀態 |
| `IsoGenerator` | **完全不呼叫 super** | 走不到拋出點，不受影響 |

也就是說「拋出前尚無 side effect」只對 super-first 的形狀成立。**這是有意識接受的
production 風險**：凍結 114 分鐘的代價遠大於單一物件的部分狀態，而診斷的 `class=` 欄位可讓事後辨識當次是哪一型——但**不能再宣稱降級一律極小**。

**跳過為什麼是安全的，承重的是 identity 而不是「先前做過了」**（審查給出比初稿更強的論證）：`IsoObject` **沒有覆寫 `equals`／`hashCode`**（javap 確認），所以 `entitySet`（`ObjectSet`）是 identity 語意。若真的發生過 unload → 從磁碟 reload，那會是一個**全新反序列化的實例**，identity 不同、`entitySet.contains` 必為 false、**根本不會拋**。

於是「它拋了」本身就蘊含「同一個實例從來沒被 unregister 過」，也就蘊含「沒有發生真正的
unload」，於是先前那次 add 掛上的 ProcessItems 與 generator 註冊**都還活著**——跳過確實無損。

**但這條鏈依賴 `entitySet` 的內容與物件生命週期沒有脫鉤。** 若診斷回報
`addedToEngine=false`（＝真的不變量破壞），這條鏈就斷了，跳過會留下：沒有 container 的容器家具（開了是空的）、沒進 ProcessItems 的冷藏／腐敗物品（食物永不腐壞或永不冷藏）、沒掛上發電機的用電物件（發電機在跑但這台沒電）——三者全部靜默、全部持續到重啟、全部無 log。**這正是把 `isAddedToEngine()` 列為必要診斷欄位的理由：沒有它，我們無法知道自己身處哪個世界。**
log 行本身也直接寫明「該物件的容器處理與供電掛載本次載入未執行」，讓玩家回報進來時對得上。

**診斷**：這是本刀最主要的產出——現況出事只拿得到 25 份一模一樣的 stack 然後靜音，連是哪一格都不知道。捕手留下**方格座標＋sprite 名＋class＋`addedToEngine`＋identity＋chunk jobType＋執行緒名＋例外**。三個決定性欄位的用途：

| 欄位 | 它能分開什麼 |
|---|---|
| `addedToEngine`（`GameEntity` 的 `public final` 方法） | `true`＝引擎狀態自洽、單純重複 add（查「誰重複呼叫」）；`false`＝真的不變量破壞（範圍縮到 `setComponentOperationHandler` 一個方法）。**兩者後續調查方向完全不同。** |
| `identityHashCode` | 同一個兇手一直拋（單一物件）vs 一堆不同物件（系統性） |
| `IsoChunk.jobType`（`public` 欄位） | 驗「SoftReset job 對活著的物件重跑 `doLoadGridsquare`」這個假說——`doLoadGridsquare` 自己就有 SoftReset 專屬分支（offset 835-842），這是目前最具體、最可驗的根因方向 |

去重鍵**只有座標**，明細才是全欄位——診斷含 identityHashCode，若拿完整明細當去重鍵，同一格的每個不同實例都會算成新方格，去重就失效了（實際踩到並修正）。

以下每一條都是審查抓出來的，不是原始設計：

- **明細額度按「相異方格」計，不按事件計**。損壞的方格每次玩家經過都會再觸發，按事件計的話一格幾小時就能吃光 20 格額度；之後 B、C、D 格陸續損壞卻只在 `lastSite` 互相覆蓋、座標永久遺失——而「是否集中於特定建築」正是本刀唯一想回答的問題，需要的是 20 格的證據而非一格 ×20。
- **心跳用 primed 而非 `lastHeartbeatNs = 0` 哨兵**。`System.nanoTime()` 原點任意且規格明文允許為負，負原點時 `now - 0 >= 10 分鐘` 恆為假＝**心跳一輩子不印**。W5 的 `reportPrimed`
  早已是正解，只是同檔的心跳漏套——**兩邊一併修正**。心跳同時印本區間增量（累計值看不出惡化速率）。
- **執行緒名**：「這次是 WorldStreamer 背景執行緒（本來就不凍主迴圈）還是主迴圈（本來凍 114
  分鐘）」是本刀最有運維價值的一個 bit。
- **哨兵值逐種可分辨**：`方格=none`／`方格=getter-threw`／`方格=partial(getter-threw)`、`sprite=null-sprite`／`unnamed`／`getter-threw`，並另計 `診斷取值失敗` 次數。否則凌晨三點看到 200 行 `方格=null sprite=?` 的人無法分辨「物件本來就怪」與「每次取值都在爆、什麼都沒蒐到」。座標改為三軸全成功才印——逐軸退化會讓 `方格=7130,-2147483648,0` 看起來像有個真 X。
- **null receiver 用不同語氣**（`世界資料異常：方格物件清單含 null 項`）：那是與本案無關、可能更嚴重的另一種損壞，不能被當成同一個 bug 的第 N 次。
- **啟動橫幅**印出 `enabled` 與 property 原值。沒有它，下次若從未守衛的 vehicles 路徑凍結，運維只看得到「已安裝但一行都沒印」，無法分辨「沒蓋到這條路徑」與「守衛壞了」；也順帶抓
  `-D...=0`／`=no` 這類會靜默保持啟用的打錯。
- **`DebugLog` 全壞時退到 `System.err`**：那是本設計唯一的完全靜默失敗（額度被無聲扣光、心跳全滅），而 stderr 是獨立通道且 LinuxGSM console log 抓得到——把「完全失明」降級成「看得見但簡陋」。
- **anomaly 處理自帶最後一道網**：若拋出的正是 `DebugLog.log` 本身，用「再 log 一次」回應會讓例外逃出守衛回到 `doLoadGridsquare`——把捕手變成新的凍結源（**W5 同缺陷一併修正**）。
- **`rethrowFatal` 只重拋 `VirtualMachineError`**。它只用在診斷路徑，而診斷路徑撞到的
  `LinkageError`（例如 `getSimpleName()` 讀不到 InnerClasses——對改寫 bytecode 的專案正是最該防的一類）100% 是診斷子系統的缺陷，不是「世界不可續跑」的訊號；在已決定放棄診斷的那一行把它升級成凍結，正好是這道網存在的理由的反面（**W5 同缺陷一併修正**）。
- 主路徑刻意**不**呼叫 `rethrowFatal`——它用 `catch (RuntimeException)`，所有 `Error` 自然穿透。兩條路徑對 `AssertionError` 的處置因此不同，這是有意的：「遊戲自己的操作失敗」與「我們的
  log 失敗」契約本來就不同。

**共享狀態一律在鎖內**（codex 審查抓到的 blocking，前一版是錯的）：前一版註解宣稱「共享欄位只有 primitive 或 String，最壞只是少報」——`distinctSites` 是 `LinkedHashSet`，**非
thread-safe**。並行 `add`／`size` 沒有任何定義保證，HashMap 家族在 resize 期間被併發改動可能讓內部鏈結成環而**空轉**——那正是本刀要防的凍結形態，等於守衛自己變成新的凍結源。延遲的 `caught++` 寫入也能覆蓋較新值，讓計數倒退、心跳的「本區間 +N」變負，不只是「下限」。

成本論證也站不住：整段只在例外**已經拋出之後**才執行，一次 `fillInStackTrace` 就是數微秒級，鎖的奈秒級成本在這條路徑上不可觀測。現行做法：診斷取值在鎖**外**（那是遊戲物件的 getter，持鎖呼叫等於把不可控的第三方程式碼拉進 critical section），共享狀態全部在鎖內，log 輸出用快照在鎖外做。

**橫幅不是「開機證明」**（codex 更正）：helper 是被 patch 的 `IsoChunk` 在**第一次執行到受守衛的 callsite** 時才觸發載入的，單純載入 patched `IsoChunk` 不會初始化它；而且 vehicles
迴圈排在兩個 redirect 之前。看不到橫幅只代表還沒有方格走過那兩個 callsite。訊息文字已改為「首次生效」。另外 `<clinit>` 的 catch 原本連 OOM／SOE 都吞，與本檔「Error 必須致命且可見」的契約矛盾，已補 `rethrowFatal`。

**`objectChunkJob` 是 best-effort**（codex 更正命名）：讀的是**該物件自己的 chunk**，不是正在執行載入的那個 `IsoChunk`。若物件掛在錯誤的 square／list 上（本案的可能形態之一），讀到的會是另一個 chunk 的 job。`identityHashCode` 可能碰撞且不可跨重啟視為唯一 ID；`isAddedToEngine()` 是同執行緒下的有效快照，不是與 `entitySet` 線性一致的跨執行緒視圖。

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
1. `BaseVehicle` 那處（offset 457）仍是活的凍結路徑——但依上面的順序論證，它**每個實體最多拋一次**（掉一個 frame，非活鎖）。附帶損害：那一次拋出後 vehicle 停在 `addedToWorld=true`
   而 `createPhysics()`／`parts.addToWorld()` 全沒跑，**且因旗標已設所以永遠不會重試**——一台永久沒有物理與零件的車。比守衛跳過一個 object 更糟，但有界。
2. **新的穩態成本**：修好之後，壞掉的方格從「拋一次然後凍結」變成「每次載入都拋，永遠」。`fillInStackTrace` 在該深度約 1-5 µs，乘上 chunk 載入速率是一筆之前不存在的 CPU 稅。淨值仍遠優於凍結，但心跳的「本區間 +N」就是為了讓運維估得出它的量級。
3. 根因未定位。**不可據此認定此類假死已排除**——但下次命中時 `addedToEngine` 那一欄會直接指出往哪查（見上）。
4. 本刀只擋 `doLoadGridsquare`。主迴圈若因其他未知原因卡死（如 W5 之前的 SOE）仍會靜默凍到下次排程重啟——`Load2` 守衛（W7）與 frame 停滯 watchdog 都是互補而非重複的投資（8/14 那 114 分鐘完全是因為沒有東西在看）。

**驗證**：SmokeCheck——vanilla 前提（三個 owner 各 1 處、`IsoMovingObject` 未自行宣告
`addToWorld`）、兩處改道各一次且原呼叫歸零、**指令總數未變**、`BaseVehicle` 範圍宣告釘死、**`BaseVehicle` 排除前提兩段式**——先釘 `addToWorld()V` 恰委派到 `(Z)V`（未守衛的 callsite 是
`()V`，但旗標邏輯在 `(Z)V`，不先釘住委派就等於驗了一個無關的方法），再釘 `(Z)V` 內
`addedToWorld=true` 唯一、`super` 唯一、賦值在 super 之前，且**存的是 `ICONST_1`**（存 `false` 一樣通過順序檢查卻讓早退永不觸發）。唯一性是 CFG dominance 的窮人版——完整支配分析過重，刻意停在此強度，殘留是「理論上仍可能有繞過旗標的分支」、**位置錨**（改道點之後最近的呼叫必須是 `getSprite()`，釘住是 tile 迴圈而非屍體迴圈——否則計數相同但改到別的 callsite 會全綠）、原體保留（`getSprite`／`getPipedFuelAmount` 數不變**且非零**）、負對照改用**相對 vanilla 的差值**（絕對零會在 PZ 於他處新增同名呼叫時誤報）、主 catch 型別鎖定。行為測試 16 案＋kill switch 模式：替身必拋負對照、守衛吞下、**正向真的入世界**（堵「空 helper」假綠通道）、屍體迴圈多載、**vehicle 不得被吞**（拿掉 `instanceof BaseVehicle` 直通即失敗，讓宣告的範圍邊界變成可執行而非註解）、座標定位（含 `addedToEngine`／identity／jobType 三欄）、**真實 `DebugLogStream` 落地**（堵「刪光 log 仍全綠」——原本所有鑑識斷言只讀 package-private 測試欄位）、logger 丟 `RuntimeException` 不外逃、**logger 丟 `LinkageError` 不外逃**（Probe 原本結構上無法注入 `Error`，那行 `rethrowFatal`
對測試而言是死碼）、**心跳真的印出來**（原本只斷言計數器，哨兵壞掉照樣全綠）、**額度按相異方格**（同一格 50 次只花一格額度、換格仍拿得到）、anomaly 路徑、`Error` 不吞、半初始化 getter（哨兵逐種釘死＋`診斷取值失敗` 計數）、null receiver（兩模式行為不同，各自釘住）、明細額度釘死 `MAX_REPORTS`。kill switch 執行傳 `disabled` 參數讓測試自行斷言旋鈕生效（只看 exit code 的話，property 名稱打錯會變成「把 enabled 版再跑一遍、照樣 exit 0」，降級路徑其實從未被測到）。

**Mutation 實測**（證明新閘不是裝飾，兩者在修正前都會全綠通過）：`catch (RuntimeException)` → `catch (Throwable)` ⇒ `struct FAIL 主 catch 型別鎖定`；刪掉 production log 行 ⇒ `應恰好輸出一行，實得 0`。

<a id="2s"></a>
## 2s. 朝向暫存執行緒隔離（W7，server）

**事故**：2026-08-13 19:55:03，玩家 Player-A 的雞舍連同旁邊的水桶整組消失。chunk <chunk-A>（方格 <squares of chunk-A>）在重啟後 4 秒載入失敗，被原版的 `Blam + LoadBrandNew`
清空重生：**46,142 bytes → 8,549 bytes**，雞舍、32 隻家禽的完整基因組、`Base.Bucket`
全滅，只剩草地。完整鑑識報告在 `temp/report/incident-2026-08-13-hutch-chunk-blam.md`（`temp/` 已 gitignore，**僅存在於調查者本機**，不隨 repo 散佈；本節已收錄其全部技術結論）。

```
Error loading chunk <chunk-A>
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

先呼叫 `getVectorFromDirection(tempVector2_2)`（① 寫入共用 static），再呼叫
`setForwardDirection(tempVector2_2)`（② 讀回來 normalize()）。

而 `IsoMovingObject.getVectorFromDirection(Vector2, IsoDirections)` 的第一件事是**把 x、y 都歸零**再依方向填回真值。主執行緒（每 tick 為殭屍／動物／玩家呼叫）與
`ServerChunkLoader$LoaderThread` 同時走這段、零同步：一方在歸零空窗期、另一方讀取，就拿到 (0,0)，`normalize()` 長度 0 → 拋例外。

**存檔本身沒有壞**：`IsoDirections.fromIndex(int)` 是 `VALUES[index & 7]`，8 個方向全都有非零向量，存進檔案的方向值不可能產生零向量。失敗純屬競態擲骰——**這是判定
`blam/` 備份可直接還原的關鍵前提**。

**非本專案所致**（三重實證，完整推導見事故報告第五節）：(a) 正式服 jar sha256 `09a80a46…` 與反編譯快照來源逐位元組相同，jar 未被改動；(b) 崩潰路徑上的 `IsoGameCharacter`／`IsoMovingObject`／`IsoChunk`／`IsoGridSquare`／`IsoHutch` 全部不在 loose class 覆寫清單內，直接由 jar 載入；(c) 堆疊上唯一被我方 patch 的 `IsoAnimal`，其 `load()` 經常數池正規化後與原版 411 條指令逐條相同（本 class 四刀全在 `updateStress`／`respondToSound`／`killed`／`updateLOS`）。

**手術**：`FieldGetSwap`（本次擴充為可吃 `GETSTATIC`，原僅支援 `GETFIELD`）在方法內兩處 `getstatic tempVector2_2` 之後各插一個 `INVOKESTATIC ForwardVectorGuard.swap`
——吃掉共享實例、回傳執行緒私有替身。vanilla 方法體只有 8 條指令、無分支無 frame：

```
 0: aload_0 / 1: getstatic tempVector2_2 / 4: invokevirtual getVectorFromDirection
 7: pop / 8: aload_0 / 9: getstatic tempVector2_2
12: invokevirtual setForwardDirection / 15: return
```

`getstatic` 與插入的 `invokestatic` 皆 3 bytes、堆疊 1→1，形狀最單純的一類手術。同一執行緒內 ① 寫和 ② 讀拿到同一個實例，語意與原版逐字相同；跨執行緒互不可見。

**不複製共享實例的內容**（刻意）：站點 ① 之後緊接的 `getVectorFromDirection` 無條件覆寫 x、y，內容不具意義；站點 ② 要讀的正是站點 ① 寫進私有實例的值。複製共享內容反而會把別的執行緒的髒值帶進來。

**移除副作用的耦合核對**：本刀讓該方法不再於共享實例留下值。原版 `tempVector2_2`
類別內共 12 個 `getstatic`（另有 1 個 `putstatic` 在 `static {}` 初始化欄位），本方法佔 2 個，其餘 10 個**逐一核對全部先寫後讀**，無人依賴該遺留值：

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
實例，呼叫端接著 `temp.x += rand; temp.y += rand; temp.normalize()`），本刀不涵蓋。`IsoDirections` 是全遊戲高流量核心 enum，爆炸半徑與本刀不同級，待本刀上線觀察後另案評估。

**`ThreadLocal` 有界性**：vanilla 只有一個 static-final `ServerChunkLoader`，其 constructor
只建立**一條**固定 `LoaderThread`（`ServerChunkLoader:34`、`ServerMap:823`），不是逐 chunk
起執行緒——故每條長生命週期執行緒各持有一個 8 bytes 的 `Vector2`，不隨 job 累積。helper 的 `<clinit>` 也只建立 supplier 與 `ThreadLocal` 本身，`Vector2::new` 要到各執行緒首次 `get()` 才執行。

**驗證閘**（SmokeCheck 11 項，含 3 項真開執行緒的行為 smoke）：

- vanilla 前提：方法體全序＝8 條指令；`getstatic tempVector2_2` 恰 2 次；**兩個 `invokevirtual` 的 owner/name/desc 各鎖 1 次**
- 手術後：全序＝兩組 `getstatic → swap → invokevirtual`；swap 改道 x2 且 getstatic 保留 x2；**兩個 `invokevirtual` 目標未被動到**
- 負對照：`IsoGameCharacter` 其餘方法零 swap 改道
- 耦合鎖：類別內 `getstatic` 總數＝12 且手術前後一致
- helper 行為：回傳非 null 且不是傳入實例／同執行緒兩次回同一實例／**跨執行緒回不同實例**

其中兩項 `invokevirtual` 目標鎖是 codex 審查抓出的 fail-closed 缺口：`matchOpcodeSeq`
只比 opcode 是 operand-blind 的，PZ 若保留相同 opcode 形狀與兩個 `tempVector2_2` 讀取、只把 call target 換掉，原本的全序閘仍會全綠，違反「任何方法改寫都讓建置失敗」的契約。同輪另修 `Patcher.FieldGetSwap` 的 compact constructor，把 opcode 封死為 GETFIELD／GETSTATIC
——誤傳 PUT 會對「已被消費的值」插入 helper，而那類 bytecode 不保證 verifier 會攔住。

**全類別爆炸半徑實證**：正規化常數池後比對原版與修補版的完整 javap，**零指令被刪除、恰好新增兩條 `invokestatic ForwardVectorGuard.swap`**，其餘差異全部是 `ldc`↔`ldc_w` 編碼互換與隨之位移的 offset。

**無重入**（`ThreadLocal` 私有實例在兩站點之間不會被同執行緒覆寫的依據）：兩個站點之間只有 `getVectorFromDirection(Vector2, IsoDirections)`，它是 static 純 switch 無回呼；其內部呼叫的 `getForwardIsoDirection()` 全樹**僅一處宣告**（`IsoObject:1920`，零覆寫），`setForwardIsoDirection(IsoDirections)` 也只有 `IsoObject` 與 `IsoGameCharacter` 兩處（全樹 grep 實證，非臆測）。另外 `setForwardDirection(Vector2)` 只把 `dir.x`／`dir.y`
複製進 `this.forwardDirection`，**不保留參照**，故私有實例不會被別名進角色狀態。

**降級分析（helper 若載入失敗會怎樣）**：這是本刀最危險的假想面——patch 自己變成毀存檔的來源。結論是**不會**：`NoClassDefFoundError`／`LinkageError` 屬 `Error`，而
`IsoChunk.LoadOrCreate` 的失敗分支是 `catch (Exception var7)`，**攔不到 Error**。因此 helper 缺席時例外會穿透 `LoadOrCreate` → `LoadChunk`，`Blam()`／`LoadBrandNew()`／`BackupBlam()` 一個都不會執行，直接打死 `ServerChunkLoader$LoaderThread`——**吵鬧的停止載入，而非安靜的大規模抹除**。且此情境已被三道閘擋在上線前：build 的
manifest 完整性守門（dist\java 任何 class 未登記即中止）、install.sh 的 fail-closed
payload preflight、以及開機健檢（驗證清單 11a）。

**效能**：`setForwardIsoDirection`／`setForwardDirectionFromIsoDirection` 全樹 48 個呼叫點，且**沒有角色的 per-tick 無條件呼叫路徑**（render loop 那幾處是 `IsoMannequin`，走 `IsoObject` 版不經本方法）——本方法只在轉向、spawn 與載入時被呼叫。`ThreadLocal.get()` 相對原版 `getstatic` 約多 1–2ns，在此頻率下不可量測。

**沒有計數觀測**（刻意）：本 helper 是唯一會被多執行緒同時呼叫的 helper，靜態計數器本身就是競態；且驗證訊號現成且更強——見部署後驗證清單第 11 項。

**沒有 kill switch**（刻意，偏離 W4-1／W5／W6 慣例，故在此說明理由）：那三刀都會**改變行為**（併包改變送出批次、守衛吞掉例外、捕手跳過入世界），旋鈕的價值在於「懷疑是它造成的就先關掉，不必整包 uninstall」。W7 不同——它是**語意保持**的：全類別 javap 多重集合比對證明零刪除、僅新增 2 條指令，且同執行緒行為與原版逐字相同。更關鍵的是，本刀的「關掉」等同於**把共享 static 換回去，也就是把毀存檔的競態放回來**；提供這種旋鈕是提供一把傷害自己的刀。真要退場就是 `uninstall.sh`（整包），那才是正確的粒度。唯一無法被旋鈕挽救的情境（helper 載入失敗）也不需要旋鈕——見上方降級分析，那是吵鬧的執行緒死亡而非資料損失。

<a id="2t"></a>
## 2t. chunk 寫入閘（W8，server）

**事故家族**：正式服累計 **43 個 chunk** 因 `SANITY CHECK FAIL`（CRC／長度不符）在載入時被 vanilla 的 `Blam + LoadBrandNew` 抹除重生，累計損失 ~143KB 玩家建造資料，且持續發生（8/14 單日 8 筆）。實案：玩家 Player-B 的基地箱子（chunk <chunk-B>，28,401→5,470 bytes，8/14 03:38）。與 2s 的 Player-A 案**進同一條毀滅路徑，但成因不同**——W7 治不了這族。

**鑑識定案（三個關鍵事實）**：

1. **載入側完全無辜**：43/43 筆 log 的 `load=` 等於對磁碟檔自算的 body CRC、`save=`
   等於檔案 header 欄位——遊戲讀到的就是檔案裡的東西，SanityCheck 是正確地偵測到「檔案真的壞了」。損毀發生在**寫入磁碟的那一刻**。
2. **兩種簽名**：A 組 16 筆 header CRC=0＋len 正確＋body 完整自洽（被捕捉在 `Save()`
   尾端「回填 len」與「回填 crc」相鄰兩行之間的狀態）；B 組 27 筆 header CRC 屬於別份 body（寫檔與重填撕裂）。A 組資料 100% 可救（改寫 header 8 bytes 後還原）。**42.21 起佔位值改變**：`Save(ByteBuffer,CRC32,Z)` 的 header CRC 佔位由 `putLong(0L)` 改為
   `putLong(-1L)`（len 佔位仍是 0，之後才回填），所以 42.21 的「回填前被撕裂」會是 CRC=-1（`0xFFFFFFFFFFFFFFFF`）而非 0。判讀 BLOCKED log 與 `blamguard/` 傾印時，CRC=-1 也屬 A 組，不要當成 B 組的垃圾值。
3. **正常 chunk 的 CRC 都是好的**（抽樣 25/25 相符）——這不是系統性「不寫 CRC」，是個案級的寫入競態。

**根因狀態：機制未定罪**（誠實記錄，防止未來重查）：
- ~~載入側共用 static（`sliceBufferLoad`/`crcLoad`）競態~~ → 43/43 逐位元組對帳證偽；
- ~~`ChunkSaveWorker` 池化 buffer 與 `AddHotSave` 重填競賽~~ → 簽名完美吻合，但 hot-save
  入列點被 `!GameServer.server` 閘死（`IsoChunkMap.updateInternal`），伺服器不走；
- 現行首嫌：`ClientChunkRequest.Chunk` **跨玩家共用 static 物件池**的 pending-write 與重填競賽（`ServerChunkLoader$SaveLoadedTask.save` 寫 `chunk.bb` 時，同一實例可能被重新入列填另一份資料）——未逐行證實。
- **W4-1 交互**：W4-1（8/13 17:09）上線後發生率 0.30→0.80 筆/重啟（2.7 倍；樣本僅
  10 次重啟且 8/14 為異常日）。W4-1 是否為放大器未定案；本閘的 BLOCKED stack 會直接指認寫入路徑，比關刀對照更快得到答案（使用者決策：W4-1 照跑）。

**閘門設計（不依賴根因）**：所有 chunk 寫檔收斂到 `IsoChunk.SafeWrite`——在唯一的橋上驗證，不論上游誰弄髒的。**快照 → 驗證 → 放行/擋下**：

1. 活 buffer 複製進執行緒私有陣列（關閉驗證與寫入間的 TOCTOU——驗過的位元組就是寫入的位元組）；
2. 驗 header len == 實際長度、header CRC == body 自算 CRC（`Save()` 正常收尾時兩者必然成立，不符＝100% 上游損毀，**零合法誤判空間**）；
3. 通過 → 把驗證過的快照交給 vanilla `SafeWrite`（鎖／sanityCheck／目錄建立全走原版）；
4. 失敗 → **跳過寫入**（磁碟保留上一版好檔案）＋前 10 筆帶完整 stack 的 BLOCKED log（兇手路徑蒐證）＋損毀 buffer 傾印 `blamguard/`（上限 16 份，檔名帶序號防同毫秒覆蓋）＋`ChunkChecksum.setChecksum(wx,wy,0)` 使下輪存檔的 CRC 比對必然不符而重寫。

**重試語意的誠實界定**（codex 審查修正——「保證自癒」是過度宣稱）：
- **仍載入的 chunk**（SaveLoadedTask 路徑、定期存檔）：live IsoChunk 還在世界裡，下輪 `SaveWorldEveryMinutes` 週期重新序列化＋checksum 已歸零 → 必然重寫。真自癒。
- **unload／quit 的最終存檔被擋**：`SaveUnloadedTask.release()` 隨後把 IsoChunk 交給
  reuser、記憶體內容不可恢復——**該 chunk 回退到上次成功落盤的版本，沒有重試**。損失上限＝上次成功存檔以來的變更（正常節奏 ≤ SaveWorldEveryMinutes=30 分）。對照組是 vanilla 把損毀 buffer 寫進磁碟 → 下次載入 Blam → **自上古以來的一切全滅**。回退 30 分鐘 vs 全滅，仍嚴格更好，但要知道它不是零損失。
- **刻意不做 A 簽名 header 修復**：物件池重用下 body 可能屬於別塊 chunk，補上正確
  CRC 等於把跨 chunk 汙染合法化成能通過驗證的檔案。拒寫是唯一保守正確的選擇。

**掛點（安全關鍵）**：redirect 三個呼叫端而非 hook `SafeWrite` 內部——它的
`new FileOutputStream(outFile)` 建構當下就 truncate 舊檔，內部攔截來不及保住上一版。全 jar 恰 **5 個** SafeWrite 呼叫點（SmokeCheck census 釘死，新增即建置失敗）：

| 呼叫點 | 處置 | 理由 |
|---|---|---|
| `IsoChunk.Save(Z)` ×2 | **改道** | 伺服器世界存檔主路徑 |
| `ServerChunkLoader$SaveLoadedTask.save` ×1 | **改道** | chunk 出貨存檔路徑（首嫌所在） |
| `ChunkSaveWorker.WriteQueuedSave` ×1 | 不改 | 唯一入列點 `AddHotSave` 被 `!GameServer.server` 閘死（SmokeCheck pin） |
| `WorldGenerate` ×1 | 不改 | 只寫首次生成 chunk（method-local buffer），寫壞也沒有玩家資料可失 |

**取捨（誠實記錄）**：
- 跳過寫入 = 該 chunk 磁碟版本暫停在上一版，直到下次內容變更觸發重寫。「舊而有效」勝過「被 Blam 全滅」，且 checksum 歸零保證必然重試。
- 閘門攔不住「buffer 被**完整地**填成另一塊 chunk 的自洽資料」（header 無座標欄位）——已觀測 43 筆全是不自洽型，全數會被攔下；該殘餘情境窄得多。
- verify 對 len≤17 回 MALFORMED：空 body 的 CRC=0 會與 crc 欄位 0 假相符，必須先擋（這同時攔下「truncate 後零位元組寫入」的檔案抹除情境）。

**失敗紀律**（codex 審查後精確分層）：
- **不可寫 buffer（null／非 heap array）＝各模式一律拒寫**：vanilla 對它的行為是「FileOutputStream 建構先 truncate 舊檔、之後才炸」＝把好檔案換成空檔。拒寫是唯一不毀檔的選項，observe 的「零行為改變」在毀檔面前讓位。
- **守衛內部故障（buffer 已確認合法後的 RuntimeException）＝fail-open 回退 vanilla**
  ——守衛的 bug 不得癱瘓全部存檔。
- **log／傾印基礎設施的 RuntimeException 與 LinkageError 一律吞下**（W6 教訓），不得外逃進存檔路徑。

`MODE` 三態：`-Dmdc.chunkWriteGuard=0` 停用（零開銷 passthrough）／`1` enforce（預設）／`2` observe——照常驗證＋log＋傾印但一律寫入活 buffer。**observe 的兩個誠實限定**：log 印 `FLAGGED` 而非 `BLOCKED`（沒有擋任何東西）；`blamguard/` 傾印是驗證當下的快照，實際落盤的活 buffer 之後仍可能被改動，兩者不保證相同。成本：CRC32 硬體加速 ≤64KB ~30µs，最壞 200 塊/s 佔單核 <1%。

**驗證閘**（SmokeCheck 19 項，codex 審查後補強 5 項堵 false-green）：verify 四情境行為 smoke（自洽→OK、**A 組實案簽名→CRC_MISMATCH**、len 竄改→LEN_MISMATCH、截斷→MALFORMED）＋resolveMode 四值＋**safeWrite 本體執行級 smoke 三條決策路徑**（損毀 buffer 靜默擋下＋checksum 歸零實測、null buffer 拒寫、自洽 buffer 真的委派
vanilla——測試環境必拋＝到達寫入路徑的證明）；vanilla 前提（兩方法 SafeWrite/
setChecksum 計數＋**setChecksum 先於 SafeWrite 的順序鎖**、census 總數 5＋**逐類分佈**堵新舊呼叫點互抵、hot-save 閘 **getstatic→ifne 方向鎖**、格式 offset **語境鎖**（17→CRC32.update、5→ByteBuffer.position，非僅常數存在））；手術後改道到位＋原呼叫歸零；負對照（排除條件鎖到精確簽名 `Save(Z)V`，其他 Save 多載也受檢；SafeWrite 本體無遞迴）。

**歷史損失的還原路線**（另案執行）：A 組 16 筆改寫 header CRC 後即可還原（Player-B 案優先）；B 組 27 筆 body 可能為撕裂混合體，需逐筆分析不可批次。還原一律在閘門上線後進行——否則還原完可能再被同一缺陷吃掉。（2026-08-14 18:11 已執行：A 組 16/16 全數還原成功，含 Player-B 基地 <chunk-B>。）

<a id="2u"></a>
## 2u. 存檔管線隔離（W9，server）

**根治刀**。W8 閘上線首晚攔下 8 筆損毀寫入（零資料損失），現行犯證據把根因從「嫌疑」推進到「定罪」：

- **8/8 呼叫堆疊一致**：`SaveChunkThread → SaveLoadedTask.save`（`IsoChunk.Save(Z)`
  直接路徑零事件）；
- **8/8 簽名一致**：len 欄位正確、CRC 欄位＝0（3 筆）或垃圾值（5 筆）——與歷史
  43 筆（A 組 16＋B 組 27）同款兩簽名；
- **機制**（bytecode 實證）：`Save(ByteBuffer,CRC32,Z)` 的 header 指紋用**呼叫者傳入的
  CRC32** 計算；序列化入口 `SaveChunkThread.addLoadedJob` 傳入的是**單一共用實例**
  `SaveChunkThread.crc32`。兩執行緒同時序列化：對方 `reset()` 插在我 `update` 與
  `getValue` 之間 → 指紋 **0**（A 組）；`update` 交錯 → **垃圾**（B 組）。body/len
  由各自執行緒完整寫入 → **len 恆正確**——與 8/8 觀測相容的唯一機制（buffer 竊用假說解釋不了 len 恆正確，且 unpack 雙重歸還路徑經全 jar 普查證實為死碼）。
- **並行序列化實證**：`QueuedSaveAll` 走 `GameServer$1`（shutdown hook 執行緒），與主迴圈的 `ServerCell.update → saveChunk` 同時進 `addLoadedJob`——歷史 blam 集中在重啟窗口（0.8 筆/重啟）由此解釋。運行中爆發（21:50、22:15 兩波與 `growing
  ByteBuffer` 擴容事件同叢集）的第二執行緒未逐一指認；本刀不依賴指認——任何並行呼叫者都被 ThreadLocal 隔離。
- **第二處同款競態**：`SaveLoadedTask.save()` 的去重比對四連讀外層
  `ServerChunkLoader.crcSave` 共用實例，而 save() 可在 SaveChunkThread 與
  LoaderThread（經 `saveNow`——載入前沖存檔，`LoaderThread.run` 偏移 214 實證存活，非死碼）並行——污染 ChunkChecksum（去重誤判＝陳舊跳寫；客戶端校驗錯亂＝重送，疑與黑邊案「crc 恆 0」同根）。

**三刀**（helper：`zombie/mdc/ChunkSaveIsolation`，全部只動存檔管線）：

1. `addLoadedJob` 的 GETFIELD `crc32` → `headerCrc`（ThreadLocal）——指紋競態根絕；
2. `SaveLoadedTask.save()` 的 GETFIELD `crcSave` ×4 → `dedupCrc`（ThreadLocal）——去重競態根絕；
3. `getChunk`／`getByteBuffer`／`releaseChunk`（addLoadedJob 租用＋例外歸還、release() 歸還）→ 私有化——存檔管線徹底退出 `ClientChunkRequest` 的全域 static
   共用池（`freeChunks` private static／`freeBuffers` **public** static，與 N 條
   PlayerDownloadServer WorkerThread、RequestZipListPacket.parse 共用）；同時關閉
   W8 的理論盲區（池雙發同一 buffer 時「完整重填成別塊 chunk 的自洽資料」可通過
   CRC 驗證——私有化後此路徑物理上不存在）。

**私有化語意（codex 對抗審查後收緊為 exactly-once）**：Chunk 殼**不入池**——每次
new（vanilla update() 用無同步的 savedChunks ArrayList 歸還，主迴圈與 shutdown hook
並行 updateSaved 時同一 task 可被 release 兩次；入池殼會被二次出租＝在私有池內復刻本刀要根絕的競態。順帶：這也揭露 vanilla 全域池本就有同款雙重歸還孔，可能是運行中爆發的第二機制）。buffer 歸還走 `synchronized(c)` 原子摘取（雙重 release
第二次拿到 null＝no-op）；私有 buffer 池有界（≤256 顆軟上限＋單顆 ≤256KB，超限丟 GC——vanilla 全域池無界，`sendLargeArea` 的 clear() 經普查為死碼從不執行）。

**驗證閉環**：W8 的 `flagged` 計數器是現成 A/B 儀表——本刀生效後 flagged 應歸零；不歸零＝機制另有分支，用 BLOCKED stack 續查。W8 不拆，永久保險絲。

**Kill switch**：`-Dmdc.chunkSaveIsolation=0` 完全停用（helper 原樣委派回共用實例／共用池——off 路徑的 bytecode 就是 vanilla 呼叫，SmokeCheck 釘保真）。

**驗證閘**（SmokeCheck 15 項＋獨立 JVM off 測試；codex 對抗審查後補強 4 項）：行為 6（headerCrc 跨緒相異／dedupCrc 分族／機制錨——共用 CRC32 遭外部 reset→0、疊 update→垃圾的最小重演／私有池 fresh-shell＋buffer 重用／隔離定義——全域池計數不變／**雙重歸還冪等**——release 兩次只入池一次）＋結構 9（vanilla 前提三方法形狀、**耦合鎖全 jar 版**——兩顆 CRC32 的讀者全 jar 普查總數＝已釘位置數（硬編類別清單掃不到新增 nestmate，codex 修正）、**序列化者清冊**——全 jar SaveLoadedChunk 恰 2＋逐類分佈、手術後 **swap 緊鄰性**（GETFIELD 之後必須緊接 helper）＋改道歸零、SaveChunkThread 負對照、helper off 路徑 bytecode 保真）；build 步驟 9d 以
`-Dmdc.chunkSaveIsolation=0` 獨立 JVM **真的執行** off 分支（CRC identity、全域池同一性 marker 驗證、私有池零使用——off 分支不該首跑於事故現場）。

**未涵蓋（誠實界定）**：`PlayerDownloadServer.update` 的發送序列化仍用共用 buffer 池（其 CRC32 為 per-connection 且僅主緒＝分析上安全）——發送方向若有池污染，客戶端
CRC 檢查會擋下並重新請求，不落盤；黑邊／重送觀測歸 W4-1 戰場。`saveNow` 的佇列重排（同 chunk 新舊存檔順序可顛倒）與 `run()` 例外路徑的 task 洩漏是兩個獨立的
vanilla 缺陷，影響小、暫不動刀，記錄於此供 TIS 回報。

### 2026-09-28 42.21 對版

**CRC 兩刀退役（官方已修）**。42.21.0 把存檔管線所有共用 CRC32 拿掉，改成每次呼叫
`new CRC32()` 的區域變數，執行緒之間沒有共用實例可競爭：

- 欄位：42.20.4 的 `ServerChunkLoader.crcSave`、`SaveChunkThread.crc32`、`IsoChunk` 的
  static `crcLoad`／`crcSave` 在 42.21 全部不存在（`javap -p` 對照）。
- `SaveChunkThread.addLoadedJob`：42.20.4 在 offset 35 `getfield crc32`；42.21 在 32 `new CRC32`、39 `astore_3`，43 `SaveLoadedChunk(Chunk,CRC32)` 傳的就是這個區域實例。
- `SaveLoadedTask.save()`：42.20.4 在 22／32／64／95 四次 `getfield ServerChunkLoader.crcSave`；42.21 在 18 `new CRC32`、25 `astore_3`，之後的 update／getValue 都讀區域變數。
- `IsoChunk.Save(ByteBuffer,CRC32,Z)` 仍只用參數傳入的 CRC32 回填 header，呼叫端給區域實例＝header 指紋沒有共用狀態。

所以 `addLoadedJob` 的 `crc32 → headerCrc` 與 `save()` 的 `crcSave → dedupCrc ×4` 兩個同形替換一併刪除（目標欄位已不存在），helper 的兩個 ThreadLocal 也拿掉。命中數：`SaveLoadedTask.save`
5→1（只剩 W8 的 SafeWrite 改道）、`addLoadedJob` 4→3、`release()` 維持 1。首次生效橫幅從
`headerCrc` 移到私有池的 `getChunk`，上線驗證仍是 `grep ChunkSaveIsolation` 看到「首次生效」一行。

**第三刀（私有池）保留**。42.21 的 `ClientChunkRequest` 兩個全域 static 池（`freeChunks`／`freeBuffers`）與 `SaveChunkThread.update()` 都沒變：`update()` 仍用欄位
`savedChunks` 逐一 `release()`、整個方法沒有鎖。主迴圈（`ServerMap.postupdate → updateSaved`）與 shutdown hook（`QueuedQuit → QueuedSaveAll → SaveAll` 輪詢 `updateSaved`）同時跑時，同一個 task
可能被 release 兩次，全域池就會把同一顆殼／buffer 租給兩個主人（其中一個可能是發送端的
WorkerThread）。W8 攔得住不自洽的寫入，攔不住「buffer 被完整重填成別塊 chunk 的自洽資料」；私有池讓這條路徑不存在，成本近乎零。

**SmokeCheck 改釘**：刪除 crc32／crcSave 前提、全 jar 耦合鎖、swap 緊鄰性與 CRC 行為 smoke；新增「退役前提」（`addLoadedJob`／`save()` 各 `new CRC32` ×1，`ServerChunkLoader`／`SaveChunkThread`／`SaveLoadedTask`／`IsoChunk` 零 CRC32 欄位——TIS 退回共用實例即紅，復活兩刀用 `git checkout 8d2bee8`）與「之三存在理由」（`update()` 無鎖且讀 `savedChunks`、`ClientChunkRequest` 兩池仍為 static——TIS 加鎖或改成 per-instance 池即紅，重新評估之三）。`save()` 加負對照：零 `ChunkSaveIsolation` 呼叫。build 步驟 9d 的 off 路徑測試只剩三個池 helper。

**驗證閉環不變**：W8 `flagged` 在 42.21 應恆 0；不為 0 代表還有官方修正與私有池都沒涵蓋的機制，看 BLOCKED stack。

<a id="2v"></a>
## 2v. 抑噪第 8 項—— toxic log 改道

**根因**：MOD PSR（Plysken Solar Revolution）在每個遊戲分鐘（~2.5 真實秒）無條件呼叫
`IsoBuilding.setToxic(false)` 遍歷所有 powerbank，導致建築毒氣狀態隨機刷新。server 的
`GameServer.sendToxicBuilding` 每次變動都廣播給全部 63 人。抑噪前 15.41 小時／8 session 實測：**164,176 行毒氣訊息／全 console 360,669 行 ＝ 45.5%**（逐 session 35.5%–80.8%、17–25 個相異座標），淹沒真正的錯誤。**按 `(frame, building, value)` 去重只能消除 19.8%**——`(frame,building)` 組合
96,451 個只出現一次、30,982 個兩次，主體是同一 building 每 2.5 秒跨 frame 反覆送，不是同 frame 重複。

**為什麼只攔 log、不動封包**：client 的 `WorldRegionToMetaGrid.lambda$updateSquares$0` 自己計算「該室內有 activated generator」並本地標記 `toxic=true`，**不通知 server**。若 server 端做去重來減少廣播，會把玩家鎖在會扣血的毒氣室裡——client 認為有毒而 server 說沒有，結果玩家進去直接扣血但看不到警告。這是本項最有價值的知識：server 的 `isToxic` 只是「上次送了什麼」的殘影，不是真實狀態。**只有攔 log 才是安全的**。

**手術**：`zombie/network/GameServer` 的 `sendToxicBuilding (IIZ)V`，offset 11 的
`INVOKESTATIC zombie/debug/DebugLog.log:(Lzombie/debug/DebugType;Ljava/lang/String;)V` 改道
`zombie/mdc/LogFilter.logType`，expectedHits = 1。訊息由 offset 6 的 `invokedynamic makeConcatWithConstants`
組成（BSM recipe = `Send Toxic Building at [ \u0001 , \u0001 Toxic: \u0001 ]`），座標與 boolean 都是變數→ 必須 `startsWith`，不能 `equals`。

**為什麼安全**：

- **method-scoped**：`GameServer` 全 class 有 21 個同 descriptor 的 `DebugLog.log` 呼叫點，本方法內恰 1 個。其他 20 個呼叫點保持原版。
- **廣播封包不動**：`sendToxicBuilding` 的 putInt 序列、`udpEngine.connections` 廣播路徑完全保留。所有玩家仍會收到最新的毒氣狀態——不是斷網，只是 console 安靜。
- **client 端狀態不變**：client jar 完全未動，`receiveToxicBuilding` 照常執行。

**已知代價**：server console 少了一條「client 還在收廣播」的 liveness 訊號。該訊號的對稱項在 client
端 `GameClient.receiveToxicBuilding` 的 `Receive Toxic Building at [ ... ]` 仍在，本 patch 未動。

**SmokeCheck 斷言**：vanilla 前提（恰 1 個 `DebugLog.log(DebugType,String)` ＋封包段 `PacketType.send` 存在）／手術後（改道 ×1、原呼叫歸零、`send` 與 `putInt` 數量與 vanilla 相同）／負對照（全 class
21→20 保持 vanilla、helper 恰 1）。

---

<a id="2w"></a>
## 2w. 食材重量記憶化（**實測後決定不啟用 `on`，維持 observe**）

> **退役（2026-09-02）**：本刀（`InventoryItem.getExtraItemsWeight` 的 `CreateItem`
> 改道）已移除。observe 實測收益僅 0.06–0.18%，「永不啟用 `on`」既已定案，留著 observe 只是白背一個 patch 表面與每次更新的重驗成本。復活方式：從退役前最後一版 13650e1 取回（`git checkout 13650e1 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

**浪費**：`InventoryItem.getExtraItemsWeight ()F` 對 `extraItems` 内每個 fullType 字串完整建構一個 InventoryItem，只為讀 `getActualWeight()` 就丟棄。單次建構含 `ScriptManager.FindItem`（兩次
hash、miss 退化為 moduleList 線性掃描）＋`Item.InstanceItem`（codeLen=4064 ＞ FreqInlineSize=325
故永不 inline）＋4 個 ArrayList＋10 次 `Translator.getText`＋`synchWithVisual`＋`ConfigureItemOnCreate`，保守估 ~1.6-2.0 KB 配置、1.5-5 µs。

**頻率**：`Moodle.Update` 的 HEAVY_LOAD 分支（無節流）→`ItemContainer.getCapacityWeight`
→`IsoGameCharacter.getInventoryWeight`→`getUnequippedWeight`／`ItemContainer.getContentsWeight`
**相互遞迴走訪整棵巢狀背包樹**，每玩家每 tick 一次。2026-08-16 jstack 46 樣本命中 2 次。

**無法死工消除**：`IsoGameCharacter.updateInternal` 有兩個 `Moodles.Update` callsite——`:9103` 在
`GameClient.client` 為真時、`:9129` 在 `!client` 分支——vanilla 刻意讓 dedicated server 跑。HEAVY_LOAD 被 `calculateBaseSpeed`（減速）、`Fitness.reduceEndurance`、`testDefense`、`getClimbingFailChanceFloat` 消費，是 server 權威 gameplay。

**手術**：`zombie/inventory/InventoryItem` 的 `getExtraItemsWeight ()F`，offset 35 的
`INVOKESTATIC zombie/inventory/InventoryItemFactory.CreateItem:(Ljava/lang/String;)Lzombie/inventory/InventoryItem;`
改道 `zombie/mdc/ItemWeightMemo.createItem`，expectedHits = 1。Helper 新檔 `patcher/game/zombie/mdc/ItemWeightMemo.java`。

**三態旋鈕** `-Dmdc.itemWeightMemo`（class 初始化時讀一次，改值需重啟）：

- **`observe`**（預設，第一版唯一上線的模式）：完全不保留任何 InventoryItem 實例。`SEEN` 只存字串，且只收「通得過五道門的型別」——因為只有它們在 on 模式下真的進得了 CACHE。`hits` = 重複且可快取的呼叫數（＝**啟用 on 之後真正會命中的次數**）、`misses` = 首見且可快取的型別數、`uncacheable` = null
  或被門擋下的呼叫（on 模式下每次仍會重新建構，**不計入命中率**）、`vanillaNsAvg` = 原版單次建構耗時。**行為與原版逐位元相同**。（第二輪 review 抓到早期版本把 null 與被門擋下的型別也算 hit，會讓唯一的上線決策依據灌水，已修正。）
- **`on`**：命中即回傳共用實例。**尚未經正式服量測驗證，預設不啟用**。
- **`off`**：純轉發。

**factory 恰好呼叫一次**：`createItem` 切成三段（呼叫前觀測／原版 factory／呼叫後觀測），factory
不在任何 try 之内。前後兩段各自吞 `RuntimeException | LinkageError`。拋例外時不重跑 factory（避免 `Rand.Next`、`initialiseItem` 的 Lua OnCreate、MOVEABLE 的 script 寫回執行兩次）。

**`on` 模式的五道門**（`cacheable`）：非 null 且 `scriptItem != null`／`getLuaCreate() == null`（`Item.InstanceItem:1916-1918` 的 Lua 回呼）／`getItemConfig() == null`（`:1915` 的 `ConfigureItemOnCreate`
→`ItemConfig.ConfigureEntityOnCreate`）／`!isItemType(MOVEABLE)`／`!hasComponents()`。**MOVEABLE 是三方 review 抓到的實質風險**：`Item.InstanceItem:1801-1805` 對 MOVEABLE 執行
`this.actualWeight = moveable.getActualWeight()`，寫回共享的 script 單例，而所有 `Moveables.<sprite>`
共用同一份 script。**第五道門用 vanilla 自己的判斷**：`Item.InstanceItem:1909` 無條件呼叫
`GameEntityFactory.CreateInventoryItemEntity`，而它內部正是以 `itemScript.hasComponents()` 決定要不要
`createEntity`（GameEntityFactory.java:114-117），所以拿同一個謂詞當門，跳過建構就不會漏掉任何 ECS
component 建立／連接。

**`on` 模式的已知行為差異**：全域 RNG 序列位移（命中時少抽的 `Rand.Next` 至少兩次——`createItemInternal:139`
的 id、`InstanceItem:1911` 的 OutfitRNG 種子；實際次數依型別分支增加：KEY 的 keyId、CLOTHING／ALARM_CLOCK_CLOTHING
的 palette、MAP 的 pickRandom、RADIO 的 setRandomChannel）。PZ MP 非 lockstep（server 權威＋client 預測），故不 desync，但抽樣序列不同。不做補抽補償（次數隨分支而異，猜錯更糟）。

**共用實例的安全性**：`getExtraItemsWeight` 內該區域變數（slot 3）只被 `IFNULL` 與兩次
`getActualWeight()` 讀取，迴圈下一圈即覆寫，從不逃逸——由 SmokeCheck 的 `extraWeightNoEscape`
語境指紋鎖住（factory 結果緊接 `ASTORE`、該 slot 恰 3 次 ALOAD 且消費者僅那三處）。

**不做 null 負快取**：`InventoryItemFactory.createItemInternal:113` 找不到 script item 時印 `Couldn't find item`
並回 null，那是「有 recipe 引用不存在的 item」的訊號；快取 null 會讓它只出現第一次、也讓 mod 之後補註冊時永遠取不到。`nullResults` 計數追蹤它。

**SmokeCheck 斷言**：vanilla 語境指紋（恰 1 個 `CreateItem(String)` ＋2 個 `getActualWeight()` ＋零逃逸——factory 結果緊接 `ASTORE`，該 slot 只被 1 次 `IFNULL` 與 2 次 `getActualWeight` 讀取，共 3 次 ALOAD）／手術後（改道 ×1、原呼叫歸零、真指令總數與 vanilla 相同 = 1:1 替換）／負對照（全 class 5→4 保持 vanilla、`createCloneItem` 未被動到、helper 恰 1）／helper 契約（factory 委派恰 2 處 = off 純轉發＋phase 2，無第三處重試路徑）／五道門各恰一次。已用 mutation test（把 21／5／3 改成錯值）確認這些斷言真的會紅。

**實測結論（2026-08-17，observe 樣本窗 4 個 session／累計 uptime 9.68 小時，截至 11:12）——不啟用 `on`，收益不足以承擔風險**。樣本窗以記憶化實際生效的 session 為界（`01-28` 首次生效 01:30:50 起，含 `04-04`／`04-53`／`06-12`；**不是** PSR 統計那個 15.01 小時凍結窗——後者從 `20-04` 起算，當時這把刀還沒部署）。四個 session 的週期行給出三個關鍵量：

- **命中率**：`hits/(hits+misses)` = 99.997%（如 `attempts=4194304 hits=4194250 misses=54 types=54`），`uncacheable=0`、`nullResults=0`、`overflow=0`、`anomalies=0`。五道門在真實流量下沒擋掉任何東西，型別集合只有 25–54 個。
- **呼叫速率**：兩種算法都做。(a) 相鄰週期行的 `Δattempts / Δt` ＝ **328–732 calls/s**（中位約 520）；(b) 全期下界＝已印出的 attempts 總和 9,437,184 ÷ 9.68 h ＝ **271 calls/s**（各 session 重啟歸零、未達 2^20 的殘餘未計入，故為下界；比 (a) 低是因為含開服初期的低負載時段）。
- **原版單次建構**：`vanillaNsAvg` 隨樣本增加收斂到約 **2.1 µs**（首次取樣 2521 ns 偏高，後續 1980–2239）。

於是收益區間 = `271 × 2.1 µs` ≈ 0.57 ms/s 到 `732 × 2.5 µs` ≈ 1.83 ms/s，相對主迴圈單核預算（10 fps ⇒ 1000 ms/s）約 **0.06%–0.18%**，換算 **0.006–0.018 fps**。而 `on` 的代價是上面「已知行為差異」整段（全域 RNG 序列位移）＋**首次真正執行共用實例路徑**——observe **不走** memo 命中分支（`MODE == MODE_ON` 才查 `CACHE`；observe 只做一次
`SEEN.containsKey` 後照常呼叫 factory），所以 `anomalies=0` **完全沒有演練過共用實例**。風險遠大於 0.18% 的上限，維持 observe／或轉 `off`。

**方法教訓（值得記）**：命中率是比例、不是收益。99.997% 支撐「快取會命中」，不支撐「值得啟用」——絕對收益必須是「命中率 × 呼叫速率 × 單次成本」，缺了速率這一項就會把一把 0.1% 的刀當成主要優化機會。與 W3-2 ECS memo 同構（審查全綠、實測淨劣化而撤刀）：**只有量測證明有收益**。

**第二個方法教訓：樣本窗不可互借。** 這段結論第一版寫「14 小時 observe 數據」——那是從同一天
PSR 統計的凍結窗（`20-04` 起算、15.01 小時）借來的，但兩者的起點完全不同：PSR 從 `20-04` session 起算（該 session 起
`coverage REMOVE` 帶 `bank=` tag），而這把刀是 `01-28` session 才生效（橫幅 01:30:50）。前三個 session 根本沒有它。**每個 patch 的樣本窗必須用它自己的「首次生效」訊號界定**（本專案每把刀都有開機橫幅，就是為此），不能沿用同一天其他分析的時間範圍。對照組亦同：`18-12` 之所以能當 PSR 1.71 的對照，是因為實測它 0 筆帶 tag。

**`on` 期無法自帶對照組**（設計限制，先記下）：`vanillaNs` 只在 cache miss 走 factory 那條路累加，而 `on` 模式下 miss ≈ 型別數（數十次）且還須同時撞上 `(attempts & TIMING_MASK)==0` 才取樣，故 `vanillaSamples` 幾乎必為 0、`~vanillaNsAvg` 會印 0。日後若真要驗證 `on`，對照基準只能用
observe 期的歷史值 2.1 µs 去比 `on` 期的 `memoNsAvg`，不能期待同一份 log 自帶兩側數據。

---

<a id="2x"></a>
## 2x. 卡讀條根治（W10，server）

> **W10-A 退役（2026-09-28，42.21.0 官方已修）**：`NetTimedActionPacket.processServer` 兩處改以 `act.write` 送出（javap offset 81／142 `aload_3`），`Action.write／parse／copyFrom` 也無條件帶 playerId，initial Accept／Reject 都能被
> client 認領。B 刀（Lua 建構子保險絲）與 D1 保留，見本節末「2026-09-28 42.21 對版」。

**症狀**：MP 玩家的進度條走到 100% 後停住，動作動畫繼續 loop（「小人不停操弄手部」），成品不產出，被消耗的 input item 維持綠色 job 標記；且該玩家**後續所有排隊動作一起堵死**（`ISTimedActionQueue` 是單頭序列，head 不彈出則全塞）。玩家長期回報三種情境：製作、搬移家具後無法製作、以及「吃／閱讀／製作隨機發生」。社群普遍的處置是重開遊戲。

**正式服實證**（2026-08-23，單一 session 的 `server-console.txt`）：

| Lua 建構子 | 該行程式碼 | 命中 | 對應玩家回報 |
|---|---|---|---|
| `ISMoveablesAction.lua:308` | `item:getWorldSprite()`（`mode == "place"`） | 6 | 「鐵桶搬移後無法製作」 |
| `ISReadABook.lua:492` | `SkillBook[item:getSkillTrained()]` | 3 | 「閱讀」 |
| `ISEatFoodAction.lua:298` | `item:getContainer() or ...` | 3 | 「吃」 |

三者的例外訊息都是 `attempted index: <getter> of non-table: null`，stack 一致：

```
se.krka.kahlua.vm.KahluaThread.tableget:1430
Lua(Vanilla).new(ISReadABook.lua:492)
se.krka.kahlua.integration.LuaCaller.protectedCall:109
zombie.core.NetTimedAction.parse                    ← 中斷在這裡
zombie.network.packets.INetworkPacket.parseServer:55
zombie.network.PacketTypes$PacketType.onServerPacket:967
zombie.network.GameServer.mainLoopDealWithNetData:1611   ← 被 catch 吞掉
zombie.network.GameServer.main:909
```

同 session 另有 21 筆 `SyncItemFieldsPacket.parse:383` 的
`NullPointerException: InventoryItem.hasSharpness() because "item" is null`——同源不同封包（不造成卡讀條，但會讓物品狀態不同步）。

### 根因：兩個 vanilla 缺陷疊乘

**缺陷 1——靜默的 null 穿到 Lua**。`InventoryItem` 在封包中以「容器 ID＋item ID」傳輸：

`PZNetKahluaTableImpl.loadInventoryItem(ByteBufferReader, IConnection)` 先解析 `ContainerID`、再讀 itemId，容器存在才回 `getItemWithID(itemId)`，否則回 null。

容器找不到、或容器內沒有那個 itemId → **回 null，不 log、不拒絕**。該 null 成為
`NetTimedAction.parse` 組出的 `arguments[]` 的一員，餵進 Lua 的 `<Type>.new(...)`，而那些建構子第一件事就是索引它 → Kahlua 拋 `RuntimeException` → **穿過名為 protected 的
`protectedCall`** → `parse` 中斷 → `processServer` 從未執行 → server 既不回 Accept 也不回
Reject。諷刺的是 vanilla 本來就寫好了失敗處理，只是例外繞過了它：

`NetTimedAction.parse` 對 `protectedCall` 的回傳本來就有處理：不成功或第一個回傳值為 null 時，把 `action`
設為 null 後返回。這條路徑存在，但例外讓它到不了。

**缺陷 2——回覆封包帶錯 state**。`javap` 對真實 jar（`NetTimedActionPacket.processServer`）：

```
 51: aload_0 / 52: getAction / 55: astore_3      ← act 存進 slot 3
 60: aload_3 / 61: getstatic Accept / 64: setState   ← act.setState(Accept)   設在 act
 81: aload_0                                     ← this  ✘
 84: invokevirtual NetTimedActionPacket.write
142: aload_0                                     ← Reject 分支同病  ✘
145: invokevirtual NetTimedActionPacket.write
```

`this.state` 自 parse 起恆為 `Request`，所以該方法送出的初始 Accept／Reject 回覆實際上都是
Request。結果是 **initial Request rejection 無法讓 client 的 `ActionManager.isRejected` 成立**。已接受 action 在 `ActionManager.update` 中因 `perform()==false` 產生的後續 Reject 是從正確的
action 物件序列化，不受此缺陷影響。同 codebase 的 `ItemTransactionPacket.processServer` 也是寫對的對照（offset 25/59 直接 `this.setState`，44/78 再 `this.write`）。

### 為什麼 client 端一道自癒都沒有

| 機制 | 位置 | 為何失效 |
|---|---|---|
| `finished()` | `BaseAction.java:175` | 要求 `!waitForFinished`，而 `LuaTimedActionNew.start:129` 在 MP 一律設 true → 完成訊號只能來自 server |
| `hasStalled()` | `BaseAction.java:76` | 要求 `lastTime < 0` 或 `currentTime < 0`；卡住時 time 停在 `maxTime`（正值）→ 恆 false |
| 30 分鐘 timeout | `ActionManager.java:117` | 只把項目移出清單、**不設 Done/Reject**；而 `isDone:136`／`isRejected:128` 都有 `!actions.isEmpty()` 前綴 → 清單清空後兩者同時 false ＝從「等 30 分鐘」升級為「永久」 |
| `isUsingTimeout` | `ISReadABook:22`／`ISResearchRecipe:25` | 回 false → 連移出清單都不會發生 |

對照組：`TransactionManager.isDone:381`／`isRejected:377` **沒有**那個 `!isEmpty()` 前綴，空 stream 的 `allMatch` 回 true ⇒ 撿東西那條約 20 秒後會自動 `forceComplete`。`ActionManager` 就差這一個前綴。

### 手術（兩刀，皆 redirect；純 server 端路徑）

| 刀 | 掛點 | 改道 | 效果 |
|---|---|---|---|
| B | `NetTimedAction.parse` 內唯一的 `LuaCaller.protectedCall`（javap offset 167） | → `NetTimedActionGuard.protectedCall` | 攔下 `RuntimeException`，回一個 `isSuccess()==false` 的 `LuaReturn`（`LuaReturn.createReturn(new Object[]{FALSE, msg})` → `LuaFail`），讓 vanilla 既有的 `action = null; return;` 真正被走到 |
| A | `NetTimedActionPacket.processServer` 的兩處 `write`（offset 84／145） | → `NetTimedActionGuard.write` | `action == null`（即 vanilla reject 分支的判別條件）時把 state 補成 `Reject` 再送出 → client `isRejected` 成立 → `forceStop()` → queue 解除堵塞 |

**兩刀是「與」關係**：只有 B → Reject 送出去仍是 Request state；只有 A → `parse` 已中斷、`processServer` 根本沒被呼叫。缺一刀對玩家實測的症狀都是零效果。

**為什麼 client 不需要任何 patch**：`LuaTimedActionNew.update:93-98` 已經有完整的
`isDone → forceComplete` / `isRejected → forceStop` 邏輯，只是從來沒被觸發過。我們只要讓
server 把正確的封包送出去，client 就會自己解除。

### 語意邊界（刻意不做的事）

1. **不猜、不代找那個 null 的 `InventoryItem`**。猜錯會消耗錯誤材料或憑空產出成品。本刀的語意是「把靜默的永久卡死變成有聲的失敗」——玩家看到動作中斷可重試，而非無限讀條。item 為何是 null（容器不同步／被前一步消耗）屬上游問題，由 helper 的診斷 log 蒐證後另案處理。
2. **不介入 accept 分支**。`Action.write` 在 `state == Accept` 時**不寫 playerId**，而 client 的
   `ActionManager.setStateFromPacket:244` 要靠 playerId 比對認領封包（`IDShort.id` 預設 0，對不上真實 onlineID）→ 補正 Accept 的 state 只會改變線路內容、拿不到任何好處。修它需要改 `Action.write`／`parse` 的線路格式，而該類 **client 與 server 共用**，單邊修改會讓對側讀錯位元組。副作用是 `maxTime == -1` 的動作進度條仍為 `POSITIVE_INFINITY`（體感問題），但 A+B 之後它不會再永久卡（Done 或 Reject 必有一個到達）。
3. **不介入 `!isConsistent` 那條 reject 路徑**。該路徑的 `getAction()` → `Action.copyFrom` 會對
   null player 呼叫 `PlayerID.set` 而先行 NPE，是獨立的既有問題，維持 vanilla 行為。

### 守門

SmokeCheck 十條，其中兩條是「本刀該不該存在」的結構事實：

- **A 刀存在理由**：vanilla 的 `processServer` 中 write 兩處 receiver 皆 `this`、setState 兩處皆非 `this`。**TIS 修好這個 bug 時該條會紅**——提醒撤刀，而不是讓兩份修正疊加。
- **B 刀著力點**：vanilla 的 `parse` 內存在 `ACONST_NULL → PUTFIELD action` 序列（本刀不新增失敗語意，只是讓既有路徑可達）。
- catch 型別鎖定 `RuntimeException`（`Error` 必須穿透，與 W6 同紀律）、helper 的 `write` 委派恰 1 次且**不在 try 範圍內**（診斷失敗不得改變線路行為）、兩處改道後真指令數與 vanilla
  相同（1:1 同形替換）、以及相對 vanilla 的 class-wide 差值負對照（`NetTimedAction` 只少一個
  `protectedCall`、`NetTimedActionPacket` 只少兩個 `write`）。

行為測試 `NetTimedActionGuardTest` 跑三個模式（出貨組態＋兩個 kill switch），自驗 argv 與
helper 實際旗標相符——property 名稱打錯會炸在測試裡，不會默默把 enabled 版跑三遍假綠。

**kill switch**（分離以便二分定位）：`-Dmdc.netTimedActionGuard=0`（B）／`-Dmdc.netTimedActionState=0`（A）。

### 驗證閉環

部署後 server log 應從「`Lua(Vanilla).new(...)` 例外 ＋ 玩家卡讀條」轉為「`[MinidoracatJavaPatch][NetTimedAction] lua ctor failed type=<Type> nullArgs=<i/j>` ＋`reject sent` ＋ 玩家看到動作中斷可重試」。`anomalies` 必須恆 0。`nullArgs` 是「某個建構子參數已反序列化為 null」的直接指紋；它能定位 action type 與參數位置，但**不能區分**是 container 解析失敗或 itemId miss。要區分兩者仍須在 `loadInventoryItem` 加觀測。

**建議回報 TIS**：`loadInventoryItem` 靜默回 null ＋ Lua 建構子無 null 守衛 ＋`protectedCall` 未攔 `RuntimeException` ＋ `processServer` 對錯物件設 state，四者疊起來就是「client 無限等待」。附 `ItemTransactionPacket` 作為同 codebase 的正確對照即可。

### 2026-09-08 校正：W10-D 僅處理 request 解析失敗，撤除 D2 座標救回

正式服曾記錄 `NetTimedAction` 封包在 `PZNetKahluaTableImpl.loadComponent` 拋 NPE：`GameEntityManager.GetEntity(netID)` 回 null 後仍直接 `getComponent`。這發生在
`NetTimedAction.parse` 的 `actionArgs.load`，早於 Lua 建構子；例外離開 parse 後，原版不會執行 `processServer`，因此沒有 Accept／Reject。這是 W10 原始兩刀以外的另一個入口。已有 Lua pcall 失敗則可能本來就回 `isSuccess()==false`；不能把所有卡讀條都歸因於 B 刀的
`RuntimeException` 捕手，也不能把 `caught=0` 當成沒有其他故障。

**本輪保留 D1、撤除 D2**：

| 邊界 | 現行行為 |
|---|---|
| `NetTimedAction.parse` 頭部 | `beginParse(this)` 清上一包原因，將上下文綁在本次 packet |
| 該方法唯一的 `actionArgs.load` | `loadArgs` 只在有效 parse 上下文且 `args && stateFix` 時攔 `RuntimeException`，清空半成品參數並留下失敗原因；`Error` 穿透 |
| 既有 `protectedCall` 改道 | 有解析失敗原因就不呼叫 Lua 建構子，回失敗結果，讓 vanilla 設 `action=null` |
| W10-A `write` 改道 | `action=null` 時序列化正確 Reject；原因只供同一 packet 使用，並在 write **之前**取用即清，write 拋錯也不留殘餘 |
| 共用 `PZNetKahluaTableImpl` | 完全不改、不出貨 loose class；`StatePacket` 等其他使用者維持原版（2026-10-01 起 W51 為觀測重新出貨：type 17 查不到動物時記一行，解析結果不變，見 [2bo](#2bo)） |

`-Dmdc.netTimedActionArgs=0` 關 D；`-Dmdc.netTimedActionState=0` 關 A 時 D 也直通。沒有正確 Reject 出口就不得吞解析例外。B 刀的 `netTimedActionGuard` 開關仍獨立。這只涵蓋 `actionArgs.load` 的失敗，不是整個 `parse` 的萬用 catch。

**D2 撤除理由**：舊版把 netID 解成座標，挑玩家附近唯一帶相同 component 的物件。距離與唯一性都無法證明它就是原物件；原目標消失後，另一件物品仍可能通過。且改道位於共用 decoder，影響面超過 timed action。故刪除整個 `loadComponent`／座標救回入口，不保留 fallback。本刀讓失敗變得明確，**不修復搬移過的製作台身分，也不保證重試一定成功**。

**驗證**：真 jar 類別的缺 component request → partial args 清空 → 建構子不執行 →
回覆 bytes 為同 action/player id 的 Reject；同一 packet 隨後解析正常請求不受污染。另驗缺 parse 上下文時仍原樣拋錯、原因不跨 packet、write 例外清狀態、`Error` 穿透。四組態＝出貨／B off／A off／D off。共用 decoder 負對照仍拋原版 NPE，不會選替代物件。

**線上判讀**：`argsFailed` 是被處理的解析失敗、`argsRejected` 是跳過建構子的次數；`reject serialized cause=` 只證明回覆已序列化，**不是 client 已收到／動作已恢復的證據**。解析失敗 log 列 `connectionPlayers`，不把同機多人連線的第一位玩家冒充發送者；`anomalies` 應為 0。新版本的正式服驗收尚未完成，不宣稱修後成功率。

**2026-09-10 精準診斷**：解析失敗行另列 action `type/name`；只有原版
`loadComponent` 本身拋 NPE、table 是原版實例且 buffer 已讀滿 long＋short 時，才以絕對讀取記錄 `componentRef=wire netId=… componentId=… readerPos=…`。其餘回 `componentRef=unavailable`，包含沒有 stack 的 fast-throw、自訂 table 與截斷資料。不改 buffer position／limit／byte order，不查找或替換任何 entity／component，也不改 Reject 行為。SmokeCheck 鎖住原 decoder 十位元組欄位順序與無 catch 的回傳鏈；真 decoder 測試涵蓋
heap／direct、唯讀 slice、不同 byte order 與未知例外。**wire 身分不是目前世界物件存在的證據**。

TIS 草稿：`docs/report/2026-09-07-tis-timed-action-followups.md` R1／R3，**尚未提交**。

### 2026-09-28 42.21 對版

- **A 刀退役**：42.21 原版 reject 分支已由 `act` 序列化 Reject（官方修法與本刀相同）；`processServer` 內的
  `NetTimedActionPacket.write` 呼叫點不復存在（改道命中 0）。刪除 `NetTimedActionGuard.write`、`takeCause`、`-Dmdc.netTimedActionState`，`NetTimedActionPacket.processServer` 只剩 W10-C 的 1 個改道（expectedHits 3→1）。
- **B／D1 保留並與 A 解耦**：`loadInventoryItem` 靜默回 null、`loadComponent` 對缺席 entity 直接 NPE、`LuaCaller.protectedCall` 不攔例外，三者在 42.21 逐位元未變（`parse` 命中 3/3、方法 SAME）。D 刀以前以 `ARGS_GUARD && STATE_FIX` 啟用（沒有 A 就沒有正確 Reject 出口）；42.21 原版就是正確出口，改為只看
  `-Dmdc.netTimedActionArgs`。失敗原因的生命週期改為「`beginParse` 清空、`loadArgs` 設定、`protectedCall`
  取用即清」，`parse` 走完後不留任何上下文。
- **log 變化**：`reject serialized cause=` 行隨 A 刀移除（原版送 Reject 不經 helper）；解析失敗仍由
  `args parse failed`／`lua ctor failed` 逐筆記錄。heartbeat 節拍改為每 2048 次 `parse`：`parses caught argsFailed argsRejected suppressed anomalies guard args`。
- **驗證**：`NetTimedActionGuardTest` 三組態（出貨／B off／D off）。缺 component 的 Request 在 D 刀下 parse 走完、不呼叫建構子，接著執行 dist 內的真 `processServer`（只替換 RakNet 送出端），擷取到恰 1 包、bytes 為同 action／player id 的 Reject。SmokeCheck 改釘「`processServer` 的 write 與 setState 兩處 receiver
  皆為 act」（TIS 退回 `this.write` 時紅＝重估 A 刀）與「processServer 不經 `NetTimedActionGuard`」。
- 復活 A 刀：`git checkout 8d2bee8 -- <檔案>`（PatchConfig／NetTimedActionGuard／SmokeCheck／測試／build.ps1）。

<a id="2y"></a>
## 2y. 動物聲音排序活鎖捕手（W11，server）

**事故**：2026-08-23 19:25:45 起（W10 部署後第一晚，與 W10 無關——stack 全程不經
NetTimedAction 或任何 mdc helper），`IngameState.updateInternal` 拋
`IllegalArgumentException: Comparison method violates its general contract!`（TimSort），stack：`BaseAnimalSoundManager.update:45` ← `CollisionManager.resolveContactsInternal:367`
← `IsoWorld.updateWorld:3340` ← `IngameState.updateInternal:1508`。19:25–21:47 斷續
1411 次後**惡化為每幀必炸**：A 段（封包處理）活著（聊天正常、連得上），B 段每幀中斷
→ `updateManagers()`（`ActionManager`／`TransactionManager`）永久跳過 → **全服卡讀條、撿不起物品、「時間停止」**。frame 照推進（frameNo++ 在炸點前）所以看門狗不救；唯一止血是重啟（graceful 收得進去——console 指令執行在 `:957-971`，炸點 `:1508` 之後）。

**vanilla 缺陷（兩層疊乘）**：

1. **比較器違反契約**：`compare` 每次呼叫都現場重算
   `FMODParameterUtils.getClosestListenerDistanceSquared`，且用 `>`／`<` 手寫三態——
   NaN 與任何值比較皆 false → 回 0（「相等」），違反遞移性（NaN「等於」所有人，但其他人彼此有大小）。TimSort 偵測到不變量被破壞即拋 IAE。無 listener 時回
   `Float.MAX_VALUE` 是一致的，所以**唯一能炸的輸入就是 NaN 座標**。
2. **炸後活鎖自我強化**：`update()` 的 `characters.clear()` 在 sort **之後**（javap：sort offset 19、clear offset 116+）。sort 一拋 clear 即跳過，清單永不清空，stale／已 despawn 動物參照永久滯留 → 之後每幀重炸。這解釋了正式服「19:25 偶發 → 21:47 每幀」的惡化曲線。

**觸發背景（非缺陷方）**：圈養農場 50–80+ 隻動物（<pen-1> 老鼠場、<pen-2> 兔場）高密度碰撞＋Cleaner 舊版每分鐘批次 `animal:remove()`×20（時間對齊：19:25:03 最後一批清除 → 19:25:45 首炸，間隔 42 秒）。但 `remove()` 是合法公開 API，vanilla 自己的
despawn 走同一路徑——**修 Cleaner 只能降頻，缺陷本體在 vanilla**。NaN 的精確生成點（動物側 vs listener 側）尚未定罪，由本刀的診斷 log 蒐證。

**手術**：`update()V` 內唯一 `ArrayList.sort(Comparator)V` callsite（offset 19）redirect →
`AnimalSortGuard.sort`（3B→3B、堆疊 2 進 0 出不變）。helper 語意：

- 正常路徑直接委派（逐指令等價）
- TimSort 拋 IAE → 吞下、計數、掃清單記 NaN 座標動物、**不排序直接返回**——聲音優先級退化一幀（清單每幀重建，無害），`update()` 走完 → `clear()` 執行 →
  **活鎖鏈條斷開**
- **只攔 IAE**（TimSort 契約違反的精確型別）；其他 RuntimeException 與 Error 穿透維持 vanilla（與 W6/W10 同紀律）
- 診斷：`nanAnimals=0` 但仍炸 ＝ NaN 在 listener（玩家）側——後續根因的黃金判別
- 部分排序狀態不回滾：TimSort 就地排序拋出時清單半排，該清單僅供「取前 N 近發聲」，無持久影響

**刻意不做**：不重刻排序語意（快照 key 排序）——那要假設比較器意圖，TIS 改語意時會默默錯位；捕手對任何比較器實作都成立。

**守門**：SmokeCheck 六條——vanilla 前提（update 內 sort 恰 1）、**順序錨**（sort 先於
clear；TIS 把 clear 移進 finally 或 sort 前時此條紅，提醒重估本刀）、手術後 1:1、catch 型別鎖 IAE、委派恰 2 處（off 直通＋on）、class-wide 差值負對照。行為測試兩模式（on/off）：等價／IAE 攔下／非 IAE 穿透／Error 穿透。

**kill switch**：`-Dmdc.animalSortGuard=0`。

**驗證閉環**：事故複現條件下（農場動物回升＋Cleaner 清除）console 應出現
`[MinidoracatJavaPatch][AnimalSort] contract violation caught size=... nanAnimals=...`
而**不再**出現 `IngameState.updateInternal> Exception thrown`（該 stack）；全服不卡讀條、不需重啟。`anomalies` 恆 0。


<a id="2z"></a>
## 2z. 車輛 DB chunk 索引一致性守衛（W12，server）

**事故**：2026-08-23～24 正式服連續三輛車在卡車／仍坐車斷線／21:58 crash 後不可見；`vehicles.db` 列與 blob 都仍在，但 header 的 `wx,wy` 與 `x,y` 自相矛盾：

| id | script | x,y | 錯誤 wx,wy | 正確 wx,wy |
|----|--------|-----|------------|-------------|
| 152 | `84mercLWB4` | <x>,<y> | <chunk-v1> | <chunk-v2> |
| 64 | `92nissanGTR` | <x>,<y> | 0,0 | <chunk-v3> |
| 2518 | `90bmwE30m3` | <x>,<y> | 0,0 | <chunk-v4> |

`VehiclesDB2` 載入只以 `WHERE wx=? AND wy=?` 查 row，所以車在正確地點永遠不會載入。三筆皆由停服施工窗修正，原 blob 不動。

**vanilla 根因鏈（高置信，約 0.82；精確保存交錯約 0.60）**：

1. 車輛 physics 先更新 x/y；只有 `current` 非 null 且 chunk 改變時才重綁
   `vehicle.chunk`，`current` 又到 `postupdate()` 才刷新。
2. `IsoChunk.resetForStore()` 清 `vehicles` 並把 pooled chunk 的 wx,wy 設成 0,0，卻不反向清掉每台車仍持有的 `vehicle.chunk`；同一物件 checkout 後會改成任意新座標。
3. 玩家仍在車內斷線時，`GameServer.disconnectPlayer()` 立即呼叫
   `VehiclesDB2.updateVehicleAndTrailer()`。
4. `VehiclesDB2$VehicleBuffer.set()` 從 `vehicle.chunk` 取 wx,wy、從 vehicle physics 取 x,y；SQL 原樣 commit，無 invariant。故 reset 後寫出 0,0，reuse 後寫出任意錯格。

**手術**：鎖定 private inner class
`VehiclesDB2$VehicleBuffer.set(BaseVehicle)` 中唯一
`aload0 → aload1 → BaseVehicle.getY()F → putfield y:F` 全序，在原 y 寫入後追加 16 條線性指令：從 `VehicleBuffer` **已捕捉**的 x/y 與原 wx/wy primitive 餵給
`VehicleChunkIndexGuard.wx/wy(BaseVehicle,float,int)`，再覆寫對應 buffer 欄位。Helper 以 `PZMath.fastfloor(coordinate / 8.0F)` 推導；off／非 finite 回傳同一 snapshot 的原值。

**範圍與風險**：

- 一點涵蓋 add/update、斷線、cell unload、拖車與 SQL INSERT/UPDATE。
- 不修改 physics、world membership 或 chunk lifecycle；只保護 persistence header。
- 不能消除當輪 client 車輛 desync，也不自動修已損壞 row。
- x/y 非 finite 時保留 vanilla chunk 值並記 anomaly，不用猜位置。
- anomaly log 只印前 8 筆與每 64 筆，包含 sqlId、兩組 chunk、x/y 與 chunk identity。

**守門／測試**：

- method-scope `expectedHits=1`；全序任一步漂移即建置失敗。
- SmokeCheck 鎖 vanilla chunk/wx/wy 讀取數、helper 各一次、全 class 無外洩、完整 operand/order、wx／wy 寫回正確欄位、真指令只 +16。
- 行為測試鎖正負座標邊界、實案 <chunk-v1>→<chunk-v2>、NaN／Infinity fallback、null chunk 不被 helper 解參考，以及 kill switch 回傳 captured vanilla 值。

**kill switch**：`-Dmdc.vehicleChunkIndexGuard=0`。

**官方回報**：見 `docs/report/2026-08-24-vehicle-chunk-index-corruption-tis.md`。

---

<a id="2aa"></a>
## 2aa. 動物同步範圍對齊（W13，server）

**現象**：正式服穩態出向流量中，帶動物完整快照特徵（`maxWeight`／`ageToGrow`／`fertility`／`meatRatio`／`eggSize` 等基因欄位名）的封包占 **38.3–39.8%**，是最大單項。不是事故——沒有 crash、沒有活鎖，只是持續吃掉近四成上傳。

**鑑識**（雙向 pcap 解碼，自製 decoder：Ethernet/IPv4/UDP → RakNet connected datagram（reliability／split header）→ 以 `(src,dst,ports,splitId,splitCount)` 重組 → `0x86` ＋ BE
short PacketType → `AnimalUpdatePacket` requested 區）：

| 指標 | 實測（8.03 秒／25,000 datagrams） |
|---|---:|
| client→server requested IDs | 109 |
| server→client full snapshots | 109 |
| 相異 `(client endpoint, onlineID)` | **14** |
| 5 秒內重複的 tuple | **14/14（100%）** |
| 第一次以外的重複 snapshot | **95/109（87.2%）** |
| 每 tuple 在窗內 full 次數 | **5–10** |
| request→response 配對 | 109/109（43.9–94.2 ms，median 72.7） |
| `dataSize` 平均 | 1,125.4 bytes |
| 動物距該 client 玩家 | **70.4–91.5 squares** |
| RakNet split groups／不完整 | 1,167／4 |
| AnimalUpdate parse errors | 0 |

重送節奏貼合 `UpdateLimit` 的 800/1000 ms；類型是野生 deer／mouse／rat，非單一牧場。

**vanilla 根因（幾何不一致）**：握手時（`GameServer.receivePlayerConnect`，反編譯 GameServer.java:2771-2772,2788-2789），server 保存的是 clamp 後值：

```
range          = clamp(client 送來的 chunk grid width, 12, 20)
relevantRange  = range/2 + 2
chunkGridWidth = range
```

`ClientServerMap.loaded[]` 與 client chunk streaming 無關：它追蹤的是 64-square
server-cell `isLoaded` 狀態，不是每個 8-square client chunk 是否已完成載入。

對正常、未被 clamp 的奇數 width，client chunk 視窗在 streaming 完成後的共同安全半寬下界是
`(range/2) chunks × 8` squares（**整數除法**）。但
`AnimalSynchronizationManager.sendUpdateToClient` 判 relevancy 用的半徑是（javap offset 233-242）

```
(getRelevantRange() - 2) * 10  ==  (range/2) * 10
```

半徑是該安全下界的 **10/8 倍**，因此額外環帶會包含 client 尚無 GridSquare 的位置；pcap 沒有逐封包 loaded-set 證據，所以不能宣稱整個環帶每一格都未載入。

**必須用整數除法算**：`IsoChunkMap.CalcChunkWidth` 強制正常 grid width 為**奇數**（自動計算上限 19；debug 選項 5/7/9/11/13），`GameServer` 只把值 clamp 到 12–20。寫成 `range*4`／`range*5` 對奇數 range 會算錯——range=13 是 48／60，不是 52／65，`range*4` 反而超出安全下界 4 squares。閉環：

1. 環帶動物照收輕量 `AnimalPacket`；
2. client 本地無 instance → 把 onlineID 放進 requested 並 `sendRequestToServer`；
3. server 回 `IsoAnimal.save()` 全量（modData ＋ `fullGenome` 每個 `AnimalGene`／兩個
   `AnimalAllele`，gene 名字串重複三次，約 1.1 KiB）；
4. client 端 `AnimalPacket.isConsistent` ＝ `getCell().getGridSquare(...) != null`（AnimalPacket.java:252-262）——格子沒載入就 **整段跳過**、不建 instance；
5. 下一個 800/1000 ms 更新再送輕量包 → 回到 2。

**手術**：改道 `sendUpdateToClient` 內唯一的 `UdpConnection.RelevantTo(FFF)Z`（invokevirtual → invokestatic，receiver 前置；淨堆疊與指令長度皆不變）到
`AnimalRelevancyGate.relevantTo`，把半徑夾到 `(getChunkGridWidth()/2) * 8`。

**為什麼不用 constChange**：`Patcher.ConstChange` 是**逐方法**的，所以「`bipush 10` 在本
class 有兩處（另一處在 `isAnimalOnScreen`，語意是 800 vs 1000 ms 節拍選擇）」並不足以排除常數替換——method-scope 的 `10→8` 在 `sendUpdateToClient` 內數學上等價。真正的理由是：① 常數烘進 bytecode 就沒有 runtime kill switch，硬規則要求不重新部署即可降回 vanilla；② `chunkGridWidth` 是 server 可取得的最佳寬度輸入，但必須先排除 clamp 多解與偶數幾何，不能把它當 client 已完成 streaming 的 loaded set；③ 載具排除（見下）需要 helper 邏輯，常數手術做不到。

**範圍與風險**：

- 只縮不放：`aligned >= vanillaRadius` 時走 vanilla（`passthrough` 計數）。
- **載具排除（必要，不是保守起見）**：`IsoChunkMap.ProcessChunkPos`（IsoChunkMap.java:868-878）在玩家位於載具時把 chunk-map 中心沿行進方向前移 `currentSpeedKmHour / 5` squares（乘客 `min(s*2, 20)`，駕駛無上限）。server 的 `releventPos` 是玩家實際座標、**不知道這個前移**，所以載具情境下任何以玩家為中心的半徑都會同時前側擋掉已載入格（動物該出現卻不出現）、後側放行未載入格（迴圈照舊）。故任一 player 在載具內即整段 passthrough
  ——**本刀只對步行玩家生效**。
- **殘留誤差（刻意接受）**：client 載入範圍是 **chunk 對齊矩形**，這裡夾的是**連續半徑**（`RelevantTo` 是軸對齊方形，非圓形）。player 在 chunk 內的連續偏移
  `p ∈ [0,8)` 使兩側可用寬度相差小於 8 squares；`(range/2)*8` 是所有 p 的共同安全下界。
  - **over-send ＝ 0**只在完整前提成立：未被 clamp 的奇數
    `stored ∈ {13,15,17,19}`、該 player 的 server/client center chunk 一致、相關 chunks
    已 streaming 完成，且 `RelevantTo` 沒有先由 `connectArea` 命中而是走 radius 分支。只對這條 radius 分支可說重送閉環**應該消失**而非「縮小」；其餘情境仍是保守 mitigation。正式服若仍見重送，不要歸因於下列 `<8 squares` under-send。
  - **under-send < 8 squares**：較寬側那些格子其實已載入、動物卻不同步 ⇒ 視野最外緣不到一格 chunk 的動物可能晚出現。這是本刀付出的代價。「範圍外沒格子所以零可見變化」是**過度宣稱**，不要這樣寫。
- **中心與 streaming 是兩個獨立前提**：server 的 `releventPos` 只在
  `PlayerPacket.processServer` 收包時更新（節拍最長約 600 ms ＋網路延遲），client 的
  chunk 中心由本機座標即時決定。步行跨 chunk 或 teleport 會造成 center 暫時不同；即使中心相同，`WorldStreamer` 也可能尚未完成相關 chunks。兩者都可能留下暫態，所以**不能宣稱「步行一律安全」**。要完全消除得由 client 判斷 loaded set，或由 client
  明確回報給 server——純 server 半徑做不到。
- **clamp 邊界檢查**：`GameServer` 存進 connection 的是 `max(12, min(20, raw))`。只在 `stored ∈ {13,15,17,19}` enforce（13–19 唯一解，但 14/16/18 因偶數 rectangle 不對稱仍 passthrough）。`stored=12` ⟺ `raw ≤ 12`（可能是 debug 5/7/9/11）、`stored=20` ⟺ `raw ≥ 20`，兩者都**無法還原** client 真實寬度；少這道檢查，raw=11 會被當成 12（實際共同半寬 40、卻算成 48）⇒ 保留 8 squares over-reach，迴圈照舊。
- coop／split-screen：`RelevantTo` 先比對 `connectArea[n]`，命中即回 true 而不看 radius（**miss 後仍會查 radius**）。靜態碼無法證明命中時區內每個 client chunk 都已完成
  streaming，因此 `connectArea` 載入窗是殘留驗證項，不把它當精確 loaded set。
- 不動 requested 端處理。針對「已登入 client 主動要求任意 onlineID」的放大面（`setRequested` 無 relevancy／無冷卻，每包最多 150）另案評估——需要在 requested
  端加 gate，手術形狀是線性插入，風險層級不同。
- 不改 `IsoAnimal.save` 位元格式（那會動 wire schema，vanilla client 就 parse 不了、必須雙端 patch）。本刀純 server 端，玩家不需安裝任何東西。

**守門／測試**：

- method-scope `expectedHits=1`。
- SmokeCheck **10 條**：vanilla 前提（`sendUpdateToClient` 內 RelevantTo 恰 1；半徑源自
  `getRelevantRange` 且**不讀** `getChunkGridWidth` ← 缺陷的結構事實，TIS 改用
  chunkGridWidth 或對齊常數時本條會紅、提醒撤刀；`isAnimalOnScreen` 不呼叫 RelevantTo）、手術後（改道 x1／原呼叫歸零／真指令數不變；`isAnimalOnScreen` 逐項未被碰）、helper 契約（`alignedRadius` 讀 `getChunkGridWidth` 恰 1；入口 3 條 vanilla 委派＋2 次夾過半徑判定；**載具排除讀 `getPlayerAt`／`getVehicle` 各恰 1**；**入口呼叫載具排除恰 1 次**）、負對照（全 class 恰少 1、改道恰 1）。
- 行為測試三模式各跑一次獨立 JVM（`MODE` 是 static final），自驗 argv 與 MODE 相符。主幾何迴圈：可信奇數 **13·15·17·19**（vanilla 實際值），clamp 邊界 **12／20** 必須 passthrough，偶數 **14·16·18** 因 rectangle 不對稱也驗證 passthrough；另有連續 offset
  `p ∈ [0,8)` 的共同下界幾何自檢（由 chunk 邊界獨立推導、不呼叫 production 公式）、原點 `Math.nextUp(floor)` 精確 float 門檻、±X／±Y 四方向、對角線與非零 index 測試：載入下界內與 `dist == floor` 必送、原點 `Math.nextUp(floor)` 依模式分流、vanilla 半徑外不送；**載具內 passthrough**／步行照常夾取／coop connectArea 不受影響／**遠距動物也計入 `rejected`**（證明 `rejected` 不是環帶占比）。真實 `UdpConnection.RelevantTo` 直接參與（連線與玩家實例用 `Unsafe.allocateInstance` 繞過建構子——vanilla `PacketsCache`
  建構子會拉進 `PacketTypes`→`AntiCheat`／`ServerOptions` 整條靜態初始化鏈；`players` 陣列必須補，否則載具檢查會 NPE 而全部退化成 passthrough）。
- 變異驗證（實測）：移除 `stored % 2 == 0` parity guard → enforce **6 個 `arg FAIL`**；半徑增加 1 ULP → 四個可信奇數各由原點 `Math.nextUp(floor)` 抓到，共 **4 個 `arg FAIL`**。原檔還原後完整 build 必須回到 0。

**三態**：`-Dmdc.animalRelevancy`＝`1`／未設 enforce、`2` observe（回 vanilla 結果、只統計 `suppressed` 判定差集）、`0` off（緊急降級，不需重新部署）。

**生效後觀測**：heartbeat `[MinidoracatJavaPatch][AnimalRelevancy] mode=1 calls=… rejected=…
suppressed=… passthrough=… anomalies=…`（週期 2^20 次判定）。`anomalies` 必須恆 0。

**計數語意（別看錯）**：`rejected` 是「夾過半徑判定為 false」的次數，**包含 vanilla 本來也不會送的遠距動物**（`toSendList` 未經距離預篩），所以 `rejected/calls` **不是**環帶占比。`suppressed` 只是 observe 下「vanilla=true、夾後=false」的**判定差集**；其中包含較寬側已載入卻被夾掉的 under-send，因此同樣**不是**環帶占比或實際浪費率。真正浪費仍以 pcap
重複快照指標為準。`passthrough` 會包含載具、12/20 clamp 邊界、14/16/18 偶數等情境。驗收重跑同一 pcap decoder：`repeat_5s` 與 extra snapshot ratio（基線 87.2%，目標 <5%）。殘留來源只可歸到載具／clamp／偶數 passthrough、requested 路徑、center 漂移、streaming 空窗或 connectArea 載入窗；不得歸因於 `<8 squares` under-send。**官方回報**：見 `docs/report/2026-08-24-animal-relevancy-resend-loop-tis.md`。

---


<a id="2ab"></a>
## 2ab. 動物 requested 冷卻＋範圍閘（W14，server）

**動機（實測）**：W13 上線後跨 transport 重量測（60 秒穩態、s2c entry coverage 100%、兩條獨立 decoder 逐值一致）：步行／direct 族群的 request→full 閉環已消失（0 request），但殘留 598 份 full 之中 **96.2% 落在 vanilla 環帶、98.5% 來自單一「全程在載具內」的連線**——正是 W13 刻意 passthrough 回 vanilla 的分支。172/181 tuple 在收到 full 後 ≥1 秒仍再要、間隔鎖在 800/1000 ms timer。載具情境下 server 半徑天生算不準（client chunk 中心沿行進方向前移），所以第二刀不再依賴幾何：**「同一連線同一動物，冷卻窗內只回一次完整快照」**。完整量測見 internal-analysis `reports/ops/2026-08-24-animal-fullgenome-network.md` §7。

**手術（兩個 redirect，都在 `AnimalSynchronizationManager.sendUpdateToClient`，與 W13 同一
MethodOps，expectedHits 1→6）**：

| # | 改道位置 | 原呼叫 | 用途 |
|---|---|---|---|
| 1 | offset 12、31 | `UdpConnection.getPacket(PacketTypes$PacketType)` invokevirtual 恰 2 處 | ThreadLocal 捕獲 connection，供範圍閘用。`sendRequestToServer` 是 invokeinterface `IConnection.getPacket`，(opcode, owner) 不同，redirect 按 (opcode, owner, name, desc) 精確匹配，不會誤中 client 路徑。 |
| 2 | offset 83、370、419 | `HashMap.get(Object)` invokevirtual 恰 3 處 | offset 83 是 `requests.get(guid)`（requested 過濾目標）；offset 370/419 是 `timerUpdateAnimal.get(Short)`。三處同簽名無法在 bytecode 層分開，runtime 用 `key instanceof Long` 完美分流：guid map 的 key 是 Long、timer map 的 key 是 Short；timer 路徑零配置直通（熱路徑）。 |

**為什麼在 `requests.get` 過濾是 wire-safe**：vanilla 對回傳集合只做 iterate（填進
`packet.requested`），不 mutate；send 後的清除走另一次 `computeIfAbsent`（offset 541-547），清的是原 map entry，不是回傳值。`AnimalUpdatePacket.write` 的 requested 區把「實際寫入數」回填進 requestedCount（對 animal==null 直接跳過；SmokeCheck 把「write 內
`AnimalInstanceManager.get` 恰 2」釘成結構前提），所以少放幾個 ID 不改任何線上格式。濾到全空且 updated/deleted 也空時該 tick 不送包、原 map entry 不被清。**不卡死的真正理由是
server 保留 map、下一 tick 重新過濾**——不是「client 會重送」：client 的 `sendRequestToServer`
只在它**收到** AnimalUpdate 包之後才跑（`parse` → `processClient` 尾端），我們不送包它就不會重送。因此**絕對不可以**在濾空時順手清掉 map entry：那會把索取直接丟掉，直到下一份 inbound
包才有機會恢復，動物就延遲出現或永不出現。

**冷卻語意**：

- **第一次一定放行**——動物「永遠不出現」的風險只存在於「丟棄且不回應」的設計，這裡不存在：首發照 answer，只有冷卻窗內的**重複**索取被暫時擋下。
- **標記時機＝過濾放行時**。放行後同 tick 內必然序列化（同執行緒、無讓出點；動物在
  filter 與 write 之間不會消失——`AnimalInstanceManager` 只在同一主執行緒變動）。**本 filter 的輸出上限就是 vanilla 自己的 150**，故 vanilla 的填充迴圈不會再截掉任何已 mark 的 ID——mark 與實際送出對齊。已知殘留例外：`write` 的 requested 區若中途拋
  `IOException`，vanilla 把 count 回填 0，但該批已 mark，於是要多等一個冷卻窗才重送（罕見故障路徑，刻意接受）。
- **observe 也標記**：observe 不擋任何東西，「放行」＝「實際送出」，標記是正確的實態記錄；日後切 enforce 帶著熱狀態，不會出現切換瞬間的重送尖峰。

**範圍閘（abuse 面，獨立開關）**：已登入 client 可對任意 onlineID 索取完整
`IsoAnimal.save()`（vanilla 無任何 relevancy 檢查，每包上限 150）。以 vanilla 自己的半徑公式 `(getRelevantRange()-2)*10` 加 `+48` squares 寬裕邊界拒絕明顯超遠的索取。+48 覆蓋載具 look-ahead（駕駛前移 speedKmH/5：48 格＝240 km/h；乘客上限 20 格）——被拒的 ID
距離之遠，連 vanilla 的 updated 路徑都不會對該連線宣告它，「看不到」本來就是正確行為；玩家回到範圍內後 updated 恢復、索取即被回應。ThreadLocal 捕獲缺失（理論上不可能：同方法內 getPacket 先於 requests.get）時跳過範圍檢查、只做冷卻——fail-open 到較保守的那一側。

**放大面上界**：`requestedCount` 是 client 端未設限的 int（`AnimalUpdatePacket.parse` 直接照數字讀、`setRequested` 全量複製進 server map），vanilla 只靠 send 迴圈的
`animalsCount >= 150 → break` 把每 tick 工作量壓在 150。故本 filter 的迴圈**也以 150 為上界**——否則一包 65,536 個 ID 就能把每 tick 成本從 O(150) 放大到 O(65,536)。另：不存在的 onlineID
一律**不 mark**（但保留在輸出，讓 vanilla 照原樣送包並清 map），否則可用大量假 ID 灌爆 bucket
觸發淘汰、藉此清掉真動物的冷卻。

**狀態與回收**：`guid → (onlineID → lastSentMs)`，Trove primitive map（無 boxing）。三道自癒界線：(1) 逾 cooldownMs 的條目天然失效（只比時間差，不需刪除）；(2) 每 30 秒掃一次、回收 120 秒未觸碰的整個 guid bucket（斷線連線由此回收，不需要 hook disconnect）；(3) 單 bucket 超過 2048 條目即整桶清空（`bucketResets` 計數）——最壞後果是該連線幾隻動物提早重送一次，不會 OOM。所有狀態變動都在 server 動物同步執行緒上（`update()` 單執行緒逐連線呼叫，遞迴 pending 亦同執行緒）；AtomicLong 計數器只為跨執行緒讀 heartbeat 的正確性。

**ThreadLocal 清除不變式**：`filterRequests` 的**每一條 Long-key 路徑**都必須經過
`finally { CURRENT.remove(); }`，包含兩把 kill switch 都 off 的組態——否則 `getPacket`
每次 `set` 都沒人清，ThreadLocal 會長期釘住最後一個 `UdpConnection`（含其 1 MB buffer），正是 `getPacket` javadoc 宣稱要避免的事。故 both-off 的 early return 刻意放在 `try` **內**（該路徑每連線每 tick 只到一次，進 try 成本可忽略）；非 Long key 的 timer map 熱路徑（每動物每 tick 兩次）刻意留在 try 外、也不碰 ThreadLocal——它在 bytecode 上晚於
`requests.get`，到那時同 tick 的捕獲已被清掉。

**三態（兩把獨立 kill switch，不需重新部署）**：`-Dmdc.animalRequestCooldown`＝`1`／未設
enforce、`2` observe（只計數不過濾）、`0` off；`-Dmdc.animalRequestCooldownMs` 冷卻毫秒（預設 6000，夾在 [1000, 30000]）。`-Dmdc.animalRequestRange` 同三態、獨立於冷卻。兩者皆
off 時 `filterRequests` 純委派 `map.get(key)`。

**守門／測試**：

- 與 W13 共用 MethodOps：`expectedHits = 6`（1 W13 RelevantTo offset 242 ＋ 2 getPacket ＋3 HashMap.get）。
- SmokeCheck **9 條**：vanilla 前提 3（`sendUpdateToClient` 內 HashMap.get 恰 3、getPacket
  恰 2；`sendRequestToServer` 走 invokeinterface ← redirect 不會誤中 client 路徑的結構事實；`write` 內 `AnimalInstanceManager.get` 恰 2 ← wire-safe 依據）、手術後 2（改道 x2/x3、原呼叫歸零、真指令數不變；`write` 與 `sendRequestToServer` 未被改動）、helper 契約 3（filterRequests 原 `HashMap.get` 委派恰 1＝fail-open 回 raw；範圍閘讀
  `AnimalInstanceManager.get`／`RelevantTo`／`getRelevantRange` 各恰 1；捕獲恰 1 次
  `ThreadLocal.set`＋恰 1 次原委派）、負對照 1（全 class 差額全部落在 `sendUpdateToClient`）。
- 行為測試五模式各跑一次獨立 JVM（模式是 static final）：`enforce`／`observe`／`off`／`cooldown-only`（range=0）／`range-only`（cooldown=0），自驗 argv 與實際模式相符。覆蓋：Long/Short key 判別式、空集合 fast path、冷卻生命週期（首發放行→窗內擋→
  skew+6001 再放行）、guid 隔離、範圍幾何三點釘（距 150 在 +48 帶內必在場、距 188
  cutoff 上仍在場、距 189/200 依模式分流）、被範圍擋下的 ID 不被 mark、connection null
  範圍跳過而冷卻照做、動物不存在放行、非 Short 元素防禦保留、cap 2049 整桶清空、sweep 回收、COOLDOWN off 整場零 bucket 不變式。真 `IsoAnimal` 直接注入
  `AnimalInstanceManager`（**坑**：其 `<clinit>` 走 `IsoObjectID` → `Rand.Next`，測試 JVM
  必須先 `RandStandard.INSTANCE.init()` 播種，否則 NPE→`ExceptionInInitializerError`；注入用 `getAnimals().put(id, animal)` 繞過 `add()` 的 DebugType noise）。
- 變異驗證（實測，完整 build gate；原檔還原後 baseline 一律 **0**）：

  | 變異 | 結果 |
  |---|---|
  | 冷卻判定永不命中 | 4 個 `arq FAIL` |
  | 判別式 `instanceof Long`→`Number` | 2 個（含 anomalies 連動） |
  | 範圍邊界 48→0 | 4 個（距 150／188 釘子全紅） |
  | 移除 150 迴圈上界 | 1 個（放大面上界） |
  | cap 改回「整桶清空」 | 1 個（既有冷卻被清＝bypass 回歸） |
  | 不存在動物也 mark | 1 個（cap bypass 前置） |
  | both-off early return 移回 `try` 外 | 1 個（ThreadLocal 清除不變式） |

  **最後兩項都是 review 後補測試才咬住的，值得記下方法論**：

  - 「不存在動物也 mark」首次變異 0 紅 ⇒ 「假 ID 不得 mark」這條 security property
    完全沒被覆蓋。補 I3（灌 2048+ 個假 ID 後 `markRefused`／`accepted` 都不得動）。
  - 「both-off early return 移回 `try` 外」首次也 0 紅，而且**第一版補的斷言仍然 0 紅**——因為它用了測試自己的 `filter(...)` 多載，那個多載會在呼叫前重新注入連線，使斷言無論 `finally` 有沒有清都恆真。改成直接呼叫 `filterRequests` 才咬住。**教訓：斷言必須避開會自行重置被測狀態的測試輔助方法，否則是恆真的假綠。**

**生效後觀測**：heartbeat `[MinidoracatJavaPatch][AnimalRequestGate] cooldown=… range=…
cooldownMs=… calls=… accepted=… cooldownSuppressed=… rangeSuppressed=… cooldownObserved=…
rangeObserved=… markRefused=… anomalies=…`（週期 2^14 次 Long-key filter 呼叫，量級遠低於
W13 判定熱路徑）。`anomalies` 必須恆 0。

**計數語意（別看錯）**：`accepted` 是放行的 ID 數；因為 filter 輸出上限＝vanilla 的 150，它與「實際進入 `packet.requested` 的 ID 數」一致（唯一例外是 `write` 期間 `IOException`
把 count 回填 0 的故障路徑）。它與冷卻開關無關（range-only 模式放行也 +1）；`cooldownSuppressed/rangeSuppressed` 只在 enforce 遞增、`cooldownObserved/rangeObserved` 只在 observe 遞增。`accepted` 不是「新動物數」——同一動物冷卻窗過後重送也 +1。

**部署後驗收**：重跑 internal-analysis 的
`evidence/.analysis/w13_compare_scientist.py`（雙 pcap 比較），觀察 repeat_5s 與載具連線的
full/s 是否由 ~1/s/tuple 降為 ~1/冷卻窗；殘留不得歸因於首發。機制保證是「窗內重複由每秒一次降為每冷卻窗一次」，**不承諾** extra ratio 必達 <5%——那要靠部署後量測。


---

<a id="2ac"></a>
## 2ac. 主迴圈凍結看門狗（W15，server，純觀測）

### 背景（2026-08-24 兩波卡頓事件的觀測缺口）

21:27–21:31 主迴圈近乎凍結累計約 216 秒（`f:7115→7118` 三幀、每幀 ~72s、console 靜默，只有 Steam callback 執行緒在印 initiating connection）→ RakNet 心跳逾時**同幀踢掉 9 條在線連線**（`Received packet type=X before Login, disconnecting` ×9 IP）→ 殘留封包以
connection-null 轟 12.5 分鐘（75,143 行）＋ PacketsCache 超限 18,260 行 → 玩家重連
chunk 重串流＝黑邊。事後鑑識收斂到三個互不排斥的候選機制，全部卡在同一個觀測缺口——**凍結當下沒有人拿到主執行緒的 stack**：

1. 主迴圈在跑極貴的動物相關工作（updateLOS／pathfind／同步；當晚 Animals Instances
   4174、loaded 770、AnimalRelevancy 判定 490k/s、「動物車」時間重疊）；
2. ZGC 瞬時 allocation stall（GC log 的 usage 是 cycle 邊界快照，cycle 中的瞬時峰值不被記錄，無法排除瞬間打滿 32G；basic `gc` tag 也不印 Allocation Stall 行）；
3. glibc 損毀 heap 上的 malloc 停滯（同日 4 次 native abort/SIGSEGV：`__libc_free`／`malloc_consolidate invalid chunk size`／`malloc invalid size (unsorted)`，損毀 allocator 上的 malloc 可掛任意執行緒任意久）。

已排除：swap（=0）、cgroup memory stall（PSI 累計 9.9s）、W12/W13/W14（下午對照組——不含 W13 的 clean 版 session 頓挫更嚴重；W14 當時未生效）。8 vCPU 下 GC worker
搶核為共同放大器（cpu.pressure 累計 3.8h）。三假說的裁決手段一致：**下一次凍結時的主執行緒 stack**。本刀把它自動化。

**2026-08-29 native 反編譯補充**（libPZPopMan64.so DWARF 反編譯，詳見 2h 後記）：假說 1 的
popman 分支獲得明確 native 機制——`n_updateMain` 對 `PassToMain` SPSC queue 做
**drain-to-empty、無筆數/時間 budget**（只有觀察到 queue 空才 return），大 backlog＝主執行緒線性長 stall；且 moodycamel queue 的 512 是**單一 block 大小**而非總容量（block 滿即 malloc 新 block 串接、無 backpressure），排除「queue 滿直接阻塞」機制、也意味 backlog 可無上限累積。裁決指紋因此更精確：快照若拍到主執行緒停在 `ZombiePopulationManager.n_updateMain`（native method frame）＝popman
backlog stall 定罪；`mcd::MapCollisionData::shouldWait` 為 worker idle 判斷、不阻塞主執行緒，MCD 側排除。

**2026-08-29 首戰定罪（80+ 人破峰值，376ce13 session）**：兩次凍結全拍到、同族——**排程存檔 `QueuedSaveAll` 在主執行緒的同步阻塞**，三假說（動物重活/ZGC/malloc）於此兩例全排除，第四機制成立。#1（f:26524，5.8s，RUNNABLE，heap 22.4G/32G）：`WorldMapVisitedServer.save`（`QueuedSaveAll:831`）逐玩家 zip deflate world-map-visited，「Saving took 5868ms」自證，人數線性放大。#2（f:40623，7s，TIMED_WAITING，heap 28.2G）：`ServerMap.SaveAll:173` 的 `Thread.sleep` 等 save worker 清佇列（「SaveAll took 5851ms」）。兩次 `ticksDuringStall=1`＝近乎完全凍結。候選刀方向（另案立案）：(a) worldmap visited
save 移出主執行緒/分批（非世界一致性關鍵資料）；(b) SaveAll 等待迴圈的 backlog 來源（SaveChunkThread 吞吐 vs 80+ 人 chunk 量）。W15 本身維持觀測不動。

**2026-09-02 定罪閉環（8/30–9/2 全部 RUNNABLE 凍結對帳）**：4 天所有非關機的 5–7s 凍結（8/30 ×9、8/31 ×1、9/1 ×3、9/2 ×3）快照**全部**落在同一族——`ServerMap.SaveAll` 等 4 條
`WorkerThread` 序列化 loaded cells（「SaveAll took 4474–5043ms」＝大頭）→
`WorldMapVisitedServer.saveUser` deflate（<1s，快照恰好拍在此段是 5s 門檻的時序巧合；`map_visited_server/*.zip` 每人僅 ~10KB）。**這是 vanilla「全存檔＝同步凍結」的設計**（`SaveAll` 主執行緒 `sleep(10)` 輪詢 worker 完成；chunk 序列化需世界靜止），patch 無法根治，候選 W21（visited 搬背景執行緒）只省 <1s、不立案。頻率面的兩個來源都已定案：排程存檔
`SaveWorldEveryMinutes=60`（每小時 2–3.6s）；`restart-countdown.sh` 的 `build_message`
每次公告（5/3/1 分鐘）都 `send_save`＝每次重啟前 5 分鐘 3 次額外 5–6s 凍結（9/1
21:05/21:07/21:09、9/2 00:00/00:02/00:04、00:35/00:37/00:39 逐筆對上）——**使用者決策（2026-09-02）：刻意保留，作為重啟前的存檔保險，不列待辦**。本刀維持純觀測、收案。**2026-09-06 後記**：「不可根治」不等於「不可縮短」——`SaveWorldEveryMinutes` 60→30 後用外部 probe
量 SaveAll 的 4 條 worker，抓到 80% 樣本卡在 `BitHeader`／`ByteBlock` 共用 `ConcurrentLinkedDeque`
池的 CAS 競爭（worker 4→8 反而倒賠），立案 **W25**（2am）把池改執行緒私有；縮短幅度以 W25 驗收為準。

### 手術（headCall，與 W4-1 同機制）

- 掛點：`ServerMap.preupdate()V` 頭部插入 `ALOAD 0; INVOKESTATIC
  MainLoopWatchdog.tick(Lzombie/network/ServerMap;)V`（`expectedHits = 1`）。
- 掛點證據（javap 對 42.20.3 jar）：`GameServer.main` 內 `invokevirtual
  ServerMap.preupdate:()V` **全方法恰 1 處**（offset 3466，經 `ServerMap.instance`），且位於主 while 迴圈內＝每幀恰一次；W6 事故 stack（`GameServer.main:972 →
  ServerMap.preupdate`）為執行期佐證。頭部插入＝本刀在 vanilla 任何一行執行前先記時間戳，凍結不論發生在 preupdate 內或主迴圈其他位置，都表現為時間戳停止推進。
- helper `zombie/mdc/MainLoopWatchdog`：
  - `tick()` 熱路徑＝一次 `System.nanoTime()` volatile write＋幀計數遞增＋一次
    started 檢查（每幀一次、10Hz，可忽略；零配置）；首次 tick 在主執行緒上 lazy
    啟動 daemon 輪詢執行緒並印首次生效 banner。
  - daemon 每 1s 輪詢幀齡。幀齡 ≥ 門檻（預設 5000ms，`-Dmdc.mainLoopWatchdogThresholdMs`
    clamp 1000..600000）→ 判定凍結：立刻對主執行緒 `getStackTrace()` 拍第一張快照，之後每 10s 補拍、單次凍結上限 12 張（覆蓋 ~2 分鐘；216s 級事件拍好拍滿）。
  - 快照行帶 `Thread.getState()` 與 heap used/max——三假說的分流指紋：RUNNABLE＋動物/AI frame＝重活；RUNNABLE＋分配點 frame＝allocation stall；stack 淺/空或
    native frame＝JNI/malloc 側；BLOCKED/WAITING＝鎖或 park。
  - 恢復時印總時長、期間 tick 推進數（**0＝完全凍結；>0＝每幀超過門檻的慢幀連發**，兩者機制不同）與累計 `stalls/dumps/maxStallMs/anomalies`。
  - 正常運轉零輸出（不成為新噪音源，符合 log 入列門檻精神）。
- 門檻取 5s 的理由：當天的慢性頓挫是 2–3 秒級且每 10–20 幀一次——門檻低於它會讓快照自己變成頓挫風暴中的額外負擔；5s 起跳只抓「玩家一定有感」的事件。

### 語意邊界（刻意不做的事）

- **純觀測**：不中斷、不恢復、不 kill 任何東西——凍結的處置仍歸 vanilla／運維。
- 只拍主執行緒（`getStackTrace()`），不用 `Thread.getAllStackTraces()`——全執行緒快照貴一個量級，且主執行緒 stack 已足以裁決三假說；SmokeCheck 把這條釘死。
- 不偵測 W6 型活鎖（主迴圈仍在轉、每圈拋例外跳過工作——tick 照常推進不觸發）；那一族已由 ChunkLoadGuard 治本，且屬「tick 有推進」的另一種形狀。

### 守門（SmokeCheck）

1. vanilla 前提：`GameServer.main` 內 `ServerMap.preupdate` 恰 1 處——「幀齡」語意建立在「每圈恰一次」上，TIS 改成多處呼叫或移除時建置紅、重選掛點而非默默失真。
2. 手術後：preupdate 頭部 headCall 全序（`aload_0 → tick` 恰一次）＋真指令數恰 +2（原體未動）。
3. helper 契約：`tick` 恰 1 次 `nanoTime`、零快照呼叫；快照走單執行緒 `getStackTrace`
   恰 1 處、全 class 零 `getAllStackTraces`。

kill switch：`-Dmdc.mainLoopWatchdog=0`（tick 早退、執行緒不啟動）。

**生效後觀測**：開機 banner `[MinidoracatJavaPatch][MainLoopWatchdog] 首次生效
threshold=5000ms …`；凍結事件時 `主迴圈已凍結 <ms>（快照 n/12）ticks=… state=…
heapUsedMB=…` ＋逐行 stack；恢復時 `凍結結束 observedMs≈… ticksDuringStall=…`。`anomalies` 必須恆 0。快照的 stack 直接餵回 216s 三假說裁決；若長期零凍結事件，本刀就是零成本保險絲，不撤。

<a id="2ad"></a>
## 2ad. 動物卸載接手守衛（W16，server，observe）

> **退役（2026-09-02）**：本刀（APM `removeChunkFromWorld` headCall＋4 redirect、`virtualizeAnimal`、Worker `addAnimal`／`saveRealAnimals`／`moveAnimal`、Main `saveRealAnimals`、`AnimalZones.spawnAnimalsOnZone`、`IsoChunk.removeFromWorld`
> 2 redirect＋TailCall）已移除。8 天全零遺失（s2Missed／queueFailures／sourceGap／cellNullAdd／chunkNullAdd／duplicateRemoved／cellNullSave 全 0；clearShortfall 1–4 但
> handedOff=scanSeen 故非遺失）⇒ vanilla 卸載接手鏈無辜、觀測結論已達；其 heartbeat
> 每 256 unload 一行佔正式服 log 7.3%（5274/71806 行）。復活方式：從退役前最後一版 13650e1 取回（`git checkout 13650e1 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

### 立案（2026-08-24～25，全服流失定罪）

正式服 39 小時全服 apop 統計：母雞 206→123（−40%）、火雞 20→7（−65%）；三個單點案例互相獨立：Player-H 雞舍全滅、Player-A 公牛＋15 雞＋2 小雞同窗消失、同牧區兔群
16→3。8/23 06:09–13:13 故障窗每輪 world save 都正常結尾、無 crash；W13/W14 部署前已在發生——**100% vanilla 持久化缺陷，不是本專案造成**。完整時序與 apop 基線指令在
internal-analysis：

- `reports/incidents/2026-08-24-Player-H-雞舍雞消失.md`
- `reports/incidents/2026-08-25-全服放養動物流失-公牛兔群與鹿瞬移.md`

兩個持久化域要分開：hutch 內動物寫 map chunk；世界動物寫 apop。世界動物平時只有
`AnimalChunk.animals` 的虛擬群體常駐，實體動物只在 world save 瞬間由
`AnimalManagerMain.saveRealAnimals` 掃 `IsoCell.objectList` 包成一次性 wrapper。chunk unload
則靠 `IsoChunk.removeFromWorld` 先呼 `AnimalPopulationManager.removeChunkFromWorld` 接手，再進清場迴圈對 movingObjects 呼 `removeFromWorld`。正式服流失落在這條接手／落地鏈。

### vanilla 靜默失敗點（javap 對 42.20.3 jar）

1. **S1**：`AnimalManagerWorker.addAnimal` offset 15 的
   `getCellFromSquarePos(II)` 回 null → offset 23 直接 return；整個 VirtualAnimal 靜默丟。
2. **S1b**：同方法 cell 命中後，offset 44 的
   `AnimalCell.getOrCreateChunkFromSquarePos(II)` 回 null → offset 52 第二個靜默 return。`cellNullAdd==0` **不足以**排除 addAnimal 入口流失，兩點必須同時量（review-lane-grok 審查抓到的觀測缺口）。
3. **S2**：`AnimalPopulationManager.removeChunkFromWorld` 掃 loaded square 的
   movingObjects；square null 或集合在前置步驟被變動 ⇒ 動物掃不到、沒有接手。
4. **S3**：家畜 `virtualId` 恆 0.0；`addAnimal` 的防重合併分支對同 AnimalChunk 的 id=0
   wrapper 做 `findAnimalById`，誤判時 `remove(j--)` 丟棄（只有 `DebugType.Animal.error`，正式服 channel 未必開）。
5. **S4**：`AnimalManagerWorker.removeFromWorld(IsoAnimal)` **零 addAnimal 呼叫**——只讀
   lastCellSavedTo、載入該 cell、設 `dataChanged=true`，反而加速下一次 save 把已不存在的動物從 apop 抹掉。SmokeCheck 把零呼叫釘成結構事實；TIS 若補接手，該條建置紅，提醒**撤 W16**而非疊兩份修正。
6. save 同族：`AnimalManagerWorker.saveRealAnimals` offset 39 的
   `getCellFromSquarePos` 回 null → 逐隻 skip，本輪不落地且無 log。

### 手術（本版只 observe；enforce 等數據選刀）

`-Dmdc.animalPersistGuard` 三態：`2`／未設＝observe（預設）、`0`＝off（headCall 早退、redirect 純委派）、`1` 保留給階段二。**本版即使設 1 也只 observe**——沒有先猜著修；24–48h 數據回來按設計 `docs/animal-persistence-guard-design-v1.md` §2.2 決策表選刀。

| # | 方法與 javap 錨 | 手術／觀測 |
|---|---|---|
| O1 | `AnimalPopulationManager.removeChunkFromWorld` 頭部 | headCall 開 ThreadLocal per-wave 帳、`unloads++`；不配置新 Wave（每執行緒重用） |
| O1a | 同方法 `n_unloadChunk(II)` offset 25 | probe 委派成功後才 `nanoTime` 起點；scanNs 不含 native unload／heartbeat |
| O1c | 同方法 `IsoAnimal.unloaded()` offset 133 | redirect：**委派前** `scanSeen++`，故 unloaded 拋錯不會假成 S2 |
| O1d | 同方法 `n_addAnimal(IsoAnimal)` offset 139 | probe：委派成功返回後才 `handedOff++`；拋錯不假記成功 |
| O1b | 同方法尾部 `TIntHashSet.remove(I)Z` offset 195 | redirect：委派前截 end、成功後標 scanCompleted 並結算 scanNs |
| O2/O2b | `Worker.addAnimal` cell offset 15／chunk offset 44 | probe：`attempts/cellNullAdd/chunkNullAdd`（S1/S1b） |
| O2c | 同方法兩個 `ArrayList.remove(int)` offset 132/174 | redirect 純委派＋`duplicateRemoved++`（S3 實際丟棄，不再只靠 debug channel） |
| O4b/O4 | Worker.saveRealAnimals cell offset 39／Main.saveRealAnimals getObjectList offset 14 | `cellNullSave`＋world-save 實體動物分母 |
| O3 | `IsoChunk.removeFromWorld` 清場 offset 332/507 | server＋IsoAnimal 才記 wave.cleared，最後原樣委派 |
| O3b | 同方法唯一 RETURN 前 | 新 `TailCall`（純線性 `aload0; invokestatic`）分 wave 結帳 |
| 來源帳 | APM.virtualizeAnimal／AnimalZones.spawnAnimalsOnZone／Worker.moveAnimal | 各 redirect package-private add 邊界，成功返回後記 `virtualized/zoneAdds/movedAdds` |

package-private probe 留在 `zombie.characters.animals.MdcAnimalPersistProbe`，不走 reflection／MethodHandles。HeadCall/TailCall 各只加 2 條線性指令、無新 branch/frame；其餘全為 1:1 redirect。

### 計數語意（部署判讀不可讀錯）

接手後動物仍留在 movingObjects，故 `droppedAtClear` 本身是健康常態。**已廢止**
`droppedAtClear-handedOff` 全域淨差：unloaded/n_add/清場例外會製造假正差、假零或負差，不同 wave 還會互相抵銷。現在只在 IsoChunk 真出口對**完整 wave**分類：

- `s2Missed += max(cleared-scanSeen,0)`：清場看見、掃描沒看見；S2 直接證據；
- `clearShortfall += max(scanSeen-cleared,0)`：掃描看見、清場未完成；不與 S2 抵銷；
- `queueFailures += max(scanSeen-handedOff,0)`：掃描看見但 n_add 未正常返回；
- `abortedWaves`：APM 掃描未到尾但 IsoChunk catch 後到達 tail；`unpairedWaves`：連 IsoChunk
  tail 都沒到，下一個 enter 才發現舊帳；`skippedWaves`：前置子系統在 APM 前就失敗。

`attempts` 是所有 Worker.addAnimal 入口。完整來源式：`sourceGap = attempts - handedOff - virtualized - zoneAdds - movedAdds`。SmokeCheck 三層 census：APM.n_add 只來自 remove/virtualize、Main.add 只來自 n_add/zone、Worker.add 只來自 Main/move，各 jar-wide 恰 2且分佈各 1+1；`sourceGap` 應恆 0。

每 256 unload-end 或 world save-start 一行 heartbeat：`completed/aborted/unpaired/skipped/scanSeen/handedOff/droppedAtClear/s2Missed/
clearShortfall/queueFailures/attempts/virtualized/zoneAdds/movedAdds/sourceGap/cellNullAdd/
chunkNullAdd/duplicateRemoved/cellNullSave/lastSaveReal/scanAvgUs/scanMaxUs/anomalies/mode`。save-start 的 `cellNullSave` 是累積到上一個完成 save 的值，不作同一行單-wave 對帳。

### 守門與行為測試

- APM 全序 `n_unload < unloaded < n_add < remove`；IsoChunk 接手1／清場2／唯一 RETURN；APM.remove jar-wide caller 恰1；S4 `Worker.removeFromWorld` 零 addAnimal。
- 三層 source census＋分佈、S1/S1b/S3/O4b 逐方法恰 N。patched 原呼叫歸零；redirect 真指令不變，HeadCall/TailCall各 +2。
- probe 八 wrapper 各委派恰1；clearMoving 零 NEW／DebugLog。
- `AnimalPersistGuardTest` 三獨立 JVM（observe、mode1 observe-alias、off）：正常 wave、`clearShortfall` 負差緊接 `s2Missed` 正差（證明不互抵）、queueFailures、unpaired、aborted、sourceGap=0、S3 remove、cell/chunk/save 正負 passthrough、O3 過濾、off 純委派。變異刪 O3 instanceof 必紅。

### 階段二決策（本版不做）

- `cellNullAdd/chunkNullAdd` 分別處理 S1/S1b；不可混刀。
- **完整 wave** `s2Missed>0` 才設計 O3 補接手；不得呼內含 delete 的 public
  `virtualizeAnimal`。`droppedAtClear>0` 本身絕不是觸發條件。
- `queueFailures/aborted/unpaired/sourceGap` 非零先查例外與上游來源，不得當 S2。
- `duplicateRemoved>0`＝S3 已直接發生，按 animalID/virtualId 明細設計。
- 全綠仍丟：S4 outcome、devirtualize fromWorker 或 apop 載入側。

明確不做：鹿瞬移（`fromWorker` 對 id=0 setForceX/Y）、PathfindNativeThread native crash、阻止 chunk unload、任何序列化格式改動。W15 掛點與 property 零重疊。

<a id="2ae"></a>
## 2ae. hutch 載入回傳守衛（W17，server，預設 enforce）

### 缺陷（靜態已定罪）

`IsoHutch.load(ByteBuffer,int,boolean)` 逐隻反序列化動物後，offset 526 呼
`addAnimalInside(animal,false)`，下一條真指令 offset 529 是 `POP`——**完全忽略 boolean
回傳**。`addAnimalInside` 的位置選擇：

1. preferred=-1 時 `Rand.Next(0,getMaxAnimals())`；
2. 槽被 animalInside／deadBodiesInside／nestBox 佔用就重骰，>100 次跳出；
3. 最終落位 offset 148-151 **只查 animalInside**：佔用 → false；空 → put。

false 時該動物已從 blob new/load 完、又 `removeFromSquare`，卻不進 hutch map、不進世界、無 log，最終由 GC 回收＝**載入即滅失**。接近滿舍（vanilla maxAnimals=20）或屍體/nestBox
擠掉有效槽時最容易觸發；兔子爆量案例與此形完全一致。

### 手術

`IsoHutch.load` 內雙參 `addAnimalInside` callsite 恰 1（單參多載在 update，descriptor 不同）redirect → `HutchLoadGuard.addInside(hutch,animal,sendEvent)`：

1. 先原樣委派 `hutch.addAnimalInside` 恰 1 次；成功或 mode=off 直接回原值。
2. false 且 `animalInside.containsValue(animal)`：重複 add（vanilla 已 warn），不救，避免雙槽同體。
3. 零 Rand 順序掃 0..max−1：第一輪找 animalInside＋deadBodiesInside 都空的乾淨槽；無則第二輪只找 animalInside 空槽。判空刻意用 `map.get(key)==null` 而非 containsKey：public map 若有 `key→null`，vanilla 視為空槽，helper 亦同。private
   `checkNestBoxPrefPosition` 無法直呼，但 vanilla 最終落位本來也不查。
4. enforce 有槽：補齊 vanilla 成功狀態的六步不變式：`animalInside.put(slot,animal)`、`animal.hutch=hutch`、`setPreferredHutchPosition(slot)`、`setHutchPosition(slot)`、`setItemID(0)`、`tryRemoveAnimalFromWorld(animal)`，回 true。preferred 不能漏：vanilla 成功時
   preferred 就是最後落位 key；漏補會讓後續進出籠／重骰看到陳舊位置（review-lane-grok 審查修正）。
5. 真滿（兩輪無槽）：`CRITICAL` log 動物 type/id＋hutch 座標，回 false；至少把靜默滅失變成可補償的有聲事件。helper 不創造第 21 個容量。
6. observe 有槽：印 wouldForce、回 false（不改行為）；救援段自身 RuntimeException／LinkageError → `anomalies++` 並退回 vanilla false。**原委派不包 try**，vanilla 拋什麼照拋。

`tryRemoveAnimalFromWorld` javap body 是 client-only（`GameClient.client && animal!=null &&
isExistInTheWorld` 才 remove）；server 上 no-op，照呼只是保持與 vanilla 成功路徑同構。load 的 `sendEvent=false` 本來就不走 sync 分支。

三態：`-Dmdc.hutchLoadGuard=1`／未設 enforce（預設）、`2` observe（只記不救）、`0` off（純委派）。client 安全：worldVersion≥212 的 client load 在 offset 191-209 直接
skip 動物 blob，迴圈不執行；且 loose class 只部署 server，無 server-only 判定 desync 面。

### 守門與行為測試

- vanilla load callsite 精確全序：`ALOAD0 → ALOAD7 → ICONST0 → addAnimalInside → POP`；TIS 若開始消費回傳或把 sendEvent 改 true，建置即紅。
- vanilla `addAnimalInside` 成功契約：42.20.3 恰 105 真指令；Rand×2、map put、hutch
  PUTFIELD、preferred×2、hutchPosition、itemID、tryRemove 的數量與順序全鎖。
- 手術後同一實參形狀只換 static helper、原 call 歸零、真指令不變、class-wide 差1。
- helper 原委派恰1、全 class 零 Rand、forceInto 六步各恰1且 backlink 是精確 PUTFIELD。
- `HutchLoadGuardTest` 三獨立 JVM，以 ZeroRandom 確定製造「有空 slot1 但 vanilla 101 次全撞 slot0」；另驗 clean-slot 優先、dead-body fallback、key→null、duplicate、20 隻全存活與第21隻 CRITICAL。變異拿掉 map put 必紅。


---

<a id="2af"></a>
## 2af. 動物 LOS 節流閘（W18，server，預設 observe）

### 立案（2026-08-25 晚峰黑邊診斷的副產品）

67 人破歷史峰值 → 主執行緒單核飽和（99.9%R）→ fps 9.8→5.0 → 吞吐型黑邊。60 張 jcmd
stack（22:45-22:48、66 人）：**`IsoAnimal.updateLOS` 單一 leaf 25/60=41.7% 主執行緒**、LOS 家族合計 46.7%、無第二個 >5% 熱點；另一批 8/17 40 人層 18.3%（docs/isoanimal-updatelos-design-v1.md §1，該檔並有 server-only 七呼叫點表與 Lua 可達性分析，本刀直接引用）。結構（javap 對 42.20.3 jar）：每隻實體動物每 tick 掃
`getCell().getObjectList()` 全表（`Set`），迴圈唯一有效輸出＝對 zombie/player 呼
`behavior.spotted()`＋`spottedList={this}`；動物/載具/屍體/物理物件全被 instanceof 丟棄。放大係數 ~769 動物 × 全表數千項 × 10Hz。

### 手術

caller 側單 redirect：`IsoAnimal.updateInternal()V` 內唯一
`invokevirtual updateLOS:()V`（offset 197）→
`invokestatic zombie/mdc/AnimalLosGate.updateLOS(IsoAnimal)V`（1:1 同形，expectedHits=1）。`updateLOS` 本體不動（Lua/mod 直呼路徑照舊；W3-3 的兩處 prefilter redirect 保留＝防禦深度）。

enforce 幀輪轉：`floorMod((long)(identityHashCode(animal)*0x9E3779B9 >>> 16) + frame, N) == 0`
才轉呼叫（`floorMod(long,int)` 全程 long 無截斷），幀源＝vanilla
`MovingObjectUpdateScheduler.instance.getFrameCounter()`（`startFrame()` 每 tick +1）。**grok 對抗審查 BLOCKING 修正記錄**：v1 草案用 nanoTime 牆鐘窗口——單點抽樣在
tick=k×window 且 gcd(k,N)>1 時整個剩餘類永久 skip（fps5、N=4 ⇒ 半數動物視覺失明），恰在本刀要救的低 fps 情境發作；改用幀源後 Δframe=1 ⇒ gcd(1,N)=1、CPU 砍幅恆 (N-1)/N。**但 Δframe=1 是條件性事實，不是數學免疫**（三 lane review B1）：它成立於「server ⇒ `getUpdateSchedulerSimulationLevelForObject` 恆回 FULL ⇒ frameMod=1 ⇒
bucket 每 tick 全跑」這條 42.20.3 前提鏈——vanilla 的 bucket 本身就在做
`buckets[frame % frameMod]` 幀輪轉（`getID() % frameMod` 分子桶），TIS 若在 server 開
LOD 分級，動物被 update 的幀集合變 `frame ≡ getID() (mod frameMod)`，與 N 有公因數 ⇒
剩餘類永久失明。防護雙保險：SmokeCheck 承重前提釘（建置紅）＋helper `gateApplies()`
runtime fail-open（`getCurrentSimulationLevel().getFrameMod() != 1` 直接 forward、計
`lodPassthrough`——寧可失去節流也不失明）。mix 防 `-XX:hashCode` 切換與低位聚集。

行為代價（誠實語意——速率非單次延遲）：`spotted()` 是速率型副效應，skip ⇒ 速率 ×1/N。受影響：玩家/殭屍近距壓力累積、馴養 `playerAcceptanceList` 累加（dist<10 分支）、野生警戒與偷襲 XP 機會、`attackIfStressed` 起手機率、`lastAlerted` 衰減；另沿 W3-3 已接受結論承擔全域 Rand 序列位移（N 上調時重秤）。首次偵測延遲 ≤(N-1) tick。**故預設 N=2
保守出貨**（速率減半、延遲 ≤1 tick），體感驗證後 property 上調。`fleeFromChr` 依賴的
`spottedChr` 在 skip 期間保留殘值＝逃跑黏性反而更高。skip 時 `spottedList` 保持 `{this}`（動物版恆此值，零 server 消費者；Lua 讀取者看到與 vanilla 重建後相同值）。聽覺
`respondToSound` 不經 LOS 不受影響。

例外語意：主 try 只 catch `RuntimeException`（簿記 fail-open、anomalies++ 後照常轉呼叫）；**`LinkageError` 一律外逃＝fail-fast**（新 jar＋舊 loose class 的二進位不相容必須炸得可見，比照 ChunkRequestPacker rethrow 與 8/17 NoSuchFieldError 事故處置；review B2——否則
enforce 下 `getFrameCounter` 消失會變成每呼叫吞錯的完全靜默降級，heartbeat 在拋出點下游永遠印不出來）。vanilla 委派在 try 外原樣上拋；`maybeBeat()` 在簿記完成後執行、內部自包
RuntimeException（log 故障不外逃、不擋主流程、不再讓 forward 被記成 skip）。

三態：`-Dmdc.animalLosGate=2|observe`（預設，量 objectList.size 分布＋每 64 次 forward
夾測單次耗時；未知值落回 observe）／`1|enforce`／`0|off`——**parseMode() 文字別名比照家族四把三態刀**（review I2：數值 clamp 會把 `=off` 靜默變 observe、`=-1` 靜默變 off）；`-Dmdc.animalLosN`（clamp 1..16，預設 2）。heartbeat 每 4096 呼叫才讀時鐘、60s 節流（熱路徑不無條件讀 nanoTime，比照 AnimalRelevancyGate 慣例）。

### 守門與行為測試

- vanilla 前提：updateInternal 掛點恰 1、updateLOS 內 `getObjectList():Set` 恰 1＋零
  `lastSpotted` 引用（TIS 下放玩家尾段＝skip 不再零差，紅則撤刀重估）。
- 完備性回歸釘（七呼叫點表 #2）：`IsoPlayer.updateInternal1` 的 isAnimal 短路仍在（isAnimal 恰 1＋`IsoLivingCharacter.update` 恰 2＋玩家版 updateLOS 恰 1）——TIS 拆分流＝動物流入未節流的玩家版 updateLOS，紅則重估。
- helper：委派恰 2（off 直通＋主路徑 try/finally 夾測合一）、`getFrameCounter` 恰 1（幀源存在性）、`getCurrentSimulationLevel`/`getFrameMod` 各恰 1（fail-open 存在性）、主方法熱路徑零 NEW、全 class 零 Rand、具名 exception handler 只允許 RuntimeException（LinkageError 穿透；finally any-handler 允許）。
- **承重前提釘（review B1，五支）**：server⇒FULL 短路（`GameServer.server` GETSTATIC 恰 1＋FULL ≥2）、`getFrameMod` 真指令恰 5（`1<<idx` 全形狀）、`startFrame` LCONST_1/LADD 各恰 1、`bucket.add` 的 `getID()` 恰 1＋IREM 恰 1、`MOUS.update()` 每幀全桶掃描形狀（bucket.update
  恰 1＋simulationLevels/frameCounter GETFIELD 各恰 1——堵「隔幀呼叫 bucket 而 frameMod 仍 1」的雙保險共同盲區）。任一紅＝TIS 動排程結構，重驗 gcd 面。
- **client 支配釘（review I3）**：updateInternal 內 `GameClient.client` GETSTATIC 恰 1 且位於 callsite 前（server-only enforce 的 desync 防線，2n 教訓）。
- `AnimalLosGateTest` 七組態獨立 JVM（off 別名／observe／enforce N=4、N=2 出貨、clamp
  0→1、999→16／未知值 bogus→observe；MODE 與 N 皆自驗）：off 計數凍結／observe 對帳＋size 採樣兩分支（反射注入 objectList 的非 null 成功路徑精確對帳＋null cell 安全跳過）＋錯誤契約（簿記
  RuntimeException fail-open 恰一次委派、vanilla RuntimeException/Error sentinel 原樣外逃不計 anomalies）／enforce 四軌斷言——逐 (animal,frame) 公式 oracle（mutation 主力殺手）、同幀重複一致（輔助訊號）、4N 幀內每動物恰 1/N forward 幀（輪轉硬保證＝無失明）、相位分散（mix 退化成常數 ⇒ 全體同幀 ⇒ 紅）＋LOD fail-open（frameMod>1 恆 forward 計
  lodPassthrough、frameMod==1 照常輪轉）。
- mutation 6/6 全殺：恆 forward／判定反轉／`+`改`^`／改回牆鐘／拿掉 mix 五隻殺因「逐幀公式不符」＋拿掉 gateApplies fail-open 一隻由 LOD 段「N 幀內全 forward」殺。

### 部署與觀測

- observe 先行一晚：`sizeAvg/sizeMin/sizeMax`（objectList 組成，決定要不要第二刀清單替換）、`losAvgUs×forwarded` 對帳 41.7% 採樣佔比。
- 切 enforce（property 重啟）後驗收：晚峰 fps 對照（N=2 預期還回 ~20% 主執行緒——推估值，以 observe 實測回填）＋行為面抽查（殭屍咬雞/逃跑、馴養靠近速度、偷襲 XP、高壓動物起手）；AnimalSpottedPrefilter 計數下降屬預期；**`lodPassthrough` 應恆 0**（非 0＝TIS
  已開 LOD、fail-open 生效中、節流面縮小）。N 上調前重驗速率代價＋Rand 位移＋排程結構。
- **W18-2 AnimalLosScan 已於 2026-08-29 落地（預設 observe）**：W18 Gate 已佔用
  `updateInternal` 唯一 callsite，故不再新增 bytecode 手術；改為 Gate forward 路徑
  `invokestatic AnimalLosScan.updateLOS`（Gate off 仍直通、不經 Scan，kill switch 分層）。observe＝純 timing wrapper（`calls/elapsedNs/sumObjects`）量 Gate enforce 後的真實殘餘；on 才啟用保守裕度平方預篩（`d² > (threshold+0.25)²`）消除遠距 pair 的 sqrt/
  `tryCastTo`/prefilter 白繳；**threshold 每個 zombie/player candidate pair live 讀**（W3-3 must-keep：前一 pair 的 mod behavior 可動態改 spottingDist；讀取異常該 pair
  全額 delegate，禁止沿用舊 gate）。邊界帶與近距全額 delegate W3-3，與現行行為
  bit-exact（含 RNG）。三態 `-Dmdc.animalLosScan=0|off/1|on/2|observe`（預設 observe）；on 等 observe `ms/s` 與 jstack 佔比互驗後才離峰 canary（加速比 ≤1.1× 即撤）。review 修正：Gate banner 在 sample t0 前完成；delegated 只計實際 prefilter 呼叫。SmokeCheck 鎖 42.20.4 本體/caller census/live-threshold/GameTime前綴；行為測試鎖
  off/observe/on、遠距 fast skip、lastAlerted 負值 clamp、近距 delegate、12.2 邊界帶、隱形玩家、null fallback、**pair 中途 spottingDist 10→100 後下一 pair 必須 delegate**。**2026-08-29 晚峰 observe 判定（80+ 人破峰值）**：Δbeat 差分 580 calls/幀 ×
  avgUs=55（累積均值，瞬時更高）≈ 31.9ms/幀；主迴圈 3.2fps（幀長 ~312ms）⇒
  **殘餘 ≈10–13% ≥ 8% 門檻，on canary 解封條件成立**（objAvg 2035→峰值更大、sizeMax=2611；離峰 19 時僅 6.7%——佔比隨人數/動物/objectList 線性）。scanned/fastSkipped/delegated 恆 0（observe 不走 fast path，語意正確）、fallbacks=0、anomalies=0、Gate forwarded ≈ Scan calls 對帳成立、Gate 與 Scan
  avgUs 一致（wrapper 開銷不可見）。**canary 已排入：8/29 20:5x 將
  `-Dmdc.animalLosScan=on` 寫入兩份 JVM json（serverfiles＋canonical，bak
  `.bak-20260829-losscan`），下次重啟自動生效、為該次重啟唯一變更**；驗收＝on 實測
  avgUs ÷ observe 基線（離峰 25／晚峰 55，以 objAvg 校正規模差），加速比 ≤1.1× 即改回 observe 撤刀。scanned>0、fastSkipped≫delegated、fallbacks/anomalies=0 為健康指紋；AnimalSpotted（W3-3）skipped 增速驟降屬預期（歸屬轉移到 fastSkipped）。**2026-09-02 on canary 驗收（8/30 00:12 起 on，對照 8/29 20-14 observe 基線）**：on 期 `avgUs` 35–43 @ `objAvg` 2809–3119（9/1 21-21 晚峰 40@3076、9/2 00-41 離峰
  43@3119、9/1 18-26 晚峰 35@2809）vs observe 基線 53–55 @ 3298 ⇒ objAvg 線性校正後**加速比 ≈1.25×（> 1.1× 門檻），保留 on**；`fastSkipped` 26.2G／`delegated` 9.2M（pair 級 99.96% 走 fast path）、`fallbacks=0`、`anomalies=0`、Gate `forwarded` ≈ Scan
  `calls` 對帳成立。**但 pair 級 99.96% 只換來 ~25%**＝迴圈本體（`Set` 迭代＋instanceof＋距離平方，≈35ns/pair × ~1136 pair/call）仍有固定成本。當時判為下一步須換資料來源；2026-09-11 覆核發現非目標的純取值／計算仍可再提前排除，先採下方較小修改。

### 2026-09-11：非目標種類提前排除

只把 `AnimalLosScan.updateLOS` 原有的「非殭屍、非玩家」排除移到 self 分支之後、XYZ／高度／距離平方／所在格讀取之前。`IsoAnimal` 繼承 `IsoPlayer`，所以排除其他動物的條件不可刪；null 則仍落到原 `getX()` 拋 NPE，不被新 `instanceof` 判定靜默略過。原版相關 getter 為純欄位讀取；不承諾涵蓋任意第三方 Java 子類在 getter 中加入的副作用。

physics／vehicle／grapple-only／self 順序不動；有效目標走訪順序、每 pair 即時
`spottingDist`、`lastAlerted` 前綴與既有 Rand 路徑不動。Gate 與 Scan 模式不改，不提高 N、不削減視力或聽覺，也沒有新增快取、執行緒、旋鈕或 bytecode 掛點。**仍是原 Set 全掃**；省掉的是無效候選的逐物件工作，不宣稱已實作專用候選清單。

驗證沿用 off／observe／on 三模式，補混合物件與 self 的有效結果原序、前一個目標修改視距後下一個目標仍即時可見，以及 null 前綴保留／後綴中斷。相同行為案例在舊 helper
與新 helper 均通過；不為了量速度斷言某個 getter 應被呼叫幾次。

本機拋棄式 A/B 以同 JVM 兩個隔離 helper、同一真 `HashSet` 的 4,800 個物件，五個 JVM 樣本、各 11 輪交替量測；每側暖機 10,000 次，先排除首次 heartbeat。每輪在計時外對帳 fast/delegate/fallback/anomaly／原版委派與 consumer 效果，避免把錯走 fallback 或漏做有效工作量成加速。非目標占 25%／50%／75% 的合成負載分別約
**1.4–1.5×／1.7–1.8×／2.7–2.9×**；全為有效目標時近乎持平。兩側每次掃描配置量同為
32 bytes（原 Set iterator），未新增逐物件配置。

這是固定物件清單的**本機合成負載結果，不是正式服 FPS 或視線耗時的改善比例**。正式部署仍須獨立切換，再用既有 Scan timing、相近 objectList 規模與負載驗收；`fallbacks/anomalies` 應為 0，N 與有效判定保持不變。不因本機加速就混入候選快取方案。

---

<a id="2ag"></a>
## 2ag. 車輛永久移除授權守衛（W19，server，預設 observe；本版純觀測）

### 立案（2026-08-23 Player-F 案＋2026-08-28 三方核實）

正式服 8/23 三輛未認領完好車（`Trailer_Livestock` id=462、`StepVan` id=468、`SmallCar`
id=173）被玩家以噴燈拆解永久刪除——`vehicles.db` 整列 DELETE，MVCK 認領車倖存（internal-analysis `reports/incidents/2026-08-23-Player-F-拖車被拆.md`）。源碼定罪（42.20.4 逐項實查＋codex/grok 雙 lane 獨立復核）：

- vanilla `VehicleCommands.lua:359-366` 的 `Commands.remove` 直呼
  `vehicle:permanentlyRemove()` 且**無任何權限檢查**（同檔 `repairPart:345-357` 有
  `checkPermissions(player, Capability.UseMechanicsCheat)`，`remove` 沒有）；dispatcher
  `:457-467` 只驗 `module=='vehicle'`。
- Java 側 `GameServer.receiveClientCommand`（:2289-2297 反編譯）對 vehicle/remove 有一道**形式閘**，但其中 `NetworkPlayerAI.isDismantleAllowed()`（NetworkPlayerAI.java:647-649）**恆回 true**＝實質全放行——TIS 自留的 hook 點從未實作。
- **Player-F 案實路不經 Commands.remove**：`ISRemoveBurntVehicle.lua:135` 是 shared timed
  action 的 `complete()`，在 server 端 Lua 直呼 `permanentlyRemove()`（Nep Dismantle Any
  Car 只改 client 選單開放完好車，server 跑的 complete 是 vanilla 的；其 `isValid` 只驗噴燈不驗燒毀）。⇒ Lua 層只蓋 `Commands.remove` 攔不到本案；換裝任何拆車 MOD（VSO 等）也都委派到同一 vanilla 能力。**唯一交匯點＝Java 咽喉 `permanentlyRemove` 本身。**

### 咽喉 caller census（SmokeCheck 全 jar 釘死＝4）

| callsite | 語境 | server 可達 |
|---|---|---|
| `LuaManager$GlobalObject.removeVehicle` | `!GameServer.server` 分支（javap offset 44-54） | 否（死路徑，守衛被 SmokeCheck 釘住） |
| `RandomizedWorldBase`（:442 反編譯） | 世界事件清理 | 是（合法維運） |
| `VehicleManager.removeVehicles`（:50） | admin `/remove vehicles` 批次 | 是（合法維運） |
| `BaseVehicle.setSmashed`（:10442） | 換殼重建（先刪舊車再 new） | 是（合法維運） |

Lua 端另有三個入口全走上述咽喉：`Commands.remove`（client command——admin 機械面板
`onCheatRemove` 與任意玩家偽造/委派 command **同路**）、`ISRemoveBurntVehicle`（timed
action）、其他 MOD server Lua 直呼。

### 手術（headCall，純觀測）

`BaseVehicle.permanentlyRemove()V` 頭部 headCall → `zombie/mdc/VehicleRemoveGuard.onRemove
(BaseVehicle)V`（`ALOAD 0 → INVOKESTATIC`，與 W15 preupdate 同機制，真指令 +2、expectedHits=1；掛在既存 W3-4 BaseVehicle ClassPatch 上——同 class 不得開第二個
ClassPatch，否則後者從 vanilla 重讀覆蓋前者的手術）。vanilla 頭部形狀 `iconst_0;
istore_1` 起、單一尾部 RETURN（javap 42.20.4 offset 0-88）。

每刪除一行 log：`remove#seq vid script pos claim caller lua nearest near`。

- **caller 分類**：`Thread.currentThread().getStackTrace()` 取第一個非 mdc/非咽喉自身
  frame（Java 維運 caller 直接可辨）＋全 stack 掃 `se.krka.kahlua.`/`zombie.Lua.` 前綴（＝Lua 驅動）。低頻刀（日常刪車每日數十次量級）成本可忽略。
- **MVCK 認領狀態（六路，來源＝MVCK 42.15 源碼實證）**：車輛 modData 的 `SQLID` 只是
  imprint 印記——**`unclaimVehicle` 不清印記**（MVCKServer.lua:120-147），SQLID 存在≠
  仍認領；owner 真相在 Global ModData 表 `MVCKByVehicleSQLID`（key=SQLID →
  `OwnerPlayerID`，MVCKServer.lua:53/69/90）。狀態：`unclaimed`（無印記）／`stale-imprint`（有印記、表無條目＝已解除）／`claimed:<owner>`／`no-mvck-table`／`no-moddata`／`unknown-*`（讀取失敗記錄而非靜默放行）。helper 全程唯讀（SmokeCheck 釘零 rawset）。
- **近距玩家**：`GameServer.getPlayers()` 掃最近距離＋32 格內名單（cap 3）——「借位刪車」訊號。環境不可用（測試 JVM）回占位。
- rate limit：10s 窗上限 20 行完整記錄、超限 `suppressed++`（防未知高頻迴圈刷版；正常頻率遠低於此）。

### 為什麼本版不 enforce（三方審查一致）

授權判定需要 (requester, vehicle) 對，而咽喉點只有 vehicle——requester 藏在 Lua 層（client command 的 player／timed action 的 `action.character`）。三個 enforce 候選全數有致命面：只蓋 `Commands.remove`（Lua）漏 timed-action 實路；單點 ThreadLocal 橋（receiveClientCommand）橋不到 `NetTimedAction.perform`（:132-137）的第二來源；純車況規則（燒毀放行＋完好拒）**誤殺 admin 刪完好車**（admin 與惡意同走 command、Kahlua
frame 分不出人）並擋 `setSmashed` 換殼。enforce 條件（候選：admin capability OR
認領者+距離+燒毀；unclaimed burnt 是否 public 需明寫）待 observe 回答「合法刪除頻率與 caller 分佈」後另案設計；屆時身分橋需同時覆蓋 receiveClientCommand 與
NetTimedAction.perform 兩個入口（W14 ThreadLocal 捕獲＋finally 清除慣例）。

kill switch：`-Dmdc.vehicleRemoveGuard=2|observe`（預設）／`1|enforce`（**本版
observe-alias**，比照 W16）／`0|off`（純早退）；文字別名＋未知值落回 observe。

### 守門與行為測試

- SmokeCheck：全 jar `permanentlyRemove` 呼叫點恰 4 且逐類分佈釘死（總數＋分佈雙鎖堵互抵；TIS 新增 caller＝observe 分類器過時＝建置紅）；`GlobalObject.removeVehicle` 的
  `GameServer.server` 守衛存在（死路徑前提）；手術後 headCall 全序＋真指令恰 +2；helper
  契約（零 `permanentlyRemove` 遞迴、`getStackTrace` 恰 1、claim/onRemove 零 rawset）。
- `VehicleRemoveGuardTest` 三組態獨立 JVM（observe 預設／1=observe-alias／off 文字別名，MODE 自驗防 property 假綠）：caller 分類三向（Kahlua 反射鏈／Java 維運 frame／setSmashed 自呼跳自身）＋MVCK 狀態機全六路（GlobalModData 注入）＋空殼 vehicle 整段不炸（觀測刀不得擋刪車）＋rate-limit 30 連打→20 記錄/10 壓制。

### 部署與觀測（驗收）

- observe 一輪（建議 ≥7 天，涵蓋週末晚峰）後能回答：合法刪除頻率（burnt 拆解／admin
  批次／世界事件 各多少）、可疑事件（完好＋unclaimed/stale＋Lua 驅動＋近距玩家非 admin）是否存在。
- 車輛數量驗證**不用** `vehicles.db` 總列數（新車生成會抵銷刪除）：以本刀 log 的
  per-vid ledger 對照 DB 差分。
- 立即止血屬營運面（移除 Nep 或降 admin-only＋`MVCK.ServerSideChecking=true`），與本刀互補不互替（8/25 稽核建議 #5/#6）。
- **2026-09-02 收案（8/28–9/2，6 天 285 筆）**：`claim=unclaimed` 264／`stale-imprint`
  16／`claimed:<owner>` 5（**全是認領者本人**，near=[owner]）；100% `lua=true`（Kahlua
  反射鏈）、`nearest≤2` 格 267 筆＝玩家親手拆；script 分佈為一般車（SmallCar 30、CarNormal 23、SmallCar02 17…），`CarNormalBurnt` 僅 4。**六天內零「他人認領中的車被拆」**
  ⇒ 「只保護已認領車」在此窗會擋 0 筆、「完好未認領拒拆」會擋 264 筆合法玩法（含 MSW
  裝載，8/28 已知語意混雜）——enforce 沒有任何規則有數據支持。**決策：本刀收成純鑑識帳本（observe 保留、每筆 rate-limit 內記錄），不 enforce；Player-F 類事件靠帳本事後對帳＋營運規則（MVCK 認領）處理。**
---

<a id="2ah"></a>
## 2ah. 衣物同步守衛（W20，server，預設 observe；(b) 附可開的 enforce）

### 立案（2026-08-28 三方核實的三個 log 叢集）

8/28 當輪 68 分鐘（分析 repo errors.txt）：ERROR 1,589，其中
`INetworkPacket.send> Exception thrown` ×362＋NPE 指紋 ×363＋`SyncVisualsPacket.parse > Player h...` ×129——三叢集合計約 490。**362 是 per-connection 放大值**（`INetworkPacket.send` :124-133 的 try-catch 是
per-connection；`sendToRelative`/`sendToAll` 對每條 relevant connection 各
getPacket＋setData＋各自炸），且 (a)(b) 混在同一指紋，實際邏輯事件數遠小於 362、分解靠本刀計數。

**(a) ContainerID square-null NPE**：`IsoGameCharacter.addHole` →
`BloodClothingType.addHole` → `Clothing.setCondition`（condition≤0 → :817
`isWorn()&&isRemoveOnBroken()`）→ `Unwear(true)` → `GameServer.sendRemoveItemFromContainer`
→ **sendToRelative 分支**（:2452＝`container.getCharacter()` 非 IsoPlayer——玩家自身走
:2450 且 ContainerID 有 IsoPlayer 專用分支，**受害主體是殭屍/屍體等非玩家角色**）→
`ContainerID.set(ItemContainer)` :94 → 雙參 set 的 ObjectContainer/IsoObject 分支
`o.square.getObjects()`（javap offset 197/233）**無 null 守衛**。square 矛盾根源（codex lane 定位）：`Unwear` :120 用 `c.getSquare()!=null` 放行（`IsoMovingObject.getSquare()`＝`current ?: square`），ContainerID 卻直讀 raw `square`
field——IsoGameCharacter 建構只填 current。NPE 被 per-connection catch 吞掉後
`Unwear` 的 `inventory.Remove`＋`AddWorldInventoryItem` 照常執行 ⇒ client 未收到移除通知＝黏性 desync（可能「身上副本未消＋地面副本出現」的複製視覺）。

**(b) tint NPE**：`SyncClothingPacket$ItemDescription` 帶參 ctor 對 baseTexture/
textureChoice 都有 `getVisual()==null ? -1 :` 守衛（offset 39-87、IFNONNULL×2），**唯獨 tint 直呼 `getVisual().getTint()`（offset 91-101）**——vanilla 同一 ctor 自防兩行漏第三行。`getVisual()` 於 clothing asset 不存在/未 ready 時清成 null（InventoryItem 反編譯 :2320-2333）⇒ 該玩家每次 SyncClothing 廣播（IsoGameCharacter
:3470、Clothing :1012/:1061/:1111 等 sendToAll）對每條 connection 各炸一次＝**該玩家衣物同步黏性全滅**。

**(c) visuals count mismatch**：`SyncVisualsPacket.parse` :57-130 以 server 本地 player
重建 itemVisuals（:60）、讀 wire count（:61）、不符即 error＋**整包 return**。server
console 的 "Player has X ... sync Y"＝server 本地 X、client 宣稱 Y（**client 多
server 少**，8/28 樣本 14/15）。`isConsistent` :53 同判 ⇒ 不 process/forward。

### 三叢集關係（核實定案＋待 observe 證偽）

- (a)→(c) **因果不成立**：(a) 主體非 IsoPlayer，SyncVisuals 只對玩家。
- (b)(c) **強共同根因假說**：`WornItems.getItemVisuals` 跳過 null-visual item（WornItems.java :155-167），SyncClothing.set 的 lambda 只濾 item/getItem() null——同一件 null-visual worn item 同時讓 (b) ctor 炸、讓 server itemVisual count 比
  client 少 1 ⇒ (c) 的 wireMinusLocal=+1。observe 以「(b) 的 player 與 (c) 的 player
  同一人＋diff 恆 +1」定罪；若 (c) 無 null-visual 玩家或 diff 分佈雜訊化則分流。MirageWardrobe（wid 3770186452，8/17 起在服）歸因也依此，不預設成立。

### 手術（1 headCall 多 slot＋1 headCall＋4 redirect）

| 掛點 | 手術 | expectedHits |
|---|---|---|
| `ContainerID.set(ItemContainer,IsoObject)V` 頭部 | headCall slots={1,2} → `ContainerIdProbe.onSet`（**多 slot headCall 首用**：aload_1→aload_2→invokestatic，真指令 +3、峰值 2、frames 不需增補） | 1 |
| `SyncClothingPacket.set(IsoPlayer)V` 頭部 | headCall slots={1} → `ClothingSyncGuard.onClothingSet`（ThreadLocal 記組包對象） | 1 |
| `ItemDescription.<init>(WornItem)V` | redirect `ItemVisual.getTint()` → `tintOf(ItemVisual)`（1:1 同形） | 1 |
| `SyncVisualsPacket.parse` | redirect `PlayerID.getPlayer()` ×3 → `parsePlayer`（捕獲 parse 對象）＋`DebugType.error(Object)` ×1 → `onVisualsMismatch`（資訊超集行：原訊息＋player＋signed diff＋分佈計數） | 4 |

- **(b) 語意**：off＝直通（null 就地 NPE，vanilla 等價）；observe（預設）＝記錄後拋
  NPE（**保 vanilla 失敗語意**——同樣被 send 的 per-connection catch 吞，行為零差、log 指紋換成可歸因版）；enforce＝null visual／null tint 都回 `ImmutableColor.white`（**只保序列化存活**，transport liveness——接收端 process :190-207 仍有
  `getVisual().setTint` 假設，不宣稱端到端根治）。**禁止改成 lambda 過濾整件 item**：`SyncClothingPacket.process` 會把封包未列出的
  worn item 從遠端 `WornItems.remove`（SmokeCheck 行為錨釘死）＝把 asset 暫未 ready
  解讀成脫衣。
- **(c) 刻意不 enforce**：SyncVisuals 是純 positional 協定（wire 只有 count＋依序
  patch/dirt/blood，無 item identity）——「跳過異常項」「clamp 到 min(count)」都會把洞/血/condition 套到錯的衣服；vanilla 整包拒絕反而安全。修復方向只能是治成因（(b) enforce）或完整 SyncClothing reconciliation/resync（另案）。
- **(a) 刻意不修**：修復要動封包定位語意（改讀 getSquare() 或 null 時換 ContainerType
  fallback），影響所有容器封包——等本探針分解（o class 分佈、square vs getSquare 差、caller）後另案。
- (b) 的 NPE 現況無 pool 洩漏疑慮：PacketsCache 是 per-connection 每 PacketType 長存
  handler，setData 失敗時尚未 startPacket（codex lane 查證）——helper 也不在 packet
  欄位留狀態。

### 守門與行為測試

- vanilla 前提釘：ctor `getVisual=5、getTint=1、IFNONNULL=2`（**TIS 補上守衛時
  IFNONNULL 變 3＝(b) 撤刀訊號**）；write 內 GETFIELD tint=4（第二 NPE 點）；process
  的 `WornItems.remove(InventoryItem)`=1（禁止過濾的行為錨）；parse `getPlayer=3、
  error(Object)=1、getItemVisuals=1`；雙參 set `raw square=6／getSquare=0／getObjects=2`＋單參呼叫雙參=1（兩層 set 結構）。
- 手術後：三 headCall 全序（`headCallSlotsOk` 多 slot 版首用）＋真指令對帳（+3/+2/不變）；redirect 原呼叫歸零；**負對照**：`SyncVisualsPacket.write` 未被動（redirect 是
  method-scope，write 的 getPlayer 保持 vanilla）。
- helper 契約：tintOf 委派 2（off 直通＋非 null 主路徑）、white 引用 2（兩個 enforce
  出口）；onVisualsMismatch 的 error 出口恰 1（off/observe 同 sink，資訊超集不翻倍）；parsePlayer 委派 1；onSet 的 getStackTrace 恰 1（square-null 時才走）。
- `ClothingSyncGuardTest` 三組態獨立 JVM（observe 預設／tint enforce／三把全 off，模式自驗）：parseCounts 三例（+1/-1/格式不符→null）、tintOf 三態（observe 拋 NPE
  帶刀名、enforce white＋repaired、off 直通 NPE 計數凍結）、tint-null 三態、mismatch
  signed diff 分佈（plus/minus/other 各 +1）、ContainerIdProbe 分解計數（objectNull/
  squareNull/正常路徑）與 off 純早退、caller 分類（跳過兩層 ContainerID.set）。

### kill switch（三把分離、獨立降級，比照 W10）

`-Dmdc.containerIdProbe`（0|off／2|observe 預設）；`-Dmdc.clothingTintGuard`（0|off／1|enforce＝null→white／2|observe 預設）；`-Dmdc.visualsMismatchProbe`（0|off／2|observe 預設）。未知值一律落回 observe。

### 部署與觀測（驗收）

- observe 一輪後能回答：(a) 的 o class 分佈（殭屍/屍體/其他）與 square-vs-getSquare
  指紋、(b) 集中在哪些玩家與 fullType（nullTint 路徑帶 item）、(c) 的 diff 符號分佈與 player、(b)(c) 是否同人＝共同根因定罪、是否集中 MirageWardrobe 物品。
- (b) 開 enforce（`-Dmdc.clothingTintGuard=1`，需重啟）後驗收：`nullVisual` 續計但
  `repaired`>0 且 send-exception 指紋中 (b) 份額歸零；**不看 ERROR 總量**（(a) 未修）。若 (b)(c) 同根因成立，(c) 的 129 條應同步顯著下降——這是免費的因果驗證。
- 玩家面回歸：無新「衣服脫不掉／別人看不到我衣服」回報（enforce 只影響 tint 序列化，白色 tint 是可見但無害的降級指紋）。
- **2026-09-02 巡檢收案（8/28–9/2 observe＋(b) enforce 各一輪）**：nullVisual 8 天 480+ 筆**全部 `player=Player-G`**，enforce 生效後 `action=white`、send-exception 指紋消失；(c)
  mismatch 仍在（8/30 21-46 ×6、9/1 00-06 ×20，全 Player-G、`wireMinusLocal=+1`）——同根因（同人同號）成立，但「enforce 讓 (c) 下降」的預期**不成立**：tint 修復只保序列化，`getItemVisuals` 少算 1 的機制未動，(c) 要等那件物品被找出並處理。**(a) 主體推翻**：squareNull 全部是 `o=IsoPlayer`（`getSquare=non-null(current)`、`container=none/IsoPlayer`、caller `SyncItemFieldsPacket.setData:135`），非殭屍／屍體；對應 `Error with packet of type: SyncItemFields` 40/2 天；低頻（1–5/session），維持不修。

### W20-2：nullVisual 物品歸因（2026-09-02）

nullVisual 路徑印不出物品——redirect 只換了 `getTint` 的 receiver，而那個 receiver 就是
null（`nullTint` 路徑才有 `describeItem(visual)`）。補一刀 ctor 頭部 headCall
`ItemDescription.<init>(WornItem)V` slot 1 → `ClothingSyncGuard.onItemDescription(WornItem)`（ThreadLocal 存當前 WornItem；ctor 頭部 `aload_1` 只碰參數不碰 uninitializedThis，super 之前合法，`-Xverify:all` 通過），nullVisual 行改印 `item=<fullType>@<bodyLocation>`（任一環節缺→`?`，`ItemBodyLocation.toString()` 走 Registries、失敗落回 `?`）。`expectedHits` 1→2（headCall＋getTint redirect）；SmokeCheck 釘 ctor 頭部全序、真指令 +2、helper 純 `ThreadLocal.set`（零 NEW／零 DebugLog／零 WornItem 呼叫）。部署後第一筆
`nullVisual#N player=Player-G item=…` 即物品歸因；MirageWardrobe 假說由 fullType 直接證實或排除。

### 2026-09-28 42.21 對版

三個掛點（`SyncClothingPacket.set`、`ItemDescription.<init>`、`SyncVisualsPacket.parse`）逐指令未變，ctor 的 tint 仍無 `getVisual()==null` 守衛（IFNONNULL 仍 2），`WornItems.getItemVisuals` 仍跳過 null visual，本刀原樣保留、不改碼。patch notes 的「Clothing condition is now handled server-side」是新增的
`BloodClothingType.setConditionAndSync`，不碰 visual／tint。

**觀察（待量測）**：42.21 的 `SyncVisualsPacket.processServer` 由手寫迴圈（排除發送者＋`isRelevantTo`）改為 `sendToClients(SyncVisuals, connection)`，只排除發送者並要求 fully-connected，**不再依距離過濾**——這是 patch notes「Synchronized players' appearance between chunks」的實作。每次外觀同步的出向封包數因此從「附近玩家」變成「全部在線玩家」，屬新的頻寬放大點。不影響本刀的 mismatch 判定（在 parse，每個上行包一次）。要不要比照 W26／W36 以 `RecipientWindow` 過濾，須先抓包量測 `SyncVisuals` 出向流量再評估；貿然依距離過濾會把 TIS 剛修掉的跨 chunk 外觀不同步帶回來。

---

<a id="2ai"></a>
## 2ai. 面向物件 sprite-grid null 守衛（W22，server，預設 on）

> **退役（2026-09-28，42.21.0 官方已修）**：42.21 的 `IsoGameCharacter.faceThisObject` 開頭改為
> `object instanceof IsoGameCharacter` 轉 `faceThisObjectAlt`，否則要求 `object != null &&
> object.getObjectIndex() != -1` 才繼續（javap offset 17–25：`getObjectIndex` → `iconst_m1` →
> `if_icmpne`／`return`）。守衛通過代表物件仍列在其 square 的 `objects` 中，`getSpriteGridObjects(…, true)` 必含 self，`getClosestSpriteGridObject` 不再回 null——本節記錄的 stale 食槽 NPE 前提已消失。下方「IFNULL/IFNONNULL=5」撤刀訊號因官方改用不同形狀的守衛而沒有觸發，是人工審計判定退役。改道、`FaceObjectGuard` helper、SmokeCheck W22 四項與
> `FaceObjectGuardTest` 一併移除；復活：`git checkout 8d2bee8 -- <檔案>`。以下原文保留當歷史。

### 立案（2026-09-02 全 patch 巡檢，log 最大單一例外源）

9/1–9/2 兩天 `ERROR: StateMachine.stateExecute> Exception thrown` **3386 次（≈70/h）**，100% 同一指紋：`NullPointerException: Cannot invoke "IsoObject.getFacingPosition(Vector2)"
because "object" is null` at `IsoGameCharacter.faceThisObject`；caller 全是動物狀態機——
`AnimalIdleState.execute` 2366／`AnimalEatState.execute` 1020（各自對 `eatFromTrough`／`drinkFromTrough` 呼叫 `faceThisObject`）。每次帶 8 行 stack，是本輪巡檢 log 噪音第一名。

### 根因（javap 對 42.20.4 jar＋反編譯）

`faceThisObject` 對 sprite-grid 物件（食槽等多格物件）先呼叫
`object.getClosestSpriteGridObject(getX(), getY())`（offset 200）再 `astore_1 / aload_1 /
getFacingPosition`（offset 203–206）**無條件解參考**。而 `getClosestSpriteGridObject` 在
`getSpriteGridObjects(result, true)` 回空清單時回 `null`（IsoObject:5436-5450）：「包含 self」只在 self 仍列於其 square 的 `objects` 清單時成立（5389-5395 的
`testSq.getObjects()` 迭代＋`object.getSpriteGrid() == spriteGrid`）——動物側
`eatFromTrough`／`drinkFromTrough` 指向**已移出世界的食槽**（拆除／搬移後動物側參照未清）或 grid 格在 server 端未載入（`getGridSquare` 回 null）時，清單為空 ⇒ NPE。100% vanilla。

**後果不只是噪音**：例外中斷該 tick 的 `state.execute`——`AnimalIdleState.execute` 的
`faceThisObject` 在前、`changeState(AnimalEatState／AnimalWalkState)` 在後，動物卡在 idle
不轉 eat/walk、每 tick 重炸直到參照被別的路徑覆蓋。與 W16/W17 的動物議題同域，機制獨立。

### 手術

`IsoGameCharacter.faceThisObject(IsoObject)V` 內唯一 `invokevirtual
IsoObject.getClosestSpriteGridObject(FF)` → `invokestatic FaceObjectGuard.closestSpriteGridObject
(IsoObject,FF)IsoObject`（1:1 同形，receiver 前置，`expectedHits=1`）。helper 委派 vanilla；結果 null 時回原 `object`（`getFacingPosition` 以 object 自身 x/y 計算＝面向舊位置），非 null
逐位元等價；委派拋出的任何例外原樣穿透（含 `getSquare()==null` 那類不同訊息的 NPE）。`faceThisObjectAlt` 內同名 callsite **刻意不動**（log 零命中；SmokeCheck 負對照釘死 Alt 仍
vanilla、class-wide 改道恰 1）。掛在既存 W7 的 IsoGameCharacter ClassPatch 上（同 class 不得開第二個 ClassPatch，W19 教訓）。診斷：前 32 次 fallback 印 class／sprite／square／呼叫者座標（可對回農場與食槽），之後只計數；heartbeat 每 2²⁰ 次 `calls/fallbacks/anomalies`。kill switch `-Dmdc.faceObjectGuard=0`（純直通，null 照回＝vanilla 語意）。

### 守門與行為測試

- vanilla 前提：`faceThisObject` 內 `getClosestSpriteGridObject=1、getFacingPosition(Vector2)=1、
  IFNULL+IFNONNULL=5`，且 closest 結果緊接 `astore_1→aload_1→aload→getFacingPosition`（`callFollowedByStoreLoadCall`：TIS 補上 null 檢查／換 slot／改鏈式呼叫時建置紅＝撤刀訊號）；`faceThisObjectAlt` 另 1、class-wide 2。
- 手術後：改道 x1、原呼叫歸零、真指令不變；Alt 未動；class-wide 改道恰 1；helper 委派恰 1、零 NEW、零 DebugLog（診斷在獨立方法）。
- `FaceObjectGuardTest` on/off 獨立 JVM（旗標自驗）：非 null 同實例轉發、null→原 object＋fallbacks+1（off 回 null）、半初始化物件診斷不炸、RuntimeException／Error 穿透、零 anomalies。

### 驗收

- `StateMachine.stateExecute` 的 faceThisObject NPE 歸零（vanilla 別處 NPE 仍會印、不混算）。
- 首批 fallback 詳細行的 sprite／square 對回食槽：預期是 `furniture_feeding_trough`／水槽且 square 已無該物件（stale 參照定罪）；若出現 square=null 或非食槽，重估根因分佈。
- 行為面：農場動物是否恢復進 eat state（食槽消耗、動物飢餓值）——與動物減少議題的關聯由 W17 之後的 apop 對照觀察，本刀不宣稱根治動物消失。
---

<a id="2aj"></a>
## 2aj. 卡讀條第二波觀測（W10-C，server，預設 observe；enforce＝打斷時補送 Reject）

> **2026-09-28 42.21 對版**：C（`NetTimedAction.start` tailCall）與 R（`ActionManager.update` 三改道）退役；W10-E（`ActionManager.stop` headCall＋`remove(BZ)` 改道）因官方已修退役；派送 bridge 精簡為動作封包 owner 檢查。詳見本節末「2026-09-28 42.21 對版」。

### 立案（2026-08-28 回報、2026-09-02 落地）

W10（§2x，8/23 上線）根治「server 建構動作就炸 → 既不 Accept 也不 Reject」後，玩家 8/28 晚峰仍回報「讀條走滿不完成」（製作／做奶油／拆除；間歇、排隊多條卡一條、後續動作一起堵死）。兩份受害者 client log 在卡住當下**完全安靜**——零 error、零 Reject、零 `[NetTimedAction]`；server 端 W10 heartbeat `caught=0 rejected=0`＝W10 的目標情境根本沒發生。其中一份 log 顯示
DismantleAllAtOnce（wid 3761218629）一次排入 15 條 handcraft action，是「排隊多條卡一條」的直接背景。反編譯（42.20.4）定案 **三條 W10 未覆蓋、且在 server log 上零指紋** 的 server 路徑；本刀不猜哪一條是主因，把三條全量出來。

### 三條路徑（vanilla 事實，javap 對 42.20.4 jar）

- **B 靜默打斷**：`NetTimedActionPacket.processServer` 對每個新 Request 先
  `ActionManager.stopPlayerActions(playerId)`（offset 48）再 `start`；而 server 端
  `ActionManager.remove(B,Z)`（反編譯 :188-199）只移出清單＋`stop()`、**不送任何封包**（`startPacket` 恰 1 且在 `GameServer.server` 判定的 client 分支）。被打斷的 **Accept 中**舊動作在 client 端永遠等不到 Done／Reject——client 的 `isDone`／`isRejected` 都要靠回包。同 id 重送（client retry）不是打斷，另計。
- **C 30 分鐘路徑**：client 讀條長度用 client Lua 自算的 `maxTime`，server 何時 `perform`
  用 server 自算的 `endTime`（`NetTimedAction.getDuration`＝Lua `getDuration` ×20ms＋server
  專屬 `adjustMaxTime`）；`Action.setTimeData`（:30-31）在 `duration<0` 時直接
  `endTime = start + AnimEventEmulator.getDurationMax()`＝**1,800,000 ms**。`ISHandcraftAction:257-258`
  在 server 端 `craftRecipe` 為 nil 時就回 -1——ctor 不炸、零 log，client 讀條走滿後 server
  要 30 分鐘才 perform。**-1 對動畫驅動的動作是合法值**，故本路徑只能觀測不能 enforce。
- **R Reject 出口**：`ActionManager.update` 的 `Action.perform()`（恰 1）回 false 時走 Reject
  分支，但 Done／Reject 兩分支各自 `GameServer.getConnectionFromPlayer`（恰 2），回 null＝**封包不送**（同樣零 log）。量 `perform` true/false 分佈與 connection null 次數。

### 手術（1 redirect＋1 tailCall＋3 redirect；Request 上下文共用 W10-E dispatch）

| 目標 | 手術 | 語意 |
|---|---|---|
| `NetTimedActionPacket.processServer`（掛在 W10 既存 ClassPatch；總 `expectedHits=3`） | `ActionManager.stopPlayerActions` → `MdcTimedActionProbe.stopPlayerActions` | Request 取自 W10-E dispatch 上下文；先觀測同 owner 的 Accept 舊動作，再委派 vanilla 停止流程。不再另插 headCall |
| `NetTimedAction.start()V`（掛在 W10 既存 `nta` ClassPatch；`expectedHits=1`） | **TailCall**（每個 RETURN 前 `aload_0; invokestatic onStart(NetTimedAction)V`；單一 RETURN offset 57） | `setTimeData` 之後讀 `duration`／`endTime`，負值逐筆列出（type／name／player／endTimeDeltaMs） |
| `ActionManager.update()V`（**新 ClassPatch**；`expectedHits=3`） | `Action.perform()Z` → `perform(Action)Z`；`GameServer.getConnectionFromPlayer` ×2 → `connectionOf` | 1:1 同形；先委派 vanilla 再簿記 |

`TailCall` 詞彙隨 W16 退役被刪、本刀復用（`Patcher.TailCall`：`visitInsn(RETURN)` 前插兩條、`visitMaxs` 下限 1、無新 branch target ⇒ 不動 frames）。helper **放 `zombie.core`**（不是
`zombie.mdc`）：`Action` 是 package-private class、欄位 protected、`perform()` package-private，同 package 才能零反射直讀；唯一反射是 `ActionManager.actions`（private static），class init 一次快取，找不到即 `IllegalStateException` 外逃＝fail-fast（TIS 改結構時開機就紅，不會靜默直通）。

**enforce（B 唯一可安全修的一條）**：對被打斷的 Accept 中舊動作，模仿 `ActionManager.update`
Reject 分支（:87-96）的形狀——`state=Reject` → `startPacket` → `NetTimedAction.doPacket` →
`action.write` → `send`——用**同一物件**送出（W10 的 A 刀教訓：`this.write` 送錯物件＝Reject
永遠是 Request state）。client 收到 Reject 走 vanilla `isRejected→forceStop`，是既有路徑、無更差結果；連線 null／未 fully connected 則 `rejectsSkippedNoConn++` 不動 state。同 id 重送不補（那是 client retry，補送會拒掉它剛重送的那條）。C 與 R 在 enforce 下仍只觀測。

### 語意邊界（刻意不做的事）

- 不改 `setTimeData` 的 -1 退路、不縮 `getDurationMax`：-1 是動畫驅動動作的合法值，改了會讓合法動作提前 perform（消耗材料／產出成品的時序錯位）。
- 不在 processServer 拒絕新 Request 來「保護」舊動作：vanilla 語意就是新請求打斷舊動作（client 端 `ISTimedActionQueue` 也是這樣切換），要修的是「打斷後沒回包」，不是打斷本身。
- 不猜 R 路徑 connection null 的原因（斷線瞬間 vs 重連中）——先量 `connNull` 是否非零。

### 守門（SmokeCheck 六條）

- vanilla (B)：`processServer` 內 `stopPlayerActions=1`；`ActionManager.remove` 內 `startPacket=1`＋`GETSTATIC GameServer.server=1`（**server 分支零封包＝B 刀存在理由**；TIS 補送 Reject 時該條紅＝撤刀訊號）。
- vanilla (C)：`start` 內 `RETURN=1`、`setTimeData=1`；`setTimeData` 內 `getDurationMax=1`。
- vanilla (R)：`update` 內 `perform=1`、`getConnectionFromPlayer=2`。
- 手術後：`processServer` 的 `stopPlayerActions` 改道 x1／原呼叫歸零，真指令數不變；`start` 尾部 `aload_0→onStart`（`tailCallOk`：每個 RETURN 前兩條＋呼叫數＝RETURN 數）、真指令恰 +2；`update` 改道 1+2、原呼叫歸零、真指令不變。
- helper 契約：三委派各恰 1；`sendReject` 內 `write=1／doPacket=1／send=1`（補送形狀釘死）。

### 行為測試（`MdcTimedActionProbeTest`，獨立 JVM 四組態）

observe／enforce／觀測 off 均保持 connection scope；另跑 `actionRemoveScope=0` 的原版負對照。B 測試透過真 dispatch 上下文與實際 `stopPlayerActions`／`ActionManager.stop` 驗
Accept 才計數、同 id 重送不補 Reject、尚無登錄連線時安全跳過補送；不再使用 production setter
或直接呼叫私有觀測器。start 的正／負 duration、perform false、取消與錯誤邊界見下方 W10-E。測試先播種 Rand、以真 IsoPlayer／UdpConnection 的最小欄位初始化避免載入完整世界；`GameServer.server=true`，因此 scope off 真的會執行原版 server 移除，不以 no-op 當負對照。

### 部署與觀測（驗收）

- 開機 `[TimedActionProbe] 首次生效 mode=2`；heartbeat（每 256 個 start 檢查、60s 一行）`starts/negativeDuration/interruptCalls/interruptedAccepted/sameIdResend/rejectsSent/
  rejectsSkippedNoConn/performCalls/performFalse/connLookups/connNull/logged/suppressed/anomalies`；逐筆行 10s 窗 20 行 rate limit，`anomalies` 必須恆 0。
- 一個晚峰後看三類份額：`interrupted#`（含 `waitedMs`——已等接近 client maxTime 卻被打斷＝玩家體感「走滿不完成」的直接指紋）vs `negativeDuration#`（type 分佈：預期 `ISHandcraftAction`
  server 端 recipe nil）vs `performFalse#`／`connNull`。份額決定下一步：B 佔大宗 ⇒ 開
  `-Dmdc.timedActionProbe=1`（需重啟）並驗 `rejectsSent>0`、玩家回報下降；C 佔大宗 ⇒ 回到
  Lua 層（哪些 recipe 在 server 端 nil、與 DismantleAllAtOnce／Neat_Crafting 的關聯），Java 側無安全刀；R 非零 ⇒ 另案查連線生命週期。
- 官方回報：B（server remove 不回包）與 C（-1 → 30 分鐘）各自成案，等本刀數據後補進
  `docs/report/` 的 TIS 回報（B-R1 更新＋兩份新報告）。

### 2026-09-08 校正：W10-E 以真實連線隔離取消

**先撤回舊歸因**：9/7 observe 與初版 `scope=player` 都把 GeneralAction 解析出的 `playerId`
當成發送者。真 wire 已否證這個前提，因此舊 `1438`／`706` 分類、`98% 已完成後取消`、「少數帳號發起」與 `spared(scope=player)` 成功論全部不可再作為跨玩家受害數或修復證據。負 duration 也不等於無害。既有 W10-C 觀測只顯示當時未見 recipe-nil 製作型 -1 等情境，不能排除其他未觀察到的卡讀條機制。

**原版缺陷（真 jar／真 wire）**：

- `Action.id` 是各 client JVM 自己循環使用的 **255 個非零 byte**；不同連線會撞號，同機多人共用同一個計數器。Java 表示包含負數，不是整數 1–255。
- `GeneralActionPacket.setReject` 只寫 id/state。action id 66 的真序列化 bytes 為
  `42 00 00 00 ff`：Reject ordinal 0、player onlineID 0、playerIndex -1。server 於 index=-1 時查全域 player map，結果是目前的 0 號玩家或 null，**不是發送者**。
- `GeneralActionPacket.processServer` 直接 `ActionManager.stop(this)`，沒有 `getAction/copyFrom`。原版 `stop` 又只把 byte id 傳給 `remove`；server 分支對整份 queue 只比 id、停止命中者、不向其他 client 回 Done／Reject。
- 只在 `remove` 過濾 owner 還不夠：`NetTimedActionPacket` 的 Reject 會先 `getAction/copyFrom`
  改旁人 state；Fishing Reject 後仍可按全域 id 產生旁人的 Lua event。Request 的數字
  onlineID 與連線 slot 解析出的 player 也可能不一致，必須在查改既有 action 前驗證。

**現行手術與信任邊界**：

1. `PacketTypes$PacketType.onServerPacket` 最後唯一的 `INetworkPacket.processServer` 改道至
   `MdcTimedActionProbe.processServer`。原授權、parse、一致性、anticheat、warn／sync 全保留；receiver-first static bridge 只替換最後派送，ASM 重新定位原 labels。
2. Action 封包以 ThreadLocal 綁定**真正的 connection 與 packet**，`finally` 恢復上一層；可重入且例外後不殘留。W10-C Request 診斷共用這份上下文，已刪舊 `onProcessServer`
   headCall／`CURRENT_REQUEST`／測試注入 setter。
3. Request 先驗 owner 物件確實屬於該連線，以及 wire onlineID 等於該 owner 的 onlineID。不一致即拒絕派送，不能先 `copyFrom` 或向旁人補 Reject。
4. Reject 在原 packet method 的前後副作用之前直接進 scoped `stop`。候選必須同 action id，且其 owner **以物件 identity 屬於 connection.players**；不相信 packet 中的預設 owner，不以缺席／過期玩家的相同數字 id 認領。
5. 沒有 packet 上下文的 server 內部停止，只移除傳入的確定 queue 實例。封包缺連線、空 players、非 queue 實例等身分不明情況均不刪除，**不 fallback 全表**。
6. 先移出本連線整批命中者再逐一 `stop`／清 emulator，避免重入重刪。某筆失敗仍完成其餘已移出者的收尾，最後重拋首個例外並保留後續 suppressed 例外，不靜默吞錯。

`-Dmdc.actionRemoveScope` 預設開啟，橫幅為 `scope=connection`；只有 `0|off|vanilla`
回原版整表移除。與 `-Dmdc.timedActionProbe` 的 observe/enforce/off 獨立；關觀測不會關取消保護。W10-C enforce 的 interrupted 掃描同樣受 connection 範圍約束。

**回歸閉環**：四個獨立審查反例先重現失敗，再由同一組測試通過：NetTimedAction typed Reject
於下幀刪旁人、Fishing Reject＋bobber flag 產生旁人事件資料、Request 數字 id／owner
不一致覆寫旁人、首筆 stop 例外漏掉後續 emulator。另驗 GeneralAction 真 write→parse 的
0 號在線／離線、同機多人、重送同 id、無 own match、未知連線、stale owner、server 內部
identity、重入與 nested context；scope off 負對照**真的刪掉雙方同 id**。測試使用真遊戲類別及最小欄位初始化，不宣稱等同完整多人遊戲驗收。

**新計數與驗收**：

- `ownedRemoved`＝實際移出 queue 的數量，不代表 Lua stop 全部成功；`sparedOther`＝每次取消中保留的其他 owner 同 id 項目，可能重複計同一個動作，不是受害人數或成功率。
- `cancelsHandled`、`noOwnedMatch`、`unknownRefused` 分開看；`unknownRefused` 也含不可信 Request。`vanillaRemovals` 只在明示 off 路徑增加。未知身分 log 為 `untrustedAction`。
- 逐筆行列真 `connection`／`connectionPlayers` 與 queue owner；多位同機玩家不硬猜其中一人。`anomalies` 應為 0。必須另驗**本人合法取消仍有效**及玩家症狀，不能只看 spared 上升。
- 本輪尚未取得新版正式服驗收。W10-D 的 Reject 序列化證據另行對帳，不混作 E 的成功樣本。

**部署邊界**：新 manifest 移除舊 `PZNetKahluaTableImpl.class`，新增 `PacketTypes$PacketType.class`，且刪掉舊 helper 入口。升級須使用舊 manifest 完整卸載，再安裝新包，與受控重啟放在同一個窗口；不能刪了舊檔後讓已載入舊 caller 的 JVM 繼續等待排程。未做正式服部署／重啟前，修正只存在於新產物。

TIS 草稿：`docs/report/2026-09-07-tis-timed-action-followups.md` R2，**尚未提交**。

### 2026-09-28 42.21 對版

**W10-E 退役（42.21.0 官方已修）**：`ActionManager` 全面改以 (PlayerID, id) 分鍵——`stop` 呼叫
`remove(action.playerId, action.id, true)`，`remove` 的兩個 lambda 同時比 `Action.id` 與 `PlayerID.getID()`；`isDone／isRejected／isLooped／getDuration／getAction` 同改；`GeneralActionPacket.setReject(B, IsoPlayer)` 寫入發送者，client `LuaTimedActionNew` 以自己的 `playerId` 取消；`FishingAction.getLuaTable` 改用本封包 owner。`Action.write／parse`
無條件帶 playerId（local player 的 index≥0，server 以本連線 slot 解析）。本刀掛點 `remove(BZ)V` 已不存在，`onStop／removeById／removeForConnection／CURRENT_STOP／-Dmdc.actionRemoveScope` 全數刪除，`ActionManager` 不再出貨。

**C／R 退役**：§2bf 已記 1,600 筆負時長全是動畫動作的合法 -1、709 次打斷中 653 次是 ISWaitWhileGettingUp，R（perform false／connection null）沒有非零事證。刪 `NetTimedAction.start` tailCall 與 `ActionManager.update` 的
`perform`／`getConnectionFromPlayer`×2 改道（原版三條路徑仍在，只是不再量）。

**B 保留**：`processServer` 的 `stopPlayerActions` 改道與 observe／enforce／off 三態不變。42.21 的 server 端
`remove(PlayerID,B,Z)` 仍只移出＋`stop()`，唯一 `startPacket` 在 client 分支（SmokeCheck 改查新 descriptor）。原版 `stopPlayerActions` 已以 onlineID 過濾，舊的 connection scope 分支刪除；同 id 重送判定改用 bridge 綁定的
NetTimedAction 封包（只有這一種封包綁定）。heartbeat 改由每 256 次 `stopPlayerActions` 帶動（300 秒一行）：`interruptCalls interruptedAccepted sameIdResend rejectsSent rejectsSkippedNoConn unknownRefused logged suppressed anomalies mode ownerCheck`。

**派送 bridge 精簡（動作封包 owner 檢查）**：42.21 原版以 wire 上的 `PlayerID.getID()` 為查詢／取消鍵，但
`PlayerID.isConsistent` 只驗 `id != -1` 且解析得到玩家，不比對 onlineID 或所屬連線；`playerIndex=-1` 時 server
以 `IDToPlayerMap` 直接解析成其他連線的玩家，四個動作封包也沒有 anticheat。改造 client 因此能以他人 onlineID
取消他人動作，或送 Request 讓 `stopPlayerActions` 無聲打斷他人所有讀條。bridge 現在只做三件事：

1. 所有 `Action` 封包（GeneralAction／NetTimedAction／BuildAction／FishingAction，任何 state）派送前要求 owner 物件以
   identity 屬於本連線 `players`，且 wire onlineID 等於 owner 的 onlineID；不符就不派送，計 `unknownRefused`，逐筆 `untrustedAction#`（10 秒 20 行）。MODE_OFF 時照樣拒絕、不記錄。`-Dmdc.actionOwnerCheck=0|off` 回原版直通。
2. 通過者一律交回原版 `processServer`：取消、NetTimedAction 的 `getAction／copyFrom`、Fishing Reject 後的
   `OnFishingActionMPUpdate` 都照原版執行（舊版的 Reject 早退與 scoped stop 已刪）。
3. NetTimedAction 封包派送期間綁定為 W10-C 的 Request 上下文（try/finally 還原、可重入）；`WorldSoundPacket`
   照舊交給 §2at 觀測器。

**驗證**：`MdcTimedActionProbeTest` 四組態（observe／enforce／off／`actionOwnerCheck=0`）。四種動作封包以真 write→parse
產生帶他人 onlineID 的 Reject（GeneralAction 走 `setReject(id, player)` 的本地 slot，其餘走 index -1）：檢查開著時雙方同 id 動作不動；owner-off 負對照中原版確實取消了他人動作。合法取消交回原版只移除自己的同 id 動作，Fishing
Reject＋bobber flag 的事件資料屬於本人。偽造 Request（index 0／-1）在 `getAction／copyFrom` 前拒絕；owner-off 下原版把他人的動作改成 Reject。另驗同機多人 index 1、過期 owner 物件、owner 屬本連線但 wire onlineID 是同機另一人、空連線與缺連線。SmokeCheck 釘 W10-E 退役依據（`remove` 以 (id, PlayerID.getID) 分鍵、`setReject` 寫入發送者）、owner 檢查存在理由（`PlayerID.isConsistent` 不讀 onlineID／不查連線）、四個封包的原版取消入口、bridge 不呼叫
`ActionManager.stop／remove`，以及 `ActionManager` 不出貨。

**命中數**：`NetTimedActionPacket.processServer` 3→1、`NetTimedAction` 4→3（start 移除）、`ActionManager` 5→0（不再是
patched class）、`PacketTypes$PacketType.onServerPacket` 1 不變。部署時用舊 manifest 完整卸載，避免殘留舊的
`ActionManager.class`（它呼叫已刪除的 helper 方法）。

<a id="2ak"></a>
## 2ak. 每 Steam ID 帳號上限的登入期執法（W23，server，預設 on）

### 立案（2026-09-06）

服主要「同一個 Steam ID 只能一個帳號」。`MaxAccountsPerUser` 自 7 月起一直是 2、也真的擋過（`*_user.txt` 有 `MaxAccountsReached` 拒絕紀錄），但 whitelist 實查 **636 個 Steam ID 共 1284 個帳號**，單一 ID 最多 6 個。把 ini 改成 1 只擋「以後再建」，既有的多帳號照常登入。

### 根因（javap 對 42.20.4 jar）

1. `ServerWorldDatabase.authClient(String,String,String,long,int)` 只在「whitelist 查無此帳號名」分支呼叫 `isNewAccountAllowed`（offset 685）；既有帳號分支 offset 643 直接 `areturn`，**不做任何計數**。
2. `isNewAccountAllowed` 的計數 key 是連線 `getSteamId()`；家庭共享時是子帳號 ID，whitelist 存的卻是 ownerid ⇒ 每個子帳號一份新額度。
3. `LoginPacket.processServer` 每次登入都 `setUserSteamID`（offset 1320，`UPDATE whitelist SET
   steamid=? WHERE username=?`）⇒ `whitelist.steamid` 是「最後登入者」而非「建立者」，多人共用帳號名時計數基準本身在漂移。
4. 任一列具 `PriorityLogin`（role≥priority）整個 ID 無上限（vanilla 豁免）。

### 手術

`LoginPacket.processServer` 與 `GoogleAuthKeyPacket.processServer` 內各唯一的
`invokevirtual ServerWorldDatabase.authClient(…)` → `invokestatic
MdcAccountGate.authClient(ServerWorldDatabase,…)`（1:1 同形，receiver 前置，各 `expectedHits=1`）。helper 先委派 vanilla；`authorized` 才追加名額判定：以 key 撈 whitelist 列、依 `lastConnection`
由新到舊排序（NULL 最舊、同刻以 id 倒序），**帳號名的名次 < `MaxAccountsPerUser` 才放行**，新帳號則看既有列數；任一列 `PriorityLogin` 照 vanilla 豁免。拒絕時只改 `LogonResult`
的 `authorized=false`／`dcReason="MaxAccountsReached"`，後續 log 與 `AccessDenied` 封包全走 vanilla
既有分支（offset 218-223）。**不刪任何資料**，uninstall 即回 vanilla。

helper 放 `zombie.network`（非 `zombie.mdc`）：`ServerWorldDatabase.conn` 是 package-private，loose class 與 jar 同 classloader、同 runtime package 可直接用（jar 未 sealed，manifest 實查）。

身分 key **預設連線 `getSteamId()`**（與 vanilla 同語意，家庭共享子帳號各算各的——使用者
2026-09-06 決定，實查有真的兩個人共用一套家庭庫同時在線）。`-Dmdc.accountGate.key=owner`
改為 `getOwnerId()`＋whitelist `ownerid` 優先，整個家庭合計 max 個。kill switch
`-Dmdc.accountGate=0`。例外一律 fail-open（回 vanilla 結果並記 `[AccountGate] fail-open`）。

行為結果：每個 Steam ID 只剩「最近登入的那個」帳號名能進；其餘登入即 `MaxAccountsReached`，而拒絕發生在 `updateLastConnectionDate` 之前，名次不會因嘗試而改變（穩定）。`MaxAccountsPerUser` 改回 2 就自動放寬到前 2 名。

### 守門與行為測試

- vanilla 前提：兩個封包的 `processServer` 內 5 參數 `authClient` 各恰 1。
- 手術後：改道 x1、原呼叫歸零、真指令不變；helper 委派 vanilla `authClient` 恰 1。
- `allowed()` 純函式：名次 < max 放行、超額拒、新帳號依既有數、空清單放行、PriorityLogin 豁免（含第三個帳號名）。SQL 已對正式服 whitelist 實跑驗證排序（6 帳號 ID 的名次與預期一致）。

### 驗收

- 開機健檢無 `VerifyError`／`NoClassDefFoundError`；`grep -c 'AccountGate' server-console.txt`。
- 首個被拒登入：`*_user.txt` 出現 `access denied: user "X" reason "MaxAccountsReached"`，console 同時有 `[AccountGate] deny user="X" key=… rank=N accounts=M max=1`（rank ≥ max）。
- 反向：單帳號玩家與 admin（PriorityLogin）登入零 deny 行。
- `fail-open` 行 ≠ 0 ⇒ SQL 或 schema 變了，先 `-Dmdc.accountGate=0` 再查。
---

<a id="2al"></a>
## 2al. `%ld` 格式字串修正（W24，server，無旋鈕）

> **退役（2026-09-28，42.21.0 官方已修）**：官方把兩個字面值改為 `idToEntityMap(%d)=%s, expected %s` 與
> `idToEntityMap(%d)=%s, expected null for %s`（javap 42.21 offset 72／158；第二條 3 個 spec 對 3 個 arg），格式例外不可能再發生；另外 storedNew 為 `getEntityNetID()==-1` 的 IsoObject 時先 `remove(newID)`，清掉過期對應（舊版是丟棄結果的空呼叫）。42.21 命中 0/2，PatchConfig 段刪除。驗收 grep 改看 42.21 原文 `idToEntityMap(`。

### 立案（2026-09-06，從 minor 升級）

`checkEntityIDChange` 的兩個診斷訊息用 C 風格 `%ld` 當 Java `Formatter` 格式字串 →
`UnknownFormatConversionException: Conversion = 'l'`，而呼叫鏈無 catch ⇒ 例外上拋、把呼叫端整條動作打斷（建造升級扣料不出貨、拆解中止、chunk 卸載中止）。已回報 TIS（[tis-bug-report-42.20.3-minor.md](tis-bug-report-42.20.3-minor.md) Bug 2，當時只見 chunk 卸載故判 minor）。次數分佈與逐路徑後果見
`internal-analysis/reports/ops/2026-09-06-補丁需求總評-0830至0906.md` §4.1。Lua 修不安全（例外時移除封包已送出、`idToEntityMap` 可能已被改），故移交本 repo。

### 根因（javap 對 42.20.4 jar，`work/projectzomboid.jar` sha256 `80e405a4…`）

```
 72: ldc_w  #396  // String idToEntityMap(%ld)=%s, expected %s     ← 3 個 arg / 3 個 spec
144: ldc_w  #408  // String idToEntityMap(%ld)=%s, expected null   ← 3 個 arg / 2 個 spec（第 3 個被靜默忽略）
```

兩個字面值在全 class 只有這兩處 `ldc` ⇒ 無共享常數池汙染。

### 手術

同一方法內兩個 `ConstChange`（`%ld`→`%d`；第二條順手補 `(entity=%s)` 讓被忽略的第 3 個 arg
有 spec 可對），`expectedHits = 2`。純 LDC 常數替換：堆疊形狀不變、無 helper、無 kill switch（**回退＝`uninstall.sh`**）。

### 守門與驗證

`expectedHits = 2` 逐方法守門（字面值被官方改寫即建置失敗）；常數手術「數量對不代表改對地方」，每次 PZ 更新重跑上面的 javap 語境對照。手術後 `javap -c dist/java`：目標方法除兩個字面值外
108 行逐指令相同（offset 與 Exception table 不變）；同 class 其他方法的 `ldc_w`→`ldc` 是 ASM
常數池重排的既有現象（`GameServer` 586／`IsoObject` 222／`IsoAnimal` 200 行同樣如此），由 `LoadCheck`＋`BytecodeVerify` 覆蓋。`dist/manifest.txt`：`GameEntityManager.class … 2hits`。

### 驗收

`grep -c "Conversion = 'l'"` 歸零；反向 `grep 'idToEntityMap('` 應開始印出診斷行
（原本被格式例外吃掉）。**entity ID 不一致本身是 vanilla 議題、本刀不修**——只是讓它變成
「印一行、動作照常完成」。
---

<a id="2am"></a>
## 2am. 序列化物件池執行緒隔離（W25，server，預設 on）

### 立案（2026-09-06，SaveAll 量測）

背景：W15 已定案 `SaveAll` 是 vanilla「全存檔＝主迴圈同步凍結」設計（2ac），問題只剩「能不能縮短」。候選 1 是 worker 4→8（主機 8 vCPU），先用外部 probe 量 4 條 worker 到底在幹嘛再決定。

量測工具 `temp/saveall-probe.py`（正式服 systemd transient unit `mdc-saveall-probe`，root）：偵測 JVM 冒出 ≥2 條匿名 `Thread-N`（`SaveAll` 每次 new 4 條 `WorkerThread`，所有長駐管線執行緒都有名字：`SaveChunk`／`LoadChunk`／`RecalcAll`）即進入 10 Hz `/proc` 逐執行緒 CPU／狀態採樣＋最多 6 張 `jcmd Thread.print`，worker 消失後再拍 4 秒；DebugLog 的 `SaveAll took` 自動對帳。

| 存檔事件（online） | 57 | 52 | 48 | 23 |
|---|---|---|---|---|
| `SaveAll took` | 4.05s | 3.33s | 3.20s | 2.20s |
| worker 階段（4 條同時活著） | 3.84s | 3.20s | 2.78s | 1.98s |
| 4 worker 各自 CPU | 98–100% | 96–97% | 105% | 93–99% |
| worker 狀態採樣 | R 100%，零 D／零 S | 同 | 同 | 同 |
| 4 條結束時間差 | 0.42s | 0.21s | 0.20s | 0.20s |
| 主執行緒 | 5%，`SaveAll:133 sleep` | 7% | 0% | 7% |
| `SaveChunk`（磁碟寫）| 23% | 13% | 12% | 10% |
| 整機 8 vCPU 忙碌 | 67% | 49% | 43% | 57% |

- 純 CPU-bound 序列化：不是磁碟（寫入去重靠 `ChunkChecksum`，`SaveChunk` 只 10–23%）、不是鎖等待；round-robin 分配平均、無落單 worker；整機還有 2.5–4 顆核閒著。
- **worker stack 樣本 43/54＝80% 落在 `ConcurrentLinkedDeque.pollFirst／linkLast／unlink／
  skipDeletedSuccessors`**，呼叫者是 `zombie.util.io.BitHeader`（`pool_byte/short/int/long` 四個
  `static ConcurrentLinkedDeque`：`getHeader` 的 `poll()`＋各 `release()` 的 `offer()`）與
  `zombie.core.utils.ByteBlock`（`pool_data_block` 同款）。每寫一個欄位標頭就對全域池 poll＋offer
  一次，4 條 worker 打同一組 head/tail cache line；CLD 的 `offer` 每次還 new 一個 Node。
- 機制驗證（本機 JDK25 microbench `temp/CldBench.java`，poll＋offer 一對）：共用 CLD 1 執行緒
  19–24ns、**4 執行緒 444–554ns、8 執行緒 1169–2206ns**；ThreadLocal ArrayDeque 4–10ns 與執行緒數無關。⇒ **候選 1（worker 4→8）會把池競爭放大到倒賠**，正確的刀是拿掉共用池。jstack 有 safepoint bias（CLD 的迴圈回邊是 poll 點），80% 是上界；真實份額由 canary 的
  `SaveAll took` 回答。

### 根因（javap 對 42.20.4 jar `80e405a4`）

```
BitHeader.getHeader(HeaderSize, ByteBuffer, Z):
   7: getstatic pool_byte   10: invokevirtual ConcurrentLinkedDeque.poll:()Ljava/lang/Object;   13: checkcast BitHeaderByte
  48: getstatic pool_short  51: invokevirtual …poll   |  89: getstatic pool_int  92: …poll   |  130: getstatic pool_long  133: …poll
BitHeader$BitHeaderByte/Short/Int/Long.release()V（四個方法逐指令同形，7 條真指令）:
   0: aload_0  1: invokevirtual reset  4: getstatic pool_x  7: aload_0  8: invokevirtual ConcurrentLinkedDeque.offer:(Ljava/lang/Object;)Z  11: pop  12: return
ByteBlock.Start:  0: getstatic pool_data_block  3: invokevirtual …poll
ByteBlock.End:   12: getstatic pool_data_block 16: invokevirtual …contains（$assertionsDisabled 守衛下＝死碼）
                 84: getstatic pool_data_block 88: invokevirtual …offer  91: pop
```

全 jar 對 `pool_byte/short/int/long` 的 `getstatic` 各恰 3（getHeader／release／`debug_print` 的 `size()`），`pool_data_block` 恰 3（Start／End ×2）——沒有別的消費者會與私有池不一致。

### 手術

全部 1:1 同形 redirect（receiver 前置、堆疊形狀與指令長度不變）到 `zombie.mdc.IoPoolIsolation`：`getHeader` 內 `CLD.poll` ×4 → `poll(CLD)Object`；四個 `release()` 的 `offer` 各 ×1 →
`offer(CLD,Object)Z`；`ByteBlock.Start` poll ×1、`End` contains ×1＋offer ×1。6 個 class、7 個方法、11 個命中點；helper 2 個 class（`IoPoolIsolation`＋nested `Local`）。

helper 不認識任何欄位名：以傳入的池實例做 identity 分槽（每執行緒 `ThreadLocal<Local>`，最多 8 個相異池 → 各一顆 `ArrayDeque`，實際 5 個；超出的池原樣委派 vanilla、計 `slotOverflow`）。`poll` 回本執行緒先前歸還的物件或 null（呼叫端自行 new）；`offer` 進本執行緒池、LIFO（剛歸還的仍在 L1）、每池上限 1024（超出丟給 GC；vanilla 全域池無界）、回傳恆 true；跨執行緒配置／歸還安全（歸還進歸還者的池）。SaveAll 的 worker 每次是新執行緒 → 從空池起步（每執行緒駐留＝巢狀深度，數十顆
24-byte 物件）、結束隨 Thread 物件 GC。熱路徑零共用寫入：無 AtomicLong、無 CAS；橫幅在每執行緒首次建 `Local` 時 CAS 印一次。三個改道目標各恰一處委派 vanilla（off／分槽溢位路徑）。

主執行緒另走靜態快取（2026-10-24）：主執行緒的 `ThreadLocalMap` 條目多，`ThreadLocal.get` 常走 `getEntryAfterMiss` 線性探測。正式服 47 人、FPS 4 時抓的 63 份主執行緒 dump 有 3 份停在這裡，都在送 chunk 給玩家的 `IsoChunk.SaveLoadedChunk`（每寫一個欄位標頭就查一次）。第一次由名為 `main` 的執行緒取用時記下它與它的 `Local`，之後主執行緒只比對 `Thread` 身分；其他執行緒照舊走 `ThreadLocal`。兩個靜態欄位只由主執行緒寫一次，也只有主執行緒會通過身分比對而讀到 `mainLocal`，所以不需要 volatile。

語意差異只有一處：`ByteBlock.End` 的 `assert !Core.debug || !pool.contains(block)` 改查本執行緒池——正式服 assertions 關閉，該分支是死碼。header／block 物件本身的 bytes 佈局零變化，存檔格式與網路格式不受影響（純配置策略）。kill switch `-Dmdc.ioPoolIsolation=0`（三處全部回到 vanilla 共用池）。

### 守門與驗證

- SmokeCheck：vanilla 前提（`getHeader` poll=4＋四池 getstatic 各 1、class-wide poll=4／offer=0；四個 `release()` 全序 `ALOAD→INVOKEVIRTUAL→GETSTATIC→ALOAD→INVOKEVIRTUAL→POP→RETURN`；`ByteBlock` Start poll=1／End contains=1＋offer=1；全 jar 五個池欄位 getstatic 各 =3）、手術後（改道數／原呼叫歸零／真指令不變／getstatic 池欄位保留供 identity 分槽）、helper 契約（三方法各委派 CLD 恰 1、零 NEW、零 DebugLog）。TIS 若新增第 4 個池消費者或改寫 release 形狀，建置紅。
- `IoPoolIsolationTest`（on／off 各獨立 JVM，走 dist 內手術後的真 `BitHeader`／`ByteBlock`）：寫→讀 round trip 逐位元；同執行緒 LIFO 回收同實例；4 執行緒 ×20000 巢狀配置零例外、on 時
  IdentityHashMap 交集為空＋全域池恆空＋每執行緒駐留＝巢狀深度、off 時全域池收到歸還；`contains`／cap 1024＋dropped／第 9 個相異池溢位委派 vanilla／`offer(null)` NPE。
- A/B（`temp/IoPoolBench.java`，dist 手術後真類別，每 iter 3 alloc＋3 release）：1 執行緒 69→32ns、**4 執行緒 1095→38ns（29×）**、8 執行緒 1858→44ns。
- 主執行緒快取 A/B（`temp/IoPoolMainBench.java`，dist 真類別，同一 JVM 兩條各塞 64／1000／4000 個 ThreadLocal（一半失效）的執行緒，poll＋offer 一對）：main 走快取 2.1–2.2ns、worker 走 `ThreadLocal` 9.2–9.7ns。微基準的表在 L1 裡；正式服主執行緒的表冷，差距以 dump 比例（約 5%）為準。

### 驗收

- 部署後下一次重啟起，`temp/saveall-probe.py` 對相近人數的 `SaveAll took`／worker 階段：worker 階段預期顯著縮短（上界 3–5×，實際份額由此回答），4 worker 仍 ~100% R；jcmd 樣本中
  `ConcurrentLinkedDeque` 應消失、殘餘落在 `IsoGridSquare.save`／`ErosionData`／`InventoryItem.save`
  等真序列化。
- 開機零 linkage error；`[IoPoolIsolation] 首次生效` 橫幅恰一行；`slotOverflow` 線上應恆 0（目前無週期行，需要時用 jcmd／測試存取器）。
- W25 生效後再重估 worker 4→8（屆時才是真 CPU 擴展）。不歸這刀管的殘餘：worker 結束後主執行緒
  0.4–0.7s RUNNABLE（`ServerPlayerDB`／visited／`GameEntityManager.Save` 那串）。

---

<a id="2an"></a>
## 2an. 雞舍自發同步收件人過濾（W26，server，預設 enforce）

### 問題與手術範圍（2026-09-09）

`IsoHutch.update()` 內有兩處 `sync()`：髒污增加，以及週期／舍內數量變化；後者使用 3.5 秒計時器。原版沿 `sync()` → `sync(0)` → `IsoObject.syncIsoObject`
對全部連線重複序列化並傳送雞舍狀態，包含巢箱內每顆蛋的完整資料。遠端 client 尚未載入該格時，`GameClient.SyncIsoObject` 直接丟棄這份更新。

- 在既有 W17 的同一個 `IsoHutch` ClassPatch 內，將 `update()` 兩個呼叫同形改道
  `HutchSyncGate.syncUpdate`；不新增第二份 ClassPatch 覆蓋 W17。
- **只改這兩個呼叫**：`updateAnimalInside` 等其他四個 caller 的五處 `sync()` 全部保留；不改通用 `IsoObject` 廣播、玩家操作／remote relay、地圖初載或存檔格式。
- 仍依原連線順序執行 `startPacket` → `doPacket` → 原 `syncIsoObjectSend` → `send`；不切割、快取或重寫蛋資料。所有收件人都被略過時，仍執行原 `flagForHotSave()`。
- off、非 dedicated 路徑、雞舍子類及無效物件回原 `sync()`；序列化／送包例外不被 helper
  catch，保留原版中斷順序。新增範圍檢查若拋 RuntimeException，只讓該連線保守直通。

### 收件邊界與傳送過渡期

僅對可信奇數 `chunkGridWidth ∈ {13,15,17,19}` 過濾。半徑取 **整窗寬 `W*8` squares**，不是 W13 的共同半寬下界；以所有四個 player slot 的 server 實體位置與原版 `RelevantTo`
位置取聯集，任一在窗內就送。這不是 client loaded-set 的精確判定，而是刻意寬裕的遠距排除。

未 fullyConnected、clamp／偶數寬度、換角或 split-screen 加入中的 `connectArea`、缺失／非有限位置、死亡／無所在格、未知 slot 等狀態一律放行。任一角色目前在載具內或
noclip，也讓整條連線放行。

**傳送不能靠短 TTL 猜轉場完成。** 另將 `TeleportPacket.write` 的唯一 `PlayerID.write`
同形改道 `writeTeleportPlayer`：送出前把原角色實例記進同步保護的 weak-key map，該角色本次生命週期保留全量雞舍廣播。身分不是 online ID，換角後不會把豁免帶給新實例；map 也不保留已失去其他參照的角色。沒有額外 ACK、loaded-set 或背景執行緒。

傳送簿記若拋 RuntimeException，設 `disabled=true`，整把雞舍過濾回原廣播；原
`PlayerID.write` 仍在 catch 外原樣執行。`LinkageError` 等 Error 不被吞掉。代價是被傳送過的角色仍收遠端更新；不以更激進的排除交換漏送風險。

2026-09-26 起判定程式碼（含 teleport 豁免名單與簿記故障的全域降級）移到共用的
`RecipientWindow`，W36 GameEntity 廣播沿用同一份判定（見 2ay）；雞舍的行為與計數不變。`TeleportPacket.write` 的改道目標隨之改為 `RecipientWindow.writeTeleportPlayer`。`hutchSyncGate` 與 `gameEntityRelevancy` 都為 0 時才不記 teleport 豁免。

### 模式與觀測

`-Dmdc.hutchSyncGate`：未設定＝`1`／`enforce`，`2`／`observe` 只記判定但照送，`0`／`off` 回原版；未知值保守落到 observe。**需重啟才套用模式**。

首次有效同步及每 5 分鐘輸出 `[MinidoracatJavaPatch][HutchSync]` 累計值：

- `calls`＝有效雞舍同步次數；`considered/sent/skipped`＝逐連線考慮／實際送出／略過次數。
- `wouldSkip`＝符合排除條件的連線次數。`sentBytes` 是實際序列化並送出的 PZ packet bytes，**不含 RakNet／UDP 開銷，也不是 client 收到的證據**。
- `wouldSkipBytes` 只在 observe 真正寫出被判定可省的封包時累加；enforce 不序列化被排除者，因此此值為 0 不代表沒有節省。
- `passthrough/exempt`＝不確定／豁免判定次數；`scopeErrors/logErrors` 應為 0、`disabled` 應為 false。off 不輸出本刀 heartbeat。

### 守門與驗收

SmokeCheck 鎖兩個 update 呼叫的語境、全 class 七處原 sync 分布、同形手術前後真指令數、原版 server 廣播分支與 helper 四步順序／hot-save、其餘 caller 和 load/save/收發負對照；另外鎖 TeleportPacket 的唯一 PlayerID 寫入、原 ID write 在 catch 外、XYZ／parse 不變，以及 `PlayerID.set/getPlayer` 保留同一角色實例的前提。TIS 更動這些前提時必須重新評估。

本機 enforce／observe／off 三個獨立 JVM 驗證：窗界與位置聯集、split-screen、不可信狀態／載具／noclip／傳送豁免、移近後收到最新狀態、零收件人仍 hot-save、受精蛋資料與原版逐位元相同、序列化與送包例外外逃、簿記失敗後持續直通。另以拋棄式程式直接跑 dist 的 `IsoHutch.update` 兩掛點及 `TeleportPacket.write`：原版會向遠端多送而失敗，手術後近端照收、遠端略過、傳送後即使 server 位置未變仍收到。完整 build 與兩輪安裝往返通過；**這些不是實際玩家端 UI 驗收**。

線上驗收需另外確認：正確包已載入、mode=1、上述異常指標為零、`skipped` 確實增加；再對照雞舍 `SyncIsoObject` 的封包流量，以及靠近／重入地圖、開關門、取蛋與傳送後的同步。不能由 `skipped` 比例直接宣稱相同比例的全服頻寬或 FPS 改善。

---

<a id="2ao"></a>
## 2ao. RequestData ACK 迴圈邊界（W27，server）

> **退役（2026-09-28，42.21.0 官方已修）**：patch notes「Fixed incorrect for loop condition in ACKWasReceived」。javap 42.21 offset 15 由 `if_icmpgt 64` 變為 `if_icmpge 64`，其餘 0–85 指令、運算元、跳轉目的地逐行相同——與本刀產物逐指令一致，空佇列／查無連線／重複 ACK／RequestID 不符四種邊界語意等價。PatchConfig 段、SmokeCheck
> 兩條斷言、`RequestDataAckTest` 與 build.ps1 步驟刪除；`Patcher.IntComparisonChange` 失去唯一使用者，一併移除。復活：`git checkout 8d2bee8 -- patcher/src/Patcher.java patcher/tests/request-data-ack`（並回填 PatchConfig／SmokeCheck／build.ps1）。

`RequestDataManager.ACKWasReceived` 用 `i <= requests.size()` 逐項查找連線；空佇列或查無該連線時必定存取 `get(size)` 而拋 `IndexOutOfBoundsException`。修正只把該方法唯一的 `IF_ICMPGT` 改成 `IF_ICMPGE`，即 `<=` 改 `<`。指令長度、堆疊、branch target、frames 不變，逐方法命中恰 1，沒有 runtime helper。

未更動 RequestID 比對、ACK 協定、傳送窗口、資料內容、連線生命週期，也不攔截傳送例外。這是已完成／已移除請求之遲到 ACK 的邊界修正，**不是製作卡讀條或整體下載管線重設**。不另加 runtime 開關；回退使用既有整包 uninstall，且不得在 JVM 執行中刪除 helper。

**驗證**：原版真類別在空佇列重現越界；修正後測試空佇列、未知連線、錯誤 RequestID、有效 ACK 續傳 `packSize + 17` bytes 的逐位元對帳、完成後重複 ACK，以及原送出例外 identity。SmokeCheck 同時鎖住 `i=0 → i/size 比較 → get(i)` 語境，並比對整個方法只有該 opcode 改變。官方改掉此迴圈時須重新評估撤刀，不得放寬守門硬套。

---

<a id="2ap"></a>
## 2ap. PopMan 缺格生成與背景存檔互斥（W28，server，預設 on）

> **退役（2026-09-28，42.21.0 官方已修）**：42.21 的 Java 側兩個缺格 fallback 仍在 `saveLock`
> 外呼叫 `n_addZombie`（desc 改為 `(FFFBIIIII)V`，多了 persistentId），但鎖的另一端已不再碰同一個池：native 反編譯（`libPZPopMan64.so` 42.21）顯示 `ManagerWorker::saveCell` 不再動
> `ManagerMain::m_zombiePool`。舊版把 `saveRealZombieHack` 的 `Zombie*` 推回 Main 池的路徑，以及
> `n_saveRealZombies`／`beginSaveRealZombies`，都已刪除。Main 池只剩主執行緒的 `n_addZombie`／`n_registerZombie`／`n_getAddZombieData` 與 `ManagerMain::stop`，worker 端 `addRealZombie`
> 以 id 去重，關機 double free 的前提不再成立，補鎖已無可保護的對象。改道、`PopManAddLock`
> helper、SmokeCheck W28 全部斷言與 `PopManAddLockTest` 一併移除；復活：`git checkout 8d2bee8 -- <檔案>`。**上線後**以關機 log 觀察是否仍出現 `double free`／`ObjectPool::clear` 相關 crash；若再現，代表損毀另有來源，需重開調查而不是直接復活本刀。以下原文保留當歷史。

`ZombiePopulationManager.addZombieStanding`／`addZombieMoving` 在方格未載入時直接呼叫
`n_addZombie`，卻沒有取得既有 `saveLock`；背景 `processPendingSaveCells` 在該鎖內執行
`n_saveRealZombies`／`n_saveCell`，兩側可並行改動同一 native 物件池。真 jar 的五個 `n_addZombie` 呼叫中，另三個位於已持鎖的 `removeChunkFromWorld`／`virtualizeZombie`。

**事故證據與界線**：core 證實 `ObjectPool<popman::Zombie*>::clear` 對相鄰兩格中的同一指標連續 delete，觸發 `double free`；Main／worker 兩池均有重複指標，且存在跨池共用。core 沒有重複入池當下的執行時序，故不能宣稱本次兩處缺鎖已解釋所有損毀來源；這是已確認競態缺口的根因修復，不是在停止流程吞例外或去重 free。

**手術**：僅將上述兩個 fallback 的 native 呼叫同形改道到 `PopManAddLock.addZombie`，沿用同一 `saveLock`，只在 native 呼叫周圍 `lock`／`finally unlock`。快取的 private `MethodHandle` 保留原 native 名稱、可見性與 descriptor；解析失敗明確外傳，原生 `Throwable` 原物件穿透。其他方法、存檔格式、停止流程與官方 native library 不改。代價是兩個 fallback 遇到背景存檔持鎖時必須等待；不新增鎖或改變原有鎖順序。

`-Dmdc.popmanAddLock=0`／`off` 停用補鎖，但仍委派原 native；未設定預設 on，需重啟生效。`[MinidoracatJavaPatch][PopManAddLock] lock=true` 於 helper 首次初始化印出，不是保證每次開機立即出現，也不代表歷史 crash 已經重現並排除。

**驗證**：SmokeCheck 鎖五處 native 呼叫普查、兩處精確改道、其餘方法及 frames/maxs 不變。`PopManAddLockTest` 以真 caller／真鎖／真 helper，僅替換 native body 與最小世界 fixture，驗證 on 互斥、off／vanilla 無互斥、三類例外原物件與解鎖，以及重入後持有數不變。移除 lock 或 unlock 的隔離 mutant 均立即失敗而不掛住。這些回歸證明 Java 互斥契約，**不等於重播真 native 物件池損毀**；線上仍須觀察後續存檔與停止事件。

---

<a id="2aq"></a>
## 2aq. 動物同步接收驗證（W29，server，預設 enforce）

強化伺服器端驗證：不合法的動物同步請求在修改任何遊戲狀態前整包拒絕；正常請求保留既有授權、解析與處理流程。可靠／不可靠兩種同步都涵蓋，不改客戶端或傳輸格式，玩家不需安裝額外檔案。

手術只在既有 `GameServer` ClassPatch 加入一個接收呼叫改道至 `AnimalUpdateGuard`。不在原版解析後才攔截，以免共用封包物件的殘留狀態繼續被處理；拒絕不拋普通例外，也不自行踢人或封鎖帳號。正常及非目標封包的原版例外仍原樣穿透。

`-Dmdc.animalUpdateGuard=0`／`off` 回原版；未設定及未知值均 enforce，沒有 observe 模式，需重啟生效。**關閉會重新暴露已確認的驗證缺口**，不應只為了減少紀錄而關閉。只有拒絕時輸出 `[MinidoracatJavaPatch][AnimalUpdateGuard]` Warning，每個 60 秒窗最多三行；`blocked` 為拒絕總數、`suppressed` 為限頻略過的紀錄、`logErrors` 為紀錄路徑例外。紀錄故障不會放行請求；不保存 raw payload 或玩家名稱，也不由單筆紀錄推定動機。

**驗證**：原版真接收入口的狀態保護反例失敗；新包保留雞舍及巢箱成員資格，涵蓋正常 client writer、buffer 邊界、共用封包狀態、明示回退、未知設定、限頻及真 logger 故障。SmokeCheck 鎖唯一接收入口、精確同形改道、上游協定指紋與 client 入口不變。本機完整建置與兩輪隔離安裝／移除通過；不代表已部署或已證明歷史動物遺失的原因，也不會自動復原既有存檔。

**2026-09-28 42.21 對版**：驗證缺口在 42.21.0 仍在，上行 wire 與 client writer 逐指令未變，helper 不改（缺口細節另以私下管道回報 TIS，不在公開文件描述）。42.21 對這個類別唯一的語意差是 client 分支的 `removeFromSquare()` 後多 `setSquare(null)`；原本的整類 SHA 因此與行號位移一起誤報。SmokeCheck 指紋改為略過 debug 資訊（行號、區域變數、SourceFile）的整類文字雜湊，仍涵蓋 `@PacketSetting`、欄位與全部方法，值更新為 42.21。

<a id="2ar"></a>
## 2ar. 容器大批物品登記（W30，server，預設 on）

`ItemContainer.addItemsToProcessItems` 內唯一的批次登記呼叫，同形改道至
`BulkItemRegistration`；疊加在既有 ItemContainer ClassPatch，不覆蓋 W5／W5-2。不替換 `IsoCell`、原始清單實例或單件登記 API，不建立跨呼叫索引。

原版對批次每件物品線性查找既有清單。本刀只在既有清單至少 4096 件、批次至少
256 個非 null 元素時，暫時索引批次 identity、掃描既有清單一次，再依原順序撤銷待移除並補入缺席物品。輸入／既有清單須為 exact ArrayList、移除集須為 exact HashSet、清單不得別名；物品須沿用 Object.equals/hashCode 且非 Comparable。任何資格不符均在修改遊戲狀態前整通回原版，保留自訂回呼及壞元素之前的前綴副作用。可選索引配置失敗會明示警告後回原版；不承諾任意並行修改清單的等價性。

`-Dmdc.bulkItemRegistration=0`／`off` 停用，其餘值與未設定均啟用，需重啟。SmokeCheck 鎖 IsoCell final、原批次方法與純 getter 指紋、唯一改道及 frames；行為差分涵蓋順序／identity、重複、移除撤銷、raw 錯誤、自訂回呼、外部同大小修改，另以強制 hash 碰撞 JVM 演練 Comparable tree-bin 回呼。這是條件式加速；實際大容器分布與命中頻率未量測，不能由微型測試推算整服 FPS。

**2026-09-27 退役**：W45（2bh）把 `processItems` 換成帶身分索引的清單，`contains` 變 O(1)，原版逐件迴圈即為 O(M)，本刀的暫時索引不再有收益；正式服本就幾乎不觸發（清單 ≥4096 且批次 ≥256）。改道、helper（`BulkItemRegistration`）、SmokeCheck 契約與行為測試一併移除，ItemContainer 剩 W5（2）＋W41（2）共 4 個命中點。要復活請從 git 歷史取回。

<a id="2as"></a>
## 2as. 魚群廣播內容共用（W31，server，預設 on）

`FishSchoolManager.updateSeed`／`updateFishingData` 內的兩個廣播呼叫各改道一次至
`FishingDataBroadcast`。不改 `GameServer.transmitFishingData` 原方法、單連線回覆、client 或 wire 格式；每位收件人的 header、鎖與送出流程保留。

只從第一個真正完成的 body 取快照，供同批後續收件人使用，不提前序列化、不跨批快取。連線清單須為 exact ArrayList，兩張來源 map 須為原版 exact Trove 型別；自訂清單整批直通原版，自訂連線或傳到本層的例外會停止剩餘共用。byte order／容量不符時該封走原逐欄位寫入，保留部分寫入後失敗的行為。快照 OOM 只放棄額外共用、印警告，當下已完成的封包仍照送。原生 Send 內部吞掉的失敗，以及原生 Error 留住 sendLock 的既有缺陷均未改動。共用依賴同一主執行緒更新，不保證任意並行修改來源資料的等價性。

`-Dmdc.fishingDataBroadcast=0`／`off` 停用，其餘值與未設定均啟用，需重啟。SmokeCheck 鎖兩個 caller 的精確改道、原廣播與 lambda／decoder 指紋、ByteBufferWriter final，並確認 FishSchoolManager 其他方法未變。永久回歸使用真 Java writer／send／client decoder，只替換 native send 葉子並注入故障；比較逐收件人位元組、解析結果、例外與鎖，涵蓋自訂連線清單在兩封之間修改來源的反例。

**驗收邊界**：候選曾以正常 Steam client 驗證實際魚群狀態更新與玩家物品往返；正式整合包另做完整本機建置及隔離安裝／移除。多收件者共用目前仍是離線真 Java
封包管線證據，不是多客戶端實機或正式服收益驗收。兩刀只加入 server 出包，完成本機出包不代表已部署；正式安裝／回退與重啟仍須遵循既有維護流程。

**2026-09-28 42.21 對版**：`transmitFishingData`、兩個 lambda、兩個 caller 與 client
`receiveFishingData` 逐指令未變（收件人仍是全部連線、body 欄位順序相同），helper 的共用 body 仍逐位元等價，不改碼。原 SmokeCheck 的三條 FAIL 只是 `GameServer.java` 行號位移；契約指紋改為略過 debug 資訊的
methodText 雜湊，42.20.4 與 42.21 算出同值。

<a id="2at"></a>
## 2at. 聲音封包慢呼叫觀測（server，預設 observe）

只在既有已認證封包派送器中包住 `WorldSoundPacket.processServer`；其他封包、授權／解析順序、原 wire class 與聲音／魚群資料不變。`MdcWorldSoundProbe` 與封包同 package，直接讀原始 radius／volume，不用反射。完整處理時間包含聲音建立、Lua 事件、魚群掃描及後續轉送；不能把總耗時直接當成其中一段。

- `slowCall`：單包耗時至少 100ms，記原始半徑、音量、成功／失敗與觀測批次序號。
- `slowBatch`：兩次 `ServerMap.preupdate` 掛點之間累積至少 100ms，記筆數、總耗時、最大單包耗時與最大半徑；下一掛點結算，即使沒有後續聲音也不漏最後一批。`batchSeq` 是觀測器自己的批次序號，不是原版 frame 號；結算行會晚於被量測的批次。
- 兩類明細共用每 60 秒最多 3 行的額度；超額累計 `suppressed`。有新觀測時每 300 秒輸出累計 heartbeat，含 calls／slow／failed／耗時／半徑／批次與 logErrors。初次有呼叫後可立即輸出第一份 heartbeat。log 不記玩家名或座標。
- 既有看門狗的凍結快照附上 in-flight 半徑、音量與持續時間，未返回的慢包也能留證。volatile 發佈、reader acquire fence 與序號重驗避免跨封包拼接；累計欄位是診斷讀值，非原子快照。不另開執行緒；看門狗停用不會停掉本觀測器的批次結算。

主迴圈執行緒的最外層派送才計時；nested 的耗時包含在外層，不重複計算。武裝前及外來執行緒直通原版，分別計 unarmed／foreign。原方法恰好執行一次，不改參數、不 clamp、不重試。診斷 RuntimeException 只計 logErrors；Error 刻意 fail-fast，若診斷 Error 與原例外同時發生，仍可能由 finally 的 Error 取代原例外。`-Dmdc.worldSoundProbe=0`／`off` 停用，其餘值與未設定均 observe，需重啟。

驗證包含完整建置／結構守門、observe／off／unarmed／真 logger 故障四組態，原 RuntimeException／Error 身分、巢套／外來執行緒、限流及無後續封包的批次結算。累積門檻以注入完成樣本避免 sleep 上界假設；另以真原版零半徑封包走整合派送煙霧。這些不是實際慢包的線上驗收；生效後須核對 banner，並把 slowCall／slowBatch 與同時段堆疊對照。

**2026-09-28 42.21 對版**：派送 bridge 精簡（§2aj 末）後，`WorldSoundPacket` 仍由同一個 `MdcTimedActionProbe.processServer`
交給本觀測器，呼叫點與行為不變（SmokeCheck「只由既有派送器呼叫一次」照舊）。

<a id="2au"></a>
## 2au. 動物離線補算觀測（W32，server，預設 observe）

**事故（2026-09-24）**：同一個玩家牧場兩次在 chunk 重新載入時出事——
13:01:38（玩家斷線重連）與 14:22:13（另一玩家高速抵達）各凍結主迴圈約 13 秒，隨即同秒 6 隻動物死亡（`user Bob died`），地上大量糞便，Cleaner 同時記到豬群 `pregnancies=12 unborn=140`。兩張 MainLoopWatchdog 快照同為 `ServerMap.preupdate → IsoChunk.doLoadGridsquare →
AnimalPopulationManager.addChunkToWorld → AnimalManagerMain.fromWorker →
IsoAnimal.updateStatsAway → AnimalData.hourGrow`，全是原版路徑。

**待證假說**：原版以 `worldAgeHours - zone.hourLastSeen` 當離線時數，逐小時執行
`hourGrow`、配種、生蛋與掠食判定。`DesignationZone.hourLastSeen` 只在 zone 兩角都離開串流時更新；大圍場橫跨多個 chunk，只要角落 chunk 持續載入，中間 chunk 卸載重載就會拿到陳舊值，每次重載都重補數天。動物自身的 `timeSinceLastUpdate`（`unloaded()` 寫入）才是真實離線時間，本刀拿它當對照。

**手術**：全 jar 三個 `IsoAnimal.updateStatsAway(I)V` 呼叫點 1:1 改道 `AnimalAwayProbe`——
`AnimalManagerMain.fromWorker` ×1（source=chunk）與 `DesignationZoneAnimal.doMeta` ×2（source=zone，同樣用 `hourLastSeen`）。helper 委派前先讀動物自身時數，原版恰好執行一次，不改時數、不 clamp，例外原樣穿透。

- 明細：zone 時數 ≥ `-Dmdc.animalAwayProbe.detailHours`（預設 24）逐筆記
  source／hoursAway／animalHoursAway／worldAgeHours／耗時／是否死亡／動物型別與座標／zone id、名稱、hourLastSeen、rect、streamed；每 60 秒最多 30 行，超額計 `suppressed`。
- heartbeat：有呼叫時每 5 分鐘最多一行，含 calls／big／mismatch（zone 時數比動物自身多 ≥24h）／died／max／sum／totalMs／maxMs／anomalies。
- `-Dmdc.animalAwayProbe=0` 停用（純直通），需重啟。

**判讀**：`mismatch` 持續成長、明細中 hoursAway 遠大於 animalHoursAway＝假說成立，再另案出 enforce（改用動物自身時間或設上限）。兩者相近＝離線補算本來就這麼長，需改查 hourGrow 本身。SmokeCheck 釘三個呼叫點 census、兩個方法同形改道，以及原版讀 `DesignationZone.hourLastSeen` 的事實（TIS 改用動物自身時間時該條會紅＝重估本刀）。`AnimalAwayProbeTest` 覆蓋 observe／off、陳舊 zone、無時間戳、zone 路徑與例外穿透。

<a id="2av"></a>
## 2av. 分娩品種守衛（W33，server，預設 on）

**事故（2026-09-24 18:03:06）**：`AnimalData.checkPregnancy → IsoAnimal.addBaby` NPE（`getData()` is null）。原版以母獸品種名查幼崽定義，`getBreedByName` 查不到時回 null，卻仍直接交給 `IsoAnimal` 建構子，產出 data／adef 為 null 的幼崽並已進入世界。之後約 2 分鐘
`IsoAnimal.update` NPE 1289 次，每次打斷該 tick 的 `IsoCell.ProcessObjects`；關機前兩次 `AnimalPopulationManager.save` 也因它 NPE 中斷。母獸位置當時未記錄。

**手術**：`checkPregnancy` 內唯一 `addBaby()` 1:1 改道 `BabyBreedGuard.addBaby`。先做原版同一組查詢（`getDef(babyType)`、母獸品種、`getBreedByName`），任一環為 null 就不生這一隻、回 null（caller 丟棄回傳值），並記母獸型別／ID／品種／babyType／座標（前 64 筆）；否則委派原版，行為不變。`babyType` 為 null 仍交原版（原版自己回 null）。全 jar 另有 3 個生成故事呼叫點（ranch、遷徙群、拖車故事）刻意不動。kill switch `-Dmdc.babyBreedGuard=0`，需重啟。

SmokeCheck 釘全 jar 4 個呼叫點、`checkPregnancy` 同形改道，以及原版 `getBreedByName`
結果直接進建構子的事實（TIS 補檢查時會紅＝撤刀）。`BabyBreedGuardTest` 以真
`AnimalDefinitions` 查詢覆蓋 on／off、品種不符、幼崽定義缺失、babyType null 與例外穿透。驗收：`[BabyBreedGuard] skip birth` 出現時對照該品種來源 mod；`IsoAnimal.update` 的
`adef is null` NPE 不再出現。

**2026-09-26 更正**：上面「null 品種→data null」的歸因不成立，真因是建構子檢查失敗（見 2az）。

<a id="2aw"></a>
## 2aw. 伺服器角色聲音參數跳過（W34，server，預設 observe）

**依據（2026-09-25 晚峰 JFR，5 分鐘，主執行緒 13,966 取樣）**：224 取樣（1.6%）落在
`IsoGameCharacter.updateEmitter → FMODParameterList.update → ParameterFootstepMaterial*`。server 上實際走到 `updateEmitter` 的只有動物：`IsoAnimal.update` 每 tick 呼叫兩次（反編譯
`IsoAnimal.java:388,453`），`AnimalPopulationManager` 卸載後另補呼叫；`IsoPlayer` 有
`!GameServer.server` 守衛，殭屍在 server 不進 `MovingObjectUpdateScheduler` bucket。動物在 server 註冊 FootstepMaterial／FootstepMaterial2（`IsoPlayer.initFMODParameters`，無 server
守衛），計算時走訪格子全部物件、查 `FOOTSTEP_MATERIAL` 屬性、`Enum.valueOf`。

**為何是白工**：server 的 emitter 一律是 `DummyCharacterSoundEmitter`（`IsoGameCharacter.java:781-783`），沒有 FMOD event instance。`FMODParameter.update` 算出的值只經 `setCurrentValue` 迭代
`FMODLocalParameter.instances`（server 恆空）與 `startEventInstance`（server 不發生）送出，無其他讀者。

**手術**：`updateEmitter` 內唯一 `FMODParameterList.update()` 1:1 改道 `EmitterParamGate.update`（併入既有 W7 的 `IsoGameCharacter` ClassPatch；同 ClassPatch 的 W22 已於 42.21.0 退役）。三態 `-Dmdc.emitterParamGate`：`2|observe`（預設，照常計算，每 16 次取樣計時一次）、`1|enforce`（`GameServer.server` 時跳過）、`0|off`（純直通）；未知值落回 observe。需重啟。每 5 分鐘一行
`[EmitterParamGate] mode= server= calls= skipped= sampled= avgNs= estSavedMs= anomalies=`。

SmokeCheck 釘 vanilla 呼叫點 1（class-wide 1）、手術後改道 1／原呼叫 0／真指令數不變，以及 helper
零 NEW、零 DebugLog、跳過條件讀 `GameServer.server`。`EmitterParamGateTest` 三個 JVM 覆蓋
observe／enforce／off：非 server 一律計算、server＋enforce 完全不計算、取樣計時、委派例外穿透。**開 enforce 的判準**：observe 心跳的 `estSavedMs` 換算約佔主執行緒 1% 以上，且 `anomalies=0`。

**同一份 JFR 的其他結論**：`RBTrashed.trashHouse`／`isKidsRoom` 0 取樣、`requestSaveCell` 8 取樣、主執行緒 >20 ms `ThreadPark` 0 筆、5 分鐘 deopt 85 次——pzopt 的 kidsRoomMemo、saveCellAsync
與 JVM `jitSteady` 皆不立案。新熱點另案：`UsingPlayerUpdateSystem.update` 5.9%（每幀全掃 IsoObject
entity bucket，只為清掉離開 10 格的 usingPlayer）、`WorldSoundManager.getSoundAttractAnimal` 標記
4.7%（行號落在單純算距離的第 372 行，推論實際成本是前一個呼叫 `getSoundAnimal` 對全域
soundList 的逐動物線性掃描，因為沒開 `DebugNonSafepoints` 所以歸屬不精確）。

<a id="2ax"></a>
## 2ax. 使用中玩家索引（W35，server，預設 observe）

**依據（同一份 2026-09-25 晚峰 JFR）**：主執行緒 5.9% 在 `UsingPlayerUpdateSystem.update`，熱點落在迴圈裡讀 `getUsingPlayer()` 那行（jar 行 35＝bytecode 44）。原版每幀掃過 IsoObject bucket 的全部 entity，只為把「使用中玩家已離開 10 格／換層／死亡」的 `usingPlayer` 清成 null；真正有 usingPlayer 的 entity 極少，成本是逐一讀取每個 entity 的記憶體存取。

**手術**：`GameEntity.usingPlayer` 是 private，全 class 恰 7 個 putfield——`setUsingPlayer` 1、`receiveUpdateUsingPlayer` 3、`receiveSyncEntity` 2、`reset` 1（只寫 null）。
- `setUsingPlayer` 頭部 headCall（slots 0、1，帶新值）、兩個 receive 方法每個 RETURN 前 tailCall（讀寫入後的值）；寫成非 null 就記入 `MdcUsingPlayerIndex` 的弱參照集合（不延長 entity 生命週期）。
- `UsingPlayerUpdateSystem.update` 內唯一 `EntityBucket.getEntities()` 1:1 改道：enforce 時回傳「集合中仍有 usingPlayer、有 component 且 bucket bit 在」的精簡陣列；usingPlayer 已是 null 的就移出集合。原版對每個 entity 的處理互相獨立，不在集合中的 entity 原版也不會做任何事，結果相同，只有處理順序不同。
- 自我稽核：每 256 次呼叫全表數一次「有 usingPlayer 的 bucket 成員」，多於索引＝遺漏（`missed`）。enforce 出現遺漏就永久退回原版全表並記一行 `MISSED`。

三態 `-Dmdc.usingPlayerIndex`：`2|observe`（預設，回原版全表，只追蹤與稽核）、`1|enforce`、`0|off`（連追蹤都關）。需重啟。每 5 分鐘一行 `[UsingPlayerIndex] mode= calls= bucket= active= activeMax=
audits= missed= fellBack= anomalies=`。**開 enforce 的判準**：observe 一個晚峰以上 `missed=0`，且 `bucket` 遠大於 `activeMax`。

SmokeCheck 釘 usingPlayer 為 private、putfield 7 個的分佈（TIS 新增寫入點時紅＝索引會漏）、update 改道同形且真指令數不變、GameEntity 追蹤點 1＋1＋2。`MdcUsingPlayerIndexTest` 以真 `IsoObjectBucket` 與已 patch 的 `setUsingPlayer` 覆蓋三模式：enforce 只回使用中且在 bucket 的 entity、清成 null 後移出、繞過追蹤點的寫入被稽核抓到並退回全表。

---

<a id="2ay"></a>
## 2ay. GameEntity 廣播收件範圍＋CraftLogic 同步變化閘（W36，server，預設 enforce）

**依據（2026-09-26 03:11–03:13 正式服抓包，internal-analysis
`reports/ops/2026-09-26-MIC42-stamp-traffic.md`）**：PacketType 293 `GameEntity` 佔出向 UDP
**17.5%**（30 秒、9,589 包、9.41 MiB；只計含 anchor 的封包，屬下限）。一分鐘內 107,641 個追蹤章只有 96 種值，最常見的值 39,159 次、送給全部 20 名在線玩家；所在物品是曬草架上的 `Base.DryGrass`。

**原版路徑（42.20.4）**：自動工作站製作中，`CraftLogic.onUpdate` 每 1000 ms（`UpdateLimit(1000L)`）呼叫 `sendCraftLogicSync`，以 `save` 序列化整份 CraftLogic（含製作中輸入物品與 modData）→
`Component.sendServerPacket` → `GameEntityNetwork.sendPacketData(..., isIgnoreConnection=true)` →
`INetworkPacket.sendToAll`，不看距離全服廣播。IsoObject 的 entityNetID 由所在格座標＋object index
組成，client 端 `GameEntityManager.GetEntity` 查不到就直接丟棄（只有 InventoryItem 有擁有者背包的
fallback），沒載入該格的 client 收到也沒有用。

**手術**：

1. `GameEntityNetwork.sendPacketData` 內唯一 `INetworkPacket.sendToAll` 1:1 改道
   `GameEntityBroadcastGate.sendToAll`（descriptor 相同）。處理的是整個 server 廣播出口：CraftLogicSync、SyncGameEntity、UpdateUsingPlayer，以及其他 Component 的 `sendServerPacket`。helper 保留原版的連線順序、排除連線 GUID 與 `isFullyConnected` 條件，逐連線呼叫原版 `INetworkPacket.send`；只有位置可信的實體（有所在格的 IsoObject、以車輛座標定位的 VehiclePart）才判定是否略過。InventoryItem（未裝備時 `getX` 回 `Float.MAX_VALUE`，client 依擁有者背包查找）、MetaEntity、非有限座標一律回原版全服廣播。
2. 收件判定沿用 W26：原 `HutchSyncGate.shouldSend` 的判定與 teleport 豁免名單抽成共用的
   `RecipientWindow`（行為逐項不變）。收件窗是整窗寬 `W*8`，server 實體位置與原版 `RelevantTo` 取聯集；非可信奇數寬度、`connectArea`、換角、死亡等不確定狀態，以及載具內、noclip、送出過 teleport 的角色一律照送。這個窗約是 client 載入半寬（最多 `(W/2)*8+7`）的兩倍。
3. `CraftLogic.onUpdate` 內唯一 `sendCraftLogicSync` 改道 `MdcCraftSyncGate.periodicSync`：只在 client
   看得到的內容變化時才送。比較的內容是每筆 in-progress 的整數百分比（原版 tooltip 與 overlay 同一算式
   `(int)(getProgress*100)`）、in-progress 清單的筆數與身分、目前配方（封包尾的配方名），以及
   DryingCraftLogic 顯示用的濕度（`%.0f%%`，另分出「大於 0 即暫停」）。owner 身分也納入，pool 重用的 component 不會沿用舊基準。helper 放在 crafting package，以讀取 package-private 的清單；濕度只存在 private `temporaryWetnesses`，以快取的反射讀取。
4. `CraftLogicSystem.stop` 內唯一 `sendCraftLogicSync` 改道 `explicitSync`：照送，並把送出的內容記為基準。少了這步，「[A]@0% 已送 → 取消 → 同一個 A 以 0% 重新開始」會被週期路徑誤判為已送。原版 `onStart` 本來就不送，開始製作由下一次週期同步以清單變化送出。
5. 簽章算不出來（資料不完整、濕度欄位讀不到）就照原版送，並清掉該 CraftLogic 的基準。

封包格式與序列化都不變，玩家不需要安裝任何東西。

### 遠方玩家進入範圍時的狀態（42.20.4 反編譯＋javap）

`PlayerDownloadServer.update` 對 server 已載入的 chunk 以 `IsoChunk.SaveLoadedChunk` 即時序列化，不是讀磁碟檔：`IsoObject.save → saveEntity → Component.save`，`CraftLogic.save` 在製作中寫出
in-progress 清單（含 elapsedTime），`DryingCraftLogic.save` 追加每筆濕度；client 由
`IsoObject.load → loadEntity → CraftLogic.load → loadInProgressCraftData` 還原。因此剛下載該 chunk 的
client 拿到的就是下載當下的狀態，**不需要補送**。步行玩家在 chunk 進入載入範圍之前早就在收件窗內，之後的每一次變化同步都會收到。SmokeCheck 釘住這條鏈，TIS 改掉任一環就要重新評估。

### 代價與殘留

- client 不跑 CraftLogicSystem，進度只來自同步，所以 client 看到的進度與剩餘時間改成 1% 一跳。DryGrass（`time = 86400`，一個遊戲日）在本服 1 小時的遊戲日下，1% 約 36 真實秒，tooltip 的剩餘時間最多落後這麼久。server 端製作時序完全不變。
- 被略過的期間 client 仍持有該格的情況（載具前移的載入區、releventPos 延遲）：載具與 teleport 已豁免；其餘只會落後到下一次內容變化或重新下載 chunk。
- server 未載入、從磁碟送出的 chunk 與原版相同，不在本刀範圍。
- CraftLogicSync 仍是整份 save（輸入物品 modData 照送），也仍然早於 Lua 的 `luaCallOnUpdate`；本刀只減少次數與收件人。
- 簽章只看上述內容，不看輸入物品本身。若配方或 mod 在製作中的 Lua OnUpdate 改動輸入物品（例如 modData），client 要等下一次內容變化才看到。

### 模式與觀測

`-Dmdc.gameEntityRelevancy`、`-Dmdc.craftLogicSyncGate` 各自三態：未設定＝`1`／`enforce`，`2`／`observe` 只計數照送，`0`／`off` 純委派；未知值落到 observe，需重啟。

首次有效呼叫及每 5 分鐘各一行：

- `[MinidoracatJavaPatch][GameEntityBroadcast] mode= calls= unpositioned= considered= sent= skipped=
  wouldSkip= sentBytes= wouldSkipBytes= craftCalls= craftWouldSkip= passthrough= exempt= scopeErrors=
  logErrors= disabled=`。bytes 是 GameEntity body（12 bytes 標頭＋EntityPacketData 內容），不含 3 bytes
  封包型別與 RakNet／UDP 開銷；payload 在送出前就已組好，enforce 下 `wouldSkipBytes` 就是沒送出的量。
- `[MinidoracatJavaPatch][CraftSyncGate] mode= periodic= sent= suppressed= wouldSuppress= explicit=
  sigErrors= wetness=ok logErrors=`。

### 守門與驗證

SmokeCheck 鎖：`sendPacketData` 廣播分支唯一 `sendToAll` 的參數語境（GameEntity 型別、排除連線＝參數 3、values＝{data, entity, component}）與其後的 release；原版 `sendToAll` 只有 GUID 排除＋fully-connected＋send；手術後改道 x1、client 與單連線 send 原樣、真指令數不變；helper 的 send 不在 try 內、範圍判定在 try
內且只 catch RuntimeException；上述 chunk 狀態鏈；`sendCraftLogicSync` 全 jar 恰 2 處（onUpdate 在
`limit.Check` 之後、stop 在 `finaliseRecipe` 之後）、同步內容為整份 save＋廣播出口、DryingCraftLogic
不自送且有 `temporaryWetnesses`；兩處改道 x1 與兩個 class 真指令總數不變；helper 的送出次數、明確同步先送再記基準、基準表為 WeakHashMap。

`GameEntityTrafficTest` 三模式各一個獨立 JVM，跑 dist 內手術後的真 `sendPacketData` 與真
`CraftLogic.onUpdate`：窗內收、窗外只在 enforce 略過、載具與不可信寬度照收、排除連線與未 fully-connected
維持原版、wire 與原版 `sendToAll` 逐位元相同、InventoryItem 維持全服；整數百分比不變不送、跨百分比與清單變長照送、明確同步後同身分同百分比重新開始仍送；配方、owner、濕度顯示變化改變簽章；簽章失敗照送。把 `explicitSync` 改成不記基準的變體會被測試抓到。完整 build 與兩輪安裝往返通過。**以上都不是線上驗收**。

線上驗收：確認正確包已載入、兩把 `mode=1`、`scopeErrors`／`sigErrors`／`logErrors` 為 0、`disabled=false`、`wetness=ok`，且 `skipped`、`suppressed` 會增加。再以 MIC42 報告的抓包方法比較 `86 01 25`（GameEntity）封包佔出向 UDP 的比例（修補前 17.5%，下限），並實地看曬草架：從遠處走近與開車抵達時的
tooltip 進度、下雨暫停與濕度、完成後出貨。不能由 `skipped` 比例直接宣稱同比例的頻寬改善。

<a id="2az"></a>
## 2az. 動物半建構物件守衛＋apop 先序列化再開檔（W37，server，預設 on）

**事故（2026-09-26 10:04–11:10）**：某玩家小型圈舍離線 86 小時後重新串流，原版 `doMeta`
對同一批物件重複補算（同一隻母雞五次、小雞兩次），小雞下一幀進入 `AnimalData.grow`。新成體建構失敗，`grow` 在 `newAnimal.getData().setAge` NPE 808 次，接著 `IsoAnimal.update` 的 `this.adef.turnDelta`
NPE 38,291 次。每次都打斷 `IngameState.updateInternal` 在 `IsoWorld.update` 之後的全部步驟（`UpdateStuff` 內的 `GameTime.update`、Lua `OnTick`、GameEntityManager），`worldAgeHours` 停在 29622
約 65 分鐘。約 10:08 起 7 次存檔都在 `IsoAnimal.save` NPE，`QueuedSaveAll` 後段（SGlobalObjects、GlobalModData、地圖標記等）停在 09:37:14。11:10 模組更新重啟時關機 hook 同一 NPE 死亡，後續有序關機全部跳過，`steamclient.so+0x25aa8dc` SIGSEGV（與 9/16 22:00 同 offset）。該 cell 的 `apop_X_Y.bin` 被截成 0 bytes，下次載入 `newLimit < 0` 失敗後原版 `spawnAnimalsInCell` 重生野生動物，該 cell 原有動物消失。9/16（另一個 cell 的 apop 至今 0 bytes）與 9/24 18:03（2av）是同一型。

**根因（42.20.4 反編譯＋javap）**：
1. `IsoAnimal` 帶座標建構子的 `super()` → `IsoGameCharacter(IsoCell,FFF)` 在座標非 0 時先把物件放進
   cell objectList（safeToAdd）或 addList，之後 `IsoAnimal` 才跑 `checkForChickenpocalypse()`／`checkForWater()`；任一為真就跳過 `init()`，留下 adef／data 皆 null、animalId=-1 的物件。chickenpocalypse 分支呼叫 `delete()`，`removeFromWorld` 會撤掉 addList；**water 分支什麼都不做，物件進世界**。
2. 呼叫端（`grow`、`addBaby`）緊接 `getData()` NPE；`grow` 在 `parent.delete()` 之前拋出，小雞留在世界每幀重試。
3. `AnimalCell.save()` 先 `new FileOutputStream`（截斷）才序列化，序列化只 catch IOException。

**2av 更正**：W33 當時歸因「幼崽品種查不到→null 品種→data null」不成立：`AnimalData` 建構子在品種為 null 時改抽隨機品種（`Rand.Next(0, size+1)`，有 1/(n+1) 機率 IndexOutOfBounds），不會留下
data null。9/24 的 `getData() is null` 同樣是建構檢查失敗。W33 保留為品種不符的保守跳過。

第一次失敗走哪個分支沒有 log 可證（`DebugType.Animal` 預設關、water 分支原版不印任何字）。本刀的
`ctor failed reason=` 會分出 water／chickenpocalypse／noInit。

**手術**：
1. `IsoAnimal` 四個帶座標建構子每個 RETURN 前 TailCall `AnimalSpawnGuard.afterCtor`（RETURN 數 3/3/2/2）：data 為 null 且座標非 0 時，依同一個 `isSafeToAdd` 分支從 objectList／addList 撤出（`IsoGameCharacter`
   加入動作的逆操作）。`(IsoCell)` 載入用建構子走 0,0,0，不碰。
2. `AnimalData.checkStages` 唯一 `grow` 1:1 改道 `AnimalSpawnGuard.grow`；W33 `BabyBreedGuard` 委派原版
   `addBaby` 改經 `AnimalSpawnGuard.addBaby`。只在「本次呼叫期間有建構失敗」時吞 NPE（addBaby 回 null），其餘例外原樣穿透；小雞維持原狀，下次 update 由原版重試。
3. 全 jar 僅有的兩個 `AnimalCell.save()` 呼叫點（`AnimalManagerWorker.save`、`AnimalCell.unload`）改道
   `zombie.characters.animals.MdcAnimalCellSave`：同一把 `SliceBufferLock`、同檔名，序列化成功才開檔。RuntimeException 時保留舊檔、標回 `dataChanged` 待重試、不外拋，`QueuedSaveAll` 後段與關機 hook 照常完成。

kill switch：`-Dmdc.animalSpawnGuard=0`（1＋2）、`-Dmdc.animalCellSave=0`（3），需重啟。

### 守門與驗證

SmokeCheck 釘三條存在理由（`IsoGameCharacter` 建構子內 `getAddList`、四個建構子各含兩項檢查、`AnimalCell.save` 的 FileOutputStream 早於序列化）、四個建構子 `tailCallOk` 且真指令恰 +2×RETURN、`checkStages`／`unload`／`worker.save` 同形改道、`AnimalCell.save()` 全 jar 恰 2 個呼叫點。`AnimalSpawnGuardTest`（on／off）：unsafe／safe 兩分支撤出、0,0,0 與正常建構不碰、grow／addBaby 只吞建構失敗的 NPE。`MdcAnimalCellSaveTest` 用真 `AnimalCell→AnimalChunk→VirtualAnimal→IsoAnimal` 序列化鏈：off 重現原版例外外拋＋檔案 0 bytes，on 例外不外拋、舊檔逐位元保留、健康 cell 正常覆寫。完整 build 與兩輪安裝往返通過。**真 `IsoAnimal` 建構子在測試 JVM 建不起來，建構子 TailCall 只有結構驗證，沒有執行驗證。**

線上驗收：`[AnimalSpawnGuard] ctor failed`／`skip grow` 出現時對照座標與 reason；`this.adef is null`、`AnimalData.getBreed` NPE、`AnimalCell.load> Exception` 新增檔案不再出現；`[AnimalCellSave] serialize failed`
應為 0，出現代表仍有其他壞動物進到存檔，舊檔已保住。

### 2026-09-28 42.21 對版

**官方沒有修掉半建構問題**：42.21 四個帶座標建構子仍是 `if (!checkForChickenpocalypse(null) && !checkForWater())`
才 `init()`，water 分支仍不撤出；`AnimalData.grow` 仍在建構後立刻 `newAnimal.getData()`。patch notes 的「animals not spawning due to an error on the server」對應的是 `AnimalManagerMain.fromWorker` 補登記
`AnimalInstanceManager` 與新方法 `IsoAnimal.removeFromUpdateLists()`，不碰建構流程。

**42.21 的相關變動**：
1. `IsoGameCharacter` 建構子加入 cell 改成呼叫 `IsoCell.addMovingObject`（本體仍是 isSafeToAdd→objectList／addList
   兩分支），並在 `cell != null` 時**新增** `setMovingSquareNow()`：物件在建構子內就進了格子的 `movingObjects`（42.20.4 只設 `current`）。
2. `checkForChickenpocalypse` 多一個 `replacingAnimal` 參數：建構子傳 null，只有 `grow` 傳新成體，避免原動物看到格子上同 ID 的新成體而誤刪兩者——這是 TIS 為第 1 點補的配套。

**第 1 點對本刀的影響**：只撤 objectList／addList 已不夠。water／noInit 失敗的物件仍留在格子上：`IsoGridSquare.getAnimals` 會回傳它；建構子的 chickenpocalypse 檢查早於 `init()`，新物件的 animalId 恆為初值 -1，與格子上這隻殘留物件（同為 -1）相等，4 格內之後每次建構都判定 chickenpocalypse 而失敗（grow 每次 update 重試、每次失敗）；chunk 卸載時 `removeChunkFromWorld` 對它呼叫 `unloaded()`，`getData()` 為 null 直接 NPE。

**改了什麼**：
- `afterCtor` 先判定 reason（撤出格子後 `current` 會清空），撤 cell 之後對非 chickenpocalypse 分支呼叫
  `removeFromSquare()`（撤 current／last／movingSq 的 `movingObjects` 與 `staticMovingObjects`）；chickenpocalypse
  分支原版 `delete()` 已含 removeFromSquare，不重做。log 多 `removedFromSquare`、`squareRemoved`。reason 判定自身出錯時記 anomalies 並照樣撤出。
- SmokeCheck 存在理由改釘：`IsoGameCharacter` 建構子內 `IsoCell.addMovingObject` 恰 1、`setMovingSquareNow`
  恰 1；`IsoCell.addMovingObject` 本體只有 isSafeToAdd／objectList／addList／兩次 `Set.add`（共 3 個呼叫，官方日後在裡面加其他登記時會紅＝逆操作要跟著補）；四個建構子改數 `checkForChickenpocalypse(IsoAnimal)Z`。TailCall 命中數不變（RETURN 數 3/3/2/2）。
- `AnimalSpawnGuardTest` 兩個失敗建構案例掛上格子，新增「自格子 movingObjects 撤出」斷言與 `squareRemoved=2`；舊 helper 在這兩條與計數上失敗、新 helper 通過；off 組態確認原版殘留。

### 2026-10-20 放下動物到水格

**事故**：約 1 小時內兩次，動物經 `IsoGridSquare.AddWorldInventoryItem` 被放到水格；兩次都在同一位玩家離線的同一秒，位置就是他離線時所在的格子（第一次所在的 5 分鐘窗口，W60 的 drops 計數只加 1）。建構子的 water 檢查失敗、本刀撤出之後，原版照樣 `copyFrom`（把手上那隻的 `AnimalData` 接過來）、登記 `AnimalInstanceManager`、`setSquare`／`setCurrent`，`setMovingSquareNow` 再把它放回格子，並送 `DropAnimal` 給附近客戶端。玩家離開後那個 cell 要卸載：`IsoChunk.removeFromWorld` 對格子的 movingObjects 逐一呼叫 `removeFromWorld`，在 `IsoGameCharacter.releaseRagdollController` → `AnimalVisual.getModelTest` 讀 `AnimalDefinitions.getDef(null).textureSkinned` NPE。`ServerMap.postupdate` 的 try 包住整個 loadedCells 迴圈，每 tick 在這個 cell 中斷，排在它後面的 cell 不再卸載、也不再 `update`。60 分鐘 33,701 次 NPE，major GC 後的存活量從 15.9 GB 漲到 40.5 GB（heap 上限 40 GB），接著 heap OOM、主迴圈停住，健康檢查重啟。

**手術**（追加）：
4. `AddWorldInventoryItem(InventoryItem,FFFZZ)` 動物分支唯一的 `IsoAnimal.setMovingSquareNow`（javap offset 240）與唯一的 `INetworkPacket.sendToRelative`（offset 418，DropAnimal）改道 `AnimalSpawnGuard.dropPlace`／`dropSend`，掛在 W60 的 `IsoGridSquare` ClassPatch。`adef` 為 null 時 `dropPlace` 把它移出世界：`removeFromWorld`（吞例外）、`removeFromSquare`、`setSquare(null)`，`AnimalInstanceManager` 還指著它就註銷；`dropSend` 不送。物品照原版被收走，那隻動物不保留：原版同樣保不住它。
5. `IsoChunk.removeFromWorld` 的兩個 `IsoMovingObject.removeFromWorld`（offset 332 movingObjects、506 staticMovingObjects）改道 `chunkRemove`，掛在抑噪 #10 的 `IsoChunk` ClassPatch，給其他路徑放回格子的半成品當保底。只有 `adef` 為 null 的動物會被吞例外，之後補做 objectList 移除與 `AnimalInstanceManager` 註銷；不交給 `AnimalManagerWorker`，半成品不會存進 apop。其他物件與建構完成的動物照原版，例外照樣外拋。

判定半成品用 `adef == null`：`copyFrom` 會把手上那隻的 data 接過來，放下之後 `getData()` 已經不是 null。kill switch 同 `-Dmdc.animalSpawnGuard=0`。

**守門與驗證**：SmokeCheck 釘三條存在理由：放下動物先 `new IsoAnimal`，再 `setMovingSquareNow`、`sendToRelative` 各恰 1；`IsoGameCharacter.removeFromWorld` 呼叫 `releaseRagdollController`；`IsoChunk.removeFromWorld` 恰 2 個 `removeFromWorld`。抑噪 #10 與 W60 的手術後全文比對加進這四個改道。`AnimalSpawnGuardTest` on／off：水格放下的動物不回格子、註銷、不送；建構完成的照放、照送；卸載只吞 `adef` 為 null 的例外，建構完成的照樣外拋。

實機 E2E（JavaPatch E2E 技能 `animal-water-drop-mp`）：伺服器在陸地生一隻母雞、包成動物物品，對真的水格呼叫原版 `AddWorldInventoryItem`；玩家傳送到遠處 25 秒後，再問伺服器那一格還在不在。修補前的包：水格上留一隻半成品，那一格仍在記憶體裡，console 有 277 次 `textureSkinned` NPE。修補後：`[AnimalSpawnGuard] drop discarded`，水格上沒有半成品，那一格已卸載，零 NPE。這是 W37 第一次用真的建構子走完建構失敗。

### 2026-10-21 放下的格子是水格時改放旁邊的陸地格

**回報**：上一節的修補上線後（10-20 05:41 開服起生效），同一位玩家在自己河岸畜牧區放下的雞，10-20 20:45、20:46 與 10-21 02:56 共被丟棄三次（`drop discarded`，兩隻洛島紅、一隻來亨母雞）。玩家錄的影片裡，他站在看起來是草地的岸邊選「扔掉」，雞就不見了。那幾格人站得上去，也放得了東西（`canDropOnFloor` 成立），但 `getFloor()` 回傳的實心地板 `FloorMaterial` 是 `Water`，`isWaterSquare()` 成立，建構子的 water 檢查照樣失敗。上一節的做法擋住了記憶體事故，玩家仍然失去動物。

**手術**（追加，在 W60 的 `IsoGridSquare` ClassPatch，該 class 命中點 7 → 9）：
6. `AddWorldInventoryItem(InventoryItem,FFFZZ)` 動物分支 `new IsoAnimal` 的座標參數 `this.x`、`this.y`（javap offset 105、109 的 `GETFIELD`，方法內各恰 1 個）改成 `INVOKESTATIC AnimalSpawnGuard.dropX`／`dropY(IsoGridSquare)I`。放下的格子不是水格就回傳原座標；是水格時，`landFor` 依北、南、西、東、四個斜角的順序找第一個鄰格：已載入、不是水格、`isFree(false)`（有實心地板，沒有實心物或樹）、和放下的格子之間沒有牆、圍欄或門窗擋著（`testCollideAdjacent`，角色移動用的碰撞）。建構子因此直接在陸地格建出完整的動物，water 檢查不會失敗。
7. 原版接著 `setSquare(this)`、`setCurrent(this)` 把動物指回放下的格子；`dropPlace` 在 `setMovingSquareNow` 之前把 square 與 current 換成 `landFor` 選的陸地格（同一次放下裡世界沒有變動，結果和 `dropX`／`dropY` 相同），`dropSend` 把 DropAnimal 封包裡的格子換成陸地格：客戶端照封包裡的格子自己建一隻（`AnimalCommandPacket`），不換的話客戶端會在水格上建、建構失敗。log 記 `drop moved from=<水格> pos=<陸地格>`，`dropMoved` 計數。
8. 找不到陸地格時座標不變，照上一節丟棄（`drop discarded`）。只看 8 個鄰格：人站得上去的水格在岸邊，旁邊通常就有陸地。

Patcher 新形態 `FieldGetRedirect`：把 `GETFIELD owner.name:desc` 換成 `INVOKESTATIC helper(Lowner;)desc`，堆疊效果（objectref → 值）與指令長度（3 bytes）都不變，原 frames 照舊有效，計入逐方法命中數。既有的 `fieldGetSwap` 在讀完欄位後才插 helper，helper 只拿得到欄位值，不知道是哪一格。Kill switch 同 `-Dmdc.animalSpawnGuard=0`（連同上一節與建構子撤出一起關）。

**守門與驗證**：SmokeCheck 的 vanilla 前提加釘：動物分支的 `GETFIELD x`、`GETFIELD y` 各恰 1 個，都在 `new IsoAnimal` 與它的 `<init>` 之間，前一條都是 `aload_0`（讀的是放下的那一格）；W60 的手術後全文比對加進這兩個改道；helper 契約加上 `dropX`／`dropY` 各經 `landFor` 呼叫 `getX`／`getY` 一次。`AnimalSpawnGuardTest` on／off 用真的 `IsoGridSquare`（水格的地板物件是 `solidfloor`＋`FloorMaterial=Water`，鄰格經 `ServerMap` 替身查，碰撞讀 `collideMatrix`）：鄰格是水、隔著圍欄、有實心物都跳過，選第一個能放的；陸地格與四周沒有陸地的水格回原格；`dropPlace` 把指回水格的動物換到陸地格，`dropSend` 送陸地格；off 全部照原版。

實機 E2E（`animal-water-drop-mp` 改成照原版抓起再扔掉）：原版地圖河岸的水格都是深水（`solidtrans`，人走不上去），情境在一格水格上疊一層旁邊陸地的地板，做出 `isWaterSquare()` 成立、人站得上去、`canDropOnFloor` 成立的格子；在北邊陸地生一隻母雞，玩家站上水格用 `ISPickupAnimal` 抓起，再用 `ISInventoryPaneContextMenu.dropItem` 扔掉。修補後：`drop moved from=<水格> pos=<北邊陸地>`，伺服器與客戶端都看到母雞在那格陸地，水格上沒有動物，玩家離開後那一格照常卸載，零 `textureSkinned` NPE（RESULT PASS，4 項檢查）。修補前的包（與正式服相同）：`drop discarded`，伺服器與客戶端都找不到母雞（RESULT FAIL，4 項檢查錯 2 項），就是玩家遇到的情形。

<a id="2ba"></a>
## 2ba. 畜牧區離線補算快照（W38，server，預設 on）

**現象**：玩家回報動物經常異常死亡。W32 觀測 9/25 00:06–9/26 12:2x（約 36 小時）補算後當場死亡約 133 隻；有明細的 42 隻中 27 隻（64%）在同一幀被同一 zone 的 `doMeta` 重複補算 2–4 次；全部明細 3,057 組（幀、動物、座標）裡 594 組被處理超過一次，離線時數出現 62→0→−61 這類「補進未來」序列。`AnimalMetaPredator=false`，不是離線掠食者。

**根因（42.20.4 反編譯＋javap）**：`DesignationZoneAnimal.doMeta` 以索引走訪 `this.animals`（方法內 8 個
`GETFIELD animals`）。`IsoAnimal.updateStatsAway` 先把 `zoneCheckTimer` 歸零再 `checkZone()` →
`setDZone()`，後者即使同一個 zone 也先 `removeAnimal` 再 `addAnimal`，把該動物移到清單尾端。索引迴圈因此重複補算部分動物（每次再加一整段離線時數的 `hourGrow`、年齡、`timeSinceLastUpdate`），同時漏掉另一些。

**手術**：同一個 `doMeta` MethodOps（W32 兩個 redirect 保留）：頭部 `AnimalMetaSnapshot.begin`、單一 RETURN
前 `end`，8 個 `GETFIELD animals` 之後插 `animals(list)`。begin 後第一次讀（`check()` 已重建清單）拍快照，本次 doMeta 其餘讀取都回快照：每隻動物每個迴圈恰好補算一次，補算內容不變。doMeta 不巢狀；例外跳過 end
時下一次 begin 覆蓋。kill switch `-Dmdc.animalMetaSnapshot=0`。

SmokeCheck 釘存在理由（`updateStatsAway` 呼叫 `checkZone`、`setDZone` 先 remove 後 add）、head／tail／8 個
swap 位置與真指令 +12、W32 兩個改道。`AnimalMetaSnapshotTest` 以原版迴圈形狀重現：off 重複與漏算，on 每隻恰一次。

線上驗收：W32 明細中同一（幀、動物、座標）重複的組數應歸零；`died` 佔 `calls` 的比例應下降。

<a id="2bb"></a>
## 2bb. 動物死亡帳本（W39，server，純觀測）

取代開 `DebugType.Animal`（water 分支原版不印字、debugln／noise 量大）。`IsoAnimal.OnDeath()` 頭部
`AnimalDeathLedger.onDeath`：每隻死亡一行 `[AnimalDeath] animal=種類#ID pos wild baby ageDays health hunger
thirst zone hutch [catchUp=次數x/時數h catchUpAgoMs] via=前 4 個遊戲幀`。catchUp 由 W32 三個補算呼叫點回報，60 秒內剛補算過才標記；via 可分辨飢渴、補算、玩家擊殺與宰殺。每 60 秒最多 40 行明細，每 5 分鐘一行
`beat deaths domestic wild afterCatchUp suppressed anomalies`。不改行為，kill switch `-Dmdc.animalDeathLedger=0`。SmokeCheck 釘 OnDeath 頭部 aload_0→onDeath、真指令 +2；`AnimalDeathLedgerTest` 驗補算標記、上限與 data null。

<a id="2bc"></a>
## 2bc. 物品處理清單 null 容錯＋跨執行緒寫入觀測（W40，server，預設 on）

**事故（2026-09-26 13:52–15:21）**：20–29 人時 fps 從 9.8 掉到 2–3，14:40 起單幀凍結 15–16 秒。W15 看門狗 78 張主執行緒快照中 65 張（83%）落在同一條路徑：chunk 載入 → `IsoObject.addToWorld` →
`ItemContainer.addItemsToProcessItems` → `IsoCell.addToProcessItems` → `ArrayList.contains`。同一時段
`IsoCell.ProcessItems` 每次都 NPE（`"i" is null`，738 次，9 月其他 session 零次）。GC 無 Allocation Stall，major GC 後 heap 44–54%，排除記憶體。

**根因（42.20.4 反編譯＋javap）**：伺服器每 5 秒跑一次 `ProcessItems`，以索引走訪 `processItems` 並直接呼叫
`i.update()`／`i.finishupdate()`，沒有 null 檢查。清單一旦混進 null，每次都在該處 NPE：null 之後的物品永遠不再被評估，`finishupdate()` 為真的物品不再進 `processItemsRemove`，清單只剩 chunk 卸載會縮減，長成「已載入容器的全部物品」。每個 `addToProcessItems` 都要對整份清單做線性 `contains`，玩家走進物品多的區域就凍結；null 不會自行消失，只有重啟能清。null 的來源靜態分析找不到：所有加入點都擋 null、封包都排入主迴圈、Workshop Lua 沒有碰這份清單，推測是跨執行緒寫入的資料競爭。

**手術**：`IsoCell.ProcessItems` 內唯一 `InventoryItem.update()`／`finishupdate()` 1:1 改道 `ProcessItemsGuard`：null 時不呼叫、`finishupdate` 回 true，原版就把它放進 `processItemsRemove`，同一幀的 `ProcessRemoveItems` 移掉；非 null 行為不變。四個 `addToProcessItems`／`addToProcessItemsRemove` 頭部呼叫 `touch`（W30 的
`BulkItemRegistration` 快路徑隨 W30 退役移除）：非主執行緒（第一次 `ProcessItems` 的執行緒）寫入時記執行緒名與前 8 個遊戲幀（前 20 筆、之後每 1000 筆）。每 5 分鐘 `beat calls nulls size offThreadWrites anomalies`，`size` 是清單大小，可直接看出是否又在膨脹。kill switch `-Dmdc.processItemsGuard=0`。

SmokeCheck 釘存在理由（原版 `ProcessItems` 零 null 檢查、update／finishupdate 各 1）、兩處同形改道、四個
headCall 與真指令 +2、`IsoCell` 其餘方法逐指令不變。`ProcessItemsGuardTest` 跑 dist 內手術後的真 `ProcessItems`／`ProcessRemoveItems`／`addToProcessItems`：off 重現 NPE、null 之後不處理、null 留在清單；on 同幀移除、其他執行緒寫入被記錄。線上驗收：`beat size` 維持小量；若出現 `null in processItems` 或 `off-main-thread write`，以該行的執行緒與呼叫來源追查 null 的真正來源。

**2026-09-26 線上結果**：18:00／20:06 重啟後 `nulls=0`，清單 1.5–3.1 萬；`off-main-thread write` 每分鐘約 38 次（20:06 session 到 23:59 共 8,384 次），抽樣明細（前 20 筆＋每 1000 筆，28 行）全是 `ServerPlayersVehicles` 執行緒載入車輛：`VehiclesDB2$SQLStore.loadChunk` →
`BaseVehicle.load` → `setCurrentKey` → `ItemContainer.AddItem` → `IsoCell.addToProcessItems`。修法見 W41（2bd）。

**2026-09-28 42.21 對版**：W45（2bh）上線後 `processItems.contains` 走索引，§2bd 為評估改 HashSet 而加的抽樣線性搜尋（`touchAdd` 每 256 次 `contains(SENTINEL)`）與 beat 欄位 `scanUsAvg／estScanMs` 已無意義，一併移除；`addCalls`、`addAllItems` 等登記量照舊。§2bd／§2bh 提到的 `scanUsAvg` 驗收訊號從此不再輸出。42.21 的 `IsoCell.ProcessItems`／四個寫入口逐指令未變，其餘行為不變。

<a id="2bd"></a>
## 2bd. 非主執行緒物品登記改道主執行緒（W41，server，預設 on）

**根因**：W40 抓到的唯一跨執行緒寫入者是 `ServerPlayersVehicles`。它在背景載入車輛時把鑰匙放進車內容器，`ItemContainer.AddItem` 無條件 `IsoCell.addToProcessItems(item)`，與主執行緒同時增刪同一份 `ArrayList`。`ArrayList.add` 擴容與主執行緒 `remove` 交錯，就會留下 null 或重複項——正是 W40 的 null 來源。

**手術**：`ItemContainer.AddItem(InventoryItem)`／`AddItem(String)` 內唯一 `addToProcessItems` 1:1 改道
`ProcessItemsGuard.addToProcessItems`（與 W5 同一個 `ItemContainer` ClassPatch）。主執行緒（`GameServer.mainThread`）照原版直接登記；其他執行緒排入 `ConcurrentLinkedQueue`，由 `ProcessItems` 頭部
`beginPass` 在主執行緒補登記，最晚延後一個處理週期。佇列超過 100,000 件時退回原版直接寫。補登記前若物品已被移除，會多被 `update()` 一次再由 `finishupdate()` 移出，與原版「先登記後移除」的形狀相同。`AddItemBlind` 本來就不登記，保持原版。kill switch `-Dmdc.processItemsDefer=0`（與 W40 的 `processItemsGuard` 分開）。

**W40 量測擴充**：`ProcessItems` 唯一 RETURN 前 `endPass` 記單次耗時；`addToProcessItems(InventoryItem)` 頭部
`touchAdd` 計登記次數，每 256 次抽樣量一次整份清單 `contains` 的耗時；`addToProcessItems(ArrayList)` 頭部
`touchAddAll` 計批次件數（原版逐件 `contains`）。beat 新增 `passes passMsAvg passMsMax addCalls addAllItems
scanUsAvg estScanMs deferred drained pending overflow`；`estScanMs`＝登記次數×抽樣平均，用來判斷是否值得把清單換成 HashSet。（42.21.0 起 `scanUsAvg`／`estScanMs` 已移除，見 §2bc 對版段。）

SmokeCheck：`BaseVehicle.setCurrentKey` 經 `ItemContainer` 加鑰匙（存在理由）、兩個 `AddItem` 同形改道其餘指令與
frames 不變、`AddItemBlind` 不動、`ProcessItems` 頭 beginPass＋尾 endPass 真指令 +4、四個寫入口 headCall 形狀。`ProcessItemsGuardTest nodefer` 驗其他執行緒照原版直接寫；`on` 驗排入佇列、不碰清單、下一次 `ProcessItems`
補登記且當幀處理。線上驗收：`off-main-thread write` 歸零（改道後其他執行緒不再進 `IsoCell`）、`deferred≈drained`、`pending` 小、`overflow=0`、`nulls=0`。

<a id="2be"></a>
## 2be. 動物補算時數上限（W42，server，預設 on）

**證據（20:06 session，3.3h）**：W32 記到 650 筆補算，174 筆的 zone 時數比動物自身離線時間多 ≥24h；38 隻死亡中
25 隻在補算後 60 秒內，其中 8 隻浣熊同一圈舍 `catchUp=2x/17h`、hunger 1.0 同時死亡，`AnimalMetaPredator=false`。W38 已消除 doMeta 內的重複補算（594/3057 組 → 1/649），剩下的是時數本身算太多。

**根因**：`fromWorker` 與 `DesignationZoneAnimal.doMeta` 都以 `worldAgeHours - zone.hourLastSeen` 當離線時數。`hourLastSeen` 只在整個 zone 離開串流時更新；大圍場部分 chunk 重載、或 zone 在關機時仍串流中，重啟與重載都會拿陳舊值、重補數天。動物自身 `timeSinceLastUpdate` 由 `unloaded()` 寫入，是較準的離線起點，但原版在動物活著時不更新它，一直載入中的動物時鐘停在上次卸載，存檔後帶著陳舊值。

**手術**：
- W32 的三個補算改道（`AnimalAwayProbe`）在委派前讀動物自身離線時數，取 `min(zone 時數, 自身時數)`；自身無紀錄（-1，新生或舊存檔）沿用原版時數；時鐘在未來（先前多補、之後未卸載）補 0；非正時數不動。只會減少、不會增加。
- `AnimalData.update` 內唯一 `hourGrow(false)`（活著的每小時一次）1:1 改道 `liveHourGrow`：先把動物時鐘設為現在再委派。與 W33 同一個 `AnimalData` ClassPatch。
- kill switch `-Dmdc.animalCatchUpCap=0`：回原版時數且不刷新時鐘。`-Dmdc.animalAwayProbe=0` 只關觀測，上限照做。

部署後第一次載入時，仍帶舊版陳舊時鐘的動物上限無效（自身時數也很大）＝原版行為；活過一小時或卸載一次後才有效。

**觀測**：W32 beat 新增 `capped cappedHours clockRefresh cap`，`sumHours` 改名 `sumAppliedHours`（實際補算時數）。逐筆明細只在「zone 時數比自身多 ≥24h」或「補算後死亡」時記，新增 `applied=`。W39 死亡帳本的 `catchUp` 記實際補算時數。

SmokeCheck：原版 `AnimalData` 全 class 零寫入動物時鐘、`update` 內 `hourGrow` 恰 1（存在理由），改道同形。`AnimalAwayProbeTest` 三組態：出貨組態驗陳舊 zone 補 2h 而非 200h、時鐘在未來補 0、無紀錄沿用 500h、活著時刷新時鐘；`nocap` 驗全部回原版時數且不刷新。線上驗收：`capped` 與 `cappedHours` 成長、`mismatch` 明細的 `applied` 接近
`animalHoursAway`、補算後 60 秒內的死亡明顯減少。

<a id="2bf"></a>
## 2bf. 2026-09-27 log 精簡、探針退役與量測補強（server）

20:06 session（3.8h、53,126 行）的 patch log 以 TimedActionProbe 2,863、AnimalRelevancy 2,542、ChunkWriteGuard
1,946、AnimalAwayProbe 824 行為大宗。本輪只改輸出節奏與內容，不改任何刀的行為：

- **心跳改 5 分鐘**：AnimalRelevancyGate、AnimalRequestGate、ChunkWriteGuard（計數閘後加 5 分鐘時間閘，多執行緒用
  CAS）；VehicleIntersectPrefilter、VehicleCouldSeeGate（主執行緒）；AnimalLosGate、AnimalLosScan、W10-C beat（60s → 300s）。
- **W10-C 逐筆明細移除**：`negativeDuration#`、`interrupted#`、`performFalse#`、`otherOwnerSameId#`、`noOwnedMatch#`
  只留 beat 計數；唯一保留 `untrustedAction#`（身分不可信，異常訊號，恆 0）。1,600 筆負時長全是動畫動作、709 次打斷中 653 次是 ISWaitWhileGettingUp，逐筆已無新資訊。
- **W39 `via` 修正**：38 筆全卡在 `IsoPlayer.onKilled<IsoGameCharacter.Kill<…<die`。改為跳過 `OnDeath`／`DoDeath`／`onKilled`／`Kill`／`die`，取前 6 個遊戲幀。
- **退役**：W5-2 `ContainerAddCycleProbe`（9/23–9/26 共 40 個 session wouldCycle／depthCapped 全 0；W5 捕手仍在）、W20(a) `ContainerIdProbe`（同窗每 session 0–3 次 square-null，ContainerID 不再 patch）、WorldSound 週期心跳（同窗零慢呼叫；慢呼叫明細與看門狗的 `describeActive` 保留）。
- **抑噪 #10**：`IsoChunk.removeFromWorld: vehicle wasn't removed from world id=`（20:06 session 3,002 行、678 個
  id、佔 5.7%）。dedicated server 的 `IsoPlayer.players[]` 只有 ServerLOS 執行緒瞬間寫入，`BaseVehicle.removeFromWorld`
  的乘客早退幾乎不成立；原版印完隨即再呼叫一次 `removeFromWorld` 完成移除＝車輛卸載的正常路徑。方法內唯一
  `DebugLog.log(String)` 改道 `LogFilter.log`，`LOG_PREFIX` 以 startsWith 攔。SmokeCheck 釘「印完再移除」與乘客早退只比對 `IsoPlayer.players`。
- **SpriteConfig 18 名**：2026-09-26 25.2h 窗重算，達 ≥4 筆/h 者收（Commercial_* 六名、MetalFloorLvl1、Wood_Crate_Lvl2、Floor_SummerGrass、WoodFloorLvl1、WoodenDarkDoorFrameLvl3、Composter、BrickFloorLvl1、Floor_SummerGrassCorner、ComposterShoddy、DoubleFenceGate、WoodenDarkWindowFrameLvl3、Floor_Concrete）。8/19 窗內不達標的名字量已放大；BrickDoorFrameLvl2（3.3/h）以下仍放行。`LogFilterNoiseTest` 鏡像 37＋5。
- **雞舍同步變化量測**（W26，純觀測）：每次自發 sync 的 payload 與同一雞舍上一次逐位元比較，beat 新增
  `unchanged unchangedBytes`。蛋的 Food age 每秒可變，只有 payload 完全相同才算；數據出來前不做 W36 式變化閘。

<a id="2bg"></a>
## 2bg. 地面物品過期清除時機同步＋物品搬移失敗即時回報（W43／W44，server，預設 on）

**症狀（2026-09-27 01:40 玩家回報）**：畜牧場的糞便「撿不起來」，讀條走滿後空等、東西沒進背包，同一件重試多次都一樣；玩家重登後那些物品就消失。client log 無任何錯誤；伺服器 00:21 session 有 81 行
`ERROR: sendItemsToContainer: can't find world item with id=…`（29 個 id，單一 id 重試 9 次），70 行集中在該玩家待在農場的 01:32–01:43。9/20 以來每 session 0–104 行，不是新問題。

**卡住的機制（W44 的對象）**：地面撿物走 `ItemTransaction`，不是 W10 的 `NetTimedAction`。來源是 WorldObject 且伺服器找不到物品時，`isConsistent` 在 source=null 時仍回 0 ⇒ Accept；到期 `Transaction.update()` 失敗，`TransactionManager.update` 只 `setState(Reject)`、**不送封包**（只有 Done 回送）。client 停在 Accept，等「時長＋10 秒」逾時被移除，空清單上 `isDone` 成立 ⇒ `forceComplete`：讀條走滿、空等、沒拿到。整疊撿時每件各卡一輪。

**幽靈物品從哪來（W43 的對象）**：
- `IsoGridSquare.load:3272-3301` 載入時丟掉過期地面物品，沒有 client 守衛（2n 受精蛋案已證實 client 收 chunk 與讀本機快取都走這段）。伺服器只在自己載入 chunk 時清；chunk 一直載著，過期物品留在記憶體。client 每次載入都清 ⇒ 同一格兩邊
  `objects` 清單長度不同。
- 伺服器移除地面物件時送的 `RemoveItemFromSquare` 只帶物件序號（`RemoveItemFromSquarePacket.set` 取 `getObjectIndex`），client 按序號刪 ⇒ 錯位時刪到別的物件或超出範圍直接略過（非 debug 不留 log），被撿走的那件留在 client 畫面上。
- 正式服 `DayLength=3`（1 小時一天）＋`HoursForWorldItemRemoval=24`＝現實約 1 小時就過期；清單含全部 `Dung_*`、`ChickenFeather`、`TurkeyFeather`、`Egg`，畜牧區最容易觸發。

**W43 手術（無新 bytecode 改動）**：client 取得「伺服器已載入 chunk」資料的唯一出口是 `PlayerDownloadServer.update()`
內的 `SaveLoadedChunk`（W4-1 已改道到 `ChunkRequestPacker.saveLoadedChunk`；另一個呼叫點在 `ServerChunkLoader` 存檔路徑，與 client 無關）。helper 在序列化前呼叫 `WorldItemExpirySync.beforeSend`：以與 `IsoGridSquare.load` 逐項相同的條件（含 `split("_")[0]` 分支不看 dropTime／hours 的原版怪處），把該 chunk 的過期地面物品經原版 `GameServer.RemoveItemFromMap`
移除。已載入該格的其他 client 收到的序號與伺服器一致（relevance 範圍 `range/2+2` chunk 大於 client 視窗）；下載中的 client 那格尚未載入，移除封包直接被丟棄（`delayPacket` 只延後 coop 載入中的格子），拿到的資料本來就沒有這些物品 ⇒ 兩邊清單一致。方向與受精蛋案相反：伺服器採用 client 本來就會套用的結果，玩家可見的清除規則不變。
- 時鐘邊界：client 載入晚於伺服器序列化，途中到期的物品會被 client 多刪，故提前
  `-Dmdc.worldItemExpiry.marginHours`（預設 1 遊戲小時≈現實 2.5 分鐘，clamp 0..24）。
- 成本：原版每移除一件做一次 `RecalcAllWithNeighbours`；每件只付一次，沒有過期物品的 chunk 只多一次 64×層數掃描。
- 只吞 `RuntimeException`（計 anomalies，序列化照常＝原版）。kill switch `-Dmdc.worldItemExpiry=0|off`。
- beat（5 分鐘，僅在有 chunk 下載時）：`chunks removed removedSinceLast maxPerChunk anomalies`。

**W44 手術**：`TransactionManager.update()` 內三個 `Transaction.setState` 1:1 改道 `MdcTransactionReject.setState`（`zombie.core` 套件以讀 protected `entries`／`playerId`）。先照原樣設狀態；只有 Reject 且為 `ItemTransactionPacket`
時，比照 Done 分支以同一物件送給該玩家連線（Reject 的 write 只帶 id＋state），client `isRejected` ⇒ `forceStop`。任一 entry 來源為 `Floor` 時不送：原版「地面→地面」先搬完才以距離 >1.1 回 false（`Transaction.updateItem:302-341`），物品其實已移動，維持原版不打斷後續排隊。已知取捨：多件交易前面幾件已搬、後面失敗時，改為立刻中斷剩餘排隊（原版是空等後繼續）。kill switch `-Dmdc.transactionReject=0|off`。beat 只在有 Reject 時：`rejects sent skippedFloor
skippedNoConn anomalies`。

**殘留（原版，本刀不處理）**：chunk 傳輸途中對同格的新增／移除封包會因 client 那格尚未載入而被丟；伺服器未載入的
chunk 由 client 讀檔、伺服器稍後自行載入時多清的部分不通知 client。兩者都會產生幽靈，頻率預期遠低於主因；要即時清掉需要 client Lua 按 id 移除（原版沒有按 id 刪地面物品的封包）。

**驗證**：SmokeCheck 釘 `IsoGridSquare.load` 丟棄條件的呼叫／欄位讀取數（TIS 改條件即紅，避免再錯位）、`RemoveItemFromSquarePacket.set` 取 `getObjectIndex`（存在理由）、`TransactionManager.update` 內 setState=3 且
`PacketType.send` 恰 1（TIS 補 Reject 回送即紅＝撤 W44）、改道同形。`WorldItemExpirySyncTest` 驗丟棄條件與原版逐項等價（含兩個原版怪處）與旋鈕解析。沒有本機可執行的 dedicated server 端到端情境；線上驗收＝`can't find world item`
每 session 行數大幅下降、`WorldItemExpiry removed` 成長且 `anomalies=0`、`TransactionReject sent` 對應剩餘的撿物失敗。

<a id="2bh"></a>
## 2bh. 物品處理清單身分索引（W45，server，預設 on；W30 同批退役）

**症狀（2026-09-27 晚峰）**：三張新地圖首次生成＋40–51 人時主迴圈 FPS 1–2。低 FPS thread dump 約 10% 主執行緒樣本停在 `ArrayList.indexOfRange ← ArrayList.contains ← IsoCell.addToProcessItems(:2766) ←
ItemContainer.addItemsToProcessItems ← IsoObject.addToWorld ← IsoChunk.doLoadGridsquare ←
ServerMap$ServerCell.RecalcAll2／Load2`。W40 beat（19:45 session 約 79 分鐘）：`size=29892 addCalls=196495
addAllItems=7374174 scanUsAvg=37.4 estScanMs=283114`＝主執行緒約 6%（外推上限：每次登記都當 miss 全掃）；19:05 session size 15k–27k、scanUsAvg≈29、約 5.6%。

**原版缺陷**：
- `addToProcessItems` 兩個多載加入前先 `processItems.contains`（ArrayList 線性）。chunk 載入時
  `IsoObject.addToWorld → ItemContainer.addItemsToProcessItems` 對容器每件物品各掃一次整份清單；地面食物因
  `Food.shouldUpdateInWorld` 每 5 秒經 `IsoWorldInventoryObject.update` 重新登記一次。`Food.updateAge` 另有一處
  `getProcessItems().contains(this)`。
- 清單為什麼大：`DaysForRottenFoodRemoval ≠ -1`（本服 44）時 `Food.finishupdate()` 對會腐壞的食物回 false（`Food.java:1335-1337`），它們常駐清單；本服 1.5–3 萬件。
- `ProcessRemoveItems` 每幀兩次 `processItems.removeAll(processItemsRemove)`，不先檢查空集合，每次掃完整份清單。
- 對照：同 class 的 `processIsoObject` 已有伴生 `processIsoObjectSet`（`IsoCell.java:139-143`；`ProcessIsoObject`
  先 `isEmpty` 才 removeAll 並同步 Set，`:2193-2198`），`processItems` 沒有。

**手術**：IsoCell 建構子唯一 `PUTFIELD processItems` 之前插 `INVOKESTATIC ProcessItemsIndex.wrap(ArrayList)ArrayList`（Patcher 新詞彙 FieldPutWrap：堆疊 1→1、真指令 +1、`ClassWriter(0)` 保留原 frames；併入既有 isoCell ClassPatch），把新建的空 ArrayList 換成子類 `zombie.mdc.ProcessItemsIndex`。IsoCell 其他方法、欄位型別與 getter 都不動，所有呼叫端（含 Lua 經 getter 拿到的清單）自動受益。
- 清單本身仍是順序與內容的權威；另以 IdentityHashMap 記每個元素的出現次數，`contains` 查表。identity 等價的依據：InventoryItem 全繼承鏈沒有覆寫 equals／hashCode（SmokeCheck 全 jar 釘住）。IdentityHashMap 不呼叫元素的任何方法，移除不留墓碑（2g Trove 墓碑教訓）。
- 空集合 `removeAll(HashSet)` 直接回 false：原版逐一掃完後同樣回 false，`HashSet.contains` 不拋例外，內容與
  modCount 不變。其他集合（例如 `Set.of()` 遇 null 元素會拋 NPE）照原版做。
- 單件修改增量同步：add×2、addAll×2（先快照，自身 addAll 安全；無效 index 先交原版報錯）、remove(int)、remove(Object)（與原版同一個 `indexOf` 比對迴圈找位置再經 remove(int)）、set、clear；iterator／listIterator 的
  remove／set／add 都經過這些方法。未覆寫路徑的結構修改由 modCount 比對察覺，下次查詢前整份重建。
- 原版熱路徑 `removeAll(HashSet<InventoryItem>)` 增量扣除（`HashSet.contains` 以清單元素的 equals 比對，物品是
  identity），並核對實際移除數＝索引扣除數，對不上就整份重建（例如 equals 相等但非同一物件的元素）。
- **批次區段**：replaceAll／sort／removeIf／retainAll，以及參數不是 exact `HashSet` 的 removeAll（含本清單或其 view
  的別名、比較子語意的 TreeSet）照原版執行；區段內本清單的 `contains` 走原版線性（回呼看到的是正在修改的陣列），結束後不論成功或例外都整份重建。原因：replaceAll 中途拋例外時已替換的前段不動 modCount；comparator 中途拋例外時
  TimSort 會留下重複／遺失的元素；`removeAll(本清單)` 做完後參數本身已被清空，無從推算移除了哪些鍵。
- `subList` 一旦發出，該清單永久改回線性（view 的 set 直接寫陣列、不動 modCount，無從察覺），並在擁有者下次操作時清空索引，不再持有已移出清單的物品；全域停用時同樣釋放。`clone` 回傳內容相同的原版 ArrayList（淺拷貝會共用索引）。
- 建構改用 `super()`＋`addAll`：空清單與原版 `new ArrayList<>()` 同樣是預設空容量（ensureCapacity／擴容行為一致）。
- 只有建立清單的執行緒使用索引。伺服器由 `GameServer.main → IsoWorld.init` 在主執行緒建構 IsoCell，主迴圈也在同一執行緒。其他執行緒照原版寫並標 dirty，查詢走原版線性；W41 已把背景執行緒的登記改道主執行緒，線上
  `offThreadWrites=0`。並行修改的安全性與原版 ArrayList 相同（原版本來就沒有同步）。
- 抽驗：每 4096 次查詢抽一次原版線性比對；不一致即記錄並重建，on 模式累計 3 次全域停用，所有清單退回原版。這是最後防線，不是正確性保證——只察覺剛好抽中的查詢，正確性靠上面的同步規則。
- 三態 `-Dmdc.processItemsIndex`：1|on 預設、2|observe（照常維護索引，但一律回傳原版線性結果並逐次比對）、0|off（`wrap` 原樣回傳，完全原版）；未知值落回 on，需重啟。observe 仍走空集合 removeAll 捷徑（結果與原版相同）。
- beat（每 5 分鐘，由每幀的 removeAll 帶動）：`mode size keys lookups hits audits divergences rebuilds rebuildItems
  emptyRemoveAll offOwner views anomalies disabled`；首次生效另印一行 `owner=`（應為主執行緒名）。

**成本與效果**：本機 microbenchmark，常駐 3 萬件，登記 10 萬件，並以每 1000 件一次的批次 removeAll 維持大小。正式服的 `ArrayList.indexOfRange` 由整個 JVM 共用，equals 呼叫點是 megamorphic，所以基準測試先以 String／Integer／自訂 equals 類別把該呼叫點弄成 megamorphic：

| 情境 | 原版每件登記 | W45 每件登記 | 空 removeAll（原版 → W45） |
|---|---|---|---|
| megamorphic（貼近正式服） | 43.0 µs | 0.30 µs | 12.4 µs → 18 ns |
| 只見過 Object（JIT 內聯成 ==） | 6.0 µs | 0.25 µs | 9.2 µs → 16 ns |

W45 的數字已含攤提的批次移除。正式服 W40 實測 29–37 µs，與 megamorphic 情境同級。依 19:45 session 外推，主執行緒約省 6%（上限估計），chunk 大量載入時佔比更高。這不是 FPS 1–2 的唯一解：新地圖首次生成與 MOD 的
`LoadGridsquare` Lua 另案處理。3 萬鍵的 IdentityHashMap 約 1 MB。

**W40 心跳語意變化**：W40 的 `scanUsAvg`／`estScanMs` 以 `getProcessItems().contains(SENTINEL)` 抽樣。W45 上線後這個呼叫走索引，只有每 4096 次一次的抽驗會線性掃描，應從約 30 µs 降到 1 µs 以下，可直接當驗收訊號。`estScanMs` 從此只代表索引查表成本。（42.21.0 起兩個欄位已移除、不再輸出，見 §2bc 對版段。）

**驗證**：
- SmokeCheck（上游前提，TIS 修好即紅＝撤刀訊號）：`processItems` 是 private final ArrayList 且無伴生索引欄位；兩個 `addToProcessItems` 各恰 1 個 `ArrayList.contains`；`ProcessRemoveItems` 有 0 個 `isEmpty`、2 個 `removeAll`；全 jar `PUTFIELD processItems` 恰 1、GETFIELD 全在 IsoCell；InventoryItem 全繼承鏈無 equals／hashCode 覆寫。手術形狀：建構子 `new ArrayList → wrap → putfield` 全序、真指令恰 +1、其餘逐字不變，且 NEW 到 PUTFIELD 之間不得有合流點（frame 或跳轉目標 label）——否則另一條路徑可能把既有清單送進欄位，wrap 會切斷別名。守門另帶一個負對照：在記憶體建出「一條路徑 new、一條路徑讀共用清單、PUTFIELD 前合流」的方法，必須被拒絕。
- `ProcessItemsIndexTest` 四組態（on／observe／off／`-XX:hashCode=2` 全部 identity hash 碰撞），`-Xverify:all`：真 IsoCell 建構子掛點；真 IsoCell（dist 手術後，含 W40／W41）與原版清單跑同一組操作的差分；20 萬步隨機操作差分，逐步比對內容、contains 與每個操作的回傳值；批次與別名情境逐一與原版 ArrayList 比對例外、內容、contains
  與「查詢後索引＝內容 multiset」（replaceAll／removeIf 中途例外與回呼讀本清單、sort 在最後合併中途拋例外、removeAll／retainAll 以本清單或唯讀 view 為參數、比較子 TreeSet、equals 相等非同一物件、Set.of() 遇 null、無效 index、空清單 ensureCapacity）；subList 與全域停用後釋放索引；跨執行緒、clone；人為弄壞索引後抽驗察覺、重建、累計 3 次停用（observe 恆回原版結果）。7 個手工 mutant（removeAll 不更新索引、remove(int) 不遞減、subList 不停用、set 不同步、observe 回索引結果、空 removeAll 回 true、自身 addAll 不快照）全數被抓。
- 獨立審查（critic）：無 blocking；四項 SHOULD-FIX（批次操作例外與回呼、removeAll 別名、永久線性未釋放索引、守門未擋合流點）與四項 NIT 均已修正並補上對應案例，修正前的 helper 跑新測試會失敗。
- 線上驗收：首次生效行 `owner=` 為主執行緒；beat `divergences=0 anomalies=0 disabled=false views=0`；W40 `scanUsAvg` 從約 30 µs 降到 1 µs 以下（42.21.0 起此欄位已移除）；低 FPS dump 不再出現 `ArrayList.indexOfRange ← IsoCell.addToProcessItems`。

**官方回報**：`docs/report/2026-09-27-processitems-linear-contains-tis.md`（草稿，未送出）。

<a id="2bi"></a>
## 2bi. VehicleCollide 歸還後強制重送授權（W46，server，預設 on）

**症狀（2026-09-27 晚上）**：`Packets limit has exceeded for VehicleCollide` 兩小時約 18 萬行，佔 console 86%，`server-console.txt`（20 MB 上限）約 80 分鐘寫滿清空一次。8 月以來只有 9/26 15:22 那個 session（ProcessItems 凍結
5–16 秒）出現過 1,974 行。

**抓包（入站 15 秒與 30 秒兩次，另對一個 client 雙向 20 秒）**：
- 送包的 client 每次 3–4 個、會換人換車（22:09 與 22:16 兩批完全不同），每人每秒 46–237 包。30 秒內 7,167 包全是 `collide=0`（歸還）、零個申請；同一台車每秒的包數約等於 client 幀率。
- 對照 8/24 舊抓包：正常一次碰撞是 6 個申請、29 個歸還，之後就停。
- 雙向抓包：伺服器對那幾台車的位置與 client 一致（車輛 ID 沒錯位），也持續送更新，但只帶乘客旗標（16384），從未帶授權（8192）。
- 警告以 100 ms 為單位成批出現（最多 1,730 行），緊跟在主迴圈卡頓之後。`isLimitExceeded` 依伺服器處理封包的時刻計數（`MaxPacketsPerSecond=1000`），卡頓後積壓的包同一瞬間處理才超過門檻；平時每個 client 只有幾十到兩百多包/秒，不會觸發警告。警告量＝卡住的車數 × 卡頓長度，迴圈平時可能就存在。

**原版缺陷（42.20.4）**：
1. client 撞到伺服器管的車時，`BaseVehicle.authorizationClientCollide` 先在本機自設 `LocalCollide`（不等伺服器），同時送 `VehicleCollide(true)`（`WorldSimulation.java:238-243`）。
2. client 持有 `LocalCollide` 的車 1 秒沒動，就每幀送 `VehicleCollide(false)`，並把計時歸零，所以下一幀又送，直到伺服器傳來新授權才停（`WorldSimulation.java:184-197`）。
3. 伺服器授權同步只送「與上次送給該連線不同」的差異：`ServerVehicleState.shouldSend` 以連線快取的
   `netPlayerAuthorization／netPlayerId` 比對，相同就不帶 8192。
4. 申請與歸還若在伺服器同一幀處理（卡頓 ≥1 秒、網路把兩包湊在一起），或申請被其他卡住的 client 每幀送來的歸還立刻蓋回 `Server`，伺服器的淨變化為零，永遠不會送授權更新。client 卡在 `LocalCollide`，每幀送歸還，直到那台車離開它的載入範圍或重登。觸發原因是推測，修法不依賴它。
5. `VehicleRequest` 只能要求乘客／完整同步，不會重送授權，原版沒有其他自癒路徑。

卡住的 client 也把那幾台車當成自己負責的物理：忽略伺服器送來的位置、自行模擬，送出的 `VehiclePhysics` 被伺服器以無授權忽略。玩家撞動那幾台車時可能只在自己畫面上移動（推論，未實際觀察）。

**手術**：`VehicleCollidePacket.processServer` 頭部 headCall，slots＝{0, 2}（封包、連線），真指令 +3，新 ClassPatch。helper `zombie.network.packets.vehicle.MdcVehicleCollideResync`（同 package 以讀 protected 欄位
`isCollide`／`vehicleId`）：
- 只處理歸還包（`isCollide=false`）。從 `connection.vehicleStates` 取這台車既有的快取，把 `netPlayerId` 設成
  `Short.MIN_VALUE`（原版只會是 -1 或 onlineID）。下一輪 `sendVehicles` 的 `shouldSend` 必帶 8192，送出伺服器當下的真正授權，client 的 `netPlayerFromServerUpdate` 收到後改掉 `LocalCollide`，停止送歸還。
- 不論伺服器是否因他人駕駛（`Local`）而忽略這次歸還都重送：client 收到 `Local(他人)` 會改成 `Remote`。
- 正常歸還本來就有差異、本來就會送，不多送；送出後 `setAuthorization` 把快取寫回，不會持續重送。
- 連線沒有這台車的快取時不新建（`getVehicleState` 新建會觸發額外同步）。
- 申請包、封包格式、`authorizationServerCollide` 本身都不動。純伺服器端，玩家不需安裝任何東西。
- `-Dmdc.vehicleCollideResync=0|off` 回原版，其他值（含未設）啟用，需重啟。RuntimeException 只計數，不擋原版處理。
- beat（每 5 分鐘，由歸還包帶動）：`releases invalidated noState noVehicle anomalies`，首次生效另印一行。

**驗證**：
- SmokeCheck（上游前提，TIS 修好即紅＝撤刀訊號）：`shouldSend` 以連線快取的 `netPlayerId` 比對才帶 `SIPUSH 8192`；原版 `processServer` 恰 1 個 `authorizationServerCollide`、不碰 `ServerVehicleState`／`vehicleStates`；`authorizationClientCollide` 本機自設 `LocalCollide`。手術形狀：頭部 `aload_0／aload_2／invokestatic` 全序、真指令恰 +3。helper 只有 1 處 `PUTFIELD netPlayerId`、零 `getVehicleState`。
- `MdcVehicleCollideResyncTest`（on／off，`-Xverify:all`）：走 dist 手術後的真 `processServer`，以真 `shouldSend`
  判定。伺服器已是 `Server` 時的歸還：on 下一輪帶授權、off 不送；送出一次後不再重送；伺服器忽略歸還（`Local(5)`）時 on 把 `Local(5)` 送回；申請包不動快取；連線沒有快取時不新建。
- 線上驗收：用 `temp/vc_trace.py`（gitignore）重抓入站 30 秒，只送 `collide=0` 的迴圈應消失（每台車最多數十包）；卡頓後的 `VehicleCollide` 警告尖峰應大幅下降；beat `invalidated` 成長、`anomalies=0`。

<a id="2bj"></a>
## 2bj. 動物視線空間預篩（W47，server，預設 on）

**數據（2026-09-28 01:0x，約 50 人、約 6 FPS）**：W18 心跳每幀約 254 次實際視線檢查、每次 64 µs，合計約 16 ms／幀（主迴圈約 9%）；W18-2 心跳 `objAvg=4339`＝每次檢查都走完整份 `objectList`，只為找出門檻（約 12 格）內的殭屍與玩家。同一天把 `-Dmdc.animalLosN` 由 3 改為 5（兩份 JVM json 同步，備份 `*.bak-20260928T011256-pre-los5`），視線頻率再降 40%；W47 處理剩下每次檢查的成本。

**手術**：W18-2 `AnimalLosScan.updateLOS` 的 on 路徑在前置取值成功後、清 `spottedList` 前多一次
`AnimalLosIndex.tryHandle`；回 true 即本次已處理完，false 照原完整掃描（此時未改任何遊戲狀態）。另在既有 W45 的 IsoCell
建構子手術多一個 FieldPutWrap：`objectList` 的 `new HashSet()` 寫入前換成 `AnimalLosIndex.ObjectSet`（HashSet 子類，預設容量與迭代順序同原版，只覆寫 `add`／`addAll` 記成功加入次數，迭代成本不變）。`Patcher.MethodOps.fieldPutWrap` 改為清單以容納同方法兩個包裝。
- 快照：`objectList` 依迭代順序只留目標（殭屍、非動物玩家），殭屍依快照位置分入 16 格網格，並記下每隻動物前面有幾個目標。幀號（`MovingObjectUpdateScheduler.getFrameCounter`）、清單身分、加入次數或大小改變即重建；清單不是 `ObjectSet` 不走快速路徑；遇到 null 元素不建，交給完整掃描照原樣拋出。
- 每隻動物只取「門檻＋`MARGIN`（16 格）範圍內的殭屍＋全部玩家」為候選，依迭代順序逐一走與 W18-2 迴圈逐句相同的單一目標處理；處理到自己的位置才把自己加入 `spottedList`。候選範圍超過 1,024 格網格（門檻約 250 格以上）直接回完整掃描。
- 只在呼叫開始時 `lastAlerted == 0` 才走快速路徑；候選的 `spotted()` 讓 `lastAlerted ≠ 0` 或改了門檻時，從該目標之後改走完整順序。
- 最後一個有效候選之後若還有任何有效目標，補一次 spotted 前綴（`spottedChr = null`）。

**等價依據**（對照 W18-2 完整掃描，W18-2 本身與原版＋W3-3 逐位元相同）：
1. 遠距目標唯一效果是 spotted 前綴（`spottedChr = null`、`lastAlerted` 衰減）。`lastAlerted == 0` 時衰減恆無效果；原版 `spotted()` 開頭同樣先清 `spottedChr`，所以候選之前的遠距前綴會被候選自己的前綴覆蓋，可以省略；只剩「最後一個有效候選之後還有沒有有效目標」決定最後的 `spottedChr`。
2. 候選依迭代順序處理，`spotted()` 呼叫的順序、對象與距離位元都與完整掃描相同；門檻逐 pair 即時讀取。
3. 原版有十多處經 `getObjectList()` 直接增刪（玩家、動物、虛擬殭屍、網路殭屍等），更新期間成員並非不變；HashSet 擴容也會改變其餘元素的迭代順序。快照綁定（加入次數, 大小）：只有加入會增加大小或觸發擴容，任何移除都讓大小變小，同大小換成員必然經過加入；移除不改其餘元素的相對順序。所以兩者都沒變時，快照的成員與順序恆等於當下的迭代結果。
4. 動物的 `spottedList` 只放自己；原版 `BaseAnimalBehavior` 全類不讀寫它。依快照中自己的位置加入，`spotted()` 中途拋出例外時留下的 `spottedList` 也與完整掃描相同。
5. `spotted()` 可經 XP 與 Lua `AddXP` 回呼執行任意程式碼，可能在呼叫中途增刪 `objectList`。完整掃描的 HashSet iterator 在目前元素不是最後一個時，下一次取元素即拋 `ConcurrentModificationException`；是最後一個則迴圈自然結束。W47 在每次委派後比對（加入次數, 大小），依快照中目前目標是否為整份清單最後一個元素，照樣拋出（`modifiedExits`）或結束，不處理已失效的後續目標。
6. 同一回呼也可能同步觸發另一隻動物的視線檢查。快照與候選暫存是靜態共用的，巢狀呼叫一律走完整掃描（`nested`），外層以 `finally` 釋放保護，正常返回與例外皆同。

**唯一假設與監看**：快照之後到檢查當下，殭屍移動不超過 16 格（玩家不受限，一律逐一檢查）。兩道監看：快速路徑最後的掃描遇到落在門檻內的非候選殭屍即判違反（`lateFixes`，同時照原版補處理）；on 模式每 64 次改跑完整掃描並逐一確認門檻內的殭屍都在候選中（`auditMisses`；observe 每次都比對）。任一違反即本次啟動永久停用快速路徑、全部回到 W18-2 完整掃描，並記一行座標。

**開關**：`-Dmdc.animalLosIndex` `1|on`（預設）、`2|observe`（只比對不改行為）、`0|off`；需重啟。只在 `AnimalLosScan` 為 on 時生效。心跳每 5 分鐘：`calls fast exactAlerted exactDomain exactSnapshot audits auditMisses tailSwitches lateFixes candAvg targetAvg
rebuilds rebuildUsAvg disabled modifiedExits nested anomalies`。

**驗證**：
- SmokeCheck：原版前提三條（`spotted()` 開頭先清 `spottedChr`、`BaseAnimalBehavior` 全類不碰 `spottedList`、`addMovingObject` 以 `isSafeToAdd` 延後加入）；IsoCell 建構子兩個 FieldPutWrap 各自緊接在 `new ArrayList()`／`new HashSet()` 之後、移除後逐字同原版、全 jar `objectList` PUTFIELD 恰 1；helper 的快照／候選／比對都在清 `spottedList` 之前、單一目標處理與 W18-2
  迴圈同一組委派（prefilter 2、門檻 1、DistanceTo 1、multiplier 1）、零 Rand；`AnimalLosScan` 恰 1 次 `tryHandle` 且在完整掃描清空之前。
- `AnimalLosIndexTest`（`-Xverify:all`，W18-2 on，清單經 `wrapObjectList` 建成與正式服相同的 HashSet）：4,000 個隨機世界（殭屍、隱形／幽靈玩家、抓取用殭屍、其他動物、車輛、物理物件、z 差、無方格、自己不在清單、`spotted()` 中途拋例外、`spotted()` 中途移除後續元素／加入殭屍／移除自己）逐次比對原版 `IsoAnimal.updateLOS` 與 W47 的 spotted 呼叫序列、`spottedChr`、`lastAlerted` 位元、`spottedList`、門檻與例外，全部一致；另測同一幀內同大小換成員、只移除、擴容後成員還原（順序改變）、經 iterator 移除、超大門檻改走完整掃描、被委派目標正好是最後一個元素時中途改動清單（原版不拋）、`spotted()` 內巢狀觸發另一隻動物的視線檢查（含內層拋例外後再次呼叫）；observe、off 同樣一致；位移違反兩種情境都停用且結果仍一致。11 個手工 mutant（不補最後前綴、警戒不改走完整順序、候選不排序、自己一律加入、門檻變大不改走完整順序、快照不看加入次數、快照不看大小、拿掉網格上限、不模擬 CME、最後元素也拋 CME、拿掉重入保護）全數被抓。
- 審查（critic，唯讀，三輪）：第一輪抓到以清單大小判斷快照有效（同幀同大小換成員、擴容換序會不同）、例外出口的 `spottedList`
  差異、超大門檻逐格掃描的效能退化；第二輪抓到 `spotted()` 中途改動清單時完整掃描會拋 CME 而 W47 繼續處理；第三輪抓到巢狀視線檢查覆寫外層候選暫存。五項皆已修正並補測。
- 一次性基準（4,260 個物件、2,500 殭屍分布 1,200 格見方、760 隻動物分 10 群、每幀 150 次檢查、每幀重建快照）：每幀 4,780 µs → 約 230–260 µs；每次候選約 10 個（全部目標 2,540）。`ObjectSet` 完整迭代 4,300 個元素與原版 HashSet 相差約 2.5%（雜訊內）。
- 線上驗收：首次生效行；beat `auditMisses=0 lateFixes=0 disabled=false anomalies=0`、`fast` 佔 `calls` 大宗；W18 `losAvgUs` 從約 64 µs 降到個位數。

<a id="2bk"></a>
## 2bk. 動物聽覺量測（W48）與空間索引（W48-2，server，預設開）

**原版**：伺服器每隻動物每個 tick 在 `IsoAnimal.updateInternal → respondToSound` 呼叫一次 `WorldSoundManager.getSoundAnimal`。client 只看動物所在 chunk 的聲音清單（`chunk.soundList`），伺服器（`GameServer.server`）卻整份掃過全域 `soundList`，挑出會影響動物（`stresshumans || stressAnimals`）且在範圍內最大聲的一個，成本是「動物數 × 全世界聲音數」。9/25 晚峰 JFR 把約 4.7% 記在下一行的 `getSoundAttractAnimal`（沒開 `DebugNonSafepoints`，歸屬不精確），W47 與
`animalLosN` 都不經這條路徑。改寫前先量清楚成本與清單組成。

**手術**：併入既有 IsoAnimal ClassPatch 的 `respondToSound`（已有聲音壓力常數手術）：方法內唯一的
`invokevirtual getSoundAnimal` 1:1 改道 `AnimalSoundProbe.getSoundAnimal`（receiver 前置），委派原版一次並計時，回傳原版結果；原版例外原樣上拋、不計入。每 64 次抽樣一次，照原版條件與距離算式重掃清單，數出會影響動物的聲音數（`eligible`）與其中在範圍內的數量（`inRange`）。另記同一幀內前後兩隻動物之間清單變動的次數（`changesPerFrame`），評估「每幀建一次精簡清單」能否重用。

**心跳**（首次生效一行，之後每 5 分鐘）：`calls frames callsPerFrame=平均/最大 nsAvg usMax frameUsAvg frameUsMax
list=平均/最大 samples eligible=平均/最大 inRange=平均/最大 hits changesPerFrame windowPct anomalies`。`windowPct` 是兩次心跳之間此呼叫佔真實時間的百分比，`frameUsAvg` 是每幀合計耗時。

**判讀**：`windowPct` 或 `frameUsAvg` 佔幀長明顯（>2%）才值得做加速版。`eligible` 遠小於 `list` 時，每幀建一份只含會影響動物的精簡清單即可；`eligible` 也大但 `inRange` 很小時，需要依位置分區。`changesPerFrame` 高代表清單在動物之間常被追加，快照要能察覺追加（清單只在 `update()` 移除、其他時候只從尾端追加）。加速版必須維持原版的清單順序與「嚴格大於才換」的取捨，並處理 W47 審查抓到的兩類問題：掃描途中清單被改動、同一次呼叫中又觸發另一次檢查。

**開關**：`-Dmdc.animalSoundProbe=0|off` 直接委派，不計時也不計數；需重啟。

**9/28 06:1x 實測**（06:00 重啟後、清晨非尖峰）：`windowPct` 2.66–4.80%、`frameUsAvg` 3.2–4.1 ms（最大 15 ms）、每幀約 452–525 次（最大 709）、`list` 約 4,000–4,300（最大 4,761）、`eligible` 約 98%、`inRange` 平均 12–14（最大 278）、`hits` 約 51%、`changesPerFrame` 約 50–54、`anomalies=0`。會影響動物的聲音幾乎是全部，精簡清單沒用；在範圍內的極少，故 W48-2 依位置分區。

### W48-2 空間索引

**做法**（`AnimalSoundIndex`，由 `AnimalSoundProbe` 呼叫，量測照舊包住它）：只找可能在範圍內的聲音，逐一照原版算式（同一個 `IsoUtils.DistanceToSquared(float×6)`、z×3、半徑×野生 3 倍、`!(distSq > r²)`、`volume × (1 − distSq/r²)`）取最大者；音量相同取清單中較前者——原版依序掃描「嚴格大於才換」，結果就是最大值中索引最小者，所以不必照清單順序走。聲音依「半徑×3＋2」分三類：≤64 放 64 格網格、≤512 放 512 格網格（各查動物所在格與周圍 8 格），更大的（環境音 600／5000、警報、直升機）每次都查。不在周圍格內的聲音，水平距離已大於「半徑×3＋2」，原版的浮點距離不可能落在範圍內。不影響動物、音量 ≤ 0、半徑 0 的聲音原版永遠選不到（半徑 0 時 `delta` 為 NaN），不入索引。

**清單變動**：`WorldSoundManager` 建構子唯一的 `PUTFIELD soundList` 前插 `wrapSoundList`（FieldPutWrap），把新 ArrayList 換成同實作的子類 `SoundList`，多記追加與 `set` 次數；其餘結構變動看 ArrayList 自己的 `modCount`。每次查詢前比對：只有尾端追加（`addSound`，每幀約 50 次）就把新元素補進索引，其他任何變動（`update()` 移除到期聲音——每幀都會發生、`KillCell` 清空、插入、`set`、`removeIf`）整份重建。`replaceAll`、`sort`、`removeAll`、`retainAll` 會在回呼途中直接改寫內部陣列、最後才遞增 `modCount`（回呼拋出時根本不遞增），`subList`／`reversed` 視圖的寫入也繞過計數，所以一被呼叫就把清單標成不可信、從此改走原版（全 jar 讀 `soundList` 的地方只用 `size/get/add/remove(int)/clear/iterator`，原版從不呼叫它們）。先讀好動物座標，再在清單鎖內（與 `addSound` 的 `synchronized(soundList)` 同一把）完成索引維護、查詢與抽樣比對，其他執行緒的追加不會插在索引與原版比對之間（critic 審查抓到：否則合法的並行追加會被當成不一致而永久停用）。

**改走原版的情況**：非伺服器、清單不是 `SoundList` 或已不可信、清單含 null（原版會拋 NPE，照原樣）、動物座標非有限值或絕對值 ≥ 1e7。

**監看**：聲音在清單中時，座標、半徑、音量、旗標不會被原地改寫。SmokeCheck 守門的部分：全 jar 對這些欄位的寫入全部在
`WorldSound.init` 多載內；唯一例外是 `BodyDamage.TriggerSneezeCough` 在 `addSound` 後把 `stressAnimals` 設 `false`（索引每次即時重查旗標，關掉只會少一個候選，與原版一致）；`getNew` 呼叫 2 處、`release` 呼叫 3 處。42.20.4 人工查核、未由結構檢查保障的前提：`init` 只作用在剛從物件池取出的物件、`update()` 先移出清單再回收、`KillCell` 回收後立刻清空——升版時上述呼叫數一變就要重新核對。另每 256 次查詢比對一次原版結果，不一致即本次啟動永久停用、記錄前 10 筆明細。

**開關**：`-Dmdc.animalSoundIndex`：`1|on`（預設）、`2|observe`（每次都比對，回傳原版）、`0|off`（不包清單、全走原版）；需重啟。索引開啟時 W48 的抽樣改為每 1024 次一次（抽樣本身要整份重掃）。

**心跳** `[AnimalSoundIndex]`（首次生效一行，之後每 5 分鐘）：`calls fast candAvg rebuilds rebuildUsAvg tailAppends entries far
fallback[notServer untrusted null coords] audits auditMisses observeMismatches disabled anomalies`。

**驗證**：
- SmokeCheck：原版前提（`getSoundAnimal` 讀 `GameServer.server` 一次、全域 `soundList` 一次；全 jar 呼叫點恰 1 個且在
  `respondToSound`）；改道後原呼叫歸零、改道恰 1、真指令數不變；probe 只經索引查詢一次、不直接呼叫原版、零 Rand。W48-2：原版 `getSoundAnimal` 的指令文字 SHA-256 與核對時相同（TIS 改任何一處都紅，須重新核對等價）；全 jar
  `PUTFIELD soundList` 恰 1、原版前為 `new ArrayList`、手術後緊接 wrap、移除 wrap 後逐字等於原版、真指令 +1；helper 的逐聲音算式呼叫同一個 `DistanceToSquared(FFFFFF)F`、只接 `RuntimeException`、零 Rand；聲音欄位寫入逐方法全在
  `init` 多載內、`getNew`／`release` 呼叫數（見「監看」）。
- `AnimalSoundProbeTest`（`-Xverify:all`）：400 幀隨機聲音清單（含半徑 0、三種旗標、z 差、無方格動物、幀內追加），每次呼叫回傳值與原版為同一物件；calls／hits／frames／每幀上限／幀內變動／抽樣次數與 eligible／inRange 合計對得上獨立重算；清單含 null 時與原版同型例外且不計入；off 模式不計數。索引開、關兩種抽樣間隔各跑一次。
- `AnimalSoundIndexTest`（`-Xverify:all`，四組態）：on 模式 240 個隨機世界、約 7 萬次查詢，每次與原版比對同一物件；清單變動涵蓋幀內追加、真 `update()` 到期移除、中間移除、插入、`set`、交換、反轉、`removeIf`、`addAll`、清空、同值雙胞胎、移除後插入；另測 64／512 格線邊界、負座標、負半徑、音量 ≤ 0、半徑 0、NaN 與超大座標、無方格、含 null（同型 NPE）、取過 `subList`
  後改走原版。bulk 操作：`replaceAll` 部分完成後拋出、寫入 null（同型 NPE）、回呼中重入查詢，`sort` 比較器中途拋出，`removeAll`／`retainAll` 在 `contains` 回呼中重入查詢，全部與原版相同。並行：另一執行緒在查詢讀動物座標時以同一把鎖追加更大聲的聲音 600 次，全部與原版相同且不觸發停用。observe 每次比對零不一致；off 不包清單；原地改寫聲音座標時抽樣比對發現並停用。16 個 mutant（取捨去掉索引比較或反向、去掉野生 ×3、只查中心格、忽略 `set`／`modCount`／不可信旗標、排除負半徑、整數除法、放大近距上限、距離 z 不乘 3；`replaceAll`／`sort`／`removeAll`／`retainAll` 不標不可信；在鎖內才讀動物座標）全數被測試抓到。
- 本機基準（一次性，不入 build）：4,300 聲音、每幀 525 次查詢、每 10 次追加一個、每幀 `update()`：原版每幀約 2.18 ms、索引約 0.19 ms（含每幀一次重建），命中數相同。
- 線上驗收：首次生效行；beat `auditMisses=0 disabled=false anomalies=0`、`fast` 佔 `calls` 大宗；W48 的 `frameUsAvg`／`windowPct` 對照 9/28 基線。

**9/28 線上驗收**（12:06:58 生效；12:07–13:25 與 13:27–13:58 兩個 session，下午非尖峰，兩次重啟都是排程／MOD 更新的正常流程）：
- `[AnimalSoundIndex]` 抽樣比對合計 130,438 次零不一致（`auditMisses=0`、`disabled=false`、`anomalies=0`），四種 fallback 全為 0；`calls` 與 `fast` 的差額是動物沒有所在方格、依原版第一步回 null 的呼叫。`candAvg` 139–197（清單約 3,900–4,700），約為本機基準的兩倍，所以正式服的加速比基準小。
- W48 心跳換算成每 5 分鐘邊際值，取規模相近的窗口（`list` 4,000–4,450、每幀呼叫 480–660）：每次呼叫中位 7.76 µs（7.17–8.52，n=17）→ 1.21 µs（1.09–1.38，n=9），每幀 4.34 ms → 0.69 ms，佔主執行緒 4.29% → 0.61%，前後範圍不重疊；每幀最大值 12.5–16.4 ms → 2.3–3.8 ms。當時伺服器大多接近 10 fps 上限，省下的是主執行緒餘裕，不代表 fps 會等比例上升。
- 命中率（hits/calls）12:07 session 0.284、13:27 session 0.315，與上線前 11:17 session 的 0.278 相近；06:07 session 的 0.363
  是清晨世界狀態不同，不是索引漏抓（漏抓會在抽樣比對出現不一致）。
- 每幀整份重建的邊際成本在同一 session 內逐漸上升：12:07 session 110→190 µs、13:27 session 80→131 µs，目前每幀不到 0.2 ms；晚峰再看是否持續上升。

<a id="2bl"></a>
## 2bl. 自建房間 XL 樹例外（client，42.21.0；client 包 0.2.2）

**症狀**（2026-09-29 Player-I、Player-J 回報；官方 bug report
[101887](https://theindiestone.com/forums/topic/101887-42210-entering-player-built-rooms-adjoining-pre-built-structures-causes-exceptions-visibility-glitches/)、[101955](https://theindiestone.com/forums/topic/101955-bugged-house-when-building-under-watchtower-b42/) 同一問題）：在預製建築旁邊加蓋、或疊在預製平房上的封閉自建房間，一走進去家具、樹、圍籬、路燈、窗戶都看不見（仍可互動），右下角 ERROR 每幀往上跳。兩份 client log 只有同一條例外：`Cannot invoke "IsoRoom.getRectsBounds()" because the
return value of "IsoGridSquare.getRoom()" is null at IsoTree.isPlayerInsideARoom(IsoTree.java:327)`，由
`FBORenderCell.renderInternal` 接住（一份 580 次，另一份 7 秒內 415 次，約每幀一次）。官方 QA 在 101887 回覆已於內部修好、會在之後的版本推出（2026-09-28），沒有日期。

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- `IsoTree.isPlayerInsideARoom(IsoPlayer)Z` 是 42.21 新增的 XXL 樹室內淡化判斷（42.20.4 沒有這個方法）：offset 1
  `invokevirtual IsoPlayer.isInARoom()Z` 為真，就在 offset 10–16 依序呼叫 `getSquare()`、`getRoom()`、`getRectsBounds()`，中間不檢查 null（line 327）。
- `IsoGridSquare.isInARoom()Z` 是 `getRoom() != null`，**或** `getIsoWorldRegion().isPlayerRoom()`（`IsoWorldRegion.isFogMask()`＝封閉且 `roofCnt == squareSize`）。
- 自建房間要有 IsoRoom，靠 client 端 `WorldRegionToMetaGrid.clientProcessBuildings` 把封閉、屋頂 ≥50% 的區域轉成
  user-defined building；`isAdjacentToOrOverlappingAPredefinedBuilding` 會丟掉緊貼或重疊預製建築的那些，而
  `isAdjacent`／`overlaps` 都以 `bIgnoreZ=true` 比對，所以疊在預製平房上的二樓也算。這些格子 `getRoom()` 為 null，`isInARoom()` 卻為 true。
- `FBORenderCell.renderInternal` 用 try/catch 包住整段 `RenderTiles`，例外讓該幀排在第一棵 XL 樹之後的物件都不畫。只有 sprite 名稱含 `XL` 的樹會走到這個判斷，而且前面「瞄準中且看得到樹」「在車上」兩個條件都不成立。
- 42.20.4→42.21.0 新增的 `getRoom().…`／`isInARoom()` 用法只有 IsoTree 這幾行，沒有其他同型呼叫點。

**手術**：`isPlayerInsideARoom` 內唯一的 `invokevirtual IsoPlayer.isInARoom()Z` 1:1 改道
`invokestatic zombie/mdc/TreeRoomGuard.isInARoom(IsoPlayer)Z`（3 bytes 換 3 bytes、堆疊 1→1、frames 原樣）。helper 回傳 `isInARoom() && getSquare() != null && getSquare().getRoom() != null`：原版不拋例外時結果相同；原版會 NPE 時回 false，這種房間裡的 XL 樹就不做室內淡化（同 42.20.4），`isPlayerCloseToARoom` 不受影響。第一次遇到時在 console.txt 記一行 `[MinidoracatJavaPatch][TreeRoomGuard] room without IsoRoom at x,y,z; XL tree
room fade skipped`（每次啟動至多一行）。標準版與省記憶體版都含這刀，模組版本 `v3.1`。

**守門與驗證**：
- SmokeCheck（兩個變體）：vanilla 前提兩條——`isPlayerInsideARoom` 恰一個 `isInARoom`、`getRoom` 之後直接接
  `getRectsBounds`（官方補上 null 檢查時轉紅＝撤刀訊號），以及 `IsoGridSquare.isInARoom` 含
  `IWorldRegion.isPlayerRoom`；手術後 `aload_1→TreeRoomGuard.isInARoom→ifne` 全序鎖、原呼叫歸零、真指令數不變；負對照 `isPlayerCloseToARoom` 與 `render` 的 methodText 與原版相同。
- LoadCheck：`TreeRoomGuard.isInARoom(IsoPlayer)` 為 public static boolean。
- `TreeRoomGuardBehaviorTest`（兩個變體各跑一次）：以 Unsafe 配置真 IsoTree／IsoPlayer／IsoGridSquare，真
  `IsoWorldRegion`（封閉、屋頂全滿）經格子的區域快取接上。自建房間回 false、不拋例外；預製房間在範圍內回 true、範圍外回 false；室外回 false。同一組狀態在原版 jar 上會拋出與玩家 log 一字不差的 NPE（拋棄式探針對照，未入庫）。
- 實機驗收（2026-09-29 晚，Player-I，省記憶體版）：console.txt 載入 `client patch v3.1-lowmem(a0bbfd6)`，進入遊戲第 11 幀在自建二樓印出一行 `TreeRoomGuard`；之後約 6.5 分鐘（含四處移動，chunk 串流 parts 446→1372）零 `isPlayerInsideARoom`
  NPE、零 `FBORenderCell.renderInternal` 例外，玩家回報家具與室外物件恢復顯示。修補前同一棟房子在遊戲中每幀拋一次。

**退場**：官方版本修掉後 SmokeCheck 的 vanilla 前提會轉紅，屆時從 `PatchConfig.client()` 移除本刀，並刪除 helper
與測試。

<a id="2bm"></a>
## 2bm. 動物離線補算根治（W49，server，預設 on）

**症狀（2026-09-30 玩家回報）**：懷孕中的乳牛天數幾乎不動，同一座農場的動物成長也很慢。NAS 快照實測，同一群動物的推進率長期只有五到六成：牛的懷孕推進率在 W42 上線前 47%、上線後 58%，成長（age）23%／45%——不足在 W42 之前就存在。完整評估與證據見 [animal-catchup-hook-save-design-v0.md](animal-catchup-hook-save-design-v0.md)。

**根因**（原版四個缺陷，正式服 bytecode 確認）：
- 重啟抹除：`IsoAnimal.save` 的時鐘欄位寫存檔當下，不是動物的 `timeSinceLastUpdate`；`DesignationZone.streamed` 預設 true 且不存檔，開機後把 `hourLastSeen` 覆寫成開機時刻。「玩家離開到下次重啟」這段離線時間，重啟後沒有任何路徑會補。
- 首次進世界補 0：`fromWorker` 以 `getZone()` 推算時數，`connectedDZone` 不存檔；本次開機首次進世界的動物、不在畜牧區的放養動物一律補 0。畜牧區只部分串流時，也只補到 `hourLastSeen`。
- 餘數歸零：`updateStatsAway` 一次性把 age 加上 `floor(時數/24)×mod`，再把 `hoursSurvived` 設成 `age×24`，每呼叫一次就丟掉最多 23 小時的累積。
- 兩種 `growUp` 語意混用：補算按日曆午夜、載入中按累積滿 24 小時，所以不能單純保留餘數。

**手術**（W32 的 `AnimalAwayProbe`＋新 helper `MdcAnimalSave`）：
- 自身時鐘補算：chunk 路徑（`fromWorker`）改以動物自身離線時數補算，不看 zone。自身時鐘在卸載時由原版 `unloaded()` 寫入，載入中與雞舍內每小時刷新（W42 的 `liveHourGrow`、本刀的雞舍改道），補算時原版迴圈逐小時推進，同一段時間不會算兩次。zone 路徑（`doMeta`）維持 W42 的 `min(zone, 自身)`，一直載入中的動物補 0。野生動物與無時鐘紀錄（新生）沿用原版。
- 已卸載動物寫出自身時鐘：`VirtualAnimal.save` 內唯一的 `IsoAnimal.save` 改道 `MdcAnimalSave.save`，以 `try/finally` 記下正在寫出的動物；`IsoAnimal.save` 內唯一的 `getTimeInMillis` 改道 `clockToWrite`，只在這個上下文中、動物不在 `currentCell.getObjectList()`、時鐘有效時寫動物時鐘，上下文在第一次時鐘寫入即消耗。`saveRealAnimals` 收集世界中動物的條件就是 objectList 成員，兩者在 `AnimalPopulationManager.save()` 同一執行緒依序執行，所以世界中的動物（包括剛從雞舍放出、還沒定位的）照原版寫存檔當下。網路封包、背包、雞舍的 `IsoAnimal.save` 不經這個上下文。
- 長離線上限：一次離線最多補 `-Dmdc.animalCatchUpLimit`（預設 168）遊戲小時。超過時先把時鐘設為「現在 − 上限」再補，較早的部分丟棄；迴圈跑完時鐘恰為現在，後續入口看到 0。不經三個 Java 改道的直接呼叫同樣受上限約束：`updateStatsAway` 頭部插入 `entryHours`（`aload_0; iload_1; invokestatic; istore_1`，線性、slot 型別不變），牲畜拖車的 Lua `Vehicles.Update.TrailerAnimalFood`（以車輛零件上次更新推算的離線時數）與管理員指令保留原本的時數來源，只套上限並記帳（W32 計數、W39 帳本）。拖車動物在那之前可能剛被 `liveHourGrow` 刷新時鐘，所以不能改用自身時鐘取較小值。上限保證只涵蓋正常跑完的補算：原版在逐小時迴圈之前就一次把 age 入帳，委派中途拋例外時，之後再補會多算 age，這是原版既有的語意，本刀只計數（`delegateFailures`）。無時鐘紀錄（新生、遷徙群）沿用原版時數、只受上限約束，不在「可信的單段離線」保證之內。
- 累積小時語意：`updateStatsAway` 內 `setHoursSurvived`、`setAge`、`hourGrow`、`growUp` 四個呼叫 1:1 改道。一次性的 age 與 `hoursSurvived` 重設略過，每小時照 `AnimalData.update`（載入中）的公式累積：`hoursSurvived + 1`，累積滿 24 小時就把 age 設成 `daysSurvived + (mod − 1)` 並 `growUp(true)`；午夜的 `growUp` 略過。補 0 小時（`doMeta` 對一直載入中的動物）時完全不動。
- 掛鉤屠體不補算：只清 `fromMeta` 後返回。原版會把屠體當活體補算，改變尺寸與體重。
- 雞舍時鐘：`IsoHutch.updateAnimalInside` 的兩個 `setHoursSurvived` 改道，雞舍內每小時一併刷新動物時鐘，放出後不會被 `doMeta` 當成離線再補一次。

**遊戲體驗**：離線期間的動物開始真的成長，懷孕可能到期，食槽不足時可能餓死（原版的離線補算本來就是這樣設計，本服過去因重啟抹除而沒有體驗到）。上限 168 遊戲小時約為本服 7 個真實小時。

**kill switch**：`-Dmdc.animalOwnClock=0`（關自身時鐘補算與寫出；W42 關閉時一併停用）、`-Dmdc.animalCatchUpLimit=N`（≤0＝不設上限）、`-Dmdc.animalCatchUpAccrual=0`、`-Dmdc.animalCarcassGuard=0`；雞舍刷新跟隨 `-Dmdc.animalCatchUpCap`。

**部署後的第一次重啟**：舊 apop 的時鐘仍是原版寫的存檔當下，第一輪只能補到存檔時刻；動物卸載後再存檔一次，才開始帶自身時鐘。

**觀測**：W32 beat 新增 `hutchRefresh ownCatchUps ownGainHours limited limitedHours directCalls directHours directLimited directLimitedHours carcassSkips accrualGrowths delegateFailures maxRunMs maxRunCalls ownClock limit accrual carcassGuard`。`ownGainHours` 是自身時鐘比 W42 多補的時數；`direct*` 是不經 Java 改道的直接呼叫（拖車、管理員），只有次數與時數、沒有耗時；`maxRunMs` 是經 Java 改道的相鄰補算（前一筆結束到下一筆開始小於 50 ms，通常是同一幀的整批）的最長累計耗時，是近似值而不是精確的幀邊界。observe 委派時發一次性票證給正在補算的那隻動物，委派中若有 MOD 回呼對別的動物直接呼叫，照樣受上限約束。`[AnimalSave]` beat 的 `ownClock saveNow` 是寫出動物時鐘與存檔當下的筆數。

**預期與驗收**：09-30 下午那段（208.8 遊戲小時，兩段卸載合計 147 小時，未超過上限），修後懷孕應推進約 8.7 天，原版實測 +4。線上驗收看 NAS 備份的 `<cell-K>` 推進率接近 100%（扣除超過上限的離線）、`maxRunMs` 沒有因補算量增加而凍結主迴圈、W39 的補算後死亡沒有異常上升。

**守門**：SmokeCheck 釘存在理由四條（`fromWorker` 在 `getZone()` 為 null 時補 0、`IsoAnimal.save` 時鐘欄位寫存檔當下、`updateStatsAway` 一次性 `setHoursSurvived(age×24)` 且 `growUp` 在午夜分支、雞舍內不刷新時鐘），加上 `AnimalData.update` 載入中的成長公式（helper 照抄這段）；`updateStatsAway` 頭部的 `entryHours` 插入全序鎖定、真指令恰 +4，其餘與四組改道以「原版文字置換後與 dist 逐字相同」鎖同形。Patcher 新增詞彙 `HeadIntFilter`（instance 方法頭部的 int 參數過濾）。`AnimalAwayProbeTest` 五組態（出貨、觀測關、W42 關、自身時鐘關、屠體守衛關；另驗相鄰補算累計與委派例外計數）、`AnimalCatchUpAccrualTest` 直接呼叫手術後的 `updateStatsAway`（累積語意開與關；含拖車式直接呼叫 200 小時被截成 168）、`MdcAnimalSaveTest` 驗時鐘寫出的各種判定。

<a id="2bn"></a>
## 2bn. 掛鉤屠體存檔保住掛鉤狀態＋W37 重試不重複寫出（W50，server，預設 on）

**症狀（2026-09-30）**：農場出現一隻不明活豬，其實是掛在屠宰鉤上的公豬屠體。中午的存檔裡牠還掛在鉤上，下午的存檔變成活體，重開後在附近走動。全服 2,973 個 apop 檔掃描：帶屠體 modData 的動物 3 隻，全部已是活體，沒有任何正確掛著的屠體。

**根因**：`IsoAnimal.save` 只在 `isOnHook() && hook != null && hook.getSquare() != null` 時寫 onHook=1＋鉤子座標；`hook` 參照不存檔，從磁碟載入的屠體要等進世界後第一次 `update()` 的 `reattachBackToHook()` 才補回。這之前所屬 cell 被存檔（同 cell 其他動物卸載，或屠體還在 worker 的虛擬狀態），就寫成 onHook=0，下次載入變成活體。鉤子本身沒有被拆：三份存檔中的鉤子紀錄逐位元相同。

**手術**：
- `VirtualAnimal.save` 內唯一的 `IsoAnimal.save` 改道 `MdcAnimalSave.save`（與 W49 共用）。原版寫完後，若屠體仍掛鉤、參照無效、且帶著載入時還原的 `attachBackToHook` 座標，就在 buffer 內把該筆尾端 `[0][petTimer][wild][onlineID]` 改成 `[1][x][y][z][petTimer][wild][onlineID]`，與原版正常掛鉤時的格式相同。只作用在 apop；網路封包照原版，屠體掛回後的快照本來就帶 1。
- 所有檢查（旗標、尾端三個欄位與屠體現值相符、剩餘容量）都在改寫之前；不符時拋 `IOException`，由 W37 保留舊檔並標回重試，絕不提交已知錯誤的 onHook=0。`SliceY.SliceBuffer` 固定 10 MiB，本案 cell 是 124 KB，容量不足只是理論路徑。
- 依賴 W37：W37 關閉時原版先開檔（截斷）才序列化，拋例外會留下截斷的檔案，所以本刀一併停用。
- 座標缺席（兩軸同時為 0，與原版 `reattachBackToHook` 的判斷相同）時照原版寫出並計 `noCoords`。正常流程不會發生：`load` 讀到 1 時一定還原座標，只有掛回成功才清座標。只有一軸為 0 的座標仍是有效座標，照常保住。
- 鉤子確實不存在（鉤子格已載入、格上沒有 `IsoButcherHook`）：`reattachBackToHook` 每個 RETURN 前的 `afterReattach` 只觀測，每隻屠體記一行 `hook missing`。原版此時會把屠體存成活體；本刀讓牠繼續掛著，要不要轉成屍體等觀測數據再定。
- W37 重試不重複寫出：`saveRealAnimals` 把世界中的動物包成暫存清單，原版只在整份寫完才清。失敗後清單留著，下一輪又把同一批加進去，同一隻會被寫兩次（W37 自 9/26 起的隱患）。`AnimalManagerWorker.saveRealAnimals` 頭部先清掉上一輪殘留的清單（此時必然是失敗或未寫出的舊快照，本輪會重新收集）；失敗當下不清，下一輪之前的重試仍帶著世界中的動物。W37 的 `IOException` 也改為標回 `dataChanged`。

**kill switch**：`-Dmdc.animalHookSave=0`；W37 的重試修正跟隨 `-Dmdc.animalCellSave`。

**觀測**：`[AnimalSave]` beat 的 `kept noCoords ioFail hookMissing`；每隻屠體第一次被保住時記一行 `kept hook state`。W37 清掉殘留快照時記 `cleared stale real-animal snapshots`。

**驗證**：
- 真實事故檔重播：把 12:00 快照中那隻屠體的掛鉤紀錄改成原版錯存的 onHook=0，交給尾端改寫後與原檔逐位元相同（120,985 bytes）。
- `MdcAnimalSaveTest` 四組態：尾端改寫的容量與欄位邊界、非零起點與連續兩筆；參照有效、非屠體、無座標時不動；`IOException` 交給 W37；本刀關閉與 W37 關閉時照原版。
- `MdcAnimalCellSaveTest`：`IOException` 保留舊檔；失敗一次 → 清殘留 → 重新收集 → 每隻只寫一次；不清時寫兩次（原版行為對照）。
- SmokeCheck：原版存檔以 hook 參照決定 onHook、尾端恰為 `putFloat(petTimer)`→`put(wild)`→`putShort(onlineID)`；`load` 讀到 onHook 後接著讀三個座標；apop 只經 `VirtualAnimal.save` 寫動物；`reattachBackToHook` 掛回成功才清座標；`saveRealAnimals` 只追加。
- 線上驗收：每日全服 apop `deathTime` 掃描不再出現新的活化屠體；`kept` 大於 0 代表原版會寫錯的情況確實被攔下。

**已活化的 3 隻**：不動存檔，玩家可以照一般動物宰殺。

<a id="2bo"></a>
## 2bo. 動物 ID 解析失敗紀錄（W51，server，純觀測）

**為什麼要記**：MP 的動物讀條動作（餵水、牽繩、拴樹、裝拖車、手餵）以動物的 online ID 傳給伺服器。伺服器查不到這個 ID 時，原版把 nil 交給 Lua 動作，不留任何紀錄，錯誤要到動作執行中才爆出來（例如餵水每 400 ms 一次 `animEvent` 例外）。原版 log 看不出玩家送來的是哪個 ID，也就分不出「這隻動物從沒登記到伺服器」和「伺服器已移除、client 還留著」。

**手術**：`PZNetKahluaTableImpl.load(ByteBufferReader, IConnection, byte)` 的 type 17（IsoAnimal）唯一的 `AnimalID.parse` 1:1 改道 `NetTimedActionGuard.parseAnimalId`。helper 在 try 之外呼叫原 parse，解析結果與例外都照原版；`getAnimal()` 為 null 才計數並記一行：

```text
[MinidoracatJavaPatch][AnimalIdMiss] id=<online ID> src=<呼叫端> [type=<動作> name=<名稱>] connectionPlayers=<onlineID:帳號|…> n=<累計>
```

- `src` 是 stack 上第一個不在 table decoder 與 helper 內的 frame，只在要寫 log 時才走 stack。讀條動作是 `zombie.core.NetTimedAction.parse`；其他共用這個 decoder 的封包（`BuildAction`、`StatePacket`）是各自的 parse。
- `type`／`name` 只在 `src` 是 `NetTimedAction.parse` 時才帶，取自 W10 的 parse 上下文。其他 parse 中途離開時這個上下文會殘留，所以不拿它歸因別的封包。
- `id=-1` 一定查不到：原版不登記 -1（`IsoObjectID.incorrect`）。其他值單看這一行分不出是從沒登記還是已移除。
- 與 W10 共用時間窗（每 10 秒最多 20 行），超過只累計 `suppressed`。W10 的 heartbeat（`[NetTimedAction] parses=…`）新增 `animalIdMisses animalIdLog`。

**與 W10-D2 撤除的關係**：這個 class 在 2026-09-08 撤除 D2（在共用 decoder 猜替代物件）後就不再出貨。本刀只為觀測重新出貨，不改任何解析結果。SmokeCheck 釘住：`load(…B)` 除了這一處與原版逐字相同、其餘方法逐指令不變、原 parse 不在 helper 的 try 內。存在理由也釘在原版 jar 上：`AnimalID.parse` 只以 `AnimalInstanceManager.get` 查 ID，type 17 解析完直接回 `getAnimal()`。

**kill switch**：`-Dmdc.animalIdMiss=0`（改道仍在，只委派原 parse，不記也不計）。

**部署**：manifest 新增 `zombie/network/PZNetKahluaTableImpl.class`（117 → 118 個 class）。照常用舊 manifest 完整卸載，再安裝新包，與受控重啟放在同一個窗口。上線後先確認橫幅指紋是新版，再看 W10 heartbeat 出現 `animalIdLog=1`。

**判讀**：和 MinidoracatFixesFor42 的伺服器端守衛（`MDFX_GiveWaterAnimalGuard nilAnimal`、`MDFX_AnimalCompleteGuard nilAnimal`）依時間與帳號對照。`AnimalIdMiss` 在伺服器建立動作時記，Fixes 那行在動作執行中或結束時出現。

**未做**：伺服器端的 ID 登記與移除紀錄（用來分辨從沒登記與已移除）等累積樣本再決定。

**驗證**：`NetTimedActionGuardTest` 四組態（出貨與三個 kill switch），用真 table decoder 與真 `NetTimedAction.parse`：查得到的動物原樣解析、不記；查不到時建構子照原版拿到 nil，並記恰 1 行、帶動作 type／name；殘留的 parse 上下文不會讓其他呼叫端被標成讀條動作；連續 30 筆查不到全部計數，逐筆 log 受節流；kill switch 不記也不計。

<a id="2bp"></a>
## 2bp. 分割畫面與重生的名稱冒用守衛（W52，server，預設 enforce）

**原版缺陷（42.21.0）**：分割畫面加入與主玩家重生都走 `ConnectCoopPacket`。stage 1 的 `parse` 從封包讀名稱，只擋空字串與任何連線 `usernames[]` 裡在線的同名，不比對這條連線登入的帳號，也不查名稱是否屬於別的帳號；「接替死亡玩家」與「新的分割畫面玩家」兩個分支都直接 `connection.setUserName(playerIndex, 名稱)`，stage 2 的 `GameServer.receivePlayerConnect` 再把它設成 `player.username`。兩條路徑：

- **分割畫面**（`AllowCoop=true`，原版預設）：原版 CoopUserName 面板讓第 2 位玩家自由輸入名稱（`isValidUserName` 只檢查格式），不必改客戶端。
- **主玩家重生**（不看 `AllowCoop`）：`parse` 只在 `playerIndex != 0` 時讀 `allowCoop`；客戶端重生時 `ConnectCoopPacket.setInit` 送 `IsoPlayer.username`，改過 Lua 的客戶端重生後立刻 `setUsername` 就能換名。

後果：以名字認人的檢查把他當成那個帳號（`SafeHouse.playerAllowed` 比對 `getUsername()`、陣營名單、許多 MOD）；冒名者在線時本人登不進來（`LoginPacket` 拒絕已在線的同名）。角色資料（以 `connection.getUserName()` 載入）與權限（取自連線）不受影響。本機 E2E（42.21.0）兩條都重現過：分割畫面以離線帳號名加入，伺服器出現序號 1、名為該帳號的玩家；重生後改名，伺服器的 0 號玩家換成別人的名字。

**正常客戶端送的是什麼**：0 號重生時客戶端把 `IsoPlayer.username` 設成 `GameClient.username`（`LuaManager.assignUsername`／`setPlayerMouse`），也就是登入時送出的名稱；客戶端與 `LoginPacket.parse` 都做 `trim()`，所以與伺服器的 `connection.getUserName()` 相同。首次進場的 `ConnectPacket` 也直接用 `connection.getUserName()`。伺服器的名稱經 `ConnectedPacket` 回寫客戶端自己的 `player.username`（`bMe` 分支），伺服器改名後兩端一致；`parse` 不在客戶端執行（`handlingType=1`）。

**手術**：`ConnectCoopPacket.parse` 頭部 headCall，slots＝{0, 2}（封包、連線），綁定本次封包與連線登入名；方法內唯一的 `ByteBufferReader.getUTF()`（stage 1 名稱，javap offset 137）1:1 改道 `readName`。共 2 個命中點，新 ClassPatch。helper `zombie.network.packets.connection.MdcCoopNameGuard`（同 package 以讀 protected `playerIndex`）照原版讀名稱，回傳原版後續程式要用的名稱：

- 0 號：一律用連線登入的帳號（`connection.getUserName()`）。接替死亡玩家與新玩家兩個分支吃同一個值，stage 2 的 `player.username` 跟著正確。連線沒有登入名時回空字串。
- 1–3 號：名稱屬於任何帳號（`ServerWorldDatabase.containsCaseinsensitiveUser`，whitelist 不分大小寫，與 `LoginPacket` 同一張表）就回空字串。
- 回空字串＝走原版「No username given」拒絕：在 `disconnectPlayer`、配 ID、`setUserName`、送 granted 之前結束，客戶端走原版 `OnCoopJoinFailed`。空名稱照原版拒絕，其餘照原版；helper 不改任何連線狀態。

**kill switch**：`-Dmdc.coopNameGuard`，未設定／`1`／`enforce`＝執法（預設；未知值也執法），`2`／`observe`＝只記錄、照原版，`0`／`off`＝原版；需重啟。判定的 RuntimeException 計入 `anomalies` 並照原版放行。

**log**（每 10 秒最多 20 行；名稱截斷，控制字元與引號換成 `?`）：

```text
[MinidoracatJavaPatch][CoopNameGuard] 首次生效 mode=enforce（-Dmdc.coopNameGuard=observe|off）
[MinidoracatJavaPatch][CoopNameGuard] renamed player=1/4 login="<登入帳號>" sent="<封包名稱>" mode=enforce renamed=<n> rejected=<n> suppressed=<n> anomalies=<n>
[MinidoracatJavaPatch][CoopNameGuard] rejected player=2/4 login="<主機帳號>" sent="<封包名稱>" reason=account mode=enforce …
```

observe 印 `wouldRename`／`wouldReject`；`reason=noLogin` 是 0 號連線沒有登入名。首次生效行在開機後第一個 stage 1 封包出現（通常是第一次重生）。

**限制**：不是帳號的名字仍是共用命名空間：別台主機的分割畫面可以沿用同一個非帳號名字，拿到以那個名字授予的安全屋或陣營權限（使用者 2026-10-03 選擇只擋帳號名，不改成衍生名稱）。`GoogleAuthKey` 先 `connection.setUserName(封包名稱)` 才驗證並斷線的問題不在本刀範圍：它改的是原版載入角色也用的登入名，需要改過 Java 的客戶端並搶在斷線前。

**驗證**：
- SmokeCheck（存在理由，TIS 修好即紅＝撤刀）：原版 `parse` 唯一 `getUTF` 存入 slot 4、兩個 `setUserName` 都直接載入它、slot 4 另外只有 stage 2 寫一次，且不呼叫 `IConnection.getUserName()`、不碰 `ServerWorldDatabase`；`receivePlayerConnect` 把名稱參數設成 `player.username`；首次進場 `ConnectPacket` 傳 `connection.getUserName()`。手術形狀：頭部 `aload_0／aload_2／begin` 全序，除了 `getUTF`→`readName` 之外方法文字（含 frames）與原版相同，全 class 真指令恰 +3。helper 契約：`readName` 恰讀 1 次名稱且不在 try 內；資料庫只查 `containsCaseinsensitiveUser`；不寫任何名稱。
- `MdcCoopNameGuardTest`（enforce／observe／off，`-Xverify:all`）：走 dist 手術後的真 `parse`，帳號名以真 `ServerWorldDatabase` 查 in-memory SQLite whitelist。enforce：0 號送別人的名字改用登入名且照常 granted；送登入名不動；連線沒有登入名在任何副作用前拒絕；1 號用帳號名（大小寫不同）在 `setUserName`、配 ID、送 granted 之前拒絕；自由名稱與空名稱照原版；stage 2 留下的綁定不會被下一個連線的 stage 1 拿去用。observe 照原版只計數；off 重現原版冒名（負對照）。
- 本機實機 E2E（42.21.0 本機 dedicated server，dist 放在 classpath 最前面；開機後以 `adduser` 建兩個離線帳號）：enforce 那輪正常重生名稱不變；重生後立刻 `setUsername` 成離線帳號，伺服器記 `[CoopNameGuard] renamed player=1/4 login="test" sent="victim1"`，0 號仍叫 test 且照常 granted，客戶端自己的 0 號名稱也回到 test；分割畫面用自由名稱照常加入；用離線帳號名的分割畫面玩家被拒（伺服器 `rejected … reason=account`，客戶端 `access denied: No username given`、觸發 `OnCoopJoinFailed`）。同一情境以 `-Dmdc.coopNameGuard=off` 重跑重現原版：0 號變成離線帳號名，分割畫面以離線帳號名加入。

**部署**：manifest 新增 `zombie/network/packets/connection/ConnectCoopPacket.class` 與 helper（118 → 120 個 class）。2026-10-04 00:4x 以延後生效流程 stage＋arm（沒有手動重啟），06:00 排程重啟時切換：console 有 `[mdc-java-patch] ACTIVATED` 與 `[mdc-javagate] OK: 120`，舊的 118 個 class 封存在 job 的 `state/`。

**上線驗收（2026-10-04 21:34）**：06:06 起四個 session 的橫幅都是 `server patch 13dbc29`，120 個 loose class 逐檔 SHA 與 manifest 相符，沒有 linkage 錯誤，各刀心跳的 `anomalies` 都是 0。主玩家重生 11 次（四個 session 依序 4、0、3、4 次），每個有重生的 session 都在第一次重生時印出 `[CoopNameGuard] 首次生效 mode=enforce`，沒有任何 `renamed`／`rejected`：正常客戶端重生送的都是登入名，守衛不需要介入。正式服 `AllowCoop=false`，分割畫面沒有樣本。

<a id="2bq"></a>
## 2bq. 步行時縮小車輛相關範圍（W53，server，預設 observe）

**問題（2026-10-07）**：KI5 與 rSemiTruck 系模組車的門、窗、引擎蓋是蒙皮模型。client 對每台已載入的車每幀都跑 `BaseVehicle.postupdate`，對每個蒙皮零件無條件重算骨架，沒有距離、可見性或靜止閘門（`BaseVehicle.getMinimumSimulationLevel` 固定 FULL）。新生成的 KI5 車每台每幀 22–47 µs、原版車 3–5 µs。正式服車多的一處，client 載入 107 輛（KI5 55、KI5 拖車 18、rSemi 11），JFR 量到車輛 `postupdate`＋`update` 每幀約 5.4 ms。要每位玩家各自安裝 client 修補不實際，Lua 也碰不到這條路徑。

**client 持有哪些車由伺服器決定（42.21.0 反編譯＋javap）**：
- client 讀 chunk 不載入車：`IsoChunk` 只在 `!GameClient.client` 時讀 vehicles 區段。client 只對伺服器 `VehicleUpdate` 告知過的車（`VehiclePacket.parse` 寫入 `VehicleCache`）要 `VehicleFullUpdate`，而且只有該格已載入時才建車。
- 伺服器 `VehicleManager.sendVehicles` 每 100 ms 對每條連線、每台車判 `isRelevantTo` 才送；伺服器每 1000 ms 對所有車加 Passengers 旗標，client 每 1000 ms 對自己持有的每台車送 `VehicleRequest(16384)`，`VehicleRequestPacket.processServer` 判 `!isRelevantTo` 就回 `VehicleRemove`。
- 範圍來自 client：`ConnectPacket` 送 `IsoChunkMap.chunkGridWidth`（1080p 以上是 19），`GameServer.receivePlayerConnect` 算 `relevantRange = clamp(w,12,20)/2 + 2`，`isRelevantTo` 是 `±relevantRange*8` 的正方形（1080p ⇒ ±88）。這比 client 19×19 chunk map（約 −79…+72）還寬，所以 client 載入的車數由自己的 chunk map 決定。用正式服 `vehicles.db` 快照驗證：上述地點 chunk map 內 107 輛、伺服器 ±88 內 146 輛，兩者交集仍是 107 輛。
- ServerOptions 沒有任何相關選項。直接改 `relevantRange`（constChange）不行：`isRelevantTo` 有 32 個檔案在用，包括門窗、物件、屍體同步與 `ServerMap.outsidePlayerInfluence`（決定伺服器 cell 何時卸載）。

**手術**：兩個方法內各自唯一的 `UdpConnection.isRelevantTo(FF)Z` 1:1 改道（新 ClassPatch ×2，各 `expectedHits=1`）：
- `VehicleManager.sendVehicles`（javap offset 75，後接 `ifeq` 跳過送出）→ `VehicleRelevancyGate.sendRelevant`。
- `VehicleRequestPacket.processServer`（offset 83，在 `flag == 16384` 分支，後接 `ifne`、否則送 `VehicleRemove`）→ `keepRelevant`。

helper `zombie.mdc.VehicleRelevancyGate` 兩個入口共用同一個判定，結果一律是原版結果的子集：
1. 原版 `isRelevantTo` 為 false ⇒ false（只縮不放；720p 的 ±64 不會被放寬）。
2. 任一 `connectArea[n]` 非 null（握手中、co-op 加入中）⇒ 原版。
3. 任一本地玩家的 `releventPos` 與車的平面距離 ≤ R（圓形，含等於）⇒ true。
4. 連線上任一本地玩家在車內（駕駛或乘客，`getVehicle() != null`）⇒ 原版（停著的車另外計數，見下方「停著的車」）。理由：車內 client 的 chunk map 中心會往行進方向前移「時速 ÷ 5」格（`IsoChunkMap.ProcessChunkPos`，時速 100 公里約 20 格），伺服器無從得知；玩家若開了「自動縮放」或「開車時平移鏡頭」選項（兩者預設關閉；`MultiTextureFBO2.update`、`PlayerCamera.java:102-142`），車內會縮到最遠、鏡頭往前偏，1920×1080 下畫面角落最遠約 71 格；AutoDrive 的前視 60–110 m 也要看到停著的車。
5. 其餘：`on` 回 false（不送、或叫 client 移除），`observe` 照原版回 true，兩者都計數。

兩個呼叫點用同一個 R、不做遲滯：如果「送」比「留」窄，留在 client 的環帶車收不到零件更新，而 `vehicle.updateFlags` 每個 tick 送完就清，漏掉的門窗變更不會補送。車再次進入 R 時走原版流程：伺服器的 Passengers `VehicleUpdate` → client `doRequest` → `VehicleFullUpdate`。兩個方法都只在伺服器執行，client 不自行重算範圍，沒有 desync。

**停著的車（只計數，2026-10-09）**：坐車照原版的代價，在同一個停車場實測是步行 108 fps、坐上車 75 fps（2026-10-09 本機實機 E2E，R=64，同一輪的相對值）。車停著時 chunk map 不前移（位移是時速 ÷ 5 再四捨五入，2.5 km/h 以下為 0 格），開車平移鏡頭也只在時速 >1 時動（`PlayerCamera.java:102`），照步行算不會多看到什麼。為了評估「停著的車照步行算」，車內分支另外計數，回傳值不變：
- 車速取 `getCurrentAbsoluteSpeedKmHour()`：伺服器收到駕駛的 `VehiclePhysicsPacket` 時寫入（`VehiclePhysicsPacket.java:94-95`），沒有遠端駕駛時讀到 0（`BaseVehicle.getCurrentSpeedKmHour`）。
- 連線上每位在車內的玩家，車速低於 1 km/h 持續 `stillSec` 秒才算停著；車一動或換車就重新計時。helper 以 `WeakHashMap<IsoPlayer, …>` 記每位玩家，只記車輛 ID、不持有車。
- 在車內的玩家全部停著時，這次 R 外判定計入 `stillSend`／`stillKeep`；已算停著的玩家又開動，計入 `stillResumes`。
- 已知偏差：被拖的車沒有駕駛，車速讀成 0，坐在裡面會被算成停著；下車再上同一台車開走也算一次 resume（略為高估）。

**開關**（需重啟）：
- `-Dmdc.vehicleRelevancy`：未設定／`2`／`observe`＝只計數、照原版（預設；未知值也是 observe）；`1`／`on`／`enforce`＝縮小；`0`／`off`＝原版。
- `-Dmdc.vehicleRelevancyRadius`：R（格），預設 64，夾在 32–160。
- `-Dmdc.vehicleRelevancyBeatSec`：心跳最短間隔（秒），預設 300，夾在 10–3600（本機量測時調短用）。
- `-Dmdc.vehicleRelevancyStillSec`：停多久才算停著（秒），預設 10，夾在 0–600；只影響停車計數。

**log**（首次判定時一行；之後每 4096 次判定看一次時間，間隔到了才印）：

```text
[MinidoracatJavaPatch][VehicleRelevancy] 首次生效 mode=observe radius=64 stillSec=10（-Dmdc.vehicleRelevancy=observe|on|off，-Dmdc.vehicleRelevancyRadius，-Dmdc.vehicleRelevancyStillSec）
[MinidoracatJavaPatch][VehicleRelevancy] mode=observe radius=64 sendOutside=<n> keepOutside=<n> passVehicle=<n> passConnectArea=<n> stillSend=<n> stillKeep=<n> stillResumes=<n> anomalies=<n>
```

- `sendOutside`：`sendVehicles` 判定「原版會送、在 R 外」的次數（每連線每台車每 100 ms 一次，所以是判定次數，不是車數）。`on` 時這些更新沒送。
- `keepOutside`：Passengers 請求判定「原版會留、在 R 外」的次數，約等於每秒每連線的 R 外車數。`on` 時每一次都是一個 `VehicleRemove`，observe 下用來估計會移除多少車。
- `passVehicle`／`passConnectArea`：在 R 外但因車內或 connectArea 照原版的次數。`anomalies` 恆應為 0。
- `stillSend`／`stillKeep`／`stillResumes`：見上方「停著的車」。`(stillSend + stillKeep) ÷ passVehicle` 是車內 R 外判定中停著的比例；`stillKeep` 約等於「停著時環帶的車 × 秒」，也就是改成照步行算後 client 少持有的車量；`stillResumes` 是開動時要整圈補回的次數。

**預期效果（推測，以正式服 `vehicles.db` 快照計算）**：上述地點原版 107 輛；圓形 R=64 剩 57 輛、R=56 剩 40 輛、R=48 剩 28 輛。依車型加權（KI5 35、KI5 拖車 12、rSemi 25、其他 4.5 µs/輛）約為原本的 54％／38％／24％。實測 5.4 ms 與車型表估算的 2.3 ms 有落差，原因未查，所以毫秒數只是推測。

**副作用**：
- 畫面：看到的世界範圍是螢幕像素 × 縮放倍率（`MultiTextureFBO2.getWidth`），在世界座標是菱形，畫面四角離角色最遠。1920×1080 約 16 格 × 縮放倍率（100% 約 16 格、最遠 250% 約 40 格）；同樣縮放下 2560×1440 最遠約 53 格、3440×1440 約 62 格、3840×2160 約 80 格（已超出原版 chunk map 的 ±72–79 格）。縮放 25%–250% 預設全部開啟（`MultiTextureFBO2.zoomLevelsDefault`、`MainOptions.lua:1426-1444`）。車在角色視線外會淡出，15 格內例外（`BaseVehicle.java:3476-3484`）。車在 R 外收到 Passengers 更新後約 0.2–1.2 秒出現，衝刺約 6–7 格/秒，所以約在 R−9 格處出現（推測）。因此步行時，R=64 在 1920×1080、2560×1440 的任何縮放下都在畫面外，3440×1440 拉到最遠、3840×2160 縮放 200% 以上時，衝刺可能在畫面四角看到車出現；R=48 在 1920×1080 最遠縮放下約有 8 格餘裕，2560×1440 以上拉到最遠就看得到（推測）。
- 流量：一台車的 `VehicleFullUpdate` 就是 `vehicle.save()`，正式服快照中位數約 10 KB、最大約 118 KB（載滿的貨櫃拖車）。單次接近時要下載的量比原版少，但在 R 邊界來回會「移除→重送」，原版不會。下車時 1 秒內移除 R 外的車；上車時一次補回環帶的車，client 解析可能卡頓（推測，要實機量）。
- 別人開的車：駛入 R 時伺服器送 `VehicleUpdate`，client 隨即要 Full，約 0.2 秒加 RTT 後出現。`VehiclePhysics` 仍以原版 ±88 轉發，client 沒有那台車時會每個物理包送一次 `VehicleRequest(1)`，伺服器只記旗標、車進入 R 時才送，不形成迴圈。
- 拖車：掛接表以 `sendToAll` 廣播，牽引車每幀 `tryReconnectToTowedVehicle`，拖車抵達後自動接回。
- 殘留風險：車剛出 R、在下一次 1 秒輪詢前又回到 R 內，這段空窗的零件變更沒送、client 也沒被移除，零件狀態可能過期到下一次該零件變化為止。原版範圍比 chunk map 寬，沒有這個空窗。
- 家族 MOD：MiniMap 的車輛圖示預設不限距離，步行時會縮到 R；AutoDrive、VehicleManager 的掃描是車內、近距離或伺服器端，不受影響。

**驗證**：
- SmokeCheck（存在理由，TIS 改即紅＝重估）：原版 `sendVehicles` 唯一 `isRelevantTo`、參數是車的 `getX／getY`、後接 `IFEQ`，方法內送 `VehicleFullUpdate`；`processServer` 唯一 `isRelevantTo` 後接 `IFNE`、再來是 `ALOAD 2` 與 `VehicleRemove`，方法內有 `SIPUSH 16384`；`isRelevantTo` 讀 `relevantRange` 兩次、不讀 `chunkGridWidth`。手術後兩個方法除了 `isRelevantTo`→helper 之外方法文字（含 frames）與原版相同、全 class 真指令數不變；兩個 class 的 `isRelevantTo` 都歸零且各只改道到自己的入口。helper 契約：兩個入口共用 `decide`，`decide` 恰呼叫原版 `isRelevantTo` 一次，並各查一次 connectArea、半徑與車內；停車判定只讀一次車速。
- `VehicleRelevancyGateTest`（observe R=64、on R=64、on R=64 stillSec=0、on R=48、on R=5 夾到 32、off，`-Xverify:all`）：真 `UdpConnection.isRelevantTo` 做原版負對照（環帶與正方形角落原版相關、89 格不相關）；R−1、恰等於 R 一律相關，`Math.nextUp(R)`、R+1、圓外正方形內的對角依模式分流；原版外一律不相關；兩個入口各自計數；車內（含分割畫面 2 號乘客）與 connectArea 照原版；分割畫面任一玩家 R 內即相關；parseMode 別名。停著的車回傳一律照原版：stillSec=0 時兩個入口各計一次、預設 10 秒時未滿不計；0.99 km/h 算停著、倒車 1.0 km/h 算在動；已算停著的車開動計一次 resume、持續在動不重複計、換到在動的車不計；分割畫面有人在動就不算停著。另外走 dist 手術後的真 `VehicleRequestPacket.processServer`：真 wire 請求、真 `VehicleRemovePacket` 寫出再解碼，R 內不移除、環帶只有 on 回 `VehicleRemove`、原版外三模式都移除、Full 請求只記旗標、車內照原版。
- 本機實機 E2E（2026-10-07，正式服 139 個 MOD 的本機複本，在車輛密集區站 20–25 秒，JFR MainThread 每幀）：off 80 fps（每幀 12.5 ms，client 持有 113 輛，`postupdate` 3.11＋`update` 1.25 ms）；on R=48 190 fps（5.2 ms，37 輛，0.81＋0.27 ms）；on R=64 137 fps（7.3 ms，57 輛，1.51＋0.49 ms）。沿同一條路步行，最大幀 off／R=48／R=64 為 119／72／96 ms，超過 50 ms 的幀 7／3／6；`anomalies=0`。
- 車內照原版的實機驗證（同一套複本，R=48）：在 56 格外圈生 6 台車，步行時 client 載入 0 台（關閉時 4 台，另 2 台關閉時也沒有載入）；坐進車後外圈載入 4 台，與關閉時相同；`passVehicle` 計數隨之增加、`anomalies=0`。另與 W54 一起開：同一處站立 198 fps（5.0 ms，37 輛），步行最大幀 68 ms。

<a id="2br"></a>
## 2br. 車輛靜止姿勢跳過（W54，client，預設 on；client 包 0.2.5）

**問題**：KI5 系列與 rSemiTruck 這類模組車的門、引擎蓋、車窗、裝甲是蒙皮（skinned）零件模型。客戶端對每台已載入的車每幀跑 `BaseVehicle.postupdate`，不分遠近、停著或看不看得到，車身與每個非 static 零件模型各呼叫一次 `updateAnimationPlayer`，後者無條件 `AnimationPlayer.Update`，整副骨架重算一次。新生成的車每台每幀：KI5 22–47 µs、rSemiTruck 車頭 32 µs，原版車 3–5 µs。2026-10-07 正式服複本 JFR（閒置、113 輛）：`postupdate` 每幀 3.76 ms，其中 `updateAnimationPlayer` 2.88 ms。零件有 `parent` 時共用 parent 的 player（窗、裝甲掛在門底下），`postupdate` 仍對每個零件模型各 Update 一次，所以同一個門的 player 每幀被算 2–3 次。

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- `BaseVehicle.updateAnimationPlayer(AnimationPlayer, VehiclePart)` 是 private，只在 `postupdate` 被呼叫兩次（offset 40 車身、offset 77 `models` 迴圈）。方法內唯一的 `invokevirtual AnimationPlayer.Update(F)V`（offset 44）在所有善後之前：移除播完的 track、窗 track 依 `openDelta` 設時間、沒有 track 時 `playPartAnim("Opened"/"Closed"/"ClosedToOpen")`。角色、殭屍、動物、機械介面 3D 車（`UI3DScene`）都走別的呼叫點。
- 沒在播放的 track 時間不前進（`AnimationTrack.tickCurrentTimeInternal` 讀 `isPlaying`，false 時 dt 設 0）。靜止的門窗姿勢（`animate = FALSE` 的 Opened／Closed／ClosedToOpen）每幀重算出同樣的矩陣：權重 1 的骨頭完全由 track 決定，權重 0 的骨頭保留上一次的值。
- renderer 是拉資料：`getSkinTransforms` 只在 `SkinTransformData.dirty` 時從 `modelTransforms` 重算蒙皮矩陣，不跑 Update 就沿用上一次的結果。
- 原版的怪癖：共用 player 被 Update k 次，每次都推進正在播放的 track，所以 KI5 的門開關動畫在原版是 k 倍速（MOD 的 `rate` 是在這個行為下調的）。「同一幀只 Update 一次」會讓門變慢 2–3 倍，本刀不做。

**手術**：`updateAnimationPlayer` 內唯一的 `AnimationPlayer.Update(F)V` 1:1 改道 `zombie/mdc/VehicleAnimGate.update(AnimationPlayer, float)`（3 bytes 換 3 bytes、堆疊形狀不變、frames 原樣），`expectedHits = 1`，新 ClassPatch `zombie/vehicles/BaseVehicle`。標準版與省記憶體版都含這刀，模組版本 `v3.2`。

**判定**（helper 以 `WeakHashMap<AnimationPlayer, 簽章>` 記每個 player，player 被 GC 時自動消失；池回收再用的 player 會帶 `needFirstFrame`，第一次一定照算）：
- 簽章：model、skinningData（身分）、track 數、角度與目標角度、`doBlending`，以及每條 track 的身分、clip 身分、`currentTimeValue`、`getBlendWeight()`、`getBlendFieldWeight()`、`getLayerIdx()`、`priority`、`isPlaying`／`reverse`／`looping`／`hasBoneMask()`。
- 照原版算：任一 track `isPlaying`、`isBoneTransformsNeedFirstFrame()`、`parentPlayer != null`、`updateBones == false`、`isRecording()`、沒有 track，或簽章與上一次完整計算不同。同一份簽章要連續完整算過兩次才開始跳過，讓 first-frame 與權重 <1 的混合多走一步。
- 只在第一個呼叫的執行緒（主執行緒）運作；其他執行緒照原版並計 `foreignThread`。簽章讀取拋例外時照原版並計 `anomalies`。

**抽樣比對**：每 256 次本來會跳過的呼叫抽 1 次照算，比對算前算後的 `modelTransforms`（16 個 float 逐一比，相對誤差，絕對值小於 1 時以 1 計），超過 1e-5 就本次啟動永久回原版、清空簽章表並記一行。verify 模式永遠照算，只比對與計數。

**kill switch**：`-Dmdc.vehAnimSkip=on`（預設；未設定或未知值）｜`verify`（`2`）｜`off`（`0`），需重啟。

**log**（console.txt）：

```text
[MinidoracatJavaPatch][VehicleAnimGate] active mode=on sampleEvery=256 tolerance=1.0E-5
[MinidoracatJavaPatch][VehicleAnimGate] calls=<n> computed=<n> skipped=<n> sampled=<n> mismatches=0 players=<n> foreignThread=0 anomalies=0 disabled=false
[MinidoracatJavaPatch][VehicleAnimGate] pose mismatch bone=<i> diff=<d> tracks=<n>; vanilla Update for the rest of this session calls=…
```

第一行（`mode=on`／`verify`／`off`，設了旗標時附原值）在第一台蒙皮車第一次更新時出現，off 也會印，可用來確認開關生效；統計行只在 on／verify 每 5 分鐘一行（每 4096 次呼叫檢查一次時間）。

**不做的事**：
- 同幀去重：見根因最後一點；只讓最後一次呼叫算骨頭、其他次只推進時間的版本可以保住倍速，但本刀上線後停著的車已經全部跳過，它只剩開門那一秒有用。
- `ModelInfo.getAnimationPlayer` 的查找快取（同一份 JFR 每幀 0.71 ms）：有 parent 的零件會遞迴到 parent 的 `getAnimationPlayer`，每次都 `ModelManager.getLoadedModel(檔名)`，裡面是 `ScriptManager.getModelScript` 的字串雜湊查表（`ScriptBucketCollection.getScript` 做 `contains(".")`、模組與名稱查表），`getModelInfoForPart` 的線性掃描只是比對參照、成本小得多。快取 `getLoadedModel` 的結果無法證明與原版逐次相同：Lua 重載、腳本重載、`ModelScript.loadedModel` 被清掉、`setModelVisible` 換模型都會讓結果改變，沒有單一失效訊號可掛。只快取 `getModelInfoForPart` 也不划算：`models` 是 `ArrayList`，`modCount` 在 `java.base` 裡不能讀，一個零件也可能有多個 ModelInfo（原版回第一個），驗證快取等於重掃一遍。

**守門與驗證**：
- SmokeCheck（兩個變體）：存在理由與設計前提——`updateAnimationPlayer` 是 private 且全 class 只在 `postupdate` 被呼叫 2 次、方法內恰一個 `Update(F)V` 且在 `getModelInfoForPart` 之前、`tickCurrentTimeInternal` 讀 `isPlaying`、`getSkinTransforms` 讀寫 `dirty`；手術後除了這一個呼叫之外方法文字（含 frames）與原版相同；`postupdate`、`playPartAnim` 未改動。LoadCheck：`VehicleAnimGate.update(AnimationPlayer, float)` 為 public static void。
- `VehicleAnimGateBehaviorTest`（裸 JVM，真 `AnimationPlayer`／`AnimationTrack`／`SkinningData`／`AnimationClip`；`build-client.ps1` 對兩個變體各跑 on、verify、off 三次）：合成一台 KI5 式的車（門的 player 由門、窗、裝甲共用，另有後車廂），同一組輸入跑原版（A）與改道（B），零件呼叫順序與善後照 javap 的 `updateAnimationPlayer` 與 `playPartAnim`，每幀比對 `modelTransforms`、蒙皮矩陣（逐位）與每條 track 的時間。情境：S1 靜止 300 幀；S2 搖窗後停住；S3 開門動畫（仍是 3 倍速）；S4 播完換 Opened；S5 player 還回池再配置（拿回同一物件）；S6 換 skinningData；S7 `parentPlayer` 與沒有 track；S8 `updateBones=false`；S9 就地改 clip 的關鍵影格（簽章看不到）。on 每幀 A==B 逐位相同，S9 由抽樣在 42 幀內抓到並回原版，之後每幀 A==B；verify 不跳過、本來會跳過的 2,537 次全部比對；off 每次呼叫都落到原版（N1）。負對照：同幀只 Update 一次（N2）測試抓得到門變慢；窗時間改變那幀不算（N3）測試抓得到姿勢不同。

**實機量測**（2026-10-07，同一份正式服複本、同一處車輛密集區，JFR MainThread 每幀，W54＋W55 一起）：off 75.6 fps（每幀 13.2 ms；`postupdate` 3.45 ms，其中 `updateAnimationPlayer` 2.62、`ModelInfo.getAnimationPlayer` 0.71）→ on 113 fps（8.8 ms；`postupdate` 0.78 ms，`updateAnimationPlayer` 0.15、`getAnimationPlayer` 0.53）。verify 模式在 113 台正式服車、每台再額外呼叫 1000 次 `postupdate` 下 0 次 pose mismatch；開關門動畫截圖與原版相同。

**殘留**：簽章看不到的輸入（同一個 track 物件就地換骨頭遮罩、clip 資料被改、debug 選項）會讓畫面最多停在舊姿勢 256 次跳過，由抽樣比對抓到後本次回原版。

<a id="2bs"></a>
## 2bs. `isBoneReparented` 快速路徑（W55，client，預設 on；client 包 0.2.5）

**問題**：`AnimationPlayer.updateMultiTrackBoneTransformsInternal` 對每根骨頭呼叫一次 `isBoneReparented(int)`，原版每次都 `Integer.valueOf`、`Lambda.predicate`（從池配置）再 `PZArrayUtil.contains(reparentedBoneBindings, …)`。車輛、殭屍與多數角色的清單是空的，結果一定是 false。車輛 JFR 中它約佔 `AnimationPlayer.updateInternal` 樣本的 27%（SemiTruck 100／373、M998 94／341）。

**手術**：該方法內唯一的 `invokevirtual AnimationPlayer.isBoneReparented(I)Z`（offset 115，也是全 jar 唯一呼叫點）1:1 改道 `zombie/mdc/BoneReparentFastPath.isBoneReparented(AnimationPlayer, int)`，`expectedHits = 1`，新 ClassPatch `zombie/core/skinnedmodel/animation/AnimationPlayer`。helper 以 `MethodHandles.privateLookupIn` 取得 private final `reparentedBoneBindings` 的 VarHandle；清單為空回 false，否則呼叫原版 `isBoneReparented`（本體未改）。結果與原版逐位相同，所有走標準動畫路徑的 player 都受惠（角色、殭屍、動物、車、機械介面 3D 車）。欄位找不到時記一行並一律委派原版。

**kill switch**：`-Dmdc.boneReparentFast=off`（或 `0`）一律委派原版，需重啟。第一次用到時在 console.txt 記一行 `[MinidoracatJavaPatch][BoneReparentFastPath] active=true|false`（設了旗標時附原值）。

**守門與驗證**：
- SmokeCheck（兩個變體）：存在理由——原版 `isBoneReparented` 以 `Lambda.predicate`＋`PZArrayUtil.contains` 掃 `reparentedBoneBindings`（TIS 自己加快速路徑時轉紅＝重估撤刀），欄位是 `ArrayList`；`AnimationPlayer` 內唯一的呼叫點在這個方法；手術後除了這一個呼叫之外方法文字與原版相同；`isBoneReparented` 本體未改。LoadCheck：helper 為 public static boolean。
- `BoneReparentFastPathBehaviorTest`（兩個變體各跑 on、off）：沒有 reparent、有 reparent、池回收後清空三種狀態下每根骨頭 helper 與原版相同；60 幀真動畫（含 reparent 的 player）的 `modelTransforms` SHA-256 摘要 on 與 off 必須相同，由 `build-client.ps1` 比對。裸 JVM 微基準（只報告）：空清單時原版約 90–100 ns／次。

<a id="2bt"></a>
## 2bt. 補算等畜牧區就緒（W56，server，預設 on）

**問題（2026-10-07 玩家回報）**：牧區有很多槽、槽裡飼料和水都是滿的，玩家離線幾小時回來，牛羊飢渴卻一片黃紅，「好像結算時當成沒吃沒喝」。正式服 10-01（W49 上線）起，畜牧區整區載入時的補算（zone 路徑）明細 15,730 筆中 15,569 筆 `applied=0`，涉及 323 個畜牧區；回報的牧場每一筆都是 0。

**原因（42.21.0 反編譯＋javap）**：
- 卸載時 `IsoFeedingTrough.removeFromWorld` 把槽移出 `zone.troughs`；槽回到清單只有兩條路：建構子（新蓋的槽）與 `DesignationZoneAnimal.check()`。`check()` 開頭 `isFullyStreamed()` 為假就 return（畜牧區兩個角落格都載入才跑），同時重建地上食物與河邊格；`addToWorld` 不登記。
- 伺服器以 64×64 cell 為單位載入：`ServerMap.ServerCell.RecalcAll2` 在同一個呼叫裡依 x 遞增對 64 個 chunk 逐一 `doLoadGridsquare`；每個 chunk 先讓物件 `addToWorld`，再經 `AnimalPopulationManager.addChunkToWorld` → `AnimalManagerMain.fromWorker` 立刻呼叫 `updateStatsAway`（chunk 路徑補算）。這時槽還沒登記，補算的 `eatAndDrinkAfterMeta` 找不到槽，整段不吃不喝。
- 原版之後在 `DesignationZone.update`（每 2.5 秒）偵測到畜牧區整區載入，`doMeta` 先 `check()` 再以 zone 時數補一次——等於重複補算，但第二次吃得到槽。W42（2be）為了擋重複補算取 min(zone 時數, 動物自身離線時數)，chunk 路徑補完後動物時鐘已到現在，第二次就變 0 小時。所以「吃不到槽」是補丁讓它浮現：原版靠重複補算才餵飽。
- 本機 MP E2E（W56 之前的 dist）：牛群飢渴設 0.5、離開讓 chunk 卸載、撥快 10 小時再回來，槽所在格載入當下 `zone.troughs` 登記 0 個；出貨組態與 `-Dmdc.animalOwnClock=0` 回來後停在 0.56／0.61、槽沒動；`-Dmdc.animalCatchUpCap=0`（原版時數）+1 秒吃到 0.05。

**手術**（共 5 個命中點；新 ClassPatch ×3，`IsoAnimal` 併入既有 ClassPatch）：
- `IsoWorld.update` 內唯一的 `DesignationZone.update()`（javap offset 52）改道 `AnimalAwayProbe.zoneUpdate`。
- `AnimalPopulationManager.removeChunkFromWorld` 內唯一的 `IsoAnimal.unloaded()`（offset 133）改道 `AnimalAwayProbe.unloaded`。
- `IsoFeedingTrough.addToWorld` 兩個 `checkOverlayAfterAnimalEat()`（offset 40 副槽找到的主槽、45 主槽）改道 `AnimalAwayProbe.troughAddedToWorld`。
- `IsoAnimal.removeFromWorld` 頭部 headCall `AnimalAwayProbe.leavingWorld`。

**行為**（helper 在既有的 `zombie.mdc.AnimalAwayProbe`，延後條目是 `AnimalAwayProbe$Deferred`）：
1. **延後**：chunk 路徑上有自身時鐘、不是野生、站在畜牧區內的動物，照 W49 算好時數後不當場補算，設 `fromMeta=true` 並排隊。原版 `updateInternal` 開頭 `fromMeta` 為真就整段 return，所以延後期間動物凍結，也不會刷新時鐘。畜牧區外、野生、沒有時鐘紀錄的照舊當場補。
2. **排空**：`zoneUpdate` 先照原版 `DesignationZone.update()`，再（finally）處理佇列。一筆就緒的條件：動物所在畜牧區與所有相連畜牧區涵蓋的 chunk 都已載入（`getChunkForGridSquare`，伺服器走 `ServerMap`）；或延後超過 `-Dmdc.animalCatchUpDeferMs`（預設 10000）。就緒的那些先對涉及的畜牧區各 `check()` 一次，再依延後順序把時鐘還原成延後當下的值、以記下的時數補算。伺服器每幀先 `ServerMap.preupdate`（cell 載入）再 `IsoWorld.update`，所以整個 cell 內的畜牧區同一幀就補完。
3. **一致性**：zone 路徑（`doMeta`）略過延後中的動物，避免同一段時間補兩次；直接呼叫（拖車 Lua、管理員指令，`entryHours`）接手時取消延後；延後中被 chunk 卸載時，原版 `unloaded()` 把時鐘寫成現在，helper 再還原成延後當下的值，下次載入從頭補（凍結期間沒有模擬）；延後中要離開世界（抱起、放進拖車、`remove()`）時在 `removeFromWorld` 頭部當場補完（`IsoChunk.removeFromWorld` 先交給 `AnimalPopulationManager` 才逐一 `removeFromWorld`，卸載走不到這裡）；補算對象跟著 `AnimalData.parent`（放下時原版 `copyFrom` 換新物件、共用同一份資料）；延後中死亡的不補。
4. **存檔**：`MdcAnimalSave.clockToWrite`（W49-A3）原本對世界中的動物寫存檔當下；延後中的動物（`fromMeta` 為真）改寫自身時鐘，存檔後重啟不會丟掉那段離線時間。
5. **槽登記**：`troughAddedToWorld` 先 `checkZone()`（原版建構子用的同一個登記）再呼叫原方法；畜牧區只載入一部分（例如跨 server cell、另一側沒載入）時，已載入的槽也看得到，活動物的 AI 同樣受惠。登記失敗只計數，不影響載入。
6. **例外**：排空時 `check()` 或補算丟 RuntimeException 只計數、不外拋（不讓 `IsoWorld.update` 中斷），動物一律解凍；補算中途失敗時記一行（補到哪個時鐘、還差幾小時），剩下的時數與原版在 `fromWorker` 拋出時一樣不補。

**開關**（需重啟）：`-Dmdc.animalCatchUpDefer=0` 停用延後（依賴 W49，`-Dmdc.animalOwnClock=0`／`-Dmdc.animalCatchUpCap=0` 時一併停用）；`-Dmdc.troughZoneRegister=0` 停用槽登記；`-Dmdc.animalCatchUpDeferMs` 等待上限（毫秒）。

**log**：`[AnimalAwayProbe]` 心跳追加 `deferred`（排隊）、`deferDrained`（補完）、`deferTimeouts`（逾時才補）、`deferUnloaded`、`deferDead`、`deferZoneSkips`、`deferDirect`、`deferLeaving`、`deferTransferred`、`deferFailures`、`deferPending`、`maxDeferMs`、`zoneRefreshes`、`defer`、`troughRegister`；逾時才補的那筆另記 `source=chunk-timeout` 明細。`maxDeferMs` 平時是幾十毫秒；開機後第一位玩家進場時會到 1–3 分鐘：`PauseEmpty=true` 時，玩家還沒進入世界前伺服器暫停、不跑 `IsoWorld.update`，排空跟著停，這段期間世界本身也沒有前進。`deferTimeouts` 來自跨 64 格 server cell 邊界（或與這種畜牧區相連）、只載入一側的牧場。

**限制**：畜牧區一直只載入一部分時，逾時後只用已載入的槽補算；沒載入那一側的槽、河與地上食物用不到（活動物同樣走不到）。部分載入時河邊格與地上食物不重建，屬原版 `isFullyStreamed` 閘（待裁定，見 open-issues）。

**驗證**：
- SmokeCheck（存在理由，TIS 修好即紅＝重估）：槽 `removeFromWorld` 移出 `zone.troughs`、`addToWorld` 不登記、`checkZone` 全 jar 恰 2 處且都在建構子；`check()` 開頭 `isFullyStreamed()` 為假即 return、之後才碰三個清單；`updateInternal` 開頭 `fromMeta`→return，`IsoAnimal.update` 在其外不碰時鐘、`AnimalData.update` 全 jar 恰 1 處；`fromWorker` 先設 `fromMeta` 再補算；`unloaded()` 一開始把時鐘寫成現在、全 jar 恰 2 處；`IsoChunk.removeFromWorld` 先交給 `AnimalPopulationManager` 才逐一 `removeFromWorld`；`DesignationZone.update()` 全 jar 恰 1 處。手術後各方法除了改道／頭部兩條指令外文字（含 frames）與原版相同，三個新 class 其餘方法逐字不變。
- `AnimalCatchUpDeferTest`（on、nodefer、noown、notrough、deferms0）：真 `DesignationZoneAnimal`（`getZoneF`／`getAllDZones` 走原版）配替身動物。on：排隊並凍結、重複 chunk 路徑不重排、zone 路徑略過、相連區未全部載入就等、就緒後先重建再補（補算當下看得到槽、從延後當下的時鐘起算）、同批共用畜牧區只重建一次並依順序補、逾時照補、延後中卸載還原時鐘、死亡不補、直接呼叫接手、畜牧區外／野生／無時鐘當場補、重建與補算例外不外拋且解凍、離開世界當場補、換成新物件時補在新物件。nodefer／noown 是原版負對照：chunk 路徑補算時看不到槽，之後 zone 路徑被截成 0 小時。deferms0 走正式入口 `zoneUpdate`。槽登記用真 `checkZone`。`MdcAnimalSaveTest` 四組態：延後中的動物在 objectList 也寫自身時鐘（noown 寫存檔當下）。
- 本機實機 MP E2E（42.21.0 dedicated server，dist 放 classpath 最前；三頭牛、飼料槽＋水槽、飢渴設 0.5、離開卸載後撥快 10 小時再回來；每種組態各跑兩輪）：

| 組態 | 牧場 | 回來第一筆 飢餓／口渴（兩輪） | 結果 |
|---|---|---|---|
| 出貨 | 8×8 一個 chunk | 0.044–0.056／0.088–0.097 | 第一筆就吃到；`maxDeferMs` 15、24 |
| 出貨 | 16×8 跨兩個 chunk，牛在 x 較小、槽在 x 較大的 chunk | 0.046–0.054／0.000–0.097 | 第一筆就吃到；`maxDeferMs` 22、21 |
| `animalCatchUpDefer=0` | 同上跨 chunk | 0.538–0.562／0.600–0.614 | 挨餓（槽在牛補算之後才進世界） |
| `animalCatchUpDefer=0` | 8×8 一個 chunk | 0.046–0.055／0.000–0.094 | 吃到（同 chunk 只靠槽登記就夠） |

  槽所在格載入當下 `zone.troughs` 登記數從 0 變成 1、2（槽登記生效）；八輪 `anomalies=0`、`deferTimeouts=0`。

**上線驗收（2026-10-08 17:58）**：10-08 03:45 MOD 更新重啟時套用（job 狀態 `APPLIED`、03:46 起的 session 橫幅即為新版，舊的 123 個 class 封存在 job 的 `state/`；之後每次開服 `[mdc-javagate] OK: 127`）。之後六個 session（重啟都是 MOD 更新或定時重啟，沒有當機）橫幅都是 `server patch ffed4ff`，127 個 loose class 逐檔 SHA 與 manifest 相符，零 linkage 錯誤，各刀心跳 `anomalies` 都是 0；`deferFailures`、`delegateFailures` 都是 0。各 session 最後一筆心跳合計延後 20,342 筆、補完 20,259 筆（逾時才補 1,308 筆）、延後中卸載 71 筆。`maxDeferMs` 57–181 秒都出在開機後第一位玩家進場（見上段），逾時集中在跨 server cell 邊界的牧場。補算後 2 秒內死亡、飢或渴 ≥0.8 的家畜：畜牧區內從 227／644（35%，10-04 起 41 個 session）降到 2／62（3%）；畜牧區外（W56 不處理）47% 與 44% 不變。同一幀連續補算的最長耗時 240–702 ms，與之前 11–790 ms 同範圍；看門狗記到的凍結都在關服。

<a id="2bu"></a>
## 2bu. 自建房 chunk 載入斷線（W57，client，預設 on；client 包 0.2.6）

**症狀**（2026-10-09 玩家回報；官方 bug report [102213](https://theindiestone.com/forums/topic/102213-title-b4221-nullpointerexception-in-isoroomhaslightswitches-roomdef-is-null-when-loading-chunks-in-player-built-areas/) 是另一位 MP 玩家回報的同一問題）：跨 chunk 走進有自建房的區域時，右下角跳錯、畫面一黑、被斷線送回主選單；同一區時好時壞。玩家的 client log（42.21.0 `4a0e9546ec`）只有一條例外：`NullPointerException: Cannot read field "objects" because "this.def" is null at IsoRoom.hasLightSwitches(IsoRoom.java:604)`，往上是 `IsoLightSwitch.chunkLoaded(IsoLightSwitch.java:879)` ← `IsoChunk.loadInMainThread(IsoChunk.java:2930)` ← `IsoChunkMap.updateInternal`。`IngameState.updateInternal` 的 catch 接住後停掉 `WorldStreamer`、存檔、`doDisconnect("crash")`。

**根因**（javap 對 42.21.0 jar `e1a69eb7`；LineNumberTable：IsoRoom 604＝offset 12–27 的 `getfield def`→`getfield RoomDef.objects`，IsoLightSwitch 879＝offset 61–82，含 `hasLightSwitches`，IsoChunk 2930＝`loadInMainThread` offset 1862–1863 的 `IsoLightSwitch.chunkLoaded`）：
- 格子在 World Streamer 執行緒反序列化 chunk 時就綁房間：`IsoChunk.LoadFromDiskOrBufferInternal` 唯一的 `IsoGridSquare.setRoomID`（offset 880）經 `IsoChunk.getRoom`、`IsoMetaGrid.getRoomByID` 取得或建立 `IsoMetaCell.isoRooms` 裡的 IsoRoom。之後 chunk 進 `IsoChunk.loadGridSquare` 佇列；主執行緒的 `IsoChunkMap.updateInternal` 每幀只取有限數量，`setChunkDirect` 放進 ChunkMap 後才 `doLoadGridsquare` → `loadInMainThread`。
- 在這段空窗裡，只要同一個或相鄰 cell（256×256 格）的 IsoRegions 有變更（有人蓋、拆或打壞牆、地板、門、圍籬；server 把區域變更廣播給所有連線），client 主執行緒的 `IsoRegions.update` → `DataRoot.clientProcessBuildings` → `WorldRegionToMetaGrid.clientProcessBuildings` 就把這些 cell 的**所有** user-defined building 拆掉重建：`removeIsoRoom` → `IsoRoom.clear()` 把 def 設成 null、清空 lightSwitches。重建後的 `updateSquares` 只走 `IsoCell.getChunkMap(i).getChunk(x, y)`；佇列裡的 chunk 不在 ChunkMap，格子仍指向被清空的 IsoRoom（roomId 不是 -1，`getRoom()` 照樣回它）。
- `loadInMainThread` 的 `IsoLightSwitch.chunkLoaded` 對每個有房間的格子呼叫 `hasLightSwitches()`：lightSwitches 已空，接著讀 `def.objects` 就 NPE；有開關時下一步的 `createLights(room.def.lightsActive)` 也一樣。所以只要有一格指向被清空的房間，原版一定斷線。
- 只有自建房會遇到：預製建築不會被 `clientProcessBuildings` 拆掉。時好時壞是因為要剛好在空窗內遇到重建；移動時排隊的 chunk 越多，空窗越長。
- 與 R1（[101035](https://theindiestone.com/forums/topic/101035-42204-mp-client-kicked-to-main-menu-isometacellisorooms-hashmap-raced-between-main-loop-and-world-streamer-thread-aioobe-in-recalculatebuildingandroomids/)，42.20.4 `isoRooms` HashMap 並行存取的 AIOOBE）同一個上游缺陷：主執行緒重建 metagrid 時沒有和 World Streamer 協調。R1 是兩個執行緒同時寫同一個 HashMap，這次是交接中的 chunk 拿到已被清空的房間。

**手術**：`IsoChunk.loadInMainThread()V` 內唯一的 `invokestatic IsoLightSwitch.chunkLoaded(IsoChunk)V`（offset 1863）1:1 改道 `invokestatic zombie/mdc/StaleRoomGuard.chunkLoaded(IsoChunk)V`（描述子相同、3 bytes 換 3 bytes、frames 原樣），`expectedHits = 1`，新 ClassPatch `zombie/iso/IsoChunk`。另一個呼叫點 `recalcNeighboursNow`（@Deprecated，jar 內沒有呼叫者）不動。helper 先用與原版 `chunkLoaded` 相同的走訪，數出房間 def 為 null 的格子：
- 沒有：直接呼叫原版 `IsoLightSwitch.chunkLoaded`，結果逐位相同。
- 有（原版必定 NPE）：以 `MethodHandles.privateLookupIn` 取得 `WorldRegionToMetaGrid` 的 `lambda$updateSquares$0`、`$1`。這兩個 private static 方法是 `updateSquares` 交給 `forEachSquare` 的逐格邏輯：前者重新 `setRoomID`、設 `roofHideBuilding`、建房間燈並登記到 cell、標毒氣、把格子上的開關掛到房間；後者設 `associatedBuilding`、`RecalcProperties`、通知尋路。helper 依原版 `forEachSquare` 的順序對這個 chunk 的格子各跑一遍，再做 `updateSquares` 對每個更新 chunk 的收尾：`invalidateRenderChunkLevels(2112L)`、`getCutawayData().invalidateAll()`、`checkLightingLater_AllPlayers_AllLevels()`，最後呼叫原版 `chunkLoaded`。這等於補上原版漏掉的「重建後重綁這個 chunk」，狀態與「重建時這個 chunk 已在 ChunkMap 裡」相同。此時 chunk 還沒交給尋路（`addChunkToWorld` 在 `doLoadGridsquare` 後段），`PolygonalMap2.squareChanged` 對它不做事：原生尋路以 `loadedBits` 擋掉，Java 版查不到 chunk 就略過。

**kill switch**：`-Dmdc.staleRoomHeal=off`（或 `0`）一律直接呼叫原版，需重啟。第一次載入 chunk 時在 console.txt 記一行 `[MinidoracatJavaPatch][StaleRoomGuard] active=true|false`（設了旗標時附原值）；每次補綁記一行 `chunk <wx>,<wy>: <N> squares pointed to a cleared IsoRoom; rebound like WorldRegionToMetaGrid.updateSquares (#<n>)`，每次啟動最多 20 行。

**不處理**：R1 的 HashMap 並行存取（client 包 0.2.9 起由 W68 處理，見 [2cd](#2cd)）；格子原本沒有房間、重建後才有的情況（不會當機，要等下一次重建或重新載入，原版對已載入的 chunk 也是這樣）；`IsoLightSwitch.load` 在 streamer 執行緒讀到正在被清空的房間（另一個時間點，沒有回報）。

**守門與驗證**：
- SmokeCheck（兩個變體）：存在理由——`LoadFromDiskOrBufferInternal` 恰一個 `setRoomID`、`IsoRoom.clear` 把 def 設成 null、`hasLightSwitches` 讀 `def.objects` 前沒有 null 檢查（TIS 改了任一處就轉紅＝重估撤刀）；helper 依賴——`updateSquares` 只走 `IsoChunkMap.getChunk`，依序以 static `lambda$updateSquares$0`（含 `setRoomID`）、`$1`（含 `RecalcProperties`）呼叫 `forEachSquare`，收尾的 `2112L`、cutaway、lighting 各一，helper 的 `RENDER_DIRTY_FLAGS` 與原版常數相同；手術後 `loadInMainThread` 除了這一個呼叫之外文字（含 frames）與原版相同；負對照 `recalcNeighboursNow` 未改動。LoadCheck：helper 為 public static void。
- `StaleRoomGuardBehaviorTest`（兩個變體各跑 on、off）：真 `IsoMetaGrid` 一個 cell（IsoMetaCell／IsoMetaChunk 的房間查詢、`getRoomByID` 建出的 IsoRoom 與 IsoBuilding）、真 `IsoChunk`／`IsoGridSquare` 建構子，只有 IsoWorld、IsoCell、IsoMetaGrid 以 Unsafe 配置。原版負對照：16 格綁在已 `clear()` 的房間，原版 `chunkLoaded` 拋出與玩家 log 一字不差的 NPE。沒有過期格子時 helper 與原版逐項相同。on：房間被重建——不拋例外、16 格改綁新的 IsoRoom、室內不是 exterior 而室外是、`associatedBuilding` 指向新建築、玩家擺的開關掛到新房間、房間燈登記到 chunk 與 cell；房間被拆掉——不拋例外、64 格都沒有房間且是 exterior。off：`active=false`，過期格子照原版拋同一個 NPE。
- 實機重現與驗證（2026-10-09，本機 SP `-debug`，關殭屍）：起點 14322,4969（原版 Trailer3 蓋房示範的地點），在周圍 20–50 格蓋 8 間 3×3 封閉、z=1 鋪地板當屋頂的自建房，各在不同 chunk；每輪傳送到約 4000 格外、等 6 秒再傳回，接著 8 秒內每個 tick 在起點旁拆／放一面牆。Steam 原版（沒有任何 loose class）兩輪都在第一次傳回時拋出與玩家 log 逐行相同的 NPE（`IsoRoom.java:604` ← `IsoLightSwitch.java:879` ← `IsoChunk.java:2930` ← `IsoChunkMap.updateInternal`）並退回主選單。client 包 0.2.6（`client v3.3(b932af1)`，標準版，以安裝器裝在遊戲本體的 hardlink 複本）跑完 12 輪零例外，8 間房始終都認得；第一、二次傳回就記到 20 次補綁（每次啟動的記錄上限），每次 3–9 格。另一輪在進世界前視窗被關掉，未計。

**退場**：官方修好後 SmokeCheck 的存在理由會轉紅，屆時從 `PatchConfig.client()` 移除本刀，並刪除 helper 與測試。

<a id="2bv"></a>
## 2bv. SyncIsoObject 洪流觀測（W58，server，純觀測）

**為什麼要記**：2026-10-11 正式服 console 在 01:40 與 01:42 兩個時段共印了 163 次 `Packets limit has exceeded for SyncIsoObject`（`MaxPacketsPerSecond=1000`，代表單一連線 1 秒內送超過 1000 包），同時段某基地附近兩位玩家的客戶端卡到 1 FPS 以下。原版這行警告（`PacketsCache.isLimitExceeded`）不記是哪條連線，抓不到送的人，也看不出是哪個物件。伺服器收到 SyncIsoObject 後，`IsoObject.syncIsoObject` 的 bRemote 分支會不分距離轉送給其他所有連線（`IsoObject.java:910-921`），一位玩家的洪流會變成全服每條連線各收一份。

**手術**：`GameServer.receiveSyncIsoObject(ByteBufferReader, UdpConnection, short)` 內唯一的 `invokevirtual IsoObject.syncIsoObject(ZBLUdpConnection;LByteBufferReader;)V`（offset 115，前面是 `sq.getObjects().get(index)` 與 `checkcast IsoObject`）1:1 改道 `invokestatic zombie/mdc/SyncIsoObjectProbe.syncIsoObject`（receiver 變第一參數），`expectedHits = 1`，掛在既有的 GameServer ClassPatch 上。這個方法在主執行緒的 `mainLoopDealWithNetData` 派送。javap 語境見 `docs/specs/zombie_network_GameServer.json`。helper 先在 try 內記帳，再在 try 外照原樣呼叫原方法，回傳與例外都與原版相同：
- 每條連線（connected GUID）一個 1 秒視窗：視窗過期後的第一包重新起算。同一視窗第 201 包（門檻 200）計一次 flood 並印一行；同一連線 10 秒內的下一次 flood 只計 `suppressed`，下一行帶上這段期間的數目。
- 每 5 分鐘一行 beat（首次呼叫立即印首次生效行），同時清掉 60 秒沒送封包的連線。
- 熱路徑不配置物件（新連線與 log 除外）；簿記例外只計 `anomalies`。

```text
[MinidoracatJavaPatch][SyncIsoObjectProbe] flood#<n> player=<帳號> steamId=<id> guid=<guid> packets=201 windowMs=<ms> rate=<包/秒>/s obj=<class> sprite=<名稱|?> at=<x>,<y>,<z> state=<byte> first=<class> sprite=<名稱|?> at=<x>,<y>,<z> sameAsFirst=<n>/201 suppressed=<n>
[MinidoracatJavaPatch][SyncIsoObjectProbe] beat calls=<n> floods=<n> logged=<n> suppressed=<n> maxWindow=<這 5 分鐘單一視窗最大包數> maxPlayer=<帳號> tracked=<連線數> anomalies=<n>
```

`obj` 是越過門檻的那一包，`first` 是這個視窗的第一包；`sameAsFirst` 是視窗內有幾包打在第一包的同一個物件上（接近 201 就是同一個物件在洪流）。`windowMs` 是主執行緒處理第一包到第 201 包經過的毫秒，`rate` 由它換算；同一幀排隊的封包會一起處理，`windowMs` 接近 0 時 `rate` 只代表一幀內就收到 201 包。`maxWindow` 也算沒越過門檻的連線，可看出平常的峰值。

**kill switch**：`-Dmdc.syncIsoObjectProbe=0|off`（預設開；改道仍在，只呼叫原方法，不記也不印），需重啟。

**部署**：manifest 新增 `zombie/mdc/SyncIsoObjectProbe.class` 與 `SyncIsoObjectProbe$Conn.class`（128 → 130 個 class），`GameServer.class` 換成多一個命中點的版本。manifest 只增不減，不必先卸載：用延後生效流程 stage＋arm（不手動重啟），下一次排程重啟時切換。上線後先確認橫幅指紋是新版、`[mdc-javagate] OK: 130`，再看 console 出現首次生效行。

**判讀**：出現 `Packets limit has exceeded for SyncIsoObject` 時，同一秒附近的 flood 行就是來源連線與物件；拿 `obj`／`first` 的 class、sprite 與座標到現場看是什麼物件、誰在操作。門檻 200 遠低於原版的 1000，警告之前就會先記到。

**未做**：不擋包、不限速、不改轉送範圍；等抓到來源與物件再決定修哪裡。

**守門與驗證**：
- SmokeCheck：原版 `receiveSyncIsoObject` 恰一個 `IsoObject.syncIsoObject`，且是 GameServer 全 class 唯一；手術後方法文字（含 frames）只差這一個呼叫；helper 內原方法恰呼叫一次。
- `SyncIsoObjectProbeTest`（on、off 兩個 JVM）：原版負對照——直接呼叫原方法（未改道的路徑）不記帳、不印；200 包不算 flood、第 201 包恰一行且欄位齊全（帳號、steamId、guid、物件與第一包物件、`sameAsFirst=150/201`）；同一視窗不重複計；10 秒內的下一次 flood 只計 suppressed，10 秒後再印並帶上 `suppressed=1`；另一條連線各自計數；beat 帶最大視窗與玩家、清掉閒置連線；log 時讀 sprite 拋例外只計 `anomalies`、原方法照常呼叫；原方法拋出的例外原樣上拋；`connection=null` 照常呼叫。off：原方法每包一次、不記帳、整個 JVM 零 log 行。

<a id="2bw"></a>
## 2bw. 世界地圖隊友點錯位（W59，server，預設 on）

**症狀**（2026-10-12 正式服 42.21.0，`MapRemotePlayerVisibility=2`，只顯示同陣營或同安全屋）：伺服器重啟後，同陣營的兩位一般玩家幾乎同時上線，出生點相距約 12 格，一進遊戲就在彼此畫面上。Player-A 的小地圖和世界地圖都看不到 Player-B，Player-B 卻看得到 Player-A。已排除：地圖 MOD 的圖層開關（引擎畫的本機名牌和隊友點綁同一個設定，名牌有畫）、其他 MOD、伺服器可見規則（`shouldSendWorldMapPlayerPosition` 比對兩人的 Faction，兩個方向結果相同）、偽裝與隱形。看不到客戶端內部狀態，所以根因是推論；機制可以從反編譯完整推出，並在行為測試裡用真程式重現。

**原版機制**（42.21.0 反編譯＋javap）：
- 隊友點由引擎畫：`UIWorldMap.renderRemotePlayers` → `shouldShowRemotePlayer` 的第一個檢查是 `WorldMapRemotePlayer.hasFullData()`，只有 `setFullData` 會把它設成 true。
- 伺服器每秒對每條連線送 `WorldMapPlayerPosition`（`isFullUpdate=false`：onlineId、changeCount、x、y）。客戶端 `GameClient.receiveWorldMapPlayerPosition` 只在本機條目的計數和收到的值不同時回送索取；伺服器 `receiveWorldMapPlayerPosition` 回完整資料（`isFullUpdate=true`），客戶端 `setFullData` 之後才有 `hasFullData`。這個封包型別是 `UNRELIABLE_SEQUENCED`，索取或回覆掉了都不會重送。
- 客戶端每幀 `IsoGameCharacter.renderlast` → `updateUserName` → `IsoPlayer.resetDisplayName` → `WorldMapRemotePlayer.setPlayer`；`setPlayer` 只要有欄位不同就 `changeCount++`，不碰 `hasFullData`。新條目是 0，畫面上有這位隊友時第一幀就被推到 1。
- 伺服器條目第一次 `setPlayer` 後是 1，之後沒有改名、改身分、隱形或偽裝就一直是 1。onlineId 每次開機重新分配，所以重啟後每個條目都從 1 開始。
- 結果：條目剛建立時只要掉一包，客戶端被第一幀推到 1，剛好等於伺服器的 1，從此不再索取，這位隊友在這位玩家的大小地圖都不畫。要等條目被刪掉（遠端玩家 5 秒沒更新的逾時、PlayerTimeout）或伺服器端計數改變才恢復。單向是因為只有一個方向掉了封包。

**手術**：GameServer 全 class 恰 2 個 `invokevirtual WorldMapRemotePlayer.getChangeCount()S`，下一條都是 `ByteBufferWriter.putShort(S)V`：`sendWorldMapPlayerPosition(UdpConnection)` offset 139（前面是 `aload 4`）與 `receiveWorldMapPlayerPosition(ByteBufferReader, UdpConnection, short)` offset 174（前面是 `aload 7`）。兩處 1:1 改道 `invokestatic zombie/mdc/WorldMapChangeCount.getChangeCount(WorldMapRemotePlayer)S`，各自 `expectedHits = 1`，掛在既有的 GameServer ClassPatch 上；javap 語境見 `docs/specs/zombie_network_GameServer.json`。helper 回傳 `BASE + 2 × 原計數`（轉成 short），`BASE` 是每次開機隨機選的偶數，範圍 1024–16382。伺服器條目本身的計數不動，伺服器也從不讀客戶端的計數。

**為什麼這樣就夠**：客戶端還沒拿到完整資料時，本機值是條目建立後 `setPlayer` 偵測到的欄位變化次數，通常是 1；伺服器送出的值是偶數，原計數 8192 以內至少 1024。兩邊不會相等，客戶端每秒重新索取，直到回覆送達。拿到之後本機值等於送出值，之後的相等條件與原版相同。附帶效果：客戶端本機的 +1 不會再剛好等於伺服器下一次的計數，伺服器端的變化不會被漏掉。

**流量**：和原版相同。畫面上的隊友原版就每秒重新索取一次（伺服器送 accessLevel `None`，客戶端的角色名是 `user`，每次拿到資料後下一幀 `setPlayer` 都會 +1），改了以後一樣；畫面外的隊友拿到資料後計數相等，不再索取。只有原本會卡住的條目多出每秒一次索取，直到拿到資料為止。

**kill switch**：`-Dmdc.worldMapChangeCount=0|off`（預設開）回傳原值，送出的 wire 與原版相同，需重啟。第一次送出時在 console 記一行 `[MinidoracatJavaPatch][WorldMapChangeCount] 首次生效 base=<BASE>（-Dmdc.worldMapChangeCount=0|off 回原版）`。

**部署**：只改伺服器，玩家不用裝任何東西。manifest 新增 `zombie/mdc/WorldMapChangeCount.class`（130 → 131 個 class），`GameServer.class` 換成多兩個命中點的版本。manifest 只增不減，不必先卸載：用延後生效流程 stage＋arm，下一次排程重啟時切換。上線後確認橫幅指紋是新版、`[mdc-javagate] OK: 131`，有玩家看得到別人之後 console 出現首次生效行。

**守門與驗證**：
- SmokeCheck：存在理由——原版 GameServer 恰 2 個 `getChangeCount`（兩個送出方法各 1，下一條都是 `putShort`），GameClient 只在 `receiveWorldMapPlayerPosition` 讀 1 次（TIS 改了比較方式就轉紅，屆時重估）；手術後兩個方法文字（含 frames）只差這個呼叫、GameServer 不再直接讀計數；helper 內原計數恰讀一次。
- `WorldMapChangeCountTest`（on、off 兩個 JVM）：伺服器端跑手術後的真 `GameServer.sendWorldMapPlayerPosition(UdpConnection)` 與 `receiveWorldMapPlayerPosition`，客戶端跑真 `GameClient.receiveWorldMapPlayerPosition`；封包由真 writer 與 `PacketType` 寫進只覆寫 buffer 端點的連線，由測試決定送達或丟掉，客戶端每幀直接呼叫真 `setPlayer`。掉一次回覆、掉一次索取兩種情境：原版負對照（兩種模式都跑，餵原版計數的位置包）與 off 之後 10 秒都不索取、`hasFullData` 一直是 false，重現症狀；on 每秒索取，回覆送達後 `hasFullData=true`。沒掉包時畫面上 10 秒索取 10 次、畫面外 0 次，兩種模式相同。伺服器條目計數不被改；原計數 0–8192 的送出值都是至少 1024 的偶數；off 送原值、零 log 行。

<a id="2bx"></a>
## 2bx. 動物遺失／複製觀測（W60，server，純觀測）

**為什麼要記**：2026-10-13 一位玩家回報雞舍裡原本有 4 隻公雞，搬走一隻後回來只剩 2 隻。存檔顯示這 4 隻是同一隻公雞的複製體（同一個 animalId，從 10-08 起 1 隻慢慢變成 4 隻），搬走後總數少了一隻，沒有死亡紀錄，也不在背包或附近的車上。全服 apop 另有 15 群、61 隻同 ID 複製體。伺服器 Animal log 等級是 Warning，原版會刪除或丟失動物的幾條路徑都不留紀錄，看不出是哪一條。

**原版機制**（42.21.0 反編譯＋javap）：
- **放下動物**：`IsoGridSquare.AddWorldInventoryItem` 遇到 `AnimalInventoryItem` 時新建 `IsoAnimal`、`copyFrom` 手上的動物，再以手上動物的 onlineID 查 `AnimalInstanceManager`；查到的「前持有者」只要不是新動物，就 `removeFromWorld`、`removeFromSquare`、從管理器移除（`IsoGridSquare.java:6082-6097`）。伺服器上從雞舍 chunk 載入的動物用 `IsoAnimal(IsoCell)` 建構（不走 `init`，不配 onlineID），`IsoAnimal.load` 只在客戶端設定 onlineID（`IsoAnimal.java:1495-1500`），所以 onlineID 停在 `IsoPlayer` 的欄位預設值 1（`IsoPlayer.java:334`）。從雞舍抓起再放下時，查到的是當下持有這個 ID 的別隻動物，牠被移出世界、之後不再存檔。這是推論，還沒有實際抓到。
- **chunk 卸載後立刻重載**：`ServerChunkLoader$LoaderThread` 對每個 chunk 先 `saveNow(wx, wy)` 再 `LoadChunk`。`saveNow` 只處理還在存檔佇列裡的工作（`ServerChunkLoader.java:490-511`），存檔執行緒已經取走、正在寫的那份不在佇列裡，載入可能讀到舊檔或寫到一半的檔。雞舍內的動物跟著 chunk 存，舍外的在 APM 記憶體：讀到舊雞舍時，同一隻會同時出現在雞舍和舍外（複製），或兩邊都沒有（消失）。
- **長大**：`AnimalData.grow` 先讓新動物沿用舊 animalId，再 `checkForChickenpocalypse`：±4 格內有別隻同 ID 的動物就刪掉長大前的動物，grow 接著刪掉新動物（`AnimalData.java:1406-1444`、`IsoAnimal.java:275-296`），只印 `DebugType.Animal.println`。複製體長大時兩隻一起消失。

**手術**：六個呼叫各自 1:1 同形改道 `zombie/mdc/AnimalLossProbe`（receiver 變第一參數），helper 在 try 外照原樣呼叫原方法恰一次，回傳與例外都與原版相同；javap 語境見 `docs/specs/zombie_animal_loss_probe.json`。
- `IsoGridSquare.AddWorldInventoryItem(InventoryItem, float, float, float, boolean, boolean)`：offset 144 `IsoAnimal.copyFrom` → `dropCopy`、168 `AnimalInstanceManager.get` → `dropLookup`、210 `AnimalInstanceManager.add` → `dropRegister`，`expectedHits = 3`（新的 ClassPatch）。三個呼叫依序發生，helper 照這個順序配對：`copyFrom` 記手上的動物，`get` 在原版移走前記下前持有者的位置與狀態，`add` 判定。
- `ServerChunkLoader$LoaderThread.run()`：offset 226 `IsoChunk.LoadChunk` → `loadChunk`，`expectedHits = 1`（新的 ClassPatch）。載入前後各查一次：另一條執行緒正在存同一個 chunk 的卸載存檔就記一行。
- `ServerChunkLoader$SaveUnloadedTask.save()`：offset 5 `IsoChunk.Save(Z)V` → `saveUnloaded`，`expectedHits = 1`（新的 ClassPatch）。存檔前登記執行緒與開始時間，finally 移除；另一條執行緒也在存同一個 chunk 時記一行。
- `AnimalData.grow(String)`：offset 313 `IsoAnimal.checkForChickenpocalypse` → `growDuplicate`，`expectedHits = 1`（掛在既有的 AnimalData ClassPatch）。回 true 時在同範圍找出同 ID 的另一隻一起記。

```text
[MinidoracatJavaPatch][AnimalLossProbe] drop-evict#<n> at=<x>,<y>,<z> dropped=<種類>#<animalId> onlineId=<id> evicted=<種類>#<animalId> pos=<x>,<y>,<z> inWorld=<0|1> hutch=<0|1> vehicle=<0|1> dead=<0|1>
[MinidoracatJavaPatch][AnimalLossProbe] drop-dup#<n> at=<x>,<y>,<z> dropped=<種類>#<animalId> onlineId=<id> source=<種類>#<animalId> pos=… inWorld=1 … prev=<none|種類#animalId>
[MinidoracatJavaPatch][AnimalLossProbe] reload-race#<n> chunk=<wx>,<wy> phase=<before|during> savingThread=<執行緒名> saveElapsedMs=<n>
[MinidoracatJavaPatch][AnimalLossProbe] double-write#<n> chunk=<wx>,<wy> threads=<先>,<後> otherElapsedMs=<n>
[MinidoracatJavaPatch][AnimalLossProbe] grow-dup#<n> animal=<種類>#<animalId> pos=… newType=<長大後種類> twin=<種類>#<animalId> pos=…
[MinidoracatJavaPatch][AnimalLossProbe] beat drops=<n> evicted=<n> dropDups=<n> loads=<n> unloadedSaves=<n> reloadRaces=<n> doubleWrites=<n> growDups=<n> logged=<n> suppressed=<n> anomalies=<n>
```

`drop-evict` 是放下動物時被移出世界的別隻（`evicted` 是牠被移走前的狀態；描述失敗時寫 `?`）。`drop-dup` 是手上的動物放下後仍在世界上，等於多了一隻。明細每 60 秒最多 30 行，其餘計 `suppressed`；第一次事件（通常是開機後第一次載入 chunk）印首次生效行，之後每 5 分鐘一行 beat。簿記例外只計 `anomalies`。

**kill switch**：`-Dmdc.animalLossProbe=0|off`（預設開；改道仍在，只呼叫原方法，不記也不印），需重啟。

**部署**：manifest 新增 `zombie/mdc/AnimalLossProbe.class`、`AnimalLossProbe$Saving.class` 與三個新 patched class（`IsoGridSquare`、`ServerChunkLoader$LoaderThread`、`ServerChunkLoader$SaveUnloadedTask`），`AnimalData.class` 換成多一個命中點的版本（131 → 136 個 class）。manifest 只增不減，不必先卸載：用延後生效流程 stage＋arm，下一次排程重啟時切換。上線後確認橫幅指紋是新版、`[mdc-javagate] OK: 136`，再看 console 出現首次生效行。

**判讀**：玩家再回報動物變多或消失時，先找同一時段、座標附近的明細。`drop-evict` 的 `evicted` 就是消失的那隻；`reload-race` 的 chunk 座標（×8＝格座標）對到雞舍或圍欄時，用 `hutch-animal-scan.py` 比對前後存檔看多了或少了哪隻。三種都沒有出現，就是還有別的路徑，再加觀測。

**未做**：不擋、不修；等抓到是哪條路徑再決定怎麼修（放下時只移走同一隻、載入前等正在寫的存檔寫完等）。

**守門與驗證**：
- SmokeCheck：原版四個方法各恰 1 個被觀測的呼叫；放下動物依序 `copyFrom` → `get` → `add`；LoaderThread 先 `saveNow` 再 `LoadChunk`。手術後四個方法文字（含 frames）只差這六個呼叫；helper 內每個原方法恰呼叫一次。
- `AnimalLossProbeTest`（on、off 兩個 JVM）：放下動物照手術後的順序呼叫三個 helper，用真 `AnimalInstanceManager`。原版負對照（直接呼叫原方法）不記帳、不印。手上的動物就是前持有者時不記；前持有者是持有 onlineID 1 的別隻時記一行 `drop-evict`，帶放下位置與被移走那隻的種類、ID、位置；手上的動物放下後仍在世界上記一行 `drop-dup`；描述前持有者時例外只計 `anomalies`、原方法照常；`copyFrom` 的例外原樣上拋。長大：`checkForChickenpocalypse` 回 true 時記一行 `grow-dup`，帶真 `IsoGridSquare.getAnimals` 在 ±4 格內找到的同 ID 另一隻（`ServerMap` 替身回傳格子）；回 false 不記。chunk：另一條執行緒持有存檔登記時載入同一個 chunk 記 `reload-race`；不同 chunk、同一條執行緒先存後載（`saveNow`）都不記；兩條執行緒同時存同一個 chunk 記 `double-write`；改道後的 `saveUnloaded` 跑真 `IsoChunk.Save`（noSave）不留登記。off：原方法照常呼叫，不記帳、整個 JVM 零 log 行。

<a id="2by"></a>
## 2by. 開車被夾回過期 chunk 的瞬移（W61，client，預設 on；client 包 0.2.7）

**症狀**（2026-10-14 正式服，同一時段多位玩家回報）：開車時車周圍突然一片黑（客戶端的 chunk 一起消失），接著車被丟到幾十到幾百格外（樹林、水裡），AutoDrive 交還並顯示「離路太遠停止」；有時跳過去幾十秒後又跳回來。同時段伺服器主迴圈 9.1–10.0 fps、chunk 供給沒有積壓，伺服器效能與當天新上線的 MOD 都已排除。

**證據**：
- AutoDrive 診斷片段（10-07～10-14 共 16 件夾回，含 10-14 兩位玩家的 3 件）：前一筆取樣（約 0.2 秒前）已載入範圍的前緣離車心還有 40.8 m 以上（中位約 63 m，2 件前方全部已載入），下一筆只剩約 2 m，也就是車周圍的 chunk 一起從客戶端消失，不是車開進黑邊。接著一筆車速三項精確為 0（物理凍結，5 Hz 取樣看到 9 件），再下一筆座標落在另一個 chunk 的邊界 ±0.0005（14 件角落、2 件單軸）。例：車在 chunk (a,b)，落點是 chunk (a−8,b+7) 的 `maxX − 0.0005`、`minY + 0.0005`，距離 77 格；最多一件 3.5 秒內連跳 5 次。黑邊最嚴重的玩家（可視損失 20%、520 趟）0 次夾回，黑邊多寡與瞬移沒有正相關。
- 同時段伺服器反作弊記到 `Anti-cheat="Speed" … VehiclePhysicsUnreliablePacket: speed=198–833 > limit=150`。`AntiCheatSpeed` 用的 `NetworkCharacterAI.SpeedChecker` 算的是客戶端回報位置的位移 ÷ 約 1 秒，所以是瞬移的結果；反作弊只記錄，不拉車（`react` 是空的、`sync` 沒有覆寫）。
- 伺服器 cmd.txt 另有兩次 755、891 格的大跳，落點在該玩家 1–3 分鐘前開過的地方附近（x 是 8 的倍數、y 是 8 的倍數減 1）。這兩次比較像既有回報 [2026-10-01 車被留在已卸載的 chunk](report/2026-10-01-vehicle-orphaned-chunk-river-warp-tis.md) 的路徑：車停止送位置、伺服器留著舊位置，之後 `VehiclePacket.doRemove()` 把本機駕駛移到那個舊位置；落點的 chunk 邊界座標是之後夾回留下的（推論）。W61 不處理這條（見「不處理」）。

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- `BaseVehicle.update()`（offset 1538–1815）：`sq = getCell().getGridSquare(getX(), getY(), getZ())`；`sq == null && !this.chunk.refs.isEmpty()` 時，把 x、y 夾進 `this.chunk.wx*8` 到 `+8`（離邊 `5.0E-4f`），`setZ(0.2)`，用現在的旋轉組成 t1，`setWorldTransform(t1)`（客戶端呼叫 `Bullet.teleportVehicle`，速度歸零），再 `current = getGridSquare(…)`。offset 1818 起，`current` 有格子而且屬於別的 chunk 時才把 `this.chunk` 換過去。
- 客戶端卸載 chunk（`IsoChunkMap.Up/Down/Left/Right` 移出邊界，或整張重建的 `Unload()` → `refs.remove` → `removeFromWorld`）不會清掉車上的 `chunk`：`IsoChunk.removeFromWorld` 只處理格子上的 moving objects；`resetForStore()` 清空 `vehicles`、`refs`，`wx`、`wy` 歸零後放回 `chunkStore`。`IsoChunk` 與 `IsoChunkMap` 所有方法裡，只有 `IsoChunk.update` 寫 `vehicle.chunk`（指回自己）。
- `IsoChunkMap.LoadChunk` 從 `chunkStore` 取回物件、設好新座標後，先 `refs.add(this)`（offset 123）才 `WorldStreamer.addJob`（offset 135）。車還指著這種物件時，原版條件就成立，車被夾到新座標的角落；`refs` 在載入完成前就非空，所以也可能夾進一塊還沒載入的 chunk，下一幀再夾一次。
- 塌陷是整張 chunk map 重建：`IsoChunkMap.ProcessChunkPos` 以「玩家位置＋車速前視」為網格中心（駕駛前視 `km/h ÷ 5` 格、沒有上限；乘客上限 20 格），中心單幀跳動達到網格寬（約 720p 為 13、1080p 以上 19 個 chunk）時呼叫 `Unload()`，整張 map 的 `refs` 清掉、`removeFromWorld`、送去存檔回收，並 `WorldSimulation.deactivateChunkMap`。一般行駛一幀只平移一排，車底下那塊不會卸載。這次是什麼讓中心跳動未定，候選有兩個：駕駛的車速讀值尖峰一幀（網格 13 時要 ≥ 520 km/h）、`VehiclePacket.doRemove()` 把駕駛移到伺服器位置（推論，都沒有直接證據）。
- 車的 `chunk` 怎麼變成過期：整張重建時，有本機乘客的車 `BaseVehicle.removeFromWorld()` 提早 return，車仍掛在已卸載的 chunk 上（同上回報）。之後依那個 chunk 物件的狀態分三段：(A) 尚未回收，`vehicles` 仍有這台車、`refs` 為空，`update()` 開頭每幀呼叫 `removeFromWorld()`（又提早 return）就 return，不送位置、乘客不擺位；(B) 已回收（`vehicles` 清空），開頭只送 `VehicleRequest(2)` 就往下跑，`refs` 為空所以不夾回；(C) 被 `LoadChunk` 重用到別處，`refs` 又非空，往下跑到夾回，車被夾到新座標的角落。另外，車開在沒有格子的地方時 `current` 是 null，`chunk` 停在最後一塊有格子的 chunk，也可能這樣落後（推論）。

**手術**：`update()V` 內唯一的 `invokevirtual BaseVehicle.setWorldTransform(Transform)V`（offset 1776，在夾回區塊內）1:1 改道 `invokestatic zombie/mdc/VehicleClampGuard.setWorldTransform(BaseVehicle, Transform)V`（3 bytes 換 3 bytes、堆疊形狀不變、frames 原樣），`expectedHits = 1`，加在 W54 已有的 client `BaseVehicle` ClassPatch 上。標準版與省記憶體版都含這刀，模組版本 `v3.4`。

**判定**（呼叫時原版已經把 x、y 改成夾回值、z 設成 0.2，`jniTransform.origin` 仍是車真正的物理位置 P）：
1. 原版要夾進的 `this.chunk` 已載入、車在它的 `vehicles` 清單裡、與 P 所在的 chunk 相鄰（相距 ≤ 1 個 chunk）：照原版。這是車剛離開的那一塊，也就是正常的往回拉。
2. 否則在 P 所在 chunk 與相鄰的 8 塊裡找已載入、而且 ChunkMap 位置與自身座標一致的 chunk，取離 P 最近的一塊，用原版同樣的夾法（離邊 0.0005）夾進去，改寫 x、y 與 t1 後放車。往回拉最多約 8 格（斜角約 11 格）。
3. 相鄰都沒有已載入的 chunk：不放車（不呼叫 `setWorldTransform`），只把 x、y 改回 P。這時 P 所在的 chunk 沒載入，原版 `checkSurroundingChunks`（`WorldSimulation.updateVehiclePhysics` 每個物理子步對每台車檢查周圍 3×3，有一塊沒載入就 `setPhysicsActive(false)`）已經停掉這台車的物理，車留在原處，高度與方向都不變，等那一塊載入。結果與原版 (B) 段相同（`refs` 為空、不夾回、`update()` 照常往下跑）：乘客擺回座位，`updatePhysicsNetwork()` 不看物理是否啟用，每 150–300 ms 照送 `Bullet.getOwnVehiclePhysics` 的位置（P），伺服器 `setX/Y` 跟上。所以即使是 `doRemove()` 把駕駛移到伺服器舊位置觸發的塌陷，伺服器也會收斂到 P，不會在兩處之間來回重建；代價是駕駛回到車上時 chunk map 可能再重建一次（推論）。不往回拉兩格以上，那本身就是玩家看得出來的瞬移。

接著原版從 offset 1789 起依新位置取 `current`；有格子時把 `this.chunk` 換成車真正所在的 chunk，過期參照就此修好。判定途中拋出例外時照原版（只記第一次）。

**kill switch**：`-Dmdc.vehicleClampGuard=off`（或 `0`）一律照原版，需重啟。第一次走到夾回時在 console.txt 記一行 `[MinidoracatJavaPatch][VehicleClampGuard] active=true|false`；每次介入記一行（每次啟動最多 20 行）：

```text
[MinidoracatJavaPatch][VehicleClampGuard] vehicle <id> <車型> at <x>,<y>: vanilla would clamp it into chunk <wx>,<wy> (loaded=<b>, listed=<b>, <n> chunks away) at <x>,<y>; clamped into loaded chunk <wx>,<wy> at <x>,<y> (#<n>)
[MinidoracatJavaPatch][VehicleClampGuard] vehicle <id> <車型> at <x>,<y>: vanilla would clamp it into chunk … at …; no loaded chunk next to it, left where it is (not placed) (#<n>)
```

**不處理**：
- 觸發塌陷的整張重建本身（觸發點未定，見「根因」），以及一般黑邊（成因在 chunk 串流，見 [2o](#2o)、W4-1）：本刀只讓「拉回」不拉到別處。
- 同一族的另外三條路徑（見 [2026-10-01 回報](report/2026-10-01-vehicle-orphaned-chunk-river-warp-tis.md)，修法建議 2、3）：(a) 車掛在已卸載的 chunk 上時，`update()` 每幀提早 return，不送 VehiclePhysics、引擎聲停、乘客位置不更新；(b) `VehiclePacket.doRemove()`：客戶端沒有伺服器位置的格子、車上有本機玩家時，把駕駛移到伺服器的舊位置（2026-10-01 的 175 格、2026-10-14 的 755／891 格大跳）。這兩條由 [2bz](#2bz) 的 W62、W63 處理。(c) 沒有本機乘客的車（例：拖車）在 chunk 卸載時直接被 `removeFromWorld()`，或 `VehicleManager.clientUpdateVehiclePos` 在伺服器位置沒載入時移除，可能就是拖車只在客戶端消失（推論，未實證），仍未處理。
- 掉穿地面：車所在的 chunk 有格子、但物理地面還沒建好時，這段不會觸發（z 一律用 0 查格子）。2026-10-14 換駕駛後掉到地圖下（z −33 以下）屬於這一類；坐在車上時鏡頭高度直接用車的物理高度，畫面全黑但 HUD 照常（同上回報第 4 點），另案處理。

**守門與驗證**：
- SmokeCheck（兩個變體）：存在理由——`update()` 恰一個 `setWorldTransform`，在 `sq == null`（IFNONNULL）後緊接 `this.chunk.refs.isEmpty()`→IFNE 的夾回區塊內，夾法只讀 `wx`、`wy` 與 `5.0E-4f`，不讀 `loaded`、`vehicles`（TIS 補上任一檢查就轉紅＝重估撤刀），helper 的 `EDGE` 與原版常數連動；`resetForStore` 清空 `vehicles` 與 `refs`，`IsoChunk`／`IsoChunkMap` 只在 `IsoChunk.update` 把 `vehicle.chunk` 指回自己；`LoadChunk` 從 `chunkStore` 取物件、先 `refs.add` 再 `addJob`。規則 3 的依據：`WorldSimulation.updateVehiclePhysics` 每子步呼叫 `checkSurroundingChunks`，`hasChunksAllAround` 以 `IsoCell.getChunk`＋`loaded` 查周圍，不齊就設 `disableSimulationDueToLackOfSurroundingChunks` 並 `setPhysicsActive`，`setPhysicsActive(ZZ)` 讀這個旗標。手術後 `update()` 除了這個呼叫之外文字（含 frames）與原版相同；負對照：`setWorldTransform` 本體與另一個呼叫點 `flipUpright` 未改動。LoadCheck：helper 為 public static void。
- `VehicleClampGuardBehaviorTest`（兩個變體各跑 on、off）：查表用真 `IsoCell.getChunk`→`IsoChunkMap.getChunk`，chunk 是真 `IsoChunk`，放車用真 `BaseVehicle.setWorldTransform`（`GameServer.server=true`，不呼叫原生 Bullet）；每個情境先照原版夾回區塊準備 x、y、z 與 t1。原版負對照：車指著被重用到別處的 chunk 時，夾回目標在那一塊的角落、離車 77 格，off 照原版把車放過去。on：過期而且遠——改夾進相鄰的已載入 chunk，只往回 1.6 格，x、y 與物理位置一致、高度 0.2；車剛離開的 chunk（已載入、在清單裡、相鄰）——結果與原版相同；在清單裡但隔了兩格——不照原版往回拉 10 格，改夾進相鄰的那一塊；相鄰已載入但車不在清單裡——改夾進最近的那一塊；周圍都沒有已載入的、或最近的隔了兩格——不放車，物理位置與高度都不變。
- 實機驗證：未做。要重現得先造出整張重建（例如單幀把玩家位置拉遠 13 個 chunk 以上），再碰上車的 chunk 物件被重用的時機，本機還沒做出來；上線後看玩家 console.txt 的 `VehicleClampGuard` 行（每行附原版會把車放到哪裡）。

**退場**：官方修好（夾回前檢查 chunk，或卸載時解除車的參照）時 SmokeCheck 的存在理由會轉紅，屆時從 `PatchConfig.client()` 移除本刀，並刪除 helper 與測試。

<a id="2bz"></a>
## 2bz. 車被留在已卸載 chunk 上的兩條路徑（W62、W63，client，預設 on；client 包 0.2.7）

**症狀**：開車時引擎聲突然消失、車自己慢下來，接著駕駛被拉回幾百格外的舊位置，有時落進河裡、畫面全黑（[2026-10-01 回報](report/2026-10-01-vehicle-orphaned-chunk-river-warp-tis.md)：175 格；2026-10-14 伺服器 cmd.txt：755、891 格，落點都在 1–3 分鐘前開過的地方附近）。玩家之間流傳的避開法是「引擎聲一停就沿原路倒車」。

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- chunk 卸載（一般平移或整張重建，見 [2by](#2by)）時，`IsoChunk.removeFromWorld` 對還在 cell 裡的車呼叫 `BaseVehicle.removeFromWorld()`。那個方法先 `breakConstraint(false,false)`、`VehiclesDB2.setVehicleUnloaded`，接著只要任一座位是 `IsoPlayer.players` 裡的人就 RETURN，不設 `removedFromWorld`，車仍掛在已卸載的 chunk 上。
- W62 的對象：`BaseVehicle.update()` 開頭（offset 49–106）`this.chunk.vehicles.contains(this)` 為真、`!GameServer.server`、`this.chunk.refs.isEmpty()` 時呼叫 `removeFromWorld()`（又在本機乘客那裡 RETURN）後 RETURN。所以那個 chunk 物件被回收之前，整個 update 每幀被跳過：不送 VehiclePhysics（伺服器留著舊位置）、乘客不擺位、`updateSounds` 不跑（引擎聲停），也不夾回。倒車把那塊 chunk 帶回 ChunkMap、或物件被回收後，才恢復。同一段的另一個分支是 chunk 物件已回收（`vehicles` 已清空）：客戶端只送 `VehicleRequest(2)` 就往下跑。
- W63 的對象：`VehiclePacket.doRemove()`（`VehicleUpdatePacket`、`VehicleFullUpdatePacket` 的 parse 都會先走它）在 `this.square == null`（伺服器位置在客戶端沒有格子，z 一律用 0 查）時，對坐在這台車上的本機玩家 `player.setPosition(伺服器 x, y, 0)`（offset 75），再送 `VehicleRequest(2)`；整個方法不看車的授權。駕駛的客戶端是這台車的物理權威（Local／LocalCollide），伺服器的位置只是它送出去的值；W62 那段停送位置時，這個值就停在舊處，doRemove 便把駕駛移回去。位移超過網格寬時 chunk map 整張重建，接著車可能被夾到別處（[2by](#2by)）。

**手術**：
- W62：`update()V` 內 4 個 `invokevirtual java/util/ArrayList.contains(Object)Z` 全部 1:1 改道 `invokestatic zombie/mdc/VehicleOrphanGuard.contains(ArrayList, Object)Z`。開頭那個是要處理的判斷；另 3 個是 `getAnimals().contains(animal)`（只在非客戶端執行）與兩個 `assert this.chunk.vehicles.contains(this)`（沒開 `-ea` 不執行），helper 對它們回傳原值。與 W61 同一個 MethodOps，`expectedHits = 5`（W61 1＋W62 4）。
- W63：`VehiclePacket.doRemove(IConnection)Z` 內唯一的 `invokevirtual IsoPlayer.setPosition(FFF)V` 1:1 改道 `invokestatic zombie/mdc/VehicleOrphanGuard.setPosition(IsoPlayer, float, float, float)V`，`expectedHits = 1`，新的 client ClassPatch。
- 兩刀都是 3 bytes 換 3 bytes、堆疊形狀不變、frames 原樣；標準版與省記憶體版都含，模組版本仍是 `v3.4`。

**判定**：
- W62：清單是車自己 chunk 的 `vehicles`、清單裡有這台車、不是伺服器、那塊 chunk 的 `refs` 為空、任一座位是 `IsoPlayer.players` 裡的人（與 `removeFromWorld` 提早 RETURN 的條件相同）時回 false；其餘一律回 `list.contains(o)`。回 false 之後原版走「chunk 已回收」那條分支：客戶端送 `VehicleRequest(2)`（批次送出，伺服器只把下次更新附上位置），然後照常往下跑。物理照原版：周圍沒載入時 `checkSurroundingChunks` 已經停住車；`refs` 為空所以不夾回；`updatePhysicsNetwork()` 照送位置，伺服器跟上；車底下的 chunk 載入後，offset 1818 起原版把 `this.chunk` 換過去並從舊 chunk 的清單移除。也就是讓這段與原版回收後那段相同，不再每幀跳過。沒有本機乘客的車照原版被移除。
- W63：玩家所在的車 `isLocalPhysicSim()`（客戶端 Local 或 LocalCollide）時不移動玩家，其餘照原版（別人開的車上的乘客、Server 授權、不在車上）。原版接著照常送 `VehicleRequest(2)`，這個封包照樣被吃掉；伺服器收到本機下一次 VehiclePhysics 就改回本機的位置。

**kill switch**：`-Dmdc.vehicleOrphanUpdate=off`（W62）、`-Dmdc.driverRecallGuard=off`（W63），或 `0`，一律照原版，需重啟。第一次用到時在 console.txt 記一行 `[MinidoracatJavaPatch][VehicleOrphanGuard] active orphanUpdate=true|false driverRecall=true|false`；每次介入記一行（同一台車掛在同一塊 chunk 上算一次，每次啟動各最多 20 行）：

```text
[MinidoracatJavaPatch][VehicleOrphanGuard] vehicle <id> <車型> at <x>,<y>: its chunk <wx>,<wy> was unloaded with a local passenger aboard; vanilla would skip every update, kept updating (#<n>)
[MinidoracatJavaPatch][VehicleOrphanGuard] vehicle <id> <車型> at <x>,<y>: server position <x>,<y> is not loaded here and this client simulates the vehicle; vanilla would move the driver there, kept in the vehicle (#<n>)
```

**不處理**：
- chunk 卸載當下那次 `removeFromWorld()` 的 `breakConstraint`（斷開拖車約束）與拖車本身被移出客戶端世界（2by「不處理」(c)）。
- 掉穿地面、整張重建的觸發點：見 2by。
- 開 `-ea` 時，W62 讓 offset 1869 的 assert 在車換到新 chunk 那一幀不成立；原版回收後那段（`vehicles` 已清空）本來就同樣不成立，遊戲預設不開 `-ea`。

**守門與驗證**：
- SmokeCheck（兩個變體）：W62 存在理由——`update()` 恰 4 個 `ArrayList.contains`，第一個的前後指令逐條核對（`aload_0; getfield chunk; getfield vehicles; aload_0; contains; ifne` → 不含：`GameClient.client`、`sendVehicleRequest(id, 2)`、`goto` 續行點；含：`GameServer.server`、`chunk.refs.isEmpty()`、`removeFromWorld()`、`RETURN`，所有分支都跳到同一個續行點），另 3 個是 animals 1、assert 2；`BaseVehicle.removeFromWorld` 讀 `IsoPlayer.players`，第一個 RETURN 在設 `removedFromWorld` 之前；`IsoChunk.removeFromWorld` 恰一個 `BaseVehicle.removeFromWorld()`。W63 存在理由——`doRemove` 恰一個 `IsoPlayer.setPosition(FFF)V`，前面是 `player.getVehicle() == vehicleID.getVehicle()`（IF_ACMPNE），參數是 `position.getX()、getY()、0`，之後 `sendVehicleRequest(…, 2)`；方法只呼叫 BaseVehicle 的 `removeFromWorld`、`removeFromSquare`（TIS 補上授權判斷就轉紅）。手術後 `update()` 與 `doRemove` 除了改道的呼叫之外文字（含 frames）與原版相同；負對照：`VehiclePacket.doRequest`、`parse` 未改動。LoadCheck：兩個 helper 的簽名。
- `VehicleOrphanGuardBehaviorTest`（兩個變體各跑 on、`updateOff`、`recallOff`，關掉的那一把必須與原版相同、另一把照常）：W62——chunk 已卸載、車上有本機乘客回 false（同一段只記一次），關掉時回 true（原版負對照）；車上只有別人、chunk 仍被參照、別的清單、chunk 物件已回收、伺服器端，都與 `ArrayList.contains` 相同。W63——Local、LocalCollide 不移動，關掉時照原版移到伺服器位置；Remote、Server 授權與不在車上都照原版移動。
- 實機驗證：未做（與 W61 同樣要先造出卸載時機）。上線後看玩家 console.txt 的 `VehicleOrphanGuard` 行，以及引擎聲突然消失的回報是否減少。

**退場**：官方修好（卸載時把車移到已載入的 chunk、`update()` 不再對本機乘客的車提早 return，或 `doRemove` 不傳送本機模擬的車的駕駛）時，SmokeCheck 的存在理由轉紅，屆時從 `PatchConfig.client()` 移除對應的改道，並刪除 helper 與測試。

<a id="2ca"></a>
## 2ca. 車被推到地板下（W64 server，預設 on；W65 client，預設 on，client 包 0.2.7 起）

**症狀**（2026-10-14 正式服）：一位玩家開 '82 Oshkosh M911（KI5）在大門口以 0–1 km/h 倒車，碰到後面停著的 W900（rSemiTruck），畫面立刻全黑，只剩 HUD 與小地圖；下車後車陷在地面裡，上不去，重登也一樣。玩家錄影：碰撞那一幀之後全黑約 37 秒，下車後車身一半在地面下。伺服器 cmd.txt 同一秒有這台車的 `vehicle.crash`（客戶端判定水平速度變化超過 1 m/s 才送）。

**症狀二**（2026-10-15 正式服）：一位玩家開車兜風時被丟進虛空：畫面全黑、只剩 HUD 與小地圖，聽得到附近的殭屍卻什麼都看不到，車速表讀到 346 km/h（空檔、引擎轉速 0）；下線再上線還是一樣。伺服器 vehicles.db 唯讀查詢：這台車 23:07 的每小時備份還在約 360 格外、車身朝上、物理高度 0.220；兩分鐘後玩家斷線，伺服器存的是物理高度 −32.000、四輪朝天（up −0.993），全庫 7,535 台只有它在地板下 1 m 以上。這位玩家的客戶端沒有裝修補包；推論是 [2by](#2by) 那一族的瞬移，車在客戶端還沒有地面的地方一路往下掉。

**根因**（javap 對 42.21.0 jar `e1a69eb7`；伺服器 vehicles.db 唯讀查詢）：
- 車的物理只在駕駛（或撞到它的人，LocalCollide）的客戶端算，這次是原生物理在碰撞中把 M911 推到地板下。伺服器 `VehiclePhysicsPacket.processServer` 照單全收客戶端回報的高度（`setZ`、`jniTransform.origin.set`），不看樓層。vehicles.db 裡這台車的物理高度是 −0.599（另外兩台 M911 是 0.506、0.507）。
- `BaseVehicle.update()` 只用物理高度推算樓層（`fastfloor(y / 2.44949 + 0.05)`，那一格或下一格有地板才採用，否則 0），不把車放回地板以上；方法內唯一的 `setWorldTransform` 在「車的位置沒有格子」的夾回區塊（[2by](#2by)）。坐在車上時鏡頭高度取車的物理高度，原點在地板下就全黑（同一機制見 [2026-10-01 回報](report/2026-10-01-vehicle-orphaned-chunk-river-warp-tis.md) 第 4 點）。
- 原版只有 `BaseVehicle.load` 以 `PZMath.clamp` 把存檔高度夾在所在樓層的地板以上。重登時客戶端 `VehicleFullUpdatePacket.parse` 先 `load`（夾到地板），接著 `Bullet.setOwnVehiclePhysics` 又套上伺服器記的高度，所以每個客戶端都拿到同一個地下的高度。伺服器從資料庫重新載入這台車（重啟）才會被夾回（推論，未實測）。
- 翻覆的車（W64 第一版）：W64 碰到翻覆的車照原版不動、也不記錄，症狀二的車在斷線交回伺服器時沒有被處理。原版 `load` 只把高度夾回地板，照存檔旋轉放車、不扶正。玩家兩分鐘後重登，伺服器重新載入這台車（附近 150 格內沒有其他玩家，推論 cell 已卸載），車以四輪朝天、原點貼地的姿勢回到駕駛的客戶端，7 秒後斷線時伺服器又存回 −32（推論是駕駛的客戶端物理讓它再掉下去）。

**手術**：
- W64（server）：`BaseVehicle.setNetPlayerAuthorization(Authorization, int)` 頭部 headCall（slots 0、1）→ `zombie/mdc/VehicleSinkRestore.onSetAuthorization(BaseVehicle, Authorization)`。頭部時欄位還是舊的授權。掛在 W3-4 既有的 BaseVehicle ClassPatch 上（同一 class 只能有一個 ClassPatch），`expectedHits = 1`。
- W65（client）：`BaseVehicle.update()` 內唯一的 `invokevirtual updateTransform()V`（offset 1951，這時原版已從物理回寫 x、y 並把 z 設成樓層）1:1 改道 `invokestatic zombie/vehicles/MdcVehicleSinkGuard.updateTransform(BaseVehicle)V`；與 W61／W62 同一個 MethodOps，`expectedHits = 6`。helper 放在 `zombie.vehicles` 才能呼叫 protected 的 `updateTransform`。標準版與省記憶體版都含，模組版本仍是 `v3.4`。
- 兩刀都不改堆疊形狀、frames 原樣。

**判定**：
- 物理原點比所在樓層的地板低 0.25 m 以上、那一格有地板（`hasFloor`）。判斷只看地板：原版 `load` 也把高度夾在地板以上，原點在地板下時坐進去就全黑。vehicles.db 7,574 台裡，除了這台 M911，原點最低的是 3 台車頭著地的半掛拖車（−0.07 至 −0.11 m），照原版。
- 車要是正的（`getUpVectorDot()` 至少 `MINIMUM_DOT_UPRIGHT` 0.8）。例外：翻覆而且原點比地板低一層（2.449 m）以上的車，先扶正再抬回（W64 2026-10-16 起；W65 client 包 0.2.8 起）；翻覆而低於地板不到一層的照原版，留給玩家自己翻正（車頂著地或翻在溝裡的車，原點本來就在地板上或只低一點）。
- 扶正只改旋轉：保留車頭方向（前向量的水平分量決定繞 Y 軸的角度），俯仰與側傾歸零。伺服器改的 `jniTransform` 旋轉會進存檔（`save` 從 `getWorldTransform` 寫旋轉）與完整更新（`VehicleInterpolationData.set` 從 `getWorldTransform` 取旋轉；客戶端 `VehicleFullUpdatePacket.parse` 以 `setWorldTransform`、`Bullet.setOwnVehiclePhysics` 套用）。原版沒有任何地方會扶正車：`load` 照存檔旋轉放車，`flipUpright` 只在玩家翻車的動作裡用。
- 放回的高度＝樓層地板＋停放高度。停放高度照原版 `CarController` 建構子生成車輛的算法：−min(模型偏移＋第一個輪子偏移－半徑, 重心偏移－車身高÷2)，至少 0.1。M911 是 0.5666。
- W64：只在授權從別人交回 Server 時檢查一次（下車、換到乘客座、斷線、碰撞歸還；`authorizationServerOnSeat`、`authorizationServerCollide`、`GameServer.disconnectPlayer` 都走 `setNetPlayerAuthorization`）。抬回後速度歸零、`updateFlags |= Full`，下一輪 `sendVehicles` 對每個連線送 `VehicleFullUpdate`，客戶端照原版用伺服器的高度與旋轉放車。
- W65：只處理本機模擬的車（`isLocalPhysicSim()`：Local、LocalCollide）；用原版 `setWorldTransform`（原生 `teleportVehicle`）放車，水平位置不變、方向不變（翻覆深陷的先扶正）、速度歸零，再照原版 `updateTransform`。本機下一則 VehiclePhysics 就把地面上的高度與姿勢送給伺服器。在伺服器 JVM 裡不動（自建伺服器也會載入 client 包）。

**kill switch**：`-Dmdc.vehicleSinkRestore=off`（W64）、`-Dmdc.vehicleSinkGuard=off`（W65），或 `0`，一律照原版，需重啟。第一次用到時記一行 `active=true|false`；每次介入記一行（每次啟動 W64 最多 50 行、W65 最多 20 行）：

```text
[MinidoracatJavaPatch][VehicleSinkRestore] vehicle <id> <車型> at <x>,<y> handed back to the server (<原授權> pid=<n>): physics height <y> is <m> m below the floor of level <z>; <結果> (#<n>)
[MinidoracatJavaPatch][VehicleSinkGuard] vehicle <id> <車型> at <x>,<y> (<授權>): physics height <y> is below the floor of level <z>; [flipped (up <u>), set upright keeping its heading, ]lifted to <h> (#<n>)
```

W64 的 `<結果>`：`lifted to <h> and sent a full update`（抬回）、`flipped (up <u>), set upright keeping its heading, lifted to <h> and sent a full update`（扶正再抬回）、`flipped (up <u>), less than one level below the floor, left as is`（翻覆不到一層，照原版）、`no floor on that square, left as is`（沒有地板，照原版；翻覆的前面加 `flipped (up <u>), `）。

**不處理**：
- 原生物理為什麼在這次碰撞中把車推下去：在 libPZBullet 裡，Java 端看不到。
- 原點在地板下、那一格卻沒有地板的車，以及翻覆而低於地板不到一層的車：照原版（W64 各記一行）。
- 車為什麼掉進虛空（[2by](#2by) 那一族的瞬移）：客戶端修補包的 W61–W63 處理；掉下去之後，裝了 client 包 0.2.8 的駕駛由 W65 在下一幀放回地面（翻覆的先扶正）。沒裝修補包的玩家照樣會掉進去，W64 在下車或下線、車交回伺服器時把它擺回地面，玩家重登就恢復。
- 交回伺服器之後就沒有人再開、也沒人撞的地下車：W64 不會再檢查；部署本身要重啟，重啟時原版 `load` 會夾回。
- 同一位玩家 20:57 那次被踢回主選單是另一個原版缺陷（`IsoMetaCell.isoRooms` 跨執行緒競態，[2026-09-05 回報](report/2026-09-05-client-metagrid-isorooms-race-tis.md)，TIS #101035），與陷車無關。

**守門與驗證**：
- SmokeCheck（伺服器）：W64 存在理由——`VehiclePhysicsPacket.processServer` 恰一個 `setZ`、沒有 `PZMath.clamp`；`BaseVehicle.load` 有 `PZMath.clamp`；`CarController` 建構子讀 `getModelOffset`、`getWheel`×2、`getCenterOfMassOffset`、`getExtents`；三條交回路徑都呼叫 `setNetPlayerAuthorization`；`shouldSend` 讀 `vehicle.updateFlags`。扶正的前提——`load` 恰一個 `Transform.setRotation`、沒有 `flipUpright`；`save` 與 `VehicleInterpolationData.set` 各恰一個 `getWorldTransform` 與 `getRotation`；`VehicleFullUpdatePacket.write` 恰一個 `save`。手術後頭部 `aload_0; aload_1; invokestatic` 全序、真指令恰 +3；helper 只寫 `updateFlags` 一處、不碰授權欄位。
- SmokeCheck（client 兩個變體）：W65 存在理由——`update()` 恰一個 `updateTransform`，在最後一個 `setZ` 與夾回的 `setWorldTransform` 之後；`updateTransform` 是 protected。手術後 `update()` 除了 W61、W62、W65 的改道之外逐指令與原版相同。
- `VehicleSinkRestoreTest`（`build.ps1` [9zj/10]，on、off）：真 `setNetPlayerAuthorization`＋真 `ServerVehicleState.shouldSend`，查格子走真 `IsoCell.getGridSquare`。M911 下車（Local→Server）與碰撞歸還（LocalCollide→Server）：抬回 0.5666、速度歸零、下一輪送完整更新、送一次後不再重送；掉到 −80 m 也抬回 0 樓；停放高度算出來是負的拖車抬到 0.1；四輪朝天、車頭 30° 掉到 −32 m：扶正（up 0.9999 以上）、車頭仍朝 30°、抬回停放高度並送完整更新；正常停放 0.506、地板下 0.109 與 0.24 m、交給駕駛、本來就是 Server、沒有地板、翻覆而在地板下 0.6 m 與 2.4 m（不到一層）、車頂著地都不動，翻覆深陷但沒有地板也不動。off：照原版留在 −0.599、翻覆的 −32 m 也不扶正，都不送完整更新（原版負對照）。
- `MdcVehicleSinkGuardBehaviorTest`（`build-client.ps1`，兩個變體各 on、off）：Local、LocalCollide 的車拉回 0.5666；四輪朝天、車頭 1.1 rad 掉到 −32 m 的車拉回並扶正（up 0.9999 以上、車頭不變、水平位置不變、速度歸零）；Remote、Server、正常行駛、地板下 0.109 與 0.24 m、翻覆而在地板下 0.6 m 與 2.4 m、車頂著地、沒有地板（含翻覆深陷）、伺服器 JVM 都不動；正的車放車只改高度，水平位置與方向不變、速度歸零。off：照原版留在地板下，翻覆的 −32 m 也不扶正。
- 實機 E2E（W64，2026-10-15，本機 no-Steam 專用伺服器以 dist 為 classpath 最前面，客戶端是沒裝修補包的 Steam 本體；家族 E2E 情境 `vehicle-sink-mp`）：坐上 SUV、發動，在駕駛的客戶端把車轉成四輪朝天、車頭 30°、物理高度 −20，伺服器也記到 −20.011、up −1.000 後正常離線。修補版：離線那一刻伺服器記 `flipped (up -0.99999994), set upright keeping its heading, lifted to 0.382928 and sent a full update`；重開客戶端，角色照原版坐回駕駛座，12 秒內車在本機一直是 y 0.320、up 1.000、車頭 (0.500, 0.866)，伺服器同值，畫面正常。原版對照（`-Dmdc.vehicleSinkRestore=off`）：重登那一刻車已在 −16 m、四輪朝天，12 秒內在客戶端一路掉到 −636 m，畫面全黑只剩 HUD、車速表讀到 279 km/h，伺服器存成 −32.000——與症狀二的玩家截圖和正式服 vehicles.db 一致。
- 實機 E2E（W65，2026-10-16，同一情境的 `client=1`，客戶端從裝了 client 包 0.2.8 標準版的遊戲複本開）：同樣把車轉成四輪朝天、−20 之後，11 ms 內 client console 記 `flipped (up -0.99999994), set upright keeping its heading, lifted to 0.382928 (#1)`；之後 15 秒車在本機一直是 y 0.374–0.383、up 1.000、車頭 (0.500, 0.866)，伺服器同值，畫面正常（駕駛沒有離線）。沒裝包的原版對照就是上一條 W64 那輪的場次 1：車設在 −20 之後 5 秒都留在 −20、四輪朝天。碰撞把車推進地板下的那種情況仍無法穩定重現；上線後看伺服器 console 的 `VehicleSinkRestore` 行與玩家 console.txt 的 `VehicleSinkGuard` 行。

**退場**：官方在伺服器收高度時夾樓層，或在 `update()` 把掉到地板下的車放回地面時，SmokeCheck 的存在理由轉紅，屆時從 `PatchConfig` 移除對應的手術，並刪除 helper 與測試。

<a id="2cb"></a>
## 2cb. 畜牧區同步修補（W66，server，預設 on）

**症狀**（2026-10-17 正式服）：多位玩家同一天回報「畜牧區不見了」：有人的畜牧區在他離線時消失，有人看到重複的畜牧區、刪掉後重登又出現，也有相連的幾塊一起不見。伺服器沒有任何紀錄能查出是誰、在什麼時候刪的。

**調查**（唯讀；10-14 至 10-18 的 42 份備份與當下存檔，逐份解析 `map_meta.bin` 的畜牧區）：這段期間刪除 46 塊、新增 47 塊，總數一直在 659–666 塊，重啟前後沒有整批消失；多數是同一時段在同一位置刪掉又重畫。伺服器只有收到客戶端送來的 `SyncZone` 刪除封包才會刪畜牧區，JavaPatch 與伺服器 MOD 都不增刪畜牧區。

**原版機制**（42.21.0 反編譯＋javap）：
- **刪除沒有權限檢查，也不留紀錄**：`SyncZonePacket.processServer` 照 id 找到畜牧區就轉發給其他人，再 `DesignationZoneAnimal.removeZone`；`getAllDZones` 把所有相連的畜牧區一起刪（10-17 晚上 666 塊中有 192 塊屬於多塊相連的組）。原版畜牧區面板列出所有已載入的畜牧區，預設名稱都是「畜牧區 #N」，很容易刪到別人的。
- **快取封包的欄位殘留**：每條連線兩端各只有一個 `SyncZonePacket` 實例（`PacketsCache`），伺服器解析這位玩家送來的畜牧區新增、改名、刪除用它。動物吃草（`IsoGridSquare.removeGrass`）更新既有草地再生區的時間戳時，用 `INetworkPacket.sendToRelative(SyncZone, …)` 通知附近玩家，也是拿每條連線的這個實例：`setData(Zone)` 只設 x／y／z／type／時間戳，不清 `designationZone`，`write` 又先看 `designationZone`，所以送出的是這位玩家上次送來的畜牧區新增、改名或刪除。他建立或改名過的畜牧區被別人刪掉後，會在他畫面上一直被加回來（伺服器沒有、動物不認，重登才消失），他的客戶端也收不到草地區的時間戳。沒送過畜牧區的玩家收到真的草地 `SyncZone` 時，客戶端的實例同樣留著上一次收到的畜牧區欄位，`processClient` 會重播上一次收到的刪除，連帶刪掉之後在旁邊重畫的畜牧區（只在畫面上，重登恢復）。全 jar 只有 `removeGrass` 用 `SyncZone` 送非畜牧區資料。
- **開服載入時暫時 id 撞號**：`DesignationZone.load` 用建構子建立畜牧區，建構子先抽隨機暫時 id（`Rand.Next(9999999) + 100000`），撞到已載入的 id 就不加入 `allZones`；`DesignationZoneAnimal` 建構子照樣把它加進畜牧區清單，`load` 接著才設回真 id。這塊這一場還能圈住動物，但不存檔（`IsoMetaGrid.savePart` 走 `allZones`）、不送給之後連線的玩家（`MetaDataPacket`），下次重啟就消失。664 塊時每次開服約 2.2% 機率掉一塊；10-16 早上的重啟就掉了一塊，與它相連的其他塊都還在。

**手術**：五個呼叫各自 1:1 同形改道，堆疊形狀不變、frames 原樣；javap 語境見 `docs/specs/zombie_zone_sync.json`。
- 紀錄：`SyncZonePacket.processServer` 唯一的 `sendToClients`（offset 12，在 `removeDesignationZone` 之前）→ `zombie/network/packets/MdcSyncZoneLog.sendToClients(SyncZonePacket, PacketType, UdpConnection)`；`SyncZonePacket.parse` 唯一的 `DesignationZone.load`（offset 32）→ `MdcSyncZoneLog.load`。新的 ClassPatch，兩個方法 `expectedHits` 各 1。helper 放在 `zombie.network.packets` 才讀得到封包的 package-private 欄位。
- 草地通知：`IsoGridSquare.removeGrass()` 兩個 `INetworkPacket.sendToRelative`（offset 240 送 RegisterZone、290 送 SyncZone）→ `zombie/mdc/ZoneSyncGuard.sendToRelative`，`expectedHits = 2`，掛在 W60 的 IsoGridSquare ClassPatch（同一 class 只能有一個 ClassPatch）。原呼叫是介面靜態方法，改道後是一般類別的 `invokestatic`。
- 載入補回：`IsoMetaGrid.load(ByteBuffer)` 唯一的 `DesignationZone.load`（offset 706）→ `ZoneSyncGuard.load`，新的 ClassPatch，`expectedHits = 1`。封包路徑經 `MdcSyncZoneLog.load` 也走同一個補回。

**行為**：
- 紀錄：每次有人新增、改名、刪除畜牧區記一行，帶送封包的帳號與他當時的位置；刪除時列出接著會被連帶刪掉的每一塊。新增與改名靠載入前看封包裡的 id 是否已存在來分辨，改名記下原版覆寫前的舊名稱與寬高。轉發與刪除照原版。每分鐘最多 30 行，超過的只計數，下一行補報筆數。
- 草地通知：`SyncZone` 換成原版的 `RegisterZone`，收件人與內容不變。`FishSchoolManager` 經 `GameServer.sendZone` 已用 `RegisterZone` 更新區域時間戳；客戶端照型別找到同一塊草地區就更新名稱與時間戳，找不到就登記。草地照常再生，不會再帶出殘留的畜牧區；兩端都不再出現非畜牧區的 `SyncZone`。新建草地區本來就送 `RegisterZone`，照原樣。
- 載入補回：照原版載入後，結果不在 `allZones` 就補進去並記一行。已經漏掉的那塊在下次有人改名或重送時也會補回。

```text
[MinidoracatJavaPatch][ZoneSync] 新增 zone=<id> "<名稱>" <x>,<y>,<z> <寬>x<高> by=<帳號>@<x>,<y>,<z>
[MinidoracatJavaPatch][ZoneSync] 改名 zone=<id> "<舊名>"→"<新名>"[ <舊寬>x<舊高>→<新寬>x<新高>] <x>,<y>,<z> by=<帳號>@<x>,<y>,<z>
[MinidoracatJavaPatch][ZoneSync] 移除 zone=<id> "<名稱>" <x>,<y>,<z> <寬>x<高> 連帶 <n> 塊[ [zone=<id> "<名稱>" <x>,<y>,<z> <寬>x<高>; …]] by=<帳號>@<x>,<y>,<z>
[MinidoracatJavaPatch][ZoneSync] 載入時暫時 id 撞號，補回存檔清單：zone=<id> "<名稱>" <x>,<y>,<z> <寬>x<高>
[MinidoracatJavaPatch][ZoneSync] 首次生效：草地再生改送 RegisterZone（-Dmdc.zoneGrassSync=0|off 回原版）
```

超過每分鐘上限之後的第一行，行尾加 `（之前另有 <n> 筆超過每分鐘上限未印）`。

**kill switch**：`-Dmdc.zoneSyncLog`（紀錄）、`-Dmdc.zoneGrassSync`（草地通知）、`-Dmdc.zoneLoadRepair`（載入補回），設成 `0` 或 `off` 就照原版，需重啟。

**部署**：manifest 新增 `zombie/mdc/ZoneSyncGuard.class`、`zombie/network/packets/MdcSyncZoneLog.class` 與兩個新 patched class（`SyncZonePacket`、`IsoMetaGrid`），`IsoGridSquare.class` 換成多兩個命中點的版本（137 → 141 個 class）。manifest 只增不減，不必先卸載：用延後生效流程 stage＋arm，下一次排程重啟時切換。上線後確認橫幅指紋是新版、`[mdc-javagate] OK: 141`；第一次有動物在草地區吃草時出現首次生效行，有人新增、改名、刪除畜牧區時出現對應的 `[ZoneSync]` 行。

**判讀**：玩家回報畜牧區不見時，在 console 搜 `[ZoneSync] 移除`，找同一塊或它所在的相連組：`by=` 是誰刪的、他當時在哪裡。找不到刪除紀錄、重啟後才不見，再搜 `撞號`。

**不處理**：
- 誰可以刪畜牧區：原版任何玩家都能刪任何一塊，連同相連的一起刪。2026-10-18 起，壓到安全屋的由 W67 限制成員才能刪（[2cc](#2cc)）；不在安全屋的照原版。
- 已經出現在玩家畫面上的幽靈畜牧區：重登就消失；部署後不會再產生（草地通知這條路）。
- 客戶端連線時的撞號：客戶端收到畜牧區也走 `DesignationZone.load`（`MetaDataPacket.parse`，與收到別人新增的轉發），撞號的那塊在客戶端不在 `allZones`，但原版面板（`DesignationZoneAnimal.getAllZones()`）照樣列出。玩家替它改名時，`setName` 的 `sync()` 依 `allZones.contains` 判斷，送出的是刪除，伺服器會刪掉它和相連的整組（讀碼推論，未重現）。要修得改客戶端（client 包）；部署後紀錄會把這種情況記成這位玩家的「移除」。

**守門與驗證**：
- SmokeCheck：存在理由——`SyncZonePacket.parse` 兩個 `designationZone` 寫入都在 Zone 分支之前、`setData` 只在畜牧區分支寫一次 `designationZone`，`write` 第一個讀的欄位是 `designationZone`；`removeGrass` 恰 2 個 `sendToRelative`，依序送 RegisterZone、SyncZone；全 jar 讀 `PacketType.SyncZone` 的只有 `removeGrass`×1、`DesignationZone.sync`×2、`SyncZonePacket.processServer`×1；`DesignationZone` 建構子先 `Rand.Next` 再 `getZoneById`，不為 null 就跳過 `allZones`，`DesignationZoneAnimal` 建構子沒有分支；全 jar 恰 3 個 `DesignationZone.load`；`processServer` 的 `sendToClients` 在 `removeDesignationZone` 之前。手術後四個方法文字（含 frames）只差這五個呼叫；helper 內每個原方法恰呼叫一次，封包載入不繞過補回。
- `ZoneSyncTest`（`build.ps1` [9zk/10]，on、off 兩個 JVM，自驗三個開關）：走真的 `parse`／`processServer`、真的 `INetworkPacket.sendToRelative` 與真的 `DesignationZone.load`，收件端用真 `ByteBufferReader` 解回。草地：建立者新增畜牧區、伺服器刪掉後，原版草地通知送給建立者的仍是那塊畜牧區的新增（原版負對照）；on 兩人都收到同一塊草地區的 `RegisterZone`、沒有 `SyncZone`，off 逐位元同原版；新建草地區的 `RegisterZone` 照原版。客戶端重播（原版負對照）：收到刪除後再收到真的草地 `SyncZone`，旁邊重畫的畜牧區被刪掉。載入：腳本化亂數讓暫時 id 撞到已載入的 id，原版那塊在畜牧區清單、不在 `allZones`（負對照）；on 補進 `allZones` 恰一次、已漏掉的那塊下次重送時補回，沒撞號不重複加入，非畜牧區型別照原版回 null。紀錄：新增、改名（舊名→新名、寬度變化）、刪除各一行，刪除行列出的 id 恰好是實際被刪掉的整組，帶刪除者帳號與位置；不存在的 id 不轉發也不記；轉發筆數 on／off 相同；每分鐘 30 行上限與補報。off：整個 JVM 零紀錄行。
- 實機 E2E（2026-10-18，本機 no-Steam 專用伺服器以 dist 為 classpath 最前面，客戶端是 Steam 本體；家族 E2E 情境 `zone-sync-mp`）：客戶端用原版 API 建三塊畜牧區（兩塊相連）、改名一塊、刪掉相連組的一塊，再建第四塊；伺服器用 Lua 刪掉第四塊（像別的玩家刪的），客戶端看到它消失後，伺服器在玩家旁邊讓 8 格草被吃掉（7 格更新既有草地區＝原版送 `SyncZone` 的那條路）。修補版：伺服器 console 依序記 3 行新增、1 行改名（舊名→新名）、1 行移除（`連帶 1 塊`＝相連的那塊）、1 行新增與首次生效行；第四塊沒有在客戶端復活，客戶端草地區的時間戳與伺服器相同。原版對照（三個 kill switch 全關）：第四塊在客戶端被加回來（伺服器上沒有），客戶端草地區的時間戳一直是 0，console 沒有 `[ZoneSync]`。開服載入撞號無法在實機穩定重現，只由 `ZoneSyncTest` 驗；兩輪開服讀檔都正常。

**退場**：官方在 `setData(Zone)` 或 `parse` 清掉畜牧區欄位、草地改用別的封包、建構子不再以暫時 id 查重時，SmokeCheck 的存在理由轉紅，屆時移除對應的手術；紀錄可以留著。

<a id="2cc"></a>
## 2cc. 安全屋內的畜牧區只有成員能刪（W67，server，預設 on）

**起因**：[2cb](#2cb) 調查到的 10-17 兩件，被刪的畜牧區都在受害者自己的安全屋內，刪的人不是那間的成員。原版任何玩家都能刪任何一塊畜牧區，連同相連的一起刪（2cb「原版機制」）。2026-10-18 服主裁定：壓到安全屋的畜牧區，只准那間的屋主、成員與管理員刪。

**判定**：W66 改道的 `SyncZonePacket.processServer` 在轉發前（`MdcSyncZoneLog.sendToClients`）先問 `zombie/mdc/ZoneDeleteGuard.denial`：
- 只看畜牧區的刪除（`zoneAdded` 是 false）。
- 送封包的連線具 `Capability.CanSetupSafehouses` 就放行。這是管理員，原版安全屋反作弊與 Safehouse MOD 用同一個判斷。
- 其他人：用伺服器的 `getAllDZones` 算出接著會被刪的整組，只要其中一塊（任何樓層，地下室也算）和某間安全屋的範圍重疊，連線的帳號或連線上任一位玩家就必須是那間的屋主或成員（原版 `SafeHouse.playerAllowed(String)`：成員名單或屋主）。Safehouse MOD 管理的安全屋，屋主欄是 MOD 的標記，真正的屋主與所有分享對象都在原生成員名單裡。
- 整組有一塊不符就整組拒絕：原版刪一塊就是刪整組，不能只刪一部分。

**拒絕時**：不轉發給其他玩家，並把封包的 `zoneAdded` 設成 true。原版 `processServer` 在轉發之後才讀它（javap offset 16），讀到 true 就不呼叫 `removeDesignationZone`。刪除者的客戶端送出前已經先刪掉整組（原版面板呼叫 `DesignationZoneAnimal.removeZone(zone, true)`），所以 `ZoneDeleteGuard.reject` 把伺服器上的整組逐塊用新的 `SyncZonePacket` 當成新增送給他，再用伺服器聊天送一行中英提示：

```text
這塊畜牧區（或和它相連的畜牧區）在別人的安全屋裡，只有那間安全屋的成員能刪除。 / This livestock zone, or one connected to it, is inside someone else's safehouse. Only its members can remove it.
```

console 記一行（不受 `-Dmdc.zoneSyncLog` 影響，同樣每分鐘最多 30 行）：

```text
[MinidoracatJavaPatch][ZoneSync] 拒絕移除 zone=<id> "<名稱>" <x>,<y>,<z> <寬>x<高> 連帶 <n> 塊[ […]] 安全屋 <x>,<y> <寬>x<高> owner=<屋主欄> by=<帳號>@<x>,<y>,<z>
[MinidoracatJavaPatch][ZoneSync] 首次生效：安全屋內的畜牧區只有成員能刪（-Dmdc.zoneDeleteGuard=0|off 回原版）
```

允許的刪除照原版，並照 W66 記一行「移除」。判定時出例外就照原版刪除，並記一行原因。

**影響面**（10-18 07:30 存檔唯讀統計，安全屋取自 `map_meta.bin` 的原生清單）：663 塊畜牧區分成 546 組；110 塊壓到 372 間安全屋，含這些塊的 86 組會受保護，其中 70 組全在同一間內。6 組另含安全屋外的 11 塊：外面那塊若屬非成員，還連著時他刪不掉自己的那塊，要請屋主或管理員處理。11 組跨兩間以上安全屋，每組都有共同成員，沒有一組只剩管理員刪得掉。

**手術**：沒有新的改道，掛在 W66 改道的同一個呼叫上（同一個呼叫只能改道一次）；新增 helper `zombie/mdc/ZoneDeleteGuard`。

**kill switch**：`-Dmdc.zoneDeleteGuard=0|off`，需重啟；與 W66 的三個開關各自獨立。

**部署**：manifest 新增 `zombie/mdc/ZoneDeleteGuard.class`，`MdcSyncZoneLog.class` 與 `PatchInfo.class` 換新（141 → 142 個 class）。上線後確認橫幅指紋、`[mdc-javagate] OK: 142`；第一次有人刪畜牧區時出現首次生效行。

**不處理**：
- 改名與新增：照原版，誰都能改別人畜牧區的名字，也能在別人的安全屋裡畫畜牧區。原版面板不能改大小；改過的客戶端可以用改名封包改寬高，未處理。
- 不在安全屋的畜牧區：照原版，靠 W66 的紀錄查是誰刪的。
- 客戶端撞號讓改名變成刪除（2cb「不處理」）：在別人的安全屋內會被這刀擋下並補回；自己的照原版刪。

**守門與驗證**：
- SmokeCheck：存在理由——`processServer` 恰一個 `zoneAdded` 讀取，在 `sendToClients` 之後、`removeDesignationZone` 之前，緊接 `ifne`；`SafeHouse.playerAllowed(String)` 只看成員名單與屋主；`setData` 以 `values[1]` 設 `zoneAdded`、`write` 用 `DesignationZone.save` 送整筆；`ChatServer.sendMessageToServerChat(連線, 文字)` 存在。helper 契約——轉發前恰一次 `denial`，拒絕時恰一次寫 `zoneAdded` 與一次 `reject`；`reject` 逐塊 `setData`＋`sendToClient`、一次聊天提示；成員判斷只走 `playerAllowed`，管理員看 `CanSetupSafehouses`。
- `ZoneSyncTest`（`build.ps1` [9zk/10]，on、guardoff（只關 W67）、off 三個 JVM）：三間安全屋、六塊畜牧區、四條連線（屋主、外人、成員、具 `CanSetupSafehouses` 的管理員），走真的 `parse`／`processServer`。on：外人刪屋內那塊、刪相連組在屋外的那塊、刪屋子底下地下室那塊，以及屋主刪跨到別人安全屋的那組，全部拒絕——伺服器整組都在、其他三人收不到轉發、刪除者收到整組逐塊的新增（解回的 id 恰好是整組），記一行「拒絕移除」帶安全屋與刪除者；外人刪不在安全屋的那塊、成員刪屋內那塊、管理員刪跨兩間的那組、屋主刪地下室那塊，照原版刪整組並轉發給其他三人。guardoff、off：八個案例全部照原版刪整組（原版負對照）。
- 實機 E2E（2026-10-18，本機 no-Steam 專用伺服器以 dist 為 classpath 最前面，客戶端是 Steam 本體；家族 E2E 情境 `zone-guard-mp`，test 用一般帳號）：伺服器在玩家旁邊開一間屬於別人的安全屋，客戶端用原版 API 畫三塊畜牧區（屋內一塊、和它相連而跨出屋外一塊、屋外一塊），照原版面板的做法刪屋內那塊。修補版：伺服器記 `拒絕移除 … 連帶 1 塊 … 安全屋 … by=test`，兩塊都還在伺服器上；客戶端送出前已先刪掉兩塊，4 秒後兩塊都被補回，聊天框收到中英提示。刪屋外那塊照原版刪掉；伺服器把 test 加成成員後再刪屋內那塊，照原版連同相連的一起刪掉。原版對照（`-Dmdc.zoneDeleteGuard=off`）：外人刪屋內那塊，伺服器連同相連的一起刪掉，客戶端也沒有了，聊天框只有原版的歡迎訊息。

**退場**：官方加上畜牧區的擁有者或安全屋檢查時重估；`processServer` 的讀取順序變了（SmokeCheck 轉紅）就要改寫。

<a id="2cd"></a>
## 2cd. 房間表被兩條執行緒同時寫（W68，client，預設 on；client 包 0.2.9）

**症狀**（2026-10-19 玩家回報，裝著 client 包 0.2.8；同一個缺陷在 42.20.4 已回報 TIS [101035](https://theindiestone.com/forums/topic/101035/)，至今沒有回覆）：附近有人蓋或拆東西、自己這邊正在載入地圖時，被斷線送回主選單。玩家的 client log（42.21.0 `4a0e9546ec`）只有一條例外：`NullPointerException: Cannot read field "def" because "isoRoom" is null at WorldRegionToMetaGrid.recalculateBuildingAndRoomIDs(WorldRegionToMetaGrid.java:468)`，往上是 `removeFromMetaGrid` ← `removeUserDefinedBuildingsFromCell` ← `WorldRegionToMetaGrid.clientProcessBuildings` ← `DataRoot.clientProcessBuildings` ← `IsoRegions.update` ← `IngameState.updateInternal`。42.20.4 的回報是同一個方法的 `ArrayIndexOutOfBoundsException: Index 924 out of bounds for length 924`（經 `addToMetaGrid`）。那位玩家的 log 有 `[StaleRoomGuard] active=true`，W57 這次沒有介入，修補包的 class 也不在堆疊上。

**根因**（javap 對 42.21.0 jar `e1a69eb7`；LineNumberTable：line 468＝`recalculateBuildingAndRoomIDs` offset 257–263，正把 `new ArrayList<>(isoRooms.values())` 複製出來的 IsoRoom 逐一以 `isoRoom.def.id` 放回）：
- `IsoMetaCell.isoRooms`、`isoBuildings` 是一般 `HashMap`。World Streamer 執行緒反序列化 chunk 時，`IsoGridSquare.setRoomID` 經 `IsoChunk.getRoom` 呼叫 `IsoMetaGrid.getRoomByID`，查不到就建新的 IsoRoom、IsoBuilding 並 `put` 進兩張表。整條路徑沒有鎖。
- 附近有人蓋、拆或打壞牆、門、地板時，主執行緒的 `clientProcessBuildings` 拆掉重建同一個與相鄰 cell 的自建建築：`removeRoomDef` 從 `isoRooms` 移除房間，每拆或建一棟就呼叫一次 `recalculateBuildingAndRoomIDs`，把兩張表的 values 複製到 `ArrayList`、清空、再逐一放回。這邊也沒有鎖，也不等 streamer。
- 兩條執行緒同時寫同一個 HashMap，記錄的 size 和走得到的節點數就對不上：多一筆時複製越界（AIOOBE），少一筆時複製出來的陣列尾端是 null，放回時讀 `isoRoom.def` 就 NPE（這位玩家）。jar 裡沒有地方把 null 放進這兩張表，null 只可能來自並行修改。例外由 `IngameState.updateInternal` 接住，玩家被斷線送回主選單。
- 時好時壞：要剛好在 streamer 補房間時重建。附近一直有人施工、自己又在移動（chunk 不斷載入）時最容易遇到。與 W57（[2bu](#2bu)）是同一個上游缺陷：主執行緒重建 metagrid 時不和 World Streamer 協調。W57 是交接中的 chunk 拿到被清空的房間，這裡是兩條執行緒同時寫同一張表。

**手術**：helper `zombie/mdc/MetaGridLock` 持有一把公平的 `ReentrantLock`。jar 內每一個 `IsoMetaGrid.getRoomByID(J)` 呼叫點，以及 `DataRoot.clientProcessBuildings` 內唯一的 `WorldRegionToMetaGrid.clientProcessBuildings(ArrayList)`，都 1:1 改道到它（receiver 變第一參數，3 bytes 換 3 bytes、frames 原樣），各自 `expectedHits = 1`：

| 類別 | 方法 | 改道的呼叫 | 誰會走到 |
|---|---|---|---|
| `IsoChunk`（與 W57 同一個 ClassPatch） | `getRoom(J)` | `getRoomByID` | `setRoomID`：streamer 反序列化 chunk、重建的 `updateSquares`、W57 補綁 |
| `RoomDef` | `getIsoRoom()` | `getRoomByID` | 主執行緒 `doLoadGridsquare` 等 |
| `IsoBuilding` | `CreateFrom(BuildingDef, IsoMetaCell)` | `getRoomByID` | 只在 `getRoomByID` 裡（重入） |
| `FBORenderCutaways` | `CalculateBuildingsToCollapse()` | `getRoomByID` | 主執行緒準備畫面 |
| `MetaGridPacket` | `processClient(UdpConnection)` | `getRoomByID` | 收到房間開關燈封包 |
| `DataRoot` | `clientProcessBuildings()` | `WorldRegionToMetaGrid.clientProcessBuildings` | 主執行緒重建 |

helper 照原版呼叫原方法一次，回傳值與例外不變。重建期間別的執行緒要查或補房間，等重建做完；有執行緒在查或補房間時，重建等它做完。公平鎖：主執行緒排隊後，streamer 下一次查房間排在它後面，所以主執行緒最多等一次查房間。

**為什麼不會死結**（三點都由 SmokeCheck 鎖進建置期）：
- 拿著鎖的 `getRoomByID` 不等任何東西：它在 jar 內的遞移呼叫（33 個方法，含 `IsoRoom`、`IsoBuilding`、`BuildingDef` 的建構子與 `CreateFrom`）沒有 synchronized、monitorenter、native，也不呼叫 JDK 的等待 API。唯一碰到的 JDK 鎖是 `IsoBuilding.rooms`（`Vector`）自己的方法鎖，它不回呼。
- streamer 走到 `IsoChunk.getRoom` 的每一層都不拿著鎖往下呼叫（沒有 synchronized、monitorenter，也不直接取得 j.u.c 鎖、號誌或 FileChannel 鎖）：`WorldStreamer` 的 `threadLoop`、`loadReceivedChunks`、`DoChunk`、`DoChunkAlways`，`IsoChunk` 的 `LoadChunk`、`LoadOrCreate`、`LoadFromDisk`、`LoadFromBuffer`、`LoadFromDiskOrBuffer`、`LoadFromDiskOrBufferInternal`，新 chunk 的 `CellLoader` 與 `IsoCell.PlaceLot`，以及 `IsoGridSquare.setRoomID`。chunk 檔的讀寫鎖只在 `SafeRead`、`SafeWrite` 裡，反序列化之前就放掉了。
- 重建拿著鎖時可以進別的 monitor（`ObjectPool`、log 等；等鎖的 streamer 手上沒有它們），但不能等 streamer：`clientProcessBuildings` 的遞移呼叫（只走宣告的實作，3972 個方法）完全不碰 `WorldStreamer`。這個閉包裡會讓執行緒等待的 JDK 呼叫只有 8 處，都已逐一看過：Lua 除錯器巢狀畫面迴圈裡的 render 同步（`SpriteRenderer` 兩處、`RenderContextQueueItem`、`TextureDraw` 的兩個 `Future.get`、`UIManager$Sync` 的 `sleep`）、`IsoSpriteInstance` 物件池的自旋，以及只在伺服器送毒氣封包時才走到的 `UdpConnection.startPacket` 鎖；客戶端重建時都不會等到 streamer。主執行緒整個 frame 都拿著 `MainThread.m_contextLock`，但拿著本鎖的 `getRoomByID` 不會去要它。

所以等鎖的執行緒手上沒有重建需要的鎖，拿著鎖的執行緒也不會反過來等它們。

**kill switch**：`-Dmdc.metaGridLock=off`（或 `0`）一律不上鎖，與原版相同，需重啟。第一次使用時在 console.txt 記一行 `[MinidoracatJavaPatch][MetaGridLock] active=true|false`（設了旗標時附原值）。重建結束時有執行緒在等，就記一行 `clientProcessBuildings (<n> changed cells) held the lock <ms> ms; <k> thread(s) waited instead of touching the room maps mid-rebuild (#<n>)`，每次啟動最多 20 行；原版在這種時候就是兩邊同時碰房間表。

**不處理**：
- MOD 的 Lua 直接呼叫 `getMetaGrid():getRoomByID(...)`（原版 Lua 沒有）：走反射，改道不到。
- 同一類的其他未同步存取：streamer 反序列化時讀的 `IsoMetaChunk` 房間陣列（`getRoomAt`、`getEmptyOutsideAt`），以及 `IsoCell.roomList`（`getRoomByID` 在鎖內加入，主執行緒其他地方讀時不上鎖）。沒有回報，不在這次範圍。
- 伺服器：`clientProcessBuildings` 只在客戶端與單機跑。自建伺服器從同一個安裝目錄載入 client 包時，伺服器的 `getRoomByID` 也經過這把鎖，只是多了互斥，結果不變。

**守門與驗證**：
- SmokeCheck（兩個變體）：存在理由——`isoRooms`、`isoBuildings` 是 `HashMap`，`getRoomByID` 沒有鎖並 `put` 兩張表，`recalculateBuildingAndRoomIDs` 複製兩張表的 values 後清空重填，`setRoomID` 經 `IsoChunk.getRoom` 呼叫 `getRoomByID`（TIS 換成並行安全的表或自己加鎖就轉紅，屆時重估撤刀）。呼叫點清冊——全 jar 的 `getRoomByID` 恰 5 個呼叫點、`clientProcessBuildings` 恰 1 個，新增或搬走都轉紅。手術後這 6 個方法除了被改道的呼叫，文字（含 frames）與原版相同。不死結的三個依據（上段）都在建置期重算：`getRoomByID` 閉包（虛擬呼叫含 jar 內所有覆寫）零違規、streamer 各層零持鎖、重建閉包不碰 `WorldStreamer` 且等待呼叫與已審的 8 處逐字相同（多一處、少一處都轉紅，要重新稽核）。helper 開、關兩條路各呼叫原方法一次，鎖是公平的。LoadCheck：兩個 helper 方法是 public static、簽名一致。
- `MetaGridLockBehaviorTest`（兩個變體各跑 on、off）：真的 `IsoMetaGrid` 一個 cell、600 間自建房，走改道後的 `IsoChunk.getRoom`、`IsoBuilding.CreateFrom`、`DataRoot.clientProcessBuildings`。`DataRoot.changedCells` 換成 `isEmpty()` 會先跑測試動作的清單，動作就在原版 `clientProcessBuildings` 的第一行執行，也就是拿著鎖的那一段。判定不靠計時，看執行緒是排進鎖的佇列（`hasQueuedThread`）還是直接做完，5 秒只是防卡死。on：重建進行中，另一條執行緒的 `getRoom` 排進佇列，重建做完才查到同一間房，並記一次等待；另一條執行緒停在 `getRoomByID` 裡建房間時，重建排進佇列等它做完，`CreateFrom` 的重入照常；真的 `recalculateBuildingAndRoomIDs` 重建 5000 次，期間 streamer 連續查房間（5,600 次以上，兩邊交錯），兩邊零例外，兩張表走得到的筆數等於記錄的大小。off（原版負對照）：重建進行中，另一條執行緒直接查完；另一條執行緒在建房間時，重建直接做完。
- 拋棄式重現（原版 jar，兩邊都走改道後的呼叫點；900 間房，每次「進服」200 ms，主執行緒出第一個例外就結束）：`-Dmdc.metaGridLock=off` 300 次全部出事——AIOOBE 272 次、沒有訊息的 NPE 16 次、`"isoBuilding" is null` 9 次、`"isoRoom" is null` 3 次（與玩家 log 逐字相同）。上鎖時 300 次、共 2,150,991 次重建，零例外。
- 實機 E2E（2026-10-19，本機 SP `-debug`，客戶端從裝了 0.2.9 標準版開發建置的遊戲複本開；`stale-room-sp` 情境，即 W57 的實機重現：8 間自建房，12 輪「傳送到約 4000 格外 6 秒 → 傳回 → 8 秒內每個 tick 在起點旁拆／放一面牆」，chunk 一邊載入、主執行緒一邊重建）：`RESULT PASS`，每輪 8 間房都認得，零例外，40,857 幀 p50 5 ms、p95 9 ms、最大 199 ms。console 有 `[MetaGridLock] active=true` 與 11 行等待（重建拿著鎖 0.4–4.7 ms，期間 1 條執行緒等著查房間，原版這 11 次都是兩邊同時碰房間表），W57 照常補綁（記滿 20 行）。原版的競態要剛好撞在 HashMap 寫入的同一刻，這個情境 12 輪重現不出來（2026-10-09 的 0.2.6 輪次也沒有），原版負對照以上一條的拋棄式重現為準。

**退場**：官方修好後（SmokeCheck 的存在理由轉紅）從 `PatchConfig.client()` 移除本刀，並刪除 helper 與測試。

<a id="2ce"></a>
## 2ce. 實體 ID 撞號時照常移除（W69，server，預設 on）

**症狀**（2026-10-19 玩家回報）：撿起大型晾曬架後，原地留下看得到、選得到，但撿起、拆解、大錘都沒反應的殘件，那裡也放不了別的東西，重登後還在。伺服器 console 在撿起那一刻有：

```text
ERROR: Entity ... GameEntityManager.checkEntityIDChange> idToEntityMap(<id>)=WoodFloorLvl3:floors_interior_tilesandwood_01_45:..., expected null for null:crafted_05_84:...
java.lang.RuntimeException: Stored entity mismatch
    GameEntityManager.UnregisterEntity ← GameEntity.removeFromWorld ← IsoObject.removeFromWorld ← IsoThumpable.removeFromWorld
    ← RemoveItemFromSquarePacket.removeItemFromMap ← GameServer.RemoveItemFromMap ← IsoGridSquare.transmitRemoveItemFromSquare ← Lua pickUpMoveableInternal
```

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- `IsoObject.getEntityNetID` 以 x、y、z 與物件在格子清單的序號組成 ID（`x + y<<16 + z<<32 + 序號<<40`），但 `isFloor()` 為真時序號一律算 0。玩家後蓋的地板（`WoodFloorLvl3` 等建造實體）在格子裡已有牆或家具時會加在清單後面，這時排第 0 個的實體算出來的 ID 與地板相同。
- 先登記的那個佔住 `GameEntityManager.idToEntityMap` 的這一格：`RegisterEntity` 遇到已有就不登記；物件換序號時 `checkEntityIDChange` 拿掉舊 ID，新 ID 被佔就只記一行 `expected null for`、不放進去，`addedToEntityManager` 仍是 true。
- 伺服器移除它時，`UnregisterEntity(GameEntity, boolean)` 先 `remove(ID)`（拿走的是地板），發現不是自己就丟 `Stored entity mismatch`；ID 表沒有它就直接 return。兩條路都沒有把它移出 engine，`addedToEntityManager` 也不清。
- 呼叫端 `RemoveItemFromSquarePacket.removeItemFromMap` 在 `removeFromWorld` 之後才 `removeFromSquare`，而 `GameServer.RemoveItemFromMap` 在這之前就把移除封包送給附近客戶端：客戶端刪了，伺服器留著，玩家重登、重新載入那一格時物件又出現。多格家具的整組迴圈（`transmitRemoveItemFromSquare`）在某一格丟例外就中斷，那一格和排在它後面的格子都留在伺服器上（這次 2×2 的晾曬架留下 3 格，客戶端只刪了前 2 格）。整組缺了一格之後，撿起、拆解、大錘都要求整組都在（`IsoObjectUtils.getSpriteGridMultiTileObjects`），永遠拆不掉，也擋住那幾格的放置。
- chunk 卸載時（`ServerMap$ServerCell.Unload` → `IsoChunk.removeFromWorld`）同一個例外中斷整個 cell 的卸載，由 `ServerMap.postupdate` 接住，下一個 tick 重試；地板在 ID 表的登記已被拿掉，之後它移除時同樣留在 engine（推論）。
- 正式服 10-11～10-19 的 console：`Stored entity mismatch` 248 次，19 次在移除時（撿起、拆解、大錘、建造升級時移除前一階段），其餘在 chunk 卸載。撞號的多是 `WoodFloorLvl3`、`GravelFloor` 與牆、牆框、圍欄。

**手術**：`GameEntity.removeFromWorld(Z)` 唯一的 `GameEntityManager.UnregisterEntity(GameEntity, boolean)`（javap offset 13）改道 `zombie/entity/MdcEntityIdGuard.unregister`，掛在 W35 的 `GameEntity` ClassPatch（同一 class 只能有一個 ClassPatch）。helper 放在同一個 package，讀得到 package-private 的 `UnregisterEntity` 與 `addedToEntityManager`；ID 表是 private，開機時以反射取一次，取不到就記 anomaly、一律直通原版。只在伺服器、實體已登記時介入：
- ID 表那一格就是自己：直接呼叫原版。
- 存的是別的實體（撞號）或空的（未登記）：先把自己放進那一格，呼叫原版照常移除（離開 engine，需要時轉存 MetaEntity），再把原本的實體放回。那一格仍空著才放回；轉存的 MetaEntity 沿用同一個 ID，它佔著時以它為準，記 `holderBack=0`。
- ID 算法不動：客戶端用同一套算法算 ID，只改伺服器兩邊會對不上。原版客戶端那條路本來就不丟例外。

console（每分鐘最多 20 行，超過的只計數，下一分鐘第一次記錄前補一行筆數；心跳每 30 分鐘一行）：

```text
[MinidoracatJavaPatch][EntityIdGuard] 撞號仍照常移除 id=<id> at=<x>,<y>,<z>#<序號> entity=<名稱:sprite> holder=<名稱:sprite> holderBack=1 offload=0
[MinidoracatJavaPatch][EntityIdGuard] ID 未登記仍照常移除 id=<id> at=<x>,<y>,<z>#<序號> entity=<名稱:sprite> offload=1
[MinidoracatJavaPatch][EntityIdGuard] 首次生效（-Dmdc.entityIdGuard=0|off 回原版）calls=… collisions=… missing=… notRestored=… anomalies=…
```

**kill switch**：`-Dmdc.entityIdGuard=0|off`，需重啟。

**部署**：manifest 新增 `zombie/entity/MdcEntityIdGuard.class`，`GameEntity.class` 與 `PatchInfo.class` 換新（142 → 143 個 class）。上線後確認橫幅指紋、`[mdc-javagate] OK: 143`，第一次有物件移除時出現首次生效行。

**不處理**：
- ID 撞號本身：兩個實體仍共用一個網路 ID，以這個 ID 定址的實體封包只找得到 ID 表裡的那個。要根治得改 ID 算法，客戶端也要一起改。
- 已經留在伺服器上的殘件：本刀只讓之後的移除照常完成。缺了幾格的多格家具，原版玩法拆不掉，要由管理員用除錯選單 Brush Tool → Destroy tile 逐格刪（`SledgehammerDestroyPacket` 按序號直接刪，不檢查整組），或在伺服器端清除。
- `GameEntity.reset()` 與 `onUnEquip()` 呼叫的單參數 `UnregisterEntity`：正式服的例外都不在這兩條路上，不改。

**守門與驗證**：
- SmokeCheck：存在理由——`UnregisterEntity(GameEntity,Z)` 恰一處 `new RuntimeException("Stored entity mismatch")` 並 `athrow`，兩條分支各一次 `LongMap.remove`；`IsoObject.getEntityNetID` 以 `isFloor` 決定序號並經 `checkEntityIDChange` 換號（TIS 修掉任一條就轉紅，重估撤刀）。呼叫點清冊——全 jar 對 `UnregisterEntity(GameEntity,Z)` 只有 `GameEntity.removeFromWorld(Z)` 與單參數多載各一。手術後 `removeFromWorld(Z)` 除了被改道的呼叫，文字（含 frames）與原版相同。helper 呼叫原版 `UnregisterEntity(GameEntity,Z)` 三處（兩條直通、一條修補），不呼叫單參數多載、不自己丟例外。
- `MdcEntityIdGuardTest`（`build.ps1` [9zl/10]，on、off 兩個 JVM）：走 dist 內已 patch 的 `removeFromWorld(Z)` 與真的 `UnregisterEntity`、`Engine`、`getEntityNetID`。撞號（殘件排第 0 個、後蓋的地板排第 1 個，ID 表存的是地板）：on 不丟例外，殘件離開 engine 並取消登記，ID 表仍是地板，之後移除地板照常；off（原版負對照）丟 `Stored entity mismatch`，殘件仍在 engine，地板的登記被拿掉，再移除一次殘件也留在 engine。ID 未登記：on 離開 engine，原版留在 engine。正常移除兩邊相同。

**退場**：官方修好 ID 算法或 `UnregisterEntity` 的處理（SmokeCheck 存在理由轉紅）時重估。

<a id="2cf"></a>
## 2cf. Bandit Raid 劇情無限迴圈（W70，server，預設 on）

**症狀**（2026-10-24 正式服）：一次加了三張地圖 MOD 後約 55 分鐘，主迴圈停在同一幀約 10 分鐘（`MainLoopWatchdog` 12 份快照），heap 從 26 GB 漲到 40 GB 上限，全服卡黑邊、新連線進不來，最後由健康檢查重開。12 份快照都停在同一條路徑：

```text
RandomizedBuildingBase.addZombies／RandomizedWorldBase.createRandomDeadBody
  ← RDSBanditRaid.randomizeDeadSurvivor(RDSBanditRaid.java:143／149)
  ← RBBasic.addRandomDeadSurvivorStory ← RBBasic.randomizeBuilding ← RandomizedBuildingBase.ChunkLoaded
  ← IsoChunk.randomizeBuildingsEtc ← IsoChunk.doLoadGridsquare ← ServerMap$ServerCell.RecalcAll2 ← ServerCell.Load2
  ← ServerMap.preupdate ← GameServer.main
```

關機存檔把迴圈生出來的殭屍和屍體存了下來：那棟樓的 8 個 chunk 檔各 11–16 MB（平常 9–40 KB），所在 cell 的 `zpop` 檔從 113 KB 變成 1.5 MB。重開後只要有人靠近，伺服器就掉到 1.3 FPS（物件清單平均 4.5 萬，平常 3–5 千）。這些要停服另外清理，本刀只防止再發生。

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- `randomizeDeadSurvivor` 在房間數大於 5 的建築先算 `size = min((int) Math.floor(房間數 / 10), 10)`（offset 677–695），再跑 `size` 輪，每輪 `addZombies` 2–3 隻、`createRandomDeadBody` 2–3 具。
- 外層迴圈的計數器是 local slot 9（offset 701 `istore 9`、703 `iload 9; iload_2; if_icmpge`、793 `iinc 9, 1`），內層放屍體的迴圈也用 slot 9（756 `istore 9`、787 `iinc 9, 1`）。內層跑完時計數器等於屍體數 2 或 3，外層加 1 後回到 3 或 4。
- 所以 `size` ≥ 5 時外層永遠跑不完；`size` = 4 時每輪有一半機會再跑一輪，但會結束。`isValid` 不選超過 100 間房的建築，受影響的是 50–100 間房的建築。`getRandomRoomNoKids` 回 null 的那一輪不歸零計數器，但大樓幾乎都找得到房間。
- 觸發條件：建築沒被探索過、世界存活滿 30 天（`setMinimumDays(30)`）、從死亡倖存者劇情抽中 Bandit Raid（權重 1）。正式服原版地圖的建築大多早已探索過，新加的地圖區域整片都是新的，才會碰到。

**手術**：`RDSBanditRaid.randomizeDeadSurvivor` 唯一的 `Math.floor(D)D`（offset 682，算 `size` 那一行）1:1 改道 `zombie/mdc/BanditRaidLoopGuard.floor`：結果大於 4 時回 4，其餘照 `Math.floor`。原版 40–49 間房（`size` = 4）的建築迴圈本來就會結束，所以 50–100 間房的建築改照 40–49 間房的方式跑；49 間房以下行為完全不變。內層計數器不改：要換 local slot 或加指令，不是既有的同形手術。

console（每攔下一次一行，前 50 次之後每 1000 次一行）：

```text
[MinidoracatJavaPatch][BanditRaidLoopGuard] 攔下 Bandit Raid 無限迴圈：輪數 <n> → 4（房間數約 <n×10> 以上）clamped=<累計>（-Dmdc.banditRaidLoopGuard=0|off 回原版）
```

**kill switch**：`-Dmdc.banditRaidLoopGuard=0|off`，需重啟。

**部署**：manifest 新增 `zombie/mdc/BanditRaidLoopGuard.class` 與 `RDSBanditRaid.class`，`PatchInfo.class` 換新（143 → 145 個 class）。上線後確認橫幅指紋與 `[mdc-javagate] OK: 145`；攔下行只在抽中這個劇情的大樓載入時出現。

**不處理**：
- 已經生出來的殭屍和屍體：本刀只防止再發生。2026-10-24 那棟樓在停服時重置 chunk，並把 `zpop` 換回事發前的備份。
- `size` = 4 的建築每輪一半機會多跑一輪：原版行為，會結束，不改。

**守門與驗證**：
- SmokeCheck：存在理由——方法內恰一個 `Math.floor`，之後 `d2i; istore` 存成 size，外層迴圈 `iload i; iload size; if_icmpge` 的迴圈體內有 `iconst_0; istore i`（內層重用同一個計數器；TIS 修掉就轉紅，重估撤刀）。手術後除了被改道的呼叫，方法文字（含 frames）與原版相同，`RDSBanditRaid` 其餘方法不變；helper 內恰一次 `Math.floor`。
- `BanditRaidLoopGuardTest`（`build.ps1` [9zm/10]，on、off 兩個 JVM）：同一個 JVM 載入 jar 裡的原版與 dist 內手術後的 `RDSBanditRaid`，只把碰世界與亂數的呼叫（`Rand` 15 處、取房間 6、`addZombies` 3、屍體 3＋3、車 1、世界 3）換成替身（世界格子一律 null、亂數照種子），迴圈、計數器與分支照原 bytecode 跑，以替身被呼叫的順序當紀錄。1–49 間房 × 40 個種子（取房間有 1/4 機會落空）兩份逐筆相同；50–100 間房、永遠找得到房間時，原版 33 組全部超過 5000 次替身呼叫（負對照），on 的 1020 組全部跑完、每組都和同種子的 45 間房紀錄相同、各 clamped 一次；off 跟原版一樣停不下來。

**退場**：官方修好內層迴圈的計數器（SmokeCheck 存在理由轉紅）時撤刀。

<a id="2cg"></a>
## 2cg. 雞舍內動物產蛋不搜尋畜牧區雞舍（W72，server，預設 on）

**症狀**（2026-10-25 正式服，45–48 人、FPS 9.2–9.6、主執行緒 CPU 94%）：60 份主執行緒 dump 有 3 份停在 `DesignationZoneAnimal.getZone`，其中 2 份是同一條路徑：

```text
DesignationZoneAnimal.getZone ← DesignationZoneAnimal.getAllDZones ← DesignationZoneAnimal.getHutchsConnected
  ← AnimalData.getRegionHutch ← AnimalData.checkEggs ← IsoHutch.updateAnimalInside ← IsoHutch.update
  ← HutchManager.updateAll ← IsoWorld.updateInternal
```

正式服有 666 塊畜牧區；`getZone` 每次線性掃整份清單，`getAllDZones` 對整組相連畜牧區沿四邊外一格逐格呼叫它。以正式服畜牧區幾何在本機用真 jar 量，一次 `getHutchsConnected` 平均 33.6 µs（雞舍大小的單塊畜牧區 15.1 µs）。

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- `IsoHutch.update` 每個 tick 對雞舍內每隻動物呼叫 `AnimalData.checkEggs(cal, false)`。蛋時間到了的動物會在 offset 254、261 各算一次 `getRegionHutch()`，每次又呼叫 3 次 `getHutchsConnected()`（offset 32 `isEmpty`、45 `size`、55 `get`），也就是每隻每 tick 6 次整組畜牧區相連搜尋。
- 雞舍內的動物 `parent.hutch != null`，所以 offset 257 `ifnull`、267 `ifne`（`isDoorClosed`）、277 `ifnonnull`（讀 `IsoAnimal.hutch`）三條路都跳到同一個 offset 351（`else if (parent.addEgg(false))`）：搜尋結果對雞舍內的動物不影響任何分支。
- `addEgg(false)` 對雞舍內的動物是 `hutch.addAnimalInNestBox`：產蛋箱都有母雞或都已有 10 顆蛋時回 false，`eggTime` 不歸零，於是下一個 tick 重來。雞舍滿、蛋沒人撿時，每隻這樣的母雞每 tick 白算 6 次搜尋（約 90–200 µs）。
- 搜尋一路（`getRegionHutch` → `getHutchsConnected` → `getAllDZones` → `getZone`、`IsoAnimal.getConnectedDZone`、`IsoHutch.isDoorClosed`）沒有任何欄位寫入，只新建區域清單並呼叫清單與 `String.equals`，略過它不改任何狀態或亂數。

**手術**：`AnimalData.checkEggs` 內 4 個 `getRegionHutch()`（offset 254、261、281、295）1:1 改道 `zombie/mdc/HutchEggRegion.regionHutch`：動物已在雞舍內（`parent.hutch != null`）時直接回傳 `parent.hutch`，否則照原版搜尋。281、295 只在 277 確認 `parent.hutch == null` 之後才到，照原版。掛在 W33／W42／W60 已有的 `AnimalData` ClassPatch，`expectedHits = 4`。

行為差異只有一種：原版搜尋本身丟例外（畜牧區的雞舍清單含 null，`testHutch.type` NPE）時，雞舍內的動物不再丟；原版這時每個 tick 都會丟例外打斷 `IsoHutch.update`。`IsoHutch.update` 有 `isOwner()`（`!GameClient.client`）守衛，client 不跑這段，也沒有 desync。

console（開機第一次呼叫印一行，之後每 5 分鐘一行）：

```text
[MinidoracatJavaPatch][HutchEggRegion] mode=<0|1|2> calls=<n> skips=<n> samples=<n> sampleUs=<x.x> estSavedMs=<n> sampleErrors=<n> logErrors=<n>
```

`calls` 是改道點被呼叫的累計次數，`skips` 是其中動物在雞舍內、略過搜尋的次數（observe 時是可略過的次數），`sampleUs` 是每 256 次略過抽量一次原版 `getRegionHutch()` 的平均耗時（只計時、結果丟掉），`estSavedMs` ＝ `skips` × `sampleUs`（累計毫秒；observe 時是可省下的量）。兩行 `estSavedMs` 的差 ÷ 300 秒就是每秒省下的主執行緒毫秒數。第一行在 `mode=` 前多一段「首次生效」說明。

**kill switch**：`-Dmdc.hutchEggRegion`：`1|on`（預設）、`2|observe`（照原版搜尋，只計數並抽樣計時）、`0|off`（純委派、不計數）；其他值當 on；需重啟。

**部署**：manifest 新增 `zombie/mdc/HutchEggRegion.class`，`AnimalData.class` 與 `PatchInfo.class` 換新。上線後確認橫幅指紋與 `[mdc-javagate] OK: <manifest 行數>`，並看到 `[HutchEggRegion]` 首次生效行。

**不處理**：
- 雞舍外的動物（`callToHutch`、`checkEggs` 走 offset 281／295）仍照原版搜尋：結果真的會用到。要加速得替畜牧區查詢建索引（第三輪研究的方案 B），等本刀上線後的 dump 仍看得到 `getZone` 再評估。
- 產蛋箱滿了沒人撿時母雞的蛋時間一直卡在「已到期」、不再產蛋：原版行為，本刀不改。

**守門與驗證**：
- SmokeCheck：存在理由——`checkEggs` 恰 4 個 `getRegionHutch`；第 1 個之後是 `IFNULL`、第 2 個之後是 `isDoorClosed` 的 `IFNE`、再來 `aload_0; getfield parent; getfield IsoAnimal.hutch; IFNONNULL`，三個跳轉目標相同，且目標是 `aload_0; getfield parent; iconst_0; invokevirtual IsoAnimal.addEgg(Z)Z`；第 3、4 個在 `IFNONNULL` 之後。搜尋一路六個方法沒有 `PUTFIELD`／`PUTSTATIC`／`INVOKEINTERFACE`／`INVOKEDYNAMIC`，呼叫只限清單方法、`String.equals` 與彼此。任一條轉紅＝TIS 改了流程或搜尋，先重估再談。手術後 `checkEggs` 除了 4 個改道，方法文字（含 frames）與原版相同；helper 只讀 `AnimalData.parent` 與 `IsoAnimal.hutch`、沒有 `PUTFIELD`、會委派原版 `getRegionHutch`。
- `HutchEggRegionTest`（`build.ps1` [9zn/10]，on、observe、off 各一個 JVM，測試自驗 argv 與實際模式相符）：同一個 JVM 各用一個隔離 class loader 載入 dist 內手術後的 `AnimalData`（連同 dist 內的 helper）、jar 內的原版，以及 8 個把 `getRegionHutch()` 改道到測試內 mutant 的原版；三方都只把讀交配季的 `SandboxOptions`（測試 JVM 建不起）換成同形替身。動物、行為、雞舍是只覆寫出口（`addEgg`、`callToHutch`、`addMetaEgg`、`haveRoomForNewEggs`、`isWild` 等）的子類，畜牧區是真的 `DesignationZoneAnimal`（兩塊相連、一塊不相連），原版的相連搜尋與雞舍型別比對照原碼跑。23040 種情境（動物在不在雞舍內、雞舍放在哪一塊與型別、門開關、有沒有空位、meta、`addEgg`／`addMetaEgg` 結果、蛋時間、今天蛋數、野生、`adef.hutches`）逐筆比對 `eggsToday`、`eggTime`、產卵欄位、出口呼叫順序與例外。on：只有「雞舍內、原版搜尋自己丟例外」那 160 種不同（原版丟 NPE、on 不丟），每一種都和照 on 語意自己寫的 mutant 相同；雞舍內情境的搜尋次數 3360 → 22（只剩抽樣）；calls／skips 與改道點命中數相符，抽樣數照每 256 次一次；同一個丟例外情境連跑 256 次，抽樣搜尋丟的例外只計 `sampleErrors`、不外拋；心跳行格式相符。observe、off：23040 種逐筆相同、搜尋次數相同；observe 計數相符，off 不計數。mutant：一律回 null、一律回 `parent.hutch`、只在雞舍外短路、雞舍外不比型別都被抓到；雞舍內回 null、雞舍內回別的雞舍與原版相同（等價的證明）。

**退場**：TIS 讓雞舍內的動物不再走這段搜尋，或改了 `checkEggs` 的分支（SmokeCheck 存在理由轉紅）時重估。

<a id="2ch"></a>
## 2ch. IsoObject 每幀更新閘（W73，server，預設 observe）

**症狀**（2026-10-25 晚峰，45–48 人，FPS 9.2–9.6、主執行緒 CPU 94%）：60 份 thread dump 中 `IsoCell.ProcessIsoObject` 在堆疊上 7 次，其中 5 次以它本身為最上層（約 8% 主執行緒，約 9 ms／幀）。最上層是它本身＝停在不含 safepoint poll 的便宜 `update()` 本體或迴圈本身，跟玩家建造物的空操作 update 相符；但清單大小與組成沒有任何現成紀錄，所以本刀第一次部署只量。

**根因**（javap 對 42.21.0 jar `e1a69eb7`）：
- `IsoCell.updateInternal` 每幀呼叫 `ProcessIsoObject`，沒有 server 守衛；它對 `processIsoObject` 每件呼叫 `IsoObject.update()`（offset 82，全方法唯一），有東西要移除的幀先 `ArrayList.removeAll(processIsoObjectRemove)`（offset 20）。
- 進清單的是 `IsoThumpable`（玩家建造物、搬過的家具，`addToWorld` 無條件登記）、爐子、洗衣機／烘衣機、壁爐、烤肉架、堆肥箱、陷阱、飼料槽、發電機、充電器、屠宰鉤；冰箱只有客戶端登記。物件只要還在已載入的 chunk 上就每幀 update，chunk 卸載經 `IsoObject.removeFromWorld` 正常移除（沒有漏）。
- 伺服器上 `IsoThumpable.update()` 的路徑：offset 1 `getObjectIndex()`（`square.objects.indexOf`，-1 就 return）→ offset 12 `GameServer.server` ifne 298 → offset 299–305 `getLifeLeft`／`ldc -1.0f`／`fcmpl`／`ifle 420`，420 是 `return`。`lifeLeft` 初值 -1，只有有燃料的燈才 > -1；所以一般建造物每幀只做一次線性 `indexOf` 與一次 getter，沒有任何寫入。全 jar 沒有 `IsoThumpable` 子類別。

**手術**（掛在 W40／W45／W47 的 `IsoCell` ClassPatch）：`ProcessIsoObject` 的 `IsoObject.update()` 與 `ArrayList.removeAll` 1:1 改道 `zombie/mdc/IsoObjectUpdateGate.update`／`removeAll`，頭部 `beginPass`、唯一 RETURN（offset 108）前 `endPass`（命中 4）；`addToProcessIsoObject`／`addToProcessIsoObjectRemove` 頭部 `touchAdd`／`touchRemove`（各命中 1）。清單內容、順序與其他物件的更新順序都不動。
- **observe（預設）**：照原版逐件 update。一般幀每件只多一次旗標判斷（本機 6 萬件量三次，每件多 0.55–0.69 ns；off 與原版差 ±0.4 ns 以內；8 萬件約每幀 0.05 ms），每幀兩次 `nanoTime`；每 5 分鐘挑一幀逐件計時（每件兩次 `nanoTime`＋一次 HashMap 查詢），同一幀結束時走一遍清單普查，寫一行心跳。
- **on**：`GameServer.server`、精確類別 `IsoThumpable`、每幀即時讀的 `lifeLeft` 不是 `> -1`（與原版 `fcmpl; ifle` 相同，NaN 也跳過）時不呼叫 update。燈被點起來（`setLifeLeft`、`loadChange`）下一幀就恢復原版路徑，不需追蹤寫入點。每 4096 次跳過抽一次照原版跑，前後比對 `IsoThumpable` 宣告的全部欄位，不一致就本次啟動永久回原版並印一行座標。
- **off**：純委派，不計數、不出心跳。

console（第一幀一行首次生效，之後每 5 分鐘一行；frames／pass／removeAll／timed 是本區間值，skips／audits／fallback／offThread 是累計值）：

```text
[MinidoracatJavaPatch][IsoObjectGate] 首次生效 mode=observe raw=null server=true（…）
[MinidoracatJavaPatch][IsoObjectGate] beat mode=observe frames=<n> passAvgMs=<ms> passMaxMs=<ms> size=<清單長度> setSize=<processIsoObjectSet 大小> nulls=<n> removeAll=calls:<n>,removed:<n>,avgUs:<µs>,maxUs:<µs> thump=<n>(skip=<可跳過>,lit=<燈>,orphan=<不在格子 objects 裡>) noSquare=<n> noChunk=<已卸載卻還在清單> classes=<類別:數量,…> timedMs=<逐件計時幀總計> skipMs=<可跳過件> skipNetMs=<扣掉 nanoTime 成本> skipN=<n> tsNs=<一次 nanoTime ns> timed=IsoThumpable(skip):<n>/<µs>,<類別>:<n>/<µs>,… skips=<n> audits=<n> fallback=<n> offThreadAdds=<n> offThreadRemoves=<n> anomalies=<n>
```

**判讀與開 on 的條件**：`skipNetMs`（逐件計時那一幀裡可跳過的 thumpable 實際花的毫秒）連續幾次晚峰心跳都 ≥ 2 ms（約 2% 主執行緒）才開 on；開 on 後 `passAvgMs` 應降低約 `skipNetMs` × 0.8，降幅不到一半就撤回 observe。`noChunk` 非零＝有物件在 chunk 卸載後還留在清單（漏），`offThreadAdds`／`offThreadRemoves` 非零＝有非主執行緒寫清單（已知可能來源：屠宰鉤建構子在 LoadChunk 執行緒、WorldStreamer Convert／SoftReset job），兩者都另案處理。

**kill switch**：`-Dmdc.isoObjectGate=0|off`（原版）、`1|on`（跳過）、`2|observe`（預設）；未知值當 observe，需重啟。

**部署**：manifest 新增 `zombie/mdc/IsoObjectUpdateGate.class`，`IsoCell.class` 換新。上線後確認首次生效行 `mode=observe`，5 分鐘後第一行 beat。

**不處理**：
- 洗衣機／烘衣機閒置時每幀查電力、堆肥箱幾乎每幀掃容器兩遍：有寫入，不是空操作，不能套同一種跳過；看心跳 `timed` 再另案。
- 「只迭代活躍子集」（連 thumpable 的 header 都不碰）：要追蹤 6 個 `putfield lifeLeft` 與 Lua 經 getter 改清單，複雜度等同 W45；等 on 之後殘餘仍 ≥ 2 ms 再評估。
- `removeAll` 改寫：保序是原版與 MinidoracatFixesFor42 `MDFX_ChargerIdleGuard` 的前提；identity 掃描在 ZGC 下實測沒有比較快。

**守門與驗證**：
- SmokeCheck：存在理由——`ProcessIsoObject` 恰一個 `IsoObject.update()`、一個 `ArrayList.removeAll`（參數是 `processIsoObjectRemove`）、一個 null 檢查、單一 RETURN；`IsoThumpable.update` 伺服器路徑如上（逐條比對）且整個方法以 SHA-256 釘住；`getLifeLeft` 是單純 getter；`getObjectIndex`（SHA 釘）→ `IsoGridSquare.getObjects` getter → `PZArrayList.indexOf`／`objectsEqual` 沒有寫入、沒有 throw；全 jar 沒有 `IsoThumpable` 子類別，`IsoThumpable` 與 jar 內祖先沒有覆寫 `equals`（任一條紅＝重評跳過條件，不要直接改釘）。手術後 `ProcessIsoObject` 兩處改道、head、tail、真指令恰 +4；兩個 add 方法頭部各 +2；W40 的「IsoCell 其餘方法逐指令不變」把這三個方法列入例外。helper 的 `skippable` 讀 `GameServer.server`、以 `getClass` 比 `IsoThumpable.class`、用 `fcmpl`（不是 `fcmpg`）。
- `IsoObjectUpdateGateTest`（`build.ps1` [9zo/10]，observe、on、0、未知值四個 JVM，各自驗 argv 與實際模式相符）：原版 jar 的真 `IsoThumpable.update` 與閘門在伺服器／客戶端 × `lifeLeft`（-1、-2、-1 的下一個 float、-Inf、NaN、-0.5、-0、0、5、+Inf）× 格子（無、不在 objects、在 objects）共 60 格逐欄位（含祖先）相同，跳過次數只在 on 且等於預期；子類別一律照原版；原版負對照（燈的 update 會寫欄位，差分抓得到錯誤跳過）；五個 mutant（`>=`、`<= -1`、拿掉 server 檢查、改 instanceof、拿掉 lifeLeft 判斷）全被抓到；dist 內手術後的真 `ProcessIsoObject`：null 略過、其他物件照常、燈照原版、removeAll 計數、心跳每個欄位、心跳後區間歸零、非主執行緒 add／remove 各記一次（off 全部不記）；on 另驗 8192 次可跳過抽 2 次照原版跑、比對不一致即永久停用且之後全照原版。

**退場**：官方讓 `IsoThumpable` 不再每幀登記，或 SmokeCheck 存在理由轉紅時重估；observe 資料顯示可跳過部分 < 2 ms／幀就整刀撤回。

<a id="2ci"></a>
## 2ci. 尋路執行緒觀測（W74，server，預設 on，純觀測）

**現象**（2026-10-25 正式服，45–48 人）：`PathfindNativeThread` 99–100% CPU（開服 44 分鐘平均 93%），60 份 thread dump 全部停在 `PathfindNative.findPath ← PathfindNativeThread.findPath ← updateThread`。它是獨立執行緒，主執行緒不會等它（只經 `ConcurrentLinkedQueue` 送請求、收結果），所以不拉低 FPS；滿載的代價是尋路排隊，殭屍與動物在等結果時原地不動。

**原版結構**（javap 對 42.21.0 jar；native 以 objdump 對 42.21.0 `libPZPathFind64.so`）：
- `PathfindNativeThread.runInner` 每輪由 `Sync` 控在 50 ms（`endFrame` 的 `50000000L`）；`updateThread` 依序取空 chunk（每輪最多 10 個 `ChunkUpdateTask`）、格子、載具、請求四個任務佇列（8 個 `ConcurrentLinkedQueue.poll`，offset 7／54／68／96／110／140／154／182），呼叫 `PathfindNative.update()`（offset 192），再以 `requestsPerUpdate = 2`（offset 195 `iconst_2`）最多處理 2 個未取消的請求。吞吐上限是每秒 40 個請求；CPU 吃滿時每個請求平均至少 25 ms。
- 請求依 `PathRequestTask.init` 分三個佇列：玩家、有目標的殭屍、其他（所有動物與沒目標的殭屍），`RequestQueue.removeFirst` 嚴格依此優先。
- native 端 `updateChunk`／`removeChunk`／`updateSquare`／`addVehicle`／`removeVehicle`／`teleportVehicle` 六個 JNI 都會設起一個全域旗標；`findPath` 一進門看到旗標就釋放全部可見圖、`createVisibilityGraphs`（含 `createVehicleClusters`）、遞增 `HLAStar::ModificationCount`（每個 chunk level 的高階區域在之後的搜尋中重算）。所以每輪只要有任何世界變動任務，下一次 native `findPath` 就要付一次全域重建。重建佔每次尋路多少，在這刀之前量不到。

**手術**（全部同形 1:1 改道到 `zombie/pathfind/nativeCode/MdcPathfindProbe`，receiver 前置；helper 放在目標 package，才讀得到 package-private 的請求欄位與任務類別）：
- `PathfindNativeThread.updateThread()V`：8 個 `INVOKEVIRTUAL ConcurrentLinkedQueue.poll` → `poll(ConcurrentLinkedQueue)`，依取出的任務類別計 chunk（更新與移除）、格子、載具；`INVOKESTATIC PathfindNative.update()V` → `update()`，先結算這一輪再呼叫原版。`expectedHits` 9。W81 起這 9 個點改道到 `MdcPathfindGate`，再由它呼叫這兩個入口（[2cp](#2cp)）。
- `PathfindNativeThread.findPath(PathFindRequest)V`：offset 20 的 `INVOKEVIRTUAL PathfindNative.findPath(PathFindRequest,ByteBuffer,Z)I` → `findPath(PathfindNative,…)`，計時、記成敗（native 回傳不是 1 算失敗）、請求者、排隊時間。`expectedHits` 1。
- `PathRequestTask.execute()V`：offset 11 的 `INVOKEVIRTUAL PathfindNativeThread.addRequest(PathFindRequest,I)V` → `addRequest(PathfindNativeThread,…)`，記入隊時刻。`expectedHits` 1。
- 入隊與輪次的簿記在原版呼叫之前，`findPath` 的計時包住原版呼叫、記帳在之後；原版呼叫只有一次、參數原樣、回傳值原樣、例外原樣上拋（不計入 `calls`，另計 `thrown`）；簿記自己的 RuntimeException 只計 `anomalies`。所有簿記都在尋路執行緒上（單寫者），log 也在同一執行緒用 `DebugLog.log` 寫，與 ChunkSaveIsolation、ChunkWriteGuard 等非主執行緒 helper 相同。
- 重建分組：native 旗標在世界變動任務後成立、下一次 native `findPath` 後清除。探針以「上次 native `findPath` 之後有沒有執行過世界變動任務」把每次呼叫分成 `rb`（付重建）與 `nr`（不付）。兩個偏差方向相反：native 對 `loadId` 過期的 chunk 不設旗標，所以 `rb` 次數是上界；高階區域在之後第一次被搜尋碰到時才重算，有一部分會落在 `nr` 呼叫裡，所以 `rb − nr` 的差是重建成本的下界。
- 探針成本（本機 `build.ps1` [9zp/10] 量測，native 換成立即回傳的替身）：每個請求（一次 `addRequest`＋一次 `findPath`）多 71 ns；每輪另有一次 `nanoTime` 與幾個計數。相對每個請求至少 25 ms，約百萬分之 3。

console（開服第一輪一行，之後每 60 秒一行，數字都是這 60 秒內的值，`total` 除外）：

```text
[MinidoracatJavaPatch][PathfindProbe] 首次生效（純觀測，每 60 秒一行；-Dmdc.pathfindProbe=0|off 停用）
[MinidoracatJavaPatch][PathfindProbe] beat sec=<視窗秒數> rounds=<輪數> roundMsAvg=<每輪平均 ms> roundMsMax=<最長一輪 ms> worldRounds=<有世界變動的輪數> chunk=<n> square=<n> vehicle=<n> enq=<玩家>/<有目標殭屍>/<其他> calls=<native findPath 次數> extra=<多目標的第 2 個以後> fail=<n> thrown=<n> busyPct=<native 耗時佔視窗 %> usAvg=<µs> usP95=<µs> usMax=<µs> rb=<n>/<平均 µs>/<p95 µs> nr=<n>/<平均 µs>/<p95 µs> rebuildPct=<%|n/a> waitMs=<平均>/<p95>/<最大> player=<n>/<失敗>/<平均 µs>/<平均等待 ms>/<p95 等待 ms> aggroZombie=… zombie=… animal=… other=… otherTasks=<n> waitDropped=<n> anomalies=<n> total=<累計 calls>
```

欄位說明：`roundMs` 是相鄰兩次 `update()` 的間隔（含執行緒閒置等通知的時間）。`enq` 是請求進優先佇列的次數，與各請求者的 `n` 相減就是被取消或還在排的。`waitMs` 從請求進優先佇列算到它第一次 native `findPath` 開始，不含主執行緒送出到尋路執行緒下一輪撿起的那一段（最多一輪，看 `roundMsAvg`）。p95 是每倍頻 4 桶的直方圖上界（誤差 ≤ 25%，不超過最大值）。`rebuildPct = rb 次數 ×（rb 平均 − nr 平均）÷ 總耗時`。請求者依 mover 類別分：`animal`（`IsoAnimal`）、`player`（其他 `IsoPlayer`）、`aggroZombie`（`IsoZombie` 且 `target` 非 null）、`zombie`、`other`。

**判讀**（上線後看晚峰幾行）：
- `busyPct` 接近 100 且 `roundMsAvg` 遠大於 50：成本瓶頸。看 `rebuildPct`：≥ 30% 值得做世界變動批次化閘門（把重建從每輪一次攤到每 T 毫秒一次；k 輪攤一次時每個請求成本約剩 `1 − rebuildPct × (1 − 1/k)`，30%、k=5 約 1.3 倍吞吐，50% 約 1.7 倍）；< 15% 不值得（全部拿掉也只有約 1.18 倍），改看 `usP95`／`fail` 高的請求者（例如失敗請求很貴時考慮負快取）。
- `roundMsAvg` 約 50、`calls` 約等於 `rounds × 2` 且 `waitMs` 很長：每輪 2 個的上限才是瓶頸，考慮把 `requestsPerUpdate` 調大（同長度常數手術）。
- `animal` 的等待 p95 遠大於其他：嚴格優先序讓動物排最後，屬原版取捨。
- `anomalies`、`otherTasks`、`waitDropped` 應為 0；`thrown` 非 0 代表 native 尋路丟例外（原版照樣處理）。

**kill switch**：`-Dmdc.pathfindProbe=0|off`，需重啟；關掉後四個入口都只委派，不讀時鐘、不計數、不寫 log。

**部署**：manifest 新增 `zombie/pathfind/nativeCode/MdcPathfindProbe.class`、`MdcPathfindProbe$Hist.class` 與 `PathfindNativeThread.class`、`PathRequestTask.class`（+4 個 class，`PatchInfo.class` 換新）。上線後確認 console 有「首次生效」一行，之後每分鐘一行 `beat`。

**不處理**：本刀不改尋路行為。批次化閘門、`requestsPerUpdate`、負快取都等這刀的晚峰數據再決定；pfguard 對這條執行緒的成本另估約 0.1%（不在本刀範圍）。

**守門與驗證**：
- SmokeCheck：存在理由——`updateThread` 恰 8 個 `ConcurrentLinkedQueue.poll`、1 個 `PathfindNative.update` 緊接 `iconst_2; istore`（`requestsPerUpdate = 2`）、1 個 `RequestQueue.isEmpty`；`Sync.endFrame` 恰一個 `50000000L`；`findPath(PathFindRequest)` 恰 1 個 `PathfindNative.findPath`；`PathRequestTask.execute` 恰 1 個 `addRequest`。任一變了，beat 的輪次與重建分組要重新解讀。手術後三個方法除了被改道的呼叫，方法文字（含 frames）與原版相同，兩個 class 其餘方法不變；helper 的 `poll`／`update`／`addRequest` 各恰一次呼叫原版，`findPath` 的 on／off 兩條路各一次。
- `MdcPathfindProbeTest`（`build.ps1` [9zp/10]，on、off 兩個 JVM，自驗 argv 與實際開關相符）：走 dist 內手術後的真 `updateThread`、`findPath(PathFindRequest)`、`PathRequestTask.execute`（測試 JVM 沒有 native 庫，兩個 native 入口丟 `UnsatisfiedLinkError`，正好驗原版例外原樣上拋）：chunk、格子、載具任務照原順序各執行一次、`taskReturnQueue` 收到同一批任務、請求進原版同一個優先佇列（同一物件、原順序）；以覆寫 `findPath` 的替身驗回傳原版值、參數同一物件、原版恰一次、`RuntimeException` 與 `Error` 同一物件上拋；on 的請求者分類、多目標、失敗、`rb`／`nr` 分組與一行欄位正確；off 全部零計數（原版負對照）；最後量每個請求的探針成本（上限 20 µs，實測 71 ns）。

**退場**：判讀完、決定（或不做）批次化閘門之後可以關掉或撤刀。

<a id="2cj"></a>
## 2cj. 非主執行緒的每幀更新清單移除排回主執行緒（W75，server，預設 on）

**症狀**（2026-10-25 正式服，W73 觀測）：50 分鐘內 20 次 `off-main-thread remove thread=ServerPlayersVehicles`，來源都是載車時把拖車裡的動物移出世界：`BaseVehicle.load → addAnimalInTrailer → IsoAnimal.removeFromWorld → … → IsoObject.removeFromWorld → IsoCell.addToProcessIsoObjectRemove`；`offThreadAdds` 為 0。原版在背景執行緒讀 `processIsoObjectSet`、可能寫 `processIsoObjectRemove`（兩個都是 `HashSet`），主執行緒同時在 `ProcessIsoObject` 頭部 `removeAll`／`clear`、在 `addToProcessIsoObject` 改同兩份集合。目前沒有觀察到 `ConcurrentModificationException`，但 HashSet 並行寫入可能讓移除遺失或內部結構損壞（同類問題見 W40／W41 的 `processItems`）。

**原版語意**（javap 對 42.21.0 jar `e1a69eb7`）：`addToProcessIsoObjectRemove` 是 `processIsoObjectSet.contains → processIsoObjectRemove.add`；`addToProcessIsoObject` 是 `processIsoObjectRemove.remove → processIsoObjectSet.add → processIsoObject.add`（加回會取消待移除）；`ProcessIsoObject` 頭部 `isEmpty → removeAll → removeAll → clear` 之後才進更新迴圈。全 jar 對 `addToProcessIsoObjectRemove` 只有 `IsoObject.removeFromWorld()` 一個呼叫點（offset 15）。

**手術**（比照 W41）：
- `IsoObject.removeFromWorld()` offset 15 唯一的 `invokevirtual IsoCell.addToProcessIsoObjectRemove` 1:1 改道 `zombie/mdc/IsoObjectRemoveDefer.addToProcessIsoObjectRemove(IsoCell, IsoObject)`（receiver 前置，命中 1；`IsoObject` 原本沒有 server ClassPatch）。非主執行緒（`GameServer.mainThread` 以外）不跑原版，把（cell, 物件）排進佇列；主執行緒或 `mainThread` 還沒設定時照原版。佇列超過 10 萬筆時照原版直接呼叫。
- `ProcessIsoObject` 頭部（W73 的頭部呼叫改經 `IsoObjectRemoveDefer.beginPass`）：先排空佇列，對每筆呼叫原版 `addToProcessIsoObjectRemove`，再呼叫 W73 的 `beginPass`。排空在原版 `isEmpty`／`removeAll` 之前，所以移除在同一輪生效，與原版背景呼叫後下一幀的結果相同。
- `addToProcessIsoObject` 頭部（W73 的 `touchAdd` 改經 `IsoObjectRemoveDefer.beforeAdd(cell, object)`，HeadCall 帶 slot 0、1）：先呼叫 W73 `touchAdd`，再把佇列裡同一 cell＋物件的待移除拿掉，對應原版那行 `processIsoObjectRemove.remove(object)`，「背景移除→主執行緒加回」結果與原版相同。W75-2（[2ck](#2ck)）起，任何直接執行的加入都會取消（主執行緒，或沒有改道、開關 off 時的背景執行緒），只有排空自己呼叫的加入不取消。
- 用頭部呼叫而不是改道 `addToProcessIsoObject` 的呼叫點：呼叫點遍布十幾個類別，Lua（例如 MinidoracatFixesFor42 的 `MDFX_ChargerIdleGuard`）也直接呼叫這個公開方法，頭部呼叫才能涵蓋全部。
- 與 W73 無關：`-Dmdc.isoObjectGate=0` 時 W73 的 `beginPass`／`touchAdd` 直接返回，W75 照樣排空與取消。

console（前 20 次延後與之後每 1000 次各一行附來源；第一幀一行首次生效；之後每 5 分鐘一行心跳，數值為累計）：

```text
[MinidoracatJavaPatch][IsoObjectRemoveDefer] deferred n=<n> thread=<執行緒> via=<呼叫來源>
[MinidoracatJavaPatch][IsoObjectRemoveDefer] 首次生效 on raw=<property>（…）
[MinidoracatJavaPatch][IsoObjectRemoveDefer] beat deferred=<n> drained=<n> cancelled=<n> pending=<n> maxPending=<n> overflow=<n> anomalies=<n> sources=[<次數>×<來源> | … | other×<n>]
```

**驗收**：上線後 W73 心跳的 `offThreadRemoves` 不再增加（背景呼叫不再進到 `IsoCell.addToProcessIsoObjectRemove`），W75 心跳 `deferred` 與 `drained`＋`cancelled`＋`pending` 相符、`overflow=0`、`anomalies=0`。

**kill switch**：`-Dmdc.isoObjectRemoveDefer=0|off` 純委派（原版在呼叫端執行緒直接跑），需重啟。

**部署**：manifest 新增 `zombie/mdc/IsoObjectRemoveDefer.class`、`IsoObjectRemoveDefer$Entry.class` 與 patched `zombie/iso/IsoObject.class`，`IsoCell.class`、`PatchInfo.class` 換新（151 → 154 個 class）。

**不處理**：
- 同一個 `IsoObject.removeFromWorld()` 緊接著（offset 20）在背景執行緒呼叫 `removeFromStaticUpdaterObjectList`：動物不在 static updater 清單裡時只讀 Set（`remove` 回 false），沒有觀測到寫入；另案。
- `addToProcessIsoObject` 的非主執行緒延後：觀測 0 次。

**守門與驗證**：
- SmokeCheck：存在理由——上面三個原版語意（呼叫順序逐一比對）與全 jar 唯一呼叫點；手術後 `IsoObject.removeFromWorld()` 除了被改道的呼叫，方法文字（含 frames）與原版相同，`IsoObject` 其餘方法不變；`ProcessIsoObject` 頭部改為 `IsoObjectRemoveDefer.beginPass`、`addToProcessIsoObject` 頭部 `beforeAdd(cell, object)` 真指令 +3；helper 的 `beginPass` 先 `drain` 再 W73 `beginPass`、`beforeAdd` 恰一次 W73 `touchAdd`、`drain` 恰一次呼叫原版。
- `IsoObjectRemoveDeferTest`（`build.ps1` [9zq/10]，on、off、on＋W73 off 三個 JVM，自驗 argv 與實際開關相符）：走 dist 內手術後的真 `ProcessIsoObject`／`addToProcessIsoObject`／`addToProcessIsoObjectRemove`，兩份 Set 換成記錄執行緒的 `HashSet` 子類。`mainThread` 為 null 與主執行緒呼叫照原版；背景呼叫 on 不碰任何集合、排隊，下一次 `ProcessIsoObject` 移除且當幀不 update，與原版循序執行結果相同；off（原版負對照）背景執行緒直接讀寫 Set；「背景移除→主執行緒加回」取消、物件照常 update；取消只比對同一 cell＋物件；心跳欄位。三個 mutant（不取消、排空放在 `removeAll` 之後、在背景執行緒排空）都至少一個情境與原版不同。

**退場**：官方讓背景載車不再呼叫 `removeFromWorld`，或 `IsoCell` 這兩份集合改成執行緒安全時撤刀。

---

<a id="2ck"></a>
## 2ck. 非主執行緒的每幀更新清單加入排回主執行緒（W75-2，server，預設 on）

**症狀**（2026-10-26 正式服，W73 觀測）：00:06 開服到 00:56 `offThreadAdds=81`，前 20 行來源全是同一條：`LoadChunk` 執行緒讀 chunk 檔時建構屠宰鉤，`IsoChunk.LoadFromDiskOrBuffer → IsoGridSquare.load → IsoObject.factoryFromFileInput → IsoObject$40.InstantiateObject → IsoButcherHook.<init>(IsoCell) → IsoCell.addToProcessIsoObject`。到 01:14 累計 110 次：不只開服後，玩家走動載入有屠宰鉤的 chunk 時也持續出現；前一場（22:16 開服）整場 0 次。原版在背景執行緒寫 `processIsoObjectRemove`、`processIsoObjectSet`（HashSet）與 `processIsoObject`（ArrayList），主執行緒同時在 `ProcessIsoObject` 走訪清單、`removeAll`、`clear`。

**原版**（javap 對 42.21.0 jar `e1a69eb7`）：`IsoButcherHook` 恰三個 `invokevirtual IsoCell.addToProcessIsoObject`——`<init>(IsoGridSquare)` offset 37、`<init>(IsoCell)` offset 22（`super(cell)` 之後、cell 為 null 時跳過）、`load` offset 18（只在 `GameClient.client` 分支，伺服器跑不到）。讀檔工廠 classID 40（`IsoObject$40`）呼叫 `<init>(IsoCell)`。

**手術**：
- `IsoButcherHook` 三個呼叫點 1:1 改道 `zombie/mdc/IsoObjectRemoveDefer.addToProcessIsoObject(IsoCell, IsoObject)`（receiver 前置；新 `IsoButcherHook` ClassPatch，三個方法各命中 1）。非主執行緒排進 W75 同一條 FIFO 佇列（op＝ADD），保留背景執行緒加入與移除的先後；主執行緒或 `mainThread` 還沒設定時照原版。佇列上限沿用 10 萬筆，超過照原版直接跑。
- 排空（`ProcessIsoObject` 頭部、原版 `removeAll` 之前）依 FIFO 對每筆呼叫原版：ADD 呼叫 `addToProcessIsoObject`、REMOVE 呼叫 `addToProcessIsoObjectRemove`。排空期間原版加入頭部的 `beforeAdd` 不取消，否則背景「先加後移」時排空的加入會把後面那筆移除取消掉、物件留在清單（原版是被移除）。
- **與原版循序結果相同**：同一 cell＋物件的最終狀態只取決於最後一次呼叫（加入＝留在清單；移除＝下一次 `removeAll` 後不在）。直接執行的原版呼叫一定晚於佇列裡同一物件的所有呼叫，所以先取消佇列裡同一 cell＋物件的相反操作，剩下的同類操作在排空時是冪等的。加入頭部 `beforeAdd` 取消待移除（W75 原案，現在也包括沒有改道的背景加入）；`IsoObject.removeFromWorld` 改道的直接分支（主執行緒，或 W75 off 時的背景執行緒）取消待加入——原版「背景加入後主執行緒移除」是下一輪 `removeAll` 拿掉、不會被 update，延後後若不取消會留在清單。
- 開關各自獨立：`-Dmdc.isoObjectAddDefer`（本刀）、`-Dmdc.isoObjectRemoveDefer`（W75）、`-Dmdc.isoObjectGate`（W73）任一個 off 時另外兩個照常。W75 off 時背景移除照原版在背景執行緒直接跑，仍先取消佇列裡同一物件的待加入；這時取消與主執行緒排空同一筆有極小的競態（排空先拿到就照原版循序可能顛倒），和原版本來的背景寫入同等級，只有混合開關時才會發生。

console（前 20 次延後與之後每 1000 次各一行附來源；首次生效行列出兩個開關；心跳併進 W75 那行，數值為累計）：

```text
[MinidoracatJavaPatch][IsoObjectRemoveDefer] deferred add n=<n> thread=<執行緒> via=<呼叫來源>
[MinidoracatJavaPatch][IsoObjectRemoveDefer] 首次生效 remove=on add=on raw=<W75 property>/<W75-2 property>（…）
[MinidoracatJavaPatch][IsoObjectRemoveDefer] beat deferred=<n> drained=<n> cancelled=<n> pending=<n> maxPending=<n> overflow=<n> anomalies=<n> sources=[…] add=deferred:<n>,drained:<n>,cancelled:<n>,overflow:<n> addSources=[<次數>×<來源> | …]
```

`deferred`／`drained`／`cancelled`／`overflow`／`sources` 是移除（W75，欄位與之前相同）；`add=` 與 `addSources` 是加入；`pending`／`maxPending` 是兩者共用的佇列。

**驗收**：上線後 W73 心跳的 `offThreadAdds` 不再增加，W75 心跳 `add=deferred` 增加且 `deferred`＝`drained`＋`cancelled`（兩種操作各自）＋共用的 `pending`，`overflow=0`、`anomalies=0`。

**kill switch**：`-Dmdc.isoObjectAddDefer=0|off` 純委派（原版在呼叫端執行緒直接跑），需重啟。

**部署**：manifest 新增 `zombie/mdc/IsoObjectRemoveDefer$Stats.class` 與 patched `zombie/iso/IsoButcherHook.class`，`IsoObjectRemoveDefer.class`、`IsoObjectRemoveDefer$Entry.class`、`PatchInfo.class` 換新（154 → 156 個 class）。

**不處理**（全 jar 其他 `addToProcessIsoObject` 呼叫點，這次不改）：
- 各類物件的 `addToWorld`：`IsoThumpable`、`IsoStove`、`IsoBarbecue`、`IsoCarBatteryCharger`、`IsoClothingDryer`、`IsoClothingWasher`、`IsoCombinationWasherDryer`、`IsoStackedWasherDryer`、`IsoCompost`、`IsoFeedingTrough`、`IsoFireplace`、`IsoGenerator`、`IsoTrap`。chunk 載入時由 `IsoChunk.doLoadGridsquare` 呼叫，一般在主執行緒（`ServerMap.preupdate → ServerCell.Load2 → RecalcAll2`）；`WorldStreamer` 的 Convert／SoftReset job 會在自己的執行緒直接呼叫 `doLoadGridsquare`，這條是非主執行緒，但觀測 0 次。
- `IsoObject.addToWorld` 的冰箱分支與 `IsoObject.checkHaveElectricity`：都在 `!GameServer.server` 內，伺服器跑不到。

**守門與驗證**：
- SmokeCheck：存在理由——`IsoButcherHook` 恰三個呼叫點（列出方法）、`<init>(IsoCell)` 在 `super(cell)` 之後以 `aload_1`／`aload_0` 呼叫、讀檔工廠 `IsoObject$40` 建構 `IsoButcherHook(IsoCell)`、`IsoGridSquare.load` 呼叫 `factoryFromFileInput`；手術後三個方法除了改道，文字與 frames 不變，`IsoButcherHook` 其餘方法不變。helper 契約：`drain` 各恰一次呼叫原版加入與移除、兩個改道入口各恰一次呼叫原版、移除入口在呼叫原版之前取消待加入、`beforeAdd` 呼叫取消。W73、W75 既有守門維持綠燈。
- `IsoObjectAddDeferTest`（`build.ps1` [9zr/10]，on、off、on＋W75 off＋W73 off 三個 JVM，自驗 argv 與實際開關相符）：走 dist 內手術後的真 `IsoButcherHook(IsoCell)` 建構子與真 `IsoCell`，兩份 Set 換成記錄執行緒的 `HashSet` 子類、清單換成記錄每件被取出（update）次數的 `ArrayList` 子類，每個情境另以原版在主執行緒依呼叫時間循序跑一份比對。`mainThread` 為 null 與主執行緒建構照原版；背景加入 on 不碰集合與清單、排隊，下一輪在清單且被 update 一次；背景「先加後移」排空後不在清單、不被 update；背景移除後主執行緒加入留在清單；背景加入後主執行緒移除不在清單、不被 update（取消待加入）；取消只比對同一 cell＋物件；心跳欄位；off（原版負對照）背景執行緒直接讀寫集合與清單。三個 mutant（排空時不跳過取消、主執行緒移除不取消待加入、ADD／REMOVE 分兩條佇列，兩種排空順序各一個情境）都與原版不同。

**退場**：官方把屠宰鉤的登記移到 `addToWorld`，或 `IsoCell` 這三份集合改成執行緒安全時撤刀。

---

<a id="2cl"></a>
## 2cl. 建造請求的類別與距離驗證（W76、W77，server，預設 on）

強化伺服器端的建造請求驗證：不通過的請求在呼叫建造物件的建構子之前拒絕，原版照常回 Reject，客戶端不會卡住；正常建造的授權、解析與處理流程不變。缺口細節不在公開文件描述。

**檢查**：
- **W76 類別**：請求指名的全域類別表，沿 metatable 鏈（最多 16 層）必須找得到全域 `ISBuildingObject`；`ISBuildingObject` 本身、找不到的全域、鏈上有循環的都拒絕。不寫死名單：原版所有建造類別（舊式 `ISWoodenWall` 等與 B42 的 `ISBuildIsoEntity`）都是 `ISBuildingObject:derive(...)`，原版唯一的二層 derive `ISAnimalPickMateCursor` 也通過；MOD 的建造類別只要也 derive 就能過。
- **W77 距離**：建造位置與 owner 的位置每軸差 ≤ 8 格、樓層差 ≤ 1.5；座標不是有限數一律拒絕。原版游標的 z 就是玩家所在樓層，`tryBuild` 先走到旁邊才送出；合法最遠是 4 格寬雙門、雙柵欄門的原點格（約 5.1 格），8 格是加上伺服器端位置落後的餘量，1.5 是爬梯時玩家 z 是小數。role 有 `UseBuildCheat` 的管理員不限（作弊建造跳過走路；管理面板的建造作弊鈕只改客戶端旗標，所以看伺服器端的 role，不看 `isBuildCheat()`）。

**合法來源調查**：正式服 2026-10-25 的 MOD 清單裡，本機有副本的 MOD 都沒有自己送建造請求或另做不經 `ISBuildingObject` 的建造類別；Neat_Building、TheShortcut 走原版 `ISBuildIsoEntity`，AreaTasks 要求玩家站在該格，SMB_steps 梯子站位距離小於 1、z 是小數。

**手術**：`BuildAction.parse` 頭部 `MdcBuildActionGuard.beginParse(this)`（HeadCall），唯一 `LuaCaller.protectedCall`（javap offset 221，建造物件的建構子）1:1 改道 `MdcBuildActionGuard.protectedCall`（新 ClassPatch，命中 2）。不通過時不呼叫建構子，回只帶一個 nil 的 `LuaSuccess`，原版 `getFirst() == null` 分支設 `item = null`，之後 `processServer` 照原版回 Reject。x、y、z 與 `objectType` 是 `BuildAction` 的 private 欄位，helper 在 class init 時反射取得（找不到就 class init 失敗＝fail-fast）；helper 放 `zombie.core` 才讀得到 `Action.playerId`。檢查本身拋 `RuntimeException` 時計 anomalies、照原版呼叫建構子。

console（第一次建造請求一行首次生效；拒絕時每 10 秒最多 20 行）：

```text
[MinidoracatJavaPatch][BuildActionGuard] 首次生效 typeCheck=on rangeCheck=on maxRange=8.0 maxDz=1.5（…）.
[MinidoracatJavaPatch][BuildActionGuard] refused reason=<type|range> type=<請求的類別名> owner=<onlineID>:<名稱> at=<x>,<y>,<z> target=<x>,<y>,<z> typeRefused=<n> rangeRefused=<n> suppressed=<n> anomalies=<n>.
```

**驗收**：上線後正常建造（舊式木牆、B42 建造選單、多格家具、雙門、樓梯、梯子上）照常完成、沒有 `refused` 行；有 `refused` 行時以 `type=`、`at=`、`target=` 判斷是誤擋還是異常請求。

**kill switch**：`-Dmdc.buildTypeCheck=0|off`（W76）、`-Dmdc.buildRangeCheck=0|off`（W77），各自獨立，需重啟。

**部署**：manifest 新增 `zombie/core/MdcBuildActionGuard.class` 與 patched `zombie/core/BuildAction.class`。

**守門與驗證**：
- SmokeCheck：上游指紋——`parse` 恰 1 個 `protectedCall` 且在 `getFunctionObject` 之後、之後 `getFirst()==null` 時 `item=null`，x/y/z/`objectType` 欄位存在，`isValid` 的全 jar 呼叫點數仍為 0；手術後頭部 `aload_0; beginParse`、唯一 `protectedCall` 同形改道，其餘指令與 frames 不變，`BuildAction` 其餘方法不變；helper 委派原呼叫恰 2 處、catch 只有 `RuntimeException`。
- `MdcBuildActionGuardTest`（`build.ps1` [9zs/10]，both、type-off、range-off 三個 JVM，自驗 argv 與實際開關相符）：走 dist 內手術後的真 `BuildAction.parse`＋真 wire（Action 標頭、座標、sprite、`PZNetKahluaTableImpl.save` 的類別與參數表），Lua 環境換成最小替身（`LuaManager.env` 放原版 derive 形狀的類別表，`LuaManager.caller` 計次）。照常建造：原版類別相鄰格、二層 derive、雙門原點格、每軸差恰 8 格、樓層差 1、`UseBuildCheat` 管理員遠距；拒絕（不呼叫建構子、`item=null`、計數與 log）：非建造類別、`ISBuildingObject` 本身、metatable 循環、找不到的類別、單軸 9 格（兩軸各一）、遠處、樓層差 2、NaN；拒絕後跑原版 `processServer`，恰送 1 包 Reject 給送封包的人；off 組態照原版呼叫建構子。

**退場**：官方在伺服器端驗證建造類別與位置時撤刀。

---

<a id="2cm"></a>
## 2cm. timed action 的執行者驗證（W78，server，預設 on）

強化伺服器端的 timed action 驗證：NetTimedAction 請求重建出的動作，執行者（動作表的 `character`，`ISBaseTimedAction.new(self, character)` 設的）必須是送封包的玩家本人，否則回 Reject。缺口細節不在公開文件描述。

**檢查**：沒有新的 bytecode 手術。既有派送 bridge `MdcTimedActionProbe.processServer`（[2aj](#2aj) 的 owner 檢查所在）在 owner 檢查通過之後、交回原版之前，對 Request 狀態且 `action` 不是 null 的 NetTimedAction 讀 `character`：不是 nil 也不是 owner 物件時把 `action` 設成 null，原版 `processServer` 走 `action == null` 的 Reject 分支（與 parse 失敗相同），客戶端收到 Reject。`character` 是 nil 時照原版（無從判斷）。其他 IsoPlayer 參數不看：替別人包紮（`ISApplyBandage` 等）的病人在別的參數，`character` 仍是排隊的人。

**合法來源調查**：原版客戶端以動作表的 `character` 本人送出動作，同機多人時兩者也解析成同一個物件，合法請求恆相等。原版與正式服 MOD 的 `ISBaseTimedAction.new(self, X)`，X 都是排隊的角色；替別人處理的醫療動作、`ISMedicalCheckAction`、`ISWakeOtherPlayer` 的 `character` 都是排隊者。

console（與 owner 檢查共用 TimedActionProbe 的時間窗；首次生效與心跳行多了 `executorCheck=`、`executorRefused=`）：

```text
[MinidoracatJavaPatch][TimedActionProbe] executorMismatch#<n> id=<id> type=<動作類別> owner=<onlineID>:<名稱> character=<onlineID>:<名稱>|<類別> connectionPlayers=<…> action=rejected suppressed=<n>.
```

`-Dmdc.timedActionProbe=0|off` 時照樣拒絕，只是不計數、不記錄（同 owner 檢查）。

**驗收**：上線後一般動作與替別人包紮照常完成，沒有 `executorMismatch` 行。

**kill switch**：`-Dmdc.actionExecutorCheck=0|off`，需重啟。

**部署**：`zombie/core/MdcTimedActionProbe.class` 換新，沒有新 class。

**守門與驗證**：
- SmokeCheck：原版 `NetTimedActionPacket.processServer` 讀 `action`、null 時跳 Reject 分支；bridge 在第一次交回原版之前呼叫 `checkExecutor`，`checkExecutor` 讀 `character`、不符時寫 `action=null`，不自行派送。
- `MdcTimedActionProbeTest`（`build.ps1` [9s/10]，既有四組態＋`actionExecutorCheck=0`，自驗 argv）：`character`＝owner、替別人包紮（病人在別的參數）、nil、非 Request 狀態都照原版派送且動作表不動；`character` 是別的玩家或不是玩家時，仍交給原版派送但 `action=null`（原版 Reject 的 bytes 由 `NetTimedActionGuardTest` 驗），計數並記 `executorMismatch`；`MODE_OFF` 照樣拒絕、零計數零 log；off 組態照原版。

**退場**：官方在伺服器端比對動作執行者與封包發送者時撤刀。

---

<a id="2cn"></a>
## 2cn. 物件外觀更新的距離驗證（W79，server，預設 on）

強化伺服器端的 `UpdateItemSprite` 驗證：送封包的連線上必須有玩家在該物件附近，否則不改、不轉發。缺口細節不在公開文件描述。

**合法來源**：只有客戶端 `IsoObject.transmitUpdatedSprite()` 的客戶端分支會送。呼叫者是 `ISMoveablesAction` 的 scrap／repair（玩家身旁）與 `BentFences.smashFence`／`BrokenFences.destroyFence`（駕駛撞圍籬，車旁、沿圍籬幾格）；其他搬運、拆解的 sprite 更新在伺服器 `complete()` 裡進行。正式服 MOD 沒有在客戶端送這個封包。

**檢查**：每軸差 ≤ 20 格、樓層差 ≤ 1.5；封包不帶玩家，同機多人時任一位在範圍內就放行。20 格是撞圍籬時駕駛離撞擊點、`smashFence` 沿圍籬改幾格、長拖車與伺服器位置落後的餘量（估計值，沒有實機量）。安全屋沒有加：本服的安全屋權限在家族 Safehouse MOD（Lua），原版 `SafeHouse` 以名字判斷，合法撞圍籬也可能發生在別人的安全屋旁。

**手術**：`GameServer.receiveUpdateItemSprite` 頭部 `SpriteUpdateGuard.begin(connection)`（HeadCall slot 1），唯一 `IsoCell.getGridSquare(III)`（javap offset 47，之後存起來、`ifnull` 跳到 return）1:1 改道 `SpriteUpdateGuard.getGridSquare`（在既有 GameServer ClassPatch 上，命中 2）。不通過時回 null，原版什麼都不做。

console（拒絕時每 10 秒最多 20 行）：

```text
[MinidoracatJavaPatch][SpriteUpdateGuard] refused target=<x>,<y>,<z> connectionPlayers=<…> at=<各玩家位置> maxRange=20.0 refused=<n> suppressed=<n> anomalies=<n>.
```

**驗收**：上線後拆物件、修理、開車撞圍籬照常；出現 `refused` 行時看 `at=` 與 `target=` 的距離，正常操作被擋就放寬門檻。

**kill switch**：`-Dmdc.spriteUpdateRange=0|off`，需重啟。

**部署**：manifest 新增 `zombie/mdc/SpriteUpdateGuard.class`，`zombie/network/GameServer.class` 換新（連同 W76、W77：156 → 159 個 class）。

**守門與驗證**：
- SmokeCheck：上游指紋——`receiveUpdateItemSprite` 恰 1 個 `getGridSquare(III)`，存起來後 null 就跳過，方法內沒有讀 `UdpConnection.players`；手術後頭部 `aload_1; begin`、唯一 `getGridSquare` 同形改道，其餘指令與 frames 不變；helper 恰一次呼叫原版。
- `SpriteUpdateGuardTest`（`build.ps1` [9zt/10]，on、off 兩個 JVM，自驗 argv）：走 dist 內手術後的真 `receiveUpdateItemSprite`＋真 wire，真 `IsoCell` 的伺服器分支轉給記錄呼叫的 `ServerMap` 子類。照原版取格子：玩家身旁、每軸恰 20 格、樓層差 1、同機多人任一位在範圍內；不取格子並記 log：21 格、遠處、樓層差 2、同機多人都不在範圍內、連線上沒有玩家；off 照原版。

**退場**：官方在伺服器端驗證 `UpdateItemSprite` 的距離，或改成伺服器權威時撤刀。

---

<a id="2co"></a>
## 2co. 回收的殭屍物件清掉上一輪的網路擁有者（W80，server，預設 on）

**症狀**（2026-10-26 Quest 屍潮 E2E 調查）：生成的殭屍有一部分兩個客戶端都看不到，伺服器上卻在、而且有擁有者；改歸別的玩家時伺服器印 `moveZombie: There are no zombies in nz.zombies.`（[抑噪 #5](#1)），之後才出現。Quest horde-mp 迴圈（`--define loop=10`，原版伺服器、Quest 還沒有 Lua 修正；第 8 輪 A 被名單外的殭屍咬死而中止）32 隻裡生成時帶擁有者的 15 隻兩端都沒看到，`nil` 的 17 隻都看到，那行 12 次。正式服的這行被抑噪 #5 丟掉；唯一沒過濾的一輪是 42.21.0 更新後 JavaPatch 停用的那輪（9/28 20:53～9/29 00:05，約 47 人），22:23～00:00 有 1,353 行（每分鐘最多 91）。

**原版**（42.21.0 jar `e1a69eb7`）：
- 殭屍死後擁有者 X 留在物件上：`NetworkZombieManager.moveZombie` 的已死分支只在 `realState == OnGround` 時清擁有者。屍體建立時 `removeFromWorld` 把 onlineId 設成 −1，`getZombieAuth` 下一次就把牠從 X 的擁有清單拿掉：欄位是 X，X 的清單沒有牠。
- 物件 5 秒後經 `VirtualZombieManager.reuseZombie` 進回收池，`IsoZombie.resetForReuse()` 不碰擁有者；`createZombieOutsideWorld` 把牠交給下一隻殭屍（人口管理、Lua `addZombies*`、建築故事都走這裡）。
- 新殭屍出生時擁有者＝X，X 的清單卻沒有牠。`updateAuth` 選到 X 時 `moveZombie` 因 `getOwner() == to` 什麼都不做：伺服器不把牠送給任何客戶端、也沒有人模擬，所有人都看不到。改歸別人時才從 X 的清單移除失敗、印那行，加進新擁有者的清單後出現。
- 已死的才會帶：活著被 `removeFromWorld` 的殭屍會經 `moveZombie(z, null, null)` 的活著分支清掉擁有者。

**手術**：`VirtualZombieManager.reuseZombie` 唯一 `invokevirtual IsoZombie.resetForReuse()`（javap offset 119，前 `aload_1`、後 `aload_0; aload_1; addToReusable`）1:1 改道 `zombie/mdc/ZombieReuseOwner.resetForReuse(IsoZombie)`（receiver 前置；新 `VirtualZombieManager` ClassPatch，命中 1）。helper 先呼叫原方法（例外原樣上拋，跟原版一樣不進池），再 `setOwner(null)`、`setOwnerPlayer(null)`，接著呼叫原版 `NetworkZombieManager.recheck(X)`（持 `owns.lock`）把擁有者不是 X 的物件從 X 的擁有清單拿掉。出生時沒有擁有者，`updateAuth` 照原版規則指派並加進對方的清單。全 jar 只有這一個 `resetForReuse` 呼叫點；其他直接 `addToReusable` 的只有 `init`（新物件）與 `IsoCell.Dispose`（關世界時把還在 cell 的殭屍直接放進池，可能帶擁有者，但 `IngameState.exit` 隨後 `VirtualZombieManager.Reset()` 清空整個池，不會帶到下一局）。

**切點**：選進池時清，不選出池時清：出池在 `createZombieOutsideWorld` 的重用分支，是一長串逐欄位重設，沒有單一呼叫可同形改道；進池的 `resetForReuse` 就是原版「重用前重設」的地方。不改 `moveZombie` 的已死分支：擁有者要把死亡同步到倒地，提早清可能影響死亡動畫（推論）。為什麼還要 `recheck`：X 的清單在進池前通常已經沒有牠（`NetworkZombiePacker.postupdate` 每一輪對已進入遊戲的連線拿掉 onlineId −1 的殭屍），但死亡在網路封包處理時發生、同一輪接著同步存檔讓主迴圈停超過 5 秒時，同一輪的 `VirtualZombieManager.update` 就把牠回收，人口管理還可能在 `postupdate` 之前把牠拿去生成、配新 onlineId，X 的清單就一直留著牠。只清欄位的話，`updateAuth` 改歸 Y 時牠同時在 X 與 Y 的清單（兩個客戶端都拿到模擬權），歸回 X 時在 X 的清單出現兩次；擁有者欄位還在時，原版在這種時序下欄位與清單是一致的。`recheck` 讓欄位與 X 的清單在進池當下就一致。沒處理的同類情況（原版本來就這樣，本刀沒有更糟）：封包死亡時已經倒地（`realState == OnGround`）的，已死分支先清了欄位、X 的清單還留著，進池時已沒有舊擁有者可以 `recheck`；`recheck(X)` 也不碰別的連線的清單（例如 `clearTargetAuth` 改歸時留下的）。

**抑噪 #5 改計數**：這行仍不印，`LogFilter.log` 每遇到一次計數，本刀心跳帶出累計 `nzMissing`（與本刀開關無關）。上線後比對每小時的 `nzMissing` 與上面那輪的 1,353 行／97 分鐘，看剩下多少是其他來源。

console（第一次回收一行首次生效，之後每 30 分鐘一行，數值為開服以來累計）：

```text
[MinidoracatJavaPatch][ZombieReuseOwner] 首次生效（-Dmdc.zombieReuseOwner=0|off 不清擁有者）mode=on reused=<n> stale=<n> nzMissing=<n> anomalies=<n>
[MinidoracatJavaPatch][ZombieReuseOwner] beat mode=on reused=<n> stale=<n> nzMissing=<n> anomalies=<n>
```

`reused`＝進回收池的次數；`stale`＝其中還帶擁有者的次數（on 時都已清掉，off 時照原版留著）；`nzMissing`＝抑噪 #5 的累計。

**驗收**：上線後心跳 `stale` 隨殭屍死亡增加、`anomalies=0`；`nzMissing` 每小時明顯低於上面那輪；玩家不再回報「打不到、看不到的殭屍」。

**kill switch**：`-Dmdc.zombieReuseOwner=0|off` 不清擁有者（照樣計數與心跳），需重啟。

**部署**：manifest 新增 `zombie/mdc/ZombieReuseOwner.class` 與 patched `zombie/VirtualZombieManager.class`，`zombie/mdc/LogFilter.class`、`PatchInfo.class` 換新（159 → 161 個 class）。

**守門與驗證**：
- SmokeCheck：存在理由——原版 `resetForReuse` 與 `createZombieOutsideWorld` 都不呼叫 `setOwner`／`setOwnerPlayer`、不碰 `NetworkZombieComponent`；`moveZombie` 開頭是 `isDead` 分支、其第一個 `setOwner` 只在 `realState == OnGround` 時執行，全方法恰 3 個 `setOwner`；`IsoZombie.removeFromWorld` 只經 `moveZombie` 處理擁有者（任一項變紅＝官方可能已修，先重估撤刀）；掛點語境與全 jar 唯一呼叫點；手術後 `reuseZombie` 除了改道，文字與 frames 不變，`VirtualZombieManager` 其餘方法不變；helper 恰一次呼叫原方法且在任何 try 之外，之後 `setOwner(null)`、`setOwnerPlayer(null)`、`recheck` 各一次、依此順序，`recheck` 的引數是清欄位前存下的舊擁有者、在 try 內，catch 只有 `RuntimeException`。
- `ZombieReuseOwnerTest`（`build.ps1` [9zu/10]，on、off 兩個 JVM，自驗 argv）：dist 內手術後的真 `reuseZombie`＋helper 與 jar 內原版 `reuseZombie` 各自隔離載入，擁有者走真的 `NetworkZombieComponent`、擁有清單走真的 `NetworkZombieManager`；測試 JVM 跑不了真的 `resetForReuse`（要 SandboxOptions、動作群組與動畫），兩邊的呼叫都換成同一個同形替身。帶擁有者（連線＋玩家）進池：原版照舊（負對照）、on 清成 null、off 照舊；原方法恰一次、呼叫當下擁有者仍是上一輪的；X 的清單還留著這個物件、另有一個已歸 Y 卻還留在 X 清單的殘留項時，on 拿掉這兩個、X 名下其他殭屍留著（同原版 `recheck`），原版與 off 整份照舊（off 不呼叫 `recheck`）；只帶擁有玩家也清；沒有擁有者照原版；已在池裡照原版早退、不呼叫原方法；原方法丟例外時兩邊都原樣上拋、不進池、擁有者不動；計數與首次生效行帶 `nzMissing`。`LogFilterNoiseTest` 另驗抑噪 #5 每次 +1、其他被攔的 log 不計。
- MP 實機（2026-10-26，本機專用伺服器＋兩個客戶端；Quest horde-mp `--define loop=10`，Quest 用 `940096f`，還沒有 Lua 繞過）：每輪生成 4 隻、A 砍完、15 秒後下一輪。on（`quest-hzloop-w80on-1026a`）：`HZLOOP SUMMARY n=10 bad=0`，40 隻生成時擁有者都是 `nil`、兩端都看到、砍完沒有殘留。off（`quest-hzloop-w80off-1026a`，`-Dmdc.zombieReuseOwner=off`）：`bad=4`，第 7～10 輪的 16 隻生成時帶擁有者，其中 15 隻兩端都沒看到（另 1 隻已改歸另一位玩家才出現），擁有者是 `nil` 的 24 隻都看到。兩輪伺服器的例外與原版那輪相同（都是開服時 MOD 的雜訊）。

**退場**：官方在回收或出生時清擁有者（SmokeCheck 存在理由變紅）時撤刀。

**官方回報**：[report/2026-10-26-zombie-reuse-stale-owner-tis.md](report/2026-10-26-zombie-reuse-stale-owner-tis.md)（草稿，未送出）。

---

<a id="2cp"></a>
## 2cp. 尋路世界變動批次化閘門（W81，server，預設 1000 ms）

**症狀**（2026-10-26 正式服，W74 探針 `beat`，41–45 人）：開服 11 分鐘後 `PathfindNativeThread` 就 100% 忙，動物等路徑的平均時間一路升到 89–106 秒；前一場也從開服 40 分鐘一直塞到重啟，平均等 25–77 秒。飽和時每一輪都在重建：一分鐘 179 輪，`rb=179`（平均 316 ms）、`nr=179`（平均 19 ms），`rebuildPct` 88–93%。每輪約 335 ms、只處理 2 個請求，約 6 req/s；排進 `otherQ` 的需求約 21–26 req/s。請求全是動物（玩家、有目標與沒目標的殭屍都是 0），62–80% 失敗。

**原版機制**（見 [2ci](#2ci)）：native 的六個寫入 JNI（`updateChunk`／`removeChunk`／`updateSquare`／`addVehicle`／`removeVehicle`／`teleportVehicle`）都設同一個全域 dirty 旗標，下一次 `findPath` 一進門就釋放全部可見圖、重建所有載具 cluster，並讓高階區域快取失效。`updateThread` 每輪先套用所有世界任務再處理請求，所以 45 人時幾乎每輪都有載具在動、chunk 在載，每一輪的第一個請求都要付一次全域重建（約 300 ms），每輪只剩 2 個請求的額度。chunk 佇列另有每輪最多 10 個 `ChunkUpdateTask` 的上限（javap `updateThread` offset 0 `bipush 10`／`istore_1`，迴圈內 `instanceof ChunkUpdateTask` 時 `iinc 1 -1`）。

**手術**（在 W74 同一個 `PathfindNativeThread` ClassPatch 上，`updateThread` 的 8 個 `ConcurrentLinkedQueue.poll` 與唯一 `PathfindNative.update()` 改道目標由 `MdcPathfindProbe` 換成 `zombie/pathfind/nativeCode/MdcPathfindGate`，同形 1:1，`expectedHits` 仍是 9；閘門再呼叫 W74 探針的 `poll`／`update`，探針的帳照舊）：
- 閘門關閉的輪次：chunk、格子、載具三個佇列的 `poll` 回 null，任務留在佇列裡，FIFO 不變；`requestTaskQueue` 永遠直通，請求不延遲。佇列以物件身分比對（同 package 讀得到 protected 欄位）。
- 閘門開的輪次：第一次 poll chunk 佇列時，helper 照原版迴圈本體（offset 18–29：`execute()`、`taskReturnQueue.add`）把整個佇列排空再回 null，不受每輪 10 個的上限；格子、載具迴圈原版本來就排空，直通即可。否則每 T 才開一次、每次只吃 10 個，chunk 任務會無限積壓。這樣不需要沒有 runtime 開關的 `constChange`。
- `execute()` 丟例外時與原版相同：該任務已取出、不進 `taskReturnQueue`、例外原樣上拋（helper 沒有 catch），剩下的留在佇列；`update` 沒被呼叫，下一輪仍是開的，接著排空。
- `update()`：先結算這一輪，再以 `now − lastOpen ≥ T` 決定下一輪開不開（開就記 `lastOpen`），最後交給 W74 的 `update`（再呼叫原版 `PathfindNative.update()`）。開服第一輪開。
- 閘門關而佇列有任務、又沒有請求時，原版 `shouldWait()` 回 false，執行緒以 `Sync` 的 20 Hz 空轉（只有輪詢與空的 native `update`）；不改 `shouldWait`。
- 所有狀態只在尋路執行緒讀寫（單寫者）。

**估算**（R≈297 ms 重建、S≈19 ms 搜尋、`Sync` 週期 50 ms、每輪 2 個請求）：T=500 約 17 req/s、T=1000 約 28 req/s（目前約 6 req/s），需求 21–26 req/s，所以預設 T=1000。主執行緒不等尋路（[2ci](#2ci)），FPS 預期不變。

**開關**：`-Dmdc.pathfindGateMs=<T>`，需重啟。未設＝1000；`0`／`off`＝每一輪都開，兩個入口只委派 W74 探針，與原版逐指令等價（原版 10 個上限照舊）；0–5000 照用，其他值與無法解析落回 1000。與 `-Dmdc.pathfindProbe` 各自獨立：探針 off 時閘門照常（但沒有心跳），閘門 off 時探針照常。

console（第一輪一行；心跳併在 W74 每 60 秒一行的 `beat` 行尾）：

```text
[MinidoracatJavaPatch][PathfindGate] 首次生效（世界變動每 1000 ms 最多套用一次；-Dmdc.pathfindGateMs=<原始值>；0|off 每輪都套用＝原版）
[MinidoracatJavaPatch][PathfindProbe] beat sec=… （W74 欄位）… total=<n> gate=<T> opens=<開閘輪數> heldRounds=<有世界任務在排但閘門關著的輪數> drained=<開閘輪平均>/<最大> staleMsMax=<ms> chunkU=<ChunkUpdateTask 數> chunkR=<ChunkRemoveTask 數> refail=<n>
```

欄位說明：`drained` 是開閘輪次套用的世界任務數（chunk＋格子＋載具）。`staleMsMax` 是開閘時最舊積壓任務等了多久，以第一個被擋輪次的開頭近似它的入列時刻（最多少算一輪）；穩定時約為 T 到 T 加一輪。`chunkU`＋`chunkR` 等於 W74 的 `chunk`。`refail` 是同一個 mover 這次失敗的目標格（`floor` 後的 x、y、z）與它上一次失敗的目標格相同的次數，只在尋路執行緒記（mover → 目標格的 `IdentityHashMap`，超過 10,000 筆整表清空；多目標請求只看第一個目標），用來量動物失敗後立刻以同一目標重試的迴圈有多大，只觀測。T=0 時閘門欄位都是 0（純委派不計數）。

**行為差異**：尋路對世界的認知最多晚 T（加一輪）：剛關上的門、剛蓋好的牆、剛停下或開走的載具、新載入的 chunk，在這段時間內仍以舊狀態規劃路徑；mover 照路徑碰撞後由原版 `walkingOnTheSpot` 判失敗再重新尋路。只影響伺服器端尋路（伺服器自己模擬的 mover），正式服目前只有動物在用；client 不用伺服器的 native 世界重算路徑，不會因此 desync。三種任務在主執行緒 `init` 時就快照好資料，延後 `execute` 不會讀到已卸載的 chunk；native `updateChunk` 自己用 `loadId` 擋過期資料。

**驗收**（上線後看晚峰幾行 `beat`）：`rb` 次數降到每分鐘約 `opens`（≤ 60），`calls` 每分鐘上升、`waitMs` 下降，`fail` 比例不上升；`staleMsMax` 約 T 到 T 加一輪、不持續增長；`drained` 最大值不持續增長（積壓沒有越滾越大）；主執行緒 FPS 不變；`PathfindNativeThread` CPU 仍可能 100%（需求大於產能時本來就會吃滿），不算失敗。pfguard 的 `cluster_alloc_calls`、`merge_calls`、`guard_alloc` 速率跟著下降是預期結果，不是 allowlist 失效。

**部署**：manifest 新增 `zombie/pathfind/nativeCode/MdcPathfindGate.class`，`PathfindNativeThread.class`、`MdcPathfindProbe.class`、`MdcPathfindProbe$Hist.class`、`PatchInfo.class` 換新（159 → 160 個 class）。上線後確認 console 有 `PathfindGate` 的「首次生效」一行，`beat` 行尾出現 `gate=1000`。

**不處理**：`requestsPerUpdate`（每輪 2 個）、失敗請求負快取、動物失敗後重試的 AI 都不改；`refail` 的數字出來後再決定。

**守門與驗證**：
- SmokeCheck：原版前提——`updateThread` 恰 8 個 `ConcurrentLinkedQueue.poll`、1 個 `PathfindNative.update`（W74 已鎖）；offset 0 是 `bipush 10`／`istore_1`，chunk 迴圈恰 1 個 `instanceof ChunkUpdateTask` 與 `iinc 1 -1`；請求迴圈的 `iconst_2`／`istore_2` 仍在；`PathfindNativeThread$Sync` 的 `period` 仍是 `50000000L`；`shouldWait` 恰 4 個 `isEmpty`。手術後 `updateThread` 除了 9 個改道到 `MdcPathfindGate` 的呼叫，方法文字（含 frames）與原版相同；helper 契約：`poll` 不直接呼叫 `ConcurrentLinkedQueue.poll`、只經探針取任務，排空恰 1 個 `execute` 與 1 個 `add`、沒有 catch；`update` 恰 1 次探針 `update`、不直接呼叫 native。
- `MdcPathfindGateTest`（`build.ps1` [9zv/10]，T=1000＋探針 on（出貨組態）、T=0＋探針 on（原版負對照）、T=1000＋探針 off 三個 JVM，自驗 argv）：走 dist 內手術後的真 `updateThread`（native `update` 照 W74 丟 `UnsatisfiedLinkError`，閘門在那之前就結算）。連跑四輪：開服第一輪開，一次套完 16 個 chunk（含移除）與格子、載具，順序不變；第二輪關，世界任務留在佇列、請求照常進 `otherQ`；到期那輪仍關、結算後下一輪開；開閘一輪照 chunk→格子→載具各自 FIFO 套完積壓，`taskReturnQueue` 同順序；W74 計數、`worldRounds`、`rb`／`nr` 分組與心跳欄位正確。`decide` 的邊界：差 1 ns 不開、恰滿 T 開。三個情境（開閘 15 個 `ChunkUpdateTask`、閘門關、`execute` 丟例外）的結果與預期相同；T=0 時與原版逐輪相同（10 個上限、例外語意）。`refail`：同格才計、中間成功不重置、多目標第 2 次不計、負座標、滿 10,000 筆清空；探針 off 不計。mutant（測試內照 javap 抄的原版迴圈模型，先自驗模型與真 `updateThread` 相同）：開閘時仍受 10 個上限、閘門關時擋到請求佇列、排空時吞掉例外，三個都被抓到。

**退場**：TIS 改成局部失效（native 寫入 JNI 不再設全域旗標，或 `findPath` 不再整批重建可見圖與載具 cluster）時撤刀。PathFind native lib 換版時用 objdump 重驗六個寫入 JNI 仍設 `PolygonalMap2::instance+0x80`、`findPath` 開頭仍檢查並呼叫 `createVisibilityGraphs`。

---

<a id="3"></a>
## 3. 部署後驗證清單

1. **開機健檢**：console 無 `VerifyError`/`ClassFormatError`/`NoSuchMethodError`（有＝立刻 uninstall）。
2. **抑噪生效**：上表 7 種訊息不再出現（開機幾分鐘內原本必有 2/4/6）。
3. **未誤攔**（反向）：debug 模式下 ItemPickInfo 診斷訊息、SpriteConfig 其他名稱警告、anticheat `is not valid` 仍會輸出。
4. **行為觀察**：動物面板（admin cheat）壓力恢復約快一倍、槍聲增量約 1/3。（殭屍 `zombiesCulled` 觀察項隨 2a 一併移除。）
5. **安全屋驗證**（**2026-07-29 起 SafehouseClaimPacket 修復已停用**，本條僅在重新啟用該刀後適用）：在曾回報失敗的房屋重新申請，應先看到 repair log，隨後由原版規則成功建立；非房屋座標仍必須被 `building not found` 拒絕。
6. **容器刷新驗證**：在無 TownZone 的自訂地圖與 `haveConstruction=true` 的 vanilla Zone 各選一個已探索、已拿取且少於 `MaxItemsForLootRespawn` 的原生固定容器；等下一個正常週期後應可補貨。同區玩家製箱、搬動家具及有效安全屋內容器不得補貨；解除安全屋後只在再下一個週期恢復。
7. **登入量測驗證**：controlled Steam login 應出現三個 op 各一行；任何 unknown/duplicate/missing op、非十進位或負的 `elapsedNs`、玩家識別資料外洩都視為失敗。先觀察 log，不以本 patch 宣稱 busy 已修復。
8. **chunk unload 驗證**：以相近在線人數與移動速度比較 patch 前後 server FPS、黑邊回報與 thread dump；hot stack 不應再長時間停在 `Array.removeValue -> EntityBucket/EngineEntityManager`。若出現
   `VerifyError`／entity membership 異常，先停服執行 `uninstall.sh` 回退，不以單次低負載時段宣稱根治。
9. **效能第一波驗證**：(a) console 出現 `[MinidoracatJavaPatch][VehiclePrefilter]` 統計行，`rejected/(rejected+delegated)` 應 >0.9（低於此值＝預篩無效益，考慮回退）；(b) 開機健檢無 `ArrayIndexOutOfBoundsException`（含 `connectionAdded`＝512→256 界限分析被推翻，立即
   uninstall）；(c) 載具行為不變：上下車、駕駛、乘客、殭屍隔車不可見；(d) fps-dip-sampler
   新 dump 中載具主題（getIntersectPoint/getLocalPos/releaseVector3f/serverUpdate）佔比應從
   ~29% 顯著塌陷——這是第二波（P2/P3/P5）的立案量測；(e) `anomalies` 持續增長＝script null
   或幾何異常頻繁，需調查。
10. **受精蛋豁免退役驗證**（2026-08-08，2n 已退役——本項現在是**負向**驗證，確認舊 patch
   已徹底清除、世界清理回歸原版）：(a) 兩個退役 class 都不在磁碟上：`ls /home/pzserver/serverfiles/java/zombie/iso/IsoGridSquare.class`
   與 `.../zombie/mdc/FertilizedEggGuard.class` 皆應「No such file」。**只殘留改道版
   `IsoGridSquare.class` 而 helper 已刪＝chunk 載入路徑必爆 `NoClassDefFoundError`**，這是本項最重要的一條。（install.sh 的不明 loose class 巡檢已 fail-closed，會在安裝前擋下這種殘留。）(b) 新 `patch-manifest.txt` 行數必須與本次 build 的 `dist/manifest.txt` 完全一致（`grep -c . patch-manifest.txt` 對帳；42.20.4／W25 後為 **80** 筆——歷史數字 48/51/55/71/72
   皆為當時版本，勿拿舊數字驗新部署）；其中 `NetTimedActionGuard.class`、`NetTimedAction.class`、`NetTimedActionPacket.class` 各恰一筆，且 `grep -E 'IsoGridSquare|FertilizedEggGuard' patch-manifest.txt` 無輸出。(c) 開機健檢無 `VerifyError`／`NoSuchMethodError`／`LinkageError`（此路徑跑在
   `ServerChunkLoader` 執行緒上，出現即立刻 uninstall）。(d) log 不再出現 `[MinidoracatJavaPatch][EggGuard]` 任何一行（重啟後全新 log 起算）。(e) 行為回歸原版：地上的受精蛋與一般蛋一樣，過 24 遊戲小時並讓該 chunk 卸載後重回即消失。玩家端與伺服器端此時**行為一致**（退役正是為了消除這個 desync），可直接目視驗證。(f) 玩家引導：受精蛋要孵化請放**雞舍**（`IsoHutch`）——雞舍內的蛋不是
   `IsoWorldInventoryObject`，不經 `IsoGridSquare.load` 的清除路徑，本來就不受清單影響。
11. **W7 朝向暫存執行緒隔離驗證**（2s）：(a) 開機健檢無 `VerifyError`／`NoSuchMethodError`／`NoClassDefFoundError`——本刀改的是
   `IsoGameCharacter`（全遊戲最熱的 class 之一）且 helper 跑在 chunk loader 執行緒上，出現即立刻 uninstall。(b) **主驗證訊號＝例外歸零**：`grep -c 'Forward Direction cannot be zero' <DebugLog>`
   對照修前的每日 0–13 次。**注意分母**：修前 67 次裡有 66 次走的是 `IsoDirections.TEMP`
   那條**本刀不涵蓋**的獨立競態（`createRealZombieAlways`，主執行緒），所以正確的預期是「**stack 內含 `IsoAnimal.load`／`setForwardDirectionFromIsoDirection` 的那一類歸零**」，而不是總數歸零。只看總數會誤判成「patch 沒效」。(c) `blam/` 不再新增 Forward Direction 類型的目錄：`grep -l 'Forward Direction' /home/pzserver/Zomboid/Saves/Multiplayer/pzserver/blam/*/*_error.txt`
   應只剩 `<chunk-A>_error.txt` 這一筆歷史紀錄。(d) 行為不變：角色／殭屍／動物轉向正常，動物出雞舍後朝向不亂跳。(e) **還原前置**：本刀確認生效後才把 `blam/<chunk-A>.bin` 複製回 `map/<chunk-A>.bin`（必須在 server 進程停止的窗口內，否則記憶體版本會在下次世界存檔打回去）。
12. **W8 chunk 寫入閘驗證**（2t）：(a) 開機健檢無 linkage 錯誤（改道方法跑在存檔與 chunk 出貨路徑上，出現即立刻 uninstall）。(b) **心跳**：`grep 'ChunkWriteGuard' <DebugLog>` 應出現 `passed=N flagged=0` 週期行（每 2048 次通過印一行）——證明閘門真的在驗，而非默默 passthrough。(c) **BLOCKED 事件**（enforce 模式）＝雙重訊號：該 chunk 逃過一次抹除（止血生效），且 log 內的 stack trace 直接指認寫入路徑（蒐證到手）。出現時把前 10 筆的完整 stack
   與 `blamguard/` 傾印檔一起歸檔分析——這就是根因獵捕的決勝證據。**判讀注意**：observe 模式印的是 `FLAGGED` 且照常寫入＝沒有保護，不可誤讀成已擋下；若 BLOCKED 發生在 unload/quit 的最終存檔，該 chunk 回退到上次成功落盤版本（見 2t 重試語意），玩家可能回報「東西回到半小時前」——那是止血的代價，不是新 bug。(d) `flagged` 持續為 0 且 blam/ 不再新增 CRC 類目錄 = 缺陷可能與 W4-1 或特定時序相關，繼續觀察；`flagged>0` 且 blam/ 不再新增 = 閘門正在攔截現行損毀。(e) **anomalies 增長**＝守衛遇到非預期 buffer 狀態走了 fail-open，需調查。
13. **W9 存檔管線隔離驗證**（2u）：(a) 開機健檢無 linkage 錯誤（改道方法跑在主迴圈與存檔執行緒上，出現即立刻 uninstall）。(b) **首次生效橫幅**：`grep 'ChunkSaveIsolation' <DebugLog>` 應出現「首次生效」一行（第一次 chunk 存檔序列化時印）——證明改道真的被走到。(c) **主驗證訊號＝W8 flagged 歸零**：`ChunkWriteGuard` 心跳應變成 `passed=N flagged=0`
   長期維持（修前基線：首晚 2.5 小時 8 筆）。**尤其盯重啟窗口**——關機存檔（QueuedSaveAll on shutdown hook）正是定罪的競態場景，連續數次重啟 flagged 仍為 0
   才算根治確認。flagged>0＝機制另有分支，取該筆 BLOCKED stack 續查。(d) blam/ 不再新增任何 CRC 類目錄（`SANITY CHECK FAIL` 歸零）。(e) 行為不變：chunk 正常存讀、玩家離開區域後重回內容不回退、客戶端 chunk 下載正常（發送路徑一概未動）。(f) kill switch 演練過（build 步驟 9d 以獨立 JVM 真的執行 off 分支＋步驟 7 的
   bytecode 保真閘）；線上如需停用：JAVA_OPTS 加 `-Dmdc.chunkSaveIsolation=0` 後重啟。
14. **抑噪第 8 項（toxic log）驗證**（2v）：(a) 開機健檢無 `VerifyError`／`NoSuchMethodError`／`NoClassDefFoundError`——改道方法跑在
   `sendToxicBuilding` callsite 上，出現即立刻 uninstall。(b) **先確認驗的是新版**，再看訊號。一律用**帶時間戳的 per-session** log，不要用
   `server-console.txt`：後者在本伺服器實測是每次重啟覆寫（`server patch` 指紋恰 1 次、`LOADING ASSETS: START` 恰 1 次、toxic 行數與 per-session DebugLog 完全相同），但那是未文件化的行為——LinuxGSM 或 PZ 改成 append 就會讓總量計數靜默給出跨 session 的錯答案。
   ```bash
   LOG=$(ls -t /home/pzserver/Zomboid/Logs/*DebugLog-server.txt | head -1)
   grep 'server patch' "$LOG"                    # 指紋必須是新版，否則下面的數字沒有意義
   grep -c 'Send Toxic Building' "$LOG"          # 主驗證訊號：應為 0
   ```
   修前基線：`2026-08-17_00-12` session（舊版 `5f5f466`）1.25 小時內 **18,117 行**（≈14,494/h）；抑噪前 15.41 小時平均 **10,654/h**。(c) 同期其餘 Multiplayer 頻道訊息（`Receive`／`Network`／`Packets` 等）必須照常輸出——若一起消失，代表攔錯了（`logType` 只比對 `Send Toxic Building at [ ` 前綴）。(d) 廣播封包正常：玩家在毒氣區域仍被扣血、生命值介面正確更新，只是 server 端 log 安靜。(e) 行為不變：`GameServer.sendToxicBuilding` 的 `doPacket`／`putInt`×2／`putBoolean`／`send`
   與廣播迴圈完全保留（已由 SmokeCheck 逐項與 vanilla 對數，含真指令總數）。

15. **食材重量記憶化驗證**（2w）：(a) 開機健檢無 linkage 錯誤（改道方法跑在 `getExtraItemsWeight` 熱路徑上，出現即立刻 uninstall）。(b) **首次生效橫幅**：對同一個 per-session log
   `grep 'ItemWeightMemo' "$LOG"` 應出現「首次生效 mode=observe」一行（第一次呼叫 `getExtraItemsWeight` 時印）——證明改道真的被走到。(c) **observe 模式判讀**（預設）：`hits` 已與 `on` 的實際行為對齊——只計「通得過五道門」的型別，不再是單純的型別重複率（第二輪 review 抓到舊語意會讓命中率灌水）：
      - `attempts` = 所有呼叫次數（取樣與週期 log 的時鐘，不受 cacheability 偏置）
      - `hits` = 重複且可快取的呼叫數 ＝ **啟用 on 之後真正會命中的次數**；`hits/attempts` 才是「on 能省下的建構比例」，`hits/(hits+misses)` 是可快取型別內的命中率
      - `misses` = 首見且可快取的型別數（開局成長快，型別集合穩定後放緩）
      - `uncacheable` = null 或被五道門擋下的呼叫。這些在 on 模式下**每次仍會重新建構**，故不計入命中率；佔比高就代表這把刀的天花板低
      - `vanillaNsAvg` = 原版單次建構耗時。**單看它沒有意義，必須乘上呼叫速率**（由相鄰週期行的 `Δattempts / Δt` 求得）才是 on 能省下的量級——2026-08-17 就是漏了這一項才把一把 0.11% 的刀誤判為主要優化機會
      - `anomalies` ≠ 0 或 `overflow` ≠ 0 ＝ 異常，需調查
      - `types`（observe 模式）＝ SEEN 的型別數，等於 `misses`（只有首見且可快取才寫入）(d) 行為不變：背包容量計算正常、玩家負重值正確、背包滿時拒絕插入照常工作。(e) **`on` 模式已實測否決，不再排程啟用**（2026-08-17 定案，見 2w 的「實測結論」段）：命令列不得指定 `-Dmdc.itemWeightMemo=on`，產生的 log 應恆為 `mode=observe`。14 小時／4 session 實測：命中率 99.997%、`uncacheable=0`、型別集合 25–54，看似漂亮，但呼叫速率僅 **328–732 calls/s**、單次建構約 **2.1 µs** ⇒ 收益上限 ≈ 1.09 ms/s
   ≈ 主迴圈 **0.11%** ≈ 0.011 fps。而 `on` 的代價是全域 RNG 序列位移＋**首次真正執行共用實例路徑**（observe 不走 memo 命中分支，故既有 `anomalies=0` 完全沒演練過共用實例）。**風險與 0.11% 不成比例，維持 observe。**
   ⚠️ 本項不是「等條件達成再開」——條件已量測且**未達標**。若日後要重啟評估，必須先有新的呼叫速率量測（例如遊戲更新改了 `Moodle.Update` 的走訪方式），單憑命中率不足以翻案；且 `on` 期**無法自帶對照組**（`vanillaNs` 只在 miss 走 factory 時累加，on 模式 miss ≈ 型別數且須撞上 `(attempts & TIMING_MASK)==0` 才取樣 ⇒ `vanillaSamples` 幾乎必為 0），對照基準只能用 observe 期歷史值 2.1 µs 比 `on` 期 `memoNsAvg`。下次重建若確認仍無收益，可考慮整刀退役（redirect＋helper 一併移除）。

16. **PZ 更新**（順序不可調換）：
   1. **更新前先 `uninstall.sh`**——loose class 不在 Steam depot 內，`app_update` 只換 jar
      **不會刪掉它們**，殘留的舊 patched class 仍會覆蓋新 jar。同源閘只擋重新安裝，擋不住殘留。本伺服器的 update／monitor cron 是全自動的，**沒有人工介入視窗**，得知新版就要立刻執行。
   2. 重拉 jar → `build.ps1`。
   3. **命中數守門通過 ≠ 座標仍有效**——守門只數數量、不驗語境。常數手術必須另外用 `javap`
      確認該常數的前後指令（見 2b 的 42.20 實例：`respondToSound` 內仍剛好有一個 `20.0f`，舊座標會通過守門卻改到逃跑距離）。redirect 手術則確認 owner／method 未被搬家（見第 1 節 42.20 實例：consistency log 搬到 `INetworkPacket.logInconsistentPacket`）。
   4. 逐項語境驗證通過後才重新部署，並回到本清單第 1 項重跑開機健檢。
