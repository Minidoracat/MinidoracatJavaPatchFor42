package zombie.mdc;

import java.util.WeakHashMap;

import org.lwjgl.util.vector.Matrix4f;

import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.debug.DebugLog;

/**
 * W54 車輛靜止姿勢跳過（client；docs/patches.md 2br）。
 *
 * <p>BaseVehicle.updateAnimationPlayer 內唯一的 {@code AnimationPlayer.Update(F)V} 改道到
 * {@link #update}。原版每幀對每個非 static 零件模型無條件重算整副骨架；所有 track 都沒在播放、
 * 姿勢輸入（track、clip、時間、方向、權重、layer、priority、遮罩、model、skinningData、角度）
 * 與上一次完整計算相同、而且同一組輸入已連續完整算過兩次時，重算的結果與上一次相同，
 * renderer 讀的是上一次算好的矩陣，所以跳過。
 *
 * <p>每 {@value #SAMPLE_EVERY} 次跳過抽 1 次照算並比對 modelTransforms；不一致就本次啟動
 * 永久回原版。{@code -Dmdc.vehAnimSkip=on|verify|off}：verify 永遠照算、只比對與計數。
 * 只在第一個呼叫的執行緒（主執行緒）運作，其他執行緒一律照原版。
 */
public final class VehicleAnimGate {

    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_VERIFY = 2;
    static final String RAW_MODE = System.getProperty("mdc.vehAnimSkip");
    static final int MODE = parseMode(RAW_MODE);
    static final int SAMPLE_EVERY = 256;
    /** modelTransforms 比對容差（相對值，絕對值小於 1 時以 1 計）。 */
    static final float TOLERANCE = 1.0E-5F;
    private static final long BEAT_INTERVAL_MS = 300_000L;

    private static final WeakHashMap<AnimationPlayer, PoseState> STATES = new WeakHashMap<>();
    private static Thread owner;
    private static boolean disabled;
    private static boolean announced;
    private static long nextBeatMs;
    private static float[] scratch = new float[16 * 64];
    private static final float[] cell = new float[16];

    static long calls;
    static long computed;
    static long skipped;
    static long sampled;
    static long mismatches;
    static long foreignThread;
    static long anomalies;
    private static long skipCandidates;

    private VehicleAnimGate() {}

    static int parseMode(String raw) {
        if (raw == null) {
            return MODE_ON;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off", "0" -> MODE_OFF;
            case "verify", "2" -> MODE_VERIFY;
            default -> MODE_ON;
        };
    }

