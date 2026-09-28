package zombie.mdc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

import zombie.MovingObjectUpdateScheduler;
import zombie.WorldSoundManager;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;

/**
 * W48 量測刀：回傳值必須與原版 getSoundAnimal 完全相同（同一個物件），計數與抽樣要對得上獨立重算。
 * 用法：{@code AnimalSoundProbeTest on|off}（off 需 {@code -Dmdc.animalSoundProbe=0}）。
 */
public final class AnimalSoundProbeTest {
    private static IsoGridSquare square;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        GameServer.server = true;
        square = alloc(IsoGridSquare.class);
        require(AnimalSoundProbe.ENABLED == "on".equals(mode), "ENABLED 與模式一致");
        WorldSoundManager mgr = WorldSoundManager.instance;
        List<WorldSoundManager.WorldSound> list = mgr.soundList;
        Field frameField = MovingObjectUpdateScheduler.class.getDeclaredField("frameCounter");
        frameField.setAccessible(true);
        Random r = new Random(48L);

        long calls = 0;
        long hits = 0;
        long samples = 0;
        long eligibleSum = 0;
        long inRangeSum = 0;
        long changes = 0;
        long frameCallsMax = 0;
        long listMax = 0;
        int frames = 400;
        for (int f = 0; f < frames; f++) {
            frameField.setLong(MovingObjectUpdateScheduler.instance,
                    frameField.getLong(MovingObjectUpdateScheduler.instance) + 1);
            list.clear();
            int size = r.nextInt(10) == 0 ? 0 : r.nextInt(400);
            for (int i = 0; i < size; i++) {
                list.add(sound(r));
            }
            int animals = 1 + r.nextInt(12);
            frameCallsMax = Math.max(frameCallsMax, animals);
            for (int k = 0; k < animals; k++) {
                if (k > 0 && r.nextInt(4) == 0) {
                    list.add(sound(r));
                    changes++;
                }
                TestAnimal a = animal(r);
                WorldSoundManager.WorldSound expected = mgr.getSoundAnimal(a);
                WorldSoundManager.WorldSound got = AnimalSoundProbe.getSoundAnimal(mgr, a);
                require(expected == got, "回傳值必須與原版同一物件 frame=" + f + " animal=" + k);
                calls++;
                if (got != null) {
                    hits++;
                }
                listMax = Math.max(listMax, list.size());
                if (calls % AnimalSoundProbe.sampleEveryForTest() == 0) {
                    samples++;
                    long[] counts = reference(list, a);
                    eligibleSum += counts[0];
                    inRangeSum += counts[1];
                }
            }
        }
        require(hits > 0 && hits < calls, "隨機世界要同時涵蓋有、無結果：hits=" + hits + " calls=" + calls);

        if ("on".equals(mode)) {
            require(AnimalSoundProbe.callsForTest() == calls, "calls " + AnimalSoundProbe.callsForTest() + " vs " + calls);
            require(AnimalSoundProbe.hitsForTest() == hits, "hits");
            require(AnimalSoundProbe.framesForTest() == frames, "frames " + AnimalSoundProbe.framesForTest());
            require(AnimalSoundProbe.frameCallsMaxForTest() == frameCallsMax, "每幀呼叫上限");
            require(AnimalSoundProbe.changesForTest() == changes, "同幀清單變動 " + AnimalSoundProbe.changesForTest() + " vs " + changes);
            require(AnimalSoundProbe.samplesForTest() == samples, "每 " + AnimalSoundProbe.sampleEveryForTest() + " 次抽樣一次");
            require(AnimalSoundProbe.eligibleSumForTest() == eligibleSum, "抽樣：會影響動物的聲音數");
            require(AnimalSoundProbe.inRangeSumForTest() == inRangeSum, "抽樣：範圍內聲音數");
            require(AnimalSoundProbe.listMaxForTest() == listMax, "清單長度上限");
            require(AnimalSoundProbe.sampleEveryForTest() != 64 || eligibleSum > 0 && inRangeSum > 0, "抽樣必須實際涵蓋範圍內的聲音");
        } else {
            require(AnimalSoundProbe.callsForTest() == 0 && AnimalSoundProbe.samplesForTest() == 0, "off 不計數");
        }

        // 原版例外原樣上拋，不計入 calls、不算 anomalies。
        long before = AnimalSoundProbe.callsForTest();
        list.clear();
        list.add(null);
        String vanillaError = thrown(() -> mgr.getSoundAnimal(animal(r)));
        String probeError = thrown(() -> AnimalSoundProbe.getSoundAnimal(mgr, animal(r)));
        require(vanillaError != null && vanillaError.equals(probeError), "例外同型上拋：" + vanillaError + " / " + probeError);
        require(AnimalSoundProbe.callsForTest() == before, "例外呼叫不計入");
        require(AnimalSoundProbe.anomaliesForTest() == 0, "anomalies 恆 0");
        list.clear();
        System.out.println("AnimalSoundProbeTest OK mode=" + mode + " calls=" + calls + " hits=" + hits
                + " samples=" + samples + " eligible=" + eligibleSum + " inRange=" + inRangeSum + " changes=" + changes);
    }

    /** 獨立重算：原版條件（stresshumans || stressAnimals）與距離判斷。 */
    private static long[] reference(List<WorldSoundManager.WorldSound> list, TestAnimal a) {
        long eligible = 0;
        long inRange = 0;
        float bonus = a.wild ? 3.0F : 1.0F;
        for (WorldSoundManager.WorldSound s : list) {
            if (!(s.stresshumans || s.stressAnimals)) {
                continue;
            }
            eligible++;
            float radius = s.radius * bonus;
            float d2 = IsoUtils.DistanceToSquared(a.px, a.py, a.pz * 3.0F, s.x, s.y, s.z * 3.0F);
            if (d2 <= radius * radius) {
                inRange++;
            }
        }
        return new long[] {eligible, inRange};
    }

    private static WorldSoundManager.WorldSound sound(Random r) {
        WorldSoundManager.WorldSound s = new WorldSoundManager.WorldSound();
        s.x = r.nextInt(400);
        s.y = r.nextInt(400);
        s.z = r.nextInt(3);
        s.radius = r.nextInt(8) == 0 ? 0 : 1 + r.nextInt(60);
        s.volume = 1 + r.nextInt(100);
        int kind = r.nextInt(10);
        s.stresshumans = kind < 2;
        s.stressAnimals = kind == 2;
        s.stressZombies = kind >= 2;
        return s;
    }

    private static TestAnimal animal(Random r) throws Exception {
        TestAnimal a = alloc(TestAnimal.class);
        a.px = r.nextFloat() * 400;
        a.py = r.nextFloat() * 400;
        a.pz = r.nextInt(3);
        a.wild = r.nextBoolean();
        a.sq = r.nextInt(30) == 0 ? null : square;
        return a;
    }

    private interface Call {
        void run() throws Exception;
    }

    private static String thrown(Call c) {
        try {
            c.run();
            return null;
        } catch (Exception e) {
            return e.getClass().getName();
        }
    }

    static class TestAnimal extends IsoAnimal {
        float px;
        float py;
        float pz;
        boolean wild;
        IsoGridSquare sq;

        TestAnimal() {
            super(null);
        }

        @Override public float getX() { return px; }
        @Override public float getY() { return py; }
        @Override public float getZ() { return pz; }
        @Override public boolean isWild() { return wild; }
        @Override public IsoGridSquare getCurrentSquare() { return sq; }
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private AnimalSoundProbeTest() {
    }
}
