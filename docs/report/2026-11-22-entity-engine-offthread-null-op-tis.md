# TIS 回報草稿：背景執行緒載車時寫 entity engine 的待處理佇列，佇列留下 entity 為 null 的移除後主迴圈每個 tick 都中斷（未送出）

- 狀態：草稿，尚未回報。JavaPatch 的處理：W105 把非主執行緒的 engine 登記／移除排回主執行緒、W106 讓待處理佇列單筆壞資料不再卡死主迴圈、W107 把背景載車時的拖車動物放入排回主執行緒（[docs/patches.md 2dm、2dn、2do](../patches.md#2dm)）。
- 事件：2026-11-22 本服主迴圈從某個 tick 起每個 tick 都拋同一個例外，搬物品與各種動作全部卡住，直到重啟。
- 資料來源：42.22.2 jar（sha256 `3c4a7c5f…`）的 javap、42.21／42.22.2 反編譯快照（下文只寫類別與方法名稱，不貼原始碼）、本服 DebugLog、JavaPatch 的行為測試（真 `GameEntityManager.UnregisterEntity`、真 `Engine`、真 `processPendingOperations`，以兩條執行緒照事故的先後順序呼叫）。
- 已確認：(1) javap：42.22.2 的 `GameEntityManager.UnregisterEntity(GameEntity, boolean)` 第一步就是 `engine.removeEntity(entity)`，之後才讀 `addedToEntityManager`；42.21 先看 `addedToEntityManager`。(2) javap：`BaseVehicle.load(ByteBuffer,int,boolean)` 對存檔裡的拖車動物呼叫 `addAnimalInTrailer(IsoAnimal)`，它呼叫 `animal.removeFromWorld()`，經 `GameEntity.removeFromWorld(boolean)` 走到 `UnregisterEntity`；`VehiclesDB2$QueueLoadChunk.processWorldStreamer` 在 `ServerPlayersVehicles` 執行緒呼叫 `load`。(3) javap：`EngineEntityManager.removeEntity` 在 `Engine.processing` 為 true 時從 `SingleThreadPool` 取一個操作物件放進 `pendingOperations`；`SingleThreadPool.free` 先把物件放回池、再 `reset`，兩者都沒有同步。(4) 行為測試：主執行緒 `free` 做到一半（已放回池、還沒 `reset`）時，另一條執行緒 Unregister 一個從沒進過 engine 的 entity，之後主執行緒 `reset`；佇列留下 entity 為 null 的 Remove，之後每次 `processPendingOperations` 都拋 `IllegalArgumentException: key cannot be null`，那筆一直留在佇列。(5) 本服 DebugLog：第一個堆疊與 (4) 相同（`ObjectSet.remove` ← `EngineEntityManager.removeEntityInternal` ← `processPendingOperations` ← `updateOperations` ← `Engine.updateSimulation` ← `GameEntityManager.Update` ← `IngameState.updateInternal`），之後每個 tick 重複。
- 未確認：正式服那一次是 (4) 的時序，還是兩條執行緒同時從池裡 `pop` 同一個物件（另一個可能的交錯）；兩者的結果相同，我們沒有辦法從 log 分辨。同一天 00:41 有一個 `AnimalSynchronizationManager.update` 的 `ConcurrentModificationException`，推測也來自同一條背景路徑（`addAnimalInTrailer` 呼叫 `sendExtraUpdateToClients`），沒有重現。
- 玩家可用的避開方法：沒有。發生後只能重啟。

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add
tags：multiplayer, server, freeze, threading

### Title

```text
[42.22.2] [MP] Loading a vehicle with trailer animals writes the entity engine from the ServerPlayersVehicles thread; a null pending remove then stops every server tick
```

### Body

本文照 `scripts/tis_forum_html.py` 的草稿寫法（小標下一行是 `-----`，程式與 log 縮排 5 格並前後空行）。

```text
Version: 42.22.2 (3d39148846), dedicated server.

Summary
-----
In 42.22.2, GameEntityManager.UnregisterEntity(GameEntity, boolean) starts with

     engine.removeEntity(entity)

and only then reads addedToEntityManager. In 42.21 it checked addedToEntityManager first. So every UnregisterEntity call now touches the engine, even for an entity that was never registered.

On the server, VehiclesDB2$QueueLoadChunk.processWorldStreamer() loads vehicles on the ServerPlayersVehicles thread. BaseVehicle.load() calls addAnimalInTrailer(IsoAnimal) for each saved trailer animal, which calls animal.removeFromWorld() -> GameEntity.removeFromWorld(boolean) -> GameEntityManager.UnregisterEntity() -> Engine.removeEntity(), on that thread.

EngineEntityManager.removeEntity() then writes engine state from the background thread: while the main thread is inside Engine.update() (processing == true) it obtains an EntityOperation from entityOperationPool and adds it to pendingOperations; otherwise it changes entitySet and the entity arrays directly. entityOperationPool is a SingleThreadPool, and SingleThreadPool.free() adds the object back to freeObjects before it calls reset(). Neither is synchronized.

If the background thread obtains the operation that the main thread is freeing, between the add and the reset, the main thread's reset() clears the operation's entity after the background thread has queued it. pendingOperations now holds a Remove whose entity is null. EngineEntityManager.processPendingOperations() calls removeEntityInternal(null), ObjectSet.remove(null) throws, and the exception leaves the method before pendingOperations.clear(). The null entry stays in the queue, and the same exception is thrown again on every following tick.

What players see
-----
From that tick on, every server tick stops in GameEntityManager.Update(). IngameState.updateInternal() catches the exception and skips the rest of the tick, so moving items, timed actions and most other server work stop. Only a restart clears it. The server log repeats:

     ERROR: General ... IngameState.updateInternal> Exception thrown
     java.lang.IllegalArgumentException: key cannot be null.
          at zombie.entity.util.ObjectSet.locateKey(ObjectSet.java:122)
          at zombie.entity.util.ObjectSet.remove(ObjectSet.java:201)
          at zombie.entity.EngineEntityManager.removeEntityInternal(EngineEntityManager.java:168)
          at zombie.entity.EngineEntityManager.processPendingOperations(EngineEntityManager.java:124)
          at zombie.entity.EngineEntityManager.updateOperations(EngineEntityManager.java:144)
          at zombie.entity.Engine.updateSimulation(Engine.java:127)
          at zombie.entity.GameEntityManager.Update(GameEntityManager.java:140)
          at zombie.gameStates.IngameState.updateInternal(IngameState.java:1557)

Evidence
-----
- javap of the 42.22.2 jar for each call above (UnregisterEntity, BaseVehicle.load / addAnimalInTrailer, EngineEntityManager.removeEntity, SingleThreadPool.obtain / free).
- A test with the real GameEntityManager, Engine and EngineEntityManager from the 42.22.2 jar: with Engine.processing set, add an EntityOperation back to freeObjects (the first half of free()), call GameEntityManager.UnregisterEntity(e, false) from a second thread for an entity that was never in the engine, then call reset() on that operation (the second half of free()). pendingOperations holds one entry with a null entity, and three processPendingOperations() calls in a row each throw "key cannot be null"; the entry is still there afterwards.
- Our server log: the first stack trace matches the one above, then it repeats on every tick until the restart.

Not confirmed
-----
We cannot tell from the log whether our case was the free/obtain overlap above or two threads popping the same object from freeObjects; both leave a null entity in the queue. We also saw a ConcurrentModificationException in AnimalSynchronizationManager.update() earlier the same day and think it comes from the same background path (addAnimalInTrailer() calls sendExtraUpdateToClients()), but we did not reproduce it.

Suggested fix
-----
- Keep UnregisterEntity() from touching the engine for an entity that was never added (as in 42.21), and/or run the trailer-animal part of BaseVehicle.load() (addAnimalInTrailer) on the main thread, for example in QueueLoadChunk.processMain().
- In processPendingOperations(), clear the queue even when one entry throws, so one bad entry cannot stop every later tick.
```
