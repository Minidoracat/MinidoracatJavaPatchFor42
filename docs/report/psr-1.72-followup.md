# PSR v1.72 — follow-up（15 小時凍結快照版）

- Discord 版：貼「DISCORD BEGIN」到「DISCORD END」之間（含 markdown）
- 敏感資訊審核見檔尾

<!-- ===================== DISCORD BEGIN ===================== -->

## Thank you — v1.72 fixed the regression

We ran v1.72 for **15.01 hours of server uptime across 8 sessions** (2026-08-16 20:04:04 → 2026-08-17 11:12:54 UTC+8; sequential, 1.1–1.3 min restart gaps, no overlap). Every sweep/campaign number below is frozen to that window. **Item 4 necessarily uses a different one** (8/16 06:12 → 8/17 06:12): we installed a server-side filter for the toxic log line at 01:28, so that message stops appearing partway through the frozen window and could not be counted there. Those are the only two windows in this report, and we flag which is which at the point of use. The CPU regression is gone — thank you for the fast turnaround, and for the level of detail in the code comments; several of the numbers below only became readable *because* you added the `bank=` tag.

Your three-point proof for removing the per-square `RecalcAllWithNeighbours` holds up on our side too: `haveElectricity()` reads chunk state live with no cache, the work inside `RecalcAllWithNeighbours` is collision/pathfinding/vision, and vanilla `IsoGenerator.setSurroundingElectricity` does the same job without ever calling it.

### Measured impact

| Metric | v1.71 (`18-12` session, 1.84 h) | v1.72 (8 sessions, 15.01 h) |
|---|---|---|
| `coverage REMOVE` lines | 198 → **107.7 / hour** | 163 → **10.9 / hour** average |
| per-session rate | — | **3.7 – 23.1 / hour** |
| Main-loop fps | 6.4 and declining | **9.93 – 10.02**, flat over 3.5 h |
| `Server is too busy` | 12 in 2.5 h | **1** in 3.5 h — no logged REMOVE in the preceding 10 frames. That does *not* clear PSR, though: as noted in item 3, the ADD/reapply sweeps print nothing, so this is an absence of correlation in the logged line, not an exoneration |

That is **4.7× at the busiest session and 29× at the quietest**, 9.9× on the pooled average — and the fps curve is flat instead of decaying.

---

## Four residual optimizations (not regressions)

A scoping note, because "residual" needs to be precise. **1.72 is not worse than 1.71** — the headline numbers settle that at the aggregate level (9.9× fewer sweeps, flat fps). What we cannot do is compare items 1–3 against 1.71 line by line: items 1 and 2 are *about* the campaign accounting you added in 1.72, and for item 3 the 1.71 log lacks the `bank=` tag needed to disambiguate. Item 4 we *can* compare, and did: toxic volume was 12.8–17.0 k lines/h under 1.71 versus 11.0–17.9 k/h under 1.72 — unchanged. (That comparison uses its own window, 8/16 06:12 → 8/17 06:12, because the toxic line stops at our log filter rather than at the frozen point.) So: item 4 is demonstrably pre-existing, items 1–3 are costs we can only characterise in 1.72. All four are voluntary.

### 1. NEW — the same rect is re-swept across *campaigns*, rediscovering the same unloadable columns

**This is not about the 3-try cap.** We read your comment at the reset site and the cap works exactly as documented — *« Le plafond borne UNE campagne de retrait, pas la vie de l'objet »* — and the reason you give is sound: without the reset, a bank that is toggled a few times would exhaust its quota for life and stop cleaning its coverage forever. We are not asking you to change that.

**What we measured is the cost of the campaigns themselves.** For a base whose owner is offline, the chunk columns stay unloaded indefinitely, so every new campaign re-discovers the *same* unreadable columns from scratch, at full rect cost.

`try1/3` occurrences per bank over the 15 h — each one is a new campaign:

| bank | `try1/3` | `try3/3` | total REMOVE lines |
|---|---|---|---|
| A | **16** | 4 | 26 |
| B | **13** | 1 | 15 |
| C | **12** | 0 | 12 |
| D | **10** | 0 | 10 |
| E | 7 | 4 | 15 |
| F | 6 | 2 | 13 |

Note bank C: `try3/3` never appears in 15 h — it never exhausts its budget — yet it still starts 12 separate campaigns. And the most-swept rects:

| rect size | sweeps in 15 h |
|---|---|
| 55,296 sq (64×48×18) | **41** |
| 3,264 sq | 15 |
| 9,792 sq | 13 |
| 6,912 sq | 12 |
| 4,608 sq | 12 |
| 10,368 sq | 10 |

Where we could follow a single rect over time, the `unreadable` count did not move (`unreadable=384` on a 48,379-touched rect, identical across 6 hours). The removals themselves are not wasted — GARDE 2 re-adds neighbouring coverage, so there is real work to undo — but re-*discovering* which columns are unreadable is repetition.

