package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.fields.NetObject;

/**
 * W110 門同步回送閘行為驗證（獨立 JVM；{@code MODE} 是 static final）。用法：{@code DoorSyncGuardTest off|observe|enforce}
 * （配 {@code -Dmdc.doorSyncGuard=同值}，測試自驗）。
 *
 * <p>裸 JVM 跑不動真的 {@code IsoDoor.syncIsoObject}（尾段要 GameTime、Lua 事件、格子重算），所以 {@link TestDoor} 照原版遠端分支的封包格式
 * 讀包並套用狀態（本片欄位、單門換 sprite、車庫門每片用真的 {@code NetObject.parse／getObject}、{@code setLockedByKey}、{@code changeSprite}；
 * 片取不到就像原版一樣不轉發），用 {@code forwards} 代表「重算並轉發」；替身與原版的對應由 SmokeCheck 的 W110 SHA 鎖守住。
 *
 * <p>鎖：原版負對照（相同內容照樣轉發、舊值回送把伺服器翻回去）；observe 不改狀態、position 還原、原方法照常；enforce 對 same 不呼叫原方法、
 * 寫入 lastPlayerOnlineId，對不同內容照常；試開旗標、只有血量不同、片取不到、片 sprite 不符照常；車庫門 locked≠lockedByKey 的包在各片已套用後算 same
 * （原版迴圈把本片 locked 蓋成 lockedByKey）；1 秒內第 9 個 flip 起隔離，enforce 丟包並對封包指到的門主動同步一次（代表片是 IsoThumpable 時也一樣），
 * 隔離期間丟包、期滿恢復，同一條鏈 10 秒最多一行 log；預讀例外還原 position 並照原樣；
 * beat 欄位與清掉 60 秒沒 flip 的鏈；off 只呼叫原方法、零 log。
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
            expect("enforce：第 9 個 flip 丟掉（原方法沒呼叫、狀態不變），封包指到的 g2 主動同步一次、代表片 g0 沒有（g2=" + g2.serverSyncs + " g0=" + g0.serverSyncs + "）",
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
        expect(mode + "：期滿恢復，flip 照常呼叫並套用", g2.calls == calls + 1 && g2.isOpen() != wasOpen
                && g0.isOpen() == g2.isOpen() && gt.open == g2.isOpen() && g1.sprite == (g1.isOpen() ? g1.getOpenSprite() : closed(g1)));
        for (int i = 1; i <= 8; i++) {
            send(g2, pkt(g2, !g2.isOpen(), false, 6, chain), tq + 3500 * MS + i * 10 * MS);
        }
        List<String> q = rowsContaining(" quarantine#");
        expect(mode + "：再洪流一次" + (off ? "（off 不計）" : "→ quarantine 2，同一條鏈 10 秒內只印 1 行（" + q + "）"),
                off ? DoorSyncGuard.quarantinesForTest() == 0 && q.isEmpty()
                        : DoorSyncGuard.quarantinesForTest() == 2 && q.size() == 1 && q.get(0).contains("player=Player_A")
                                && q.get(0).contains("guid=1001") && q.get(0).contains("chain=110,200,0#1")
                                && q.get(0).contains("sprite=") && q.get(0).contains("flips=9 ") && q.get(0).contains("mode=" + mode));
        if (enforce) {
            expect("enforce：第二次隔離開始再對 g2 主動同步一次", g2.serverSyncs == 2 && g0.serverSyncs == 0);
        }

        // ---- 代表片是 IsoThumpable 的鏈：主動同步的仍是封包指到的 IsoDoor ----
        IsoThumpable ht = thump(120, 200, 0);
        TestDoor h1 = door(121, 200, 0, 1);
        TestDoor h2 = door(122, 200, 0, 1);
        long tx = tq + 30 * SEC;
        for (int i = 0; i <= 8; i++) {
            send(h2, pkt(h2, !h2.isOpen(), false, 7, ht, h1, h2), tx + i * 10 * MS);
        }
        List<String> qh = rowsContaining(" chain=120,200,0#0 ");
        expect(mode + "：代表片是 IsoThumpable 的鏈" + (off ? "（off 不計）" : enforce ? "隔離時主動同步 h2 一次、h1 沒有" : "隔離、沒有主動同步"),
                off ? DoorSyncGuard.quarantinesForTest() == 0 && qh.isEmpty()
                        : DoorSyncGuard.quarantinesForTest() == 3 && qh.size() == 1 && h1.serverSyncs == 0
                                && h2.serverSyncs == (enforce ? 1 : 0) && ht.open == h2.isOpen());

        // ---- 預讀例外 ----
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
        expect(mode + "：預讀例外" + (off ? "（off 不讀）" : "計 anomalies 1、") + "還原 position、照原樣呼叫原方法（原方法自己的例外照常上拋）",
                d1.calls == calls + 1 && d1.entryPos == START && thrown != null
                        && DoorSyncGuard.anomaliesForTest() == anomalies + (off ? 0 : 1));

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
                        && DoorSyncGuard.sameSkippedForTest() == (enforce ? 5 : 0) && DoorSyncGuard.otherForTest() == 4
                        && beat.contains(" tracked=0 ") && beat.contains(" anomalies=1") && DoorSyncGuard.trackedForTest() == 0);

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
        if (DoorSyncGuard.allow(door, true, conn, bb, now)) {
            door.syncIsoObject(true, (byte) 0, conn, bb);
        }
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
