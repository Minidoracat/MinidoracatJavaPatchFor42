/*
 * Synthetic cases for libmdcpfguard.so. Each case is a separate child process so the
 * harness can assert on the delivered signal. Run through tests/run-tests.sh.
 *
 * The SIGSEGV/SIGBUS handler here exists only to prove the fault lands on the writing
 * instruction (it prints si_addr and the faulting RIP). The shim itself installs no
 * handler in production.
 */

#define _GNU_SOURCE

#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

void *cluster_grow(void *old, size_t new_size)
    __asm__("_ZN13PolygonalMap220createVehicleClusterEP11VehicleRectR9ArrayListIS1_ERS2_IP14VehicleClusterE");
void *round_grow(void *old, size_t new_size) __asm__("_ZN13PolygonalMap221createVehicleClustersEv");
void *unrelated_grow(void *old, size_t new_size) __asm__("_ZN8HLAStar48findPathEv");
/* VehicleCluster double (layout of 42.20.4: 0x18 bytes, count at +0x0c, array at +0x10). */
struct cluster {
    int32_t level;
    int32_t pad;
    int32_t capacity;
    int32_t count;
    void **array;
};
struct cluster *cluster_alloc(void) __asm__("_ZN14VehicleCluster5allocEv");
void cluster_release(struct cluster *c) __asm__("_ZN14VehicleCluster7releaseEv");
void cluster_merge(struct cluster *dst, struct cluster *src) __asm__("_ZN14VehicleCluster5mergeEPS_");
size_t fake_cluster_pool_size(void);
size_t fake_cluster_fresh(void);
void *split_grow(void *old, size_t new_size)
    __asm__("_ZN15VisibilityGraph8trySplitEP4EdgeP11VehicleRectR9ArrayListIiE");
/* Calling the helper directly: the caller symbol here is not on the allowlist either, so
 * only size/alignment policy decides. Used by the align>page case. */
void *reallocate_aligned_direct(void *old, size_t new_size, size_t align)
    __asm__("_Z18reallocate_alignedPvmm");
void fake_release(void *p);

extern void mdc_pfguard_dump(int fd) __attribute__((weak));

static void report_counters(void)
{
    if (mdc_pfguard_dump != NULL) {
        fputs("counters: ", stdout);
        fflush(stdout);
        mdc_pfguard_dump(STDOUT_FILENO);
    } else {
        puts("counters: <shim not loaded>");
    }
}

static volatile sig_atomic_t fault_armed;
static volatile uintptr_t expected_fault_address;

static void fault_handler(int sig, siginfo_t *info, void *raw)
{
    ucontext_t *uc = (ucontext_t *)raw;
    int exact = fault_armed && (uintptr_t)info->si_addr == expected_fault_address;
    char buf[256];
    int n = snprintf(buf, sizeof(buf), "FAULT_%s sig=%d si_addr=%p expected=%p rip=%p\n",
                     exact ? "OK" : "BAD", sig, info->si_addr,
                     (void *)expected_fault_address,
                     (void *)(uintptr_t)uc->uc_mcontext.gregs[REG_RIP]);
    if (n > 0 && write(STDOUT_FILENO, buf, (size_t)n) < 0)
        _exit(70);
    _exit(exact ? 42 : 43);
}

static void install_handler(void)
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = fault_handler;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
}
/* Keeps the compiler from folding the deliberate wild store into setup code. */
__attribute__((noinline)) static void store_qword(volatile uint64_t *where, uint64_t value)
{
    *where = value;
}

static int case_clean(int rounds)
{
    for (int round = 0; round < rounds; round++) {
        void *block = NULL;
        int previous_capacity = 0;
        for (int capacity = 4; capacity <= 64; capacity *= 2) {
            block = cluster_grow(block, (size_t)capacity * 8);
            if (block == NULL) {
                puts("FAIL clean: allocation returned NULL");
                return 1;
            }
            uint64_t *slots = block;
            for (int i = 0; i < previous_capacity; i++) {
                if (slots[i] != 0x1000ULL + (uint64_t)i) {
                    puts("FAIL clean: payload was not preserved across growth");
                    return 1;
                }
            }
            for (int i = previous_capacity; i < capacity; i++)
                slots[i] = 0x1000ULL + (uint64_t)i;
            previous_capacity = capacity;
        }
        void *round_block = round_grow(NULL, 256);
        memset(round_block, 0x5A, 256);
        fake_release(round_block);
        fake_release(block);
    }
    report_counters();
    puts("OK clean");
    return 0;
}

