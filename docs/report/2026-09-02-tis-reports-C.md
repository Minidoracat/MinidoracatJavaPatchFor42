# TIS 回報草稿 C（client／native／設計面）— 2026-09-02

> 由 Main 撰寫：C1 client 貼圖管線洩漏（既有 `docs/tis-bug-report.md` 的 42.20.4 論壇格式版）、
> C2 PathFind native 堆損毀（既有 `docs/report/2026-08-31-pathfind-vehiclerect-pool-poisoning-tis.md`
> 的論壇格式包裝）、C3 全存檔同步凍結（設計面 feedback，非 bug）。

## C1. Client 貼圖管線 DirectBuffer 洩漏 → 實體隱形

### 中文摘要
- 報什麼：`ImageData.dispose()` 不釋放 `frames`（APNG 每幀滿尺寸 buffer）→ DirectBuffer 地板單調上升 → 超過
  `TextureIDAssetManager.waitFileTask()` 的 50 MB 硬門檻後所有貼圖載入執行緒無限 sleep（零 log）→
  全有全無 bake 閘門讓整隻模型不畫＝「只剩影子＋名牌」。42.20.4 四個相關 class 與 42.20.2 逐位元組相同（缺陷仍在）。
- 優先級：高（長期社群回報「invisible zombies/players」的根因；relog 無效、只能重開遊戲）。
- 附件：受影響玩家修前／修後 console.txt（console (12)／(17)）、遙測表；不要附 patch 本體。
- 板別：Bug Reports（B42）；標 client-side。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags（貼進 Tags 欄；逗號分隔，或一個一個打字後按 Enter）：
42.20.4, multiplayer, client, textures, invisible

### Title
[42.20.4] [MP client] Native DirectBuffer leak in the texture pipeline silently starves texture loading — zombies/players/vehicles render as shadow + nametag only until the game is restarted

