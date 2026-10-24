import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * W70 BanditRaidLoopGuard 行為驗證（argv：distJava jar on|off；off 須帶 {@code -Dmdc.banditRaidLoopGuard=off}）。
 *
 * <p>同一個 JVM 載入兩份 {@code RDSBanditRaid}：jar 裡的原版，與 dist 內 W70 手術後的版本。兩份都只把碰世界與亂數的呼叫
 * 換成 {@link BanditRaidProbe} 的同形替身（世界格子一律 null、亂數照種子），迴圈、計數器與分支照原 bytecode 跑，
 * 以「替身被呼叫的順序」當行為紀錄比對。
 *
 * <p>鎖：49 間房以下兩份紀錄逐筆相同（含取房間落空的分支）；50–100 間房且大樓裡永遠找得到房間時，原版停不下來（超過替身呼叫上限），
 * on 照 45 間房的紀錄跑完、clamped 增加；off（kill switch）跟原版一樣停不下來。
 */
public final class BanditRaidLoopGuardTest {
    static final String RAID = "zombie.randomizedWorld.randomizedDeadSurvivor.RDSBanditRaid";
    static final String RAID_INTERNAL = RAID.replace('.', '/');
    static final String PROBE = "BanditRaidProbe";
    static final String METHOD = "randomizeDeadSurvivor";
    static final String METHOD_DESC = "(Lzombie/iso/BuildingDef;)V";

    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dist = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        boolean on = !(args.length > 2 && "off".equals(args[2]));
        URLClassLoader game = new URLClassLoader(new URL[]{
                dist.toUri().toURL(), jar.toUri().toURL(),
                BanditRaidLoopGuardTest.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader());
        Class<?> guard = Class.forName("zombie.mdc.BanditRaidLoopGuard", true, game);
        // BuildingDef 建構子用到 Rand；方法內的 Rand 呼叫另外換成替身
        Class<?> randStandard = Class.forName("zombie.core.random.RandStandard", true, game);
        randStandard.getMethod("init").invoke(randStandard.getField("INSTANCE").get(null));
        expect("開關與 argv 相符（" + (on ? "on" : "off") + "）", getStatic(guard, "ENABLED").equals(on));

        Map<String, Integer> vanillaHits = new HashMap<>();
        Map<String, Integer> patchedHits = new HashMap<>();
        Runner vanilla = new Runner(game, transform(readJarClass(jar), vanillaHits));
        Runner patched = new Runner(game, transform(Files.readAllBytes(dist.resolve(RAID_INTERNAL + ".class")), patchedHits));
        expect("替身換掉的呼叫數兩份相同（原版 " + vanillaHits + "）", vanillaHits.equals(patchedHits));
        expect("替身換掉全部世界與亂數呼叫（Rand 15、房間 6、殭屍 3、屍體 3＋3、車 1、世界 3）",
                vanillaHits.equals(Map.of("Rand", 15, "room", 6, "addZombies", 3, "createRandomDeadBody", 3,
                        "getRandomSquareForCorpse", 3, "spawnCarOnNearestNav", 1, "world", 3)));

        // 49 間房以下：逐筆相同（含取房間落空）
        int diffs = 0;
        for (int rooms = 1; rooms <= 49; rooms++) {
            for (long seed = 1; seed <= 40; seed++) {
                String v = vanilla.run(rooms, seed, 0.25, 5000);
                String p = patched.run(rooms, seed, 0.25, 5000);
                if (v == null || !v.equals(p)) {
                    diffs++;
                }
            }
        }
        expect("1–49 間房：原版與手術後的替身呼叫紀錄逐筆相同（1960 組，不同 " + diffs + "）", diffs == 0);

        // 50–100 間房、永遠找得到房間：原版停不下來
        int vanillaStops = 0;
        for (int rooms = 50; rooms <= 100; rooms += 5) {
            for (long seed = 1; seed <= 3; seed++) {
                if (vanilla.run(rooms, seed, 0.0, 5000) != null) {
                    vanillaStops++;
                }
            }
        }
        expect("50–100 間房（原版負對照）：33 組全部超過替身呼叫上限（跑完 " + vanillaStops + "）", vanillaStops == 0);

        long before = (Long) call(guard, "clampedForTest");
        int done = 0;
        int sameAs45 = 0;
        int maxEvents = 0;
        for (int rooms = 50; rooms <= 100; rooms++) {
            for (long seed = 1; seed <= 20; seed++) {
                String p = patched.run(rooms, seed, 0.0, 5000);
                if (p != null) {
                    done++;
                    maxEvents = Math.max(maxEvents, (Integer) getStatic(probe(game), "events"));
                    if (p.equals(vanilla.run(45, seed, 0.0, 5000))) {
                        sameAs45++;
                    }
                }
            }
        }
        long clamped = (Long) call(guard, "clampedForTest") - before;
        if (on) {
            expect("on：50–100 間房 1020 組全部跑完（跑完 " + done + "，最多 " + maxEvents + " 次替身呼叫）", done == 1020);
            expect("on：每組都跟同種子的 45 間房紀錄相同（相同 " + sameAs45 + "）", sameAs45 == 1020);
            expect("on：每組各 clamped 一次（" + clamped + "）", clamped == 1020);
        } else {
            expect("off（kill switch）：50–100 間房跟原版一樣停不下來（跑完 " + done + "）", done == 0);
            expect("off：不計 clamped（" + clamped + "）", clamped == 0);
        }

        // helper 本身
        double[][] cases = {{0.6, 0}, {3.9, 3}, {4.0, 4}, {4.9, 4}, {5.0, on ? 4 : 5}, {10.0, on ? 4 : 10}, {-1.5, -2}};
        boolean helperOk = true;
        for (double[] c : cases) {
            helperOk &= (Double) guard.getMethod("floor", double.class).invoke(null, c[0]) == c[1];
        }
        expect("helper floor：4 以下照 Math.floor，5 以上 on 回 4、off 照原值", helperOk);
        expect("helper floor：NaN 照原值", Double.isNaN((Double) guard.getMethod("floor", double.class).invoke(null, Double.NaN)));

        if (failed > 0) {
            System.out.println("BanditRaidLoopGuardTest FAILED " + failed);
            System.exit(1);
        }
        System.out.println("BanditRaidLoopGuardTest（" + (on ? "on" : "off") + "）全數通過");
    }

