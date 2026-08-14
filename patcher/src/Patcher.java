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
 *   1. redirect —— 把指定呼叫指令改道為指定 helper 的同形 INVOKESTATIC 方法
 *      （receiver 變第一參數；淨堆疊效果與指令長度皆不變）。helper 用 javac
 *      對遊戲 jar 正常編譯，不做方法合成。
 *   2. constChange —— 替換方法內 LDC/BIPUSH/SIPUSH 數值常數（同長度指令、堆疊不變）。
 *
 * 守門：每個 MethodOps 帶 expectedHits，逐方法核對實際命中數——任何 build 漂移
 * （呼叫點增減、descriptor 變更、方法改名）都讓建置失敗而非默默出錯。
 * 輸出 manifest：每個 patch class 的「原版 jar class SHA256＋手術後 SHA256」——
 * install.sh 據此做同源驗證與 payload 完整性 preflight。
 */
public final class Patcher {

    record Site(int opcode, String owner, String name, String desc, String redirectOwner, String redirectName) {
        Site(int opcode, String owner, String name, String desc, String redirectName) {
            this(opcode, owner, name, desc, FILTER_OWNER, redirectName);
        }
    }
    record ConstChange(Object from, Object to) {}

    /**
     * 分頁筆數 clamp：鎖定「INVOKESTATIC site → istore C → iload O → iload C → iadd → istore O」
     * 全序（C＝筆數 slot、O＝offset slot，slot 一致性逐步核對），在 istore O 之後插入
     * 「iload C; INVOKESTATIC helper(I)I; istore C」。線性插入、無新分支、堆疊峰值 1、
     * frames 不需增補；序列任何一步不符即整段放棄（命中數守門會讓建置失敗）。
     * helper 內部以 v3 專用 buffer 的 remaining() 計算上限（保險絲，隔離後不應觸發）。
     */
    record CountClamp(String siteOwner, String siteName, String siteDesc,
                      String helperOwner, String helperName) {}

