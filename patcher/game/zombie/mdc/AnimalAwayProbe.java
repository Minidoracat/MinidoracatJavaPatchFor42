package zombie.mdc;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZone;

/**
 * W32 動物離線補算觀測（2026-09-24；docs/patches.md 2au）＋W42 補算時數上限（2026-09-27；2be）。
 *
 * <p><b>症狀</b>（正式服 9/24 13:01:53／14:22:27）：同一牧場 chunk 重新載入時主迴圈凍結約 13s，
 * 隨即同秒 6 隻動物死亡、地上大量糞便、豬群一口氣 12 胎。兩張 MainLoopWatchdog 快照同為
 * {@code AnimalManagerMain.fromWorker → IsoAnimal.updateStatsAway → AnimalData.hourGrow}。
 *
 * <p><b>成因</b>：vanilla 以 {@code worldAgeHours - zone.hourLastSeen} 當離線時數，
 * 但 {@code DesignationZone.hourLastSeen} 只在「整個 zone 兩角都離開串流」時更新；
 * 大圍場部分 chunk 重載、或 zone 在關機時仍串流中，都會拿到陳舊值、重補數天。
 * 9/26 20:06 session：174/650 筆補算比動物自身離線時間多 ≥24h，38 隻死亡中 25 隻在補算後 60 秒內。
 *
 * <p><b>W32 手術</b>：全 jar 三個 {@code updateStatsAway(I)V} 呼叫點 1:1 改道——{@code fromWorker} ×1
 * （source=chunk）與 {@code DesignationZoneAnimal.doMeta} ×2（source=zone）；委派 vanilla、計時、比對兩種時數。
 * kill switch {@code -Dmdc.animalAwayProbe=0}（不計數、不記錄）。
 *
 * <p><b>W42</b>：補算時數取 {@code min(zone 時數, 動物自身離線時數)}；動物自身時鐘
 * {@code timeSinceLastUpdate} 由 {@code unloaded()} 寫入，W42 另在活著的每小時（{@code AnimalData.update}
 * 內唯一 {@code hourGrow(false)}）刷新，否則一直載入中的動物時鐘停在上次卸載、上限無效。
 * 無時鐘紀錄（-1，新生或舊存檔）時沿用 vanilla 時數；時鐘在未來（先前多補）時補 0 小時。
 * 只會減少補算、不會增加。kill switch {@code -Dmdc.animalCatchUpCap=0}（回 vanilla 時數且不刷新時鐘）。
 * vanilla 例外原樣穿透；只有診斷失敗計 anomalies。
 */
