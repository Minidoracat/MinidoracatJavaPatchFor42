package zombie.mdc;

import java.util.Arrays;

import zombie.core.MdcTimedActionProbe;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * W58 SyncIsoObject 洪流觀測（2026-10-11；docs/patches.md 2bv）。純觀測：原方法照常呼叫恰一次，回傳與例外都與原版相同。
 *
 * <p><b>原版</b>：{@code GameServer.receiveSyncIsoObject} 依封包座標與 index 找到物件後呼叫
 * {@code IsoObject.syncIsoObject(true, state, connection, bb)}，後者不分距離轉送給其他所有連線。
 * 單一連線 1 秒超過 {@code MaxPacketsPerSecond} 時 {@code PacketsCache.isLimitExceeded} 只印
 * {@code Packets limit has exceeded for SyncIsoObject}，不記是哪條連線，抓不到送的人與物件。
 *
 * <p><b>手術</b>：{@code receiveSyncIsoObject} 內唯一的 {@code syncIsoObject} 呼叫 1:1 改道本類（receiver 前置）。
 * 每條連線（connected GUID）記 1 秒視窗計數，視窗內第一次超過 {@link #THRESHOLD} 包時印一行 flood
 * （玩家、steamId、guid、包數與經過毫秒、這一包與視窗第一包的物件、第一包物件占幾包）；同一連線 10 秒最多一行，
 * 其餘計入 suppressed。每 5 分鐘一行 beat，並清掉 60 秒沒送封包的連線。
 *
 * <p>只在主執行緒（{@code mainLoopDealWithNetData}）被呼叫，計數不加鎖；熱路徑不配置物件（新連線與 log 除外）。
 * 簿記全部在 try 內，例外只計 {@code anomalies}；原方法在 try 外呼叫，原版例外原樣上拋。
 * kill switch {@code -Dmdc.syncIsoObjectProbe=0|off}：只呼叫原方法，不記帳也不印。
 */
public final class SyncIsoObjectProbe {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.syncIsoObjectProbe"));
    /** 1 秒視窗內超過此包數才算洪流。 */
    static final int THRESHOLD = 200;
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final long LOG_GAP_NS = 10_000_000_000L;
    private static final long IDLE_NS = 60_000_000_000L;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][SyncIsoObjectProbe] ";

    /** 一條連線的視窗狀態。 */
    private static final class Conn {
        final long guid;
        String name;
        long lastSeenNs;
        long windowStartNs;
        int count;
        IsoObject firstObj;
        int sameAsFirst;
        boolean everLogged;
        long lastLogNs;
        long suppressed;

        Conn(long guid, long now) {
            this.guid = guid;
            this.windowStartNs = now - WINDOW_NS;
        }
    }

    // ---- 主執行緒單寫 ----
    // ponytail: 線性掃描找連線，伺服器同時在線數十人以內夠用；上百條連線再換 long-key map
    private static Conn[] conns = new Conn[16];
    private static int connCount;
    private static long calls;
    private static long floods;
    private static long logged;
    private static long suppressed;
    private static long anomalies;
    private static int periodMax;
    private static String periodMaxName;
    private static long lastBeatNs;
    private static boolean announced;

    private SyncIsoObjectProbe() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** 取代 {@code GameServer.receiveSyncIsoObject} 內的 {@code obj.syncIsoObject(true, state, connection, bb)}。 */
    public static void syncIsoObject(IsoObject obj, boolean remote, byte state, UdpConnection connection,
            ByteBufferReader bb) {
        if (ENABLED) {
            try {
                record(obj, state, connection, System.nanoTime());
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        obj.syncIsoObject(remote, state, connection, bb);
    }

    static void record(IsoObject obj, byte state, UdpConnection connection, long now) {
        calls++;
        if (!announced || now - lastBeatNs >= BEAT_NS) {
            beat(now);
        }
        if (connection == null) {
            return;
        }
        Conn c = find(connection.getConnectedGUID(), now);
        c.lastSeenNs = now;
        c.name = connection.getUserName();
        if (now - c.windowStartNs >= WINDOW_NS) {
            c.windowStartNs = now;
            c.count = 0;
            c.firstObj = obj;
            c.sameAsFirst = 0;
        }
        c.count++;
        if (obj == c.firstObj) {
            c.sameAsFirst++;
        }
        if (c.count > periodMax) {
            periodMax = c.count;
            periodMaxName = c.name;
        }
        if (c.count != THRESHOLD + 1) {
            return;
        }
        floods++;
        if (c.everLogged && now - c.lastLogNs < LOG_GAP_NS) {
            c.suppressed++;
            suppressed++;
            return;
        }
        c.everLogged = true;
        c.lastLogNs = now;
        long ms = (now - c.windowStartNs) / 1_000_000L;
        String line = TAG + "flood#" + floods + " player=" + MdcTimedActionProbe.safeName(c.name)
                + " steamId=" + connection.getSteamId() + " guid=" + c.guid
                + " packets=" + c.count + " windowMs=" + ms + " rate=" + c.count * 1000L / Math.max(1L, ms) + "/s"
                + " obj=" + describe(obj) + " state=" + state
                + " first=" + describe(c.firstObj) + " sameAsFirst=" + c.sameAsFirst + "/" + c.count
                + " suppressed=" + c.suppressed;
        c.suppressed = 0L;
        logged++;
        DebugLog.log(line);
    }

    private static Conn find(long guid, long now) {
        for (int i = 0; i < connCount; i++) {
            if (conns[i].guid == guid) {
                return conns[i];
            }
        }
        if (connCount == conns.length) {
            conns = Arrays.copyOf(conns, connCount * 2);
        }
        Conn c = new Conn(guid, now);
        conns[connCount++] = c;
        return c;
    }

    private static String describe(IsoObject obj) {
        if (obj == null) {
            return "null";
        }
        IsoGridSquare sq = obj.square;
        return obj.getClass().getSimpleName() + " sprite=" + MdcTimedActionProbe.safeName(obj.getSpriteName())
                + " at=" + (sq == null ? "?" : sq.x + "," + sq.y + "," + sq.z);
    }

    /** 每 5 分鐘一行（首次呼叫立即一行）；先清掉 60 秒沒送封包的連線。 */
    private static void beat(long now) {
        String head = announced ? "beat " : "首次生效（純觀測；-Dmdc.syncIsoObjectProbe=0|off 停用）";
        announced = true;
        lastBeatNs = now;
        int kept = 0;
        for (int i = 0; i < connCount; i++) {
            if (now - conns[i].lastSeenNs <= IDLE_NS) {
                conns[kept++] = conns[i];
            }
        }
        Arrays.fill(conns, kept, connCount, null);
        connCount = kept;
        DebugLog.log(TAG + head + "calls=" + calls + " floods=" + floods + " logged=" + logged
                + " suppressed=" + suppressed + " maxWindow=" + periodMax
                + " maxPlayer=" + MdcTimedActionProbe.safeName(periodMaxName)
                + " tracked=" + connCount + " anomalies=" + anomalies);
        periodMax = 0;
        periodMaxName = null;
    }

    // ---- 測試存取器 ----
    static long callsForTest() {
        return calls;
    }

    static long floodsForTest() {
        return floods;
    }

    static long loggedForTest() {
        return logged;
    }

    static long suppressedForTest() {
        return suppressed;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static int trackedForTest() {
        return connCount;
    }
}
