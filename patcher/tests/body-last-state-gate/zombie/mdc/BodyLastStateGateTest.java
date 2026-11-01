package zombie.mdc;

import java.lang.reflect.Field;
import java.util.ArrayList;

import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartLast;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.network.GameServer;

/**
 * W94：真的 {@code BodyDamage.setBodyPartsLastState}（BodyDamage／BodyPart 以 Unsafe 配置，3 個部位：包紮、咬傷＋割傷、抓傷）。
 * 經 helper 呼叫後比對 {@code bodyPartsLastState}：observe／off 與原版一樣抄進去；on 在伺服器上不抄、在客戶端（{@code GameServer.server}
 * false）照原版抄；玩家與動物分開計數，observe 每 64 次量一次。
 *
 * <p>用法：{@code BodyLastStateGateTest vanilla|off|observe|on}。vanilla 的 classpath 不含 dist：只驗原版會抄（helper 要跳過的就是這件事）。
 */
public final class BodyLastStateGateTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        String mode = args[0];
        GameServer.server = true;
        BodyDamage player = body(alloc(IsoPlayer.class));
        BodyDamage animal = body(alloc(IsoAnimal.class));
        if (mode.equals("vanilla")) {
            player.setBodyPartsLastState();
            check("原版：setBodyPartsLastState 把三個部位的包紮／咬傷／割傷／抓傷抄進 lastState", copied(player));
            finish(mode);
            return;
        }
        int want = mode.equals("off") ? BodyLastStateGate.OFF : mode.equals("on") ? BodyLastStateGate.ON : BodyLastStateGate.OBSERVE;
        check("模式與參數一致（" + BodyLastStateGate.modeName() + "）", BodyLastStateGate.MODE == want);
        boolean on = mode.equals("on");

        BodyLastStateGate.setBodyPartsLastState(player);
        check(mode + "：伺服器上玩家的 lastState " + (on ? "沒抄（跳過）" : "照原版抄"), copied(player) != on && untouched(player) == on);
        for (int i = 0; i < 127; i++) {
            BodyLastStateGate.setBodyPartsLastState(animal);
        }
        check(mode + "：伺服器上動物的 lastState " + (on ? "沒抄（跳過）" : "照原版抄"), copied(animal) != on && untouched(animal) == on);
        boolean off = mode.equals("off");
        check(mode + "：計數 players=" + BodyLastStateGate.playersForTest() + " animals=" + BodyLastStateGate.animalsForTest()
                        + " skipped=" + BodyLastStateGate.skippedForTest() + " samples=" + BodyLastStateGate.samplesForTest(),
                BodyLastStateGate.playersForTest() == (off ? 0 : 1) && BodyLastStateGate.animalsForTest() == (off ? 0 : 127)
                        && BodyLastStateGate.skippedForTest() == (on ? 128 : 0)
                        && BodyLastStateGate.samplesForTest() == (mode.equals("observe") ? 2 : 0));

        GameServer.server = false;
        BodyDamage client = body(alloc(IsoPlayer.class));
        BodyLastStateGate.setBodyPartsLastState(client);
        check(mode + "：客戶端（GameServer.server=false）一律照原版抄", copied(client));
        GameServer.server = true;

        if (!off) {
            BodyLastStateGate.forceBeatForTest();
            String line = BodyLastStateGate.lastBeatLine;
            check("心跳（" + line + "）", line != null && line.contains("mode=" + mode) && line.contains(" players=1")
                    && line.contains(" animals=127") && line.contains(" skipped=" + (on ? 128 : 0)));
        }
        finish(mode);
    }

    /** 3 個部位：0 包紮、1 咬傷＋割傷、2 抓傷；lastState 全 false。 */
    static BodyDamage body(IsoGameCharacter owner) throws Exception {
        BodyDamage bd = alloc(BodyDamage.class);
        ArrayList<BodyPart> parts = new ArrayList<>();
        ArrayList<BodyPartLast> last = new ArrayList<>();
        String[][] flags = {{"bandaged"}, {"bitten", "cut"}, {"scratched"}};
        for (String[] f : flags) {
            BodyPart p = alloc(BodyPart.class);
            for (String name : f) {
                set(BodyPart.class, p, name, true);
            }
            set(BodyPart.class, p, "bandageLife", 1.0F);
            parts.add(p);
            last.add(new BodyPartLast());
        }
        set(BodyDamage.class, bd, "bodyParts", parts);
        set(BodyDamage.class, bd, "bodyPartsLastState", last);
        set(BodyDamage.class, bd, "parentChar", owner);
        return bd;
    }

    static boolean copied(BodyDamage bd) throws Exception {
        ArrayList<BodyPartLast> last = lastState(bd);
        return last.get(0).bandaged() && !last.get(0).bitten() && last.get(1).bitten() && last.get(1).isCut()
                && !last.get(1).scratched() && last.get(2).scratched() && !last.get(2).bandaged();
    }

    static boolean untouched(BodyDamage bd) throws Exception {
        for (BodyPartLast l : lastState(bd)) {
            if (l.bandaged() || l.bitten() || l.scratched() || l.isCut()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static ArrayList<BodyPartLast> lastState(BodyDamage bd) throws Exception {
        Field f = BodyDamage.class.getDeclaredField("bodyPartsLastState");
        f.setAccessible(true);
        return (ArrayList<BodyPartLast>) f.get(bd);
    }

    private static void finish(String mode) {
        System.out.println(failed == 0 ? "BodyLastStateGateTest OK (" + mode + ")" : "BodyLastStateGateTest FAILED " + failed);
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

    private BodyLastStateGateTest() {}
}
