# PSR 6.9.0 — follow-up（一週 log＋存檔快照版）

- Discord 版：貼「DISCORD BEGIN」到「DISCORD END」之間（含 markdown）
- 敏感資訊審核見檔尾
- 前一版（v1.72 follow-up，15 小時凍結快照）全文與 23 次修正紀錄見本檔 git history

<!-- ===================== DISCORD BEGIN ===================== -->

## PSR 6.9.0 on a dedicated server — four follow-ups

We have been running 6.9.0 (`PowerBankObject_Server.lua` as shipped on 2026-10-01) on a busy B42 dedicated server. The coverage work is in good shape — the two guards, the chunk-aligned removal and the 3-try campaign cap all behave exactly as your comments describe. What follows is one week of server logs (**2026-10-07 → 10-14, 67 sessions of ≥30 min, 178.2 h of uptime**) plus one save snapshot, and four things they show. Line numbers refer to 6.9.0; positions are replaced by placeholders such as `<base-A>`.

The short version: **1,922 `coverage REMOVE` lines in 178.2 h (10.8 / hour), each costing about 0.36 s of main-thread time, almost all of it in `psrReapplyOtherCoverage`; and about half of those REMOVEs are one bank switching itself off and on every other pass.**

| # | What | Kind | Our suggestion |
|---|---|---|---|
| 1 | reapply after every REMOVE walks every activated bank on the map | cost (a design premise that no longer holds) | only re-sweep banks whose rect meets the removed rect — provably identical result |
| 2 | no hysteresis on enable: a bank that just turned off measures 0 drain and turns back on | bug | judge re-enable against `lastLiveDrain`, which you already keep |
| 3 | in a multi-bank network each bank compares *its share* of the charge with the *whole building's* drain; a switched-off member still takes a share | bug (inconsistent with R-32 network billing) | decide on the network total, or exclude switched-off members |
| 4 | the same bank still logs two REMOVEs in one frame | residual of the v1.72 report | — |

---

### 1. `psrReapplyOtherCoverage` only needs the banks that overlap the removed rect

**Cost.** For two banks of one network that both turn off in the same `updateNetwork` call, the gap between their two REMOVE lines is the first bank's reapply plus the second bank's removal sweep. Over the week there were **210 such same-frame pairs: median 360 ms, p10 330 ms, p90 410 ms (min 308, max 550)**. The removal sweep itself is cheap — a 6.5k-position rect costs on the order of 10 ms (the pair around a 73,722-position rect took 476 ms, a 6,524-position one 360–415 ms, so roughly 1.7 µs per position) — so about 0.33–0.35 s of each REMOVE is the reapply. In our save, 99 activated banks have a `PSR_coverRect`, **1,696,147 (x, y, z) positions in total** (median 6,498, max 107,334). Because `cell:getChunk` is nil on a server (your comment at `:371-373`), every column of every rect goes through `getGridSquare` once per z, and most of those columns are in unloaded chunks where the ADD does nothing at all. So each REMOVE costs ~1.7 M Java calls, and that grows with every solar base anyone builds — it does not depend on who is online.

`:459` says the cost is bounded because a bank turning off is a rare event. Item 2 is why it is not rare on our server: **10.8 REMOVEs / hour ≈ 3.9 s of main thread per hour**, landing in the same frame as the hourly pass and stacking on top of it (frames with three REMOVEs ran 2.4–2.8 s against a ~1.1 s median for passes without one).

**Why only overlapping banks can have lost anything.** `IsoChunk.removeGeneratorPos(x, y, z)` removes only entries whose coordinates are *exactly* `(x, y, z)`, and the REMOVE sweep only calls it for `(x, y, z)` inside the chunk-aligned rect (`psrChunkAlignedRect`, `:301-308`, z range unchanged). Every entry a bank B wrote through the ADD sweep lies inside B's *strict* rect (`:1773-1781`, `:485`). So B loses an entry ⇔ B's rect and the aligned removed rect intersect on x, y **and** z. A bank that misses on any axis lost nothing; reapplying it is a deduplicated no-op on loaded chunks and a no-op on unloaded ones. Guard 1, the double `activated` check and the building fallback for banks without `PSR_coverRect` are all unaffected.

