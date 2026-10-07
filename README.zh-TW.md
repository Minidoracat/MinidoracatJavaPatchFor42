# MinidoracatJavaPatchFor42

[English](README.md) | **繁體中文**

**Project Zomboid Build 42**（目前對應 **42.21.0**）的 bytecode 修補：修正我們經營高人數多人伺服器時找到的原版
bug 與效能熱點，另有給玩家選裝的客戶端修補包。

- **伺服器修補**：dedicated server 的 loose `.class` 覆蓋，處理主迴圈凍結與活鎖、chunk／動物／車輛遺失、主執行緒熱點、頻寬迴圈、卡讀條與 log 噪音。42.21.0 共 65 個 patched class、172 個命中點、62 個 helper class。
- **客戶端修補**：玩家選裝的修補包（隊友／殭屍／車輛隱形、42.21.0「自建房間」繪製 bug、模組車停著也每幀重算動畫），從
  [Releases](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases) 下載。
- **Native 防護**：Linux dedicated server 上兩個 native 崩潰的 `LD_PRELOAD`／`LD_AUDIT` shim。

每一項修補都針對一個具體的原版缺陷或實測熱點，並附完整根因分析：[docs/patches.md](docs/patches.md)（繁體中文原文，最詳細）與 [docs/patches.en.md](docs/patches.en.md)（英文版）。