public final class AnimalAwayProbe {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalAwayProbe"));
    private static final boolean CAP = !"0".equals(System.getProperty("mdc.animalCatchUpCap"));
    /** 超過此時數（zone 值）計入 big。 */
    private static final int DETAIL_HOURS = Integer.getInteger("mdc.animalAwayProbe.detailHours", 24);
    /** zone 時數比動物自身多出此時數以上才算多算（mismatch）並逐筆記錄。 */
    private static final int OVERSHOOT_HOURS = 24;
    /** 無時鐘紀錄（新生或舊存檔）。 */
    static final long NO_RECORD = Long.MIN_VALUE;
    private static final long WINDOW_MS = 60_000L;
    private static final int WINDOW_LINES = 30;
    private static final long HEARTBEAT_MS = 300_000L;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalAwayProbe] ";

    // 只在主執行緒（fromWorker／doMeta／動物 update）呼叫；計數不需原子。
    private static long calls, big, mismatch, died, anomalies, suppressed, capped, cappedHours, clockRefresh;
    private static long maxHours, maxAnimalHours, sumHours, totalNs, maxNs;
    private static long windowStart, windowLines, lastBeat;

    /** {@code AnimalManagerMain.fromWorker}：chunk 載入時單隻動物補算。 */
    public static void updateStatsAway(IsoAnimal animal, int hours) {
        observe(animal, hours, "chunk");
    }

    /** {@code DesignationZoneAnimal.doMeta}：zone 重新完整串流時整區補算（兩處 callsite）。 */
    public static void updateStatsAwayZone(IsoAnimal animal, int hours) {
        observe(animal, hours, "zone");
    }

    /** {@code AnimalData.update} 內唯一 {@code hourGrow(false)}：活著的每小時刷新動物自身時鐘。 */
    public static void liveHourGrow(AnimalData data, boolean meta) {
        if (CAP && data.parent != null) {
            data.parent.timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis();
            clockRefresh++;
        }
        data.hourGrow(meta);
    }

    private static void observe(IsoAnimal animal, int hours, String source) {
        // 必須在委派前讀：vanilla 會推進 timeSinceLastUpdate。
        long animalHours = ENABLED || CAP ? animalHoursAway(animal) : NO_RECORD;
        int applied = CAP ? cap(hours, animalHours) : hours;
        AnimalDeathLedger.noteCatchUp(animal, applied);
        if (!ENABLED) {
            animal.updateStatsAway(applied);
            return;
        }
        long t0 = System.nanoTime();
        animal.updateStatsAway(applied);
        long ns = System.nanoTime() - t0;
        record(animal, hours, applied, animalHours, ns, source);
    }

    /** 補算時數上限：無紀錄或非正時數沿用 vanilla；否則不超過動物自身離線時數（在未來＝0）。 */
    static int cap(int hours, long animalHours) {
        if (hours <= 0 || animalHours == NO_RECORD) {
            return hours;
        }
        return (int) Math.max(0L, Math.min(hours, animalHours));
    }

    /** 動物自身離線時數；{@link #NO_RECORD}＝無紀錄；負值＝時鐘在未來。 */
    static long animalHoursAway(IsoAnimal animal) {
        try {
            long last = animal.timeSinceLastUpdate;
            if (last <= 0L) {
                return NO_RECORD;
            }
            return Math.floorDiv(GameTime.getInstance().getCalender().getTimeInMillis() - last, 3_600_000L);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            return NO_RECORD;
        }
    }

    private static void record(IsoAnimal animal, int hours, int applied, long animalHours, long ns, String source) {
        try {
            calls++;
            sumHours += Math.max(applied, 0);
            totalNs += ns;
            maxNs = Math.max(maxNs, ns);
            maxHours = Math.max(maxHours, hours);
            maxAnimalHours = Math.max(maxAnimalHours, animalHours);
            if (applied < hours) {
                capped++;
                cappedHours += hours - applied;
            }
            boolean dead = animal.isDead();
            if (dead) {
                died++;
            }
            if (hours >= DETAIL_HOURS) {
                big++;
            }
            boolean overshoot = animalHours != NO_RECORD && hours - animalHours >= OVERSHOOT_HOURS;
            if (overshoot) {
                mismatch++;
            }
            if (overshoot || dead) {
                detail(animal, hours, applied, animalHours, ns, dead, source);
            }
            long now = System.currentTimeMillis();
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                DebugLog.log(TAG + "calls=" + calls + " big=" + big + " mismatch=" + mismatch
                        + " capped=" + capped + " cappedHours=" + cappedHours + " clockRefresh=" + clockRefresh
                        + " died=" + died + " maxHours=" + maxHours + " maxAnimalHours=" + maxAnimalHours
                        + " sumAppliedHours=" + sumHours + " totalMs=" + totalNs / 1_000_000L
                        + " maxMs=" + maxNs / 1_000_000L + " suppressed=" + suppressed
                        + " anomalies=" + anomalies + " cap=" + (CAP ? 1 : 0));
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void detail(IsoAnimal animal, int hours, int applied, long animalHours, long ns, boolean dead,
            String source) {
        long now = System.currentTimeMillis();
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now;
            windowLines = 0;
        }
        if (++windowLines > WINDOW_LINES) {
            suppressed++;
            return;
        }
        DesignationZone z = animal.getZone();
        DebugLog.log(TAG + "source=" + source + " hoursAway=" + hours + " applied=" + applied
                + " animalHoursAway=" + (animalHours == NO_RECORD ? "none" : String.valueOf(animalHours))
                + " worldAgeHours=" + (int) GameTime.getInstance().getWorldAgeHours()
                + " ms=" + ns / 1_000_000L + " dead=" + (dead ? 1 : 0)
                + " animal=" + animal.getAnimalType() + "#" + animal.getAnimalID()
                + " pos=" + (int) animal.getX() + "," + (int) animal.getY() + "," + (int) animal.getZ()
                + (z == null ? " zone=null"
                        : " zone=" + z.getId() + " name=" + z.getName() + " hourLastSeen=" + z.hourLastSeen
                        + " rect=" + z.x + "," + z.y + "," + z.w + "x" + z.h
                        + " streamed=" + (z.streamed ? 1 : 0)));
    }

    // ---- 測試存取器 ----
    static boolean enabledForTest() { return ENABLED; }
    static boolean capForTest() { return CAP; }
    static long callsForTest() { return calls; }
    static long bigForTest() { return big; }
    static long mismatchForTest() { return mismatch; }
    static long cappedForTest() { return capped; }
    static long anomaliesForTest() { return anomalies; }

    private AnimalAwayProbe() {}
}
