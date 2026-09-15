package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.objects.IsoHutch;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.ZomboidNetData;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.AnimalUpdatePacket;
import zombie.network.packets.character.AnimalUpdateReliablePacket;
import zombie.network.packets.character.AnimalUpdateUnreliablePacket;
import zombie.popman.animal.AnimalInstanceManager;
import zombie.popman.animal.AnimalSynchronizationManager;

/**
 * W29 行為回歸；enforce／off／unknown 各跑獨立 JVM，argv 自驗實際 property。
 * 未套用守衛時，真 GameServer 接收入口的容器狀態保護斷言必須失敗。
 *
 * <p>核心案例走真接收入口、pooled packet、parse／processServer 與同步請求表。
 * 另直接呼叫 helper，避免原版外層 catch 掩蓋拒絕例外；紀錄測試使用真 DebugLog
 * 輸出與反射讀取 counter，沒有 production 測試 API。
 *
 * <p>狀態探針使用雞舍／巢箱的成員資格，因為此變更先於需要完整世界的生命週期收尾。
 * 世界／連線等引擎相依物件以 Unsafe 配置，Role／packet 使用真建構子；
 * Conn 只替換每連線每型別的 packet cache，並在授權後的限流檢查處觀察委派。
 * Rand 必須先播種，避免類別初始化永久失敗。
 *
 * <p>主要正常資料由原版 client writer 產生；另手組 buffer 邊界與異常資料。
 */
public final class AnimalUpdateGuardTest {

    // 刻意不是 static final 初始式：碰 PacketTypes 會拉起 AntiCheat／ServerOptions 靜態鏈，
    // 而那條鏈需要已播種的全域 Rand。若在類別初始化就解析，就會早於 main 的
    // RandStandard.init()，一次 ExceptionInInitializerError 讓該 JVM 永久 NoClassDefFoundError。
    private static PacketTypes.PacketType RELIABLE;
    private static PacketTypes.PacketType UNRELIABLE;
    /** 非目標型別對照：requiredCapability 同為 LoginOnServer，handlingType=2（server 端不解析）。 */
    private static PacketTypes.PacketType OTHER;

    private static final long GUID = 990011L;
    private static final short[] NONE = new short[0];

    /** true＝期望退化成原版行為（kill switch off）；false＝期望守衛生效。 */
    private static boolean expectVanilla;
    private static Conn conn;
    private static Method mainLoop;
    private static Map<Long, HashSet<Short>> requests;
    private static int failed;

