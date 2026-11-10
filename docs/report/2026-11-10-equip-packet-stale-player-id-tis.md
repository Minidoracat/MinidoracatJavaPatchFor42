# TIS 回報草稿：客戶端替非玩家角色送出帶舊玩家編號的 Equip 封包，伺服器逐包印 `wrong player connection index (N)`（未送出）

- 狀態：草稿，尚未回報。封包本身沒有修補（伺服器本來就拒收）；JavaPatch 只處理 log：抑噪 #7 擋掉緊接在後的 `The packet Equip is not consistent`，抑噪 #11 把 `wrong player connection index` 改成計數、由心跳的 `badPlayerIdx` 帶出（[docs/patches.md §1](../patches.md#1)）。沒有 #11 的版本後台只剩 `wrong player connection index` 這一行、看不到封包名。
- 事件：2026-11-10 另一台裝了 JavaPatch 的伺服器回報後台一直刷 `ERROR: Multiplayer … at ConnectionPlayer.parse > wrong player connection index (N)`，N 是 4 的倍數，同一個 N 常以約 267 ms 的間隔連續出現十幾次。同一時段那位服主的客戶端 console 有 84 行 `EquipPacket.set get invalid arguments`，間隔相同。
- 資料來源：42.22.2 jar（sha256 `3c4a7c5f…`）的 javap、42.21.0／42.22.2 反編譯快照（下文只寫類別與方法名稱，不貼原始碼）、本服 console（42.21.0 與 42.22.2、JavaPatch 啟用前後的場次）、一個只用原版 jar 的小程式。
- 已確認：(1) javap：`IsoGameCharacter.tryPerformDropHeldItem(Predicate)` 在 `GameClient.client && isLocal()` 時以 `this` 呼叫 `INetworkPacket.send(PacketType.Equip, …)`，沒有檢查是不是 `IsoPlayer`；`isLocal()` 對沒有 `NetworkComponent` 的角色回 true；這個方法是 42.22 新增的，42.21.0 沒有。(2) javap：`EquipPacket.setData` 參數不是單一 `IsoPlayer` 時只印警告就 return，`INetworkPacket.send(IConnection, PacketType, Object...)` 接著照樣 `sendToConnection`；封包物件來自 `IConnection.getPacket`，也就是 `PacketsCache` 裡收包與送包共用的那一個。(3) 小程式（原版 jar、不帶任何 agent）：同一個 `EquipPacket` 先以客戶端身分 parse 一包玩家編號 12 的資料，再 `setData(非玩家物件)`、`write`，寫出的玩家編號是 12；伺服器端 parse 這包印出 `wrong player connection index (12)`，`isConsistent` 為 false。兩行 log 與回報逐字相同。(4) 本服 console：42.21.0 的 14 個場次 0 行；更新到 42.22.2 之後、JavaPatch 還沒啟用的 5 個原版場次共 34,684 行，逐行核對其中一場的 1,430 行，每一行的下一行都是 `The packet Equip is not consistent`；JavaPatch 啟用後的現行場次（35 人在線）88 分鐘 5,290 行，佔 console 31,778 行的 16.6%，單一分鐘最多 662 行。(5) 呼叫來源：`IsoZombie` 自己呼叫 `climbThroughWindow`／`climbThroughWindowFrame`，`IsoGameCharacter` 的這幾個方法先呼叫 `dropHeavyItems()`，再進 `tryPerformDropHeldItem`。
- 未確認：沒有在受控環境（本機專用伺服器＋客戶端）讓殭屍爬窗來重現，觸發的角色是從呼叫來源推出來的；殭屍以外（動物、摔落時的 `dropHandItems`）有沒有貢獻沒有量。殘留的玩家編號小於 4 時伺服器不報錯、會當成送出者自己的玩家處理（下文英文第 4 段），這一段只讀程式、沒有測。
- 玩家可用的避開方法：不需要。伺服器把這些封包丟掉，玩家自己換手持物的封包不受影響。

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add
tags：multiplayer, server, log, network

