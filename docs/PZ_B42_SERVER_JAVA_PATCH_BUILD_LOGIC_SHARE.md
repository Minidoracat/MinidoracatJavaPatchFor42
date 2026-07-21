# Project Zomboid B42 專用伺服器 Java Patch 建置邏輯

## 文件範圍

本文件說明一組 Project Zomboid 專用伺服器 Java Patch 的設計與重建邏輯，不包含伺服器憑證、私人路徑、玩家資料、Workshop ID 或預先建置的遊戲二進位檔案。

這些 Patch 是依照 Project Zomboid Build 42.19 設計。遊戲每次更新後，都必須重新審核與建置。

## Loose Class Override 架構

專用伺服器的 classpath 會先載入 loose `java/` 目錄，再載入 `java/projectzomboid.jar`：

```text
java/;java/projectzomboid.jar
```

因此，修改後的 class 必須放在相同的 package 路徑：

```text
<SERVER_ROOT>/java/zombie/<package>/<Class>.class
```

不需要修改 `projectzomboid.jar` 本身。伺服器下次完整啟動時，JVM 會優先載入 loose class，蓋過 JAR 內具有相同完整類別名稱的 class。

停服後移除 loose class，再完整啟動伺服器，即可恢復使用 JAR 內的原版 class。

## 功能性 Patch

### `zombie/core/Transaction.class`

- 目標：原生 MP 物品轉移與地板物品拾取使用的 transaction duration 計算。
- 修改：將唯一的 `20.0f` duration 常數改為 `14.0f`。
- 結果：轉移時間變成原版的 70%，實際速度約為原版的 `1.428571` 倍。
- 伺服器仍具有最終權威；transaction 接受封包會將伺服器決定的 duration 傳給客戶端。
- 重建方式：對 constant pool 做有防護的位元組替換。必須確認只有一個符合預期的 float entry，並驗證輸出的精確 binary delta。

### `zombie/network/PacketsCache.class`

- 目標：`isLimitExceeded()`。
- 修改：原本使用 `ServerOptions.maxPacketsPerSecond` 進行比較，改成固定使用整數 `32767`。
- 保留原本的比較 branch 與 operand stack 結構。
- 重建方式：將讀取 option 的指令序列改為 `pop; sipush 32767; nop...`，保留後續原有的 `if_icmple`。

### `zombie/popman/ZombieCountOptimiser.class`

- 目標：`startCount()`。
- 修改：每次都將 `zombieCountForDelete` 設為 0。
- 結果：`incrementZombie()` 的一般自動淘汰路徑不會把殭屍排入刪除佇列，因為計數 gate 會一直維持在非正數。
- 這代表停用此 `ZombieCountOptimiser` 的一般 MP 自動淘汰路徑，不代表引擎其他位置不存在任何獨立的生成、清理或效能限制。
- 目標 method body：

```text
iconst_0
putstatic zombieCountForDelete
nop ...
return
```

- 保留原始 method code length，並將 `return` 放在原本最後一個 bytecode offset。
- 不能在前面提早 `return` 後留下無法抵達的 NOP 區段，否則可能因既有 `StackMapTable` frame 產生 `VerifyError`。
- 重建方式：解析 class 結構，確認目標 class、field reference、method descriptor 與 Code attribute，再只重寫該 method 的 bytecode。

### `zombie/characters/animals/IsoAnimal.class`

- 目標：修改動物 stress 的 method。
- 修改：要求增加的 stress 大於 0 時直接 return。
- 0 或負值仍依照原版流程執行。
- 結果：stress 可以維持或下降，但不能透過此 method 增加。
- 重建方式：在 method 開頭加入或替換 float comparison 與正值 early return，同時保留原始 continuation target 與 verifier frames。

### `zombie/network/fields/hit/Zombie.class`

- 目標：`process()`。
- 修改：先取得 `getZombie()` 並保存結果；若結果為 null，在套用其他殭屍狀態欄位前直接 return。
- 結果：過期或無法解析的 hit-field reference 不會在後續 setter 造成 null dereference。
- 重建方式：重寫 method 開頭的 instruction block、增加所需 local variable、branch 到既有的最終 return，並更新 `max_locals`、Code attribute 長度及 `StackMapTable`。