    public static void main(String[] args) throws Exception {
        RandStandard.INSTANCE.init();
        GameServer.server = true;

        final String mode = args.length > 0 ? args[0] : "enforce";
        final String raw = System.getProperty("mdc.animalUpdateGuard");
        RELIABLE = PacketTypes.PacketType.AnimalUpdateReliable;
        UNRELIABLE = PacketTypes.PacketType.AnimalUpdateUnreliable;
        OTHER = PacketTypes.PacketType.SneezeCough;
        switch (mode) {
            case "enforce":
                expectVanilla = false;
                expect("自驗：enforce 組態不得帶 mdc.animalUpdateGuard（實際 " + raw + "）", raw == null);
                break;
            case "off":
                expectVanilla = true;
                expect("自驗：off 組態必須帶 mdc.animalUpdateGuard=0|off（實際 " + raw + "）",
                        "0".equals(raw) || "off".equals(raw));
                break;
            case "unknown":
                expectVanilla = false;
                expect("自驗：unknown 組態必須帶未知值（實際 " + raw + "）",
                        raw != null && !"0".equals(raw) && !"off".equals(raw));
                break;
            default:
                throw new IllegalArgumentException("未知 argv：" + mode);
        }

        // ── 接線與夾具 ────────────────────────────────────────────────────
        final AnimalInstanceManager mgr = AnimalInstanceManager.getInstance();
        conn = connection(GUID);
        conn.pooled.put(RELIABLE, new AnimalUpdateReliablePacket());
        conn.pooled.put(UNRELIABLE, new AnimalUpdateUnreliablePacket());

        UdpEngine engine = alloc(UdpEngine.class);
        Map<Long, UdpConnection> connectionMap = new HashMap<>();
        connectionMap.put(GUID, conn);
        setField(UdpEngine.class, engine, "connectionMap", connectionMap);
        GameServer.udpEngine = engine;

        mainLoop = GameServer.class.getDeclaredMethod("mainLoopDealWithNetData", ZomboidNetData.class);
        mainLoop.setAccessible(true);
        requests = requestsMap();

        final IsoHutch hutch = hutch();

        // ── B 正常 request 語意：原版 writer 產生的 wire 必須逐位元被接受並如實落表 ──
        expectAccepted("合法 request（reliable，原版 client writer 產生）",
                RELIABLE, clientWire((short) 11, (short) 12), setOf((short) 11, (short) 12));
        expectAccepted("合法 request（unreliable，另一個真註冊子類）",
                UNRELIABLE, clientWire((short) 21, (short) 22), setOf((short) 21, (short) 22));
        expectAccepted("合法空 request（8 byte 最小合法表頭，兩個 count 皆 0）",
                RELIABLE, header(0, 0, 0, 0), setOf());

        // 緩衝區形狀：守衛必須用 absolute 讀、以當前 position 為基準，不得假設 array()／position 0
        expectAccepted("合法 request（position=3 的 offset buffer）",
                RELIABLE, offset(clientWire((short) 31)), setOf((short) 31));
        expectAccepted("合法 request（direct buffer，無 backing array）",
                RELIABLE, direct(clientWire((short) 32)), setOf((short) 32));
        expectAccepted("合法 request（read-only buffer，無 backing array）",
                RELIABLE, readOnly(clientWire((short) 33)), setOf((short) 33));
        expectAccepted("合法 request（slice，底層 array offset 非零）",
                RELIABLE, offset(clientWire((short) 35)).slice(), setOf((short) 35));
        // byte order 是呼叫端既有狀態：守衛只准照它讀，不准改、也不准硬當 BIG_ENDIAN
        ByteBuffer le = littleEndianWire((short) 34);
        expectAccepted("合法 request（LITTLE_ENDIAN buffer，照既有 order 讀）",
                RELIABLE, le, setOf((short) 34));
        expect("合法 request：buffer 的 byte order 未被守衛改動（仍 LITTLE_ENDIAN）",
                le.order() == ByteOrder.LITTLE_ENDIAN);
        // limit 之內剛好吻合：證明「以 limit 為界」的那一半不是靠 capacity 湊巧成立
        expectAccepted("合法 request（capacity 16、limit 10 剛好吻合，limit 外仍有舊位元組）",
                RELIABLE, staleTail(10), setOf((short) 205));

        // ── C 非目標型別：守衛不得攔、不得吞，原封不動委派 ───────────────────
        IsoAnimal bystander = hutchAnimal(mgr, (short) 106, hutch, 6);
        int otherBefore = delegations(OTHER);
        Throwable otherThrown = feed(OTHER, wire(new short[] {106}, NONE, 0));
        expect("非目標型別（SneezeCough）：委派原版恰一次（守衛只攔兩個 AnimalUpdate）",
                delegations(OTHER) == otherBefore + 1);
        expect("非目標型別：無例外穿出 mainLoop", otherThrown == null);
        expect("非目標型別：同一份位元組不被當成動物刪除清單執行（該型別 server 端不解析）",
                hutch.animalInside.get(6) == bystander);

        // ── D 畸形封包整包拒絕（於任何原版 parse／狀態修改之前） ──────────────
        expectDropped("表頭 0 byte（連一個 int 都沒有）", RELIABLE, truncated(0));
        expectDropped("表頭 4 byte（只有刪除 count）", RELIABLE, truncated(4));
        expectDropped("表頭 7 byte（差一個 byte 不足兩個 int）", RELIABLE, truncated(7));
        expectDropped("刪除 count=1（正常 client 沒有這個清單的寫入者）",
                RELIABLE, wire(new short[] {201}, NONE, 0));
        expectDropped("刪除 count=-1（負數刪除計數）", RELIABLE, header(-1, 0, 0, 0));
        expectDropped("requested count=-1（負數）", RELIABLE, header(0, -1, 0, 0));
        expectDropped("requested count=Integer.MIN_VALUE（負數極值）",
                RELIABLE, header(0, Integer.MIN_VALUE, 0, 0));
        expectDropped("requested count=Integer.MAX_VALUE（*2 溢位）",
                RELIABLE, header(0, Integer.MAX_VALUE, 0, 0));
        expectDropped("requested count=0x40000000（*2 恰溢位成負數）",
                RELIABLE, header(0, 0x40000000, 1, 0));
        expectDropped("requested count=3 但只有 2 個 ID（截斷）", RELIABLE, header(0, 3, 2, 0));
        expectDropped("requested count=1、1 個 ID 再加 1 byte 尾巴（奇數剩餘）",
                RELIABLE, header(0, 1, 1, 1));
        expectDropped("requested count=1、1 個 ID 再加 2 byte 尾巴（偶數但數量不吻合）",
                RELIABLE, header(0, 1, 1, 2));
        expectDropped("requested count=0 但帶 2 byte 尾巴", RELIABLE, header(0, 0, 0, 2));
        expectDropped("unreliable 子類同樣畸形（刪除 count=1）",
                UNRELIABLE, wire(new short[] {202}, NONE, 0));
        expectDropped("requested count=1 但 ID 位元組落在 limit 之外（capacity 內仍看得到舊資料）",
                RELIABLE, staleTail(8));
        expectDropped("表頭只露 4 byte、limit 外全是舊位元組", RELIABLE, staleTail(4));

        // ── E pooled packet：正常 → 拒絕 → 正常，不得重播舊請求 ────────────────
        IsoAnimal pooledVictim = hutchAnimal(mgr, (short) 104, hutch, 4);
        expectAccepted("pooled ①正常請求 {41,42}", RELIABLE,
                clientWire((short) 41, (short) 42), setOf((short) 41, (short) 42));
        Set<Short> beforeReject = requestTable();
        int pooledBefore = delegations(RELIABLE);
        Throwable pooledThrown = feed(RELIABLE, wire(new short[] {104}, NONE, 0));
        if (expectVanilla) {
            expect("pooled ②（off）：委派原版 ⇒ 雞舍格位被清空（危險行為對照）",
                    hutch.animalInside.get(4) == null);
            expect("pooled ②（off）：原版入口被執行", delegations(RELIABLE) == pooledBefore + 1);
        } else {
            expect("pooled ②拒絕：雞舍格位保留（動物仍在原處）",
                    hutch.animalInside.get(4) == pooledVictim);
            expect("pooled ②拒絕：從未進入原版 onServerPacket",
                    delegations(RELIABLE) == pooledBefore);
            expect("pooled ②拒絕：無例外穿出 mainLoop", pooledThrown == null);
            expect("pooled ②拒絕：請求表不變（不清空、不污染）",
                    Objects.equals(beforeReject, requestTable()));
        }
        expectAccepted("pooled ③正常請求 {43}：恰為新集合，未重播 ①的 {41,42}",
                RELIABLE, clientWire((short) 43), setOf((short) 43));

        // pooled 封包的四組集合先弄髒：整包拒絕必須連 parse 開頭的 deleted.clear()／
        // requested.clear() 都沒跑到，否則就代表拒絕點在原版狀態修改之後。
        if (!expectVanilla) {
            AnimalUpdatePacket pooledPacket = (AnimalUpdatePacket) conn.pooled.get(RELIABLE);
            pooledPacket.getRequested().add((short) 71);
            pooledPacket.getUpdated().add((short) 72);
            pooledPacket.getDeleted().add((short) 73);
            pooledPacket.getPending().add((short) 74);
            feed(RELIABLE, wire(new short[] {203}, NONE, 0));
            expect("拒絕後 pooled requested 保留上一包及新加入的資料",
                    pooledPacket.getRequested().equals(setOf((short) 43, (short) 71)));
            expect("拒絕後 pooled updated 原封不動",
                    pooledPacket.getUpdated().equals(setOf((short) 72)));
            expect("拒絕後 pooled deleted 原封不動",
                    pooledPacket.getDeleted().equals(setOf((short) 73)));
            expect("拒絕後 pooled pending 原封不動",
                    pooledPacket.getPending().equals(setOf((short) 74)));
            pooledPacket.getRequested().clear();
            pooledPacket.getUpdated().clear();
            pooledPacket.getDeleted().clear();
            pooledPacket.getPending().clear();
        }

        // ── F 真實損害：原版可執行的未授權移除（本測試的預修復反例） ────────────
        // 涵蓋雞舍格位與非零 position 下的巢箱狀態保護。
        IsoAnimal hutched = hutchAnimal(mgr, (short) 101, hutch, 1);
        expectDeletion("雞舍格位（reliable）", RELIABLE, wire(new short[] {101}, NONE, 0),
                () -> hutch.animalInside.get(1) == hutched, () -> hutch.animalInside.get(1) == null);

        IsoAnimal hutched2 = hutchAnimal(mgr, (short) 102, hutch, 2);
        expectDeletion("雞舍格位（unreliable，另一個真註冊子類）", UNRELIABLE,
                wire(new short[] {102}, NONE, 0),
                () -> hutch.animalInside.get(2) == hutched2, () -> hutch.animalInside.get(2) == null);

        IsoAnimal nested = nestBoxAnimal(mgr, (short) 103, hutch, 1);
        expectDeletion("巢箱（offset buffer 上的惡意刪除）", RELIABLE,
                offset(wire(new short[] {103}, NONE, 0)),
                () -> hutch.getAnimalInNestBox(1) == nested,
                () -> hutch.getAnimalInNestBox(1) == null);

        // 混合包：同一包既要刪又要請求——拒絕必須是整包拒絕，request 不得偷渡
        IsoAnimal mixedVictim = hutchAnimal(mgr, (short) 105, hutch, 5);
        Set<Short> beforeMixed = requestTable();
        int mixedBefore = delegations(RELIABLE);
        feed(RELIABLE, wire(new short[] {105}, new short[] {51, 52}, 0));
        if (expectVanilla) {
            expect("混合包（off）：委派原版 ⇒ 雞舍格位被清空（危險行為對照）",
                    hutch.animalInside.get(5) == null);
        } else {
            expect("混合包：雞舍格位保留", hutch.animalInside.get(5) == mixedVictim);
            expect("混合包：整包拒絕，requested 不得偷渡進請求表",
                    Objects.equals(beforeMixed, requestTable()));
            expect("混合包：從未進入原版 onServerPacket", delegations(RELIABLE) == mixedBefore);
        }

        // 直接呼叫補丁入口，分開驗證例外傳播及有限頻的真實紀錄輸出。
        Method entry = guardEntry();
        IsoAnimal directVictim = hutchAnimal(mgr, (short) 107, hutch, 7);
        int directBefore = delegations(RELIABLE);
        Throwable directThrown = invokeDirect(entry, RELIABLE, wire(new short[] {107}, NONE, 0));
        if (expectVanilla) {
            expect("直呼 helper（off）：雞舍格位被原版清空", hutch.animalInside.get(7) == null);
            expect("直呼 helper（off）：委派原版恰一次", delegations(RELIABLE) == directBefore + 1);
        } else {
            expect("直呼 helper：拒絕不向外層拋例外", directThrown == null);
            expect("直呼 helper：雞舍格位保留", hutch.animalInside.get(7) == directVictim);
            expect("直呼 helper：不執行原版解析", delegations(RELIABLE) == directBefore);
            ByteBuffer protectedBuffer = offset(header(0, -1, 0, 0)).asReadOnlyBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN);
            protectedBuffer.position(2).mark().position(3);
            int savedLimit = protectedBuffer.limit();
            expect("直接拒絕保留 position／limit／order",
                    invokeDirect(entry, RELIABLE, protectedBuffer) == null
                            && protectedBuffer.position() == 3 && protectedBuffer.limit() == savedLimit
                            && protectedBuffer.order() == ByteOrder.LITTLE_ENDIAN);
            protectedBuffer.reset();
            expect("直接拒絕保留呼叫端先前的 mark", protectedBuffer.position() == 2);
            checkLogging(entry);
        }
        Set<Short> directLegit = setOf((short) 61);
        int legitBefore = delegations(RELIABLE);
        Throwable legitThrown = invokeDirect(entry, RELIABLE, clientWire((short) 61));
        expect("直呼 helper：合法 request 委派原版恰一次", delegations(RELIABLE) == legitBefore + 1);
        expect("直呼 helper：合法 request 無例外", legitThrown == null);
        expect("直呼 helper：合法 request 如實落表", Objects.equals(directLegit, requestTable()));

