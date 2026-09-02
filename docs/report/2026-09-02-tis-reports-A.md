# TIS 官方回報草稿 — A 組：伺服器假死／資料損失（六份）

> 目標板：The Indie Stone 官方論壇 Bug Reports（B42）。每份 `### Body` 的 fenced block
> 可直接複製貼上。所有根因均對 **42.20.4** 反編譯快照
> （`pz_jar_sha256 = 80e405a4bfc42f6072e75b3735f458a6514143da011d3226007ded305a442f44`，
> 與正式服現場 jar 相同）逐行核對，行號即該快照行號。
> 撰於 2026-09-02。

---

## R1. 容器環 StackOverflowError 全服假死

### 中文摘要

報「容器互相巢狀成環 → `ItemContainer.getCharacter()` 無限自遞迴 → 主迴圈 SOE → 全服假死
13 分鐘」。優先級**高**（一個封包序列即可打死 dedicated server，且無自癒）。建議附件：
21:31:10 那份 1024 層 SOE 堆疊全文、以及守衛切斷時印出的環閉合點 log（含 containerId /
itemId / fullType）。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）

### Title

`[42.20.4] [MP] Container cycles cause unbounded recursion in ItemContainer.getCharacter() — StackOverflowError kills the dedicated server main loop (13-minute silent freeze)`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific -- see analysis]

Summary
-------
Nothing in ItemContainer prevents a container from ending up inside its own
descendant chain. Once such a cycle exists, ItemContainer.getCharacter() --
which walks "container -> the item holding it -> that item's container" with no
cycle detection and no depth bound -- recurses until the stack is exhausted. On
2026-08-13 at 21:31:10 this killed our dedicated server's main loop with a
StackOverflowError whose 1024 visible frames were all ItemContainer.getCharacter.
The process stayed alive but the world stopped; the frame counter froze at
f:54247 for 13 minutes until a watchdog force-restarted it.

Trigger conditions
------------------
Not deterministic from a fixed sequence of player actions, because the cycle
itself must first be created by a packet-driven item move. The preconditions are
in the code, not in the save:

1. Any code path that puts container item A inside container B while B is
   (transitively) inside A. AddItem is the only gate and it does not check for
   this (see below).
2. Afterwards, ANY call to getCharacter() or isInCharacterInventory() on a
   container on that cycle is fatal. Both are called constantly by inventory
   UI sync, transaction validation and the container broadcast helpers, so the
   crash follows within seconds.

We were unable to find the cycle in the save files, and that is expected: the
"containingItem" backlink is assigned once in the InventoryContainer constructor
and is not serialised, and InventoryContainer.save() is a nested recursive
writer -- a cycle present at save time would blow the stack while saving. Our
"World saved" 60 seconds before the crash completed normally. The cycle is
therefore purely a runtime artifact of the MP item-move path.

Observed
--------
Player-visible: the world stops. Players remain "connected" but nothing moves,
no action completes, and the server does not accept the graceful `quit` typed at
21:40. Only SIGKILL + watchdog restart at 21:44 recovered it. World data loss was
small (last successful save 21:30:10) but every player action between 21:31 and
21:44 was silently discarded.

Log fingerprint (truncated -- all 1024 frames are the same frame repeating):

  java.lang.StackOverflowError
    at zombie.inventory.ItemContainer.getCharacter(ItemContainer.java)
    at zombie.inventory.ItemContainer.getCharacter(ItemContainer.java)
    ... x1024

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
1. Unbounded self-recursion, no cycle detection.
   zombie/inventory/ItemContainer.java:3250-3256

     public IsoGameCharacter getCharacter() {
        if (this.getParent() instanceof IsoGameCharacter) { ... }
        else { return this.containingItem != null
                 && this.containingItem.getContainer() != null
               ? this.containingItem.getContainer().getCharacter()   // :3254
               : null; }
     }

   The same shape exists in isInCharacterInventory(IsoGameCharacter) at
   ItemContainer.java:342-358 (self-recursion at :352). Neither keeps a visited
   set nor a depth counter. On a cycle both recurse forever.

2. Nothing stops the cycle from being created.
   ItemContainer.AddItem(InventoryItem) -- ItemContainer.java:458-495 -- rejects
   only a duplicate item id (:461, containsID) and then unconditionally performs
   `item.container = this; this.items.add(item);` (:478-479). There is no check
   that `this` is not already inside `item`.

3. The engine does have an ancestor check, but it is private and only two levels
   deep. zombie/core/TransactionManager.java:91-118,
   chainContainsContainingItem(ItemContainer, int) walks upward inside
   `for (int i = 0; i < 2; i++)` (:97). Two levels is enough for the transaction
   dedup it was written for, but it cannot see a longer cycle, and being private
   it is not reachable from AddItem anyway.

4. Secondary (silent, not fatal): the five GameServer container broadcast
   helpers (sendAddItemToContainer, GameServer.java:2389-2405, and the same
   shape in sendAddItemsToContainer :2407, sendReplaceItemInContainer :2425,
   sendRemoveItemFromContainer :2449, sendRemoveItemsFromContainer :2467) are a
   three-branch if/else-if with no final else: `getCharacter() instanceof
   IsoPlayer` (:2390), `getParent() != null` (:2392), then
   `container.inventoryContainer != null && ...getWorldItem() != null` (:2396).
   A nested container that satisfies none of the three -- which includes any
   container whose owner walk cannot resolve -- gets no packet and no log line,
   so clients never learn about add/remove in it.

Field data
----------
- 2026-08-13 21:31:10, ~60 players online. Main loop dead, frame frozen at
  f:54247 for 13 minutes. Graceful `quit` at 21:40 not accepted; forced restart
  21:44.
- Last successful world save 21:30:10 (i.e. the cycle did not exist in, and was
  not written to, the save).
- Since installing a depth guard we have not lost the main loop to this again.

Suggested fix
-------------
Minimal and low-risk, in order of value:

1. Bound the two walks. Give getCharacter() and isInCharacterInventory() a
   visited-set or a depth cap (64 is far beyond any legitimate nesting) and
   return null / false at the cap, which is the same value both methods already
   produce for "no owner found". This alone converts a server kill into a
   harmless miss.
