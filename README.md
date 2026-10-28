# MinidoracatJavaPatchFor42

**English** | [繁體中文](README.zh-TW.md)

Bytecode patches for **Project Zomboid Build 42** (currently **42.21.0**) that fix vanilla bugs and remove
hot spots we found while running a busy multiplayer server, plus an optional client package for players.

- **Server patches**: loose `.class` overrides for the dedicated server. They address main-loop freezes
  and livelocks, lost chunks, animals and vehicles, main-thread hot spots, bandwidth loops, stuck timed
  actions and log noise. 79 patched classes, 239 patch sites and 89 helper classes on 42.21.0.
- **Client patches**: an optional package for players (invisible players/zombies/vehicles; three 42.21.0
  player-built room bugs: vanishing objects, and two disconnects while chunks load near player-built buildings; mod vehicles re-animated every frame while parked; vehicles thrown tens or hundreds of tiles away when driving into an area that has not loaded; a vehicle pushed under the floor in a collision, which blacks out the screen). Download it from [Releases](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases).
- **Native guards**: `LD_PRELOAD`/`LD_AUDIT` shims for two native crashes on the Linux dedicated server.

Every patch targets a specific vanilla defect or a measured hot spot and has a written root-cause analysis:
[docs/patches.en.md](docs/patches.en.md) (English) and [docs/patches.md](docs/patches.md)
(Traditional Chinese, the original and most detailed version).

