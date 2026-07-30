package zombie.network;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugLogStream;
import zombie.debug.DebugType;
import zombie.network.packets.character.CreatePlayerPacket;

/**
 * Join metrics wrapper 的行為與例外 precedence 測試。
 *
 * <p>全象限覆蓋以可 override write() 的 FakePacket 為主軸（不觸發 GameClient/Lua 的
 * static init）：delegate 成功、receiver/argument identity、nonfatal sentinel identity、
 * 三種 delegate fatal 均直拋且不進 sink、sink nonfatal 不改 delegate outcome、
 * sink fatal precedence。null-receiver NPE 與無 Lua env 的 triggerEvent NPE 驗證其餘
 * 三個 wrapper 的 delegate 失敗路徑——注意 LuaEventManager 對未知事件會**先註冊進
 * EventMap 再碰 LuaManager.env 拋 NPE**，同名事件第二次呼叫會正常返回，因此每個
 * triggerEvent 測試點使用唯一事件名以保證走 NPE 路徑。</p>
 */
@SuppressWarnings("removal")
public final class JoinMetricsBehaviorTest {

    private static final Pattern METRIC = Pattern.compile(
            "^\\[MinidoracatJavaPatch\\]\\[JoinMetrics\\] "
                    + "op=(TRIGGER_ON_NEW_GAME|DB_UPDATE_CHARACTER|DB_PROCESS|WRITE_PACKET"
                    + "|REJOIN_TOTAL|REJOIN_LOAD_CHARACTER) elapsedNs=([0-9]+)$");

    private static int probeEventCounter;

    public static void main(String[] args) throws Exception {
        Field logStream = DebugType.class.getDeclaredField("logStream");
        logStream.setAccessible(true);
        Object original = logStream.get(DebugType.Multiplayer);
        ProbeLogStream probe = new ProbeLogStream();
        logStream.set(DebugType.Multiplayer, probe);
        try {
            successAndIdentity(probe);
            delegateNpePerWrapper(probe);
            nonfatalIdentityAndSinkNonfatal(probe);
            delegateFatalSkipsSink(probe);
            sinkFatalPrecedence(probe);
        } finally {
            logStream.set(DebugType.Multiplayer, original);
        }
        System.out.println("join-metrics OK  delegate/sink precedence、identity 與 cardinality 全數通過");
    }

    /** 每次取新事件名：LuaEventManager 對未知事件先註冊再拋 NPE，同名第二次會成功。 */
    private static String freshProbeEvent() {
        return "MinidoracatJoinMetricsProbeEvent" + probeEventCounter++;
    }

    private static void successAndIdentity(ProbeLogStream probe) {
        FakePacket packet = new FakePacket();
        ByteBufferWriter writer = new ByteBufferWriter(ByteBuffer.allocate(16));
        probe.reset(null);
        MinidoracatJoinMetrics.write(packet, writer);
        require(packet.calls == 1, "delegate exactly once");
        require(packet.lastWriter == writer, "argument identity");
        requireMetric(probe, "WRITE_PACKET");
    }

    private static void delegateNpePerWrapper(ProbeLogStream probe) {
        probe.reset(null);
        Throwable observed = capture(() -> MinidoracatJoinMetrics.triggerEvent(freshProbeEvent(), null, null));
        require(observed instanceof NullPointerException, "triggerEvent 無 Lua env NPE");
        requireMetric(probe, "TRIGGER_ON_NEW_GAME");

        probe.reset(null);
        observed = capture(() -> MinidoracatJoinMetrics.serverUpdateNetworkCharacter(null, null, 0, null));
        require(observed instanceof NullPointerException, "serverUpdateNetworkCharacter(null) NPE");
        requireMetric(probe, "DB_UPDATE_CHARACTER");

        probe.reset(null);
        observed = capture(() -> MinidoracatJoinMetrics.process(null));
        require(observed instanceof NullPointerException, "process(null) NPE");
        requireMetric(probe, "DB_PROCESS");

        // REJOIN_LOAD_CHARACTER：null receiver 在派發點即拋，不觸發 ServerPlayerDB <clinit>。
        // REJOIN_TOTAL 的 delegate 是 GameServer static（INVOKESTATIC 必觸發 GameServer <clinit>，
        // 裸 JVM 無法安全初始化）——delegate 行為由 SmokeCheck 結構斷言補位。
        probe.reset(null);
        observed = capture(() -> MinidoracatJoinMetrics.serverLoadNetworkCharacter(null, 0, "probe"));
        require(observed instanceof NullPointerException, "serverLoadNetworkCharacter(null) NPE");
        requireMetric(probe, "REJOIN_LOAD_CHARACTER");
    }

