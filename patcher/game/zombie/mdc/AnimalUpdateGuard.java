package zombie.mdc;

import java.nio.ByteBuffer;

import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.PacketTypes;

/**
 * W29：在伺服器接收入口驗證動物同步；異常整包拒絕，不讓 pooled packet 繼續處理。
 * 正常封包保留原版授權與派送，客戶端及 wire 實作不改。
 * 拒絕不拋普通例外，避免外層反作弊處罰與洗版；限頻紀錄失敗也不能放行。
 * 預設 enforce，{@code -Dmdc.animalUpdateGuard=0|off} 回原版；不對既有動物損失歸因。
 */
public final class AnimalUpdateGuard {

    /** {@link #inspect} 的判定碼；0＝接受，其餘為拒絕原因。 */
    static final int REASON_OK = 0;
    static final int REASON_SHORT_HEADER = 1;
    static final int REASON_DELETION = 2;
    static final int REASON_NEGATIVE_COUNT = 3;
    static final int REASON_ODD_TAIL = 4;
    static final int REASON_COUNT_MISMATCH = 5;

    /** 合法上行標頭：int 刪除數＋int requested 數。 */
    private static final int HEADER_BYTES = 2 * Integer.BYTES;

    private static final boolean ENFORCE = enforceFromProperty(System.getProperty("mdc.animalUpdateGuard"));

    private static final String TAG = "[MinidoracatJavaPatch][AnimalUpdateGuard] ";
    /** 拒絕紀錄限頻：每 60 秒窗最多 3 行，超限只累計 suppressed。 */
    private static final long WINDOW_NS = 60_000_000_000L;
    private static final int WINDOW_CAP = 3;

    private static final Object LOCK = new Object();
    private static long blocked;
    private static long suppressed;
    private static long logErrors;
    private static long windowStartNs;
    private static int windowCount;

    private AnimalUpdateGuard() {}

    /**
     * {@code GameServer.mainLoopDealWithNetData} 內
     * {@code PacketType.onServerPacket(ByteBufferReader, UdpConnection)} 的改道目標。
     * 形狀不符的動物同步上行封包整包丟棄（不委派、不拋例外）；其餘原樣委派，
     * vanilla 例外原樣穿透。
     */
    public static void onServerPacket(PacketTypes.PacketType type, ByteBufferReader reader, UdpConnection connection)
            throws Exception {
        if (ENFORCE && reader != null
                && (type == PacketTypes.PacketType.AnimalUpdateReliable
                        || type == PacketTypes.PacketType.AnimalUpdateUnreliable)) {
            ByteBuffer wire = reader.bb;
            if (wire != null) {
                int reason = inspect(wire);
                if (reason != REASON_OK) {
                    reject(type, connection, wire.remaining(), reason);
                    return;
                }
            }
        }
        type.onServerPacket(reader, connection);
    }

    /**
     * 純函式形狀檢查（絕對位置讀取、零配置、不改任何 buffer 狀態）。
     *
     * <p>接受條件全部成立：剩餘 ≥ 標頭長度；刪除數恰為 0（上行方向不得攜帶刪除）；
     * requested 數非負；ID 區剩餘為偶數 byte，且筆數與 requested 數精確吻合
     * （同時擋掉截斷與多餘尾 byte）。筆數以右移比對而非乘法，天然免疫溢位。
     */
    static int inspect(ByteBuffer wire) {
        int start = wire.position();
        int payload = wire.limit() - start;
        if (payload < HEADER_BYTES) {
            return REASON_SHORT_HEADER;
        }
        if (wire.getInt(start) != 0) {
            return REASON_DELETION;
        }
        int requested = wire.getInt(start + Integer.BYTES);
        if (requested < 0) {
            return REASON_NEGATIVE_COUNT;
        }
        int idBytes = payload - HEADER_BYTES;
        if ((idBytes & 1) != 0) {
            return REASON_ODD_TAIL;
        }
        return requested == idBytes >> 1 ? REASON_OK : REASON_COUNT_MISMATCH;
    }

    /** 只記安全欄位：型別名、連線 GUID、原因碼、payload 長度與三個計數；不記 raw payload／帳號。 */
    private static void reject(PacketTypes.PacketType type, UdpConnection connection, int payload, int reason) {
        synchronized (LOCK) {
            blocked++;
            try {
                long now = System.nanoTime();
                if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
                    windowStartNs = now;
                    windowCount = 0;
                }
                if (windowCount >= WINDOW_CAP) {
                    suppressed++;
                    return;
                }
                windowCount++;
                DebugType.General.warn(TAG + "dropped type=" + type.name()
                        + " guid=" + (connection == null ? "none" : String.valueOf(connection.getConnectedGUID()))
                        + " reason=" + reason
                        + " payload=" + payload
                        + " blocked=" + blocked
                        + " suppressed=" + suppressed
                        + " logErrors=" + logErrors + ".");
            } catch (RuntimeException failure) {
                // 紀錄失敗不得把已判定違規的封包放行；Error 刻意不接。
                logErrors++;
            }
        }
    }

    /** {@code 0|off} 回原版直通；null 與未知值一律 enforce。 */
    static boolean enforceFromProperty(String raw) {
        if (raw == null) {
            return true;
        }
        String value = raw.trim();
        return !("0".equals(value) || "off".equalsIgnoreCase(value));
    }
}
