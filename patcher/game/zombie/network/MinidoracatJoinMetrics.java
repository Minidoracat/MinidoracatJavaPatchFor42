package zombie.network;

import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.packets.character.CreatePlayerPacket;
import zombie.savefile.ServerPlayerDB;

/**
 * CreatePlayerPacket.processServer（join／死亡重生換角）的窄範圍計時 wrapper。
 *
 * <p>正式服 join 時主迴圈實測 6–11 秒停頓；本 wrapper 只量測並委派 processServer 尾段
 * 四個重活（OnNewGame Lua event、ServerPlayerDB 兩個呼叫、封包序列化），不改呼叫順序、
 * 參數與例外邊界。IsoPlayer 建構子無法以 redirect 包（INVOKESPECIAL &lt;init&gt; 的
 * 未初始化物件不可傳入 helper）——殘差時間＝ctor＋spawn 邏輯，由四項量測反推。</p>
 */
@SuppressWarnings("removal")
public final class MinidoracatJoinMetrics {

    private static final String TRIGGER_ON_NEW_GAME = "TRIGGER_ON_NEW_GAME";
    private static final String DB_UPDATE_CHARACTER = "DB_UPDATE_CHARACTER";
    private static final String DB_PROCESS = "DB_PROCESS";
    private static final String WRITE_PACKET = "WRITE_PACKET";
    private static final String REJOIN_TOTAL = "REJOIN_TOTAL";
    private static final String REJOIN_LOAD_CHARACTER = "REJOIN_LOAD_CHARACTER";

    public static void triggerEvent(String event, Object param1, Object param2) {
        long start = System.nanoTime();
        long elapsedNs;
        Throwable failure = null;
        try {
            LuaEventManager.triggerEvent(event, param1, param2);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(TRIGGER_ON_NEW_GAME, elapsedNs);
        if (failure != null) {
            MinidoracatJoinMetrics.<RuntimeException>rethrow(failure);
        }
    }

    public static void serverUpdateNetworkCharacter(
            ServerPlayerDB db, IsoPlayer player, int playerIndex, UdpConnection connection) {
        long start = System.nanoTime();
        long elapsedNs;
        Throwable failure = null;
        try {
            db.serverUpdateNetworkCharacter(player, playerIndex, connection);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(DB_UPDATE_CHARACTER, elapsedNs);
        if (failure != null) {
            MinidoracatJoinMetrics.<RuntimeException>rethrow(failure);
        }
    }

    public static void process(ServerPlayerDB db) {
        long start = System.nanoTime();
        long elapsedNs;
        Throwable failure = null;
        try {
            db.process();
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(DB_PROCESS, elapsedNs);
        if (failure != null) {
            MinidoracatJoinMetrics.<RuntimeException>rethrow(failure);
        }
    }

    public static void write(CreatePlayerPacket packet, ByteBufferWriter b) {
        long start = System.nanoTime();
        long elapsedNs;
        Throwable failure = null;
        try {
            packet.write(b);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(WRITE_PACKET, elapsedNs);
        if (failure != null) {
            MinidoracatJoinMetrics.<RuntimeException>rethrow(failure);
        }
    }

    public static void receivePlayerConnect(ByteBufferReader bb, IConnection connection, String username) {
        long start = System.nanoTime();
        long elapsedNs;
        Throwable failure = null;
        try {
            GameServer.receivePlayerConnect(bb, connection, username);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(REJOIN_TOTAL, elapsedNs);
        if (failure != null) {
            MinidoracatJoinMetrics.<RuntimeException>rethrow(failure);
        }
    }

    public static IsoPlayer serverLoadNetworkCharacter(ServerPlayerDB db, int playerIndex, String idStr) {
        long start = System.nanoTime();
        long elapsedNs;
        IsoPlayer result = null;
        Throwable failure = null;
        try {
            result = db.serverLoadNetworkCharacter(playerIndex, idStr);
            elapsedNs = System.nanoTime() - start;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable nonfatal) {
            elapsedNs = System.nanoTime() - start;
            failure = nonfatal;
        }

        safeLog(REJOIN_LOAD_CHARACTER, elapsedNs);
        if (failure != null) {
            MinidoracatJoinMetrics.<RuntimeException>rethrow(failure);
        }
        return result;
    }

    private static void safeLog(String operation, long elapsedNs) {
        try {
            DebugType.Multiplayer.println(
                    "[MinidoracatJavaPatch][JoinMetrics] op=" + operation + " elapsedNs=" + elapsedNs);
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
            // Instrumentation 不能改變 join 結果；非致命的 log sink 失敗不外拋。
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T)failure;
    }

    private MinidoracatJoinMetrics() {}
}