    /** 取代 {@code animPlayer.Update(del)}（BaseVehicle.updateAnimationPlayer offset 44）。 */
    public static void update(AnimationPlayer player, float deltaT) {
        calls++;
        if (MODE == MODE_OFF || disabled) {
            if (!announced) {
                announce();
            }
            computed++;
            player.Update(deltaT);
            return;
        }
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            foreignThread++;
            computed++;
            player.Update(deltaT);
            return;
        }
        if (!announced) {
            announce();
        }
        PoseState state;
        boolean skippable;
        try {
            state = STATES.get(player);
            if (state == null) {
                state = new PoseState();
                STATES.put(player, state);
            }
            skippable = state.capture(player);
        } catch (RuntimeException e) {
            // 簽章讀不到（理論上不會）就照原版算，不讓例外進動畫路徑。
            anomalies++;
            computed++;
            player.Update(deltaT);
            return;
        }
        if (!skippable) {
            computed++;
            player.Update(deltaT);
            state.committed();
        } else if (MODE == MODE_VERIFY || ++skipCandidates % SAMPLE_EVERY == 0) {
            verify(player, deltaT, state);
        } else {
            skipped++;
        }
        if ((calls & 4095) == 0) {
            beat(false);
        }
    }

    /** 本來會跳過時照算一次，比對算出的 modelTransforms 是否與上一次完全相同。 */
    private static void verify(AnimationPlayer player, float deltaT, PoseState state) {
        sampled++;
        Matrix4f[] before = player.getSkinTransforms(null);
        int n = before == null ? 0 : before.length;
        if (scratch.length < n * 16) {
            scratch = new float[n * 16];
        }
        for (int i = 0; i < n; i++) {
            store(before[i], scratch, i * 16);
        }
        computed++;
        player.Update(deltaT);
        state.committed();
        Matrix4f[] after = player.getSkinTransforms(null);
        float worst = 0.0F;
        int worstBone = -1;
        if (after != before || after == null) {
            worst = Float.POSITIVE_INFINITY;
        } else {
            for (int i = 0; i < n; i++) {
                store(after[i], cell, 0);
                float d = 0.0F;
                for (int k = 0; k < 16; k++) {
                    float a = scratch[i * 16 + k];
                    float e = Math.abs(cell[k] - a) / Math.max(1.0F, Math.abs(a));
                    if (!(e <= d)) {
                        d = Float.isNaN(e) ? Float.POSITIVE_INFINITY : e;
                    }
                }
                if (d > worst) {
                    worst = d;
                    worstBone = i;
                }
            }
        }
        if (worst > TOLERANCE) {
            mismatches++;
            boolean disable = MODE == MODE_ON;
            if (disable) {
                disabled = true;
                STATES.clear();
            }
            log("pose mismatch bone=" + worstBone + " diff=" + worst + " tracks="
                    + player.getMultiTrack().getTrackCount()
                    + (disable ? "; vanilla Update for the rest of this session" : "") + " " + counters());
        }
    }

    private static void store(Matrix4f m, float[] out, int o) {
        out[o] = m.m00; out[o + 1] = m.m01; out[o + 2] = m.m02; out[o + 3] = m.m03;
        out[o + 4] = m.m10; out[o + 5] = m.m11; out[o + 6] = m.m12; out[o + 7] = m.m13;
        out[o + 8] = m.m20; out[o + 9] = m.m21; out[o + 10] = m.m22; out[o + 11] = m.m23;
        out[o + 12] = m.m30; out[o + 13] = m.m31; out[o + 14] = m.m32; out[o + 15] = m.m33;
    }

    private static void announce() {
        announced = true;
        nextBeatMs = System.currentTimeMillis() + BEAT_INTERVAL_MS;
        log("active mode=" + (MODE == MODE_VERIFY ? "verify" : MODE == MODE_OFF ? "off" : "on")
                + (RAW_MODE == null ? "" : " (-Dmdc.vehAnimSkip=" + RAW_MODE + ")")
                + " sampleEvery=" + SAMPLE_EVERY + " tolerance=" + TOLERANCE);
    }

    private static void beat(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now < nextBeatMs) {
            return;
        }
        nextBeatMs = now + BEAT_INTERVAL_MS;
        log(counters());
    }

    static String counters() {
        return "calls=" + calls + " computed=" + computed + " skipped=" + skipped + " sampled=" + sampled
                + " mismatches=" + mismatches + " players=" + STATES.size() + " foreignThread=" + foreignThread
                + " anomalies=" + anomalies + " disabled=" + disabled;
    }

    private static void log(String message) {
        try {
            DebugLog.log("[MinidoracatJavaPatch][VehicleAnimGate] " + message);
        } catch (RuntimeException ignored) {
            // 診斷行寫不出來也不能讓例外回到動畫路徑。
        }
    }

    static boolean isDisabled() {
        return disabled;
    }

    /** 行為測試用：清掉跨情境殘留的狀態與計數。 */
    static void resetForTest() {
        STATES.clear();
        owner = null;
        disabled = false;
        announced = false;
        calls = computed = skipped = sampled = mismatches = foreignThread = anomalies = skipCandidates = 0;
    }

    /**
     * 一個 player 的姿勢輸入簽章。{@link #capture} 讀現在的輸入；{@link #committed} 在完整計算後
     * 記下「最後一次完整計算用的輸入」與連續次數。
     */
    static final class PoseState {
        private Object[] refs = new Object[8];
        private int[] vals = new int[16];
        private int refCount;
        private int valCount;
        private Object[] lastRefs = new Object[8];
        private int[] lastVals = new int[16];
        private int lastRefCount = -1;
        private int lastValCount = -1;
        private boolean sameAsLast;
        private int confirmations;

        /** 回傳 true＝可以跳過：沒有 track 在播放、輸入與上一次完整計算相同且已連續算過兩次。 */
        boolean capture(AnimationPlayer player) {
            AnimationMultiTrack multiTrack = player.getMultiTrack();
            int trackCount = multiTrack.getTrackCount();
            boolean force = trackCount == 0
                    || player.parentPlayer != null
                    || !player.updateBones
                    || player.isBoneTransformsNeedFirstFrame()
                    || player.isRecording();
            ensure(2 + trackCount * 2, 4 + trackCount * 6);
            refCount = 0;
            valCount = 0;
            refs[refCount++] = player.getModel();
            refs[refCount++] = player.getSkinningData();
            vals[valCount++] = trackCount;
            vals[valCount++] = Float.floatToRawIntBits(player.getAngle());
            vals[valCount++] = Float.floatToRawIntBits(player.getTargetAngle());
            vals[valCount++] = player.doBlending ? 1 : 0;
            for (int i = 0; i < trackCount; i++) {
                AnimationTrack track = multiTrack.getTrackAt(i);
                if (track.isPlaying) {
                    force = true;
                }
                refs[refCount++] = track;
                refs[refCount++] = track.currentClip;
                vals[valCount++] = Float.floatToRawIntBits(track.getCurrentTimeValue());
                vals[valCount++] = Float.floatToRawIntBits(track.getBlendWeight());
                vals[valCount++] = Float.floatToRawIntBits(track.getBlendFieldWeight());
                vals[valCount++] = track.getLayerIdx();
                vals[valCount++] = track.priority;
                vals[valCount++] = (track.isPlaying ? 1 : 0) | (track.reverse ? 2 : 0)
                        | (track.looping ? 4 : 0) | (track.hasBoneMask() ? 8 : 0);
            }
            sameAsLast = equalsLast();
            return !force && sameAsLast && confirmations >= 2;
        }

        void committed() {
            if (sameAsLast) {
                if (confirmations < Integer.MAX_VALUE) {
                    confirmations++;
                }
                return;
            }
            if (lastRefs.length < refCount) {
                lastRefs = new Object[refs.length];
            }
            if (lastVals.length < valCount) {
                lastVals = new int[vals.length];
            }
            System.arraycopy(refs, 0, lastRefs, 0, refCount);
            System.arraycopy(vals, 0, lastVals, 0, valCount);
            java.util.Arrays.fill(lastRefs, refCount, lastRefs.length, null);
            lastRefCount = refCount;
            lastValCount = valCount;
            confirmations = 1;
            sameAsLast = true;
        }

        private boolean equalsLast() {
            if (refCount != lastRefCount || valCount != lastValCount) {
                return false;
            }
            for (int i = 0; i < refCount; i++) {
                if (refs[i] != lastRefs[i]) {
                    return false;
                }
            }
            for (int i = 0; i < valCount; i++) {
                if (vals[i] != lastVals[i]) {
                    return false;
                }
            }
            return true;
        }

        private void ensure(int refNeed, int valNeed) {
            if (refs.length < refNeed) {
                refs = new Object[Math.max(refNeed, refs.length * 2)];
            }
            if (vals.length < valNeed) {
                vals = new int[Math.max(valNeed, vals.length * 2)];
            }
        }
    }
}
