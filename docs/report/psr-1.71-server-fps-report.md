# PSR v1.71 — dedicated-server CPU regression（bug report）

- Discord 版：貼「DISCORD BEGIN」到「DISCORD END」之間（含 markdown）
- Steam 版：貼「STEAM BEGIN」到「STEAM END」之間
- 敏感資訊審核見檔尾

<!-- ===================== DISCORD BEGIN ===================== -->

**v1.71 — dedicated server main-loop CPU regression: `psrSweepRect` calls `RecalcAllWithNeighbours` per square, and the REMOVE sweep retries forever on unloaded bases**

**Environment**
- Project Zomboid B42.20.2, Linux dedicated server (Java 25 / ZGC, 32 GB heap)
- Server normally runs 9–10 fps main-loop at 70–80 concurrent players; PSR (pre-1.71) has been running on it for weeks with no measurable cost
- PSR updated on the server 2026-08-15 05:53 UTC+8 (= v1.71; file mtime and the 2026-08-14 comments in `PowerBankObject_Server.lua` match)
- ~13 distinct powerbank coverage rects in this world; several belong to bases whose owners are offline → chunks unloaded
- Sandbox: `PSR.ChargeFreq = 1` (every 10 game-min ≈ 25 real s here)

**Symptom**
Server main-loop tick rate (from the `f:` counter in `DebugLog-server.txt`) fell from ~8.8 fps to **6.4 fps over 2.5 h and still declining**, with only **23 players online**; main thread at **99.9 % of one core**. Players reported the server as very laggy.

**Profiler**
12 jstack samples of `main`, 0.5 s apart: 11/12 RUNNABLE, both dominant stacks converge on
```
IsoGridSquare.RecalcPropertiesIfNeeded / CalculateCollide / CalculateVisionBlocked
  ← IsoGridSquare.RecalcAllWithNeighbours     (6/12)
  ← zombie.Lua.Event.trigger                  (3/12)
  ← LuaEventManager.triggerEvent ← GameTime.update ← GameServer.main
```

**Log evidence** (only REMOVE is logged — the ADD sweep is unlogged by design)
| Session | PSR | `coverage REMOVE` lines |
|---|---|---|
| 08-14 22:29 | pre-1.71 | 0 |
| 08-15 00:12 | pre-1.71 | 0 |
| 08-15 05:53 (update) | 1.71 | 33 |
| 08-15 06:12 | 1.71 | 447 |
| 08-15 14:58 | 1.71 | **1103**, rate rising 56 → 123 lines / 10 min as fps fell |

- 1056 of 1067 lines end in `complete=false`
- the same rect is re-swept every pass — one rect 265× in a single session
- single sweeps touch up to **87,035 squares** (an 80×64×17 rect)

```
[15-08-26 15:00:16.106] PSR: coverage REMOVE rect(R1 z0..17) touched=3562 skippedGen=2 unreadable=378 complete=false.
[15-08-26 16:18:47.781] PSR: coverage REMOVE rect(R2 z0..17) touched=6763 skippedGen=5 unreadable=904 complete=false.
[15-08-26 17:21:29.334] PSR: coverage REMOVE rect(R3 z0..17) touched=3494 skippedGen=16 unreadable=61 complete=false.
```

**Where** — `42.1/media/lua/server/PSR/PowerBank/PowerBankObject_Server.lua`, `psrSweepRect(rect, remove)`:
```lua
if sq and sq.RecalcAllWithNeighbours then
    sq:RecalcAllWithNeighbours(false)
end
```
runs for every (wx, wy, wz) in the rect. `RecalcAllWithNeighbours` recalculates the square **and its 8 neighbours**, so a rect of N squares ≈ 9N recalcs — your own comment already flags this as one of the hottest functions ("13,8 ms de moyenne par appel").

