package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * W60 動物遺失／複製觀測行為驗證（獨立 JVM；{@code ENABLED} 是 static final）。
 * 用法：{@code AnimalLossProbeTest on|off}（off 需 {@code -Dmdc.animalLossProbe=off}，測試自驗）。
 *
 * <p>放下動物：照 SmokeCheck 鎖住的 bytecode 順序呼叫 dropCopy → dropLookup → dropRegister，用真
 * {@link AnimalInstanceManager}。鎖：原方法各恰一次、參數與回傳原樣；手上的動物就是前持有者（從地上撿起再放下）
 * 不記；前持有者是別隻（雞舍載入的動物沒有登記 onlineID，查到持有同一個 ID 的動物）記 drop-evict，帶被移走那隻
 * 的種類、ID、位置；手上的動物放下後仍在世界上記 drop-dup；描述時例外只計 anomalies；原方法例外原樣上拋。
 *
 * <p>chunk：另一條執行緒正在存某個 chunk 時，本執行緒載入同一個 chunk 記 reload-race；不同 chunk、同一條執行緒先存後載
 * （LoaderThread 的 saveNow）都不記；兩條執行緒同時存同一個 chunk 記 double-write。真 {@code IsoChunk.Save}
 * 在 noSave 下跑完後不留登記。
 *
 * <p>長大：checkForChickenpocalypse 回 true 時記 grow-dup，帶 ±4 格內同 ID 的另一隻（真 IsoCell／IsoGridSquare，
 * ServerMap 替身回傳格子）；回 false 不記。off：原方法照常呼叫，不記帳、整個 JVM 零 log 行。
 */
public final class AnimalLossProbeTest {
    private static final String TAG = "[MinidoracatJavaPatch][AnimalLossProbe]";

