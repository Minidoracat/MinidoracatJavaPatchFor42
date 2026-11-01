package zombie.mdc;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * W91：真的 {@code IsoGameCharacter.checkIsNearVehicle}（玩家與車以 Unsafe 配置），對「潛行 × 3.5 格內有車 × 呼叫前 nearWallCrouching」
 * 八種組合比對 helper 與原版呼叫後的玩家狀態（回傳值在 updateInternal2 被 pop，不比）。原版會改狀態的替身玩家（覆寫
 * {@code checkIsNearVehicle}，不潛行也翻轉 nearWallCrouching）模擬遊戲更新加了副作用：observe 第一次、on 抽查時抓到並停用。
 *
 * <p>用法：{@code NearVehicleSneakGateTest vanilla|off|observe|on}。vanilla 的 classpath 不含 dist：只驗原版本身（潛行而且車在 3.5 格內
 * 才設 nearWallCrouching=true，不潛行不寫），也就是 helper 要保持的語意。
 */
public final class NearVehicleSneakGateTest {

    private static int failed;
    static BaseVehicle car;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args[0];
        boolean vanilla = mode.equals("vanilla");
        IsoCell cell = alloc(IsoCell.class);
        Set<BaseVehicle> vehicles = new HashSet<>();
        car = alloc(BaseVehicle.class);
        car.setX(100.0F);
        car.setY(100.0F);
        vehicles.add(car);
        set(IsoCell.class, cell, "vehicles", vehicles);
        IsoWorld world = alloc(IsoWorld.class);
        world.currentCell = cell;
        IsoWorld.instance = world;
        if (!vanilla) {
            int want = mode.equals("off") ? NearVehicleSneakGate.OFF : mode.equals("on") ? NearVehicleSneakGate.ON : NearVehicleSneakGate.OBSERVE;
            check("模式與參數一致（" + NearVehicleSneakGate.modeName() + "）", NearVehicleSneakGate.MODE == want);
        }
        boolean allSame = true;
        for (int combo = 0; combo < 8; combo++) {
            boolean sneaking = (combo & 1) != 0;
            boolean near = (combo & 2) != 0;
            boolean crouch = (combo & 4) != 0;
            IsoPlayer a = player(sneaking, near, crouch);
            boolean r = a.checkIsNearVehicle();
            boolean vanillaAfter = a.isNearWallCrouching();
            if (vanilla) {
                allSame &= vanillaAfter == (crouch || sneaking && near) && r == near;
                continue;
            }
            IsoPlayer b = player(sneaking, near, crouch);
            NearVehicleSneakGate.checkIsNearVehicle(b);
            allSame &= b.isNearWallCrouching() == vanillaAfter;
        }
        if (vanilla) {
            check("原版：潛行而且車在 3.5 格內才把 nearWallCrouching 設成 true，不潛行不寫（八種組合）", allSame);
            finish(mode);
            return;
        }
        check("八種組合（潛行 × 3.5 格內有車 × 呼叫前 nearWallCrouching）呼叫後的玩家狀態與原版相同", allSame);
        IsoPlayer walker = player(false, true, false);
        IsoPlayer sneaker = player(true, true, false);
        long sneakBefore = NearVehicleSneakGate.sneakingForTest();
        for (int i = 0; i < 100; i++) {
            NearVehicleSneakGate.checkIsNearVehicle(sneaker);
        }
        check("潛行時一律照原版呼叫（100 次）", NearVehicleSneakGate.sneakingForTest() - sneakBefore == (mode.equals("off") ? 0 : 100)
                && sneaker.isNearWallCrouching());
        long skippedBefore = NearVehicleSneakGate.skippedForTest();
        for (int i = 0; i < 100; i++) {
            NearVehicleSneakGate.checkIsNearVehicle(walker);
        }
        long skipped = NearVehicleSneakGate.skippedForTest() - skippedBefore;
        check("不潛行：" + (mode.equals("on") ? "on 跳過" : "照原版呼叫") + "（100 次，skipped=" + skipped + "）",
                skipped == (mode.equals("on") ? 100 : 0) && !walker.isNearWallCrouching());
        // 替身：原版加了「不潛行也翻轉 nearWallCrouching」的副作用
        IsoPlayer mutating = alloc(MutatingPlayer.class);
        mutating.setX(100.5F);
        mutating.setY(100.0F);
        int calls = 0;
        while (!NearVehicleSneakGate.disabledForTest() && calls < 10_000) {
            NearVehicleSneakGate.checkIsNearVehicle(mutating);
            calls++;
        }
        if (mode.equals("off")) {
            check("off：替身照原版每次呼叫（nearWallCrouching 翻了 10000 次，回到原值）、不停用",
                    !NearVehicleSneakGate.disabledForTest() && ((MutatingPlayer) mutating).calls == 10_000);
        } else if (mode.equals("observe")) {
            check("observe：替身第一次呼叫就抓到 nearWallCrouching 變了並停用", NearVehicleSneakGate.disabledForTest() && calls == 1);
        } else {
            check("on：替身在每 4096 次跳過抽一次的抽查抓到並停用（第 " + calls + " 次）",
                    NearVehicleSneakGate.disabledForTest() && calls <= 4096 && ((MutatingPlayer) mutating).calls == 1);
        }
        if (!mode.equals("off")) {
            long skippedAfter = NearVehicleSneakGate.skippedForTest();
            NearVehicleSneakGate.checkIsNearVehicle(walker);
            check("停用後不潛行也照原版呼叫", NearVehicleSneakGate.skippedForTest() == skippedAfter);
            NearVehicleSneakGate.forceBeatForTest();
            String line = NearVehicleSneakGate.lastBeatLine;
            check("停用後心跳照印（" + line + "）", line != null && line.contains("mode=" + mode) && line.contains(" mismatches=1")
                    && line.contains(" disabled=true"));
        }
        finish(mode);
    }

    /** 原版加了副作用的替身：不管潛不潛行都翻轉 nearWallCrouching。 */
    static final class MutatingPlayer extends IsoPlayer {
        int calls;

        MutatingPlayer() {
            super(null);
        }

        @Override
        public boolean checkIsNearVehicle() {
            calls++;
            setNearWallCrouching(!isNearWallCrouching());
            return true;
        }
    }

    static IsoPlayer player(boolean sneaking, boolean near, boolean crouch) throws Exception {
        IsoPlayer p = alloc(IsoPlayer.class);
        p.setX(near ? 102.0F : 110.0F);
        p.setY(100.0F);
        Field f = IsoGameCharacter.class.getDeclaredField("sneaking");
        f.setAccessible(true);
        f.setBoolean(p, sneaking);
        p.setNearWallCrouching(crouch);
        return p;
    }

    private static void finish(String mode) {
        System.out.println(failed == 0 ? "NearVehicleSneakGateTest OK (" + mode + ")" : "NearVehicleSneakGateTest FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
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

    private NearVehicleSneakGateTest() {}
}
