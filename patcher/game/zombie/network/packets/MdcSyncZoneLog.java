package zombie.network.packets;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.mdc.ZoneSyncGuard;
import zombie.network.GameServer;
import zombie.network.PacketTypes;

/**
 * W66 畜牧區同步修補之一：記錄玩家送來的畜牧區新增、改名與刪除（2026-10-18；docs/patches.md 2cb）。
 *
 * <p>原版伺服器收到 {@code SyncZone} 就照 id 新增、更新或刪除畜牧區（刪除時連同所有相連的一起刪），沒有權限檢查，
 * 也不留任何紀錄，玩家回報「畜牧區不見了」時查不到是誰按的。{@code SyncZonePacket.processServer} 只在找到畜牧區時才
 * 轉發並處理，轉發（{@code sendToClients}）在刪除之前，所以改道它就拿得到送封包的連線，也能在刪除前列出接著會被
 * {@code DesignationZoneAnimal.removeZone} 連帶刪掉的整組（同一個 {@code getAllDZones}）。新增與改名靠
 * {@code parse} 內唯一 {@code DesignationZone.load} 改道到 {@link #load} 分辨：載入前先看這個 id 是否已存在，存在就是改名
 * （原版會覆寫名稱與寬高，這裡先記下舊值）。轉發與刪除照原版，本類只多寫一行 log。
 *
 * <p>每分鐘最多 {@value #LINES_PER_MINUTE} 行，超過的只計數，下一行補上筆數。kill switch {@code -Dmdc.zoneSyncLog=0|off}
 * （載入補回另由 {@code -Dmdc.zoneLoadRepair} 控制）。
 */
public final class MdcSyncZoneLog {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.zoneSyncLog"));
    static final int LINES_PER_MINUTE = 30;
    private static final String TAG = "[MinidoracatJavaPatch][ZoneSync] ";

    /** 最近一次 {@link #load} 的結果（主執行緒：parse 與 processServer 在同一次封包處理內）。 */
    private static DesignationZone lastZone;
    private static boolean lastExisted;
    private static String lastOldName;
    private static int lastOldW;
    private static int lastOldH;

    private static long windowStartMs;
    private static int linesInWindow;
    private static int suppressed;

    private MdcSyncZoneLog() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** 取代 {@code SyncZonePacket.parse} 內唯一的 {@code DesignationZone.load}：記下載入前的狀態，再照原版載入（含 W66 之三的補回）。 */
    public static DesignationZone load(ByteBuffer input, int worldVersion) {
        DesignationZoneAnimal existing = null;
        if (ENABLED && input.remaining() >= 8) {
            try {
                existing = DesignationZoneAnimal.getZoneById(input.getDouble(input.position()));
            } catch (RuntimeException ignored) {
                // 偷看失敗只影響「新增／改名」的判讀
            }
        }
        String oldName = existing == null ? null : existing.getName();
        int oldW = existing == null ? 0 : existing.getW();
        int oldH = existing == null ? 0 : existing.getH();
        DesignationZone zone = ZoneSyncGuard.load(input, worldVersion);
        lastZone = zone;
        lastExisted = existing != null;
        lastOldName = oldName;
        lastOldW = oldW;
        lastOldH = oldH;
        return zone;
    }

    /** 取代 {@code SyncZonePacket.processServer} 內唯一的 {@code this.sendToClients(type, connection)}：先記錄，再照原版轉發。 */
    public static void sendToClients(SyncZonePacket packet, PacketTypes.PacketType type, UdpConnection connection) {
        if (ENABLED) {
            try {
                String line = describe(packet, connection);
                if (line != null) {
                    emit(line, System.currentTimeMillis());
                }
            } catch (RuntimeException ignored) {
                // 紀錄失敗不得影響轉發與刪除
            }
        }
        packet.sendToClients(type, connection);
    }

    static String describe(SyncZonePacket packet, UdpConnection connection) {
        DesignationZone zone = packet.designationZone;
        if (zone == null) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        if (packet.zoneAdded) {
            if (zone == lastZone && lastExisted) {
                s.append("改名 zone=").append(id(zone)).append(" \"").append(lastOldName).append("\"→\"")
                        .append(zone.getName()).append('"');
                if (lastOldW != zone.getW() || lastOldH != zone.getH()) {
                    s.append(' ').append(lastOldW).append('x').append(lastOldH).append('→')
                            .append(zone.getW()).append('x').append(zone.getH());
                }
                s.append(' ').append(zone.getX()).append(',').append(zone.getY()).append(',').append(zone.getZ());
            } else {
                s.append("新增 ");
                rect(s, zone);
            }
        } else {
            s.append("移除 ");
            rect(s, zone);
            if (zone instanceof DesignationZoneAnimal animalZone) {
                ArrayList<DesignationZoneAnimal> group = DesignationZoneAnimal.getAllDZones(null, animalZone, null);
                int others = 0;
                StringBuilder list = new StringBuilder();
                for (DesignationZoneAnimal member : group) {
                    if (member != zone) {
                        list.append(others == 0 ? "" : "; ");
                        rect(list, member);
                        others++;
                    }
                }
                s.append(" 連帶 ").append(others).append(" 塊");
                if (others > 0) {
                    s.append(" [").append(list).append(']');
                }
            }
        }
        s.append(" by=").append(who(connection));
        return s.toString();
    }

    private static void rect(StringBuilder s, DesignationZone zone) {
        s.append("zone=").append(id(zone)).append(" \"").append(zone.getName()).append("\" ")
                .append(zone.getX()).append(',').append(zone.getY()).append(',').append(zone.getZ()).append(' ')
                .append(zone.getW()).append('x').append(zone.getH());
    }

    private static long id(DesignationZone zone) {
        Double id = zone.getId();
        return id == null ? -1L : id.longValue();
    }

    private static String who(UdpConnection connection) {
        if (connection == null) {
            return "?";
        }
        String name = connection.getUserName();
        IsoPlayer player = GameServer.getAnyPlayerFromConnection(connection);
        if (player == null) {
            return name;
        }
        return name + "@" + (int) player.getX() + "," + (int) player.getY() + "," + (int) player.getZ();
    }

    static void emit(String line, long nowMs) {
        if (nowMs - windowStartMs >= 60_000L) {
            windowStartMs = nowMs;
            linesInWindow = 0;
        }
        if (linesInWindow >= LINES_PER_MINUTE) {
            suppressed++;
            return;
        }
        linesInWindow++;
        String tail = suppressed > 0 ? "（之前另有 " + suppressed + " 筆超過每分鐘上限未印）" : "";
        suppressed = 0;
        DebugLog.log(TAG + line + tail);
    }
}
