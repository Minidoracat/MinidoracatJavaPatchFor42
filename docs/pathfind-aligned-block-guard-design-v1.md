# PathFind aligned-block guard（`libmdcpfguard.so`）設計 v1

**目的只有一個：下一次「第一次野寫入」發生時，讓 SIGSEGV 落在寫入者那條指令上。**
不是止血、不是補症狀、不是把 `0x30` 吞掉。

- 事故與證據：`MinidoracatServerAnalyze/reports/ops/2026-08-31-B42-pathfind-vehiclerect-pool-poisoning.md`
- 目標 library：官方 `libPZPathFind64.so`（42.20.4，sha256 `0777dda6…21c4`）——**不修改、不散布**
- 交付狀態：**2026-09-06 19:38 已安裝正式服（使用者授權），生效待下次重啟**。當日觸發背景：
  同族 native crash 一天 4 次（03:13／03:40／06:44 `malloc(): invalid size (unsorted)` abort；
  19:04 SIGSEGV `__libc_free` on `PathfindNativeThread` in `findPath`——首次直接死在尋路執行緒），
  加 8/31、9/3 共 6 次；AutoDrive MOD 上線前（7/30、8/23、8/24）同簽名已存在，排除為根因。
  安裝紀錄：artifact sha `5dd7ceef…4de5`（WSL gcc 13.3／glibc 2.39 建置，85/85 合成測試；
  正式服 `.so` 28/28 前提 PASS）、launcher 備份 `start-server.sh.pre-pfguard-20260906T113847Z`
  （sha `9bfcb6a6…5957` → 新 `a5190841…3aaf`）、全鏈 dry-run 以 pzserver 身分 gate PASS。
  **與 §6-2 第 4 點的一處刻意偏離**：wrapper 在 manifest mismatch／檔案缺失時**不再 exit 78 拒啟**，
  改為印 `STARTUP DISARMED` 橫幅後以 vanilla（僅 libjsig 絕對路徑）啟動——正式服無人工視窗
  （cron 更新＋monitor `*/10`），拒啟會把「未驗的新 `.so`」升級成「全服停機到有人看到」；
  fail-closed 的語意是「絕不 preload 未驗 observer」，不是「不讓遊戲跑」。`PFG_DRY_RUN=1`
  下 mismatch 仍 exit 78 供人工檢查。順帶效果：**libjsig 首次真正生效**（原 launcher 用裸檔名
  ＋不存在的 `jre64/lib/amd64` 路徑，`/proc/pid/maps` 實測從未載入）。

## 1. 為什麼「觀測」是唯一正確的下一步

本次 core 已經證明崩潰點與破壞點**不在同一 round**：受害 `VehicleCluster` 與持有它的
`VisibilityGraph` 在崩潰時都已在各自的 free pool，`PolygonalMap2` 的 cluster list `count=0`。
中間還隔了一層 `ObjectPool<VehicleRect>`（零驗證的 front-push/front-pop deque），
把污染值延遲了至少一個 round 才交給消費者。

⇒ **任何在崩潰點做的事（檢查指標、跳過壞值、重試）都只是把下一次崩潰推遲並讓證據更模糊。**
要抓 writer，必須讓「寫下去的那一刻」就 fault。

## 2. 承重前提（全部已實測，任一條不成立就不能上）

對 exact `.so` 的實測（`readelf`／`objdump`，指令與數量）：

| 前提 | 為什麼承重 | 實測 |
|---|---|---|
| whole-library allocator wrappers | 防止更新新增未盤點的同 allocator 路徑 | `aligned_alloc=2`、`malloc_usable_size=1`、`free=4`、`malloc=1` transfers |
| `reallocate_aligned` 精確組成 | observer 依賴的配置/copy/free contract | **1** `call aligned_alloc` + **1** `call malloc_usable_size` + **2** `call free` |
| `deallocate_aligned` 精確組成 | 唯一 aligned-block deallocation helper | **1** tail `jmp free@plt` |
| `reallocate_aligned` / `deallocate_aligned` 全部經 PLT | `LD_PRELOAD` 才攔得到 DSO **內部**呼叫 | 136 / 94 個 transfer，**0 個 direct transfer** |
| `DT_SONAME` | `RTLD_NOLOAD` real-symbol resolver 的唯一 handle key（無 RTLD_NEXT fallback） | `libPZPathFind64.so` |
| 無 `DF_SYMBOLIC` / `BIND_NOW` | 否則內部呼叫會先綁自己、preload 失效 | `readelf -d` 無 FLAGS 行 |
| 兩個 helper + 四個 caller 都在 dynsym | `dladdr(return_address)` 與 exact resolver 的 runtime contract | `GLOBAL` / `DEFAULT` |
| 四 caller 的精確 shape | tail `jmp` 不會建立白名單函式的 return address | CALL = `1/7/1/3`，每個 JMP = `0` |

⇒ **`reallocate_aligned` / `deallocate_aligned` 這一族 heap block 的生命週期完全閉合在那兩個
函式內**，因此可以把它們**整族搬離 glibc heap**，而 process 其餘部分（`operator new`／`delete`
的 209／553 個呼叫點、JVM、RakNet、popman）**完全不動**。

這是本設計與「通用 malloc shim」的關鍵差別：**我們不換全域 allocator，只換一族有界的 block。**

## 3. 機制

### 3-1. 每個受保護 block 一張獨立映射

```
        ┌───────────────┬──────────────────────┬───────────────┐
mmap →  │ guard PROT_NONE│  data pages  PROT_RW │ guard PROT_NONE│
        └───────────────┴──────────────────────┴───────────────┘
                        ↑
                   user pointer（= data 區起點，page 對齊）
```

