package zombie.network.packets;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.BodyLocationGroup;
import zombie.characters.WornItems.WornItems;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.network.fields.character.Player;
import zombie.scripting.objects.ItemBodyLocation;

/**
 * W103 穿戴同步驗證（MdcWornItemGuard）行為驗證。玩家的穿戴欄（真 {@code WornItems}）、背包與封包清單直接設好，走 classpath 上的真
 * {@code processServer}（dist 內手術後的，或 vanilla 模式的 jar 原版）與它呼叫的真 {@code process}。測試環境沒有物品腳本，原版替
 * 背包沒有的 ID 準備物品時會印「Couldn't find item 種類」：用這一行當原版處理了那一項的證據。
 * argv：{@code observe}（出貨組態）／{@code enforce}／{@code off}（kill switch）／{@code vanilla}（classpath 不含 dist，原版負對照）／
 * {@code logthrow}（enforce，日誌串流一寫就拋例外：照做、Error 照常外拋），自驗。
 */
public final class WornItemGuardTest {

    private static final String TAG = "[MinidoracatJavaPatch][WornItemGuard]";
    private static final String GUARD = "zombie.network.packets.MdcWornItemGuard";
    private static final String VANILLA_MISS = "Couldn't find item ";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final java.io.PrintStream OUT = System.out;

    private static int failed;
    private static boolean oom;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args.length > 0 ? args[0] : "observe";
        String packetFrom = SyncClothingPacket.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if ("vanilla".equals(mode)) {
            boolean guardAbsent;
            try {
                Class.forName(GUARD);
                guardAbsent = false;
            } catch (ClassNotFoundException e) {
                guardAbsent = true;
            }
            expect("自驗：vanilla＝封包類別來自 jar（" + packetFrom + "）、classpath 沒有 helper", guardAbsent && packetFrom.endsWith(".jar"));
        } else {
            int want = "observe".equals(mode) ? 1 : "off".equals(mode) ? 0 : 2;
            expect("自驗：argv=" + mode + " 與實際模式 " + guardInt("MODE") + " 相符、封包類別來自 dist（" + packetFrom + "）",
                    guardInt("MODE") == want && !packetFrom.endsWith(".jar"));
        }
        if ("logthrow".equals(mode)) {
            logThrow();
            return;
        }
        boolean observe = "observe".equals(mode);
        boolean enforce = "enforce".equals(mode);
        ByteArrayOutputStream log = captureLog();

        World w = world();
        w.packet.processServer(null, w.conn);

        // 背包沒有的四項：15、16（已在穿戴欄）、99、98（哪裡都沒有）。原版處理其中三項（16 已穿在同一位置，這一包它不動）。
        List<String> missed = rows(log, VANILLA_MISS);
        List<String> unbacked = rows(log, TAG + " unbacked ");
        expect("原版處理無對應項的次數（Couldn't find item 行）＝" + (enforce ? 0 : 3) + "（實際 " + missed.size() + "）", missed.size() == (enforce ? 0 : 3));
        expect("unbacked 行數＝" + (observe || enforce ? 4 : 0) + "（實際 " + unbacked.size() + "）", unbacked.size() == (observe || enforce ? 4 : 0));
        expect("封包清單 " + (enforce ? "少掉 4 項無對應項、其餘 3 項順序不變" : "7 項不動") + "（實際 " + w.ids() + "）",
                w.ids().equals(enforce ? "[10, 11, 14]" : "[10, 11, 14, 15, 16, 99, 98]"));
        // 背包裡有的照穿（10 從背包穿上、11 已穿不動、14 不是衣物從背包重設）；enforce 下 15、16 不在清單，原版把它們從穿戴欄卸下。
        expect("穿戴欄＝" + (enforce ? "[10, 11, 14]" : "[10, 11, 14, 15, 16]") + "（實際 " + w.worn() + "）",
                w.worn().equals(enforce ? "[10, 11, 14]" : "[10, 11, 14, 15, 16]"));
        expect("背包裡的眼鏡穿在封包指定的位置", w.player.getWornItems().getItem(ItemBodyLocation.EYES) == w.glasses);

