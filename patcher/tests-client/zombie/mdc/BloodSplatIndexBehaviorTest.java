package zombie.mdc;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import zombie.GameTime;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.random.RandStandard;
import zombie.core.textures.ColorInfo;
import zombie.core.utils.BoundedQueue;
import zombie.debug.BooleanDebugOption;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.sprite.IsoSprite;

/**
 * W84 鄰居 chunk 血跡位置索引的行為驗證（裸 JVM）：手術後的 FBORenderCell 與 jar 原版逐筆比對。
 *
 * <p>兩份 FBORenderCell（jar 原版、手術後）各自定義在隔離的 loader（連同 jar 的巢狀類別），只在兩個
 * renderOneLevel_Blood 裡把碰畫面與遊戲狀態的呼叫換成本類別的同一組同形替身：除錯開關、血跡選項、世界時間、cutaway、
 * 格子、是否畫這一格、黑屋、頂點光、{@code renderBloodSplat}（記錄型別、座標與最後的顏色）、{@code IsoCell.getChunk}。
 * 其餘是原 bytecode。chunk 與清單是真的（手術後的 IsoChunk 建構子），兩份各跑一個由同一顆種子建出的世界：
 * 每幀先照原版的方式改清單（addBloodSplat 的滿了就移到 fade、removeBlood、清空、set、remove），再對每個 chunk 每一層照
 * renderOneLevel 的順序呼叫自己 1 次與 8 個鄰居。每幀比對所有 renderBloodSplat 的紀錄與之後兩份清單的內容、順序、每筆的
 * fade 與 chunk。座標含 0、1.5、6.5、7、8、NaN、讀檔量化值與遠方 chunk（x≈19200，float 捨入），fade 有 0、負值與快到 0 的。
 *
 * <p>參數 {@code on}／{@code verify}／{@code off}（後兩者另以 {@code -Dmdc.bloodSplatIndex=...} 啟動）。on 與 verify 另跑
 * 突變：把一個 chunk 的索引弄壞（版本不變），on 不抽樣時畫出來必須與原版不同（比對抓得到），抽樣時必須偵測、本次停用、
 * 畫出來與原版相同。另有只換內容的寫入（set、listIterator().set 要重建索引，subList().set、換到一半拋出的 replaceAll 要改回
 * 原版掃描；拿掉 set 計數的負對照必須畫得不同），以及另一條執行緒插在主執行緒讀候選的途中。最後印 {@code blood-digest=}，
 * build-client 比對三種模式相同（原版跑在包過與沒包過的清單上結果一樣）。
 */
public final class BloodSplatIndexBehaviorTest {