- **user pointer 對齊在 data 區起點**：本次事故的損壞正好在 `user-8`（glibc size word）。
  在這個佈局下，`user-8` 落在前置 guard page ⇒ **寫入當場 SIGSEGV，RIP 就是寫入者**。
- 後置 guard page 抓「越過 data 頁尾」的溢出；**page 內的 slack 用 canary 覆蓋**，
  在 free／realloc 時驗證，抓小幅溢出（代價是延後到下一次 allocator 事件才發現）。
- **free 改為 quarantine**：`mprotect(PROT_NONE)`，位址進入有界環（預設 4096 個）後才 `munmap`。
  ⇒ **use-after-free 的讀或寫都當場 fault**。本次事故的受害者正是一個「已被池回收的過期
  cluster 的 array」，UAF 是第一順位待驗機制，這條直接覆蓋它。

### 3-2. 不安裝自己的 signal handler（刻意）

JVM 已經會在 SIGSEGV 時印 hs_err（`Problematic frame`）並落 core。我們**不介入訊號鏈**：

- 不會破壞 first-fault 現場，也不會讓 hs_err 再次被自己的 handler 攪亂。
- 這次的 hs_err 之所以被截斷，是因為 glibc 在 handler 期間又 abort；把這一族 block 移出
  glibc heap 之後，那條路徑就不再參與。
- **相關實測**：官方 `start-server.sh` 的 `LD_PRELOAD="libjsig.so"` 用裸檔名、且
  `LD_LIBRARY_PATH` 指向不存在的 `jre64/lib/amd64` ⇒ **JVM signal chaining 從來沒啟用過**。
  這也是本工具**必須用絕對路徑**掛載的直接教訓（否則會像 jsig 一樣靜默失效，而且不會有人發現）。

### 3-3. 選擇性保護（記憶體有界）

預設**只保護 cluster／rect array 這一族的配置點**，用 `dladdr(RA)` 取 caller 的
**最近動態符號名**比對白名單——不寫死 offset，換版不會把舊 offset 靜默指到別的函式。
這不是函式範圍的形式證明：編譯器若把 callsite inline 到未具名/相鄰 helper，會漏保護或誤配。
因此白名單**不是唯一閘門**：每次更新還須由 `verify-preconditions.sh` 證明每個白名單函式
真的仍有 `reallocate_aligned@plt` edge；線上再看 `allowlist_matched` bitmask。

RA→決策以 256 條 direct-mapped cache 記憶，**由專用 mutex 保護**（不可把 cache 命中率當成
data-race 的理由）。`MDC_PFGUARD_CALLERS` 可覆寫白名單；`MDC_PFGUARD_ALL=1` 保護全部
（有 block 數上限，但 `MDC_PFGUARD=0` kill switch 優先）。

記憶體必須把 **live + quarantine** 一起算：預設各上限 4096，約 8192 mappings、通常約
24,576 VMAs（prefix/data/suffix）。2026-08-31 正式服 `vm.max_map_count=1,048,576`、baseline
VMAs=683，VMA 餘裕充足。48 MiB（小 block）／288 MiB（64 KiB）只是**quarantine admission
baseline 範例，不是 byte 上界**：owned block 可在納管後繼續成長，alignment padding 也增加 VA。
權威值是線上的 `pages_mapped`/maps 計數。入 quarantine 前的 `MADV_DONTNEED` 釋回 data pages；
`madvise_failures>0` 才會讓已觸頁 RSS 留住。`MDC_PFGUARD_ALL=1` 必須先量測再上。


### 3-4. 帳本留在 core 裡（不做 I/O）

`mdc_pfguard_ring`（16,384 筆 × 64 bytes）與 `mdc_pfguard_counters` 都是 shim 的**靜態變數，
且刻意不給初值以確保落在 `.bss`（anonymous private）**，因此：

- hot path 不做 malloc、I/O、log 檔或自訂 signal handler；每筆 event 會短暫取得
  `g_ring_lock`。無競爭時通常是 user-space mutex；**競爭時可進 futex syscall**，故它是
  80 人 tail-latency 的明確風險，不能拿單執行緒 benchmark 當保證。
- 落 core 時帳本**自動在 core 裡**：`coredump_filter=0x31` 含 bit 0（anonymous private）。
- 讀法（旗標式介面，不是位置參數）：
  ```bash
  python3 native-observer/scripts/pfguard_ring.py --core <core> --shim <libmdcpfguard.so> [--limit N]
  python3 native-observer/scripts/pfguard_ring.py --pid  <pid>  --shim <libmdcpfguard.so>
  ```
  解法與本次分析 pool globals 相同：ELF 符號 offset ＋ mapping base（core 走 `NT_FILE`）。
  reader 對 live pid 每個 slot 讀 seq→payload→seq，兩次皆符合 expected generation 才接受；
  **兩條路徑都在 `run-tests.sh` 內有覆蓋**（18,000 events wrap 的 live `--pid`、以及
  用 `gcore` 產生的真 ELF core）。

> **為什麼要特意放進 `.bss`**：`0x31` 不含 bit 2（file-backed private），所以 `.data` 是否落 core
> 取決於 kernel 的「dump segments that have been written to」規則（VMA 有 `anon_vma` 且 bit 0 開
> 就整段 dump）。**該規則在本次正式服 core 上實測成立**（`libPZPathFind64.so` 的 `.data`
> 位址可讀），但沒有理由把帳本壓在一條 kernel 內部規則上；
> 移到 `.bss` 同時讓出貨的 `.so` 從 1.1 MB 縮到 52 KB（1 MiB 的 ring 不再進檔案）。

