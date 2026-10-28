package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Stack;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoSurvivor;
import zombie.characters.IsoZombie;
import zombie.characters.Stats;
import zombie.characters.animals.IsoAnimal;
import zombie.core.textures.ColorInfo;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector2;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerLOS;
import zombie.vehicles.BaseVehicle;

/**
 * W83 PlayerLosFilter 行為測試。用法：{@code PlayerLosFilterTest vanilla|off|observe|on|violation <trace>}，每種一個 JVM，
 * 全部帶 {@code -XX:hashCode=2}（identity hash 固定，HashSet 迭代順序在兩個 JVM 之間相同）與 {@code -XX:-OmitStackTraceInFastThrow}
 * （C2 熱路徑的 NPE 不換成沒有訊息的預建例外，例外訊息才逐行可比）。
 *
 * <p>vanilla：classpath 不含 dist，跑 jar 內原版 {@code IsoPlayer.updateLOS}，把每次呼叫的結果寫進 trace；off／observe／on：跑 dist 內
 * 手術後的版本，同一批世界逐行比對 trace（同一個亂數種子、同樣的建立順序）。每行記下：例外類別與訊息、{@code TestZombieSpotPlayer}／
 * {@code spotted} 呼叫序列、spottedList／lastSpotted、viewer 與 stats 的全部基本型別欄位、每個物件的全部基本型別欄位（雜湊）、
 * GameTime 倍率。情境：隨機世界（視窗邊界 ±1 格、z 邊界、自己、physics、車輛、沒有格子、grapple-only、隱形、玩家、動物、
 * survivor、睡著）、PlayerData 重複／缺少／status 不是 BusyInMain、迴圈中改清單（CME，含最後一個元素時修改）、null 與非
 * IsoMovingObject 元素、非主執行緒。violation（on）：迴圈中改 PlayerData 位置讓視窗外物件變成看得見，驗 audit 抓到後永久停用。
 *
 * <p>測試替身只在出口：viewer 覆寫 {@code TestZombieSpotPlayer} 記錄呼叫（殭屍不往下呼叫原版：IsoZombie 是 final、真的
 * {@code spotted}／ZombieControl 需要完整世界與網路），{@code getDetectionRange}（真的要 traits、穿戴物品；伺服器上它的值不影響
 * 分支，canSee 恆等於 couldSee）；玩家與動物目標覆寫 {@code spotted} 記錄。其餘都是原版程式碼。
 */
public final class PlayerLosFilterTest {
    private static final PrintStream OUT = System.out;
    private static int failed;

    static final List<String> EVENTS = new ArrayList<>();
    static final IdentityHashMap<Object, Integer> IDS = new IdentityHashMap<>();
    static Object hookOn;
    static Runnable hook;