**Suggested direction (compatible with your cap semantics):** remember the *unreadable columns* rather than the try count — a per-rect set of chunk coordinates that came back unreadable, skipped on subsequent campaigns. The bank keeps its ability to clean (your invariant, untouched), and skipping is a no-op for correctness *while the column is still unloaded*, because `getGridSquare` returns nil there anyway. **The part we cannot design for you is invalidation**, and it is the part that matters: if a column comes back and the skip list does not notice, that coverage would never be cleaned again — strictly worse than today. We do not know your chunk-load hooks well enough to propose the mechanism, but a coarse bound would cap the downside — e.g. drop the whole skip list every N campaigns, or whenever any `getGridSquare` in that rect succeeds. Worst case then costs one extra full sweep occasionally instead of a permanent hole.

---

### 2. NEW — banks in the same building each run their own campaign over the *same* rect

Your comment at the log site asks exactly this question:

> *« 10 balayages du même rect » pouvait être **une** bank qui boucle (échec du plafond) ou **quatre** banks à trois essais (succès). Les deux s'impriment à l'identique.*

The `bank=` tag answers it: **it is the latter.**

`PSR_coverTries` lives on the powerbank, but `psrCoverRect(building, self.z)` is a function of the **building** (and the z) — so banks sharing a building *and* a z produce byte-identical rects, and each runs its own campaign over it. All three shared-rect groups we saw were same-z:

| banks sharing one rect | rect size | total sweeps of that rect |
|---|---|---|
| **3 banks** | 4,608 sq | 12 |
| **2 banks** | 55,296 sq | **41** |
| 2 banks | 13,056 sq | 2 |

The two banks are one tile apart in the same building, and their sweeps land in the **same frame**, 111 ms apart:

```
f:3495  bank=B try1/3  rect(R) touched=48379 unreadable=384 complete=false
f:3495  bank=A try3/3  rect(R) touched=48379 unreadable=384 complete=false
```

We are **not** suggesting you merge these into one sweep. We read GARDE 2: `psrReapplyOtherCoverage` is unconditional and re-adds the coverage of every still-activated bank, so when bank A is still ON at the moment bank B is removed, A's coverage is legitimately re-applied — and A's own later REMOVE is the only thing that can clear it. A shared per-rect budget would strand A's coverage permanently. This is a cost report, not a redundancy claim.

What we can say is the multiplier: a building with *n* banks on one z pays *n* campaigns over an identical rect. If there is a safe way to amortize that (e.g. one REMOVE pass over the rect followed by a single reapply, once all banks of that building have been processed in the current pass), the 40-sweep rect above is where it would pay off. We are not confident enough about the ordering constraints to propose the change itself.

---

### 3. Two tries consumed in one tick — and one sample shows the whole pass repeating

**What:** for a given bank, `try N` and `try N+1` sometimes appear within the **same `f:` frame number** — i.e. the same main-loop tick. One try is charged against the 3-attempt cap with no opportunity for chunk state to change.

**Scale:** 19 such bank-level pairs over the 15 h, spread across 17 frames — 16 later in the session, 3 in the first few frames after boot. The 29 pairs that *do* span frames fall into two different things, and we should not lump them: three sit at **35 frames**, and those are the same campaign's `try2 → try3` retry **5.7 s** later (the `f:4654 → f:4689` sequence below), not a pass boundary. Excluding those, the smallest gap is **217 frames (22.8 s)**, and the rest spread from there — 19 land at 120–200 s matching `ChargeFreq=2`, with two outliers at 547 s and 659 s, so pass spacing drifts with load and is not a clean cadence. What the frame numbers *do* establish is narrow but sufficient: no pair sits on an adjacent or near-adjacent tick, so the same-tick pairs are not a frame-boundary artefact.

**One frame tells us the mechanism.** Most of these frames contain only one bank, where "the pass ran twice" and "this bank was re-entered" look identical. Frame **4654** had three banks in one building, and the ordering separates them:

```
22:03:52.128  f:4654  bank=X,Y,1    try1/3 legacy
22:03:52.391  f:4654  bank=X,Y+1,1  try1/3 legacy
22:03:52.650  f:4654  bank=X,Y+2,1  try1/3 legacy
22:03:52.902  f:4654  bank=X,Y,1    try2/3 legacy    ← second round begins
22:03:53.147  f:4654  bank=X,Y+1,1  try2/3 legacy
22:03:53.380  f:4654  bank=X,Y+2,1  try2/3 legacy
```

All three `try1` complete **before** any `try2` starts. That is the whole bank list being walked twice inside one tick — not a single bank re-entering its own branch. Six full rect sweeps in that tick, and it lasted at least 1.25 s. All six report `touched=2295 unreadable=128 complete=false`, byte-identical, so the second round learned nothing. (The follow-up `try3` lands 35 frames later at `f:4689` and succeeds with `unreadable=0 complete=true`.)

**A second signal — `legacy` and the normal path both sweep.** At `f:12824` one bank produced:

```
22:19:20.786  bank=D try1/3          touched=10365 unreadable=0 complete=true
22:19:21.016  bank=D try1/3 legacy   touched=10365 unreadable=0 complete=true
```

