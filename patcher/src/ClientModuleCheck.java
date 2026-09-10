import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 新模組的版本漂移守門；由 SmokeCheck 呼叫，不啟動遊戲。 */
public final class ClientModuleCheck {
    private ClientModuleCheck() {}

    public static void verify(Path output, Path jarPath, String mode) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (mode.equals("client-profiler")) {
                String owner = "se/krka/kahlua/integration/LuaCaller";
                ClassNode vanilla = vanilla(jar, owner);
                ClassNode patched = patched(output, owner);
                require(countOwner(vanilla, "se/krka/kahlua/vm/KahluaThread", true) == 15,
                        "LuaCaller vanilla VM 邊界必須恰15處；新入口需重新盤點");
                require(countOwner(patched, "se/krka/kahlua/vm/KahluaThread", true) == 0,
                        "LuaCaller 原VM邊界必須全數改道");
                require(countOwner(patched, "zombie/mdc/MdcProfilerHooks", false) == 15,
                        "LuaCaller profiler helper 呼叫必須恰15處");
                for (Patcher.MethodOps op : PatchConfig.clientProfiler().getFirst().methods) {
                    MethodNode before = method(vanilla, op.name, op.desc);
                    MethodNode after = method(patched, op.name, op.desc);
                    Patcher.Site site = op.redirects.getFirst();
                    require(real(before).size() == real(after).size(), op.name + op.desc + " 指令數不变");
                    require(calls(after, "zombie/mdc/MdcProfilerHooks", site.redirectName(),
                            Patcher.redirectDesc(site.opcode(), site.owner(), site.desc())) == 1,
                            op.name + op.desc + " 同形改道恰1");
                }
                // array overload 的 protected 包裝仍只委派給同一個 pcall，不能再量外層。
                for (String name : new String[]{"protectedCallVoid", "protectedCallBoolean"}) {
                    String result = name.equals("protectedCallVoid") ? "V" : "Ljava/lang/Boolean;";
                    String desc = "(Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;[Ljava/lang/Object;)" + result;
                    require(real(method(vanilla, name, desc)).size() == real(method(patched, name, desc)).size(),
                            name + "[] 包裝沒有額外插樁");
                }
                return;
            }
            if (!mode.equals("client-core")) {
                throw new IllegalArgumentException("Unknown client module: " + mode);
            }
            String exposerName = "zombie/Lua/LuaManager$Exposer";
            MethodNode originalExpose = method(vanilla(jar, exposerName), "exposeAll", "()V");
            MethodNode exposed = method(patched(output, exposerName), "exposeAll", "()V");
            List<AbstractInsnNode> tail = real(exposed);
            require(real(exposed).size() == real(originalExpose).size() + 2, "Lua expose只新增2條指令");
            require(tail.get(tail.size() - 3) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                    && tail.get(tail.size() - 2) instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("zombie/mdc/MdcPatchRuntime")
                    && call.name.equals("register") && call.desc.equals("(Lzombie/Lua/LuaManager$Exposer;)V")
                    && tail.getLast().getOpcode() == Opcodes.RETURN, "Lua bridge在原註冊結束後才發布");

            ClassNode originalCore = vanilla(jar, "zombie/core/Core");
            ClassNode core = patched(output, "zombie/core/Core");
            String resetDesc = "(Ljava/lang/String;Ljava/lang/String;)V";
            List<AbstractInsnNode> reset = real(method(core, "ResetLua", resetDesc));
            require(reset.size() == real(method(originalCore, "ResetLua", resetDesc)).size() + 2,
                    "ResetLua只新增2條指令");
            require(reset.getFirst() instanceof VarInsnNode load && load.var == 0
                    && reset.get(1) instanceof MethodInsnNode call && call.name.equals("onLuaReset")
                    && call.owner.equals("zombie/mdc/MdcPatchRuntime"), "Lua reset先停止舊capture");
            MethodNode beforeRender = method(originalCore, "EndFrameUI", "()V");
            MethodNode afterRender = method(core, "EndFrameUI", "()V");
            require(calls(beforeRender, "zombie/core/logger/ExceptionLogger", "render", "()V") == 1,
                    "啟動畫面掛在原版exception popup的唯一呼叫點");
            require(calls(afterRender, "zombie/core/logger/ExceptionLogger", "render", "()V") == 0
                    && calls(afterRender, "zombie/mdc/MdcPatchRuntime", "renderEndFrameUI", "()V") == 1
                    && real(afterRender).size() == real(beforeRender).size(), "UI顯示同形改道且指令數不变");
            MethodNode renderHelper = method(patched(output, "zombie/mdc/MdcPatchRuntime"), "renderEndFrameUI", "()V");
            require(calls(renderHelper, "zombie/core/logger/ExceptionLogger", "render", "()V") == 1,
                    "啟動畫面保留原版exception popup");
        }
    }

    private static int countOwner(ClassNode node, String owner, boolean pcallOnly) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                        && (!pcallOnly || call.name.equals("pcall") || call.name.equals("pcallvoid") || call.name.equals("pcallBoolean"))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int calls(MethodNode node, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : node.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static List<AbstractInsnNode> real(MethodNode node) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (AbstractInsnNode instruction : node.instructions) if (instruction.getOpcode() >= 0) instructions.add(instruction);
        return instructions;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing method " + node.name + "." + name + desc));
    }

    private static ClassNode vanilla(JarFile jar, String owner) throws Exception {
        var entry = jar.getJarEntry(owner + ".class");
        if (entry == null) throw new IllegalStateException("Missing vanilla class " + owner);
        try (var in = jar.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static ClassNode patched(Path output, String owner) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(output.resolve(owner + ".class"))).accept(node, 0);
        return node;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException(label);
        System.out.println("PASS " + label);
    }
}
