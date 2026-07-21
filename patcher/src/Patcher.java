import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;

/**
 * PZ loose-class patcher（B42 伺服器）。
 *
 * 原則：只做「堆疊形狀不變」的 bytecode 手術，原 class 的 StackMapFrames 與
 * max stack/locals 原樣保留（ClassWriter flags=0），把驗證風險壓到最低：
 *   1. redirect —— 把指定呼叫指令改道為 INVOKESTATIC zombie/mdc/LogFilter 同形靜態方法
 *      （receiver 變第一參數；淨堆疊效果與指令長度皆不變）。過濾邏輯在 LogFilter.java
 *      用 javac 對遊戲 jar 正常編譯，不做方法合成。
 *   2. constChange —— 替換方法內 LDC/BIPUSH/SIPUSH 數值常數（同長度指令、堆疊不變）。
 *
 * 守門：每個 MethodOps 帶 expectedHits，逐方法核對實際命中數——任何 build 漂移
 * （呼叫點增減、descriptor 變更、方法改名）都讓建置失敗而非默默出錯。
 * 輸出 manifest：每個 patch class 的「原版 jar class SHA256＋手術後 SHA256」——
 * install.sh 據此做同源驗證與 payload 完整性 preflight。
 */
public final class Patcher {

    record Site(int opcode, String owner, String name, String desc, String redirectName) {}
    record ConstChange(Object from, Object to) {}

    static final class MethodOps {
        final String name, desc;
        final List<Site> redirects = new ArrayList<>();
        final List<ConstChange> consts = new ArrayList<>();
        int expectedHits = 0;
        int actualHits = 0;
        MethodOps(String name, String desc) { this.name = name; this.desc = desc; }
    }

    static final class ClassPatch {
        final String internalName;
        final List<MethodOps> methods = new ArrayList<>();
        ClassPatch(String internalName) { this.internalName = internalName; }
        MethodOps method(String name, String desc) {
            MethodOps m = new MethodOps(name, desc);
            methods.add(m);
            return m;
        }
    }

    static final String FILTER_OWNER = "zombie/mdc/LogFilter";

    static final class PatchingVisitor extends ClassVisitor {
        final ClassPatch patch;
        PatchingVisitor(ClassVisitor cv, ClassPatch patch) {
            super(Opcodes.ASM9, cv);
            this.patch = patch;
        }
        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            for (MethodOps ops : patch.methods) {
                if (ops.name.equals(name) && ops.desc.equals(desc)) {
                    return new MethodSurgeon(mv, ops);
                }
            }
            return mv;
        }
    }

    static final class MethodSurgeon extends MethodVisitor {
        final MethodOps ops;
        MethodSurgeon(MethodVisitor mv, MethodOps ops) {
            super(Opcodes.ASM9, mv);
            this.ops = ops;
        }
        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            for (Site s : ops.redirects) {
                if (s.opcode() == opcode && s.owner().equals(owner) && s.name().equals(name) && s.desc().equals(desc)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER_OWNER, s.redirectName(),
                            redirectDesc(opcode, owner, desc), false);
                    ops.actualHits++;
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
        @Override
        public void visitLdcInsn(Object value) {
            for (ConstChange c : ops.consts) {
                if (c.from().equals(value)) {
                    super.visitLdcInsn(c.to());
                    ops.actualHits++;
                    return;
                }
            }
            super.visitLdcInsn(value);
        }
        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                for (ConstChange c : ops.consts) {
                    if (c.from() instanceof Integer i && i == operand && c.to() instanceof Integer t) {
                        super.visitIntInsn(opcode, t);
                        ops.actualHits++;
                        return;
                    }
                }
            }
            super.visitIntInsn(opcode, operand);
        }
    }

    /** redirect 目標簽名：receiver（非 static 時）前置，回傳型別沿用原 desc（log 類皆 V）。 */
    static String redirectDesc(int opcode, String owner, String desc) {
        if (opcode == Opcodes.INVOKESTATIC) {
            return desc;
        }
        return "(L" + owner + ";" + desc.substring(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("用法: Patcher <projectzomboid.jar> <輸出目錄> <manifest 輸出路徑>");
            System.exit(2);
        }
        Path jarPath = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Path manifestPath = Path.of(args[2]);

        List<ClassPatch> patches = PatchConfig.all();
        List<String> manifest = new ArrayList<>();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (ClassPatch p : patches) {
                String entryName = p.internalName + ".class";
                var entry = jar.getEntry(entryName);
                if (entry == null) {
                    throw new IllegalStateException("jar 內找不到 " + entryName);
                }
                byte[] original = jar.getInputStream(entry).readAllBytes();
                String origSha = HexFormat.of().formatHex(sha.digest(original));

                ClassReader cr = new ClassReader(original);
                ClassWriter cw = new ClassWriter(0);   // frames/maxs 原樣保留
                cr.accept(new PatchingVisitor(cw, p), 0);

                int total = 0;
                for (MethodOps m : p.methods) {
                    if (m.actualHits != m.expectedHits) {
                        throw new IllegalStateException(p.internalName + "." + m.name + m.desc
                                + " 命中數 " + m.actualHits + " != 預期 " + m.expectedHits
                                + "（build 已漂移？重新分析後更新 PatchConfig）");
                    }
                    total += m.actualHits;
                }

                byte[] patched = cw.toByteArray();
                Path out = outDir.resolve(entryName);
                Files.createDirectories(out.getParent());
                Files.write(out, patched);
                manifest.add(entryName + "\t" + origSha + "\t"
                        + HexFormat.of().formatHex(sha.digest(patched)) + "\t" + total + "hits");
                System.out.printf("patched %-70s %d hits%n", entryName, total);
            }
        }
        Files.createDirectories(manifestPath.getParent());
        // 強制 LF：manifest 會被伺服器端 bash 逐行解析，CRLF 會污染欄位
        Files.writeString(manifestPath, String.join("\n", manifest) + "\n");
        System.out.println("manifest -> " + manifestPath + "（" + manifest.size() + " classes）");
    }

    private Patcher() {}
}
