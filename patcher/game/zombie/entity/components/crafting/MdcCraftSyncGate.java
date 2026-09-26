package zombie.entity.components.crafting;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import zombie.debug.DebugLog;
import zombie.entity.components.crafting.recipe.CraftRecipeData;

/**
 * W36：CraftLogic.onUpdate 在製作中每 1000 ms 無條件 {@code sendCraftLogicSync}（整份狀態）。
 * 改為只在 client 看得到的內容變化時才送：每筆 in-progress 的整數百分比（原版 tooltip／overlay
 * 同一算式）、in-progress 清單（筆數與身分）、目前配方，以及 DryingCraftLogic 顯示用的濕度。
 * CraftLogicSystem.stop 結束時的明確同步照送，並記下送出內容，週期路徑以此為比較基準。
 *
 * <p>client 不跑 CraftLogicSystem，進度只來自這些同步；代價是 client 端進度與剩餘時間
 * 以 1% 為步進更新。封包格式不變。放在本 package 以讀 package-private 的 in-progress 清單。
 *
 * <p>{@code -Dmdc.craftLogicSyncGate}：未設定＝1／enforce，2／observe 只記判定、照送，
 * 0／off 回原版；未知值落到 observe。需重啟。
 */
public final class MdcCraftSyncGate {
    static final int OFF = 0;
    static final int ENFORCE = 1;
    static final int OBSERVE = 2;
    static final int MODE = parseMode();

    private static final String TAG = "[MinidoracatJavaPatch][CraftSyncGate] ";
    private static final long BEAT_NS = 300_000_000_000L;
    /** DryingCraftLogic 的濕度只存在 private map；讀不到時該類不省封包（簽章失敗→照送）。 */
    private static final Field WETNESS = wetnessField();
    /** 每個 CraftLogic 上次送出內容的簽章；弱鍵且值不持有物件參照。 */
    private static final Map<CraftLogic, long[]> LAST = new WeakHashMap<>();

    // CraftLogicSystem 在世界更新主緒；LAST 另以鎖保護，避免非預期執行緒毀損 map。
    private static long periodic, periodicSent, suppressed, wouldSuppress, explicit, sigErrors, logErrors;
    private static long lastBeatNs;
    private static boolean announced;

    private MdcCraftSyncGate() {}

    /** CraftLogic.onUpdate 內每秒 sendCraftLogicSync 的同形改道。 */
    public static void periodicSync(CraftLogic logic) {
        if (MODE == OFF) {
            logic.sendCraftLogicSync();
            return;
        }
        periodic++;
        long signature;
        try {
            signature = signature(logic);
        } catch (RuntimeException failure) {
            sigErrors++;
            forget(logic);
            logic.sendCraftLogicSync();
            maybeBeat();
            return;
        }
        if (sameAsLast(logic, signature)) {
            wouldSuppress++;
            if (MODE == ENFORCE) {
                suppressed++;
                maybeBeat();
                return;
            }
        }
        logic.sendCraftLogicSync();
        periodicSent++;
        remember(logic, signature);
        maybeBeat();
    }

    /** CraftLogicSystem.stop 結束時的明確同步：照送，並記下實際送出的內容。 */
    public static void explicitSync(CraftLogic logic) {
        logic.sendCraftLogicSync();
        if (MODE == OFF) {
            return;
        }
        explicit++;
        try {
            remember(logic, signature(logic));
        } catch (RuntimeException failure) {
            sigErrors++;
            forget(logic);
        }
    }

    /** client 可見內容的簽章；owner 身分一併納入，pool 重用的 component 不會沿用舊基準。 */
    static long signature(CraftLogic logic) {
        long h = mix(0L, System.identityHashCode(logic.getGameEntity()));
        CraftRecipeData test = logic.getCraftTestData();
        h = mix(h, test == null ? 0 : System.identityHashCode(test.getRecipe()));
        ArrayList<CraftRecipeData> inProgress = logic.getAllInProgressCraftData();
        int size = inProgress.size();
        h = mix(h, size);
        Map<?, ?> wetness = wetness(logic);
        for (int i = 0; i < size; i++) {
            CraftRecipeData data = inProgress.get(i);
            h = mix(h, System.identityHashCode(data));
            h = mix(h, System.identityHashCode(data.getRecipe()));
            h = mix(h, (int) (logic.getProgress(data) * 100.0));
            if (wetness != null) {
                Object value = wetness.get(data);
                double wet = value instanceof Double d ? d : 0.0;
                // tooltip 以 %.0f%% 顯示且 >0 即標記暫停；0 與「顯示 0% 但仍濕」要分開。
                h = mix(h, wet > 0.0 ? 1 + (int) Math.round(wet * 100.0) : 0);
            }
        }
        return h;
    }

    private static Map<?, ?> wetness(CraftLogic logic) {
        if (!(logic instanceof DryingCraftLogic)) {
            return null;
        }
        if (WETNESS == null) {
            throw new IllegalStateException("DryingCraftLogic.temporaryWetnesses unavailable");
        }
        try {
            return (Map<?, ?>) WETNESS.get(logic);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 每步對 h 為雙射：前綴相同時下一個值不同，結果必不同；更早的差異只剩 64-bit 雜湊碰撞機率。 */
    private static long mix(long h, int value) {
        h = (h + value) * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 29);
    }

    private static boolean sameAsLast(CraftLogic logic, long signature) {
        synchronized (LAST) {
            long[] last = LAST.get(logic);
            return last != null && last[0] == signature;
        }
    }

    private static void remember(CraftLogic logic, long signature) {
        synchronized (LAST) {
            long[] last = LAST.get(logic);
            if (last == null) {
                LAST.put(logic, new long[] {signature});
            } else {
                last[0] = signature;
            }
        }
    }

    private static void forget(CraftLogic logic) {
        synchronized (LAST) {
            LAST.remove(logic);
        }
    }

    private static Field wetnessField() {
        try {
            Field field = DryingCraftLogic.class.getDeclaredField("temporaryWetnesses");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static void maybeBeat() {
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        announced = true;
        lastBeatNs = now;
        try {
            DebugLog.log(TAG + "mode=" + MODE + " periodic=" + periodic + " sent=" + periodicSent
                    + " suppressed=" + suppressed + " wouldSuppress=" + wouldSuppress
                    + " explicit=" + explicit + " sigErrors=" + sigErrors
                    + " wetness=" + (WETNESS != null ? "ok" : "missing") + " logErrors=" + logErrors);
        } catch (RuntimeException failure) {
            logErrors++;
        }
    }

    private static int parseMode() {
        String raw = System.getProperty("mdc.craftLogicSyncGate");
        if (raw == null) {
            return ENFORCE;
        }
        return switch (raw.trim()) {
            case "0", "off" -> OFF;
            case "1", "enforce" -> ENFORCE;
            default -> OBSERVE;
        };
    }
}