Two sweeps in one tick, both labelled `try1` — so this pair is not a budget increment at all, it is the legacy call site and the normal one each doing a full sweep. Overall `legacy` appears on **33 of 163** REMOVE lines, and there is a third variant we did not expect: **5 lines tagged `demontage`** with no `tryN/3` at all.

`psrReapplyOtherCoverage` cannot account for any of this: it calls `psrSweepRect(oRect, false)`, and the log line is only emitted when `remove` is true. So every line above is a real removal sweep.

**Is this new in 1.72?** We cannot tell, and we want to be straight about that. Our 1.71 log does contain two REMOVE lines for one rect in one frame 19 ms apart — but that was before the `bank=` tag, so those two lines are exactly the ambiguous case your own comment describes: one bank looping, or two banks each doing their quota, printing identically. So we can only say the *shape* predates 1.72, not the behaviour. This is the second time the tag paid for itself.

**Suggested direction:** a per-tick guard would collapse the double walk, but given the `legacy` finding the more useful question is probably why both call sites fire in the same tick. We are reporting the observation rather than proposing the fix.

*(Incidental confirmation: one early-session pair is `try2/3 → try3/3` at `f:2`, meaning `try1` was spent in the previous session — so `PSR_coverTries` does persist across restarts, as your design intends.)*

---

### 4. `suppressToxic` broadcasts one packet per *bank* instead of per *building*

**What:** `PBSystem.suppressToxic` hooks `Events.EveryOneMinute` (≈ 2.5 real seconds at Day Length = 1 h) and iterates over **powerbanks**, so banks sharing a building send byte-identical `ToxicBuilding` packets — same coordinates (building bbox centre), same payload.

Measured over the 15.41 h before we filtered the log (8 sessions): **164,176 toxic lines out of 360,669 total = 45.5 % of the entire server console** (per-session range 35.5 %–80.8 %), 17–25 distinct building coordinates, each recurring every 2.5–3 s. At 63 players that is ≈ 175 packets/s ≈ 7 KB/s. Bandwidth is negligible; the problem is that it buries every other line in the console, which is where we diagnose freezes and data-loss incidents. (Same volume in 1.71 — not a 1.72 change.)

**Where the volume actually comes from — we measured this rather than assuming.** Deduplicating on `(frame, building, value)` removes **19.8 %** (32,477 of 164,176), taking the rate from 10,654/h to 8,546/h. The distribution explains why it is not more: **96,451** `(frame, building)` pairs appear exactly once, versus 30,982 twice (plus a long tail: 1,182 at four, 47 at six, one at eight). So same-frame duplication is the minority; the bulk is **the same building being re-broadcast every 2.5 s across different frames**, which no per-pass dedup can touch.

**Please do not gate this on `isToxic()`.** We verified in bytecode why your v1.39 → v1.40 note is right: on the client, `WorldRegionToMetaGrid.lambda$updateSquares$0` sets `IsoBuilding.toxic = true` for buildings with an indoor activated generator, and that path runs **only** in the `!GameServer.server` branch and never notifies the server. The server's `isToxic` is therefore just a record of "what I last sent", not a mirror of client state — gating on it would suppress the only packet that can clear the client flag, and gas is destructive.

**Suggested fix, with honest sizing:** dedup by building within one `suppressToxic` pass (a `processed[buildingId]` set). `setToxic` is idempotent and the payload identical, so semantics should be unchanged — but per our measurement it only removes **~20 %** (≈ 2,100 of 10,654 lines/h). The remaining ~80 % is the per-2.5 s cadence itself. If the `EveryOneMinute` hook could be a longer interval, that is where the rest lives — and it is your call whether the immediacy of gas suppression is worth it. One thing we would *not* do: skip buildings whose generator is currently off. That walks into the same trap as state dedup — the `false` packet is precisely what clears the client-side flag, and the client sets that flag without telling you.

---

## Summary

Four optimization opportunities, no bugs and no regressions. **v1.72 is ready to ship.** Our server is flat at 9.93–10.02 fps with the charging system behaving as intended.

We are still on `ChargeFreq=2` (the 1.71 workaround), which is a **÷6** on *pass frequency*. We first assumed that scales the sweep rate ×6 too — for the REMOVE line it does not, and your cap is the reason. Over the 8 sessions there were **109 campaigns** (`try1` occurrences) producing **158 sweeps**, i.e. **1.45 per campaign against a ceiling of 3**. Campaign count comes from activation churn, not pass frequency, so `109 × 3` = 327 bounds it at **×2.07 ≈ 22.6/h, 21 % of 1.71's 107.7/h** — and the real figure lands lower still, because **66 % of campaigns (72/109) already reach `complete=true` on `try1`** and cannot get more expensive however often the pass runs.

