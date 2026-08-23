package zombie.mdc;

import java.util.ArrayList;
import java.util.Comparator;

import zombie.characters.animals.IsoAnimal;

/**
 * W11 動物聲音排序活鎖捕手的行為驗證。
 *
 * <p>argv：無參數＝啟用；{@code off}＝kill switch。測試反射自驗旗標與 argv 相符
 * （property 打錯會炸在測試裡，不會默默跑 enabled 版假綠）。
 *
 * <p>TimSort 是否會對 NaN 比較器拋 IAE 是 vanilla 的事實（正式服 1411 次實證），
 * 不在此重現；本測試鎖 helper 自己的契約：IAE 攔下、其他例外穿透、off 直通。
 * 元素用 String＋泛型擦除繞過 IsoAnimal 建構（診斷路徑的 instanceof 對非動物元素安全跳過）。
 */
public final class AnimalSortGuardTest {

    private static int failed;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean wantEnabled = !"off".equals(mode);
        java.lang.reflect.Field f = AnimalSortGuard.class.getDeclaredField("ENABLED");
        f.setAccessible(true);
        boolean enabled = f.getBoolean(null);
        expect("自驗：argv=" + mode + " 與旗標相符（enabled=" + enabled + "）", enabled == wantEnabled);

        // 正常路徑：委派後結果與直接 sort 等價
        ArrayList a = list("c", "a", "b");
        ArrayList b = list("c", "a", "b");
        Comparator<String> natural = Comparator.naturalOrder();
        AnimalSortGuard.sort(a, (Comparator) natural);
        b.sort(natural);
        expect("正常比較器：排序結果與 vanilla 等價", a.equals(b));

        // 契約違反（IAE）：enabled 攔下且清單保持可用；off 穿透
        Comparator<Object> bad = (x, y) -> { throw new IllegalArgumentException("Comparison method violates its general contract!"); };
        ArrayList c = list("b", "a");
        boolean threw = false;
        try {
            AnimalSortGuard.sort(c, (Comparator) bad);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        if (enabled) {
            expect("IAE 被攔下（活鎖鏈斷開；清單續用）", !threw && c.size() == 2);
        } else {
            expect("kill switch：IAE 穿透（vanilla 行為）", threw);
        }

        // 其他 RuntimeException 必須穿透（不得放寬 catch）
        Comparator<Object> rte = (x, y) -> { throw new IllegalStateException("boom"); };
        boolean rteEscaped = false;
        try {
            AnimalSortGuard.sort(list("b", "a"), (Comparator) rte);
        } catch (IllegalStateException e) {
            rteEscaped = true;
        }
        expect("非 IAE 的 RuntimeException 穿透", rteEscaped);

        // Error 必須穿透
        Comparator<Object> err = (x, y) -> { throw new StackOverflowError("boom"); };
        boolean errEscaped = false;
        try {
            AnimalSortGuard.sort(list("b", "a"), (Comparator) err);
        } catch (StackOverflowError e) {
            errEscaped = true;
        }
        expect("Error 穿透", errEscaped);

        if (failed > 0) {
            System.out.println("animal-sort FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("animal-sort OK  mode=" + mode + "：等價/攔截/穿透/kill switch 全數通過");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArrayList<IsoAnimal> list(String... items) {
        ArrayList raw = new ArrayList<>();
        for (String s : items) {
            raw.add(s);
        }
        return raw;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "asg pass  " : "asg FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalSortGuardTest() {}
}
