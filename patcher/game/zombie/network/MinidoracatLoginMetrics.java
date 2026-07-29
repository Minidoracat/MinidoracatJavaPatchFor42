package zombie.network;

import java.sql.SQLException;

import zombie.debug.DebugType;

/**
 * LoginPacket 的窄範圍計時 wrapper。
 *
 * <p>只量測並委派原 ServerWorldDatabase 呼叫；不保存玩家資料、不改 SQL／transaction，
 * 也不新增執行緒、queue、cache 或 retry。</p>
 */
@SuppressWarnings("removal")
public final class MinidoracatLoginMetrics {

    private static final String SET_PASSWORD = "SET_PASSWORD";
    private static final String UPDATE_LAST_CONNECTION = "UPDATE_LAST_CONNECTION";
    private static final String SET_USER_STEAM_ID = "SET_USER_STEAM_ID";

    public static void setPassword(ServerWorldDatabase receiver, String username, String password)
            throws SQLException {
        long start = System.nanoTime();
        long elapsedNs;
        Throwable failure = null;
        try {
            receiver.setPassword(username, password);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            elapsedNs = System.nanoTime() - start;
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(SET_PASSWORD, elapsedNs);
        if (failure instanceof SQLException sql) {
            throw sql;
        }
        if (failure != null) {
            MinidoracatLoginMetrics.<RuntimeException>rethrow(failure);
        }
    }

    public static void updateLastConnectionDate(
            ServerWorldDatabase receiver, String username, String password) {
        long start = System.nanoTime();
        long elapsedNs;
        Throwable failure = null;
        try {
            receiver.updateLastConnectionDate(username, password);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            elapsedNs = System.nanoTime() - start;
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(UPDATE_LAST_CONNECTION, elapsedNs);
        if (failure != null) {
            MinidoracatLoginMetrics.<RuntimeException>rethrow(failure);
        }
    }

    public static String setUserSteamID(
            ServerWorldDatabase receiver, String username, String steamID) {
        long start = System.nanoTime();
        long elapsedNs;
        String result = null;
        Throwable failure = null;
        try {
            result = receiver.setUserSteamID(username, steamID);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            elapsedNs = System.nanoTime() - start;
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(SET_USER_STEAM_ID, elapsedNs);
        if (failure != null) {
            MinidoracatLoginMetrics.<RuntimeException>rethrow(failure);
        }
        return result;
    }

    private static void safeLog(String operation, long elapsedNs) {
        try {
            DebugType.Multiplayer.println(
                    "[MinidoracatJavaPatch][LoginMetrics] op=" + operation + " elapsedNs=" + elapsedNs);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (failure instanceof ThreadDeath fatal) {
                throw fatal;
            }
            if (failure instanceof LinkageError fatal) {
                throw fatal;
            }
            // Instrumentation 不能改變登入結果；非致命的格式化／log sink 失敗不外拋。
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T)failure;
    }

    private MinidoracatLoginMetrics() {}
}
