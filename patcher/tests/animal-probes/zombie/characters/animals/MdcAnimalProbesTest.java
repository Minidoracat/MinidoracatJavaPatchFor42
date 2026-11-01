package zombie.characters.animals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import zombie.MovingObjectUpdateScheduler;
import zombie.ai.State;
import zombie.ai.states.animals.AnimalIdleState;
import zombie.ai.states.animals.AnimalWalkState;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.entity.Component;
import zombie.entity.ComponentContainer;
import zombie.entity.ComponentType;
import zombie.entity.EntityBucket;
import zombie.entity.GameEntity;
import zombie.entity.components.fluids.FluidContainer;
import zombie.entity.util.Array;
import zombie.entity.util.BitSet;
import zombie.entity.util.ImmutableArray;
import zombie.iso.IsoObject;
import zombie.iso.Vector3;
import zombie.network.GameServer;

/**
 * W95 純觀測探針（三個共用 {@code -Dmdc.animalProbes}）：
 * <ul>
 *   <li>a0：helper 真的呼叫原版 {@code AnimalZones.updateVirtualAnimals}（原版開頭會清掉 {@code doneVirtualAnimals}，放一個哨兵看它被清掉），
 *       observe 計數；心跳的 loadedCells 掃描：2 個 cell（一個 chunks 是 null）、3 個 chunk（一個空的）、3 隻虛擬動物（2 隻遷徙群組沒有定義
 *       ＝idle、1 隻 rabbit）、1 個足跡。</li>
 *   <li>F1：回傳原版同一個陣列；5 個實體（2 個 rainCatcher &gt; 0、1 個 0、1 個沒有液體容器、1 個液體容器的 rainCatcher 是負的），
 *       第 64 次呼叫抽樣數到 2。</li>
 *   <li>sim：一條連線（relevantRange 7 → 半徑 66 格），4 隻動物：近的、遠而閒置、遠而在走、遠而閒置且懷孕壓力 90；只在 frame % 16 == 0 的幀數，
 *       wouldPct 50、pregStress 1；不在主執行緒、frame 17 不數。</li>
 * </ul>
 * sim 的例外（動物替身的 {@code getCurrentState} 拋）：不計入 animals（計數在兩個判斷之後）、anomalies 1、記一行例外類別與訊息。
 * off 一律原樣轉呼叫、不計數。{@code logthrow}（配 observe）：真的 DebugLog 輸出導到會拋例外的串流，首次生效、心跳與 anomaly 記錄爆掉時
 * a0 照原版呼叫（哨兵被清掉）、F1 回原版同一個陣列、例外不外拋。用法：{@code MdcAnimalProbesTest observe|off|logthrow}。
 */
public final class MdcAnimalProbesTest {

    private static int failed;
    /** logthrow：日誌串流改拋 OutOfMemoryError（證明 Error 不被吞）。 */
    static boolean oom;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args[0];
        if (mode.equals("logthrow")) {
            logThrow();
            finish(mode);
            return;
        }
        boolean off = mode.equals("off");
        java.io.ByteArrayOutputStream logBuf = new java.io.ByteArrayOutputStream();
        zombie.debug.DebugType.General.setLogSeverity(zombie.debug.LogSeverity.All);
        zombie.debug.DebugLog.getInstance().setStdOut(logBuf);
        check("模式與參數一致（" + MdcAnimalProbes.modeName() + "）", MdcAnimalProbes.MODE == (off ? MdcAnimalProbes.OFF : MdcAnimalProbes.OBSERVE));
        GameServer.server = true;
        GameServer.mainThread = Thread.currentThread();

        // ---- a0 ----
        Field done = AnimalZones.class.getDeclaredField("doneVirtualAnimals");
        done.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Object> doneSet = (Set<Object>) done.get(AnimalZones.getInstance());
        doneSet.add("sentinel");
        MdcAnimalProbes.updateVirtualAnimals();
        check(mode + "：a0 照原版呼叫 updateVirtualAnimals（哨兵被清掉）、計數 " + (off ? 0 : 1),
                doneSet.isEmpty() && MdcAnimalProbes.vaCallsForTest() == (off ? 0 : 1));

