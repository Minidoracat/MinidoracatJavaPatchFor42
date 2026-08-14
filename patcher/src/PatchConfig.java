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
    private static final String LOGIN_METRICS = "zombie/network/MinidoracatLoginMetrics";
    private static final String JOIN_METRICS = "zombie/network/MinidoracatJoinMetrics";
    private static final String TWO_STR_VOID = "(Ljava/lang/String;Ljava/lang/String;)V";
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

        // 登入尖峰先量測三個同步 DB 寫入的個別耗時；只改呼叫目標，不改順序、參數、return/POP 或例外邊界
        Patcher.ClassPatch login = new Patcher.ClassPatch("zombie/network/packets/connection/LoginPacket");
        Patcher.MethodOps loginProcess = login.method("processServer",
                "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V");
        loginProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/network/ServerWorldDatabase",
                "setPassword", TWO_STR_VOID, LOGIN_METRICS, "setPassword"));
        loginProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/network/ServerWorldDatabase",
                "updateLastConnectionDate", TWO_STR_VOID, LOGIN_METRICS, "updateLastConnectionDate"));
        loginProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/network/ServerWorldDatabase",
                "setUserSteamID", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                LOGIN_METRICS, "setUserSteamID"));
        loginProcess.expectedHits = 3;
        patches.add(login);

        // join 卡頓量測（正式服實測 6–11 秒主迴圈停頓集中在玩家 join/死亡重生換角）：
        // CreatePlayerPacket.processServer 尾段四個重活各包 timing wrapper，不改呼叫順序、
        // 參數與例外邊界。IsoPlayer ctor 無法以 redirect 包（INVOKESPECIAL <init> 的
        // 未初始化物件不可傳遞）——殘差時間＝ctor＋spawn 邏輯，由四項量測反推。
        Patcher.ClassPatch createPlayer = new Patcher.ClassPatch("zombie/network/packets/character/CreatePlayerPacket");
        Patcher.MethodOps createProcess = createPlayer.method("processServer",
                "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V");
        createProcess.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, "zombie/Lua/LuaEventManager",
                "triggerEvent", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
                JOIN_METRICS, "triggerEvent"));
        createProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/savefile/ServerPlayerDB",
                "serverUpdateNetworkCharacter",
                "(Lzombie/characters/IsoPlayer;ILzombie/core/raknet/UdpConnection;)V",
                JOIN_METRICS, "serverUpdateNetworkCharacter"));
        createProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/savefile/ServerPlayerDB",
                "process", "()V", JOIN_METRICS, "process"));
        createProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/network/packets/character/CreatePlayerPacket",
                "write", "(Lzombie/core/network/ByteBufferWriter;)V", JOIN_METRICS, "write"));
        createProcess.expectedHits = 4;
        patches.add(createPlayer);

        // 一般重連（既有角色，join 大宗）走 GameServer.receivePlayerConnect，CreatePlayerPacket
        // 只蓋新角色/死亡換角。REJOIN_TOTAL 包整個 receivePlayerConnect（兩個呼叫點：一般＋coop），
        // REJOIN_LOAD_CHARACTER 包內層 SQL SELECT＋玩家全量反序列化（同方法兩個 if/else 呼叫點）。
        String rpcDesc = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;Ljava/lang/String;)V";
        Patcher.ClassPatch connect = new Patcher.ClassPatch("zombie/network/packets/connection/ConnectPacket");
        Patcher.MethodOps connectParse = connect.method("parse",
                "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V");
        connectParse.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, "zombie/network/GameServer",
                "receivePlayerConnect", rpcDesc, JOIN_METRICS, "receivePlayerConnect"));
        connectParse.expectedHits = 1;
        patches.add(connect);

        Patcher.ClassPatch connectCoop = new Patcher.ClassPatch("zombie/network/packets/connection/ConnectCoopPacket");
        Patcher.MethodOps connectCoopParse = connectCoop.method("parse",
                "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V");
        connectCoopParse.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, "zombie/network/GameServer",
                "receivePlayerConnect", rpcDesc, JOIN_METRICS, "receivePlayerConnect"));
        connectCoopParse.expectedHits = 1;
        patches.add(connectCoop);

        Patcher.ClassPatch gameServer = new Patcher.ClassPatch("zombie/network/GameServer");
        Patcher.MethodOps rpc = gameServer.method("receivePlayerConnect", rpcDesc);
        rpc.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, "zombie/savefile/ServerPlayerDB",
                "serverLoadNetworkCharacter", "(ILjava/lang/String;)Lzombie/characters/IsoPlayer;",
                JOIN_METRICS, "serverLoadNetworkCharacter"));
        rpc.expectedHits = 2;
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

        // ---- W4-1 chunk 供給併包（黑邊根因修復；docs/chunk-throughput-design-v1.md）----
        // vanilla 供給只跑到設計值 15%：client 每幀送一包（約 3 chunk）→ parse 每包無條件
        // new 一個 ccr 入列 → update() 每 worker 週期只處理一個 ccr（10Hz）＝約 30 chunk/s，
        // 而 NON_LARGE_AREA_CHUNKS_LIMIT=20 × 10Hz = 200 chunk/s 的預算浪費 85%。
        // 需求超過供給後越過 client 8 秒逾時 → client 丟棄已送達資料並重發且不通知取消
        // → 自我維持 livelock（實測 pending 恆＝請求率×8s＝240、18 分鐘燒 105MB 全丟棄）。
        // headCall 把佇列前段併包：不新增 chunk、不改順序、不碰 largeArea；隊首已含同座標者
        // 不搬（保留 vanilla 跨 ccr 去重語意）；搬空的 ccr 由同一方法後段的 vanilla 本體回收。
        // **掛點是 removeOlderDuplicateRequests()V 而非 update()V**（審查抓到的 blocking）：
        // update() 對 ccrWaiting 的存取全包在 if (workerThread.ready) 內，那是 vanilla 與
        // WorkerThread（sendArray 會 add ccrForRetries 並持續 chunks.add）互斥的唯一機制；
        // headCall 插在 offset 0 會落在閘外，與 worker 同時改同一個 plain ArrayList，最壞情況
        // 是同一 Chunk 實例雙重 releaseChunk 進 static freeChunks 池＝跨玩家汙染。
        // removeOlderDuplicateRequests 全 class 僅被 update() 呼叫一次（javap 實證）且就在
        // ready 閘內、vanilla 去重之前——正是本刀需要的位置。
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

        Patcher.ClassPatch pds = new Patcher.ClassPatch("zombie/network/PlayerDownloadServer");
        Patcher.MethodOps pdsDedupe = pds.method("removeOlderDuplicateRequests", "()V");
        pdsDedupe.headCall = new Patcher.HeadCall("zombie/mdc/ChunkRequestPacker", "packQueue",
                "(Lzombie/network/PlayerDownloadServer;)V");
        pdsDedupe.expectedHits = 1;
        patches.add(pds);

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
        patches.add(gameChr);

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
    public static List<Patcher.ClassPatch> client() {
        List<Patcher.ClassPatch> patches = new ArrayList<>();

        // 兩刀都在 waitFileTask 方法範圍內：redirect＝passthrough 觀測（水位/hwm/floor/stall，
        // 節流 log 到 console.txt）；constChange＝門檻 50MB→1GB（v1.1）。實測（Tester-A 兩場 log）
        // 證明水位地板因 ImageData 解碼例外洩漏單調上升，v1 的 256MB 天花板 ~35 分鐘被追上
        // →天花板只買時間，1GB 依實測斜率約 2–2.5 小時被追上（≈v1 跑道 ×4，無時間保證）；
        // 根治＝ImageData dispose 修補（另行實作）。
        // 高 RAM 受害 client 實驗值，≤8GB RAM 機器不適用。
        Patcher.ClassPatch tex = new Patcher.ClassPatch("zombie/core/textures/TextureIDAssetManager");
        Patcher.MethodOps wait = tex.method("waitFileTask", "()V");
        wait.redirects.add(new Patcher.Site(Opcodes.INVOKESTATIC, "zombie/core/utils/DirectBufferAllocator",
                "getBytesAllocated", "()J", "zombie/mdc/TexturePipelineGuard", "bytesAllocatedObserved"));
        wait.consts.add(new Patcher.ConstChange(52428800L, 4294967296L));
        wait.expectedHits = 2;
        patches.add(tex);

        // ---- W4-2 chunk 請求逾時 8s→30s（黑邊 livelock 斷鏈；docs/chunk-throughput-design-v1.md）----
        // resendTimedOutRequests 對超過 8000ms 未完成的請求設 flagsWs|=9 → loadReceivedChunks
        // 因 flagsWs&8 直接丟棄「已經送達、只是慢了一點」的整包資料並把 chunk 重新排隊，
        // 且不送 NotRequiredInZip 通知 server 取消 → server 繼續送作廢資料 → 供給更擠 →
        // 更多逾時。實測：pending 恆＝請求率×8s、每個卡住的 chunk 重發約 141 輪、
        // 18 分鐘燒掉約 105MB 全數丟棄、零 chunk 載入。
        // RequestZipList 與 SentChunkPacket 皆 reliability=2（RELIABLE，RakNet 保證送達），
        // 故此逾時幾乎不是在救「真的遺失」，而是在懲罰「server 慢」——放寬到 30s 讓遲到的
        // 資料被接受即可斷鏈。上界仍有限（server 真的不回時 30s 後照樣重試）。
        // 全 class 僅此一處 8000L（javap 實證），方法範圍鎖定。
        // 註：與 v2.1 的三個 ChunkStream 觀測 headCall 同屬一個 WorldStreamer ClassPatch，
        //     在下方 v2.1 區塊一併宣告（同一 class 只能有一個 ClassPatch）。

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

        // ---- v2.1 chunk 串流觀測（黑邊事件鑑識，2026-08-11 兩起實案；純觀測不改行為）----
        // 三個 headCall 全部 receiver-only：updateMain=心跳＋節流讀態＋STALL 判定，
        // receiveChunkPart/receiveNotRequired=接收計數（黑邊期間凍結＝斷流證據）。
        // 假說待驗：largeArea 停送 gate（pendingRequests1>20）×server 端 3 次重試放棄。
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
        // W4-2（見本方法上方說明）：同一 ClassPatch 內追加逾時常數 8s→30s
        Patcher.MethodOps resend = streamer.method("resendTimedOutRequests", "()V");
        resend.consts.add(new Patcher.ConstChange(8000L, 15000L));
        resend.expectedHits = 1;
        patches.add(streamer);

        return patches;
    }

    private PatchConfig() {}
}
