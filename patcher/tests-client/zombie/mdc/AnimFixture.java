package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.util.vector.Vector3f;

import zombie.asset.Asset;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.Keyframe;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * 車輛動畫行為測試共用的合成資料：真 SkinningData、AnimationClip、AnimationPlayer，Model 以 Unsafe 配置後
 * 只補 Asset 狀態與 tag（AnimationPlayer 只讀這兩項）。骨架仿 KI5 車門：門骨底下掛窗骨與裝甲骨。
 */
final class AnimFixture {

    static final String[] BONES = {
            "Dummy01", "door_fl_bone", "window_fl_bone", "armor_fl_bone", "trunk_bone", "hood_bone",
            "wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr", "seat_fl", "seat_fr" };
    static final int[] PARENTS = { -1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0 };
    static final int DOOR = 1;
    static final int WINDOW = 2;
    static final int TRUNK = 4;

    private AnimFixture() {}

    /** 每根骨頭都有 t=0 與 t=duration 兩個關鍵影格；只有 animatedBone 會動（rotation 或 translation）。 */
    static AnimationClip clip(String name, float duration, int animatedBone, float angle, float drop) {
        List<Keyframe> frames = new ArrayList<>();
        for (int bone = 0; bone < BONES.length; bone++) {
            for (float t : new float[]{ 0.0F, duration * 0.5F, duration }) {
                float f = t / duration;
                Keyframe k = new Keyframe();
                k.bone = bone;
                k.boneName = BONES[bone];
                k.time = t;
                boolean moving = bone == animatedBone;
                float half = moving ? angle * f * 0.5F : 0.0F;
                k.rotation = new Quaternion(0.0F, 0.0F, (float) Math.sin(half), (float) Math.cos(half));
                k.position = new Vector3f(bone * 0.1F, moving ? drop * f : 0.0F, 0.05F * bone);
                frames.add(k);
            }
        }
        return new AnimationClip(duration, frames, name, true);
    }

    static HashMap<String, AnimationClip> clips() {
        HashMap<String, AnimationClip> clips = new HashMap<>();
        clips.put("door_opening", clip("door_opening", 1.0F, DOOR, 1.2F, 0.0F));
        clips.put("window_opening", clip("window_opening", 1.0F, WINDOW, 0.0F, -0.3F));
        clips.put("trunk_opening", clip("trunk_opening", 0.8F, TRUNK, -1.4F, 0.0F));
        return clips;
    }

    static SkinningData skinning(HashMap<String, AnimationClip> clips) {
        List<Matrix4f> bind = new ArrayList<>();
        List<Matrix4f> inverse = new ArrayList<>();
        List<Matrix4f> offset = new ArrayList<>();
        List<Integer> hierarchy = new ArrayList<>();
        HashMap<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < BONES.length; i++) {
            bind.add(new Matrix4f());
            inverse.add(new Matrix4f());
            Matrix4f off = new Matrix4f();
            off.m30 = -0.1F * i;
            offset.add(off);
            hierarchy.add(PARENTS[i]);
            indices.put(BONES[i], i);
        }
        return new SkinningData(clips, bind, inverse, offset, hierarchy, indices);
    }

    static Model model(SkinningData skinning) throws Exception {
        Model model = alloc(Model.class);
        model.tag = skinning;
        Constructor<?> priv = Class.forName("zombie.asset.Asset$PRIVATE").getDeclaredConstructor(Asset.class);
        priv.setAccessible(true);
        set(Asset.class, model, "priv", priv.newInstance(model));
        set(Asset.class, model, "isDefered", true);
        return model;
    }

    static AnimationPlayer player(Model model) {
        AnimationPlayer player = AnimationPlayer.alloc(model);
        if (!player.isReady()) {
            throw new IllegalStateException("fixture player not ready");
        }
        return player;
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    /** 兩個 player 的 modelTransforms 與蒙皮矩陣是否逐位相同。 */
    static boolean samePose(AnimationPlayer a, AnimationPlayer b) {
        return sameMatrices(a.getSkinTransforms(null), b.getSkinTransforms(null))
                && sameMatrices(a.getSkinTransforms(a.getSkinningData()), b.getSkinTransforms(b.getSkinningData()));
    }

    static boolean sameMatrices(Matrix4f[] a, Matrix4f[] b) {
        if (a == null || b == null || a.length != b.length) {
            return a == b;
        }
        for (int i = 0; i < a.length; i++) {
            if (!bitsEqual(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    static boolean bitsEqual(Matrix4f a, Matrix4f b) {
        float[] x = values(a);
        float[] y = values(b);
        for (int k = 0; k < 16; k++) {
            if (Float.floatToRawIntBits(x[k]) != Float.floatToRawIntBits(y[k])) {
                return false;
            }
        }
        return true;
    }

    static float[] values(Matrix4f m) {
        return new float[]{
                m.m00, m.m01, m.m02, m.m03, m.m10, m.m11, m.m12, m.m13,
                m.m20, m.m21, m.m22, m.m23, m.m30, m.m31, m.m32, m.m33 };
    }
}
