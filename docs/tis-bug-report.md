# TIS 官方回報草稿 — 貼圖管線 native 記憶體洩漏（隱形實體根因）

> 用途：貼到 The Indie Stone 官方論壇 Bug Reports 板（B42），或提交給官方支援信箱。
> 下方英文本文可直接複製貼上；附件建議附上玩家 console.txt（修前 console (12)、修後 console (17)）。
> 數據來源：42.20.0–42.20.2 反編譯核實＋正式服（30–40 人、100+ mods）受影響玩家遙測。
> 撰於 2026-08-11。發文前請把文末 placeholder（伺服器名/聯絡方式）補上。

---

**Title:** [42.20.2] [MP] Native DirectBuffer leak in the texture pipeline permanently starves texture loading — players/zombies/vehicles become invisible (models gone, shadows + nametags remain)

## Summary

On modded MP servers, `DirectBufferAllocator` allocated bytes ratchet upward and never return to baseline. Once the *floor* (not the peak) permanently exceeds the 50 MB threshold in `TextureIDAssetManager.waitFileTask()`, every texture-loading worker sleeps forever (silently — nothing is logged), the shared `FileSystemImpl` queue backs up behind them, and every model whose texture bake is incomplete is skipped entirely. Result: entities render as shadow blob + nametag only, permanently, until the game process is fully restarted (relogging does **not** fix it — see below).

We root-caused this from decompiled 42.20.2 bytecode, then verified the diagnosis in the field: an experimental client-side fix for the leak (details below) took an affected player's DirectBuffer floor from a monotonic 110 MB → 1,096 MB ratchet down to a flat **0 bytes** across whole sessions, and the invisibility never recurred.

## Environment

- Version: 42.20.2 (verified identical Java to 42.20.0 — all 23,735 classes byte-identical between the two jars)
- Dedicated Linux server, 30–40 concurrent players, ~100 workshop mods
- Affected clients include i9-13900K / RTX 4090 / 32 GB and Ryzen 9800X3D / 64 GB — hardware, drivers (Intel Arc / AMD / NVIDIA all reported in community threads), reinstalls and DDU were all ruled out
- Matches long-standing community reports, e.g. Steam discussion "[B42 Multiplayer] models missing" (60-player server, 2–3 players affected, worsens above ~20 players)

## Symptom

Zombies, other players and vehicles lose their 3D models but keep their blob shadow and nametag (those are independent pipelines — `FBORenderShadows` decals and the UI text batch — which is why exactly this fingerprint appears). New entities entering view are affected first. Relog helps at most temporarily; only fully restarting the game recovers. Nothing relevant appears in console.txt because the stall path logs nothing.

## Root cause (decompiled 42.20.2, class/method references)

1. **`zombie.core.textures.ImageData.dispose()` never frees `frames`.**
   It disposes `data` and `mipMaps`, but the `frames` list (per-frame, full-size WrappedBuffers for animated/APNG textures) is never touched. Every dispose/reload cycle of an animated texture deterministically leaks all of its frames — no exception, no log. This is the bulk of the ratchet: on our server both instrumented machines reached a ~110 MB resident floor within minutes of joining — already 2× the entire 50 MB budget.

2. **`ImageData.getData()` falls back to a fixed 64 MB allocation.**
   When `data == null` it allocates `67108864` bytes regardless of the texture's actual size.

3. **APNG + mipmap flag → ArrayIndexOutOfBoundsException that skips disposal.**
   For an animated texture loaded with the mipmap flag, `getMipMapCount()` can return 0; the generate-HW-id path then calls `getMipMapData(-1)`, which indexes `mipMaps[-2]` and throws AIOOBE from the middle of the method — skipping the buffer disposal at its tail. A single such event leaks the 64 MB fallback plus the mip chain plus all frames; we observed matching +64 MB / +99 MB single-step jumps in the floor.

