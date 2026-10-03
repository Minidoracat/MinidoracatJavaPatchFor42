# TIS 回報草稿：client 卸載有雞舍的 chunk 時 `IsoHutch.removeFromWorld` NPE，玩家被斷線踢回主選單（未送出）

- 狀態：草稿，尚未回報。本 repo 沒有對應修補：拋出例外的程式與寫入 null 的程式都在原版 client 端，伺服器修補碰不到。client 端的繞過已收進 Lua 修復 MOD（MinidoracatFixesFor42 的 `MDFX_HutchNullSlotGuard`，Workshop 42.21.0-0.13.0 起，2026-10-03 發布）。
- 事件：2026-10-01 19:12（Player-L，這一場玩了約 55 分鐘，往北移動時）與 2026-10-03 約 21:00（Player-N，進遊戲約 6 秒、往東移動時），兩人的 client 都在卸載 chunk 時拋出同一個 NPE，被原版的例外處理以 `doDisconnect("crash")` 斷線，回到主選單。
- 資料來源：兩位玩家的 client console.txt／DebugLog（版本都是 42.21.0 4a0e9546ec，都沒有裝 client 修補包）、42.21.0 與 42.20.4 反編譯快照（下文只寫類別與方法名稱，不貼原始碼）、本機 42.21.0 jar 的 javap（sha256 與快照相同）。
- 已確認：(1) 兩份 log 的例外與呼叫鏈相同，只差捲動方向；(2) `IsoHutch.removeFromWorld` 逐一呼叫 `removeFromUpdateLists()` 的迴圈是 42.21.0 新增，42.20.4 沒有；(3) client 的兩條動物同步路徑會把 null 寫進 `animalInside`，42.20.4 就是這樣；(4) `IsoChunkMap` 四個方向的捲動與 `Unload()`（傳送）都會走到 `IsoChunk.removeFromWorld`，而且都在會斷線的那個 catch 裡；(5) 本機 no-Steam 伺服器＋客戶端實機重現：母雞進巢箱後往東三段傳送在 `IsoChunkMap.Right` 崩潰，母雞換格後一次傳送 400 格在 `IsoChunkMap.Unload` 崩潰，堆疊與玩家 log 相同。
- 未確認：兩位玩家各是哪一條路徑產生 null。client log 沒有雞舍座標，也不記動物同步內容。
- 玩家可用的避開方法：沒有可靠的方法。重登就能回到遊戲；修好之前，離開有雞舍的區域（走路、開車或傳送）時都可能再發生。伺服器啟用 MinidoracatFixesFor42 42.21.0-0.13.0 以上後，玩家連線時會自動下載繞過。

