package zombie.mdc;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import zombie.debug.DebugLog;

/**
 * W25 序列化物件池執行緒隔離（2026-09-06；docs/patches.md 2am）。
 *
 * <p><b>立案證據</b>（正式服 SaveAll 量測，`temp/saveall-probe.py`，4 次存檔 57／52／48／23 人）：
 * SaveAll 的 4 條 WorkerThread 各 96–106% CPU、狀態採樣 100% R（零 D 零 S）、4 條結束時間差
 * ≤0.4s＝純 CPU-bound 且分配均勻；但 jcmd 的 worker stack 樣本 **43/54＝80%** 落在
 * {@code java.util.concurrent.ConcurrentLinkedDeque.pollFirst／linkLast／unlink／skipDeletedSuccessors}，
 * 呼叫者全是 {@code zombie.util.io.BitHeader}（四個 {@code static ConcurrentLinkedDeque} 物件池：
 * {@code getHeader} 的 {@code poll()}＋各 {@code release()} 的 {@code offer()}）與
 * {@code zombie.core.utils.ByteBlock}（同款單一池）。每寫一個欄位標頭就對全域池做一次
 * poll＋offer，4 條 worker 打同一組 head/tail cache line。
 *
 * <p><b>機制驗證</b>（本機 JDK25 microbench，poll＋offer 一對，`temp/CldBench.java`）：
 * 共用 CLD 1 執行緒 19–24ns、4 執行緒 444–554ns、8 執行緒 1169–2206ns；
 * ThreadLocal ArrayDeque 4–10ns 與執行緒數無關。⇒ worker 4→8 會把池競爭放大到倒賠，
 * 正確的刀是拿掉共用池。
 *
 * <p><b>手術</b>（全部 1:1 同形 redirect，receiver 前置）：
 * <ul>
 *   <li>{@code BitHeader.getHeader} 內 {@code CLD.poll()} ×4 → {@link #poll}</li>
 *   <li>{@code BitHeader$BitHeaderByte/Short/Int/Long.release()} 內 {@code CLD.offer} ×1 → {@link #offer}</li>
 *   <li>{@code ByteBlock.Start} 內 {@code CLD.poll()} ×1 → {@link #poll}；
 *       {@code ByteBlock.End} 內 {@code CLD.contains} ×1 → {@link #contains}、{@code CLD.offer} ×1 → {@link #offer}</li>
 * </ul>
 * helper 不認識任何欄位名：以傳入的池實例做 identity 分槽（每執行緒最多 {@value #SLOTS} 個
 * 相異池；超出的池原樣委派 vanilla、計 {@code slotOverflow}，線上應恆 0）。
 *
 * <p><b>語意</b>：物件池只是 free list——{@code poll} 回本執行緒先前歸還的物件或 null（呼叫端
 * 自行 new），{@code offer} 進本執行緒池（超過 {@value #MAX_PER_POOL} 顆直接丟給 GC，回傳值
 * 沿 vanilla 恆 true）；跨執行緒配置／歸還安全（歸還進歸還者的池）。執行緒結束（SaveAll 的
 * worker 每次都是新執行緒）其池隨 Thread 物件 GC。唯一可觀察差異：{@code ByteBlock.End} 的
 * {@code assert !Core.debug || !pool.contains(block)} 改查本執行緒池——正式服 assertions
 * 關閉，該分支為死碼。header 物件本身的 bytes 佈局零變化，存檔格式／網路格式不受影響。
 *
 * <p>熱路徑零共用寫入：無 AtomicLong 計數、無 CAS；橫幅在每執行緒首次建 Local 時以
 * CAS 印一次。kill switch：{@code -Dmdc.ioPoolIsolation=0}（三處全部原樣委派 vanilla 共用池）。
 */
public final class IoPoolIsolation {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.ioPoolIsolation"))
            && !"off".equals(System.getProperty("mdc.ioPoolIsolation"));

    /** 每執行緒可分槽的相異池數（實際 5：BitHeader ×4＋ByteBlock ×1）。 */
    static final int SLOTS = 8;
    /** 每執行緒每池上限；vanilla 全域池無界，本池 LIFO 典型駐留＝巢狀深度（數十）。 */
    static final int MAX_PER_POOL = 1024;

