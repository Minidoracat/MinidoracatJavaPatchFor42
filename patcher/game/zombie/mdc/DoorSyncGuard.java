package zombie.mdc;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import zombie.core.MdcTimedActionProbe;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.sprite.IsoSprite;

/**
 * W110 門同步回送閘（2026-11-27；docs/patches.md 2dr）。掛在 W58 {@link SyncIsoObjectProbe#syncIsoObject} 裡、record 之後、
 * 呼叫原方法之前；不加手術點。
 *
 * <p><b>原版</b>：{@code IsoDoor.syncIsoObject} 的遠端分支把封包的開關、鎖、血量蓋到門上，車庫門再對鏈上每片
 * {@code setLockedByKey}／{@code changeSprite}，然後轉給附近其他連線。客戶端收到後對鏈上別片 {@code setLockedByKey} 值有變就
 * {@code sync(3|4)} 回送那一片；兩個以上客戶端時，晚到的舊值回送把伺服器翻回舊值並轉發，客戶端互相觸發。
 *
 * <p><b>分類</b>（只看 {@code IsoDoor}、遠端、{@code bb != null}、在格子上的門；其餘原樣）：預讀封包（記下 position）。
 * {@code same}＝原版處理這一包後除了 {@code lastPlayerOnlineId} 以外的物件欄位都不會變；{@code flip}＝本片或任一片的開關或鎖和封包不同；
 * 其餘（只有血量不同、帶「試開」旗標、有片取不到或不是門，計 {@code other}）照原樣。本片也列在鏈裡時，原版迴圈的 {@code setLockedByKey}
 * 會把本片 {@code locked} 蓋成封包的 lockedByKey，所以那時不拿本片 {@code locked} 和封包的 locked 比。每條鏈（車庫門取 (x,y,z,index) 最小的片，否則本片）1 秒視窗內
 * 第 {@link #FLIP_LIMIT}+1 個 flip 起隔離 {@link #QUARANTINE_NS}。
 *
 * <p><b>模式</b> {@code -Dmdc.doorSyncGuard=off|observe|enforce}（預設 observe，和 {@code -Dmdc.syncIsoObjectProbe} 互相獨立）：
 * observe 只計數、還原 position、照原樣呼叫原方法；enforce 對 {@code same} 只寫 {@code lastPlayerOnlineId}、不呼叫原方法，
 * 隔離開始那一包丟掉並讓伺服器對封包指到的門主動同步一次（{@code IsoDoor} 的同步帶整條鏈），隔離期間這條鏈的遠端門同步一律丟掉。
 *
 * <p>只在主執行緒被呼叫，不加鎖。分類與記錄的 RuntimeException／LinkageError 只計 anomalies、還原 position、照原樣；
 * Error 照常外拋；原方法與主動同步不在 try 內。
 */