每筆記錄：`seq / 單調 ns / user ptr / mapping base / caller RA / 前一個 ptr / size / old size / tid / op`。
⇒ 拿到 writer 的 RIP 之後，可以立刻回答「這個受害 block 是誰配置的、多久之前、當時多大」。
### 3-5. 實作對應（檔案）

| 檔案 | 內容 |
|---|---|
| `native-observer/pfguard.c` | shim 本體（兩個 interposed symbols；ring 16,384×64 B；65,536 buckets + 262,144 chain nodes） |
| `native-observer/build.sh` | 建置、匯出符號自檢、exact-SONAME fake DSO、missing-real binary |
| `native-observer/tests/verify-preconditions.sh` | 28 個 binary/PLT/SONAME/dynsym/exact caller-shape gates |
| `native-observer/tests/run-tests.sh` | 85 條：多執行緒、ownership race、ring wrap/live/core、VMA/cap/overflow boundaries |
| `native-observer/tests/fakepathfind.c` | exact `libPZPathFind64.so` SONAME；兩 helper＋四 allowlisted caller，禁止 tail-call |
| `native-observer/tests/missing_real.c` | 不載 target DSO，證明 missing real symbol 會 fail-closed |
| `native-observer/scripts/pfguard_ring.py` | live/core 帳本 reader；seq→payload→seq 同世代才接受 |
| `native-observer/deploy/run-with-pfguard.sh` | 每次啟動驗 PathFind + observer + `pfguard.env` manifest；絕對路徑 preload；任何疑慮（mismatch／缺檔／tuning 行不合法）→ `STARTUP DISARMED` 以 vanilla 啟動（`PFG_DRY_RUN=1` 下 exit 78）；tuning 只接受 `MDC_PFGUARD_*=[A-Za-z0-9_,]*` 行、絕不 source |

## 3-6. 實測結果（2026-08-31，WSL2 Ubuntu 24.04 / glibc 2.39，與正式服同版）

`bash native-observer/tests/verify-preconditions.sh work/native/libPZPathFind64.so`
→ **28/28 `ok`，`RESULT: PASS`**（對 sha `0777dda6…21c4` 的真實 `.so`）。

`bash native-observer/tests/run-tests.sh` → **85 passed, 0 failed**。逐條證明的事：

| 測試 | 證明 |
|---|---|
| `double-uses-plt` | 測試替身有 ≥4 個 `@plt` 呼叫、**0** 個 direct call ⇒ 這組測試真的在演練 interposition（不是自己呼叫自己） |
| `clean` + 4 條 counter 斷言 | 64 輪成長／寫入／釋放：`guard_alloc>0`、`canary=0`、`shrink=0`、結束 `guard_live=0` ⇒ **clean workload 零誤報、零洩漏** |
| `underflow` | 對 `user-8` 寫入（**本次事故的形狀**）→ SIGSEGV，且 handler 印出 `si_addr` 與**寫入指令的 RIP** ⇒ 第一現場可指認 |
| `uaf` | 釋放後寫入 → SIGSEGV（quarantine 生效） |
| `overflow-page` | 越過 data 頁 → SIGSEGV（後置 guard page 生效） |
| `overflow-slack` | page 內的小幅溢出（無硬體 trap）→ 下一次 free 時 canary 抓到 → 自家 `FATAL canary-overflow-on-free` + abort |
| owned shrink / foreign rounding | owned `user_size>newSize` 是 exact logical shrink（`OWNED_SHRINK` + `owned_shrink`）；foreign `malloc_usable_size>request` 另記 `FOREIGN_USABLE_GT_REQUEST` + `foreign_rounding`。兩者都安全 `min()` copy，證據不混用 |
| `delegate` | 非 allowlisted caller 的 block **留在 glibc heap**、對 `user-8` 的寫入**不會** fault ⇒ 選擇性是真的 |
| `mixed-selective` / `mixed-all` | 同一行程內兩種 caller 交錯：mode 1 時 `skip_caller>0` 且只有 allowlisted 的指標 page 對齊；mode 2 時 `skip_caller=0` 且兩者都受保護。（用 `mixed` 而非 `clean` 才有鑑別力——`clean` 只呼叫 allowlisted wrapper，`skip_caller=0` 在兩種模式下都成立） |
| **`owned-stays-guarded`** | **承重不變式**：`MAXBLOCKS=1` 下新配置被 delegate（`second_guarded=0`、`skip_cap>0`）而**已擁有的 block 仍走 guarded 路徑**（`owned_still_guarded=1`，且 payload 完整）⇒ 自家 mmap 指標**永不**交給真函式／`malloc_usable_size` |
| `zero-realloc` / alignment / overflow | `newSize==0` 正確釋放；新 block 非 power-of-two alignment 會 delegate；owned block 支援 >page power-of-two alignment 並保留 payload；兩段 mapping 算術 overflow 都安全失敗 |
| `off` / `all-mode` | `MDC_PFGUARD=0` 全數 delegate（`guard_alloc=0`）；`=2` 旁路 allowlist |
| **`churn`** | 80,000 輪短命 block ＋每輪一次 table miss：**9.1 µs/輪、`nodes_used=4097`**。4096 個 node 對應仍在 quarantine 的 slot，另 1 個是剛配置的 live slot；高水位**固定**、不隨歷史 `guard_alloc` 線性成長 ⇒ 沒有 tombstone 懸崖 |
| ledger reader ×3 | 18,000 events 真 wrap 的 stopped live pid + ELF core 均鎖 `head=18000`／無 uncommitted；另有 active writer 與 `/proc/$pid/mem` reader 真並行測試 |

