/*
 * libmdcpfguard.so — PZ 42.20.4 libPZPathFind64.so aligned-block guard (observation only).
 *
 * Interposes exactly two exported PathFind symbols:
 *     _Z18reallocate_alignedPvmm   reallocate_aligned(void*, unsigned long, unsigned long)
 *     _Z18deallocate_alignedPv     deallocate_aligned(void*)
 *
 * Selected blocks are moved off the glibc heap into private mmap regions with PROT_NONE
 * guard pages on both sides and the user pointer pinned to the start of the data pages,
 * so a write below the block (the shape observed in the 2026-08-31 core: user-8) faults
 * on the writing instruction. Freed regions are quarantined (PROT_NONE, address not
 * reused until the bounded ring wraps) so use-after-free accesses fault too.
 *
 * Deliberately does NOT install a signal handler: the JVM already reports the faulting
 * frame in hs_err and dumps a core. The forensic ledger lives in static storage so it
 * lands inside that core.
 *
 * Design, risks and load-bearing preconditions:
 *     docs/pathfind-aligned-block-guard-design-v1.md
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <malloc.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define PFG_PAGE            4096UL
#define PFG_RING_CAP        16384u
#define PFG_BUCKETS         65536u          /* power of two */
#define PFG_NODES           262144u         /* hard cap on simultaneously guarded blocks */
#define PFG_QUAR_MAX        16384u
#define PFG_RA_CACHE        256u
#define PFG_MAX_CALLERS     16
#define PFG_CANARY_BYTE     0xA5
#define PFG_CANARY_WINDOW   64          /* bytes of slack painted/checked per block */
#define PFG_LAYOUT_VERSION  4u

/* ASCII tags so the core reader can prove it found the right structures. */
#define PFG_RING_MAGIC      0x4746504344444D55ULL  /* "UMDDCPFG" little-endian bytes */
#define PFG_COUNTER_MAGIC   0x53544E554F434D55ULL  /* "UMCOUNTS" little-endian bytes */

/* ------------------------------------------------------------------ layout */

enum pfg_op {
    PFG_OP_GUARD_ALLOC = 1,
    PFG_OP_GUARD_FREE = 2,
    PFG_OP_DELEGATE_REALLOC = 3,
    PFG_OP_DELEGATE_FREE = 4,
    PFG_OP_FOREIGN_USABLE_GT_REQUEST = 5,
    PFG_OP_CANARY = 6,
    PFG_OP_MMAP_FAIL = 7,
    PFG_OP_TABLE_FULL = 8,
    PFG_OP_OWNERSHIP_CONFLICT = 9,
    PFG_OP_OWNED_SHRINK = 10,
};

enum pfg_slot_state {
    PFG_SLOT_LIVE = 1,
    PFG_SLOT_CLAIMED = 2,
    PFG_SLOT_QUARANTINED = 3,
};

struct pfg_event {                  /* 64 bytes; natural alignment == packed */
    uint64_t seq;
    uint64_t ns;
    uint64_t user;
    uint64_t base;
    uint64_t ra;
    uint64_t old_user;
    uint32_t size;
    uint32_t old_size;
    uint32_t tid;
    uint16_t op;
    uint16_t flags;
};

struct pfg_ring {
    uint64_t magic;
    uint32_t version;
    uint32_t entry_size;
    uint64_t capacity;
    uint64_t head;                  /* monotonic; newest slot = (head-1) % capacity */
    uint64_t reserved[3];
    struct pfg_event ev[PFG_RING_CAP];
};

struct pfg_counters {               /* field order is the reader's contract */
    uint64_t magic;
    uint64_t version;
    uint64_t mode;
    uint64_t whitelist_symbols;     /* configured names */
    uint64_t allowlist_matched;     /* bitmask of names actually seen at runtime */
    uint64_t guard_alloc;
    uint64_t guard_free;
    uint64_t guard_live;
    uint64_t guard_peak;
    uint64_t bytes_live;
    uint64_t pages_mapped;
    uint64_t quarantined;
    uint64_t quarantine_unmapped;
    uint64_t delegate_realloc;
    uint64_t delegate_free;
    uint64_t owned_shrinks;
    uint64_t foreign_usable_gt_request;
    uint64_t skip_not_allowlisted;
    uint64_t skip_align;
    uint64_t skip_size;
    uint64_t skip_capacity;
    uint64_t skip_table_full;
    uint64_t canary_violations;
    uint64_t shrink_anomalies;
    uint64_t mmap_failures;
    uint64_t real_symbol_missing;
    uint64_t ra_cache_hit;
    uint64_t ra_cache_miss;
    uint64_t quarantine_failures;
    uint64_t nodes_used;
    uint64_t ownership_conflicts;
    uint64_t madvise_failures;
};

/* Chained hash, never open-addressed: every guarded block gets a fresh mmap address, so
 * an open-addressed table would accumulate tombstones for the *lifetime* count of
 * allocations and eventually make every miss a full-table scan under the lock. */
