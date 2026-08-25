package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zombie.MovingObjectUpdateScheduler;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;

/**
 * W18 行為測試：off／observe／enforce 各跑獨立 JVM（build.ps1 七組態），MODE 與 N 都自驗防
 * property 假綠（args[0]=mode、args[1]=wantN 可選）。
 *
 * <p>動物用 {@code TestAnimal extends IsoAnimal} 覆寫 {@code updateLOS()} 直接計數（forward 證據），
 * {@code Unsafe.allocateInstance} 取得不跑建構子。{@code IsoWorld.instance} 反射塞空殼；
 * observe 的 size 採樣用 {@code allocateInstance(IsoCell.class)}＋反射寫 private final
 * {@code objectList}（IsoCell 是 final 不能 extends；JDK25 對非 static final instance field
 * 的 setAccessible 寫已由 probe 驗證）提供可控集合（非 null 成功路徑），再切回 null cell
 * 驗安全跳過——兩分支都有行為覆蓋。
 *
 * <p>enforce 幀源＝反射寫 {@code MovingObjectUpdateScheduler.instance.frameCounter}，全確定性
 * 無 sleep。三軌斷言：(1) 逐 (animal, frame) 公式 oracle（mutation 殺公式 mutant 的主力）；
 * (2) 同幀重複呼叫結果一致（輔助訊號——牆鐘實作在固定 frame 下時變；殺因統計以 track 1 為主）；
 * (3) 4N 幀內每動物恰 1/N forward 幀（輪轉硬保證＝無失明，與公式形狀無關的獨立性質）；
 * 另加 (4) 相位分散：無任何一幀 forward 全體動物（mix 退化成常數相位時紅）。
 *
 * <p>錯誤契約（observe JVM 內續測）：簿記 RuntimeException（null animal 於 sample 幀的
 * {@code getCell()} NPE）⇒ anomalies+1 且仍恰好委派一次（以委派 NPE 外逃為證）；vanilla
 * 委派的 RuntimeException 與 Error 原樣外逃、不計 anomalies。LOD fail-open：
 * {@code getCurrentSimulationLevel().getFrameMod() != 1} 的動物在 enforce 下恆 forward、
 * 計 lodPassthrough（frameMod==1 者照常輪轉）。
 */
public final class AnimalLosGateTest {

    public static void main(String[] args) throws Exception {
        String arg = args.length == 0 ? "observe" : args[0];
        int wantMode = switch (arg) {
            case "off" -> AnimalLosGate.MODE_OFF;
            case "enforce" -> AnimalLosGate.MODE_ENFORCE;
            default -> AnimalLosGate.MODE_OBSERVE;
        };
        if (AnimalLosGate.MODE != wantMode) {
            throw new AssertionError("mode got=" + AnimalLosGate.MODE + " want=" + wantMode);
        }
        int wantN = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        if (AnimalLosGate.N != wantN) {
            throw new AssertionError("N got=" + AnimalLosGate.N + " want=" + wantN);
        }

        // IsoWorld.instance 空殼：getCell() 預設回 null；observe 段落內再反射注入可控 cell。
        Field worldInstance = IsoWorld.class.getDeclaredField("instance");
        worldInstance.setAccessible(true);
        IsoWorld world = alloc(IsoWorld.class);
        worldInstance.set(null, world);

        List<TestAnimal> animals = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            animals.add(alloc(TestAnimal.class));
        }