**overhead（實測）**：allowlisted 的 `alloc(32) → realloc(64) → free` 完整生命週期
**14.2 µs**，delegated 同樣流程 **45 ns**。每次受保護配置 ≈ **7.1 µs**
（一個 cycle 有兩次配置），成本幾乎全是 syscall（`mmap`＋`mprotect`＋quarantine `mprotect`
＋環滿時 `munmap`）與首次觸頁。`churn` 的單向生命週期（alloc＋free）為 **9.1 µs**。

> **兩個必須講清楚的限度**：
> 1. 這個數字是 **WSL2** 量的，WSL2 的 syscall 比裸機貴；正式服應更低，但**我沒有在正式服量過**。
> 2. canary 掃描已限制在 slack 前 **64 bytes**（`MDC_PFGUARD_CANARY`）。原本掃整頁時是
>    18 µs／cycle，改成窗口後 14–15 µs ⇒ **主成本是 syscall，不是 canary**，再優化 canary 沒有意義。

**換算到正式服**（`推測`，用實測單價乘上未知的配置率）：若一輪
`createVehicleClusters` 期間有 G 次受保護配置、每秒 10 輪，額外成本 ≈ `G × 7.1 µs × 10`。
G=200 → 14 ms/s（1.4% wall clock）；G=2000 → 140 ms/s（**不可接受**）。
**所以第一個 canary window 的首要任務是讀 `guard_alloc` 的成長率**，而不是等 crash。

### 一次 review 抓到的 BLOCKING（已修，記錄下來避免重蹈）

初版用**開址 hash table＋tombstone**。因為每個受保護 block 都是**新的 mmap 位址**，
tombstone 只增不減：累積約 65k 次**歷史**配置後表內再無 `state==0`，於是每一次
**miss**（PathFind 那 136+94 個呼叫點的多數都是 miss）都會在 `g_lock` 內掃完整張 4 MB 表。
`MDC_PFGUARD_MAXBLOCKS` 看的是 live 數量，救不了歷史計數；而且是**硬懸崖**，
正式服上會表現為「跑了幾分鐘到幾小時後 pathfind 執行緒突然變慢」，且當時沒有任何 counter 能指認。
**改法**：換成鏈式 hash（bucket 陣列＋node free list），**evicted quarantine node** 才回收，零 tombstone；
正常穩態 high-water = quarantine cap + live，而非歷史 allocation count。新增 `nodes_used` counter 與
`churn` 壓力測試把這件事釘住。

> 好消息（`實測`，來自本次 core）：這條工作**不在主迴圈執行緒上**——crash thread 不是
> 主執行緒（core 的 thread 2）。因此上面的成本落在
> pathfind 工作執行緒，不直接吃 tick；但它仍會與主迴圈競爭 CPU。

## 4. 明確不做（每條都有理由）

| 不做 | 理由 |
|---|---|
| 通用 `malloc`/`free`/`memcpy` interpose | 會把 JVM／ZGC／RakNet／popman／jemalloc(LWJGL) 全拖進來；本次已把嫌疑面收斂到一族有界 block，沒有理由付那個風險 |
| 在 glibc 的 usable area 裡塞 redzone | 會破壞 `malloc_usable_size` 語意，而 vanilla `reallocate_aligned` 正是以它為 copy 長度 ⇒ 反而**放大**溢出 |
| 改寫 `malloc_usable_size` 回傳值 | 同上，且影響全 process |
| 低址指標 guard（`ptr < 0x10000` 就擋） | 只掩蓋症狀，且會讓下一次污染改成「高位垃圾」時完全無聲 |
| 吞掉 `VehicleRect::release(0x30)` | 同上；而且會刪掉唯一的訊號 |
| 自己掛 SIGSEGV handler | 會蓋掉 JVM 的 hs_err／core 產出，等於自毀證據（jsig 本來就沒載入，訊號鏈已經很脆） |
| 修改／hex-patch／重新散布官方 `.so` | 違反散布邊界，且更新即失效 |
| 以 `Pathfind.UseNativeCode=false` 避險 | 實測在 dedicated server **無效**（`IsoWorld.init` 讀值早於 `DebugOptions.load`，唯一的 runtime re-check 只有 client 呼叫）。要走這條得另外做 loose-class patch，且若 writer 在 popman 就完全不止血 |

## 5. 風險與代價（誠實列）

