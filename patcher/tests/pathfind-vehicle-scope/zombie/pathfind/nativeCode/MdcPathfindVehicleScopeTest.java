package zombie.pathfind.nativeCode;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.vehicles.BaseVehicle;

/**
 * W85 MdcPathfindVehicleScope 行為驗證（獨立 JVM；argv[0] 與 JVM 旗標相符）：
 * <ul>
 *   <li>{@code observe}／{@code off}／{@code on-probeoff}：同一串呼叫（啟動迴圈、更新器、遲滯移動、沒送過與送過的重新加入、
 *       移除、原版 addToWorld 內 CheckSwap 造成的 remove→add→add、非主執行緒的 add 與移除送過的車、探針看到非動物）送到
 *       替身 native 的序列與原版逐筆相同（on-probeoff 的非主執行緒呼叫改在主執行緒下一次入口照順序送）。observe 另驗
 *       vehNative 鏡像（含非主執行緒送的與移除的）與 M2 beat 欄位（與暴力列舉相同），off 零計數零 log，on-probeoff 從頭就回原版。</li>
 *   <li>{@code on}：只送範圍內的車；進入 ≤8 chunk、離開 &gt;12 chunk 的遲滯；1 秒限頻；沒送過的車重新加入或移除不送任務；
 *       送過的車重新加入用離開半徑；探針在 native findPath 之前就記下非動物，下一次更新補送延後的車各一次並之後全照原版。</li>
 *   <li>{@code on-throw}：helper 例外 → 補送延後的車各一次、之後停止簿記。</li>
 *   <li>{@code on-thread}：非主執行緒呼叫不直接呼叫原版，排進佇列由主執行緒照同一套規則決定：延後的車在背景重新加入、
 *       之後回原版時只補送一次；背景移除送過的車由主執行緒送 remove；回原版之後背景呼叫仍排隊、由主執行緒照原版送；
 *       已離開世界的延後車不補送。</li>
 *   <li>{@code on-race}：回原版補送延後車 A 的途中，背景執行緒對另一輛延後車 V 做 remove＋add：native 對 V 沒有重複 add。</li>
 *   <li>{@code on-reenter}：佇列依序 ADD(V)、REMOVE(V)，第一次範圍查詢丟例外：V 照順序送 add 再 remove，最後不在 native。</li>
 * </ul>
 * 替身 native 只覆寫 {@code addVehicle}／{@code removeVehicle}／{@code findPath} 記序列（沒有 JNI）；車是 allocateInstance 的
 * 原版 BaseVehicle（座標用反射寫），動物是覆寫座標與死亡的 IsoAnimal 子類別。
 */
public final class MdcPathfindVehicleScopeTest {
    private static final String TAG = "[MinidoracatJavaPatch][PathfindVehicleScope]";
    private static int failed;
    private static ByteArrayOutputStream logBuf;

    static final class FakeNative extends PathfindNative {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        int catAtFindPath = Integer.MIN_VALUE;
        /** 送出這一筆（例如 "add A"）之後在同一個執行緒上跑一次 {@link #hook}。 */
        String hookOn;
        Runnable hook;

        @Override
        public void addVehicle(BaseVehicle vehicle) {
            String call = "add " + name(vehicle);
            calls.add(call);
            if (hook != null && call.equals(hookOn)) {
                Runnable h = hook;
                hook = null;
                h.run();
            }
        }

        @Override
        public void removeVehicle(BaseVehicle vehicle) {
            calls.add("remove " + name(vehicle));
        }

        @Override
        public int findPath(PathFindRequest request, java.nio.ByteBuffer pathBB, boolean bRender) {
            catAtFindPath = MdcPathfindProbe.nonAnimalCat;
            return 1;
        }

        private static String name(BaseVehicle v) {
            return IDS.get(v) + (Thread.currentThread() == GameServer.mainThread ? "" : "@bg");
        }
    }
    /** BaseVehicle 是 final：用 allocateInstance 的原版車，座標與 removedFromWorld 用反射寫。 */
    private static final java.util.IdentityHashMap<BaseVehicle, String> IDS = new java.util.IdentityHashMap<>();

    static class TestAnimal extends IsoAnimal {
        float tx;
        float ty;
        boolean dead;
        boolean boom;

        TestAnimal() {
            super((IsoCell) null);
        }

        @Override
        public float getX() {
            if (boom) {
                throw new IllegalStateException("boom");
            }
            return tx;
        }

        @Override
        public float getY() {
            return ty;
        }

        @Override
        public boolean isDead() {
            return dead;
        }
    }

