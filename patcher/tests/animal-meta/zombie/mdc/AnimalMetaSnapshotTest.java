package zombie.mdc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * W38 AnimalMetaSnapshot 行為驗證（{@code on}／{@code off}）。以原版 doMeta 的索引迴圈形狀＋
 * setDZone 的「remove 再 add（移到尾端）」重現缺陷：off 時有動物被重複補算、有動物被漏掉；
 * on 時每隻恰一次，且 end 之後回到即時清單。
 */
public final class AnimalMetaSnapshotTest {

    private static int failed;

    public static void main(String[] args) {
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", AnimalMetaSnapshot.enabledForTest() == !off);

        ArrayList<String> live = new ArrayList<>(List.of("A", "B", "C", "D", "E"));
        Map<String, Integer> seen = new HashMap<>();
        AnimalMetaSnapshot.begin(null);
        // 原版：for (i = 0; i < this.animals.size(); i++) this.animals.get(i).updateStatsAway(h)
        for (int i = 0; i < AnimalMetaSnapshot.animals(live).size(); i++) {
            String animal = (String) AnimalMetaSnapshot.animals(live).get(i);
            seen.merge(animal, 1, Integer::sum);
            live.remove(animal);   // checkZone → setDZone：removeAnimal
            live.add(animal);      // addAnimal（尾端）
        }
        AnimalMetaSnapshot.end(null);
        boolean exactlyOnce = seen.size() == 5 && seen.values().stream().allMatch(n -> n == 1);
        if (off) {
            expect("off：重現原版重複補算與漏算", !exactlyOnce
                    && seen.values().stream().anyMatch(n -> n > 1) && seen.size() < 5);
        } else {
            expect("on：每隻恰補算一次 " + seen, exactlyOnce);
        }
        expect("end 之後回即時清單", AnimalMetaSnapshot.animals(live) == live);
        expect("begin 前回即時清單", AnimalMetaSnapshot.animals(live) == live);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AnimalMetaSnapshotTest 全數通過" + (off ? "（off）" : ""));
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "ams pass  " : "ams FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private AnimalMetaSnapshotTest() {}
}