2. Reject the cycle at the door. In AddItem(InventoryItem), before
   `item.container = this`, walk `this` upward and refuse (return the existing
   item / log) if `item` is found on the chain. TransactionManager's
   chainContainsContainingItem is already the right shape -- promoting it to a
   shared helper and removing the `i < 2` bound would cover both uses.
3. Give the GameServer container broadcasts a final else that logs (or falls back
   to the owning chunk), so a container that matches none of the three branches
   cannot silently stop replicating.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode
patch) that truncates both walks at depth 64 and dumps the cycle's closing point:
the main loop has not been lost to this StackOverflowError since, and the
truncation log confirms real cycles (a control run using the vanilla walk over
the same captured chain reproduces the StackOverflowError). Logs / bytecode diffs
available on request.
```

---

## R2. 「Entity is already registered」主迴圈永久活鎖＋stale entitySet 根因

### 中文摘要

報兩層問題：(1) `GameServer.main` 的 catch 在迴圈頂端而 `ServerCell.Load2` 的出隊在
`RecalcAll2()` **之後**，導致同一個地圖格每 0.1 秒重撞，凍結 114 分鐘；(2) 我方捕手 6 天
21 次全部是 `addedToEngine=false` 但 entitySet 仍持有該實例 —— 指向 vanilla 有 stale
entitySet 殘留（reset/pool 路徑繞過 `removeEntityInternal`）。優先級**最高**（唯一一個會
造成 114 分鐘全服靜止且看門狗救不了的形態）。建議附件：兩次事故的完整 stack、21 筆捕手
明細（座標／sprite／class／addedToEngine／identity／jobType）。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）

### Title

`[42.20.4] [MP] "Entity is already registered" from IsoChunk.doLoadGridsquare permanently livelocks the dedicated server main loop (114-minute freeze), and the underlying stale EngineEntityManager.entitySet entries are still occurring daily`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific -- see analysis]

Summary
-------
When IsoChunk.doLoadGridsquare re-adds an IsoObject that EngineEntityManager
still has in its entitySet, addEntityInternal throws IllegalArgumentException.
GameServer.main catches it at the TOP of the main loop, so the rest of that
iteration (world update, packet processing, frame advance) is skipped -- and
because ServerMap$ServerCell.Load2 dequeues the cell only AFTER the call that
threw, the same cell is retried every tick, forever. On 2026-08-14 at 01:34:56
our server's frame counter stopped at f:46186 and never advanced again; the
freeze ended 114 minutes later only because an unrelated scheduled restart
happened at 03:28. Nobody restarted it to fix it -- nothing reported a problem.

This is a livelock, not a crash. The process is healthy, the network/Steam
threads run, players can still connect -- into a completely frozen world. No
"restart if the process dies" supervision helps.

The second half of this report is about why the entity is in entitySet at all.
We instrumented the throw site and have been collecting per-occurrence
diagnostics; the data says vanilla is leaving stale entities in entitySet, and
we believe we have located the paths that do it.

Trigger conditions
------------------
Not deterministic. It requires an IsoObject that is already present in
EngineEntityManager.entitySet to be loaded into a grid square again. On our
server this happens ~3-4 times a day at ~60-80 concurrent players, on ordinary
tile objects (ground blends, fences, storage furniture). Whether it escalates
into the 114-minute freeze depends only on which code path re-adds it -- see the
Load2 ordering below; the object-level throw itself is the same every time.

Observed
--------
Player-visible during the freeze: total world stasis. Players connect but
nothing moves, no action resolves, no packet has any effect. We logged 170
disconnects during the 114-minute window. Chat/console appeared alive.

The exception is printed and then suppressed by PZ's duplicate-exception limiter
after ~25 identical prints, so after roughly two and a half seconds the log goes
completely silent while the server stays frozen for another two hours.

  java.lang.IllegalArgumentException: Entity is already registered blends_natural_01_53:zombie.iso.IsoObject@...
    at zombie.entity.EngineEntityManager.addEntityInternal(EngineEntityManager.java)   <- the throw
    at zombie.entity.EngineEntityManager.addEntity(EngineEntityManager.java)           <- the direct branch, not the addedToEngine-guarded one
    at zombie.entity.Engine.addEntity(Engine.java)
    at zombie.entity.GameEntityManager.RegisterEntity(GameEntityManager.java)
    at zombie.entity.GameEntity.addToWorld(GameEntity.java)
    at zombie.iso.IsoObject.addToWorld(IsoObject.java)
    at zombie.iso.IsoChunk.doLoadGridsquare(IsoChunk.java)
    at zombie.network.ServerMap$ServerCell.RecalcAll2(ServerMap.java)
    at zombie.network.ServerMap$ServerCell.Load2(ServerMap.java)
    at zombie.network.ServerMap.preupdate(ServerMap.java)
    at zombie.network.GameServer.main(GameServer.java)

The identical stack occurred on 2026-08-07 18:05 (victim sprite fencing_01_57)
and 2026-08-14 01:34 (victim blends_natural_01_53).

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
LAYER 1 -- why one bad object freezes the server forever.

zombie/network/ServerMap.java:792-812, ServerCell.Load2():

     for (int i = 0; i < loaded2.size(); i++) {
        if (loaded2.get(i) == this) {
           long start = System.nanoTime();
           this.RecalcAll2();        // :798  <-- can throw
           loaded2.remove(i);        // :799  <-- dequeue, never reached
           ...

The dequeue is downstream of the fallible work. Any RuntimeException out of
RecalcAll2 leaves the cell in loaded2, and GameServer.main's catch is above the
world/packet/frame section, so the next tick repeats the exact same call. Note
that addEntityInternal throws on its FIRST statement
(EngineEntityManager.java:137-138) before any field is written, so the state bits
are bit-identical next tick: the retry can never succeed. This ordering, not the
exception type, is the entire cause of the freeze -- any RuntimeException at the
same place produces the same 114 minutes.

LAYER 2 -- why the entity is still registered.

zombie/entity/EngineEntityManager.java:136-162 is the whole invariant:

     void addEntityInternal(GameEntity entity) {
        if (this.entitySet.contains(entity)) {
           throw new IllegalArgumentException("Entity is already registered " + entity);   // :137-138
        } else { ... this.entitySet.add(entity); ... entity.addedToEngine = true; }        // :142,:144
     }

     void removeEntityInternal(GameEntity entity) {
        boolean removed = this.entitySet.remove(entity);   // :151
        if (removed) { ... entity.addedToEngine = false; ... }   // :159
     }

entitySet membership is created only at :142 and destroyed only at :151, and
addedToEngine is supposed to track it exactly. Our instrumentation reads
GameEntity.isAddedToEngine() at the throw site, and over 2026-08-27..09-02 we
recorded 21 occurrences, all on the main thread, EVERY ONE of them with
addedToEngine == false -- while the throw itself proves entitySet.contains ==
true. That combination is unreachable through addEntityInternal /
removeEntityInternal alone. It means something cleared the flag without removing
the set entry. Reading 42.20.4, three vanilla paths can do exactly that:

(a) zombie/entity/GameEntity.java:431-451, reset():
      if (this.addedToEngine || this.addedToEntityManager) { ...
         GameEntityManager.UnregisterEntity(this);        // :441
      }
      ...
      this.addedToEngine = false;                        // :445
    Line :445 is unconditional -- it runs whether or not :441 actually removed
    anything.

(b) zombie/entity/GameEntityManager.java:234-316, UnregisterEntity() has two
    early exits that make :441 a no-op while leaving entitySet untouched:
      - :235  `if (gameEntity != null && gameEntity.addedToEntityManager)` --
              entire body skipped when the flag is already false;
      - :255  `if (stored != null)` -- if idToEntityMap.remove(netID) returns
              null, engine.removeEntity(gameEntity) at :259 is never called.
    Note also the ordering in RegisterEntity: engine.addEntity(gameEntity) at
    GameEntityManager.java:211 runs BEFORE gameEntity.addedToEntityManager = true
    at :217. So the first time addEntityInternal throws, the object is left with
    addedToEntityManager == false -- and by (b):235 every later UnregisterEntity
    on that instance is a no-op. The bad state is self-sealing.

(c) zombie/entity/GameEntityManager.java:312-313 clears addedToEntityManager and
    addedToEngine directly with no engine removal (client-side branch).

And the object pooling makes the stale entry permanent, because it preserves
object identity. IsoObject does not override equals/hashCode, so entitySet
(an ObjectSet) is identity-based:
  - zombie/iso/IsoGridSquare.java:2543-2553, DeleteTileObject() calls obj.reset()
    and pushes the instance into CellLoader.isoObjectCache (:2551-2552) for
    anything whose getObjectName() is "IsoObject";
  - zombie/iso/WorldReuserThread.java:50-51 pushes recycled objects into the same
    cache;
  - zombie/iso/IsoObject.java:366-373, getNew() pops that cache and calls
    obj.reset() -- the SAME instance is then placed on a new square and
    re-added to the world.
If that instance still had an entitySet entry, the recycled object throws on
every load attempt for the rest of the process lifetime. This matches our victim
list exactly: all 21 hits are plain tile objects that go through this pool --
blends_natural_*, blends_street_*, fencing_01_57 / fencing_01_58,
furniture_storage_*.

The server-side consequence beyond the freeze: the object never enters the
world. A fence that is not there has no collision; a storage unit that is not
there has no container. Both desync against clients that do render them.

Field data
----------
- 2026-08-14 01:34:56 -> 03:28. Frame frozen at f:46186 for 114 minutes.
  170 player disconnects during the window. Ended by an unrelated scheduled
  restart.
- 2026-08-07 18:05: identical stack, victim fencing_01_57.
- 2026-08-27..2026-09-02 (6 days, instrumented): 21 occurrences, 100% on the main
  thread, 100% with addedToEngine == false while entitySet.contains == true.
  Victims: blends_natural_*, blends_street_*, fencing_01_57/58,
  furniture_storage_*.

Suggested fix
-------------
Two independent changes; the first stops the outage class, the second fixes the
defect.

1. Dequeue before the fallible work in ServerMap$ServerCell.Load2 (ServerMap.java
   :798-799): remove the cell from loaded2 first, then call RecalcAll2(), or wrap
   RecalcAll2() so the cell is removed in a finally. As it stands, ANY exception
   from cell loading is an unbounded main-loop livelock, which is strictly worse
   than a crash because no supervision can detect it.
2. Make entitySet membership and addedToEngine impossible to desynchronise:
   - in GameEntity.reset(), only clear addedToEngine after a removal that is
     known to have happened (or call engine removal unconditionally rather than
     via the addedToEntityManager-gated UnregisterEntity);
   - in GameEntityManager.RegisterEntity, set addedToEntityManager = true before
     engine.addEntity (or in a finally), so a failed add cannot leave an entity
     that UnregisterEntity refuses to touch;
   - before pushing an IsoObject into CellLoader.isoObjectCache
     (IsoGridSquare.DeleteTileObject, WorldReuserThread), assert/ensure it is no
     longer in entitySet -- recycling an identity that the engine still holds is
     what makes this permanent.
   As a cheap belt-and-braces measure, addEntityInternal could log-and-return
   instead of throwing when entitySet already contains the entity, which by
   itself would have prevented both of our multi-hour freezes.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode
patch) that catches the RuntimeException at the two doLoadGridsquare add sites
and records the diagnostics quoted above; since installing it we have had no
further main-loop freeze from this path, and the addedToEngine == false readings
are what pointed us at the reset/pool paths. Logs / bytecode diffs available on
request.
```

