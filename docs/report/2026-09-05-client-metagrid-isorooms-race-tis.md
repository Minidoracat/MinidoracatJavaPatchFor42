# TIS 官方回報草稿 — client 端 `IsoMetaCell.isoRooms` 跨執行緒競態 → 玩家被踢回主選單

**狀態**：**已回報 2026-09-05** → https://theindiestone.com/forums/topic/101035-42204-mp-client-kicked-to-main-menu-isometacellisorooms-hashmap-raced-between-main-loop-and-world-streamer-thread-aioobe-in-recalculatebuildingandroomids/ 。本檔與 `forum-html/D-R1.html` 為公開去識別化版本，不宣稱與歷史貼文逐字一致。
回報前核對（2026-09-05）`docs/report/` 全部既有草稿與 `patches.md`：`WorldRegionToMetaGrid.recalculateBuildingAndRoomIDs`／`IsoMetaGrid.getRoomByID`／`isoRooms` 零命中；唯一沾邊的 `lambda$updateSquares$0` 是 PSR toxic 案，不同方法不同問題。
與 A 組 R3（`tempVector2_2`）、R6（CRC32）同家族：main loop 與 World Streamer 對同一個非執行緒安全物件無鎖存取。這次是 **client** 端。

## R1. client 端 `IsoMetaCell.isoRooms` 跨執行緒競態 → 玩家被踢回主選單

## 中文摘要

MP client 在高速移動穿過建築密集區時，主迴圈經 `IsoRegions.update`、`clientProcessBuildings` 重建 metagrid，在 `recalculateBuildingAndRoomIDs` 複製房間 map 的值；同時 World Streamer 執行緒載入新 chunk，經 `IsoChunk.LoadFromDiskOrBufferInternal`、`IsoMetaGrid.getRoomByID` 向同一個 `HashMap` 加入房間。未同步的 map 大小與遍歷結果可能不一致，造成 `ArrayIndexOutOfBoundsException`，例外使 client 退出 `IngameState`。這份 stack 不涉及存檔寫入，但不據此保證整個 session 的存檔狀態。

證據等級：玩家 client `console.txt`（42.20.4）＋本機 jar（sha256 `80e405a4…`）`javap -l` LineNumberTable 逐行對應＋JDK 25 `HashMap` 源碼。全鏈 bytecode 級核實，非反編譯推測。

### 核實紀錄（2026-09-05）

| 項 | 方法 | 結果 |
|---|---|---|
| 版本 | `VERSION.txt` sha256 vs `projectzomboid.jar` | 一致 `80e405a4bfc42f…`，42.20.4 |
| log :482 是哪一句 | `javap -c -l` `WorldRegionToMetaGrid.recalculateBuildingAndRoomIDs` | `line 482: 205` → bc 210 `getfield isoRooms` → 213 `HashMap.values` → 216 `ArrayList.<init>`；**isoRooms**（`isoBuildings` 那句是 line 477） |
| 寫者 | `javap` `IsoMetaGrid.getRoomByID(long)` | bc 60 `isoRooms.containsKey` → 253 `isoRooms.put`；整個 class 零 `synchronized`／`monitorenter` |
| 寫者執行緒 | `IsoChunk` bytecode caller map | `LoadChunk(int,int,ByteBuffer)` → `LoadOrCreate` → `LoadFromDisk`／`LoadFromBuffer` → `LoadFromDiskOrBuffer` → `LoadFromDiskOrBufferInternal` → `getRoom(long)` → `IsoMetaGrid.getRoomByID`；唯一呼叫點 |
| 執行緒身分 | `WorldStreamer.create` bytecode | 建立名為 `World Streamer` 的 worker；`threadLoop` 經網路 chunk 或 jobList 路徑呼叫 `DoChunk`、`DoChunkAlways`，再進入 `IsoChunk.LoadChunk` |
| 讀者執行緒 | log stack | `GameWindow.mainThreadStep` → `IngameState.updateInternal` → `IsoWorld.update` → `IsoRegions.update` → `DataRoot.clientProcessBuildings` |
| 讀者無鎖 | grep `IsoRegions.java`／`DataRoot.java`／`WorldRegionToMetaGrid.java` | 零 `synchronized`、零 `WorldStreamer` 引用（沒有暫停 streaming） |
| JDK 順序 | JDK 25 `HashMap` | `putVal` 先連接節點再更新大小；`Values.toArray` 依大小配置陣列後遍歷節點，兩者若因並行修改而不一致即可越界 |
| MOD 排除界線 | AutoDrive／MiniMap Lua 靜態搜尋 | 未發現 `getRoom`／`getBuilding`／`getMetaGrid`／`IsoChunk` 呼叫；這是相關 MOD 範圍的陰性證據，不是所有 MOD 與執行路徑皆已排除的證明 |

