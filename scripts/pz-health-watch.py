#!/usr/bin/env python3
"""Probe PZ's main-thread RCON command queue before allowing LinuxGSM monitoring.

Run under the existing monitor cron lock. Pass the same update and restart-flow
locks used by maintenance. Credentials stay in the server ini, never in argv/logs.
"""
import argparse
import contextlib
import datetime
import fcntl
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import tempfile
import time


class ProbeConfigError(Exception):
    pass


def log(message):
    print(f"{datetime.datetime.now().astimezone().isoformat()} [PZHealth] {message}", flush=True)


def probe(ini, timeout=15):
    values = dict(line.split("=", 1) for line in ini.read_text().splitlines()
                  if line.startswith(("RCONPort=", "RCONPassword=")))
    try:
        port = int(values["RCONPort"])
        password = values["RCONPassword"]
        if not 0 < port < 65536 or not password:
            raise ValueError()
    except (KeyError, ValueError) as error:
        raise ProbeConfigError("RCON configuration missing/invalid; refusing recovery") from error
    deadline = time.monotonic() + timeout
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        def exact(size):
            result = bytearray()
            while len(result) < size:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("RCON deadline")
                sock.settimeout(remaining)
                chunk = sock.recv(size - len(result))
                if not chunk:
                    raise ConnectionError("RCON disconnected")
                result.extend(chunk)
            return bytes(result)

        def send(request_id, kind, body):
            data = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\0\0"
            sock.sendall(struct.pack("<i", len(data)) + data)

        def receive():
            size = struct.unpack("<i", exact(4))[0]
            if not 10 <= size <= 4106:
                raise ProbeConfigError("unexpected RCON framing; refusing recovery")
            data = exact(size)
            request_id, kind = struct.unpack("<ii", data[:8])
            if data[-2:] != b"\0\0":
                raise ProbeConfigError("invalid RCON terminator; refusing recovery")
            return request_id, kind, data[8:-2]

        send(1, 3, password)
        while True:
            request_id, kind, _ = receive()
            if request_id == -1:
                raise ProbeConfigError("RCON authentication rejected; refusing recovery")
            if request_id == 1 and kind == 2:
                break
        send(2, 2, "players")
        while True:
            request_id, kind, body = receive()
            if request_id == 2 and kind == 0:
                if not re.match(rb"Players connected \(\d+\):", body):
                    raise ProbeConfigError("unexpected players response; refusing recovery")
                return


def identity(root):
    matches = []
    for directory in Path("/proc").iterdir():
        if not directory.name.isdecimal():
            continue
        try:
            if (directory / "exe").resolve() != root / "serverfiles/ProjectZomboid64":
                continue
            fields = (directory / "stat").read_text().rsplit(")", 1)[1].split()
            matches.append((int(directory.name), fields[19]))
        except (FileNotFoundError, ProcessLookupError):
            continue
    if len(matches) > 1:
        raise RuntimeError("multiple game processes; refusing recovery")
    return matches[0] if matches else None


def eligible(root, expected):
    locks = root / "lgsm/lock"
    if identity(root) != expected or expected is None:
        return False
    if (locks / "manual-stop.inhibit").exists():
        return False
    if any((locks / name).exists() for name in
           ("pzserver-starting.lock", "pzserver-stopping.lock", "update.lock", "backup.lock")):
        return False
    # Use LinuxGSM's wall-clock timestamp: LXC /proc/uptime and process start
    # ticks can have different time namespaces, making ps elapsed time invalid.
    started = int((locks / "pzserver-started.lock").read_text().splitlines()[0])
    return time.time() - started >= 600


def run(args):
    root = args.root.resolve()
    ini = root / "Zomboid/Server/pzserver.ini"
    if args.check_only:
        probe(ini)
        log("HEALTHY: main-thread players command completed")
        return
    with contextlib.ExitStack() as stack:
        for path in (args.update_lock, args.flow_lock):
            lock = stack.enter_context(path.open("a"))
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                log("SKIP: maintenance owns shared lock")
                return
        expected = identity(root)
        if not eligible(root, expected):
            log("SKIP: stopped, inhibited, starting or maintenance in progress")
            return
        state = root / "lgsm/lock/pz-health-recovery.json"
        if state.exists():
            last = json.loads(state.read_text())["attempted_at"]
            if time.time() - last < 1800:
                log("SKIP: recovery cooldown (30 minutes)")
                return
        for attempt in range(3):
            if not eligible(root, expected):
                log("SKIP: process or maintenance state changed")
                return
            try:
                probe(ini)
                log("HEALTHY: main-thread players command completed")
                return
            except (TimeoutError, ConnectionError) as error:
                log(f"UNRESPONSIVE: pid={expected[0]} attempt={attempt + 1}/3 error={type(error).__name__}")
            if attempt < 2:
                time.sleep(30)
        if not eligible(root, expected):
            log("SKIP: process or maintenance state changed before recovery")
            return
        # Write before invoking recovery: failures must not create a restart loop.
        # Exclusive create with a random name: the server account shares the lock
        # directory and could otherwise pre-plant a symlink on a predictable temp.
        handle, name = tempfile.mkstemp(dir=state.parent, prefix=".pz-health-recovery.", suffix=".tmp")
        try:
            with open(handle, "w") as stream:
                stream.write(json.dumps({"attempted_at": time.time(), "pid": expected[0]}))
                # LinuxGSM rejects foreign-owned files anywhere in its lock directory.
                owner = state.parent.stat()
                os.fchown(stream.fileno(), owner.st_uid, owner.st_gid)
                os.fchmod(stream.fileno(), 0o600)
            os.replace(name, state)
        except BaseException:
            with contextlib.suppress(OSError):
                os.unlink(name)
            raise
        log(f"RECOVER: pid={expected[0]} failed three main-thread probes; LinuxGSM restart")
        result = subprocess.run(
            ["timeout", "--kill-after=15s", "600s", "runuser", "-u", "pzserver", "--",
             str(root / "pzserver"), "restart"], cwd=root, check=False)
        if result.returncode:
            log(f"RECOVERY_FAILED: LinuxGSM exit={result.returncode}")
            raise RuntimeError(f"LinuxGSM recovery failed: exit={result.returncode}; inspect before retry")
        log("RESTART_DISPATCHED: game readiness is not yet confirmed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--update-lock", type=Path)
    parser.add_argument("--flow-lock", type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    if not args.check_only and (args.update_lock is None or args.flow_lock is None):
        parser.error("recovery requires both shared maintenance locks")
    try:
        run(args)
    except Exception as error:
        # Never print exception values from credential-bearing network/config data.
        log(f"ERROR: {type(error).__name__}; health check/recovery incomplete, inspect configuration and maintenance logs")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
