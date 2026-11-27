package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import zombie.GameTime;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.fields.NetObject;
import zombie.pathfind.CollideWithObstaclesPoly;

/**
 * W110 門同步回送閘行為驗證（獨立 JVM；{@code MODE} 是 static final）。用法：{@code DoorSyncGuardTest off|observe|enforce}
 * （配 {@code -Dmdc.doorSyncGuard=同值}，測試自驗）。
 *
 * <p>主流程用 {@link TestDoor}：照原版遠端分支的封包格式讀包並套用狀態（本片欄位、單門換 sprite、車庫門每片用真的 {@code NetObject.parse／getObject}、
 * {@code setLockedByKey}、{@code changeSprite}；片取不到就像原版一樣不轉發），用 {@code forwards} 代表「重算並轉發」；替身與原版的對應由
 * SmokeCheck 的 W110 SHA 鎖守住。{@link #vanilla} 另用 jar 裡真正的 {@code IsoDoor.syncIsoObject} 跑單門與車庫門的 same／flip 差分。
 *
 * <p>鎖：原版負對照（相同內容照樣轉發、舊值回送把伺服器翻回去）；observe 不改狀態、position 還原、原方法照常；enforce 對 same 不呼叫原方法、
 * 寫入 lastPlayerOnlineId，對不同內容照常；試開旗標、只有血量不同、片取不到、片 sprite 不符照常；車庫門 locked≠lockedByKey 的包在各片已套用後算 same
 * （原版迴圈把本片 locked 蓋成 lockedByKey）；同一扇門×同一條連線 1 秒內第 9 個 flip 起隔離，enforce 丟包並對收包的門主動同步一次，
 * 隔離期間丟包、到期時期間丟過包且門還在世界上就補同步恰一次；另一條連線、別扇門（即使被列為片）、收包物件是 IsoThumpable 照原樣；
 * 同一個 key 10 秒最多一行 log；短包計 malformed、還原 position 並照原樣；追蹤表滿時照原樣並計 overflow、1 秒後就地淘汰；
 * beat 欄位與清掉 60 秒沒 flip 的紀錄；off 只呼叫原方法、零 log。
 */
public final class DoorSyncGuardTest {
    private static final String TAG = "[MinidoracatJavaPatch][DoorSyncGuard]";
    private static final long SEC = 1_000_000_000L;
    private static final long MS = 1_000_000L;
    private static final int START = 3;

