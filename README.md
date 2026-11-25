# MinidoracatJavaPatchFor42

**English** | [繁體中文](README.zh-TW.md)

Unofficial bytecode patches for **Project Zomboid Build 42** (currently **42.22.2**). They fix vanilla bugs and hot spots we found while running a busy multiplayer server. There are two parts:

- **Client patch package** for players (Windows and macOS): fixes invisible players and zombies, several disconnects and vehicle teleports. [What it fixes](#client-patches-what-they-fix) · [Install](#client-patches-install)
- **Server patches** for dedicated server owners: keep the main loop from freezing, stop chunks, animals and vehicles from being lost, and cut main-thread time and bandwidth. [What they fix](#server-patches-what-they-fix) · [Build and deploy](#server-build-and-deploy)

Both load as a Java agent; the official `projectzomboid.jar` is not modified. After a game update the patches switch themselves off until a version built for the new game is installed. Every patch has a written root-cause analysis: see [Documentation](#documentation).

> **For The Indie Stone developers**
>
> Everything here was diagnosed on a production server with thread dumps, JFR, heartbeat counters and packet captures, and every fix was checked instruction by instruction against the shipped `projectzomboid.jar`. Several of these issues were fixed in 42.20.2–42.22.2 (see [Fixed upstream](docs/catalog.en.md#fixed-upstream)); thank you. The **TIS** column of the [patch catalog](docs/catalog.en.md) shows which ones we have reported on the forum and which are still waiting. We are happy to share logs, packet statistics or test builds for any item: open an issue here or contact **Minidoracat** on the forum.

## Client patches: what they fix

<a id="client-patches-release-package"></a>

- **Invisible players, zombies and vehicles.** After playing for a while, only a shadow and a name tag show until the game is restarted. The package fixes the texture buffer leaks behind it; the standard variant also raises the texture limit. ([2j](docs/patches.en.md#2j))
- **Disconnects near player-built buildings.** Two different bugs disconnect you to the main menu when chunks load while someone builds or demolishes nearby. ([W57](docs/patches.en.md#2bu), [W68](docs/patches.en.md#2cd))
- **Vehicle teleports.** Driving into an area that has not loaded yet can throw the car tens or hundreds of tiles away, or pull the driver back to an old position, sometimes into water. ([W61](docs/patches.en.md#2by), [W62–W63](docs/patches.en.md#2bz))
- **Vehicle under the floor.** A collision can push the car under the floor, and the screen goes black. ([W65](docs/patches.en.md#2ca))
- **Frame rate.** Modded vehicles with moving parts (KI5 and others) are re-animated every frame even when parked, and floor blood after a horde is scanned splat by splat. ([W54](docs/patches.en.md#2br), [W55](docs/patches.en.md#2bs), [W84](docs/patches.en.md#2cs))
- **Error flood from an item without a model**, on the ground or in the hand. ([W100](docs/patches.en.md#2dh), [W101](docs/patches.en.md#2di))
- **Car doors that will not open** (single player): leave a car with the engine running, walk away and come back, and the doors and hood are out of place. ([W108](docs/patches.en.md#2dp))

There are three variants and the installer recommends one: **standard** (32 GB of RAM or more), **low-memory** (the same fixes with vanilla's texture limit) and **compatible** (the vehicle fixes only, for use next to mods that replace game classes, such as PZ_Optimization). Every client patch is in the [patch catalog](docs/catalog.en.md#client-patches-release-package).

## Client patches: install

**Windows**: close the game, open PowerShell (press the Windows key, type `PowerShell`, press Enter), paste this line and press Enter:

```powershell
irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1 | iex
```

**macOS**: quit the game, open Terminal (Applications ▸ Utilities), paste this line and press Return:

```sh
/bin/bash -c "$(curl -fsSL https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-mac.sh)"
```

In the menu, choose `1` (install or update), then `1` (client fixes), press Enter to accept the recommended variant, press Enter when it asks about the game's memory limit to raise it to the recommended value, and type `Y` to confirm. To remove everything, paste the same line and choose `3`.

- You do not need to uninstall before a game update: when the game changes, the patches switch themselves off and the game runs vanilla. When a game update or Steam's file check has turned them off, paste the line again (for a new game version, once a package for it is released).
- If you use PZ_Optimization or another mod that replaces game classes, install the compatible variant (the installer recommends it).

Download without the command, the game memory limit, mods that replace game classes, Windows Smart App Control, macOS details and building the package yourself: see the [client install guide](docs/client.en.md).

## Server patches: what they fix

<a id="patch-catalog"></a>

- **Freezes and livelocks.** Errors that used to stop the main loop for minutes or hours are caught or prevented: "Entity is already registered" during chunk loading, the animal sound sort, half-constructed animals, the Bandit Raid story loop, and background threads writing engine and cell lists. ([stability](docs/catalog.en.md#stability-freezes-livelocks-and-crashes))
- **Lost data.** A write gate stops corrupt chunk saves before they wipe player builds. Offline catch-up for livestock is no longer doubled, skipped or cut short (unexplained deaths, slow growth, animals coming back unfed). Also fixed: hooked carcasses coming back alive, ghost ground items, leftover objects that cannot be removed, and buildings of whole maps disappearing when `Map=` lists a missing folder. ([data integrity](docs/catalog.en.md#data-integrity-lost-chunks-animals-and-vehicles))
- **Main-thread time.** Animal sight and hearing, item registration, player sight, parked vehicles, vehicle sounds and pathfinding rebuilds cost much less (for example animal hearing: 4.34 → 0.69 ms per frame). Most of these give the same results as vanilla; the riskier ones start in observe mode, which only measures. ([performance](docs/catalog.en.md#performance-main-thread-time))
- **Bandwidth.** Animal snapshot resends (38–40% of outbound traffic), crafting station broadcasts (17.5%) and a `VehicleCollide` loop (239 packets/s from one client). ([network](docs/catalog.en.md#network-bandwidth-and-resend-loops))
- **Multiplayer bugs.** Timed actions stuck at 100%, invisible zombies, teammates missing from the map, vehicles stuck under the floor, livestock zones lost or duplicated, PVE deaths announced as kills. ([multiplayer correctness](docs/catalog.en.md#multiplayer-correctness))
- **Accounts and abuse.** Server-side checks on more kinds of client requests (builds, object sprites, light settings, item data, clothing), IP bans that also work in Steam mode, an account limit per Steam ID, and logs of walking teleports and of vehicles moved by someone who is not driving them. ([accounts and abuse](docs/catalog.en.md#accounts-and-abuse))
- **Log noise.** Known spam is dropped; anticheat and unknown warnings still print. ([log noise](docs/catalog.en.md#log-noise))
- **Linux native crashes.** Guards for a crash and a memory leak in the pathfinding library and for an out-of-bounds read in Steam's networking library. ([native guards](docs/catalog.en.md#native-guards-linux-dedicated-server))

Every server patch has a `-Dmdc.*` switch that restores vanilla. Every patch, with its TIS report status, is in the [patch catalog](docs/catalog.en.md).

### Results on our server

Our server has daily peaks of 68–95 concurrent players (254 slots). The dedicated server's main loop is single-threaded and capped at 10 FPS, so every millisecond counts. Some examples of what changed:

| Area | Before | After |
|---|---|---|
| Console noise | `Send Toxic Building` alone was 45.5% of all console lines | gone; other noise patterns filtered, anticheat warnings kept |
| Chunk corruption ("Blam" wipes) | 43 player-built chunks wiped after `SANITY CHECK FAIL` | first night: 8 corrupt writes blocked, nothing lost; 0 flagged in 2.9 M writes after the root-cause fix |
| Main-loop freezes | 13-minute and 114-minute freezes, a server-wide livelock, 5–16 s stalls | no recurrence since the guards went live |
| Animal hearing scan | 4.34 ms per frame (4.3% of the main thread) | 0.69 ms (0.6%) |
| Animal line of sight | ~16 ms per frame (~9% of the main loop) at ~50 players | spatial prefilter, identical results (benchmark 4.8 ms → 0.25 ms per frame) |
| `processItems` registration | ~30k-item list scanned linearly on every registration (~6% of the main thread) | identity index (benchmark 43 µs → 0.3 µs) |
| Animal full snapshots | 38–40% of outbound traffic, 87% of it resends | radius aligned with the client's loaded area, per-animal cooldown |
| `VehicleCollide` loop | 239 packets/s from one client, 180k warning lines in 2 hours | 9.8 packets/s |
| Crafting station broadcasts | `GameEntity` packets were 17.5% of outbound UDP | nearby players only, sent when the visible state changes |

At ~63 players the main loop held 9.4–10.1 FPS with every patch counter reporting zero anomalies.

## Server: build and deploy

Requirements: JDK 25 (the 42.22 jar is class file version 69) and the server's `projectzomboid.jar`.

```powershell
copy <serverfiles>\java\projectzomboid.jar work\
.\build.ps1        # patch → hit counts → linkage, bytecode and behavior checks → agent jar → load check under the signed jar → dist/
bash patcher/tests/install-roundtrip.sh   # two install/uninstall rounds in a temporary serverfiles (WSL or Git Bash)
```

```bash
# copy mdc-javapatch-agent.jar, agent-manifest.txt, install.sh and uninstall.sh from dist/ into one folder on the server:
bash install.sh    # agent SHA, jar identity and conflict gates; installs into serverfiles/mdc-javapatch/, active at the next start
bash uninstall.sh  # moves that folder aside to patch-disabled-*; vanilla at the next start
```

Start the server through `native-observer/deploy/run-with-pfguard.sh`: it adds `-javaagent` to `JAVA_TOOL_OPTIONS`
only after the agent jar and the game jar match their SHAs, and starts vanilla otherwise; the JVM configuration
file stays unchanged. Without that launch gate, add `-javaagent:<serverfiles>/mdc-javapatch/mdc-javapatch-agent.jar`
to the JVM options yourself (the agent also compares the jar SHA and stays off on a mismatch).

**After a game update** the agent sees the new jar and stays off, so the server runs vanilla instead of a new jar
with old patches. Rebuild against the new jar, check every patch site's context with `javap` (a matching hit count
does not prove the site means the same thing), then install again. Running `uninstall.sh` as soon as an update is
known is still recommended. Build-time checks and the deployment checklist are described in
[docs/patches.en.md §3](docs/patches.en.md#3).

How the patches work, recommended server settings, optional tools and the repository layout: see the [server guide](docs/server.en.md).

## Documentation

<a id="open-vanilla-issues"></a><a id="fixed-upstream"></a><a id="how-the-patches-work"></a>

| Document | Content |
|---|---|
| [Patch catalog](docs/catalog.en.md) | Every patch by area: what goes wrong in vanilla, what the patch does, TIS report status; vanilla bugs we have not patched; patches retired because TIS fixed the issue |
| [Client install guide](docs/client.en.md) | Every install option, the game memory limit, mods that replace game classes, Smart App Control, macOS details, building the package |
| [Server guide](docs/server.en.md) | How the patches work, recommended server settings, optional tools, repository layout |
| [docs/patches.en.md](docs/patches.en.md) | Technical details of every patch: incident, root cause with bytecode evidence, fix, safety argument, checks, kill switch, measured results |
| [docs/patches.md](docs/patches.md) | The same in Traditional Chinese (the original, most detailed version) |
| [docs/report/](docs/report/) | Bug reports written for the TIS forum |
| [docs/optimization-summary.md](docs/optimization-summary.md) | Operations overview and timeline (Traditional Chinese) |
| `docs/*-design-*.md` | Design notes for the larger patches (Traditional Chinese) |
| [docs/specs/](docs/specs/) | `javap` evidence for the early patches (JSON) |

## Support

The mods are free. If you want to support the server and mod development:

[![Ko-fi](https://raw.githubusercontent.com/Minidoracat/workshop-resources/refs/heads/main/badges/badge_kofi.png)](https://ko-fi.com/minidoracat)

## License and disclaimer

MIT, see [LICENSE](LICENSE).

Third-party: the build uses OW2 ASM 9.8 (BSD-3-Clause), redistributed in `lib/` with its notice in
[lib/LICENSE-ASM.txt](lib/LICENSE-ASM.txt). ASM is not included in any release package.

- This is an **unofficial** project with no affiliation with, or endorsement by, The Indie Stone. Project
  Zomboid and its assets are copyright The Indie Stone.
- The repository contains no game files. **Client release packages contain modified copies of a few classes
  from `projectzomboid.jar`**, produced by the patcher in this repository from the unmodified official jar of
  the stated game version. They only work with a legitimately owned copy of that exact version, and the
  installer refuses any other. If The Indie Stone asks us to stop distributing them, we will.
- Modifying game files is at your own risk. `bash uninstall.sh` (server) and the client installer's "Remove everything"
  remove the patches; back up your server before installing.
- Rebuild and re-verify after every Project Zomboid update. Never apply an old patch to a new game version.
