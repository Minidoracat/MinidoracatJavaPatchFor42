# TIS 官方論壇回報草稿 — 卡讀條後續（2026-09-07）

> 用途：延續 2026-09-02 Batch B 的 R1（forum topic 100905，「Timed actions can stall permanently at 100%…」）。
> 三份內容的 root cause 均以 42.20.4 反編譯（`pz-decompiled-reference/snapshots/42.20.4-20260826`）
> 與 42.20.4 jar 的 `javap` 核對過，行號為 42.20.4 行號。發文前確認不含玩家名／SteamID／主機資訊／實際座標。

## 0. 補充舊帖還是另開？（評估）

| 內容 | 決定 | 理由 |
|---|---|---|
| **F1** `PZNetKahluaTableImpl.loadComponent` NPE（第三條「既不 Accept 也不 Reject」入口） | **在 topic 100905 回覆補充** | 同一症狀（永久卡 100%、queue 堵死）、同一機制家族（parse 階段參數解析失敗未被處理、無回覆）、同一建議修法（parse 對參數失敗要走 reject）。另開會被當重複帖合併。 |
| **F2** `ActionManager.remove` 只比 byte id、跨玩家連帶取消 | **另開新帖**（Bug Reports） | 獨立缺陷：與參數解析無關，觸發者是「別的玩家」，修法在 `ActionManager`。目前是程式碼級證據；本文先寫成 code-level report，等 W10-E observe 有 `crossVictimsActive` 數據後補一段 Observed 再發，或先發並註明「reproduction data to follow」。 |
| **F3** `IsoObject.getEntityNetID()` 由座標＋物件順位推導、client/server 不一致、地板固定 index 0 撞號 | **另開新帖**（Bug Reports，entity system） | 影響面不只 timed action：`GameEntityManager.checkEntityIDChange` 的 `expected null` 錯誤、A-R2 已報的 stale `entitySet`（Entity is already registered）都是同一族。F1 的 Root cause 段只引用它，細節放這帖。 |

發文節奏：F1 先（回覆帖，最短）；F3 隔一天；F2 等 observe 數據（預計 1–2 天）再發。

---

## F1. 回覆 topic 100905：第三條入口——`loadComponent` NPE

### 中文摘要

W10 上線後 server 端仍每天數次到數十次 `Error with packet of type: NetTimedAction`，stack 固定在
`PZNetKahluaTableImpl.loadComponent:531`：`GameEntityManager.GetEntity(netID)` 回 null 沒檢查就
`getComponent` → NPE。位置在 `NetTimedAction.parse` 的 `this.actionArgs.load(...)`（比原帖的
`protectedCall` 更早），所以 parse 中斷、`processServer` 不執行、client 永久卡——與原帖同症狀、不同入口。
觸發情境：用「搬過的」鐵桶／製作台當 CraftBench 製作。8 天 98 次。

### 建議板塊

回覆到既有主題：https://theindiestone.com/forums/topic/100905-42204-mp-timed-actions-can-stall-permanently-at-100-and-block-the-whole-action-queue-when-a-packet-argument-deserializes-to-null-the-server-sends-neither-accept-nor-reject/

### Body

