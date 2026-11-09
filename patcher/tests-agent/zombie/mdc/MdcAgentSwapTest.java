package zombie.mdc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * MdcAgent.Swap 的還原不變式：beginRestore() 回傳之後，transform 不再換任何 class；回傳之前換上的都在回傳的清單裡。
 * 多條執行緒同時載入、主執行緒在中途切到還原模式，重複多輪（沒有鎖時，transform 通過模式檢查後才被切換的那一筆會漏在清單外）。
 *
 * <p>用法：{@code java -cp <agent classes>;<本測試> zombie.mdc.MdcAgentSwapTest}
 */
public final class MdcAgentSwapTest {

    public static void main(String[] args) throws Exception {
        ClassLoader app = ClassLoader.getSystemClassLoader();
        byte[] vanilla = {1, 2, 3};
        byte[] patched = {4, 5, 6};
        int threads = 8;
        int perThread = 64;
        Map<String, MdcAgent.Payload> table = new HashMap<>();
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                table.put("t/C" + t + "_" + i, new MdcAgent.Payload(MdcAgent.sha256(vanilla), patched));
            }
        }
        int leaked = 0;
        int rounds = 400;
        // 單執行緒的基本契約：載入時換上；retransform（JVM 交來原始 bytes）照樣給同一份；redefine 成別的 bytes 不蓋掉對方
        MdcAgent.Swap one = new MdcAgent.Swap(app, table);
        if (one.transform(null, app, "t/C0_0", null, null, vanilla) != patched
                || one.transform(null, app, "t/C0_0", MdcAgentSwapTest.class, null, vanilla) != patched
                || one.transform(null, app, "t/C0_0", MdcAgentSwapTest.class, null, new byte[]{9}) != null
                || one.transform(null, app, "t/C0_1", MdcAgentSwapTest.class, null, vanilla) != null
                || one.transform(null, app, "t/C0_2", null, null, new byte[]{9}) != null
                || one.transform(null, app, "other/Class", null, null, vanilla) != null
                || one.transform(null, null, "t/C0_3", null, null, vanilla) != null) {
            throw new AssertionError("Swap.transform 的基本契約不對");
        }
        long swappedBefore = 0;
        for (int round = 0; round < rounds; round++) {
            MdcAgent.Swap swap = new MdcAgent.Swap(app, table);
            Set<String> gotPatched = ConcurrentHashMap.newKeySet();
            Set<Throwable> failures = ConcurrentHashMap.newKeySet();
            CountDownLatch start = new CountDownLatch(1);
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                int id = t;
                workers[t] = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        for (int i = 0; i < perThread; i++) {
                            String name = "t/C" + id + "_" + i;
                            if (swap.transform(null, app, name, null, null, vanilla) != null) {
                                gotPatched.add(name);
                            }
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
                workers[t].start();
            }
            start.countDown();
            // 每輪在不同的進度切換（0–399／512 個已換上）；worker 都結束了（例如 transform 丟例外）就不再等
            for (int target = round % 400; gotPatched.size() < target && java.util.Arrays.stream(workers).anyMatch(Thread::isAlive); ) {
                Thread.onSpinWait();
            }
            Set<String> snapshot = swap.beginRestore();
            for (Thread w : workers) {
                w.join();
            }
            if (!failures.isEmpty()) {
                throw new AssertionError("transform 丟例外：" + failures.iterator().next(), failures.iterator().next());
            }
            swappedBefore += snapshot.size();
            for (String name : gotPatched) {
                if (!snapshot.contains(name)) {
                    leaked++;
                }
            }
            // 還原模式下：新載入一律不換；retransform 交回原始 bytes 才記為已還原
            if (swap.transform(null, app, "t/C0_0", null, null, vanilla) != null
                    || swap.transform(null, app, "t/C0_0", MdcAgentSwapTest.class, null, vanilla) != null
                    || swap.transform(null, app, "t/C0_1", MdcAgentSwapTest.class, null, patched) != null
                    || !swap.restored().equals(Set.of("t/C0_0"))) {
                throw new AssertionError("還原模式下 transform 的結果不對：restored=" + swap.restored());
            }
        }
        if (leaked != 0) {
            throw new AssertionError("有 " + leaked + " 個 class 在切到還原模式之後才被換上、不在還原清單裡");
        }
        if (swappedBefore == 0 || swappedBefore == (long) rounds * threads * perThread) {
            throw new AssertionError("切換時機沒有落在載入中途（每輪切換前換上的總數 " + swappedBefore + "），測試沒有測到交錯");
        }
        System.out.println("MdcAgentSwapTest OK：" + rounds + " 輪、每輪 " + threads + " 條執行緒；切換前換上 " + swappedBefore
                + " 個都在還原清單裡，切換後 0 個被換上");
    }

    private MdcAgentSwapTest() {
    }
}