| 風險 | 說明 | 緩解 |
|---|---|---|
| **把潛伏損壞變成即時崩潰** | 這是**設計目的**，不是副作用。今天是隨機 abort＋資料悄悄壞掉；開了之後會在第一次野寫入當場死，並留下可指認的現場 | 當 canary window 用；停用＝`MDC_PFGUARD=0`，**但 mode 只在啟動時讀一次，所以需要下一次重啟才生效**（沒有 runtime 開關，刻意如此：hot path 不讀環境變數）；完全移除＝從 `LD_PRELOAD` 拿掉 |
| 若存在**非 `deallocate_aligned` 的釋放路徑**，會炸 | guarded mmap 指標若走未知 glibc free 路徑會 fault或損壞 | update gate 對 helper 本體同時驗 `call` 與 tail `jmp`，並驗每個 allowlisted caller 都有 `reallocate_aligned@plt` edge；任一不符不部署 |
| vanilla 對**我們的指標**呼叫 `malloc_usable_size` | 只會發生在「我們把 guarded 指標交回真函式」時 | 程式以 slot claim/finish state machine 保證 `old` 或 `p` 屬於我們 ⇒ **永不 delegate**；同指標的 concurrent free/realloc 立即記 `OWNERSHIP_CONFLICT` 後 abort，絕不落入 glibc |
| quarantine 記憶體 | 4096 個 PROT_NONE mappings 仍佔 VA；原先已觸頁的 RSS 由 `MADV_DONTNEED` 降回去 | 監控 `pages_mapped` / `quarantined` / `madvise_failures`；上界見 §3-3，不能只看 `guard_live` |
| 額外 syscall / lock 成本 | ownership 的 `g_lock`、RA cache 的 `g_ra_lock`、**每筆帳本 event 的 `g_ring_lock`** 都是 process-global；後者競爭時可 futex | 合成 suite 含 8-thread correctness stress；正式 canary 仍以 `guard_alloc` rate、RSS、worker latency / CPU 為準，單執行緒數字不是尾延遲保證 |
| owned block 的 align/size 政策 | owned pointer 絕不可交給 real helper；`MAXSIZE` 只限制新 block admission | guarded mmap 支援任意 power-of-two alignment；mapping arithmetic 全段 overflow-check。mmap/VMA 資源失敗時以 `posix_memalign` 建正確對齊 glibc block、copy requested span、quarantine 舊 mmap，避免 unchecked caller 收到 NULL |
| `old_size > newSize` | owned 是 logical shrink；foreign 可能只是 glibc rounding | ring op + counters 分流（`owned_shrink` / `foreign_rounding`），安全 `min()` copy；只有 owned 事件可直接支持 missing-min 假說 |
| 換版失效 | 白名單靠最近動態符號名，inline/unlabelled helper 仍可能漏配或誤配 | update gate 驗 symbol + PLT edge；線上 `allowlist_matched` 為 0x0、或期望 bit 長時間未亮時停止 canary；必要時 `ALL=1` 先量再收斂 |
| `mprotect(PROT_NONE)` 失敗 | quarantine 區仍可寫 ⇒ UAF 可能不當場 fault（要等 FIFO wrap 才 `munmap`） | 回傳值有檢查，計入 `quarantine_failures`；該值非 0 就代表 UAF 偵測有缺口 |

## 6. 部署與回退（**尚未執行；需授權**）

### 6-1. 本機 gate

```bash
bash native-observer/build.sh
bash native-observer/tests/verify-preconditions.sh work/native/libPZPathFind64.so
bash native-observer/tests/run-tests.sh
sha256sum native-observer/out/libmdcpfguard.so native-observer/scripts/pfguard_ring.py
```

### 6-2. 安裝（授權後；**先安裝，不在玩家在線時自行重啟**）

1. 正式服唯讀前置：官方 PathFind SHA 必須仍為
   `0777dda6db77ddd3059f27f94e0d56fae827b21436b5feb4d719e96878fd21c4`；
   記錄 `start-server.sh` SHA、`vm.max_map_count`、當前 `/proc/$pid/maps` 行數。
   2026-08-31 實機：`vm.max_map_count=1,048,576`、current VMAs=683；
   預設 `MAXBLOCKS=4096` 即使每 block 約 3 VMA，也有數十倍餘裕。
2. 使用 `/home/pzserver/scripts/pfguard`，不是 `/home/pzserver/observer`：`scripts/` 已被
   `fix-permissions.sh` prune，root ownership 不會五分鐘後被改回 pzserver。目錄
   `root:pzserver 0750`；`.so`/manifest `root:pzserver 0640`；wrapper/reader `root:pzserver 0750`。
3. 每個檔案先傳入**同一目錄**的隨機 stage 名；以 `install` 設定 stage 的最終 owner/mode，
   核對 stage SHA，再用 `mv -Tf stage final` 做同檔案系統 atomic rename；最後再核 final SHA。
   **不得**把 `install source final` 稱為 atomic，也不得直接 truncate 既有 final inode。
4. 產生 root-owned `manifest.sha256`，內容必含正式服：
   - `/home/pzserver/serverfiles/linux64/libPZPathFind64.so` 的 pinned 官方 SHA；
   - `/home/pzserver/scripts/pfguard/libmdcpfguard.so` 的本次 artifact SHA。
   `deploy/run-with-pfguard.sh` 在**每次啟動**先 `sha256sum --check`；任一 mismatch → `STARTUP DISARMED`
   以 vanilla 啟動（2026-09-06 起；原設計 exit 78 已放棄，見文首），因此自動 Steam 更新保留 launcher 時
   不會把 observer 掛到未驗的新 `.so`，也不會讓遊戲起不來。
5. `cp -a start-server.sh start-server.sh.pre-pfguard-<UTC stamp>` 留 rollback 副本；再用同目錄
   stage＋`mv -Tf` 原子替換 launcher。唯一執行行由：
   ```bash
   LD_PRELOAD="${LD_PRELOAD}:${JSIG}" ./ProjectZomboid64 "$@"
   ```
   改為：
   ```bash
   exec /home/pzserver/scripts/pfguard/run-with-pfguard.sh "$@"
   ```
   wrapper 使用 observer + `jre64/lib/libjsig.so` 的**絕對路徑**。精確舊行不是唯一一筆時
   fail-closed；`bash -n`、owner/mode `pzserver:pzserver 0775`、launcher final SHA 都要重驗。
6. 不改 jar、loose class、官方 `.so`、JVM JSON 或存檔。安裝不影響正在跑的 JVM；
   **啟用需要下一次重啟**。未另行決定立刻受控重啟，就等既有排程。

### 6-3. 啟動後驗收

```bash
pid=$(pgrep -f '[P]rojectZomboid64' | head -1)
grep -F '/home/pzserver/scripts/pfguard/libmdcpfguard.so' "/proc/$pid/maps"
# 以 root 執行（需讀 /proc/$pid/mem）；reader 本身 root:pzserver 0750
python3 /home/pzserver/scripts/pfguard/pfguard_ring.py \
  --pid "$pid" --shim /home/pzserver/scripts/pfguard/libmdcpfguard.so --limit 5
```