同一方法也會複製 `isoBuildings`（line 477），而 `getRoomByID` 也會修改該 map（bc 313）；它具有同型並行風險，但此次 stack 指向 rooms。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add
tags：multiplayer, client, crash, chunk

### 修正前必做

- 附件 `console.txt` 來自玩家，貼出前跑 `temp/redact-expressions.txt` 去識別（玩家名、Steam 路徑含使用者名、伺服器 IP）。stack 段本身無識別資訊，可直接貼。
- 本文不含玩家名；伺服器資訊沿用 R3 模板。

### Title

`[42.20.4] [MP] Client kicked to main menu: IsoMetaCell.isoRooms HashMap raced between main loop and World Streamer thread (AIOOBE in recalculateBuildingAndRoomIDs)`

（原長版 ~230 字元會被論壇標題欄截斷；長描述留在 Body 的 Summary。）

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer — client side]
Client: [Windows, bundled Java runtime]
Server settings: [Dedicated multiplayer]
Mods: [Modded session. No mod code appears on the reported exception stack; the identified shared-map accesses are in vanilla Java.]
Save: [Existing MP save. The reported stack is in the update loop, not a save-writing path.]

Summary
-------
Two threads share the same java.util.HashMap without any synchronisation:

  * Main loop: IsoRegions.update() -> DataRoot.clientProcessBuildings() ->
    WorldRegionToMetaGrid.addToMetaGrid() -> recalculateBuildingAndRoomIDs(),
    which copies the values of the room map into a list, and does the same
    for the building map a few lines earlier.

  * "World Streamer" thread: WorldStreamer.threadLoop() -> loadReceivedChunks()
    -> DoChunk() -> DoChunkAlways() -> IsoChunk.LoadChunk() -> LoadOrCreate()
    -> LoadFromDisk()/LoadFromBuffer() -> LoadFromDiskOrBuffer() ->
    LoadFromDiskOrBufferInternal() -> IsoChunk.getRoom(long) ->
    IsoMetaGrid.getRoomByID, which lazily creates rooms and inserts them
    into the room map, also updating the building map for a new building.

HashMap.putVal links a new node before updating the recorded size.
HashMap$Values.toArray allocates an array using that size and then walks
the table. Without synchronization, a concurrent write can leave the
array smaller than the number of values encountered:

  java.lang.ArrayIndexOutOfBoundsException: Index 924 out of bounds for length 924

The exception escapes IsoWorld.update -> IngameState.updateInternal, the
state machine exits IngameState and the player is dumped to the main menu
with no message. This stack is not a save-writing path; it does not establish
whether all other session state was persisted.

Reproduction steps
------------------
The exact interleaving has not been reproduced deterministically.

1. Join a dedicated server.
2. Drive at high speed (60+ km/h, sustained) through a dense town so the
   World Streamer is continuously loading chunks with many rooms. Our
   affected cell had 924 IsoRoom instances at the time of the crash.
3. Keep driving until an IsoRegions buffer swap (clientProcessBuildings)
   coincides with a chunk load. Sustained driving is a suspected trigger,
   not a guaranteed reproduction or evidence that an auto-drive mod
   causes the underlying shared-map defect.

Observed
--------
Client console.txt (single occurrence in 27,101 lines; no Lua errors, no
other exceptions in the session):

  ERROR: General  f:49265 st:22,442,295,551> IngameState.updateInternal> Exception thrown
    java.lang.ArrayIndexOutOfBoundsException: Index 924 out of bounds for length 924 at HashMap.valuesToArray(null:-1).
    Stack trace:
      java.base/java.util.HashMap.valuesToArray(Unknown Source)
      java.base/java.util.HashMap$Values.toArray(Unknown Source)
      java.base/java.util.ArrayList.<init>(Unknown Source)
      zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid.recalculateBuildingAndRoomIDs(WorldRegionToMetaGrid.java:482)
      zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid.addToMetaGrid(WorldRegionToMetaGrid.java:396)
      zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid.clientProcessBuildings(WorldRegionToMetaGrid.java:144)
      zombie.iso.areas.isoregion.data.DataRoot.clientProcessBuildings(DataRoot.java:390)
      zombie.iso.areas.isoregion.IsoRegions.update(IsoRegions.java:240)
      zombie.iso.IsoWorld.updateWorld(IsoWorld.java:3338)
      zombie.iso.IsoWorld.updateInternal(IsoWorld.java:3429)
      zombie.iso.IsoWorld.update(IsoWorld.java:3362)
      zombie.gameStates.IngameState.updateInternal(IngameState.java:1508)
      zombie.gameStates.IngameState.update(IngameState.java:1328)
      zombie.gameStates.GameStateMachine.update(GameStateMachine.java:65)
      zombie.GameWindow.logic(GameWindow.java:390)
      zombie.GameWindow.frameStep(GameWindow.java:790)
      zombie.GameWindow.mainThreadStep(GameWindow.java:567)
      zombie.MainThread.mainLoop(MainThread.java:69)
  LOG  : Lua  f:49265> removing all player data
  LOG  : General f:49265> STATE: exit zombie.gameStates.IngameState

