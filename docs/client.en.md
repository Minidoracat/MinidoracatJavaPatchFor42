# Client patch package: install guide

**English** | [繁體中文](client.md) · [Back to README](../README.md)

What the package fixes: the [README](../README.md#client-patches-what-they-fix) has the short list and the [patch catalog](catalog.en.md#client-patches-release-package) has every patch.


For players. Close the game, open PowerShell (press the Windows key, type `PowerShell`, press Enter), paste this line
and press Enter:

```powershell
irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1 | iex
```

It downloads `MinidoracatClientPatches-<game version>-<package version>.zip` from the latest release, checks its
SHA-256 against the value built into the script, extracts it to a temporary folder and opens the installer's menu; the
temporary folder is deleted afterwards. The menu follows the Windows display language or regional format (Traditional
Chinese if either is Chinese, English otherwise); press `L` to switch. To install or update, choose `1` (install or
update), then `1` (client fixes), press Enter to accept the recommended variant (standard for 32 GB RAM or more,
low-memory otherwise, compatible when another mod already replaces the game classes the full variants patch), press
Enter when it asks about the game's memory limit to raise it to the recommended value, and type `Y` to confirm; when
it is done, press Enter, then `0` to exit. To pick another variant, choose `1` again; to remove everything or see the
status, paste the same line and choose `3` (remove everything) or `4` (status).

**Changing the game memory limit (from 0.3.7).** Item `5` of the main menu shows the current limit and the
recommendation for your computer. Choose `5`, then `1` for the recommended value, `2` to type your own (whole MB, at
least 3072 and at most your RAM; it warns above half of your RAM) or `3` to restore the game's default 3072 MB, and
confirm with `Y`. It only changes `-Xmx` in `ProjectZomboid64.json` (`Info.plist` on macOS), works with or without the
patches installed, and takes effect the next time the game starts. Removing the patches keeps the value; Steam's
"Verify integrity of game files" puts 3072 MB back. On Windows, PZ_Optimization may change the value itself when the
game starts.

To run one action without the menu, use `& ([scriptblock]::Create((irm <the URL above>))) status`; the trailing
arguments go to the installer unchanged (`status`, `uninstall -All`; `-Lang en` alone opens the menu in English). The
command runs `install-windows.ps1` from this repo's release page, so using it means trusting this repo and GitHub;
the SHA-256 check only catches a broken download or mismatched versions, not a hijacked repo. To read the script
first, run just `irm <that URL>`. For an older version, replace `releases/latest/download` with
`releases/download/client-v<package version>` (the file exists from 0.3.5 on; 0.3.5 installs right away instead of
opening the menu).

Without the command: download `MinidoracatClientPatches-<game version>-<package version>.zip` from
[Releases](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases) and check it against `SHA256SUMS.txt`
(`Get-FileHash .\MinidoracatClientPatches-*.zip`).

1. Close the game and extract the whole zip into any folder: your Desktop or Downloads is fine, it does not need
   to be in the game folder. The installer finds the game through Steam by itself, even on another drive, and
   asks you to paste the path only if it cannot (`-GameDir "<path>"` also works).
2. Run `Install-Patches.bat`, choose `1` (install or update), then `1` (client fixes).
3. Press Enter to accept the recommended variant. If it asks about the game's memory limit, press Enter to raise it
   to the recommended value. Then type `Y` to confirm.

The installer follows the Windows display language or regional format (Traditional Chinese if either is Chinese,
English otherwise); press `L` in the main menu or add `-Lang en` to switch. It only works on the exact game version it was built for and checks
the SHA-256 of `projectzomboid.jar` and of the package files. Since 0.3.0 the patches load as a Java agent (from
42.22 on the official jar is signed, and the old way of putting `.class` files into the game folder keeps the game
from starting): the installer puts `MinidoracatClientPatches.jar` and a small loader, `mdc-agent-loader.dll` (the
game's launcher needs it to load a Java agent; its source is in `patcher/agent-loader/`), into the game folder's
`.mdc-patches` and adds one `-agentpath:` launch option to `ProjectZomboid64.json`; the game's own files stay
untouched. Uninstalling removes only that option and keeps your other settings (a custom `-Xmx`, for example);
a memory limit you agreed to raise while installing is kept too (Steam's "Verify integrity of game files" puts 3072 MB
back). An interrupted install rolls
back. **No need to uninstall before a game update**: when the jar changes, the patches switch themselves off and the
game runs vanilla. Steam updates often restore the original `ProjectZomboid64.json`; the patches are then inactive
(the installer's status shows "installed but not active"), and pasting the command again (or running `Install-Patches.bat`
from the package for the new version) turns them back on. Do not delete the `.mdc-patches` folder by hand: with the
launch option still there and the files gone, the game does not start. Remove the patches by pasting the command
again and choosing `3`, or with `Uninstall-Patches.bat` (choose `A`). Removal only reads the install record in the game folder and a
newer installer can remove an older install, so you do not need to keep the extracted folder. Starting the game
through `ProjectZomboid64.bat` or `ShowConsole.bat` skips `ProjectZomboid64.json`, so the patches do not load.
Upgrading from 0.2.x: close the game and paste the command or run the new `Install-Patches.bat`; the `.class` files
the old version put into the game folder are removed in the same install. It also recognizes the old TexPipeline v3.0
package (the one that only came with `uninstall.bat`).

| Module | Purpose |
|---|---|
| `core` | Shared Lua bridge, installation fingerprint check and startup status; installed automatically |
| `client-fixes-standard` | Texture leak fixes, 4 GiB texture wait limit, chunk streaming log, player-built room disconnect fixes (two), vehicle animation speed-ups, vehicle teleport fixes (three), vehicle-under-the-floor fix, floor blood speed-up, fix for ground items without a model, fix for car doors that will not open after walking away with the engine running (32 GB RAM or more) |
| `client-fixes-lowmem` | The same fixes with the vanilla 50 MiB texture limit; excludes the other variants |
| `client-fixes-compat` | Only the vehicle fixes (vehicle animation speed-up, the three teleport fixes, vehicle under the floor, car doors that will not open after walking away with the engine running), for use next to mods that replace the other classes (for example PZ_Optimization); excludes the other variants |
| `profiler` | DevProfiler for mod developers (Java → Lua call timing, CSV/JFR export); its UI is the separate mod `MinidoracatDevProfilerFor42` |

**Mods that replace game classes.** Some mods replace game classes: they put `.class` files into the game folder, or,
like PZ_Optimization from 42.22 on, use a Java agent (launch option `-javaagent:pzopt/agent/pzopt-agent.jar`, which
replaces the classes under `pzopt/overrides/`). If one of them replaces a class this package patches, our patch for
that class would not take effect, so the installer stops with code 7 and changes nothing. Do not delete just the files
it lists: the rest of that mod still needs them, and the game closes when you join a server
([issue #1](https://github.com/Minidoracat/MinidoracatJavaPatchFor42/issues/1)). Either keep that mod and install
`client-fixes-compat` (the installer recommends it), or remove that mod with its own uninstaller (PZ_Optimization:
`Uninstall-PZ-Optimization.cmd` in the game folder) and install a full variant. The compatible version works next to
the PZ_Optimization agent; both installers put their own agent first, and either order works (from 0.3.8; both orders
tested at the Java class-loading level with PZ_Optimization revision 3d39148846, and the order with this package first
also run in single-player in the game). Up to 0.3.7, this package
placed before it made PZ_Optimization run entirely stock (its `agent.log` says
`already loaded … zombie.ai.State … the game runs stock`), so update to 0.3.8. If you installed a full variant first
and PZ_Optimization after it, this package switches itself off and the game runs unpatched; the installer's status
check tells you to install the compatible version. While PZ_Optimization's "Warm-start cache (Java AOT)" option is on,
the game loads classes from that cache and the installer refuses to install; turn it off first (it takes effect at the
next start, so start the game once more to the main menu and close it). Also, from 42.22 on, a `.class` file in the
same package as an official class makes Java throw `SecurityException` by itself (the installer warns about these),
so such mods built for 42.21 need an update from their authors.

**Windows Smart App Control.** The patches load through a small DLL (`mdc-agent-loader.dll`) that is not code-signed. On a PC where Windows 11 Smart App Control is enforcing, Windows blocks it and the game does not start at all (seen with 0.3.1 on one such PC). From 0.3.2 the installer checks this and stops with code 11 without changing anything; in evaluation mode it installs and warns. If the game stopped starting after Smart App Control switched to enforcing, remove everything by pasting the command again and choosing `3`, or with `Uninstall-Patches.bat`. We do not ask you to change that Windows setting.

**macOS.** Quit the game, open Terminal (Applications ▸ Utilities), paste this line and press Return:

```sh
/bin/bash -c "$(curl -fsSL https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-mac.sh)"
```

It downloads `MinidoracatClientPatches-<game version>-<package version>-macOS.zip` from the newest release, checks its SHA-256 against the value built into the script, and opens the installer's menu (the same as on Windows). The menu follows the first of your macOS preferred languages (Traditional Chinese if it is Chinese, English otherwise); press `L` to switch. To install or update, choose `1`, then `1`, press Return to accept the recommended variant, press Return when it asks about the game's memory limit to raise it, and confirm with `Y`; when it is done, press Return, then `0` to exit. To remove everything, paste the same line and choose `3`. Adding a space and `status` or `uninstall --all` to the end of the line runs that action without the menu; `--lang en` opens the menu in English. If you only see a line starting with `curl:`, the download failed; paste the line again. The line runs `install-mac.sh` from this repository's release page, so using it means trusting this repository and GitHub; the SHA-256 check catches broken or mismatched downloads, not a compromised repository. To read the script first, run `curl -fsSL <that URL> | less`. For an older package, use `releases/download/client-v<package version>/install-mac.sh` instead of `releases/latest/download/install-mac.sh` (up to 0.3.5 it installs right away instead of opening the menu).

Item `5` of the main menu changes the game memory limit, the same as on Windows (see above).

Without the command: download the `-macOS.zip` (check it with `shasum -a 256`), double-click it to extract it, open Terminal, type `bash ` (with a space), drag `Install-Patches.command` from the extracted folder into the window and press Return; the menus are the same as on Windows. Double-clicking `Install-Patches.command` does not work because macOS blocks scripts that are not notarized.

The installer runs with the Java that comes with the game, finds `Project Zomboid.app` through Steam (or asks you to drag it in, or reads `PZ_APP`), copies the same `MinidoracatClientPatches.jar` into `Project Zomboid.app/Contents/Java/.mdc-patches` and adds one `-javaagent:` option to `JVMOptions` in `Project Zomboid.app/Contents/Info.plist`. The Mac launcher loads a Java agent directly, so there is no loader DLL. The game starts with a 3 GB Java memory limit (`-Xmx3072m`), which is not enough on a server with many mods (the game closes a few minutes after joining with `OutOfMemoryError`); when the limit is below the recommendation for your Mac (8 GB with 32 GB of RAM or more, 6 GB with 16 GB, 4 GB with 12 GB; only 8 GB was tested in the real game), the installer asks whether to raise it. Apart from that memory limit, only the one launch-option line of `Info.plist` changes, and removing everything (menu `3`) gives back the original file byte for byte; a memory limit you raised stays, because it is the game's own setting. A Steam update or "Verify integrity of game files" may put back the original `Info.plist` (and the 3 GB limit); the status then shows "installed but not active" and installing again turns the patches back on and asks about memory again. Tested on an Apple silicon Mac, natively and as the Intel (x86_64) build under Rosetta 2; not yet on an Intel Mac.

Build the package yourself with `build-client.ps1` (it also needs MinGW-w64 gcc, for the small agent loader DLL, and Git for Windows' bash, to test the macOS launcher script); installer tests:
`powershell -NoProfile -ExecutionPolicy Bypass -File patcher/tests-client-installer/Run-InstallerTests.ps1`
(do not run them while a Project Zomboid JVM is running).
