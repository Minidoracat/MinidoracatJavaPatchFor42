#!/usr/bin/env python3
"""Enumerate the HL A* object pools (HLSuccessor / HLSearchNode) in core.567642 and look for
the corrupted write address V = victim_array-8 (= 0x7fd45c709a68) or other garbage entries.
Read-only."""
import sys
import struct
from collections import Counter
from pathlib import Path

sys.path.insert(0, "/home/pzserver/scripts/pfguard")
from pfguard_ring import Target  # noqa: E402

BASE = 0x7FD65497A000
V = 0x7FD45C709A68                  # address where {HLSearchNode*, 0x30} was written
HLSUCC_POOL = BASE + 0x81C00       # ObjectPool<HLSuccessor> deque (map_pointer at ::pool = 0x181c00)
HLNODE_POOL = BASE + 0x81AE0       # ObjectPool<HLSearchNode>
VT_HLSEARCHNODE = BASE + 0x7F450    # vptr value of HLSearchNode objects
t = Target(Path(sys.argv[1]), None)


def u64(a): return struct.unpack("<Q", t.read(a, 8))[0]
def u32(a): return struct.unpack("<I", t.read(a, 4))[0]
def qwords(a, n): return list(struct.unpack(f"<{n}Q", t.read(a, 8 * n)))


def pool(base):
    f = qwords(base, 11)
    (map_ptr, map_size, start_cur, start_first, start_last, start_node,
     finish_cur, finish_first, finish_last, finish_node, total_alloc) = f
    items = []
    node = start_node
    while node <= finish_node:
        block = u64(node)
        begin = start_cur if node == start_node else block
        end = finish_cur if node == finish_node else block + 0x200
        assert block <= begin <= end <= block + 0x200, f"deque cursor outside block at {base:#x}"
        items.extend(qwords(begin, (end - begin) // 8))
        node += 8
    return items, total_alloc, f


for name, base in (("HLSuccessor", HLSUCC_POOL), ("HLSearchNode", HLNODE_POOL)):
    try:
        items, total, f = pool(base)
    except Exception as e:
        print(f"{name}: enumerate failed: {e!r}; raw fields={[hex(x) for x in qwords(base, 11)]}")
        continue
    bad = [x for x in items if x % 16 != 0 or x < 0x10000]
    print(f"{name} pool: free={len(items)} total_alloc={total} garbage(non-16-aligned/low)={len(bad)} "
          f"unique={len(set(items)) == len(items)}")
    if bad:
        print("   garbage sample:", [hex(x) for x in bad[:10]])
    if V in items:
        print(f"   *** V={V:#x} IS IN THE {name} POOL at index {items.index(V)} ***")
    near = [x for x in items if abs(x - V) < 0x100]
    if near:
        print("   entries within 0x100 of V:", [hex(x) for x in near])
    if name == "HLSearchNode":
        # every pooled node should carry the HLSearchNode vptr
        vt = Counter()
        for x in items[:20000]:
            try:
                vt[u64(x)] += 1
            except Exception:
                vt["unreadable"] += 1
        print("   vptr census of pooled nodes:", {(hex(k) if isinstance(k, int) else k): v for k, v in vt.most_common(5)})
        # scan successors arrays (+0x58 cap, +0x5c count, +0x60 data) for V or garbage
        hitsV = 0
        garbage = 0
        scanned = 0
        for x in items[:20000]:
            try:
                cap, cnt, data = u32(x + 0x58), u32(x + 0x5C), u64(x + 0x60)
            except Exception:
                continue
            if data == 0 or cnt == 0 or cnt > cap or cap > 65536:
                continue
            scanned += 1
            try:
                ents = qwords(data, cnt)
            except Exception:
                continue
            if V in ents:
                hitsV += 1
                print(f"   *** node {x:#x} successors[{ents.index(V)}] == V ***  cap={cap} cnt={cnt} data={data:#x}")
            garbage += sum(1 for e in ents if e % 16 != 0 or e < 0x10000)
        print(f"   successors arrays scanned={scanned} entries==V:{hitsV} garbage entries:{garbage}")

# Is V referenced anywhere near the HL pools' deque blocks? (cheap check: the 0x200 node blocks)
print("\n== the HLSearchNode at 0x7fd501c7bcd0 (the pointer that was written into the victim)")
n = 0x7FD501C7BCD0
try:
    print("   vptr:", hex(u64(n)), "(HLSearchNode vtable+0x10 =", hex(VT_HLSEARCHNODE) + ")")
    print("   +0x58 cap/cnt/data:", u32(n + 0x58), u32(n + 0x5C), hex(u64(n + 0x60)))
    print("   fields 0x08..0x78:", [hex(q) for q in qwords(n + 8, 14)])
except Exception as e:
    print("   unreadable:", e)
