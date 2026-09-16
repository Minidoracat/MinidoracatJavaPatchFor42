#!/usr/bin/env python3
"""以精確 anchor 修補正式服已知的 B42 Workshop 相容問題。

路徑裡的 {version} 會依引擎 getModVersionDirName 規則，在 42.0 與遊戲版本之間
選最高的 version 目錄（只比 major.minor）。遊戲版本：--game-version，否則從
server-console.txt 的 `version=X.Y.Z` 讀。

正式服每 5 分鐘由 cron 呼叫 apply-workshop-compat-patches.sh --apply，再跑 fix-permissions.sh。

2026-08-21 正式服稽核：Project Gurashi、Tikitown、Secretz 已停用；
Tsarslib 的 AnimSets 大小寫 symlink 亦已退役，B42.20.3 會以
ZomboidFileSystem.getCanonicalFile(File,String) 做不分大小寫的子路徑解析。

PSR: --psr-output FILE 僅產生副本；--psr-temporary STATE_DIR 則在 --apply 時就地修補。
啟動前呼叫暫時模式：Workshop 更新、modversion 變更或來源替換後永久退場；
只還原完整符合我方指紋的檔案，作者新檔不覆寫。--psr-retire 可離線回復。
"""

from __future__ import annotations

import json
import stat
import time
import urllib.parse
import urllib.request

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


PSR_SCAN_PATCH = {
    "path": "3725311427/mods/Plysken Solar Revolution/{version}/media/lua/server/PSR/PowerBank/PowerBankObject_Server.lua",
    "known_versions": (
        (
            "8425616770884e0efe21df5e294358232bb6175495e11b322e8fd5d44252c3ed",
            "63f1d42ba3f743caa25b10cdd995d32b786434f190178ccabd69b6793e4cd91b",
        ),
    ),
    "replacements": (
        (
            '            local dtype = full and psrGetDeviceType(obj) or (psrIsLight(obj) and "light")',
            """            local dtype
            if full then
                dtype = psrGetDeviceType(obj)
            elseif psrIsLight(obj) then
                dtype = "light"
            end""",
            1,
        ),
    ),
}

MODVERSION_RE = re.compile(r"^[ \t]*modversion[ \t]*=[ \t]*(\S+)[ \t]*$", re.MULTILINE)

WORKSHOP_DETAILS_URL = (
    "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
)

TEMPORARY_STATE_VERSION = 1

TEMPORARY_PHASES = ("active", "retiring", "retired")

PSR_LIFECYCLE = {
    "patch": PSR_SCAN_PATCH,
    "workshop_id": "3725311427",
    "pinned_revision": 1789544087,
    "pinned_modversion": "6.6.8",
    "state_tool": "minidoracat-psr-temporary",
    "state_name": "psr-temporary-state.json",
    "backup_name": "psr-powerbank-original.lua",
    "mod_info_parents": 5,
}


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


def atomic_write(path: Path, data: bytes, stat_source: Path | None = None) -> None:
    source_stat = (stat_source or path).stat()
    mode = stat.S_IMODE(source_stat.st_mode)
    if stat.S_ISDIR(source_stat.st_mode):
        mode &= ~0o111  # 目錄的執行位不該帶到新檔案
    temporary = path.with_name(f".{path.name}.minidoracat-{os.getpid()}.tmp")
    if os.path.lexists(temporary):
        raise RuntimeError(f"temporary path already exists: {temporary}")
    temporary.write_bytes(data)
    os.chmod(temporary, mode)
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


def patch_sources(patch: dict):
    return (
        patch["known_sources"]
        if "known_sources" in patch
        else dict(patch["known_versions"])
    )


def run_psr_output(
    root: Path, output: Path, apply: bool, game_version: str | None = None
) -> int:
    """只輸出已驗證的 PSR 副本，不改來源，也不讓一般排程自動套用。"""
    try:
        source, original, updated, _ = analyze_file(root, PSR_SCAN_PATCH, game_version)
        if output.resolve().is_relative_to(root.resolve()):
            raise RuntimeError("PSR output must be outside the Workshop root")
        if output.is_symlink():
            raise RuntimeError(f"PSR output must not be a symlink: {output}")
        if output.exists():
            if output.read_bytes() != updated:
                raise RuntimeError(f"refusing to overwrite unrelated output: {output}")
            print(f"OUTPUT_ALREADY_VERIFIED {output} sha256={sha256_bytes(updated)}")
            return 0
        print(f"PSR_SOURCE {source} sha256={sha256_bytes(original)}")
        print(f"PSR_OUTPUT {output} sha256={sha256_bytes(updated)}")
        if not apply:
            return 2
        if source.read_bytes() != original:
            raise RuntimeError("PSR source changed during generation; refusing to write")
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("xb") as stream:
            stream.write(updated)
        if output.read_bytes() != updated:
            raise RuntimeError(f"output verification failed: {output}")
        print("GENERATED_AND_VERIFIED_SOURCE_UNCHANGED")
        return 0
    except (OSError, RuntimeError) as error:
        print(f"WARNING PSR: {error}", file=sys.stderr)
        return 3