        AnimalManagerWorker worker = alloc(AnimalManagerWorker.class);
        set(AnimalManagerWorker.class, worker, "loadedCells", new ArrayList<AnimalCell>());
        set(AnimalManagerWorker.class, null, "instance", worker);
        AnimalCell cell = alloc(AnimalCell.class);
        AnimalChunk[] chunks = new AnimalChunk[4];
        chunks[0] = chunk(va(""), va("rabbit"));
        chunks[1] = chunk(va("cow"));
        chunks[2] = chunk();
        chunks[2].animalTracks.add(alloc(AnimalTracks.class));
        chunks[3] = chunk();
        cell.chunks = chunks;
        worker.loadedCells.add(cell);
        worker.loadedCells.add(alloc(AnimalCell.class));
        HashMap<String, MigrationGroupDefinitions> defs = new HashMap<>();
        defs.put("rabbit", new MigrationGroupDefinitions());
        MigrationGroupDefinitions.migrationDef = defs;
        String scan = MdcAnimalProbes.scanVirtual();
        check("a0 掃描（" + scan + "）", scan.equals("cells=1 chunks=3 va=3 vaIdle=2 vaMoving=1 tracks=1"));

        // ---- F1 ----
        // IsoObjectBucket（protected 巢狀類別）的建構子與 updateMembership 都不在本 package 可見範圍，用反射
        java.lang.reflect.Constructor<?> bucketCtor =
                Class.forName("zombie.entity.EntityBucket$IsoObjectBucket").getDeclaredConstructor(int.class);
        bucketCtor.setAccessible(true);
        EntityBucket bucket = (EntityBucket) bucketCtor.newInstance(3);
        java.lang.reflect.Method membership = EntityBucket.class.getDeclaredMethod("updateMembership", GameEntity.class);
        membership.setAccessible(true);
        float[] rain = {1.0F, 0.0F, 2.5F, Float.NaN, -1.0F};
        for (float r : rain) {
            membership.invoke(bucket, entity(r));
        }
        check("前提：bucket 內 5 個", bucket.getEntities().size() == 5);
        boolean sameArray = true;
        for (int i = 0; i < 64; i++) {
            sameArray &= MdcAnimalProbes.fluidEntities(bucket) == bucket.getEntities();
        }
        check(mode + "：F1 64 次都回原版同一個陣列；" + (off ? "不計數" : "計 64 次、第 64 次抽樣數到 2 個 rainCatcher > 0"),
                sameArray && MdcAnimalProbes.fluidCallsForTest() == (off ? 0 : 64)
                        && MdcAnimalProbes.fluidScansForTest() == (off ? 0 : 1) && MdcAnimalProbes.rcSumForTest() == (off ? 0 : 2));

        // ---- sim ----
        UdpEngine engine = alloc(UdpEngine.class);
        List<UdpConnection> conns = new ArrayList<>();
        UdpConnection c = alloc(UdpConnection.class);
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.players = new IsoPlayer[4];
        c.releventPos[0] = new Vector3(1000.0F, 1000.0F, 0.0F);
        c.setRelevantRange((byte) 7);
        conns.add(c);
        conns.add(null);
        set(UdpEngine.class, engine, "connections", conns);
        GameServer.udpEngine = engine;
        ProbeAnimal near = animal(1060.0F, AnimalIdleState.instance(), false);
        ProbeAnimal farIdle = animal(1070.0F, AnimalIdleState.instance(), false);
        ProbeAnimal farWalking = animal(1100.0F, AnimalWalkState.instance(), false);
        ProbeAnimal farPregnant = animal(1200.0F, AnimalIdleState.instance(), true);
        ProbeAnimal[] all = {near, farIdle, farWalking, farPregnant};
        Field frameCounter = MovingObjectUpdateScheduler.class.getDeclaredField("frameCounter");
        frameCounter.setAccessible(true);
        frameCounter.setLong(MovingObjectUpdateScheduler.instance, 16L);
        for (ProbeAnimal a : all) {
            MdcAnimalProbes.sim(a);
        }
        frameCounter.setLong(MovingObjectUpdateScheduler.instance, 17L);
        for (ProbeAnimal a : all) {
            MdcAnimalProbes.sim(a);
        }
        Thread other = new Thread(() -> MdcAnimalProbes.sim(farIdle));
        frameCounter.setLong(MovingObjectUpdateScheduler.instance, 32L);
        other.start();
        other.join();
        check(mode + "：sim 只數 frame % 16 == 0 那幀、只在主執行緒（animals=" + MdcAnimalProbes.simAnimalsForTest() + " would="
                        + MdcAnimalProbes.simWouldForTest() + " pregStress=" + MdcAnimalProbes.simPregStressForTest() + "）",
                MdcAnimalProbes.simAnimalsForTest() == (off ? 0 : 4) && MdcAnimalProbes.simWouldForTest() == (off ? 0 : 2)
                        && MdcAnimalProbes.simPregStressForTest() == (off ? 0 : 1));
        check("sim 不改動物（狀態、座標照舊）", farIdle.state == AnimalIdleState.instance() && farIdle.getX() == 1070.0F);

