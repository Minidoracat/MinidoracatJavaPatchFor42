package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import gnu.trove.set.hash.TIntHashSet;

import zombie.characters.animals.AnimalCell;
import zombie.characters.animals.AnimalChunk;
import zombie.characters.animals.AnimalManagerWorker;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.MdcAnimalPersistProbe;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/** W16 行為測試：observe／enforce-alias／off 各跑獨立 JVM，MODE 自驗防 property 假綠。 */
public final class AnimalPersistGuardTest {

    public static void main(String[] args) throws Exception {
        String arg = args.length == 0 ? "observe" : args[0];
        int wantMode = switch (arg) {
            case "off" -> AnimalPersistGuard.MODE_OFF;
            case "enforce" -> AnimalPersistGuard.MODE_ENFORCE;
            default -> AnimalPersistGuard.MODE_OBSERVE;
        };
        if (AnimalPersistGuard.MODE != wantMode) {
            throw new AssertionError("mode got=" + AnimalPersistGuard.MODE + " want=" + wantMode);
        }
        boolean active = wantMode != AnimalPersistGuard.MODE_OFF;
        Counts before = new Counts();

        TestAnimal normalAnimal = alloc(TestAnimal.class);
        TestAnimal serverOff = alloc(TestAnimal.class);
        TestMoving nonAnimal = alloc(TestMoving.class);
        IsoChunk chunk = alloc(IsoChunk.class);
        chunk.wx = 10;
        chunk.wy = 20;

        // A. 正常完整 wave：seen=queued=cleared=1，零 S2／queue gap。
        AnimalPersistGuard.unloadEnter(null);
        AnimalPersistGuard.scanStarted();
        AnimalPersistGuard.scanAnimal(normalAnimal);
        AnimalPersistGuard.recordHandedOff();
        finishScan(1);
        boolean oldServer = GameServer.server;
        try {
            GameServer.server = true;
            AnimalPersistGuard.clearMoving(normalAnimal);
            AnimalPersistGuard.clearMoving(nonAnimal);   // mutation 刪 instanceof 會誤計
            GameServer.server = false;
            AnimalPersistGuard.clearMoving(serverOff);
        } finally {
            GameServer.server = oldServer;
        }
        AnimalPersistGuard.chunkUnloadExit(chunk);
        if (normalAnimal.unloadedCalls != 1 || normalAnimal.removeCalls != 1
                || nonAnimal.removeCalls != 1 || serverOff.removeCalls != 1) {
            throw new AssertionError("scan/clear 委派次數不符");
        }

        // B0. 完整 wave：scan/queue 看見 1、clear 看見 0 → clearShortfall +1。
        // 緊接 B 的反向正差，兩者必須各自保留，不能退回全域淨額抵銷。
        TestAnimal shortfall = alloc(TestAnimal.class);
        AnimalPersistGuard.unloadEnter(null);
        AnimalPersistGuard.scanStarted();
        AnimalPersistGuard.scanAnimal(shortfall);
        AnimalPersistGuard.recordHandedOff();
        finishScan(20);
        AnimalPersistGuard.chunkUnloadExit(chunk);

        // B. 完整 wave：scan 看不到、clear 看見 1 → s2Missed +1（正差不與別 wave 抵銷）。
        TestAnimal missed = alloc(TestAnimal.class);
        AnimalPersistGuard.unloadEnter(null);
        AnimalPersistGuard.scanStarted();
        finishScan(2);
        try {
            GameServer.server = true;
            AnimalPersistGuard.clearMoving(missed);
        } finally {
            GameServer.server = oldServer;
        }
        AnimalPersistGuard.chunkUnloadExit(chunk);

        // C. 完整 wave：seen=clear=1、n_add 未成功返回 → queueFailures +1、S2 不增。
        TestAnimal queueFailed = alloc(TestAnimal.class);
        AnimalPersistGuard.unloadEnter(null);
        AnimalPersistGuard.scanStarted();
        AnimalPersistGuard.scanAnimal(queueFailed);
        finishScan(3);
        try {
            GameServer.server = true;
            AnimalPersistGuard.clearMoving(queueFailed);
        } finally {
            GameServer.server = oldServer;
        }
        AnimalPersistGuard.chunkUnloadExit(chunk);

        // D. 上一 wave 沒到 IsoChunk tail；下次 enter 必須記 unpaired，且新 wave 不承接舊帳。
        AnimalPersistGuard.unloadEnter(null);
        AnimalPersistGuard.unloadEnter(null);
        AnimalPersistGuard.scanStarted();
        finishScan(4);
        AnimalPersistGuard.chunkUnloadExit(chunk);

        // E. APM 掃描未正常到尾，但 IsoChunk catch 後仍走 tail → abortedWaves +1。
        AnimalPersistGuard.unloadEnter(null);
        AnimalPersistGuard.chunkUnloadExit(chunk);

        // F. 完整來源帳：四個 Worker.addAnimal 來源各 1，attempts 同為 4 → sourceGap 0。
        AnimalPersistGuard.recordVirtualized();
        AnimalPersistGuard.recordZoneAdd();
        AnimalPersistGuard.recordMovedAdd();

        // G. 真 worker/cell：cell 正負、chunk 正負、save 正負都驗 passthrough。
        AnimalManagerWorker worker = new AnimalManagerWorker();
        AnimalCell loadedCell = alloc(AnimalCell.class);
        set(loadedCell, "loaded", true);
        set(loadedCell, "x", 0);
        set(loadedCell, "y", 0);
        set(loadedCell, "chunks", new AnimalChunk[1024]);
        set(worker, "minX", 0);
        set(worker, "minY", 0);
        set(worker, "width", 1);
        set(worker, "height", 1);
        set(worker, "cells", new AnimalCell[]{loadedCell});
        for (int i = 0; i < 4; i++) {
            if (MdcAnimalPersistProbe.cellForAdd(worker, 0, 0) != loadedCell) {
                throw new AssertionError("cellForAdd 正向未原樣回傳");
            }
        }
        if (MdcAnimalPersistProbe.cellForAdd(worker, -1, 0) != null) {
            throw new AssertionError("cellForAdd 越界應 null");
        }
        AnimalChunk created = MdcAnimalPersistProbe.chunkForAdd(loadedCell, 0, 0);
        if (created == null) {
            throw new AssertionError("chunkForAdd 正向未建立/回傳 chunk");
        }
        AnimalCell unloadedCell = alloc(AnimalCell.class);
        if (MdcAnimalPersistProbe.chunkForAdd(unloadedCell, 0, 0) != null) {
            throw new AssertionError("unloaded cell 的 chunkForAdd 應 null");
        }
        if (MdcAnimalPersistProbe.cellForSave(worker, 0, 0) != loadedCell
                || MdcAnimalPersistProbe.cellForSave(worker, -1, 0) != null) {
            throw new AssertionError("cellForSave 正／負向 passthrough 不符");
        }

        // H. S3 redirect 原樣 remove 並計數。
        ArrayList<Object> duplicateList = new ArrayList<>();
        Object removedValue = new Object();
        duplicateList.add(removedValue);
        if (AnimalPersistGuard.removeDuplicate(duplicateList, 0) != removedValue
                || !duplicateList.isEmpty()) {
            throw new AssertionError("removeDuplicate 未原樣委派 ArrayList.remove");
        }

        // I. O4 分母。
        IsoCell isoCell = alloc(IsoCell.class);
        Set<IsoMovingObject> objects = new HashSet<>();
        objects.add(normalAnimal);
        objects.add(nonAnimal);
        set(isoCell, "objectList", objects);
        if (AnimalPersistGuard.saveScan(isoCell) != objects) {
            throw new AssertionError("saveScan 未原樣回傳 objectList");
        }

        Counts after = new Counts();
        long on = active ? 1L : 0L;
        assertDelta("unloads", before.unloads, after.unloads, 7L * on);
        assertDelta("scanSeen", before.scanSeen, after.scanSeen, 3L * on);
        assertDelta("handedOff", before.handedOff, after.handedOff, 2L * on);
        assertDelta("virtualized", before.virtualized, after.virtualized, on);
        assertDelta("zoneAdds", before.zoneAdds, after.zoneAdds, on);
        assertDelta("movedAdds", before.movedAdds, after.movedAdds, on);
        assertDelta("attempts", before.attempts, after.attempts, 5L * on);
        assertDelta("cellNullAtAdd", before.cellNullAdd, after.cellNullAdd, on);
        assertDelta("chunkNullAtAdd", before.chunkNullAdd, after.chunkNullAdd, on);
        assertDelta("cellNullAtSave", before.cellNullSave, after.cellNullSave, on);
        assertDelta("duplicateRemoved", before.duplicateRemoved, after.duplicateRemoved, on);
        assertDelta("droppedAtClear", before.droppedAtClear, after.droppedAtClear, 3L * on);
        assertDelta("completedWaves", before.completed, after.completed, 5L * on);
        assertDelta("abortedWaves", before.aborted, after.aborted, on);
        assertDelta("unpairedWaves", before.unpaired, after.unpaired, on);
        assertDelta("s2Missed", before.s2Missed, after.s2Missed, on);
        assertDelta("clearShortfall", before.clearShortfall, after.clearShortfall, on);
        assertDelta("queueFailures", before.queueFailures, after.queueFailures, on);
        assertDelta("saveWaves", before.saveWaves, after.saveWaves, on);
        assertDelta("scanCount", before.scanCount, after.scanCount, 5L * on);
        assertDelta("anomalies", before.anomalies, after.anomalies, 0L);
        if (active && after.lastSaveReal != 1L) {
            throw new AssertionError("lastSaveReal=" + after.lastSaveReal);
        }
        long sourceGap = (after.attempts - before.attempts)
                - (after.handedOff - before.handedOff)
                - (after.virtualized - before.virtualized)
                - (after.zoneAdds - before.zoneAdds)
                - (after.movedAdds - before.movedAdds);
        if (sourceGap != 0L) {
            throw new AssertionError("sourceGap=" + sourceGap);
        }
        System.out.println("AnimalPersistGuardTest OK mode=" + AnimalPersistGuard.MODE);
    }

