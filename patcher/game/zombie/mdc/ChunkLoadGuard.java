package zombie.mdc;

import java.util.LinkedHashSet;
import java.util.Set;

import zombie.debug.DebugLog;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;
import zombie.vehicles.BaseVehicle;

/**
 * W6 地圖格載入捕手（2026-08-14 01:34:56 正式服全服假死實案，凍結 114 分鐘）。
 *
 * <p><b>事故</b>：主迴圈 frame 永久停在 {@code f:46186}，從 01:34:56 到 03:28 排程重啟為止。
 * 進程活著、Steam/Discord/網路執行緒照常，玩家能連進來但世界完全靜止
 * （01:48「484現在登不上去了」、01:59「還在線上的快登出吧」，期間 170 次斷線）。
 * <b>沒有任何人是為了救它而重啟的</b>——救場的是排程的 mod 更新重啟。
 *
 * <p><b>vanilla 缺陷</b>：{@code EngineEntityManager} 維護兩份平行結構——{@code entitySet}
 * （「登記過了嗎」）與 {@code entities}（每圈要走訪的陣列）。{@code addEntityInternal}
 * 開頭即 {@code entitySet.contains(entity)}，為真就 {@code athrow}：
 * <pre>
 * java.lang.IllegalArgumentException: Entity is already registered
 *   EngineEntityManager.addEntityInternal(:137) ← throw
 *   ... GameEntity.addToWorld(:527) → IsoObject.addToWorld(:4497)
 *   IsoChunk.doLoadGridsquare(:3973)
 *   ServerMap$ServerCell.RecalcAll2(:385) → Load2(:224) → preupdate(:969)
 *   GameServer.main(:972)
 * </pre>
 *
 * <p><b>為何是永久活鎖而非崩潰</b>：{@code GameServer.main} 攔住例外只印出來，
 * 但攔截點在迴圈最上方——這一圈剩下的工作（更新世界、處理封包、推進 frame）全數跳過，
 * 而那個地圖格<b>還留在待載入佇列裡</b>。下一圈同一個物件、同樣被拒絕，每 0.1 秒一次。
 * 這是活鎖，任何「進程掛掉就重啟」的保護都救不了。
 *
 * <p><b>非本專案 patch 所致</b>（javap 實證，不是靠時間相關性——log 只回溯到 7/29，
 * 而 entity patch 也是 7/29 上線，沒有乾淨的 pre-patch 基準線）：
 * {@code addEntityInternal} offset 0-8 是 {@code entitySet.contains}、offset 11-27 就
 * {@code athrow}，本專案的 {@link FastIdentityArrayRemoval} 改道在 <b>offset 38</b> 的
 * {@code entities.add}，位於 throw 之後、拋出時根本執行不到；{@code removeEntityInternal}
 * 由 offset 5 的 {@code entitySet.remove} 決定所有分支，我方改道的 {@code Array.removeValue}
 * 在 offset 29 而 <b>offset 32 是 {@code pop}</b>，回傳值被丟棄不可能影響判斷。
 * {@code entitySet} 全程未被碰過。同一條逐行相同的 stack 在 8/07 18:05 也發生過一次
 * （{@code fencing_01_57}）。
 *
 * <p><b>手術</b>：{@code doLoadGridsquare} 內共有<b>三</b>處 {@code addToWorld}，全部通往同一個
 * throw 點。<b>審查抓到的 blocking</b>：初版只擋了第一處，等於守衛對三分之二的觸發路徑失效。
 * 現況與理由如下——
 * <table>
 *   <tr><th>offset</th><th>site owner</th><th>迴圈</th><th>處置</th></tr>
 *   <tr><td>457</td><td>{@code BaseVehicle}</td><td>{@code vehicles}</td><td><b>刻意留 vanilla</b></td></tr>
 *   <tr><td>737</td><td>{@code IsoObject}</td><td>{@code square.getObjects()}</td><td>改道（兩次事故的兇手）</td></tr>
 *   <tr><td>947</td><td>{@code IsoMovingObject}</td><td>{@code getStaticMovingObjects()}</td><td>改道</td></tr>
 * </table>
 * {@code IsoMovingObject} <b>自己沒有宣告 {@code addToWorld}</b>（javap 計數 0），所以 offset 947
 * 派送到的是<b>同一個方法體</b>——包住它零額外語意風險，而該迴圈裝的是屍體
 * （{@code IsoDeadBody}，正式服 DeadBody id 已發到 287089），是很有可能的下一個兇手。
 * {@code BaseVehicle} 則不同：它<b>自己宣告 {@code addToWorld}</b> 且開頭就有
 * {@code addedToWorld} 旗標早退守衛（offset 0-26），方法體另含 parts／engine 掛載，
 * 包住它等於吞一個大得多的範圍。<b>它仍是一條活的凍結路徑</b>，此處是有意識的取捨，
 * 並由 SmokeCheck 把它的呼叫數釘在 1（新增第四處即建置失敗，強迫重新決定）。
 *
 * <p>堆疊形狀不變（[receiver] → void）。{@code Patcher.redirectDesc} 以 <b>site owner</b>
 * 組簽名，故兩處各需一個同名多載。
 *
 * <p><b>降級範圍取決於 runtime class，不是單一方法體</b>（codex 審查更正初版的核心論證）：
 * 改道點的 site owner 只是<b>靜態型別</b>，實際跑的是虛擬派送到的覆寫版本。已用 javap 確認
 * 至少三種形狀都存在——
 * <ul>
 *   <li>{@code IsoObject.addToWorld}：offset 0 就是 super（拋出點），後續的
 *       {@code createContainersFromSpriteProperties()}／{@code addItemsToProcessItems()}／
 *       {@code addObjectPoweredByGenerator} 都還沒執行。</li>
 *   <li>{@code IsoDeadBody.addToWorld}：super 在 offset 1，但<b>之後</b>還有
 *       {@code CorpseCount.corpseAdded}、{@code FliesSound.corpseAdded}、
 *       <b>{@code ObjectIDManager.addObject}</b>——跳過等於這具屍體不進 ID 登記表。
 *       而這正是 offset 947 那個 {@code getStaticMovingObjects()} 迴圈的主要內容。</li>
 *   <li>{@code IsoWorldInventoryObject.addToWorld}：{@code getProcessWorldItems().add()}
 *       在 super <b>之前</b>——「拋出時什麼都還沒跑」對這型<b>是假的</b>，
 *       守衛會吞掉一個部分完成的狀態。</li>
 *   <li>{@code IsoGenerator.addToWorld}：<b>完全不呼叫 super</b>，走不到拋出點。</li>
 * </ul>
 * 「會拋出正代表 entity 已登記、先前那次成功的 add 已做過這些步驟」這個等價性論證，
 * 只在 super-first 的形狀上成立。<b>這是有意識接受的 production 風險</b>：凍結 114 分鐘
 * 的代價遠大於單一物件的部分狀態，而診斷有 {@code class=} 欄位可事後辨識是哪一型。
 * 完整取捨與已知殘留記於 docs/patches.md 2r。
 *
 * <p><b>本刀是止血＋蒐證，不是治療</b>：重複登記怎麼產生的（推測與跨格建造物有關——
 * 兩次兇手分別是 {@code fencing_01_57} 與緊接在 {@code Fences_MetalFarmGate} sprite 警告
 * 之後的 {@code blends_natural_01_53}，且當下都有大量玩家建造活動）沒有重現條件，不強修。
 * 現況出事只拿得到 25 份一模一樣的 stack 然後靜音，連是哪一格都不知道；本 helper 的重點
 * 是留下方格座標與 sprite 名，累積幾次才可能判斷是否集中於特定建築。
 *
 * <p>旋鈕（不需重新部署）：{@code -Dmdc.chunkLoadGuard.enabled=false}＝完全等同 vanilla。
 */
