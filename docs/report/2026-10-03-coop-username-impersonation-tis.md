# TIS 回報草稿：`ConnectCoopPacket` 採用客戶端送來的名稱，分割畫面與重生都能換成離線玩家的名字（未送出）

- 狀態：草稿，尚未回報。使用者 2026-10-03 決定之後自己到官方論壇公開回報，不走私下管道。修補是 W52（`docs/patches.md` 2bp）：0 號重生一律用連線登入名、1–3 號分割畫面不能用已有帳號的名稱。2026-10-04 06:06 起在正式服生效（橫幅 `server patch 13dbc29`）；生效前草稿放在 `private/`（當時正式服的重生換名還開著），生效後依使用者 2026-10-03 的裁定移到這裡。
- 正式服現況：2026-10-03 23:06 起 `AllowCoop=false`，擋掉下文路徑 A；2026-10-04 06:06 起 W52 生效，路徑 B 由修補擋下。生效後到 2026-10-04 21:34 共 11 次主玩家重生，全部送登入名，守衛沒有改名或拒絕任何一次。車輛管理 MOD（MinidoracatVehicleManagerFor42）以 SteamID 綁定判斷身分，兩條路徑都拿不到車輛權限；原版安全屋、陣營與 MVCK 仍以名字認人，W52 生效後重生拿不到別人的名字。
- 資料來源：42.21.0 反編譯快照（下文只寫類別與方法名稱，不貼原始碼）、本機 42.21.0 jar 的 javap（jar sha256 開頭 `e1a69eb743ede60b`）、原版 `media/lua/client/OptionScreens/CoopUserName.lua` 與 `CoopCharacterCreation.lua`、車輛管理 MOD 2026-09-29 的本機 E2E（`identity-mp` 修正前那一輪與 `respawn-name-mp`；本機 dedicated server、沒有任何 Java patch，`Open=true`、`DoLuaChecksum=true`、`AllowCoop=true`），以及 JavaPatch 2026-10-04 的 E2E `e2e/coop-name-mp`（開機後 `adduser` 建離線帳號 victim1、victim2；W52 enforce 一輪、`-Dmdc.coopNameGuard=off` 一輪，證據 `temp/e2e-coop-1004a`、`temp/e2e-coop-1004b`）。車輛管理那兩輪的 test2、alice 不一定是本輪 world 的 whitelist 帳號；用真帳號的重現是 JavaPatch 的 off 那輪（W52 off 時 helper 原樣回傳封包名稱，判定與原版相同）。
- 已確認：(1) javap：`parse` 只在 `playerIndex != 0` 時才看 `ServerOptions.allowCoop`；stage 1 從封包讀名稱，只擋空字串與 `UdpConnection.usernames[]` 裡已在線的同名，「接替死亡玩家」與「新的分割畫面玩家」兩個分支都直接 `IConnection.setUserName`。(2) `receivePlayerConnect` 用這個名稱設 `player.username`；角色資料以 `connection.getUserName()`（登入的帳號）載入、角色權限取自連線，所以換掉的只有名字，拿不到對方的角色或管理權限。(3) 原版 `CoopUserName` 面板讓第 2 位玩家自由輸入名稱（`isValidUserName` 只檢查格式），`CoopCharacterCreation` 把它交給 `setPlayerJoypad`；不必改客戶端。(4) E2E：test2 不在線時以分割畫面用 test2 加入，伺服器 log 出現 `coop player=2/4 username="test2" assigned id=1` 與 `access granted`；伺服器殺死主玩家後，客戶端重生並立刻 `IsoPlayer:setUsername("alice")`，log 出現 `coop player=1/4 username="alice" is replacing dead player`，重生後的 0 號玩家名稱是 alice。(5) 程式上的後果：`SafeHouse.playerAllowed(IsoPlayer)` 以 `getUsername()` 比對成員名單與屋主；`LoginPacket` 遇到 `usernames[]` 已有同名就拒絕登入（`AlreadyConnected`），冒名者在線時本人登不進來。
- 未確認：實機進入他人安全屋、陣營權限（只有程式推論）；白名單伺服器（`parse` 沒有白名單或帳號查詢，推論結果相同，沒有實測）。
- 服主可用的緩解：關閉 `AllowCoop` 只擋路徑 A；路徑 B 要靠伺服器修補（0 號玩家忽略封包裡的名稱），或開 `DoLuaChecksum` 提高門檻（擋改檔，擋不住注入）。

