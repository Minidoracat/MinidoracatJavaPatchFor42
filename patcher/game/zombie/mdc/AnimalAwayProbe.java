package zombie.mdc;

import zombie.GameTime;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.debug.DebugLog;
import zombie.iso.areas.DesignationZone;

/**
 * W32 動物離線補算觀測（2026-09-24；docs/patches.md 2au）＋W42 補算時數上限（2026-09-27；2be）
 * ＋W49 離線補算根治（2026-10-01；2bm）。
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
        // 必須在委派前讀：vanilla 會推進 timeSinceLastUpdate。
        long animalHours = ENABLED || CAP ? animalHoursAway(animal) : NO_RECORD;
        int applied = plan(animal, hours, animalHours, chunk);
        AnimalDeathLedger.noteCatchUp(animal, applied);
        long t0 = ENABLED ? System.nanoTime() : 0L;
        delegate(animal, applied);
        if (!ENABLED) {
            return;
        }
        long t1 = System.nanoTime();
        accumulateRun(t0, t1);
        record(animal, hours, applied, animalHours, t1 - t0, chunk ? "chunk" : "zone");
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
                        + " limit=" + LIMIT + " accrual=" + (ACCRUAL ? 1 : 0) + " carcassGuard=" + (CARCASS ? 1 : 0));
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

    private AnimalAwayProbe() {}
}
