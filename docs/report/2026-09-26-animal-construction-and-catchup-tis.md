# TIS 回報草稿：動物建構失敗留下半成品＋apop 截斷、離線補算重複（未送出）

- 狀態：草稿，尚未回報。本服止血為 W37（docs/patches.md 2az）與 W38（2ba）。
- 待補：W37 上線後 `[AnimalSpawnGuard] ctor failed reason=` 的實際分佈（water／chickenpocalypse）。

```text
[42.20.4][Dedicated Server] Three animal bugs: half-constructed IsoAnimal leaks into the world, AnimalCell.save truncates apop files, and doMeta catches up some animals several times

Version: 42.20.4 (dedicated server, Linux)

1) Half-constructed IsoAnimal stays in the world and breaks every tick
IsoAnimal(cell, x, y, z, type, breed[, skeleton]) calls super() first. IsoGameCharacter's constructor already adds the object to cell objectList/addList when x/y/z != 0. Only afterwards does IsoAnimal run checkForChickenpocalypse()/checkForWater(); if either is true, init() is skipped and the object is left with adef == null and data == null. The water branch does not even call delete().
Callers then fail immediately:
  AnimalData.grow -> newAnimal.getData().setAge(...)   NPE (thrown before parent.delete(), so the chick retries every tick)
  IsoAnimal.addBaby -> baby.getData()                   NPE
and the leaked object throws every tick in IsoAnimal.update:
  NullPointerException: Cannot read field "turnDelta" because "this.adef" is null at IsoAnimal.update
This aborts IngameState.updateInternal after IsoWorld.update each frame (GameTime.update, Lua OnTick, etc. are skipped), so game time froze for ~65 minutes (38,291 NPEs). Seen three times on our server (9/16, 9/24, 9/26).
Also: a newly constructed animal has animalId == -1 until init(), so checkForChickenpocalypse() matches any leaked -1 animal within 4 tiles, which makes further constructions fail too.
Suggested fix: run the checks before adding to the cell, or remove the object from the cell when the checks fail; null-check getData() in grow/addBaby.

2) AnimalCell.save() truncates the apop file before serializing
AnimalCell.save() opens new FileOutputStream(apop_X_Y.bin) (truncating it) and only then calls save(ByteBuffer). Only IOException is caught. When serialization throws (e.g. an animal with data == null from bug 1), the file is left at 0 bytes. On the next load: IllegalArgumentException: newLimit < 0, then spawnAnimalsInCell() replaces the cell's animals with fresh wild ones. All player animals in that cell are lost. The exception also aborts ServerMap.QueuedSaveAll (everything after AnimalPopulationManager.save is skipped) and, on quit, the shutdown hook.
Suggested fix: serialize into the buffer first and open the file only on success (keep the old file on failure).

3) DesignationZoneAnimal.doMeta catches up some animals several times and skips others
doMeta iterates this.animals by index and calls updateStatsAway(hours). updateStatsAway resets zoneCheckTimer and calls checkZone() -> setDZone(), which does removeAnimal + addAnimal even for the same zone, moving the animal to the end of the list. Within one doMeta, some animals are therefore caught up 2-5 times (hunger/age/timeSinceLastUpdate advanced several times, e.g. offline hours 62 -> 0 -> -61) while others are skipped.
On our server, about 133 animals died right after catch-up in ~36 hours; 27 of 42 logged deaths had been caught up more than once in the same frame. AnimalMetaPredator was off.
Suggested fix: iterate over a snapshot of this.animals in doMeta (or avoid remove/add in setDZone when the zone is unchanged).
```
