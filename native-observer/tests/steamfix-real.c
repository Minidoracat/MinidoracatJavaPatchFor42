/* Exercises the pinned, real Steam PseudoTCP implementation without opening
 * sockets or starting Steam. Internal offsets are guarded by the runner's SHA.
 * The packet callback verifies bytes a peer would receive, not patch wiring.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <assert.h>
#include <dlfcn.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct node { struct node *next, *prev; uint32_t seq, len; uint8_t xmit, ctl; };
struct segment { uint32_t conv, seq, ack; uint8_t flags, pad; uint16_t wnd;
                 const void *data; uint32_t len, tsval, tsecr; };
static unsigned char original_payload[2750];
static uint32_t wanted_seq, wanted_len;
static unsigned packets;
static int packet(void *notify, void *tcp, const unsigned char *data, uint32_t size) {
    (void)notify; (void)tcp;
    uint32_t seq;
    memcpy(&seq, data+4, 4);
    seq = ntohl(seq);
    assert(seq == wanted_seq && size == wanted_len+24);
    assert(!memcmp(data+24, original_payload + (seq-10000), wanted_len));
    packets++;
    return 0;
}
static void unexpected(void) { abort(); }
static void put32(void *obj, size_t off, uint32_t value) { memcpy((char *)obj+off, &value, 4); }
static uint32_t get32(void *obj, size_t off) { uint32_t value; memcpy(&value,(char *)obj+off,4); return value; }

int main(int argc, char **argv) {
    assert(argc == 3);
    void *lib = dlopen(argv[1], RTLD_NOW|RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 2; }
    struct link_map *map = NULL;
    assert(dlinfo(lib, RTLD_DI_LINKMAP, &map) == 0);
    int (*process)(void *, struct segment *) = (void *)(map->l_addr + 0x2285dc0);
    void *callbacks[] = {(void *)unexpected,(void *)unexpected,(void *)unexpected,
                         (void *)unexpected,(void *)packet};
    struct { void **vtable; } receiver = {callbacks};
    void *notify = &receiver;
    unsigned char *tcp = calloc(1, 0x36000);
    assert(tcp);
    memcpy(tcp+8, &notify, sizeof(notify));
    put32(tcp, 0x1c, 3);          /* established */
    put32(tcp, 0x20, 0x1234);
    put32(tcp, 0xf040, 77);      /* receive next */
    put32(tcp, 0xf044, 61440);
    put32(tcp, 0x25860, 12750); /* send next */
    put32(tcp, 0x25864, 65535);
    put32(tcp, 0x25868, 2750);  /* send buffered */
    put32(tcp, 0x25870, 10000); /* send unacknowledged */
    put32(tcp, 0x35874, 1375);  /* MSS */
    put32(tcp, 0x35898, 3000);  /* RTO */
    put32(tcp, 0x3589c, 65535);
    put32(tcp, 0x358a0, 5500);
    put32(tcp, 0x358a8, 12750); /* recovery end */
    struct node *sentinel = (void *)(tcp+0xf050);
    struct node *first = calloc(1, sizeof(*first));
    struct node *next = calloc(1, sizeof(*next));
    assert(first && next);
    *first = (struct node){next,sentinel,10000,1375,1,0};
    *next = (struct node){sentinel,first,11375,1375,1,0};
    sentinel->next = first; sentinel->prev = next;
    for (unsigned i=0;i<sizeof(original_payload);i++) original_payload[i]=(unsigned char)(i*37+11);
    memcpy(tcp+0xf060,original_payload,sizeof(original_payload));
    struct segment ack = {.conv=0x1234,.seq=77,.wnd=65535};
    int deferred = !strcmp(argv[2],"deferred");
    int full = !strcmp(argv[2],"full");
    int duplicate = !strcmp(argv[2],"duplicate");
    int repeated = !strcmp(argv[2],"repeated");
    int passive = !strcmp(argv[2],"passive");
    assert(deferred || full || duplicate || repeated || passive || !strcmp(argv[2],"partial"));
    wanted_seq=full ? 11375 : duplicate ? 10000 : 10999;
    wanted_len=(full || duplicate) ? 1375 : 376;
    ack.ack=wanted_seq;
    tcp[0x358a4]=(deferred || passive || repeated) ? 0 : duplicate ? 2 : 7;
    assert(process(tcp,&ack));
    if (passive) {
        assert(packets==0 && get32(tcp,0x25870)==10999);
        puts("PASS no unsolicited packet on non-recovery ACK");
    } else {
        if (repeated) {
            ack.ack=11100;
            assert(process(tcp,&ack));
            wanted_seq=11100; wanted_len=275;
        }
        if (deferred || repeated) {
            assert(packets==0);
            for (unsigned i=0;i<3;i++) assert(process(tcp,&ack));
        }
        assert(packets==1);
        puts("PASS peer-visible retransmission sequence and payload");
    }
    /* Steam owns nodes removed by full ACK; do not free stale pointers. */
    struct node *item=sentinel->next;
    while(item!=sentinel) { struct node *after=item->next; free(item); item=after; }
    free(tcp);
    dlclose(lib);
    return 0;
}
