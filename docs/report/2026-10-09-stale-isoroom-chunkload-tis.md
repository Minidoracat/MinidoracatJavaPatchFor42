# TIS 論壇回覆 — 102213：自建房 chunk 載入時 `IsoRoom.hasLightSwitches` NPE 的成因與修法

**狀態**：**已回覆 2026-10-09** → https://theindiestone.com/forums/topic/102213-title-b4221-nullpointerexception-in-isoroomhaslightswitches-roomdef-is-null-when-loading-chunks-in-player-built-areas/#findComment-494741 （回覆既有主題；原回報者寫了「I could not determine how a square ends up pointing to such a room」，本文補上這段並附重現方法與修法建議）。論壇版本與下方本文相同，貼上時編輯器把網址轉成連結。與 R1（[2026-09-05-client-metagrid-isorooms-race-tis.md](2026-09-05-client-metagrid-isorooms-race-tis.md)，論壇 101035）同一個上游缺陷。

## 中文摘要

chunk 在 World Streamer 執行緒反序列化時就把格子綁上 IsoRoom；主執行緒接手（`loadInMainThread`）之前，同一個或相鄰 cell 若有 IsoRegions 變更，client 主執行緒的 `clientProcessBuildings` 會把這一帶所有自建建築拆掉重建，舊 IsoRoom 被 `clear()`（def 變成 null），重建後的 `updateSquares` 只重綁已在 ChunkMap 裡的 chunk。排隊中的 chunk 因此留著被清空的房間，`IsoLightSwitch.chunkLoaded` 一讀 `def.objects` 就 NPE，client 被斷線送回主選單。單機也走同一條路徑。修法建議：房間綁定改在主執行緒做；最小修法是在 `loadInMainThread` 發現過期房間時，對這個 chunk 補跑 `updateSquares` 的逐格步驟（我們 client 包 W57 的做法）。只加 null 檢查不夠：格子仍指向空房，其他沒檢查 null 的 `getRoom().def` 讀取點一碰到就 NPE。

證據等級：玩家 client log（42.21.0 `4a0e9546ec`）＋本機 jar（sha256 `e1a69eb7…`）`javap -l` 逐行對應＋本機 SP 實機重現（原版第一輪就斷線，堆疊與玩家 log 逐行相同；W57 下 12 輪零例外）。

### 核實紀錄（2026-10-09）

| 項 | 方法 | 結果 |
|---|---|---|
| 版本 | `VERSION.txt` sha256 vs `projectzomboid.jar` | 一致 `e1a69eb7…`，42.21.0 |
| 例外行號 | `javap -c -l` | `IsoRoom.java:604`＝`hasLightSwitches` offset 12–27（`getfield def`→`getfield RoomDef.objects`）；`IsoLightSwitch.java:879`＝`chunkLoaded` offset 61–82（含 `hasLightSwitches`）；`IsoChunk.java:2930`＝`loadInMainThread` offset 1862–1863 的 `IsoLightSwitch.chunkLoaded` |
| 綁房間的執行緒 | `javap` `IsoChunk.LoadFromDiskOrBufferInternal` | 唯一的 `IsoGridSquare.setRoomID`（offset 880），經 `IsoChunk.getRoom` → `IsoMetaGrid.getRoomByID`；這條路徑在 World Streamer 執行緒 |
| 房間被清空 | `WorldRegionToMetaGrid.removeIsoRoom` → `IsoRoom.clear(Z)V` | `aconst_null; putfield def`、清空 `lightSwitches` |
| 重綁範圍 | `javap` `WorldRegionToMetaGrid.updateSquares` | 只走 `IsoCell.getChunkMap(i).getChunk(II)`，再以 `lambda$updateSquares$0`／`$1` 走訪格子 |
| 區域變更廣播 | `IsoRegionWorker.update` | server 對 `udpEngine.connections` 全部送出（`getTargetConn() == null`） |
| 實機重現 | 本機 SP `-debug`、原版 jar（Steam 本體，無任何 loose class） | 見下方「重現」；第一次傳回就拋出與玩家 log 逐行相同的堆疊並退回主選單 |

### 重現（本機 SP，情境在 `.claude/skills/javapatch-e2e/scenarios/stale-room-sp/`，未進版控）

起點 14322,4969（原版 Trailer3 蓋房示範的同一地點），關殭屍；在起點周圍 20–50 格蓋 8 間 3×3 封閉自建房（`walls_exterior_wooden_01_40`／`_41`，z=1 鋪 `carpentry_02_58` 當屋頂），各在不同 chunk。每輪傳送到約 4000 格外、等 6 秒、傳回起點，接著 8 秒內每個 tick 在起點旁拆／放一面牆。

## 回覆本文

