package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.logger.ZLogger;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.network.ServerOptions;
import zombie.network.chat.ChatServer;

/**
 * W88 PVP=false 時玩家死亡不歸給玩家（PveDeathLog）行為驗證。
 *
 * <p>測試 JVM 跑不了整個角色死亡（Lua 事件、log 檔、聊天伺服器、血跡），所以把 dist 內手術後的
 * {@code IsoGameCharacter.DoDeath(HandWeapon, IsoGameCharacter, boolean)} 與 jar 內原版各自原樣搬進一個 static 方法
 * （slot 0 的 this 變成第一個參數，locals 與 frames 不變），只把七個外部效果呼叫 1:1 換成本類別的替身並記錄：
 * {@code OnDeath}、{@code PVPLogTool.logKill}、{@code LoggerManager.getLogger}＋{@code ZLogger.write}、
 * {@code ChatServer.getInstance}＋{@code sendMessageToServerChat}、{@code doDeathSplatterAndSounds}。條件判斷、
 * {@code getAttackedBy}（原版）／{@code PveDeathLog.getAttackedBy}（手術後）、{@code isAnimal}、{@code GameServer.server}、
 * {@code ServerOptions} 都是真的。對死者（玩家、動物、殭屍）× 攻擊者（玩家、動物、殭屍、null）× PVP（false／true）逐格比：
 * 只有「PVP=false、死者是玩家、攻擊者是 IsoPlayer」且開關 on 時改走 non pvp 分支，其餘與原版逐筆相同。
 * argv：jar、dist、{@code on}／{@code off}，自驗旗標。
 */
public final class PveDeathLogTest {
    static final String GC = "zombie/characters/IsoGameCharacter";
    static final String DESC = "(Lzombie/inventory/types/HandWeapon;L" + GC + ";Z)V";
    static final String SELF = "zombie/mdc/PveDeathLogTest";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final PrintStream OUT = System.out;

    static final List<String> events = new ArrayList<>();
    private static int failed;

    // ---- 替身（同形：receiver → 第一個參數）----
    public static void onDeath(IsoGameCharacter c) {
        events.add("OnDeath");
    }

    public static void logKill(IsoPlayer wielder, IsoPlayer target) {
        events.add("logKill " + wielder.username + " -> " + target.username);
    }

    public static ZLogger getLogger(String name) {
        events.add("getLogger " + name);
        return null;
    }

    public static void write(ZLogger logger, String text) {
        events.add("write " + text);
    }

    public static ChatServer chatServer() {
        return null;
    }

    public static void chat(ChatServer server, String text) {
        events.add("chat " + text);
    }

    public static void splatter(IsoGameCharacter c, HandWeapon weapon, IsoGameCharacter wielder, boolean gory) {
        events.add("splatter");
    }

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();   // DebugLog 第一次寫行時經 GameClient → ServerOptions 呼叫 Rand
        String jar = args[0];
        Path dist = Path.of(args[1]);
        String mode = args[2];
        boolean on = "on".equals(mode);
        expect("自驗：argv=" + mode + " 與實際旗標相符（-Dmdc.pveDeathLog=" + System.getProperty("mdc.pveDeathLog") + "）",
                PveDeathLog.ENABLED == on);
        expect("parseEnabled：null/1/on/亂寫→on、0/off/ OFF →off",
                PveDeathLog.parseEnabled(null) && PveDeathLog.parseEnabled("1") && PveDeathLog.parseEnabled("on")
                        && PveDeathLog.parseEnabled("x") && !PveDeathLog.parseEnabled("0")
                        && !PveDeathLog.parseEnabled("off") && !PveDeathLog.parseEnabled(" OFF "));

