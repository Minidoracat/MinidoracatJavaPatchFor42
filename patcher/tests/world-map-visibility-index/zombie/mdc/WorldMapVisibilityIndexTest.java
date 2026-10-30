package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import zombie.characters.Faction;
import zombie.characters.IsoPlayer;
import zombie.core.random.RandStandard;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.areas.SafeHouse;
import zombie.network.GameServer;

/**
 * W86 世界地圖可見性索引行為驗證（獨立 JVM；{@code MODE} 是 static final）。
 * 用法：{@code WorldMapVisibilityIndexTest observe|on|off}（on／off 需 {@code -Dmdc.worldMapVisibilityIndex=on|off}，測試自驗）。
 *
 * <p>真 {@code SafeHouse}／{@code Faction} 類別、真 {@code safehouseList}／{@code Faction.factions}，原版查詢直接呼叫 jar 的
 * 靜態方法。邊界資料：同名在多塊、非標記 owner 等於查詢名、只差大小寫、名單內重複名、空名單塊、跨第二個 bitset word 的塊；
 * 同名在兩個派系（第一個贏）、派系 0 的成員又是後面派系的 owner（回派系 0）、owner 也列在成員、沒有成員的派系、成員重複；
 * 不在任何地方的名字與 null。
 *
 * <p>鎖：所有有序名字對逐值等於原版（派系比參照）；經手術後的 GameServer 私有方法也一樣；owner／成員 null、非字串成員、
 * {@code Faction.factions} 被換掉、輪外、其他執行緒都回原版（含原版的例外）；{@code round()} 把原版這一輪包在輪內、例外照丟；
 * 跨輪變動（71 → 63 → 1 → 65 → 71 塊、名字換塊、名單與塊順序打亂）每輪逐對等於原版（bitset 池重用與 words 變動）。
 * 原版負對照：輪內改名單後，查表本身會答錯，on 的抽樣比對在 256 次內抓到並永久停用、之後回原版的值，observe 第一次就抓到；
 * 停用後的下一次心跳帶 {@code disabled=true}。off 同一組資料全是原版的值、零計數零 log。
 */
public final class WorldMapVisibilityIndexTest {
    private static final String TAG = "[MinidoracatJavaPatch][WorldMapVisibilityIndex]";
    private static final String A = "Player-A";
    private static final String A_LOWER = "player-a";
    private static final String B = "Player-B";
    private static final String C = "Player-C";
    private static final String D = "Player-D";
    private static final String E = "Player-E";
    private static final String F = "Player-F";
    private static final String G = "Player-G";
    private static final String H = "Player-H";
    private static final String MARK = "@MSH:1";
    private static final String[] NAMES = {A, A_LOWER, B, C, D, E, F, G, H, MARK, null};

    // countersForTest 的位置
    private static final int ROUNDS = 0;
    private static final int Q_FACTION = 1;
    private static final int Q_SAFEHOUSE = 2;
    private static final int FAST = 3;
    private static final int NULL_ARG = 4;
    private static final int UNTRUSTED = 5;
    private static final int SWAPPED = 6;
    private static final int OUTSIDE = 7;
    private static final int OFF_THREAD = 8;
    private static final int AUDITS = 9;
    private static final int MISMATCHES = 10;
    private static final int BUILDS = 11;
    private static final int OBSERVED = 12;
    private static final int VANILLA_NS = 13;
    private static final int INDEX_NS = 14;

