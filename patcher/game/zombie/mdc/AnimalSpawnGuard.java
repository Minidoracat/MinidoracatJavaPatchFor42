package zombie.mdc;

import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * W37 動物半建構物件守衛（2026-09-26；docs/patches.md 2az）。
 *
 * <p><b>事故</b>（9/16 22:00、9/24 18:03、9/26 10:04 三次同型）：{@code IsoAnimal} 建構子的
 * {@code super()}（{@code IsoGameCharacter}）在座標非 0 時先把物件放進 cell 的 objectList／addList，
 * 之後才跑 {@code checkForChickenpocalypse()}／{@code checkForWater()}；任一為真就跳過 {@code init()}，
 * 留下 adef／data 皆 null 的物件。water 分支不呼叫 delete()，物件照樣進世界；
 * {@code IsoAnimal.update} 每 tick 在 {@code this.adef.turnDelta} NPE，打斷整個
 * {@code IngameState.updateInternal}（時間凍結、OnTick 全停）；它被虛擬化後 apop 存檔也 NPE。
 * 呼叫端（{@code AnimalData.grow}、{@code IsoAnimal.addBaby}）緊接著 {@code getData()} NPE，
 * 且 grow 在 parent.delete() 之前拋出，小雞留在世界每幀重試、每次再漏一個。
 *
 * <p><b>手術</b>：(1) 四個帶座標建構子每個 RETURN 前 {@link #afterCtor}：data 為 null 且物件是
 * 以座標建構時，做 {@code IsoGameCharacter} 建構子加入動作的逆操作：{@code addMovingObject}
 * （同一個 isSafeToAdd 分支撤 objectList／addList）與 {@code setMovingSquareNow}（撤出格子的
 * movingObjects；42.21 建構子新增這一步）。只撤 cell 時，water／noInit 物件仍留在格子上，
 * {@code getAnimals} 會回傳它——建構子的 chickenpocalypse 檢查早於 init、新物件 animalId 恆為 -1，
 * 4 格內之後每次建構都與它的 -1 相等而失敗；chunk 卸載時 {@code unloaded()} 對它 NPE。
 * chickenpocalypse 分支原版 {@code delete()} 已含 removeFromSquare，不重做。
 * (2) {@code checkStages} 的 grow 與 W33 的 addBaby 委派包在 {@link #grow}／{@link #addBaby}：
 * 呼叫期間有建構失敗且拋 NPE 時吞掉這次（小雞維持原狀，下次 update 由原版重試），
 * 其餘例外原樣穿透。成功路徑行為不變。
 * (3) 放下動物（2026-10-20 正式服 OOM）：{@code IsoGridSquare.AddWorldInventoryItem} 在放下的格子建新動物，
 * 水格上建構失敗、(1) 撤出之後，原版照樣 copyFrom、登記 AnimalInstanceManager、setSquare／setCurrent，
 * {@code setMovingSquareNow} 再把它放回格子，並送 DropAnimal 給附近客戶端。cell 卸載時 {@code removeFromWorld}
 * 在 releaseRagdollController 讀 {@code AnimalDefinitions.getDef(null)} NPE，整個 cell 卸不掉，
 * {@code ServerMap.postupdate} 的迴圈每 tick 在這裡中斷，排在後面的 cell 不再卸載也不再更新，heap 約 1 小時用盡。
 * {@link #dropPlace} 把 adef 為 null 的動物移出世界、{@link #dropSend} 不送；{@link #chunkRemove} 是其他路徑
 * 放回格子時的保底：只對 adef 為 null 的動物吞 removeFromWorld 的例外。物品照原版被收走，那隻動物不保留。
 * (4) 放下的格子是水格時改放旁邊的陸地格（2026-10-21 正式服：岸邊格看起來是草地、人站得上去，地板卻是水，
 * 放下的雞都被 (3) 丟掉）：new IsoAnimal 的兩個座標參數改讀 {@link #dropX}／{@link #dropY}，建構子直接在
 * {@link #landFor} 選的陸地格建好；原版接著 setSquare／setCurrent 又指回水格，{@link #dropPlace} 換回陸地格、
 * {@link #dropSend} 送陸地格（客戶端照封包裡的格子建動物）。找不到陸地格照 (3) 丟棄。
 * kill switch {@code -Dmdc.animalSpawnGuard=0}（(1)–(4) 一起）。
 */
public final class AnimalSpawnGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalSpawnGuard"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalSpawnGuard] ";
    private static final long DETAIL_LIMIT = 64L;

    // 帶座標的動物建構、grow／addBaby、放下動物與 chunk 卸載都在主執行緒。
    private static long ctorFailures, removed, squareRemoved, swallowed, dropDiscarded, dropMoved, unsent, unloadBroken, anomalies;

    // landFor 檢查的鄰格：先上下左右，再斜角。
    private static final int[][] NEIGHBOURS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

    /** IsoAnimal 帶座標建構子每個 RETURN 前。 */
    public static void afterCtor(IsoAnimal animal) {
        if (!ENABLED || animal.getData() != null) {
            return;
        }
        // IsoGameCharacter 建構子只在 (x,y,z) 非 0 時加入 cell；(IsoCell) 載入用建構子走 0,0,0。
        float x = animal.getX(), y = animal.getY(), z = animal.getZ();
        if ((int) x == 0 && (int) y == 0 && (int) z == 0) {
            return;
        }
        ctorFailures++;
        IsoCell cell = null;
        String reason = "unknown";
        boolean hit = false, onSquare = false;
        try {
            cell = animal.getCell();
            // 先定 reason：撤出格子後 current 會清成 null。
            IsoGridSquare sq = animal.getCurrentSquare();
            reason = sq != null && sq.isWaterSquare() ? "water"
                    : cell != null && cell.getRemoveList().contains(animal) ? "chickenpocalypse"
                    : "noInit";
            onSquare = sq != null && sq.getMovingObjects().contains(animal);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        try {
            if (cell != null) {
                if (cell.isSafeToAdd()) {
                    hit = cell.getObjectList().remove(animal);
                }
                hit |= cell.getAddList().remove(animal);
            }
            if (hit) {
                removed++;
            }
            if (!"chickenpocalypse".equals(reason)) {
                animal.removeFromSquare();
                if (onSquare) {
                    squareRemoved++;
                }
            }
            if (ctorFailures <= DETAIL_LIMIT || ctorFailures % 1000 == 0) {
                DebugLog.log(TAG + "ctor failed reason=" + reason + " pos=" + (int) x + "," + (int) y + "," + (int) z
                        + " removedFromWorld=" + hit + " removedFromSquare=" + onSquare + " failures=" + ctorFailures
                        + " removed=" + removed + " squareRemoved=" + squareRemoved + " swallowed=" + swallowed
                        + "（原版會留下 adef/data 為 null 的動物，每 tick 打斷世界更新）");
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** {@code AnimalData.checkStages} 內唯一 grow 呼叫。 */
    public static void grow(AnimalData data, String newType) {
        long before = ctorFailures;
        try {
            data.grow(newType);
        } catch (NullPointerException e) {
            if (!swallow(before, "grow", data.parent, newType)) {
                throw e;
            }
        }
    }

    /** W33 BabyBreedGuard 委派原版 addBaby 的出口。 */
    public static IsoAnimal addBaby(IsoAnimal mother) {
        long before = ctorFailures;
        try {
            return mother.addBaby();
        } catch (NullPointerException e) {
            if (!swallow(before, "addBaby", mother, null)) {
                throw e;
            }
            return null;
        }
    }

    private static boolean swallow(long before, String site, IsoAnimal parent, String newType) {
        if (!ENABLED || ctorFailures == before) {
            return false;
        }
        swallowed++;
        if (swallowed <= DETAIL_LIMIT || swallowed % 1000 == 0) {
            try {
                DebugLog.log(TAG + "skip " + site + " parent=" + parent.getAnimalType() + "#" + parent.getAnimalID()
                        + (newType == null ? "" : " newType=" + newType)
                        + " pos=" + (int) parent.getX() + "," + (int) parent.getY() + "," + (int) parent.getZ()
                        + " swallowed=" + swallowed + "（新動物建構失敗，原版會在此 NPE 打斷該 tick；下次 update 重試）");
            } catch (RuntimeException | LinkageError e) {
                anomalies++;
            }
        }
        return true;
    }

    /** {@code IsoGridSquare.AddWorldInventoryItem} 動物分支 new IsoAnimal 的 x 參數（原版讀放下的格子的 x）。 */
    public static int dropX(IsoGridSquare sq) {
        return landFor(sq).getX();
    }

    /** 同上的 y 參數。 */
    public static int dropY(IsoGridSquare sq) {
        return landFor(sq).getY();
    }

    /**
     * 放下動物的格子：不是水格就是原格（照原版）。是水格（{@code isWaterSquare}，建構子的 water 檢查會失敗）時，回傳
     * 鄰格中第一個能放動物的陸地格：已載入、不是水格、{@code isFree(false)}（有實心地板，沒有牆、樹之類的實心物）、
     * 和放下的格子之間沒有牆、圍欄、門窗擋著（{@code testCollideAdjacent}，角色移動用的同一份碰撞）；找不到就回原格。
     * 同一次放下裡 dropX、dropY、dropPlace 各呼叫一次，世界沒有變動，三次結果相同。
     * ponytail: 只看 8 個鄰格；四周都是水或都被擋住時照舊丟棄，要更遠再擴大範圍。
     */
    static IsoGridSquare landFor(IsoGridSquare sq) {
        if (!ENABLED || sq == null) {
            return sq;
        }
        try {
            if (!sq.isWaterSquare()) {
                return sq;
            }
            IsoCell cell = sq.getCell();
            for (int[] d : NEIGHBOURS) {
                IsoGridSquare n = cell.getGridSquare(sq.getX() + d[0], sq.getY() + d[1], sq.getZ());
                if (n != null && !n.isWaterSquare() && n.isFree(false) && !sq.testCollideAdjacent(null, d[0], d[1], 0)) {
                    return n;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        return sq;
    }

    /** {@code IsoGridSquare.AddWorldInventoryItem} 動物分支唯一 {@code setMovingSquareNow}（放下的格子）。 */
    public static void dropPlace(IsoAnimal animal) {
        if (!ENABLED || animal.adef != null) {
            if (ENABLED) {
                moveToLand(animal);
            }
            animal.setMovingSquareNow();
            return;
        }
        dropDiscarded++;
        removeBroken(animal);
        try {
            animal.removeFromSquare();
            animal.setSquare(null);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        if (dropDiscarded <= DETAIL_LIMIT || dropDiscarded % 1000 == 0) {
            log("drop discarded " + describe(animal) + " dropDiscarded=" + dropDiscarded
                    + "（放下的格子建不出動物，原因見前一行 ctor failed；水格旁有能放的陸地格時已先改到那一格建。"
                    + "原版會把牠放回格子，cell 卸載時每 tick NPE、整個 cell 卸不掉。物品已被收走，這隻動物沒有保留，"
                    + "也不送 DropAnimal）");
        }
    }

    /** 建構子已在 landFor 選的陸地格建好；原版 setSquare／setCurrent 指回放下的水格，換回陸地格。 */
    private static void moveToLand(IsoAnimal animal) {
        IsoGridSquare drop = animal.getCurrentSquare();
        IsoGridSquare land = landFor(drop);
        if (land == drop) {
            return;
        }
        try {
            animal.setSquare(land);
            animal.setCurrent(land);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            return;
        }
        dropMoved++;
        if (dropMoved <= DETAIL_LIMIT || dropMoved % 1000 == 0) {
            log("drop moved from=" + drop.getX() + "," + drop.getY() + "," + drop.getZ() + " " + describe(animal)
                    + " dropMoved=" + dropMoved + "（放下的格子是水格，原版建不出動物；改放旁邊的陸地格）");
        }
    }

    /**
     * 同一分支唯一 {@code INetworkPacket.sendToRelative}（DropAnimal；args[1] 是新建的動物、args[2] 是放下的格子，
     * 客戶端在這一格建動物）。
     */
    public static void dropSend(PacketTypes.PacketType type, float x, float y, Object[] args) {
        if (ENABLED && args.length > 1 && args[1] instanceof IsoAnimal animal) {
            if (animal.adef == null) {
                unsent++;   // 伺服器已丟棄這隻；送出去，認得這隻動物的客戶端會照原版在同一個水格再建一次
                return;
            }
            IsoGridSquare at = animal.getCurrentSquare();
            if (args.length > 2 && args[2] instanceof IsoGridSquare && at != null && at != args[2]) {
                args[2] = at;   // dropPlace 換到陸地格了：客戶端也在陸地格建
                x = at.getX();
                y = at.getY();
            }
        }
        INetworkPacket.sendToRelative(type, x, y, args);
    }

    /** {@code IsoChunk.removeFromWorld} 兩個 {@code IsoMovingObject.removeFromWorld}（格子的 movingObjects、staticMovingObjects）。 */
    public static void chunkRemove(IsoMovingObject obj) {
        if (!ENABLED || !(obj instanceof IsoAnimal animal) || animal.adef != null) {
            obj.removeFromWorld();
            return;
        }
        unloadBroken++;
        boolean threw = removeBroken(animal);
        if (unloadBroken <= DETAIL_LIMIT || unloadBroken % 1000 == 0) {
            log("unload broken " + describe(animal) + " threw=" + threw + " unloadBroken=" + unloadBroken
                    + "（格子上有沒建構完成的動物；原版 removeFromWorld 的例外會中斷整個 cell 卸載）");
        }
    }

    /**
     * 沒建構完成的動物移出世界。原版 removeFromWorld 的 cell 名單、更新排程、entity 等段落在 IsoGameCharacter 的
     * releaseRagdollController 之前做完，之後讀 {@code AnimalDefinitions.getDef(null)} NPE；吞掉後補做 IsoAnimal 後段的
     * objectList 移除，並在 AnimalInstanceManager 仍指著牠時註銷。後段的 AnimalManagerWorker 交接不補，半成品不進 apop。
     */
    private static boolean removeBroken(IsoAnimal animal) {
        boolean threw = false;
        try {
            animal.removeFromWorld();
        } catch (RuntimeException e) {
            threw = true;
        }
        try {
            if (threw && animal.getCell() != null) {
                animal.getCell().getObjectList().remove(animal);
            }
            AnimalInstanceManager aim = AnimalInstanceManager.getInstance();
            if (aim.get(animal.getOnlineID()) == animal) {
                aim.remove(animal);
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        return threw;
    }

    private static String describe(IsoAnimal animal) {
        String breed = "?";
        try {
            AnimalData data = animal.getData();
            if (data != null && data.getBreed() != null) {
                breed = data.getBreed().getName();
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        return "pos=" + (int) animal.getX() + "," + (int) animal.getY() + "," + (int) animal.getZ()
                + " animalId=" + animal.getAnimalID() + " onlineId=" + animal.getOnlineID()
                + " breed=" + breed + " name=" + animal.getCustomName();
    }

    private static void log(String message) {
        try {
            DebugLog.log(TAG + message);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    static boolean enabledForTest() { return ENABLED; }
    static long ctorFailuresForTest() { return ctorFailures; }
    static long removedForTest() { return removed; }
    static long squareRemovedForTest() { return squareRemoved; }
    static long swallowedForTest() { return swallowed; }
    static long anomaliesForTest() { return anomalies; }
    static long dropDiscardedForTest() { return dropDiscarded; }
    static long dropMovedForTest() { return dropMoved; }
    static long unsentForTest() { return unsent; }
    static long unloadBrokenForTest() { return unloadBroken; }

    private AnimalSpawnGuard() {}
}