static int case_underflow(void)
{
    install_handler();
    uint64_t *block = cluster_grow(NULL, 32);
    memset(block, 0, 32);
    puts("writing one qword below the block (the 2026-08-31 shape)");
    fflush(stdout);
    expected_fault_address = (uintptr_t)(block - 1);
    fault_armed = 1;
    store_qword(block - 1, 0xdeadbeefdeadbeefULL);
    puts("FAIL underflow: the wild store did not fault");
    return 1;
}

static int case_uaf(void)
{
    install_handler();
    uint64_t *block = cluster_grow(NULL, 32);
    memset(block, 0, 32);
    fake_release(block);
    puts("writing into the freed block");
    fflush(stdout);
    expected_fault_address = (uintptr_t)block;
    fault_armed = 1;
    store_qword(block, 0x41414141ULL);
    puts("FAIL uaf: the write after free did not fault");
    return 1;
}

static int case_overflow_page(void)
{
    install_handler();
    uint64_t *block = cluster_grow(NULL, 32);
    memset(block, 0, 32);
    puts("writing past the mapped data pages");
    fflush(stdout);
    expected_fault_address = (uintptr_t)((char *)block + 4096);
    fault_armed = 1;
    store_qword((uint64_t *)((char *)block + 4096), 0x42424242ULL);
    puts("FAIL overflow-page: the out-of-page store did not fault");
    return 1;
}

static int case_overflow_slack(void)
{
    uint64_t *block = cluster_grow(NULL, 32);
    memset(block, 0, 32);
    /* Inside the page, past the request: no hardware trap, must be caught by the canary. */
    store_qword(block + 5, 0x43434343ULL);
    puts("slack corrupted; freeing should trip the canary");
    fflush(stdout);
    fake_release(block);
    puts("FAIL overflow-slack: canary violation was not detected");
    return 1;
}

static int case_shrink(void)
{
    void *block = cluster_grow(NULL, 4096);
    memset(block, 0x11, 4096);
    puts("requesting a smaller size (vanilla would memcpy the old usable size)");
    fflush(stdout);
    void *shrunk = cluster_grow(block, 32);
    if (shrunk == NULL) {
        puts("FAIL shrink: allocation returned NULL");
        return 1;
    }
    report_counters();
    puts("OK shrink-survived");
    fake_release(shrunk);
    return 0;
}

static int case_foreign_rounding(void)
{
    void *plain = unrelated_grow(NULL, 4);
    if (plain == NULL)
        return 1;
    memset(plain, 0x66, 4);
    void *guarded = cluster_grow(plain, 8);
    const unsigned char expected[4] = {0x66, 0x66, 0x66, 0x66};
    if (guarded == NULL || memcmp(guarded, expected, sizeof(expected)) != 0)
        return 1;
    fake_release(guarded);
    report_counters();
    puts("OK foreign-rounding");
    return 0;
}

static int case_delegate(void)
{
    install_handler();
    uint64_t *block = unrelated_grow(NULL, 32);
    memset(block, 0, 32);
    /* A non-allowlisted caller must stay on the glibc heap: this store must not fault,
     * which is also proof that the guard is genuinely selective. */
    uint64_t saved = 0;
    if (((uintptr_t)block & 4095u) == 0) {
        /* In ALL mode the same deliberately harmless store becomes the armed
         * underflow test; arm before any access below user. */
        expected_fault_address = (uintptr_t)(block - 1);
        fault_armed = 1;
    } else {
        memcpy(&saved, block - 1, sizeof(saved));
    }
    store_qword(block - 1, saved);
    report_counters();
    puts("OK delegate");
    fake_release(block);
    return 0;
}

static int case_ringdump(void)
{
    /* Exceed the 16,384-entry ring so live/core decoding exercises wrap generation
     * checks rather than only pristine slots. The harness uses a tiny quarantine. */
    for (int i = 0; i < 9000; i++) {
        size_t size = 64 * (size_t)((i % 8) + 1);
        void *block = cluster_grow(NULL, size);
        memset(block, 0x77, size);
        fake_release(block);
    }
    printf("READY %d\n", (int)getpid());
    fflush(stdout);
    raise(SIGSTOP);
    puts("OK ringdump");
    return 0;
}

