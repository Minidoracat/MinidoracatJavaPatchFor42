# /// script
# requires-python = ">=3.11"
# dependencies = ["pyelftools>=0.31"]
# ///
"""PZ native library snapshot tool (sibling of scripts/decompile.py for Java).

Two layers:
  L1 (seconds, run on EVERY game update): sha256 + symbol-table snapshot + diff.
     Catches TIS changing a native lib while the jar stays byte-identical —
     the blind spot of the jar-only re-verification SOP.
  L2 (minutes, on demand): Ghidra headless decompilation to grep-able pseudo-C.
     libPZPopMan64.so ships with full DWARF (real names/types/lines); the other
     TIS libs keep .symtab (named functions, no variable names).

Snapshot root mirrors the Java tool: ../pz-decompiled-reference/snapshots/
  {ver}-{YYYYMMDD}/native/NATIVE_VERSION.txt   per-lib sha256/size/dwarf
  {ver}-{YYYYMMDD}/native/symbols/{lib}.sym    sorted "TYPE NAME" (no addresses)
  {ver}-{YYYYMMDD}/native/jni/{lib}.jni        Java_* exports only
  {ver}-{YYYYMMDD}/native/decompiled/{lib}.c   L2 output (on demand)

Usage:
  uv run scripts/native_snapshot.py fetch
  uv run scripts/native_snapshot.py snapshot --version 42.20.4
  uv run scripts/native_snapshot.py diff 42.20.3-20260817 42.20.4-20260829
  uv run scripts/native_snapshot.py setup
  uv run scripts/native_snapshot.py decompile --version 42.20.4 --lib libPZPopMan64.so
  uv run scripts/native_snapshot.py status | where | verify 42.20.4
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

from elftools.elf.elffile import ELFFile

PROJECT_ROOT = Path(__file__).resolve().parent.parent
REF_ROOT = PROJECT_ROOT.parent / "pz-decompiled-reference"
SNAP_ROOT = REF_ROOT / "snapshots"
TOOLS_ROOT = REF_ROOT / "tools"
GHIDRA_ROOT = TOOLS_ROOT / "ghidra"
WORK_NATIVE = PROJECT_ROOT / "work" / "native"

SSH_HOST = os.environ.get("PZ_SSH_HOST", "your-ssh-host")  # ~/.ssh/config alias of the game server
REMOTE_DIR = "/home/pzserver/serverfiles/linux64"
# TIS-authored libs only; Valve runtime (steam_api/steamwebrtc/steamclient) excluded.
LIBS = (
    "libPZPopMan64.so",
    "libPZPathFind64.so",
    "libPZBullet64.so",
    "libPZBulletNoOpenGL64.so",
    "libPZClipper64.so",
    "libPZXInitThreads64.so",
    "libpzexe_jni64.so",
    "libRakNet64.so",
    "libZNetJNI64.so",
    "libZNetNoSteam64.so",
    "libjassimp64.so",
)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_symbols(path: Path) -> tuple[list[str], list[str], bool]:
    """Return (sorted symbol lines, sorted JNI export names, has_dwarf).

    Symbol lines are "SECTION_KIND TYPE BIND NAME" without addresses so that
    recompiled-but-unchanged libs diff clean and real interface changes pop.
    """
    lines: set[str] = set()
    jni: set[str] = set()
    with path.open("rb") as f:
        elf = ELFFile(f)
        has_dwarf = elf.get_section_by_name(".debug_info") is not None
        for secname in (".dynsym", ".symtab"):
            sec = elf.get_section_by_name(secname)
            if sec is None:
                continue
            kind = "dyn" if secname == ".dynsym" else "sym"
            for s in sec.iter_symbols():
                name = s.name
                if not name:
                    continue
                st = s["st_info"]["type"].removeprefix("STT_")
                bind = s["st_info"]["bind"].removeprefix("STB_")
                if st not in ("FUNC", "OBJECT", "GNU_IFUNC"):
                    continue
                lines.add(f"{kind} {st} {bind} {name}")
                if name.startswith("Java_") and st == "FUNC":
                    jni.add(name)
    return sorted(lines), sorted(jni), has_dwarf


def cmd_fetch(_args) -> int:
    WORK_NATIVE.mkdir(parents=True, exist_ok=True)
    names = " ".join(LIBS)
    ssh = subprocess.Popen(
        ["ssh", SSH_HOST, f"cd {REMOTE_DIR} && tar cf - {names}"],
        stdout=subprocess.PIPE,
    )
    tar = subprocess.run(["tar", "xf", "-", "-C", str(WORK_NATIVE)], stdin=ssh.stdout)
    ssh.wait()
    if ssh.returncode or tar.returncode:
        print("FETCH FAILED", file=sys.stderr)
        return 1
    for lib in LIBS:
        p = WORK_NATIVE / lib
        print(f"  {lib:32s} {p.stat().st_size:>9} bytes")
    return 0


def snap_dir(version: str, date: str | None = None) -> Path:
    date = date or dt.date.today().strftime("%Y%m%d")
    return SNAP_ROOT / f"{version}-{date}" / "native"


def find_snapshot(name_or_version: str) -> Path | None:
    """Accept '42.20.4-20260829' or bare '42.20.4' (latest match wins)."""
    exact = SNAP_ROOT / name_or_version / "native"
    if exact.is_dir():
        return exact
    matches = sorted(SNAP_ROOT.glob(f"{name_or_version}-*/native"))
    return matches[-1] if matches else None


def cmd_snapshot(args) -> int:
    src = Path(args.src) if args.src else WORK_NATIVE
    libs = [src / l for l in LIBS if (src / l).is_file()]
    if not libs:
        print(f"no libs under {src}; run `fetch` first", file=sys.stderr)
        return 1
    out = snap_dir(args.version, args.date)
    (out / "symbols").mkdir(parents=True, exist_ok=True)
    (out / "jni").mkdir(exist_ok=True)
    meta: dict[str, dict] = {}
    for p in sorted(libs):
        syms, jni, dwarf = read_symbols(p)
        (out / "symbols" / f"{p.name}.sym").write_text("\n".join(syms) + "\n")
        (out / "jni" / f"{p.name}.jni").write_text("\n".join(jni) + "\n")
        meta[p.name] = {
            "sha256": sha256(p),
            "size": p.stat().st_size,
            "dwarf": dwarf,
            "symbols": len(syms),
            "jni_exports": len(jni),
        }
        print(f"  {p.name:32s} syms={len(syms):>6} jni={len(jni):>3} dwarf={'Y' if dwarf else 'n'}")
    header = (
        "# Native Library Snapshot Metadata\n"
        f"pz_build={args.version}\n"
        f"snapshot_date={dt.datetime.now().astimezone().isoformat()}\n"
        f"source={'work/native (fetched from ' + SSH_HOST + ')' if src == WORK_NATIVE else src}\n"
        "tool=native_snapshot.py/pyelftools\n"
    )
    (out / "NATIVE_VERSION.txt").write_text(header + json.dumps(meta, indent=2, sort_keys=True) + "\n")
    print(f"snapshot -> {out}")
    return 0


def load_meta(nat: Path) -> dict[str, dict]:
    text = (nat / "NATIVE_VERSION.txt").read_text()
    return json.loads(text[text.index("{"):])


def cmd_diff(args) -> int:
    a, b = find_snapshot(args.old), find_snapshot(args.new)
    if not a or not b:
        print(f"snapshot not found: {'' if a else args.old} {'' if b else args.new}", file=sys.stderr)
        return 1
    ma, mb = load_meta(a), load_meta(b)
    changed = 0
    for lib in sorted(set(ma) | set(mb)):
        if lib not in ma:
            print(f"NEW LIB  {lib}")
            changed += 1
            continue
        if lib not in mb:
            print(f"GONE LIB {lib}")
            changed += 1
            continue
        if ma[lib]["sha256"] == mb[lib]["sha256"]:
            print(f"  same   {lib}")
            continue
        changed += 1
        sa = set((a / "symbols" / f"{lib}.sym").read_text().splitlines())
        sb = set((b / "symbols" / f"{lib}.sym").read_text().splitlines())
        added, removed = sorted(sb - sa), sorted(sa - sb)
        print(f"CHANGED  {lib}  size {ma[lib]['size']} -> {mb[lib]['size']}  symbols +{len(added)} -{len(removed)}")
        for s in removed:
            print(f"    - {s}")
        for s in added:
            print(f"    + {s}")
        if not added and not removed:
            print("    (symbol set identical -> internal-only change; consider L2 decompile diff)")
    print(f"\n{changed} lib(s) changed" if changed else "\nall libs unchanged")
    return 0


def find_ghidra() -> Path | None:
    if not GHIDRA_ROOT.is_dir():
        return None
    hits = sorted(GHIDRA_ROOT.glob("ghidra_*/support"))
    return hits[-1].parent if hits else None


def cmd_setup(_args) -> int:
    if (g := find_ghidra()) is not None:
        print(f"ghidra already present: {g}")
        return 0
    GHIDRA_ROOT.mkdir(parents=True, exist_ok=True)
    api = "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/latest"
    with urllib.request.urlopen(api) as r:
        rel = json.load(r)
    asset = next(a for a in rel["assets"] if a["name"].endswith(".zip") and "PUBLIC" in a["name"])
    zip_path = GHIDRA_ROOT / asset["name"]
    print(f"downloading {asset['name']} ({asset['size'] >> 20} MiB) ...")
    urllib.request.urlretrieve(asset["browser_download_url"], zip_path)
    print("extracting ...")
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(GHIDRA_ROOT)
    zip_path.unlink()
    print(f"ghidra ready: {find_ghidra()}")
    return 0


def cmd_decompile(args) -> int:
    ghidra = find_ghidra()
    if ghidra is None:
        print("ghidra not installed; run `setup` first", file=sys.stderr)
        return 1
    nat = find_snapshot(args.version)
    if nat is None:
        print(f"no snapshot for {args.version}; run `snapshot` first", file=sys.stderr)
        return 1
    lib = WORK_NATIVE / args.lib
    if not lib.is_file():
        print(f"{lib} missing; run `fetch` first", file=sys.stderr)
        return 1
    meta = load_meta(nat)
    if args.lib in meta and meta[args.lib]["sha256"] != sha256(lib):
        print(f"REFUSING: work/native/{args.lib} sha != snapshot metadata (stale fetch?)", file=sys.stderr)
        return 1
    out_dir = nat / "decompiled"
    out_dir.mkdir(exist_ok=True)
    out_c = out_dir / f"{args.lib}.c"
    headless = ghidra / "support" / ("analyzeHeadless.bat" if sys.platform == "win32" else "analyzeHeadless")
    proj = PROJECT_ROOT / "work" / "ghidra-proj"
    if proj.exists():
        shutil.rmtree(proj)
    proj.mkdir(parents=True)
    script = PROJECT_ROOT / "scripts" / "GhidraExportDecomp.java"
    cmd = [
        str(headless), str(proj), "snap",
        "-import", str(lib),
        "-scriptPath", str(script.parent),
        "-postScript", script.name, str(out_c),
        "-deleteProject", "-analysisTimeoutPerFile", str(args.timeout),
    ]
    print("running:", " ".join(cmd))
    rc = subprocess.run(cmd).returncode
    if rc == 0 and out_c.is_file() and out_c.stat().st_size > 0:
        print(f"decompiled -> {out_c} ({out_c.stat().st_size >> 10} KiB)")
        return 0
    print("DECOMPILE FAILED", file=sys.stderr)
    return rc or 1


def cmd_verify(args) -> int:
    nat = find_snapshot(args.version)
    if nat is None:
        print(f"no snapshot for {args.version}", file=sys.stderr)
        return 1
    ok = True
    jni = (nat / "jni" / "libPZPopMan64.so.jni")
    if jni.is_file() and "Java_zombie_popman_ZombiePopulationManager_n_1updateMain" in jni.read_text():
        print("PASS jni anchor: popman n_updateMain export present")
    else:
        print("FAIL jni anchor: popman n_updateMain export missing")
        ok = False
    dec = nat / "decompiled" / "libPZPopMan64.so.c"
    if dec.is_file():
        text = dec.read_text(errors="replace")
        for anchor in ("chunkUpdateTask", "updateMain"):
            if anchor in text:
                print(f"PASS decompile anchor: {anchor}")
            else:
                print(f"FAIL decompile anchor: {anchor}")
                ok = False
    else:
        print("SKIP decompile anchors (no L2 output yet)")
    return 0 if ok else 1


def cmd_status(_args) -> int:
    if not SNAP_ROOT.is_dir():
        print("no snapshots")
        return 0
    for d in sorted(SNAP_ROOT.iterdir()):
        nat = d / "native"
        if not nat.is_dir():
            print(f"  {d.name}: (java only)")
            continue
        meta = load_meta(nat)
        dec = sorted(p.name for p in (nat / "decompiled").glob("*.c")) if (nat / "decompiled").is_dir() else []
        print(f"  {d.name}: {len(meta)} libs, decompiled: {', '.join(dec) if dec else 'none'}")
    return 0


def cmd_where(_args) -> int:
    print(REF_ROOT.resolve())
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fetch").set_defaults(fn=cmd_fetch)
    s = sub.add_parser("snapshot")
    s.add_argument("--version", required=True)
    s.add_argument("--date", default=None, help="override YYYYMMDD (default today)")
    s.add_argument("--src", default=None, help="override lib source dir (default work/native)")
    s.set_defaults(fn=cmd_snapshot)
    s = sub.add_parser("diff")
    s.add_argument("old")
    s.add_argument("new")
    s.set_defaults(fn=cmd_diff)
    sub.add_parser("setup").set_defaults(fn=cmd_setup)
    s = sub.add_parser("decompile")
    s.add_argument("--version", required=True)
    s.add_argument("--lib", required=True, choices=LIBS)
    s.add_argument("--timeout", type=int, default=3600)
    s.set_defaults(fn=cmd_decompile)
    s = sub.add_parser("verify")
    s.add_argument("version")
    s.set_defaults(fn=cmd_verify)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    sub.add_parser("where").set_defaults(fn=cmd_where)
    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