        if (!off) {
            MdcAnimalProbes.forceBeatForTest();
            String line = MdcAnimalProbes.lastBeatLine;
            check("心跳（" + line + "）", line != null && line.contains("mode=observe") && line.contains(" vaCalls=1 ")
                    && line.contains(" cells=1 chunks=3 va=3 vaIdle=2 vaMoving=1 tracks=1") && line.contains(" fluidCalls=64 ")
                    && line.contains(" bucketMax=5 ") && line.contains(" rcAvg=2 ") && line.contains(" simFrames=1 ")
                    && line.contains(" animals=4 ") && line.contains(" wouldPct=50.0 ") && line.contains(" pregStress=1.0 ")
                    && line.contains(" anomalies=0"));
        } else {
            check("off：anomalies 0", MdcAnimalProbes.anomaliesForTest() == 0);
        }

        // sim 的例外：still() 讀狀態時拋；不計入 animals、記 anomaly 與例外類別訊息
        ProbeAnimal boom = animal(1070.0F, AnimalIdleState.instance(), false);
        boom.boom = true;
        long animals0 = MdcAnimalProbes.simAnimalsForTest();
        frameCounter.setLong(MovingObjectUpdateScheduler.instance, 48L);
        MdcAnimalProbes.sim(boom);
        String log = logBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
        if (!off) {
            check("sim 例外：不計入 animals（" + (MdcAnimalProbes.simAnimalsForTest() - animals0) + "）、anomalies 1、記一行例外類別與訊息",
                    MdcAnimalProbes.simAnimalsForTest() == animals0 && MdcAnimalProbes.anomaliesForTest() == 1
                            && log.contains("[MinidoracatJavaPatch][AnimalProbes] anomaly #1 sim java.lang.IllegalStateException: <test> state boom"));
        } else {
            check("off：sim 不呼叫判斷、不記 anomaly、整個 JVM 沒有本 tag 的記錄", MdcAnimalProbes.anomaliesForTest() == 0
                    && !log.contains("[MinidoracatJavaPatch][AnimalProbes]"));
        }
        finish(mode);
    }

    /** 日誌鏈拋例外：首次生效、心跳、anomaly 的記錄爆掉時，a0／F1 的原版呼叫照常完成。 */
    static void logThrow() throws Exception {
        check("logthrow 配 observe（" + MdcAnimalProbes.modeName() + "）", MdcAnimalProbes.MODE == MdcAnimalProbes.OBSERVE);
        GameServer.server = true;
        GameServer.mainThread = Thread.currentThread();
        zombie.debug.DebugType.General.setLogSeverity(zombie.debug.LogSeverity.All);
        zombie.debug.DebugLog.getInstance().setStdOut(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                if (oom) {
                    throw new OutOfMemoryError("<test> logger oom");
                }
                throw new IllegalStateException("<test> logger exploded");
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (oom) {
                    throw new OutOfMemoryError("<test> logger oom");
                }
                throw new IllegalStateException("<test> logger exploded");
            }
        });
        Throwable self = null;
        try {
            zombie.debug.DebugLog.log("[MdcAnimalProbesTest] self-check");
        } catch (Throwable e) {
            self = e;
        }
        check("自驗：DebugLog.log 真的拋例外（" + self + "）", self instanceof IllegalStateException);
        Field done = AnimalZones.class.getDeclaredField("doneVirtualAnimals");
        done.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Object> doneSet = (Set<Object>) done.get(AnimalZones.getInstance());
        AnimalManagerWorker worker = alloc(AnimalManagerWorker.class);
        set(AnimalManagerWorker.class, worker, "loadedCells", new ArrayList<AnimalCell>());
        set(AnimalManagerWorker.class, null, "instance", worker);

        doneSet.add("sentinel");
        Throwable t = null;
        try {
            MdcAnimalProbes.updateVirtualAnimals();
        } catch (Throwable e) {
            t = e;
        }
        check("首次生效記錄拋例外：a0 照原版呼叫（哨兵被清掉）、例外不外拋（" + t + "）、記下 anomalies",
                t == null && doneSet.isEmpty() && MdcAnimalProbes.vaCallsForTest() == 1 && MdcAnimalProbes.anomaliesForTest() >= 1);

        // anomaly 記錄也爆掉：sim 的例外
        UdpEngine engine = alloc(UdpEngine.class);
        set(UdpEngine.class, engine, "connections", new ArrayList<UdpConnection>());
        GameServer.udpEngine = engine;
        Field frameCounter = MovingObjectUpdateScheduler.class.getDeclaredField("frameCounter");
        frameCounter.setAccessible(true);
        frameCounter.setLong(MovingObjectUpdateScheduler.instance, 16L);
        ProbeAnimal boom = animal(1070.0F, AnimalIdleState.instance(), false);
        boom.boom = true;
        long a0 = MdcAnimalProbes.anomaliesForTest();
        t = null;
        try {
            MdcAnimalProbes.sim(boom);
        } catch (Throwable e) {
            t = e;
        }
        check("anomaly 記錄拋例外：sim 例外不外拋（" + t + "）、anomalies 增加", t == null && MdcAnimalProbes.anomaliesForTest() > a0);

        MdcAnimalProbes.armBeatForTest();
        doneSet.add("sentinel");
        t = null;
        try {
            MdcAnimalProbes.updateVirtualAnimals();
        } catch (Throwable e) {
            t = e;
        }
        String line = MdcAnimalProbes.lastBeatLine;
        check("心跳記錄拋例外：a0 照原版呼叫（哨兵被清掉）、例外不外拋（" + t + "）、心跳行有組出來（" + line + "）",
                t == null && doneSet.isEmpty() && line != null && line.startsWith("beat "));

        java.lang.reflect.Constructor<?> bucketCtor =
                Class.forName("zombie.entity.EntityBucket$IsoObjectBucket").getDeclaredConstructor(int.class);
        bucketCtor.setAccessible(true);
        EntityBucket bucket = (EntityBucket) bucketCtor.newInstance(3);
        boolean same = true;
        for (int i = 0; i < 64; i++) {
            same &= MdcAnimalProbes.fluidEntities(bucket) == bucket.getEntities();
        }
        check("F1 照回原版同一個陣列（日誌壞掉時也一樣）", same);
        oom = true;
        MdcAnimalProbes.armBeatForTest();
        doneSet.add("sentinel");
        t = null;
        try {
            MdcAnimalProbes.updateVirtualAnimals();
        } catch (Throwable e) {
            t = e;
        }
        check("Error 照常外拋：心跳的日誌鏈拋 OutOfMemoryError 時不被吞（" + t + "），原版已先跑完（哨兵被清掉）",
                t instanceof OutOfMemoryError && doneSet.isEmpty());
    }

    /** 狀態由測試指定（原版 getCurrentState 走 ECS）。其餘條件都是欄位讀取，Unsafe 配置後是 null／false。 */
    static final class ProbeAnimal extends IsoAnimal {
        State state;
        boolean boom;

        ProbeAnimal() {
            super(null);
        }

        @Override
        public State getCurrentState() {
            if (boom) {
                throw new IllegalStateException("<test> state boom");
            }
            return state;
        }
    }

    static ProbeAnimal animal(float x, State state, boolean pregnantStressed) throws Exception {
        ProbeAnimal a = alloc(ProbeAnimal.class);
        a.setX(x);
        a.setY(1000.0F);
        a.state = state;
        set(IsoGameCharacter.class, a, "health", 1.0F);
        if (pregnantStressed) {
            AnimalData d = alloc(AnimalData.class);
            d.pregnant = true;
            set(IsoAnimal.class, a, "data", d);
            a.stressLevel = 90.0F;
        }
        return a;
    }

    static VirtualAnimal va(String group) throws Exception {
        VirtualAnimal v = alloc(VirtualAnimal.class);
        v.migrationGroup = group;
        return v;
    }

    static AnimalChunk chunk(VirtualAnimal... animals) throws Exception {
        AnimalChunk ch = alloc(AnimalChunk.class);
        set(AnimalChunk.class, ch, "animals", new ArrayList<>(List.of(animals)));
        set(AnimalChunk.class, ch, "animalTracks", new ArrayList<AnimalTracks>());
        return ch;
    }

    /** 未初始化的 IsoObject，ComponentContainer 有 bucketBits；rainCatcher 是 NaN 時不放液體容器。 */
    static IsoObject entity(float rainCatcher) throws Exception {
        IsoObject o = alloc(IsoObject.class);
        ComponentContainer cc = alloc(ComponentContainer.class);
        Array<Component> list = new Array<>(false, 1);
        Component[] byId = new Component[ComponentType.MAX_ID_INDEX + 1];
        if (!Float.isNaN(rainCatcher)) {
            FluidContainer fc = alloc(FluidContainer.class);
            set(FluidContainer.class, fc, "rainCatcher", rainCatcher);
            byId[ComponentType.FluidContainer.GetID()] = fc;
            list.add(fc);
        } else {
            list.add(null);
        }
        set(ComponentContainer.class, cc, "componentList", list);
        set(ComponentContainer.class, cc, "componentArray", byId);
        set(ComponentContainer.class, cc, "bucketBits", new BitSet());
        set(GameEntity.class, o, "components", cc);
        return o;
    }

    private static void finish(String mode) {
        System.out.println(failed == 0 ? "MdcAnimalProbesTest OK (" + mode + ")" : "MdcAnimalProbesTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private MdcAnimalProbesTest() {}
}