```text
We hit the same crash on our 42.21.0 server (client side, revision 4a0e9546ec) and traced how a square ends up pointing at a cleared room. It is a hand-over race between the World Streamer thread and the main loop, and it can be reproduced on demand, in single player too.

How a square keeps a cleared IsoRoom
------------------------------------

1. Squares are bound to rooms while the chunk is deserialized on the World Streamer thread: IsoChunk.LoadFromDiskOrBufferInternal -> IsoGridSquare.setRoomID -> IsoChunk.getRoom -> IsoMetaGrid.getRoomByID, which returns (or creates) the IsoRoom in IsoMetaCell.isoRooms. The chunk then waits in IsoChunk.loadGridSquare. The main thread's IsoChunkMap.updateInternal takes a few chunks per frame, calls setChunkDirect and only then doLoadGridsquare -> loadInMainThread.

2. If any IsoRegions change happens in the same or a neighbouring cell during that window (someone builds or removes a wall, floor, door or fence; the server sends region updates to every connection), the main thread runs IsoRegions.update -> DataRoot.clientProcessBuildings -> WorldRegionToMetaGrid.clientProcessBuildings. That removes every user-defined building in those cells (removeIsoRoom -> IsoRoom.clear(), which sets def to null and empties lightSwitches) and builds them again.

3. WorldRegionToMetaGrid.updateSquares then re-binds squares only for chunks returned by IsoCell.getChunkMap(i).getChunk(x, y). The queued chunk is not in the chunk map yet, so its squares keep the cleared IsoRoom (roomId is not -1, so getRoom() still returns it).

4. loadInMainThread -> IsoLightSwitch.chunkLoaded -> IsoRoom.hasLightSwitches() reads def.objects and throws. With a registered switch, createLights(room.def.lightsActive) would throw the same way. The catch in IngameState.updateInternal stops the World Streamer and disconnects the client.

Only player-built rooms are affected because clientProcessBuildings never removes pre-built buildings. It is intermittent because a rebuild has to land inside that window; the more chunks are queued while moving, the wider the window.

Reproduction (single player, -debug, vanilla 42.21.0, no mods needed)
-------------------------------------------------------------------

1. Start at 14322,4969 (the spot of the Trailer3 building debug scenario), zombies off.
2. Build 8 small enclosed rooms (3x3, walls `walls_exterior_wooden_01_40` / `walls_exterior_wooden_01_41`, floor `carpentry_02_58` on z=1 as the roof) 20-50 tiles around the player, each in its own chunk, and wait until their squares report a room.
3. Teleport about 4000 tiles away, wait 6 seconds, teleport back, and add or remove one wall next to the player on every tick for 8 seconds.

Vanilla threw on the first return in both runs, with the same stack as the report above (IsoRoom.java:604, IsoLightSwitch.java:879, IsoChunk.java:2930 on our build), and dropped back to the main menu. With the stopgap described below, all 12 rounds ran without an exception; on the first two returns alone it logged 20 queued chunks (3-9 squares each) that pointed at a cleared room and re-bound them. I can share the Lua scenario if it helps.

Why a null check is not enough
------------------------------

Returning false from hasLightSwitches when def is null avoids this NPE, but the chunk's squares keep pointing at the cleared room until a later rebuild happens to touch that chunk. Other code reads getRoom().def without a check as well; for example IsoLightSwitch.switchLight, called when a switch is toggled, writes getRoom().def.lightsActive. The crash would move there.

Suggested fix
-------------

- Bind rooms on the main thread. During deserialization keep at most the room ID, and resolve each square's room (getRoomAt / setRoomID) in loadInMainThread before anything reads it (before the room.addSquare loop). IsoLightSwitch.load also registers the switch with square.getRoom() while the chunk is deserialized, so that registration would move along. Main-thread binding cannot interleave with clientProcessBuildings, and it also stops the World Streamer thread from inserting into IsoMetaCell.isoRooms, which is the other half of the same problem (the HashMap race with an AIOOBE in recalculateBuildingAndRoomIDs, topic 101035).
- A smaller change: in loadInMainThread, if any square's room has def == null, run the per-square steps of updateSquares (lambda$updateSquares$0 and $1 in 42.21.0) and its per-chunk invalidation on that chunk before IsoLightSwitch.chunkLoaded, i.e. treat it as one of the chunks the last rebuild should have updated. This is the stopgap we ship in our client patch; chunks without a stale room are not touched.

Details with bytecode offsets: https://github.com/Minidoracat/MinidoracatJavaPatchFor42/blob/main/docs/patches.en.md#2bu
```

## 給 Discord／Workshop 玩家的白話版

跨 chunk 走進有自建房的地方偶爾斷線，是 42.21.0 原版 bug：地圖在背景讀好後、正式載入前，附近剛好有人蓋或拆東西，遊戲把這一帶的自建房砍掉重建，還在排隊的那塊地圖沒被更新，正式載入時讀到已清空的房間就出錯。不是電腦或 MOD 的問題，也不影響伺服器存檔。我們的客戶端修補包 0.2.6 會在這種情況下先把那塊地圖重新對到新房間，不再斷線；官方修好後會撤掉。
