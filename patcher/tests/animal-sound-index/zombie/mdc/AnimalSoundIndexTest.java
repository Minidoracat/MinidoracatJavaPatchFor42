package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import zombie.WorldSoundManager;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;

/**
 * W48-2 差分測試：同一份真 {@code WorldSoundManager.soundList}（經 W48-2 包裝），每次查詢都拿原版 {@code getSoundAnimal}
 * 與索引版比對回傳物件是否相同。清單以原版方式變動：{@code add} 追加、真 {@code update()} 讓聲音到期移除，另穿插插入、
 * 中間移除、{@code set}、交換、{@code removeIf}、排序、{@code addAll}、清空。
 * 用法：{@code AnimalSoundIndexTest on|observe|off|violation}（observe 需 {@code -Dmdc.animalSoundIndex=observe}、
 * off 需 {@code =0}）。
 */
public final class AnimalSoundIndexTest {
    private static IsoGridSquare square;
    private static final WorldSoundManager MGR = WorldSoundManager.instance;
    private static long queries;
    private static long hits;
    private static long listSizeSum;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "on" : args[0];
        GameServer.server = true;
        square = alloc(IsoGridSquare.class);
        List<WorldSoundManager.WorldSound> list = MGR.soundList;
        switch (mode) {
            case "on" -> {
                require(AnimalSoundIndex.MODE == AnimalSoundIndex.MODE_ON, "mode on");
                require(list instanceof AnimalSoundIndex.SoundList, "soundList 必須被包裝");
                differential(list, 240, 11L);
                edgeCases(list);
                require(AnimalSoundIndex.fastForTest() * 10 > queries * 8, "索引必須承擔大部分查詢：fast=" + AnimalSoundIndex.fastForTest());
                // 測試世界刻意密集（5% 半徑 5000、15% 半徑 100–600），只要求候選少於一半；實際收益看正式服心跳 candAvg。
                require(AnimalSoundIndex.candidateSumForTest() * 2 < listSizeSum, "候選必須少於整份清單的一半：cand="
                        + AnimalSoundIndex.candidateSumForTest() + " list=" + listSizeSum);
                require(AnimalSoundIndex.rebuildsForTest() > 0 && AnimalSoundIndex.tailAppendsForTest() > 0, "必須涵蓋重建與尾端追加");
                require(AnimalSoundIndex.auditsForTest() > 0 && AnimalSoundIndex.auditMissesForTest() == 0, "抽樣比對零不一致");
                require(!AnimalSoundIndex.disabledForTest() && AnimalSoundIndex.anomaliesForTest() == 0, "不得停用、無異常");
                bulkOps();
                concurrentAppend();
                require(!AnimalSoundIndex.disabledForTest() && AnimalSoundIndex.auditMissesForTest() == 0, "並行追加不得觸發停用");
                untrustedView(list);
            }
            case "observe" -> {
                require(AnimalSoundIndex.MODE == AnimalSoundIndex.MODE_OBSERVE, "mode observe");
                require(list instanceof AnimalSoundIndex.SoundList, "soundList 必須被包裝");
                differential(list, 60, 12L);
                require(AnimalSoundIndex.auditsForTest() == AnimalSoundIndex.fastForTest(), "observe 每次都比對");
                require(AnimalSoundIndex.observeMismatchesForTest() == 0, "observe 零不一致");
            }
            case "off" -> {
                require(AnimalSoundIndex.MODE == AnimalSoundIndex.MODE_OFF, "mode off");
                require(list.getClass() == ArrayList.class, "off 不包裝清單");
                differential(list, 40, 13L);
                require(AnimalSoundIndex.fastForTest() == 0, "off 不走索引");
            }
            case "violation" -> violation(list);
            default -> throw new IllegalArgumentException(mode);
        }
        System.out.println("AnimalSoundIndexTest OK mode=" + mode + " queries=" + queries + " hits=" + hits
                + " fast=" + AnimalSoundIndex.fastForTest() + " cand=" + AnimalSoundIndex.candidateSumForTest()
                + " listSum=" + listSizeSum + " rebuilds=" + AnimalSoundIndex.rebuildsForTest()
                + " tail=" + AnimalSoundIndex.tailAppendsForTest() + " audits=" + AnimalSoundIndex.auditsForTest());
    }

    // ---- 差分 ----

    private static void differential(List<WorldSoundManager.WorldSound> list, int worlds, long seed) throws Exception {
        Random r = new Random(seed);
        for (int w = 0; w < worlds; w++) {
            list.clear();
            float[][] centers = new float[1 + r.nextInt(5)][];
            for (int c = 0; c < centers.length; c++) {
                centers[c] = new float[] {200 + r.nextInt(3000) - (r.nextInt(6) == 0 ? 2500 : 0), 200 + r.nextInt(3000)};
            }
            for (int i = r.nextInt(300); i > 0; i--) {
                list.add(sound(r, centers));
            }
            int frames = 5 + r.nextInt(20);
            for (int f = 0; f < frames; f++) {
                if (r.nextInt(3) != 0) {
                    MGR.update(); // 原版：life 遞減、到期移除並回收
                }
                mutate(list, r, centers);
                int animals = 5 + r.nextInt(30);
                for (int k = 0; k < animals; k++) {
                    if (r.nextInt(5) == 0) {
                        for (int n = 1 + r.nextInt(4); n > 0; n--) {
                            list.add(sound(r, centers)); // 幀內追加
                        }
                    }
                    compare(list, animal(r, centers), "world " + w + " frame " + f + " animal " + k);
                }
            }
        }
    }

    /** 各種結構變動（原版不做，但索引必須察覺並重建）。 */
    private static void mutate(List<WorldSoundManager.WorldSound> list, Random r, float[][] centers) {
        int op = r.nextInt(14);
        int n = list.size();
        switch (op) {
            case 0 -> {
                if (n > 0) list.remove(r.nextInt(n));
            }
            case 1 -> list.add(n == 0 ? 0 : r.nextInt(n + 1), sound(r, centers));
            case 2 -> {
                if (n > 0) list.set(r.nextInt(n), sound(r, centers));
            }
            case 3 -> {
                if (n > 1) Collections.swap(list, r.nextInt(n), r.nextInt(n));
            }
            case 4 -> list.removeIf(s -> s.volume < 20);
            case 5 -> Collections.reverse(list); // 經 set／listIterator.set；sort 會把清單標成不可信，另在 bulkOps 測
            case 6 -> {
                List<WorldSoundManager.WorldSound> more = new ArrayList<>();
                for (int i = r.nextInt(8); i > 0; i--) {
                    more.add(sound(r, centers));
                }
                list.addAll(more);
            }
            case 7 -> {
                if (r.nextInt(4) == 0) list.clear();
            }
            case 8 -> {
                if (n > 0) {
                    WorldSoundManager.WorldSound twin = copy(list.get(r.nextInt(n)));
                    list.add(twin); // 同位置同半徑同音量：必須取清單中較前者
                }
            }
            case 9, 10 -> {
                if (n > 0) {
                    list.remove(r.nextInt(n)); // 大小與追加次數都不變，只有 modCount 看得出來
                    list.add(r.nextInt(n), sound(r, centers));
                }
            }
            default -> {
                // 不變動
            }
        }
    }

    private static void compare(List<WorldSoundManager.WorldSound> list, TestAnimal a, String where) {
        WorldSoundManager.WorldSound vanilla = MGR.getSoundAnimal(a);
        WorldSoundManager.WorldSound ours = AnimalSoundIndex.getSoundAnimal(MGR, a);
        queries++;
        listSizeSum += list.size();
        if (vanilla != null) {
            hits++;
        }
        if (vanilla != ours) {
            throw new AssertionError(where + " 不一致：vanilla=" + describe(vanilla, list) + " ours=" + describe(ours, list)
                    + " animal=" + a.px + "," + a.py + "," + a.pz + " wild=" + a.wild + " list=" + list.size());
        }
    }

    // ---- 邊界 ----

    private static void edgeCases(List<WorldSoundManager.WorldSound> list) throws Exception {
        Random r = new Random(99L);
        // 格線邊界：聲音與動物分別落在 64／512 的倍數上下，半徑剛好碰到範圍
        list.clear();
        for (int x = 0; x <= 1536; x += 64) {
            for (int rad : new int[] {1, 20, 21, 22, 170, 171, 300, 2000}) {
                list.add(sound(x, 700, 0, rad, 50, true, false));
                list.add(sound(x - 1, 700, 0, rad, 50, false, true));
            }
        }
        for (float ax = -70; ax <= 1600; ax += 7.75F) {
            for (float dy : new float[] {0, 20, 21, 60, 61, 62, 63.9F, 64, 66}) {
                compare(list, animal(ax, 700 + dy, 0, r.nextBoolean()), "邊界 ax=" + ax + " dy=" + dy);
                compare(list, animal(ax, 700 - dy, 0, r.nextBoolean()), "邊界 ax=" + ax + " -dy=" + dy);
            }
        }
        // 負座標、負半徑、音量 ≤ 0、半徑 0、z 差、同值多筆
        list.clear();
        list.add(sound(-5, -5, 0, 10, 30, true, false));
        list.add(sound(-5, -5, 0, -10, 30, true, false));
        list.add(sound(-6, -5, 1, 10, 0, true, false));
        list.add(sound(-6, -5, 0, 0, 90, true, false));
        list.add(sound(-5, -5, 0, 10, 30, true, false));
        list.add(sound(-100, -100, 3, 40, 80, false, true));
        for (int i = 0; i < 400; i++) {
            compare(list, animal(-120 + r.nextFloat() * 130, -120 + r.nextFloat() * 130, r.nextInt(4), r.nextBoolean()), "負座標");
        }
        // 非有限座標、超出範圍、沒有方格
        list.add(sound(10, 10, 0, 50, 50, true, true));
        compare(list, animal(Float.NaN, 10, 0, false), "NaN");
        compare(list, animal(3.0e7F, 10, 0, true), "超出範圍");
        TestAnimal noSquare = animal(10, 10, 0, false);
        noSquare.sq = null;
        compare(list, noSquare, "無方格");
        // 清單含 null：兩邊都照原版拋出 NPE
        list.add(3, null);
        String vanillaError = thrown(() -> MGR.getSoundAnimal(animal(10, 10, 0, false)));
        String oursError = thrown(() -> AnimalSoundIndex.getSoundAnimal(MGR, animal(10, 10, 0, false)));
        require(vanillaError != null && vanillaError.equals(oursError), "null 元素同型拋出：" + vanillaError + " / " + oursError);
        require(AnimalSoundIndex.fallbackNullForTest() > 0, "null 元素必須改走原版");
        list.remove(3);
        compare(list, animal(10, 10, 0, false), "移除 null 後恢復索引");
        list.clear();
    }

    /**
     * replaceAll／sort／removeAll／retainAll 會在回呼途中直接改寫陣列、最後才遞增 modCount（回呼拋出時不遞增）：
     * 部分完成後拋出、寫入 null、回呼中重入查詢，都必須與原版相同。每個情境用新的 WorldSoundManager（建構子經 W48-2 包裝）。
     */
    private static void bulkOps() {
        TestAnimal a = animal(100, 100, 0, false);
        // replaceAll 第一筆換成更大聲的 C 後拋出：清單變 [C,B]，modCount 不變
        {
            WorldSoundManager m = new WorldSoundManager();
            WorldSoundManager.WorldSound first = sound(100, 100, 0, 20, 90, true, false);
            m.soundList.add(first);
            m.soundList.add(sound(100, 100, 0, 20, 10, true, false));
            require(compareOn(m, a, "replaceAll 前") == first, "replaceAll 前取 A");
            WorldSoundManager.WorldSound louder = sound(100, 100, 0, 20, 100, true, false);
            thrown(() -> m.soundList.replaceAll(s -> {
                if (s == first) {
                    return louder;
                }
                throw new IllegalStateException("stop");
            }));
            require(compareOn(m, a, "replaceAll 部分完成") == louder, "replaceAll 部分完成後取新物件");
        }
        // replaceAll 寫入 null 後拋出：原版 NPE
        {
            WorldSoundManager m = new WorldSoundManager();
            WorldSoundManager.WorldSound first = sound(100, 100, 0, 20, 90, true, false);
            m.soundList.add(first);
            m.soundList.add(sound(100, 100, 0, 20, 10, true, false));
            compareOn(m, a, "replaceAll null 前");
            thrown(() -> m.soundList.replaceAll(s -> {
                if (s == first) {
                    return null;
                }
                throw new IllegalStateException("stop");
            }));
            String v = thrown(() -> m.getSoundAnimal(a));
            String o = thrown(() -> AnimalSoundIndex.getSoundAnimal(m, a));
            require(v != null && v.equals(o), "replaceAll 寫入 null 後同型拋出：" + v + " / " + o);
        }
        // replaceAll 回呼中重入查詢：第一筆已被換掉
        {
            WorldSoundManager m = new WorldSoundManager();
            WorldSoundManager.WorldSound first = sound(100, 100, 0, 20, 90, true, false);
            m.soundList.add(first);
            m.soundList.add(sound(100, 100, 0, 20, 10, true, false));
            compareOn(m, a, "replaceAll 重入前");
            WorldSoundManager.WorldSound louder = sound(100, 100, 0, 20, 100, true, false);
            m.soundList.replaceAll(s -> {
                if (s == first) {
                    return louder;
                }
                compareOn(m, a, "replaceAll 回呼中");
                return s;
            });
        }
        // sort 第三次比較時拋出：同位置同音量，平手取清單第一筆，而第一筆已被搬動
        {
            WorldSoundManager m = new WorldSoundManager();
            for (int radius : new int[] {20, 19, 21, 18}) {
                m.soundList.add(sound(100, 100, 0, radius, 50, true, false));
            }
            compareOn(m, a, "sort 前");
            int[] calls = {0};
            thrown(() -> m.soundList.sort((p, q) -> {
                if (++calls[0] == 3) {
                    throw new IllegalStateException("stop");
                }
                return Integer.compare(p.radius, q.radius);
            }));
            compareOn(m, a, "sort 部分完成");
        }
        // removeAll／retainAll 在 contains 回呼中重入查詢（陣列壓縮途中）
        for (boolean retain : new boolean[] {false, true}) {
            WorldSoundManager m = new WorldSoundManager();
            WorldSoundManager.WorldSound first = sound(100, 100, 0, 20, 90, true, false);
            m.soundList.add(first);
            m.soundList.add(sound(100, 100, 0, 20, 10, true, false));
            m.soundList.add(sound(100, 100, 0, 20, 5, true, false));
            compareOn(m, a, "batchRemove 前");
            java.util.Collection<Object> probe = new java.util.AbstractCollection<>() {
                @Override
                public boolean contains(Object o) {
                    compareOn(m, a, retain ? "retainAll 回呼中" : "removeAll 回呼中");
                    return (o == first) != retain;
                }

                @Override
                public java.util.Iterator<Object> iterator() {
                    return Collections.emptyIterator();
                }

                @Override
                public int size() {
                    return 1;
                }
            };
            if (retain) {
                m.soundList.retainAll(probe);
            } else {
                m.soundList.removeAll(probe);
            }
            compareOn(m, a, retain ? "retainAll 後" : "removeAll 後");
        }
    }

    /** 另一執行緒在查詢讀取動物座標途中（索引維護之前）以 addSound 的同一把鎖追加更大聲的聲音：必須與原版相同、不得觸發停用。 */
    private static void concurrentAppend() {
        WorldSoundManager m = new WorldSoundManager();
        List<WorldSoundManager.WorldSound> l = m.soundList;
        l.add(sound(100, 100, 0, 20, 10, true, false));
        TestAnimal a = animal(100, 100, 0, false);
        compareOn(m, a, "並行前");
        for (int i = 0; i < 600; i++) {
            WorldSoundManager.WorldSound louder = sound(100, 100, 0, 20, 11 + i % 80, true, false);
            a.onGetZ = () -> {
                Thread t = new Thread(() -> {
                    synchronized (l) {
                        l.add(louder);
                    }
                });
                t.start();
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            };
            WorldSoundManager.WorldSound ours = AnimalSoundIndex.getSoundAnimal(m, a);
            require(a.onGetZ == null, "hook 必須被觸發");
            WorldSoundManager.WorldSound vanilla = m.getSoundAnimal(a);
            require(ours == vanilla, "並行追加後不一致 i=" + i);
            queries++;
        }
    }

    private static WorldSoundManager.WorldSound compareOn(WorldSoundManager m, TestAnimal a, String where) {
        WorldSoundManager.WorldSound vanilla = m.getSoundAnimal(a);
        WorldSoundManager.WorldSound ours = AnimalSoundIndex.getSoundAnimal(m, a);
        queries++;
        if (vanilla != ours) {
            throw new AssertionError(where + " 不一致：vanilla=" + describe(vanilla, m.soundList) + " ours=" + describe(ours, m.soundList));
        }
        return ours;
    }

    /** 取過 subList 的清單永久改走原版，結果仍相同。 */
    private static void untrustedView(List<WorldSoundManager.WorldSound> list) {
        Random r = new Random(5L);
        float[][] centers = {{500, 500}};
        for (int i = 0; i < 200; i++) {
            list.add(sound(r, centers));
        }
        long before = AnimalSoundIndex.fallbackUntrustedForTest();
        list.subList(0, 10).set(0, sound(r, centers)); // 視圖寫入繞過計數
        for (int i = 0; i < 50; i++) {
            compare(list, animal(r, centers), "subList 之後");
        }
        require(AnimalSoundIndex.fallbackUntrustedForTest() == before + 50, "取過 subList 後必須改走原版");
    }

    /** 違反「聲音欄位不會被原地改寫」：抽樣比對必須發現並停用。 */
    private static void violation(List<WorldSoundManager.WorldSound> list) {
        require(AnimalSoundIndex.MODE == AnimalSoundIndex.MODE_ON, "mode on");
        list.clear();
        WorldSoundManager.WorldSound far = sound(5000, 5000, 0, 20, 90, true, false);
        list.add(far);
        TestAnimal a = animal(100, 100, 0, false);
        require(AnimalSoundIndex.getSoundAnimal(MGR, a) == null, "改寫前範圍內沒有聲音");
        far.x = 100; // 原地搬到動物旁邊（原版不會這樣做）
        far.y = 100;
        for (int i = 0; i < 300 && !AnimalSoundIndex.disabledForTest(); i++) {
            AnimalSoundIndex.getSoundAnimal(MGR, a);
        }
        require(AnimalSoundIndex.disabledForTest() && AnimalSoundIndex.auditMissesForTest() == 1, "抽樣比對必須發現並停用");
        require(AnimalSoundIndex.getSoundAnimal(MGR, a) == far && MGR.getSoundAnimal(a) == far, "停用後改走原版");
        queries = 1;
    }

    // ---- 物件 ----

    private static WorldSoundManager.WorldSound sound(Random r, float[][] centers) {
        float[] c = centers[r.nextInt(centers.length)];
        int spread = r.nextInt(4) == 0 ? 400 : 60;
        int radius;
        int k = r.nextInt(20);
        if (k < 11) {
            radius = 20;
        } else if (k < 15) {
            radius = 1 + r.nextInt(70);
        } else if (k < 18) {
            radius = 100 + r.nextInt(500);
        } else if (k == 18) {
            radius = r.nextInt(3) == 0 ? -(1 + r.nextInt(30)) : 0;
        } else {
            radius = 5000;
        }
        int volume = r.nextInt(12) == 0 ? -r.nextInt(5) : 1 + r.nextInt(100);
        int kind = r.nextInt(10);
        WorldSoundManager.WorldSound s = sound((int) c[0] + r.nextInt(2 * spread + 1) - spread,
                (int) c[1] + r.nextInt(2 * spread + 1) - spread, r.nextInt(4), radius, volume, kind < 5, kind == 5 || kind == 6);
        s.stressZombies = kind >= 5;
        s.life = 1 + r.nextInt(16);
        return s;
    }

    private static WorldSoundManager.WorldSound sound(int x, int y, int z, int radius, int volume, boolean humans, boolean animals) {
        WorldSoundManager.WorldSound s = new WorldSoundManager.WorldSound();
        s.x = x;
        s.y = y;
        s.z = z;
        s.radius = radius;
        s.volume = volume;
        s.stresshumans = humans;
        s.stressAnimals = animals;
        s.life = 16;
        return s;
    }

    private static WorldSoundManager.WorldSound copy(WorldSoundManager.WorldSound o) {
        WorldSoundManager.WorldSound s = sound(o.x, o.y, o.z, o.radius, o.volume, o.stresshumans, o.stressAnimals);
        s.life = o.life;
        return s;
    }

    private static TestAnimal animal(Random r, float[][] centers) {
        float[] c = centers[r.nextInt(centers.length)];
        float spread = r.nextInt(3) == 0 ? 500 : 80;
        TestAnimal a = animal(c[0] + (r.nextFloat() * 2 - 1) * spread, c[1] + (r.nextFloat() * 2 - 1) * spread,
                r.nextInt(4), r.nextBoolean());
        if (r.nextInt(40) == 0) {
            a.sq = null;
        }
        return a;
    }

    private static TestAnimal animal(float x, float y, float z, boolean wild) {
        TestAnimal a;
        try {
            a = alloc(TestAnimal.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        a.px = x;
        a.py = y;
        a.pz = z;
        a.wild = wild;
        a.sq = square;
        return a;
    }

    private static String describe(WorldSoundManager.WorldSound s, List<WorldSoundManager.WorldSound> list) {
        if (s == null) {
            return "null";
        }
        return "#" + list.indexOf(s) + "(" + s.x + "," + s.y + "," + s.z + " r=" + s.radius + " v=" + s.volume + ")";
    }

    private interface Call {
        void run();
    }

    private static String thrown(Call c) {
        try {
            c.run();
            return null;
        } catch (RuntimeException e) {
            return e.getClass().getName();
        }
    }

    static class TestAnimal extends IsoAnimal {
        float px;
        float py;
        float pz;
        boolean wild;
        IsoGridSquare sq;
        Runnable onGetZ;

        TestAnimal() {
            super(null);
        }

        @Override public float getX() { return px; }
        @Override public float getY() { return py; }
        @Override public float getZ() {
            Runnable hook = onGetZ;
            onGetZ = null;
            if (hook != null) {
                hook.run();
            }
            return pz;
        }
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

    private AnimalSoundIndexTest() {
    }
}