```text
[42.21.0][MP] ConnectCoopPacket trusts the username sent by the client: split-screen and respawning players can take an offline player's name

Version: 42.21.0 (4a0e9546ec). Dedicated server. Our production server now works around this with a server-side Java patch (see "Our workaround" below); the reproductions below were done without it or with it switched off.

Summary
In stage 1, ConnectCoopPacket.parse() reads the username from the packet and only rejects an empty name or a name that is currently connected (any UdpConnection.usernames[] entry). It does not compare the name with the account that logged in on this connection, and it does not check whether the name belongs to another account. Both branches (replacing a dead player, and a new split-screen player) then call setUserName with it, and GameServer.receivePlayerConnect() sets player.username to that name. The character itself is still loaded for connection.getUserName() and the role comes from the connection, so only the name changes.

Two ways to reach it
A. Split-screen, AllowCoop=true: the CoopUserName panel lets player 2 type any valid name, and CoopCharacterCreation passes it to setPlayerJoypad. No modified client is needed.
B. Respawn of the primary player, any AllowCoop value: parse() only checks allowCoop when playerIndex != 0, and the dead-player branch replaces usernames[0] with the name in the packet (ConnectCoopPacket.setInit sends IsoPlayer.username). A client that runs custom Lua, for example IsoPlayer:setUsername(...) right after respawning, comes back under another name.

Impact (read from the code; A and B behave the same after the join)
- Checks that identify players by name treat the player as that account. SafeHouse.playerAllowed(IsoPlayer) compares getUsername() with the member list and the owner. Faction membership is also by name, and many mods use getUsername().
- While the impersonator is connected, the real owner cannot log in: LoginPacket denies a username that matches any connected usernames[] entry ("AlreadyConnected").
- Not affected: the character data and the role/capabilities, which come from the connection.

Reproduction (local dedicated server, 42.21.0, Open=true, DoLuaChecksum=true, AllowCoop=true)
A. Player "test" logs in and adds split-screen player 2 under another name. We called setPlayerJoypad(1, 1, nil, name, true), which is what CoopCharacterCreation does with the text from CoopUserName. With "test2" (not connected), on a server without our patches: coop player=2/4 username="test2" assigned id=1, then access granted. With "victim2", an offline account created with the adduser console command, on our build with the workaround switched off: coop player=3/4 username="victim2" assigned id=2, access granted. On the server the new IsoPlayer's username is the requested name.
B. The server kills player "test". The client respawns (setPlayerJoypad(0, 0, nil, nil, true), or setPlayerMouse(nil) as the mouse player does) and immediately calls IsoPlayer:setUsername(name). With "alice": coop player=1/4 username="alice" is joining, then is replacing dead player. With "victim1", another offline account: the same, and the client's own player 0 is renamed too after ConnectedPacket. On the server the respawned player 0's username is the new name.
Not tested in-game: entering the account owner's safehouse, and whitelist servers (parse() has no whitelist or account lookup).

Suggested fix
1. playerIndex 0: ignore the username in the packet and keep the name the connection logged in with (usernames[0] as set by LoginPacket).
2. playerIndex 1-3: reject a name that belongs to an existing account other than the host's, or derive the name from the host account (for example "host#2").
For server owners until then: AllowCoop=false stops A only.

Our workaround (server-side bytecode patch, for reference: https://github.com/Minidoracat/MinidoracatJavaPatchFor42/blob/main/docs/patches.en.md#2bp)
We redirect the one ByteBufferReader.getUTF() in ConnectCoopPacket.parse() (the stage 1 name) and return the name the rest of parse() uses:
- playerIndex 0: always connection.getUserName(), the account the connection logged in with. A normal client already sends exactly that name (assignUsername/setPlayerMouse use GameClient.username, trimmed the same way as LoginPacket.parse), and ConnectedPacket writes the server's name back to the client's own player, so normal players see no change.
- playerIndex 1-3: an empty string when the name matches an existing account (ServerWorldDatabase.containsCaseinsensitiveUser), which takes the existing "No username given" rejection before any state changes.
In-game test of the workaround: a normal respawn keeps its name; a respawn renamed to "victim1" comes back as "test" on the server and on the client; a split-screen player with a free name joins; a split-screen player named "victim2" is rejected. On our production server, from 2026-10-04 06:06 to 21:34, all 11 respawns sent the login name, so the guard never had to rename or reject anything.

We can share the server logs of these runs.
```
