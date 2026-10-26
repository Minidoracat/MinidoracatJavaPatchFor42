package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.component.NetworkZombieComponent;
import zombie.characters.ecs.ECSComponent;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.popman.NetworkZombieList;
import zombie.popman.NetworkZombieManager;

/**
 * W80 回收的殭屍物件清掉上一輪的網路擁有者（ZombieReuseOwner）行為驗證。
 *
 * <p>走 dist 內手術後的真 {@code VirtualZombieManager.reuseZombie}＋helper，對照 jar 內原版 {@code reuseZombie}；兩份各自用
 * 隔離 loader 載入，共用同一個 {@code IsoZombie}。測試 JVM 跑不了真的 {@code IsoZombie.resetForReuse()}（要 SandboxOptions、
 * 動作群組與動畫），所以兩邊的 {@code invokevirtual IsoZombie.resetForReuse()} 都 1:1 換成本類別的同形替身 {@link #resetStandIn}
 * （記下呼叫次數與當下的擁有者，可要求丟例外）；原版 resetForReuse 不碰擁有者由 SmokeCheck 釘住。擁有者走真的
 * {@code NetworkZombieComponent}（{@code setOwner}／{@code getOwner} 原碼），擁有清單走真的 {@code NetworkZombieManager}
 * （{@code recheck} 原碼）。argv：jar、dist、{@code on}／{@code off}，自驗旗標。
 */
public final class ZombieReuseOwnerTest {
    static final String VZM = "zombie/VirtualZombieManager";
    static final String HELPER = "zombie/mdc/ZombieReuseOwner";
    static final String ZOMBIE = "zombie/characters/IsoZombie";
    static final String TAG = "[MinidoracatJavaPatch][ZombieReuseOwner]";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    static int standIns;
    static int resets;
    static UdpConnection ownerAtReset;
    static IsoPlayer playerAtReset;
    static boolean throwOnReset;

    private static int failed;

