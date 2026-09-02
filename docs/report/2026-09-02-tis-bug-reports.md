# TIS 官方回報總表（2026-09-02，對 42.20.4 逐項核對）

> 全部草稿分三檔（每份含 `### Title` 一行＋`### Body` fenced block，貼上即用）：
> - `2026-09-02-tis-reports-A.md`：伺服器假死／資料損失 6 份（A-R1…A-R6）
> - `2026-09-02-tis-reports-B.md`：MP 玩法／動物／同步／minor 7 份（B-R1…B-R7）
> - `2026-09-02-tis-reports-C.md`：client 貼圖洩漏、native crash、設計面建議 3 份（C1…C3）
>
> 現況核對基準：正式服 jar `80e405a4`（42.20.4，2026-08-26 至今最新 public；unstable 分支已於
> 2026-07-29 移除）。每份 Root cause 都引用 42.20.4 反編譯快照行號或 javap offset；
> 另有 SmokeCheck 的 vanilla 前提斷言（釘「缺陷存在的結構事實」）在 42.20.4 jar 上全綠。

## 0. 論壇格式（依 TIS 官方 Bug Report Guide 與你先前被 QA 採納的貼文）

- 板別：`Bug Reports`（forum/85）；標題 `[42.20.4] [MP] <concise symptom>`（你 42.17 那篇用 `[42.17 MP]`，兩者皆可）。
- 內文開頭五行欄位：`Version / Mode / Server settings / Mods / Save`，再 `Reproduction steps`（競態類寫 Trigger conditions）。
- Guide 要求「Mods must be disabled」：草稿的 Mods 欄位一律誠實寫 ~80 mods＋「根因在 vanilla Java、附 class/method 引用」——
  這是你 getFileWriter 那篇的做法，QA 接受了 bytecode 級證據。
- 每篇一個主題；建議**分批發**（每天 3–4 篇，先發資料損失與假死），避免同日 15 篇被當洗版。
- **玩家人數（2026-08-26 20:00 → 09-02 03:10，connections.txt 逐事件重建）**：7 天平均在線 ≈30、每日峰值 68–95
  （最高 95，9/1 晚）、晚峰 19–24 時小時峰值平均 60、7 天 465 個不同 Steam 帳號、254 slots。
  16 份草稿的 `Server settings` 欄位已統一用這組數字（人數是負載語境，不是個資；競態／同步類 bug 的觸發率與它直接相關）。

### 板塊對照（2026-09-02 查論壇實際結構）

| 板 | 板 URL | 發新主題 | 放什麼 |
|---|---|---|---|
| **Bug Reports** | https://theindiestone.com/forums/forum/85-bug-reports/ | https://theindiestone.com/forums/forum/85-bug-reports/?do=add | A-R1…A-R6、B-R1…B-R7、C1、C2 共 15 份。板規只有「English only」，**沒有 MP 子板**，用標題 `[MP]`／`[MP client]` 區分。 |
| PZ Support | https://theindiestone.com/forums/forum/18-pz-support/ | https://theindiestone.com/forums/forum/18-pz-support/?do=add | 板頭寫「crash reports… Bug reports go here（連到 85）」——給「不知原因的崩潰求助」。C2 有根因與修法，仍發 Bug Reports，首行註明 crash 即可。 |
| PZ Suggestions | https://theindiestone.com/forums/forum/20-pz-suggestions/ | https://theindiestone.com/forums/forum/20-pz-suggestions/?do=add | C3（SaveAll 同步凍結，設計面）。 |
| Mod Portal | https://theindiestone.com/forums/forum/87-mod-portal/ | https://theindiestone.com/forums/forum/87-mod-portal/?do=add | 你 getFileWriter 那篇在此；若要反映 42.20.4 移除 `loadstring` 的 mod 生態衝擊（可選），也在此。 |
| PZ Multiplayer › Help | https://theindiestone.com/forums/forum/72-help/ | — | 玩家求助用，**不要**放 bug 回報。 |