struct pfg_slot {
    uint64_t user;
    uint64_t base;
    uint64_t map_len;
    uint64_t data_len;
    uint64_t user_size;
    uint64_t ra;
    uint32_t tid;
    int32_t next;                   /* index+1 link; 0 terminates the bucket/free list */
    uint32_t state;                 /* enum pfg_slot_state */
    uint32_t reserved;
};

struct pfg_quar {
    uint64_t base;
    uint64_t map_len;
    uint64_t user;
    uint64_t seq;
};

/* Exported on purpose: gdb and scripts/pfguard_ring.py locate the ledger by symbol.
 *
 * Deliberately left uninitialised so both objects land in .bss (an anonymous private
 * mapping) rather than .data. A written-to private *file-backed* mapping is also dumped
 * under coredump_filter=0x31 (the kernel dumps any VMA that has an anon_vma when
 * MMF_DUMP_ANON_PRIVATE is set — verified on the 2026-08-31 production core, whose
 * libPZPathFind64.so .data is readable), but relying on that rule buys nothing and
 * keeping the 1 MiB ring out of the shipped .so is strictly better. The header fields
 * are filled in by pfg_init(); a core taken before the constructor ran shows magic=0,
 * and the reader says so instead of guessing.
 */
struct pfg_ring mdc_pfguard_ring;
struct pfg_counters mdc_pfguard_counters;

static struct pfg_slot g_nodes[PFG_NODES];
static int32_t g_buckets[PFG_BUCKETS];  /* link = node index + 1; 0 = empty (zeroed .bss) */
static int32_t g_free_head;             /* recycled node list; 0 = none */
static uint32_t g_nodes_high;           /* bump allocator high-water mark */
static struct pfg_quar g_quar[PFG_QUAR_MAX];
static uint32_t g_quar_head;        /* oldest entry */
static uint32_t g_quar_live;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_ring_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_ra_lock = PTHREAD_MUTEX_INITIALIZER;

struct pfg_ra_cache_entry {
    uint64_t ra;
    uint32_t allow;
    uint32_t valid;
};
static struct pfg_ra_cache_entry g_ra_cache[PFG_RA_CACHE];

/* ------------------------------------------------------------------ config */

static int g_mode = 1;              /* 0 off, 1 selective (allowlisted callers), 2 all */
static int g_trace_delegated;
static size_t g_max_size = 64 * 1024;
#ifdef PFG_TESTING
static int g_test_fail_owned_map;
#endif
static size_t g_canary_window = PFG_CANARY_WINDOW;
static uint64_t g_max_blocks = 4096;
static uint32_t g_quar_cap = 4096;
static const char *g_callers[PFG_MAX_CALLERS];
static int g_ncallers;
static char g_callers_env[2048];

static const char *const PFG_DEFAULT_CALLERS[] = {
    "_ZN13PolygonalMap221createVehicleClustersEv",
    "_ZN13PolygonalMap220createVehicleClusterEP11VehicleRectR9ArrayListIS1_ERS2_IP14VehicleClusterE",
    "_ZN14VehicleCluster5mergeEPS_",
    "_ZN15VisibilityGraph8trySplitEP4EdgeP11VehicleRectR9ArrayListIiE",
};

static void *(*g_real_realloc)(void *, size_t, size_t);
static void (*g_real_free)(void *);
static pthread_once_t g_resolve_once = PTHREAD_ONCE_INIT;

#define PFG_INC(field) __atomic_add_fetch(&mdc_pfguard_counters.field, 1, __ATOMIC_RELAXED)
#define PFG_ADD(field, n) __atomic_add_fetch(&mdc_pfguard_counters.field, (uint64_t)(n), __ATOMIC_RELAXED)
#define PFG_SUB(field, n) __atomic_sub_fetch(&mdc_pfguard_counters.field, (uint64_t)(n), __ATOMIC_RELAXED)

/* ------------------------------------------------------------------ helpers */

