#!/usr/bin/env python3
"""Dump the glibc neighbourhood of the polluted rect array in core.567642 and classify
the previous chunk (the writer candidate). Read-only."""
import sys
import struct
from pathlib import Path

sys.path.insert(0, "/home/pzserver/scripts/pfguard")
from pfguard_ring import Target  # noqa: E402

BASE = 0x7FD65497A000
ARRAY = 0x7FD45C709A70          # polluted cluster array (capacity 16 -> 128B request -> 0x90 chunk)
CLUSTER = 0x7FD4A0C026A0
t = Target(Path(sys.argv[1]), None)


def u64(a): return struct.unpack("<Q", t.read(a, 8))[0]


def sym_of(v):
    """Map a value into libPZPathFind64 (vtable/func pointers) if it points there."""
    if BASE <= v < BASE + 0x200000:
        return f"PathFind+0x{v - BASE:x}"
    return None


def dump(start, n, mark=None):
    for a in range(start, start + n, 16):
        q0, q1 = u64(a), u64(a + 8)
        tag = ""
        for q in (q0, q1):
            s = sym_of(q)
            if s:
                tag += f"  [{s}]"
        m = " <== array" if a == mark else (" <== array-16 (chunk hdr)" if a == mark - 16 else "")
        print(f"{a:#x}: {q0:016x} {q1:016x}{tag}{m}")


print("== 512 bytes before .. 256 bytes after the polluted array")
dump(ARRAY - 512, 512 + 256, mark=ARRAY)

print("\n== walk glibc chunks backwards from the victim using size words (heuristic)")
# victim chunk header at ARRAY-16; its size word is overwritten, so walk forward from 512 bytes before
a = ARRAY - 512
# find a plausible chunk boundary: scan for size words with sane flags and that chain to ARRAY-16
for off in range(0, 512, 16):
    c = ARRAY - 512 + off
    chain = []
    cur = c
    ok = False
    for _ in range(64):
        sz = u64(cur + 8) & ~0x7
        if sz < 0x20 or sz > 0x10000:
            break
        chain.append((cur, u64(cur + 8)))
        cur += sz
        if cur == ARRAY - 16:
            ok = True
            break
        if cur > ARRAY - 16:
            break
    if ok:
        print(f"chain from {c:#x} reaches victim header:")
        for hdr, szw in chain:
            print(f"   chunk {hdr:#x} size_word={szw:#x} (size {szw & ~7:#x}, prev_inuse={szw & 1}, non_main={(szw >> 2) & 1}) user={hdr + 16:#x}")
        break
else:
    print("no clean chain found (heap metadata in this window may be damaged or window too small)")

print("\n== previous chunk candidate: 0x20 chunk immediately below the victim header (user = array-32)")
prev_user = ARRAY - 32
for i in range(0, 24, 8):
    v = u64(prev_user + i)
    print(f"   prev_user+{i:#x}: {v:#x} {sym_of(v) or ''}")
print(f"   prev chunk size word (array-40): {u64(ARRAY - 40):#x}")

print("\n== does the pointer-like size word / other values point into known pools?")
badsz = u64(ARRAY - 8)
print(f"   size word value {badsz:#x}: 16-aligned={badsz % 16 == 0}; as chunk user? header size word at {badsz - 8:#x} = ", end="")
try:
    print(f"{u64(badsz - 8):#x}; first qwords: {u64(badsz):#x} {u64(badsz + 8):#x} {u64(badsz + 16):#x}")
except Exception as e:
    print(f"unreadable ({e})")
