package zombie.mdc;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * W38 畜牧區離線補算每隻一次（2026-09-26；2026-11-10 42.22.2 改寫；docs/patches.md 2ba）。
 *
 * <p><b>42.21 原版缺陷</b>：{@code DesignationZoneAnimal.doMeta} 以索引走訪 {@code this.animals}，補算中
 * {@code checkZone()→setDZone()} 先 remove 再 add 把動物移到清單尾端，同一幀有的動物被補算 2–5 次、有的漏掉
 * （正式服 9/25–9/26 約 36 小時補算後死亡約 133 隻，有明細的 42 隻中 27 隻被重複處理）。當時的手術是把 8 個
 * {@code GETFIELD animals} 換成本次快照。
 *
 * <p><b>42.22.2 原版</b>：{@code animals} 改成 HashSet，doMeta 走 {@code getAnimalsInZone()} 的複本，不再重排；但幼體迴圈寫成
 * {@code for (i < set.size()) for (animal : set) if (isBaby) updateStatsAway(hours)}，每隻幼體被補算「畜牧區動物總數」次。
 * W42 有自身時鐘時總補算時數不超過自身離線時數，但多出來的每一次仍委派、跑 checkZone 與記帳；沒有時鐘或 W42 關閉時就是 N 倍補算。
 *
 * <p><b>手術</b>：doMeta 頭部 {@link #begin}、唯一 RETURN 前 {@link #end}；W32 的 {@code updateStatsAwayZone} 先問
 * {@link #first}，同一次 doMeta 內第二次遇到同一隻就不委派。doMeta 不會巢狀；例外跳過 end 時下一次 begin 重設。
 * kill switch {@code -Dmdc.animalMetaSnapshot=0}（每次都委派，回原版 N 倍）。
 */
public final class AnimalMetaSnapshot {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalMetaSnapshot"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalMetaSnapshot] ";
    private static final long HEARTBEAT_MS = 300_000L;

    // 只在主執行緒（zone 串流 → doMeta）。
    private static boolean armed;
    private static final Set<IsoAnimal> DONE = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long metas, animalsSeen, repeats, lastBeat;

    public static void begin(DesignationZoneAnimal zone) {
        armed = ENABLED;
        DONE.clear();
        if (armed) {
            metas++;
        }
    }

    /** 本次 doMeta 第一次遇到這隻動物（或不在 doMeta 內、或 kill switch 關閉）回 true；重複的回 false。 */
    static boolean first(IsoAnimal animal) {
        if (!armed) {
            return true;
        }
        if (DONE.add(animal)) {
            animalsSeen++;
            return true;
        }
        repeats++;
        return false;
    }

    public static void end(DesignationZoneAnimal zone) {
        armed = false;
        DONE.clear();
        long now = System.currentTimeMillis();
        if (ENABLED && now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            DebugLog.log(TAG + "metas=" + metas + " animals=" + animalsSeen + " repeats=" + repeats);
        }
    }

    static boolean enabledForTest() { return ENABLED; }
    static long repeatsForTest() { return repeats; }

    private AnimalMetaSnapshot() {}
}