    /** 一份 RDSBanditRaid（原版或手術後）＋共用的替身。 */
    static final class Runner {
        final Class<?> probe;
        final Object raid;
        final Method randomize;
        final Class<?> defClass;
        final Class<?> roomClass;

        Runner(URLClassLoader game, byte[] raidBytes) throws Exception {
            ClassLoader own = new ClassLoader(game) {
                Class<?> defined;

                @Override
                protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!RAID.equals(name)) {
                        return super.loadClass(name, resolve);
                    }
                    if (defined == null) {
                        defined = defineClass(name, raidBytes, 0, raidBytes.length);
                    }
                    return defined;
                }
            };
            Class<?> cls = Class.forName(RAID, true, own);
            raid = cls.getConstructor().newInstance();
            defClass = Class.forName("zombie.iso.BuildingDef", true, game);
            roomClass = Class.forName("zombie.iso.RoomDef", true, game);
            randomize = cls.getMethod(METHOD, defClass);
            probe = probe(game);
        }

        /** 跑一次；超過替身呼叫上限回 null。 */
        String run(int rooms, long seed, double nullRoom, int limit) throws Exception {
            Object def = defClass.getConstructor().newInstance();
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) defClass.getMethod("getRooms").invoke(def);
            for (int i = 0; i < rooms; i++) {
                list.add(roomClass.getConstructor(long.class, String.class).newInstance((long) i, "room" + i));
            }
            probe.getDeclaredMethod("reset", long.class, double.class, int.class).invoke(null, seed, nullRoom, limit);
            try {
                randomize.invoke(raid, def);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalStateException s && "LOOP_LIMIT".equals(s.getMessage())) {
                    return null;
                }
                throw e;
            }
            return getStatic(probe, "trace").toString();
        }
    }

    static Class<?> probe(ClassLoader game) throws ClassNotFoundException {
        return Class.forName(PROBE, true, game);
    }

    static byte[] readJarClass(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile()); InputStream in = zip.getInputStream(zip.getEntry(RAID_INTERNAL + ".class"))) {
            return in.readAllBytes();
        }
    }

    /** 只改 randomizeDeadSurvivor：世界與亂數呼叫換成 BanditRaidProbe 的同形 static 方法，其餘指令（含 W70 改道）不動。 */
    static byte[] transform(byte[] raw, Map<String, Integer> hits) {
        ClassReader reader = new ClassReader(raw);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!METHOD.equals(name) || !METHOD_DESC.equals(desc)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                        if ("zombie/iso/IsoWorld".equals(owner) && "instance".equals(fname)) {
                            count(hits, "world");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "world", "()Lzombie/iso/IsoWorld;", false);
                        } else if ("zombie/iso/IsoWorld".equals(owner) && "currentCell".equals(fname)) {
                            count(hits, "world");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "cell", "(Lzombie/iso/IsoWorld;)Lzombie/iso/IsoCell;", false);
                        } else {
                            super.visitFieldInsn(opcode, owner, fname, fdesc);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                        if ("zombie/core/random/Rand".equals(owner)) {
                            String stub = switch (mname + mdesc) {
                                case "Next(I)I" -> "next";
                                case "Next(II)I" -> "next2";
                                case "NextBool(I)Z" -> "nextBool";
                                default -> throw new IllegalStateException("未預期的 Rand 呼叫 " + mname + mdesc);
                            };
                            count(hits, "Rand");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, stub, mdesc, false);
                        } else if ("zombie/iso/IsoCell".equals(owner) && "getGridSquare".equals(mname)) {
                            count(hits, "world");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "square",
                                    "(Lzombie/iso/IsoCell;III)Lzombie/iso/IsoGridSquare;", false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL && RAID_INTERNAL.equals(owner)) {
                            String key = switch (mname) {
                                case "getLivingRoomOrKitchen", "getRandomRoomNoKids", "getRandomRoom" -> "room";
                                case "addZombies", "spawnCarOnNearestNav" -> mname;
                                default -> throw new IllegalStateException("未預期的自身呼叫 " + mname + mdesc);
                            };
                            count(hits, key);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, mname, "(Ljava/lang/Object;" + mdesc.substring(1), false);
                        } else if (opcode == Opcodes.INVOKESTATIC
                                && ("getRandomSquareForCorpse".equals(mname) || "createRandomDeadBody".equals(mname))) {
                            count(hits, mname);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, mname, mdesc, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    static void count(Map<String, Integer> hits, String key) {
        hits.merge(key, 1, Integer::sum);
    }

    static Object getStatic(Class<?> cls, String field) throws Exception {
        var f = cls.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    static Object call(Class<?> cls, String method) throws Exception {
        Method m = cls.getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(null);
    }

    static void expect(String what, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) {
            failed++;
        }
    }
}