1. 啟動 banner 與 `/proc/$pid/maps` 都要命中（防止重演 `libjsig.so` 靜默 preload 失敗）。
2. `allowlist_matched` 必須在幾分鐘內非 0（理想 0xf）；`real_symbol_missing=0`。
3. 記錄 `guard_alloc` 成長率、worker CPU/latency、VMA 數；單執行緒 benchmark 不是 80 人保證。
4. `guard_live`、`pages_mapped`、`quarantined` 一起看；`nodes_used` 正常停在
   quarantine high-water + live（預設約 4097），不可隨歷史 alloc 線性成長。
5. `ownership_conflicts`、`canary_violations`、`quarantine_failures`、`mmap_failures`、
   `madvise_failures` 必須為 0。`skip_capacity` 可表示 soft admission cap；
   **不得同時誤增 `skip_table_full`**。

### 6-4. 回退

還原備份 launcher（先比對 backup SHA／owner／mode），`bash -n`，於下一次重啟恢復原版。
先保留 observer 檔案供事故 core 解碼；不要在運行中或未取得新鮮刪除確認時刪除。

## 7. 目前不能宣稱的事

- **不能**說這會修好 crash：它只把「無聲的隨機損壞」換成「有聲的、可指認的第一現場」。
- **不能**說 writer 已知；本設計就是為了取得它。
- **不能**以「capacity 成長」排除 `reallocate_aligned`：`malloc_usable_size` 的 rounding
  使 `usable(old) > newSize` 可能在邏輯成長時出現。本工具只會記錄該形狀並安全 copy；root cause
  仍未定案。
- **不能**保證一次就抓到：若 writer 寫的是別族 block（`operator new` 的 C++ 物件），
  本工具只會保持安靜。屆時的下一步是把同樣手法套到 `ObjectPool` 的物件族（見 §8）。

## 8. 若第一輪沒抓到（預備路線，尚未實作）

1. 放寬白名單 → `MDC_PFGUARD_ALL=1` 並量測記憶體與 tick 成本。
2. 若仍安靜 ⇒ 受害族不在 aligned-block 這一族。改對 `ObjectPool<T>` 的物件本體下手：
   `VehicleRect::alloc/release` 各只有 1 個 PLT 呼叫點，可用同樣手法接管
   （`operator new(0x28)` 的物件改成 guarded 映射），成本仍有界。
3. 若 popman／MCD 才是 writer：那族的 native 邊界在 `libPZPopMan64.so`（帶完整 DWARF），
   要換的是 `mcd`/`popman` 的容器配置點，屬另案設計。

## 9. 第一輪結果與第二輪（2026-09-07）

### 9-1. 05:41 crash：observer 在場但沒抓到，原因已定案

`hs_err_pid567642`：`PolygonalMap2::createVehicleClusters()+0x89`、`movups %xmm0,(%rbx)`、
`RBX=0x30`、`si_addr=0x30`——與 8/31 **逐位元組同一簽名**（`VehicleRect::alloc()` 從被污染的
池交出 `0x30`）。hs_err 自身在印 summary 時二次 SIGSEGV、native stack 逾時（heap 已壞），只剩
11 KB；core 6.5 GB 已歸檔 NAS。

core 重驗（`native-observer/analysis/core-{recheck,neigh,hlpool}-20260907.py`（位址綁定該 core），
全用 `pfguard_ring.py` 的 stdlib core reader）：

| 項目 | 8/31 | 9/7 |
|---|---|---|
| rect pool 剛 pop 的格位 | `0x30` | `0x30` |
| 受害 cluster rect array | cap 4（0x30 chunk） | cap 16（0x90 chunk） |
| 覆寫範圍 | `[array-8, array+8)` = `{ptr, 0x30}` | 同 |
| 覆寫的 ptr 指向 | `0x60` chunk，首 qword `{0x3f1d, 0x46519a00}`（`Node`／`SearchNode` 形狀） | **`HLSearchNode` 物件（vptr = `vtable for HLSearchNode`+0x10，`0x80` chunk）** |
| 受害 array 正下方 | `0x20` chunk、**在用**（`{ptr,0,0}`） | `0x20` chunk、**已 free 在 tcache**（safe-linked next=NULL、key） |
| 受害 array 是否 guarded | — | **否**（glibc chunk，非 page-aligned） |
| observer counters（core 內） | — | `guard_alloc=443,747`、`skip_capacity=1,380,923`、`guard_live=4096` 釘死、`canary_violations=0`、`ownership_conflicts=0`；ring 最後 16k 筆 **100% 來自 `createVehicleClusters` 的暫時 local list**。**注意**：`pfguard.c` 的 capacity 檢查在 allowlist 之前（L625 vs L631），`skip_capacity` 計的是 cap 滿之後**所有** caller 的呼叫，故不能換算成「覆蓋率」；正確敘述是「開機約 100 秒 cap 飽和後，任何 caller 的新配置一律不再 guard」 |

三個結論（2026-09-07 三 lane review 後修正措辭）：