```text
Follow-up: a third entry point into the same "neither Accept nor Reject" stall, this time before the Lua constructor is even reached.

After deploying our server-side hotfix for the two defects above, the server log kept showing a different failure on the same packet type, 98 times in 8 days (up to 40 per session):

  ERROR: General ... at GameServer.mainLoopDealWithNetData > Error with packet of type: NetTimedAction for <steamid>
  java.lang.NullPointerException: Cannot invoke "zombie.entity.GameEntity.getComponent(zombie.entity.ComponentType)" because "gameEntity" is null
      zombie.network.PZNetKahluaTableImpl.loadComponent(PZNetKahluaTableImpl.java:531)
      zombie.network.PZNetKahluaTableImpl.load(PZNetKahluaTableImpl.java:689)
      zombie.network.PZNetKahluaTableImpl.load(PZNetKahluaTableImpl.java:546)
      zombie.core.NetTimedAction.parse(NetTimedAction.java:155)
      zombie.network.packets.INetworkPacket.parseServer(INetworkPacket.java:55)
      zombie.network.PacketTypes$PacketType.onServerPacket(PacketTypes.java:967)
      zombie.network.GameServer.mainLoopDealWithNetData(GameServer.java:1612)

Root cause (42.20.4)
--------------------
1. PZNetKahluaTableImpl.loadComponent (lines 500-505) dereferences the result of GameEntityManager.GetEntity without a null check:

     long gameEntityNetID = input.getLong();
     short componentID = input.getShort();
     GameEntity gameEntity = GameEntityManager.GetEntity(gameEntityNetID);
     return gameEntity.getComponent(ComponentType.FromId(componentID));   // NPE when the entity is unknown

   loadResource (lines 492-498) has the same shape.

2. The call sits inside NetTimedAction.parse at `this.actionArgs.load(b, connection)` (line 148 in source, javap offset 68), i.e. BEFORE the protectedCall that the original report is about (offset 167). The exception leaves parse, processServer is never called, and GameServer.mainLoopDealWithNetData swallows it. Client side this is indistinguishable from the original defect: the action parks at 100% forever and the queue is blocked.

3. Why the entity is unknown on the server - IsoObject.getEntityNetID (IsoObject.java:5831-5848) derives the net ID from the object's position in its square:

     newID = x + (y << 16) + (z << 32) + (objectIndex << 40)     // objectIndex = square.getObjects().indexOf(this); floors always use 0

   Client and server each compute this from their own square.getObjects() order, and the server-side idToEntityMap entry is only refreshed when getEntityNetID() happens to be called again. Picking up and placing a craft bench (a metal drum in the cases we traced) removes and re-inserts the IsoObject, so its index - and therefore its ID - changes; if the two sides do not end up with the same order, the ID the client sends does not exist on the server (this NPE) or resolves to a different object. Players describe exactly that: "a drum that has been moved cannot be used for crafting until relog, other benches work". I will file the ID derivation itself as a separate report since it affects more than timed actions.

Suggested fix
-------------
- loadComponent / loadResource: null-check GetEntity and return null (or throw a typed exception that parse converts into the existing `action = null` path) instead of NPE.
- NetTimedAction.parse: treat any failure while loading actionArgs the same way as a failed constructor call, so processServer runs and the client receives a Reject.
- Longer term: give IsoObject a net ID that does not depend on list order (see the separate report).

Observed after our follow-up hotfix
-----------------------------------
We extended the server-side hotfix so that (a) a failure inside actionArgs.load is turned into a Reject reply, and (b) when GetEntity misses, the server decodes the coordinates embedded in the ID and, if exactly one IsoObject on that square within reach of the requesting player carries the requested component, uses it (and refreshes its map entry). Counters and logs available on request.
```

---

## F2. 新帖：`ActionManager.remove` 只比 byte id，跨玩家連帶取消

### 中文摘要

`Action.lastId` 是各 client 自己的 static byte（1..255 循環），不同玩家可同時持有相同 id。server
端 `ActionManager.stop(Action)` 丟掉玩家身分只呼叫 `remove(action.id, true)`，而 server 分支的
`remove` 對整份清單只比 `t.id == id`、不回封包。乙送新 Request 時 `processServer` 先
`stopPlayerActions(乙)`，停掉乙掛著的動畫型動作（duration -1，server 端會掛到 30 分鐘），若甲正在跑的
製作與它同 id，甲的製作被一起無聲移除——甲永遠等不到 Done／Reject。`GeneralActionPacket`（client 取消）
走同一條 `stop`。程式碼級證據完整；線上 victim 數據由 observe patch 收集中，發文時補 Observed 段。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add
tags：multiplayer, dedicated server, timed-action, stuck

### Title

`[42.20.4] [MP] ActionManager.remove matches server-side actions by byte id only — cancelling one player's action silently removes any other player's action with the same id (ids are per-client, 1..255)`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64, LinuxGSM, OpenJDK 25 + ZGC, 254 slots; evening peaks 60–95 concurrent players]
Mods: [~80 workshop mods on the production server; the defect is in vanilla Java (class/method references below)]
Save: [Not save-specific]

