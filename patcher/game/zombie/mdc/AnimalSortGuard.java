package zombie.mdc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.animals.IsoAnimal;
import zombie.debug.DebugLog;

/**
 * W11 動物聲音排序活鎖捕手（2026-08-23 事故；docs/patches.md 2y）。
 *
 * <p><b>事故</b>：19:25:45 起 {@code IngameState.updateInternal} 拋
 * {@code IllegalArgumentException: Comparison method violates its general contract!}
 * （TimSort，stack 經 {@code BaseAnimalSoundManager.update:45} ←
 * {@code CollisionManager.resolveContactsInternal:367}），19:23–21:47 斷續 1411 次後
 * 惡化為<b>每幀必炸</b>：A 段（封包處理）活著、B 段（世界更新）每幀中斷 →
 * {@code updateManagers()}（ActionManager／TransactionManager）永久跳過 →
 * 全服卡讀條＋撿不起物品＋「時間停止」。只有重啟能解。
 *
 * <p><b>vanilla 缺陷（兩層，皆在 {@code BaseAnimalSoundManager}）</b>：
 * <ol>
 *   <li>比較器每次 {@code compare} 都<b>現場重算</b>
 *       {@code FMODParameterUtils.getClosestListenerDistanceSquared}，且用
 *       {@code > / <} 手寫三態——NaN 與任何值比較皆 false → 回 0（「相等」），
 *       違反遞移性；TimSort 偵測到即拋 IAE。無 listener 時回 {@code Float.MAX_VALUE}
 *       是一致的，所以<b>唯一能炸的輸入就是 NaN 座標</b>（動物或 listener 側）。</li>
 *   <li>{@code update()} 的 {@code characters.clear()} 在 sort <b>之後</b>
 *       （javap：sort 於 offset 19，clear 於 offset 116+）——sort 一拋，清單永不清空，
 *       已 despawn 動物的 stale 參照永久滯留 → 之後每幀重炸，<b>自我強化活鎖</b>。</li>
 * </ol>
 *
 * <p><b>觸發背景</b>（非缺陷方）：圈養農場 50–80+ 隻動物高密度碰撞＋
 * Cleaner 每分鐘批次 {@code animal:remove()} ×20——但 {@code remove()} 是合法公開 API，
 * vanilla 自己的 despawn 走同一路徑；沒有 Cleaner 也遲早會炸。
 *
 * <p><b>本 helper 的語意</b>（{@code -Dmdc.animalSortGuard=0} 停用）：
 * 改道 {@code update()} 內唯一的 {@code ArrayList.sort} callsite。正常路徑逐指令等價
 * （直接委派）。TimSort 拋 {@code IllegalArgumentException} 時：吞下、計數、
 * 掃清單記錄 NaN 座標的動物（座標能對回農場，且「nanAnimals=0 但仍炸」＝ NaN 在
 * listener 側——這個判別是後續根因的黃金診斷）、<b>不排序直接返回</b>——
 * 聲音優先級退化一幀（清單本來就每幀重建，無害），但 {@code update()} 得以走完
 * → {@code clear()} 執行 → <b>活鎖鏈條斷開</b>。
 *
 * <p><b>刻意不做的事</b>：
 * <ul>
 *   <li>不重刻排序語意（快照 key 排序）——那要假設比較器的意圖，TIS 改語意時會默默錯位；
 *       捕手對任何比較器實作都成立。</li>
 *   <li>只攔 {@code IllegalArgumentException}（TimSort 契約違反的精確型別）——
 *       其他 RuntimeException 與 Error 一律穿透維持 vanilla 行為（與 W6/W10 同紀律）。</li>
 *   <li>部分排序狀態不回滾——TimSort 就地排序，拋出時清單可能半排；
 *       該清單僅供「取前 N 近的動物發聲」，順序錯亂一幀無任何持久影響。</li>
 * </ul>
 */
public final class AnimalSortGuard {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalSortGuard"));

    /** 成功委派的排序次數；heartbeat 節拍。 */
    private static final AtomicLong sorted = new AtomicLong();
    /** 攔下的契約違反次數（本刀生效次數）。 */
    private static final AtomicLong caught = new AtomicLong();
    /** helper 自身診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    /** 逐筆詳細診斷的上限，之後只計數。 */
    private static final long DETAIL_LIMIT = 32L;
    /** heartbeat 週期（動物聲音排序每 tick 最多兩次——vocals 與 footstep 各一）。 */
    private static final long HEARTBEAT_EVERY = 16384L;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalSort] ";

    /**
     * {@code BaseAnimalSoundManager.update()} 內唯一 {@code ArrayList.sort} callsite 的改道目標。
     * 簽名對泛型擦除安全（元素以 Object 對待，診斷時才 instanceof IsoAnimal）。
     */
    public static void sort(ArrayList<IsoAnimal> list, Comparator<IsoAnimal> comp) {
        if (!ENABLED) {
            list.sort(comp);
            return;
        }
        try {
            list.sort(comp);
            if (sorted.incrementAndGet() % HEARTBEAT_EVERY == 0L) {
                heartbeat();
            }
        } catch (IllegalArgumentException e) {
            long n = caught.incrementAndGet();
            if (n <= DETAIL_LIMIT) {
                report(n, list, e);
            }
            // 不排序、不重拋：讓 update() 走完（playSound 順序退化一幀），
            // 關鍵是後續的 characters.clear() 得以執行——活鎖鏈條在此斷開。
        }
    }

    /**
     * 攔截現場診斷：清單大小＋座標為 NaN 的動物（座標可對回農場座標）。
     * {@code nanAnimals=0} 但仍炸 ＝ NaN 在 listener（玩家）側，而非動物側。
     */
    private static void report(long n, ArrayList<IsoAnimal> list, IllegalArgumentException e) {
        try {
            StringBuilder nan = new StringBuilder();
            int nanCount = 0;
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                if (!(o instanceof IsoAnimal animal)) {
                    continue;
                }
                float x = animal.getX();
                float y = animal.getY();
                float z = animal.getZ();
                if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
                    nanCount++;
                    if (nan.length() < 256) {
                        if (nan.length() > 0) {
                            nan.append(' ');
                        }
                        nan.append(x).append(',').append(y).append(',').append(z);
                    }
                }
            }
            DebugLog.log(TAG + "contract violation caught"
                    + " size=" + list.size()
                    + " nanAnimals=" + nanCount
                    + (nanCount > 0 ? " coords=" + nan : " (NaN likely on listener side)")
                    + " n=" + n
                    + " err=" + e.getMessage());
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    private static void heartbeat() {
        try {
            DebugLog.log(TAG + "sorted=" + sorted.get() + " caught=" + caught.get()
                    + " anomalies=" + anomalies.get() + " enabled=" + (ENABLED ? 1 : 0));
        } catch (RuntimeException | LinkageError ignored) {
            anomalies.incrementAndGet();
        }
    }

    private AnimalSortGuard() {}
}
