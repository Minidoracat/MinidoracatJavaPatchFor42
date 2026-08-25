# W16／W17 動物持久化守衛 — 設計 v1（已建置，待部署）

> 立案來源：MinidoracatServerAnalyze 兩份調查（證據正典，本檔只帶結論）：
> - `MinidoracatServerAnalyze/reports/incidents/2026-08-24-Player-H-雞舍雞消失.md`
> - `MinidoracatServerAnalyze/reports/incidents/2026-08-25-全服放養動物流失-公牛兔群與鹿瞬移.md`
>
> 編號說明：W15 已被主迴圈凍結看門狗（patches.md 2ac）使用，本案取 **W16（卸載接手守衛）／W17（hutch 載入守衛）**。
>
> **實作正典**：`docs/patches.md` 2ad／2ae。下文原始設計經 42.20.3 javap 重驗後，
> O1b/O2/O4 掛點有實質修正；本檔已同步最終決策，但若再衝突一律以 patches.md＋程式碼為準。

## 0. 一句話

B42 世界動物（放養／出籠層）在 server chunk unload 的接手鏈上**靜默丟失**：正式服 39 小時全服母雞 −40%（206→123）、火雞 −65%（20→7）；單點案例三起（Player-H 雞舍全滅、Player-A 公牛＋15 雞＋2 小雞同窗消失、同牧區兔群 16→3）。**無 crash 的正常日照丟**（8/23 06:09–13:13 窗口全輪次正常結尾）、**W13/W14 部署前就在丟**——100% vanilla 缺陷。W16 先 observe 量化丟失環節，再依數據上 enforce；W17 靜態已定罪、直接帶 enforce。

## 1. vanilla 機制（42.20.3 反編譯，實作前一律 javap 重驗）

### 1.1 兩個持久化域

| 域 | 內容 | 寫入時機 |
|---|---|---|
| map chunk（`map/<x>/<y>.bin`） | hutch 內動物（`IsoHutch.save` 序列化 `animalInside`＋`NestBox.animal`，IsoHutch.java:888-923／1179-1191） | chunk unload＋world save |
| apop（`apop/apop_<cx>_<cy>.bin`） | 世界動物：虛擬群體（`AnimalChunk.animals` 常駐）＋實體動物（**只在 world save 瞬間**由 `saveRealAnimals` 掃 `IsoCell.objectList` 包成一次性 wrapper 寫入，AnimalManagerWorker.java:60-85、AnimalManagerMain.java:44-53） | world save（`SaveWorldEveryMinutes=60`）＋關機 |

`map_animals.bin` 是 AnimalZone spawn 中繼資料，**不是活體**（IsoMetaGrid.java:1927-1946）。

### 1.2 卸載接手鏈（設計上存在，但在漏）

`IsoChunk.removeFromWorld()`（IsoChunk.java:3178-3229）順序：

```
:3180 AnimalPopulationManager.removeChunkFromWorld(chunk)
        └ 掃 chunk 全 square 的 movingObjects 中的 IsoAnimal
          → animal.unloaded() → n_addAnimal(animal)          (:59-86)
            └ 包成 VirtualAnimal（保留 virtualId/migrationGroup）
              → AnimalManagerMain.addAnimal（同步直呼，非佇列）
              → AnimalManagerWorker.addAnimal                (:197-232)
                  寫入 AnimalChunk.animals ＋ cell.dataChanged=true
:3216-3229 清場迴圈：mov.get(a).removeFromWorld()  ← 接手之後才丟實體
```

**已鎖定的靜默失敗點**（全部無 log）：

| # | 位置 | 失敗形態 |
|---|---|---|
| S1 | `AnimalManagerWorker.addAnimal` 開頭 `getCellFromSquarePos(...)==null` → 直接 return | 動物座標異常／越界 ⇒ 整隻靜默丟棄 |
| S2 | `removeChunkFromWorld` 的掃描集合 | 動物不在該 chunk 任何 loaded square 的 movingObjects（square null／集合被前置步驟變動）⇒ 掃不到 |
| S3 | `AnimalManagerWorker.addAnimal` 防重分支 | 家畜 `virtualId` 恆 0.0 ⇒ 同 AnimalChunk 全部家畜合併進同一 id=0 的 VirtualAnimal；`findAnimalById` 誤判時 `remove(j--)` 丟棄（有 `DebugType.Animal.error` 但 debug channel 未必開） |
| S4 | `AnimalManagerWorker.removeFromWorld(IsoAnimal)`（:234-244） | **不做接手、只標 lastCellSavedTo 的 cell dirty** ⇒ 主動加速下次 save 把動物從 apop 抹掉 |

