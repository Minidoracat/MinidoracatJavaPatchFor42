import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * W31 釣魚資料廣播單次編碼（{@link zombie.mdc.FishingDataBroadcast}）對原版的逐項等價回歸。
 *
 * <p>受測的是 <b>dist\java 內的正式 helper</b>，不是驗證期的候選副本；原版
 * {@code GameServer.transmitFishingData} 在同一個 loader 內跑同一批 fixture 當基準。
 * 遊戲側只替換<b>唯一的原生葉子</b> {@code RakNetPeerInterface.sendNative}（擷取位元組＋注入
 * 失敗）；{@code UdpConnection} 的 buffer／鎖、{@code PacketType} 的 header 與 send、
 * {@code RakNetPeerInterface.Send}（含 sendLock 與 sendBuf 複製）、以及
 * {@code GameClient}／{@code FishSchoolManager} 的真解析都是實碼。
 *
 * <p>另外三處測試專用改寫：{@code UdpEngine} 補一個無參建構子（免 RakNet 原生 startup）、
 * {@code ExceptionLogger.logException} 前置一個計數呼叫（原 body 照跑，是觀測不是假造）、
 * helper 的快照 {@code new byte[]} 導到 {@link Probe#allocateSnapshot}（注入一次性 OOM，
 * 同時充當「共用路徑到底有沒有走」的計數器——kill switch 的證據）。
 *
 * <p>on／off 由 build.ps1 以獨立 JVM 分別啟動（{@code ENABLED} 是 static final）；測試自驗
 * argv 與 property 相符，property 名稱打錯不得假綠。
 */
public final class FishingDataBroadcastTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[2].equals("on") || args[2].equals("off"))) {
            System.out.println("fishing-data-broadcast FAIL 用法：FishingDataBroadcastTest <distJava> <gameJar> <on|off>");
            System.exit(2);
            return;
        }
        Path distJava = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        String mode = args[2];
        boolean enabled = mode.equals("on");

        String flag = System.getProperty("mdc.fishingDataBroadcast");
        boolean killed = "0".equals(flag) || "off".equals(flag);
        if (killed == enabled) {
            throw new AssertionError("自驗：mode=" + mode + " 必須搭配 -Dmdc.fishingDataBroadcast"
                    + (enabled ? " 不為 0/off" : "=0/off") + "（實際 " + flag + "）");
        }

        // ExceptionLogger 的真 body 會經 ZomboidFileSystem 落地到 user.home\Zomboid；
        // 導進沙盒目錄，測試不得把垃圾寫進本機真實存檔目錄（SmokeCheck 同一條紀律）。
        Path sandbox = Files.createTempDirectory("mdc-fish-broadcast-test-");
        System.setProperty("user.home", sandbox.toString());

        URL[] urls = {
            FishingDataBroadcastTest.class.getProtectionDomain().getCodeSource().getLocation(),
            distJava.toUri().toURL(),
            jar.toUri().toURL(),
        };
        try (GameLoader loader = new GameLoader(urls, FishingDataBroadcastTest.class.getClassLoader())) {
            Method run = Class.forName("FishingDataBroadcastTest$Body", true, loader)
                    .getMethod("run", String.class);
            try {
                run.invoke(null, mode);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(cause);
            }
        }
        deleteTree(sandbox);
        System.out.println("fishing-data-broadcast OK  mode=" + mode);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ---------------------------------------------------------------- 原生葉子替身與注入點

    /**
     * 唯一被替換的原生葉子（{@code sendNative}）＋例外計數＋快照配置鉤子。
     * 由 loader 載入，與遊戲類別、helper 同一份，觀測到的都是同一組物件。
     */
    public static final class Probe {

        public static final java.util.List<Sent> sent = new java.util.ArrayList<>();
        public static int exceptionLogged;
        public static int sendCalls;
        /** 快照配置<b>嘗試</b>次數；共用路徑有沒有真的走過，就看它。 */
        public static int snapshotAttempts;
        /** 0-based：第幾次 sendNative 要失敗；-1＝不失敗。 */
        public static int failAtCall = -1;
        /** 1＝RuntimeException（Send 自己吞掉），2＝Error（穿透回 transmit 的 Throwable 邊界）。 */
        public static int failKind;
        public static boolean snapshotFailure;

        public static void reset() {
            sent.clear();
            exceptionLogged = 0;
            sendCalls = 0;
            snapshotAttempts = 0;
            failAtCall = -1;
            failKind = 0;
            snapshotFailure = false;
        }

        public static int onSendNative(java.nio.ByteBuffer data, int length, int priority, int reliability,
                byte channel, long guid, boolean broadcast) {
            sendCalls++;
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            sent.add(new Sent(guid, priority, reliability, channel, broadcast, copy));
            int ordinal = sendCalls - 1;
            if (ordinal == failAtCall) {
                if (failKind == 1) {
                    throw new RuntimeException("injected sendNative RuntimeException at call " + ordinal);
                }
                if (failKind == 2) {
                    throw new Error("injected sendNative Error at call " + ordinal);
                }
            }
            return length;
        }

        public static byte[] allocateSnapshot(int size) {
            snapshotAttempts++;
            if (snapshotFailure) {
                snapshotFailure = false;
                throw new OutOfMemoryError("injected optional snapshot failure");
            }
            return new byte[size];
        }

        public static void onExceptionLogged() {
            exceptionLogged++;
        }

        public record Sent(long guid, int priority, int reliability, byte channel,
                boolean broadcast, byte[] data) {

            public String describe() {
                return "SEND guid=" + guid + " prio=" + priority + " rel=" + reliability
                        + " chan=" + channel + " bcast=" + broadcast + " len=" + data.length
                        + " sha256=" + sha256(data);
            }
        }

        static String sha256(byte[] data) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
                return java.util.HexFormat.of().formatHex(digest);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Probe() {}
    }

    // ---------------------------------------------------------------- 情境本體（由 loader 載入）

    /**
     * 受測本體。它與遊戲類別、helper 由同一個 loader 載入，所以可以直接靜態引用 zombie.*；
     * 外層 driver 則完全不碰 zombie 型別，避免整條鏈被 app loader 先載走而繞過改寫。
     */
    public static final class Body {

        private static final long[] NOISE_KEYS = {1001L, 1002L, 1003L};
        private static final long CHUM_A = 4001L;
        private static final long CHUM_B = 4002L;

        public static void run(String mode) throws Exception {
            boolean enabled = mode.equals("on");

            java.lang.reflect.Field random = zombie.core.random.RandAbstract.class.getDeclaredField("rand");
            random.setAccessible(true);
            random.set(zombie.core.random.RandStandard.INSTANCE, new java.util.Random(1));
            zombie.network.GameServer.server = true; // transmitFishingData 只在 server 端呼叫


            Spec s0 = spec("零收件人", 0);
            s0.expectedSends = 0;
            s0.expectedSnapshots = 0;
            Spec s1 = spec("單一收件人（無人可共用）", 1);
            s1.expectedSnapshots = 0;
            Spec s2 = spec("三收件人（共用主路徑）", 3);
            s2.expectedSnapshots = 1;
            Spec s3 = spec("三收件人，兩張 map 皆空", 3);
            s3.noise = new gnu.trove.map.hash.TLongIntHashMap();
            s3.chum = new gnu.trove.map.hash.TLongObjectHashMap<>();
            s3.expectedSnapshots = 1;
            Spec s4 = spec("收件人 1 的 buffer 容量不足（32 bytes）", 3);
            s4.smallWriter = 1;
            s4.expectedSends = 2;   // 收件人 1 在 endPacket 前就溢位
            s4.expectedLogged = 1;  // ——也只有它被記錄
            s4.expectedSnapshots = 1;
            Spec s5 = spec("收件人 1 的 buffer 是 LITTLE_ENDIAN", 3);
            s5.littleEndian = 1;
            s5.decodable = false;   // LE 封包的 header id 對不上，兩邊都只會是 BADHEADER
            s5.expectedSnapshots = 1;
            Spec s6 = spec("sendNative 於收件人 1 拋 RuntimeException", 3);
            s6.failAtCall = 1;
            s6.failKind = 1;
            s6.expectedLogged = 0;  // RakNetPeerInterface.Send 自己吞掉 Exception
            s6.expectedSnapshots = 1;
            Spec s7 = spec("sendNative 於收件人 1 拋 Error", 3);
            s7.failAtCall = 1;
            s7.failKind = 2;
            s7.expectedLogged = 1;  // Error 穿透 Send，由 transmit 的 Throwable 邊界接住
            s7.expectedSnapshots = 1;
            Spec s8 = spec("每個收件人的序列化都拋（ChumData 為 null）", 3);
            s8.chum = new gnu.trove.map.hash.TLongObjectHashMap<>();
            s8.chum.put(CHUM_A, new zombie.iso.FishSchoolManager.ChumData(120, 480));
            s8.chum.put(CHUM_B, null);
            s8.expectedSends = 0;
            s8.expectedLogged = 3;  // 每個收件人各一次 cancelPacket＋log，與原版相同
            s8.expectedSnapshots = 0;
            Spec s9 = spec("自訂 map 子類別，且首次走訪拋一次", 3);
            s9.noise = new OnceFailingNoise();
            s9.noise.put(NOISE_KEYS[0], 10);
            s9.expectedSends = 2;
            s9.expectedLogged = 1;
            s9.expectedSnapshots = 0;   // 非 exact map ⇒ 全程原寫入
            Spec s10 = spec("快照配置 OOM 一次（不得波及當下封包）", 3);
            s10.snapshotFailure = true;
            s10.expectedSnapshots = 1;
            Spec s11 = spec("自訂連線子類別在收件人 1 之前改動來源", 3);
            s11.mutateAt = 1;
            s11.expectedSnapshots = 1;
            Spec s12 = spec("自訂 connections 清單在收件人 1 之前改動來源", 3);
            s12.mutateAt = 1;
            s12.mutateViaList = true;
            s12.expectedSnapshots = 0;  // 非原版清單 ⇒ 整批直通原版
            Spec s13 = spec("零收件人且兩張 map 為 null", 0);
            s13.noise = null;
            s13.chum = null;
            s13.expectedSends = 0;
            s13.expectedSnapshots = 0;

            for (Spec spec : new Spec[]{s0, s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12, s13}) {
                compare(spec, enabled);
            }
        }

        // ------------------------------------------------------------ 逐情境比對

        private static void compare(Spec spec, boolean enabled) throws Exception {
            String expectedState = spec.connections == 0 || spec.expectedSends == 0 ? "" : expectedState(spec);
            Report original = runBatch(spec, false);
            Report helper = runBatch(spec, true);

            boolean same = original.summary.equals(helper.summary);
            check(spec.name + "：helper 與原版的可觀察結果一致（收件人順序／位元組／log／鎖／解析）"
                    + (same ? "" : "\n--- 原版 ---\n" + original.summary + "--- helper ---\n" + helper.summary), same);

            int expectedSends = spec.expectedSends < 0 ? spec.connections : spec.expectedSends;
            check(spec.name + "：原版送出 " + expectedSends + " 封（實際 " + original.sends + "）",
                    original.sends == expectedSends);
            check(spec.name + "：原版記錄 " + spec.expectedLogged + " 次例外（實際 " + original.logged + "）",
                    original.logged == spec.expectedLogged);

            // 已知上游缺陷：原生 Error 會讓 sendLock 留在持有狀態——原版與 helper 都一樣，
            // 這裡把它釘死，日後若 vanilla 修了（或我們誤改了）都會在這條紅。
            boolean expectSendLockFree = spec.failKind != 2;
            check(spec.name + "：peer sendLock free=" + expectSendLockFree + "（原版與 helper 同）",
                    original.sendLockFree == expectSendLockFree && helper.sendLockFree == expectSendLockFree);
            check(spec.name + "：每條連線的 buffer 鎖都已釋放", original.locksFree && helper.locksFree);

            if (spec.decodable) {
                for (int i = 0; i < helper.states.size(); i++) {
                    String expected = spec.mutateAt >= 0 && i >= spec.mutateAt
                            ? expectedState.replace(CHUM_A + ":120/", CHUM_A + ":999/") : expectedState;
                    check(spec.name + "：真 client 解析後狀態 " + helper.states.get(i) + " 應為 " + expected,
                            helper.states.get(i).equals(expected));
                }
            }

            int expectedSnapshots = enabled ? spec.expectedSnapshots : 0;
            check(spec.name + "：helper 快照配置嘗試 " + expectedSnapshots + " 次（實際 "
                    + helper.snapshots + "；off 時必須為 0＝純委派原版）",
                    helper.snapshots == expectedSnapshots);
        }

        private static Report runBatch(Spec spec, boolean useHelper) throws Exception {
            if (spec.noise instanceof OnceFailingNoise unstable) {
                unstable.calls = 0;
            }
            if (spec.mutateAt >= 0) {
                spec.chum.put(CHUM_A, new zombie.iso.FishSchoolManager.ChumData(120, 480));
            }

            java.util.List<zombie.core.raknet.UdpConnection> connections =
                    spec.mutateViaList ? new MutatingList(spec) : new java.util.ArrayList<>();
            zombie.core.raknet.UdpEngine engine =
                    zombie.core.raknet.UdpEngine.class.getDeclaredConstructor().newInstance();
            set(zombie.core.raknet.UdpEngine.class, engine, "peer", new zombie.core.raknet.RakNetPeerInterface());
            set(zombie.core.raknet.UdpEngine.class, engine, "connections", connections);

            for (int i = 0; i < spec.connections; i++) {
                zombie.core.raknet.UdpConnection c = !spec.mutateViaList && i == spec.mutateAt
                        ? new ChangingConnection(engine, 7000L + i, i, spec)
                        : new zombie.core.raknet.UdpConnection(engine, 7000L + i, i);
                if (i == spec.smallWriter) {
                    replaceBuffer(c, java.nio.ByteBuffer.allocate(32));
                }
                if (i == spec.littleEndian) {
                    java.nio.ByteBuffer original = (java.nio.ByteBuffer)
                            get(zombie.core.raknet.UdpConnection.class, c, "bb");
                    replaceBuffer(c, java.nio.ByteBuffer.allocate(original.capacity())
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN));
                }
                connections.add(c);
            }
            zombie.network.GameServer.udpEngine = engine;

            Probe.reset();
            Probe.failAtCall = spec.failAtCall;
            Probe.failKind = spec.failKind;
            Probe.snapshotFailure = spec.snapshotFailure;

            if (useHelper) {
                zombie.mdc.FishingDataBroadcast.transmitFishingData(spec.seed, spec.trashSeed, spec.noise, spec.chum);
            } else {
                zombie.network.GameServer.transmitFishingData(spec.seed, spec.trashSeed, spec.noise, spec.chum);
            }

            Report report = new Report();
            report.sends = Probe.sent.size();
            report.logged = Probe.exceptionLogged;
            report.snapshots = Probe.snapshotAttempts;
            report.sendLockFree = lockFree((java.util.concurrent.locks.Lock) get(
                    zombie.core.raknet.RakNetPeerInterface.class,
                    get(zombie.core.raknet.UdpEngine.class, engine, "peer"), "sendLock"));

            StringBuilder sb = new StringBuilder();
            sb.append("SENDLOCKFREE ").append(report.sendLockFree).append('\n');
            for (Probe.Sent sent : Probe.sent) {
                sb.append(sent.describe()).append('\n');
            }
            sb.append("LOGGED ").append(Probe.exceptionLogged).append('\n');
            report.locksFree = true;
            for (zombie.core.raknet.UdpConnection c : connections) {
                boolean free = lockFree((java.util.concurrent.locks.Lock)
                        get(zombie.core.raknet.UdpConnection.class, c, "bufferLock"));
                report.locksFree &= free;
                java.nio.ByteBuffer buffer = (java.nio.ByteBuffer)
                        get(zombie.core.raknet.UdpConnection.class, c, "bb");
                sb.append("LOCKFREE ").append(free)
                        .append(" BUFFER ").append(buffer.position()).append('/').append(buffer.limit())
                        .append('/').append(buffer.order()).append('\n');
            }
            for (Probe.Sent sent : Probe.sent) {
                String state = decodeState(sent.data);
                report.states.add(state);
                sb.append("STATE ").append(state).append('\n');
            }
            report.summary = sb.toString();
            return report;
        }

        // ------------------------------------------------------------ 真 client 解析

        private static String decodeState(byte[] packet) throws Exception {
            zombie.core.network.ByteBufferReader reader =
                    new zombie.core.network.ByteBufferReader(java.nio.ByteBuffer.wrap(packet));
            byte marker = reader.getByte();
            short id = reader.getShort();
            if (marker != (byte) 134 || id != zombie.network.PacketTypes.PacketType.FishingData.getId()) {
                return "BADHEADER marker=" + marker + " id=" + id;
            }
            zombie.network.GameClient.receiveFishingData(reader, id);
            return clientState();
        }

        @SuppressWarnings("rawtypes")
        private static String clientState() throws Exception {
            zombie.iso.FishSchoolManager manager = zombie.iso.FishSchoolManager.getInstance();
            int seed = (Integer) get(zombie.iso.FishSchoolManager.class, manager, "seed");
            int trashSeed = (Integer) get(zombie.iso.FishSchoolManager.class, manager, "trashSeed");
            gnu.trove.map.hash.TLongIntHashMap noise = (gnu.trove.map.hash.TLongIntHashMap)
                    get(zombie.iso.FishSchoolManager.class, null, "noiseFishPointDisabler");
            gnu.trove.map.hash.TLongObjectHashMap chum = (gnu.trove.map.hash.TLongObjectHashMap)
                    get(zombie.iso.FishSchoolManager.class, null, "chumPoints");
            long[] noiseKeys = noise.keys();
            java.util.Arrays.sort(noiseKeys);
            long[] chumKeys = chum.keys();
            java.util.Arrays.sort(chumKeys);
            StringBuilder sb = new StringBuilder("seed=").append(seed).append(" trash=").append(trashSeed)
                    .append(" noise=").append(java.util.Arrays.toString(noiseKeys)).append(" chum=[");
            for (long key : chumKeys) {
                zombie.iso.FishSchoolManager.ChumData data =
                        (zombie.iso.FishSchoolManager.ChumData) chum.get(key);
                sb.append(key).append(':').append(data.maxForceTime).append('/').append(data.endTime).append(',');
            }
            return sb.append(']').toString();
        }

        /** client 應得的結果：所有 key 到齊、maxForceTime 保真、endTime 不在線上傳輸（恆 0）。 */
        private static String expectedState(Spec spec) {
            long[] noiseKeys = spec.noise.keys();
            java.util.Arrays.sort(noiseKeys);
            long[] chumKeys = spec.chum.keys();
            java.util.Arrays.sort(chumKeys);
            StringBuilder sb = new StringBuilder("seed=").append(spec.seed).append(" trash=").append(spec.trashSeed)
                    .append(" noise=").append(java.util.Arrays.toString(noiseKeys)).append(" chum=[");
            for (long key : chumKeys) {
                zombie.iso.FishSchoolManager.ChumData data = spec.chum.get(key);
                sb.append(key).append(':').append(data == null ? "null" : data.maxForceTime).append("/0").append(',');
            }
            return sb.append(']').toString();
        }

        // ------------------------------------------------------------ fixture 小工具

        private static void replaceBuffer(zombie.core.raknet.UdpConnection c, java.nio.ByteBuffer buffer)
                throws Exception {
            set(zombie.core.raknet.UdpConnection.class, c, "bb", buffer);
            set(zombie.core.raknet.UdpConnection.class, c, "bbw",
                    new zombie.core.network.ByteBufferWriter(buffer));
        }

        private static boolean lockFree(java.util.concurrent.locks.Lock lock) throws Exception {
            boolean[] acquired = new boolean[1];
            Thread t = new Thread(() -> {
                if (lock.tryLock()) {
                    acquired[0] = true;
                    lock.unlock();
                }
            }, "lock-probe");
            t.setDaemon(true);
            t.start();
            t.join(10_000L);
            if (t.isAlive()) {
                throw new AssertionError("lock-probe 逾時未結束（鎖狀態無法判定）");
            }
            return acquired[0];
        }

        private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        }

        private static Object get(Class<?> owner, Object target, String name) throws Exception {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        }


        private static Spec spec(String name, int connections) {
            Spec spec = new Spec();
            spec.name = name;
            spec.connections = connections;
            spec.noise = new gnu.trove.map.hash.TLongIntHashMap();
            for (long key : NOISE_KEYS) {
                spec.noise.put(key, (int) (key - 1000L) * 10);
            }
            spec.chum = new gnu.trove.map.hash.TLongObjectHashMap<>();
            spec.chum.put(CHUM_A, new zombie.iso.FishSchoolManager.ChumData(120, 480));
            spec.chum.put(CHUM_B, new zombie.iso.FishSchoolManager.ChumData(-7, 9));
            return spec;
        }

        private static void check(String what, boolean ok) {
            System.out.println((ok ? "fishing-data-broadcast OK   " : "fishing-data-broadcast FAIL ") + what);
            if (!ok) {
                throw new AssertionError(what);
            }
        }

        /** 首次走訪拋一次的 map 子類別：暫時性序列化失敗＋非 exact map 兩件事各一個代表。 */
        private static final class OnceFailingNoise extends gnu.trove.map.hash.TLongIntHashMap {
            int calls;

            @Override
            public boolean forEachKey(gnu.trove.procedure.TLongProcedure action) {
                if (calls++ == 0) {
                    throw new IllegalStateException("first traversal failed");
                }
                return super.forEachKey(action);
            }
        }

        /** 自訂連線子類別：在自己被 startPacket 時原地改動來源。 */
        private static final class ChangingConnection extends zombie.core.raknet.UdpConnection {
            private final Spec spec;

            ChangingConnection(zombie.core.raknet.UdpEngine engine, long guid, int index, Spec spec) {
                super(engine, guid, index);
                this.spec = spec;
            }

            @Override
            public zombie.core.network.ByteBufferWriter startPacket() {
                spec.chum.get(CHUM_A).maxForceTime = 999;
                return super.startPacket();
            }
        }

        /**
         * 自訂 connections 清單：{@code get} 是任意可執行碼，能在兩個收件人<b>之間</b>原地改動
         * 兩張 exact map——連線類別與 map 類別檢查都攔不到。helper 必須整批直通原版。
         */
        private static final class MutatingList extends java.util.ArrayList<zombie.core.raknet.UdpConnection> {
            private final Spec spec;

            MutatingList(Spec spec) {
                this.spec = spec;
            }

            @Override
            public zombie.core.raknet.UdpConnection get(int index) {
                if (index == spec.mutateAt) {
                    spec.chum.get(CHUM_A).maxForceTime = 999;
                }
                return super.get(index);
            }
        }

        private static final class Spec {
            String name;
            int connections;
            int smallWriter = -1;
            int littleEndian = -1;
            int failAtCall = -1;
            int failKind;
            boolean snapshotFailure;
            int mutateAt = -1;
            boolean mutateViaList;
            boolean decodable = true;
            int seed = 4242;
            int trashSeed = 777;
            int expectedSends = -1;   // -1＝每條連線一封
            int expectedLogged;
            int expectedSnapshots;
            gnu.trove.map.hash.TLongIntHashMap noise;
            gnu.trove.map.hash.TLongObjectHashMap<zombie.iso.FishSchoolManager.ChumData> chum;
        }

        private static final class Report {
            String summary = "";
            int sends;
            int logged;
            int snapshots;
            boolean locksFree;
            boolean sendLockFree;
            final java.util.List<String> states = new java.util.ArrayList<>();
        }

        private Body() {}
    }

    // ---------------------------------------------------------------- loader

    /**
     * self-first over [測試 class 目錄, dist\java, 遊戲 jar]：受測鏈（含正式 helper）
     * 一律由這裡載入並改寫，JDK 類別才委派 parent。
     */
    private static final class GameLoader extends URLClassLoader {

        private static final String RAKNET = "zombie.core.raknet.RakNetPeerInterface";
        private static final String UDPENGINE = "zombie.core.raknet.UdpEngine";
        private static final String EXCLOGGER = "zombie.core.logger.ExceptionLogger";
        private static final String HELPER = "zombie.mdc.FishingDataBroadcast";
        private static final Set<String> TRANSFORMED = Set.of(RAKNET, UDPENGINE, EXCLOGGER, HELPER);
        private static final String SEND_NATIVE_DESC = "(Ljava/nio/ByteBuffer;IIIBJZ)I";
        private static final String PROBE = "FishingDataBroadcastTest$Probe";

        GameLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (name.startsWith("java.") || name.startsWith("jdk.")
                            || name.startsWith("sun.") || name.startsWith("com.sun.")) {
                        c = super.loadClass(name, false);
                    } else {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException notLocal) {
                            c = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            URL url = findResource(path);
            if (url == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] raw;
            try (InputStream in = url.openStream()) {
                raw = in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
            byte[] bytes = TRANSFORMED.contains(name) ? transform(name, raw) : raw;
            return defineClass(name, bytes, 0, bytes.length);
        }

        private static byte[] transform(String name, byte[] raw) {
            ClassReader reader = new ClassReader(raw);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor;
            if (RAKNET.equals(name)) {
                visitor = new SendNativeStub(writer);
            } else if (UDPENGINE.equals(name)) {
                visitor = new AddNoArgCtor(writer);
            } else if (HELPER.equals(name)) {
                visitor = new SnapshotAllocationHook(writer);
            } else {
                visitor = new CountExceptionLog(writer);
            }
            reader.accept(visitor, 0);
            return writer.toByteArray();
        }

        /** 唯一被假造的一層：JNI 葉子 sendNative → Probe.onSendNative（其上全是實碼）。 */
        private static final class SendNativeStub extends ClassVisitor {
            private boolean found;

            SendNativeStub(ClassVisitor cv) {
                super(Opcodes.ASM9, cv);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (!"sendNative".equals(name)) {
                    return super.visitMethod(access, name, desc, sig, exceptions);
                }
                if (!SEND_NATIVE_DESC.equals(desc)) {
                    throw new IllegalStateException("sendNative descriptor 已變（" + desc + "）——jar 形狀已變，需重評本測試");
                }
                found = true;
                MethodVisitor mv = super.visitMethod(access & ~Opcodes.ACC_NATIVE, name, desc, sig, exceptions);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 1);  // ByteBuffer
                mv.visitVarInsn(Opcodes.ILOAD, 2);  // length
                mv.visitVarInsn(Opcodes.ILOAD, 3);  // priority
                mv.visitVarInsn(Opcodes.ILOAD, 4);  // reliability
                mv.visitVarInsn(Opcodes.ILOAD, 5);  // ordering channel
                mv.visitVarInsn(Opcodes.LLOAD, 6);  // guid
                mv.visitVarInsn(Opcodes.ILOAD, 8);  // broadcast
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "onSendNative", SEND_NATIVE_DESC, false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return null;
            }

            @Override
            public void visitEnd() {
                if (!found) {
                    throw new IllegalStateException("RakNetPeerInterface.sendNative 不存在——無法擷取送出的封包");
                }
                super.visitEnd();
            }
        }

        /** 補一個 public UdpEngine()：免 RakNet 原生 Init/Startup，既有方法一律不動。 */
        private static final class AddNoArgCtor extends ClassVisitor {
            private String superName = "java/lang/Object";

            AddNoArgCtor(ClassVisitor cv) {
                super(Opcodes.ASM9, cv);
            }

            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] itf) {
                this.superName = superName;
                super.visit(version, access, name, sig, superName, itf);
            }

            @Override
            public void visitEnd() {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                super.visitEnd();
            }
        }

        /** 計數 ExceptionLogger.logException(Throwable)；原 body 照跑（觀測，不是假造）。 */
        private static final class CountExceptionLog extends ClassVisitor {
            private boolean found;

            CountExceptionLog(ClassVisitor cv) {
                super(Opcodes.ASM9, cv);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if (mv == null || !"logException".equals(name) || !"(Ljava/lang/Throwable;)V".equals(desc)) {
                    return mv;
                }
                found = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "onExceptionLogged", "()V", false);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (!found) {
                    throw new IllegalStateException("ExceptionLogger.logException(Throwable) 不存在——無法計數失敗");
                }
                super.visitEnd();
            }
        }

        /**
         * helper 內唯一的快照 {@code new byte[]} → Probe.allocateSnapshot：注入一次性 OOM，
         * 兼作共用路徑的計數器。恰一處，否則 helper 形狀已變、本測試的前提需重評。
         */
        private static final class SnapshotAllocationHook extends ClassVisitor {
            private int hits;

            SnapshotAllocationHook(ClassVisitor cv) {
                super(Opcodes.ASM9, cv);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if (!"transmitFishingData".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        if (opcode == Opcodes.NEWARRAY && operand == Opcodes.T_BYTE) {
                            hits++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "allocateSnapshot", "(I)[B", false);
                        } else {
                            super.visitIntInsn(opcode, operand);
                        }
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (hits != 1) {
                    throw new AssertionError("FishingDataBroadcast 的快照配置點應恰一處，實際 " + hits);
                }
                super.visitEnd();
            }
        }
    }

    private FishingDataBroadcastTest() {}
}