Root cause (42.20.4 bytecode, verified with javap -l against the shipped jar)
---------------------------------------------------------------------------
1. IsoMetaCell exposes room and building maps backed by plain HashMap.

2. Reader, main thread, no lock.

     WorldRegionToMetaGrid.recalculateBuildingAndRoomIDs
       line 477: copies building-map values, then rebuilds the map
       line 482: copies room-map values, then rebuilds the map

   The LineNumberTable maps line 482 to getfield isoRooms / HashMap.values /
   ArrayList.<init>, i.e. the frame in the stack above is the isoRooms copy.
   It is reached from IsoRegions.update() whenever
   the region worker requests a buffer swap on the non-server path.
   The client processes this when the region worker finishes
   a pass. Neither IsoRegions, DataRoot nor WorldRegionToMetaGrid contain a
   synchronized block or any reference to WorldStreamer; the streamer is not
   paused for this.

3. Writer, World Streamer thread, no lock.

     WorldStreamer worker -> chunk load/deserialization -> IsoChunk.getRoom
       -> IsoMetaGrid.getRoomByID

   The last method looks up room and building entries and inserts missing
   entries into their respective shared maps.

   The IsoChunk -> getRoomByID edge is the only caller of getRoom(long) in
   IsoChunk, and it sits inside the chunk deserialisation path, which on a
   client can execute on the streamer thread. Disk and received chunk
   loading both reach this method.

4. Why the exception is exactly "Index N out of bounds for length N".
   The JDK map implementation links a new entry before updating its size.
   The collection-copy operation sizes its destination from that recorded
   size, then visits the entries. A concurrent writer can invalidate that
   relationship before the copy finishes.

   A concurrent put that has linked its node but not yet incremented size
   gives the reader an array of size and a table with size + 1 nodes. That
   is the observed signature. (Any earlier unsynchronised put that corrupted
   the map's size/table invariant produces the same failure on the next
   copy, so the crash can also be deferred.)

Impact
------
* Client is kicked to the main menu with no error dialog. The player has to
  rejoin; the server is unaffected.
* Concurrent chunk loading and metagrid rebuilding expose the shared-map
  race. Dense areas and sustained movement are plausible trigger conditions;
  the report does not establish a measured failure rate.
* The building map has a similar unsynchronized copy/update path, but this
  exception identifies the room-map copy, not a building-map failure.

Suggested fixes (minimal)
-------------------------
These are proposals, not verified fixes:

1. A concurrent map may address the unsafe collection-copy operation, but
   does not make the complete rebuild atomic. A lookup could still miss a
   room during rebuilding and create a duplicate; changing only the map
   implementation is not a complete consistency fix.

2. Protect both the entire rebuild and the corresponding lookup/insertion
   sequence with the same lock. Validate lock ordering against chunk loading
   and other metagrid consumers before adopting this approach.

3. Schedule clientProcessBuildings only while the World Streamer is quiescent,
   after verifying the synchronization mechanism and other possible writers.
   The rebuild must not overlap chunk deserialization.

Attachments: full client console.txt (redacted), and javap -l output for the
three classes above showing the line-number mapping. Available on request.
```

## 給 Discord／Workshop 玩家的白話版

這份例外指向遊戲本體的房間清單並行存取問題，不是電腦效能不足的證據。背景載入地圖與主迴圈整理房間清單可能同時碰到未同步的 map，造成 client 退出到主選單；已整理證據回報官方。單憑這份 stack 不能保證整個 session 的存檔狀態，也不能把高速移動當成必現重現步驟。