每份草稿（A/B/C 三檔）的 `### 中文摘要` 之後都有一段 `### 建議板塊`，帶板 URL 與發新主題 URL（`?do=add` 需登入）。

### 去識別化（已檢查三份草稿的英文 body）

- 英文 body **零**玩家名、Steam ID、IP、主機路徑、伺服器名；玩家只以 "one player"／"a player" 指涉。玩家名（Player-A、Player-G…）只出現在中文摘要（不貼）。
- 遊戲座標、chunk 編號、mod 名稱（BetterFirstAidQuickPatch、PSR）不是個資，保留（mod 名是誠實揭露的一部分）。
- **附件貼出前要處理**：
  - console／DebugLog 摘錄：stack 本身無個資，但同段 log 行常帶 username（`fully connected`、`sendItemsToContainer` 等）——只貼 stack 與計數行，或把 username 換成 `<player>`。
  - `connections.txt`／`user.txt`：含 IP 與 Steam ID，**不附**。
  - hs_err（C2）：`Command Line:` 一行含我方 `-Dmdc.*` 旗標與 `-Duser.language=CH`——C2 本文已揭露 patch，留著無妨；不想解釋就塗掉那一行。core dump **不附**（含玩家資料，"available privately"）。
  - `vehicles.db` 列快照（B-R5）：modData blob 內有認領者名（MVCK）——貼 header 欄位（id/wx/wy/x/y）就好，不貼 blob。
  - pcap 統計（B-R6）：只貼 decoder 彙總表，不附 pcap 本體（含 IP）。
  - 貼圖遙測／console (12)/(17)（C1）：是玩家自己的 client console，含其 Steam 名與本機路徑——貼前把 `Users\<name>` 與暱稱塗掉，或只貼遙測行。
- 附件：console 摘錄用 spoiler／code block；hs_err、core、telemetry 用「available on request」。
- **貼法（A-R1 實貼驗證通過）**：草稿是 80 字硬換行的純文字，直接貼會壞（整篇 code block／每行一段／
  html 原始碼 code block 三種都試過）。`python scripts/tis_forum_html.py` 產出 `docs/report/forum-html/<篇>.html`
  （段落合併、小標粗體、log／程式碼片段各自 code block、清單／表格保留）→ **用瀏覽器（Chrome/Edge）雙擊開啟**
  → 在排好版的網頁上從 `Version:` 拖選到最後 → Ctrl+C → 論壇編輯器 **Ctrl+V** → 標題另外複製到 Title 欄。
  不要用編輯器開 .html 再複製（會貼到原始碼），不要 Ctrl+Shift+V（純文字失去全部格式）。
  貼完可用 `curl` 抓 topic 頁對帳：粗體小標數、`<pre>` 數、清單數與本地 .html 相同，且全文 diff 無句子破損。
- **編輯器已知會咬壞的東西（6 篇實貼對帳，2026-09-02）**：行內 `<code>` 在 4/6 篇被整段搬到段尾
  （A-R2／A-R3／A-R4／A-R6，句子破損如「uses a JVM-wide shared as scratch space」）、同段兩個 `==`
  會被當 highlight 語法吃掉。轉換器已改成**不產生行內 code／em、` == ` 改寫成 ` is `**，只留粗體與
  `<pre>`（6/6 存活）。上述 4 篇已用重生的 .html 重貼修正。
- **Tags 只能從既有 tag 下拉選，不能自訂**：版本不打 tag（標題已有 `[42.20.4]`，板上其他回報也不打）；`dedicated` 選
  `dedicated server`；打 `freeze` 會跳 `freezers`（冰箱），要點到 `freeze` 那個；`data loss` 不存在，用
  `corruption`／`save-data`。每篇的 4 個 tag 已改成確認存在的名稱（用 `/forums/tags/<tag>/` 逐一驗過）。

## 1. 你先前的回報現況