    static final PrintStream OUT = System.out;
    static final String CELL = "zombie/iso/fboRenderChunk/FBORenderCell";
    static final String SELF = "zombie/mdc/BloodSplatIndexBehaviorTest";
    static final String CUTAWAY = "zombie/iso/fboRenderChunk/FBORenderCutaways$ChunkLevelData";
    static final Map<String, String[]> STAND_INS = Map.ofEntries(
            Map.entry("zombie/debug/BooleanDebugOption.getValue()Z", new String[]{"debugValue", "(Lzombie/debug/BooleanDebugOption;)Z"}),
            Map.entry("zombie/core/Core.getInstance()Lzombie/core/Core;", new String[]{"core", "()Lzombie/core/Core;"}),
            Map.entry("zombie/core/Core.getOptionBloodDecals()I", new String[]{"bloodOption", "(Lzombie/core/Core;)I"}),
            Map.entry("zombie/GameTime.getInstance()Lzombie/GameTime;", new String[]{"gameTime", "()Lzombie/GameTime;"}),
            Map.entry("zombie/GameTime.getWorldAgeHours()D", new String[]{"worldAge", "(Lzombie/GameTime;)D"}),
            Map.entry("zombie/iso/IsoChunk.getCutawayDataForLevel(I)L" + CUTAWAY + ";",
                    new String[]{"cutaway", "(Lzombie/iso/IsoChunk;I)L" + CUTAWAY + ";"}),
            Map.entry(CUTAWAY + ".shouldRenderSquare(ILzombie/iso/IsoGridSquare;)Z",
                    new String[]{"shouldRender", "(L" + CUTAWAY + ";ILzombie/iso/IsoGridSquare;)Z"}),
            Map.entry("zombie/iso/IsoChunk.getGridSquare(III)Lzombie/iso/IsoGridSquare;",
                    new String[]{"square", "(Lzombie/iso/IsoChunk;III)Lzombie/iso/IsoGridSquare;"}),
            Map.entry(CELL + ".isBlackedOutBuildingSquare(Lzombie/iso/IsoGridSquare;)Z",
                    new String[]{"blackedOut", "(Ljava/lang/Object;Lzombie/iso/IsoGridSquare;)Z"}),
            Map.entry("zombie/iso/IsoGridSquare.getVertLight(II)I", new String[]{"vertLight", "(Lzombie/iso/IsoGridSquare;II)I"}),
            Map.entry("zombie/iso/sprite/IsoSprite.renderBloodSplat(FFFLzombie/core/textures/ColorInfo;)V",
                    new String[]{"render", "(Lzombie/iso/sprite/IsoSprite;FFFLzombie/core/textures/ColorInfo;)V"}),
            Map.entry("zombie/iso/IsoCell.getChunk(II)Lzombie/iso/IsoChunk;", new String[]{"chunkAt", "(Lzombie/iso/IsoCell;II)Lzombie/iso/IsoChunk;"}));
    static final float[] SPECIAL = {0.0F, -0.0F, 0.5F, 1.0F, 1.0001F, 1.4999F, 1.5F, 1.5001F, 2.0F, 6.0F, 6.4999F, 6.5F, 6.5001F,
            6.9999F, 7.0F, 7.0001F, 7.9999F, 8.0F, Float.NaN, -0.25F, 8.25F};
    static final float[] LEVELS = {0.0F, 0.25F, 0.999F, 1.0F, 1.5F, -0.5F, -1.0F, 2.0F};
    static final int[] OPTIONS = {10, 1, 5, 9, 3, 7, 0, 2, 8, 4, 6};
    static final int FRAMES = 30;
    static final double WORLD_AGE = 100.0;

    static final IdentityHashMap<IsoSprite, Integer> SPRITE_TYPE = new IdentityHashMap<>();
    static IsoGridSquare sentinel;
    static int standIns;
    static int option = 10;
    static long lastKey;
    static World world;
    static MessageDigest frameDigest;
    static long renders;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        RandStandard.INSTANCE.init();
        IsoWorld.instance = TreeRoomGuardBehaviorTest.alloc(IsoWorld.class);
        sentinel = TreeRoomGuardBehaviorTest.alloc(IsoGridSquare.class);
        for (int t = 0; t < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length; t++) {
            IsoSprite sprite = TreeRoomGuardBehaviorTest.alloc(IsoSprite.class);
            IsoFloorBloodSplat.spriteMap.put(IsoFloorBloodSplat.FLOOR_BLOOD_TYPES[t], sprite);
            SPRITE_TYPE.put(sprite, t);
        }
        int failed = 0;

