/*
 * libfakepathfind.so — stand-in for libPZPathFind64.so in the guard's synthetic tests.
 *
 * Mirrors the things that matter for interposition fidelity:
 *   1. it *defines* the two aligned-block symbols with vanilla semantics
 *      (aligned_alloc -> memcpy(malloc_usable_size(old)) -> free), so the shim has a
 *      real implementation to delegate to via dlsym();
 *   2. its callers are exported under the same mangled names the real library uses,
 *      so the shim's dladdr()-based allowlist is exercised for real;
 *   3. it defines VehicleCluster::alloc/release/merge with the vanilla pool semantics
 *      (0x18-byte object, LIFO free list, merge grows `this` through reallocate_aligned,
 *      rewrites rect backpointers, zeroes `other->count` and otherwise drops `other` on
 *      the floor) so the shim's merge-release wrapper has the real leak to fix.
 *
 * Calls to the aligned-block helpers must go through the PLT (verified by
 * tests/run-tests.sh), exactly like the shipped library does.
 */

#define _GNU_SOURCE

#include <malloc.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define FAKE_CALLER __attribute__((noinline, noclone, optimize("no-optimize-sibling-calls")))
void *fake_reallocate_aligned(void *old, size_t new_size, size_t align)
    __asm__("_Z18reallocate_alignedPvmm");
void fake_deallocate_aligned(void *p) __asm__("_Z18deallocate_alignedPv");

void *fake_reallocate_aligned(void *old, size_t new_size, size_t align)
{
    if (new_size == 0) {
        free(old);
        return NULL;
    }
    void *p = aligned_alloc(align < sizeof(void *) ? sizeof(void *) : align, new_size);
    if (p == NULL)
        return NULL;
    size_t usable = old != NULL ? malloc_usable_size(old) : 0;
    if (usable > 0)
        memcpy(p, old, usable);          /* the vanilla bug: no min(usable, new_size) */
    free(old);
    return p;
}

void fake_deallocate_aligned(void *p)
{
    free(p);
}

/* --- allowlisted callers (mangled names copied from the real 42.20.4 library) --- */

FAKE_CALLER void *fake_cluster_grow(void *old, size_t new_size)
    __asm__("_ZN13PolygonalMap220createVehicleClusterEP11VehicleRectR9ArrayListIS1_ERS2_IP14VehicleClusterE");
FAKE_CALLER void *fake_round_grow(void *old, size_t new_size)
    __asm__("_ZN13PolygonalMap221createVehicleClustersEv");
FAKE_CALLER void *fake_split_grow(void *old, size_t new_size)
    __asm__("_ZN15VisibilityGraph8trySplitEP4EdgeP11VehicleRectR9ArrayListIiE");

/* --- a caller that is deliberately NOT on the allowlist --- */

FAKE_CALLER void *fake_unrelated_grow(void *old, size_t new_size) __asm__("_ZN8HLAStar48findPathEv");

/* The real library never tail-calls these helpers: it stores the result into the
 * ArrayList and keeps going. A tail call here would `jmp` instead of `call`, so the
 * shim would see the *test program* as the caller and the allowlist would never match.
 * Thread-local storage keeps that anti-tail-call sink out of the stress test's data-race
 * surface. */
static _Thread_local void *fake_sink;

FAKE_CALLER void *fake_cluster_grow(void *old, size_t new_size)
{
    void *block = fake_reallocate_aligned(old, new_size, 8);
    fake_sink = block;
    return block;
}

FAKE_CALLER void *fake_round_grow(void *old, size_t new_size)
{
    void *block = fake_reallocate_aligned(old, new_size, 8);
    fake_sink = block;
    return block;
}

/* --- VehicleCluster pool double (layout and semantics of 42.20.4) --- */

struct fake_cluster {
    int32_t level;
    int32_t pad;
    int32_t capacity;
    int32_t count;
    void **array;                        /* VehicleRect*; rect+0 is the cluster backpointer */
};

struct fake_cluster *fake_cluster_alloc(void) __asm__("_ZN14VehicleCluster5allocEv");
void fake_cluster_release(struct fake_cluster *c) __asm__("_ZN14VehicleCluster7releaseEv");
FAKE_CALLER void fake_cluster_merge(struct fake_cluster *this, struct fake_cluster *other)
    __asm__("_ZN14VehicleCluster5mergeEPS_");

static pthread_mutex_t fake_pool_lock = PTHREAD_MUTEX_INITIALIZER;
static struct fake_cluster **fake_pool;
static size_t fake_pool_used, fake_pool_cap, fake_pool_fresh;

size_t fake_cluster_pool_size(void)
{
    pthread_mutex_lock(&fake_pool_lock);
    size_t n = fake_pool_used;
    pthread_mutex_unlock(&fake_pool_lock);
    return n;
}

size_t fake_cluster_fresh(void)
{
    pthread_mutex_lock(&fake_pool_lock);
    size_t n = fake_pool_fresh;
    pthread_mutex_unlock(&fake_pool_lock);
    return n;
}

/* ObjectPool::alloc: pop the most recently released object, else construct a new one;
 * init() only zeroes count — capacity/array ride along with the pooled object. */
struct fake_cluster *fake_cluster_alloc(void)
{
    pthread_mutex_lock(&fake_pool_lock);
    struct fake_cluster *c;
    if (fake_pool_used > 0) {
        c = fake_pool[--fake_pool_used];
    } else {
        c = calloc(1, sizeof(*c));
        fake_pool_fresh++;
    }
    pthread_mutex_unlock(&fake_pool_lock);
    if (c != NULL)
        c->count = 0;
    return c;
}

/* ObjectPool::release: bare push, nothing is freed. */
void fake_cluster_release(struct fake_cluster *c)
{
    pthread_mutex_lock(&fake_pool_lock);
    if (fake_pool_used == fake_pool_cap) {
        fake_pool_cap = fake_pool_cap ? fake_pool_cap * 2 : 64;
        fake_pool = realloc(fake_pool, fake_pool_cap * sizeof(*fake_pool));
        if (fake_pool == NULL)
            abort();
    }
    fake_pool[fake_pool_used++] = c;
    pthread_mutex_unlock(&fake_pool_lock);
}

/* VehicleCluster::merge, instruction for instruction in spirit: rewrite every moved rect's
 * backpointer, grow `this` (4, then doubling) through reallocate_aligned, append, then
 * `other->count = 0` — and *not* release `other`. That last omission is the leak. */
FAKE_CALLER void fake_cluster_merge(struct fake_cluster *this, struct fake_cluster *other)
{
    for (int32_t i = 0; i < other->count; i++) {
        void **rect = other->array[i];
        *rect = this;
        if (this->count == this->capacity) {
            int32_t cap = this->capacity == 0 ? 4 : this->capacity * 2;
            this->array = fake_reallocate_aligned(this->array, (size_t)cap * sizeof(void *), 8);
            this->capacity = cap;
            fake_sink = this->array;
        }
        this->array[this->count++] = rect;
    }
    other->count = 0;
}

FAKE_CALLER void *fake_split_grow(void *old, size_t new_size)
{
    void *block = fake_reallocate_aligned(old, new_size, 8);
    fake_sink = block;
    return block;
}

FAKE_CALLER void *fake_unrelated_grow(void *old, size_t new_size)
{
    void *block = fake_reallocate_aligned(old, new_size, 8);
    fake_sink = block;
    return block;
}

void fake_release(void *p)
{
    fake_deallocate_aligned(p);
    fake_sink = NULL;
}