---

## R3. 共用 static `tempVector2_2` 跨執行緒競態 → chunk 載入失敗 → Blam 抹除玩家建造

### 中文摘要

報「`IsoGameCharacter.setForwardDirectionFromIsoDirection()` 用 JVM 全域共用的
`private static final Vector2 tempVector2_2` 當暫存，chunk loader 執行緒與主迴圈同時進入
即讀到 (0,0) → `normalize()` 長度 0 → 例外 → 整塊 chunk 被 Blam 抹除」。優先級**最高**
（真實玩家建造永久消失，且存檔本身是好的）。建議附件：Player-A 雞舍案的 `blam/` 前後檔
（46,142 / 8,549 bytes）與該次 chunk 載入 stack。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）

### Title

`[42.20.4] [MP] Shared static Vector2 in IsoGameCharacter.setForwardDirectionFromIsoDirection races between the chunk-loader thread and the main loop — "Forward Direction cannot be zero length vector" aborts a chunk load and Blam() wipes player-built content`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific -- the save file was verified intact, see analysis]

Summary
-------
IsoGameCharacter.setForwardDirectionFromIsoDirection() uses a JVM-wide shared
`private static final Vector2 tempVector2_2` as scratch space, with no
synchronisation. IsoMovingObject.getVectorFromDirection zeroes that vector before
filling it. When the dedicated server's chunk-loader thread deserialises an animal
at the same moment the main loop turns any character, one thread reads the vector
during the other's zeroing window, gets (0,0), and normalize() throws. On the
chunk-loader thread that exception propagates out of IsoChunk.LoadOrCreate, and
vanilla responds with Blam() + LoadBrandNew() -- the chunk is permanently
regenerated from scratch and everything the players built in it is gone.

