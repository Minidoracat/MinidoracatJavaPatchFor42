# TIS 回報草稿：IsoCell.processItems 混入 null 後永久卡死（未送出）

- 狀態：草稿，尚未回報。本服止血為 W40（docs/patches.md 2bc），根治為 W41（2bd）。
- 2026-09-27 已補 null 來源：W40 記到 `ServerPlayersVehicles` 執行緒直接寫入（見 Root cause）。

```text
[42.20.4][Dedicated Server] A single null in IsoCell.processItems permanently breaks ProcessItems and snowballs into multi-second main-loop freezes

Version: 42.20.4 (dedicated server, Linux, 20-30 players)

Summary
IsoCell.ProcessItems() (run every 5 s on the server) calls update()/finishupdate() on every entry of processItems without a null check. Once a null ends up in the list, every pass throws NPE at that index. Items after the null are never evaluated, so finished items are never moved to processItemsRemove, and the list only shrinks on chunk unload. It grows to roughly "all container items in loaded chunks". Because addToProcessItems() uses ArrayList.contains() (O(n)), every container registration on chunk load becomes very slow. The null never clears itself; only a restart recovers.

Observed
- NPE every ProcessItems pass (738 times in ~90 min):
  java.lang.NullPointerException: Cannot invoke "zombie.inventory.InventoryItem.update()" because "i" is null
    at zombie.iso.IsoCell.ProcessItems(IsoCell.java:2625)
    at zombie.iso.IsoCell.updateInternal(IsoCell.java:4903)
    ...
    at zombie.network.GameServer.main(GameServer.java:1024)
- Server fps dropped from ~9.8 to 2-3 with 20-29 players; single frames took up to 16 s.
- 65 of 78 main-thread stack samples taken during the freezes:
    java.util.ArrayList.contains
    zombie.iso.IsoCell.addToProcessItems(IsoCell.java:2766)
    zombie.inventory.ItemContainer.addItemsToProcessItems(ItemContainer.java:3606)
    zombie.iso.IsoObject.addToWorld(IsoObject.java:4504)
    zombie.iso.IsoChunk.doLoadGridsquare(IsoChunk.java:3973)
    zombie.network.ServerMap$ServerCell.RecalcAll2 / Load2
- GC was healthy (no allocation stalls). A restart cleared it immediately.

Root cause of the null
An off-main-thread write. We logged every call to addToProcessItems / addToProcessItemsRemove that did not come from GameServer.mainThread. In one 4-hour evening session there were 8,384 such writes (about 38 per minute); every sampled one came from the ServerPlayersVehicles thread:
    zombie.iso.IsoCell.addToProcessItems
    zombie.inventory.ItemContainer.AddItem
    zombie.inventory.ItemContainer.addItem
    zombie.vehicles.BaseVehicle.setCurrentKey
    zombie.vehicles.BaseVehicle.load
    zombie.iso.IsoObject.load
    zombie.vehicles.VehiclesDB2$QueueLoadChunk.vehicleLoaded
    zombie.vehicles.VehiclesDB2$SQLStore.loadChunk
When VehiclesDB2 loads a vehicle on that thread, the ignition key is put into the vehicle container, and ItemContainer.AddItem unconditionally calls IsoWorld.instance.currentCell.addToProcessItems(item). That ArrayList is modified by the main thread at the same time (add, and removeAll in ProcessRemoveItems), so an add that races a resize or removal can leave a null (or a duplicate) in the list. The freeze happened once in about a month of logs, but the unsafe writes happen every minute.

Suggested fix
1. Make ProcessItems null-tolerant: skip null entries and remove them (e.g. treat a null as finished so it goes into processItemsRemove).
2. Optionally keep a HashSet alongside processItems, as is already done for processIsoObject/processIsoObjectSet, so addToProcessItems is O(1) instead of O(n).
3. Do not touch the shared processItems list from ServerPlayersVehicles: queue items added off the main thread and register them on the main thread (this is what our second mitigation does: the two addToProcessItems calls in ItemContainer.AddItem check the current thread and defer to a queue drained at the start of ProcessItems).

We are running server-side mitigations implementing (1) and (3).
```