static int case_ringlive(void)
{
    printf("READY %d\n", (int)getpid());
    fflush(stdout);
    usleep(100000);
    for (int i = 0; i < 150000; i++) {
        void *block = cluster_grow(NULL, 32);
        ((uint64_t *)block)[0] = (uint64_t)i;
        fake_release(block);
    }
    puts("OK ringlive");
    return 0;
}

/* A guarded pointer must never be handed to the real reallocate_aligned (it would call
 * malloc_usable_size() on a non-glibc block). Guarded blocks are page-aligned; glibc
 * blocks from aligned_alloc(8, n) are not. With MAXBLOCKS=1 the capacity limit is already
 * reached, so a *new* allocation must be delegated while an *owned* one must stay guarded:
 * that difference is exactly the invariant. */
static int case_owned_stays_guarded(void)
{
    uint64_t *owned = cluster_grow(NULL, 32);
    if (((uintptr_t)owned & 4095u) != 0) {
        puts("FAIL owned-stays-guarded: first block was not guarded (test setup wrong)");
        return 1;
    }
    memset(owned, 0x21, 32);

    void *second = cluster_grow(NULL, 32);
    int second_guarded = ((uintptr_t)second & 4095u) == 0;

    uint64_t *regrown = cluster_grow(owned, 64);
    int owned_still_guarded = ((uintptr_t)regrown & 4095u) == 0;
    printf("second_guarded=%d owned_still_guarded=%d\n", second_guarded, owned_still_guarded);
    if (!owned_still_guarded) {
        puts("FAIL owned-stays-guarded: an owned block was delegated to the real function");
        return 1;
    }
    for (int i = 0; i < 4; i++) {
        if (regrown[i] != 0x2121212121212121ULL) {
            puts("FAIL owned-stays-guarded: payload lost across the guarded realloc");
            return 1;
        }
    }
    fake_release(regrown);
    fake_release(second);
    report_counters();
    puts("OK owned-stays-guarded");
    return 0;
}

static int case_zero_realloc(void)
{
    void *block = cluster_grow(NULL, 32);
    memset(block, 0x31, 32);
    void *result = cluster_grow(block, 0);
    if (result != NULL) {
        puts("FAIL zero-realloc: expected NULL");
        return 1;
    }
    report_counters();
    puts("OK zero-realloc");
    return 0;
}

static int case_bigalign(void)
{
    /* Invalid alignment is not guarded; the real helper normalizes it in the test
     * double, so this exercises skip_align without invoking UB. */
    void *block = reallocate_aligned_direct(NULL, 64, 3);
    if (block == NULL)
        return 1;
    fake_release(block);
    report_counters();
    puts("OK bigalign");
    return 0;
}

static int case_owned_policy(void)
{
    uint64_t *block = cluster_grow(NULL, 32);
    for (int i = 0; i < 4; i++)
        block[i] = 0x5050505050505050ULL + (uint64_t)i;
    uint64_t *grown = reallocate_aligned_direct(block, 128, 8192);
    if (grown == NULL || ((uintptr_t)grown & 8191u) != 0) {
        puts("FAIL owned-policy: growth did not preserve 8KiB alignment");
        return 1;
    }
    for (int i = 0; i < 4; i++) {
        if (grown[i] != 0x5050505050505050ULL + (uint64_t)i) {
            puts("FAIL owned-policy: payload not preserved");
            return 1;
        }
    }
    fake_release(grown);
    report_counters();
    puts("OK owned-policy");
    return 0;
}

static int case_owned_fallback(void)
{
    uint64_t *block = cluster_grow(NULL, 32);
    for (int i = 0; i < 4; i++)
        block[i] = 0x7171717171717171ULL + (uint64_t)i;
    uint64_t *fallback = cluster_grow(block, 64);
    if (fallback == NULL || ((uintptr_t)fallback & 7u) != 0)
        return 1;
    for (int i = 0; i < 4; i++) {
        if (fallback[i] != 0x7171717171717171ULL + (uint64_t)i)
            return 1;
    }
    fake_release(fallback);
    report_counters();
    puts("OK owned-fallback");
    return 0;
}

