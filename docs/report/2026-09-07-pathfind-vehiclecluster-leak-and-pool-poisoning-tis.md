# TIS 官方回報草稿 — `libPZPathFind64.so`：`VehicleCluster::merge` 洩漏（R1）／`VehicleRect` 池被 heap 覆寫毒化 → 伺服器 native crash 家族（R2）

**狀態**：**草稿（2026-09-07，未送）**。兩篇獨立：R1 是行為確定、可一行修的洩漏；R2 是「writer 未定案」的 heap corruption 家族，只提供實測證據鏈與兩份同簽名 core 的比對，不宣稱根因。
回報前核對（2026-09-07）`docs/report/` 全部既有草稿：`VehicleCluster`／`VehicleRect`／`createVehicleClusters`／`PolygonalMap2` 零命中，與 A／B／C／D 組不重疊。

證據來源：正式服（Linux x86_64 dedicated，42.20.4）hs_err ×2＋完整 core ×2（8/31、9/7）＋LinuxGSM console；對 exact `libPZPathFind64.so`（sha256 `0777dda6db77ddd3059f27f94e0d56fae827b21436b5feb4d719e96878fd21c4`）的 `objdump`／`readelf`；core 解析用 `native-observer/analysis/*.py`（stdlib ELF core reader）。**對外只引用函式名、`.so` offset 與觀測值，不貼反編譯原文。**

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add
R1 tags：multiplayer, server, memory leak, vehicles, pathfinding
R2 tags：multiplayer, server, crash, vehicles, pathfinding

### 修正前必做

- 本文不含玩家名、主機名、IP、內部路徑；hs_err 節錄只保留 signal／frame／registers／Java frames（`Command Line` 與 `Host:` 行不貼）。
- 附件（索取時再給）：`hs_err_pid567642.log`（11 KB，去識別後）、兩份 core 的解析 JSON、`objdump` 節錄。**core 本身不外流**（含玩家資料）。
- R1 的「修好後」數字（`merge_released`／池 `total_alloc` 斜率）等 round-3 重啟後補上再送；沒有也可送，證據已足。

---

## R1. `VehicleCluster::merge` 從不釋放被併入的 cluster → `ObjectPool<VehicleCluster>` 無上限洩漏（每秒 ~40 個）

## 中文摘要

`PolygonalMap2::createVehicleClusters()` 每次重建車輛 cluster（車輛移動即觸發）時，`createVehicleCluster()` 遇到「一個 rect 同時鄰接兩個既有 cluster」就把第二個 cluster 從 `PolygonalMap2` 的清單 `memmove` 掉、呼叫 `VehicleCluster::merge(dst, src)` 把 src 的 rect 全搬到 dst、src 的 `count` 歸零——然後 **src 就沒人管了**：全 `.so` 唯一的 `VehicleCluster::release@plt` 呼叫點在 `VisibilityGraph::release()`，它只走訪**還在清單裡**的 cluster。被併入的 cluster（0x18 bytes 物件＋它用 `reallocate_aligned` 配置的 rect array）永遠回不了池。正式服實測 ~38–41 個/秒（兩份 core：58 分鐘 131,959 次 alloc／池內 free 427；4h04m 602,901／183），每 6 小時約 86 萬個、以最小 array 換算 ≈70 MB 起跳，RSS 線性成長、只有重啟能回收。修法一行：merge 後 `release(src)`。

證據等級：`objdump`／`readelf` 對 exact `.so`（呼叫點 census：merge 1、release 1、alloc 2）＋兩份 core 的池計數器直讀＋LD_PRELOAD 攔截 merge 補 release 後池計數穩定（本地合成測試；正式服數字待重啟後補）。

### 核實紀錄（2026-09-07）

