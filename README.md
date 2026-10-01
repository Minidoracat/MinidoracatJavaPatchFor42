# MinidoracatJavaPatchFor42

**English** | [繁體中文](README.zh-TW.md)

Bytecode patches for **Project Zomboid Build 42** (currently **42.21.0**) that fix vanilla bugs and remove
hot spots we found while running a busy multiplayer server, plus an optional client package for players.

- **Server patches**: loose `.class` overrides for the dedicated server. They address main-loop freezes
  and livelocks, lost chunks, animals and vehicles, main-thread hot spots, bandwidth loops, stuck timed
  actions and log noise. 58 patched classes, 162 patch sites and 59 helper classes on 42.21.0.
- **Client patches**: an optional package for players (invisible players/zombies/vehicles, the 42.21.0
  "player-built room" rendering bug). Download it from [Releases](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases).
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
| W37 Half-constructed animals ([2az](docs/patches.en.md#2az)) | When the checks in an `IsoAnimal` constructor fail, the object is already in the cell with null `adef`/`data`; every tick throws (game time froze for about 65 minutes). Saving opens `apop_*.bin` before serializing, so a failure leaves a 0-byte file and the animals are lost. | Withdraws failed objects before the constructor returns; serializes first and writes the file only on success. | draft |
| W40 / W41 `processItems` ([2bc](docs/patches.en.md#2bc), [2bd](docs/patches.en.md#2bd)) | The vehicle loader thread writes `IsoCell.processItems` directly. A null gets in, `ProcessItems` throws every 5 s, the list stops shrinking and linear `contains` during chunk loading froze the server for 5–16 s (FPS 9.8 → 2–3). | W40 skips and removes nulls in the same frame; W41 queues off-thread registrations and applies them on the main thread. | draft |
| Hit packet null guards ([2c](docs/patches.en.md#2c)) | `hit/Zombie.process` and `hit/Fall.process` dereference fields that a malformed packet leaves null. | Null guard before the super call. | not reported |
| W15 Main-loop watchdog ([2ac](docs/patches.en.md#2ac)) | Freezes leave no stack trace. | Observe only: snapshots the main thread after 5 s without a frame. It showed that the remaining freezes are the synchronous `QueuedSaveAll`. | suggestion [#100925](https://theindiestone.com/forums/topic/100925/) |

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
| W33 Birth breed guard ([2av](docs/patches.en.md#2av)) | A birth whose breed or baby definition cannot be resolved still reaches the `IsoAnimal` constructor. | Skips that birth and logs it. | not reported |
| W43 / W44 Ground-item expiry and failed transactions ([2bg](docs/patches.en.md#2bg)) | Clients drop expired ground items on every chunk load, the server never does while the chunk stays loaded. Item indexes diverge and "ghost" items cannot be picked up; the failed pickup is never reported, so the progress bar waits its duration plus 10 s. | The server removes expired ground items with the same rule before sending the chunk; failed transactions are rejected at once. | not reported |

### Performance: main-thread time

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| Entity removal index ([2g](docs/patches.en.md#2g)) | Batch chunk unloads run an identity linear search over a global array for every entity. | Weak-key plus primitive sidecar index: 439 → 63 ns per entity. | not reported |
| W1-1 Vehicle LOS prefilter ([2k](docs/patches.en.md#2k)) | `IsoZombie.isVehicleBetween` runs a full OBB intersection against every vehicle in the cell for every zombie. | A conservative bounding-sphere test first; 99.87% of checks rejected, vehicle checks vanished from low-FPS dumps. | not reported |
| W3 wave ([design, Chinese](docs/wave3-design-v1.md)) | Owned zombies redo the ownership election every tick (O(connections × players)); animals call `spotted()` on every zombie in the cell although its effects need 10 tiles; vehicles compute player visibility on the server only to feed a no-op. | Re-elect every 3 passes; skip far `spotted()` calls (99.94% intercepted); short-circuit the dead work. | not reported |
| W18 / W18-2 Animal LOS throttle ([2af](docs/patches.en.md#2af)) | `IsoAnimal.updateLOS` walks the whole object list every tick; it was 41.7% of main-thread samples at 67 players. | Round-robin throttle (configurable N) and a cheaper scan of the same candidates with identical results. | not reported |
| W47 Animal LOS spatial prefilter ([2bj](docs/patches.en.md#2bj)) | After W18-2 every LOS check still walks ~4,300 objects: ~16 ms per frame (~9% of the main loop) at ~50 players. | Per-frame zombie grid; results identical to vanilla (4,000 randomized worlds diffed); benchmark 4,780 → ~250 µs per frame. | not reported |
| W48-2 Animal hearing index ([2bk](docs/patches.en.md#2bk)) | Every animal scans the global sound list (~4,300 sounds, ~12 in range) every tick. | Spatial index with identical results (audited every 256 calls; 130k audits, zero mismatches): 4.34 → 0.69 ms per frame. | not reported |
| W45 `processItems` identity index ([2bh](docs/patches.en.md#2bh)) | Every item registration runs a linear `contains` over a ~30k-item list. | Identity-indexed list with the same order and contents; 43 → 0.3 µs per registration (benchmark). | draft |
| W25 Serialization pool isolation ([2am](docs/patches.en.md#2am)) | `SaveAll` workers spend 80% of their samples contending on the `ConcurrentLinkedDeque` pools in `BitHeader` and `ByteBlock`. | Per-thread pools: 1,095 → 38 ns per iteration with 4 threads (29×). | not reported |
| W35 Using-player index ([2ax](docs/patches.en.md#2ax)) | `UsingPlayerUpdateSystem` scans every IsoObject entity each frame (5.9% in JFR) only to clear `usingPlayer` when a player walks away. | Tracks the objects that have a `usingPlayer`, with periodic audits. | not reported |
| W34 Emitter parameters ([2aw](docs/patches.en.md#2aw)) | The server computes FMOD footstep parameters for a dummy emitter that nothing reads (1.6%). | Skips them on the server. | not reported |
| W4-1 Chunk request packing ([2p](docs/patches.en.md#2p)) | Chunk supply per player is capped at main-loop FPS × one row of chunks; at 3 FPS a car outruns it (black edges). | Packs queued requests into larger batches (observe mode by default). | not reported |

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
| LootRespawn fixed containers ([2e](docs/patches.en.md#2e)) | On custom maps without a vanilla TownZone, original fixed containers never respawn loot. | Narrow fallback for original, unmoved containers only. | design issue |
| Animal stress tuning ([2b](docs/patches.en.md#2b)) | Gameplay tuning, not a bug. | Idle stress decay ×2, sound stress ÷3, slaughter chain cap halved. | — |

### Accounts and abuse

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| W23 Account limit per Steam ID ([2ak](docs/patches.en.md#2ak)) | `MaxAccountsPerUser` is only checked when a new account is created, so existing accounts are never limited. | Enforces the limit at login, keeping the most recently used accounts. Server policy. | not reported |
| W29 Animal sync validation ([2aq](docs/patches.en.md#2aq)) | A validation gap in client-to-server animal sync packets. The details will be reported to TIS privately rather than published here. | Validates the packet and drops it before any game state changes. | to be reported privately |
| W19 Vehicle removal ledger ([2ag](docs/patches.en.md#2ag)) | Vehicles can be deleted permanently from several Lua paths without an audit trail. | Logs every permanent removal with caller and ownership (observe only). | not reported |

### Log noise

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| Log-noise suppression ([1](docs/patches.en.md#1), [2v](docs/patches.en.md#2v), [2bf](docs/patches.en.md#2bf)) | Known warnings flood the console (for example `Invalid SpriteConfig object!` for base-game objects); `Send Toxic Building`, triggered by a mod, was 45.5% of all lines. | Drops only exact known messages; anticheat and unknown warnings still print. Packets are never touched. | [#100917](https://theindiestone.com/forums/topic/100917/) (partly fixed in 42.21.0) |

### Observability

Observe-only probes that change no behavior: the main-loop watchdog ([2ac](docs/patches.en.md#2ac)), the
animal offline catch-up probe ([2au](docs/patches.en.md#2au)), the animal death ledger
([2bb](docs/patches.en.md#2bb)) and the slow sound-packet probe ([2at](docs/patches.en.md#2at)).
They write rate-limited heartbeat lines to the console and are how most of the issues above were found.

### Client patches (release package)

| Patch | What goes wrong in vanilla | What the patch does | TIS |
|---|---|---|---|
| Texture pipeline limit and leak fixes ([2j](docs/patches.en.md#2j)) | Texture-loading threads sleep forever once 50 MB of decoded, not-yet-uploaded buffers pile up. Leaks (APNG frames, 64 MB fallback buffers) push that floor over the limit: players, zombies and vehicles render as a shadow and a name tag. | Fixes the leaks; the standard variant also raises the wait limit to 4 GiB. Invisibility stopped recurring. | [#100919](https://theindiestone.com/forums/topic/100919/) |
| Chunk streaming log ([2o](docs/patches.en.md#2o)) | Black-edge incidents left no evidence on the client. | Logs streaming state and stalls; no behavior change. | — |
| Player-built room XL tree fix ([2bl](docs/patches.en.md#2bl)) | 42.21.0: `IsoTree.isPlayerInsideARoom` reads `getRoom().getRectsBounds()` whenever `isInARoom()` is true, but `isInARoom()` is also true for enclosed player-built rooms that have no `IsoRoom`. The NPE aborts the frame: furniture, trees and fences vanish. | Treats such rooms as "not inside a room" for the XL tree fade. | [#101887](https://theindiestone.com/forums/topic/101887/) (fixed internally by TIS) |

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
| Driven vehicle left on an unloaded chunk ([report](docs/report/2026-10-01-vehicle-orphaned-chunk-river-warp-tis.md)) | On the client, a vehicle with a local player aboard cannot be removed when its chunk unloads, and nothing moves it to a loaded chunk. It stops sending its position and stops updating its sounds (the engine "goes silent"). Once the server's stale position is outside the client's loaded area, a vehicle update teleports the driver back to it, and the car can end up in water: the driver cannot get out and the screen stays black. | When the engine sound stops or the car brakes by itself, reverse along the road until the sound returns. If the car is already stuck in water, reconnect a few times (each reconnect moved it a few squares toward the shore) or ask an admin. | draft |

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
3. Press Enter to accept the recommended variant (standard for 32 GB RAM or more, low-memory otherwise), then
   `Y` to confirm.

The installer follows the Windows display language (Traditional Chinese or English); press `L` in the main menu
or run `Install-Patches.bat -Lang en` to switch. It only works on the exact game version it was built for: it
checks the SHA-256 of `projectzomboid.jar` and of every file it writes, never touches files it does not own,
and rolls back an interrupted install. **Run `Uninstall-Patches.bat` (choose `A`) before every game update**, so
keep the extracted folder (or download the same zip again when you need it).

| Module | Purpose |
|---|---|
| `core` | Shared Lua bridge, installation fingerprint check and startup status; installed automatically |
| `client-fixes-standard` | Texture leak fixes, 4 GiB texture wait limit, chunk streaming log, XL tree room fix (32 GB RAM or more) |
| `client-fixes-lowmem` | The same fixes with the vanilla 50 MiB texture limit; excludes the standard variant |
| `profiler` | DevProfiler for mod developers (Java → Lua call timing, CSV/JFR export); its UI is the separate mod `MinidoracatDevProfilerFor42` |

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
  `players` to prove the main loop responds, and restarts only after three consecutive failures. Test:
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