    private static final AtomicBoolean banner = new AtomicBoolean();
    /** 超過 SLOTS 個相異池而委派 vanilla 的次數；恆應為 0（TIS 新增第 9 個池才會動）。 */
    private static final AtomicLong slotOverflow = new AtomicLong();

    /** 執行緒私有狀態：只由擁有者執行緒存取，故 ArrayDeque 不需同步。 */
    static final class Local {
        final ConcurrentLinkedDeque<?>[] keys = new ConcurrentLinkedDeque<?>[SLOTS];
        @SuppressWarnings("unchecked")
        final ArrayDeque<Object>[] pools = new ArrayDeque[SLOTS];
        int used;
        long dropped;

        Local() {
            if (banner.compareAndSet(false, true)) {
                try {
                    DebugLog.log("[MinidoracatJavaPatch][IoPoolIsolation] 首次生效"
                            + "（BitHeader/ByteBlock 物件池改執行緒私有；slots=" + SLOTS
                            + " cap=" + MAX_PER_POOL + "；-Dmdc.ioPoolIsolation=0 停用）");
                } catch (RuntimeException | LinkageError ignored) {
                    // 橫幅只是驗證便利，失敗不得影響序列化路徑
                }
            }
        }

        ArrayDeque<Object> pool(ConcurrentLinkedDeque<?> q) {
            ConcurrentLinkedDeque<?>[] k = keys;
            for (int i = 0, n = used; i < n; i++) {
                if (k[i] == q) {
                    return pools[i];
                }
            }
            if (used == SLOTS) {
                return null;
            }
            ArrayDeque<Object> d = new ArrayDeque<>(32);
            k[used] = q;
            pools[used] = d;
            used++;
            return d;
        }
    }

    private static final ThreadLocal<Local> LOCAL = ThreadLocal.withInitial(Local::new);

    /** 本執行緒對應池；off 或分槽溢位回 null＝呼叫端原樣委派 vanilla 共用池（每個改道目標恰一處委派）。 */
    private static ArrayDeque<Object> local(ConcurrentLinkedDeque<?> q) {
        if (!ENABLED) {
            return null;
        }
        ArrayDeque<Object> d = LOCAL.get().pool(q);
        if (d == null) {
            slotOverflow.incrementAndGet();
        }
        return d;
    }

    /** {@code INVOKEVIRTUAL ConcurrentLinkedDeque.poll()Object} 的改道目標。 */
    public static Object poll(ConcurrentLinkedDeque<?> q) {
        ArrayDeque<Object> d = local(q);
        return d == null ? q.poll() : d.pollFirst();
    }

    /** {@code INVOKEVIRTUAL ConcurrentLinkedDeque.offer(Object)Z} 的改道目標。 */
    public static boolean offer(ConcurrentLinkedDeque<Object> q, Object x) {
        ArrayDeque<Object> d = local(q);
        if (d == null) {
            return q.offer(x);
        }
        Objects.requireNonNull(x);   // 與 CLD.offer(null) 同款 NPE
        if (d.size() < MAX_PER_POOL) {
            d.addFirst(x);   // LIFO：剛歸還的物件仍在 L1，下一次 poll 直接命中
        } else {
            LOCAL.get().dropped++;
        }
        return true;
    }

    /** {@code INVOKEVIRTUAL ConcurrentLinkedDeque.contains(Object)Z} 的改道目標（ByteBlock.End 的 assert 分支）。 */
    public static boolean contains(ConcurrentLinkedDeque<?> q, Object x) {
        ArrayDeque<Object> d = local(q);
        return d == null ? q.contains(x) : d.contains(x);
    }

    // ---- 測試存取器 ----

    static boolean enabledForTest() {
        return ENABLED;
    }

    static long slotOverflowForTest() {
        return slotOverflow.get();
    }

    static int localPoolSizeForTest(ConcurrentLinkedDeque<?> q) {
        Local local = LOCAL.get();
        for (int i = 0; i < local.used; i++) {
            if (local.keys[i] == q) {
                return local.pools[i].size();
            }
        }
        return -1;
    }

    static long droppedForTest() {
        return LOCAL.get().dropped;
    }

    static int usedSlotsForTest() {
        return LOCAL.get().used;
    }

    private IoPoolIsolation() {}
}