static int case_map_overflow(void)
{
    size_t requests[] = {SIZE_MAX, SIZE_MAX - (4096 - 1)};
    for (size_t i = 0; i < sizeof(requests) / sizeof(requests[0]); i++) {
        if (reallocate_aligned_direct(NULL, requests[i], 8) != NULL) {
            puts("FAIL map overflow unexpectedly allocated");
            return 1;
        }
    }
    report_counters();
    puts("OK map-overflow");
    return 0;
}

/* Selectivity without faulting: allowlisted and non-allowlisted callers in one process. */
static int case_mixed(void)
{
    for (int i = 0; i < 16; i++) {
        void *guarded = cluster_grow(NULL, 32);
        void *plain = unrelated_grow(NULL, 32);
        memset(guarded, 1, 32);
        memset(plain, 2, 32);
        printf("i=%d guarded_page_aligned=%d plain_page_aligned=%d\n", i,
               ((uintptr_t)guarded & 4095u) == 0, ((uintptr_t)plain & 4095u) == 0);
        fake_release(guarded);
        fake_release(plain);
    }
    report_counters();
    puts("OK mixed");
    return 0;
}

/* Gives `c` exactly one rect through the createVehicleCluster caller (allowlisted). */
static void cluster_put_rect(struct cluster *c, void *rect)
{
    if (c->capacity == 0) {
        c->array = cluster_grow(NULL, 4 * sizeof(void *));
        c->capacity = 4;
    }
    c->array[c->count++] = rect;
}

static int case_allowlist(void)
{
    uint64_t rect[2] = {0, 0};
    struct cluster *dst = cluster_alloc();
    struct cluster *src = cluster_alloc();
    cluster_put_rect(src, rect);
    cluster_merge(dst, src);                 /* grows dst->array from inside merge */
    void *blocks[] = {
        round_grow(NULL, 32),
        split_grow(NULL, 32),
        src->array,
        dst->array,
    };
    for (size_t i = 0; i < sizeof(blocks) / sizeof(blocks[0]); i++)
        fake_release(blocks[i]);
    report_counters();
    puts("OK allowlist");
    return 0;
}

/* merge-release: the absorbed cluster must come back through the pool exactly once. */
static int case_merge_release(int rounds)
{
    uint64_t rect[2] = {0, 0};
    struct cluster *dst = cluster_alloc();
    struct cluster *src = cluster_alloc();
    cluster_put_rect(src, rect);
    size_t before = fake_cluster_pool_size();
    for (int i = 0; i < rounds; i++)
        cluster_merge(dst, src);             /* round 2+: src is empty and already pooled */
    size_t after = fake_cluster_pool_size();
    struct cluster *again = cluster_alloc();
    printf("MERGE dst_count=%d src_count=%d backptr_ok=%d pool_delta=%zu lifo_returns_src=%d\n",
           dst->count, src->count, (void *)rect[0] == (void *)dst, after - before, again == src);
    if (dst->capacity)
        fake_release(dst->array);
    if (src->capacity)
        fake_release(src->array);
    report_counters();
    puts("OK merge-release");
    return 0;
}

/* A pooled cluster that alloc() hands out again is a fresh owner: merging it a second
 * time must release it a second time, not be mistaken for a double release. */
static int case_merge_reuse(void)
{
    uint64_t rect[2] = {0, 0};
    struct cluster *dst = cluster_alloc();
    struct cluster *src = cluster_alloc();
    cluster_put_rect(src, rect);
    cluster_merge(dst, src);
    struct cluster *again = cluster_alloc();    /* == src (LIFO); leaves the shim's set */
    cluster_put_rect(again, rect);
    cluster_merge(dst, again);
    printf("REUSE again_is_src=%d pool=%zu\n", again == src, fake_cluster_pool_size());
    if (dst->capacity)
        fake_release(dst->array);
    if (src->capacity)
        fake_release(src->array);
    report_counters();
    puts("OK merge-reuse");
    return 0;
}