1. **寫入形狀（實測）**：兩次都是 16 bytes `{A*-search-node 指標, 0x30}` 落在「受害 array 正下方那個
   `0x20` chunk（usable 24 bytes）的 +0x18..+0x27」。8/31 的指標指向一個首 qword 為 `{id, float}` 形狀的
   `0x60` chunk（**較像 `Node`**，不是有 vptr 的 `SearchNode`）、9/7 指向 `HLSearchNode`（vptr 證實）。
   **推論（非定案）**：payload 來自 A\* 物件；writer 的 RIP 仍未知——「型別混淆」與「懸空指標寫入重切過的
   chunk」都相容，也不能排除同一 16 bytes 來自兩條不同指令。已排除：(a) `HLSuccessor {node, double cost}`
   的正常寫入（objdump：`mov %rbp,(%rbx); movsd %xmm0,0x8(%rbx)`，0x30 當 double 不合法）；(b) 兩個
   `dtNodeQueue`（`VGAStar`、`HLGlobals::astar`，cap 7500，`AStar::init` 重用同一 heap 陣列故長壽）
   的尾端溢出——兩份 core 的後鄰 chunk size word 完整、無 node 指標殘留；(c) 9/7 core 全 core 掃描：
   write base 唯一參照是 tcache bin head、兩個 A\* 池的 parent 欄位無人指向它（歷史／已消失的參照
   無法排除）。
2. **victim 族在現行 cap 下無法覆蓋（實測）**：`VehicleCluster::merge` 把被併入的 cluster 從
   `PolygonalMap2` 清單 `memmove` 掉、`count=0`，**從不 `release`**（全 `.so` 唯一 release 點在
   `VisibilityGraph::release`）——cluster 物件與其 rect array 每秒約 38–41 個一去不回（8/31 core：
   58 分鐘 131,959；9/7 core：4h04m 602,901；pool free 分別 427／183）。live 白名單 block 因此線性
   成長，4096 cap 開機約 100 秒飽和，之後新的 cluster array 零覆蓋（core 內 183 個 free-pool cluster
   的 array 0/183 page-aligned——該次樣本，非全部歷史）。可回報 TIS 的獨立 bug；「每 6 小時 ~85 MB」
   是物件數換算，未含 array 尺寸分佈。
3. HL 兩個 pool（`HLSuccessor` 26,496／`HLSearchNode` 7,500）在**崩潰時**全數在 free pool、零垃圾
   ——只能說「崩潰當下 HL 池乾淨」，不能說「HL 池從未被毒化」；已觀察到的毒化只在 rect pool
   （經 `VisibilityGraph::release` 洗入）。

### 9-2. 第二輪組態（2026-09-07 06:19 已裝、12:00 重啟生效；review 後判定為「有界診斷」而非主線）

wrapper 讀同目錄 `pfguard.env`（root:pzserver 0640，**列入 manifest**；只接受
`MDC_PFGUARD_*=[A-Za-z0-9_,]*` 行、絕不 source；缺檔／被改／行不合法一律 `STARTUP DISARMED`）：

- `MDC_PFGUARD_CALLERS`＝16 個 A\* 層的 `reallocate_aligned` caller（`HLAStar::findPath`／
  `addChunkLevelAndAdjacentToList`×2／`setLowestCostSuccessor`／`getSuccessors`／`addSuccessor`×3、
  `HLChunkLevel::init{Stairs,Regions,SlopedSurfaces}`、`SearchNode::getSuccessors`、
  `VGAStar::getSearchNode`×3、`PolygonalMap2::findPathHighLevelThenLowLevel`）；全部 CALL 形狀、
  皆在 dynsym（`verify-preconditions.sh` 現在直接讀 `pfguard.env` 逐一驗；78 checks）。
  **刻意拿掉** `createVehicleCluster`／`merge`（洩漏族，會在 27 分鐘內把 65536 cap 打滿）。
  已知盲區：`AStar::shortestPath`（`+0x31620`，自身也 `reallocate_aligned`）不在 16 名內；
  `dtNodeQueue` 走 `dtAlloc`→`malloc`，`ALL=1` 也不涵蓋。
- `MDC_PFGUARD_MAXBLOCKS=65536`、`MDC_PFGUARD_QUARANTINE=16384`：正常 `nodes_used` 約 81,920＋
  realloc 暫時 slot，遠低於 262,144；**RSS 沒有上界**（§3-3：owned block 可繼續成長，「≈256 MB」
  只是小 block 情境估計），以線上 `pages_mapped`／RSS／VMA 為準。
- 命中假說（只測其一）：writer 透過**過期的 A\* array 指標**寫入（realloc 搬走後仍寫舊 block）→ 舊
  block 在 quarantine（PROT_NONE）→ 寫入指令當場 SIGSEGV。**抓不到**：壞指標來自 pool 物件
  （`HLSearchNode`／`SearchNode`／rb-tree node）、或寫入目的地不是 A\* array。
- 驗收：重啟後 banner `callers=16 maxblocks=65536 quarantine=16384`、`allowlist_matched` 位元逐漸
  點亮、`skip_capacity` 增長率、`pages_mapped`／RSS／VMA、`PathfindNativeThread` CPU 對照第一輪 42%。

### 9-3. 三 lane review（2026-09-07；grok-4.6:xhigh 完整結構化輸出、gpt-6-astra:max 五段 prose
（結構化 yield 兩次被 provider 內容政策擋下）、claude-fable-5-1:high 部分（final yield 失敗，
只留 hub 摘要））——共識與修正

- **共識 finding（已修）**：wrapper `set -e` 下 `source pfguard.env` 會讓格式錯誤變成拒啟而非
  DISARMED；`pfguard.env` 不在 manifest（缺檔靜默回到 round-1 預設）；`run-tests.sh` 仍斷言
  `STARTUP FATAL`；`verify-preconditions.sh` 仍只驗 round-1 四個 caller。→ wrapper 改嚴格解析＋env
  入 manifest＋DISARMED 路徑剔除環境帶入的 observer；`run-tests.sh` 改 9 條行為測試（假遊戲二進位驗
  preload／argv／匯出）；`verify-preconditions.sh` 讀 env 逐 caller 驗 dynsym＋CALL 形狀。
