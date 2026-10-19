#!/usr/bin/env python3
"""Isolated RCON wire and recovery checks; never touches a game process."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("health", Path(__file__).with_name("pz-health-watch.py"))
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class HealthTests(unittest.TestCase):
    def wire(self, mode):
        with tempfile.TemporaryDirectory() as directory, socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            ini = Path(directory) / "server.ini"
            ini.write_text(f"RCONPort={listener.getsockname()[1]}\nRCONPassword=test-only\n")
            commands = []
            errors = []

            def server():
                try:
                    with listener.accept()[0] as connection:
                        connection.settimeout(2)
                        def exact(size):
                            data = b""
                            while len(data) < size:
                                piece = connection.recv(size - len(data))
                                if not piece:
                                    raise EOFError()
                                data += piece
                            return data
                        def receive():
                            size = struct.unpack("<i", exact(4))[0]
                            data = exact(size)
                            return struct.unpack("<ii", data[:8]), data[8:-2]
                        def send(request_id, kind, body=b""):
                            data = struct.pack("<ii", request_id, kind) + body + b"\0\0"
                            # TCP fragmentation must not turn a live server into a failure.
                            for byte in struct.pack("<i", len(data)) + data:
                                connection.sendall(bytes([byte]))
                        self.assertEqual(receive(), ((1, 3), b"test-only"))
                        send(1, 0)
                        send(-1 if mode == "auth" else 1, 2)
                        if mode == "auth":
                            return
                        commands.append(receive())
                        if mode == "hung":
                            time.sleep(0.25)
                        else:
                            send(2, 0, b"Players connected (0): ")
                except Exception as error:
                    errors.append(error)
            thread = threading.Thread(target=server)
            thread.start()
            try:
                if mode == "hung":
                    with self.assertRaises(TimeoutError):
                        health.probe(ini, timeout=0.15)
                elif mode == "auth":
                    with self.assertRaises(health.ProbeConfigError):
                        health.probe(ini, timeout=1)
                else:
                    health.probe(ini, timeout=1)
            finally:
                thread.join(3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])
            if mode != "auth":
                self.assertEqual(commands, [((2, 2), b"players")])

    def test_main_thread_reply_not_merely_tcp_or_auth(self):
        for mode in ("healthy", "hung", "auth"):
            with self.subTest(mode=mode):
                self.wire(mode)

    def test_recovery_and_safety_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            locks = root / "lgsm/lock"
            locks.mkdir(parents=True)
            started = locks / "pzserver-started.lock"
            started.write_text(str(int(time.time()) - 1200))
            args = argparse.Namespace(root=root, update_lock=root / "update", flow_lock=root / "flow", check_only=False)
            current = (123, "456")
            with patch.object(health, "identity", return_value=current), patch.object(health, "probe") as probe, \
                 patch.object(health.time, "sleep") as sleep, patch.object(health.subprocess, "run") as restart, \
                 patch.object(health, "alive", return_value=False):
                restart.return_value.returncode = 0
                health.run(args)
                restart.assert_not_called()
                probe.side_effect = [TimeoutError(), None]
                health.run(args)
                restart.assert_not_called()
                probe.side_effect = health.ProbeConfigError("auth")
                with self.assertRaises(health.ProbeConfigError):
                    health.run(args)
                restart.assert_not_called()
                probe.side_effect = OSError("local probe resource failure")
                with self.assertRaises(OSError):
                    health.run(args)
                restart.assert_not_called()
                probe.side_effect = TimeoutError()
                for name in ("manual-stop.inhibit", "pzserver-starting.lock", "pzserver-stopping.lock", "update.lock", "backup.lock"):
                    flag = locks / name
                    flag.touch()
                    health.run(args)
                    flag.unlink()
                    restart.assert_not_called()
                started.write_text(str(int(time.time())))
                health.run(args)
                restart.assert_not_called()
                started.write_text(str(int(time.time()) - 1200))
                with patch.object(health, "identity", side_effect=[current, current, current, (999, "789")]):
                    health.run(args)
                restart.assert_not_called()
                with args.flow_lock.open("a") as lock:
                    health.fcntl.flock(lock, health.fcntl.LOCK_EX | health.fcntl.LOCK_NB)
                    health.run(args)
                restart.assert_not_called()
                health.run(args)
                self.assertEqual([call.args[0][-1] for call in restart.call_args_list], ["stop", "start"])
                health.run(args)
                self.assertEqual(restart.call_count, 2, "cooldown must prevent repeated recovery")
                self.assertTrue((locks / "pz-health-recovery.json").is_file())

    def test_start_runs_only_after_the_old_process_exits(self):
        # 2026-10-20 03:54: LinuxGSM stop reported Stopped once tmux was gone while the old JVM was still in
        # its shutdown hook; restart started a second pre-touched 40 GB heap and the container OOM killer
        # killed the old JVM mid-save.
        cases = {"exits during the wait": (5, False, ["stop", "start"]),
                 "exits only after SIGKILL": (None, True, ["stop", "kill", "start"]),
                 "survives SIGKILL": (None, False, ["stop", "kill"])}
        for name, (exits_after, dies_on_kill, expected) in cases.items():
            with self.subTest(name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                locks = root / "lgsm/lock"
                locks.mkdir(parents=True)
                (locks / "pzserver-started.lock").write_text(str(int(time.time()) - 1200))
                args = argparse.Namespace(root=root, update_lock=root / "update", flow_lock=root / "flow", check_only=False)
                events, proc = [], {"polls": 0, "killed": False}

                def is_alive(expected_id):
                    self.assertEqual(expected_id, (123, "456"))
                    if proc["killed"] and dies_on_kill:
                        return False
                    if exits_after is not None and proc["polls"] >= exits_after:
                        return False
                    proc["polls"] += 1
                    return True

                def linuxgsm(argv, **kwargs):
                    if argv[-1] == "start":
                        self.assertFalse(is_alive((123, "456")), "start while the old JVM is still alive")
                    events.append(argv[-1])
                    return argparse.Namespace(returncode=0)

                def kill(pid, sig):
                    self.assertEqual((pid, sig), (123, health.signal.SIGKILL))
                    events.append("kill")
                    proc["killed"] = True

                with patch.object(health, "identity", return_value=(123, "456")), \
                     patch.object(health, "probe", side_effect=TimeoutError()), \
                     patch.object(health.time, "sleep"), \
                     patch.object(health, "alive", side_effect=is_alive), \
                     patch.object(health.subprocess, "run", side_effect=linuxgsm), \
                     patch.object(health.os, "kill", side_effect=kill):
                    if "start" in expected:
                        health.run(args)
                    else:
                        with self.assertRaises(RuntimeError):
                            health.run(args)
                self.assertEqual(events, expected)

    def test_planted_symlink_on_the_old_temp_name_cannot_redirect_the_state_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            locks = root / "lgsm/lock"
            locks.mkdir(parents=True)
            (locks / "pzserver-started.lock").write_text(str(int(time.time()) - 1200))
            victim = root / "victim"
            victim.write_text("untouched")
            victim.chmod(0o644)
            before = victim.stat()
            (locks / "pz-health-recovery.tmp").symlink_to(victim)
            args = argparse.Namespace(root=root, update_lock=root / "update", flow_lock=root / "flow", check_only=False)
            with patch.object(health, "identity", return_value=(123, "456")), \
                 patch.object(health, "probe", side_effect=TimeoutError()), \
                 patch.object(health.time, "sleep"), \
                 patch.object(health, "alive", return_value=False), \
                 patch.object(health.subprocess, "run") as restart:
                restart.return_value.returncode = 0
                health.run(args)
                self.assertEqual(restart.call_count, 2)
            self.assertEqual(victim.read_text(), "untouched")
            after = victim.stat()
            self.assertEqual((after.st_mode, after.st_uid, after.st_gid),
                             (before.st_mode, before.st_uid, before.st_gid))
            state = locks / "pz-health-recovery.json"
            self.assertFalse(state.is_symlink())
            self.assertEqual(json.loads(state.read_text())["pid"], 123)
            self.assertEqual([p.name for p in locks.iterdir() if p.name.endswith(".tmp")],
                             ["pz-health-recovery.tmp"], "recovery must not leave its own temp behind")

    @unittest.skipUnless(os.geteuid() == 0, "requires root to model the production launcher")
    def test_root_launcher_keeps_linuxgsm_state_owned_by_server(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            locks = root / "lgsm/lock"
            locks.mkdir(parents=True)
            os.chown(locks, 65534, 65534)
            started = locks / "pzserver-started.lock"
            started.write_text(str(int(time.time()) - 1200))
            os.chown(started, 65534, 65534)
            args = argparse.Namespace(root=root, update_lock=root / "update", flow_lock=root / "flow", check_only=False)

            def linuxgsm_permission_gate(*args, **kwargs):
                foreign = [p.name for p in locks.iterdir() if p.stat().st_uid != 65534]
                self.assertEqual(foreign, [], "LinuxGSM refuses restart when the root launcher leaves foreign-owned state")
                return argparse.Namespace(returncode=0)

            with patch.object(health, "identity", return_value=(123, "456")), \
                 patch.object(health, "probe", side_effect=TimeoutError()), \
                 patch.object(health.time, "sleep"), \
                 patch.object(health, "alive", return_value=False), \
                 patch.object(health.subprocess, "run", side_effect=linuxgsm_permission_gate) as restart:
                health.run(args)
                self.assertEqual(restart.call_count, 2)


if __name__ == "__main__":
    unittest.main()