    private static void nonfatalIdentityAndSinkNonfatal(ProbeLogStream probe) {
        FakePacket packet = new FakePacket();
        RuntimeException sentinel = new IllegalStateException("delegate-nonfatal-sentinel");
        packet.failure = sentinel;
        probe.reset(null);
        Throwable observed = capture(() ->
                MinidoracatJoinMetrics.write(packet, new ByteBufferWriter(ByteBuffer.allocate(16))));
        require(observed == sentinel, "nonfatal sentinel identity");
        require(packet.calls == 1, "nonfatal delegate count");
        requireMetric(probe, "WRITE_PACKET");

        FakePacket okPacket = new FakePacket();
        probe.reset(new IllegalStateException("sink-nonfatal"));
        MinidoracatJoinMetrics.write(okPacket, new ByteBufferWriter(ByteBuffer.allocate(16)));
        require(okPacket.calls == 1 && probe.attempts == 1,
                "sink nonfatal 吞掉、delegate 成功結果保留");

        probe.reset(new IllegalStateException("sink-nonfatal-over-npe"));
        observed = capture(() -> MinidoracatJoinMetrics.process(null));
        require(observed instanceof NullPointerException, "delegate NPE 優先於 sink nonfatal");
        require(probe.attempts == 1, "sink 仍被嘗試一次");
    }

    private static void delegateFatalSkipsSink(ProbeLogStream probe) {
        Throwable[] fatals = {
            new OutOfMemoryError("delegate-vme"),
            new ThreadDeath(),
            new LinkageError("delegate-linkage")
        };
        for (Throwable fatal : fatals) {
            FakePacket packet = new FakePacket();
            packet.failure = fatal;
            probe.reset(new AssertionError("must-not-run"));
            Throwable observed = capture(() ->
                    MinidoracatJoinMetrics.write(packet, new ByteBufferWriter(ByteBuffer.allocate(16))));
            require(observed == fatal, "delegate fatal identity: " + fatal.getClass().getSimpleName());
            require(packet.calls == 1 && probe.attempts == 0,
                    "delegate fatal skips sink: " + fatal.getClass().getSimpleName());
        }
    }

    private static void sinkFatalPrecedence(ProbeLogStream probe) {
        FakePacket okPacket = new FakePacket();
        OutOfMemoryError sinkFatal = new OutOfMemoryError("sink-fatal");
        probe.reset(sinkFatal);
        Throwable observed = capture(() ->
                MinidoracatJoinMetrics.write(okPacket, new ByteBufferWriter(ByteBuffer.allocate(16))));
        require(observed == sinkFatal, "sink VirtualMachineError 蓋過 delegate 成功");
        require(okPacket.calls == 1 && probe.attempts == 1, "sink VME counts");

        LinkageError linkage = new LinkageError("sink-linkage");
        probe.reset(linkage);
        observed = capture(() -> MinidoracatJoinMetrics.process(null));
        require(observed == linkage, "sink LinkageError 蓋過 delegate NPE");
    }

    private static void requireMetric(ProbeLogStream probe, String operation) {
        require(probe.attempts == 1 && probe.lines.size() == 1, operation + " metric cardinality");
        Matcher matcher = METRIC.matcher(probe.lines.get(0));
        require(matcher.matches(), operation + " metric regex");
        require(operation.equals(matcher.group(1)), operation + " metric op");
        require(Long.parseLong(matcher.group(2)) >= 0L, operation + " elapsedNs");
        require(!probe.lines.get(0).contains("ProbeEvent"), operation + " metric privacy");
    }

    private static Throwable capture(ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T)failure;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    /** 可注入失敗的 write() override；不觸發 GameClient/Lua static init。 */
    private static final class FakePacket extends CreatePlayerPacket {
        int calls;
        ByteBufferWriter lastWriter;
        Throwable failure;

        @Override
        public void write(ByteBufferWriter b) {
            calls++;
            lastWriter = b;
            if (failure != null) {
                JoinMetricsBehaviorTest.<RuntimeException>rethrow(failure);
            }
        }
    }

    private static final class ProbeLogStream extends DebugLogStream {
        int attempts;
        Throwable failure;
        final List<String> lines = new ArrayList<>();

        ProbeLogStream() {
            super(System.out, System.err, System.err, DebugType.Multiplayer);
        }

        void reset(Throwable nextFailure) {
            attempts = 0;
            failure = nextFailure;
            lines.clear();
        }

        @Override
        public void println(String line) {
            attempts++;
            lines.add(line);
            if (failure != null) {
                JoinMetricsBehaviorTest.<RuntimeException>rethrow(failure);
            }
        }
    }

    private JoinMetricsBehaviorTest() {}
}