**The change** is small: both callers (`:1812`/`:1820` in `updateGenerator`, `:2017-2019` in `removePowerCoverage`) keep the aligned rect they just swept and pass it as a second argument, and the reapply loop skips a bank unless

```lua
oRect.x1 <= removedRect.x2 and oRect.x2 >= removedRect.x1
and oRect.y1 <= removedRect.y2 and oRect.y2 >= removedRect.y1
and oRect.z1 <= removedRect.z2 and oRect.z2 >= removedRect.z1
```

It has to be the **aligned** rect, not the strict one: the removal reaches up to 7 tiles past the building, and a neighbour whose rect only meets that margin did lose entries. Inclusive comparisons matter for the same reason (rects that share one edge column).

**How we checked it.** We ran unmodified 6.9.0 and the patched file side by side in a standard-Lua harness with engine-semantics stubs (deduplicating `addGeneratorPos`, exact-match `removeGeneratorPos`, server `getChunk` = nil, unloaded chunks, generator squares for guard 1): randomized worlds with overlapping, adjacent and same-building banks, rects across chunk borders and negative coordinates, basements (`z = -1`), disjoint z ranges, banks without `PSR_coverRect`, and demolition. Over 270 worlds and ~4,700 REMOVEs, after every call the per-chunk generator lists (including order), every bank's state and the REMOVE log lines were identical; the patched file made ~22 % fewer `getGridSquare` and 45–48 % fewer `addGeneratorPos` calls in those small dense worlds (on a real map, where most banks are far apart, almost the whole reapply goes). Deliberately broken variants — passing the strict rect, or turning any `<=`/`>=` into `<`/`>` — were caught by the state comparison; dropping a bound only re-sweeps more and left the state identical, as the argument above predicts.

**The one observable difference**, so you can judge it: coverage that went missing for a reason *other* than a REMOVE — the engine's `checkForMissingGenerators` clearing a reloaded chunk, or a demolished bank freeing its own generator square inside a neighbour's rect — is restored today by *any* REMOVE anywhere on the map, and with the filter only by that bank's own next activation pass (at most one charge pass later). That is the normal state of affairs whenever nobody happens to REMOVE, so we do not think it is a new gap, but it is your call.

We are planning to run exactly this as a temporary server-side patch pinned to 6.9.0 until an official version has it; it retires itself automatically as soon as you publish an update.

---

### 2. No hysteresis on enable — a bank turns itself off and on every other pass

**Mechanism** (`updateGenerator`, `:1729-1734`, and your own note at `:1532-1538`): once a bank is off, its coverage is removed, nothing in the building is powered, and `getDrainBuilding` counts only powered, running devices — so `self.drain` becomes **0**. On the next pass `activate = on and charge + solar − 0 > 0` is true for any positive charge. It turns on (ADD sweep, `PSR_coverTries` reset to nil); one pass later the real drain is back, `charge + solar − D ≤ 0`, it turns off (REMOVE `try1/3`, full reapply). Period: two passes, ~5.4 min at `ChargeFreq = 2`. The median interval between repeated REMOVEs of the same bank in our log is 321–323 s.

You already found this deadlock for the backup generator and fixed it there with `lastLiveDrain`; the enable decision still uses the live value.

**Two shapes in the log:**

- **A — solar too small for the building, flickers all day.** E.g. `<bank-P>`: 5 panels, building drain 643.2/h → **336 REMOVEs in the week**, 248 of 305 repeat intervals ≤ 7 min. `<bank-Q>` (3 panels, `lastLiveDrain` 670.4) flickered continuously for about 22 hours. While it is on for that hour the charge is clamped at 0, so the base effectively gets ~(D − charge) of free energy every other pass.
- **B — night tail.** When the charge can no longer cover one hour but is still above 0, the bank flickers 1–2 more times before it really stays off.