On 2026-08-13 this destroyed a player's chicken hutch: chunk 1160,968 went from
46,142 bytes to 8,549 bytes, taking the hutch, 32 poultry with their full
genomes, and adjacent items with it. The save file on disk was NOT corrupt.

Reproduction steps
------------------
Not deterministic -- the window is a few instructions wide. Statistically
reliable conditions on a busy dedicated server:

1. Have animals stored inside a hutch (IsoHutch) in a chunk that unloads/reloads.
2. Keep the main loop busy turning characters (zombies/animals/players spawning
   or changing facing) -- i.e. normal play at 50+ concurrent players.
3. Force chunk streaming across that area, e.g. restart the server with players
   online so many chunks load at once.

We saw 67 instances of the underlying IllegalStateException in the retained logs;
one of them landed on the chunk-loader thread and that one cost the chunk.

Observed
--------
Player-visible: a player-built structure and its contents vanish between
sessions; the tile reverts to freshly generated terrain (in our case, plain
grass). No warning to the player, nothing in the server console that names the
player or the build.

Log fingerprint (the failing chunk load, 4 seconds after a restart):

  Error loading chunk 1160,968
  java.lang.RuntimeException: java.lang.IllegalStateException: Forward Direction cannot be zero length vector.
    at zombie.characters.IsoGameCharacter.setForwardDirection(IsoGameCharacter.java)   <- the throw
    at zombie.characters.IsoGameCharacter.setForwardDirectionFromIsoDirection(IsoGameCharacter.java)
    at zombie.characters.IsoGameCharacter.setForwardIsoDirection(IsoGameCharacter.java)
    at zombie.characters.animals.IsoAnimal.load(IsoAnimal.java)
    at zombie.iso.objects.IsoHutch.load(IsoHutch.java)
    at zombie.iso.IsoGridSquare.load(IsoGridSquare.java)
    at zombie.iso.IsoChunk.LoadOrCreate(IsoChunk.java)
    at zombie.iso.IsoChunk.LoadChunk(IsoChunk.java)
    at zombie.network.ServerChunkLoader$LoaderThread.run(ServerChunkLoader.java)

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
1. The scratch vector is a shared static.
   zombie/characters/IsoGameCharacter.java:440
     private static final Vector2 tempVector2_2 = new Vector2();

   zombie/characters/IsoGameCharacter.java:5072-5074
     public void setForwardDirectionFromIsoDirection() {
        this.getVectorFromDirection(tempVector2_2);   // :5073  write shared static
        this.setForwardDirection(tempVector2_2);      // :5074  read it back
     }

2. The writer zeroes it first.
   zombie/iso/IsoMovingObject.java:374-381
     public static Vector2 getVectorFromDirection(Vector2 moveForwardVec, IsoDirections dir) {
        ...
        moveForwardVec.x = 0.0F;   // :380
        moveForwardVec.y = 0.0F;   // :381
        switch (dir) { ... }       // then fills the real value
     }
   Between :381 and the switch arm writing the real component, the shared
   instance holds (0,0) and is visible to every other thread.

3. The reader rejects a zero vector by throwing.
   zombie/characters/IsoGameCharacter.java:2966-2974
     float forwardDirectionLength = this.forwardDirection.normalize();   // :2970
     ...
     if (PZMath.equal(forwardDirectionLength, 0.0F)) {
        throw new IllegalStateException("Forward Direction cannot be zero length vector.");  // :2973
     }

4. Two threads genuinely reach :5073/:5074 concurrently. The deserialisation
   path is zombie/characters/animals/IsoAnimal.java:1399
     this.setForwardIsoDirection(IsoDirections.fromIndex(input.getInt()));
   which routes through IsoGameCharacter.setForwardIsoDirection
   (IsoGameCharacter.java:5067-5070) to setForwardDirectionFromIsoDirection, and
   runs on ServerChunkLoader$LoaderThread. The main loop calls the same method
   for every character that changes facing.

5. The save file is fine. IsoDirections.fromIndex(int) is VALUES[index & 7], and
   all eight directions have non-zero vectors -- no stored direction value can
   produce a zero-length vector. The failure is purely a data race, which is why
   the blam/ backup restores cleanly.

6. The blast radius comes from the recovery policy.
   zombie/iso/IsoChunk.java:2302-2306
     this.loaded = this.LoadOrCreate(wx, wy, fromServer);
     ...
        this.Blam(wx, wy);
        this.loaded = this.LoadBrandNew(wx, wy);
   LoadOrCreate catches Exception (IsoChunk.java:2312-2364) and regenerates the
   chunk. A transient, retryable race is treated as permanent file corruption.

Field data
----------
- 2026-08-13 19:55:03, chunk 1160,968 (squares 9280-9287 / 7744-7751), 4 seconds
  after a restart: 46,142 bytes -> 8,549 bytes. Lost: one IsoHutch, 32 poultry
  with full genomes, a Base.Bucket, surrounding build.
- Same IllegalStateException, 67 occurrences in the retained logs: 1 on
  ServerChunkLoader$LoaderThread (the data loss above) and 66 in
  VirtualZombieManager.createRealZombieAlways on the main thread, where the outer
  IngameState.UpdateStuff try only costs a tick. (Those 66 come through the
  separate shared IsoDirections.TEMP instance, which has the same shape.)

Suggested fix
-------------
1. Make the scratch vector non-shared in setForwardDirectionFromIsoDirection: a
   method-local `new Vector2()` (this method is not on any per-tick unconditional
   path -- it runs on turn, spawn and load) or a ThreadLocal. Only 2 of the 12
   reads of tempVector2_2 in IsoGameCharacter are in this method, and neither
   depends on a value left behind by another method, so this is a contained
   change.