    private static FakeNative fake;
    private static IsoCell cell;
    private static Set<BaseVehicle> cellVehicles;
    private static Set<IsoMovingObject> objects;
    private static final List<String> VANILLA = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "observe" : args[0];
        zombie.core.random.RandStandard.INSTANCE.init();
        GameServer.server = true;
        GameServer.mainThread = Thread.currentThread();
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);

        parsing(mode);
        fake = new FakeNative();
        MdcPathfindVehicleScope.nativeForTest = fake;
        cell = alloc(IsoCell.class);
        cellVehicles = new LinkedHashSet<>();
        objects = new LinkedHashSet<>();
        setField(IsoCell.class, cell, "vehicles", cellVehicles);
        setField(IsoCell.class, cell, "objectList", objects);
        // 動物：A、B 同一個 chunk (125,125)，C 在 (128,140)（離所有車 ≥14 chunk，只讓 scopeChunks 的聯集重疊），死掉的 D 不算，
        // objectList 裡的車不是動物
        objects.add(animal(1000.5F, 1000.5F, false));
        objects.add(animal(1003F, 1006F, false));
        objects.add(animal(1024.5F, 1120.5F, false));
        objects.add(animal(1040F, 1000F, true));
        objects.add(vehicle("objVeh", 1041F, 1001F));
        // 已載入的 chunk：一格載入的 ServerCell 有 3 個 chunk、一格還沒載入的 2 個（不算）
        ServerMap.ServerCell loaded = new ServerMap.ServerCell();
        loaded.isLoaded = true;
        loaded.chunks[0][0] = alloc(IsoChunk.class);
        loaded.chunks[3][5] = alloc(IsoChunk.class);
        loaded.chunks[7][7] = alloc(IsoChunk.class);
        ServerMap.ServerCell loading = new ServerMap.ServerCell();
        loading.chunks[1][1] = alloc(IsoChunk.class);
        loading.chunks[2][2] = alloc(IsoChunk.class);
        ServerMap.instance.loadedCells.add(loaded);
        ServerMap.instance.loadedCells.add(loading);

        switch (mode) {
            case "observe", "off", "on", "on-probeoff" -> script(mode);
            case "on-throw" -> onThrow();
            case "on-thread" -> onThread();
            case "on-race" -> onRace();
            case "on-reenter" -> onReenter();
            default -> expect("未知 argv " + mode, false);
        }
        if (failed > 0) {
            System.out.println("MdcPathfindVehicleScopeTest（" + mode + "）失敗 " + failed + " 項");
            System.exit(1);
        }
        System.out.println("MdcPathfindVehicleScopeTest（" + mode + "）全數通過");
    }

    private static void parsing(String mode) {
        int want = switch (mode) {
            case "off" -> MdcPathfindVehicleScope.MODE_OFF;
            case "observe" -> MdcPathfindVehicleScope.MODE_OBSERVE;
            default -> MdcPathfindVehicleScope.MODE_ON;
        };
        expect("開關與 argv 相符（" + mode + "）", MdcPathfindVehicleScope.MODE == want);
        expect("parseMode：null／亂碼／2／observe＝observe，0／off／OFF＝off，1／on／ On ＝on",
                MdcPathfindVehicleScope.parseMode(null) == 2 && MdcPathfindVehicleScope.parseMode("x") == 2
                        && MdcPathfindVehicleScope.parseMode("2") == 2 && MdcPathfindVehicleScope.parseMode("observe") == 2
                        && MdcPathfindVehicleScope.parseMode("0") == 0 && MdcPathfindVehicleScope.parseMode("off") == 0
                        && MdcPathfindVehicleScope.parseMode("OFF") == 0 && MdcPathfindVehicleScope.parseMode("1") == 1
                        && MdcPathfindVehicleScope.parseMode("on") == 1 && MdcPathfindVehicleScope.parseMode(" On ") == 1);
        expect("parseRadius：null＝64、8 與 1024 照用、7／1025／-64／亂碼／空字串＝64、前後空白照用",
                MdcPathfindVehicleScope.parseRadius(null) == 64 && MdcPathfindVehicleScope.parseRadius("8") == 8
                        && MdcPathfindVehicleScope.parseRadius("1024") == 1024 && MdcPathfindVehicleScope.parseRadius("7") == 64
                        && MdcPathfindVehicleScope.parseRadius("1025") == 64 && MdcPathfindVehicleScope.parseRadius("-64") == 64
                        && MdcPathfindVehicleScope.parseRadius("abc") == 64 && MdcPathfindVehicleScope.parseRadius("") == 64
                        && MdcPathfindVehicleScope.parseRadius(" 96 ") == 96);
        expect("半徑換 chunk：R=64 進入 8、離開 12；R=60 進入 8、離開 12；R=32 進入 4、離開 6；R=8 進入 1、離開 2",
                MdcPathfindVehicleScope.kIn(64) == 8 && MdcPathfindVehicleScope.kOut(64) == 12
                        && MdcPathfindVehicleScope.kIn(60) == 8 && MdcPathfindVehicleScope.kOut(60) == 12
                        && MdcPathfindVehicleScope.kIn(32) == 4 && MdcPathfindVehicleScope.kOut(32) == 6
                        && MdcPathfindVehicleScope.kIn(8) == 1 && MdcPathfindVehicleScope.kOut(8) == 2);
        expect("出貨半徑（-Dmdc.pathfindVehicleScopeR 未設或亂碼）R=64、進入 8、離開 12、格網 12",
                MdcPathfindVehicleScope.R == 64 && MdcPathfindVehicleScope.K_IN == 8 && MdcPathfindVehicleScope.K_OUT == 12
                        && MdcPathfindVehicleScope.GRID == 12);
        expect("chunkOf：0→0、7.9→0、8→1、-0.5→-1、1000.5→125", MdcPathfindVehicleScope.chunkOf(0F) == 0
                && MdcPathfindVehicleScope.chunkOf(7.9F) == 0 && MdcPathfindVehicleScope.chunkOf(8F) == 1
                && MdcPathfindVehicleScope.chunkOf(-0.5F) == -1 && MdcPathfindVehicleScope.chunkOf(1000.5F) == 125);
    }

    /** 主要情境；observe／off／on-probeoff 的期望就是原版序列，on 另列。 */
    private static void script(String mode) throws Exception {
        boolean on = mode.equals("on");
        boolean observe = mode.equals("observe");
        boolean off = mode.equals("off");
        BaseVehicle v1 = vehicle("V1", 1040F, 1000F);  // chunk (130,125)，距離 5
        BaseVehicle v2 = vehicle("V2", 1200F, 1000F);  // 25
        BaseVehicle v3 = vehicle("V3", 1064F, 1000F);  // 8（剛好進入）
        BaseVehicle v4 = vehicle("V4", 1072F, 1000F);  // 9（剛好在外）
        cellVehicles.addAll(List.of(v1, v2, v3, v4));
        // S1 啟動迴圈：還沒有範圍資料
        add(v1);
        add(v2);
        add(v3);
        add(v4);
        expect(mode + " S1 啟動：" + (on ? "全部延後、不送" : "照原版送 4 輛"),
                on ? fake.calls.isEmpty() && deferred().equals(List.of("V1", "V2", "V3", "V4")) : fake.calls.equals(VANILLA));
        // S2 第一次更新：範圍內（≤8 chunk）補送
        Set<BaseVehicle> got = MdcPathfindVehicleScope.getVehicles(cell);
        expect(mode + " S2 getVehicles 回原版同一個 Set", got == cellVehicles);
        if (on) {
            expect("on S2 第一次更新補送 V1（5）、V3（8），V2（25）、V4（9）留著",
                    fake.calls.equals(List.of("add V1", "add V3")) && deferred().equals(List.of("V2", "V4")));
            expect("on S2 動物 3 隻（死的 D 與 objectList 裡的車不算）", MdcPathfindVehicleScope.animalsForTest() == 3);
        }
        // S3 遲滯：10、12 不移除；13 但 1 秒內不動；過 1 秒移除；回 10 不加；回 8 加
        int before = fake.calls.size();
        moveChunk(v1, 135);
        tick(true);
        moveChunk(v1, 137);
        tick(true);
        boolean held = fake.calls.size() == before;
        moveChunk(v1, 138);
        tick(false);
        boolean limited = fake.calls.size() == before;
        tick(true);
        boolean removed = fake.calls.size() == before + (on ? 1 : 0);
        moveChunk(v1, 135);
        tick(true);
        boolean stayed = fake.calls.size() == before + (on ? 1 : 0);
        moveChunk(v1, 133);
        tick(true);
        if (on) {
            expect("on S3 遲滯：距離 10、12 不移除；13 在 1 秒內不動、過了才移除；回 10 不加、回 8 才加",
                    held && limited && removed && stayed
                            && fake.calls.subList(before, fake.calls.size()).equals(List.of("remove V1", "add V1")));
        } else {
            expect(mode + " S3 更新器不送任何任務", fake.calls.size() == before);
        }
        // S4 沒送過的車重新加入（scriptReloaded：remove 緊接 add）
        before = fake.calls.size();
        remove(v2);
        add(v2);
        if (on) {
            expect("on S4 沒送過的 V2 重新加入：不送任何任務、仍延後", fake.calls.size() == before
                    && deferred().equals(List.of("V4", "V2")));
        }
        // S5 送過的車重新加入用離開半徑（11 ≤ 12 照送）；新車在 11 延後
        moveChunk(v3, 136);
        before = fake.calls.size();
        remove(v3);
        add(v3);
        BaseVehicle v5 = vehicle("V5", 1088F, 1008F); // (136,126)，距離 11
        cellVehicles.add(v5);
        add(v5);
        if (on) {
            expect("on S5 送過的 V3 在 11 重新加入照送 remove＋add（用離開半徑）；新車 V5 在 11 延後",
                    fake.calls.subList(before, fake.calls.size()).equals(List.of("remove V3", "add V3"))
                            && deferred().equals(List.of("V4", "V2", "V5")));
        }
        // S6 移除：沒送過的 V4 不送任務，送過的 V1 照送
        before = fake.calls.size();
        remove(v4);
        cellVehicles.remove(v4);
        remove(v1);
        cellVehicles.remove(v1);
        if (on) {
            expect("on S6 移除沒送過的 V4 不送任務、移除送過的 V1 照送",
                    fake.calls.subList(before, fake.calls.size()).equals(List.of("remove V1"))
                            && deferred().equals(List.of("V2", "V5")));
            expect("on S6 計數 enter/leave/adds/removes/skipAdd/skipRemove＝3/1/4/3/6/2（實際 "
                            + java.util.Arrays.toString(MdcPathfindVehicleScope.countsForTest()) + "）",
                    java.util.Arrays.equals(java.util.Arrays.copyOf(MdcPathfindVehicleScope.countsForTest(), 6),
                            new long[] {3, 1, 4, 3, 6, 2}));
        }
        if (!off) {
            beat(mode, on ? 1 : 3);
        }
        // S6b 原版 addToWorld → createPhysics → OnSpawnVehicleStart → CheckSwap → scriptReloaded：remove→add→add（同一輛車）。
        // observe／off 必須照原版送三筆（原版自己的重複 add，不在 observe 修）；on 的 remove 是沒送過的車不送，兩個 add 照送。
        BaseVehicle v9 = vehicle("V9", 1040F, 1016F); // (130,127)，距離 5
        cellVehicles.add(v9);
        before = fake.calls.size();
        remove(v9);
        add(v9);
        add(v9);
        expect(mode + " S6b CheckSwap 的 remove→add→add：" + (on ? "on 不送沒送過的 remove、兩個 add 照送" : "照原版三筆"),
                fake.calls.subList(before, fake.calls.size()).equals(on ? List.of("add V9", "add V9")
                        : List.of("remove V9", "add V9", "add V9")));
        // S7 非動物請求者（mover null＝other）經真探針 findPath：探針在 native findPath 之前就記下；下一次 getVehicles 回原版
        int r = MdcPathfindProbe.findPath(fake, new PathFindRequest(), null, false);
        boolean probeOn = MdcPathfindProbe.ENABLED;
        expect(mode + " S7 探針" + (probeOn ? "在 native findPath 被呼叫時已記下 other" : " off：不記"),
                r == 1 && fake.catAtFindPath == (probeOn ? MdcPathfindProbe.CAT_OTHER : -1));
        before = fake.calls.size();
        MdcPathfindVehicleScope.getVehicles(cell);
        if (on) {
            expect("on S7 探針看到 other → 補送延後的 V2、V5 各一次，原因 nonAnimal:other",
                    fake.calls.subList(before, fake.calls.size()).equals(List.of("add V2", "add V5"))
                            && deferred().isEmpty() && MdcPathfindVehicleScope.fallenForTest()
                            && "nonAnimal:other".equals(MdcPathfindVehicleScope.fallbackReasonForTest()));
        }
        // S8 非主執行緒：新車 V6 的 add、送過的 V5 的 remove。observe 照原版時機直接送、主執行緒排空時補簿記；
        // on（含 on-probeoff）一律排隊，由主執行緒下一次入口照順序送（回原版之後全部送）
        BaseVehicle v6 = vehicle("V6", 3000F, 3000F);
        background(() -> {
            MdcPathfindVehicleScope.addVehicle(fake, v6);
            MdcPathfindVehicleScope.removeVehicle(fake, v5);
        });
        String bg = observe || off ? "@bg" : "";
        VANILLA.add("add V6" + bg);
        VANILLA.add("remove V5" + bg);
        cellVehicles.remove(v5);
        moveChunk(v3, 160);
        before = fake.calls.size();
        tick(true);
        BaseVehicle v8 = vehicle("V8", 3000F, 3000F);
        add(v8);
        remove(v8);
        if (on) {
            expect("on S8 回原版後：非主執行緒呼叫排隊、主執行緒下一次更新照順序送；更新器不移除；遠處新車照送",
                    fake.calls.subList(before, fake.calls.size()).equals(
                            List.of("add V6", "remove V5", "add V8", "remove V8"))
                            && MdcPathfindVehicleScope.offThreadForTest() == 2);
            expect("on 全序列", fake.calls.equals(List.of("add V1", "add V3", "remove V1", "add V1", "remove V3", "add V3",
                    "remove V1", "add V9", "add V9", "add V2", "add V5", "add V6", "remove V5", "add V8", "remove V8")));
            expect("on vehNative＝送進 native 的淨集合（V3、V9、V2、V6；背景移除的 V5 不在）",
                    names(MdcPathfindVehicleScope.sentForTest()).equals(Set.of("V3", "V9", "V2", "V6")));
            List<String> rows = rows();
            expect("on log：首次生效 1 行、回原版 1 行（補送 2 輛）", count(rows, "首次生效 mode=on R=64") == 1
                    && count(rows, "回原版（nonAnimal:other）：補送 2 輛延後的載具") == 1 && rows.size() == 2);
        } else {
            expect(mode + " 全序列與原版逐筆相同（" + fake.calls.size() + " 筆）", fake.calls.equals(VANILLA)
                    && VANILLA.size() == 18);
        }
        if (mode.equals("on-probeoff")) {
            expect("on-probeoff：非主執行緒呼叫也在主執行緒送（背景執行緒從不直接呼叫原版）",
                    fake.calls.stream().noneMatch(c -> c.endsWith("@bg")) && MdcPathfindVehicleScope.offThreadForTest() == 2
                            && MdcPathfindVehicleScope.pendingForTest() == 0);
        }
        if (observe) {
            String line = MdcPathfindVehicleScope.beatForTest(cellVehicles);
            expect("observe vehNative 鏡像＝原版 vehicleState（V2、V3、V9 與背景送的 V6；背景移除的 V5 不在），beat 也是 4",
                    names(MdcPathfindVehicleScope.sentForTest()).equals(Set.of("V2", "V3", "V9", "V6"))
                            && line.contains(" vehNative=4 ") && MdcPathfindVehicleScope.pendingForTest() == 0
                            && MdcPathfindVehicleScope.deferredForTest().isEmpty()
                            && MdcPathfindVehicleScope.offThreadForTest() == 2 && !MdcPathfindVehicleScope.fallenForTest());
            List<String> rows = rows();
            expect("observe log：只有首次生效 1 行", rows.size() == 1 && count(rows, "首次生效 mode=observe R=64") == 1);
        }
        if (off) {
            expect("off：零計數、不簿記、零 log", java.util.Arrays.equals(MdcPathfindVehicleScope.countsForTest(), new long[7])
                    && MdcPathfindVehicleScope.sentForTest().isEmpty() && MdcPathfindVehicleScope.offThreadForTest() == 0
                    && MdcPathfindVehicleScope.pendingForTest() == 0
                    && MdcPathfindVehicleScope.anomaliesForTest() == 0 && rows().isEmpty());
        }
        if (mode.equals("on-probeoff")) {
            List<String> rows = rows();
            expect("on-probeoff：探針關掉從頭就回原版（probeOff）、從不延後、首次生效行註明",
                    MdcPathfindVehicleScope.fallenForTest() && "probeOff".equals(MdcPathfindVehicleScope.fallbackReasonForTest())
                            && rows.size() == 1 && count(rows, "已回原版：probeOff") == 1);
        }
        expect(mode + " anomalies 0", MdcPathfindVehicleScope.anomaliesForTest() == 0);
    }

    /** M2 beat 欄位：與暴力列舉相同。 */
    private static void beat(String mode, int vehNative) {
        List<int[]> animalChunks = new ArrayList<>();
        int animals = 0;
        for (IsoMovingObject o : objects) {
            if (o instanceof TestAnimal a && !a.dead) {
                animals++;
                int[] c = {MdcPathfindVehicleScope.chunkOf(a.tx), MdcPathfindVehicleScope.chunkOf(a.ty)};
                if (animalChunks.stream().noneMatch(x -> x[0] == c[0] && x[1] == c[1])) {
                    animalChunks.add(c);
                }
            }
        }
        int[] scope = new int[3];
        int[] near = new int[3];
        int[] ks = {4, 8, 12};
        for (int j = 0; j < 3; j++) {
            Set<Long> area = new HashSet<>();
            for (int[] c : animalChunks) {
                for (int dx = -ks[j]; dx <= ks[j]; dx++) {
                    for (int dy = -ks[j]; dy <= ks[j]; dy++) {
                        area.add(MdcPathfindVehicleScope.key(c[0] + dx, c[1] + dy));
                    }
                }
            }
            scope[j] = area.size();
            for (BaseVehicle bv : cellVehicles) {
                int vx = MdcPathfindVehicleScope.chunkOf(bv.getX());
                int vy = MdcPathfindVehicleScope.chunkOf(bv.getY());
                int ok = 0;
                for (int[] c : animalChunks) {
                    if (Math.max(Math.abs(c[0] - vx), Math.abs(c[1] - vy)) <= ks[j]) {
                        ok = 1;
                    }
                }
                near[j] += ok;
            }
        }
        String want = " mode=" + (mode.equals("observe") ? "observe" : "on") + " animals=" + animals
                + " animalChunks=" + animalChunks.size() + " scopeChunks@32/64/96=" + scope[0] + "/" + scope[1] + "/" + scope[2]
                + " loadedChunks=3 vehicles=" + cellVehicles.size() + " vehNearAnimals@32/64/96=" + near[0] + "/" + near[1]
                + "/" + near[2] + " vehNative=" + vehNative
                + (mode.equals("on") ? " deferred=2 enter=3 leave=1 adds=4 removes=3 skipAdd=6 skipRemove=2 fallback=0"
                        : mode.equals("on-probeoff") ? " deferred=0 enter=0 leave=0 adds=7 removes=4 skipAdd=0 skipRemove=0"
                                + " fallback=1(probeOff)" : "")
                + " scopeUsAvg=";
        String line = MdcPathfindVehicleScope.beatForTest(cellVehicles);
        String body = line.substring(line.indexOf(" mode="), line.indexOf(" scopeUsAvg=") + " scopeUsAvg=".length());
        expect(mode + " beat 欄位與暴力列舉相同（聯集有重疊：" + scope[1] + " < 2×289）\n      want" + want + "\n      got " + body,
                body.equals(want) && scope[1] < 2 * 289 && scope[0] == 2 * 81
                        && line.matches(".* offThread=0 anomalies=0 beatUs=\\d+$"));
    }

    /** helper 例外：補送延後的車各一次，這輛照原版，之後停止簿記。 */
    private static void onThrow() throws Exception {
        BaseVehicle v1 = vehicle("V1", 1040F, 1000F);
        BaseVehicle v2 = vehicle("V2", 1200F, 1000F);
        cellVehicles.addAll(List.of(v1, v2));
        add(v1);
        add(v2);
        tick(true);
        TestAnimal bad = animal(1100F, 1100F, false);
        bad.boom = true;
        objects.add(bad);
        tick(true);
        expect("on-throw：更新器讀動物座標丟例外 → 補送延後的 V2 一次，回原版並停止簿記",
                fake.calls.equals(List.of("add V1", "add V2")) && MdcPathfindVehicleScope.fallenForTest()
                        && MdcPathfindVehicleScope.disabledForTest() && MdcPathfindVehicleScope.anomaliesForTest() == 1
                        && MdcPathfindVehicleScope.deferredForTest().isEmpty()
                        && MdcPathfindVehicleScope.fallbackReasonForTest().equals(
                        "exception@update: java.lang.IllegalStateException: boom"));
        int before = fake.calls.size();
        BaseVehicle v7 = vehicle("V7", 3000F, 3000F);
        cellVehicles.add(v7);
        remove(v2);
        add(v2);
        add(v7);
        tick(true);
        expect("on-throw：之後 remove＋add 與遠處新車照原版、更新器不動（動物仍會丟例外也不再碰）",
                fake.calls.subList(before, fake.calls.size()).equals(List.of("remove V2", "add V2", "add V7")));
        List<String> rows = rows();
        expect("on-throw log：首次生效、回原版（補送 1 輛）各 1 行", rows.size() == 2
                && count(rows, "回原版（exception@update: java.lang.IllegalStateException: boom）：補送 1 輛延後的載具") == 1);
    }

    /**
     * 非主執行緒（on，還沒回原版）：背景呼叫不直接呼叫原版，排進佇列，主執行緒排空時照同一套規則決定。
     * (a) 延後的車在背景重新加入 → 兩筆都不送、仍延後；(b) 背景移除送過的車 → 主執行緒送 remove；(c) 背景移除延後的車不送；
     * (d) 回原版只補送一次（不重複 add）、略過已離開世界的；(e) 回原版之後背景呼叫仍排隊、由主執行緒照原版送並簿記。
     * d1a0a15 之前的程式在 (a) 會從背景直接送 remove／add、回原版時又補送一次 add（native 收到重複 id）。
     */
    private static void onThread() throws Exception {
        BaseVehicle v1 = vehicle("V1", 1040F, 1000F);
        BaseVehicle v2 = vehicle("V2", 1200F, 1000F);
        BaseVehicle v3 = vehicle("V3", 1300F, 1000F);
        BaseVehicle v4 = vehicle("V4", 1400F, 1000F);
        cellVehicles.addAll(List.of(v1, v2, v3, v4));
        add(v1);
        add(v2);
        add(v3);
        add(v4);
        tick(true);
        expect("on-thread 起點：補送範圍內的 V1，V2–V4 延後", fake.calls.equals(List.of("add V1"))
                && deferred().equals(List.of("V2", "V3", "V4")));
        // (a)
        background(() -> {
            MdcPathfindVehicleScope.removeVehicle(fake, v2);
            MdcPathfindVehicleScope.addVehicle(fake, v2);
        });
        boolean queuedA = fake.calls.size() == 1 && MdcPathfindVehicleScope.pendingForTest() == 2;
        tick(false);
        expect("on-thread (a) 背景重新加入延後的 V2：背景不呼叫原版、排 2 筆；主執行緒排空（不受 1 秒限頻）後兩筆都不送、V2 仍延後",
                queuedA && fake.calls.equals(List.of("add V1")) && MdcPathfindVehicleScope.pendingForTest() == 0
                        && deferred().equals(List.of("V3", "V4", "V2")) && !MdcPathfindVehicleScope.fallenForTest());
        // (b)
        background(() -> MdcPathfindVehicleScope.removeVehicle(fake, v1));
        cellVehicles.remove(v1);
        boolean queuedB = fake.calls.size() == 1;
        tick(false);
        expect("on-thread (b) 背景移除送過的 V1：背景不呼叫原版；主執行緒排空時在主執行緒送 remove V1、V1 離開 vehNative、沒有回原版",
                queuedB && fake.calls.equals(List.of("add V1", "remove V1"))
                        && !MdcPathfindVehicleScope.sentForTest().contains(v1)
                        && !MdcPathfindVehicleScope.fallenForTest() && MdcPathfindVehicleScope.offThreadForTest() == 3);
        // (c)(d)
        background(() -> MdcPathfindVehicleScope.removeVehicle(fake, v3));
        cellVehicles.remove(v3);
        setField(BaseVehicle.class, v4, "removedFromWorld", true);
        MdcPathfindProbe.findPath(fake, new PathFindRequest(), null, false);
        tick(false);
        long v2Adds = fake.calls.stream().filter("add V2"::equals).count();
        expect("on-thread (c)(d) 背景移除延後的 V3 不送；回原版只補送 V2 一次（背景重新加入沒有造成重複 add）、略過已離開世界的 V4",
                fake.calls.equals(List.of("add V1", "remove V1", "add V2")) && v2Adds == 1
                        && "nonAnimal:other".equals(MdcPathfindVehicleScope.fallbackReasonForTest())
                        && count(rows(), "回原版（nonAnimal:other）：補送 1 輛延後的載具，略過已離開世界 1 輛") == 1);
        // (e)
        BaseVehicle v5 = vehicle("V5", 3000F, 3000F);
        background(() -> MdcPathfindVehicleScope.addVehicle(fake, v5));
        tick(false);
        expect("on-thread (e) 回原版之後背景 add 仍排隊、由主執行緒照原版送，V5 記進 vehNative",
                fake.calls.equals(List.of("add V1", "remove V1", "add V2", "add V5"))
                        && MdcPathfindVehicleScope.sentForTest().contains(v5) && MdcPathfindVehicleScope.pendingForTest() == 0
                        && MdcPathfindVehicleScope.offThreadForTest() == 5 && MdcPathfindVehicleScope.anomaliesForTest() == 0);
    }

    /**
     * 回原版交接的競態：補送延後車 A 時（替身 native 的回呼裡），背景執行緒對另一輛延後車 V 做 remove＋add，再讓回原版繼續。
     * d1a0a15 先設回原版才補送，背景看到回原版就直接送 remove V／add V，回原版接著又補送 add V → native 收到重複 add。
     */
    private static void onRace() throws Exception {
        BaseVehicle n = vehicle("N", 1040F, 1000F);
        BaseVehicle a = vehicle("A", 1200F, 1000F);
        BaseVehicle v = vehicle("V", 1300F, 1000F);
        cellVehicles.addAll(List.of(n, a, v));
        add(n);
        add(a);
        add(v);
        tick(true);
        expect("on-race 起點：送 N，A、V 延後", fake.calls.equals(List.of("add N")) && deferred().equals(List.of("A", "V")));
        fake.hookOn = "add A";
        fake.hook = () -> {
            try {
                background(() -> {
                    MdcPathfindVehicleScope.removeVehicle(fake, v);
                    MdcPathfindVehicleScope.addVehicle(fake, v);
                });
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        MdcPathfindProbe.findPath(fake, new PathFindRequest(), null, false);
        tick(false);
        tick(false);
        List<String> dup = new ArrayList<>();
        Set<String> inNative = replay(fake.calls, dup);
        expect("on-race 回原版補送 A 時背景對延後車 V 做 remove＋add：native 對 V 沒有重複 add、最後 N／A／V 各在一次"
                        + "（實際 " + fake.calls + "）",
                fake.hook == null && dup.isEmpty() && inNative.equals(Set.of("N", "A", "V"))
                        && names(MdcPathfindVehicleScope.sentForTest()).equals(Set.of("N", "A", "V"))
                        && MdcPathfindVehicleScope.pendingForTest() == 0 && MdcPathfindVehicleScope.anomaliesForTest() == 0
                        && "nonAnimal:other".equals(MdcPathfindVehicleScope.fallbackReasonForTest()));
        expect("on-race 背景執行緒沒有直接呼叫原版、委派順序 add N, add A, add V, remove V, add V",
                fake.calls.equals(List.of("add N", "add A", "add V", "remove V", "add V")));
    }

    /**
     * 例外路徑的重入：佇列依序 ADD(V)、REMOVE(V)，對第一次範圍查詢注入例外（V 所在格的動物 chunk 清單第一次讀就丟）。
     * d1a0a15 在 ADD(V) 的錯誤處理裡回原版並排空佇列，先送了後面的 remove V，回到外層才送 add V → V 留在 native。
     */
    private static void onReenter() throws Exception {
        BaseVehicle n = vehicle("N", 1040F, 1000F);
        cellVehicles.add(n);
        add(n);
        tick(true);
        BaseVehicle v = vehicle("V", 1601F, 1601F); // chunk (200,200)，離動物 75 chunk
        cellVehicles.add(v);
        int bucket = Math.floorDiv(200 - MdcPathfindVehicleScope.K_IN, MdcPathfindVehicleScope.GRID);
        Field f = MdcPathfindVehicleScope.class.getDeclaredField("BUCKETS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        gnu.trove.map.hash.TLongObjectHashMap<gnu.trove.list.array.TLongArrayList> buckets =
                (gnu.trove.map.hash.TLongObjectHashMap<gnu.trove.list.array.TLongArrayList>) f.get(null);
        int[] thrown = new int[1];
        buckets.put(MdcPathfindVehicleScope.key(bucket, bucket), new gnu.trove.list.array.TLongArrayList() {
            @Override
            public int size() {
                if (thrown[0]++ == 0) {
                    throw new IllegalStateException("range");
                }
                return 0;
            }
        });
        background(() -> {
            MdcPathfindVehicleScope.addVehicle(fake, v);
            MdcPathfindVehicleScope.removeVehicle(fake, v);
        });
        cellVehicles.remove(v);
        tick(false);
        tick(false);
        List<String> dup = new ArrayList<>();
        Set<String> inNative = replay(fake.calls, dup);
        expect("on-reenter 佇列 ADD(V)、REMOVE(V)，第一次範圍查詢丟例外：照順序送 add V 再 remove V，最後 native 沒有 V"
                        + "（實際 " + fake.calls + "）",
                thrown[0] >= 1 && fake.calls.equals(List.of("add N", "add V", "remove V")) && dup.isEmpty()
                        && inNative.equals(Set.of("N")) && MdcPathfindVehicleScope.pendingForTest() == 0);
        expect("on-reenter 例外後回原版並停止簿記（anomalies 1、原因 exception@addVehicle）",
                MdcPathfindVehicleScope.fallenForTest() && MdcPathfindVehicleScope.disabledForTest()
                        && MdcPathfindVehicleScope.anomaliesForTest() == 1
                        && "exception@addVehicle: java.lang.IllegalStateException: range".equals(
                        MdcPathfindVehicleScope.fallbackReasonForTest()));
    }

    /** 照呼叫序列模擬 native 裡的載具集合（去掉 @bg）；已在集合裡又 add 記進 {@code dup}。 */
    private static Set<String> replay(List<String> calls, List<String> dup) {
        Set<String> in = new HashSet<>();
        for (String c : calls) {
            String id = c.substring(c.indexOf(' ') + 1).replace("@bg", "");
            if (c.startsWith("add ")) {
                if (!in.add(id)) {
                    dup.add(id);
                }
            } else {
                in.remove(id);
            }
        }
        return in;
    }

    // ---- 工具 ----

    private static void background(Runnable r) throws InterruptedException {
        Thread t = new Thread(r);
        t.start();
        t.join();
    }

    private static void add(BaseVehicle v) {
        VANILLA.add("add " + IDS.get(v));
        MdcPathfindVehicleScope.addVehicle(fake, v);
    }

    private static void remove(BaseVehicle v) {
        VANILLA.add("remove " + IDS.get(v));
        MdcPathfindVehicleScope.removeVehicle(fake, v);
    }

    private static void tick(boolean expire) {
        if (expire) {
            MdcPathfindVehicleScope.expireForTest();
        }
        MdcPathfindVehicleScope.getVehicles(cell);
    }

    /** 沿 x 移到動物 chunk (125,125) 右邊第 cx 個 chunk（y 不變）。 */
    private static void moveChunk(BaseVehicle v, int cx) throws Exception {
        setField(IsoMovingObject.class, v, "x", cx * 8F + 1F);
    }

    private static List<String> deferred() {
        List<String> out = new ArrayList<>();
        for (BaseVehicle v : MdcPathfindVehicleScope.deferredForTest()) {
            out.add(IDS.get(v));
        }
        return out;
    }

    private static Set<String> names(Set<BaseVehicle> set) {
        Set<String> out = new HashSet<>();
        for (BaseVehicle v : set) {
            out.add(IDS.get(v));
        }
        return out;
    }

    private static BaseVehicle vehicle(String id, float x, float y) throws Exception {
        BaseVehicle v = alloc(BaseVehicle.class);
        IDS.put(v, id);
        setField(IsoMovingObject.class, v, "x", x);
        setField(IsoMovingObject.class, v, "y", y);
        return v;
    }

    private static TestAnimal animal(float x, float y, boolean dead) throws Exception {
        TestAnimal a = alloc(TestAnimal.class);
        a.tx = x;
        a.ty = y;
        a.dead = dead;
        return a;
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

    private static int count(List<String> rows, String needle) {
        int n = 0;
        for (String r : rows) {
            if (r.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings({"unchecked", "deprecation", "removal"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "pvs pass  " : "pvs FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