- **Grok 的關鍵反駁（採納）**：guard page 只在「寫入目的地本身是 guarded block」時發作；兩份 core 的
  目的地都是 **cluster rect array 的 `user-8` 與 `user+0`**——若受害 array 是 guarded block，`user-8`
  正是前置 PROT_NONE 頁，writer 會**當場**被抓（設計 §3-1 的本案）。round-1 沒抓到不是形狀免疫，是
  洩漏把 cap 打滿讓長壽 cluster array 零覆蓋；round-2 改瞄 A\* array 是「用同一把尺量比較不可能是
  目的地的東西」。⇒ **最有價值的儀器是把 victim 族守住**，前提是洩漏被止住（否則任何 cap 都會飽和）。
- **Codex 的補充**：round-2 只測「過期 A\* array」一種假說；HLSuccessor 正常路徑排除；dtNodeQueue
  現存陣列尾端完整只排除「目前 backing array 的持續尾端污染」，不是歷史釋放 block 的形式排除；
  core 腳本的 BASE／pool 偏移雖與 decompile 對得上，仍應核 NT_FILE 與 deque 游標（`start_cur-8`
  只適用未跨 deque 區塊的 pop）。

### 9-4. 第三輪（2026-09-07 授權後實作；shim layout **v5**）

1. **止血洩漏（唯一的行為變更）**：shim 以 LD_PRELOAD 同名定義接管 `VehicleCluster::merge`
   （全 `.so` 唯一 PLT 呼叫點＝`createVehicleCluster`，GLOBAL/DEFAULT dynsym）與
   `VehicleCluster::alloc`（2 個 PLT 呼叫點，同一函式）；真實定義以 `RTLD_NOLOAD` handle 的
   `dlsym` 取得（連同 `VehicleCluster::release`），三者任一缺失＝`real_symbol_missing`
   → 首次呼叫 fail-fast，不自製替身。流程：呼叫真 merge → 若 `src!=NULL && src!=dst &&
   src->count(+0x0c)==0` → 位址寫入 shim 私有集合（open addressing、backward-shift 刪除、
   4096 槽、佔半即拒）→ 成功插入才呼叫真 `release(src)`（回池；array／cap 保留、`init` 只清
   count＝池既有語意）；`alloc` 回傳的位址從集合移除。**集合的用途只有一個**：stale
   backpointer（我們在追的那種損毀）可能讓同一 cluster 被 merge 兩次——第二次插入失敗就不
   release，杜絕「池把同一物件租給兩個人」這個新失效模式。每條跳過路徑都有計數：
   `merge_skipped_state`／`merge_double_release_blocked`／`merge_set_full`；`merge_calls`／
   `merge_released`／`cluster_alloc_calls` 對帳。旋鈕 `MDC_PFGUARD_MERGE_RELEASE=0`（pfguard.env，
   下次重啟生效）整段關閉＝回到 vanilla 洩漏。安全依據（42.20.4 反編譯逐行複核）：merge 前
   `createVehicleCluster` 已把 src `memmove` 出 cluster 清單、merge 內把 src 每個 rect 的
   backpointer 改指 dst、結尾無條件 `src->count=0`、merge 後 caller 不再觸碰 src；graphs 在所有
   cluster 建完後才建立 ⇒ src 無其他持有者。
2. **重瞄 victim 族**：`pfguard.env` allowlist＝`createVehicleCluster`＋`VehicleCluster::merge`
   （rect array 的兩個成長點；洩漏止血後有界）＋原 16 個 A\* caller（有界診斷），共 18，
   `PFG_MAX_CALLERS` 16→32；**排除 `createVehicleClusters`**（每輪暫時 list）。cap 維持 65536、
   quarantine 16384。
3. **驗證**：`run-tests.sh` 115/115（新增 merge-release／merge-double／merge-off／merge-reuse／
   merge-self／merge-churn 20k 步含影子模型對帳／merge-set-full 2100：集合滿只跳過、alloc 排空後
   恢復；threads 壓力改走真 merge 路徑），fake `.so` 以 42.20.4 語意實作 alloc／release／merge
   （含 merge 內經 `reallocate_aligned@plt` 成長＝merge 仍是 allowlist caller）；
   `verify-preconditions.sh` 91/91 對正式 `.so`（新增：三符號 GLOBAL/DEFAULT、merge/release/alloc
   PLT 邊 1/1/2 且 direct 0、merge 只由 createVehicleCluster 呼叫、release 只由
   `VisibilityGraph::release` 呼叫、merge 結尾 `movl $0x0,0xc(%reg)`、merge 內 realloc 恰 1）。
4. **驗收（下次排程重啟後）**：橫幅 `v5 … callers=18 merge_release=1`；`pfguard_ring.py --pid`：
   `merge_released≈merge_calls`、`merge_double_release_blocked=0`、`merge_set_full=0`、
   `guard_live` 有界（不再釘在 cap）、`skip_capacity` 不再成長、`allowlist_matched` 含 bit 0/1、
   anomalies 0；cluster 池 `total_alloc` 斜率塌陷。`merge_double_release_blocked>0` 本身就是
   「stale backpointer 存在」的直接證據，要連同 ring 一起保存。
5. **不做**：`VehicleRect::alloc/release` 的值驗證（heap 已壞，只是把崩潰點推後、抹掉證據，設計 §4）；
   行程內重建 native world（共用 arena 已污染）；Java 側限速 vehicle task（不改變 writer，dirty bit
   已合併重建）；`UseNativeCode=false`（dedicated 無效）。
6. **平行**：向 TIS 回報兩個獨立缺陷（merge 洩漏＝有行號的確定 bug；rect pool 零驗證＋兩次同簽名
   `0x30`），不宣稱 writer 已知。
