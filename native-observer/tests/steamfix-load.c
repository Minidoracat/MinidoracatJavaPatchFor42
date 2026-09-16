/* Loads an arbitrary shared object under LD_AUDIT. Exercises the repair's
 * refusal path: an image it does not recognise must leave the process alive,
 * whatever load bias the loader picked for it.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <sys/mman.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    /* Force the synthetic prelinked image away from its preferred address.
     * Otherwise l_addr == 0 is rejected before the memory-read guard runs.
     */
    void *reserved = mmap((void *)0x40000000, 4096, PROT_NONE,
                         MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE, -1, 0);
    if (reserved == MAP_FAILED) { perror("reserve"); return 4; }
    void *lib = dlopen(argv[1], RTLD_NOW|RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 3; }
    struct link_map *map;
    if (dlinfo(lib, RTLD_DI_LINKMAP, &map) || !map->l_addr) {
        fprintf(stderr, "test requires a nonzero load bias\n");
        return 5;
    }
    dlclose(lib);
    munmap(reserved, 4096);
    return 0;
}
