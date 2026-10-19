package zombie.entity;

import java.lang.reflect.Field;

import zombie.debug.DebugLog;
import zombie.entity.util.LongMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * W69 實體 ID 撞號時照常移除（2026-10-19；docs/patches.md 2ce）。
 *
 * <p><b>原版缺陷</b>：{@code IsoObject.getEntityNetID} 以「座標＋物件在格子清單的序號」組 ID，但地板一律算序號 0。
 * 玩家後蓋的地板排在牆或家具後面時，排第 0 個的實體與地板同 ID。伺服器移除它時
 * {@code GameEntityManager.UnregisterEntity} 從 {@code idToEntityMap} 拿出的是地板，丟 {@code Stored entity mismatch}：
 * 地板的登記先被拿掉，被移除的實體留在 engine；{@code RemoveItemFromSquarePacket.removeItemFromMap} 停在
 * {@code removeFromSquare} 之前，但移除封包已先送給客戶端（伺服器留著物件，重登後變成拆不掉的殘件）；
 * chunk 卸載時整個 cell 的卸載中斷。ID 為空時原版直接 return，實體同樣留在 engine。
 *
 * <p><b>手術</b>：{@code GameEntity.removeFromWorld(boolean)} 唯一 {@code UnregisterEntity(GameEntity, boolean)}
 * 改道 {@link #unregister}。伺服器上、該 ID 存的不是這個實體時，先把它放進該 ID，交給原版照常移除（離開 engine、
 * 需要時轉存 MetaEntity），再把原本的實體放回（該 ID 仍空著才放；轉存的 MetaEntity 沿用同一個 ID 時以它為準）。
 * ID 算法不動：客戶端也用同一套算法，只改伺服器會讓兩邊對不上。
 *
 * <p>kill switch {@code -Dmdc.entityIdGuard=0|off}（直通原版）。需重啟。
 */
public final class MdcEntityIdGuard {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.entityIdGuard"));
    private static final String TAG = "[MinidoracatJavaPatch][EntityIdGuard] ";
    private static final long BEAT_NS = 1_800_000_000_000L;
    private static final long WINDOW_NS = 60_000_000_000L;
    private static final int LOG_PER_WINDOW = 20;
    private static final LongMap<GameEntity> MAP = resolveMap();

    private static long calls;
    private static long collisions;
    private static long missing;
    private static long notRestored;
    private static long suppressed;
    private static long anomalies;
    private static long lastBeatNs;
    private static long windowStartNs;
    private static int windowLogged;
    private static boolean announced;

    private MdcEntityIdGuard() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    @SuppressWarnings("unchecked")
    private static LongMap<GameEntity> resolveMap() {
        try {
            Field f = GameEntityManager.class.getDeclaredField("idToEntityMap");
            f.setAccessible(true);
            return (LongMap<GameEntity>) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            anomalies++;
            return null;
        }
    }

    /** {@code GameEntity.removeFromWorld(boolean)} 內 {@code GameEntityManager.UnregisterEntity(GameEntity, boolean)} 的改道目標。 */
    public static void unregister(GameEntity entity, boolean offloadToMeta) {
        LongMap<GameEntity> map = MAP;
        if (!ENABLED || map == null || entity == null || !entity.addedToEntityManager || !GameServer.server) {
            GameEntityManager.UnregisterEntity(entity, offloadToMeta);
            return;
        }
        calls++;
        long id = entity.getEntityNetID();
        GameEntity other;
        boolean fix;
        synchronized (map) {
            other = map.get(id);
            fix = other != entity;
            if (fix) {
                map.put(id, entity);
            }
        }
        if (!fix) {
            GameEntityManager.UnregisterEntity(entity, offloadToMeta);
            maybeBeat();
            return;
        }
        boolean restored = false;
        try {
            GameEntityManager.UnregisterEntity(entity, offloadToMeta);
        } finally {
            if (other != null) {
                synchronized (map) {
                    if (map.get(id) == null) {
                        map.put(id, other);
                        restored = true;
                    }
                }
            }
            record(entity, other, id, offloadToMeta, restored);
        }
    }

    private static void record(GameEntity entity, GameEntity other, long id, boolean offload, boolean restored) {
        if (other != null) {
            collisions++;
            if (!restored) {
                notRestored++;
            }
        } else {
            missing++;
        }
        long now = System.nanoTime();
        if (now - windowStartNs >= WINDOW_NS) {
            if (suppressed > 0 && windowStartNs != 0) {
                log("前一分鐘另有 " + suppressed + " 筆未列出");
            }
            windowStartNs = now;
            windowLogged = 0;
            suppressed = 0;
        }
        if (windowLogged < LOG_PER_WINDOW) {
            windowLogged++;
            log((other != null ? "撞號" : "ID 未登記") + "仍照常移除 id=" + id + " at=" + where(entity)
                    + " entity=" + describe(entity)
                    + (other != null ? " holder=" + describe(other) + " holderBack=" + (restored ? 1 : 0) : "")
                    + " offload=" + (offload ? 1 : 0));
        } else {
            suppressed++;
        }
        maybeBeat();
    }

    private static String where(GameEntity entity) {
        if (entity instanceof IsoObject o) {
            IsoGridSquare sq = o.getSquare();
            if (sq != null) {
                return sq.getX() + "," + sq.getY() + "," + sq.getZ() + "#" + o.getObjectIndex();
            }
        }
        return "-";
    }

    private static String describe(GameEntity entity) {
        try {
            return String.valueOf(entity);
        } catch (RuntimeException e) {
            anomalies++;
            return entity.getClass().getName();
        }
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        lastBeatNs = now;
        String head = announced ? "beat " : "首次生效（-Dmdc.entityIdGuard=0|off 回原版）";
        announced = true;
        log(head + "calls=" + calls + " collisions=" + collisions + " missing=" + missing
                + " notRestored=" + notRestored + " anomalies=" + anomalies);
    }

    private static void log(String msg) {
        try {
            DebugLog.log(TAG + msg);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies++;
        }
    }

    // ---- 測試存取器 ----

    static long collisionsForTest() {
        return collisions;
    }

    static long missingForTest() {
        return missing;
    }

    static long notRestoredForTest() {
        return notRestored;
    }

    static boolean mapResolvedForTest() {
        return MAP != null;
    }
}