| 貼文 | 板 | 現況（42.20.4） |
|---|---|---|
| [42.17 MP] visited map fragmented / 3.83 MB blob per login（2026-04-26，QA Artem_VB 已建 ticket）— https://theindiestone.com/forums/topic/94585-4217-mp-player-visited-map-data-fragmented-and-sometimes-lost-on-relog-server-streams-a-fixed-~383mb-blob-per-user-on-every-login/ | Bug Reports | **部分處理**：`WorldMapVisitedServer.sendRequestData` 現以 `Deflater` 壓縮後傳送（42.20.4 反編譯 :125-156），5 分鐘 `savePeriod` 已不存在（改由 `QueuedSaveAll` 統一存檔）。傳輸層 ack/retry 未見。可在該串回一句 follow-up。 |
| [42.20] getFileWriter allowlist 靜默回 null（2026-08-01）— https://theindiestone.com/forums/topic/97743-4220-getfilewriter-silently-returns-null-for-non-allowlisted-extensions-mod-data-writes-fail-with-no-error-and-no-log/ | Mod Portal | 未查 42.20.4 是否加 log；與本批無關。 |
| Dedicated Server can't download mods（2026-07-14 回覆）— https://theindiestone.com/forums/topic/96649-dedicated-server-cant-download-mods/ | PZ Support | 不適用。 |

## 2. 本批 16 份：分類、優先級、現況

**發文狀態（2026-09-02）**：16/16 已發（Bug Reports 15 篇 topic 100891–100921、PZ Suggestions 1 篇 100925），每篇皆以 `curl` 抓回與本地 .html 對帳通過；原排程 9 天分批改為單日發完（使用者決定）。§4 的排程表僅留作紀錄。

優先級：**P0** 資料損失／全服假死；**P1** 玩法卡死／動物滅失；**P2** 網路效能；**P3** minor／設計建議。

### I. 伺服器假死／活鎖 → **Bug Reports**：https://theindiestone.com/forums/forum/85-bug-reports/?do=add

| # | 標題 | P | 42.20.4 現況與依據 | 附件 |
|---|---|---|---|---|
| A-R1 | Container cycles → `ItemContainer.getCharacter()` 無限遞迴 → SOE 主迴圈死 13 分鐘 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100891-42204-mp-container-cycles-cause-unbounded-recursion-in-itemcontainergetcharacter-stackoverflowerror-kills-the-dedicated-server-main-loop-13-minute-silent-freeze/ | P0 | 仍在：`ItemContainer.java:3250-3256`／`:342-358` 無 visited/深度；`AddItem :458-495` 只擋重複 id；`TransactionManager.chainContainsContainingItem :97` private 且只 2 層。守衛 8/13 起零復發。 | 8/13 21:31 SOE 堆疊、環閉合點 log |
| A-R2 | "Entity is already registered" 主迴圈活鎖 114 分鐘＋stale `entitySet` 每日仍發生 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100893-42204-mp-entity-is-already-registered-from-isochunkdoloadgridsquare-permanently-livelocks-the-dedicated-server-main-loop-114-minute-freeze-and-the-underlying-stale-engineentitymanagerentityset-entries-are-still-occurring-daily/ | P0 | 仍在：`ServerMap.Load2 :798-799` dequeue 在 fallible 之後；`EngineEntityManager :137` throw 為首句。**新根因鏈**（本批發現）：`GameEntity.reset :445` 無條件清 `addedToEngine`、`GameEntityManager.UnregisterEntity :235/:255` 早退跳過 `engine.removeEntity`、`RegisterEntity :211` 先 add 後 `:217` 設旗標＝自封；IsoObject 池（`IsoGridSquare :2551-2552`、`IsoObject.getNew :366-373`）重用同一實例。8/27–9/2 捕手 21 次全 `addedToEngine=false`。 | 8/14 01:34 stack、21 筆 victim 清單 |
| A-R4 | `BaseAnimalSoundManager` comparator NaN → TimSort IAE → `clear()` 跳過 → 全服卡讀條「時間停止」 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100897-42204-mp-baseanimalsoundmanager-comparator-violates-its-contract-on-nan-distances-timsort-illegalargumentexception-skips-charactersclear-and-self-sustains-into-a-server-wide-livelock-stuck-action-bars-time-stopped/ | P0 | 仍在：`BaseAnimalSoundManager.java :19-24` 現場重算＋手寫三態、`:42` sort 先於 `:59` clear。8/23 1411 次；捕手 8/27–9/2 仍攔 18 次、`nanAnimals=0`。 | 8/23 IAE 堆疊、捕手 log |
| A-R5 | `IsoGridSquare.removeGlassAttachments` 無條件 `n--` → 一個砸窗封包無限迴圈（SIGKILL） — **已發 2026-09-02** https://theindiestone.com/forums/topic/100899-42204-mp-isogridsquareremoveglassattachments-decrements-its-loop-index-unconditionally-a-single-smash-window-packet-can-spin-the-server-tick-forever-requires-sigkill/ | P0 | 仍在：`IsoGridSquare.java :8226-8227`；`IsoObjectUtils :36-41/:45-60` 給出兩條「沒移除任何東西」的實路。8/02 事故。 | 8/02 兩份 thread dump |