public final class ChunkLoadGuard {

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("mdc.chunkLoadGuard.enabled", "true"));

    /** 完整明細額度；用完後改走心跳。每次一行，故給得比 CycleGuard 寬。 */
    private static final int MAX_REPORTS = 20;
    /** 相異方格集合的硬上限，避免病態情況下無界成長（只存格式化字串，不持有遊戲物件）。 */
    private static final int MAX_DISTINCT_SITES = 32;
    /** 損壞的方格不會自己痊癒——每次玩家經過都會再觸發，運維不能在第一天之後看不到它。 */
    private static final long HEARTBEAT_INTERVAL_NS = 600_000_000_000L;
    private static final int MAX_ANOMALY_TRACES = 3;

    // 觀測欄位刻意不同步。事故路徑是主迴圈（ServerMap.preupdate → Load2 → RecalcAll2），
    // 但 WorldStreamer 對 Convert／SoftReset job 會在自己的執行緒直接呼叫 doLoadGridsquare
    // （javap 實證：WorldStreamer offset 111-112；其餘 job 才排進 loadGridSquare 佇列交主迴圈），
    // 故不假設單執行緒。多執行緒下最壞是每執行緒各自吃一份 MAX_REPORTS 額度或計數略偏——
    // 全部欄位皆 primitive 或 String、無路徑會拋，後果良性，不值得付 Atomic 成本。
    // 全部觀測狀態一律在 REPORT_LOCK 下讀寫（codex 審查抓到的 blocking）。
    // 初版註解宣稱「共享欄位只有 primitive 或 String，最壞只是少報」——**那是錯的**：
    // distinctSites 是 LinkedHashSet，非 thread-safe。並行 add/size 沒有任何定義保證，
    // HashMap 家族在 resize 期間被併發改動可能讓內部鏈結成環而**空轉**——那正是本刀
    // 要防的凍結形態，等於守衛自己變成新的凍結源。而且延遲的 caught++ 寫入可覆蓋較新值，
    // 讓計數倒退、心跳的「本區間 +N」變成負數，不只是「下限」而已。
    // 成本論證也站不住：這整段只在例外已經拋出之後才執行，一次 fillInStackTrace 就是
    // 數微秒級，鎖的奈秒級成本在這條路徑上不可觀測。
    private static final Object REPORT_LOCK = new Object();
    private static long caught;
    private static long reports;
    private static long anomalies;
    private static long anomalyTraces;
    private static long diagFailures;      // 診斷取值自己失敗的次數（否則哨兵值一詞多義且靜默）
    private static long lastHeartbeatNs;
    private static long heartbeatCaught;
    private static boolean heartbeatPrimed;
    private static String firstSite;
    private static String lastSite;
    /** 只存格式化後的字串，不持有任何遊戲物件參照（生命週期契約與 W5 的 State 相同）。 */
    private static final Set<String> distinctSites = new LinkedHashSet<>();

    static {
        // 橫幅：沒有它，下次若從 BaseVehicle 那條未守衛的路徑凍結，運維只會看到
        // 「ChunkLoadGuard 已安裝但一行都沒印」，無法分辨「守衛沒蓋到這條路徑」與「守衛壞了」。
        // 同時印出 property 原值——`-D...=0`／`=no` 這類打錯會靜默保持啟用。
        //
        // **這不是「啟動橫幅」**（codex 審查更正）：helper 是被 patch 的 IsoChunk 在**第一次
        // 執行到受守衛的 callsite** 時才觸發載入的，單純載入 patched IsoChunk 不會初始化它；
        // 而且 vehicles 迴圈排在兩個 redirect 之前。所以它是「守衛首次生效」的證明，
        // 不是無條件的開機證明——看不到它只代表還沒有任何方格走過那兩個 callsite。
        try {
            DebugLog.log("[MinidoracatJavaPatch][ChunkLoadGuard] 首次生效 enabled=" + ENABLED
                    + "（property 原值=" + System.getProperty("mdc.chunkLoadGuard.enabled", "(未設定)")
                    + "；已守衛 IsoObject／IsoMovingObject 兩處，vehicles 路徑刻意未守衛）");
        } catch (Throwable t) {
            // 類別初始化不得因 log 失敗而炸掉（那會變成 NoClassDefFoundError），
            // 但 VM 級故障仍必須穿透——否則就與本檔「Error 必須致命且可見」的契約矛盾
            // （codex 審查抓到：此處原本連 OOM／SOE 都吞）。
            rethrowFatal(t);
        }
    }

    private ChunkLoadGuard() {}

    /**
     * {@code doLoadGridsquare} 內 {@code IsoObject.addToWorld()} 的改道目標。
     * 簽名與被取代的 {@code INVOKEVIRTUAL} 同形（receiver → void）。
     *
     * <p>只攔 {@link RuntimeException}：{@link Error}（OOM、StackOverflow、LinkageError）
     * 必須保持致命且可見，把 VM 級故障吞掉遠比凍結更糟。反過來也不只攔
     * {@code IllegalArgumentException}——凍結機制與例外型別無關，同一個位置換一種
     * RuntimeException 一樣會鎖死主迴圈 114 分鐘，守衛不該對下一個變種失效。
     */
    public static void addToWorld(IsoObject object) {
        if (!ENABLED) {
            object.addToWorld();          // 旋鈕停用：完全等同 vanilla（含原本的凍結行為）
            return;
        }
        try {
            object.addToWorld();
        } catch (RuntimeException e) {
            report(object, e);   // caught++ 在 report() 的鎖內做，避免無同步的讀-改-寫
        }
    }

    /**
     * {@code getStaticMovingObjects()} 迴圈那一處（offset 947）的改道目標。
     *
     * <p>需要獨立多載是因為 {@code Patcher.redirectDesc} 以 <b>site owner</b> 組簽名，
     * 而該 callsite 的 owner 是 {@code IsoMovingObject}。語意上它與上面完全相同——
     * {@code IsoMovingObject} 自己沒宣告 {@code addToWorld}，原本的 {@code INVOKEVIRTUAL}
     * 也是解析到 {@code IsoObject.addToWorld}，此處的虛擬派送重現同一目標。
     */
    public static void addToWorld(IsoMovingObject object) {
        // 宣告的範圍邊界必須由程式碼保證，不能靠「目前的呼叫者剛好不會這樣做」（審查抓到）。
        // BaseVehicle extends IsoMovingObject，而 getStaticMovingObjects() 不是型別同質的
        // （vanilla 自己在 getDeadBody()／getDeadBodys() 都要 instanceof IsoDeadBody 過濾），
        // 所以一旦有 vehicle 進到那個 list，就會經由本多載被吞掉——正是文件明文拒絕的那件事。
        if (object instanceof BaseVehicle) {
            object.addToWorld();
            return;
        }
        addToWorld((IsoObject) object);
    }

    /** 診斷；本身絕不能再拋，否則就把捕手變成新的凍結源。 */
    private static void report(IsoObject object, RuntimeException cause) {
        try {
            // 診斷取值在鎖**外**完成：這些是遊戲物件的 getter，在半初始化狀態下行為未知，
            // 持鎖呼叫它們等於把不可控的第三方程式碼拉進 critical section。
            // 去重鍵**只有座標**，明細才是全欄位——診斷含 identityHashCode，若拿完整明細
            // 當去重鍵，同一格的每個不同實例都會算成新方格，去重就失效了。
            String key = squareKey(object);
            String site = describe(object, key);
            String threadName = safeThreadName();

            // 共享狀態（含非 thread-safe 的 distinctSites）全部在鎖內；輸出用快照在鎖外做，
            // 免得 DebugLog 的 I/O 把鎖握住。
            String line = null;
            synchronized (REPORT_LOCK) {
                caught++;
                if (firstSite == null) {
                    firstSite = site;
                }
                lastSite = site;

                // 明細額度按**相異方格**計，不按事件計。損壞的方格每次玩家經過都會再觸發，
                // 若按事件計，同一格幾小時內就能把 20 格額度吃光；之後 B、C、D 格陸續損壞
                // 卻只會在 lastSite 互相覆蓋、座標永久遺失——而「是否集中於特定建築」正是
                // 本刀唯一想回答的問題，需要的是 20 格的證據而非一格的證據 ×20。
                boolean isNewSite = distinctSites.size() < MAX_DISTINCT_SITES && distinctSites.add(key);
                if (isNewSite && reports < MAX_REPORTS) {
                    reports++;
                    line = "[MinidoracatJavaPatch][ChunkLoadGuard] 已跳過載入失敗的物件"
                            + "（累計 " + caught + " 次｜相異方格 " + distinctSites.size()
                            + "｜thread=" + threadName + "）" + site + " ← " + cause
                            + "｜注意：該物件此次載入的後續掛載未執行（實際缺什麼取決於 runtime class）";
                } else {
                    long now = System.nanoTime();
                    // System.nanoTime() 原點任意、規格明文允許為負，所以 lastHeartbeatNs=0
                    // 當哨兵在負原點的 JVM 上會讓 (now - 0 >= 10 分鐘) 恆為假＝心跳一輩子
                    // 不印。W5 的 reportPrimed 已是正解，只是同檔心跳沒套用；兩邊一併改。
                    if (!heartbeatPrimed) {
                        heartbeatPrimed = true;
                        lastHeartbeatNs = now;
                        heartbeatCaught = caught;
                    } else if (now - lastHeartbeatNs >= HEARTBEAT_INTERVAL_NS
                            && caught != heartbeatCaught) {
                        long delta = caught - heartbeatCaught;   // 速率比累計值更能看出惡化
                        lastHeartbeatNs = now;
                        heartbeatCaught = caught;
                        line = "[MinidoracatJavaPatch][ChunkLoadGuard] 仍在發生（本區間 +" + delta
                                + "｜累計 " + caught + "｜相異方格 " + distinctSites.size()
                                + "｜診斷取值失敗 " + diagFailures + "｜anomalies " + anomalies
                                + "）首例=" + firstSite + " 最近=" + lastSite;
                    }
                }
            }
            if (line != null) {
                DebugLog.log(line);
            }
        } catch (Throwable t) {
            rethrowFatal(t);
            anomalies++;
            // 審查抓到的 blocking：若剛才拋出的正是 DebugLog.log 本身，用「再 log 一次」回應
            // 就會讓例外逃出 report()、逃出上面的 catch(RuntimeException)、回到 doLoadGridsquare
            // ——把捕手本身變成新的凍結源。診斷失敗絕不得升級為凍結，故整段自帶最後一道網。
            if (anomalyTraces < MAX_ANOMALY_TRACES) {
                anomalyTraces++;
                try {
                    DebugLog.log("[MinidoracatJavaPatch][ChunkLoadGuard] anomaly #" + anomalies + ": " + t);
                    for (StackTraceElement e : t.getStackTrace()) {
                        DebugLog.log("[MinidoracatJavaPatch][ChunkLoadGuard]     at " + e);
                    }
                } catch (Throwable ignored) {
                    rethrowFatal(ignored);
                    // DebugLog 整條壞掉時本刀會完全失明（額度被無聲扣光、心跳全滅），
                    // 而 stderr 是獨立通道且 LinuxGSM console log 抓得到——用它把
                    // 「完全看不見」降級成「看得見但簡陋」。它自己再失敗就真的放棄。
                    try {
                        System.err.println("[MinidoracatJavaPatch][ChunkLoadGuard] DebugLog 不可用，"
                                + "caught=" + caught + " anomalies=" + anomalies);
                    } catch (Throwable giveUp) {
                        rethrowFatal(giveUp);
                    }
                }
            }
        }
    }

    /**
     * 案發座標與物件識別——這是目前完全沒有的東西（vanilla 只給 25 份相同 stack 然後靜音）。
     * 每個取值都獨立防禦：物件正處於半初始化狀態，任何 getter 都可能再爆。
     *
     * <p>哨兵值刻意逐種區分（審查抓到）：凌晨三點看到 200 行 {@code 方格=null sprite=?} 的人
     * 必須能分辨「這些物件本來就怪」與「每次取值都在爆、我們什麼都沒蒐到」。
     */
    private static String describe(IsoObject object, String squareKey) {
        if (object == null) {
            // 與本案無關、可能更嚴重的另一種損壞：方格的物件清單裡有 null 項。
            // 語氣必須不同，否則會被當成同一個 bug 的第 N 次。
            return " 世界資料異常：方格物件清單含 null 項（非 already-registered）";
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append(" 方格=").append(squareKey);
        sb.append(" sprite=").append(safeSpriteName(object));
        sb.append(" class=").append(safeClassName(object));
        // 決定性欄位（審查指出，且 LineNumberTable 已證明事故走直通分支）：
        //   addedToEngine=true  → 引擎狀態完全自洽，就是單純對已在世界裡的物件再 add 一次，
        //                         接下來查「誰重複呼叫 addToWorld／哪個物件同時在兩個 list」
        //   addedToEngine=false → 才是真的不變量破壞；entitySet.add 與 addedToEngine=true 之間
        //                         唯一會拋的是 setComponentOperationHandler，搜尋範圍縮到一個方法
        // 兩者的後續調查方向完全不同，沒有這一欄就分不出來。
        sb.append(" addedToEngine=").append(safeAddedToEngine(object));
        // 同一個兇手一直拋，還是一堆不同物件（系統性）——identity 是唯一分得出來的東西
        sb.append(" id=").append(Integer.toHexString(System.identityHashCode(object)));
        // SoftReset job 對活著的物件重跑 doLoadGridsquare 是目前最具體、最可驗的根因假說
        // （doLoadGridsquare 自己就有 SoftReset 專屬分支）。
        // 命名刻意是 objectChunkJob 而非 job（codex 審查）：讀的是**該物件自己的 chunk**，
        // 不是正在執行載入的那個 IsoChunk。若物件掛在錯誤的 square/list 上（本案的可能形態
        // 之一），讀到的會是另一個 chunk 的 job——所以它是 best-effort 線索，不是權威值。
        sb.append(" objectChunkJob=").append(safeJobType(object));
        return sb.toString();
    }

    private static String safeAddedToEngine(IsoObject object) {
        try {
            return String.valueOf(object.isAddedToEngine());
        } catch (Throwable t) {
            rethrowFatal(t);
            diagFailures++;
            return "getter-threw";
        }
    }

    private static String safeJobType(IsoObject object) {
        try {
            IsoChunk chunk = object.getChunk();
            if (chunk == null) {
                return "no-chunk";
            }
            IsoChunk.JobType job = chunk.jobType;
            return job == null ? "null" : job.toString();
        } catch (Throwable t) {
            rethrowFatal(t);
            diagFailures++;
            return "getter-threw";
        }
    }

    /**
     * 去重鍵：只有座標。三軸全成功才印——逐軸退化會讓 {@code 7130,-2147483648,0}
     * 看起來像有個真 X，運維會拿它去對地圖。
     */
    private static String squareKey(IsoObject object) {
        if (object == null) {
            return "null-object";
        }
        IsoGridSquare square;
        try {
            square = object.getSquare();
        } catch (Throwable t) {
            rethrowFatal(t);
            diagFailures++;
            return "getter-threw";
        }
        if (square == null) {
            return "none";
        }
        try {
            return square.getX() + "," + square.getY() + "," + square.getZ();
        } catch (Throwable t) {
            rethrowFatal(t);
            diagFailures++;
            return "partial(getter-threw)";
        }
    }

    private static String safeSpriteName(IsoObject object) {
        try {
            IsoSprite sprite = object.getSprite();
            if (sprite == null) {
                return "null-sprite";
            }
            String name = sprite.getName();
            return name == null ? "unnamed" : name;
        } catch (Throwable t) {
            rethrowFatal(t);
            diagFailures++;
            return "getter-threw";
        }
    }

    /**
     * {@code getClass().getSimpleName()} 對 inner／anonymous class 會讀 InnerClasses attribute，
     * 可拋 {@code NoClassDefFoundError}／{@code IncompatibleClassChangeError}——對一個改寫
     * bytecode 的專案，那正是最該防的一類。初版是 {@code describe()} 裡唯一裸奔的取值，
     * 且在最後一行：它一拋，前面已成功取得的座標與 sprite 名會連同整個 site 一起丟掉。
     */
    private static String safeClassName(IsoObject object) {
        try {
            return object.getClass().getSimpleName();
        } catch (Throwable t) {
            rethrowFatal(t);
            diagFailures++;
            return "getter-threw";
        }
    }

    /**
     * 「這次是 WorldStreamer 背景執行緒（本來就不會凍主迴圈）還是主迴圈（本來會凍 114 分鐘）」
     * 是本刀最有運維價值的一個 bit，而註解花了五行說明有兩個執行緒卻沒印出來。
     */
    private static String safeThreadName() {
        try {
            return Thread.currentThread().getName();
        } catch (Throwable t) {
            rethrowFatal(t);
            return "?";
        }
    }

    /**
     * <b>只重拋 {@link VirtualMachineError}</b>（審查抓到）。這個方法只用在<b>診斷路徑</b>，
     * 而診斷路徑撞到的 {@code LinkageError}（例如 {@code getSimpleName()} 讀不到 InnerClasses）
     * 100% 是診斷子系統的缺陷，不是「世界不安全、不可續跑」的訊號——在已經決定放棄診斷的
     * 那一行把它升級成凍結，正好是這道網存在的理由的反面。堆真的爆了（OOM／SOE）則必須穿透。
     *
     * <p>主路徑（{@link #addToWorld}）刻意<b>不</b>呼叫本方法：它用 {@code catch (RuntimeException)}，
     * 於是所有 {@code Error} 都自然穿透。兩條路徑對 {@code AssertionError} 的處置因此不同，
     * 這是有意的——「遊戲自己的操作失敗」與「我們的 log 失敗」契約本來就不同。
     */
    private static void rethrowFatal(Throwable t) {
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
    }

    // ---- 測試掛點（package-private；production 不呼叫）----
    static void resetForTest() {
        caught = 0;
        reports = 0;
        anomalies = 0;
        anomalyTraces = 0;
        diagFailures = 0;
        lastHeartbeatNs = 0;
        heartbeatCaught = 0;
        heartbeatPrimed = false;
        firstSite = null;
        lastSite = null;
        distinctSites.clear();
    }

    /** caught, reports, anomalies, diagFailures, distinctSites */
    static long[] statsForTest() {
        return new long[]{caught, reports, anomalies, diagFailures, distinctSites.size()};
    }

    static boolean enabledForTest() {
        return ENABLED;
    }

    static String firstSiteForTest() {
        return firstSite;
    }

    static int maxReportsForTest() {
        return MAX_REPORTS;
    }

    /** 測試用：把心跳的 primed 時間往回撥，讓下一次呼叫必定越過間隔。 */
    static void expireHeartbeatForTest() {
        heartbeatPrimed = true;
        lastHeartbeatNs = System.nanoTime() - HEARTBEAT_INTERVAL_NS - 1L;
    }
}
