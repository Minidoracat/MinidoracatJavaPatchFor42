#!/usr/bin/python3 -I
"""Root-only launch shim: guarantee the core limit, then drop to the game account.

    sudo <install-dir>/core-launch.py <game args...>

Why it exists: the account that launches the server gets a process whose RLIMIT_CORE
*hard* limit is 0, so neither the launcher nor the JVM can raise it and a crash leaves
no core. Only root can lift a hard limit, so the lift must happen before the drop --
rlimits and coredump_filter both survive setgid/setuid and execve, so arming here arms
the JVM that is eventually exec'd.

Everything this process runs is fixed in CONFIG below: the caller supplies game
arguments and nothing else -- no executable, no account, no paths, no environment.
No shell is involved (execve of the wrapper, argv forwarded verbatim).

Startup banner is always printed, and is never ambiguous:
    CORE ARMED       RLIMIT_CORE unlimited *and* coredump_filter verified by read-back
    CORE DISARMED    not enough free space for a core; launched with RLIMIT_CORE 0
    CORE ARM FAILED  arming was attempted and did not verify; startup is refused
Low disk space deliberately launches with core disabled; unexpected arming failures
are deployment errors and cannot silently reopen the startup observation gap.
Anything wrong with the privilege boundary itself (not root, target account resolves to
root, wrapper missing or not root-owned) is FATAL and never execs.
"""

from __future__ import annotations

import os
import pwd
import re
import resource
import stat
import sys

CONFIG = {
    # Example deployment paths: configure the root-owned copy before installation.
    # Unprivileged account that owns the game. Must not resolve to uid/gid 0.
    "account": "pzserver",
    # Absolute path of the existing launch wrapper. Root-owned, not writable by anyone
    # else; argv is forwarded to it verbatim.
    "wrapper": "/opt/pz-observer/run-with-pfguard.sh",
    # Game install: working directory and the only source of LD_LIBRARY_PATH.
    "serverfiles": "/srv/projectzomboid",
    # Filesystem that will receive the core (wherever kernel core_pattern points).
    "core_dir": "/srv/projectzomboid",
    # Below this much free space a core would fill the disk during a crash, which is
    # worse than having no core: existing policy, keep the server up without arming.
    "min_free_bytes": 12 * 1024**3,
    # 0x31 = anonymous private + ELF headers + private huge pages. Keeps the observer's
    # .bss ledger, leaves the shared (ZGC heap) mappings out of the dump.
    "coredump_filter": 0x31,
    "coredump_filter_path": "/proc/self/coredump_filter",
}

TAG = "[mdc-core-launch]"
# Locale names only: no separators that could carry a path or a second value.
SAFE_LOCALE = re.compile(r"\A[A-Za-z0-9._@-]+\Z")


def die(message: str) -> None:
    """Privilege-boundary or deployment error: there is nothing safe to launch."""
    sys.stderr.write(f"{TAG} FATAL {message}\n")
    sys.stderr.flush()
    raise SystemExit(1)


def announce(message: str) -> None:
    """Startup banner. Both streams, like the wrapper's own banners, then flushed --
    execve() does not flush Python's buffers."""
    for stream in (sys.stdout, sys.stderr):
        stream.write(f"{TAG} {message}\n")
        stream.flush()


def free_bytes(path: str) -> int:
    status = os.statvfs(path)
    return status.f_bavail * status.f_frsize


def arm_core(config: dict) -> tuple[str, str]:
    """Returns (state, detail) with state in ARMED / DISARMED / ARM FAILED.

    Order is load-bearing: the filter is written and read back *before* the limit is
    raised, so a failure can never leave an unlimited core paired with the default
    filter -- that dump would be the whole shared heap.
    """
    try:
        free = free_bytes(config["core_dir"])
    except OSError as exc:
        return "ARM FAILED", f"cannot stat {config['core_dir']}: {exc}"
    needed = config["min_free_bytes"]
    if free < needed:
        return "DISARMED", (
            f"{free} bytes free on {config['core_dir']}, need {needed}"
            " -- launching with core dumps disabled"
        )

    wanted = config["coredump_filter"]
    path = config["coredump_filter_path"]
    try:
        with open(path, "w") as handle:
            # The 0x prefix is not decoration: the kernel parses this value with base 0,
            # so a bare "31" would be read as decimal 31 (0x1f) and silently mis-arm.
            handle.write(f"{wanted:#x}\n")
        with open(path) as handle:
            got = int(handle.read().strip(), 16)
    except (OSError, ValueError) as exc:
        return "ARM FAILED", f"coredump_filter {path}: {exc}"
    if got != wanted:
        return "ARM FAILED", f"coredump_filter read back {got:#x}, wanted {wanted:#x}"

    unlimited = resource.RLIM_INFINITY
    try:
        resource.setrlimit(resource.RLIMIT_CORE, (unlimited, unlimited))
        limits = resource.getrlimit(resource.RLIMIT_CORE)
    except (OSError, ValueError) as exc:
        return "ARM FAILED", f"RLIMIT_CORE: {exc}"
    if limits != (unlimited, unlimited):
        return "ARM FAILED", f"RLIMIT_CORE read back {limits}"
    return "ARMED", (
        f"unlimited core, filter {wanted:#x}, {free} bytes free on {config['core_dir']}"
    )