def generate_temporary_patch(spec: dict, original: bytes) -> bytes:
    source_hash = sha256_bytes(original)
    patch = spec["patch"]
    if source_hash not in patch_sources(patch):
        raise RuntimeError(
            f"{patch['path']}: unknown upstream sha256={source_hash}; refusing to generate"
        )
    newline = b"\r\n" if b"\r\n" in original else b"\n"
    header = anchor_bytes(
        spec.get("header", "").format(source_hash=source_hash), newline
    )
    updated = header + apply_replacements(original, patch)
    expected = dict(patch.get("known_versions", ())).get(source_hash)
    if expected is not None and sha256_bytes(updated) != expected:
        raise RuntimeError(f"{patch['path']}: generated fingerprint mismatch")
    return updated


def fetch_workshop_updated(workshop_id: str, timeout: float = 10.0) -> int:
    """讀公開 Steam GetPublishedFileDetails 的 time_updated；不使用任何憑證。"""
    body = urllib.parse.urlencode(
        {"itemcount": 1, "publishedfileids[0]": workshop_id}
    ).encode("ascii")
    request = urllib.request.Request(
        WORKSHOP_DETAILS_URL,
        data=body,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": "minidoracat-workshop-compat/1",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as error:
        raise RuntimeError(f"steam metadata: unparsable response ({error})") from error

    response = payload.get("response") if isinstance(payload, dict) else None
    details = (
        response.get("publishedfiledetails") if isinstance(response, dict) else None
    )
    if not isinstance(details, list) or len(details) != 1:
        raise RuntimeError(
            "steam metadata: expected exactly one publishedfiledetails entry"
        )
    detail = details[0]
    if not isinstance(detail, dict):
        raise RuntimeError("steam metadata: malformed publishedfiledetails entry")
    if str(detail.get("publishedfileid")) != workshop_id:
        raise RuntimeError(
            f"steam metadata: id mismatch {detail.get('publishedfileid')!r}"
        )
    if detail.get("result") != 1:
        raise RuntimeError(f"steam metadata: result={detail.get('result')!r}")
    updated = detail.get("time_updated")
    if not isinstance(updated, int) or isinstance(updated, bool) or updated <= 0:
        raise RuntimeError(f"steam metadata: bad time_updated={updated!r}")
    return updated


def temporary_state_paths(spec: dict, state_dir: Path) -> tuple[Path, Path]:
    """狀態與原檔備份永遠固定在 STATE_DIR 之下，不從 state 讀任意路徑。"""
    return state_dir / spec["state_name"], state_dir / spec["backup_name"]


def refuse_symlink(path: Path) -> None:
    if path.is_symlink():
        raise RuntimeError(f"{path}: symlink is not accepted")


def read_modversion(spec: dict, source: Path) -> str:
    """讀來源 version 目錄的 mod.info modversion；讀不到就當不明狀態，不猜。"""
    info = source.parents[spec["mod_info_parents"]] / "mod.info"
    refuse_symlink(info)
    match = MODVERSION_RE.search(info.read_text(encoding="utf-8", errors="replace"))
    if match is None:
        raise RuntimeError(f"{info}: modversion not found")
    return match.group(1)


def load_temporary_state(spec: dict, state_dir: Path) -> dict | None:
    state_path, backup_path = temporary_state_paths(spec, state_dir)
    for path in (state_dir, state_path, backup_path):
        refuse_symlink(path)
    if not state_path.exists():
        return None
    if not state_path.is_file():
        raise RuntimeError(f"{state_path}: not a regular file")
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, ValueError) as error:
        raise RuntimeError(f"{state_path}: unreadable state ({error})") from error
    if not isinstance(state, dict) or state.get("tool") != spec["state_tool"]:
        raise RuntimeError(f"{state_path}: state not written by this tool")
    if state.get("state_version") != TEMPORARY_STATE_VERSION:
        raise RuntimeError(
            f"{state_path}: unsupported state_version={state.get('state_version')!r}"
        )
    if state.get("phase") not in TEMPORARY_PHASES:
        raise RuntimeError(f"{state_path}: unknown phase={state.get('phase')!r}")
    if (
        state.get("workshop_id") != spec["workshop_id"]
        or state.get("pinned_revision") != spec["pinned_revision"]
    ):
        raise RuntimeError(f"{state_path}: state belongs to another upstream revision")
    for key in ("original_sha256", "patched_sha256"):
        value = state.get(key)
        if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
            raise RuntimeError(f"{state_path}: invalid {key}")
    if state["original_sha256"] not in patch_sources(spec["patch"]):
        raise RuntimeError(f"{state_path}: original fingerprint is not supported")
    return state