2. IsoDirections.TEMP is the same pattern (ToVector() hands out a shared static
   that callers then mutate and normalize) and produces the other 66 hits. Worth
   the same treatment.
3. Independent of the race: IsoChunk.LoadOrCreate should not answer a
   RuntimeException from deserialisation with Blam() + LoadBrandNew(). Failing
   the load loudly (and leaving the file alone) turns "player base deleted" into
   "chunk temporarily unavailable". Right now any transient exception anywhere in
   the load path is a permanent data-loss event.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode
patch) that swaps the two shared-static reads in that one method for a per-thread
instance -- a whole-class javap comparison shows zero instructions removed and
exactly two added. The chunk-load failures on that path stopped, and we restored
the affected chunk from its blam/ backup intact, which confirms the on-disk data
was never corrupt. Logs / bytecode diffs available on request.
```

---

## R4. 動物聲音 comparator 違反契約 → TimSort IAE → `clear()` 被跳過 → 全服活鎖

### 中文摘要

報「`BaseAnimalSoundManager` 的 comparator 對 NaN 距離回 0 違反遞移性 → TimSort 拋 IAE →
`characters.clear()` 在 sort 之後被跳過 → 清單永不清空 → 之後每幀重炸 → `updateManagers()`
永久跳過 → 全服卡讀條、時間停止」。優先級**高**（frame 照推進，看門狗不救，只能重啟）。
建議附件：19:25–21:47 的 IAE 計數曲線與 `IngameState.updateInternal` stack。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）

### Title

`[42.20.4] [MP] BaseAnimalSoundManager comparator violates its contract on NaN distances — TimSort IllegalArgumentException skips characters.clear() and self-sustains into a server-wide livelock (stuck action bars, "time stopped")`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific -- see analysis]

Summary
-------
BaseAnimalSoundManager sorts its per-frame animal list with a comparator that
recomputes a listener distance on every comparison and hand-rolls the tri-state
with `>` / `<`. If any distance is NaN, both comparisons are false and the
comparator returns 0 ("equal") -- NaN is "equal" to everything while everything
else is ordered, so transitivity is broken and TimSort throws
IllegalArgumentException: "Comparison method violates its general contract!".

The damage is not the exception, it is where it lands: update() sorts BEFORE it
clears the list. The throw skips characters.clear(), so the list is never
emptied, keeps growing with stale and despawned animals, and every following
frame re-throws with a larger, still-poisoned list. The exception propagates all
the way to IngameState.updateInternal, aborting the rest of the server tick --
including updateManagers() (ActionManager / TransactionManager). Players can
still chat and connect, but no timed action ever completes, nothing can be picked
up, and the world reads as "time stopped". The frame counter still advances
(frameNo++ happens before the throw point), so a frame-stall watchdog never
fires. The only recovery is a restart.

Trigger conditions
------------------
Not deterministic; requires a NaN coordinate to reach the distance function.
Conditions under which we reproduce it statistically:

1. High animal density -- we run penned farms with 50-80+ animals in a single
   area (two of ours: 11134,6875 and 6320,5518), which puts large lists into
   BaseAnimalSoundManager every frame.
2. Concurrent animal despawn/removal churn. In our case a mod issued batched
   removals, but `remove()` is a legal public API and vanilla's own despawn takes
   the same path -- removal frequency changes the rate, not the defect.
3. Once the first IAE lands, no further trigger is needed: the un-cleared list
   guarantees recurrence, and the failure escalates on its own from occasional to
   every frame.

Observed
--------
Player-visible: server-wide stuck progress bars, items cannot be picked up,
timed actions never finish, in-game time appears frozen. Chat and logins keep
working, which makes it look like a client problem to the players.

Log fingerprint:

  java.lang.IllegalArgumentException: Comparison method violates its general contract!
    at java.base/java.util.TimSort.mergeHi(TimSort.java)
    ...
    at java.base/java.util.ArrayList.sort(ArrayList.java)
    at zombie.characters.BaseAnimalSoundManager.update(BaseAnimalSoundManager.java)
    at zombie.iso.CollisionManager.resolveContactsInternal(CollisionManager.java)
    at zombie.iso.IsoWorld.updateWorld(IsoWorld.java)
    at zombie.gameStates.IngameState.updateInternal(IngameState.java)

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
1. The comparator. zombie/characters/BaseAnimalSoundManager.java:13-27

     private final Comparator<IsoAnimal> comp = new Comparator<IsoAnimal>() {
        public int compare(IsoAnimal a, IsoAnimal b) {
           float aScore = FMODParameterUtils.getClosestListenerDistanceSquared(a.getX(), a.getY(), a.getZ());  // :19
           float bScore = FMODParameterUtils.getClosestListenerDistanceSquared(b.getX(), b.getY(), b.getZ());  // :20
           if (aScore > bScore) { return 1; }        // :21-22
           else { return aScore < bScore ? -1 : 0; } // :24
        }
     };

   Two independent problems here:
   - NaN: both `>` and `<` are false, so :24 returns 0. A NaN element compares
     "equal" to every other element while those elements compare unequal to each
     other -- transitivity violated, which is exactly what TimSort detects.
   - The key is recomputed on every comparison rather than once per element, so
     the ordering is also not stable against a listener moving mid-sort.
   (The no-listener case returns Float.MAX_VALUE consistently, so NaN input
   coordinates are the only way to break the contract here.)

2. The self-sustaining part. zombie/characters/BaseAnimalSoundManager.java:40-61

     public void update() {
        if (!this.characters.isEmpty()) {
           this.characters.sort(this.comp);   // :42  <-- throws
           ...
           this.postUpdate();                 // :58
           this.characters.clear();           // :59  <-- never reached
        }
     }

   clear() at :59 is after the sort at :42 and is not in a finally block. Once
   :42 throws, the list is permanent. addCharacter (:34-38) keeps appending, and
   the retained IsoAnimal references include animals that have since despawned.

3. Nothing above catches it usefully: the exception unwinds through
   CollisionManager.resolveContactsInternal and IsoWorld.updateWorld to
   IngameState.updateInternal, so the remainder of the server tick -- notably
   updateManagers() -- is skipped every frame.

Field data
----------
- 2026-08-23 19:25:45 first occurrence; 1411 occurrences between 19:25 and 21:47,
  after which it became one per frame. Server-wide stuck action bars until a
  manual restart. Frame counter kept advancing throughout.
- 2026-08-27..2026-09-02 (6 days, with our catcher installed): still 18
  occurrences, i.e. the defect fires regularly on a normally populated server.
- In all instrumented occurrences the animal coordinates we scanned contained no
  NaN (`nanAnimals=0`), which points at the listener side of
  FMODParameterUtils.getClosestListenerDistanceSquared rather than at the animal
  positions.

Suggested fix
-------------
1. Make the comparator total. Compute the score once per element (a
   decorate-sort-undecorate pass, or cache the score on IsoAnimal for the frame)
   and compare with Float.compare(a, b), which defines a total order including
   NaN. This is a couple of lines and removes the contract violation regardless
   of where the NaN comes from.
2. Move `this.characters.clear()` into a finally around the body of update().
   This is the difference between "one frame of sound priority is wrong" and "the
   server is unusable until restarted", and it is worth doing even after (1).
3. Separately worth checking why getClosestListenerDistanceSquared can return
   NaN at all on a dedicated server -- a listener position that is NaN is likely
   a symptom of something else.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode
patch) that wraps the sort call at :42, swallows only IllegalArgumentException,
and returns unsorted so update() runs to completion and reaches clear(). Since
installing it the livelock has not recurred -- the exception still fires (18
times in the last 6 days) but each occurrence now costs one frame of sound
priority instead of the server. Logs / bytecode diffs available on request.
```

