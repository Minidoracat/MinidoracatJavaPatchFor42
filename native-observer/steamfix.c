/* Cold-load repair of the pinned Steam PseudoTCP partial-ACK branch.
 * Never edits the disk image, interposes memcpy, or handles a signal.
 * glibc invokes la_objopen before relocations/constructors and before dlopen
 * publishes the object. Return 0: no per-symbol/per-packet audit callbacks.
 */
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <link.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define SITE 0x22862c8u
#define TEXT_START 0xd14090u
#define TEXT_SIZE 0x1c4a96fu
#define PAGE 4096u
static const unsigned char build_note[] = {
    4,0,0,0,20,0,0,0,3,0,0,0,'G','N','U',0,
    0xdf,0x98,0x28,0x70,0xf3,0x87,0x38,0x95,0x83,0xef,
    0x18,0x82,0xf4,0x2a,0x92,0x0b,0xa6,0x91,0x8a,0xf4
};
static const unsigned char original[] = {0x44,0x29,0xf8,0x41,0x89,0x44,0x24,0x14};
static const unsigned char continuation[] = {
    0x80,0xbd,0xa4,0x58,0x03,0x00,0x02,0x0f,0x86,0xf4,0x01,0x00,0x00
};
static const unsigned char transmit[] = {
    0x8b,0x70,0x10,0x31,0xd2,0x80,0x78,0x19,0x00,0x41,0x89,0xe8,
    0x0f,0x95,0xc2,0x48,0x89,0xdf,0x89,0xf1,0x2b,0x8b,0x70,0x58,
    0x02,0x00,0x01,0xd2,0x4c,0x01,0xf1,0xe8,0xfb,0xfc,0xff,0xff
};

static void log_message(const char *msg, size_t size) {
    int saved = errno;
    while (size) {
        ssize_t n = write(STDERR_FILENO, msg, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        msg += n; size -= (size_t)n;
    }
    errno = saved;
}
#define LOG(s) log_message("[mdc-steamfix] " s "\n", sizeof("[mdc-steamfix] " s "\n")-1)

unsigned int la_version(unsigned int version) {
    const char *mode = getenv("MDC_STEAMFIX");
    if (version < LAV_CURRENT || !mode || strcmp(mode, "1")) {
        LOG("DISARMED: unsupported audit ABI or repair not enabled");
        return 0;
    }
    return LAV_CURRENT;
}

/* Build ID plus exact RX segment geometry bounds every subsequent byte read.
 * The launcher separately pins the complete on-disk Steam and repair SHA256.
 * l_addr is a load bias, not a mapping: a legal DSO whose first PT_LOAD has a
 * nonzero p_vaddr leaves l_addr itself unmapped, so every address below is
 * proven readable first by letting the kernel copy it -- write(2) reports
 * EFAULT instead of raising SIGSEGV. No signal handler, no loader reentry:
 * pipe2/write/close are plain syscalls, and the sink needs no path.
 */
static int readable(int sink, uintptr_t address, size_t size) {
    const char *at = (const void *)address;
    while (size) {
        ssize_t n = write(sink, at, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;   /* EFAULT: not mapped, or not readable */
        at += n; size -= (size_t)n;
    }
    return 1;
}

static int verify(int sink, uintptr_t base) {
    const Elf64_Ehdr *eh = (const void *)base;
    if (!readable(sink, base, sizeof(*eh))) return 0;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) || eh->e_ident[EI_CLASS] != ELFCLASS64 ||
        eh->e_ident[EI_DATA] != ELFDATA2LSB || eh->e_machine != EM_X86_64 ||
        eh->e_type != ET_DYN || eh->e_phoff != 64 || eh->e_phnum != 11 ||
        eh->e_phentsize != sizeof(Elf64_Phdr)) return 0;
    const Elf64_Phdr *ph = (const void *)(base + eh->e_phoff);
    if (!readable(sink, (uintptr_t)ph, (size_t)eh->e_phnum * sizeof(*ph))) return 0;
    if (ph[2].p_type != PT_LOAD || ph[2].p_vaddr != 0 || ph[2].p_filesz < 0x2cc ||
        ph[3].p_type != PT_LOAD || ph[3].p_flags != (PF_R|PF_X) ||
        ph[3].p_vaddr != TEXT_START || ph[3].p_memsz != TEXT_SIZE ||
        ph[3].p_filesz != TEXT_SIZE || ph[3].p_offset != 0xd13090) return 0;
    return readable(sink, base + 0x2a8, sizeof(build_note)) &&
        !memcmp((void *)(base + 0x2a8), build_note, sizeof(build_note)) &&
        readable(sink, base + SITE, sizeof(original) + sizeof(continuation)) &&
        !memcmp((void *)(base + SITE), original, sizeof(original)) &&
        !memcmp((void *)(base + SITE + sizeof(original)), continuation, sizeof(continuation)) &&
        readable(sink, base + 0x2285181, sizeof(transmit)) &&
        !memcmp((void *)(base + 0x2285181), transmit, sizeof(transmit));
}