        switch (wantMode) {
            case AnimalLosGate.MODE_OFF -> testOff(animals);
            case AnimalLosGate.MODE_OBSERVE -> {
                testObserve(animals, world);
                testErrorContract(animals);
            }
            default -> testEnforce(animals);
        }
        System.out.println("AnimalLosGateTest OK mode=" + AnimalLosGate.MODE + " n=" + AnimalLosGate.N);
    }

    /** off：純直通——每呼叫必轉、helper 計數全凍結。 */
    private static void testOff(List<TestAnimal> animals) {
        final int rounds = 50;
        for (int r = 0; r < rounds; r++) {
            for (TestAnimal a : animals) {
                AnimalLosGate.updateLOS(a);
            }
        }
        for (TestAnimal a : animals) {
            assertEq("off losCalls", rounds, a.losCalls);
        }
        assertEq("off calls 凍結", 0L, AnimalLosGate.callsForTest());
        assertEq("off forwarded 凍結", 0L, AnimalLosGate.forwardedForTest());
        assertEq("off skipped 凍結", 0L, AnimalLosGate.skippedForTest());
        assertEq("off anomalies", 0L, AnimalLosGate.anomaliesForTest());
    }

    /**
     * observe：每呼叫必轉＋計數對帳＋size 採樣兩分支（反射注入 objectList 的成功路徑：
     * sizeMin/Max/Avg 與注入集合相符；null cell：安全跳過、不計 anomaly）。
     */
    private static void testObserve(List<TestAnimal> animals, IsoWorld world) throws Exception {
        Field cellField = IsoWorld.class.getDeclaredField("currentCell");
        cellField.setAccessible(true);

        // 段 1：非 null cell，objectList 固定 7 個元素——跑到 forwarded=129（sample 命中 1/65/129）。
        // IsoCell 是 final 不能 extends：allocateInstance ＋ 反射寫 private final objectList
        // （JDK25 對非 static final instance field 的 setAccessible 寫已由 probe 驗證可行）。
        // 隱性耦合（review r2 記載）：size==7 依賴 IsoMovingObject 家族維持 identity
        // equals/hashCode——TIS 若改成以 ID 為基礎，集合塌縮、此斷言轉紅（失敗方向安全）。
        IsoCell cell = alloc(IsoCell.class);
        Set<IsoMovingObject> objects = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            objects.add(alloc(TestAnimal.class));
        }
        Field objectListField = IsoCell.class.getDeclaredField("objectList");
        objectListField.setAccessible(true);
        objectListField.set(cell, objects);
        cellField.set(world, cell);
        final int rounds1 = 9;   // 9×16=144 calls ⇒ forwarded 1..144，sample 於 1/65/129
        for (int r = 0; r < rounds1; r++) {
            for (TestAnimal a : animals) {
                AnimalLosGate.updateLOS(a);
            }
        }
        long want1 = (long) rounds1 * animals.size();
        assertEq("observe 段1 calls", want1, AnimalLosGate.callsForTest());
        assertEq("observe 段1 forwarded", want1, AnimalLosGate.forwardedForTest());
        assertEq("observe 段1 skipped", 0L, AnimalLosGate.skippedForTest());
        assertEq("observe 段1 sizeSamples", 3L, AnimalLosGate.sizeSamplesForTest());
        assertEq("observe 段1 sizeSum", 21L, AnimalLosGate.sizeSumForTest());
        assertEq("observe 段1 sizeMin", 7L, AnimalLosGate.sizeMinForTest());
        assertEq("observe 段1 sizeMax", 7L, AnimalLosGate.sizeMaxForTest());
        assertEq("observe 段1 losSamples", 3L, AnimalLosGate.losSamplesForTest());
        assertEq("observe 段1 anomalies", 0L, AnimalLosGate.anomaliesForTest());

        // 段 2：null cell——推進到下一個 sample 幀（forwarded=193），採樣安全跳過、零 anomaly。
        cellField.set(world, null);
        final int rounds2 = 4;   // +64 ⇒ forwarded 145..208，sample 於 193 命中 null 分支
        for (int r = 0; r < rounds2; r++) {
            for (TestAnimal a : animals) {
                AnimalLosGate.updateLOS(a);
            }
        }
        for (TestAnimal a : animals) {
            assertEq("observe losCalls", rounds1 + rounds2, a.losCalls);
        }
        assertEq("observe 段2 sizeSamples 不變", 3L, AnimalLosGate.sizeSamplesForTest());
        assertEq("observe 段2 losSamples 照量", 4L, AnimalLosGate.losSamplesForTest());
        assertEq("observe 段2 anomalies", 0L, AnimalLosGate.anomaliesForTest());
    }

    /**
     * 錯誤契約（observe JVM 續跑，全部以 delta 對帳）：
     * (a) 簿記 RuntimeException fail-open：null animal 推到 sample 幀 ⇒ try 內 getCell() 前的
     *     identityHashCode(null) 不炸、sample 分支 animal.getCell() NPE ⇒ anomalies+1，
     *     仍恰好委派一次（null.updateLOS() NPE 外逃為證）；
     * (b) vanilla RuntimeException 原樣外逃、不計 anomalies；
     * (c) vanilla Error（LinkageError 家族）原樣外逃、不計 anomalies。
     */
    private static void testErrorContract(List<TestAnimal> animals) {
        // 推進 forwarded 至 ≡0 (mod 64)：下一次呼叫恰為 sample 幀。
        long fwd = AnimalLosGate.forwardedForTest();
        int filler = (int) (64L - (fwd % 64L)) % 64;
        for (int i = 0; i < filler; i++) {
            AnimalLosGate.updateLOS(animals.get(0));
        }
        assertEq("錯誤契約前置：forwarded ≡ 0 (mod 64)", 0L, AnimalLosGate.forwardedForTest() % 64L);

        long beforeAnom = AnimalLosGate.anomaliesForTest();
        long beforeFwd = AnimalLosGate.forwardedForTest();
        boolean npe = false;
        try {
            AnimalLosGate.updateLOS(null);   // sample 幀：getCell() NPE 在 try 內
        } catch (NullPointerException e) {
            npe = true;                       // fail-open 委派 null.updateLOS() 的 NPE 外逃
        }
        if (!npe) {
            throw new AssertionError("簿記失敗後應仍委派一次（null 委派 NPE 未外逃）");
        }
        assertEq("簿記失敗計 anomalies", beforeAnom + 1, AnimalLosGate.anomaliesForTest());
        assertEq("簿記失敗仍計 forwarded", beforeFwd + 1, AnimalLosGate.forwardedForTest());

        // (b) vanilla RuntimeException 外逃、不計 anomaly。
        beforeAnom = AnimalLosGate.anomaliesForTest();
        ThrowingAnimal thrower = allocQuiet(ThrowingAnimal.class);
        boolean sentinel = false;
        try {
            AnimalLosGate.updateLOS(thrower);
        } catch (IllegalStateException e) {
            sentinel = "sentinel-rte".equals(e.getMessage());
        }
        if (!sentinel) {
            throw new AssertionError("vanilla RuntimeException 未原樣外逃");
        }
        assertEq("vanilla 例外不計 anomalies", beforeAnom, AnimalLosGate.anomaliesForTest());

        // (c) vanilla Error 外逃、不計 anomaly。注意：這只證「委派例外不被包裝」——委派本就
        // 在主 try 外，catch 型別收斂（LinkageError 穿透）的真守門是 SmokeCheck 的
        // tryCatchBlocks 型別鎖，此斷言不具該辨別力。
        ErrorAnimal errorer = allocQuiet(ErrorAnimal.class);
        boolean err = false;
        try {
            AnimalLosGate.updateLOS(errorer);
        } catch (NoClassDefFoundError e) {
            err = "sentinel-err".equals(e.getMessage());
        }
        if (!err) {
            throw new AssertionError("vanilla Error 未原樣外逃");
        }
        assertEq("vanilla Error 不計 anomalies", beforeAnom, AnimalLosGate.anomaliesForTest());
    }

    /**
     * enforce：反射驅動 frameCounter，四軌斷言（公式 oracle／同幀一致／輪轉硬保證／相位分散）
     * ＋LOD fail-open。N 由 property 決定（build.ps1 傳 4／2／clamp 邊界 0→1、999→16）。
     */
    private static void testEnforce(List<TestAnimal> animals) throws Exception {
        final int n = AnimalLosGate.N;

        MovingObjectUpdateScheduler sched = MovingObjectUpdateScheduler.instance;
        if (sched == null) {
            throw new AssertionError("MovingObjectUpdateScheduler.instance 為 null");
        }
        Field fc = MovingObjectUpdateScheduler.class.getDeclaredField("frameCounter");
        fc.setAccessible(true);

        final long base = 1_000_000L;
        final int frames = 4 * n;
        Map<Long, Integer> fwdPerFrame = new HashMap<>();
        for (long off = 0; off < frames; off++) {
            long frame = base + off;
            fc.setLong(sched, frame);
            for (TestAnimal a : animals) {
                int before = a.losCalls;
                AnimalLosGate.updateLOS(a);
                boolean fwd = a.losCalls > before;
                int phase = (System.identityHashCode(a) * 0x9E3779B9) >>> 16;
                boolean want = Math.floorMod((long) phase + frame, n) == 0;
                if (fwd != want) {
                    throw new AssertionError("逐幀公式不符 animal=" + System.identityHashCode(a)
                            + " frame=" + frame + " got=" + fwd + " want=" + want);
                }
                if (fwd) {
                    fwdPerFrame.merge(frame, 1, Integer::sum);
                }
                // 同幀重複：結果必須一致（輔助訊號，見 class doc）。
                int mid = a.losCalls;
                AnimalLosGate.updateLOS(a);
                boolean fwd2 = a.losCalls > mid;
                if (fwd2 != fwd) {
                    throw new AssertionError("同幀結果不一致 animal=" + System.identityHashCode(a)
                            + " frame=" + frame);
                }
            }
        }
        // 輪轉硬保證：4N 幀內每動物恰 4 個 forward 幀（每幀呼叫 2 次 ⇒ losCalls==8）。
        for (TestAnimal a : animals) {
            assertEq("輪轉硬保證 losCalls（animal=" + System.identityHashCode(a) + "）",
                    8, a.losCalls);
        }
        // 相位分散：無任何一幀 forward 全體動物（mix 退化成常數相位 ⇒ 全體同幀 ⇒ 紅）。
        // N==1 時每幀本來就全體 forward，分散無定義，跳過。
        // 決定性化（review r2）：identityHashCode 不可控，本次 JVM 若 16 隻真 hash 經 mix 後
        // 恰好全落同一 mod-N 殘餘類（N=2 理想均勻 ≈ 2^-15，非零），正確實作也會全體同幀——
        // 先自算相位分布，全同殘餘類時跳過斷言（println 留痕），把機率性假紅顯式歸零。
        if (n > 1) {
            Set<Integer> residues = new HashSet<>();
            for (TestAnimal a : animals) {
                int phase = (System.identityHashCode(a) * 0x9E3779B9) >>> 16;
                residues.add(Math.floorMod(phase, n));
            }
            if (residues.size() < 2) {
                System.out.println("相位分散斷言跳過：本次 JVM 全體動物同殘餘類（機率級事件）");
            } else {
                for (Map.Entry<Long, Integer> e : fwdPerFrame.entrySet()) {
                    if (e.getValue() >= animals.size()) {
                        throw new AssertionError("相位聚集：frame=" + e.getKey()
                                + " forward 了全體 " + e.getValue() + " 隻動物");
                    }
                }
            }
        }
        assertEq("enforce calls=forwarded+skipped",
                AnimalLosGate.callsForTest(),
                AnimalLosGate.forwardedForTest() + AnimalLosGate.skippedForTest());
        long wantForwarded = (long) animals.size() * 8;
        assertEq("enforce forwarded 總數", wantForwarded, AnimalLosGate.forwardedForTest());
        if (n > 1 && AnimalLosGate.skippedForTest() == 0L) {
            throw new AssertionError("enforce N>1 應有 skip（恆 forward mutant?）");
        }
        if (n == 1 && AnimalLosGate.skippedForTest() != 0L) {
            throw new AssertionError("enforce N=1 應等效全跑（skipped=" + AnimalLosGate.skippedForTest() + "）");
        }

        // LOD fail-open：frameMod>1 的動物恆 forward 計 lodPassthrough；frameMod==1 照常輪轉。
        TestAnimal lodAnimal = alloc(TestAnimal.class);
        lodAnimal.simLevel = UpdateSchedulerSimulationLevelHolder.nonFull();
        long beforeLod = AnimalLosGate.lodPassthroughForTest();
        int lodFwd = 0;
        for (long off = 0; off < n; off++) {
            fc.setLong(sched, base + 100 + off);
            int before = lodAnimal.losCalls;
            AnimalLosGate.updateLOS(lodAnimal);
            if (lodAnimal.losCalls > before) {
                lodFwd++;
            }
        }
        assertEq("LOD fail-open：N 幀內全 forward", n, lodFwd);
        assertEq("LOD fail-open：lodPassthrough 計數", beforeLod + n, AnimalLosGate.lodPassthroughForTest());
        TestAnimal fullAnimal = alloc(TestAnimal.class);
        fullAnimal.simLevel = UpdateSchedulerSimulationLevelHolder.full();
        int fullFwd = 0;
        for (long off = 0; off < n; off++) {
            fc.setLong(sched, base + 200 + off);
            int before = fullAnimal.losCalls;
            AnimalLosGate.updateLOS(fullAnimal);
            if (fullAnimal.losCalls > before) {
                fullFwd++;
            }
        }
        assertEq("frameMod==1 照常輪轉：N 幀內恰 1 forward", 1, fullFwd);

        // maybeBeat 內層覆蓋（review r2）：4096-call 閘讓七組態的正常呼叫量（最大 N=16 組態
        // 約 2080，均 < 4096）永遠不進
        // 內層。反射把 calls 推到閘界，下一呼叫時 (calls & 0xFFF)==0 進入 60s 節流判斷＋
        // beat 拼接（lastBeatNs==0 ⇒ 必印一行含 lodPassthrough 的 beat）——驗拼接不炸、
        // 不計 anomalies。註：此步破壞 calls=forwarded+skipped 恆等式，故置於對帳斷言之後。
        Field callsField = AnimalLosGate.class.getDeclaredField("calls");
        callsField.setAccessible(true);
        long beforeBeatAnom = AnimalLosGate.anomaliesForTest();
        callsField.setLong(null, 0xFFFL);   // 下一呼叫 calls=0x1000
        AnimalLosGate.updateLOS(fullAnimal);
        assertEq("beat 內層拼接不炸（anomalies 不變）", beforeBeatAnom, AnimalLosGate.anomaliesForTest());
        assertEq("enforce anomalies", 0L, AnimalLosGate.anomaliesForTest());
    }

    private static void assertEq(String what, long want, long got) {
        if (want != got) {
            throw new AssertionError(what + " want=" + want + " got=" + got);
        }
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    private static <T> T allocQuiet(Class<T> type) {
        try {
            return alloc(type);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** getCurrentSimulationLevel 覆寫載體：simLevel 可由測試注入（null＝視同適用 gate）。 */
    static class TestAnimal extends IsoAnimal {
        int losCalls;
        zombie.UpdateSchedulerSimulationLevel simLevel;

        TestAnimal() {
            super(null);
        }

        @Override
        public void updateLOS() {
            losCalls++;
        }

        @Override
        public zombie.UpdateSchedulerSimulationLevel getCurrentSimulationLevel() {
            return simLevel;
        }
    }

    static final class ThrowingAnimal extends TestAnimal {
        @Override
        public void updateLOS() {
            throw new IllegalStateException("sentinel-rte");
        }
    }

    static final class ErrorAnimal extends TestAnimal {
        @Override
        public void updateLOS() {
            throw new NoClassDefFoundError("sentinel-err");
        }
    }

    /** 取 enum 實例的小工具（FULL 與任一非 FULL）。 */
    static final class UpdateSchedulerSimulationLevelHolder {
        static zombie.UpdateSchedulerSimulationLevel full() {
            return zombie.UpdateSchedulerSimulationLevel.FULL;
        }

        static zombie.UpdateSchedulerSimulationLevel nonFull() {
            for (zombie.UpdateSchedulerSimulationLevel lvl
                    : zombie.UpdateSchedulerSimulationLevel.values()) {
                if (lvl.getFrameMod() != 1) {
                    return lvl;
                }
            }
            throw new AssertionError("找不到 frameMod>1 的 simulation level");
        }
    }
}
