# TIS 回報草稿：IsoCell.addToProcessItems 每次登記都線性掃描（未送出）

- 狀態：草稿，尚未回報。本服緩解為 W45（docs/patches.md 2bh），本機建置通過、尚未部署。
- 與 `2026-09-26-processitems-null-tis.md` 是同一份清單，該篇 Suggested fix 2 就是本篇修法。兩篇都未送出：
  可以分開送（一篇講正確性、一篇講效能），也可以把本篇的量測併進該篇。
- 數據來源：9/27 正式服 W40 心跳（19:05、19:45 兩個 session）與低 FPS thread dump；microbenchmark 在本機 JDK 25 跑。
  堆疊已拿掉本服兩個直通 wrapper frame（`BulkItemRegistration`、`ChunkLoadGuard`），兩者在這條路徑都原樣轉呼叫原版。

```text
[42.20.4][Dedicated Server] IsoCell.addToProcessItems does a linear ArrayList.contains on every registration; with rotten-food removal enabled it costs ~6% of the server main thread

Version: 42.20.4 (dedicated server, Linux, 40-50 players)

Summary
IsoCell.addToProcessItems(InventoryItem) and addToProcessItems(ArrayList) call processItems.contains(item) before adding. processItems is an ArrayList, so every registration scans the whole list. On our server the list holds 15,000-30,000 items and one scan takes about 30-37 µs. Chunk loading registers every item of every container (IsoObject.addToWorld -> ItemContainer.addItemsToProcessItems -> addToProcessItems), one full scan per item, so the cost lands where the server is already busiest. Two related spots:
- Food.updateAge() calls getProcessItems().contains(this) and then addToProcessItems(this), which is two full scans.
- ProcessRemoveItems() runs processItems.removeAll(processItemsRemove) twice per frame without checking whether processItemsRemove is empty. An empty removeAll still walks the whole list (about 12 µs at 30,000 items).
The sibling list processIsoObject already avoids both: it has a companion processIsoObjectSet, and ProcessIsoObject() checks processIsoObjectRemove.isEmpty() before removeAll. processItems has neither.

Why the list gets this large
With DaysForRottenFoodRemoval set (ours is 44), Food.finishupdate() returns false for every perishable food, so those items stay in processItems for as long as their chunk is loaded. Food on the ground also re-registers every 5 seconds (Food.shouldUpdateInWorld -> IsoWorldInventoryObject.update -> addToProcessItems), and every re-registration is another full scan. Any server with rotten-food removal enabled and a lot of stored food will have a similar list.

Observed
- Server fps 1-2 with 40-51 players while new map areas were being generated. About 10% of main-thread samples in low-fps thread dumps were in this stack (two frames from our own pass-through wrappers removed; both forward to the vanilla call unchanged):
    java.util.ArrayList.indexOfRange
    java.util.ArrayList.contains
    zombie.iso.IsoCell.addToProcessItems(IsoCell.java:2766)
    zombie.inventory.ItemContainer.addItemsToProcessItems(ItemContainer.java:3606)
    zombie.iso.IsoObject.addToWorld(IsoObject.java:4504)
    zombie.iso.IsoChunk.doLoadGridsquare(IsoChunk.java:3973)
    zombie.network.ServerMap$ServerCell.RecalcAll2
    zombie.network.ServerMap$ServerCell.Load2
    zombie.network.ServerMap.preupdate
- In-game measurement (once every 256 registrations we time contains() of an object that is never in the list): list size 29,892, 37.4 µs per scan on average, 7.57 million registrations in about 79 minutes. That is up to about 283 s of main-thread time, roughly 6%. It is an upper bound, because a hit stops scanning early. Another session: 15,000-27,000 items, 29 µs per scan, about 5.6%.
- Microbenchmark on JDK 25 with 30,000 resident items: 43 µs per registration with the vanilla ArrayList when the equals() call inside ArrayList.indexOfRange is megamorphic (as it is in a running server, where every ArrayList shares that code), and 6 µs when only Object has ever reached it. With an identity index: 0.3 µs.

Suggested fix
Do the same as processIsoObject/processIsoObjectSet. InventoryItem and its subclasses do not override equals()/hashCode(), so a plain HashSet (or Collections.newSetFromMap(new IdentityHashMap<>())) gives the same answers as the current contains().

  private final HashSet<InventoryItem> processItemsSet = new HashSet<>();

  public void addToProcessItems(InventoryItem item) {
      if (item != null && !GameClient.client) {
          this.processItemsRemove.remove(item);
          if (this.processItemsSet.add(item)) {
              this.processItems.add(item);
          }
      }
  }
  // same change inside the loop of addToProcessItems(ArrayList<InventoryItem>)

  private void ProcessRemoveItems(Iterator<InventoryItem> it2) {
      if (!this.processItemsRemove.isEmpty()) {
          this.processItems.removeAll(this.processItemsRemove);
          this.processItemsSet.removeAll(this.processItemsRemove);
          this.processItemsRemove.clear();
      }
      if (!this.processWorldItemsRemove.isEmpty()) {
          this.processWorldItems.removeAll(this.processWorldItemsRemove);
          this.processWorldItemsRemove.clear();
      }
  }

  // Food.updateAge(): replace getProcessItems().contains(this) with a set lookup,
  // e.g. a new IsoCell.isInProcessItems(item) that returns processItemsSet.contains(item).

Notes
- removeAll on the list stays O(n) when something is actually removed, but that only happens when items finish or chunks unload, not on every registration.
- getProcessItems() exposes the list, so code that modifies it directly would bypass the set (the same situation as processIsoObject today). In vanilla only IsoCell modifies it.

We run a server-side mitigation that does this by replacing processItems with an ArrayList subclass that keeps an identity index (O(1) contains, empty removeAll returns immediately). A vanilla fix would let us remove it.
```