### Title

```text
[42.22.2] [MP] Clients send an Equip packet with a stale player id for non-player characters; the server logs "wrong player connection index (N)" for each one
```

### Body

本文照 `scripts/tis_forum_html.py` 的草稿寫法（小標下一行是 `-----`，程式與 log 縮排 5 格並前後空行）。

```text
Version: 42.22.2 (3d39148846), dedicated server and clients. IsoGameCharacter.tryPerformDropHeldItem() is new in 42.22; 42.21.0 does not have it.

Summary
-----
On a client, IsoGameCharacter.tryPerformDropHeldItem() sends an Equip packet when isLocal() is true:

     INetworkPacket.send(PacketTypes.PacketType.Equip, this)

It does not check that the character is a player, and isLocal() returns true for a character without a NetworkComponent. dropHeavyItems(), dropPrimaryHandItem(), dropSecondaryHandItem() and dropHandItems() all go through this method. climbThroughWindow(...) and climbThroughWindowFrame(...) call dropHeavyItems() first, and IsoZombie calls them for itself when it reaches a window.

EquipPacket.setData() accepts exactly one IsoPlayer. For anything else it logs "EquipPacket.set get invalid arguments" and returns normally, so INetworkPacket.send() still sends the packet. The packet object is the connection's cached instance (PacketsCache), the same one the client uses to parse incoming Equip packets. Its player field still holds the id from the last Equip packet that the client received, usually the online id of another player, and write() sends that id.

Since 42.22 the server reads this field as the index of a player on the sending connection (ConnectionPlayer.parse, valid range 0-3). An online id of 4 or more fails the check: the server logs "wrong player connection index (N)", the packet is not consistent, and it is dropped.

If the stale id is 0-3, the check passes and the server treats the packet as coming from the sender's own player with that index. processServer() then sets that player's hand items from the primaryHand / secondaryHand fields left in the cached packet and relays the packet to other clients. We only read the code for this case; we did not test it.

What players see
-----
Nothing in game in the common case: the packets are dropped. The server log gets one pair of lines per packet:

     ERROR: Multiplayer ... at ConnectionPlayer.parse > wrong player connection index (12)
     WARN : Multiplayer ... The packet Equip is not consistent: ...

The client log gets one line per packet:

     WARN : Multiplayer ... at EquipPacket.setData > EquipPacket.set get invalid arguments

Evidence
-----
- Our dedicated server, sessions with vanilla game code: 0 lines in 14 sessions on 42.21.0; 34,684 lines in the 5 sessions after the update to 42.22.2. In the session we checked line by line, every one of the 1,430 "wrong player connection index" lines is followed by "The packet Equip is not consistent".
- A later session with 35 players online: 5,290 lines in 88 minutes, up to 662 lines in one minute. This session runs our server patches, which do not change this code path.
- A client log from another server has 84 "EquipPacket.set get invalid arguments" lines; runs of them are about 267 ms apart, the same spacing as the server lines.
- A small program with only the vanilla 42.22.2 jar on the classpath: with GameClient.client = true, parse a buffer that holds the short 12 into an EquipPacket, call setData(new Object()) on the same instance, then write() it. The first short written is 12. With GameServer.server = true, parse that output into a new EquipPacket: the server logs "wrong player connection index (12)" and isConsistent() returns false.

Not confirmed
-----
We did not reproduce it in a controlled game session (for example a zombie climbing through a window next to a player). The trigger above comes from the call sites, and we did not measure which characters send most of these packets.

Suggested fix
-----
1. In IsoGameCharacter.tryPerformDropHeldItem(), send the packet only for a local player, the same check that updateHandEquips() already has (this instanceof IsoPlayer player && player.isLocalPlayer()).
2. Do not send a packet when setData() rejects its arguments. For example, let EquipPacket.setData() throw, so that INetworkPacket.send() goes to its catch block instead of sending the cached instance with the fields of an earlier packet.
```
