# TIS 官方回報草稿 — MP timed action 永久卡讀條（吃／閱讀／製作／搬移家具）

> 用途：貼到 The Indie Stone 官方論壇 Bug Reports 板（B42），或提交給官方支援信箱。
> 下方英文本文可直接複製貼上。附件建議附上玩家 client console.txt（顯示 client 側全程健康、
> frame 持續推進、無任何 exception）＋ server console.txt 節錄（`Lua(Vanilla).new(...)` 例外）。
> 數據來源：42.20.3 反編譯與 `javap` 核實 ＋ 正式服（60+ 人、77 mods）實測。
> 撰於 2026-08-23。發文前請把文末 placeholder（伺服器名／聯絡方式）補上。
>
> **注意**：本檔遵守專案規則——只引用 class／method／field 名稱、Lua 行號與 bytecode 位置事實，
> 不貼任何反編譯的 Java 原始碼。`media/lua/` 是遊戲隨附的明文檔案，可原文引用。

---

**Title:** [42.20.3] [MP] Timed actions can hang forever at 100% (eat / read / craft / place furniture) — the server drops the packet on a Lua constructor exception, and the initial Reject reply is serialized with Request state

## Summary

On a dedicated server, a client-side timed action can hang **permanently**: the progress bar fills to 100%, the action animation keeps looping, no result is produced, the consumed input items keep their job highlight, and — because `ISTimedActionQueue` is a single-headed queue — **every subsequent queued action for that player is blocked as well**. The player's only recourse is to cancel manually or restart.

Two independent defects combine to produce it:

1. `PZNetKahluaTableImpl.loadInventoryItem` returns `null` **silently** when it cannot resolve the container or the item id. That `null` is passed straight into the Lua action constructor, whose very first statement dereferences it, so Kahlua throws a `RuntimeException` that propagates **through** `LuaCaller.protectedCall`, out of `NetTimedAction.parse`, and is swallowed by `GameServer.mainLoopDealWithNetData`. `processServer` therefore never runs, so the server sends **neither Accept nor Reject**.

2. `NetTimedActionPacket.processServer` calls `setState(Accept/Reject)` on the intermediate `act` object but serializes `this` — whose `state` is still `Request`. Consequently an **initial Request rejection** cannot make `ActionManager.isRejected` true on the client. Later rejection of an already accepted action (`perform()` returning false in `ActionManager.update`) is serialized from the correct action object and is not affected.

The client has no recovery path of its own (four separate mechanisms all fail — see below), so the action waits forever.

## Environment

- Version: 42.20.3 (dedicated Linux server, `projectzomboid.jar` verified identical to the client jar)
- 60+ concurrent players, 77 workshop mods
- Observed across three vanilla action classes on this modded server. The failure-handling defects are in vanilla code; we have **not** yet reproduced the trigger (the unresolvable item reference) on a mod-free server.

## Symptom / player-visible behaviour

- Progress bar reaches 100% and stays there; the character keeps playing the action animation ("hands keep moving"); no output item appears.
- The input items stay highlighted with the recipe's job name in the inventory panel.
- All further actions by that player are stuck behind it.
- The client console is completely clean — frame counter keeps advancing, no exceptions. Everything visible in the log is on the server side.

## Server-side evidence (single session, one of our production logs)

| Lua constructor | statement that dereferences the null | occurrences |
|---|---|---|
| `ISMoveablesAction.lua:308` | `local worldSpriteName = item:getWorldSprite();` (the `mode == "place"` branch) | 6 |
| `ISReadABook.lua:492` | `if SkillBook[item:getSkillTrained()] then` | 3 |
| `ISEatFoodAction.lua:298` | `o.container = item:getContainer() or character:getInventory();` | 3 |

All three produce `attempted index: <getter> of non-table: null`, with an identical propagation path:

```
se.krka.kahlua.vm.KahluaThread.tableget
Lua(Vanilla).new(<the .lua file above>)
se.krka.kahlua.integration.LuaCaller.protectedCall
zombie.core.NetTimedAction.parse                      <-- aborts here
zombie.network.packets.INetworkPacket.parseServer
zombie.network.PacketTypes$PacketType.onServerPacket
zombie.network.GameServer.mainLoopDealWithNetData     <-- caught and swallowed
zombie.network.GameServer.main
```

The same session also logged 21 occurrences of `NullPointerException: InventoryItem.hasSharpness() because "item" is null` from `SyncItemFieldsPacket.parse` — same underlying cause (an item reference the server cannot resolve), different packet. That one does not hang anything, but it silently drops an item-state sync.

## Root cause 1 — `loadInventoryItem` returns null silently

`InventoryItem` arguments are serialized as `ContainerID` + item id. On the receiving side, `PZNetKahluaTableImpl.loadInventoryItem` returns `null` if either the container cannot be resolved **or** `getItemWithID` misses — with no logging and no rejection. The `null` is then stored into the `actionArgs` table, becomes an element of the `arguments[]` array that `NetTimedAction.parse` builds, and is handed to the Lua `<Type>.new(...)` constructor.

