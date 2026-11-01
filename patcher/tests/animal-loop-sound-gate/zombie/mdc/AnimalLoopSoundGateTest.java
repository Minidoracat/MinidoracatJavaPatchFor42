package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import zombie.SoundManager;
import zombie.characters.CharacterSoundEmitter;
import zombie.characters.DummyCharacterSoundEmitter;
import zombie.characters.IsoGameCharacter;
import zombie.characters.animals.AnimalSoundState;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalBreed;
import zombie.characters.animals.datas.AnimalData;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/**
 * W92：真的 {@code IsoCell.ProcessObjects}（dist 手術後；vanilla 時是 jar 原版），objectList 放 5 隻動物（1 隻在鉤子上）與 1 個非動物，
 * 動物是覆寫 {@code updateVocalProperties}／{@code updateLoopingSounds} 只計數的替身（emitter 是 {@code DummyCharacterSoundEmitter}，
 * 其中一隻有 runloop 聲音、eventInstance 0）。跑 512 幀：observe／off 與原版一樣每幀對 4 隻各呼叫一次；on 一次都不呼叫、
 * 照原版跑 updateZombieVocals；observe 量得到迴圈時間；第 256、512 幀稽核、anomalies 0。接著三種違反前提（emitter 換成會出聲的、
 * eventInstance 非 0、SoundManager.instance 換成別的類別）：observe 只記 anomalies，on 在下一次稽核停用並回到原版迴圈，心跳照印。
 *
 * <p>{@code logthrow}（配 {@code -Dmdc.animalLoopSoundGate=on} 與 {@code observe} 各跑一次）：把真的 DebugLog 輸出導到會拋例外的串流，
 * 首次生效、心跳、停用三種記錄都爆掉時 {@code ProcessObjects} 照常跑完：on 在前提不成立的稽核幀停用並回原版 iterator（那幀照原版呼叫 4 隻），
 * observe 在稽核時 helper 自己拋例外（objectList 的 iterator 拋一次）也先停用、回原版 iterator。
 *
 * <p>用法：{@code AnimalLoopSoundGateTest vanilla|off|observe|on|logthrow}。vanilla 的 classpath 不含 dist：只驗原版每幀都跑整段迴圈（負對照）。
 */
public final class AnimalLoopSoundGateTest {

    private static int failed;
    /** logthrow：日誌串流改拋 OutOfMemoryError（證明 Error 不被吞）。 */
    static boolean oom;
    static int loopCalls;
    static int vocalCalls;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args[0];
        boolean vanilla = mode.equals("vanilla");
        GameServer.server = true;
        SoundManager.instance = alloc(SoundManager.class);
        IsoCell cell = alloc(IsoCell.class);
        ThrowOnceSet objects = new ThrowOnceSet();
        set(IsoCell.class, cell, "objectList", objects);
        ArrayList<Object> zombies = new ArrayList<>();
        set(IsoCell.class, cell, "zombieList", zombies);
        CountingAnimal[] animals = new CountingAnimal[5];
        for (int i = 0; i < animals.length; i++) {
            animals[i] = animal(i == 4);
            objects.add(animals[i]);
        }
        objects.add(alloc(zombie.vehicles.BaseVehicle.class));
        AnimalSoundState runState = breedWithRunLoop(animals[0]);
        boolean patched = !vanilla;
        Method processObjects = IsoCell.class.getDeclaredMethod("ProcessObjects", Iterator.class);
        processObjects.setAccessible(true);
        if (mode.equals("logthrow")) {
            logThrow(processObjects, cell, objects, animals);
            finish(mode + "-" + AnimalLoopSoundGate.modeName());
            return;
        }
        if (patched) {
            int want = mode.equals("off") ? AnimalLoopSoundGate.OFF : mode.equals("on") ? AnimalLoopSoundGate.ON : AnimalLoopSoundGate.OBSERVE;
            check("模式與參數一致（" + AnimalLoopSoundGate.modeName() + "）", AnimalLoopSoundGate.MODE == want);
        }

        for (int f = 0; f < 512; f++) {
            processObjects.invoke(cell, objects.iterator());
        }
        boolean skips = mode.equals("on");
        check((vanilla ? "原版" : mode) + "：512 幀 updateLoopingSounds " + (skips ? "0 次" : "每幀對 4 隻不在鉤子上的動物各一次")
                        + "（實際 " + loopCalls + "／updateVocalProperties " + vocalCalls + "）",
                loopCalls == (skips ? 0 : 4 * 512) && vocalCalls == loopCalls);
        // 迴圈之後照原版跑 updateZombieVocals：zombieList 放一個不是 IsoZombie 的物件，原版 checkcast 會拋 ClassCastException
        zombies.add("not a zombie");
        Throwable vocals = null;
        try {
            processObjects.invoke(cell, objects.iterator());
        } catch (java.lang.reflect.InvocationTargetException e) {
            vocals = e.getCause();
        }
        zombies.clear();
        check("迴圈之後照原版跑到 updateZombieVocals（zombieList 的非殭屍物件拋 " + vocals + "）", vocals instanceof ClassCastException);
        if (vanilla) {
            finish(mode);
            return;
        }
        check("稽核 " + (mode.equals("off") ? "0" : "2") + " 次、anomalies 0、沒有停用（audits=" + AnimalLoopSoundGate.auditsForTest() + "）",
                AnimalLoopSoundGate.auditsForTest() == (mode.equals("off") ? 0 : 2) && AnimalLoopSoundGate.anomaliesForTest() == 0
                        && !AnimalLoopSoundGate.disabledForTest());
        check("observe 每 64 幀量一次整段迴圈（samples=" + AnimalLoopSoundGate.loopSamplesForTest() + "）",
                AnimalLoopSoundGate.loopSamplesForTest() == (mode.equals("observe") ? 8 : 0));
        check("稽核經 getAnimalSoundState 讀 runloop 的狀態而且沒改它（eventInstance 0）", runState.getEventInstance() == 0L);

