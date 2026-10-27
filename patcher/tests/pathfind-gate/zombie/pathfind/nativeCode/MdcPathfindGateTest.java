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
 * W81 MdcPathfindGate 行為驗證（獨立 JVM；argv[0] = gate-on|gate-off|probe-off|adaptive-off，須與 {@code -Dmdc.pathfindGateMs}、
 * {@code -Dmdc.pathfindProbe}、{@code -Dmdc.pathfindGateAdaptive} 相符）。
 *
 * <p>走 dist 內手術後的真 {@code PathfindNativeThread.updateThread}（native {@code PathfindNative.update} 照 W74 測試的做法：
 * 測試 JVM 沒有 native 庫，丟 {@code UnsatisfiedLinkError} 當作一輪結束；閘門在呼叫 native 之前就結算並決定下一輪）。
 *
 * <p>鎖：開服第一輪開、之後每 T 開一輪；關閉時世界任務留在佇列、請求照常進優先佇列；開閘一輪套完全部積壓（超過 10 個 chunk）
 * 且 FIFO；{@code execute} 丟例外與原版相同；T=0 與原版逐輪相同（含 10 個上限）；W74 計數、rb／nr 分組、心跳欄位、refail；
 * {@code staleMsMax} 不含輪與輪之間的閒置、穩定狀態約 T 到 T 加一輪；同一個 chunk 移除後又載入的 {@code reload10}／{@code reload60}；
 * 自適應計時（合成時鐘）：開閘輪耗時大於 T 時不連續開閘、下一次開閘 ≥ 開閘輪結束＋max(T, 2×開閘輪耗時)，fixed（v1）為負對照。
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
    /** R1 之後、R2（第一個被擋輪次）之前的閒置；staleMsMax 不得把它算進去。 */
    static final long IDLE_MS = 400L;
    /** stale 穩定狀態測試裡輪與輪之間的間隔（模擬 Sync 每輪 50 ms）。 */
    static final long ROUND_MS = 50L;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "gate-on";
        boolean gate = !"gate-off".equals(mode);
        boolean probe = !"probe-off".equals(mode);
        boolean adaptive = !"adaptive-off".equals(mode);
        expect("開關與 argv 相符（" + mode + "：gate=" + MdcPathfindGate.GATE_MS + " probe=" + MdcPathfindProbe.ENABLED
                        + " adaptive=" + MdcPathfindGate.ADAPTIVE + "）",
                MdcPathfindGate.GATE_MS == (gate ? 1000L : 0L) && MdcPathfindProbe.ENABLED == probe
                        && MdcPathfindGate.ADAPTIVE == adaptive);
        expect("開關解析：未設 1000、0／off 關、0–5000 照用、範圍外與亂碼落回 1000",
                MdcPathfindGate.parseMs(null) == 1000L && MdcPathfindGate.parseMs("0") == 0L
                        && MdcPathfindGate.parseMs(" OFF ") == 0L && MdcPathfindGate.parseMs("5000") == 5000L
                        && MdcPathfindGate.parseMs("250") == 250L && MdcPathfindGate.parseMs("5001") == 1000L
                        && MdcPathfindGate.parseMs("-1") == 1000L && MdcPathfindGate.parseMs("abc") == 1000L);

        mainSequence(gate, probe);
        if (gate) {
            boundary();
            adaptiveTiming(adaptive);
        }
        scenarios(gate);
        if (gate) {
            staleSteady();
        }
        refail(probe);
        reload(probe);

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

        Thread.sleep(IDLE_MS);
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
            MdcPathfindGate.lastOpenNs = System.nanoTime() - MdcPathfindGate.holdNs;
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
            expect("閘門計數：opens=2、heldRounds=2、drained 18+4、最大 18、staleMsMax ≥ 30 且不含 R2 前閒置的 " + IDLE_MS
                            + " ms（實際 " + java.util.Arrays.toString(stats) + "）",
                    stats[0] == 2 && stats[1] == 2 && stats[2] == 22 && stats[3] == 18 && stats[4] >= 30_000_000L
                            && stats[4] < IDLE_MS * 1_000_000L);
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
            String tail = " chunkU=17 chunkR=1 refail=0 reload10=1 reload60=1 holdMsMax=";
            expect("心跳行尾：" + gateFields.trim() + " …" + tail + "（r1 之後緊接著載入同一個 chunk c5）",
                    window.contains(gateFields) && window.contains(tail)
                            && window.indexOf(" total=") < window.indexOf(" gate="));
            long holdMs = Long.parseLong(window.substring(window.indexOf(" holdMsMax=") + 11, window.indexOf(" rbMin=")));
            boolean adaptive = MdcPathfindGate.ADAPTIVE;
            expect("心跳 holdMsMax：" + (!gate ? "T=0 為 0" : adaptive ? "R1 開閘輪結束後 ≥ T" : "fixed 恰為 T")
                    + "（實際 " + holdMs + "）", !gate ? holdMs == 0L : adaptive ? holdMs >= 1000L : holdMs == 1000L);
            if (gate) {
                String stale = window.substring(window.indexOf(" staleMsMax=") + 12, window.indexOf(" chunkU="));
                expect("心跳 staleMsMax ≥ 30 且 < " + IDLE_MS + "（實際 " + stale + "）",
                        Long.parseLong(stale) >= 30L && Long.parseLong(stale) < IDLE_MS);
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
        MdcPathfindGate.holdNs = MdcPathfindGate.GATE_NS;
        MdcPathfindGate.lastOpenNs = base;
        boolean early = MdcPathfindGate.decide(base + MdcPathfindGate.GATE_NS - 1L);
        long afterEarly = MdcPathfindGate.lastOpenNs;
        boolean due = MdcPathfindGate.decide(base + MdcPathfindGate.GATE_NS);
        expect("開閉邊界：now-lastOpen = T-1 ns 不開且 lastOpen 不動；= T 開並記 lastOpen",
                !early && afterEarly == base && due && MdcPathfindGate.lastOpenNs == base + MdcPathfindGate.GATE_NS);
        MdcPathfindGate.lastOpenNs = saved;
    }

    /**
     * 自適應計時（合成時鐘直接呼叫 closeRound；開閘輪的重建 findPath 在它的結算之後、下一次結算之前，以 W74 的
     * busyNsTotal 累加模擬）。adaptive：開閘輪 findPath 3T → 下一輪不開、hold=6T、從開閘輪結束起算，6T−1 ns 不開、恰 6T 開；
     * findPath T/4 之後閒置 100T → hold=T（閒置不算進開閘輪耗時；冒煙實測牆鐘版閒置 47 秒把 hold 撐到 95 秒）；
     * 開閘輪本身結算時即使早已超過 hold 也不連開；holdMsMax=6T。fixed（負對照，v1）：耗時 3T 的下一次結算就開、hold 恆為 T。
     */
    private static void adaptiveTiming(boolean adaptive) {
        long T = MdcPathfindGate.GATE_NS;
        long ms = 1_000_000L;
        long t0 = 1_000_000_000_000L;
        MdcPathfindGate.resetWindow();
        MdcPathfindGate.open = true;
        MdcPathfindGate.lastRoundOpen = false;
        MdcPathfindGate.holdNs = T;
        MdcPathfindGate.lastOpenNs = t0 - ms;
        MdcPathfindGate.closeRound(t0);
        boolean afterOpen = MdcPathfindGate.open;
        MdcPathfindProbe.busyNsTotal += 3 * T;
        long t1 = t0 + 3 * T + 50 * ms;
        MdcPathfindGate.closeRound(t1);
        boolean afterRebuild = MdcPathfindGate.open;
        long hold1 = MdcPathfindGate.holdNs;
        long start1 = MdcPathfindGate.lastOpenNs;
        if (!adaptive) {
            expect("fixed（v1 負對照）：開閘輪耗時 3T，下一次結算就開（重建吃掉整個 T）、hold 恆為 T、holdMsMax=T",
                    !afterOpen && afterRebuild && hold1 == T && start1 == t1 && MdcPathfindGate.holdNsMax == T);
        } else {
            expect("adaptive：開閘輪 findPath 3T 時不連續開閘，hold=6T 從開閘輪結束（t1）起算",
                    !afterOpen && !afterRebuild && hold1 == 6 * T && start1 == t1);
            MdcPathfindGate.closeRound(t1 + 6 * T - 1L);
            boolean early = MdcPathfindGate.open;
            long t2 = t1 + 6 * T;
            MdcPathfindGate.closeRound(t2);
            expect("adaptive：下一次開閘 ≥ 開閘輪結束＋max(T, 2×3T)：差 1 ns 不開、恰滿開", !early && MdcPathfindGate.open);
            MdcPathfindGate.closeRound(t2 + ms);
            boolean afterOpen2 = MdcPathfindGate.open;
            MdcPathfindProbe.busyNsTotal += T / 4;
            long t3 = t2 + ms + 100 * T;
            MdcPathfindGate.closeRound(t3);
            expect("adaptive：findPath T/4 後閒置 100T → hold=T（閒置不算、不低於 T）、從 t3 起算（實際 hold "
                            + MdcPathfindGate.holdNs / ms + " ms）",
                    !afterOpen2 && !MdcPathfindGate.open && MdcPathfindGate.holdNs == T && MdcPathfindGate.lastOpenNs == t3);
            MdcPathfindGate.closeRound(t3 + T - 1L);
            boolean early2 = MdcPathfindGate.open;
            MdcPathfindGate.closeRound(t3 + T);
            expect("adaptive：T−1 ns 不開、恰滿 T 開", !early2 && MdcPathfindGate.open);
            MdcPathfindGate.lastOpenNs = t3 - 100 * T;
            MdcPathfindGate.closeRound(t3 + T + ms);
            expect("adaptive：開閘輪自己的結算即使早已超過 hold 也不連開", !MdcPathfindGate.open);
            expect("adaptive：holdMsMax 記到 6T", MdcPathfindGate.holdNsMax == 6 * T);
        }
        MdcPathfindGate.open = false;
        MdcPathfindGate.lastRoundOpen = false;
        MdcPathfindGate.holdNs = T;
        MdcPathfindGate.lastOpenNs = System.nanoTime();
        MdcPathfindGate.resetWindow();
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
        expect("心跳 refail=4", MdcPathfindProbe.windowForTest().contains(" refail=4 "));
    }

    /** stale 穩定狀態：每輪間隔 ROUND_MS、每輪都有新 chunk；從關閉後第一輪到開閘那輪 ≈ T（T − 一輪到 T ＋ 一輪）。 */
    private static void staleSteady() throws Exception {
        PathfindNativeThread t = fresh();
        REAL.run(t);
        MdcPathfindGate.lastOpenNs = System.nanoTime() - MdcPathfindGate.holdNs;
        REAL.run(t);
        REAL.run(t);
        expect("stale 穩定狀態：前置兩輪後閘門關、積壓為空", !MdcPathfindGate.open && t.chunkTaskQueue.isEmpty());
        MdcPathfindProbe.resetWindowForTest();
        int held = 0;
        for (boolean wasOpen = false; !wasOpen; held++) {
            Thread.sleep(ROUND_MS);
            t.chunkTaskQueue.add(new Chunk("k" + held));
            wasOpen = MdcPathfindGate.open;
            REAL.run(t);
            if (held > 200) {
                throw new AssertionError("閘門一直沒開");
            }
        }
        long staleMs = MdcPathfindGate.statsForTest()[4] / 1_000_000L;
        long lo = MdcPathfindGate.GATE_MS - ROUND_MS;
        long hi = MdcPathfindGate.GATE_MS + 2 * ROUND_MS;
        expect("stale 穩定狀態：每 " + ROUND_MS + " ms 一輪，staleMsMax 在 " + lo + "–" + hi + "（實際 " + staleMs + "，"
                + (held - 1) + " 輪被擋）", staleMs >= lo && staleMs <= hi);
    }

    /**
     * reload：真 updateThread 執行 ChunkRemoveTask 後，同一個 chunk 的 ChunkUpdateTask 在 10 秒內計 reload10、60 秒內計 reload60
     * （含 reload10）；超過 60 秒、別的 chunk、沒移除過都不計；beat 清掉超過 60 秒的紀錄、留下較新的；表滿整表清空；探針 off 不計。
     */
    private static void reload(boolean probe) throws Exception {
        PathfindNativeThread t = fresh();
        MdcPathfindProbe.ageRemovedForTest(MdcPathfindProbe.RELOAD60_NS + 1L);
        MdcPathfindProbe.resetWindowForTest();
        boolean empty = MdcPathfindProbe.removedSizeForTest() == 0;
        runChunks(t, remove(1, 2), load(1, 2));
        String within10 = reloads();
        runChunks(t, remove(1, 2));
        MdcPathfindProbe.ageRemovedForTest(30_000_000_000L);
        runChunks(t, load(1, 2));
        String within60 = reloads();
        runChunks(t, remove(1, 2));
        MdcPathfindProbe.ageRemovedForTest(MdcPathfindProbe.RELOAD60_NS + 1L);
        runChunks(t, load(1, 2));
        String over60 = reloads();
        runChunks(t, remove(1, 2), load(2, 1), load(-1, 2));
        String otherChunk = reloads();
        runChunks(t, remove(3, -1), load(-1, -1));
        String negative = reloads();
        runChunks(t, load(3, -1));
        String negativeSame = reloads();
        if (!probe) {
            expect("探針 off：reload 不計、最近移除表不長（實際 " + negativeSame + "，表 "
                    + MdcPathfindProbe.removedSizeForTest() + "）",
                    negativeSame.equals("0/0") && MdcPathfindProbe.removedSizeForTest() == 0);
            return;
        }
        expect("reload：移除後馬上載入 1/1、隔 30 秒 1/2、超過 60 秒不計 1/2、別的 chunk 與沒移除過不計 1/2、"
                        + "負座標不撞 key 1/2→2/3（實際 " + within10 + "、" + within60 + "、" + over60 + "、" + otherChunk + "、"
                        + negative + "→" + negativeSame + "）",
                empty && within10.equals("1/1") && within60.equals("1/2") && over60.equals("1/2")
                        && otherChunk.equals("1/2") && negative.equals("1/2") && negativeSame.equals("2/3"));
        String tail = MdcPathfindProbe.windowForTest();
        expect("心跳 reload10=2 reload60=3（後面接 v2 欄位）", tail.contains(" reload10=2 reload60=3 holdMsMax="));

        // 表裡剩 (1,2)；推 59 秒後再移除 (5,5)，再推 2 秒：(1,2) 61 秒、(5,5) 2 秒。
        MdcPathfindProbe.ageRemovedForTest(59_000_000_000L);
        runChunks(t, remove(5, 5));
        MdcPathfindProbe.ageRemovedForTest(2_000_000_000L);
        int before = MdcPathfindProbe.removedSizeForTest();
        MdcPathfindProbe.resetWindowForTest();
        int after = MdcPathfindProbe.removedSizeForTest();
        String reset = reloads();
        runChunks(t, load(1, 2), load(5, 5));
        expect("beat：清掉超過 60 秒的 (1,2)、留下 2 秒的 (5,5)，計數歸零；之後載入 (1,2) 不計、(5,5) 計 1/1（實際 表 " + before
                        + "→" + after + "、" + reset + "→" + reloads() + "）",
                before == 2 && after == 1 && reset.equals("0/0") && reloads().equals("1/1"));

        Object[] fill = new Object[MdcPathfindProbe.REMOVED_CAP];
        for (int i = 0; i < fill.length; i++) {
            fill[i] = remove(1000 + i, 0);
        }
        runChunks(t, fill);
        int full = MdcPathfindProbe.removedSizeForTest();
        runChunks(t, remove(7, 7));
        expect("最近移除表滿 " + MdcPathfindProbe.REMOVED_CAP + " 筆整表清空（實際 " + full + "→"
                + MdcPathfindProbe.removedSizeForTest() + "）", full == MdcPathfindProbe.REMOVED_CAP
                && MdcPathfindProbe.removedSizeForTest() == 1);
        MdcPathfindProbe.resetWindowForTest();
    }

    /** 把 chunk 任務照順序放進佇列，強制開閘跑一輪真 updateThread（T=0 時本來就每輪開）。 */
    private static void runChunks(PathfindNativeThread t, Object... tasks) throws Exception {
        for (Object task : tasks) {
            t.chunkTaskQueue.add((IPathfindTask) task);
        }
        MdcPathfindGate.open = true;
        Throwable thrown = REAL.run(t);
        if (thrown != null || !t.chunkTaskQueue.isEmpty()) {
            throw new AssertionError("chunk 佇列沒排空", thrown);
        }
    }

    private static ChunkRemove remove(int wx, int wy) {
        ChunkRemove task = new ChunkRemove("r" + wx + "," + wy);
        task.wx = wx;
        task.wy = wy;
        return task;
    }

    private static Chunk load(int wx, int wy) {
        Chunk task = new Chunk("u" + wx + "," + wy);
        task.wx = wx;
        task.wy = wy;
        return task;
    }

    private static String reloads() {
        long[] r = MdcPathfindProbe.reloadForTest();
        return r[0] + "/" + r[1];
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