**That bound covers only the logged REMOVE sweeps, though, and we want to be explicit about what it misses.** As noted above, `psrSweepRect(…, false)` prints nothing, so none of the following is in our numbers: the GARDE 2 reapply that runs after *every* REMOVE (once per still-activated bank), the ADD sweep on the activation path itself, the 5 `demontage` lines, and whatever second sweep the `legacy` call site contributes. **None of those is bounded by the 3-try cap**, and the ADD/reapply work plausibly does scale with pass frequency. So we cannot extrapolate total cost from this log at all — which is exactly why your own 1.71 suggestion #4 (a low-frequency log line for the ADD sweep) would be worth having.

Given that, we will treat `ChargeFreq=1` as a measurement rather than a prediction: change it alone, nothing else altered, and report the fps delta either way. On rollback, to be precise since it is not instant — `ChargeFreq` is a sandbox option, so reverting means editing the setting and waiting for a restart; our server restarts on a schedule, so the worst case is one scheduled cycle at the higher rate.

<!-- ===================== DISCORD END ===================== -->


<!-- ===================== APPENDIX BEGIN（備用，作者質疑數字時再貼）===================== -->

### Appendix — how each figure was derived

Frozen at **2026-08-17 11:12:54**; every line with a later timestamp is excluded. Sessions are labelled S1..S8 in chronological order. Coordinates are omitted by design.

```
FIELD DEFINITIONS
  logged REMOVE = any 'PSR: coverage REMOVE' line (tryN/3 variants + demontage)
  sweep         = a logged REMOVE carrying tryN/3
  campaign      = one try1/3 occurrence (the allowance resets on each activation)
  uptime        = last timestamp - first timestamp, within the frozen window

PER-SESSION
  S1  uptime  1.15 h   REMOVE  26    22.6/h
  S2  uptime  0.65 h   REMOVE  15    23.1/h
  S3  uptime  2.29 h   REMOVE  48    21.0/h
  S4  uptime  1.25 h   REMOVE  11     8.8/h
  S5  uptime  2.57 h   REMOVE  22     8.6/h
  S6  uptime  0.81 h   REMOVE   3     3.7/h
  S7  uptime  1.29 h   REMOVE   9     7.0/h
  S8  uptime  5.00 h   REMOVE  29     5.8/h
  ---------------------------------------------
  TOTAL  uptime 15.01 h   REMOVE 163    10.9/h   = 163 / 15.01

v1.71 BASELINE (one session, zero lines carry bank= )
  REMOVE 198 / 1.84 h = 107.7/h   (lines with bank= : 0)

REDUCTION
  pooled      107.7 / 10.9 = 9.9x
  per-session 107.7 / 23.1 = 4.7x   ..   107.7 / 3.7 = 29x

VARIANTS AND CAMPAIGN STRUCTURE
  logged REMOVE      163  =  158 tryN/3  +  5 demontage
  lines with legacy  33
  try1 109   try2 28   try3 21
  sweeps per campaign  = 158 / 109 = 1.45   (ceiling 3)
  ceiling bound        = 109 x 3 = 327  ->  327 / 158 = x2.07
  ChargeFreq=1 bound   = 10.9 x 2.07 = 22.6/h  ->  22.6 / 107.7 = 21%
  try1 complete=true   = 72 / 109 = 66%

PAIRS (consecutive try N -> N+1 for the same bank; awk state reset per file)
  same frame   19   (spread over 17 distinct frames)
  cross frame  29   (smallest frame gap = 35)
  total pairs  48

TOXIC WINDOW (separate: 8/16 06:12 -> 8/17 06:12, because our filter lands at 01:28)
  toxic / total lines = 164176 / 360669 = 45.5%
  rate                = 164176 / 15.41 h = 10654/h
  dedup on (frame, building, value): kept 131699, removed 32477 = 19.8%
  post-dedup rate     = 131699 / 15.41 h = 8546/h
```

<!-- ===================== APPENDIX END ===================== -->

## 敏感資訊審核（不貼）

**確認沒有**：伺服器 IP、port、玩家名稱、Steam ID、系統路徑、mod 清單。

**座標處理**：bank 用 A–F／`X,Y+n` 代號、rect 只給尺寸與 square 數、log 樣本用 `rect(R)`。保留的具體數字是 `touched` / `unreadable` / `skippedGen` 與 frame 編號（論證需要，不含位置資訊）。第 4 項只說「13–15 distinct building coordinates」不給座標。無洩漏風險。

**附錄的匿名處理**：`temp/psr-appendix.sh` 產生的是**去座標版本**——session 匿名為 S1–S8（僅時序）、
不含任何 bank／rect／building 座標、不含 log 檔名。刻意**不**直接貼 `psr-frozen.sh` 的原始輸出，
因為那會攤開真實 rect 與 bank 明細，違反上面這條座標策略。附錄保留的是每個數字的**算式與欄位定義**
（例如 `163 / 15.01`、`109 x 3 = 327 → 327/158 = x2.07`），作者可拿去對自己的 log 重算而不需信任我的轉述。
預設不貼；只在對方質疑某個數字時貼該區塊。

## 中文備忘（不貼）

### 這一輪共二十三次修正（前七次見 git history）

