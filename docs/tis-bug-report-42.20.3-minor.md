# TIS 官方回報草稿 — 42.20.3 兩個 server 端小 bug（NPE＋格式字串）

> 用途：貼到 The Indie Stone 官方論壇 Bug Reports 板（B42）。兩個 bug 獨立、都很小，
> 可以合成一篇發（下方就是合篇格式）或拆兩篇。與 texture 洩漏主報告
> （tis-bug-report.md）分開發——混在一起會稀釋處理效率。
> 數據來源：正式服 42.20.3（30–40 人、100+ mods）上線後首個 11 小時 session 的
> server console/DebugLog 實測；stack trace 全為 vanilla frame（無任何 mod class 在
> 堆疊上——觸發資料可能來自 mod 物品，但 null-safety 與格式字串是 vanilla 程式碼）。
> 撰於 2026-08-18。發文前補上文末 placeholder。

---

**Title:** [42.20.3] [MP] Two small server-side bugs: NPE in `SyncItemFieldsPacket.parse` on stale item ids, and an invalid `%l` format string that destroys the diagnostic it tries to log

Both observed on a dedicated Linux server (30–40 concurrent players, ~100 workshop mods) during the first 11-hour session on 42.20.3. All stack frames below are vanilla classes — no mod code appears in either trace.

## Bug 1: `SyncItemFieldsPacket.parse` NPEs when the item lookup returns null

**Frequency:** 201 occurrences in 11 hours (~18/hour).

```
java.lang.NullPointerException: Cannot invoke "zombie.inventory.InventoryItem.hasSharpness()"
    because "item" is null at SyncItemFieldsPacket.parse(SyncItemFieldsPacket.java:383)
  zombie.network.packets.SyncItemFieldsPacket.parse(SyncItemFieldsPacket.java:383)
  zombie.network.packets.INetworkPacket.parseServer(INetworkPacket.java:55)
  zombie.network.PacketTypes$PacketType.onServerPacket(PacketTypes.java:967)
  zombie.network.GameServer.mainLoopDealWithNetData(GameServer.java:1611)
  zombie.network.GameServer.main(GameServer.java:909)
```

The item lookup inside `parse` returns null and line 383 dereferences it without a null
check. Why the lookup misses is undetermined from our side — a stale id (item removed
between client send and server processing), packet ordering, or a mod-originated id are
all possible; the stack itself is pure vanilla. The exception escapes to the
`GameServer.main` catch, so the remainder of that main-loop iteration's
`mainLoopDealWithNetData` work is skipped — self-recovering, but each occurrence wastes
part of a tick and prints a full stack to the console.

**Suggested fix:** null-check the item lookup in `SyncItemFieldsPacket.parse` and drop
(or log-once) the packet instead of throwing into the main loop.

## Bug 2: C-style `%ld` in `GameEntityManager.checkEntityIDChange` format strings — the error it tries to report is replaced by a formatter crash

**Frequency:** 10 occurrences in the same session, all during bulk chunk unload after a
mass disconnect (~23 players hit simultaneous RakNet connection-lost within the same
second; underlying network cause undetermined — the server main loop was running normally
at ~10 fps throughout. The chunk unload cascade followed the disconnects).

```
java.util.UnknownFormatConversionException: Conversion = 'l'
    at java.util.Formatter$FormatSpecifier.conversion
  ...
  java.base/java.lang.String.format(Unknown Source)
  zombie.debug.DebugLogStream.getFormattedOutputStr(DebugLogStream.java:119)
  ...
  zombie.entity.GameEntityManager.checkEntityIDChange(GameEntityManager.java:400)
  zombie.iso.IsoObject.getEntityNetID(IsoObject.java:6004)
  zombie.entity.GameEntityManager.UnregisterEntity(GameEntityManager.java:289)
  zombie.entity.GameEntity.removeFromWorld(GameEntity.java:555)
  zombie.iso.IsoObject.removeFromWorld(IsoObject.java:4552)
  zombie.iso.objects.IsoThumpable.removeFromWorld(IsoThumpable.java:1911)
  zombie.iso.IsoObject.removeFromWorldToMeta(IsoObject.java:4582)
  zombie.iso.IsoChunk.removeFromWorld(IsoChunk.java:3397)
  zombie.network.ServerMap$ServerCell.Unload(ServerMap.java:418)
  zombie.network.ServerMap.postupdate(ServerMap.java:1004)
  zombie.network.GameServer.main(GameServer.java:1105)
```

`checkEntityIDChange` calls `DebugType.error` with C-style format strings in its two
map-consistency checks — both message templates are of the form
`"idToEntityMap(%ld)=%s, expected …"`, and the second call also supplies one trailing
argument with no matching conversion (silently ignored today).

`java.util.Formatter` rejects `%ld` (it stops at the `l`, hence `Conversion = 'l'` in the
exception). Two consequences:

1. The diagnostic the method wants to emit — an entity net-ID map inconsistency detected
   during unregister, exactly the kind of anomaly you want visibility into — is
   **never printed**; it is replaced by the formatter exception.
2. The exception propagates out of the logging call through the `removeFromWorld` chain
   and kills the rest of that frame's `ServerMap.postupdate`, so remaining cell unloads
   are retried on later frames.

**Suggested fix:** replace `%ld` with `%d` in both format strings (Java's `%d` handles
`long`), and give the trailing argument of the second call its own `%s` while touching
the line. Restores the intended diagnostic and stops the postupdate abort.

— [server name], [contact]
