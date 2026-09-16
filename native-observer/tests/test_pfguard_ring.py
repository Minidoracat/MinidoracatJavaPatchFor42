#!/usr/bin/env python3
"""Synthetic-ELF regression tests for pfguard_ring.py (stdlib only).

    python3 native-observer/tests/test_pfguard_ring.py

Every fixture is built in-process, so no core dump or shim binary is needed.
"""

import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import pfguard_ring as ring  # noqa: E402

PAGE = 4096
SHIM = "/fixture/lib/libmdcpfguard.so"


def note(ntype: int, desc: bytes, name: bytes = b"CORE") -> bytes:
    payload = name + b"\0"
    return (struct.pack("<III", len(payload), len(desc), ntype)
            + payload + b"\0" * (-len(payload) % 4)
            + desc + b"\0" * (-len(desc) % 4))


def nt_file(entries, page: int = PAGE) -> bytes:
    """entries: (start, end, file_offset_in_pages, name)."""
    body = struct.pack("<QQ", len(entries), page)
    body += b"".join(struct.pack("<QQQ", s, e, o) for s, e, o, _ in entries)
    return body + b"".join(name.encode() + b"\0" for *_, name in entries)


def prpsinfo(pid: int) -> bytes:
    desc = bytearray(136)
    struct.pack_into("<i", desc, ring.PRPSINFO_PID, pid)
    return bytes(desc)


def prstatus(tid: int) -> bytes:
    desc = bytearray(336)
    struct.pack_into("<i", desc, ring.PRSTATUS_PID, tid)
    return bytes(desc)


def build_core(path: Path, loads, notes, extended: bool = False) -> Path:
    """loads: [(vaddr, payload)]; notes: [(ntype, desc)]. extended -> PN_XNUM header."""
    note_blob = b"".join(note(ntype, desc) for ntype, desc in notes)
    phnum = len(loads) + (1 if note_blob else 0)
    cursor = 64 + phnum * 56
    phdrs = b""
    if note_blob:
        phdrs += struct.pack("<IIQQQQQQ", 4, 0, cursor, 0, 0, len(note_blob), 0, 4)
        cursor += len(note_blob)
    for vaddr, payload in loads:
        phdrs += struct.pack("<IIQQQQQQ", 1, 5, cursor, vaddr, 0,
                             len(payload), len(payload), PAGE)
        cursor += len(payload)
    shoff, tail = 0, b""
    if extended:
        shoff = cursor
        # section 0 carries the real phnum in sh_info
        tail = struct.pack("<IIQQQQIIQQ", 0, 0, 0, 0, 0, 0, 0, phnum, 0, 0)
    header = struct.pack("<16sHHIQQQIHHHHHH",
                         b"\x7fELF\x02\x01\x01", 4, 0x3E, 1, 0, 64, shoff, 0, 64, 56,
                         ring.PN_XNUM if extended else phnum,
                         64 if extended else 0, 1 if extended else 0, 0)
    path.write_bytes(header + phdrs + note_blob + b"".join(p for _, p in loads) + tail)
    return path


def hs_err_text(pid: int, mappings) -> str:
    lines = [f"#  SIGSEGV (0xb) at pc=0x1, pid={pid}, tid={pid+1}", "", "Dynamic libraries:"]
    lines += [f"{s:012x}-{e:012x} r-xp {o:08x} 08:02 4711 {n}" for s, e, o, n in mappings]
    return "\n".join(lines) + "\n"


class PfguardRingTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory(prefix="pfguard-ring-test-")
        self.addCleanup(directory.cleanup)
        self.tmp = Path(directory.name)

    def path(self, name: str) -> Path:
        return self.tmp / name

    def test_extended_phnum_from_section0(self):
        """e_phnum == PN_XNUM must be resolved through section header 0's sh_info."""
        core = build_core(self.path("core.xnum"),
                          loads=[(0x7F0000000000, b"AAAA" + b"payload!"),
                                 (0x7F0000002000, b"BBBBBBBB")],
                          notes=[(ring.NT_FILE,
                                  nt_file([(0x7F0000000000, 0x7F0000001000, 0, SHIM)]))],
                          extended=True)
        target = ring.Target(core, None)
        self.assertEqual(target.read(0x7F0000000004, 8), b"payload!")
        self.assertEqual(target.load_bias(SHIM), 0x7F0000000000)

    def test_pn_xnum_without_section_table_is_rejected(self):
        core = build_core(self.path("core.plain"), loads=[(0x1000, b"x" * 16)], notes=[])
        blob = bytearray(core.read_bytes())
        struct.pack_into("<H", blob, 56, ring.PN_XNUM)   # e_phnum, no e_shoff
        core.write_bytes(bytes(blob))
        with self.assertRaisesRegex(ValueError, "PN_XNUM"):
            ring.Elf(core)

    def test_nt_file_offsets_are_page_units(self):
        """NT_FILE file offsets are page counts; the bias must scale them."""
        start = 0x7F1000005000
        core = build_core(self.path("core.pages"), loads=[(start, b"z" * 32)],
                          notes=[(ring.NT_FILE,
                                  nt_file([(start, start + 0x1000, 5, SHIM)], page=8192))])
        target = ring.Target(core, None)
        self.assertEqual(target.load_bias("libmdcpfguard.so"), start - 5 * 8192)

    def test_load_bias_matches_path_or_basename_only(self):
        start = 0x7F2000000000
        core = build_core(self.path("core.names"), loads=[(start, b"q" * 16)],
                          notes=[(ring.NT_FILE,
                                  nt_file([(start, start + 0x1000, 0, "/lib/guard.so")]))])
        target = ring.Target(core, None)
        # "libmdcpfguard.so".endswith("guard.so") used to match this mapping.
        with self.assertRaises(KeyError):
            target.load_bias("libmdcpfguard.so")
        self.assertEqual(target.load_bias("/lib/guard.so"), start)
        self.assertEqual(target.load_bias("guard.so"), start)


    def test_hs_err_fallback_when_core_has_no_nt_file(self):
        pid, start = 4242, 0x7F4000000000
        core = build_core(self.path("core.nofile"), loads=[(start, b"h" * 16)],
                          notes=[(ring.NT_PRPSINFO, prpsinfo(pid))])
        log = self.path(f"hs_err_pid{pid}.log")
        log.write_text(hs_err_text(pid, [(start, start + 0x1000, 0, SHIM),
                                         (start + 0x1000, start + 0x2000, 0x1000, SHIM)]))
        target = ring.Target(core, None, log)
        self.assertEqual(target.load_bias("libmdcpfguard.so"), start)

    def test_hs_err_from_another_pid_is_refused(self):
        core = build_core(self.path("core.other"), loads=[(0x1000, b"h" * 16)],
                          notes=[(ring.NT_PRPSINFO, prpsinfo(4242))])
        log = self.path("hs_err_pid4242.log")  # a misleading filename must not authenticate the log
        log.write_text(hs_err_text(9999, [(0x7F5000000000, 0x7F5000001000, 0, SHIM)]))
        with self.assertRaisesRegex(ValueError, "belongs to pid 9999"):
            ring.Target(core, None, log)

    def test_hs_err_refused_when_core_cannot_prove_its_pid(self):
        core = build_core(self.path("core.anon"), loads=[(0x1000, b"h" * 16)], notes=[])
        log = self.path("hs_err_pid4242.log")
        log.write_text(hs_err_text(4242, [(0x7F6000000000, 0x7F6000001000, 0, SHIM)]))
        with self.assertRaisesRegex(ValueError, "refusing to trust"):
            ring.Target(core, None, log)

    def test_hs_err_pid_matched_against_thread_ids(self):
        pid, start = 777, 0x7F7000000000
        core = build_core(self.path("core.tids"), loads=[(start, b"t" * 16)],
                          notes=[(ring.NT_PRSTATUS, prstatus(pid)),
                                 (ring.NT_PRSTATUS, prstatus(pid + 1))])
        log = self.path(f"hs_err_pid{pid}.log")
        log.write_text(hs_err_text(pid, [(start, start + 0x1000, 0, SHIM)]))
        self.assertEqual(ring.Target(core, None, log).load_bias(SHIM), start)

    def test_nt_file_wins_over_hs_err(self):
        start = 0x7F8000000000
        core = build_core(self.path("core.both"), loads=[(start, b"b" * 16)],
                          notes=[(ring.NT_PRPSINFO, prpsinfo(4242)),
                                 (ring.NT_FILE,
                                  nt_file([(start, start + 0x1000, 0, SHIM)]))])
        log = self.path("hs_err_pid9999.log")
        log.write_text(hs_err_text(9999, [(0x10000, 0x11000, 0, SHIM)]))
        target = ring.Target(core, None, log)   # bogus log ignored, no exception
        self.assertEqual(target.load_bias(SHIM), start)

    def test_truncated_core_is_rejected(self):
        core = build_core(self.path("core.full"), loads=[(0x1000, b"c" * 64)],
                          notes=[(ring.NT_FILE,
                                  nt_file([(0x1000, 0x2000, 0, SHIM)]))])
        blob = core.read_bytes()
        short_header = self.path("core.head")
        short_header.write_bytes(blob[:32])
        with self.assertRaisesRegex(ValueError, "truncated read"):
            ring.Elf(short_header)
        short_table = self.path("core.table")
        short_table.write_bytes(blob[:80])      # header + one phdr's worth of bytes
        with self.assertRaisesRegex(ValueError, "truncated read"):
            ring.Target(short_table, None)

    def test_truncated_note_header_cannot_be_misread_as_missing_maps(self):
        core = build_core(self.path("core.note"), loads=[],
                          notes=[(ring.NT_PRPSINFO, prpsinfo(4242))])
        data = bytearray(core.read_bytes())
        note_size = struct.unpack_from("<Q", data, 64 + 32)[0]
        struct.pack_into("<Q", data, 64 + 32, note_size + 11)
        data.extend(b"x" * 11)
        core.write_bytes(data)
        log = self.path("hs_err_pid4242.log")
        log.write_text(hs_err_text(4242, [(0x1000, 0x2000, 0, SHIM)]))
        with self.assertRaisesRegex(ValueError, "truncated ELF note"):
            ring.Target(core, None, log)

    def test_target_needs_a_source(self):
        with self.assertRaises(ValueError):
            ring.Target(None, None)


if __name__ == "__main__":
    unittest.main(verbosity=2)
