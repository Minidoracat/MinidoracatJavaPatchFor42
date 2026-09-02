#!/usr/bin/env python3
"""Decode libmdcpfguard.so's forensic ledger from a core dump or a live process.

    pfguard_ring.py --core core.12345 --shim out/libmdcpfguard.so [--limit 200]
    pfguard_ring.py --pid 12345      --shim out/libmdcpfguard.so [--limit 200]

The ledger lives in the shim's static storage, so it is present in any core the JVM
dumps. Only the standard library is used, so this runs anywhere python3 does.
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

RING_MAGIC = 0x4746504344444D55
COUNTER_MAGIC = 0x53544E554F434D55
EVENT_FORMAT = "<QQQQQQIIIHH"
EVENT_SIZE = struct.calcsize(EVENT_FORMAT)
RING_HEADER_FORMAT = "<QIIQQ24x"
RING_HEADER_SIZE = struct.calcsize(RING_HEADER_FORMAT)
NT_FILE = 0x46494C45

OPS = {
    1: "GUARD_ALLOC",
    2: "GUARD_FREE",
    3: "DELEGATE_REALLOC",
    4: "DELEGATE_FREE",
    5: "FOREIGN_USABLE_GT_REQUEST",
    6: "CANARY",
    7: "MMAP_FAIL",
    8: "TABLE_FULL",
    9: "OWNERSHIP_CONFLICT",
    10: "OWNED_SHRINK",
}

COUNTER_FIELDS = (
    "magic", "version", "mode", "whitelist_symbols", "allowlist_matched",
    "guard_alloc", "guard_free", "guard_live", "guard_peak", "bytes_live",
    "pages_mapped", "quarantined", "quarantine_unmapped", "delegate_realloc",
    "delegate_free", "owned_shrinks", "foreign_usable_gt_request",
    "skip_not_allowlisted", "skip_align", "skip_size", "skip_capacity",
    "skip_table_full", "canary_violations", "shrink_anomalies", "mmap_failures",
    "real_symbol_missing", "ra_cache_hit", "ra_cache_miss", "quarantine_failures",
    "nodes_used", "ownership_conflicts", "madvise_failures",
)
LAYOUT_VERSION = 4


class Elf:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.data = path.read_bytes() if path.stat().st_size < (64 << 20) else None
        self.handle = path.open("rb")
        header = self._read(0, 64)
        if header[:4] != b"\x7fELF" or header[4] != 2:
            raise ValueError(f"{path}: not a 64-bit ELF")
        (self.e_phoff, self.e_shoff) = struct.unpack_from("<QQ", header, 32)
        (self.e_phentsize, self.e_phnum, self.e_shentsize, self.e_shnum, self.e_shstrndx) = \
            struct.unpack_from("<HHHHH", header, 54)

    def _read(self, offset: int, size: int) -> bytes:
        if self.data is not None:
            return self.data[offset:offset + size]
        self.handle.seek(offset)
        return self.handle.read(size)

    def segments(self) -> list[tuple[int, int, int, int, int]]:
        result = []
        for index in range(self.e_phnum):
            raw = self._read(self.e_phoff + index * self.e_phentsize, self.e_phentsize)
            p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz, p_memsz, _align = \
                struct.unpack_from("<IIQQQQQQ", raw, 0)
            result.append((p_type, p_offset, p_vaddr, p_filesz, p_memsz))
        return result

    def sections(self) -> list[dict[str, int | str]]:
        raw_headers = [
            struct.unpack_from("<IIQQQQIIQQ", self._read(self.e_shoff + i * self.e_shentsize,
                                                        self.e_shentsize), 0)
            for i in range(self.e_shnum)
        ]
        shstr = raw_headers[self.e_shstrndx]
        strtab = self._read(shstr[4], shstr[5])
        out = []
        for name_off, sh_type, _flags, addr, offset, size, link, _info, _align, entsize in raw_headers:
            end = strtab.find(b"\0", name_off)
            out.append({
                "name": strtab[name_off:end].decode(),
                "type": sh_type, "addr": addr, "offset": offset,
                "size": size, "link": link, "entsize": entsize,
            })
        return out

    def symbol(self, wanted: str) -> int:
        sections = self.sections()
        for table in ("symtab", "dynsym"):
            for index, section in enumerate(sections):
                if section["name"] != f".{table}":
                    continue
                strtab = sections[section["link"]]
                names = self._read(strtab["offset"], strtab["size"])
                blob = self._read(section["offset"], section["size"])
                entsize = section["entsize"] or 24
                for cursor in range(0, len(blob), entsize):
                    st_name, _info, _other, _shndx, st_value, _size = \
                        struct.unpack_from("<IBBHQQ", blob, cursor)
                    end = names.find(b"\0", st_name)
                    if names[st_name:end].decode(errors="replace") == wanted:
                        return st_value
        raise KeyError(f"symbol {wanted} not found in {self.path}")


class Target:
    """Reads virtual memory from either a core file or a live process."""

    def __init__(self, core: Path | None, pid: int | None) -> None:
        self.pid = pid
        self.core = None
        self.loads: list[tuple[int, int, int]] = []
        self.files: list[tuple[int, int, int, str]] = []
        if core is not None:
            self.core = Elf(core)
            for p_type, p_offset, p_vaddr, p_filesz, _memsz in self.core.segments():
                if p_type == 1 and p_filesz:
                    self.loads.append((p_vaddr, p_filesz, p_offset))
                elif p_type == 4:
                    self.files.extend(self._parse_nt_file(p_offset, p_filesz))
            self.loads.sort()
        elif pid is not None:
            for line in Path(f"/proc/{pid}/maps").read_text().splitlines():
                parts = line.split(None, 5)
                bounds = parts[0].split("-")
                start, end = int(bounds[0], 16), int(bounds[1], 16)
                offset = int(parts[2], 16)
                name = parts[5].strip() if len(parts) > 5 else ""
                self.loads.append((start, end - start, start))
                if name:
                    self.files.append((start, end, offset, name))
            self.handle = open(f"/proc/{pid}/mem", "rb", buffering=0)
        else:
            raise ValueError("need --core or --pid")

    def _parse_nt_file(self, offset: int, size: int) -> list[tuple[int, int, int, str]]:
        blob = self.core._read(offset, size)
        cursor = 0
        entries: list[tuple[int, int, int, str]] = []
        while cursor + 12 <= len(blob):
            namesz, descsz, ntype = struct.unpack_from("<III", blob, cursor)
            name_start = cursor + 12
            desc_start = name_start + ((namesz + 3) & ~3)
            if ntype == NT_FILE and descsz >= 16:
                count, _page = struct.unpack_from("<QQ", blob, desc_start)
                table = desc_start + 16
                strings = table + count * 24
                names = blob[strings:desc_start + descsz].split(b"\0")
                for index in range(count):
                    start, end, file_off = struct.unpack_from("<QQQ", blob, table + index * 24)
                    label = names[index].decode(errors="replace") if index < len(names) else ""
                    entries.append((start, end, file_off, label))
            cursor = desc_start + ((descsz + 3) & ~3)
        return entries

    def read(self, address: int, size: int) -> bytes:
        if self.core is not None:
            for vaddr, filesz, offset in self.loads:
                rel = address - vaddr
                if 0 <= rel and rel + size <= filesz:
                    return self.core._read(offset + rel, size)
            raise ValueError(f"address {address:#x} not present in core")
        self.handle.seek(address)
        data = self.handle.read(size)
        if data is None or len(data) != size:
            raise ValueError(f"short read at {address:#x}")
        return data

    def load_bias(self, needle: str) -> int:
        candidates = [
            start - file_off for start, _end, file_off, name in self.files
            if name.endswith(needle) or needle.endswith(name.split("/")[-1])
        ]
        if not candidates:
            raise KeyError(f"{needle} is not mapped in this target")
        return min(candidates)


def decode(target: Target, shim: Path, limit: int) -> int:
    elf = Elf(shim)
    bias = target.load_bias(shim.name)
    ring_addr = bias + elf.symbol("mdc_pfguard_ring")
    counter_addr = bias + elf.symbol("mdc_pfguard_counters")

    header = target.read(ring_addr, RING_HEADER_SIZE)
    magic, version, entry_size, capacity, head = struct.unpack(RING_HEADER_FORMAT, header)
    if magic != RING_MAGIC:
        raise SystemExit(f"ring magic mismatch at {ring_addr:#x}: {magic:#x}")
    if entry_size != EVENT_SIZE:
        raise SystemExit(f"event size mismatch: shim says {entry_size}, reader knows {EVENT_SIZE}")
    if version != LAYOUT_VERSION:
        raise SystemExit(f"layout mismatch: shim is v{version}, this reader knows v{LAYOUT_VERSION}")

    counters = struct.unpack_from(f"<{len(COUNTER_FIELDS)}Q",
                                 target.read(counter_addr, 8 * len(COUNTER_FIELDS)))
    values = dict(zip(COUNTER_FIELDS, counters, strict=True))
    if values["magic"] != COUNTER_MAGIC:
        raise SystemExit(f"counter magic mismatch: {values['magic']:#x}")

    print(f"shim            {shim} (bias {bias:#x}, layout v{version})")
    print(f"ring            {ring_addr:#x} capacity={capacity} head={head}")
    print("counters        " + " ".join(
        f"{name}={values[name]}" for name in COUNTER_FIELDS if name not in ("magic", "version")))
    print()

    shown = min(limit, capacity, head)
    print(f"last {shown} events (newest first)")
    print(f"{'seq':>8} {'op':<17} {'user':>14} {'size':>7} {'old_user':>14} {'old':>7} "
          f"{'ra':>14} {'tid':>7} {'ns':>18}")
    skipped = 0
    for index in range(shown):
        expected = head - index
        slot = (expected - 1) % capacity
        slot_addr = ring_addr + RING_HEADER_SIZE + slot * EVENT_SIZE
        first_seq = struct.unpack("<Q", target.read(slot_addr, 8))[0]
        raw = target.read(slot_addr, EVENT_SIZE)
        second_seq = struct.unpack("<Q", target.read(slot_addr, 8))[0]
        seq, ns, user, base, ra, old_user, size, old_size, tid, op, _flags = \
            struct.unpack(EVENT_FORMAT, raw)
        if first_seq != expected or second_seq != expected or seq != expected:
            skipped += 1
            print(f"{expected:>8} {'<uncommitted>':<17} {'-':>14} {'-':>7} "
                  f"{'-':>14} {'-':>7} {'-':>14} {'-':>7} {'-':>18}")
            continue
        print(f"{seq:>8} {OPS.get(op, str(op)):<17} {user:>14x} {size:>7} {old_user:>14x} "
              f"{old_size:>7} {ra:>14x} {tid:>7} {ns:>18}")
    if skipped:
        print(f"warning: skipped {skipped} uncommitted/torn ring slot(s)", file=sys.stderr)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--core", type=Path)
    source.add_argument("--pid", type=int)
    parser.add_argument("--shim", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=200)
    args = parser.parse_args()
    return decode(Target(args.core, args.pid), args.shim, args.limit)


if __name__ == "__main__":
    sys.exit(main())