    /** 替身：原版 resetForReuse 的位置。記下呼叫當下的擁有者，證明 helper 在原方法之後才清。 */
    public static void resetStandIn(IsoZombie z) {
        resets++;
        ownerAtReset = z.getOwner();
        playerAtReset = z.getOwnerPlayer();
        if (throwOnReset) {
            throw new IllegalStateException("resetForReuse 失敗（測試）");
        }
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();   // DebugLog 第一次寫行時經 GameClient → ServerOptions 呼叫 Rand
        String jar = args[0];
        Path dist = Path.of(args[1]);
        String mode = args[2];
        boolean wantOn = "on".equals(mode);
        expect("自驗：argv=" + mode + " 與實際旗標相符（-Dmdc.zombieReuseOwner=" + System.getProperty("mdc.zombieReuseOwner") + "）",
                ZombieReuseOwner.ENABLED == wantOn);
        expect("parseEnabled：null/1/on/亂寫→on、0/off/ OFF →off",
                ZombieReuseOwner.parseEnabled(null) && ZombieReuseOwner.parseEnabled("1") && ZombieReuseOwner.parseEnabled("on")
                        && ZombieReuseOwner.parseEnabled("x") && !ZombieReuseOwner.parseEnabled("0")
                        && !ZombieReuseOwner.parseEnabled("off") && !ZombieReuseOwner.parseEnabled(" OFF "));

        byte[] vanillaVzm;
        try (ZipFile zf = new ZipFile(jar)) {
            vanillaVzm = zf.getInputStream(zf.getEntry(VZM + ".class")).readAllBytes();
        }
        ClassLoader patchedLoader = isolated(Map.of(
                VZM, standIn(Files.readAllBytes(dist.resolve(VZM + ".class"))),
                HELPER, standIn(Files.readAllBytes(dist.resolve(HELPER + ".class")))));
        int patchedStandIns = standIns;
        Runner patched = new Runner(patchedLoader.loadClass(VZM.replace('/', '.')));
        Class<?> helper = patchedLoader.loadClass(HELPER.replace('/', '.'));
        Runner vanilla = new Runner(isolated(Map.of(VZM, standIn(vanillaVzm))).loadClass(VZM.replace('/', '.')));
        expect("替身：手術後那份只在 helper 換 1 處（reuseZombie 已改道），原版 reuseZombie 換 1 處",
                patchedStandIns == 1 && standIns == 2);
        expect("隔離的 helper 讀到同一個開關，且是手術後那份 reuseZombie 呼叫的那個",
                helper != ZombieReuseOwner.class && helper.getClassLoader() == patchedLoader
                        && staticBool(helper, "ENABLED") == wantOn);

        ByteArrayOutputStream log = captureLog();
        for (int i = 0; i < 3; i++) {
            LogFilter.log(LogFilter.MOVE_ZOMBIE_MISS);
        }
        UdpConnection x = alloc(UdpConnection.class);
        IsoPlayer p = alloc(IsoPlayer.class);
        // X 的擁有清單（真的 NetworkZombieManager，兩個 Runner 共用同一份）
        Field owns = NetworkZombieManager.class.getDeclaredField("owns");
        owns.setAccessible(true);
        LinkedList<IsoZombie> xList = ((NetworkZombieList) owns.get(NetworkZombieManager.getInstance())).getNetworkZombie(x).zombies;

        // ---- 帶上一輪擁有者（連線＋玩家）進池：原版留著，on 清掉，off 照原版 ----
        for (Runner r : new Runner[]{vanilla, patched}) {
            boolean clears = r == patched && wantOn;
            String who = r == vanilla ? "原版" : "手術後";
            IsoZombie z = zombie(x, p);
            int before = resets;
            r.reuse(z);
            expect(who + "：原 resetForReuse 恰一次，呼叫當下擁有者仍是上一輪的（helper 在它之後才清）",
                    resets == before + 1 && ownerAtReset == x && playerAtReset == p);
            expect(who + "：進回收池", r.isReused(z));
            expect(who + (clears ? "：擁有者與擁有玩家都清成 null" : "：擁有者與擁有玩家照舊（原版負對照）"),
                    clears ? z.getOwner() == null && z.getOwnerPlayer() == null : z.getOwner() == x && z.getOwnerPlayer() == p);

            // 已在池裡：原版直接 return，不呼叫 resetForReuse，helper 也不跑
            z.setOwner(x);
            z.setOwnerPlayer(p);
            before = resets;
            r.reuse(z);
            expect(who + "：已在池裡的再進一次照原版早退，不呼叫原方法、不動擁有者",
                    resets == before && z.getOwner() == x && z.getOwnerPlayer() == p);
        }

        // ---- X 的清單還留著這個物件（死亡與回收同一輪、中間停超過 5 秒）：on 一併拿掉，X 名下其他殭屍留著 ----
        // stray：已歸 Y 卻還留在 X 清單裡；on 的 recheck 照原版語意一併拿掉，off 不呼叫 recheck 所以留著。
        UdpConnection y = alloc(UdpConnection.class);
        for (Runner r : new Runner[]{vanilla, patched}) {
            boolean clears = r == patched && wantOn;
            String who = r == vanilla ? "原版" : "手術後";
            IsoZombie z = zombie(x, p);
            IsoZombie other = zombie(x, p);
            IsoZombie stray = zombie(y, p);
            xList.clear();
            xList.add(z);
            xList.add(other);
            xList.add(stray);
            r.reuse(z);
            expect(who + (clears ? "：X 的清單拿掉回收的物件與已歸別人的殘留項、留著 X 名下其他殭屍（同原版 recheck）"
                            : "：X 的清單整份照舊（原版負對照；off 不呼叫 recheck）"),
                    clears ? xList.equals(List.of(other)) : xList.equals(List.of(z, other, stray)));
        }
        xList.clear();

        // ---- 只有擁有玩家、沒有擁有者連線：一樣清 ----
        IsoZombie onlyPlayer = zombie(null, p);
        patched.reuse(onlyPlayer);
        expect("只帶擁有玩家：" + (wantOn ? "清成 null" : "照舊"),
                wantOn ? onlyPlayer.getOwnerPlayer() == null : onlyPlayer.getOwnerPlayer() == p);

        // ---- 沒有擁有者：照原版，不計 stale ----
        IsoZombie clean = zombie(null, null);
        patched.reuse(clean);
        expect("沒有擁有者：仍是 null、進回收池", clean.getOwner() == null && clean.getOwnerPlayer() == null && patched.isReused(clean));

        // ---- 原方法丟例外：兩邊都原樣上拋、不進池、擁有者不動 ----
        throwOnReset = true;
        for (Runner r : new Runner[]{vanilla, patched}) {
            String who = r == vanilla ? "原版" : "手術後";
            IsoZombie z = zombie(x, p);
            Throwable thrown = r.reuseThrows(z);
            expect(who + "：resetForReuse 的例外原樣上拋，不進池、擁有者不動",
                    thrown instanceof IllegalStateException && !r.isReused(z) && z.getOwner() == x && z.getOwnerPlayer() == p);
        }
        throwOnReset = false;

        long reused = (long) accessor(helper, "reusedForTest");
        long stale = (long) accessor(helper, "staleForTest");
        expect("計數：手術後成功回收 4 次（帶擁有者、清單還留著、只帶玩家、乾淨），其中 3 次帶擁有者（stale 與開關無關）",
                reused == 4 && stale == 3);
        expect("零 anomalies", (long) accessor(helper, "anomaliesForTest") == 0);
        String first = log.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains(TAG)).findFirst().orElse("");
        expect("首次生效行帶模式、累計與抑噪 #5 的 nzMissing（" + first.trim() + "）",
                first.contains("首次生效") && first.contains("mode=" + (wantOn ? "on" : "off") + " reused=1 stale=1 nzMissing=3 anomalies=0"));