輔助事實：devirtualize（`AnimalManagerMain.fromWorker` :70-97）對 id=0 且無 migrationGroup 的動物 `setForceX/Y` 到 VirtualAnimal 座標——實體化位置可能偏離玩家預期（另案：野生鹿「瞬移」體感）。

### 1.3 W17 的獨立缺陷（靜態已定罪）

`IsoHutch.load`（:880-883）逐隻 `addAnimalInside(animal, false)` **忽略 boolean 回傳**；`addAnimalInside`（:765-802）槽位碰撞 `Rand.Next(0, getMaxAnimals())` 重試 100 次失敗即回 false——該動物不進任何容器、隨 GC 消失。觸發情境＝**接近滿舍**（vanilla 雞舍 `maxAnimals=20`，HutchDefinitions.lua:10）；兔子爆量案例正對此形。codex-rescue 與 review-lane-grok 雙 lane 獨立確認。

## 2. W16 設計：動物卸載接手守衛（observe → enforce）

### 2.1 階段一 observe（純觀測，先部署）

掛點（機制沿用既有先例：headCall＝W4-1/W15 同型；redirect＝主力手術）：

| # | 掛點 | 機制 | 觀測 |
|---|---|---|---|
| O1/O1a | APM.removeChunkFromWorld 頭部／n_unloadChunk | HeadCall＋redirect | 開 per-wave 帳；n_unload 成功後開始 scanNs |
| O1c/O1d/O1b | unloaded／n_addAnimal／尾部 TIntHashSet.remove | redirect | unloaded 前 scanSeen、n_add 成功後 handedOff、尾部完成 scan |
| O2/O2b/O2c | Worker.addAnimal 的 cell／chunk／兩個 remove | redirect | S1、S1b、S3 實際丟棄 |
| O3/O3b | IsoChunk 清場兩處 removeFromWorld／唯一 RETURN | redirect＋TailCall | per-wave cleared；出口分類 s2Missed/shortfall/queue/abort |
| O4/O4b | Main getObjectList／Worker save cell | redirect | world-save 分母＋save null |
| 來源帳 | APM.virtualize／AnimalZones.spawn／Worker.move | redirect | virtualized/zoneAdds/movedAdds；與 unload 組完整 sourceGap |

Heartbeat（每 256 unload-end 或 world save-start）：
`completed/aborted/unpaired/skipped/scanSeen/handedOff/droppedAtClear/s2Missed/
clearShortfall/queueFailures/attempts/virtualized/zoneAdds/movedAdds/sourceGap/cellNullAdd/
chunkNullAdd/duplicateRemoved/cellNullSave/lastSaveReal/scanAvgUs/scanMaxUs/anomalies/mode`

kill switch：`0` off／`2` observe（預設）／`1` 階段二保留值（本版仍 observe-alias）。

### 2.2 階段二 enforce（依 observe 數據選刀，不猜著修）

| observe 訊號 | 對應刀 |
|---|---|
| `cellNullAdd > 0` | S1：null cell 座標 clamp／最近合法 cell 重試 |
| `chunkNullAdd > 0` | S1b：cell 已命中卻取不到 chunk，先查 loaded/fileLoaded 狀態；不可與 S1 混成同一刀 |
| **完整 wave** 的 `s2Missed > 0` | S2：clear 看見、scan 沒看見；`droppedAtClear > 0` 本身是健康常態。補接手不得呼 `virtualizeAnimal`（內含 delete、會變動清場迭代集合），需走只包 VirtualAnimal 的同套件路徑 |
| `queueFailures > 0`／`abortedWaves > 0` | scan 有看見，但 unloaded/n_add/尾部結算未正常完成；先依 stack／sourceGap 分型，不得當 S2 |
| `duplicateRemoved > 0` | S3 合併誤殺已直接發生；依 animalID／virtualId 明細設計，不預先改合併規則 |
| 全綠仍丟 | S4 outcome、devirtualize `fromWorker` 或 apop 載入側；開下一輪 observe |

### 2.3 與 W15（凍結看門狗）的互補

- W15 假說 1「主迴圈在跑極貴的動物工作」：W16 的 O1 計時直接量化「每次 unload 波接手 N 隻、耗時 X ms」；凍結事件時間戳可與 W16 事件對齊。
- W15 凍結快照若 stack 停在 `removeChunkFromWorld`／`addAnimal`——兩案同一份證據裁決。
- 零掛點重疊（W15 在 `ServerMap.preupdate`；W16 全在動物鏈），property、SmokeCheck、helper 均獨立。

## 3. W17 設計：hutch 載入回傳檢查（直接帶 enforce）

- 掛點：`IsoHutch.load` 唯一雙參 `addAnimalInside(IsoAnimal,false)`；SmokeCheck 精確鎖
  `ALOAD0 → ALOAD7 → ICONST0 → call → POP`，TIS 開始消費回傳或改 sendEvent 即建置紅。