### Body
```text
Version: [42.20.4]
Mode: [Multiplayer (client-side defect; observed on a dedicated server)]
Server settings: [Dedicated, Linux x86_64, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01)]
Mods: [Clients and server run ~80 workshop mods. The leak is in vanilla zombie.core.textures.ImageData / TextureIDAssetManager (class/method references below); mods only add more textures and therefore reach the threshold sooner. We could not build a mod-free repro because the trigger is "enough unique texture content seen in one session", which a vanilla-only client on a vanilla server reaches much more slowly.]
Save: [Any; not save-related]

Summary
On modded MP clients, DirectBufferAllocator.getBytesAllocated() ratchets upward and never returns to baseline. Once the *floor* (not the peak) permanently exceeds the 50 MB constant in TextureIDAssetManager.waitFileTask(), every texture-loading worker sleeps forever — silently, nothing is logged — the shared FileSystemImpl queue backs up behind them, and every model whose texture bake is incomplete is skipped entirely. Result: zombies, other players and vehicles render as blob shadow + nametag only (those two are independent pipelines, which is why exactly this fingerprint appears). Relogging does not recover; only a full game restart does. This matches long-standing community reports such as "[B42 Multiplayer] models missing".

We root-caused it from decompiled bytecode and then confirmed it in the field with an experimental client-side fix (details below). The four relevant classes (ImageData, TextureID, TextureIDAssetManager, WorldStreamer) are instruction-for-instruction identical between 42.20.2, 42.20.3 and 42.20.4, so none of the 42.20.3 "memory leak" fixes touched this pipeline.

Trigger conditions (not a fixed step list)
1. Join a busy modded server and play normally for 1–8 hours, moving through new areas (Louisville is the fastest trigger).
2. Entities entering view start rendering without a model (shadow + nametag remain). New entities are affected first.
3. Relog: symptom persists or returns within minutes. Restart the game: symptom clears.

Root cause (decompiled 42.20.4)
1. zombie.core.textures.ImageData.dispose() disposes `data` and `mipMaps` but never touches the `frames` list (per-frame full-size WrappedBuffers for animated/APNG textures). Every dispose/reload cycle of an animated texture leaks all of its frames. Because DirectBufferAllocator.ALL holds strong references to the non-disposed WrappedBuffers, the leak is process-level and survives relog.
2. ImageData.getData() falls back to a fixed 67,108,864-byte allocation when `data == null`, regardless of the texture's real size.
3. For an animated texture loaded with the mipmap flag, getMipMapCount() can return 0; the generate-HW-id path then calls getMipMapData(-1) → indexes mipMaps[-2] → ArrayIndexOutOfBoundsException thrown from the middle of the method, skipping the buffer disposal at its tail. One such event leaks the 64 MB fallback + the mip chain + all frames (we observed matching +64 MB / +99 MB single-step floor jumps).
4. TextureIDAssetManager.waitFileTask(): `while (DirectBufferAllocator.getBytesAllocated() > 52428800L) Thread.sleep(20);` — no timeout, no log, no recovery. Texture and mesh tasks share the FileSystemImpl pool (2–4 threads, bounded in-flight), so mesh loading starves too.
5. ModelInstanceTextureCreator.render() aborts unless every source texture isReady(), and ModelSlotRenderData.canRender() has no fallback, so one missing texture means the entity draws nothing (rather than untextured / lower-res).

Field data (client-side telemetry around getBytesAllocated(), two instrumented machines: i9-13900K/RTX 4090/32 GB and Ryzen 9800X3D/64 GB)
- Unpatched: floor ≈110 MB within minutes of joining (already 2× the whole budget), growing ≈170 MB/h monotonically; driving into Louisville added +550 MB in minutes; an 8-hour session ended at 1,096 MB with the pipeline permanently dead (longest continuous starvation 854 s; 2,404 would-stall samples).
- With the leak fixed client-side (dispose frames, size the fallback, guard the AIOOBE path): floor drains to 0 bytes every 60 s window, 8-hour floor flat at 0, invisibility never recurred on either machine. On 42.20.3/42.20.4 the same player saw a legitimate *transient* load-burst high-water mark of ≈1.12 GiB — over 20× the 50 MB constant — which drained normally, i.e. even a leak-free modded client legitimately exceeds the constant during loading.

Suggested fixes
1. ImageData.dispose(): also dispose each entry of `frames` and clear the list (highest impact, one-line class of change).
2. ImageData.getData(): size the fallback from the texture's actual dimensions, or fail fast, instead of a fixed 64 MB.
3. Wrap the generate-HW-id path so partially allocated buffers are disposed on exception; guard getMipMapCount()==0 before deriving mip indices.
4. waitFileTask(): log when a worker enters the wait (today it is fully silent, which is why this was undiagnosable from logs), add an escape/timeout, and reconsider the 50 MB constant for modern modded MP.

We validated 1–3 as an experimental client-side patch on affected players of our server (with idempotent-dispose guards, since WrappedBuffer.dispose() throws on double-dispose). Before/after console.txt files, telemetry logs and the bytecode diff are available on request.
```

---

## C2. PathFind native 堆損毀經 `VehicleRect` 物件池洗白 → 延遲 SIGSEGV

### 中文摘要
- 報什麼：42.20.4 `libPZPathFind64.so` 的 `PolygonalMap2::createVehicleClusters()` SIGSEGV，core 證明 `0x30` 經
  `VisibilityGraph::release()` 進入 `VehicleRect` 池、一輪後才炸；兩個設計問題（池無驗證、`reallocate_aligned`
  拷貝 `malloc_usable_size(old)` 不夾 newSize）。首次寫入者未定罪，誠實揭露。
- 優先級：高（8/22–8/31 七次 glibc 堆損毀簽名崩潰；hs_err 被 glibc abort 截斷＝唯一證據是 core）。
- 附件：recovered ucontext／pool census 腳本輸出（不附 core，含玩家資料）、七次 console 摘錄、hs_err（截斷）。
- 板別：Bug Reports（B42）；Steam 指南說 crash 走 Support，但本文是根因分析，建議 Bug Reports 並在首行註明 crash。
- 內文＝既有草稿 `docs/report/2026-08-31-pathfind-vehiclerect-pool-poisoning-tis.md` 全文，前面加下列欄位頭。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags（貼進 Tags 欄；逗號分隔，或一個一個打字後按 Enter）：
42.20.4, multiplayer, dedicated, crash, pathfinding

