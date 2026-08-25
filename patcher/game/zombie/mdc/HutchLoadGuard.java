package zombie.mdc;

import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.objects.IsoHutch;

/**
 * W17 hutch 載入回傳檢查（docs/patches.md 2ae；設計正典
 * docs/animal-persistence-guard-design-v1.md §3。靜態已定罪、直接帶 enforce）。
 *
 * <p><b>缺陷（javap 對 42.20.3 jar 實測）</b>：{@code IsoHutch.load} 對每隻反序列化動物
 * 呼 {@code addAnimalInside(animal, false)} 並<b>忽略 boolean 回傳</b>（offset 526-529：
 * {@code invokevirtual; pop}）。而 {@code addAnimalInside(IsoAnimal,Z)Z} 的落位邏輯：
 * 骰 {@code Rand.Next(0, getMaxAnimals())} 找槽位，撞上 animalInside／deadBodiesInside／
 * nestBox 佔用就重骰，>100 次跳出——<b>最終落位只查 animalInside</b>（offset 148-151），
 * 佔用即 return false。false ⇒ 該動物不進任何容器、不進世界、隨 GC 消失＝<b>載入即滅失</b>。
 * 觸發情境＝接近滿舍（vanilla 雞舍 maxAnimals=20；屍體與 nestBox 佔位會把有效槽擠掉，
 * 讓「有空槽卻機率性 false」成為常態）。兔子爆量案例正對此形。
 *
 * <p><b>手術</b>：redirect {@code IsoHutch.load} 內該 callsite（恰 1）→ {@link #addInside}。
 * helper 委派原方法（恰 1 次）；回 false 時按模式救援：
 * <ol>
 *   <li>重複 add（{@code containsValue}＝vanilla 已 warn 的 case）不救——該動物已在舍內，
 *       load blob 重複時強塞會造出雙槽同體。</li>
 *   <li>順序掃 slot 0..max-1：第一輪找「animalInside 與 deadBodiesInside 都空」的乾淨槽；
 *       沒有再退第二輪只查 animalInside（＝vanilla 最終落位的判準，不劣於 vanilla；
 *       {@code checkNestBoxPrefPosition} 是 private、無法納入，略過它同樣不劣於 vanilla
 *       ——vanilla 自己的最終落位也不查 nestBox）。<b>順序掃、零 {@code Rand} 呼叫</b>
 *       （SmokeCheck 釘死：不動全域 RNG 序列）。</li>
 *   <li>enforce：對齊 vanilla 成功狀態六步（javap offset 154-219）——
 *       {@code animalInside.put(slot)}＋{@code animal.hutch=this}＋
 *       {@code getData().setPreferredHutchPosition(slot)}＋{@code setHutchPosition(slot)}＋
 *       {@code setItemID(0)}＋{@code tryRemoveAnimalFromWorld(animal)}。preferred 必須等於
 *       map key（vanilla 成功時就是最後一骰的位置）；該 tryRemove body 是 client-only、
 *       server 上 no-op，照呼維持同構。sync 分支對齊 load 的 sendEvent=false，不走。</li>
 *   <li>真滿（兩輪都無槽）：CRITICAL log（動物 type/id＋hutch 座標）——至少把靜默滅失
 *       變成可補償的有聲事件。回 false（vanilla 值）。</li>
 * </ol>
 *
 * <p><b>client 安全性（2n 通則檢查）</b>：client 端 {@code IsoHutch.load} 在 worldVersion
 * ≥212 直接 skip 動物 blob（offset 191-209），迴圈不執行、redirect 為死碼；且 loose class
 * 只部署 server——無 desync 面。
 *
 * <p><b>三態</b>：{@code -Dmdc.hutchLoadGuard}＝{@code 1}｜未設 enforce（預設出貨）／
 * {@code 2} observe（只 log 不救，行為與 vanilla 逐位元相同）／{@code 0} off（純委派）。
 * 委派本身永不包 try——vanilla 拋什麼照拋；救援段包 try（RuntimeException｜LinkageError
 * → anomalies++、退回 vanilla 值），自身故障不得比 vanilla 更糟。
 */
public final class HutchLoadGuard {

    static final int MODE_OFF = 0;
    static final int MODE_ENFORCE = 1;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();

    private static final String TAG = "[MinidoracatJavaPatch][HutchLoadGuard] ";

    /** 委派呼叫數（load 逐隻一次）。 */
    private static final AtomicLong delegated = new AtomicLong();
    /** vanilla 回 false 的次數（滅失候補；enforce 下＝進入救援的次數）。 */
    private static final AtomicLong vanillaRejects = new AtomicLong();
    /** enforce：成功強制入位數。 */
    private static final AtomicLong forced = new AtomicLong();
    /** observe：本可強制入位（有空槽）但按模式不救的次數。 */
    private static final AtomicLong wouldForce = new AtomicLong();
    /** 兩輪掃描都無槽的真滿事件（CRITICAL）。 */
    private static final AtomicLong critical = new AtomicLong();
    /** 重複 add（containsValue）不救的次數。 */
    private static final AtomicLong duplicates = new AtomicLong();
    /** helper 自身診斷失敗數；恆應為 0。 */
    private static final AtomicLong anomalies = new AtomicLong();

    private HutchLoadGuard() {}

