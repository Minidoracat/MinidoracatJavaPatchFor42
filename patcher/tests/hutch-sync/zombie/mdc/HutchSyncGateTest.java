package zombie.mdc;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import zombie.characters.CheatType;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.PlayerCheats;
import zombie.characters.animals.AnimalAllele;
import zombie.characters.animals.AnimalGene;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.inventory.types.Food;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.Vector3;
import zombie.iso.objects.IsoHutch;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.util.list.PZArrayList;
import zombie.vehicles.BaseVehicle;

/**
 * 雞舍自發 sync 接收者過濾（W26）行為驗證。argv：{@code enforce}（預設出貨）／
 * {@code observe}／{@code off}；{@code MODE} 是 static final，三組態各跑獨立 JVM 並自驗
 * argv 與實際 MODE 相符——property 名稱打錯會炸在測試裡，不會默默把 enforce 跑三遍假綠。
 *
 * <p><b>走真序列化，只在 JNI 邊界收手</b>：{@link CaptureConnection} 是真
 * {@link UdpConnection} 的子類，只覆寫 {@code startPacket}／{@code getBufferPosition}／
 * {@code endPacket} 三個 buffer 端點，內容仍由真的 {@link ByteBufferWriter}、真的
 * {@code PacketTypes.PacketType.SyncIsoObject.doPacket/send} 與真的
 * {@link IsoHutch#syncIsoObjectSend} 寫出。vanilla 對照直接呼叫 {@link IsoHutch#sync()}
 * （原版無條件廣播），因此「過濾後送出的封包」可與原版逐位元比對，並用真
 * {@link ByteBufferReader} 解回收件人實際讀到的狀態。
 *
 * <p>所有遊戲物件以 {@code Unsafe.allocateInstance} 取得而不跑建構子（{@code UdpConnection}
 * 的 {@code PacketsCache} 建構子會拉進整條 {@code PacketTypes} → {@code AntiCheat}／
 * {@code ServerOptions} 靜態鏈），故欄位由測試自行補齊——特別是
 * {@code IsoGameCharacter.health}（欄位初始值 1.0F 不會執行，留 0 會讓 {@code isDead()}
 * 為真而整條走 passthrough）與 {@code cheats}（{@code isNoClip()} 會解參考）。
 * {@link RandStandard#init()} 先行，理由同 W14／W17。
 *
 * <p><b>執行順序有意義</b>：teleport 簿記故障會讓 helper 全域停用過濾，故該段永遠排在最後。
 */
public final class HutchSyncGateTest {

    private static int failed;

    /** off 模式下 {@code EXEMPT} 為 null，{@code shouldSend} 不可直呼（只驗端到端行為）。 */
    private static final boolean JUDGE = HutchSyncGate.MODE != HutchSyncGate.OFF;
    /** 只有 enforce 會真的少送。 */
    private static final boolean FILTERS = HutchSyncGate.MODE == HutchSyncGate.ENFORCE;

    private static List<UdpConnection> connections;
    private static IsoGridSquare playerSquare;

