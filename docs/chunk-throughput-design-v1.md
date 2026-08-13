# chunk 供給吞吐修復設計 v1（W4-1 / W4-2）

> 對應事故：2026-08-13 Player-D「黑邊」實案（路易斯維爾市中心，卡 10 多分鐘不能動、
> 離開該區即恢復、同安全屋其他玩家正常、無斷線）。鑑識方法與否證清單見
> `docs/patches.md` 2o（v2.1 觀測 patch）與 memory `pz-blackedge-rootcause`。

## 1. 根因鏈（八路鑑識＋對抗驗證定案）

| # | 環節 | 實證 |
|---|---|---|
| 1 | client `WorldStreamer.updateMain` **每幀**送一次 `RequestZipList`，每包僅約 3 個 chunk | 穩態 reqNum 約 30/s、log 逐分鐘取樣 |
| 2 | server `RequestZipListPacket.parse` **每個封包無條件 new 一個 `ClientChunkRequest`** 入列，從不併入佇列尾端未滿的 ccr（只有同一封包內超過 20 才換新 ccr） | 反編譯 `RequestZipListPacket.java:50-68` |
| 3 | `PlayerDownloadServer.update()` 每個 worker 週期**只處理一個 ccr**，且由 `GameServer` 的 `UpdateLimit(100L)` 以 10Hz 呼叫 | `PlayerDownloadServer.java:72-112`、`GameServer.java:822/1047` |
| ⇒ | **實際約 30 chunk/s**，而 `NON_LARGE_AREA_CHUNKS_LIMIT`=20 × 10Hz = **200 chunk/s** 的預算浪費 85% | |
| 4 | 需求超過供給後，積壓越過 client `WorldStreamer.resendTimedOutRequests` 的 **8000ms** 逾時；該路徑設 `flagsWs |= 9` → `loadReceivedChunks` 因 `flagsWs & 8` **丟棄已送達的整包資料**並把 chunk 重新排隊，**且不送 NotRequiredInZip 通知 server 取消** | `WorldStreamer.java:635-650`、`:241/:260` |
| ⇒ | **自我維持 livelock**：pending 恆＝請求率×8s（實測 240 ↔ 30/s，Little's law 對得上）、每個卡住的 chunk 重發約 141 輪、18 分鐘燒掉約 105MB 全數丟棄、零 chunk 載入＝永久黑邊 | ChunkStream 觀測 log 逐分鐘 |
| 5 | `PlayerDownloadServer` 是 **per-UdpConnection** 的獨立 daemon thread，不在主迴圈上 | 解釋「只有他卡、server 全域指標全綠、同安全屋別人正常」 |

**已否證**（避免重蹈）：地板物品堆撐肥 chunk（他卡的那格物品密度全服最低）、client 效能
（全程 60fps 零停頓）、連線型態（同一條 SDR relay 載入時間 10.8s～101.1s 都有）、頻寬
（峰值僅約 1Mbps）、server 主迴圈健康度（綠，且此路徑根本不在主迴圈計量內）。

**副線**（不修，僅記錄）：client 從不填 `ChunkRequest.crc`（javap 實證：`WorldStreamer` 對該
欄位**零 putfield**），使 server 兩條「你已經有這塊」捷徑（`PlayerDownloadServer:320-323`、
`:349-350`）永久失效——已有的 chunk 也整包重壓重送。屬 vanilla bug，值得單獨報 TIS。

## 2. W4-1：server 端供給併包

**目標**：填滿 vanilla 自己設計好的批次容量，不新增任何 chunk、不改處理順序。

**掛點**：`PlayerDownloadServer.removeOlderDuplicateRequests()V` 頭部 headCall
（receiver-only，helper `zombie.mdc.ChunkRequestPacker`）。

> **為何不是 `update()V`**（審查抓到的 blocking，必讀）：`update()` 對 `ccrWaiting` 的所有存取
> 都包在 `if (workerThread.ready)` 內，那是 vanilla 與 **WorkerThread** 互斥的唯一機制——
> worker 的 `sendArray` 會對 `ccrWaiting` 加入 `ccrForRetries` 並持續 `chunks.add()`。
> headCall 插在 offset 0 會落在該閘**之外**，與 worker 同時改同一個 plain ArrayList；
> 最壞情況是同一個 `Chunk` 實例同時掛在兩個 ccr、被雙重 `releaseChunk` 進 **static** 的
> `freeChunks` 池＝**跨玩家汙染**。`removeOlderDuplicateRequests()` 全 class 僅被 `update()`
> 呼叫一次（javap 實證）且就在 ready 閘內、vanilla 去重之前——正是需要的位置。
> SmokeCheck 以「dedupe 頭部全序 ＋ update() 內零 packer 呼叫」把這件事鎖進建置期。

