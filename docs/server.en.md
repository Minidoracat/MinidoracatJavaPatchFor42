# Server patches: how they work and operations notes

**English** | [繁體中文](server.md) · [Back to README](../README.md)

The build and deploy steps are in the [README](../README.md#server-build-and-deploy).

## How the patches work

Since 42.22 the official `projectzomboid.jar` is a signed jar, and mixing unsigned loose `.class` files into one of
its packages throws `SecurityException`. The patch set therefore ships as one **Java agent**
(`mdc-javapatch-agent.jar`): the official jar and its signature are untouched, and the agent swaps in the bytes
patched at build time as the official classes load. The patcher reads the original class from the jar with ASM
and changes it in place; nothing is decompiled or recompiled.

- **Shape-preserving surgery only**: call redirects to a static helper with the same stack effect and
  instruction length, in-method constant changes, and linear head/tail calls. Stack map frames are kept
  (`ClassWriter(0)`). Helpers are ordinary Java classes compiled against the game jar.
- **Per-method hit counts**: every patch site declares how many instructions it must match. After a game
  update a moved or changed site fails the build instead of patching the wrong place.
- **Vanilla premise checks**: `SmokeCheck` pins the structural facts each fix depends on (for example "this
  call is not null-checked"). When TIS fixes a bug, its check turns red and tells us to retire the patch.
- **Verification**: `LoadCheck` loads every class with `-Xverify:all`, `BytecodeVerify` runs ASM's data-flow
  verifier, and behavior tests run the real game classes in a bare JVM.
- **Kill switches**: every server patch reads a `-Dmdc.*` property (`0`/`off` restores vanilla; many offer an
  `observe` mode that measures without changing behavior).
- **Fail-closed install, all-or-nothing loading**: installing requires the agent jar SHA, jar identity and conflict
  gates to pass; at every start the launch gate and the agent compare both SHAs again, and a game update that
  replaces the jar leaves the whole set off (the server starts vanilla). The agent also stays off when a class it
  patches is already loaded or a loose class on the class path would shadow the patch, and if defining its helpers
  fails it restores the classes it already replaced. `[mdc-agent] ARMED` and a version banner
  (`server patch <commit> … jar=<sha>`) print at startup. The official `IntegrityReport` works as designed: the
  version string gains `agents=1` and the jar still reports original.

## Recommended server settings

Not patches, but part of the same work on a busy server:

| Setting | Change | Why |
|---|---|---|
| `PingLimit` | 400 → 800 | Ping kicks during login and reconnect storms dropped from 18 to 5 per day |
| `SaveWorldEveryMinutes` | 15 → 60 | Each full save pauses clients for 5–6 s on a busy server |
| `BackupsPeriod` | 30 → 120 | The built-in backup spends ~25 s compressing |

## Optional tools

- **Main-loop health check** (`scripts/pz-health-watch.py`, Linux/LinuxGSM): asks the local RCON for
  `players` to prove the main loop responds, and restarts only after three consecutive failures: stop,
  wait until the old JVM has exited (SIGKILL after 300 s), then start. Test:
  `python3 scripts/test_pz_health_watch.py`.
- **Native snapshot** (`scripts/native_snapshot.py`): hashes and symbol-diffs the server's native libraries
  after each game update, since TIS updates them independently of the jar.

## Repository layout

```text
build.ps1 / build-client.ps1   server build / client package build
patcher/src/                   ASM patcher, PatchConfig (every patch site), SmokeCheck, LoadCheck, BytecodeVerify
patcher/game/                  server runtime helpers
patcher/game-client*/          client fix helpers and the shared client core
patcher/game-profiler/         DevProfiler runtime
patcher/tests*/                behavior tests (server, client, installer, profiler)
deploy/                        server install.sh / uninstall.sh and gate tests
deploy-client/                 client installers (Manage-Patches.ps1 and .bat launchers; mac/Install-Patches.command, mac/install-online.sh)
patcher/installer-mac/         macOS client installer (mdc-mac-installer.jar, run with the game's own Java)
native-observer/               LD_PRELOAD / LD_AUDIT native guards, startup wrapper, tests
scripts/                       optional tools listed above
docs/                          detailed patch documentation, design notes, reports to TIS
lib/                           ASM 9.8 (BSD-3-Clause, see lib/LICENSE-ASM.txt)
```