None of the three Lua constructors above guards its `item` parameter; each dereferences it in its first few statements. `LuaCaller.protectedCall` does not contain the resulting `RuntimeException` despite its name.

Worth noting: **`NetTimedAction.parse` already contains the correct failure handling** — it checks `!result.isSuccess() || result.getFirst() == null`, sets its `action` field to `null` and returns, which makes `processServer` take its reject branch. The exception simply bypasses that check. Wrapping the `protectedCall` in a `try`/`catch (RuntimeException)` that yields a failed `LuaReturn` is enough to make the existing path reachable.

## Root cause 2 — `processServer` serializes the wrong object

In `NetTimedActionPacket.processServer` (both the accept and the reject branch):

- `getAction()` result is stored in a local (slot 3, named `act` in the LVT);
- `setState(Accept)` / `setState(Reject)` is invoked **on that local**;
- but the receiver pushed immediately before the `write(ByteBufferWriter)` call is **local slot 0** — i.e. `this`, the packet that was just parsed, whose `state` is still `Request`.

So both immediate replies go out as `Request`. On the client, `ActionManager.setStateFromPacket` finds the matching action and sets its state to `Request` (a no-op), which means:

- this initial Request rejection cannot trigger `ActionManager.isRejected(id)` / `forceStop()`;
- later `perform()==false` rejection and normal Done are unaffected because `ActionManager.update` serializes the actual action object, which is why most actions still complete and this defect stayed hidden.

**Contrast within the same codebase:** `ItemTransactionPacket.processServer` does it correctly — it calls `setState` on `this` and then serializes `this`. Only the `NetTimedAction` path introduced the intermediate object without writing the state back.

*(Side note, same area: `Action.write` omits `playerId` when `state == Accept`, but `ActionManager.setStateFromPacket` requires a `playerId` match to find the action. Since the default `IDShort` id is `0`, an Accept reply can never be claimed by the client — which is why `LuaTimedActionNew.update`'s "fetch duration from Accept" branch appears to be dead code, and actions whose `getDuration()` returns `-1` show an infinite-length progress bar.)*

## Why the client never recovers on its own

| Mechanism | Why it does not fire |
|---|---|
| `BaseAction.finished()` | Requires `!waitForFinished`, but `LuaTimedActionNew.start` sets `setWaitForFinished(true)` for every MP action that has a Lua `complete()` — so completion can only ever come from the server. |
| `BaseAction.hasStalled()` | Requires `lastTime`/`currentTime` to be negative. A stuck action sits at `maxTime` (positive), so this is always false. |
| `ActionManager.update()` 30-minute timeout | It only **removes** the entry; it never sets Done or Reject. And both `isDone` and `isRejected` are guarded by `!actions.isEmpty()`, so once the removal empties the queue *both* return false — the action goes from "stuck for 30 minutes" to "stuck forever". |
| `isUsingTimeout()` | `ISReadABook` and `ISResearchRecipe` return `false`, so for those the entry is never even removed. |

For comparison, `TransactionManager.isDone` / `isRejected` do **not** have the `!isEmpty()` guard, so `allMatch` over an empty stream returns true and the item-transaction path self-heals after its timeout. `ActionManager` differs by exactly that one condition.

## Suggested fixes (in the order we'd prioritise them)

1. **`NetTimedActionPacket.processServer` (Reject branch)** — serialize the object whose state was set, or set `this.state = Reject` before serializing. Reject includes `playerId`, so the client can match it and call `forceStop()`.
2. **`NetTimedAction.parse`** — contain a Lua constructor failure (`catch (RuntimeException)`) and fall into the existing `action = null` path so the reject reply is sent. Please keep `Error` propagating.
3. **Accept reply protocol** — setting the serialized state to Accept alone is insufficient: `Action.write` omits `playerId` for Accept, while `ActionManager.setStateFromPacket` requires a `playerId` match. Either include enough identity in Accept or match it by the action id/connection safely.
4. **`PZNetKahluaTableImpl.loadInventoryItem`** — log whether `ContainerID` resolution failed or `getItemWithID` missed. The current null is invisible; action argument position alone cannot distinguish those causes.
5. **Lua constructors** — a `null` guard on the `item` parameter in `ISEatFoodAction`, `ISReadABook`, `ISMoveablesAction` (and the same pattern elsewhere) would degrade this to a clean rejection.
6. **Action timeout** — when a client-side action expires, mark it rejected / force-stop it explicitly before removal. Simply removing `!actions.isEmpty()` is unsafe because empty-stream `allMatch` would make `isDone` fire first and falsely complete the action.
7. **`SyncItemFieldsPacket.parse`** — null-check the resolved item before calling `hasSharpness()`.

We are running (1) and (2) as server-side bytecode patches on our own server; both are single-callsite redirects and behave as described. Happy to provide our reproduction notes, the full log excerpts, or test a build.

## Contact

- Server: <伺服器名稱>
- Contact: <聯絡方式>