抓出者：**Codex 15 次、Grok 2 次、自查 6 次**（15+2+6=23）。前七次是措辭／統計方法問題；**第八次之後都是實質錯誤**。第 20/21 次值得特別記：那是**採納正確 advisory 之後衍生的新錯誤**——Codex 指出「÷6 是 pass 頻率」完全正確，我卻把它直接當成 sweep 率倍數，再被同一位 advisor 抓第二次。**採納修正時要重新檢查它影響的每個下游推論，不能只改那一個數字。**

- **第八次：「四項都是 1.71 就有」是錯的。** 作者註解明寫 `PLAFOND DE TENTATIVES — AJOUTÉ LE 2026-08-16`——cap 本身是 1.72 新增。第 1／2 項建立在 `PSR_coverTries` 上，1.71 的 log 既無 tag 也無 campaign 概念，**根本無法對照**。已改成只宣告第 3／4 項有 1.71 證據，第 1／2 項明說無法比較。另外 `psrCoverRect(building, self.z)` 吃 z，「同建築同 rect」只在**同 z** 成立，已加限定並實測確認兩組共用 rect 都是 z1。
- **第九次：資料污染——把 1.71 對照組混進 1.72 統計。** 先前腳本用 `find -newermt '2026-08-16 20:00'`（**mtime**）界定範圍，把 1.71 的 `18-12` session（198 筆，mtime 落在 20:04）掃了進來。我報告的「359 筆」＝ 162（1.72）＋ 198（1.71）－ 1。改用**檔名**界定後：1.72 是 **162 筆**，1.71 是 **198 筆 / 1.84h / 107.7 筆每小時**。連帶錯誤：(a) 時長被跨午夜 bug 算成 `21-54` session=24.00h（只取時分秒），改用完整日期後是 2.29h；(b) rect 表格的「80,640 sq × 47 次」**根本不存在**——真正的 top 是 55,296×40，而 80,640 那個 rect（z0..19）只有 3 次；(c)「2 banks 13,056 sq」那組也不存在。**教訓：`-newermt` 是 mtime 不是內容時間，界定 log 範圍要用檔名。**
- **第十次：機制判斷錯誤，而且真相更有價值。** 我把「同一顆 bank 同 tick 兩次 try」寫成單一 bank 分支重入，並拿 `f:4654` 當最強樣本。實際印出原始行才發現 `f:4654` 是**三顆 bank 的 try1 全部跑完、才開始三顆的 try2**——交錯順序證明是**整份 bank 清單在一個 tick 內被走了兩遍**，不是單 bank 重入。多數 frame 只有一顆 bank，兩種機制看起來一樣，**只有這一個 frame 能區分**。另外撞到兩個全新事實：(a) `f:12824` 同一顆 bank 同 tick 出現 `try1` 與 `try1 legacy`，**兩次都是 try1** → legacy 與正常路徑是各自獨立的 sweep 呼叫點，那組配對根本不是額度遞增；(b) 存在第三種變體 `demontage`（5 筆，不帶 `tryN/3`），我所有腳本先前都漏掉它——這正是 `rect(7456,...)` 在兩支腳本間 9 vs 12 的差異來源。`legacy` 共 33/162 筆。
- **第十一次：時長仍然錯，是 advisory 用算術抓到的。** 我寫「20:04 → 隔日 09:36」卻同時寫累計 13.96h——後者大於前者的牆鐘區間 13.53h，物理上不可能。查證後兩個數都錯：09:36 是我先前巡檢的時間、不是 log 末行；而 `psr-recount2.sh` 給 `06-12` session 3.95h，實際末行是 **10:19:06**（06:12:39 起算 = 4.11h）。改用會印出實際起訖時間戳的腳本（可人工驗算）後定案：**累計 uptime 14.12h、牆鐘跨距 14.25h、8 個 session 循序無重疊（間隔 1.1–1.3 分鐘＝重啟時間）、速率 11.5/h、平均降幅 9.4×**。**教訓：任何時長／速率都要印出起訖時間戳讓人能驗算，不能只印算好的小時數。**
- **第十二次：拿「最小 Δf=35」當族群分界，與自己下一段矛盾。** 我在 Scale 段寫「最小 Δf=35 幀 → 同 tick 與 once-per-pass 是分離族群」，卻在同一節下一段自己寫明那 35 幀就是 `f:4654 → f:4689` **同一 campaign 的 try2→try3**（實測 5.7 秒後重試）——那根本不是 pass 邊界。已改成三分：同 tick（Δf=0）19 筆／同 campaign 快速重試（Δf=35，5.7s）3 筆／pass 間隔（Δf≥217，22.8s 起）26 筆，並把可主張的結論收斂成「沒有任何配對落在相鄰或近相鄰 tick，故同 tick 那批不是 frame 邊界假影」。
- **第十三次（規則 (e) 首次生效，自查抓到）**：上一輪把那個重試間隔寫成「~3.5 s」，實際 `22:03:52.902 → 22:03:58.630` ＝ **5.728 秒**（ev9 的輸出本來就寫著 `Δf=35 Δt=5.728s`，我引用時憑印象寫錯）。交付前逐項驗算 17 組數字時抓到——這條規則寫下不到十分鐘就抓到一個錯，值得保留。
- **第十四次：第 4 項的「~9.5k → ~200 lines/hour」是我編的**（無任何計算依據），實測後**大幅縮水**。按 `(frame, building, value)` 去重只消除 **19.8%**（32,477/164,176），行率 10,654/h → 8,546/h。分佈說明原因：`(frame,building)` 組合有 **96,451 個只出現 1 次**、30,982 個 2 次（長尾：1,182 個 4 次、47 個 6 次、1 個 8 次）——**同 frame 重複是少數，主體是同一 building 每 2.5s 跨 frame 反覆送**，per-pass 去重碰不到。已改成誠實標示 ~20%，並指出剩下 80% 在 `EveryOneMinute` 的 cadence 本身（且明說我方不建議用狀態去重解，理由同 client 標記問題）。
- **第十五次：34.4% 這個佔比也不對。** 那是先前巡檢用單一時間窗算的（且對不上任何 session 的實際行數）。改用可驗算的範圍：抑噪前 15.41 小時／8 session，**164,176 / 360,669 = 45.5%**，逐 session 35.5%–80.8%，相異座標 17–25 個。
- **第十六次：抑噪生效時間我記錯了。** 先前寫「06:12 重啟生效」，實際 `01-28` session 起 toxic 就已歸零（`01-28`／`04-04`／`04-53` 三個 session 全為 0，只有 `00-12` 及更早有）。`PatchInfo built=2026-08-17T00:16` → 部署後第一次重啟即 `01-28`，故**生效時間是 01:28**。06:12 只是我當時抽查的那個 session。已修 AGENTS.md 與 optimization-summary.md。**教訓：驗證「某訊息歸零」時要往回找最早歸零的 session，不能只確認自己抽查的那一個。**
- **第十七次：scoping note 自相矛盾。** 同一段先說第 1–3 項「cannot compare against 1.71」，又說「None of the four is a regression」——無法對照就不能斷言不是退步。已改成兩層：**整體**由 headline 數字定案（9.4×、fps 平坦），**逐項**只有第 4 項真的做過對照（toxic 行率 1.71 期 12.8–17.0 k/h vs 1.72 期 11.0–17.9 k/h，區間重疊＝unchanged；我掃的 15.41 小時正好橫跨兩版，`18-12` 是 1.71 最後一場、`20-04` 起有 bank tag）。第 1–3 項明說只能在 1.72 內描述。
- **第十八次：第 1 項的建議缺失效機制，而那正是最危險的部分。** 原文只寫「skipped until that column is observed loaded again」——沒說怎麼觀察。若 column 回來了而 skip list 沒察覺，那塊覆蓋就**永遠不會被清理**，比現況更糟。已改成明說「invalidation 我們無法替你設計」，並給保守上界（每 N 個 campaign 清空、或該 rect 內任一 `getGridSquare` 成功即清空），把最壞情況從「永久空洞」降為「偶爾多掃一次」。
- **第十九次：第 4 項的另一個建議與我自己同節的警告矛盾。** 原文說「the sweep could skip buildings with no activated generator」——但同一節上方我才剛解釋 `false` 封包是唯一能清除 client toxic 標記的東西，而 client 會自行標記且不通知 server。跳過關閉的 generator 會踩同一顆雷（把玩家鎖在毒氣室）。已刪除該建議並改成明確的反面提醒。**教訓同第 12 次：整節寫完要自己讀一遍前後段是否打架。**
- **第二十次：ChargeFreq 的 ×6 我又用錯了。** 採納 Codex 的「÷6 是 pass 頻率」之後，我直接把它當 sweep 率倍數寫成 69/h——錯。**campaign 數來自 activation churn，與 pass 頻率無關**，而每個 campaign 的 sweep 被 cap 封在 3 次。凍結窗實測：**109 個 campaign／158 次 tryN sweep ＝ 平均 1.45（上限 3）**⇒ 上界 `109×3=327` ＝ **×2.07 ≈ 22.6/h**，只有 1.71 的 21%。更強的交叉檢查：**66%（72/109）的 campaign 在 try1 就 `complete=true`**，這批不管 pass 多密都只掃一次。（諷刺的是我最初隨口說的 23/h 碰巧接近正解，但推理是錯的「÷2」。）
- **第二十一次：22.6/h 也不能當總成本上界。** cap 只封住帶 `tryN/3` 的 REMOVE 那條線。**不印 log 的部分完全不受約束**：GARDE 2 的 reapply（每次 REMOVE 後對每個仍 ON 的 bank 各一次）、activation 分支自己那次 ADD sweep、5 筆 `demontage`、`legacy` 第二條路徑——`psrSweepRect(…, false)` 不 print（我自己在報告 `:122` 就寫過這件事），所以它們一筆都不在我的數字裡，而且 ADD/reapply 很可能真的隨 pass 頻率線性成長。已改成明確標示「logged REMOVE 的上界」＋列出遺漏項，並指出這正是作者自己 1.71 建議清單第 4 項（ADD sweep 的低頻 log）會派上用場的地方。**結論：ChargeFreq=1 的總成本無法從現有 log 外推，只能實測。**
- **第二十二次：`easy rollback` 不誠實。** `ChargeFreq` 是 sandbox option，改了要等重啟才生效，不是即時回退。已改成明說「最壞情況是一個排程週期跑在較高的 rate」。
- **第二十三次：把「log 缺席」當成因果排除。** headline 表格與 optimization-summary 都寫著「該次 `too busy` 前 10 幀無 PSR 行＝**另有成因**」。但我在同一份報告的第 21 次修正裡才剛證明 `psrSweepRect(…, false)` 不印 log——ADD／reapply 可能正在跑而完全不留痕跡。所以「前 10 幀無 logged REMOVE」只能說**該條 log 線上沒有時間關聯**，不能升級成「PSR 無罪」。已改成明說「this is an absence of correlation in the logged line, not an exoneration」，並在誠實邊界補上「也沒有把它當成 PSR 無罪的證據」。