| 項 | 方法 | 結果 |
|---|---|---|
| 版本 | `sha256sum linux64/libPZPathFind64.so` | `0777dda6…21c4`，42.20.4（client 與 dedicated 同檔） |
| merge 的呼叫點 | `objdump -d` 全檔 grep `VehicleCluster::merge@plt` | **恰 1 處**：`createVehicleCluster` 內 `0x4c5ab` |
| release 的呼叫點 | 同上 grep `VehicleCluster::release@plt` | **恰 1 處**：`VisibilityGraph::release` 內 `0x6697b`（同函式 `0x6696c` 逐 rect `VehicleRect::release`） |
| alloc 的呼叫點 | 同上 grep `VehicleCluster::alloc@plt` | 2 處，皆在 `createVehicleCluster`（`0x4c638`／`0x4c6b0`），緊接 `init()` |
| merge 對 src 做了什麼 | `objdump --disassemble='VehicleCluster::merge(VehicleCluster*)'` | 逐 rect 改 backpointer→dst、dst 滿了 `reallocate_aligned(…, 8)`（`0x5ccdb`）倍增、結尾 `movl $0x0,0xc(%r15)`（`0x5ccf8`＝`src->count=0`）後 `ret`；**沒有 release、沒有 free** |
| caller 在 merge 前後 | `createVehicleCluster` 反組譯 | merge 前把 src 從 `ArrayList<VehicleCluster*>` 清單 `memmove` 移除並 `count--`；merge 後不再引用 src |
| 洩漏率 | 兩份 core 直讀 `ObjectPool<VehicleCluster>` deque（`.so` `0x84ae0`） | 8/31：`total_alloc` 131,959／free 427／uptime 58 min ⇒ 37.9/s；9/7：602,901／183／4h04m ⇒ 41.1/s |
| 修補驗證 | LD_PRELOAD 同名接管 merge，真 merge 後對 `count==0` 的 src 呼叫真 `release` | 本地合成測試：`merge_released == merge_calls`、池大小＝釋放數－再取用數（20k 步影子模型逐步對帳）；正式服部署待重啟 |

### Title

`[42.20.4] libPZPathFind64.so: VehicleCluster::merge() leaks the absorbed cluster (never released to the pool) — ~40/s on a busy MP server, RSS grows until restart`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer — dedicated server; the same native library ships with the client, so SP/host are affected in proportion to vehicle activity]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04, glibc 2.39), LinuxGSM, bundled Zulu 25.0.1 JRE + ZGC, 254 slots; ~30 concurrent average, 60–95 evening peaks; several hundred vehicles in loaded areas]
Mods: [~80 workshop mods. The defect is entirely inside vanilla libPZPathFind64.so (sha256 0777dda6db77ddd3059f27f94e0d56fae827b21436b5feb4d719e96878fd21c4); no mod touches the native pathfinder.]
Save: [Any; not save-specific]

Summary
-------
When PolygonalMap2::createVehicleClusters() rebuilds the vehicle clusters
(every time a vehicle moves), createVehicleCluster() may find a rect whose
neighbours already belong to two different clusters. It then removes the
second cluster from the PolygonalMap2 cluster list (memmove + count--) and
calls VehicleCluster::merge(dst, src). merge() moves every rect of src into
dst (rewriting the rect backpointers), grows dst through
reallocate_aligned(), sets src->count = 0 and returns.

Nothing ever calls VehicleCluster::release(src). The only call site of
VehicleCluster::release() in the whole library is VisibilityGraph::release(),
and it only iterates the clusters that are still in the list. The absorbed
cluster object (0x18 bytes) and the rect array it owns (allocated with
reallocate_aligned, 32 bytes and up) are therefore leaked from
ObjectPool<VehicleCluster> on every merge.

Measured on our server: 38–41 leaked clusters per second (numbers below),
i.e. ~0.86 million per 6 hours; at 32 bytes for the object chunk plus 48
bytes for the smallest rect-array chunk that is ~70 MB of native heap per 6
hours (more with larger arrays), growing until the process is restarted.

Evidence (objdump/readelf against the shipped 42.20.4 library)
---------------------------------------------------------------
1. Call-site census, whole library (all PLT, no direct calls):

     VehicleCluster::merge@plt     1 site   0x4c5ab  in PolygonalMap2::createVehicleCluster(VehicleRect*, ArrayList<VehicleRect*>&, ArrayList<VehicleCluster*>&)
     VehicleCluster::release@plt   1 site   0x6697b  in VisibilityGraph::release()
     VehicleCluster::alloc@plt     2 sites  0x4c638, 0x4c6b0  in createVehicleCluster (each followed by init())