        if (observe) {
            expect("observe：原版處理的每一項都有記到 " + types(missed, true) + " ⊆ " + types(unbacked, false),
                    types(unbacked, false).containsAll(types(missed, true)) && types(unbacked, false).size() == 4);
        }
        if (observe || enforce) {
            String action = enforce ? "removed" : "logged";
            expect("log：種類、ID、位置、是否已穿、動作、連線玩家、計數（" + unbacked.get(0) + "）",
                    unbacked.get(0).contains(TAG + " unbacked type=Base.GhostA id=15 loc=") && unbacked.get(0).contains(" worn=1 action=" + action
                            + " connectionPlayers=7:tester unbacked=1 suppressed=0 anomalies=0."));
            expect("哪裡都沒有的那一項記 worn=0", unbacked.get(2).contains(" id=99 ") && unbacked.get(2).contains(" worn=0 "));
            expect("客戶端給的種類字串清過：空白與等號變成 ?、整行不含原字串",
                    unbacked.get(3).contains(" type=Evil?type?x?id?1 id=98 ") && !unbacked.get(3).contains("Evil type"));
            Method clean = Class.forName(GUARD).getDeclaredMethod("clean", String.class);
            clean.setAccessible(true);
            String cleaned = (String) clean.invoke(null, "A\nB\tC\u0000" + "x".repeat(100));
            expect("種類字串的換行與控制字元清掉、截成 64 字元（" + cleaned.length() + "）",
                    cleaned.length() == 64 && cleaned.startsWith("A?B?C?x") && cleaned.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f));
            expect("unbacked 計數 4、anomalies 0", guardLong("unbackedForTest") == 4 && guardLong("anomaliesForTest") == 0);
            expect("首次生效行恰 1 行", rows(log, TAG + " 首次生效 mode=" + mode + " bodyModelTypes=").size() == 1
                    && rows(log, " reflect=ok.").size() == 1);
        } else if ("off".equals(mode)) {
            expect("off：零計數、首次生效行寫 mode=off",
                    guardLong("unbackedForTest") == 0 && rows(log, TAG + " 首次生效 mode=off bodyModelTypes=").size() == 1);
        }

        // 同一包前面的項目把後面那一項原本穿著的東西擠掉：背包裡的 10 改穿到 16 的位置，原版輪到 16 時它已經不在穿戴欄。
        int missedBefore = missed.size();
        int unbackedBefore = unbacked.size();
        World shifted = world();
        shifted.items.get(0).location = ItemBodyLocation.BANDAGE;
        shifted.packet.processServer(null, shifted.conn);
        List<String> missedShift = rows(log, VANILLA_MISS).subList(missedBefore, rows(log, VANILLA_MISS).size());
        List<String> unbackedShift = rows(log, TAG + " unbacked ").subList(unbackedBefore, rows(log, TAG + " unbacked ").size());
        expect("擠掉：原版處理 " + (enforce ? "0 項" : "4 項，含被擠掉的 GhostB") + "（實際 " + types(missedShift, true) + "）",
                enforce ? missedShift.isEmpty() : missedShift.size() == 4 && types(missedShift, true).contains("Base.GhostB"));
        if (observe || enforce) {
            expect("擠掉：被擠掉的那一項有記到" + (enforce ? "、清單只剩背包裡有的（" + shifted.ids() + "）" : ""),
                    types(unbackedShift, false).contains("Base.GhostB") && (!enforce || shifted.ids().equals("[10, 11, 14]")));

            // 清單尾端有壞項目（null）：前面已經找到四項無對應，判斷仍然整包作廢——不外拋、清單一項都不動、記 anomaly
            World broken = world();
            broken.items.add(null);
            List<Object> before = new ArrayList<>(broken.items);
            Class.forName(GUARD).getMethod("begin", SyncClothingPacket.class, UdpConnection.class).invoke(null, broken.packet, broken.conn);
            expect("判斷途中出例外：不外拋、清單項目與順序都不動、anomalies 1、記一行 anomaly、unbacked 計數不變",
                    before.equals(new ArrayList<Object>(broken.items)) && guardLong("anomaliesForTest") == 1
                            && rows(log, TAG + " anomaly reason=NullPointerException ").size() == 1
                            && guardLong("unbackedForTest") == 8);

            // 傷口與繃帶的顯示用物品：不逐項記、只累計，第一次出現記一行 bodyModels；10 分鐘內再出現不再記
            Method isModel = Class.forName(GUARD).getDeclaredMethod("isBodyModelForTest", String.class);
            isModel.setAccessible(true);
            expect("顯示用物品種類表：繃帶（乾淨、帶血）與各性別的傷口在表內，一般衣物與 null 不在",
                    (boolean) isModel.invoke(null, "Base.Bandage_RightHand") && (boolean) isModel.invoke(null, "Base.Bandage_RightHand_Blood")
                            && (boolean) isModel.invoke(null, "Base.Wound_RHand_Bite_Female") && (boolean) isModel.invoke(null, "Base.Wound_LForearm_Laceration_Male")
                            && !(boolean) isModel.invoke(null, "Base.Glasses_Normal") && !(boolean) isModel.invoke(null, (Object) null));
            int rowsBefore = rows(log, TAG + " unbacked ").size();
            World model = world();
            model.items.add(desc(97, "Base.Bandage_RightHand_Blood", ItemBodyLocation.BANDAGE));
            model.packet.processServer(null, model.conn);
            World model2 = world();
            model2.items.add(desc(97, "Base.Wound_RHand_Bite_Female", ItemBodyLocation.WOUND));
            model2.packet.processServer(null, model2.conn);
            List<String> beats = rows(log, TAG + " bodyModels total=");
            expect("顯示用物品：兩包各 1 項只累計（" + guardLong("bodyModelsForTest") + "）、unbacked 行只多另外 4＋4 行、bodyModels 行恰 1 行（" + beats + "）"
                            + (enforce ? "、enforce 只拿掉另外 4 項、顯示用物品留在清單（" + model.ids() + "、" + model2.ids() + "）" : "、清單不動"),
                    guardLong("bodyModelsForTest") == 2 && rows(log, TAG + " unbacked ").size() == rowsBefore + 8 && beats.size() == 1
                            && beats.get(0).contains(" bodyModels total=1 action=counted unbacked=")
                            && guardLong("unbackedForTest") == 16
                            && model.ids().equals(enforce ? "[10, 11, 14, 97]" : "[10, 11, 14, 15, 16, 99, 98, 97]")
                            && model2.ids().equals(enforce ? "[10, 11, 14, 97]" : "[10, 11, 14, 15, 16, 99, 98, 97]"));
            if (enforce) {
                // 放行＝原版真的輪到它：測試環境沒有物品腳本，原版去建立時印 Couldn't find item；其餘無對應項都拿掉了，所以只會有這兩種
                var built = types(rows(log, VANILLA_MISS), true);
                expect("enforce：留在清單的顯示用物品照原版處理（原版走到建立的種類 " + built + "）",
                        built.size() == 2 && built.containsAll(List.of("Base.Bandage_RightHand_Blood", "Base.Wound_RHand_Bite_Female")));
            }

            // 已經穿著的普通衣物（16，背包沒有）被封包改報成顯示用物品的種類：穿著的不是顯示用物品，所以照樣逐項記、不算顯示用物品。
            //（這個 JVM 到這裡已接近每 10 秒 20 行的上限，行數不一定滿 4；計數才是準的）
            long modelsBefore = guardLong("bodyModelsForTest");
            long unbackedCountBefore = guardLong("unbackedForTest");
            int fakeBefore = rows(log, TAG + " unbacked ").size();
            World fake = world();
            fake.items.get(4).itemType = "Base.Bandage_RightHand";
            fake.packet.processServer(null, fake.conn);
            List<String> fakeRows = rows(log, TAG + " unbacked ");
            expect("穿著的普通衣物假報顯示用物品的種類：不算顯示用物品、照樣算無對應項並逐項記" + (enforce ? "、enforce 照樣拿掉（" + fake.ids() + "）" : ""),
                    guardLong("bodyModelsForTest") == modelsBefore && guardLong("unbackedForTest") == unbackedCountBefore + 4
                            && (!enforce || fake.ids().equals("[10, 11, 14]"))
                            && fakeRows.subList(fakeBefore, fakeRows.size()).stream()
                                    .anyMatch(r -> r.contains(" unbacked type=Base.Bandage_RightHand id=16 ") && r.contains(" worn=1 ")));

            // 已經穿著的顯示用物品（97，背包沒有；種類與位置都對）下一包照樣列出：留在清單、穿戴欄還是原本那一件
            long keptBefore = guardLong("bodyModelsForTest");
            long keptUnbacked = guardLong("unbackedForTest");
            int keptMiss = rows(log, VANILLA_MISS).size();
            World kept = world();
            StubClothing wornModel = (StubClothing) clothing(97, ItemBodyLocation.WOUND);
            wornModel.fullType = "Base.Wound_RHand_Bite_Female";
            kept.player.getWornItems().setItem(ItemBodyLocation.WOUND, wornModel);
            kept.items.add(desc(97, "Base.Wound_RHand_Bite_Female", ItemBodyLocation.WOUND));
            kept.packet.processServer(null, kept.conn);
            List<String> keptMissed = rows(log, VANILLA_MISS);
            expect("已經穿著的顯示用物品再同步一次：算顯示用物品、不算無對應項，留在清單（" + kept.ids() + "）、穿戴欄還是原本那一件（" + kept.worn() + "）、原版沒有重建它",
                    guardLong("bodyModelsForTest") == keptBefore + 1 && guardLong("unbackedForTest") == keptUnbacked + 4
                            && kept.ids().equals(enforce ? "[10, 11, 14, 97]" : "[10, 11, 14, 15, 16, 99, 98, 97]")
                            && kept.player.getWornItems().getItem(ItemBodyLocation.WOUND) == wornModel
                            && kept.worn().equals(enforce ? "[10, 11, 14, 97]" : "[10, 11, 14, 15, 16, 97]")
                            && !types(keptMissed.subList(keptMiss, keptMissed.size()), true).contains("Base.Wound_RHand_Bite_Female"));

            // 顯示用物品的種類報在別的位置（帽子 11 穿著的位置）：不算顯示用物品，enforce 照樣拿掉
            long misplacedModels = guardLong("bodyModelsForTest");
            long misplacedUnbacked = guardLong("unbackedForTest");
            World misplaced = world();
            misplaced.items.add(desc(96, "Base.Bandage_RightHand", ItemBodyLocation.EARS));
            misplaced.packet.processServer(null, misplaced.conn);
            expect("顯示用物品的種類報在別的位置：不算顯示用物品、算無對應項" + (enforce ? "、enforce 照樣拿掉（" + misplaced.ids() + "）" : ""),
                    guardLong("bodyModelsForTest") == misplacedModels && guardLong("unbackedForTest") == misplacedUnbacked + 5
                            && (!enforce || misplaced.ids().equals("[10, 11, 14]")));

            // 化妝品（化妝介面的預覽，背包沒有）：種類在表內、位置是腳本的位置才算顯示用物品；位置錯的、表內沒有的種類照樣算無對應項
            expect("顯示用物品種類表：原版化妝品在表內，表內沒有的化妝品種類不在",
                    (boolean) isModel.invoke(null, "Base.MakeUp_LipsRed") && (boolean) isModel.invoke(null, "Base.MakeUp_SkullFace2")
                            && (boolean) isModel.invoke(null, "Base.MakeUp_EyesShadowBlue") && (boolean) isModel.invoke(null, "Base.MakeUp_Crow")
                            && !(boolean) isModel.invoke(null, "Base.MakeUp_Fake"));
            long makeUpModels = guardLong("bodyModelsForTest");
            long makeUpUnbacked = guardLong("unbackedForTest");
            int makeUpMiss = rows(log, VANILLA_MISS).size();
            World makeUp = world();
            makeUp.items.add(desc(95, "Base.MakeUp_LipsRed", ItemBodyLocation.MAKE_UP_LIPS));
            makeUp.items.add(desc(94, "Base.MakeUp_LipsBlue", ItemBodyLocation.MAKE_UP_EYES));
            makeUp.items.add(desc(93, "Base.MakeUp_Fake", ItemBodyLocation.MAKE_UP_FULL_FACE));
            makeUp.packet.processServer(null, makeUp.conn);
            List<String> makeUpMissed = rows(log, VANILLA_MISS);
            TreeSet<String> makeUpBuilt = types(makeUpMissed.subList(makeUpMiss, makeUpMissed.size()), true);
            expect("化妝品：種類與位置都對的 1 項算顯示用物品，位置錯的與表內沒有的種類算無對應項（另外 4＋2 項）"
                            + (enforce ? "、enforce 只留對的那一項（" + makeUp.ids() + "）、原版只走到建立它（" + makeUpBuilt + "）" : "、清單不動、原版三項都處理（" + makeUpBuilt + "）"),
                    guardLong("bodyModelsForTest") == makeUpModels + 1 && guardLong("unbackedForTest") == makeUpUnbacked + 6
                            && makeUp.ids().equals(enforce ? "[10, 11, 14, 95]" : "[10, 11, 14, 15, 16, 99, 98, 95, 94, 93]")
                            && (enforce ? makeUpBuilt.equals(new TreeSet<>(List.of("Base.MakeUp_LipsRed")))
                                    : makeUpBuilt.containsAll(List.of("Base.MakeUp_LipsRed", "Base.MakeUp_LipsBlue", "Base.MakeUp_Fake"))));
        }

        if (failed > 0) {
            OUT.println("worn-item-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        OUT.println("worn-item-guard OK mode=" + mode);
    }

    /** 日誌鏈拋例外（enforce）：首次生效與 unbacked 記錄爆掉時照樣拿掉無對應項、不外拋、只計 anomalies；改拋 OutOfMemoryError 時照常外拋。 */
    private static void logThrow() throws Exception {
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                explode();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                explode();
            }
        });
        Throwable t = null;
        try {
            DebugLog.log("[WornItemGuardTest] self-check");
        } catch (Throwable e) {
            t = e;
        }
        expect("自驗：DebugLog.log 真的拋例外（" + t + "）", t instanceof IllegalStateException);
        t = null;
        World w = world();
        try {
            w.packet.processServer(null, w.conn);
        } catch (Throwable e) {
            t = e;
        }
        expect("首次生效與 unbacked 記錄拋例外：真 processServer 照常跑完、不外拋、無對應項已拿掉（" + w.ids() + "）、anomalies 5（" + t + "）",
                t == null && w.ids().equals("[10, 11, 14]") && guardLong("anomaliesForTest") == 5);
        oom = true;
        t = null;
        World w2 = world();
        try {
            w2.packet.processServer(null, w2.conn);
        } catch (Throwable e) {
            t = e;
        }
        expect("Error 照常外拋：日誌鏈拋 OutOfMemoryError 時不被吞，從 processServer 拋出（" + t + "）", t instanceof OutOfMemoryError);
        OUT.println(failed == 0 ? "worn-item-guard OK mode=logthrow" : "worn-item-guard FAIL " + failed + " 項");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void explode() {
        if (oom) {
            throw new OutOfMemoryError("<test> logger oom");
        }
        throw new IllegalStateException("<test> logger exploded");
    }

    private static final class World {
        IsoPlayer player;
        UdpConnection conn;
        SyncClothingPacket packet;
        ArrayList<SyncClothingPacket.ItemDescription> items;
        InventoryItem glasses;

        String ids() {
            List<Integer> out = new ArrayList<>();
            for (SyncClothingPacket.ItemDescription d : items) {
                out.add(d.itemId);
            }
            return out.toString();
        }

        String worn() {
            TreeSet<Integer> out = new TreeSet<>();
            player.getWornItems().forEach(x -> out.add(x.getItem().getID()));
            return out.toString();
        }
    }

    /**
     * 背包：眼鏡 10（衣物）、帽子 11（衣物）、背包 14（不是衣物）。穿戴欄：帽子 11、背包 14、不是衣物的 15（背包沒有）、
     * 衣物 16（背包沒有，已穿在它自己的位置）。封包依序列 10、11、14、15、16、99、98。
     */
    @SuppressWarnings("unchecked")
    private static World world() throws Exception {
        World w = new World();
        BodyLocationGroup group = new BodyLocationGroup("Test");
        ItemBodyLocation[] used = {ItemBodyLocation.EYES, ItemBodyLocation.BACK, ItemBodyLocation.BELT, ItemBodyLocation.EARS,
                ItemBodyLocation.BANDAGE, ItemBodyLocation.WOUND, ItemBodyLocation.DRESS, ItemBodyLocation.CUIRASS,
                ItemBodyLocation.MAKE_UP_LIPS, ItemBodyLocation.MAKE_UP_EYES, ItemBodyLocation.MAKE_UP_FULL_FACE};
        for (ItemBodyLocation loc : used) {
            group.getOrCreateLocation(loc);
        }
        w.player = alloc(IsoPlayer.class);
        w.player.onlineId = 7;
        w.player.username = "tester";
        set(w.player, "ecsComponentMap", new HashMap<>());
        WornItems wornItems = new WornItems(group);
        set(w.player, "wornItems", wornItems);
        ItemContainer bag = alloc(ItemContainer.class);
        ArrayList<InventoryItem> bagItems = new ArrayList<>();
        set(bag, "items", bagItems);
        set(w.player, "inventory", bag);

        w.glasses = clothing(10, ItemBodyLocation.EYES);
        InventoryItem hat = clothing(11, ItemBodyLocation.EARS);
        InventoryItem backpack = plain(14);
        bagItems.add(w.glasses);
        bagItems.add(hat);
        bagItems.add(backpack);
        wornItems.setItem(ItemBodyLocation.EARS, hat);
        wornItems.setItem(ItemBodyLocation.BACK, backpack);
        wornItems.setItem(ItemBodyLocation.BELT, plain(15));
        wornItems.setItem(ItemBodyLocation.BANDAGE, clothing(16, ItemBodyLocation.BANDAGE));

        w.conn = alloc(UdpConnection.class);
        w.conn.players = new IsoPlayer[4];
        w.conn.players[0] = w.player;
        w.packet = new SyncClothingPacket();
        ((Player) get(w.packet, "player")).setPlayer(w.player);
        w.items = (ArrayList<SyncClothingPacket.ItemDescription>) get(w.packet, "items");
        w.items.add(desc(10, "Base.Glasses", ItemBodyLocation.EYES));
        w.items.add(desc(11, "Base.Hat", ItemBodyLocation.EARS));
        w.items.add(desc(14, "Base.Bag", ItemBodyLocation.BACK));
        w.items.add(desc(15, "Base.GhostA", ItemBodyLocation.BELT));
        w.items.add(desc(16, "Base.GhostB", ItemBodyLocation.BANDAGE));
        w.items.add(desc(99, "Base.GhostC", ItemBodyLocation.DRESS));
        w.items.add(desc(98, "Evil type=x id=1", ItemBodyLocation.CUIRASS));
        return w;
    }

    private static SyncClothingPacket.ItemDescription desc(int id, String type, ItemBodyLocation loc) {
        SyncClothingPacket.ItemDescription d = new SyncClothingPacket.ItemDescription();
        d.itemId = id;
        d.itemType = type;
        d.location = loc;
        return d;
    }

    /** Unsafe 配置的衣物沒有 script：位置（與需要時的種類）由測試給。 */
    public static class StubClothing extends Clothing {
        public ItemBodyLocation loc;
        public String fullType;

        public StubClothing() {
            super(null, null, null, (String) null, null, null);
        }

        @Override public ItemBodyLocation getBodyLocation() { return loc; }

        @Override public String getFullType() { return fullType != null ? fullType : super.getFullType(); }
    }

    public static class StubItem extends InventoryItem {
        public StubItem() {
            super(null, null, null, (String) null);
        }
    }

    private static InventoryItem clothing(int id, ItemBodyLocation loc) throws Exception {
        StubClothing c = alloc(StubClothing.class);
        c.id = id;
        c.loc = loc;
        return c;
    }

    private static InventoryItem plain(int id) throws Exception {
        StubItem item = alloc(StubItem.class);
        item.id = id;
        return item;
    }

    private static int guardInt(String name) throws Exception {
        Field f = Class.forName(GUARD).getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static long guardLong(String name) throws Exception {
        Method m = Class.forName(GUARD).getDeclaredMethod(name);
        m.setAccessible(true);
        return (long) m.invoke(null);
    }

    private static ByteArrayOutputStream captureLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(buf);
        return buf;
    }

    /** log 行裡的物品種類集合：原版的「Couldn't find item 種類」行（照 helper 的規則把空白與等號換成 ?），或 unbacked 行的 type=。 */
    private static TreeSet<String> types(List<String> rows, boolean vanilla) {
        TreeSet<String> out = new TreeSet<>();
        for (String row : rows) {
            out.add(vanilla ? row.substring(row.indexOf(VANILLA_MISS) + VANILLA_MISS.length()).trim().replace(' ', '?').replace('=', '?')
                    : row.replaceAll(".* unbacked type=(\\S+) .*", "$1"));
        }
        return out;
    }

    private static List<String> rows(ByteArrayOutputStream log, String needle) {
        List<String> out = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(needle)) {
                out.add(line);
            }
        }
        return out;
    }

    private static Object get(Object target, String name) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
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
        OUT.println((ok ? "wig pass  " : "wig FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private WornItemGuardTest() {}
}
