package zombie.mdc;

import java.lang.reflect.Constructor;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugType;
import zombie.network.fields.character.ConnectionPlayer;

/**
 * W20 ClothingSyncGuard 行為驗證（獨立 JVM；模式是 static final，
 * 兩組態由 build.ps1 分開驅動並以 argv 自驗，property 拼錯不得假綠）。
 * 覆蓋：(c) mismatch 訊息解析與 signed diff 分佈、parsePlayer 委派與 ThreadLocal 捕獲、off 純直通。
 * (b) tint 修復已於 42.22.2 隨原版修好退役。
 */
public final class ClothingSyncGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "observe";
        int wantMode = "off".equals(want) ? ClothingSyncGuard.MODE_OFF : ClothingSyncGuard.MODE_OBSERVE;
        expect("property 與測試模式一致（" + want + "）", ClothingSyncGuard.MISMATCH_MODE == wantMode);

        testParseCounts();
        testParsePlayer();
        testMismatch(wantMode);

        if (failed != 0) {
            System.out.println("clothing-sync-guard FAIL x" + failed);
            System.exit(1);
        }
        System.out.println("clothing-sync-guard OK mismatch=" + ClothingSyncGuard.MISMATCH_MODE);
    }

    private static void testParseCounts() {
        long[] c = ClothingSyncGuard.parseCounts(
                "Player has 14 itemVisuals but server tries to sync 15 ones");
        expect("parseCounts：local=14/wire=15", c != null && c[0] == 14 && c[1] == 15);
        long[] c2 = ClothingSyncGuard.parseCounts(
                "Player has 17 itemVisuals but server tries to sync 3 ones");
        expect("parseCounts：local=17/wire=3", c2 != null && c2[0] == 17 && c2[1] == 3);
        expect("parseCounts：格式不符回 null（TIS 改字串的訊號）",
                ClothingSyncGuard.parseCounts("something else") == null);
    }

    /** parsePlayer 委派原版 getPlayer、回同一實例；兩種模式都捕獲（只是 log 用）。 */
    private static void testParsePlayer() throws Exception {
        ConnectionPlayer cp = new ConnectionPlayer();
        cp.setPlayer(null);
        expect("parsePlayer：getPlayer 為 null → 回 null、ThreadLocal=?",
                ClothingSyncGuard.parsePlayer(cp) == null && "?".equals(ClothingSyncGuard.parsePlayerForTest()));
        IsoPlayer player = (IsoPlayer) rawInstance(IsoPlayer.class);
        cp.setPlayer(player);
        expect("parsePlayer：回原版 getPlayer 的同一實例、ThreadLocal＝它的 username",
                ClothingSyncGuard.parsePlayer(cp) == player
                        && java.util.Objects.equals(player.getUsername(), ClothingSyncGuard.parsePlayerForTest()));
        expect("parsePlayer 全程零 anomalies", ClothingSyncGuard.anomaliesForTest() == 0);
    }

    private static void testMismatch(int mode) {
        long m0 = ClothingSyncGuard.mismatchesForTest();
        long plus0 = ClothingSyncGuard.wirePlusForTest();
        long minus0 = ClothingSyncGuard.wireMinusForTest();
        long other0 = ClothingSyncGuard.wireOtherForTest();
        ClothingSyncGuard.onVisualsMismatch(DebugType.General,
                "Player has 14 itemVisuals but server tries to sync 15 ones");
        ClothingSyncGuard.onVisualsMismatch(DebugType.General,
                "Player has 15 itemVisuals but server tries to sync 14 ones");
        ClothingSyncGuard.onVisualsMismatch(DebugType.General, "unparseable format");
        if (mode == ClothingSyncGuard.MODE_OFF) {
            expect("off：mismatch 計數凍結（error 直通）",
                    ClothingSyncGuard.mismatchesForTest() == m0
                    && ClothingSyncGuard.wirePlusForTest() == plus0);
        } else {
            expect("observe：mismatch+3、plus/minus/other 各+1",
                    ClothingSyncGuard.mismatchesForTest() == m0 + 3
                    && ClothingSyncGuard.wirePlusForTest() == plus0 + 1
                    && ClothingSyncGuard.wireMinusForTest() == minus0 + 1
                    && ClothingSyncGuard.wireOtherForTest() == other0 + 1);
        }
        expect("mismatch 全程零 anomalies", ClothingSyncGuard.anomaliesForTest() == 0);
    }

    /** 以 serialization 建構子分配未初始化實例，避開世界依賴（W12 慣例）。 */
    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "csg pass  " : "csg FAIL  ") + what);
        if (!ok) failed++;
    }

    private ClothingSyncGuardTest() {}
}