        RuntimeException originalFailure = new IllegalStateException("original-dispatch-failure");
        conn.delegationFailure = originalFailure;
        try {
            expect("非目標封包原委派例外 identity 保留",
                    invokeDirect(entry, OTHER, truncated(0)) == originalFailure);
        } finally {
            conn.delegationFailure = null;
        }

        if (failed > 0) {
            System.out.println("animal-update-guard FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("animal-update-guard OK  mode=" + mode
                + "：真接線（GameServer.mainLoopDealWithNetData）／原版 writer 合法 wire／"
                + "offset+direct+read-only buffer／非目標型別原委派／畸形整包拒絕（短表頭・負數・"
                + "溢位・截斷・尾巴）／pooled 不重播／"
                + (expectVanilla ? "kill switch off 危險行為對照（雞舍格位真的被清空）"
                        : "雞舍格位與巢箱皆未被未授權移除")
                + " 全數通過");
    }

    // ── 期望形狀 ──────────────────────────────────────────────────────────

    /** 合法流量：三個模式都必須一致——委派恰一次、無例外、請求表恰等於期望集合。 */
    private static void expectAccepted(String what, PacketTypes.PacketType type, ByteBuffer payload,
            Set<Short> expectedIds) throws Exception {
        int before = delegations(type);
        Throwable thrown = feed(type, payload);
        expect(what + "：委派原版 onServerPacket 恰一次", delegations(type) == before + 1);
        expect(what + "：無例外穿出 mainLoop", thrown == null);
        expect(what + "：請求表 == " + expectedIds + "（實際 " + requestTable() + "）",
                Objects.equals(expectedIds, requestTable()));
    }