> **For The Indie Stone developers**
>
> Everything below was diagnosed on a production server with thread dumps, JFR, heartbeat counters and
> packet captures, and every fix was checked instruction by instruction against the shipped
> `projectzomboid.jar`. Several of these issues were fixed in 42.20.2–42.21.0 (see
> [Fixed upstream](#fixed-upstream)); thank you. The **TIS** column shows which ones we have reported on the
> forum and which are still waiting. We are happy to share logs, packet statistics or test builds for any
> item: open an issue here or contact **Minidoracat** on the forum.

## Contents

- [Why](#why)
- [Patch catalog](#patch-catalog): [stability](#stability-freezes-livelocks-and-crashes) ·
  [data integrity](#data-integrity-lost-chunks-animals-and-vehicles) · [performance](#performance-main-thread-time) ·
  [network](#network-bandwidth-and-resend-loops) · [multiplayer correctness](#multiplayer-correctness) ·
  [accounts and abuse](#accounts-and-abuse) · [log noise](#log-noise) · [observability](#observability) ·
  [client](#client-patches-release-package) · [native](#native-guards-linux-dedicated-server)
- [Open vanilla issues](#open-vanilla-issues)
- [Fixed upstream](#fixed-upstream)
- [How the patches work](#how-the-patches-work)
- [Server: build and deploy](#server-build-and-deploy)
- [Client patches: install](#client-patches-install)
- [Recommended server settings](#recommended-server-settings)
- [Optional tools](#optional-tools)
- [Repository layout](#repository-layout)
- [Documentation](#documentation)
- [Support](#support) · [License and disclaimer](#license-and-disclaimer)

## Why

Our server has daily peaks of 68–95 concurrent players (254 slots). The dedicated server's main loop is
single-threaded and capped at 10 FPS, so every millisecond counts. Each patch started from a production
incident or a profiler hot spot. Some examples of what changed:

| Area | Before | After |
|---|---|---|
| Console noise | `Send Toxic Building` alone was 45.5% of all console lines | gone; other noise patterns filtered, anticheat warnings kept |
| Chunk corruption ("Blam" wipes) | 43 player-built chunks wiped after `SANITY CHECK FAIL` | first night: 8 corrupt writes blocked, nothing lost; 0 flagged in 2.9 M writes after the root-cause fix |
| Main-loop freezes | 13-minute and 114-minute freezes, a server-wide livelock, 5–16 s stalls | no recurrence since the guards went live |
| Animal hearing scan | 4.34 ms per frame (4.3% of the main thread) | 0.69 ms (0.6%) |
| Animal line of sight | ~16 ms per frame (~9% of the main loop) at ~50 players | spatial prefilter, identical results (benchmark 4.8 ms → 0.25 ms per frame) |
| `processItems` registration | ~30k-item list scanned linearly on every registration (~6% of the main thread) | identity index (benchmark 43 µs → 0.3 µs) |
| Animal full snapshots | 38–40% of outbound traffic, 87% of it resends | radius aligned with the client's loaded area, per-animal cooldown |
| `VehicleCollide` loop | 239 packets/s from one client, 180k warning lines in 2 hours | 9.8 packets/s |
| Crafting station broadcasts | `GameEntity` packets were 17.5% of outbound UDP | nearby players only, sent when the visible state changes |

At ~63 players the main loop held 9.4–10.1 FPS with every patch counter reporting zero anomalies.

## Patch catalog

Status is the default on 42.21.0. Every server patch has a `-Dmdc.*` kill switch (see its section) unless
noted. **TIS**: a topic number links to our forum report; "draft" means a report is written but not posted;
"not reported" means no report yet.

### Stability: freezes, livelocks and crashes

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| W5 Container cycle guard ([2q](docs/patches.en.md#2q)) | Multiplayer moves can place a container inside its own descendant. `ItemContainer.getCharacter()` then recurses without end and `StackOverflowError` kills the main loop (13-minute silent freeze). | Detects the cycle on the ownership walk (identity path plus depth fuse), cuts it and logs the loop. No recurrence since 2026-08-13. | [#100891](https://theindiestone.com/forums/topic/100891/) |
| W6 Chunk-load catcher ([2r](docs/patches.en.md#2r)) | `IsoObject.addToWorld` throws "Entity is already registered" inside `IsoChunk.doLoadGridsquare`. The square stays queued and throws again every 0.1 s: a 114-minute livelock. | Catches it at the two `addToWorld` calls, logs coordinates and sprite, skips that object. The stale registrations still happen a few times a day but no longer freeze the server. | [#100893](https://theindiestone.com/forums/topic/100893/) |
| W11 Animal sound sort ([2y](docs/patches.en.md#2y)) | `BaseAnimalSoundManager` sorts with a comparator that recomputes distances; NaN breaks its contract, TimSort throws, `clear()` is skipped and the stale list fails every frame: server-wide stuck actions and "time stopped". | Catches the exception and skips the sort for that frame so `clear()` still runs; logs where the NaN came from. | [#100897](https://theindiestone.com/forums/topic/100897/) |
| W37 Half-constructed animals ([2az](docs/patches.en.md#2az)) | When the checks in an `IsoAnimal` constructor fail, the object is already in the cell with null `adef`/`data`; every tick throws (game time froze for about 65 minutes). Saving opens `apop_*.bin` before serializing, so a failure leaves a 0-byte file and the animals are lost. Dropping an animal on a water square puts the failed object back on the square; when that cell unloads, every tick throws, no later cell unloads and the heap filled in about an hour. Some shore squares look like grass and can be walked on, but their floor is water, so players drop animals there without noticing. | Withdraws failed objects before the constructor returns; serializes first and writes the file only on success. An animal dropped on a water square is built on the first neighbouring square that is land, walkable and not cut off by a wall or fence, and clients are told that square. If there is none, the failed animal is removed from the world and not sent to clients; chunk unloading swallows only that animal's exception. | draft |
| W40 / W41 `processItems` ([2bc](docs/patches.en.md#2bc), [2bd](docs/patches.en.md#2bd)) | The vehicle loader thread writes `IsoCell.processItems` directly. A null gets in, `ProcessItems` throws every 5 s, the list stops shrinking and linear `contains` during chunk loading froze the server for 5–16 s (FPS 9.8 → 2–3). | W40 skips and removes nulls in the same frame; W41 queues off-thread registrations and applies them on the main thread. | draft |
| Hit packet null guards ([2c](docs/patches.en.md#2c)) | `hit/Zombie.process` and `hit/Fall.process` dereference fields that a malformed packet leaves null. | Null guard before the super call. | not reported |
| W15 Main-loop watchdog ([2ac](docs/patches.en.md#2ac)) | Freezes leave no stack trace. | Observe only: snapshots the main thread after 5 s without a frame. It showed that the remaining freezes are the synchronous `QueuedSaveAll`. | suggestion [#100925](https://theindiestone.com/forums/topic/100925/) |
| W70 Bandit Raid story infinite loop ([2cf](docs/patches.en.md#2cf)) | The Bandit Raid dead-survivor story reuses the outer loop counter in its inner corpse loop, so for an unexplored building with 50–100 rooms the loop never ends: the main thread stays inside the cell load and keeps spawning zombies and corpses until the heap is full (10-minute freeze on our server after adding new maps; the shutdown save kept tens of thousands of zombies and their corpses). | Caps the round count at 4, the largest value for which the vanilla loop ends, so these buildings run like a 40–49-room building; smaller buildings are unchanged. | not reported |
| W75 Off-thread update-list removal ([2cj](docs/patches.en.md#2cj)) | When a vehicle loads on the `ServerPlayersVehicles` thread with animals in its trailer, `IsoObject.removeFromWorld` calls `IsoCell.addToProcessIsoObjectRemove` on that thread, which reads and writes the two `HashSet`s the main thread changes every frame in `ProcessIsoObject` (20 times in 50 minutes on our server). | Queues those calls and runs them at the head of the next `ProcessIsoObject` on the main thread, before vanilla's removal step, so the removal lands in the same pass; a main-thread add of the same object cancels the queued removal, as vanilla's add does. | not reported |
| W75-2 Off-thread update-list add ([2ck](docs/patches.en.md#2ck)) | While reading a chunk file on the `LoadChunk` thread, the butcher hook constructor calls `IsoCell.addToProcessIsoObject` on that thread and writes the list and sets the main thread uses every frame (81 times in the first 50 minutes after a restart on our server). | Same FIFO queue as W75 (adds and removes keep their order), drained at the head of the next `ProcessIsoObject` on the main thread; a direct remove of the same object cancels the queued add, so every ordering ends as vanilla would. | not reported |

### Data integrity: lost chunks, animals and vehicles

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| W7 Per-thread direction vector ([2s](docs/patches.en.md#2s)) | `IsoGameCharacter.setForwardDirectionFromIsoDirection` uses a shared static `tempVector2_2`. The chunk loader thread and the main loop race, a zero-length vector aborts the chunk load and vanilla wipes the chunk (a player's hutch with 32 animals was lost). | Redirects both reads to a thread-private vector (same stack shape, no frame changes). | [#100895](https://theindiestone.com/forums/topic/100895/) |
| W8 Chunk write gate ([2t](docs/patches.en.md#2t)) | 43 player-built chunks were wiped after `SANITY CHECK FAIL`. Forensics showed the files were already written with a wrong or zero CRC. | Before `SafeWrite` truncates the old file, verifies length and CRC; a corrupt write is blocked, the previous file is kept and the stack is logged. First night: 8 corrupt writes blocked, no data lost. | [#100901](https://theindiestone.com/forums/topic/100901/) |
| W9 Save pipeline isolation ([2u](docs/patches.en.md#2u)) | Shared `CRC32` instances raced between the save threads (the cause of W8's corrupt headers), and the save pipeline shared a global chunk pool with the send workers. | The CRC part was fixed upstream in 42.21.0 and retired; the private chunk pool for the save pipeline remains. 0 flagged in 2.9 M writes. | [#100901](https://theindiestone.com/forums/topic/100901/) (partly fixed) |
| W12 Vehicle chunk index ([2z](docs/patches.en.md#2z)) | `VehiclesDB2$VehicleBuffer.set` takes `wx`/`wy` from a stale or pooled `vehicle.chunk` while `x`/`y` come from physics. The row is saved under the wrong chunk and the vehicle never loads again. | Derives `wx`/`wy` from the vehicle position (about 180 corrections a week on our server). | [#100913](https://theindiestone.com/forums/topic/100913/) |
| W17 Hutch load ([2ae](docs/patches.en.md#2ae)) | `IsoHutch.load` discards the result of `addAnimalInside`; in a nearly full hutch the animal is silently destroyed on load. | Falls back to a free slot; logs a critical line only when the hutch is really full. | [#100907](https://theindiestone.com/forums/topic/100907/) |
| W38 Livestock catch-up snapshot ([2ba](docs/patches.en.md#2ba)) | The offline catch-up loop iterates `zone.animals` by index while catch-up moves animals to the end of the list, so some are caught up twice and others skipped. Doubled hunger and age caused most unexplained deaths. | Iterates over a snapshot taken for that pass. | draft |
| W42 Catch-up hours cap ([2be](docs/patches.en.md#2be)) | Offline hours come from the pen's last-seen time, which is stale when only part of a large pen reloads; animals get days of extra catch-up (25 of 38 deaths in one session happened within 60 s after catch-up). | Caps catch-up at the animal's own offline time and refreshes its clock hourly while it is alive. | draft |
| W49 Animal offline catch-up ([2bm](docs/patches.en.md#2bm)) | Herds age and progress pregnancy at only 50–60% of game time: saves store the save time instead of the animal's clock, so time before a restart is never caught up; the first load after boot catches up 0 hours; each catch-up drops up to 23 accumulated hours; catch-up grows at midnight but loaded animals every 24 hours. | Catches up from the animal's own clock (saved for unloaded animals, refreshed hourly while loaded or in a hutch) with the same 24-hour accrual as loaded animals; one absence catches up at most 168 game hours (configurable); hooked carcasses are not caught up. | not reported |
| W50 Hooked carcass saves ([2bn](docs/patches.en.md#2bn)) | A carcass loaded from disk finds its hook only on its first update; a save before that writes onHook=0 and the carcass comes back as a live animal. | Writes the hook state from the coordinates restored at load (the same format vanilla uses) and never commits a record known to be wrong; failed apop saves no longer write in-world animals twice on retry. | not reported |
| W56 Catch-up waits for the animal zone ([2bt](docs/patches.en.md#2bt)) | The offline catch-up runs while the chunk loads, before the troughs are registered back to the zone (they are removed on unload and only the whole-zone `check()` restores them), so the catch-up finds no trough. Vanilla only fed the animals through a second catch-up when the whole zone streamed in; once W42 removed that double catch-up, livestock came back as if they had not eaten or drunk while the player was away. | Freezes the animal at load and catches up once every chunk of the connected zones is loaded and the troughs and ground food are rebuilt (normally the same frame, at most 10 seconds); a trough is registered with its zone as soon as it enters the world; deferred animals that unload, are picked up or are replaced by a new object are handled. | not reported |
| W33 Birth breed guard ([2av](docs/patches.en.md#2av)) | A birth whose breed or baby definition cannot be resolved still reaches the `IsoAnimal` constructor. | Skips that birth and logs it. | not reported |
| W43 / W44 Ground-item expiry and failed transactions ([2bg](docs/patches.en.md#2bg)) | Clients drop expired ground items on every chunk load, the server never does while the chunk stays loaded. Item indexes diverge and "ghost" items cannot be picked up; the failed pickup is never reported, so the progress bar waits its duration plus 10 s. | The server removes expired ground items with the same rule before sending the chunk; failed transactions are rejected at once. | not reported |
| W69 Entity ID collision on removal ([2ce](docs/patches.en.md#2ce)) | `IsoObject.getEntityNetID` builds the ID from the position and the object's index on the square, but always uses index 0 for floors. When a player-built floor sits after a wall or furniture in the object list, the object at index 0 gets the floor's ID. Removing it on the server throws `Stored entity mismatch` in `UnregisterEntity`: clients already got the removal packet, the server keeps the object, and after a relog it comes back as a leftover that cannot be removed and blocks placement (once multi-tile furniture is missing a tile, it cannot be picked up, disassembled or sledgehammered). On chunk unload the exception aborts the cell unload. 248 times in 8 days on our server, 19 of them on removals. | When the ID slot holds another entity, puts the removed entity there, lets vanilla remove it, then puts the original entity back; an entity missing from the ID table also leaves the engine. The ID scheme is unchanged (clients use the same one). | not reported |

### Performance: main-thread time

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| Entity removal index ([2g](docs/patches.en.md#2g)) | Batch chunk unloads run an identity linear search over a global array for every entity. | Weak-key plus primitive sidecar index: 439 → 63 ns per entity. | not reported |
| W1-1 Vehicle LOS prefilter ([2k](docs/patches.en.md#2k)) | `IsoZombie.isVehicleBetween` runs a full OBB intersection against every vehicle in the cell for every zombie. | A conservative bounding-sphere test first; 99.87% of checks rejected, vehicle checks vanished from low-FPS dumps. | not reported |
| W3 wave ([design, Chinese](docs/wave3-design-v1.md)) | Owned zombies redo the ownership election every tick (O(connections × players)); animals call `spotted()` on every zombie in the cell although its effects need 10 tiles; vehicles compute player visibility on the server only to feed a no-op. | Re-elect every 3 passes; skip far `spotted()` calls (99.94% intercepted); short-circuit the dead work. | not reported |
| W18 / W18-2 Animal LOS throttle ([2af](docs/patches.en.md#2af)) | `IsoAnimal.updateLOS` walks the whole object list every tick; it was 41.7% of main-thread samples at 67 players. | Round-robin throttle (configurable N) and a cheaper scan of the same candidates with identical results. | not reported |
| W47 Animal LOS spatial prefilter ([2bj](docs/patches.en.md#2bj)) | After W18-2 every LOS check still walks ~4,300 objects: ~16 ms per frame (~9% of the main loop) at ~50 players. | Per-frame zombie grid; results identical to vanilla (4,000 randomized worlds diffed); benchmark 4,780 → ~250 µs per frame. | not reported |
| W83 Player LOS window filter ([2cr](docs/patches.en.md#2cr)) | `IsoPlayer.updateLOS` on the server walks the whole object list for every player every frame and pays a type-cast and `ServerLOS.isCouldSee` lookup (a linear scan over all players) for each character, near or far; it was the top self frame (13.3% of 60 dumps) at 51–55 players. Objects outside the 96×96 `ServerLOS` window can never be seen there, and on the server the loop does nothing observable with them. | Wraps the loop's iterator so it only yields the player itself, objects inside that window and anything unusual, on the vanilla iterator (same order and `ConcurrentModificationException` points); the loop body is untouched. Observe by default (vanilla behavior at negligible cost); both modes randomly pick each call with probability 1/64 for an audit (every would-be-skipped object must be invisible) and 1/64 for a vanilla-timing loop, and any mismatch reverts to vanilla for the session. Identical to vanilla in a field-by-field diff of 1,105 calls. | not reported |
| W48-2 Animal hearing index ([2bk](docs/patches.en.md#2bk)) | Every animal scans the global sound list (~4,300 sounds, ~12 in range) every tick. | Spatial index with identical results (audited every 256 calls; 130k audits, zero mismatches): 4.34 → 0.69 ms per frame. | not reported |
| W72 Hutch animals' egg check ([2cg](docs/patches.en.md#2cg)) | Every tick, every animal inside a hutch whose egg time is due recomputes the paddock hutch twice, each time 3 full connected-zone searches (15–34 µs each with 666 zones); the result never changes the branch for an animal already inside a hutch. With full nest boxes the egg time never resets, so this repeats every tick (2 of 60 main-thread dumps). | Returns the animal's own hutch for animals inside one; vanilla search otherwise. Identical outcomes to vanilla over 23,040 scenarios except where the vanilla search itself throws. | not reported |
| W45 `processItems` identity index ([2bh](docs/patches.en.md#2bh)) | Every item registration runs a linear `contains` over a ~30k-item list. | Identity-indexed list with the same order and contents; 43 → 0.3 µs per registration (benchmark). | draft |
| W25 Serialization pool isolation ([2am](docs/patches.en.md#2am)) | `SaveAll` workers spend 80% of their samples contending on the `ConcurrentLinkedDeque` pools in `BitHeader` and `ByteBlock`. | Per-thread pools (the main thread skips the `ThreadLocal` lookup): 1,095 → 38 ns per iteration with 4 threads (29×). | not reported |
| W35 Using-player index ([2ax](docs/patches.en.md#2ax)) | `UsingPlayerUpdateSystem` scans every IsoObject entity each frame (5.9% in JFR) only to clear `usingPlayer` when a player walks away. | Tracks the objects that have a `usingPlayer`, with periodic audits. | not reported |
| W34 Emitter parameters ([2aw](docs/patches.en.md#2aw)) | The server computes FMOD footstep parameters for a dummy emitter that nothing reads (1.6%). | Skips them on the server. | not reported |
| W4-1 Chunk request packing ([2p](docs/patches.en.md#2p)) | Chunk supply per player is capped at main-loop FPS × one row of chunks; at 3 FPS a car outruns it (black edges). | Packs queued requests into larger batches (observe mode by default). | not reported |
| W53 Smaller vehicle range on foot ([2bq](docs/patches.en.md#2bq)) | The client recomputes the skeleton of every skinned part of every loaded vehicle each frame (22–47 µs per KI5/rSemiTruck vehicle), and the server's vehicle relevance range (±88 tiles at 1080p) is wider than the client's own chunk map, so busy spots load over a hundred vehicles and cost several ms per frame. | On foot, only sends and keeps vehicles within a circle of R tiles (default 64; always a subset of vanilla); vanilla while anyone on the connection is in a vehicle or joining. Observe (count only) by default; at a spot with 107 vehicles in a production snapshot, R=64/48 leaves 57/28. It also counts players sitting in parked vehicles, to evaluate treating them like walking. | not reported |
| W73 IsoObject per-frame update gate ([2ch](docs/patches.en.md#2ch)) | `IsoCell.ProcessIsoObject` calls `update()` every frame on every stove, washer, generator and player-built `IsoThumpable` in loaded chunks. On the server an ordinary built object's update only runs a linear `indexOf` over its square and returns without writing anything; in evening-peak dumps the loop itself was about 8% of the main thread. | Observe by default: measures list size, composition, per-class cost of one timed frame every 5 minutes and off-thread writes. On mode skips the update of `IsoThumpable` objects whose `lifeLeft` is not above -1 (everything except fuelled lamps), with one in 4096 skips run as vanilla and compared field by field; any difference reverts to vanilla for the session. | not reported |
| W74 Pathfinding thread probe ([2ci](docs/patches.en.md#2ci)) | `PathfindNativeThread` (its own thread, not the main loop) runs at 99–100% CPU on our server with every sample inside native `findPath`. It serves at most 2 requests per 50 ms round, and any chunk, square or vehicle change makes the next native `findPath` rebuild every visibility graph and vehicle cluster, but nothing shows how long each search takes, how much of it is that rebuild, who is waiting or for how long. | Observe only (results, exceptions, call count and order unchanged): one console line per minute with search count, failures, average/p95/max cost, the cost split by whether a world change came first (so the rebuild share can be computed), per-requester counts (players, zombies with or without a target, animals) and queue wait. | not reported |
| W81 Pathfinding world-change batching gate ([2cp](docs/patches.en.md#2cp)) | Every chunk, square or vehicle change sets one global flag in native pathfinding, and the next search rebuilds every visibility graph and vehicle cluster. With 41–45 players on our server that happened every round: each round took about 335 ms for 2 requests (about 6 req/s against 21–26 req/s of demand, all animals), and animals waited 25–106 seconds on average for a path. | Applies world changes in batches: after an open round (including the rebuild it triggers) ends, the gate stays closed for max(T, 2 × that round's pathfinding time) (`-Dmdc.pathfindGateMs`, default 1000, `0` = vanilla; `-Dmdc.pathfindGateAdaptive=off` goes back to a fixed T counted from the open decision). The closed time is capped at 10 s (`-Dmdc.pathfindGateHoldMaxMs`, `0`/`off` = no cap), because the rebuild grows with the requests since the last one and the 2× would feed on itself. In between, those tasks stay queued in order while path requests pass through; an open round applies the whole backlog, without vanilla's 10-chunks-per-round cap. Server pathfinding sees changes up to "rebuild time + closed time" late (estimated about 6 s at medium load and 10 s at full load with T=3000 on our server). Main-thread time unchanged. | not reported |

### Network: bandwidth and resend loops

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| W13 / W14 Animal sync range and cooldown ([2aa](docs/patches.en.md#2aa), [2ab](docs/patches.en.md#2ab)) | The animal relevancy radius is 10/8 of the client's guaranteed loaded half-width, and requests have no cooldown or range check. Clients keep requesting full snapshots of animals they cannot place: 38–40% of outbound traffic, 87% of it resends. | Aligns the radius with the client's loaded area, sends one full snapshot per animal per connection per cooldown, range-checks requests. | [#100915](https://theindiestone.com/forums/topic/100915/) |
| W36 `GameEntity` broadcast scope ([2ay](docs/patches.en.md#2ay)) | Working crafting stations (for example drying racks) broadcast their full `CraftLogic` state to every player every second: 17.5% of outbound UDP. | Sends only to connections in range, and only when the visible progress or state changes. | draft |
| W26 Hutch sync recipients ([2an](docs/patches.en.md#2an)) | Hutch self-syncs go to every player on the server. | Sends them only to players whose loaded area covers the hutch. | not reported |
| W31 Fish school broadcast ([2as](docs/patches.en.md#2as)) | `transmitFishingData` serializes the same body again for every connection. | Serializes once per batch and reuses the bytes. | not reported |
| W46 `VehicleCollide` resync ([2bi](docs/patches.en.md#2bi)) | When the server handles a collide request and its release in the same frame, its per-connection cache does not change, so it never corrects the client, which keeps sending releases every frame: 239 packets/s and 180k warning lines in 2 hours. | A release marks that connection's authorization cache stale so the next sync sends the real authority: 9.8 packets/s. | not reported |

### Multiplayer correctness

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| W10 Stuck timed actions ([2x](docs/patches.en.md#2x)) | A packet argument that deserializes to null makes the Lua action constructor throw before `processServer`. The client gets neither Accept nor Reject, and its whole action queue is stuck. | A fuse around the constructor and the argument parse; the client gets a proper Reject. (The Reject written from the wrong object, W10-A, was fixed in 42.21.0.) | [#100905](https://theindiestone.com/forums/topic/100905/) |
| W10-C Interrupted actions ([2aj](docs/patches.en.md#2aj)) | A new request silently stops the player's accepted action on the server without telling the client. | Sends a Reject for the interrupted action (enforce mode) and only dispatches action packets whose player belongs to the sending connection. | draft |
| W20 Clothing sync ([2ah](docs/patches.en.md#2ah)) | One worn item with a null visual throws in `ItemDescription` and stops all clothing broadcasts for that player; a count mismatch drops the whole `SyncVisuals` packet. | Guards the tint read; observability for the other two clusters. | [#100911](https://theindiestone.com/forums/topic/100911/) |
| W59 Teammates missing from the map ([2bw](docs/patches.en.md#2bw)) | A client only requests a remote player's full map data when its change count differs from the server's, and draws the player only after that data arrives. Both sides start the entry at 1 (the client's first frame bumps a new entry from 0 to 1), and the request and reply are unreliable, so one lost packet leaves the counts equal forever: that teammate never appears on the player's mini map or world map. | The server sends `base + 2 × count` (base: an even number of at least 1024, random per boot), which a client without the data can never match, so it keeps requesting every second until the data arrives. Same traffic as vanilla otherwise; server only. | not reported |
| W64 Vehicle stuck under the floor ([2ca](docs/patches.en.md#2ca)) | Vehicle physics runs on the driver's client, and `VehiclePhysicsPacket.processServer` stores whatever height the client reports. When the client's physics pushes a vehicle under the floor (2026-10-14: a slow reverse into a parked semi truck; 2026-10-15: a car flipped 32 m under the floor after a driving teleport), every client keeps getting that height, even after relogging. Only `BaseVehicle.load` clamps the height to the floor, and nothing rights a flipped vehicle. | When the vehicle is handed back to the server (driver leaves, seat change, disconnect, collide release) with its origin 0.25 m or more below a floor that exists, lifts it to its spawn resting height and sends a full update to every client. A flipped vehicle is only handled when it is more than one level below the floor: it is set upright first, keeping its heading. A flipped vehicle sunk less than that stays vanilla for players to flip back. | not reported |
| W66 Livestock zones lost or duplicated ([2cb](docs/patches.en.md#2cb)) | Any player can delete any animal zone, together with every zone touching it, and the server keeps no record. Each connection reuses one cached `SyncZonePacket`; the grass-regrowth notice sent when animals graze (`IsoGridSquare.removeGrass`) does not clear its zone fields, so it resends that player's last zone add, rename or delete: a zone someone else deleted keeps coming back on that player's screen, and other clients replay the last delete they received. On startup, a zone whose temporary random id collides with an already loaded zone is left out of the saved list and disappears at the next restart (about 2.2% per restart with 664 zones). | Logs every zone add, rename and delete with the account, its position and the whole connected group a delete takes with it; sends the grass notice as `RegisterZone`, which vanilla already uses for zone timestamps; puts a zone missed by the id collision back into the saved list. Who may delete zones is unchanged. | not reported |
| W80 Invisible zombies ([2co](docs/patches.en.md#2co)) | A dead zombie keeps its network owner, and the owner is not cleared when the object is recycled for the next zombie 5 seconds later. The new zombie is born owned by that player, but the player's owned list does not contain it: when the server assigns it to the same player again it does nothing, sends it to no client, and nobody sees it until ownership changes (the server prints `moveZombie: There are no zombies in nz.zombies.`). In our vanilla test, all 15 zombies spawned with a stale owner were invisible on both clients. | Clears the owner when the object enters the reuse pool and takes the object out of that player's owned list, so the new zombie gets an owner by the vanilla rules. Suppression #5 now counts that line and the heartbeat reports the total. | draft |
| LootRespawn fixed containers ([2e](docs/patches.en.md#2e)) | On custom maps without a vanilla TownZone, original fixed containers never respawn loot. | Narrow fallback for original, unmoved containers only. | design issue |
| Animal stress tuning ([2b](docs/patches.en.md#2b)) | Gameplay tuning, not a bug. | Idle stress decay ×2, sound stress ÷3, slaughter chain cap halved. | — |

### Accounts and abuse

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| W23 Account limit per Steam ID ([2ak](docs/patches.en.md#2ak)) | `MaxAccountsPerUser` is only checked when a new account is created, so existing accounts are never limited. | Enforces the limit at login, keeping the most recently used accounts. Server policy. | not reported |
| W52 Split-screen and respawn names ([2bp](docs/patches.en.md#2bp)) | `ConnectCoopPacket` takes the player name from the packet and only rejects an empty or connected name. A split-screen player (vanilla UI) or a respawning player (modified client Lua, any `AllowCoop`) can take an offline player's name and pass name-based safehouse, faction and mod checks; the owner cannot log in meanwhile. | Player 0 always gets the account the connection logged in with; split-screen players 1–3 cannot use an existing account name (rejected before any state changes). | draft |
| W67 Livestock zones in safehouses ([2cc](docs/patches.en.md#2cc)) | Any player can delete any animal zone, together with every zone touching it, including zones inside someone else's safehouse. | A delete whose group overlaps a safehouse is accepted only from that safehouse's owner, its members or an admin (`CanSetupSafehouses`); otherwise the server keeps the zones, sends them back to the player who deleted them and tells them why. Server policy. | not reported |
| W76 Build request class check ([2cl](docs/patches.en.md#2cl)) | A gap in server-side validation of build requests. The details are not published here. | The build class named in the request must be a subclass of `ISBuildingObject` (walking the metatable chain, no hard-coded list, so vanilla and mod build classes pass); otherwise the server rejects the request and logs it. | not reported |
| W77 Build distance check ([2cl](docs/patches.en.md#2cl)) | Same as above. | Rejects and logs a build more than 8 tiles from the player on either axis or more than one floor away; admins with the build cheat capability are exempt. | not reported |
| W78 Timed action performer check ([2cm](docs/patches.en.md#2cm)) | A gap in server-side validation of timed actions. The details are not published here. | The action's performer (`character`) must be the player who sent the packet; otherwise the server rejects the request and logs it. Other player arguments (for example the patient when bandaging someone else) are unchanged. | not reported |
| W79 Object sprite update distance check ([2cn](docs/patches.en.md#2cn)) | A gap in server-side validation of object sprite updates. The details are not published here. | A player on the sending connection must be within 20 tiles of the object; otherwise the server neither changes nor forwards it, and logs it. Dismantling, repairing and driving into fences work as before. | not reported |
| W82 Light settings sync check ([2cq](docs/patches.en.md#2cq)) | A gap in server-side validation of light settings sync. The details are not published here. | The server ignores client light-setting fields it should not change: from a client it only accepts removing the bulb (a shot light) and turning the light off, and only when a player on the sending connection is within 50 tiles of the light. Other clients get the server's values, and refused packets are logged. | not reported |
| W29 Animal sync validation ([2aq](docs/patches.en.md#2aq)) | A validation gap in client-to-server animal sync packets. The details will be reported to TIS privately rather than published here. | Validates the packet and drops it before any game state changes. | to be reported privately |
| W19 Vehicle removal ledger ([2ag](docs/patches.en.md#2ag)) | Vehicles can be deleted permanently from several Lua paths without an audit trail. | Logs every permanent removal with caller and ownership (observe only). | not reported |

### Log noise

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| Log-noise suppression ([1](docs/patches.en.md#1), [2v](docs/patches.en.md#2v), [2bf](docs/patches.en.md#2bf)) | Known warnings flood the console (for example `Invalid SpriteConfig object!` for base-game objects); `Send Toxic Building`, triggered by a mod, was 45.5% of all lines. | Drops only exact known messages; anticheat and unknown warnings still print. Packets are never touched. | [#100917](https://theindiestone.com/forums/topic/100917/) (partly fixed in 42.21.0) |

### Observability

Observe-only probes that change no behavior: the main-loop watchdog ([2ac](docs/patches.en.md#2ac)), the
animal offline catch-up probe ([2au](docs/patches.en.md#2au)), the animal death ledger
([2bb](docs/patches.en.md#2bb)), the animal ID miss log ([2bo](docs/patches.en.md#2bo)), the slow
sound-packet probe ([2at](docs/patches.en.md#2at)), the SyncIsoObject flood probe ([2bv](docs/patches.en.md#2bv); vanilla's
`Packets limit has exceeded` warning does not name the connection, so this logs the player and object behind a flood) and the
animal loss probe ([2bx](docs/patches.en.md#2bx); logs when putting an animal down removes a different animal, when a chunk is
reloaded while its unload save is still being written, and when a growing animal is deleted because a copy with the same ID is nearby).
They write rate-limited heartbeat lines to the console and are how most of the issues above were found.

### Client patches (release package)

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| Texture pipeline limit and leak fixes ([2j](docs/patches.en.md#2j)) | Texture-loading threads sleep forever once 50 MB of decoded, not-yet-uploaded buffers pile up. Leaks (APNG frames, 64 MB fallback buffers) push that floor over the limit: players, zombies and vehicles render as a shadow and a name tag. | Fixes the leaks; the standard variant also raises the wait limit to 4 GiB. Invisibility stopped recurring. | [#100919](https://theindiestone.com/forums/topic/100919/) |
| Chunk streaming log ([2o](docs/patches.en.md#2o)) | Black-edge incidents left no evidence on the client. | Logs streaming state and stalls; no behavior change. | — |
| Player-built room XL tree fix ([2bl](docs/patches.en.md#2bl)) | 42.21.0: `IsoTree.isPlayerInsideARoom` reads `getRoom().getRectsBounds()` whenever `isInARoom()` is true, but `isInARoom()` is also true for enclosed player-built rooms that have no `IsoRoom`. The NPE aborts the frame: furniture, trees and fences vanish. | Treats such rooms as "not inside a room" for the XL tree fade. | [#101887](https://theindiestone.com/forums/topic/101887/) (fixed internally by TIS) |
| W54 Skip unchanged vehicle part poses ([2br](docs/patches.en.md#2br)) | Every loaded vehicle calls `AnimationPlayer.Update` for every skinned part model every frame, whatever the distance, whether parked or visible; windows and armor share the door's player, so one skeleton is computed 2–3 times per frame. Mod vehicles with moving parts (KI5, rSemiTruck) cost 22–47 µs per vehicle per frame, vanilla vehicles 3–5 µs. | Skips the Update when no track is playing and the pose inputs match the last full computation; the renderer reuses the previous matrices. One in 256 skips is fully recomputed and compared; any difference reverts to vanilla for the session. Bit-identical poses in the synthetic tests. | not reported |
| W55 `isBoneReparented` fast path ([2bs](docs/patches.en.md#2bs)) | The skeleton update calls `isBoneReparented` once per bone, each time allocating a pooled lambda and scanning a list that is empty for vehicles, zombies and most characters. | Returns false for an empty list, vanilla otherwise; bit-identical results for every character and vehicle. | not reported |
| W57 Player-built room chunk-load disconnect ([2bu](docs/patches.en.md#2bu)) | 42.21.0: a chunk's squares are bound to rooms on the World Streamer thread. If the client rebuilds the player-built buildings of that area (any build or demolition in the same or a neighbouring cell) before the main thread takes the chunk over, the old `IsoRoom` is cleared (`def = null`) and `updateSquares` skips the queued chunk; `IsoLightSwitch.chunkLoaded` then throws and the client is disconnected to the main menu. | When the chunk has squares pointing at a cleared room, runs the game's own `updateSquares` steps on that chunk first (the rebuild vanilla skipped); otherwise vanilla. | [#102213](https://theindiestone.com/forums/topic/102213/) |
| W61 Vehicle teleported into a stale chunk ([2by](docs/patches.en.md#2by)) | 42.21.0: when the client unloads all chunks around a moving vehicle at once (a full chunk-map rebuild), the vehicle keeps pointing at its old chunk object. Once that object is recycled and queued for another location, `BaseVehicle.update` clamps the vehicle into it, checking only that its `refs` is non-empty, so the vehicle is thrown onto a corner of that chunk, tens or hundreds of tiles away. | Keeps vanilla when that chunk is loaded, lists the vehicle and is adjacent; otherwise clamps into the nearest adjacent loaded chunk, or, if none is loaded, leaves the vehicle where it is (vanilla has already stopped its physics) until that area loads. | not reported |
| W62 Vehicle stops updating on an unloaded chunk ([2bz](docs/patches.en.md#2bz)) | 42.21.0: when a chunk unloads under a vehicle with a local passenger, `removeFromWorld` refuses to remove it and it stays assigned to that chunk; `BaseVehicle.update` then calls `removeFromWorld` again and returns every frame, so the vehicle stops sending its position, the engine goes silent and passengers are not placed. | Only in that case, lets `update` take vanilla's recycled-chunk branch instead (request a vehicle update and carry on), so the vehicle keeps updating and moves to the loaded chunk under it once that loads. | not reported |
| W63 Driver pulled back to the server's old position ([2bz](docs/patches.en.md#2bz)) | 42.21.0: `VehiclePacket.doRemove` moves every local player seated in a vehicle to the server's position when that square is not loaded on the client, even when this client simulates the vehicle and the server's position is just a stale copy of its own. | Does not move the driver of a vehicle this client simulates (Local/LocalCollide); vanilla otherwise. | not reported |
| W65 Locally simulated vehicle under the floor ([2ca](docs/patches.en.md#2ca)) | `BaseVehicle.update` derives the level from the physics height but never puts a vehicle that fell under the floor back on it; the seated camera uses the raw physics height, so the whole view goes black and the vehicle stays sunk after the driver gets out. | For a vehicle this client simulates (Local/LocalCollide) with its origin 0.25 m or more below a floor that exists, moves it back to its spawn resting height with vanilla `setWorldTransform`, then runs vanilla `updateTransform`. A flipped vehicle is only handled more than one level below the floor, and is set upright first, keeping its heading (from 0.2.8). | not reported |
| W68 Room tables written by two threads ([2cd](docs/patches.en.md#2cd)) | 42.21.0 (also 42.20.4): while loading a chunk, the World Streamer thread adds rooms to `IsoMetaCell.isoRooms` and `isoBuildings` (plain `HashMap`s) through `IsoMetaGrid.getRoomByID`. When someone builds or demolishes nearby, the main thread rebuilds the player-built buildings and copies, clears and refills the same maps in `recalculateBuildingAndRoomIDs`. Nothing synchronizes the two: the copy gets a `null` (`NPE: "isoRoom" is null`) or one entry too many (`ArrayIndexOutOfBoundsException`), and the client is disconnected to the main menu. | One fair lock around every `getRoomByID` call in the jar and around the rebuild, so the two never touch the maps at the same time; the vanilla methods run unchanged. | [#101035](https://theindiestone.com/forums/topic/101035/) |

### Native guards (Linux dedicated server)

| Guard | What goes wrong in vanilla | What the guard does | TIS |
|---|---|---|---|
| pfguard ([design, Chinese](docs/pathfind-aligned-block-guard-design-v1.md)) | `libPZPathFind64.so`: SIGSEGV in `PolygonalMap2::createVehicleClusters` from a corrupted `VehicleRect` pool slot; `VehicleCluster::merge` never returns merged clusters (about 40 leaked blocks per second). | `LD_PRELOAD` guard pages around the allocator family (fault at the first bad write) and returns merged clusters to the pool. Checks the exact library hash at startup and disarms on mismatch. | crash: [#100921](https://theindiestone.com/forums/topic/100921/); leak: draft |
| steamfix | Valve's `steamclient.so` PseudoTCP: a partial ACK shortens the segment but does not advance its start sequence, so a retransmit reads out of bounds. | Opt-in `LD_AUDIT` fix of that single block in the loaded library (the file on disk is not modified), pinned to the exact Steam build. | Valve library |
| Startup gate ([run-with-pfguard.sh](native-observer/deploy/run-with-pfguard.sh)) | After an automatic game update, stale loose classes stop the server from starting or run silently against the new jar. | Re-checks the payload SHA and jar identity at every start; moves a mismatched patch aside and starts vanilla. | — |

## Open vanilla issues

Vanilla bugs we have diagnosed but not patched. The reports live in [docs/report/](docs/report/) and stay drafts
until posted.

| Issue | What goes wrong in vanilla | What players can do | TIS |
|---|---|---|---|
| Driven vehicle left on an unloaded chunk ([report](docs/report/2026-10-01-vehicle-orphaned-chunk-river-warp-tis.md)) | On the client, a vehicle with a local player aboard cannot be removed when its chunk unloads, and nothing moves it to a loaded chunk. It stops sending its position and stops updating its sounds (the engine "goes silent"). Once the server's stale position is outside the client's loaded area, a vehicle update teleports the driver back to it, and the car can end up in water: the driver cannot get out and the screen stays black. | The client package (0.2.7, W61–W63) keeps such a vehicle updating and stops both teleports. Without it: when the engine sound stops or the car brakes by itself, reverse along the road until the sound returns. If the car is already stuck in water, reconnect a few times (each reconnect moved it a few squares toward the shore) or ask an admin. | draft |
| Disconnect when a chunk with a hen house unloads ([report](docs/report/2026-10-03-hutch-removefromworld-npe-tis.md)) | 42.21.0: `IsoHutch.removeFromWorld` calls `removeFromUpdateLists()` on every value of `animalInside`, but the client's own animal sync puts `null` into that map (for example when a hen moves to a nest box). When the client unloads that chunk (walking or driving away, or a teleport), the NPE reaches the catch in `IngameState.updateInternal`, which disconnects the client to the main menu. Reproduced on a local server. | Reconnect. Our Lua fixes mod clears these empty slots on the client (`MDFX_HutchNullSlotGuard` in [MinidoracatFixesFor42](https://github.com/Minidoracat/MinidoracatFixesFor42), on the Workshop since 42.21.0-0.13.0). | draft |

## Fixed upstream

Patches we retired because TIS fixed the underlying issue:

| Retired patch | Fixed in | Our report |
|---|---|---|
| popman shared buffer race ([2h](docs/patches.en.md#2h)) | 42.20.2 (`readByteBuffer`) | — |
| `IsoCell` list membership sidecar ([2m](docs/patches.en.md#2m)) | 42.20.2 (the high-population chunk unloading fix) | — |
| `VehicleManager` 512 → 256 connection slots ([2k](docs/patches.en.md#2k)) | 42.20.2 | — |
| `WorldStreamer` 8 s request timeout livelock ([2p](docs/patches.en.md#2p)) | 42.20.3 (`ChunkNotReady`) | — |
| Glass removal infinite loop ([2l](docs/patches.en.md#2l)) | 42.21.0 | [#100899](https://theindiestone.com/forums/topic/100899/) |
| `faceThisObject` null dereference (W22, [2ai](docs/patches.en.md#2ai)) | 42.21.0 | [#100909](https://theindiestone.com/forums/topic/100909/) |
| Timed-action Reject written from the wrong object (W10-A, [2x](docs/patches.en.md#2x)) | 42.21.0 | [#100905](https://theindiestone.com/forums/topic/100905/) |
| Shared `CRC32` in the chunk save pipeline (W9, [2u](docs/patches.en.md#2u)) | 42.21.0 | [#100901](https://theindiestone.com/forums/topic/100901/) |
| `IsoThumpable not found` console spam ([1](docs/patches.en.md#1)) | 42.21.0 | [#100917](https://theindiestone.com/forums/topic/100917/) |
| `%ld` in a Java format string (W24, [2al](docs/patches.en.md#2al)) | 42.21.0 | — |
| `RequestData` ACK loop bound (W27, [2ao](docs/patches.en.md#2ao)) | 42.21.0 | — |
| PopMan missing-cell generation vs background save (W28, [2ap](docs/patches.en.md#2ap)) | 42.21.0 | — |
| `AnimationSet` / `ActionStateContainer` log noise ([1](docs/patches.en.md#1)) | 42.20 / 42.21.0 | — |

## How the patches work

The dedicated server's classpath puts `java/.` before `java/projectzomboid.jar`, so a loose `.class` file
overrides the class in the jar. The patcher reads the original class from the jar with ASM and changes it in
place; nothing is decompiled or recompiled.

- **Shape-preserving surgery only**: call redirects to a static helper with the same stack effect and
  instruction length, in-method constant changes, and linear head/tail calls. Stack map frames are kept
  (`ClassWriter(0)`). Helpers are ordinary Java classes compiled against the game jar.
- **Per-method hit counts**: every patch site declares how many instructions it must match. After a game
  update a moved or changed site fails the build instead of patching the wrong place.
- **Vanilla premise checks**: `SmokeCheck` pins the structural facts each fix depends on (for example "this
  call is not null-checked"). When TIS fixes a bug, its check turns red and tells us to retire the patch.
- **Verification**: `LoadCheck` loads every class with `-Xverify:all`, `BytecodeVerify` runs ASM's data-flow
  verifier, and behavior tests run the real game classes in a bare JVM.
- **Kill switches**: every server patch reads a `-Dmdc.*` property (`0`/`off` restores vanilla; many offer an
  `observe` mode that measures without changing behavior).
- **Fail-closed install**: payload SHA, jar identity and a scan for unknown loose classes must all pass. A
  version banner (`server patch <commit> … jar=<sha>`) prints at startup.

## Server: build and deploy

Requirements: JDK 25 (the 42.21 jar is class file version 69) and the server's `projectzomboid.jar`.

```powershell
copy <serverfiles>\java\projectzomboid.jar work\
.\build.ps1        # patch → hit counts → linkage, bytecode and behavior checks → dist/
wsl bash patcher/tests/install-roundtrip.sh   # two install/uninstall rounds in a temporary serverfiles
```

```bash
# copy dist/ to the server, then in serverfiles/java:
bash install.sh    # payload SHA, jar identity and conflict gates; takes effect at the next restart
bash uninstall.sh  # restores vanilla at the next restart
```

**Before every game update, run `uninstall.sh`.** Loose classes are not part of the Steam depot, so an update
replaces the jar but leaves the old patched classes in place. Rebuild against the new jar, check every patch
site's context with `javap` (a matching hit count does not prove the site means the same thing), then install
again. Build-time checks and the deployment checklist are described in
[docs/patches.en.md §3](docs/patches.en.md#3).

## Client patches: install

For players. Download `MinidoracatClientPatches-<game version>-<package version>.zip` from
[Releases](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases) and check it against `SHA256SUMS.txt`
(`Get-FileHash .\MinidoracatClientPatches-*.zip`).

1. Close the game and extract the whole zip into any folder: your Desktop or Downloads is fine, it does not need
   to be in the game folder. The installer finds the game through Steam by itself, even on another drive, and
   asks you to paste the path only if it cannot (`-GameDir "<path>"` also works).
2. Run `Install-Patches.bat`, choose `1` (install or update), then `1` (client fixes).
3. Press Enter to accept the recommended variant (standard for 32 GB RAM or more, low-memory otherwise, compatible
   when another mod already replaces the game classes the full variants patch), then `Y` to confirm.

The installer follows the Windows display language (Traditional Chinese or English); press `L` in the main menu
or run `Install-Patches.bat -Lang en` to switch. It only works on the exact game version it was built for: it
checks the SHA-256 of `projectzomboid.jar` and of every file it writes, never touches files it does not own,
and rolls back an interrupted install. **Run `Uninstall-Patches.bat` (choose `A`) before every game update**, so
keep the extracted folder (or download the same zip again when you need it). If you forgot and the game no
longer starts after an update, close it and run `Uninstall-Patches.bat` (choose `A`). It also recognizes the old
TexPipeline v3.0 package (the one that only came with `uninstall.bat`), so installing the new package or removing
cleans it up.

| Module | Purpose |
|---|---|
| `core` | Shared Lua bridge, installation fingerprint check and startup status; installed automatically |
| `client-fixes-standard` | Texture leak fixes, 4 GiB texture wait limit, chunk streaming log, XL tree room fix, player-built room disconnect fix, vehicle animation speed-ups, vehicle teleport fixes (32 GB RAM or more) |
| `client-fixes-lowmem` | The same fixes with the vanilla 50 MiB texture limit; excludes the other variants |
| `client-fixes-compat` | Only the XL tree room fix and the vehicle fixes, for use next to mods that replace the other classes (for example PZ_Optimization); excludes the other variants |
| `profiler` | DevProfiler for mod developers (Java → Lua call timing, CSV/JFR export); its UI is the separate mod `MinidoracatDevProfilerFor42` |

**Mods that replace game classes.** Some mods, such as PZ_Optimization, also put `.class` files into the game
folder. If one of them already replaces a class this package patches, the installer stops with code 7 and changes
nothing, because only one copy of a class can load. Do not delete just the files it lists: the rest of that mod
still needs them, and the game closes when you join a server
([issue #1](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/issues/1)). Either keep that mod and install
`client-fixes-compat` (the installer recommends it), or remove that mod with its own uninstaller (PZ_Optimization:
`Uninstall-PZ-Optimization.cmd` in the game folder) and install a full variant. That uninstaller deletes by path, so
run this installer again afterwards if these patches were already installed. With PZ_Optimization, turn off its
"Warm-start cache (Java AOT)" option before installing or removing: while it is on, the game loads classes from that
cache instead of the game folder, and the installer refuses. The option takes effect at the next start, so after
turning it off, start the game once more to the main menu and close it. You can turn it back on afterwards; turn it
off again before the next install, update or removal.

Build the package yourself with `build-client.ps1`; installer tests:
`powershell -NoProfile -ExecutionPolicy Bypass -File patcher/tests-client-installer/Run-InstallerTests.ps1`
(do not run them while a Project Zomboid JVM is running).

## Recommended server settings

Not patches, but part of the same work on a busy server:

| Setting | Change | Why |
|---|---|---|
| `PingLimit` | 400 → 800 | Ping kicks during login and reconnect storms dropped from 18 to 5 per day |
| `SaveWorldEveryMinutes` | 15 → 60 | Each full save pauses clients for 5–6 s on a busy server |
| `BackupsPeriod` | 30 → 120 | The built-in backup spends ~25 s compressing |

## Optional tools

- **Main-loop health check** (`scripts/pz-health-watch.py`, Linux/LinuxGSM): asks the local RCON for
  `players` to prove the main loop responds, and restarts only after three consecutive failures: stop,
  wait until the old JVM has exited (SIGKILL after 300 s), then start. Test:
  `python3 scripts/test_pz_health_watch.py`.
- **Native snapshot** (`scripts/native_snapshot.py`): hashes and symbol-diffs the server's native libraries
  after each game update, since TIS updates them independently of the jar.

## Repository layout

```text
build.ps1 / build-client.ps1   server build / client package build
patcher/src/                   ASM patcher, PatchConfig (every patch site), SmokeCheck, LoadCheck, BytecodeVerify
patcher/game/                  server runtime helpers
patcher/game-client*/          client fix helpers and the shared client core
patcher/game-profiler/         DevProfiler runtime
patcher/tests*/                behavior tests (server, client, installer, profiler)
deploy/                        server install.sh / uninstall.sh and gate tests
deploy-client/                 client installer (Manage-Patches.ps1 and .bat launchers)
native-observer/               LD_PRELOAD / LD_AUDIT native guards, startup wrapper, tests
scripts/                       optional tools listed above
docs/                          detailed patch documentation, design notes, reports to TIS
lib/                           ASM 9.8 (BSD-3-Clause, see lib/LICENSE-ASM.txt)
```

## Documentation

| Document | Language | Content |
|---|---|---|
| [docs/patches.en.md](docs/patches.en.md) | English | Every patch: incident, root cause with bytecode evidence, fix, safety argument, checks, kill switch, measured results |
| [docs/patches.md](docs/patches.md) | 繁體中文 | The original, most detailed version of the above |
| [docs/report/](docs/report/) | English (drafts) | Bug reports written for the TIS forum |
| [docs/optimization-summary.md](docs/optimization-summary.md) | 繁體中文 | Operations overview and timeline |
| `docs/*-design-*.md` | 繁體中文 | Design notes for the larger patches |
| [docs/specs/](docs/specs/) | JSON | `javap` evidence for the early patches |

## Support

The mods are free. If you want to support the server and mod development:

[![Ko-fi](https://raw.githubusercontent.com/Minidoracat/workshop-resources/refs/heads/main/badges/badge_kofi.png)](https://ko-fi.com/minidoracat)

## License and disclaimer

MIT, see [LICENSE](LICENSE).

Third-party: the build uses OW2 ASM 9.8 (BSD-3-Clause), redistributed in `lib/` with its notice in
[lib/LICENSE-ASM.txt](lib/LICENSE-ASM.txt). ASM is not included in any release package.

- This is an **unofficial** project with no affiliation with, or endorsement by, The Indie Stone. Project
  Zomboid and its assets are copyright The Indie Stone.
- The repository contains no game files. **Client release packages contain modified copies of a few classes
  from `projectzomboid.jar`**, produced by the patcher in this repository from the unmodified official jar of
  the stated game version. They only work with a legitimately owned copy of that exact version, and the
  installer refuses any other. If The Indie Stone asks us to stop distributing them, we will.
- Modifying game files is at your own risk. `bash uninstall.sh` (server) and `Uninstall-Patches.bat` (client)
  remove the patches; back up your server before installing.
- Rebuild and re-verify after every Project Zomboid update. Never apply an old patch to a new game version.
