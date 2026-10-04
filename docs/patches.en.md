# Patch details (English)

English edition of [docs/patches.md](patches.md), the detailed per-patch documentation of this repository.
The Traditional Chinese original is the authoritative and more detailed version. This edition condenses
historical notes and review rounds but keeps the root causes, bytecode evidence, fixes, build-time checks,
kill switches and measured results. Section numbers match the original, so `patches.md#2x` and
`patches.en.md#2x` describe the same patch.

Conventions:
- **W-numbers** (W5, W10, …) are our internal patch IDs, roughly in the order the patches were written.
- **javap offsets** refer to the shipped `projectzomboid.jar` of the game version named in each section.
- **Kill switches** are JVM system properties (`-Dmdc.<name>=0|off`) read at startup; `observe` modes measure
  without changing behavior.
- **Retired** sections are kept short: what the patch did and why it was removed (usually because TIS fixed
  the underlying issue).
- Players are referred to by codes (Player-A, Player-B, …).

The categorized catalog with report status is in the [README](../README.md#patch-catalog).

<a id="0"></a>
## 0. Core mechanism: why "installed = active" (server and client)

The PZ launch classpath lists `java/.` before `java/projectzomboid.jar`, so the JVM resolves loose
`.class` files before jar entries. Dropping a modified class at its original package path under
`java/` makes **our version replace vanilla on the next restart** without touching the jar;
deleting the loose file fully reverts it.

Patches are not recompiled decompiler output (that path is full of decompiler artifacts). They are
**bytecode patches**: ASM reads the vanilla class from the jar and applies only
**shape-preserving** edits (same stack effect and instruction length). The original
StackMapFrames and max stack are kept as-is, so the verifier sees a structure isomorphic to vanilla:

1. **Redirect**: an instruction such as `INVOKEVIRTUAL DebugType.warn(...)` is replaced in place
   with `INVOKESTATIC zombie/mdc/LogFilter.warnFmt(...)` (the receiver becomes the first argument;
   net stack effect is identical). Filter logic lives in `LogFilter.java`, plain Java compiled
   with javac against the game jar and shipped with the patch. **It only swallows known noise
   (exact literal `equals` or `startsWith` prefix); everything else is forwarded to the original
   call. When in doubt, let it through.**
2. **Const-change**: inside one named method only, an `LDC`/`BIPUSH` constant is changed (the new
   value gets a new constant-pool entry; the original entry shared by other methods is untouched).

Some later patches add two more linear, branch-free shapes: **head call** (a static call inserted at
method entry) and **tail call** (a static call inserted before each `RETURN`); each section states its shape.

### Safety layers (each one tested)

| Layer | What it catches |
|---|---|
| Per-method hit-count check | PZ update adds/removes a call site or renames a method → build fails; no misaligned patch is produced |
| LoadCheck link verification + helper signature assertions | Redirect target missing or signature mismatch |
| CheckClassAdapter JVMS data-flow verification | Any stack/frame error (verifier-level, run offline first) |
| `install.sh` three gates | Corrupted artifact (payload SHA), game already updated (vanilla jar SHA differs → refuse), file conflicts |
| `uninstall.sh` | Exact removal from the manifest; one-step rollback |

---

<a id="1"></a>
## 1. Log-noise suppression (server; 8 active: #2–#8 in the table + #10 in §2bf; #1 and #9 retired in 42.21.0)

On the production server (78 maps, multiplayer), `console.txt` received dozens to hundreds of
meaningless warnings per minute: (a) real errors were buried (OOM and other incident diagnosis got
harder); (b) log I/O and file growth are real costs; (c) DebugLog file writes carry
synchronization cost on hot call paths.

| # | Location | Suppressed message | Trigger | What is preserved |
|---|---|---|---|---|
| 1 | ~~AnimationSet.GetState~~ (**retired in 42.21.0**) | `AnimState not found: X` | Mod animation set lacks a state; engine already returns an empty fallback | 42.21 downgraded this line from `warn` to `trace` (below the server's default Warning threshold); the redirect site no longer exists |
| 2 | SkinningBoneHierarchy.buildBoneHierarchy | `SkeletonBone not resolved for bone: X` | Mod model skeletons use non-standard bone names; floods at boot | Skeleton build result unchanged |
| 3 | SpriteConfig.initObjectInfo | `Invalid SpriteConfig object!` for an **exact allow-list of 37 names** (initial: MetalBigWireFence / WoodFloorLvl3 / Wooden_Windows; 42.20 added DoubleWireGate / BrickWallLvl2 / MetalSmallWireFence / BrickWindowFrameLvl2 / Piano / WoodenWallLvl3; 42.20.3 added SandFloor / WoodenDarkWallLvl3 / GravelFloor / Floor_SpringGrass / DoubleDoor / WoodenWindowFrameLvl3 / WoodFloorLvl2 / Wood_DoubleDoorDark / WoodDoorFrameLvl3 / Fences_MetalFarmGate; 42.20.4 added Commercial_FullGlassBlackWall / Commercial_GridGlassBlackWall / Commercial_HalfGlassRedWall / Commercial_FullGlassRedWall / Commercial_HalfGlassBlackWall / Commercial_GridGlassRedWall / MetalFloorLvl1 / Wood_Crate_Lvl2 / Floor_SummerGrass / WoodFloorLvl1 / WoodenDarkDoorFrameLvl3 / Composter / BrickFloorLvl1 / Floor_SummerGrassCorner / ComposterShoddy / DoubleFenceGate / WoodenDarkWindowFrameLvl3 / Floor_Concrete, see §2bf). Full-message `equals`; admission threshold ≥4 lines/h; list behavior pinned by `LogFilterNoiseTest` | Fires on every load of these objects (the 42.20 six: 1,183 lines in 5.5 h; the 42.20.3 ten: 23,517 lines in 26 h, then the largest remaining noise source; 17 low-volume names at ≤86 lines deliberately left out). All 37 definitions come from a building mod's entity overrides (22 share vanilla names, 15 are mod-only), so retirement follows that mod's updates, not PZ versions (42.21.0 audit: trigger code bit-identical, no name removable) | **Any other name (including null and low-volume names) still warns**; `resetObjectInfo` cleanup still runs |
| 4 | ItemPickInfo.GetPickInfo | Prefix `ItemPickInfo -> cannot get ID for ` (container/room/tile/zone variants) | Mod maps with custom containers/rooms not registered in ItemConfigurator; fires on every loot roll and is **not gated by debug mode** | The 4 debug-mode diagnostics use different prefixes and still print; loot fallback unchanged |
| 5 | NetworkZombieManager.moveZombie | `moveZombie: There are no zombies in nz.zombies.` (full-string `equals`) | Zombie ownership-transfer race; routine in MP | Ownership transfer logic unchanged |
| 6 | PacketsCache.\<init\> | Prefix `No packet handler for type:` | Several vanilla PacketTypes are handled by a built-in switch instead of a handler class, so **every player connection prints a long burst** | `printException` (real errors) and `Packets limit has exceeded` (real rate limiting) untouched |
| 7 | INetworkPacket.logInconsistentPacket | Format constant `The packet %s is not consistent: %s` (`equals`) | Routine desync message for vehicle packets | **The `sync` self-repair still runs**; the anti-cheat `The packet %s is not valid` stays in `onServerPacket` and **never passes through our code** |
| 8 | GameServer.sendToxicBuilding | `Send Toxic Building at [ ... , ... Toxic: ... ]` (`startsWith` prefix) | The mod PSR (Plysken Solar Revolution) periodically calls `IsoBuilding.setToxic`, randomly refreshing building toxicity, broadcast to all players | **Only the log line is dropped; the broadcast packet is untouched**; clients mark generator-in-building themselves |
| 9 | ~~IsoObject.syncIsoObject~~ (**retired in 42.21.0**) | `ERROR: IsoThumpable not found on square x,y,z` (`System.out.println`, invokedynamic-built → `startsWith` prefix) | B42 construction calls `setHealth` before the IsoThumpable is added to the square → server `sync()` → `getObjectIndex()==-1` always prints; 11,567 lines in 4 days on production (≈120/h ≫ 4/h threshold) | 42.21 changed `IsoThumpable.setHealth` to sync only when `server && getObjectIndex() != -1`, removing the source; remaining not-found lines are a low-frequency breakage signal and print as in vanilla |

> **42.20 changes**: suppression for `ActionStateContainer.tryInsertChildState` was removed — TIS
> downgraded those two `DebugType.warn` calls to `trace` (class-wide warn 8→6, trace 1→3). The #7
> consistency log moved from `PacketTypes$PacketType.onServerPacket` to
> `INetworkPacket.logInconsistentPacket` (interface default method, same text) and the redirect
> moved with it; the `PlayerHitZombiePacket` override only adds a pre-filter and still calls
> super, so both paths are covered.

> **42.21.0 changes (2026-09-28)**: #1 `AnimationSet.GetState` was downgraded to `trace` by TIS
> (javap 42.21 offset 37 `invokevirtual DebugType.trace`); #9's noise source was removed by the
> `getObjectIndex() != -1` guard in `IsoThumpable.setHealth` (javap 42.21 offsets 21–25). Both are
> retired; `LogFilter`'s first `FMT_EXACT` entry and `PRINTLN_PREFIX` / `println` /
> `suppressesPrintln` were deleted. The audit confirmed the trigger code and frequency premise of
> the other 7 (+ #10 in §2bf) still hold. Revive with
> `git checkout 8d2bee8 -- patcher/src/PatchConfig.java patcher/game/zombie/mdc/LogFilter.java`.

Trade-off: these messages disappear from the log. To diagnose exactly the problem one of them
describes, run `uninstall.sh` first and observe vanilla. Each spec's `verification` section lists
positive and negative checks.

---

<a id="2"></a>
## 2. Behavioral patches — what changes and why it is safe

<a id="2a"></a>
### 2a. Zombie over-cap culling speed-up `10 → 6` (retired: removed in 42.20, not restored after re-analysis)

The patch changed the culling sample chance so over-cap zombies far from players were deleted
faster. TIS rewrote the class in 42.20 (`prepareZombiesForDeletion()` driven from
`MovingObjectUpdateScheduler.startFrame`): scanning moved from all loaded zombies to each
connection's `zombiesToSend` (only zombies whose owner is non-null **and not this connection**,
filtered by `RelevantTo`), the threshold became per-connection with a per-frame delete quota, view
protection only checks that connection's players, and the safe radius doubled (`(range-2)*10`).
**Ownerless zombies never enter that list**, yet they were the memory-pressure source the patch
targeted, so the constant no longer reaches the original goal. Constant semantics for reference:
`AdjustForFramerate` on the server is `(int)(chance * 0.33333334f)` → 10 = 1/3, 6 = 1/2, 5 = 100%.
Vanilla side note: `canBeDeletedUnnoticed` ignores other connections' players, so a player on
connection B may see a zombie "unseen" by connection A vanish; not speeding it up avoids amplifying that.

<a id="2b"></a>
### 2b. Animal stress model: three constant tweaks (server, `IsoAnimal`)

**Background**: `stressLevel` (0–100) moves only through `changeStress` (with gene
amplification and clamping). MP has a structural "fast in, slow out" problem: dense gunfire/shouts
(each sound +radius/20; gunshot radius 70–150 → +3.5–7.5 per shot), routine slaughter adds
+Rand(10,30) to every animal in the pen, and the only natural decay is idle `-multiplier/5500`.
Animals sit in the high-stress range: ≥80 starts thumping (breaking) fences — the most painful MP
loss — and >40 sharply raises lure failure.

| Patch | Value | Effect |
|---|---|---|
| `updateStress` idle decay divisor | `5500 → 2750` | 2× recovery with no stressor (the only natural outflow) |
| `respondToSound` sound-stress factor | `0.05f → 1/60f` | Per-sound stress ÷3 (main spike source) |
| `killed` slaughter cascade cap | `Rand(10,30) → Rand(10,15)` | Mean 20 → 12.5; herd-panic semantics kept |

**Why safe**: only magnitudes of existing constants change — no new paths, no instructions added
or removed. The `[0,100]` clamp, gene/defect amplification, flee behavior, and the anti-abuse path
(direct high-stress write on attack) are untouched. Only instructions in the named methods point to
new constant-pool entries; other methods verified unchanged. The server is authoritative and syncs
values to clients, so **server-only install is enough**.

> **42.20 trap**: TIS rewrote `changeStress(sound.radius / 20.0F)` as
> `changeStress(sound.radius * 0.05F)` and added `fleeDistance = sound.radius * 3.0F + 20.0F` in
> the wild branch. `respondToSound` still has **exactly one `20.0f`**, so the old
> `ConstChange(20.0f, 60.0f)` would pass the hit-count check (1 == 1) while changing flee distance
> to `radius*3+60` and not touching stress. **Hit counts check quantity, not context**; every
> update must re-confirm const-change sites with `javap` (the correct site is near offset 347:
> `ldc 0.05f; fmul; invokevirtual changeStress:(F)V`).

**Rejected on purpose**: PacketsCache packet-rate constants (use ini `MaxPacketsPerSecond`);
held-animal (`heldBy`) calming rate (already an active tool); culling safe radius (players would
see zombies vanish); early-return in `updateStress` (would also kill decay).

---

<a id="2c"></a>
## 2c. Crash-prevention head guards (server, 2 sites)

**Root cause**: MP hit packets resolve the target lazily from a CharacterID. A stale or
type-confused reference makes `getZombie()` (= `tryCastTo`, may return null) or the incoming
`character` null, and the vanilla setter chain has no check → NPE. The patch inserts a 4-instruction
null guard at method entry (`aload; [invokevirtual]; ifnonnull L; return; L:[F_SAME]`); stack peak 1,
locals unchanged, original frames kept.

| Location | Guard | Ordering |
|---|---|---|
| `hit/Zombie.process()V` | `getZombie()==null → return` | **Before `super.process()`** — otherwise a null character NPEs in the parent first, and type confusion writes zombie state into the wrong character |
| `hit/Fall.process(IsoGameCharacter)V` | `character==null → return` | Defense in depth only: the packet pipeline still uses the target later; no end-to-end crash-proof claim |

**Verification (build step 6)**: behavior smoke with negative control (vanilla must throw NPE,
patched must return quietly — proves guard placement) plus ASM structure assertions (guard first,
`super` called exactly once, the 9 setters unchanged).

**Future (not done)**: a more fundamental fix is `Zombie.isConsistent()` checking
`getZombie()!=null` (it currently checks ID existence, not type); `hit/Player` has a symmetric risk.

---

<a id="2d"></a>
## 2d. Safehouse room/building binding repair (retired: 2026-09-28; disabled since 2026-07-29)

After adding large custom `Map=` entries in B42.19, safehouse claims failed with
`SafehouseClaimPacket.isConsistent > building not found`: lotheaders had valid `RoomDef`/`BuildingDef`,
but `IsoGridSquare.getBuilding()` returned null at runtime. The patch redirected `getBuilding()`
in `isConsistent` and `SafeHouse.canBeSafehouse` in `processServer` to helpers that, only when the
building was null, rescanned the current and 8 neighboring metacells' `roomList` using vanilla
`IsoMetaChunk.getRoomAt` rules and re-bound the room ID; all vanilla eligibility checks were kept.
It was disabled once production returned to vanilla maps; the 42.21 port deleted the leftover
helpers, LoadCheck and SmokeCheck entries. 42.21.0 did not change `SafehouseClaimPacket` or room
binding, so whether the root cause remains cannot be determined from code. Revive:
`git checkout 8d2bee8 -- patcher/game/zombie/mdc/LogFilter.java` and re-verify.

---

<a id="2e"></a>
## 2e. Periodic loot respawn for native fixed containers (server)

**Root cause**: B42.19 `LootRespawn.respawnInChunk` gates each `(x,y)` column on the ground
square's Zone; only the exact names `TownZone`, `TownZones`, `TrailerPark` are scanned. A hospital
on a custom map had only `Region`/`FarmLand` zones, so its native medicine cabinets never respawned.
Separately, inside a `TownZone`, any player construction or furniture move can permanently set the
whole Zone's `haveConstruction=true`; vanilla has no path to clear it, so every fixed container in
that Zone is blocked from then on.

**Patch**: two redirects in `LootRespawn.respawnInChunk` only; the periodic marker, loot tables and
other gates are untouched.

1. `IsoGridSquare.getZone()` → `getLootRespawnZone`. If the vanilla Zone already qualifies and has
   no construction, it is returned as-is (zero behavior change). Otherwise the helper scans the same
   column in the chunk and returns a qualifying Zone, used for this gate only, if it finds an object
   that is not a corpse, not `IsoThumpable`, not a compost, has `movedThumpable=false` and has a
   container. When a vanilla Zone exists its `hourLastSeen` is copied, so
   `SeenHoursPreventLootRespawn` still applies.
2. `IsoObject.getContainerCount()` → `getLootRespawnContainerCount`: moved native furniture returns 0;
   player-built containers are mostly `IsoThumpable` and still excluded by the vanilla `instanceof`
   gate; unmoved fixed objects return their real count.

**Safehouse semantics kept**: `SafeHouse.getSafeHouse(square)` is not redirected and is re-checked
every cycle. Safehouse present → no respawn; after unclaiming, restock waits for the chunk's **next
normal `HoursForLootRespawn` cycle** (no claim/unclaim farming). `explored`, `hasBeenLooted`,
`MaxItemsForLootRespawn`, `SeenHoursPreventLootRespawn` and `ItemPickerJava.fillContainer` stay vanilla.

**Known limits**: a square with no Zone at all has no `hourLastSeen` to keep, so the fallback uses 0
(production runs `SeenHoursPreventLootRespawn=0`, so no current effect). This fixes periodic respawn
only; it does not force unexplored containers to spawn or rewrite existing `lootRespawnHour`.

---

<a id="2f"></a>
## 2f. Login sync DB write timing (server, observability probe)

**Purpose**: logins sometimes coincided with `Server is too busy`, but evidence only showed the
main thread doing synchronous work during login, not which DB operation caused spikes. This adds
attributable server-side timing without changing login concurrency or rejection policy.

Only three call sites in `LoginPacket.processServer` are redirected:

| Original call | Helper op | Vanilla bytecode |
|---|---|---|
| `ServerWorldDatabase.setPassword(String,String)` | `SET_PASSWORD` | offset 1273; caller's existing `catch Exception` kept |
| `ServerWorldDatabase.updateLastConnectionDate(String,String)` | `UPDATE_LAST_CONNECTION` | offset 1303 |
| `ServerWorldDatabase.setUserSteamID(String,String)` | `SET_USER_STEAM_ID` | offset 1330; following `POP` kept |

Each helper **delegates to the original receiver method exactly once**, with `System.nanoTime()`
around the delegate only, and on success logs:

```text
[MinidoracatJavaPatch][LoginMetrics] op=<fixed enum> elapsedNs=<decimal>
```

The payload contains no username, password, Steam ID, IP or token. Output uses the existing
`DebugType.Multiplayer.println` sink only — no new file writer, thread, flush, cache, queue, retry,
SQL or transaction. Non-fatal log formatting/sink failures never change the login result;
`VirtualMachineError`, `ThreadDeath` and `LinkageError` propagate. If the delegate throws a fatal
error, the wrapper computes elapsed time and rethrows without logging; if the delegate fails
non-fatally and logging fails fatally, the logging error wins; suppressed lists are never modified.

This is not a login optimization; it is the A/B baseline. After deployment, align the three ops'
elapsed distributions with `Server is too busy` periods; only once one op shows a long tail are
behavior changes (transactions/batching) evaluated. The native 70 ms busy check,
`LoginQueueEnabled`, `DenyLoginOnOverloadedServer`, auth/protocol and login order are unchanged.

---

<a id="2g"></a>
## 2g. Indexed entity removal on chunk unload (server)

**Production evidence**: during reported stutter and black edges, the host had ~85% CPU idle, low
I/O and ample free memory, with no OOM, `VehicleCollide` or sustained login spikes, yet server FPS
dropped to ~2–5. In 4 of 5 matching-jar thread dumps the main thread was at:

```text
Array.removeValue
  -> EntityBucket.updateMembership
  -> EngineEntityManager.removeEntityInternal
  -> IsoChunk.removeFromWorld
  -> ServerMap$ServerCell.Unload
  -> ServerMap.postupdate
```

`EngineEntityManager.entities` and every `EntityBucket.entities` are `new Array<>(false, 16)`.
Vanilla `removeValue(entity, true)` scans linearly from index 0; when many chunks unload in one
wave, k entities repeatedly scan shrinking global/bucket arrays: O(k×N), worst case near O(N²).
This explains the idle host: PZ's single authoritative world-loop thread saturates one core while
the rest idle, and players still see delayed chunk delivery and black edges.

**Patch scope: four call sites.**

| class.method | Original call | Redirect |
|---|---|---|
| `EngineEntityManager.addEntityInternal` | `Array.add` ×1 | `FastIdentityArrayRemoval.add` |
| `EngineEntityManager.removeEntityInternal` | `Array.removeValue` ×1 | `FastIdentityArrayRemoval.remove` |
| `EntityBucket.updateMembership` | `Array.add` ×1, `removeValue` ×1 | same as above |

`ServerCell.Unload`, chunk decisions, entity callbacks, bucket bits, listener order, login queue and
busy protection are untouched. On patched `add` the helper maintains a sidecar mapping
`System.identityHashCode(entity)` → index; removal is a tail swap-remove that updates only the moved
element's index, so normal add/remove is O(1).

**Safety and lifecycle**:

- Registry is `WeakHashMap<Array<?>, State>` and only accepts `ordered=false` arrays; `State` holds
  only `TIntIntHashMap`/`TIntHashSet` and primitive counters, no strong reference to the Array or entities.
- `TIntIntHashMap` keeps Trove's default auto-compaction (factor = loadFactor = 0.5; amortized
  O(1)/remove). **It must not be disabled**: Trove removal leaves REMOVED tombstones, and compaction
  is the only bounded reclamation. An early version set `setAutoCompactionFactor(0.0F)`; hours of
  load/unload churn saturated tombstones, exhausted FREE slots and degraded get/put probing to full
  table scans — on 2026-08-06 production saw 15–25 s main-loop stalls and repeated
  `Server is too busy`; thread dumps confirmed it and the setting was reverted.
- On identity-hash collision or duplicate identity, that hash falls back to vanilla's
  front-to-back first-identity-match linear semantics; every fast remove first checks `items[index] == value`.
- On size/index drift the sidecar rebuilds at most once; if still inconsistent it is invalidated and
  vanilla `removeValue` is called. `ordered=true`, `identity=false` and null always take the vanilla path.
- Locking is per Array; the global weak-registry lock covers only short lookup/registration, never
  an O(N) rebuild or the original add.

**Verification**: the build asserts the four original calls are gone and each helper call appears
exactly once, and that helper/inner classes contain no `IdentityHashMap` or strong entity
references. Tests cover tail-swap equivalence, missing elements, ordered/equality/null, size and
index drift, duplicates/hash collisions, concurrent use across Arrays, deterministic stats, and the
tombstone churn regression `churnKeepsTombstonesBounded` (4,096 live × 20,480 cycles, reflectively
counting Trove REMOVED slots; asserts maxRemoved ≤ 3,072 and zero rebuild/linearScan/fallback; the
negative control with compaction disabled reaches maxRemoved = 6,500 and fails). A scale benchmark
(N = 1,024/2,048/4,096/8,192; 3 warmup + median of 7) reports add, first remove, full remove,
ns/entity, doubling ratio and thread allocation; timings are report-only, with no machine-dependent
pass/fail threshold.

---

<a id="2h"></a>
## 2h. popman shared-buffer thread race, v3 buffer isolation (retired: fixed upstream by TIS in 42.20.2)

On 2026-07-30 production logged 77 `IngameState.UpdateStuff> Exception thrown` =
`BufferUnderflowException` in `ZombiePopulationManager.updateMain`. Root cause: `updateMain` (main
thread) pages the native add-zombie queue through `this.byteBuffer` (`allocateDirect(1024)`,
29 bytes/record) while `writeCellSnapshot` on the MapCollisionData background thread (21 bytes/record)
uses the same buffer; the writers share `saveLock` but the reader takes no lock, so the shared
`position` races → random underflow or silent misreads, and the exception aborts the rest of that
tick (including `PathfindNative.updateMain`). A runtime trace (`pageCount=35 > readable=28`,
1024−814 = 210 = 10×21-byte writer records) confirmed the overlap. The v3 patch redirected all 10
`getfield byteBuffer` reads in `updateMain` to a main-thread-only buffer (plus a count-clamp fuse).
42.20.2 added a dedicated `readByteBuffer`, instruction-for-instruction equivalent to v3, so both
the swap and the clamp were retired.

---

<a id="2i"></a>
## 2i. Join stall timing (retired: 2026-09-02, observability probe)

A timing-wrapper probe of the same shape as §2f, added to attribute 6–11 s main-loop stalls during
player join/respawn. It wrapped `CreatePlayerPacket.processServer`'s four heavy calls
(`OnNewGame` Lua event, `ServerPlayerDB.serverUpdateNetworkCharacter`, `ServerPlayerDB.process()`,
packet `write`) and the normal rejoin path `GameServer.receivePlayerConnect` (total, plus
`serverLoadNetworkCharacter`), logging `[MinidoracatJavaPatch][JoinMetrics] op=… elapsedNs=…`.
Retired once the question was answered: production checks from 8/30–9/2 showed `REJOIN_TOTAL`
routinely 5–13 ms, so the wrapper no longer needed to stay in the patch surface. Revive from
commit `13650e1` (`git checkout 13650e1 -- <file>` plus the matching PatchConfig/SmokeCheck/build.ps1 sections).

---

<a id="2j"></a>
## 2j. Client texture pipeline threshold fix + observability (client, first client patch; "invisible entities")

**Symptom and root cause**: a known unfixed B42 MP bug — an affected client sees teammates,
zombies and vehicles as **only a shadow and a nameplate, with no 3D model**; triggers with >20
players online, relog temporarily helps, and nothing is logged. First link of the causal chain
(four independent decompile traces + adversarial review):
`TextureIDAssetManager.waitFileTask` uses the global 50 MB DirectBuffer watermark as a hard gate
(`while (getBytesAllocated() > 52428800L) sleep(20)`); above it, the 2–4 file-loading threads sleep
indefinitely (no log, no timeout). Textures and meshes share the FileSystemImpl loader pool, so
while it is stalled every newly visible or just-Reset entity stays fully invisible because of the
all-or-nothing bake gate (`ModelInstanceTextureCreator.render` bakes nothing if any texture is not
ready), while shadows (FBORenderShadows blob decals) and nameplates (UI batch) draw via separate
pipelines. Details: `docs/specs/zombie_core_textures_TextureIDAssetManager.json`.

**Patch** (`PatchConfig.client()`, expectedHits = 2, both in `waitFileTask`):

1. Redirect — `DirectBufferAllocator.getBytesAllocated()J` →
   `zombie/mdc/TexturePipelineGuard.bytesAllocatedObserved()J` (same `()J` shape; the value passes
   through unchanged and exceptions from the real call propagate as in vanilla; the observation
   part swallows its own errors but rethrows `VirtualMachineError`/`ThreadDeath`/`LinkageError`,
   so loading behavior never changes).
2. Const-change — threshold `52428800L` (50 MB) → v1 256 MB → v1.1 1 GB → **v1.2 `4294967296L` (4 GB)**.
   The threshold measures decoded-but-not-uploaded pixel buffers, but `WrappedBuffer` uses LWJGL
   native malloc (**not bounded by `-XX:MaxDirectMemorySize`**) and the check happens before
   allocation, so several workers can pass at once — it is not a hard cap. Tester logs showed the
   watermark **floor ratchets up and never falls** (50→125→154→263→273 MB) and reached v1's 256 MB
   in ~35 minutes, re-sleeping all loaders; with 1 GB, a content surge while driving into Louisville
   raised the floor by 550+ MB in minutes (final floor 1,096 MB > 1,024 MB → pipeline permanently
   dead). **Leak/residency scales with the amount of new content seen, not with time**; any ceiling
   only buys runway, and the root fix is disposing the leaked `ImageData` buffers (§2j-v2.0). The
   leak is process-level static state, so relog does not clear it (explaining why relog only
   sometimes helps); only a full game restart resets it. Not suitable for low-RAM (≤8 GB) machines.

**Observability output** (decisions inside `synchronized`, `DebugLog.log` always outside the lock
so slow logging never serializes the loader threads): an `active` line (flag set only after a
successful log; retried if DebugLog is not ready at early boot — this line is the install
verification contract); a `hwmBytes` line per 8 MB high-water step; while above the vanilla 50 MB
gate, at most one line per 5 s with `bytes/hwm/floorBytes/aboveVanillaMs/vanillaStallSamples/patchedStallSamples`;
and (v1.1) a periodic line every 60 s when nothing else was logged, where `floorBytes` = the 60 s
minimum watermark — **a monotonically rising `floorBytes` is direct evidence of an ongoing leak, and
its slope is the leak rate**. `vanillaStallSamples` counts samples where vanilla would have entered
at least one 20 ms wait (alone it does not prove sustained starvation; consecutive stall lines plus
`aboveVanillaMs` do); `patchedStallSamples > 0` means the floor reached even the 4 GB ceiling.

**Packaging, isolated from the server**: `build-client.ps1` builds a separate client package
(currently `MinidoracatClientPatches-42.21.0-0.2.2.zip`) and never touches the server manifest; the
client classpath `[".", "projectzomboid.jar"]` is unchanged and loose classes override.
`Install-Patches.bat` installs selected modules (`core`, `profiler`, and mutually exclusive
`client-fixes-standard`/`client-fixes-lowmem`) after jar/payload SHA and ownership checks;
`Uninstall-Patches.bat` removes per module and refuses unknown or modified classes. Steam file
verification does not remove loose classes: uninstall before a game update, **never while the JVM is
running**. Binaries are for local use by legal game owners only and are not committed or redistributed.

**Verification**: hit count exactly 2; SmokeCheck client mode — vanilla precondition pins (exactly
one `getBytesAllocated` and one `52428800L` in `waitFileTask`, so a PZ rewrite fails the build),
full-order pin (observed → 4 GB → `lcmp` → `ifle`), `sleep(20)` loop kept, helper threshold constant
tied to the bytecode constant, real allocate/dispose passthrough smoke; LoadCheck client mode
(`-Xverify:all` + signature/constant ties); BytecodeVerify; `TexturePipelineGuardBehaviorTest`
(real `DirectBufferAllocator` allocations for passthrough / 50 MB crossing / dispose-to-zero;
the 1 GB threshold and floor/periodic/priority state machine via reflective `observe()` with
synthetic values). In the field: search `console.txt` for `TexPipelineGuard`; an `active` line means
it is live; on recurrence, compare `vanillaStallSamples` with relog times.

### 2j-v2.0 Leak root fix, wave 1 (S1/S2/S4/S6)

**Diagnosis** (four retention traces + adversarial review, all confirmed in source): the 1,096 MB
floor comes from side paths, not from "upload without free" (`generateHwId` does dispose at the end).
Main culprit 1 (40–60%): **`ImageData.dispose()` ignores `frames`** — APNG animated textures hold a
full-size buffer per frame, and dispose frees only `data` + `mipMaps`; a deterministic, silent leak
and the bulk of the ~110 MB baseline. Main culprit 2 (20–35%): **`getData()` falls back to a fixed
`67108864` (64 MB)** regardless of real size, and mip-flagged APNGs hit `getMipMapCount()==0` →
`getMipMapData(-1)` AIOOBE, skipping the trailing dispose — leaking 64 MB + mip chain + all frames
per hit (the +64/+99 MB jumps). Minor: cancel-path discards (5–15%, pending telemetry) and
`setImageData` overwrite pinning (3–10%, wave 2).

**Patch** — a new **head call** shape: after `visitCode`, insert `aload_0; invokestatic helper`
(linear, no branches; `visitMaxs` takes max(original, 1)). The helper
`zombie.core.textures.MinidoracatTextureLeakGuard` must live in the same package because `frames`
is package-private.

- **S1** `dispose()` head call `disposeFrames`: dispose each frame (guarded by `isDisposed`, since
  a double `WrappedBuffer` dispose throws ISE), then clear. Safe: the only `frames` readers in the
  codebase are `AnimatedTextureID.setImageData` (runs before dispose and nulls `frame.data` after
  transfer) and the `ImageData(ImageDataFrame)` constructor.
- **S2** `getData()`/`getMipMapCount()` head call `ensureData`: when `data==null`, allocate
  `getWidthHW × getHeightHW × 4` (the 64 MB branch becomes dead code) and, for APNG, fill with the
  first frame (vanilla uploaded all zeros = invisible; now the first frame shows). `getMipMapCount`
  returns the true value, so the AIOOBE family completes the normal dispose. Bad files (non-positive
  size) fall back to vanilla. Caller census: `getMipMapCount` has 2 internal callers (the repaired
  path); all `getData` readers are bounded by `w*h*4`.
- **S4** redirect of the single call site in `TextureID.createSteamAvatar` to
  `createSteamAvatarFixed`: semantic re-implementation that disposes on failure/exception paths
  (vanilla leaked 65,536 B per attempt and the UI retries).
- **S6** `TextureID.freeMemory()` head call `onFreeMemory`: vanilla only drops the reference without
  disposing (a fake-free footgun; zero callers in 42.20, closed defensively).

**Verification**: hit count 7 (head call ×4 + redirect ×1 + the two v1.2 patches). SmokeCheck
vanilla precondition pins (`dispose` never touches `frames` = TIS has not fixed it; exactly one
64 MB constant in `getData`; `freeMemory` only drops the reference; exactly one avatar call site),
head-call full-order pins, redirect original-call count zero, `MipMapLevel.dispose` call count
unchanged. Behavior tests (same package, direct `frames` access) against the real
`DirectBufferAllocator`: frame accounting returns to zero, idempotent dispose, real-size allocation,
first-frame fill, bad-file fallback. `install.bat`/`uninstall.bat` gained per-file payload gates
(source pre-check / conflict / re-verify / rollback / ownership removal); a fake-game-dir manual
round trip passed (not part of the build gate).
**Expected**: join baseline 110 MB → <15 MB, Louisville surge +550 MB → ~0, 8 h floor
1,096 MB → <100 MB; a floor below vanilla's 50 MB gate closes the invisibility path (the 4 GB
threshold becomes a second fuse). Documented residual risks: unaudited bulk-write paths at worst
produce a bounded, logged `BufferOverflowException`; `createSteamAvatarFixed` cannot be tested on a
bare JVM (structure pins + manual QA); the `ensureData` lazy-init race has the same shape as
vanilla and is not worsened.

### 42.21 re-validation (2026-09-28)

**TIS fixed neither the threshold nor the five leak points; both patch groups are kept as-is (rebuild only).**

- `TextureIDAssetManager.waitFileTask()V` bytecode is identical to 42.20.4 (javap:
  `getBytesAllocated` → `ldc2_w 52428800` → `lcmp/ifle` → `sleep(20)`); `TextureIDAssetManager`,
  `DirectBufferAllocator`, `zombie/asset/**` and `zombie/fileSystem/**` are unchanged.
- The five targets (`ImageData.dispose`/`getData`/`getMipMapCount`,
  `TextureID.freeMemory`/`createSteamAvatar`) are bytecode-identical: dispose ignores frames,
  `getData` still has `ldc 67108864`, the avatar failure path still returns null directly,
  `freeMemory` still only drops the reference. A vanilla run against the 42.21 jar reproduces it:
  frame buffers survive dispose; a frames-only instance has `getMipMapCount()==0`;
  `getMipMapData(-1)` throws AIOOBE and leaves 64 MB + mip chain behind.
- 42.21's only `ImageData` changes are `ImageData(String)` switching to stb `NativeImage` (replacing
  ImageIO/PNGDecoder) and a power-of-two helper swap, neither on the entity texture path
  (`FileTask_LoadImageData → waitFileTask → ImageData(InputStream,boolean)`).

**The "shadow only" fix in 42.21 is a different chain** (inferred from code):
`ZomboidFileSystem.getAllModFolders` became `synchronized` + `volatile` and publishes the list only
once filled; `resetModFolders()` now resets both `modFolders` and `allowedPrefixes`. Previously a
race could cache `allowedPrefixes` computed from a half-built list, so worker-thread
`validatePrefix` (e.g. in `FileTask_LoadMesh`) threw
`IllegalArgumentException("Invalid prefix found …")` for mod paths → mesh load failure → shadow only.
That chain **logs**; ours (loader threads sleeping on the watermark gate) **logs nothing**.

**Triage for post-42.21 "shadow/nameplate only" reports** — check the console:

- `Invalid prefix found` present → the TIS chain (should be fixed in 42.21; report upstream if it persists).
- `TexPipelineGuard` stall lines with rising `vanillaStallSamples`/`patchedStallSamples` → our chain.
- Neither → new analysis; do not treat "TIS says it's fixed" as grounds to retire these patches.

---

<a id="2k"></a>
## 2k. Performance wave 1: vehicle line-of-sight prefilter + VehicleManager 512→256 (server)

**Basis**: an FPS-dip sampler (thread dump on each low) aggregated 66 dumps; vehicle geometry was
the largest theme at ~23% (`isVehicleBetween → getIntersectPoint` plus
`VehicleManager.serverUpdate`), a diffuse load with no single hotspot. Two independent code reviews
(including javap against the real jar) agreed. At 87 players the low was 4.4 FPS; expected gain
6–19% of tick time (→ 4.7–5.4 FPS). Amdahl bound: clearing every hotspot still would not reach
10 FPS — **this wave improves tail latency; it is not a 100-player capacity promise**.

### 2k-1. `IsoZombie.isVehicleBetween` conservative bounding-sphere prefilter

Vanilla runs a full OBB intersection against **every vehicle** in the loaded cell (on the miss path
2× `Transform.inverse()` per vehicle and ~6 vector-pool borrow/returns), with no distance prefilter,
every tick for chasing zombies. The patch redirects the method's only `getIntersectPoint` call
(javap offset 99, exactly one) to `VehicleIntersectPrefilter`:

- Compute the squared distance from the sight segment to the vehicle center; outside the
  conservative bounding sphere an intersection is geometrically impossible → return null (callers
  only test non-null, so **semantics are strictly equivalent**). Inside the sphere, or on any
  anomaly, delegate to vanilla.
- Per-vehicle radius: the **L1 upper bound** of `extents/2 + |centerOfMassOffset|` (≥ the L2
  half-diagonal, so zero false negatives) + 1.0F slack (absorbs sub-tile offsets between
  `getX/getY` and the physics origin), minimum 6.0F; very long mod vehicles scale automatically.
  Rejected alternatives: fixed vehicle length, endpoint distance, tight AABB ignoring rotation.
- A second-stage TTL result cache was rejected by both reviews (invalidation would need rotation,
  flip, towing and mod reload; the result is a pooled mutable `Vector3f` that cannot be held).
- `rejected/delegated/anomalies` counters print one line every 2^24 calls via the existing
  Multiplayer sink — **a reject rate > 0.9 is the effectiveness criterion** and a precondition for
  raising the player cap.
- Only this method's call site changes; `CombatManager`, `BaseVehicle.processRangeHit` and other
  callers are untouched.

### 2k-2. `VehicleManager.connected` 512→256 (retired: fixed upstream by TIS in 42.20.2)

`serverUpdate` scanned all 512 `connected[]` slots × all vehicles every tick for flag propagation
(5/5 dumps sat on that loop's back edge, offset 175 `goto 124`), although RakNet indexes are < 256
(`UdpEngine.connectionArray[256]`, IDs decoded as `getByte()&255`, `setIndex` has no callers). The
patch changed `<init>`'s `sipush 512` (context-pinned: followed by `anewarray UdpConnection`) to 256,
halving the scan. 42.20.2 deleted `connected[512]` and `BaseVehicle.connectionState[512]` in favor of
a per-connection `UdpConnection.vehicleStates` HashMap, so the patch site disappeared.

### Side note: manifest integrity check (a pitfall hit in this wave)

`build.ps1`'s `$helperEntries` was a hand-written list: a helper compiled into `dist/java` but
missing from the manifest would not be copied by `install.sh` → `NoClassDefFoundError` in
production (SmokeCheck's URLClassLoader loads all of `dist/java`, so it could not catch this). A
two-way check now aborts the build if `dist/java` and the manifest differ.

---

<a id="2l"></a>
## 2l. Server freeze fix: `removeGlassAttachments` infinite-loop fuse (retired: fixed upstream by TIS in 42.21.0)

On 2026-08-02 the whole server froze (frame counter stuck, `pkill -9` needed). Two thread dumps
4 s apart showed the main thread RUNNABLE inside `SmashWindowPacket.processServer →
IsoWindow.smashWindow → IsoGridSquare.removeGlassAttachments`: the loop did an unconditional `n--`
after `RemoveTileObject(o)`, but 42.20's safe-removal path could leave the object in place, so the
same index was hit forever (livelock; no patch classes on any stack — a pure vanilla bug). The patch
redirected the single call site (`smashWindow(ZZ)V` offset 221) to `GlassAttachmentGuard`, which
only stepped the index back when the list actually shrank, otherwise skipping the object and
logging `[MinidoracatJavaPatch][GlassGuard] stuck glass attachment skipped at x,y,z sprite=…`.
42.21.0 rewrote the method as a reverse loop without `n--` compensation (javap 42.21: offset 54
`iflt` exit, 205 `iinc 3,-1`), which always terminates within size iterations; keeping the helper
would override the new vanilla code, so it and its SmokeCheck asserts were deleted. Revive:
`git checkout 8d2bee8 -- patcher/game/zombie/mdc/GlassAttachmentGuard.java` (plus PatchConfig/SmokeCheck/build.ps1).

---

<a id="2m"></a>
## 2m. Performance wave 2 P5: `IsoCell` three-list identity membership sidecar (retired: fixed upstream by TIS in 42.20.2)

After 80 concurrent players (2026-08-03) the FPS lows returned and chunk unload was again the top
dump theme. `IsoCell`'s three scheduling lists (process, process-remove, static updaters) are
`ArrayList`s whose hot paths do O(N) mostly-missing `contains`/`remove` scans, and the per-tick
`removeAll` is O(P×R). The patch redirected 15 `ArrayList` call sites (`IsoCell` 10, `IsoObject` 1,
`IsoDeadBody` 4) to `CellListMembership` helpers backed by an identity-set sidecar (duplicate-aware,
anchored per `IsoCell` generation, strict gates falling back to vanilla, exact `modCount`
parity for `removeAll`, permanent kill after 8 audit divergences), with 18 build/behavior
assertions. 42.20.2 added native companion Sets with O(1) membership and an `isEmpty` fast path
(better than our O(P+R)) and fixed the `IsoDeadBody` side-channel mutation, so the patch was retired.

<a id="2n"></a>
## 2n. Fertilized-egg exemption from world item removal (`IsoGridSquare.load`) (server; retired: 2026-08-08, server-only redirect causes client desync)

- **Intent**: the production `WorldItemRemovalList` (247 entries) contains `Base.Egg`. The removal check in `IsoGridSquare.load` only compares `worldItem.getItem().getFullType()` against `SandboxOptions.worldItemRemovalSet` (exact string match), while fertilization is per-instance state on `Food` (`fertilized`/`fertilizedTime`/`timeToHatch`/`animalHatch`). With `HoursForWorldItemRemoval=24` vs. a hen egg hatch time of 504 h × 2.5 (`AnimalEggHatch=5`) = **1260 h**, every fertilized egg on the ground was deleted before it could use vanilla's supported on-ground hatch path (`Food.checkEggHatch` → `baby.addToWorld()`).
- **Patch**: shape-preserving INVOKEVIRTUAL→INVOKESTATIC redirect of the single `IsoWorldInventoryObject.isIgnoreRemoveSandbox()Z` call in `load(ByteBuffer,int,boolean)` (the only point where `worldItem` is on the stack after the list checks) to `FertilizedEggGuard`, exempting eggs that are `fertilized` with a non-empty `animalHatch`, bounded by a hatch-window ceiling of `dropTime + 4.0 × timeToHatch` world hours (ground items have `outermostContainer == null`, so cooling/cooking/freezing never clear `fertilized`, and `fertilizedTime` only advances while the chunk is loaded — without the ceiling, eggs in rarely loaded chunks would be kept forever).
- **It worked server-side**: `keptLoads=3649`, `expiredLoads=0`, `anomalies=0`; one tracked egg progressed 557 → 1121/1260 across many chunk unload/reload cycles.
- **Why retired**: the removal block in `IsoGridSquare.load` has **no `GameClient.client` guard**, and the server syncs the full `SandboxOptions` (including the removal list) to clients at handshake (`ConnectionDetails.writeSandboxOptions`). Both client chunk-load paths (server chunk packet, local MP cache) go through `IsoChunk.LoadFromDiskOrBufferInternal` → `gs.load()` and apply the same pure function of item state + world clock, so the client filters the egg out on every load: it never reappears on the player's screen and cannot be picked up until it hatches. Players reported exactly this.
- **Decision**: back to vanilla (fertilized eggs removed after 24 in-game hours); players are pointed to hutches (`IsoHutch`), whose eggs are not `IsoWorldInventoryObject` and never hit this path. Redirect, helper, LoadCheck signature check and 13 SmokeCheck assertions removed; `IsoGridSquare` is vanilla bytes again.
- **Lesson**: a server-only patch on a decision path that the client also runs without a `GameClient.client` guard will always cause visual/interaction desync. Confirm the guard exists first; otherwise patch the client too or solve it at the configuration layer.

<a id="2o"></a>
## 2o. Client chunk streaming observability (client, v2.1→v3.0, observe-only; black-edge forensics)

**Motivation**: 2026-08-11, two "black edges" incidents for the same player (one froze for 10 minutes and required a game restart; one self-healed after 5 minutes). Server metrics were green over the same window (normal tick rate; the client kept receiving broadcasts, so the network was alive), so the stall was in the client streaming pipeline — but the client log had been flooded by translation warnings (fixed separately in MinidoracatLangFor42). This patch **only observes, never changes behavior**, to capture evidence next time.

**What is observed** (`zombie.iso.WorldStreamer`; signatures and private fields verified with javap against the real jar): request lifecycle `sendRequests` → `pendingRequests1` + `mainThreadRequestQueue` → `updateMain` sends `RequestZipList` → `sentRequests` → `receiveChunkPart`/`receiveNotRequired` match `requestNumber` → `loadReceivedChunks` completes.
Hypotheses under test: (a) while `requestingLargeArea`, the `sendRequests` head gate stops sending new requests once `pendingRequests1 > 20`; (b) server-side `ClientChunkRequest.getRetryChunk` returns null after ≥3 retries, permanently abandoning that `requestNumber`. Together these would leave pending never drained and all new requests stopped (permanent black edges); opening the world map triggers largeArea mode, matching "the map also won't open during black edges". **Hypothesis (b) is void since 42.20.3**: the retry mechanism (`getRetryChunk`/`retriesCount`/`MAX_CHUNK_SEND_TRIES`) was removed and ungenerated chunks are now reported via the pending mechanism + `ChunkNotReady` packet (see 2p).

**Patch** (head calls, all receiver-only `(Lzombie/iso/WorldStreamer;)V`, helper `zombie.mdc.ChunkStreamObserver`):
- `updateMain()V` — heartbeat. Queue depths are read via reflection once per 10 s window (otherwise one time comparison per frame). Stall rule: outstanding requests and >30 s with zero receives → `STALL noReceiveMs=…` line (at most one per 10 s, with all queue depths); a periodic line every 60 s when there is activity (silent in single-player/idle).
- `receiveChunkPart` / `receiveNotRequired` — receive counters + last-receive timestamp (counters frozen during black edges = direct evidence the flow stopped).
- `receiveChunkNotReady(I)V` (added in v3.0) — see below.

**Why it is safe** (finalized after adversarial review):
- Head call inserts `aload_0; invokestatic` before the first instruction (empty stack at entry; args and locals untouched). Non-fatal helper exceptions are swallowed; fatal errors propagate.
- Threading: receive hooks run on the UdpEngine network thread (via `GameClient.addIncoming`) and share **no lock** with the main thread (an early draft shared a class monitor, which would have let main-thread reflection/string building block packet handling). The receive path only does `AtomicLong` increments + a volatile timestamp; all decision state is owned by the main thread.
- STALL uses two baselines: both the rising edge of outstanding requests and the last receive must be ≥30 s old (a single baseline false-alarms when a request is sent after minutes of idling). Baseline contamination is handled by "heartbeat gap >30 s = reset" instead of holding a `WorldStreamer` reference (a static strong reference would pin a retired instance's `IsoChunk` lists + native `Inflater`); this covers relog, in-place reconnect and freezes.
- Reflection field drift → a one-time "disabled" line, then permanent degradation to counters only.

**Build-time checks (SmokeCheck)**: vanilla anchors (`updateMain` touches `GameClient.connection`; zero pre-existing observer calls), total-order lock for all head calls, receive method bodies preserved, and a **name/type contract for every reflected field** (drift = build failure, not silent degradation). Behavior tests inject time: dual-baseline STALL/throttling, no false alarm on a new request after idle, instance swap reset, quiet suppression, recovery.

**Reading the log (42.20.3 / v3.0)**. Note `reqQ0` counts chunk **list heads** — each element is a whole `chunk.next` chain, so `reqQ0=1` may mean 1 or 200 chunks; they are flattened into `reqQ1`.
- `STALL … notReadyAgoMs=` small (seconds) and periodic `notReady=` keeps rising → server alive but keeps answering "not generated" = **generation bottleneck** (42.20.3 pending mechanism: 30 s generation timeout / 4096 limit). Check the server for `the chunk %d,%d was not generated` warnings and worldgen load; not a flow stop.
- `STALL … notReadyAgoMs=-1` (or large) + frozen parts → not even NotReady arrives = **full flow stop** (network/connection); compare with the server's send state for that connection.
- `STALL … pending1=20 largeArea=true` → candidate for hypothesis (a); collect `largeDl` series (42.20.x only, see 42.21 below).
- `STALL` while parts keep increasing → receive alive, the loading side (`DoChunk`/refs) is stuck; separate analysis.
- No `STALL` but the player sees black edges → payloads still arriving: the stall is outside `WorldStreamer` (`IsoChunkMap`/render layer) or the NotReady re-queue loop is too long — compare periodic `notReady=`/`sent=` with the vanilla warning `the server did not generate the chunk %d,%d in time, requesting it again`.

**v3.0 (42.20.3 rebuild, 2026-08-17)**: all anchors and reflected fields re-verified. Added the 4th head call `receiveChunkNotReady(I)V` — the server's proactive reply for ungenerated/over-limit chunks in the new protocol. Vanilla lifecycle: after draining `sentRequests` → `pendingRequests`, entries with `flagsWs&1` and the matching `requestNumber` are removed from the network thread's `pendingRequests` and marked `flagsUdp |= 16/24`; the same request object is still in the streamer thread's `pendingRequests1`, and `loadReceivedChunks` finishes it by flags — re-queued into `chunkRequests1` if the chunk is still referenced (deferred retry, with the vanilla "requesting it again" warning), otherwise returned to the `chunkStore` pool. **Independent baseline**: the hook only updates `lastNotReadyNs` and never touches the payload baseline (`lastReceiveNs`), so STALL keeps meaning "30 s without payload" and a generation bottleneck is not muted; STALL lines carry `notReadyAgoMs` for classification (a draft that counted NotReady as a receive would never fire STALL on the most likely black-edge shape of the new protocol). SmokeCheck adds the four-head-call order lock and an existence census for the new protocol methods; tests cover independent baselines/classification and notReady-only periodic lines.

**Low-memory variant (v3.0-lowmem)**: on ≤8 GB RAM machines (the confirmed 42.20.3 invisible-entity case from 2j had 8101 MB RAM and `-Xmx3G`), 2j's 4 GB wait threshold does not fit (it is a pre-allocation watermark check, not a hard cap: several workers can pass in the same second and a single allocation is unbounded, so worst-case native usage exceeds 4 GB). An explicit Patcher `client-lowmem` mode skips the constant change and redirects to `bytesAllocatedObservedLowMem` (effective threshold 50 MB baked into the helper; banner and stall classification use the effective value). Observability and the leak fixes are kept.

### 42.21 re-validation (2026-09-28)

**TIS rewrote the request lifecycle** (javap on the 42.21 jar): `WorldStreamer` removed `requestingLargeArea`, `largeAreaDownloads` and `requestLargeAreaZip`; the `sentRequests` → `pendingRequests` drain and `flagsWs&1` cancel reaping moved from the receive methods to a new `udpUpdate()`, called by `GameClient.addIncoming` before each incoming packet; the `sendRequests` head gate (send only if `pendingRequests1.size() <= 20`, `bipush 20; if_icmple`) is now **unconditional**, so hypothesis (a) — previously largeArea-only — is the normal case. Hook semantics are unchanged (`updateMain`/`receiveChunkNotReady` identical; both receive method heads still mean "payload arrived").

**Changes**:
- The helper no longer reflects the two removed fields. The old helper would hit `NoSuchFieldException` on 42.21 → reflection permanently disabled → all queues `-1` → STALL never fires (observability silently neutered). STALL/periodic lines drop `largeArea=`/`largeDl=` and add `sendGate=open|closed|?` (`pendingRequests1 > 20` = closed; `?` when reflection is disabled); `SEND_GATE_PENDING1=20` is used for labeling only.
- Reflection read + decision extracted into `readAndDecide(now, ws)` (`onUpdateMain` unchanged) so tests exercise the real path.
- SmokeCheck: field contract now 6 fields; the old "`receiveChunkPart` touches `sentRequests` the same number of times" check would be `0 == 0` on 42.21, so it now pins the `pendingRequests` touch count in `receiveChunkPart`/`receiveNotRequired` as unchanged and non-zero; new vanilla precondition: the `sendRequests` gate is `getfield pendingRequests1 → size → bipush 20 → if_icmple`, exactly one `20` in the method, and the helper constant equals 20.
- Tests: `sendGate` labeling, plus a case running `readAndDecide` against the game jar's real `WorldStreamer.instance` (21 entries in `pendingRequests1`, 31 s without receives must print `STALL … pending1=21 sendGate=closed`); a mutant that reflects any removed field turns it red.

**Reading update**: `STALL … sendGate=closed` → the client stopped sending new requests because of the in-flight cap; with `pending1` stuck ≥21 and frozen parts = in-flight requests never complete (server did not answer or the reply was lost). The `largeArea=true` rule above applies to 42.20.x only.

<a id="2p"></a>
## 2p. Chunk supply batching (W4-1 v2, server, default observe) + request timeout 8 s→15 s (W4-2, client; retired: target method removed in 42.20.3)

> **Revived as v2 on 2026-09-07 (default observe).** The 2026-09-02 retirement ("packed 47–82/session, skip[short] 99.3% ⇒ benefit ≈ 0") was a misdiagnosis — see 2p-1; v2 design in 2p-2; v1 analysis in 2p-v1. Last v1 commit: 13650e1.

### 42.21 re-validation (2026-09-28; code unchanged, observe data to be recollected)

**Server side unchanged**: `PlayerDownloadServer.update()`/`removeOlderDuplicateRequests()` bodies are identical to 42.20.4 (still one `ccrWaiting.remove(0)` per frame inside the ready gate); `ClientChunkRequest.isChunksFilled`'s split threshold of 20 and `RequestZipListPacket.parse` ("new ccr per packet, switch only when full at 20 within the same packet") are unchanged. All three hook hit counts unchanged.

**New client in-flight cap**: 42.21 `WorldStreamer.sendRequests` applies "refill only when `pendingRequests1 ≤ 20`, stop at ≥40" to all requests (42.20.4 had these thresholds only for largeArea downloads; normal requests were uncapped), and chunks with empty `refs` are always dropped. `pendingRequests1` entries are removed only when the chunk is complete, so each player has at most 40 chunks in flight and the server's `ccrWaiting` holds at most ~2 ccrs (≤3 partially filled).

**Impact**: the 2p-0 supply model ("client sends one row per frame, uncapped in flight") and the `[ChunkPacker]` observe data from 42.20.4 (`depth`, `would.merge`) no longer apply. The server's one-ccr-per-frame limit still exists, so enforce could in theory raise per-frame delivery from 20 to ~38 (`batch` > 40 is unreachable on 42.21), but the bottleneck may now be the client window. Default stays observe until 42.21 peak-hour heartbeats are collected; judge by the share of `depth=2` (`depth≥3` should almost never appear). `RequestLargeAreaZipPacket` is gone in 42.21; the helper's largeArea branch was already unreachable in 42.20.4 and is left as dead code.

### 2p-0. Actual supply model (2026-09-06 incident, 42.20.4)

**Incident**: 74 players online, 9 ms ping; a player driving fast in a straight line saw large unloaded black areas ahead. Server frame-count deltas: 4.86 fps → **3.18** → **2.69** over three minutes, then repeated `Server is too busy`. Zero `not generated` / `stayed outside` / `ChunkNotReady` in that session (not the generation path); chunk files on that road were 2–3 KB, so neither bandwidth nor ping was the bottleneck.

**Supply chain** (42.20.4 snapshot):
1. Client `IsoChunkMap.ProcessChunkPos`: when the center chunk changes, each frame steps one `LoadLeft/Right/Up/Down`, calling `LoadChunkForLater` → `WorldStreamer.addJob(…, true)` for a whole row of `chunkGridWidth` chunks, chained on `chunkHeadMain`; `chunkGridWidth = 19` at ≥1080p (`CalcChunkWidth` clamps to 19); in a vehicle the center is shifted ahead by `speedKmH/5` squares.
2. `WorldStreamer.threadLoop` runs every 20 ms with pending work, else 140 ms; `sendRequests` has no in-flight cap outside largeArea but has a `shouldSendChunk` distance gate (`|dx|<10 && |dy|<10` chunks from any known player); `updateMain` merges all request chains into at most one `RequestZipList` packet per main-thread frame.
3. Server `RequestZipListPacket.parse` takes a new pooled ccr per packet and only switches to another ccr when full at 20 **within the same packet** (`ClientChunkRequest.isChunksFilled`); it never appends to a previous packet's partial ccr.
4. `GameServer`'s main loop calls `PlayerDownloadServer.update()` once per connection per frame; it does `ccrWaiting.remove(0)` **once**, only when `workerThread.ready`. Loaded chunks are serialized on the main thread (`SaveLoadedChunk`), unloaded ones are read from disk by the worker (`SafeRead`), missing files go pending → generate → 30 s `ChunkNotReady`. The worker sets `ready=true` when `sendArray` returns (no ACK wait).
5. `SentChunkPacket`: 1000-byte parts, `reliability=2` = RELIABLE (**not** ordered).
6. Client `IsoChunkMap.updateInternal` integrates `1 + floor(3q/19)` arrived chunks per frame.

⇒ **Per-player supply cap = main-loop fps × one row.** 3.2 fps × 19 ≈ 61 chunks/s; driving at 100 km/h (if truly 27.8 squares/s) needs ~66 straight and ~93 at 45° (√2×); each batch also waits for the next frame (+150 ms average, +700 ms at troughs). In-vehicle auto-zoom pushes the visible front close to the window edge: at zoom 2.5 while driving, the margin between request release and the black row entering the screen is only **0.26 s** (1.66 s at zoom 1) — the problem is "a few hundred ms late", not "can't load at all". Review caveats: the speedometer includes `getFakeSpeedModifier` (`120/min(SpeedLimit,120)`) and may overstate true speed; the native send buffer was not measured; the `shouldSendChunk` distance gate may delay look-ahead by ~0.7 s.

### 2p-1. Why v1 was wrongly retired

The old helper counted in order: `queue<2` → `skipShort`, then `head.largeArea` → `skipLarge`, then `head.size() >= BATCH` → `skipFull`. With `BATCH=8` and a row of 19, any queue ≥2 had a head ≥8 ⇒ **always `skipFull`, never packed**. A 2026-09-01 session: `packed=82 skip[short=4,288,182 full=66,823 budget=2,651] overrunTicks=2,405` — "queue ≥2 ccrs" happened ~70k times in one evening (≈1.4–2.4 backlogged connections per frame). The 150 ms overrun gate was designed for 10 fps and fired almost every tick at 3–5 fps (2,405 ≈ 2,651). The 99.3% `skip[short]` was just denominator dilution (74 connections × every tick). **"Benefit ≈ 0" meant the patch never engaged, not that there was no demand. Lesson: before retiring a patch, confirm it actually fired.**

### 2p-2. v2 design (2026-09-07)

- **Batch cap above vanilla's 20**: `chunks` is an unbounded `ArrayList` and consumers (`update()`/`sendArray`) loop on `chunks.size()`; 20 is only the parse/pending split threshold ⇒ `isChunksFilled` is not touched; the helper raises the cap. Default 38 (two rows); `-Dmdc.chunkPacker.batch` clamped 1..60 (three rows).
- **Overrun gate off by default** (`-Dmdc.chunkPacker.overrunMs=0`); main-thread serialization is throttled instead by a global per-tick extra-move budget `-Dmdc.chunkPacker.windowBudget` (default 200). Tick boundaries are detected at the head of `update()` (every connection passes every frame, independent of queue ≥2), gap threshold 80 ms.
- **Tri-state `-Dmdc.chunkPacker`**: `0|off` / `1|enforce` / `2|observe` (default). Observe never modifies the queue; it only counts would-merge.
- **Three hooks (one `PlayerDownloadServer` ClassPatch)**:
  1. Head call `packQueue` at the head of `removeOlderDuplicateRequests()V` — inside the ready gate, before vanilla dedupe (same as v1; see 2p-v1).
  2. Head call `onUpdate` at the head of `update()V` — **outside the gate; only counters, tick boundary and heartbeat, never touches any pds field** (outside the gate the worker shares `bb/sb/bbw` and the `cancelled` HashSet). Subtracting `readyCalls` gives the share of `ready=false` (worker spanning frames).
  3. 1:1 redirect of the single `IsoChunk.SaveLoadedChunk(Chunk,CRC32)V` in `update()` to `saveLoadedChunk` (receiver prepended; exceptions pass through unchanged to vanilla's catch → `sendNotRequired`) to measure main-thread serialization time = the cost of enforce.
- **Never split the ready gate**: with multiple commands queued, the worker sets `ready=true` after each one, so the main thread would pass the gate and race the running worker on `bb/sb/bbw` and `cancelled`. "Let the worker chain disk-only ccrs" is not a reliable classification (loaded-but-unsaved content must be serialized on the main thread) and would be a pipeline redesign.
- **Heartbeat** every 5 min: `updates/ready/notReady`, `depth[0/1/2/3-4/5+/max]`, `head[avgX10/max/full20]` (= client packet size), `would[pack/merge]`, `packed/merged`, `skip[short/large/full/noSource/budget/dupAbort]`, `save[calls/avgUs/maxUs/tickMaxMs/ticks/>5/>20/>50ms]`, `overrunTicks`, `anomalies`.
- **Build-time checks (SmokeCheck)**: vanilla preconditions (`update` has 3 `List.remove(I)`, 1 dedupe call, 1 `SaveLoadedChunk`, zero helper calls; dedupe is called exactly once in the class = the in-gate fact); total order of both head calls and zero `packQueue` in `update`; redirect ×1 with the original call gone (+2 real instructions); dedupe original body preserved (+2 real instructions); three public field contracts; `isChunksFilled` has exactly one `bipush 20` while `update`/`sendArray` have no constant 20 and ≥1 `size()` each (if TIS adds a hard cap on the consumer side this goes red ⇒ re-evaluate BATCH); helper `saveLoadedChunk` has no catch.
- **Tests**: four configurations (observe / enforce / enforce + `windowBudget=0` / off, including mode dispatch through a real `PlayerDownloadServer` via `packQueue`): conservation, BATCH cap, whole-move abort on duplicates (three scenarios), largeArea, ordering, budget gate, tick reset, depth statistics.
- **Criteria to switch to enforce** (after one peak evening): `depth≥2` share of `ready` and `would.merge` are significant (two or more rows really waiting); `save.tickMaxMs` within 10% of a 3 fps frame (~300 ms); low `notReady` share (worker not spanning frames, so bigger batches won't prolong `ready=false`). Acceptance after enforce: `packed/merged` rise, fewer black-edge reports from drivers, `anomalies` stays 0, main-loop fps does not drop.
- **Upper bound of benefit**: 2–3 rows per frame ⇒ 120–180 chunks/s at 3.2 fps, critical speed ×2–3. When driving straight with single packets (queue always ≤1) the gain is zero — then main-loop fps is the only multiplier.

### 2p-v1. v1 analysis (2026-08-13)

**Root cause** (full design: `docs/chunk-throughput-design-v1.md`): vanilla chunk supply ran at ~15% of design capacity — the client sent one `RequestZipList` per frame (~3 chunks), `RequestZipListPacket.parse` unconditionally enqueued a new `ClientChunkRequest` per packet (never merging into a partial ccr), and `PlayerDownloadServer.update()` processed one ccr per worker cycle (10 Hz) ≈ 30 chunks/s, wasting 85% of the `NON_LARGE_AREA_CHUNKS_LIMIT` (20) × 10 Hz = 200 chunks/s budget. Once the backlog exceeded the client's 8 s timeout, `resendTimedOutRequests` set `flagsWs |= 9`, and `loadReceivedChunks` discarded the **already delivered** data and re-queued without telling the server to cancel → self-sustaining livelock (measured: pending constant at request rate × 8 s = 240; ~141 resend rounds in 18 minutes; ~105 MB received and discarded; zero chunks loaded = permanent black edges). `PlayerDownloadServer` is a per-`UdpConnection` daemon thread, so only that player stalls while global server metrics stay green.

**W4-1 patch**: head call at `PlayerDownloadServer.removeOlderDuplicateRequests()V` → `zombie.mdc.ChunkRequestPacker.packQueue`, packing the front of the queue up to the batch cap. **The hook is not `update()V`**: all `ccrWaiting` access in `update()` is inside `if (workerThread.ready)`, the only mutual exclusion with the worker thread (on 42.20.2, `sendArray` added to `ccrForRetries` and kept calling `chunks.add`); hooking offset 0 would be outside the gate, worst case releasing the same `Chunk` twice into the **static** `freeChunks` pool = cross-player corruption. `removeOlderDuplicateRequests` is called exactly once in the class, by `update()`, inside the gate and before vanilla dedupe (javap-verified). Dedupe semantics preserved: vanilla only detects cross-ccr duplicates, so ccrs whose `(wx,wy)` already exists in the head are skipped and left to vanilla; emptied ccrs are removed and pooled by the rest of the vanilla method; largeArea is not touched. v1 defaults: batch cap **8**, global extra-move budget **120** per 100 ms window (`-Dmdc.chunkPacker.batch` / `-Dmdc.chunkPacker.windowBudget`; `windowBudget=0` disabled the patch = vanilla, an emergency off without redeploy).

**W4-2 patch (retired in 42.20.3)**: constant change `8000L` → `15000L` in `WorldStreamer.resendTimedOutRequests()V` (the only occurrence in the class). Both `RequestZipList` and `SentChunkPacket` are RELIABLE, so this timeout was penalizing a slow server rather than recovering real loss. **42.20.3 removed the method entirely** (blind timeout-resend replaced by proactive `ChunkNotReady`), so the patch was retired and its SmokeCheck constant assertions removed.

**v1 checks**: SmokeCheck — vanilla preconditions (exactly 3 same-signature `List.remove(I)` + 1 dedupe call in `update`), hook inside the ready gate (dedupe head order + zero packer calls in `update()`), both original bodies preserved, three public field contracts, `isChunksFilled`'s `bipush 20` pinned (if TIS lowers it and we don't follow = oversending). 8 behavior tests: conservation, batch cap, dedupe preserved, largeArea both ways, ordering, degenerate input, never above vanilla cap, window budget cap.

**42.20.3 migration (2026-08-17)**: TIS refactored the same area (fixing "Loading Map forever"): pending mechanism (`PendingChunk` ≤4096, `OutOfRangeRequest` ≤1024, new `ChunkNotReady` packet); **server retry mechanism removed** (`Chunk.retriesCount`, `MAX_CHUNK_SEND_TRIES`, `getRetryChunk`); worker refill moved to a `queuedByWorker` concurrent queue, so `WorkerThread` no longer writes `ccrWaiting` (the hook's exclusion precondition is looser; hook unchanged). `update()` now runs ready gate → `updatePendingChunks()` → dedupe (hook); ccrs refilled from pending are ordinary non-largeArea ccrs and safe to pack. **The throughput bottleneck was not fixed** (`RequestZipListPacket` bit-identical, still one ccr per tick) ⇒ W4-1 kept; the official changelog says additional black-edge causes are still under investigation. Client side: `WorldStreamer` was substantially refactored — W4-2 retired; the v2.2 client package was fully invalidated and **rebuilt as v3.0** (2o observability re-verified plus the 4th head call `receiveChunkNotReady(I)V`; the three texture-line classes were instruction-identical and reused; 42.20.3 client and server jars have the same whole-file SHA `bda809fb…`, so the install same-source check works as-is). The `retriesCount` SmokeCheck assertion was removed with the vanilla field. Full analysis: `docs/report/pz-42.20.3-update-analysis.md`.

<a id="2q"></a>
## 2q. Container-cycle crash guard (W5, server)

**Incident**: On 2026-08-13 21:31:10 the production server's main loop died with `java.lang.StackOverflowError`: 1024 frames, all `ItemContainer.getCharacter` recursing into itself. The server hung for 13 minutes (frame counter frozen at f:54247), a graceful `quit` was never processed, and the watchdog force-restarted it. The last world save (21:30:10) succeeded, so only ~1 minute of world data was lost, but every player action during the 13-minute hang was never received by the server.

**Vanilla defect**: `getCharacter()` climbs "container → item holding it → that item's container" to find the owner with **no cycle detection**. `ItemContainer` has no cycle check anywhere; `AddItem` only rejects duplicate IDs and does not stop a container from being placed inside its own descendant. MP packet-driven moves can therefore produce "A inside B, B inside A".

**The cycle can only exist at runtime** (so scanning saves finds nothing): `containingItem` is set once in the `InventoryContainer` constructor and is not serialized; `InventoryContainer.save` writes nested containers recursively, so a cycle in the save would have crashed the save first — yet the `World saved` 60 s before the crash succeeded.

**Patch** (two sites, both shape-preserving call redirects, method-scoped):

| Method | Redirected site | Truncation result |
|---|---|---|
| `getCharacter()` | the only self-recursion (javap offset 42) | returns `null` = owner unknown, same value vanilla returns for a container lying on the ground |
| `isInCharacterInventory(IsoGameCharacter)` | the only self-recursion (offset 52) | returns `false`, same as vanilla's fall-through after walking the chain |

The second site was flagged in review as "the next one to blow up": `Transaction.getDuration()` calls it, and `getDuration()` runs **only on the server, when a `Transaction` is constructed from an `ItemTransactionPacket`** — the same packet path that creates the cycle. Helper `zombie.mdc.ContainerCycleGuard` tracks depth in a ThreadLocal (shared by both sites) and cuts the recursion once it exceeds `MAX_DEPTH` (default 64; effective chain length 65, because the first level runs vanilla code without the helper).

**Diagnostics**: on a trip the guard walks the chain and logs each containerId / itemId / fullType on the cycle and the closing point (hard cap of 128 steps; only field reads and trivial getters, each verified not to recurse). The full chain is logged at most once per 60 s and at most 5 times per run; after that a **10-minute trips heartbeat** line is logged (a cycle never heals by itself, so admins must keep seeing it). Exceptions inside the helper itself are logged with a stack trace, bounded to the first 3.

**Kill switch**: `-Dmdc.cycleGuard.maxDepth=0` disables both sites (back to vanilla's crash behavior, at ~2× stack consumption); no redeploy needed.

**⚠ Known degradation (must read)**: the five `GameServer` inventory broadcasts (`sendAddItemToContainer` / `sendAddItemsToContainer` / `sendReplaceItemInContainer` / `sendRemoveItemFromContainer` / `sendRemoveItemsFromContainer`) have three branches for a container nested in an item: `getCharacter() instanceof IsoPlayer` → `getParent() != null` → **a third branch that vanilla left empty**. For a container on a cycle, `getCharacter()` returns null and `getParent()` is null, so **no packet is sent and nothing is logged** — clients never see add/remove/replace in that container (players perceive items vanishing). Likewise, `removeFromHands` in `ItemContainer.Remove` is skipped (item removed from the container but still in hand). This is better than vanilla's whole-server death, but it is a **new, silent, persistent** degradation. **This patch stops the bleeding and catches the event; it is not a root-cause fix.**

**W5-2 entry-point probe (landed 2026-08-29, observe mode first; enforce pending data)**: the single `containsID(I)Z` call in `AddItem(InventoryItem)` is redirected 1:1 to `ContainerAddCycleProbe.containsID(ItemContainer,int)`. The vanilla result is returned unchanged, and the probe only runs **when `containsID` returns false** (i.e. vanilla really proceeds to add), so duplicate-ID rejections do not pollute `wouldCycle`. It is attached to the existing W5 `ItemContainer` ClassPatch (no second ClassPatch for the same class). Vanilla `TransactionManager.chainContainsContainingItem` is private and only climbs 2 levels; the helper implements the same semantics with a full 64-level upward walk (zero config; hitting the depth cap = an existing cycle or abnormally deep chain).
- **`AddItemBlind` is deliberately not hooked**: a head call there would fire before the null/capacity rejections (false positives), and Blind only does `items.add(item)` without setting the `item.container` back-link, so the upward walk could miss real cycles. 42.20.4 has zero external Java callers (Lua/reflection could still reach it); the W5 use-site guard covers it until a trustworthy mid-method hook or downward-graph check exists.
- **This version is observe-only and never rejects**: the caller census found at least four remove→add paths (`ItemContainer.transferItems`, `IsoMannequin`, `GameServer` replace, `EvolvedRecipe`); rejecting outright would make already-removed items disappear. Enforce needs a rollback design based on measured `wouldCycle/depthCapped/caller` data, and must not piggyback on `containsID=true` (misleading error plus `getItemWithID` returning null).
- `isInside` and `InventoryContainer.save` still have no cycle protection; the W5 guard remains the backstop. We cannot yet claim "cycles cannot form".

**Verification**: SmokeCheck pins, for each site: the vanilla precondition (exactly one self-recursion), exactly one redirect with the original recursion count at zero, **unchanged total instruction count** (structural proof of 1:1 replacement), original body preserved (`getParent` call count and `containingItem` access count unchanged), and a whole-class negative control (the other 27 call sites stay vanilla). Behavior tests (7 cases + kill-switch mode): **vanilla must overflow** (vanilla climbing logic on the same cycle throws SOE, proving the cycle is real); guard cuts and returns null; **positive case returns the real owner** (blocks a fake "always null" helper from passing); normal nesting never trips; diagnostics identify the closing point; depth counter resets cleanly; threshold boundary (63 does not trip / 66 trips). Tests build cycles from uninitialized objects allocated via `sun.reflect.ReflectionFactory` (the `InventoryItem` constructor pulls in ZomboidFileSystem); `dist\java` precedes the jar on the classpath, so the **redirected** methods are what is tested.

<a id="2r"></a>
## 2r. Grid-square load catcher (W6, server)

**Incident**: On 2026-08-14 01:34:56 the production main loop froze permanently at `f:46186` until a scheduled mod-update restart at 03:28 — **a 114-minute freeze, and nobody restarted it to fix it**. The process stayed alive, Steam/Discord/network threads kept running, players could connect but the world was frozen (170 disconnects during the window). The **line-for-line identical** stack had occurred once before on 2026-08-07 (culprit sprite `fencing_01_57`; this time `blends_natural_01_53`).

**Vanilla defect**: `EngineEntityManager` keeps two parallel structures — `entitySet` ("is it registered?") and `entities` (the array iterated each tick). `addEntityInternal` offsets 0–8 do `entitySet.contains(entity)`; if true, offsets 11–27 `athrow`:

```
java.lang.IllegalArgumentException: Entity is already registered <sprite>:zombie.iso.IsoObject@…
  EngineEntityManager.addEntityInternal(:137)   ← throw
  Engine.addEntity(:58) → GameEntityManager.RegisterEntity(:253)
  GameEntity.addToWorld(:527) → IsoObject.addToWorld(:4497)
  IsoChunk.doLoadGridsquare(:3973)
  ServerMap$ServerCell.RecalcAll2(:385) → Load2(:224) → ServerMap.preupdate(:969)
  GameServer.main(:972)
```

**Why a permanent livelock rather than a crash**: `GameServer.main` catches and prints the exception, but the catch is at the **top** of the loop — the rest of the tick (world update, packet processing, frame advance) is skipped, and the grid square **stays in the pending-load queue**. Next tick the same object is rejected again, every 0.1 s. The log goes quiet after 25 prints (PZ suppresses repeated exceptions), but the frame never advances again. **This is a livelock; no "restart if the process dies" protection can catch it.**

**The incident took the direct branch of `addEntity`; the `addedToEngine` guard was never evaluated** (an earlier draft misread the bytecode; corrected after two review rounds):

```
addEntity(GameEntity):
   0-21:  if (delayed.value() || bucketsUpdating.value())   →  ifeq 116
  24-47:      if (scheduledForEngineRemoval || removingFromEngine) throw
  48-71:      if (addedToEngine) { if (Core.debug) throw; return; }   ← only inside this branch
  72-113:     addedToEngine = true; enqueue into pendingOperations
    116:  addEntityInternal(entity)    ← the other path, never looks at addedToEngine
```

Evidence comes from the log's own line number: the stack records `addEntity(EngineEntityManager.java:55)` and `javap -l` shows **`line 55: 116`** — the direct call. (`delayed` = `Engine.processing`, true only inside `Engine.update()` / `simulationUpdate()` / `renderLast()`; `ServerMap.preupdate → Load2 → RecalcAll2 → doLoadGridsquare` is not among them.)

Consequences for root-cause analysis:
- No irreproducible "two engine flags disagree" corruption is needed. `addedToEngine == true` with `entitySet.contains == true` is **fully self-consistent** and still throws on the direct branch. The most likely cause is **something calling `addToWorld()` again on an object already in the world**.
- The search space shrinks from "who corrupted `addedToEngine`" (10+ classes touch it) to "who adds twice / which object is in two lists".
- It explains **why it never self-heals**: the throw is the first statement of `addEntityInternal`, **no field has been written**, so the next tick has identical state and throws again.

The W6 diagnostics therefore include **`isAddedToEngine()`** (`public final` on `GameEntity`): the first hit splits the hypothesis — `true` = plain double add; `false` = a real invariant break, and the only thing that can throw between `entitySet.add` and `addedToEngine = true` is `setComponentOperationHandler`, narrowing it to one method. Until that data point arrives, **this patch is containment plus evidence collection, not a cure**.

**Not caused by this project's patches** (javap evidence; time correlation is unusable because logs only go back to the day `FastIdentityArrayRemoval` shipped): in `addEntityInternal` the throw is at offset 27 while our redirected `entities.add` is at **offset 38**, unreachable when it throws; in `removeEntityInternal` every branch is decided by `entitySet.remove` at offset 5, and our redirected `Array.removeValue` at offset 29 is followed by **`pop` at offset 32**, so its return value cannot influence anything. `entitySet` is never touched by us.

**Patch**: `doLoadGridsquare` contains **three** `addToWorld` calls, all reaching the same throw. The first version guarded only one (so the guard missed two-thirds of trigger paths) — **both independent reviews flagged this as blocking**; `countExactCalls` filters by owner, so "only one call in the class" was a filter artifact.

| offset | site owner | loop | action |
|---|---|---|---|
| 457 | `BaseVehicle` | `vehicles` | **deliberately left vanilla** |
| 737 | `IsoObject` | `square.getObjects()` | redirected (culprit in both incidents) |
| 947 | `IsoMovingObject` | `getStaticMovingObjects()` | redirected |

`IsoMovingObject` **does not declare `addToWorld` itself** (asserted by SmokeCheck), so offset 947 dispatches to the **same method body**; wrapping it adds no semantic risk. That loop holds corpses (`IsoDeadBody`; production DeadBody IDs are in the hundreds of thousands), a likely next culprit.

**Why `BaseVehicle` is excluded — ordering, not "it has a guard"**:

```
BaseVehicle.addToWorld(Z):
   0-26: if (addedToWorld) { DebugType.Vehicle.error(...); return; }
  45-47: addedToWorld = true                              ← flag set here
  55-56: invokespecial IsoMovingObject.addToWorld()       ← throw happens after this
```

The flag store (offset 47) **precedes** the super call (offset 56), so after a throw `addedToWorld` is already true and the next tick returns early at offset 26 — **each vehicle can throw at most once**, costing one frame (~100 ms), not a 114-minute livelock. `IsoObject` has no such flag (offset 0 is the super call), so it throws forever. SmokeCheck pins **this ordering** as a structural fact: if TIS ever moved the flag store after the super call (a change that looks like a bug fix), this exclusion would silently become a live freeze path while every other assertion stays green.

**Boundary enforced by code, not by caller coincidence**: `BaseVehicle extends IsoMovingObject`, and `getStaticMovingObjects()` is not type-homogeneous (vanilla's own `getDeadBody()` / `getDeadBodys()` filter with `instanceof IsoDeadBody`). A vehicle in that list would be swallowed via the `addToWorld(IsoMovingObject)` overload, so the helper has an `instanceof BaseVehicle` pass-through, covered by a behavior test that fails if the pass-through is removed.

Helper `zombie.mdc.ChunkLoadGuard` catches **only `RuntimeException`**: `Error` (OOM / SOE / LinkageError) must stay fatal and visible — swallowing VM-level failures is far worse than a freeze. Conversely it does not narrow to `IllegalArgumentException`: the freeze mechanism is independent of exception type. SmokeCheck pins the caught type from the **exception table** (an older check looked for the `VirtualMachineError` UTF-8 constant, which the diagnostic `rethrowFatal` puts in the pool anyway, so widening to `Throwable` still passed; mutation testing confirmed the new check fails and the old one did not).

**Degradation depends on the runtime class, not one method body**: the redirected site's owner is only the static type; virtual dispatch runs the override. javap shows at least four shapes:

| runtime class | shape | what is lost when swallowed |
|---|---|---|
| `IsoObject` | super call at offset 0 (the throw point) | `createContainersFromSpriteProperties()`, each container's `addItemsToProcessItems()`, `addObjectPoweredByGenerator`, plus `GameEntity.addToWorld`'s own `addedToWorldOrEquipped = true` and `sendEntityEvent(AddedToWorld)` |
| `IsoDeadBody` | super at offset 1, **side effects after** | `CorpseCount.corpseAdded`, `FliesSound.corpseAdded`, **`ObjectIDManager.addObject`** — the corpse is not registered by ID; corpses are the main content of the offset-947 loop |
| `IsoWorldInventoryObject` | **side effect before super** | `getProcessWorldItems().add()` already ran — the guard swallows a **partially completed** state |
| `IsoGenerator` | **never calls super** | never reaches the throw; unaffected |

"No side effects before the throw" holds only for super-first shapes. This is a **consciously accepted production risk**: a 114-minute freeze costs far more than partial state on one object, and the diagnostic `class=` field identifies which shape was hit. We no longer claim the degradation is always minimal.

**Why skipping is safe — identity carries the argument**: `IsoObject` **does not override `equals` / `hashCode`** (javap-confirmed), so `entitySet` (`ObjectSet`) has identity semantics. A genuine unload → reload from disk yields a **freshly deserialized instance** whose `entitySet.contains` is false, so it **would not throw**. Hence "it threw" implies the same instance was never unregistered, i.e. no real unload happened, so the ProcessItems and generator registrations from the earlier add **are still live** — skipping loses nothing.

**But this chain assumes `entitySet` has not diverged from object lifetime.** If diagnostics report `addedToEngine=false` (real invariant break), the chain breaks and skipping leaves: furniture containers with no container (open empty), fridge/rotting items not in ProcessItems (food never rots or never chills), powered objects not attached to a generator — all silent, all lasting until restart, none logged. **That is why `isAddedToEngine()` is a mandatory diagnostic field.** The log line also states that the object's container processing and power hookup did not run for this load, so player reports can be matched.

**Diagnostics** (the main output of this patch; vanilla gives 25 identical stacks and then silence, without even the square): **square coordinates + sprite name + class + `addedToEngine` + identity hash + chunk jobType + thread name + exception**. The three decisive fields:

| Field | What it separates |
|---|---|
| `addedToEngine` (`public final` on `GameEntity`) | `true` = engine state consistent, plain double add (find "who calls twice"); `false` = real invariant break (narrowed to `setComponentOperationHandler`). **The two lead to completely different investigations.** |
| `identityHashCode` | one culprit throwing repeatedly vs many distinct objects (systemic) |
| `IsoChunk.jobType` (`public` field) | tests the hypothesis "a SoftReset job re-runs `doLoadGridsquare` on live objects" — `doLoadGridsquare` has its own SoftReset branch (offsets 835–842); currently the most concrete, testable root-cause lead |

The dedup key is **coordinates only**; details carry all fields (keying on the full detail, which includes identityHashCode, would count every instance on the same square as a new square — hit in practice and fixed).

Design points added during review:
- **Detail budget counts distinct squares, not events**: a broken square re-triggers every time a player passes; per-event counting could exhaust the 20-entry budget on one square in hours, and later squares would only overwrite `lastSite`. Whether failures cluster on specific buildings is exactly the question this patch must answer.
- **Heartbeat uses a primed flag, not a `lastHeartbeatNs = 0` sentinel**: `System.nanoTime()` has an arbitrary, possibly negative origin; then `now - 0 >= 10 min` is never true and the heartbeat never prints. W5's `reportPrimed` already did this right but its heartbeat did not — **fixed in both**. The heartbeat also prints the per-interval delta.
- **Thread name**: whether it was the WorldStreamer background thread (never froze the main loop) or the main loop (froze 114 min) is the single most operationally useful bit.
- **Distinguishable sentinels**: `square=none` / `square=getter-threw` / `square=partial(getter-threw)`, `sprite=null-sprite` / `unnamed` / `getter-threw`, plus a separate "diagnostic read failed" counter. Coordinates print only when all three axes succeed (per-axis fallback would make `7130,-2147483648,0` look like a real X).
- **Null receiver gets a different message** ("world data anomaly: square object list contains a null entry"): an unrelated, possibly worse corruption that must not be counted as another instance of this bug.
- **Startup banner** prints `enabled` and the raw property value, so admins can tell "path not covered" from "guard broken", and catch typos like `-D...=0` / `=no` that silently keep it enabled.
- **Fallback to `System.err` if `DebugLog` is fully broken** — the only fully silent failure mode (budget drained, heartbeat dead); stderr is an independent channel captured by the LinuxGSM console log.
- **The anomaly handler has a last-resort net**: if `DebugLog.log` itself is what threw, "log it again" would let the exception escape back into `doLoadGridsquare`, turning the catcher into a new freeze source (**same defect fixed in W5**).
- **`rethrowFatal` rethrows only `VirtualMachineError`**: it is used only on the diagnostic path, where a `LinkageError` (e.g. `getSimpleName()` failing on InnerClasses — exactly the risk for a bytecode-rewriting project) is a diagnostics bug, not a sign the world cannot continue; escalating it into a freeze would defeat the net (**same defect fixed in W5**).
- The main path deliberately does **not** call `rethrowFatal`: it uses `catch (RuntimeException)`, so every `Error` passes through. The two paths treat `AssertionError` differently on purpose: "the game's own operation failed" and "our logging failed" have different contracts.

**All shared state under a lock** (blocking review finding): an earlier comment claimed shared fields were only primitives/Strings, but `distinctSites` is a `LinkedHashSet` — not thread-safe; concurrent modification during a HashMap-family resize can form an internal cycle and **spin**, the very freeze this patch guards against. Unsynchronized `caught++` could also go backwards and make the heartbeat delta negative. Cost is irrelevant: the code runs only after an exception was thrown (`fillInStackTrace` alone is microseconds). Current design: diagnostic reads outside the lock (they are game getters; holding a lock over uncontrolled code is wrong), all shared state inside the lock, log output from a snapshot outside the lock.

**The banner is not proof of startup**: the helper class is loaded when the patched `IsoChunk` **first executes a guarded call site**, not when `IsoChunk` loads, and the vehicles loop runs before both redirects. No banner only means no square has reached those call sites yet; the message now says "first activation". The `<clinit>` catch originally swallowed OOM/SOE too, contradicting the "Errors stay fatal" contract; `rethrowFatal` was added there.

**`objectChunkJob` is best-effort**: it reads **the object's own chunk**, not the `IsoChunk` currently loading; if the object hangs off the wrong square/list (a possible shape of this bug), it reports another chunk's job. `identityHashCode` can collide and is not unique across restarts; `isAddedToEngine()` is a same-thread snapshot, not a view linearizable with `entitySet`.

**Kill switch**: `-Dmdc.chunkLoadGuard.enabled=false` restores vanilla exactly (including the freeze); no redeploy needed.

**Other layers considered and rejected / deferred**:

| Layer | Decision |
|---|---|
| **`ServerCell.Load2`'s `RecalcAll2()` call site** | **Deferred as a separate item — best value but not folded into this patch.** In `Load2`, `RecalcAll2()` is at offset 37 and `loaded2.remove(i)` at offset 44: **dequeue happens after the fallible work, and that ordering alone causes the livelock**, regardless of which call throws. Guarding it would close the whole livelock class (`doLoadGridsquare`'s exception table covers none of the incident point, so any throwing virtual call yields the same 114 minutes). **Cost**: blast radius two to three orders of magnitude larger — the whole cell's 8×8 chunks skipped, `loadVehicles()` skipped, cell marked done. The two are layers, not alternatives: the per-object guard keeps common wounds small; a `Load2` guard guarantees the queue always drains. |
| `EngineEntityManager.addEntityInternal` | **Rejected.** Best failure mode (`addToWorld` would finish containers / ProcessItems / generator, which W6 skips) and covers every caller, but the widest semantic change; the method is package-private, so the helper would have to live in `zombie.entity` instead of `zombie.mdc`, breaking the project convention that all helpers live in `zombie/mdc`. |
| `RecalcAll2` layer | **Rejected.** Strictly worse than `Load2` — dequeue is at `Load2` offset 44, so catching inside `RecalcAll2` **does not fix the livelock**. |
| `GameServer.main` layer | Already exists, and is the livelock generator: without dequeueing, catching changes nothing. |
| External frame-stall watchdog | Complementary, not a replacement; costs a full server restart (all 50–100 player sessions). Lowest value for this class, but still worthwhile for **unknown** stalls. |

**⚠ Known residuals**:
1. The `BaseVehicle` site (offset 457) is still a live throw path, but by the ordering argument **each instance throws at most once** (one lost frame). Collateral: that vehicle stays `addedToWorld=true` without `createPhysics()` / `parts.addToWorld()` and **never retries** — a vehicle permanently without physics and parts. Worse than skipping one object, but bounded.
2. **New steady-state cost**: a broken square goes from "throw once then freeze" to "throw on every load, forever". `fillInStackTrace` at this depth is ~1–5 µs times the chunk load rate. Still far better than a freeze; the heartbeat delta lets admins size it.
3. Root cause not located. **Do not treat this class of freeze as eliminated** — but the next hit's `addedToEngine` field points the way.
4. Only `doLoadGridsquare` is guarded. A main-loop stall from any other unknown cause (e.g. the SOE before W5) still freezes silently until the next scheduled restart; a `Load2` guard and a frame-stall watchdog are complementary investments (the 114 minutes happened because nothing was watching).

**Verification**: SmokeCheck pins: vanilla preconditions (one call per owner, `IsoMovingObject` declares no `addToWorld`); each redirect exactly once with the original calls at zero; **unchanged total instruction count**; the `BaseVehicle` scope declaration; the **two-step `BaseVehicle` exclusion precondition** — first that `addToWorld()V` delegates to `(Z)V` (the unguarded site calls `()V` but the flag logic is in `(Z)V`), then within `(Z)V`: a single `addedToWorld=true`, a single super call, the store before the super call, and the stored value is **`ICONST_1`** (storing `false` would pass the ordering check but disable the early return). Uniqueness is a poor man's CFG dominance check; full dominance analysis was judged too heavy (residual: a theoretical branch bypassing the flag). Also: a **position anchor** (the next call after the redirect must be `getSprite()`, pinning the tile loop rather than the corpse loop — otherwise redirecting the wrong site with the same count would pass); original body preserved (`getSprite` / `getPipedFuelAmount` counts unchanged **and non-zero**); negative controls as **deltas against vanilla** (absolute zero would false-alarm if PZ adds same-named calls elsewhere); main catch type locked.

Behavior tests (16 cases + kill-switch mode): stand-in must throw (negative control); guard swallows; **positive case really enters the world** (blocks an empty helper); corpse-loop overload; **vehicles must not be swallowed** (fails if the `instanceof BaseVehicle` pass-through is removed); coordinate reporting incl. `addedToEngine` / identity / jobType; **output actually lands in a real `DebugLogStream`** (previously all forensic assertions read package-private test fields, so deleting the log line stayed green); logger throwing `RuntimeException` does not escape; **logger throwing `LinkageError` does not escape** (previously the probe could not inject an `Error`, so `rethrowFatal` was dead code to the tests); **heartbeat actually prints** (previously only counters were asserted); **budget counts distinct squares** (50 hits on one square cost one entry, another square still gets one); anomaly path; `Error` not swallowed; half-initialized getters (each sentinel pinned plus the failed-read counter); null receiver (different in each mode, both pinned); detail budget pinned to `MAX_REPORTS`. The kill-switch run passes a `disabled` argument so the test asserts the switch took effect (checking only the exit code would let a misspelled property rerun the enabled path and still exit 0).

**Mutation results** (both passed green before the fix): `catch (RuntimeException)` → `catch (Throwable)` ⇒ `struct FAIL` on the main-catch-type lock; deleting the production log line ⇒ "expected exactly one line, got 0".

<a id="2s"></a>
## 2s. Thread-isolated forward-direction temp vector (W7, server)

**Incident**: On 2026-08-13 19:55:03, Player-A's hutch and the adjacent water bucket vanished. A chunk failed to load 4 seconds after a restart and was wiped and regenerated by vanilla `Blam + LoadBrandNew`: **46,142 bytes → 8,549 bytes**; the hutch, 32 poultry with full genomes, and a `Base.Bucket` were destroyed, leaving only grass. (The full forensic report is kept privately; all its technical conclusions are included here.)

```
Error loading chunk <x>,<y>
java.lang.RuntimeException: java.lang.IllegalStateException:
    Forward Direction cannot be zero length vector.
  IsoGameCharacter.setForwardDirection(:2827)   ← throw
  IsoGameCharacter.setForwardDirectionFromIsoDirection(:5104)
  IsoAnimal.load(:1536) → IsoHutch.load(:953)   ← hens in the hutch being deserialized
  IsoGridSquare.load(:3275/3281)
  IsoChunk.LoadFromDisk → LoadOrCreate(:2353) → LoadChunk(:2332)
  ServerChunkLoader$LoaderThread.run(:70)       ← background thread
```

**Vanilla defect**: `setForwardDirectionFromIsoDirection()` uses a **JVM-wide shared** `private static final Vector2 tempVector2_2` as scratch:

It first calls `getVectorFromDirection(tempVector2_2)` (① writes the shared static), then `setForwardDirection(tempVector2_2)` (② reads it back and normalizes it).

`IsoMovingObject.getVectorFromDirection(Vector2, IsoDirections)` first **zeroes x and y**, then fills in the real direction. The main thread (every tick, for zombies/animals/players) and `ServerChunkLoader$LoaderThread` run this concurrently with no synchronization: if one reads during the other's zeroed window it gets (0,0), `normalize()` sees length 0 and throws.

**The save was not corrupt**: `IsoDirections.fromIndex(int)` is `VALUES[index & 7]` and all 8 directions have non-zero vectors, so no stored direction can produce a zero vector. The failure is a pure race — **the key premise for restoring directly from the `blam/` backup**.

**Not caused by this project** (three independent checks): (a) the production jar's sha256 matches the decompile-snapshot source byte for byte; (b) `IsoGameCharacter` / `IsoMovingObject` / `IsoChunk` / `IsoGridSquare` / `IsoHutch` on the crash path are not among our loose-class overrides and load straight from the jar; (c) the only patched class on the stack, `IsoAnimal`, has a `load()` identical to vanilla instruction-for-instruction (411 instructions, after constant-pool normalization); its four patches are all in `updateStress` / `respondToSound` / `killed` / `updateLOS`.

**Patch**: `FieldGetSwap` (extended in this change to accept `GETSTATIC`; previously `GETFIELD` only) inserts an `INVOKESTATIC ForwardVectorGuard.swap` after each of the two `getstatic tempVector2_2` in the method — it consumes the shared instance and returns a thread-private substitute. The vanilla body is 8 instructions, no branches, no frames:

```
 0: aload_0 / 1: getstatic tempVector2_2 / 4: invokevirtual getVectorFromDirection
 7: pop / 8: aload_0 / 9: getstatic tempVector2_2
12: invokevirtual setForwardDirection / 15: return
```

Stack effect of each insertion is 1→1 (the simplest patch shape). Within a thread, write ① and read ② see the same instance, so semantics are identical to vanilla; threads no longer see each other's values.

**Shared contents are deliberately not copied**: `getVectorFromDirection` right after site ① unconditionally overwrites x and y, and site ② reads what site ① wrote into the private instance. Copying shared contents would only import another thread's dirty values.

**Coupling check for the removed side effect**: after the patch this method no longer leaves a value in the shared instance. The class has 12 `getstatic tempVector2_2` (plus one `putstatic` in `static {}`); this method accounts for 2, and the other 10 were each verified to **write before reading**, so nobody depends on the leftover value:

| Method | Count | Usage |
|---|---|---|
| `processHitDamage` | 2 | `.set(wielder coords)` → `getVectorFromDirection(tempVector2_2)` |
| `renderlast` | 4 | reads `.x`/`.y` only after `getNameCoordForPlayer` / `getNameCoords` fill it |
| `isObjectBehind` | 1 | `.set(this coords)` |
| `isBehind` | 1 | `.set(chr coords)` |
| `updateMovementStatistics` | 2 | `.set(this coords)` → `distanceTo(tempVector2_2)` |

SmokeCheck pins the class's `getstatic` total at 12 — any new reader added by TIS forces this check to be redone.

**Scope (deliberately excluded)**: across all retained logs the same exception appears **67 times**:

| Location | Count | Outer handling | Consequence |
|---|---|---|---|
| `IsoAnimal.load` ← `IsoHutch.load` (chunk loader thread) | 1 | `Blam + LoadBrandNew` | **whole chunk wiped** |
| `VirtualZombieManager.createRealZombieAlways` (main thread) | 66 | `IngameState.UpdateStuff` try | one lost tick, no data loss |

The latter is a **separate** race on **`IsoDirections.TEMP`** (`ToVector()` returns a shared static instance; callers then do `temp.x += rand; temp.y += rand; temp.normalize()`), not covered here. `IsoDirections` is a high-traffic core enum with a very different blast radius; to be evaluated separately after this patch has run in production.

**`ThreadLocal` is bounded**: vanilla has one static-final `ServerChunkLoader` whose constructor starts **one** fixed `LoaderThread` (not a thread per chunk), so each long-lived thread holds one 8-byte `Vector2`, not accumulating per job. The helper's `<clinit>` only creates the supplier and the `ThreadLocal`; `Vector2::new` runs on each thread's first `get()`.

**Build-time checks** (11 SmokeCheck items, including 3 behavior smokes on real threads):
- Vanilla precondition: full body sequence = 8 instructions; exactly 2 `getstatic tempVector2_2`; **owner/name/desc of both `invokevirtual`s pinned once each**.
- After patch: full sequence = two `getstatic → swap → invokevirtual` groups; 2 swap redirects with 2 `getstatic` retained; **both `invokevirtual` targets untouched**.
- Negative control: no swap redirects elsewhere in `IsoGameCharacter`.
- Coupling lock: class `getstatic` total = 12, same before and after.
- Helper behavior: returns non-null and not the passed instance / same instance twice on one thread / **different instances across threads**.

The two `invokevirtual` target pins close a fail-open gap found in review: `matchOpcodeSeq` compares opcodes only (operand-blind), so if PZ kept the opcode shape and both reads but changed the call target, the sequence check would stay green, violating the "any rewrite of the method fails the build" contract. In the same round, `Patcher.FieldGetSwap`'s compact constructor was restricted to GETFIELD/GETSTATIC — passing a PUT opcode would insert the helper on an already-consumed value, which the verifier is not guaranteed to reject.

**Whole-class blast radius**: diffing full javap of vanilla vs patched (constant pool normalized) shows **zero instructions removed and exactly two `invokestatic ForwardVectorGuard.swap` added**; all other differences are `ldc`↔`ldc_w` encoding swaps and the resulting offset shifts.

**No reentrancy** (why the same thread cannot overwrite the private instance between the two sites): between them runs only `getVectorFromDirection(Vector2, IsoDirections)`, a static pure switch with no callbacks; the `getForwardIsoDirection()` it relies on is **declared once** in the whole tree (`IsoObject`, no overrides), and `setForwardIsoDirection(IsoDirections)` exists only in `IsoObject` and `IsoGameCharacter` (verified by whole-tree grep). `setForwardDirection(Vector2)` copies `dir.x` / `dir.y` into `this.forwardDirection` and **keeps no reference**, so the private instance never aliases into character state.

**Degradation if the helper fails to load**: the most dangerous hypothetical is the patch itself becoming a save-wiping source. It **cannot**: `NoClassDefFoundError` / `LinkageError` are `Error`s, and `IsoChunk.LoadOrCreate`'s failure branch is `catch (Exception var7)`, which **does not catch Errors**. With the helper missing, the error propagates through `LoadOrCreate` → `LoadChunk` without running `Blam()` / `LoadBrandNew()` / `BackupBlam()`, and kills `ServerChunkLoader$LoaderThread` — **a loud stop of chunk loading, not a quiet mass wipe**. This scenario is also blocked before going live by three gates: the build's manifest-completeness check (any unregistered class in `dist\java` aborts the build), `install.sh`'s fail-closed payload preflight, and the startup health check (post-deploy checklist item 11a).

**Performance**: `setForwardIsoDirection` / `setForwardDirectionFromIsoDirection` have 48 call sites in the tree and **no unconditional per-tick path for characters** (the render-loop calls are `IsoMannequin`, which uses the `IsoObject` version, not this method); it runs only on turning, spawning and loading. `ThreadLocal.get()` costs ~1–2 ns more than `getstatic`, unmeasurable at this frequency.

**No counters** (deliberate): this is the only helper called concurrently from multiple threads, so a static counter would itself be a race; a stronger validation signal already exists (post-deploy checklist item 11).

**No kill switch** (deliberate, departing from the W4-1 / W5 / W6 convention): those patches **change behavior** (batching changes send groups, the guard swallows exceptions, the catcher skips world entry), so a switch lets admins turn one off on suspicion without uninstalling everything. W7 is **semantics-preserving**: the whole-class javap multiset diff proves zero deletions and only two added instructions, and same-thread behavior is identical to vanilla. More importantly, "turning it off" means **putting the shared static back, i.e. restoring the save-wiping race** — a switch that only hurts. The correct exit is `uninstall.sh` (whole package). The one scenario a switch could not rescue (helper fails to load) does not need one: as analyzed above, it is a loud thread death, not data loss.

<a id="2t"></a>
## 2t. Chunk write gate (W8, server, default enforce)

**Incident family**: on the production server, **43 chunks** failed `SANITY CHECK FAIL` (CRC/length mismatch) on load and were wiped and regenerated by vanilla `Blam + LoadBrandNew`, losing ~143 KB of player-built data in total, and it kept happening (8 in one day on 2026-08-14). Example: Player-B's base storage (chunk <x>,<y>, 28,401 → 5,470 bytes). Same destruction path as the Player-A case in 2s, **but a different cause** — W7 does not cover it.

**Forensic findings (three key facts)**:

1. **The load side is innocent**: in 43/43 log lines, `load=` equals the body CRC we computed from the on-disk file and `save=` equals the file header field. The game read exactly what was in the file; SanityCheck correctly detected a genuinely broken file. The corruption happens **at write time**.
2. **Two signatures**: group A (16 files) has header CRC=0, correct len, and a complete self-consistent body (captured between the two adjacent "back-fill len" and "back-fill crc" statements at the end of `Save()`); group B (27 files) has a header CRC that belongs to a different body (write and refill torn). Group A is 100% recoverable by rewriting the 8 header bytes.
   **Since 42.21 the placeholder changed**: `Save(ByteBuffer,CRC32,Z)` writes the header CRC placeholder as `putLong(-1L)` instead of `putLong(0L)` (len placeholder is still 0). A 42.21 "torn before back-fill" file therefore shows CRC=-1 (`0xFFFFFFFFFFFFFFFF`); treat it as group A, not as group-B garbage, when reading BLOCKED logs and `blamguard/` dumps.
3. **Healthy chunks have correct CRCs** (25/25 sampled) — this is a per-write race, not a systematic "CRC never written".

**Root-cause status at the time: mechanism not proven** (recorded to avoid re-investigation):
- ~~Race on shared load-side statics (`sliceBufferLoad`/`crcLoad`)~~ → disproved by byte-level reconciliation of 43/43.
- ~~`ChunkSaveWorker` pooled buffer vs. `AddHotSave` refill~~ → signature fits, but hot-save enqueue is gated by `!GameServer.server` (`IsoChunkMap.updateInternal`); the server never takes it.
- Lead suspect: the **cross-player static object pool** of `ClientChunkRequest.Chunk` — pending write vs. refill (while `ServerChunkLoader$SaveLoadedTask.save` writes `chunk.bb`, the same instance may be re-queued and refilled). Not proven line by line. (Later proven otherwise — see 2u.)
- **Interaction with W4-1**: after W4-1 shipped the rate went 0.30 → 0.80 per restart (2.7×; only 10 restarts, one abnormal day). Undecided; the gate's BLOCKED stacks identify the write path directly, which is faster than an A/B toggle, so W4-1 stayed on.

**Gate design (independent of root cause)**: every chunk file write converges on `IsoChunk.SafeWrite`, so we validate on that single bridge regardless of who corrupted the buffer upstream. **Snapshot → verify → pass/block**:

1. Copy the live buffer into a thread-private array (closes the TOCTOU between verify and write — the verified bytes are the written bytes).
2. Check header len == actual length and header CRC == CRC computed over the body. Both always hold when `Save()` completes normally, so a mismatch is 100% upstream corruption — **no room for false positives**.
3. Pass → hand the verified snapshot to vanilla `SafeWrite` (locking, sanityCheck, directory creation all vanilla).
4. Fail → **skip the write** (the previous good file stays on disk), log BLOCKED with a full stack for the first 10 events (to identify the culprit path), dump the corrupt buffer to `blamguard/` (max 16 files, sequence number in the name to avoid same-millisecond overwrite), and call `ChunkChecksum.setChecksum(wx,wy,0)` so the next save's checksum comparison is guaranteed to mismatch and rewrite.

**Retry semantics (stated precisely; "guaranteed self-heal" was an overclaim)**:
- **Chunk still loaded** (`SaveLoadedTask` path, periodic saves): the live `IsoChunk` is still in the world; the next `SaveWorldEveryMinutes` cycle re-serializes it and the zeroed checksum forces a rewrite. True self-heal.
- **Final save on unload/quit is blocked**: `SaveUnloadedTask.release()` then hands the `IsoChunk` to the reuser and the in-memory content is gone — **the chunk falls back to its last successfully written version; there is no retry**. Loss is bounded by changes since the last good save (≤ `SaveWorldEveryMinutes` = 30 min in normal operation). Vanilla would have written the corrupt buffer and Blam'd the chunk on next load, losing everything. Strictly better, but not zero-loss.
- **Deliberately no group-A header repair**: with pooled buffers the body may belong to another chunk; stamping a correct CRC would launder cross-chunk contamination into a file that passes verification. Refusing to write is the only conservative choice.

**Hook points (safety-critical)**: we redirect the three call sites rather than hooking inside `SafeWrite`, because its `new FileOutputStream(outFile)` truncates the old file on construction — an internal intercept would be too late to keep the previous version. The whole jar has exactly **5** `SafeWrite` call sites (SmokeCheck census; any new one fails the build):

| Call site | Action | Reason |
|---|---|---|
| `IsoChunk.Save(Z)` ×2 | **redirect** | main server world-save path |
| `ServerChunkLoader$SaveLoadedTask.save` ×1 | **redirect** | chunk shipping/save path (lead suspect) |
| `ChunkSaveWorker.WriteQueuedSave` ×1 | unchanged | only enqueue point `AddHotSave` is gated by `!GameServer.server` (SmokeCheck pin) |
| `WorldGenerate` ×1 | unchanged | only writes freshly generated chunks (method-local buffer); nothing player-made to lose |

**Trade-offs**:
- Skipping a write freezes that chunk's disk version at the previous one until the next rewrite; "old but valid" beats "Blam'd", and the zeroed checksum forces the retry.
- The gate cannot catch a buffer **completely** refilled with another chunk's self-consistent data (the header has no coordinates). All 43 observed cases were inconsistent and would be blocked; the residual case is much narrower. (Closed by 2u's private pool.)
- verify returns MALFORMED for len ≤ 17: an empty body has CRC=0 and would falsely match a 0 CRC field. This also blocks the "truncate then write zero bytes" wipe scenario.

**Failure discipline**:
- **Unwritable buffer (null / not a heap array) → refuse to write in every mode**: vanilla would truncate the old file in the `FileOutputStream` constructor and then throw, replacing a good file with an empty one. Observe mode's "no behavior change" yields to not destroying files.
- **Internal guard fault (RuntimeException after the buffer is confirmed valid) → fail open to vanilla**; a guard bug must not stall all saving.
- **RuntimeException and LinkageError from log/dump infrastructure are always swallowed** (W6 lesson) and never escape into the save path.

`MODE`: `-Dmdc.chunkWriteGuard=0` off (zero-cost passthrough) / `1` enforce (default) / `2` observe — verifies, logs and dumps as usual but always writes the live buffer. Observe caveats: the log says `FLAGGED`, not `BLOCKED`; the `blamguard/` dump is the snapshot at verification time and the live buffer actually written may differ.
Cost: hardware CRC32 on ≤64 KB ≈ 30 µs; worst case 200 chunks/s < 1% of one core.

**Build-time checks** (19 SmokeCheck items, 5 added after review to prevent false greens):
- verify behavior smoke in four cases: consistent → OK, **real group-A signature → CRC_MISMATCH**, tampered len → LEN_MISMATCH, truncated → MALFORMED; `resolveMode` for all four values.
- **Execution-level smoke of `safeWrite` itself** on three decision paths: corrupt buffer blocked silently with checksum actually zeroed; null buffer refused; consistent buffer really delegated to vanilla (it throws in the test environment, which proves the write path was reached).
- Vanilla preconditions: `SafeWrite`/`setChecksum` counts in both methods plus an **ordering pin (`setChecksum` before `SafeWrite`)**; census total 5 plus **per-class distribution** (so a new and a removed call site cannot cancel out); hot-save gate **`getstatic → ifne` direction pin**; format offsets pinned **in context** (17 → `CRC32.update`, 5 → `ByteBuffer.position`, not just the constants existing).
- Post-patch: redirects present, original calls gone. Negative controls: exclusion pinned to the exact signature `Save(Z)V` (other `Save` overloads are checked too); no recursion inside `SafeWrite`.

**Restoring historical losses** (done separately, only after the gate went live so restored chunks could not be hit again): group A (16) is restorable by rewriting the header CRC; group B (27) bodies may be torn mixtures and need per-file analysis. On 2026-08-14 all 16/16 group-A chunks were restored, including Player-B's base (<x>,<y>).

<a id="2u"></a>
## 2u. Save pipeline isolation (W9, server; CRC parts retired in 42.21 — fixed upstream by TIS; private pool still active)

**Root-cause fix.** On its first night W8 blocked 8 corrupt writes (zero data loss), and the caught-in-the-act evidence moved the root cause from suspected to proven:

- **8/8 identical call stacks**: `SaveChunkThread → SaveLoadedTask.save` (zero events on the direct `IsoChunk.Save(Z)` path).
- **8/8 identical signatures**: correct len; CRC field = 0 (3) or garbage (5) — the same two signatures as the historical 43 (A 16 + B 27).
- **Mechanism** (bytecode evidence): `Save(ByteBuffer,CRC32,Z)` computes the header fingerprint with the **caller-supplied CRC32**, and the serialization entry `SaveChunkThread.addLoadedJob` passed a **single shared instance** `SaveChunkThread.crc32`. With two threads serializing concurrently, the other thread's `reset()` landing between my `update` and `getValue` yields **0** (group A); interleaved `update`s yield **garbage** (group B). Body and len are written completely by each thread, so **len is always correct** — the only mechanism consistent with all 8 observations (the buffer-theft hypothesis cannot explain always-correct len, and the unpack double-return path was proven dead code by a jar-wide census).
- **Concurrent serialization proven**: `QueuedSaveAll` runs on `GameServer$1` (shutdown-hook thread) and enters `addLoadedJob` concurrently with main-loop `ServerCell.update → saveChunk`. This explains why historical Blams cluster around restarts (0.8 per restart). The second thread in mid-session bursts (two waves clustered with `growing ByteBuffer` resize events) was not individually identified; the fix does not depend on it — any concurrent caller is isolated by ThreadLocal.
- **A second race of the same shape**: the dedup comparison in `SaveLoadedTask.save()` reads the outer shared `ServerChunkLoader.crcSave` four times, and `save()` can run concurrently on `SaveChunkThread` and `LoaderThread` (via `saveNow`, flush-before-load; live at `LoaderThread.run` offset 214, not dead code). This pollutes `ChunkChecksum` (false dedup = stale skipped write; client checksum confusion = resends, possibly the same root as the black-edges "crc always 0" case).

**Three patches** (helper `zombie/mdc/ChunkSaveIsolation`; save pipeline only):

1. `addLoadedJob`: GETFIELD `crc32` → `headerCrc` (ThreadLocal) — removes the fingerprint race. *(retired in 42.21)*
2. `SaveLoadedTask.save()`: GETFIELD `crcSave` ×4 → `dedupCrc` (ThreadLocal) — removes the dedup race. *(retired in 42.21)*
3. `getChunk` / `getByteBuffer` / `releaseChunk` (rent + exception-return in `addLoadedJob`, return in `release()`) → private — the save pipeline fully leaves `ClientChunkRequest`'s global static pools (`freeChunks` private static / `freeBuffers` **public** static, shared with N `PlayerDownloadServer` WorkerThreads and `RequestZipListPacket.parse`). This also closes W8's theoretical blind spot (the pool handing the same buffer out twice, which is fully refilled with another chunk's self-consistent data and passes CRC verification) — with a private pool that path physically cannot exist.

**Private pool semantics (exactly-once, tightened after adversarial review)**: `Chunk` shells are **never pooled** — always `new`. Vanilla `update()` returns tasks via the unsynchronized `savedChunks` ArrayList; when the main loop and the shutdown hook run `updateSaved` concurrently, the same task can be released twice, and a pooled shell would be rented twice — recreating the race inside the private pool. (This also shows the vanilla global pool has the same double-return hole, a possible second mechanism for mid-session bursts.) Buffer return uses an atomic take under `synchronized(c)` (the second release sees null = no-op). The private buffer pool is bounded (soft cap 256 buffers, ≤256 KB each; excess left to GC) — the vanilla global pool is unbounded, and the `clear()` in `sendLargeArea` was shown by census to be dead code.

**Verification loop**: W8's `flagged` counter is a ready-made A/B meter — it should drop to zero with this fix; if not, another branch exists and the BLOCKED stacks lead the way. W8 stays as a permanent fuse.

**Kill switch**: `-Dmdc.chunkSaveIsolation=0` disables everything (helpers delegate unchanged to the shared instance/pool; the off-path bytecode is the vanilla call, pinned by SmokeCheck).

**Build-time checks (original, 42.20)**: 15 SmokeCheck items plus a separate-JVM off test. Behavior (6): `headerCrc` differs per thread; `dedupCrc` is separate; mechanism anchor — minimal repro of shared CRC32 externally reset → 0 and interleaved update → garbage; private pool fresh shell + buffer reuse; isolation — global pool counts unchanged; **double-release idempotent** (release twice, pooled once). Structural (9): vanilla shape of the three methods; **jar-wide coupling pin** (total readers of both CRC32 fields = pinned locations; a hard-coded class list would miss new nestmates); **serializer roster** (exactly 2 `SaveLoadedChunk` jar-wide, per-class); post-patch **swap adjacency** (helper immediately after GETFIELD) and originals gone; `SaveChunkThread` negative control; off-path bytecode fidelity. Build step 9d **actually executes** the off branch in a JVM started with `-Dmdc.chunkSaveIsolation=0` (CRC identity, global-pool identity marker, private pool unused) so the off branch never runs for the first time during an incident.

**Not covered (known vanilla defects, reported for TIS)**: `PlayerDownloadServer.update` send-side serialization still uses the shared buffer pool (its CRC32 is per-connection and main-thread only, analytically safe); send-side pool pollution would be caught by the client CRC check and re-requested, never written to disk (black-edges/resend observation belongs to W4-1). `saveNow` queue reordering (old/new saves of the same chunk can swap) and a task leak on the `run()` exception path are separate low-impact vanilla bugs; not patched.

### 2026-09-28 — 42.21 re-validation

**Both CRC patches retired (fixed upstream by TIS).** 42.21.0 removed every shared CRC32 from the save pipeline; each call now uses a local `new CRC32()`, so there is no shared instance to race on:

- Fields: 42.20.4's `ServerChunkLoader.crcSave`, `SaveChunkThread.crc32`, and `IsoChunk`'s static `crcLoad`/`crcSave` no longer exist in 42.21 (`javap -p` comparison).
- `SaveChunkThread.addLoadedJob`: 42.20.4 had `getfield crc32` at offset 35; 42.21 has `new CRC32` at 32, `astore_3` at 39, and passes that local to `SaveLoadedChunk(Chunk,CRC32)` at 43.
- `SaveLoadedTask.save()`: 42.20.4 had four `getfield ServerChunkLoader.crcSave` at 22/32/64/95; 42.21 has `new CRC32` at 18, `astore_3` at 25, and all later update/getValue read the local.
- `IsoChunk.Save(ByteBuffer,CRC32,Z)` still fills the header from the parameter CRC32; callers pass a local, so the fingerprint has no shared state.

The `crc32 → headerCrc` and `crcSave → dedupCrc ×4` shape-preserving swaps were deleted (target fields gone), together with both ThreadLocals. Hit counts: `SaveLoadedTask.save` 5→1 (only W8's `SafeWrite` redirect remains), `addLoadedJob` 4→3, `release()` stays 1. The first-activation banner moved from `headerCrc` to the private pool's `getChunk`; live verification is still `grep ChunkSaveIsolation` for the "first activation" line.

**Patch 3 (private pool) kept.** In 42.21 `ClientChunkRequest`'s two global static pools (`freeChunks`/`freeBuffers`) and `SaveChunkThread.update()` are unchanged: `update()` still `release()`s each entry of the `savedChunks` field with no lock. When the main loop (`ServerMap.postupdate → updateSaved`) and the shutdown hook (`QueuedQuit → QueuedSaveAll → SaveAll` polling `updateSaved`) run together, a task can be released twice and the global pool can rent the same shell/buffer to two owners (one possibly a send-side WorkerThread). W8 blocks inconsistent writes but not "buffer fully refilled with another chunk's self-consistent data"; the private pool removes that path at near-zero cost.

**SmokeCheck re-pinned**: removed the crc32/crcSave preconditions, jar-wide coupling pin, swap adjacency and CRC behavior smoke. Added "retirement preconditions" (`addLoadedJob`/`save()` each have exactly one `new CRC32`; `ServerChunkLoader`/`SaveChunkThread`/`SaveLoadedTask`/`IsoChunk` have zero CRC32 fields — goes red if TIS reintroduces a shared instance; revive the two patches with `git checkout 8d2bee8`) and "reason for patch 3" (`update()` is unlocked and reads `savedChunks`; both `ClientChunkRequest` pools are still static — goes red if TIS adds a lock or per-instance pools, prompting re-evaluation). Negative control: `save()` has zero `ChunkSaveIsolation` calls. Build step 9d's off-path test now covers only the three pool helpers.

**Verification loop unchanged**: W8 `flagged` should stay 0 on 42.21; non-zero means a mechanism covered by neither the upstream fix nor the private pool — check the BLOCKED stacks.

<a id="2v"></a>
## 2v. Log-noise suppression #8 — toxic-building log redirect (server)

**Root cause**: the PSR mod (Plysken Solar Revolution) calls `IsoBuilding.setToxic(false)` unconditionally every game minute (~2.5 real seconds) while iterating all power banks, which flips buildings' toxic state. The server's `GameServer.sendToxicBuilding` broadcasts every change to all 63 players. Measured before suppression over 15.41 h / 8 sessions: **164,176 toxic lines out of 360,669 console lines = 45.5%** (35.5%–80.8% per session, 17–25 distinct coordinates), drowning real errors. **Deduplicating by `(frame, building, value)` would remove only 19.8%**: 96,451 `(frame, building)` pairs occur once and 30,982 twice — the bulk is the same building re-sent every 2.5 s across frames, not duplicates within a frame.

**Why only the log, not the packet**: the client (`WorldRegionToMetaGrid.lambda$updateSquares$0`) independently decides "this room has an activated generator" and marks `toxic=true` locally **without telling the server**. Server-side broadcast dedup could lock players inside a damaging toxic room — the client thinks it is toxic, the server thinks not, and the player takes damage with no warning. Key insight: the server's `isToxic` is only an echo of "what was last sent", not the real state. **Filtering the log is the only safe change.**

**Patch**: in `zombie/network/GameServer.sendToxicBuilding (IIZ)V`, the `INVOKESTATIC zombie/debug/DebugLog.log:(Lzombie/debug/DebugType;Ljava/lang/String;)V` at offset 11 is redirected to `zombie/mdc/LogFilter.logType`, expectedHits = 1. The message is built by the `invokedynamic makeConcatWithConstants` at offset 6 (recipe `Send Toxic Building at [ \u0001 , \u0001 Toxic: \u0001 ]`); coordinates and boolean vary, so matching must use `startsWith`, not `equals`.

**Why it is safe**:
- **Method-scoped**: `GameServer` has 21 `DebugLog.log` calls with this descriptor; exactly 1 is in this method. The other 20 stay vanilla.
- **Broadcast untouched**: the `putInt` sequence and the `udpEngine.connections` broadcast are fully preserved; every player still receives the latest toxic state — only the console is quieter.
- **Client untouched**: no client jar change; `receiveToxicBuilding` runs as usual.

**Known cost**: the server console loses a liveness signal that clients are receiving broadcasts. The symmetric client-side line `Receive Toxic Building at [ ... ]` in `GameClient.receiveToxicBuilding` is unchanged.

**SmokeCheck**: vanilla preconditions (exactly 1 `DebugLog.log(DebugType,String)` in the method and the `PacketType.send` packet section present); post-patch (redirect ×1, original call gone, `send` and `putInt` counts equal to vanilla); negative controls (class-wide 21→20 remain vanilla, helper called exactly once).

---

<a id="2w"></a>
## 2w. Ingredient weight memoization (retired: 2026-09-02, never enabled beyond observe)

Redirected `InventoryItemFactory.CreateItem` inside `InventoryItem.getExtraItemsWeight ()F` (which builds a full `InventoryItem` per `extraItems` entry just to read `getActualWeight()`, reached every tick per player via the `Moodle.Update` HEAVY_LOAD path) to a memo helper with `-Dmdc.itemWeightMemo=observe|on|off`. The observe run (2026-08-17, 4 sessions / 9.68 h) measured a 99.997% hit rate, 271–732 calls/s and ~2.1 µs per vanilla construction — a gain of only **0.06–0.18%** of the main-loop budget (≈0.006–0.018 fps). `on` would shift the global RNG sequence (skipped `Rand.Next` calls) and would be the first real exercise of shared-instance reuse, so the risk outweighed the gain; `on` was never enabled and the patch was removed to save its patch surface and per-update re-validation cost.
Lessons kept: hit rate is a ratio, not a gain — absolute benefit = hit rate × call rate × per-call cost; and each patch's sample window must start at its own first-activation banner, not borrow another analysis's window.
Revive: restore from the last pre-retirement commit `13650e1` (`git checkout 13650e1 -- <files>` plus the matching PatchConfig / SmokeCheck / build.ps1 sections).

---

<a id="2x"></a>
## 2x. Stuck timed action fix (W10, server; W10-A retired in 42.21 — fixed upstream by TIS; B and D1 active)

> **W10-A retired (2026-09-28, fixed upstream in 42.21.0)**: `NetTimedActionPacket.processServer` now sends both replies via `act.write` (javap offsets 81/142 are `aload_3`), and `Action.write/parse/copyFrom` always carry the playerId, so the client can claim the initial Accept/Reject. Patch B (Lua constructor fuse) and D1 remain; see "42.21 re-validation" at the end.

**Symptom**: in MP the progress bar reaches 100% and stops, the action animation keeps looping ("character keeps fiddling with their hands"), no product is created, consumed inputs keep their green job marker, and **every queued action behind it is blocked** (`ISTimedActionQueue` is head-only; if the head never pops, everything stalls). Players reported three scenarios: crafting, crafting after moving furniture, and "eating/reading/crafting at random". The usual workaround was reconnecting.

**Production evidence** (2026-08-23, one session's `server-console.txt`):

| Lua constructor | Line content | Hits | Matching player report |
|---|---|---|---|
| `ISMoveablesAction.lua:308` | `item:getWorldSprite()` (`mode == "place"`) | 6 | "can't craft after moving a barrel" |
| `ISReadABook.lua:492` | `SkillBook[item:getSkillTrained()]` | 3 | "reading" |
| `ISEatFoodAction.lua:298` | `item:getContainer() or ...` | 3 | "eating" |

All three fail with `attempted index: <getter> of non-table: null` and the same stack:

```
se.krka.kahlua.vm.KahluaThread.tableget:1430
Lua(Vanilla).new(ISReadABook.lua:492)
se.krka.kahlua.integration.LuaCaller.protectedCall:109
zombie.core.NetTimedAction.parse                    <- aborted here
zombie.network.packets.INetworkPacket.parseServer:55
zombie.network.PacketTypes$PacketType.onServerPacket:967
zombie.network.GameServer.mainLoopDealWithNetData:1611   <- swallowed by catch
zombie.network.GameServer.main:909
```

The same session also had 21 `SyncItemFieldsPacket.parse:383` NPEs (`InventoryItem.hasSharpness() because "item" is null`) — same source, different packet (no stuck action, but item state desyncs).

### Root cause: two vanilla defects compounding

**Defect 1 — a silent null reaches Lua.** `InventoryItem` arguments travel as "container ID + item ID". `PZNetKahluaTableImpl.loadInventoryItem` parses a `ContainerID`, reads the item id, and returns `container.getItemWithID(id)` — or **null, with no log and no rejection**, if the container is not found or does not contain that id. The null becomes an element of the `arguments[]` built by `NetTimedAction.parse` and is passed to Lua `<Type>.new(...)`; the constructors index it immediately → Kahlua throws `RuntimeException` → it **propagates through the so-called protected `protectedCall`** → `parse` aborts → `processServer` never runs → the server sends neither Accept nor Reject. Vanilla already has a failure path right after `protectedCall` (`if (!result.isSuccess() || result.getFirst() == null) { this.action = null; return; }`), but the exception bypasses it.

**Defect 2 — the reply carries the wrong state.** `javap` of `NetTimedActionPacket.processServer` (42.20):

```
 51: aload_0 / 52: getAction / 55: astore_3      <- act stored in slot 3
 60: aload_3 / 61: getstatic Accept / 64: setState   <- act.setState(Accept)  (on act)
 81: aload_0                                     <- this  (wrong)
 84: invokevirtual NetTimedActionPacket.write
142: aload_0                                     <- Reject branch, same bug
145: invokevirtual NetTimedActionPacket.write
```

`this.state` stays `Request` from parse onward, so the initial Accept/Reject replies from this method are actually sent as Request. Hence **an initial Request rejection can never make the client's `ActionManager.isRejected` true**. Later Rejects for already-accepted actions (from `perform()==false` in `ActionManager.update`) are serialized from the correct object and unaffected. For contrast, `ItemTransactionPacket.processServer` in the same codebase does it right (offsets 25/59 `this.setState`, then 44/78 `this.write`).

### Why the client never self-heals

| Mechanism | Location | Why it fails |
|---|---|---|
| `finished()` | `BaseAction` | requires `!waitForFinished`, but `LuaTimedActionNew.start` always sets it true in MP → completion can only come from the server |
| `hasStalled()` | `BaseAction` | requires `lastTime < 0` or `currentTime < 0`; a stuck action sits at `maxTime` (positive) → always false |
| 30-minute timeout | `ActionManager` | only removes the entry, **does not set Done/Reject**; `isDone`/`isRejected` both start with `!actions.isEmpty()` → once the list is empty both are false, turning "wait 30 min" into "forever" |
| `isUsingTimeout` | `ISReadABook` / `ISResearchRecipe` | returns false → the entry is never even removed |

Contrast: `TransactionManager.isDone`/`isRejected` lack the `!isEmpty()` prefix; `allMatch` on an empty stream is true, so the item-pickup path auto-`forceComplete`s after ~20 s. `ActionManager` differs by that one prefix.

### Patches (two redirects, server-only path)

| Patch | Hook point | Redirect | Effect |
|---|---|---|---|
| B | the only `LuaCaller.protectedCall` in `NetTimedAction.parse` (javap offset 167) | → `NetTimedActionGuard.protectedCall` | catches `RuntimeException` and returns a `LuaReturn` with `isSuccess()==false` (`LuaReturn.createReturn(new Object[]{FALSE, msg})` → `LuaFail`), so vanilla's existing `action = null; return;` is actually reached |
| A *(retired in 42.21)* | the two `write` calls in `NetTimedActionPacket.processServer` (offsets 84/145) | → `NetTimedActionGuard.write` | when `action == null` (vanilla's reject-branch condition), set state to `Reject` before sending → client `isRejected` true → `forceStop()` → queue unblocked |

**Both were required on 42.20**: B alone sends a Reject still labelled Request; A alone never runs because `parse` already aborted. Either one alone has zero effect on the player symptom.

**No client patch needed**: `LuaTimedActionNew.update` already has complete `isDone → forceComplete` / `isRejected → forceStop` handling that was simply never triggered. Once the server sends the right packet, the client unblocks itself.

### Deliberate boundaries

1. **We never guess or substitute the null `InventoryItem`.** A wrong guess could consume the wrong materials or create products from nothing. The patch turns a silent permanent hang into an explicit failure — the player sees the action interrupted and can retry. Why the item is null (container desync / consumed by a previous step) is an upstream issue; the helper's diagnostic log collects evidence.
2. **The Accept branch was not touched (42.20).** `Action.write` omitted the playerId when `state == Accept`, and the client's `ActionManager.setStateFromPacket` matches packets by playerId (`IDShort.id` defaults to 0, never matching a real onlineID), so fixing Accept's state would change wire bytes with no benefit. Fixing it requires changing the `Action.write/parse` wire format, which client and server share — a one-sided change would desync byte parsing. Side effect: actions with `maxTime == -1` still show a `POSITIVE_INFINITY` bar, but with A+B they no longer hang forever (Done or Reject always arrives).
3. **The `!isConsistent` reject path is left vanilla.** Its `getAction()` → `Action.copyFrom` NPEs first by calling `PlayerID.set` on a null player — a separate pre-existing issue.

### Build-time checks

Ten SmokeCheck items, two of which are structural "should this patch exist" facts:

- **Reason for A**: in vanilla `processServer`, both `write` receivers are `this` and both `setState` receivers are not `this`. **This goes red when TIS fixes the bug** — a reminder to retire the patch rather than stack two fixes (this is what fired on 42.21).
- **B's anchor**: vanilla `parse` contains the `ACONST_NULL → PUTFIELD action` sequence (B adds no new failure semantics, it only makes the existing path reachable).
- The catch type is pinned to `RuntimeException` (`Error` must propagate, same discipline as W6); the helper's `write` delegation occurs exactly once and **outside any try** (diagnostic failure must not change wire behavior); real instruction counts after both redirects equal vanilla (1:1 shape-preserving); class-wide diff negative controls (`NetTimedAction` loses exactly one `protectedCall`, `NetTimedActionPacket` exactly two `write`s).

Behavior test `NetTimedActionGuardTest` runs each configuration (shipping config plus each kill switch) and asserts that argv matches the helper's actual flags — a misspelled property name fails the test instead of silently running the enabled build three times.

**Kill switches** (separate, for bisecting): `-Dmdc.netTimedActionGuard=0` (B); `-Dmdc.netTimedActionState=0` (A, removed in 42.21).

### Verification loop

After deployment the server log should change from "`Lua(Vanilla).new(...)` exception + stuck player" to "`[MinidoracatJavaPatch][NetTimedAction] lua ctor failed type=<Type> nullArgs=<i/j>` + reject + player sees the action interrupted and can retry". `anomalies` must stay 0. `nullArgs` is a direct fingerprint that a constructor argument deserialized to null; it locates the action type and argument position but **cannot distinguish** container-resolution failure from an itemId miss (that would need an observation point in `loadInventoryItem`).

**Suggested TIS report**: `loadInventoryItem` silently returns null + Lua constructors have no null guard + `protectedCall` does not catch `RuntimeException` + (42.20) `processServer` sets state on the wrong object — together they produce "client waits forever". `ItemTransactionPacket` serves as the correct in-codebase reference.

### 2026-09-08 correction: W10-D handles only request argument-parse failures; D2 coordinate recovery removed

Production also logged `NetTimedAction` packets NPE-ing in `PZNetKahluaTableImpl.loadComponent`: `GameEntityManager.GetEntity(netID)` returns null and `getComponent` is called on it anyway. This happens in `NetTimedAction.parse`'s `actionArgs.load`, before the Lua constructor; once the exception leaves parse, vanilla never runs `processServer`, so there is no Accept/Reject — a second entry point beyond W10's original two patches. (A Lua pcall failure may already return `isSuccess()==false`; not every stuck action is B's `RuntimeException` case, and `caught=0` does not mean no other fault.)

**D1 kept, D2 removed**:

| Boundary | Current behavior |
|---|---|
| head of `NetTimedAction.parse` | `beginParse(this)` clears the previous packet's cause and binds context to this packet |
| the method's only `actionArgs.load` | `loadArgs` catches `RuntimeException` only within a valid parse context and when enabled, clears the partial arguments, and records the cause; `Error` propagates |
| existing `protectedCall` redirect | if a parse-failure cause exists, skips the Lua constructor and returns a failure result so vanilla sets `action=null` |
| W10-A `write` redirect (42.20 only) | with `action=null`, serializes a correct Reject; the cause is scoped to this packet and taken-and-cleared **before** write, so a throwing write leaves no residue |
| shared `PZNetKahluaTableImpl` | not modified, no loose class shipped; other users such as `StatePacket` stay vanilla (since 2026-10-01 W51 ships it again for observation only: one line when type 17 finds no animal, decoding unchanged; see [2bo](#2bo)) |

`-Dmdc.netTimedActionArgs=0` disables D. (On 42.20, D was also disabled when A was off: a parse exception must not be swallowed without a correct Reject exit.) B's `netTimedActionGuard` switch is independent. D covers only `actionArgs.load` failures; it is not a catch-all around `parse`.

**Why D2 was removed**: the earlier version decoded the netID to coordinates and picked the unique object near the player with the same component. Neither distance nor uniqueness proves it is the original object; after the original disappears another item could still match. And the redirect sat in a shared decoder, affecting more than timed actions. The whole `loadComponent`/coordinate recovery entry was deleted, with no fallback. D makes the failure explicit; it **does not restore the identity of a moved crafting station and does not guarantee a retry succeeds**.

**Verification**: with real jar classes, a request with a missing component → partial args cleared → constructor not run → reply bytes are a Reject with the same action/player id; a normal request parsed afterwards on the same packet instance is unaffected. Also verified: missing parse context still rethrows as vanilla, causes do not leak across packets, a write exception clears state, `Error` propagates. Four configurations (shipping / B off / A off / D off). Shared-decoder negative control still throws vanilla's NPE and never selects a substitute object.

**Reading live logs**: `argsFailed` = handled parse failures; `argsRejected` = constructor skips. A "reject serialized" line (42.20) only proves the reply was serialized, **not that the client received it or the action recovered**. Parse-failure lines list `connectionPlayers` rather than attributing the packet to the first player on a shared connection. `anomalies` should be 0. Production acceptance of this version is not complete; no post-fix success rate is claimed.

**2026-09-10 precise diagnostics**: parse-failure lines also include action `type/name`. Only when vanilla `loadComponent` itself threw the NPE, the table is a vanilla instance, and the buffer has a full long+short available does the helper use absolute reads to log `componentRef=wire netId=… componentId=… readerPos=…`; otherwise `componentRef=unavailable` (including stackless fast-throw, custom tables and truncated data). Buffer position/limit/byte order are never changed, no entity/component is looked up or substituted, and Reject behavior is unchanged. SmokeCheck pins the original decoder's 10-byte field order and its catch-free return chain; real-decoder tests cover heap/direct buffers, read-only slices, different byte orders and unknown exceptions. **A wire identity is not evidence that the object currently exists in the world.**

A TIS follow-up report draft exists (items R1/R3), **not yet submitted**.

### 2026-09-28 — 42.21 re-validation

- **Patch A retired**: 42.21's vanilla reject branch serializes the Reject from `act` (same fix as ours); the `NetTimedActionPacket.write` call sites in `processServer` are gone (0 redirect hits). Removed `NetTimedActionGuard.write`, `takeCause` and `-Dmdc.netTimedActionState`; `NetTimedActionPacket.processServer` now has only W10-C's single redirect (expectedHits 3→1).
- **B and D1 kept, decoupled from A**: `loadInventoryItem` silently returning null, `loadComponent` NPE-ing on an absent entity, and `LuaCaller.protectedCall` not catching exceptions are byte-identical in 42.21 (`parse` hits 3/3, method SAME). D used to be enabled by `ARGS_GUARD && STATE_FIX` (no correct Reject exit without A); 42.21 vanilla is now the correct exit, so D depends only on `-Dmdc.netTimedActionArgs`. The cause lifecycle is now "`beginParse` clears, `loadArgs` sets, `protectedCall` takes and clears", leaving no context after `parse`.
- **Log changes**: the "reject serialized" line disappears with A (vanilla sends the Reject without the helper); parse failures are still logged per event as `args parse failed` / `lua ctor failed`. The heartbeat now fires every 2048 `parse` calls: `parses caught argsFailed argsRejected suppressed anomalies guard args`.
- **Verification**: `NetTimedActionGuardTest` in three configurations (shipping / B off / D off). A Request with a missing component completes `parse` under D without calling the constructor, then the real `processServer` from the dist runs (only the RakNet send side replaced); exactly one packet is captured and its bytes are a Reject with the same action/player id. SmokeCheck now pins "both `write` and `setState` receivers in `processServer` are `act`" (red if TIS reverts to `this.write` → re-evaluate A) and "`processServer` does not go through `NetTimedActionGuard`".
- Revive A: `git checkout 8d2bee8 -- <files>` (PatchConfig / NetTimedActionGuard / SmokeCheck / tests / build.ps1).

<a id="2y"></a>
## 2y. Animal sound sort livelock catcher (W11, server, default on)

**Incident** (2026-08-23, unrelated to W10 — the stack never touches `NetTimedAction` or any mdc helper):
`IngameState.updateInternal` threw `IllegalArgumentException: Comparison method violates its general contract!`
(TimSort) from `BaseAnimalSoundManager.update` ← `CollisionManager.resolveContactsInternal` ← `IsoWorld.updateWorld`.
After ~2 h of intermittent throws (1,411) it **threw every frame**: packet handling stayed alive (chat, logins), but the
rest of the frame aborted, so `updateManagers()` (`ActionManager` / `TransactionManager`) never ran → **server-wide
stuck timed actions, items could not be picked up, "time stopped"**. The frame counter still advanced (incremented
before the throw), so no watchdog fired; only a restart helped (graceful shutdown still worked, since console commands
run earlier in the frame).

**Vanilla defect (two compounding layers)**:

1. **Comparator breaks its contract**: `compare` recomputes `FMODParameterUtils.getClosestListenerDistanceSquared` on
   every call and hand-codes the result with `>`/`<`; any comparison with NaN returns 0 ("equal"), breaking transitivity.
   With no listener the value is a consistent `Float.MAX_VALUE`, so **the only trigger is a NaN coordinate**.
2. **Self-reinforcing livelock**: `characters.clear()` runs **after** the sort (javap: sort offset 19, clear offset
   116+). When the sort throws, `clear` is skipped and stale / despawned animals stay in the list forever → every later
   frame throws again. This matches the production curve "occasional → every frame".

**Trigger context**: dense fenced farms (50–80+ animals) plus an admin cleanup script removing animals in batches via
`animal:remove()` (first throw 42 s after a batch). `remove()` is a legitimate API and vanilla despawn uses the same path,
so fixing the script only lowers the frequency; the defect is in vanilla. Whether the NaN comes from the animal or the
listener side is still open; the patch's diagnostic log collects evidence.

**Patch**: redirect the single `ArrayList.sort(Comparator)V` in `update()V` (offset 19) → `AnimalSortGuard.sort`
(3 → 3 bytes, stack 2-in/0-out unchanged):

- Normal path delegates directly.
- On IAE: swallow, count, log animals with NaN coordinates, **return unsorted**. Sound priority degrades for one frame
  (the list is rebuilt every frame); `update()` completes, `clear()` runs, **the livelock chain is broken**.
  A half-sorted list has no lasting effect (it only selects the N closest emitters).
- **Only IAE** is caught; other `RuntimeException`s and `Error`s propagate as in vanilla (same rule as W6/W10).
- `nanAnimals=0` while it still throws ⇒ the NaN is on the listener (player) side.

Deliberately not done: re-implementing the sort (e.g. snapshotted keys) — it would assume the comparator's intent and
silently diverge if TIS changes it; the catcher works for any comparator.

**Build-time checks**: six SmokeCheck pins — exactly 1 sort in `update`; **ordering anchor** (sort before clear; red if
TIS moves `clear` into a `finally` or before the sort → re-evaluate the patch); 1:1 redirect; catch type = IAE; exactly
2 delegation sites (off passthrough + on); class-wide negative control. Behaviour tests (on/off): equivalence, IAE caught,
non-IAE and `Error` propagate.

**Kill switch**: `-Dmdc.animalSortGuard=0`.

**Verification**: under incident conditions the console shows
`[MinidoracatJavaPatch][AnimalSort] contract violation caught size=... nanAnimals=...` instead of
`IngameState.updateInternal> Exception thrown` with this stack; no stuck timed actions, no restart. `anomalies` stays 0.

<a id="2z"></a>
## 2z. Vehicle DB chunk-index consistency guard (W12, server, default on)

**Incident** (2026-08-23/24): three vehicles became invisible after a player got stuck, disconnected while seated, or a
crash. Rows and blobs were intact in `vehicles.db`, but the header `wx,wy` contradicted `x,y`:

Two rows stored `wx,wy = 0,0` and one stored a neighbouring wrong chunk; in every case the correct chunk follows from `x,y`.

`VehiclesDB2` loads only via `WHERE wx=? AND wy=?`, so these vehicles never load at their real location. The rows were
fixed offline; blobs untouched.

**Vanilla root-cause chain** (confidence ≈0.82; exact save interleaving ≈0.60):

1. Physics updates x/y first; `vehicle.chunk` is rebound only when `current` is non-null and the chunk changed, and
   `current` is refreshed only in `postupdate()`.
2. `IsoChunk.resetForStore()` clears `vehicles` and sets the pooled chunk's wx,wy to 0,0 but does not clear each
   vehicle's `vehicle.chunk` back-reference; when the pooled object is reused it takes arbitrary new coordinates.
3. A player disconnecting inside a vehicle makes `GameServer.disconnectPlayer()` call
   `VehiclesDB2.updateVehicleAndTrailer()` immediately.
4. `VehiclesDB2$VehicleBuffer.set()` takes wx,wy from `vehicle.chunk` and x,y from physics; SQL commits them with no
   invariant ⇒ 0,0 after a reset, an arbitrary wrong cell after reuse.

**Patch**: in `VehiclesDB2$VehicleBuffer.set(BaseVehicle)`, match the unique sequence
`aload0 → aload1 → BaseVehicle.getY()F → putfield y:F` and append 16 linear instructions: the **already-captured** x/y
and original wx/wy go to `VehicleChunkIndexGuard.wx/wy(BaseVehicle,float,int)`, whose results overwrite the buffer
fields. The helper computes `PZMath.fastfloor(coordinate / 8.0F)`; when off or non-finite it returns the original value.

**Scope and risk**: one site covers add/update, disconnect, cell unload, trailers and SQL INSERT/UPDATE. Physics, world
membership and chunk lifecycle are untouched — only the persisted header is protected. It does not fix the in-session
client desync or repair already-corrupted rows. Non-finite x/y keeps the vanilla value and logs an anomaly (first 8, then
every 64th: sqlId, both indices, x/y, chunk identity).

**Build-time checks / tests**: method-scoped `expectedHits=1` (any drift fails the build). SmokeCheck pins vanilla
chunk/wx/wy read counts, one call per helper, no leakage elsewhere in the class, full operands/order, write-back to the
correct fields, exactly +16 real instructions. Behaviour tests: positive/negative coordinate boundaries, the real case
wrong chunk → chunk derived from x,y, NaN/Infinity fallback, null chunk never dereferenced by the helper, kill switch returns captured
vanilla values.

**Kill switch**: `-Dmdc.vehicleChunkIndexGuard=0`.

**Report to TIS**: `docs/report/2026-08-24-vehicle-chunk-index-corruption-tis.md`.

---

<a id="2aa"></a>
## 2aa. Animal sync range alignment (W13, server, default enforce)

**Symptom**: packets carrying full animal snapshots (gene fields such as `maxWeight` / `ageToGrow` / `fertility` /
`meatRatio` / `eggSize`) were **38.3–39.8%** of steady-state production upload — the largest single category. No crash,
just ~40% of bandwidth.

**Forensics** (bidirectional pcap, custom decoder: UDP → RakNet datagram / split reassembly → `0x86` + BE short
PacketType → `AnimalUpdatePacket` requested section):

| Metric | Measured (8.03 s / 25,000 datagrams) |
|---|---:|
| client→server requested IDs / server→client full snapshots | 109 / 109 |
| distinct `(client endpoint, onlineID)` | **14** |
| tuples repeated within 5 s | **14/14 (100%)** |
| repeat snapshots beyond the first | **95/109 (87.2%)** |
| full snapshots per tuple in the window | **5–10** |
| request→response latency | 43.9–94.2 ms (median 72.7) |
| mean `dataSize` | 1,125.4 bytes |
| animal distance from that client's player | **70.4–91.5 squares** |
| AnimalUpdate parse errors | 0 |

Resend cadence matches the 800/1000 ms `UpdateLimit`; the animals were wild deer/mice/rats, not one farm.

**Vanilla root cause (geometry mismatch)**: at handshake (`GameServer.receivePlayerConnect`) the server stores
`range = clamp(client chunk grid width, 12, 20)`, `relevantRange = range/2 + 2`, `chunkGridWidth = range`.
(`ClientServerMap.loaded[]` tracks 64-square server cells, not whether 8-square client chunks finished streaming.)
For a normal unclamped odd width, the client chunk window's common safe half-width after streaming is
`(range/2) × 8` squares (integer division). But `AnimalSynchronizationManager.sendUpdateToClient` uses the radius
`(getRelevantRange() - 2) * 10 == (range/2) * 10` (javap offsets 233–242) — **10/8 of the safe bound** — so the extra ring
reaches positions where the client has no `GridSquare` yet. Integer division matters: `IsoChunkMap.CalcChunkWidth` forces
normal widths to be **odd** (auto max 19; debug options 5–13), so `range*4` would be wrong (range=13: 48, not 52).

The loop: ring animals get the light `AnimalPacket` → client has no instance → requests the onlineID
(`sendRequestToServer`) → server answers with full `IsoAnimal.save()` (modData + `fullGenome`, ≈1.1 KiB) → client
`AnimalPacket.isConsistent` (`getGridSquare(...) != null`) fails, entry **skipped**, no instance → next 800/1000 ms update
repeats.

**Patch**: redirect the single `UdpConnection.RelevantTo(FFF)Z` in `sendUpdateToClient` (invokevirtual → invokestatic
with receiver first; shape-preserving) to `AnimalRelevancyGate.relevantTo`, clamping the radius to
`(getChunkGridWidth()/2) * 8`.

**Why not a constant change**: a method-scoped `10→8` would be mathematically equivalent, but (1) a baked-in constant has
no runtime kill switch (our rule: every patch can fall back to vanilla without redeploying); (2) clamp ambiguity and even
widths must be excluded first; (3) the vehicle exclusion needs helper logic.

**Scope and risk**:

- Shrink only: if `aligned >= vanillaRadius` the vanilla result is used (`passthrough`).
- **Vehicle exclusion (required)**: `IsoChunkMap.ProcessChunkPos` shifts the client chunk-map centre forward by
  `currentSpeedKmHour / 5` squares in a vehicle (passengers `min(s*2, 20)`, drivers unbounded). The server's
  `releventPos` does not know this, so any player-centred radius would block loaded squares ahead and admit unloaded ones
  behind. If a player is in a vehicle the check passes through — **the patch only affects players on foot**.
- **Clamp boundaries**: enforce only for `stored ∈ {13,15,17,19}`. `stored=12` (raw ≤ 12, e.g. debug 11) and `stored=20`
  cannot recover the real width (raw=11 treated as 12 would leave 8 squares of over-reach); even 14/16/18 pass through
  because even-width rectangles are asymmetric.
- **Accepted residual error**: the client load area is a chunk-aligned rectangle; the clamp is a continuous radius. The
  player's in-chunk offset `p ∈ [0,8)` makes the two sides differ by < 8 squares, and `(range/2)*8` is the common lower
  bound. So there is an **under-send of < 8 squares** on the wider side: animals less than one chunk from the view edge
  may appear late. Over-send is 0 only when the width is a trusted odd value, server and client agree on the centre
  chunk, streaming has finished, and `RelevantTo` took the radius branch (not `connectArea`).
- Centre agreement and streaming are independent preconditions: the server's `releventPos` updates only on
  `PlayerPacket.processServer` (up to ≈600 ms + latency), and `WorldStreamer` may lag, so crossing chunks or teleporting
  leaves transients. Fully eliminating them needs client-side loaded-set knowledge; a server-only radius cannot.
- Coop / split-screen: `RelevantTo` returns true on a `connectArea[n]` match without checking the radius (on a miss it
  still does); that load window is a residual item, not an exact loaded set.
- The requested side (arbitrary onlineIDs, up to 150 per packet) is handled by W14 (§2ab). `IsoAnimal.save` wire format is
  unchanged; server-only, players install nothing.

**Build-time checks / tests**:

- Method-scoped `expectedHits=1`. **10 SmokeCheck pins**: vanilla preconditions (exactly 1 `RelevantTo`; radius from
  `getRelevantRange` and **no** `getChunkGridWidth` read — the defect's structural fact, red if TIS fixes it → retire;
  `isAnimalOnScreen` does not call `RelevantTo`); post-patch (1 redirect, 0 original calls, instruction count unchanged,
  `isAnimalOnScreen` untouched); helper contract (one `getChunkGridWidth` read; 3 vanilla delegations + 2 clamped
  decisions; vehicle exclusion reads `getPlayerAt` / `getVehicle` once each and is called once); class-wide negative
  control.
- Behaviour tests run each mode in its own JVM (mode is `static final`) against the real `UdpConnection.RelevantTo`
  (instances via `Unsafe.allocateInstance` to avoid the `PacketsCache` → `PacketTypes` → `AntiCheat` static-init chain).
  Covered: odd widths 13/15/17/19 enforce; 12/20 and 14/16/18 pass through; an independent geometry self-check over
  `p ∈ [0,8)`; exact float threshold `Math.nextUp(floor)`; four axes, diagonals, non-zero indices; vehicle passthrough;
  coop `connectArea` unaffected; far animals counted in `rejected`.
- Mutation checks (measured): removing the parity guard → 6 failures; radius +1 ULP → 4 failures; restored → 0.

**Modes**: `-Dmdc.animalRelevancy` = `1`/unset enforce; `2` observe (returns vanilla, counts `suppressed`);
`0` off (no redeploy).

**Observability**: heartbeat `[MinidoracatJavaPatch][AnimalRelevancy] mode=1 calls=… rejected=… suppressed=…
passthrough=… anomalies=…` (every 2^20 decisions; later throttled to ≤1 line per 5 min, §2bf). `anomalies` must stay 0.
`rejected` includes far animals vanilla would not send either, and `suppressed` includes the wider-side under-send, so
**neither is the ring share or real waste** — use the pcap repeat metrics. Acceptance: rerun the pcap decoder;
`repeat_5s` / extra snapshot ratio from the 87.2% baseline to a <5% target. Residuals may only be attributed to
passthrough cases, the requested path, centre drift, streaming gaps or `connectArea` — never to the < 8-square under-send.

**Report to TIS**: `docs/report/2026-08-24-animal-relevancy-resend-loop-tis.md`.

---

<a id="2ab"></a>
## 2ab. Animal requested cooldown + range gate (W14, server, default enforce)

**Motivation (measured after W13)**: 60 s steady state, two independent decoders agreeing: on-foot connections no longer
loop (0 requests), but of 598 remaining full snapshots **96.2% were in the vanilla ring and 98.5% came from one connection
that stayed in a vehicle** — the branch W13 passes through. 172/181 tuples re-requested ≥1 s after receiving the full
snapshot, on the 800/1000 ms timer. A server-side radius cannot be accurate in vehicles, so this patch uses no geometry:
**per connection and animal, at most one full snapshot per cooldown window.**

**Patch** (two redirects in `AnimalSynchronizationManager.sendUpdateToClient`, sharing W13's MethodOps; expectedHits 1→6):

| # | Site | Original call | Purpose |
|---|---|---|---|
| 1 | offsets 12, 31 | 2 × `UdpConnection.getPacket(PacketTypes$PacketType)` invokevirtual | Capture the connection in a ThreadLocal for the range gate. The client path `sendRequestToServer` uses invokeinterface `IConnection.getPacket`, and redirects match (opcode, owner, name, desc), so it is never hit. |
| 2 | offsets 83, 370, 419 | 3 × `HashMap.get(Object)` invokevirtual | 83 is `requests.get(guid)` (filter target); 370/419 are `timerUpdateAnimal.get(Short)`. Same signature, so the helper splits at runtime on `key instanceof Long` (guid map key = Long, timer map key = Short); the timer path is an allocation-free passthrough. |

**Wire safety**: vanilla only iterates the returned collection into `packet.requested`; post-send clearing uses a separate
`computeIfAbsent` (offsets 541–547) on the original entry. `AnimalUpdatePacket.write` back-fills the count actually
written (SmokeCheck pins exactly 2 `AnimalInstanceManager.get` in `write`), so fewer IDs change no wire format. If the
filter empties everything and updated/deleted are empty, no packet is sent and the map entry is **kept**: the server
re-filters next tick. It must never be cleared then — the client only re-requests after **receiving** an AnimalUpdate,
so clearing would drop the request and the animal could appear late or never.

**Cooldown semantics**:

- **First request always answered**; only repeats inside the window are held.
- Marked when let through; serialization follows in the same tick on the same thread. The filter output is capped at
  vanilla's 150, so vanilla never truncates a marked ID. Accepted exception: an `IOException` in `write` zeroes the count
  after marking, delaying that batch by one window (rare failure path).
- Observe also marks (let through = sent), so switching to enforce starts warm with no resend spike.

**Range gate (abuse surface, independent switch)**: vanilla lets a logged-in client request the full `IsoAnimal.save()`
of any onlineID. IDs beyond vanilla's own radius `(getRelevantRange()-2)*10` + 48 squares are rejected (+48 covers the
vehicle look-ahead: driver speedKmH/5, 48 = 240 km/h; passengers max 20). Such animals would not be announced to that
connection anyway; back in range, updates and answers resume. Missing ThreadLocal capture (should be impossible) skips
the range check and keeps the cooldown — fail-open to the conservative side.

**Amplification bound**: `requestedCount` is an unbounded client-controlled int; vanilla bounds work only via
`animalsCount >= 150 → break`. The filter loop is also bounded at 150 (else one packet with 65,536 IDs costs O(65,536)
per tick). Non-existent onlineIDs are never marked (but kept, so vanilla sends and clears as before), so fake IDs cannot
fill a bucket and evict real cooldowns.

**State**: `guid → (onlineID → lastSentMs)` in Trove primitive maps (no boxing). Self-healing: expired entries are simply
ignored; every 30 s buckets idle for 120 s are dropped (covers disconnects, no hook); a bucket above 2048 entries is cleared
(`bucketResets`; worst case a few early resends, never OOM). All mutations happen on the single animal-sync thread;
`AtomicLong` counters exist only for cross-thread heartbeat reads.

**ThreadLocal invariant**: every Long-key path through `filterRequests` ends in `finally { CURRENT.remove(); }`,
including the both-switches-off early return (deliberately inside the `try`), otherwise the ThreadLocal pins the last
`UdpConnection` and its 1 MB buffer. The Short-key timer hot path stays outside the `try` and never touches it.

**Modes (two independent switches, no redeploy)**: `-Dmdc.animalRequestCooldown` = `1`/unset enforce, `2` observe (count
only), `0` off; `-Dmdc.animalRequestCooldownMs` (default 6000, clamped [1000, 30000]); `-Dmdc.animalRequestRange` same
three modes. Both off → pure `map.get(key)` delegation.

**Build-time checks / tests**:

- `expectedHits = 6` (1 W13 `RelevantTo` at offset 242 + 2 `getPacket` + 3 `HashMap.get`). **9 SmokeCheck pins**:
  vanilla preconditions (3 `HashMap.get` / 2 `getPacket`; `sendRequestToServer` uses invokeinterface; 2
  `AnimalInstanceManager.get` in `write`); post-patch (redirect counts, 0 original calls, instruction count unchanged,
  `write` / `sendRequestToServer` untouched); helper contract (one raw `HashMap.get` fail-open delegation; range gate reads
  `AnimalInstanceManager.get` / `RelevantTo` / `getRelevantRange` once each; one `ThreadLocal.set` + one delegation in the
  capture); class-wide delta confined to `sendUpdateToClient`.
- Behaviour tests in five JVM modes (`enforce` / `observe` / `off` / `cooldown-only` / `range-only`): key
  discrimination, empty fast path, cooldown lifecycle (pass → block → pass at +6001 ms), guid isolation, range pins
  (distance 150 and 188 present, 189/200 split by mode), range-blocked IDs not marked, null connection keeps cooldown,
  missing animal passes, cap 2049 clears the bucket, sweep, zero buckets with cooldown off. Real `IsoAnimal`s are injected
  into `AnimalInstanceManager` (its `<clinit>` needs `RandStandard.INSTANCE.init()` first).
- Mutation checks (full build gate; baseline 0): cooldown never hits → 4 failures; `instanceof Long` → `Number` → 2;
  margin 48 → 0 → 4; no 150 loop bound → 1; cap clearing regression → 1; marking non-existent animals → 1; both-off return
  outside `try` → 1. The last two only failed after review added tests (the first ThreadLocal assertion went through a
  helper that re-injected the connection, making it trivially true).

**Observability**: heartbeat `[MinidoracatJavaPatch][AnimalRequestGate] cooldown=… range=… cooldownMs=… calls=…
accepted=… cooldownSuppressed=… rangeSuppressed=… cooldownObserved=… rangeObserved=… markRefused=… anomalies=…` (every
2^14 Long-key calls; later ≤1 line per 5 min, §2bf). `anomalies` must stay 0. `accepted` = IDs let through (equals IDs
placed in `packet.requested`, except the `IOException` path; counts resends too, not "new animals"). `*Suppressed`
increment only in enforce, `*Observed` only in observe.

**Acceptance**: rerun the two-pcap comparison; `repeat_5s` and vehicle-connection full snapshots/s should drop from ~1/s
per tuple to ~1 per window. The mechanism guarantees that reduction; it does **not** promise an extra ratio < 5%.

---

<a id="2ac"></a>
## 2ac. Main-loop freeze watchdog (W15, server, observability only, default on)

### Background

On 2026-08-24 the main loop nearly froze for ~216 s (`f:7115→7118`, three frames of ~72 s, console silent) → RakNet
heartbeat timeouts **dropped 9 connections in one frame** → 12.5 minutes of connection-null log spam (75,143 lines) plus
18,260 `PacketsCache` limit lines → reconnects and chunk re-streaming = black edges. Three candidate mechanisms remained,
all blocked by one gap — **no main-thread stack during the freeze**: (1) expensive animal work on the main loop (4,174
animal instances that night); (2) a transient ZGC allocation stall invisible in cycle-boundary GC logs; (3) malloc stalls
on a corrupted glibc heap (4 native aborts that day). Swap, cgroup memory stall and W12/W13/W14 were ruled out. This patch
automates capturing the stack.

**2026-08-29 native addendum** (see §2h): popman's `n_updateMain` drains the `PassToMain` SPSC queue **to empty with no
budget**, and the moodycamel 512 is a block size, not capacity (unbounded backlog). Fingerprint: a main thread stuck in
`ZombiePopulationManager.n_updateMain` = popman backlog stall.

**Findings**: the first captures (2026-08-29, 80+ players) were both **scheduled saves blocking the main thread**, a fourth
mechanism: `WorldMapVisitedServer.save` deflating per-player visited maps (5.8 s, RUNNABLE) and `ServerMap.SaveAll`
sleeping while save workers drain (7 s, TIMED_WAITING); `ticksDuringStall=1`. By 2026-09-02 every non-shutdown 5–7 s
freeze over four days (16 events) was the same family: `SaveAll` waiting for 4 `WorkerThread`s to serialize loaded cells
("SaveAll took 4474–5043ms"), then visited-map deflate (<1 s). This is vanilla's "full save = synchronous freeze" design
(`SaveAll` polls with `sleep(10)`; chunk serialization needs a quiescent world) and cannot be patched away; moving visited
saves off-thread (candidate W21) would save < 1 s and was not pursued. Frequency comes from `SaveWorldEveryMinutes=60`
and saves issued by the admin restart countdown (kept deliberately as pre-restart insurance). 2026-09-06: with
`SaveWorldEveryMinutes=30`, an external probe found 80% of SaveAll worker samples contending on the shared
`BitHeader` / `ByteBlock` `ConcurrentLinkedDeque` pools → **W25** (§2am) makes them thread-private. W15 itself stays
observe-only.

### Patch (head call, same mechanism as W4-1)

- Hook: head of `ServerMap.preupdate()V`: `ALOAD 0; INVOKESTATIC MainLoopWatchdog.tick(Lzombie/network/ServerMap;)V`
  (`expectedHits = 1`). javap (42.20.3): `GameServer.main` has **exactly one** `invokevirtual ServerMap.preupdate:()V`
  (offset 3466) inside the main loop = once per frame. Recording at the head means a freeze anywhere in the loop stops
  the timestamp.
- `zombie/mdc/MainLoopWatchdog`: `tick()` = one `System.nanoTime()` volatile write + frame counter + started check
  (10 Hz, allocation-free); the first tick lazily starts a daemon thread and prints a banner. The daemon polls every 1 s;
  frame age ≥ threshold (default 5000 ms, `-Dmdc.mainLoopWatchdogThresholdMs`, clamped 1000..600000) → immediate
  main-thread `getStackTrace()`, then every 10 s, max 12 per freeze (~2 min). Lines include `Thread.getState()` and heap
  used/max (RUNNABLE + animal/AI frames = heavy work; RUNNABLE + allocation frames = allocation stall; shallow/native =
  JNI/malloc; BLOCKED/WAITING = lock/park). Recovery prints duration, ticks advanced (**0 = full freeze; >0 = a run of
  slow frames**) and cumulative `stalls/dumps/maxStallMs/anomalies`. Silent in normal operation.
- 5 s threshold: chronic 2–3 s stutter would otherwise turn snapshots into extra load; 5 s catches only noticeable events.

### Deliberately not done

- **Observe only**: never interrupts or recovers anything.
- Main thread only, never `Thread.getAllStackTraces()` (an order of magnitude costlier; pinned by SmokeCheck).
- Does not detect W6-style livelocks where ticks keep advancing (fixed by `ChunkLoadGuard`).

### Build-time checks (SmokeCheck)

1. Vanilla precondition: exactly one `ServerMap.preupdate` in `GameServer.main` (red if TIS changes it → re-choose hook).
2. Post-patch: head-call sequence `aload_0 → tick` exactly once, exactly +2 real instructions.
3. Helper contract: `tick` has one `nanoTime` and no snapshot call; one single-thread `getStackTrace` site; zero
   `getAllStackTraces` in the class.

**Kill switch**: `-Dmdc.mainLoopWatchdog=0` (`tick` returns early, no thread).

**Observability**: `[MinidoracatJavaPatch][MainLoopWatchdog]` first-activation banner with `threshold=5000ms`; per freeze
a "main loop frozen `<ms>` (snapshot n/12)" line with `ticks=… state=… heapUsedMB=…` plus the stack; on recovery a
"freeze ended" line with `observedMs≈… ticksDuringStall=…`. `anomalies` must stay 0. With no freezes it is a zero-cost
fuse and stays.

<a id="2ad"></a>
## 2ad. Animal unload hand-off guard (W16, server, observe) (retired: 2026-09-02)

> **Retired 2026-09-02.** This was a pure observability probe on the animal persistence chain (APM `removeChunkFromWorld` head call + 4 redirects, `virtualizeAnimal`, Worker `addAnimal`/`saveRealAnimals`/`moveAnimal`, Main `saveRealAnimals`, `AnimalZones.spawnAnimalsOnZone`, `IsoChunk.removeFromWorld` 2 redirects + tail call), opened after a production server lost ~40% of hens and ~65% of turkeys from `apop` over 39 hours with clean world saves (a vanilla persistence defect, pre-dating our patches).
>
> It instrumented the vanilla silent-failure points found via javap on 42.20.3: `AnimalManagerWorker.addAnimal` returning silently when `getCellFromSquarePos` (offset 15) or `AnimalCell.getOrCreateChunkFromSquarePos` (offset 44) returns null; `removeChunkFromWorld` missing animals during the moving-object scan; the `virtualId==0` dedupe branch in `addAnimal` dropping animals via `remove(j--)`; `AnimalManagerWorker.removeFromWorld(IsoAnimal)` making zero `addAnimal` calls; and `saveRealAnimals` skipping animals when the cell lookup (offset 39) returns null.
>
> After 8 days every loss counter stayed at zero (`s2Missed`, `queueFailures`, `sourceGap`, `cellNullAdd`, `chunkNullAdd`, `duplicateRemoved`, `cellNullSave`; `clearShortfall` 1–4 but with `handedOff == scanSeen`, so not a loss). Conclusion: the vanilla unload hand-off chain is not the culprit. The heartbeat line (one per 256 unloads) was 7.3% of production log volume, so the probe was removed. Kill switch was `-Dmdc.animalPersistGuard` (0=off, 2/unset=observe). To revive: restore files from commit `13650e1` and re-add the matching PatchConfig / SmokeCheck / build.ps1 sections.

<a id="2ae"></a>
## 2ae. Hutch load return-value guard (W17, server, default enforce)

### Defect (confirmed statically)

`IsoHutch.load(ByteBuffer,int,boolean)` deserializes each animal and then calls `addAnimalInside(animal,false)` at offset 526; the next real instruction (offset 529) is `POP`, so **the boolean result is ignored**. `addAnimalInside` picks a slot as follows:

1. if preferred == -1, `Rand.Next(0, getMaxAnimals())`;
2. if that slot is occupied by `animalInside` / `deadBodiesInside` / a nest box, re-roll; give up after >100 attempts;
3. the final placement (offsets 148–151) **checks only `animalInside`**: occupied → return false; free → put.

On false, the animal has already been created/loaded from the blob and `removeFromSquare` has run, but it is neither in the hutch map nor in the world, and nothing is logged — it is garbage-collected, i.e. **the animal is destroyed on load**. It triggers most easily near a full hutch (vanilla `maxAnimals` = 20) or when dead bodies / nest boxes crowd out valid slots; an observed rabbit-overflow case matches this shape exactly.

### Patch

The single two-argument `addAnimalInside` call site in `IsoHutch.load` (the one-argument overload lives in `update` and has a different descriptor) is redirected to `HutchLoadGuard.addInside(hutch, animal, sendEvent)`:

1. Delegate to `hutch.addAnimalInside` exactly once; on success or `mode=off`, return the original value.
2. False and `animalInside.containsValue(animal)`: duplicate add (vanilla already warns) — no rescue, to avoid one animal in two slots.
3. Scan slots `0..max-1` in order with no `Rand`: first pass looks for a slot empty in both `animalInside` and `deadBodiesInside`; otherwise a second pass accepts any slot empty in `animalInside`. Emptiness is tested with `map.get(key) == null` (not `containsKey`) to match vanilla, which treats `key→null` as free. The private `checkNestBoxPrefPosition` cannot be called, but vanilla's final placement does not check it either.
4. Enforce with a free slot: reproduce the full vanilla success state — `animalInside.put(slot, animal)`, `animal.hutch = hutch`, `setPreferredHutchPosition(slot)`, `setHutchPosition(slot)`, `setItemID(0)`, `tryRemoveAnimalFromWorld(animal)` — and return true. The preferred position must be set: on vanilla success it equals the final slot key, and omitting it leaves a stale position for later hutch entry/exit and re-rolls.
5. Truly full (no slot in either pass): log `CRITICAL` with animal type/id and hutch coordinates and return false. The silent loss becomes a visible, compensable event; the helper never creates a 21st slot.
6. Observe with a free slot: log `wouldForce` and return false (behaviour unchanged). A `RuntimeException`/`LinkageError` inside the rescue path increments `anomalies` and falls back to vanilla's false. **The original delegation is not wrapped in try** — whatever vanilla throws still propagates.

`tryRemoveAnimalFromWorld` is client-only per javap (`GameClient.client && animal != null && isExistInTheWorld`), so it is a no-op on the server; it is called only to keep the success path isomorphic with vanilla. Load passes `sendEvent=false`, so no sync branch is involved.

Kill switch `-Dmdc.hutchLoadGuard`: `1`/unset = enforce (default), `2` = observe (log only), `0` = off (pure delegation). Client safety: for `worldVersion >= 212` the client-side load skips the animal blob at offsets 191–209 so the loop never runs, and the loose class ships only to the server, so there is no server-only desync surface.

### Build-time checks and tests

- Vanilla load call site pinned in exact order: `ALOAD0 → ALOAD7 → ICONST0 → addAnimalInside → POP`. If TIS starts consuming the return value or switches `sendEvent` to true, the build fails.
- Vanilla `addAnimalInside` success contract: exactly 105 real instructions on 42.20.3; counts and order of `Rand`×2, map put, hutch `PUTFIELD`, preferred×2, hutchPosition, itemID and tryRemove are all pinned.
- After patching: same argument shape, only the call target becomes the static helper; the original call count drops to zero; real instruction count unchanged; class-wide difference of exactly 1.
- Helper: exactly one original delegation, zero `Rand` in the class, each of the six `forceInto` steps exactly once, back-link is an exact `PUTFIELD`.
- `HutchLoadGuardTest` in three separate JVMs: a `ZeroRandom` deterministically produces "slot 1 is free but vanilla hits slot 0 on all 101 attempts"; also covers clean-slot preference, dead-body fallback, `key→null`, duplicates, all 20 animals surviving, and `CRITICAL` for the 21st. Mutation removing the map put must fail.

---

<a id="2af"></a>
## 2af. Animal LOS throttle gate (W18, server, default observe)

### Motivation (side result of the 2026-08-25 evening-peak black-edge diagnosis)

A record 67 players saturated the main thread on one core (99.9% R), fps fell 9.8 → 5.0, and throughput-type black edges appeared. 60 `jcmd` stack samples at 66 players showed **`IsoAnimal.updateLOS` as a single leaf at 25/60 = 41.7% of the main thread**, the LOS family at 46.7% in total, and no other hotspot above 5%; an earlier 40-player sample showed 18.3%. Structure (javap, 42.20.3): every real animal scans the whole `getCell().getObjectList()` (`Set`) every tick; the loop's only useful output is calling `behavior.spotted()` for zombies/players and setting `spottedList = {this}`; animals, vehicles, corpses and physics objects are all discarded by `instanceof`. Amplification ≈ 769 animals × thousands of objects × 10 Hz.

### Patch

One caller-side redirect: the only `invokevirtual updateLOS:()V` in `IsoAnimal.updateInternal()V` (offset 197) → `invokestatic zombie/mdc/AnimalLosGate.updateLOS(IsoAnimal)V` (1:1, shape-preserving, `expectedHits=1`). The body of `updateLOS` is untouched, so Lua/mod direct calls behave as before; the two W3-3 prefilter redirects remain as defence in depth.

Enforce uses frame rotation: forward the call only when `floorMod((long)(identityHashCode(animal) * 0x9E3779B9 >>> 16) + frame, N) == 0` (all-`long` arithmetic, no truncation). The frame source is vanilla `MovingObjectUpdateScheduler.instance.getFrameCounter()` (`startFrame()` adds 1 per tick). The hash mix guards against `-XX:hashCode` changes and low-bit clustering.

Why a frame counter, not wall-clock: a wall-clock window sampled once per tick permanently skips whole residue classes when tick = k×window and gcd(k, N) > 1 (at 5 fps with N=4, half the animals would go blind) — exactly the low-fps situation this patch targets. With Δframe = 1, gcd(1, N) = 1 and CPU reduction is always (N−1)/N. **Δframe = 1 is conditional, not guaranteed**: it holds because on the server `getUpdateSchedulerSimulationLevelForObject` always returns FULL ⇒ `frameMod = 1` ⇒ every bucket runs every tick (42.20.3). Vanilla buckets already rotate by `buckets[frame % frameMod]` with `getID() % frameMod` sub-buckets; if TIS enabled LOD tiers on the server, an animal would update only on frames `≡ getID() (mod frameMod)`, and a common factor with N would blind a residue class permanently. Two safeguards: SmokeCheck pins these load-bearing preconditions (build fails), and the helper's `gateApplies()` fails open at runtime (if `getCurrentSimulationLevel().getFrameMod() != 1`, forward and count `lodPassthrough` — lose the throttle rather than blind animals).

Behavioural cost (rate, not a one-off delay): `spotted()` is a rate-type side effect, so skipping scales its rate by 1/N. Affected: stress build-up from nearby players/zombies, taming `playerAcceptanceList` accumulation (dist < 10 branch), wild alertness and sneak-XP opportunities, `attackIfStressed` trigger chance, `lastAlerted` decay; plus the global `Rand` sequence shift already accepted for W3-3 (re-evaluate if N is raised). First-detection delay ≤ N−1 ticks. **Hence the conservative default N = 2** (half rate, ≤ 1 tick delay), to be raised after in-game validation. `spottedChr` (used by `fleeFromChr`) keeps its previous value during skips, so fleeing is if anything stickier. On skip `spottedList` stays `{this}` (the constant animal value; no server consumers; Lua readers see what vanilla would rebuild). Hearing (`respondToSound`) does not go through LOS and is unaffected.

Exceptions: the main try catches only `RuntimeException` (bookkeeping fails open: `anomalies++`, then forward as normal). **`LinkageError` always propagates (fail-fast)** — a new game jar with an old loose class must fail visibly; otherwise, under enforce, a missing `getFrameCounter` would degrade into silent per-call swallowing with no heartbeat. The vanilla delegation sits outside the try and propagates unchanged; `maybeBeat()` runs after bookkeeping and wraps its own `RuntimeException`s (log failures never escape, never block the main loop, never turn a forward into a recorded skip).

Kill switches: `-Dmdc.animalLosGate=2|observe` (default; records the `objectList.size` distribution and times one forward in every 64; unknown values fall back to observe) / `1|enforce` / `0|off`. `parseMode()` accepts text aliases like the other tri-state patches (numeric clamping would silently turn `=off` into observe and `=-1` into off). `-Dmdc.animalLosN` (clamped 1..16, default 2). The heartbeat reads the clock only every 4096 calls and prints at most once per 60 s (no unconditional `nanoTime` on the hot path, as in `AnimalRelevancyGate`).

### Build-time checks and tests

- Vanilla preconditions: exactly one hook in `updateInternal`; exactly one `getObjectList():Set` in `updateLOS` and zero `lastSpotted` references (if TIS moves the player tail logic in, skipping is no longer zero-diff — failing build means retire and re-evaluate).
- Completeness pin: the `isAnimal` short-circuit in `IsoPlayer.updateInternal1` still exists (`isAnimal` ×1, `IsoLivingCharacter.update` ×2, player `updateLOS` ×1). If TIS splits the flow, animals would reach the un-throttled player `updateLOS`.
- Helper: exactly 2 delegations (off pass-through + main path with timed try/finally), `getFrameCounter` ×1, `getCurrentSimulationLevel`/`getFrameMod` ×1 each (fail-open present), zero `NEW` on the hot path, zero `Rand` in the class, named exception handlers only for `RuntimeException` (`LinkageError` passes through; finally any-handler allowed).
- **Load-bearing precondition pins (5)**: server ⇒ FULL short-circuit (`GameServer.server` `GETSTATIC` ×1 + FULL ≥ 2); `getFrameMod` exactly 5 real instructions (`1 << idx` shape); `startFrame` `LCONST_1`/`LADD` ×1 each; `bucket.add` has `getID()` ×1 + `IREM` ×1; `MovingObjectUpdateScheduler.update()` scans all buckets every frame (`bucket.update` ×1, `simulationLevels`/`frameCounter` `GETFIELD` ×1 each — closes the blind spot "buckets called on alternate frames while `frameMod` stays 1"). Any failure means TIS changed scheduling; re-check the gcd argument.
- **Client dominance pin**: exactly one `GameClient.client` `GETSTATIC` in `updateInternal`, located before the call site (server-only enforce must not desync clients).
- `AnimalLosGateTest`, seven configurations in separate JVMs (off alias / observe / enforce N=4 / shipped N=2 / clamp 0→1 and 999→16 / unknown `bogus`→observe; each self-checks MODE and N): off freezes counters; observe reconciles counts and both size-sampling branches (reflectively injected `objectList` and null-cell skip); error contract (bookkeeping `RuntimeException` fails open with exactly one delegation; vanilla `RuntimeException`/`Error` sentinels propagate without counting anomalies); enforce asserts a per-(animal, frame) formula oracle, same-frame consistency, exactly 1/N forwarded frames per animal within 4N frames (no blindness), phase dispersion (a constant mix would put every animal on the same frame → fail), and LOD fail-open (`frameMod > 1` always forwards and counts `lodPassthrough`; `frameMod == 1` rotates).
- Mutation 6/6 killed: always-forward, inverted test, `+`→`^`, wall-clock source, removed mix (killed by the formula oracle), removed `gateApplies` fail-open (killed by the LOD "all forwarded within N frames" check).

### Deployment and measurements

- Observe first for one night: `sizeAvg/sizeMin/sizeMax` (objectList composition, deciding whether a list-replacement follow-up is needed) and `losAvgUs × forwarded` reconciled against the 41.7% sample share.
- After switching to enforce (property + restart): compare evening-peak fps (N=2 was estimated to return ~20% of the main thread — an estimate to be replaced by observe data) and spot-check behaviour (zombies attacking chickens / fleeing, taming approach speed, sneak XP, high-stress animal attacks). A drop in `AnimalSpottedPrefilter` counters is expected. **`lodPassthrough` should stay 0** (non-zero = TIS enabled LOD, fail-open active, throttle scope reduced). Before raising N, re-validate the rate cost, `Rand` shift and scheduler structure.

#### W18-2 `AnimalLosScan` (landed 2026-08-29, default observe)

The Gate already owns the only `updateInternal` call site, so no new bytecode hook is added: the Gate's forward path calls `invokestatic AnimalLosScan.updateLOS` (Gate off still passes straight through without Scan, so kill switches are layered). Observe is a pure timing wrapper (`calls/elapsedNs/sumObjects`) measuring the real residual cost after Gate enforce. On enables a conservative squared-distance prefilter with margin (`d² > (threshold + 0.25)²`) that skips the `sqrt` / `tryCastTo` / prefilter work for distant pairs. **The threshold is read live for each zombie/player candidate pair** (a mod behaviour on the previous pair may change `spottingDist`; on a read error that pair fully delegates, never reusing a stale gate). The boundary band and near pairs fully delegate to W3-3, so behaviour is bit-exact with the current path (including RNG). Kill switch `-Dmdc.animalLosScan=0|off / 1|on / 2|observe` (default observe). SmokeCheck pins the 42.20.4 method body, caller census, live-threshold read and `GameTime` prefix; tests cover off/observe/on, far fast-skip, negative `lastAlerted` clamp, near delegation, the 12.2 boundary band, invisible players, null fallback, and "after `spottingDist` changes 10→100 mid-scan, the next pair must delegate".

Measurements:

- **2026-08-29 evening-peak observe (80+ players, record)**: 580 calls/frame × `avgUs` 55 ≈ 31.9 ms/frame at 3.2 fps (~312 ms/frame) ⇒ **residual ≈ 10–13%, above the 8% threshold**, so an on-canary was justified (`objAvg` 2035 and rising at peak, `sizeMax` 2611; off-peak only 6.7% — share scales with players / animals / objectList size). `fallbacks = 0`, `anomalies = 0`, Gate `forwarded` ≈ Scan `calls`, identical `avgUs` for Gate and Scan (wrapper overhead invisible). `-Dmdc.animalLosScan=on` was then applied as the sole change of the next restart, with a rollback rule of speed-up ≤ 1.1×.
- **2026-09-02 on-canary result** (on since 08-30 vs. 08-29 observe baseline): on `avgUs` 35–43 at `objAvg` 2809–3119 vs. observe 53–55 at 3298 ⇒ after linear `objAvg` normalisation **≈ 1.25× speed-up (> 1.1×), kept on**. `fastSkipped` 26.2 G vs. `delegated` 9.2 M (99.96% of pairs take the fast path), `fallbacks = 0`, `anomalies = 0`, Gate/Scan counts reconcile. Skipping 99.96% of pairs yields only ~25% because the loop itself (`Set` iteration + `instanceof` + squared distance, ≈ 35 ns/pair × ~1136 pairs/call) has a fixed cost.

### 2026-09-11: early rejection of non-target kinds

The existing "not a zombie and not a player" rejection in `AnimalLosScan.updateLOS` is moved to just after the self check and before reading XYZ / height / squared distance / square. `IsoAnimal` extends `IsoPlayer`, so the check excluding other animals must stay; a null entry still reaches the original `getX()` and throws NPE instead of being silently skipped by the new `instanceof`. The vanilla getters involved are plain field reads; side effects added by arbitrary third-party Java subclasses in those getters are not covered.

Unchanged: physics / vehicle / grapple-only / self ordering, visiting order of valid targets, per-pair live `spottingDist`, `lastAlerted` prefix and existing `Rand` paths, Gate and Scan modes, N, sight and hearing. No new cache, thread, knob or bytecode hook. **It still scans the full `Set`**; it only saves per-object work on non-targets and does not claim a dedicated candidate list.

Tests reuse the off/observe/on modes and add: original order of valid results with mixed objects and self, a target changing sight range before the next target is still read live, and null prefix preserved / suffix aborted. The same behaviour cases pass on both the old and new helpers; no assertions on getter call counts.

Local throwaway A/B (two isolated helpers in one JVM, the same real `HashSet` of 4,800 objects, 5 JVM samples × 11 alternating rounds, 10,000 warm-up iterations per side, first heartbeat excluded; each round reconciles fast/delegate/fallback/anomaly, vanilla delegation and consumer effects outside the timed region): with 25% / 50% / 75% non-target objects the speed-up was about **1.4–1.5× / 1.7–1.8× / 2.7–2.9×**; with only valid targets roughly flat. Allocation per scan is 32 bytes on both sides (the original `Set` iterator); no per-object allocation added.

These are **local synthetic results on a fixed object list, not production FPS or LOS-cost improvements**. Production rollout still needs a separate switch and acceptance via the existing Scan timing at comparable objectList sizes and load; `fallbacks/anomalies` must be 0 and N / validity rules stay unchanged. A candidate-cache design is not being bundled on the strength of this local result.

---

<a id="2ag"></a>
## 2ag. Vehicle permanent-removal authorization guard (W19, server, default observe; observe-only in this version)

### Motivation (2026-08-23 Player-F incident, verified 2026-08-28)

On the production server three unclaimed, intact vehicles (`Trailer_Livestock`, `StepVan`, `SmallCar`) were dismantled with a blowtorch and permanently deleted (whole rows removed from `vehicles.db`); vehicles claimed via MVCK survived. Source analysis on 42.20.4, independently re-checked by two review lanes:

- Vanilla `VehicleCommands.lua` `Commands.remove` calls `vehicle:permanentlyRemove()` with **no permission check** (the sibling `repairPart` has `checkPermissions(player, Capability.UseMechanicsCheat)`; `remove` does not). The dispatcher only checks `module == 'vehicle'`.
- On the Java side, `GameServer.receiveClientCommand` has a nominal gate for vehicle/remove, but it relies on `NetworkPlayerAI.isDismantleAllowed()`, which **always returns true** — an unimplemented TIS hook, so effectively everything passes.
- **The Player-F path does not go through `Commands.remove` at all**: `ISRemoveBurntVehicle.complete()` is a shared timed action that calls `permanentlyRemove()` from server-side Lua (the "Nep Dismantle Any Car" mod only opens the client menu for intact vehicles; the server runs vanilla `complete`, whose `isValid` checks for a blowtorch but not for a burnt vehicle). A Lua-level guard on `Commands.remove` would miss this case, and any other dismantling mod delegates to the same vanilla capability. **The single convergence point is the Java choke point `permanentlyRemove` itself.**

### Choke-point caller census (SmokeCheck pins exactly 4 jar-wide)

| Call site | Context | Reachable on server |
|---|---|---|
| `LuaManager$GlobalObject.removeVehicle` | `!GameServer.server` branch (javap offsets 44–54) | No (dead path; the guard is pinned by SmokeCheck) |
| `RandomizedWorldBase` | world-event cleanup | Yes (legitimate maintenance) |
| `VehicleManager.removeVehicles` | admin `/remove vehicles` batch | Yes (legitimate maintenance) |
| `BaseVehicle.setSmashed` | shell swap (delete old vehicle, create new) | Yes (legitimate maintenance) |

Three Lua entry points all reach the choke point: `Commands.remove` (client command — the admin mechanics panel `onCheatRemove` and any player-forged/delegated command share this path), `ISRemoveBurntVehicle` (timed action), and direct calls from other mods' server Lua.

### Patch (head call, observe only)

Head call at the start of `BaseVehicle.permanentlyRemove()V` → `zombie/mdc/VehicleRemoveGuard.onRemove(BaseVehicle)V` (`ALOAD 0 → INVOKESTATIC`, same mechanism as W15 preupdate; +2 real instructions, `expectedHits=1`). It is added to the existing W3-4 `BaseVehicle` ClassPatch — a class must not get a second ClassPatch, or the later one re-reads vanilla and overwrites the earlier patch. Vanilla head shape starts `iconst_0; istore_1`, with a single tail `RETURN` (javap 42.20.4 offsets 0–88).

One log line per removal: `remove#seq vid script pos claim caller lua nearest near`.

- **Caller classification**: `Thread.currentThread().getStackTrace()` → first frame that is neither mdc nor the choke point itself (identifies Java maintenance callers), plus a full-stack scan for `se.krka.kahlua.` / `zombie.Lua.` prefixes (= Lua-driven). The patch is low-frequency (tens of removals per day), so the cost is negligible.
- **MVCK claim state (six values, based on MVCK 42.15 source)**: the vehicle modData `SQLID` is only an imprint — `unclaimVehicle` does not clear it, so an `SQLID` does not mean "still claimed"; ownership truth is the Global ModData table `MVCKByVehicleSQLID` (key = SQLID → `OwnerPlayerID`). States: `unclaimed` (no imprint) / `stale-imprint` (imprint but no table entry = unclaimed) / `claimed:<owner>` / `no-mvck-table` / `no-moddata` / `unknown-*` (read failures are recorded, not silently passed). The helper is read-only (SmokeCheck pins zero `rawset`).
- **Nearby players**: `GameServer.getPlayers()` → nearest distance plus up to 3 names within 32 tiles (signal for "removing someone else's vehicle by standing next to it"). Returns placeholders when unavailable (test JVM).
- Rate limit: up to 20 full lines per 10 s window, then `suppressed++` (guards against unknown high-frequency loops; normal frequency is far lower).

### Why this version does not enforce

An authorization decision needs a (requester, vehicle) pair, but the choke point only sees the vehicle; the requester lives in Lua (the command's player / the timed action's `action.character`). Every enforce candidate had a fatal gap: guarding only `Commands.remove` (Lua) misses the timed-action path; a single ThreadLocal bridge in `receiveClientCommand` cannot see the second source, `NetTimedAction.perform`; a pure vehicle-state rule (allow burnt, deny intact) **blocks admins removing intact vehicles** (admins and abusers use the same command, indistinguishable by Kahlua frames) and would break `setSmashed` shell swaps. Enforce rules (candidates: admin capability OR owner + distance + burnt; whether unclaimed burnt vehicles are public must be stated explicitly) were deferred until observe data showed the legitimate removal rate and caller distribution; an identity bridge would need to cover both `receiveClientCommand` and `NetTimedAction.perform` (W14 ThreadLocal capture + clear in `finally`).

Kill switch `-Dmdc.vehicleRemoveGuard=2|observe` (default) / `1|enforce` (**an observe alias in this version**, as in W16) / `0|off` (early return); text aliases, unknown values fall back to observe.

### Build-time checks and tests

- SmokeCheck: exactly 4 `permanentlyRemove` call sites jar-wide with per-class distribution pinned (total + distribution prevent offsetting changes; a new TIS caller means the observe classifier is stale → build fails); the `GameServer.server` guard in `GlobalObject.removeVehicle` exists (dead-path precondition); head call order after patching and exactly +2 real instructions; helper contract (no recursive `permanentlyRemove`, `getStackTrace` ×1, zero `rawset` in claim/onRemove).
- `VehicleRemoveGuardTest`, three configurations in separate JVMs (observe default / `1` = observe alias / `off` text alias; MODE self-checked to avoid a false green from the property): three-way caller classification (Kahlua reflection chain / Java maintenance frame / `setSmashed` self-call skipping itself), all six MVCK states (injected GlobalModData), an empty-shell vehicle must not throw (an observe probe must never block removal), rate limit: 30 rapid calls → 20 logged / 10 suppressed.

### Deployment and outcome

- Vehicle counts are verified via the per-vid ledger from this log against DB diffs, **not** the total row count of `vehicles.db` (new spawns offset deletions).
- Immediate mitigation is operational (remove the dismantle mod or restrict it to admins, plus `MVCK.ServerSideChecking=true`) and complements rather than replaces this probe.
- **Closed 2026-09-02 (08-28 to 09-02, 6 days, 285 removals)**: `claim=unclaimed` 264 / `stale-imprint` 16 / `claimed:<owner>` 5 (**all by the owner**, near=[owner]); 100% `lua=true` (Kahlua reflection chain); `nearest ≤ 2` tiles in 267 = players dismantling by hand; scripts are ordinary vehicles (SmallCar 30, CarNormal 23, SmallCar02 17, …), `CarNormalBurnt` only 4. **Zero cases of someone else's claimed vehicle being dismantled** ⇒ "protect claimed vehicles only" would have blocked 0 removals, and "deny intact unclaimed" would have blocked 264 legitimate ones. No enforce rule is supported by the data. **Decision: keep this patch as a pure forensic ledger (observe, every removal logged within the rate limit), no enforce; Player-F-type incidents are handled by after-the-fact ledger reconciliation plus operational rules (MVCK claims).**

<a id="2ah"></a>
## 2ah. Clothing sync guard (W20, server, default observe; (b) has an opt-in enforce)

### Incident (2026-08-28, three log clusters)

One 68-minute session logged 1,589 ERROR lines, including `INetworkPacket.send> Exception thrown` ×362, a matching NPE fingerprint ×363, and `SyncVisualsPacket.parse > Player h...` ×129 (≈490 combined). The 362 is **amplified per connection**: `INetworkPacket.send` wraps each connection in its own try/catch, and `sendToRelative`/`sendToAll` run `getPacket` + `setData` for every relevant connection, so one bad event throws once per recipient. Clusters (a) and (b) share that fingerprint; the real event count is much lower and is broken down by this patch's counters.

**(a) `ContainerID` square-null NPE.** `IsoGameCharacter.addHole` → `BloodClothingType.addHole` → `Clothing.setCondition` (condition ≤ 0 and `isWorn() && isRemoveOnBroken()`) → `Unwear(true)` → `GameServer.sendRemoveItemFromContainer` → `sendToRelative` branch → `ContainerID.set(ItemContainer)` → the two-arg `set`'s ObjectContainer/IsoObject branch dereferences `o.square.getObjects()` (javap offsets 197/233) **with no null check**. Mismatch: `Unwear` gates on `c.getSquare() != null` (`IsoMovingObject.getSquare()` = `current ?: square`), while `ContainerID` reads the raw `square` field. The NPE is swallowed by the per-connection catch, but `Unwear` still runs `inventory.Remove` + `AddWorldInventoryItem`, so clients never get the removal: a sticky desync (possibly the worn copy stays visible while a ground copy appears).

**(b) tint NPE.** The `SyncClothingPacket$ItemDescription(WornItem)` constructor guards `baseTexture` and `textureChoice` with `getVisual()==null ? -1 :` (offsets 39–87, 2× `IFNONNULL`), **but calls `getVisual().getTint()` unguarded** (offsets 91–101). `getVisual()` becomes null when the clothing asset is missing or not ready. Every `SyncClothing` broadcast for that player (`sendToAll` from `IsoGameCharacter` and several `Clothing` paths) then throws once per connection, so **all clothing sync for that player stops for good**.

**(c) visuals count mismatch.** `SyncVisualsPacket.parse` rebuilds `itemVisuals` from the server's local player, reads the wire count, and on a mismatch logs an error and **drops the whole packet**. The console line "Player has X ... sync Y" means X on the server and Y claimed by the client. In the incident sample the client had one more than the server (14/15). `isConsistent` makes the same check, so the packet is neither processed nor forwarded.

### How the clusters relate (hypotheses tested in observe mode)

- (a) → (c) is **not causal**: the (a) victim is not an `IsoPlayer` (initial reading, later overturned; see acceptance), and `SyncVisuals` applies only to players.
- (b) and (c) likely share a root cause: `WornItems.getItemVisuals` skips items with a null visual, while the `SyncClothing.set` lambda only filters null item / null `getItem()`. One null-visual worn item both crashes the (b) constructor and makes the server's item-visual count one lower than the client's, so (c) shows `wireMinusLocal=+1`. The hypothesis is confirmed if (b) and (c) report the same player and the diff is always +1. The MirageWardrobe mod (Workshop ID 3770186452) is tested the same way; it is not assumed to be the cause.

### Patch (1 multi-slot head call + 1 head call + 4 redirects)

| Hook | Patch | expectedHits |
|---|---|---|
| `ContainerID.set(ItemContainer,IsoObject)V` head | head call slots={1,2} → `ContainerIdProbe.onSet` (first multi-slot head call: `aload_1`, `aload_2`, `invokestatic`; +3 real instructions, max stack 2, no frame changes) | 1 |
| `SyncClothingPacket.set(IsoPlayer)V` head | head call slot={1} → `ClothingSyncGuard.onClothingSet` (ThreadLocal records the player being packed) | 1 |
| `ItemDescription.<init>(WornItem)V` | redirect `ItemVisual.getTint()` → `tintOf(ItemVisual)` (1:1, shape-preserving) | 1 |
| `SyncVisualsPacket.parse` | redirect `PlayerID.getPlayer()` ×3 → `parsePlayer` (captures the parsed player) + `DebugType.error(Object)` ×1 → `onVisualsMismatch` (superset line: original message + player + signed diff + distribution counters) | 4 |

- **(b) modes.** off = pass-through (NPE thrown at the call site, same as vanilla). observe (default) = log, then throw the NPE. This **keeps the vanilla failure**: the same per-connection catch swallows it, so behavior is unchanged but the log line is attributable. enforce = a null visual or null tint returns `ImmutableColor.white`. This only keeps serialization alive (transport liveness). The receiver's `process` still assumes `getVisual().setTint`, so enforce does not claim an end-to-end fix.
- **Filtering out the whole item is deliberately not done.** `SyncClothingPacket.process` calls `WornItems.remove` on the remote side for any worn item missing from the packet (pinned as a behavior anchor in SmokeCheck). A temporarily unready asset would then look like the player undressing.
- **(c) is deliberately never enforced.** `SyncVisuals` is purely positional: the wire has a count, then patch/dirt/blood in order, with no item identity. Skipping an entry or clamping to `min(count)` would apply holes, blood or condition to the wrong garment, so vanilla's whole-packet reject is the safe choice. The only fixes are to remove the cause ((b) enforce) or to build a full `SyncClothing` reconciliation/resync (separate work).
- **(a) is deliberately not fixed.** A fix would change how packets locate containers (read `getSquare()`, or fall back to another `ContainerType` on null), which affects every container packet. It is deferred until the probe shows the breakdown (class of `o`, `square` vs `getSquare()`, caller).
- The (b) NPE does not leak pooled packets: `PacketsCache` keeps one long-lived handler per connection per `PacketType`, and `setData` fails before `startPacket`. The helper keeps no state in packet fields.

### Build-time checks and tests

- Vanilla preconditions: in the constructor, `getVisual`=5, `getTint`=1, `IFNONNULL`=2 (**`IFNONNULL`=3 means TIS added the guard: retire (b)**). `GETFIELD tint`=4 in `write` (a second NPE site). `WornItems.remove(InventoryItem)`=1 in `process` (the anchor that forbids filtering). In `parse`: `getPlayer`=3, `error(Object)`=1, `getItemVisuals`=1. In the two-arg `set`: raw `square`=6, `getSquare`=0, `getObjects`=2. The one-arg `set` calls the two-arg `set` exactly once.
- After patching: all three head calls checked in full order (first use of the multi-slot `headCallSlotsOk`), real-instruction delta checked (+3 / +2 / unchanged), original redirected calls reduced to zero. **Negative control:** `SyncVisualsPacket.write` is untouched (redirects are method-scoped, so its `getPlayer` stays vanilla).
- Helper contracts: `tintOf` delegates twice (off pass-through + non-null main path) and references `white` twice (the two enforce exits). `onVisualsMismatch` has exactly one `error` exit (off and observe share it, so lines are not doubled). `parsePlayer` delegates once. `onSet` has exactly one `getStackTrace` (only on the square-null path).
- `ClothingSyncGuardTest` runs three configurations in separate JVMs (observe default / tint enforce / all off, each asserting its own mode): `parseCounts` (+1 / -1 / unparseable → null); `tintOf` in each mode (observe throws an NPE naming the patch, enforce returns white and bumps `repaired`, off passes through with counters frozen); null tint in each mode; mismatch signed-diff buckets (plus/minus/other); `ContainerIdProbe` counters (objectNull / squareNull / normal) and the early return when off; caller classification (skips both `ContainerID.set` frames).

### Kill switches (three independent switches, like W10)

- `-Dmdc.containerIdProbe` = `0|off` / `2|observe` (default)
- `-Dmdc.clothingTintGuard` = `0|off` / `1|enforce` (null → white) / `2|observe` (default)
- `-Dmdc.visualsMismatchProbe` = `0|off` / `2|observe` (default)

Unknown values fall back to observe.

### Deployment and acceptance

- One observe round should answer four questions: (a) the class of `o` and the square vs `getSquare()` fingerprint; (b) which players and item full types (the null-tint path prints the item); (c) the sign of the diff and the player; and whether (b) and (c) are the same player (which would confirm the shared cause) and involve MirageWardrobe items.
- (b) enforce (`-Dmdc.clothingTintGuard=1`, restart required) passes when `nullVisual` still counts, `repaired` > 0, and the (b) share of the send-exception fingerprint drops to zero. Total ERROR count is not a criterion, because (a) is not fixed.
- Player-facing regression check: no new reports of clothes that cannot be removed or that other players cannot see. Enforce only affects tint serialization, and a white tint is a visible but harmless marker.
- **Closed 2026-09-02** (8/28–9/2: one observe round, then one (b) enforce round). All 480+ `nullVisual` events over 8 days came from **one player (Player-G)**. With enforce, `action=white` and the send-exception fingerprint disappeared. (c) mismatches continued (×6 and ×20 in two later windows, all Player-G, all `wireMinusLocal=+1`), so the shared cause is confirmed. The expectation that enforce would reduce (c) was **wrong**: the tint fix only protects serialization and does not change `getItemVisuals` counting one item short. (c) goes away only once the offending item is found and dealt with.
- **(a) finding reversed:** every squareNull event was `o=IsoPlayer` (`getSquare` non-null via `current`, container none/IsoPlayer, caller `SyncItemFieldsPacket.setData`), not a zombie or corpse. It matches `Error with packet of type: SyncItemFields` (40 in 2 days). Low frequency (1–5 per session); still not fixed.

### W20-2: attributing the null-visual item (2026-09-02)

The `nullVisual` path could not print the item because the redirected `getTint` receiver is the null visual itself (only the null-tint path had `describeItem(visual)`). An added head call at `ItemDescription.<init>(WornItem)V` passes slot 1 to `ClothingSyncGuard.onItemDescription(WornItem)`, which stores the current `WornItem` in a ThreadLocal. At the constructor head, `aload_1` touches only the argument, not `uninitializedThis`, so it is legal before `super()` (passes `-Xverify:all`). The `nullVisual` line now prints `item=<fullType>@<bodyLocation>` (`?` for any missing piece; `ItemBodyLocation.toString()` goes through Registries and falls back to `?`). `expectedHits` goes from 1 to 2 (head call + `getTint` redirect). SmokeCheck pins the constructor-head order, +2 real instructions, and a helper that only calls `ThreadLocal.set` (no `NEW`, no DebugLog, no `WornItem` calls). The first `nullVisual#N player=... item=...` line after deployment identifies the item and confirms or rules out MirageWardrobe from the full type.

### 42.21 re-validation (2026-09-28)

The three hooks (`SyncClothingPacket.set`, `ItemDescription.<init>`, `SyncVisualsPacket.parse`) are instruction-for-instruction unchanged. The constructor still has no `getVisual()==null` guard on tint (`IFNONNULL` still 2), and `WornItems.getItemVisuals` still skips null visuals. The patch stays as-is with no code changes. The patch-note item "Clothing condition is now handled server-side" is the new `BloodClothingType.setConditionAndSync`, which does not touch visual or tint.

**Open observation (not yet measured).** In 42.21, `SyncVisualsPacket.processServer` no longer uses a hand-written loop (skip the sender + `isRelevantTo`). It now calls `sendToClients(SyncVisuals, connection)`, which only skips the sender and requires the client to be fully connected, **with no distance filter**. This implements the patch note "Synchronized players' appearance between chunks". Each appearance sync now goes to every online player instead of only nearby ones, which is a new bandwidth amplifier. It does not affect this patch's mismatch check, which runs once per inbound packet in `parse`. Whether to filter it with `RecipientWindow` (as in W26/W36) needs a packet capture of outbound `SyncVisuals` traffic first. Filtering by distance without that data would bring back the cross-chunk appearance desync TIS just fixed.

---

<a id="2ai"></a>
## 2ai. Face-object sprite-grid null guard (W22, server, default on) (retired: fixed upstream by TIS in 42.21.0)

**Retired 2026-09-28.** Incident (42.20.4): 3,386 `StateMachine.stateExecute> Exception thrown` over two days (≈70/h), all `NullPointerException ... "object" is null` in `IsoGameCharacter.faceThisObject`, called from `AnimalIdleState.execute` and `AnimalEatState.execute` (`eatFromTrough`/`drinkFromTrough`). It was the biggest single source of log noise. It also left animals stuck in idle, because the exception aborted `state.execute` before `changeState`. Root cause: for sprite-grid objects (troughs), `faceThisObject` dereferenced the result of `getClosestSpriteGridObject(getX(), getY())` unconditionally (offsets 200–206). That method returns null when the object is no longer in its square's `objects` list (a stale trough reference after dismantle/move) or when a grid square is not loaded.

The patch redirected that single call (1:1, shape-preserving) to `FaceObjectGuard.closestSpriteGridObject`, which fell back to the original `object` when the result was null. Kill switch: `-Dmdc.faceObjectGuard=0`.

42.21 changed `faceThisObject`: `IsoGameCharacter` targets now go to `faceThisObjectAlt`, and everything else must pass `object != null && object.getObjectIndex() != -1` (javap offsets 17–25). The object is therefore still in its square, `getSpriteGridObjects(…, true)` always includes it, and the null result cannot happen. TIS used a different guard shape, so the build-time retirement signal (`IFNULL+IFNONNULL`=5) never fired; retirement was decided by manual audit. The redirect, `FaceObjectGuard`, the four W22 SmokeCheck pins, and `FaceObjectGuardTest` are removed. To restore: `git checkout 8d2bee8 -- <files>`.

---

<a id="2aj"></a>
## 2aj. Stuck timed actions, second-wave probe (W10-C, server, default observe; enforce = send Reject on interrupt)

> **42.21 re-validation (2026-09-28):** C (`NetTimedAction.start` tail call) and R (three redirects in `ActionManager.update`) are retired. W10-E (`ActionManager.stop` head call + `remove(BZ)` redirect) is retired because TIS fixed it upstream. The dispatch bridge is reduced to an action-packet owner check. Only B remains active. See "42.21 re-validation" at the end of this section.

### Incident (reported 2026-08-28, shipped 2026-09-02)

W10 ([§2x](#2x)) fixed actions that throw during server construction and so never get Accept or Reject. Players still reported stuck timed actions at peak hours: the progress bar finishes but the action never completes (crafting, butter-making, dismantling). It is intermittent: one action in a long queue sticks and blocks everything behind it. Two victims' client logs were **completely silent** while stuck (no errors, no Reject, no `[NetTimedAction]`), and the server W10 heartbeat showed `caught=0 rejected=0`, so W10's scenario was not involved. One log showed the DismantleAllAtOnce mod (Workshop ID 3761218629) queuing 15 handcraft actions at once. Decompiling 42.20.4 turned up **three server paths that W10 does not cover and that leave no server log trace**. This patch does not guess which one is the cause; it measures all three.

### Three paths (vanilla, javap on the 42.20.4 jar)

- **B — silent interrupt.** For each new Request, `NetTimedActionPacket.processServer` calls `ActionManager.stopPlayerActions(playerId)` (offset 48) before `start`. On the server, `ActionManager.remove(B,Z)` only removes the action from the list and calls `stop()`. It **sends no packet**: its only `startPacket` is in the client branch of the `GameServer.server` check. An interrupted action that was already Accepted never receives Done or Reject, and the client needs one of those replies for both `isDone` and `isRejected`. Resending the same id (a client retry) is not an interrupt and is counted separately.
- **C — 30-minute path.** The client bar length is the client-Lua `maxTime`. The server performs the action at its own `endTime` (`NetTimedAction.getDuration` = Lua `getDuration` × 20 ms + server-side `adjustMaxTime`). When `duration < 0`, `Action.setTimeData` sets `endTime = start + AnimEventEmulator.getDurationMax()` = **1,800,000 ms**. `ISHandcraftAction` returns -1 on the server when `craftRecipe` is nil. The constructor does not throw and nothing is logged, so the client bar finishes but the server performs the action 30 minutes later. **-1 is legitimate for animation-driven actions**, so this path can only be observed, not enforced.
- **R — Reject exit.** When `Action.perform()` (exactly 1 in `ActionManager.update`) returns false, `update` takes the Reject branch. Both the Done and Reject branches call `GameServer.getConnectionFromPlayer` (2 total); a null result means **no packet is sent**, again with no log. The probe measures the true/false split of `perform` and the number of null connections.

### Patch (1 redirect + 1 tail call + 3 redirects; Request context shared with the W10-E dispatch)

| Target | Patch | Behavior |
|---|---|---|
| `NetTimedActionPacket.processServer` (on the existing W10 ClassPatch; total `expectedHits=3`) | `ActionManager.stopPlayerActions` → `MdcTimedActionProbe.stopPlayerActions` | Reads the Request from the W10-E dispatch context, records the owner's Accepted old action, then delegates to the vanilla stop. No separate head call. |
| `NetTimedAction.start()V` (existing `nta` ClassPatch; `expectedHits=1`) — *retired in 42.21* | **Tail call** (before each `RETURN`: `aload_0; invokestatic onStart(NetTimedAction)V`; single `RETURN` at offset 57) | Reads `duration`/`endTime` after `setTimeData` and logs each negative case (type / name / player / endTimeDeltaMs) |
| `ActionManager.update()V` (new ClassPatch; `expectedHits=3`) — *retired in 42.21* | `Action.perform()Z` → `perform(Action)Z`; `GameServer.getConnectionFromPlayer` ×2 → `connectionOf` | 1:1, shape-preserving; delegates to vanilla, then records |

The `TailCall` primitive (`Patcher.TailCall`) was reintroduced for this patch. It inserts two instructions before `visitInsn(RETURN)`, sets a `visitMaxs` floor of 1, and adds no branch targets, so frames are untouched. The helper lives in **`zombie.core`**, not `zombie.mdc`: `Action` is package-private, its fields are protected, and `perform()` is package-private, so only same-package code can read them without reflection. The one reflective access is `ActionManager.actions` (private static), cached once at class init. If it is missing, an `IllegalStateException` escapes (fail-fast: a TIS restructure fails at boot instead of silently passing through).

**Enforce (B is the only path that can be fixed safely).** For an interrupted action that was Accepted, the helper copies the shape of the Reject branch in `ActionManager.update`: `state=Reject` → `startPacket` → `NetTimedAction.doPacket` → `action.write` → `send`, all on **the same object**. (Lesson from W10 patch A: calling `this.write` sent the wrong object, so the Reject always carried the Request state.) On Reject the client takes vanilla's `isRejected → forceStop`, an existing path with no worse outcome. If the connection is null or not fully connected, `rejectsSkippedNoConn++` and the state is left alone. A same-id resend gets no Reject, because it is a client retry and a Reject would cancel the action the client just resent. C and R stay observe-only under enforce.

### Deliberate non-goals

- The -1 fallback in `setTimeData` is not changed and `getDurationMax` is not shortened. -1 is valid for animation-driven actions, and changing it would perform legitimate actions early (consuming materials or producing output at the wrong time).
- `processServer` does not reject new Requests to protect old ones. In vanilla a new request interrupts the old action (the client's `ISTimedActionQueue` switches the same way). The bug is the missing reply after an interrupt, not the interrupt itself.
- The patch does not guess why R's connection is null (mid-disconnect vs reconnecting); it first measures whether `connNull` is non-zero.

### Build-time checks (SmokeCheck, 6 pins)

- Vanilla B: `stopPlayerActions`=1 in `processServer`. In `ActionManager.remove`: `startPacket`=1 and `GETSTATIC GameServer.server`=1. **No packet on the server branch is the reason B exists**; if TIS adds a Reject there, this pin fails and B is retired.
- Vanilla C: `RETURN`=1 and `setTimeData`=1 in `start`; `getDurationMax`=1 in `setTimeData`.
- Vanilla R: `perform`=1 and `getConnectionFromPlayer`=2 in `update`.
- After patching: `stopPlayerActions` redirected ×1, original call count zero, real-instruction count unchanged. `start` ends with `aload_0 → onStart` (`tailCallOk`: two instructions before each `RETURN`, call count = `RETURN` count), exactly +2 real instructions. `update` has 1 + 2 redirects, originals at zero, real-instruction count unchanged.
- Helper contracts: each of the three delegations exactly once; `sendReject` has `write`=1, `doPacket`=1, `send`=1 (pins the Reject shape).

### Behavior tests (`MdcTimedActionProbeTest`, four configurations in separate JVMs)

observe / enforce / probe-off, plus a vanilla negative control (at the time, `actionRemoveScope=0`). B tests go through the real dispatch context and the real `stopPlayerActions`/`ActionManager.stop`. They check that only Accepted actions are counted, that a same-id resend gets no Reject, and that sending is safely skipped when no connection is registered yet. No production setters, and no direct calls to private observers. Tests seed `Rand` and give a real `IsoPlayer`/`UdpConnection` only the minimum fields, so no full world is loaded. `GameServer.server=true`, so the negative control really runs the vanilla server removal instead of a no-op.

### Deployment and acceptance (as originally shipped)

- Boot prints `[TimedActionProbe]` first-activation `mode=2`. The heartbeat (checked every 256 starts, one line per 60 s) prints `starts/negativeDuration/interruptCalls/interruptedAccepted/sameIdResend/rejectsSent/rejectsSkippedNoConn/performCalls/performFalse/connLookups/connNull/logged/suppressed/anomalies`. Per-event lines are limited to 20 per 10 s window. `anomalies` must stay 0.
- After one peak evening, compare the shares of `interrupted#` (with `waitedMs`: an interrupt after nearly the full client `maxTime` directly matches "the bar finished but the action never completed"), `negativeDuration#` (by type; expected: `ISHandcraftAction` with a nil recipe on the server), and `performFalse#`/`connNull`. If B dominates, enable `-Dmdc.timedActionProbe=1` (restart) and check `rejectsSent>0` and fewer player reports. If C dominates, the fix belongs in Lua (which recipes are nil on the server, and whether that relates to DismantleAllAtOnce/Neat_Crafting); there is no safe Java patch. If R is non-zero, investigate connection lifecycle separately.
- B (server `remove` sends no reply) and C (-1 → 30 minutes) are reported to TIS separately, in `docs/report/`.

### W10-E: cancel isolation by real connection (2026-09-08) (retired: fixed upstream by TIS in 42.21.0)

The 9/7 observe data and the first `scope=player` version both assumed the `playerId` parsed from `GeneralAction` was the sender. The real wire format disproved that, so all earlier classifications and success claims from that data are withdrawn. The vanilla defect in 42.20.4: `Action.id` is one of **255 non-zero bytes**, cycled independently by each client JVM (and shared by split-screen players), so ids collide across connections. `GeneralActionPacket.setReject` wrote only id/state (serialized as `42 00 00 00 ff` for id 66, i.e. `playerIndex=-1`), so the server resolved the global player map to player 0 or null, **not the sender**. `processServer` called `ActionManager.stop` → `remove(B,Z)`, which matched **by id alone across the whole queue** and stopped any other player's action with that id, with no reply. `NetTimedActionPacket` Reject (`getAction/copyFrom`) and Fishing Reject had the same cross-player reach.

The patch redirected the final `INetworkPacket.processServer` call in `PacketTypes$PacketType.onServerPacket` to `MdcTimedActionProbe.processServer`, which bound the real connection and packet in a ThreadLocal (re-entrant, restored in `finally`). It then checked the owner and wire onlineID and limited cancels to actions whose owner belonged by object identity to `connection.players`, with no whole-table fallback. Kill switch: `-Dmdc.actionRemoveScope` (default connection scope). The TIS report draft is `docs/report/2026-09-07-tis-timed-action-followups.md`.

42.21 keys `ActionManager` by `(PlayerID, id)` throughout: `stop` calls `remove(action.playerId, action.id, true)`, and `remove`, `isDone`, `isRejected`, `isLooped`, `getDuration` and `getAction` all compare both keys. `setReject(B, IsoPlayer)` writes the sender, `FishingAction.getLuaTable` uses the packet's owner, and `Action.write/parse` always carries `playerId`. The hook `remove(BZ)V` no longer exists. `onStop`, `removeById`, `removeForConnection`, `CURRENT_STOP` and `-Dmdc.actionRemoveScope` are deleted, and `ActionManager` is no longer shipped.

### 42.21 re-validation (2026-09-28)

**C and R retired.** Per [§2bf](#2bf), all 1,600 negative durations were legitimate -1s from animation-driven actions, 653 of 709 interrupts were `ISWaitWhileGettingUp`, and R (perform false / null connection) never recorded a non-zero value. The `NetTimedAction.start` tail call and the `ActionManager.update` redirects (`perform`, `getConnectionFromPlayer` ×2) are removed. The three vanilla paths still exist; they are just no longer measured.

**B kept.** The `stopPlayerActions` redirect in `processServer` and its observe/enforce/off modes are unchanged. In 42.21 the server-side `remove(PlayerID,B,Z)` still only removes and calls `stop()`, and its only `startPacket` is in the client branch (SmokeCheck now checks the new descriptor). Vanilla `stopPlayerActions` already filters by onlineID, so the old connection-scope branch is removed. Same-id resend detection now uses the `NetTimedAction` packet bound by the bridge (the only packet type it binds). The heartbeat runs every 256 `stopPlayerActions` calls (one line per 300 s): `interruptCalls interruptedAccepted sameIdResend rejectsSent rejectsSkippedNoConn unknownRefused logged suppressed anomalies mode ownerCheck`.

**Dispatch bridge reduced to an action-packet owner check.** 42.21 uses the wire `PlayerID.getID()` as the lookup/cancel key, but `PlayerID.isConsistent` only checks `id != -1` and that a player resolves; it does not compare the onlineID or the owning connection. With `playerIndex=-1` the server resolves the id through `IDToPlayerMap` to a player on another connection, and none of the four action packets has an anticheat check. A modified client can therefore cancel another player's actions with that player's onlineID, or send a Request that makes `stopPlayerActions` silently interrupt all of the other player's actions. The bridge now does three things:

1. Every `Action` packet (GeneralAction / NetTimedAction / BuildAction / FishingAction, any state) is dispatched only if its owner object belongs by identity to this connection's `players` and the wire onlineID equals the owner's onlineID. Otherwise it is dropped: `unknownRefused` is incremented and an `untrustedAction#` line is logged (20 lines per 10 s). With the probe mode off the check still rejects, but logs nothing. `-Dmdc.actionOwnerCheck=0|off` restores vanilla pass-through.
2. Packets that pass go to vanilla `processServer` unchanged: cancel, `NetTimedAction` `getAction/copyFrom`, and `OnFishingActionMPUpdate` after a Fishing Reject all run as in vanilla (the old Reject early-exit and scoped stop are removed).
3. While a `NetTimedAction` packet is being dispatched it is bound as the W10-C Request context (try/finally restore, re-entrant). `WorldSoundPacket` still goes to the [§2at](#2at) observer.

**Verification.** `MdcTimedActionProbeTest` runs four configurations (observe / enforce / off / `actionOwnerCheck=0`). For all four packet types, a real `write → parse` produces a Reject carrying another player's onlineID (GeneralAction through `setReject(id, player)` with a local slot, the others through index -1). With the check on, both players' same-id actions are untouched. In the owner-off negative control, vanilla really cancels the other player's action. A legitimate cancel goes to vanilla and removes only the sender's own same-id action, and Fishing Reject + bobber-flag event data belongs to the sender. A forged Request (index 0 / -1) is rejected before `getAction/copyFrom`; with owner-off, vanilla flips the other player's action to Reject. Also covered: split-screen index 1, a stale owner object, an owner on this connection whose wire onlineID belongs to the other local player, and an empty or missing connection. SmokeCheck pins the W10-E retirement evidence (`remove` keyed by `(id, PlayerID.getID)`, `setReject` writes the sender), the reason for the owner check (`PlayerID.isConsistent` reads neither the onlineID nor the connection), the vanilla cancel entry of all four packets, that the bridge never calls `ActionManager.stop/remove`, and that `ActionManager` is not shipped.

**Hit counts.** `NetTimedActionPacket.processServer` 3 → 1; `NetTimedAction` 4 → 3 (`start` removed); `ActionManager` 5 → 0 (no longer patched); `PacketTypes$PacketType.onServerPacket` stays at 1. To deploy, fully uninstall with the old manifest first, so no stale `ActionManager.class` is left calling deleted helper methods.

---

<a id="2ak"></a>
## 2ak. Login-time enforcement of the per-Steam-ID account limit (W23, server, default on)

### Incident (2026-09-06)

The server admin wants one account per Steam ID. `MaxAccountsPerUser` had been 2 since July and did block some attempts (`MaxAccountsReached` rejections in `*_user.txt`), yet the whitelist held **1,284 accounts across 636 Steam IDs**, with up to 6 on a single ID. Setting the ini value to 1 only blocks new accounts; existing extra accounts still log in.

### Root cause (javap on the 42.20.4 jar)

1. `ServerWorldDatabase.authClient(String,String,String,long,int)` calls `isNewAccountAllowed` only when the username is not in the whitelist (offset 685). The existing-account branch returns at offset 643 **without any count**.
2. `isNewAccountAllowed` counts by the connection's `getSteamId()`. Under Steam Family Sharing that is the borrower's ID, while the whitelist stores the owner ID, so every borrower gets a fresh quota.
3. `LoginPacket.processServer` calls `setUserSteamID` on every login (offset 1320, `UPDATE whitelist SET steamid=? WHERE username=?`). `whitelist.steamid` therefore records the last user to log in, not the creator, and the count basis drifts when several people share one username.
4. If any row for the ID has `PriorityLogin` (role ≥ priority), the whole ID is unlimited (a vanilla exemption).

### Patch

The single `invokevirtual ServerWorldDatabase.authClient(…)` in each of `LoginPacket.processServer` and `GoogleAuthKeyPacket.processServer` is redirected to `invokestatic MdcAccountGate.authClient(ServerWorldDatabase,…)` (1:1, shape-preserving, receiver first; `expectedHits=1` each). The helper calls vanilla first and adds a quota check only when the result is `authorized`:

- It loads the whitelist rows for the key and sorts them newest first by `lastConnection` (NULL oldest; ties broken by id descending).
- An existing username is allowed only if **its rank < `MaxAccountsPerUser`**; a new username is allowed only if the existing row count is below the limit.
- Any `PriorityLogin` row exempts the ID, as in vanilla.
- On rejection the helper only sets `authorized=false` and `dcReason="MaxAccountsReached"` on the `LogonResult`. Logging and the `AccessDenied` packet then use vanilla's existing branch (offsets 218–223).

**No data is deleted**; uninstalling restores vanilla behavior.

The helper lives in `zombie.network`, not `zombie.mdc`, because `ServerWorldDatabase.conn` is package-private. Loose classes share the jar's classloader and runtime package; the jar is not sealed (manifest checked).

The identity key **defaults to the connection's `getSteamId()`**, the same as vanilla: each Family Sharing borrower counts separately. This is the admin's choice, since two different people were confirmed playing at the same time from one shared library. `-Dmdc.accountGate.key=owner` switches to `getOwnerId()` (preferring the whitelist `ownerid`), so the whole family shares one quota. Kill switch: `-Dmdc.accountGate=0`. Any exception fails open (returns the vanilla result and logs `[AccountGate] fail-open`).

Result: for each Steam ID, only the most recently used username can log in; the others get `MaxAccountsReached`. The rejection happens before `updateLastConnectionDate`, so a failed attempt does not change the ranking. Setting `MaxAccountsPerUser` back to 2 automatically allows the top two.

### Build-time checks and tests

- Vanilla precondition: exactly one 5-argument `authClient` in each packet's `processServer`.
- After patching: redirected ×1, original call count zero, real-instruction count unchanged. The helper delegates to vanilla `authClient` exactly once.
- `allowed()` is a pure function, tested for: rank < max allowed; over quota rejected; a new account judged by the existing count; empty list allowed; `PriorityLogin` exemption (including for a third username). The SQL ordering was checked against the production whitelist (ranks for a 6-account ID matched expectations).

### Acceptance

- Boot health check shows no `VerifyError` or `NoClassDefFoundError`; `grep -c 'AccountGate' server-console.txt`.
- First rejected login: `*_user.txt` shows `access denied: user "X" reason "MaxAccountsReached"`, and the console shows `[AccountGate] deny user="X" key=… rank=N accounts=M max=1` (rank ≥ max).
- Negative check: single-account players and admins (`PriorityLogin`) produce no deny lines.
- Any `fail-open` line means the SQL or schema changed; set `-Dmdc.accountGate=0` first, then investigate.

---

<a id="2al"></a>
## 2al. `%ld` format-string fix (W24, server, no switch) (retired: fixed upstream by TIS in 42.21.0)

**Retired 2026-09-28.** In 42.20.4, `GameEntityManager.checkEntityIDChange` used the C-style `%ld` in two Java `Formatter` strings (`idToEntityMap(%ld)=%s, expected %s` at javap offset 72; `idToEntityMap(%ld)=%s, expected null` at 144, which also had 3 args for 2 specifiers). They threw `UnknownFormatConversionException: Conversion = 'l'`, and no caller catches it. The exception propagated up and aborted the calling action: a build upgrade consumed materials without producing the result, a dismantle stopped midway, or a chunk unload aborted. Reported to TIS in [tis-bug-report-42.20.3-minor.md](tis-bug-report-42.20.3-minor.md), Bug 2. A Lua fix was unsafe, because the removal packet has already been sent and `idToEntityMap` may already be modified when the exception is thrown.

The patch made two `ConstChange` edits (`%ld` → `%d`, and `(entity=%s)` added to the second string for the third arg; `expectedHits=2`). These were pure LDC constant swaps: same stack shape, no helper, no kill switch (rollback = `uninstall.sh`). The entity-ID mismatch itself is a vanilla issue and was never addressed; the fix only turned it into one log line with the action completing normally.

42.21 changes the strings to `idToEntityMap(%d)=%s, expected %s` and `idToEntityMap(%d)=%s, expected null for %s` (offsets 72/158; 3 specifiers for 3 args). It also calls `remove(newID)` first when `storedNew` is an `IsoObject` with `getEntityNetID()==-1`, clearing the stale mapping (previously a call whose result was discarded). The patch matches 0/2 in 42.21, and its PatchConfig entry is removed. For acceptance, grep for the 42.21 text `idToEntityMap(`.

<a id="2am"></a>
## 2am. Thread-isolated serialization object pools (W25, server, default on)

### Motivation (2026-09-06, SaveAll measurement)

W15 established that `SaveAll` is a vanilla "full save = synchronous main-loop freeze" by design ([2ac](#2ac)); the only open question was whether it could be made shorter. The first candidate was raising the worker count 4→8 (the host has 8 vCPUs), so we first measured what the 4 workers actually do. An external probe detects the ≥2 anonymous `Thread-N` threads that `SaveAll` creates (every long-lived pipeline thread is named: `SaveChunk` / `LoadChunk` / `RecalcAll`), then samples per-thread CPU/state from `/proc` at 10 Hz plus up to 6 `jcmd Thread.print` dumps, and reconciles against the `SaveAll took` DebugLog line.

| Save event (players online) | 57 | 52 | 48 | 23 |
|---|---|---|---|---|
| `SaveAll took` | 4.05s | 3.33s | 3.20s | 2.20s |
| Worker phase (all 4 alive) | 3.84s | 3.20s | 2.78s | 1.98s |
| CPU per worker | 98–100% | 96–97% | 105% | 93–99% |
| Worker state samples | R 100%, zero D / zero S | same | same | same |
| Spread of worker end times | 0.42s | 0.21s | 0.20s | 0.20s |
| Main thread | 5%, `SaveAll:133 sleep` | 7% | 0% | 7% |
| `SaveChunk` (disk write) | 23% | 13% | 12% | 10% |
| Whole host, 8 vCPU busy | 67% | 49% | 43% | 57% |

- Pure CPU-bound serialization: not disk (writes are deduplicated by `ChunkChecksum`, `SaveChunk` only 10–23%), not lock waits; round-robin distribution is even; 2.5–4 cores stay idle.
- **80% of worker stack samples (43/54) are in `ConcurrentLinkedDeque.pollFirst/linkLast/unlink/skipDeletedSuccessors`.** Callers: `zombie.util.io.BitHeader` (four `static ConcurrentLinkedDeque` pools `pool_byte/short/int/long`: `poll()` in `getHeader`, `offer()` in each `release()`) and `zombie.core.utils.ByteBlock` (`pool_data_block`, same pattern). Every field header written does one poll + one offer on a global pool, so the 4 workers hammer the same head/tail cache lines, and each CLD `offer` also allocates a Node.
- Microbenchmark (JDK 25, one poll+offer pair): shared CLD 19–24 ns with 1 thread, **444–554 ns with 4 threads, 1169–2206 ns with 8 threads**; a ThreadLocal `ArrayDeque` is 4–10 ns regardless of thread count. ⇒ **Going 4→8 workers would amplify pool contention and make saves slower**; the correct fix is to remove the shared pool. (jstack has safepoint bias — CLD loop back-edges are poll points — so 80% is an upper bound; the real share is answered by the post-deploy `SaveAll took`.)

### Root cause (javap, 42.20.4)

```
BitHeader.getHeader(HeaderSize, ByteBuffer, Z):
   7: getstatic pool_byte   10: invokevirtual ConcurrentLinkedDeque.poll:()Ljava/lang/Object;   13: checkcast BitHeaderByte
  48: getstatic pool_short  51: invokevirtual …poll   |  89: getstatic pool_int  92: …poll   |  130: getstatic pool_long  133: …poll
BitHeader$BitHeaderByte/Short/Int/Long.release()V (all four identical, 7 real instructions):
   0: aload_0  1: invokevirtual reset  4: getstatic pool_x  7: aload_0  8: invokevirtual ConcurrentLinkedDeque.offer:(Ljava/lang/Object;)Z  11: pop  12: return
ByteBlock.Start:  0: getstatic pool_data_block  3: invokevirtual …poll
ByteBlock.End:   12: getstatic pool_data_block 16: invokevirtual …contains   (under $assertionsDisabled guard = dead code)
                 84: getstatic pool_data_block 88: invokevirtual …offer  91: pop
```

Across the whole jar, each of `pool_byte/short/int/long` has exactly 3 `getstatic`s (getHeader / release / `size()` in `debug_print`), and `pool_data_block` has exactly 3 (Start / End ×2) — no other consumer could disagree with a private pool.

### Patch

All sites are 1:1 shape-preserving redirects (receiver first; stack effect and instruction length unchanged) to `zombie.mdc.IoPoolIsolation`: the 4 `CLD.poll` in `getHeader` → `poll(CLD)Object`; the `offer` in each of the four `release()` → `offer(CLD,Object)Z`; `ByteBlock.Start` poll ×1, `End` contains ×1 + offer ×1. 6 classes, 7 methods, 11 sites; 2 helper classes (`IoPoolIsolation` + nested `Local`).

The helper knows no field names: it slots by identity of the pool instance passed in (per-thread `ThreadLocal<Local>`, up to 8 distinct pools each with its own `ArrayDeque`; 5 in practice; overflow pools delegate to vanilla and count `slotOverflow`). `poll` returns an object previously released by this thread, or null (caller allocates). `offer` pushes to this thread's pool LIFO (recently released objects stay in L1), capped at 1024 per pool (excess goes to GC; the vanilla global pool is unbounded), always returns true. Cross-thread alloc/release is safe (the object goes to the releaser's pool). SaveAll workers are new threads each time, so they start empty (per-thread residency = nesting depth, a few dozen 24-byte objects) and are GC'd with the Thread. The hot path has zero shared writes (no AtomicLong, no CAS); the banner is printed once via CAS when a thread first creates its `Local`. Each of the three redirect targets has exactly one delegation to vanilla (off / slot-overflow path).

The only semantic difference: `ByteBlock.End`'s `assert !Core.debug || !pool.contains(block)` now checks the thread-local pool — assertions are disabled on the production server, so that branch is dead code. The byte layout of headers/blocks is untouched; save and network formats are unaffected (allocation policy only). Kill switch `-Dmdc.ioPoolIsolation=0` (all three sites return to the vanilla shared pools).

### Build-time checks and tests

- SmokeCheck: vanilla preconditions (`getHeader` poll=4 plus one getstatic per pool; class-wide poll=4 / offer=0; all four `release()` exactly `ALOAD→INVOKEVIRTUAL→GETSTATIC→ALOAD→INVOKEVIRTUAL→POP→RETURN`; `ByteBlock` Start poll=1 / End contains=1 + offer=1; each of the five pool fields has exactly 3 getstatics jar-wide); post-patch (redirect counts, original calls gone, real instruction count unchanged, pool getstatics kept for identity slotting); helper contract (each of the three methods delegates to CLD exactly once, zero `NEW`, zero DebugLog). If TIS adds a 4th pool consumer or reshapes `release`, the build fails.
- `IoPoolIsolationTest` (on/off in separate JVMs, against the patched real `BitHeader`/`ByteBlock` from dist): bit-exact write→read round trip; same-thread LIFO reuses the same instance; 4 threads × 20000 nested allocations with no exceptions; with on, per-thread IdentityHashMap sets are disjoint, the global pool stays empty, per-thread residency = nesting depth; with off, the global pool receives releases; `contains`, cap 1024 + dropped, 9th distinct pool delegates to vanilla, `offer(null)` NPE.
- A/B benchmark (patched real classes, 3 alloc + 3 release per iteration): 1 thread 69→32 ns, **4 threads 1095→38 ns (29×)**, 8 threads 1858→44 ns.

### Acceptance

- After the next restart, compare `SaveAll took` / worker phase at similar player counts: the worker phase should shrink noticeably (upper bound 3–5×); workers remain ~100% R; `ConcurrentLinkedDeque` should disappear from jcmd samples, leaving real serialization (`IsoGridSquare.save`, `ErosionData`, `InventoryItem.save`, …).
- No linkage errors at startup; exactly one `[IoPoolIsolation]` first-activation banner line; `slotOverflow` should stay 0 in production (no periodic line; inspect via jcmd / test accessor).
- Re-evaluate 4→8 workers only after W25 is live (only then is it real CPU scaling). Not covered here: 0.4–0.7s of main-thread RUNNABLE after the workers finish (`ServerPlayerDB` / visited / `GameEntityManager.Save`).

---

<a id="2an"></a>
## 2an. Recipient filtering for spontaneous hutch syncs (W26, server, default enforce)

### Problem and scope (2026-09-09)

`IsoHutch.update()` calls `sync()` in two places: when dirt increases, and on the periodic / occupant-count-change path (3.5 s timer). Vanilla goes `sync()` → `sync(0)` → `IsoObject.syncIsoObject`, re-serializing and sending the full hutch state — including full data for every egg in the nest boxes — to every connection. A remote client that has not loaded that square simply drops the update in `GameClient.SyncIsoObject`.

- Inside the existing W17 `IsoHutch` ClassPatch, the two `update()` calls are redirected (shape-preserving) to `HutchSyncGate.syncUpdate`; no second ClassPatch overriding W17.
- **Only these two calls change**: the five `sync()` calls in the other four callers (`updateAnimalInside`, …) are kept; the generic `IsoObject` broadcast, player actions / remote relay, initial map load and save format are untouched.
- The original per-connection order `startPacket` → `doPacket` → original `syncIsoObjectSend` → `send` is kept; egg data is not split, cached or rewritten. When every recipient is skipped, the original `flagForHotSave()` still runs.
- off, non-dedicated paths, hutch subclasses and invalid objects go to the original `sync()`. Serialization/send exceptions are not caught by the helper (vanilla abort order preserved). If the new range check throws a RuntimeException, only that connection falls back to pass-through.

### Recipient window and teleport transition

Filtering applies only to trusted odd `chunkGridWidth ∈ {13,15,17,19}`. The radius is the **full window width `W*8` squares** (not W13's conservative half-width lower bound), using the union of the server-side positions of all four player slots and vanilla `RelevantTo`; any one inside the window means send. This is not an exact client loaded-set test but a deliberately generous far-distance exclusion.

Always sent (pass-through): not fullyConnected, clamped/even widths, `connectArea` during character switch or split-screen join, missing/non-finite positions, dead / no current square, unknown slot. Any character currently in a vehicle or in noclip makes the whole connection pass through.

**Teleports are not handled with a guessed short TTL.** The single `PlayerID.write` in `TeleportPacket.write` is redirected (shape-preserving) to `writeTeleportPlayer`: before sending, the character instance is recorded in a synchronized weak-key map, and that character keeps full hutch broadcasts for the rest of its lifetime. The key is the instance, not the online ID, so a character switch does not carry the exemption over; the map does not retain otherwise-unreferenced characters. No extra ACK, loaded-set or background thread.

If teleport bookkeeping throws a RuntimeException, `disabled=true` and the whole hutch filter reverts to the original broadcast; the original `PlayerID.write` still runs, outside the catch. `LinkageError` and other Errors are not swallowed. Trade-off: teleported characters keep receiving remote updates; we do not trade missed updates for more aggressive exclusion.

Since 2026-09-26 the decision code (including the teleport exemption set and the global degrade on bookkeeping failure) lives in the shared `RecipientWindow`, also used by the W36 GameEntity broadcast ([2ay](#2ay)); hutch behavior and counters are unchanged. The `TeleportPacket.write` redirect target is now `RecipientWindow.writeTeleportPlayer`. Teleport exemptions are recorded unless both `hutchSyncGate` and `gameEntityRelevancy` are 0.

### Modes and observability

`-Dmdc.hutchSyncGate`: unset = `1`/`enforce`; `2`/`observe` records decisions but still sends; `0`/`off` = vanilla; unknown values fall back to observe. **Restart required.**

On the first effective sync and every 5 minutes, `[MinidoracatJavaPatch][HutchSync]` prints cumulative counters:

- `calls` = effective hutch syncs; `considered/sent/skipped` = per-connection considered / actually sent / skipped.
- `wouldSkip` = connections matching the exclusion. `sentBytes` = PZ packet bytes actually serialized and sent — **excludes RakNet/UDP overhead and is not proof of client receipt**.
- `wouldSkipBytes` accumulates only in observe (when the excludable packet is actually written); enforce never serializes excluded recipients, so 0 there does not mean no savings.
- `passthrough/exempt` = uncertain / exempt decisions; `scopeErrors/logErrors` should be 0 and `disabled` false. No heartbeat in off.

### Build-time checks and acceptance

SmokeCheck pins: the context of the two `update` calls, the distribution of all seven original `sync` calls in the class, real instruction counts before/after the shape-preserving patch, the vanilla server broadcast branch and the helper's four-step order / hot-save, and negative controls for the other callers and load/save/send/receive; plus the single `PlayerID` write in `TeleportPacket`, the original ID write being outside the catch, XYZ/parse unchanged, and the `PlayerID.set/getPlayer` same-instance precondition. Any TIS change to these preconditions requires re-evaluation.

Local enforce/observe/off (separate JVMs) cover: window bounds and position union, split-screen, untrusted states / vehicle / noclip / teleport exemption, receiving the latest state after moving closer, hot-save with zero recipients, fertilized-egg data bit-identical to vanilla, serialization/send exceptions propagating, persistent pass-through after bookkeeping failure. A throwaway harness drove the patched `IsoHutch.update` sites and `TeleportPacket.write` from dist: vanilla over-sends to the remote connection; patched, the near connection receives, the far one is skipped, and after a teleport the character receives even though its server position did not change yet. Full build and two install round-trips passed; **this is not in-game client UI verification**.

Production acceptance: correct package loaded, mode=1, the anomaly counters above at zero, `skipped` increasing; then compare hutch `SyncIsoObject` packet traffic and sync behavior when approaching / re-entering, opening doors, collecting eggs and after teleport. The `skipped` ratio must not be quoted as the same ratio of server-wide bandwidth or FPS improvement.

---

<a id="2ao"></a>
## 2ao. RequestData ACK loop bound (W27, server; retired: fixed upstream by TIS in 42.21.0)

`RequestDataManager.ACKWasReceived` searched connections with `i <= requests.size()`, so an empty queue or unknown connection always hit `get(size)` and threw `IndexOutOfBoundsException` (late ACKs for completed/removed requests). The patch changed the method's single `IF_ICMPGT` to `IF_ICMPGE` (`<=` → `<`): same length, stack, branch target and frames, no runtime helper; RequestID matching, ACK protocol, send window and connection lifecycle untouched. It was a boundary fix, not a stuck-progress-bar or download-pipeline fix.

**Retired 2026-09-28**: 42.21.0 patch notes say "Fixed incorrect for loop condition in ACKWasReceived"; javap shows offset 15 changed from `if_icmpgt 64` to `if_icmpge 64` with instructions 0–85, operands and targets otherwise identical — matching our patched output instruction-for-instruction (empty queue / unknown connection / duplicate ACK / RequestID mismatch are equivalent). The PatchConfig entry, both SmokeCheck assertions, `RequestDataAckTest`, the build step and the now-unused `Patcher.IntComparisonChange` were removed. Revive: `git checkout 8d2bee8 -- patcher/src/Patcher.java patcher/tests/request-data-ack` (and restore PatchConfig / SmokeCheck / build.ps1).

---

<a id="2ap"></a>
## 2ap. PopMan unloaded-square spawn vs. background save mutual exclusion (W28, server; retired: 42.21.0)

`ZombiePopulationManager.addZombieStanding` / `addZombieMoving` called `n_addZombie` for unloaded squares without taking `saveLock`, while background `processPendingSaveCells` ran `n_saveRealZombies` / `n_saveCell` under that lock; a core dump showed `ObjectPool<popman::Zombie*>::clear` deleting the same pointer twice (shutdown `double free`). The patch redirected those two fallback calls to `PopManAddLock.addZombie`, holding the same `saveLock` around the native call (`-Dmdc.popmanAddLock`, default on).

**Retired 2026-09-28**: in 42.21 the two Java fallbacks still call `n_addZombie` outside `saveLock` (descriptor now `(FFFBIIIII)V`, adds a persistentId), but native analysis of 42.21 `libPZPopMan64` shows `ManagerWorker::saveCell` no longer touches `ManagerMain::m_zombiePool`; the path that pushed `saveRealZombieHack`'s `Zombie*` back into the Main pool and `n_saveRealZombies` / `beginSaveRealZombies` are gone, and the worker's `addRealZombie` deduplicates by id. The lock no longer protects anything, so the redirect, `PopManAddLock`, all W28 SmokeCheck assertions and `PopManAddLockTest` were removed (revive: `git checkout 8d2bee8 -- <files>`). If `double free` / `ObjectPool::clear` crashes reappear at shutdown, the corruption has another source and needs fresh investigation rather than reviving this patch.

---

<a id="2aq"></a>
## 2aq. Animal sync receive validation (W29, server, default enforce)

Hardens server-side validation: invalid animal sync requests are rejected as a whole before any game state is modified; valid requests keep the existing authorization, parsing and handling flow. Both reliable and unreliable sync are covered; no client or wire-format change, nothing for players to install.

The patch adds a single receive-call redirect to `AnimalUpdateGuard` inside the existing `GameServer` ClassPatch. It does not intercept after vanilla parsing (so leftover state in the shared packet object cannot continue to be processed); rejection does not throw an ordinary exception and does not kick or ban. Vanilla exceptions for valid and non-target packets propagate unchanged.

`-Dmdc.animalUpdateGuard=0`/`off` = vanilla; unset and unknown values = enforce; no observe mode; restart required. **Disabling re-exposes a confirmed validation gap** — do not turn it off just to reduce log lines. Only rejections log a `[MinidoracatJavaPatch][AnimalUpdateGuard]` Warning, at most 3 lines per 60 s window; `blocked` = total rejections, `suppressed` = rate-limited log lines, `logErrors` = exceptions in the logging path. A logging failure never lets a request through; no raw payload or player names are stored, and no intent is inferred from a single entry.

**Verification**: the state-protection counterexample fails against the vanilla receive entry; the new build preserves hutch and nest-box membership, covering the normal client writer, buffer boundaries, shared packet state, explicit off, unknown config, rate limiting and a real logger failure. SmokeCheck pins the single receive entry, the exact shape-preserving redirect, an upstream protocol fingerprint, and an unchanged client entry. Full local build and two isolated install/uninstall round-trips passed; this does not prove deployment, does not establish the cause of historical animal losses, and does not repair existing saves.

**42.21 re-validation (2026-09-28)**: the validation gap is still present in 42.21.0 and the upstream wire format and client writer are instruction-identical, so the helper is unchanged. The details will be reported to TIS privately rather than here. The only semantic change in the class is on the client branch; the old whole-class SHA flagged it together with line-number shifts, so the SmokeCheck fingerprint is now a whole-class text hash that ignores debug info (line numbers, local variables, SourceFile) while still covering `@PacketSetting`, fields and all methods; value updated for 42.21.

<a id="2ar"></a>
## 2ar. Bulk item registration for large containers (W30, server; retired: 2026-09-27, superseded by W45)

Redirected the single batch-registration call in `ItemContainer.addItemsToProcessItems` to `BulkItemRegistration`. Vanilla does a linear lookup in the existing list for each item in the batch; the patch, only when the existing list had ≥4096 items and the batch ≥256 non-null elements (exact `ArrayList`/`HashSet`, no aliasing, `Object.equals/hashCode`, non-`Comparable`), built a temporary identity index of the batch and scanned the existing list once, preserving order; any ineligibility fell back to vanilla before modifying state (`-Dmdc.bulkItemRegistration`, default on).

**Retired 2026-09-27**: W45 ([2bh](#2bh)) replaced `processItems` with an identity-indexed list, making `contains` O(1) and the vanilla per-item loop O(M), so the temporary index no longer helps; it also almost never triggered in production. Redirect, helper, SmokeCheck contract and behavior tests were removed; `ItemContainer` now has 4 sites (W5 ×2 + W41 ×2). Revive from git history.

<a id="2as"></a>
## 2as. Shared payload for fish-school broadcasts (W31, server, default on)

The two broadcast calls in `FishSchoolManager.updateSeed` / `updateFishingData` are each redirected once to `FishingDataBroadcast`. `GameServer.transmitFishingData` itself, single-connection replies, the client and the wire format are untouched; each recipient's header, locking and send flow are preserved.

The body is snapshotted from the first recipient whose write actually completes and reused for the remaining recipients of the same batch — no early serialization, no cross-batch cache. The connection list must be an exact `ArrayList` and both source maps exact vanilla Trove types; custom lists pass the whole batch through to vanilla, and a custom connection or an exception reaching this layer stops further sharing. On byte-order/capacity mismatch that packet uses the original field-by-field write, preserving partial-write-then-fail behavior. OOM while snapshotting only abandons sharing (with a warning); already-completed packets are still sent. Failures swallowed inside native Send, and the existing defect where a native Error leaves `sendLock` held, are unchanged. Sharing relies on updates happening on the same main thread; equivalence under arbitrary concurrent modification of the source data is not guaranteed.

`-Dmdc.fishingDataBroadcast=0`/`off` disables; any other value or unset enables; restart required. SmokeCheck pins the exact redirects in both callers, fingerprints of the original broadcast, lambdas and decoder, `ByteBufferWriter` being final, and that other `FishSchoolManager` methods are unchanged. Permanent regression tests use the real Java writer / send / client decoder, replacing only the native send leaf and injecting faults; they compare per-recipient bytes, decoded results, exceptions and lock state, including the counterexample of a custom connection list mutating the source between two packets.

**Acceptance scope**: the candidate was checked with a normal Steam client for live fish-school updates and player item round-trips; the release bundle had a full local build and isolated install/uninstall. Multi-recipient sharing is backed by offline real-Java packet-pipeline evidence, not multi-client field testing or measured production gains.

**42.21 re-validation (2026-09-28)**: `transmitFishingData`, both lambdas, both callers and client `receiveFishingData` are instruction-identical (recipients still all connections, same body field order); the shared body remains bit-equivalent, no code change. The three SmokeCheck FAILs were only `GameServer.java` line-number shifts; contract fingerprints are now methodText hashes that ignore debug info, giving the same value on 42.20.4 and 42.21.

<a id="2at"></a>
## 2at. Slow world-sound packet probe (server, default observe)

Wraps only `WorldSoundPacket.processServer` inside the existing authenticated packet dispatcher; other packets, authorization/parse order, the wire class and sound/fish data are unchanged. `MdcWorldSoundProbe` lives in the packet's package and reads the raw radius/volume directly (no reflection). The measured time covers sound creation, Lua events, fish-school scans and relaying; the total must not be attributed to any single stage.

- `slowCall`: a single packet taking ≥100 ms; records raw radius, volume, success/failure and the probe's batch sequence number.
- `slowBatch`: ≥100 ms accumulated between two `ServerMap.preupdate` hooks; records count, total time, max single-packet time and max radius. Settled at the next hook, so the last batch is not lost even without further sounds. `batchSeq` is the probe's own counter, not a vanilla frame number; the settlement line appears after the measured batch.
- Both detail kinds share a budget of 3 lines per 60 s; excess counts as `suppressed`. With new observations, a cumulative heartbeat every 300 s (calls / slow / failed / time / radius / batch / logErrors); the first heartbeat may print right after the first call. No player names or coordinates are logged.
- The existing watchdog's freeze snapshot includes in-flight radius, volume and duration, so a slow packet that never returns still leaves evidence. Volatile publish + reader acquire fence + sequence re-check prevent mixing fields from different packets; cumulative fields are diagnostic reads, not atomic snapshots. No extra thread; disabling the watchdog does not stop batch settlement.

Only the outermost dispatch on the main-loop thread is timed (nested time is included in the outer call, not double-counted). Calls before arming and from foreign threads pass through, counted as unarmed/foreign. The original method runs exactly once — no argument change, clamp or retry. Diagnostic RuntimeExceptions only increment logErrors; Errors deliberately fail fast (if a diagnostic Error coincides with an original exception, the finally-block Error can replace it). `-Dmdc.worldSoundProbe=0`/`off` disables; any other value or unset = observe; restart required.

Verification: full build and structural checks; observe / off / unarmed / real-logger-failure configurations; identity of original RuntimeException/Error; nested and foreign threads; rate limiting; batch settlement without follow-up packets. Accumulation thresholds are tested with injected completion samples (no sleep-bound assumptions), plus an integration smoke with a real vanilla zero-radius packet. This is not production acceptance of real slow packets; after deploy, check the banner and correlate slowCall/slowBatch with stacks from the same period.

**42.21 re-validation (2026-09-28)**: after the dispatch bridge was slimmed (end of [2aj](#2aj)), `WorldSoundPacket` is still handed to this probe by the same `MdcTimedActionProbe.processServer`; call site and behavior unchanged (SmokeCheck "called exactly once from the existing dispatcher" still holds).

<a id="2au"></a>
## 2au. Offline animal catch-up probe (W32, server, default observe)

**Incident (2026-09-24)**: the same player ranch failed twice on chunk reload — once after a player reconnect, once when another player arrived at high speed. Each froze the main loop for ~13 s, immediately followed by 6 animal deaths in the same second, lots of dung on the ground, and the cleaner recording a pig herd at `pregnancies=12 unborn=140`. Both MainLoopWatchdog snapshots showed the all-vanilla path `ServerMap.preupdate → IsoChunk.doLoadGridsquare → AnimalPopulationManager.addChunkToWorld → AnimalManagerMain.fromWorker → IsoAnimal.updateStatsAway → AnimalData.hourGrow`.

**Hypothesis under test**: vanilla uses `worldAgeHours - zone.hourLastSeen` as the offline duration and runs `hourGrow`, breeding, egg laying and predator checks hour by hour. `DesignationZone.hourLastSeen` is only updated when both corners of the zone leave streaming; a large pen spans several chunks, so while a corner chunk stays loaded, an inner chunk that unloads and reloads gets a stale value and re-applies days of catch-up on every reload. The animal's own `timeSinceLastUpdate` (written by `unloaded()`) is the real offline time, used here as the reference.

**Patch**: all three `IsoAnimal.updateStatsAway(I)V` call sites in the jar are redirected 1:1 to `AnimalAwayProbe` — `AnimalManagerMain.fromWorker` ×1 (source=chunk) and `DesignationZoneAnimal.doMeta` ×2 (source=zone, also based on `hourLastSeen`). The helper reads the animal's own hours before delegating; vanilla runs exactly once, hours are not changed or clamped, exceptions propagate.

- Detail: when zone hours ≥ `-Dmdc.animalAwayProbe.detailHours` (default 24), one line per call with source / hoursAway / animalHoursAway / worldAgeHours / duration / died / animal type and position / zone id, name, hourLastSeen, rect, streamed; at most 30 lines per 60 s, excess counted as `suppressed`.
- Heartbeat: at most one line per 5 minutes when there were calls: calls / big / mismatch (zone hours ≥24h more than the animal's own) / died / max / sum / totalMs / maxMs / anomalies.
- `-Dmdc.animalAwayProbe=0` disables (pure pass-through); restart required.

**Interpretation**: steadily growing `mismatch` with hoursAway ≫ animalHoursAway confirms the hypothesis → a separate enforce patch (use the animal's own time or cap it). If they are close, the catch-up really is that long and `hourGrow` itself must be investigated. SmokeCheck pins the three-site census, the shape-preserving redirects in both methods, and the fact that vanilla reads `DesignationZone.hourLastSeen` (fails if TIS switches to the animal's own time → re-evaluate). `AnimalAwayProbeTest` covers observe/off, stale zone, missing timestamp, the zone path and exception propagation.

<a id="2av"></a>
## 2av. Birth breed guard (W33, server, default on)

**Incident (2026-09-24)**: NPE in `AnimalData.checkPregnancy → IsoAnimal.addBaby` (`getData()` is null). The baby had already entered the world with null data/adef; over the next ~2 minutes `IsoAnimal.update` threw 1289 NPEs, each aborting that tick's `IsoCell.ProcessObjects`, and the last two `AnimalPopulationManager.save` calls before shutdown also aborted on it. The mother's position was not recorded.

**Patch**: the single `addBaby()` in `checkPregnancy` is redirected 1:1 to `BabyBreedGuard.addBaby`. It repeats vanilla's lookups (`getDef(babyType)`, mother's breed, `getBreedByName`); if any is null, this baby is not born and null is returned (the caller discards the return value), logging mother type / ID / breed / babyType / position (first 64 entries). Otherwise it delegates to vanilla unchanged. A null `babyType` still goes to vanilla (which returns null itself). The 3 other spawn-story call sites in the jar (ranch, migrating herd, trailer story) are intentionally untouched. Kill switch `-Dmdc.babyBreedGuard=0`, restart required.

SmokeCheck pins the 4 call sites jar-wide, the shape-preserving redirect in `checkPregnancy`, and that vanilla passes the `getBreedByName` result straight into the constructor (fails if TIS adds a check → retire). `BabyBreedGuardTest` uses real `AnimalDefinitions` lookups to cover on/off, breed mismatch, missing baby definition, null babyType and exception propagation. Acceptance: when `[BabyBreedGuard] skip birth` appears, check which mod the breed comes from; the `adef is null` NPE in `IsoAnimal.update` should no longer appear.

**Correction (2026-09-26)**: the "null breed → null data" attribution above is wrong; the real cause is the constructor's check failing (see [2az](#2az)). W33 is kept as a conservative skip for breed mismatches.

<a id="2aw"></a>
## 2aw. Skip character sound parameters on the server (W34, server, default observe)

**Evidence (2026-09-25 evening peak JFR, 5 minutes, 13,966 main-thread samples)**: 224 samples (1.6%) in `IsoGameCharacter.updateEmitter → FMODParameterList.update → ParameterFootstepMaterial*`. On the server only animals actually reach `updateEmitter`: `IsoAnimal.update` calls it twice per tick, and `AnimalPopulationManager` calls it again after unload; `IsoPlayer` has a `!GameServer.server` guard, and zombies are not in a `MovingObjectUpdateScheduler` bucket on the server. Animals register FootstepMaterial / FootstepMaterial2 on the server (`IsoPlayer.initFMODParameters`, no server guard); computing them walks every object on the square, looks up the `FOOTSTEP_MATERIAL` property and calls `Enum.valueOf`.

**Why it is wasted work**: on the server the emitter is always a `DummyCharacterSoundEmitter` with no FMOD event instance. Values computed by `FMODParameter.update` are only consumed via `setCurrentValue` iterating `FMODLocalParameter.instances` (always empty on the server) and `startEventInstance` (never happens on the server); there is no other reader.

**Patch**: the single `FMODParameterList.update()` in `updateEmitter` is redirected 1:1 to `EmitterParamGate.update` (merged into the existing W7 `IsoGameCharacter` ClassPatch; W22 in the same ClassPatch was retired in 42.21.0). Three-state `-Dmdc.emitterParamGate`: `2|observe` (default; computes as usual, times one in 16 calls), `1|enforce` (skip when `GameServer.server`), `0|off` (pure pass-through); unknown values fall back to observe; restart required. One line every 5 minutes: `[EmitterParamGate] mode= server= calls= skipped= sampled= avgNs= estSavedMs= anomalies=`.

SmokeCheck pins 1 vanilla call site (1 class-wide), post-patch redirect 1 / original call 0 / real instruction count unchanged, and helper zero `NEW`, zero DebugLog, skip condition reading `GameServer.server`. `EmitterParamGateTest` (three JVMs) covers observe/enforce/off: non-server always computes, server+enforce never computes, sampled timing, delegated exceptions propagate. **Criterion for enabling enforce**: `estSavedMs` from the observe heartbeat ≈ ≥1% of main-thread time, with `anomalies=0`.

**Other conclusions from the same JFR**: `RBTrashed.trashHouse` / `isKidsRoom` 0 samples, `requestSaveCell` 8 samples, zero main-thread `ThreadPark` >20 ms, 85 deopts in 5 minutes — so several other candidate optimizations (kids-room memo, async save-cell, JIT steady-state tuning) were not pursued. New hotspots tracked separately: `UsingPlayerUpdateSystem.update` 5.9% (full scan of the IsoObject entity bucket every frame just to clear `usingPlayer` beyond 10 squares → [2ax](#2ax)), and `WorldSoundManager.getSoundAttractAnimal` 4.7% (the attributed line is a plain distance calculation; the real cost is inferred to be the preceding `getSoundAnimal` linear scan of the global soundList per animal — attribution is imprecise because `DebugNonSafepoints` was off).

<a id="2ax"></a>
## 2ax. Active using-player index (W35, server, default observe)

**Evidence (same 2026-09-25 evening-peak JFR)**: 5.9% of the main thread is in `UsingPlayerUpdateSystem.update`, hot on the line that reads `getUsingPlayer()` inside the loop (bytecode 44). Vanilla scans every entity in the IsoObject bucket each frame only to null out a `usingPlayer` who has moved beyond 10 squares / changed level / died; very few entities actually have a usingPlayer, so the cost is the memory access of touching every entity.

**Patch**: `GameEntity.usingPlayer` is private with exactly 7 putfields in the class — `setUsingPlayer` 1, `receiveUpdateUsingPlayer` 3, `receiveSyncEntity` 2, `reset` 1 (null only).
- head call at the start of `setUsingPlayer` (slots 0, 1, with the new value) and a tail call before each RETURN of the two receive methods (reading the value after the write); a non-null write adds the entity to `MdcUsingPlayerIndex`'s weak-reference set (does not extend entity lifetime).
- The single `EntityBucket.getEntities()` in `UsingPlayerUpdateSystem.update` is redirected 1:1: in enforce it returns a compact array of indexed entities that still have a usingPlayer, have the component and have the bucket bit set; entities whose usingPlayer is already null are dropped from the set. Vanilla's per-entity processing is independent and does nothing for entities outside the set, so the result is identical; only processing order differs.
- Self-audit: every 256 calls, count bucket members with a usingPlayer across the full table; more than the index = a miss (`missed`). In enforce, a miss permanently falls back to the vanilla full table and logs one `MISSED` line.

Three-state `-Dmdc.usingPlayerIndex`: `2|observe` (default; returns the vanilla full table, only tracks and audits), `1|enforce`, `0|off` (no tracking either); restart required. One line every 5 minutes: `[UsingPlayerIndex] mode= calls= bucket= active= activeMax= audits= missed= fellBack= anomalies=`. **Criterion for enabling enforce**: `missed=0` over at least one evening peak in observe, and `bucket` ≫ `activeMax`.

SmokeCheck pins usingPlayer being private, the distribution of the 7 putfields (fails if TIS adds a write site → the index would miss it), the shape-preserving `update` redirect with unchanged real instruction count, and the 1+1+2 tracking points in GameEntity. `MdcUsingPlayerIndexTest` uses a real `IsoObjectBucket` and the patched `setUsingPlayer` across all three modes: enforce returns only in-use entities present in the bucket, entities are dropped after being nulled, and a write bypassing the tracking points is caught by the audit and falls back to the full table.

---

<a id="2ay"></a>
## 2ay. GameEntity broadcast recipient window + CraftLogic sync change gate (W36, server, default enforce)

**Evidence (2026-09-26 production packet capture)**: PacketType 293 `GameEntity` was **17.5%** of outbound UDP (30 s, 9,589 packets, 9.41 MiB; only packets containing the anchor were counted, so this is a lower bound). In one minute, 107,641 tracking stamps had only 96 distinct values; the most common value appeared 39,159 times and was sent to all 20 online players — the item was `Base.DryGrass` on a drying rack.

**Vanilla path (42.20.4)**: while an automatic workstation is crafting, `CraftLogic.onUpdate` calls `sendCraftLogicSync` every 1000 ms (`UpdateLimit(1000L)`), serializing the entire CraftLogic via `save` (including in-progress input items and their modData) → `Component.sendServerPacket` → `GameEntityNetwork.sendPacketData(..., isIgnoreConnection=true)` → `INetworkPacket.sendToAll`, a server-wide broadcast regardless of distance. An IsoObject's entityNetID is built from its square coordinates + object index; if the client's `GameEntityManager.GetEntity` cannot find it the packet is dropped (only InventoryItem has an owner-inventory fallback), so clients that have not loaded the square gain nothing.

**Patch**:

1. The single `INetworkPacket.sendToAll` in `GameEntityNetwork.sendPacketData` is redirected 1:1 to `GameEntityBroadcastGate.sendToAll` (same descriptor). This covers the whole server broadcast exit: CraftLogicSync, SyncGameEntity, UpdateUsingPlayer and other components' `sendServerPacket`. The helper keeps vanilla's connection order, excluded-GUID and `isFullyConnected` conditions and calls vanilla `INetworkPacket.send` per connection; only entities with a trustworthy position (an IsoObject with a square, a VehiclePart positioned by its vehicle) are candidates for skipping. InventoryItem (`getX` returns `Float.MAX_VALUE` when not equipped; the client resolves it via the owner inventory), MetaEntity and non-finite coordinates always use the vanilla server-wide broadcast.
2. The recipient decision reuses W26: `HutchSyncGate.shouldSend` and the teleport exemption set were extracted into the shared `RecipientWindow` (behavior unchanged item by item). The window is the full width `W*8`, using the union of server entity positions and vanilla `RelevantTo`; untrusted odd widths, `connectArea`, character switch, death and other uncertain states, plus characters in vehicles, in noclip or that have been sent a teleport, always receive. The window is about twice the client load half-width (at most `(W/2)*8+7`).
3. The single `sendCraftLogicSync` in `CraftLogic.onUpdate` is redirected to `MdcCraftSyncGate.periodicSync`: send only when client-visible content changes. The signature covers each in-progress entry's integer percentage (the same `(int)(getProgress*100)` used by vanilla tooltip and overlay), count and identity of in-progress entries, current recipe (the recipe name at the packet tail), and DryingCraftLogic's displayed wetness (`%.0f%%`, plus a separate "> 0 means paused" bit). Owner identity is included so a pooled/reused component does not inherit a stale baseline. The helper lives in the crafting package to read the package-private list; wetness only exists in private `temporaryWetnesses`, read via cached reflection.
4. The single `sendCraftLogicSync` in `CraftLogicSystem.stop` is redirected to `explicitSync`: always sends and records the sent content as the baseline. Without this, "[A]@0% sent → cancel → same A restarts at 0%" would be misjudged by the periodic path as already sent. Vanilla `onStart` never sends; a craft start is sent by the next periodic sync as a list change.
5. If the signature cannot be computed (incomplete data, wetness field unreadable), send as vanilla and clear that CraftLogic's baseline.

Packet format and serialization are unchanged; players install nothing.

### State when a distant player comes into range (42.20.4, javap)

`PlayerDownloadServer.update` serializes server-loaded chunks live via `IsoChunk.SaveLoadedChunk`, not from disk: `IsoObject.save → saveEntity → Component.save`; `CraftLogic.save` writes the in-progress list (including elapsedTime) while crafting, and `DryingCraftLogic.save` appends per-entry wetness; the client restores via `IsoObject.load → loadEntity → CraftLogic.load → loadInProgressCraftData`. A client that just downloaded the chunk therefore has the state as of download time — **no catch-up send is needed**. A walking player is inside the recipient window well before the chunk enters load range, and receives every subsequent change sync. SmokeCheck pins this chain; any TIS change to a link requires re-evaluation.

### Costs and residual gaps

- Clients do not run CraftLogicSystem; progress comes only from syncs, so client-side progress and remaining time now step in 1% increments. For DryGrass (`time = 86400`, one game day) with this server's 1-real-hour game day, 1% ≈ 36 real seconds, so the tooltip's remaining time can lag by up to that. Server-side crafting timing is completely unchanged.
- Periods where a skipped client still holds the square (vehicle look-ahead load area, delayed relevantPos): vehicles and teleports are exempt; otherwise the client only lags until the next content change or chunk re-download.
- Chunks the server has not loaded and sends from disk behave as vanilla; out of scope.
- CraftLogicSync is still a full `save` (input-item modData still sent) and still precedes Lua `luaCallOnUpdate`; this patch only reduces count and recipients.
- The signature ignores the input items themselves; if a recipe or mod's Lua OnUpdate modifies input items (e.g. modData) mid-craft, clients see it at the next content change.

### Modes and observability

`-Dmdc.gameEntityRelevancy` and `-Dmdc.craftLogicSyncGate`, each three-state: unset = `1`/`enforce`; `2`/`observe` counts but still sends; `0`/`off` pure delegation; unknown values fall back to observe; restart required.

On the first effective call and every 5 minutes, one line each:

- `[MinidoracatJavaPatch][GameEntityBroadcast] mode= calls= unpositioned= considered= sent= skipped= wouldSkip= sentBytes= wouldSkipBytes= craftCalls= craftWouldSkip= passthrough= exempt= scopeErrors= logErrors= disabled=`. Bytes are the GameEntity body (12-byte header + EntityPacketData), excluding the 3-byte packet type and RakNet/UDP overhead; the payload is built before sending, so in enforce `wouldSkipBytes` is exactly the amount not sent.
- `[MinidoracatJavaPatch][CraftSyncGate] mode= periodic= sent= suppressed= wouldSuppress= explicit= sigErrors= wetness=ok logErrors=`.

### Build-time checks and tests

SmokeCheck pins: the argument context of the single `sendToAll` in the `sendPacketData` broadcast branch (GameEntity type, excluded connection = arg 3, values = {data, entity, component}) and the following release; vanilla `sendToAll` consisting only of GUID exclusion + fully-connected + send; post-patch redirect ×1, client and single-connection send unchanged, real instruction count unchanged; helper send outside the try, range check inside the try catching only RuntimeException; the chunk-state chain above; exactly 2 `sendCraftLogicSync` sites jar-wide (onUpdate after `limit.Check`, stop after `finaliseRecipe`); sync content = full save + broadcast exit; DryingCraftLogic not sending on its own and having `temporaryWetnesses`; both redirects ×1 with unchanged total real instructions in both classes; helper send counts, explicit sync sending before recording the baseline, baseline table being a `WeakHashMap`.

`GameEntityTrafficTest` (one JVM per mode) runs the patched real `sendPacketData` and real `CraftLogic.onUpdate` from dist: in-window receives, out-of-window skipped only in enforce, vehicles and untrusted widths receive, excluded connection and not-fully-connected behave as vanilla, wire bit-identical to vanilla `sendToAll`, InventoryItem stays server-wide; unchanged integer percentage not sent, percentage crossing and list growth sent, same identity/percentage restart after explicit sync still sent; recipe, owner and wetness-display changes alter the signature; signature failure sends. A variant of `explicitSync` that skips recording the baseline is caught by the tests. Full build and two install round-trips passed. **None of this is production acceptance.**

Production acceptance: correct package loaded, both switches `mode=1`, `scopeErrors`/`sigErrors`/`logErrors` 0, `disabled=false`, `wetness=ok`, and `skipped`/`suppressed` increasing. Then repeat the packet capture and compare the share of `86 01 25` (GameEntity) packets in outbound UDP (17.5% lower bound before the patch), and check a drying rack in game: tooltip progress when walking up from afar and when arriving by car, rain pause and wetness, output on completion. The `skipped` ratio must not be quoted as the same ratio of bandwidth saved.

<a id="2az"></a>
## 2az. Half-constructed animal guard + serialize-before-open for apop files (W37, server, default on)

**Incident (2026-09-26)**: a player's small pen re-streamed after 86 hours offline; vanilla `doMeta` applied catch-up to the same objects repeatedly (one hen five times, a chick twice), and the chick entered `AnimalData.grow` the next frame. Construction of the new adult failed; `grow` threw 808 NPEs at `newAnimal.getData().setAge`, then `IsoAnimal.update` threw 38,291 NPEs at `this.adef.turnDelta`. Each one aborted every step of `IngameState.updateInternal` after `IsoWorld.update` (`GameTime.update` inside `UpdateStuff`, Lua `OnTick`, GameEntityManager), so `worldAgeHours` stayed at 29622 for ~65 minutes. From then on, 7 saves in a row died with an NPE in `IsoAnimal.save`, and the tail of `QueuedSaveAll` (SGlobalObjects, GlobalModData, map markers, …) stopped being written. At the restart for a mod update, the shutdown hook died on the same NPE, the rest of the orderly shutdown was skipped, and the process ended in a SIGSEGV inside `steamclient.so` (same crash site as an earlier incident). That cell's `apop_X_Y.bin` was truncated to 0 bytes; on the next load `newLimit < 0` failed, vanilla `spawnAnimalsInCell` respawned wild animals, and the cell's original animals were lost. An earlier incident (another cell's apop file still 0 bytes) and the 2026-09-24 incident ([2av](#2av)) are the same pattern.

**Root cause (42.20.4, javap)**:
1. `IsoAnimal`'s positional constructors call `super()` → `IsoGameCharacter(IsoCell,FFF)`, which — for non-zero coordinates — already puts the object into the cell's objectList (safeToAdd) or addList. Only afterwards does `IsoAnimal` run `checkForChickenpocalypse()` / `checkForWater()`; if either is true, `init()` is skipped, leaving an object with null adef/data and animalId=-1. The chickenpocalypse branch calls `delete()` (`removeFromWorld` also withdraws it from addList); **the water branch does nothing, so the object enters the world**.
2. Callers (`grow`, `addBaby`) immediately NPE on `getData()`; `grow` throws before `parent.delete()`, so the chick stays in the world and retries every frame.
3. `AnimalCell.save()` opens `new FileOutputStream` (truncating) before serializing, and serialization only catches IOException.

**Correction to 2av**: W33's attribution "baby breed not found → null breed → null data" is wrong: the `AnimalData` constructor picks a random breed when breed is null (`Rand.Next(0, size+1)`, with a 1/(n+1) chance of IndexOutOfBounds) and never leaves data null. The 2026-09-24 `getData() is null` was also a failed constructor check. W33 remains as a conservative skip on breed mismatch.

No log shows which branch caused the first failure (`DebugType.Animal` is off by default; the vanilla water branch prints nothing). This patch's `ctor failed reason=` distinguishes water / chickenpocalypse / noInit.

**Patch**:
1. Tail call to `AnimalSpawnGuard.afterCtor` before every RETURN of the four positional `IsoAnimal` constructors (RETURN counts 3/3/2/2): if data is null and coordinates are non-zero, withdraw the object from objectList / addList following the same `isSafeToAdd` branch (the inverse of `IsoGameCharacter`'s add). The `(IsoCell)` load constructor uses 0,0,0 and is not touched.
2. The single `grow` in `AnimalData.checkStages` is redirected 1:1 to `AnimalSpawnGuard.grow`; W33's `BabyBreedGuard` now delegates to vanilla `addBaby` via `AnimalSpawnGuard.addBaby`. An NPE is swallowed only if a constructor failure happened during this call (addBaby returns null); all other exceptions propagate. The chick stays as-is and vanilla retries on the next update.
3. The only two `AnimalCell.save()` call sites in the jar (`AnimalManagerWorker.save`, `AnimalCell.unload`) are redirected to `zombie.characters.animals.MdcAnimalCellSave`: same `SliceBufferLock`, same file name, but the file is opened only after serialization succeeds. On RuntimeException the old file is kept, `dataChanged` is set again for retry, nothing is rethrown, and the rest of `QueuedSaveAll` and the shutdown hook complete normally.

Kill switches: `-Dmdc.animalSpawnGuard=0` (1+2), `-Dmdc.animalCellSave=0` (3); restart required.

### Build-time checks and tests

SmokeCheck pins the three reasons the patch exists (`getAddList` inside the `IsoGameCharacter` constructor, both checks in each of the four constructors, `FileOutputStream` preceding serialization in `AnimalCell.save`), `tailCallOk` on all four constructors with exactly +2×RETURN real instructions, shape-preserving redirects in `checkStages` / `unload` / `worker.save`, and exactly 2 `AnimalCell.save()` call sites jar-wide. `AnimalSpawnGuardTest` (on/off): withdrawal on both unsafe/safe branches, 0,0,0 and normal construction untouched, grow/addBaby swallow only construction-failure NPEs. `MdcAnimalCellSaveTest` uses the real `AnimalCell→AnimalChunk→VirtualAnimal→IsoAnimal` serialization chain: off reproduces the vanilla exception escaping and a 0-byte file; on the exception does not escape, the old file is preserved bit-for-bit, and healthy cells overwrite normally. Full build and two install round-trips passed. **The real `IsoAnimal` constructor cannot be built in the test JVM, so the constructor tail call has structural verification only, not execution verification.**

Production acceptance: when `[AnimalSpawnGuard] ctor failed` / `skip grow` appears, check position and reason; `this.adef is null`, `AnimalData.getBreed` NPEs and new `AnimalCell.load> Exception` files should stop; `[AnimalCellSave] serialize failed` should be 0 — if it appears, some other broken animal still reaches the save, but the old file was preserved.

### 42.21 re-validation (2026-09-28)

**TIS did not fix the half-construction problem**: all four positional constructors in 42.21 still only call `init()` under `if (!checkForChickenpocalypse(null) && !checkForWater())`, the water branch still does not withdraw, and `AnimalData.grow` still calls `newAnimal.getData()` right after construction. The patch-note item "animals not spawning due to an error on the server" corresponds to `AnimalManagerMain.fromWorker` additionally registering with `AnimalInstanceManager` and the new `IsoAnimal.removeFromUpdateLists()`; construction is untouched.

**Related 42.21 changes**:
1. The `IsoGameCharacter` constructor now adds to the cell via `IsoCell.addMovingObject` (body still the isSafeToAdd → objectList/addList branches) and **newly** calls `setMovingSquareNow()` when `cell != null`: the object enters the square's `movingObjects` inside the constructor (42.20.4 only set `current`).
2. `checkForChickenpocalypse` gained a `replacingAnimal` parameter: constructors pass null, only `grow` passes the new adult, so the original animal no longer sees the same-ID adult on the square and deletes both — TIS's companion fix for change 1.

**Impact of change 1**: withdrawing from objectList/addList is no longer enough. A water/noInit failure stays on the square: `IsoGridSquare.getAnimals` returns it; since the constructor's chickenpocalypse check runs before `init()`, a new object's animalId is always the initial -1, equal to the leftover's -1, so every later construction within 4 squares fails as chickenpocalypse (grow retries and fails on every update); and on chunk unload `removeChunkFromWorld` calls `unloaded()` on it, which NPEs on null `getData()`.

**What changed**:
- `afterCtor` determines the reason first (withdrawing from the square clears `current`), then, after withdrawing from the cell, calls `removeFromSquare()` for non-chickenpocalypse branches (removes from `movingObjects` of current/last/movingSq and from `staticMovingObjects`); the chickenpocalypse branch's vanilla `delete()` already includes removeFromSquare, so it is not repeated. Logs gain `removedFromSquare` and `squareRemoved`. If reason detection itself fails, anomalies is incremented and withdrawal still happens.
- SmokeCheck existence pins now: exactly 1 `IsoCell.addMovingObject` and exactly 1 `setMovingSquareNow` in the `IsoGameCharacter` constructor; `IsoCell.addMovingObject`'s body contains only isSafeToAdd / objectList / addList / two `Set.add` (3 calls total — fails if TIS adds another registration, so the inverse operation must be extended); the four constructors now count `checkForChickenpocalypse(IsoAnimal)Z`. Tail-call hit counts unchanged (RETURN counts 3/3/2/2).
- `AnimalSpawnGuardTest`: the two failed-construction cases now attach to a square, with new assertions for removal from the square's movingObjects and `squareRemoved=2`; the old helper fails these, the new one passes; the off configuration confirms the vanilla leftover.

<a id="2ba"></a>
## 2ba. Snapshot of the livestock-zone list during offline catch-up (W38, server, default on)

**Symptom**: players reported frequent unexplained animal deaths. W32 observed ~133 animals dying immediately after catch-up over ~36 hours (2026-09-25 to 09-26); of 42 with detail lines, 27 (64%) had been caught up 2–4 times in the same frame by the same zone's `doMeta`. Of all 3,057 detail tuples (frame, animal, position), 594 were processed more than once, with offline-hour sequences like 62→0→−61 ("catching up into the future"). `AnimalMetaPredator=false`, so offline predators are not the cause.

**Root cause (42.20.4, javap)**: `DesignationZoneAnimal.doMeta` iterates `this.animals` by index (8 `GETFIELD animals` in the method). `IsoAnimal.updateStatsAway` resets `zoneCheckTimer` and then calls `checkZone()` → `setDZone()`, which — even for the same zone — does `removeAnimal` then `addAnimal`, moving the animal to the end of the list. The index loop therefore catches up some animals repeatedly (each time adding another full offline period of `hourGrow`, age and `timeSinceLastUpdate`) and skips others.

**Patch**: in the same `doMeta` MethodOps (W32's two redirects kept): head call `AnimalMetaSnapshot.begin`, `end` before the single RETURN, and `animals(list)` inserted after each of the 8 `GETFIELD animals`. The first read after begin (after `check()` has rebuilt the list) takes a snapshot, and every later read in this `doMeta` returns the snapshot: each animal is caught up exactly once per loop, with unchanged catch-up logic. `doMeta` does not nest; if an exception skips `end`, the next `begin` overwrites. Kill switch `-Dmdc.animalMetaSnapshot=0`.

SmokeCheck pins the reasons it exists (`updateStatsAway` calls `checkZone`; `setDZone` removes before adding), head/tail/8 swap positions with +12 real instructions, and W32's two redirects. `AnimalMetaSnapshotTest` reproduces the vanilla loop shape: off shows duplicates and misses, on processes each animal exactly once.

Production acceptance: duplicate (frame, animal, position) tuples in W32 detail lines should drop to zero; `died` as a share of `calls` should fall.

<a id="2bb"></a>
## 2bb. Animal death ledger (W39, server, observe only)

Replaces turning on `DebugType.Animal` (the vanilla water branch prints nothing, and debugln output is very noisy). A head call `AnimalDeathLedger.onDeath` at the start of `IsoAnimal.OnDeath()` logs one line per death: `[AnimalDeath] animal=type#ID pos wild baby ageDays health hunger thirst zone hutch [catchUp=countx/hoursh catchUpAgoMs] via=<last 4 game frames>`. `catchUp` is reported by W32's three catch-up call sites and only tagged if a catch-up happened within the last 60 s; `via` distinguishes hunger/thirst, catch-up, player kill and butchering. At most 40 detail lines per 60 s; one line every 5 minutes: `beat deaths domestic wild afterCatchUp suppressed anomalies`. No behavior change; kill switch `-Dmdc.animalDeathLedger=0`. SmokeCheck pins the `aload_0 → onDeath` head call in OnDeath with +2 real instructions; `AnimalDeathLedgerTest` covers catch-up tagging, rate limits and null data.

<a id="2bc"></a>
## 2bc. Item-processing list null tolerance + off-thread write probe (W40, server, default on)

**Incident (2026-09-26 13:52–15:21)**: at 20–29 players server fps fell from 9.8 to 2–3; from 14:40 single frames froze 15–16 s. 65 of 78 (83%)
W15-watchdog main-thread snapshots were in chunk load → `IsoObject.addToWorld` → `ItemContainer.addItemsToProcessItems` → `IsoCell.addToProcessItems` →
`ArrayList.contains`. Meanwhile `IsoCell.ProcessItems` threw an NPE on every run (`"i" is null`, 738 times; zero in every other September session).
No GC allocation stalls, heap 44–54% after major GC — memory ruled out.

**Root cause (42.20.4 decompile + javap)**: the server runs `ProcessItems` every 5 s, walking `processItems` by index and calling `i.update()` /
`i.finishupdate()` with no null check. Once a null is in the list every pass NPEs there: later items are never evaluated, finished items never reach
`processItemsRemove`, and the list only shrinks on chunk unload, growing to "every item in every loaded container". Each `addToProcessItems` does a linear
`contains` over it, so entering an item-heavy area freezes the server; only a restart clears the null. Static analysis found no null source (all insertion
points reject null, packets are queued to the main loop, no Workshop Lua touches the list) — a cross-thread race was suspected.

**Patch**: the only `InventoryItem.update()` / `finishupdate()` calls in `IsoCell.ProcessItems` are 1:1 redirected to `ProcessItemsGuard`. For null it calls
nothing and `finishupdate` returns true, so vanilla moves it into `processItemsRemove` and `ProcessRemoveItems` drops it the same frame; non-null is unchanged.
The four `addToProcessItems` / `addToProcessItemsRemove` methods get a head call `touch`: a write from a non-main thread (main = the thread that first ran
`ProcessItems`) logs thread name and top 8 game frames (first 20, then every 1000th). Heartbeat every 5 min: `beat calls nulls size offThreadWrites anomalies`
(`size` shows regrowth). Kill switch `-Dmdc.processItemsGuard=0`. (W30's `BulkItemRegistration` fast path was removed when W30 retired.)

SmokeCheck pins the reason to exist (vanilla `ProcessItems`: zero null checks, one `update`, one `finishupdate`), both shape-preserving redirects, four head
calls (+2 real instructions each), and every other `IsoCell` method instruction-identical. `ProcessItemsGuardTest` runs the real patched methods from `dist`:
off reproduces the NPE and the stuck null; on removes it the same frame and records off-thread writes.

**Production (2026-09-26)**: after the 18:00/20:06 restarts `nulls=0`, list size 15k–31k; ~38 `off-main-thread write`/min (8,384 by 23:59). All 28 sampled
details were the `ServerPlayersVehicles` thread loading vehicles: `VehiclesDB2$SQLStore.loadChunk` → `BaseVehicle.load` → `setCurrentKey` →
`ItemContainer.AddItem` → `IsoCell.addToProcessItems`. Fixed by W41 (2bd).

**42.21 re-validation (2026-09-28)**: with W45 (2bh) live, `processItems.contains` is indexed, so 2bd's sampled linear scan (`touchAdd` calling
`contains(SENTINEL)` every 256 calls) and heartbeat fields `scanUsAvg` / `estScanMs` were removed; `addCalls`, `addAllItems` etc. remain. In 42.21
`IsoCell.ProcessItems` and the four write entry points are instruction-identical; other behavior unchanged.

<a id="2bd"></a>
## 2bd. Redirect off-main-thread item registration to the main thread (W41, server, default on)

**Root cause**: the only cross-thread writer W40 found is `ServerPlayersVehicles`: loading a vehicle in the background puts its key into the vehicle container,
and `ItemContainer.AddItem` unconditionally calls `IsoCell.addToProcessItems(item)` on the same `ArrayList` the main thread mutates. A resize in
`ArrayList.add` interleaved with a main-thread `remove` leaves a null or duplicate — the W40 null.

**Patch**: the only `addToProcessItems` call in `ItemContainer.AddItem(InventoryItem)` / `AddItem(String)` is 1:1 redirected to
`ProcessItemsGuard.addToProcessItems` (same ClassPatch as W5). On `GameServer.mainThread` it registers directly; other threads enqueue into a
`ConcurrentLinkedQueue` drained by `beginPass` at the head of `ProcessItems` (at most one cycle late). Above 100,000 queued items it falls back to the vanilla
direct write. An item removed before its deferred registration gets one extra `update()` and is dropped by `finishupdate()` — same shape as vanilla
"register then remove". `AddItemBlind` never registers and is untouched. Kill switch `-Dmdc.processItemsDefer=0` (independent of W40).

**W40 instrumentation extended**: `endPass` before `ProcessItems`' only RETURN times each pass; head calls `touchAdd` / `touchAddAll` count single and batch
registrations. Heartbeat adds `passes passMsAvg passMsMax addCalls addAllItems deferred drained pending overflow` (the HashSet-evaluation fields `scanUsAvg` /
`estScanMs` were removed in 42.21.0, see 2bc).

SmokeCheck: `BaseVehicle.setCurrentKey` adds the key via `ItemContainer` (reason to exist); both redirects shape-preserving; `AddItemBlind` untouched;
`beginPass` + `endPass` = +4 real instructions; head-call shape on all four write entry points. `ProcessItemsGuardTest nodefer` checks the vanilla direct write;
`on` checks queueing without touching the list and registration + processing on the next pass. Production acceptance: `off-main-thread write` → 0,
`deferred ≈ drained`, small `pending`, `overflow=0`, `nulls=0`.

<a id="2be"></a>
## 2be. Cap on animal catch-up hours (W42, server, default on)

**Evidence (20:06 session, 3.3 h)**: W32 logged 650 catch-ups; in 174 the zone hours exceeded the animal's own offline time by ≥24 h. 25 of 38 deaths were
within 60 s of a catch-up, including 8 raccoons in one pen dying together (`catchUp=2x/17h`, hunger 1.0, `AnimalMetaPredator=false`). W38 had already removed
duplicate catch-ups in doMeta (594/3057 groups → 1/649); the remaining problem is the hour count itself.

**Root cause**: `fromWorker` and `DesignationZoneAnimal.doMeta` use `worldAgeHours - zone.hourLastSeen`. `hourLastSeen` updates only when the whole zone leaves
streaming, so large pens with partial chunk reloads, or zones still streamed at shutdown, catch up days from a stale value. The animal's own
`timeSinceLastUpdate` (written by `unloaded()`) is a better start, but vanilla never refreshes it while the animal is alive, so always-loaded animals save a stale clock.

**Patch**:
- The three W32 catch-up redirects (`AnimalAwayProbe`) use `min(zone hours, own hours)`. No own record (-1: newborn/old save) → vanilla hours; clock in the
  future → 0; non-positive hours untouched. Can only reduce, never increase.
- The only `hourGrow(false)` in `AnimalData.update` (hourly while alive) is 1:1 redirected to `liveHourGrow`, which sets the animal clock to now, then
  delegates (same ClassPatch as W33).
- `-Dmdc.animalCatchUpCap=0` restores vanilla hours and no refresh; `-Dmdc.animalAwayProbe=0` disables only the observation, the cap still applies.

Animals still carrying an old stale clock on the first post-deploy load are uncapped (= vanilla) until they live one hour or unload once.

**Observability**: W32 heartbeat adds `capped cappedHours clockRefresh cap`; `sumHours` → `sumAppliedHours`. Per-event details (now with `applied=`) only when
zone hours exceed own hours by ≥24 h or the animal dies after catch-up. W39's death ledger `catchUp` records applied hours.

SmokeCheck: vanilla `AnimalData` never writes the animal clock and `update` has exactly one `hourGrow`; redirect shape-preserving. `AnimalAwayProbeTest`
(three configs): stale zone → 2 h instead of 200 h, future clock → 0, no record → 500 h kept, clock refreshed while alive; `nocap` → all vanilla. Production
acceptance: `capped` / `cappedHours` grow, `applied` ≈ `animalHoursAway` in `mismatch` details, fewer deaths within 60 s of catch-up.

<a id="2bf"></a>
## 2bf. 2026-09-27 log trimming, probe retirement and measurement additions (server)

The 20:06 session log (3.8 h, 53,126 lines) was dominated by TimedActionProbe 2,863, AnimalRelevancy 2,542, ChunkWriteGuard 1,946 and AnimalAwayProbe 824
lines. This round changes only log cadence/content, no patch behavior:

- **Heartbeats → 5 min**: AnimalRelevancyGate, AnimalRequestGate, ChunkWriteGuard (time gate after the count gate, CAS across threads),
  VehicleIntersectPrefilter, VehicleCouldSeeGate, AnimalLosGate, AnimalLosScan, W10-C beat (60 s → 300 s).
- **W10-C per-event details removed** (`negativeDuration#`, `interrupted#`, `performFalse#`, `otherOwnerSameId#`, `noOwnedMatch#` → counts only); only
  `untrustedAction#` kept (anomaly signal, always 0). All 1,600 negative durations were animations; 653 of 709 interruptions were ISWaitWhileGettingUp.
- **W39 `via` fix**: all 38 entries read `IsoPlayer.onKilled<IsoGameCharacter.Kill<…<die`; now skips `OnDeath`/`DoDeath`/`onKilled`/`Kill`/`die` and records
  the next 6 frames.
- **Retired**: W5-2 `ContainerAddCycleProbe` (40 sessions with `wouldCycle`/`depthCapped` = 0; W5 catcher stays), W20(a) `ContainerIdProbe` (0–3 square-null
  per session; ContainerID no longer patched), periodic WorldSound heartbeat (zero slow calls; slow-call details and watchdog `describeActive` stay).
- **Log-noise suppression #10**: `IsoChunk.removeFromWorld: vehicle wasn't removed from world id=` (3,002 lines, 678 ids, 5.7% of the session). On a dedicated
  server `IsoPlayer.players[]` is only written transiently by ServerLOS, so `BaseVehicle.removeFromWorld`'s passenger early-exit almost never holds, and
  vanilla immediately calls `removeFromWorld` again = normal unload path. The method's only `DebugLog.log(String)` is redirected to `LogFilter.log`
  (`LOG_PREFIX` startsWith). SmokeCheck pins "print then remove" and that the early-exit checks only `IsoPlayer.players`.
- **SpriteConfig +18 names** (≥4 lines/h over a 25.2 h window on 2026-09-26): six `Commercial_*`, MetalFloorLvl1, Wood_Crate_Lvl2, Floor_SummerGrass,
  WoodFloorLvl1, WoodenDarkDoorFrameLvl3, Composter, BrickFloorLvl1, Floor_SummerGrassCorner, ComposterShoddy, DoubleFenceGate, WoodenDarkWindowFrameLvl3,
  Floor_Concrete. BrickDoorFrameLvl2 (3.3/h) and below still pass. `LogFilterNoiseTest` mirrors 37 + 5.
- **Hutch sync change measurement** (W26, observe only): each spontaneous sync payload is compared byte-for-byte with that hutch's previous one; heartbeat
  adds `unchanged unchangedBytes`. No W36-style change gate until data is in.

<a id="2bg"></a>
## 2bg. Ground-item expiry sync + immediate item-transfer reject reply (W43 / W44, server, default on)

**Symptom (player report, 2026-09-27)**: dung on a livestock farm can't be picked up — the bar completes, nothing arrives, retries fail the same way, and the
items vanish after relog. Client log clean; the server session had 81 `ERROR: sendItemsToContainer: can't find world item with id=…` (29 ids, one retried 9
times), 70 while that player was at the farm. 0–104 such lines per session since 9/20 — not new.

**Why the action hangs (W44)**: ground pickup uses `ItemTransaction`, not W10's `NetTimedAction`. With a WorldObject source the server can't find,
`isConsistent` still returns 0 ⇒ Accept. When due, `Transaction.update()` fails and `TransactionManager.update` only calls `setState(Reject)` — **no packet is
sent** (only Done replies). The client sits in Accept until "duration + 10 s" times out; `isDone` is then true on the empty list ⇒ `forceComplete`: bar done,
nothing received. Stack pickups hang once per item.

**Where the ghost items come from (W43)**:
- `IsoGridSquare.load` discards expired ground items on load with no client guard (both client chunk receipt and local cache go through it, see 2n). The
  server only purges when it loads a chunk; a chunk that stays loaded keeps expired items in server memory while every client load purges them ⇒ the same
  square's `objects` list differs in length.
- `RemoveItemFromSquare` carries only the object index (`RemoveItemFromSquarePacket.set` uses `getObjectIndex`); with misaligned lists the client deletes the
  wrong object or silently skips an out-of-range index, leaving the picked-up item visible.
- Production uses `DayLength=3` + `HoursForWorldItemRemoval=24` ≈ expiry after one real hour; the list includes all `Dung_*`, `ChickenFeather`,
  `TurkeyFeather`, `Egg`, so livestock areas hit it most.

**W43 (no new bytecode change)**: the only path delivering server-loaded chunk data to a client is `SaveLoadedChunk` in `PlayerDownloadServer.update()`
(already redirected by W4-1 to `ChunkRequestPacker.saveLoadedChunk`). Before serializing, `WorldItemExpirySync.beforeSend` removes the chunk's expired ground
items via vanilla `GameServer.RemoveItemFromMap`, with conditions term-by-term identical to `IsoGridSquare.load` (including the vanilla quirk where the
`split("_")[0]` branch ignores dropTime/hours). Clients that already have the square get indices consistent with the server (relevance range `range/2+2`
chunks exceeds the client view); for the downloading client the remove packet is dropped (square not loaded; `delayPacket` only defers coop loading) and the
data already lacks those items ⇒ both sides agree. The server adopts what the client applies anyway; the visible purge rule is unchanged.
- Clock margin: the client loads after the server serializes, so the server purges early by `-Dmdc.worldItemExpiry.marginHours` (default 1 game hour
  ≈ 2.5 real min, clamped 0..24).
- Cost: one vanilla `RecalcAllWithNeighbours` per removed item (once each); chunks with nothing expired cost one 64×levels scan.
- Only `RuntimeException` is swallowed (counted; serialization proceeds as vanilla). Kill switch `-Dmdc.worldItemExpiry=0|off`. Heartbeat (5 min, only
  while downloads happen): `chunks removed removedSinceLast maxPerChunk anomalies`.

**W44**: the three `Transaction.setState` calls in `TransactionManager.update()` are 1:1 redirected to `MdcTransactionReject.setState` (package `zombie.core`
to read protected `entries` / `playerId`). It sets the state as before; for Reject on an `ItemTransactionPacket` it sends the object to that player's
connection like the Done branch does (Reject writes only id + state) ⇒ client `isRejected` ⇒ `forceStop`. Not sent if any entry's source is `Floor`: vanilla
floor→floor moves the item first and only then fails the >1.1 distance check, so the item really moved and the queue must not be interrupted. Known trade-off:
a multi-item transaction whose later item fails now aborts the remaining queue immediately (vanilla waits, then continues). Kill switch
`-Dmdc.transactionReject=0|off`. Heartbeat only on rejects: `rejects sent skippedFloor skippedNoConn anomalies`.

**Residual (vanilla, not addressed)**: add/remove packets for a square arriving mid-transfer are dropped by the client; purges done when the server later loads
a chunk the client read from disk aren't notified. Both leave ghosts, expected to be far rarer; fixing them needs client Lua removal by id (no such packet).

**Verification**: SmokeCheck pins the call/field-read counts of the `IsoGridSquare.load` discard condition (red if TIS changes it), `getObjectIndex` in
`RemoveItemFromSquarePacket.set` (reason to exist), exactly 3 `setState` and 1 `PacketType.send` in `TransactionManager.update` (red if TIS adds a Reject reply
= retire W44), and shape-preserving redirects. `WorldItemExpirySyncTest` proves term-by-term equivalence with vanilla (incl. both quirks) and knob parsing. No
local dedicated-server end-to-end run exists; production acceptance = `can't find world item` lines drop sharply, `WorldItemExpiry removed` grows with
`anomalies=0`, `TransactionReject sent` accounts for remaining pickup failures.

<a id="2bh"></a>
## 2bh. Identity index for the item-processing list (W45, server, default on; W30 retired in the same batch)

**Symptom (2026-09-27 evening peak)**: three new maps generating for the first time + 40–51 players → main-loop FPS 1–2. ~10% of main-thread samples in
low-FPS dumps were in `ArrayList.indexOfRange ← ArrayList.contains ← IsoCell.addToProcessItems ← ItemContainer.addItemsToProcessItems ← IsoObject.addToWorld ←
IsoChunk.doLoadGridsquare ← ServerMap$ServerCell.RecalcAll2/Load2`. W40 heartbeat (~79 min): `size=29892 addCalls=196495 addAllItems=7374174 scanUsAvg=37.4
estScanMs=283114` ≈ 6% of the main thread (upper bound: every registration a full-scan miss); another session ≈ 5.6%.

**Vanilla defects**:
- Both `addToProcessItems` overloads do a linear `processItems.contains` before adding. Chunk load scans the whole list once per container item; ground food
  re-registers every 5 s (`Food.shouldUpdateInWorld` via `IsoWorldInventoryObject.update`); `Food.updateAge` has another `getProcessItems().contains(this)`.
- The list is large because with `DaysForRottenFoodRemoval ≠ -1` (44 here) `Food.finishupdate()` returns false for perishable food, which stays resident:
  15k–30k items on this server.
- `ProcessRemoveItems` calls `processItems.removeAll(processItemsRemove)` twice per frame without an empty check, scanning the full list each time.
- The same class already pairs `processIsoObject` with `processIsoObjectSet` (and checks `isEmpty` before `removeAll`); `processItems` has neither.

**Patch**: before the only `PUTFIELD processItems` in the IsoCell constructor, insert `INVOKESTATIC ProcessItemsIndex.wrap(ArrayList)ArrayList` (new Patcher
primitive FieldPutWrap: stack 1→1, +1 real instruction, `ClassWriter(0)` keeps frames), replacing the new empty list with subclass `zombie.mdc.ProcessItemsIndex`.
No other IsoCell code, field type or getter changes; all callers (including Lua via the getter) benefit.
- The list stays authoritative for order/content; an IdentityHashMap holds per-element occurrence counts for `contains`. Identity is valid because nothing in
  the InventoryItem hierarchy overrides equals/hashCode (SmokeCheck, jar-wide). IdentityHashMap calls no element methods and leaves no tombstones (lesson of 2g).
- Empty `removeAll(HashSet)` returns false immediately (vanilla also returns false, content/modCount unchanged); other collection types run vanilla.
- Incremental sync on add×2, addAll×2 (snapshot first; invalid index left to vanilla), remove(int), remove(Object) (same `indexOf` loop as vanilla), set,
  clear; iterator/listIterator mutations route through these. Any other structural change is detected via modCount and triggers a full rebuild before the
  next query. The hot `removeAll(HashSet<InventoryItem>)` is subtracted incrementally and the removed count cross-checked; mismatch → rebuild.
- **Bulk sections** (replaceAll / sort / removeIf / retainAll, removeAll with a non-exact-`HashSet` argument incl. aliases of this list) run vanilla with
  linear `contains` inside the section and a full rebuild afterwards, success or exception (partial replaceAll doesn't bump modCount; a throwing comparator
  leaves TimSort duplicates; `removeAll(thisList)` empties its own argument).
- After `subList` is handed out the list is permanently linear (view writes bypass modCount) and its index is freed; `clone` returns a plain ArrayList;
  construction via `super()` + `addAll` keeps vanilla's default empty capacity.
- Only the creating thread (main: `GameServer.main → IsoWorld.init`, same thread as the main loop) uses the index; other threads write as vanilla, mark it dirty
  and query linearly. With W41, production shows `offThreadWrites=0`. Concurrency safety equals vanilla's unsynchronized ArrayList.
- Audit: every 4096th query is compared with a linear scan; mismatch → log + rebuild; 3 mismatches in on mode disable the index globally (last line of
  defense, not the correctness argument).
- `-Dmdc.processItemsIndex`: `1|on` (default), `2|observe` (maintain index, always return and compare with vanilla), `0|off` (`wrap` is identity); unknown →
  on; restart required. Heartbeat (5 min): `mode size keys lookups hits audits divergences rebuilds rebuildItems emptyRemoveAll offOwner views anomalies
  disabled`, plus a first-activation `owner=` line (should be the main thread).

**Cost and effect** (local microbenchmark: 30k resident items, 100k registrations, batch removeAll every 1000; `ArrayList.indexOfRange` is JVM-wide shared and
megamorphic in production, so the benchmark first makes its equals site megamorphic):

| Scenario | Vanilla per registration | W45 per registration | Empty removeAll (vanilla → W45) |
|---|---|---|---|
| megamorphic (close to production) | 43.0 µs | 0.30 µs | 12.4 µs → 18 ns |
| only Object seen (JIT inlines to ==) | 6.0 µs | 0.25 µs | 9.2 µs → 16 ns |

Production W40 measured 29–37 µs, matching the megamorphic case; estimated saving ≈ 6% of the main thread (upper bound), more during heavy chunk loading. Not
the only FPS 1–2 cause (first-time map generation and mods' `LoadGridsquare` Lua are handled separately). The index costs ~1 MB at 30k keys.

**Verification**:
- SmokeCheck (upstream preconditions; red when TIS fixes it = retire): `processItems` is private final ArrayList with no companion index; one
  `ArrayList.contains` per `addToProcessItems`; `ProcessRemoveItems` has 0 `isEmpty` and 2 `removeAll`; exactly one `PUTFIELD processItems` jar-wide, all
  GETFIELDs in IsoCell; no equals/hashCode override in the InventoryItem hierarchy. Shape: `new ArrayList → wrap → putfield` in order, +1 real instruction,
  rest byte-identical, and no merge point (frame / jump-target label) between NEW and PUTFIELD (else another path could store an existing list); a synthetic
  negative control with such a merge must be rejected.
- `ProcessItemsIndexTest` (on / observe / off / `-XX:hashCode=2` all-collision, `-Xverify:all`): real patched IsoCell from `dist` vs a vanilla list; 200k-step
  random differential on content, contains and every return value; bulk/alias/exception cases compared with vanilla ArrayList; subList, global disable,
  cross-thread, clone; corrupted index caught by audit and disabled after 3. All 7 hand-made mutants caught. Independent critic review: no blockers; four
  SHOULD-FIX and four NITs fixed with regression cases.
- Production acceptance: `owner=` is the main thread; `divergences=0 anomalies=0 disabled=false views=0`; low-FPS dumps no longer show
  `ArrayList.indexOfRange ← IsoCell.addToProcessItems`. (W40 `scanUsAvg` falling below 1 µs was the initial signal; that field is gone since 42.21.0.)

**Upstream report**: `docs/report/2026-09-27-processitems-linear-contains-tis.md` (draft, not yet submitted).

<a id="2bi"></a>
## 2bi. Force authorization resend after a VehicleCollide release (W46, server, default on)

**Symptom (2026-09-27 evening)**: ~180k lines of `Packets limit has exceeded for VehicleCollide` in two hours (86% of console output); `server-console.txt`
(20 MB cap) filled about every 80 min. Previously seen only once since August (1,974 lines during the 9/26 ProcessItems freeze).

**Packet captures** (inbound 15 s and 30 s; bidirectional 20 s for one client):
- 3–4 clients at a time, changing players and vehicles, 46–237 packets/s each. All 7,167 packets in 30 s were `collide=0` (release), zero requests; per-vehicle
  rate ≈ client frame rate. A normal collision (older capture) is 6 requests + 29 releases, then stops.
- Server positions for those vehicles matched the client (no ID misalignment) and updates kept flowing, but only with the passenger flag (16384), never the
  authorization flag (8192).
- Warnings come in 100 ms bursts (up to 1,730 lines) right after main-loop stalls: `isLimitExceeded` counts at processing time (`MaxPacketsPerSecond=1000`), so
  only the post-stall backlog trips it. Volume = stuck vehicles × stall length; the loop likely exists at other times too.

**Vanilla defect (42.20.4)**:
1. On hitting a server-owned vehicle, `BaseVehicle.authorizationClientCollide` sets `LocalCollide` locally without waiting and sends `VehicleCollide(true)`.
2. If a `LocalCollide` vehicle hasn't moved for 1 s, the client sends `VehicleCollide(false)` and resets the timer — i.e. every frame — until the server sends
   a new authorization.
3. Server authorization sync is diff-based: `ServerVehicleState.shouldSend` compares with the connection's cached `netPlayerAuthorization` / `netPlayerId` and
   omits 8192 when equal.
4. If request and release are processed in the same server frame (stall ≥1 s, or coalesced packets), or another stuck client's per-frame releases immediately
   reset it to `Server`, the net change is zero and no authorization update is ever sent. The client stays `LocalCollide`, sending releases every frame until
   the vehicle leaves its range or it relogs. (Trigger inferred; the fix doesn't depend on it.)
5. `VehicleRequest` can't request an authorization resend; vanilla has no self-healing path.

A stuck client also simulates those vehicles itself, ignoring server positions, while the server ignores its `VehiclePhysics` as unauthorized; bumped
vehicles may move only on that player's screen (inferred, not observed).

**Patch**: head call at `VehicleCollidePacket.processServer` (slots {0, 2} = packet, connection; +3 real instructions; new ClassPatch) into
`zombie.network.packets.vehicle.MdcVehicleCollideResync` (same package, to read protected `isCollide` / `vehicleId`):
- Release packets only: take this vehicle's existing entry in `connection.vehicleStates` and set `netPlayerId` to `Short.MIN_VALUE` (vanilla only uses -1 or an
  onlineID). The next `sendVehicles` then includes 8192 with the current authorization; `netPlayerFromServerUpdate` clears `LocalCollide` on the client.
- Also resends when the server ignored the release because someone else drives (`Local`): the client turns `Local(other)` into `Remote`.
- A normal release already differs and is already sent, so nothing extra goes out; `setAuthorization` writes the cache back, so no repeated resend. No entry
  is created if absent (`getVehicleState` would trigger extra sync). Requests, packet format and `authorizationServerCollide` untouched; server-only.
- `-Dmdc.vehicleCollideResync=0|off` restores vanilla (anything else enables; restart required). RuntimeExceptions are counted, never block vanilla.
- Heartbeat (5 min, driven by releases): `releases invalidated noState noVehicle anomalies`, plus a first-activation line.

**Verification**: SmokeCheck (upstream preconditions; red = retire): `shouldSend` compares the cached `netPlayerId` before `SIPUSH 8192`; vanilla
`processServer` has exactly one `authorizationServerCollide` and doesn't touch `ServerVehicleState` / `vehicleStates`; `authorizationClientCollide` sets
`LocalCollide` locally. Shape: head `aload_0 / aload_2 / invokestatic`, exactly +3 real instructions; helper has one `PUTFIELD netPlayerId` and zero
`getVehicleState`. `MdcVehicleCollideResyncTest` (on/off, `-Xverify:all`) drives the real patched `processServer` and real `shouldSend`: on resends
authorization once then stops, off doesn't; ignored release (`Local(5)`) is sent back; requests don't touch the cache; no entry created. Production
acceptance: a new 30 s inbound capture shows no `collide=0`-only loops, post-stall warning spikes drop sharply, `invalidated` grows with `anomalies=0`.

<a id="2bj"></a>
## 2bj. Spatial prefilter for animal line-of-sight (W47, server, default on)

**Data (2026-09-28, ~50 players, ~6 FPS)**: W18 showed ~254 real LOS checks per frame at 64 µs each ≈ 16 ms/frame (~9% of the main loop); W18-2 `objAvg=4339`
= every check walks all of `objectList` to find zombies/players within the ~12-tile threshold. `-Dmdc.animalLosN` was raised 3 → 5 the same day (−40% LOS
frequency); W47 cuts the cost per remaining check.

**Patch**: in W18-2 `AnimalLosScan.updateLOS` (on path), after prerequisites and before `spottedList` is cleared, call `AnimalLosIndex.tryHandle`; true = done,
false = original full scan (no state changed yet). A second FieldPutWrap in the W45 IsoCell-constructor patch replaces `objectList`'s `new HashSet()` with
`AnimalLosIndex.ObjectSet` (HashSet subclass, same capacity/iteration order, only counts successful `add`/`addAll`). `Patcher.MethodOps.fieldPutWrap` now
takes a list.
- Snapshot: targets (zombies, non-animal players) in iteration order, zombies bucketed into a 16-tile grid, plus each animal's position among targets.
  Rebuilt when frame number (`MovingObjectUpdateScheduler.getFrameCounter`), list identity, insertion count or size change; non-`ObjectSet` lists and null
  elements go to the full scan.
- Candidates per animal: zombies within threshold + `MARGIN` (16 tiles) plus all players, processed in iteration order by code statement-identical to the W18-2
  loop; the animal adds itself to `spottedList` at its own position. Candidate ranges over 1,024 grid cells (threshold ≳250 tiles) → full scan.
- Fast path only when `lastAlerted == 0` at entry; if a candidate's `spotted()` changes `lastAlerted` or the threshold, the remainder switches to full order.
  If any valid target follows the last valid candidate, one extra spotted prefix (`spottedChr = null`) is applied.

**Equivalence** (vs W18-2, itself bit-identical to vanilla + W3-3):
1. Far targets only apply the spotted prefix; with `lastAlerted == 0` decay is a no-op and each `spotted()` clears `spottedChr` first, so only "any valid
   target after the last candidate" matters.
2. Candidates keep iteration order, so `spotted()` call order, targets and distance bits match; the threshold is read per pair.
3. `objectList` is mutated in 10+ vanilla places and HashSet resizes reorder elements. Binding the snapshot to (insertion count, size) is sufficient: only
   insertions grow or resize, removals shrink and don't reorder the rest, so unchanged pair ⇒ identical membership and order.
4. An animal's `spottedList` holds only itself and `BaseAnimalBehavior` never touches it, so the result matches even if `spotted()` throws.
5. `spotted()` can run arbitrary code (XP, Lua `AddXP`) and mutate `objectList`; the full scan's iterator then throws `ConcurrentModificationException` unless
   the current element was last. W47 re-checks (insertion count, size) after each delegation and throws (`modifiedExits`) or ends accordingly.
6. Nested LOS checks triggered from those callbacks always use the full scan (`nested`; static buffers guarded with `finally`).

**Single assumption and monitoring**: zombies move ≤16 tiles between snapshot and check (players are always checked). A non-candidate zombie found inside the
threshold in the final pass is a violation (`lateFixes`, still processed as vanilla); on mode runs a full-scan audit every 64 checks (`auditMisses`; observe
every time). Any violation disables the fast path for the rest of the run and logs coordinates.

**Switch**: `-Dmdc.animalLosIndex` `1|on` (default), `2|observe`, `0|off`; restart required; effective only with `AnimalLosScan` on. Heartbeat (5 min):
`calls fast exactAlerted exactDomain exactSnapshot audits auditMisses tailSwitches lateFixes candAvg targetAvg rebuilds rebuildUsAvg disabled modifiedExits
nested anomalies`.

**Verification**:
- SmokeCheck: vanilla preconditions (`spotted()` clears `spottedChr` first; `BaseAnimalBehavior` never touches `spottedList`; `addMovingObject` defers via
  `isSafeToAdd`); both constructor FieldPutWraps directly follow their `new`, byte-identical to vanilla when removed, one `objectList` PUTFIELD jar-wide; helper
  work precedes the `spottedList` clear, same delegations as W18-2 (prefilter 2, threshold 1, DistanceTo 1, multiplier 1), zero Rand; exactly one `tryHandle`.
- `AnimalLosIndexTest` (`-Xverify:all`): 4,000 random worlds (invisible/ghost players, grabbing zombies, vehicles, z differences, throwing and list-mutating
  `spotted()`, nested checks, same-size swaps, resize reordering, huge thresholds) compare vanilla `IsoAnimal.updateLOS` with W47 on call sequence,
  `spottedChr`, `lastAlerted` bits, `spottedList`, threshold and exceptions — identical in on/observe/off and after violation-disable. All 11 hand-made mutants
  caught. Three critic rounds found five issues (size-only snapshot key, exception-exit `spottedList`, huge-threshold cliff, missing CME emulation, nested
  buffer overwrite); all fixed with tests.
- One-off benchmark (4,260 objects, 2,500 zombies over 1,200×1,200 tiles, 760 animals, 150 checks/frame, rebuild every frame): 4,780 µs → ~230–260 µs per
  frame, ~10 candidates per check out of 2,540 targets; `ObjectSet` iteration within ~2.5% of HashSet (noise).
- Production acceptance: `auditMisses=0 lateFixes=0 disabled=false anomalies=0`, `fast` dominates `calls`, W18 `losAvgUs` from ~64 µs to single digits.

<a id="2bk"></a>
## 2bk. Animal hearing measurement (W48) and spatial index (W48-2, server, default on)

**Vanilla**: every tick, per animal, the server calls `WorldSoundManager.getSoundAnimal` (`IsoAnimal.updateInternal → respondToSound`). The client only checks
the animal's `chunk.soundList`, but the server (`GameServer.server`) scans the whole global `soundList` for the loudest in-range sound affecting animals
(`stresshumans || stressAnimals`): cost = animals × world sounds. A 9/25 peak JFR put ~4.7% on the adjacent `getSoundAttractAnimal` (no
`DebugNonSafepoints`, so imprecise); W47 and `animalLosN` don't touch this path.

**W48 (measurement)**: in the existing IsoAnimal ClassPatch, the only `invokevirtual getSoundAnimal` in `respondToSound` is 1:1 redirected to
`AnimalSoundProbe.getSoundAnimal`, which times one vanilla call and returns its result (exceptions propagate, uncounted). Every 64th call it rescans with
vanilla conditions to count `eligible` and `inRange` sounds, and it counts intra-frame list changes (`changesPerFrame`). Heartbeat (first line, then 5 min):
`calls frames callsPerFrame nsAvg usMax frameUsAvg frameUsMax list samples eligible inRange hits changesPerFrame windowPct anomalies` (`windowPct` = share of
wall time). Kill switch `-Dmdc.animalSoundProbe=0|off`.

**Measured 9/28 early morning (off-peak)**: `windowPct` 2.66–4.80%, `frameUsAvg` 3.2–4.1 ms (max 15), ~452–525 calls/frame, `list` ~4,000–4,300, `eligible`
~98%, `inRange` avg 12–14 (max 278), `hits` ~51%, `changesPerFrame` ~50–54, `anomalies=0`. Almost every sound affects animals (a filtered list won't help)
but few are in range ⇒ W48-2 partitions spatially.

### W48-2 spatial index

**Approach** (`AnimalSoundIndex`, called inside the W48 probe): examine only sounds that can be in range, each with vanilla's exact formula (same
`IsoUtils.DistanceToSquared(float×6)`, z×3, radius ×3 for wild animals, `!(distSq > r²)`, `volume × (1 − distSq/r²)`), and take the maximum, ties to the
lower list index — which is exactly what vanilla's "replace only if strictly greater" scan yields. Sounds are bucketed by `radius×3 + 2`: ≤64 → 64-tile grid,
≤512 → 512-tile grid (animal's cell + 8 neighbors), larger (ambient 600/5000, alarms, helicopters) always checked; outside those cells the horizontal distance
already exceeds the reach. Sounds that can never win in vanilla (not affecting animals, volume ≤ 0, radius 0 → NaN) aren't indexed.

**List changes**: FieldPutWrap `wrapSoundList` before the only `PUTFIELD soundList` in the `WorldSoundManager` constructor installs subclass `SoundList`,
counting appends and `set`; other changes are seen via `modCount`. Pure tail appends (`addSound`, ~50/frame) are indexed incrementally; anything else
(`update()` expiry every frame, `KillCell`, inserts, `set`, `removeIf`) triggers a full rebuild. `replaceAll`/`sort`/`removeAll`/`retainAll` and
`subList`/`reversed` view writes can bypass `modCount`, so they mark the list untrusted permanently (vanilla never calls them on `soundList`). Animal
coordinates are read first; index maintenance, query and audit then run under the same `synchronized(soundList)` lock as `addSound`, so concurrent appends
can't cause false mismatches (found by critic review).

**Vanilla fallback**: not a server; list not a `SoundList` or untrusted; null in list (vanilla NPE reproduced); non-finite or |coord| ≥ 1e7.

**Monitoring**: sounds in the list must not be mutated in place. SmokeCheck guards that all jar-wide writes to their coordinates/radius/volume/flags are in
`WorldSound.init` overloads, except `BodyDamage.TriggerSneezeCough` clearing `stressAnimals` after `addSound` (flags are re-read per query, so this only
removes a candidate, as in vanilla); `getNew` has 2 call sites and `release` 3. Manually verified on 42.20.4, not structurally guarded: `init` only on fresh
pool objects, `update()` removes before recycling, `KillCell` clears right after recycling — re-verify if those counts change. Every 256th query is compared
with vanilla; any mismatch disables the index for the run and logs the first 10 details.

**Switch**: `-Dmdc.animalSoundIndex` `1|on` (default), `2|observe` (compare every call, return vanilla), `0|off` (no wrap, fully vanilla); restart required.
With the index on, W48 samples every 1024th call. Heartbeat `[AnimalSoundIndex]` (5 min): `calls fast candAvg rebuilds rebuildUsAvg tailAppends entries far
fallback[notServer untrusted null coords] audits auditMisses observeMismatches disabled anomalies`.

**Verification**:
- SmokeCheck: `getSoundAnimal` reads `GameServer.server` and global `soundList` once each, with exactly one call site jar-wide (in `respondToSound`); redirect
  count 1, real instruction count unchanged; probe calls the index once, never vanilla directly, zero Rand. W48-2: SHA-256 of vanilla `getSoundAnimal`
  instruction text matches the audited version (any TIS change → re-audit); one `PUTFIELD soundList` jar-wide preceded by `new ArrayList`, followed by the
  wrap, byte-identical without it, +1 real instruction; helper uses the same `DistanceToSquared(FFFFFF)F`, catches only `RuntimeException`, zero Rand.
- `AnimalSoundProbeTest` (`-Xverify:all`): 400 random frames; every return is the same object as vanilla; counters match an independent recount; null →
  same exception type; off doesn't count.
- `AnimalSoundIndexTest` (`-Xverify:all`, four configs): ~70k queries over 240 random worlds identical to vanilla by identity, covering every list mutation
  kind, grid-line boundaries, negative/NaN/huge coordinates, negative radius, volume ≤ 0, radius 0, null, `subList`, throwing/re-entrant bulk operations, and
  600 concurrent louder appends under the lock (no false disable). In-place coordinate mutation is caught by the audit. All 16 mutants caught.
- Local one-off benchmark (4,300 sounds, 525 queries/frame, `update()` each frame): vanilla ~2.18 ms/frame vs index ~0.19 ms incl. rebuild, identical hits.

**Production result (9/28, active from 12:06:58, two afternoon off-peak sessions)**:
- 130,438 audit comparisons, zero mismatches (`auditMisses=0`, `disabled=false`, `anomalies=0`), all fallbacks 0; `calls − fast` = animals with no square
  (vanilla returns null). `candAvg` 139–197 (list ~3,900–4,700), about twice the benchmark, so the real speedup is smaller.
- W48 marginal values over comparable windows (`list` 4,000–4,450, 480–660 calls/frame): median per call 7.76 µs (n=17) → 1.21 µs (n=9); per frame 4.34 ms →
  0.69 ms; main-thread share 4.29% → 0.61% (non-overlapping ranges); per-frame max 12.5–16.4 ms → 2.3–3.8 ms. The server was mostly near its 10 fps cap, so
  this is headroom, not a proportional fps gain.
- Hit ratio 0.284 / 0.315 vs 0.278 before deployment (an early-morning 0.363 reflects world state, not misses — misses would show as audit mismatches).
- Per-frame rebuild cost crept up within sessions (110→190 µs, 80→131 µs), still < 0.2 ms; recheck at evening peak.

<a id="2bl"></a>
## 2bl. Player-built room XL-tree exception (client, 42.21.0; client patch package 0.2.2)

**Symptom** (reported 2026-09-29 by Player-I and Player-J; same as official reports
[101887](https://theindiestone.com/forums/topic/101887-42210-entering-player-built-rooms-adjoining-pre-built-structures-causes-exceptions-visibility-glitches/)
and [101955](https://theindiestone.com/forums/topic/101955-bugged-house-when-building-under-watchtower-b42/)): inside an enclosed player-built room adjoining
a pre-built structure or stacked on a pre-built single-storey house, furniture, trees, fences, street lights and windows disappear (still interactable) and the
ERROR counter climbs every frame. Both client logs contain only `Cannot invoke "IsoRoom.getRectsBounds()" because the return value of
"IsoGridSquare.getRoom()" is null at IsoTree.isPlayerInsideARoom(IsoTree.java:327)`, caught by `FBORenderCell.renderInternal` about once per frame (580
times; 415 in 7 s). TIS QA replied in 101887 (2026-09-28) that it is fixed internally for a later build, no date.

**Root cause** (javap, 42.21.0 jar `e1a69eb7`):
- `IsoTree.isPlayerInsideARoom(IsoPlayer)Z` is new in 42.21 (XXL-tree indoor fade): if offset 1 `invokevirtual IsoPlayer.isInARoom()Z` is true, offsets
  10–16 call `getSquare()`, `getRoom()`, `getRectsBounds()` with no null check.
- `IsoGridSquare.isInARoom()Z` = `getRoom() != null` **or** `getIsoWorldRegion().isPlayerRoom()` (`isFogMask()`: enclosed and `roofCnt == squareSize`).
- Player-built rooms get an IsoRoom only via client `WorldRegionToMetaGrid.clientProcessBuildings`, which drops regions for which
  `isAdjacentToOrOverlappingAPredefinedBuilding` is true; `isAdjacent`/`overlaps` use `bIgnoreZ=true`, so a second floor on a pre-built house counts. There
  `getRoom()` is null while `isInARoom()` is true.
- `FBORenderCell.renderInternal` wraps all of `RenderTiles` in try/catch, so everything after the first XL tree is skipped that frame. Only trees with `XL`
  in the sprite name reach the check (and only when not aiming at a visible tree and not in a vehicle).
- These IsoTree lines are the only new `getRoom().…` / `isInARoom()` uses between 42.20.4 and 42.21.0.

**Patch**: the only `invokevirtual IsoPlayer.isInARoom()Z` in `isPlayerInsideARoom` → `invokestatic zombie/mdc/TreeRoomGuard.isInARoom(IsoPlayer)Z` (3 bytes
for 3, stack 1→1, frames unchanged). The helper returns `isInARoom() && getSquare() != null && getSquare().getRoom() != null`: identical whenever vanilla
doesn't throw; where vanilla would NPE it returns false, so XL trees in such rooms skip the indoor fade (as in 42.20.4); `isPlayerCloseToARoom` unaffected.
First occurrence logs once per launch: `[MinidoracatJavaPatch][TreeRoomGuard] room without IsoRoom at x,y,z; XL tree room fade skipped`. In both standard and
low-memory variants; module version `v3.1`.

**Checks and verification**:
- SmokeCheck (both variants): vanilla preconditions — one `isInARoom` in `isPlayerInsideARoom` with `getRoom` immediately followed by `getRectsBounds` (red
  when TIS adds the null check = retire), and `IsoGridSquare.isInARoom` includes `IWorldRegion.isPlayerRoom`; patched sequence
  `aload_1 → TreeRoomGuard.isInARoom → ifne` locked, original call gone, instruction count unchanged; `isPlayerCloseToARoom` and `render` identical to vanilla.
  LoadCheck: `TreeRoomGuard.isInARoom(IsoPlayer)` is public static boolean.
- `TreeRoomGuardBehaviorTest` (per variant; real IsoTree/IsoPlayer/IsoGridSquare via Unsafe, real enclosed fully-roofed `IsoWorldRegion`): player-built room →
  false, no exception; pre-built room → true in range, false out of range; outdoors → false. The same state on the vanilla jar throws the exact NPE from the
  player logs.
- In-game (2026-09-29, Player-I, low-memory variant, `client patch v3.1-lowmem(a0bbfd6)`): one `TreeRoomGuard` line on frame 11 in the built second floor, then
  ~6.5 min across four locations (chunk parts 446→1372) with zero NPEs/`renderInternal` exceptions; furniture and outdoor objects render again. Unpatched, the
  same house threw every frame.

**Exit**: when an official build fixes it the SmokeCheck precondition turns red; remove the patch from `PatchConfig.client()` and delete helper and test.

<a id="2bm"></a>
## 2bm. Animal offline catch-up, fixed at the root (W49, server, default on)

**Symptom (player report, 2026-09-30)**: a pregnant cow's pregnancy barely advanced, and the whole farm grew slowly. NAS snapshots show the same herd
progressing at only 50–60% of game time: cow pregnancy 47% before W42 and 58% after, age 23% / 45%. The shortfall predates W42. Full evaluation and
evidence (Chinese): [animal-catchup-hook-save-design-v0.md](animal-catchup-hook-save-design-v0.md).

**Root cause** (four vanilla defects, confirmed against the production bytecode):
- Restart erasure: `IsoAnimal.save` writes the save time into the clock field instead of the animal's `timeSinceLastUpdate`, and
  `DesignationZone.streamed` starts as true and is not saved, so the first check after boot overwrites `hourLastSeen` with the boot time. Offline time
  between the player leaving and the next restart is never caught up.
- First entry catches up 0 hours: `fromWorker` derives hours from `getZone()`, and `connectedDZone` is not saved; every animal entering the world for the
  first time after boot, and every free-range animal outside a pen, catches up 0 hours. When a pen is only partly streamed, it catches up only to
  `hourLastSeen`.
- Remainder reset: `updateStatsAway` adds `floor(hours/24) × mod` to age in one step and sets `hoursSurvived` to `age × 24`, dropping up to 23
  accumulated hours on every call.
- Two `growUp` triggers: catch-up grows at calendar midnight, loaded animals every 24 accumulated hours, so simply keeping the remainder would double count.

**Patch** (W32's `AnimalAwayProbe` plus a new helper, `MdcAnimalSave`):
- Own-clock catch-up: the chunk path (`fromWorker`) catches up the animal's own offline hours and ignores the zone. The own clock is written by vanilla
  `unloaded()` on unload, refreshed every hour while loaded or in a hutch (W42's `liveHourGrow`, and this patch's hutch redirect), and advanced hour by hour
  by the catch-up loop, so no period is counted twice. The zone path (`doMeta`) keeps W42's `min(zone, own)`, which gives 0 for animals that stayed loaded.
  Wild animals and animals without a clock (newborns) keep vanilla hours.
- Unloaded animals save their own clock: the only `IsoAnimal.save` in `VirtualAnimal.save` is redirected to `MdcAnimalSave.save`, which records the animal
  being written in a `try/finally`; the only `getTimeInMillis` in `IsoAnimal.save` is redirected to `clockToWrite`, which returns the animal's clock only
  inside that context, only when the animal is not in `currentCell.getObjectList()`, and only for a valid clock. The context is consumed by the first clock
  write. `saveRealAnimals` collects in-world animals by exactly that objectList membership, on the same thread and right before serialization in
  `AnimalPopulationManager.save()`, so in-world animals (including ones just released from a hutch and not yet placed) still save the current time.
  Network packets, inventory items and hutches do not go through this context.
- Long-absence cap: one absence catches up at most `-Dmdc.animalCatchUpLimit` (default 168) game hours. Above that the clock is first moved to
  "now − limit" and the earlier part is dropped; the loop ends exactly at now, so later entry points see 0. Direct callers that bypass the three Java
  redirects are capped too: `entryHours` is inserted at the head of `updateStatsAway` (`aload_0; iload_1; invokestatic; istore_1`, linear, the slot
  stays an int). The livestock trailer's Lua `Vehicles.Update.TrailerAnimalFood` (offline hours derived from the vehicle part's last update) and admin
  commands keep their own hour source and only get the cap and the accounting (W32 counters, W39 ledger); a trailer animal may have had its clock
  refreshed by `liveHourGrow` just before, so the own clock cannot be used there. The cap holds for catch-ups that complete normally: vanilla adds age
  before its hourly loop, so if the loop throws halfway, a later catch-up of the rest adds age again. That is existing vanilla behaviour; the patch only
  counts it (`delegateFailures`). Animals without a clock (newborns, migration groups) keep vanilla hours with the cap applied, which is not the same
  guarantee as a reliable single absence.
- Accrued hours: the `setHoursSurvived`, `setAge`, `hourGrow` and `growUp` calls in `updateStatsAway` are 1:1 redirected. The one-step age and
  `hoursSurvived` reset is skipped; every hour accrues with the loaded formula from `AnimalData.update` (`hoursSurvived + 1`; after 24 accrued hours age
  becomes `daysSurvived + (mod − 1)` and `growUp(true)` runs); the midnight `growUp` is skipped. A 0-hour catch-up (`doMeta` on an animal that stayed
  loaded) changes nothing.
- Hooked carcasses are not caught up: only `fromMeta` is cleared. Vanilla catches carcasses up like live animals, changing their size and weight.
- Hutch clock: the two `setHoursSurvived` calls in `IsoHutch.updateAnimalInside` are redirected so the clock is refreshed every hour inside a hutch; a
  released animal is not caught up again by `doMeta` for time it already lived in the hutch.

**Gameplay**: animals now really grow while their owners are away; pregnancies can come to term and animals can starve if troughs run out. That is how
vanilla offline catch-up is designed; restarts had been hiding it on our server. 168 game hours is about 7 real hours on our server.

**Kill switches**: `-Dmdc.animalOwnClock=0` (own-clock catch-up and saving; also off when W42 is off), `-Dmdc.animalCatchUpLimit=N` (≤0 = no cap),
`-Dmdc.animalCatchUpAccrual=0`, `-Dmdc.animalCarcassGuard=0`; the hutch refresh follows `-Dmdc.animalCatchUpCap`.

**First restart after deployment**: existing apop files still hold the vanilla save time, so the first round catches up only from that point; an animal
carries its own clock once it has been unloaded and saved again.

**Observability**: the W32 beat adds `hutchRefresh ownCatchUps ownGainHours limited limitedHours directCalls directHours directLimited
directLimitedHours carcassSkips accrualGrowths delegateFailures maxRunMs maxRunCalls ownClock limit accrual carcassGuard`. `ownGainHours` is how many
more hours the own clock caught up than W42 would have; `direct*` counts direct callers (trailers, admins), with counts and hours but no timing;
`maxRunMs` is the longest accumulated time of adjacent catch-ups through the Java redirects (under 50 ms from the end of one to the start of the next,
usually one frame's batch), an approximation rather than an exact frame boundary. The observe path hands a single-use ticket to the animal it is
catching up, so a mod callback that directly catches up another animal during that call is still capped. The `[AnimalSave]` beat counts
`ownClock saveNow` (records saved with the animal's clock vs the current time).

**Expected result and acceptance**: for the afternoon of 2026-09-30 (208.8 game hours, two unloaded stretches totalling 147 hours, under the cap) the
pregnancy should advance about 8.7 days instead of the measured +4. In production: NAS snapshots of the `<cell-K>` farm approach 100% progression (minus
absences above the cap), `maxRunMs` shows no main-loop stall from the larger catch-ups, and W39's post-catch-up deaths do not rise abnormally.

**Guards**: SmokeCheck pins four vanilla preconditions (`fromWorker` catches up 0 when `getZone()` is null, `IsoAnimal.save` writes the save time into the
clock field, `updateStatsAway` resets `hoursSurvived` to `age × 24` in one step and calls `growUp` only in the midnight branch, the hutch never refreshes
the clock) plus the loaded growth formula in `AnimalData.update` that the helper copies. The `entryHours` insertion at the head of `updateStatsAway` is
locked in order with exactly +4 real instructions, and the rest must match the vanilla text with only the four redirect sets replaced. The patcher gains
one primitive, `HeadIntFilter` (an int-parameter filter at the head of an instance method). `AnimalAwayProbeTest` runs five configurations (shipped,
observation off, W42 off, own clock off, carcass guard off; it also checks the adjacent-run accumulation and the delegate failure count),
`AnimalCatchUpAccrualTest` calls the patched `updateStatsAway` directly (accrual on and off, including a trailer-style direct call of 200 hours capped at
168), and `MdcAnimalSaveTest` covers every clock decision.

<a id="2bn"></a>
## 2bn. Hooked carcasses keep their hook state in saves, and W37 retries no longer duplicate animals (W50, server, default on)

**Symptom (2026-09-30)**: an unknown live pig appeared on a farm. It was a boar carcass from a butcher hook: the noon save still had it on the hook, an
afternoon save turned it into a live animal, and after a restart it walked around. A scan of all 2,973 apop files found 3 animals with carcass modData,
all already alive again, and not a single carcass still correctly on a hook.

**Root cause**: `IsoAnimal.save` writes onHook=1 plus the hook coordinates only when `isOnHook() && hook != null && hook.getSquare() != null`. The `hook`
reference is not saved; a carcass loaded from disk gets it back only in `reattachBackToHook()` during its first `update()` in the world. If its cell is
saved before that (another animal in the cell unloads, or the carcass is still virtual in the worker), onHook=0 is written and the carcass loads as a live
animal. The hook itself was intact: its record is bit-identical in all three saves.

**Patch**:
- The only `IsoAnimal.save` in `VirtualAnimal.save` is redirected to `MdcAnimalSave.save` (shared with W49). After vanilla writes the record, if the
  animal is still on a hook, has no valid hook reference and carries the `attachBackToHook` coordinates restored at load, the record's tail
  `[0][petTimer][wild][onlineID]` is rewritten in the buffer to `[1][x][y][z][petTimer][wild][onlineID]`, the same format vanilla writes for a hooked
  carcass. Only apop saves are affected; network packets stay vanilla (the snapshot after reattaching already carries 1).
- Every check (flag, the three tail fields matching the carcass's current values, remaining capacity) runs before anything is modified. On any mismatch
  it throws `IOException`, W37 keeps the previous file and retries later; a record known to be wrong is never committed. `SliceY.SliceBuffer` is a fixed
  10 MiB and the affected cell is 124 KB, so running out of room is theoretical.
- Depends on W37: with W37 off, vanilla opens (truncates) the file before serializing, so a thrown exception would leave a truncated file; the patch turns
  itself off in that case.
- Without coordinates (both axes 0, the same test vanilla `reattachBackToHook` uses) it saves as vanilla and counts `noCoords`. That cannot happen in
  the normal flow: `load` restores the coordinates whenever it reads 1, and only a successful reattach clears them. Coordinates with only one axis at 0
  are valid and are kept.
- A hook that really is gone (hook square loaded, no `IsoButcherHook` on it): `afterReattach`, before every RETURN of `reattachBackToHook`, only observes
  and logs one `hook missing` line per carcass. Vanilla would save such a carcass as a live animal; the patch keeps it hanging, and whether to turn it into
  a corpse waits for data.
- W37 retries no longer write in-world animals twice: `saveRealAnimals` wraps in-world animals in a temporary list that vanilla clears only after the whole
  cell has been written. After a failure the list stays, the next round appends the same animals again, and each is written twice (a latent W37 issue
  since 2026-09-26). The head of `AnimalManagerWorker.saveRealAnimals` now clears lists left from the previous round (necessarily stale: this round
  collects them again); nothing is cleared at the moment of failure, so a retry before the next round still includes the in-world animals. W37 also marks
  `dataChanged` on `IOException` now.

**Kill switch**: `-Dmdc.animalHookSave=0`; the W37 retry fix follows `-Dmdc.animalCellSave`.

**Observability**: the `[AnimalSave]` beat counts `kept noCoords ioFail hookMissing`; the first time a carcass is kept, one `kept hook state` line is
logged. W37 logs `cleared stale real-animal snapshots` when it clears a leftover list.

**Verification**:
- Replay of the real incident file: the carcass record in the noon snapshot, rewritten to the onHook=0 that vanilla saved, comes out of the tail rewrite
  bit-identical to the original file (120,985 bytes).
- `MdcAnimalSaveTest`, four configurations: capacity and field boundaries of the tail rewrite, non-zero start and two consecutive records; no change for a
  valid hook reference, a non-carcass or missing coordinates; `IOException` handed to W37; vanilla behaviour with the patch or W37 off.
- `MdcAnimalCellSaveTest`: `IOException` keeps the previous file; fail once → clear leftovers → collect again → every animal written once; without
  clearing, written twice (the vanilla behaviour, as a control).
- SmokeCheck: vanilla decides onHook from the hook reference and ends the record with `putFloat(petTimer)` → `put(wild)` → `putShort(onlineID)`;
  `load` reads three coordinates after onHook; apop writes animals only through `VirtualAnimal.save`; `reattachBackToHook` clears the coordinates only
  after a successful reattach; `saveRealAnimals` only appends.
- In production: the daily scan of apop `deathTime` finds no newly revived carcasses; `kept > 0` means a save vanilla would have gotten wrong was caught.

**The 3 carcasses already revived**: left as they are; players can slaughter them like any other animal.

<a id="2bo"></a>
## 2bo. Animal ID miss log (W51, server, observe-only)

**Why**: MP animal timed actions (giving water, leashing, tying to a tree, loading into a trailer, hand-feeding) send the animal's online ID to the
server. When the server cannot find that ID, vanilla hands nil to the Lua action without logging anything, and the error only surfaces while the
action runs (for example an `animEvent` exception every 400 ms while giving water). The vanilla log does not show which ID the player sent, so "this
animal was never registered on the server" cannot be told apart from "the server removed it but the client still has it".

**Patch**: the only `AnimalID.parse` in the type 17 (IsoAnimal) branch of `PZNetKahluaTableImpl.load(ByteBufferReader, IConnection, byte)` is
redirected 1:1 to `NetTimedActionGuard.parseAnimalId`. The helper calls the original parse outside any try, so the decoded value and any exception
are exactly vanilla; only when `getAnimal()` is null does it count and log one line:

```text
[MinidoracatJavaPatch][AnimalIdMiss] id=<online ID> src=<caller> [type=<action> name=<name>] connectionPlayers=<onlineID:account|…> n=<total>
```

- `src` is the first stack frame outside the table decoder and the helper; the stack is walked only when a line is written. Timed actions show
  `zombie.core.NetTimedAction.parse`; other packets that share this decoder (`BuildAction`, `StatePacket`) show their own parse.
- `type`/`name` are added only when `src` is `NetTimedAction.parse`, taken from W10's parse context. That context is left behind when another parse
  exits early, so it is never used to attribute other packets.
- `id=-1` never resolves: vanilla does not register -1 (`IsoObjectID.incorrect`). For any other value, this line alone cannot say whether the animal
  was never registered or was removed.
- Shares W10's time window (at most 20 lines per 10 seconds); beyond that only `suppressed` grows. The W10 heartbeat (`[NetTimedAction] parses=…`)
  gains `animalIdMisses animalIdLog`.

**Relation to the W10-D2 removal**: this class stopped shipping on 2026-09-08 when D2 (guessing a replacement object in the shared decoder) was removed.
W51 ships it again for observation only and changes no decoded value. SmokeCheck pins that `load(…B)` is identical to vanilla except for this one call,
every other method is instruction-for-instruction unchanged, and the original parse is outside the helper's try. The reason for the patch is pinned
on the vanilla jar too: `AnimalID.parse` looks the ID up only through `AnimalInstanceManager.get`, and the type 17 branch returns `getAnimal()` right
after parsing.

**Kill switch**: `-Dmdc.animalIdMiss=0` (the redirect stays and only delegates to the original parse; nothing is logged or counted).

**Deployment**: the manifest gains `zombie/network/PZNetKahluaTableImpl.class` (117 → 118 classes). As usual, uninstall completely with the old
manifest, then install the new package, in the same window as a controlled restart. After it is live, first confirm the banner fingerprint is the new
build, then look for `animalIdLog=1` in the W10 heartbeat.

**Reading the log**: match it by time and account against the server-side guards in MinidoracatFixesFor42 (`MDFX_GiveWaterAnimalGuard nilAnimal`,
`MDFX_AnimalCompleteGuard nilAnimal`). `AnimalIdMiss` is logged when the server builds the action; the Fixes line follows while the action runs or when
it completes.

**Not done**: a server-side record of ID registration and removal (to tell "never registered" from "removed") waits until there are samples.

**Verification**: `NetTimedActionGuardTest`, four configurations (shipping plus three kill switches), with the real table decoder and the real
`NetTimedAction.parse`: a known animal decodes to the same instance and logs nothing; a missing one reaches the constructor as nil exactly as in
vanilla and logs exactly one line with the action type/name; a stale parse context does not label another caller as a timed action; 30 consecutive
misses are all counted while per-line logging is throttled; the kill switch logs and counts nothing.

<a id="2bp"></a>
## 2bp. Name guard for split-screen joins and respawns (W52, server, default enforce)

**Vanilla defect (42.21.0)**: split-screen joins and respawns of the primary player both go through `ConnectCoopPacket`. Stage 1 of `parse` reads the
name from the packet and only rejects an empty name or a name that is in any connection's `usernames[]`. It does not compare the name with the account
that logged in on this connection, and it does not check whether the name belongs to another account. Both branches ("replacing dead player" and "new
split-screen player") call `connection.setUserName(playerIndex, name)`, and stage 2 (`GameServer.receivePlayerConnect`) sets `player.username` to it.
Two ways in:

- **Split-screen** (`AllowCoop=true`, the vanilla default): the vanilla CoopUserName panel lets player 2 type any name (`isValidUserName` only checks
  the format). No modified client is needed.
- **Respawn of the primary player** (any `AllowCoop`): `parse` reads `allowCoop` only when `playerIndex != 0`; on respawn the client sends
  `IsoPlayer.username` through `ConnectCoopPacket.setInit`, so a client running modified Lua can call `setUsername` right after respawning.

Impact: checks that identify players by name treat the player as that account (`SafeHouse.playerAllowed` compares `getUsername()`, faction lists, many
mods), and the real owner cannot log in while the impersonator is connected (`LoginPacket` denies a connected name). The character data (loaded for
`connection.getUserName()`) and the role (taken from the connection) are not affected. Both ways were reproduced on a local 42.21.0 dedicated server:
a split-screen player joined under an offline account's name and the server created player 1 with that name; a respawned client renamed itself and
the server's player 0 took the other name.

**What a normal client sends**: on respawn the client sets player 0's `IsoPlayer.username` to `GameClient.username` (`LuaManager.assignUsername` /
`setPlayerMouse`), the name it logged in with. Both the client and `LoginPacket.parse` apply `trim()`, so it equals the server's
`connection.getUserName()`. The first join (`ConnectPacket`) uses `connection.getUserName()` directly. The server's name is written back to the
client's own `player.username` by `ConnectedPacket` (`bMe` branch), so both sides agree after a correction; the client never runs `parse`
(`handlingType=1`).

**Patch**: head call at `ConnectCoopPacket.parse` (slots {0, 2} = packet, connection) that binds this packet and the connection's login name; the
only `ByteBufferReader.getUTF()` in the method (the stage 1 name, javap offset 137) is redirected 1:1 to `readName`. Two patch sites, new ClassPatch.
The helper `zombie.network.packets.connection.MdcCoopNameGuard` (same package, to read the protected `playerIndex`) reads the name exactly as vanilla
does and returns the name the rest of vanilla's code will use:

- Player 0: always the account the connection logged in with (`connection.getUserName()`). Both branches use this value, so stage 2's
  `player.username` is right too. A connection without a login name gets an empty string.
- Players 1–3: an empty string if the name belongs to any account (`ServerWorldDatabase.containsCaseinsensitiveUser`: the whitelist, case-insensitive,
  the same table `LoginPacket` uses).
- An empty string takes vanilla's "No username given" rejection, which returns before `disconnectPlayer`, ID assignment, `setUserName` and the
  granted reply; the client runs vanilla `OnCoopJoinFailed`. An empty name is rejected as in vanilla and everything else is unchanged; the helper
  changes no connection state.

**Kill switch**: `-Dmdc.coopNameGuard`: unset / `1` / `enforce` = enforce (default; unknown values enforce too), `2` / `observe` = log only and keep
vanilla, `0` / `off` = vanilla. Restart required. A RuntimeException in the decision is counted in `anomalies` and vanilla proceeds.

**Log** (at most 20 lines per 10 s; names are truncated, control characters and quotes become `?`):

```text
[MinidoracatJavaPatch][CoopNameGuard] 首次生效 mode=enforce（-Dmdc.coopNameGuard=observe|off）
[MinidoracatJavaPatch][CoopNameGuard] renamed player=1/4 login="<login account>" sent="<packet name>" mode=enforce renamed=<n> rejected=<n> suppressed=<n> anomalies=<n>
[MinidoracatJavaPatch][CoopNameGuard] rejected player=2/4 login="<host account>" sent="<packet name>" reason=account mode=enforce …
```

Observe mode prints `wouldRename` / `wouldReject`; `reason=noLogin` means player 0 on a connection without a login name. The first-activation line
(`首次生效`, "first activation") appears with the first stage 1 packet after boot, usually the first respawn.

**Limits**: names that are not accounts remain a shared namespace: another host's split-screen player can reuse a non-account name and get the
safehouse or faction rights granted to that name (on 2026-10-03 the user chose to block account names only rather than derive names from the host).
`GoogleAuthKey` calling `connection.setUserName(packet name)` before it verifies and disconnects is out of scope: it changes the login name that
vanilla also uses to load the character, and it needs a modified Java client that beats the disconnect.

**Verification**:
- SmokeCheck (reasons for the patch; red when TIS fixes it = retire): in vanilla `parse` the only `getUTF` is stored in slot 4, both `setUserName`
  calls load slot 4 directly, slot 4 is written only once more (stage 2), and there is no `IConnection.getUserName()` call and no
  `ServerWorldDatabase`; `receivePlayerConnect` sets `player.username` from its name parameter; the first-join `ConnectPacket` passes
  `connection.getUserName()`. Shape: head `aload_0 / aload_2 / begin` in order, the method text (frames included) equals vanilla except
  `getUTF` → `readName`, exactly +3 real instructions class-wide. Helper contract: `readName` reads the name exactly once and not inside a try; the only
  database call is `containsCaseinsensitiveUser`; it writes no name.
- `MdcCoopNameGuardTest` (enforce / observe / off, `-Xverify:all`) drives the real patched `parse`, with account names looked up through the real
  `ServerWorldDatabase` in an in-memory SQLite whitelist. Enforce: player 0 sending another name gets the login name and is still granted; sending the
  login name changes nothing; a connection without a login name is rejected before any side effect; player 1 using an account name (different case) is
  rejected before `setUserName`, ID assignment and the granted reply; free names and empty names behave as in vanilla; the binding left by a stage 2
  packet is not used by the next connection's stage 1. Observe only counts; off reproduces the vanilla impersonation (negative control).
- In-game E2E (local 42.21.0 dedicated server with dist first on the classpath; two offline accounts created with `adduser` after boot). Enforce
  run: a normal respawn keeps its name; a client that calls `setUsername` with an offline account's name right after respawning is logged as
  `[CoopNameGuard] renamed player=1/4 login="test" sent="victim1"`, player 0 is still test and is granted as usual, and the client's own player 0
  name returns to test; a split-screen player with a free name joins; a split-screen player using the other offline account's name is rejected
  (server `rejected … reason=account`, client `access denied: No username given` and `OnCoopJoinFailed`). The same scenario with
  `-Dmdc.coopNameGuard=off` reproduces vanilla: player 0 takes the offline account's name and the split-screen player joins under the other one.

**Deployment**: the manifest gains `zombie/network/packets/connection/ConnectCoopPacket.class` and the helper (118 → 120 classes). Staged and armed on
2026-10-04 at 00:4x with the deferred-activation flow (no manual restart); the switch happened at the 06:00 scheduled restart: the console shows
`[mdc-java-patch] ACTIVATED` and `[mdc-javagate] OK: 120`, and the previous 118 classes are archived in the job's `state/`.

**Production acceptance (2026-10-04 21:34)**: all four sessions since 06:06 print the banner `server patch 13dbc29`; the 120 loose classes match the
manifest SHA by SHA; there are no linkage errors and no heartbeat reports a non-zero `anomalies`. There were 11 primary-player respawns (4, 0, 3 and 4
per session); each session with a respawn printed `[CoopNameGuard] 首次生效 mode=enforce` at its first respawn, and there was no `renamed` or `rejected`
line: normal clients send their login name when respawning, so the guard never had to act. Production runs `AllowCoop=false`, so there is no
split-screen sample.

---

<a id="3"></a>
## 3. Post-deployment verification checklist

1. **Boot health**: no `VerifyError` / `ClassFormatError` / `NoSuchMethodError` in the console; if present, uninstall immediately.
2. **Log-noise suppression**: the messages in section 1 no longer appear (#2/#4/#6 normally show within minutes of boot).
3. **No over-suppression**: in debug mode, ItemPickInfo diagnostics, other SpriteConfig warnings and anticheat `is not valid` still print.
4. **Behavior**: animal panel (admin cheat) shows stress recovering ~2× faster and gunshot increments ~1/3.
5. **Safehouse**: SafehouseClaimPacket repair **disabled since 2026-07-29**; only if re-enabled, a previously failing house claim should log a repair and then
   succeed under vanilla rules, and non-house coordinates must still get `building not found`.
6. **Container loot respawn**: native fixed containers (explored, looted, below `MaxItemsForLootRespawn`) on a map without TownZone and in a vanilla zone with
   `haveConstruction=true` restock after the next cycle; player-crafted crates, moved furniture and valid-safehouse containers don't; a released safehouse
   resumes only from the cycle after next.
7. **Login timing**: one line per op (three ops) on a controlled Steam login; unknown/duplicate/missing ops, non-decimal or negative `elapsedNs`, or leaked
   player identifiers = failure. Observation only; don't claim it fixes "server busy".
8. **Chunk unload**: compare FPS, black-edge reports and thread dumps at similar load; hot stacks should leave `Array.removeValue ->
   EntityBucket/EngineEntityManager`. On `VerifyError` or entity-membership anomalies, stop and roll back with `uninstall.sh`.
9. **Performance wave 1**: `[MinidoracatJavaPatch][VehiclePrefilter]` shows `rejected/(rejected+delegated)` > 0.9 (else consider rollback); no
   `ArrayIndexOutOfBoundsException` at boot (esp. `connectionAdded` — would invalidate the 512→256 bound; uninstall); vehicle behavior unchanged; vehicle share
   of fps-dip dumps drops from ~29%; growing `anomalies` = investigate.
10. **Fertilized-egg exemption retirement (2n, retired 2026-08-08; negative check)**: `zombie/iso/IsoGridSquare.class` and `zombie/mdc/FertilizedEggGuard.class`
    must be absent from `serverfiles/java/` — **a leftover redirected `IsoGridSquare.class` without its helper means `NoClassDefFoundError` on chunk load**
    (install.sh's unknown-loose-class scan fails closed); installed `patch-manifest.txt` must match this build's `dist/manifest.txt` exactly (never compare
    with historical counts), with no `IsoGridSquare|FertilizedEggGuard` entries; no linkage errors (`ServerChunkLoader` thread); no `[EggGuard]` lines;
    fertilized eggs on the ground now expire like normal eggs on both client and server. To hatch eggs, keep them in a hutch (`IsoHutch`), which isn't
    subject to the `IsoGridSquare.load` purge.
11. **W7 facing-direction isolation (2s)**: no linkage errors (hot `IsoGameCharacter`, chunk-loader thread); `Forward Direction cannot be zero` with
    `IsoAnimal.load` / `setForwardDirectionFromIsoDirection` in the stack should reach zero — **not the total**, since 66 of 67 pre-fix occurrences came from
    a separate `IsoDirections.TEMP` race (`createRealZombieAlways`, main thread) this patch doesn't cover; no new Forward Direction entries in `blam/`; turning
    unchanged. Restore a blammed chunk file into `map/` only after confirming the fix and only while the server is stopped.
12. **W8 chunk write gate (2t)**: no linkage errors; `ChunkWriteGuard` heartbeat `passed=N flagged=0` proves it is checking. In enforce mode a `BLOCKED` event
    means a wipe was prevented and its stack names the write path — archive the first 10 stacks with the `blamguard/` dumps. Observe mode prints `FLAGGED`
    and still writes (no protection). A block on the final unload/quit save rolls that chunk back to its last good save ("things went back half an hour" is
    the mitigation's cost). `flagged>0` with no new `blam/` entries = the gate is catching live corruption; growing `anomalies` = fail-open, investigate.
13. **W9 save pipeline isolation (2u)**: no linkage errors; one `ChunkSaveIsolation` first-activation line; main signal = W8 `flagged` stays 0 long-term,
    especially across several restarts (the shutdown `QueuedSaveAll` is the proven race; pre-fix baseline 8 in 2.5 h); no new CRC entries in `blam/`
    (`SANITY CHECK FAIL` gone); chunk save/load and client downloads unchanged. Disable with `-Dmdc.chunkSaveIsolation=0` in JAVA_OPTS + restart (off branch
    exercised at build time).
14. **Log-noise suppression #8, toxic log (2v)**: no linkage errors; check the fingerprint in the timestamped per-session DebugLog first (don't rely on
    `server-console.txt` being overwritten per restart — undocumented):
    ```bash
    LOG=$(ls -t ~/Zomboid/Logs/*DebugLog-server.txt | head -1)
    grep 'server patch' "$LOG"              # must be the new build
    grep -c 'Send Toxic Building' "$LOG"    # should be 0 (pre-fix ~10.6k–14.5k lines/h)
    ```
    Other Multiplayer-channel messages must still print (filter matches only the `Send Toxic Building at [ ` prefix); toxic damage and the health UI still work;
    the `sendToxicBuilding` packet/broadcast code is instruction-counted against vanilla by SmokeCheck.
15. **Ingredient weight memoization (2w) — retired 2026-09-02; historical.** It only ever ran in observe mode: measured 99.997% hit rate but only 328–732
    calls/s at ~2.1 µs ⇒ ceiling ≈ 0.11% of the main loop, not worth the global RNG shift and untested shared-instance path of `on`;
    `-Dmdc.itemWeightMemo=on` was never to be set.
16. **PZ update** (order matters):
    1. **Run `uninstall.sh` before updating** — loose classes aren't in the Steam depot, so `app_update` replaces the jar but leaves stale patched classes
       shadowing it; the same-source gate only blocks reinstalls. With unattended update automation, uninstall as soon as a new version is known.
    2. Re-fetch the jar → `build.ps1`.
    3. **A passing hit-count check ≠ correct patch site**: confirm constant patches with `javap` on surrounding instructions (42.20: `respondToSound` still had
       exactly one `20.0f`, so a stale offset would pass yet patch the flee distance — see 2b); for redirects confirm owner/method didn't move (42.20: the
       consistency log moved to `INetworkPacket.logInconsistentPacket` — see section 1).
    4. Redeploy only after all context checks pass, then rerun item 1.
