package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import zombie.MovingObjectUpdateScheduler;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoWorld;

/**
 * W18 行為測試：off／observe／enforce 各跑獨立 JVM（build.ps1 三步），MODE 自驗防 property 假綠。
 *
 * <p>動物用 {@code TestAnimal extends IsoAnimal} 覆寫 {@code updateLOS()} 直接計數（forward 證據），
 * {@code Unsafe.allocateInstance} 取得不跑建構子。{@code IsoWorld.instance} 反射塞空殼
 * （currentCell=null）⇒ helper sample 分支的 {@code getCell()} 回 null 安全跳過
 * （sizeSamples==0 且 anomalies==0——同時演練 null-cell 分支）。
 *
 * <p>enforce 幀源＝反射寫 {@code MovingObjectUpdateScheduler.instance.frameCounter}，全確定性
 * 無 sleep。三軌斷言：(1) 逐 (animal, frame) 公式 oracle——mutation 靠此殺公式 mutant；
 * (2) 同幀重複呼叫結果一致——殺「改回 nanoTime 牆鐘」類 mutant（牆鐘在固定 frame 下時變）；
 * (3) 4N 幀內每動物恰 forward N 分之一的幀數（輪轉硬保證＝無失明）。
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

        // IsoWorld.instance 空殼：getCell() 回 null（見 class doc）。
        Field worldInstance = IsoWorld.class.getDeclaredField("instance");
        worldInstance.setAccessible(true);
        worldInstance.set(null, alloc(IsoWorld.class));

        List<TestAnimal> animals = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            animals.add(alloc(TestAnimal.class));
        }

        switch (wantMode) {
            case AnimalLosGate.MODE_OFF -> testOff(animals);
            case AnimalLosGate.MODE_OBSERVE -> testObserve(animals);
            default -> testEnforce(animals);
        }
        System.out.println("AnimalLosGateTest OK mode=" + AnimalLosGate.MODE);
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

    /** observe：每呼叫必轉＋計數對帳＋null-cell 的 size 採樣安全跳過＋預設 N 自驗。 */
    private static void testObserve(List<TestAnimal> animals) {
        assertEq("observe 預設 N", 2, AnimalLosGate.N);
        final int rounds = 50;
        for (int r = 0; r < rounds; r++) {
            for (TestAnimal a : animals) {
                AnimalLosGate.updateLOS(a);
            }
        }
        long want = (long) rounds * animals.size();
        for (TestAnimal a : animals) {
            assertEq("observe losCalls", rounds, a.losCalls);
        }
        assertEq("observe calls", want, AnimalLosGate.callsForTest());
        assertEq("observe forwarded", want, AnimalLosGate.forwardedForTest());
        assertEq("observe skipped", 0L, AnimalLosGate.skippedForTest());
        assertEq("observe sizeSamples（cell null 全跳過）", 0L, AnimalLosGate.sizeSamplesForTest());
        assertEq("observe anomalies", 0L, AnimalLosGate.anomaliesForTest());
    }

    /**
     * enforce（build.ps1 傳 -Dmdc.animalLosN=4）：反射驅動 frameCounter，
     * 三軌斷言（公式 oracle／同幀一致／輪轉硬保證）。
     */
    private static void testEnforce(List<TestAnimal> animals) throws Exception {
        final int n = AnimalLosGate.N;
        assertEq("enforce 測試參數 n", 4, n);

        MovingObjectUpdateScheduler sched = MovingObjectUpdateScheduler.instance;
        if (sched == null) {
            throw new AssertionError("MovingObjectUpdateScheduler.instance 為 null");
        }
        Field fc = MovingObjectUpdateScheduler.class.getDeclaredField("frameCounter");
        fc.setAccessible(true);

        final long base = 1_000_000L;
        final int frames = 4 * n;
        for (long off = 0; off < frames; off++) {
            fc.setLong(sched, base + off);
            for (TestAnimal a : animals) {
                int before = a.losCalls;
                AnimalLosGate.updateLOS(a);
                boolean fwd = a.losCalls > before;
                int phase = (System.identityHashCode(a) * 0x9E3779B9) >>> 16;
                boolean want = Math.floorMod(phase + (int) (base + off), n) == 0;
                if (fwd != want) {
                    throw new AssertionError("逐幀公式不符 animal=" + System.identityHashCode(a)
                            + " frame=" + (base + off) + " got=" + fwd + " want=" + want);
                }
                // 同幀重複：結果必須一致（牆鐘 mutant 在固定 frame 下時變 ⇒ 紅）。
                int mid = a.losCalls;
                AnimalLosGate.updateLOS(a);
                boolean fwd2 = a.losCalls > mid;
                if (fwd2 != fwd) {
                    throw new AssertionError("同幀結果不一致 animal=" + System.identityHashCode(a)
                            + " frame=" + (base + off));
                }
            }
        }
        // 輪轉硬保證：4N 幀內每動物恰 4 個 forward 幀（每幀呼叫 2 次 ⇒ losCalls==8）。
        for (TestAnimal a : animals) {
            assertEq("輪轉硬保證 losCalls（animal=" + System.identityHashCode(a) + "）",
                    8, a.losCalls);
        }
        assertEq("enforce calls=forwarded+skipped",
                AnimalLosGate.callsForTest(),
                AnimalLosGate.forwardedForTest() + AnimalLosGate.skippedForTest());
        long wantForwarded = (long) animals.size() * 8;
        assertEq("enforce forwarded 總數", wantForwarded, AnimalLosGate.forwardedForTest());
        if (AnimalLosGate.skippedForTest() == 0L) {
            throw new AssertionError("enforce 應有 skip（恆 forward mutant?）");
        }
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

    static final class TestAnimal extends IsoAnimal {
        int losCalls;

        private TestAnimal() {
            super(null);
        }

        @Override
        public void updateLOS() {
            losCalls++;
        }
    }
}