/* src==dst is the one state vanilla could hand us where releasing would be wrong. */
static int case_merge_self(void)
{
    struct cluster *dst = cluster_alloc();
    cluster_merge(dst, dst);
    printf("SELF pool=%zu\n", fake_cluster_pool_size());
    report_counters();
    puts("OK merge-self");
    return 0;
}

/* Tracking-set churn: interleaved merges and allocs with a shadow model. A false
 * "already present" shows as merge_double_blocked>0; a false "absent" (double release)
 * shows as pool_size != releases - pool_hits. Deterministic LCG so failures replay. */
static int case_merge_churn(int rounds)
{
    enum { HELD_MAX = 3000 };
    static struct cluster *held[HELD_MAX];
    static uint64_t rects[HELD_MAX][2];
    int nheld = 0;
    uint64_t seed = 0x9E3779B97F4A7C15ULL;
    size_t releases = 0, allocs = 0;
    struct cluster *sink = cluster_alloc();
    allocs++;
    for (int i = 0; i < rounds; i++) {
        seed = seed * 6364136223846793005ULL + 1442695040888963407ULL;
        /* alternate 512-round phases: alloc-heavy (set drains) / merge-heavy (set fills) */
        unsigned alloc_odds = ((i >> 9) & 1) ? 1 : 4;
        int want_alloc = nheld < 16 || (nheld < HELD_MAX && (seed >> 33) % 5 < alloc_odds);
        if (want_alloc) {
            struct cluster *c = cluster_alloc();
            allocs++;
            held[nheld++] = c;
        } else {
            int pick = (int)((seed >> 40) % (uint64_t)nheld);
            struct cluster *src = held[pick];
            held[pick] = held[--nheld];
            if (src->capacity == 0) {
                src->array = malloc(sizeof(void *));
                src->capacity = 1;
            }
            src->array[0] = rects[pick];
            src->count = 1;
            cluster_merge(sink, src);
            releases++;
            sink->count = 0;                 /* keep sink's array from growing forever */
        }
    }
    size_t pool_hits = allocs - fake_cluster_fresh();
    printf("CHURN rounds=%d releases=%zu allocs=%zu pool=%zu expected_pool=%zu\n",
           rounds, releases, allocs, fake_cluster_pool_size(), releases - pool_hits);
    if (sink->capacity)
        fake_release(sink->array);
    report_counters();
    puts("OK merge-churn");
    return 0;
}

/* Set saturation must skip (counted), never release, and recover once alloc drains it.
 * Sources are allocated up front: merging straight after alloc would just recycle the
 * same pooled object (LIFO) and never fill the set. */
static int case_merge_set_full(int extra)
{
    enum { SET_HALF = 2048 };
    int total = SET_HALF + extra;
    struct cluster **srcs = calloc((size_t)total, sizeof(*srcs));
    struct cluster *sink = cluster_alloc();
    uint64_t rect[2] = {0, 0};
    for (int i = 0; i < total; i++) {
        srcs[i] = cluster_alloc();
        srcs[i]->array = malloc(sizeof(void *));
        srcs[i]->capacity = 1;
    }
    for (int i = 0; i < total; i++) {
        srcs[i]->array[0] = rect;
        srcs[i]->count = 1;
        cluster_merge(sink, srcs[i]);
        sink->count = 0;
    }
    size_t pooled = fake_cluster_pool_size();
    for (size_t i = 0; i < pooled; i++)
        cluster_alloc();                     /* drain: every entry leaves the set */
    struct cluster *src = cluster_alloc();   /* fresh object (pool is empty) */
    src->array = malloc(sizeof(void *));
    src->capacity = 1;
    src->array[0] = rect;
    src->count = 1;
    cluster_merge(sink, src);
    printf("SETFULL total=%d pooled=%zu pool_after=%zu\n", total, pooled, fake_cluster_pool_size());
    if (sink->capacity)
        fake_release(sink->array);
    report_counters();
    puts("OK merge-set-full");
    return 0;
}

struct thread_args {
    int loops;
    void *shared;
    pthread_barrier_t *barrier;
};

