package zombie.network;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * W79 {@code UpdateItemSprite} 距離檢查（SpriteUpdateGuard）行為驗證。走 dist 內手術後的真
 * {@code GameServer.receiveUpdateItemSprite}（同 package 直接呼叫）：真 wire（sprite id、名稱、x、y、z、物件索引），
 * 真 {@code IsoCell} 的伺服器分支轉給 {@code ServerMap.instance}，換成記錄 {@code getGridSquare} 呼叫的替身（回 null，原版之後什麼都不做）。
 * 「原版取格子」＝原版會去改那格的物件；沒取＝什麼都不改。argv：{@code on}／{@code off}，自驗旗標。
 */
public final class SpriteUpdateGuardTest {

    private static final String TAG = "[MinidoracatJavaPatch][SpriteUpdateGuard]";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream（擷取 log 時一起被收走）：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "on";
        boolean wantOn = !"off".equals(mode);
        expect("自驗：argv=" + mode + " 與實際旗標相符（" + flag() + "）", flag() == wantOn);
        GameServer.server = true;

        // IsoCell 是 final：用真 IsoCell（伺服器分支轉給 ServerMap.instance），把 ServerMap 換成記錄呼叫的子類別。
        RecordingMap map = alloc(RecordingMap.class);
        map.calls = new ArrayList<>();
        ServerMap.instance = map;
        IsoWorld.instance.currentCell = alloc(IsoCell.class);
        ByteArrayOutputStream log = captureLog();

        UdpConnection solo = conn(player((short) 3, "fixer", 100.5F, 200.5F, 0.0F));
        UdpConnection split = conn(player((short) 4, "host", 500.5F, 500.5F, 0.0F), player((short) 5, "couch", 100.5F, 200.5F, 0.0F));
        UdpConnection driver = conn(player((short) 6, "driver", 100.0F, 200.0F, 0.0F));

        // ---- 合法來源照原版 ----
        expectFetched(true, "搬運動作 scrap／repair：玩家身旁同一格", solo, 100, 200, 0);
        expectFetched(true, "撞圍籬：車旁沿圍籬（每軸差 20 格，邊界）", driver, 120, 180, 0);
        expectFetched(true, "樓層差 1", solo, 101, 201, 1);
        expectFetched(true, "同機多人：任一位在範圍內就放行", split, 101, 199, 0);

        // ---- 範圍外：on 不取格子（原版會改）----
        int refusedCases = 0;
        expectFetched(!wantOn, "x 差 21 格", solo, 122, 200, 0);
        refusedCases++;
        expectFetched(!wantOn, "遠處（地圖另一頭）", solo, 9000, 9000, 0);
        refusedCases++;
        expectFetched(!wantOn, "樓層差 2", solo, 100, 200, 2);
        refusedCases++;
        expectFetched(!wantOn, "同機多人都不在範圍內", split, 9000, 100, 0);
        refusedCases++;
        expectFetched(!wantOn, "連線上沒有玩家", conn(), 100, 200, 0);
        refusedCases++;
        long refused = refusedForTest();
        if (wantOn) {
            expect("W79 計數恰 +" + refusedCases, refused == refusedCases);
            expect("W79 log：refused 帶目標、連線玩家與位置",
                    rows(log).stream().anyMatch(r -> r.contains(TAG + " refused target=9000,9000,0 connectionPlayers=3:fixer"
                            + " at=100.5,200.5,0.0")));
        } else {
            expect("off：零計數、零 log", refused == 0 && rows(log).isEmpty());
        }
        expect("連線上下文取用即清", !boundForTest());
        expect("零 anomalies", anomaliesForTest() == 0);

        if (failed > 0) {
            OUT.println("sprite-update-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("sprite-update-guard OK mode=" + mode);
    }

    private static void expectFetched(boolean fetched, String what, UdpConnection connection, int x, int y, int z) {
        List<int[]> calls = ((RecordingMap) ServerMap.instance).calls;
        int before = calls.size();
        GameServer.receiveUpdateItemSprite(wire(x, y, z), connection, PacketTypes.PacketType.UpdateItemSprite.getId());
        boolean got = calls.size() == before + 1 && calls.get(before)[0] == x && calls.get(before)[1] == y && calls.get(before)[2] == z;
        boolean none = calls.size() == before;
        expect((fetched ? "照原版取格子：" : "不取格子（拒絕）：") + what, fetched ? got : none);
    }

    /** 客戶端 {@code IsoObject.transmitUpdatedSpriteToServer} 的 wire：sprite id、名稱、x、y、z、物件索引、附加圖數。 */
    private static ByteBufferReader wire(int x, int y, int z) {
        ByteBuffer bb = ByteBuffer.allocate(64);
        bb.putInt(12345);
        byte[] name = "fencing_01_8".getBytes(StandardCharsets.UTF_8);
        bb.putShort((short) name.length);
        bb.put(name);
        bb.putInt(x);
        bb.putInt(y);
        bb.putInt(z);
        bb.putInt(0);
        bb.put((byte) 0);
        bb.flip();
        return new ByteBufferReader(bb);
    }

    /** 原版取完格子是 null 就什麼都不做；這裡只記下被要了哪一格。 */
    private static final class RecordingMap extends ServerMap {
        List<int[]> calls;

        @Override
        public IsoGridSquare getGridSquare(int x, int y, int z) {
            calls.add(new int[]{x, y, z});
            return null;
        }
    }

    private static IsoPlayer player(short id, String name, float x, float y, float z) throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        p.onlineId = id;
        p.username = name;
        p.setX(x);
        p.setY(y);
        p.setZ(z);
        Field ecs = findField(p.getClass(), "ecsComponentMap");
        ecs.setAccessible(true);
        ecs.set(p, new HashMap<>());
        return p;
    }

    private static UdpConnection conn(IsoPlayer... players) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        c.players = new IsoPlayer[4];
        for (int i = 0; i < players.length; i++) {
            c.players[i] = players[i];
        }
        return c;
    }

    // SpriteUpdateGuard 在 zombie.mdc，測試存取器是 package-private：反射讀。
    private static boolean flag() throws Exception {
        Field f = guard().getDeclaredField("ENABLED");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static long refusedForTest() throws Exception {
        return (long) accessor("refusedForTest");
    }

    private static long anomaliesForTest() throws Exception {
        return (long) accessor("anomaliesForTest");
    }

    private static boolean boundForTest() throws Exception {
        return (boolean) accessor("boundForTest");
    }

    private static Object accessor(String name) throws Exception {
        Method m = guard().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    private static Class<?> guard() throws Exception {
        return Class.forName("zombie.mdc.SpriteUpdateGuard");
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    private static List<String> rows(ByteArrayOutputStream log) {
        List<String> out = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        OUT.println((ok ? "sug pass  " : "sug FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private SpriteUpdateGuardTest() {}
}
