package zombie.entity;

import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.entity.util.Array;
import zombie.entity.util.ImmutableArray;

/**
 * W35 使用中玩家索引（2026-09-25；docs/patches.md 2ax）。
 *
 * <p><b>熱點</b>：{@code UsingPlayerUpdateSystem.update} 每幀掃過 IsoObject bucket 的全部 entity，
 * 只為了把「使用中玩家已離開 10 格／換層／死亡」的 {@code usingPlayer} 清成 null。真正有
 * usingPlayer 的 entity 極少，時間花在逐一讀取每個 entity（晚峰 JFR 主執行緒 5.9%）。
 *
 * <p><b>手術</b>：
 * <ul>
 *   <li>{@code GameEntity.usingPlayer} 只有 {@code GameEntity} 內部寫入：{@code setUsingPlayer}
 *       （headCall 帶新值）、{@code receiveUpdateUsingPlayer}／{@code receiveSyncEntity}（tailCall，
 *       讀寫入後的值）、{@code reset}（只會寫 null，不需要追蹤）。凡是寫成非 null 就記入弱參照集合。</li>
 *   <li>{@code UsingPlayerUpdateSystem.update} 內唯一 {@code EntityBucket.getEntities()} 改道
 *       {@link #entities}：enforce 時回傳「集合中仍有 usingPlayer 且屬於該 bucket」的精簡陣列。
 *       原版對每個 entity 的處理互相獨立（只可能把該 entity 自己的 usingPlayer 清掉），
 *       不在集合中的 entity 原版也不會做任何事，因此結果相同，只有處理順序不同。</li>
 * </ul>
 *
 * <p><b>自我稽核</b>：observe 與 enforce 每 {@value #AUDIT_EVERY} 次呼叫做一次全表比對，
 * 找出「有 usingPlayer、屬於 bucket、卻不在集合中」的 entity（missed）。enforce 下只要出現
 * missed 就永久退回原版全表並記錄，不猜原因。
 *
 * <p>三態 {@code -Dmdc.usingPlayerIndex}：{@code 2|observe}（預設：回傳原版全表，只記錄與稽核）、
 * {@code 1|enforce}、{@code 0|off}（追蹤與稽核全關，純直通）。未知值落回 observe。需重啟。
 */
public final class MdcUsingPlayerIndex {

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode(System.getProperty("mdc.usingPlayerIndex"));

    static final int AUDIT_EVERY = 256;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][UsingPlayerIndex] ";

    /** 弱參照：entity 被丟棄時自動移出，不延長其生命週期。GameEntity／IsoObject 未覆寫 equals/hashCode。 */
    private static final Set<GameEntity> TRACKED = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Array<GameEntity> COMPACT = new Array<>(false, 64);
    private static final ImmutableArray<GameEntity> COMPACT_VIEW = new ImmutableArray<>(COMPACT);

    private static long calls;
    private static long audits;
    private static long missed;
    private static long lastBucketSize;
    private static long lastCompactSize;
    private static long maxCompactSize;
    private static long anomalies;
    private static boolean fellBack;
    private static long lastBeatNs;
    private static boolean announced;

    static int parseMode(String raw) {
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0":
            case "off":
                return MODE_OFF;
            case "1":
            case "enforce":
                return MODE_ENFORCE;
            default:
                return MODE_OBSERVE;
        }
    }

    /** {@code GameEntity.setUsingPlayer} 頭部：寫入前的新值。 */
    public static void onSetUsingPlayer(GameEntity entity, IsoPlayer player) {
        if (MODE != MODE_OFF && player != null) {
            track(entity);
        }
    }

    /** {@code receiveUpdateUsingPlayer}／{@code receiveSyncEntity} 每個 RETURN 前：寫入後的值。 */
    public static void afterReceive(GameEntity entity) {
        if (MODE != MODE_OFF && entity.getUsingPlayer() != null) {
            track(entity);
        }
    }

    private static void track(GameEntity entity) {
        synchronized (TRACKED) {
            TRACKED.add(entity);
        }
    }

    /** {@code UsingPlayerUpdateSystem.update} 內 {@code EntityBucket.getEntities()} 的改道目標。 */
    public static ImmutableArray<GameEntity> entities(EntityBucket bucket) {
        ImmutableArray<GameEntity> all = bucket.getEntities();
        if (MODE == MODE_OFF) {
            return all;
        }
        calls++;
        int index = bucket.getIndex();
        COMPACT.clear();
        synchronized (TRACKED) {
            Iterator<GameEntity> it = TRACKED.iterator();
            while (it.hasNext()) {
                GameEntity e = it.next();
                if (e.getUsingPlayer() == null) {
                    it.remove();
                } else if (e.hasComponents() && e.getBucketBits().get(index)) {
                    // hasComponents 先擋：無 component 時 getBucketBits 會印 error 並回假 bits
                    COMPACT.add(e);
                }
            }
        }
        lastBucketSize = all.size();
        lastCompactSize = COMPACT.size;
        if (COMPACT.size > maxCompactSize) {
            maxCompactSize = COMPACT.size;
        }
        if (calls % AUDIT_EVERY == 0L) {
            audit(all, index);
        }
        maybeBeat();
        if (MODE == MODE_ENFORCE && !fellBack) {
            return COMPACT_VIEW;
        }
        COMPACT.clear();
        return all;
    }

    /** 全表比對：有 usingPlayer 的 bucket 成員都必須已在 COMPACT 中。 */
    private static void audit(ImmutableArray<GameEntity> all, int index) {
        audits++;
        int found = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getUsingPlayer() != null) {
                found++;
            }
        }
        if (found > COMPACT.size) {
            missed += found - COMPACT.size;
            if (MODE == MODE_ENFORCE && !fellBack) {
                fellBack = true;
                log("MISSED：全表有 " + found + " 個使用中 entity，索引只有 " + COMPACT.size
                        + "；本次起永久退回原版全表掃描");
            }
        }
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        String head = announced ? "" : "首次生效（-Dmdc.usingPlayerIndex=0|off/1|enforce/2|observe 預設）";
        announced = true;
        log(head + "mode=" + MODE + " calls=" + calls + " bucket=" + lastBucketSize
                + " active=" + lastCompactSize + " activeMax=" + maxCompactSize
                + " audits=" + audits + " missed=" + missed + " fellBack=" + (fellBack ? 1 : 0)
                + " anomalies=" + anomalies);
    }

    private static void log(String msg) {
        try {
            DebugLog.log(TAG + msg);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies++;
        }
    }

    // ---- 測試存取器 ----

    static long missedForTest() {
        return missed;
    }

    static boolean fellBackForTest() {
        return fellBack;
    }

    static int trackedForTest() {
        synchronized (TRACKED) {
            return TRACKED.size();
        }
    }

    private MdcUsingPlayerIndex() {}
}
