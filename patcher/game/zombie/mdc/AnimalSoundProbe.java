package zombie.mdc;

import java.util.List;

import zombie.MovingObjectUpdateScheduler;
import zombie.WorldSoundManager;
import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;
import zombie.iso.IsoUtils;

/**
 * W48 動物聽覺掃描量測（2026-09-28；docs/patches.md 2bk）。純觀測：回傳值與例外都與原版相同。
 *
 * <p><b>原版</b>：伺服器每隻動物每個 tick 在 {@code IsoAnimal.updateInternal → respondToSound} 呼叫一次
 * {@code WorldSoundManager.getSoundAnimal}。client 只看動物所在 chunk 的聲音清單，伺服器（{@code GameServer.server}）
 * 卻整份掃過全域 {@code soundList}，挑出會影響動物（{@code stresshumans || stressAnimals}）且在範圍內最大聲的一個，
 * 成本是「動物數 × 全世界聲音數」。9/25 晚峰 JFR 把約 4.7% 記在下一行的 {@code getSoundAttractAnimal}，
 * 歸屬不精確，實際成本待量。
 *
 * <p><b>手術</b>：{@code respondToSound} 內唯一的 {@code getSoundAnimal} 呼叫 1:1 改道本類（receiver 前置），
 * 委派原版並計時；每 64 次抽一次重掃清單，數出會影響動物的聲音與其中在範圍內的數量。這些數字決定下一步：
 * 只含會影響動物的精簡清單就夠，還是需要依位置分區。
 *
 * <p>計數只在主執行緒更新。簿記失敗只計 {@code anomalies}，不影響回傳；原版例外原樣上拋（不計入 calls）。
 * kill switch {@code -Dmdc.animalSoundProbe=0|off}：直接委派，不計時也不計數。
 */
