package zombie.mdc;

import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoHutch;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;

/**
 * 僅過濾 IsoHutch.update 的兩個自發 sync；操作、remote relay 與存檔格式保持原版。
 * 收件判定（整窗寬、位置聯集、載具／noclip／teleport 豁免）在 {@link RecipientWindow}，
 * 與 W36 GameEntity 廣播共用。
 */
public final class HutchSyncGate {
    static final int OFF = 0;
    static final int ENFORCE = 1;
    static final int OBSERVE = 2;
    static final int MODE = parseMode();

    private static final String TAG = "[MinidoracatJavaPatch][HutchSync] ";
    private static final long BEAT_NS = 300_000_000_000L;

    // syncUpdate 在世界更新主緒。
    private static long calls, considered, sent, skipped, wouldSkip, sentBytes, wouldSkipBytes;
    private static long passthrough, exempt, scopeErrors, logErrors;
    private static long lastBeatNs;
    private static boolean announced;

    private HutchSyncGate() {}

    /** IsoHutch.update 內 sync() 的同形改道；不改原有 dirty/size/timer 更新順序。 */
    public static void syncUpdate(IsoHutch hutch) {
        if (MODE == OFF || !GameServer.server || GameClient.client
                || hutch.getClass() != IsoHutch.class) {
            hutch.sync();
            return;
        }
        if (RecipientWindow.disabled()) {
            hutch.sync();
            maybeBeat();
            return;
        }
        IsoGridSquare square = hutch.getSquare();
        if (square == null || hutch.getObjectIndex() == -1) {
            hutch.sync(); // 保留原版 diagnostic，無效物件不標記 hot-save。
            return;
        }
        calls++;
        float x = square.getX();
        float y = square.getY();
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            considered++;
            boolean eligible;
            try {
                eligible = shouldSend(connection, x, y);
            } catch (RuntimeException failure) {
                // 新增的範圍檢查失敗才回原廣播；序列化與送包例外不在 catch 裡。
                scopeErrors++;
                eligible = true;
            }
            if (!eligible) {
                wouldSkip++;
                if (MODE == ENFORCE) {
                    skipped++;
                    continue;
                }
            }
            ByteBufferWriter out = connection.startPacket();
            PacketTypes.PacketType.SyncIsoObject.doPacket(out);
            hutch.syncIsoObjectSend(out);
            int bytes = connection.getBufferPosition();
            PacketTypes.PacketType.SyncIsoObject.send(connection);
            sent++;
            sentBytes += bytes;
            if (!eligible) {
                wouldSkipBytes += bytes; // observe 真正寫出的 bytes，不外推未序列化的資料。
            }
        }
        // 原版即使沒有連線仍會走這裡；不能因所有收件人都被過濾而漏存。
        hutch.flagForHotSave();
        maybeBeat();
    }

    static boolean shouldSend(UdpConnection connection, float x, float y) {
        switch (RecipientWindow.verdict(connection, x, y)) {
            case RecipientWindow.SKIP:
                return false;
            case RecipientWindow.PASSTHROUGH:
                passthrough++;
                return true;
            case RecipientWindow.EXEMPT:
                exempt++;
                return true;
            default:
                return true;
        }
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        announced = true;
        lastBeatNs = now;
        try {
            DebugLog.log(TAG + "mode=" + MODE + " calls=" + calls + " considered=" + considered
                    + " sent=" + sent + " skipped=" + skipped + " wouldSkip=" + wouldSkip
                    + " sentBytes=" + sentBytes + " wouldSkipBytes=" + wouldSkipBytes
                    + " passthrough=" + passthrough + " exempt=" + exempt
                    + " scopeErrors=" + scopeErrors + " logErrors=" + logErrors
                    + " disabled=" + RecipientWindow.disabled());
        } catch (RuntimeException failure) {
            logErrors++;
        }
    }

    private static int parseMode() {
        String raw = System.getProperty("mdc.hutchSyncGate");
        if (raw == null) {
            return ENFORCE;
        }
        return switch (raw.trim()) {
            case "0", "off" -> OFF;
            case "1", "enforce" -> ENFORCE;
            default -> OBSERVE;
        };
    }
}