static uint64_t pfg_now_ns(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static __thread uint32_t t_tid;

static uint32_t pfg_tid(void)
{
    if (t_tid == 0)
        t_tid = (uint32_t)gettid();
    return t_tid;
}

static void pfg_record(uint16_t op, void *user, void *base, void *ra, void *old_user,
                       size_t size, size_t old_size)
{
    pthread_mutex_lock(&g_ring_lock);
    uint64_t head = __atomic_add_fetch(&mdc_pfguard_ring.head, 1, __ATOMIC_RELAXED);
    struct pfg_event *ev = &mdc_pfguard_ring.ev[(head - 1) % PFG_RING_CAP];
    /* The mutex owns the physical slot across generations. `seq` is still a commit
     * marker for a core captured mid-write; without the mutex an old stalled writer
     * could wake after 16,384 newer records and race the same payload slot. */
    __atomic_store_n(&ev->seq, 0, __ATOMIC_RELEASE);
    ev->ns = pfg_now_ns();
    ev->user = (uint64_t)(uintptr_t)user;
    ev->base = (uint64_t)(uintptr_t)base;
    ev->ra = (uint64_t)(uintptr_t)ra;
    ev->old_user = (uint64_t)(uintptr_t)old_user;
    ev->size = size > UINT32_MAX ? UINT32_MAX : (uint32_t)size;
    ev->old_size = old_size > UINT32_MAX ? UINT32_MAX : (uint32_t)old_size;
    ev->tid = pfg_tid();
    ev->op = op;
    ev->flags = 0;
    __atomic_store_n(&ev->seq, head, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&g_ring_lock);
}

static void pfg_write(int fd, const char *buf, int len)
{
    if (len <= 0)
        return;
    ssize_t written = write(fd, buf, (size_t)len);
    (void)written;                  /* diagnostics only; nothing to recover here */
}

static void pfg_fatal(const char *what, void *user, void *ra, size_t a, size_t b)
{
    char buf[320];
    int n = snprintf(buf, sizeof(buf),
                     "[mdc-pfguard] FATAL %s user=%p ra=%p a=%zu b=%zu tid=%u seq=%llu\n",
                     what, user, ra, a, b, pfg_tid(),
                     (unsigned long long)__atomic_load_n(&mdc_pfguard_ring.head, __ATOMIC_RELAXED));
    pfg_write(STDERR_FILENO, buf, n);
    abort();
}

static void pfg_resolve(void)
{
    void *handle = dlopen("libPZPathFind64.so", RTLD_NOLOAD | RTLD_LAZY);
    if (handle != NULL) {
        g_real_realloc = (void *(*)(void *, size_t, size_t))dlsym(handle, "_Z18reallocate_alignedPvmm");
        g_real_free = (void (*)(void *))dlsym(handle, "_Z18deallocate_alignedPv");
    }
    if (g_real_realloc == NULL || g_real_free == NULL)
        PFG_INC(real_symbol_missing);
}

static void *pfg_delegate_realloc(void *old, size_t new_size, size_t align, void *ra)
{
    PFG_INC(delegate_realloc);
    if (g_trace_delegated)
        pfg_record(PFG_OP_DELEGATE_REALLOC, NULL, NULL, ra, old, new_size, 0);
    if (g_real_realloc == NULL) {
        pfg_fatal("real-reallocate-symbol-missing", old, ra, new_size, align);
        return NULL;
    }
    return g_real_realloc(old, new_size, align);
}

static void pfg_delegate_free(void *p, void *ra)
{
    PFG_INC(delegate_free);
    if (g_trace_delegated)
        pfg_record(PFG_OP_DELEGATE_FREE, p, NULL, ra, NULL, 0, 0);
    if (g_real_free == NULL) {
        pfg_fatal("real-deallocate-symbol-missing", p, ra, 0, 0);
        return;
    }
    g_real_free(p);
}

/* ------------------------------------------------------------------ table */

static uint32_t pfg_hash(uint64_t key)
{
    key ^= key >> 33;
    key *= 0xff51afd7ed558ccdULL;
    key ^= key >> 33;
    return (uint32_t)key & (PFG_BUCKETS - 1);
}

/* Links are stored as `index + 1` so that a zero-filled .bss already means "empty".
 * That removes any dependency on constructor ordering: if a hook somehow ran before
 * pfg_init(), an all-zero bucket array is a valid empty table rather than a self-loop.
 * caller holds g_lock for all four helpers. */
static int32_t pfg_node_alloc(void)
{
    if (g_free_head != 0) {
        int32_t link = g_free_head;
        g_free_head = g_nodes[link - 1].next;
        return link;
    }
    if (g_nodes_high < PFG_NODES) {
        g_nodes_high++;
        __atomic_store_n(&mdc_pfguard_counters.nodes_used, g_nodes_high, __ATOMIC_RELAXED);
        return (int32_t)g_nodes_high;       /* link = index + 1 */
    }
    return 0;
}

static struct pfg_slot *pfg_find(void *user)
{
    uint64_t key = (uint64_t)(uintptr_t)user;
    int32_t link = g_buckets[pfg_hash(key)];
    while (link != 0) {
        struct pfg_slot *node = &g_nodes[link - 1];
        if (node->user == key)
            return node;
        link = node->next;
    }
    return NULL;
}

static int pfg_insert(const struct pfg_slot *entry)
{
    int32_t link = pfg_node_alloc();
    if (link == 0)
        return 0;
    uint32_t bucket = pfg_hash(entry->user);
    struct pfg_slot *node = &g_nodes[link - 1];
    *node = *entry;
    node->state = PFG_SLOT_LIVE;
    node->next = g_buckets[bucket];
    g_buckets[bucket] = link;
    return 1;
}

static void pfg_erase(void *user)
{
    uint64_t key = (uint64_t)(uintptr_t)user;
    int32_t *link = &g_buckets[pfg_hash(key)];
    while (*link != 0) {
        struct pfg_slot *node = &g_nodes[*link - 1];
        if (node->user == key) {
            int32_t self = *link;
            *link = node->next;
            node->user = 0;
            node->next = g_free_head;
            g_free_head = self;
            return;
        }
        link = &node->next;
    }
}

/* ------------------------------------------------------------------ mapping */

static void *pfg_map(size_t size, size_t align, size_t *map_len, size_t *data_len,
                     void **base_out)
{
    if (align == 0 || (align & (align - 1)) != 0)
        return NULL;
    size_t effective_align = align < PFG_PAGE ? PFG_PAGE : align;
    if (size > SIZE_MAX - (PFG_PAGE - 1))
        return NULL;
    size_t data = (size + PFG_PAGE - 1) & ~(PFG_PAGE - 1);
    size_t align_padding = effective_align - PFG_PAGE;
    if (data == 0 || data > SIZE_MAX - 2 * PFG_PAGE ||
        data + 2 * PFG_PAGE > SIZE_MAX - align_padding)
        return NULL;
    size_t len = data + 2 * PFG_PAGE + align_padding;
    void *base = mmap(NULL, len, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (base == MAP_FAILED)
        return NULL;
    uintptr_t start = (uintptr_t)base + PFG_PAGE;
    if (start > UINTPTR_MAX - (effective_align - 1)) {
        munmap(base, len);
        return NULL;
    }
    uintptr_t aligned = (start + effective_align - 1) & ~(uintptr_t)(effective_align - 1);
    void *user = (void *)aligned;
    if (mprotect(user, data, PROT_READ | PROT_WRITE) != 0) {
        munmap(base, len);
        return NULL;
    }
    size_t canary = data - size;
    if (canary > g_canary_window)
        canary = g_canary_window;
    if (canary > 0)
        memset((char *)user + size, PFG_CANARY_BYTE, canary);
    *map_len = len;
    *data_len = data;
    *base_out = base;
    return user;
}

/* Only the first g_canary_window bytes of slack are painted, so only those are checked:
 * a small overflow lands immediately after the block, and scanning a whole page on every
 * allocator event costs more than it buys (measured 18 us -> 4 us per lifecycle). */
static int pfg_canary_ok(const struct pfg_slot *slot)
{
    size_t data = slot->data_len;
    size_t canary = data - slot->user_size;
    if (canary > g_canary_window)
        canary = g_canary_window;
    const unsigned char *p = (const unsigned char *)(uintptr_t)slot->user + slot->user_size;
    for (size_t i = 0; i < canary; i++, p++) {
        if (*p != PFG_CANARY_BYTE)
            return 0;
    }
    return 1;
}

/* caller holds g_lock; the slot remains searchable as QUARANTINED until eviction.
 * That is deliberate: a second free/realloc must be diagnosed here, never delegated
 * to glibc with one of our mmap pointers. */
static int pfg_quarantine_claimed(struct pfg_slot *slot)
{
    if (slot == NULL || slot->state != PFG_SLOT_CLAIMED)
        return 0;

    void *user = (void *)(uintptr_t)slot->user;
    size_t data = slot->data_len;
    if (madvise(user, data, MADV_DONTNEED) != 0)
        PFG_INC(madvise_failures);
    if (mprotect(user, data, PROT_NONE) != 0)
        PFG_INC(quarantine_failures);

    if (g_quar_live == g_quar_cap) {
        struct pfg_quar *victim = &g_quar[g_quar_head];
        void *victim_user = (void *)(uintptr_t)victim->user;
        struct pfg_slot *victim_slot = pfg_find(victim_user);
        if (victim_slot != NULL && victim_slot->state == PFG_SLOT_QUARANTINED)
            pfg_erase(victim_user);
        else
            PFG_INC(ownership_conflicts);
        munmap((void *)(uintptr_t)victim->base, victim->map_len);
        PFG_INC(quarantine_unmapped);
        PFG_SUB(pages_mapped, victim->map_len / PFG_PAGE);
        memset(victim, 0, sizeof(*victim));
        g_quar_head = (g_quar_head + 1) % g_quar_cap;
        g_quar_live--;
    }

    slot->state = PFG_SLOT_QUARANTINED;
    struct pfg_quar *entry = &g_quar[(g_quar_head + g_quar_live) % g_quar_cap];
    entry->base = slot->base;
    entry->map_len = slot->map_len;
    entry->user = slot->user;
    entry->seq = __atomic_load_n(&mdc_pfguard_ring.head, __ATOMIC_RELAXED);
    g_quar_live++;
    PFG_INC(quarantined);
    return 1;
}

/* Atomically claims ownership under g_lock. Return 1 for a live guarded block, 0 for a
 * foreign pointer, and -1 for a second/concurrent operation on a claimed or quarantined
 * block. The latter is evidence, not a reason to call the real allocator. */
static int pfg_claim(void *user, struct pfg_slot *owned)
{
    pthread_mutex_lock(&g_lock);
    struct pfg_slot *slot = pfg_find(user);
    if (slot == NULL) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    *owned = *slot;
    if (slot->state != PFG_SLOT_LIVE) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    slot->state = PFG_SLOT_CLAIMED;
    owned->state = PFG_SLOT_CLAIMED;
    pthread_mutex_unlock(&g_lock);
    return 1;
}

static int pfg_restore_claim(void *user)
{
    int restored = 0;
    pthread_mutex_lock(&g_lock);
    struct pfg_slot *slot = pfg_find(user);
    if (slot != NULL && slot->state == PFG_SLOT_CLAIMED) {
        slot->state = PFG_SLOT_LIVE;
        restored = 1;
    }
    pthread_mutex_unlock(&g_lock);
    return restored;
}

static int pfg_finish_claim(void *user, uint32_t *state_out)
{
    int released = 0;
    *state_out = 0;
    pthread_mutex_lock(&g_lock);
    struct pfg_slot *slot = pfg_find(user);
    if (slot != NULL) {
        *state_out = slot->state;
        if (pfg_quarantine_claimed(slot))
            released = 1;
    }
    pthread_mutex_unlock(&g_lock);
    if (released)
        PFG_INC(guard_free);
    return released;
}

static void pfg_ownership_conflict(void *user, void *ra, uint32_t state)
{
    PFG_INC(ownership_conflicts);
    pfg_record(PFG_OP_OWNERSHIP_CONFLICT, user, NULL, ra, NULL, 0, state);
    pfg_fatal("ownership-conflict", user, ra, state, 0);
}

/* Resource exhaustion must not turn a previously guarded growth into NULL for callers
 * that do not check realloc results. Transition to a correctly aligned glibc block while
 * the old mmap remains CLAIMED, then quarantine the old mapping. The returned pointer is
 * foreign to our table and will use the real deallocator later. */
static void *pfg_owned_to_glibc(void *old, const struct pfg_slot *owned,
                                size_t new_size, size_t align, void *ra)
{
    size_t effective_align = align < sizeof(void *) ? sizeof(void *) : align;
    void *fallback = NULL;
    if (effective_align == 0 || (effective_align & (effective_align - 1)) != 0 ||
        posix_memalign(&fallback, effective_align, new_size) != 0) {
        if (!pfg_restore_claim(old))
            pfg_ownership_conflict(old, ra, PFG_SLOT_CLAIMED);
        return NULL;
    }
    memcpy(fallback, old, owned->user_size < new_size ? owned->user_size : new_size);
    uint32_t state;
    if (!pfg_finish_claim(old, &state)) {
        free(fallback);
        pfg_ownership_conflict(old, ra, state);
        return NULL;
    }
    PFG_SUB(guard_live, 1);
    PFG_SUB(bytes_live, owned->user_size);
    pfg_record(PFG_OP_GUARD_FREE, old, (void *)(uintptr_t)owned->base, ra, fallback,
               new_size, owned->user_size);
    return fallback;
}

/* ------------------------------------------------------------------ policy */

static int pfg_caller_allowed(void *ra)
{
    uint64_t ra_key = (uint64_t)(uintptr_t)ra;
    uint32_t idx = (uint32_t)((ra_key >> 4) & (PFG_RA_CACHE - 1));
    pthread_mutex_lock(&g_ra_lock);
    struct pfg_ra_cache_entry *entry = &g_ra_cache[idx];
    if (entry->valid && entry->ra == ra_key) {
        int allow = (int)entry->allow;
        pthread_mutex_unlock(&g_ra_lock);
        PFG_INC(ra_cache_hit);
        return allow;
    }
    PFG_INC(ra_cache_miss);

    int allow = 0;
    Dl_info info;
    if (dladdr(ra, &info) != 0 && info.dli_sname != NULL) {
        for (int i = 0; i < g_ncallers; i++) {
            if (strcmp(info.dli_sname, g_callers[i]) == 0) {
                allow = 1;
                /* `whitelist_symbols` is only how many names were configured; this
                 * bitmask is the deployment check: it says which of them the loaded
                 * library actually calls from. A configured-but-never-matched name
                 * (renamed or stripped after a game update) leaves its bit at 0. */
                __atomic_or_fetch(&mdc_pfguard_counters.allowlist_matched,
                                  1ULL << (i & 63), __ATOMIC_RELAXED);
                break;
            }
        }
    }
    entry->ra = ra_key;
    entry->allow = (uint32_t)allow;
    entry->valid = 1;
    pthread_mutex_unlock(&g_ra_lock);
    return allow;
}

static int pfg_should_guard(void *ra, size_t size, size_t align)
{
    if (g_mode == 0)
        return 0;
    if (align == 0 || (align & (align - 1)) != 0) {
        PFG_INC(skip_align);
        return 0;
    }
    if (size > g_max_size) {
        PFG_INC(skip_size);
        return 0;
    }
    if (__atomic_load_n(&mdc_pfguard_counters.guard_live, __ATOMIC_RELAXED) >= g_max_blocks) {
        PFG_INC(skip_capacity);
        return 0;
    }
    if (g_mode == 2)
        return 1;
    if (!pfg_caller_allowed(ra)) {
        PFG_INC(skip_not_allowlisted);
        return 0;
    }
    return 1;
}

/* ------------------------------------------------------------------ interposed */

void *pfg_reallocate_aligned(void *old, size_t new_size, size_t align)
    __asm__("_Z18reallocate_alignedPvmm");
void pfg_deallocate_aligned(void *p) __asm__("_Z18deallocate_alignedPv");

void *pfg_reallocate_aligned(void *old, size_t new_size, size_t align)
{
    pthread_once(&g_resolve_once, pfg_resolve);
    void *ra = __builtin_return_address(0);

    struct pfg_slot owned;
    int claim = old != NULL ? pfg_claim(old, &owned) : 0;
    if (claim < 0) {
        pfg_ownership_conflict(old, ra, owned.state);
        return NULL;
    }
    int is_ours = claim > 0;

    if (is_ours && !pfg_canary_ok(&owned)) {
        PFG_INC(canary_violations);
        pfg_record(PFG_OP_CANARY, old, (void *)(uintptr_t)owned.base, ra, NULL,
                   owned.user_size, owned.map_len);
        pfg_fatal("canary-overflow-on-realloc", old, ra, owned.user_size, new_size);
    }

    if (new_size == 0) {
        if (is_ours) {
            uint32_t state;
            if (!pfg_finish_claim(old, &state)) {
                pfg_ownership_conflict(old, ra, state);
                return NULL;
            }
            PFG_SUB(guard_live, 1);
            PFG_SUB(bytes_live, owned.user_size);
            pfg_record(PFG_OP_GUARD_FREE, old, (void *)(uintptr_t)owned.base, ra, NULL,
                       0, owned.user_size);
            return NULL;
        }
        return pfg_delegate_realloc(old, 0, align, ra);
    }

    /* MAXSIZE is an admission limit for foreign/new blocks. Once a block is guarded,
     * preserving ownership is mandatory; pfg_map supports its requested alignment and
     * overflow-checks the full mapping arithmetic. */
    if (!is_ours && !pfg_should_guard(ra, new_size, align))
        return pfg_delegate_realloc(old, new_size, align, ra);

    size_t old_size = 0;
    if (is_ours)
        old_size = owned.user_size;
    else if (old != NULL)
        old_size = malloc_usable_size(old);
    if (old_size > new_size) {
        PFG_INC(shrink_anomalies);
        if (is_ours) {
            PFG_INC(owned_shrinks);
            pfg_record(PFG_OP_OWNED_SHRINK, old, (void *)(uintptr_t)owned.base, ra,
                       old, new_size, old_size);
        } else {
            PFG_INC(foreign_usable_gt_request);
            pfg_record(PFG_OP_FOREIGN_USABLE_GT_REQUEST, old, NULL, ra, old,
                       new_size, old_size);
        }
    }

    size_t map_len = 0;
    size_t data_len = 0;
    void *base = NULL;
#ifdef PFG_TESTING
    void *user = (is_ours && g_test_fail_owned_map)
        ? NULL : pfg_map(new_size, align, &map_len, &data_len, &base);
#else
    void *user = pfg_map(new_size, align, &map_len, &data_len, &base);
#endif
    uint64_t reserved_live = 0;
    int inserted = 0;
    int live_reserved = 0;

    if (user != NULL) {
        struct pfg_slot entry = {
            .user = (uint64_t)(uintptr_t)user,
            .base = (uint64_t)(uintptr_t)base,
            .map_len = map_len,
            .data_len = data_len,
            .user_size = new_size,
            .ra = (uint64_t)(uintptr_t)ra,
            .tid = pfg_tid(),
        };
        int capacity_rejected = 0;
        pthread_mutex_lock(&g_lock);
        if (!is_ours &&
            __atomic_load_n(&mdc_pfguard_counters.guard_live, __ATOMIC_RELAXED) >= g_max_blocks) {
            PFG_INC(skip_capacity);
            capacity_rejected = 1;
        } else {
            inserted = pfg_insert(&entry);
            if (inserted && !is_ours) {
                /* Reservation and decision share g_lock: MAXBLOCKS is a hard cap for
                 * new guarded blocks even when many workers race the pre-check. */
                reserved_live = PFG_ADD(guard_live, 1);
                live_reserved = 1;
            }
        }
        pthread_mutex_unlock(&g_lock);
        if (!inserted) {
            if (!capacity_rejected) {
                PFG_INC(skip_table_full);
                pfg_record(PFG_OP_TABLE_FULL, user, base, ra, old, new_size, old_size);
            }
            munmap(base, map_len);
            user = NULL;
        }
    } else {
        PFG_INC(mmap_failures);
        pfg_record(PFG_OP_MMAP_FAIL, NULL, NULL, ra, old, new_size, old_size);
    }

    if (user == NULL) {
        if (is_ours)
            return pfg_owned_to_glibc(old, &owned, new_size, align, ra);
        return pfg_delegate_realloc(old, new_size, align, ra);
    }

    if (old_size > 0)
        memcpy(user, old, old_size < new_size ? old_size : new_size);

    if (is_ours) {
        uint32_t state;
        if (!pfg_finish_claim(old, &state)) {
            pfg_ownership_conflict(old, ra, state);
            return NULL;
        }
    } else if (old != NULL) {
        free(old);
    }
    PFG_INC(guard_alloc);
    uint64_t live = live_reserved
        ? reserved_live
        : __atomic_load_n(&mdc_pfguard_counters.guard_live, __ATOMIC_RELAXED);
    if (is_ours) {
        if (new_size >= old_size)
            PFG_ADD(bytes_live, new_size - old_size);
        else
            PFG_SUB(bytes_live, old_size - new_size);
    } else {
        PFG_ADD(bytes_live, new_size);
    }
    PFG_ADD(pages_mapped, map_len / PFG_PAGE);
    uint64_t peak = __atomic_load_n(&mdc_pfguard_counters.guard_peak, __ATOMIC_RELAXED);
    while (live > peak &&
           !__atomic_compare_exchange_n(&mdc_pfguard_counters.guard_peak, &peak, live, 1,
                                        __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
    }
    pfg_record(PFG_OP_GUARD_ALLOC, user, base, ra, old, new_size, old_size);
    return user;
}

void pfg_deallocate_aligned(void *p)
{
    pthread_once(&g_resolve_once, pfg_resolve);
    void *ra = __builtin_return_address(0);

    if (p == NULL) {
        pfg_delegate_free(NULL, ra);
        return;
    }

    struct pfg_slot owned;
    int claim = pfg_claim(p, &owned);
    if (claim < 0) {
        pfg_ownership_conflict(p, ra, owned.state);
        return;
    }
    if (claim == 0) {
        pfg_delegate_free(p, ra);
        return;
    }

    if (!pfg_canary_ok(&owned)) {
        PFG_INC(canary_violations);
        pfg_record(PFG_OP_CANARY, p, (void *)(uintptr_t)owned.base, ra, NULL,
                   owned.user_size, owned.map_len);
        pfg_fatal("canary-overflow-on-free", p, ra, owned.user_size, owned.map_len);
    }

    uint32_t state;
    if (!pfg_finish_claim(p, &state)) {
        pfg_ownership_conflict(p, ra, state);
        return;
    }
    PFG_SUB(guard_live, 1);
    PFG_SUB(bytes_live, owned.user_size);
    pfg_record(PFG_OP_GUARD_FREE, p, (void *)(uintptr_t)owned.base, ra, NULL,
               0, owned.user_size);
}

/* ------------------------------------------------------------------ lifecycle */

static size_t pfg_env_size(const char *name, size_t fallback)
{
    const char *value = getenv(name);
    if (value == NULL || *value == '\0')
        return fallback;
    char *end = NULL;
    unsigned long long parsed = strtoull(value, &end, 0);
    if (end == value)
        return fallback;
    return (size_t)parsed;
}

static void pfg_load_callers(void)
{
    const char *value = getenv("MDC_PFGUARD_CALLERS");
    if (value == NULL || *value == '\0') {
        for (size_t i = 0; i < sizeof(PFG_DEFAULT_CALLERS) / sizeof(PFG_DEFAULT_CALLERS[0]); i++)
            g_callers[g_ncallers++] = PFG_DEFAULT_CALLERS[i];
        return;
    }
    snprintf(g_callers_env, sizeof(g_callers_env), "%s", value);
    char *cursor = g_callers_env;
    while (cursor != NULL && *cursor != '\0' && g_ncallers < PFG_MAX_CALLERS) {
        char *comma = strchr(cursor, ',');
        if (comma != NULL)
            *comma = '\0';
        if (*cursor != '\0')
            g_callers[g_ncallers++] = cursor;
        cursor = comma != NULL ? comma + 1 : NULL;
    }
}

__attribute__((constructor)) static void pfg_init(void)
{
    const char *mode = getenv("MDC_PFGUARD");
    if (mode != NULL && *mode != '\0') {
        if (strcmp(mode, "0") == 0 || strcmp(mode, "off") == 0)
            g_mode = 0;
        else if (strcmp(mode, "2") == 0 || strcmp(mode, "all") == 0)
            g_mode = 2;
        else
            g_mode = 1;
    }
    /* The kill switch must win: a stale MDC_PFGUARD_ALL=1 in the environment used to
     * silently re-enable full guarding after an operator set MDC_PFGUARD=0. */
    const char *all = getenv("MDC_PFGUARD_ALL");
    if (g_mode != 0 && all != NULL && strcmp(all, "1") == 0)
        g_mode = 2;

    g_trace_delegated = (int)pfg_env_size("MDC_PFGUARD_TRACE_DELEGATED", 0);
    g_max_size = pfg_env_size("MDC_PFGUARD_MAXSIZE", 64 * 1024);
    g_canary_window = pfg_env_size("MDC_PFGUARD_CANARY", PFG_CANARY_WINDOW);
    g_max_blocks = pfg_env_size("MDC_PFGUARD_MAXBLOCKS", 4096);
#ifdef PFG_TESTING
    g_test_fail_owned_map = (int)pfg_env_size("MDC_PFGUARD_TEST_FAIL_OWNED_MAP", 0);
#endif
    uint32_t quar = (uint32_t)pfg_env_size("MDC_PFGUARD_QUARANTINE", 4096);
    if (quar == 0)
        quar = 1;
    else if (quar > PFG_QUAR_MAX)
        quar = PFG_QUAR_MAX;
    g_quar_cap = quar;
    pfg_load_callers();

    mdc_pfguard_ring.magic = PFG_RING_MAGIC;
    mdc_pfguard_ring.version = PFG_LAYOUT_VERSION;
    mdc_pfguard_ring.entry_size = sizeof(struct pfg_event);
    mdc_pfguard_ring.capacity = PFG_RING_CAP;
    mdc_pfguard_counters.magic = PFG_COUNTER_MAGIC;
    mdc_pfguard_counters.version = PFG_LAYOUT_VERSION;
    mdc_pfguard_counters.mode = (uint64_t)g_mode;
    mdc_pfguard_counters.whitelist_symbols = (uint64_t)g_ncallers;

    char buf[512];
    int n = snprintf(buf, sizeof(buf),
                     "[mdc-pfguard] v%u mode=%d callers=%d maxsize=%zu maxblocks=%llu"
                     " quarantine=%u canary=%zu ring=%u buckets=%u nodes=%u"
                     " (guard active; detected corruption always aborts; config read once at startup)\n",
                     PFG_LAYOUT_VERSION, g_mode, g_ncallers, g_max_size,
                     (unsigned long long)g_max_blocks, g_quar_cap, g_canary_window,
                     PFG_RING_CAP, PFG_BUCKETS, PFG_NODES);
    pfg_write(STDERR_FILENO, buf, n);
}

/* Test/debug helper; never called on the hot path. */
void mdc_pfguard_dump(int fd)
{
    char buf[1024];
    const struct pfg_counters *c = &mdc_pfguard_counters;
    int n = snprintf(buf, sizeof(buf),
                     "mode=%llu callers=%llu guard_alloc=%llu guard_free=%llu guard_live=%llu"
                     " guard_peak=%llu bytes_live=%llu pages_mapped=%llu quarantined=%llu"
                     " quar_unmapped=%llu delegate_realloc=%llu delegate_free=%llu"
                     " owned_shrink=%llu foreign_rounding=%llu"
                     " skip_caller=%llu skip_align=%llu skip_size=%llu skip_cap=%llu"
                     " skip_table=%llu canary=%llu shrink=%llu mmap_fail=%llu"
                     " missing_real=%llu ra_hit=%llu ra_miss=%llu quar_fail=%llu"
                     " nodes_used=%llu matched=0x%llx ownership_conflicts=%llu"
                     " madvise_fail=%llu ring_head=%llu\n",
                     (unsigned long long)c->mode, (unsigned long long)c->whitelist_symbols,
                     (unsigned long long)c->guard_alloc, (unsigned long long)c->guard_free,
                     (unsigned long long)c->guard_live, (unsigned long long)c->guard_peak,
                     (unsigned long long)c->bytes_live, (unsigned long long)c->pages_mapped,
                     (unsigned long long)c->quarantined, (unsigned long long)c->quarantine_unmapped,
                     (unsigned long long)c->delegate_realloc, (unsigned long long)c->delegate_free,
                     (unsigned long long)c->owned_shrinks,
                     (unsigned long long)c->foreign_usable_gt_request,
                     (unsigned long long)c->skip_not_allowlisted, (unsigned long long)c->skip_align,
                     (unsigned long long)c->skip_size, (unsigned long long)c->skip_capacity,
                     (unsigned long long)c->skip_table_full, (unsigned long long)c->canary_violations,
                     (unsigned long long)c->shrink_anomalies, (unsigned long long)c->mmap_failures,
                     (unsigned long long)c->real_symbol_missing, (unsigned long long)c->ra_cache_hit,
                     (unsigned long long)c->ra_cache_miss,
                     (unsigned long long)c->quarantine_failures,
                     (unsigned long long)c->nodes_used,
                     (unsigned long long)c->allowlist_matched,
                     (unsigned long long)c->ownership_conflicts,
                     (unsigned long long)c->madvise_failures,
                     (unsigned long long)mdc_pfguard_ring.head);
    pfg_write(fd, buf, n);
}