### Title
[42.20.4] [MP dedicated] Native SIGSEGV in PolygonalMap2::createVehicleClusters() — VehicleRect object pool hands out a corrupted slot (0x30) that entered the pool through VisibilityGraph::release() one round earlier

### Body（欄位頭；其後貼草稿的 Summary 起全文）
```text
Version: [42.20.4] (build id 24909836)
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, glibc 2.39, kernel 6.17.4), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods, none of which ship native code. The corrupted objects are native-only structures inside libPZPathFind64.so (unmodified Steam depot file, sha256 0777dda6…). Full disclosure of our own Java-side patches is in the report body; none of them touch zombie.pathfind or any native structure.]
Save: [Existing MP save; not save-related]

Crash: yes (SIGSEGV in native code, 7 occurrences 2026-08-22 … 2026-08-31). hs_err is truncated at the "Host:" line because glibc aborted inside the JVM crash handler — attached anyway; the core dump is the only usable artifact and is available privately (it contains player data).

<paste "## Summary" … "## Attachments we can provide on request" from docs/report/2026-08-31-pathfind-vehiclerect-pool-poisoning-tis.md here, headings demoted one level>
```

---

## C3. 全存檔（QueuedSaveAll）在主執行緒同步凍結 5–7 秒（設計面 feedback）

### 中文摘要
- 報什麼：不是 bug，是設計成本——`ServerMap.SaveAll()` 主執行緒 `sleep(10)` 輪詢 4 條 worker 序列化所有 loaded cells，
  80 人時每次 5–7 秒全服凍結（我方看門狗 4 天 16 次快照全同族）；`checkClientPause` 有送 Pause 封包所以不踢線，但玩家體感每小時一次 rubber-band。
- 優先級：低（feedback）；可與 C1/C2 分開、獨立發文或放在 PZ Suggestions。
- 板別：PZ Suggestions／General Discussion（不是 Bug Reports）。

### 建議板塊

**PZ Suggestions** — https://theindiestone.com/forums/forum/20-pz-suggestions/（發新主題：https://theindiestone.com/forums/forum/20-pz-suggestions/?do=add）
建議 tags（貼進 Tags 欄；逗號分隔，或一個一個打字後按 Enter）：
42.20.4, multiplayer, dedicated, performance, save

### Title
[42.20.4] [MP dedicated] Suggestion: full world save (QueuedSaveAll) blocks the main loop for 5–7 s on a busy server — consider an incremental / off-thread cell save

### Body
```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux, 7-day average ≈30 concurrent players, evening peaks 60–95, SaveWorldEveryMinutes=60]
Mods: [~80 workshop mods; not mod-related — the path below is vanilla ServerMap]
Save: [Existing MP save]

This is a performance/design note rather than a bug.

Observed
We run a main-thread stall watchdog on our dedicated server. Over four days (2026-08-30 … 09-02) every non-shutdown stall of ≥5 s it captured — 16 of them, 5–7 s each — had the same main-thread stack:

  ServerMap.SaveAll  (waiting: Thread.sleep(10) loop until 4 WorkerThreads finish SaveCell for every loaded cell)
  → ServerMap.QueuedSaveAll
  → ServerMap.preupdate
  → GameServer.main

with the server's own log confirming it ("SaveAll took 4474–5043 ms", "Saving took 5208–6038 ms"). Duration scales with player count (2–3.6 s at 20–40 players, 5–7 s at 60–80). WorldMapVisitedServer.save() (per-user zip deflate) adds <1 s on top.

Because QueuedSaveAll sends Pause to clients when the save is slow, nobody gets kicked — but every player experiences a 5–7 s freeze followed by a rubber-band, once per SaveWorldEveryMinutes and again on every "save" command / restart countdown.

Why we think it is worth a look
The world is frozen for the whole duration because chunk serialization needs a quiescent world. Two possible directions, either of which would remove most of the wall-clock cost from the main thread:
1. Incremental cell saves spread over many ticks (only cells dirty since the last save), so no single tick pays for the whole world.
2. Snapshot-then-serialize: copy the dirty chunk buffers on the main thread (cheap) and let the existing SaveChunkThread do the serialization/CRC/IO off-thread — the loaded-chunk save path already goes through that thread, so the machinery is there.

Happy to provide the watchdog stack captures and the per-save timing series.
```
