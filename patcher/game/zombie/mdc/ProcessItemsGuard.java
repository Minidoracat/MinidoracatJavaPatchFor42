package zombie.mdc;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * W40 物品處理清單 null 容錯＋W41 跨執行緒寫入改道主執行緒（2026-09-26；docs/patches.md 2bc／2bd）。
 *
 * <p><b>W40</b>：{@code IsoCell.ProcessItems} 內唯一 {@code InventoryItem.update()}／{@code finishupdate()}
 * 1:1 改道；null 時不呼叫、{@code finishupdate} 回 true，同一幀由原版 {@code ProcessRemoveItems} 移除。
 *
 * <p><b>W41</b>：W40 觀測抓到 null 的來源——{@code ServerPlayersVehicles} 執行緒載入車輛時
 * （{@code VehiclesDB2.SQLStore.loadChunk → BaseVehicle.load → setCurrentKey → ItemContainer.AddItem}）
 * 直接呼叫 {@code IsoCell.addToProcessItems}，與主執行緒同時增刪同一份 {@code ArrayList}（晚峰約每分鐘 38 次）。
 * {@code ItemContainer.AddItem} 兩個 {@code addToProcessItems} 呼叫點改道 {@link #addToProcessItems}：
 * 不在主執行緒（{@code GameServer.mainThread}）時排入佇列，由 {@code ProcessItems} 頭部 {@link #beginPass}
 * 在主執行緒補登記；佇列超過上限退回原版直接呼叫。
 *
 * <p><b>觀測</b>：四個寫入口頭部記錄非主執行緒寫入（前 20 筆＋每 1000 筆附呼叫來源）；每 5 分鐘心跳帶
 * 清單大小、ProcessItems 單次耗時、登記呼叫量與抽樣線性搜尋成本（每 256 次量一次整份 contains）。
 * kill switch：{@code -Dmdc.processItemsGuard=0}（W40＋觀測）、{@code -Dmdc.processItemsDefer=0}（W41）。
 */
public final class ProcessItemsGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.processItemsGuard"));
    private static final boolean DEFER = !"0".equals(System.getProperty("mdc.processItemsDefer"));
    private static final String TAG = "[MinidoracatJavaPatch][ProcessItemsGuard] ";
    private static final long HEARTBEAT_MS = 300_000L;
    private static final long DETAIL_LIMIT = 20L;
    private static final int DEFER_CAP = 100_000;
    private static final long SAMPLE_MASK = 255L;
    private static final Object SENTINEL = new Object();

    private static final ConcurrentLinkedQueue<InventoryItem> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pending = new AtomicInteger();
    private static final AtomicLong deferred = new AtomicLong();
    private static final AtomicLong overflow = new AtomicLong();
    private static final AtomicLong offThread = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();

    // 以下只在主執行緒（ProcessItems 與主執行緒的寫入口）。
    private static long calls, nulls, lastBeat;
    private static long passes, passNsTotal, passNsMax, passStartNs, drained;
    private static long addCalls, addAllItems, scanSamples, scanNsTotal;

    /** {@code IsoCell.ProcessItems} 頭部：補登記其他執行緒排入的物品並開始計時。 */
    public static void beginPass(IsoCell cell) {
        passStartNs = System.nanoTime();
        InventoryItem item;
        while ((item = QUEUE.poll()) != null) {
            pending.decrementAndGet();
            drained++;
            cell.addToProcessItems(item);
        }
    }

    /** {@code IsoCell.ProcessItems} 唯一 RETURN 前。 */
    public static void endPass(IsoCell cell) {
        long ns = System.nanoTime() - passStartNs;
        passes++;
        passNsTotal += ns;
        passNsMax = Math.max(passNsMax, ns);
        long now = System.currentTimeMillis();
        if (ENABLED && now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            beat(cell);
        }
    }

    /** {@code IsoCell.ProcessItems} 內唯一 {@code InventoryItem.update()}。 */
    public static void update(InventoryItem item) {
        if (item != null) {
            calls++;
            item.update();
            return;
        }
        if (!ENABLED) {
            item.update(); // 原版：NPE
            return;
        }
        nulls++;
        if (nulls <= DETAIL_LIMIT || nulls % 1000 == 0) {
            DebugLog.log(TAG + "null in processItems skipped, removed this frame nulls=" + nulls + " size=" + size()
                    + " offThreadWrites=" + offThread.get()
                    + "（原版每次 ProcessItems 在此 NPE，清單不再縮減，chunk 載入的線性 contains 越來越慢）");
        }
    }

    /** {@code IsoCell.ProcessItems} 內唯一 {@code InventoryItem.finishupdate()}；null 回 true 交給原版移除。 */
    public static boolean finishupdate(InventoryItem item) {
        if (item != null || !ENABLED) {
            return item.finishupdate();
        }
        return true;
    }

    /** {@code ItemContainer.AddItem} 的 {@code IsoCell.addToProcessItems} 改道：非主執行緒時排入佇列。 */
    public static void addToProcessItems(IsoCell cell, InventoryItem item) {
        Thread main = GameServer.mainThread;
        if (!DEFER || main == null || Thread.currentThread() == main || item == null) {
            cell.addToProcessItems(item);
            return;
        }
        if (pending.incrementAndGet() > DEFER_CAP) {
            pending.decrementAndGet();
            overflow.incrementAndGet();
            cell.addToProcessItems(item);
            return;
        }
        QUEUE.add(item);
        deferred.incrementAndGet();
    }

    /** {@code IsoCell.addToProcessItems(InventoryItem)} 頭部。 */
    public static void touchAdd(IsoCell cell) {
        if (!onMain()) {
            return;
        }
        if ((++addCalls & SAMPLE_MASK) == 0L) {
            sampleScan(cell);
        }
    }

    /** {@code IsoCell.addToProcessItems(ArrayList)} 頭部（原版逐件 contains）。 */
    public static void touchAddAll(IsoCell cell, ArrayList<InventoryItem> items) {
        if (onMain() && items != null) {
            addAllItems += items.size();
        }
    }

    /** {@code IsoCell.addToProcessItemsRemove} 兩個多載頭部與 {@link BulkItemRegistration} 快路徑。 */
    public static void touch(IsoCell cell) {
        onMain();
    }

    /** 主執行緒回 true；其他執行緒記錄後回 false；主執行緒尚未設定或 kill switch 時回 false 且不記。 */
    private static boolean onMain() {
        Thread main = GameServer.mainThread;
        if (!ENABLED || main == null) {
            return false;
        }
        if (Thread.currentThread() == main) {
            return true;
        }
        long n = offThread.incrementAndGet();
        if (n <= DETAIL_LIMIT || n % 1000 == 0) {
            try {
                DebugLog.log(TAG + "off-main-thread write n=" + n + " thread=" + Thread.currentThread().getName()
                        + " via=" + callers());
            } catch (RuntimeException | LinkageError e) {
                anomalies.incrementAndGet();
            }
        }
        return false;
    }

    private static void sampleScan(IsoCell cell) {
        long t0 = System.nanoTime();
        if (cell.getProcessItems().contains(SENTINEL)) {
            anomalies.incrementAndGet();
        }
        scanNsTotal += System.nanoTime() - t0;
        scanSamples++;
    }

    private static void beat(IsoCell cell) {
        double scanUs = scanSamples == 0 ? 0 : scanNsTotal / 1000.0 / scanSamples;
        long scans = addCalls + addAllItems;
        DebugLog.log(TAG + "beat updates=" + calls + " nulls=" + nulls + " size=" + cell.getProcessItems().size()
                + " passes=" + passes + " passMsAvg=" + fmt(passes == 0 ? 0 : passNsTotal / 1e6 / passes)
                + " passMsMax=" + fmt(passNsMax / 1e6) + " addCalls=" + addCalls + " addAllItems=" + addAllItems
                + " scanUsAvg=" + fmt(scanUs) + " estScanMs=" + (long) (scans * scanUs / 1000)
                + " deferred=" + deferred.get() + " drained=" + drained + " pending=" + pending.get()
                + " overflow=" + overflow.get() + " offThreadWrites=" + offThread.get() + " anomalies=" + anomalies.get());
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    private static int size() {
        try {
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            return cell == null ? -1 : cell.getProcessItems().size();
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            return -1;
        }
    }

    private static String callers() {
        return StackWalker.getInstance().walk(frames -> {
            StringBuilder s = new StringBuilder();
            frames.filter(f -> f.getClassName().startsWith("zombie.") && !f.getClassName().startsWith("zombie.mdc."))
                    .limit(8)
                    .forEach(f -> s.append(s.isEmpty() ? "" : "<")
                            .append(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1))
                            .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber()));
            return s.toString();
        });
    }

    static boolean enabledForTest() { return ENABLED; }
    static boolean deferForTest() { return DEFER; }
    static long nullsForTest() { return nulls; }
    static long offThreadForTest() { return offThread.get(); }
    static long deferredForTest() { return deferred.get(); }
    static int pendingForTest() { return pending.get(); }
    static long addCallsForTest() { return addCalls; }

    private ProcessItemsGuard() {}
}