2. VehicleCluster::merge (0x5cc40, 221 bytes): per-rect backpointer rewrite,
   dst growth via reallocate_aligned(ptr, bytes, 8) at 0x5ccdb, then

     5ccf8:  movl   $0x0,0xc(%r15)     ; src->count = 0
     5cd0e:  ret

   No release, no free, no re-insertion of src anywhere.

3. createVehicleCluster: before the call at 0x4c5ab the src cluster is
   memmove'd out of the ArrayList<VehicleCluster*> passed as the third
   argument and the count is decremented; after the call src is not
   referenced again. So after merge() returns, src is reachable from nothing
   — not the cluster list, not any rect (merge rewrote them all), not a
   VisibilityGraph (graphs are built after all clusters exist).

4. Leak rate, read directly from ObjectPool<VehicleCluster>'s std::deque
   globals (library offset 0x84ae0) in two full core dumps of the server:

     core A (2026-08-31): total_alloc = 131,959, objects in the free pool = 427,   uptime 58 min  ->  37.9 clusters/s
     core B (2026-09-07): total_alloc = 602,901, objects in the free pool = 183,   uptime 4h04m   ->  41.1 clusters/s

   The pool never shrinks (release() is a bare deque push), so total_alloc
   minus (free + currently listed clusters) is the leaked count. Listed
   clusters are in the hundreds; the leaked count is in the hundreds of
   thousands.

5. Confirmation: we interposed VehicleCluster::merge with an LD_PRELOAD
   wrapper that calls the real merge and then the real
   VehicleCluster::release(src) when src->count == 0 (guarding against a
   double release with an address set). With that in place the pool is
   stable: every merge is matched by one release, allocs are served from the
   pool again. We only use this as a diagnostic on our own server; it is not
   a fix we can ship to players.

Reproduction
------------
1. Any MP server with vehicles being driven (cluster rebuilds are triggered
   by vehicle movement; merges happen whenever parked/moving vehicles are
   adjacent enough for one rect to touch two clusters — parking lots, car
   trains, towing).
2. Count the two PLT entries, e.g. with uprobes:

     perf probe -x linux64/libPZPathFind64.so '_ZN14VehicleCluster5allocEv'
     perf probe -x linux64/libPZPathFind64.so '_ZN14VehicleCluster7releaseEv'
     perf stat -e 'probe_libPZPathFind64:*' -p <pid> -- sleep 60

   alloc grows continuously; release only ever equals the number of clusters
   still listed at each rebuild. The difference is the leak.
3. Alternatively watch RSS of the server over a few hours with constant
   vehicle traffic: it climbs linearly and never comes back.

Impact
------
* Unbounded native memory growth on long-running servers (our 6-hourly
  restarts hide it; a server restarting weekly leaks in the GB range).
* Every leaked cluster keeps its rect array, so the pool of "old" rect arrays
  in the heap grows as well; this made our heap-corruption investigation
  (separate report) much harder because the victim allocations are drowned in
  leaked ones.

Suggested fix (one line)
------------------------
In PolygonalMap2::createVehicleCluster, right after VehicleCluster::merge(dst,
src) returns (or at the end of merge itself), return src to the pool:

     VehicleCluster::release(src);   // src->count is already 0; its array/capacity are reused by init()

