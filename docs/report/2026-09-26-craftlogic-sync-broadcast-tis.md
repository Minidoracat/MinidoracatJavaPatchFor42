# TIS 官方回報草稿 — 自動工作站製作中每秒全服廣播整份 CraftLogic 狀態

**狀態**：草稿，**尚未回報**。貼文前依 `temp/redact-expressions.txt` 自查。
**數據來源**：正式服 2026-09-26 03:06–03:13 離峰抓包（19–21 人在線）＋42.20.4 `javap` 核實；
量測細節見 MinidoracatServerAnalyze `reports/ops/2026-09-26-MIC42-stamp-traffic.md`「重送來源」。
**我方緩解**：W36（docs/patches.md 2ay），本機驗證完成、**尚未部署**，貼文時依部署狀態改寫最後一節。

**規則**：不貼反編譯 Java 源碼；class／method 名稱、bytecode 行為描述可以。不寫玩家名、IP、座標。

**審稿注意（避免過度宣稱）**：

- 17.5% 是**下限**：只計入含 anchor 的封包，被切片的後續片段沒算到；也只量了一個 30 秒離峰窗。
- 章（item modData）是 mod 寫的，只佔 GameEntity 封包的 9.7%；**送給誰與多久送一次是原版行為**，
  不宣稱 mod 造成廣播。
- 我們沒有逐 client 的已載入 chunk 集合，「遠方 client 收到即丟棄」是依 entityNetID 組成與
  client 端查找邏輯推得，不是逐包觀測。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add
tags：multiplayer, server, performance, network

### Title

`[42.20.4] [MP] Automatic crafting stations broadcast their full CraftLogic state to every client once per second (GameEntity packets ignore relevancy)`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer — dedicated server]
Server settings: [Dedicated Linux server, Java 25, 1-hour in-game day]
Mods: [Modded server. One mod stores a small tag in item modData, which makes
       the payload larger; the send cadence and the recipient list described
       below are vanilla behaviour and do not depend on any mod.]
Save: [Existing MP save. No crash, no data loss — this is a bandwidth issue.]

Summary
-------
While an automatic crafting station (drying rack, etc.) is crafting,
CraftLogic.onUpdate sends a CraftLogicSync packet every 1000 ms
(UpdateLimit(1000)). The packet is the component's full save() output,
including every in-progress craft and the complete serialization of the
consumed input items with their modData. It is sent through
Component.sendServerPacket -> GameEntityNetwork.sendPacketData with
isIgnoreConnection = true, and that branch calls INetworkPacket.sendToAll:
every fully connected client receives it, regardless of distance.

On our server, packets of type 293 (GameEntity) were 17.5% of all outgoing
UDP bytes in a 30-second capture. A drying-grass recipe lasts one in-game
day, so a single rack repeats this for the entire in-game day, to every
player online.

Measurements
------------
Read-only tcpdump of outgoing UDP, off-peak, 19-21 players online.

| Metric                                             | Value              |
|----------------------------------------------------|--------------------|
| GameEntity (type 293) share of outgoing UDP bytes  | 17.5% (lower bound)|
| Window                                             | 30 s               |
| GameEntity packets / bytes in window               | 9,589 / 9.41 MiB   |
| Tags per packet (drying grass on racks)            | 7 - 11             |
| Distinct tag values seen in 60 s                   | 96                 |
| Tag occurrences in 60 s                            | 107,641            |
| Most common tag value, occurrences in 60 s         | 39,159             |
| Clients receiving that value                       | all 20 online      |

"Lower bound": we only counted packets containing a recognizable anchor;
continuation fragments of split packets were not attributed.

Code path (42.20.4, verified with javap against the shipped jar)
-----------------------------------------------------------------
1. CraftLogicSystem.updateSimulation (server only; it returns immediately
   on the client) calls CraftLogic.onUpdate for each in-progress craft.
2. CraftLogic.onUpdate: after updating the sprite overlay, if
   GameServer.server and limit.Check() (UpdateLimit(1000)), it calls
   sendCraftLogicSync().
3. sendCraftLogicSync: EntityPacketData(CraftLogicSync) <- this.save(bb)
   (full state; DryingCraftLogic.save also appends the wetness of each
   craft) + the current recipe name, then sendServerPacket(data, null).