public final class DoorSyncGuard {
    static final int OFF = 0;
    static final int OBSERVE = 1;
    static final int ENFORCE = 2;
    static final String RAW = System.getProperty("mdc.doorSyncGuard");
    static final int MODE = parseMode(RAW);
    /** 1 秒內超過這麼多 flip 才隔離：人手與殭屍開關不可能到（讀碼推的，observe 看 maxFlipsPerSec 再定）。 */
    static final int FLIP_LIMIT = 8;
    static final long QUARANTINE_NS = 3_000_000_000L;
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final long LOG_GAP_NS = 10_000_000_000L;
    private static final long IDLE_NS = 60_000_000_000L;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][DoorSyncGuard] ";

    /** 實際生效的模式：sprite 欄位找不到就不分類（off）；lastPlayerOnlineId 找不到就 enforce 降成 observe。 */
    static final int ACTIVE = Handles.DOOR_CLOSED == null || Handles.THUMP_CLOSED == null ? OFF
            : MODE == ENFORCE && Handles.LAST_ID == null ? OBSERVE : MODE;

    /** 私有欄位的 VarHandle；查找的 checked 例外留在這個巢狀類別，外層只有 RuntimeException＋LinkageError handler。 */
    private static final class Handles {
        static final VarHandle LAST_ID = handle(IsoDoor.class, "lastPlayerOnlineId", short.class);
        static final VarHandle DOOR_CLOSED = handle(IsoDoor.class, "closedSprite", IsoSprite.class);
        static final VarHandle THUMP_CLOSED = handle(IsoThumpable.class, "closedSprite", IsoSprite.class);

        private static VarHandle handle(Class<?> owner, String name, Class<?> type) {
            try {
                return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, name, type);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    private static final int PASS = 0;
    private static final int SKIP = 1;
    private static final int SKIP_RESYNC = 2;

    /** 一條鏈的視窗狀態；只記座標，不握物件參照。 */
    private static final class Chain {
        final int x, y, z, idx;
        long windowStartNs;
        int flips;
        int sames;
        long lastFlipNs;
        long quarantineUntilNs;
        boolean quarantined;
        boolean everLogged;
        long lastLogNs;

        Chain(int x, int y, int z, int idx) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.idx = idx;
        }
    }

    // ---- 主執行緒單寫 ----
    // ponytail: 線性掃描找鏈（同 SyncIsoObjectProbe.Conn），同時在翻轉的門鏈個位數；上百條再換 map
    private static Chain[] chains = new Chain[8];
    private static int chainCount;
    private static IsoObject[] parts = new IsoObject[16];
    private static long doorRemote, same, sameSkipped, other, flip, quarantines, dropped, wouldDrop, anomalies;
    private static int maxFlipsPerSec;
    private static long lastBeatNs;
    private static boolean announced;

    private DoorSyncGuard() {
    }

    static int parseMode(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            case "enforce", "on" -> ENFORCE;
            default -> OBSERVE;
        };
    }

    /** 回 true＝呼叫端照原樣呼叫原方法。enforce 丟包時回 false；隔離開始時本方法先對封包指到的門主動同步一次（不在 try 內）。 */
    public static boolean allow(IsoObject obj, boolean remote, UdpConnection connection, ByteBufferReader bb) {
        return MODE == OFF || allow(obj, remote, connection, bb, System.nanoTime());
    }

    static boolean allow(IsoObject obj, boolean remote, UdpConnection connection, ByteBufferReader bb, long now) {
        if (MODE == OFF) {
            return true;
        }
        try {
            if (!announced || now - lastBeatNs >= BEAT_NS) {
                beat(now);
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
        if (ACTIVE == OFF || !remote || bb == null || !(obj instanceof IsoDoor door) || door.square == null) {
            return true;
        }
        int start = bb.position();
        int verdict;
        try {
            verdict = door.getObjectIndex() == -1 ? PASS : decide(door, connection, bb, now);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            verdict = PASS;
        }
        Arrays.fill(parts, null);
        if (verdict == PASS) {
            bb.position(start);
            return true;
        }
        if (verdict == SKIP_RESYNC) {
            // 對 IsoDoor 本身同步：它的 syncIsoObjectSend 帶整條鏈；代表片可能是 IsoThumpable，格式不同
            door.syncIsoObject(false, (byte) 0, null, null);
        }
        return false;
    }

    /** 預讀並分類；回 PASS／SKIP／SKIP_RESYNC。PASS 時呼叫端還原 position。 */
    private static int decide(IsoDoor door, UdpConnection connection, ByteBufferReader bb, long now) {
        doorRemote++;
        boolean bOpen = bb.getBoolean();
        boolean bLocked = bb.getBoolean();
        boolean bLockedByKey = bb.getBoolean();
        boolean tryLocked = bb.getBoolean();
        boolean tryBarricaded = bb.getBoolean();
        int health = bb.getInt();
        short lastId = bb.getShort();
        boolean garage = bb.getBoolean();
        int n = 0;
        if (garage) {
            int size = bb.getByte();
            for (int i = 0; i < size; i++) {
                IsoObject p = null;
                if (bb.getByte() == 1) {
                    short id = bb.getShort();
                    int x = bb.getInt();
                    int y = bb.getInt();
                    int z = bb.getByte();
                    p = lookup(x, y, z, id);
                }
                if (n == parts.length) {
                    parts = Arrays.copyOf(parts, n * 2);
                }
                parts[n++] = p;
            }
        }

        boolean flipped = door.isOpen() != bOpen || door.isLockedByKey() != bLockedByKey;
        boolean misc = tryLocked || tryBarricaded;
        // 本片也在 parts 裡時，原版迴圈的 setLockedByKey 會把本片 locked 再蓋成 bLockedByKey（迴圈裡已比過），不比 bLocked
        boolean selfInParts = false;
        // 鏈的代表片：車庫門取各片 (x,y,z,index) 最小的，否則本片
        IsoObject rep = door;
        int rx = door.square.x, ry = door.square.y, rz = door.square.z, ri = door.getObjectIndex();
        for (int i = 0; i < n; i++) {
            IsoObject p = parts[i];
            selfInParts |= p == door;
            IsoGridSquare sq = p == null ? null : p.getSquare();
            if (sq == null) {
                misc = true;
                continue;
            }
            boolean open;
            boolean lockedByKey;
            boolean locked;
            Object wantSprite;
            if (p instanceof IsoDoor d) {
                open = d.isOpen();
                lockedByKey = d.isLockedByKey();
                locked = d.isLocked();
                wantSprite = open ? d.getOpenSprite() : (IsoSprite) Handles.DOOR_CLOSED.get(d);
            } else if (p instanceof IsoThumpable t) {
                open = t.open;
                lockedByKey = t.isLockedByKey();
                locked = t.isLocked();
                wantSprite = open ? t.openSprite : (IsoSprite) Handles.THUMP_CLOSED.get(t);
            } else {
                misc = true;
                continue;
            }
            if (open != bOpen || lockedByKey != bLockedByKey || locked != bLockedByKey) {
                flipped = true;
            } else if (p.sprite != wantSprite) {
                misc = true;
            }
            int pi = p.getObjectIndex();
            if (before(sq.x, sq.y, sq.z, pi, rx, ry, rz, ri)) {
                rep = p;
                rx = sq.x;
                ry = sq.y;
                rz = sq.z;
                ri = pi;
            }
        }
        if (!selfInParts && door.isLocked() != bLocked) {
            flipped = true;
        }
        if (!flipped && door.health != health) {
            misc = true;
        }

        Chain c = find(rx, ry, rz, ri, now, flipped && !misc);
        if (c != null && c.quarantined && now >= c.quarantineUntilNs) {
            c.quarantined = false;
        }
        if (c != null && now - c.windowStartNs >= WINDOW_NS) {
            c.windowStartNs = now;
            c.flips = 0;
            c.sames = 0;
        }
        if (misc) {
            other++;
        } else if (flipped) {
            flip++;
            c.flips++;
            c.lastFlipNs = now;
            if (c.flips > maxFlipsPerSec) {
                maxFlipsPerSec = c.flips;
            }
        } else {
            same++;
            if (c != null) {
                c.sames++;
            }
        }

        if (c != null && c.quarantined) {
            return drop();
        }
        if (!misc && flipped && c.flips > FLIP_LIMIT) {
            c.quarantined = true;
            c.quarantineUntilNs = now + QUARANTINE_NS;
            quarantines++;
            report(c, rep, connection, now);
            return drop() == SKIP ? SKIP_RESYNC : PASS;
        }
        if (!misc && !flipped && ACTIVE == ENFORCE) {
            Handles.LAST_ID.set(door, lastId);
            sameSkipped++;
            return SKIP;
        }
        return PASS;
    }

    private static int drop() {
        if (ACTIVE == ENFORCE) {
            dropped++;
            return SKIP;
        }
        wouldDrop++;
        return PASS;
    }

    /** 同 NetObject.getObject 的查法，但取不到時不印 warn（原版稍後會再查一次並自己印）。 */
    private static IsoObject lookup(int x, int y, int z, short id) {
        IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
        if (sq == null) {
            return null;
        }
        List<IsoObject> objs = sq.getObjects();
        return id >= 0 && id < objs.size() ? objs.get(id) : null;
    }

    private static boolean before(int x, int y, int z, int i, int rx, int ry, int rz, int ri) {
        if (x != rx) {
            return x < rx;
        }
        if (y != ry) {
            return y < ry;
        }
        if (z != rz) {
            return z < rz;
        }
        return i < ri;
    }

    private static Chain find(int x, int y, int z, int idx, long now, boolean create) {
        for (int i = 0; i < chainCount; i++) {
            Chain c = chains[i];
            if (c.x == x && c.y == y && c.z == z && c.idx == idx) {
                return c;
            }
        }
        if (!create) {
            return null;
        }
        if (chainCount == chains.length) {
            chains = Arrays.copyOf(chains, chainCount * 2);
        }
        Chain c = new Chain(x, y, z, idx);
        c.windowStartNs = now - WINDOW_NS;
        chains[chainCount++] = c;
        return c;
    }

    /** 鏈進入隔離時印一行；同一條鏈 10 秒最多一行。 */
    private static void report(Chain c, IsoObject rep, UdpConnection connection, long now) {
        if (c.everLogged && now - c.lastLogNs < LOG_GAP_NS) {
            return;
        }
        c.everLogged = true;
        c.lastLogNs = now;
        try {
            DebugLog.log(TAG + "quarantine#" + quarantines + " mode=" + modeName()
                    + " player=" + MdcTimedActionProbe.safeName(connection == null ? null : connection.getUserName())
                    + " guid=" + (connection == null ? "?" : String.valueOf(connection.getConnectedGUID()))
                    + " chain=" + c.x + "," + c.y + "," + c.z + "#" + c.idx
                    + " sprite=" + MdcTimedActionProbe.safeName(rep.getSpriteName())
                    + " flips=" + c.flips + " same=" + c.sames + " quarantineMs=" + QUARANTINE_NS / 1_000_000L);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 每 5 分鐘一行（首次呼叫立即一行）；先清掉 60 秒沒有 flip 的鏈。 */
    private static void beat(long now) {
        String head = announced ? "beat " : "首次生效（-Dmdc.doorSyncGuard=off|observe|enforce） ";
        announced = true;
        lastBeatNs = now;
        int kept = 0;
        for (int i = 0; i < chainCount; i++) {
            if (now - chains[i].lastFlipNs <= IDLE_NS) {
                chains[kept++] = chains[i];
            }
        }
        Arrays.fill(chains, kept, chainCount, null);
        chainCount = kept;
        String line = TAG + head + "mode=" + modeName() + " doorRemote=" + doorRemote + " same=" + same
                + " sameSkipped=" + sameSkipped + " other=" + other + " flip=" + flip + " quarantines=" + quarantines + " dropped=" + dropped
                + " wouldDrop=" + wouldDrop + " maxFlipsPerSec=" + maxFlipsPerSec + " tracked=" + chainCount
                + " anomalies=" + anomalies;
        maxFlipsPerSec = 0;
        try {
            DebugLog.log(line);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    static String modeName() {
        if (ACTIVE != MODE) {
            return (ACTIVE == OFF ? "off" : "observe") + "(fieldMissing,raw=" + RAW + ")";
        }
        return MODE == OFF ? "off" : MODE == ENFORCE ? "enforce" : "observe";
    }

    // ---- 測試存取器 ----
    static long doorRemoteForTest() {
        return doorRemote;
    }

    static long sameForTest() {
        return same;
    }

    static long otherForTest() {
        return other;
    }

    static long sameSkippedForTest() {
        return sameSkipped;
    }

    static long flipForTest() {
        return flip;
    }

    static long quarantinesForTest() {
        return quarantines;
    }

    static long droppedForTest() {
        return dropped;
    }

    static long wouldDropForTest() {
        return wouldDrop;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static int trackedForTest() {
        return chainCount;
    }

    /** 下一次呼叫就印 beat（模擬過了 5 分鐘）。 */
    static void armBeatForTest(long now) {
        lastBeatNs = now - BEAT_NS;
    }
}
