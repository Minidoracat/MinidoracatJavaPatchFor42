# IsoAnimal.updateLOS 設計案 — v0（交接骨架，尚未開始分析）

> 狀態：**未開始**。本檔只是交接，讓下一個 session 能直接動手，不必從對話重建脈絡。
> 建立於 2026-08-17，接在「第 8 把抑噪刀＋食材重量記憶化」上線之後。

## 為什麼值得做

目前**唯一還有量級收益的標的**。對照本輪剛定案的兩把刀：

| 標的 | 主執行緒佔比 | 狀態 |
|---|---|---|
| `IsoAnimal.updateLOS` | **約 15%** | 未動 |
| 食材重量記憶化 `on` | 0.06–0.18% | 實測後否決 |
| toxic log 抑噪 | 0.003–0.03%（估） | 已上線（價值在可觀測性） |

差兩個數量級。

## ⚠️ 第一步不是設計，是重新取樣

**15% 這個數字來自 2026-08-16 巡檢的 46 份 jstack 樣本，而那是 PSR v1.72 上線前後的混合期。**
PSR 的 `coverage REMOVE` 已從 107.7/h 降到 10.9/h（9.9×），主迴圈的負載結構因此改變過。

所以**不可直接假設 15% 仍成立**。新 session 第一件事：重新取一批 jstack（建議 ≥30 份、跨峰值與離峰），
確認 `IsoAnimal.updateLOS` 仍在前列、佔比多少。若已掉到 5% 以下，這個案子就該重新排序而非直接做。

取樣方式參考本輪 `temp/psr-*.sh` 的做法：凍結時間窗、印出可驗算的原始計數。

## 第二步：守衛檢查（這是硬閘，不是可選項）

**在談任何演算法之前，先用 `javap` 確認 `updateLOS` 及其呼叫鏈上有沒有
`GameClient.client` / `GameServer.server` 守衛。**

理由是本專案付過代價的通則（見 `docs/patches.md` 2n、AGENTS.md「修復 5」段）：
**受精蛋清除豁免**那把刀本身完全有效（正式服 log：keptLoads 數千、anomalies 全零），
但 `IsoGridSquare.load` 的判定區塊**沒有** `GameClient.client` 守衛，而 `SandboxOptions`
由 server 在握手時完整同步給 client——於是 client 端用一模一樣的條件自行把蛋濾掉，
玩家看不到也撿不起來。**server-only patch 必然產生視覺／互動 desync，最後只能退役。**

動物 LOS 直接餵行為與動畫，是最容易踩同一顆雷的位置。若無守衛，設計案**一開始**就要決定：
(a) 連 client 一起改（需 `build-client.ps1` 分流，且遊戲更新即失效）、或
(b) 從設定層解決、或
(c) 放棄。**不要做完才發現。**

## 已知障礙（先前分析結論，未重新驗證）

`updateLOS` 對 `IsoCell.objectList` 做 per-animal 全表掃描。想削減掃描範圍或降低頻率，
障礙是 **`lastAlerted` 的衰減次數本身是警戒／逃跑的載重狀態**——不是純粹的觀測計數器，
所以「少掃幾次」會改變動物行為，不是無損優化。

這與 `AnimalSpottedPrefilter`（已上線、預篩掉 99.98%）不同：那把刀是在**進入**判定前做保守包圍
球預篩，零 false-negative。`updateLOS` 的問題在判定**內部**，不能用同樣手法。

## 手術可用形狀（本專案鐵則）

只做堆疊形狀與指令長度不變的手術：`redirect`／`constChange`／`headGuard`／`headCall`／
`fieldGetSwap`／`countClamp`。**禁 early-return**（JDK25 實測 RETURN 後接原碼＋原 frames → VerifyError）。
逐方法 `expectedHits` 守門，且「數量對不代表改對地方」——常數手術必須用 `javap` 驗語境。
詳見 AGENTS.md「手術鐵則」與 `docs/patches.md`。

## 紀律提醒（本輪血換的）

- **只有量測證明有收益**。記憶化那把刀命中率 99.997% 卻只值 0.11%——因為我漏了「乘上呼叫速率」。
  絕對收益 ＝ 命中率 × 呼叫速率 × 單次成本，缺任一項都會誤判。
- 任何時長／速率都要**印出起訖時間戳供人工驗算**，並用**凍結時間窗**（伺服器持續運行，
  沒有凍結點的統計每次重跑都會漂移）。
- 下機制結論前**必須印原始行讀一次事件順序**，聚合數字不能替代。
- 寫下任何「X 不是原因」之前，先確認觀測管道**看得見** X。

## 相關檔案

- `patcher/game/zombie/mdc/AnimalSpottedPrefilter.java` — 同一 class 已上線的預篩刀，可參考語境鎖法
- `docs/patches.md` 2b（IsoAnimal 壓力三調）、2a（ZombieCountOptimiser 退役分析）、2n（受精蛋退役通則）
- `patcher/src/PatchConfig.java` — `all()` 逐項 `expectedHits`
- `patcher/src/SmokeCheck.java` — 結構斷言的寫法（本輪新增 `extraWeightNoEscape` 等 4 個 helper 可參考）