static void *thread_stress_worker(void *raw)
{
    struct thread_args *args = raw;
    uint64_t rect[2] = {0, 0};
    for (int i = 0; i < args->loops; i++) {
        void *a = cluster_grow(NULL, 32);
        void *b = round_grow(NULL, 64);
        void *d = split_grow(NULL, 128);
        void *plain = unrelated_grow(NULL, 32);
        /* merge-release path under contention. Vanilla semantics: a cluster's array
         * rides along with the pooled object, so nothing touches `src` after merge (the
         * shim pooled it; another thread may already own it) and dst's array stays put. */
        struct cluster *dst = cluster_alloc();
        struct cluster *src = cluster_alloc();
        cluster_put_rect(src, rect);
        cluster_merge(dst, src);
        cluster_release(dst);
        ((uint64_t *)a)[0] = (uint64_t)i;
        fake_release(a);
        fake_release(b);
        fake_release(d);
        fake_release(plain);
    }
    return NULL;
}

static int case_threads(int loops)
{
    enum { THREADS = 8 };
    pthread_t threads[THREADS];
    struct thread_args args = {.loops = loops};
    for (int i = 0; i < THREADS; i++) {
        if (pthread_create(&threads[i], NULL, thread_stress_worker, &args) != 0)
            return 1;
    }
    for (int i = 0; i < THREADS; i++)
        pthread_join(threads[i], NULL);
    report_counters();
    puts("OK threads");
    return 0;
}
static void *thread_capacity_worker(void *raw)
{
    pthread_barrier_t *barrier = raw;
    pthread_barrier_wait(barrier);
    void *block = cluster_grow(NULL, 32);
    pthread_barrier_wait(barrier);  /* all allocations remain live together */
    fake_release(block);
    return NULL;
}

static int case_capacity_race(void)
{
    enum { THREADS = 8 };
    pthread_t threads[THREADS];
    pthread_barrier_t barrier;
    pthread_barrier_init(&barrier, NULL, THREADS);
    for (int i = 0; i < THREADS; i++)
        pthread_create(&threads[i], NULL, thread_capacity_worker, &barrier);
    for (int i = 0; i < THREADS; i++)
        pthread_join(threads[i], NULL);
    report_counters();
    puts("OK capacity-race");
    return 0;
}


static void *thread_free_shared(void *raw)
{
    struct thread_args *args = raw;
    pthread_barrier_wait(args->barrier);
    fake_release(args->shared);
    return NULL;
}

static void *thread_realloc_shared(void *raw)
{
    struct thread_args *args = raw;
    pthread_barrier_wait(args->barrier);
    (void)cluster_grow(args->shared, 64);
    return NULL;
}

static int case_ownership_race(int free_vs_realloc)
{
    pthread_barrier_t barrier;
    pthread_t first, second;
    struct thread_args args = {
        .shared = cluster_grow(NULL, 32),
        .barrier = &barrier,
    };
    pthread_barrier_init(&barrier, NULL, 3);
    pthread_create(&first, NULL, thread_free_shared, &args);
    pthread_create(&second, NULL,
                   free_vs_realloc ? thread_realloc_shared : thread_free_shared, &args);
    puts(free_vs_realloc ? "armed free/realloc ownership race"
                         : "armed double-free ownership race");
    fflush(stdout);
    pthread_barrier_wait(&barrier);
    pthread_join(first, NULL);
    pthread_join(second, NULL);
    puts("FAIL ownership race was not detected");
    return 1;
}

/* Node recycling: the table must not degrade after many short-lived blocks. An
 * open-addressed table with tombstones would make every miss a full-table scan here. */
static int case_churn(int rounds)
{
    struct timespec start, stop;
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < rounds; i++) {
        void *block = cluster_grow(NULL, 32);
        ((uint64_t *)block)[0] = (uint64_t)i;
        fake_release(block);
        void *plain = unrelated_grow(NULL, 32);   /* a table miss on every iteration */
        fake_release(plain);
    }
    clock_gettime(CLOCK_MONOTONIC, &stop);
    double first = 0;
    (void)first;
    printf("CHURN rounds=%d total_ms=%.1f per_round_us=%.2f\n", rounds,
           (double)(stop.tv_sec - start.tv_sec) * 1e3 +
               (double)(stop.tv_nsec - start.tv_nsec) / 1e6,
           ((double)(stop.tv_sec - start.tv_sec) * 1e9 +
            (double)(stop.tv_nsec - start.tv_nsec)) / 1e3 / (double)rounds);
    report_counters();
    return 0;
}