- helper 先委派原方法；false 時 duplicate precedence 不救，零 Rand 兩階段選槽：
  先找 animal/dead-body 都空的 clean slot，再用 vanilla `map.get(key)==null` 判準 fallback
  （含 key→null）。enforce 補 map/backlink/preferred/hutchPosition/itemID/tryRemove 六步；
  observe 只記不救；真滿 CRITICAL、有聲但不創造第 21 容量。
- kill switch：`-Dmdc.hutchLoadGuard=0` off／`1` enforce（預設）／`2` observe。
- client worldVersion≥212 直接 skip 動物 blob且 loose class 只部署 server，無 desync 面。

## 4. 實作決策（已裁決；42.20.3 javap）

1. **package-private**：主 helper `zombie.mdc.AnimalPersistGuard`；薄 probe
   `zombie.characters.animals.MdcAnimalPersistProbe` 同套件委派 n_unload/n_add/Main.add/
   Worker.add/cell/chunk。reflection/MethodHandles 不採用。
2. **來源帳**：jar-wide census 釘 APM.n_add 只來自 remove/virtualize、Main.add 只來自
   n_add/zone、Worker.add 只來自 Main/move。heartbeat `sourceGap` 必須 0。
3. **per-wave 出口**：新增純線性 `TailCall`（唯一 RETURN 前 `aload0; invokestatic`）在
   IsoChunk 真出口結帳；完整 wave 才分類 s2Missed/clearShortfall/queueFailures，abort/unpaired
   獨立計數。O1 scanNs 從 n_unload 成功後到尾部 remove 前，不含 native unload/heartbeat。
4. **W17 存取**：相關 field/setter 全 public；force-put 補 preferred/hutchPosition 同 key。
5. O3 兩處可安全 1:1 redirect；helper 熱路徑零配置、零 log。S3 兩個
   ArrayList.remove(int) 同形 redirect 直接計實際丟棄。

## 5. SmokeCheck（已落地）

- vanilla 完整順序、逐方法恰 N、三層 jar-wide source census＋分佈、S4 零 addAnimal、
  IsoChunk 唯一 RETURN、W17 `ALOAD0/ALOAD7/ICONST0/call/POP` 與原成功路徑 105 真指令。
- 手術後 redirect 真指令數不變；HeadCall/TailCall 各 +2；原呼叫歸零、class-wide 差額吻合。
- helper 各委派恰 1；O3 零 NEW/DebugLog；W17 全 class 零 Rand、forceInto 六步各恰 1。

## 6. 測試（獨立 JVM，MODE=static final）

- W16 observe／mode1 observe-alias／off：正常、S2 正差、queue failure、unpaired、aborted、
  sourceGap=0、S1/S1b/S3/save 正負 passthrough、O3 過濾、off 純委派。
- W17 enforce/observe/off：ZeroRandom 確定性 false、clean/dead-body/null-value 選槽、
  duplicate、20 隻全存活、第21隻 CRITICAL、六步狀態。
- 變異保證：拿掉 force-put map put 或 O3 instanceof，對應測試立即紅。

## 7. 部署與驗收

1. W16-observe＋W17 同一次 build 出貨；`dist/manifest.txt` 共 **70 class**（40 patched＋
   30 runtime helper），install.sh 三閘照常。
2. W16 部署後跑滿 24–48h：帶回 heartbeat
   `completed/aborted/unpaired/scanSeen/handedOff/s2Missed/queueFailures/sourceGap/
   cellNullAdd/chunkNullAdd/duplicateRemoved/cellNullSave`，與每日 apop 基線對帳後才選 enforce。
3. W17 驗收：接近滿舍載入出現 force log；CRITICAL 只允許真滿。長期 hen/turkeyhen 日流失
   應歸零或收斂到可解釋的屠宰／死亡。
4. 正典已同步 `patches.md` 2ad/2ae 與 AGENTS.md；部署後再整理 TIS 官方回報
   （unload 丟棄＋hutch load 回傳）及實測數據。

## 8. 明確不做（語意邊界）

- 不改 wire schema、不動 client、不碰 `IsoHutch.save`／`AnimalCell.save` 序列化格式。
- 不在 W16 裡處理鹿瞬移（devirtualize setForce 體感）——那是同步/體感問題，先跑 W13/W14/相關 observe 對照（Analyze repo 8/25 報告 §證據三），有數據再立案。
- 不做「阻止 chunk unload」（記憶體代價不可控）。
- crash 元兇（PathfindNativeThread native heap corruption）不歸本案——獨立 P0，native 層 bytecode 碰不到。