/*
 * libfakepathfind.so — stand-in for libPZPathFind64.so in the guard's synthetic tests.
 *
 * Mirrors the two things that matter for interposition fidelity:
 *   1. it *defines* the two aligned-block symbols with vanilla semantics
 *      (aligned_alloc -> memcpy(malloc_usable_size(old)) -> free), so the shim has a
 *      real implementation to delegate to via dlsym();
 *   2. its callers are exported under the same mangled names the real library uses,
 *      so the shim's dladdr()-based allowlist is exercised for real.
 *
 * Calls to the aligned-block helpers must go through the PLT (verified by
 * tests/run-tests.sh), exactly like the shipped library does.
 */

#define _GNU_SOURCE

#include <malloc.h>
#include <stddef.h>
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
FAKE_CALLER void *fake_merge_grow(void *old, size_t new_size) __asm__("_ZN14VehicleCluster5mergeEPS_");
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

FAKE_CALLER void *fake_merge_grow(void *old, size_t new_size)
{
    void *block = fake_reallocate_aligned(old, new_size, 8);
    fake_sink = block;
    return block;
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
