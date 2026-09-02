# TIS 官方論壇回報草稿 — Batch B（MP 玩法／動物／網路／minor）

> 用途：貼到 The Indie Stone 官方論壇 Bug Reports 板（B42）。每份 `### Body` 為可直接複製貼上的完整內文。
> 所有 Root cause 均以 42.20.4 反編譯（`pz-decompiled-reference/snapshots/42.20.4-20260826`）或 42.20.4 jar 的 `javap` 重新核對過，行號為 42.20.4 行號。
> 撰於 2026-09-02。發文前逐份確認附件已備妥、不含玩家名／IP／實際座標。

---

## R1. MP 卡讀條永久堵塞

### 中文摘要

報 W10 兩個疊乘的 vanilla 缺陷：`loadInventoryItem` 靜默回 null → Lua ctor 例外穿過 `protectedCall` → `processServer` 從未執行；加上 `processServer` 對中間物件 `act` 設 state 卻送出 `this`。結果是 client 四道自癒全失效、動作永久卡在 100% 且整條動作佇列堵死。
優先級：**最高**（玩家可見、需重開遊戲、社群長期回報）。
附件：8/23 session 的三筆 Lua ctor 例外全 stack、8/27–9/2 我方 hotfix 的 60 筆 reject 分類表、`NetTimedActionPacket.processServer` 與 `ItemTransactionPacket.processServer` 的 javap 對照。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags：`42.20.4, multiplayer, dedicated, timed action, stuck`

### Title

`[42.20.4] [MP] Timed actions can stall permanently at 100% and block the whole action queue when a packet argument deserializes to null — the server sends neither Accept nor Reject`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific - see analysis]

Summary
-------
On a dedicated server a timed action can reach 100% and never complete: the animation keeps looping, the product is never produced, the consumed input item keeps its green job marker, and every subsequent queued action for that player is blocked as well, because ISTimedActionQueue is a single-headed sequence. The only player-side recovery is restarting the game. Two independent vanilla defects multiply into this: an InventoryItem packet argument can deserialize to null silently, and the reply packet that NetTimedActionPacket.processServer sends carries the wrong state.

Trigger conditions
------------------
Not deterministic. It requires a NetTimedAction whose serialized InventoryItem argument cannot be resolved on the server - the container is not found, or the container no longer holds that item id. In practice players hit it while crafting, while crafting after moving furniture, while eating, and while reading. It is much more frequent on a populated server.

Observed
--------
Player side: progress bar parks at 100%, character keeps looping the action animation, nothing is produced, and every later action of that player is stuck behind it. No client-side error.

Server side, one line per occurrence:

  attempted index: getWorldSprite of non-table: null
  se.krka.kahlua.vm.KahluaThread.tableget:1430
  Lua(Vanilla).new(ISMoveablesAction.lua:308)
  se.krka.kahlua.integration.LuaCaller.protectedCall:109
  zombie.core.NetTimedAction.parse
  zombie.network.packets.INetworkPacket.parseServer:55
  zombie.network.PacketTypes$PacketType.onServerPacket:967
  zombie.network.GameServer.mainLoopDealWithNetData:1611
  zombie.network.GameServer.main:909

The same shape occurs with ISReadABook.lua:492 and ISEatFoodAction.lua:298.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
1. zombie.network.PZNetKahluaTableImpl.loadInventoryItem (42.20.4, lines 473-478) returns null when the container cannot be resolved or the container has no item with that id. No log, no rejection:

     ContainerID container = new ContainerID();
     container.parse(input, connection);
     int itemId = input.getInt();
     return container.getContainer() != null ? container.getContainer().getItemWithID(itemId) : null;

   That null becomes an element of the arguments[] array that NetTimedAction.parse passes to the Lua constructor.

2. The Lua constructor indexes it on its first line, so Kahlua throws a RuntimeException. LuaCaller.protectedCall does not catch RuntimeException, so it propagates out of NetTimedAction.parse.

3. NetTimedAction.parse already contains the correct failure handling (42.20.4 NetTimedAction.java:161-164):

     LuaReturn result = LuaManager.caller.protectedCall(LuaManager.thread, functionObject, arguments);
     if (!result.isSuccess() || result.getFirst() == null) {
        this.action = null;
        return;
     }

   The exception bypasses this path entirely. parse aborts, processServer is never reached, and GameServer.mainLoopDealWithNetData swallows the exception. No Accept and no Reject is ever sent.

4. Independently, NetTimedActionPacket.processServer (42.20.4, lines 70-86) sets the state on the intermediate object but serializes this:

     NetTimedAction act = this.getAction();
     act.setState(Transaction.TransactionState.Reject);   // state set on act
     ByteBufferWriter bbw = connection.startPacket();
     PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
     this.write(bbw);                                     // serialized from this

   javap on the shipped 42.20.4 jar confirms it at the bytecode level: offsets 81 and 142 are both aload_0 immediately before invokevirtual NetTimedActionPacket.write, while both setState receivers are aload_3 (act). this.state has been Request since parse, so the initial Accept/Reject reply is transmitted as Request and the client's ActionManager.isRejected never becomes true. ItemTransactionPacket.processServer in the same codebase is the correct counter-example: offsets 25/59 call this.setState, offsets 44/78 call this.write.

Why no client-side mechanism recovers
-------------------------------------
  BaseAction.finished()   requires !waitForFinished, but LuaTimedActionNew.start sets waitForFinished true in MP, so completion can only come from the server.
  BaseAction.hasStalled() requires lastTime < 0 or currentTime < 0; when stuck, time is parked at maxTime (positive), so it is always false.
  ActionManager 30-minute timeout   only removes the entry from the list. isDone and isRejected are both prefixed with !actions.isEmpty(), so once the list is empty both return false - the stall is upgraded from "30 minutes" to "permanent".
  isUsingTimeout          ISReadABook and ISResearchRecipe return false, so even the removal never happens.

