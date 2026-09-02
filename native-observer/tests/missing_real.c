#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>

int main(void)
{
    typedef void *(*realloc_fn)(void *, size_t, size_t);
    realloc_fn fn = (realloc_fn)dlsym(RTLD_DEFAULT, "_Z18reallocate_alignedPvmm");
    if (fn == NULL) {
        puts("FAIL missing-real: shim symbol not found");
        return 2;
    }
    puts("armed missing real symbol");
    fflush(stdout);
    (void)fn(NULL, 32, 8);
    puts("FAIL missing-real: delegate did not fail closed");
    return 1;
}
