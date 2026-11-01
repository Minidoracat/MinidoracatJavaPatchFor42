package zombie.mdc;

import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import zombie.SoundManager;
import zombie.characters.DummyCharacterSoundEmitter;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalBreed;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/**
 * W92 動物循環音迴圈在伺服器整段跳過（2026-11-02；docs/patches.md 2da）。
 *
 * <p><b>背景</b>：{@code IsoCell.ProcessObjects} offset 10 唯一的 {@code objectList.iterator()} 之後，迴圈對每個不在鉤子上的
 * {@code IsoAnimal} 呼叫 {@code updateVocalProperties}（第一行 {@code !GameServer.server}）與 {@code updateLoopingSounds}。伺服器上
 * {@code SoundManager.instance} 是原版 {@code SoundManager}，{@code isListenerInRange} 恆 false，所以「動畫在跑」一律被改成 false、
 * {@code start} 到不了；動物 emitter 是 {@code DummyCharacterSoundEmitter}（{@code playVocals} 回 0），{@code eventInstance} 永遠 0、
 * {@code isPlaying()} 恆 false。整段沒有可觀測輸出（唯一的狀態變化是延後建立空的 {@code AnimalSoundState}，原版之後第一次取用時也會建）。
 *
 * <p><b>改什麼</b>：那個 {@code Set.iterator()} 同形改道 {@link #iterator}：伺服器 on 時回空 iterator，迴圈直接結束，接著照原版跑
 * {@code updateZombieVocals}。
 *
 * <p><b>模式</b>（{@code -Dmdc.animalLoopSoundGate}，需重啟）：{@code 2|observe}（預設）回原版 iterator，每 64 幀包一層量整段 µs；
 * {@code 1|on} 回空 iterator；{@code 0|off} 原版；未知值當 observe。observe 與 on 都每 256 幀稽核一次：{@code SoundManager.instance}
 * 是原版 {@code SoundManager}、每隻不在鉤子上的動物 emitter 是 {@code DummyCharacterSoundEmitter}、runloop／walkloop 的
 * {@code AnimalSoundState.eventInstance == 0}；不成立記 anomalies，on 就本次啟動永久回原版。helper 例外同樣回原版並停用（先停用再記錄）；
 * 日誌鏈本身的 RuntimeException／LinkageError 只記 anomalies、不外拋；Error（OOM／SOE）照常外拋。心跳每 5 分鐘一行，停用後照印。
 */
public final class AnimalLoopSoundGate {

    static final int OFF = 0;
    static final int ON = 1;
    static final int OBSERVE = 2;
    static final String RAW = System.getProperty("mdc.animalLoopSoundGate");
    static final int MODE = parseMode(RAW);
    static final int AUDIT_MASK = 255;
    static final int TIME_MASK = 63;
    private static final String[] LOOPS = {"runloop", "walkloop"};

    private static final String TAG = "[MinidoracatJavaPatch][AnimalLoopSoundGate] ";
    private static final long HEARTBEAT_MS = 300_000L;

    private static volatile boolean disabled;
    private static boolean started;
    private static long lastBeat;
    private static long frames, skipped, audits, anomalies, loopNs, loopMaxNs, loopSamples;
    private static int objects, animals;
    private static final Timed TIMED = new Timed();
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

    /**
     * {@code IsoCell.ProcessObjects} 唯一的 {@code objectList.iterator()}。心跳、稽核拋出的例外（RuntimeException／LinkageError）記 anomalies
     * 並停用；記錄本身的例外在 {@link #log} 內只記 anomalies、不停用。Error（OOM／SOE）照常外拋。原版 {@code set.iterator()} 在 try 外。
     */
    public static Iterator<?> iterator(Set<?> set) {
        if (MODE == OFF || !GameServer.server) {
            return set.iterator();
        }
        boolean skip = false;
        boolean time = false;
        try {
            long f = ++frames;
            tick();
            if (!disabled && (f & AUDIT_MASK) == 0) {
                audit(set);
            }
            if (!disabled) {
                objects = set.size();
                skip = MODE == ON;
                time = !skip && (f & TIME_MASK) == 0;
            }
        } catch (RuntimeException | LinkageError t) {
            skip = false;
            time = false;
            failed(t);
        }
        if (skip) {
            skipped++;
            return Collections.emptyIterator();
        }
        Iterator<?> it = set.iterator();
        if (time) {
            TIMED.it = it;
            TIMED.t0 = System.nanoTime();
            return TIMED;
        }
        return it;
    }