    /** 畸形流量：enforce 側整包拒絕、狀態零變動；off 側必須退化成原版（委派照發）。 */
    private static void expectDropped(String what, PacketTypes.PacketType type, ByteBuffer payload)
            throws Exception {
        if (!expectVanilla) {
            expect(what + "：直接拒絕也不拋例外，不依賴 mainLoop 的 catch",
                    invokeDirect(guardEntry(), type, payload.duplicate().order(payload.order())) == null);
        }
        int before = delegations(type);
        Set<Short> table = requestTable();
        Throwable thrown = feed(type, payload);
        if (expectVanilla) {
            expect(what + "（off）：kill switch 真的退回原版（委派恰一次）",
                    delegations(type) == before + 1);
        } else {
            expect(what + "：從未進入原版 onServerPacket（拒絕先於任何 parse）",
                    delegations(type) == before);
            expect(what + "：無例外穿出 mainLoop", thrown == null);
            expect(what + "：請求表不變", Objects.equals(table, requestTable()));
        }
    }

    /**
     * 未授權移除：enforce 側動物必須仍在原處；off 側必須真的不見（危險行為對照＝本測試
     * 在未接線的原版 GameServer 上會紅的那一條）。
     */
    private static void expectDeletion(String what, PacketTypes.PacketType type, ByteBuffer payload,
            Probe intact, Probe destroyed) throws Exception {
        expect(what + "：前置——動物確實掛在容器裡", intact.ok());
        int before = delegations(type);
        Throwable thrown = feed(type, payload);
        if (expectVanilla) {
            expect(what + "（off）：原版收下並執行移除，容器格位被清空", destroyed.ok());
            expect(what + "（off）：原版入口被執行", delegations(type) == before + 1);
        } else {
            expect(what + "：動物仍在原處（未授權移除被整包拒絕）", intact.ok());
            expect(what + "：從未進入原版 onServerPacket", delegations(type) == before);
            expect(what + "：無例外穿出 mainLoop", thrown == null);
        }
    }

