package zombie.mdc;

import java.util.Locale;

import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/**
 * W94 伺服器跳過 {@code BodyDamage.setBodyPartsLastState}（2026-11-02；docs/patches.md 2dc）。
 *
 * <p><b>背景</b>：{@code IsoPlayer.postupdateInternal} offset 121（玩家與動物都走這裡）每次把 17 個 {@code BodyPart} 的包紮、咬傷、
 * 抓傷、割傷狀態抄進 {@code bodyPartsLastState}。這份狀態只經 {@code getBodyPartsLastState} 讀，全 jar 唯一的呼叫者
 * {@code IsoGameCharacter$Bandages.update} 開頭 {@code GameServer.server} 就回傳；不存檔、不進封包。伺服器上沒有讀者。
 *
 * <p><b>改什麼</b>：那個呼叫同形改道 {@link #setBodyPartsLastState}：伺服器 on 時跳過，其他情況照原版。
 *
 * <p><b>模式</b>（{@code -Dmdc.bodyLastStateSkip}，需重啟）：{@code 2|observe}（預設）照原版呼叫，分玩家／動物計數，每 64 次量一次 µs；
 * {@code 1|on} 跳過；{@code 0|off} 原版；未知值當 observe。心跳每 5 分鐘一行。心跳與記錄（格式化、輸出）在不外拋的邊界內，
 * RuntimeException／LinkageError 只記 anomalies，Error（OOM／SOE）照常外拋；原版呼叫不在 try 內。
 */
public final class BodyLastStateGate {

    static final int OFF = 0;
    static final int ON = 1;
    static final int OBSERVE = 2;
    static final String RAW = System.getProperty("mdc.bodyLastStateSkip");
    static final int MODE = parseMode(RAW);
    static final int TIME_MASK = 63;

    private static final String TAG = "[MinidoracatJavaPatch][BodyLastStateGate] ";
    private static final long HEARTBEAT_MS = 300_000L;

    private static boolean started;
    private static long lastBeat;
    private static long calls, players, animals, skipped, sampleNs, samples, anomalies;
    static String lastBeatLine;

    static int parseMode(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            case "1", "on" -> ON;
            default -> OBSERVE;
        };
    }

    /** {@code IsoPlayer.postupdateInternal} 唯一的 {@code getBodyDamage().setBodyPartsLastState()}。 */
    public static void setBodyPartsLastState(BodyDamage bd) {
        if (MODE == OFF || !GameServer.server) {
            bd.setBodyPartsLastState();
            return;
        }
        if ((++calls & 1023) == 0) {
            tick();
        }
        if (bd.getParentChar() instanceof IsoAnimal) {
            animals++;
        } else {
            players++;
        }
        if (MODE == ON) {
            skipped++;
            return;
        }
        if ((calls & TIME_MASK) != 0) {
            bd.setBodyPartsLastState();
            return;
        }
        long t0 = System.nanoTime();
        bd.setBodyPartsLastState();
        sampleNs += System.nanoTime() - t0;
        samples++;
    }

    /** 首次生效與心跳；整段不外拋（RuntimeException／LinkageError 只記 anomalies，Error 照常外拋），狀態先更新再記錄，失敗也不會每次重試。 */
    private static void tick() {
        try {
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                lastBeat = now;
                log("首次生效 mode=" + modeName() + " raw=" + RAW
                        + "（-Dmdc.bodyLastStateSkip=0|off 原版、1|on 伺服器跳過、2|observe 只量；心跳每 5 分鐘一行）");
            }
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        } catch (RuntimeException | LinkageError t) {
            anomalies++;
        }
    }

    private static void beat() {
        String line = "beat mode=" + modeName() + " players=" + players + " animals=" + animals + " skipped=" + skipped
                + " usAvg=" + (samples == 0 ? "0" : String.format(Locale.ROOT, "%.2f", sampleNs / 1000.0 / samples))
                + " samples=" + samples + " anomalies=" + anomalies;
        players = animals = skipped = sampleNs = samples = 0;
        lastBeatLine = line;
        log(line);
    }

    static String modeName() {
        return MODE == OFF ? "off" : MODE == ON ? "on" : "observe";
    }

    /** 記錄不外拋：日誌鏈的 RuntimeException／LinkageError 只記 anomalies；Error 照常外拋。 */
    private static void log(String s) {
        try {
            DebugLog.log(TAG + s);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies++;
        }
    }

    static void forceBeatForTest() {
        armBeatForTest();
        tick();
    }

    /** 下一次 tick（第 1024 的倍數次呼叫）就印心跳。 */
    static void armBeatForTest() {
        started = true;
        lastBeat = System.currentTimeMillis() - HEARTBEAT_MS;
    }

    static long anomaliesForTest() { return anomalies; }

    static long playersForTest() { return players; }
    static long animalsForTest() { return animals; }
    static long skippedForTest() { return skipped; }
    static long samplesForTest() { return samples; }

    private BodyLastStateGate() {}
}
