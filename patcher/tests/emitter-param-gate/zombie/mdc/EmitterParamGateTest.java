package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.audio.FMODParameter;
import zombie.audio.FMODParameterList;
import zombie.network.GameServer;

/**
 * W34 EmitterParamGate 行為驗證（獨立 JVM；argv[0] = observe|enforce|off，須與
 * {@code -Dmdc.emitterParamGate} 相符，測試自驗避免 property 打錯而假綠）。
 *
 * <p>鎖：enforce 只在 {@code GameServer.server} 時跳過參數計算，非 server 照常計算；
 * observe／off 一律計算；委派拋出的例外原樣穿透；observe 有取樣計時。
 */
public final class EmitterParamGateTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String want = args.length == 0 ? "observe" : args[0];
        int expectMode = switch (want) {
            case "enforce" -> EmitterParamGate.MODE_ENFORCE;
            case "off" -> EmitterParamGate.MODE_OFF;
            default -> EmitterParamGate.MODE_OBSERVE;
        };
        expect("mode 與 argv 相符（" + want + "）", EmitterParamGate.MODE == expectMode);
        expect("parseMode：未知值落回 observe", EmitterParamGate.parseMode("x") == EmitterParamGate.MODE_OBSERVE);
        expect("parseMode：enforce/1/off/0", EmitterParamGate.parseMode("enforce") == 1
                && EmitterParamGate.parseMode("1") == 1 && EmitterParamGate.parseMode("OFF") == 0
                && EmitterParamGate.parseMode("0") == 0);

        CountingParam p = (CountingParam) rawInstance(CountingParam.class);
        FMODParameterList list = new FMODParameterList();
        list.add(p);

        GameServer.server = false;
        for (int i = 0; i < 40; i++) {
            EmitterParamGate.update(list);
        }
        expect("非 server：任何模式都照常計算（40 次）", p.calls == 40);

        GameServer.server = true;
        p.calls = 0;
        for (int i = 0; i < 40; i++) {
            EmitterParamGate.update(list);
        }
        if (expectMode == EmitterParamGate.MODE_ENFORCE) {
            expect("server＋enforce：完全不計算", p.calls == 0);
            expect("server＋enforce：skipped 計數 40", EmitterParamGate.skippedForTest() == 40);
        } else {
            expect("server＋" + want + "：照常計算", p.calls == 40);
            expect("server＋" + want + "：skipped 恆 0", EmitterParamGate.skippedForTest() == 0);
        }
        if (expectMode == EmitterParamGate.MODE_OBSERVE) {
            expect("observe：80 次呼叫取樣計時 5 次（每 16 次一次）", EmitterParamGate.sampledForTest() == 5);
        }

        if (expectMode != EmitterParamGate.MODE_ENFORCE) {
            p.toThrow = new IllegalStateException("boom");
            boolean thrown = false;
            try {
                for (int i = 0; i < 16; i++) {
                    EmitterParamGate.update(list);
                }
            } catch (IllegalStateException e) {
                thrown = e.getMessage().equals("boom");
            }
            expect("委派例外原樣穿透", thrown);
        }

        if (failed > 0) {
            System.out.println("EmitterParamGateTest FAIL x" + failed);
            System.exit(1);
        }
        System.out.println("EmitterParamGateTest 全數通過（" + want + "）");
    }

    /** 測試替身：只覆寫 update，計數並可拋出。 */
    public static class CountingParam extends FMODParameter {
        int calls;
        RuntimeException toThrow;

        public CountingParam() {
            super("test");
        }

        @Override
        public void update() {
            calls++;
            if (toThrow != null) {
                throw toThrow;
            }
        }

        @Override
        public float calculateCurrentValue() {
            return 0.0F;
        }

        @Override
        public void setCurrentValue(float value) {}

        @Override
        public void startEventInstance(long inst) {}

        @Override
        public void stopEventInstance(long inst) {}
    }

    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "epg pass  " : "epg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private EmitterParamGateTest() {}
}