        byte[] vanillaGc;
        try (ZipFile zf = new ZipFile(jar)) {
            vanillaGc = zf.getInputStream(zf.getEntry(GC + ".class")).readAllBytes();
        }
        int[] vanillaCalls = new int[2];
        int[] patchedCalls = new int[2];
        Method vanilla = transplant(vanillaGc, "zombie/mdc/W88VanillaDoDeath", vanillaCalls);
        Method patched = transplant(Files.readAllBytes(dist.resolve(GC + ".class")), "zombie/mdc/W88PatchedDoDeath", patchedCalls);
        expect("搬移：兩份各換 7 個外部效果呼叫；原版 2 個 invokevirtual getAttackedBy、手術後 2 個 PveDeathLog.getAttackedBy、0 個原呼叫",
                vanillaCalls[0] == 7 && patchedCalls[0] == 7 && vanillaCalls[1] == 2 && patchedCalls[1] == 2
                        && countCalls(patched, Opcodes.INVOKEVIRTUAL, GC, "getAttackedBy") == 0
                        && countCalls(vanilla, Opcodes.INVOKESTATIC, "zombie/mdc/PveDeathLog", "getAttackedBy") == 0);

        GameServer.server = true;
        ServerOptions.instance.announceDeath.setValue(true);
        ServerOptions.instance.dropOffWhiteListAfterDeath.setValue(false);
        ByteArrayOutputStream log = captureLog();

        String[] victims = {"player", "animal", "zombie"};
        String[] attackers = {"player", "animal", "zombie", "null"};
        int drops = 0;
        for (boolean pvp : new boolean[]{false, true}) {
            ServerOptions.instance.pvp.setValue(pvp);
            for (String v : victims) {
                for (String a : attackers) {
                    String label = "PVP=" + pvp + " 死者=" + v + " 攻擊者=" + a;
                    IsoGameCharacter attacker = character(a, "killer");
                    IsoGameCharacter victim = character(v, "victim");
                    victim.setAttackedBy(attacker);
                    List<String> want = run(vanilla, victim);
                    victim.setAttackedBy(attacker);
                    long before = PveDeathLog.droppedForTest();
                    List<String> got = run(patched, victim);
                    boolean drop = on && !pvp && v.equals("player") && attacker instanceof IsoPlayer;
                    if (drop) {
                        drops++;
                        want = List.of("OnDeath", "getLogger user", "write user victim died at (0,0,0) (non pvp)",
                                "chat victim is dead.", "splatter");
                    }
                    expect(label + (drop ? "：改走 non pvp（user.txt died、聊天 is dead.、不呼叫 logKill）" : "：與原版逐筆相同")
                            + " got=" + got, got.equals(want));
                    expect(label + "：attackedBy 欄位不動、計數只在改走時 +1",
                            victim.getAttackedBy() == attacker && PveDeathLog.droppedForTest() == before + (drop ? 1 : 0));
                }
            }
        }
        // 原版前提（紅燈）：PVP=false、玩家被記成玩家攻擊時走 logKill
        ServerOptions.instance.pvp.setValue(false);
        IsoGameCharacter victim = character("player", "victim");
        victim.setAttackedBy(character("player", "killer"));
        expect("原版：PVP=false 照樣 logKill killer -> victim、不寫 user.txt、不公告 is dead.",
                run(vanilla, victim).equals(List.of("OnDeath", "logKill killer -> victim", "splatter")));

        String text = log.toString(StandardCharsets.UTF_8);
        expect("首次生效行恰一次、帶 mode", count(text, "[PveDeathLog] 首次生效") == 1
                && text.contains("mode=" + (on ? "on" : "off")));
        expect((on ? "on：改走" : "off：零") + " non pvp 的 log 行（" + drops + " 行）、帶死者與攻擊者",
                count(text, "[PveDeathLog] PVP=false: victim died with attackedBy=killer, logged as non pvp") == drops
                        && drops == (on ? 2 : 0));
        expect("anomalies=0", PveDeathLog.anomaliesForTest() == 0);

