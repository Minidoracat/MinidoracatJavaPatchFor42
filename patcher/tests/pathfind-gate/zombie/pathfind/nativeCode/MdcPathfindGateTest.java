package zombie.pathfind.nativeCode;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

import zombie.ai.astar.Mover;

/**
 * W81 MdcPathfindGate 行為驗證（獨立 JVM；argv[0] = gate-on|gate-off|probe-off，須與 {@code -Dmdc.pathfindGateMs}、
 * {@code -Dmdc.pathfindProbe} 相符）。
 *
 * <p>走 dist 內手術後的真 {@code PathfindNativeThread.updateThread}（native {@code PathfindNative.update} 照 W74 測試的做法：
 * 測試 JVM 沒有 native 庫，丟 {@code UnsatisfiedLinkError} 當作一輪結束；閘門在呼叫 native 之前就結算並決定下一輪）。
 *
 * <p>鎖：開服第一輪開、之後每 T 開一輪；關閉時世界任務留在佇列、請求照常進優先佇列；開閘一輪套完全部積壓（超過 10 個 chunk）
 * 且 FIFO；{@code execute} 丟例外與原版相同；T=0 與原版逐輪相同（含 10 個上限）；W74 計數、rb／nr 分組、心跳欄位、refail。
 * 情境另以測試內的 updateThread 模型（照 javap 抄的原版迴圈）跑三個 mutant，各自至少一個情境與出貨語意不同。
 */
public final class MdcPathfindGateTest {

    private static int failed;
    static final List<String> EXECUTED = new ArrayList<>();
    static final RuntimeException BOOM = new IllegalStateException("boom");

    static final class Chunk extends ChunkUpdateTask {
        final String name;

        Chunk(String name) {
            this.name = name;
        }

