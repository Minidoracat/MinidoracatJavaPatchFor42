import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * W28：真 addZombieStanding／addZombieMoving 缺格路徑的互斥、例外解鎖與重入回歸。
 * 每輪以獨立 loader 載入 vanilla 負對照；on/off 由 build.ps1 分別啟動 JVM。
 * 只替換 native body；IsoWorld 初始化／建構子與 IsoCell 建構子／缺格查詢為 fixture。
 * 遊戲 jar 內的類別與 zombie.* helper 採 child-first，避免跨 package 的繼承鏈被兩個 loader 拆開。
 */
public final class PopManAddLockTest {

    private static final String ZPM = "zombie/popman/ZombiePopulationManager";
    private static final String HELPER_CLASS = "zombie.mdc.PopManAddLock";
    private static final String N_ADD_DESC = "(FFFBIIII)V";

    /** 條件等待上限（不是猜排程的 sleep：所有等待都以「條件成立」收斂，逾時＝斷言失敗）。 */
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(15);

    private static final float X = 1024.5f;
    private static final float Y = 2048.25f;
    private static final float Z = 0.0f;
    private static final int DESCRIPTOR_ID = 77;
    private static final int STATE = 42;
    private static final int PATH_TARGET_X = 1234;
    private static final int PATH_TARGET_Y = 5678;

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[2].equals("on") || args[2].equals("off"))) {
            System.out.println("popman-add-lock FAIL 用法：PopManAddLockTest <distJava> <gameJar> <on|off>");
            System.exit(2);
            return;
        }
        Path distJava = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        String mode = args[2];
        boolean lockExpected = mode.equals("on");

        // 自驗：off 這輪若忘了帶 property，helper 會是開鎖狀態而「什麼都通過」——先把它釘死。
        String flag = System.getProperty("mdc.popmanAddLock");
        boolean killed = "0".equals(flag) || "off".equals(flag);
        check("自驗：mode=" + mode + " 必須搭配 -Dmdc.popmanAddLock" + (lockExpected ? " 不為 0/off" : "=0/off")
                + "（實際 " + flag + "）", killed != lockExpected);

        try (Lane vanilla = new Lane("vanilla", jar, null);
             Lane patched = new Lane("patched", jar, distJava)) {

            for (boolean movingVariant : new boolean[]{false, true}) {
                String what = movingVariant ? "addZombieMoving" : "addZombieStanding";
                // 負對照：未修補的真呼叫端在他人持鎖時照樣進原生（缺口本體，修好前後都必須成立）
                expectNoExclusion(vanilla, movingVariant, "vanilla " + what);
                if (lockExpected) {
                    expectExclusion(patched, movingVariant, "patched/on " + what);
                } else {
                    expectNoExclusion(patched, movingVariant, "patched/off " + what);
                }
            }

            // 例外契約與解鎖：三種 throwable 類別（unchecked／Error／checked）各自不得被包裝或吞掉
            for (Throwable t : new Throwable[]{
                    new RuntimeException("popman-add-lock native boom"),
                    new Error("popman-add-lock native error"),
                    new Exception("popman-add-lock native checked")}) {
                expectPassthrough(patched, t);
            }

            if (lockExpected) {
                expectReentrantPreserved(patched);
            }
        }

        System.out.println("popman-add-lock OK  mode=" + mode);
    }

    // ---------------------------------------------------------------- 情境

    /** patched/on：background 持鎖 ⇒ 真呼叫端必排隊，原生在取鎖前不得進入。 */
    private static void expectExclusion(Lane lane, boolean movingVariant, String label) throws Exception {
        Native.reset(lane.saveLock);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holder(lane.saveLock, held, release);
        Throwable[] thrown = new Throwable[1];
        Thread caller = caller(label, () -> thrown[0] = lane.invokeCatching(movingVariant));
        try {
            holder.start();
            check(label + "：background 取得 saveLock", await(held));
            caller.start();
            check(label + "：background 持鎖期間呼叫端排隊等 saveLock", awaitQueued(lane.saveLock, caller));
            check(label + "：排隊期間原生尚未進入", Native.CALLS.get() == 0);
        } finally {
            release.countDown();
            check(label + "：holder／caller 在期限內結束", joined(holder) & joined(caller));
        }
        check(label + "：解鎖後原生恰執行一次，進入時呼叫端持鎖",
                Native.CALLS.get() == 1 && Native.heldByCallerAtEnter);
        check(label + "：呼叫端正常返回且鎖已完全釋放",
                thrown[0] == null && !lane.saveLock.isLocked());
    }

    /**
     * vanilla（缺口本體）與 patched/off（kill switch）：background 持鎖時呼叫端照進原生。
     * 用 gate 把原生停在鎖內，主執行緒才能在「確實同時」的狀態下觀察，而不是事後推測。
     */
    private static void expectNoExclusion(Lane lane, boolean movingVariant, String label) throws Exception {
        Native.reset(lane.saveLock);
        Native.entered = new CountDownLatch(1);
        Native.gate = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holder(lane.saveLock, held, release);
        Throwable[] thrown = new Throwable[1];
        Thread caller = caller(label, () -> thrown[0] = lane.invokeCatching(movingVariant));
        try {
            holder.start();
            check(label + "：background 取得 saveLock", await(held));
            caller.start();
            check(label + "：background 持鎖期間呼叫端直接進入原生", await(Native.entered));
            check(label + "：鎖仍在他人手上、呼叫端未持鎖也未排隊",
                    lane.saveLock.isLocked() && !Native.heldByCallerAtEnter
                            && !lane.saveLock.hasQueuedThread(caller));
        } finally {
            Native.gate.countDown();
            release.countDown();
            check(label + "：holder／caller 在期限內結束", joined(holder) & joined(caller));
        }
        check(label + "：原生恰執行一次且呼叫端正常返回", Native.CALLS.get() == 1 && thrown[0] == null);
    }

    /** 原生例外原物件穿透 ＋ finally 已解鎖（他執行緒可立刻取鎖）。 */
    private static void expectPassthrough(Lane lane, Throwable boom) throws Exception {
        Native.reset(lane.saveLock);
        Native.throwOnEnter = boom;
        try {
            Throwable got = lane.invokeCatching(false);
            String kind = boom.getClass().getSimpleName();
            check("patched " + kind + "：原生例外原物件穿透回原呼叫端", got == boom);
            check("patched " + kind + "：原生仍恰執行一次", Native.CALLS.get() == 1);
            check("patched " + kind + "：例外後他執行緒可取得 saveLock",
                    !lane.saveLock.isLocked() && lockableFromOtherThread(lane.saveLock));
        } finally {
            while (lane.saveLock.isHeldByCurrentThread()) {
                lane.saveLock.unlock();
            }
        }
    }

    /** 呼叫端已持鎖：返回後原持有數不變。 */
    private static void expectReentrantPreserved(Lane lane) throws Exception {
        Native.reset(lane.saveLock);
        lane.saveLock.lock();
        try {
            lane.invoke(false);
            check("patched/on 重入：原生執行時呼叫端持鎖", Native.heldByCallerAtEnter);
            check("patched/on 重入：返回後呼叫端持有數仍為 1、鎖仍在手",
                    lane.saveLock.getHoldCount() == 1 && lane.saveLock.isHeldByCurrentThread());
            check("patched/on 重入：原生恰執行一次", Native.CALLS.get() == 1);
        } finally {
            while (lane.saveLock.isHeldByCurrentThread()) {
                lane.saveLock.unlock();
            }
        }
        check("patched/on 重入：呼叫端解鎖後完全釋放", !lane.saveLock.isLocked());
    }

    // ---------------------------------------------------------------- 同步小工具

    private static Thread holder(ReentrantLock lock, CountDownLatch held, CountDownLatch release) {
        Thread t = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                if (!await(release)) {
                    throw new AssertionError("holder release timeout");
                }
            } finally {
                lock.unlock();
            }
        }, "saveLock-holder");
        t.setDaemon(true);
        return t;
    }

    /** 受測呼叫端執行緒：daemon＋有界 join，任何掛死都收斂成一條紅色而不是整個建置停住。 */
    private static Thread caller(String label, Runnable body) {
        Thread t = new Thread(body, label + "-caller");
        t.setDaemon(true);
        return t;
    }

    private static boolean joined(Thread t) {
        try {
            t.join(TimeUnit.NANOSECONDS.toMillis(DEADLINE_NANOS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(DEADLINE_NANOS, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 等到 thread 真的進了 saveLock 的等待隊列；它先跑完（沒排隊）就是沒有互斥。 */
    private static boolean awaitQueued(ReentrantLock lock, Thread t) {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        while (System.nanoTime() < deadline) {
            if (lock.hasQueuedThread(t)) {
                return true;
            }
            if (!t.isAlive()) {
                return false;
            }
            LockSupport.parkNanos(100_000L);
        }
        return false;
    }

    private static boolean lockableFromOtherThread(ReentrantLock lock) throws Exception {
        boolean[] ok = new boolean[1];
        Thread t = new Thread(() -> {
            try {
                if (lock.tryLock(DEADLINE_NANOS, TimeUnit.NANOSECONDS)) {
                    ok[0] = true;
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "relock-probe");
        t.setDaemon(true);
        t.start();
        check("relock-probe 在期限內結束", joined(t));
        return ok[0];
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "popman-add-lock OK   " : "popman-add-lock FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    // ---------------------------------------------------------------- 原生替身

    /**
     * {@code n_addZombie} 的測試替身（真 libPZPopMan 不在測試機上）。
     * 由 loader 把真方法的 body 換成一個 1:1 轉呼這裡的 forwarder，因此呼叫端方法、
     * saveLock、helper 與 MethodHandle 都是實碼——被換掉的只有「原生那一層」。
     */
    public static final class Native {

        static final AtomicInteger CALLS = new AtomicInteger();

        static volatile ReentrantLock lock;
        static volatile CountDownLatch entered;
        static volatile CountDownLatch gate;
        static volatile Throwable throwOnEnter;

        static volatile boolean heldByCallerAtEnter;

        static void reset(ReentrantLock saveLock) {
            lock = saveLock;
            CALLS.set(0);
            entered = null;
            gate = null;
            throwOnEnter = null;
            heldByCallerAtEnter = false;
        }

        public static void enter(float x, float y, float z, byte dir, int descriptorID, int state,
                int pathTargetX, int pathTargetY) throws Throwable {
            CALLS.incrementAndGet();
            ReentrantLock l = lock;
            heldByCallerAtEnter = l != null && l.isHeldByCurrentThread();
            CountDownLatch signal = entered;
            if (signal != null) {
                signal.countDown();
            }
            CountDownLatch hold = gate;
            if (hold != null && !await(hold)) {
                throw new AssertionError("native gate timeout");
            }
            Throwable boom = throwOnEnter;
            if (boom != null) {
                throw boom;
            }
        }

        private Native() {}
    }

    // ---------------------------------------------------------------- lane（一份獨立的遊戲世界）

    /** 一條受測世界：自己的 ClassLoader、自己的 ZPM 與 saveLock、備好缺格 fallback 的 fixture。 */
    private static final class Lane implements AutoCloseable {

        private final GameLoader loader;
        final ReentrantLock saveLock;
        private final Object manager;
        private final Object dir;
        private final Object flags;
        private final Method standing;
        private final Method moving;

        Lane(String name, Path jar, Path distJava) throws Exception {
            loader = new GameLoader(name, jar, distJava);

            // fixture：把世界換成「方格一律未載入」的空殼，讓真呼叫端自己走進 fallback 分支
            Class<?> isoWorld = loader.loadClass("zombie.iso.IsoWorld");
            Class<?> isoCell = loader.loadClass("zombie.iso.IsoCell");
            Constructor<?> worldCtor = isoWorld.getDeclaredConstructor();
            worldCtor.setAccessible(true);
            Object world = worldCtor.newInstance();
            Constructor<?> cellCtor = isoCell.getDeclaredConstructor(int.class, int.class);
            cellCtor.setAccessible(true);
            isoWorld.getField("currentCell").set(world, cellCtor.newInstance(0, 0));
            isoWorld.getField("instance").set(null, world);

            Class<?> zpm = loader.loadClass("zombie.popman.ZombiePopulationManager");
            java.lang.reflect.Field lockField = zpm.getDeclaredField("saveLock");
            lockField.setAccessible(true);
            saveLock = (ReentrantLock) lockField.get(null);
            java.lang.reflect.Field instanceField = zpm.getDeclaredField("instance");
            instanceField.setAccessible(true);
            manager = instanceField.get(null);

            Class<?> directions = loader.loadClass("zombie.iso.IsoDirections");
            dir = directions.getEnumConstants()[3];
            Class<?> stateFlags = loader.loadClass("zombie.popman.ZombieStateFlags");
            flags = stateFlags.getConstructor(int.class).newInstance(STATE);

            standing = zpm.getDeclaredMethod("addZombieStanding", float.class, float.class, float.class,
                    directions, int.class, stateFlags);
            standing.setAccessible(true);
            moving = zpm.getDeclaredMethod("addZombieMoving", float.class, float.class, float.class,
                    directions, int.class, stateFlags, int.class, int.class);
            moving.setAccessible(true);

            if (distJava != null) {
                // helper 的 clinit（MethodHandle 解析＋橫幅）先跑掉，別混進後面的時序觀察窗；
                // 解析失敗會在這裡就炸（fail-fast），不會退化成「靜默沒上鎖」。
                Class.forName(HELPER_CLASS, true, loader);
            }
        }

        void invoke(boolean movingVariant) throws Exception {
            if (movingVariant) {
                moving.invoke(manager, X, Y, Z, dir, DESCRIPTOR_ID, flags, PATH_TARGET_X, PATH_TARGET_Y);
            } else {
                standing.invoke(manager, X, Y, Z, dir, DESCRIPTOR_ID, flags);
            }
        }

        Throwable invokeCatching(boolean movingVariant) {
            try {
                invoke(movingVariant);
                return null;
            } catch (InvocationTargetException e) {
                return e.getCause();
            } catch (Throwable t) {
                return t;
            }
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }

    // ---------------------------------------------------------------- loader

    /**
     * 遊戲類別一律由自己載入（包含 fmod 等跨 package 子類）；JDK 與測試 recorder 委派 parent。
     * patched lane 優先讀 {@code dist\java}（含 helper 與改道後的 caller），其餘回落遊戲 jar。
     */
    private static final class GameLoader extends ClassLoader implements AutoCloseable {

        private final ZipFile jar;
        private final Path distJava;

        GameLoader(String name, Path jarPath, Path distJava) throws Exception {
            super("game-" + name, PopManAddLockTest.class.getClassLoader());
            this.jar = new ZipFile(jarPath.toFile());
            this.distJava = distJava;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("zombie.") && jar.getEntry(name.replace('.', '/') + ".class") == null) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    found = findClass(name);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            byte[] raw = null;
            try {
                if (distJava != null) {
                    Path onDisk = distJava.resolve(path);
                    if (Files.isRegularFile(onDisk)) {
                        raw = Files.readAllBytes(onDisk);
                    }
                }
                if (raw == null) {
                    ZipEntry entry = jar.getEntry(path);
                    if (entry != null) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            raw = in.readAllBytes();
                        }
                    }
                }
            } catch (Exception e) {
                throw new ClassNotFoundException(name, e);
            }
            if (raw == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = transform(name.replace('.', '/'), raw);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        public void close() throws Exception {
            jar.close();
        }
    }

    // ---------------------------------------------------------------- bytecode fixture

    private static byte[] transform(String internalName, byte[] raw) throws ClassNotFoundException {
        if (internalName.equals(ZPM)) {
            // 唯一動到受測類別的地方：原生那一層換成測試替身（同名同 desc 同 private static）
            ClassNode cn = read(raw);
            MethodNode nAdd = method(cn, "n_addZombie", N_ADD_DESC);
            nAdd.access &= ~Opcodes.ACC_NATIVE;
            blank(nAdd, 8, 8);
            nAdd.instructions.add(new VarInsnNode(Opcodes.FLOAD, 0));
            nAdd.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
            nAdd.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
            nAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
            nAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
            nAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
            nAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
            nAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 7));
            nAdd.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "PopManAddLockTest$Native", "enter", N_ADD_DESC, false));
            nAdd.instructions.add(new InsnNode(Opcodes.RETURN));
            return write(cn);
        }
        if (internalName.equals("zombie/iso/IsoWorld")) {
            // fixture：整個世界的 static init 在測試 JVM 起不來，而 fallback 只需要 instance 非 null
            ClassNode cn = read(raw);
            MethodNode clinit = method(cn, "<clinit>", "()V");
            blank(clinit, 0, 0);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            superOnly(cn, method(cn, "<init>", "()V"), 1);
            return write(cn);
        }
        if (internalName.equals("zombie/iso/IsoCell")) {
            // fixture＝「空 cell」：方格一律未載入，真呼叫端因此走進 n_addZombie fallback
            ClassNode cn = read(raw);
            superOnly(cn, method(cn, "<init>", "(II)V"), 3);
            MethodNode getGridSquare = method(cn, "getGridSquare", "(III)Lzombie/iso/IsoGridSquare;");
            blank(getGridSquare, 1, 4);
            getGridSquare.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            getGridSquare.instructions.add(new InsnNode(Opcodes.ARETURN));
            return write(cn);
        }
        return raw;
    }

    private static ClassNode read(byte[] raw) {
        ClassNode cn = new ClassNode();
        new ClassReader(raw).accept(cn, 0);
        return cn;
    }

    private static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static MethodNode method(ClassNode cn, String name, String desc) throws ClassNotFoundException {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        // PZ 改了簽名就在這裡炸掉，而不是默默少掛一個 fixture 讓測試假通過
        throw new ClassNotFoundException(cn.name + "." + name + desc + " 不存在（jar 形狀已變，需重評本測試）");
    }

    /** 清空方法 body（含 try/catch 與 debug 資訊），改寫後的 body 都是無分支直線碼，不需要 frames。 */
    private static void blank(MethodNode m, int maxStack, int maxLocals) {
        m.instructions.clear();
        m.tryCatchBlocks = new ArrayList<>();
        m.localVariables = new ArrayList<>();
        m.visibleLocalVariableAnnotations = null;
        m.invisibleLocalVariableAnnotations = null;
        m.maxStack = maxStack;
        m.maxLocals = maxLocals;
    }

    private static void superOnly(ClassNode cn, MethodNode ctor, int maxLocals) {
        blank(ctor, 1, maxLocals);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cn.superName, "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    private PopManAddLockTest() {}
}