### 共通模式（值得記）

- 第 1／2／8 次：沒讀完對方程式碼／註解就下判斷。
- 第 3／9／11 次：腳本缺陷。**三次都是「範圍或區間界定」出錯**——跨 session 狀態殘留、mtime 當內容時間、時長只取時分秒／末行認錯。
- 第 4／5／6／7 次：手上有一個數字，跳過「這個數字能支撐這句話嗎」直接寫成結論（編機制、拿平均值推分佈、拿否證預測當否證假說、用抓不到目標情形的篩選宣告已排除）。
- 第 10 次：**只看聚合統計、沒看原始行**。19 個配對數字是對的，但「配對」背後的事件順序完全沒看過，所以機制講錯。
- **第 16 次：抽樣不具代表性**。驗證 toxic 歸零時我只確認自己抽查的 `06-12` session，沒往回找最早歸零的那一場（實際是 `01-28`），於是把生效時間記錯 4 小時 44 分。與第 10 次同源——**都是拿一個樣本代替整個集合**。
- **第 12／17／19 次：同一份文件內前後段互相矛盾**——這三次都是我在同一節裡先下一個斷言、幾行後又寫出否證它的事實。第 12 次拿同 campaign 重試當 pass 邊界（下一段自己寫出那 35 幀是 try2→try3）、第 17 次說「無法對照」又說「不是 regression」、第 19 次建議跳過關閉的 generator（同節上方才剛解釋那會把玩家鎖在毒氣室）。**這類錯誤不需要新數據就能抓到，只需要把整節讀完一次。**
- **第 13／14／15 次：憑印象寫出量化承諾**。「~3.5 s」「~200 lines/hour」「34.4%」全都不是算出來的。第 14／15 次尤其致命——那是對外報告裡的效益承諾，作者一對自己的 log 就會發現差 40 倍。
- **第 18 次：提建議時沒想失敗模式**。「記住不可讀的 column 並跳過」聽起來安全，但我沒說失效機制；若 column 回來而 skip list 沒察覺，那塊覆蓋永遠不會被清理，**比現況更糟**。給別人的建議必須自帶最壞情況分析。
- **第 6／23 次：拿「沒看到」當「不存在」**。第 6 次是密度相同就宣告「不是 startup batch」，第 23 次是 log 缺席就宣告「另有成因」。共同結構：**在一條已知不完整的觀測管道上，缺席什麼都不能證明**。這條特別危險，因為它讀起來像結論而不像猜測。
- 往後對外報告的鐵則：(a) 每個形狀詞（bimodal／batch／always／never／rules out）先問「我的量測能不能區分它與最接近的替代解釋」；(b) 範圍界定用內容欄位不用檔案屬性；(c) **下機制結論前必須印原始行讀一次順序**，聚合數字不能替代；(d) 每個時長／速率都要印起訖時間戳供人工驗算；(e) 交付前把「數字之間互相是否算得通」當一道獨立檢查（13.96h > 13.53h 這種矛盾用小學算術就抓到了）；(f) **每個對外的量化承諾都要指得出算式與來源腳本**，憑印象的數字一律重算；(g) 提給對方的每個建議都要附最壞情況，尤其「快取／跳過」類建議必須說明失效機制；(h) **寫下任何「X 不是原因」之前，先確認觀測管道能看見 X**——本專案的 log 只印 REMOVE 側，ADD／reapply 全程隱形。

