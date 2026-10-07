package zombie.mdc;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.lwjgl.util.vector.Matrix4f;

import zombie.core.skinnedmodel.advancedanimation.AnimBoneWeight;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.model.Model;

/**
 * W55 isBoneReparented 快速路徑的行為驗證（裸 JVM，classpath 以手術後的 AnimationPlayer 優先）。
 *
 * <p>逐根骨頭比對 helper 與原版 {@code isBoneReparented}（本體未改）；另跑 60 幀真動畫（含 reparent 的 player），
 * 印出所有 modelTransforms 的摘要 {@code pose-digest=}。build-client.ps1 比對 on 與 off 兩次執行的摘要必須相同，
 * 即手術後的骨頭迴圈與原版逐位相同。argv＝on｜off，必須與 {@code -Dmdc.boneReparentFast} 相符。
 */
public final class BoneReparentFastPathBehaviorTest {

    static int failed;

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "on";
        boolean wantOn = !want.equals("off");
        check("自驗：argv=" + want + " 與 -Dmdc.boneReparentFast 相符", BoneReparentFastPath.ENABLED == wantOn);
        check("欄位契約：reparentedBoneBindings 以 VarHandle 讀得到（off 時不生效）",
                BoneReparentFastPath.active() == wantOn);

        Model model = AnimFixture.model(AnimFixture.skinning(AnimFixture.clips()));
        int bones = AnimFixture.BONES.length;

        AnimationPlayer plain = AnimFixture.player(model);
        check("R1 沒有 reparent：每根骨頭 helper 與原版相同且為 false", sameAsVanilla(plain, bones, false));

        AnimationPlayer reparented = AnimFixture.player(model);
        reparented.addBoneReparent("window_fl_bone", "Dummy01");
        check("R2 有 reparent：每根骨頭 helper 與原版相同，且至少一根為 true", sameAsVanilla(reparented, bones, true));

        reparented.release();
        AnimationPlayer recycled = AnimFixture.player(model);
        check("R3 前提：池回收拿回同一物件", recycled == reparented);
        check("R3 回收後清單清空：每根骨頭 helper 與原版相同且為 false", sameAsVanilla(recycled, bones, false));
        recycled.addBoneReparent("window_fl_bone", "Dummy01");

        // 端到端：真 Update 走手術後的骨頭迴圈；摘要由 build-client.ps1 比對 on／off。
        AnimationPlayer door = AnimFixture.player(model);
        start(door, "door_opening", "door_fl_bone");
        start(door, "window_opening", "window_fl_bone");
        start(recycled, "door_opening", null);
        start(plain, "trunk_opening", "trunk_bone");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[4];
        for (int f = 0; f < 60; f++) {
            for (AnimationPlayer p : List.of(door, recycled, plain)) {
                p.Update(0.016666668F * 0.8F);
                for (Matrix4f m : p.getSkinTransforms(null)) {
                    for (float v : AnimFixture.values(m)) {
                        int bits = Float.floatToRawIntBits(v);
                        buf[0] = (byte) (bits >>> 24);
                        buf[1] = (byte) (bits >>> 16);
                        buf[2] = (byte) (bits >>> 8);
                        buf[3] = (byte) bits;
                        digest.update(buf);
                    }
                }
            }
        }
        float doorTime = door.getMultiTrack().getTrackAt(0).getCurrentTimeValue();
        check("端到端前提：動畫有在前進（門 track 時間 > 0.5）", doorTime > 0.5F);
        System.out.println("pose-digest=" + HexFormat.of().formatHex(digest.digest()));

        // 只報告，不設門檻（機器相依）。
        AnimationPlayer empty = AnimFixture.player(model);
        int n = 2_000_000;
        boolean sink = false;
        for (int w = 0; w < 3; w++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink ^= empty.isBoneReparented(i % bones);
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink ^= BoneReparentFastPath.isBoneReparented(empty, i % bones);
            }
            long t2 = System.nanoTime();
            if (w == 2) {
                System.out.printf("  benchmark 空清單：原版 %.1f ns/次、helper %.1f ns/次（sink=%b）%n",
                        (t1 - t0) / (double) n, (t2 - t1) / (double) n, sink);
            }
        }

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("BoneReparentFastPath 行為驗證全數通過（" + want + "）");
    }

    static boolean sameAsVanilla(AnimationPlayer p, int bones, boolean expectAnyTrue) {
        boolean anyTrue = false;
        for (int i = 0; i < bones; i++) {
            boolean vanilla = p.isBoneReparented(i);
            if (BoneReparentFastPath.isBoneReparented(p, i) != vanilla) {
                return false;
            }
            anyTrue |= vanilla;
        }
        return anyTrue == expectAnyTrue;
    }

    static void start(AnimationPlayer p, String clip, String mask) {
        AnimationTrack track = p.play(clip, false);
        track.setBlendWeight(1.0F);
        track.setSpeedDelta(0.7F);
        track.isPlaying = true;
        if (mask != null) {
            track.setBoneWeights(List.of(new AnimBoneWeight(mask, 1.0F)));
            track.initBoneWeights(p.getSkinningData());
        }
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
        if (!ok) {
            failed++;
        }
    }
}
