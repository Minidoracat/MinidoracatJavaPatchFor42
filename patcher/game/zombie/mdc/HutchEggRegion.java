package zombie.mdc;

import java.util.Locale;

import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.objects.IsoHutch;

/**
 * W72 雞舍內動物產蛋時不再搜尋畜牧區雞舍（2026-10-25；docs/patches.md 2cg）。
 *
 * <p><b>量測</b>：正式服 45–48 人、FPS 9.2–9.6 時的 60 份主執行緒 dump，有 2 份停在
 * {@code HutchManager.updateAll → IsoHutch.update → AnimalData.checkEggs → getRegionHutch → getHutchsConnected →
 * getAllDZones → getZone}。雞舍內的母雞蛋時間到了、產蛋箱卻都有母雞或都已 10 顆蛋時，{@code addEgg} 回 false、
 * {@code eggTime} 不歸零，於是每 tick 重來；每次 {@code checkEggs} 算兩次 {@code getRegionHutch()}，每次又呼叫
 * 3 次 {@code getHutchsConnected()}（各是一次 666 塊畜牧區的相連搜尋，本機實測 15–34 µs）。
 *
 * <p><b>做法</b>：{@code checkEggs} 內 4 個 {@code getRegionHutch()} 改道 {@link #regionHutch}。動物已在雞舍內
 * （{@code parent.hutch != null}）時直接回傳 {@code parent.hutch}，否則照原版。等價理由（javap）：offset 254／261 之後的
 * {@code ifnull}／{@code ifne}／{@code ifnonnull} 都跳到同一個 offset 351（{@code addEgg(false)}），而 offset 277 讀的
 * 正是 {@code parent.hutch}，非 null 必跳 351；不論 {@code getRegionHutch()} 回什麼，雞舍內的動物都走到 351。
 * {@code getRegionHutch} 一路到 {@code getZone} 沒有欄位寫入，略過不改任何狀態或亂數。offset 281／295 只在
 * {@code parent.hutch == null} 時才到，照原版委派。唯一差別：原版搜尋本身丟例外（畜牧區雞舍清單含 null）時，
 * 雞舍內的動物不再丟。
 *
 * <p>{@code -Dmdc.hutchEggRegion}：{@code 1|on}（預設）、{@code 2|observe}（只計數、照原版搜尋並抽樣計時）、
 * {@code 0|off}（純委派、不計數）；其他值當 on；需重啟。只在主執行緒用（{@code IsoHutch.update}、{@code AnimalData}
 * 更新與補算），計數不加鎖。心跳每 5 分鐘一行 {@code [MinidoracatJavaPatch][HutchEggRegion]}：{@code skips} 是略過
 * （observe 時是可略過）的次數，{@code sampleUs} 是每 256 次略過抽量一次原版 {@code getRegionHutch()} 的平均耗時，
 * {@code estSavedMs}＝{@code skips}×{@code sampleUs}（observe 時是可省下的量）。
 */
public final class HutchEggRegion {
    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_OBSERVE = 2;
    static final int MODE = parseMode(System.getProperty("mdc.hutchEggRegion"));

    private static final int SAMPLE_MASK = 255;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][HutchEggRegion] ";

    private static long calls;
    private static long skips;
    private static long samples;
    private static long sampleNs;
    private static long sampleErrors;
    private static long logErrors;
    private static long lastBeatNs;
    private static boolean announced;

    private HutchEggRegion() {
    }

    static int parseMode(String raw) {
        if (raw == null) {
            return MODE_ON;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0":
            case "off":
                return MODE_OFF;
            case "2":
            case "observe":
                return MODE_OBSERVE;
            default:
                return MODE_ON;
        }
    }

    /** {@code AnimalData.checkEggs} 內 {@code getRegionHutch()} 的同形改道。 */
    public static IsoHutch regionHutch(AnimalData data) {
        if (MODE == MODE_OFF) {
            return data.getRegionHutch();
        }
        calls++;
        IsoAnimal parent = data.parent;
        IsoHutch inside = parent == null ? null : parent.hutch;
        if (inside == null) {
            maybeBeat();
            return data.getRegionHutch();
        }
        skips++;
        boolean sample = (skips & SAMPLE_MASK) == 1L;
        if (MODE == MODE_OBSERVE) {
            maybeBeat();
            if (!sample) {
                return data.getRegionHutch();
            }
            long start = System.nanoTime();
            IsoHutch vanilla = data.getRegionHutch();
            sampleNs += System.nanoTime() - start;
            samples++;
            return vanilla;
        }
        if (sample) {
            // 只計時：原版搜尋沒有副作用，結果丟掉；它丟的例外原版也會丟，這裡吞掉只計數，回傳照常。
            long start = System.nanoTime();
            try {
                data.getRegionHutch();
                sampleNs += System.nanoTime() - start;
                samples++;
            } catch (RuntimeException e) {
                sampleErrors++;
            }
        }
        maybeBeat();
        return inside;
    }

    private static void maybeBeat() {
        if (announced && (calls & 63L) != 0L) {
            return;
        }
        long now = System.nanoTime();
        if (announced && now - lastBeatNs < BEAT_NS) {
            return;
        }
        String head = announced ? "" : "首次生效（-Dmdc.hutchEggRegion=0|off 停用、2|observe 只計數） ";
        announced = true;
        lastBeatNs = now;
        try {
            DebugLog.log(beatLine(head));
        } catch (RuntimeException e) {
            logErrors++;
        }
    }

    static String beatLine(String head) {
        long avgNs = samples == 0L ? 0L : sampleNs / samples;
        return TAG + head + "mode=" + MODE + " calls=" + calls + " skips=" + skips
                + " samples=" + samples + " sampleUs=" + String.format(Locale.ROOT, "%.1f", avgNs / 1000.0)
                + " estSavedMs=" + skips * avgNs / 1_000_000L + " sampleErrors=" + sampleErrors
                + " logErrors=" + logErrors;
    }

    // ---- 測試用 ----

    static long callsForTest() {
        return calls;
    }

    static long skipsForTest() {
        return skips;
    }

    static long samplesForTest() {
        return samples;
    }

    static long sampleErrorsForTest() {
        return sampleErrors;
    }
}