        // 違反前提 1：emitter 換成會出聲的 CharacterSoundEmitter
        Field emitter = IsoGameCharacter.class.getDeclaredField("emitter");
        emitter.setAccessible(true);
        emitter.set(animals[1], alloc(CharacterSoundEmitter.class));
        runFrames(processObjects, cell, objects, 256);
        expectAfterViolation(mode, "emitter 不是 DummyCharacterSoundEmitter", 1);
        emitter.set(animals[1], alloc(DummyCharacterSoundEmitter.class));
        // 違反前提 2：runloop 的 eventInstance 非 0
        set(AnimalSoundState.class, runState, "eventInstance", 7L);
        runFrames(processObjects, cell, objects, 256);
        expectAfterViolation(mode, "runloop eventInstance 非 0", 2);
        set(AnimalSoundState.class, runState, "eventInstance", 0L);
        // 違反前提 3：SoundManager.instance 不是原版 SoundManager（換成別的 BaseSoundManager）
        SoundManager.instance = alloc(zombie.DummySoundManager.class);
        runFrames(processObjects, cell, objects, 256);
        expectAfterViolation(mode, "SoundManager.instance 不是 SoundManager", 3);
        SoundManager.instance = alloc(SoundManager.class);

        if (!mode.equals("off")) {
            int before = loopCalls;
            processObjects.invoke(cell, objects.iterator());
            check(mode + "：最後一幀" + (mode.equals("on") ? "停用後回到原版迴圈" : "照原版迴圈"), loopCalls == before + 4);
            AnimalLoopSoundGate.forceBeatForTest();
            processObjects.invoke(cell, objects.iterator());
            String line = AnimalLoopSoundGate.lastBeatLine;
            check("心跳（" + line + "）", line != null && line.contains("mode=" + mode) && line.contains(" objects=6")
                    && line.contains(" animals=4") && line.contains(" disabled=" + mode.equals("on")));
        }
        finish(mode);
    }

    /** 日誌鏈拋例外：首次生效、心跳、停用三種記錄爆掉時原版呼叫照常完成。 */
    static void logThrow(Method processObjects, IsoCell cell, ThrowOnceSet objects, CountingAnimal[] animals) throws Exception {
        boolean on = AnimalLoopSoundGate.MODE == AnimalLoopSoundGate.ON;
        check("logthrow 只配 on 或 observe（" + AnimalLoopSoundGate.modeName() + "）", AnimalLoopSoundGate.MODE != AnimalLoopSoundGate.OFF);
        explodeLog();
        int perFrame = on ? 0 : 4;
        int before = loopCalls;
        Throwable t = frame(processObjects, cell, objects);
        check("首次生效記錄拋例外：ProcessObjects 照常跑完（" + t + "）、迴圈照模式（" + (loopCalls - before) + " 次）、沒有停用、記下 anomalies",
                t == null && loopCalls - before == perFrame && !AnimalLoopSoundGate.disabledForTest() && AnimalLoopSoundGate.anomaliesForTest() >= 1);
        for (int f = 2; f <= 10; f++) {
            frame(processObjects, cell, objects);
        }
        AnimalLoopSoundGate.forceBeatForTest();
        before = loopCalls;
        t = frame(processObjects, cell, objects);
        String line = AnimalLoopSoundGate.lastBeatLine;
        check("心跳記錄拋例外：ProcessObjects 照常跑完（" + t + "）、迴圈照模式、心跳行有組出來（" + line + "）",
                t == null && loopCalls - before == perFrame && line != null && line.startsWith("beat ") && !AnimalLoopSoundGate.disabledForTest());
        // 心跳把 frames 歸 0：再跑 255 幀，下一幀就是第 256 幀（稽核）
        for (int f = 1; f < 256; f++) {
            frame(processObjects, cell, objects);
        }
        if (on) {
            Field emitter = IsoGameCharacter.class.getDeclaredField("emitter");
            emitter.setAccessible(true);
            emitter.set(animals[1], alloc(CharacterSoundEmitter.class));
        } else {
            objects.armed = true;
        }
        before = loopCalls;
        t = frame(processObjects, cell, objects);
        check((on ? "on：第 256 幀稽核抓到前提不成立" : "observe：第 256 幀稽核時 helper 自己拋例外") + "，anomaly／停用記錄都拋例外："
                        + "ProcessObjects 照常跑完（" + t + "）、已停用、這幀就回原版 iterator（呼叫 " + (loopCalls - before) + " 次）",
                t == null && AnimalLoopSoundGate.disabledForTest() && loopCalls - before == 4 && !objects.armed);
        before = loopCalls;
        t = frame(processObjects, cell, objects);
        check("停用後下一幀照原版迴圈", t == null && loopCalls - before == 4);
        oom = true;
        AnimalLoopSoundGate.forceBeatForTest();
        t = frame(processObjects, cell, objects);
        check("Error 照常外拋：日誌鏈拋 OutOfMemoryError 時不被吞，從 ProcessObjects 拋出（" + t + "）", t instanceof OutOfMemoryError);
    }

    /** 跑一幀；回傳 ProcessObjects 拋出的例外（沒有就 null）。參數 iterator 先取好，避免消耗 objectList 預備好的那次例外。 */
    static Throwable frame(Method processObjects, IsoCell cell, ThrowOnceSet objects) throws Exception {
        Iterator<IsoMovingObject> arg = new ArrayList<IsoMovingObject>().iterator();
        try {
            processObjects.invoke(cell, arg);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        }
    }

    /** 真的 DebugLog 開到 All、輸出導到一寫就拋例外的串流；先自驗 logger 真的會拋。 */
    static void explodeLog() {
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
            zombie.debug.DebugLog.log("[AnimalLoopSoundGateTest] self-check");
        } catch (Throwable e) {
            self = e;
        }
        check("自驗：DebugLog.log 真的拋例外（" + self + "）", self instanceof IllegalStateException);
    }

    /** objectList 替身：{@code armed} 時下一次 {@code iterator()} 拋一次例外（模擬稽核時 helper 自己失敗）。 */
    static final class ThrowOnceSet extends HashSet<IsoMovingObject> {
        boolean armed;

        @Override
        public Iterator<IsoMovingObject> iterator() {
            if (armed) {
                armed = false;
                throw new IllegalStateException("<test> audit iterator boom");
            }
            return super.iterator();
        }
    }

    static void runFrames(Method processObjects, IsoCell cell, Set<IsoMovingObject> objects, int n) throws Exception {
        for (int f = 0; f < n; f++) {
            processObjects.invoke(cell, objects.iterator());
        }
    }

    static void expectAfterViolation(String mode, String what, int nth) {
        long anomalies = AnimalLoopSoundGate.anomaliesForTest();
        boolean disabled = AnimalLoopSoundGate.disabledForTest();
        if (mode.equals("off")) {
            check("off：" + what + " 不稽核", anomalies == 0 && !disabled);
        } else if (mode.equals("observe")) {
            check("observe：" + what + " 在下一次稽核記 anomalies（累計 " + anomalies + "）、不停用", anomalies >= nth && !disabled);
        } else if (nth == 1) {
            check("on：" + what + " 在下一次稽核記 anomalies 並停用", anomalies == 1 && disabled);
        } else {
            check("on：停用後不再稽核（" + what + " 不記）", anomalies == 1 && disabled);
        }
    }

    /** 只計數的動物替身：原版這兩個方法在伺服器上沒有輸出，這裡只記有沒有被呼叫。 */
    static final class CountingAnimal extends IsoAnimal {
        CountingAnimal() {
            super(null);
        }

        @Override
        public void updateVocalProperties() {
            vocalCalls++;
        }

        @Override
        public void updateLoopingSounds() {
            loopCalls++;
        }
    }

    static CountingAnimal animal(boolean onHook) throws Exception {
        CountingAnimal a = alloc(CountingAnimal.class);
        Field emitter = IsoGameCharacter.class.getDeclaredField("emitter");
        emitter.setAccessible(true);
        emitter.set(a, alloc(DummyCharacterSoundEmitter.class));
        set(IsoAnimal.class, a, "animalSoundState", new HashMap<String, AnimalSoundState>());
        if (onHook) {
            set(IsoAnimal.class, a, "onHook", true);
        }
        return a;
    }

    /** 給這隻動物一個有 runloop 聲音（slot "run"）的品種，先建好那個 slot 的 AnimalSoundState 並回傳。 */
    static AnimalSoundState breedWithRunLoop(IsoAnimal a) throws Exception {
        AnimalBreed breed = alloc(AnimalBreed.class);
        HashMap<String, AnimalBreed.Sound> sounds = new HashMap<>();
        AnimalBreed.Sound run = new AnimalBreed.Sound();
        run.id = "runloop";
        run.slot = "run";
        sounds.put("runloop", run);
        set(AnimalBreed.class, breed, "sounds", sounds);
        AnimalData data = alloc(AnimalData.class);
        set(AnimalData.class, data, "breed", breed);
        set(IsoAnimal.class, a, "data", data);
        return a.getAnimalSoundState("run");
    }

    private static void finish(String mode) {
        System.out.println(failed == 0 ? "AnimalLoopSoundGateTest OK (" + mode + ")" : "AnimalLoopSoundGateTest FAILED " + failed);
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

    private AnimalLoopSoundGateTest() {}
}