static int matches(uintptr_t base) {
    int saved = errno;
    int sink[2];
    /* O_NONBLOCK: an unexpectedly full pipe disarms instead of stalling the
     * loader. Every probe together stays well under one page.
     */
    if (pipe2(sink, O_CLOEXEC|O_NONBLOCK)) { errno = saved; return 0; }
    int ok = verify(sink[1], base);
    close(sink[0]);
    close(sink[1]);
    errno = saved;
    return ok;
}

static void relative_jump(unsigned char *at, uintptr_t target) {
    int32_t delta = (int32_t)((int64_t)target - (int64_t)((uintptr_t)at + 5));
    at[0] = 0xe9;
    memcpy(at + 1, &delta, sizeof(delta));
}

static unsigned char *near_page(uintptr_t site) {
    uintptr_t page = site & ~(uintptr_t)(PAGE-1);
    /* Bounded cold-path search; never replace an existing mapping. */
    for (uintptr_t distance = 16u << 20; distance < (uintptr_t)INT32_MAX - PAGE;
         distance += 16u << 20) {
        for (int sign = 1; sign >= -1; sign -= 2) {
            if ((sign < 0 && page <= distance) || (sign > 0 && page > UINTPTR_MAX-distance)) continue;
            uintptr_t address = sign > 0 ? page+distance : page-distance;
            void *p = mmap((void *)address, PAGE, PROT_READ|PROT_WRITE,
                          MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE, -1, 0);
            if (p == MAP_FAILED) continue;
            /* Older kernels may ignore MAP_FIXED_NOREPLACE. */
            if ((uintptr_t)p == address) return p;
            munmap(p, PAGE);
        }
    }
    return NULL;
}

unsigned int la_objopen(struct link_map *map, Lmid_t lmid, uintptr_t *cookie) {
    (void)cookie;
    const char *name = strrchr(map->l_name, '/');
    name = name ? name+1 : map->l_name;
    if (strcmp(name, "steamclient.so")) return 0;
    if (lmid != LM_ID_BASE || !map->l_addr || sysconf(_SC_PAGESIZE) != PAGE ||
        !matches(map->l_addr)) {
        LOG("DISARMED: unverified Steam image, namespace, or page size; vanilla unchanged");
        return 0;
    }
    unsigned char *site = (void *)(map->l_addr + SITE);
    unsigned char *trampoline = near_page((uintptr_t)site);
    if (!trampoline) {
        LOG("DISARMED: no near trampoline mapping; vanilla unchanged");
        return 0;
    }
    memcpy(trampoline, original, sizeof(original));
    /* add dword ptr [r12+0x10],r15d: advance seq by the partially ACKed bytes.
     * Registers/stack are untouched; the next original CMP overwrites flags.
     */
    const unsigned char advance[] = {0x45,0x01,0x7c,0x24,0x10};
    memcpy(trampoline + sizeof(original), advance, sizeof(advance));
    relative_jump(trampoline + sizeof(original) + sizeof(advance), (uintptr_t)site + sizeof(original));
    if (mprotect(trampoline, PAGE, PROT_READ|PROT_EXEC)) {
        munmap(trampoline, PAGE);
        LOG("DISARMED: trampoline protection failed; vanilla unchanged");
        return 0;
    }
    void *page = (void *)((uintptr_t)site & ~(uintptr_t)(PAGE-1));
    if (mprotect(page, PAGE, PROT_READ|PROT_WRITE)) {
        munmap(trampoline, PAGE);
        LOG("DISARMED: code protection failed; vanilla unchanged");
        return 0;
    }
    relative_jump(site, (uintptr_t)trampoline);
    memset(site + 5, 0x90, sizeof(original)-5);
    if (mprotect(page, PAGE, PROT_READ|PROT_EXEC)) {
        LOG("FATAL: cannot restore executable protection after patch; refusing partial startup");
        _exit(78);
    }
    /* ponytail: retain 4 KiB per load until process exit; never reclaim while
     * Steam's text remains mapped. Revisit only if repeated reloads become real.
     */
    LOG("APPLIED v1: PseudoTCP partial-ACK seq repair; build=df982870; disk image unchanged");
    return 0;
}

