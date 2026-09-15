import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import apply_workshop_compat_patches as compat


class PsrOutputTests(unittest.TestCase):
    def setUp(self):
        self.parent = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.root = self.parent / "workshop"
        self.source = self.root / compat.PSR_SCAN_PATCH["path"].replace("{version}", "42.1")
        self.source.parent.mkdir(parents=True)
        anchor = compat.PSR_SCAN_PATCH["replacements"][0][0]
        self.original = ("local function scan()\n" + anchor + "\nend\n").encode()
        self.updated = compat.apply_replacements(self.original, compat.PSR_SCAN_PATCH)
        self.source.write_bytes(self.original)
        self.output = self.parent / "output" / "PowerBankObject_Server.lua"
        spec = dict(compat.PSR_SCAN_PATCH)
        spec["known_versions"] = ((compat.sha256_bytes(self.original), compat.sha256_bytes(self.updated)),)
        self.enterContext(patch.object(compat, "PSR_SCAN_PATCH", spec))

    def run_output(self, apply=True, output=None):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return compat.run_psr_output(self.root, output or self.output, apply, "42.20.4")

    def test_check_then_generate_never_changes_source(self):
        self.assertEqual(self.run_output(False), 2)
        self.assertFalse(self.output.parent.exists())
        self.assertEqual(self.run_output(), 0)
        self.assertEqual(self.output.read_bytes(), self.updated)
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(self.run_output(), 0)
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_unknown_upstream_is_not_generated(self):
        changed = self.original + b"-- upstream update\n"
        self.source.write_bytes(changed)
        self.assertEqual(self.run_output(), 3)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.source.read_bytes(), changed)

    def test_foreign_output_is_preserved(self):
        self.output.parent.mkdir()
        self.output.write_bytes(b"user data")
        self.assertEqual(self.run_output(), 3)
        self.assertEqual(self.output.read_bytes(), b"user data")

    def test_workshop_output_cannot_be_used_as_an_installer(self):
        for target in (self.source, self.root / "other.lua"):
            with self.subTest(target=target):
                self.assertEqual(self.run_output(output=target), 3)
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertFalse((self.root / "other.lua").exists())

    def test_source_update_during_generation_is_preserved(self):
        analyze = compat.analyze_file
        def replaced(*args, **kwargs):
            result = analyze(*args, **kwargs)
            self.source.write_bytes(b"new upstream content")
            return result
        with patch.object(compat, "analyze_file", side_effect=replaced):
            self.assertEqual(self.run_output(), 3)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.source.read_bytes(), b"new upstream content")

    def test_competing_output_writer_is_not_overwritten(self):
        open_path = Path.open
        def competing(path, mode="r", *args, **kwargs):
            if path == self.output and mode == "xb":
                with open_path(path, "wb") as stream:
                    stream.write(b"competing writer")
            return open_path(path, mode, *args, **kwargs)
        with patch.object(Path, "open", competing):
            self.assertEqual(self.run_output(), 3)
        self.assertEqual(self.output.read_bytes(), b"competing writer")
        self.assertEqual(self.source.read_bytes(), self.original)

    @unittest.skipIf(os.name == "nt", "Windows symlink creation requires separate permission")
    def test_symlink_output_is_refused(self):
        self.output.parent.mkdir()
        target = self.parent / "owned.lua"
        target.write_bytes(self.updated)
        self.output.symlink_to(target)
        self.assertEqual(self.run_output(), 3)
        self.assertEqual(target.read_bytes(), self.updated)

    def test_cli_modes_cannot_be_combined(self):
        with patch("sys.argv", ["compat", "--psr-output", "out.lua", "--psr-temporary", "state"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                compat.main()
        self.assertEqual(error.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
