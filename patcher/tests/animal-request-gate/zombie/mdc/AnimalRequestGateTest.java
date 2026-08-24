package zombie.mdc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import zombie.characters.animals.IsoAnimal;
import zombie.core.random.RandStandard;
import zombie.core.raknet.UdpConnection;
import zombie.iso.Vector3;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * W14 動物 requested 冷卻＋範圍閘的行為驗證。
 *
 * <p>argv：{@code enforce}（預設出貨，兩把都 enforce）／{@code observe}／{@code off}／
 * {@code cooldown-only}／{@code range-only}。兩把 kill switch 各自獨立，模式是
 * {@code static final}，所以五個組合必須各跑一個獨立 JVM（build.ps1 [9k/10]）。測試自驗
 * {@code cooldownMode()}／{@code rangeMode()} 與 argv 相符——property 名稱打錯會炸在
 * 測試裡，不會默默把 enforce 版跑五遍假綠（與 AnimalRelevancyGateTest 同紀律）。
 *
 * <p><b>刻意不呼叫 {@link AnimalRequestGate#getPacket}</b>：它委派
 * {@code connection.getPacket(type)}，會拉進 {@code PacketsCache} → {@code PacketTypes}
 * → {@code AntiCheat}／{@code ServerOptions} 整條靜態初始化鏈。連線捕獲的結構正確性
 * （invokevirtual 恰 2 處、owner／desc 精確匹配）由 patcher 的 SmokeCheck 守；這裡改用
 * package-private {@code setConnectionForTest} 直接注入 ThreadLocal，只驗 filter 語意。
 *
 * <p><b>每次過濾都要重新注入連線</b>：{@code filterRequests} 的 finally 會
 * {@code CURRENT.remove()}——捕獲缺失必須退化成 {@code null}（跳過範圍檢查、只做冷卻），
 * 絕不能沿用<b>上一個</b>連線的座標去判定別人的快照。這正是 vanilla 的實態：
 * {@code getPacket} 與 {@code requests.get} 在同一次 {@code sendUpdateToClient} 內成對
 *出現。所以 {@link #filter(UdpConnection, long, HashSet)} 每次都先 set 再 filter；
 * 不帶連線的多載顯式 set {@code null}，讓「範圍跳過」是斷言而不是上一次呼叫的殘留。
 *
 * <p><b>存在性查詢與 RANGE_MODE 無關</b>——helper 的 abuse 面設計：不存在的 ID 一律
 * <b>不 mark</b>，否則 client 能用大量假 ID 灌爆 bucket 去清掉真動物的冷卻。因此
 * <b>任何</b>進入過濾迴圈的呼叫都會摸到 {@code AnimalInstanceManager}，而冷卻／accepted／
 * cap／sweep 的斷言<b>只有在 ID 真的掛著動物時才成立</b>（假 ID 走的是「保留在 out 但不
 * 計數、不 mark」那條 passthrough）。故本測試在第一次 filter 之前就把
 * {@code AnimalInstanceManager} 初始化好，並替每一個要走冷卻語意的 onlineID 掛上真動物；
 * 唯一刻意不掛的是 G 段的 {@code 201}，那條正是在驗 passthrough 本身。
 *
 * <p>連線與動物實例都用 {@code Unsafe.allocateInstance} 取得而<b>不跑建構子</b>（同上理由），
 * 欄位由測試自行補齊：{@code UdpConnection.RelevantTo} 只讀 {@code connectArea}／
 * {@code releventPos}／{@code relevantRange}；動物只被讀 {@code getX()}／{@code getY()}
 * （＝{@code IsoMovingObject} 的 private float x／y，沿超類鏈反射寫入）。動物用
 * {@code getAnimals().put(id, animal)} 直接掛進 {@code IsoObjectID}——繞過
 * {@code AnimalInstanceManager.add()}，避免 DebugType 噪音與 {@code setOnlineID} 的額外依賴。
 * {@code AnimalInstanceManager} 的 {@code <clinit>} 是唯一需要真的先跑起來的 vanilla
 * 靜態初始化：它 {@code new IsoObjectID<>(IsoAnimal.class)} → {@code Rand.Next(32766)}
 * → {@code RandAbstract.rand}，而遊戲外那個 randomizer 是 null（NPE ⇒
 * {@code ExceptionInInitializerError}；class init 一旦失敗，該 JVM 內從此永久
 * {@code NoClassDefFoundError}）。故 {@code main} 的第一件事就是
 * {@code RandStandard.INSTANCE.init()} 播種。這條依賴一旦在未來版本斷掉，
 * {@code main} 會直接帶著堆疊往外拋、exit≠0——<b>刻意不 catch</b>：範圍幾何是 security
 * contract，注入失敗必須紅，不得降級成 skip，也不得被 helper 的 fail-open 吃成 anomaly
 * 之後假綠。
 *
 * <pre>
 *   AnimalRequestGate.allowRange   vanillaRadius = (getRelevantRange()-2)*10
 *                                  cutoff        = vanillaRadius + RANGE_MARGIN(48)
 *   UdpConnection.RelevantTo:276   |px - x| &lt;= radius（含等於）
 * </pre>
 * relevantRange=16 ⇒ vanilla 140、cutoff 188。距離 150（vanilla 外、帶內）、188（恰等於）、
 * 189（帶外）三點把 {@code +48} 這個常數釘死：margin 若被改成 0 或 64，其中一點就會紅。
 *
 * <p>所有計數器都是<b>累積值</b>，因此每條斷言都取前後差值（{@link Counts}），而不是絕對值。
 * 每個子案例用自己的 guid／onlineID，避免冷卻狀態互相污染。
 */
public final class AnimalRequestGateTest {

    /**
     * 對齊 helper 的 {@code MAX_ANIMALS_PER_PACKET}（＝vanilla {@code sendUpdateToClient}
     * 的 {@code animalsCount >= 150 → break}）：單次過濾的輸出上界，也是灌桶時的批次大小。
     */
    private static final int PER_PACKET_CAP = 150;
    /** 對齊 helper 的 {@code BUCKET_CAP}。 */
    private static final int BUCKET_CAP = 2048;

    private static int failed;

    public static void main(String[] args) throws Exception {
        final String modeName = args.length > 0 ? args[0] : "enforce";
        final int[] expected = expectedModes(modeName);
        final int cd = AnimalRequestGate.cooldownMode();
        final int rg = AnimalRequestGate.rangeMode();

        final boolean cdEnforce = cd == 1;
        final boolean cdObserve = cd == 2;
        final boolean cdActive = cd != 0;
        final boolean rgEnforce = rg == 1;
        final boolean rgObserve = rg == 2;
        /** 兩把都 off ⇒ filterRequests 純委派 map.get(key)，連 tick() 都不跑。 */
        final boolean bothOff = cd == 0 && rg == 0;

        // 播種必須早於第一次 filter：存在性查詢與 RANGE_MODE 無關，任何進入迴圈的呼叫
        // 都會觸發 AnimalInstanceManager.<clinit>（見類別註解）。不 catch——注入失敗要紅。
        RandStandard.INSTANCE.init();
        final AnimalInstanceManager mgr = AnimalInstanceManager.getInstance();

        final float px = 1000.0f;
        final float py = 1000.0f;
        final byte relevantRange = 16;
        final float vanilla = (relevantRange - 2) * 10.0f;
        final float cutoff = vanilla + 48.0f;
        final UdpConnection conn = conn(relevantRange, px, py);

        Counts b;
        Counts a;

        // ── A 自驗：argv 與實際模式相符 ──────────────────────────────────────
        expect("自驗：argv=" + modeName + " ⇒ cooldownMode 應為 " + expected[0]
                + "（實際 " + cd + "）", cd == expected[0]);
        expect("自驗：argv=" + modeName + " ⇒ rangeMode 應為 " + expected[1]
                + "（實際 " + rg + "）", rg == expected[1]);
        expect("自驗：cooldownMs()==6000（預設值／enforce 顯式值都夾在 [1000,30000] 內）",
                AnimalRequestGate.cooldownMs() == 6000L);

        // ── B 判別式：只有 (Long key, HashSet value) 進入過濾 ─────────────────
        // 這三條都走 try 之前的 early return，helper 的 finally 不會執行 ⇒ 顯式清乾淨。
        AnimalRequestGate.setConnectionForTest(null);

        // timer map 路徑（connection.timerUpdateAnimal.get(Short)）：熱路徑零配置直通
        HashSet<Object> timerish = set((short) 7);
        b = snap();
        Object shortKeyed = AnimalRequestGate.filterRequests(oneEntry((short) 3, timerish), (short) 3);
        a = snap();
        expect("Short key（timer map）：回傳原物件（identity）", shortKeyed == timerish);
        expect("Short key：零計數變動（連 tick 都不跑）", same(b, a));

        // Long key 但 value 形狀不對：一樣原樣直通
        java.util.ArrayList<Object> notASet = new java.util.ArrayList<>();
        notASet.add((short) 7);
        b = snap();
        Object notASetOut = AnimalRequestGate.filterRequests(oneEntry(1001L, notASet), 1001L);
        a = snap();
        expect("Long key ＋ 非 HashSet value：回傳原物件（identity）", notASetOut == notASet);
        expect("非 HashSet value：零計數變動", same(b, a));

        // Long key 但 map 無此 entry：vanilla 拿到 null，這裡也必須是 null
        b = snap();
        Object missing = AnimalRequestGate.filterRequests(new HashMap<Object, Object>(), 1001L);
        a = snap();
        expect("Long key 但 map 無 entry：回 null（不得包裝成空集合）", missing == null);
        expect("null value：零計數變動", same(b, a));

        // ── C 空集合 fast path ─────────────────────────────────────────────
        HashSet<Object> empty = new HashSet<>();
        b = snap();
        Object emptyOut = filter(1002L, empty);
        a = snap();
        expect("空集合：回傳原物件（identity，不配置新集合）", emptyOut == empty);
        expect("空集合：零計數變動", same(b, a));

        // ── D 冷卻生命週期（不注入連線 ⇒ 範圍閘一律放行，隔離出冷卻語意） ──────
        // ID 必須真的掛著動物，否則走的是「假 ID passthrough」而完全碰不到冷卻。
        final long gD = 2001L;
        final short idD = 11;
        animal(mgr, idD, px, py);

        HashSet<Object> in1 = set(idD);
        b = snap();
        Object d1 = filter(gD, in1);
        a = snap();
        expect("首發一定放行：ID 在結果內", contains(d1, idD));
        if (bothOff) {
            expect("both off：純委派回原物件（identity）", d1 == in1);
            expect("both off：零計數變動", same(b, a));
        } else {
            expect("非 both off：回新 HashSet（不 mutate vanilla 的 map entry）", d1 != in1);
            expect("首發：accepted +1", a.accepted == b.accepted + 1);
            expect("首發：mark 只在 COOLDOWN 非 off 時發生（bucket " + b.buckets + " → " + a.buckets + "）",
                    a.buckets == b.buckets + (cdActive ? 1 : 0));
        }

        HashSet<Object> in2 = set(idD);
        b = snap();
        Object d2 = filter(gD, in2);
        a = snap();
        if (bothOff) {
            expect("both off：窗內重複純委派、ID 在場", d2 == in2 && contains(d2, idD));
            expect("both off：零計數變動", same(b, a));
        } else if (cdEnforce) {
            expect("冷卻 enforce：窗內重複的 ID 缺席", !contains(d2, idD));
            expect("冷卻 enforce：cooldownSuppressed +1", a.cdSup == b.cdSup + 1);
            expect("冷卻 enforce：被擋下的 ID 不計 accepted", a.accepted == b.accepted);
        } else if (cdObserve) {
            expect("冷卻 observe：窗內重複的 ID 仍在場（行為未改）", contains(d2, idD));
            expect("冷卻 observe：cooldownObserved +1", a.cdObs == b.cdObs + 1);
            expect("冷卻 observe：放行＝實際送出 ⇒ accepted +1", a.accepted == b.accepted + 1);
        } else {
            expect("COOLDOWN off：窗內重複照樣在場", contains(d2, idD));
            expect("COOLDOWN off：冷卻計數不動", a.cdSup == b.cdSup && a.cdObs == b.cdObs);
            expect("COOLDOWN off：完全不 mark（bucket 不增）", a.buckets == b.buckets);
            expect("COOLDOWN off：仍計 accepted +1", a.accepted == b.accepted + 1);
        }

        // ── E guid 隔離：狀態是 (guid → onlineID)，另一連線不受影響 ───────────
        b = snap();
        Object e1 = filter(2002L, set(idD));
        a = snap();
        expect("guid 隔離：另一連線的同一 ID 立即放行", contains(e1, idD));
        expect("guid 隔離：不記冷卻抑制／觀測", a.cdSup == b.cdSup && a.cdObs == b.cdObs);

        // 冷卻窗過後（skew 只推 6001 ms，< 30 s 掃描間隔，不會誤觸 sweep）
        AnimalRequestGate.clockSkewForTest += 6001L;
        b = snap();
        Object d3 = filter(gD, set(idD));
        a = snap();
        expect("冷卻窗過後（skew +6001 > cooldownMs 6000）：再度放行", contains(d3, idD));
        expect("冷卻窗過後：不記冷卻抑制／觀測", a.cdSup == b.cdSup && a.cdObs == b.cdObs);

        // ── F/G 範圍幾何（真 IsoAnimal＋真 UdpConnection；注入失敗直接往外拋＝紅） ──
        expect("幾何前提：relevantRange=16 ⇒ vanilla 半徑 " + vanilla + "、cutoff " + cutoff,
                vanilla == 140.0f && cutoff == 188.0f);

        IsoAnimal far = animal(mgr, (short) 101, px + 200.0f, py);
        animal(mgr, (short) 102, px + 150.0f, py);
        animal(mgr, (short) 103, px + 100.0f, py);
        animal(mgr, (short) 104, px + 200.0f, py);
        animal(mgr, (short) 105, px + cutoff, py);
        animal(mgr, (short) 106, px + cutoff + 1.0f, py);

        // G 動物不存在：vanilla 的 write 對 null 本來就跳過 ⇒ 放行，不算 range 事件。
        // 刻意帶著連線跑：證明存在性 passthrough 先於範圍判定，而不是「因為沒連線才沒事」。
        final short idMissing = 201;
        b = snap();
        Object gOut = filter(conn, 3001L, set(idMissing));
        a = snap();
        expect("manager 無此 ID：一律在場", contains(gOut, idMissing));
        expect("manager 無此 ID：範圍計數不動", a.rgSup == b.rgSup && a.rgObs == b.rgObs);

        // F 距 200（cutoff 188 之外）
        b = snap();
        Object fFar = filter(conn, 3101L, set((short) 101));
        a = snap();
        if (rgEnforce) {
            expect("範圍 enforce：距 200 缺席", !contains(fFar, (short) 101));
            expect("範圍 enforce：rangeSuppressed +1", a.rgSup == b.rgSup + 1);
            expect("範圍 enforce：被範圍擋下的 ID 不計 accepted", a.accepted == b.accepted);
        } else if (rgObserve) {
            expect("範圍 observe：距 200 仍在場（行為未改）", contains(fFar, (short) 101));
            expect("範圍 observe：rangeObserved +1", a.rgObs == b.rgObs + 1);
        } else {
            expect("範圍 off：距 200 在場、範圍計數不動",
                    contains(fFar, (short) 101) && a.rgSup == b.rgSup && a.rgObs == b.rgObs);
        }

        // 範圍閘先於冷卻：被範圍擋下的 ID 絕不能被 mark，否則玩家走回範圍內還要再等一個窗
        if (rgEnforce) {
            moveTo(far, px + 100.0f, py);
            b = snap();
            Object fBack = filter(conn, 3101L, set((short) 101));
            a = snap();
            expect("範圍 enforce：被擋下的 ID 未被 mark（動物移回近距後同 guid 立即放行）",
                    contains(fBack, (short) 101));
            expect("範圍 enforce：該次不記 cooldownSuppressed（證明前一次真的沒 mark）",
                    a.cdSup == b.cdSup);
        }

        // +48 margin 的三個釘子：150（vanilla 外、帶內）／188（恰等於，RelevantTo 是 <=）／100
        expectAllowedByRange(conn, "距 150（vanilla 140 外、+48 帶內）", 3102L, (short) 102);
        expectAllowedByRange(conn, "距 == cutoff 188（RelevantTo 含等於）", 3105L, (short) 105);
        expectAllowedByRange(conn, "距 100（vanilla 半徑內）", 3103L, (short) 103);

        // 帶外 1 格：另一側的釘子（margin 被放寬成 64 這條就會紅）
        b = snap();
        Object fEdge = filter(conn, 3106L, set((short) 106));
        a = snap();
        if (rgEnforce) {
            expect("範圍 enforce：距 189（cutoff+1）缺席、rangeSuppressed +1",
                    !contains(fEdge, (short) 106) && a.rgSup == b.rgSup + 1);
        } else if (rgObserve) {
            expect("範圍 observe：距 189 在場、rangeObserved +1",
                    contains(fEdge, (short) 106) && a.rgObs == b.rgObs + 1);
        } else {
            expect("範圍 off：距 189 在場、範圍計數不動",
                    contains(fEdge, (short) 106) && a.rgSup == b.rgSup && a.rgObs == b.rgObs);
        }

        // ── 不變式：每一條 Long-key 路徑都必須清掉 ThreadLocal 捕獲 ────────────
        // 這條在**所有五個模式**都要成立，尤其是兩把 kill switch 都 off 的組態：
        // both-off 的 early return 若寫在 try 外面就會繞過 finally，於是 getPacket
        // 每次 set 都沒人清，ThreadLocal 長期釘住最後一個 UdpConnection（含其 1 MB
        // buffer），而 getPacket 的 javadoc 正是宣稱要避免這件事。
        AnimalRequestGate.setConnectionForTest(conn);
        expect("前置：注入後捕獲值存在", AnimalRequestGate.capturedConnectionForTest() == conn);
        // 直接呼叫 filterRequests：不能走 filter(...) 多載，那會在呼叫前重新注入，
        // 使本斷言無論 finally 有沒有清都恆真（曾經如此，變異測試抓不到才發現）。
        AnimalRequestGate.filterRequests(oneEntry(3900L, set((short) 103)), 3900L);
        expect("不變式：Long-key 過濾後捕獲值必被清除（含 both-off 組態）",
                AnimalRequestGate.capturedConnectionForTest() == null);
        // 反向釘：非 Long key（timer map 熱路徑）刻意不碰 ThreadLocal
        AnimalRequestGate.setConnectionForTest(conn);
        AnimalRequestGate.filterRequests(oneEntry((short) 3, set((short) 3)), (short) 3);
        expect("反向釘：timer map 熱路徑不碰 ThreadLocal（捕獲值保留）",
                AnimalRequestGate.capturedConnectionForTest() == conn);
        AnimalRequestGate.setConnectionForTest(null);

        // ThreadLocal 捕獲缺失：範圍跳過（fail-open 到保守側），冷卻照做
        b = snap();
        Object noConn = filter(3104L, set((short) 104));
        a = snap();
        expect("connection=null：距 200 也在場（範圍跳過）", contains(noConn, (short) 104));
        expect("connection=null：範圍計數不動", a.rgSup == b.rgSup && a.rgObs == b.rgObs);

        b = snap();
        Object noConn2 = filter(3104L, set((short) 104));
        a = snap();
        if (cdEnforce) {
            expect("connection=null：範圍跳過但冷卻照做（窗內重複缺席、cooldownSuppressed +1）",
                    !contains(noConn2, (short) 104) && a.cdSup == b.cdSup + 1);
        } else if (cdObserve) {
            expect("connection=null：範圍跳過但冷卻照做（cooldownObserved +1）",
                    contains(noConn2, (short) 104) && a.cdObs == b.cdObs + 1);
        } else {
            expect("connection=null 且 COOLDOWN off：窗內重複照樣在場",
                    contains(noConn2, (short) 104));
        }

        // ── H 集合含非 Short 元素：保留、不炸、不計 accepted ──────────────────
        final short idH = 21;
        animal(mgr, idH, px, py);
        HashSet<Object> mixed = set(idH, "junk");
        b = snap();
        Object mixedOut = filter(4001L, mixed);
        a = snap();
        expect("非 Short 元素原樣保留（不猜、不丟）", contains(mixedOut, "junk"));
        expect("同批的 Short 元素照常處理", contains(mixedOut, idH));
        expect("非 Short 元素不計入 accepted",
                a.accepted == b.accepted + (bothOff ? 0 : 1));

        // ── COOLDOWN off 的全局不變式：整場都不該有任何 bucket ───────────────
        if (cd == 0) {
            expect("COOLDOWN off：整場完全不 mark（bucketCount 恆為 0）",
                    AnimalRequestGate.bucketCountForTest() == 0);
        }

        // ── I bucket 上限安全閥：拒記新 ID，既有冷卻必須存活 ──────────────────
        // 這是 abuse 面的核心斷言：舊版「整桶清空」可被「灌大量相異 ID」用來清掉目標
        // 動物的冷卻。現行語意是拒記，故既有條目必須仍受保護。
        if (cdActive) {
            final long capGuid = 5001L;
            final short capTarget = 999;
            // 反射註冊（allocateInstance ×2057）必須做在冷卻窗開始「之前」：capTarget 的
            // 冷卻只有 6 s，把兩千多次反射算進窗內，機器忙碌時「既有冷卻存活」那條會偶發
            // 假紅。註冊完才開窗，窗內就只剩純 hash 運算（微秒級），斷言因此是確定性的。
            final short firstFillID = 1000;
            final int overflowCount = 10;
            animal(mgr, capTarget, px, py);
            for (int id = firstFillID; id < firstFillID + BUCKET_CAP - 1 + overflowCount; id++) {
                animal(mgr, (short) id, px, py);
            }
            // 先讓 target 建立冷卻
            filter(capGuid, set(capTarget));
            // 每次呼叫的輸出上界是 vanilla 的 150，故灌到 2048 需要多次呼叫；每個 ID 都得
            // 掛真動物，假 ID 不會被 mark（那是 helper 刻意堵掉的 cap bypass，見 I3）。
            int marked = 1;
            short next = firstFillID;
            while (marked < BUCKET_CAP) {
                HashSet<Object> batch = new HashSet<>();
                for (int i = 0; i < PER_PACKET_CAP && marked < BUCKET_CAP; i++, marked++) {
                    batch.add(next++);
                }
                filter(capGuid, batch);
            }
            b = snap();
            HashSet<Object> overflow = new HashSet<>();
            for (int i = 0; i < overflowCount; i++) {
                overflow.add(next++);
            }
            Object capOut = filter(capGuid, overflow);
            a = snap();
            expect("cap：達 2048 後拒記新 ID（markRefused 遞增）", a.refused > b.refused);
            expect("cap：拒記不影響本次放行（超額 ID 仍在場＝退化成 vanilla 行為）",
                    sizeOf(capOut) == overflowCount);
            if (cdEnforce) {
                Object stillCooled = filter(capGuid, set(capTarget));
                expect("cap：既有冷卻在達上限後仍存活（不得被大量相異 ID 清掉）",
                        !contains(stillCooled, capTarget));
            }
        }

        // ── I3 cap bypass 前置：不存在的 onlineID 一律不得 mark ────────────────
        // 這條直接堵「灌大量假 ID 佔滿 bucket → 觸發淘汰 → 清掉真動物冷卻」的路徑。
        // 假 ID 不需要注入動物；若 helper 誤把它們 mark，bucket 會被灌到 2048 而開始
        // 拒記（markRefused 遞增），本斷言即紅。
        if (cdActive) {
            final long phantomGuid = 5201L;
            b = snap();
            int flooded = 0;
            short phantom = 25000;
            while (flooded < BUCKET_CAP + PER_PACKET_CAP) {
                HashSet<Object> batch = new HashSet<>();
                for (int i = 0; i < PER_PACKET_CAP; i++, flooded++) {
                    batch.add(phantom++);
                }
                filter(phantomGuid, batch);
            }
            a = snap();
            expect("cap bypass：大量不存在 ID 完全不 mark（markRefused 不動＝從未逼近上限）",
                    a.refused == b.refused);
            expect("cap bypass：大量不存在 ID 不建立任何 bucket 條目（accepted 不動）",
                    a.accepted == b.accepted);
        }

        // ── I2 每 tick 工作量上界＝vanilla 的 150（requestedCount 是 client 端無上限的 int） ──
        if (!bothOff) {
            HashSet<Object> flood = new HashSet<>();
            for (int i = 0; i < 400; i++) {
                short id = (short) (20000 + i);
                animal(mgr, id, px, py);
                flood.add(id);
            }
            Object floodOut = filter(5101L, flood);
            expect("放大面：單次過濾最多輸出 150 筆（對齊 vanilla 的 break 上限，實際 "
                    + sizeOf(floodOut) + "）", sizeOf(floodOut) == PER_PACKET_CAP);
        }

        // ── J sweep：30 s 一掃、回收 120 s 未觸碰的整個 guid bucket ────────────
        if (cdActive) {
            animal(mgr, (short) 31, px, py);
            animal(mgr, (short) 32, px, py);
            for (long g = 6001L; g <= 6003L; g++) {
                filter(g, set((short) 31));
            }
            int before = AnimalRequestGate.bucketCountForTest();
            expect("sweep 前置：至少 2 個 bucket 在冊（實際 " + before + "）", before >= 2);

            AnimalRequestGate.clockSkewForTest += 121_000L;
            filter(7001L, set((short) 32));
            int after = AnimalRequestGate.bucketCountForTest();
            expect("sweep：120 s 未觸碰的 bucket 被回收（" + before + " → " + after
                    + "），只留本次新建的那一個", after < before && after == 1);
        }

        // ── K helper 自身無異常 ────────────────────────────────────────────
        expect("helper 自身無異常（anomalies=0）", AnimalRequestGate.anomalyCount() == 0L);

        if (failed > 0) {
            System.out.println("animal-request-gate FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("animal-request-gate OK  mode=" + modeName
                + "：判別式／空集合 fast path／冷卻生命週期／guid 隔離／"
                + "範圍幾何（+48 三點釘死）／放大面上界 150"
                + "／非 Short 元素／"
                + (cdActive ? "cap 拒記／既有冷卻存活／sweep" : "COOLDOWN off 不變式（整場零 bucket）")
                + " 全數通過");
    }

    /** argv → {cooldownMode, rangeMode}；未知 argv 直接炸，避免 build.ps1 打錯字默默假綠。 */
    private static int[] expectedModes(String want) {
        switch (want) {
            case "enforce":
                return new int[] {1, 1};
            case "observe":
                return new int[] {2, 2};
            case "off":
                return new int[] {0, 0};
            case "cooldown-only":
                return new int[] {1, 0};
            case "range-only":
                return new int[] {0, 1};
            default:
                throw new IllegalArgumentException("未知 argv：" + want);
        }
    }

    /** 一律放行、且範圍計數不動的期望（帶內／邊界上／近距都是這個形狀）。 */
    private static void expectAllowedByRange(UdpConnection connection, String what, long guid,
            short onlineID) {
        Counts before = snap();
        Object out = filter(connection, guid, set(onlineID));
        Counts after = snap();
        expect(what + "：一律在場", contains(out, onlineID));
        expect(what + "：範圍計數不動", after.rgSup == before.rgSup && after.rgObs == before.rgObs);
    }

    /**
     * 模擬 vanilla 一次 {@code sendUpdateToClient}：先 {@code getPacket}（捕獲連線）、
     * 再 {@code requests.get(connection.getConnectedGUID())}。helper 消費完即
     * {@code CURRENT.remove()}，所以捕獲必須每次重做（見類別註解）。
     */
    private static Object filter(UdpConnection connection, long guid, HashSet<Object> requested) {
        AnimalRequestGate.setConnectionForTest(connection);
        return AnimalRequestGate.filterRequests(oneEntry(guid, requested), guid);
    }

    /** 沒有捕獲到連線的那一次過濾：顯式注入 null，讓「範圍跳過」是斷言而非殘留。 */
    private static Object filter(long guid, HashSet<Object> requested) {
        return filter(null, guid, requested);
    }

    private static HashMap<Object, Object> oneEntry(Object key, Object value) {
        HashMap<Object, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private static HashSet<Object> set(Object... elements) {
        HashSet<Object> s = new HashSet<>();
        for (Object e : elements) {
            s.add(e);
        }
        return s;
    }

    private static boolean contains(Object out, Object element) {
        return out instanceof Set && ((Set<?>) out).contains(element);
    }

    private static int sizeOf(Object out) {
        return out instanceof Set ? ((Set<?>) out).size() : -1;
    }

    /**
     * 最小連線：繞過建構子（見類別註解），只補齊 {@code RelevantTo} 會讀的兩個陣列。
     * 玩家在 {@code releventPos[0]}；{@code connectArea} 全為 null（否則矩形分支會先命中）。
     */
    private static UdpConnection conn(byte relevantRange, float x, float y) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.releventPos[0] = new Vector3(x, y, 0.0f);
        c.setRelevantRange(relevantRange);
        return c;
    }

    /**
     * 掛一隻動物進 {@code IsoObjectID}——刻意繞過 {@code AnimalInstanceManager.add()}
     * （會走 DebugLog／setOnlineID 的額外依賴），helper 只透過 {@code get(id)} 讀它。
     */
    private static IsoAnimal animal(AnimalInstanceManager mgr, short onlineID, float x, float y)
            throws Exception {
        IsoAnimal animal = alloc(IsoAnimal.class);
        moveTo(animal, x, y);
        mgr.getAnimals().put(onlineID, animal);
        return animal;
    }

    /** {@code getX()}／{@code getY()} 是 IsoMovingObject 的 private float 欄位直讀。 */
    private static void moveTo(IsoAnimal animal, float x, float y) throws Exception {
        java.lang.reflect.Field fx = findField(animal.getClass(), "x");
        java.lang.reflect.Field fy = findField(animal.getClass(), "y");
        fx.setAccessible(true);
        fy.setAccessible(true);
        fx.setFloat(animal, x);
        fy.setFloat(animal, y);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws Exception {
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
        java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        return (T) unsafe.allocateInstance(type);
    }

    /** 計數器都是累積值，所有斷言都用前後差值。 */
    private static final class Counts {
        final long accepted;
        final long cdSup;
        final long rgSup;
        final long cdObs;
        final long rgObs;
        final long refused;
        final long anomalies;
        final int buckets;

        Counts() {
            this.accepted = AnimalRequestGate.acceptedCount();
            this.cdSup = AnimalRequestGate.cooldownSuppressedCount();
            this.rgSup = AnimalRequestGate.rangeSuppressedCount();
            this.cdObs = AnimalRequestGate.cooldownObservedCount();
            this.rgObs = AnimalRequestGate.rangeObservedCount();
            this.refused = AnimalRequestGate.markRefusedCount();
            this.anomalies = AnimalRequestGate.anomalyCount();
            this.buckets = AnimalRequestGate.bucketCountForTest();
        }
    }

    private static Counts snap() {
        return new Counts();
    }

    private static boolean same(Counts before, Counts after) {
        return after.accepted == before.accepted
                && after.cdSup == before.cdSup
                && after.rgSup == before.rgSup
                && after.cdObs == before.cdObs
                && after.rgObs == before.rgObs
                && after.refused == before.refused
                && after.buckets == before.buckets
                && after.anomalies == before.anomalies;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "arq pass  " : "arq FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalRequestGateTest() {}
}