That is exactly what VisibilityGraph::release() already does for the clusters
that stay in the list, so pool semantics are unchanged.
```

---

## R2. `VehicleRect` 物件池被 heap 覆寫毒化 → `createVehicleClusters()+0x89` SIGSEGV（兩次逐位元組同簽名）＋同族 glibc abort；writer 仍未定案

## 中文摘要

兩次伺服器 SIGSEGV（8/31、9/7）frame 都是 `libPZPathFind64.so+0x4ca19 PolygonalMap2::createVehicleClusters()+0x89`、`movups %xmm0,(%rbx)`、`RBX=0x30`：`VehicleRect::alloc()`（純 deque pop、零驗證）交出 `0x30` 這個非指標值，寫入即崩。兩份 core 逐格證明 `0x30` 是**上一輪** `VisibilityGraph::release()` 從一個 rect array 已被野寫入破壞的 cluster 逐格 push 進池的（release 是純 push、零驗證）。野寫入形狀兩次相同：16 bytes `{A*-search-node 指標, 0x30}` 落在受害 array 正下方那個 0x20 chunk 的 `+0x18..+0x27`，即蓋掉受害 array 的 glibc size word 與第一個元素；8/31 的指標指向 `Node` 形狀物件、9/7 指向 `HLSearchNode`（vptr 證實）。同族還有 `PathfindNativeThread` 內 `__libc_free`／`malloc_usable_size` SIGSEGV ×3、`malloc(): invalid size (unsorted)` abort ×3、`corrupted size vs. prev_size` ×2（7 月底至 9 月初共 10 起）。**破壞指令未定案**：已排除 `HLSuccessor {node, double}` 正常寫入、`dtNodeQueue` 尾端溢出；與「懸空指標寫進被重切的 chunk」及「型別混淆」皆相容。建議 TIS 用 ASan build 跑一次高車流 MP。

證據等級：hs_err ×2（9/7 含完整 registers）＋core ×2 直讀（池 deque、受害 cluster、chunk header、全 core 指標掃描）＋exact `.so` 反組譯；console 的 glibc abort 訊息 ×5。

### 核實紀錄（2026-09-07）

| 項 | 方法 | 結果 |
|---|---|---|
| fault 指令 | `objdump -d -C --start-address=0x4c9f0 --stop-address=0x4ca1c` | `4c9f0 call VehicleRect::alloc()@plt` → `4c9fc mov %rax,%rbx` → `4c9ff call VehiclePoly::getAABB()@plt` → `4ca19 movups %xmm0,(%rbx)`；`RBX=0x30`＝alloc 回傳值 |
| 兩次同簽名 | hs_err pid 2107211（8/31）／567642（9/7） | 同 `+0x4ca19`、同 `si_addr=0x30`；9/7 registers：`RBX=0x30 RAX=0x20 R15=0x20 R13=&PolygonalMap2::instance` |
| 池零驗證 | `VehicleRect::alloc` `0x5de60`／`release` `0x5e020` 反組譯 | alloc＝`mov (%rax),%r12; add $8,%rax`（front pop），release＝`mov %rdi,-8(%rax); sub $8,%rax`（front push），無任何檢查 |
| 池的生產者／消費者 | 全檔 PLT census | `VehicleRect::alloc@plt` 恰 1（`0x4c9f0`，createVehicleClusters）、`VehicleRect::release@plt` 恰 1（`0x6696c`，VisibilityGraph::release） |
| `0x30` 怎麼進池 | 8/31 core：受害 cluster（在 cluster free pool 內、cap 4／count 2、array=`[0x30, X]`）；本輪 local list index 30 == X；池 `start.cur-8 == 0x30` | release 逐格 push `[slot0, slot1]` ⇒ pop 順序 `[slot1, slot0]`；X 是本輪第 31 顆、`0x30` 是第 32 次 alloc——**逐格吻合**，唯一路徑 |
| 覆寫形狀 | 兩份 core 的受害 array 前後 chunk header | 只有受害 array 的 size word（`array-8`）與 `array+0` 壞：`{ptr, 0x30}`；`array-0x10`、後鄰 size word 完整 ⇒ 短範圍野寫，非 allocator 行為 |
| 覆寫的 ptr | 8/31：`0x60` chunk，首 qword `{0x3f1d, 0x46519a00}`（`{id, float}`＝`Node` 形狀，全 core 21 個引用）；9/7：`0x80` chunk，`vptr == vtable for HLSearchNode + 0x10` | payload 來自 A* 物件 |
| write base（受害 array 正下方 0x20 chunk） | 8/31：在用，內容 `{ptr,0,0}`；9/7：已 free 在 tcache（safe-linked next=NULL、key 正確），全 core 唯一引用＝tcache bin head | 兩種狀態都出現 ⇒ 與 dangling pointer 相容 |
| 已排除 | objdump `HLAStar::setLowestCostSuccessor` 等 | `HLSuccessor {node, double cost}` 寫入是 `mov %rbp,(%rbx); movsd %xmm0,0x8(%rbx)`，`0x30` 當 double 不合法；`VGAStar`／`HLGlobals::astar` 的 `dtNodeQueue`（cap 7500）尾端：兩 core 後鄰 size word 完整、無 node 指標殘留 |
| 家族其他成員 | hs_err ×3＋console | 見 Body 表 |
| 我方觀測器 | guard-page allocator 攔 `reallocate_aligned` 族（private mmap＋PROT_NONE） | 第一輪 4 小時：cluster／list array 族 443,747 個 guarded block（同時最多 4096 個）**零 canary 破壞、零 UAF**；第二輪（A\* 16 個配置點）4 小時 122k block、22.9k live 亦零——寫入目的地是 glibc 內的 0x20 chunk，不是這些 array 的尾端 |

### Title

`[42.20.4] [MP] Server crashes in libPZPathFind64.so: VehicleRect pool poisoned by a 16-byte heap overwrite (createVehicleClusters()+0x89 x2, plus glibc heap aborts on PathfindNativeThread)`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer — dedicated server]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04, glibc 2.39), LinuxGSM, bundled Zulu 25.0.1 JRE + ZGC (-Xms32g -Xmx32g), 254 slots; ~30 concurrent average, 60–95 evening peaks; several hundred vehicles in loaded areas]
Mods: [~80 workshop mods. Everything below is inside vanilla libPZPathFind64.so (sha256 0777dda6db77ddd3059f27f94e0d56fae827b21436b5feb4d719e96878fd21c4) and glibc; no mod code is on any of the stacks and no mod calls into the native pathfinder. The first crash in this family (2026-08-23) predates the auto-driving mod that was later suspected.]
Save: [Any; the corruption is in the native pathfinding heap, not in save data. No save damage observed.]
Crash: [10 native crashes/aborts in 16 days, all in the pathfinding native heap; two with a byte-identical SIGSEGV signature and full core dumps]

Summary
-------
Two SIGSEGVs, 2026-08-31 and 2026-09-07, have the same problematic frame:

  C  [libPZPathFind64.so+0x4ca19]  PolygonalMap2::createVehicleClusters()+0x89
  siginfo: si_signo: 11 (SIGSEGV), si_code: 1 (SEGV_MAPERR), si_addr: 0x0000000000000030
  RAX=0x0000000000000020, RBX=0x0000000000000030, ... R15=0x0000000000000020
  R13=... _ZN13PolygonalMap28instanceE+0x0 in libPZPathFind64.so

     4c9f0:  call   <VehicleRect::alloc()@plt>
     4c9fc:  mov    %rax,%rbx              ; rbx = the "rect" the pool handed out
     4c9ff:  call   <VehiclePoly::getAABB()@plt>
     4ca19:  movups %xmm0,(%rbx)           ; <- fault, rbx == 0x30

VehicleRect::alloc() returned the value 0x30. ObjectPool<VehicleRect> is a
std::deque<void*> with front-push release() / front-pop alloc() and no
validation (0x5de60: mov (%rax),%r12 ; add $0x8,%rax — 0x5e020: mov
%rdi,-0x8(%rax) ; sub $0x8,%rax). The crash is just "write through whatever
the pool gave me". The interesting part is how 0x30 got into the pool, which
both core dumps answer the same way, and what wrote it, which they do not.

How 0x30 enters the pool (from the 2026-08-31 core, byte-exact)
----------------------------------------------------------------
* The whole library has exactly one VehicleRect::alloc@plt site (0x4c9f0,
  createVehicleClusters) and exactly one VehicleRect::release@plt site
  (0x6696c, VisibilityGraph::release). One producer, one consumer.
* In the core, the deque slot that alloc() had just popped (start.cur - 8)
  holds 0x30. total_alloc = 832; the 800 entries still in the deque are all
  unique, valid rect addresses.
* A VehicleCluster in the cluster free pool has capacity 4, count 2 and
  array = [0x30, X]. X is the rect that createVehicleClusters had obtained
  one alloc() earlier (index 30 of its 31-entry local list). release() pushes
  the array front-to-back, alloc() pops front, so releasing [slot0, slot1]
  hands them back as [slot1, slot0]: X first, then 0x30. Slot for slot, that
  is what happened.
* Therefore: the previous VisibilityGraph::release() walked a cluster whose
  rect array had already been overwritten and pushed the garbage first
  element into the pool as if it were a rect pointer. The pool carried it
  until the next rebuild popped it.

The overwrite itself (both cores)
---------------------------------
Only the victim rect array is damaged; the chunk headers before and after it
are intact:

  | location                          | 2026-08-31 core          | 2026-09-07 core                      |
  | victim cluster rect array         | capacity 4 (0x30 chunk)  | capacity 16 (0x90 chunk)             |
  | array-0x10 (prev_size slot)       | 0                        | 0                                    |
  | array-0x8  (glibc size word)      | pointer-like value P     | pointer-like value P                 |
  | array+0x0  (element 0)            | 0x30                     | 0x30                                 |
  | next chunk's size word            | intact                   | intact                               |
  | what P points to                  | live 0x60 chunk whose first qword is {0x3f1d, 0x46519a00} (id/float layout — looks like a Node); 21 references in the core | live 0x80 chunk whose first qword is vtable for HLSearchNode + 0x10, i.e. an HLSearchNode object |
  | the 0x20 chunk directly below the victim ("write base") | in use, contents {ptr, 0, 0} | freed, in tcache (safe-linked next = NULL, key correct); the only reference to it in the whole core is the tcache bin head |

So in both cases a single 16-byte store {A*-search-node pointer, 0x30} landed
at +0x18..+0x27 of a 32-byte chunk (24 usable bytes) — one 16-byte element
past the end of that chunk — and clobbered the size word and element 0 of
whatever came next, which in both cases was a VehicleCluster rect array.

What we have ruled out (objdump against the same .so, plus the cores)
---------------------------------------------------------------------
* The regular HLSuccessor {node, double cost} store in
  HLAStar::setLowestCostSuccessor: it is `mov %rbp,(%rbx); movsd
  %xmm0,0x8(%rbx)`, and 0x30 is not a plausible double.
* Overflow off the end of the two dtNodeQueue heaps (VGAStar and
  HLGlobals::astar, capacity 7500, reused across searches): in both cores the
  chunk following each heap array has an intact size word and no node
  pointers spilled past the end.
* Overflow off the tail of the reallocate_aligned() arrays we could guard:
  we ran the server with those blocks moved to a guard-page allocator
  (private mmap, PROT_NONE page after each block, canary-painted slack).
  Round 1, 4 hours, cluster/rect-list arrays: 443,747 guarded blocks (up to
  4,096 live at a time), zero canary violations, zero use-after-free hits.
  Round 2, 4 hours, the 16 A*-side allocation sites (HLAStar / HLChunkLevel
  / SearchNode / VGAStar growth paths): 122k guarded blocks, 22.9k live,
  zero again. The writer does not run past the end of those arrays; it
  writes into a plain 32-byte glibc chunk.
* Our own Java-side patches and mods: none of the stacks contain mod or
  patch frames, and the first crash in this family (2026-08-23) predates the
  auto-driving mod that was later suspected.

What we cannot say
------------------
The instruction that performs the 16-byte store is not identified. Both
"dangling pointer into a block that has since been re-carved into 0x20
chunks" (the 09-07 write base is a freed tcache chunk, the 08-31 one is a
live 24-byte block) and "type confusion" fit. The record shape {node*, 0x30}
suggests a {pointer, int/size} pair rather than {pointer, double}; 0x30 == 48
could be a count, a capacity or an index.

Crash family on the same server (all vanilla libPZPathFind64.so 42.20.4)
------------------------------------------------------------------------
  | date (local)       | signal / frame                                              | thread                       |
  | 2026-08-23 22:40   | SIGSEGV libc.so.6 malloc_usable_size+0x1d                   | PathfindNativeThread, findPath |
  | 2026-08-24 03:29   | SIGSEGV libc.so.6 __libc_free+0x7e                          | PathfindNativeThread, findPath |
  | 2026-08-31 00:58   | SIGSEGV createVehicleClusters()+0x89, RBX=0x30; then "corrupted size vs. prev_size" abort while writing hs_err | PathfindNativeThread |
  | 2026-09-03 ~03:1x  | abort: corrupted size vs. prev_size in fastbins             | (no hs_err)                  |
  | 2026-09-06 03:13   | abort: malloc(): invalid size (unsorted)                    | (no hs_err)                  |
  | 2026-09-06 03:40   | abort: malloc(): invalid size (unsorted)                    | (no hs_err)                  |
  | 2026-09-06 06:44   | abort: malloc(): invalid size (unsorted)                    | (no hs_err)                  |
  | 2026-09-06 19:04   | SIGSEGV libc.so.6 __libc_free+0x7e                          | PathfindNativeThread, findPath |
  | 2026-09-07 05:41   | SIGSEGV createVehicleClusters()+0x89, RBX=0x30              | PathfindNativeThread         |

Sessions die between 36 minutes and 4 hours after start; the damage
accumulates rather than being immediate. The Java frames under every
PathfindNativeThread crash are PathfindNative.findPath(ByteBuffer,ByteBuffer)
<- findPath(PathFindRequest,ByteBuffer,boolean) <- PathfindNativeThread.updateThread.

Reproduction
------------
Not deterministic. It needs a long session with sustained vehicle traffic
(several hundred vehicles in loaded areas, players driving, frequent
cluster rebuilds) plus continuous zombie pathfinding. We have not been able
to trigger it in SP.

Suggested next steps on your side
---------------------------------
1. Build libPZPathFind64.so once with AddressSanitizer (or at least
   -D_GLIBCXX_ASSERTIONS and bounds checks in ArrayList<T>) and run a busy MP
   session: the store that writes {node*, 0x30} one element past a 24-byte
   block will be reported at its own instruction.
2. Audit code that stores a 16-byte {ISearchNode*/HLSearchNode*/Node*, int}
   record into a buffer whose pointer may be stale after a
   reallocate_aligned() growth (reallocate_aligned frees the old block, so
   any cached pointer to the old array is a dangling write target).
3. Independently of the writer, ObjectPool::release()/alloc() blindly trusting
   pool contents turns a single overwritten qword into a crash one rebuild
   later, far from the cause; a debug-build sanity check there would move
   the report to the first symptom.
4. See the separate report on VehicleCluster::merge() leaking the absorbed
   cluster — it is a distinct bug, but it multiplies the number of stale
   rect arrays in this heap.

Attachments (on request): hs_err_pid567642.log (redacted), the core-dump
analysis output for both cores (JSON with the addresses/values quoted above),
objdump excerpts for the functions named.
```

## 給 Discord／Workshop 玩家的白話版

> 伺服器這陣子偶爾整台猝死（大約每幾天一次，多在凌晨），已經追到是遊戲本體的原生尋路程式庫（`libPZPathFind64.so`）自己把記憶體寫壞：某個地方多寫了 16 個位元組，砸到旁邊「車輛群」的資料，之後車輛一移動、程式重算車輛分群就崩。不是任何 MOD、也不是伺服器硬體。另外同一支程式庫還有個一行就能修的記憶體洩漏（每秒漏掉約 40 個車輛群物件），我們已用觀測工具把兩件事的證據整理好回報官方；官方修好前，伺服器維持每 6 小時排程重啟＋崩潰自動拉起，存檔不受影響。
