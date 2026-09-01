package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

/**
 * W22 FaceObjectGuard 行為驗證（獨立 JVM；argv 無參數＝啟用、{@code off}＝kill switch，
 * 測試自驗旗標與 argv 相符——property 打錯會炸在測試裡，不會默默跑 enabled 版假綠）。
 *
 * <p>鎖 helper 契約：非 null 結果逐位元轉發（不換物件、不多呼叫）；null 結果 enabled 回原
 * object＋fallbacks+1、off 回 null（vanilla 語意）；委派拋出的 RuntimeException／Error 原樣
 * 穿透；診斷路徑對 sprite/square 皆 null 的半初始化物件不得炸（anomalies 恆 0）。
 * vanilla 為何回 null 是 IsoObject 的事實（反編譯 5389-5395），不在此重現。
 */
public final class FaceObjectGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean wantEnabled = !"off".equals(mode);
        expect("自驗：argv=" + mode + " 與旗標相符（enabled=" + FaceObjectGuard.enabledForTest() + "）",
                FaceObjectGuard.enabledForTest() == wantEnabled);

        // 正常路徑：vanilla 回非 null → 原樣轉發（同一實例），恰委派一次
        Fake self = fake();
        Fake other = fake();
        self.result = other;
        long calls0 = FaceObjectGuard.callsForTest();
        expect("非 null 結果原樣轉發（同一實例）",
                FaceObjectGuard.closestSpriteGridObject(self, 1.5f, 2.5f) == other && self.calls == 1);
        expect("非 null 結果不計 fallback", FaceObjectGuard.fallbacksForTest() == 0);
        expect("enabled 時 calls+1、off 時凍結",
                FaceObjectGuard.callsForTest() == calls0 + (wantEnabled ? 1 : 0));

        // null 結果（正式服 3386 次的那條路徑）：enabled 回原 object；off 回 null
        Fake stale = fake();
        stale.result = null;
        stale.sprite = sprite("furniture_feeding_trough_01_2");
        stale.square = square(11134, 6875, 0);
        IsoObject got = FaceObjectGuard.closestSpriteGridObject(stale, 11135.2f, 6874.7f);
        if (wantEnabled) {
            expect("null → 回原 object 本身（面向舊位置）、fallbacks+1",
                    got == stale && FaceObjectGuard.fallbacksForTest() == 1);
        } else {
            expect("kill switch：null 直通（vanilla 語意）、fallbacks 凍結",
                    got == null && FaceObjectGuard.fallbacksForTest() == 0);
        }
        expect("null 路徑恰委派一次", stale.calls == 1);

        // 半初始化物件（sprite/square 皆 null）走診斷路徑不得炸
        Fake bare = fake();
        bare.result = null;
        IsoObject gotBare = FaceObjectGuard.closestSpriteGridObject(bare, 0f, 0f);
        expect("sprite/square 皆 null：診斷不炸、回傳語意不變",
                wantEnabled ? gotBare == bare : gotBare == null);

        // 委派拋 RuntimeException（vanilla getSquare()==null 那類 NPE）必須穿透
        Fake boom = fake();
        boom.toThrow = new NullPointerException("<test> getSquare() is null");
        boolean rteEscaped = false;
        try {
            FaceObjectGuard.closestSpriteGridObject(boom, 0f, 0f);
        } catch (NullPointerException e) {
            rteEscaped = true;
        }
        expect("委派的 RuntimeException 穿透", rteEscaped);

        // Error 穿透
        Fake err = fake();
        err.errorToThrow = new StackOverflowError("<test>");
        boolean errEscaped = false;
        try {
            FaceObjectGuard.closestSpriteGridObject(err, 0f, 0f);
        } catch (StackOverflowError e) {
            errEscaped = true;
        }
        expect("委派的 Error 穿透", errEscaped);

        expect("全程零 anomalies", FaceObjectGuard.anomaliesForTest() == 0);

        if (failed > 0) {
            System.out.println("face-object-guard FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("face-object-guard OK  mode=" + mode + "：轉發/fallback/穿透/kill switch 全數通過");
    }

    /** 測試替身：只覆寫改道實際會虛擬派送到的 getClosestSpriteGridObject 與診斷用 getter。 */
    public static class Fake extends IsoObject {
        IsoObject result;
        RuntimeException toThrow;
        Error errorToThrow;
        IsoSprite sprite;
        IsoGridSquare square;
        int calls;

        @Override
        public IsoObject getClosestSpriteGridObject(float toX, float toY) {
            calls++;
            if (errorToThrow != null) {
                throw errorToThrow;
            }
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }

        @Override
        public IsoSprite getSprite() {
            return sprite;
        }

        @Override
        public IsoGridSquare getSquare() {
            return square;
        }
    }

    private static Fake fake() throws Exception {
        return (Fake) rawInstance(Fake.class);
    }

    private static IsoGridSquare square(int x, int y, int z) throws Exception {
        IsoGridSquare sq = (IsoGridSquare) rawInstance(IsoGridSquare.class);
        sq.x = x;
        sq.y = y;
        sq.z = z;
        return sq;
    }

    private static IsoSprite sprite(String name) throws Exception {
        IsoSprite s = (IsoSprite) rawInstance(IsoSprite.class);
        s.name = name;
        return s;
    }

    /** 以 serialization 建構子分配未初始化實例（繞過貼圖／世界依賴；W6 慣例）。 */
    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "fog pass  " : "fog FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private FaceObjectGuardTest() {}
}
