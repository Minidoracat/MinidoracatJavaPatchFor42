# TIS 官方論壇回報草稿 — 卡讀條後續（2026-09-07；2026-09-08 校正）

**提交狀態：本檔 R1–R3 全部尚未提交。** 已提交的是 2026-09-02 的既有報告（包含 B-R1／topic 100905），本輪不改那些歷史紀錄，也未對外發文。

證據分級：原版 jar／反編譯的控制流、真類別的隔離重現、正式服觀測分開寫。舊 W10-E 把取消封包解析出的 `playerId` 當成發送者，這個前提已被真 wire 否證；舊草稿的「98% 已完成後取消」「少數帳號發起」「保護成功率」及逐玩家歸因全部撤回。`duration=-1` 也不代表無害。

## 0. 投稿安排

| 內容 | 建議位置 | 狀態與邊界 |
|---|---|---|
| R1：`loadComponent` 失敗發生在 Lua 建構子之前，整個 request 無回覆 | 回覆既有 topic 100905 | 草稿；只證明解析失敗與拒絕出口，不聲稱搬移物件已被安全救回 |
| R2：取消 wire 沒有正確 owner，server 又以全域 byte id 移除 | 新 Bug Reports 主題 | 草稿；以 wire／queue 重現為主，不使用舊錯誤身分觀測統計 |
| R3：IsoObject entity ID 依清單順位、地板固定 0 | 獨立 entity 系統主題 | 草稿；程式碼機制與觀測相符，不宣稱每起 CraftBench miss 或 ECS stale membership 都由它造成 |

---

## R1. 回覆 topic 100905：`loadComponent` 在建構子之前中斷 request

### 中文摘要

正式服曾記錄 `PZNetKahluaTableImpl.loadComponent` 對不存在的 entity 解參考，NPE 穿過 `NetTimedAction.parse`，使 `processServer` 根本沒有執行。這是既有「不回 Accept／Reject」問題的另一個入口，不是 Lua 建構子內的例外。

本輪保留 D1：在該 request 的解析範圍內處理失敗、清掉半成品參數、不呼叫建構子、走正確 Reject。**D2 座標救回已撤除**：附近唯一同型 component 不足以證明就是原物件；共用 decoder 也會影響 `StatePacket` 等非動作路徑。

### 建議板塊

回覆既有主題：https://theindiestone.com/forums/topic/100905-42204-mp-timed-actions-can-stall-permanently-at-100-and-block-the-whole-action-queue-when-a-packet-argument-deserializes-to-null-the-server-sends-neither-accept-nor-reject/

### Title

`[42.20.4] [MP] Follow-up to topic 100905 — component deserialization can abort the request before the Lua constructor`

### Body

```text
Follow-up to the existing report: another entry point into the same missing Accept/Reject response occurs before the Lua constructor is called.

Observed server failure
-----------------------
Our dedicated server has recorded this exception while parsing NetTimedAction packets:

     java.lang.NullPointerException: Cannot invoke "zombie.entity.GameEntity.getComponent(zombie.entity.ComponentType)" because "gameEntity" is null
         at zombie.network.PZNetKahluaTableImpl.loadComponent(...)
         at zombie.network.PZNetKahluaTableImpl.load(...)
         at zombie.core.NetTimedAction.parse(...)

Code path in the 42.20.4 jar
--------------------------
- loadComponent reads an entity net ID and a component type, then dereferences GameEntityManager.GetEntity(netID) without checking whether the entity exists. loadResource has the same unguarded entity lookup shape.
- NetTimedAction.parse calls actionArgs.load before LuaCaller.protectedCall. An exception here never reaches the constructor-failure handling.
- PacketType.onServerPacket does not call processServer when parseServer throws. The outer network loop logs the exception, but no Accept or Reject is sent for that request.
- This reaches the unanswered-request condition described in topic 100905. It does not prove that every reported stuck action has this cause.

Local reproduction and mitigation boundary
-----------------------------------------
An isolated harness using the real jar classes serialized a missing component reference into a request. The original shared table decoder throws; a request-scoped guard can clear partial arguments, skip the Lua constructor, and serialize a Reject carrying the same action/player identifiers. A subsequent valid request on the reused packet still parses normally.

We are keeping the shared table decoder unchanged. We are not substituting another nearby object when an entity lookup fails: even a unique nearby component is not proof of the original object's identity. This mitigation makes a failed request explicit; it does not repair craft-bench identity or prove that the client has received the reply.

Suggested fix
-------------
- Give unresolved component/resource references an explicit failure result or typed exception.
- Handle that failure at the timed-action request boundary, without passing incomplete arguments to the Lua constructor.
- Send the existing Reject response with the correct state and identifiers.
- Investigate why the referenced entity is missing separately; do not guess a replacement object from coordinates alone.
```

---

## R2. 新帖：取消封包身分缺口＋全域 byte id 移除

### 中文摘要

`GeneralActionPacket.setReject` 只寫 action id／state，不寫 `PlayerID`；真 wire 的 player id 是預設 `0`、index 是 `-1`。server 因此解析成目前 onlineID 0 的玩家或 null，**不是發送者**。`processServer` 直接 `stop(this)`，沒有 `getAction/copyFrom` 補正。

原版 `ActionManager.remove` 又只以 byte id 掃整份 server queue。真正修正必須從已認證連線取得 owner，不能只比封包中的 `playerId`；server 內部停止則可使用確定的 queue 實例。另須防止 typed cancel 的前後副作用繞過範圍守衛。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add