```text
[42.21.0][MP] Client is disconnected ("crash") with an NPE in IsoHutch.removeFromWorld when a chunk with a hen house unloads

Version: 42.21.0 (4a0e9546ec). Dedicated server (Linux) and Windows 11 clients. Our server runs server-side Java patches, but none of them run on clients; both clients were unmodified.

Summary
42.21.0 added a loop to IsoHutch.removeFromWorld() that calls IsoAnimal.removeFromUpdateLists() on every value of animalInside, with no null check. On multiplayer clients animalInside routinely holds null values, because the client's own animal sync puts null into the map instead of removing the key. When the client unloads a chunk that contains such a hutch, the NullPointerException escapes from IsoChunkMap.ProcessChunkPos. The catch in IngameState.updateInternal then saves and calls GameClient.doDisconnect("crash"), and the player is back at the main menu.

What players see
- While walking or driving, the game drops to the main menu without a message. Reconnecting works.
- One player hit it about 6 seconds after loading in, while moving east. Another hit it after about 55 minutes of play, while moving north. In both cases the chunk being unloaded contained a hutch (it is in the stack trace).

Evidence
1. Client console, two players on two days, the same trace except for the scroll direction:
     ERROR: General f:357 ... IngameState.updateInternal> Exception thrown
     java.lang.NullPointerException: Cannot invoke "zombie.characters.animals.IsoAnimal.removeFromUpdateLists()" because "animal" is null at IsoHutch.removeFromWorld(IsoHutch.java:1095).
       zombie.iso.objects.IsoHutch.removeFromWorld(IsoHutch.java:1095)
       zombie.iso.IsoObject.removeFromWorldToMeta(IsoObject.java:4613)
       zombie.iso.IsoChunk.removeFromWorld(IsoChunk.java:3398)
       zombie.iso.IsoChunkMap.Right(IsoChunkMap.java:887)       (other case: IsoChunkMap.Up(IsoChunkMap.java:775))
       zombie.iso.IsoChunkMap.LoadRight(IsoChunkMap.java:658)   (other case: IsoChunkMap.LoadUp(IsoChunkMap.java:686))
       zombie.iso.IsoChunkMap.ProcessChunkPos(IsoChunkMap.java:970)
       zombie.gameStates.IngameState.updateInternal(IngameState.java:1542)
   The next lines are "removing all player data" and "STATE: exit zombie.gameStates.IngameState".
2. Bytecode of IsoHutch.removeFromWorld() in 42.21.0: after super.removeFromWorld() and HutchManager.remove(this), it iterates animalInside.values() and calls removeFromUpdateLists() on each element, with no null test. In 42.20.4 the method ended after HutchManager.remove(this). removeFromUpdateLists() itself is new in 42.21.0.
3. Reproduced on 42.21.0 with a local dedicated server and one client, using vanilla calls only:
   - On the server, build a hen house with IsoHutch.new(...) and transmitCompleteItemToClients(), and put 4 hens in slots 0-3 (addAnimalInside, then removeFromWorld/removeFromSquare, as BaseAnimalBehavior.enterHutch does).
   - Once the client shows the 4 hens, call addAnimalInNestBox() for the hen in slot 0 on the server. About 0.2 s later the client's animalInside has 4 entries: 3 hens and a null (the hen is in the nest box).
   - Teleport the client 40, 80 and 120 squares east, one second apart. The second hop crashes with the trace above, through IsoChunkMap.Right, and the client is back at the main menu. The server log shows a client-side "disconnection-notification".
   - Variant: move the hen from slot 0 to slot 10 instead (client animalInside: 5 entries, one null), then teleport 400 squares. The client crashes at once, through IsoChunkMap.Unload.
   - A hen in a nest box keeps her old hutchPosition on the client and stays registered there. After the client unloads and reloads the hutch, her next update writes the null again.

Where the null values come from (client side, unchanged since 42.20.4)
- NetworkPlayerAI.parse(AnimalPacket), animal in a hutch: if the animal's hutchPosition is not -1, it first puts null at that slot, then puts the animal at the slot from the packet. When the packet moves the animal into a nest box (hutchPosition -1, hutchNestBox set), the old slot keeps the null, and the client does not reset hutchPosition in that branch. A hen going to lay an egg is enough. The null stays until the hen comes back to the same slot. If she comes back to a different slot, it stays until another animal takes that slot or the client reloads the hutch.
- AnimalUpdatePacket.parse, response to an animal request, animal in a hutch: it puts null at packet.hutchPosition, then calls addAnimalInside(animal, false) and discards the result. If addAnimalInside picks another slot (the preferred slot holds a dead body, or is the preferred slot of an animal in a nest box) or returns false (the animal is already in the map), a null slot remains.
Neither path removes the key. Everything else that reads the map with get() treats null as an empty slot, so these entries were harmless until removeFromWorld started iterating values().

Paths that unload a hutch on the client (all inside ProcessChunkPos, under the catch that disconnects)
- IsoChunkMap.Left/Right/Up/Down: crossing a chunk boundary (by the player, or by the look-ahead point while in a vehicle) removes the far column or row with IsoChunk.removeFromWorld().
- IsoChunkMap.Unload(): a jump of chunkGridWidth chunks or more (a teleport) removes every loaded chunk.

Other client effects of the same null entries
- ISHutchMenu greys out "Put animal in hutch" when getAnimalInside():size() >= getMaxAnimals(), and null entries count toward size().
- ISDesignationAnimalZoneUI counts hutch animals with getAnimalInside():size().

Suggested fix
1. IsoHutch.removeFromWorld(): skip null values.
2. NetworkPlayerAI.parse(AnimalPacket) and AnimalUpdatePacket.parse: remove the old key instead of putting null. This also keeps size() right for the UI above.
Either change alone stops the disconnect.

We can share both client logs.
```
