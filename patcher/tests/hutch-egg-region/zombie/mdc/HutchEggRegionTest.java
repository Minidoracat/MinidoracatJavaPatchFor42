package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.characters.animals.datas.AnimalData;
import zombie.iso.IsoCell;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.objects.IsoHutch;
import zombie.util.PZCalendar;

/**
 * W72 HutchEggRegion 行為驗證。用法：{@code HutchEggRegionTest <jar> <distJava> on|observe|off}，observe／off 須帶
 * {@code -Dmdc.hutchEggRegion=2|0}；測試先自驗 argv 與實際模式相符。
 *
 * <p>同一個 JVM 各用一個隔離 class loader 跑三種 {@code checkEggs}：dist 內 W72 手術後的真 {@code AnimalData}（連同 dist 內的
 * helper）、jar 內的原版，以及把 {@code getRegionHutch()} 改道到測試內 mutant 的原版。三份都只把讀交配季的
 * {@code SandboxOptions} 換成同形替身（測試 JVM 建不起它）。動物、行為、雞舍是 allocateInstance 的子類（只覆寫
 * {@code addEgg}／{@code callToHutch}／{@code addMetaEgg}／{@code haveRoomForNewEggs} 等出口來記錄），畜牧區是真的
 * {@code DesignationZoneAnimal}，原版的相連搜尋與雞舍型別比對照原碼跑。每種情境記下 {@code eggsToday}／{@code eggTime}／
 * 產卵相關欄位、出口呼叫順序與例外，三方逐筆比對。
 *
 * <p>鎖：observe／off 與原版逐筆相同、搜尋次數相同；on 與原版逐筆相同，唯一差別是「動物在雞舍內、原版搜尋本身丟例外」
 * 那類（原版缺陷，on 不再丟），且雞舍內走到搜尋的情境只剩每 256 次一次的抽樣搜尋。mutant：改到 {@code parent.hutch == null}
 * 那半邊的必須被抓到；只改 {@code parent.hutch != null} 那半邊的（回 null、回別的雞舍）必須與原版相同——這就是等價的證明。
 */
public final class HutchEggRegionTest {
    static final String DATA = "zombie/characters/animals/datas/AnimalData";
    static final String REGION_DESC = "()Lzombie/iso/objects/IsoHutch;";
    static final long NOW_MS = 1_800_000_000_000L;

    private static int failed;

    // ---- 出口紀錄（單執行緒）----
    static final StringBuilder LOG = new StringBuilder();
    static int connectedCalls;
    static boolean addEggResult;
    static boolean addMetaEggResult;
    static boolean haveRoom;

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        java.nio.file.Path dist = java.nio.file.Path.of(args[1]);
        String mode = args[2];
        int wantMode = switch (mode) {
            case "on" -> HutchEggRegion.MODE_ON;
            case "observe" -> HutchEggRegion.MODE_OBSERVE;
            case "off" -> HutchEggRegion.MODE_OFF;
            default -> throw new IllegalArgumentException(mode);
        };
        expect("模式與 argv 相符（" + mode + "，-Dmdc.hutchEggRegion=" + System.getProperty("mdc.hutchEggRegion") + "）",
                HutchEggRegion.MODE == wantMode);
        expect("parseMode：null/1/on/ON/亂寫→on、2/observe→observe、0/off/ OFF →off",
                HutchEggRegion.parseMode(null) == 1 && HutchEggRegion.parseMode("1") == 1
                        && HutchEggRegion.parseMode("on") == 1 && HutchEggRegion.parseMode("ON") == 1
                        && HutchEggRegion.parseMode("x") == 1 && HutchEggRegion.parseMode("2") == 2
                        && HutchEggRegion.parseMode("observe") == 2 && HutchEggRegion.parseMode("0") == 0
                        && HutchEggRegion.parseMode("off") == 0 && HutchEggRegion.parseMode(" OFF ") == 0);

