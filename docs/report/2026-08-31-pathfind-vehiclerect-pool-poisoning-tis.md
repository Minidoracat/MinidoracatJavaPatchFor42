# TIS bug report draft — 42.20.4 dedicated server: native heap corruption laundered through `VehicleRect`'s object pool

**Status: draft, not yet sent.** Contains no decompiled source: function names, offsets,
observed values and behaviour only.

---

## Summary

On a 42.20.4 Linux dedicated server, `PolygonalMap2::createVehicleClusters()` crashed with
SIGSEGV because `VehicleRect::alloc()` returned the value `0x30`, which is not a pointer.
A full core dump shows how that value got into the pool: an earlier
`VisibilityGraph::release()` walked a `VehicleCluster`'s rect array and pushed every slot
into the `VehicleRect` pool without validation — and one of those slots, together with the
array's glibc chunk header, had already been overwritten by something else. The core proves
the corruption and its shape; it does **not** identify the writer, so we describe it below
as an apparent short-range out-of-bounds or dangling-pointer store rather than a
demonstrated one.

The crash site is therefore **not** the site of the bug. Two separate vanilla design
issues turn one such corrupting store into a delayed, unattributable crash, and we would
like both addressed regardless of where the original store comes from.

## Environment

| Item | Value |
|---|---|
| Build | 42.20.4, build id 24909836, Linux dedicated server |
| JVM | Zulu 25.30+17-CA (25.0.1+8-LTS), ZGC |
| OS | Ubuntu 24.04, glibc 2.39, kernel 6.17.4 |
| Library | `linux64/libPZPathFind64.so`, sha256 `0777dda6db77ddd3059f27f94e0d56fae827b21436b5feb4d719e96878fd21c4` (unmodified Steam depot file) |
| Load | 20–80 concurrent players, 824 vehicle polys live at crash time |
| Frequency | 7 crashes with glibc heap-corruption signatures between 2026-08-22 and 2026-08-31 |

**Disclosure:** this server runs our own Java bytecode patches (loose `.class` files, log
redirection / metrics / throttling gates). They contain **no native code and no `.so`**.
No patched class lives in `zombie.pathfind.*`, and nothing we do touches `PolygonalMap2`,
`PathfindNative`, or any pooled native structure. For completeness: one of our helpers
*reads* the public float fields of `zombie.pathfind.VehiclePoly` on the **client-only**
branch of a vehicle visibility check (on a server it returns before touching it) and never
writes to it. No Workshop mod on this server ships native code. The corrupted objects are
native-only structures with no Java representation. We are happy to reproduce with all
patches removed if you need that, but we do not believe they are relevant to a corruption
inside a native object pool.

## Crash signature

```
SIGSEGV (0xb) at pc=libPZPathFind64.so+0x4ca19, tid=<pathfind thread>
Problematic frame: C [libPZPathFind64.so+0x4ca19] PolygonalMap2::createVehicleClusters()+0x89
... then, while the JVM was writing hs_err:
corrupted size vs. prev_size
Aborted (core dumped)
```

Note the hs_err file is **truncated at the `Host:` line** because glibc aborted inside the
JVM's own crash handler. Any 42.20.x crash of this family will produce a useless hs_err;
the core dump is the only usable artifact.

## What the core proves

Recovered the original SIGSEGV `ucontext_t` from the crash thread's stack (the core itself
is the later SIGABRT):

```
RIP    = libPZPathFind64.so + 0x4ca19      (movups %xmm0,(%rbx))
RBX    = 0x30                              (the value VehicleRect::alloc() returned)
CR2    = 0x30
trapno = 0x0e, err = 0x06                  (user-mode write to a not-present page)
R12    = 31                                (loop index; 32nd allocation of this round)
R13    = the PolygonalMap2 instance
```

`VehicleRect` pool state (`ObjectPool<VehicleRect>`, a `std::deque<void*>` used as a
front-push / front-pop free list):

```
total allocations counter        832
free-list entries               800   (all 800 distinct)
slot just popped (start.cur-8)  0x30
in-flight ArrayList this round   capacity 32, count 31, all 31 distinct
=> 800 + 31 = 831 accounted, exactly one allocation unaccounted for
```

The polluted owner:

```
VehicleCluster @X: capacity 4, count 2, rect array @A
  array[0] = 0x30                       <-- not a pointer
  array[1] = <a real VehicleRect>
glibc chunk of A:  size word at A-8 has been overwritten with a heap pointer value
                   (the neighbouring chunk header at A+0x28 is still intact: 0x35)
```

Timing, and the reason the crash site cannot identify the writer:

* the `VehicleCluster` above is **already in the cluster free pool** at crash time;
* the `VisibilityGraph` that owned it is **already in the graph free pool**;
* `PolygonalMap2`'s cluster list count is **0** — the crash happened in the first loop of
  `createVehicleClusters()`, before any cluster was built this round;
* `array[1]` is the **31st allocation of the current round** (index 30), i.e. the
  allocation immediately before the poisoned one.

