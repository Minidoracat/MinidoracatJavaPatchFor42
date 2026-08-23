# TIS 官方回報草稿 — 動物聲音排序比較器違反契約 → 全服主迴圈活鎖

> 用途：貼到 The Indie Stone 官方論壇 Bug Reports 板（B42）。英文本文可直接複製。
> 數據來源：正式服（60+ 人、77 mods）2026-08-23 事故 log ＋ 42.20.3 `javap` 核實。
> 撰於 2026-08-23。發文前補上文末 placeholder。
>
> **規則**：不貼反編譯 Java 源碼；class／method 名稱、bytecode offset、行為描述可以。
> 本報告與 timed-action 報告（tis-bug-report-timed-action-stuck.md）互補：那份是
> 「單一玩家永久卡讀條」，這份是「全服同時卡死」——症狀相似、機制完全不同。

---

**Title:** [42.20.3] [MP] Server-wide livelock: `BaseAnimalSoundManager`'s comparator violates the comparison contract (NaN-unsafe, unstable keys) — one bad float makes every subsequent frame throw, freezing timed actions, item pickup and world time for all players until restart

## Summary

On our dedicated server the main loop entered a self-reinforcing livelock: `IngameState.updateInternal` threw `java.lang.IllegalArgumentException: Comparison method violates its general contract!` (TimSort) intermittently for ~2 hours (1,411 occurrences), then escalated to **every frame**. Because packet ingestion happens earlier in `GameServer.main` than the world update, the server kept accepting connections and chat while the world stopped advancing: every player simultaneously experienced frozen action progress bars, items that could not be picked up, and stopped world time ("someone stopped time" was the player description). Only a full server restart recovers.

Root cause is the distance comparator in `zombie.characters.BaseAnimalSoundManager` plus a structural detail in its `update()` that turns a single bad comparison into a permanent livelock.

## Environment

- 42.20.3 dedicated Linux server, 60+ concurrent players, 77 workshop mods
- Trigger context: an animal pen holding 50–80+ animals (rats/rabbits) plus periodic batch `IsoAnimal.remove()` calls (20/minute) from a cleanup mod. Both are legitimate uses of public API; vanilla despawn/butchering exercises the same paths. The defect itself is entirely in vanilla code.

## Incident timeline (from our server log)

| Time | Event |
|---|---|
| 19:25:03 | batch animal removal (20 rats) at the pen (11133,6874) |
| 19:25:45 | **first** `IllegalArgumentException` from TimSort via `BaseAnimalSoundManager.update` |
| 19:25 – 21:47 | 1,411 intermittent occurrences, frequency climbing |
| ~21:47 | escalates to **every frame** → all players report frozen progress bars / "time stopped" |
| 22:00 | manual restart (graceful `quit` still worked — the throw point is after console command processing) |

## Root cause 1 — the comparator violates the total-order contract

`BaseAnimalSoundManager` keeps an `ArrayList<IsoAnimal> characters` and sorts it in `update()` (the sort call sits at bytecode offset 19 of `update()V`; the comparator is the anonymous `Comparator<IsoAnimal>` field `comp`). The comparator:

1. **Recomputes the key on every call** — each `compare(a, b)` invocation calls `FMODParameterUtils.getClosestListenerDistanceSquared(...)` for both arguments instead of comparing precomputed keys.
2. **Is NaN-unsafe** — it implements the three-way result with `>` / `<` float comparisons and returns `0` otherwise. Any NaN distance therefore compares "equal" to everything, while other elements still compare ordered among themselves — violating transitivity. TimSort detects the broken invariant and throws.

`getClosestListenerDistanceSquared` returns `Float.MAX_VALUE` when there is no listener (consistent, harmless). So the only input that can break the sort is a NaN coordinate — either an animal's position or the closest listener's position — e.g. an animal mid-removal or a corrupted position from high-density collision resolution (the crash stack goes through `CollisionManager.resolveContactsInternal`, which is where the sound manager's update is driven from).

Using `Float.compare(aScore, bScore)` over **precomputed** keys would fix both problems.

## Root cause 2 — one throw becomes a permanent livelock

In `update()`, `characters.clear()` executes **after** the sort (offsets: sort at 19, clear at 116+ of `update()V`). When the sort throws:

- `clear()` is skipped, so the list is never emptied;
- entries are only appended (`addCharacter` is called from `IsoAnimal.update`), so stale references — including animals already removed from the world, exactly the ones most likely to carry NaN positions — stay in the list forever;
- every subsequent frame re-sorts the same poisoned list and throws again.

That is why the incident escalated from intermittent to every-frame: after enough churn the list permanently contains at least one NaN element. Wrapping the body in try/finally (or clearing first, or pruning invalid entries) would stop the escalation even if the comparator stayed unfixed.

## Why this freezes the whole server

The exception propagates out of `IsoWorld.update` and is caught per-frame around `IngameState.update` inside `GameServer.main`'s frame step. Everything after the throw point in that frame is skipped — including `IngameState.updateManagers()`, i.e. `ActionManager.update()` and `TransactionManager.update()`:

- `ActionManager.update()` never runs → no timed-action `Done` replies → every player's action progress bar fills and hangs (in MP, actions with a Lua `complete()` wait for the server reply);
- `TransactionManager.update()` never runs → item transactions never complete → nobody can pick anything up;
- world time stops advancing → "someone stopped the time".

Meanwhile packet processing (which happens earlier in the main loop iteration) stays alive, so players can still connect and chat — which makes the failure look like "mass desync" rather than a crash, and nothing obviously fatal appears in the console apart from the repeating stack trace.

## Suggested fixes (independent; any one helps)

1. **`BaseAnimalSoundManager` comparator** — precompute the distance key once per element per sort (snapshot), and compare with `Float.compare`. This removes both the unstable-key and the NaN-ordering violations.
2. **`update()` robustness** — put `characters.clear()` in a `finally`, or clear/prune at the start. This alone converts a bad frame into a one-frame sound-priority glitch instead of a permanent livelock.
3. **Defensive**: skip animals whose position is NaN (or whose current square is null) when building the sound candidate list.
4. Consider auditing the sibling zombie sound managers (`BaseZombieSoundManager` and subclasses) for the same pattern.

We are currently running a server-side bytecode mitigation (catching the `IllegalArgumentException` around that single sort callsite, logging NaN diagnostics, and letting `update()` finish so `clear()` runs). It stops the livelock but is a workaround; the comparator fix belongs upstream.

## Contact

- Server: <伺服器名稱>
- Contact: <聯絡方式>