For comparison, TransactionManager.isDone / isRejected do NOT have the !isEmpty() prefix, which is why picking items up recovers by itself after roughly 20 seconds. ActionManager is missing exactly that one prefix.

Field data
----------
Two independent fingerprints from our production server:
- Defects 1-3 (null argument): in a single session on 2026-08-23, 12 `Lua(Vanilla).new` exceptions of the shape above - ISMoveablesAction.lua:308 x6, ISReadABook.lua:492 x3, ISEatFoodAction.lua:298 x3 - matching exactly the three action families players had been reporting as "stuck at 100%" (moving furniture, reading, eating).
- Defect 4 (wrong reply state): between 2026-08-27 and 2026-09-02, 60 initial Reject replies that vanilla would have transmitted with state == Request, i.e. invisible to the client. By Lua action type: ISMoveablesAction 26, BetterFirstAidQuickPatch 16, ISPickupFishAction 5, ISEatFoodAction 5, ISReadABook 3, other 5.

Suggested fix
-------------
1. loadInventoryItem: do not return a bare null. Log the failure, or make NetTimedAction.parse reject the packet when any deserialized argument is null.
2. NetTimedAction.parse: catch RuntimeException around the protectedCall so the result is isSuccess() == false and the existing "this.action = null; return;" path is actually reachable. (Error should still propagate.)
3. NetTimedActionPacket.processServer: serialize the object whose state was set, or set the state on this - matching ItemTransactionPacket.
4. Optional but worthwhile: remove the !actions.isEmpty() prefix from ActionManager.isDone / isRejected, so the 30-minute timeout degrades into a recoverable stop instead of a permanent one.

2 and 3 are both required: with only 2 the Reject is emitted but still carries state Request; with only 3, parse has already aborted and processServer is never called.

No client patch is needed: LuaTimedActionNew.update already contains complete isDone -> forceComplete and isRejected -> forceStop handling. It simply never fires today.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode patch) on our server that makes the failed protectedCall reach the existing null-action path and corrects the reply state. Since it went live (2026-08-23) the `Lua(Vanilla).new` exception has not recurred on the affected paths, the 60 Reject replies above were received by clients as retryable aborts, and we have had no further reports of stuck progress bars. Logs / bytecode diffs available on request.
```

---

## R2. `IsoHutch.load` 忽略 `addAnimalInside` 回傳 → 動物載入即滅失

### 中文摘要

報 W17：`IsoHutch.load` 對 `addAnimalInside(animal,false)` 的 boolean 回傳直接丟棄；false 時該動物已 new/load 完並 `removeFromSquare`，卻不進 hutch map、不進世界、無 log，直接被 GC。純靜態定罪（不宣稱等於正式服的放養動物流失，那是另一個持久化域）。
優先級：**高**（靜默資料損失、修法只有幾行）。
附件：42.20.4 `IsoHutch.load` / `addAnimalInside` 反編譯節錄、零 `Rand` 測試 harness 的重現說明（「有空槽但 vanilla 101 次全撞」）。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags：`42.20.4, multiplayer, dedicated, animals, hutch`

### Title

`[42.20.4] [MP] IsoHutch.load discards the addAnimalInside() return value - animals in a near-full hutch are silently destroyed on load`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific - see analysis]

Summary
-------
IsoHutch.load deserializes each animal stored inside a hutch and then calls addAnimalInside(animal, false) without inspecting the boolean it returns. When that call returns false, the animal has already been fully constructed from the save blob and removed from its square, but it is never inserted into the hutch map and never re-enters the world. The reference is simply dropped and garbage-collected. Nothing is logged. The animal is gone at the next save.

Trigger conditions
------------------
Not deterministic - slot selection uses Rand. It requires a hutch whose occupancy is close to getMaxAnimals() (20 in vanilla), or whose free slots are blocked by dead bodies or nest boxes. Reproducing it reliably needs either a nearly full hutch and many restarts, or a seeded/zero RNG.

Observed
--------
Animals disappear from a hutch across a server restart or a chunk reload. The console shows nothing at all - no warning, no error, no "animal already exists" line. From the player's point of view a hutch that had, say, 18 birds in it comes back with fewer, with no indication of why.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
zombie.iso.objects.IsoHutch.load, 42.20.4 line 882:

    for (int ix = 0; ix < loadedAnimals.size(); ix++) {
       IsoAnimal animal = loadedAnimals.get(ix);
       this.addAnimalInside(animal, false);      // return value discarded
    }

In bytecode the call is followed immediately by POP. Each animal in loadedAnimals was created and loaded a few lines earlier and then explicitly removeFromSquare()'d (42.20.4 line 864 in the pre-212 branch; the worldVersion >= 212 branch is equivalent), so at this point the only thing that can put the animal anywhere is addAnimalInside.

IsoHutch.addAnimalInside(IsoAnimal, boolean), 42.20.4 lines 765-803:

  - line 770-772: when preferredHutchPosition == -1, pick Rand.Next(0, getMaxAnimals()).
  - lines 776-786: while the preferred slot is occupied by animalInside, or by deadBodiesInside, or rejected by checkNestBoxPrefPosition, re-roll the position; give up after 100 retries ("if (++tries > 100) break;").
  - line 788: the final placement checks ONLY animalInside.get(pos) == null. If the loop gave up while sitting on an occupied slot, this is false and line 801 returns false.

The retry loop is a random walk over [0, maxAnimals), not a scan, so with a mostly full hutch 101 independent draws can all land on occupied slots even when a free slot exists. There is no fallback and no message.

Note that addAnimalInside returns false for two very different reasons: "animal already present in animalInside" (lines 766-768, which does warn) and "no free slot after 101 draws" (line 801, entirely silent). Only the second one loses data.

Field data
----------
This one is convicted statically (source plus bytecode); we do not have a runtime capture pinning a specific in-game loss to this exact call, and we are careful not to overclaim. For context on why we went looking: over one 39-hour window our production server lost hens 206 -> 123 (-40%) and turkeys 20 -> 7 (-65%) map-wide with no crash and with every world save completing normally, and one penned rabbit group went 16 -> 3. Free-range animals live in a different persistence domain (the animal population manager) than hutch contents, so we explicitly do NOT attribute those numbers to this defect. What matches is the signature: animals disappear, nothing is logged, and no save fails.

Suggested fix
-------------
Minimal:
1. Consume the return value in IsoHutch.load. On false, log at least the animal type/id and the hutch coordinates, so a silent loss becomes a diagnosable event.

Better, and still small:
2. Make placement deterministic on the load path. After the random preference fails, scan slots 0..getMaxAnimals()-1 in order and take the first slot where animalInside.get(i) == null, preferably preferring slots where deadBodiesInside.get(i) is also null. This is one linear scan per animal, only on load, and it is bounded by maxAnimals (20).
3. If the hutch really is full, keep the animal in the world or emit a CRITICAL rather than dropping the reference. Do not silently create a slot beyond maxAnimals.

When recovering, make sure to restore the same state vanilla sets on the success path (lines 789-798): the animalInside map entry, animal.hutch, setPreferredHutchPosition and setHutchPosition to the actual slot, setItemID(0), and tryRemoveAnimalFromWorld. Leaving preferredHutchPosition stale would make later re-rolls and cage in/out operations see the wrong position.

The "already present" false at line 766 should stay a failure - recovering from that one would double-slot the same animal.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode patch) that consumes the return value and performs exactly the ordered fallback scan above. With a zero-RNG harness we can deterministically construct the case "a free slot exists but vanilla misses it in 101 draws" and show the animal surviving with the patch and vanishing without it. Logs / bytecode diffs available on request.
```