### II. 伺服器資料損失 → **Bug Reports**：https://theindiestone.com/forums/forum/85-bug-reports/?do=add

| # | 標題 | P | 42.20.4 現況與依據 | 附件 |
|---|---|---|---|---|
| A-R3 | 共用 static `tempVector2_2` 跨執行緒競態 → chunk 載入失敗 → Blam 抹除玩家建造 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100895-42204-mp-shared-static-vector2-in-isogamecharactersetforwarddirectionfromisodirection-races-between-the-chunk-loader-thread-and-the-main-loop-forward-direction-cannot-be-zero-length-vector-aborts-a-chunk-load-and-blam-wipes-player-built-conte/ | P0 | 仍在：`IsoGameCharacter.java :440/:5072-5074`、`IsoMovingObject :380-381` 先歸零；`IsoChunk :2302-2306` Blam。Player-A 案 46,142→8,549 bytes。 | 8/13 19:55 例外＋chunk 大小前後 |
| A-R6 | 存檔管線共用 CRC32 競態 → header CRC 錯／0 → SANITY CHECK FAIL → Blam — **已發 2026-09-02** https://theindiestone.com/forums/topic/100901-42204-mp-shared-crc32-instances-in-the-chunk-save-pipeline-race-across-threads-chunk-headers-are-written-with-a-wrong-or-zero-crc-and-the-next-load-answers-sanity-check-fail-with-blam-loadbrandnew-wiping-player-built-chunks/ | P0 | 仍在：`ServerChunkLoader :430/:440/:478`（`SaveChunkThread.crc32`）、`:31/:593-596`（`crcSave`）、`IsoChunk.Save :4496-4502`；42.20.3/42.20.4 逐指令未變。43 chunk 損失；隔離後 flagged=0（2.92M 寫入）。 | 8/14 首晚 8 筆 BLOCKED stack、43 筆鑑識表 |
| B-R5 | `VehiclesDB2$VehicleBuffer.set` 從 stale/pooled `vehicle.chunk` 取 wx/wy → 車輛永久不可見 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100913-42204-mp-vehicles-become-permanently-invisible-vehiclesdb2-writes-wxwy-from-a-stale-or-pooled-vehiclechunk-while-xy-come-from-physics/ | P0 | 仍在：`VehiclesDB2.java :1028-1032`（載入 `WHERE wx=? AND wy=?` :705/:737）；`IsoChunk.resetForStore :5255-5256` wx/wy=0。守衛 8/27–9/2 修正 182 次（\|Δ\|=1 ×176）。 | 三輛車 DB 列快照、8/28 NaN 案 |
| B-R2 | `IsoHutch.load` 丟棄 `addAnimalInside` 回傳 → 近滿舍動物載入即滅失 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100907-42204-mp-isohutchload-discards-the-addanimalinside-return-value-animals-in-a-near-full-hutch-are-silently-destroyed-on-load/ | P1 | 仍在：`IsoHutch.java :882` 呼叫後 POP；`:776-786` 101 次隨機選位、`:788` 最終只查 `animalInside`、`:801` 靜默 `return false`。靜態定罪（無執行期實例）。 | 無（靜態） |

