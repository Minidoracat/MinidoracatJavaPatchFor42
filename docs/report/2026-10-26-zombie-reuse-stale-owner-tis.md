# TIS 回報草稿：回收的殭屍物件帶著上一輪的網路擁有者，新生成的殭屍有些所有客戶端都看不到（未送出）

- 狀態：草稿，尚未回報。本服修補為 W80（[docs/patches.md 2co](../patches.md#2co)，預設 on，`-Dmdc.zombieReuseOwner=off` 回原版）。
- 事件：2026-10-26 測試 Quest MOD 的屍潮（伺服器用 `addZombiesInOutfitArea` 生成）時，每一場都有幾隻殭屍兩個客戶端都看不到、清不完，伺服器上卻在、而且有擁有者；擁有權換給另一位玩家時，伺服器印 `moveZombie: There are no zombies in nz.zombies.`，殭屍接著出現。正式服這行平時被 JavaPatch 抑噪 #5 丟掉；唯一沒過濾的一輪（9/28 20:53～9/29 00:05，約 47 人）在 22:23～00:00 有 1,353 行。
- 資料來源：42.21.0 jar（sha256 `e1a69eb7…`）的 javap、42.17.0／42.20.4／42.21.0 反編譯快照（下文只寫類別與方法名稱，不貼原始碼）、本機專用伺服器＋兩個客戶端的 E2E 三輪：原版 `quest-hzloop-1026e`、W80 on `quest-hzloop-w80on-1026a`、W80 off `quest-hzloop-w80off-1026a`。
- 已確認：(1) 42.17.0、42.20.4、42.21.0 的回收流程都不清擁有者：`IsoZombie.resetForReuse()` 與整個 `VirtualZombieManager`（含 `createZombieOutsideWorld` 的重用分支）都沒有呼叫 `setOwner`／`setOwnerPlayer`；(2) 伺服器端生成真殭屍的路徑（人口管理、`createhorde` 指令、Lua 的 `addZombiesInOutfitArea` 等）都經 `createRealZombieAlways` → `createZombieOutsideWorld` 從同一個回收池取物件；(3) 伺服器只把殭屍送給擁有清單裡有牠的連線，或轉送擁有者送來的更新（`NetworkZombiePacker.postupdate`、`getZombieData`），所以不在任何清單裡的殭屍沒有人收到；(4) E2E：原版 32 隻裡生成時帶擁有者的 15 隻兩端都沒看到、`nil` 的 17 隻都看到，那行 12 次；W80 on 40 隻生成時全是 `nil`、兩端全部看到；W80 off 生成時帶擁有者的 16 隻有 15 隻兩端都沒看到。
- 未確認：正式服那 1,353 行有多少是這個原因（W80 上線後看心跳的 `nzMissing`）；看不到的殭屍在伺服器端會不會移動或攻擊，沒有測。
- 玩家可用的避開方法：沒有。看不到的殭屍要等擁有權換給別人才出現。MOD 可以在生成後 `setOwner(nil)`、`setOwnerPlayer(nil)`（Quest 的做法），但這只清欄位、不清舊擁有清單，見下方建議修法第 2 點。

```text
[42.21.0][MP] Recycled zombie objects keep their previous network owner, so some new zombies are invisible to every client until ownership changes

Version: 42.21.0 (4a0e9546ec), dedicated server. The recycle code is the same in 42.17.0 and 42.20.4.

Summary
When a zombie dies on a multiplayer server, its network owner (setOwner / setOwnerPlayer) stays on the object: the dead branch of NetworkZombieManager.moveZombie() only clears it when realState is OnGround. Five seconds after the body is created, VirtualZombieManager.update() passes the object to reuseZombie(), which calls IsoZombie.resetForReuse() and puts it in the reuse pool. Neither of them resets the owner, and createZombieOutsideWorld() does not either when it takes the object out of the pool for the next zombie.

The new zombie is therefore born owned by the previous owner X, but X's owned list in NetworkZombieManager no longer contains it: getZombieAuth() dropped it when its onlineId became -1. When updateAuth() picks X again, moveZombie() does nothing because getOwner() == to, so the zombie is never added to X's list. Nobody simulates it, and the server sends it to no client: NetworkZombiePacker sends a zombie to the connection whose owned list contains it, and relays the updates that its owner sends. The zombie stays invisible until updateAuth() picks another player. Then the removal from X's list fails, the server logs "moveZombie: There are no zombies in nz.zombies.", and the zombie is added to the new owner's list and appears.

Every server-side way of creating a real zombie takes objects from this pool (createRealZombieAlways -> createZombieOutsideWorld): the population manager, the createhorde command, Lua addZombiesInOutfitArea and others.

What players see
- Zombies that spawn near players are sometimes missing on every client, although the server has them. They appear later, when ownership moves to another player. A horde spawned by a mod or by an admin command can be impossible to clear for a while.
- The server log has "moveZombie: There are no zombies in nz.zombies.". Our server (about 47 players) logged it 1,353 times in 97 minutes in one session. We do not know how many of those come from this cause.

Reproduction (local dedicated server, vanilla game code, two clients standing next to each other, ZombieConfig.PopulationMultiplier and PopulationStartMultiplier set to 0 so that only the spawned zombies are around)
1. On the server, spawn 4 zombies around the players with addZombiesInOutfitArea(x - 5, y - 5, x + 5, y + 5, z, 4, nil, 50). For each zombie in the returned list, print getOnlineID() and getOwnerPlayer() (nil, or the player who owned the object in its last life).
2. Player A kills them while A owns them.
3. About 15 seconds after the last kill (more than 5 seconds, so the objects have been recycled), spawn again at the same place. Repeat.
4. Five seconds after each spawn, check on both clients which of the spawned onlineIds are in getCell():getZombieList().

Our results, 4 zombies per round:
- Vanilla: 32 zombies over 8 rounds. The 15 that had an owner at spawn were missing on both clients; the 17 with nil were on both. The server logged the moveZombie line 12 times.
- With our server-side fix (the owner is cleared when the object enters the pool): 40 zombies over 10 rounds, all with nil at spawn, all on both clients.
- The same build with the fix switched off: 40 zombies over 10 rounds. Of the 16 that had an owner at spawn, 15 were missing on both clients and 1 appeared after ownership had moved to player B; the 24 with nil were on both clients.

Suggested fix
1. Clear the owner when the object is recycled: in VirtualZombieManager.reuseZombie() or IsoZombie.resetForReuse(), call setOwner(null) and setOwnerPlayer(null). The new zombie is then born without an owner, and updateAuth() assigns one through moveZombie(), which also adds it to that owner's list.
2. At the same point, take the object out of the old owner's list, for example with NetworkZombieManager.getInstance().recheck(oldOwner) after clearing (it runs under owns.lock and removes every entry whose owner is not that connection). Normally the list no longer has the object. But if the zombie dies while the server handles a zombie packet (NetworkZombiePacker.parseZombie -> die()) and the main loop then stalls for more than 5 seconds in the same tick (for example a world save in ServerMap.preupdate()), VirtualZombieManager.update() recycles the object and the population manager can spawn it again with a new onlineId before ServerMap.postupdate() runs getZombieAuth(). Clearing only the two fields would then leave it in X's list while updateAuth() adds it to another player's list, or adds it to X's list a second time.

Our server-side patch does both by redirecting the resetForReuse() call in reuseZombie(). Details: https://github.com/Minidoracat/MinidoracatJavaPatchFor42/blob/main/docs/patches.en.md#2co
```