---

## R3. 動物對 stale 食槽參照 `faceThisObject` NPE

### 中文摘要

報 W22：`IsoObject.getClosestSpriteGridObject` 在 sprite-grid 清單為空時回 null，`IsoGameCharacter.faceThisObject` 無條件解參考 → 動物狀態機每 tick 炸、卡 idle 不轉 eat/walk。9/1–9/2 兩天 3386 次（≈70/h），是我方 log 最大單一例外源。
優先級：**高**（一行修法、有行為後果、噪音第一名）。
附件：`StateMachine.stateExecute` 例外樣本（含 caller 分佈 2366/1020）、42.20.4 `faceThisObject` javap（offset 200→206 無 ifnull）。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags：`42.20.4, multiplayer, dedicated, animals, exception`

### Title

`[42.20.4] [MP] IsoGameCharacter.faceThisObject dereferences a null result from getClosestSpriteGridObject - animal state machines throw ~70 NPE/hour and stick in idle`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code.]
Save: [Existing MP save; not save-specific - see analysis]

Summary
-------
IsoObject.getClosestSpriteGridObject can return null, and IsoGameCharacter.faceThisObject dereferences that result without a check. On a dedicated server the animal state machine hits this continuously through eatFromTrough / drinkFromTrough. The exception aborts state.execute for that tick, so the animal never reaches the changeState call that follows faceThisObject: it stays in idle and re-throws on the next tick, indefinitely, until something else overwrites the stale reference.

Trigger conditions
------------------
Not deterministic. It needs an animal holding a reference to a sprite-grid object (a feeding or water trough) that is no longer listed on its grid square - destroyed, picked up, or moved - or whose grid squares are not currently loaded on the server. On a farm server with troughs this is continuous.

Observed
--------
3,386 occurrences over two days (2026-09-01 to 2026-09-02), roughly 70 per hour, 100% the same fingerprint, 8 stack lines each:

  ERROR: StateMachine.stateExecute> Exception thrown
  java.lang.NullPointerException: Cannot invoke "zombie.iso.IsoObject.getFacingPosition(zombie.iso.Vector2)" because "object" is null
      at zombie.characters.IsoGameCharacter.faceThisObject

Callers: AnimalIdleState.execute 2,366 and AnimalEatState.execute 1,020. This was by a wide margin the single largest exception source in our console.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
IsoGameCharacter.faceThisObject(IsoObject), 42.20.4 lines 10387-10391:

    if (object.hasSpriteGrid()) {
       object = object.getClosestSpriteGridObject(this.getX(), this.getY());
    }

    object.getFacingPosition(facingPosition);

