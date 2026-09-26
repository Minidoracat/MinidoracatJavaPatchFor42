package zombie.mdc;

import java.util.Set;
import java.util.function.Predicate;

/**
 * W43 丟棄條件與 IsoGridSquare.load 逐項等價（含原版怪處）＋旋鈕解析。
 * 條件不等價＝伺服器清得比 client 少（幽靈照舊）或多（刪掉玩家看得到的東西）。
 */
public final class WorldItemExpirySyncTest {

    private static int failed;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    public static void main(String[] args) {
        Predicate<String> list = Set.of("Base.Dung_Chicken", "Base.Egg", "Base")::contains;
        Predicate<String> dungOnly = Set.of("Base.Dung_Chicken")::contains;
        double h = 24.0;

        check("清單內、已過期 → 丟", WorldItemExpirySync.expired("Base.Dung_Chicken", false, 10.0, false, h, false, dungOnly, 34.1));
        check("清單內、剛好未過期（now == drop+h）→ 留", !WorldItemExpirySync.expired("Base.Dung_Chicken", false, 10.0, false, h, false, dungOnly, 34.0));
        check("清單外 → 留", !WorldItemExpirySync.expired("Base.Axe", false, 0.0, false, h, false, dungOnly, 1000.0));
        check("ignoreRemoveSandbox → 留", !WorldItemExpirySync.expired("Base.Dung_Chicken", false, 0.0, true, h, false, dungOnly, 1000.0));
        check("dropTime=-1（從未丟到地上）→ 留", !WorldItemExpirySync.expired("Base.Dung_Chicken", false, -1.0, false, h, false, dungOnly, 1000.0));
        check("hours=0（功能關閉）→ 留", !WorldItemExpirySync.expired("Base.Dung_Chicken", false, 0.0, false, 0.0, false, dungOnly, 1000.0));
        check("過時物品 → 不論條件一律丟", WorldItemExpirySync.expired("Base.Axe", true, -1.0, true, 0.0, false, dungOnly, 0.0));
        check("黑名單模式：清單外者過期 → 丟", WorldItemExpirySync.expired("Base.Axe", false, 0.0, false, h, true, dungOnly, 100.0));
        check("黑名單模式：清單內且無底線者 → 留", !WorldItemExpirySync.expired("Base.Egg", false, 0.0, false, h, true, list, 100.0));
        check("原版怪處：黑名單模式下帶底線的清單內物品，前綴不在清單 → 仍丟",
                WorldItemExpirySync.expired("Base.Dung_Chicken", false, 0.0, false, h, true, dungOnly, 100.0));
        check("原版怪處：前綴命中時 dropTime=-1 仍比較時間（-1+0 < now）→ 丟",
                WorldItemExpirySync.expired("Base_Thing", false, -1.0, false, 0.0, false, list, 0.0));

        check("旋鈕：預設啟用", WorldItemExpirySync.parseEnabled(null));
        check("旋鈕：0/off 關閉", !WorldItemExpirySync.parseEnabled("0") && !WorldItemExpirySync.parseEnabled(" OFF "));
        check("旋鈕：未知值維持啟用", WorldItemExpirySync.parseEnabled("2"));
        check("margin：預設 1、clamp 0..24、壞值回 1",
                WorldItemExpirySync.parseMargin(null) == 1.0
                        && WorldItemExpirySync.parseMargin("-3") == 0.0
                        && WorldItemExpirySync.parseMargin("99") == 24.0
                        && WorldItemExpirySync.parseMargin("abc") == 1.0
                        && WorldItemExpirySync.parseMargin("NaN") == 1.0);

        if (failed > 0) {
            System.out.println("WorldItemExpirySyncTest FAILED: " + failed);
            System.exit(1);
        }
        System.out.println("WorldItemExpirySyncTest passed");
    }
}
