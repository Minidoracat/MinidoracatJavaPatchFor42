package zombie.mdc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import zombie.characters.IsoGameCharacter;
import zombie.core.random.RandStandard;
import zombie.iso.BuildingDef;
import zombie.iso.BuildingID;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.RoomID;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid;

/**
 * W68 metagrid 房間表鎖的行為驗證（裸 JVM）。
 *
 * <p>世界是真的：IsoMetaGrid 一個 cell、IsoMetaCell 的 rooms／isoRooms／isoBuildings、getRoomByID 建出的 IsoRoom 與
 * IsoBuilding，以及改道後的 IsoChunk.getRoom、IsoBuilding.CreateFrom、DataRoot.clientProcessBuildings；只有 IsoWorld、IsoCell、
 * IsoMetaGrid、DataRoot 以 Unsafe 配置後補欄位。「重建」：DataRoot.changedCells 換成 {@link RebuildHook}，原版
 * clientProcessBuildings 第一行的 isEmpty() 先跑測試動作再回 true——動作就在改道後拿著鎖的那一段裡執行，原版隨即結束。
 *
 * <p>參數 {@code on}：出貨組態；{@code off}：以 {@code -Dmdc.metaGridLock=off} 啟動，必須與原版相同（重建與查房間不互斥）。
 * 原版不互斥時真的會出事，由拋棄式重現（docs/patches.md 2cd「守門與驗證」）示範，這裡不跑。
 */
public final class MetaGridLockBehaviorTest {

    static final int ROOMS = 600;
    static final int REBUILDS = 5000;

    static IsoCell cell;
    static IsoMetaCell metaCell;
    static long[] ids;
    static BuildingDef[] buildings;

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        RandStandard.INSTANCE.init();
        int failed = 0;
        world();
        IsoChunk chunk = new IsoChunk(cell);
        DataRoot dataRoot = TreeRoomGuardBehaviorTest.alloc(DataRoot.class);
        RebuildHook changedCells = new RebuildHook();
        TreeRoomGuardBehaviorTest.set(DataRoot.class, dataRoot, "changedCells", changedCells);

        failed += check(off ? "kill switch off：不上鎖" : "出貨組態：上鎖", MetaGridLock.ENABLED == !off);

        // 1. 重建進行中，另一個執行緒經 IsoChunk.getRoom 查房間：看它是排進鎖的佇列，還是直接查完。
        AtomicReference<IsoRoom> found = new AtomicReference<>();
        Thread lookup = new Thread(() -> found.set(chunk.getRoom(ids[0])), "World Streamer");
        String[] lookupDuringRebuild = new String[1];
        changedCells.action = () -> {
            lookup.start();
            lookupDuringRebuild[0] = queuedOrDone(lookup, () -> !lookup.isAlive());
        };
        dataRoot.clientProcessBuildings();
        boolean lookupOk = joined(lookup, 5000) && found.get() != null && found.get().def == metaCell.rooms.get(ids[0]);
        if (off) {
            failed += check("原版負對照：重建進行中，另一個執行緒照樣查房間（兩邊同時碰房間表）",
                    "done".equals(lookupDuringRebuild[0]) && lookupOk && MetaGridLock.contended == 0);
        } else {
            failed += check("重建進行中，另一個執行緒的 getRoom 排進鎖的佇列，重建做完才查到同一間房，記一次等待",
                    "queued".equals(lookupDuringRebuild[0]) && lookupOk && MetaGridLock.contended == 1);
        }

