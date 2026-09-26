# TIS 回報草稿：IsoCell.processItems 混入 null 後永久卡死（未送出）

- 狀態：草稿，尚未回報。本服止血為 W40（docs/patches.md 2bc）。
- 待補：W40 上線後若記到 `off-main-thread write`，把執行緒名稱與呼叫來源補進「Root cause of the null」。

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
Unknown. All vanilla add paths (addToProcessItems(item), addToProcessItems(ArrayList)) skip nulls, and all packets are queued to the main loop, so we suspect a concurrent (off-main-thread) write racing ArrayList.add/removeAll. This happened once in about a month of logs.

Suggested fix
1. Make ProcessItems null-tolerant: skip null entries and remove them (e.g. treat a null as finished so it goes into processItemsRemove).
2. Optionally keep a HashSet alongside processItems, as is already done for processIsoObject/processIsoObjectSet, so addToProcessItems is O(1) instead of O(n).
3. Check whether any non-main thread can reach addToProcessItems / addToProcessItemsRemove on the server.

We are running a server-side mitigation implementing (1) and will report the offending thread if our logging catches one.
```