4. GameEntityNetwork.sendPacketData(..., isIgnoreConnection = true):
   the server branch calls INetworkPacket.sendToAll(GameEntity, ...), which
   only skips the excluded connection and connections that are not fully
   connected. There is no isRelevantTo / RelevantTo check.

The same sendToAll branch is used by every server-side GameEntity broadcast
(SyncGameEntity, UpdateUsingPlayer, and any component's sendServerPacket),
so all of them go to every client.

Why distant clients cannot use the packet
-----------------------------------------
For an IsoObject, getEntityNetID() is built from the square's x/y/z and the
object index. On the client, GameEntityNetwork.parse looks the entity up with
GameEntityManager.GetEntity(id); if the client has not loaded that square the
lookup fails and the packet is discarded (only inventory items have a
fallback via the owning player's inventory). So for world objects, only
clients that have the square loaded can apply the update.

Relevancy-gating is safe for clients that load the area later
------------------------------------------------------------
A client that approaches the station later does not need the missed syncs.
PlayerDownloadServer serializes server-loaded chunks live with
IsoChunk.SaveLoadedChunk -> IsoObject.save -> saveEntity -> Component.save,
and CraftLogic.save writes the in-progress crafts (with elapsed time) while
running; DryingCraftLogic.save appends the wetness. The client restores them
through IsoObject.load -> loadEntity -> CraftLogic.load. The chunk download
already delivers the current crafting state.

Impact
------
* Outgoing bandwidth scales with (active crafting stations) x (connected
  players), even for players nowhere near the station. Drying racks are
  popular, and each craft lasts one in-game day.
* The same full state is re-serialized every second although the only
  client-visible changes are the progress percentage and, for drying racks,
  the wetness value.

Suggested fixes
---------------
1. In GameEntityNetwork.sendPacketData's server broadcast branch, use the
   existing INetworkPacket.sendToRelative (or an equivalent relevancy check)
   for entities with a world position (IsoObject with a square, VehiclePart),
   and keep the current behaviour for inventory items, whose getX()/getY()
   are not a world position unless equipped. The same relevancy rules should
   apply to vehicle drivers, because the client's chunk map is shifted ahead
   of the vehicle.
2. Only send the periodic CraftLogicSync when client-visible content changes:
   the integer progress percentage of each in-progress craft, the in-progress
   list itself, the selected recipe, and the displayed wetness for drying
   racks. A long recipe then needs about 100 updates instead of one per
   second for the whole duration. (Note: onStart does not send anything today;
   the start is currently delivered by the next periodic sync, so a
   change-based gate must treat the in-progress list as part of the state.)
3. Alternatively, send a small progress-only packet (elapsed time per craft)
   instead of the full save(), or let the client extrapolate progress between
   syncs, since the server already knows the recipe time.

All three are server-side and keep the packet layout, so unmodified clients
keep working. Fix 2 changes client-visible progress granularity to 1%.

Reproduction
------------
No mods required.

1. Start a 42.20.4 dedicated server and connect two clients.
2. With client A, place items on a drying rack so that a drying craft starts.
3. Move client B far away from the rack (outside its loaded area).
4. Capture client B's incoming UDP (or log GameEntity packets per connection
   on the server). Client B receives a CraftLogicSync for the rack about once
   per second for as long as the craft runs, and discards it.

Server-side mitigation we prepared
----------------------------------
A bytecode patch on our server (not yet deployed at the time of writing)
redirects the single sendToAll in sendPacketData to a helper that skips
connections whose players are all far outside their chunk-grid window
(vehicle drivers, teleports and uncertain states still receive everything),
and gates the once-per-second sync in CraftLogic.onUpdate on the content
described in fix 2. It does not change the packet format.

Attachments available on request: redacted packet-count summary and javap
output for the methods above.
```

## 給 Discord／Workshop 玩家的白話版

曬草架這類自動工作站在製作期間，原版每秒把整個工作站的狀態（包含架上所有物品）送給**全伺服器每一位玩家**，
不管他們離得多遠。本服量到這類封包佔伺服器上傳流量約 17.5%。我們已整理證據回報官方，
並準備了伺服器端修補：只送給附近玩家，而且進度沒變就不送。修補後曬草架的進度會改成每 1% 更新一次，
其他行為不變，玩家不需要安裝任何東西。