### `zombie/network/fields/hit/Fall.class`

- 目標：`process(...)`。
- 修改：在 method 開頭檢查傳入的角色物件；若為 null，直接 branch 到最終 return。
- 重建方式：插入四個 byte 的 `aload/ifnull` guard，接著更新 Code attribute 長度、line/local-variable offset 與相關 `StackMapTable` frame offset。

## Log 抑制 Patch

這些 Patch 只移除指定的 log call。除非另外註明，否則不改變 control flow、return value 或遊戲狀態。

### `zombie/network/PacketTypes$PacketType.class`

- 目標：`onServerPacket()` 的 inconsistent-packet branch。
- 只移除回報 inconsistent packet 的 WARN log。
- 保留 `packet.sync(...)`、return path，以及拒絕 inconsistent packet 的原始行為。
- 重建方式：將 stack-balanced 的警告字串建立與呼叫區段替換為相同長度的 NOP。

### `zombie/characters/action/ActionStateContainer.class`

- 移除兩個 unsupported child-state transition target 警告。
- 保留 action-state transition 的判定與流程。
- 重建方式：精確比對 opcode pattern，再替換為相同長度的 NOP。

### `zombie/core/skinnedmodel/advancedanimation/AnimationSet.class`

- 移除 `GetState()` 找不到 `AnimState` 時的警告。
- 保留原版建立並回傳空 `AnimState` 的 fallback。
- 重建方式：精確比對 opcode pattern，再替換為相同長度的 NOP。

### `zombie/inventory/ItemPickInfo.class`

- 移除找不到 container ID 時的 log call。
- 保留 room、tile 與 zone 等其他診斷訊息。
- 重建方式：精確比對 opcode pattern，再替換為相同長度的 NOP。

### `zombie/popman/NetworkZombieManager.class`

- 目標：`moveZombie(...)`。
- 只移除 `There are no zombies in nz.zombies` log call。
- 保留殭屍 ownership、list 更新、packet 行為與 return path。
- 重建方式：將 `ldc_w <message>; invokestatic DebugLog.log` 指令序列替換為六個 NOP byte。

### `zombie/core/skinnedmodel/model/SkinningBoneHierarchy.class`

- 移除 unresolved `SkeletonBone` 的 ragdoll 警告。
- 保留 fallback 至 `SkeletonBone.None`，以及原本的 hierarchy building loop。
- 重建方式：移除 stack-balanced 的 warning call sequence，同時保留 loop branch target。

### `zombie/entity/components/spriteconfig/SpriteConfig.class`

- 目標：`initObjectInfo()` 產生的 invalid SpriteConfig 警告。
- 只抑制以下名稱：

```text
MetalBigWireFence
WoodFloorLvl3
Wooden_Windows
```

- 其他名稱仍會輸出警告，包含未知或 null 名稱。
- 保留 invalid-object detection 與 `resetObjectInfo()`。
- 重建方式：使用 ASM 重寫 `initObjectInfo()`，並加入 private static 名稱過濾 helper；由 ASM 重新計算 method frames 與 maximum stack sizes。

## 建置流程

1. 確認專用伺服器已停止。
2. 從該伺服器自己的 `projectzomboid.jar` 讀取每個原版目標 class。
3. 驗證預期的 class name、method descriptor、field 與 bytecode pattern。
4. pattern 出現零次、多次或 class 結構不相容時，必須拒絕建置，不能猜測 offset。
5. 只套用完成需求所需的最小 bytecode 修改。
6. 將結果寫入相同 package 路徑的 loose class。
7. 驗證輸入與輸出 hash，並在伺服器目錄外保存被取代 loose class 的備份。
8. 使用 `javap -c -p`，以 loose `java/` 優先於 JAR 的 classpath 反組譯並檢查目標 method。
9. 使用伺服器附帶的 JRE 與 `-Xverify:all` 載入 class；出現 `VerifyError` 或 `ClassFormatError` 時不得部署。
10. 完整重啟專用伺服器，再進行各 Patch 對應的 runtime regression test。

## 更新規則

Project Zomboid 更新後，不可直接沿用舊 loose class。必須重新從新版 JAR 取得每個目標 class、比較 method 結構與行為、重新建置，並再次完成靜態與 runtime 驗證。

