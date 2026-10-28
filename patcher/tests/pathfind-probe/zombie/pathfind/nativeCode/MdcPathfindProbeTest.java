package zombie.pathfind.nativeCode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import zombie.ai.astar.Mover;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;

/**
 * W74 MdcPathfindProbe 行為驗證（獨立 JVM；argv[0] = on|off，須與 {@code -Dmdc.pathfindProbe} 相符）。
 *
 * <p>走 dist 內已 patch 的 {@code PathfindNativeThread.updateThread}／{@code findPath(PathFindRequest)} 與
 * {@code PathRequestTask.execute}。測試 JVM 沒有載入 libPZPathFind64，兩個 native 入口（{@code PathfindNative.update}、
 * {@code PathfindNative.findPath}）會丟 {@code UnsatisfiedLinkError}——正好拿來驗「原版例外原樣上拋」。
 * 回傳值與參數同一物件用覆寫 {@code findPath} 的替身 {@code PathfindNative} 直接驗 helper。
 *
 * <p>鎖：任務照原順序執行並進 taskReturnQueue、請求進原版同一個優先佇列、helper 回傳原版的值、參數原樣交給原版、
 * 原版例外（同一個物件）上拋、on 的分類與重建分組正確、off 全部零計數；最後量每個請求多花多少 ns。
 */
public final class MdcPathfindProbeTest {

    private static int failed;
    static final List<Object> EXECUTED = new ArrayList<>();

    static final class FakeChunk extends ChunkUpdateTask {
        @Override
        public void execute() {
            EXECUTED.add(this);
        }
    }

    static final class FakeChunkRemove extends ChunkRemoveTask {
        @Override
        public void execute() {
            EXECUTED.add(this);
        }
    }

    static final class FakeSquare extends SquareUpdateTask {
        @Override
        public void execute() {
            EXECUTED.add(this);
        }
    }

    static final class FakeVehicle extends VehicleUpdateTask {
        @Override
        public void execute() {
            EXECUTED.add(this);
        }
    }

    static final class OtherMover implements Mover {
        @Override
        public int getID() {
            return 7;
        }

        @Override
        public int getPathFindIndex() {
            return 0;
        }
    }

    /** 替身 native：依序回傳腳本值或丟出指定例外，並記下收到的參數。 */
    static final class FakeNative extends PathfindNative {
        final ArrayDeque<Object> script = new ArrayDeque<>();
        int calls;
        PathFindRequest lastRequest;
        ByteBuffer lastBb;
        boolean lastRender;
        int constant = Integer.MIN_VALUE;