Summary
-------
Server-side action ids are not unique across players, but ActionManager removes actions by id alone. Whenever a player's action is stopped on the server (new request replacing an old one, or an explicit cancel), every other player's action that happens to carry the same byte id is removed too - silently, with no Done/Reject sent. The victim's client waits forever: progress bar parked at 100%, queue blocked, only a relog helps. On a busy server this is a plausible steady source of "stuck progress bar" reports that leaves no server-side trace.

Root cause (42.20.4, decompiled + javap on the shipped jar)
-----------------------------------------------------------
1. Action.set (Action.java:37-45): `this.id = lastId++` where lastId is a static byte of the *client* JVM. Each client counts 1..255 independently, so two players can hold the same id at the same time.

2. ActionManager.stop (ActionManager.java:51-54; javap offset 24) discards the player identity:

     public static void stop(Action action) { ... remove(action.id, true); }

3. ActionManager.remove, server branch (ActionManager.java:188-199):

     List<Action> transactionForDelete = actions.stream().filter(t -> t.id == id).collect(...);
     actions.removeAll(transactionForDelete);
     for (Action action : transactionForDelete) { action.stop(); ... }

   The filter compares only id (lambda$remove$1 reads Action.id and nothing else). No packet is sent on this branch.

4. Every server-side cancel goes through stop(): NetTimedActionPacket.processServer calls ActionManager.stopPlayerActions(playerId) before starting a new request (lines 70-72), and GeneralActionPacket.processServer calls ActionManager.stop (javap offset 30).

5. The dominant trigger is the client's own cancel packet for an action the server has ALREADY completed. IsoGameCharacter.updateInternal (8986-9006) evaluates `valid = act.valid()` at the top of every frame; when the server performs an action and the resulting world change reaches the client first (grass removed, tree felled, egg taken, floor placed), the Lua isValid() of the still-queued client action turns false in that frame, `act.update()` is skipped (so the isDone -> forceComplete path inside LuaTimedActionNew.update never runs), and the action goes straight to `act.stop()` -> ActionManager.remove(id, true) -> GeneralActionPacket(reject). On the server that id no longer exists for this player (it was Done and cleared), GeneralActionPacket.processServer's getAction() builds a temporary object, stop() calls remove(id, true), and the only actions left in the list with that id belong to OTHER players - which are then removed. Every player doing quick repetitive actions (clearing grass, chopping, collecting eggs, watering animals, batch crafting) therefore sprays cancels across the whole id space. A secondary trigger is a new request stopping the sender's own parked animation-driven (-1) action via stopPlayerActions - same remove(id) path.

Client-side consequence
-----------------------
The victim's LuaTimedActionNew waits for ActionManager.isDone/isRejected (LuaTimedActionNew.java:93-98); neither ever becomes true because the server never sent a reply and the entry is still in the client's list. Same dead end as in topic 100905.

Suggested fix
-------------
- Make the server-side identity (playerId, id) instead of id alone: stop(Action) should remove that Action instance (or filter on id AND playerId), and GeneralActionPacket should pass the connection's player.
- Alternatively allocate ids on the server.
- Independently: send a Reject when the server removes an Accepted action, so the client can recover.

Observed (dedicated server, 42.20.4, 2026-09-07 15:56-22:16, 4 sessions, 60-90 concurrent players)
---------------------------------------------------------------------------------------------------
We instrumented ActionManager.stop/remove on our server (observe only, no behaviour change) and logged every removal whose victim belongs to a different player than the sender:
- 1438 cross-player removals in ~6 hours; 706 of the victims were positive-duration actions still waiting for the server to complete them (ISHandcraftAction 27% of those, ISPetAnimal, ISReadABook, ISMoveablesAction, BuildAction, ISEatFoodAction ...); 92 distinct players affected; rate grew from 70/h to 145/h with player count.
- 1435 of 1438 came through GeneralActionPacket (client cancel), 3 through stopPlayerActions.
- In 98% of the cases the sender's own action was no longer in the list (removeAll matched only other players' entries) - i.e. the cancel was for an id the server had already completed, exactly the sequence in item 5.
- Worst single case: one player's 150-second crafting action was removed four times in a row with 2-22 seconds remaining, by cancels from unrelated players; the player eventually quit.
- Senders are simply the most active players (one was clearing grass, one chopping trees / collecting eggs, one placing floors); no mod errors or abnormal traffic in their client logs.

