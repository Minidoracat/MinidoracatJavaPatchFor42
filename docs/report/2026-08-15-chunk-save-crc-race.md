# TIS 官方回報 — 存檔管線共用 CRC32 競態導致 chunk 損毀（Blam 資料抹除根因）

> 用途：貼到 The Indie Stone 官方論壇 Bug Reports 板（B42），或提交官方支援信箱。
> 下方英文本文可直接複製貼上；建議附件：`blam/` 目錄的代表性樣本（`.bin`＋`_error.txt`
> 各一組，A 組 crc=0 與 B 組垃圾值各一）與我們攔截器的 BLOCKED log 節錄。
> 數據來源：42.20.2 反編譯（bytecode 逐指令核實）＋正式服（50–100 人）兩週 43 筆
> 損毀屍檢＋寫入攔截器 8 筆現行犯＋修復後 292 萬次寫入零損毀的對照驗證。
> 撰於 2026-08-15。發文前補上文末 placeholder（伺服器名/聯絡方式）。
> 姊妹報告：`docs/tis-bug-report.md`（貼圖管線 native 洩漏，獨立問題）。

---

**Title:** [42.20.2] [MP Server] Shared CRC32 instances in the chunk-save pipeline race across threads — corrupted headers trigger "SANITY CHECK FAIL! CRC mismatch" and Blam wipes player-built chunks on next load

## Summary

On a busy dedicated MP server, chunk files are periodically written to disk with a
**valid body and length field but a wrong (or zero) CRC field in the 17-byte header**.
On the next load of such a chunk, `IsoChunk$SanityCheck.checkCRC` correctly rejects the
file, and the engine responds with `Blam()` + `LoadBrandNew()` — permanently wiping all
player-built content in that chunk (the original is backed up under `blam/`, but the
live world regenerates fresh).

We root-caused this from decompiled 42.20.2 bytecode: the header CRC is computed with a
**caller-supplied `CRC32` object that is a shared, unsynchronized instance**, and the
serialization entry point can run on more than one thread at the same time. A concurrent
`reset()` or `update()` on the shared instance yields a header CRC of **0** or an
interleaved garbage value, while the body and the length field (written by the
serializing thread itself) remain correct — exactly matching all 51 corrupted files we
examined (43 historical + 8 caught live by an instrumentation gate we injected).

Replacing the two shared `CRC32` instances with per-thread ones eliminated the
corruption completely on our production server: **2.92 million chunk writes, five
hourly world-saves and one shutdown save with zero corrupt headers**, against a
pre-fix baseline of ~9–18 expected events for that volume (Poisson p < 2×10⁻⁴).

## Environment

- Version: 42.20.2, dedicated Linux server (jar SHA-256 prefix `09a80a46`)
- 50–100 concurrent players, `SaveWorldEveryMinutes=60`
- 43 corrupted chunks over ~2 weeks; corruption clusters at **restart windows**
  (historical average 0.8 per restart) and during **hourly full world-saves**

## Symptom

Next load of an affected chunk:

```
java.lang.RuntimeException: SANITY CHECK FAIL! thread="LoadChunk"
CRC mismatch save=0 load=3748456437
save chunk=null
load wx,wy=1009,1428 thread="LoadChunk"
	at zombie.iso.IsoChunk$SanityCheck.log(IsoChunk.java:4583)
	at zombie.iso.IsoChunk$SanityCheck.checkCRC(IsoChunk.java:4531)
	at zombie.iso.IsoChunk.LoadFromDiskOrBufferInternal(IsoChunk.java:3649)
	...
	at zombie.network.ServerChunkLoader$LoaderThread.run(ServerChunkLoader.java:70)
```

The chunk is then blammed and regenerated: in this real case a 28,401-byte chunk
holding a player base (storage crates) was replaced by a 5,470-byte fresh chunk.

Two corruption signatures, across all 51 files:

- **Signature A (19 of 51):** header CRC field `== 0`; length field correct; body fully
  self-consistent (recomputing CRC32 over `[17, len)` matches the `load=` value the
  game itself reports). These files are byte-perfect except the 8-byte CRC field —
  we restored 16 of them by rewriting the header CRC alone.