def write_temporary_state(spec: dict, state_dir: Path, state: dict) -> None:
    state_path, _ = temporary_state_paths(spec, state_dir)
    refuse_symlink(state_path)
    data = (json.dumps(state, indent=2, sort_keys=True) + "\n").encode("utf-8")
    atomic_write(state_path, data, state_path if state_path.is_file() else state_dir)


def apply_temporary(
    spec: dict, state_dir: Path, source: Path, original: bytes, apply: bool
) -> int:
    try:
        patched = generate_temporary_patch(spec, original)
    except RuntimeError as error:
        print(f"WARNING temporary: {error}", file=sys.stderr)
        return 3

    print(f"NEEDS_PATCH file {source}")
    if not apply:
        print("CHECK_NEEDS_PATCH")
        return 2

    try:
        state_path, backup_path = temporary_state_paths(spec, state_dir)
        state_dir.mkdir(parents=True, exist_ok=True)
        refuse_symlink(state_dir)
        refuse_symlink(state_path)
        refuse_symlink(backup_path)
        # 先存並驗證原檔備份，狀態次之，最後才換來源檔：任何一步中斷都還原得回去。
        if backup_path.exists():
            if backup_path.read_bytes() != original:
                raise RuntimeError(
                    f"{backup_path}: existing backup differs; refusing to overwrite"
                )
        else:
            atomic_write(backup_path, original, state_dir)
        if backup_path.read_bytes() != original:
            raise RuntimeError(f"backup verify failed: {backup_path}")
        write_temporary_state(
            spec,
            state_dir,
            {
                "tool": spec["state_tool"],
                "state_version": TEMPORARY_STATE_VERSION,
                "phase": "active",
                "workshop_id": spec["workshop_id"],
                "pinned_revision": spec["pinned_revision"],
                "original_sha256": sha256_bytes(original),
                "patched_sha256": sha256_bytes(patched),
                "source_path_seen": str(source),  # 僅供維運判讀，不用於定位檔案
                "applied_at": int(time.time()),
                "retired_at": None,
                "retire_reason": None,
            },
        )
        if source.read_bytes() != original:
            raise RuntimeError(f"{source}: source changed during apply; left untouched")
        atomic_write(source, patched, source)
        if source.read_bytes() != patched:
            raise RuntimeError(f"post-verify failed: {source}")
    except (OSError, RuntimeError) as error:
        print(f"WARNING temporary: {error}", file=sys.stderr)
        return 3

    print("APPLIED_AND_VERIFIED")
    return 0


def retire_temporary(
    spec: dict,
    state_dir: Path,
    state: dict,
    source: Path,
    current: bytes,
    apply: bool,
    reason: str,
) -> int:
    restore = sha256_bytes(current) == state["patched_sha256"]
    if state["phase"] == "retired" and not restore:
        print("ALREADY_RETIRED")
        return 0

    print(f"NEEDS_RETIRE file {source} reason={reason}")
    if not apply:
        print("CHECK_NEEDS_PATCH")
        return 2

    try:
        if state["phase"] != "retired":
            # tombstone 先落地，restore 中途 crash 也能在下次重跑補完。
            state = dict(
                state,
                phase="retiring",
                retire_reason=reason,
                retired_at=int(time.time()),
            )
            write_temporary_state(spec, state_dir, state)
        if restore:
            _, backup_path = temporary_state_paths(spec, state_dir)
            refuse_symlink(backup_path)
            original = backup_path.read_bytes()
            if sha256_bytes(original) != state["original_sha256"]:
                raise RuntimeError(
                    f"{backup_path}: backup does not match recorded original; refusing to restore"
                )
            if (
                sha256_bytes(generate_temporary_patch(spec, original))
                != state["patched_sha256"]
            ):
                raise RuntimeError(
                    f"{backup_path}: recorded patch does not match this tool"
                )
            if source.read_bytes() == current:
                atomic_write(source, original, source)
                if source.read_bytes() != original:
                    raise RuntimeError(f"post-verify failed: {source}")
                print(f"RESTORED file {source}")
            else:
                print(f"KEPT file {source}: changed during retirement; left untouched")
        else:
            print(f"KEPT file {source}: not our patched bytes; left untouched")
        write_temporary_state(spec, state_dir, dict(state, phase="retired"))
    except (OSError, RuntimeError) as error:
        print(f"WARNING temporary retire: {error}", file=sys.stderr)
        return 3

    print(f"RETIRED reason={reason}")
    return 0