static double elapsed_ns(struct timespec a, struct timespec b)
{
    return (double)(b.tv_sec - a.tv_sec) * 1e9 + (double)(b.tv_nsec - a.tv_nsec);
}

static int case_bench(int iterations)
{
    struct timespec start, stop;

    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < iterations; i++) {
        void *block = cluster_grow(NULL, 32);
        ((uint64_t *)block)[0] = (uint64_t)i;
        block = cluster_grow(block, 64);
        ((uint64_t *)block)[7] = (uint64_t)i;
        fake_release(block);
    }
    clock_gettime(CLOCK_MONOTONIC, &stop);
    double guarded = elapsed_ns(start, stop) / (double)iterations;

    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < iterations; i++) {
        void *block = unrelated_grow(NULL, 32);
        ((uint64_t *)block)[0] = (uint64_t)i;
        block = unrelated_grow(block, 64);
        ((uint64_t *)block)[7] = (uint64_t)i;
        fake_release(block);
    }
    clock_gettime(CLOCK_MONOTONIC, &stop);
    double delegated = elapsed_ns(start, stop) / (double)iterations;

    printf("BENCH iterations=%d allowlisted=%.0f ns/cycle delegated=%.0f ns/cycle ratio=%.1fx\n",
           iterations, guarded, delegated, delegated > 0 ? guarded / delegated : 0.0);
    report_counters();
    return 0;
}

int main(int argc, char **argv)
{
    const char *name = argc > 1 ? argv[1] : "clean";
    int number = argc > 2 ? atoi(argv[2]) : 0;

    if (strcmp(name, "clean") == 0)
        return case_clean(number > 0 ? number : 64);
    if (strcmp(name, "underflow") == 0)
        return case_underflow();
    if (strcmp(name, "uaf") == 0)
        return case_uaf();
    if (strcmp(name, "overflow-page") == 0)
        return case_overflow_page();
    if (strcmp(name, "overflow-slack") == 0)
        return case_overflow_slack();
    if (strcmp(name, "shrink") == 0)
        return case_shrink();
    if (strcmp(name, "foreign-rounding") == 0)
        return case_foreign_rounding();
    if (strcmp(name, "delegate") == 0)
        return case_delegate();
    if (strcmp(name, "owned-policy") == 0)
        return case_owned_policy();
    if (strcmp(name, "owned-fallback") == 0)
        return case_owned_fallback();
    if (strcmp(name, "map-overflow") == 0)
        return case_map_overflow();
    if (strcmp(name, "capacity-race") == 0)
        return case_capacity_race();
    if (strcmp(name, "ringdump") == 0)
        return case_ringdump();
    if (strcmp(name, "ringlive") == 0)
        return case_ringlive();
    if (strcmp(name, "bench") == 0)
        return case_bench(number > 0 ? number : 20000);
    if (strcmp(name, "owned-stays-guarded") == 0)
        return case_owned_stays_guarded();
    if (strcmp(name, "zero-realloc") == 0)
        return case_zero_realloc();
    if (strcmp(name, "bigalign") == 0)
        return case_bigalign();
    if (strcmp(name, "mixed") == 0)
        return case_mixed();
    if (strcmp(name, "churn") == 0)
        return case_churn(number > 0 ? number : 40000);
    if (strcmp(name, "allowlist") == 0)
        return case_allowlist();
    if (strcmp(name, "threads") == 0)
        return case_threads(number > 0 ? number : 2000);
    if (strcmp(name, "double-free") == 0)
        return case_ownership_race(0);
    if (strcmp(name, "free-realloc") == 0)
        return case_ownership_race(1);
    if (strcmp(name, "merge-release") == 0)
        return case_merge_release(number > 0 ? number : 1);
    if (strcmp(name, "merge-reuse") == 0)
        return case_merge_reuse();
    if (strcmp(name, "merge-self") == 0)
        return case_merge_self();
    if (strcmp(name, "merge-churn") == 0)
        return case_merge_churn(number > 0 ? number : 20000);
    if (strcmp(name, "merge-set-full") == 0)
        return case_merge_set_full(number > 0 ? number : 52);

    fprintf(stderr, "unknown case: %s\n", name);
    return 64;
}