        Path jar = jarPath();
        byte[] vanillaBytes;
        Map<String, byte[]> nested = new HashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            vanillaBytes = jf.getInputStream(jf.getJarEntry(CELL + ".class")).readAllBytes();
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (e.getName().startsWith(CELL + "$") && e.getName().endsWith(".class")) {
                    nested.put(e.getName().substring(0, e.getName().length() - 6), jf.getInputStream(e).readAllBytes());
                }
            }
        }
        byte[] patchedBytes;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(CELL + ".class")) {
            patchedBytes = in.readAllBytes();
        }
        failed += check("測試前提：classpath 上的 FBORenderCell 是手術後的（與 jar 不同），jar 有 " + nested.size() + " 個巢狀類別",
                !java.util.Arrays.equals(vanillaBytes, patchedBytes) && !nested.isEmpty());
        standIns = 0;
        Class<?> vanilla = isolated(standIns(vanillaBytes), nested);
        int vanillaStandIns = standIns;
        standIns = 0;
        Class<?> patched = isolated(standIns(patchedBytes), nested);
        failed += check("兩份套用同一組替身（各 " + vanillaStandIns + " 處）", vanillaStandIns == standIns && standIns >= 14);
        Renderer vRender = new Renderer(vanilla);
        Renderer pRender = new Renderer(patched);

        BloodSplatIndex.resetForTest();
        World vWorld = new World(20261029L);
        List<String> vFrames = run(vRender, vWorld);
        long vRenders = renders;
        BloodSplatIndex.resetForTest();
        World pWorld = new World(20261029L);
        List<String> pFrames = run(pRender, pWorld);
        int firstDiff = -1;
        for (int f = 0; f < Math.min(vFrames.size(), pFrames.size()) && firstDiff < 0; f++) {
            if (!vFrames.get(f).equals(pFrames.get(f))) {
                firstDiff = f;
            }
        }
        failed += check(FRAMES + " 幀逐筆相同：" + vRenders + " 次 renderBloodSplat（型別、座標、顏色、淡出）與每幀之後兩份清單的內容、順序、fade、chunk"
                        + (firstDiff < 0 ? "" : "；第一個不同的幀 " + firstDiff),
                firstDiff < 0 && vFrames.size() == pFrames.size() && renders == vRenders && vRenders > 100_000);
        OUT.println("  " + BloodSplatIndex.counters());

        boolean wrapped = pWorld.chunks.get(0).floorBloodSplats instanceof BloodSplatIndex.SplatQueue
                && pWorld.chunks.get(0).floorBloodSplatsFade instanceof BloodSplatIndex.FadeList;
        if (mode.equals("off")) {
            failed += check("kill switch off：IsoChunk 的清單是原版類別，沒有任何鄰居呼叫走索引",
                    pWorld.chunks.get(0).floorBloodSplats.getClass() == BoundedQueue.class
                    && pWorld.chunks.get(0).floorBloodSplatsFade.getClass() == ArrayList.class
                    && BloodSplatIndex.neighborCalls == 0 && BloodSplatIndex.indexedLists == 0);
        } else {
            failed += check("出貨組態：清單換成 SplatQueue／FadeList，鄰居呼叫走索引、走訪不到掃描量的 1/4，有重建",
                    wrapped && BloodSplatIndex.neighborCalls > 10_000 && BloodSplatIndex.indexedLists > 0
                    && BloodSplatIndex.visited * 4 < BloodSplatIndex.scanned && BloodSplatIndex.rebuilds > 0);
            failed += check("抽樣比對有跑、零不一致、沒有停用、不可信的 fade 清單照原版走（fallback untrusted > 0）",
                    BloodSplatIndex.sampled > (mode.equals("verify") ? BloodSplatIndex.indexedLists - 1 : 50)
                    && BloodSplatIndex.mismatches == 0 && !BloodSplatIndex.isDisabled()
                    && BloodSplatIndex.fallbackUntrusted > 0 && BloodSplatIndex.anomalies == 0);
            failed += mutant(vRender, pRender, mode.equals("verify"));
            failed += contentChanges(vRender, pRender, mode.equals("verify"));
            failed += foreignThread();
        }
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        vFrames.forEach(s -> all.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        OUT.println("blood-digest=" + HexFormat.of().formatHex(all.digest()));
        OUT.println(failed == 0 ? "BloodSplatIndexBehaviorTest " + mode + " 全部通過" : "BloodSplatIndexBehaviorTest " + mode + " 失敗 " + failed + " 項");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 弄壞一個 chunk 的 E 方向索引（版本不變）：不抽樣時比對要抓到不同，抽樣時 helper 要偵測、停用、畫出與原版相同。 */
    static int mutant(Renderer vRender, Renderer pRender, boolean verifyMode) throws Exception {
        int failed = 0;
        BloodSplatIndex.resetForTest();
        World vWorld = new World(7L);
        World pWorld = new World(7L);
        frame(vRender, vWorld, 0);
        frame(pRender, pWorld, 0);
        int target = -1;
        for (int i = 0; i < pWorld.chunks.size() && target < 0; i++) {
            IsoChunk c = pWorld.chunks.get(i);
            if (c.floorBloodSplats.size() >= 1000 && pWorld.at(c.wx + 1, c.wy) != null
                    && ((BloodSplatIndex.SplatQueue) c.floorBloodSplats).strips.count[BloodSplatIndex.Strips.E] > 0) {
                target = i;
            }
        }
        IsoChunk pTarget = pWorld.chunks.get(target);
        IsoChunk pEast = pWorld.at(pTarget.wx + 1, pTarget.wy);
        IsoChunk vEast = vWorld.at(pTarget.wx + 1, pTarget.wy);
        if (!verifyMode) {
            ((BloodSplatIndex.SplatQueue) pTarget.floorBloodSplats).strips.count[BloodSplatIndex.Strips.E] = 0;
            BloodSplatIndex.sampleNextForTest(false);
            String v = westNeighbor(vRender, vWorld, vEast);
            String p = westNeighbor(pRender, pWorld, pEast);
            failed += check("突變（不抽樣）：鄰居 chunk 的 E 候選被清空時，畫出來與原版不同（比對抓得到）",
                    !v.equals(p) && !BloodSplatIndex.isDisabled());
        }
        ((BloodSplatIndex.SplatQueue) pTarget.floorBloodSplats).strips.count[BloodSplatIndex.Strips.E] = 0;
        long mismatchesBefore = BloodSplatIndex.mismatches;
        BloodSplatIndex.sampleNextForTest(true);
        String v = westNeighbor(vRender, vWorld, vEast);
        String p = westNeighbor(pRender, pWorld, pEast);
        failed += check("突變（抽樣）：偵測到候選少了、本次停用，畫出來與原版相同",
                v.equals(p) && BloodSplatIndex.isDisabled() && BloodSplatIndex.mismatches == mismatchesBefore + 1);
        String v2 = renderChunk(vRender, vWorld, vEast, 2);
        String p2 = renderChunk(pRender, pWorld, pEast, 2);
        failed += check("停用之後一律照原版：整個 chunk 再畫一次仍相同，且沒有再比對", v2.equals(p2)
                && BloodSplatIndex.mismatches == mismatchesBefore + 1);
        return failed;
    }

    /**
     * 不改大小、只換內容的寫入：把目標 chunk fade 清單裡 8 筆在中間的血跡換成貼東緣的，再只畫它東邊鄰居的那一次（不抽樣）。
     * set／listIterator().set 要讓索引重建；subList().set 與中途拋出的 replaceAll 要讓這份清單改回原版掃描。
     * 負對照：set 之後把計數扣回去（等於 FadeList 沒記 set），on 必須畫得和原版不同，verify 必須偵測並停用。
     */
    static int contentChanges(Renderer vRender, Renderer pRender, boolean verifyMode) throws Exception {
        int failed = 0;
        String[] names = {"set 但不記次數（負對照）", "set", "listIterator().set", "subList().set", "replaceAll 換到一半拋出"};
        for (int k = 0; k < names.length; k++) {
            BloodSplatIndex.resetForTest();
            World vWorld = new World(11L);
            World pWorld = new World(11L);
            frame(vRender, vWorld, 0);
            frame(pRender, pWorld, 0);
            int target = -1;
            for (int i = 0; i < pWorld.chunks.size() && target < 0; i++) {
                IsoChunk c = pWorld.chunks.get(i);
                if (pWorld.at(c.wx + 1, c.wy) != null && central(c.floorBloodSplatsFade).size() >= 8) {
                    target = i;
                }
            }
            if (target < 0) {
                failed += check("內容變更情境的前提：找得到東邊有鄰居、fade 至少 8 筆在中間的 chunk", false);
                continue;
            }
            IsoChunk vT = vWorld.chunks.get(target);
            IsoChunk pT = pWorld.chunks.get(target);
            List<Integer> at = central(pT.floorBloodSplatsFade).subList(0, 8);
            long untrustedBefore = BloodSplatIndex.fallbackUntrusted;
            replace(k, vWorld, vT, at);
            replace(k, pWorld, pT, at);
            if (k == 0) {
                ((BloodSplatIndex.FadeList) pT.floorBloodSplatsFade).sets -= at.size();
            }
            BloodSplatIndex.sampleNextForTest(false);
            String v = westNeighbor(vRender, vWorld, vWorld.at(vT.wx + 1, vT.wy));
            String p = westNeighbor(pRender, pWorld, pWorld.at(pT.wx + 1, pT.wy));
            boolean untrusted = BloodSplatIndex.fallbackUntrusted > untrustedBefore;
            boolean ok;
            if (k == 0) {
                ok = verifyMode ? v.equals(p) && BloodSplatIndex.isDisabled() : !v.equals(p);
            } else {
                ok = v.equals(p) && !BloodSplatIndex.isDisabled() && (k >= 3) == untrusted;
            }
            failed += check("內容變更：" + names[k] + (k == 0 ? (verifyMode ? "，verify 偵測到並停用、畫出與原版相同" : "，畫出與原版不同（測得到）")
                    : k >= 3 ? "，這份清單改回原版掃描、畫出與原版相同" : "，索引重建、畫出與原版相同"), ok);
        }
        return failed;
    }

    /** fade 清單裡 x 明顯在中間（不是任何東緣候選）的位置。 */
    static List<Integer> central(ArrayList<IsoFloorBloodSplat> fade) {
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < fade.size(); i++) {
            float x = fade.get(i).x;
            if (x > 2.0F && x < 6.0F) {
                at.add(i);
            }
        }
        return at;
    }

    /** 用第 k 種寫法把 at 位置換成貼東緣的血跡（兩個世界各自建、順序相同）。 */
    static void replace(int k, World w, IsoChunk c, List<Integer> at) {
        ArrayList<IsoFloorBloodSplat> fade = c.floorBloodSplatsFade;
        Map<Integer, IsoFloorBloodSplat> edge = new HashMap<>();
        for (int j = 0; j < at.size(); j++) {
            edge.put(at.get(j), w.edgeSplat(c, j));
        }
        switch (k) {
            case 0, 1 -> edge.forEach(fade::set);
            case 2 -> {
                for (var it = fade.listIterator(); it.hasNext(); ) {
                    int i = it.nextIndex();
                    it.next();
                    if (edge.containsKey(i)) {
                        it.set(edge.get(i));
                    }
                }
            }
            case 3 -> edge.forEach((i, b) -> fade.subList(i, i + 1).set(0, b));
            default -> {
                int last = at.get(at.size() - 1);
                int[] pos = {0};
                try {
                    fade.replaceAll(b -> {
                        int i = pos[0]++;
                        if (i > last) {
                            throw new IllegalStateException("stop");
                        }
                        return edge.getOrDefault(i, b);
                    });
                } catch (IllegalStateException expected) {
                    // 原版 ArrayList 與 FadeList 都停在同一筆
                }
            }
        }
    }

    /**
     * 主執行緒的鄰居呼叫取到候選後、還沒讀完之前，另一條執行緒進來對同一份清單呼叫：那條執行緒要照原版拿到整份清單，
     * 主執行緒接著讀到的仍是同一批候選。
     */
    static int foreignThread() throws Exception {
        BloodSplatIndex.resetForTest();
        World w = new World(7L);
        IsoChunk t = null;
        for (IsoChunk c : w.chunks) {
            // 第一筆在中間：第 0 個候選一定不是清單的第 0 筆，讀錯清單時看得出來
            if (t == null && c.floorBloodSplats.size() >= 1000 && c.floorBloodSplats.get(0).x < 6.0F) {
                t = c;
            }
        }
        BoundedQueue<IsoFloorBloodSplat> q = t.floorBloodSplats;
        // 與東邊鄰居的 4 參數呼叫相同的範圍：東鄰居外擴 1 格＝t 的東緣那一條
        int minX = (t.wx + 1) * 8 - 1;
        int minY = t.wy * 8 - 1;
        int maxX = (t.wx + 2) * 8 + 1;
        int maxY = (t.wy + 1) * 8 + 1;
        BloodSplatIndex.sampleNextForTest(false);
        BloodSplatIndex.enter(t, minX, minY, maxX, maxY);
        int n = BloodSplatIndex.queueSize(q);
        Object first = BloodSplatIndex.queueGet(q, 0);
        int[] otherSize = new int[1];
        Object[] otherGet = new Object[1];
        IsoChunk target = t;
        Thread other = new Thread(() -> {
            BloodSplatIndex.enter(target, minX, minY, maxX, maxY);
            otherSize[0] = BloodSplatIndex.queueSize(q);
            otherGet[0] = BloodSplatIndex.queueGet(q, 0);
        });
        other.start();
        other.join();
        Object again = BloodSplatIndex.queueGet(q, 0);
        int nAgain = BloodSplatIndex.queueSize(q);
        return check("另一條執行緒插進來：它拿到整份清單（" + otherSize[0] + " 筆），主執行緒仍讀同一批候選（" + n + " 筆）",
                n > 0 && n < q.size() && first != q.get(0)
                && otherSize[0] == q.size() && otherGet[0] == q.get(0)
                && again == first && nAgain == n && BloodSplatIndex.foreignThread == 1);
    }

    /** 只呼叫 c 對西邊鄰居（4 參數多載 dwx=-1、dwy=0）的那一次，三層，回傳摘要。 */
    static String westNeighbor(Renderer renderer, World w, IsoChunk c) throws Exception {
        world = w;
        option = 10;
        frameDigest = MessageDigest.getInstance("SHA-256");
        for (int level = -1; level <= 1; level++) {
            renderer.neighbor.invoke(renderer.cell, c, -1, 0, level);
        }
        w.digestState(frameDigest);
        return HexFormat.of().formatHex(frameDigest.digest());
    }

    /** 每幀：改清單，再畫每一組 chunk 的每一層；回傳每幀摘要（畫的紀錄＋之後的清單狀態）。 */
    static List<String> run(Renderer renderer, World w) throws Exception {
        renders = 0;
        List<String> frames = new ArrayList<>();
        for (int f = 0; f < FRAMES; f++) {
            w.mutate(f);
            frames.add(frame(renderer, w, f));
        }
        return frames;
    }

    static String frame(Renderer renderer, World w, int f) throws Exception {
        world = w;
        option = OPTIONS[f % OPTIONS.length];
        frameDigest = MessageDigest.getInstance("SHA-256");
        for (IsoChunk c : w.chunks) {
            for (int level = -1; level <= 1; level++) {
                renderer.level(c, level);
            }
        }
        w.digestState(frameDigest);
        return HexFormat.of().formatHex(frameDigest.digest());
    }

    /** 只畫一個 chunk 的三層（突變用）。 */
    static String renderChunk(Renderer renderer, World w, IsoChunk c, int f) throws Exception {
        world = w;
        option = OPTIONS[f % OPTIONS.length];
        frameDigest = MessageDigest.getInstance("SHA-256");
        for (int level = -1; level <= 1; level++) {
            renderer.level(c, level);
        }
        w.digestState(frameDigest);
        return HexFormat.of().formatHex(frameDigest.digest());
    }

    /** 隔離 loader 裡的一份 FBORenderCell：照 renderOneLevel 的順序呼叫兩個 private 多載。 */
    static final class Renderer {
        final Object cell;
        final Method own;
        final Method neighbor;

        Renderer(Class<?> cls) throws Exception {
            cell = cls.getField("instance").get(null);
            own = cls.getDeclaredMethod("renderOneLevel_Blood", IsoChunk.class, int.class, int.class, int.class, int.class, int.class);
            own.setAccessible(true);
            neighbor = cls.getDeclaredMethod("renderOneLevel_Blood", IsoChunk.class, int.class, int.class, int.class);
            neighbor.setAccessible(true);
        }

        void level(IsoChunk c, int level) throws Exception {
            own.invoke(cell, c, level, c.wx * 8, c.wy * 8, (c.wx + 1) * 8, (c.wy + 1) * 8);
            int[][] around = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
            for (int[] d : around) {
                neighbor.invoke(cell, c, d[0], d[1], level);
            }
        }
    }

    /** 兩組 4×4 chunk（近處與 x≈19200 的遠處），清單照原版的方式建與改。 */
    static final class World {
        final Random random;
        final List<IsoChunk> chunks = new ArrayList<>();
        final Map<Long, IsoChunk> byPos = new HashMap<>();
        final IdentityHashMap<IsoFloorBloodSplat, Integer> ids = new IdentityHashMap<>();
        final IdentityHashMap<IsoChunk, Integer> chunkIds = new IdentityHashMap<>();
        final Map<IsoChunk, Integer> nextIndex = new IdentityHashMap<>();
        boolean sorted;

        World(long seed) throws Exception {
            random = new Random(seed);
            IsoCell cell = TreeRoomGuardBehaviorTest.alloc(IsoCell.class);
            for (int[] base : new int[][]{{10, 20}, {2395, 1700}}) {
                for (int dx = 0; dx < 4; dx++) {
                    for (int dy = 0; dy < 4; dy++) {
                        IsoChunk c = new IsoChunk(cell);
                        c.wx = base[0] + dx;
                        c.wy = base[1] + dy;
                        chunkIds.put(c, chunks.size());
                        chunks.add(c);
                        byPos.put(key(c.wx, c.wy), c);
                        int n = switch (random.nextInt(4)) {
                            case 0 -> 0;
                            case 1 -> random.nextInt(60);
                            default -> 1000 + random.nextInt(300);
                        };
                        for (int i = 0; i < n; i++) {
                            addBlood(c);
                        }
                    }
                }
            }
        }

        static long key(int wx, int wy) {
            return ((long) wx << 32) ^ (wy & 0xFFFFFFFFL);
        }

        IsoChunk at(int wx, int wy) {
            return byPos.get(key(wx, wy));
        }

        float coord() {
            return switch (random.nextInt(10)) {
                case 0, 1 -> SPECIAL[random.nextInt(SPECIAL.length)];
                case 2 -> (float) random.nextInt(256) / 255.0F * 8.0F;
                default -> random.nextFloat() * 8.0F;
            };
        }

        /** IsoChunk.addBloodSplat 的清單操作：滿了把最舊的移到 fade（fade=lockFPS×5）再加。 */
        void addBlood(IsoChunk c) {
            int type = random.nextInt(23) - 1;
            IsoFloorBloodSplat b = new IsoFloorBloodSplat(coord(), coord(), LEVELS[random.nextInt(LEVELS.length)], type,
                    (float) (random.nextDouble() * 200.0));
            if (type < 8) {
                int next = nextIndex.getOrDefault(c, 0) + 1;
                b.index = next;
                nextIndex.put(c, next >= 10 ? 0 : next);
            } else if (random.nextInt(10) == 0) {
                b.index = 11;
            }
            ids.put(b, ids.size());
            if (c.floorBloodSplats.isFull()) {
                IsoFloorBloodSplat b2 = c.floorBloodSplats.removeFirst();
                b2.fade = random.nextInt(4) == 0 ? 1 + random.nextInt(3) : PerformanceSettings.getLockFPS() * 5;
                c.floorBloodSplatsFade.add(b2);
            }
            c.floorBloodSplats.add(b);
        }

        /** 貼東緣（x=7.5）、第 0 層、不被抽稀（index 0）的血跡，y 依 j 錯開。 */
        IsoFloorBloodSplat edgeSplat(IsoChunk c, int j) {
            IsoFloorBloodSplat b = new IsoFloorBloodSplat(7.5F, j + 0.5F, 0.0F, j % IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length, 50.0F);
            b.fade = PerformanceSettings.getLockFPS() * 5;
            b.chunk = c;
            ids.put(b, ids.size());
            return b;
        }

        void mutate(int f) {
            for (IsoChunk c : chunks) {
                int op = random.nextInt(20);
                if (op < 8) {
                    int n = random.nextInt(40);
                    for (int i = 0; i < n; i++) {
                        addBlood(c);
                    }
                } else if (op == 8) {
                    // IsoGridSquare.removeBlood（非只牆）：附近 3×3 格的血跡逐一 remove(ix)
                    int sx = c.wx * 8 + random.nextInt(8);
                    int sy = c.wy * 8 + random.nextInt(8);
                    for (int ix = 0; ix < c.floorBloodSplats.size(); ix++) {
                        IsoFloorBloodSplat splat = c.floorBloodSplats.get(ix);
                        int splatX = (int) (c.wx * 8 + splat.x);
                        int splatY = (int) (c.wy * 8 + splat.y);
                        if (splatX >= sx - 1 && splatX <= sx + 1 && splatY >= sy - 1 && splatY <= sy + 1) {
                            c.floorBloodSplats.remove(ix);
                            ix--;
                        }
                    }
                } else if (op == 9 && f % 10 == 9) {
                    c.floorBloodSplats.clear();
                    c.floorBloodSplatsFade.clear();
                } else if (op == 10 && !c.floorBloodSplatsFade.isEmpty()) {
                    int i = random.nextInt(c.floorBloodSplatsFade.size());
                    c.floorBloodSplatsFade.set(i, c.floorBloodSplatsFade.get(i));
                } else if (op == 11 && !c.floorBloodSplatsFade.isEmpty()) {
                    c.floorBloodSplatsFade.remove(c.floorBloodSplatsFade.get(random.nextInt(c.floorBloodSplatsFade.size())));
                } else if (op == 12 && !sorted && c.floorBloodSplatsFade.size() > 2) {
                    sorted = true;
                    c.floorBloodSplatsFade.sort((a, b) -> 0);
                }
            }
        }

        void digestState(MessageDigest d) {
            for (IsoChunk c : chunks) {
                feed(d, c.floorBloodSplats.size());
                for (int i = 0; i < c.floorBloodSplats.size(); i++) {
                    splat(d, c.floorBloodSplats.get(i));
                }
                feed(d, c.floorBloodSplatsFade.size());
                for (IsoFloorBloodSplat b : c.floorBloodSplatsFade) {
                    splat(d, b);
                }
            }
        }

        void splat(MessageDigest d, IsoFloorBloodSplat b) {
            feed(d, ids.get(b));
            feed(d, b.fade);
            feed(d, b.chunk == null ? -1 : chunkIds.get(b.chunk));
        }
    }

    static void feed(MessageDigest d, int v) {
        d.update((byte) (v >>> 24));
        d.update((byte) (v >>> 16));
        d.update((byte) (v >>> 8));
        d.update((byte) v);
    }

    // ---- 替身（兩份 FBORenderCell 共用；只在 renderOneLevel_Blood 兩個多載裡改道）----

    public static boolean debugValue(BooleanDebugOption option) {
        return true;
    }

    public static Core core() {
        return null;
    }

    public static int bloodOption(Core core) {
        return option;
    }

    public static GameTime gameTime() {
        return null;
    }

    public static double worldAge(GameTime time) {
        return WORLD_AGE;
    }

    public static FBORenderCutaways.ChunkLevelData cutaway(IsoChunk chunk, int level) {
        return null;
    }

    public static IsoGridSquare square(IsoChunk chunk, int x, int y, int z) {
        lastKey = (((long) chunk.wx * 31 + chunk.wy) * 31 + x) * 31 * 31 + (long) y * 31 + z;
        lastKey ^= lastKey >>> 7;
        return Math.floorMod(lastKey, 11) == 0 ? null : sentinel;
    }

    public static boolean shouldRender(FBORenderCutaways.ChunkLevelData data, int playerIndex, IsoGridSquare square) {
        return square != null && Math.floorMod(lastKey, 5) != 0;
    }

    public static boolean blackedOut(Object cell, IsoGridSquare square) {
        return Math.floorMod(lastKey, 7) == 0;
    }

    public static int vertLight(IsoGridSquare square, int i, int playerIndex) {
        long h = lastKey * 0x9E3779B97F4A7C15L + i * 0x632BE59BD9B4E019L;
        return (int) (h ^ (h >>> 29));
    }

    public static void render(IsoSprite sprite, float x, float y, float z, ColorInfo info) {
        renders++;
        MessageDigest d = frameDigest;
        feed(d, SPRITE_TYPE.get(sprite));
        feed(d, Float.floatToRawIntBits(x));
        feed(d, Float.floatToRawIntBits(y));
        feed(d, Float.floatToRawIntBits(z));
        feed(d, Float.floatToRawIntBits(info.r));
        feed(d, Float.floatToRawIntBits(info.g));
        feed(d, Float.floatToRawIntBits(info.b));
        feed(d, Float.floatToRawIntBits(info.a));
    }

    public static IsoChunk chunkAt(IsoCell cell, int wx, int wy) {
        return world.at(wx, wy);
    }

    // ---- 類別載入 ----

    static byte[] standIns(byte[] bytes) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!name.equals("renderOneLevel_Blood")) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        String[] to = STAND_INS.get(owner + "." + n + d);
                        if (to == null) {
                            super.visitMethodInsn(op, owner, n, d, itf);
                        } else {
                            standIns++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, to[0], to[1], false);
                        }
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /** 只自己定義 FBORenderCell 與它的巢狀類別（原名），其餘交給父 loader：IsoChunk、清單與 helper 都是同一份。 */
    static Class<?> isolated(byte[] cell, Map<String, byte[]> nested) throws Exception {
        Map<String, byte[]> own = new HashMap<>(nested);
        own.put(CELL, cell);
        ClassLoader loader = new ClassLoader(BloodSplatIndexBehaviorTest.class.getClassLoader()) {
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
        return Class.forName(CELL.replace('/', '.'), true, loader);
    }

    static Path jarPath() throws Exception {
        return Path.of(IsoFloorBloodSplat.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    static int check(String what, boolean ok) {
        OUT.println((ok ? "behavior OK   " : "behavior FAIL ") + what);
        return ok ? 0 : 1;
    }

    private BloodSplatIndexBehaviorTest() {}
}