4. **`TextureIDAssetManager.waitFileTask()` waits forever, silently.**
   `while (DirectBufferAllocator.getBytesAllocated() > 52428800L) Thread.sleep(20);` — no timeout, no logging, no recovery. Once the leaked floor exceeds 50 MB, all texture loads stop permanently. Because texture and mesh tasks share the `FileSystemImpl` pool (2–4 threads, bounded in-flight), mesh loading starves too.

5. **All-or-nothing bake gate turns a starved pipeline into full invisibility.**
   `ModelInstanceTextureCreator.render()` aborts unless every source texture `isReady()`, and `ModelSlotRenderData.canRender()` has no fallback — so a single missing texture means the entity draws nothing at all (rather than e.g. untextured or lower-res).

   Contributing context: the B41 model-count valve (`auto3DZombies`) is a dead field in B42, so nothing throttles model load when the pipeline degrades.

Why only some players, and why relog doesn't cure it: the leak lives in process-level statics (`DirectBufferAllocator.ALL` holds strong references to the non-disposed WrappedBuffers), so it survives relog and accumulates with **unique content seen**, not time. Players who traverse more new areas/mod content hit the threshold first; driving into Louisville added +550 MB to the floor within minutes on one instrumented client.

## Field measurements (client-side telemetry we added around `getBytesAllocated()`)

| Metric | Unpatched behaviour | After fixing the leak client-side |
|---|---|---|
| Floor minutes after joining | ~110 MB (both machines) | **0 bytes** |
| Floor growth, normal play | ~170 MB/hour, monotonic | none (drains to 0 every 60 s window) |
| Driving into Louisville | +550 MB in minutes | no residual growth |
| 8-hour session floor | 1,096 MB → pipeline permanently dead | flat 0 |
| Longest continuous starvation (bytes > 50 MB) | 854 s in one session; 2,404 would-stall samples | transient peaks (hwm 92 MB) drain immediately; 0 stalls |
| Invisible entities | recurring, only full restart recovers | none since deployment |

Note the 92 MB *transient* peak with the leak fixed: even a leak-free modded client legitimately exceeds the 50 MB constant during load bursts (our telemetry counted 111 would-stall samples in a half-hour tail), so the threshold itself is also worth revisiting.

## Suggested fixes

1. In `ImageData.dispose()`, also dispose each entry of `frames` (and clear the list). This is the single highest-impact change.
2. In `ImageData.getData()`, size the fallback from the texture's actual dimensions (or fail fast) instead of a fixed 64 MB.
3. Wrap the generate-HW-id path so partially-allocated buffers are disposed on exception, and guard `getMipMapCount() == 0` before deriving mip indices.
4. In `waitFileTask()`: log when a worker enters the wait (today it is fully silent — this bug was undiagnosable from logs), add an escape/timeout, and reconsider the 50 MB constant for modern modded MP.

We validated 1–3 as an experimental client-side patch on the affected players of our server (with idempotent-dispose guards, since `WrappedBuffer.dispose()` throws on double-dispose); telemetry logs, before/after console.txt files, and implementation details are available on request.

— [server name], [contact]

---

## 附：中文對照重點（發文不用貼這段）

- 核心主張：`ImageData.dispose()` 漏掉 `frames`（APNG 每幀滿尺寸 buffer）→ DirectBuffer 地板單調上升 → 超過 `waitFileTask` 的 50MB 門檻後所有貼圖載入執行緒無限睡（零 log）→ 全有全無烘焙閘門讓整隻模型不畫 → 影子＋名牌獨立管線照畫＝隱形指紋。
- 洩漏在 process 級 static（`DirectBufferAllocator.ALL` 強引用），relog 清不掉、與「看過的新內容量」成正比（路易斯 +550MB/數分鐘）。
- 實證：client 端修掉洩漏後，地板 110MB→0、8 小時 1096MB→0、隱形未再發生（console (17)）。
- 附帶論點：就算沒洩漏，重模組服瞬時峰值 92MB 也超過 50MB 常數（半小時 111 個 would-stall 樣本），門檻本身也該檢討。
