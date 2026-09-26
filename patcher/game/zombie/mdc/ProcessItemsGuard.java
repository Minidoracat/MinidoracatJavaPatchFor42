package zombie.mdc;

import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

/**
 * W40 物品處理清單 null 容錯＋跨執行緒寫入觀測（2026-09-26；docs/patches.md 2bc）。
 *
 * <p><b>事故</b>（9/26 13:52–15:21）：{@code IsoCell.processItems} 混進一個 null。伺服器每 5 秒跑一次
 * {@code ProcessItems}，每次都在 {@code i.update()} NPE（738 次）；null 之後的物品永遠不再被評估，
 * {@code finishupdate()} 為真的物品不再移出，清單一路長到「已載入容器的全部物品」。每個容器載入都要對
 * 整份清單做 {@code ArrayList.contains}，chunk 載入凍結 5–16 秒，20–29 人時 fps 從 9.8 掉到 2–3。
 * null 的來源在靜態分析中找不到（所有加入點都擋 null、封包都排入主迴圈），推測是跨執行緒寫入的資料競爭。
 *
 * <p><b>手術</b>：(1) {@code ProcessItems} 內唯一 {@code InventoryItem.update()}／{@code finishupdate()}
 * 1:1 改道：null 時不呼叫、{@code finishupdate} 回 true，讓原版把它放進 processItemsRemove，
 * 同一幀的 {@code ProcessRemoveItems} 就移掉；非 null 行為不變。(2) 四個 addToProcessItems／
 * addToProcessItemsRemove 頭部與 {@link BulkItemRegistration} 快路徑呼叫 {@link #touch}：非主執行緒寫入
 * 時記執行緒名與呼叫來源（純觀測）。每 5 分鐘一行心跳帶清單大小。kill switch {@code -Dmdc.processItemsGuard=0}。
 */
public final class ProcessItemsGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.processItemsGuard"));
    private static final String TAG = "[MinidoracatJavaPatch][ProcessItemsGuard] ";
    private static final long HEARTBEAT_MS = 300_000L;
    private static final long DETAIL_LIMIT = 20L;

    /** 第一次 ProcessItems 的執行緒＝主執行緒；之前不做跨執行緒判定。 */
    private static volatile Thread main;
    // update／finishupdate 只在主執行緒（IsoCell.ProcessItems）。
    private static long calls, nulls, lastBeat;
    private static final AtomicLong offThread = new AtomicLong();
    private static final AtomicLong anomalies = new AtomicLong();

    /** {@code IsoCell.ProcessItems} 內唯一 {@code InventoryItem.update()}。 */
    public static void update(InventoryItem item) {
        if (item != null) {
            if (main == null) {
                main = Thread.currentThread();
            }
            if ((++calls & 4095) == 0) {
                maybeBeat();
            }
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

    /** processItems／processItemsRemove 的寫入口：非主執行緒呼叫時記錄（純觀測）。 */
    public static void touch(IsoCell cell) {
        Thread m = main;
        if (!ENABLED || m == null || Thread.currentThread() == m) {
            return;
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
    }

    private static void maybeBeat() {
        long now = System.currentTimeMillis();
        if (now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            DebugLog.log(TAG + "beat calls=" + calls + " nulls=" + nulls + " size=" + size()
                    + " offThreadWrites=" + offThread.get() + " anomalies=" + anomalies.get());
        }
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
    static long nullsForTest() { return nulls; }
    static long offThreadForTest() { return offThread.get(); }

    private ProcessItemsGuard() {}
}
