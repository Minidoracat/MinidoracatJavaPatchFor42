import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 守衛語意驗證（codex 要求的驗證門檻）：
 *  1. 行為 smoke＋負對照：原版 new hit.Zombie().process() 必拋 NPE（證明裸跑必炸）；
 *     修補版必須安靜返回（證明 guard 真的在 super 之前生效）。Fall.process(null) 同理。
 *  2. ASM 結構斷言：guard 序列位於方法最前、invokespecial Character.process 恰一次且在 guard 後、
 *     原 9 個 IsoZombie setter 未增減。
 */
public final class SmokeCheck {

    public static void main(String[] args) throws Exception {
        Path distJava = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        int failed = 0;

        // ---- 1. 行為 smoke ----
        try (URLClassLoader patched = new URLClassLoader(
                     new URL[]{ distJava.toUri().toURL(), jar.toUri().toURL() }, null);
             URLClassLoader original = new URLClassLoader(new URL[]{ jar.toUri().toURL() }, null)) {

            failed += expect("原版 Zombie.process() 必拋 NPE（負對照）",
                    invokeProcess(original, "zombie.network.fields.hit.Zombie", false), true);
            failed += expect("修補版 Zombie.process() 安靜返回",
                    invokeProcess(patched, "zombie.network.fields.hit.Zombie", false), false);
            failed += expect("原版 Fall.process(null) 必拋 NPE（負對照）",
                    invokeProcess(original, "zombie.network.fields.hit.Fall", true), true);
            failed += expect("修補版 Fall.process(null) 安靜返回",
                    invokeProcess(patched, "zombie.network.fields.hit.Fall", true), false);
        }

        // ---- 2. 結構斷言 ----
        MethodNode zp = method(distJava, "zombie/network/fields/hit/Zombie", "process", "()V");
        AbstractInsnNode[] zh = firstReal(zp, 4);
        boolean zGuard = zh[0] instanceof VarInsnNode v0 && v0.getOpcode() == Opcodes.ALOAD && v0.var == 0
                && zh[1] instanceof MethodInsnNode m1 && m1.name.equals("getZombie")
                && zh[2] instanceof JumpInsnNode j2 && j2.getOpcode() == Opcodes.IFNONNULL
                && zh[3].getOpcode() == Opcodes.RETURN;
        failed += check("Zombie.process guard 序列在方法最前", zGuard);
        int superIdx = -1, guardEnd = zp.instructions.indexOf(zh[3]);
        int superCount = 0, setterCount = 0;
        for (AbstractInsnNode in : zp.instructions) {
            if (in instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == Opcodes.INVOKESPECIAL
                        && mi.owner.equals("zombie/network/fields/hit/Character") && mi.name.equals("process")) {
                    superCount++;
                    superIdx = zp.instructions.indexOf(mi);
                }
                if (mi.owner.equals("zombie/characters/IsoZombie") && mi.name.startsWith("set")) {
                    setterCount++;
                }
            }
        }
        failed += check("super.process 恰一次且在 guard 之後", superCount == 1 && superIdx > guardEnd);
        failed += check("IsoZombie setter 恰 9 個（未增減）", setterCount == 9);

        MethodNode fp = method(distJava, "zombie/network/fields/hit/Fall",
                "process", "(Lzombie/characters/IsoGameCharacter;)V");
        AbstractInsnNode[] fh = firstReal(fp, 3);
        boolean fGuard = fh[0] instanceof VarInsnNode fv && fv.getOpcode() == Opcodes.ALOAD && fv.var == 1
                && fh[1] instanceof JumpInsnNode fj && fj.getOpcode() == Opcodes.IFNONNULL
                && fh[2].getOpcode() == Opcodes.RETURN;
        failed += check("Fall.process guard 序列在方法最前", fGuard);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("守衛語意驗證全數通過");
    }

    /** 回傳 true=拋了 NPE、false=正常返回；其他例外直接失敗拋出。 */
    static boolean invokeProcess(ClassLoader cl, String cls, boolean withArg) throws Exception {
        Class<?> c = Class.forName(cls, true, cl);
        Object o = c.getDeclaredConstructor().newInstance();
        if (withArg) {
            // Fall 座標為 0 時會在碰 character 前短路 return——設非零強制走到解參照路徑，
            // 負對照（原版必 NPE）才成立
            for (String f : new String[]{ "dropPositionX", "dropPositionY" }) {
                var fld = c.getDeclaredField(f);
                fld.setAccessible(true);
                fld.setFloat(o, 1.0f);
            }
        }
        Method m = withArg
                ? c.getMethod("process", Class.forName("zombie.characters.IsoGameCharacter", false, cl))
                : c.getMethod("process");
        try {
            if (withArg) {
                m.invoke(o, new Object[]{ null });
            } else {
                m.invoke(o);
            }
            return false;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof NullPointerException) {
                return true;
            }
            throw e;
        }
    }

    static int expect(String what, boolean gotNpe, boolean wantNpe) {
        boolean ok = gotNpe == wantNpe;
        System.out.println((ok ? "smoke OK   " : "smoke FAIL ") + what);
        return ok ? 0 : 1;
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "struct OK  " : "struct FAIL ") + what);
        return ok ? 0 : 1;
    }

    static MethodNode method(Path distJava, String cls, String name, String desc) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(distJava.resolve(cls + ".class"))).accept(cn, 0);
        return cn.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }

    /** 取前 n 條「真指令」（跳過 label/frame/line）。 */
    static AbstractInsnNode[] firstReal(MethodNode m, int n) {
        AbstractInsnNode[] out = new AbstractInsnNode[n];
        int i = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null && i < n; in = in.getNext()) {
            if (in.getOpcode() >= 0) {
                out[i++] = in;
            }
        }
        return out;
    }

    private SmokeCheck() {}
}