def run_temporary(
    spec: dict,
    root: Path,
    state_dir: Path,
    apply: bool,
    game_version: str | None = None,
    retire: bool = False,
) -> int:
    try:
        state = load_temporary_state(spec, state_dir)
        source = resolve_patch_path(root, spec["patch"]["path"], game_version)
        refuse_symlink(source)
        try:
            current = source.read_bytes()
        except FileNotFoundError:
            if state is None:
                raise
            if state["phase"] != "retired":
                if not apply:
                    print("CHECK_NEEDS_RETIRE source missing")
                    return 2
                write_temporary_state(
                    spec,
                    state_dir,
                    dict(
                        state,
                        phase="retired",
                        retire_reason="source missing",
                        retired_at=int(time.time()),
                    ),
                )
            print("RETIRED source missing; no file restored")
            return 0
    except (OSError, RuntimeError) as error:
        print(f"WARNING temporary: {error}", file=sys.stderr)
        return 3

    print(f"SOURCE file {source}")

    # 退場過的一律不再套用；未完成的 restore 不需要網路就能補完。
    if state is not None and state["phase"] in ("retiring", "retired"):
        return retire_temporary(
            spec,
            state_dir,
            state,
            source,
            current,
            apply,
            state.get("retire_reason") or "resumed",
        )

    if retire:
        if state is None:
            print("NOT_APPLIED")
            return 0
        return retire_temporary(
            spec, state_dir, state, source, current, apply, "manual"
        )

    if state is not None and sha256_bytes(current) not in (
        state["original_sha256"],
        state["patched_sha256"],
    ):
        return retire_temporary(
            spec, state_dir, state, source, current, apply, "source replaced"
        )

    # 本機 mod.info 先看：Steam metadata 有快取延遲，modversion 一變就先退場。
    try:
        modversion = read_modversion(spec, source)
    except (OSError, RuntimeError) as error:
        print(f"WARNING temporary: {error}; file left untouched", file=sys.stderr)
        return 3
    print(f"LOCAL modversion={modversion} pinned={spec['pinned_modversion']}")

    if modversion != spec["pinned_modversion"]:
        if state is None:
            print(
                f"WARNING temporary: local modversion {modversion} != pinned "
                f"{spec['pinned_modversion']}; refusing to patch",
                file=sys.stderr,
            )
            return 3
        return retire_temporary(
            spec,
            state_dir,
            state,
            source,
            current,
            apply,
            f"local modversion {modversion}",
        )

    try:
        updated = fetch_workshop_updated(spec["workshop_id"])
    except (OSError, RuntimeError) as error:
        print(
            f"WARNING temporary: steam metadata unavailable ({error}); file left untouched",
            file=sys.stderr,
        )
        return 3
    print(f"UPSTREAM time_updated={updated} pinned={spec['pinned_revision']}")

    if state is None:
        if updated != spec["pinned_revision"]:
            print(
                f"WARNING temporary: upstream revision {updated} != pinned "
                f"{spec['pinned_revision']}; refusing to patch",
                file=sys.stderr,
            )
            return 3
        return apply_temporary(spec, state_dir, source, current, apply)

    current_hash = sha256_bytes(current)
    if updated != spec["pinned_revision"]:
        return retire_temporary(
            spec,
            state_dir,
            state,
            source,
            current,
            apply,
            f"upstream revision {updated}",
        )
    if current_hash == state["patched_sha256"]:
        print(f"OK file {source}")
        print("ALREADY_PATCHED")
        return 0
    if current_hash == state["original_sha256"]:
        # 上一輪在寫 state 之後、換檔之前中斷；來源仍是已驗證的原檔，補完即可。
        return apply_temporary(spec, state_dir, source, current, apply)
    return retire_temporary(
        spec,
        state_dir,
        state,
        source,
        current,
        apply,
        f"source replaced sha256={current_hash}",
    )


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
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--psr-output", type=Path, metavar="FILE", help="generate a SHA-pinned PSR copy outside Workshop")
    mode.add_argument("--psr-temporary", type=Path, metavar="STATE_DIR", help="patch PSR temporarily and retire on upstream updates")
    parser.add_argument("--psr-retire", action="store_true", help="with --psr-temporary: retire without network access")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.psr_retire and not args.psr_temporary:
        parser.error("--psr-retire requires --psr-temporary STATE_DIR")

    if args.self_test:
        self_test()
        return 0

    game_version = args.game_version or detect_game_version(args.console)
    if game_version:
        print(f"GAME_VERSION {game_version}")
    if args.psr_output:
        return run_psr_output(args.root, args.psr_output, args.apply, game_version)
    if args.psr_temporary:
        return run_temporary(PSR_LIFECYCLE, args.root, args.psr_temporary, args.apply, game_version, args.psr_retire)
    return run(args.root, args.backup_root, args.apply, game_version=game_version)


if __name__ == "__main__":
    raise SystemExit(main())