**Scale:** 976 of the 1,922 REMOVEs (**50.8 %**) are the same bank again within 7 minutes. The seven busiest banks account for ~70 % of all REMOVEs. Each flicker also flips the building's toxic flag (the engine's `IsoGenerator.setActivated` calls `building.setToxic(activated)` for an indoor generator, then `:1768` / `:1854` send `false`), so clients see the toxic packets toggle; that is a consequence of the flicker, not a cause, and we are **not** suggesting any change to `suppressToxic` or state-based dedup.

**Suggestion:** when deciding whether an *off* bank may turn back on, use `lastLiveDrain` in place of a `drain` of 0 (only when `lastLiveDrain > 0`), with an escape when the battery is (nearly) full so a bank whose capacity is smaller than one hour of drain can still start. Rough effect: type A flickers once per charge cycle instead of every other pass, type B disappears — about half the REMOVEs and toxic toggles. This changes what players see (an under-sized base no longer gets the alternate free hour; after unplugging devices the stale `lastLiveDrain` delays the restart until the battery catches up), so it is a gameplay decision and we would rather you make it than patch it on our side.

---

### 3. Multi-bank networks: share-vs-whole-building comparison, and switched-off members still take a share

`updateNetwork` bills the network once, on the deduplicated network drain, and splits the remaining charge across members by capacity (`PowerBankSystem_Server.lua:878-886`). But each member's `updateGenerator` then decides on **its own share**, **its own panel count** and the **whole building's** drain (`PowerBankObject_Server.lua:1731-1734`).

`<base-A>`, one building, three banks in one network (save snapshot):

| bank | on | charge | max | drain | panels |
|---|---|---|---|---|---|
| `<A1>` | yes | 193.5 | 627.6 | 267.2 | 16 |
| `<A2>` | yes | 193.55 | 627.8 | 267.2 | **0** |
| `<A3>` | **no** (switched off by the owner) | 249.73 | 810.0 | 301.6 | 0 |

The network holds 637 charge against 267.2/h of drain — about 2.4 hours of power. Yet each powered bank compares ~193 with 267.2; `<A2>` has no panels, so its test is always `193 − 267 < 0` and it flickers (228 REMOVEs in the week, `<A1>` 159). Meanwhile `<A3>`, which the owner turned off, still holds **39 %** of the network charge. The building starts flickering while the network still has more than two hours in it. This looks like the per-bank enable test was not updated when R-32 moved billing to the network.

**Suggestion:** make the enable decision once per network (total charge + total solar vs network drain), or at least exclude members with `on == false` from the charge split.

---

### 4. Same-frame double REMOVE is still there

Item 3 of our v1.72 report: the same bank logging `try1/3` and `try2/3` in **one** frame. In 6.9.0 we still see it **57 times in the week** (median gap 365 ms, the same as a full reapply). Our guess — not verified — is `loadGenerator` running twice for one bank when its chunk loads (`OnChunkLoaded` and `OnObjectAdded`), or an asymmetric link that visits the bank twice in `updatePowerbanks`. It is minor next to items 1–2, but each one burns a try and a full reapply for nothing.

---

## Summary

Item 1 is a pure cost fix with an identical result and makes every REMOVE cheap regardless of items 2–4 (our estimate: ~0.36 s → under 0.05 s per REMOVE, ~3.4 s of main thread per hour on our server — an estimate from log gaps and rect sizes, not a profiler). Items 2 and 3 are behaviour bugs that cause most of the REMOVEs in the first place; they need your judgement because they change when bases have power. Item 4 is a small leftover. We are still on `ChargeFreq = 2`. Happy to share the harness or more numbers.

<!-- ===================== DISCORD END ===================== -->


<!-- ===================== APPENDIX BEGIN（備用，作者質疑數字時再貼）===================== -->

### Appendix — how each figure was derived

Window: server `DebugLog-server.txt` content timestamps 2026-10-07 → 2026-10-14 14:56 (UTC+8), sessions of ≥ 30 min only. Save snapshot: `gos_psr_powerbank.bin` at 14:50 on 10-14. Positions omitted by design.