### 本次數據來源與方法

- **凍結快照：`2026-08-17 11:12:54`（含）以前的 log 行。** 由 `temp/psr-frozen.sh` 一次算出報告
  用到的每個 sweep／campaign 數字，凍結點寫在腳本開頭，**重跑必得相同結果**（伺服器仍在運行，
  沒有凍結點的話每次重算都會漂移——本輪就因此出現過 162／164 兩個版本）。
- **本報告只有兩個時間窗**：(1) 上述凍結窗，供第 1–3 項與 Measured impact；(2) 第 4 項的 toxic
  對照窗 `8/16 06:12 → 8/17 06:12`——因為我方在 01:28 部署了該訊息的 server 端抑噪，凍結窗內
  它中途就消失、無法計數。兩處都在使用點標明。
- 1.72 範圍：檔名 ≥ `2026-08-16_2004` 的 8 個 `DebugLog-server.txt`，凍結窗內共 **163 筆**
  `coverage REMOVE`（**158 筆帶 `tryN/3`** ＋ **5 筆 `demontage`**；**33 筆帶 `legacy`**；全部帶 `bank=`）。
  1.71 對照組 `18-12` session 獨立統計，**198 筆、0 筆帶 tag**（確認 tag 是 1.72 才加的）。
- campaign 結構：`try1` 出現 **109** 次＝campaign 數（每次 activation 重置額度）、`try2` 28、`try3` 21。
  平均 **1.45 sweep/campaign**（上限 3）⇒ 全滿上界 `109×3=327` ＝ **×2.07**；`try1` 即 `complete=true`
  者 **72/109 ＝ 66%**。
