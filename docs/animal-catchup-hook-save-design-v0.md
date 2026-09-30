# 動物離線補算不足與掛鉤屠體存檔掉鉤 — 評估 v0（已實作為 W49／W50，未部署）

> 立案來源：internal-analysis `reports/incidents/2026-09-30-Player-K-牛懷孕天數停滯與不明豬隻.md`（私有，含真名與座標；本檔只帶結論與方法）。
>
> 範圍：根因確認、量化、修法比較、風險與驗證計畫。評估期間正式服全程唯讀：沒有部署 loose class、沒有重啟、沒有改 ini 或沙盒。
>
> 對象版本：PZ 42.21.0（jar `e1a69eb7`）、正式服 JavaPatch `0b583bf`（manifest `595afbd9`）。本機 `dist/manifest.txt` 與正式服 manifest 的 sha256 相同；另在正式服逐一核對 8 個相關 loose class 的 sha256，並確認 `AnimalChunk`、`VirtualAnimal`、`AnimalPopulationManager`、`DesignationZone`、`IsoButcherHook` 沒有 loose 版本（走 jar）。因此下文用本機 `work/projectzomboid.jar` 與 `dist/java` 做的 javap 比對，就是正式服實際執行的 bytecode。
>
> 審查：初稿到第三版各經一輪 critic 對抗審查（皆 REVISE），第四版 ACCEPT。歷輪修正：W42 錯截的量化與 09:02 案例的單一歸因、屠體尺寸證據、A1 上限語意與失敗路徑的保證範圍、B1 的作用範圍與失敗契約（含對 W37 的相依）、A3 的例外作用域與來源判定，以及觀測設計的結算方式。文中以「實測」「推定」「未知」區分證據等級。
>
> **實作（2026-10-01）**：使用者決定全部實作（第一期與第二期都做），已實作為 W49（[patches.md 2bm](patches.md#2bm)）與 W50（[2bn](patches.md#2bn)），本機 build 全綠、尚未部署。與本評估不同的地方：
> - A1 擴大到所有 chunk 入場（非野生、有時鐘），不只 `connectedDZone` 為空時：畜牧區只部分串流時原版只補到 `hourLastSeen`，也是缺口；自身時鐘在卸載、載入中與雞舍內每小時都會更新，不會重複計算。
> - 長離線上限預設 168 遊戲小時（`-Dmdc.animalCatchUpLimit` 可調）。實作審查另找到不經三個 Java 改道的正常入口：牲畜拖車的 Lua `Vehicles.Update.TrailerAnimalFood` 直接呼叫 `updateStatsAway`（以車輛零件上次更新推算離線時數）。`updateStatsAway` 頭部加了時數過濾，讓這類直接呼叫保留原本的時數來源，只套同一個上限並記帳。
> - W37 重試修正改在 `AnimalManagerWorker.saveRealAnimals` 頭部清上一輪殘留的快照，失敗當下不清（失敗當下就清，下一輪之前的重試會漏寫世界中動物）。
> - 第 5 節的段落帳本改為 W32 beat 計數：A1 之後入場時總是補足自身時數，剩下的丟失只有上限截斷，可以直接計數（`limited`、`limitedHours`、`ownGainHours`）。
> - 新增雞舍內每小時刷新時鐘（`IsoHutch.updateAnimalInside` 兩個 `setHoursSurvived` 改道）。
> - A4（累積小時語意）一併實作，A2 由它涵蓋；B2 維持只觀測。
> - 其他決定：A0 不做（MOD 更新重啟不能減少，改由 A1／A3 根治重啟抹除）；A.4 不補回；B.3 採 (a)，3 隻已活化屠體不動。

## 0. 結論

### 問題 A：懷孕與成長補算不足

1. 實測：同一群動物的推進率長期只有五到六成（第 2.3 節）。牛的懷孕推進率在 W42 上線前是 47%、上線後是 58%；成長（age）上線前 23%、上線後 45%。資料能支持的只有兩點：不足在 W42 之前就存在；觀測期間牛群沒有整體變慢。前後兩段的重啟頻率、玩家在線時間都不同，這張表不能用來排序主次因。
2. 原版有四個缺陷會讓離線或載入中的時間不被計入，全部以正式服 bytecode 確認（第 2.1 節）：
   - 重啟抹除：`DesignationZone.streamed` 預設 true 且不存檔，開機後第一次檢查就把 `hourLastSeen` 改成開機時刻；`IsoAnimal.save` 寫入的時鐘是存檔當下而不是動物的 `timeSinceLastUpdate`。「玩家離開到下次重啟」這段時間，重啟後沒有任何路徑會補。
   - 首次進世界補 0：`AnimalManagerMain.fromWorker` 的時數取自 `getZone()`，而 `connectedDZone` 不存檔；本次開機第一次進世界的動物，以及不在任何畜牧區的放養動物，一律補 0 小時。重啟後的離線時間只能靠之後的 `doMeta` 補，前提是畜牧區兩角比動物晚載入。
   - 餘數歸零：`IsoAnimal.updateStatsAway` 每呼叫一次就把 `hoursSurvived` 設成 `新 age × 24`，丟掉最多 23 小時；一天內被呼叫一次以上，載入中的成長就永遠不會發生。
   - 兩種 `growUp` 觸發語意混用：補算按「跨過日曆午夜」，載入中按「累積滿 24 小時」，所以不能單純保留餘數。
3. W42 的角色（第 2.2 節）：W42 的 `min(zone 時數, 動物自身時數)` 在「動物一直在載入中、畜牧區重新串流」與「zone 值陳舊」時擋掉原版的重算，這部分正確。理論上還有一個會誤截的情境：`fromWorker` 補 0 之後，`liveHourGrow` 把時鐘刷新成現在，接著 `doMeta` 本該補的重啟後離線時數被截成 0。這個情境在程式上成立，但尚無任何被證實的實例，規模也無法從現有 W32 明細估計：184 筆候選中 41 筆有前序補算證據、較支持正常去重，其餘 143 筆無法判定。原報告舉的 09-30 09:02 那 17 筆也無法判定：快照顯示那段期間這 17 隻的 age 都 +10，也就是有兩個成長標準日的推進，但這可以來自補算，也可以來自載入中的成長；與「先補後去重」相容，但不能證實。原報告「W42 擋掉多補後損失才顯現」的推測，目前沒有證據支持。
4. 09-30 下午（原報告焦點，8.70 遊戲天）的情境帳本（第 2.4 節，推定）：若 Player-K 是這座農場唯一的載入來源，則載入中 2.57 天、重啟前卸載 3.51 天、重啟後卸載 2.62 天。實測牛懷孕 +4、豬圈母豬 +3，與「重啟抹除＋餘數歸零為主」的解釋相容。但 14:00 快照顯示這段時間還有 Player-K 以外的載入，來源不明，所以帳本只是推定，不是辨識結果。移交要求把損失精確分攤到四條路徑，在現有觀測下做不到。第一期的觀測擴充能在每次開機內量到三件事：餘數歸零的實際丟失；動物入場後到時鐘第一次被設成現在之前仍未補的時數（含首次進世界補 0；W42 開啟時此後不會再補，例外路徑另以計數對帳）；以及其後 `doMeta` 被截斷的時數中，落在這段未補時數上的部分（W42 誤截的上限估計）。重啟前還沒結算的段落另外列出。重啟抹除因原版存檔已抹掉卸載時刻，要到第二期 A3 之後才看得到（第 5 節）。
5. 補算本身的成本（第 2.5 節，實測）：全服平均每補 1 小時 0.053 ms，單隻單次最多 19 ms。長離線整批補算的停頓只能線性外推（86 隻 × 168 小時約 0.77 秒），上線驗收要量整批，不只單隻。

### 問題 B：掛鉤屠體被存成活豬

1. 根因（第 3.1 節，bytecode 確認）：`IsoAnimal.save` 只有在 `isOnHook() && hook != null && hook.getSquare() != null` 時寫 onHook=1。`hook` 參照不存檔；從磁碟載入的屠體要等進世界後第一次 `update()` 的 `reattachBackToHook()` 才會補回參照。在那之前，只要所屬 cell 被存檔，屠體就被寫成 onHook=0，下次載入就成了活體。正式服這幾個方法與 jar 相同，W42 沒有碰這條路徑。
2. 時序（第 3.2 節）：
   - 排除「鉤子被拆」：鉤子所在 chunk 在 12:00、14:00 與現況三份存檔中，都解碼出同一筆 ButcherHook 物件紀錄（serialize=1、classID 40、同一 sprite），內容逐位元相同。
   - 排除「chunk 卸載順序」：卸載不會清掉 `hook` 參照與它的 `square`，存檔照樣寫 1。12:00 快照正是這種狀態：玩家 11:04 離線，11:42 存檔寫 1。
   - 成立：寫 0 發生在「本次開機從磁碟載入後、成功 reattach 之前」。屠體可能一直在 worker 的虛擬狀態（同 cell 其他動物或變動讓 cell 被重寫），也可能已進世界但還沒 reattach；現有證據無法區分，修法兩者都要涵蓋。寫 0 的存檔在 12:45 關機之後、14:00 備份之前。
   - 14:00 快照的 size 0.784 不是「進過世界」的證據：`IsoAnimal.load` 讀回尺寸時經 `AnimalData.setSize` 以基因下限夾值，0.85 × 該個體的基因值 0.9222 ＝ 0.78387，與快照逐值相等；任何從磁碟載入、尚未由 Lua 重設掛鉤尺寸的屠體都會是這個值。
3. 全服掃描（第 3.4 節，實測）：2,973 個 apop 檔中，帶屠體 modData 的動物共 3 隻，全部已是 onHook=0 的活體；目前全服沒有任何正確掛著的屠體。各隻何時活化不可考（只知道最後一次被鉤子更新的時刻）。

### 建議摘要

| 期 | 內容 | 性質 |
|---|---|---|
| 一 | B1：存檔時保住屠體的掛鉤狀態，只作用在 apop 存檔路徑、依賴 W37 開啟，並補上 W37 失敗時的清理。B3：屠體不接受離線補算。A2：補算 0 小時時保留餘數。W32 觀測擴充：以每隻動物的離線段落配對記帳 | 直接修 B；A 只做零多補風險的修正與量測 |
| 二 | A1：首次進世界與放養動物改用動物自身時鐘補算。A3：apop 存檔時，不在世界中（不在 objectList）的動物改寫自身時鐘。兩者共用一個長離線上限（遊戲體驗取捨，由使用者決定），且都依賴 W42 開啟。B2：鉤子確實不存在時的處理，先只觀測 | 依第一期觀測數據與使用者對遊戲體驗的決定；會改變「玩家離線期間動物是否成長」 |
| 三（選配） | A4：補算改用「累積小時」語意，完全消除餘數損失 | 重寫補算迴圈 |
| 營運 | A0：減少 MOD 更新重啟的次數 | 使用者決定 |

## 1. 部署一致性與比對方法

- 以 javap 比對 jar 與 `dist/java` 的同名方法。比對前先去掉 constant pool 索引、指令 offset 與分支目標，並把 `ldc_w` 視同 `ldc`（constant pool 重排只改變 ldc 的編碼形式）。
- 與 jar 相同的方法：
  - `IsoAnimal`：`updateStatsAway(I)V`、`save(ByteBuffer,ZZ)V`、`load(ByteBuffer,IZ)V`、`reattachBackToHook()V`、`removeFromWorld()V`、`update()V`、`unloaded()V`、`checkZone()V`、`getZone()`、`setDZone`、`setOnHook`、`setHook`。
  - `AnimalData`：`growUp(Z)V`、`hourGrow(Z)V`、`init()V`、`initSize()V`、`setSize(F)V`、`getDaysSurvived()I`。
  - `AnimalManagerWorker`：`saveRealAnimals`、`loadChunk`、`addAnimal`、`removeFromWorld`、`stop`。
- 與 jar 不同的方法，差異只有 JavaPatch 既有的改道：
  - `AnimalData.update()`：唯一的 `hourGrow(false)` 改道 `AnimalAwayProbe.liveHourGrow`（W42）。
  - `AnimalManagerMain.fromWorker`：唯一的 `updateStatsAway` 改道 `AnimalAwayProbe.updateStatsAway`（W32／W42）。
  - `DesignationZoneAnimal.doMeta`：W38 快照與 W32 的兩處改道。
  - `AnimalManagerWorker.save()`、`AnimalCell.unload()`：`AnimalCell.save()` 改道 `MdcAnimalCellSave`（W37，只改開檔順序，不改序列化內容）。
- 走 jar（沒有 loose 版本）：`DesignationZone.checkStreamed`、`IsoButcherHook.*`、`AnimalPopulationManager.removeChunkFromWorld`／`save`。
- 全快照搜尋 `updateStatsAway` 的呼叫者只有 `fromWorker` 一處與 `doMeta` 兩處；`DesignationZone.loading()`／`unloading()` 沒有 Java 呼叫者；`IsoHutch.doMeta` 只處理髒污，不補本案牛豬的成長。

## 2. 問題 A：補算不足

### 2.1 原版機制（42.21.0，逐項 bytecode 查證）

**載入中**：`AnimalData.update()` 每次偵測到遊戲小時改變，就讓 `hoursSurvived + 1`。當 `age < floor(hoursSurvived / 24)` 時，把 age 設成 `daysSurvived + (mod − 1)`、`hoursSurvived` 設成 `age × 24`，並呼叫一次 `growUp(false)`（`pregnantTime++`、體型與體重成長、每日產蛋數歸零、糞便）。本服 `AnimalAgeModifier=3`，mod＝5，所以載入中每累積 24 小時，age 跳 5、懷孕 +1。

**補算**：`IsoAnimal.updateStatsAway(h)`（非野生時）依序：
1. `checkZone()`。
2. `age += floor(h / 24) × mod`。
3. `setHoursSurvived(新 age × 24)`，餘數就在這一步被丟掉。
4. 把 `lastHourCheck` 設成現在的小時。
5. 從 `timeSinceLastUpdate` 起逐小時跑 `hourGrow(true)`、`tryInseminateInMeta`、`checkEggs`，每小時把 `timeSinceLastUpdate` 加 1 小時，遇到日曆 0 點就 `growUp(true)`，並檢查 meta 掠食者。
6. 最後 `init()`，依 age 重算 size 與 weight。

**兩個呼叫點與時數來源**：
- `AnimalManagerMain.fromWorker`（chunk 載入）：時數＝`getZone() == null ? 0 : worldAgeHours − zone.hourLastSeen`（bytecode：`getZone; astore; aload; ifnonnull; iconst_0`）。`getZone()` 從 `connectedDZone` 隨機取一個；`connectedDZone` 只在 `checkZone()` 時填入，不存檔。所以本次開機第一次進世界的動物一律補 0；沒有連到任何畜牧區的放養動物永遠補 0。
- `DesignationZoneAnimal.doMeta`（畜牧區兩角都載入時由 `DesignationZone.checkStreamed` 觸發）：時數＝`worldAgeHours − hourLastSeen`。只涵蓋當下已在世界中、位於畜牧區內的動物。

**畜牧區串流狀態**：`DesignationZone.streamed` 欄位初始值是 true（`<init>` 內 `iconst_1; putfield streamed`），不存檔；`hourLastSeen` 有存檔。`checkStreamed` 每 2.5 秒執行一次：兩角有一角未載入且 `streamed` 為 true 時，設成 false 並把 `hourLastSeen` 設成現在。開機時沒有玩家，所以開機後第一次檢查就把存檔裡的 `hourLastSeen` 覆寫成開機時刻。

**動物時鐘**：
- `unloaded()` 把 `timeSinceLastUpdate` 設成卸載時刻。
- `IsoAnimal.save` 寫入時鐘欄位時，寫的是 `GameTime.getCalender().getTimeInMillis()`（存檔當下），不是 `timeSinceLastUpdate`（bytecode：`getHoursSurvived; putDouble` 之後緊接 `GameTime.getInstance; getCalender; getTimeInMillis; putLong`）；`load` 把讀到的值放回 `timeSinceLastUpdate`。
- 動物卸載後，所在 cell 的下一次存檔一定會寫出（卸載會設 `dataChanged`）；之後只要 cell 內有其他動物在世界中或有其他變動，每次存檔都重寫，磁碟上的時鐘就一路前移。

**合起來**：玩家離開農場後發生重啟，重啟後 `fromWorker` 對這些動物補 0，畜牧區的 `hourLastSeen` 已是開機時刻，磁碟時鐘最多只回溯到最後一次寫出。「離開到重啟」這段在任何路徑上都不會被補回；「重啟後」這段只有 `doMeta` 涵蓋到該動物時才補。

### 2.2 W42 與原版的交互

W42（docs/patches.md 2be）做兩件事：補算時數改成 `min(zone 時數, 動物自身離線時數)`；並在 `AnimalData.update` 的 `hourGrow(false)` 前把動物時鐘刷新成現在。動物自身離線時數以 `floorDiv(現在 − 時鐘, 1 小時)` 計，0 代表不足 1 小時。

W42 正確擋掉的情境（程式上成立，也有明細佐證）：
- 動物一直在載入中，畜牧區一角離開後又回來：原版 `doMeta` 會把這段時間再算一次，W42 取到的自身時數約 0。
- 大圍場部分 chunk 重載：zone 值陳舊，W42 取動物自身時數。
- 同一隻動物先被 `fromWorker` 補過、接著 `doMeta`：`updateStatsAway` 本身每補 1 小時就把時鐘推進 1 小時，`doMeta` 看到的自身時數約 0。

程式上成立、但尚未觀察到的誤截情境：`fromWorker` 對本次開機首次進世界的動物補 0，動物在世界中活過一個遊戲小時，`liveHourGrow` 把時鐘刷新成現在，之後畜牧區兩角串流完成觸發 `doMeta`，W42 看到自身時數 0，把原版本來會補的重啟後離線時數截掉。本服約 2.8 真實分鐘一個遊戲小時，只有 `fromWorker` 與 `doMeta` 之間跨過一個小時才會發生。

W32 逐筆明細只記「zone 時數比自身多 ≥24 小時」或「補算後死亡」。取 09-29 15:51 到 09-30 22:51（31 小時、22 輪）共 1,260 筆，篩出符合上述誤截形態的候選：zone 路徑、自身時數 0、`hourLastSeen` 等於該輪開機時刻（畜牧區本輪首次串流），共 184 筆、8,883 小時。再往前找同一隻動物同一輪的 chunk 路徑明細：

| 候選 | 筆數 | 被截時數 | 判定 |
|---|---|---|---|
| 同輪之前有 chunk 路徑補算（applied > 0） | 41 | 3,787 | 有前序補算證據，較支持正常去重（前後間隔最長 761 秒，不是每筆都有緊鄰的完整事件鏈） |
| 同輪之前沒有可見的 chunk 明細 | 143 | 5,096 | 無法判定：`fromWorker` 若補了但差距 <24 小時、或明細被限流（同期 suppressed＝460），都不會留紀錄 |

原報告舉的 09-30 09:02 那 17 筆屬於後者。06:00 到 10:00 兩份快照之間（78.9 遊戲小時），這 17 隻的 age 都 +10、餘數從 2 變成 12，母豬懷孕 +3。age +10 表示區間內有兩個成長標準日的推進，但來源不唯一：載入中每累積 24 小時 age 也會 +5，例如載入 48 小時、一次跨午夜的短補算、再載入 12 小時，就能得到同樣的 age +10、懷孕 +3、餘數 12（審查以鏡像驗證；只用來說明快照差分不唯一，不主張這就是實際事件鏈）。所以快照差分與「09:01 的 `fromWorker` 先補、09:02 的 `doMeta` 去重」相容，但不能證明區間內有大量補算，更不能定位到 `fromWorker`；Player-K 在場不到 40 分鐘，也不能限制農場在這 78.9 遊戲小時內的總載入時間。所以這 17 筆既不能證實是誤截，也不能證實是正常去重。

結論：W42 誤截尚無被證實的實例，規模未知。要回答必須以每隻動物的離線段落配對記帳（第 5 節），不能用「本輪是否補過」這種以整輪開機分組的分類。

### 2.3 A.2：W42 上線前後的推進率

資料為 `<cell-K>` 的 apop 快照：從 NAS 整包備份串流取出 15 份（09-25 到 09-30），加上原報告的 6 份。每份用存檔內的時間戳換算成遊戲時間；換算方法見附錄。推進率的算法：懷孕是 Δ懷孕天數 ÷ Δ遊戲天數；成長是（Δage ÷ 5）÷ Δ遊戲天數。

| 區間 | 遊戲天 | 牛 懷孕 | 母豬（牛群側）懷孕 | 母豬（豬圈）懷孕 | 牛 age | 公牛（牛群側）age | 公豬（豬圈）age |
|---|---|---|---|---|---|---|---|
| P0 W38／W42 之前（09-25→09-26 00:00） | 17.9 | 67% | — | — | 33% | 45% | 45% |
| P1 跨 W37／W38 上線 | 16.4 | 24% | 30% | 30% | 12% | 12% | 6% |
| P2 跨 W42 上線 | 7.0 | 114% | 114% | 100% | 43% | 43% | 43% |
| P3 W42（09-27 06:00→09-28 20:00） | 29.4 | 44% | 41% | 27% | 20% | 20% | 20% |
| P4 跨 42.21 更新（其中約 3 小時純原版） | 2.6 | 117% | 117% | 117% | 78% | 78% | 78% |
| P5 W42（09-29） | 21.3 | 71% | 71% | 71% | 71% | 71% | 19% |
| P6 W42（09-30 上午） | 11.1 | 81% | 81% | 54% | 81% | 81% | 27% |
| P7 W42（09-30 下午，報告焦點） | 8.7 | 46% | 46% | 34% | 23% | 23% | 0% |
| W42 前合計（P0＋P1） | 34.3 | 47% | — | — | 23% | 29% | 26% |
| W42 後合計（P3＋P5＋P6＋P7） | 70.4 | 58% | 57% | 45% | 45% | 45% | 18% |

判讀：
- 可支持的描述：不足在 W42 之前就存在；觀測期間牛群那一側沒有整體變慢。
- 不能從這張表推出的：W42 是不是主因、豬圈為何比牛群差。兩段期間的重啟頻率、玩家在線時間、W38 之前的重複補算都不同，這是不同暴露條件下的描述性比率，不是相同事件序列的對照。
- P2、P4 高於 100% 的區間短、事件少，可能來自跨午夜的整數效應，也可能有其他原因，表格本身無法判定。

### 2.4 A.1：09-30 下午的情境帳本（推定）

- 區間：12:00 快照（11:42:52 存檔）到 21:57 快照（21:57:16 存檔），遊戲時間 208.8 小時（8.70 天）。牆鐘全長 614 分鐘，扣掉 10 次停服後的有效運行時間約 595 分鐘，平均 0.351 遊戲小時／分鐘。
- 前提：載入與卸載以 Player-K 的連線、斷線與動作 log 推定，並假設在線期間都在農場、沒有其他載入。後一項已知不成立：14:00 快照牛群的餘數是 10，表示 12:47–13:47 之間牛群所在 chunk 有約 10 小時處於載入中，而 Player-K 那段時間在遠處。

| 路徑 | 遊戲時數 | 天 | 原版 | 現況（W42） |
|---|---|---|---|---|
| 載入中（Player-K 在場，7 段：0.4、0.4、19.1、19.1、14.1、5.1、3.5 小時） | 61.7 | 2.57 | 每段開頭被 `fromWorker` 或 `doMeta` 歸零；若沒有其他載入，沒有一段滿 24 小時 | 同原版 |
| 卸載：最後一次重啟之前的部分 | 84.2 | 3.51 | 重啟抹除 | 同原版 |
| 卸載：最後一次重啟之後的部分 | 62.9 | 2.62 | 由 `doMeta` 補（前提是畜牧區兩角比動物晚載入），否則 `fromWorker` 補 0 | 同原版；另有 2.2 節的誤截情境 |

實測：牛懷孕 +4、age +10；豬圈母豬懷孕 +3、豬圈公豬 age +0。

判讀：
- 相容的解釋：豬圈 age 整個下午沒動，表示載入中沒有一次累積滿 24 小時，也沒有一次補算達 24 小時；豬圈的 3 次懷孕推進來自短補算剛好跨過午夜。這與「重啟抹除＋餘數歸零為主」相容。
- 做不到的：把 5.7 天（豬圈）或 4.7 天（牛）的損失精確分攤到四條路徑。未知的載入會改變卸載時刻、畜牧區的 `hourLastSeen`、補算次數與 cell 存檔時鐘，三個桶的分配都會跟著變；age 與懷孕同步增加也可能來自一次 48 小時以上的補算，而不只是載入中的成長。
- 09-30 全天（00:00→21:57，19.8 天）：牛懷孕 +13（66%），豬圈母豬 +9（46%）。

### 2.5 全服 W32 統計與 CPU（實測）

期間同上（31 小時、22 輪），數字取各輪最後一次 heartbeat：

| 項目 | 值 |
|---|---|
| `updateStatsAway` 呼叫 | 195,689 次（約 6,300 次／真實小時） |
| 被 W42 截斷 | 35,394 次（18%），共 187,893 小時，平均每次 5.3 小時 |
| 實際補算 | 339,400 小時（平均每次 1.73 小時；很多呼叫是 0） |
| 補算耗時 | 共 17,974 ms，平均每補 1 小時 0.053 ms，單隻單次最多 19 ms |
| 補算後死亡 | 11 |

- 0.053 ms 是含零時數呼叫的總平均，不是長離線情境的最壞值。本案農場 86 隻，若一次補 168 小時，線性外推約 0.77 秒的整批停頓；實際值要上線後以整批量測驗收。
- 9/24 那次 13 秒凍結的堆疊落在 `fromWorker` 的補算；W42 立案時歸因於 zone 值陳舊造成的多補。W38 修的是 `doMeta` 清單變動造成的重複走訪，兩者是不同的問題，不能直接互證。

### 2.6 修法候選（A.3）

四個檢查點依移交要求：是否重新引入 9/24 型多補（一次大量懷孕到期、補算後死亡、主迴圈凍結）；`fromWorker` 與 `doMeta` 之間會不會重複補算；存檔相容；CPU。

| 方案 | 修掉的路徑 | 多補風險 | 重複補算 | 存檔相容 | CPU | 規模 |
|---|---|---|---|---|---|---|
| A0 減少 MOD 更新重啟 | 按比例減少重啟抹除 | 無 | 無 | 無關 | 無 | 營運 |
| A1 `fromWorker` 且 `connectedDZone` 為空時，改用動物自身離線時數補算（屠體、野生、無紀錄 -1 除外），受長離線上限約束 | 首次進世界補 0；放養動物永不補算 | 低：磁碟時鐘不早於卸載時刻，只會少補；但補算量增加，必須有上限 | 無，前提是 W42 開啟且上限語意照下方設計。W42 關閉時 `doMeta` 回原版時數，會與 A1 重複，所以 A1 必須跟著 W42 的開關 | 不改格式 | 增加量＝首次進世界時的可見離線時數 × 動物數 | 只改 `AnimalAwayProbe`；判斷用 `getConnectedDZone().isEmpty()`，不呼叫會耗用 `Rand` 的 `getZone()` |
| A2 補算時數為 0 時，委派後把 `hoursSurvived` 設回委派前的值 | 被 `doMeta`（一直載入中）與補 0 的 `fromWorker` 歸零的餘數 | 無：沒有補算就沒有同一段時間算兩次 | 無 | 不改格式 | 0 | 只改 `AnimalAwayProbe` |
| A3 apop 存檔時，已卸載（不在世界中）的動物改寫自身時鐘；世界中的動物照原版寫存檔當下 | 重啟抹除 | 中：長時間沒人來的農場，重啟後第一次載入會一次補足；本服真實 1 天＝24 遊戲天，必須由 A1 的上限約束 | 無，前提同 A1 | 格式不變；原版不拿這個欄位當補算時數，回退安全；只撤 A3 時回到現況 | 增加量＝卸載到關機的時數 × 動物數 | 與 B1 共用 `VirtualAnimal.save` 內的改道，另加 `IsoAnimal.save` 內唯一 `getTimeInMillis` 的改道（見下方） |
| A4 補算改用累積小時語意：`growUp` 在「餘數＋補算時數」每滿 24 小時時觸發，age 同步，`hoursSurvived` 保留新餘數 | 所有餘數損失，包含補算時數大於 0 的情況 | 低；但會改變 `growUp` 時點（每日產蛋歸零、糞便、體型成長從午夜改為按累積） | 無 | 不改格式 | 同原版 | 大：由 helper 重寫補算迴圈（委派原版 `updateStatsAway(0)` 取得 `checkZone`／`init`），升版時要追原版改動 |
| A5 撤 W42（回原版） | 消除理論上的誤截 | 高：重新引入 zone 陳舊造成的多補與補算後死亡（W42 立案原因） | 高 | — | — | 不建議，列為對照 |

**長離線上限的語意（A1、A3 共用，實作前必須定案）**：
- 分清兩個值：`limit`（設定上限）與 `applied`（本次實際委派的時數）。
- 原版 `updateStatsAway` 只把時鐘推進 `applied` 小時，不會在結尾設成現在。若只把補算夾到上限，時鐘仍落後，之後的 `doMeta` 會經 W42 的 min 再補剩下的部分，總量繞過上限（例：實際離線 64 小時、上限 48，先補 48、`doMeta` 再補 16，共 64）。
- 建議語意是「一次離線最多補 `limit` 小時，較早的部分丟棄」：只有在動物自身離線時數大於 `limit` 時，委派前把 `timeSinceLastUpdate` 設成 `現在 − limit`，再委派 `limit` 小時；沒超過時不動時鐘，行為與原版相同。
- 上限保證只涵蓋正常跑完的路徑：迴圈結束時時鐘剛好等於現在，後續入口都看到 0，總補算不超過 `limit`。原版在逐小時迴圈之前就把 age 一次加上 `floor(時數 / 24) × mod`，所以委派中途拋例外時，age 已按整段入帳，時鐘卻停在中途；下一個入口補剩下的時數時會再加一次 age（例：上限 48、完成 1 小時後拋例外，age 先 +10，之後補 47 小時再 +5，共 +15，審查以鏡像驗證）。這是原版既有的語意，沒有上限時也一樣。第二期不另做處置，也不在例外時把時鐘設成現在假裝補完；只計數委派例外，觀測到再定失敗政策。meta 掠食者的提前 return 會讓動物死亡，不會再補；本服 `AnimalMetaPredator=false`。
- 截斷時補的是「最近 `limit` 小時」，跨午夜的 `growUp`，以及 `checkEggs` 讀取的日曆（產蛋期、窩數、下蛋時間），都改從 `現在 − limit` 起算。這是丟棄較早離線時間的政策後果，只發生在真的截斷時；未截斷的補算不受影響。`tryInseminateInMeta` 雖收日曆參數，配種資格仍以當下 `GameTime` 判斷。

**A2 的邊界**：`lastHourCheck` 仍被設成現在，最多少算 1 小時；`init()` 依 age 重算體型，與原版相同。A2 不處理補算時數大於 0 時的餘數，也就是移交提醒的「兩種語意混用，單純保留餘數會重算」，那部分只有 A4 能根治。

**A3 的作用域與時鐘判斷**：
- `IsoAnimal.save` 也被 `AnimalUpdatePacket`、`AnimalInventoryItem`、`IsoHutch` 呼叫。雞舍內的動物由 `IsoHutch.updateAnimalInside` 累積時數與成長、不經 `liveHourGrow` 刷新，`releaseAnimal` 放回世界時也不刷新時鐘。陳舊時鐘若被寫出，放出後、第一次刷新前被 `doMeta` 納入時，雞舍內已成長過的時間會被當成離線再補一次。
- 所以 A3 只在 apop 路徑、且只對不在世界中的動物生效。做法：把 `VirtualAnimal.save` 內唯一的 `IsoAnimal.save` 呼叫改道到 helper，helper 以 Java 的 `try/finally` 設定並還原「目前正在寫出的動物」（巢狀時還原前一層）；`IsoAnimal.save` 內唯一的 `getTimeInMillis` 改道，只在這個上下文中、寫出的正是該動物、它不在 `IsoWorld.instance.currentCell.getObjectList()`、時鐘大於 0 且不晚於現在時，回傳動物時鐘；其他情況（包括取不到 cell）照原版回傳存檔當下。
- 來源用 objectList 判斷，不用 current square。`saveRealAnimals` 收集世界中動物的條件就是 objectList 成員，而 `AnimalPopulationManager.save()` 在同一執行緒先收集、再序列化，所以「在 objectList」正好對應 real snapshot 來源，也不新增跨執行緒存取（objectList 是 `HashSet`，查詢 O(1)）。current square 不可靠：`IsoHutch.releaseAnimal` 經 `IsoCell.addMovingObject` 放回世界的動物，在下一次定位更新前 current 仍是 null，但一進 objectList 就會被 `saveRealAnimals` 收集（審查以狀態轉移鏡像驗證）。若有動物同時在虛擬清單與 objectList（例如移出被延後），這次存檔照原版寫存檔當下，只是 A3 這次不生效，方向安全。
- 頭尾各一個 HeadCall／TailCall 的做法不安全：TailCall 只在正常 RETURN 前執行，例外退出時旗標不會清除，後續的封包與雞舍存檔會被誤判成 apop（審查以鏡像驗證）。
- `saveRealAnimals` 收集的世界中動物也經 `VirtualAnimal.save` 寫出；它們都在 objectList，照原版寫存檔當下。從雞舍放出、尚未刷新時鐘的動物也屬這一類，不會寫出陳舊時鐘。已卸載動物的記憶體時鐘則是卸載時刻：`removeChunkFromWorld` 與 `virtualizeAnimal` 交給 worker 前都先呼叫 `unloaded()`（2.1 節）。

**A1 與 A3 的關係**：A1 補的是「現在 − 磁碟時鐘」。玩家離開後若 cell 沒有其他變動，時鐘停在卸載後的下一次存檔，A1 單獨就能補回大部分重啟抹除；若 cell 仍持續被重寫，時鐘會前移到關機時刻，這時只剩 A3 能補。本案屬於後者：12:00 快照的存檔時刻是 11:42:52，不是玩家離開後的第一次存檔 11:12:52。

**預期效果**：本案現有資料無法可靠估計各方案能挽回多少天（第 2.4 節）。第一期的觀測擴充上線後，可以量到餘數歸零、入場後未補（含首次進世界補 0）的實際丟失，以及 W42 截斷落在未補時數上的部分；重啟抹除的規模要到 A3 上線後，或以 apop 快照差分估計。第二期要不要做、上限取多少，應同時依據這些數據與使用者對遊戲體驗的決定。

### 2.7 A.4：已受影響動物要不要補回（只評估）

- 不建議一次性補回，原因有三：
  - 各動物的損失取決於各自畜牧區與載入歷史，只能以同群快照差分粗估；46 天是相對 100% 基準的差距，不是可直接認定應補的欠帳。
  - 一次補回多天，會讓多隻母畜同時到期，出生集中；也會讓多隻幼體同時轉換成長階段，大量建構新成體，其中建構失敗是 W37 處理過的高風險路徑。
  - 需要停服離線編輯 apop，或寫一次性伺服器 Lua 腳本，兩者都要另外授權。
- 若仍要補：
  - 只補懷孕天數（`AnimalData.setPregnancyTime`，public），只補玩家回報的個體，依快照差分的一半保守補，並分散到期時間。
  - 不補 age，避免集中階段轉換。
  - 原版 `IsoAnimal.debugAgeAway(int)` 不適合拿來補：它只把 `hoursSurvived` 加上時數、逐小時 `hourGrow`，不設定 age、不呼叫 `growUp`，回到載入中只會觸發一次成長。
- 本案 Player-K 的懷孕乳牛（animalId 7792）：09-25 到 09-30 的 114.3 遊戲天中，懷孕推進 68 天（59%），相對 100% 差 46 天。孕期 196 天，現況剩 64 天。

## 3. 問題 B：掛鉤屠體存成活豬

### 3.1 原版機制（42.21.0，逐項 bytecode 查證）

- `IsoAnimal.save(ByteBuffer,ZZ)V` 的掛鉤區段：`isOnHook; ifeq → else`、`getfield hook; ifnull → else`、`getfield hook; getSquare; ifnull → else`。三者都成立才寫 1 與座標：`attachBackToHookX/Y > 0` 時用 `attachBackToHook`，否則用 `hook.getSquare()`；只要有一個不成立就寫 0。後面緊接 `putFloat petTimer`、`put wild`、`putShort onlineID`，然後 return。
- `load` 讀到 1 時還原 `onHook` 與 `attachBackToHook` 座標，不還原 `hook` 參照；讀回尺寸時經 `AnimalData.setSize` 以基因的上下限夾值（掛鉤展示尺寸是由 Lua 以不夾值的 `setSizeForced` 設定的，所以一載入就被夾回活體最小尺寸）。
- `update()` 在 `isOnHook()` 時只做 `reattachBackToHook()`、`ensureCorrectSkin()` 與網路同步，不跑 `AnimalData.update`，所以屠體的時鐘不會被 W42 刷新，餘數也不會累積。
- `reattachBackToHook()` 需要動物已有 `square`，再到 `attachBackToHook` 那一格找 `IsoButcherHook`。找到後呼叫 `hook.reattachAnimal(this)`：設定雙向參照、動畫狀態，並呼叫 Lua `ButcheringUtil.onReattachAnimal`，後者把 size 設回掛鉤展示尺寸；接著把 `attachBackToHook` 清成 0。
- 卸載：`IsoChunk.removeFromWorld` 先由 `AnimalPopulationManager.removeChunkFromWorld` 對動物呼叫 `unloaded()` 並轉入 worker，再呼叫動物的 `removeFromWorld()`（記下 `attachBackToHook`＝鉤子格）與鉤子的 `removeFromWorldToMeta()`（鉤子若持有動物，也記下同一組座標）。`IsoObject.removeFromWorld`、`IsoGridSquare.softClear`／`discard` 都不會清掉物件的 `square` 欄位，所以本次開機曾 reattach 的虛擬屠體，`hook` 參照與它的 `square` 仍然存在，存檔寫 1。
- 從磁碟載入的屠體沒有 `hook` 參照。它在 AnimalChunk 裡是虛擬狀態（`VirtualAnimal.load` 還會把它移出世界更新清單），或已進世界但尚未被 update；在這段期間，只要所屬 cell 被存檔就寫 0。`saveRealAnimals`、同 cell 任何動物的載入與存在、遷徙等都會讓整個 cell 重寫。
- `fromWorker` 不檢查 `onHook`：屠體每次進世界都跑一次 `updateStatsAway`，包含 `hourGrow(true)`（飢餓、健康、產乳、羊毛、授精）、`growUp`（體型、糞便）、`tryInseminateInMeta`、`checkEggs`、meta 掠食者檢查，最後 `init()`。這是 B3 要處理的附帶缺陷。

### 3.2 B.1：本案時序

資料來源：`<cell-K>` 的 apop 快照、鉤子所在 chunk 的 3 份存檔（12:00、14:00 從備份取出，現況直接讀取），加上 console、存檔、連線與動作 log。`deathAge` 只在鉤子持有屠體、且鉤子被 update 時更新，所以 `deathTime + deathAge` 等於「最後一次被鉤子更新」的遊戲時刻。

| 時間（真實） | 事件 | 證據等級與來源 |
|---|---|---|
| 約 11:00–11:04 | 最後一次被鉤子更新（W 31492.99）；Player-K 11:04 離線，農場卸載 | 實測（12:00 快照的 `deathTime + deathAge`、連線 log） |
| 11:42:52 | 自動存檔：屠體為虛擬狀態，但本次開機曾 reattach，`hook` 參照與 `square` 仍在，寫 onHook=1、size 0.600 | 實測（12:00 快照，存檔時刻 W 31508.58） |
| 12:05 | 重啟 | 實測（log） |
| 約 12:10–12:12 | Player-K 回到農場約 1 分鐘後開車離開。屠體從磁碟載入、進世界並成功 reattach，鉤子把 `deathAge` 更新到 W 31517.67，隨後農場卸載 | 推定（14:00 快照的 `deathAge` 對到這段時間；動作 log） |
| 12:45 | 重啟：關機存檔時屠體是虛擬狀態，本次開機曾 reattach，寫 1 | 推定 |
| 12:47–14:00 之間 | 屠體從磁碟載入（`hook` 為 null），在成功 reattach 之前，所屬 cell 被存檔，寫 onHook=0、size 0.784。屠體可能一直在 worker 虛擬狀態，也可能已進世界尚未 reattach；Player-K 這段時間在遠處，cell 被載入或重寫的原因不明 | 實測（14:00 快照：onHook=0、size 0.784、位置仍在鉤子旁、`deathAge` 停在 W 31517.67）；中間狀態未知 |
| 下一次開機 | 從磁碟載入 onHook=0，成為活體，開始走動 | 實測（16:00 快照：位置離鉤子約 45 格，餘數與豬圈同步） |

排除與成立：
- 鉤子被拆：排除。三份鉤子 chunk 依 `IsoGridSquare.save` 格式解碼，都含同一筆物件紀錄（serialize=1、classID 40＝ButcherHook、sprite 296056），前後內容逐位元相同。12:00 到 14:00 的位元組差異只有 48 個物件的同一個旗標位元，以及檔頭時間戳。
- chunk 卸載順序：排除。見 3.1 節，卸載後寫 1；11:42 那次就是實例。
- 本次開機從磁碟載入後、reattach 前存檔：成立。
- 屠體在寫 0 之前是否進過世界：無法判定。size 0.784 是載入時的夾值（0.85 × 基因值 0.9222 ＝ 0.78387，逐值相等），餘數 0 在 12:00 就已是 0（屠體不跑 `AnimalData.update`），兩者都不能定位到 `fromWorker`。
- 寫 0 的存檔是哪一次：14:00 快照的存檔時刻換算後落在 13:15 關機前後，但牆鐘換算有約 ±8 小時的不確定性，13:47 自動存檔也不能排除。

### 3.3 B.2：修法候選

| 方案 | 內容 | 相容與風險 | 規模 |
|---|---|---|---|
| B1 存檔保住掛鉤狀態 | 把 `VirtualAnimal.save` 內唯一的 `IsoAnimal.save` 呼叫改道到 helper（與 A3 共用）。helper 委派原版寫出後，若 `onHook` 為真但 `hook` 無效、且有 `attachBackToHook` 座標，就把剛寫出的 onHook=0 改成 1、補寫三個座標 int，並把其後 7 bytes（petTimer、wild、onlineID）後移 12 bytes。只作用在 apop 存檔（`AnimalCell` 經 `AnimalChunk`／`VirtualAnimal` 寫出，含 `saveRealAnimals` 收集的世界中動物）；`hook` 有效的正常路徑完全不動；`AnimalUpdatePacket`、`AnimalInventoryItem`、`IsoHutch` 呼叫的 `IsoAnimal.save` 不受影響 | onHook=1 加座標本來就是合法格式，回退安全。不碰網路：原版在屠體尚未 reattach 時送給 client 的快照一樣是 onHook=0，client 可能暫時把屠體顯示成活體，但伺服器狀態正確，reattach 後的快照會帶 1；這部分維持原版 | 新增 `VirtualAnimal` 的 ClassPatch（一個 1:1 改道）；不需擴充 TailCall |
| B1 替代：load 端救援 | 載入後若 onHook=0 但 modData 帶屠體欄位，就恢復掛鉤 | 缺座標，要靠鄰格搜尋；活化後被當活豬養的個體會誤判 | 不建議當主修法 |
| B1 替代：替身 hook | `getfield hook` 同形改成 helper，回傳一個 `getSquare()` 非 null 的替身 | 需要偽造 `IsoGridSquare`，脆弱 | 不建議 |
| B2 鉤子確實不存在時的處理 | `reattachBackToHook` 尾端記錄「鉤子格已載入，但格上沒有 `IsoButcherHook`」的次數、座標與動物，先只觀測；若之後啟用，比照 `IsoButcherHook.removeHook` 把屠體轉成屍體（`new IsoDeadBody(animal)`、保留 modData、size 還原成 `originalSize`、`invalidateCorpse`、`remove`、`sendCorpse`） | B1 關掉了原版「存成活體」這個錯誤出口，所以要提供正確的出口。會發生的情境：地圖或建築重置工具刪了 chunk 但沒動 apop、chunk 被 Blam 重生、MOD 移除鉤子。原版用大錘拆鉤走 `removeHook`，本來就會轉屍體。B2 只看得到已進世界的屠體，回答不了「從未進世界就被存檔」 | 新掛點 |
| B3 屠體不接受離線補算 | `AnimalAwayProbe` 在最前面判斷 `isOnHook()`：不委派 `updateStatsAway`、不呼叫 W39 的 `noteCatchUp`，並把 `fromMeta` 設回 false（原版在 `updateStatsAway` 開頭做這件事，跳過委派就要補做） | 消除屠體被補算的錯誤副作用 | 只改 `AnimalAwayProbe` |

**B1 的失敗契約**：B1 不得成功提交已知錯誤的 onHook=0。
- 所有檢查（旗標、尾端 8 bytes 的第一個是否為 0、剩餘容量）都在修改 buffer 之前完成。
- 需要介入但剩餘容量不足或尾端格式不符時，拋 `IOException`。伺服器的 apop 存檔全部經 W37 的 `MdcAnimalCellSave`：定時存檔走 `AnimalManagerWorker.save`、關機走 `AnimalCell.unload`；`saveToBufferMap` 這條 apop 呼叫鏈只在單機執行（`ChunkSaveWorker.Update` 排除伺服器，且只在 `!GameClient.client && !GameServer.server` 時呼叫 `HotsaveAncilliarySystems`）。W37 在開檔前完成序列化，例外時保留舊檔。容量不足只是理論路徑：`SliceY.SliceBuffer` 固定 10 MiB，本案 cell 是 124 KB（84 隻）。
- B1 依賴 W37：`-Dmdc.animalCellSave=0` 時，W37 直接呼叫原版 `AnimalCell.save()`，它先開檔（當場截斷）才序列化，B1 拋出的 `IOException` 會留下被截斷的檔案。所以 B1 helper 與 W37 放在同一個 package、讀取 W37 的啟用旗標；W37 關閉時 B1 自動停用、照原版寫出，並在開機橫幅註明。
- W37 的失敗處理要一併補兩件事：
  - 對 `IOException` 也把 `dataChanged` 標回 true（目前只對 RuntimeException 標記），讓下一次存檔重試。
  - 失敗時清掉 `saveRealAnimalHack`。`AnimalCell.save(ByteBuffer)` 只在整份成功後才清這個暫存清單；失敗時清單保留，下次 `saveRealAnimals` 又把同一批世界中動物加進去，`AnimalChunk.save` 不去重，同一隻會被寫兩次（審查以鏡像驗證：兩隻經一次失敗再收集後變成四筆）。這是 W37 現役的隱患：9/26 上線以來若出現過 `serialize failed`，就可能已經發生。
- `attachBackToHook` 缺席時不介入，照原版寫出並計數。這需要 `hook` 參照與座標同時失效：`load` 讀到 1 時一定還原座標，卸載時 `removeFromWorld` 也會記下座標，唯一會清座標的 `reattachBackToHook` 成功時 `hook` 一定有效，所以正常流程不會發生。不以屠體位置推定鉤子格：掛鉤展示位置是格中心再加偏移（本案 y 方向合計 +1.0 格），取整會落在隔壁格，讓 B2 誤判鉤子不存在。

建議 B1＋B3 放第一期，B2 放第二期且先只觀測。第一期上線後，鉤子確實不存在的屠體會一直懸空、每幀嘗試 reattach（成本小），要讓玩家與管理員知道這個已知狀態，而不是宣稱 B 已全部解決。

### 3.4 B.3：全服掃描與清理（只評估）

- 掃描方法：
  - 正式服 apop 目錄共 2,973 個檔。以 `grep -l -a -F deathTime` 篩出 3 個檔，取回本機後用結構化解析器完整讀到檔尾。
  - 屍體（IsoDeadBody）存在 map chunk，不在 apop；所以 apop 內帶 `deathTime` 的必然是 `IsoAnimal`。

| cell | 動物 | onHook | 最後一次被鉤子更新 | 活化時間 |
|---|---|---|---|---|
| `<cell-K>` | boar#8129（landrace） | 0 | W 31517.67 | 由 12:00、14:00 快照夾住：12:47–14:00 間寫 0，下一次開機活化 |
| `<cell-L>` | boar#4174（largeblack） | 0 | W 25073.3 | 未知（晚於最後更新時刻） |
| `<cell-M>` | sow#134（largeblack） | 0 | W 30519.2 | 未知（晚於最後更新時刻） |

- 目前全服沒有 onHook=1 的屠體。活化後又被殺掉的個體 modData 已不在 apop，無從統計，所以 3 隻是下限；單一現況截面也不能推出「所有沒取下的屠體最終都會活化」，只能說這個缺陷在本服已發生至少 3 次。
- 處理選項：
  - (a) 不動，告知玩家可正常宰殺。活體被殺時，`setAnimalBodyData` 會重設主要屠宰欄位（parts、BloodQty、leather、head、corpseSize）。殘留的 `deathTime`、`deathAge`、`originalSize`、`animalRotStage` 預期無害；`headless` 不在這 3 隻的 modData 裡。
  - (b) 停服離線編輯 apop，還原成掛鉤屠體：onHook=1、原鉤子座標、size 與位置還原。8129 的鉤子仍在；另兩隻的鉤子要先確認。
  - (c) 刪除並補償玩家。
- 建議 (a)：不需停服，也不必動存檔。(b) 要停服，並要取得本次重啟授權。

## 4. 風險

- A1／A3 會改變遊戲體驗：離線期間的動物會開始真的成長，懷孕可能到期、食槽不足時可能餓死。原版的 meta 補算本來就是這樣設計（`AnimalMetaStatsModifier`、`AnimalStatsModifier` 決定離線倍率，本服兩者都是 4），但現況因重啟抹除，玩家實際上沒體驗過。上限值要由使用者決定，上線公告要說明。
- A1／A3 若上限語意沒照 2.6 節設計，後續入口會繞過上限。
- A3 若沒限定在 apop 路徑的已卸載動物、或用頭尾旗標而不是 `try/finally` 上下文，雞舍、背包、拖車的動物放回世界後可能被多補（2.6 節）。
- B1 依賴 `save` 尾端格式。TIS 若在尾端加欄位，SmokeCheck 會在建置時轉紅；Patcher 的逐方法命中數守門也能擋住掛點漂移。
- B1 上線後，鉤子確實不存在的屠體會一直懸空，要靠 B2 觀測出現頻率再決定是否轉屍體。
- 第一期落在 W32 helper、`VirtualAnimal.save` 的一個改道，以及 W37 helper 的失敗處理；不改存檔格式、不改網路協定。B1、B3、A2 各有 kill switch，可單獨關閉；關閉 W37 會連帶停用 B1（3.3 節）。整批回退用 `uninstall.sh`。
- W37 現役隱患（本次評估發現，與 A、B 無關）：`MdcAnimalCellSave` 在序列化失敗時保留舊檔、標記重試，但沒清 `saveRealAnimalHack`，重試時世界中的動物會被重複寫出（3.3 節）。B1 的失敗契約會一併修掉；若 B1 不做，也建議單獨修。

## 5. 測試與線上驗收計畫

離線（本機）：
- `AnimalAwayProbeTest` 擴充（含各自的 kill switch 組態）：
  - A2：補 0 小時保留 `hoursSurvived`；補大於 0 時維持原版。
  - B3：屠體不委派、不記 `noteCatchUp`，且 `fromMeta` 被清除。
  - 第二期 A1：`connectedDZone` 為空時依時鐘補算；屠體、野生排除；-1 沿用原版；時鐘在未來時補 0。上限情境：`limit` 與 `applied` 分離、未達上限時不動時鐘；「離線 64 小時、上限 48，接著立即 `doMeta`」「兩個重疊畜牧區」「截斷後跨午夜與產蛋期」「存檔再載入」，驗證正常路徑的總補算不超過上限。委派中拋例外時只計數、不改時鐘，例外照原版外拋。W42 關閉時 A1 也停用。
  - 第二期 A3：apop 寫出途中拋例外後，上下文確實還原，同一執行緒緊接著寫出的封包與雞舍仍用存檔當下；世界中動物（在 objectList）照原版寫存檔當下，包括「`releaseAnimal` 已放進 objectList、current 仍為 null」的狀態；「雞舍內持續成長 → 放出、尚未刷新時鐘 → `saveRealAnimals` → 存檔再載入 → A1」不多補。
- 以真實事故存檔重現 B：擴充 `MdcAnimalCellSaveTest` 既有的真 `AnimalCell→AnimalChunk→VirtualAnimal→IsoAnimal` 序列化鏈。
  - 讀入 12:00 快照的 `<cell-K>` apop（含 onHook=1 的屠體，本機私有檔），不 reattach，直接存檔。
  - 驗收：off 組態重現 onHook 變 0；on 組態保持 1 且座標不變；其餘 83 隻動物的序列化位元組完全不變。
  - 審查已用同一份檔在記憶體中驗證 B1 的修補演算法：把屠體的掛鉤紀錄改成原版錯存的樣子，再依 B1 補寫，結果與原檔逐位元相同。
- B1 buffer 邊界：剩餘容量 11 與 12 bytes、同一 buffer 連續兩筆動物、非零起始 position；容量不足時拋 `IOException` 且 buffer 未被修改。B1 開、W37 關：B1 停用，輸出與原版逐位元相同、不拋例外。W37 失敗處理：「失敗一次 → 重新收集 → 下一次成功」後，每隻動物只寫出一次、`dataChanged` 在失敗後為 true。另驗證 `AnimalUpdatePacket`、`IsoHutch` 呼叫的 `IsoAnimal.save` 輸出與原版逐位元相同。
- SmokeCheck 新增存在理由：
  - `fromWorker` 的 `getZone()` 為 null 時給 0（TIS 修掉時轉紅＝重估 A1）。
  - `DesignationZone.<init>` 的 `streamed=true`，以及 `checkStreamed` 覆寫 `hourLastSeen`。
  - `IsoAnimal.save` 內唯一 `getTimeInMillis` 寫入時鐘欄位（A3 的存在理由）。
  - `save` 掛鉤區段後恰好 `putFloat／put／putShort` 三筆寫入（B1 的格式前提）。
- 完整 build 與兩輪 install／uninstall 往返。

線上（部署後，照「量測／觀測作業鐵則」）：
- W32 觀測擴充（第一期），以每隻動物的離線段落配對記帳，只進 heartbeat 聚合。前提是 W42 開啟；W42 關閉時沒有結算點，段落帳本停用。
  - 開段：`fromWorker` 取出動物時，在委派前記下自身離線時數（待補）與入場類型（`connectedDZone` 是否為空）。之後這隻動物每次 `updateStatsAway`（`fromWorker` 或 `doMeta`）的實際補算都計入該段。
  - 結算點是動物時鐘被設成現在、這段待補從此不可能再補的時刻：入場後第一次 `liveHourGrow`（W42 在此刷新時鐘，之後 `doMeta` 經 W42 的 min 只看得到刷新後的時數）；或段落未結算就再次入場（交給 worker 的兩條路徑都先呼叫 `unloaded()`，把時鐘設成卸載當下）。結算值：未補＝max(0, 待補 − 補到)，按入場類型分開累計。
  - 動物在結算前死亡，另計段數與時數，不算丟失。
  - 截斷歸屬：結算時未補大於 0 的動物保留剩餘未補量。之後 `doMeta` 若被 W42 截斷，截掉的時數中不超過剩餘未補量的部分計為「截斷落在未補時數上」，並從剩餘量扣除；其餘計為去重。沒有剩餘未補量的動物，截斷全部計為去重。前者是原版沒有 W42 時 `doMeta` 可能補回的上限，不代表原版一定會補回。
  - 對帳：沒有未結算段落的動物若又有實際補算（例如雞舍內不經 `liveHourGrow` 的動物放出後被 `doMeta` 補），計入「未配對補算」時數，用來修正未補的估計。
  - 覆蓋範圍：heartbeat 印出目前未結算的段落數與待補總時數；開段總數減去已結算、死亡與目前未結算，就是因物件被回收而消失的段落數，一併印出。入場後不到一個遊戲小時就卸載、本次開機沒再入場的動物，段落會一直未結算。重啟前最後一次 heartbeat 的未結算量，是這次開機無法判定部分的近似值；它只涵蓋到該行輸出為止，不是關機時的精確結算。
  - 餘數：每次呼叫記錄委派前後 `hoursSurvived − age × 24` 的差，以最終值計（A2 啟用時為還原後），累計「餘數歸零實際丟失」。
  - 段落以 `WeakHashMap` 綁動物，不延長生命週期。開段、累計與結算都發生在既有掛點：W32 的 `fromWorker`、`doMeta` 入口與 `liveHourGrow`，死亡則由 W39 在 `IsoAnimal.OnDeath` 頭部的 `AnimalDeathLedger.onDeath` 通知結算；不需要新增掛點。死亡通知要放在 W39 自己的 kill switch 判斷之前，關掉 W39 不影響帳本。
  - 不能回答：重啟抹除（入場時的待補只從磁碟時鐘算起，卸載時刻已被原版存檔抹掉），要到 A3 上線後，或以 apop 快照差分估計。
- B1：新增 `[HookSaveGuard] kept= noCoords= ioFail= anomalies=`；`noCoords>0` 代表出現正常流程不該有的狀態，要人工調查。B2 觀測新增 `hookMissing=`。
- 用每 2 小時的 NAS 備份追蹤 `<cell-K>` 與另外 2–3 個活躍農場 cell 的推進率（同本檔 2.3 節方法）。這是觀測指標，不是預期值；第二期要不要做、上限取多少，依第一期的分路徑帳本與使用者對遊戲體驗的決定。
- 每日全服 apop `deathTime` 掃描：B1 上線後新增的活化屠體應為 0；`kept>0` 代表原版會寫錯的情況確實被攔下。
- 守門指標：整批 `fromWorker`／`doMeta` 的耗時與動物數（新增量測，不只看單隻 `maxMs`），以及 W39 的 `afterCatchUp` 死亡數、看門狗凍結次數，都不應上升。

## 6. 待使用者決定（2026-10-01 已決定，見檔頭）

1. 是否實作；若實作，第一期範圍是否就是建議的 B1（`VirtualAnimal.save` 改道＋W37 失敗處理；B1 依賴 W37 開啟）＋B3＋A2＋W32 觀測擴充。W37 的 `saveRealAnimalHack` 隱患即使不做 B1 也建議單獨修。
2. 第二期（A1、A3）要不要做，以及長離線上限。這個值決定「玩家離線期間動物成長多少、會不會餓死」。建議等第一期觀測數據出來再決定；若要先定，保守值可取 48 遊戲小時（本案重啟後的離線多在 60 小時以內）。
3. A0：MOD 更新重啟的節奏（例如累積到固定時段才重啟）。
4. A.4：已受影響的動物是否補回；若補，是否只補懷孕、只補玩家回報的個體。
5. B.3：3 隻活化屠體採 (a)／(b)／(c) 哪一種，是否通知各自的玩家。

## 附錄：資料與方法

- 證據（不進版控）：
  - `work/animal-eval-20260930/` 下的 `remote/backups/`：15 份 NAS 備份的 `<cell-K>` apop、2 份鉤子 chunk。
  - `remote/current/`：現況 apop、全服 3 個含 `deathTime` 的 apop、鉤子 chunk、部署核對、沙盒設定。
  - `remote/logs/`：09-29 18:00 起的 W32／W38 log、存檔事件、輪次邊界、玩家連線與動作。
  - `critic-report.md` 到 `critic-report-4.md`：四輪審查意見（前三輪 REVISE，第四輪 ACCEPT）。
  - 原報告的 6 份 apop 在 internal-analysis 的 evidence 目錄。
  - 所有遠端檔案都在遠端印出 sha256，取回後逐一核對相符。
- 解析器：`work/animal-eval-20260930/apop_parse.py`，依 `AnimalCell.load → AnimalChunk.load → VirtualAnimal.load → IsoAnimal.load` 的順序結構化解析。羊毛與產蛋兩個條件欄位按物種試解析，再以後續欄位的合理性裁決。21 份 `<cell-K>` 快照與 3 份含屠體的 apop 都完整讀到檔尾，沒有多餘位元組。
- 遊戲時間換算：
  - `GameTime.getWorldAgeHours` 從每日 7:00 起算，所以「日曆小時數（自 1970 起）− worldAgeHours」＝24k＋7；本存檔 k＝8589。apop 內每隻動物的時鐘欄位就是存檔當下的日曆毫秒，可直接換成 worldAgeHours。
  - 推進率只用快照內的存檔時刻，不依賴牆鐘。
- 牆鐘換算：
  - console 行首的 `st` 是單調毫秒計數，以含 ISO 時間的行校準。
  - 各輪遊戲時間速率以 W32 明細的 worldAgeHours 擬合，本服約 0.30–0.38 遊戲小時／分鐘，低於 DayLength 名目值 0.4。
  - 快照對應哪一次存檔有約 ±8 小時的不確定性，只用於時序判定。
- 限制：
  - W32 明細只記差距 ≥24 小時或死亡，無法得到每一次補算的時數，也無法辨識 W42 誤截。
  - 載入時段由 Player-K 的 log 推定；其他玩家或機制造成的載入無 log 可查，已知至少有一次（2.4 節）。
  - 屠體在寫 0 之前是否進過世界，現有資料無法判定；B2 觀測只能回答「鉤子格已載入卻沒有鉤子」這一種。
