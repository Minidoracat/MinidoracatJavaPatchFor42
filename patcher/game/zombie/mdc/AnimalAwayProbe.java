package zombie.mdc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.objects.IsoFeedingTrough;

/**
 * W32 動物離線補算觀測（2026-09-24；docs/patches.md 2au）＋W42 補算時數上限（2026-09-27；2be）
 * ＋W49 離線補算根治（2026-10-01；2bm）＋W56 補算等畜牧區就緒（2026-10-08；2bt）。
 *
 * <p><b>症狀</b>（正式服 9/24 13:01:53／14:22:27）：同一牧場 chunk 重新載入時主迴圈凍結約 13s，
 * 隨即同秒 6 隻動物死亡、地上大量糞便、豬群一口氣 12 胎。兩張 MainLoopWatchdog 快照同為
 * {@code AnimalManagerMain.fromWorker → IsoAnimal.updateStatsAway → AnimalData.hourGrow}。
 *
 * <p><b>成因</b>：vanilla 以 {@code worldAgeHours - zone.hourLastSeen} 當離線時數，
 * 但 {@code DesignationZone.hourLastSeen} 只在「整個 zone 兩角都離開串流」時更新；
 * 大圍場部分 chunk 重載、或 zone 在關機時仍串流中，都會拿到陳舊值、重補數天。
 * 9/26 20:06 session：174/650 筆補算比動物自身離線時間多 ≥24h，38 隻死亡中 25 隻在補算後 60 秒內。
 *
 * <p><b>W32 手術</b>：全 jar 三個 {@code updateStatsAway(I)V} 呼叫點 1:1 改道——{@code fromWorker} ×1
 * （source=chunk）與 {@code DesignationZoneAnimal.doMeta} ×2（source=zone）；委派 vanilla、計時、比對兩種時數。
 * kill switch {@code -Dmdc.animalAwayProbe=0}（不計數、不記錄）。
 *
 * <p><b>W42</b>：補算時數取 {@code min(zone 時數, 動物自身離線時數)}；動物自身時鐘
 * {@code timeSinceLastUpdate} 由 {@code unloaded()} 寫入，W42 另在活著的每小時（{@code AnimalData.update}
 * 內唯一 {@code hourGrow(false)}）刷新，否則一直載入中的動物時鐘停在上次卸載、上限無效。
 * 無時鐘紀錄（-1，新生或舊存檔）時沿用 vanilla 時數；時鐘在未來（先前多補）時補 0 小時。
 * kill switch {@code -Dmdc.animalCatchUpCap=0}（回 vanilla 時數且不刷新時鐘；W49 的自身時鐘補算一併停用）。
 *
 * <p><b>W49</b>（原版少補，懷孕與成長只有五到六成）：
 * <ul>
 *   <li>自身時鐘補算（A1）：chunk 路徑改以動物自身離線時數補算，不看 zone。原版在 {@code connectedDZone}
 *       尚未填入（本次開機首次進世界、放養動物）時補 0，zone 只部分串流時也只補到 {@code hourLastSeen}。
 *       自身時鐘在卸載時寫入、載入中與雞舍內每小時刷新、補算時逐小時推進，不會重複計算同一段時間。
 *       zone 路徑維持 W42。存檔寫出自身時鐘（A3）由 {@code MdcAnimalSave} 負責，兩者共用
 *       {@code -Dmdc.animalOwnClock=0} 開關，且依賴 W42。</li>
 *   <li>長離線上限：一次離線最多補 {@code -Dmdc.animalCatchUpLimit}（預設 168）遊戲小時；超過時先把時鐘設為
 *       「現在 − 上限」再委派，較早的部分丟棄。只在自身時鐘補算開啟時生效，≤0＝不設上限。{@code updateStatsAway}
 *       頭部的 {@link #entryHours} 讓不經三個 Java 改道的直接呼叫（牲畜拖車 Lua、管理員指令）同樣受上限約束並記帳。
 *       無時鐘紀錄（新生、遷徙群）沿用原版時數、只受上限約束，不在「可信的單段離線」保證之內。</li>
 *   <li>累積小時語意（A4）：{@code updateStatsAway} 內四個呼叫改道。原版一次性把 age 加上
 *       {@code floor(時數/24)×mod}、把 {@code hoursSurvived} 設成 {@code age×24}（丟掉最多 23 小時的累積），
 *       並在日曆午夜呼叫 {@code growUp}；改為與載入中相同的逐小時累積，每滿 24 小時成長一次。
 *       kill switch {@code -Dmdc.animalCatchUpAccrual=0}。</li>
 *   <li>掛鉤屠體不補算（B3）：只清 {@code fromMeta}（原版的第一步）後返回。kill switch
 *       {@code -Dmdc.animalCarcassGuard=0}。</li>
 *   <li>雞舍時鐘：{@code IsoHutch.updateAnimalInside} 每小時推進 {@code hoursSurvived} 時一併刷新時鐘，
 *       放出後不會被 {@code doMeta} 當成離線再補一次。跟隨 W42 開關。</li>
 * </ul>
 *
 * <p><b>W56</b>（chunk 路徑補算看不到槽，2026-10-07 玩家回報「離線結算當成沒吃沒喝」）：卸載時
 * {@code IsoFeedingTrough.removeFromWorld} 把槽移出 {@code zone.troughs}，只有 {@code DesignationZoneAnimal.check()}
 * （畜牧區兩角都已載入時）重建槽、地上食物與河邊格；伺服器 cell 載入在同一個呼叫裡逐 chunk 加入物件並呼叫
 * {@code fromWorker}，所以 chunk 路徑補算時槽還沒登記回來。原版靠之後 {@code doMeta}（先 {@code check()}）再補一次，
 * W42 把那次截成 0。改為：
 * <ul>
 *   <li>延後：chunk 路徑上有自身時鐘、不是野生、站在畜牧區內的動物，記下本次時數並凍結（{@code fromMeta=true}，
 *       原版 {@code updateInternal} 因此整段跳過，也不刷新時鐘），排進佇列。</li>
 *   <li>排空：{@code IsoWorld.update} 唯一的 {@code DesignationZone.update()} 改道 {@link #zoneUpdate}，照原版更新後處理佇列：
 *       相連畜牧區的 chunk 全部載入（或延後超過 {@code -Dmdc.animalCatchUpDeferMs}）時，先對這些畜牧區 {@code check()}，
 *       再把時鐘還原成延後當下的值、以記下的時數補算。伺服器每幀先載入 cell 再更新世界，通常同一幀就補完。</li>
 *   <li>一致：zone 路徑略過延後中的動物；直接呼叫（拖車、管理員指令）接手時取消延後；延後中被卸載時把時鐘還原成
 *       延後前的值（凍結期間沒有模擬，下次載入從頭補）；延後中要離開世界（{@code IsoAnimal.removeFromWorld} 頭部：抱起、
 *       放進拖車、被移除）就當場補完；補算跟著 {@code AnimalData.parent}（放下時 copyFrom 換新物件）；延後中死亡的不補；
 *       存檔時延後中的動物寫自身時鐘（MdcAnimalSave）。</li>
 *   <li>槽登記：{@code IsoFeedingTrough.addToWorld} 兩個 {@code checkOverlayAfterAnimalEat()} 改道 {@link #troughAddedToWorld}，
 *       槽進世界就登記回所在畜牧區，畜牧區只載入一部分時也看得到已載入的槽。</li>
 * </ul>
 * kill switch {@code -Dmdc.animalCatchUpDefer=0}（依賴 W49 自身時鐘）、{@code -Dmdc.troughZoneRegister=0}。
 * vanilla 例外原樣穿透（委派例外另計 delegateFailures）；只有診斷失敗計 anomalies。
 */