```
FIELD DEFINITIONS
  REMOVE            = any 'PSR: coverage REMOVE' line
  same-frame pair   = two REMOVE lines of different banks in one network, same f: frame,
                      consecutive; gap = print-time difference
  flicker repeat    = the same bank logs another REMOVE within 7 minutes
  rect positions    = (x2-x1+1) x (y2-y1+1) x (z2-z1+1) over PSR_coverRect of activated banks

PER DAY                 hours   REMOVE   per hour   flicker repeats
  10-07                  23.7     150       6.3          56
  10-08                  22.0     134       6.1          69
  10-09                  25.9     386      14.9         251
  10-10                  21.6     309      14.3         152
  10-11                  20.4     288      14.1         152
  10-12                  23.8     246      10.3         117
  10-13                  25.6     292      11.4         129
  10-14 (to 14:56)       12.9     100       7.7          44
  TOTAL                 178.2    1922      10.8         976 (50.8 %, incl. 57 same-frame)

COST
  same-frame pairs     210   gap median 360 ms   p10 330   p90 410   min 308   max 550
  same bank same frame  57   (52 measurable, gap median 365 ms)
  per hour             10.8 x 0.36 s = 3.9 s
  saved (estimate)     10.8 x ~0.31 s = ~3.4 s / hour

SAVE SNAPSHOT
  banks activated with PSR_coverRect   99
  rect positions                       1,696,147 total, median 6,498, max 107,334

<base-A>
  network charge 193.5 + 193.55 + 249.73 = 636.78 ; drain 267.2 / h -> 2.38 h
  switched-off share 249.73 / 636.78 = 39.2 %
```

<!-- ===================== APPENDIX END ===================== -->

## 敏感資訊審核（不貼）

**確認沒有**：伺服器 IP、port、主機別名、玩家名稱、Steam ID、系統路徑、mod 清單、bank／建築座標。

**座標處理**：bank 一律用 `<bank-P>`、`<bank-Q>`、`<A1>`–`<A3>`，建築用 `<base-A>`；只保留面板數、耗電、電量與 REMOVE 次數（論證需要，不含位置）。原始分析裡的 bank 座標與建築中心座標都沒有帶進來。

**原始碼引用**：只用 PSR 的識別字與 6.9.0 行號；唯一的程式片段是我方修補的三行相交判斷，不含 PSR 原文。引擎只寫類別與方法名，不寫反編譯行號。

## 中文備忘（不貼）

### 本版數據來源

- 一週 log 與存檔快照來自 2026-10-14 的唯讀分析（內部分析稿，不公開）；每筆 REMOVE 0.36 秒是同幀相鄰 bank 的列印間隔推出來的，**沒有用 profiler 量函式層級**，對外已標成估計。
- 差分測試、突變測試與真 Kahlua 編譯在私有 repo `MinidoracatWorkshopCompatPatches`（`scripts/test_psr_reapply_behavior.lua`、`scripts/KahluaCheck.java`）；270 個隨機世界、約 4,700 次 REMOVE（seed 777／2026／9001）。第 1 項的呼叫數下降比例（約 22%／45–48%）是測試裡小而密集的隨機世界，不代表正式服比例，對外已註明。
- 第 1 項「我們準備在伺服器上暫時套用」：寫稿當下尚未上線；送出前若已上線，改成實測的同幀配對間隔。
- 第 4 項的成因（`loadGenerator` 雙路徑或連結不對稱）沒有追，對外已標成猜測。
- 第 2 項的遲滯建議會改變玩法，我方不自行上線，只回報；第 3 項同理。

### 對外報告鐵則（沿用 1.72 版的教訓）

(a) 每個形狀詞（always／never／rules out）先問量測能不能區分它與最接近的替代解釋；(b) 範圍界定用內容欄位不用檔案屬性；(c) 下機制結論前先印原始行讀順序；(d) 時長／速率要能由起訖時間戳驗算；(e) 交付前檢查數字之間互相算得通；(f) 每個量化承諾都要指得出算式與來源；(g) 每個建議都附最壞情況，「跳過／快取」類必須說明失效條件（第 1 項已寫明唯一可觀察差異）；(h) 寫「X 不是原因」之前，先確認觀測管道看得見 X（ADD／reapply 不印 log）。