### III. MP 玩法／同步 → **Bug Reports**：https://theindiestone.com/forums/forum/85-bug-reports/?do=add

| # | 標題 | P | 42.20.4 現況與依據 | 附件 |
|---|---|---|---|---|
| B-R1 | Timed action 100% 卡死、整條 action queue 堵塞（null 參數 → 既不 Accept 也不 Reject） — **已發 2026-09-02** https://theindiestone.com/forums/topic/100905-42204-mp-timed-actions-can-stall-permanently-at-100-and-block-the-whole-action-queue-when-a-packet-argument-deserializes-to-null-the-server-sends-neither-accept-nor-reject/ | P1 | 仍在：`PZNetKahluaTableImpl :473-478` 靜默 null；`NetTimedActionPacket :70-86` 對 `act` 設 state 卻 `this.write`（javap offset 81/142 `aload_0`）。修正後 8/27–9/2 送出 60 次本應有的 Reject。 | `Lua(Vanilla).new` 例外樣本 |
| B-R3 | `faceThisObject` 對 `getClosestSpriteGridObject` 回 null 無條件解參考 → 動物狀態機每 tick NPE、卡 idle — **已發 2026-09-02** https://theindiestone.com/forums/topic/100909-42204-mp-isogamecharacterfacethisobject-dereferences-a-null-result-from-getclosestspritegridobject-animal-state-machines-throw-~70-npehour-and-stick-in-idle/ | P1 | 仍在：`IsoGameCharacter :10387-10391`（javap 200→204 無 ifnull）；`IsoObject :5389-5395/:5450`。9/1–9/2 3386 次。 | NPE 堆疊＋caller 統計 |
| B-R4 | 衣物同步三叢集：(b) `ItemDescription` ctor tint 漏守衛→單一玩家衣物廣播全滅；(c) SyncVisuals count 不符整包丟；(a) `ContainerID` 直讀 raw square — **已發 2026-09-02** https://theindiestone.com/forums/topic/100911-42204-mp-clothingvisuals-sync-an-unguarded-getvisualgettint-disables-all-clothing-broadcasts-for-one-player-syncvisualspacket-drops-whole-packets-on-count-mismatch-and-containerid-reads-a-raw-square-field/ | P1 | 仍在：`SyncClothingPacket :259-262`（260/261 有守衛、262 無）；`SyncVisualsPacket :57-65`；`WornItems :155-165` 跳過 null-visual；`ContainerID :182/:186`。8 天 480+ 筆同一玩家。 | send Exception／mismatch 樣本 |
| B-R6 | 動物 relevancy 半徑 10/8 於 client 載入半寬＋requested 無冷卻/範圍 → 完整快照重送迴圈 ~38% 上行 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100915-42204-mp-animal-relevancy-radius-is-108-of-the-clients-guaranteed-loaded-half-width-and-the-requested-path-has-no-cooldown-or-range-check-a-repeating-full-snapshot-loop-consuming-~38-of-server-upload/ | P2 | 仍在：`AnimalSynchronizationManager :122`（`(getRelevantRange()-2)*10`）、`:57-60` setRequested 無閘、`:107` 唯一上限 150。pcap 8.03s：109 req↔109 full、14 個 ID、87.2% 重送。 | pcap decoder 統計表 |

### IV. Client → **Bug Reports**（標 client-side）：https://theindiestone.com/forums/forum/85-bug-reports/?do=add

