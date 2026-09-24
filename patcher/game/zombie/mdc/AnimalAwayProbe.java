package zombie.mdc;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZone;

/**
 * W32 動物離線補算觀測（2026-09-24；docs/patches.md 2au）。純 observe，不改行為。
 *
 * <p><b>症狀</b>（正式服 9/24 13:01:53／14:22:27）：同一牧場 chunk 重新載入時主迴圈凍結約 13s，
 * 隨即同秒 6 隻動物死亡、地上大量糞便、豬群一口氣 12 胎。兩張 MainLoopWatchdog 快照同為
 * {@code AnimalManagerMain.fromWorker → IsoAnimal.updateStatsAway → AnimalData.hourGrow}。
 *
 * <p><b>待證假說</b>：vanilla 以 {@code worldAgeHours - zone.hourLastSeen} 當離線時數，
 * 但 {@code DesignationZone.hourLastSeen} 只在「整個 zone 兩角都離開串流」時更新；
 * 大圍場橫跨多 chunk、角落 chunk 一直載入時，中間 chunk 卸載重載會拿到陳舊值，
 * 每次重載都重補數天。對照組＝動物自身 {@code timeSinceLastUpdate}（{@code unloaded()} 寫入）
 * 推得的真實離線時數。
 *
 * <p><b>手術</b>：全 jar 三個 {@code updateStatsAway(I)V} 呼叫點 1:1 改道——{@code fromWorker} ×1
 * （source=chunk）與 {@code DesignationZoneAnimal.doMeta} ×2（source=zone，同樣用 hourLastSeen）；委派 vanilla、
 * 計時、比對兩種時數。kill switch {@code -Dmdc.animalAwayProbe=0}（純直通）。
 * vanilla 例外原樣穿透；只有診斷失敗計 anomalies。
 */
public final class AnimalAwayProbe {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalAwayProbe"));
    /** 超過此時數（zone 值）才逐筆記錄。 */
    private static final int DETAIL_HOURS = Integer.getInteger("mdc.animalAwayProbe.detailHours", 24);
    private static final long WINDOW_MS = 60_000L;
    private static final int WINDOW_LINES = 30;
    private static final long HEARTBEAT_MS = 300_000L;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalAwayProbe] ";

    // 只在主執行緒（fromWorker）呼叫；計數不需原子。
    private static long calls, big, mismatch, died, anomalies, suppressed;
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

    private static void observe(IsoAnimal animal, int hours, String source) {
        if (!ENABLED) {
            animal.updateStatsAway(hours);
            return;
        }
        // 必須在委派前讀：vanilla 會推進 timeSinceLastUpdate。
        long animalHours = animalHoursAway(animal);
        long t0 = System.nanoTime();
        animal.updateStatsAway(hours);
        long ns = System.nanoTime() - t0;
        record(animal, hours, animalHours, ns, source);
    }

    /** 動物自身離線時數；-1＝無紀錄（新生或舊存檔）。 */
    static long animalHoursAway(IsoAnimal animal) {
        try {
            long last = animal.timeSinceLastUpdate;
            if (last <= 0L) {
                return -1L;
            }
            return (GameTime.getInstance().getCalender().getTimeInMillis() - last) / 3_600_000L;
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            return -1L;
        }
    }

    private static void record(IsoAnimal animal, int hours, long animalHours, long ns, String source) {
        try {
            calls++;
            sumHours += Math.max(hours, 0);
            totalNs += ns;
            maxNs = Math.max(maxNs, ns);
            maxHours = Math.max(maxHours, hours);
            maxAnimalHours = Math.max(maxAnimalHours, animalHours);
            boolean dead = animal.isDead();
            if (dead) {
                died++;
            }
            if (animalHours >= 0 && hours - animalHours >= 24) {
                mismatch++;
            }
            if (hours >= DETAIL_HOURS) {
                big++;
                detail(animal, hours, animalHours, ns, dead, source);
            }
            long now = System.currentTimeMillis();
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                DebugLog.log(TAG + "calls=" + calls + " big=" + big + " mismatch=" + mismatch
                        + " died=" + died + " maxHours=" + maxHours + " maxAnimalHours=" + maxAnimalHours
                        + " sumHours=" + sumHours + " totalMs=" + totalNs / 1_000_000L
                        + " maxMs=" + maxNs / 1_000_000L + " suppressed=" + suppressed
                        + " anomalies=" + anomalies + " detailHours=" + DETAIL_HOURS);
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void detail(IsoAnimal animal, int hours, long animalHours, long ns, boolean dead, String source) {
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
        DebugLog.log(TAG + "source=" + source + " hoursAway=" + hours + " animalHoursAway=" + animalHours
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
    static long callsForTest() { return calls; }
    static long bigForTest() { return big; }
    static long mismatchForTest() { return mismatch; }
    static long anomaliesForTest() { return anomalies; }

    private AnimalAwayProbe() {}
}
