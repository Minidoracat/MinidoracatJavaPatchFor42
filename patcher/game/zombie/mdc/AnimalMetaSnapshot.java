package zombie.mdc;

import java.util.ArrayList;

import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * W38 畜牧區離線補算快照（2026-09-26；docs/patches.md 2ba）。
 *
 * <p><b>原版缺陷</b>：{@code DesignationZoneAnimal.doMeta} 以索引走訪 {@code this.animals} 逐隻
 * {@code updateStatsAway(hours)}；後者把 {@code zoneCheckTimer} 歸零後 {@code checkZone()} →
 * {@code setDZone()}，即使同一個 zone 也先 removeAnimal 再 addAnimal，把該動物移到清單尾端。
 * 結果同一幀有的動物被補算 2–5 次（飢渴／年齡×N，timeSinceLastUpdate 推進到未來），有的整個漏掉。
 * 正式服 9/25–9/26 約 36 小時補算後死亡約 133 隻，有明細的 42 隻中 27 隻被重複處理。
 *
 * <p><b>手術</b>：doMeta 頭部 {@link #begin}、每個 RETURN 前 {@link #end}，方法內 8 個
 * {@code GETFIELD animals} 之後 {@link #animals}：begin 之後第一次讀（已在 {@code check()} 重建清單之後）
 * 拍一份快照，本次 doMeta 其餘讀取都回快照，每隻動物每個迴圈恰好一次；補算本身不變。
 * doMeta 不會巢狀；若例外跳過 end，下一次 begin 覆蓋。kill switch {@code -Dmdc.animalMetaSnapshot=0}。
 */
public final class AnimalMetaSnapshot {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalMetaSnapshot"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalMetaSnapshot] ";
    private static final long HEARTBEAT_MS = 300_000L;

    // 只在主執行緒（zone 串流 → doMeta）。
    private static boolean armed;
    private static ArrayList<?> source;
    private static ArrayList<?> snapshot;
    private static long metas, animalsSeen, lastBeat;

    public static void begin(DesignationZoneAnimal zone) {
        armed = ENABLED;
        source = null;
        snapshot = null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList animals(ArrayList live) {
        if (!armed || live == null) {
            return live;
        }
        if (source != live) {
            source = live;
            snapshot = new ArrayList(live);
            metas++;
            animalsSeen += live.size();
        }
        return snapshot;
    }

    public static void end(DesignationZoneAnimal zone) {
        armed = false;
        source = null;
        snapshot = null;
        long now = System.currentTimeMillis();
        if (ENABLED && now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            DebugLog.log(TAG + "metas=" + metas + " animals=" + animalsSeen);
        }
    }

    static boolean enabledForTest() { return ENABLED; }

    private AnimalMetaSnapshot() {}
}