    private static int failed;
    private static ByteArrayOutputStream logBuf;
    private static final Map<String, IsoGridSquare> squares = new HashMap<>();
    private static UdpConnection conn;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        zombie.core.random.RandStandard.INSTANCE.init();
        GameServer.server = true;
        logBuf = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.All);
        DebugLog.getInstance().setStdOut(logBuf);
        boolean off = mode.equals("off");
        boolean enforce = mode.equals("enforce");
        boolean observe = mode.equals("observe");

        expect("自驗：MODE 與 argv 相符（" + DoorSyncGuard.modeName() + "）", DoorSyncGuard.MODE
                == (off ? DoorSyncGuard.OFF : enforce ? DoorSyncGuard.ENFORCE : DoorSyncGuard.OBSERVE) && (off || enforce || observe));
        expect("私有欄位都找得到：ACTIVE == MODE", DoorSyncGuard.ACTIVE == DoorSyncGuard.MODE);
        expect("parseMode：null／observe／亂填→observe，0／off→off，enforce／on→enforce",
                DoorSyncGuard.parseMode(null) == DoorSyncGuard.OBSERVE && DoorSyncGuard.parseMode("x") == DoorSyncGuard.OBSERVE
                        && DoorSyncGuard.parseMode(" OFF") == DoorSyncGuard.OFF && DoorSyncGuard.parseMode("0") == DoorSyncGuard.OFF
                        && DoorSyncGuard.parseMode("Enforce") == DoorSyncGuard.ENFORCE && DoorSyncGuard.parseMode("on") == DoorSyncGuard.ENFORCE);

        // 世界：伺服器上 IsoCell.getGridSquare 轉給 ServerMap.instance
        ServerMap.instance = alloc(TestMap.class);
        IsoWorld.instance.currentCell = alloc(IsoCell.class);
        conn = alloc(UdpConnection.class);
        Field guid = UdpConnection.class.getDeclaredField("connectedGuid");
        guid.setAccessible(true);
        guid.setLong(conn, 1001L);
        conn.setUserName("Player A");

        TestDoor d1 = door(100, 200, 0, 0);
        TestDoor w = door(150, 200, 0, 0);
        // 車庫門鏈：三片門在各自格子的 index 1，第四片是 IsoThumpable
        TestDoor g0 = door(110, 200, 0, 1);
        TestDoor g1 = door(111, 200, 0, 1);
        TestDoor g2 = door(112, 200, 0, 1);
        IsoThumpable gt = thump(113, 200, 0);
        IsoObject[] chain = {g0, g1, g2, gt};

        // ---- 原版負對照：直接呼叫原方法（未經本閘） ----
        d1.syncIsoObject(true, (byte) 0, conn, pkt(d1, false, false, 5));
        expect("原版負對照：相同內容的包照樣處理並轉發（forwards 1）、狀態不變", d1.calls == 1 && d1.forwards == 1 && !d1.isOpen());
        d1.syncIsoObject(true, (byte) 0, conn, pkt(d1, true, false, 5));
        expect("原版負對照：舊值回送（open=true）把伺服器翻回去並轉發", d1.isOpen() && d1.forwards == 2 && d1.sprite == d1.getOpenSprite());
        d1.syncIsoObject(true, (byte) 0, conn, pkt(d1, false, false, 5));
        expect("原版負對照：再翻回關門", !d1.isOpen() && d1.sprite == closed(d1) && DoorSyncGuard.doorRemoteForTest() == 0 && rows().isEmpty());

        // ---- W58 入口接線：相同內容經 SyncIsoObjectProbe.syncIsoObject ----
        int c0 = w.calls;
        SyncIsoObjectProbe.syncIsoObject(w, true, (byte) 0, conn, pkt(w, false, false, 77));
        expect(mode + "：經 W58 入口的相同內容包，原方法" + (enforce ? "不呼叫、lastPlayerOnlineId=77" : "照常呼叫"),
                enforce ? w.calls == c0 && lastId(w) == 77 : w.calls == c0 + 1);

        long t = System.nanoTime() + 1000 * SEC;
        // ---- observe 的 position 還原與不改狀態（直接問閘） ----
        ByteBufferReader same = pkt(d1, false, false, 9);
        boolean allowed = DoorSyncGuard.allow(d1, true, conn, same, t);
        expect(mode + "：allow(same) 回 " + !enforce + "；" + (enforce ? "lastPlayerOnlineId 寫成 9" : "position 還原、lastPlayerOnlineId 不變"),
                allowed == !enforce && (enforce ? lastId(d1) == 9 : same.position() == START && lastId(d1) == 5) && !d1.isOpen());

        // ---- 不同內容照常 ----
        int calls = d1.calls;
        send(d1, pkt(d1, true, false, 5), t += 10 * SEC);
        expect(mode + "：flip 照常呼叫原方法、position 從頭讀、門打開", d1.calls == calls + 1 && d1.entryPos == START && d1.isOpen());
        send(d1, pkt(d1, false, false, 5), t += 10 * SEC);
        // 試開旗標：狀態相同但帶 tryLocked
        calls = d1.calls;
        send(d1, raw(false, false, false, false, true, 500, 5), t += SEC);
        expect(mode + "：帶試開旗標的包照常呼叫", d1.calls == calls + 1 && d1.entryPos == START);
        send(d1, raw(false, false, false, false, false, 400, 5), t += SEC);
        expect(mode + "：只有血量不同的包照常呼叫、血量套用", d1.calls == calls + 2 && d1.health == 400);
        d1.health = 500;

        // ---- 車庫門 ----
        calls = g2.calls;
        send(g2, pkt(g2, false, false, 6, chain), t += SEC);
        expect(mode + "：車庫門相同內容（4 片都一致）" + (enforce ? "不呼叫" : "照常呼叫"), g2.calls == calls + (enforce ? 0 : 1));
        calls = g2.calls;
        int fw = g2.forwards;
        send(g2, raw(false, false, false, false, false, 500, 6, new int[]{999, 999, 0, 0}, ref(g0), ref(g1), ref(g2), ref(gt)), t += SEC);
        expect(mode + "：有片取不到照常呼叫（原版不轉發）", g2.calls == calls + 1 && g2.forwards == fw);
        IsoSprite keep = g1.sprite;
        g1.sprite = alloc(IsoSprite.class);
        calls = g2.calls;
        send(g2, pkt(g2, false, false, 6, chain), t += SEC);
        expect(mode + "：片的 sprite 和開關不符照常呼叫（原版 changeSprite 修回）", g2.calls == calls + 1 && g1.sprite == keep);

        // ---- 車庫門 bLocked=false、bLockedByKey=true：原版迴圈的 setLockedByKey 把本片 locked 蓋回 true，處理後不變 → same ----
        TestDoor k0 = door(130, 200, 0, 1);
        TestDoor k1 = door(131, 200, 0, 1);
        k0.setLockedByKey(true);
        k1.setLockedByKey(true);
        k1.syncIsoObject(true, (byte) 0, conn, raw(false, false, true, false, false, 500, 8, ref(k1), ref(k0)));
        expect("原版負對照：車庫門 locked=false／lockedByKey=true 的包處理後各片 locked、lockedByKey 仍是 true、開關與 sprite 不變",
                k0.isLocked() && k0.isLockedByKey() && k1.isLocked() && k1.isLockedByKey() && !k0.isOpen() && !k1.isOpen()
                        && k0.sprite == closed(k0) && k1.sprite == closed(k1));
        calls = k1.calls;
        long sameBefore = DoorSyncGuard.sameForTest();
        long flipBefore = DoorSyncGuard.flipForTest();
        send(k1, raw(false, false, true, false, false, 500, 8, ref(k1), ref(k0)), t += SEC);
        expect(mode + "：同一包經閘" + (off ? "照常呼叫" : "算 same 不算 flip" + (enforce ? "、不呼叫原方法" : "、照常呼叫")),
                k1.calls == calls + (enforce ? 0 : 1) && DoorSyncGuard.flipForTest() == flipBefore
                        && DoorSyncGuard.sameForTest() == sameBefore + (off ? 0 : 1) && k1.isLocked() && k1.isLockedByKey());

        // ---- 洪流：9 個 flip 在 1 秒內 ----
        long tq = t += 10 * SEC;
        int g2Before = g2.calls;
        for (int i = 0; i < 8; i++) {
            send(g2, pkt(g2, !g2.isOpen(), false, 6, chain), tq + i * 10 * MS);
        }
        expect(mode + "：前 8 個 flip 都照常呼叫、還沒隔離", g2.calls == g2Before + 8 && DoorSyncGuard.quarantinesForTest() == 0);
        boolean openBefore = g2.isOpen();
        send(g2, pkt(g2, !g2.isOpen(), false, 6, chain), tq + 80 * MS);
        if (enforce) {
            expect("enforce：第 9 個 flip 丟掉（原方法沒呼叫、狀態不變），收包的 g2 主動同步一次、鏈上的 g0 沒有（g2=" + g2.serverSyncs + " g0=" + g0.serverSyncs + "）",
                    g2.calls == g2Before + 8 && g2.isOpen() == openBefore && g2.serverSyncs == 1 && g0.serverSyncs == 0
                            && DoorSyncGuard.quarantinesForTest() == 1 && DoorSyncGuard.droppedForTest() == 1);
        } else {
            expect(mode + "：第 9 個 flip 照常呼叫、沒有主動同步" + (observe ? "；計 quarantine 1、wouldDrop 1" : ""),
                    g2.calls == g2Before + 9 && g2.serverSyncs == 0
                            && DoorSyncGuard.quarantinesForTest() == (observe ? 1 : 0) && DoorSyncGuard.wouldDropForTest() == (observe ? 1 : 0));
        }
        calls = g2.calls;
        send(g2, pkt(g2, g2.isOpen(), false, 6, chain), tq + 1500 * MS);
        send(g2, pkt(g2, !g2.isOpen(), false, 6, chain), tq + 1600 * MS);
        expect(mode + "：隔離期間（same＋flip 各一）" + (enforce ? "都丟掉、dropped 3" : "照常呼叫" + (observe ? "、wouldDrop 3" : "")),
                enforce ? g2.calls == calls && DoorSyncGuard.droppedForTest() == 3
                        : g2.calls == calls + 2 && DoorSyncGuard.wouldDropForTest() == (observe ? 3 : 0));
        calls = g2.calls;
        boolean wasOpen = g2.isOpen();
        send(g2, pkt(g2, !g2.isOpen(), false, 6, chain), tq + 3500 * MS);
        expect(mode + "：期滿恢復，flip 照常呼叫並套用；" + (enforce ? "期間丟過包 → 先補同步 g2 一次（resyncs 1）" : "沒有補同步"),
                g2.calls == calls + 1 && g2.isOpen() != wasOpen
                && g0.isOpen() == g2.isOpen() && gt.open == g2.isOpen() && g1.sprite == (g1.isOpen() ? g1.getOpenSprite() : closed(g1))
                && g2.serverSyncs == (enforce ? 2 : 0) && DoorSyncGuard.resyncsForTest() == (enforce ? 1 : 0));
        for (int i = 1; i <= 8; i++) {
            send(g2, pkt(g2, !g2.isOpen(), false, 6, chain), tq + 3500 * MS + i * 10 * MS);
        }
        List<String> q = rowsContaining(" quarantine#");
        expect(mode + "：再洪流一次" + (off ? "（off 不計）" : "→ quarantine 2，同一扇門×連線 10 秒內只印 1 行，印收包門自己的格子與 index（" + q + "）"),
                off ? DoorSyncGuard.quarantinesForTest() == 0 && q.isEmpty()
                        : DoorSyncGuard.quarantinesForTest() == 2 && q.size() == 1 && q.get(0).contains("player=Player_A")
                                && q.get(0).contains("guid=1001") && q.get(0).contains(" door=112,200,0#1 ")
                                && q.get(0).contains("sprite=") && q.get(0).contains("flips=9 ") && q.get(0).contains("mode=" + mode));
        if (enforce) {
            expect("enforce：第二次隔離開始再對 g2 主動同步一次", g2.serverSyncs == 3 && g0.serverSyncs == 0);
        }

        // ---- 鏈上有 IsoThumpable 片：隔離與主動同步都只針對收包的 IsoDoor ----
        IsoThumpable ht = thump(120, 200, 0);
        TestDoor h1 = door(121, 200, 0, 1);
        TestDoor h2 = door(122, 200, 0, 1);
        long tx = tq + 30 * SEC;
        for (int i = 0; i <= 8; i++) {
            send(h2, pkt(h2, !h2.isOpen(), false, 7, ht, h1, h2), tx + i * 10 * MS);
        }
        List<String> qh = rowsContaining(" door=122,200,0#1 ");
        expect(mode + "：鏈上有 IsoThumpable 片" + (off ? "（off 不計）" : enforce ? "，隔離時主動同步收包的 h2 一次、h1 沒有" : "，隔離、沒有主動同步"),
                off ? DoorSyncGuard.quarantinesForTest() == 0 && qh.isEmpty()
                        : DoorSyncGuard.quarantinesForTest() == 3 && qh.size() == 1 && h1.serverSyncs == 0
                                && h2.serverSyncs == (enforce ? 1 : 0) && ht.open == h2.isOpen());

        // ---- 短包 ----
        calls = d1.calls;
        long anomalies = DoorSyncGuard.anomaliesForTest();
        ByteBuffer shortBuf = ByteBuffer.allocate(START + 2);
        shortBuf.position(START);
        ByteBufferReader truncated = new ByteBufferReader(shortBuf);
        truncated.position(START);
        Throwable thrown = null;
        try {
            send(d1, truncated, tq + 20 * SEC);
        } catch (BufferUnderflowException e) {
            thrown = e;
        }
        expect(mode + "：短包" + (off ? "（off 不讀）" : "計 malformed 1、不計 anomalies、") + "還原 position、原方法恰呼叫一次（原方法自己的例外照常上拋）",
                d1.calls == calls + 1 && d1.entryPos == START && thrown != null
                        && DoorSyncGuard.malformedForTest() == (off ? 0 : 1) && DoorSyncGuard.anomaliesForTest() == anomalies);

        // ---- beat ----
        long tb = tq + 100 * SEC;
        DoorSyncGuard.armBeatForTest(tb);
        send(w, pkt(w, false, false, 77), tb);
        List<String> beats = rowsContaining(" beat ");
        String beat = beats.isEmpty() ? "" : beats.get(beats.size() - 1);
        expect(mode + "：beat（" + beat + "）", off ? rows().isEmpty() && DoorSyncGuard.doorRemoteForTest() == 0
                : beat.contains("mode=" + mode + " ") && beat.contains(" quarantines=3 ") && beat.contains(" dropped=" + (enforce ? 5 : 0) + " ")
                        && beat.contains(" wouldDrop=" + (enforce ? 0 : 5) + " ") && beat.contains(" maxFlipsPerSec=9 ")
                        && beat.contains(" sameSkipped=" + (enforce ? 4 : 0) + " ") && beat.contains(" other=4 ")
                        && beat.contains(" resyncs=" + (enforce ? 1 : 0) + " ") && beat.contains(" overflow=0 ") && beat.contains(" malformed=1 ")
                        && DoorSyncGuard.sameSkippedForTest() == (enforce ? 5 : 0) && DoorSyncGuard.otherForTest() == 4
                        && beat.contains(" tracked=0 ") && beat.contains(" anomalies=0") && DoorSyncGuard.trackedForTest() == 0
                        && DoorSyncGuard.quarantinedForTest() == 0);

        perKey(mode, tb + 10 * SEC);
        vanilla(mode, tb + 400 * SEC);

        for (String row : rows()) {
            System.out.println("log  " + row.substring(row.indexOf(TAG)));
        }
        if (failed > 0) {
            System.out.println("door-sync-guard FAIL " + failed + " 項（mode=" + mode + "）");
            System.exit(1);
        }
        System.out.println("door-sync-guard OK mode=" + mode);
    }

    /** 同 W58 入口：閘回 true 才呼叫原方法。 */
    private static void send(IsoDoor door, ByteBufferReader bb, long now) {
        send(conn, door, bb, now);
    }

    private static void send(UdpConnection from, IsoDoor door, ByteBufferReader bb, long now) {
        if (DoorSyncGuard.allow(door, true, from, bb, now)) {
            door.syncIsoObject(true, (byte) 0, from, bb);
        }
    }

    /** 隔離對象是「這扇 IsoDoor × 這條連線」：兩條連線、到期補同步、別扇門的片、IsoThumpable 收包、追蹤表上限。 */
    private static void perKey(String mode, long t0) throws Exception {
        boolean off = mode.equals("off");
        boolean enforce = mode.equals("enforce");
        boolean observe = mode.equals("observe");
        UdpConnection connB = connection(1002L, "Player B");
        IsoObject plain = alloc(IsoObject.class);

        // ---- 兩條連線對同一扇門 ----
        TestDoor m = door(140, 200, 0, 0);
        long q0 = DoorSyncGuard.quarantinesForTest();
        for (int i = 0; i < 4; i++) {
            send(connB, m, pkt(m, !m.isOpen(), false, 2), t0 + i * 10 * MS);
        }
        for (int i = 0; i < 8; i++) {
            send(m, pkt(m, !m.isOpen(), false, 1), t0 + (40 + i * 10) * MS);
        }
        expect(mode + "：B 的 4 個 flip 不算進 A 的視窗（合算是 12 個）：A 的 8 個 flip 後仍沒有隔離、12 包都轉給原方法",
                DoorSyncGuard.quarantinesForTest() == q0 && m.calls == 12);
        send(m, pkt(m, !m.isOpen(), false, 1), t0 + 120 * MS);
        int callsM = m.calls;
        boolean openM = m.isOpen();
        send(connB, m, pkt(m, !m.isOpen(), false, 2), t0 + 130 * MS);
        expect(mode + "：A 的第 9 個 flip " + (off ? "（off 不計）" : "隔離 A×這扇門") + "；B 對同一扇門的 flip 照常轉給原方法並套用",
                DoorSyncGuard.quarantinesForTest() == q0 + (off ? 0 : 1) && m.calls == callsM + 1 && m.isOpen() != openM
                        && m.serverSyncs == (enforce ? 1 : 0));
        long drop0 = DoorSyncGuard.droppedForTest();
        long would0 = DoorSyncGuard.wouldDropForTest();
        callsM = m.calls;
        send(m, pkt(m, !m.isOpen(), false, 1), t0 + 140 * MS);
        expect(mode + "：A 隔離中的下一包" + (enforce ? "丟掉（dropped +1）" : "照常呼叫" + (observe ? "（wouldDrop +1）" : "")),
                enforce ? m.calls == callsM && DoorSyncGuard.droppedForTest() == drop0 + 1
                        : m.calls == callsM + 1 && DoorSyncGuard.wouldDropForTest() == would0 + (observe ? 1 : 0));

        // ---- 到期補同步：任何一次呼叫（收包物件不是門也一樣）先處理到期 ----
        long r0 = DoorSyncGuard.resyncsForTest();
        ByteBufferReader notDoor = pkt(m, false, false, 1);
        boolean passPlain = DoorSyncGuard.allow(plain, true, conn, notDoor, t0 + 3200 * MS);
        expect(mode + "：隔離到期後下一次呼叫" + (enforce ? "補同步 m 恰一次（期間丟過包）" : "不補同步（沒有丟過包）") + "；非門物件照原樣",
                passPlain && notDoor.position() == START && m.serverSyncs == (enforce ? 2 : 0)
                        && DoorSyncGuard.resyncsForTest() == r0 + (enforce ? 1 : 0) && DoorSyncGuard.quarantinedForTest() == 0);
        DoorSyncGuard.allow(plain, true, conn, null, t0 + 3300 * MS);
        expect(mode + "：再呼叫不會再補同步", m.serverSyncs == (enforce ? 2 : 0) && DoorSyncGuard.resyncsForTest() == r0 + (enforce ? 1 : 0));

        // ---- 隔離期間沒丟過包：到期不補 ----
        TestDoor r1 = door(141, 200, 0, 0);
        long t1 = t0 + 10 * SEC;
        for (int i = 0; i <= 8; i++) {
            send(r1, pkt(r1, !r1.isOpen(), false, 1), t1 + i * 10 * MS);
        }
        DoorSyncGuard.allow(plain, true, conn, null, t1 + 3200 * MS);
        expect(mode + "：隔離期間沒有丟過包（只有開始那一包），到期不補同步",
                r1.serverSyncs == (enforce ? 1 : 0) && DoorSyncGuard.resyncsForTest() == r0 + (enforce ? 1 : 0)
                        && DoorSyncGuard.quarantinedForTest() == 0);

        // ---- 門已不在世界上：到期不補 ----
        TestDoor r2 = door(142, 200, 0, 0);
        long t2 = t0 + 20 * SEC;
        for (int i = 0; i <= 9; i++) {
            send(r2, pkt(r2, !r2.isOpen(), false, 1), t2 + i * 10 * MS);
        }
        r2.square.getObjects().remove(r2);
        DoorSyncGuard.allow(plain, true, conn, null, t2 + 3200 * MS);
        expect(mode + "：隔離期間丟過包但門已移出格子（getObjectIndex == -1），到期不補同步",
                r2.serverSyncs == (enforce ? 1 : 0) && DoorSyncGuard.resyncsForTest() == r0 + (enforce ? 1 : 0)
                        && DoorSyncGuard.quarantinedForTest() == 0);

        // ---- 封包宣告別扇門的片：不改那扇門的紀錄；隔離中收包物件是 IsoThumpable 照原樣 ----
        TestDoor x = door(160, 200, 0, 1);
        TestDoor y = door(161, 200, 0, 1);
        IsoThumpable xt = thump(162, 200, 0);
        long t3 = t0 + 30 * SEC;
        long q3 = DoorSyncGuard.quarantinesForTest();
        for (int i = 0; i <= 8; i++) {
            send(x, pkt(x, !x.isOpen(), false, 3, x, y, xt), t3 + i * 10 * MS);
        }
        int callsY = y.calls;
        send(y, pkt(y, !y.isOpen(), false, 3, y, x, xt), t3 + 100 * MS);
        expect(mode + "：x 的封包都列出 y 這片；x 隔離" + (off ? "（off 不計）" : "") + "後 y 自己收的 flip 照常轉給原方法、y 沒有被隔離",
                DoorSyncGuard.quarantinesForTest() == q3 + (off ? 0 : 1) && y.calls == callsY + 1 && y.serverSyncs == 0);
        long remote0 = DoorSyncGuard.doorRemoteForTest();
        ByteBufferReader thumpPkt = pkt(x, !x.isOpen(), false, 3, x, y, xt);
        boolean thumpPass = DoorSyncGuard.allow(xt, true, conn, thumpPkt, t3 + 150 * MS);
        expect(mode + "：x 隔離中，收包物件是同鏈 IsoThumpable 的同步照原樣（回 true、position 不動、不計 doorRemote）",
                thumpPass && thumpPkt.position() == START && DoorSyncGuard.doorRemoteForTest() == remote0);

        // ---- 追蹤表上限：beat 先清空，再塞滿 MAX_TRACKED 筆 1 秒內有 flip 的紀錄 ----
        long t4 = t0 + 200 * SEC;
        DoorSyncGuard.armBeatForTest(t4);
        DoorSyncGuard.allow(plain, true, conn, null, t4);
        expect(mode + "：beat 清掉 60 秒沒 flip 的紀錄 → tracked 0", DoorSyncGuard.trackedForTest() == 0);
        for (int i = 0; i < DoorSyncGuard.MAX_TRACKED; i++) {
            TestDoor f = door(1000 + i, 300, 0, 0);
            send(f, pkt(f, true, false, 1), t4 + MS);
        }
        long over0 = DoorSyncGuard.overflowForTest();
        TestDoor fx = door(999, 300, 0, 0);
        send(fx, pkt(fx, true, false, 1), t4 + 500 * MS);
        expect(mode + "：表滿（" + DoorSyncGuard.trackedForTest() + " 筆、都在 1 秒內有 flip）時新門照原版放行並套用" + (off ? "" : "、overflow +1、不建紀錄"),
                fx.calls == 1 && fx.isOpen() && DoorSyncGuard.overflowForTest() == over0 + (off ? 0 : 1)
                        && DoorSyncGuard.trackedForTest() == (off ? 0 : DoorSyncGuard.MAX_TRACKED));
        TestDoor fy = door(998, 300, 0, 0);
        send(fy, pkt(fy, true, false, 1), t4 + 1500 * MS);
        expect(mode + "：1 秒後再有新門：先就地淘汰 1 秒內沒 flip、不在隔離的紀錄，再建紀錄（tracked " + DoorSyncGuard.trackedForTest() + "）",
                fy.calls == 1 && DoorSyncGuard.overflowForTest() == over0 + (off ? 0 : 1) && DoorSyncGuard.trackedForTest() == (off ? 0 : 1));
    }

    /**
     * 原版差分：jar 裡真正的 {@code IsoDoor.syncIsoObject(true, …)}（不是替身）。只換掉對外輸出：送包對象是空的連線表、
     * Lua 環境未載入（{@code triggerEvent} 直接 return）、伺服器上 {@code WeatherFxMask} 直接 return；格子重算照原版跑在測試格子上，
     * 車庫門片的路徑網格更新因 chunk 標成未載入而直接 return。尾段有沒有跑到看 {@code GameTime.lightSourceUpdate} 是否被設成 100。
     */
    private static void vanilla(String mode, long t0) throws Exception {
        boolean off = mode.equals("off");
        boolean enforce = mode.equals("enforce");
        UdpEngine engine = alloc(UdpEngine.class);
        set(UdpEngine.class, engine, "connections", new ArrayList<UdpConnection>());
        GameServer.udpEngine = engine;
        if (GameTime.instance == null) {
            GameTime.instance = alloc(GameTime.class);
        }
        IsoDoor v = vdoor(170, 200, 0);

        // same：真原版跑完只有 lastPlayerOnlineId 變（尾段有跑到）
        Map<String, Object> before = snapshot(v);
        GameTime.instance.lightSourceUpdate = 0.0F;
        v.syncIsoObject(true, (byte) 0, conn, pkt(v, false, false, 42));
        Set<String> diff = changed(before, snapshot(v));
        expect("原版差分：真原版處理 same 包後只有 lastPlayerOnlineId 變（" + diff + "），尾段有跑到",
                diff.equals(Set.of("IsoDoor.lastPlayerOnlineId")) && lastId(v) == 42 && GameTime.instance.lightSourceUpdate == 100.0F);

        // 同一包經閘：分類成 same；enforce 的捷徑結果和真原版相同（只寫 lastPlayerOnlineId）
        long same0 = DoorSyncGuard.sameForTest();
        before = snapshot(v);
        GameTime.instance.lightSourceUpdate = 0.0F;
        ByteBufferReader bb = pkt(v, false, false, 43);
        if (DoorSyncGuard.allow(v, true, conn, bb, t0)) {
            v.syncIsoObject(true, (byte) 0, conn, bb);
        }
        diff = changed(before, snapshot(v));
        expect(mode + "：原版差分 same 包經閘" + (off ? "（off 不分類）" : "分類成 same") + (enforce ? "、不呼叫原版（尾段沒跑）" : "、呼叫原版")
                        + "，結果都只有 lastPlayerOnlineId 變（" + diff + "）",
                DoorSyncGuard.sameForTest() == same0 + (off ? 0 : 1) && diff.equals(Set.of("IsoDoor.lastPlayerOnlineId")) && lastId(v) == 43
                        && GameTime.instance.lightSourceUpdate == (enforce ? 0.0F : 100.0F));

        // flip（開門）：分類成 flip、照常呼叫原版、欄位照封包變
        long flip0 = DoorSyncGuard.flipForTest();
        bb = pkt(v, true, false, 44);
        if (DoorSyncGuard.allow(v, true, conn, bb, t0 + 10 * SEC)) {
            v.syncIsoObject(true, (byte) 0, conn, bb);
        }
        expect(mode + "：原版差分 flip（開門）" + (off ? "" : "分類成 flip、") + "真原版把門打開、sprite 換成 openSprite",
                DoorSyncGuard.flipForTest() == flip0 + (off ? 0 : 1) && v.isOpen() && v.sprite == v.getOpenSprite() && lastId(v) == 44);

        // flip（上鎖）
        bb = raw(true, true, true, false, false, 500, 45);
        if (DoorSyncGuard.allow(v, true, conn, bb, t0 + 20 * SEC)) {
            v.syncIsoObject(true, (byte) 0, conn, bb);
        }
        expect(mode + "：原版差分 flip（上鎖）" + (off ? "" : "分類成 flip、") + "真原版把 locked／lockedByKey 都設成 true、門仍開著",
                DoorSyncGuard.flipForTest() == flip0 + (off ? 0 : 2) && v.isLocked() && v.isLockedByKey() && v.isOpen() && lastId(v) == 45);

        // 上鎖後的 same：真原版不再改任何東西
        before = snapshot(v);
        v.syncIsoObject(true, (byte) 0, conn, raw(true, true, true, false, false, 500, 46));
        diff = changed(before, snapshot(v));
        expect("原版差分：開著且上鎖的門收到相同內容，真原版只改 lastPlayerOnlineId（" + diff + "）", diff.equals(Set.of("IsoDoor.lastPlayerOnlineId")));

        // 車庫門路徑（真原版）：兩片都 locked＝lockedByKey＝true，收到 bLocked=false、bLockedByKey=true、本片也列在片裡的包
        IsoDoor g0 = vdoor(180, 200, 0);
        IsoDoor g1 = vdoor(181, 200, 0);
        // 片迴圈的 PolygonalMap2.squareChanged：PathfindNative 讀 chunk.loadedBits（0＝未載入就 return）、FMODAmbientWalls 讀 levels；
        // 尾段 RecalcProperties 在 chunk 非 null 時清 collision
        IsoChunk chunk = alloc(IsoChunk.class);
        set(IsoChunk.class, chunk, "levels", new IsoChunkLevel[1]);
        set(IsoChunk.class, chunk, "collision", new CollideWithObstaclesPoly.ChunkData());
        g0.square.chunk = chunk;
        g1.square.chunk = chunk;
        g0.setLockedByKey(true);
        g1.setLockedByKey(true);
        Map<String, Object> b0 = snapshot(g0);
        Map<String, Object> b1 = snapshot(g1);
        GameTime.instance.lightSourceUpdate = 0.0F;
        g1.syncIsoObject(true, (byte) 0, conn, raw(false, false, true, false, false, 500, 8, ref(g1), ref(g0)));
        Set<String> d0 = changed(b0, snapshot(g0));
        Set<String> d1 = changed(b1, snapshot(g1));
        expect("原版差分（車庫門）：真原版處理 bLocked=false／bLockedByKey=true、本片也列在片裡的包，收包片只改 lastPlayerOnlineId（" + d1
                        + "）、另一片不變（" + d0 + "），尾段有跑到",
                d1.equals(Set.of("IsoDoor.lastPlayerOnlineId")) && d0.isEmpty() && g1.isLocked() && g1.isLockedByKey()
                        && GameTime.instance.lightSourceUpdate == 100.0F);
        long same1 = DoorSyncGuard.sameForTest();
        b0 = snapshot(g0);
        b1 = snapshot(g1);
        GameTime.instance.lightSourceUpdate = 0.0F;
        bb = raw(false, false, true, false, false, 500, 9, ref(g1), ref(g0));
        if (DoorSyncGuard.allow(g1, true, conn, bb, t0 + 30 * SEC)) {
            g1.syncIsoObject(true, (byte) 0, conn, bb);
        }
        d0 = changed(b0, snapshot(g0));
        d1 = changed(b1, snapshot(g1));
        expect(mode + "：原版差分（車庫門）同內容經閘" + (off ? "（off 不分類）" : "分類成 same") + (enforce ? "、不呼叫原版" : "、呼叫原版")
                        + "，結果都是收包片只改 lastPlayerOnlineId（" + d1 + "）、另一片不變（" + d0 + "）",
                DoorSyncGuard.sameForTest() == same1 + (off ? 0 : 1) && d1.equals(Set.of("IsoDoor.lastPlayerOnlineId")) && d0.isEmpty()
                        && lastId(g1) == 9 && GameTime.instance.lightSourceUpdate == (enforce ? 0.0F : 100.0F));
    }

    /** 真 IsoDoor（不是替身），關著、沒鎖、血量 500，放在格子的第 0 個。 */
    private static IsoDoor vdoor(int x, int y, int z) throws Exception {
        IsoGridSquare sq = square(x, y, z);
        // 尾段 RecalcProperties／RecalcAllWithNeighbours 要讀格子的 PropertyContainer 與 nav[8]（建構子才會建）
        set(IsoGridSquare.class, sq, "properties", new zombie.core.properties.PropertyContainer());
        set(IsoGridSquare.class, sq, "nav", new IsoGridSquare[8]);
        IsoDoor d = alloc(IsoDoor.class);
        d.square = sq;
        d.health = 500;
        d.setOpenSprite(alloc(IsoSprite.class));
        set(IsoDoor.class, d, "closedSprite", alloc(IsoSprite.class));
        d.sprite = closed(d);
        sq.getObjects().add(d);
        return d;
    }

    /** 物件（含父類別）全部實例欄位的淺快照：基本型別比值，參照比 identity。 */
    private static Map<String, Object> snapshot(Object o) throws Exception {
        Map<String, Object> out = new HashMap<>();
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    out.put(c.getSimpleName() + "." + f.getName(), f.get(o));
                }
            }
        }
        return out;
    }

    private static Set<String> changed(Map<String, Object> a, Map<String, Object> b) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Object> e : a.entrySet()) {
            Object x = e.getValue();
            Object y = b.get(e.getKey());
            boolean boxed = x instanceof Number || x instanceof Boolean || x instanceof Character;
            if (boxed ? !x.equals(y) : x != y) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static UdpConnection connection(long guid, String name) throws Exception {
        UdpConnection c = alloc(UdpConnection.class);
        Field f = UdpConnection.class.getDeclaredField("connectedGuid");
        f.setAccessible(true);
        f.setLong(c, guid);
        c.setUserName(name);
        return c;
    }

    /** 原版 IsoDoor.syncIsoObject 遠端分支的替身（雙開門與尾段重算略過）；forwards＝走到「轉發給其他連線」那一步。 */
    public static class TestDoor extends IsoDoor {
        int calls;
        int forwards;
        int serverSyncs;
        int entryPos;

        /** 只為了能編譯：測試一律用 Unsafe 配置，不跑建構子。 */
        TestDoor() {
            super((IsoCell) null);
        }

        @Override
        public void syncIsoObject(boolean remote, byte val, UdpConnection source, ByteBufferReader bb) {
            if (!remote) {
                serverSyncs++;
                return;
            }
            calls++;
            entryPos = bb.position();
            boolean open = bb.getBoolean();
            boolean locked = bb.getBoolean();
            boolean lockedByKey = bb.getBoolean();
            bb.getBoolean();
            bb.getBoolean();
            int health = bb.getInt();
            setLastId(this, bb.getShort());
            List<NetObject> parts = new ArrayList<>();
            if (bb.getBoolean()) {
                byte n = bb.getByte();
                for (byte i = 0; i < n; i++) {
                    NetObject no = new NetObject();
                    no.parse(bb, null);
                    parts.add(no);
                }
            }
            this.locked = locked;
            this.lockedByKey = lockedByKey;
            this.health = health;
            if (open != isOpen()) {
                setOpen(open);
                sprite = isOpen() ? getOpenSprite() : closed(this);
            }
            for (NetObject no : parts) {
                IsoObject p = no.getObject();
                if (p == null || p.getSquare() == null) {
                    return;
                }
                if (p instanceof IsoDoor d) {
                    d.setOpen(isOpen());
                    d.setLockedByKey(lockedByKey);
                    d.changeSprite(d);
                } else if (p instanceof IsoThumpable th) {
                    th.open = isOpen();
                    th.setLockedByKey(lockedByKey);
                    th.changeSprite(th);
                }
            }
            forwards++;
        }
    }

    /** 伺服器取片：IsoCell.getGridSquare 在 server 上直接轉給這裡。 */
    public static class TestMap extends ServerMap {
        @Override
        public IsoGridSquare getGridSquare(int x, int y, int z) {
            return squares.get(x + "," + y + "," + z);
        }
    }

    private static IsoGridSquare square(int x, int y, int z) throws Exception {
        IsoGridSquare sq = squares.get(x + "," + y + "," + z);
        if (sq == null) {
            sq = alloc(IsoGridSquare.class);
            sq.x = x;
            sq.y = y;
            sq.z = z;
            set(IsoGridSquare.class, sq, "objects", new ArrayList<IsoObject>());
            squares.put(x + "," + y + "," + z, sq);
        }
        return sq;
    }

    /** 關著、沒鎖、血量 500 的門，放在格子的第 index 個（前面塞填充物件）。 */
    private static TestDoor door(int x, int y, int z, int index) throws Exception {
        IsoGridSquare sq = square(x, y, z);
        while (sq.getObjects().size() < index) {
            IsoObject filler = alloc(IsoObject.class);
            filler.square = sq;
            sq.getObjects().add(filler);
        }
        TestDoor d = alloc(TestDoor.class);
        d.square = sq;
        d.health = 500;
        d.setOpenSprite(alloc(IsoSprite.class));
        set(IsoDoor.class, d, "closedSprite", alloc(IsoSprite.class));
        d.sprite = closed(d);
        sq.getObjects().add(d);
        return d;
    }

    private static IsoThumpable thump(int x, int y, int z) throws Exception {
        IsoGridSquare sq = square(x, y, z);
        IsoThumpable t = alloc(IsoThumpable.class);
        t.square = sq;
        t.openSprite = alloc(IsoSprite.class);
        set(IsoThumpable.class, t, "closedSprite", alloc(IsoSprite.class));
        t.sprite = (IsoSprite) get(IsoThumpable.class, t, "closedSprite");
        sq.getObjects().add(t);
        return t;
    }

    private static int[] ref(IsoObject o) {
        return new int[]{o.square.x, o.square.y, o.square.z, o.getObjectIndex()};
    }

    private static ByteBufferReader pkt(IsoDoor d, boolean open, boolean lockedByKey, int lastId, IsoObject... parts) {
        int[][] refs = new int[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            refs[i] = ref(parts[i]);
        }
        return raw(open, lockedByKey, lockedByKey, false, false, d.health, lastId, refs);
    }

    /** 原版 syncIsoObjectSend 寫的內容（x、y、z、index、exist、state 之後那段），前面墊 START 個位元組。 */
    private static ByteBufferReader raw(boolean open, boolean locked, boolean lockedByKey, boolean tryLocked,
            boolean tryBarricaded, int health, int lastId, int[]... refs) {
        ByteBuffer b = ByteBuffer.allocate(256);
        b.position(START);
        b.put((byte) (open ? 1 : 0)).put((byte) (locked ? 1 : 0)).put((byte) (lockedByKey ? 1 : 0));
        b.put((byte) (tryLocked ? 1 : 0)).put((byte) (tryBarricaded ? 1 : 0));
        b.putInt(health).putShort((short) lastId).put((byte) (refs.length > 0 ? 1 : 0));
        if (refs.length > 0) {
            b.put((byte) refs.length);
            for (int[] r : refs) {
                b.put((byte) 1).putShort((short) r[3]).putInt(r[0]).putInt(r[1]).put((byte) r[2]);
            }
        }
        b.flip();
        ByteBufferReader reader = new ByteBufferReader(b);
        reader.position(START);
        return reader;
    }

    private static IsoSprite closed(IsoDoor d) {
        return (IsoSprite) get(IsoDoor.class, d, "closedSprite");
    }

    private static short lastId(IsoDoor d) {
        return (short) get(IsoDoor.class, d, "lastPlayerOnlineId");
    }

    private static void setLastId(IsoDoor d, short v) {
        try {
            set(IsoDoor.class, d, "lastPlayerOnlineId", v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Class<?> owner, Object target, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings({"unchecked", "deprecation", "removal"})
    private static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    private static List<String> rows() {
        List<String> out = new ArrayList<>();
        for (String line : logBuf.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.contains(TAG)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> rowsContaining(String token) {
        List<String> out = new ArrayList<>();
        for (String row : rows()) {
            if (row.contains(token)) {
                out.add(row);
            }
        }
        return out;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tap pass  " : "tap FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private DoorSyncGuardTest() {
    }
}