        byte[] vanillaBytes;
        try (ZipFile zf = new ZipFile(jar)) {
            vanillaBytes = zf.getInputStream(zf.getEntry(DATA + ".class")).readAllBytes();
        }
        byte[] patchedBytes = java.nio.file.Files.readAllBytes(dist.resolve(DATA + ".class"));
        byte[] helperBytes = java.nio.file.Files.readAllBytes(dist.resolve(HELPER + ".class"));
        ClassLoader patchedLoader = isolated(Map.of(DATA, copy(patchedBytes, null), HELPER, helperBytes));
        int patchedStandIns = STAND_INS;
        Class<?> patchedCls = patchedLoader.loadClass(DATA.replace('/', '.'));
        Class<?> helper = patchedLoader.loadClass(HELPER.replace('/', '.'));
        Class<?> vanillaCls = isolated(Map.of(DATA, copy(vanillaBytes, null))).loadClass(DATA.replace('/', '.'));
        int vanillaStandIns = STAND_INS - patchedStandIns;
        expect("手術後、原版兩份 AnimalData 各自隔離，共用同一個 IsoAnimal；手術後那份呼叫的是同 loader 的 helper",
                patchedCls != AnimalData.class && vanillaCls != AnimalData.class && patchedCls != vanillaCls
                        && vanillaCls.getField("parent").getType() == IsoAnimal.class
                        && patchedCls.getField("parent").getType() == IsoAnimal.class
                        && helper != HutchEggRegion.class && helper.getClassLoader() == patchedLoader);
        expect("隔離的 helper 讀到同一個模式", staticInt(helper, "MODE") == wantMode);
        expect("替身換掉 SandboxOptions：手術後與原版兩份換掉的處數相同（各 " + vanillaStandIns + "）",
                patchedStandIns > 0 && patchedStandIns == vanillaStandIns);
        Runner patched = new Runner(patchedCls);
        Runner vanilla = new Runner(vanillaCls);
        Map<String, String> mutantNames = new java.util.LinkedHashMap<>();
        mutantNames.put("delegate", "照原版（計數用）");
        mutantNames.put("nullAlways", "一律回 null");
        mutantNames.put("parentHutchNoGuard", "一律回 parent.hutch（不管是不是 null）");
        mutantNames.put("invertedGuard", "parent.hutch == null 才短路（回 null）");
        mutantNames.put("anyHutchIgnoringType", "雞舍外的動物回第一個相連雞舍、不比型別");
        mutantNames.put("insideNull", "雞舍內回 null（等價）");
        mutantNames.put("insideOther", "雞舍內回別的雞舍（等價）");
        mutantNames.put("onModel", "照 on 的語意自己寫一份（雞舍內回 parent.hutch，計數用）");
        Map<String, Runner> mutants = new java.util.LinkedHashMap<>();
        for (String m : mutantNames.keySet()) {
            mutants.put(m, new Runner(isolated(Map.of(DATA, copy(vanillaBytes, m))).loadClass(DATA.replace('/', '.'))));
        }
        expect("每個 mutant 副本改道 4 個 getRegionHutch（共 " + MUTANT_SITES + "）", MUTANT_SITES == 4 * mutantNames.size());

