package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * W58 SyncIsoObject 洪流觀測行為驗證（獨立 JVM；{@code ENABLED} 是 static final）。
 * 用法：{@code SyncIsoObjectProbeTest on|off}（off 需 {@code -Dmdc.syncIsoObjectProbe=off}，測試自驗）。
 *
 * <p>鎖：原方法每包恰一次、參數原樣、原版例外同一物件上拋；原版負對照（未改道的直接呼叫）與 off 不記帳不印；
 * 第 200 包不算洪流、第 201 包恰一行 flood（玩家／steamId／guid／物件／第一包物件占比）；同一連線 10 秒內的下一次
 * 洪流只計 suppressed，10 秒後再印並帶上 suppressed；beat 帶最大視窗與玩家、清掉 60 秒沒送封包的連線；
 * 簿記例外只計 anomalies、原方法照常呼叫。真 DebugLog 輸出導進 buffer 比對（同 MdcWorldSoundProbeTest）。
 */
public final class SyncIsoObjectProbeTest {
    private static final String TAG = "[MinidoracatJavaPatch][SyncIsoObjectProbe]";
    private static final long SEC = 1_000_000_000L;

    private static int failed;
    private static ByteArrayOutputStream logBuf;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        // DebugLog 格式化鏈在裸 JVM 的前置條件（同 MdcWorldSoundProbeTest）。
        zombie.core.random.RandStandard.INSTANCE.init();
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);

        expect("自驗：ENABLED 與 argv 相符（" + mode + "）", SyncIsoObjectProbe.ENABLED == "on".equals(mode));
        expect("parseEnabled：null/1/on 開，0/off/OFF 關", SyncIsoObjectProbe.parseEnabled(null)
                && SyncIsoObjectProbe.parseEnabled("1") && SyncIsoObjectProbe.parseEnabled("on")
                && !SyncIsoObjectProbe.parseEnabled("0") && !SyncIsoObjectProbe.parseEnabled(" off")
                && !SyncIsoObjectProbe.parseEnabled("OFF"));

        UdpConnection a = conn(1001L, "Player-A", 76561190000000001L);
        ByteBufferReader bb = alloc(ByteBufferReader.class);
        TestObject door = object(10, 20, 0, "fixtures_doors_01_0");

        // 原版負對照：未改道的 receiveSyncIsoObject 直接呼叫原方法——不經本類、不記帳、不印。
        for (int i = 0; i < 300; i++) {
            door.syncIsoObject(true, (byte) 1, a, bb);
        }
        expect("原版負對照：直接呼叫 300 次，原方法 300 次、本類 calls=0、零 log 行",
                door.calls == 300 && SyncIsoObjectProbe.callsForTest() == 0L && rows().isEmpty());

        if ("on".equals(mode)) {
            runOn(a, bb, door);
        } else {
            runOff(a, bb, door);
        }

        for (String row : rows()) {
            System.out.println("log  " + row.substring(row.indexOf(TAG)));
        }
        if (failed > 0) {
            System.out.println("sync-iso-object-probe FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("sync-iso-object-probe OK mode=" + mode);
    }

    private static void runOn(UdpConnection a, ByteBufferReader bb, TestObject door) throws Exception {
        TestObject window = object(11, 20, 0, null);
        door.calls = 0;

        // 暖機：首次生效行在另一條連線上印，不讓 DebugLog 初始化時間吃進 A 的視窗。
        UdpConnection warm = conn(9L, "Tester-A", 1L);
        SyncIsoObjectProbe.syncIsoObject(door, true, (byte) 0, warm, bb);
        expect("首次呼叫印首次生效行（含 kill switch 名稱）",
                rowsContaining("首次生效").size() == 1 && rowsContaining("-Dmdc.syncIsoObjectProbe=0|off").size() == 1);

        // A：前 150 包同一扇門，接著 50 包另一個物件 → 200 包，未超過門檻。
        for (int i = 0; i < 150; i++) {
            SyncIsoObjectProbe.syncIsoObject(door, true, (byte) 1, a, bb);
        }
        for (int i = 0; i < 50; i++) {
            SyncIsoObjectProbe.syncIsoObject(window, true, (byte) 2, a, bb);
        }
        expect("200 包：原方法每包恰一次、參數原樣、不算洪流",
                door.calls == 151 && window.calls == 50 && window.lastState == 2 && window.lastConn == a
                        && window.lastBb == bb && window.lastRemote && SyncIsoObjectProbe.floodsForTest() == 0L
                        && rowsContaining("flood#").isEmpty());
        SyncIsoObjectProbe.syncIsoObject(window, true, (byte) 2, a, bb);
        List<String> floods = rowsContaining("flood#");
        expect("第 201 包：恰一行 flood", SyncIsoObjectProbe.floodsForTest() == 1L
                && SyncIsoObjectProbe.loggedForTest() == 1L && floods.size() == 1);
        String row = floods.isEmpty() ? "" : floods.get(0);
        expect("flood 行帶玩家、steamId、guid、包數、經過毫秒與速率",
                row.contains("flood#1 player=Player-A steamId=76561190000000001 guid=1001 packets=201 windowMs=")
                        && row.contains("/s obj="));
        expect("flood 行帶這一包物件（class、sprite=null 印 ?、座標）與 state",
                row.contains("obj=TestObject sprite=? at=11,20,0 state=2"));
        expect("flood 行帶視窗第一包物件與它占幾包（150/201），suppressed=0",
                row.contains("first=TestObject sprite=fixtures_doors_01_0 at=10,20,0 sameAsFirst=150/201 suppressed=0"));
        for (int i = 0; i < 100; i++) {
            SyncIsoObjectProbe.syncIsoObject(door, true, (byte) 1, a, bb);
        }
        expect("同一視窗超過門檻後不重複計洪流", SyncIsoObjectProbe.floodsForTest() == 1L);

        // 合成時間：從現在起算，跨視窗與 10 秒限頻（record 是 syncIsoObject 內唯一的簿記入口）。
        long t0 = System.nanoTime() + 2 * SEC;
        burst(a, door, t0, 201);
        expect("下一個視窗再次洪流（10 秒內）：只計 suppressed、不印",
                SyncIsoObjectProbe.floodsForTest() == 2L && SyncIsoObjectProbe.suppressedForTest() == 1L
                        && rowsContaining("flood#").size() == 1);
        burst(a, door, t0 + 12 * SEC, 201);
        List<String> later = rowsContaining("flood#3 ");
        expect("10 秒後再洪流：印第二行並帶上這段期間 suppressed=1",
                SyncIsoObjectProbe.loggedForTest() == 2L && later.size() == 1
                        && later.get(0).contains("sameAsFirst=201/201 suppressed=1"));

        UdpConnection b = conn(1002L, "Player-B", 76561190000000002L);
        burst(b, door, t0 + 13 * SEC, SyncIsoObjectProbe.THRESHOLD);
        expect("另一條連線 200 包：不算洪流，連線各自計數", SyncIsoObjectProbe.floodsForTest() == 3L
                && SyncIsoObjectProbe.trackedForTest() == 3);

        // beat：5 分鐘後第一包觸發，60 秒沒送封包的連線（暖機、A、B）全清掉，再登記這一包的連線。
        UdpConnection c = conn(1003L, "Player-C", 76561190000000003L);
        SyncIsoObjectProbe.record(door, (byte) 1, c, t0 + 400 * SEC);
        List<String> beats = rowsContaining("SyncIsoObjectProbe] beat ");
        expect("beat 一行：calls／floods／logged／suppressed／最大視窗與玩家／追蹤數／anomalies",
                beats.size() == 1 && beats.get(0).contains(" floods=3 logged=2 suppressed=1 maxWindow=301 maxPlayer=Player-A tracked=0 anomalies=0"));
        expect("beat 清掉閒置連線後只剩這一包的連線", SyncIsoObjectProbe.trackedForTest() == 1);
        SyncIsoObjectProbe.record(door, (byte) 1, c, t0 + 401 * SEC);
        expect("beat 後同一期間不重複印", rowsContaining("SyncIsoObjectProbe] beat ").size() == 1);

        // 簿記例外：log 時讀 sprite 名稱拋出 → 只計 anomalies，原方法照常呼叫、不外拋。
        TestObject broken = object(1, 1, 0, null);
        broken.throwOnSprite = true;
        UdpConnection d = conn(1004L, "Player-D", 4L);
        for (int i = 0; i < 201; i++) {
            SyncIsoObjectProbe.syncIsoObject(broken, true, (byte) 1, d, bb);
        }
        expect("簿記例外：anomalies=1、原方法 201 次、沒有 flood 行",
                SyncIsoObjectProbe.anomaliesForTest() == 1L && broken.calls == 201
                        && rowsContaining("player=Player-D").isEmpty());

        // 原版例外：原方法拋出的例外原樣上拋（同一物件），計數已記。
        long before = SyncIsoObjectProbe.callsForTest();
        RuntimeException vanilla = new IllegalStateException("vanilla");
        door.toThrow = vanilla;
        Throwable got = catching(door, a, bb);
        door.toThrow = null;
        expect("原版例外同一物件上拋、calls 照記、不算 anomalies",
                got == vanilla && SyncIsoObjectProbe.callsForTest() == before + 1
                        && SyncIsoObjectProbe.anomaliesForTest() == 1L);

        // connection 為 null：只計 calls，原方法照常呼叫。
        int doorCalls = door.calls;
        SyncIsoObjectProbe.syncIsoObject(door, true, (byte) 1, null, bb);
        expect("connection=null：原方法照常呼叫、不追蹤、不算 anomalies",
                door.calls == doorCalls + 1 && SyncIsoObjectProbe.anomaliesForTest() == 1L);
    }

    private static void runOff(UdpConnection a, ByteBufferReader bb, TestObject door) {
        door.calls = 0;
        for (int i = 0; i < 500; i++) {
            SyncIsoObjectProbe.syncIsoObject(door, true, (byte) 1, a, bb);
        }
        expect("off：原方法 500 次、calls=0、不追蹤連線、零 log 行",
                door.calls == 500 && door.lastConn == a && SyncIsoObjectProbe.callsForTest() == 0L
                        && SyncIsoObjectProbe.trackedForTest() == 0 && rows().isEmpty());
        door.toThrow = new IllegalStateException("vanilla");
        Throwable got = catching(door, a, bb);
        expect("off：原版例外同一物件上拋", got == door.toThrow);
    }

    private static void burst(UdpConnection conn, TestObject obj, long start, int n) {
        for (int i = 0; i < n; i++) {
            SyncIsoObjectProbe.record(obj, (byte) 1, conn, start + i * 1_000_000L);
        }
    }

    private static Throwable catching(TestObject obj, UdpConnection conn, ByteBufferReader bb) {
        try {
            SyncIsoObjectProbe.syncIsoObject(obj, true, (byte) 1, conn, bb);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** 替身：只覆寫 syncIsoObject（計數、記參數、可拋出）與 getSpriteName（可拋出）。 */
    public static class TestObject extends IsoObject {
        int calls;
        boolean lastRemote;
        byte lastState;
        UdpConnection lastConn;
        ByteBufferReader lastBb;
        RuntimeException toThrow;
        boolean throwOnSprite;

        @Override
        public void syncIsoObject(boolean bRemote, byte val, UdpConnection source, ByteBufferReader bb) {
            calls++;
            lastRemote = bRemote;
            lastState = val;
            lastConn = source;
            lastBb = bb;
            if (toThrow != null) {
                throw toThrow;
            }
        }

        @Override
        public String getSpriteName() {
            if (throwOnSprite) {
                throw new IllegalStateException("sprite");
            }
            return spriteName;
        }
    }

    private static TestObject object(int x, int y, int z, String sprite) throws Exception {
        IsoGridSquare sq = alloc(IsoGridSquare.class);
        sq.x = x;
        sq.y = y;
        sq.z = z;
        TestObject o = alloc(TestObject.class);
        o.square = sq;
        o.spriteName = sprite;
        return o;
    }

    private static UdpConnection conn(long guid, String name, long steamId) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        Field f = UdpConnection.class.getDeclaredField("connectedGuid");
        f.setAccessible(true);
        f.setLong(c, guid);
        c.setUserName(name);
        c.setSteamId(steamId);
        return c;
    }

    @SuppressWarnings({"unchecked", "deprecation", "removal"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static List<String> rows() {
        List<String> out = new ArrayList<>();
        for (String line : logBuf.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> rowsContaining(String token) {
        List<String> out = new ArrayList<>();
        for (String row : rows()) {
            if (row.contains(token)) {
                out.add(row);
            }
        }
        return out;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private SyncIsoObjectProbeTest() {
    }
}