    /**
     * 欄位讀取同形替換：在每個 {@code opcode}（GETFIELD／GETSTATIC）owner.name:desc 之後插入
     * 「INVOKESTATIC helper(desc)desc」——吃掉共享欄位值、回傳替身。堆疊 1→1、線性、
     * 無新分支；只作用於所屬 MethodOps 的方法，命中數計入該方法守門。
     *
     * <p>GETFIELD 版原用於 popman v3 buffer 隔離（42.20.2 官方收編已退役）；
     * GETSTATIC 版用於 W7 朝向暫存執行緒隔離（見 PatchConfig）。指令長度兩者皆 3 bytes，
     * 且插入的 INVOKESTATIC 同樣 3 bytes，故 offset 位移只來自插入本身，frames 不受影響。
     */
    record FieldGetSwap(int opcode, String owner, String name, String desc,
                        String helperOwner, String helperName) {
        // 契約封死：只有兩種 GET 的堆疊效果是 1→1（推一個值），插入 helper 才是同形替換。
        // 誤傳 PUT 會讓 visitor 對「已被消費的值」插入 helper——assignment-expression 那類
        // 留有 duplicated value 的 bytecode 甚至不保證 verifier 會攔住（codex 審查發現）。
        FieldGetSwap {
            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.GETSTATIC) {
                throw new IllegalArgumentException(
                        "FieldGetSwap 只支援 GETFIELD／GETSTATIC，收到 opcode=" + opcode);
            }
        }
        String helperDesc() {
            return "(" + desc + ")" + desc;
        }
    }

    /**
     * 方法頭部 null 守衛：aload &lt;slot&gt;［; invokevirtual owner.name desc］; ifnonnull L; return; L:[F_SAME]。
     * 插在原第一條指令前（含 super 呼叫之前）；堆疊峰值 1、locals 不變、原 frames 照舊——
     * 新 branch target 只需補一個 F_SAME（與初始 frame 等價，後續壓縮 frame 的 delta 鏈不受影響）。
     */
    record HeadGuard(int varSlot, String callOwner, String callName, String callDesc) {}

    /**
     * 方法頭線性呼叫（v2.0 起，貼圖洩漏根治用）：visitCode 後插
     * `aload_0; invokestatic helperOwner.helperName helperDesc`（desc 收 receiver 型別、回 void）。
     * 純線性、無分支、無新 branch target——frames 完全不需增補；堆疊峰值 1，
     * visitMaxs 以 max(原值,1) 保底。helper 對遊戲 jar 正常編譯，內部自帶冪等與例外紀律。
     */
    record HeadCall(String helperOwner, String helperName, String helperDesc) {}

    static final class MethodOps {
        final String name, desc;
        final List<Site> redirects = new ArrayList<>();
        final List<ConstChange> consts = new ArrayList<>();
        HeadGuard headGuard = null;
        HeadCall headCall = null;
        CountClamp countClamp = null;
        FieldGetSwap fieldGetSwap = null;
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
        /** CountClamp 狀態機：0=待命 1=看到 site 呼叫 2=istore C 3=iload O 4=iload C 5=iadd。 */
        int clampState = 0;
        int clampCountSlot = -1;
        int clampOffsetSlot = -1;
        MethodSurgeon(MethodVisitor mv, MethodOps ops) {
            super(Opcodes.ASM9, mv);
            this.ops = ops;
        }
        @Override
        public void visitCode() {
            super.visitCode();
            HeadGuard g = ops.headGuard;
            if (g != null) {
                super.visitVarInsn(Opcodes.ALOAD, g.varSlot());
                if (g.callOwner() != null) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, g.callOwner(), g.callName(), g.callDesc(), false);
                }
                org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
                super.visitJumpInsn(Opcodes.IFNONNULL, cont);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(cont);
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                ops.actualHits++;
            }
            HeadCall hc = ops.headCall;
            if (hc != null) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, hc.helperOwner(), hc.helperName(),
                        hc.helperDesc(), false);
                ops.actualHits++;
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // 頭部插入的堆疊峰值為 1；原方法 maxStack 幾乎必 ≥1，取 max 保底
            super.visitMaxs(ops.headCall != null || ops.headGuard != null
                    ? Math.max(maxStack, 1) : maxStack, maxLocals);
        }
        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            for (Site s : ops.redirects) {
                if (s.opcode() == opcode && s.owner().equals(owner) && s.name().equals(name) && s.desc().equals(desc)) {
                    clampState = 0;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, s.redirectOwner(), s.redirectName(),
                            redirectDesc(opcode, owner, desc), false);
                    ops.actualHits++;
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            CountClamp c = ops.countClamp;
            clampState = c != null && opcode == Opcodes.INVOKESTATIC && c.siteOwner().equals(owner)
                    && c.siteName().equals(name) && c.siteDesc().equals(desc) ? 1 : 0;
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
            CountClamp c = ops.countClamp;
            if (c == null) {
                return;
            }
            switch (clampState) {
                case 1 -> {
                    if (opcode == Opcodes.ISTORE) { clampCountSlot = var; clampState = 2; } else { clampState = 0; }
                }
                case 2 -> {
                    if (opcode == Opcodes.ILOAD && var != clampCountSlot) {
                        clampOffsetSlot = var; clampState = 3;
                    } else { clampState = 0; }
                }
                case 3 -> {
                    clampState = opcode == Opcodes.ILOAD && var == clampCountSlot ? 4 : 0;
                }
                case 5 -> {
                    if (opcode == Opcodes.ISTORE && var == clampOffsetSlot) {
                        super.visitVarInsn(Opcodes.ILOAD, clampCountSlot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, c.helperOwner(), c.helperName(), "(I)I", false);
                        super.visitVarInsn(Opcodes.ISTORE, clampCountSlot);
                        ops.actualHits++;
                    }
                    clampState = 0;
                }
                default -> clampState = 0;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            super.visitInsn(opcode);
            clampState = clampState == 4 && opcode == Opcodes.IADD ? 5 : 0;
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            super.visitJumpInsn(opcode, label);
            clampState = 0;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            super.visitFieldInsn(opcode, owner, name, desc);
            FieldGetSwap sw = ops.fieldGetSwap;
            if (sw != null && opcode == sw.opcode()
                    && sw.owner().equals(owner) && sw.name().equals(name) && sw.desc().equals(desc)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, sw.helperOwner(), sw.helperName(), sw.helperDesc(), false);
                ops.actualHits++;
            }
            clampState = 0;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            super.visitTypeInsn(opcode, type);
            clampState = 0;
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            super.visitIincInsn(varIndex, increment);
            clampState = 0;
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, org.objectweb.asm.Handle handle, Object... args) {
            super.visitInvokeDynamicInsn(name, desc, handle, args);
            clampState = 0;
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) {
            super.visitTableSwitchInsn(min, max, dflt, labels);
            clampState = 0;
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) {
            super.visitLookupSwitchInsn(dflt, keys, labels);
            clampState = 0;
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
            clampState = 0;
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            super.visitFrame(type, numLocal, local, numStack, stack);
            // StackMapFrame＝控制流合流點；pattern 跨越合流點即語意不明，放棄（codex 對抗審查發現）
            clampState = 0;
        }
        @Override
        public void visitLdcInsn(Object value) {
            clampState = 0;
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
            clampState = 0;
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

    /** redirect 目標簽名：receiver（非 static 時）前置，回傳型別沿用原 desc。 */
    static String redirectDesc(int opcode, String owner, String desc) {
        if (opcode == Opcodes.INVOKESTATIC) {
            return desc;
        }
        return "(L" + owner + ";" + desc.substring(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && !(args.length == 4 && args[3].equals("client"))) {
            System.err.println("用法: Patcher <projectzomboid.jar> <輸出目錄> <manifest 輸出路徑> [client]");
            System.exit(2);
        }
        Path jarPath = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Path manifestPath = Path.of(args[2]);

        List<ClassPatch> patches = args.length == 4 ? PatchConfig.client() : PatchConfig.all();
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