javap on the shipped 42.20.4 jar shows the same thing with no null check in between:

  197: invokevirtual getY:()F
  200: invokevirtual zombie/iso/IsoObject.getClosestSpriteGridObject:(FF)Lzombie/iso/IsoObject;
  203: astore_1
  204: aload_1
  206: invokevirtual zombie/iso/IsoObject.getFacingPosition:(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;

IsoObject.getClosestSpriteGridObject, 42.20.4 lines 5432-5452, initialises "closest = null" (line 5437) and only assigns inside the loop over getSpriteGridObjectsIncludingSelf(...). When that list is empty it returns null at line 5450.

The list comes from IsoObject.getSpriteGridObjects(result, true), 42.20.4 lines 5369-5404. Even with bAddSelf = true, "self" is only added when self is still present in its own square's object list and its sprite grid still matches - the membership test at lines 5391-5395 iterates testSq.getObjects() and requires object.getSpriteGrid() == spriteGrid, and testSq itself comes from getCell().getGridSquare(x, y, z) at line 5389, which returns null for unloaded squares. So an object that has been removed from the world, or whose grid squares are not loaded on the server, yields an empty list and therefore a null "closest". This is entirely vanilla; no mod is involved in that path.

Worth noting the asymmetry inside faceThisObject itself: the BaseVehicle and BarricadeAble branches above (lines 10375-10385) all handle their cases explicitly, and the sibling method faceThisObjectAlt (line 10422) has the same call shape but produced zero hits in our logs. This reads like an oversight in one branch rather than an intentional invariant.

Field data
----------
3,386 exceptions in 48 hours (~70/h), caller split 2,366 AnimalIdleState.execute / 1,020 AnimalEatState.execute, single NPE message, 8 stack lines each.

The cost is not only log volume. AnimalIdleState.execute calls faceThisObject before changeState(AnimalEatState / AnimalWalkState), so when the NPE fires both the facing and the state transition are lost. The animal is stuck in idle and does not proceed to eat, every tick, for as long as the stale trough reference survives.

Suggested fix
-------------
The smallest correct change is local to faceThisObject:

    if (object.hasSpriteGrid()) {
       IsoObject closest = object.getClosestSpriteGridObject(this.getX(), this.getY());
       if (closest != null) {
          object = closest;
       }
    }
    object.getFacingPosition(facingPosition);

Falling back to the original object means the character faces that object's own position, which is the sensible degradation and cannot throw.

An alternative is to make getClosestSpriteGridObject return "this" instead of null on an empty list, which would match its own early return at line 5434 for objects that have no sprite grid at all. That is arguably the more consistent contract, but it changes behaviour for every caller, so the local guard is the smaller change. faceThisObjectAlt has the same shape and would benefit from the same guard.

Separately, the underlying cause of the stale references is worth addressing: clearing the animal-side eatFromTrough / drinkFromTrough reference when the trough leaves the world would remove the null case at the source instead of tolerating it.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode patch) that substitutes the original object when the vanilla call returns null. The StateMachine.stateExecute NPE fingerprint went to zero and animals resumed transitioning into the eat state; the diagnostic output for the first fallbacks identified the objects as feeding/water troughs whose square no longer contained them, confirming the stale-reference reading. Logs / bytecode diffs available on request.
```

---

## R4. 衣物／visuals 同步三叢集

### 中文摘要

報 W20 三點：(b) `ItemDescription` ctor 對 baseTexture/textureChoice 有 `getVisual()==null` 守衛、唯 tint 漏 → 一件 null-visual 穿戴物讓該玩家的衣物廣播 per-connection 全滅；(c) `SyncVisualsPacket.parse` 純 positional、count 不符整包丟；(a) `ContainerID` 雙參 set 對 x/y/z 有 raw `square` 守衛、對 `getObjects()` 沒有。8 天 480+ 筆 (b) 全同一玩家，(c) 同人 `wire-local=+1`。
優先級：**中高**（(b) 修法一行，影響單一玩家全服可見度）。
附件：nullVisual 歸因統計（單一玩家 480+）、(c) mismatch 的 signed diff 分佈、`ContainerID` 探針的 `o class` 分佈（全為 IsoPlayer）。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags：`42.20.4, multiplayer, dedicated, clothing, sync`

### Title

`[42.20.4] [MP] Clothing/visuals sync: an unguarded getVisual().getTint() disables all clothing broadcasts for one player, SyncVisualsPacket drops whole packets on count mismatch, and ContainerID reads a raw square field`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defects are in vanilla Java (class/method references below) and do not involve any mod code.]
Save: [Existing MP save; not save-specific - see analysis]

Summary
-------
Three defects in the clothing/visuals sync path, reported together because two share a root cause. (b) is the damaging one: the SyncClothingPacket$ItemDescription constructor guards getVisual() == null on two of its three fields and not the third, so one worn item with a null ItemVisual makes every clothing broadcast for that player throw - that player's clothing stops syncing for everyone. (c) SyncVisualsPacket.parse rejects a whole packet on a count mismatch, caused by the same item. (a) is a low-frequency NPE from an unguarded raw square field read in ContainerID.

Trigger conditions
------------------
Not deterministic. (b) needs a worn item whose getVisual() is null - a clothing asset that is missing or not yet ready. (c) follows from the same item. (a) needs a container parent whose raw square field is null while getSquare() is non-null.

Observed
--------
Server console, over 8 days on our server:
  - "INetworkPacket.send> Exception thrown" plus an NPE fingerprint, repeatedly, always for the same single player: 480+ occurrences of (b). That is a per-connection amplified count - send catches per connection, and sendToAll / sendToRelative re-run setData for each relevant connection.
  - "Player has X itemVisuals but server tries to sync Y ones" for the same player, with a constant difference of exactly +1 on the wire side (client claims one more than the server has). Bursts of 6 and 20 over separate hours.
  - "Error with packet of type: SyncItemFields", 40 times over 2 days: that is (a).

Player-visible effect of (b): other players stop seeing that player's clothing updates. Of (a): the item removal still commits server-side but the client is never told, so item state desyncs.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
(b) SyncClothingPacket$ItemDescription constructor, 42.20.4 SyncClothingPacket.java:259-262:

    this.location      = item.getLocation();
    this.baseTexture   = item.getItem().getVisual() == null ? -1 : item.getItem().getVisual().getBaseTexture();
    this.textureChoice = item.getItem().getVisual() == null ? -1 : item.getItem().getVisual().getTextureChoice();
    this.tint          = item.getItem().getVisual().getTint();      // line 262, no guard

The constructor defends itself on two consecutive lines and omits the guard on the third. Once it throws, no clothing packet for that player can be constructed at all, for any connection.

(c) SyncVisualsPacket.parse, 42.20.4 lines 57-65: it rebuilds the visuals list from the server-side player via getItemVisuals, reads a byte count from the wire, and on mismatch logs and returns for the whole packet; isConsistent (line 53) makes the same comparison, so the packet is neither processed nor forwarded. The wire format is purely positional - count, then patch/dirt/blood in order, with no per-entry item identity - so rejecting is safer than realigning. That makes (b) and (c) reinforce each other.

The link between (b) and (c): WornItems.getItemVisuals, 42.20.4 lines 155-165, skips any worn item whose getVisual() is null:

    ItemVisual itemVisual = item.getVisual();
    if (itemVisual != null) { ... itemVisuals.add(itemVisual); }

So exactly one null-visual worn item both makes the (b) constructor throw and makes the server-side count one lower than the client's - which is precisely the constant +1 we measured.

(a) ContainerID.set(ItemContainer), 42.20.4 line 94, delegates to ContainerID.set(ItemContainer, IsoObject), 42.20.4 lines 160-188. That method guards the raw field for the coordinates:

    if (o.square != null) { this.x = o.square.getX(); this.y = o.square.getY(); this.z = (byte)o.square.getZ(); }   // 161-165

and then dereferences the same raw field unguarded, twice:

    this.index = (short)o.square.getObjects().indexOf(o);   // lines 182 and 186

Upstream callers test getSquare() instead, and IsoMovingObject.getSquare() returns "current != null ? current : square", so it can be non-null while the raw square field is null (IsoGameCharacter construction only populates current). Our probe found every observed case had o = IsoPlayer, getSquare() non-null, raw square null, reached from SyncItemFieldsPacket.setData via containerId.set(item.getContainer()) (42.20.4 SyncItemFieldsPacket.java:126). The NPE is swallowed by the per-connection catch, so the inventory mutation still commits and the client is never notified.

Field data
----------
(b) 480+ occurrences over 8 days, all attributable to a single player once instrumented. (c) mismatches for the same player, wire minus local = +1 in every sample. (a) 40 "Error with packet of type: SyncItemFields" over 2 days, 1-5 per session.

Suggested fix
-------------
1. (b) Add the same getVisual() == null guard on the tint line, or hoist "ItemVisual v = item.getItem().getVisual();" once and branch. SyncClothingPacket.write also reads the tint field, so a null visual needs a substitute value, not just a null-safe getter. Do NOT instead filter the item out of the packet: SyncClothingPacket.process removes worn items the packet does not list, so filtering reads remotely as undressing.
2. (b) upstream: decide what a worn item with a null ItemVisual means. If it is always transient, a retry is fine; if it can persist, then WornItems.getItemVisuals skipping it is what silently breaks the positional protocol downstream.
3. (c) No realignment is safe with the current wire format. The useful change is per-entry identity (item id or body location) so the receiver can tell which entry is missing, or at minimum logging the skipped item.
4. (a) Read getSquare() instead of the raw square field in ContainerID.set(ItemContainer, IsoObject), or fall back to another ContainerType when it is null. This changes packet-addressing semantics for every container packet, so it deserves its own review.

We validated (b) and the (b)/(c) link with an experimental server-side hotfix (ASM bytecode patch). Instrumenting the tint path attributed all 480+ occurrences to one player and one worn item; substituting a white tint removed the send-exception fingerprint entirely. The (c) mismatches for the same player did NOT stop - which confirms they are caused by the missing ItemVisual itself, not by the tint dereference. Logs / bytecode diffs available on request.
```