    public static void main(String[] args) throws Exception {
        RandStandard.INSTANCE.init();
        GameServer.server = true;

        String want = args.length > 0 ? args[0] : "enforce";
        int wantMode = switch (want) {
            case "off" -> HutchSyncGate.OFF;
            case "observe" -> HutchSyncGate.OBSERVE;
            default -> HutchSyncGate.ENFORCE;
        };
        expect("自驗：argv=" + want + " 與 MODE 相符（MODE=" + HutchSyncGate.MODE + "）",
                HutchSyncGate.MODE == wantMode);

        UdpEngine engine = alloc(UdpEngine.class);
        connections = new ArrayList<>();
        setDeclared(UdpEngine.class, engine, "connections", connections);
        GameServer.udpEngine = engine;
        playerSquare = alloc(IsoGridSquare.class);

        testWindowGeometry();
        testPositionUnion();
        testSplitScreen();
        testUntrustedWidth();
        testUncertainConnection();
        testVehicleAndNoClip();
        testTeleportExemption();
        testMoveInDeliversCurrentState();
        testHotSaveWithoutRecipients();
        testInvalidObjectAndVanillaPaths();
        testEggSerialization();
        testSerializationAndSendFailure();
        // 全域毒化：必須最後（之後所有 syncUpdate 都退回原版廣播）
        testTeleportBookkeepingFault();

        if (failed > 0) {
            System.out.println("hutch-sync FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("hutch-sync OK  mode=" + want);
    }

    // ---------------------------------------------------------------- 幾何

    /**
     * 收件窗是整個 chunk-grid 寬度 {@code width*8}（不是 W13 的共同半寬下界）。
     * 上界含等號；{@code Math.nextUp} 的最小正 float 回歸必須落在窗外。
     * 以原點為 hutch 座標，避免世界座標的 ULP 吞掉半徑自身的下一個可表示值。
     */
    private static void testWindowGeometry() throws Exception {
        if (!JUDGE) {
            return;
        }
        for (int width : new int[] {13, 15, 17, 19}) {
            float radius = width * 8.0f;
            float outside = Math.nextUp(radius);
            CaptureConnection c = conn(width);
            place(c, 0, 0.0f, 0.0f);

            float[][] inside = {
                {radius - 1.0f, 0.0f}, {radius, 0.0f}, {-radius, 0.0f},
                {0.0f, radius}, {0.0f, -radius}, {radius, radius},
            };
            for (float[] at : inside) {
                move(c, 0, at[0], at[1]);
                expect("width=" + width + " 窗內 (" + at[0] + "," + at[1] + ")：送",
                        HutchSyncGate.shouldSend(c, 0.0f, 0.0f));
            }

            float[][] out = {{outside, 0.0f}, {-outside, 0.0f}, {0.0f, outside}, {outside, radius}};
            for (float[] at : out) {
                move(c, 0, at[0], at[1]);
                expect("width=" + width + " 窗外 (" + at[0] + "," + at[1] + ")：不送",
                        !HutchSyncGate.shouldSend(c, 0.0f, 0.0f));
            }
        }
    }

    /**
     * 收件範圍是「原版 relevancy ∪ server 端實體位置」：預測位置與實體位置任一在窗內都要送。
     * 少了聯集，剛跨格／預測落後的玩家會看不到雞舍變化。
     */
    private static void testPositionUnion() throws Exception {
        if (!JUDGE) {
            return;
        }
        CaptureConnection staleRelevant = conn(15);
        place(staleRelevant, 0, 4.0f, 4.0f);
        staleRelevant.releventPos[0] = new Vector3(9000.0f, 9000.0f, 0.0f);
        expect("releventPos 過期在遠方、實體位置貼著雞舍：仍送",
                HutchSyncGate.shouldSend(staleRelevant, 0.0f, 0.0f));

        CaptureConnection staleActual = conn(15);
        IsoPlayer p2 = place(staleActual, 0, 9000.0f, 9000.0f);
        staleActual.releventPos[0] = new Vector3(4.0f, 4.0f, 0.0f);
        expect("實體位置在遠方、releventPos 在窗內（原版判定）：仍送",
                HutchSyncGate.shouldSend(staleActual, 0.0f, 0.0f));
        expect("聯集前提：該玩家實體位置確實在窗外", Math.abs(p2.getX()) > 15 * 8.0f);
    }

    /** 同一條連線的 4 個 slot 取聯集：一人在窗內就整條連線都要送。 */
    private static void testSplitScreen() throws Exception {
        Hutch hutch = hutch(0, 0, new int[] {0});
        byte[] vanilla = vanillaWire(hutch);

        CaptureConnection couch = conn(15);
        place(couch, 0, 9000.0f, 9000.0f);
        place(couch, 1, 6.0f, 6.0f);
        if (JUDGE) {
            expect("split-screen：slot1 在窗內 ⇒ 整條連線送",
                    HutchSyncGate.shouldSend(couch, 0.0f, 0.0f));
        }
        sync(hutch, couch);
        expectDelivered("split-screen：一人在窗內", couch, true, vanilla);

        move(couch, 1, 9100.0f, 9100.0f);
        if (JUDGE) {
            expect("split-screen：兩人都在窗外 ⇒ 不送",
                    !HutchSyncGate.shouldSend(couch, 0.0f, 0.0f));
        }
        sync(hutch, couch);
        expectDelivered("split-screen：兩人都在窗外", couch, false, vanilla);
    }

    /**
     * chunkGridWidth 只在可信奇數 13/15/17/19 才敢夾：0（握手前）、偶數與 clamp 邊界都還原不出
     * client 真實載入矩形，一律直通。
     */
    private static void testUntrustedWidth() throws Exception {
        if (!JUDGE) {
            return;
        }
        for (int width : new int[] {0, 11, 12, 14, 16, 18, 20, 21}) {
            CaptureConnection c = conn(width);
            place(c, 0, 9000.0f, 9000.0f);
            expect("不可信 chunkGridWidth=" + width + "：遠端玩家仍直通",
                    HutchSyncGate.shouldSend(c, 0.0f, 0.0f));
        }
    }

    /** 任何「我不確定這個 client 載入了什麼」的訊號都必須直通，不得當成安全空間。 */
    private static void testUncertainConnection() throws Exception {
        if (!JUDGE) {
            return;
        }
        CaptureConnection baseline = conn(15);
        place(baseline, 0, 9000.0f, 9000.0f);
        expect("對照組：完整且遠離的連線確實會被過濾",
                !HutchSyncGate.shouldSend(baseline, 0.0f, 0.0f));

        CaptureConnection notReady = farConn();
        setDeclared(UdpConnection.class, notReady, "fullyConnected", Boolean.FALSE);
        expect("連線尚未 fully-connected：直通", HutchSyncGate.shouldSend(notReady, 0.0f, 0.0f));

        CaptureConnection shortArray = farConn();
        shortArray.players = Arrays.copyOf(shortArray.players, 2);
        expect("players 陣列長度非 4（結構與預期不符）：直通",
                HutchSyncGate.shouldSend(shortArray, 0.0f, 0.0f));

        CaptureConnection coop = farConn();
        coop.connectArea[0] = new Vector3(0.0f, 0.0f, 40.0f);
        expect("connectArea 有值（coop 判定矩形）：直通", HutchSyncGate.shouldSend(coop, 0.0f, 0.0f));

        CaptureConnection dead = farConn();
        setAny(dead.players[0], "health", 0.0f);
        expect("玩家已死（重生位置不可預測）：直通", HutchSyncGate.shouldSend(dead, 0.0f, 0.0f));

        CaptureConnection offGrid = farConn();
        setDeclared(IsoMovingObject.class, offGrid.players[0], "current", null);
        expect("玩家不在任何格上（載入／轉場中）：直通", HutchSyncGate.shouldSend(offGrid, 0.0f, 0.0f));

        CaptureConnection nanActual = farConn();
        setDeclared(IsoMovingObject.class, nanActual.players[0], "x", Float.NaN);
        expect("玩家實體座標 NaN：直通", HutchSyncGate.shouldSend(nanActual, 0.0f, 0.0f));

        CaptureConnection nanRelevant = farConn();
        nanRelevant.releventPos[0] = new Vector3(Float.NaN, 9000.0f, 0.0f);
        expect("releventPos NaN：直通", HutchSyncGate.shouldSend(nanRelevant, 0.0f, 0.0f));

        CaptureConnection nullRelevant = farConn();
        nullRelevant.releventPos[0] = null;
        expect("有玩家但沒有 releventPos：直通", HutchSyncGate.shouldSend(nullRelevant, 0.0f, 0.0f));

        CaptureConnection switching = conn(15);
        place(switching, 1, 9000.0f, 9000.0f);
        switching.releventPos[0] = new Vector3(9000.0f, 9000.0f, 0.0f);
        expect("slot 有 releventPos 但 player 尚未掛上（換角／加入中）：直通",
                HutchSyncGate.shouldSend(switching, 0.0f, 0.0f));

        CaptureConnection empty = conn(15);
        expect("連線上一個玩家都沒有：直通（不視為安全空間）",
                HutchSyncGate.shouldSend(empty, 0.0f, 0.0f));
    }


    // ------------------------------------------------------- 直通身分（可恢復）

    /**
     * 載具與 noclip 都是「當下」直通，不是終身豁免：client 的載入中心會沿行進方向前移／
     * noclip 可瞬移穿牆，但兩者都會結束，結束後整窗 {@code width*8} 的裕度足以恢復過濾。
     */
    private static void testVehicleAndNoClip() throws Exception {
        Hutch hutch = hutch(0, 0, new int[] {0});
        byte[] vanilla = vanillaWire(hutch);

        CaptureConnection driving = farConn();
        IsoPlayer driver = driving.players[0];
        if (JUDGE) {
            expect("載具：上車前遠端被過濾", !HutchSyncGate.shouldSend(driving, 0.0f, 0.0f));
        }
        setDeclared(IsoGameCharacter.class, driver, "vehicle", alloc(BaseVehicle.class));
        if (JUDGE) {
            expect("載具：車內直通", HutchSyncGate.shouldSend(driving, 0.0f, 0.0f));
        }
        sync(hutch, driving);
        expectDelivered("載具：車內遠端照送", driving, true, vanilla);

        setDeclared(IsoGameCharacter.class, driver, "vehicle", null);
        if (JUDGE) {
            expect("載具：下車後恢復過濾（非終身豁免）",
                    !HutchSyncGate.shouldSend(driving, 0.0f, 0.0f));
        }
        sync(hutch, driving);
        expectDelivered("載具：下車後遠端", driving, false, vanilla);

        CaptureConnection ghost = farConn();
        IsoPlayer admin = ghost.players[0];
        admin.cheats.set(CheatType.NO_CLIP, true);
        expect("noclip 前提：旗標真的設起來了（isCheatAllowed 需要 GameServer.server）",
                admin.isNoClip());
        if (JUDGE) {
            expect("noclip：開啟時直通", HutchSyncGate.shouldSend(ghost, 0.0f, 0.0f));
        }
        sync(hutch, ghost);
        expectDelivered("noclip：開啟時遠端照送", ghost, true, vanilla);

        admin.cheats.set(CheatType.NO_CLIP, false);
        if (JUDGE) {
            expect("noclip：關閉後恢復過濾", !HutchSyncGate.shouldSend(ghost, 0.0f, 0.0f));
        }
        sync(hutch, ghost);
        expectDelivered("noclip：關閉後遠端", ghost, false, vanilla);
    }

    /**
     * server 送出 teleport 時，目標玩家的新位置還沒回報（players/releventPos 都還是舊座標），
     * 卻可能落在雞舍旁邊——所以 write 當下就把該 <b>角色實例</b> 記成豁免，且不靠時間上限自清
     * （server 沒有 teleport ACK，猜錯就是玩家到場看到停格的雞舍）。
     */
    private static void testTeleportExemption() throws Exception {
        // 雞舍在原點＝傳送目的地附近；玩家的已知座標仍在舊位置
        Hutch destination = hutch(0, 0, new int[] {0});
        byte[] vanilla = vanillaWire(destination);

        CaptureConnection warping = conn(15);
        IsoPlayer traveller = place(warping, 0, 5000.0f, 5000.0f);
        if (JUDGE) {
            expect("teleport 前：玩家舊座標遠離目的地 ⇒ 被過濾",
                    !HutchSyncGate.shouldSend(warping, 0.0f, 0.0f));
        }

        PlayerID id = playerId(traveller, (short) 7);
        byte[] written = capture(out -> HutchSyncGate.writeTeleportPlayer(id, out));
        byte[] vanillaId = capture(id::write);
        expect("teleport wire：改道後與原版 PlayerID.write 逐位元相同",
                Arrays.equals(written, vanillaId));

        if (JUDGE) {
            expect("teleport 後：新位置尚未回報也直通",
                    HutchSyncGate.shouldSend(warping, 0.0f, 0.0f));
        }
        sync(destination, warping);
        expectDelivered("teleport 後：目的地雞舍照送", warping, true, vanilla);

        move(warping, 0, 7000.0f, 7000.0f);
        if (JUDGE) {
            expect("teleport 豁免綁在角色實例上，位置再變也不失效",
                    HutchSyncGate.shouldSend(warping, 0.0f, 0.0f));
        }
        sync(destination, warping);
        expectDelivered("teleport 豁免：位置再變仍送", warping, true, vanilla);

        // 另一條連線的玩家沒被 teleport，不得跟著沾光
        CaptureConnection bystander = farConn();
        if (JUDGE) {
            expect("豁免不外溢到其他角色", !HutchSyncGate.shouldSend(bystander, 0.0f, 0.0f));
        }

        PlayerID orphan = new PlayerID();
        orphan.setID((short) 9);
        byte[] orphanWire = capture(out -> HutchSyncGate.writeTeleportPlayer(orphan, out));
        expect("PlayerID 尚未解析出角色：不記豁免、不炸、照樣寫入",
                Arrays.equals(orphanWire, capture(orphan::write)));
    }

    /**
     * 狀態轉換：遠端玩家在被過濾期間雞舍改變狀態，玩家移進窗內後拿到的必須是<b>當下</b>狀態，
     * 而不是過濾前的舊快照。
     */
    private static void testMoveInDeliversCurrentState() throws Exception {
        Hutch hutch = hutch(120, 340, new int[] {0});
        CaptureConnection c = conn(15);
        place(c, 0, 9000.0f, 9000.0f);

        byte[] before = vanillaWire(hutch);
        sync(hutch, c);
        expectDelivered("移入前：遠端", c, false, before);

        setDeclared(IsoHutch.class, hutch.object, "open", Boolean.TRUE);
        setDeclared(IsoHutch.class, hutch.object, "openEggHatch", Boolean.TRUE);
        setDeclared(IsoHutch.class, hutch.object, "hutchDirt", 42.5f);
        setDeclared(IsoHutch.class, hutch.object, "nestBoxDirt", 7.25f);
        move(c, 0, 124.0f, 343.0f);

        byte[] after = vanillaWire(hutch);
        expect("狀態確實有變（新舊 vanilla wire 不同）", !Arrays.equals(before, after));

        sync(hutch, c);
        expect("移入後：收到 1 個封包", c.packets.size() == 1);
        if (c.packets.size() == 1) {
            expect("移入後：wire 與原版相同", Arrays.equals(c.packets.get(0), after));
            Wire w = decode(c.packets.get(0));
            expect("收件人解出的是移入當下的狀態（兩道門與兩個 dirt 各自對位）",
                    w.open && w.openEggHatch && w.hutchDirt == 42.5f && w.nestBoxDirt == 7.25f);
        }
    }

    /** 沒有任何收件人（全被過濾／連線清單為空）時，熱存檔仍必須被標記——否則改動只活在記憶體。 */
    private static void testHotSaveWithoutRecipients() throws Exception {
        Hutch hutch = hutch(0, 0, new int[] {0});

        CaptureConnection far = farConn();
        sync(hutch, far);
        expect("全數過濾時（mode=" + HutchSyncGate.MODE + "）封包數符合模式",
                far.packets.size() == (FILTERS ? 0 : 1));
        expect("全數過濾時仍標記 hot-save", hutch.chunk.requiresHotSave);

        // 連線清單為空（伺服器沒人在線）：原版也會走到 flagForHotSave，過濾不得改變這點
        sync(hutch);
        expect("連線清單為空時仍標記 hot-save", hutch.chunk.requiresHotSave);
    }

    /**
     * 改道只接管「本體 IsoHutch 且物件有效」的自發 sync；其餘全部原封不動交回 vanilla：
     * 無效物件維持原版診斷且<b>不</b>標記 hot-save，單機不送封包但仍存檔，子類別走原版全量廣播。
     */
    private static void testInvalidObjectAndVanillaPaths() throws Exception {
        Hutch orphan = hutch(0, 0, new int[] {0});
        IsoGridSquare square = orphan.square;
        orphan.object.square = null;
        CaptureConnection near = nearConn();
        sync(orphan, near);
        expect("square 為 null：不送封包", near.packets.isEmpty());
        expect("square 為 null：不標記 hot-save（原版也不會）", !orphan.chunk.requiresHotSave);
        orphan.object.square = square;

        Hutch detached = hutch(0, 0, new int[] {0});
        setDeclared(IsoGridSquare.class, detached.square, "objects",
                new PZArrayList<IsoObject>(IsoObject.class, 2));
        expect("前提：物件已不在格上（objectIndex == -1）",
                detached.object.getObjectIndex() == -1);
        CaptureConnection near2 = nearConn();
        sync(detached, near2);
        expect("objectIndex == -1：不送封包", near2.packets.isEmpty());
        expect("objectIndex == -1：不標記 hot-save", !detached.chunk.requiresHotSave);

        Hutch single = hutch(0, 0, new int[] {0});
        CaptureConnection spectator = farConn();
        GameServer.server = false;
        try {
            sync(single, spectator);
        } finally {
            GameServer.server = true;
        }
        expect("單機（GameServer.server=false）：不送封包", spectator.packets.isEmpty());
        expect("單機：仍標記 hot-save", single.chunk.requiresHotSave);

        Hutch derived = hutch(HutchSubclass.class, 0, 0, new int[] {0});
        CaptureConnection farFromDerived = farConn();
        sync(derived, farFromDerived);
        expect("IsoHutch 子類別：一律走原版全量廣播（不代管未知覆寫）",
                farFromDerived.packets.size() == 1);
        expect("IsoHutch 子類別：仍標記 hot-save", derived.chunk.requiresHotSave);
    }

    /**
     * 蛋是雞舍 payload 裡唯一的巢狀序列化（真 {@code InventoryItem.saveWithSize} 長度前綴）。
     * 用中性合成 {@link Food}（未跑建構子、只帶測試自填欄位，不引用任何正式服資料）驗證：
     * 過濾後送出的 bytes 與原版逐位元相同、長度框架可被完整消費、蛋的內容真的上線。
     */
    private static void testEggSerialization() throws Exception {
        Hutch hutch = hutch(200, 300, new int[] {2, 1, 0});
        byte[] vanilla = vanillaWire(hutch);

        CaptureConnection near = nearConn(200.0f, 300.0f);
        sync(hutch, near);
        expect("含蛋雞舍：收到 1 個封包", near.packets.size() == 1);
        if (near.packets.size() != 1) {
            return;
        }
        byte[] wire = near.packets.get(0);
        expect("含蛋雞舍：wire 與原版逐位元相同", Arrays.equals(wire, vanilla));

        Wire w = decode(wire);
        int[] counts = w.eggCounts.clone();
        Arrays.sort(counts);
        expect("收件人解出 3 個巢箱", w.eggCounts.length == 3);
        expect("收件人解出的蛋數＝{0,1,2}", Arrays.equals(counts, new int[] {0, 1, 2}));
        expect("每顆蛋的長度前綴都能被完整消費，封包尾巴為 0", w.trailing == 0);

        Food first = firstEgg(hutch.object);
        setAny(first, "age", 777.25f);
        sync(hutch, near);
        expect("改蛋內容後：仍是 1 個封包", near.packets.size() == 1);
        if (near.packets.size() == 1) {
            byte[] mutated = near.packets.get(0);
            expect("蛋的內容真的走上線（wire 改變）", !Arrays.equals(mutated, wire));
            expect("只換一個 float：封包長度不變", mutated.length == wire.length);
        }
    }

    /**
     * 序列化／送包例外必須以原例外外逃，hot-save 是否執行也須保持原版的中斷語意；
     * hot-save 本身不是封包已送達的證據。
     */
    private static void testSerializationAndSendFailure() throws Exception {
        Hutch hutch = hutch(0, 0, new int[] {1, 0});
        CaptureConnection near = nearConn();

        IllegalStateException serialiseBoom = new IllegalStateException("<test> 蛋清單壞掉");
        ExplodingEggs exploding = new ExplodingEggs();
        exploding.failure = serialiseBoom;
        setDeclared(IsoHutch.NestBox.class, firstBox(hutch.object), "eggs", exploding);

        RuntimeException caught = runExpectingThrow(hutch, near);
        expect("序列化失敗：原例外原樣外逃", caught == serialiseBoom);
        expect("序列化失敗：不標記 hot-save", !hutch.chunk.requiresHotSave);
        expect("序列化失敗：沒有半成品封包被送出", near.packets.isEmpty());

        Hutch healthy = hutch(0, 0, new int[] {0});
        CaptureConnection failing = nearConn();
        IllegalStateException sendBoom = new IllegalStateException("<test> 送包失敗");
        failing.sendFailure = sendBoom;
        RuntimeException caught2 = runExpectingThrow(healthy, failing);
        failing.sendFailure = null;
        expect("送包失敗：原例外原樣外逃", caught2 == sendBoom);
        expect("送包失敗：不標記 hot-save", !healthy.chunk.requiresHotSave);
    }

    /**
     * <b>本檔最後一段</b>：teleport 簿記若拋 RuntimeException，代表豁免名單不可信，helper 必須
     * 全域退回原版廣播（寧可多送也不能讓轉場中的玩家看到停格雞舍），且絕不破壞已開始寫入的
     * teleport 封包。LinkageError 是手術／版本不合的訊號，維持 fail-fast 外逃。
     */
    private static void testTeleportBookkeepingFault() throws Exception {
        Hutch hutch = hutch(0, 0, new int[] {0});
        byte[] vanilla = vanillaWire(hutch);

        LinkageFailingPlayerID linkage = new LinkageFailingPlayerID();
        linkage.setID((short) 11);
        Error linkageError = null;
        byte[] linkageWire = null;
        try {
            linkageWire = capture(out -> HutchSyncGate.writeTeleportPlayer(linkage, out));
        } catch (LinkageError error) {
            linkageError = error;
        }
        if (JUDGE) {
            expect("LinkageError：fail-fast 外逃，不被吞掉", linkageError instanceof NoSuchMethodError);
        } else {
            expect("off：連簿記入口都不進，LinkageError 不會被觸發", linkageError == null);
            expect("off：teleport 仍原樣寫入",
                    linkageWire != null && Arrays.equals(linkageWire, capture(linkage::write)));
        }

        CaptureConnection stillFiltering = farConn();
        if (JUDGE) {
            expect("LinkageError 之後：過濾未被停用",
                    !HutchSyncGate.shouldSend(stillFiltering, 0.0f, 0.0f));
        }

        RuntimeFailingPlayerID faulty = new RuntimeFailingPlayerID();
        faulty.setID((short) 13);
        byte[] faultyWire = capture(out -> HutchSyncGate.writeTeleportPlayer(faulty, out));
        // 沒有 try/catch：writeTeleportPlayer 若讓 RuntimeException 外逃，這裡會直接以堆疊終止
        expect("簿記 RuntimeException：不外逃且 id 仍原樣寫入",
                Arrays.equals(faultyWire, capture(faulty::write)));

        CaptureConnection far = farConn();
        sync(hutch, far);
        expect("簿記故障後：遠端連線恢復收到封包（過濾已全域停用）", far.packets.size() == 1);
        if (far.packets.size() == 1) {
            expect("簿記故障後：wire 與原版逐位元相同", Arrays.equals(far.packets.get(0), vanilla));
        }
        expect("簿記故障後：仍標記 hot-save", hutch.chunk.requiresHotSave);
    }

    // ------------------------------------------------------------ 驅動與斷言

    /** 換上這批連線、清空熱存檔旗標，然後跑一次改道後的自發 sync。 */
    private static void sync(Hutch hutch, CaptureConnection... conns) {
        arm(conns);
        hutch.chunk.requiresHotSave = false;
        HutchSyncGate.syncUpdate(hutch.object);
    }

    private static RuntimeException runExpectingThrow(Hutch hutch, CaptureConnection... conns) {
        arm(conns);
        hutch.chunk.requiresHotSave = false;
        try {
            HutchSyncGate.syncUpdate(hutch.object);
        } catch (RuntimeException failure) {
            return failure;
        }
        expect("預期會拋出例外但沒有", false);
        return null;
    }

    private static void arm(CaptureConnection... conns) {
        connections.clear();
        for (CaptureConnection c : conns) {
            c.packets.clear();
            connections.add(c);
        }
    }

    /** 原版對照：{@link IsoHutch#sync()} 無條件廣播，取其 wire 作為逐位元基準。 */
    private static byte[] vanillaWire(Hutch hutch) throws Exception {
        CaptureConnection probe = nearConn();
        arm(probe);
        hutch.chunk.requiresHotSave = false;
        hutch.object.sync();
        expect("原版對照：sync() 確實送出 1 個封包", probe.packets.size() == 1);
        return probe.packets.isEmpty() ? new byte[0] : probe.packets.get(0);
    }

    private static void expectDelivered(String what, CaptureConnection c, boolean eligible,
            byte[] vanilla) {
        int wanted = FILTERS && !eligible ? 0 : 1;
        expect(what + "（mode=" + HutchSyncGate.MODE + "）：封包數 " + wanted,
                c.packets.size() == wanted);
        if (wanted == 1 && c.packets.size() == 1) {
            expect(what + "：送出的 wire 與原版逐位元相同",
                    Arrays.equals(c.packets.get(0), vanilla));
        }
    }

    // ------------------------------------------------------------ fixture

    /** 只覆寫 buffer 三端點的真連線：內容仍由真 writer／真 PacketType／真 hutch 寫出。 */
    static final class CaptureConnection extends UdpConnection {
        ByteBuffer buffer;
        ByteBufferWriter writer;
        ArrayList<byte[]> packets;
        RuntimeException sendFailure;

        /** 永不執行：實例一律以 Unsafe 配置，只為了讓子類別能通過編譯。 */
        private CaptureConnection() {
            super(null, 0L, 0);
        }

        @Override
        public ByteBufferWriter startPacket() {
            this.writer.clear();
            return this.writer;
        }

        @Override
        public int getBufferPosition() {
            return this.buffer.position();
        }

        @Override
        public void endPacket(int priority, int reliability, byte ordering) {
            int length = this.buffer.position();
            byte[] wire = new byte[length];
            this.buffer.position(0);
            this.buffer.get(wire);
            this.buffer.clear();
            if (this.sendFailure != null) {
                throw this.sendFailure;
            }
            this.packets.add(wire);
        }
    }

    /** 只為了驗「非本體類別一律交回 vanilla」；同樣永不執行建構子。 */
    public static final class HutchSubclass extends IsoHutch {
        private HutchSubclass() {
            super((IsoCell) null);
        }
    }

    /** 在真 {@code syncIsoObjectSend} 迴圈中途炸開，模擬序列化失敗。 */
    static final class ExplodingEggs extends ArrayList<Food> {
        private static final long serialVersionUID = 1L;
        RuntimeException failure;

        @Override
        public int size() {
            throw this.failure;
        }
    }

    static final class LinkageFailingPlayerID extends PlayerID {
        @Override
        public IsoPlayer getPlayer() {
            throw new NoSuchMethodError("<test> 模擬手術後簽名不合");
        }
    }

    static final class RuntimeFailingPlayerID extends PlayerID {
        @Override
        public IsoPlayer getPlayer() {
            throw new IllegalStateException("<test> 簿記入口壞掉");
        }
    }

    private static final class Hutch {
        IsoHutch object;
        IsoGridSquare square;
        IsoChunk chunk;
    }

    private static final class Wire {
        boolean open;
        boolean openEggHatch;
        float hutchDirt;
        float nestBoxDirt;
        int[] eggCounts;
        int trailing;
    }

    private static Hutch hutch(int x, int y, int[] eggsPerBox) throws Exception {
        return hutch(IsoHutch.class, x, y, eggsPerBox);
    }

    private static Hutch hutch(Class<? extends IsoHutch> type, int x, int y, int[] eggsPerBox)
            throws Exception {
        IsoHutch object = alloc(type);
        IsoGridSquare square = alloc(IsoGridSquare.class);
        square.x = x;
        square.y = y;
        square.z = 0;
        PZArrayList<IsoObject> objects = new PZArrayList<>(IsoObject.class, 2);
        objects.add(object);
        setDeclared(IsoGridSquare.class, square, "objects", objects);
        IsoChunk chunk = alloc(IsoChunk.class);
        square.chunk = chunk;
        object.square = square;

        HashMap<Integer, IsoHutch.NestBox> boxes = new HashMap<>();
        for (int i = 0; i < eggsPerBox.length; i++) {
            IsoHutch.NestBox box = alloc(IsoHutch.NestBox.class);
            ArrayList<Food> eggs = new ArrayList<>();
            for (int e = 0; e < eggsPerBox[i]; e++) {
                eggs.add(egg(1.5f + e));
            }
            setDeclared(IsoHutch.NestBox.class, box, "eggs", eggs);
            boxes.put(i, box);
        }
        setDeclared(IsoHutch.class, object, "nestBoxes", boxes);

        Hutch holder = new Hutch();
        holder.object = object;
        holder.square = square;
        holder.chunk = chunk;
        return holder;
    }

    /** 中性合成受精蛋：使用真 Food/AnimalGene 序列化，不依賴正式服資料或物品登錄表。 */
    private static Food egg(float age) throws Exception {
        Food food = alloc(Food.class);
        setAny(food, "age", age);
        food.setFertilized(true);
        food.setAnimalHatch("chick");
        food.setAnimalHatchBreed("leghorn");
        setAny(food, "timeToHatch", 504);
        setAny(food, "fertilizedTime", 12);
        HashMap<String, AnimalGene> genome = new HashMap<>();
        for (String name : new String[] {"fertility", "maxweight"}) {
            AnimalGene gene = new AnimalGene();
            gene.name = name;
            gene.allele1 = new AnimalAllele();
            gene.allele2 = new AnimalAllele();
            gene.allele1.name = gene.allele2.name = name;
            gene.allele1.currentValue = 0.8f;
            gene.allele2.currentValue = 1.2f;
            genome.put(name, gene);
        }
        setAny(food, "eggGenome", genome);
        return food;
    }

    @SuppressWarnings("unchecked")
    private static IsoHutch.NestBox firstBox(IsoHutch hutch) throws Exception {
        Field field = IsoHutch.class.getDeclaredField("nestBoxes");
        field.setAccessible(true);
        return ((HashMap<Integer, IsoHutch.NestBox>) field.get(hutch)).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Food firstEgg(IsoHutch hutch) throws Exception {
        Field field = IsoHutch.NestBox.class.getDeclaredField("eggs");
        field.setAccessible(true);
        return ((ArrayList<Food>) field.get(firstBox(hutch))).get(0);
    }

    private static CaptureConnection conn(int chunkGridWidth) throws Exception {
        CaptureConnection c = alloc(CaptureConnection.class);
        c.buffer = ByteBuffer.allocate(64 * 1024);
        c.writer = new ByteBufferWriter(c.buffer);
        c.packets = new ArrayList<>();
        c.players = new IsoPlayer[4];
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.setChunkGridWidth(chunkGridWidth);
        setDeclared(UdpConnection.class, c, "fullyConnected", Boolean.TRUE);
        return c;
    }

    /** 可信寬度、單一步行玩家、遠在窗外——enforce 下應被過濾的標準連線。 */
    private static CaptureConnection farConn() throws Exception {
        CaptureConnection c = conn(15);
        place(c, 0, 9000.0f, 9000.0f);
        return c;
    }

    private static CaptureConnection nearConn() throws Exception {
        return nearConn(0.0f, 0.0f);
    }

    private static CaptureConnection nearConn(float x, float y) throws Exception {
        CaptureConnection c = conn(15);
        place(c, 0, x, y);
        return c;
    }

    /** 步行、活著、站在格上、無作弊、無載具的最小玩家；同時填 releventPos。 */
    private static IsoPlayer place(CaptureConnection c, int slot, float x, float y)
            throws Exception {
        IsoPlayer player = alloc(IsoPlayer.class);
        setAny(player, "health", 1.0f);
        setDeclared(IsoMovingObject.class, player, "current", playerSquare);
        player.cheats = new PlayerCheats();
        c.players[slot] = player;
        move(c, slot, x, y);
        return player;
    }

    private static void move(CaptureConnection c, int slot, float x, float y) throws Exception {
        setDeclared(IsoMovingObject.class, c.players[slot], "x", x);
        setDeclared(IsoMovingObject.class, c.players[slot], "y", y);
        c.releventPos[slot] = new Vector3(x, y, 0.0f);
    }

    private static PlayerID playerId(IsoPlayer player, short onlineId) throws Exception {
        PlayerID id = new PlayerID();
        id.setID(onlineId);
        setAny(id, "player", player);
        return id;
    }

    private interface WriteOp {
        void run(ByteBufferWriter out);
    }

    private static byte[] capture(WriteOp op) {
        ByteBuffer scratch = ByteBuffer.allocate(64);
        op.run(new ByteBufferWriter(scratch));
        byte[] out = new byte[scratch.position()];
        scratch.position(0);
        scratch.get(out);
        return out;
    }

    /** 用真 reader 解回收件人讀到的東西；蛋只驗長度框架能被完整消費。 */
    private static Wire decode(byte[] packet) {
        ByteBuffer bb = ByteBuffer.wrap(packet);
        ByteBufferReader r = new ByteBufferReader(bb);
        Wire w = new Wire();
        r.getByte();                    // packet marker
        r.getShort();                   // packet ID
        r.getInt();                     // square x
        r.getInt();                     // square y
        r.getInt();                     // square z
        r.getByte();                    // object index
        r.getBoolean();                 // vanilla 固定 true
        r.getBoolean();                 // vanilla 固定 false
        w.open = r.getBoolean();
        w.openEggHatch = r.getBoolean();
        w.hutchDirt = r.getFloat();
        w.nestBoxDirt = r.getFloat();
        int boxes = r.getByte();
        w.eggCounts = new int[boxes];
        for (int i = 0; i < boxes; i++) {
            int eggs = r.getByte();
            w.eggCounts[i] = eggs;
            for (int e = 0; e < eggs; e++) {
                int size = r.getInt();
                r.position(r.position() + size);
            }
        }
        w.trailing = bb.remaining();
        return w;
    }

    // ------------------------------------------------------------ 反射工具

    private static void setDeclared(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setAny(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
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
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "hsg pass  " : "hsg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private HutchSyncGateTest() {}
}