Turning the server-side removal into "same id AND same playerId only" (our follow-up patch) reduces these to zero by construction; we will report the after-numbers once it has run for a day.
```

---

## F3. 新帖：IsoObject 的 entity net ID 由「座標＋清單順位」推導

### 中文摘要

`IsoObject.getEntityNetID()` 不是登記的 ID，而是 `x + (y<<16) + (z<<32) + (objectIndex<<40)`
現算，index 來自 `square.getObjects().indexOf(this)`，地板固定 0。三個後果：(1) 搬移／重載後 index
變、ID 變；(2) client 與 server 各算各的，順序不同就對不上（F1 的 NPE，或指到別的物件）；(3) 同格
地板與 objects[0] 撞號——`checkEntityIDChange` 印出 `idToEntityMap(<id>)=WoodFloorLvl3..., expected
null (entity=WoodenWallFrame...)`（該 log 在 42.20.4 因 `%ld` 格式字串本來印不出來，見先前回報）。
與 A-R2（stale entitySet）同族。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add
tags：multiplayer, dedicated server, entity

### Title

`[42.20.4] [MP] IsoObject entity net IDs are derived from the object's index in its square — moving an object changes its ID, client and server can disagree, and floors collide with objects[0]`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64, LinuxGSM, OpenJDK 25 + ZGC, 254 slots]
Mods: [~80 workshop mods; the defect is in vanilla Java (class/method references below)]
Save: [Not save-specific]

Summary
-------
GameEntity net IDs for IsoObjects are computed, not assigned:

  IsoObject.getEntityNetID (IsoObject.java:5831-5848)
     newID = x + (y << 16) + (z << 32) + (objectIndex << 40)
     objectIndex = this.isFloor() ? 0 : square.getObjects().indexOf(this)

The ID is recomputed lazily whenever getEntityNetID() is called and the index changed (checkEntityIDChange moves the idToEntityMap entry). This has three observable consequences on a dedicated server.

1. The ID is not stable. Picking an object up and placing it again removes it from and re-inserts it into square.getObjects(), so its index and ID change. Any packet argument that references the object by net ID (PZNetKahluaTableImpl.saveComponent / loadComponent, saveResource / loadResource) is only valid as long as both sides agree on the list order.

2. Client and server compute the ID independently from their own square.getObjects(). After a move, a chunk reload, or any difference in insertion order, the client's ID may not exist on the server - GameEntityManager.GetEntity returns null and PZNetKahluaTableImpl.loadComponent throws NPE (98 occurrences in 8 days on our server, reported as a follow-up in topic 100905) - or it may resolve to a different object on the same square. Players see it as "a craft bench that has been moved cannot be used until relog".

3. Floors always use index 0, but so does whatever object happens to be at objects[0] of a square that has no floor at index 0. checkEntityIDChange then logs:

     idToEntityMap(<id>)=WoodFloorLvl3:carpentry_02_56:...IsoThumpable@..., expected null (entity=WoodenWallFrame:carpentry_02_101:...)

   (Note: in 42.20.4 this log line never prints because the format string uses `%ld`, which java.util.Formatter rejects with UnknownFormatConversionException - reported separately. We patched the format string to obtain the lines above.)

The same family of symptoms was reported earlier as "Entity is already registered" from IsoChunk.doLoadGridsquare with stale EngineEntityManager.entitySet entries (topic 100893).

Suggested fix
-------------
- Give IsoObject a persistent per-object ID (assigned once, saved with the object, sent to clients), and use it for network references instead of the (x, y, z, index) composite.
- Until then: refresh the server-side map entry when an object is re-inserted into a square (register/unregister on add/remove) and null-check GetEntity in loadComponent/loadResource so a mismatch degrades into a rejected action rather than a dropped packet.
```