    private static int failed;
    private static ByteArrayOutputStream logBuf;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        boolean on = "on".equals(mode);
        zombie.core.random.RandStandard.INSTANCE.init();
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);

        expect("自驗：ENABLED 與 argv 相符（" + mode + "）", AnimalLossProbe.ENABLED == on);
        expect("parseEnabled：null/1/on 開，0/off/OFF 關", AnimalLossProbe.parseEnabled(null)
                && AnimalLossProbe.parseEnabled("1") && AnimalLossProbe.parseEnabled("on")
                && !AnimalLossProbe.parseEnabled("0") && !AnimalLossProbe.parseEnabled(" off")
                && !AnimalLossProbe.parseEnabled("OFF"));

        AnimalInstanceManager aim = AnimalInstanceManager.getInstance();

        // ---- 原版負對照：未改道的放下流程直接呼叫原方法，不經本類 ----
        TestAnimal vSrc = animal("hen", 11, 1f, 2f);
        aim.add(vSrc, (short) 501);
        TestAnimal vDst = animal("hen", 0, 1f, 2f);
        vDst.copyFrom(vSrc);
        IsoAnimal vPrev = aim.get((short) 501);
        aim.add(vDst, (short) 501);
        expect("原版負對照：直接呼叫 copyFrom/get/add，本類 drops=0、零 log 行",
                vDst.copyCalls == 1 && vPrev == vSrc && AnimalLossProbe.drops.get() == 0L && rows().isEmpty());

        // ---- 放下：手上的動物就是前持有者（地上撿起再放下） ----
        TestAnimal src = animal("cockerel", 4321, 10f, 20f);
        aim.add(src, (short) 502);
        TestAnimal dst = animal("cockerel", 0, 10f, 20f);
        IsoAnimal prev = drop(aim, dst, src, (short) 502);
        expect("正常放下：copyFrom 恰一次且參數是手上的動物、get 回前持有者、add 登記新動物",
                dst.copyCalls == 1 && dst.copyArg == src && prev == src && aim.get((short) 502) == dst);

        // ---- 放下：雞舍載入的動物，onlineID 停在 1，被另一隻持有 ----
        TestAnimal bystander = animal("hen", 1357, 1200f, 3400f);
        aim.add(bystander, (short) 1);
        TestAnimal hutched = animal("cockerel", 4321, 0f, 0f);
        hutched.setOnlineID((short) 1);
        TestAnimal dst2 = animal("cockerel", 0, 5600f, 7800f);
        IsoAnimal prev2 = drop(aim, dst2, hutched, (short) 1);
        expect("移走別隻：get 原樣回傳那隻、add 照常登記", prev2 == bystander && aim.get((short) 1) == dst2);

        // ---- 放下：手上的動物放下後仍在世界上 ----
        TestAnimal ghost = animal("hen", 2468, 30f, 40f);
        ghost.inWorld = true;
        TestAnimal dst3 = animal("hen", 0, 31f, 41f);
        IsoAnimal prev3 = drop(aim, dst3, ghost, (short) 503);
        expect("來源仍在世界上：get 回 null、add 照常登記", prev3 == null && aim.get((short) 503) == dst3);

        // ---- 描述時例外：只計 anomalies，原方法照常 ----
        TestAnimal brokenOther = animal("hen", 1, 0f, 0f);
        brokenOther.throwOnType = true;
        aim.add(brokenOther, (short) 504);
        TestAnimal src4 = animal("hen", 2, 0f, 0f);
        TestAnimal dst4 = animal("hen", 0, 0f, 0f);
        IsoAnimal prev4 = drop(aim, dst4, src4, (short) 504);
        expect("描述前持有者時例外：get 照樣回傳、add 照常登記", prev4 == brokenOther && aim.get((short) 504) == dst4);

        // ---- 原方法例外原樣上拋 ----
        TestAnimal throwingDst = animal("hen", 0, 0f, 0f);
        throwingDst.copyThrows = new IllegalStateException("copy");
        Throwable copyEx = null;
        try {
            AnimalLossProbe.dropCopy(throwingDst, src4);
        } catch (IllegalStateException e) {
            copyEx = e;
        }
        expect("copyFrom 的例外原樣上拋（同一個物件）", copyEx == throwingDst.copyThrows);
        AnimalLossProbe.dropRegister(aim, throwingDst, (short) 505);   // 收掉上一個放下的暫存

        // ---- 長大 ----
        TestServerMap map = alloc(TestServerMap.class);
        ServerMap.instance = map;
        IsoWorld world = alloc(IsoWorld.class);
        world.currentCell = alloc(IsoCell.class);
        IsoWorld.instance = world;
        IsoGridSquare home = square(map, 100, 100, 0);
        TestAnimal chick = animal("chick", 9753, 100f, 100f);
        chick.sq = home;
        TestAnimal twin = animal("chick", 9753, 102f, 97f);
        square(map, 102, 97, 0).getMovingObjects().add(twin);
        TestAnimal stranger = animal("chick", 1234, 99f, 99f);
        square(map, 99, 99, 0).getMovingObjects().add(stranger);
        TestAnimal grown = animal("hen", 9753, 100f, 100f);
        chick.chickenResult = true;
        boolean deleted = AnimalLossProbe.growDuplicate(chick, grown);
        expect("長大撞到同 ID：回傳原值 true、原方法恰一次且參數是新動物",
                deleted && chick.chickenCalls == 1 && chick.chickenArg == grown);
        TestAnimal calf = animal("calf", 77, 50f, 50f);
        calf.sq = square(map, 50, 50, 0);
        boolean kept = AnimalLossProbe.growDuplicate(calf, animal("cow", 77, 50f, 50f));
        expect("長大沒撞到：回傳原值 false", !kept && calf.chickenCalls == 1);

        // ---- chunk 卸載存檔與重載：改道後的 saveUnloaded 跑真 IsoChunk.Save（noSave 下直接返回） ----
        Core.getInstance().setNoSave(true);
        IsoChunk chunk = alloc(IsoChunk.class);
        chunk.wx = 7;
        chunk.wy = 8;
        AnimalLossProbe.saveUnloaded(chunk, false);
        expect("真 IsoChunk.Save（noSave）經改道跑完：不留登記", AnimalLossProbe.savingForTest(7, 8) == null);

        if (on) {
            runChunkSeams();
            runOnAsserts();
        } else {
            expect("off：不記帳", AnimalLossProbe.drops.get() == 0L && AnimalLossProbe.growDups.get() == 0L
                    && AnimalLossProbe.unloadedSaves.get() == 0L && AnimalLossProbe.anomalies.get() == 0L);
            expect("off：整個 JVM 零 log 行", rows().isEmpty());
        }

        for (String row : rows()) {
            System.out.println("log  " + row.substring(row.indexOf(TAG)));
        }
        if (failed > 0) {
            System.out.println("animal-loss-probe FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("animal-loss-probe OK mode=" + mode);
    }

    /** 記帳本身（wrapper 只在 on 時呼叫）：另一條執行緒持有存檔登記時載入同一個 chunk。 */
    private static void runChunkSeams() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread saver = new Thread(() -> {
            AnimalLossProbe.Saving s = AnimalLossProbe.beginSave(100, 200);
            saving.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            AnimalLossProbe.endSave(s);
        }, "SaveChunk");
        saver.start();
        saving.await();
        boolean recheckRace = AnimalLossProbe.beforeLoad(100, 200);
        boolean recheckOther = AnimalLossProbe.beforeLoad(100, 201);
        AnimalLossProbe.Saving own = AnimalLossProbe.beginSave(300, 400);
        boolean recheckOwn = AnimalLossProbe.beforeLoad(300, 400);
        AnimalLossProbe.endSave(own);
        AnimalLossProbe.Saving dup = AnimalLossProbe.beginSave(100, 200);
        AnimalLossProbe.endSave(dup);
        release.countDown();
        saver.join();
        boolean recheckAfter = AnimalLossProbe.beforeLoad(100, 200);
        expect("載入時另一條執行緒正在存同一個 chunk：不必再查（已記 race）；不同 chunk、自己先存後載、存完之後都要再查",
                !recheckRace && recheckOther && recheckOwn && recheckAfter);
        expect("存完之後不留登記", AnimalLossProbe.savingForTest(100, 200) == null
                && AnimalLossProbe.savingForTest(300, 400) == null);
    }

    private static void runOnAsserts() {
        expect("on：首次生效行恰一行", rowsContaining("首次生效").size() == 1);
        List<String> evicts = rowsContaining("drop-evict#");
        expect("on：drop-evict 兩行：第一行帶放下位置、放下的動物、onlineId=1、被移走那隻的種類 ID 位置；描述失敗那次寫 evicted=?",
                evicts.size() == 2 && evicts.get(0).contains("at=5600,7800,0 dropped=cockerel#4321 onlineId=1")
                        && evicts.get(0).contains("evicted=hen#1357 pos=1200,3400,0 inWorld=0 hutch=0 vehicle=0 dead=0")
                        && evicts.get(1).contains("dropped=hen#2 onlineId=504 evicted=?"));
        List<String> dups = rowsContaining("drop-dup#");
        expect("on：drop-dup 恰一行，帶仍在世界上的來源與 prev=none",
                dups.size() == 1 && dups.get(0).contains("dropped=hen#2468 onlineId=503")
                        && dups.get(0).contains("source=hen#2468 pos=30,40,0 inWorld=1") && dups.get(0).contains("prev=none"));
        List<String> grows = rowsContaining("grow-dup#");
        expect("on：grow-dup 恰一行，帶長大前的動物、新種類與同 ID 的另一隻",
                grows.size() == 1 && grows.get(0).contains("animal=chick#9753 pos=100,100,0")
                        && grows.get(0).contains("newType=hen") && grows.get(0).contains("twin=chick#9753 pos=102,97,0"));
        List<String> races = rowsContaining("reload-race#");
        expect("on：reload-race 恰一行，帶 chunk、phase=before 與存檔執行緒名",
                races.size() == 1 && races.get(0).contains("chunk=100,200 phase=before savingThread=SaveChunk"));
        List<String> doubles = rowsContaining("double-write#");
        expect("on：double-write 恰一行，帶兩條執行緒名",
                doubles.size() == 1 && doubles.get(0).contains("chunk=100,200 threads=SaveChunk,main"));
        expect("on：計數 drops=5 evicted=2 dropDups=1 growDups=1 reloadRaces=1 doubleWrites=1 unloadedSaves=4 anomalies=1",
                AnimalLossProbe.drops.get() == 5L && AnimalLossProbe.evicted.get() == 2L
                        && AnimalLossProbe.dropDups.get() == 1L && AnimalLossProbe.growDups.get() == 1L
                        && AnimalLossProbe.reloadRaces.get() == 1L && AnimalLossProbe.doubleWrites.get() == 1L
                        && AnimalLossProbe.unloadedSaves.get() == 4L && AnimalLossProbe.anomalies.get() == 1L);
    }

    /** 照手術後 AddWorldInventoryItem 的順序：copyFrom → get →（原版移走前持有者）→ add。 */
    private static IsoAnimal drop(AnimalInstanceManager aim, TestAnimal dst, IsoAnimal src, short id) {
        AnimalLossProbe.dropCopy(dst, src);
        IsoAnimal prev = AnimalLossProbe.dropLookup(aim, id);
        AnimalLossProbe.dropRegister(aim, dst, id);
        return prev;
    }

    /** 替身：覆寫放下與長大會讀的方法；copyFrom／checkForChickenpocalypse 記次數與參數（原版需要 Lua 與世界）。 */
    public static class TestAnimal extends IsoAnimal {
        String type;
        float px, py;
        boolean inWorld;
        boolean throwOnType;
        int copyCalls;
        IsoAnimal copyArg;
        RuntimeException copyThrows;
        boolean chickenResult;
        int chickenCalls;
        IsoAnimal chickenArg;

        IsoGridSquare sq;

        public TestAnimal() {
            super((IsoCell) null);
        }

        @Override
        public IsoGridSquare getCurrentSquare() {
            return sq;
        }

        @Override
        public String getAnimalType() {
            if (throwOnType) {
                throw new IllegalStateException("type");
            }
            return type;
        }

        @Override
        public float getX() {
            return px;
        }

        @Override
        public float getY() {
            return py;
        }

        @Override
        public float getZ() {
            return 0f;
        }

        @Override
        public boolean isExistInTheWorld() {
            return inWorld;
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public boolean isOnHook() {
            return false;
        }

        @Override
        public void copyFrom(IsoAnimal animal) {
            copyCalls++;
            copyArg = animal;
            if (copyThrows != null) {
                throw copyThrows;
            }
        }

        @Override
        public boolean checkForChickenpocalypse(IsoAnimal replacingAnimal) {
            chickenCalls++;
            chickenArg = replacingAnimal;
            return chickenResult;
        }
    }

    /** ServerMap 替身：IsoCell.getGridSquare 在伺服器上轉給 ServerMap.instance。 */
    public static class TestServerMap extends ServerMap {
        static final java.util.Map<Long, IsoGridSquare> SQUARES = new java.util.HashMap<>();

        @Override
        public IsoGridSquare getGridSquare(int x, int y, int z) {
            return SQUARES.get(((long) x << 32) | (y & 0xffffffffL));
        }
    }

    private static IsoGridSquare square(TestServerMap map, int x, int y, int z) throws Exception {
        long k = ((long) x << 32) | (y & 0xffffffffL);
        IsoGridSquare sq = TestServerMap.SQUARES.get(k);
        if (sq == null) {
            sq = alloc(IsoGridSquare.class);
            sq.x = x;
            sq.y = y;
            sq.z = z;
            Field moving = IsoGridSquare.class.getDeclaredField("movingObjects");
            moving.setAccessible(true);
            moving.set(sq, new ArrayList<IsoMovingObject>());
            TestServerMap.SQUARES.put(k, sq);
        }
        return sq;
    }

    private static TestAnimal animal(String type, int id, float x, float y) throws Exception {
        TestAnimal a = alloc(TestAnimal.class);
        a.type = type;
        a.animalId = id;
        a.px = x;
        a.py = y;
        return a;
    }

    @SuppressWarnings({"unchecked", "deprecation", "removal"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static List<String> rows() {
        List<String> out = new ArrayList<>();
        for (String line : logBuf.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> rowsContaining(String token) {
        List<String> out = new ArrayList<>();
        for (String row : rows()) {
            if (row.contains(token)) {
                out.add(row);
            }
        }
        return out;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalLossProbeTest() {
    }
}
