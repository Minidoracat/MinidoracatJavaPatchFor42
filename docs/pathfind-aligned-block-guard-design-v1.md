# PathFind aligned-block guard（`libmdcpfguard.so`）設計 v1

**目的只有一個：下一次「第一次野寫入」發生時，讓 SIGSEGV 落在寫入者那條指令上。**
不是止血、不是補症狀、不是把 `0x30` 吞掉。

- 事故與證據：`MinidoracatServerAnalyze/reports/ops/2026-08-31-B42-pathfind-vehiclerect-pool-poisoning.md`
- 目標 library：官方 `libPZPathFind64.so`（42.20.4，sha256 `0777dda6…21c4`）——**不修改、不散布**
- 交付狀態：**已實作＋已通過合成測試；尚未部署正式服**（部署需使用者當次授權）

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
| `native-observer/deploy/run-with-pfguard.sh` | 每次啟動驗 PathFind + observer manifest；絕對路徑 preload；mismatch exit 78 |

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
   `deploy/run-with-pfguard.sh` 在**每次啟動**先 `sha256sum --check`；任一 mismatch exit 78，
   因此自動 Steam 更新保留 launcher 時會 fail-closed，不會把 observer 掛到未驗的新 `.so`。
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
