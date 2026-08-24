# [42.20.3][Dedicated MP] Vehicle becomes permanently invisible because saved wx/wy no longer match x/y

## Public-report draft

### Environment

- Project Zomboid 42.20.3 stable
- Dedicated multiplayer server, Linux, Java 25
- Observed on a high-population modded server
- Exact reproduction on a fresh unmodded save: **not yet confirmed**

### Summary

A vehicle may remain present and structurally valid in `vehicles.db`, with the correct physical `x,y` and serialized BLOB, but have an incorrect `wx,wy` chunk index. The vehicle then becomes permanently invisible after reconnect/restart because chunk loading queries vehicles by `wx,wy`, not by `x,y`.

Three independent incidents were observed within approximately one day:

1. Correct `x,y`; `wx,wy` changed to an unrelated non-zero chunk.
2. Correct `x,y`; `wx,wy` changed to `0,0`.
3. A second vehicle with correct `x,y`; `wx,wy` changed to `0,0`.

All three rows remained in the database with intact vehicle BLOBs. Recomputing `wx=floor(x/8)` and `wy=floor(y/8)` during a stopped-server window made the vehicles load again without replacing their serialized data.

### Player-visible symptoms

- Vehicle stalls or appears desynchronized while driving.
- Player disconnects/reconnects, or the server restarts shortly afterward.
- Vehicle is no longer visible at the final location or anywhere along the route.
- The database row still exists.
- Repeated chunk reloads do not recover the vehicle.

### Observed sequence

The clearest incident had this sequence:

1. The player entered a vehicle and drove a long distance.
2. No exit-vehicle action was recorded.
3. The connection disconnected while the player was still associated with the vehicle.
4. `GameServer.disconnectPlayer()` invoked `VehiclesDB2.updateVehicleAndTrailer()`.
5. The following hourly database snapshot contained the final physical `x,y`, but `wx,wy=0,0`.
6. After reconnect/restart, the correct chunk could not load the vehicle.

A second incident produced the same mixed row shape, except the incorrect `wx,wy` were another valid-looking non-zero pair.

### Expected behavior

`vehicles.db` must always satisfy:

```text
wx == floor(x / 8)
wy == floor(y / 8)
```

A stale, null, pooled, or recycled `vehicle.chunk` reference must not corrupt the persistent chunk index.

### Actual behavior

`VehiclesDB2$VehicleBuffer.set(BaseVehicle)` takes the fields from two independent sources:

```text
wx, wy <- vehicle.chunk.wx / vehicle.chunk.wy
x, y   <- vehicle.getX() / vehicle.getY()
```

No invariant check is performed before `SQLStore` commits the row.

### Technical analysis (42.20.3)

Relevant methods in the 42.20.3 dedicated-server jar:

- `BaseVehicle.update()` updates physical `x,y`, but only changes chunk membership when `current` is non-null and points at another chunk.
- `BaseVehicle.postupdate()` refreshes `current` afterward.
- `IsoMovingObject.findCurrentGridSquare()` returns null when the physical location is not currently loaded.
- `ServerMap.ServerCell.Unload()` removes/unloads chunk contents and queues vehicle persistence.
- `BaseVehicle.removeFromWorld()` does not clear `vehicle.chunk` on the dedicated server.
- `IsoChunk.resetForStore()` clears the chunk vehicle list and sets `wx=0; wy=0`, but does not clear back-references held by vehicle objects.
- The same `IsoChunk` object can later be obtained from `IsoChunkMap.chunkStore` and assigned another coordinate.
- `GameServer.disconnectPlayer()` saves the associated vehicle before clearing the player from it.
- `VehiclesDB2$QueueUpdateVehicle.init()` immediately snapshots the vehicle through `VehicleBuffer.set()`.
- `VehiclesDB2$SQLStore` writes the mixed fields without normalizing them.
- Chunk loading uses `WHERE wx=? AND wy=?`, so a mismatched row cannot be discovered at its physical `x,y`.

This produces a consistent lifecycle:

```text
vehicle keeps old chunk reference
→ chunk is reset for pool reuse (wx/wy become 0,0)
→ optional pool checkout assigns unrelated wx/wy
→ later vehicle save combines pooled chunk wx/wy with current physics x/y
→ database row becomes unreachable from the correct chunk
```

The `0,0` incidents match the state after reset and before reuse. The unrelated non-zero incident matches the state after reuse. Runtime object-identity logging has not yet captured the full interleaving, so this final identity link remains a high-confidence hypothesis rather than a direct trace.

### Related prior fix

The official 42.20.1 changelog includes:

- “Fixed an issue that caused significant performance problems with chunk unloading on multiplayer servers”
- “Fixed an issue where vehicles could temporarily disappear for players after another player disconnected”

The persistent database-index variant described here still occurs on 42.20.3. The relevant `VehicleBuffer.set()` and `IsoChunk.resetForStore()` field behavior is unchanged across the locally inspected 42.17–42.20.3 jars.

### Suggested upstream containment

At the persistence boundary, derive the chunk index from one captured physical position instead of trusting `vehicle.chunk`:

```java
float x = vehicle.getX();
float y = vehicle.getY();

buffer.x = x;
buffer.y = y;
buffer.wx = PZMath.fastfloor(x / 8.0F);
buffer.wy = PZMath.fastfloor(y / 8.0F);
```

This is a narrow persistence invariant. It covers add/update/disconnect/unload/trailer save paths and does not change vehicle physics or chunk membership.

A deeper lifecycle fix may also clear or rebind stale `vehicle.chunk` references before an `IsoChunk` enters the pool, but that has a much larger multiplayer/physics/towing risk surface. The persistence-boundary invariant is still useful as defense in depth.

### Suggested diagnostics

When `vehicle.chunk.wx/wy` differ from `floor(x/8),floor(y/8)`, log:

- vehicle SQL id
- both chunk-coordinate pairs
- physical x,y
- `System.identityHashCode(vehicle.chunk)`
- chunk reference count
- whether `chunk.vehicles` contains the vehicle
- thread and caller stack

Matching the same chunk identity across reset/reuse/save would confirm the final interleaving.

### Attachments to prepare before posting

- Redacted server log around disconnect and restart
- Redacted before/after SQLite rows for the affected vehicles
- Screenshot showing the vehicle absent at the final location
- `hs_err` for the coincident crash, clearly marked as possibly separate
- Mod list, while noting that no mod was observed writing `vehicles.db` chunk fields directly