---

## R5. 車輛 DB chunk 索引不一致（車輛永久不可見）

### 中文摘要

W12 既有草稿（42.20.3）更新到 42.20.4 並精簡：`VehicleBuffer.set` 的 wx/wy 取自 `vehicle.chunk`、x/y 取自 physics，兩來源無 invariant；`resetForStore` 把 pooled chunk 的 wx/wy 清成 0,0 卻不清車輛的反向參照；載入只查 `WHERE wx=? AND wy=?`。42.20.4 逐行核對後 `VehicleBuffer.set` 未變。
優先級：**高**（永久資料不可達、修法四行、涵蓋所有 persistence 路徑）。
附件：三筆實案 SQLite 前後列、8/27–9/2 hotfix 的 182 筆修正分佈（|Δ|=1 ×176、|Δ|=2 ×6）、8/28 兩輛 NaN 車紀錄。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags：`42.20.4, multiplayer, dedicated, vehicles, data loss`

### Title

`[42.20.4] [MP] Vehicles become permanently invisible: VehiclesDB2 writes wx/wy from a stale or pooled vehicle.chunk while x/y come from physics`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The defect is in vanilla Java (class/method references below) and does not involve any mod code; no mod writes vehicles.db chunk fields.]
Save: [Existing MP save; the corrupted rows persist across restarts and are not recoverable in-game]

Summary
-------
A vehicle can remain present and structurally valid in vehicles.db - correct physical x,y, intact serialized BLOB - while its wx,wy chunk index points somewhere else. Chunk loading queries vehicles by wx,wy and never by x,y, so the vehicle can never be found at the place it actually is. It becomes permanently invisible after a reconnect or restart, and repeated chunk reloads do not recover it.

Trigger conditions
------------------
Not deterministic - a lifecycle race between chunk pooling and vehicle persistence. The clearest trigger is a player disconnecting while still seated in a vehicle, which makes GameServer.disconnectPlayer() save it immediately. Long drives across many chunks and cell unloads are the other conditions present in our incidents.

