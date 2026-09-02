import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Opcodes;

/**
 * 各 class 的手術座標（依 work/specs/*.json 分析定案，逐項含 javap 證據；總表見 docs/patches.md）。
 * 命中數守門：任何 build 漂移（呼叫點增減、descriptor 變更）都會讓建置失敗而非默默出錯。
 */
public final class PatchConfig {

    private static final String DT = "zombie/debug/DebugType";
    private static final String WARN_FMT = "(Ljava/lang/String;[Ljava/lang/Object;)V";
    private static final String WARN_OBJ = "(Ljava/lang/Object;)V";
    private static final String DL = "zombie/debug/DebugLog";
    private static final String LOG_STR = "(Ljava/lang/String;)V";
    private static final String DL_TYPE_STR = "(Lzombie/debug/DebugType;Ljava/lang/String;)V";
    private static final String ENTITY_ARRAY = "zombie/entity/util/Array";
    private static final String FAST_ARRAY_REMOVAL = "zombie/mdc/FastIdentityArrayRemoval";

    public static List<Patcher.ClassPatch> all() {
        List<Patcher.ClassPatch> patches = new ArrayList<>();

        // ---- 抑噪（redirect 到 zombie/mdc/LogFilter，過濾樣式在該類）----

        // 42.20 移除：ActionStateContainer.tryInsertChildState 的兩個 warn 被 TIS 自己降級為 trace
        // （全 class warn 8→6、trace 1→3），噪音源已由官方修掉，不需要我方 patch。

        Patcher.ClassPatch anim = new Patcher.ClassPatch("zombie/core/skinnedmodel/advancedanimation/AnimationSet");
        Patcher.MethodOps animM = anim.method("GetState",
                "(Ljava/lang/String;)Lzombie/core/skinnedmodel/advancedanimation/AnimState;");
        animM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, DT, "warn", WARN_FMT, "warnFmt"));
        animM.expectedHits = 1;
        patches.add(anim);

        Patcher.ClassPatch bone = new Patcher.ClassPatch("zombie/core/skinnedmodel/model/SkinningBoneHierarchy");
        Patcher.MethodOps boneM = bone.method("buildBoneHierarchy",
                "(Lzombie/core/skinnedmodel/model/SkinningData;)V");
        boneM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, DT, "warn", WARN_FMT, "warnFmt"));
        boneM.expectedHits = 1;
        patches.add(bone);

        Patcher.ClassPatch sprite = new Patcher.ClassPatch("zombie/entity/components/spriteconfig/SpriteConfig");
        Patcher.MethodOps spriteM = sprite.method("initObjectInfo", "()V");
        spriteM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, DT, "warn", WARN_OBJ, "warnObj"));
        spriteM.expectedHits = 1;
        patches.add(sprite);

        Patcher.ClassPatch pick = new Patcher.ClassPatch("zombie/inventory/ItemPickInfo");
        Patcher.MethodOps pickM = pick.method("GetPickInfo",
                "(Lzombie/inventory/ItemContainer;Lzombie/inventory/ItemPickInfo$Caller;)Lzombie/inventory/ItemPickInfo;");
        pickM.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, DL, "log", LOG_STR, "log"));
        pickM.expectedHits = 9;
        patches.add(pick);

        Patcher.ClassPatch nzm = new Patcher.ClassPatch("zombie/popman/NetworkZombieManager");
        Patcher.MethodOps nzmM = nzm.method("moveZombie",
                "(Lzombie/characters/IsoZombie;Lzombie/core/raknet/UdpConnection;Lzombie/characters/IsoPlayer;)V");
        nzmM.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, DL, "log", LOG_STR, "log"));
        nzmM.expectedHits = 1;

        // 抑噪 #9（2026-09-02 巡檢）：`ERROR: IsoThumpable not found on square x,y,z` 是
        // IsoObject.syncIsoObject 在 getObjectIndex()==-1 時的 System.out.println——B42 建造
        // 流程每次 `ISBuildIsoEntity -> consume success` 後對已被替換的 IsoThumpable 送 sync
        // 必印一行；正式服 8/30–9/2 四天 11,567 行（≈120/h ≫ 入列門檻 4/h）。方法內兩處
        // println(String)（offset 22 "square is null"、offset 70 "not found on square"）同方法
        // 改道，helper 只攔 IsoThumpable 的 not-found 前綴（其他 class 的 not-found 與
        // square-is-null 是破損訊號，照常印）。訊息由 invokedynamic 組成 → startsWith。
        Patcher.ClassPatch isoObj = new Patcher.ClassPatch("zombie/iso/IsoObject");
        Patcher.MethodOps syncIso = isoObj.method("syncIsoObject",
                "(ZBLzombie/core/raknet/UdpConnection;Lzombie/core/network/ByteBufferReader;)V");
        syncIso.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                LOG_STR, "println"));
        syncIso.expectedHits = 2;
        patches.add(isoObj);
        patches.add(nzm);

        Patcher.ClassPatch cache = new Patcher.ClassPatch("zombie/network/PacketsCache");
        Patcher.MethodOps cacheM = cache.method("<init>", "()V");
        cacheM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, DT, "warn", WARN_OBJ, "warnObj"));
        cacheM.expectedHits = 1;
        patches.add(cache);

        // 42.20：consistency log 從 PacketType.onServerPacket 抽到 INetworkPacket.logInconsistentPacket
        // （interface default method，訊息文字未變）。改道那唯一一個 warn，onServerPacket 內剩下的
        // 反作弊 `The packet %s is not valid` 從此完全不經我方程式碼——比舊版「改道後再放行」更保守。
        // PlayerHitZombiePacket 的 override 只多一層前置過濾，最後仍呼叫 super，兩條路徑都涵蓋。
        Patcher.ClassPatch pkt = new Patcher.ClassPatch("zombie/network/packets/INetworkPacket");
        Patcher.MethodOps pktM = pkt.method("logInconsistentPacket",
                "(Lzombie/network/IConnection;Lzombie/network/PacketTypes$PacketType;)V");
        pktM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, DT, "warn", WARN_FMT, "warnFmt"));
        pktM.expectedHits = 1;
        patches.add(pkt);

        // 2026-07-29 停用：SafehouseClaimPacket room/building 綁定修復。
        // 原症狀「B42.19 擴充大型 Map= 後 isConsistent > building not found」是**自訂地圖造成**的；
        // 正式服已改為只保留原版 Muldraugh, KY，觸發條件消失。加上 42.20 的 IsoGridSquare 改了 620 行，
        // 無法確認 TIS 是否已修掉根因——在沒有可觀測症狀的情況下，不讓 patch 介入安全屋驗證路徑。
        // 恢復方式：解除本段註解即可（LogFilter 的 getBuilding/canBeSafehouse helper 保留在原處，
        // 仍隨 build 對遊戲 jar 編譯驗證，不會因閒置而腐爛）。
        // 座標已於 42.20 驗證仍有效：isConsistent 的 IsoGridSquare.getBuilding ×1、
        // processServer 的 SafeHouse.canBeSafehouse ×1（注意 SafeHouse 在 42.20 起 extends Invite，
        // 但 static canBeSafehouse 簽名未變）。

        // B42.19 定期刷新只認三種 vanilla Zone，且 construction 是黏性的 Zone 級旗標：
        // 只對含未搬動原生固定容器的垂直欄位放寬 gate；安全屋與其他原版條件完整保留
        Patcher.ClassPatch lootRespawn = new Patcher.ClassPatch("zombie/LootRespawn");
        Patcher.MethodOps lootRespawnChunk = lootRespawn.method("respawnInChunk", "(Lzombie/iso/IsoChunk;)V");
        lootRespawnChunk.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoGridSquare", "getZone",
                "()Lzombie/iso/zones/Zone;", "getLootRespawnZone"));
        lootRespawnChunk.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoObject", "getContainerCount",
                "()I", "getLootRespawnContainerCount"));
        lootRespawnChunk.expectedHits = 2;
        patches.add(lootRespawn);

        // 退役（2026-09-02）：登入／join 卡頓量測（LoginPacket 三個同步 DB 寫入、
        // CreatePlayerPacket 尾段四個重活、Connect/ConnectCoopPacket 與 GameServer
        // .receivePlayerConnect 的 REJOIN 兩層）。join 卡頓歸因任務已完成，正式服
        // REJOIN_TOTAL 常態 5–13ms＝已無待答問題。詳見 docs/patches.md 2i；
        // 復活方式：從退役前最後一版 2fda295 取回（`git checkout 2fda295 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

        Patcher.ClassPatch gameServer = new Patcher.ClassPatch("zombie/network/GameServer");

        // 抑噪：`Send Toxic Building at [ x , y Toxic: b ]` 佔正式服 console 34.4%
        // （2026-08-16 實測 9512/27682 行／57 分鐘）。來源不是 vanilla——全服 77 個 mod 只有
        // PSR 呼叫 IsoBuilding.setToxic，其 PBSystem.suppressToxic 掛 Events.EveryOneMinute
        // （Day Length=1h → 每 2.5 真實秒）逐 powerbank 無條件 setToxic(false)，而
        // IsoBuilding.setToxic 的 putfield 沒有變更比對，每次都真的走到這個方法。
        // 訊息由 invokedynamic makeConcatWithConstants 組成（座標與 boolean 是變數）→ startsWith。
        // 只攔 log，封包一個不少：**不可**在 server 側做狀態去重——client 的
        // WorldRegionToMetaGrid.lambda$updateSquares$0（只在 IsoRegions.update 的 !GameServer.server
        // 分支執行）會自己把建築標成 toxic=true 且不回報 server，server 的 isToxic 只是「上次送了什麼」
        // 的殘影；去重等於把玩家鎖在會扣血的毒氣室（PSR 作者 v1.39→v1.40 已實證）。
        // 必須 method-scoped：GameServer 全 class 有 21 個 DebugLog.log(DebugType,String) 呼叫點，
        // 本方法內只有一個（offset 11）。
        // 代價：server console 少了一條「client 還在收廣播」的 liveness 訊號；該訊號在玩家端的
        // GameClient.receiveToxicBuilding（`Receive Toxic Building at [ `）仍在，本 patch 不動 client jar。
        Patcher.MethodOps toxicLog = gameServer.method("sendToxicBuilding", "(IIZ)V");
        toxicLog.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, DL, "log", DL_TYPE_STR, "logType"));
        toxicLog.expectedHits = 1;
        patches.add(gameServer);

        // 大量 chunk unload 會逐 entity 從 Engine 全域陣列與各 bucket 做 identity 線性搜尋；
        // 四個精確 add/remove callsite 改道至 primitive sidecar index，生命週期與 callback 順序不動
        Patcher.ClassPatch entityManager = new Patcher.ClassPatch("zombie/entity/EngineEntityManager");
        Patcher.MethodOps entityAdd = entityManager.method("addEntityInternal",
                "(Lzombie/entity/GameEntity;)V");
        entityAdd.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, ENTITY_ARRAY, "add",
                "(Ljava/lang/Object;)V", FAST_ARRAY_REMOVAL, "add"));
        entityAdd.expectedHits = 1;
        Patcher.MethodOps entityRemove = entityManager.method("removeEntityInternal",
                "(Lzombie/entity/GameEntity;)V");
        entityRemove.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, ENTITY_ARRAY, "removeValue",
                "(Ljava/lang/Object;Z)Z", FAST_ARRAY_REMOVAL, "remove"));
        entityRemove.expectedHits = 1;
        patches.add(entityManager);

        Patcher.ClassPatch entityBucket = new Patcher.ClassPatch("zombie/entity/EntityBucket");
        Patcher.MethodOps bucketMembership = entityBucket.method("updateMembership",
                "(Lzombie/entity/GameEntity;)V");
        bucketMembership.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, ENTITY_ARRAY, "add",
                "(Ljava/lang/Object;)V", FAST_ARRAY_REMOVAL, "add"));
        bucketMembership.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, ENTITY_ARRAY, "removeValue",
                "(Ljava/lang/Object;Z)Z", FAST_ARRAY_REMOVAL, "remove"));
        bucketMembership.expectedHits = 2;
        patches.add(entityBucket);

        // 退役（2026-09-02）：食材重量記憶化（InventoryItem.getExtraItemsWeight 的
        // CreateItem 改道）。observe 實測收益僅 0.06–0.18%，「永不啟用 on」已定案——
        // W3-2 的教訓是「只有量測證明有收益」，這次量測的答案是沒有。
        // 詳見 docs/patches.md 2w；復活方式：從退役前最後一版 2fda295 取回（`git checkout 2fda295 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

        // ---- 行為（method 範圍內常數替換；ClassWriter 產新常數池條目，不動共享條目）----

        // 42.20 移除，重新分析後定案**不恢復**（完整論證見 docs/patches.md 2a）：
        // 新模型的 culling 掃描來源是 per-connection zombiesToSend（只收「有主且主人非本
        // 連線」的殭屍）——無主殭屍永遠不進列表，而它們正是當初做此手術的理由（大世界
        // 記憶體壓力主源）。在新模型加速取樣只會多刪一小撮受雙倍保護半徑＋額度上限管制
        // 的有主殭屍，與目標脫鉤。殭屍堆積要處理得換切入點，不是這個常數。
        // TIS 重寫了 ZombieCountOptimiser——`startCount()`＋`incrementZombie()` 併成
        // `prepareZombiesForDeletion()V`，判定基準從「全域殭屍總數」改為 per-connection 的
        // `zombiesToSend` 列表，且每個連線帶自己的 `zombiesCountForDelete` 遞減額度；
        // `canBeDeletedUnnoticed` 多收 UdpConnection 參數，安全距離從 `(range-2)*10/2`
        // 放寬成 `(range-2)*10`（判定半徑 ×2＝更不容易刪）。常數 10 仍在新方法內，但整個
        // 壓力模型已不同，沿用舊結論等於沒有依據——重新分析後再決定是否恢復。

        // 動物壓力三調：閒置衰減 x2、聲音壓力 /3、屠宰連鎖上限減半（clamp 與行為路徑全保留）
        Patcher.ClassPatch animal = new Patcher.ClassPatch("zombie/characters/animals/IsoAnimal");
        Patcher.MethodOps a1 = animal.method("updateStress", "()V");
        a1.consts.add(new Patcher.ConstChange(5500.0f, 2750.0f));
        a1.expectedHits = 1;
        // 42.20 改寫：`changeStress(sound.radius / 20.0F)` -> `changeStress(sound.radius * 0.05F)`
        // （數學等價，但常數池條目換成乘數）。同時新增 wild 分支的
        // `fleeDistance = sound.radius * 3.0F + 20.0F`——方法內因此**仍剛好有一個 20.0f**，
        // 沿用舊座標會通過命中數守門卻改到逃跑距離。改乘數才是原本的「聲音壓力 ÷3」。
        Patcher.MethodOps a2 = animal.method("respondToSound", "()V");
        a2.consts.add(new Patcher.ConstChange(0.05f, 1.0f / 60.0f));
        a2.expectedHits = 1;
        Patcher.MethodOps a3 = animal.method("killed", "(Lzombie/characters/IsoPlayer;)V");
        a3.consts.add(new Patcher.ConstChange(30.0f, 15.0f));
        a3.expectedHits = 1;
        // W3-3（2026-08-05 效能第三波，三稜鏡對抗審查定稿）：updateLOS 對同層所有移動物件
        // （殭屍數千）無水平距離過濾直接呼叫 behavior.spotted()——spotted() 全部持久效果的
        // 距離門檻 ≤ spottingDist(≈10)，遠距呼叫在 vanilla 中除「spottedChr=null＋lastAlerted
        // 衰減」無條件前綴外零效果（審查逐行證偽；接受共享 RNG 流分歧，MP 無決定性依賴）。
        // 兩處呼叫點（殭屍分支＋玩家分支）改道距離預過濾 helper（前綴逐句重放＋每呼叫 live 讀
        // spottingDist）。IsoAnimal.spotted 轉發方法內的第三處**不動**——外部另有
        // IsoPlayer.TestAnimalSpotPlayer 以 bForced=false＋Manhattan 距離經其進入（流量可忽略）。
        // 去虛擬化前提由 SmokeCheck 全 jar walk 把關：BaseAnimalBehavior 後代零 spotted 覆寫。
        Patcher.MethodOps a4 = animal.method("updateLOS", "()V");
        a4.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/characters/animals/behavior/BaseAnimalBehavior", "spotted",
                "(Lzombie/iso/IsoMovingObject;ZF)V",
                "zombie/characters/animals/behavior/AnimalSpottedPrefilter", "spotted"));
        a4.expectedHits = 2;
        // W18 動物 LOS 節流閘（2026-08-25，60 張 jcmd 採樣@66人晚峰：updateLOS 單一 leaf 41.7%
        // 主執行緒；docs/patches.md 2af、docs/animal-los-gate-design-v1.md；前案佐證
        // docs/isoanimal-updatelos-design-v1.md 40人層 18.3%＋server-only 七呼叫點表）：每動物
        // 每 tick 掃 getCell().getObjectList() 全表（Set）只為找 zombie/player 呼叫 spotted()。
        // caller 側 updateInternal 內唯一 updateLOS callsite（offset 197）改道 AnimalLosGate——
        // observe 量化（size 分布＋耗時採樣）、enforce 以 vanilla frameCounter 輪轉每動物每
        // N tick 掃一次（grok 審查修正：v1 草案 nanoTime 窗口在低 fps 有 gcd 剩餘類永久失明；
        // Δframe=1 是 server⇒FULL⇒frameMod=1 的條件性事實，SmokeCheck 承重前提釘＋helper
        // gateApplies fail-open 雙保險，見 patches.md 2af）。行為代價=spotted 速率 ×1/N＋首偵
        // 延遲 ≤(N-1) tick，故預設 N=2 保守出貨（-Dmdc.animalLosN 1..16 可調）。動物版
        // spottedList 恆 {this} 零 server 消費者（skip 零差）；聽覺 respondToSound 不經 LOS。
        // 三態 -Dmdc.animalLosGate（0|off / 1|enforce / 2|observe 預設，parseMode 文字別名）。
        Patcher.MethodOps a5 = animal.method("updateInternal", "()V");
        a5.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/characters/animals/IsoAnimal", "updateLOS", "()V",
                "zombie/mdc/AnimalLosGate", "updateLOS"));
        a5.expectedHits = 1;
        patches.add(animal);

        // 42.20.2 官方收編，退役：popman buffer 隔離 v3（fieldGetSwap ×10＋count-clamp）。
        // 官方新增 readByteBuffer = allocateDirect(1024) 專用讀 buffer，updateMain 全部 10 處
        // 讀側 getfield 換用之、n_getAddZombieData 亦改收——正規化後指令序列與我方 v3 完全
        // 同構（僅欄位替換），寫側 byteBuffer 不動。讀寫共用的 position 併發根因已由官方根治，
        // clamp 保險絲失去防護對象。原根因鏈與 v1-v3 演進全文見 docs/patches.md 2h。

        // ---- 效能第一波（2026-08-02，66 份低谷 thread dump 聚合定案，Claude/codex 雙審一致）----

        // 殭屍載具視線 broad-phase 預篩：isVehicleBetween 對整個 cell 每台載具做完整 OBB 相交
        // （每台 2 次矩陣求逆＋6 次向量池借還，dump 佔比 ~23% 最大宗）。改道唯一的
        // getIntersectPoint 呼叫點到保守包圍球預篩——球外幾何上不可能相交直接回 null
        // （呼叫端只判非 null，零語意差），球內或異常原樣委派。per-vehicle 動態半徑
        // （extents/COM L1 上界＋膨脹，零 false-negative），不動 CombatManager 等其他呼叫者。
        Patcher.ClassPatch zombieVeh = new Patcher.ClassPatch("zombie/characters/IsoZombie");
        Patcher.MethodOps zvb = zombieVeh.method("isVehicleBetween", "(FFF)Z");
        zvb.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/vehicles/BaseVehicle",
                "getIntersectPoint", "(Lorg/joml/Vector3f;Lorg/joml/Vector3f;Lorg/joml/Vector3f;)Lorg/joml/Vector3f;",
                "zombie/mdc/VehicleIntersectPrefilter", "getIntersectPoint"));
        zvb.expectedHits = 1;
        patches.add(zombieVeh);

        // 42.20.2 官方收編，退役：VehicleManager.connected 512→256。官方直接刪除 connected[512]
        // 與 BaseVehicle.connectionState[512] 雙陣列，serverUpdate 的 512-slot 掃描迴圈整段移除，
        // 改為 per-connection 的 UdpConnection.vehicleStates HashMap（vehicleId 為 key、惰性建立）
        // ——比我方砍半更徹底。sipush 512 座標已不存在。原分析見 docs/patches.md 2k-2。

        // ---- 效能第二波 P5：42.20.2 官方收編，全家族 15 站退役（2026-08-06 四家族覆核定案）----
        // 官方在 IsoCell 原生實作了我方 sidecar 設計：新增 processIsoObjectSet 與
        // staticUpdaterObjectSet 伴生 HashSet、processIsoObjectRemove 直接 ArrayList→HashSet、
        // 新增 removeFromStaticUpdaterObjectList() 公開 API。所有熱路徑（卸載側 contains miss、
        // 載入側 remove+contains、S 清單去重、IsoObject.removeFromWorld miss、IsoDeadBody 經
        // getter 的旁路變異）全數 O(1)；ProcessIsoObject 另加 isEmpty 快速路徑（常態 R=0 零成本，
        // 優於我方 O(P+R)）。ProcessStaticUpdaters 的 n--/size-- 補償迭代逐指令保留。
        // 15 站中 14 站的 ArrayList 呼叫已改為 Set INVOKEINTERFACE（座標消失），唯一殘存的
        // ArrayList.removeAll 參數已是 HashSet——「命中數對但語境變了」的教科書案例。
        // 官方版與我方版差異（記錄在案）：equals-based Set 而非 identity（現行等價，IsoObject
        // 階層零 equals 覆寫）、無 audit/kill 保險絲、裸 getter 仍外洩（風險轉為官方所有）。
        // 設計全文 docs/p5-chunk-unload-design-v2.md、覆核證據 docs/patches.md 2m。

        // ---- 假死修復（2026-08-02 事故：SmashWindowPacket → removeGlassAttachments 無限迴圈）----

        // 原版迴圈假設 RemoveTileObject 必使清單縮短而無條件 n--；42.20 safelyRemove 路徑
        // 特定物件移除不生效 → 同 index 重撞同物件 → 主執行緒死鎖全服假死（兩份 thread dump
        // ＋pkill -9 恢復實證）。改道唯一呼叫點到 GlassAttachmentGuard：逐語意重刻，
        // 「清單真的縮短」才回退 index，否則跳過＋log 座標與 sprite 名（定位問題物件）。
        Patcher.ClassPatch window = new Patcher.ClassPatch("zombie/iso/objects/IsoWindow");
        Patcher.MethodOps smash = window.method("smashWindow", "(ZZ)V");
        smash.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoGridSquare",
                "removeGlassAttachments", "(Lzombie/iso/objects/IsoWindow;)V",
                "zombie/mdc/GlassAttachmentGuard", "removeGlassAttachments"));
        smash.expectedHits = 1;
        patches.add(window);

        // ---- 防崩潰頭部守衛（codex 對抗審查定案：guard-before-super、最小頭部插入）----

        // hit 封包 stale/type-confused reference：getZombie()=tryCastTo 可回 null，原版 9 個 setter 無檢查；
        // 守衛必須在 super.process() 之前（否則 character-null 先在父類 NPE、type-confusion 先寫錯角色）
        Patcher.ClassPatch hitZ = new Patcher.ClassPatch("zombie/network/fields/hit/Zombie");
        Patcher.MethodOps hz = hitZ.method("process", "()V");
        hz.headGuard = new Patcher.HeadGuard(0, "zombie/network/fields/hit/Zombie", "getZombie",
                "()Lzombie/characters/IsoZombie;");
        hz.expectedHits = 1;
        patches.add(hitZ);

        // Fall.process 對傳入 character 無 null 檢查；守衛＝縱深防禦（封包 pipeline 後續仍可能用 target）
        Patcher.ClassPatch hitF = new Patcher.ClassPatch("zombie/network/fields/hit/Fall");
        Patcher.MethodOps hf = hitF.method("process", "(Lzombie/characters/IsoGameCharacter;)V");
        hf.headGuard = new Patcher.HeadGuard(1, null, null, null);
        hf.expectedHits = 1;
        patches.add(hitF);

        // 受精蛋清除豁免（IsoGridSquare.load，2026-08-04 上線 → 2026-08-08 退役，見 patches.md 2n）：
        // 退役原因不是失效。正式服 log 實證 server 端豁免完全正常（keptLoads 數千、expiredLoads
        // 與 anomalies 全零、單顆蛋 progress 推進到 1148/1260）。真正的問題在 client：清除區塊
        // 沒有 GameClient.client 守衛，而 SandboxOptions 由 server 在連線握手時完整同步
        // （ConnectionDetails.writeSandboxOptions → SandboxOptions.load），client 端判定條件與
        // server 完全一致卻沒有對應改道，於是每次 chunk 載入都自行把蛋濾掉——玩家看不到也撿不
        // 起來，只能等它孵成小雞。決策：回歸原版行為（蛋照 24h 清除），引導玩家把雞養在雞舍下蛋。

        // ---- 效能第三波 W3（2026-08-05，三線並行分析＋三稜鏡對抗審查定稿；
        //      docs/wave3-design-v1.md，W3-3 見上方 IsoAnimal 條目）----

        // W3-1 殭屍 ownership 重選舉錯峰節流：lastChangeOwner 只在實際換手時寫入，
        // owner 穩定的殭屍每 tick 都全額重選舉（O(連線×玩家) 距離掃描，Z≈2500、C≈80
        // 時每 tick ~20 萬次）。改道 packer 迴圈內唯一的 manager.updateAuth 呼叫點到
        // tick 計數器錯峰 helper（每隻已擁有殭屍每 3 個 pass 選舉一次——PERIOD 取質數
        // 免疫長 pass 步進 2 的殘差鎖死；wall-clock 版因與退化 tick 週期共振遭三稜鏡
        // 一致否決，單欄位 tick 版再被 code review MAJOR-1 打回）。owner==null／isDead／
        // SwitchZombiesOwnershipEachUpdate=true 一律即刻放行；NetworkZombieManager.
        // clearTargetAuth 內另一 callsite 不動（斷線清理不節流）。
        // 下游疊加契約：未來若做 ping 加權 owner 選舉（改 manager.updateAuth 內部），
        // 須以節流後頻率（~400ms/隻）為基準頻率重新論證。
        Patcher.ClassPatch packer = new Patcher.ClassPatch("zombie/popman/NetworkZombiePacker");
        Patcher.MethodOps packerAuth = packer.method("updateAuth", "()V");
        packerAuth.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/popman/NetworkZombieManager", "updateAuth",
                "(Lzombie/characters/IsoZombie;)V",
                "zombie/mdc/ZombieAuthThrottle", "updateAuth"));
        packerAuth.expectedHits = 1;
        patches.add(packer);

        // W3-2（ECS getECSClass ClassValue memo）已撤刀：microbenchmark 實測 vanilla
        // 0.93ns/call vs memo 1.19ns/call——深度 1 元件（常態）的 superclass walk 是
        // 1 次 getSuperclass intrinsic＋2 次參考比較，比 ClassValue fast path 更便宜，
        // memo 為淨劣化。三稜鏡面板 GO×3 但估算（0.5-2% 收益）被量測推翻；教訓：
        // 「零風險」不等於「有收益」，收益宣稱低於實作複雜度的刀必須先量測。

        // W3-4 車輛 couldSee 掃描的 server 死工消除：update() 每車每 tick 對 AABB 10–18 格
        // 做 getGridSquare＋isCouldSee＋相交掃描，結果唯一去處 setTargetAlpha 在 server 端
        // 是 vanilla 自己 if(!GameServer.server) 擋掉的 no-op、getTargetAlpha 恆回 1.0F
        // （targetAlpha[] 全 jar 僅四個守衛讀點，審查逐項證偽）。server 直接回 true 等價
        // 短路；非 server 走 public API 逐指令複刻 fallback（部署面不可達）。
        // render()V 內同名 callsite 不得觸碰——method-scope 鎖定＋SmokeCheck 負對照。
        Patcher.ClassPatch baseVeh = new Patcher.ClassPatch("zombie/vehicles/BaseVehicle");
        Patcher.MethodOps vehUpdate = baseVeh.method("update", "()V");
        vehUpdate.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/vehicles/BaseVehicle", "couldSeeIntersectedSquare", "(I)Z",
                "zombie/mdc/VehicleCouldSeeGate", "couldSeeIntersectedSquare"));
        vehUpdate.expectedHits = 1;
        patches.add(baseVeh);

        // W12 車輛 DB chunk 索引一致性：VehicleBuffer.set 原版把 wx/wy 取自
        // vehicle.chunk、x/y 取自 physics。若 chunk 已 reset/reuse，會寫出
        // (wx,wy)=(0,0)/任意值、x/y 正確的矛盾列，重啟後正確 chunk 永遠載不到車。
        // 在原 y 欄位保存後以 x/y 推導值覆寫 wx/wy；原指令完整保留，kill switch
        // 由 helper 回傳 vanilla chunk 值。全序掛點漂移＝expectedHits 0＝建置失敗。
        Patcher.ClassPatch vehicleBuffer = new Patcher.ClassPatch(
                "zombie/vehicles/VehiclesDB2$VehicleBuffer");
        Patcher.MethodOps vehicleSet = vehicleBuffer.method("set",
                "(Lzombie/vehicles/BaseVehicle;)V");
        vehicleSet.vehicleChunkIndexRepair = new Patcher.VehicleChunkIndexRepair(
                "zombie/vehicles/VehiclesDB2$VehicleBuffer",
                "zombie/vehicles/BaseVehicle",
                "zombie/mdc/VehicleChunkIndexGuard");
        vehicleSet.expectedHits = 1;
        patches.add(vehicleBuffer);

        // 退役（2026-09-02）：W4-1 chunk 供給併包（PlayerDownloadServer
        // .removeOlderDuplicateRequests headCall）。42.20.3 官方 pending 機制上線後
        // packed 只剩 47–82 次/session、skip[short] 99.3%＝效益≈0，而每次遊戲更新都要
        // 重驗 WorkerThread 互斥前提。詳見 docs/patches.md 2p；復活方式：從退役前最後一版 2fda295 取回（`git checkout 2fda295 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

        // ---- W5 容器環防崩潰守衛（2026-08-13 全服假死實案；docs/patches.md 2q）----
        // 事故：主迴圈死於 StackOverflowError，堆疊 1024 層全是 ItemContainer.getCharacter
        // 自我遞迴 → 假死 13 分鐘、graceful quit 收不進去、看門狗強制重啟。
        // vanilla 沿「容器→裝著它的物品→該物品所在容器」爬升找擁有者且零迴圈偵測，
        // 而 AddItem 只擋同 ID 重複、不阻止把容器放進自己的子孫——MP 封包驅動搬移即可造環。
        // 改道 getCharacter() 內唯一的遞迴呼叫點（javap offset 42），讓每層遞迴都經過 helper
        // 以計深度並在失控前切斷回 null（vanilla 對「不屬於任何角色」本就回 null，
        // 呼叫端普遍做 null 檢查），同時印出環上物品供事後追查。
        Patcher.ClassPatch itemCont = new Patcher.ClassPatch("zombie/inventory/ItemContainer");
        Patcher.MethodOps getChar = itemCont.method("getCharacter",
                "()Lzombie/characters/IsoGameCharacter;");
        getChar.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/inventory/ItemContainer", "getCharacter",
                "()Lzombie/characters/IsoGameCharacter;",
                "zombie/mdc/ContainerCycleGuard", "getCharacter"));
        getChar.expectedHits = 1;
        // 同一條鏈上的第二條無防環遞迴，且是「下一個最可能炸的」：Transaction.getDuration()
        // 會呼叫它，而 getDuration() 只在 server 端、於 ItemTransactionPacket 驅動的
        // Transaction 建構時執行——正是造出環的同一條封包路徑。截斷回 false 與 vanilla
        // 走完鏈的 fall-through 同值。
        Patcher.MethodOps inCharInv = itemCont.method("isInCharacterInventory",
                "(Lzombie/characters/IsoGameCharacter;)Z");
        inCharInv.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/inventory/ItemContainer", "isInCharacterInventory",
                "(Lzombie/characters/IsoGameCharacter;)Z",
                "zombie/mdc/ContainerCycleGuard", "isInCharacterInventory"));
        inCharInv.expectedHits = 1;
        // ---- W5-2 環「門口」偵測（observe 首發，2026-08-29；enforce 待 observe 數據另案）----
        // 根治方向：AddItem 加入前偵測「物品是 target 祖先」＝將成環。vanilla 述詞
        // chainContainsContainingItem 是 private 且只爬 2 層，helper 自行實作完整深度同語意爬升。
        // 本版純 observe：不改回傳值、不拒絕；且 containsID=true 時 vanilla 根本不加入，helper
        // 只在 false 時 probe，避免污染 wouldCycle。AddItemBlind 不設 item.container backlink、
        // headCall 又在容量拒絕前，無法產生可信訊號；Java 外部 caller=0，暫不掛（W5 捕手兜底）。
        // AddItem：方法內唯一 containsID 呼叫 redirect（1→1 同形，原值照回＋旁路 probe）。
        Patcher.MethodOps addItem = itemCont.method("AddItem",
                "(Lzombie/inventory/InventoryItem;)Lzombie/inventory/InventoryItem;");
        addItem.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/inventory/ItemContainer", "containsID", "(I)Z",
                "zombie/mdc/ContainerAddCycleProbe", "containsID"));
        addItem.expectedHits = 1;
        patches.add(itemCont);

        // ---- W6 地圖格載入捕手（2026-08-14 全服假死實案，凍結 114 分鐘；docs/patches.md 2r）----
        // 事故：IllegalArgumentException「Entity is already registered」由
        // GameServer.main(:972) → ServerMap.preupdate(:969) → ServerCell.Load2/RecalcAll2
        // → IsoChunk.doLoadGridsquare(:3973) → IsoObject.addToWorld(:4497) 拋出。
        // GameServer.main 攔住例外只印出來，但攔截點在迴圈最上方——這一圈剩下的工作
        // （更新世界、處理封包、推進 frame）全數跳過，而該地圖格還留在待載入佇列裡，
        // 下一圈同一個物件同樣被拒絕，每 0.1 秒一次。frame 永久停在 46186、凍結 114 分鐘，
        // 靠排程的 mod 更新重啟才結束（沒有任何人是為了救它而重啟）。此為活鎖非崩潰，
        // 「進程掛掉就重啟」的保護救不了。同一條逐行相同的 stack 在 8/07 18:05 也發生過。
        // **非本專案所致（javap 實證，非時間相關性——log 只回溯到 7/29，entity patch 亦是
        // 7/29 上線，無乾淨基準線）**：addEntityInternal offset 0-8 是 entitySet.contains、
        // offset 11-27 就 athrow，而 FastIdentityArrayRemoval 改道在 offset 38 的
        // entities.add，位於 throw 之後拋出時執行不到；removeEntityInternal 由 offset 5 的
        // entitySet.remove 決定所有分支，我方改道的 removeValue 在 offset 29 而 offset 32
        // 是 pop，回傳值被丟棄不可能影響判斷。entitySet 全程未被碰過。
        // 手術：改道 doLoadGridsquare 內的 addToWorld callsite，catch 後記座標＋sprite 名跳過。
        // 降級極小：IsoObject.addToWorld offset 0 就是拋出點 GameEntity.addToWorld()，
        // 故後續 createContainersFromSpriteProperties／addItemsToProcessItems／
        // addObjectPoweredByGenerator 都還沒執行；且會拋出正代表該 entity 已登記在世界裡，
        // 先前成功的那次 add 已做過這些步驟。
        // doLoadGridsquare 內共有三處 addToWorld，全部通往同一個 throw 點（審查抓到的
        // blocking：初版只擋第一處，等於守衛對三分之二的觸發路徑失效）：
        //   offset 457  BaseVehicle.addToWorld()V        vehicles 迴圈           → 刻意留 vanilla
        //   offset 737  IsoObject.addToWorld()V          square.getObjects()    → 改道（兩次事故兇手）
        //   offset 947  IsoMovingObject.addToWorld()V    getStaticMovingObjects → 改道
        // IsoMovingObject 自己沒宣告 addToWorld（javap 計數 0），offset 947 派送到的是同一個
        // 方法體，包住它零額外語意風險；且該迴圈裝屍體（IsoDeadBody，正式服 id 已到 287089）。
        // BaseVehicle 相反：自己宣告 addToWorld 且開頭就有 addedToWorld 早退守衛（offset 0-26），
        // 方法體另含 parts／engine 掛載，包住它等於吞一個大得多的範圍——它仍是活的凍結路徑，
        // 此為有意識取捨，由 SmokeCheck 把它的呼叫數釘在 1（出現第四處即建置失敗）。
        // redirectDesc 以 site owner 組簽名，故 helper 需兩個同名多載。
        Patcher.ClassPatch isoChunk = new Patcher.ClassPatch("zombie/iso/IsoChunk");
        Patcher.MethodOps loadSquare = isoChunk.method("doLoadGridsquare", "()V");
        loadSquare.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/iso/IsoObject", "addToWorld", "()V",
                "zombie/mdc/ChunkLoadGuard", "addToWorld"));
        loadSquare.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/iso/IsoMovingObject", "addToWorld", "()V",
                "zombie/mdc/ChunkLoadGuard", "addToWorld"));
        loadSquare.expectedHits = 2;
        patches.add(isoChunk);

        // ---- W7 朝向暫存執行緒隔離（2026-08-13 Player-A 雞舍實案；docs/patches.md 2s）----
        // 事故：chunk 1160,968（方格 9280-9287, 7744-7751）在 19:55:03 載入失敗被 Blam 重生，
        // 46,142 → 8,549 bytes（雞舍＋32 隻家禽的完整基因組＋水桶全滅，只剩草地）。
        // 根因是 vanilla 的共用 static 暫存競態，**非本專案所致**（三重實證）：
        //   (a) 正式服 jar sha256 09a80a46… 與反編譯快照來源逐位元組相同，jar 未被改動；
        //   (b) 崩潰路徑上的 IsoGameCharacter／IsoMovingObject／IsoChunk／IsoGridSquare／
        //       IsoHutch 全部不在 loose class 覆寫清單內，直接由 jar 載入；
        //   (c) 堆疊上唯一被我方 patch 的 IsoAnimal，其 load() 經常數池正規化後與原版
        //       411 條指令逐條相同（本 class 的四刀全在 updateStress／respondToSound／
        //       killed／updateLOS，皆不在 chunk 載入路徑上）。
        // 機制：setForwardDirectionFromIsoDirection() 用全域共用的 tempVector2_2 當暫存，
        // 而 IsoMovingObject.getVectorFromDirection 開頭無條件把 x、y 歸零再填值——
        // 主執行緒與 ServerChunkLoader$LoaderThread 同時走這段就可能讀到 (0,0)，
        // normalize() 長度 0 → IllegalStateException → chunk 載入失敗 → Blam + LoadBrandNew。
        //
        // 手術：方法內兩處 getstatic tempVector2_2 各接一個 INVOKESTATIC 到 helper，
        // 回傳執行緒私有替身。vanilla 方法體只有 8 條指令、無分支無 frame：
        //   0: aload_0 / 1: getstatic tempVector2_2 / 4: invokevirtual getVectorFromDirection
        //   7: pop / 8: aload_0 / 9: getstatic tempVector2_2
        //  12: invokevirtual setForwardDirection / 15: return
        // getstatic 與插入的 invokestatic 皆 3 bytes、堆疊 1→1，形狀最單純的一類手術。
        //
        // **範圍界定**：本刀只治「毀存檔」那條路徑。全 log 保留期 67 次同一例外中，
        // 另外 66 次走的是 IsoDirections.TEMP（ToVector() 直接回傳共用 static 實例）
        // → VirtualZombieManager.createRealZombieAlways 這條**獨立**競態，落在主執行緒、
        // 被 IngameState.UpdateStuff 的 try 吞掉（每次帶掉一個 tick，無資料損失）。
        // IsoDirections 是全遊戲高流量核心 enum，爆炸半徑與本刀不同級，故不併入；
        // 待本刀上線觀察後另案評估。
        Patcher.ClassPatch gameChr = new Patcher.ClassPatch("zombie/characters/IsoGameCharacter");
        Patcher.MethodOps fwdFromIso = gameChr.method("setForwardDirectionFromIsoDirection", "()V");
        fwdFromIso.fieldGetSwap = new Patcher.FieldGetSwap(Opcodes.GETSTATIC,
                "zombie/characters/IsoGameCharacter", "tempVector2_2", "Lzombie/iso/Vector2;",
                "zombie/mdc/ForwardVectorGuard", "swap");
        fwdFromIso.expectedHits = 2;
        patches.add(gameChr);   // W7＋W22 共用；faceObj 於下方掛入同一 ClassPatch

        // ---- W22 面向物件 sprite-grid null 守衛（2026-09-02 巡檢；docs/patches.md 2ai）----
        // 正式服 9/1–9/2 兩天 3386 次（約 70/h，log 最大單一例外源）
        // `StateMachine.stateExecute > NPE: "object" is null` at faceThisObject——
        // caller 100% 動物狀態機（AnimalIdleState.execute 2366／AnimalEatState.execute 1020）。
        // vanilla：sprite-grid 物件先 getClosestSpriteGridObject 再無條件解參考（javap offset
        // 200→204），而該方法在 getSpriteGridObjects 回空清單時回 null——「包含 self」只在
        // self 仍列於其 square 的 objects 時成立（反編譯 IsoObject:5389-5395）；動物的
        // eatFromTrough／drinkFromTrough 指向已移出世界的食槽（stale 參照）就必炸。
        // 例外中斷該 tick 的 state.execute ⇒ AnimalIdleState 後段 changeState(Eat/Walk) 跳過
        // ＝動物卡 idle 每 tick 重炸。手術：方法內唯一 getClosestSpriteGridObject callsite
        // 1:1 改道，helper 委派後 null→回原 object（面向舊位置），非 null 逐位元等價。
        // faceThisObjectAlt 內同名 callsite 刻意不動（log 零命中，SmokeCheck 負對照釘死）。
        // 掛在既存 W7 ClassPatch 上——同 class 不得開第二個 ClassPatch（W19 教訓）。
        // kill switch：-Dmdc.faceObjectGuard=0。
        Patcher.MethodOps faceObj = gameChr.method("faceThisObject", "(Lzombie/iso/IsoObject;)V");
        faceObj.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/iso/IsoObject", "getClosestSpriteGridObject", "(FF)Lzombie/iso/IsoObject;",
                "zombie/mdc/FaceObjectGuard", "closestSpriteGridObject"));
        faceObj.expectedHits = 1;

        // ---- W8 chunk 寫入閘（2026-08-14；CRC-blam 家族 43 筆資料損失的止血＋蒐證；
        //      docs/patches.md 2t）----
        // 鑑識定案：43/43 筆 SANITY CHECK blam 的 log 值與磁碟檔逐位元組相符＝載入側無辜、
        // 檔案是寫入時就壞的（A 組 16 筆 crc=0＋body 自洽＝被捕捉在 Save() 回填 len 與 crc
        // 兩行之間；B 組 27 筆 header 屬於別份 body＝寫檔與重填撕裂）。機制未定罪——
        // 兩個假說已證偽（載入側共用 static＝43/43 對帳排除；ChunkSaveWorker 池化 buffer＝
        // hot-save 為 !GameServer.server 單機專屬），首嫌 ClientChunkRequest.Chunk 共用池
        // 未逐行證實。故閘門設計為**不依賴根因**：全 jar 恰 5 個 SafeWrite 呼叫點
        // （SmokeCheck census 釘死），伺服器實際可達的 3 個全部改道到快照→驗證→放行/擋下
        // 的 helper；被擋的寫入跳過（磁碟保留上一版）＋stack 蒐證＋checksum 歸零自癒重試。
        //
        // 掛點選擇：必須在「進入 SafeWrite 之前」——它的 new FileOutputStream 建構當下就
        // truncate 舊檔，內部攔截點來不及保住上一版。故 redirect 呼叫端而非 headCall 本體。
        // 不涵蓋的 2 個呼叫點：ChunkSaveWorker.WriteQueuedSave（唯一入列點 AddHotSave 被
        // !GameServer.server 閘死，SmokeCheck pin 該閘）、WorldGenerate（只寫首次生成
        // chunk、method-local buffer，寫壞也沒有玩家資料可失）。
        //
        // W4-1 交互：上線後 CRC-blam 由 0.30 升至 0.80 筆/重啟（2.7 倍，樣本 10 次重啟），
        // W4-1 是否為放大器未定案；本閘上線後壞寫入進不了磁碟，且 BLOCKED log 的 stack
        // 會直接指認寫入路徑——比關掉 W4-1 對照更快得到答案（使用者決策：W4-1 照跑）。
        Patcher.MethodOps chunkSaveB = isoChunk.method("Save", "(Z)V");
        chunkSaveB.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC,
                "zombie/iso/IsoChunk", "SafeWrite", "(IILjava/nio/ByteBuffer;)V",
                "zombie/mdc/ChunkWriteGuard", "safeWrite"));
        chunkSaveB.expectedHits = 2;   // 單機分支＋伺服器 checksum-diff 分支（伺服器只走後者，前者改道無害）

        Patcher.ClassPatch sclSave = new Patcher.ClassPatch("zombie/network/ServerChunkLoader$SaveLoadedTask");
        Patcher.MethodOps sclSaveM = sclSave.method("save", "()V");
        sclSaveM.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC,
                "zombie/iso/IsoChunk", "SafeWrite", "(IILjava/nio/ByteBuffer;)V",
                "zombie/mdc/ChunkWriteGuard", "safeWrite"));
        // ---- W9 之二（同方法追加）：去重 CRC 執行緒隔離 ----
        // save() 可在 SaveChunkThread（run）與 LoaderThread（saveNow 沖存檔）並行執行，
        // 四連讀外層 ServerChunkLoader.crcSave 共用實例（reset/update/getValue×2）——
        // 競態污染 ChunkChecksum（去重誤判＝陳舊跳寫；客戶端校驗錯亂＝重送）。
        // 四個 GETFIELD 全部同形替換為執行緒私有實例（docs/patches.md 2u）。
        sclSaveM.fieldGetSwap = new Patcher.FieldGetSwap(Opcodes.GETFIELD,
                "zombie/network/ServerChunkLoader", "crcSave", "Ljava/util/zip/CRC32;",
                "zombie/mdc/ChunkSaveIsolation", "dedupCrc");
        sclSaveM.expectedHits = 5;   // SafeWrite 改道 ×1 ＋ crcSave 同形替換 ×4
        // W9 之三（後半）：release() 歸還改道私有池（與 addLoadedJob 的租用配對）
        Patcher.MethodOps sclRelM = sclSave.method("release", "()V");
        sclRelM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/network/ClientChunkRequest", "releaseChunk",
                "(Lzombie/network/ClientChunkRequest$Chunk;)V",
                "zombie/mdc/ChunkSaveIsolation", "releaseChunk"));
        sclRelM.expectedHits = 1;
        patches.add(sclSave);

        // ---- W9 存檔管線隔離（2026-08-14；CRC-blam 家族根治刀；docs/patches.md 2u）----
        // 定罪：W8 首晚 8 筆 BLOCKED 全走 SaveLoadedTask 路徑、簽名全為「len 正確＋crc 0/垃圾」
        // ——唯一相容機制是 header 指紋競態：addLoadedJob 傳給 Save() 的 SaveChunkThread.crc32
        // 是單一共用實例，而 addLoadedJob 可在主迴圈（ServerCell.update→saveChunk）與
        // GameServer$1（shutdown hook 的 QueuedSaveAll）並行執行。對方 reset() 插在我
        // update 與 getValue 之間 → 指紋 0（A 組 16 筆歷史簽名）；update 交錯 → 垃圾
        // （B 組 27 筆）。body/len 各自完整 → len 恆正確（8/8 觀測鐵律）。
        // 之一：GETFIELD crc32 → ThreadLocal（根絕指紋競態）。
        // 之三（前半）：getChunk/getByteBuffer/releaseChunk 改道私有池——存檔管線退出
        // ClientChunkRequest 全域 static 共用池（與 N 條發送 WorkerThread 共用），恢復
        // 單一所有權鏈，同時關閉 W8 閘「完整重填自洽資料」的理論盲區。
        // 驗證閉環：W8 flagged 計數器應歸零；kill switch -Dmdc.chunkSaveIsolation=0。
        Patcher.ClassPatch sctIso = new Patcher.ClassPatch("zombie/network/ServerChunkLoader$SaveChunkThread");
        Patcher.MethodOps sctAddM = sctIso.method("addLoadedJob", "(Lzombie/iso/IsoChunk;)V");
        sctAddM.fieldGetSwap = new Patcher.FieldGetSwap(Opcodes.GETFIELD,
                "zombie/network/ServerChunkLoader$SaveChunkThread", "crc32", "Ljava/util/zip/CRC32;",
                "zombie/mdc/ChunkSaveIsolation", "headerCrc");
        sctAddM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/network/ClientChunkRequest", "getChunk",
                "()Lzombie/network/ClientChunkRequest$Chunk;",
                "zombie/mdc/ChunkSaveIsolation", "getChunk"));
        sctAddM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/network/ClientChunkRequest", "getByteBuffer",
                "(Lzombie/network/ClientChunkRequest$Chunk;)V",
                "zombie/mdc/ChunkSaveIsolation", "getByteBuffer"));
        sctAddM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/network/ClientChunkRequest", "releaseChunk",
                "(Lzombie/network/ClientChunkRequest$Chunk;)V",
                "zombie/mdc/ChunkSaveIsolation", "releaseChunk"));
        sctAddM.expectedHits = 4;   // crc32 同形替換 ×1 ＋ 租用/配 buffer/例外歸還改道 ×3
        patches.add(sctIso);

        // ---- W10 卡讀條根治（2026-08-23；玩家實測「讀條走滿卻不完成」；docs/patches.md 2x）----
        // 兩個 vanilla 缺陷疊乘，皆位於 server-only 路徑（client 端 vanilla 已有完整處理，
        // 故玩家不需安裝任何東西）：
        // (1) NetTimedAction.parse 以 protectedCall 重建 Lua action，而參數中的 InventoryItem
        //     由 PZNetKahluaTableImpl.loadInventoryItem 在「容器或 item 查不到」時靜默回 null。
        //     該 null 直接成為 Lua 建構子參數，建構子首行就索引它（ISEatFoodAction.lua:298
        //     item:getContainer()／ISReadABook.lua:492 item:getSkillTrained()／
        //     ISMoveablesAction.lua:308 item:getWorldSprite()）→ Kahlua 拋 RuntimeException，
        //     穿過名為 protected 的 protectedCall，一路到 GameServer.mainLoopDealWithNetData
        //     被 catch 吞掉 → processServer 從未執行 → 既不回 Accept 也不回 Reject。
        //     vanilla 本來就寫好了失敗處理（!result.isSuccess() → action=null; return），
        //     只是例外繞過了它——B 刀就是讓那條既有路徑真正被走到。
        // (2) processServer 對中間物件 act 設 state 卻用 this.write 送出（javap：offset
        //     81／142 皆 aload_0），this.state 恆為 Request → 該方法的 initial Request
        //     rejection 無法讓 client 的 ActionManager.isRejected 成立。已接受 action 在
        //     ActionManager.update 中因 perform()==false 產生的後續 Reject 從正確 action
        //     物件序列化，不受影響；ItemTransactionPacket.processServer 也是寫對的對照。
        // 兩刀是「與」關係：只有 B → Reject 送出去仍是 Request state；只有 A → parse 就
        // 已中斷、processServer 根本沒被呼叫。kill switch 分離以便二分定位：
        // -Dmdc.netTimedActionGuard=0（B）／-Dmdc.netTimedActionState=0（A）。
        String ntaGuard = "zombie/mdc/NetTimedActionGuard";
        Patcher.ClassPatch nta = new Patcher.ClassPatch("zombie/core/NetTimedAction");
        Patcher.MethodOps ntaParse = nta.method("parse",
                "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V");
        ntaParse.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "se/krka/kahlua/integration/LuaCaller", "protectedCall",
                "(Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;[Ljava/lang/Object;)"
                        + "Lse/krka/kahlua/integration/LuaReturn;",
                ntaGuard, "protectedCall"));
        ntaParse.expectedHits = 1;   // parse 內唯一（getDuration/start/stop/perform 的同名呼叫不在此方法）
        patches.add(nta);

        Patcher.ClassPatch ntaPkt = new Patcher.ClassPatch("zombie/network/packets/NetTimedActionPacket");
        Patcher.MethodOps ntaProcess = ntaPkt.method("processServer",
                "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V");
        ntaProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/network/packets/NetTimedActionPacket", "write",
                "(Lzombie/core/network/ByteBufferWriter;)V", ntaGuard, "write"));
        ntaProcess.expectedHits = 2;   // accept 與 reject 分支各一；helper 只在 action==null 時介入
        patches.add(ntaPkt);

        // ---- W11 動物聲音排序活鎖捕手（2026-08-23 晚間事故；docs/patches.md 2y）----
        // BaseAnimalSoundManager 的比較器每次 compare 現場重算 listener 距離，且手寫 >/< 三態
        // ——NaN 一律回 0（違反遞移性）→ TimSort 拋 IllegalArgumentException。而 update() 的
        // characters.clear() 在 sort 之後（javap：sort offset 19、clear offset 116+）——
        // 一炸 clear 即跳過，stale 動物參照永存清單 → 每幀重炸（正式服 19:23 偶發 →
        // 21:47 每幀，1411 次），B 段中斷 → updateManagers 永久跳過 → 全服卡讀條＋時間停止。
        // 捕手：只攔 IAE（其他例外穿透）、不排序直接返回，讓 clear() 執行、活鎖鏈斷開；
        // 攔截時掃清單記 NaN 座標動物（nanAnimals=0 但炸 ＝ NaN 在 listener 側，黃金診斷）。
        // 觸發背景：圈養農場 50-80+ 動物高密度碰撞＋Cleaner 每分鐘批次 remove()×20（合法 API，
        // vanilla despawn 走同一路徑——修 Cleaner 只能降頻，缺陷本體在 vanilla）。
        // kill switch：-Dmdc.animalSortGuard=0。
        Patcher.ClassPatch animalSound = new Patcher.ClassPatch("zombie/characters/BaseAnimalSoundManager");
        Patcher.MethodOps animalSoundUpdate = animalSound.method("update", "()V");
        animalSoundUpdate.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList", "sort", "(Ljava/util/Comparator;)V",
                "zombie/mdc/AnimalSortGuard", "sort"));
        animalSoundUpdate.expectedHits = 1;   // update()V 內唯一 sort callsite（offset 19）
        patches.add(animalSound);

        // ---- W13 動物同步範圍對齊（2026-08-24 封包鑑識）----
        // vanilla 幾何不一致：動物 relevancy 半徑是 (getRelevantRange()-2)*10 == (range/2)*10
        // （javap offset 233-242），而 client 載入矩形的共同半寬只有 (range/2)*8 ——半徑
        // 固定超過正常奇數 grid 的安全下界。改道把半徑夾到 (getChunkGridWidth()/2)*8。
        // 完整鑑識數據、clamp 範圍、載具排除、中心同步前提、殘留誤差理由及限定條件
        // 一律見 docs/patches.md 2aa —— 這裡不複誦，避免兩份數字漂移。
        // 用 redirect 而非 method-scope constChange 的理由：常數烘進 bytecode 就沒有
        // runtime kill switch；半徑要釘 chunkGridWidth 而非 relevantRange 公式；且載具
        // 排除與 clamp 邊界判定需要 helper 邏輯。三態 -Dmdc.animalRelevancy（1/2/0）。
        Patcher.ClassPatch animalSync = new Patcher.ClassPatch("zombie/popman/animal/AnimalSynchronizationManager");
        Patcher.MethodOps animalSyncSend = animalSync.method("sendUpdateToClient",
                "(Lzombie/core/raknet/UdpConnection;ZLjava/util/HashSet;)V");
        animalSyncSend.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/core/raknet/UdpConnection", "RelevantTo", "(FFF)Z",
                "zombie/mdc/AnimalRelevancyGate", "relevantTo"));
        // ---- W14 動物 requested 冷卻＋範圍閘（2026-08-24 W13 上線後量測的第二刀）----
        // W13 生效後殘留重送 96.2% 落在 vanilla 環帶、98.5% 來自載具 passthrough 連線
        // （量測與理由見 docs/patches.md 2ab）。第二刀不依賴幾何：同連線同動物在冷卻窗內
        // 只回一次完整快照，對載具族群同樣有效。兩個 redirect 都在 sendUpdateToClient：
        // ① getPacket invokevirtual x2（reliable/unreliable 分支，offset 12/31）——
        //    ThreadLocal 捕獲 connection 供範圍閘用；sendRequestToServer 是
        //    invokeinterface IConnection.getPacket，(opcode,owner) 不同、不會誤中。
        // ② HashMap.get invokevirtual x3（offset 83 requests.get(guid) ＝過濾目標；
        //    offset 370/419 timerUpdateAnimal.get(Short) ＝ helper 以 key instanceof Long
        //    在 runtime 完美分流、原樣直通）。
        // 兩把獨立 kill switch：-Dmdc.animalRequestCooldown / -Dmdc.animalRequestRange。
        animalSyncSend.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/core/raknet/UdpConnection", "getPacket",
                "(Lzombie/network/PacketTypes$PacketType;)Lzombie/network/packets/INetworkPacket;",
                "zombie/mdc/AnimalRequestGate", "getPacket"));
        animalSyncSend.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;",
                "zombie/mdc/AnimalRequestGate", "filterRequests"));
        // 1 (W13 RelevantTo, offset 242) + 2 (W14 getPacket) + 3 (W14 HashMap.get)
        animalSyncSend.expectedHits = 6;
        patches.add(animalSync);

        // ---- W15 主迴圈凍結看門狗（2026-08-24 兩波卡頓的觀測刀；docs/patches.md 2ac）----
        // 21:27–21:31 主迴圈單幀凍結累計 ~216s（f:7115→7118 三幀），RakNet 同幀踢 9 條
        // 在線連線＋12.5 分鐘 connection-null 封包風暴（75,143 行）＝黑邊。三個候選機制
        // （動物路徑重活／ZGC 瞬時 allocation stall／glibc 損毀 heap 上的 malloc 停滯）
        // 全部卡在同一個觀測缺口：凍結當下沒有主執行緒 stack。本刀把「下次凍結時抓
        // jstack」自動化：headCall 掛 ServerMap.preupdate()V 頭部——GameServer.main
        // 主迴圈每圈恰呼叫一次（javap：main 內 invokevirtual preupdate 全方法恰 1 處，
        // SmokeCheck 釘死；W6 事故 stack 亦實證其在主迴圈上）。helper 記 volatile
        // 時間戳；daemon 每秒輪詢，幀齡 ≥5s（可調）即對主執行緒 getStackTrace 快照
        // （帶 Thread.getState 與 heap used/max 分流三假說），每 10s 補拍、單次凍結
        // 上限 12 張，恢復時印總時長與期間 tick 推進數（0=完全凍結、>0=慢幀連發）。
        // 純觀測、零行為改變、平時零輸出（不成為新噪音源）。
        // kill switch：-Dmdc.mainLoopWatchdog=0；門檻 -Dmdc.mainLoopWatchdogThresholdMs。
        Patcher.ClassPatch serverMap = new Patcher.ClassPatch("zombie/network/ServerMap");
        Patcher.MethodOps preupdate = serverMap.method("preupdate", "()V");
        preupdate.headCall = new Patcher.HeadCall("zombie/mdc/MainLoopWatchdog", "tick",
                "(Lzombie/network/ServerMap;)V");
        preupdate.expectedHits = 1;
        patches.add(serverMap);

        // 退役（2026-09-02）：W16 動物卸載接手守衛 observe。8 天正式服全零遺失
        // （s2Missed／queueFailures／sourceGap／cellNull／chunkNull／duplicateRemoved 全 0，
        // clearShortfall 的 handedOff=scanSeen 故非遺失）⇒ vanilla 卸載接手鏈無辜、觀測
        // 結論已達；heartbeat 每 256 unload 一行佔正式服 log 7.3%（5274/71806 行）。
        // 詳見 docs/patches.md 2ad；復活方式：從退役前最後一版 2fda295 取回（`git checkout 2fda295 -- <檔案>`＋回填 PatchConfig／SmokeCheck／build.ps1 對應段）。

        // ---- W17 hutch 載入回傳檢查 enforce（靜態已定罪；docs/patches.md 2ae）----
        // IsoHutch.load 逐隻 addAnimalInside(animal,false) 忽略 boolean 回傳（offset 526：
        // invokevirtual; pop 恰 1）；addAnimalInside 骰位重試 >100 次跳出後最終落位只查
        // animalInside（offset 148-151），佔用即 false ⇒ 該動物不進任何容器、隨 GC 消失＝
        // 載入即滅失。接近滿舍（屍體/nestBox 佔位擠掉有效槽）時機率性觸發，兔群 16→3 正對
        // 此形。helper 委派後對 false 強制入位（順序掃、零 Rand、map/backlink/preferred/
        // hutchPosition/itemID/tryRemove 六步對齊 vanilla；tryRemove body 是 client-only，
        // server 上 no-op，照呼維持同構）。
        // client 端 load 在 wv≥212 skip 動物 blob（offset 191-209）＝redirect 死碼，且 loose
        // class 只部署 server——無 2n 型 desync 面。
        // kill switch：-Dmdc.hutchLoadGuard（1 enforce 預設／2 observe 只記不救／0 off 純委派）。
        Patcher.ClassPatch hutch = new Patcher.ClassPatch("zombie/iso/objects/IsoHutch");
        Patcher.MethodOps hutchLoad = hutch.method("load", "(Ljava/nio/ByteBuffer;IZ)V");
        hutchLoad.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/iso/objects/IsoHutch", "addAnimalInside",
                "(Lzombie/characters/animals/IsoAnimal;Z)Z",
                "zombie/mdc/HutchLoadGuard", "addInside"));
        hutchLoad.expectedHits = 1;
        patches.add(hutch);

        // ---- W19 車輛永久移除授權守衛 observe（2026-08-28 立案；docs/patches.md 2ag）----
        // vanilla Commands.remove（VehicleCommands.lua:359-366）無權限檢查直呼
        // permanentlyRemove；Java 側 receiveClientCommand 的 vehicle/remove 閘經
        // NetworkPlayerAI.isDismantleAllowed() 恆 true＝實質全放行；另一條玩家路徑
        // ISRemoveBurntVehicle.lua:135（server 端 timed action complete 直呼，不經
        // Commands.remove）＝2026-08-23 Player-F 案三輛未認領完好車 vehicles.db 整列 DELETE
        // 的實路。移除拆車 MOD 只關選單不關能力，咽喉唯一交匯點＝permanentlyRemove 本身。
        // 本版純 observe（headCall 頭部記錄 vid/script/pos/MVCK 認領/caller frame/Lua 驅動/
        // 近距玩家）：enforce 需要 (requester, vehicle) 對而咽喉點只有 vehicle，身分橋
        // 未定案前 enforce 必然誤殺（admin onCheatRemove 與惡意刪車走同一條 command；
        // 純車況規則會擋 setSmashed 換殼與 admin /remove vehicles 批次）——三方審查
        // （codex/grok lane 2026-08-28）一致。jar-wide callsite census=4 由 SmokeCheck 釘死。
        // kill switch：-Dmdc.vehicleRemoveGuard（2 observe 預設／1 本版 observe-alias／0 off）。
        Patcher.MethodOps permRemove = baseVeh.method("permanentlyRemove", "()V");
        permRemove.headCall = new Patcher.HeadCall("zombie/mdc/VehicleRemoveGuard", "onRemove",
                "(Lzombie/vehicles/BaseVehicle;)V");
        permRemove.expectedHits = 1;

        // ---- W20 衣物同步守衛（2026-08-28 立案；docs/patches.md 2ah）----
        // 8/28 當輪三個 log 叢集（send Exception ×362 per-connection 放大＋SyncVisuals
        // mismatch ×129）。(a) ContainerID.set 雙參的 ObjectContainer/IsoObject 分支直讀
        // o.square.getObjects()（offset 197/233）無守衛——上游 Unwear 用 getSquare()
        // （current?:square）放行、此處讀 raw square＝矛盾根源；純觀測分解（headCall
        // slots={1,2}，多 slot 版首用）。(b) ItemDescription ctor 對 baseTexture/
        // textureChoice 有 getVisual()==null 守衛、唯 tint 直呼 getVisual().getTint()
        // （offset 91-101）漏——redirect getTint→tintOf（observe 記錄後拋 NPE 保 vanilla
        // 語意；enforce null→ImmutableColor.white 只保序列化）。禁止 lambda 過濾整件：
        // process 會把未列出 worn item 從遠端 WornItems.remove＝遠端脫裝。(c) SyncVisuals
        // 是純 positional 協定（wire 無 item identity），跳項/clamp 會索引錯位套錯衣服，
        // vanilla 整包拒絕反而安全——只觀測：redirect parse 的 3 處 PlayerID.getPlayer
        // （捕獲 parse 對象）＋mismatch error callsite（資訊超集行：player＋signed diff）。
        // 共同根因假說（observe 證偽）：同一件 null-visual worn item 令 (b) 炸且令
        // getItemVisuals 少算 1 ⇒ (c) wire-local=+1。kill switch 三把分離：
        // -Dmdc.containerIdProbe（0/2 預設）、-Dmdc.clothingTintGuard（0/1/2 預設）、
        // -Dmdc.visualsMismatchProbe（0/2 預設）。
        Patcher.ClassPatch containerId = new Patcher.ClassPatch("zombie/network/fields/ContainerID");
        Patcher.MethodOps cidSet = containerId.method("set",
                "(Lzombie/inventory/ItemContainer;Lzombie/iso/IsoObject;)V");
        cidSet.headCall = new Patcher.HeadCall("zombie/mdc/ContainerIdProbe", "onSet",
                "(Lzombie/inventory/ItemContainer;Lzombie/iso/IsoObject;)V", new int[]{1, 2});
        cidSet.expectedHits = 1;
        patches.add(containerId);

        Patcher.ClassPatch syncClothing = new Patcher.ClassPatch(
                "zombie/network/packets/SyncClothingPacket");
        Patcher.MethodOps scpSet = syncClothing.method("set", "(Lzombie/characters/IsoPlayer;)V");
        scpSet.headCall = new Patcher.HeadCall("zombie/mdc/ClothingSyncGuard", "onClothingSet",
                "(Lzombie/characters/IsoPlayer;)V", new int[]{1});
        scpSet.expectedHits = 1;
        patches.add(syncClothing);

        Patcher.ClassPatch itemDesc = new Patcher.ClassPatch(
                "zombie/network/packets/SyncClothingPacket$ItemDescription");
        Patcher.MethodOps idCtor = itemDesc.method("<init>",
                "(Lzombie/characters/WornItems/WornItem;)V");
        idCtor.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/core/skinnedmodel/visual/ItemVisual", "getTint",
                "()Lzombie/core/ImmutableColor;",
                "zombie/mdc/ClothingSyncGuard", "tintOf"));
        // W20-2（2026-09-02 巡檢）：enforce 後 nullVisual 仍恆為同一玩家（Player-G，8 天
        // 480+ 筆）但 nullVisual 路徑拿不到 item（redirect 只換 getTint 的 receiver，
        // 那個 receiver 就是 null）。ctor 頭部 headCall slot 1 把 WornItem 交給 helper
        // ThreadLocal，nullVisual 行才印得出 fullType／bodyLocation＝物品歸因的唯一路徑。
        // ctor 頭部 aload_1 只碰參數不碰 uninitializedThis，verifier 合法（super 之前）。
        idCtor.headCall = new Patcher.HeadCall("zombie/mdc/ClothingSyncGuard", "onItemDescription",
                "(Lzombie/characters/WornItems/WornItem;)V", new int[]{1});
        idCtor.expectedHits = 2;   // headCall 1 + getTint redirect 1
        patches.add(itemDesc);

        Patcher.ClassPatch syncVisuals = new Patcher.ClassPatch(
                "zombie/network/packets/SyncVisualsPacket");
        Patcher.MethodOps svpParse = syncVisuals.method("parse",
                "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V");
        svpParse.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/network/fields/character/PlayerID", "getPlayer",
                "()Lzombie/characters/IsoPlayer;",
                "zombie/mdc/ClothingSyncGuard", "parsePlayer"));
        svpParse.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/debug/DebugType", "error", "(Ljava/lang/Object;)V",
                "zombie/mdc/ClothingSyncGuard", "onVisualsMismatch"));
        svpParse.expectedHits = 4;   // getPlayer 3 + error 1
        patches.add(syncVisuals);

        return patches;
    }

    /**
     * Client 端 patch 集合（與 server 部署完全隔離：獨立 build-client.ps1 → dist-client\，
     * 不進 server manifest）。42.20 invisible-entities 調查（「影子/名牌在、3D 模型不見」）
     * 因果鏈第一環：TextureIDAssetManager.waitFileTask 的 50MB 全域 DirectBuffer 硬門檻——
     * 超標時 2–4 條檔案載入執行緒無限 sleep（零 log、無 timeout），貼圖與 mesh 共用管線
     * 全面停擺，此時所有新進視野/剛被 Reset 的實體因全有全無 bake 閘門整隻不畫。
     * javap 證據：waitFileTask()V ＝ invokestatic DirectBufferAllocator.getBytesAllocated()J
     * → ldc2_w 52428800L → lcmp/ifle → sleep(20) 迴圈（方法內兩者各恰一處）。
     */
    public static List<Patcher.ClassPatch> client(boolean lowmem) {
        List<Patcher.ClassPatch> patches = new ArrayList<>();

        // 兩刀都在 waitFileTask 方法範圍內：redirect＝passthrough 觀測（水位/hwm/floor/stall，
        // 節流 log 到 console.txt）；constChange＝門檻 50MB→4GB（v1.2 起；v1.1=1GB）。實測
        // （Tester-A 兩場 log）證明水位地板因 ImageData 解碼例外洩漏單調上升，天花板只買時間；
        // v2.0 起洩漏根治線（下方 MinidoracatTextureLeakGuard 五 hook）落地，天花板轉為
        // 罕見峰值上限。**lowmem 變體（Patcher 顯式 mode `client-lowmem`，出包 v3.0-lowmem）**：
        // ≤8GB RAM 機器（42.20.3 隱形實證玩家：8101MB＋Xmx3G）不適用 4GB 等待門檻（gate 是
        // 配置**前**的水位檢查、非硬上限：多 worker 可同秒通過、單筆配置不受限，native 最壞
        // 用量高於 4GB）——commit charge 預算超載；lowmem 保留觀測與根治線、不做 constChange
        // （根治後水位有回收，50MB sleep 恢復「短暫等待」的 vanilla 設計語意），且 redirect 指向
        // bytesAllocatedObservedLowMem＝effective 門檻 50MB 烘進 helper（橫幅與 stall 分類
        // 都以實際生效值計，事故 log 不說謊——三 lane＋advisory 對抗審查定案）。
        Patcher.ClassPatch tex = new Patcher.ClassPatch("zombie/core/textures/TextureIDAssetManager");
        Patcher.MethodOps wait = tex.method("waitFileTask", "()V");
        wait.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, "zombie/core/utils/DirectBufferAllocator",
                "getBytesAllocated", "()J", "zombie/mdc/TexturePipelineGuard",
                lowmem ? "bytesAllocatedObservedLowMem" : "bytesAllocatedObserved"));
        if (!lowmem) {
            wait.consts.add(new Patcher.ConstChange(52428800L, 4294967296L));
        }
        wait.expectedHits = lowmem ? 1 : 2;
        patches.add(tex);

        // ---- W4-2 chunk 請求逾時 8s→15s（42.20.2 歷史，42.20.3 已撤刀）----
        // 當時根因：resendTimedOutRequests 對超過 8000ms 未完成的請求設 flagsWs|=9 →
        // loadReceivedChunks 丟棄已送達整包並重排隊、不通知 server 取消＝livelock
        // （實測 pending 恆＝請求率×8s、141 輪重發、18 分鐘 105MB 全丟棄）。
        // 42.20.3 起 vanilla **整個刪除該方法**（盲等逾時重發由 ChunkNotReady 主動通知
        // 根治）——手術目標不存在，本刀撤除；此段僅留歷史脈絡，無對應 MethodOps。

        // ---- v2.0 貼圖洩漏根治第一波（四路 retention trace＋對抗評審定罪；docs/patches.md 2j）----
        // 主犯 1（40-60%）：ImageData.dispose() 只釋放 data＋mipMaps、完全不碰 frames——
        // APNG 動畫貼圖每幀全尺寸 buffer 永久滯留（零例外零 log、對 mod 集合確定性=110MB 雙機基線）。
        // 主犯 2（20-35%）：getData() 對 data==null 一律配置固定 67108864（64MB）不看實際尺寸；
        // mip-flag APNG 因 getMipMapCount()==0 → getMipMapData(-1) AIOOBE 跳過上傳尾端 dispose，
        // 單發漏 64MB＋mip 鏈＋全幀（=+64/+99MB 大跳）。helper 位於 zombie.core.textures
        // （frames 為 package-private），所有 dispose 過 isDisposed 冪等閘。
        String leakGuard = "zombie/core/textures/MinidoracatTextureLeakGuard";
        Patcher.ClassPatch imgData = new Patcher.ClassPatch("zombie/core/textures/ImageData");
        Patcher.MethodOps disposeM = imgData.method("dispose", "()V");
        disposeM.headCall = new Patcher.HeadCall(leakGuard, "disposeFrames",
                "(Lzombie/core/textures/ImageData;)V");
        disposeM.expectedHits = 1;
        Patcher.MethodOps getDataM = imgData.method("getData", "()Lzombie/core/textures/MipMapLevel;");
        getDataM.headCall = new Patcher.HeadCall(leakGuard, "ensureData",
                "(Lzombie/core/textures/ImageData;)V");
        getDataM.expectedHits = 1;
        Patcher.MethodOps mipCountM = imgData.method("getMipMapCount", "()I");
        mipCountM.headCall = new Patcher.HeadCall(leakGuard, "ensureData",
                "(Lzombie/core/textures/ImageData;)V");
        mipCountM.expectedHits = 1;
        patches.add(imgData);

        // S6 防禦性堵口：freeMemory 原版只斷引用不 dispose（42.20 零呼叫者，footgun 封口）。
        // S4：createSteamAvatar 失敗路徑漏 65536 bytes——redirect 唯一呼叫點到逐語意重實作
        // （成功/例外路徑逐一等價，僅失敗路徑補 dispose）。
        Patcher.ClassPatch texId = new Patcher.ClassPatch("zombie/core/textures/TextureID");
        Patcher.MethodOps freeM = texId.method("freeMemory", "()V");
        freeM.headCall = new Patcher.HeadCall(leakGuard, "onFreeMemory",
                "(Lzombie/core/textures/TextureID;)V");
        freeM.expectedHits = 1;
        Patcher.MethodOps avatarM = texId.method("createSteamAvatar",
                "(J)Lzombie/core/textures/TextureID;");
        avatarM.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, "zombie/core/textures/ImageData",
                "createSteamAvatar", "(J)Lzombie/core/textures/ImageData;",
                leakGuard, "createSteamAvatarFixed"));
        avatarM.expectedHits = 1;
        patches.add(texId);

        // ---- v3.0 chunk 串流觀測（黑邊事件鑑識；v2.1 三 headCall → 42.20.3 擴充為四）----
        // 四個 headCall 全部 receiver-only：updateMain=心跳＋節流讀態＋STALL 判定，
        // receiveChunkPart/receiveNotRequired=payload 接收計數（更新 lastReceiveNs），
        // receiveChunkNotReady=42.20.3 新協定計數（獨立基準 lastNotReadyNs，不算 payload）。
        // 官方修「Loading Map forever」（pending＋ChunkNotReady）並自承黑邊 additional
        // causes 仍在調查——本觀測線正是抓殘餘原因的工具，錨點逐一重驗健在。
        // 假說 (b)（server 3 次重試放棄）已隨重試機制刪除而失效；(a) largeArea 停送 gate 待驗。
        String cso = "zombie/mdc/ChunkStreamObserver";
        String csoDesc = "(Lzombie/iso/WorldStreamer;)V";
        Patcher.ClassPatch streamer = new Patcher.ClassPatch("zombie/iso/WorldStreamer");
        Patcher.MethodOps um = streamer.method("updateMain", "()V");
        um.headCall = new Patcher.HeadCall(cso, "onUpdateMain", csoDesc);
        um.expectedHits = 1;
        Patcher.MethodOps rcp = streamer.method("receiveChunkPart",
                "(Lzombie/core/network/ByteBufferReader;)V");
        rcp.headCall = new Patcher.HeadCall(cso, "onReceiveChunkPart", csoDesc);
        rcp.expectedHits = 1;
        Patcher.MethodOps rnr = streamer.method("receiveNotRequired",
                "(Lzombie/core/network/ByteBufferReader;)V");
        rnr.headCall = new Patcher.HeadCall(cso, "onReceiveNotRequired", csoDesc);
        rnr.expectedHits = 1;
        // 42.20.3 新協定：receiveChunkNotReady(I)V——server 對未生成/超限 chunk 的主動回覆
        // （vanilla 完整生命週期：drain sentRequests→pendingRequests，把 flagsWs&1 與相符
        // requestNumber 的 entry 移出網路緒 pendingRequests 並標 flagsUdp|=16/24；同一物件
        // 仍在 streamer 緒 pendingRequests1，loadReceivedChunks 依 flags 收尾——chunk 仍被
        // 引用時重新入列 chunkRequests1＝延後重排，不需要時歸還池）。
        // 只更新獨立基準 lastNotReadyNs——STALL 維持「30 秒無 payload」語意（生成瓶頸不被
        // 靜音），STALL 行以 notReadyAgoMs 分型（小＝生成端瓶頸、大/-1＝全斷流）。
        Patcher.MethodOps rnrd = streamer.method("receiveChunkNotReady", "(I)V");
        rnrd.headCall = new Patcher.HeadCall(cso, "onReceiveChunkNotReady", csoDesc);
        rnrd.expectedHits = 1;
        // W4-2（逾時 8s→15s）已於 42.20.3 撤刀：vanilla 整個刪除 resendTimedOutRequests
        // （盲等逾時重發由 ChunkNotReady 主動通知根治），手術目標方法不存在。
        patches.add(streamer);

        return patches;
    }

    private PatchConfig() {}
}
