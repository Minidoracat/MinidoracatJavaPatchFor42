package zombie.network;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zombie.debug.DebugLogStream;
import zombie.debug.DebugType;

/** Login metrics wrapper 的行為與雙重例外 precedence 測試。 */
@SuppressWarnings("removal")
public final class LoginMetricsBehaviorTest {

    private static final String USER = "private-user";
    private static final String PASSWORD = "private-password";
    private static final String STEAM_ID = "private-steam-id";
    private static final Pattern METRIC = Pattern.compile(
            "^\\[MinidoracatJavaPatch\\]\\[LoginMetrics\\] "
                    + "op=(SET_PASSWORD|UPDATE_LAST_CONNECTION|SET_USER_STEAM_ID) elapsedNs=([0-9]+)$");

    public static void main(String[] args) throws Exception {
        Field logStream = DebugType.class.getDeclaredField("logStream");
        logStream.setAccessible(true);
        Object original = logStream.get(DebugType.Multiplayer);
        ProbeLogStream probe = new ProbeLogStream();
        logStream.set(DebugType.Multiplayer, probe);
        try {
            successAndPrivacy(probe);
            nonfatalFailuresPreserveDelegate(probe);
            loggingFatalWinsOverNonfatalDelegate(probe);
            delegateFatalSkipsSink(probe);
        } finally {
            logStream.set(DebugType.Multiplayer, original);
        }
        System.out.println("metrics OK  delegate/sink precedence、return identity、PII 與 cardinality 全數通過");
    }

    private static void successAndPrivacy(ProbeLogStream probe) throws Exception {
        FakeDatabase database = new FakeDatabase();

        probe.reset(null);
        MinidoracatLoginMetrics.setPassword(database, USER, PASSWORD);
        require(database.passwordCalls == 1, "setPassword delegate count");
        requireMetric(probe, "SET_PASSWORD");

        probe.reset(null);
        MinidoracatLoginMetrics.updateLastConnectionDate(database, USER, PASSWORD);
        require(database.updateCalls == 1, "updateLastConnectionDate delegate count");
        requireMetric(probe, "UPDATE_LAST_CONNECTION");

        String expected = new String("return-identity");
        database.userSteamResult = expected;
        probe.reset(null);
        String actual = MinidoracatLoginMetrics.setUserSteamID(database, USER, STEAM_ID);
        require(actual == expected, "setUserSteamID return identity");
        require(database.userSteamCalls == 1, "setUserSteamID delegate count");
        requireMetric(probe, "SET_USER_STEAM_ID");

        FakeDatabase receiver = new FakeDatabase();
        probe.reset(null);
        MinidoracatLoginMetrics.updateLastConnectionDate(receiver, USER, PASSWORD);
        require(receiver.updateCalls == 1 && database.updateCalls == 1, "receiver-first delegation");
    }

    private static void nonfatalFailuresPreserveDelegate(ProbeLogStream probe) {
        Throwable suppressed = new IllegalStateException("suppressed-sentinel");

        FakeDatabase sqlDb = new FakeDatabase();
        SQLException sqlFailure = new SQLException("delegate-sql");
        sqlFailure.addSuppressed(suppressed);
        sqlDb.passwordFailure = sqlFailure;
        probe.reset(new Exception("checked-sink-failure"));
        Throwable observed = capture(() ->
                MinidoracatLoginMetrics.setPassword(sqlDb, USER, PASSWORD));
        require(observed == sqlFailure, "SQLException identity");
        require(sqlDb.passwordCalls == 1 && probe.attempts == 1, "SQLException delegate/sink count");
        requireSuppressed(sqlFailure, suppressed);

        FakeDatabase runtimeDb = new FakeDatabase();
        RuntimeException runtimeFailure = new IllegalArgumentException("delegate-runtime");
        runtimeDb.updateFailure = runtimeFailure;
        probe.reset(new IllegalStateException("runtime-sink-failure"));
        observed = capture(() ->
                MinidoracatLoginMetrics.updateLastConnectionDate(runtimeDb, USER, PASSWORD));
        require(observed == runtimeFailure, "runtime exception identity");
        require(runtimeDb.updateCalls == 1 && probe.attempts == 1, "runtime delegate/sink count");

        FakeDatabase errorDb = new FakeDatabase();
        AssertionError errorFailure = new AssertionError("delegate-error");
        errorDb.userSteamFailure = errorFailure;
        probe.reset(null);
        observed = capture(() ->
                MinidoracatLoginMetrics.setUserSteamID(errorDb, USER, STEAM_ID));
        require(observed == errorFailure, "nonfatal Error identity");
        require(errorDb.userSteamCalls == 1 && probe.attempts == 1, "Error delegate/sink count");

        FakeDatabase returnDb = new FakeDatabase();
        String expected = new String("return-after-sink-failure");
        returnDb.userSteamResult = expected;
        probe.reset(new IllegalStateException("sink-only"));
        String actual = MinidoracatLoginMetrics.setUserSteamID(returnDb, USER, STEAM_ID);
        require(actual == expected, "nonfatal sink failure preserves return");
        require(returnDb.userSteamCalls == 1 && probe.attempts == 1, "sink-only delegate/sink count");
    }