Observed
--------
- The vehicle stalls or looks desynchronized while driving.
- The player disconnects/reconnects, or the server restarts shortly afterwards.
- The vehicle is no longer visible at its final location, or anywhere along the route.
- The database row still exists with the correct x,y and an intact BLOB.
- No error is logged at any point.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
1. VehiclesDB2$VehicleBuffer.set(BaseVehicle), 42.20.4 VehiclesDB2.java:1028-1032, takes the two halves of the row from two independent sources:

     this.id = vehicle.sqlId;
     this.wx = vehicle.chunk.wx;     // from the chunk back-reference
     this.wy = vehicle.chunk.wy;
     this.x  = vehicle.getX();       // from physics
     this.y  = vehicle.getY();

   No invariant is checked before addToDB (42.20.4 lines 830-838) or updateDB (lines 854-862) commit the row.

2. Loading is by chunk index only. 42.20.4 lines 705 and 737:
     SELECT id, x, y, data, worldversion, inMeta FROM vehicles WHERE wx=? AND wy=?
   A mismatched row therefore cannot be discovered from the chunk that physically contains the vehicle.

3. IsoChunk.resetForStore(), 42.20.4 IsoChunk.java:5226-5256, clears this.vehicles (line 5247) and sets this.wx = 0; this.wy = 0 (lines 5255-5256) - but it does not clear the back-references that BaseVehicle objects still hold in vehicle.chunk. The same IsoChunk instance is then pushed into IsoChunkMap.chunkStore (lines 2265 and 3380) and later re-assigned arbitrary coordinates (LoadOrCreate lines 2313-2314, LoadBrandNew lines 2235-2236).

4. BaseVehicle.update() advances physical x,y but only rebinds vehicle.chunk when current is non-null and points at a different chunk, and current itself is only refreshed in postupdate(). GameServer.disconnectPlayer() saves the associated vehicle before clearing the player from it.

So a save taken after resetForStore and before pool checkout writes wx,wy = 0,0; a save after checkout writes the recycled chunk's unrelated coordinates. Both produce a row with correct x,y that is unreachable from its own chunk. We have no object-identity logging across reset/reuse/save, so that final interleaving is a high-confidence reading rather than a direct trace - but the two observed row shapes match the two states exactly.

Field data
----------
Three incidents within roughly one day on our server (first seen on 42.20.3; VehicleBuffer.set is unchanged in 42.20.4):

  sql id | script       | x, y                     | stored wx,wy | correct wx,wy
  152    | 84mercLWB4   | 9432.836, 11207.054      | 1168, 969    | 1179, 1400
  64     | 92nissanGTR  | 10591.696, 10335.673     | 0, 0         | 1323, 1291
  2518   | 90bmwE30m3   | 13793.331, 3864.297      | 0, 0         | 1724, 483

All three rows and BLOBs were intact. Recomputing wx = floor(x/8), wy = floor(y/8) during a stopped-server window made all three vehicles load again without touching their serialized data.

Since then we have run a persistence-boundary guard continuously. Between 2026-08-27 and 2026-09-02 it corrected 182 writes: 176 with |delta chunk| = 1 and 6 with |delta chunk| = 2 on one axis. So the common case is not the dramatic 0,0 row but a vehicle whose chunk back-reference is one chunk behind its physics position - which produces the same unreachable row. On 2026-08-28 it also caught two vehicles saved with non-finite x/y (NaN) and wx,wy = 0,0; there the guard keeps the vanilla values and logs, since no position can be derived from NaN.

Suggested fix
-------------
Narrow, at the persistence boundary, in VehicleBuffer.set:

    float x = vehicle.getX();
    float y = vehicle.getY();
    buffer.x  = x;
    buffer.y  = y;
    buffer.wx = PZMath.fastfloor(x / 8.0F);
    buffer.wy = PZMath.fastfloor(y / 8.0F);

with a guard so non-finite coordinates fall back to current behaviour plus a log rather than guessing a location. One change point covers add/update, disconnect, cell unload, trailers, and both statements, and touches neither physics nor chunk membership. It does not repair already-corrupted rows.

A deeper lifecycle fix would clear or rebind stale vehicle.chunk references before an IsoChunk enters the pool in resetForStore(), but that has a much larger MP/physics/towing risk surface. The persistence invariant is worth having regardless.

Useful diagnostics: when vehicle.chunk.wx/wy differ from floor(x/8),floor(y/8), log the sql id, both coordinate pairs, x,y, System.identityHashCode(vehicle.chunk), and whether chunk.vehicles still contains the vehicle. Matching the same chunk identity across reset, reuse and save would close the last gap.

The 42.20.1 changelog fixed a related class of problem ("vehicles could temporarily disappear for players after another player disconnected"); the persistent database-index variant here is a different, non-temporary failure and still occurs on 42.20.4.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode patch) implementing exactly the invariant above; the 182 corrections and the two NaN cases cited are its counters, and no vehicle has become unreachable since it was deployed. Logs / bytecode diffs available on request.
```

---

## R6. 動物同步 relevancy 半徑 10/8 ＋ requested 路徑無閘

### 中文摘要

W13＋W14 合併，既有草稿更新到 42.20.4（`(getRelevantRange()-2)*10` 在 42.20.4 `AnimalSynchronizationManager.java:122`、`setRequested` 在 57-60 仍無 relevancy／無冷卻、150 上限在 107）並精簡到論壇長度。含 pcap 量測與 W13 上線後的殘留量測（598 份 full、96.2% 在環帶、98.5% 來自單一載具連線）。
優先級：**中**（無 crash，但吃掉近四成上傳；requested 路徑另有放大面）。
附件：pcap decoder 統計摘要（去識別化）、W13 前後對照、`range` 值以便重算環帶。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags：`42.20.4, multiplayer, dedicated, animals, network`

### Title

`[42.20.4] [MP] Animal relevancy radius is 10/8 of the client's guaranteed loaded half-width, and the requested path has no cooldown or range check - a repeating full-snapshot loop consuming ~38% of server upload`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. The geometry defect is in vanilla Java (class/method references below); the affected animals were wild and no mod touches the animal sync path.]
Save: [Existing MP save; not save-specific - see analysis]