| # | 標題 | P | 42.20.4 現況與依據 | 附件 |
|---|---|---|---|---|
| C1 | `ImageData.dispose()` 不釋放 `frames` → DirectBuffer 地板超過 `waitFileTask` 50 MB 硬門檻 → 貼圖載入永久停擺 → 實體只剩影子＋名牌 — **已發 2026-09-02** https://theindiestone.com/forums/topic/100919-42204-mp-client-native-directbuffer-leak-in-the-texture-pipeline-silently-starves-texture-loading-zombiesplayersvehicles-render-as-shadow-nametag-only-until-the-game-is-restarted/ | P0 | 仍在：`ImageData`／`TextureID`／`TextureIDAssetManager`／`WorldStreamer` 四 class 42.20.2→42.20.4 逐位元組相同（pz-42.20.4-update-analysis §2）。修後地板 1096 MB→0、隱形零復發。 | 修前/修後 console (12)/(17)、遙測表 |

### V. Native crash → **Bug Reports**：https://theindiestone.com/forums/forum/85-bug-reports/?do=add（Steam 指南說 crash 走 Support，但本文是根因分析，仍發 Bug Reports 並首行註明 crash）

| # | 標題 | P | 42.20.4 現況與依據 | 附件 |
|---|---|---|---|---|
| C2 | `PolygonalMap2::createVehicleClusters()` SIGSEGV：`VehicleRect` 池交出 `0x30`，經 `VisibilityGraph::release()` 一輪前入池；池無驗證＋`reallocate_aligned` 拷貝 `malloc_usable_size(old)` — **已發 2026-09-02** https://theindiestone.com/forums/topic/100921-42204-mp-dedicated-native-sigsegv-in-polygonalmap2createvehicleclusters-vehiclerect-object-pool-hands-out-a-corrupted-slot-0x30-that-entered-the-pool-through-visibilitygraphrelease-one-round-earlier/ | P0 | 仍在：`libPZPathFind64.so` sha `0777dda6…`（42.20.4 depot）；8/22–8/31 七次、8/31 00:58 最近一次。首次寫入者未定罪（誠實揭露）。 | hs_err（截斷）、ucontext／pool census 輸出、七次 console 摘錄 |

### VI. Minor → **Bug Reports**（低優先，可合一篇）：https://theindiestone.com/forums/forum/85-bug-reports/?do=add

| # | 標題 | P | 42.20.4 現況與依據 |
|---|---|---|---|
| B-R7 | `IsoObject.syncIsoObject` 的 `ERROR: IsoThumpable not found on square` println（每次建造必印，4 天 11,567 行）＋`SpriteConfig.initObjectInfo` 對 19 個 vanilla 物件必刷 `Invalid SpriteConfig object!` — **已發 2026-09-02** https://theindiestone.com/forums/topic/100917-42204-mp-two-vanilla-console-spam-sources-on-a-dedicated-server-isoobjectsyncisoobject-not-found-on-square-~120-lineshour-and-spriteconfig-invalid-spriteconfig-object-for-base-game-objects/ | P3 | 仍在：`IsoObject :866-873`（`System.out.println`，繞過 debug channel）；`SpriteConfig :51-72`。 |

### VII. 設計面建議 → **PZ Suggestions**：https://theindiestone.com/forums/forum/20-pz-suggestions/（發文 https://theindiestone.com/forums/forum/20-pz-suggestions/?do=add）

| # | 標題 | P | 依據 |
|---|---|---|---|
| C3 | `QueuedSaveAll` 全存檔在主執行緒同步凍結 5–7 s（80 人）——建議增量／off-thread cell save — **已發 2026-09-02（PZ Suggestions）** https://theindiestone.com/forums/topic/100925-42204-mp-dedicated-suggestion-full-world-save-queuedsaveall-blocks-the-main-loop-for-57-s-on-a-busy-server-consider-an-incremental-off-thread-cell-save/ | P3 | `ServerMap.SaveAll :94-147` 主執行緒 `sleep(10)` 輪詢；看門狗 4 天 16 次快照同族。 |