        World world = new World();
        FOREIGN = world.foreign;
        int scenarios = 0;
        int diffs = 0;
        int insideThrowDiffs = 0;
        int otherDiffs = 0;
        int insideReached = 0;
        int insideThrows = 0;
        long vanillaSearchInside = 0;
        long patchedSearchInside = 0;
        long vanillaSearchOther = 0;
        long patchedSearchOther = 0;
        long delegateCalls = 0;
        long delegateSkips = 0;
        long onModelCalls = 0;
        long onModelSkips = 0;
        int onModelDiffs = 0;
        Map<String, Integer> mutantDiffs = new java.util.LinkedHashMap<>();
        Map<String, Integer> mutantNonThrowDiffs = new java.util.LinkedHashMap<>();
        Scenario throwScenario = null;
        long calls0 = counter(helper, "callsForTest");
        long skips0 = counter(helper, "skipsForTest");
        long samples0 = counter(helper, "samplesForTest") + counter(helper, "sampleErrorsForTest");
        for (Scenario s : Scenario.all()) {
            scenarios++;
            Result v = vanilla.run(world, s);
            Result p = patched.run(world, s);
            Result d = mutants.get("delegate").run(world, s);
            expect("delegate mutant 與原版逐筆相同（" + s + "）", v.outcome.equals(d.outcome), false);
            delegateCalls += MUT_CALLS;
            delegateSkips += MUT_INSIDE_CALLS;
            boolean reachedInside = MUT_INSIDE_CALLS > 0;
            boolean throwsInside = reachedInside && v.outcome.contains("EX:");
            if (reachedInside) {
                insideReached++;
                vanillaSearchInside += v.connected;
                patchedSearchInside += p.connected;
                if (throwsInside) {
                    insideThrows++;
                    if (throwScenario == null) {
                        throwScenario = s;
                    }
                }
            } else {
                vanillaSearchOther += v.connected;
                patchedSearchOther += p.connected;
            }
            if (!v.outcome.equals(p.outcome)) {
                diffs++;
                if (throwsInside) {
                    insideThrowDiffs++;
                } else {
                    otherDiffs++;
                    if (otherDiffs <= 5) {
                        System.out.println("  差異 " + s + "\n    原版 " + v.outcome + "\n    手術後 " + p.outcome);
                    }
                }
            }
            for (Map.Entry<String, Runner> e : mutants.entrySet()) {
                Result m = e.getValue().run(world, s);
                if (e.getKey().equals("onModel")) {
                    onModelCalls += MUT_CALLS;
                    onModelSkips += MUT_INSIDE_CALLS;
                    if (!m.outcome.equals(p.outcome)) {
                        onModelDiffs++;
                    }
                }
                if (!m.outcome.equals(v.outcome)) {
                    mutantDiffs.merge(e.getKey(), 1, Integer::sum);
                    if (!throwsInside) {
                        mutantNonThrowDiffs.merge(e.getKey(), 1, Integer::sum);
                    }
                }
            }
        }
        long calls = counter(helper, "callsForTest") - calls0;
        long skips = counter(helper, "skipsForTest") - skips0;
        long samples = counter(helper, "samplesForTest") + counter(helper, "sampleErrorsForTest") - samples0;
        expect("SandboxOptions 替身真的被呼叫（只回「沒有交配季」，" + SEASON_CALLS + " 次）", SEASON_CALLS > 0);
        System.out.println("  情境 " + scenarios + "，雞舍內走到搜尋 " + insideReached + "（其中原版搜尋丟例外 " + insideThrows
                + "），搜尋次數（getConnectedDZone）雞舍內 原版 " + vanillaSearchInside + "／手術後 " + patchedSearchInside
                + "，其餘 原版 " + vanillaSearchOther + "／手術後 " + patchedSearchOther);
        System.out.println("  helper calls=" + calls + " skips=" + skips + " samples+errors=" + samples
                + "；改道點命中 原版 " + delegateCalls + "（雞舍內 " + delegateSkips + "）、照 on 語意 " + onModelCalls
                + "（雞舍內 " + onModelSkips + "）；mutant 差異 " + mutantDiffs);
        expect("情境涵蓋：雞舍內走到搜尋、原版搜尋丟例外、雞舍外走到搜尋都有", insideReached > 100 && insideThrows > 0
                && delegateCalls > delegateSkips);
        expect("除了「雞舍內、原版搜尋丟例外」那類，其餘情境手術後與原版逐筆相同（差 " + otherDiffs + "）", otherDiffs == 0);
        expect("沒有在雞舍內走到搜尋的情境，搜尋次數與原版相同", vanillaSearchOther == patchedSearchOther);
        if (wantMode == HutchEggRegion.MODE_ON) {
            expect("on：雞舍內情境只有「原版搜尋丟例外」那類不同（" + insideThrowDiffs + "／" + insideThrows + "）",
                    insideThrowDiffs == insideThrows);
            expect("on：每種情境（含原版丟例外那類）都與「照 on 語意自己寫的一份」逐筆相同（差 " + onModelDiffs + "）",
                    onModelDiffs == 0);
            // on 只剩每 256 次略過抽量一次的原版搜尋（只計時）。
            expect("on：雞舍內情境的搜尋次數降到原版的 2% 以下（" + patchedSearchInside + "／" + vanillaSearchInside + "）",
                    vanillaSearchInside > 0 && patchedSearchInside * 50 < vanillaSearchInside);
        } else {
            expect(mode + "：雞舍內情境也與原版逐筆相同（差 " + insideThrowDiffs + "）", insideThrowDiffs == 0);
            expect(mode + "：雞舍內情境搜尋次數與原版相同（" + patchedSearchInside + "／" + vanillaSearchInside + "）",
                    patchedSearchInside == vanillaSearchInside);
        }
        if (wantMode == HutchEggRegion.MODE_OFF) {
            expect("off：不計數", calls == 0 && skips == 0 && samples == 0);
        } else {
            long wantCalls = wantMode == HutchEggRegion.MODE_ON ? onModelCalls : delegateCalls;
            long wantSkips = wantMode == HutchEggRegion.MODE_ON ? onModelSkips : delegateSkips;
            expect(mode + "：calls＝改道點命中數 " + wantCalls + "、skips＝其中雞舍內 " + wantSkips,
                    calls == wantCalls && skips == wantSkips);
            expect(mode + "：每 256 次略過抽量一次（samples+errors " + samples + "，應為 " + sampledBetween(skips0, skips0 + skips) + "）",
                    samples == sampledBetween(skips0, skips0 + skips));
            Method beat = helper.getDeclaredMethod("beatLine", String.class);
            beat.setAccessible(true);
            String line = (String) beat.invoke(null, "");
            System.out.println("  心跳 " + line);
            expect(mode + "：心跳格式（前綴、mode、calls、skips、samples、sampleUs、estSavedMs、sampleErrors、logErrors=0）",
                    java.util.regex.Pattern.matches("\\[MinidoracatJavaPatch\\]\\[HutchEggRegion\\] mode=" + wantMode
                            + " calls=" + (calls0 + calls) + " skips=" + (skips0 + skips)
                            + " samples=\\d+ sampleUs=\\d+\\.\\d estSavedMs=\\d+ sampleErrors=\\d+ logErrors=0", line));
            if (wantMode == HutchEggRegion.MODE_ON && throwScenario != null) {
                long errors0 = counter(helper, "sampleErrorsForTest");
                int thrown = 0;
                for (int i = 0; i < 256; i++) {
                    if (patched.run(world, throwScenario).outcome.contains("EX:")) {
                        thrown++;
                    }
                }
                long errors = counter(helper, "sampleErrorsForTest") - errors0;
                expect("on：原版搜尋會丟例外的雞舍內情境連跑 256 次，抽樣搜尋丟的例外只計 sampleErrors（" + errors
                        + "）、不外拋（外拋 " + thrown + "）", errors >= 1 && thrown == 0);
            }
        }
        for (String m : List.of("nullAlways", "parentHutchNoGuard", "invertedGuard", "anyHutchIgnoringType")) {
            expect("mutant 被抓到：" + mutantNames.get(m) + "（不同 " + mutantNonThrowDiffs.getOrDefault(m, 0) + "）",
                    mutantNonThrowDiffs.getOrDefault(m, 0) > 0);
        }
        for (String m : List.of("delegate", "insideNull", "insideOther", "onModel")) {
            expect("mutant 與原版相同：" + mutantNames.get(m) + "（不同 " + mutantNonThrowDiffs.getOrDefault(m, 0) + "）",
                    mutantNonThrowDiffs.getOrDefault(m, 0) == 0);
        }

