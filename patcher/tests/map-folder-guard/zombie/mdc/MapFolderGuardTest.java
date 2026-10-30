package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.random.RandStandard;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.ChooseGameInfo;
import zombie.iso.IsoLot;
import zombie.iso.IsoMetaGrid;
import zombie.iso.MapFiles;
import zombie.network.ConnectionDetails;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * W87 {@code Map=} 列了不存在的地圖資料夾（獨立 JVM；{@code ENABLED} 是 static final）。
 * 用法：工作目錄必須是空的 {@code map-folder-test}（build.ps1 建），{@code MapFolderGuardTest on|off|vanilla}；
 * off 需 {@code -Dmdc.mapFolderGuard=off}，vanilla 的 classpath 不含 dist（jar 原版），測試自驗。
 *
 * <p>在工作目錄建 {@code media/maps}：Alpha（cell 0..2）、Bravo（cell 0..2 與 Alpha 重疊＋10..12 自己的）、
 * Zulu（一個 lotheader、沒有 map.info）。{@code ZomboidFileSystem.init()} 照開服的方式掃 {@code media/}，
 * 之後跑真 {@code IsoMetaGrid.CreateStep1}：Zulu 讀不到 map.info，{@code MapFiles.load} 在 {@code createLotHeader}
 * NPE，所以 CreateStep1 在 Alpha、Bravo 都載入完、建世界（WorldGenChunk、載入執行緒）之前停下，
 * {@code IsoLot.MapFiles} 留著給測試看。再用真私有方法 {@code higherPriority300x300CellExists} 問「Bravo 的建築會不會被
 * 略過」（MetaGridLoaderThread 與 consolidateBuildings 就是拿它決定）。伺服器跑完，用真
 * {@code ConnectionDetails.writeGameMap} 寫出要送給客戶端的清單，切成客戶端（{@code GameClient.client}）照
 * {@code ConnectToServerState.TestTCP} 的方式設好，再跑一次真 CreateStep1。
 *
 * <p>鎖：事故清單 {@code Alpha;W87Missing;Bravo;Zulu}：on 伺服器與客戶端的 priority 都是 0,1,2、Bravo 自己的 cell 不被略過、
 * 重疊的 cell 照原版讓 Alpha 優先、送出的清單沒有缺的那項、一行警告；off 與 jar 原版兩邊都是 0,2,3、Bravo 自己的 cell
 * 被略過（負對照）、清單原樣送出。都在：三種模式相同、清單不動。只剩一項：送出 {@code Zulu;}（留分號，客戶端仍走
 * 清單分支，沒有分號會改走 map.info 遞迴、拿到空清單）。全部不存在：照原版。
 */
public final class MapFolderGuardTest {
    private static final String TAG = "[MinidoracatJavaPatch][MapFolderGuard]";
    private static final String MISSING = "W87Missing";
    private static final String SKIP = "skipping non-existent map folder";

    private static int failed;
    private static boolean on;
    private static boolean vanilla;
    private static ByteArrayOutputStream logBuf;
    private static Method higher;
    private static Method writeGameMap;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        on = "on".equals(mode);
        vanilla = "vanilla".equals(mode);
        Path cwd = Path.of("").toAbsolutePath();
        if (!cwd.getFileName().toString().equals("map-folder-test")) {
            System.out.println("map-folder-guard FAIL 工作目錄要是 map-folder-test（現在 " + cwd + "）");
            System.exit(2);
        }
        String src = String.valueOf(IsoMetaGrid.class.getProtectionDomain().getCodeSource().getLocation());
        expect("自驗：IsoMetaGrid 來自 " + (vanilla ? "jar 原版" : "dist") + "（" + src + "）",
                vanilla == src.endsWith(".jar"));
        if (!vanilla) {
            expect("自驗：ENABLED 與 argv 相符（" + mode + "）", MapFolderGuard.ENABLED == on);
            expect("parseEnabled：null/1/on 開，0/off/OFF 關", MapFolderGuard.parseEnabled(null)
                    && MapFolderGuard.parseEnabled("1") && MapFolderGuard.parseEnabled("on")
                    && !MapFolderGuard.parseEnabled("0") && !MapFolderGuard.parseEnabled(" off")
                    && !MapFolderGuard.parseEnabled("OFF"));
        }

