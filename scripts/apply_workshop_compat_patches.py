#!/usr/bin/env python3
"""以精確 anchor 修補正式服已知的 B42 Workshop 相容問題。

路徑裡的 {version} 會依引擎 getModVersionDirName 規則，在 42.0 與遊戲版本之間
選最高的 version 目錄（只比 major.minor）。遊戲版本：--game-version，否則從
server-console.txt 的 `version=X.Y.Z` 讀。

正式服每 5 分鐘由 cron 呼叫 apply-workshop-compat-patches.sh --apply，再跑 fix-permissions.sh。

2026-08-21 正式服稽核：Project Gurashi、Tikitown、Secretz 已停用；
Tsarslib 的 AnimSets 大小寫 symlink 亦已退役，B42.20.3 會以
ZomboidFileSystem.getCanonicalFile(File,String) 做不分大小寫的子路徑解析。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import stat
import sys
import tempfile
from pathlib import Path


VERSION_TOKEN = "{version}"
VERSION_RE = re.compile(r"[0-9]+(?:\.[0-9]+)*")
CONSOLE_VERSION_RE = re.compile(
    r"version=(\d+\.\d+(?:\.\d+)?)\s+(?:\S+\s+)?demo=(?:true|false)\b"
)
DEFAULT_CONSOLE = Path("/home/pzserver/Zomboid/server-console.txt")
MIN_MOD_VERSION_RANK = (42, 0)

PATCHES = (
    {
        "path": "3536052310/mods/Neat_Building/{version}/media/lua/server/buildrecipecode/nb_buildrecipecode.lua",
        "known_versions": (
            (
                "59b0fdcce77e4a61d5e37c9cf730eacc68c2de887818b6690e4f3d11615969e4",
                "141a4db091106387b9422abf80128c07f3090e554e696f6813f2fcd36e09e9da",
            ),
        ),
        "replacements": (
            (
                """    thumpable:getSquare():transmitRemoveItemFromSquare(thumpable)
end

function NB_BuildRecipeCode.WindowWall.OnCreate(params)""",
                """    thumpable:getSquare():transmitRemoveItemFromSquare(thumpable)
    return { replaceObject = true, object = garageDoor }
end