## 3. 已知但**不**建議本批回報的項目

| 項目 | 理由 |
|---|---|
| popman 共享 buffer 競爭（2h） | 官方 42.20.2 已收編（`readByteBuffer` 專用讀 buffer），退役。 |
| WorldStreamer 8 s 逾時重發 livelock（W4-2 client） | 42.20.3 官方重構（`ChunkNotReady` 主動通知），撤刀。 |
| hit 封包 `Zombie.process` / `Fall.process` 無 null 檢查（2c） | 42.20.4 仍無檢查（`hit/Zombie.java :56-67`），但只是防禦性守衛、正式服無事故實證，證據不足。 |
| `Send Toxic Building` 洪水（抑噪 #8） | 來源是 PSR mod 的 `setToxic` 週期呼叫，非 vanilla；已另回報 PSR 作者。 |
| 42.20.4 移除 `loadstring`（CleanUI 事故） | 應屬刻意安全修補；若要反映 mod 生態衝擊走 Mod Portal（可選，非 bug）。 |
| `NetTimedAction.perform` unboxing NPE、`Error with packet: GameCharacterAttachedItem` | vanilla 自行 catch／未調查，證據不足。 |
| 受精蛋清除 client 無守衛（2n）、LootRespawn Zone gate（2e） | 設計語意問題，非缺陷。 |

## 4. 發文節奏（避免被當洗版）

論壇沒有明文的每日發文上限，但 16 篇同一人同日連發，版主第一眼會當 spam、QA 也沒辦法逐篇開 ticket。
這批每篇都是獨立缺陷＋反編譯證據，**分散節奏**＋**明說是系列**就不會被誤判——你 42.17 那篇 QA（Artem_VB）
已經回過，他們認得這個 ID。

**節奏：每天最多 2 篇、同日兩篇間隔數小時、P0 先發，約 9 天發完。**

| 日 | 篇 | 板 |
|---|---|---|
| D1 | A-R6（CRC race Blam）、A-R3（tempVector2_2 Blam） | Bug Reports |
| D2 | B-R5（車輛 wx/wy）、C1（client 隱形） | Bug Reports |
| D3 | A-R1（容器環 SOE）、A-R2（Entity registered 活鎖） | Bug Reports |
| D4 | A-R4（動物聲音活鎖）、A-R5（砸窗迴圈） | Bug Reports |
| D5 | C2（native crash） | Bug Reports |
| D6 | B-R1（卡讀條）、B-R3（動物 NPE） | Bug Reports |
| D7 | B-R4（衣物）、B-R2（hutch） | Bug Reports |
| D8 | B-R6（動物網路） | Bug Reports |
| D9 | B-R7（log spam）；C3 | Bug Reports；PZ Suggestions |

若 D1–D2 之後 QA 回覆要你「合併」或「改用其他管道」（email／Discord），照他們的做——他們的 ticket 流程優先。

**第一篇開頭加一句**（讓版主知道這是系列，不是洗版）：

```text
Note: this is the first of a series of independent dedicated-server findings from the same 42.20.4 server, each root-caused in the decompiled bytecode. I will post them as separate topics over the next couple of weeks so each can be tracked on its own; happy to consolidate or move to another channel if QA prefers.
```

**第二篇起結尾加**（把已發的 URL 串起來，方便 QA 關聯 ticket）：

```text
Related reports from the same server: <URL of previous topic(s)>
```

**Tags**：每篇草稿「建議板塊」段下方有一行純文字（如 `42.20.4, multiplayer, dedicated, freeze, inventory`），
整串貼進 Tags 欄即可（逗號分隔會被拆成多個 tag；若這個版本的輸入框不拆，就一個一個打、每個按 Enter）。
多字 tag 一律用連字號（`data-loss`、`timed-action`、`log-spam`），不要選下拉裡別人留的雜項。

每篇貼出後把論壇 URL 回填到本表 §2（方便日後 follow-up、changelog 對照與「Related」串連）。