        // 2. 另一個執行緒正在 getRoomByID 裡建新房間（停在 IsoCell.roomList.add），這時開始重建。
        BlockingRoomList roomList = new BlockingRoomList();
        TreeRoomGuardBehaviorTest.set(IsoCell.class, cell, "roomList", roomList);
        Thread creating = new Thread(() -> chunk.getRoom(ids[1]), "World Streamer");
        creating.start();
        boolean entered = roomList.entered.await(5, TimeUnit.SECONDS);
        CountDownLatch rebuildRan = new CountDownLatch(1);
        changedCells.action = rebuildRan::countDown;
        Thread rebuild = new Thread(dataRoot::clientProcessBuildings, "main");
        rebuild.start();
        String rebuildWhileCreating = queuedOrDone(rebuild, () -> rebuildRan.getCount() == 0);
        boolean stillCreating = creating.isAlive();
        roomList.release.countDown();
        boolean bothDone = joined(creating, 5000) && joined(rebuild, 5000) && rebuildRan.getCount() == 0
                && metaCell.isoRooms.get(ids[1]) != null && metaCell.isoRooms.get(ids[1]).building != null;
        if (off) {
            failed += check("原版負對照：另一個執行緒還在建房間時，重建照樣開始",
                    entered && "done".equals(rebuildWhileCreating) && stillCreating && bothDone);
        } else {
            failed += check("另一個執行緒在 getRoomByID 裡建房間時，重建排進鎖的佇列等它做完（IsoBuilding.CreateFrom 的重入照常）",
                    entered && "queued".equals(rebuildWhileCreating) && stillCreating && bothDone);
        }

