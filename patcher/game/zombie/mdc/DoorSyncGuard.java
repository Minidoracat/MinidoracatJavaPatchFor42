package zombie.mdc;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.BufferUnderflowException;
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
 * <p><b>分類</b>（只看收包物件是 {@code IsoDoor}、遠端、{@code bb != null}、在格子上的門；{@code IsoThumpable} 門片與全由它組成的車庫門
 * 原樣）：預讀封包（記下 position）。{@code same}＝原版處理這一包後除了 {@code lastPlayerOnlineId} 以外的物件欄位都不會變；
 * {@code flip}＝本片或任一片的開關或鎖和封包不同；其餘（只有血量不同、帶「試開」旗標、有片取不到或不是門，計 {@code other}）照原樣。
 * 本片也列在鏈裡時，原版迴圈的 {@code setLockedByKey} 會把本片 {@code locked} 蓋成封包的 lockedByKey，所以那時不拿本片 {@code locked}
 * 和封包的 locked 比。各片只用來分類。
 *
 * <p><b>限速</b>：以（收包的 {@code IsoDoor}，送包的 {@code UdpConnection}）兩個物件參照為 key（identity），1 秒視窗內第
 * {@link #FLIP_LIMIT}+1 個 flip 起這個 key 隔離 {@link #QUARANTINE_NS}；另一條連線對同一扇門、或同一條連線對別扇門的包照原樣。
 * 追蹤表最多 {@link #MAX_TRACKED} 筆：滿了先淘汰不在隔離中、1 秒內沒有 flip 的；仍滿就不建紀錄（照原樣、計 {@code overflow}）。
 * beat 清掉 60 秒沒有 flip、不在隔離中的。
 *
 * <p><b>模式</b> {@code -Dmdc.doorSyncGuard=off|observe|enforce}（預設 observe，和 {@code -Dmdc.syncIsoObjectProbe} 互相獨立）：
 * observe 只計數、還原 position、照原樣呼叫原方法；enforce 對 {@code same} 只寫 {@code lastPlayerOnlineId}、不呼叫原方法，
 * 隔離開始那一包丟掉並讓伺服器對這扇門主動同步一次（{@code IsoDoor} 的同步帶整條鏈），隔離期間這個 key 的包一律丟掉；
 * 隔離到期時若期間丟過包，再主動同步一次（{@code resyncs}），讓被丟包的客戶端回到伺服器現況；隔離期間被丟掉的改動不套用、會被蓋回。
 * 主動同步排進佇列，由 {@link #flushResyncs} 在原方法套用這一包之後送出；送出當下再用世界查回確認門還在原格子上，
 * 每扇門各自隔離失敗（計 {@code resyncFailed}、不重送）。
 *
 * <p>只在主執行緒被呼叫，不加鎖。分類、記錄與主動同步的 RuntimeException／LinkageError 只計 anomalies（短包的
 * BufferUnderflowException 計 malformed）、還原 position、照原樣；Error 照常外拋；原方法不在 try 內。
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
    static final int MAX_TRACKED = 256;
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final long LOG_GAP_NS = 10_000_000_000L;
    private static final long IDLE_NS = 60_000_000_000L;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final int ANOMALY_LOGS = 5;
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

    /** 一扇門 × 一條連線的視窗狀態；key 是兩個物件參照（identity）。 */
    private static final class Track {
        final IsoDoor door;
        final UdpConnection conn;
        long windowStartNs;
        int flips;
        int sames;
        long lastFlipNs;
        long quarantineUntilNs;
        boolean quarantined;
        /** 隔離期間（開始那一包之後）enforce 丟過包：到期要補同步。 */
        boolean droppedDuring;
        boolean everLogged;
        long lastLogNs;

        Track(IsoDoor door, UdpConnection conn) {
            this.door = door;
            this.conn = conn;
        }
    }

    // ---- 主執行緒單寫 ----
    // ponytail: 線性掃描（同 SyncIsoObjectProbe.Conn）；只有正在翻轉的門才建紀錄，上限 MAX_TRACKED 筆，
    // 隔離中與 1 秒內有 flip 的紀錄不淘汰，beat 清 60 秒沒 flip 的；熱到常駐上百筆再換 map
    private static Track[] tracks = new Track[8];
    private static int trackCount;
    private static int quarantinedCount;
    private static IsoDoor[] resync = new IsoDoor[4];
    /** 和 resync 平行：true＝到期補同步（成功才計 resyncs），false＝隔離開始那一包的主動同步。 */
    private static boolean[] resyncExpiry = new boolean[4];
    private static int resyncCount;
    private static IsoObject[] parts = new IsoObject[16];
    private static long doorRemote, same, sameSkipped, other, flip, quarantines, dropped, wouldDrop, resyncs, resyncFailed, overflow,
            malformed, anomalies;
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

    /** 回 true＝呼叫端照原樣呼叫原方法。enforce 丟包時回 false；要主動同步的門只排進佇列，呼叫端在原方法之後呼叫 {@link #flushResyncs}。 */
    public static boolean allow(IsoObject obj, boolean remote, UdpConnection connection, ByteBufferReader bb) {
        return MODE == OFF || allow(obj, remote, connection, bb, System.nanoTime());
    }

    static boolean allow(IsoObject obj, boolean remote, UdpConnection connection, ByteBufferReader bb, long now) {
        if (MODE == OFF) {
            return true;
        }
        try {
            // 先解除到期的隔離，beat 才清得掉剛解除的紀錄
            if (quarantinedCount > 0) {
                expire(now);
            }
            if (!announced || now - lastBeatNs >= BEAT_NS) {
                beat(now);
            }
        } catch (RuntimeException | LinkageError e) {
            anomaly("beat", e);
        }
        boolean pass = true;
        if (ACTIVE != OFF && remote && bb != null && obj instanceof IsoDoor door && door.square != null) {
            int start = bb.position();
            int verdict;
            try {
                verdict = door.getObjectIndex() == -1 ? PASS : decide(door, connection, bb, now);
            } catch (RuntimeException | LinkageError e) {
                if (e instanceof BufferUnderflowException) {
                    malformed++;
                } else {
                    anomaly("decide", e);
                }
                verdict = PASS;
            }
            Arrays.fill(parts, null);
            if (verdict == PASS) {
                bb.position(start);
            } else {
                pass = false;
            }
        }
        return pass;
    }

    /**
     * 送出排隊的主動同步；W58 helper 在原方法套用這一包之後呼叫（原方法丟例外時不呼叫，佇列留到下一包）。
     * 對 IsoDoor 本身同步，它的 syncIsoObjectSend 帶整條鏈。送出當下用世界查回確認門還在原格子上；
     * 每扇門各自 try，失敗計 resyncFailed、不重送，不影響其他門。
     */
    public static void flushResyncs() {
        while (resyncCount > 0) {
            int i = --resyncCount;
            IsoDoor d = resync[i];
            boolean expiry = resyncExpiry[i];
            resync[i] = null;
            resyncExpiry[i] = false;
            try {
                IsoGridSquare sq = d.square;
                if (sq == null || IsoWorld.instance.currentCell.getGridSquare(sq.x, sq.y, sq.z) != sq || d.getObjectIndex() == -1) {
                    continue;
                }
                d.syncIsoObject(false, (byte) 0, null, null);
                if (expiry) {
                    resyncs++;
                }
            } catch (RuntimeException | LinkageError e) {
                resyncFailed++;
                anomaly("resync", e);
            }
        }
    }

    /** 預讀並分類；回 PASS／SKIP。PASS 時呼叫端還原 position。 */
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
        for (int i = 0; i < n; i++) {
            IsoObject p = parts[i];
            selfInParts |= p == door;
            if (p == null || p.getSquare() == null) {
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
        }
        if (!selfInParts && door.isLocked() != bLocked) {
            flipped = true;
        }
        if (!flipped && door.health != health) {
            misc = true;
        }

        Track t = find(door, connection, now, flipped && !misc);
        if (t != null && now - t.windowStartNs >= WINDOW_NS) {
            t.windowStartNs = now;
            t.flips = 0;
            t.sames = 0;
        }
        if (misc) {
            other++;
        } else if (flipped) {
            flip++;
            if (t != null) {
                t.flips++;
                t.lastFlipNs = now;
                if (t.flips > maxFlipsPerSec) {
                    maxFlipsPerSec = t.flips;
                }
            }
        } else {
            same++;
            if (t != null) {
                t.sames++;
            }
        }

        if (t != null && t.quarantined) {
            if (ACTIVE == ENFORCE) {
                dropped++;
                t.droppedDuring = true;
                return SKIP;
            }
            wouldDrop++;
            return PASS;
        }
        if (t != null && !misc && flipped && t.flips > FLIP_LIMIT) {
            t.quarantined = true;
            t.droppedDuring = false;
            t.quarantineUntilNs = now + QUARANTINE_NS;
            quarantinedCount++;
            quarantines++;
            report(t, now);
            if (ACTIVE == ENFORCE) {
                dropped++;
                queueResync(door, false);
                return SKIP;
            }
            wouldDrop++;
            return PASS;
        }
        if (!misc && !flipped && ACTIVE == ENFORCE) {
            Handles.LAST_ID.set(door, lastId);
            sameSkipped++;
            return SKIP;
        }
        return PASS;
    }

    /** 到期的隔離解除；期間丟過包的排進到期補同步（同一扇門只排一次；門還在不在世界上由 flushResyncs 送出當下查）。只在有隔離中的紀錄時被呼叫。 */
    private static void expire(long now) {
        for (int i = 0; i < trackCount; i++) {
            Track t = tracks[i];
            if (!t.quarantined || now < t.quarantineUntilNs) {
                continue;
            }
            t.quarantined = false;
            quarantinedCount--;
            if (t.droppedDuring) {
                queueResync(t.door, true);
            }
            t.droppedDuring = false;
        }
    }

    private static void queueResync(IsoDoor d, boolean expiry) {
        for (int i = 0; i < resyncCount; i++) {
            if (resync[i] == d) {
                resyncExpiry[i] |= expiry;
                return;
            }
        }
        if (resyncCount == resync.length) {
            resync = Arrays.copyOf(resync, resyncCount * 2);
            resyncExpiry = Arrays.copyOf(resyncExpiry, resyncCount * 2);
        }
        resyncExpiry[resyncCount] = expiry;
        resync[resyncCount++] = d;
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

    /** 找（門, 連線）的紀錄；create 時表滿先淘汰閒置的，仍滿就計 overflow、回 null（照原樣、不追蹤）。 */
    private static Track find(IsoDoor door, UdpConnection conn, long now, boolean create) {
        for (int i = 0; i < trackCount; i++) {
            Track t = tracks[i];
            if (t.door == door && t.conn == conn) {
                return t;
            }
        }
        if (!create) {
            return null;
        }
        if (trackCount == MAX_TRACKED) {
            prune(now, WINDOW_NS);
            if (trackCount == MAX_TRACKED) {
                overflow++;
                return null;
            }
        }
        if (trackCount == tracks.length) {
            tracks = Arrays.copyOf(tracks, Math.min(trackCount * 2, MAX_TRACKED));
        }
        Track t = new Track(door, conn);
        t.windowStartNs = now - WINDOW_NS;
        tracks[trackCount++] = t;
        return t;
    }

    /** 就地留下隔離中的與 idleNs 內有 flip 的紀錄。 */
    private static void prune(long now, long idleNs) {
        int kept = 0;
        for (int i = 0; i < trackCount; i++) {
            Track t = tracks[i];
            if (t.quarantined || now - t.lastFlipNs <= idleNs) {
                tracks[kept++] = t;
            }
        }
        Arrays.fill(tracks, kept, trackCount, null);
        trackCount = kept;
    }

    /** 進入隔離時印一行；同一筆仍在追蹤中的紀錄 10 秒最多一行（紀錄被清掉後重建就重新計）。 */
    private static void report(Track t, long now) {
        if (t.everLogged && now - t.lastLogNs < LOG_GAP_NS) {
            return;
        }
        t.everLogged = true;
        t.lastLogNs = now;
        try {
            IsoDoor d = t.door;
            UdpConnection c = t.conn;
            DebugLog.log(TAG + "quarantine#" + quarantines + " mode=" + modeName()
                    + " player=" + MdcTimedActionProbe.safeName(c == null ? null : c.getUserName())
                    + " guid=" + (c == null ? "?" : String.valueOf(c.getConnectedGUID()))
                    + " door=" + d.square.x + "," + d.square.y + "," + d.square.z + "#" + d.getObjectIndex()
                    + " sprite=" + MdcTimedActionProbe.safeName(d.getSpriteName())
                    + " flips=" + t.flips + " same=" + t.sames + " quarantineMs=" + QUARANTINE_NS / 1_000_000L);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    /** 計 anomalies；前 {@link #ANOMALY_LOGS} 次印類別與訊息。 */
    private static void anomaly(String where, Throwable e) {
        anomalies++;
        if (anomalies <= ANOMALY_LOGS) {
            try {
                DebugLog.log(TAG + "anomaly #" + anomalies + " " + where + " " + e.getClass().getName() + ": " + e.getMessage());
            } catch (RuntimeException | LinkageError ignored) {
                anomalies++;
            }
        }
    }

    /** 每 5 分鐘一行（首次呼叫立即一行）；先清掉 60 秒沒有 flip、不在隔離中的紀錄。 */
    private static void beat(long now) {
        String head = announced ? "beat " : "首次生效（-Dmdc.doorSyncGuard=off|observe|enforce） ";
        announced = true;
        lastBeatNs = now;
        prune(now, IDLE_NS);
        String line = TAG + head + "mode=" + modeName() + " doorRemote=" + doorRemote + " same=" + same
                + " sameSkipped=" + sameSkipped + " other=" + other + " flip=" + flip + " quarantines=" + quarantines + " dropped=" + dropped
                + " wouldDrop=" + wouldDrop + " resyncs=" + resyncs + " resyncFailed=" + resyncFailed + " maxFlipsPerSec=" + maxFlipsPerSec + " tracked=" + trackCount
                + " overflow=" + overflow + " malformed=" + malformed + " anomalies=" + anomalies;
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

    static long resyncsForTest() {
        return resyncs;
    }

    static long resyncFailedForTest() {
        return resyncFailed;
    }

    static long overflowForTest() {
        return overflow;
    }

    static long malformedForTest() {
        return malformed;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static int trackedForTest() {
        return trackCount;
    }

    static int quarantinedForTest() {
        return quarantinedCount;
    }

    /** 下一次呼叫就印 beat（模擬過了 5 分鐘）。 */
    static void armBeatForTest(long now) {
        lastBeatNs = now - BEAT_NS;
    }
}