### Title

`[42.20.4] [MP] GeneralAction cancellation sends default player identity, while ActionManager removes server actions globally by byte id`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated server]
Mods: [Production reports came from a modded server; the wire and queue behaviour below were reproduced with the shipped Java classes in an isolated harness]
Save: [Not save-specific]

Summary
-------
Action ids are allocated independently by each client JVM, but server-side ActionManager.remove matches only the byte id across the whole queue. A cancellation from one connection can therefore remove another connection's action without sending that other client Done or Reject.

The GeneralAction cancellation packet does not supply a usable sender identity either. Checking its decoded playerId is not a safe fix.

Wire reproduction with the shipped classes
-----------------------------------------
Construct GeneralActionPacket, call setReject((byte)66), then write it with ByteBufferWriter. The payload is:

     42 00 00 00 ff
     action id = 66; state = Reject (ordinal 0); player onlineID = 0; playerIndex = -1

GeneralActionPacket.setReject writes only id and state. The remaining fields retain their defaults. On the server, PlayerID.parsePlayer uses the global player map when playerIndex is -1, so the decoded player is whoever currently has onlineID 0, or null if no such player is connected. Neither result identifies the connection that sent the packet.

GeneralActionPacket.processServer calls ActionManager.stop(this) directly. It does not call getAction or copyFrom to obtain the sender's queued action.

Queue reproduction
------------------
Put accepted actions belonging to two different connections into the server queue with the same action byte id. Dispatch a GeneralAction cancellation from one connection. In the original server removal path, both actions are selected because the predicate compares only the action id. The server branch stops/removes them without replying to the other client.

Action.set uses a per-client static byte and skips zero: there are 255 reusable values, including negative values in Java's signed representation. These ids are not globally unique across connections, and split-screen players share one client JVM counter.

Additional scope hazards
------------------------
- NetTimedActionPacket's Reject branch calls getAction/copyFrom before stop. A foreign player header can change another queued action's state to Reject before an owner-filtered remove runs; ActionManager.update then deletes it on the next tick.
- Request headers also need verification: PlayerID can resolve playerIndex to the sender's local connection player while retaining a different wire onlineID. Existing getAction and stopPlayerActions lookups use that numeric ID.
- FishingActionPacket can process bobber-update flags after its Reject/stop branch. Its getLuaTable uses ActionManager.getPlayer(actionId), another global id-only lookup. Keeping another owner's action in the queue must not turn that owner into the cancellation's Lua-event player.

Suggested fix
-------------
- Derive network cancellation ownership from the authenticated UdpConnection and its actual players, not GeneralAction's default fields.
- Verify request player identity and numeric onlineID before existing-action lookups or mutations.
- Scope cancellation before typed-packet pre/post side effects; preserve other owners' state as well as their queue entries.
- For server-internal stops, remove the actual queued Action instance rather than every action with the same byte id.
- Keep cleanup complete for every removed action, even if one stop callback throws.

Evidence limits
---------------
Earlier owner-attribution telemetry used the decoded GeneralAction player and is invalid. We are not using those sender counts, alleged post-completion percentages, or player-specific attributions in this report. The wire and isolated queue reproductions above establish the defect; they do not quantify its production frequency or prove that all stuck action reports share this cause.
```

---

## R3. 新帖：IsoObject entity ID 依座標與清單順位推導

### 中文摘要

`getEntityNetID` 的非地板 index 來自 `getObjectIndex()`，地板強制 0，並於讀取時更新 map。這是可驗證的身分不穩定／碰撞機制；現有 CraftBench miss 與 ID collision 診斷符合此風險，但不能把個別事件或 stale ECS membership 的完整根因都直接定為此式。

### 建議板塊

**Bug Reports** — https://theindiestone.com/forums/forum/85-bug-reports/?do=add

### Title

`[42.20.4] [MP] IsoObject entity net IDs depend on square-list position, with floors forced to index zero`

### Body

```text
Version: [42.20.4]
Mode: [Multiplayer]
Server settings: [Dedicated server]
Mods: [Production observations came from a modded server; the ID derivation below is vanilla Java]
Save: [Not save-specific]

Code-level identity risk
-----------------------
IsoObject.getEntityNetID derives the identifier from square coordinates and an object-list index:

     index = isFloor() ? 0 : getObjectIndex()
     netID = x + (y << 16) + (z << 32) + (index << 40)

The method updates GameEntityManager's map when its cached ID/index needs recomputing. Removing and reinserting an object can change its list position and therefore its ID. Client and server compute their own values from their own object lists; this is not an independently assigned object identity.

Two distinct objects at the same coordinates can also receive the same composite ID if one is a floor forced to index 0 and the other occupies objects[0]. Whether that arrangement is the cause of a particular collision still requires the corresponding square state.

Related observations, not a complete causal reconstruction
--------------------------------------------------------
Our server has seen unresolved component references in NetTimedAction.parse, and idToEntityMap diagnostics naming a floor where a wall-frame entity was expected. Those observations are consistent with unstable or colliding identity, but we have not captured both sides' object lists for each failure. We are not claiming this explains every unresolved reference or the separate stale ECS-membership problem.

Suggested investigation
-----------------------
- Check client/server identity agreement through pickup, placement, chunk reload and object-list changes.
- Check forced floor index 0 against other objects occupying that index, and verify map invalidation when an object is removed or replaced.
- Consider an identity independent of list position, with explicit lifecycle rules on both peers.
- Reject unresolved action references explicitly rather than choosing a nearby substitute object.
```
