#!/usr/bin/env python3
"""Recheck the 2026-09-07 05:41 core (pid 567642) against the 8/31 poisoning mechanism,
and answer the observer question: was the polluted rect array a guarded block?

Uses the stdlib core reader from pfguard_ring.py (no elftools on the server).
Read-only. Prints JSON.
"""
import json
import sys
import struct
from pathlib import Path

sys.path.insert(0, "/home/pzserver/scripts/pfguard")
from pfguard_ring import Target  # noqa: E402

CORE = Path(sys.argv[1])
BASE = 0x7FD65497A000                 # libPZPathFind64.so load base (hs_err)
RECT_POOL = BASE + 0x84B40            # ObjectPool<VehicleRect>   (8/31 offsets, same .so sha)
CLUSTER_POOL = BASE + 0x84AE0         # ObjectPool<VehicleCluster>
GRAPH_POOL = BASE + 0x84BA0           # ObjectPool<VisibilityGraph>
POLYGONAL_MAP2 = BASE + 0x82160       # R13 in hs_err
RSP = 0x7FD5CFAF9630                  # crash thread sp (hs_err)
SHIM_BASE = 0x7FD66E667000            # observer bias (ring reader)

t = Target(CORE, None)


def u64(a): return struct.unpack("<Q", t.read(a, 8))[0]
def u32(a): return struct.unpack("<I", t.read(a, 4))[0]
def qwords(a, n): return list(struct.unpack(f"<{n}Q", t.read(a, 8 * n)))


def enumerate_pool(base):
    f = qwords(base, 11)
    (map_ptr, map_size, start_cur, start_first, start_last, start_node,
     finish_cur, finish_first, finish_last, finish_node, total_alloc) = f
    items = []
    node = start_node
    while node <= finish_node:
        block = u64(node)
        begin = start_cur if node == start_node else block
        end = finish_cur if node == finish_node else block + 0x200
        assert block <= begin <= end <= block + 0x200, "deque cursor outside its block"
        items.extend(qwords(begin, (end - begin) // 8))
        node += 8
    return items, {"start_cur": start_cur, "total_alloc": total_alloc, "free": len(items)}


out = {}
# 1. in-flight local ArrayList of this round (rsp layout identical to 8/31)
packed = u64(RSP)
cap, cnt = packed & 0xFFFFFFFF, packed >> 32
local_array = u64(RSP + 8)
local = qwords(local_array, cnt)
out["local"] = {"capacity": cap, "count": cnt, "array": hex(local_array),
                "array_page_aligned(guarded?)": local_array % 4096 == 0,
                "unique": len(set(local)) == cnt,
                "backpointers_zero": all(u64(p) == 0 for p in local)}

# 2. rect pool: slot just popped must be 0x30
pool, pf = enumerate_pool(RECT_POOL)
popped = pf["start_cur"] - 8
out["rect_pool"] = {"free": pf["free"], "total_alloc": pf["total_alloc"],
                    "popped_slot_value": hex(u64(popped)), "pool_and_local_disjoint": set(pool).isdisjoint(local)}

# 3. find the polluted cluster: any cluster (free pool + reachable?) whose array[0]==0x30
clusters, cf = enumerate_pool(CLUSTER_POOL)
graphs, gf = enumerate_pool(GRAPH_POOL)
out["cluster_pool"] = {"free": cf["free"], "total_alloc": cf["total_alloc"]}
out["graph_pool"] = {"free": gf["free"], "total_alloc": gf["total_alloc"]}

hits = []
survivor = local[cnt - 1]
for c in clusters:
    try:
        ccap, ccount, arr = u32(c + 8), u32(c + 12), u64(c + 16)
        if arr == 0 or ccount == 0 or ccount > 4096:
            continue
        ents = qwords(arr, min(ccount, 8))
    except Exception:
        continue
    if 0x30 in ents or survivor in ents:
        rec = {"cluster": hex(c), "capacity": ccap, "count": ccount, "array": hex(arr),
               "array_page_aligned(guarded?)": arr % 4096 == 0,
               "entries": [hex(e) for e in ents],
               "survivor_index": ents.index(survivor) if survivor in ents else None}
        if arr % 4096 != 0:   # glibc chunk: inspect header
            try:
                rec["size_word(array-8)"] = hex(u64(arr - 8))
                rec["prev_size(array-16)"] = hex(u64(arr - 16))
                rec["next_size_word(array+cap*8+8)"] = hex(u64(arr + ccap * 8 + 8))
            except Exception as e:
                rec["header"] = f"unreadable: {e!r}"
        hits.append(rec)
out["polluted_candidates"] = hits

# 4. where do live cluster arrays live? sample: how many of the free-pool clusters' arrays are page-aligned
aligned = total = 0
for c in clusters[:5000]:
    try:
        arr = u64(c + 16)
    except Exception:
        continue
    if arr:
        total += 1
        aligned += (arr % 4096 == 0)
out["free_pool_cluster_arrays_sample"] = {"sampled": total, "page_aligned": aligned}

print(json.dumps(out, indent=2))