    private interface Probe {
        boolean ok();
    }

    // ── 真接線呼叫 ────────────────────────────────────────────────────────

    /** 組真的 ZomboidNetData 並餵進 GameServer 的封包分派點；回傳穿出 mainLoop 的 Throwable。 */
    private static Throwable feed(PacketTypes.PacketType type, ByteBuffer payload) throws Exception {
        ZomboidNetData data = alloc(ZomboidNetData.class);
        // buffer 是 public final：真實流程由建構子配置 heap buffer，這裡改寫才能測
        // offset／direct／read-only 三種形狀（同 UdpEngine.connectionMap 的既有做法）。
        setField(ZomboidNetData.class, data, "buffer", new ByteBufferReader(payload));
        data.type = type;
        data.connection = GUID;
        try {
            mainLoop.invoke(null, data);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private static Method guardEntry() throws ReflectiveOperationException {
        return Class.forName("zombie.mdc.AnimalUpdateGuard")
                .getDeclaredMethod("onServerPacket", PacketTypes.PacketType.class,
                        ByteBufferReader.class, UdpConnection.class);
    }

    private static Throwable invokeDirect(Method entry, PacketTypes.PacketType type, ByteBuffer payload) {
        try {
            entry.invoke(null, type, new ByteBufferReader(payload), conn);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (ReflectiveOperationException e) {
            return e;
        }
    }

    private static void checkLogging(Method entry) throws Exception {
        Class<?> guard = entry.getDeclaringClass();
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        LogSeverity previousSeverity = DebugType.General.getLogSeverity();
        DebugLog logger = DebugLog.getInstance();
        logger.setStdOut(capture);
        DebugType.General.setLogSeverity(LogSeverity.Warning);
        long blockedBefore = guardCounter(guard, "blocked");
        long suppressedBefore = guardCounter(guard, "suppressed");
        try {
            setField(guard, null, "windowStartNs", System.nanoTime());
            setField(guard, null, "windowCount", 0);
            for (int i = 0; i < 4; i++) {
                expect("限頻內外的異常請求皆安靜拒絕",
                        invokeDirect(entry, RELIABLE, truncated(0)) == null);
            }
            expect("Warning 等級仍有紀錄，四次拒絕只輸出三行",
                    capture.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
                            .filter(s -> s.contains("[AnimalUpdateGuard]")).count() == 3);
            expect("被限頻的請求仍計入拒絕總數",
                    guardCounter(guard, "blocked") == blockedBefore + 4
                            && guardCounter(guard, "suppressed") == suppressedBefore + 1);
            setField(guard, null, "windowStartNs", System.nanoTime() - 60_000_000_000L);
            expect("下一個時間窗重新開放紀錄", invokeDirect(entry, RELIABLE, truncated(0)) == null
                    && capture.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
                            .filter(s -> s.contains("[AnimalUpdateGuard]")).count() == 4);

            RuntimeException logFailure = new IllegalStateException("log-output-failure");
            logger.setStdOut(new OutputStream() {
                @Override
                public void write(int value) {
                    throw logFailure;
                }
            });
            long errorsBefore = guardCounter(guard, "logErrors");
            int delegatedBefore = delegations(RELIABLE);
            IsoHutch protectedHutch = hutch();
            IsoAnimal protectedAnimal = hutchAnimal(AnimalInstanceManager.getInstance(),
                    (short) 108, protectedHutch, 1);
            expect("真 logger 輸出故障不穿出拒絕路徑",
                    invokeDirect(entry, RELIABLE, wire(new short[]{108}, NONE, 0)) == null);
            expect("紀錄故障仍保護動物且不委派原版",
                    protectedHutch.animalInside.get(1) == protectedAnimal
                            && delegations(RELIABLE) == delegatedBefore
                            && guardCounter(guard, "logErrors") == errorsBefore + 1);
            expect("紀錄故障仍占本窗配額", guardCounter(guard, "windowCount") == 2);
        } finally {
            logger.setStdOut(System.out);
            DebugType.General.setLogSeverity(previousSeverity);
        }
    }

    private static long guardCounter(Class<?> guard, String name) throws ReflectiveOperationException {
        Field field = guard.getDeclaredField(name);
        field.setAccessible(true);
        return ((Number) field.get(null)).longValue();
    }

    // ── wire 組裝 ─────────────────────────────────────────────────────────

    /**
     * 真 client wire：暫時切成 client 模式，由原版 {@code AnimalUpdatePacket.write} 寫出
     * （server 模式會走逐 ID 附資料的 S2C 分支，故必須切）。寫出的形狀就是
     * int 刪除數（client 端恆 0——client 沒有任何 deleted 的寫入者）＋int requested 數＋short ID。
     */
    private static ByteBuffer clientWire(short... ids) {
        AnimalUpdateReliablePacket packet = new AnimalUpdateReliablePacket();
        packet.getRequested().clear();
        for (short id : ids) {
            packet.getRequested().add(id);
        }
        ByteBuffer bb = ByteBuffer.allocate(64);
        boolean server = GameServer.server;
        GameServer.server = false;
        GameClient.client = true;
        try {
            packet.write(new ByteBufferWriter(bb));
        } finally {
            GameClient.client = false;
            GameServer.server = server;
        }
        bb.flip();
        return bb;
    }

    /** 計數與內容一致的異常資料夾具，可另附尾端資料。 */
    private static ByteBuffer wire(short[] deletions, short[] requested, int extraBytes) {
        ByteBuffer bb = ByteBuffer.allocate(8 + (deletions.length + requested.length) * 2 + extraBytes);
        bb.putInt(deletions.length);
        for (short id : deletions) {
            bb.putShort(id);
        }
        bb.putInt(requested.length);
        for (short id : requested) {
            bb.putShort(id);
        }
        return pad(bb, extraBytes);
    }

    /** 表頭計數寫死（負數／溢位／截斷／尾巴），刪除 ID 一律不寫。 */
    private static ByteBuffer header(int deletionCount, int requestedCount, int idCount, int extraBytes) {
        ByteBuffer bb = ByteBuffer.allocate(8 + idCount * 2 + extraBytes);
        bb.putInt(deletionCount);
        bb.putInt(requestedCount);
        for (int i = 0; i < idCount; i++) {
            bb.putShort((short) (300 + i));
        }
        return pad(bb, extraBytes);
    }

    private static ByteBuffer pad(ByteBuffer bb, int extraBytes) {
        for (int i = 0; i < extraBytes; i++) {
            bb.put((byte) 0x5A);
        }
        bb.flip();
        return bb;
    }

    /** 不足兩個 int 的表頭。 */
    private static ByteBuffer truncated(int bytes) {
        ByteBuffer bb = ByteBuffer.allocate(bytes);
        for (int i = 0; i < bytes; i++) {
            bb.put((byte) 0);
        }
        bb.flip();
        return bb;
    }

    /**
     * 以 LITTLE_ENDIAN 組出的合法 request：原版 {@code ByteBufferReader} 直接委派
     * {@code bb.getInt/getShort}，所以整包（表頭與 ID）都是同一個 order。守衛若硬當
     * BIG_ENDIAN 讀就會看到荒謬的 count 而誤拒；若把 order 改掉，後續原版 parse 會解錯 ID。
     */
    private static ByteBuffer littleEndianWire(short... ids) {
        ByteBuffer bb = ByteBuffer.allocate(8 + ids.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0);
        bb.putInt(ids.length);
        for (short id : ids) {
            bb.putShort(id);
        }
        bb.flip();
        return bb;
    }

    /**
     * capacity 16 內寫好「刪除 0／requested 1／ID 205」再接 6 個舊位元組，position=0、
     * limit 由呼叫端指定：守衛必須以 limit 為界（10＝剛好吻合應放行；8＝ID 在界外應拒絕；
     * 4＝表頭不全應拒絕），不能拿 capacity 或界外殘留資料當數據。
     */
    private static ByteBuffer staleTail(int visible) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putInt(0);
        bb.putInt(1);
        bb.putShort((short) 205);
        for (int i = 0; i < 6; i++) {
            bb.put((byte) 0x5A);
        }
        bb.position(0);
        bb.limit(visible);
        return bb;
    }

    /** payload 前面墊 3 個位元組、position 停在 3：守衛必須以當前 position 為基準讀。 */
    private static ByteBuffer offset(ByteBuffer src) {
        ByteBuffer bb = ByteBuffer.allocate(src.remaining() + 3);
        bb.put((byte) 0x7F).put((byte) 0x7F).put((byte) 0x7F);
        bb.put(src.duplicate());
        bb.flip();
        bb.position(3);
        return bb;
    }

    private static ByteBuffer direct(ByteBuffer src) {
        ByteBuffer bb = ByteBuffer.allocateDirect(src.remaining());
        bb.put(src.duplicate());
        bb.flip();
        return bb;
    }

    private static ByteBuffer readOnly(ByteBuffer src) {
        return src.asReadOnlyBuffer();
    }

    // ── 觀測點 ────────────────────────────────────────────────────────────

    private static int delegations(PacketTypes.PacketType type) {
        return conn.delegated.getOrDefault(type, 0);
    }

    private static Set<Short> requestTable() {
        HashSet<Short> live = requests.get(GUID);
        return live == null ? null : new HashSet<>(live);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, HashSet<Short>> requestsMap() throws Exception {
        Field f = AnimalSynchronizationManager.class.getDeclaredField("requests");
        f.setAccessible(true);
        return (Map<Long, HashSet<Short>>) f.get(null);
    }

    private static Set<Short> setOf(short... ids) {
        HashSet<Short> set = new HashSet<>();
        for (short id : ids) {
            set.add(id);
        }
        return set;
    }

    // ── 夾具 ──────────────────────────────────────────────────────────────

    /**
     * 本連線具 LoginOnServer capability；在授權後、解析前的限流檢查處計數。
     * packet cache 維持每連線、每型別一個真封包實例，其餘解析與狀態處理不替換。
     */
    private static final class Conn extends UdpConnection {
        Map<PacketTypes.PacketType, INetworkPacket> pooled;
        Map<PacketTypes.PacketType, Integer> delegated;
        RuntimeException delegationFailure;

        /** 永不執行：實例一律以 Unsafe 配置，只為了讓子類別能通過編譯。 */
        private Conn() {
            super(null, 0L, 0);
        }

        @Override
        public INetworkPacket getPacket(PacketTypes.PacketType packetType) {
            return this.pooled.get(packetType);
        }

        @Override
        public boolean isLimitExceeded(PacketTypes.PacketType packetType) {
            this.delegated.merge(packetType, 1, Integer::sum);
            if (delegationFailure != null) {
                throw delegationFailure;
            }
            return false;
        }
    }

    private static Conn connection(long guid) throws Exception {
        Conn c = alloc(Conn.class);
        c.pooled = new HashMap<>();
        c.delegated = new HashMap<>();
        setField(UdpConnection.class, c, "connectedGuid", guid);
        // userName 非 null 才能通過 mainLoopDealWithNetData 的 pre-Login 型別白名單
        setField(UdpConnection.class, c, "userName", "mdc-test");
        Role role = new Role("mdc-test");
        role.addCapability(Capability.LoginOnServer);
        setField(UdpConnection.class, c, "role", role);
        // fullyConnected 留 false：萬一真有例外落進 vanilla 的 catch，AntiCheat.act 會早退
        return c;
    }

    private static IsoHutch hutch() throws Exception {
        IsoHutch h = alloc(IsoHutch.class);
        h.animalInside = new HashMap<>();
        setField(IsoHutch.class, h, "nestBoxes", new HashMap<Integer, IsoHutch.NestBox>());
        return h;
    }

    /** 掛在雞舍格位上的動物：parse 的刪除分支會讀 getHutch()／getData().getHutchPosition()。 */
    private static IsoAnimal hutchAnimal(AnimalInstanceManager mgr, short onlineID, IsoHutch h,
            int hutchPosition) throws Exception {
        IsoAnimal animal = animal(mgr, onlineID, h);
        setField(AnimalData.class, animal.getData(), "hutchPosition", hutchPosition);
        animal.nestBox = -1;
        h.animalInside.put(hutchPosition, animal);
        return animal;
    }

    /** 掛在巢箱上的動物：hutchPosition=-1 時 parse 走 getNestBox(index).animal = null 那條。 */
    @SuppressWarnings("unchecked")
    private static IsoAnimal nestBoxAnimal(AnimalInstanceManager mgr, short onlineID, IsoHutch h,
            int index) throws Exception {
        IsoAnimal animal = animal(mgr, onlineID, h);
        setField(AnimalData.class, animal.getData(), "hutchPosition", -1);
        animal.nestBox = index;
        IsoHutch.NestBox box = alloc(IsoHutch.NestBox.class);
        box.animal = animal;
        Field f = IsoHutch.class.getDeclaredField("nestBoxes");
        f.setAccessible(true);
        ((Map<Integer, IsoHutch.NestBox>) f.get(h)).put(index, box);
        return animal;
    }

    private static IsoAnimal animal(AnimalInstanceManager mgr, short onlineID, IsoHutch h)
            throws Exception {
        IsoAnimal animal = alloc(IsoAnimal.class);
        setField(IsoAnimal.class, animal, "data", alloc(AnimalData.class));
        animal.hutch = h;
        animal.onlineId = onlineID;
        // 直接掛進 IsoObjectID，繞過 AnimalInstanceManager.add()（會拉 setOnlineID 的額外依賴）
        mgr.getAnimals().put(onlineID, animal);
        return animal;
    }

    // ── 反射工具 ──────────────────────────────────────────────────────────

    private static void setField(Class<?> declaring, Object target, String name, Object value)
            throws Exception {
        Field f = declaring.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "aug pass  " : "aug FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalUpdateGuardTest() {}
}