- **Signature B (32 of 51):** header CRC field holds a non-zero value that matches
  neither the body nor anything else; length field still correct.

The **length field is correct in 51/51 cases** — this constraint is what ultimately
identified the mechanism (any buffer-tearing / pool-aliasing hypothesis fails it).

## Root cause (decompiled 42.20.2, class/method references)

1. **`IsoChunk.Save(ByteBuffer, CRC32, boolean)` stamps the header with a
   caller-supplied CRC32.** At the tail of the method (after the body is fully
   serialized): `crc.reset(); crc.update(array, 17, len-17); position(5);
   putInt(len); putLong(crc.getValue()); position(len)`.

2. **The MP save pipeline passes a shared instance.**
   `ServerChunkLoader$SaveChunkThread.addLoadedJob(IsoChunk)` serializes **on the
   calling thread** via `IsoChunk.SaveLoadedChunk(chunk, this.crc32)` — where
   `this.crc32` is a single `CRC32` field of the (singleton) `SaveChunkThread`.

3. **`addLoadedJob` demonstrably runs on more than one thread.** Callers are
   `ServerMap$ServerCell.Save(boolean)` and `ServerCell.update() → saveChunk()`.
   These execute on the main loop during normal play, **and** on the shutdown path:
   the `GameServer$1` thread runs `ServerMap.QueuedQuit → QueuedSaveAll`, which walks
   every loaded cell through the same `addLoadedJob` while the main loop may still be
   saving. This matches the observed clustering of corruption at restart windows.

4. **The race produces exactly the two observed signatures.** Thread A finishes
   `update()` and calls `getValue()`; if thread B's `reset()` lands in between,
   A reads **0** → Signature A. If A's and B's `update()` calls interleave, A reads a
   mixed garbage value → Signature B. The body and the length field are written by
   thread A alone from its own buffer, hence always correct. The race window is the
   tail of `Save()` (microseconds per save) — consistent with the low absolute rate
   (~1/day) and its concentration in high-throughput save bursts.

5. **A second shared-CRC32 race sits one step downstream.**
   `ServerChunkLoader$SaveLoadedTask.save()` computes a dedup checksum over the whole
   buffer with the **outer `ServerChunkLoader.crcSave`** shared instance, then stores
   it via `ChunkChecksum.setChecksum`. `save()` normally runs on `SaveChunkThread`,
   but `SaveChunkThread.saveNow(int,int)` — called from
   `ServerChunkLoader$LoaderThread.run()` (flush-before-load) — executes queued tasks
   **on the LoaderThread**, concurrently with `run()` executing other tasks. The
   interleaved dedup CRC pollutes `ChunkChecksum` (wrong values compared /
   stored), which can (a) skip a needed disk write ("unchanged" false positive →
   stale chunk on disk) and (b) desynchronize the server↔client chunk checksum
   protocol, causing spurious re-sends.

## Additional defects found in the same pipeline (bytecode-verified)

- **Unsynchronized release path enables double-free into the global pools.**
  `SaveChunkThread.update()` drains `fromThread` into the plain `ArrayList
  savedChunks` and calls `release()` on each. `updateSaved()` is reachable from the
  main loop (`ServerMap.postupdate`, `SaveAll`) and from the shutdown path
  concurrently; concurrent `drainTo`/iteration over the same unsynchronized list can
  release the same task twice. `SaveLoadedTask.release()` returns the
  `ClientChunkRequest.Chunk` (and its `ByteBuffer`) to the **static, JVM-global**
  pools `ClientChunkRequest.freeChunks` / `freeBuffers` — a double-release puts the
  same object in the pool twice, after which two independent consumers (the save
  pipeline and the per-connection `PlayerDownloadServer` send workers share these
  pools) can own the same buffer simultaneously.
- **Task leak on save failure.** `SaveChunkThread.run()`'s `catch (Exception)` logs
  but does not put the failed task into `fromThread` — its Chunk + 16KB+ ByteBuffer
  are never released (contrast: `saveNow()` does release on failure).