**演算法**：把後續 ccr 的 chunk 搬進隊首 ccr，直到隊首達批次上限。

- **去重語意保留**：vanilla 去重只偵測「跨 ccr」重複（對每個 chunk 掃索引更小的 ccr），
  同一 ccr 內看不見。故遇到隊首已含相同 `(wx,wy)` 的 chunk **跳過不搬**，留在原 ccr 讓
  vanilla 去重照原樣運作（含其 `sendNotRequired` 取消路徑）。
- **回收**：搬空的 ccr 留在原位——本 helper 之後緊接執行的 vanilla 去重本體
  `if (ccr1.chunks.isEmpty()) { ccrWaiting.remove(i); freeRequests.add(ccr1); }`
  會移除並回收（同一次呼叫內）。helper 因此完全不碰 package-private 狀態。
- **largeArea 不介入**：它有自己的 20/40 擁塞窗。

**成本閘**（審查 B2）：併包會讓 vanilla 在同一幀序列化更多 chunk（`SaveLoadedChunk`
在主執行緒，成本由格數與物件數決定，非輸出位元組數）。因此：

| 旋鈕 | 預設 | JVM 參數 | 作用 |
|---|---|---|---|
| 批次上限 | **8**（vanilla 上限 20 的 40%） | `-Dmdc.chunkPacker.batch=N` | 單一 ccr 最多併到幾個 chunk；0＝停用 |
| 視窗預算 | **120** | `-Dmdc.chunkPacker.windowBudget=N` | 全域每 100ms 可「額外」搬移的 chunk 數，超出即整段退回 vanilla；0＝停用 |

首發保守值的理由：vanilla 每幀約 80 連線×3＝240 次序列化；批次 8 的最壞情況是 640，
視窗預算把「額外量」硬壓在 120 以內（≈vanilla 基線的 +50%）。觀測一週後再決定是否上調。

**觀測**：每 5 分鐘一行
`[ChunkPacker] batch= windowBudget= calls= packed= mergedChunks= avgBatchX10= skip[short/large/full/budget] skippedDup= anomalies=`
——`avgBatchX10` 是成效直接指標（vanilla 約 30＝3.0，目標接近 batch×10）。

## 3. W4-2：client 端請求逾時 8s → 15s

**手術**：`WorldStreamer.resendTimedOutRequests()V` 的 `8000L` → `15000L`
（方法內常數替換；全 class 僅此一處，javap 實證）。

**理由**：`RequestZipList` 與 `SentChunkPacket` 皆 `reliability=2`（RELIABLE，RakNet 保證送達），
故此逾時幾乎不是在救「真的遺失」，而是在懲罰「server 慢」——它把已經在路上的資料整包丟掉
再重問，正是 livelock 的動力來源。放寬到 15s 讓遲到的資料被接受即可斷鏈；上界仍有限
（server 真的不回時 15s 後照樣重試）。

**首發 15s 而非 30s**（審查 I3）：先配合 W4-1 上線觀察 `[ChunkStream] pending` 曲線，
確認供給側修好後逾時很少觸發，再決定是否需要放寬。殘留風險寫入發版說明：
(a) server 真的丟棄請求時復原時間 8s→15s；(b) v2.2 client 若連到未打 patch 的 server，
會多持有約 1.9× 的 `ChunkRequest`＋`bb` 狀態才放棄。

## 4. 上線與回退

W4-1 與 W4-2 **無耦合依賴**，可分開發版：

1. **W4-2（client v2.2）** 可獨立出貨給玩家；不裝也不會壞，只是少一層保險。
2. **W4-1（server）** 走既有部署鏈：`uninstall.sh` → `install.sh` → 下次重啟生效。
   - 生效驗證：log 出現 `[ChunkPacker]` 行，且 `avgBatchX10` 明顯高於 30。
   - **緊急降級不需重新部署**：`ProjectZomboid64.json` 加
     `-Dmdc.chunkPacker.windowBudget=0`（整刀停用，等同 vanilla）後重啟即可。
   - 完全回退：`uninstall.sh`。
3. **成效判準**（下次黑邊回報時）：client log 的 `[ChunkStream] pending` 不再長期釘在
   「請求率×逾時」的平衡值，且 `parts` 與 `pending` 同步下降（＝真的載進去了，
   而非收了又丟）。