    private static void audit(Set<?> set) {
        audits++;
        if (SoundManager.instance == null || SoundManager.instance.getClass() != SoundManager.class) {
            anomaly("SoundManager.instance=" + (SoundManager.instance == null ? "null" : SoundManager.instance.getClass().getName()));
        }
        int n = 0;
        for (Object o : set) {
            if (!(o instanceof IsoAnimal a) || a.isOnHook()) {
                continue;
            }
            n++;
            if (!(a.getEmitter() instanceof DummyCharacterSoundEmitter)) {
                anomaly("emitter=" + (a.getEmitter() == null ? "null" : a.getEmitter().getClass().getName()) + " animal=" + a.getOnlineID());
            }
            AnimalBreed breed = a.getData() == null ? null : a.getData().getBreed();
            if (breed == null) {
                continue;
            }
            for (String loop : LOOPS) {
                AnimalBreed.Sound s = breed.getSound(loop);
                if (s != null && s.slot != null && a.getAnimalSoundState(s.slot).getEventInstance() != 0L) {
                    anomaly(loop + " eventInstance!=0 animal=" + a.getOnlineID());
                }
            }
        }
        animals = n;
    }

    private static void anomaly(String what) {
        anomalies++;
        if (anomalies <= 5) {
            log("anomaly #" + anomalies + " " + what);
        }
        if (MODE == ON) {
            disable(what);
        }
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        if (!started) {
            started = true;
            lastBeat = now;
            log("首次生效 mode=" + modeName() + " raw=" + RAW
                    + "（-Dmdc.animalLoopSoundGate=0|off 原版、1|on 伺服器跳過動物循環音迴圈、2|observe 只量；心跳每 5 分鐘一行）");
        }
        if (now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            beat();
        }
    }

    private static void disable(String reason) {
        if (disabled) {
            return;
        }
        disabled = true;
        log("DISABLED（本次啟動永久回原版）reason=" + reason + " frames=" + frames + " audits=" + audits);
    }

    /** helper 自己的例外：先停用（之後一律回原版 iterator），再記錄；組訊息或記錄的 RuntimeException／LinkageError 不外拋。 */
    private static void failed(Throwable t) {
        boolean first = !disabled;
        disabled = true;
        anomalies++;
        if (first) {
            try {
                log("DISABLED（本次啟動永久回原版）reason=helper exception " + t + " frames=" + frames + " audits=" + audits);
            } catch (RuntimeException | LinkageError ignored) {
                anomalies++;
            }
        }
    }

    private static void beat() {
        String line = "beat mode=" + modeName() + " frames=" + frames + " objects=" + objects + " animals=" + animals
                + " loopUsAvg=" + (loopSamples == 0 ? 0 : loopNs / loopSamples / 1000) + " loopUsMax=" + loopMaxNs / 1000
                + " loopSamples=" + loopSamples + " skipped=" + skipped + " audits=" + audits + " anomalies=" + anomalies
                + " disabled=" + disabled;
        lastBeatLine = line;
        log(line);
        frames = skipped = loopNs = loopMaxNs = loopSamples = 0;
    }

    static String modeName() {
        return MODE == OFF ? "off" : MODE == ON ? "on" : "observe";
    }

    /** 記錄不外拋：日誌鏈（格式化、輸出）的 RuntimeException／LinkageError 只記 anomalies，不影響原版呼叫；Error 照常外拋。 */
    private static void log(String s) {
        try {
            DebugLog.log(TAG + s);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies++;
        }
    }

    /** observe 抽樣：包原版 iterator，第一次 {@code hasNext()} 回 false 時記下整段迴圈的時間。 */
    static final class Timed implements Iterator<Object> {
        Iterator<?> it;
        long t0;

        @Override
        public boolean hasNext() {
            boolean r = it.hasNext();
            if (!r && t0 != 0L) {
                long dt = System.nanoTime() - t0;
                t0 = 0L;
                loopNs += dt;
                loopSamples++;
                if (dt > loopMaxNs) {
                    loopMaxNs = dt;
                }
            }
            return r;
        }

        @Override
        public Object next() {
            return it.next();
        }

        @Override
        public void remove() {
            it.remove();
        }
    }

    static void forceBeatForTest() {
        started = true;
        lastBeat = System.currentTimeMillis() - HEARTBEAT_MS;
    }

    static boolean disabledForTest() { return disabled; }
    static long anomaliesForTest() { return anomalies; }
    static long auditsForTest() { return audits; }
    static long loopSamplesForTest() { return loopSamples; }

    private AnimalLoopSoundGate() {}
}