        if (failed > 0) {
            System.out.println("HutchEggRegionTest " + mode + " FAIL " + failed);
            System.exit(1);
        }
        System.out.println("HutchEggRegionTest " + mode + " OK");
    }

    static long sampledBetween(long from, long to) {
        long n = 0;
        for (long k = from + 1; k <= to; k++) {
            if ((k & 255L) == 1L) {
                n++;
            }
        }
        return n;
    }

    // ---- 情境 ----

    record Scenario(int inside, int region, boolean connected, boolean room, boolean meta, boolean addEgg,
                    boolean addMeta, int eggState, boolean full, boolean wild, boolean adefHutches, boolean doorOpen) {
        static List<Scenario> all() {
            List<Scenario> out = new ArrayList<>();
            boolean[] tf = {false, true};
            for (int inside = 0; inside < 3; inside++) {
                for (int region = 0; region < 5; region++) {
                    for (boolean connected : tf) {
                        for (boolean room : tf) {
                            for (boolean meta : tf) {
                                for (boolean addEgg : tf) {
                                    for (boolean addMeta : tf) {
                                        for (int eggState = 0; eggState < 3; eggState++) {
                                            for (boolean full : tf) {
                                                for (boolean wild : tf) {
                                                    for (boolean adefHutches : tf) {
                                                        for (boolean doorOpen : tf) {
                                                            out.add(new Scenario(inside, region, connected, room, meta,
                                                                    addEgg, addMeta, eggState, full, wild, adefHutches,
                                                                    doorOpen));
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return out;
        }
    }

    /** 真畜牧區兩塊相連（Z1、Z2）一塊不相連（Z3）；雞舍放在哪一塊依情境決定。 */
    static final class World {
        final DesignationZoneAnimal z1;
        final DesignationZoneAnimal z2;
        final DesignationZoneAnimal z3;
        final TestHutch coopOpen;
        final TestHutch coopClosed;
        final TestHutch rabbit;
        final TestHutch inside;
        final TestHutch insideOtherType;
        final TestHutch foreign;
        final AnimalDefinitions adef;
        final TestBehavior behavior;

        World() throws Exception {
            DesignationZoneAnimal.designationAnimalZoneList.clear();
            z1 = zone(10, 10, 5, 5);
            z2 = zone(15, 10, 5, 5);
            z3 = zone(100, 100, 5, 5);
            coopOpen = hutch("coop", true);
            coopClosed = hutch("coop", false);
            rabbit = hutch("rabbithutch", true);
            inside = hutch("coop", true);
            insideOtherType = hutch("rabbithutch", false);
            foreign = hutch("coop", true);
            adef = alloc(AnimalDefinitions.class);
            adef.eggsPerDay = 2;
            adef.minClutchSize = 0;
            adef.layEggPeriodStart = -1;
            behavior = alloc(TestBehavior.class);
        }

        void setup(Scenario s) {
            for (DesignationZoneAnimal z : List.of(z1, z2, z3)) {
                z.hutchs.clear();
            }
            switch (s.region) {
                case 0 -> { }
                case 1 -> z1.hutchs.add(coopOpen);
                case 2 -> {
                    z2.hutchs.add(rabbit);
                    z2.hutchs.add(coopClosed);
                }
                case 3 -> z1.hutchs.add(rabbit);
                case 4 -> {
                    z1.hutchs.add(null);   // 原版搜尋在 testHutch.type 丟 NPE 的資料
                    z2.hutchs.add(coopOpen);
                }
                default -> throw new IllegalStateException();
            }
            coopOpen.setOpen(s.doorOpen);
            inside.setOpen(s.doorOpen);
            adef.hutches = s.adefHutches ? new ArrayList<>(List.of("coop")) : null;
            haveRoom = s.room;
            addEggResult = s.addEgg;
            addMetaEggResult = s.addMeta;
        }

        TestAnimal animal(Scenario s) throws Exception {
            TestAnimal a = alloc(TestAnimal.class);
            a.adef = adef;
            a.hutch = switch (s.inside) {
                case 0 -> null;
                case 1 -> inside;
                default -> insideOtherType;
            };
            TestAnimal.state(a, s.wild, behavior, s.connected ? new ArrayList<>(List.of(z1)) : new ArrayList<>());
            return a;
        }

        private static DesignationZoneAnimal zone(int x, int y, int w, int h) throws Exception {
            DesignationZoneAnimal z = alloc(DesignationZoneAnimal.class);
            z.x = x;
            z.y = y;
            z.z = 0;
            z.w = w;
            z.h = h;
            setFinal(z, DesignationZoneAnimal.class, "hutchs", new ArrayList<IsoHutch>());
            DesignationZoneAnimal.designationAnimalZoneList.add(z);
            return z;
        }

        private static TestHutch hutch(String type, boolean open) throws Exception {
            TestHutch h = alloc(TestHutch.class);
            h.type = type;
            h.setOpen(open);
            return h;
        }
    }

    record Result(String outcome, int connected) {
    }

    /** 對一個 AnimalData 類別（真的或副本）建資料、跑 checkEggs、讀回欄位。 */
    static final class Runner {
        final Class<?> cls;
        final Field parent;
        final Field eggsToday;
        final Field eggTime;
        final Field clutchSize;
        final Field clutchSizeDone;
        final Method checkEggs;

        Runner(Class<?> cls) throws Exception {
            this.cls = cls;
            parent = cls.getField("parent");
            eggsToday = cls.getField("eggsToday");
            eggTime = cls.getField("eggTime");
            clutchSize = cls.getField("clutchSize");
            clutchSizeDone = cls.getField("clutchSizeDone");
            checkEggs = cls.getMethod("checkEggs", PZCalendar.class, boolean.class);
        }

        Result run(World world, Scenario s) throws Exception {
            world.setup(s);
            TestAnimal animal = world.animal(s);
            Object data = alloc(cls);
            parent.set(data, animal);
            eggsToday.setInt(data, s.full ? 2 : 0);
            long nowSec = NOW_MS / 1000L;
            eggTime.setLong(data, switch (s.eggState) {
                case 0 -> 0L;
                case 1 -> nowSec - 100L;
                default -> nowSec + 1000L;
            });
            PZCalendar cal = new PZCalendar(Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            cal.setTimeInMillis(NOW_MS);
            LOG.setLength(0);
            connectedCalls = 0;
            MUT_CALLS = 0;
            MUT_INSIDE_CALLS = 0;
            String ex = "";
            try {
                checkEggs.invoke(data, cal, s.meta);
            } catch (InvocationTargetException e) {
                ex = " EX:" + e.getCause().getClass().getName();
            }
            String outcome = "eggsToday=" + eggsToday.getInt(data) + " eggTime=" + eggTime.getLong(data)
                    + " clutch=" + clutchSize.getInt(data) + "/" + clutchSizeDone.getBoolean(data)
                    + " hutch=" + name(world, animal.hutch) + " log=[" + LOG + "]" + ex;
            return new Result(outcome, connectedCalls);
        }
    }

    static String name(World w, IsoHutch h) {
        if (h == null) {
            return "null";
        }
        if (h == w.coopOpen) {
            return "coopOpen";
        }
        if (h == w.coopClosed) {
            return "coopClosed";
        }
        if (h == w.rabbit) {
            return "rabbit";
        }
        if (h == w.inside) {
            return "inside";
        }
        if (h == w.insideOtherType) {
            return "insideOtherType";
        }
        return h == w.foreign ? "foreign" : "?";
    }

    // ---- 出口替身 ----

    public static class TestAnimal extends IsoAnimal {
        private static final Map<IsoAnimal, Object[]> STATE = new java.util.IdentityHashMap<>();

        public TestAnimal() {
            super((IsoCell) null);
        }

        static void state(TestAnimal a, boolean wild, BaseAnimalBehavior behavior, ArrayList<DesignationZoneAnimal> zones) {
            STATE.clear();
            STATE.put(a, new Object[]{wild, behavior, zones});
        }

        @Override
        public boolean isWild() {
            return (Boolean) STATE.get(this)[0];
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public BaseAnimalBehavior getBehavior() {
            return (BaseAnimalBehavior) STATE.get(this)[1];
        }

        @Override
        @SuppressWarnings("unchecked")
        public ArrayList<DesignationZoneAnimal> getConnectedDZone() {
            connectedCalls++;
            return (ArrayList<DesignationZoneAnimal>) STATE.get(this)[2];
        }

        @Override
        public boolean addEgg(boolean meta) {
            LOG.append("addEgg(").append(meta).append(")=").append(addEggResult).append(';');
            return addEggResult;
        }
    }

    public static class TestBehavior extends BaseAnimalBehavior {
        public TestBehavior() {
            super(null);
        }

        @Override
        public boolean callToHutch(IsoHutch hutch, boolean force) {
            LOG.append("callToHutch(").append(hutch == null ? "null" : hutch.type).append(',').append(force).append(");");
            return false;
        }
    }

    public static class TestHutch extends IsoHutch {
        private static final Field OPEN;

        static {
            try {
                OPEN = IsoHutch.class.getDeclaredField("open");
                OPEN.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public TestHutch() {
            super((IsoCell) null);
        }

        void setOpen(boolean open) {
            try {
                OPEN.setBoolean(this, open);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean haveRoomForNewEggs() {
            LOG.append("haveRoom(").append(type).append(")=").append(haveRoom).append(';');
            return haveRoom;
        }

        @Override
        public boolean addMetaEgg(IsoAnimal animal) {
            LOG.append("addMetaEgg(").append(type).append(")=").append(addMetaEggResult).append(';');
            return addMetaEggResult;
        }
    }

    // ---- mutant：副本的 getRegionHutch() 改道到這裡 ----

    static int MUT_CALLS;
    static int MUT_INSIDE_CALLS;

    public static IsoHutch mutant(Object data, String which) {
        try {
            IsoAnimal parent = (IsoAnimal) data.getClass().getField("parent").get(data);
            MUT_CALLS++;
            if (parent.hutch != null) {
                MUT_INSIDE_CALLS++;
            }
            Method region = data.getClass().getMethod("getRegionHutch");
            switch (which) {
                case "delegate":
                    return (IsoHutch) invoke(region, data);
                case "nullAlways":
                    return null;
                case "parentHutchNoGuard":
                    return parent.hutch;
                case "invertedGuard":
                    return parent.hutch == null ? null : (IsoHutch) invoke(region, data);
                case "anyHutchIgnoringType":
                    if (parent.hutch != null) {
                        return parent.hutch;
                    }
                    for (DesignationZoneAnimal z : parent.getConnectedDZone()) {
                        for (IsoHutch h : z.getHutchsConnected()) {
                            if (h != null) {
                                return h;
                            }
                        }
                    }
                    return null;
                case "insideNull":
                    return parent.hutch != null ? null : (IsoHutch) invoke(region, data);
                case "insideOther":
                    return parent.hutch != null ? FOREIGN : (IsoHutch) invoke(region, data);
                case "onModel":
                    return parent.hutch != null ? parent.hutch : (IsoHutch) invoke(region, data);
                default:
                    throw new IllegalStateException(which);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static TestHutch FOREIGN;

    private static Object invoke(Method m, Object target) throws IllegalAccessException {
        try {
            return m.invoke(target);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    // 每個 mutant 一個 static 入口（改道指令只能帶固定的 owner/name/desc）
    public static IsoHutch m_delegate(Object d) { return mutant(d, "delegate"); }
    public static IsoHutch m_nullAlways(Object d) { return mutant(d, "nullAlways"); }
    public static IsoHutch m_parentHutchNoGuard(Object d) { return mutant(d, "parentHutchNoGuard"); }
    public static IsoHutch m_invertedGuard(Object d) { return mutant(d, "invertedGuard"); }
    public static IsoHutch m_anyHutchIgnoringType(Object d) { return mutant(d, "anyHutchIgnoringType"); }
    public static IsoHutch m_insideNull(Object d) { return mutant(d, "insideNull"); }
    public static IsoHutch m_insideOther(Object d) { return mutant(d, "insideOther"); }
    public static IsoHutch m_onModel(Object d) { return mutant(d, "onModel"); }

    /**
     * 對 AnimalData 的位元組套用替身：測試 JVM 建不起 {@code SandboxOptions}（建構子要跑 Lua），所以全 class 的
     * {@code SandboxOptions.getInstance()}、{@code .animalMatingSeason}、{@code BooleanSandboxOption.getValue()} 各自 1:1
     * 換成本類別的同形 static 替身（回 null／null／false＝沒有交配季）；手術後、原版、mutant 三方套用同一個替身，比對的是
     * 其餘原 bytecode。mutant 再把 checkEggs 內的 getRegionHutch() 改道到 {@code m_<mutant>}。
     */
    static byte[] copy(byte[] bytes, String mutant) {
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                boolean eggs = name.equals("checkEggs");
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && owner.equals(SANDBOX) && n.equals("getInstance")) {
                            STAND_INS++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "sandbox", "()L" + SANDBOX + ";", false);
                        } else if (op == Opcodes.INVOKEVIRTUAL && owner.equals(BOOL_OPTION) && n.equals("getValue")) {
                            STAND_INS++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "optionValue", "(L" + BOOL_OPTION + ";)Z", false);
                        } else if (eggs && mutant != null && op == Opcodes.INVOKEVIRTUAL && owner.equals(DATA)
                                && n.equals("getRegionHutch") && d.equals(REGION_DESC)) {
                            MUTANT_SITES++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "m_" + mutant,
                                    "(Ljava/lang/Object;)Lzombie/iso/objects/IsoHutch;", false);
                        } else {
                            super.visitMethodInsn(op, owner, n, d, itf);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        if (op == Opcodes.GETFIELD && owner.equals(SANDBOX) && n.equals("animalMatingSeason")) {
                            STAND_INS++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "matingSeason",
                                    "(L" + SANDBOX + ";)L" + BOOL_OPTION + ";", false);
                        } else {
                            super.visitFieldInsn(op, owner, n, d);
                        }
                    }
                };
            }
        };
        new ClassReader(bytes).accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * 只自己定義 {@code own} 裡的類別（原名），其餘交給父 loader，所以 IsoAnimal、IsoHutch、畜牧區、本測試都是同一份；
     * 手術後那份要連 helper 一起放進來，否則 helper 方法簽名裡的 AnimalData 會撞 loader constraint。
     */
    static ClassLoader isolated(Map<String, byte[]> own) {
        return new ClassLoader(HutchEggRegionTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    byte[] b = own.get(name.replace('.', '/'));
                    if (b == null) {
                        return super.loadClass(name, resolve);
                    }
                    Class<?> c = findLoadedClass(name);
                    return c != null ? c : defineClass(name, b, 0, b.length);
                }
            }
        };
    }

    static final String SELF = "zombie/mdc/HutchEggRegionTest";
    static final String HELPER = "zombie/mdc/HutchEggRegion";
    static final String SANDBOX = "zombie/SandboxOptions";
    static final String BOOL_OPTION = "zombie/SandboxOptions$BooleanSandboxOption";
    static int MUTANT_SITES;
    static int STAND_INS;
    static long SEASON_CALLS;

    public static zombie.SandboxOptions sandbox() {
        return null;
    }

    public static zombie.SandboxOptions.BooleanSandboxOption matingSeason(zombie.SandboxOptions options) {
        return null;
    }

    public static boolean optionValue(zombie.SandboxOptions.BooleanSandboxOption option) {
        SEASON_CALLS++;
        return false;
    }

    static long counter(Class<?> helper, String name) throws Exception {
        Method m = helper.getDeclaredMethod(name);
        m.setAccessible(true);
        return (Long) m.invoke(null);
    }

    static int staticInt(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    // ---- 工具 ----

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    static void setFinal(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static void expect(String what, boolean ok) {
        expect(what, ok, true);
    }

    static void expect(String what, boolean ok, boolean printOk) {
        if (!ok) {
            failed++;
            System.out.println("  FAIL " + what);
        } else if (printOk) {
            System.out.println("  OK   " + what);
        }
    }

    private HutchEggRegionTest() {
    }
}