        // 3. 出貨組態：真的 recalculateBuildingAndRoomIDs（重建）對上真的 getRoomByID（streamer）一直跑，零例外、表沒壞。
        if (!off) {
            failed += stress(chunk, dataRoot, changedCells);
        }

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("MetaGridLock 行為驗證全數通過（" + (off ? "off" : "on") + "）");
    }

    static int stress(IsoChunk chunk, DataRoot dataRoot, RebuildHook changedCells) throws Exception {
        TreeRoomGuardBehaviorTest.set(IsoCell.class, cell, "roomList", new ArrayList<IsoRoom>());
        Method recalc = WorldRegionToMetaGrid.class.getDeclaredMethod("recalculateBuildingAndRoomIDs", IsoMetaCell.class);
        recalc.setAccessible(true);
        WorldRegionToMetaGrid rebuilder = TreeRoomGuardBehaviorTest.alloc(WorldRegionToMetaGrid.class);
        AtomicReference<Throwable> streamerError = new AtomicReference<>();
        AtomicReference<Throwable> rebuildError = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong lookups = new java.util.concurrent.atomic.AtomicLong();
        Thread streamer = new Thread(() -> {
            Random rnd = new Random(1);
            while (!stop.get()) {
                try {
                    chunk.getRoom(ids[rnd.nextInt(ROOMS)]);
                } catch (Throwable t) {
                    streamerError.compareAndSet(null, t);
                }
                if ((lookups.incrementAndGet() & 0x3FFF) == 0) {
                    cell.getRoomList().clear();
                }
            }
        }, "World Streamer");
        Random rnd = new Random(2);
        // 原版 removeFromMetaGrid 的順序：removeRoomDef／removeBuildingFromMetaCell 拿掉幾間，再 recalculateBuildingAndRoomIDs。
        changedCells.action = () -> {
            for (int k = 0; k < 4; k++) {
                int i = rnd.nextInt(ROOMS);
                metaCell.isoRooms.remove(ids[i]);
                metaCell.isoBuildings.remove(buildings[i].id);
            }
            try {
                recalc.invoke(rebuilder, metaCell);
            } catch (InvocationTargetException e) {
                rebuildError.compareAndSet(null, e.getCause());
            } catch (IllegalAccessException e) {
                rebuildError.compareAndSet(null, e);
            }
        };
        streamer.start();
        long startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (lookups.get() < 1000 && System.nanoTime() < startDeadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        long lookupsBefore = lookups.get();
        // 至少重建 REBUILDS 次，而且期間 streamer 至少查到 100 次（兩邊真的交錯過）；10 秒只是防真的卡死。
        long watchdog = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int rebuilds = 0;
        while ((rebuilds < REBUILDS || lookups.get() - lookupsBefore < 100) && System.nanoTime() < watchdog) {
            dataRoot.clientProcessBuildings();
            rebuilds++;
        }
        long lookupsDuring = lookups.get() - lookupsBefore;
        stop.set(true);
        boolean joinedStreamer = joined(streamer, 5000);
        if (streamerError.get() != null) {
            streamerError.get().printStackTrace(System.out);
        }
        if (rebuildError.get() != null) {
            rebuildError.get().printStackTrace(System.out);
        }
        return check("重建 " + rebuilds + " 次、期間 streamer 查房間 " + lookupsDuring
                        + " 次：兩邊零例外，房間表走得到的筆數等於記錄的大小、沒有 null",
                joinedStreamer && lookupsBefore >= 1000 && rebuilds >= REBUILDS && lookupsDuring >= 100
                && streamerError.get() == null && rebuildError.get() == null
                && intact(metaCell.isoRooms) && intact(metaCell.isoBuildings));
    }

    /** HashMap 走得到的值都不是 null，筆數等於記錄的 size（並行寫壞時兩者會對不上）。 */
    static boolean intact(java.util.Map<Long, ?> map) {
        int reachable = 0;
        for (Object value : map.values()) {
            if (value == null) {
                return false;
            }
            reachable++;
        }
        return reachable == map.size();
    }

    /** 一個 cell、ROOMS 間自建房，各自一棟建築；房間都還沒有 IsoRoom（第一次查才建）。 */
    static void world() throws Exception {
        cell = TreeRoomGuardBehaviorTest.alloc(IsoCell.class);
        TreeRoomGuardBehaviorTest.set(IsoCell.class, cell, "roomLights", new ArrayList<>());
        TreeRoomGuardBehaviorTest.set(IsoCell.class, cell, "roomList", new ArrayList<>());
        IsoWorld world = TreeRoomGuardBehaviorTest.alloc(IsoWorld.class);
        world.currentCell = cell;
        IsoMetaGrid grid = TreeRoomGuardBehaviorTest.alloc(IsoMetaGrid.class);
        metaCell = new IsoMetaCell(0, 0);
        TreeRoomGuardBehaviorTest.set(IsoMetaGrid.class, grid, "grid", new IsoMetaCell[][] {{metaCell}});
        TreeRoomGuardBehaviorTest.set(IsoMetaGrid.class, grid, "width", 1);
        TreeRoomGuardBehaviorTest.set(IsoMetaGrid.class, grid, "height", 1);
        TreeRoomGuardBehaviorTest.set(IsoWorld.class, world, "metaGrid", grid);
        IsoWorld.instance = world;

        ids = new long[ROOMS];
        buildings = new BuildingDef[ROOMS];
        for (int i = 0; i < ROOMS; i++) {
            RoomDef def = new RoomDef(RoomID.makeID(0, 0, i), "");
            def.userDefined = true;
            def.rects.add(new RoomDef.RoomRect(i % 256, i / 256, 1, 1));
            BuildingDef building = new BuildingDef();
            building.id = BuildingID.makeID(0, 0, i);
            building.rooms.add(def);
            def.building = building;
            metaCell.rooms.put(def.id, def);
            metaCell.roomList.add(def);
            metaCell.buildings.add(building);
            ids[i] = def.id;
            buildings[i] = building;
        }
    }

    /** DataRoot.changedCells 的替身：原版 clientProcessBuildings 第一行的 isEmpty() 先跑 action，再回 true 讓原版結束。 */
    static final class RebuildHook extends ArrayList<IsoGameCharacter.Location> {
        Runnable action = () -> {};

        @Override
        public boolean isEmpty() {
            action.run();
            return true;
        }
    }

    /** IsoCell.roomList 的替身：getRoomByID 建新房間時的 add 停住，直到測試放行。 */
    static final class BlockingRoomList extends ArrayList<IsoRoom> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean add(IsoRoom room) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.add(room);
        }
    }

    /** 最多等 5 秒：done 成立回 "done"，thread 排進 MetaGridLock 的佇列回 "queued"，都沒有回 "neither"。 */
    static String queuedOrDone(Thread thread, java.util.function.BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) {
                return "done";
            }
            if (MetaGridLock.LOCK.hasQueuedThread(thread)) {
                return "queued";
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        return "neither";
    }

    static boolean joined(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private MetaGridLockBehaviorTest() {}
}