    private static void finishScan(int key) {
        TIntHashSet set = new TIntHashSet();
        set.add(key);
        if (!AnimalPersistGuard.unloadScanExit(set, key) || set.contains(key)) {
            throw new AssertionError("unloadScanExit 未原樣委派 remove");
        }
    }

    private static void assertDelta(String what, long before, long after, long want) {
        long got = after - before;
        if (got != want) {
            throw new AssertionError(what + " delta=" + got + " want=" + want
                    + " mode=" + AnimalPersistGuard.MODE);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    static final class TestAnimal extends IsoAnimal {
        int unloadedCalls;
        int removeCalls;

        private TestAnimal() { super(null); }

        @Override public void unloaded() { unloadedCalls++; }
        @Override public void removeFromWorld() { removeCalls++; }
    }

    static final class TestMoving extends IsoMovingObject {
        int removeCalls;
        @Override public void removeFromWorld() { removeCalls++; }
    }

    static final class Counts {
        final long unloads = AnimalPersistGuard.unloadsForTest();
        final long scanSeen = AnimalPersistGuard.scanSeenForTest();
        final long handedOff = AnimalPersistGuard.handedOffForTest();
        final long virtualized = AnimalPersistGuard.virtualizedForTest();
        final long zoneAdds = AnimalPersistGuard.zoneAddsForTest();
        final long movedAdds = AnimalPersistGuard.movedAddsForTest();
        final long attempts = AnimalPersistGuard.attemptsForTest();
        final long cellNullAdd = AnimalPersistGuard.cellNullAtAddForTest();
        final long chunkNullAdd = AnimalPersistGuard.chunkNullAtAddForTest();
        final long cellNullSave = AnimalPersistGuard.cellNullAtSaveForTest();
        final long duplicateRemoved = AnimalPersistGuard.duplicateRemovedForTest();
        final long droppedAtClear = AnimalPersistGuard.droppedAtClearForTest();
        final long completed = AnimalPersistGuard.completedWavesForTest();
        final long aborted = AnimalPersistGuard.abortedWavesForTest();
        final long unpaired = AnimalPersistGuard.unpairedWavesForTest();
        final long s2Missed = AnimalPersistGuard.s2MissedForTest();
        final long clearShortfall = AnimalPersistGuard.clearShortfallForTest();
        final long queueFailures = AnimalPersistGuard.queueFailuresForTest();
        final long saveWaves = AnimalPersistGuard.saveWavesForTest();
        final long lastSaveReal = AnimalPersistGuard.lastSaveRealForTest();
        final long scanCount = AnimalPersistGuard.scanCountForTest();
        final long anomalies = AnimalPersistGuard.anomaliesForTest();
    }
}
