#!/usr/bin/env python3
"""以精確 anchor 修補正式服已知的 B42 Workshop 相容問題。"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import sys
import tempfile
from pathlib import Path


PATCHES = (
    {
        "path": "3318210146/mods/Project Gurashi Megurigaoka/common/media/lua/server/ProjectGurashiGuaranteedItems.lua",
        "known_versions": (
            (
                "adf92de5033d34f2e6f6f88bf150b04fca75ac8428bb0d4ee713673100be2e39",
                "0ae0cf455a89a468ac71ed2d33fe12cc243860eccb662561a7b96178da78378b",
            ),
        ),
        "replacements": (
            (
                """local function spawnItemsAtCoordinate(room, containerType, container)
    -- container:getParent() returns the IsoObject the container belongs to,""",
                """local function spawnItemsAtCoordinate(room, containerType, container)
    if not instanceof(container, "ItemContainer") then return end

    -- container:getParent() returns the IsoObject the container belongs to,""",
                1,
            ),
        ),
    },
    {
        "path": "3536052310/mods/Neat_Building/42.15/media/lua/server/BuildRecipeCode/NB_BuildRecipeCode.lua",
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

\t--TODO:Corner miss""",
                """    thumpable:getSquare():transmitRemoveItemFromSquare(thumpable)
    return { replaceObject = true, object = window }

\t--TODO:Corner miss""",
                1,
            ),
        ),
    },
    {
        "path": "3037854728/mods/TikitownPowerPlant/42.13/media/scripts/TikitownPower_Items.txt",
        "known_versions": (
            (
                "dba828875ece870fa45391d48f493dba8c27e99949942aed969619bec42e0252",
                "40ec4af4de135cbe30f4d91d1365110a193e6db35b02272e2d29eebfb353c094",
            ),
        ),
        "replacements": (
            (
                "OnCreate = SpecialLootSpawns.OnCreateRecipeMagazine,",
                "OnCreate = ItemCodeOnCreate.onCreateRecipeMagazine,",
                7,
            ),
        ),
    },
    {
        "path": "3661164291/mods/MedievalZ/42.13/media/scripts/MedievalZRecipeBooks.txt",
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
    {
        "path": "3494374578/mods/Secretz42/42.15/media/lua/server/SZDoors/SZCServer.lua",
        "known_versions": (
            (
                "8cdc2c1dc0dfb191d1e4a46b0c4e76dec4816198e27a3e560e3c053485fb4838",
                "a8f84aec59d3195444c075035691bfe5ea3f7a8cc50f7220ad0c16a0fe3b3894",
            ),
        ),
        "replacements": (
            (
                """-- Register the command if running on the server
if isServer() then
    Commands["DespawnDoor"] = handleDespawnDoorCommand
    print("Server command 'DespawnDoor' registered.")
end""",
                """-- B42 沒有全域 Commands registry；下方以 Events.OnClientCommand 註冊。""",
                1,
            ),
            (
                """        if door.timer <= 0 then
            if isServer() then
                --print("Timer expired, sending close command for door at square (" .. door.square:getX() .. ", " .. door.square:getY() .. ", " .. door.square:getZ() .. ")")
                sendServerCommand("SZCServer", "DespawnDoor", {x = door.square:getX(), y = door.square:getY(), z = door.square:getZ(), spriteName = door.spriteName})
            else
                closeDoor(door.square, door.spriteName)
            end
            table.remove(SZCServer.openedDoors, i)
        end""",
                """        if door.timer <= 0 then
            closeDoor(door.square, door.spriteName)
            table.remove(SZCServer.openedDoors, i)
        end""",
                1,
            ),
        ),
    },
)

SYMLINKS = (
    ("3402491515/mods/tsarslib/common/media/animsets", "AnimSets"),
    ("3402491515/mods/tsarslib/media/animsets", "AnimSets"),
)


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