    private static int parseMode() {
        String raw = System.getProperty("mdc.hutchLoadGuard");
        if (raw == null) {
            return MODE_ENFORCE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            case "2":
            case "observe":
                return MODE_OBSERVE;
            default:
                return MODE_ENFORCE;
        }
    }

    /**
     * {@code IsoHutch.load} 內 {@code addAnimalInside(IsoAnimal,Z)Z} 的改道目標。
     * 委派恰 1 次；false 才進救援分流。
     */
    public static boolean addInside(IsoHutch hutch, IsoAnimal animal, boolean sendEvent) {
        boolean ok = hutch.addAnimalInside(animal, sendEvent);
        if (MODE == MODE_OFF) {
            return ok;                       // kill switch：純委派，零觀測、零救援
        }
        delegated.incrementAndGet();
        if (ok) {
            return true;
        }
        try {
            vanillaRejects.incrementAndGet();
            if (animal == null) {
                anomalies.incrementAndGet();
                return false;
            }
            if (hutch.animalInside.containsValue(animal)) {
                duplicates.incrementAndGet();
                log("duplicate add ignored (vanilla warned) hutch=" + where(hutch)
                        + " animal=" + who(animal));
                return false;
            }
            int slot = findSlot(hutch);
            if (slot < 0) {
                critical.incrementAndGet();
                log("CRITICAL hutch full, animal lost by vanilla semantics hutch=" + where(hutch)
                        + " max=" + hutch.getMaxAnimals() + " animal=" + who(animal));
                return false;
            }
            if (MODE == MODE_OBSERVE) {
                wouldForce.incrementAndGet();
                log("observe: vanilla dropped animal, free slot=" + slot + " exists hutch="
                        + where(hutch) + " animal=" + who(animal));
                return false;
            }
            forceInto(hutch, animal, slot);
            forced.incrementAndGet();
            log("forced slot=" + slot + " hutch=" + where(hutch) + " animal=" + who(animal)
                    + " forced=" + forced.get());
            return true;
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
            try {
                DebugLog.log(TAG + "rescue failed (vanilla value kept): " + e);
            } catch (RuntimeException | LinkageError ignored) {
                // 連 log 都失敗就只剩計數
            }
            return false;
        }
    }

    /**
     * 兩輪順序掃描（零 Rand）：先「animalInside＋deadBodiesInside 都空」的乾淨槽，
     * 退而求其次只查 animalInside（vanilla 最終落位判準）。使用 get()==null 而非
     * containsKey：public map 若含 key→null，vanilla 視為空槽，helper 必須同樣處理。-1＝真滿。
     * package-private 只供確定性行為測試直接鎖 clean-priority／fallback／null-value。
     */
    static int findSlot(IsoHutch hutch) {
        int max = hutch.getMaxAnimals();
        int fallback = -1;
        for (int i = 0; i < max; i++) {
            Integer key = i;
            if (hutch.animalInside.get(key) != null) {
                continue;
            }
            if (hutch.deadBodiesInside.get(key) == null) {
                return i;
            }
            if (fallback < 0) {
                fallback = i;
            }
        }
        return fallback;
    }

    /**
     * vanilla 成功路徑的等價強制入位（javap offset 154-219；sync 段對齊 sendEvent=false
     * 不走）。vanilla 成功時的狀態不變式是 {@code preferredHutchPosition == hutchPosition
     * == put 的 key}（落位值就是迴圈最後一骰的 preferred），故這裡把 preferred 一併補齊
     * ——漏掉會讓後續依賴 preferred 的進出籠／重骰路徑看到陳舊值（review-lane-grok 抓的
     * 狀態漂移面）。
     */
    static void forceInto(IsoHutch hutch, IsoAnimal animal, int slot) {
        // 先解析所有必要參照，再動 map/backlink；資料異常時不得留下「已 put 但 helper
        // 回 false」的半套狀態。正常 load 的 AnimalData 一定非 null。
        AnimalData data = animal.getData();
        if (data == null) {
            throw new IllegalStateException("loaded animal has no AnimalData");
        }
        hutch.animalInside.put(slot, animal);
        animal.hutch = hutch;
        data.setPreferredHutchPosition(slot);
        data.setHutchPosition(slot);
        animal.setItemID(0);
        hutch.tryRemoveAnimalFromWorld(animal);
    }

    private static String where(IsoHutch hutch) {
        try {
            return "(" + (int) hutch.getX() + "," + (int) hutch.getY() + "," + (int) hutch.getZ() + ")";
        } catch (RuntimeException | LinkageError e) {
            return "(?)";
        }
    }

    private static String who(IsoAnimal animal) {
        try {
            return animal.getAnimalType() + "#" + animal.getAnimalID();
        } catch (RuntimeException | LinkageError e) {
            return "?#?";
        }
    }

    private static void log(String message) {
        try {
            DebugLog.log(TAG + message);
        } catch (RuntimeException | LinkageError e) {
            anomalies.incrementAndGet();
        }
    }

    // ---- 測試鉤（package-private；行為測試取差值用） ----

    static long delegatedForTest() { return delegated.get(); }
    static long vanillaRejectsForTest() { return vanillaRejects.get(); }
    static long forcedForTest() { return forced.get(); }
    static long wouldForceForTest() { return wouldForce.get(); }
    static long criticalForTest() { return critical.get(); }
    static long duplicatesForTest() { return duplicates.get(); }
    static long anomaliesForTest() { return anomalies.get(); }
}