public final class AnimalAwayProbe {

    private static final boolean ENABLED = !"0".equals(System.getProperty("mdc.animalAwayProbe"));
    private static final boolean CAP = !"0".equals(System.getProperty("mdc.animalCatchUpCap"));
    /** W49：chunk 路徑以自身時鐘補算（A1），apop 寫出已卸載動物的自身時鐘（A3，MdcAnimalSave）。 */
    private static final boolean OWN = CAP && !"0".equals(System.getProperty("mdc.animalOwnClock"));
    /** W49：一次離線最多補算的遊戲小時；≤0＝不設上限。只在 {@link #OWN} 時生效。 */
    static final int LIMIT = Integer.getInteger("mdc.animalCatchUpLimit", 168);
    /** W49：補算改用累積小時語意（A4，含補 0 小時保留餘數）。 */
    private static final boolean ACCRUAL = !"0".equals(System.getProperty("mdc.animalCatchUpAccrual"));
    /** W49：掛鉤屠體不接受離線補算（B3）。 */
    private static final boolean CARCASS = !"0".equals(System.getProperty("mdc.animalCarcassGuard"));
    /** W56：chunk 路徑補算延後到畜牧區就緒；依賴 W49 自身時鐘（zone 路徑略過延後中的動物，時數只能來自自身時鐘）。 */
    private static final boolean DEFER = OWN && !"0".equals(System.getProperty("mdc.animalCatchUpDefer"));
    /** W56：延後最長等待（真實時間）；畜牧區一直只載入一部分時，逾時就以已載入的部分補算。 */
    static final long DEFER_NS = Math.max(0L, Long.getLong("mdc.animalCatchUpDeferMs", 10_000L)) * 1_000_000L;
    /** W56：飼料槽進世界時登記回所在畜牧區。 */
    private static final boolean TROUGH_REGISTER = !"0".equals(System.getProperty("mdc.troughZoneRegister"));
    /** 超過此時數（zone 值）計入 big。 */
    private static final int DETAIL_HOURS = Integer.getInteger("mdc.animalAwayProbe.detailHours", 24);
    /** zone 時數比動物自身多出此時數以上才算多算（mismatch）並逐筆記錄。 */
    private static final int OVERSHOOT_HOURS = 24;
    /** 無時鐘紀錄（新生或舊存檔）。 */
    static final long NO_RECORD = Long.MIN_VALUE;
    private static final long HOUR_MS = 3_600_000L;
    private static final long WINDOW_MS = 60_000L;
    private static final int WINDOW_LINES = 30;
    private static final long HEARTBEAT_MS = 300_000L;
    /** 前一次補算結束到這一次開始小於此值，視為同一段連續補算（通常是同一幀的 fromWorker／doMeta 迴圈）。 */
    private static final long RUN_GAP_NS = 50_000_000L;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalAwayProbe] ";

    // 只在主執行緒（fromWorker／doMeta／動物與雞舍 update）呼叫；計數不需原子。
    private static long calls, big, mismatch, died, anomalies, suppressed, capped, cappedHours, clockRefresh;
    private static long maxHours, maxAnimalHours, sumHours, totalNs, maxNs;
    private static long ownCatchUps, ownGainHours, limited, limitedHours, carcassSkips, accrualGrowths, hutchRefresh;
    private static long directCalls, directHours, directLimited, directLimitedHours, delegateFailures;
    private static long runNs, runLastEndNs, runCalls, maxRunNs, maxRunCalls;
    private static long deferred, deferDrained, deferTimeouts, deferUnloaded, deferDead, deferZoneSkips, deferDirect;
    private static long deferFailures, deferLeaving, deferTransferred, zoneRefreshes, maxDeferNs;
    /** W56 延後中的補算（只在主執行緒：fromWorker、IsoWorld.update、chunk 卸載）；ORDER 保留延後順序，完成的條目標 done 後壓縮。 */
    private static final Map<IsoAnimal, Deferred> PENDING = new IdentityHashMap<>();
    private static final List<Deferred> ORDER = new ArrayList<>();
    /**
     * observe 委派的一次性票證（主執行緒）：只放行「正是這隻動物」的下一次 updateStatsAway 入口，放行即消耗；
     * 委派期間若有 MOD 回呼對別的動物（或同一隻再一次）直接呼叫，照樣當成直接呼叫受上限約束。
     */
    private static IsoAnimal probeTicket;
    private static long windowStart, windowLines, lastBeat;

    /** {@code AnimalManagerMain.fromWorker}：chunk 載入時單隻動物補算。 */
    public static void updateStatsAway(IsoAnimal animal, int hours) {
        observe(animal, hours, true);
    }

    /** {@code DesignationZoneAnimal.doMeta}：zone 重新完整串流時整區補算（兩處 callsite）。 */
    public static void updateStatsAwayZone(IsoAnimal animal, int hours) {
        observe(animal, hours, false);
    }

    /** {@code AnimalData.update} 內唯一 {@code hourGrow(false)}：活著的每小時刷新動物自身時鐘。 */
    public static void liveHourGrow(AnimalData data, boolean meta) {
        if (CAP && data.parent != null) {
            data.parent.timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis();
            clockRefresh++;
        }
        data.hourGrow(meta);
    }

    /**
     * {@code IsoAnimal.updateStatsAway} 頭部的時數過濾。經三個 Java 改道（observe）的呼叫已由 {@link #plan} 決定，憑一次性票證原樣通過；
     * 其餘直接呼叫——牲畜拖車 Lua {@code Vehicles.Update.TrailerAnimalFood}（以車輛零件上次更新推算的離線時數）與管理員
     * 指令——保留原本的時數來源，只套同一個長離線上限並記帳（W32 計數、W39 帳本）。拖車動物在這之前可能剛被
     * {@code liveHourGrow} 刷新時鐘，所以不能改用自身時鐘取較小值。
     */
    public static int entryHours(IsoAnimal animal, int hours) {
        if (animal == probeTicket) {
            probeTicket = null;
            return hours;
        }
        if (!ORDER.isEmpty()) {
            Deferred d = PENDING.remove(animal);
            if (d != null) {
                d.done = true;   // 直接呼叫接手這段離線，延後的那次不再補
                deferDirect++;
            }
        }
        try {
            directCalls++;
            int applied = hours;
            if (OWN && LIMIT > 0 && hours > LIMIT) {
                directLimited++;
                directLimitedHours += hours - LIMIT;
                applied = LIMIT;
            }
            directHours += Math.max(applied, 0);
            AnimalDeathLedger.noteCatchUp(animal, applied);
            return applied;
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            return hours;
        }
    }

    /** {@code IsoHutch.updateAnimalInside} 的兩個 {@code setHoursSurvived}（雞舍內每小時推進）：同時刷新時鐘。 */
    public static void hutchHoursSurvived(IsoAnimal animal, double hours) {
        animal.setHoursSurvived(hours);
        if (CAP) {
            animal.timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis();
            hutchRefresh++;
        }
    }

    // ---- W49 累積小時語意：IsoAnimal.updateStatsAway 內四個呼叫的改道 ----

    /** 原版補算前一次性 {@code setHoursSurvived(新 age×24)}（丟掉累積）：累積語意下略過。 */
    public static void accrualSetHoursSurvived(IsoAnimal animal, double hours) {
        if (!ACCRUAL) {
            animal.setHoursSurvived(hours);
        }
    }

    /** 原版補算前一次性 {@code setAge(age + floor(時數/24)×mod)}：累積語意下略過，改由逐小時推進。 */
    public static void accrualSetAge(AnimalData data, int age) {
        if (!ACCRUAL) {
            data.setAge(age);
        }
    }

    /**
     * 補算迴圈每小時的 {@code hourGrow(true)}：累積語意下照 {@code AnimalData.update}（載入中）的順序——
     * {@code hoursSurvived + 1}、累積滿 24 小時就把 age 設成 {@code daysSurvived + (mod − 1)} 並重設
     * {@code hoursSurvived}、{@code hourGrow}、有成長時 {@code growUp}——只把兩者的 meta 旗標換成補算的 true。
     */
    public static void accrualHourGrow(AnimalData data, boolean meta) {
        if (!ACCRUAL) {
            data.hourGrow(meta);
            return;
        }
        IsoAnimal parent = data.parent;
        parent.setHoursSurvived(parent.getHoursSurvived() + 1.0);
        boolean grow = false;
        if (data.getAge() < data.getDaysSurvived()) {
            float mod = data.getAgeGrowModifier();
            data.setAge(Float.valueOf(data.getDaysSurvived() + (mod - 1.0F)).intValue());
            parent.setHoursSurvived(data.getAge() * 24);
            grow = true;
        }
        data.hourGrow(meta);
        if (grow) {
            data.growUp(meta);
            accrualGrowths++;
        }
    }

    /** 補算迴圈在日曆午夜的 {@code growUp(true)}：累積語意下略過（成長改在累積滿 24 小時時觸發）。 */
    public static void accrualGrowUp(AnimalData data, boolean meta) {
        if (!ACCRUAL) {
            data.growUp(meta);
        }
    }

    /** MdcAnimalSave（A3）讀取：自身時鐘補算是否開啟。 */
    public static boolean ownClockEnabled() {
        return OWN;
    }

    private static void observe(IsoAnimal animal, int hours, boolean chunk) {
        if (CARCASS && animal.isOnHook()) {
            animal.fromMeta = false;   // 原版 updateStatsAway 的第一步
            carcassSkips++;
            return;
        }
        if (!chunk && PENDING.containsKey(animal)) {
            deferZoneSkips++;   // 延後的那次會在相連畜牧區都 check() 之後補；這裡再以 zone 時數補就重複
            return;
        }
        // 必須在委派前讀：vanilla 會推進 timeSinceLastUpdate。
        long animalHours = ENABLED || CAP ? animalHoursAway(animal) : NO_RECORD;
        int applied = plan(animal, hours, animalHours, chunk);
        if (chunk && DEFER && defer(animal, hours, applied, animalHours)) {
            return;
        }
        run(animal, hours, applied, animalHours, chunk ? "chunk" : "zone");
    }

    private static void run(IsoAnimal animal, int hours, int applied, long animalHours, String source) {
        AnimalDeathLedger.noteCatchUp(animal, applied);
        long t0 = ENABLED ? System.nanoTime() : 0L;
        delegate(animal, applied);
        if (!ENABLED) {
            return;
        }
        long t1 = System.nanoTime();
        accumulateRun(t0, t1);
        record(animal, hours, applied, animalHours, t1 - t0, source);
    }

    // ---- W56 補算等畜牧區就緒 ----

    /**
     * chunk 路徑補算是否延後：與 {@link #plan} 改用自身時鐘的條件一致（有時鐘、不是野生），且站在畜牧區內
     * （畜牧區外的補算沒有槽可用，延後無益）。已在佇列中的同一隻不重複排入。
     */
    private static boolean defer(IsoAnimal animal, int hours, int applied, long animalHours) {
        if (PENDING.containsKey(animal)) {
            return true;
        }
        if (animalHours == NO_RECORD || animal.isWild()) {
            return false;
        }
        try {
            if (DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ()) == null) {
                return false;
            }
        } catch (RuntimeException e) {
            anomalies++;
            return false;
        }
        animal.fromMeta = true;   // 原版 fromWorker 已設（IngameState.loading 時除外）：凍結到補算為止，期間也不刷新時鐘
        Deferred d = new Deferred(animal, hours, applied, animalHours, animal.timeSinceLastUpdate, System.nanoTime());
        PENDING.put(animal, d);
        ORDER.add(d);
        deferred++;
        return true;
    }

    /** {@code IsoWorld.update} 內唯一的 {@code DesignationZone.update()}：照原版更新畜牧區後，補完已就緒的延後補算。 */
    public static void zoneUpdate() {
        try {
            DesignationZone.update();
        } finally {
            if (!ORDER.isEmpty()) {
                drain(System.nanoTime(), AnimalAwayProbe::zoneLoaded, DesignationZoneAnimal::check);
            }
        }
    }

    /**
     * 補完就緒或逾時的延後補算：先對它們的相連畜牧區各 {@code check()} 一次（重建槽、地上食物、河邊格；只對兩角都已
     * 載入的畜牧區生效），再依延後順序委派。每筆最晚在逾時那一輪結束；例外只計數，不中斷其他筆，也不讓動物停在凍結狀態。
     */
    static void drain(long nowNs, Predicate<DesignationZoneAnimal> loaded, Consumer<DesignationZoneAnimal> refresh) {
        Map<DesignationZoneAnimal, List<DesignationZoneAnimal>> nets = new IdentityHashMap<>();
        Map<DesignationZoneAnimal, Boolean> loadedMemo = new IdentityHashMap<>();
        List<Deferred> due = new ArrayList<>();
        for (Deferred d : ORDER) {
            if (d.done) {
                continue;
            }
            d.net = network(d.animal, nets);
            d.timedOut = !allLoaded(d.net, loaded, loadedMemo);
            if (!d.timedOut || nowNs - d.since >= DEFER_NS) {
                due.add(d);
            }
        }
        if (!due.isEmpty()) {
            Set<DesignationZoneAnimal> refreshed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Deferred d : due) {
                for (DesignationZoneAnimal zone : d.net) {
                    if (refreshed.add(zone)) {
                        try {
                            refresh.accept(zone);
                            zoneRefreshes++;
                        } catch (RuntimeException e) {
                            anomalies++;
                        }
                    }
                }
            }
            for (Deferred d : due) {
                finish(d, nowNs);
            }
        }
        ORDER.removeIf(d -> d.done);
    }

    private static void finish(Deferred d, long nowNs) {
        if (d.done) {
            return;   // 排空途中被直接呼叫接手
        }
        d.done = true;
        PENDING.remove(d.animal);
        maxDeferNs = Math.max(maxDeferNs, nowNs - d.since);
        // 放下、放出拖車會以 copyFrom 建立新物件、共用同一份 AnimalData（parent 改指新物件）：補算跟著資料的擁有者。
        IsoAnimal animal = owner(d.animal);
        if (animal != d.animal) {
            deferTransferred++;
        }
        try {
            if (animal.isDead()) {
                deferDead++;
                return;
            }
            deferDrained++;
            if (d.timedOut) {
                deferTimeouts++;
            }
            animal.timeSinceLastUpdate = d.clock;   // 補算從延後當下的時鐘逐小時推進
            run(animal, d.hours, d.applied, d.animalHours, "chunk");
            if (d.timedOut && ENABLED) {
                detail(animal, d.hours, d.applied, d.animalHours, 0L, animal.isDead(), "chunk-timeout");
            }
        } catch (RuntimeException e) {
            // 委派例外已計 delegateFailures；不外拋，免得 IsoWorld.update 中斷。原版在 fromWorker 拋出時剩餘時數同樣沒有補，
            // 這裡至少留下可定位的紀錄（補到哪個時鐘、還差多少）。
            deferFailures++;
            failureLog(animal, d, e);
        } finally {
            d.animal.fromMeta = false;
            animal.fromMeta = false;
        }
    }

    private static IsoAnimal owner(IsoAnimal animal) {
        try {
            AnimalData data = animal.getData();
            return data != null && data.parent != null ? data.parent : animal;
        } catch (RuntimeException e) {
            anomalies++;
            return animal;
        }
    }

    private static void failureLog(IsoAnimal animal, Deferred d, RuntimeException e) {
        try {
            long now = System.currentTimeMillis();
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                windowLines = 0;
            }
            if (++windowLines > WINDOW_LINES) {
                suppressed++;
                return;
            }
            long doneHours = Math.floorDiv(animal.timeSinceLastUpdate - d.clock, HOUR_MS);
            DebugLog.log(TAG + "deferred catch-up failed animal=" + animal.getAnimalType() + "#" + animal.getAnimalID()
                    + " pos=" + (int) animal.getX() + "," + (int) animal.getY() + "," + (int) animal.getZ()
                    + " applied=" + d.applied + " done=" + doneHours + " lost=" + Math.max(0L, d.applied - doneHours)
                    + " clockStart=" + d.clock + " clockNow=" + animal.timeSinceLastUpdate + " error=" + e);
        } catch (RuntimeException | LinkageError ignored) {
            anomalies++;
        }
    }

    private static List<DesignationZoneAnimal> network(IsoAnimal animal,
            Map<DesignationZoneAnimal, List<DesignationZoneAnimal>> nets) {
        try {
            DesignationZoneAnimal zone = DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ());
            if (zone == null) {
                return List.of();
            }
            List<DesignationZoneAnimal> net = nets.get(zone);
            if (net == null) {
                net = DesignationZoneAnimal.getAllDZones(null, zone, null);
                nets.put(zone, net);
            }
            return net;
        } catch (RuntimeException e) {
            anomalies++;
            return List.of();
        }
    }

    private static boolean allLoaded(List<DesignationZoneAnimal> net, Predicate<DesignationZoneAnimal> loaded,
            Map<DesignationZoneAnimal, Boolean> memo) {
        for (DesignationZoneAnimal zone : net) {
            Boolean ok = memo.get(zone);
            if (ok == null) {
                try {
                    ok = loaded.test(zone);
                } catch (RuntimeException e) {
                    anomalies++;
                    ok = false;
                }
                memo.put(zone, ok);
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 畜牧區矩形涵蓋的每個 chunk 都已載入（伺服器的 getChunkForGridSquare 走 ServerMap，cell 未載入回 null）。 */
    static boolean zoneLoaded(DesignationZoneAnimal zone) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell == null) {
            return false;
        }
        for (int wy = Math.floorDiv(zone.y, 8); wy <= Math.floorDiv(zone.y + zone.h - 1, 8); wy++) {
            for (int wx = Math.floorDiv(zone.x, 8); wx <= Math.floorDiv(zone.x + zone.w - 1, 8); wx++) {
                if (cell.getChunkForGridSquare(wx * 8, wy * 8, zone.z) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code AnimalPopulationManager.removeChunkFromWorld} 內唯一的 {@code unloaded()}（原版把時鐘寫成現在）。延後中的動物
     * 凍結、沒有模擬，時鐘還原成延後當下，下次載入從頭補算這段離線時間。
     */
    public static void unloaded(IsoAnimal animal) {
        Deferred d = ORDER.isEmpty() ? null : PENDING.remove(animal);
        try {
            animal.unloaded();
        } finally {
            if (d != null) {
                d.done = true;
                animal.timeSinceLastUpdate = d.clock;
                deferUnloaded++;
            }
        }
    }

    /**
     * {@code IsoAnimal.removeFromWorld()} 頭部：延後中的動物要離開世界（抱起、放進拖車、被移除）時當場補完，與原版
     * 「載入時就補算、之後才可能被搬動」的順序一致，也不會在放下時建立的新物件上留下補算。chunk 卸載先經 {@link #unloaded}
     * 移出佇列（{@code IsoChunk.removeFromWorld} 先呼叫 {@code AnimalPopulationManager.removeChunkFromWorld}），不會走到這裡。
     */
    public static void leavingWorld(IsoAnimal animal) {
        Deferred d = ORDER.isEmpty() ? null : PENDING.get(animal);
        if (d == null || d.done) {
            return;
        }
        d.net = network(animal, new IdentityHashMap<>());
        for (DesignationZoneAnimal zone : d.net) {
            try {
                zone.check();
                zoneRefreshes++;
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        deferLeaving++;
        finish(d, System.nanoTime());
    }

    /**
     * {@code IsoFeedingTrough.addToWorld} 的兩個 {@code checkOverlayAfterAnimalEat()}（接收者是主槽，或副槽找到的主槽）：
     * 先登記回所在畜牧區（原版只在建構子與 {@code check()} 登記，卸載時 removeFromWorld 移除）。登記失敗只計數，不影響載入。
     */
    public static void troughAddedToWorld(IsoFeedingTrough trough) {
        if (TROUGH_REGISTER) {
            try {
                trough.checkZone();
            } catch (RuntimeException e) {
                anomalies++;
            }
        }
        trough.checkOverlayAfterAnimalEat();
    }

    /** MdcAnimalSave 讀取（存檔執行緒也會呼叫，所以只看動物自己的欄位）：延後中、凍結而尚未補算的動物。 */
    public static boolean awaitingCatchUp(IsoAnimal animal) {
        return DEFER && animal.fromMeta;
    }

    /** 一筆延後的 chunk 路徑補算：時數在延後當下依 W49 決定；clock 是當下的動物時鐘，補算從這裡逐小時推進。 */
    static final class Deferred {
        final IsoAnimal animal;
        final int hours;
        final int applied;
        final long animalHours;
        final long clock;
        final long since;
        List<DesignationZoneAnimal> net = List.of();
        boolean timedOut;
        boolean done;

        Deferred(IsoAnimal animal, int hours, int applied, long animalHours, long clock, long since) {
            this.animal = animal;
            this.hours = hours;
            this.applied = applied;
            this.animalHours = animalHours;
            this.clock = clock;
            this.since = since;
        }
    }

    /** 委派原版補算：發一次性票證給這隻動物（entryHours 據此放行），例外計數後原樣外拋。 */
    private static void delegate(IsoAnimal animal, int applied) {
        IsoAnimal previous = probeTicket;
        probeTicket = animal;
        try {
            animal.updateStatsAway(applied);
        } catch (RuntimeException e) {
            delegateFailures++;
            throw e;
        } finally {
            probeTicket = previous;
        }
    }

    /**
     * 連續補算的累計耗時：這一次開始距上一次結束小於 {@link #RUN_GAP_NS} 就接續同一段，否則重新起算。
     * 是相鄰補算的近似（通常對應同一幀的整批 fromWorker／doMeta），不是精確的幀邊界。
     */
    static void accumulateRun(long startNs, long endNs) {
        if (startNs - runLastEndNs > RUN_GAP_NS) {
            runNs = 0L;
            runCalls = 0L;
        }
        runLastEndNs = endNs;
        runNs += endNs - startNs;
        runCalls++;
        maxRunNs = Math.max(maxRunNs, runNs);
        maxRunCalls = Math.max(maxRunCalls, runCalls);
    }

    /**
     * 本次補算時數。W42：{@code min(zone, 自身)}。W49：chunk 路徑改用自身時數；超過上限時把時鐘設為
     * 「現在 − 上限」再補上限小時（較早的部分丟棄），迴圈跑完時鐘恰為現在。
     */
    static int plan(IsoAnimal animal, int hours, long animalHours, boolean chunk) {
        int w42 = CAP ? cap(hours, animalHours) : hours;
        if (!OWN) {
            return w42;
        }
        int want = w42;
        boolean own = chunk && animalHours != NO_RECORD && !animal.isWild();
        if (own) {
            want = (int) Math.max(0L, Math.min(animalHours, Integer.MAX_VALUE));
        }
        int applied = want;
        if (LIMIT > 0 && want > LIMIT) {
            limited++;
            limitedHours += want - LIMIT;
            if (animalHours != NO_RECORD && animalHours > LIMIT) {
                animal.timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis() - LIMIT * HOUR_MS;
            }
            applied = LIMIT;
        }
        if (own && applied > 0) {
            ownCatchUps++;
            ownGainHours += Math.max(0, applied - w42);
        }
        return applied;
    }

    /** 補算時數上限：無紀錄或非正時數沿用 vanilla；否則不超過動物自身離線時數（在未來＝0）。 */
    static int cap(int hours, long animalHours) {
        if (hours <= 0 || animalHours == NO_RECORD) {
            return hours;
        }
        return (int) Math.max(0L, Math.min(hours, animalHours));
    }

    /** 動物自身離線時數；{@link #NO_RECORD}＝無紀錄；負值＝時鐘在未來。 */
    static long animalHoursAway(IsoAnimal animal) {
        try {
            long last = animal.timeSinceLastUpdate;
            if (last <= 0L) {
                return NO_RECORD;
            }
            return Math.floorDiv(GameTime.getInstance().getCalender().getTimeInMillis() - last, HOUR_MS);
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            return NO_RECORD;
        }
    }

    private static void record(IsoAnimal animal, int hours, int applied, long animalHours, long ns, String source) {
        try {
            calls++;
            sumHours += Math.max(applied, 0);
            totalNs += ns;
            maxNs = Math.max(maxNs, ns);
            maxHours = Math.max(maxHours, hours);
            maxAnimalHours = Math.max(maxAnimalHours, animalHours);
            if (applied < hours) {
                capped++;
                cappedHours += hours - applied;
            }
            boolean dead = animal.isDead();
            if (dead) {
                died++;
            }
            if (hours >= DETAIL_HOURS) {
                big++;
            }
            boolean overshoot = animalHours != NO_RECORD && hours - animalHours >= OVERSHOOT_HOURS;
            if (overshoot) {
                mismatch++;
            }
            if (overshoot || dead) {
                detail(animal, hours, applied, animalHours, ns, dead, source);
            }
            long now = System.currentTimeMillis();
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                DebugLog.log(TAG + "calls=" + calls + " big=" + big + " mismatch=" + mismatch
                        + " capped=" + capped + " cappedHours=" + cappedHours + " clockRefresh=" + clockRefresh
                        + " hutchRefresh=" + hutchRefresh + " ownCatchUps=" + ownCatchUps
                        + " ownGainHours=" + ownGainHours + " limited=" + limited + " limitedHours=" + limitedHours
                        + " directCalls=" + directCalls + " directHours=" + directHours
                        + " directLimited=" + directLimited + " directLimitedHours=" + directLimitedHours
                        + " carcassSkips=" + carcassSkips + " accrualGrowths=" + accrualGrowths
                        + " delegateFailures=" + delegateFailures
                        + " died=" + died + " maxHours=" + maxHours + " maxAnimalHours=" + maxAnimalHours
                        + " sumAppliedHours=" + sumHours + " totalMs=" + totalNs / 1_000_000L
                        + " maxMs=" + maxNs / 1_000_000L + " maxRunMs=" + maxRunNs / 1_000_000L
                        + " maxRunCalls=" + maxRunCalls + " suppressed=" + suppressed
                        + " anomalies=" + anomalies + " cap=" + (CAP ? 1 : 0) + " ownClock=" + (OWN ? 1 : 0)
                        + " limit=" + LIMIT + " accrual=" + (ACCRUAL ? 1 : 0) + " carcassGuard=" + (CARCASS ? 1 : 0)
                        + " deferred=" + deferred + " deferDrained=" + deferDrained + " deferTimeouts=" + deferTimeouts
                        + " deferUnloaded=" + deferUnloaded + " deferDead=" + deferDead
                        + " deferZoneSkips=" + deferZoneSkips + " deferDirect=" + deferDirect
                        + " deferFailures=" + deferFailures + " deferLeaving=" + deferLeaving
                        + " deferTransferred=" + deferTransferred + " deferPending=" + PENDING.size()
                        + " maxDeferMs=" + maxDeferNs / 1_000_000L + " zoneRefreshes=" + zoneRefreshes
                        + " defer=" + (DEFER ? 1 : 0) + " troughRegister=" + (TROUGH_REGISTER ? 1 : 0));
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void detail(IsoAnimal animal, int hours, int applied, long animalHours, long ns, boolean dead,
            String source) {
        long now = System.currentTimeMillis();
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now;
            windowLines = 0;
        }
        if (++windowLines > WINDOW_LINES) {
            suppressed++;
            return;
        }
        DesignationZone z = animal.getZone();
        DebugLog.log(TAG + "source=" + source + " hoursAway=" + hours + " applied=" + applied
                + " animalHoursAway=" + (animalHours == NO_RECORD ? "none" : String.valueOf(animalHours))
                + " worldAgeHours=" + (int) GameTime.getInstance().getWorldAgeHours()
                + " ms=" + ns / 1_000_000L + " dead=" + (dead ? 1 : 0)
                + " animal=" + animal.getAnimalType() + "#" + animal.getAnimalID()
                + " pos=" + (int) animal.getX() + "," + (int) animal.getY() + "," + (int) animal.getZ()
                + (z == null ? " zone=null"
                        : " zone=" + z.getId() + " name=" + z.getName() + " hourLastSeen=" + z.hourLastSeen
                        + " rect=" + z.x + "," + z.y + "," + z.w + "x" + z.h
                        + " streamed=" + (z.streamed ? 1 : 0)));
    }

    // ---- 測試存取器 ----
    static boolean enabledForTest() { return ENABLED; }
    static boolean capForTest() { return CAP; }
    static boolean ownForTest() { return OWN; }
    static boolean accrualForTest() { return ACCRUAL; }
    static boolean carcassGuardForTest() { return CARCASS; }
    static long callsForTest() { return calls; }
    static long bigForTest() { return big; }
    static long mismatchForTest() { return mismatch; }
    static long cappedForTest() { return capped; }
    static long anomaliesForTest() { return anomalies; }
    static long limitedForTest() { return limited; }
    static long ownGainHoursForTest() { return ownGainHours; }
    static long carcassSkipsForTest() { return carcassSkips; }
    static long accrualGrowthsForTest() { return accrualGrowths; }
    static long directCallsForTest() { return directCalls; }
    static long directLimitedForTest() { return directLimited; }
    static long delegateFailuresForTest() { return delegateFailures; }
    static long maxRunNsForTest() { return maxRunNs; }
    static boolean deferForTest() { return DEFER; }
    static boolean troughRegisterForTest() { return TROUGH_REGISTER; }
    static long deferredForTest() { return deferred; }
    static long deferDrainedForTest() { return deferDrained; }
    static long deferTimeoutsForTest() { return deferTimeouts; }
    static long deferUnloadedForTest() { return deferUnloaded; }
    static long deferDeadForTest() { return deferDead; }
    static long deferZoneSkipsForTest() { return deferZoneSkips; }
    static long deferDirectForTest() { return deferDirect; }
    static long deferFailuresForTest() { return deferFailures; }
    static long deferLeavingForTest() { return deferLeaving; }
    static long deferTransferredForTest() { return deferTransferred; }
    static long zoneRefreshesForTest() { return zoneRefreshes; }
    static int pendingForTest() { return PENDING.size(); }

    private AnimalAwayProbe() {}
}