---

## R5. `IsoGridSquare.removeGlassAttachments` 無條件 `n--` 造成無限迴圈

### 中文摘要

報「一個玩家的砸窗封包即可讓整個 server tick 進入無限迴圈」。`removeGlassAttachments`
假設 `RemoveTileObject` 一定會讓清單縮短並無條件 `n--`，但 42.20 的安全移除路徑
（`IsoObjectUtils.safelyRemoveTileObjectFromSquare`）有兩條「什麼都沒移除」的返回路徑。
優先級**高**（單一封包即可觸發，需 SIGKILL 才能恢復）。修法只有一行。建議附件：兩份
間隔 4 秒的 thread dump。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）

### Title

`[42.20.4] [MP] IsoGridSquare.removeGlassAttachments decrements its loop index unconditionally — a single smash-window packet can spin the server tick forever (requires SIGKILL)`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific -- see analysis]

Summary
-------
IsoGridSquare.removeGlassAttachments walks `this.objects` by index and, whenever
it removes an attachment, does `RemoveTileObject(o); n--;` -- assuming the removal
always shortens the list. In 42.20 RemoveTileObject routes through
IsoObjectUtils.safelyRemoveTileObjectFromSquare, which has code paths that remove
nothing. When one of those is taken, the index steps back onto the same object,
the same removal fails again, and the loop never terminates. Because
removeGlassAttachments is reached from SmashWindowPacket.processServer, one
player breaking one window can wedge the entire server tick.

On 2026-08-02 at 17:48 this froze our production server: frame counter stuck at
f:15924, all players motionless, re-login stalled at authentication, graceful
stop ignored. `pkill -9` was the only recovery.

Reproduction steps
------------------
Not deterministic from a plain window smash, because it needs an attachment whose
safe removal is a no-op. Trigger conditions:

1. A window with an attachment on it -- an object with the ATTACHED_TO_GLASS
   property, or an IsoLightSwitch on the window's wall side.
2. That attachment must be in a state where safelyRemoveTileObjectFromSquare
   returns without removing it from THIS square (see root cause: either
   getAllMultiTileObjects fails, or the object's own `square` is a different
   square, or null).
3. Any player smashes that window -> SmashWindowPacket.processServer.

Observed
--------
Player-visible: complete server freeze. All players stop moving, no action has
any effect, reconnecting hangs at the authentication step, and the console's
graceful stop does nothing (the shutdown path also needs the tick).

This is a live loop, not a deadlock: two thread dumps taken 4 seconds apart both
show the main thread RUNNABLE, at different instructions inside the same loop
(alternating between PropertyContainer.get and the stream anyMatch on the
IsoDirections array). Call chain from the dumps:

  SmashWindowPacket.processServer
    -> zombie.iso.objects.IsoWindow.smashWindow
      -> zombie.iso.IsoGridSquare.removeGlassAttachments  <-- spinning here

Across all 100 threads in the dump there was no third-party class on any stack.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
1. The unconditional decrement.
   zombie/iso/IsoGridSquare.java:8218-8230

     for (int n = 0; n < this.objects.size(); n++) {
        IsoObject o = this.objects.get(n);
        if (o.sprite != null) {
           boolean isAttachedToGlass = o.getProperties().has(IsoPropertyType.ATTACHED_TO_GLASS);
           ...
           if (isAttachedToGlass || o instanceof IsoLightSwitch && isAttachedToWindowWall) {
              this.RemoveTileObject(o);   // :8226
              n--;                        // :8227  unconditional
           }
        }
     }

   `n--` is only correct if the element at index n was actually removed from
   `this.objects`. Nothing checks that.

2. The removal is not guaranteed.
   zombie/iso/IsoGridSquare.java:6090-6093

     public int RemoveTileObject(IsoObject obj) {
        boolean chunkIsLoading = obj.getSquare() == null || !obj.getSquare().getChunk().loaded || obj.getSquare().getChunk().preventHotSave;
        return this.RemoveTileObject(obj, !chunkIsLoading);   // safelyRemove = true for a loaded chunk
     }

   For a loaded chunk (the normal case when a player smashes a window) this goes
   to zombie/iso/IsoObjectUtils.java:32-62,
   safelyRemoveTileObjectFromSquare(IsoObject), which has two ways of not
   removing anything from `this`:

   - :36-41  if the object is multi-square and getAllMultiTileObjects fails, it
             returns -1 having removed nothing at all:
                 if (!getAllMultiTileObjects(object, objects)) { ...; return -1; }
   - :45-60  removal is always performed against `obj.square` / `object.square`,
             not against the square the loop is iterating. For a multi-square
             object whose `square` is a neighbouring square (or null, :60), the
             caller's `this.objects` is untouched.

   In either case `this.objects.size()` is unchanged, `n--` steps back onto the
   same element, the same predicate matches, and the same non-removal repeats --
   forever, on the server tick thread.