        if (failed > 0) {
            OUT.println("zombie-reuse-owner FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("zombie-reuse-owner OK mode=" + mode);
    }

    /** 隔離載入的 VirtualZombieManager：私有 reuseZombie 用反射呼叫，每個 Runner 一個新實例（自己的回收池）。 */
    static final class Runner {
        final Object vzm;
        final Method reuse;
        final Method isReused;

        Runner(Class<?> cls) throws Exception {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            vzm = ctor.newInstance();
            reuse = cls.getDeclaredMethod("reuseZombie", IsoZombie.class);
            reuse.setAccessible(true);
            isReused = cls.getMethod("isReused", IsoZombie.class);
        }

        void reuse(IsoZombie z) throws Exception {
            reuse.invoke(vzm, z);
        }

        Throwable reuseThrows(IsoZombie z) throws Exception {
            try {
                reuse.invoke(vzm, z);
                return null;
            } catch (InvocationTargetException e) {
                return e.getCause();
            }
        }

        boolean isReused(IsoZombie z) throws Exception {
            return (boolean) isReused.invoke(vzm, z);
        }
    }

    /** 死後 removeFromWorld 過的殭屍物件：onlineId −1，擁有者放在真的 NetworkZombieComponent。 */
    private static IsoZombie zombie(UdpConnection owner, IsoPlayer player) throws Exception {
        IsoZombie z = alloc(IsoZombie.class);
        Field ecs = findField(IsoZombie.class, "ecsComponentMap");
        ecs.setAccessible(true);
        ecs.set(z, new HashMap<>());
        // 建構子會建 NetworkZombieAI（要整套角色狀態）：配置後只補上建構子設的 ECS 類別鍵（NetworkComponent）。
        NetworkZombieComponent component = alloc(NetworkZombieComponent.class);
        Field ecsClass = findField(NetworkZombieComponent.class, "ecsClass");
        ecsClass.setAccessible(true);
        ecsClass.set(component, ECSComponent.getECSClass(NetworkZombieComponent.class));
        z.setECSComponent(component);
        z.onlineId = -1;
        z.setOwner(owner);
        z.setOwnerPlayer(player);
        if (z.getOwner() != owner || z.getOwnerPlayer() != player) {
            throw new IllegalStateException("擁有者沒寫進 NetworkZombieComponent");
        }
        return z;
    }

    /** 把類別內每個 {@code invokevirtual IsoZombie.resetForReuse()V} 換成 {@link #resetStandIn}（同形：receiver → 參數）。 */
    static byte[] standIn(byte[] bytes) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (op == Opcodes.INVOKEVIRTUAL && owner.equals(ZOMBIE) && n.equals("resetForReuse") && d.equals("()V")) {
                            standIns++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "zombie/mdc/ZombieReuseOwnerTest", "resetStandIn",
                                    "(L" + ZOMBIE + ";)V", false);
                        } else {
                            super.visitMethodInsn(op, owner, n, d, itf);
                        }
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /** 只自己定義 {@code own} 裡的類別（原名），其餘交給父 loader：IsoZombie、LogFilter 與本測試都是同一份。 */
    static ClassLoader isolated(Map<String, byte[]> own) {
        return new ClassLoader(ZombieReuseOwnerTest.class.getClassLoader()) {
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

    // 隔離的 helper 與本測試是不同的 runtime package：package-private 存取器用反射讀。
    private static Object accessor(Class<?> cls, String name) throws Exception {
        Method m = cls.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    private static boolean staticBool(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        OUT.println((ok ? "zro pass  " : "zro FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private ZombieReuseOwnerTest() {}
}