        @Override
        public void execute() {
            EXECUTED.add(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class Boom extends ChunkUpdateTask {
        @Override
        public void execute() {
            EXECUTED.add("b");
            throw BOOM;
        }

        @Override
        public String toString() {
            return "b";
        }
    }

    static final class ChunkRemove extends ChunkRemoveTask {
        final String name;

        ChunkRemove(String name) {
            this.name = name;
        }

        @Override
        public void execute() {
            EXECUTED.add(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class Square extends SquareUpdateTask {
        final String name;

        Square(String name) {
            this.name = name;
        }

        @Override
        public void execute() {
            EXECUTED.add(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class Vehicle extends VehicleUpdateTask {
        final String name;

        Vehicle(String name) {
            this.name = name;
        }

        @Override
        public void execute() {
            EXECUTED.add(name);
        }

        @Override
        public String toString() {
            return name;
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

    static final class FakeNative extends PathfindNative {
        int result = 1;

        @Override
        public int findPath(PathFindRequest request, ByteBuffer pathBB, boolean bRender) {
            return result;
        }
    }

    interface Runner {
        Throwable run(PathfindNativeThread thread) throws Exception;
    }

    /** dist 內手術後的真 updateThread；native update 的 UnsatisfiedLinkError 代表這一輪正常走完。 */
    static final Runner REAL = thread -> {
        Method m = PathfindNativeThread.class.getDeclaredMethod("updateThread");
        m.setAccessible(true);
        try {
            m.invoke(thread);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof UnsatisfiedLinkError ? null : e.getCause();
        }
    };

    /** 原版 updateThread 的四個任務迴圈（javap off 0–189），poll 換成指定函式；mutant 與原版對照用。 */
    static Runner model(Function<ConcurrentLinkedQueue<?>, Object> poll) {
        return t -> {
            try {
                int chunkUpdates = 10;
                for (IPathfindTask task = (IPathfindTask) poll.apply(t.chunkTaskQueue); task != null;
                        task = (IPathfindTask) poll.apply(t.chunkTaskQueue)) {
                    task.execute();
                    t.taskReturnQueue.add(task);
                    if (task instanceof ChunkUpdateTask && --chunkUpdates <= 0) {
                        break;
                    }
                }
                for (SquareUpdateTask task = (SquareUpdateTask) poll.apply(t.squareTaskQueue); task != null;
                        task = (SquareUpdateTask) poll.apply(t.squareTaskQueue)) {
                    task.execute();
                    t.taskReturnQueue.add(task);
                }
                for (IPathfindTask task = (IPathfindTask) poll.apply(t.vehicleTaskQueue); task != null;
                        task = (IPathfindTask) poll.apply(t.vehicleTaskQueue)) {
                    task.execute();
                    t.taskReturnQueue.add(task);
                }
                for (PathRequestTask task = (PathRequestTask) poll.apply(t.requestTaskQueue); task != null;
                        task = (PathRequestTask) poll.apply(t.requestTaskQueue)) {
                    task.execute();
                    t.taskReturnQueue.add(task);
                }
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    static final String A_ON = "exec=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,c11,c12,c13,c14,c15,s1,v1]"
            + " ret=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,c11,c12,c13,c14,c15,s1,v1,req]"
            + " chunk=[] sq=[] veh=[] req=[] otherQ=1 thrown=-";
    static final String A_OFF = "exec=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,s1,v1]"
            + " ret=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,s1,v1,req]"
            + " chunk=[c11,c12,c13,c14,c15] sq=[] veh=[] req=[] otherQ=1 thrown=-";
    static final String B_ON = "exec=[] ret=[req] chunk=[c1] sq=[s1] veh=[v1] req=[] otherQ=1 thrown=-";
    static final String B_OFF = "exec=[c1,s1,v1] ret=[c1,s1,v1,req] chunk=[] sq=[] veh=[] req=[] otherQ=1 thrown=-";
    static final String C_ALL = "exec=[a,b] ret=[a] chunk=[c] sq=[s1] veh=[] req=[req] otherQ=0 thrown=boom";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "gate-on";
        boolean gate = !"gate-off".equals(mode);
        boolean probe = !"probe-off".equals(mode);
        expect("開關與 argv 相符（" + mode + "：gate=" + MdcPathfindGate.GATE_MS + " probe=" + MdcPathfindProbe.ENABLED + "）",
                MdcPathfindGate.GATE_MS == (gate ? 1000L : 0L) && MdcPathfindProbe.ENABLED == probe);
        expect("開關解析：未設 1000、0／off 關、0–5000 照用、範圍外與亂碼落回 1000",
                MdcPathfindGate.parseMs(null) == 1000L && MdcPathfindGate.parseMs("0") == 0L
                        && MdcPathfindGate.parseMs(" OFF ") == 0L && MdcPathfindGate.parseMs("5000") == 5000L
                        && MdcPathfindGate.parseMs("250") == 250L && MdcPathfindGate.parseMs("5001") == 1000L
                        && MdcPathfindGate.parseMs("-1") == 1000L && MdcPathfindGate.parseMs("abc") == 1000L);

        mainSequence(gate, probe);
        if (gate) {
            boundary();
        }
        scenarios(gate);
        refail(probe);

        if (failed > 0) {
            System.out.println("MdcPathfindGateTest FAIL " + failed);
            System.exit(1);
        }
        System.out.println("MdcPathfindGateTest OK (" + mode + ")");
    }

    /** 真 updateThread 連跑四輪：開服第一輪開 → 關 → 關（到期）→ 開；查任務、請求、W74 計數、rb／nr、心跳。 */
    private static void mainSequence(boolean gate, boolean probe) throws Exception {
        PathfindNativeThread t = fresh();
        expect("開服第一輪開", MdcPathfindGate.open);
        fillA(t);
        String r1 = snap(t, REAL.run(t));
        expect("R1：" + (gate ? "開閘一輪套完 16 個 chunk（含 1 個移除）與格子、載具，FIFO" : "T=0 原版：10 個 ChunkUpdateTask 上限照舊")
                + "（實際 " + r1 + "）", r1.equals(gate ? A_ON : A_OFF));
        if (gate) {
            expect("R1 後下一輪關閉（未滿 T）", !MdcPathfindGate.open);
        }
        expect("首次生效行" + (gate ? "已印" : "T=0 不印"), MdcPathfindGate.announced == gate);
        FakeNative fake = new FakeNative();
        ByteBuffer bb = ByteBuffer.allocate(16);
        MdcPathfindProbe.findPath(fake, request(new OtherMover()), bb, false);
        MdcPathfindProbe.findPath(fake, request(new OtherMover()), bb, false);

        t.chunkTaskQueue.add(new Chunk("c16"));
        t.squareTaskQueue.add(new Square("s2"));
        t.vehicleTaskQueue.add(new Vehicle("v2"));
        t.requestTaskQueue.add(new PathRequestTask().init(request(new OtherMover())));
        String r2 = snap(t, REAL.run(t));
        String r2On = "exec=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,c11,c12,c13,c14,c15,s1,v1]"
                + " ret=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,c11,c12,c13,c14,c15,s1,v1,req,req]"
                + " chunk=[c16] sq=[s2] veh=[v2] req=[] otherQ=2 thrown=-";
        String r2Off = "exec=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,s1,v1,c11,c12,c13,c14,c15,c16,s2,v2]"
                + " ret=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,s1,v1,req,c11,c12,c13,c14,c15,c16,s2,v2,req]"
                + " chunk=[] sq=[] veh=[] req=[] otherQ=2 thrown=-";
        expect("R2：" + (gate ? "閘門關：世界任務留在佇列、請求照常進 otherQ" : "T=0 原版：剩下 5 個與新任務照原版套用")
                + "（實際 " + r2 + "）", r2.equals(gate ? r2On : r2Off));
        MdcPathfindProbe.findPath(fake, request(new OtherMover()), bb, false);

        Thread.sleep(30L);
        t.chunkTaskQueue.add(new Chunk("c17"));
        if (gate) {
            MdcPathfindGate.lastOpenNs = System.nanoTime() - MdcPathfindGate.GATE_NS;
        }
        String r3 = snap(t, REAL.run(t));
        if (gate) {
            expect("R3：這輪仍關（決定在上一輪），積壓 c16、c17；結算時已滿 T → 下一輪開",
                    r3.contains(" chunk=[c16,c17] sq=[s2] veh=[v2] ") && MdcPathfindGate.open);
        } else {
            expect("R3：T=0 原版套用 c17", r3.startsWith("exec=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,s1,v1,c11,c12,c13,c14,c15,c16,s2,v2,c17]"));
        }
        String r4 = snap(t, REAL.run(t));
        if (gate) {
            expect("R4：開閘照 chunk→格子→載具、各自 FIFO 套完積壓，taskReturnQueue 同順序；下一輪又關（實際 " + r4 + "）",
                    r4.equals("exec=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,c11,c12,c13,c14,c15,s1,v1,c16,c17,s2,v2]"
                            + " ret=[c1,c2,c3,c4,r1,c5,c6,c7,c8,c9,c10,c11,c12,c13,c14,c15,s1,v1,req,req,c16,c17,s2,v2]"
                            + " chunk=[] sq=[] veh=[] req=[] otherQ=2 thrown=-") && !MdcPathfindGate.open);
        }
        MdcPathfindProbe.findPath(fake, request(new OtherMover()), bb, false);

        long[] stats = MdcPathfindGate.statsForTest();
        if (gate) {
            expect("閘門計數：opens=2、heldRounds=2、drained 18+4、最大 18、staleMsMax ≥ 30（實際 " + java.util.Arrays.toString(stats) + "）",
                    stats[0] == 2 && stats[1] == 2 && stats[2] == 22 && stats[3] == 18 && stats[4] >= 30_000_000L);
        } else {
            expect("T=0 閘門零計數（純委派）", java.util.Arrays.equals(stats, new long[5]));
        }
        if (probe) {
            long[] world = MdcPathfindProbe.worldTasksForTest();
            expect("W74 計數：chunk 18、格子 2、載具 2、請求任務 2（實際 " + java.util.Arrays.toString(world) + "）",
                    world[0] == 18 && world[1] == 2 && world[2] == 2 && world[3] == 2 && world[4] == 0);
            expect("W74 chunkU=17、chunkR=1", java.util.Arrays.equals(MdcPathfindProbe.chunkSplitForTest(), new long[] {17, 1}));
            expect("W74 worldRounds " + (gate ? "2（R1、R4）" : "3（R1、R2、R3）") + "、rb／nr " + (gate ? "2/2" : "3/1")
                            + "（實際 " + MdcPathfindProbe.worldRoundsForTest() + "、" + MdcPathfindProbe.rbForTest() + "/"
                            + MdcPathfindProbe.nrForTest() + "）",
                    MdcPathfindProbe.worldRoundsForTest() == (gate ? 2 : 3)
                            && MdcPathfindProbe.rbForTest() == (gate ? 2 : 3) && MdcPathfindProbe.nrForTest() == (gate ? 2 : 1)
                            && MdcPathfindProbe.roundsForTest() == 4);
            String window = MdcPathfindProbe.windowForTest();
            System.out.println("beat line: " + window);
            String gateFields = gate ? " gate=1000 opens=2 heldRounds=2 drained=11/18 staleMsMax=" : " gate=0 opens=0 heldRounds=0 drained=0/0 staleMsMax=0 ";
            expect("心跳行尾：" + gateFields.trim() + " … chunkU=17 chunkR=1 refail=0",
                    window.contains(gateFields) && window.endsWith(" chunkU=17 chunkR=1 refail=0")
                            && window.indexOf(" total=") < window.indexOf(" gate="));
            if (gate) {
                String stale = window.substring(window.indexOf(" staleMsMax=") + 12, window.indexOf(" chunkU="));
                expect("心跳 staleMsMax ≥ 30（實際 " + stale + "）", Long.parseLong(stale) >= 30L);
            }
            expect("簿記無異常", MdcPathfindProbe.anomaliesForTest() == 0);
        } else {
            expect("探針 off：零輪、零任務、零分組", MdcPathfindProbe.roundsForTest() == 0
                    && java.util.Arrays.equals(MdcPathfindProbe.worldTasksForTest(), new long[5])
                    && MdcPathfindProbe.rbForTest() == 0 && MdcPathfindProbe.nrForTest() == 0
                    && java.util.Arrays.equals(MdcPathfindProbe.chunkSplitForTest(), new long[2]));
        }
    }

    /** decide 的邊界：差 1 ns 不開、恰滿 T 開並記 lastOpen。 */
    private static void boundary() {
        long saved = MdcPathfindGate.lastOpenNs;
        long base = 1_000_000_000L;
        MdcPathfindGate.lastOpenNs = base;
        boolean early = MdcPathfindGate.decide(base + MdcPathfindGate.GATE_NS - 1L);
        long afterEarly = MdcPathfindGate.lastOpenNs;
        boolean due = MdcPathfindGate.decide(base + MdcPathfindGate.GATE_NS);
        expect("開閉邊界：now-lastOpen = T-1 ns 不開且 lastOpen 不動；= T 開並記 lastOpen",
                !early && afterEarly == base && due && MdcPathfindGate.lastOpenNs == base + MdcPathfindGate.GATE_NS);
        MdcPathfindGate.lastOpenNs = saved;
    }

    /** 三個情境：真 updateThread、原版模型、閘門模型與三個 mutant 各跑一次。 */
    private static void scenarios(boolean gate) throws Exception {
        Runner vanilla = model(ConcurrentLinkedQueue::poll);
        Runner gateModel = model(MdcPathfindGate::poll);
        // mutant 1：開閘時不自己排空，交給原版迴圈（仍受每輪 10 個上限）。
        Runner capped = model(q -> q == PathfindNativeThread.instance.requestTaskQueue || MdcPathfindGate.open ? q.poll() : null);
        // mutant 2：閘門關時連請求佇列一起擋。
        Runner blocksRequests = model(q -> MdcPathfindGate.open ? MdcPathfindGate.poll(q) : null);
        // mutant 3：排空時吞掉 execute 的例外並繼續。
        Runner swallows = model(q -> {
            PathfindNativeThread t = PathfindNativeThread.instance;
            if (q != t.chunkTaskQueue || !MdcPathfindGate.open) {
                return MdcPathfindGate.poll(q);
            }
            for (Object task; (task = q.poll()) != null; ) {
                try {
                    ((IPathfindTask) task).execute();
                    t.taskReturnQueue.add((IPathfindTask) task);
                } catch (RuntimeException e) {
                    // 吞掉
                }
            }
            return null;
        });

        String[] names = {"A 開閘、15 個 ChunkUpdateTask", "B 閘門關、四個佇列各一", "C 開閘、execute 丟例外"};
        String[] on = {A_ON, B_ON, C_ALL};
        String[] off = {A_OFF, B_OFF, C_ALL};
        boolean[] openFor = {true, false, true};
        boolean cappedKilled = false;
        boolean blocksKilled = false;
        boolean swallowsKilled = false;
        for (int i = 0; i < 3; i++) {
            String real = scenario(i, openFor[i], REAL);
            String v = scenario(i, openFor[i], vanilla);
            expect("情境 " + names[i] + "：原版模型 = 原版預期（模型自驗）", v.equals(off[i]));
            if (gate) {
                expect("情境 " + names[i] + "：真 updateThread = 出貨語意（實際 " + real + "）", real.equals(on[i]));
                expect("情境 " + names[i] + "：閘門模型 = 真 updateThread（模型自驗）", scenario(i, openFor[i], gateModel).equals(real));
                cappedKilled |= !scenario(i, openFor[i], capped).equals(on[i]);
                blocksKilled |= !scenario(i, openFor[i], blocksRequests).equals(on[i]);
                swallowsKilled |= !scenario(i, openFor[i], swallows).equals(on[i]);
            } else {
                expect("情境 " + names[i] + "：T=0 真 updateThread = 原版（實際 " + real + "）", real.equals(off[i]));
            }
        }
        if (gate) {
            expect("mutant 被抓到：開閘時仍受 10 個上限", cappedKilled);
            expect("mutant 被抓到：閘門關時擋到請求佇列", blocksKilled);
            expect("mutant 被抓到：排空時吞掉例外", swallowsKilled);
        }
    }

    private static String scenario(int which, boolean open, Runner runner) throws Exception {
        PathfindNativeThread t = fresh();
        if (which == 0) {
            fillA(t);
        } else if (which == 1) {
            t.chunkTaskQueue.add(new Chunk("c1"));
            t.squareTaskQueue.add(new Square("s1"));
            t.vehicleTaskQueue.add(new Vehicle("v1"));
            t.requestTaskQueue.add(new PathRequestTask().init(request(new OtherMover())));
        } else {
            t.chunkTaskQueue.add(new Chunk("a"));
            t.chunkTaskQueue.add(new Boom());
            t.chunkTaskQueue.add(new Chunk("c"));
            t.squareTaskQueue.add(new Square("s1"));
            t.requestTaskQueue.add(new PathRequestTask().init(request(new OtherMover())));
        }
        MdcPathfindGate.open = open;
        return snap(t, runner.run(t));
    }

    /** refail：同一個 mover 這次失敗的目標格等於上一次失敗的目標格才計；成功不重置；多目標第 2 次起不計；滿 10,000 筆清空。 */
    private static void refail(boolean probe) {
        MdcPathfindProbe.resetWindowForTest();
        FakeNative fake = new FakeNative();
        ByteBuffer bb = ByteBuffer.allocate(16);
        OtherMover m1 = new OtherMover();
        OtherMover m2 = new OtherMover();
        OtherMover m3 = new OtherMover();
        fake.result = 0;
        MdcPathfindProbe.findPath(fake, target(m1, 10.5f, 20.2f, 0f), bb, false);
        long first = MdcPathfindProbe.refailsForTest();
        MdcPathfindProbe.findPath(fake, target(m1, 10.9f, 20.7f, 0f), bb, false);
        long sameCell = MdcPathfindProbe.refailsForTest();
        fake.result = 1;
        MdcPathfindProbe.findPath(fake, target(m1, 10.5f, 20.5f, 0f), bb, false);
        fake.result = 0;
        MdcPathfindProbe.findPath(fake, target(m1, 10.5f, 20.5f, 0f), bb, false);
        long afterSuccess = MdcPathfindProbe.refailsForTest();
        MdcPathfindProbe.findPath(fake, target(m1, 11f, 20f, 0f), bb, false);
        MdcPathfindProbe.findPath(fake, target(m2, 11f, 20f, 0f), bb, false);
        long otherCell = MdcPathfindProbe.refailsForTest();
        PathFindRequest multi = target(m1, 11f, 20f, 0f);
        MdcPathfindProbe.findPath(fake, multi, bb, false);
        MdcPathfindProbe.findPath(fake, multi, bb, false);
        long multiTarget = MdcPathfindProbe.refailsForTest();
        MdcPathfindProbe.findPath(fake, target(m3, -1.5f, -2.5f, -1f), bb, false);
        MdcPathfindProbe.findPath(fake, target(m3, -1.2f, -2.9f, -1f), bb, false);
        MdcPathfindProbe.findPath(fake, target(m3, -1.2f, -2.9f, 0f), bb, false);
        long negative = MdcPathfindProbe.refailsForTest();
        if (!probe) {
            expect("探針 off：refail 不計、表不長", negative == 0 && MdcPathfindProbe.refailMapSizeForTest() == 0);
            return;
        }
        expect("refail：第一次 0、同格 1、中間成功不重置 2、換格與換 mover 不計 2、多目標第 2 次不計 3、負座標與樓層 4（實際 "
                        + first + "/" + sameCell + "/" + afterSuccess + "/" + otherCell + "/" + multiTarget + "/" + negative + "）",
                first == 0 && sameCell == 1 && afterSuccess == 2 && otherCell == 2 && multiTarget == 3 && negative == 4);
        while (MdcPathfindProbe.refailMapSizeForTest() < MdcPathfindProbe.REFAIL_CAP) {
            MdcPathfindProbe.findPath(fake, target(new OtherMover(), 1f, 1f, 0f), bb, false);
        }
        MdcPathfindProbe.findPath(fake, target(m1, 11f, 20f, 0f), bb, false);
        expect("refail 表滿 10,000 筆整表清空（清空後同一目標不算重試）",
                MdcPathfindProbe.refailsForTest() == 4 && MdcPathfindProbe.refailMapSizeForTest() == 1);
        expect("心跳 refail=4", MdcPathfindProbe.windowForTest().endsWith(" refail=4"));
    }

    private static void fillA(PathfindNativeThread t) {
        for (int i = 1; i <= 4; i++) {
            t.chunkTaskQueue.add(new Chunk("c" + i));
        }
        t.chunkTaskQueue.add(new ChunkRemove("r1"));
        for (int i = 5; i <= 15; i++) {
            t.chunkTaskQueue.add(new Chunk("c" + i));
        }
        t.squareTaskQueue.add(new Square("s1"));
        t.vehicleTaskQueue.add(new Vehicle("v1"));
        t.requestTaskQueue.add(new PathRequestTask().init(request(new OtherMover())));
    }

    private static PathfindNativeThread fresh() {
        PathfindNativeThread t = new PathfindNativeThread();
        PathfindNativeThread.instance = t;
        EXECUTED.clear();
        return t;
    }

    private static String snap(PathfindNativeThread t, Throwable thrown) throws Exception {
        if (thrown != null && thrown != BOOM) {
            throw new AssertionError("非預期例外", thrown);
        }
        return "exec=" + names(EXECUTED) + " ret=" + names(t.taskReturnQueue) + " chunk=" + names(t.chunkTaskQueue)
                + " sq=" + names(t.squareTaskQueue) + " veh=" + names(t.vehicleTaskQueue)
                + " req=" + names(t.requestTaskQueue) + " otherQ=" + otherQ(t).size()
                + " thrown=" + (thrown == null ? "-" : thrown.getMessage());
    }

    private static String names(Collection<?> c) {
        return c.stream().map(o -> o instanceof PathRequestTask ? "req" : o.toString())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static PathFindRequest request(Mover mover) {
        PathFindRequest r = new PathFindRequest();
        r.mover = mover;
        return r;
    }

    private static PathFindRequest target(Mover mover, float x, float y, float z) {
        PathFindRequest r = request(mover);
        r.targetX = x;
        r.targetY = y;
        r.targetZ = z;
        return r;
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<PathFindRequest> otherQ(PathfindNativeThread t) throws Exception {
        Field requests = PathfindNativeThread.class.getDeclaredField("requests");
        requests.setAccessible(true);
        RequestQueue q = (RequestQueue) requests.get(t);
        Field f = RequestQueue.class.getDeclaredField("otherQ");
        f.setAccessible(true);
        return (ArrayDeque<PathFindRequest>) f.get(q);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "pfg pass  " : "pfg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