Summary
-------
Animals standing between the client's loaded chunk boundary and the server's animal relevancy radius produce a request/response cycle that never converges: the server advertises the animal in a lightweight update; the client has no local instance for that onlineID and requests the full animal; the server replies with a complete IsoAnimal snapshot (~1.1 KiB, full genome); the client's AnimalPacket.isConsistent requires the target grid square to be loaded, so outside the loaded area the body is skipped and no instance is created; 800-1000 ms later the next update arrives and it repeats. On our server these snapshots were 38.3-39.8% of outgoing bytes in steady state and 87.2% of them were repeats. A second, independent defect on the same path is that the client-supplied requested set is completely ungated.

Trigger conditions
------------------
Not deterministic (it depends where animals happen to stand), but continuous on any server with wild animals and enough players. No mods required.

Observed / measurements
-----------------------
Read-only tcpdump capture decoded with a purpose-built parser (UDP -> RakNet connected datagram with reliability/split headers -> reassembly -> PZ user message -> PacketType -> AnimalUpdatePacket requested section). Window: 8.03 s, 25,000 bidirectional datagrams.

  client -> server requested animal IDs           109
  server -> client full animal snapshots          109
  distinct (client endpoint, onlineID) pairs       14
  pairs that repeated within 5 s               14 / 14
  full snapshots that were repeats           95 / 109 (87.2%)
  requests that received a response         109 / 109 (median 72.7 ms)
  average dataSize per full snapshot         1,125.4 bytes
  distance from requesting player to animal  70.4 - 91.5 squares
  AnimalUpdatePacket parse errors                   0

Two benign explanations are ruled out: not "many animals entering view" (only 14 distinct pairs produced 109 snapshots), and not packet loss (every request got a response, mostly on reliable frames, yet the same ID was requested again on the next tick). We have no per-request record of the client's loaded state, so "every snapshot was discarded" is an inference; the repetition is the observation.

Root cause (decompiled 42.20.4, class/method references)
--------------------------------------------------------
GameServer.receivePlayerConnect stores, from the client-provided chunk grid width:
  range = clamp(width, 12, 20);  relevantRange = range / 2 + 2;  chunkGridWidth = range   (integer division)