- 時長用 log 行內的 `DD-MM-YY HH:MM:SS` 完整解析（含日期），並**印出每個 session 的實際首末時間戳
  供驗算**：8 個 session 循序、間隔 1.1–1.3 分鐘（重啟），凍結窗內累計 uptime **15.01h**、
  平均 **10.9/h**、per-session **3.7–23.1/h**。
- 配對：逐檔跑 awk、每檔狀態重置，同 bank 連續 `try N → try N+1`，用 `f:` frame 編號判定同 tick。19 個 same-frame（16 later ＋ 3 early，分佈於 17 個 frame，`f:4654` 一個 frame 內含 3 個配對）。跨 frame 的 29 個依 Δf 三分：35 幀×3（同 campaign 重試）／217 幀起×26（pass 間隔）。注意 `f:12824` 的 `try1 → try1 legacy` **不符配對條件**（非遞增），故不在 19 之內，另案當 legacy 雙路徑證據。
- rect 尺寸由 `rect(x1,y1..x2,y2 zA..zB)` 直接算 `(x2-x1+1)×(y2-y1+1)×(zB-zA+1)`。
- 誠實邊界：`too busy` 只剩 1 次，且該次前 10 幀無 **logged REMOVE**——但這只是「該條 log 線上沒有時間關聯」，**不等於排除 PSR**：ADD／reapply sweep 不印 log（見第 21 次修正），所以無法從缺席推論無關。故本報告**沒有**把 too busy 當成 PSR 的證據，也沒有把它當成 PSR 無罪的證據。
- `psrReapplyOtherCoverage` 只呼叫 `psrSweepRect(oRect, false)`，log 只在 `remove` 為真時印 → log 裡每一筆 REMOVE 都是真的移除 sweep。已讀遠端原始碼 525-556 行確認。
- 腳本：**`temp/psr-frozen.sh`（凍結快照，報告數字的唯一權威來源）**；輔助：`psr-frames2.sh`
  （frame 分組與原始順序）、`psr-ev8.sh`（Δt 分佈）、`psr-ev9.sh`（Δframe）、`psr-verify.sh`
  （原始行核對）、`psr-spans.sh`（session 起訖與重疊檢查）、`toxic-dedup.sh`（第 4 項的去重量測）。
  `psr-recount2.sh` 與 `campaign-cap.sh` 是凍結前的版本，已被 `psr-frozen.sh` 取代。

### 我方狀態

- `ChargeFreq` 仍為 2，尚未回復 1。**÷6 是 pass 頻率，不是 sweep 率**：campaign 數來自 activation churn 而與 pass 頻率無關，且每個 campaign 被 cap 封在 3 次，故 logged REMOVE 的上界是 `109×3=327` ＝ **×2.07 ≈ 22.6/h**（僅 1.71 的 21%），且 **66%（72/109）的 campaign 在 try1 就 complete=true**、不受 pass 密度影響。**但 22.6/h 只封住 logged REMOVE**——GARDE 2 的 reapply（每次 REMOVE 後對每個仍 ON 的 bank 各一次）、activation 的 ADD sweep、`demontage`、`legacy` 第二條路徑全都不印 log 且不受 cap 約束，很可能隨 pass 頻率線性成長。**故總成本無法從 log 外推，只能實測**：離峰開、盯 fps 與 `too busy`、不與其他變更同批。回退非即時（sandbox option 需等重啟），最壞情況是一個排程週期跑高 rate。
- 我方已對 `GameServer.sendToxicBuilding` 的 log 做 server 端抑噪（只攔 log、不動封包），所以**未來無法再從我們的 server console 觀測 toxic 廣播頻率**；第 4 項的數據是抑噪前蒐集的。若作者需要更多樣本，client 端的 `Receive Toxic Building` 仍在。