public final class AnimalSoundProbe {
    static final boolean ENABLED = parseEnabled(System.getProperty("mdc.animalSoundProbe"));
    private static final int SAMPLE_MASK = 63;
    private static final long BEAT_NS = 300_000_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalSoundProbe] ";

    // ---- 主執行緒單寫 ----
    private static long calls;
    private static long hits;
    private static long nsSum;
    private static long nsMax;
    private static long listSum;
    private static long listMax;
    private static long frames;
    private static long lastFrame = Long.MIN_VALUE;
    private static long frameCalls;
    private static long frameCallsMax;
    private static long frameNs;
    private static long frameNsMax;
    /** 同一幀內，前後兩隻動物之間聲音清單變了幾次（大小或尾端元素不同）。 */
    private static long changes;
    private static int lastSize = -1;
    private static Object lastTail;
    private static long samples;
    private static long eligibleSum;
    private static long eligibleMax;
    private static long inRangeSum;
    private static long inRangeMax;
    private static long anomalies;
    private static long lastBeatNs;
    private static long beatNsSum;
    private static boolean announced;

    private AnimalSoundProbe() {
    }

    static boolean parseEnabled(String raw) {
        return raw == null || !("0".equals(raw.trim()) || "off".equalsIgnoreCase(raw.trim()));
    }

    /** 取代 {@code IsoAnimal.respondToSound} 內的 {@code WorldSoundManager.getSoundAnimal(this)}。 */
    public static WorldSoundManager.WorldSound getSoundAnimal(WorldSoundManager manager, IsoAnimal animal) {
        long t0 = ENABLED ? System.nanoTime() : 0L;
        WorldSoundManager.WorldSound result = manager.getSoundAnimal(animal);
        if (ENABLED) {
            long ns = System.nanoTime() - t0;
            try {
                record(manager, animal, result, ns);
            } catch (RuntimeException e) {
                anomalies++;
            }
            maybeBeat();
        }
        return result;
    }

    private static void record(WorldSoundManager manager, IsoAnimal animal, WorldSoundManager.WorldSound result, long ns) {
        calls++;
        if (result != null) {
            hits++;
        }
        nsSum += ns;
        if (ns > nsMax) {
            nsMax = ns;
        }
        List<WorldSoundManager.WorldSound> list = manager.soundList;
        int size = list.size();
        listSum += size;
        if (size > listMax) {
            listMax = size;
        }
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        if (frame != lastFrame) {
            closeFrame();
            lastFrame = frame;
            frames++;
            lastSize = -1;
            lastTail = null;
        }
        frameCalls++;
        frameNs += ns;
        Object tail = size > 0 ? list.get(size - 1) : null;
        if (lastSize >= 0 && (size != lastSize || tail != lastTail)) {
            changes++;
        }
        lastSize = size;
        lastTail = tail;
        if ((calls & SAMPLE_MASK) == 0L) {
            sample(list, animal);
        }
    }

    private static void closeFrame() {
        if (frameCalls > frameCallsMax) {
            frameCallsMax = frameCalls;
        }
        if (frameNs > frameNsMax) {
            frameNsMax = frameNs;
        }
        frameCalls = 0L;
        frameNs = 0L;
    }

    /** 與原版同一條件與距離算式，只計數，不改任何狀態。 */
    private static void sample(List<WorldSoundManager.WorldSound> list, IsoAnimal animal) {
        float ax = animal.getX();
        float ay = animal.getY();
        float az = animal.getZ() * 3.0F;
        float bonus = animal.isWild() ? 3.0F : 1.0F;
        long eligible = 0L;
        long inRange = 0L;
        for (int i = 0, n = list.size(); i < n; i++) {
            WorldSoundManager.WorldSound sound = list.get(i);
            if (sound == null || !(sound.stresshumans || sound.stressAnimals)) {
                continue;
            }
            eligible++;
            float radius = sound.radius * bonus;
            if (!(IsoUtils.DistanceToSquared(ax, ay, az, sound.x, sound.y, sound.z * 3.0F) > radius * radius)) {
                inRange++;
            }
        }
        samples++;
        eligibleSum += eligible;
        if (eligible > eligibleMax) {
            eligibleMax = eligible;
        }
        inRangeSum += inRange;
        if (inRange > inRangeMax) {
            inRangeMax = inRange;
        }
    }

    /** 每 4096 次呼叫才讀一次時鐘；每 5 分鐘一行。首次生效立即一行。 */
    private static void maybeBeat() {
        if ((calls & 0xFFFL) != 1L) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (announced && now - lastBeatNs < BEAT_NS) {
                return;
            }
            String window = "";
            if (announced) {
                window = " windowPct=" + String.format(java.util.Locale.ROOT, "%.2f",
                        (nsSum - beatNsSum) * 100.0 / Math.max(1L, now - lastBeatNs));
            }
            String head = announced ? "beat " : "首次生效（純觀測；-Dmdc.animalSoundProbe=0|off 停用）";
            announced = true;
            lastBeatNs = now;
            beatNsSum = nsSum;
            long f = Math.max(1L, frames);
            long s = Math.max(1L, samples);
            DebugLog.log(TAG + head + "calls=" + calls + " frames=" + frames
                    + " callsPerFrame=" + calls / f + "/" + Math.max(frameCallsMax, frameCalls)
                    + " nsAvg=" + nsSum / Math.max(1L, calls) + " usMax=" + nsMax / 1000L
                    + " frameUsAvg=" + nsSum / f / 1000L + " frameUsMax=" + Math.max(frameNsMax, frameNs) / 1000L
                    + " list=" + listSum / Math.max(1L, calls) + "/" + listMax
                    + " samples=" + samples + " eligible=" + eligibleSum / s + "/" + eligibleMax
                    + " inRange=" + inRangeSum / s + "/" + inRangeMax
                    + " hits=" + hits + " changesPerFrame=" + changes / f
                    + window + " anomalies=" + anomalies);
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    // ---- 測試存取器 ----
    static long callsForTest() {
        return calls;
    }

    static long hitsForTest() {
        return hits;
    }

    static long framesForTest() {
        return frames;
    }

    static long frameCallsMaxForTest() {
        return Math.max(frameCallsMax, frameCalls);
    }

    static long changesForTest() {
        return changes;
    }

    static long samplesForTest() {
        return samples;
    }

    static long eligibleSumForTest() {
        return eligibleSum;
    }

    static long inRangeSumForTest() {
        return inRangeSum;
    }

    static long listMaxForTest() {
        return listMax;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