That last point pins the mechanism exactly: `VisibilityGraph::release()` releases the
cluster's rect array in index order `0, 1`; the pool pushes to the front and pops from the
front, so those two entries must come back out as `1, 0`. That is precisely what the core
shows. **The `0x30` entered the pool through `VisibilityGraph::release()`, one or more
rounds before the crash.**

Also worth noting: in the shipped library there is exactly **one** call site of
`VehicleRect::release()` (inside `VisibilityGraph::release()`) and exactly **one** call
site of `VehicleRect::alloc()` (inside `PolygonalMap2::createVehicleClusters()`).

## Issue 1 — the object pool has no validation, so it launders corruption across rounds

`VehicleRect::alloc()` pops a `void*` and returns it; `VehicleRect::release()` pushes a
`void*` and returns. Neither performs any check. Consequently:

* a single corrupted array slot is stored verbatim in the free list;
* it is handed to a consumer one or more rounds later;
* the crash therefore happens in a function that is entirely innocent, at a point in time
  unrelated to the corruption, with no way to attribute it.

Suggested change: validate **pool ownership** on `release()` (an exact registry of this
pool's allocated objects, with object generation/liveness where needed) and log the
offending cluster / array / slot index. Do **not** use a low-address or magnitude heuristic:
that would hide only values such as `0x30`, let high-address corruption through, and still
lose the writer. Validating on `release()` is far more useful than validating on `alloc()`,
because `release()` still has the cluster and slot index in scope.

The same pattern exists for the other `ObjectPool<T>` instantiations in this library
(`VehicleCluster`, `VisibilityGraph`, node/edge pools), so a fix in the template covers all
of them.

## Issue 2 — `reallocate_aligned()` copies `malloc_usable_size(old)`, not `min(old, new)`

`reallocate_aligned(void*, unsigned long, unsigned long)` at offset `0x5af50` does:

```
p = aligned_alloc(alignment, newSize)
memcpy(p, old, malloc_usable_size(old))     <-- source length, never clamped to newSize
free(old)
```

We checked all **136** call sites in the shipped library: every one passes either a doubled
requested capacity or an initial requested capacity. That is useful context but **does not
prove** `newSize >= malloc_usable_size(old)`: glibc size-class rounding can make an older,
smaller request have a larger usable size than the next request. We therefore cannot exclude
this primitive as the writer from static call-site shape alone. **We are not claiming this
caused the crash.** We are reporting it because a future logical shrink — and potentially
some current size-class transitions — can turn it into a heap overflow of up to the old
usable size. `min(malloc_usable_size(old), newSize)` costs nothing.

## What we cannot tell you (and what we would need)

The **first** corrupting store is not identifiable from this core:

* it happened at least one full `createVehicleClusters()` round before the crash;
* the value written over the chunk header is a valid pointer to a live 96-byte heap object,
  so the write looks like a short-range out-of-bounds or dangling-pointer store rather than
  allocator misbehaviour;
* we cannot rule out a writer in another native module — the process shares one glibc heap
  between PathFind, PopMan/MapCollisionData, RakNet and the JVM. We are **not** claiming
  PathFind wrote it.

We have built a read-only observation shim that moves only this library's
`reallocate_aligned` / `deallocate_aligned` block family onto guarded mmap regions
(PROT_NONE guard pages both sides, quarantine on free) so that the next occurrence faults
on the writing instruction. If that produces a core with a definitive writer, we will send
it. If you have a faster way to get there (a debug build with ASan, or a build with
`ObjectPool` validation enabled), we would rather run that.

## Two unrelated minor issues found while investigating

1. **`start-server.sh` never manages to preload `libjsig.so`.** It sets
   `JSIG="libjsig.so"` (bare name) while the `LD_LIBRARY_PATH` it exports contains
   `jre64/lib/amd64`, a directory that does not exist in this JRE layout. The file is at
   `jre64/lib/libjsig.so`. Every startup logs
   `ERROR: ld.so: object 'libjsig.so' from LD_PRELOAD cannot be preloaded ... ignored.`
   so JVM signal chaining has never actually been active on Linux dedicated servers.
2. **`Pathfind.UseNativeCode` cannot be turned off on a dedicated server.**
   `GameServer.main` calls `IsoWorld.instance.init()` — which is where
   `PathfindNative.useNativeCode` is read from `DebugOptions` — *before* it calls
   `DebugOptions.instance.init()`, which is what loads `debug-options.ini`. The only
   runtime re-check, `PathfindNative.checkUseNativeCode()`, is called from `IngameState`
   (client only). So the setting is silently ignored server-side, which also removes the
   obvious workaround for this bug family.

## Attachments we can provide on request

* the recovered `ucontext_t`, pool census and cluster dump (a reproducible script, not the
  core itself — the core contains player data);
* the 136-call-site classification for `reallocate_aligned`;
* console excerpts for the seven crashes in this family (2026-08-22 … 2026-08-31).