    private static Field PLAYERS_MAIN;
    private static Constructor<?> PD_NEW;
    private static Field PD_STATUS;
    private static Object BUSY;
    private static Object READY;
    private static boolean fakeDeadOk;
    private static IsoCell cell;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path trace = Path.of(args[1]);
        setup();
        boolean vanilla = mode.equals("vanilla");
        String src = IsoPlayer.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        expect("IsoPlayer 來源（" + src + "）：vanilla 是 jar 原版、其餘是 dist 手術後", vanilla == src.endsWith(".jar"));
        expect("identity hash 固定（-XX:hashCode=2）", System.identityHashCode(new Object()) == System.identityHashCode(new Object()));
        ByteArrayOutputStream log = captureLog();
        if (vanilla) {
            boolean absent;
            try {
                Class.forName("zombie.mdc.PlayerLosFilter");
                absent = false;
            } catch (ClassNotFoundException e) {
                absent = true;
            }
            expect("vanilla classpath 沒有 PlayerLosFilter", absent);
            List<String> lines = scenarios();
            Files.write(trace, lines, StandardCharsets.UTF_8);
            OUT.println("  寫出 trace " + lines.size() + " 行 → " + trace);
        } else {
            patchedChecks(mode, trace, log);
        }
        if (failed > 0) {
            OUT.println("PlayerLosFilterTest FAILED mode=" + mode + " failed=" + failed);
            System.exit(1);
        }
        OUT.println("PlayerLosFilterTest OK mode=" + mode);
    }

    private static void patchedChecks(String mode, Path trace, ByteArrayOutputStream log) throws Exception {
        int want = switch (mode) {
            case "off" -> PlayerLosFilter.MODE_OFF;
            case "on", "violation", "phase50", "phase64" -> PlayerLosFilter.MODE_ON;
            case "observe" -> PlayerLosFilter.MODE_OBSERVE;
            default -> throw new IllegalArgumentException(mode);
        };
        expect("模式與 argv 相符（" + mode + "，-Dmdc.playerLosFilter=" + System.getProperty("mdc.playerLosFilter") + "）",
                PlayerLosFilter.MODE == want);
        expect("parseMode：null／2／observe／亂寫→observe、1／on／ ON →on、0／off／OFF→off",
                PlayerLosFilter.parseMode(null) == 2 && PlayerLosFilter.parseMode("2") == 2
                        && PlayerLosFilter.parseMode("observe") == 2 && PlayerLosFilter.parseMode("x") == 2
                        && PlayerLosFilter.parseMode("1") == 1 && PlayerLosFilter.parseMode("on") == 1
                        && PlayerLosFilter.parseMode(" ON ") == 1 && PlayerLosFilter.parseMode("0") == 0
                        && PlayerLosFilter.parseMode("off") == 0 && PlayerLosFilter.parseMode("OFF") == 0);
        expect("parseSeed：數字照用、0 換成固定非零值、沒設或亂寫用 nanoTime（非零）",
                PlayerLosFilter.parseSeed("83") == 83L && PlayerLosFilter.parseSeed(" 83 ") == 83L
                        && PlayerLosFilter.parseSeed("0") != 0L && PlayerLosFilter.parseSeed(null) != 0L
                        && PlayerLosFilter.parseSeed("x") != 0L);
        String seedProp = System.getProperty("mdc.playerLosFilter.seed");
        expect("測試用固定種子（-Dmdc.playerLosFilter.seed=" + seedProp + "）", seedProp != null
                && PlayerLosFilter.SEED == Long.parseLong(seedProp.trim()));
        expect("反射取得 ServerLOS.playersMain／PlayerData 欄位與 BusyInMain", PlayerLosFilter.reflectForTest().equals("ok"));
        if (mode.equals("violation")) {
            violation(log);
            return;
        }
        if (mode.startsWith("phase")) {
            phase(Integer.parseInt(mode.substring(5)), log);
            return;
        }
        List<String> want0 = Files.readAllLines(trace, StandardCharsets.UTF_8);
        List<String> got = scenarios();
        int diffs = 0;
        for (int i = 0; i < Math.max(want0.size(), got.size()); i++) {
            String a = i < want0.size() ? want0.get(i) : "<缺>";
            String b = i < got.size() ? got.get(i) : "<缺>";
            if (!a.equals(b) && ++diffs <= 3) {
                OUT.println("  差異 #" + i + "\n    原版   " + a + "\n    手術後 " + b);
            }
        }
        long[] c = PlayerLosFilter.countersForTest();
        long calls = c[0], engaged = c[1], notBusy = c[2], noData = c[3], reflect = c[4], thread = c[5], loops = c[6],
                auditLoops = c[7], objSum = c[8], keepSum = c[9], audits = c[10], mismatches = c[11], anomalies = c[12],
                plains = c[13], plainLoops = c[14], checks = c[15], tick = c[16];
        String counters = "calls=" + calls + " engaged=" + engaged + " notBusy=" + notBusy + " noData=" + noData + " reflect=" + reflect
                + " thread=" + thread + " loops=" + loops + " auditLoops=" + auditLoops + " plainLoops=" + plainLoops
                + " obj=" + objSum + " keep=" + keepSum + " audits=" + audits + " plains=" + plains + " checks=" + checks
                + " mismatches=" + mismatches + " anomalies=" + anomalies;
        OUT.println("  " + counters);
        expect("與 jar 原版逐行相同（" + got.size() + " 行，差 " + diffs + "）", diffs == 0 && got.size() == want0.size() && got.size() > 1000);
        String logText = log.toString(StandardCharsets.UTF_8);
        if (mode.equals("off")) {
            expect("off：完全不經過濾（計數全 0）、不記 log", calls == 0 && engaged == 0 && thread == 0 && audits == 0 && plains == 0
                    && checks == 0 && !logText.contains("[PlayerLosFilter]"));
            return;
        }
        expect("每種 fallback 都走過（notBusy／noData／thread）、反射與比對零異常", notBusy > 0 && noData > 0 && thread > 0
                && reflect == 0 && anomalies == 0);
        expect("零 mismatch、未停用", mismatches == 0 && !PlayerLosFilter.disabledForTest());
        // 照同一個種子在測試裡重算 engaged 次抽樣：0＝比對、1＝原版計時
        Rng rng = new Rng(PlayerLosFilter.SEED);
        long wantAudits = 0;
        long wantPlains = 0;
        for (long i = 0; i < engaged; i++) {
            int r = rng.next();
            wantAudits += r == 0 ? 1 : 0;
            wantPlains += r == 1 ? 1 : 0;
        }
        expect("抽樣次數等於同一種子重算的精確值：audits=" + wantAudits + "、plains=" + wantPlains + "（亂數狀態同步）",
                engaged > 500 && audits == wantAudits && plains == wantPlains && tick == rng.x);
        expect("比對真的呼叫了 isCouldSee（" + checks + " 次）、原版計時迴圈有跑完（" + plainLoops + "／" + plains + "）",
                checks > 0 && plainLoops > 0 && plainLoops <= plains && auditLoops > 0 && auditLoops <= audits);
        expect("objAvg 的分母只含過濾＋比對迴圈：略過 " + (objSum - keepSum) + "／" + objSum + "（keep>0 且略過 ≥ 1/3）",
                keepSum > 0 && (objSum - keepSum) * 3 >= objSum);
        if (mode.equals("observe")) {
            expect("observe：沒有過濾迴圈", loops == 0);
        } else {
            expect("on：其餘呼叫都過濾（過濾迴圈 " + loops + "，engaged−抽樣 " + (engaged - audits - plains) + "）",
                    loops > 0 && loops <= engaged - audits - plains);
        }
        samplingProbe(mode, rng);
        expect("首次生效行：模式、reflect=ok、kill switch 與抽樣說明", logText.contains("[MinidoracatJavaPatch][PlayerLosFilter] 首次生效")
                && logText.contains("reflect=ok") && logText.contains("0|off") && logText.contains("各以 1/64 隨機抽比對")
                && logText.contains("mode=" + mode));
        String beat = PlayerLosFilter.beatLine();
        expect("beat 欄位齊全", beat.matches("mode=\\w+ calls=\\d+ engaged=\\d+ fallback\\[notBusy=\\d+ noData=\\d+ reflect=\\d+ thread=\\d+\\]"
                + " objAvg=\\d+ keepAvg=\\d+ skipAvg=\\d+ loopUsAvg=\\d+/\\d+/\\d+ audits=\\d+ plains=\\d+ mismatches=\\d+ disabled=false"
                + " anomalies=\\d+"));
        OUT.println("  beat 範例：[MinidoracatJavaPatch][PlayerLosFilter] beat " + beat);
    }

    /**
     * 直接呼叫 getCell／iterator 640 次（視窗內外都有物件的世界），照同一種子預測每次抽到什麼並驗證拿到的迭代器：
     * 原版計時是 Plain（走完全部元素、不呼叫 isCouldSee）、比對呼叫 isCouldSee、其餘 on 是過濾、observe 是原版迭代器本身（同一個物件）。
     */
    private static void samplingProbe(String mode, Rng rng) throws Exception {
        World w = new World(viewer(500.5F, 500.5F, 0), 500, 500, 0);
        for (int i = 0; i < 8; i++) {
            w.add(w.zombie(501 + i, 502, 0, false, true));
            w.add(w.zombie(700 + i, 502, 0, false, true));
        }
        w.add(w.viewer);
        w.install(true);
        RecSet<IsoMovingObject> rec = (RecSet<IsoMovingObject>) w.set;
        int plainOk = 0;
        int auditOk = 0;
        int restOk = 0;
        int plainN = 0;
        int auditN = 0;
        int restN = 0;
        for (int i = 0; i < 640; i++) {
            w.prepare(Call.plain());
            long[] c0 = PlayerLosFilter.countersForTest();
            int t = rng.next();
            PlayerLosFilter.getCell(w.viewer);
            Iterator<?> it = PlayerLosFilter.iterator(rec);
            Iterator<?> raw = rec.last;
            int n = 0;
            while (it.hasNext()) {
                it.next();
                n++;
            }
            long[] c1 = PlayerLosFilter.countersForTest();
            long checks = c1[15] - c0[15];
            String kind = it.getClass().getSimpleName();
            if (t == 1) {
                plainN++;
                plainOk += kind.equals("Plain") && checks == 0 && n == rec.size() && c1[13] == c0[13] + 1 && c1[14] == c0[14] + 1 ? 1 : 0;
            } else if (t == 0) {
                auditN++;
                auditOk += kind.equals("Filter") && checks == 8 && n == rec.size() && c1[10] == c0[10] + 1 ? 1 : 0;
            } else {
                restN++;
                boolean ok = mode.equals("observe") ? it == raw && checks == 0 : kind.equals("Filter") && it != raw && checks == 0 && n == 9;
                restOk += ok ? 1 : 0;
            }
        }
        expect("抽樣探針（640 次）：原版計時 " + plainOk + "／" + plainN + "（Plain、走完全部元素、不呼叫 isCouldSee）、比對 " + auditOk + "／" + auditN
                        + "（8 個視窗外各呼叫一次 isCouldSee）、其餘 " + restOk + "／" + restN
                        + (mode.equals("observe") ? "（回傳的就是原版迭代器本身）" : "（過濾，只交出自己與視窗內 8 個）") + "；亂數狀態同步",
                plainN > 0 && auditN > 0 && plainOk == plainN && auditOk == auditN && restOk == restN
                        && PlayerLosFilter.countersForTest()[16] == rng.x);
    }

    /** helper 的 xorshift64* 在測試裡獨立重寫一份（取最高 6 bits）。 */
    static final class Rng {
        long x;

        Rng(long seed) {
            x = seed;
        }

        int next() {
            x ^= x >>> 12;
            x ^= x << 25;
            x ^= x >>> 27;
            return (int) ((x * 0x2545F4914F6CDD1DL) >>> 58);
        }
    }

    // ---- 情境 ----

    private static List<String> scenarios() throws Exception {
        List<String> out = new ArrayList<>();
        Random r = new Random(83L);
        for (int w = 0; w < 700; w++) {
            World world = World.random(r);
            int calls = 1 + r.nextInt(2);
            for (int c = 0; c < calls; c++) {
                Call call = Call.random(r, world);
                out.add("w" + w + "c" + c + " " + call + " | " + run(world, call));
            }
        }
        for (int w = 0; w < 40; w++) {
            World world = World.random(r);
            out.add("thread w" + w + " | " + runOffMain(world, Call.plain()));
        }
        out.addAll(boundary());
        out.addAll(lastElementEdits(r));
        return out;
    }

    /** 視窗四邊與 z 上下界各 ±1 格放殭屍，界內那格看得見：界內走近處分支、界外走遠處分支。 */
    private static List<String> boundary() throws Exception {
        List<String> out = new ArrayList<>();
        for (int pz = 0; pz <= 1; pz++) {
            World w = new World(viewer(500.5F, 500.5F, pz), 500, 500, pz);
            int minX = 500 - 48;
            int minY = 500 - 48;
            int minZ = pz - LosUtil.sizeZ / 2;
            int[] xs = {minX - 1, minX, minX + 95, minX + 96};
            int[] zs = {minZ - 1, minZ, minZ + LosUtil.sizeZ - 1, minZ + LosUtil.sizeZ};
            for (int x : xs) {
                w.add(w.zombie(x, 500, pz, false, true));
                w.add(w.zombie(500, x - minX + minY, pz, false, true));
            }
            for (int z : zs) {
                w.add(w.zombie(501, 501, z, false, true));
            }
            w.install(true);
            out.add("boundary pz=" + pz + " | " + run(w, Call.plain()));
        }
        return out;
    }

    /** TestZombieSpotPlayer 時改清單：被改的是迭代順序最後一個元素時原版不丟 CME；否則丟 CME。 */
    private static List<String> lastElementEdits(Random r) throws Exception {
        List<String> out = new ArrayList<>();
        for (int edit = 0; edit < 3; edit++) {
            for (boolean last : new boolean[]{true, false}) {
                for (int attempt = 0; attempt < 200; attempt++) {
                    World w = World.random(new Random(r.nextLong()));
                    Object lastObj = null;
                    for (Object o : (Set<?>) w.set) {
                        lastObj = o;
                    }
                    IsoMovingObject target = null;
                    for (Object o : (Set<?>) w.set) {
                        if (o instanceof IsoZombie z && w.visibleNear(z) && (o == lastObj) == last) {
                            target = z;
                        }
                    }
                    if (target == null) {
                        continue;
                    }
                    Call call = Call.plain();
                    call.hookTarget = target;
                    call.edit = edit;
                    out.add("edit=" + edit + " last=" + last + " | " + run(w, call));
                    break;
                }
            }
        }
        expect("最後元素／中途改清單的世界都排得出（6 種）", out.size() == 6);
        return out;
    }

    // ---- violation：on 模式 audit 發現視窗外看得見 → 永久停用 ----

    private static void violation(ByteArrayOutputStream log) throws Exception {
        World w = null;
        IsoZombie far = null;
        for (int attempt = 0; attempt < 50 && w == null; attempt++) {
            World t = new World(viewer(200.5F, 200.5F, 0), 200, 200, 0);
            for (int i = 0; i < 10 + attempt; i++) {
                t.add(t.zombie(203 + i % 5, 201 + i / 5, 0, false, true));
            }
            IsoZombie b = t.zombie(260, 200, 0, false, true);
            t.add(b);
            t.install(true);
            // 第一個看得見的殭屍（hook 觸發點）必須排在 B 之前
            int firstSeen = -1;
            int bAt = -1;
            int i = 0;
            for (IsoMovingObject o : t.set) {
                if (o instanceof IsoZombie z && Boolean.TRUE.equals(t.seen.get(z.getCurrentSquare())) && firstSeen < 0) {
                    firstSeen = i;
                }
                if (o == b) {
                    bAt = i;
                }
                i++;
            }
            if (firstSeen >= 0 && firstSeen < bAt) {
                w = t;
                far = b;
            }
        }
        expect("violation 世界排得出（遠處殭屍不是第一個）", w != null);
        IsoZombie b = far;
        World world = w;
        Object pd = world.data;
        Field px = pd.getClass().getDeclaredField("px");
        Field vis = pd.getClass().getDeclaredField("visible");
        px.setAccessible(true);
        vis.setAccessible(true);
        boolean[][][] visible = (boolean[][][]) vis.get(pd);
        int skippedB = 0;
        int plainN = 0;
        int plainOkN = 0;
        int calls = 0;
        boolean auditSawB = false;
        // 第一個看得見的殭屍被 TestZombieSpotPlayer 時，把 PlayerData 往 +x 挪 30 格，並讓 y 197–207 那一帶都看得見：B 落進視窗且看得見
        // （模擬位置在迴圈中被改）；A 們挪完仍看得見，原版的 TestIfSeen 不會走到 canSeeHeadSquare（測試 JVM 建不起 StateMachine）。
        Runnable drift = () -> {
            try {
                px.setInt(pd, 230);
                for (boolean[][] col : visible) {
                    for (int y = 45; y <= 55; y++) {
                        col[y][LosUtil.sizeZ / 2] = true;
                    }
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
        // 照同一種子預測每次抽到什麼：還沒看到漂移下的原版計時與過濾之前，抽到比對的那幾次不漂移（必須零 mismatch），之後第一次比對才漂移
        Rng rng = new Rng(PlayerLosFilter.SEED);
        int clean = 0;
        int quietAudits = 0;
        boolean quietOk = true;
        int filtered = 0;
        while (calls < 5000 && !PlayerLosFilter.disabledForTest()) {
            int kind = rng.next();
            calls++;
            px.setInt(pd, 200);
            boolean quiet = kind == 0 && (plainN == 0 || filtered == 0);
            hook = quiet ? null : drift;
            hookOn = quiet ? null : ANY_VISIBLE;
            String res = run(world, Call.plain());
            clean += res.startsWith("thrown=- ") ? 1 : 0;
            boolean sawB = EVENTS.contains("tzsp " + id(b));
            long[] ci = PlayerLosFilter.countersForTest();
            if (quiet) {
                quietAudits++;
                quietOk &= !sawB && ci[11] == 0 && !PlayerLosFilter.disabledForTest();
            } else if (kind == 1) {
                plainN++;
                plainOkN += sawB && ci[11] == 0 && !PlayerLosFilter.disabledForTest() ? 1 : 0;
            } else if (kind == 0) {
                auditSawB = sawB && ci[11] == 1 && PlayerLosFilter.disabledForTest();
            } else {
                filtered++;
                skippedB += sawB ? 0 : 1;
            }
        }
        long[] c = PlayerLosFilter.countersForTest();
        OUT.println("  violation：第 " + calls + " 次呼叫抽到漂移下的比對（其間過濾 " + filtered + " 次、原版計時 " + plainN + " 次、不漂移的比對 "
                + quietAudits + " 次）");
        expect("不漂移時抽到的比對（" + quietAudits + " 次）：B 在視窗外、看不見，零 mismatch、不停用", quietOk);
        expect("每次都正常跑完（" + clean + "／" + calls + "）", clean == calls);
        expect("過濾的 " + filtered + " 次：B 在建立迭代器時在視窗外而被略過（前提被破壞時 on 與原版不同，所以要比對）",
                filtered > 0 && skippedB == filtered);
        expect("原版計時的 " + plainN + " 次：全部放行、原版處理到 B，但不比對、不記 mismatch", plainN > 0 && plainOkN == plainN);
        expect("漂移下第一次抽到比對：放行全部、原版處理到 B（看得見）並記 mismatch、本次啟動停用",
                auditSawB && c[11] == 1 && c[10] == quietAudits + 1 && PlayerLosFilter.disabledForTest());
        String text = log.toString(StandardCharsets.UTF_8);
        expect("停用行記下 player、px／py／pz、物件座標", text.contains("[MinidoracatJavaPatch][PlayerLosFilter] 停用")
                && text.contains("player=Tester-A/") && text.contains("px=200 py=200 pz=0") && text.contains("obj=IsoZombie@260,200,0"));
        long engaged = c[1];
        px.setInt(pd, 200);
        hook = drift;
        hookOn = ANY_VISIBLE;
        String after = run(world, Call.plain());
        expect("停用後回原版迭代器：engaged 不再增加、同樣的漂移下原版處理到 B",
                PlayerLosFilter.countersForTest()[1] == engaged && after.startsWith("thrown=- ") && EVENTS.contains("tzsp " + id(b)));
        OUT.println("  " + PlayerLosFilter.beatLine());
    }

    /**
     * 鎖相：players 位玩家每輪照固定順序各跑一次 updateLOS（各自的 PlayerData、彼此相距 300 格），只有第 k 位
     * （k 選在舊的「序號 &63」抽樣下永遠抽不到的位置）在迴圈中漂移。必須在有限輪內被比對抓到並停用，停用行是第 k 位。
     */
    private static void phase(int players, ByteArrayOutputStream log) throws Exception {
        int k = players == 64 ? 5 : 4;
        boolean oldNever = true;
        for (int r = 0; r < 64; r++) {
            oldNever &= ((long) r * players + k + 1) % 64 != 0;
        }
        expect("前提：舊的序號 &63 抽樣下，" + players + " 人輪流時第 " + k + " 位永遠抽不到比對", oldNever);
        Viewer[] vs = new Viewer[players];
        Object[] pds = new Object[players];
        World w = null;
        IsoZombie far = null;
        int base = 200 + 300 * k;
        for (int attempt = 0; attempt < 50 && w == null; attempt++) {
            for (int i = 0; i < players; i++) {
                vs[i] = viewer(200.5F + 300 * i, 200.5F, 0);
                vs[i].username = i == k ? "Tester-B" : "Tester-A";
            }
            World t = new World(vs[k], base, 200, 0);
            for (int i = 0; i < 10 + attempt; i++) {
                t.add(t.zombie(base + 3 + i % 5, 201 + i / 5, 0, false, true));
            }
            IsoZombie b = t.zombie(base + 60, 200, 0, false, true);
            t.add(b);
            for (Viewer v : vs) {
                t.add(v);
            }
            t.install(true);
            int firstSeen = -1;
            int bAt = -1;
            int i = 0;
            for (IsoMovingObject o : t.set) {
                if (o instanceof IsoZombie z && Boolean.TRUE.equals(t.seen.get(z.getCurrentSquare())) && firstSeen < 0) {
                    firstSeen = i;
                }
                if (o == b) {
                    bAt = i;
                }
                i++;
            }
            if (firstSeen >= 0 && firstSeen < bAt) {
                w = t;
                far = b;
            }
        }
        expect("鎖相世界排得出", w != null);
        World world = w;
        ArrayList<Object> main = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            pds[i] = i == k ? world.data : PD_NEW.newInstance(vs[i]);
            setField(pds[i], "px", 200 + 300 * i);
            setField(pds[i], "py", 200);
            setField(pds[i], "pz", 0);
            PD_STATUS.set(pds[i], BUSY);
            main.add(pds[i]);
        }
        PLAYERS_MAIN.set(ServerLOS.instance, main);
        IsoWorld.instance.currentCell = cell;
        setField(cell, "objectList", world.set);
        Object pd = pds[k];
        boolean[][][] visible = (boolean[][][]) getField(pd, "visible");
        Runnable drift = () -> {
            try {
                setField(pd, "px", base + 30);
                for (boolean[][] col : visible) {
                    for (int y = 45; y <= 55; y++) {
                        col[y][LosUtil.sizeZ / 2] = true;
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        GameTime.getInstance().setMultiplier(0.5F);
        int rounds = 0;
        int otherAudits = 0;
        int kSkipped = 0;
        int kQuiet = 0;
        Rng rng = new Rng(PlayerLosFilter.SEED);
        // 照同一種子預測：第 k 位在被過濾過、其他玩家也被比對過之前，抽到比對的那幾次不漂移（必須零 mismatch）
        while (rounds < 3000 && !PlayerLosFilter.disabledForTest()) {
            rounds++;
            for (int i = 0; i < players && !PlayerLosFilter.disabledForTest(); i++) {
                int kind = rng.next();
                vs[i].resetForRun();
                EVENTS.clear();
                boolean quiet = i == k && kind == 0 && (kSkipped == 0 || otherAudits == 0);
                if (i == k) {
                    setField(pd, "px", base);
                    hook = quiet ? null : drift;
                    hookOn = quiet ? null : ANY_VISIBLE;
                }
                vs[i].updateLOS();
                hook = null;
                hookOn = null;
                if (i != k && kind == 0) {
                    otherAudits++;
                }
                if (quiet) {
                    kQuiet++;
                }
                if (i == k && kind > 1 && !EVENTS.contains("tzsp " + id(far))) {
                    kSkipped++;
                }
            }
        }
        long[] c = PlayerLosFilter.countersForTest();
        String text = log.toString(StandardCharsets.UTF_8);
        OUT.println("  phase" + players + "：第 " + rounds + " 輪抓到（其他玩家先被比對 " + otherAudits + " 次、第 " + k + " 位被過濾 " + kSkipped
                + " 次、不漂移的比對 " + kQuiet + " 次）");
        expect(players + " 人輪流、只有第 " + k + " 位漂移：在有限輪內（" + rounds + " 輪）被比對抓到並停用，其他玩家的比對都沒有 mismatch",
                PlayerLosFilter.disabledForTest() && c[11] == 1 && rounds < 3000 && otherAudits > 0 && kSkipped > 0
                        && c[10] == otherAudits + kQuiet + 1 && c[16] == rng.x);
        expect("停用行是第 " + k + " 位（Tester-B）", text.contains("[MinidoracatJavaPatch][PlayerLosFilter] 停用")
                && text.contains("player=Tester-B/") && text.contains("px=" + base + " py=200 pz=0"));
    }

    static final Object ANY_VISIBLE = new Object();

    /** HashSet，記下最後一次 iterator() 交出的物件（驗 observe 回傳的就是原版迭代器本身）；迭代行為與 HashSet 相同。 */
    static final class RecSet<E> extends HashSet<E> {
        Iterator<E> last;

        @Override
        public Iterator<E> iterator() {
            last = super.iterator();
            return last;
        }
    }

    // ---- 執行與紀錄 ----

    private static String run(World w, Call call) throws Exception {
        w.prepare(call);
        EVENTS.clear();
        if (call.hookTarget != null) {
            hookOn = call.hookTarget;
            hook = w.editor(call.edit, call.hookTarget);
        } else if (hookOn != ANY_VISIBLE) {
            hookOn = null;
            hook = null;
        }
        GameTime.getInstance().setMultiplier(0.5F);
        String thrown = "-";
        try {
            w.viewer.updateLOS();
        } catch (RuntimeException e) {
            thrown = e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        hookOn = null;
        hook = null;
        return w.result(thrown);
    }

    private static String runOffMain(World w, Call call) throws Exception {
        String[] out = new String[1];
        Exception[] err = new Exception[1];
        Thread t = new Thread(() -> {
            try {
                out[0] = run(w, call);
            } catch (Exception e) {
                err[0] = e;
            }
        });
        t.start();
        t.join();
        if (err[0] != null) {
            throw err[0];
        }
        return out[0];
    }

    // ---- 世界 ----

    static final class World {
        final Viewer viewer;
        final List<IsoMovingObject> order = new ArrayList<>();
        final List<Object> extras = new ArrayList<>();
        final int px;
        final int py;
        final int pz;
        Set<IsoMovingObject> set;
        Object data;
        boolean[][][] visible;
        final Map<IsoGridSquare, Boolean> seen = new IdentityHashMap<>();
        boolean dupData;
        /** 重複 PlayerData 時第二筆的 visible（全 false）；第二筆的視窗與第一筆互斥（+300 格）。 */
        boolean[][][] dupVisible;

        World(Viewer v, int px, int py, int pz) {
            this.viewer = v;
            this.px = px;
            this.py = py;
            this.pz = pz;
        }

        static World random(Random r) throws Exception {
            float vx = 300 + r.nextInt(200) + r.nextFloat();
            float vy = 300 + r.nextInt(200) + r.nextFloat();
            int vz = r.nextInt(8) == 0 ? 1 : 0;
            Viewer v = viewer(vx, vy, vz);
            v.asleep = r.nextInt(10) == 0;
            v.range = 2.0F + r.nextFloat() * 10;
            // LOS 執行緒上次算的位置可能落後玩家幾格
            World w = new World(v, (int) vx + r.nextInt(7) - 3, (int) vy + r.nextInt(7) - 3, vz);
            int minX = w.px - 48;
            int minY = w.py - 48;
            int minZ = w.pz - LosUtil.sizeZ / 2;
            int zombies = r.nextInt(10) == 0 ? 0 : r.nextInt(120);
            for (int i = 0; i < zombies; i++) {
                int[] p = w.place(r, minX, minY, minZ);
                IsoZombie z = w.zombie(p[0], p[1], p[2], r.nextInt(20) == 0, r.nextInt(3) == 0);
                if (r.nextInt(25) == 0) {
                    setField(z, "reanimatedForGrappleOnly", true);
                    setField(z, "grappleable", new zombie.core.skinnedmodel.BaseGrappleable(z));
                }
                w.add(z);
            }
            for (int i = r.nextInt(6); i > 0; i--) {
                int[] p = w.place(r, minX, minY, minZ);
                w.add(w.target(p[0], p[1], p[2], r.nextInt(20) == 0));
            }
            for (int i = r.nextInt(5); i > 0; i--) {
                int[] p = w.place(r, minX, minY, minZ);
                TestAnimal a = w.animal(p[0], p[1], p[2]);
                if (r.nextInt(3) == 0) {
                    v.attached().add(a);
                }
                w.add(a);
            }
            for (int i = r.nextInt(3); i > 0; i--) {
                int[] p = w.place(r, minX, minY, minZ);
                IsoSurvivor s = alloc(IsoSurvivor.class);
                w.position(s, p[0], p[1], p[2], false);
                w.add(s);
            }
            if (r.nextBoolean()) {
                BaseVehicle b = alloc(BaseVehicle.class);
                w.position(b, (int) vx + 1, (int) vy, vz, false);
                w.add(b);
            }
            if (r.nextInt(4) == 0) {
                IsoPhysicsObject o = alloc(IsoPhysicsObject.class);
                w.position(o, (int) vx, (int) vy + 1, vz, false);
                w.add(o);
            }
            if (r.nextInt(20) != 0) {
                w.add(v);
            }
            if (r.nextInt(30) == 0) {
                w.extras.add(null);
            }
            if (r.nextInt(30) == 0) {
                w.extras.add("not-a-moving-object");
            }
            w.dupData = r.nextInt(15) == 0;
            w.install(r.nextInt(25) != 0);
            return w;
        }

        /** 30% 視窗邊界 ±1、z 邊界，40% 玩家附近，30% 遠處。 */
        int[] place(Random r, int minX, int minY, int minZ) {
            int k = r.nextInt(10);
            int x;
            int y;
            int z = pz + (r.nextInt(6) == 0 ? r.nextInt(3) - 1 : 0);
            if (k < 3) {
                int edge = new int[]{-1, 0, 95, 96}[r.nextInt(4)];
                int along = r.nextInt(98) - 1;
                if (r.nextBoolean()) {
                    x = minX + edge;
                    y = minY + along;
                } else {
                    x = minX + along;
                    y = minY + edge;
                }
                if (r.nextInt(5) == 0) {
                    z = minZ + new int[]{-1, 0, LosUtil.sizeZ - 1, LosUtil.sizeZ}[r.nextInt(4)];
                }
            } else if (k < 7) {
                x = (int) viewer.getX() + r.nextInt(17) - 8;
                y = (int) viewer.getY() + r.nextInt(17) - 8;
            } else {
                x = r.nextInt(900);
                y = r.nextInt(900);
            }
            return new int[]{x, y, z};
        }

        IsoZombie zombie(int x, int y, int z, boolean noSquare, boolean ghost) throws Exception {
            IsoZombie o = alloc(IsoZombie.class);
            position(o, x, y, z, noSquare);
            // 近處分支會呼叫 isFakeDead（讀 SandboxOptions）：測試 JVM 建不起來時，同樓層 7 格內的殭屍一律 ghost（原版先看 ghost）。
            // target 一律 null：target == viewer 時原版經 StateMachineComponent 判斷狀態，測試 JVM 建不起這個 ECS 元件。
            o.ghost = ghost || !fakeDeadOk && Math.abs(x + 0.5F - viewer.getX()) < 8 && Math.abs(y + 0.5F - viewer.getY()) < 8;
            setField(o, "forwardDirection", new Vector2(1, 0));
            return o;
        }

        Target target(int x, int y, int z, boolean noSquare) throws Exception {
            Target t = alloc(Target.class);
            position(t, x, y, z, noSquare);
            setField(t, "forwardDirection", new Vector2(0, 1));
            return t;
        }

        TestAnimal animal(int x, int y, int z) throws Exception {
            TestAnimal a = alloc(TestAnimal.class);
            position(a, x, y, z, false);
            setField(a, "forwardDirection", new Vector2(1, 1));
            return a;
        }

        void position(IsoMovingObject o, int x, int y, int z, boolean noSquare) throws Exception {
            setField(o, "x", x + 0.5F);
            setField(o, "y", y + 0.5F);
            setField(o, "z", (float) z);
            setField(o, "current", noSquare ? null : square(x, y, z));
            register(o);
        }

        void add(IsoMovingObject o) {
            order.add(o);
        }

        boolean visibleNear(IsoZombie z) {
            IsoGridSquare sq = z.getCurrentSquare();
            return sq != null && Boolean.TRUE.equals(seen.get(sq)) && !z.ghost;
        }

        /**
         * HashSet（正式服是 W47 的 HashSet 子類，迭代器相同；這裡不用它，vanilla classpath 沒有 dist）、PlayerData、visible：
         * 視窗內的物件格子三分之二看得見。
         */
        void install(boolean withData) throws Exception {
            set = new RecSet<>();
            set.addAll(order);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Set<Object> raw = (Set) set;
            raw.addAll(extras);
            data = withData ? PD_NEW.newInstance(viewer) : null;
            if (data != null) {
                setField(data, "px", px);
                setField(data, "py", py);
                setField(data, "pz", pz);
                visible = (boolean[][][]) getField(data, "visible");
                int n = 0;
                for (IsoMovingObject o : order) {
                    IsoGridSquare sq = o.getCurrentSquare();
                    if (sq != null) {
                        int x = sq.x - (px - 48);
                        int y = sq.y - (py - 48);
                        int z = sq.z - (pz - LosUtil.sizeZ / 2);
                        if (x >= 0 && x < 96 && y >= 0 && y < 96 && z >= 0 && z < LosUtil.sizeZ) {
                            boolean v = n++ % 3 != 0;
                            visible[x][y][z] = v;
                            seen.put(sq, v);
                        }
                    }
                }
            }
        }

        /** 每次呼叫前重建 viewer 與 ServerLOS 狀態（上一輪的結果不留下來）。 */
        void prepare(Call call) throws Exception {
            viewer.resetForRun();
            IsoWorld.instance.currentCell = cell;
            setField(cell, "objectList", set);
            ArrayList<Object> main = new ArrayList<>();
            if (data != null) {
                if (dupData) {
                    // 兩筆同一位玩家：第一筆是真的視窗與 visible，第二筆（data）視窗移開 300 格、visible 另一份全 false。
                    // findData 與 helper 都只看第一筆：取到第二筆的寫法會把看得見的目標全部略過，與原版不同。
                    Object first = PD_NEW.newInstance(viewer);
                    setField(first, "px", px);
                    setField(first, "py", py);
                    setField(first, "pz", pz);
                    setField(first, "visible", visible);
                    PD_STATUS.set(first, call.busy && !call.firstNotBusy ? BUSY : READY);
                    main.add(first);
                    if (dupVisible == null) {
                        dupVisible = new boolean[96][96][LosUtil.sizeZ];
                    }
                    setField(data, "px", px + 300);
                    setField(data, "py", py + 300);
                    setField(data, "visible", dupVisible);
                }
                PD_STATUS.set(data, call.busy ? BUSY : READY);
                main.add(PD_NEW.newInstance(alloc(Viewer.class)));
                main.add(data);
            }
            PLAYERS_MAIN.set(ServerLOS.instance, main);
        }

        Runnable editor(int edit, IsoMovingObject on) throws Exception {
            IsoZombie extra = zombie(900, 900, 0, false, false);
            return () -> {
                if (edit == 0) {
                    set.add(extra);
                } else if (edit == 1) {
                    set.remove(on);
                } else {
                    Object other = null;
                    for (Object o : (Set<?>) set) {
                        if (o != on && o != viewer && o != null) {
                            other = o;
                        }
                    }
                    if (other != null) {
                        set.remove(other);
                    }
                }
            };
        }

        String result(String thrown) throws Exception {
            StringBuilder sb = new StringBuilder("thrown=").append(thrown).append(" ev=").append(EVENTS);
            sb.append(" spotted=").append(ids(viewer.spottedStack())).append(" last=").append(ids(viewer.lastStack()));
            sb.append(" mult=").append(GameTime.getInstance().getMultiplier());
            StringBuilder dump = new StringBuilder();
            dump(dump, viewer);
            dump(dump, getField(viewer, "stats"));
            sb.append(" viewer=").append(sha(dump));
            StringBuilder objs = new StringBuilder();
            Set<Object> done = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            done.add(viewer);
            for (Object o : order) {
                if (done.add(o)) {
                    dump(objs, o);
                }
            }
            for (Object o : (Set<?>) set) {
                if (o instanceof IsoMovingObject && done.add(o)) {
                    dump(objs, o);
                }
            }
            sb.append(" objs=").append(sha(objs)).append(" n=").append(done.size());
            sb.append(" numSurv=").append(getField(viewer, "numSurvivorsInVicinity"))
                    .append(" lastSeen=").append(getField(viewer, "lastSeenZombieTime"))
                    .append(" ticks=").append(getField(viewer, "ticksSinceSeenZombie"))
                    .append(" closest=").append(viewer.closestZombie)
                    .append(" vis=").append(getField(getField(viewer, "stats"), "numVisibleZombies"));
            return sb.toString();
        }
    }

    static final class Call {
        boolean busy = true;
        /** 重複 PlayerData 時第一筆不是 BusyInMain、第二筆是：helper 必須 fallback（notBusy），不能改用第二筆。 */
        boolean firstNotBusy;
        IsoMovingObject hookTarget;
        int edit;

        static Call plain() {
            return new Call();
        }

        static Call random(Random r, World w) {
            Call c = new Call();
            c.busy = r.nextInt(10) != 0;
            c.firstNotBusy = w.dupData && r.nextBoolean();
            if (r.nextInt(12) == 0) {
                List<IsoMovingObject> near = new ArrayList<>();
                for (IsoMovingObject o : w.order) {
                    if (o instanceof IsoZombie z && w.visibleNear(z)) {
                        near.add(o);
                    }
                }
                if (!near.isEmpty()) {
                    c.hookTarget = near.get(r.nextInt(near.size()));
                    c.edit = r.nextInt(3);
                }
            }
            return c;
        }

        @Override
        public String toString() {
            return "busy=" + busy + (firstNotBusy ? " firstNotBusy" : "") + " hook=" + (hookTarget == null ? "-" : id(hookTarget) + "/" + edit);
        }
    }

    // ---- 測試物件（只覆寫出口）----

    static class Viewer extends IsoPlayer {
        float range;

        Viewer() {
            super(null);
        }

        @Override
        public void TestZombieSpotPlayer(IsoMovingObject o) {
            EVENTS.add("tzsp " + id(o));
            if (hook != null && (hookOn == o || hookOn == ANY_VISIBLE && o instanceof IsoZombie)) {
                Runnable h = hook;
                hook = null;
                h.run();
            }
            if (!(o instanceof IsoZombie)) {
                super.TestZombieSpotPlayer(o);
            }
        }

        @Override
        public float getDetectionRange() {
            return range;
        }

        @SuppressWarnings("unchecked")
        Stack<IsoMovingObject> spottedStack() throws Exception {
            return (Stack<IsoMovingObject>) getField(this, "spottedList");
        }

        @SuppressWarnings("unchecked")
        Stack<IsoMovingObject> lastStack() throws Exception {
            return (Stack<IsoMovingObject>) getField(this, "lastSpotted");
        }

        @SuppressWarnings("unchecked")
        List<IsoAnimal> attached() {
            try {
                return (List<IsoAnimal>) getField(this, "attachedAnimals");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void resetForRun() throws Exception {
            spottedStack().clear();
            spottedStack().add(null); // 上一輪殘留，驗證兩邊都先清掉
            lastStack().clear();
            setField(this, "stats", alloc(Stats.class));
            setField(this, "numSurvivorsInVicinity", 3);
            setField(this, "lastSeenZombieTime", 77.0);
            setField(this, "ticksSinceSeenZombie", 9);
            setField(this, "clearSpottedTimer", 1);
            setField(this, "timeSinceLastStab", 650.0F);
            closestZombie = 1.0E6F;
        }
    }

    static class Target extends IsoPlayer {
        Target() {
            super(null);
        }

        @Override
        public void spotted(IsoMovingObject other, boolean forced) {
            EVENTS.add("spotted " + id(this) + "<-" + id(other) + " " + forced);
        }
    }

    static class TestAnimal extends IsoAnimal {
        TestAnimal() {
            super(null);
        }

        @Override
        public void spotted(IsoMovingObject other, boolean forced) {
            EVENTS.add("spotted " + id(this) + "<-" + id(other) + " " + forced);
        }
    }

    private static Viewer viewer(float x, float y, int z) throws Exception {
        Viewer v = alloc(Viewer.class);
        register(v);
        setField(v, "x", x);
        setField(v, "y", y);
        setField(v, "z", (float) z);
        setField(v, "current", square((int) x, (int) y, z));
        setField(v, "spottedList", new Stack<IsoMovingObject>());
        setField(v, "lastSpotted", new Stack<IsoMovingObject>());
        setField(v, "attachedAnimals", new ArrayList<IsoAnimal>());
        setField(v, "forwardDirection", new Vector2(1, 0));
        setField(v, "remote", true);
        v.username = "Tester-A";
        setField(v, "cheats", new zombie.characters.PlayerCheats());
        v.range = 6.0F;
        return v;
    }

    private static final ColorInfo LIGHT = new ColorInfo(0.8F, 0.8F, 0.8F, 1.0F);
    private static final Object LIGHTING = Proxy.newProxyInstance(PlayerLosFilterTest.class.getClassLoader(),
            new Class<?>[]{IsoGridSquare.ILighting.class}, (proxy, m, a) -> {
                Class<?> t = m.getReturnType();
                if (t == ColorInfo.class) {
                    return LIGHT;
                }
                return t == boolean.class ? Boolean.FALSE : t == int.class ? 0 : t == float.class ? 0.0F : null;
            });

    private static IsoGridSquare square(int x, int y, int z) throws Exception {
        IsoGridSquare sq = alloc(IsoGridSquare.class);
        sq.x = x;
        sq.y = y;
        sq.z = z;
        IsoGridSquare.ILighting[] l = new IsoGridSquare.ILighting[4];
        java.util.Arrays.fill(l, LIGHTING);
        setField(sq, "lighting", l);
        setField(sq, "roomId", -1L);
        return sq;
    }

    // ---- 環境 ----

    private static void setup() throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        Field rand = zombie.core.random.RandAbstract.class.getDeclaredField("rand");
        rand.setAccessible(true);
        rand.set(zombie.core.random.RandStandard.INSTANCE, new Random(1L));
        GameServer.server = true;
        GameClient.client = false;
        GameServer.mainThread = Thread.currentThread();
        IsoWorld.instance = alloc(IsoWorld.class);
        cell = alloc(IsoCell.class);
        ServerLOS.instance = alloc(ServerLOS.class);
        PLAYERS_MAIN = ServerLOS.class.getDeclaredField("playersMain");
        PLAYERS_MAIN.setAccessible(true);
        Class<?> pd = Class.forName("zombie.network.ServerLOS$PlayerData");
        PD_NEW = pd.getDeclaredConstructor(IsoPlayer.class);
        PD_NEW.setAccessible(true);
        PD_STATUS = pd.getDeclaredField("status");
        PD_STATUS.setAccessible(true);
        for (Object c : PD_STATUS.getType().getEnumConstants()) {
            String n = ((Enum<?>) c).name();
            if (n.equals("BusyInMain")) {
                BUSY = c;
            } else if (n.equals("ReadyInMain")) {
                READY = c;
            }
        }
        try {
            alloc(IsoZombie.class).isFakeDead();
            fakeDeadOk = true;
        } catch (RuntimeException | LinkageError e) {
            fakeDeadOk = false;
        }
        // TestIfSeen 先比 GameTime.getViewDist()（ClimateManager.viewDistance.finalValue，測試 JVM 是 0：一律看不見）
        setField(getField(zombie.iso.weather.ClimateManager.getInstance(), "viewDistance"), "finalValue", 40.0F);
        OUT.println("  環境：isFakeDead " + (fakeDeadOk ? "可用" : "不可用（近處殭屍改 ghost）") + "、viewDist=" + GameTime.getInstance().getViewDist());
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    // ---- 工具 ----

    static void register(Object o) {
        if (!IDS.containsKey(o)) {
            IDS.put(o, IDS.size());
        }
    }

    static String id(Object o) {
        if (o == null) {
            return "null";
        }
        Integer i = IDS.get(o);
        return o.getClass().getSimpleName() + "#" + (i == null ? "?" : i);
    }

    private static List<String> ids(List<?> list) {
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(id(o));
        }
        return out;
    }

    private static final Map<Class<?>, List<Field>> PRIMS = new HashMap<>();

    /** 物件的全部基本型別、String 與基本型別陣列欄位（含父類別），依宣告順序。 */
    private static void dump(StringBuilder sb, Object o) throws Exception {
        if (o == null) {
            sb.append("null;");
            return;
        }
        List<Field> fs = PRIMS.computeIfAbsent(o.getClass(), c -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    Class<?> t = f.getType();
                    if (!Modifier.isStatic(f.getModifiers()) && (t.isPrimitive() || t == String.class
                            || t.isArray() && t.getComponentType().isPrimitive())) {
                        f.setAccessible(true);
                        out.add(f);
                    }
                }
            }
            return out;
        });
        sb.append(id(o)).append('{');
        for (Field f : fs) {
            Object v = f.get(o);
            sb.append(f.getName()).append('=');
            if (v != null && v.getClass().isArray()) {
                for (int i = 0, n = Array.getLength(v); i < n; i++) {
                    sb.append(Array.get(v, i)).append(',');
                }
            } else {
                sb.append(v);
            }
            sb.append(';');
        }
        sb.append('}');
    }

    private static String sha(StringBuilder sb) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(d, 0, 8);
    }

    private static Field find(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 往父類找
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    static void setField(Object o, String name, Object value) throws Exception {
        find(o.getClass(), name).set(o, value);
    }

    static Object getField(Object o, String name) throws Exception {
        return find(o.getClass(), name).get(o);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        OUT.println((ok ? "plf pass  " : "plf FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private PlayerLosFilterTest() {}
}