> **給 The Indie Stone 開發團隊**
>
> 以下每一項都是在正式服上用 thread dump、JFR、心跳計數器與封包擷取定位出來的，每個修正都逐指令對照過官方
> `projectzomboid.jar`。其中幾項已在 42.20.2–42.21.0 由官方修掉（見[官方已修](#官方已修)），謝謝。表格的
> **TIS** 欄標出哪些已在論壇回報、哪些還沒回報。任何一項都可以提供 log、封包統計或測試版本：請在這裡開
> issue，或在論壇聯絡 **Minidoracat**。

## 目錄

- [為什麼做這些](#為什麼做這些)
- [修補目錄](#修補目錄)：[穩定性](#穩定性凍結活鎖與崩潰)・[資料完整性](#資料完整性chunk動物與車輛遺失)・[效能](#效能主執行緒耗時)・[網路](#網路頻寬與重送迴圈)・[多人同步正確性](#多人同步正確性)・[帳號與濫用](#帳號與濫用)・[log 噪音](#log-噪音)・[觀測](#觀測)・[客戶端](#客戶端修補發布包)・[Native](#native-防護linux-dedicated-server)
- [官方未修的已知問題](#官方未修的已知問題)
- [官方已修](#官方已修)
- [修補原理](#修補原理)
- [伺服器：建置與部署](#伺服器建置與部署)
- [客戶端修補：安裝](#客戶端修補安裝)
- [建議的伺服器設定](#建議的伺服器設定)
- [選用工具](#選用工具)
- [專案結構](#專案結構)
- [文件](#文件)
- [支持作者](#支持作者)・[授權與免責](#授權與免責)

## 為什麼做這些

我們的伺服器每日尖峰 68–95 人同時在線（254 個名額）。dedicated server 的主迴圈是單執行緒、上限 10 FPS，每一毫秒都很重要。每一項修補都起源於一次正式服事故或一個 profiler 熱點。部分成效：

| 項目 | 修補前 | 修補後 |
|---|---|---|
| console 噪音 | 單是 `Send Toxic Building` 就佔全部 console 行數的 45.5% | 歸零；其他已知噪音也過濾，反作弊警告照常輸出 |
| chunk 損毀（Blam 抹除） | 43 個玩家建造的 chunk 在 `SANITY CHECK FAIL` 後被抹除 | 上線第一晚擋下 8 筆損毀寫入、零損失；根因修正後 290 萬次寫入零攔截 |
| 主迴圈凍結 | 13 分鐘與 114 分鐘凍結、全服活鎖、5–16 秒停頓 | 守衛上線後未再發生 |
| 動物聽覺掃描 | 每幀 4.34 ms（主執行緒 4.3%） | 0.69 ms（0.6%） |
| 動物視線 | 約 50 人時每幀約 16 ms（主迴圈約 9%） | 空間預篩、結果與原版相同（基準每幀 4.8 ms → 0.25 ms） |
| `processItems` 登記 | 約 3 萬件的清單，每次登記都線性掃描（約佔主執行緒 6%） | 身分索引（基準 43 µs → 0.3 µs） |
| 動物完整快照 | 佔出向流量 38–40%，其中 87% 是重送 | 半徑對齊 client 已載入範圍、每隻動物冷卻 |
| `VehicleCollide` 迴圈 | 單一 client 每秒 239 包，2 小時 18 萬行警告 | 每秒 9.8 包 |
| 製作站廣播 | `GameEntity` 封包佔出向 UDP 17.5% | 只送附近玩家，且只在畫面狀態改變時送 |

約 63 人在線時，主迴圈維持 9.4–10.1 FPS，所有修補計數器異常數皆為 0。

## 修補目錄

狀態欄以 42.21.0 的預設值為準。每項伺服器修補都有 `-Dmdc.*` 開關可回原版（見各節）。**TIS** 欄：編號連到我們的論壇回報；「草稿」表示回報已寫好但還沒發；「未回報」表示還沒寫。

### 穩定性：凍結、活鎖與崩潰

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| W5 容器環守衛（[2q](docs/patches.md#2q)） | 多人搬移可以把容器放進自己的子孫容器裡，`ItemContainer.getCharacter()` 因此無限遞迴，`StackOverflowError` 讓主迴圈死掉（無聲凍結 13 分鐘）。 | 在找擁有者的路徑上偵測環（identity 路徑＋深度保險絲），切斷並記錄環的位置。2026-08-13 以來未再發生。 | [#100891](https://theindiestone.com/forums/topic/100891/) |
| W6 地圖格載入捕手（[2r](docs/patches.md#2r)） | `IsoChunk.doLoadGridsquare` 裡的 `IsoObject.addToWorld` 丟出 "Entity is already registered"，該格一直留在待載入佇列，每 0.1 秒重撞一次：活鎖 114 分鐘。 | 在兩處 `addToWorld` 攔截，記下座標與 sprite 後跳過該物件。殘留的重複登記每天仍有幾次，但不會再讓伺服器凍結。 | [#100893](https://theindiestone.com/forums/topic/100893/) |
| W11 動物聲音排序（[2y](docs/patches.md#2y)） | `BaseAnimalSoundManager` 排序用的比較器會即時重算距離，遇到 NaN 就違反比較契約；TimSort 丟例外、`clear()` 被跳過，陳舊清單每幀都失敗：全服卡讀條、時間停止。 | 攔下例外，該幀跳過排序讓 `clear()` 照常執行，並記錄 NaN 來源。 | [#100897](https://theindiestone.com/forums/topic/100897/) |
| W37 動物半建構物件（[2az](docs/patches.md#2az)） | `IsoAnimal` 建構子的檢查失敗時，物件已經放進 cell，`adef`／`data` 為 null，之後每個 tick 都丟例外（遊戲時間凍結約 65 分鐘）。存檔先開 `apop_*.bin` 才序列化，一失敗就留下 0 bytes 檔、動物全失。 | 建構子返回前撤出失敗的物件；先序列化，成功才寫檔。 | 草稿 |
| W40／W41 `processItems`（[2bc](docs/patches.md#2bc)、[2bd](docs/patches.md#2bd)） | 載車的背景執行緒直接寫 `IsoCell.processItems`，混進 null 後 `ProcessItems` 每 5 秒丟例外、清單不再縮減，chunk 載入時的線性 `contains` 讓伺服器凍結 5–16 秒（FPS 9.8 → 2–3）。 | W40 同一幀略過並移除 null；W41 把非主執行緒的登記排入佇列，由主執行緒補登記。 | 草稿 |
| hit 封包 null 守衛（[2c](docs/patches.md#2c)） | `hit/Zombie.process` 與 `hit/Fall.process` 會解參考損壞封包留下的 null 欄位。 | 在呼叫 super 之前加 null 守衛。 | 未回報 |
| W15 主迴圈看門狗（[2ac](docs/patches.md#2ac)） | 凍結當下沒有任何 stack 可查。 | 純觀測：超過 5 秒沒有新的一幀就對主執行緒拍 stack。結果顯示剩下的凍結都來自同步的 `QueuedSaveAll`。 | 建議 [#100925](https://theindiestone.com/forums/topic/100925/) |

### 資料完整性：chunk、動物與車輛遺失

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| W7 朝向暫存執行緒隔離（[2s](docs/patches.md#2s)） | `IsoGameCharacter.setForwardDirectionFromIsoDirection` 用共用的 static `tempVector2_2` 當暫存；chunk 載入執行緒與主迴圈同時使用時讀到零向量，chunk 載入中止，原版把整個 chunk 抹除重生（一位玩家的雞舍連 32 隻家禽整組消失）。 | 兩處讀取改成執行緒私有向量（堆疊形狀不變，不動 frame）。 | [#100895](https://theindiestone.com/forums/topic/100895/) |
| W8 chunk 寫入閘（[2t](docs/patches.md#2t)） | 43 個玩家建造的 chunk 在 `SANITY CHECK FAIL` 後被抹除；鑑識證明檔案寫入時就已帶錯誤或為 0 的 CRC。 | 在 `SafeWrite` 截斷舊檔之前先驗長度與 CRC，損毀就擋下、保留舊檔並記錄 stack。上線第一晚擋下 8 筆損毀寫入，零損失。 | [#100901](https://theindiestone.com/forums/topic/100901/) |
| W9 存檔管線隔離（[2u](docs/patches.md#2u)） | 存檔執行緒共用 `CRC32` 實例而產生競態（W8 損毀 header 的根因），存檔管線還和發送執行緒共用全域 chunk 池。 | CRC 部分已在 42.21.0 由官方修掉並退役；存檔管線的私有 chunk 池保留。修正後 290 萬次寫入零攔截。 | [#100901](https://theindiestone.com/forums/topic/100901/)（部分已修） |
| W12 車輛 chunk 索引（[2z](docs/patches.md#2z)） | `VehiclesDB2$VehicleBuffer.set` 從陳舊或回收池裡的 `vehicle.chunk` 取 `wx`／`wy`，`x`／`y` 卻來自物理位置；資料列存進錯的 chunk，車子再也載不回來。 | 改由車輛位置推算 `wx`／`wy`（本服每週約修正 180 次）。 | [#100913](https://theindiestone.com/forums/topic/100913/) |
| W17 雞舍載入（[2ae](docs/patches.md#2ae)） | `IsoHutch.load` 丟掉 `addAnimalInside` 的回傳值；雞舍快滿時，動物在載入時被靜默銷毀。 | 改找空位放入；只有真的滿舍才記一行 critical。 | [#100907](https://theindiestone.com/forums/topic/100907/) |
| W38 畜牧區補算快照（[2ba](docs/patches.md#2ba)） | 離線補算迴圈用索引走 `zone.animals`，補算過程又把動物移到清單尾端，有的補算兩次、有的被跳過；加倍的飢渴與年齡是多數異常死亡的主因。 | 本次補算改走一份快照。 | 草稿 |
| W42 補算時數上限（[2be](docs/patches.md#2be)） | 離線時數取自圈區上次離開串流的時間；大圍場只有部分重載時這個時間是陳舊的，動物被多補算好幾天（某 session 38 隻死亡中有 25 隻發生在補算後 60 秒內）。 | 補算不超過動物自身的離線時間，動物活著時每小時更新自身時鐘。 | 草稿 |
| W49 動物離線補算根治（[2bm](docs/patches.md#2bm)） | 同一群動物的懷孕與成長只有遊戲時間的五到六成：存檔的時鐘欄位寫存檔當下，重啟前的離線時間永遠補不回來；開機後首次載入的動物補 0 小時；每次補算丟掉最多 23 小時的累積；補算按午夜、載入中按 24 小時兩種成長語意混用。 | 以動物自身時鐘補算（已卸載的動物把時鐘存進存檔，載入中與雞舍內每小時刷新），成長與載入中一樣每累積 24 小時一次；一次離線最多補 168 遊戲小時（可調）；掛鉤屠體不補算。 | 未回報 |
| W50 掛鉤屠體存檔（[2bn](docs/patches.md#2bn)） | 從磁碟載入的屠體要到第一次更新才找回鉤子；這之前存檔就寫成 onHook=0，屠體下次載入變成活動物。 | 依屠體載入時還原的座標寫出掛鉤狀態（與原版掛鉤格式相同），絕不寫出已知錯誤的紀錄；apop 存檔失敗重試時不再把世界中的動物寫兩次。 | 未回報 |
| W56 補算等畜牧區就緒（[2bt](docs/patches.md#2bt)） | chunk 載入時就補算離線時間，這時槽還沒登記回畜牧區（卸載時移除，只有整區載入的 `check()` 放回），補算吃不到槽；原版靠畜牧區整區載入時再補一次（重複補算）才餵到，W42 拿掉重複後，玩家回來時家畜就像離線期間沒吃沒喝。 | 載入時先凍結、不補，等相連畜牧區的 chunk 都載入、重建槽與地上食物後再補（通常同一幀，最多等 10 秒）；槽一進世界就登記回畜牧區；延後中被卸載、抱起或換成新物件都照常接手。 | 未回報 |
| W33 分娩品種守衛（[2av](docs/patches.md#2av)） | 查不到品種或幼崽定義的分娩仍會呼叫 `IsoAnimal` 建構子。 | 略過那一胎並記錄。 | 未回報 |
| W43／W44 地面物品過期與交易失敗（[2bg](docs/patches.md#2bg)） | client 每次載入 chunk 都會清掉過期的地面物品，伺服器在 chunk 常駐期間卻從不清；兩邊的物品序號錯位，出現撿不起來的幽靈物品，而撿取失敗又不回報，讀條要空等時長再加 10 秒。 | 伺服器送出 chunk 前以相同規則清掉過期地面物品；交易失敗立即回報拒絕。 | 未回報 |

### 效能：主執行緒耗時

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| entity 移除索引化（[2g](docs/patches.md#2g)） | 批次卸載 chunk 時，每個 entity 都對全域陣列做一次 identity 線性搜尋。 | weak-key＋primitive sidecar index：每個 entity 439 → 63 ns。 | 未回報 |
| W1-1 車輛視線預篩（[2k](docs/patches.md#2k)） | `IsoZombie.isVehicleBetween` 對每隻殭屍、cell 裡的每台車都做完整 OBB 相交。 | 先做保守的包圍球測試，拒絕 99.87%；車輛主題從低 FPS 的 dump 中消失。 | 未回報 |
| W3 波（[設計文件](docs/wave3-design-v1.md)） | 已有擁有者的殭屍每個 tick 都重跑擁有權選舉（O(連線 × 玩家)）；動物對 cell 內每隻殭屍呼叫 `spotted()`，但效果只在 10 格內；車輛在伺服器上算玩家可見度，結果只餵給一個空操作。 | 每 3 個 pass 才重選；跳過遠距 `spotted()`（攔截 99.94%）；直接略過白算的工作。 | 未回報 |
| W18／W18-2 動物視線節流（[2af](docs/patches.md#2af)） | `IsoAnimal.updateLOS` 每個 tick 走完整份物件清單；67 人時佔主執行緒取樣的 41.7%。 | 輪轉節流（N 可調），並以結果相同的較便宜掃描處理同一組候選。 | 未回報 |
| W47 動物視線空間預篩（[2bj](docs/patches.md#2bj)） | W18-2 之後每次視線檢查仍要走約 4,300 個物件：約 50 人時每幀約 16 ms（主迴圈約 9%）。 | 每幀建一次殭屍網格；結果與原版相同（4,000 個隨機世界差分）；基準每幀 4,780 → 約 250 µs。 | 未回報 |
| W48-2 動物聽覺索引（[2bk](docs/patches.md#2bk)） | 每隻動物每個 tick 都掃過整份全域聲音清單（約 4,300 個、範圍內平均約 12 個）。 | 結果相同的空間索引（每 256 次比對一次原版；13 萬次比對零不一致）：每幀 4.34 → 0.69 ms。 | 未回報 |
| W45 `processItems` 身分索引（[2bh](docs/patches.md#2bh)） | 每登記一件物品都要對約 3 萬件的清單做線性 `contains`。 | 帶身分索引的清單，順序與內容不變；每件登記 43 → 0.3 µs（基準）。 | 草稿 |
| W25 序列化物件池隔離（[2am](docs/patches.md#2am)） | `SaveAll` 的 worker 有 80% 的取樣卡在 `BitHeader` 與 `ByteBlock` 的 `ConcurrentLinkedDeque` 池競爭。 | 改為執行緒私有池：4 執行緒時每次 1,095 → 38 ns（29 倍）。 | 未回報 |
| W35 使用中玩家索引（[2ax](docs/patches.md#2ax)） | `UsingPlayerUpdateSystem` 每幀掃過所有 IsoObject entity（JFR 5.9%），只為了在玩家走遠時清掉 `usingPlayer`。 | 只追蹤有 `usingPlayer` 的物件，並定期全表稽核。 | 未回報 |
| W34 聲音參數（[2aw](docs/patches.md#2aw)） | 伺服器替沒人讀的 dummy emitter 計算 FMOD 腳步參數（1.6%）。 | 伺服器上略過。 | 未回報 |
| W4-1 chunk 請求併包（[2p](docs/patches.md#2p)） | 每位玩家的 chunk 供給上限是主迴圈 FPS × 一列 chunk；3 FPS 時開車會超過供給（黑邊）。 | 把佇列中的請求併成較大的批次（預設 observe）。 | 未回報 |
| W53 步行時縮小車輛相關範圍（[2bq](docs/patches.md#2bq)） | client 對每台已載入的車每幀重算所有蒙皮零件的骨架（KI5／rSemiTruck 系每台 22–47 µs），而伺服器的車輛相關範圍（1080p ±88 格）比 client 自己的 chunk map 還寬，車多的地方載入上百台、每幀數毫秒。 | 步行時只送、只留圓形 R 格內的車（預設 64，結果一律是原版的子集）；有人在車內或加入中照原版。預設 observe 只計數；正式服快照中 107 台的地點，R=64／48 剩 57／28 台。 | 未回報 |

### 網路：頻寬與重送迴圈

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| W13／W14 動物同步範圍與冷卻（[2aa](docs/patches.md#2aa)、[2ab](docs/patches.md#2ab)） | 動物 relevancy 半徑是 client 保證載入半寬的 10/8，請求又沒有冷卻與範圍檢查；client 不停要求放不進世界的動物完整快照：佔出向流量 38–40%，其中 87% 是重送。 | 半徑對齊 client 已載入範圍；每條連線每隻動物在冷卻內只送一次完整快照；請求做範圍檢查。 | [#100915](https://theindiestone.com/forums/topic/100915/) |
| W36 `GameEntity` 廣播範圍（[2ay](docs/patches.md#2ay)） | 運轉中的製作站（例如曬草架）每秒把整份 `CraftLogic` 狀態廣播給全服：佔出向 UDP 17.5%。 | 只送範圍內的連線，且只在畫面上的進度或狀態改變時送。 | 草稿 |
| W26 雞舍同步收件人（[2an](docs/patches.md#2an)） | 雞舍的自發同步送給全服每位玩家。 | 只送給已載入範圍涵蓋該雞舍的玩家。 | 未回報 |
| W31 魚群廣播（[2as](docs/patches.md#2as)） | `transmitFishingData` 對每條連線重新序列化同一份內容。 | 同一批只序列化一次並重用。 | 未回報 |
| W46 `VehicleCollide` 重送授權（[2bi](docs/patches.md#2bi)） | 伺服器在同一幀處理完撞車申請與歸還時，每連線快取沒有變化，就永遠不會糾正 client，client 每幀持續送歸還：每秒 239 包、2 小時 18 萬行警告。 | 收到歸還時把該連線的授權快取標為失效，下一輪同步必定送回真正的授權：每秒 9.8 包。 | 未回報 |

### 多人同步正確性

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| W10 卡讀條（[2x](docs/patches.md#2x)） | 封包參數反序列化成 null 時，Lua 動作建構子在 `processServer` 之前就丟例外；client 既收不到 Accept 也收不到 Reject，整條動作佇列卡死。 | 在建構子與參數解析外加保險絲，client 會收到正確的 Reject。（從錯的物件寫出 Reject 的 W10-A 已在 42.21.0 由官方修掉。） | [#100905](https://theindiestone.com/forums/topic/100905/) |
| W10-C 被打斷的動作（[2aj](docs/patches.md#2aj)） | 新的請求會在伺服器上靜默停掉玩家已被接受的動作，卻不通知 client。 | 對被打斷的動作補送 Reject（enforce 模式），並且只派送玩家屬於發送連線的動作封包。 | 草稿 |
| W20 衣物同步（[2ah](docs/patches.md#2ah)） | 一件 visual 為 null 的穿戴物會讓 `ItemDescription` 丟例外，該玩家的衣物廣播全部停擺；數量不符時整個 `SyncVisuals` 封包被丟棄。 | 守住 tint 讀取；另外兩個叢集加觀測。 | [#100911](https://theindiestone.com/forums/topic/100911/) |
| LootRespawn 固定容器（[2e](docs/patches.md#2e)） | 沒有原版 TownZone 的自訂地圖上，原生固定容器永遠不刷新。 | 只對未搬動的原生固定容器放行的窄範圍 fallback。 | 設計問題 |
| 動物壓力調整（[2b](docs/patches.md#2b)） | 玩法調整，不是 bug。 | 閒置壓力衰減 ×2、聲音壓力 ÷3、屠宰連鎖上限減半。 | — |

### 帳號與濫用

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| W23 每個 Steam ID 帳號上限（[2ak](docs/patches.md#2ak)） | `MaxAccountsPerUser` 只在建立新帳號時檢查，既有帳號從不受限。 | 在登入時執行上限，保留最近使用的帳號。屬伺服器政策。 | 未回報 |
| W52 分割畫面與重生名稱（[2bp](docs/patches.md#2bp)） | `ConnectCoopPacket` 採用封包裡的名稱，只擋空字串與在線同名。分割畫面玩家（原版介面）或重生的主玩家（改過的客戶端 Lua，不受 `AllowCoop` 影響）能換成離線玩家的名字，通過以名字認人的安全屋、陣營與 MOD 檢查，期間本人登不進來。 | 0 號一律用連線登入的帳號；1–3 號分割畫面玩家不能用任何已有帳號的名稱（在任何狀態改變前拒絕）。 | 草稿 |
| W29 動物同步驗證（[2aq](docs/patches.md#2aq)） | client 送往伺服器的動物同步封包有驗證缺口；細節會私下回報 TIS，不在此公開。 | 在任何遊戲狀態改變前驗證並丟棄不合法封包。 | 將私下回報 |
| W19 車輛永久移除帳本（[2ag](docs/patches.md#2ag)） | 好幾條 Lua 路徑都能永久刪除車輛，卻沒有任何稽核紀錄。 | 每次永久移除都記錄呼叫來源與認領狀態（純觀測）。 | 未回報 |

### log 噪音

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| log 噪音過濾（[1](docs/patches.md#1)、[2v](docs/patches.md#2v)、[2bf](docs/patches.md#2bf)） | 已知警告洗版（例如原版物件的 `Invalid SpriteConfig object!`）；由 mod 觸發的 `Send Toxic Building` 佔全部 console 行數的 45.5%。 | 只攔完全相符的已知訊息，反作弊與未知警告照常輸出；封包完全不動。 | [#100917](https://theindiestone.com/forums/topic/100917/)（42.21.0 已部分修掉） |

### 觀測

不改任何行為的觀測探針：主迴圈看門狗（[2ac](docs/patches.md#2ac)）、動物離線補算量測（[2au](docs/patches.md#2au)）、動物死亡帳本（[2bb](docs/patches.md#2bb)）、動物 ID 解析失敗紀錄（[2bo](docs/patches.md#2bo)）與聲音封包慢呼叫觀測（[2at](docs/patches.md#2at)）。它們在 console
寫限流的心跳行，上面多數問題都是靠它們找到的。

### 客戶端修補（發布包）

| 修補 | 原版哪裡出錯 | 修補做什麼 | TIS |
|---|---|---|---|
| 貼圖管線門檻與洩漏修復（[2j](docs/patches.md#2j)） | 已解碼、尚未上傳的貼圖緩衝累積到 50 MB 時，貼圖載入執行緒會永久睡眠；洩漏（APNG 影格、64 MB 備用緩衝）把地板墊過門檻：玩家、殭屍、車輛只剩影子和名牌。 | 修掉洩漏；標準版另把等待門檻放寬到 4 GiB。隱形問題不再復發。 | [#100919](https://theindiestone.com/forums/topic/100919/) |
| chunk 串流紀錄（[2o](docs/patches.md#2o)） | 黑邊發生時 client 端沒有任何證據。 | 記錄串流狀態與停滯；不改行為。 | — |
| 自建房間 XL 樹修正（[2bl](docs/patches.md#2bl)） | 42.21.0：只要 `isInARoom()` 為真，`IsoTree.isPlayerInsideARoom` 就直接讀 `getRoom().getRectsBounds()`；但沒有 `IsoRoom` 的封閉自建房間 `isInARoom()` 也為真。NPE 中斷整幀繪製：家具、樹、圍籬消失。 | 這種房間在 XL 樹淡化判斷裡視為「不在房間內」。 | [#101887](https://theindiestone.com/forums/topic/101887/)（官方已內部修正） |
| W54 車輛靜止姿勢跳過（[2br](docs/patches.md#2br)） | 每台已載入的車每幀對每個蒙皮零件模型無條件 `AnimationPlayer.Update`，不分遠近、停著或看不看得到；窗與裝甲共用門的 player，同一副骨架一幀算 2–3 次。門窗會動的模組車（KI5、rSemiTruck）每台每幀 22–47 µs，原版車 3–5 µs。 | 沒有 track 在播放、姿勢輸入與上一次完整計算相同時跳過 Update，renderer 沿用上一次的矩陣；每 256 次跳過抽查一次完整重算，不一致就本次回原版。合成測試中每幀姿勢與原版逐位相同。 | 未回報 |
| W55 `isBoneReparented` 快速路徑（[2bs](docs/patches.md#2bs)） | 骨架更新每根骨頭呼叫一次 `isBoneReparented`，每次都從池配置一個 lambda 再掃清單；車輛、殭屍與多數角色的清單是空的。 | 清單為空直接回 false，否則照原版；結果逐位相同，所有角色與車都受惠。 | 未回報 |

### Native 防護（Linux dedicated server）

| 防護 | 原版哪裡出錯 | 防護做什麼 | TIS |
|---|---|---|---|
| pfguard（[設計文件](docs/pathfind-aligned-block-guard-design-v1.md)） | `libPZPathFind64.so`：`VehicleRect` 池的槽位被寫壞，在 `PolygonalMap2::createVehicleClusters` SIGSEGV；`VehicleCluster::merge` 從不歸還被合併的 cluster（每秒約洩漏 40 塊）。 | 用 `LD_PRELOAD` 在該配置器家族周圍加 guard page（第一次錯誤寫入就當場 fault），並把被合併的 cluster 還回池。啟動時核對函式庫雜湊，不符即停用。 | 崩潰：[#100921](https://theindiestone.com/forums/topic/100921/)；洩漏：草稿 |
| steamfix | Valve 的 `steamclient.so` PseudoTCP：部分 ACK 只縮短傳送片段、卻沒推進起始序號，重傳時越界讀取。 | 選用：以 `LD_AUDIT` 在已載入的函式庫中修正那一個區塊（磁碟上的檔案不變），只對確切的 Steam build 生效。 | Valve 函式庫 |
| 啟動閘（[run-with-pfguard.sh](native-observer/deploy/run-with-pfguard.sh)） | 遊戲自動更新後，殘留的舊 loose class 讓伺服器起不來，或在新 jar 上靜默混跑。 | 每次開服重驗 payload SHA 與 jar 同源；不符就把修補移到一旁、以原版啟動。 | — |

## 官方未修的已知問題

已查出原因、但我們沒有修補的原版問題。回報放在 [docs/report/](docs/report/)，送出前都是草稿。

| 問題 | 原版哪裡出錯 | 玩家可以怎麼做 | TIS |
|---|---|---|---|
| 駕駛中的車被留在已卸載的 chunk 上（[回報](docs/report/2026-10-01-vehicle-orphaned-chunk-river-warp-tis.md)） | client 卸載 chunk 時，車上有本機玩家的車移不掉，也沒有被移到已載入的 chunk。這台車之後不再回報位置、也不更新聲音（引擎聲「消失」）。等伺服器記的舊位置落到 client 的載入範圍外，一則車輛更新就會把駕駛拉回那個舊位置，車可能因此掉進水裡：下不了車，畫面一直全黑。 | 開車時引擎聲突然消失或車子自己煞停，就沿原路倒退，等引擎聲回來再開。已經卡在水裡就重登幾次（每次重登車都往岸邊移幾格），或請管理員移位。 | 草稿 |
| 有雞舍的 chunk 卸載時被斷線踢回主選單（[回報](docs/report/2026-10-03-hutch-removefromworld-npe-tis.md)） | 42.21.0：`IsoHutch.removeFromWorld` 對 `animalInside` 的每個值呼叫 `removeFromUpdateLists()`，但 client 自己的動物同步會把 `null` 寫進這個 map（例如母雞進巢箱下蛋時）。client 卸載那個 chunk 時（走開、開車離開或傳送），NPE 一路拋到 `IngameState.updateInternal` 的 catch，client 被斷線送回主選單。已在本機伺服器實機重現。 | 重登就能回到遊戲。我們的 Lua 修復 MOD 會在 client 清掉這些空格（[MinidoracatFixesFor42](https://github.com/Minidoracat/MinidoracatFixesFor42) 的 `MDFX_HutchNullSlotGuard`，Workshop 42.21.0-0.13.0 起）。 | 草稿 |

## 官方已修

因官方修掉底層問題而退役的修補：

| 退役的修補 | 官方修正版本 | 我們的回報 |
|---|---|---|
| popman 共享 buffer 競態（[2h](docs/patches.md#2h)） | 42.20.2（`readByteBuffer`） | — |
| `IsoCell` 清單成員 sidecar（[2m](docs/patches.md#2m)） | 42.20.2（高人數 chunk 卸載修正） | — |
| `VehicleManager` 512 → 256 連線槽（[2k](docs/patches.md#2k)） | 42.20.2 | — |
| `WorldStreamer` 8 秒請求逾時活鎖（[2p](docs/patches.md#2p)） | 42.20.3（`ChunkNotReady`） | — |
| 玻璃移除無限迴圈（[2l](docs/patches.md#2l)） | 42.21.0 | [#100899](https://theindiestone.com/forums/topic/100899/) |
| `faceThisObject` null 解參考（W22，[2ai](docs/patches.md#2ai)） | 42.21.0 | [#100909](https://theindiestone.com/forums/topic/100909/) |
| 讀條 Reject 從錯的物件寫出（W10-A，[2x](docs/patches.md#2x)） | 42.21.0 | [#100905](https://theindiestone.com/forums/topic/100905/) |
| chunk 存檔管線共用 `CRC32`（W9，[2u](docs/patches.md#2u)） | 42.21.0 | [#100901](https://theindiestone.com/forums/topic/100901/) |
| `IsoThumpable not found` console 洗版（[1](docs/patches.md#1)） | 42.21.0 | [#100917](https://theindiestone.com/forums/topic/100917/) |
| Java 格式字串裡的 `%ld`（W24，[2al](docs/patches.md#2al)） | 42.21.0 | — |
| `RequestData` ACK 迴圈邊界（W27，[2ao](docs/patches.md#2ao)） | 42.21.0 | — |
| PopMan 缺格生成與背景存檔互斥（W28，[2ap](docs/patches.md#2ap)） | 42.21.0 | — |
| `AnimationSet`／`ActionStateContainer` log 噪音（[1](docs/patches.md#1)） | 42.20／42.21.0 | — |

## 修補原理

dedicated server 的 classpath 把 `java/.` 排在 `java/projectzomboid.jar` 前面，同路徑的 loose `.class` 會覆蓋 jar
裡的類別。patcher 用 ASM 直接讀 jar 裡的原版類別並就地修改，不反編譯、也不重新編譯原始碼。

- **只做堆疊形狀不變的手術**：改道到堆疊效果與指令長度相同的 static helper、方法內常數替換、線性的頭部／尾部呼叫。Stack map frame 原樣保留（`ClassWriter(0)`）；helper 是對遊戲 jar 正常編譯的 Java 類別。
- **逐方法命中數**：每個手術點都宣告必須命中幾條指令。遊戲更新後手術點移動或改變，建置直接失敗，而不是改錯地方。
- **原版前提檢查**：`SmokeCheck` 釘住每項修正所依賴的結構事實（例如「這個呼叫沒有檢查 null」）。官方修好 bug 時對應的檢查會轉紅，提醒我們退役該修補。
- **驗證**：`LoadCheck` 以 `-Xverify:all` 載入每個類別，`BytecodeVerify` 跑 ASM 的資料流驗證，行為測試在裸 JVM
  中直接跑遊戲的真實類別。
- **開關**：每項伺服器修補都讀一個 `-Dmdc.*` 屬性（`0`／`off` 回原版；很多項另有只量測、不改行為的 `observe` 模式）。
- **失敗即停的安裝**：payload SHA、jar 同源與不明 loose class 巡檢三道閘都要通過。開服時印出版本橫幅（`server patch <commit> … jar=<sha>`）。

## 伺服器：建置與部署

需求：JDK 25（42.21 的 jar 是 class file 版本 69）與伺服器的 `projectzomboid.jar`。

```powershell
copy <serverfiles>\java\projectzomboid.jar work\
.\build.ps1        # 手術 → 命中數 → 連結、bytecode、行為檢查 → dist/
wsl bash patcher/tests/install-roundtrip.sh   # 在暫存 serverfiles 上跑兩輪安裝／移除
```

```bash
# 把 dist/ 複製到伺服器，在 serverfiles/java 內執行：
bash install.sh    # payload SHA、jar 同源、衝突三道閘；下次重啟生效
bash uninstall.sh  # 下次重啟回到原版
```

**每次遊戲更新前都要先執行 `uninstall.sh`。** loose class 不在 Steam depot 裡，更新只會換掉 jar，舊的 patched class
仍會留著。對新 jar 重新建置，並用 `javap` 確認每個手術點的語境（命中數對不代表意思沒變），再重新安裝。建置檢查與部署後驗證清單見 [docs/patches.md §3](docs/patches.md#3)。

## 客戶端修補：安裝

給玩家使用。從 [Releases](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases) 下載 `MinidoracatClientPatches-<遊戲版本>-<包版本>.zip`，並與
`SHA256SUMS.txt` 比對（`Get-FileHash .\MinidoracatClientPatches-*.zip`）。

1. 關閉遊戲，把整個 zip 解壓縮到任何資料夾（桌面、下載都可以，不必放進遊戲目錄）。安裝程式會自己透過 Steam
   找到遊戲目錄，遊戲裝在別的硬碟也找得到；找不到時才會請你貼上路徑（也可以用 `-GameDir "<路徑>"` 指定）。
2. 執行 `Install-Patches.bat`，輸入 `1`（安裝或更新），再輸入 `1`（客戶端修復）。
3. 直接按 Enter 選建議的版本（32GB 以上 RAM 選標準版，其餘選省記憶體版），再輸入 `Y` 確認。

安裝器跟著 Windows 顯示語言（繁體中文或英文），主選單按 `L` 或執行 `Install-Patches.bat -Lang en` 可切換。它只接受對應的遊戲版本：會比對 `projectzomboid.jar` 與每個寫入檔案的 SHA-256，不碰不屬於自己的檔案，中斷的安裝也能復原。**每次遊戲更新前都要先執行 `Uninstall-Patches.bat`（選 `A`）**，所以解壓縮出來的資料夾請留著（刪掉了就重新下載同一個 zip）。忘了先移除、遊戲更新後開不起來時，關閉遊戲再執行 `Uninstall-Patches.bat`（選 `A`）即可；只附 `uninstall.bat` 的舊版 TexPipeline v3.0 也認得，直接安裝新版或移除都會清乾淨。

| 模組 | 用途 |
|---|---|
| `core` | 共用 Lua bridge、安裝指紋驗證與啟動狀態；自動安裝 |
| `client-fixes-standard` | 貼圖洩漏修復、4 GiB 貼圖等待門檻、chunk 串流紀錄、XL 樹房間修正、車輛動畫加速（32GB 以上 RAM） |
| `client-fixes-lowmem` | 同一組修正，保留原版 50 MiB 貼圖門檻；與標準版互斥 |
| `profiler` | 給模組開發者的 DevProfiler（Java → Lua 呼叫計時、CSV／JFR 匯出）；介面是另一個 mod `MinidoracatDevProfilerFor42` |

也可以用 `build-client.ps1` 自行建置。安裝器測試：`powershell -NoProfile -ExecutionPolicy Bypass -File patcher/tests-client-installer/Run-InstallerTests.ps1`（Project Zomboid 的 JVM 執行中不要跑）。

## 建議的伺服器設定

不是修補，但屬於同一條調校線：

| 設定 | 變更 | 原因 |
|---|---|---|
| `PingLimit` | 400 → 800 | 登入與重連風暴時的 ping 踢人從每天 18 次降到 5 次 |
| `SaveWorldEveryMinutes` | 15 → 60 | 高人數時每次全服存檔會讓 client 暫停 5–6 秒 |
| `BackupsPeriod` | 30 → 120 | 內建備份每次壓縮約 25 秒 |

## 選用工具

- **主迴圈健康檢查**（`scripts/pz-health-watch.py`，Linux／LinuxGSM）：透過本機 RCON 的 `players` 確認主迴圈確實回應，連續三次失敗才重啟。測試：`python3 scripts/test_pz_health_watch.py`。
- **Native 快照**（`scripts/native_snapshot.py`）：每次遊戲更新後對伺服器的 native 函式庫做雜湊與符號差異比對；官方會單獨更新這些函式庫，jar 不變不代表它們沒變。

## 專案結構

```text
build.ps1 / build-client.ps1   伺服器建置／客戶端修補包建置
patcher/src/                   ASM patcher、PatchConfig（所有手術點）、SmokeCheck、LoadCheck、BytecodeVerify
patcher/game/                  伺服器 runtime helper
patcher/game-client*/          客戶端修復 helper 與共用客戶端核心
patcher/game-profiler/         DevProfiler runtime
patcher/tests*/                行為測試（伺服器、客戶端、安裝器、profiler）
deploy/                        伺服器 install.sh／uninstall.sh 與閘門測試
deploy-client/                 客戶端安裝器（Manage-Patches.ps1 與 .bat 啟動檔）
native-observer/               LD_PRELOAD／LD_AUDIT native 防護、啟動 wrapper、測試
scripts/                       上面列出的選用工具
docs/                          詳細修補文件、設計筆記、給 TIS 的回報
lib/                           ASM 9.8（BSD-3-Clause，見 lib/LICENSE-ASM.txt）
```

## 文件

| 文件 | 語言 | 內容 |
|---|---|---|
| [docs/patches.md](docs/patches.md) | 繁體中文 | 每一項修補：事故、附 bytecode 證據的根因、修正、安全論證、檢查、開關、實測結果 |
| [docs/patches.en.md](docs/patches.en.md) | 英文 | 上面文件的英文版 |
| [docs/report/](docs/report/) | 英文（草稿） | 為 TIS 論壇撰寫的 bug 回報 |
| [docs/optimization-summary.md](docs/optimization-summary.md) | 繁體中文 | 營運總覽與時間線 |
| `docs/*-design-*.md` | 繁體中文 | 較大修補的設計筆記 |
| [docs/specs/](docs/specs/) | JSON | 早期修補的 `javap` 證據 |

## 支持作者

MOD 永遠免費。喜歡的話可以請我喝杯咖啡，贊助會用在伺服器與 MOD 開發上。

[![Ko-fi](https://raw.githubusercontent.com/Minidoracat/workshop-resources/refs/heads/main/badges/badge_kofi.png)](https://ko-fi.com/minidoracat)

## 授權與免責

本專案採 MIT 授權，見 [LICENSE](LICENSE)。

第三方元件：建置使用 OW2 ASM 9.8（BSD-3-Clause），隨 `lib/` 散布，授權聲明見
[lib/LICENSE-ASM.txt](lib/LICENSE-ASM.txt)。任何發布包都不含 ASM。

- **非官方專案**，與 The Indie Stone 無任何隸屬、合作或背書關係。Project Zomboid 與其相關素材之著作權均屬
  The Indie Stone 所有。
- repo 本身不含任何遊戲檔案。**客戶端發布包含有 `projectzomboid.jar` 中少數類別的修改版**，由本 repo 的 patcher
  從指定版本的官方原版 jar 產生；只能搭配合法持有的同一版本遊戲使用，安裝器也會拒絕其他版本。若 The Indie
  Stone 要求停止散布，我們會照辦。
- **修改遊戲檔案風險自負。** 伺服器用 `bash uninstall.sh`、客戶端用 `Uninstall-Patches.bat` 移除修補；安裝前請先備份伺服器。
- **每次 Project Zomboid 更新後都必須重新建置與驗證**，切勿把舊修補套到新版遊戲上。