    private static int failed;
    private static String mode;
    private static boolean engaged;
    private static ByteArrayOutputStream logBuf;
    private static final List<SafeHouse> HOUSES = new ArrayList<>();
    private static final List<Faction> FACS = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        mode = args.length == 0 ? "observe" : args[0];
        engaged = !"off".equals(mode);
        RandStandard.INSTANCE.init();   // Faction 建構子用 Rand；DebugLog 格式化鏈也要
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);

        int want = switch (mode) {
            case "on" -> WorldMapVisibilityIndex.MODE_ON;
            case "off" -> WorldMapVisibilityIndex.MODE_OFF;
            default -> WorldMapVisibilityIndex.MODE_OBSERVE;
        };
        expect("自驗：MODE 與 argv 相符（" + mode + "）", WorldMapVisibilityIndex.MODE == want);
        expect("parseMode：null／未知值→observe，1|on→on，2|observe→observe，0|off→off（去空白、不分大小寫）",
                WorldMapVisibilityIndex.parseMode(null) == WorldMapVisibilityIndex.MODE_OBSERVE
                && WorldMapVisibilityIndex.parseMode("x") == WorldMapVisibilityIndex.MODE_OBSERVE
                && WorldMapVisibilityIndex.parseMode("1") == WorldMapVisibilityIndex.MODE_ON
                && WorldMapVisibilityIndex.parseMode(" On ") == WorldMapVisibilityIndex.MODE_ON
                && WorldMapVisibilityIndex.parseMode("2") == WorldMapVisibilityIndex.MODE_OBSERVE
                && WorldMapVisibilityIndex.parseMode("0") == WorldMapVisibilityIndex.MODE_OFF
                && WorldMapVisibilityIndex.parseMode("OFF") == WorldMapVisibilityIndex.MODE_OFF);

        buildWorld();
        equivalence();
        patchedGameServer();
        untrusted();
        outsideAndOffThread();
        rounds();
        crossRounds();
        negativeControl();

        List<String> rows = rows();
        if (engaged) {
            expect("首次生效行恰一行、含 kill switch 名稱；停用行恰一行（kind=safehouse）；停用後的心跳恰一行、帶 disabled=true",
                    rows.stream().filter(r -> r.contains("首次生效") && r.contains("-Dmdc.worldMapVisibilityIndex=0|off")).count() == 1
                    && rows.stream().filter(r -> r.contains("停用（本次啟動改回原版）：kind=safehouse")).count() == 1
                    && rows.stream().filter(r -> r.contains(TAG + " beat ")).count() == 1
                    && rows.stream().filter(r -> r.contains(TAG + " beat ")).allMatch(r -> r.contains("disabled=true")
                            && r.contains("fallback[outsideRound=")));
        } else {
            expect("off：零 log 行", rows.isEmpty());
        }
        expect("log 不含任何名字", rows.stream().noneMatch(r -> r.contains("Player-") || r.contains(A_LOWER) || r.contains("@MSH")));
        for (String row : rows) {
            System.out.println("log  " + row.substring(row.indexOf(TAG)));
        }
        System.out.println("beat " + WorldMapVisibilityIndex.beatLine());
        if (failed > 0) {
            System.out.println("world-map-visibility-index FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("world-map-visibility-index OK mode=" + mode);
    }

    /** 安全屋 0..70（70 在第二個 bitset word）與派系 0..5。 */
    private static void buildWorld() {
        SafeHouse.getSafehouseList().clear();
        Faction.factions.clear();
        house(MARK, A, B);                 // 0
        house(C, A, A_LOWER, A);           // 1：非標記 owner＝查詢名、只差大小寫、重複名
        house("@MSH:2");                   // 2：空名單
        house("@MSH:3", B, D);             // 3
        for (int i = 4; i < 70; i++) {
            house("@MSH:" + (i + 10), B);  // 同名在多塊
        }
        house("@MSH:99", F, G);            // 70：第二個 word
        faction(A, A, B, H);               // 0：owner 也列在成員；H 是成員
        faction("Player-X", B, C);         // 1：B 也在這裡，第一個贏
        faction(D);                        // 2：沒有成員
        faction(A_LOWER);                  // 3：只差大小寫
        faction(F, G, G);                  // 4：成員重複
        faction(H);                        // 5：H 是派系 0 的成員、又是這裡的 owner → 原版回 0（先放全部 owner 會錯回 5）
    }

    private static void house(String owner, String... members) {
        SafeHouse h = new SafeHouse(0, 0, 1, 1, owner);
        h.getPlayers().clear();
        h.getPlayers().addAll(List.of(members));
        SafeHouse.getSafehouseList().add(h);
        HOUSES.add(h);
    }

    private static void faction(String owner, String... members) {
        Faction f = new Faction("fac-" + FACS.size(), owner);
        f.getPlayers().addAll(List.of(members));
        Faction.factions.add(f);
        FACS.add(f);
    }

    /** 所有有序名字對（含 null）在輪內逐值等於原版；原版本身答案也釘住，避免全 false 的假綠。 */
    private static void equivalence() throws Exception {
        long[] c0 = WorldMapVisibilityIndex.countersForTest();
        WorldMapVisibilityIndex.begin();
        boolean same = true;
        int pairs = 0;
        int nullPairs = 0;
        for (String a : NAMES) {
            for (String b : NAMES) {
                same &= WorldMapVisibilityIndex.isInSameSafehouse(a, b) == SafeHouse.isInSameSafehouse(a, b);
                pairs++;
                nullPairs += a == null || b == null ? 1 : 0;
            }
        }
        for (String a : NAMES) {
            IsoPlayer p = player(a);
            same &= WorldMapVisibilityIndex.getPlayerFaction(p) == Faction.getPlayerFaction(p);
        }
        boolean npe = throwsType(() -> WorldMapVisibilityIndex.getPlayerFaction(null), NullPointerException.class)
                && throwsType(() -> Faction.getPlayerFaction((IsoPlayer) null), NullPointerException.class);
        WorldMapVisibilityIndex.end();
        long[] c = delta(c0);
        expect("輪內 " + pairs + " 個有序名字對（含 null）安全屋逐值等於原版、" + NAMES.length + " 個玩家派系逐參照等於原版", same);
        expect("原版答案釘住：A／B、A／大小寫 a、A／C（owner）、F／G（第二個 word）、標記 owner／A 同屋；F／A、E／A、a／B 不同屋",
                SafeHouse.isInSameSafehouse(A, B) && SafeHouse.isInSameSafehouse(A, A_LOWER) && SafeHouse.isInSameSafehouse(A, C)
                && SafeHouse.isInSameSafehouse(F, G) && SafeHouse.isInSameSafehouse(MARK, A)
                && !SafeHouse.isInSameSafehouse(F, A) && !SafeHouse.isInSameSafehouse(E, A) && !SafeHouse.isInSameSafehouse(A_LOWER, B));
        expect("原版派系釘住：A→0、B→0（第一個贏）、H→0（成員先於後面派系的 owner）、C→1、D→2、a→3、G→4、E 與 null 名字→null",
                fac(A) == FACS.get(0) && fac(B) == FACS.get(0) && fac(H) == FACS.get(0) && fac(C) == FACS.get(1)
                && fac(D) == FACS.get(2) && fac(A_LOWER) == FACS.get(3) && fac(G) == FACS.get(4) && fac(E) == null
                && fac(null) == null);
        expect("null 玩家：helper 與原版都丟 NullPointerException", npe);
        int facQueries = NAMES.length + 1;
        if (engaged) {
            boolean observe = "observe".equals(mode);
            long fast = pairs - nullPairs + NAMES.length;
            expect("計數：safehouse=" + pairs + " faction=" + facQueries + " null=" + (nullPairs + 1) + " fast=" + fast
                            + " builds=1 mismatches=0；audits " + (observe ? "＝fast（逐次比對）" : "＝0（未滿 256 次）"),
                    c[Q_SAFEHOUSE] == pairs && c[Q_FACTION] == facQueries && c[NULL_ARG] == nullPairs + 1 && c[FAST] == fast
                    && c[BUILDS] == 1 && c[MISMATCHES] == 0 && c[ROUNDS] == 1 && c[AUDITS] == (observe ? fast : 0));
            if (observe) {
                expect("observe：這一輪記到原版與索引耗時", c[OBSERVED] == 1 && c[VANILLA_NS] > 0 && c[INDEX_NS] > 0);
            }
        } else {
            expect("off：零計數（純委派）", allZero(c));
        }
    }

    /** 經 dist 的手術後 GameServer 私有方法：結果等於原版，且（on／observe）真的進了 helper。 */
    private static void patchedGameServer() throws Exception {
        Method sameFaction = GameServer.class.getDeclaredMethod("isInSameFaction", IsoPlayer.class, IsoPlayer.class);
        Method anySafehouse = GameServer.class.getDeclaredMethod("isAnyPlayerInSameSafehouse", UdpConnection.class, IsoPlayer.class);
        sameFaction.setAccessible(true);
        anySafehouse.setAccessible(true);
        UdpConnection conn = alloc(UdpConnection.class);
        IsoPlayer[] slots = new IsoPlayer[4];
        slots[2] = player(A);   // slot 0、1 是 null，照原版略過
        setAny(conn, "players", slots);
        long[] c0 = WorldMapVisibilityIndex.countersForTest();
        WorldMapVisibilityIndex.begin();
        boolean ok = (boolean) sameFaction.invoke(null, player(A), player(B))
                && !(boolean) sameFaction.invoke(null, player(B), player(C))
                && !(boolean) sameFaction.invoke(null, player(E), player(E))
                && (boolean) sameFaction.invoke(null, player(D), player(D))
                && (boolean) anySafehouse.invoke(null, conn, player(B))
                && (boolean) anySafehouse.invoke(null, conn, player(C))
                && !(boolean) anySafehouse.invoke(null, conn, player(F));
        WorldMapVisibilityIndex.end();
        long[] c = delta(c0);
        expect("手術後 GameServer.isInSameFaction／isAnyPlayerInSameSafehouse 結果等於原版（A·B 同派系、B·C 不同、E 無派系、D 自己；A 與 B、C 同屋、與 F 不同屋）",
                ok);
        expect(engaged ? "手術後的私有方法真的進 helper：faction 8 次、safehouse 3 次、全走查表" : "off：手術後的私有方法經 helper 純委派、零計數",
                engaged ? c[Q_FACTION] == 8 && c[Q_SAFEHOUSE] == 3 && c[FAST] == 11 : allZero(c));
    }

    /** 原版會丟例外或語意可能變的狀態：整輪（或這次）回原版，例外類別相同。 */
    private static void untrusted() throws Exception {
        Field owner = SafeHouse.class.getDeclaredField("owner");
        owner.setAccessible(true);
        SafeHouse empty = HOUSES.get(2);
        long[] c0 = WorldMapVisibilityIndex.countersForTest();
        owner.set(empty, null);
        WorldMapVisibilityIndex.begin();
        boolean shOk = WorldMapVisibilityIndex.isInSameSafehouse(A, B)   // 原版在第 0 塊就停，不碰到 null owner
                && throwsType(() -> WorldMapVisibilityIndex.isInSameSafehouse(E, A), NullPointerException.class)
                && throwsType(() -> SafeHouse.isInSameSafehouse(E, A), NullPointerException.class)
                && WorldMapVisibilityIndex.getPlayerFaction(player(B)) == FACS.get(0);
        WorldMapVisibilityIndex.end();
        owner.set(empty, "@MSH:2");
        long[] c1 = delta(c0);
        expect("安全屋 owner＝null：整輪安全屋回原版（A·B 照樣 true、E·A 與原版同丟 NPE），派系照樣查表",
                shOk && (!engaged || c1[UNTRUSTED] == 2 && c1[FAST] == 1));

        List<String> members = FACS.get(1).getPlayers();
        c0 = WorldMapVisibilityIndex.countersForTest();
        members.add(null);
        WorldMapVisibilityIndex.begin();
        boolean nullMember = WorldMapVisibilityIndex.getPlayerFaction(player(B)) == FACS.get(0)
                && throwsType(() -> WorldMapVisibilityIndex.getPlayerFaction(player(E)), NullPointerException.class)
                && throwsType(() -> Faction.getPlayerFaction(player(E)), NullPointerException.class)
                && WorldMapVisibilityIndex.isInSameSafehouse(A, B);
        WorldMapVisibilityIndex.end();
        members.remove(members.size() - 1);
        c1 = delta(c0);
        expect("派系成員有 null：整輪派系回原版（B 照樣→0、E 與原版同丟 NPE），安全屋照樣查表",
                nullMember && (!engaged || c1[UNTRUSTED] == 2 && c1[FAST] == 1));

        @SuppressWarnings("unchecked")
        List<Object> rawMembers = (List<Object>) (List<?>) members;
        @SuppressWarnings("unchecked")
        List<Object> rawHouse = (List<Object>) (List<?>) HOUSES.get(0).getPlayers();
        c0 = WorldMapVisibilityIndex.countersForTest();
        rawMembers.add(42);
        rawHouse.add(43);
        WorldMapVisibilityIndex.begin();
        boolean foreign = throwsType(() -> WorldMapVisibilityIndex.getPlayerFaction(player(E)), ClassCastException.class)
                && throwsType(() -> Faction.getPlayerFaction(player(E)), ClassCastException.class)
                && WorldMapVisibilityIndex.isInSameSafehouse(A, B) == SafeHouse.isInSameSafehouse(A, B)
                && WorldMapVisibilityIndex.isInSameSafehouse(E, A) == SafeHouse.isInSameSafehouse(E, A);
        WorldMapVisibilityIndex.end();
        rawMembers.remove(rawMembers.size() - 1);
        rawHouse.remove(rawHouse.size() - 1);
        c1 = delta(c0);
        expect("非字串成員：派系整輪回原版（與原版同丟 ClassCastException）；安全屋名單的非字串元素原版 contains 本來就不中，照樣查表",
                foreign && (!engaged || c1[UNTRUSTED] == 1 && c1[FAST] == 2));

        ArrayList<Faction> original = Faction.factions;
        c0 = WorldMapVisibilityIndex.countersForTest();
        WorldMapVisibilityIndex.begin();
        boolean swapOk = WorldMapVisibilityIndex.getPlayerFaction(player(B)) == FACS.get(0);
        Faction.factions = new ArrayList<>(List.of(FACS.get(1)));
        swapOk &= WorldMapVisibilityIndex.getPlayerFaction(player(B)) == FACS.get(1);
        WorldMapVisibilityIndex.end();
        Faction.factions = original;
        c1 = delta(c0);
        expect("Faction.factions 輪內被換掉：回原版讀新清單（B→1）", swapOk && (!engaged || c1[SWAPPED] == 1));
    }

    /** 輪外（receive 路徑）與其他執行緒一律回原版讀 live 狀態。 */
    private static void outsideAndOffThread() throws Exception {
        List<String> zero = HOUSES.get(0).getPlayers();
        long[] c0 = WorldMapVisibilityIndex.countersForTest();
        zero.add(E);
        boolean outside = WorldMapVisibilityIndex.isInSameSafehouse(E, A) && WorldMapVisibilityIndex.getPlayerFaction(player(E)) == null;
        zero.remove(E);
        outside &= !WorldMapVisibilityIndex.isInSameSafehouse(E, A);
        long[] c1 = delta(c0);
        expect("輪外改名單後回原版的新值（E 加進第 0 塊→同屋、移除→不同屋）",
                outside && (engaged ? c1[OUTSIDE] == 3 && c1[FAST] == 0 : allZero(c1)));

        c0 = WorldMapVisibilityIndex.countersForTest();
        WorldMapVisibilityIndex.begin();
        boolean built = WorldMapVisibilityIndex.isInSameSafehouse(A, B);
        zero.add(E);   // 輪內改動：只有其他執行緒的查詢會看到（它們走原版）
        boolean[] seen = new boolean[2];
        Thread t = new Thread(() -> {
            seen[0] = WorldMapVisibilityIndex.isInSameSafehouse(E, A);
            seen[1] = WorldMapVisibilityIndex.getPlayerFaction(player(E)) == null;
        });
        t.start();
        t.join();
        zero.remove(E);
        WorldMapVisibilityIndex.end();
        c1 = delta(c0);
        expect("其他執行緒在輪內查詢走原版（看得到輪內新加的 E）",
                built && seen[0] && seen[1] && (engaged ? c1[OFF_THREAD] == 2 && c1[FAST] == 1 : allZero(c1)));
    }

    /** round() 把原版這一輪包在輪內；例外照丟、輪照樣結束。 */
    private static void rounds() throws Exception {
        Probe probe = installProbe();
        long[] c0 = WorldMapVisibilityIndex.countersForTest();
        WorldMapVisibilityIndex.round();
        boolean inside = probe.calls == 1 && probe.inRound == engaged && !WorldMapVisibilityIndex.inRoundForTest();
        probe.fail = true;
        boolean threw = throwsType(WorldMapVisibilityIndex::round, IllegalStateException.class);
        probe.fail = false;
        long[] c = delta(c0);
        expect(engaged ? "round()：原版 sendWorldMapPlayerPosition() 在輪內執行、結束後離開輪；原版丟例外照丟、輪照樣結束（rounds +2）"
                        : "off：round() 只呼叫原版、不進輪；例外照丟",
                inside && threw && probe.calls == 2 && !WorldMapVisibilityIndex.inRoundForTest()
                && c[ROUNDS] == (engaged ? 2 : 0));
    }

    /**
     * 跨輪變動：71 塊 → D 從第 3 塊移到最後一塊並縮到 63 塊（words 2→1）→ 1 塊（這一輪 {@code Faction.factions} 順序反過來，
     * B 從派系 0 變成派系 1）→ 65 塊（名單打亂、派系順序恢復）→ 71 塊（名單與塊順序打亂）。每輪都對全部有序名字對與全部玩家
     * 比對原版，抓 bitset 池重用時清零範圍、words 變動或派系表沒清空的錯；原版答案本身在輪間確實改變。
     */
    private static void crossRounds() throws Exception {
        List<String[]> base = new ArrayList<>();
        for (SafeHouse h : HOUSES) {
            String[] spec = new String[h.getPlayers().size() + 1];
            spec[0] = h.getOwner();
            for (int i = 0; i < h.getPlayers().size(); i++) {
                spec[i + 1] = h.getPlayers().get(i);
            }
            base.add(spec);
        }
        Random rnd = new Random(86);
        List<String[]> r2 = new ArrayList<>(base.subList(0, 62));
        r2.set(3, new String[]{base.get(3)[0], B});
        String[] last = Arrays.copyOf(base.get(70), base.get(70).length + 1);
        last[last.length - 1] = D;
        r2.add(last);
        List<String[]> r4 = new ArrayList<>(base.subList(0, 64));
        r4.add(base.get(70));
        List<String[]> r5 = new ArrayList<>(base);
        Collections.shuffle(r5, rnd);
        List<List<String[]>> worlds = List.of(base, r2, List.<String[]>of(base.get(0)), shuffledMembers(r4, rnd),
                shuffledMembers(r5, rnd));
        String[] sizes = {"71", "63", "1", "65", "71"};
        long[] c0 = WorldMapVisibilityIndex.countersForTest();
        StringBuilder bad = new StringBuilder();
        boolean[] df = new boolean[worlds.size()];
        boolean[] bd = new boolean[worlds.size()];
        Faction[] bFac = new Faction[worlds.size()];
        for (int w = 0; w < worlds.size(); w++) {
            setWorld(worlds.get(w));
            boolean flip = w == 2;
            if (flip) {
                Collections.reverse(Faction.factions);   // 同一個清單物件，只換順序：B 第一個符合的變成派系 1
            }
            df[w] = SafeHouse.isInSameSafehouse(D, F);
            bd[w] = SafeHouse.isInSameSafehouse(B, D);
            bFac[w] = Faction.getPlayerFaction(player(B));
            WorldMapVisibilityIndex.begin();
            boolean same = true;
            for (String a : NAMES) {
                for (String b : NAMES) {
                    same &= WorldMapVisibilityIndex.isInSameSafehouse(a, b) == SafeHouse.isInSameSafehouse(a, b);
                }
                IsoPlayer p = player(a);
                same &= WorldMapVisibilityIndex.getPlayerFaction(p) == Faction.getPlayerFaction(p);
            }
            WorldMapVisibilityIndex.end();
            if (flip) {
                Collections.reverse(Faction.factions);
            }
            if (!same) {
                bad.append(' ').append(sizes[w]);
            }
        }
        setWorld(null);
        long[] c = delta(c0);
        expect("跨輪 71→63→1→65→71 塊（D 換到最後一塊、1 塊那輪派系順序反過來、名單與塊順序打亂）：每輪全部有序名字對與玩家等於原版"
                        + (bad.length() > 0 ? "（不符：" + bad + "）" : ""),
                bad.length() == 0);
        expect("跨輪原版答案確實改變：D·F 只在 63 塊那輪同屋；B·D 在 63 塊與 1 塊那輪不同屋；B 的派系只在 1 塊那輪是派系 1、其他輪是派系 0；"
                        + "派系恢復成 buildWorld 的順序",
                !df[0] && df[1] && !df[2] && !df[3] && !df[4] && bd[0] && !bd[1] && !bd[2] && bd[3] && bd[4]
                && bFac[0] == FACS.get(0) && bFac[1] == FACS.get(0) && bFac[2] == FACS.get(1) && bFac[3] == FACS.get(0)
                && bFac[4] == FACS.get(0) && Faction.factions.equals(FACS));
        expect(engaged ? "跨輪：rounds=5、builds=5、mismatches=0" : "off：跨輪零計數",
                engaged ? c[ROUNDS] == 5 && c[BUILDS] == 5 && c[MISMATCHES] == 0 : allZero(c));
    }

    private static List<String[]> shuffledMembers(List<String[]> specs, Random rnd) {
        List<String[]> out = new ArrayList<>();
        for (String[] s : specs) {
            List<String> m = new ArrayList<>(Arrays.asList(s).subList(1, s.length));
            Collections.shuffle(m, rnd);
            m.add(0, s[0]);
            out.add(m.toArray(new String[0]));
        }
        return out;
    }

    /** safehouseList 換成照 spec 新建的塊；null＝放回 buildWorld 的原始 71 塊。 */
    private static void setWorld(List<String[]> specs) {
        SafeHouse.getSafehouseList().clear();
        if (specs == null) {
            SafeHouse.getSafehouseList().addAll(HOUSES);
            return;
        }
        for (String[] s : specs) {
            SafeHouse h = new SafeHouse(0, 0, 1, 1, s[0]);
            h.getPlayers().clear();
            h.getPlayers().addAll(Arrays.asList(s).subList(1, s.length));
            SafeHouse.getSafehouseList().add(h);
        }
    }

    /**
     * 原版負對照：輪內把 B 移出第 0 塊（實際不可能的輪內變動）。查表本身仍答 A·B 同屋（錯），on 的抽樣比對要在 256 次內抓到、
     * 永久停用並回原版的值；observe 第一次就抓到；off 一直是原版的值。之後 round() 不再進輪。
     */
    private static void negativeControl() throws Exception {
        List<String> zero = HOUSES.get(0).getPlayers();
        long[] c0 = WorldMapVisibilityIndex.countersForTest();
        WorldMapVisibilityIndex.begin();
        boolean before = WorldMapVisibilityIndex.isInSameSafehouse(A, B);
        zero.remove(B);
        boolean vanillaNow = SafeHouse.isInSameSafehouse(A, B);
        int stale = 0;
        int callsToDisable = -1;
        int wrongAfter = 0;
        for (int i = 1; i <= 600; i++) {
            boolean r = WorldMapVisibilityIndex.isInSameSafehouse(A, B);
            if (callsToDisable < 0) {
                stale += r ? 1 : 0;
                if (WorldMapVisibilityIndex.disabledForTest()) {
                    callsToDisable = i;
                }
            } else {
                wrongAfter += r ? 1 : 0;
            }
        }
        WorldMapVisibilityIndex.end();
        long[] c = delta(c0);
        expect("負對照前提：改動前同屋、改動後原版不同屋", before && !vanillaNow);
        switch (mode) {
            case "on" -> expect("on：查表在停用前答錯 " + stale + " 次（守門擋的是真錯）、第 " + callsToDisable
                            + " 次抽樣比對抓到（≤256）、mismatches=1、停用後一律回原版的值",
                    stale > 0 && callsToDisable > 0 && callsToDisable <= 256 && c[MISMATCHES] == 1 && wrongAfter == 0
                    && c[AUDITS] >= 1);
            case "observe" -> expect("observe：第一次查詢就比對不一致、mismatches=1、回的一直是原版的值",
                    stale == 0 && callsToDisable == 1 && c[MISMATCHES] == 1 && wrongAfter == 0);
            default -> expect("off：600 次都是原版的值、零計數、不停用",
                    stale == 0 && callsToDisable < 0 && wrongAfter == 0 && allZero(c) && !WorldMapVisibilityIndex.disabledForTest());
        }
        Probe probe = installProbe();
        long rounds = WorldMapVisibilityIndex.countersForTest()[ROUNDS];
        WorldMapVisibilityIndex.round();
        boolean plain = probe.calls == 1 && !probe.inRound && WorldMapVisibilityIndex.countersForTest()[ROUNDS] == rounds;
        boolean liveAfter = !WorldMapVisibilityIndex.isInSameSafehouse(A, B);
        zero.add(B);
        liveAfter &= WorldMapVisibilityIndex.isInSameSafehouse(A, B);
        expect("停用（或 off）後 round() 只呼叫原版、不進輪；查詢讀 live 原版", plain && liveAfter);
        int before5 = rows().size();
        WorldMapVisibilityIndex.expireBeatForTest();
        WorldMapVisibilityIndex.round();
        List<String> after = rows();
        boolean beat = after.size() == before5 + 1 && after.get(after.size() - 1).contains(TAG + " beat ")
                && after.get(after.size() - 1).contains("disabled=true");
        expect(engaged ? "停用後心跳照常：間隔到了的下一輪印一行 beat、帶 disabled=true" : "off：心跳間隔到了也不印",
                engaged ? beat : after.size() == before5);
    }

    /** udpEngine.connections 換成會記錄自己被讀時是否在輪內的空清單。 */
    private static Probe installProbe() throws Exception {
        UdpEngine engine = alloc(UdpEngine.class);
        Probe probe = new Probe();
        Field connections = UdpEngine.class.getDeclaredField("connections");
        connections.setAccessible(true);
        connections.set(engine, probe);
        GameServer.udpEngine = engine;
        return probe;
    }

    private static final class Probe extends ArrayList<UdpConnection> {
        int calls;
        boolean inRound;
        boolean fail;

        @Override
        public int size() {
            calls++;
            inRound = WorldMapVisibilityIndex.inRoundForTest();
            if (fail) {
                throw new IllegalStateException("probe");
            }
            return 0;
        }
    }

    private static Faction fac(String name) throws Exception {
        return Faction.getPlayerFaction(player(name));
    }

    private static IsoPlayer player(String name) {
        try {
            IsoPlayer p = alloc(IsoPlayer.class);
            p.username = name;
            return p;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean throwsType(Runnable r, Class<? extends Throwable> type) {
        try {
            r.run();
            return false;
        } catch (Throwable t) {
            return t.getClass() == type;
        }
    }

    private static long[] delta(long[] before) {
        long[] now = WorldMapVisibilityIndex.countersForTest();
        long[] d = new long[now.length];
        for (int i = 0; i < now.length; i++) {
            d[i] = now[i] - before[i];
        }
        return d;
    }

    private static boolean allZero(long[] c) {
        for (long x : c) {
            if (x != 0L) {
                return false;
            }
        }
        return true;
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

    private static void setAny(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
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
        System.out.println((ok ? "wmvi pass  " : "wmvi FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private WorldMapVisibilityIndexTest() {
    }
}
