#!/usr/bin/env python3
"""Run the pinned real Steam implementation offline; never initializes Steam/network.

Usage: python3 test_steamfix.py [/path/to/steamclient.so]
The proprietary binary is supplied locally, never stored in the repository.
Without it, only the synthetic refusal cases run.
Set MDC_STEAMFIX_SOURCE to build the audit library from another steamfix.c
copy, e.g. a pre-fix one, to watch the refusal cases fail.
"""
import hashlib
import os
from pathlib import Path
import resource
import signal
import struct
import subprocess
import sys
import tempfile

SHA256 = 'd8fbc2925af26522c3316f8bad2ac307b4726391a6174c220ca760fc3a421591'
ROOT = Path(__file__).resolve().parents[1]
AUDIT_SOURCE = Path(os.environ.get('MDC_STEAMFIX_SOURCE') or ROOT / 'steamfix.c')


def first_load_vaddr(path):
    """Minimal ELF64 phdr read: nonzero here means l_addr is not the mapping."""
    data = path.read_bytes()
    phoff, = struct.unpack_from('<Q', data, 0x20)
    phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
    for index in range(phnum):
        entry = phoff + index * phentsize
        if struct.unpack_from('<I', data, entry)[0] == 1:  # PT_LOAD
            return struct.unpack_from('<Q', data, entry + 0x10)[0]
    raise AssertionError(f'{path}: no PT_LOAD')


def main():
    source = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else None
    if source and hashlib.sha256(source.read_bytes()).hexdigest() != SHA256:
        raise SystemExit('refusing to execute an unverified Steam binary')
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    with tempfile.TemporaryDirectory(prefix='mdc-steam-test-') as directory:
        out = Path(directory)
        audit = out / 'libmdcsteamfix.so'
        harness = out / 'steamfix-real'
        loader = out / 'steamfix-load'
        for command in (
            ['gcc', '-std=gnu11', '-O2', '-g', '-Wall', '-Wextra', '-Werror',
             '-fPIC', '-shared', '-Wl,-z,now', '-o', str(audit), str(AUDIT_SOURCE)],
            ['gcc', '-std=gnu11', '-O2', '-g', '-Wall', '-Wextra', '-Werror',
             '-o', str(harness), str(ROOT / 'tests/steamfix-real.c'), '-ldl'],
            ['gcc', '-std=gnu11', '-O2', '-g', '-Wall', '-Wextra', '-Werror',
             '-o', str(loader), str(ROOT / 'tests/steamfix-load.c'), '-ldl'],
        ):
            subprocess.run(command, check=True)
        count = 0

        def run(image, mode, scenario, expected):
            nonlocal count
            environment = os.environ.copy()
            environment.pop('LD_PRELOAD', None)
            environment.update(LD_AUDIT=str(audit), MDC_STEAMFIX=mode)
            child = subprocess.run([str(harness), str(image), scenario],
                                   env=environment, capture_output=True, text=True, timeout=30)
            if child.returncode != expected:
                raise AssertionError(f'{mode}/{scenario}: exit={child.returncode}, expected={expected}\n'
                                     f'{child.stdout}\n{child.stderr}')
            count += 1
            print(f'PASS {mode}/{scenario} exit={child.returncode}')

        def refuse(image, why):
            nonlocal count
            environment = os.environ.copy()
            environment.pop('LD_PRELOAD', None)
            environment.update(LD_AUDIT=str(audit), MDC_STEAMFIX='1')
            child = subprocess.run([str(loader), str(image)],
                                   env=environment, capture_output=True, text=True, timeout=30)
            if child.returncode != 0 or 'DISARMED' not in child.stderr:
                raise AssertionError(f'{why}: exit={child.returncode}, expected a clean DISARMED\n'
                                     f'{child.stdout}\n{child.stderr}')
            count += 1
            print(f'PASS refuse/{why} exit=0')

        # A legal DSO whose first PT_LOAD has a nonzero p_vaddr leaves l_addr
        # outside every mapping, so refusing it must not fault. Needs no
        # proprietary binary: these two cases run on a bare checkout.
        fake = out / 'fake.c'
        fake.write_text('int mdc_fake(void) { return 42; }\n')
        shared = ['gcc', '-std=gnu11', '-O2', '-Wall', '-Wextra', '-Werror', '-fPIC', '-shared']
        images = {}
        for label, extra in (('geometry', []), ('bias', ['-Wl,-Ttext-segment=0x40000000'])):
            home = out / label
            home.mkdir()
            images[label] = home / 'steamclient.so'
            subprocess.run(shared + ['-o', str(images[label]), str(fake)] + extra, check=True)
        if first_load_vaddr(images['geometry']):
            raise AssertionError('control DSO unexpectedly has a nonzero first PT_LOAD')
        if not first_load_vaddr(images['bias']):
            raise AssertionError('linker ignored -Ttext-segment; the bias case would be vacuous')
        refuse(images['bias'], 'nonzero-first-PT_LOAD')
        refuse(images['geometry'], 'wrong-ELF-geometry')
        if not source:
            print(f'PASS {count} refusal scenarios; real-library cases need the pinned binary')
            return 0
        # These are consumer-visible cases, not byte-pattern wiring assertions.
        for scenario in ('partial', 'deferred', 'repeated'):
            run(source, '0', scenario, -signal.SIGSEGV)
            run(source, '1', scenario, 0)
        for scenario in ('full', 'duplicate', 'passive'):
            for mode in ('0', '1'):
                run(source, mode, scenario, 0)
        # Valid ELF, wrong build ID: partial-ACK crash must remain vanilla.
        other = out / 'steamclient.so'
        data = bytearray(source.read_bytes())
        data[0x2b8] ^= 1
        other.write_bytes(data)
        run(other, '1', 'partial', -signal.SIGSEGV)
        # Same build ID but changed continuation: dupACK=7 still takes the
        # original recovery path, but the repair must reject the changed image.
        data[0x2b8] ^= 1
        data[0x22862c8 + 8 + 6 - 0x1000] = 3
        other.write_bytes(data)
        run(other, '1', 'partial', -signal.SIGSEGV)
        if hashlib.sha256(source.read_bytes()).hexdigest() != SHA256:
            raise AssertionError('original binary changed on disk')
        print(f'PASS {count} scenarios including the real library; original disk SHA unchanged')
    return 0


if __name__ == '__main__':
    sys.exit(main())