def forbid_core() -> None:
    """Low-space launches have a hard 0, never whatever was inherited."""
    try:
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    except (OSError, ValueError) as exc:
        die(f"cannot force RLIMIT_CORE to 0 after a failed arm: {exc}")


def target_account(name: str) -> pwd.struct_passwd:
    try:
        account = pwd.getpwnam(name)
    except KeyError:
        die(f"account {name!r} does not exist")
    if account.pw_uid == 0 or account.pw_gid == 0:
        die(
            f"account {name!r} resolves to uid {account.pw_uid}/gid {account.pw_gid};"
            " refusing to run the game as root"
        )
    return account


def checked_wrapper(path: str) -> str:
    if not path.startswith("/"):
        die(f"wrapper {path!r} is not an absolute path")
    try:
        info = os.stat(path)
    except OSError as exc:
        die(f"wrapper {path}: {exc}")
    if not stat.S_ISREG(info.st_mode):
        die(f"wrapper {path} is not a regular file")
    if info.st_uid != 0:
        die(f"wrapper {path} is owned by uid {info.st_uid}, expected root")
    if info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        die(f"wrapper {path} is writable by non-root (mode {stat.S_IMODE(info.st_mode):04o})")
    if not info.st_mode & stat.S_IXUSR:
        die(f"wrapper {path} is not executable")
    return path


def build_env(inherited: dict, account: pwd.struct_passwd, config: dict) -> dict:
    """Allowlist, not a blocklist: LD_PRELOAD, LD_AUDIT, PYTHON*, BASH_ENV and anything
    else the caller set are absent because they were never copied. LD_LIBRARY_PATH is
    derived from the configured install, never from the caller."""
    serverfiles = config["serverfiles"]
    env = {
        "PATH": f"{serverfiles}/jre64/bin:/usr/local/bin:/usr/bin:/bin",
        "HOME": account.pw_dir,
        "USER": account.pw_name,
        "LOGNAME": account.pw_name,
        "LD_LIBRARY_PATH": f"{serverfiles}/linux64:{serverfiles}:{serverfiles}/jre64/lib",
    }
    # Locale is forwarded when it looks like a locale name: dropping it silently would
    # change the JVM's default charset, which is not this shim's business.
    for key in ("LANG", "LC_ALL"):
        value = inherited.get(key, "")
        if SAFE_LOCALE.match(value):
            env[key] = value
    return env


def drop_privileges(account: pwd.struct_passwd) -> None:
    """Supplementary groups first, then gid, then uid -- the reverse order would drop
    the privilege needed for the earlier calls. Real, effective and saved ids are all
    verified: anything left at 0 would let the game climb back to root."""
    try:
        os.initgroups(account.pw_name, account.pw_gid)
        os.setgid(account.pw_gid)
        os.setuid(account.pw_uid)
    except OSError as exc:
        die(f"privilege drop failed for {account.pw_name}: {exc}")
    uids, gids = os.getresuid(), os.getresgid()
    if uids != (account.pw_uid,) * 3 or gids != (account.pw_gid,) * 3:
        die(f"privilege drop incomplete: uid={uids} gid={gids}")


def main(argv: list[str], config: dict | None = None, environ: dict | None = None) -> None:
    config = CONFIG if config is None else config
    environ = os.environ if environ is None else environ

    if os.geteuid() != 0:
        die("must run as root (dedicated sudoers entry): only root can raise the"
            " RLIMIT_CORE hard limit, which is the entire point of this shim")
    account = target_account(config["account"])
    wrapper = checked_wrapper(config["wrapper"])

    state, detail = arm_core(config)
    if state == "ARM FAILED":
        die(f"CORE ARM FAILED: {detail}")
    if state != "ARMED":
        forbid_core()
    announce(f"CORE {state}: {detail}")

    env = build_env(environ, account, config)
    drop_privileges(account)
    # setuid() clears the dumpable flag, but execve() of a normal (non-setuid) program
    # the new uid can read sets it back to 1, which is why the wrapper and the game
    # binary must stay readable by the game account.
    try:
        os.chdir(config["serverfiles"])
    except OSError as exc:
        die(f"serverfiles {config['serverfiles']}: {exc}")
    announce(f"exec {wrapper} as {account.pw_name} with {len(argv)} game args")
    try:
        os.execve(wrapper, [wrapper, *argv], env)
    except OSError as exc:
        die(f"exec {wrapper}: {exc}")


if __name__ == "__main__":
    main(sys.argv[1:])