Two callers make it a steady-state tax on a dedicated server:
1. **ADD** — `psrSweepRect(rect, false)` in the activation branch: every ON bank, every pass, full rect × all z, and not logged.
2. **REMOVE** — `psrSweepRect(psrChunkAlignedRect(rect), true)` for OFF banks. `complete` is only true when **every column** of the aligned rect is loaded. On a dedicated server an offline player's base stays unloaded → never completes → `PSR_coverRect` never cleared → re-swept every pass forever, each time doing the full recalc work on whatever part IS loaded (3–7 k squares in the samples). "réessayer est gratuit" holds for `removeGeneratorPos` on an absent position, but the sweep over the loaded squares is not free, and it never stops.

Because `PSR_coverRect` is persisted in modData, a server restart does not clear the retry set.

You already described this exact situation in the v1.70 notes ("once your base is far enough away, the game unloads it, so the mod can no longer see the bank") — the same fact applies to the REMOVE retry.

**Suggested fixes** (either of the first two removes the regression)
1. Don't call `RecalcAllWithNeighbours` per square — recalc once per chunk column after the inner loop (or once per touched chunk after the sweep) instead of per square × 9 neighbours.
2. REMOVE retry policy: don't retry every pass while `complete=false`. Hook `LoadGridsquare` and only re-attempt the chunk that just loaded, or track which columns are already cleaned and stop re-sweeping them.
3. ADD side: skip the recalc when the position is already registered (`addGeneratorPos` is idempotent).
4. Optional: a low-frequency log line for the ADD sweep so admins can see the cost.

**Workaround** we applied: `ChargeFreq = 2` (hourly) → sweep rate ÷6. Helps, but the retry loop still runs.

Full `DebugLog-server.txt` for the sessions above, the 12 jstack dumps and the mod list are available if useful.

<!-- ===================== DISCORD END ===================== -->


<!-- ===================== STEAM BEGIN ===================== -->

v1.71 causes a heavy server-side CPU regression on dedicated servers.

Our B42.20.2 dedicated server normally runs 9–10 fps main-loop with 70–80 players online and PSR has been fine for weeks. Since this morning's update it dropped to 6.4 fps with only 23 players online, main thread at 99.9% of one core, and it keeps getting worse over time.

jstack shows the main thread stuck in IsoGridSquare.RecalcAllWithNeighbours called from a Lua timed event. Our log has 1100+ "PSR: coverage REMOVE ... complete=false" lines in 2.5 h (0 before the update). The REMOVE sweep in psrSweepRect never completes for bases whose owners are offline (their chunks are unloaded on a dedicated server), so it re-runs every pass forever, and every pass calls RecalcAllWithNeighbours on every loaded square of the rect — up to 87,000 squares per call.

Full details with log samples, jstack traces and suggested fixes posted on your Discord. Setting ChargeFreq to hourly cuts the sweep rate 6x as a workaround, but the retry loop itself needs a fix.

<!-- ===================== STEAM END ===================== -->


## 敏感資訊審核（不貼）

已確認**沒有**：伺服器 IP／port、玩家名稱、Steam ID、系統路徑（只有 mod 內相對路徑）、帳號資訊。

**唯一要你判斷的**：三行 log 樣本裡的 `rect(R1..)` 等是**玩家基地在遊戲世界裡的座標**（電池組所在建築的覆蓋範圍）。同伺服器的人看了能知道那幾個基地在哪。PVE 服風險有限，但你之前處理過基地被拆的案子——如果在意，把三行裡的座標改成 `rect(A)`／`rect(B)`／`rect(C)`，論證力道不受影響（作者要的是 `touched`／`unreadable`／`complete=false` 這幾個數字，不是座標）。

## 中文備忘（不貼）
- 定罪鏈：更新前 0 次 → 更新後 33/447/1103；jstack 11/12 在 `RecalcAllWithNeighbours`；`complete=false` 99%；同一矩形 265 次重試
- 已改正式服 `ChargeFreq` 1→2（`pzserver_SandboxVars.lua:1243`，備份 `.bak-20260815-psr`），下次重啟生效
- 改完跑一場後把 1→2 的實測 fps 補進 Discord 版