        setUp(cwd);
        incident();
        allPresent();
        oneLeft();
        if (!vanilla) {
            allMissing();
        }

        if (failed > 0) {
            System.out.println("map-folder-guard FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("map-folder-guard OK mode=" + mode);
    }

    /** 事故：缺的資料夾排在 Bravo、Zulu 前面。 */
    private static void incident() throws Exception {
        String list = "Alpha;" + MISSING + ";Bravo;Zulu";
        Round s = serverRound(list);
        List<Integer> fixed = List.of(0, 1, 2);
        List<Integer> broken = List.of(0, 2, 3);
        expect("事故：伺服器 IsoLot.MapFiles 是 Alpha、Bravo、Zulu", s.names.equals(List.of("Alpha", "Bravo", "Zulu")));
        expect(on ? "事故 on：伺服器 priority＝IsoLot.MapFiles 索引 0,1,2" : "事故（原版）：伺服器 priority 是 0,2,3（缺的資料夾佔一號）",
                s.priorities.equals(on ? fixed : broken));
        expect(on ? "事故 on：Bravo 自己的 cell（300 格 9,9）不被略過" : "事故（原版）：Bravo 自己的 cell 被當成有更高優先的地圖而略過（比到自己）",
                s.bravoOwn == !on);
        expect("事故：Bravo 與 Alpha 重疊的 cell 照原版讓 Alpha 優先", s.bravoOverlap);
        expect(on ? "事故 on：GameServer.gameMap 換成 Alpha;Bravo;Zulu，Core.gameMap 跟著換" : "事故（原版）：GameServer.gameMap 與 Core.gameMap 不動",
                on ? "Alpha;Bravo;Zulu".equals(s.gameMap) && s.gameMap.equals(s.coreMap)
                        : s.gameMapSame && list.equals(s.coreMap));
        expect("事故：送給客戶端的就是 GameServer.gameMap", s.gameMap.equals(s.sent));
        List<String> rows = s.rows;
        if (on) {
            expect("事故 on：一行警告，列出缺的資料夾、剩幾張、幾張排在後面；沒有原版的 skipping 行",
                    rows.size() == 1 && rows.get(0).contains("media/maps/" + MISSING + "/")
                            && rows.get(0).contains("已從伺服器與送給客戶端的地圖清單拿掉") && rows.get(0).contains("其餘 3 張")
                            && rows.get(0).contains("其中 2 張排在缺的資料夾後面") && !s.log.contains(SKIP));
        } else if (vanilla) {
            expect("事故（jar 原版）：零 MapFolderGuard 行，有原版的 skipping 行", rows.isEmpty() && s.log.contains(SKIP));
        } else {
            expect("事故 off：一行警告寫明照原版保留、2 張地圖建築不會載入；原版的 skipping 行照印",
                    rows.size() == 1 && rows.get(0).contains("media/maps/" + MISSING + "/")
                            && rows.get(0).contains("-Dmdc.mapFolderGuard=off") && rows.get(0).contains("排在它後面的 2 張")
                            && s.log.contains(SKIP));
        }
        Round c = clientRound(s.sent);
        expect("事故：客戶端照收到的清單算出和伺服器相同的地圖與 priority（" + c.names + " " + c.priorities + "）",
                c.names.equals(s.names) && c.priorities.equals(s.priorities));
        expect("事故：客戶端判 Bravo 自己的 cell 與伺服器相同（" + (on ? "不略過" : "略過") + "）", c.bravoOwn == s.bravoOwn);
        expect("事故：客戶端零 MapFolderGuard 行（只在伺服器動作）", c.rows.isEmpty());
    }

    /** 都在：三種模式相同，清單不動。 */
    private static void allPresent() throws Exception {
        String list = "Alpha;Bravo;Zulu";
        Round s = serverRound(list);
        expect("都在：priority 0,1,2、Bravo 自己的 cell 不略過、GameServer.gameMap 不動",
                s.priorities.equals(List.of(0, 1, 2)) && !s.bravoOwn && s.gameMapSame);
        expect(vanilla ? "都在（jar 原版）：零 MapFolderGuard 行" : "都在：一行「3 個地圖資料夾都在」",
                vanilla ? s.rows.isEmpty() : s.rows.size() == 1 && s.rows.get(0).contains("Map= 的 3 個地圖資料夾都在"));
    }

    /** 只剩一項：on 送出帶分號的 Zulu;，客戶端仍走清單分支。 */
    private static void oneLeft() throws Exception {
        String list = MISSING + ";Zulu";
        Round s = serverRound(list);
        expect(on ? "只剩一項 on：送出 Zulu;（留分號），priority 0" : "只剩一項（原版）：清單不動，priority 1",
                on ? "Zulu;".equals(s.sent) && s.priorities.equals(List.of(0))
                        : s.gameMapSame && s.priorities.equals(List.of(1)));
        Round c = clientRound(s.sent);
        expect("只剩一項：客戶端拿到 Zulu、priority 與伺服器相同（" + c.names + " " + c.priorities + "）",
                c.names.equals(List.of("Zulu")) && c.priorities.equals(s.priorities));
        expect("只剩一項：客戶端零 MapFolderGuard 行", c.rows.isEmpty());
    }

    /** 全部不存在：沒有剩下的地圖可給，照原版（直接呼叫改道目標）。 */
    private static void allMissing() throws Exception {
        String list = MISSING + ";W87Gone";
        GameServer.server = true;
        GameClient.client = false;
        GameServer.gameMap = list;
        Core.gameMap = MISSING;
        logBuf.reset();
        ArrayList<String> got = MapFolderGuard.getLotDirectories(new IsoMetaGrid());
        List<String> rows = rows();
        expect("全部不存在：回原版清單、GameServer.gameMap 不動、一行「照原版」",
                got.equals(List.of(MISSING, "W87Gone")) && GameServer.gameMap == list
                        && rows.size() == 1 && rows.get(0).contains("沒有任何存在的地圖資料夾，照原版"));
    }

    private static Round serverRound(String list) throws Exception {
        GameServer.server = true;
        GameClient.client = false;
        GameServer.gameMap = list;
        Core.gameMap = list.split(";")[0].trim();   // GameServer.main：Core.gameMap＝第一項
        logBuf.reset();
        Round r = create("伺服器 " + list);
        r.gameMapSame = GameServer.gameMap == list;
        r.gameMap = GameServer.gameMap;
        r.coreMap = Core.gameMap;
        ByteBuffer bb = ByteBuffer.allocate(4096);
        writeGameMap.invoke(null, new ByteBufferWriter(bb));
        bb.flip();
        r.sent = new ByteBufferReader(bb).getUTF();
        return r;
    }

    /** 照 ConnectToServerState.TestTCP 設客戶端狀態，跑真 CreateStep1。 */
    private static Round clientRound(String sent) throws Exception {
        GameServer.server = false;
        GameClient.client = true;
        GameClient.gameMap = sent;
        Core.gameMap = sent.contains(";") ? sent.split(";")[0].trim() : sent.trim();
        logBuf.reset();
        try {
            return create("客戶端 " + sent);
        } finally {
            GameClient.client = false;
            GameServer.server = true;
        }
    }

    private static Round create(String label) throws Exception {
        IsoMetaGrid grid = new IsoMetaGrid();
        Throwable t = null;
        try {
            grid.CreateStep1();
        } catch (Throwable e) {
            t = e;
        }
        boolean atSentinel = t instanceof NullPointerException && Stream.of(t.getStackTrace()).anyMatch(
                e -> e.getClassName().equals("zombie.iso.MapFiles") && e.getMethodName().equals("createLotHeader"));
        expect(label + "：CreateStep1 在 Zulu 載入時停下（載完前面的地圖、建世界之前）", atSentinel);
        if (!atSentinel && t != null) {
            t.printStackTrace(System.out);
        }
        Round r = new Round();
        MapFiles bravo = null;
        for (MapFiles mf : IsoLot.MapFiles) {
            r.names.add(mf.mapDirectoryName);
            r.priorities.add(mf.priority);
            if (mf.mapDirectoryName.equals("Bravo")) {
                bravo = mf;
            }
        }
        if (bravo != null) {
            r.bravoOwn = (Boolean) higher.invoke(grid, bravo.priority, 9, 9);
            r.bravoOverlap = (Boolean) higher.invoke(grid, bravo.priority, 0, 0);
        }
        r.log = logBuf.toString(StandardCharsets.UTF_8);
        r.rows = rows();
        return r;
    }

    private static List<String> rows() {
        List<String> rows = new ArrayList<>();
        for (String line : logBuf.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                rows.add(line.substring(line.indexOf(TAG)));
            }
        }
        rows.forEach(row -> System.out.println("log  " + row));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static void setUp(Path cwd) throws Exception {
        Path media = cwd.resolve("media");
        if (Files.exists(media)) {
            try (Stream<Path> walk = Files.walk(media)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Path maps = media.resolve("maps");
        lotheaders(maps.resolve("Alpha"), 0, 2);
        lotheaders(maps.resolve("Bravo"), 0, 2);
        lotheaders(maps.resolve("Bravo"), 10, 12);
        lotheaders(maps.resolve("Zulu"), 20, 20);
        Files.writeString(maps.resolve("Alpha").resolve("map.info"), "title=Alpha\n");
        Files.writeString(maps.resolve("Bravo").resolve("map.info"), "title=Bravo\n");

        // DebugLog 與 ZomboidFileSystem.searchFolders 的前置條件（server 才不碰 Core 的畫面迴圈）
        RandStandard.INSTANCE.init();
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);
        ZomboidFileSystem.instance.init();
        // Alpha、Bravo 的 map.info 預先放進快取：真讀會載縮圖與地圖翻譯，和本刀無關；Zulu 不放（它要讀不到）
        Field maps2 = ChooseGameInfo.class.getDeclaredField("Maps");
        maps2.setAccessible(true);
        Constructor<ChooseGameInfo.Map> ctor = ChooseGameInfo.Map.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        HashMap<String, ChooseGameInfo.Map> table = (HashMap<String, ChooseGameInfo.Map>) maps2.get(null);
        table.put("Alpha", ctor.newInstance());
        table.put("Bravo", ctor.newInstance());

        higher = IsoMetaGrid.class.getDeclaredMethod("higherPriority300x300CellExists", int.class, int.class, int.class);
        higher.setAccessible(true);
        writeGameMap = ConnectionDetails.class.getDeclaredMethod("writeGameMap", ByteBufferWriter.class);
        writeGameMap.setAccessible(true);
    }

    private static void lotheaders(Path dir, int from, int to) throws IOException {
        Files.createDirectories(dir);
        for (int x = from; x <= to; x++) {
            for (int y = from; y <= to; y++) {
                Files.writeString(dir.resolve(x + "_" + y + ".lotheader"), "");
            }
        }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    private static final class Round {
        final List<String> names = new ArrayList<>();
        final List<Integer> priorities = new ArrayList<>();
        boolean bravoOwn;
        boolean bravoOverlap;
        boolean gameMapSame;
        String gameMap;
        String coreMap;
        String sent;
        String log;
        List<String> rows;
    }
}