        @Override
        public int findPath(PathFindRequest request, ByteBuffer pathBB, boolean bRender) {
            calls++;
            lastRequest = request;
            lastBb = pathBB;
            lastRender = bRender;
            if (constant != Integer.MIN_VALUE) {
                return constant;
            }
            Object next = script.removeFirst();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next instanceof Error e) {
                throw e;
            }
            return (Integer) next;
        }
    }

    /** 量測用：addRequest 不進佇列。 */
    static final class NoopThread extends PathfindNativeThread {
        @Override
        public void addRequest(PathFindRequest request, int queueNumber) {
        }
    }

    public static void main(String[] args) throws Exception {
        boolean on = !(args.length > 0 && "off".equals(args[0]));
        expect("開關與 argv 相符（" + (on ? "on" : "off") + "）", MdcPathfindProbe.ENABLED == on);

        PathfindNativeThread thread = new PathfindNativeThread();
        PathfindNativeThread.instance = thread;

        // ---- 真 updateThread：chunk／格子／載具任務照原順序執行，請求任務把請求放進原版佇列，native update 原樣上拋 ----
        FakeChunk c1 = new FakeChunk();
        FakeChunkRemove c2 = new FakeChunkRemove();
        FakeSquare s1 = new FakeSquare();
        FakeSquare s2 = new FakeSquare();
        FakeVehicle v1 = new FakeVehicle();
        PathFindRequest other = request(new OtherMover());
        PathRequestTask otherTask = new PathRequestTask().init(other);
        thread.chunkTaskQueue.add(c1);
        thread.chunkTaskQueue.add(c2);
        thread.squareTaskQueue.add(s1);
        thread.squareTaskQueue.add(s2);
        thread.vehicleTaskQueue.add(v1);
        thread.requestTaskQueue.add(otherTask);
        Throwable updateThrown = invoke(thread, "updateThread", new Class<?>[0]);
        expect("updateThread：native PathfindNative.update 的 UnsatisfiedLinkError 原樣上拋",
                updateThrown instanceof UnsatisfiedLinkError && String.valueOf(updateThrown.getMessage()).contains("update"));
        expect("updateThread：任務照原順序各執行一次", EXECUTED.equals(List.of(c1, c2, s1, s2, v1)));
        expect("updateThread：taskReturnQueue 照原順序收到同一批任務（含請求任務）",
                new ArrayList<>(thread.taskReturnQueue).equals(List.of(c1, c2, s1, s2, v1, otherTask)));
        expect("updateThread：四個任務佇列都取空", thread.chunkTaskQueue.isEmpty() && thread.squareTaskQueue.isEmpty()
                && thread.vehicleTaskQueue.isEmpty() && thread.requestTaskQueue.isEmpty());
        expect("updateThread：請求進原版 otherQ（同一物件）", queue("otherQ").equals(List.of(other)));
        if (on) {
            expect("on：一輪、有世界變動", MdcPathfindProbe.roundsForTest() == 1 && MdcPathfindProbe.worldRoundsForTest() == 1);
            long[] world = MdcPathfindProbe.worldTasksForTest();
            expect("on：chunk 2（更新＋移除）、格子 2、載具 1、請求任務 1、其他 0（實際 "
                    + java.util.Arrays.toString(world) + "）", java.util.Arrays.equals(world, new long[] {2, 2, 1, 1, 0}));
            expect("on：入隊 0/0/1", java.util.Arrays.equals(MdcPathfindProbe.enqForTest(), new long[] {0, 0, 1}));
            expect("on：首次生效已宣告", MdcPathfindProbe.announcedForTest());
        } else {
            expect("off：零輪、零任務、零入隊", MdcPathfindProbe.roundsForTest() == 0
                    && java.util.Arrays.equals(MdcPathfindProbe.worldTasksForTest(), new long[5])
                    && java.util.Arrays.equals(MdcPathfindProbe.enqForTest(), new long[3])
                    && !MdcPathfindProbe.announcedForTest());
        }

        // ---- 真 findPath(PathFindRequest)：native findPath 的 UnsatisfiedLinkError 原樣上拋，不計入 calls ----
        Throwable findThrown = invoke(thread, "findPath", new Class<?>[] {PathFindRequest.class}, other);
        expect("findPath：native PathfindNative.findPath 的 UnsatisfiedLinkError 原樣上拋",
                findThrown instanceof UnsatisfiedLinkError && String.valueOf(findThrown.getMessage()).contains("findPath"));
        expect("findPath：例外不計入 calls", MdcPathfindProbe.callsForTest() == 0);
        expect((on ? "on" : "off") + "：thrown=" + (on ? 1 : 0), MdcPathfindProbe.thrownForTest() == (on ? 1 : 0));

        // ---- 真 PathRequestTask.execute：照 queueNumber 進原版三個佇列 ----
        PathFindRequest player = request((IsoPlayer) raw(IsoPlayer.class));
        PathFindRequest animal = request((IsoAnimal) raw(IsoAnimal.class));
        IsoZombie aggroMover = (IsoZombie) raw(IsoZombie.class);
        aggroMover.target = (IsoPlayer) raw(IsoPlayer.class);
        PathFindRequest aggro = request(aggroMover);
        PathFindRequest zombie = request((IsoZombie) raw(IsoZombie.class));
        for (PathFindRequest r : List.of(player, animal, aggro, zombie)) {
            new PathRequestTask().init(r).execute();
        }
        expect("execute：玩家進 playerQ、有目標殭屍進 aggroZombieQ、動物與無目標殭屍進 otherQ（同一物件、原順序）",
                queue("playerQ").equals(List.of(player)) && queue("aggroZombieQ").equals(List.of(aggro))
                        && queue("otherQ").equals(List.of(other, animal, zombie)));
        if (on) {
            expect("on：入隊 1/1/3", java.util.Arrays.equals(MdcPathfindProbe.enqForTest(), new long[] {1, 1, 3}));
        }

        // ---- helper findPath：回傳原版值、參數同一物件、原版恰一次；分類與重建分組 ----
        MdcPathfindProbe.resetWindowForTest();
        FakeNative fake = new FakeNative();
        ByteBuffer bb = ByteBuffer.allocate(16);
        fake.script.add(1);
        int r1 = MdcPathfindProbe.findPath(fake, player, bb, false);
        expect("findPath 回傳原版值、參數同一物件",
                r1 == 1 && fake.calls == 1 && fake.lastRequest == player && fake.lastBb == bb && !fake.lastRender);
        fake.script.add(1);
        fake.script.add(0);
        int r2 = MdcPathfindProbe.findPath(fake, animal, bb, true);
        int r3 = MdcPathfindProbe.findPath(fake, animal, bb, true);
        expect("同一請求連續兩次（多目標）各回原版值、render 原樣", r2 == 1 && r3 == 0 && fake.calls == 3 && fake.lastRender);
        FakeSquare s3 = new FakeSquare();
        ConcurrentLinkedQueue<IPathfindTask> world = new ConcurrentLinkedQueue<>();
        world.add(s3);
        expect("poll 回傳原版取出的同一物件、佇列取空後回 null",
                MdcPathfindProbe.poll(world) == s3 && MdcPathfindProbe.poll(world) == null && world.isEmpty());
        Throwable directUpdate = null;
        try {
            MdcPathfindProbe.update();
        } catch (Throwable t) {
            directUpdate = t;
        }
        expect("update：native 的 UnsatisfiedLinkError 原樣上拋", directUpdate instanceof UnsatisfiedLinkError);
        fake.script.add(0);
        fake.script.add(1);
        int r4 = MdcPathfindProbe.findPath(fake, aggro, bb, false);
        int r5 = MdcPathfindProbe.findPath(fake, zombie, bb, false);
        expect("後續呼叫各回原版值、原版共 5 次", r4 == 0 && r5 == 1 && fake.calls == 5 && fake.lastRequest == zombie);
        RuntimeException boom = new IllegalStateException("boom");
        AssertionError error = new AssertionError("native");
        fake.script.add(boom);
        fake.script.add(error);
        Throwable t1 = catchFind(fake, player, bb);
        Throwable t2 = catchFind(fake, player, bb);
        expect("原版例外（RuntimeException 與 Error）同一物件原樣上拋", t1 == boom && t2 == error && fake.calls == 7);
        String window = MdcPathfindProbe.windowForTest();
        System.out.println("probe line: " + window);
        if (on) {
            expect("on：calls=5、extra=1、fail=2、thrown 視窗內 2", MdcPathfindProbe.callsForTest() == 5
                    && MdcPathfindProbe.extraForTest() == 1 && MdcPathfindProbe.failsForTest() == 2
                    && MdcPathfindProbe.thrownForTest() == 2);
            expect("on：重建組 2（第一次與世界變動後第一次）、非重建組 3",
                    MdcPathfindProbe.rbForTest() == 2 && MdcPathfindProbe.nrForTest() == 3);
            expect("on：請求者 player/aggroZombie/zombie/animal/other = 1/1/1/1/0，aggroZombie 失敗 1",
                    MdcPathfindProbe.catForTest(MdcPathfindProbe.CAT_PLAYER) == 1
                            && MdcPathfindProbe.catForTest(MdcPathfindProbe.CAT_AGGRO_ZOMBIE) == 1
                            && MdcPathfindProbe.catForTest(MdcPathfindProbe.CAT_ZOMBIE) == 1
                            && MdcPathfindProbe.catForTest(MdcPathfindProbe.CAT_ANIMAL) == 1
                            && MdcPathfindProbe.catForTest(MdcPathfindProbe.CAT_OTHER) == 0
                            && MdcPathfindProbe.catFailForTest(MdcPathfindProbe.CAT_AGGRO_ZOMBIE) == 1
                            && MdcPathfindProbe.catFailForTest(MdcPathfindProbe.CAT_ANIMAL) == 0);
            expect("on：四個請求各量到一次排隊時間；只剩 native 拋例外那筆留在入隊表",
                    MdcPathfindProbe.catWaitsForTest(MdcPathfindProbe.CAT_PLAYER) == 1
                            && MdcPathfindProbe.catWaitsForTest(MdcPathfindProbe.CAT_ANIMAL) == 1
                            && MdcPathfindProbe.catWaitsForTest(MdcPathfindProbe.CAT_AGGRO_ZOMBIE) == 1
                            && MdcPathfindProbe.catWaitsForTest(MdcPathfindProbe.CAT_ZOMBIE) == 1
                            && MdcPathfindProbe.enqueuedForTest() == 1);
            expect("on：一行包含分組與分類欄位", window.contains(" calls=5 ") && window.contains(" extra=1 ")
                    && window.contains(" fail=2 ") && window.contains(" thrown=2 ") && window.contains(" rb=2/")
                    && window.contains(" nr=3/") && window.contains(" square=1 ") && window.contains(" player=1/0/")
                    && window.contains(" aggroZombie=1/1/") && window.contains(" zombie=1/0/")
                    && window.contains(" animal=1/0/") && window.contains(" other=0/0/") && window.contains(" rebuildPct=")
                    && window.contains(" anomalies=0 "));
        } else {
            expect("off：零計數（calls、extra、fail、thrown、分組、分類、入隊表）", MdcPathfindProbe.callsForTest() == 0
                    && MdcPathfindProbe.totalCallsForTest() == 0 && MdcPathfindProbe.extraForTest() == 0
                    && MdcPathfindProbe.failsForTest() == 0 && MdcPathfindProbe.thrownForTest() == 0
                    && MdcPathfindProbe.rbForTest() == 0 && MdcPathfindProbe.nrForTest() == 0
                    && MdcPathfindProbe.catForTest(MdcPathfindProbe.CAT_PLAYER) == 0
                    && MdcPathfindProbe.enqueuedForTest() == 0 && MdcPathfindProbe.roundsForTest() == 0);
        }
        expect("簿記無異常", MdcPathfindProbe.anomaliesForTest() == 0);

        // ---- 直方圖：桶上界單調、p95 不超過最大值 ----
        MdcPathfindProbe.Hist hist = new MdcPathfindProbe.Hist();
        for (int i = 1; i <= 100; i++) {
            hist.add(i * 1_000_000L);
        }
        long p95 = hist.p95Us(100_000_000L);
        expect("直方圖 p95 落在 95–119 ms（四分之一倍頻上界），不超過最大值（實際 " + p95 / 1000 + " ms）",
                p95 >= 95_000L && p95 <= 100_000L);
        boolean monotonic = true;
        for (int b = 1; b < MdcPathfindProbe.Hist.BUCKETS; b++) {
            monotonic &= MdcPathfindProbe.Hist.upperUs(b) >= MdcPathfindProbe.Hist.upperUs(b - 1);
        }
        expect("直方圖桶上界單調不減", monotonic);

        if (on) {
            rebuildBreakdown();
        }
        bench(on);

        if (failed > 0) {
            System.out.println("MdcPathfindProbeTest FAIL " + failed);
            System.exit(1);
        }
        System.out.println("MdcPathfindProbeTest OK (" + (on ? "on" : "off") + ")");
    }

    /**
     * W81 v2 行尾欄位（合成耗時）：錨點 rb 之後 19 個 nr（序位 2–5 失敗 4 ms、6–20 成功 8 ms），rb 3000 ms 成功（前一週期 20 個
     * 呼叫），緊接 rb 500 ms 失敗（1 個），59 個 nr 成功（序位 2–5 1 ms、6–20 2 ms、21–60 3 ms），rb 2000 ms 失敗（60 個）。
     * 會抓到：rbMin 沒隨視窗歸零（錨點 100）、成敗或 rb／nr 交叉、序位與分桶邊界差一（5/6、20/21、20、60）、新欄位沒歸零。
     */
    private static void rebuildBreakdown() {
        long ms = 1_000_000L;
        MdcPathfindProbe.recordForTest(true, 1, 100 * ms);
        MdcPathfindProbe.resetWindowForTest();
        for (int pos = 2; pos <= 20; pos++) {
            MdcPathfindProbe.recordForTest(false, pos <= 5 ? 0 : 1, (pos <= 5 ? 4 : 8) * ms);
        }
        MdcPathfindProbe.recordForTest(true, 1, 3000 * ms);
        MdcPathfindProbe.recordForTest(true, 0, 500 * ms);
        for (int pos = 2; pos <= 60; pos++) {
            MdcPathfindProbe.recordForTest(false, 1, (pos <= 5 ? 1 : pos <= 20 ? 2 : 3) * ms);
        }
        MdcPathfindProbe.recordForTest(true, 0, 2000 * ms);
        String window = MdcPathfindProbe.windowForTest();
        String tail = window.substring(window.indexOf(" rbMin="));
        // nrOk (15×8 + 4×1 + 15×2 + 40×3) / 74 = 3.70；nrPos (16+4)/8、(120+30)/30、120/40
        String want = " rbMin=500 rbOk=1/3000.0 rbFail=2/1250.0 nrOk=74/3.7 nrFail=4/4.0 nrPos=2.5/5.0/3.0"
                + " rbGap=500.0/3000.0/2000.0 rbGapN=1/1/1";
        expect("W81 v2 rb／nr 拆分欄位（實際" + tail + "）", tail.equals(want)
                && window.indexOf(" reload60=") < window.indexOf(" holdMsMax=")
                && window.indexOf(" holdMsMax=") < window.indexOf(" rbMin="));
        MdcPathfindProbe.resetWindowForTest();
        String cleared = MdcPathfindProbe.windowForTest();
        expect("W81 v2 欄位隨視窗歸零", cleared.endsWith(" rbMin=0 rbOk=0/0.0 rbFail=0/0.0 nrOk=0/0.0 nrFail=0/0.0"
                + " nrPos=0.0/0.0/0.0 rbGap=0.0/0.0/0.0 rbGapN=0/0/0"));
    }

    /** 每個請求一次 addRequest＋一次 findPath（native 換成立即回傳的替身），量探針多花的 ns。 */
    private static void bench(boolean on) {
        NoopThread noop = new NoopThread();
        FakeNative fake = new FakeNative();
        fake.constant = 1;
        ByteBuffer bb = ByteBuffer.allocate(16);
        PathFindRequest[] reqs = new PathFindRequest[64];
        for (int i = 0; i < reqs.length; i++) {
            reqs[i] = request(new OtherMover());
        }
        int n = 1_000_000;
        long sink = 0;
        long base = Long.MAX_VALUE;
        long probe = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                PathFindRequest r = reqs[i & 63];
                noop.addRequest(r, 3);
                sink += fake.findPath(r, bb, false);
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                PathFindRequest r = reqs[i & 63];
                MdcPathfindProbe.addRequest(noop, r, 3);
                sink += MdcPathfindProbe.findPath(fake, r, bb, false);
            }
            long t2 = System.nanoTime();
            base = Math.min(base, (t1 - t0) / n);
            probe = Math.min(probe, (t2 - t1) / n);
        }
        long overhead = Math.max(0L, probe - base);
        System.out.println("bench (" + (on ? "on" : "off") + ") per request: direct=" + base + " ns probe=" + probe
                + " ns overhead=" + overhead + " ns sink=" + sink);
        // 上限：原版每個請求至少 25 ms（20 輪/s × 2 請求、執行緒 100%）；20 µs 已是 0.08%，超過代表簿記出了病態成本。
        expect("探針每個請求多花 < 20 µs（實際 " + overhead + " ns）", overhead < 20_000L);
        expect("量測後簿記仍無異常", MdcPathfindProbe.anomaliesForTest() == 0);
    }

    private static Throwable catchFind(FakeNative fake, PathFindRequest request, ByteBuffer bb) {
        try {
            MdcPathfindProbe.findPath(fake, request, bb, false);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static PathFindRequest request(Mover mover) {
        PathFindRequest r = new PathFindRequest();
        r.mover = mover;
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<PathFindRequest> queue(String name) throws Exception {
        Field requests = PathfindNativeThread.class.getDeclaredField("requests");
        requests.setAccessible(true);
        RequestQueue q = (RequestQueue) requests.get(PathfindNativeThread.instance);
        Field f = RequestQueue.class.getDeclaredField(name);
        f.setAccessible(true);
        return new ArrayList<>((ArrayDeque<PathFindRequest>) f.get(q));
    }

    /** 反射呼叫 private 方法；回傳方法內丟出的例外（沒有就 null）。 */
    private static Throwable invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        Method m = PathfindNativeThread.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            m.invoke(target, values);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "pfp pass  " : "pfp FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
