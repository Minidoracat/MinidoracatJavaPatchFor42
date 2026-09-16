#!/usr/bin/env python3
"""Behavioural tests for deploy/core-launch.py.

    python3 native-observer/tests/test_core_launch.py        # policy only
    sudo python3 native-observer/tests/test_core_launch.py   # + real drop/arm/exec

The interesting half needs root: it forks, runs the production main() with a temp
config (fake serverfiles, fake wrapper, `nobody` as the target account) and lets it
really arm, really drop and really execve. The fake wrapper prints the uid, core
limit, coredump_filter, cwd, argv and environment it was handed, so every assertion
is about observed process state -- no stubs, no injected setters, no bypass branch.
"""

from __future__ import annotations

import importlib.util
import os
import pwd
import resource
import shutil
import sys
import tempfile
import traceback
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parent.parent / "deploy" / "core-launch.py"

_spec = importlib.util.spec_from_file_location("core_launch", MODULE_PATH)
core_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(core_launch)

FAKE_WRAPPER = """#!/bin/sh
printf 'WRAPPER uid=%s gid=%s core=%s filter=%s cwd=%s home=%s user=%s preload=%s ldpath=%s pythonpath=%s args=[%s]\\n' \\
    "$(id -u)" "$(id -g)" "$(ulimit -c)" "$(cat /proc/self/coredump_filter)" "$(pwd)" \\
    "${HOME:-unset}" "${USER:-unset}" "${LD_PRELOAD:-unset}" "${LD_LIBRARY_PATH:-unset}" \\
    "${PYTHONPATH:-unset}" "$*"
"""


class EnvironmentPolicy(unittest.TestCase):
    def setUp(self) -> None:
        self.account = pwd.getpwuid(os.getuid())
        self.config = dict(core_launch.CONFIG, serverfiles="/srv/pz")

    def test_caller_environment_is_not_forwarded(self) -> None:
        env = core_launch.build_env(
            {
                "LD_PRELOAD": "/tmp/evil.so",
                "LD_AUDIT": "/tmp/audit.so",
                "PYTHONPATH": "/tmp",
                "PYTHONHOME": "/tmp",
                "BASH_ENV": "/tmp/rc",
                "LD_LIBRARY_PATH": "/tmp/evil",
            },
            self.account,
            self.config,
        )
        self.assertEqual(set(env), {"PATH", "HOME", "USER", "LOGNAME", "LD_LIBRARY_PATH"})
        self.assertEqual(env["LD_LIBRARY_PATH"], "/srv/pz/linux64:/srv/pz:/srv/pz/jre64/lib")
        self.assertEqual(env["PATH"].split(":")[0], "/srv/pz/jre64/bin")

    def test_locale_is_forwarded_only_as_a_locale_name(self) -> None:
        kept = core_launch.build_env({"LANG": "en_US.UTF-8"}, self.account, self.config)
        self.assertEqual(kept["LANG"], "en_US.UTF-8")
        dropped = core_launch.build_env(
            {"LANG": "en_US.UTF-8:/tmp/x", "LC_ALL": "a b"}, self.account, self.config
        )
        self.assertNotIn("LANG", dropped)
        self.assertNotIn("LC_ALL", dropped)


class ArmingPolicy(unittest.TestCase):
    """Runs unprivileged: proves the two non-armed outcomes never half-arm."""

    def test_low_disk_disarms_before_touching_anything(self) -> None:
        before = resource.getrlimit(resource.RLIMIT_CORE)
        with tempfile.TemporaryDirectory() as tmp:
            filter_path = os.path.join(tmp, "filter")
            state, detail = core_launch.arm_core(
                dict(
                    core_launch.CONFIG,
                    core_dir=tmp,
                    min_free_bytes=1 << 60,
                    coredump_filter_path=filter_path,
                )
            )
            self.assertFalse(os.path.exists(filter_path))
        self.assertEqual(state, "DISARMED")
        self.assertIn("bytes free", detail)
        self.assertEqual(resource.getrlimit(resource.RLIMIT_CORE), before)

    def test_unusable_filter_fails_loudly_and_leaves_the_limit_alone(self) -> None:
        before = resource.getrlimit(resource.RLIMIT_CORE)
        with tempfile.TemporaryDirectory() as tmp:
            state, detail = core_launch.arm_core(
                dict(
                    core_launch.CONFIG,
                    core_dir=tmp,
                    min_free_bytes=0,
                    coredump_filter_path=tmp,  # a directory: the write must fail
                )
            )
        self.assertEqual(state, "ARM FAILED")
        self.assertIn("coredump_filter", detail)
        self.assertEqual(resource.getrlimit(resource.RLIMIT_CORE), before)