function NB_BuildRecipeCode.WindowWall.OnCreate(params)""",
                1,
            ),
            (
                """    thumpable:getSquare():transmitRemoveItemFromSquare(thumpable)

	--TODO:Corner miss""",
                """    thumpable:getSquare():transmitRemoveItemFromSquare(thumpable)
    return { replaceObject = true, object = window }

	--TODO:Corner miss""",
                1,
            ),
        ),
    },
    {
        "path": "3661164291/mods/MedievalZ/{version}/media/scripts/MedievalZRecipeBooks.txt",
        "known_versions": (
            (
                "0a57fe44db783531dcc5f728915b8fb1c8da6d9f4cde4be822a8c10ac3d184be",
                "4fe181af4c66a332524f7cf4d9d482eb7441a7ca61a3e2204b8ac5cfab8877e5",
            ),
        ),
        "replacements": (
            (
                "OnCreate = SpecialLootSpawns.OnCreateRecipeMagazine,",
                "OnCreate = ItemCodeOnCreate.onCreateRecipeMagazine,",
                3,
            ),
        ),
    },
)


def parse_mod_version(name: str) -> tuple[int, ...] | None:
    if not VERSION_RE.fullmatch(name):
        return None
    return tuple(int(part) for part in name.split("."))


def version_rank(parsed: tuple[int, ...]) -> tuple[int, int]:
    minor = parsed[1] if len(parsed) > 1 else 0
    return (parsed[0], minor)


def pick_version_dir(parent: Path, game_version: str) -> str | None:
    cap = parse_mod_version(game_version)
    if cap is None:
        raise RuntimeError(f"invalid game version: {game_version}")
    cap_rank = version_rank(cap)
    best_name = None
    best_rank = MIN_MOD_VERSION_RANK
    try:
        names = [entry.name for entry in parent.iterdir() if entry.is_dir()]
    except OSError as error:
        raise RuntimeError(f"cannot list {parent}: {error}") from error
    # 同 rank 時由後列舉者覆寫（engine getModVersionDirName），故為 >= 而非 >。
    for name in names:
        key = parse_mod_version(name)
        if key is None:
            continue
        rank = version_rank(key)
        if rank < MIN_MOD_VERSION_RANK or rank > cap_rank:
            continue
        if rank >= best_rank:
            best_name, best_rank = name, rank
    return best_name


def resolve_patch_path(root: Path, template: str, game_version: str | None) -> Path:
    if VERSION_TOKEN not in template:
        return root / template
    if not game_version:
        raise RuntimeError(f"{template}: game version unknown; pass --game-version")
    prefix, _, suffix = template.partition(VERSION_TOKEN)
    parent = root / prefix.rstrip("/\\")
    chosen = pick_version_dir(parent, game_version)
    if chosen is None:
        raise RuntimeError(
            f"{template}: no version directory <= {game_version} under {parent}"
        )
    return parent / chosen / suffix.lstrip("/\\")


def detect_game_version(console: Path) -> str | None:
    if not console.is_file():
        return None
    with console.open("r", encoding="utf-8", errors="replace") as handle:
        head = handle.read(65536)
    match = CONSOLE_VERSION_RE.search(head)
    return match.group(1) if match else None


def anchor_bytes(text: str, newline: bytes) -> bytes:
    return text.encode("utf-8").replace(b"\n", newline)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def apply_replacements(original: bytes, patch: dict) -> bytes:
    updated = original
    newline = b"\r\n" if b"\r\n" in original else b"\n"

    for before_text, after_text, expected_count in patch["replacements"]:
        before = anchor_bytes(before_text, newline)
        after = anchor_bytes(after_text, newline)
        before_count = updated.count(before)
        after_count = updated.count(after)

        if before_count == expected_count and after_count == 0:
            updated = updated.replace(before, after)
        else:
            raise RuntimeError(
                f"{patch['path']}: unexpected anchor counts "
                f"before={before_count}, after={after_count}, expected={expected_count}"
            )

    return updated


def analyze_file(
    root: Path, patch: dict, game_version: str | None
) -> tuple[Path, bytes, bytes, bool]:
    path = resolve_patch_path(root, patch["path"], game_version)
    original = path.read_bytes()
    current_hash = sha256_bytes(original)
    known_sources = dict(patch["known_versions"])
    known_patched = {patched for _, patched in patch["known_versions"]}

    if current_hash in known_patched:
        return path, original, original, False

    expected_patched_hash = known_sources.get(current_hash)
    if expected_patched_hash is None:
        raise RuntimeError(
            f"{path.relative_to(root)}: unknown upstream sha256={current_hash}; skipped"
        )

    updated = apply_replacements(original, patch)
    actual_patched_hash = sha256_bytes(updated)
    if actual_patched_hash != expected_patched_hash:
        raise RuntimeError(
            f"{path.relative_to(root)}: patched sha256 mismatch "
            f"actual={actual_patched_hash}, expected={expected_patched_hash}"
        )

    return path, original, updated, True


def backup_file(backup_root: Path, root: Path, path: Path, data: bytes) -> None:
    digest = sha256_bytes(data)
    backup = backup_root / digest / path.relative_to(root)
    backup.parent.mkdir(parents=True, exist_ok=True)
    if backup.exists():
        if backup.read_bytes() != data:
            raise RuntimeError(f"backup collision: {backup}")
        return
    shutil.copy2(path, backup)


def atomic_write(path: Path, data: bytes) -> None:
    source_stat = path.stat()
    temporary = path.with_name(f".{path.name}.minidoracat-{os.getpid()}.tmp")
    if os.path.lexists(temporary):
        raise RuntimeError(f"temporary path already exists: {temporary}")
    temporary.write_bytes(data)
    os.chmod(temporary, stat.S_IMODE(source_stat.st_mode))
    if hasattr(os, "chown"):
        os.chown(temporary, source_stat.st_uid, source_stat.st_gid)
    os.replace(temporary, path)


def run(
    root: Path,
    backup_root: Path,
    apply: bool,
    patches: tuple[dict, ...] = PATCHES,
    game_version: str | None = None,
) -> int:
    needs_change = False
    warnings = 0

    for patch in patches:
        try:
            path, original, updated, changed = analyze_file(root, patch, game_version)
            relative = path.relative_to(root)
            print(f"{'NEEDS_PATCH' if changed else 'OK'} file {relative}")
            needs_change = needs_change or changed
            if apply and changed:
                backup_file(backup_root, root, path, original)
                atomic_write(path, updated)
                _, _, _, still_changed = analyze_file(root, patch, game_version)
                if still_changed:
                    raise RuntimeError(f"post-verify failed: {relative}")
        except (OSError, RuntimeError) as error:
            warnings += 1
            print(f"WARNING file {patch['path']}: {error}", file=sys.stderr)

    if warnings:
        print(f"COMPLETED_WITH_WARNINGS warnings={warnings}", file=sys.stderr)
        return 3

    if apply and needs_change:
        print("APPLIED_AND_VERIFIED")
        return 0

    if not apply and needs_change:
        print("CHECK_NEEDS_PATCH")
        return 2

    print("ALREADY_PATCHED")
    return 0


def self_test() -> None:
    from unittest.mock import patch as mock_patch

    with tempfile.TemporaryDirectory() as temp_dir:
        parent = Path(temp_dir)
        for name in ("common", "media", "42", "42.13", "42.14", "42.15", "42.21"):
            (parent / name).mkdir()
        assert pick_version_dir(parent, "42.20.3") == "42.15"
        assert pick_version_dir(parent, "42.13") == "42.13"
        assert pick_version_dir(parent, "42.0") == "42"
        assert pick_version_dir(parent, "41.78") is None
        assert parse_mod_version("common") is None
        legacy_only = parent / "legacy-only"
        legacy_only.mkdir()
        (legacy_only / "41.78").mkdir()
        assert pick_version_dir(legacy_only, "42.20.3") is None
        (parent / "42.20.4").mkdir()
        assert pick_version_dir(parent, "42.20.3") == "42.20.4"
        same_rank = parent / "same-rank"
        same_rank.mkdir()
        same_rank_dirs = (same_rank / "42.20.3", same_rank / "42.20.4")
        for directory in same_rank_dirs:
            directory.mkdir()
        with mock_patch.object(Path, "iterdir", return_value=iter(same_rank_dirs)):
            assert pick_version_dir(same_rank, "42.20.3") == "42.20.4"

        console = parent / "console.txt"
        console.write_text(
            "LOG  : General      f:0 st:1> version=42.20.3 70207f62e0 demo=false\n"
            "modversion=42.13\n"
            "os.version=17.0.9\n"
            "version=1.2\n",
            encoding="utf-8",
        )
        assert detect_game_version(console) == "42.20.3"
        console.write_text(
            "LOG  : General      f:0 st:1> version=42.20.3 demo=false\n"
            "modversion=42.13\n",
            encoding="utf-8",
        )
        assert detect_game_version(console) == "42.20.3"

        root = parent / "workshop"
        (root / "3536052310/mods/Neat_Building/42.15/media/lua").mkdir(parents=True)
        resolved = resolve_patch_path(
            root,
            "3536052310/mods/Neat_Building/{version}/media/lua/x.lua",
            "42.20.3",
        )
        assert resolved.name == "x.lua"
        assert "42.15" in resolved.parts

    with tempfile.TemporaryDirectory() as temp_dir:
        root = Path(temp_dir) / "workshop"
        backup_root = Path(temp_dir) / "backups"
        test_patches = []
        fixtures = []

        for patch in PATCHES:
            rel = patch["path"].replace(VERSION_TOKEN, "42.15")
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            parts = []
            for before, _, expected_count in patch["replacements"]:
                parts.extend([before] * expected_count)
            original = "\nfixture-separator\n".join(parts).encode("utf-8")
            updated = apply_replacements(original, patch)
            test_patch = dict(patch)
            test_patch["path"] = rel
            test_patch["known_versions"] = (
                (sha256_bytes(original), sha256_bytes(updated)),
            )
            test_patches.append(test_patch)
            fixtures.append((path, original, updated))
            path.write_bytes(original)

        assert run(root, backup_root, apply=False, patches=tuple(test_patches)) == 2

        unknown_path, unknown_original, _ = fixtures[0]
        unknown_bytes = unknown_original + b"\n-- simulated upstream update\n"
        unknown_path.write_bytes(unknown_bytes)
        assert run(root, backup_root, apply=True, patches=tuple(test_patches)) == 3
        assert unknown_path.read_bytes() == unknown_bytes
        for path, _, updated in fixtures[1:]:
            assert path.read_bytes() == updated

        unknown_path.write_bytes(unknown_original)
        assert run(root, backup_root, apply=True, patches=tuple(test_patches)) == 0
        assert run(root, backup_root, apply=False, patches=tuple(test_patches)) == 0
        print("SELF_TEST_OK")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("/home/pzserver/serverfiles/steamapps/workshop/content/108600"),
    )
    parser.add_argument(
        "--backup-root",
        type=Path,
        default=Path("/home/pzserver/patches/workshop-compat-preimages"),
    )
    parser.add_argument(
        "--console",
        type=Path,
        default=DEFAULT_CONSOLE,
        help="server-console.txt used to detect game version",
    )
    parser.add_argument("--game-version", help="override detected game version")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    game_version = args.game_version or detect_game_version(args.console)
    if game_version:
        print(f"GAME_VERSION {game_version}")
    return run(args.root, args.backup_root, args.apply, game_version=game_version)


if __name__ == "__main__":
    raise SystemExit(main())
