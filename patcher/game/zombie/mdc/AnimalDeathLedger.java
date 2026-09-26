package zombie.mdc;

import java.util.Map;
import java.util.WeakHashMap;

import zombie.characters.CharacterStat;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * W39 動物死亡帳本（純觀測，2026-09-26；docs/patches.md 2bb）。
 *
 * <p>{@code IsoAnimal.OnDeath()} 頭部 {@link #onDeath}：每隻死亡記一行——種類#ID、座標、野生／幼體、
 * 年齡、健康、飢渴、所在畜牧區／雞舍、最近 60 秒內是否剛被離線補算（W32 三個呼叫點回報，含次數與時數），
 * 以及呼叫來源（跳過死亡機制本身後的前 4 個 {@code zombie.*} 幀，分辨飢渴、補算、玩家擊殺、宰殺）。
 * 每 60 秒最多 40 行明細，其餘只計數；每 5 分鐘一行彙總。不改任何行為。
 * kill switch {@code -Dmdc.animalDeathLedger=0}。
 */
public final class AnimalDeathLedger {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalDeathLedger"));
    private static final String TAG = "[MinidoracatJavaPatch][AnimalDeath] ";
    private static final long WINDOW_MS = 60_000L;
    private static final int WINDOW_LINES = 40;
    private static final long HEARTBEAT_MS = 300_000L;
    private static final long CATCHUP_RECENT_MS = 60_000L;

    // 動物死亡與補算都在主執行緒。{lastMs, count, hoursSum}
    private static final Map<IsoAnimal, long[]> CATCHUPS = new WeakHashMap<>();
    private static long deaths, domestic, wild, afterCatchUp, suppressed, anomalies;
    private static long windowStart, windowLines, lastBeat;

    /** W32 AnimalAwayProbe 每次離線補算都回報。 */
    static void noteCatchUp(IsoAnimal animal, int hours) {
        if (!ENABLED) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long[] c = CATCHUPS.get(animal);
            if (c == null || now - c[0] > CATCHUP_RECENT_MS) {
                c = new long[3];
                CATCHUPS.put(animal, c);
            }
            c[0] = now;
            c[1]++;
            c[2] += hours;
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    public static void onDeath(IsoAnimal animal) {
        if (!ENABLED) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            deaths++;
            boolean isWild = animal.isWild();
            if (isWild) {
                wild++;
            } else {
                domestic++;
            }
            long[] c = CATCHUPS.remove(animal);
            boolean recent = c != null && now - c[0] <= CATCHUP_RECENT_MS;
            if (recent) {
                afterCatchUp++;
            }
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                windowLines = 0;
            }
            if (windowLines < WINDOW_LINES) {
                windowLines++;
                DebugLog.log(TAG + describe(animal, isWild, recent ? c : null, now) + " via=" + callers());
            } else {
                suppressed++;
            }
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                DebugLog.log(TAG + "beat deaths=" + deaths + " domestic=" + domestic + " wild=" + wild
                        + " afterCatchUp=" + afterCatchUp + " suppressed=" + suppressed + " anomalies=" + anomalies);
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    static String describe(IsoAnimal a, boolean isWild, long[] catchUp, long now) {
        StringBuilder s = new StringBuilder(160);
        s.append("animal=").append(a.getAnimalType()).append('#').append(a.getAnimalID())
                .append(" pos=").append((int) a.getX()).append(',').append((int) a.getY()).append(',').append((int) a.getZ())
                .append(" wild=").append(isWild ? 1 : 0);
        AnimalData data = a.getData();
        if (data == null) {
            s.append(" data=null");
        } else {
            s.append(" baby=").append(a.isBaby() ? 1 : 0).append(" ageDays=").append(data.getAge());
        }
        s.append(" health=").append(round(a.getHealth()));
        if (a.getStats() != null) {
            s.append(" hunger=").append(round(a.getStats().get(CharacterStat.HUNGER)))
                    .append(" thirst=").append(round(a.getStats().get(CharacterStat.THIRST)));
        }
        DesignationZoneAnimal zone = a.getDZone();
        s.append(" zone=").append(zone == null ? "none" : String.valueOf(zone.getId()))
                .append(" hutch=").append(a.getHutch() != null ? 1 : 0);
        if (catchUp != null) {
            s.append(" catchUp=").append(catchUp[1]).append("x/").append(catchUp[2]).append('h')
                    .append(" catchUpAgoMs=").append(now - catchUp[0]);
        }
        return s.toString();
    }

    /** 跳過 helper 與死亡機制本身（OnDeath／DoDeath），取前 4 個遊戲幀。 */
    static String callers() {
        return StackWalker.getInstance().walk(frames -> {
            StringBuilder s = new StringBuilder();
            frames.filter(f -> f.getClassName().startsWith("zombie.")
                            && !f.getClassName().startsWith("zombie.mdc.")
                            && !f.getMethodName().equals("OnDeath") && !f.getMethodName().equals("DoDeath"))
                    .limit(4)
                    .forEach(f -> s.append(s.isEmpty() ? "" : "<")
                            .append(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1))
                            .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber()));
            return s.toString();
        });
    }

    private static String round(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    static boolean enabledForTest() { return ENABLED; }
    static long deathsForTest() { return deaths; }
    static long afterCatchUpForTest() { return afterCatchUp; }
    static long wildForTest() { return wild; }
    static long suppressedForTest() { return suppressed; }
    static long anomaliesForTest() { return anomalies; }

    private AnimalDeathLedger() {}
}
