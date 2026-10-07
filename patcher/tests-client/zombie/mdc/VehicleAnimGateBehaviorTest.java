package zombie.mdc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Quaternion;

import zombie.core.skinnedmodel.advancedanimation.AnimBoneWeight;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.animation.Keyframe;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * W54 車輛靜止姿勢跳過的行為驗證（裸 JVM，真 AnimationPlayer／AnimationTrack／SkinningData）。
 *
 * <p>同一組合成車輛（KI5 式：門的 player 由門、窗、裝甲三個零件模型共用，另有後車廂）跑兩份：
 * A＝原版（直接 {@code player.Update}），B＝經 {@link VehicleAnimGate#update}。每個零件模型的呼叫順序與呼叫後的
 * 善後照 javap 的 {@code BaseVehicle.updateAnimationPlayer}（offset 47–240）與 {@code playPartAnim}
 * （L2240–2285）；SmokeCheck 另外鎖住手術後的方法除了這一個呼叫之外與原版逐字相同。每幀結束比對 A、B 的
 * modelTransforms 與蒙皮矩陣（逐位）以及各 track 時間。
 *
 * <p>argv＝on｜verify｜off，必須與 {@code -Dmdc.vehAnimSkip} 實際生效的模式相符。
 */
public final class VehicleAnimGateBehaviorTest {

    static final float DT = 0.016666668F * 0.8F;
    static int failed;
    static int mode;

    enum Kind { DOOR, WINDOW, PLAIN }

    static final class Anim {
        final String clip;
        final boolean animate;
        final boolean reverse;
        final float rate;

        Anim(String clip, boolean animate, boolean reverse, float rate) {
            this.clip = clip;
            this.animate = animate;
            this.reverse = reverse;
            this.rate = rate;
        }
    }

    /** 一個零件模型（BaseVehicle$ModelInfo＋VehiclePart 的必要狀態）；兩份車共用輸入狀態。 */
    static final class Slot {
        final String name;
        final Kind kind;
        final Slot parent;
        final String mask;
        final Map<String, Anim> anims = new HashMap<>();
        boolean open;
        float openDelta;

        Slot(String name, Kind kind, Slot parent, String mask) {
            this.name = name;
            this.kind = kind;
            this.parent = parent;
            this.mask = mask;
        }
    }

    /** 一份車：自己的 player 與 track（ModelInfo.animPlayer／track）。 */
    static final class Side {
        static final int VANILLA = 0;
        static final int GATE = 1;
        static final int SINGLE_UPDATE = 2;

        final String name;
        final int kind;
        final Map<Slot, AnimationPlayer> players = new IdentityHashMap<>();
        final Map<Slot, AnimationTrack> tracks = new IdentityHashMap<>();
        boolean skipDoorPlayerThisFrame;

        Side(String name, int kind) {
            this.name = name;
            this.kind = kind;
        }

        /** ModelInfo.getAnimationPlayer：有 parent 就用 parent 的 player。 */
        AnimationPlayer playerOf(Slot slot) {
            Slot owner = slot;
            while (owner.parent != null) {
                owner = owner.parent;
            }
            return players.get(owner);
        }
    }

    static List<Slot> slots = new ArrayList<>();
    static Slot door;
    static Slot window;
    static Slot armor;
    static Slot trunk;
    static Slot extraParented;
    static Slot extraEmpty;
    static boolean includeExtras;
    static HashMap<String, AnimationClip> clips;
    static Model model;
    static Model model2;

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "on";
        mode = switch (want) {
            case "off" -> VehicleAnimGate.MODE_OFF;
            case "verify" -> VehicleAnimGate.MODE_VERIFY;
            default -> VehicleAnimGate.MODE_ON;
        };
        check("自驗：argv=" + want + " 與 -Dmdc.vehAnimSkip 實際模式相符", VehicleAnimGate.MODE == mode);
        if (failed > 0) {
            System.exit(1);
        }
        VehicleAnimGate.resetForTest();

        clips = AnimFixture.clips();
        model = AnimFixture.model(AnimFixture.skinning(clips));
        model2 = AnimFixture.model(AnimFixture.skinning(clips));

        door = new Slot("door", Kind.DOOR, null, "door_fl_bone");
        door.anims.put("Open", new Anim("door_opening", true, false, 1.0F));
        door.anims.put("Close", new Anim("door_opening", true, true, 1.5F));
        door.anims.put("Opened", new Anim("door_opening", false, true, 1.0F));
        door.anims.put("Closed", new Anim("door_opening", false, false, 1.0F));
        window = new Slot("window", Kind.WINDOW, door, "window_fl_bone");
        window.anims.put("ClosedToOpen", new Anim("window_opening", false, false, 1.0F));
        armor = new Slot("armor", Kind.PLAIN, door, null);
        trunk = new Slot("trunk", Kind.DOOR, null, "trunk_bone");
        trunk.anims.put("Open", new Anim("trunk_opening", true, false, 0.4F));
        trunk.anims.put("Opened", new Anim("trunk_opening", false, true, 1.0F));
        trunk.anims.put("Closed", new Anim("trunk_opening", false, false, 1.0F));
        extraParented = new Slot("extraParented", Kind.DOOR, null, "hood_bone");
        extraParented.anims.put("Closed", new Anim("trunk_opening", false, false, 1.0F));
        extraEmpty = new Slot("extraEmpty", Kind.PLAIN, null, null);
        slots.addAll(List.of(door, window, armor, trunk, extraParented, extraEmpty));

        Side a = side("A", Side.VANILLA);
        Side b = side("B", Side.GATE);
        Side c = side("C", Side.SINGLE_UPDATE);
        Side d = side("D", Side.VANILLA);
        Side[] all = { a, b, c, d };

        // ---- S1 靜止 300 幀 ----
        Counters s1 = Counters.now();
        boolean eq = true;
        for (int f = 0; f < 300; f++) {
            frame(all);
            eq &= same(a, b);
        }
        Counters s1d = Counters.now().minus(s1);
        check("S1 靜止 300 幀（門的 player 共用 k=3）：每幀 A==B", eq);
        expectCandidates("S1", s1d, 4 * 290);

        // ---- S2 搖窗（第 10–40 幀 openDelta 0→1，之後停住）；N3 負對照 ----
        eq = true;
        boolean n3Detected = false;
        boolean n3Recovered = false;
        Counters hold = null;
        for (int f = 0; f < 100; f++) {
            if (f >= 10 && f <= 40) {
                window.openDelta = (f - 10) / 30.0F;
            }
            if (f == 45) {
                hold = Counters.now();
            }
            d.skipDoorPlayerThisFrame = f == 11;   // 第 10 幀 openDelta 仍是 0，第 11 幀才第一次改變
            frame(all);
            eq &= same(a, b);
            if (f == 11) {
                n3Detected = !AnimFixture.samePose(a.players.get(door), d.players.get(door));
            }
            if (f == 12) {
                n3Recovered = AnimFixture.samePose(a.players.get(door), d.players.get(door));
            }
        }
        d.skipDoorPlayerThisFrame = false;
        Counters holdD = Counters.now().minus(hold);
        check("S2 搖窗：每幀 A==B（窗時間改變的那幀起重算）", eq);
        expectCandidates("S2 停住後", holdD, 4 * 50);
        check("N3 比對有鑑別力：窗時間改變那幀不算骨頭（D）→ 與原版姿勢不同", n3Detected);
        check("N3 前提：D 下一幀照算後回到與原版相同", n3Recovered);

        // ---- S3 開門動畫（k=3 倍速）；N2 簡單去重負對照 ----
        door.open = true;
        for (Side s : all) {
            playPartAnim(s, door, "Open");
        }
        float before = a.tracks.get(door).getCurrentTimeValue();
        Counters s3 = Counters.now();
        frame(all);
        float after = a.tracks.get(door).getCurrentTimeValue();
        check("S3 前提：原版共用 player 每幀 Update 3 次，門時間前進 3×dt×rate",
                Math.abs((after - before) - 3 * DT) < 1.0E-5F);
        Counters s3first = Counters.now().minus(s3);
        check("S3 動畫中門的 player 每次呼叫都完整計算（3 次）", mode == VehicleAnimGate.MODE_OFF
                ? s3first.computed == s3first.calls
                : s3first.computed >= 3);
        eq = same(a, b);
        boolean n2Detected = false;
        int frames = 1;
        while (a.tracks.get(door) != null && a.tracks.get(door).isPlaying && frames < 200) {
            frame(all);
            frames++;
            eq &= same(a, b);
            n2Detected |= c.tracks.get(door) != null && a.tracks.get(door) != null
                    && c.tracks.get(door).getCurrentTimeValue() != a.tracks.get(door).getCurrentTimeValue();
        }
        check("S3 開門動畫期間每幀 A==B（共 " + frames + " 幀，仍是 3 倍速）", eq && frames < 200);
        check("N2 簡單去重（同一幀只 Update 一次）會讓門變慢，測試抓得到", n2Detected);

        // ---- S4 動畫播完 → Opened 靜止 ----
        eq = true;
        Counters s4 = null;
        for (int f = 0; f < 100; f++) {
            if (f == 5) {
                s4 = Counters.now();
            }
            frame(all);
            eq &= same(a, b);
        }
        check("S4 播完換成 Opened 靜止 track：每幀 A==B", eq
                && a.tracks.get(door) != null && !a.tracks.get(door).isPlaying && a.tracks.get(door).reverse);
        expectCandidates("S4 Opened 靜止", Counters.now().minus(s4), 4 * 90);

        // ---- S5 池回收：B 的後車廂 player 還回池再配置（LIFO 拿回同一物件） ----
        AnimationPlayer oldB = b.players.get(trunk);
        oldB.release();
        AnimationPlayer newB = AnimFixture.player(model);
        b.players.put(trunk, newB);
        b.tracks.put(trunk, null);
        AnimationPlayer oldA = a.players.get(trunk);
        oldA.release();
        a.players.put(trunk, AnimFixture.player(model));
        a.tracks.put(trunk, null);
        check("S5 前提：池回收拿回同一個 player 物件（簽章表裡有舊狀態）", newB == oldB);
        Counters s5 = Counters.now();
        eq = true;
        for (int f = 0; f < 20; f++) {
            frame(all);
            eq &= same(a, b);
        }
        check("S5 池回收後第一幀照算（needFirstFrame、track 換新）且每幀 A==B", eq
                && Counters.now().minus(s5).computed >= 2);

        // ---- S6 換 model（skinningData 換新、multiTrack 被原版清空） ----
        Counters s6 = Counters.now();
        a.players.get(door).setModel(model2);
        b.players.get(door).setModel(model2);
        d.players.get(door).setModel(model2);
        c.players.get(door).setModel(model2);
        eq = true;
        for (int f = 0; f < 20; f++) {
            frame(all);
            eq &= same(a, b);
        }
        check("S6 換 skinningData：重新計算且每幀 A==B", eq && Counters.now().minus(s6).computed >= 3
                && a.players.get(door).getSkinningData() == model2.tag);

        // ---- S7 parentPlayer 與沒有 track 的 player 一律照原版 ----
        includeExtras = true;
        for (Side s : all) {
            AnimationPlayer parented = AnimFixture.player(model);
            parented.parentPlayer = s.players.get(trunk);
            s.players.put(extraParented, parented);
            s.players.put(extraEmpty, AnimFixture.player(model));
        }
        long extraCalls = 0;
        long extraComputed = 0;
        eq = true;
        for (int f = 0; f < 10; f++) {
            for (Slot slot : List.of(extraParented, extraEmpty)) {
                long c0 = VehicleAnimGate.computed;
                for (Side s : all) {
                    updateOne(s, slot);
                }
                extraCalls++;
                extraComputed += VehicleAnimGate.computed - c0;
            }
            eq &= AnimFixture.samePose(a.players.get(extraParented), b.players.get(extraParented))
                    && AnimFixture.samePose(a.players.get(extraEmpty), b.players.get(extraEmpty));
        }
        check("S7 parentPlayer≠null、沒有 track：每次呼叫都完整計算且 A==B",
                eq && extraComputed == extraCalls && a.players.get(extraParented).getMultiTrack().getTrackCount() == 1);

        // ---- S8 updateBones=false → 照原版（NonVisualOnly 路徑） ----
        for (Side s : all) {
            s.players.get(door).updateBones = false;
        }
        Counters s8 = Counters.now();
        eq = true;
        for (int f = 0; f < 5; f++) {
            frame(all);
            eq &= same(a, b);
        }
        Counters s8d = Counters.now().minus(s8);
        for (Side s : all) {
            s.players.get(door).updateBones = true;
        }
        for (int f = 0; f < 5; f++) {
            frame(all);
            eq &= same(a, b);
        }
        check("S8 updateBones=false：門的 player 每次呼叫都交給原版且 A==B", eq && s8d.computed >= 15);

        // ---- S9 簽章看不到的輸入改變（就地改 clip 的關鍵影格）→ 抽樣比對 ----
        AnimationClip doorClip = clips.get("door_opening");
        for (Keyframe k : doorClip.getKeyframes()) {
            if (k.bone == AnimFixture.DOOR) {
                k.rotation = new Quaternion(0.0F, 0.0F, 0.3F, 0.9539392F);
            }
        }
        long mismatches0 = VehicleAnimGate.mismatches;
        if (mode == VehicleAnimGate.MODE_ON) {
            int f = 0;
            while (!VehicleAnimGate.isDisabled() && f < 2000) {
                frame(all);
                f++;
            }
            check("S9 on：抽樣比對抓到不一致 → 本次永久回原版（" + f + " 幀內）",
                    VehicleAnimGate.isDisabled() && VehicleAnimGate.mismatches == mismatches0 + 1);
            eq = true;
            Counters after9 = Counters.now();
            for (int g = 0; g < 20; g++) {
                frame(all);
                eq &= same(a, b);
            }
            Counters d9 = Counters.now().minus(after9);
            check("S9 on：回原版後每幀 A==B，之後不再跳過", eq && d9.skipped == 0 && d9.computed == d9.calls);
        } else {
            eq = true;
            for (int g = 0; g < 100; g++) {
                frame(all);
                eq &= same(a, b);
            }
            check("S9 " + want + "：每幀 A==B，不會停用", eq && !VehicleAnimGate.isDisabled());
            check(mode == VehicleAnimGate.MODE_VERIFY ? "N4 verify：不一致只計數（≥1）" : "S9 off：不比對",
                    mode == VehicleAnimGate.MODE_VERIFY
                            ? VehicleAnimGate.mismatches > mismatches0
                            : VehicleAnimGate.mismatches == 0);
        }

        // ---- 模式總帳（N1 off＝原版、N4 verify＝永遠照算） ----
        System.out.println("  " + VehicleAnimGate.counters());
        if (mode == VehicleAnimGate.MODE_OFF) {
            check("N1 off：沒有任何跳過或抽樣，每次呼叫都落到原版 Update",
                    VehicleAnimGate.skipped == 0 && VehicleAnimGate.sampled == 0
                    && VehicleAnimGate.computed == VehicleAnimGate.calls && VehicleAnimGate.calls > 0);
        } else if (mode == VehicleAnimGate.MODE_VERIFY) {
            check("N4 verify：沒有跳過，本來會跳過的呼叫全部照算並比對",
                    VehicleAnimGate.skipped == 0 && VehicleAnimGate.sampled > 1000
                    && VehicleAnimGate.computed == VehicleAnimGate.calls);
        } else {
            check("on：抽樣比例約 1/" + VehicleAnimGate.SAMPLE_EVERY,
                    VehicleAnimGate.sampled > 0
                    && VehicleAnimGate.sampled <= (VehicleAnimGate.skipped + VehicleAnimGate.sampled)
                            / VehicleAnimGate.SAMPLE_EVERY + 1);
        }
        check("沒有異常與跨執行緒呼叫", VehicleAnimGate.anomalies == 0 && VehicleAnimGate.foreignThread == 0);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("VehicleAnimGate 行為驗證全數通過（" + want + "）");
    }

    static Side side(String name, int kind) {
        Side s = new Side(name, kind);
        s.players.put(door, AnimFixture.player(model));
        s.players.put(trunk, AnimFixture.player(model));
        return s;
    }

    static void frame(Side[] sides) {
        for (Side s : sides) {
            IdentityHashMap<AnimationPlayer, Boolean> seen = new IdentityHashMap<>();
            for (Slot slot : slots) {
                if (!includeExtras && (slot == extraParented || slot == extraEmpty)) {
                    continue;
                }
                AnimationPlayer p = s.playerOf(slot);
                if (s.kind == Side.SINGLE_UPDATE && p != null && seen.put(p, Boolean.TRUE) != null) {
                    tailAfterUpdate(s, slot, p);
                    continue;
                }
                if (s.skipDoorPlayerThisFrame && p == s.players.get(door)) {
                    tailAfterUpdate(s, slot, p);
                    continue;
                }
                updateOne(s, slot);
            }
        }
    }

    /** BaseVehicle.updateAnimationPlayer：isReady 守門 → Update（A 原版／B 改道）→ 善後。 */
    static void updateOne(Side s, Slot slot) {
        AnimationPlayer p = s.playerOf(slot);
        if (p == null || !p.isReady()) {
            return;
        }
        if (s.kind == Side.GATE) {
            VehicleAnimGate.update(p, DT);
        } else {
            p.Update(DT);
        }
        tailAfterUpdate(s, slot, p);
    }

    /** javap offset 47–240。 */
    static void tailAfterUpdate(Side s, Slot slot, AnimationPlayer p) {
        if (p == null || !p.isReady()) {
            return;
        }
        AnimationMultiTrack multiTrack = p.getMultiTrack();
        for (int i = 0; i < multiTrack.getTrackCount(); i++) {
            AnimationTrack track = multiTrack.getTracks().get(i);
            if (track.isPlaying && track.isFinished()) {
                multiTrack.removeTrackAt(i);
                i--;
            }
        }
        AnimationTrack own = s.tracks.get(slot);
        if (own != null && multiTrack.getIndexOfTrack(own) == -1) {
            s.tracks.put(slot, null);
            own = null;
        }
        if (own != null) {
            if (slot.kind == Kind.WINDOW) {
                own.setCurrentTimeValue(own.getDuration() * slot.openDelta);
            }
            return;
        }
        if (slot.kind == Kind.DOOR) {
            playPartAnim(s, slot, slot.open ? "Opened" : "Closed");
        }
        if (slot.kind == Kind.WINDOW) {
            playPartAnim(s, slot, "ClosedToOpen");
        }
    }

    /** BaseVehicle.playPartAnim（L2240–2285）。 */
    static void playPartAnim(Side s, Slot slot, String animId) {
        Anim anim = slot.anims.get(animId);
        if (anim == null) {
            return;
        }
        AnimationPlayer p = s.playerOf(slot);
        if (p == null || !p.isReady()) {
            return;
        }
        AnimationTrack old = s.tracks.get(slot);
        if (old != null && p.getMultiTrack().getIndexOfTrack(old) != -1) {
            p.getMultiTrack().removeTrack(old);
        }
        s.tracks.put(slot, null);
        SkinningData skinning = p.getSkinningData();
        if (skinning != null && !skinning.animationClips.containsKey(anim.clip)) {
            return;
        }
        AnimationTrack track = p.play(anim.clip, false);
        s.tracks.put(slot, track);
        if (track == null) {
            return;
        }
        track.setBlendWeight(1.0F);
        track.setSpeedDelta(anim.rate);
        track.isPlaying = anim.animate;
        track.reverse = anim.reverse;
        if (slot.mask != null) {
            track.setBoneWeights(List.of(new AnimBoneWeight(slot.mask, 1.0F)));
            track.initBoneWeights(skinning);
        }
        if (slot.kind == Kind.WINDOW) {
            track.setCurrentTimeValue(track.getDuration() * slot.openDelta);
        }
    }

    /** A、B 每個 player 的姿勢與每條 track 的時間逐位相同。 */
    static boolean same(Side a, Side b) {
        for (Slot slot : List.of(door, trunk)) {
            AnimationPlayer pa = a.players.get(slot);
            AnimationPlayer pb = b.players.get(slot);
            if (!AnimFixture.samePose(pa, pb)) {
                return false;
            }
            int n = pa.getMultiTrack().getTrackCount();
            if (n != pb.getMultiTrack().getTrackCount()) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (Float.floatToRawIntBits(pa.getMultiTrack().getTrackAt(i).getCurrentTimeValue())
                        != Float.floatToRawIntBits(pb.getMultiTrack().getTrackAt(i).getCurrentTimeValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    static void expectCandidates(String label, Counters delta, long atLeast) {
        long candidates = delta.skipped + delta.sampled;
        if (mode == VehicleAnimGate.MODE_OFF) {
            check(label + " off：沒有跳過（候選 0）", candidates == 0 && delta.computed == delta.calls);
        } else if (mode == VehicleAnimGate.MODE_VERIFY) {
            check(label + " verify：本來會跳過的 " + candidates + " 次全部照算比對（≥" + atLeast + "）",
                    delta.skipped == 0 && candidates >= atLeast);
        } else {
            check(label + " on：跳過 " + delta.skipped + "、抽樣 " + delta.sampled + "（候選 ≥" + atLeast + "）",
                    candidates >= atLeast && delta.skipped > 0);
        }
    }

    record Counters(long calls, long computed, long skipped, long sampled) {
        static Counters now() {
            return new Counters(VehicleAnimGate.calls, VehicleAnimGate.computed,
                    VehicleAnimGate.skipped, VehicleAnimGate.sampled);
        }

        Counters minus(Counters o) {
            return new Counters(calls - o.calls, computed - o.computed, skipped - o.skipped, sampled - o.sampled);
        }
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
        if (!ok) {
            failed++;
        }
    }
}
