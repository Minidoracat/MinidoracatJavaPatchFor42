package zombie.mdc;

import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

/**
 * W22 面向物件 sprite-grid null 守衛（2026-09-02；docs/patches.md 2ai）。
 *
 * <p><b>症狀</b>（正式服 9/1–9/2 兩天 3386 次、約 70/h，log 最大單一例外源）：
 * {@code StateMachine.stateExecute > NullPointerException: Cannot invoke
 * "IsoObject.getFacingPosition(Vector2)" because "object" is null} at
 * {@code IsoGameCharacter.faceThisObject}，caller 100% 是動物狀態機
 * （{@code AnimalIdleState.execute} 2366／{@code AnimalEatState.execute} 1020）。
 *
 * <p><b>vanilla 缺陷</b>：{@code faceThisObject} 對 sprite-grid 物件（食槽等多格物件）先呼叫
 * {@code object.getClosestSpriteGridObject(x, y)} 再無條件解參考——而該方法在
 * {@code getSpriteGridObjects(result, true)} 回空清單時回 {@code null}。「包含 self」只在
 * self 仍列於其 square 的 objects 清單時成立（javap／反編譯 IsoObject:5389-5395）；動物的
 * {@code eatFromTrough}／{@code drinkFromTrough} 指向<b>已移出世界</b>的食槽（拆除／搬移後
 * 動物側參照未清）或 grid 格在 server 端未載入時，清單為空 ⇒ NPE。
 *
 * <p><b>後果不只是噪音</b>：例外中斷該 tick 的 {@code state.execute}，AnimalIdleState 後段的
 * {@code changeState(AnimalEatState／AnimalWalkState)} 被跳過——動物卡在 idle、每 tick 重炸。
 *
 * <p><b>手術</b>：{@code faceThisObject} 內唯一 {@code getClosestSpriteGridObject} callsite
 * redirect → {@link #closestSpriteGridObject}（1:1 同形；receiver 前置）。helper 委派 vanilla，
 * 結果 null 時回原 {@code object}（面向它自己的 square 座標——{@code getFacingPosition} 以
 * object 自身 x/y 計算，語意即「面向舊位置」）。非 null 結果逐位元等價。
 * {@code faceThisObjectAlt} 內同名 callsite 刻意不動（log 零命中，SmokeCheck 負對照釘死）。
 *
 * <p>例外紀律：vanilla 委派拋出的任何例外原樣穿透（含 {@code getSquare()==null} 的 NPE，
 * 那是不同訊息、與本刀無關）；只有 helper 自身診斷失敗才計 anomalies。
 * kill switch：{@code -Dmdc.faceObjectGuard=0}（純直通，含 null 回傳＝vanilla 語意）。
 */
public final class FaceObjectGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.faceObjectGuard"));

    /** 委派次數；heartbeat 節拍。 */
    private static final AtomicLong calls = new AtomicLong();
    /** vanilla 回 null 而改回原 object 的次數（本刀生效次數）。 */
    private static final AtomicLong fallbacks = new AtomicLong();
    /** helper 自身診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    /** 逐筆詳細診斷的上限，之後只計數。 */
    private static final long DETAIL_LIMIT = 32L;
    /** 每動物每 tick 一次量級，heartbeat 約每 1M 次。 */
    private static final long HEARTBEAT_EVERY = 1L << 20;
    private static final String TAG = "[MinidoracatJavaPatch][FaceObjectGuard] ";

    /**
     * {@code IsoGameCharacter.faceThisObject} 內唯一 {@code getClosestSpriteGridObject(FF)}
     * callsite 的改道目標。
     */
    public static IsoObject closestSpriteGridObject(IsoObject object, float toX, float toY) {
        IsoObject closest = object.getClosestSpriteGridObject(toX, toY);
        if (!ENABLED) {
            return closest;
        }
        if (calls.incrementAndGet() % HEARTBEAT_EVERY == 0L) {
            heartbeat();
        }
        if (closest != null) {
            return closest;
        }
        long n = fallbacks.incrementAndGet();
        if (n <= DETAIL_LIMIT) {
            report(n, object, toX, toY);
        }
        return object;
    }

    /** 攔截現場診斷：物件類別／sprite 名／square 座標／呼叫者座標（可對回農場與食槽）。 */
    private static void report(long n, IsoObject object, float toX, float toY) {
        try {
            IsoSprite sprite = object.getSprite();
            IsoGridSquare square = object.getSquare();
            DebugLog.log(TAG + "sprite-grid lookup returned null, facing the object itself"
                    + " n=" + n
                    + " class=" + object.getClass().getSimpleName()
                    + " sprite=" + (sprite == null ? "null" : sprite.getName())
                    + " square=" + (square == null ? "null"
                            : square.getX() + "," + square.getY() + "," + square.getZ())
                    + " from=" + toX + "," + toY
                    + "（object 不在其 square 的 objects 清單或 grid 格未載入＝stale 參照）");
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    private static void heartbeat() {
        try {
            DebugLog.log(TAG + "calls=" + calls.get() + " fallbacks=" + fallbacks.get()
                    + " anomalies=" + anomalies.get() + " enabled=" + (ENABLED ? 1 : 0));
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    // ---- 測試存取器 ----

    static boolean enabledForTest() {
        return ENABLED;
    }

    static long fallbacksForTest() {
        return fallbacks.get();
    }

    static long callsForTest() {
        return calls.get();
    }

    static long anomaliesForTest() {
        return anomalies.get();
    }

    private FaceObjectGuard() {}
}
