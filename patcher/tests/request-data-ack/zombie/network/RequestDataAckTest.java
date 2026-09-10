package zombie.network;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

import sun.misc.Unsafe;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.network.packets.RequestDataPacket.RequestID;

/** 真 RequestDataManager／封包序列化；只替換 RakNet 送出端，不載入 native 網路。 */
public final class RequestDataAckTest {
    private static final Unsafe UNSAFE;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        GameServer.server = true;
        Capture sender = connection(41), stranger = connection(42);
        UdpEngine engine = (UdpEngine) UNSAFE.allocateInstance(UdpEngine.class);
        Field connections = UdpEngine.class.getDeclaredField("connectionMap");
        connections.setAccessible(true);
        var map = new HashMap<Long, UdpConnection>();
        map.put(sender.getConnectedGUID(), sender);
        map.put(stranger.getConnectedGUID(), stranger);
        connections.set(engine, map);
        GameServer.udpEngine = engine;
        RequestDataManager manager = RequestDataManager.getInstance();
        manager.clear();
        try {
            manager.ACKWasReceived(RequestID.RadioData, sender, 0);
            require(sender.bytes.size() == 0, "empty queue sent data");

            byte[] payload = new byte[RequestDataManager.packSize + 17];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
            sender.total = payload.length;
            manager.putDataForTransmit(RequestID.RadioData, sender,
                    ByteBuffer.wrap(payload).position(payload.length));
            require(sender.bytes.size() == RequestDataManager.packSize, "initial transmit window changed");

            manager.ACKWasReceived(RequestID.RadioData, stranger, 0);
            manager.ACKWasReceived(RequestID.WorldMap, sender, 0);
            require(stranger.bytes.size() == 0 && sender.bytes.size() == RequestDataManager.packSize,
                    "foreign or wrong-type ACK consumed the pending request");

            RuntimeException failure = new IllegalStateException("transport failure");
            sender.failure = failure;
            try {
                manager.ACKWasReceived(RequestID.RadioData, sender, 0);
                throw new AssertionError("transport failure was swallowed");
            } catch (RuntimeException e) {
                require(e == failure, "transport failure was replaced");
            }
            sender.failure = null;
            manager.ACKWasReceived(RequestID.RadioData, sender, 0);
            require(Arrays.equals(payload, sender.bytes.toByteArray()), "matching ACK did not finish exact payload");
            int completedPackets = sender.packets;
            manager.ACKWasReceived(RequestID.RadioData, sender, 0);
            require(sender.packets == completedPackets, "duplicate ACK resent completed data");
            System.out.println("PASS RequestData ACK: empty/missing/type/duplicate boundaries, exact transfer, original send failure");
        } finally { manager.clear(); }
    }

    private static Capture connection(long guid) throws Exception {
        Capture connection = (Capture) UNSAFE.allocateInstance(Capture.class);
        Field field = UdpConnection.class.getDeclaredField("connectedGuid");
        field.setAccessible(true);
        field.setLong(connection, guid);
        connection.buffer = ByteBuffer.allocate(2048);
        connection.writer = new ByteBufferWriter(connection.buffer);
        connection.bytes = new ByteArrayOutputStream();
        return connection;
    }

    private static final class Capture extends UdpConnection {
        ByteBuffer buffer;
        ByteBufferWriter writer;
        ByteArrayOutputStream bytes;
        RuntimeException failure;
        int total, packets;
        private Capture() { super(null, 0L, 0); }
        @Override public ByteBufferWriter startPacket() { writer.clear(); return writer; }
        @Override public int getBufferPosition() { return buffer.position(); }
        @Override public void endPacket(int priority, int reliability, byte ordering) {
            if (failure != null) throw failure;
            buffer.flip();
            buffer.position(3); // PZ packet header; 以下驗真正的 RequestData payload。
            require(buffer.get() == 3 && buffer.get() == RequestID.RadioData.ordinal(), "unexpected packet kind/id");
            require(buffer.getInt() == total && buffer.getInt() == bytes.size(), "transfer size/offset changed");
            int length = buffer.getInt();
            require(length == buffer.remaining(), "part length disagrees with wire");
            bytes.write(buffer.array(), buffer.position(), length);
            packets++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