    private static void loggingFatalWinsOverNonfatalDelegate(ProbeLogStream probe) {
        FakeDatabase runtimeDb = new FakeDatabase();
        RuntimeException delegate = new IllegalArgumentException("delegate-runtime");
        OutOfMemoryError sinkFatal = new OutOfMemoryError("sink-fatal");
        runtimeDb.updateFailure = delegate;
        probe.reset(sinkFatal);
        Throwable observed = capture(() ->
                MinidoracatLoginMetrics.updateLastConnectionDate(runtimeDb, USER, PASSWORD));
        require(observed == sinkFatal, "logging VirtualMachineError precedence");
        require(runtimeDb.updateCalls == 1 && probe.attempts == 1, "logging VME counts");
        require(delegate.getSuppressed().length == 0, "logging VME does not mutate delegate");

        FakeDatabase successDb = new FakeDatabase();
        ThreadDeath threadDeath = new ThreadDeath();
        probe.reset(threadDeath);
        observed = capture(() ->
                MinidoracatLoginMetrics.setUserSteamID(successDb, USER, STEAM_ID));
        require(observed == threadDeath, "logging ThreadDeath precedence");
        require(successDb.userSteamCalls == 1 && probe.attempts == 1, "logging ThreadDeath counts");

        FakeDatabase sqlDb = new FakeDatabase();
        SQLException sqlFailure = new SQLException("delegate-sql");
        LinkageError linkageError = new LinkageError("sink-linkage");
        sqlDb.passwordFailure = sqlFailure;
        probe.reset(linkageError);
        observed = capture(() ->
                MinidoracatLoginMetrics.setPassword(sqlDb, USER, PASSWORD));
        require(observed == linkageError, "logging LinkageError precedence");
        require(sqlDb.passwordCalls == 1 && probe.attempts == 1, "logging LinkageError counts");
        require(sqlFailure.getSuppressed().length == 0, "logging LinkageError does not mutate delegate");
    }

    private static void delegateFatalSkipsSink(ProbeLogStream probe) {
        FakeDatabase virtualMachineDb = new FakeDatabase();
        OutOfMemoryError virtualMachineError = new OutOfMemoryError("delegate-vme");
        virtualMachineDb.passwordFailure = virtualMachineError;
        probe.reset(new AssertionError("must-not-run"));
        Throwable observed = capture(() ->
                MinidoracatLoginMetrics.setPassword(virtualMachineDb, USER, PASSWORD));
        require(observed == virtualMachineError, "delegate VirtualMachineError identity");
        require(virtualMachineDb.passwordCalls == 1 && probe.attempts == 0, "delegate VME skips sink");

        FakeDatabase threadDeathDb = new FakeDatabase();
        ThreadDeath threadDeath = new ThreadDeath();
        threadDeathDb.updateFailure = threadDeath;
        probe.reset(new AssertionError("must-not-run"));
        observed = capture(() ->
                MinidoracatLoginMetrics.updateLastConnectionDate(threadDeathDb, USER, PASSWORD));
        require(observed == threadDeath, "delegate ThreadDeath identity");
        require(threadDeathDb.updateCalls == 1 && probe.attempts == 0, "delegate ThreadDeath skips sink");

        FakeDatabase linkageDb = new FakeDatabase();
        LinkageError linkageError = new LinkageError("delegate-linkage");
        linkageDb.userSteamFailure = linkageError;
        probe.reset(new AssertionError("must-not-run"));
        observed = capture(() ->
                MinidoracatLoginMetrics.setUserSteamID(linkageDb, USER, STEAM_ID));
        require(observed == linkageError, "delegate LinkageError identity");
        require(linkageDb.userSteamCalls == 1 && probe.attempts == 0, "delegate LinkageError skips sink");
    }

    private static void requireMetric(ProbeLogStream probe, String operation) {
        require(probe.attempts == 1 && probe.lines.size() == 1, operation + " metric cardinality");
        String line = probe.lines.get(0);
        Matcher matcher = METRIC.matcher(line);
        require(matcher.matches(), operation + " metric regex");
        require(operation.equals(matcher.group(1)), operation + " metric op");
        require(Long.parseLong(matcher.group(2)) >= 0L, operation + " elapsedNs");
        require(!line.contains(USER) && !line.contains(PASSWORD) && !line.contains(STEAM_ID),
                operation + " metric privacy");
    }

    private static void requireSuppressed(Throwable failure, Throwable expected) {
        Throwable[] suppressed = failure.getSuppressed();
        require(suppressed.length == 1 && suppressed[0] == expected, "suppressed list unchanged");
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

    private static final class FakeDatabase extends ServerWorldDatabase {
        int passwordCalls;
        int updateCalls;
        int userSteamCalls;
        Throwable passwordFailure;
        Throwable updateFailure;
        Throwable userSteamFailure;
        String userSteamResult;

        @Override
        public void setPassword(String username, String password) throws SQLException {
            passwordCalls++;
            if (passwordFailure != null) {
                LoginMetricsBehaviorTest.<RuntimeException>rethrow(passwordFailure);
            }
        }

        @Override
        public void updateLastConnectionDate(String username, String password) {
            updateCalls++;
            if (updateFailure != null) {
                LoginMetricsBehaviorTest.<RuntimeException>rethrow(updateFailure);
            }
        }

        @Override
        public String setUserSteamID(String username, String steamID) {
            userSteamCalls++;
            if (userSteamFailure != null) {
                LoginMetricsBehaviorTest.<RuntimeException>rethrow(userSteamFailure);
            }
            return userSteamResult;
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
                LoginMetricsBehaviorTest.<RuntimeException>rethrow(failure);
            }
        }
    }

    private LoginMetricsBehaviorTest() {}
}