For a normal unclamped odd width, the client chunk window has a guaranteed half-width lower bound of (range / 2) * 8 squares once streaming completes. AnimalSynchronizationManager.sendUpdateToClient uses a different factor - 42.20.4 AnimalSynchronizationManager.java:122:

    if (animal != null && connection.RelevantTo(animal.getX(), animal.getY(), (connection.getRelevantRange() - 2) * 10)) {

(getRelevantRange() - 2) * 10 == (range / 2) * 10, i.e. 10/8 of that bound, so the extra ring covers squares for which the client may have no GridSquare. RelevantTo is an axis-aligned square test, so the over-reach is larger on the diagonals. isAnimalOnScreen reuses the same expression at 42.20.4 line 187 for an unrelated purpose (800 vs 1000 ms cadence), so the * 10 reads like a generic "relevancy-ish distance" rather than a value chosen against a hard client-side requirement. ClientServerMap.loaded[] is no substitute for the client's loaded set - it tracks 64-square server-cell isLoaded state, not per-chunk streaming completion.

Second defect - AnimalSynchronizationManager.setRequested, 42.20.4 lines 57-60, copies whatever set the client sends straight into the server map:

    HashSet<Short> r = requests.computeIfAbsent(connection.getConnectedGUID(), k -> new HashSet<>());
    r.clear();
    r.addAll(request);

No relevancy check, no rate limit, no cooldown. The only bound is the send loop's "if (animalsCount >= 150) break;" at 42.20.4 line 107, and requestedCount is read from the wire as an unbounded int. An authenticated client can therefore request arbitrary animal onlineIDs and receive full IsoAnimal.save() snapshots for animals it should not be able to observe.

Third factor - IsoChunkMap.ProcessChunkPos moves the client's chunk-map centre ahead of the player by currentSpeedKmHour / 5 squares while driving (min(s * 2, 20) as a passenger), and the server's releventPos knows nothing about that look-ahead. So while driving, any player-centred server radius both withholds animals in loaded chunks ahead of the vehicle and keeps advertising animals in unloaded chunks behind it.

Field data
----------
After clamping the radius for walking players only, the loop disappeared for the walking population (0 requests in a 60-second re-measurement), but 598 full snapshots remained: 96.2% inside the vanilla over-reach ring and 98.5% to a single connection that was in a vehicle for the whole window. 172 of 181 tuples re-requested the same animal at least 1 s after receiving a full snapshot. That residual is what a geometry fix structurally cannot reach.

Suggested fix
-------------
1. Derive animal relevancy from the client's loaded set rather than relevantRange. Only the client knows it exactly; a server-side approximation cannot account for vehicle look-ahead or streaming chunks.
2. Gate setRequested: reject onlineIDs outside the requesting connection's plausible area. That fixes residual loops and closes the amplification surface at once.
3. Add a per-connection/per-animal cooldown for full snapshots, so any future mismatch degrades into one wasted packet instead of a sustained loop. This is the only one of the three that also works in the vehicle case, because it does not depend on geometry.
4. Client side: skip the request when the target square is not loaded, and/or keep a short negative cache for discarded IDs.
5. Independently, shrink the animal wire format: genome field names are a fixed schema but are sent as strings three times per gene (gene plus both alleles), which is most of the ~1.1 KiB. That needs both sides updated.

Fixes 2 and 3 are purely server-side and preserve the packet layout, so unmodified clients keep working. Cheap confirmation: log (connection, onlineID) for every entry AnimalUpdatePacket.write serializes into the requested section.

We validated the diagnosis with an experimental server-side hotfix (ASM bytecode patch): clamping the radius to (getChunkGridWidth() / 2) * 8 for walking players with trusted odd widths, plus a per-connection/per-animal cooldown and a generous range check on the requested path. The two measurement sets above are its before and after. We do not claim the clamp is loss-free - chunk alignment means the wider side can withhold animals by under 8 squares, and vehicles, clamped widths (12 and 20) and even widths must bypass it entirely, which is why we regard the cooldown rather than the geometry as the fix that generalises. Logs / bytecode diffs available on request.
```

---

## R7. 兩項 console log spam（minor）

### 中文摘要

兩項純噪音（無玩法影響），合成一份短報告：(i) `IsoObject.syncIsoObject` 在 `getObjectIndex()==-1` 時用 `System.out.println` 印 `ERROR:`，B42 建造流程每次必觸發，四天 11,567 行；(ii) `SpriteConfig.initObjectInfo` 對 19 個 vanilla 物件每次載入必刷 `Invalid SpriteConfig object!`，42.20.3 實測 26h 23,517 行。
優先級：**低**（minor，但會淹沒真錯誤）。
附件：兩段 log 樣本與計數指令、19 個 vanilla 物件名單。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/（發新主題：https://theindiestone.com/forums/forum/85-bug-reports/?do=add）
建議 tags：`42.20.4, multiplayer, dedicated, log spam`

### Title

`[42.20.4] [MP] Two vanilla console spam sources on a dedicated server: IsoObject.syncIsoObject "not found on square" (~120 lines/hour) and SpriteConfig "Invalid SpriteConfig object!" for base-game objects`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated, Linux x86_64 (Ubuntu 24.04 LXC, 8 vCPU / 62 GB), LinuxGSM, Azul Zulu OpenJDK 25 + ZGC, 254 slots; 7-day average ≈30 concurrent players, evening peaks 60–95 (max 95 on 2026-09-01), 465 distinct players in the last 7 days]
Mods: [~80 workshop mods on the production server where this was observed. Both messages are emitted by vanilla Java for vanilla conditions (class/method references below).]
Save: [Existing MP save; not save-specific]

Both items below are minor: no gameplay impact, no crash. They matter only because the volume buries genuine errors in server-console.txt and costs log I/O on a hot path. Reporting them together since neither warrants its own thread.

(i) IsoObject.syncIsoObject prints an ERROR line on every completed build
-------------------------------------------------------------------------
Reproduction: build anything with the B42 construction flow on a dedicated server. Every "ISBuildIsoEntity -> consume success" is followed by one line:

  ERROR: IsoThumpable not found on square <x>,<y>,<z>

Root cause, decompiled 42.20.4, zombie/iso/IsoObject.java:866-873:

    public void syncIsoObject(boolean bRemote, byte val, UdpConnection source, ByteBufferReader bb) {
       if (this.square == null) {
          System.out.println("ERROR: " + this.getClass().getSimpleName() + " square is null");
       } else if (this.getObjectIndex() == -1) {
          System.out.println("ERROR: " + this.getClass().getSimpleName() + " not found on square " + this.square.getX() + "," + ...);
       } else { ... }

The build flow replaces the temporary IsoThumpable with an IsoEntity on the square before the sync call, so getObjectIndex() is always -1 for that object and the branch is taken every single time. Two things make this worse than an ordinary warning: it is written with System.out.println, so it bypasses the debug-channel filters entirely, and it is labelled ERROR for what is the expected outcome of the vanilla flow.

Field data: 11,567 lines over four days (2026-08-30 to 2026-09-02), roughly 120 per hour.

Suggested fix: skip the sync call (or downgrade the message to trace) when the object has already been replaced on its square. Keep the "square is null" branch, and keep the not-found message for other classes - IsoDoor, IsoWindow, IsoStove and so on genuinely indicate breakage there.

(ii) SpriteConfig warns "Invalid SpriteConfig object!" for base-game objects
----------------------------------------------------------------------------
Reproduction: run any server that loads these tiles - which is any server.

Root cause, decompiled 42.20.4, zombie/entity/components/spriteconfig/SpriteConfig.java:67-70, inside initObjectInfo():

    if (!this.isValid()) {
       DebugType.General.warn("Invalid SpriteConfig object! scripted object = " + (this.objectInfo != null ? this.objectInfo.getName() : "null"));
       this.resetObjectInfo();
    }

It fires for base-game objects on every load, i.e. what is inconsistent is the shipped sprite-config data, not the server. Names observed on ours: SandFloor, WoodenDarkWallLvl3, GravelFloor, Floor_SpringGrass, DoubleDoor, WoodenWindowFrameLvl3, WoodFloorLvl2, Wood_DoubleDoorDark, WoodDoorFrameLvl3, Fences_MetalFarmGate, DoubleWireGate, BrickWallLvl2, MetalSmallWireFence, BrickWindowFrameLvl2, Piano, WoodenWallLvl3, MetalBigWireFence, WoodFloorLvl3, Wooden_Windows.

Field data: 23,517 lines in 26 hours measured on 42.20.3; the same names still warn on 42.20.4.

Suggested fix: correct the sprite-config entries for those objects so isValid() passes. The warning itself is useful for real mod errors and should stay - the fix belongs in the data, not the log call.

We suppress both messages on our server with an experimental server-side log filter (ASM bytecode patch on the two call sites). That is a workaround for readability only; the underlying conditions are unchanged. Log samples and the counting commands are available on request.
```