@unittest.skipUnless(os.geteuid() == 0, "needs root to raise the hard limit and drop uid")
class RealLaunch(unittest.TestCase):
    ARGS = ("-servername", "pz", "-x", "a b")

    def setUp(self) -> None:
        self.nobody = pwd.getpwnam("nobody")
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        os.chmod(self.tmp, 0o755)  # the dropped account has to traverse it
        self.serverfiles = os.path.realpath(os.path.join(self.tmp, "serverfiles"))
        os.mkdir(self.serverfiles, 0o755)
        self.wrapper = os.path.join(self.tmp, "run-with-pfguard.sh")
        Path(self.wrapper).write_text(FAKE_WRAPPER)
        os.chmod(self.wrapper, 0o755)  # created by root => root-owned, not group-writable
        self.base = dict(
            core_launch.CONFIG,
            account="nobody",
            wrapper=self.wrapper,
            serverfiles=self.serverfiles,
            core_dir=self.serverfiles,
            min_free_bytes=0,
        )

    def launch(self, args=ARGS, caller_env=None, **overrides):
        """Fork, run the real main() in the child, return (exit status, output)."""
        out = os.path.join(self.tmp, "out.txt")
        pid = os.fork()
        if pid == 0:  # child: main() either execs the wrapper or exits
            code = 0
            try:
                handle = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
                os.dup2(handle, 1)
                os.dup2(handle, 2)
                environ = dict(os.environ, **(caller_env or {}))
                core_launch.main(list(args), config=dict(self.base, **overrides), environ=environ)
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
            except BaseException:  # noqa: BLE001 - report, never fall through to the parent
                traceback.print_exc()
                code = 99
            sys.stdout.flush()
            sys.stderr.flush()
            os._exit(code)
        _, status = os.waitpid(pid, 0)
        return status, Path(out).read_text()

    def wrapper_line(self, output: str) -> str:
        lines = [line for line in output.splitlines() if line.startswith("WRAPPER ")]
        self.assertEqual(len(lines), 1, f"expected exactly one wrapper launch in:\n{output}")
        return lines[0]

    def test_armed_launch_drops_privileges_and_hands_over_a_usable_core_limit(self) -> None:
        status, output = self.launch(caller_env={"LD_PRELOAD": "/tmp/evil.so", "PYTHONPATH": "/tmp"})
        self.assertEqual(status, 0, output)
        line = self.wrapper_line(output)
        self.assertIn(f"uid={self.nobody.pw_uid} gid={self.nobody.pw_gid} ", line)
        self.assertIn("core=unlimited ", line)
        self.assertIn("filter=00000031 ", line)
        self.assertIn(f"cwd={self.serverfiles} ", line)
        self.assertIn(f"home={self.nobody.pw_dir} user=nobody ", line)
        self.assertIn("preload=unset ", line)
        self.assertIn("pythonpath=unset ", line)
        self.assertIn(f"ldpath={self.serverfiles}/linux64:{self.serverfiles}:{self.serverfiles}/jre64/lib ", line)
        self.assertTrue(line.endswith("args=[-servername pz -x a b]"), line)

    def test_low_disk_launches_disarmed_instead_of_refusing_to_start(self) -> None:
        status, output = self.launch(min_free_bytes=1 << 60)
        self.assertEqual(status, 0, output)
        self.assertIn("CORE DISARMED:", output)
        self.assertIn("core dumps disabled", output)
        line = self.wrapper_line(output)
        self.assertIn("core=0 ", line)
        self.assertTrue(line.endswith("args=[-servername pz -x a b]"), line)

    def test_failed_arm_is_reported_and_never_looks_armed(self) -> None:
        status, output = self.launch(coredump_filter_path=self.tmp)
        self.assertNotEqual(status, 0, output)
        self.assertIn("CORE ARM FAILED", output)
        self.assertNotIn("WRAPPER ", output)

    def test_root_target_account_is_refused_before_exec(self) -> None:
        status, output = self.launch(account="root")
        self.assertNotEqual(status, 0)
        self.assertIn("FATAL", output)
        self.assertIn("refusing to run the game as root", output)
        self.assertNotIn("WRAPPER ", output)

    def test_wrapper_owned_by_the_game_account_is_refused_before_exec(self) -> None:
        os.chown(self.wrapper, self.nobody.pw_uid, self.nobody.pw_gid)
        status, output = self.launch()
        self.assertNotEqual(status, 0)
        self.assertIn("expected root", output)
        self.assertNotIn("WRAPPER ", output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