Field data
----------
- 2026-08-02 17:48, production dedicated server, ~50 players online. Frame
  counter frozen at f:15924. Two thread dumps 4 seconds apart, main thread
  RUNNABLE at different offsets in the same loop. Graceful stop ineffective;
  recovered with SIGKILL and a restart.
- We have not seen it again since installing a guard, but the loop shape in
  42.20.4 is unchanged from the build we crashed on.

Suggested fix
-------------
One line. Only step the index back when the list actually shrank:

     int before = this.objects.size();
     this.RemoveTileObject(o);
     if (this.objects.size() < before) { n--; }

Equivalently, iterate over a snapshot copy of `this.objects` and remove from the
live list, which removes the index bookkeeping entirely. Either way, an
attachment that cannot be removed should be skipped (ideally with one log line
naming the square and sprite) rather than retried forever. It would also be worth
auditing the other index-rewinding loops in IsoGridSquare that call
RemoveTileObject, since the "safe" removal path can no-op for all of them.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode
patch) that replaces the loop with the same semantics plus the shrink check, and
logs the square and sprite when it skips: normal window smashing is unchanged,
and the pathological case degrades from a full server freeze to one uncleared
object plus one log line. Logs / bytecode diffs available on request.
```

---

## R6. 存檔管線共用 CRC32 跨執行緒競態 → chunk header 損毀 → SANITY CHECK FAIL → Blam

### 中文摘要

原 42.20.2 草稿（`docs/report/2026-08-15-chunk-save-crc-race.md`）更新為 42.20.4 版並精簡
到論壇可讀長度。`ServerChunkLoader$SaveChunkThread.addLoadedJob` 與
`SaveLoadedTask.save` 在 42.20.3／42.20.4 逐指令相同（見
`docs/report/pz-42.20.3-update-analysis.md`、`pz-42.20.4-update-analysis.md`）。
優先級**最高**（唯一一個已定罪、有 A/B 驗證、且會持續吃掉玩家基地的資料損失機制）。
建議附件：A 組（crc=0）與 B 組（垃圾值）各一份 `blam/*.bin` ＋ `_error.txt`、
攔截器 BLOCKED log 節錄。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）

### Title

`[42.20.4] [MP] Shared CRC32 instances in the chunk-save pipeline race across threads — chunk headers are written with a wrong or zero CRC, and the next load answers "SANITY CHECK FAIL" with Blam() + LoadBrandNew(), wiping player-built chunks`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific -- corruption is introduced at write time, see analysis]

Summary
-------
On a busy dedicated MP server, chunk files are periodically written to disk with
a valid body and a correct length field, but a wrong or zero CRC field in the
17-byte header. On the next load, IsoChunk$SanityCheck correctly rejects the file
and the engine answers with Blam() + LoadBrandNew() -- permanently regenerating
the chunk and destroying everything the players built in it.

The cause is that the header CRC is computed with a caller-supplied CRC32 object
that is a single shared, unsynchronised instance, while the serialisation entry
point demonstrably runs on more than one thread. A concurrent reset() yields a
header CRC of 0; interleaved update() calls yield garbage. The body and the
length field are written by the serialising thread from its own buffer and are
therefore always correct -- which is exactly what we see in all 51 corrupted
files we examined, and is the constraint that rules out every buffer-tearing or
pool-aliasing hypothesis.

Replacing the two shared CRC32 instances with per-thread ones eliminated the
corruption on our production server: 2.92 million chunk writes, five hourly
world-saves and one shutdown save with zero corrupt headers.

Trigger conditions
------------------
Not deterministic -- the window is a few microseconds at the tail of each save.
Statistically reliable:

1. Dedicated MP server with enough players to keep chunks streaming (we run
   50-100 concurrent).
2. A full world save (SaveWorldEveryMinutes) or, best of all, a restart with
   players still online: the shutdown path (GameServer's shutdown thread running
   ServerMap.QueuedQuit -> QueuedSaveAll) walks every loaded cell through the
   same addLoadedJob while the main loop is still saving. Our historical
   corruption averaged 0.8 events per restart.
3. Instrument the IsoChunk.SafeWrite call sites to recompute the body CRC and
   compare it against the header before writing. Without instrumentation you only
   learn about it later, when the chunk is blammed.

Observed
--------
Player-visible: a base, its walls, crates and contents disappear and the tile
reverts to freshly generated terrain, typically after a restart.

Log fingerprint on the next load:

  java.lang.RuntimeException: SANITY CHECK FAIL! thread="LoadChunk"
  CRC mismatch save=0 load=3748456437
  save chunk=null
  load wx,wy=1009,1428 thread="LoadChunk"
    at zombie.iso.IsoChunk$SanityCheck.log(...)
    at zombie.iso.IsoChunk$SanityCheck.checkCRC(...)
    at zombie.iso.IsoChunk.LoadFromDiskOrBufferInternal(...)
    at zombie.network.ServerChunkLoader$LoaderThread.run(...)

In that specific case a 28,401-byte chunk holding a player base was replaced by a
5,470-byte fresh chunk.

Two signatures across all 51 files we examined:
- Signature A (19 of 51): header CRC field == 0; length correct; body fully
  self-consistent -- recomputing CRC32 over [17, len) matches the value the game
  itself reports as `load=`. Byte-perfect except for the 8-byte CRC field.
- Signature B (32 of 51): header CRC field holds a non-zero value matching
  nothing; length still correct.
The length field is correct in 51/51.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
1. The header is stamped with a caller-supplied CRC32.
   zombie/iso/IsoChunk.java:4369 declares
     public ByteBuffer Save(ByteBuffer bb, CRC32 crc, boolean bHotSave)
   and its tail, IsoChunk.java:4496-4502, is:

     int len = bb.position();
     crc.reset();                                        // :4497
     crc.update(bb.array(), 17, len - 1 - 4 - 4 - 8);    // :4498
     bb.position(5);
     bb.putInt(len);                                     // :4500  length field
     bb.putLong(crc.getValue());                         // :4501  CRC field
     bb.position(len);

   The three-step reset/update/getValue sequence is atomic only if `crc` is not
   shared.

2. The MP save pipeline passes a single shared instance.
   zombie/network/ServerChunkLoader.java:430 and :440 declare and construct
   `private final CRC32 crc32` on the (single) SaveChunkThread, and
   ServerChunkLoader.java:471-487, addLoadedJob(IsoChunk), serialises ON THE
   CALLING THREAD:

     public void addLoadedJob(IsoChunk chunk) {
        ClientChunkRequest.Chunk reqChunk = this.ccr.getChunk();
        ...
        chunk.SaveLoadedChunk(reqChunk, this.crc32);   // :478  shared instance
        ...
     }

   SaveLoadedChunk (IsoChunk.java:4361-4363) simply forwards it to Save(...).

3. addLoadedJob runs on more than one thread. Its callers reach it from the main
   loop (ServerMap$ServerCell.Save / ServerCell.update -> saveChunk) and from the
   shutdown thread (ServerMap.QueuedQuit -> QueuedSaveAll), which is why the
   corruption historically clustered at restarts.

4. The race produces exactly the two observed signatures. Thread A completes
   :4498 and calls getValue() at :4501; if thread B's reset() at :4497 lands in
   between, A reads 0 -> Signature A. If A's and B's update() calls interleave,
   A reads a mixed value -> Signature B. The body and the length field come from
   A's own buffer, hence always correct.

5. A second shared-CRC32 race sits one step downstream.
   zombie/network/ServerChunkLoader.java:31 declares `private final CRC32
   crcSave`, and SaveLoadedTask.save() -- ServerChunkLoader.java:591-598 -- uses
   it for the dedup checksum:

     long crc = ChunkChecksum.getChecksumIfExists(this.chunk.wx, this.chunk.wy);
     ServerChunkLoader.this.crcSave.reset();                                    // :593
     ServerChunkLoader.this.crcSave.update(this.chunk.bb.array(), 0, this.chunk.bb.position());  // :594
     if (crc != ServerChunkLoader.this.crcSave.getValue()) {                    // :595
        ChunkChecksum.setChecksum(this.chunk.wx, this.chunk.wy, ServerChunkLoader.this.crcSave.getValue());  // :596
        IsoChunk.SafeWrite(this.chunk.wx, this.chunk.wy, this.chunk.bb);        // :597
     }

   save() normally runs on SaveChunkThread, but SaveChunkThread.saveNow(int,int)
   (ServerChunkLoader.java:493-514) executes queued tasks on whatever thread calls
   it -- and it is called from ServerChunkLoader$LoaderThread (flush-before-load),
   concurrently with SaveChunkThread.run() executing other tasks. An interleaved
   dedup CRC pollutes ChunkChecksum, which can both skip a needed disk write
   ("unchanged" false positive -> stale chunk on disk) and desynchronise the
   server<->client chunk checksum protocol.

6. Related defects in the same pipeline, found while tracing the above:
   - SaveChunkThread.update() drains into a plain ArrayList and calls release()
     on each task; updateSaved() is reachable from the main loop and from the
     shutdown path concurrently, so the same task can be released twice.
     SaveLoadedTask.release() (ServerChunkLoader.java:601-604) returns the
     ClientChunkRequest.Chunk and its ByteBuffer to the JVM-global static pools
     ClientChunkRequest.freeChunks / freeBuffers -- a double release puts the same
     buffer in the pool twice, after which the save pipeline and a
     PlayerDownloadServer send worker can own it simultaneously.
   - SaveChunkThread.run()'s catch(Exception) logs but does not hand the failed
     task to fromThread, so its Chunk + ByteBuffer are never released
     (saveNow() does release on failure).
   - saveNow() drains toThread, processes matches, and re-appends the remainder at
     the tail; two queued saves of the same chunk can invert order.

Field data
----------
- 43 chunks blammed over ~2 weeks, ~143 KB of player-built content lost, still
  occurring at the time (8 in a single day). Split: 16 Signature A, 27
  Signature B.
- Real case: chunk 1009,1428, a player base with storage crates, 28,401 bytes ->
  5,470 bytes.
- With a verify-before-write gate installed at the SafeWrite call sites, the
  first night caught 8 corrupt writes red-handed in 2.5 hours -- 8/8 with the
  identical stack (SaveChunkThread -> SaveLoadedTask.save, i.e. path 2/5 above),
  3 with crcField == 0 and 5 with garbage, all 8 with a correct length field.
- After making the two CRC32 instances per-thread, the same gate's flagged
  counter went to zero: 5.7 hours, 2.92 million chunk writes, five hourly full
  saves and one shutdown save, zero corrupt writes. Expected at the pre-fix rate:
  ~9-18 (Poisson p < 2e-4).
- Recovery: all 16 Signature-A files were fully restorable by recomputing CRC32
  over bytes [17, len) of the blam/ backup and rewriting the 8-byte CRC field --
  16/16 restored, including the base above. Signature B is not batch-recoverable.

Suggested fix
-------------
1. In SaveChunkThread.addLoadedJob (ServerChunkLoader.java:478) and
   SaveLoadedTask.save (:593-596), use a local or per-thread CRC32 instead of the
   shared fields. A `new CRC32()` per save is negligible next to the disk write it
   accompanies. This alone removes the data-loss mechanism.
2. Make the updateSaved() release path thread-confined or synchronised, or make
   pool release idempotent, so a double release cannot hand one ByteBuffer to two
   owners.
3. In SaveChunkThread.run()'s catch(Exception), still hand the task to fromThread
   so its pooled buffer is released.
4. Consider giving the save pipeline its own buffer pool rather than sharing the
   JVM-global ClientChunkRequest.freeChunks / freeBuffers with the client-send
   workers.
5. Orthogonal but high value: a failed CRC check should not answer with Blam() +
   LoadBrandNew(). Refusing to load (and leaving the file plus a backup alone) is
   recoverable; regenerating is not.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode
patch): first a verify-before-write gate that caught the 8 corrupt writes above
with full stacks, then per-thread CRC32 instances plus a private buffer pool for
the save pipeline, after which the gate's flagged counter has stayed at zero
across 2.92 million writes. Logs, corrupted-file samples (both signatures) and
bytecode diffs available on request.
```
