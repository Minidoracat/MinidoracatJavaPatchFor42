import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import apply_workshop_compat_patches as compat


class PsrTemporaryTests(unittest.TestCase):
    def setUp(self):
        base = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.root, self.state_dir = base / "workshop", base / "state"
        self.spec = compat.PSR_LIFECYCLE
        self.source = self.root / self.spec["patch"]["path"].replace(
            "{version}", "42.1"
        )
        self.source.parent.mkdir(parents=True)
        anchor = self.spec["patch"]["replacements"][0][0]
        self.original = (
            "local function scan(obj, full)\n" + anchor + "\nend\n"
        ).encode()
        self.patched = compat.apply_replacements(self.original, self.spec["patch"])
        self.enterContext(
            patch.dict(
                self.spec["patch"],
                {
                    "known_versions": (
                        (
                            compat.sha256_bytes(self.original),
                            compat.sha256_bytes(self.patched),
                        ),
                    )
                },
            )
        )
        self.source.write_bytes(self.original)
        self.info = self.source.parents[5] / "mod.info"
        self.info.write_text(
            "id=PSR\nmodversion=" + self.spec["pinned_modversion"] + "\n"
        )
        self.state_path, self.backup = compat.temporary_state_paths(
            self.spec, self.state_dir
        )

    def run_patch(self, revision=None, apply=True, retire=False):
        options = (
            {"side_effect": revision}
            if isinstance(revision, Exception)
            else {
                "return_value": self.spec["pinned_revision"]
                if revision is None
                else revision
            }
        )
        with patch.object(compat, "fetch_workshop_updated", **options) as fetch:
            with (
                contextlib.redirect_stdout(io.StringIO()),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                result = compat.run_temporary(
                    self.spec, self.root, self.state_dir, apply, "42.20.4", retire
                )
        self.fetch = fetch
        return result

    def state(self):
        return json.loads(self.state_path.read_text())

    def test_dry_run_and_verified_apply(self):
        self.assertEqual(self.run_patch(apply=False), 2)
        self.assertFalse(self.state_dir.exists())
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(self.run_patch(), 0)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.backup.read_bytes(), self.original)
        self.assertEqual(self.state()["phase"], "active")
        self.fetch.assert_called_once_with(self.spec["workshop_id"])

    def test_workshop_update_restores_and_permanently_retires(self):
        self.assertEqual(self.run_patch(), 0)
        self.assertEqual(self.run_patch(self.spec["pinned_revision"] + 1), 0)
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(self.state()["phase"], "retired")
        self.assertEqual(self.run_patch(OSError("offline")), 0)
        self.fetch.assert_not_called()
        self.assertEqual(self.run_patch(), 0)
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_author_file_is_preserved_even_if_metadata_is_offline(self):
        self.assertEqual(self.run_patch(), 0)
        author = b"-- new author implementation\nreturn {}\n"
        self.source.write_bytes(author)
        self.assertEqual(self.run_patch(OSError("offline")), 0)
        self.fetch.assert_not_called()
        self.assertEqual(self.source.read_bytes(), author)
        self.assertEqual(self.state()["phase"], "retired")

    def test_removed_source_retires_without_recreating_author_file(self):
        self.assertEqual(self.run_patch(), 0)
        self.source.unlink()
        self.assertEqual(self.run_patch(apply=False), 2)
        self.assertEqual(self.state()["phase"], "active")
        self.assertEqual(self.run_patch(OSError("offline")), 0)
        self.fetch.assert_not_called()
        self.assertFalse(self.source.exists())
        self.assertEqual(self.state()["phase"], "retired")
        self.source.write_bytes(self.original)
        self.assertEqual(self.run_patch(), 0)
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_modversion_update_retires_without_network(self):
        self.assertEqual(self.run_patch(), 0)
        self.info.write_text("id=PSR\nmodversion=new-version\n")
        self.assertEqual(self.run_patch(OSError("offline")), 0)
        self.fetch.assert_not_called()
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(self.state()["phase"], "retired")

    def test_metadata_failure_does_not_guess_but_manual_retirement_works(self):
        self.assertEqual(self.run_patch(OSError("offline")), 3)
        self.assertFalse(self.state_path.exists())
        self.assertEqual(self.run_patch(), 0)
        self.assertEqual(self.run_patch(OSError("offline")), 3)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.state()["phase"], "active")
        self.assertEqual(self.run_patch(OSError("offline"), retire=True), 0)
        self.fetch.assert_not_called()
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_unknown_upstream_and_new_revision_cannot_be_installed(self):
        self.assertEqual(self.run_patch(self.spec["pinned_revision"] + 1), 3)
        self.assertFalse(self.state_path.exists())
        self.source.write_bytes(b"unknown source")
        self.assertEqual(self.run_patch(), 3)
        self.assertEqual(self.source.read_bytes(), b"unknown source")
        self.assertFalse(self.state_path.exists())

    def test_existing_foreign_backup_is_not_overwritten(self):
        self.state_dir.mkdir()
        self.backup.write_bytes(b"foreign backup")
        self.assertEqual(self.run_patch(), 3)
        self.assertEqual(self.backup.read_bytes(), b"foreign backup")
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_interrupted_retirement_never_reapplies(self):
        self.assertEqual(self.run_patch(), 0)
        self.backup.write_bytes(b"bad backup")
        self.assertEqual(self.run_patch(self.spec["pinned_revision"] + 1), 3)
        self.assertEqual(self.state()["phase"], "retiring")
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.backup.write_bytes(self.original)
        self.assertEqual(self.run_patch(OSError("offline")), 0)
        self.fetch.assert_not_called()
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(self.state()["phase"], "retired")

    def test_interrupted_apply_can_finish_on_same_revision(self):
        self.assertEqual(self.run_patch(), 0)
        self.source.write_bytes(self.original)
        self.assertEqual(self.run_patch(), 0)
        self.assertEqual(self.source.read_bytes(), self.patched)
        self.assertEqual(self.backup.read_bytes(), self.original)

    def test_author_update_during_apply_is_not_overwritten(self):
        writer = compat.write_temporary_state

        def author_update(spec, state_dir, state):
            writer(spec, state_dir, state)
            self.source.write_bytes(b"author wins")

        with patch.object(compat, "write_temporary_state", side_effect=author_update):
            self.assertEqual(self.run_patch(), 3)
        self.assertEqual(self.source.read_bytes(), b"author wins")
        self.assertEqual(self.run_patch(), 0)
        self.assertEqual(self.state()["phase"], "retired")

    def test_author_update_during_restore_is_not_overwritten(self):
        self.assertEqual(self.run_patch(), 0)
        writer = compat.write_temporary_state

        def author_update(spec, state_dir, state):
            writer(spec, state_dir, state)
            if state["phase"] == "retiring":
                self.source.write_bytes(b"new author file")

        with patch.object(compat, "write_temporary_state", side_effect=author_update):
            self.assertEqual(self.run_patch(retire=True), 0)
        self.assertEqual(self.source.read_bytes(), b"new author file")
        self.assertEqual(self.state()["phase"], "retired")

    def test_foreign_state_is_refused(self):
        self.assertEqual(self.run_patch(), 0)
        state = self.state()
        state["workshop_id"] = "3577903007"
        self.state_path.write_text(json.dumps(state))
        self.assertEqual(self.run_patch(), 3)
        self.assertEqual(self.source.read_bytes(), self.patched)

    @unittest.skipIf(
        os.name == "nt", "Windows symlink creation requires separate permission"
    )
    def test_backup_symlink_is_refused(self):
        self.state_dir.mkdir()
        target = self.state_dir.parent / "unrelated"
        target.write_bytes(b"unrelated")
        self.backup.symlink_to(target)
        self.assertEqual(self.run_patch(), 3)
        self.assertEqual(target.read_bytes(), b"unrelated")
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_cli_does_not_run_other_patch_modes(self):
        argv = [
            "compat",
            "--root",
            str(self.root),
            "--game-version",
            "42.20.4",
            "--psr-temporary",
            str(self.state_dir),
            "--apply",
        ]
        with (
            patch("sys.argv", argv),
            patch.object(
                compat,
                "fetch_workshop_updated",
                return_value=self.spec["pinned_revision"],
            ),
            patch.object(compat, "run", side_effect=AssertionError("other mode ran")),
        ):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(compat.main(), 0)
        self.assertEqual(self.source.read_bytes(), self.patched)

    def test_pinned_output_hash_is_enforced(self):
        with patch.dict(
            self.spec["patch"],
            {"known_versions": ((compat.sha256_bytes(self.original), "0" * 64),)},
        ):
            self.assertEqual(self.run_patch(), 3)
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertFalse(self.state_path.exists())


if __name__ == "__main__":
    unittest.main()