def analyze_file(root: Path, patch: dict) -> tuple[Path, bytes, bytes, bool]:
    path = root / patch["path"]
    original = path.read_bytes()
    current_hash = sha256_bytes(original)
    known_sources = dict(patch["known_versions"])
    known_patched = {patched for _, patched in patch["known_versions"]}

    if current_hash in known_patched:
        return path, original, original, False

    expected_patched_hash = known_sources.get(current_hash)
    if expected_patched_hash is None:
        raise RuntimeError(
            f"{patch['path']}: unknown upstream sha256={current_hash}; skipped"
        )

    updated = apply_replacements(original, patch)
    actual_patched_hash = sha256_bytes(updated)
    if actual_patched_hash != expected_patched_hash:
        raise RuntimeError(
            f"{patch['path']}: patched sha256 mismatch "
            f"actual={actual_patched_hash}, expected={expected_patched_hash}"
        )

    return path, original, updated, True


def analyze_symlink(
    root: Path, relative: str, target: str
) -> tuple[Path, bool, bool]:
    path = root / relative
    if not os.path.lexists(path):
        return path, True, False
    if path.is_symlink() and os.readlink(path) == target:
        link_stat = path.lstat()
        parent_stat = path.parent.stat()
        needs_owner = (
            link_stat.st_uid != parent_stat.st_uid
            or link_stat.st_gid != parent_stat.st_gid
        )
        return path, False, needs_owner
    raise RuntimeError(f"{relative}: expected absent path or symlink -> {target}")


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
    symlinks: tuple[tuple[str, str], ...] = SYMLINKS,
) -> int:
    needs_change = False
    warnings = 0

    for patch in patches:
        try:
            path, original, updated, changed = analyze_file(root, patch)
            relative = path.relative_to(root)
            print(f"{'NEEDS_PATCH' if changed else 'OK'} file {relative}")
            needs_change = needs_change or changed
            if apply and changed:
                backup_file(backup_root, root, path, original)
                atomic_write(path, updated)
                _, _, _, still_changed = analyze_file(root, patch)
                if still_changed:
                    raise RuntimeError(f"post-verify failed: {patch['path']}")
        except (OSError, RuntimeError) as error:
            warnings += 1
            print(f"WARNING file {patch['path']}: {error}", file=sys.stderr)

    for relative, target in symlinks:
        try:
            path, needs_link, needs_owner = analyze_symlink(root, relative, target)
            changed = needs_link or needs_owner
            status = "NEEDS_LINK" if needs_link else "NEEDS_OWNER" if needs_owner else "OK"
            print(f"{status} link {relative} -> {target}")
            needs_change = needs_change or changed
            if apply and changed:
                path.parent.mkdir(parents=True, exist_ok=True)
                if needs_link:
                    os.symlink(target, path)
                if not hasattr(os, "lchown"):
                    raise RuntimeError("os.lchown is required to set symlink ownership")
                parent_stat = path.parent.stat()
                os.lchown(path, parent_stat.st_uid, parent_stat.st_gid)
                _, still_needs_link, still_needs_owner = analyze_symlink(
                    root, relative, target
                )
                if still_needs_link or still_needs_owner:
                    raise RuntimeError(f"post-verify failed: {relative}")
        except (OSError, RuntimeError) as error:
            warnings += 1
            print(f"WARNING link {relative}: {error}", file=sys.stderr)

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
    with tempfile.TemporaryDirectory() as temp_dir:
        root = Path(temp_dir) / "workshop"
        backup_root = Path(temp_dir) / "backups"
        test_patches = []
        fixtures = []

        for patch in PATCHES:
            path = root / patch["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            parts = []
            for before, _, expected_count in patch["replacements"]:
                parts.extend([before] * expected_count)
            original = "\nfixture-separator\n".join(parts).encode("utf-8")
            updated = apply_replacements(original, patch)
            test_patch = dict(patch)
            test_patch["known_versions"] = (
                (sha256_bytes(original), sha256_bytes(updated)),
            )
            test_patches.append(test_patch)
            fixtures.append((path, original, updated))
            path.write_bytes(original)

        for relative, target in SYMLINKS:
            path = root / relative
            (path.parent / target).mkdir(parents=True, exist_ok=True)

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
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    return run(args.root, args.backup_root, args.apply)


if __name__ == "__main__":
    raise SystemExit(main())