- **`saveNow()` reorders the queue.** It drains `toThread`, processes matches, and
  re-appends the remainder at the tail — two queued saves of the same chunk can
  invert order (older data written after newer).
- Minor: `ClientChunkRequest.freeBuffers` is `public static`;
  `unpack`/`unpackLargeArea`/`releaseBuffers`/`sendLargeArea`(`RequestLargeArea`
  command) appear to be dead code in this build — `sendLargeArea` would clear the
  global buffer pool if it were ever reachable.

## Live instrumentation (how we caught it red-handed)

We injected a verify-before-write gate at the `IsoChunk.SafeWrite` call sites
(header length-field vs actual length, header CRC vs recomputed body CRC — both are
invariants that `Save()` guarantees single-threaded). In 2.5 hours it blocked **8
corrupt writes, all with identical stacks**:

```
[MinidoracatJavaPatch][ChunkWriteGuard] BLOCKED CRC_MISMATCH chunk=739,650
  len=2837 lenField=2837 crcField=463326233 ... thread=SaveChunk
    zombie.network.ServerChunkLoader$SaveLoadedTask.save(ServerChunkLoader.java:203)
    zombie.network.ServerChunkLoader$SaveChunkThread.run(ServerChunkLoader.java:302)
```

3 of the 8 had `crcField=0` (Signature A), 5 had garbage (Signature B); all 8 had a
correct length field. Blocking the write (keeping the previous good file on disk)
reduced data loss to zero while we root-caused.

## Fix validation

We replaced the two shared `CRC32` instances with per-thread instances (and, as
defense in depth, gave the save pipeline private Chunk/ByteBuffer ownership instead
of the shared static pools). Result on the same production server, same player load:

| Session | Duration | Chunk writes | Corrupt writes |
|---|---|---|---|
| Pre-fix (gate only) | 2.5 h | ~1.3 M | **8** |
| Pre-fix (gate only) | 1.7 h | ~0.7 M | **1** |
| **Post-fix** | **5.7 h** | **2.92 M** | **0** |

The post-fix session included five hourly full world-saves and one shutdown save —
the two scenarios where corruption historically concentrated. Expected events at the
pre-fix rate: ~9–18; observed: 0 (Poisson p < 2×10⁻⁴).

## Suggested fixes (minimal)

1. In `SaveChunkThread.addLoadedJob` and `SaveLoadedTask.save`, use a **local (or
   per-thread) `CRC32`** instead of the shared fields. A `new CRC32()` per save is
   negligible next to the disk write it accompanies. This alone removes the data-loss
   mechanism.
2. Make the `updateSaved()` release path thread-confined or synchronized (the
   unsynchronized `savedChunks` ArrayList), or make pool release idempotent —
   otherwise double-released Chunks let two owners share one ByteBuffer.
3. In `SaveChunkThread.run()`'s `catch (Exception)`, still hand the task to
   `fromThread` so its pooled buffer is released.
4. Consider separating the save pipeline's buffer pool from the client-send pool
   (`ClientChunkRequest.freeChunks`/`freeBuffers` are global statics shared by both).

## Reproduction notes

Deterministic repro is impractical (microsecond window), but the statistical repro is
reliable: busy MP server (≥50 players), `SaveWorldEveryMinutes=60`, restart while
players are online; instrument `SafeWrite` callers to verify header-CRC vs body-CRC
before writing. We observed ~1 corrupt write per few hundred thousand saves,
concentrated in full-save bursts and shutdown saves.

## Recovery note for affected servers

Signature-A blams (header CRC field zeroed, length correct) are fully recoverable:
recompute CRC32 over bytes `[17, len)` of the `blam/<wx>/<wy>.bin` backup, write it
big-endian into bytes `[9, 17)`, and restore the file into `map/` while the server
process is stopped. We recovered 16/16 such chunks (including two player bases)
this way.

---

Server: ＜伺服器名稱 placeholder＞
Contact: ＜聯絡方式 placeholder＞