        if (failed > 0) {
            OUT.println("pve-death-log FAIL " + failed);
            System.exit(1);
        }
        OUT.println("pve-death-log OK mode=" + mode);
    }

    private static List<String> run(Method doDeath, IsoGameCharacter self) throws Exception {
        events.clear();
        doDeath.invoke(null, self, null, null, true);
        return new ArrayList<>(events);
    }

    private static IsoGameCharacter character(String kind, String name) throws Exception {
        switch (kind) {
            case "player": {
                IsoPlayer p = alloc(IsoPlayer.class);
                p.username = name;
                return p;
            }
            case "animal": {
                IsoAnimal p = alloc(IsoAnimal.class);
                p.username = name;
                Field animal = IsoGameCharacter.class.getDeclaredField("animal");
                animal.setAccessible(true);
                animal.setBoolean(p, true);
                return p;
            }
            case "zombie":
                return alloc(IsoZombie.class);
            default:
                return null;
        }
    }

    /**
     * 把 DoDeath 原樣搬成 {@code public static run(IsoGameCharacter, HandWeapon, IsoGameCharacter, boolean)}，換掉外部效果呼叫。
     * calls[0]＝換掉的外部效果呼叫數，calls[1]＝getAttackedBy 呼叫數（原版 invokevirtual 或手術後 PveDeathLog）。
     */
    static Method transplant(byte[] classBytes, String name, int[] calls) throws Exception {
        ClassNode src = new ClassNode();
        new ClassReader(classBytes).accept(src, 0);
        MethodNode m = src.methods.stream().filter(x -> x.name.equals("DoDeath") && x.desc.equals(DESC)).findFirst()
                .orElseThrow();
        m.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        m.name = "run";
        m.desc = "(L" + GC + ";" + DESC.substring(1);
        m.localVariables = null;
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode mi)) {
                continue;
            }
            String to = null;
            String toDesc = null;
            if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals(GC) && mi.name.equals("OnDeath")) {
                to = "onDeath";
                toDesc = "(L" + GC + ";)V";
            } else if (mi.owner.equals("zombie/network/PVPLogTool") && mi.name.equals("logKill")) {
                to = "logKill";
                toDesc = mi.desc;
            } else if (mi.owner.equals("zombie/core/logger/LoggerManager") && mi.name.equals("getLogger")) {
                to = "getLogger";
                toDesc = mi.desc;
            } else if (mi.owner.equals("zombie/core/logger/ZLogger") && mi.name.equals("write") && mi.desc.equals("(Ljava/lang/String;)V")) {
                to = "write";
                toDesc = "(Lzombie/core/logger/ZLogger;Ljava/lang/String;)V";
            } else if (mi.owner.equals("zombie/network/chat/ChatServer") && mi.name.equals("getInstance")) {
                to = "chatServer";
                toDesc = mi.desc;
            } else if (mi.owner.equals("zombie/network/chat/ChatServer") && mi.name.equals("sendMessageToServerChat")
                    && mi.desc.equals("(Ljava/lang/String;)V")) {
                to = "chat";
                toDesc = "(Lzombie/network/chat/ChatServer;Ljava/lang/String;)V";
            } else if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals(GC) && mi.name.equals("doDeathSplatterAndSounds")) {
                to = "splatter";
                toDesc = "(L" + GC + ";" + mi.desc.substring(1);
            } else if (mi.name.equals("getAttackedBy")) {
                calls[1]++;
            }
            if (to != null) {
                calls[0]++;
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, to, toDesc, false));
            }
        }
        ClassNode out = new ClassNode();
        out.version = src.version;
        out.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        out.name = name;
        out.superName = "java/lang/Object";
        out.methods.add(m);
        ClassWriter cw = new ClassWriter(0);
        out.accept(cw);
        byte[] bytes = cw.toByteArray();
        Class<?> cls = new ClassLoader(PveDeathLogTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
        harnessNodes.put(cls, m);
        return cls.getMethod("run", IsoGameCharacter.class, HandWeapon.class, IsoGameCharacter.class, boolean.class);
    }

    private static final Map<Class<?>, MethodNode> harnessNodes = new java.util.HashMap<>();

    private static int countCalls(Method harness, int opcode, String owner, String name) {
        int n = 0;
        for (AbstractInsnNode in : harnessNodes.get(harness.getDeclaringClass()).instructions) {
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == opcode && mi.owner.equals(owner) && mi.name.equals(name)) {
                n++;
            }
        }
        return n;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        OUT.println((ok ? "pdl pass  " : "pdl FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
