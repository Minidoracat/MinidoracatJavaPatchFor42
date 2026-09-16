#!/usr/bin/env python3
"""VFE 就地暫時修補的生命週期契約檢查；只讀寫暫存目錄，不碰真的 Workshop 原檔。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "compat", Path(__file__).with_name("apply_workshop_compat_patches.py")
)
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)

RELATIVE = compat.VFE_TEMPORARY["path"].replace(compat.VERSION_TOKEN, "42.18")
OTHER_REVISION = compat.VFE_PINNED_REVISION + 4242
AUTHOR_UPDATE = b"-- upstream 3.2.17 rewrite\nreturn nil\n"


def fixture_source() -> bytes:
    parts = []
    for before, _, expected_count in compat.VFE_TEMPORARY["replacements"]:
        parts.extend([before] * expected_count)
    return "\n-- fixture-separator\n".join(parts).encode("utf-8")


class VfeTemporaryTests(unittest.TestCase):
    def setUp(self):
        self.source_bytes = fixture_source()
        known = patch.dict(
            compat.VFE_TEMPORARY,
            {"known_sources": (compat.sha256_bytes(self.source_bytes),)},
        )
        known.start()
        self.addCleanup(known.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.root = base / "workshop"
        self.state_dir = base / "state"
        self.source = self.root / RELATIVE
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(self.source_bytes)
        self.mod_info = self.source.parents[3] / "mod.info"
        self.write_modversion(compat.VFE_PINNED_MODVERSION)
        self.patched = compat.generate_temporary_patch(compat.VFE_LIFECYCLE, self.source_bytes)
        self.state_path, self.backup_path = compat.temporary_state_paths(compat.VFE_LIFECYCLE, self.state_dir)
        self.fetch = None

    def write_modversion(self, value: str) -> None:
        self.mod_info.write_text(
            f"name=fixture\nid=VanillaFoodsExpanded\nmodversion={value}\nversionMin=42.18\n",
            encoding="utf-8",
        )

    def temporary(self, apply=True, updated=compat.VFE_PINNED_REVISION, retire=False):
        """updated 給 int 表示 Steam 回傳值，給 Exception 實例表示抓 metadata 會失敗。"""
        kwargs = (
            {"side_effect": updated}
            if isinstance(updated, BaseException)
            else {"return_value": updated}
        )
        with patch.object(compat, "fetch_workshop_updated", **kwargs) as fetch:
            code = compat.run_temporary(
                compat.VFE_LIFECYCLE, self.root, self.state_dir, apply, game_version="42.20.4", retire=retire
            )
        self.fetch = fetch
        return code

    def state(self) -> dict:
        return json.loads(self.state_path.read_text(encoding="utf-8"))

    def test_generated_patch_is_deterministic_and_identifies_its_source(self):
        patched = self.patched
        self.assertEqual(patched, compat.generate_temporary_patch(compat.VFE_LIFECYCLE, self.source_bytes))
        self.assertTrue(
            patched.startswith(compat.VFE_TEMPORARY_MARKER.encode("utf-8"))
        )
        self.assertIn(compat.sha256_bytes(self.source_bytes).encode("utf-8"), patched)
        self.assertIn(str(compat.VFE_PINNED_REVISION).encode("utf-8"), patched)

    def test_crlf_source_keeps_crlf(self):
        crlf = self.source_bytes.replace(b"\n", b"\r\n")
        with patch.dict(
            compat.VFE_TEMPORARY, {"known_sources": (compat.sha256_bytes(crlf),)}
        ):
            patched = compat.generate_temporary_patch(compat.VFE_LIFECYCLE, crlf)
        self.assertIn(b"\r\n", patched)
        self.assertEqual(patched.count(b"\n"), patched.count(b"\r\n"))

    def test_dry_run_writes_nothing(self):
        self.assertEqual(self.temporary(apply=False), 2)
        self.assertFalse(self.state_dir.exists())
        self.assertEqual(self.source.read_bytes(), self.source_bytes)

    def test_existing_foreign_backup_is_not_overwritten(self):
        self.state_dir.mkdir()
        foreign = b"unrelated or damaged backup"
        self.backup_path.write_bytes(foreign)
        self.assertEqual(self.temporary(), 3)
        self.assertEqual(self.backup_path.read_bytes(), foreign)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertFalse(self.state_path.exists())

    def test_author_update_during_apply_is_not_overwritten(self):
        write_state = compat.write_temporary_state

        def author_updates(spec, state_dir, state):
            write_state(spec, state_dir, state)
            if state["phase"] == "active":
                self.source.write_bytes(AUTHOR_UPDATE)

        with patch.object(compat, "write_temporary_state", side_effect=author_updates):
            self.assertEqual(self.temporary(), 3)
        self.assertEqual(self.source.read_bytes(), AUTHOR_UPDATE)
        self.assertEqual(self.temporary(updated=OTHER_REVISION), 0)
        self.assertEqual(self.source.read_bytes(), AUTHOR_UPDATE)

    def test_unknown_source_is_never_modified(self):
        foreign = self.source_bytes + b"\n-- upstream hotfix\n"
        self.source.write_bytes(foreign)
        self.assertEqual(self.temporary(), 3)
        self.assertEqual(self.source.read_bytes(), foreign)
        self.assertFalse(self.state_dir.exists())

    def test_first_apply_with_new_modversion_is_refused(self):
        self.write_modversion("3.2.17")
        self.assertEqual(self.temporary(), 3)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertFalse(self.state_dir.exists())

    def test_apply_backs_up_original_and_is_idempotent(self):
        self.assertEqual(self.temporary(), 0)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.backup_path.read_bytes(), self.source_bytes)
        state = self.state()
        self.assertEqual(state["phase"], "active")
        self.assertEqual(state["original_sha256"], compat.sha256_bytes(self.source_bytes))
        self.assertEqual(state["patched_sha256"], compat.sha256_bytes(self.patched))

        self.assertEqual(self.temporary(), 0)
        self.assertEqual(self.temporary(apply=False), 0)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.state()["phase"], "active")

    def test_interrupted_apply_is_completed_on_rerun(self):
        self.assertEqual(self.temporary(), 0)
        self.source.write_bytes(self.source_bytes)  # state 已寫、換檔前斷電
        self.assertEqual(self.temporary(), 0)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.state()["phase"], "active")

    def test_revision_change_restores_and_never_reapplies(self):
        self.assertEqual(self.temporary(), 0)
        self.assertEqual(self.temporary(updated=OTHER_REVISION), 0)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertEqual(self.state()["phase"], "retired")

        # 即使 Steam 又回報舊 revision，退場後永久不再套用。
        self.assertEqual(self.temporary(updated=compat.VFE_PINNED_REVISION), 0)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertEqual(self.state()["phase"], "retired")

    def test_local_modversion_change_retires_without_network(self):
        self.assertEqual(self.temporary(), 0)
        self.write_modversion("3.2.17")
        self.assertEqual(self.temporary(updated=AssertionError("network used")), 0)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertEqual(self.state()["phase"], "retired")
        self.assertFalse(self.fetch.called)

    def test_metadata_change_with_unchanged_source_does_not_rewrite(self):
        self.assertEqual(self.temporary(), 0)
        self.source.write_bytes(self.source_bytes)
        self.assertEqual(self.temporary(updated=OTHER_REVISION), 0)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertEqual(self.state()["phase"], "retired")

    def test_author_replacement_is_kept_and_retires(self):
        self.assertEqual(self.temporary(), 0)
        self.source.write_bytes(AUTHOR_UPDATE)
        self.assertEqual(self.temporary(), 0)
        self.assertEqual(self.source.read_bytes(), AUTHOR_UPDATE)
        self.assertEqual(self.state()["phase"], "retired")
        self.assertEqual(self.backup_path.read_bytes(), self.source_bytes)

        self.assertEqual(self.temporary(updated=AssertionError("network used")), 0)
        self.assertEqual(self.source.read_bytes(), AUTHOR_UPDATE)

    def test_author_update_during_retirement_is_not_overwritten(self):
        self.assertEqual(self.temporary(), 0)
        write_state = compat.write_temporary_state

        def author_updates(spec, state_dir, state):
            write_state(spec, state_dir, state)
            if state["phase"] == "retiring":
                self.source.write_bytes(AUTHOR_UPDATE)

        with patch.object(compat, "write_temporary_state", side_effect=author_updates):
            self.assertEqual(self.temporary(updated=OTHER_REVISION), 0)
        self.assertEqual(self.source.read_bytes(), AUTHOR_UPDATE)
        self.assertEqual(self.state()["phase"], "retired")

    def test_metadata_failure_changes_nothing(self):
        self.assertEqual(self.temporary(updated=OSError("dns down")), 3)
        self.assertFalse(self.state_dir.exists())
        self.assertEqual(self.source.read_bytes(), self.source_bytes)

        self.assertEqual(self.temporary(), 0)
        self.assertEqual(self.temporary(updated=OSError("dns down")), 3)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.state()["phase"], "active")

    def test_corrupt_backup_blocks_restore_and_rerun_finishes_it(self):
        self.assertEqual(self.temporary(), 0)
        self.backup_path.write_bytes(b"-- truncated backup\n")
        self.assertEqual(self.temporary(updated=OTHER_REVISION), 3)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.state()["phase"], "retiring")

        self.backup_path.write_bytes(self.source_bytes)
        # 補完中斷的退場不需要網路。
        self.assertEqual(self.temporary(updated=AssertionError("network used")), 0)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertEqual(self.state()["phase"], "retired")
        self.assertFalse(self.fetch.called)

    def test_manual_retire_runs_offline_and_dry_run_is_inert(self):
        self.assertEqual(self.temporary(), 0)
        self.assertEqual(
            self.temporary(apply=False, retire=True, updated=AssertionError("network")),
            2,
        )
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.state()["phase"], "active")

        self.assertEqual(
            self.temporary(retire=True, updated=AssertionError("network")), 0
        )
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        state = self.state()
        self.assertEqual(state["phase"], "retired")
        self.assertEqual(state["retire_reason"], "manual")
        self.assertFalse(self.fetch.called)

    def test_manual_retire_without_state_is_a_noop(self):
        self.assertEqual(
            self.temporary(retire=True, updated=AssertionError("network")), 0
        )
        self.assertFalse(self.state_dir.exists())
        self.assertEqual(self.source.read_bytes(), self.source_bytes)

    def test_foreign_state_is_refused(self):
        self.state_dir.mkdir(parents=True)
        self.state_path.write_text(
            json.dumps({"tool": "someone-else", "phase": "active"}), encoding="utf-8"
        )
        self.assertEqual(self.temporary(), 3)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)
        self.assertFalse(self.backup_path.exists())

    def test_state_is_bound_to_this_workshop_revision_and_original(self):
        self.assertEqual(self.temporary(), 0)
        saved = self.state()
        for key, value in (
            ("workshop_id", "different-item"),
            ("pinned_revision", OTHER_REVISION),
            ("original_sha256", "0" * 64),
        ):
            with self.subTest(key=key):
                self.state_path.write_text(json.dumps(dict(saved, **{key: value})))
                self.assertEqual(self.temporary(retire=True), 3)
                self.assertEqual(self.source.read_bytes(), self.patched)

    def test_unparsable_state_is_refused(self):
        self.state_dir.mkdir(parents=True)
        self.state_path.write_bytes(b"{not json")
        self.assertEqual(self.temporary(), 3)
        self.assertEqual(self.source.read_bytes(), self.source_bytes)

    def test_temporary_mode_does_not_touch_general_patches(self):
        argv = [
            "apply_workshop_compat_patches.py",
            "--root",
            str(self.root),
            "--vfe-temporary",
            str(self.state_dir),
            "--game-version",
            "42.20.4",
            "--apply",
        ]
        with patch.object(compat.sys, "argv", argv), patch.object(
            compat, "run", side_effect=AssertionError("general patch mode ran")
        ), patch.object(
            compat, "fetch_workshop_updated", return_value=compat.VFE_PINNED_REVISION
        ):
            self.assertEqual(compat.main(), 0)
        self.assertEqual(self.source.read_bytes(), self.patched)


class FakeResponse:
    def __init__(self, raw: bytes):
        self.raw = raw

    def read(self) -> bytes:
        return self.raw

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def details(**overrides) -> bytes:
    detail = {
        "publishedfileid": compat.VFE_WORKSHOP_ID,
        "result": 1,
        "time_updated": compat.VFE_PINNED_REVISION,
    }
    detail.update(overrides)
    return json.dumps({"response": {"publishedfiledetails": [detail]}}).encode("utf-8")


class FetchVfeUpdatedTests(unittest.TestCase):
    def fetch(self, raw: bytes):
        calls = {}

        def fake_urlopen(request, timeout=None):
            calls["request"] = request
            calls["timeout"] = timeout
            return FakeResponse(raw)

        with patch.object(compat.urllib.request, "urlopen", fake_urlopen):
            try:
                return compat.fetch_workshop_updated(compat.VFE_WORKSHOP_ID), calls
            finally:
                self.calls = calls

    def test_public_endpoint_posts_one_id_with_timeout(self):
        updated, calls = self.fetch(details())
        self.assertEqual(updated, compat.VFE_PINNED_REVISION)
        request = calls["request"]
        self.assertEqual(calls["timeout"], 10.0)
        self.assertEqual(request.get_method(), "POST")
        body = request.data.decode("ascii")
        self.assertIn("itemcount=1", body)
        self.assertIn(f"publishedfileids%5B0%5D={compat.VFE_WORKSHOP_ID}", body)
        self.assertNotIn("key=", body)

    def test_wrong_id_is_rejected(self):
        with self.assertRaises(RuntimeError):
            self.fetch(details(publishedfileid="1"))

    def test_failed_result_is_rejected(self):
        with self.assertRaises(RuntimeError):
            self.fetch(details(result=9))

    def test_non_integer_time_updated_is_rejected(self):
        with self.assertRaises(RuntimeError):
            self.fetch(details(time_updated="1789430355"))

    def test_extra_entries_are_rejected(self):
        payload = json.dumps(
            {
                "response": {
                    "publishedfiledetails": [
                        {
                            "publishedfileid": compat.VFE_WORKSHOP_ID,
                            "result": 1,
                            "time_updated": compat.VFE_PINNED_REVISION,
                        },
                        {"publishedfileid": "1", "result": 1, "time_updated": 1},
                    ]
                }
            }
        ).encode("utf-8")
        with self.assertRaises(RuntimeError):
            self.fetch(payload)

    def test_garbage_body_is_rejected(self):
        with self.assertRaises(RuntimeError):
            self.fetch(b"<html>rate limited</html>")


if __name__ == "__main__":
    unittest.main()
