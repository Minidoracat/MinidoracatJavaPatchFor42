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

        // 42.20 暫時移除：殭屍 culling 取樣率 1/3 -> 1/2。
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
        patches.add(animal);

        // 根因（codex 對抗審查＋v2 clamp 線上 overlap 證據 pageCount=35>readable=28、1024−814=210
        // =10×21 恰為寫側 10 筆）：this.byteBuffer 由 MCD 背景執行緒寫側（saveLock 互斥）與主執行緒
        // updateMain 讀側共用，讀側無鎖（vanilla 遺漏）→ position 併發亂跳 → 隨機欄位 underflow
        // ＋混讀損毀。v3 root fix：updateMain 內全部 10 處 getfield byteBuffer 之後同形換成
        // PopmanBufferGuard.UPDATE_MAIN_BUFFER 專用 buffer（讀寫隔離、零鎖）；count-clamp 降為
        // 保險絲（隔離後不應觸發，觸發即記 log）。寫側 beginSaveRealZombies/writeCellSnapshot 不動。
        Patcher.ClassPatch popman = new Patcher.ClassPatch("zombie/popman/ZombiePopulationManager");
        Patcher.MethodOps popmanUpdate = popman.method("updateMain", "()V");
        popmanUpdate.countClamp = new Patcher.CountClamp("zombie/popman/ZombiePopulationManager",
                "n_getAddZombieData", "(ILjava/nio/ByteBuffer;)I",
                "zombie/mdc/PopmanBufferGuard", "clampAddZombieCount");
        popmanUpdate.fieldGetSwap = new Patcher.FieldGetSwap("zombie/popman/ZombiePopulationManager",
                "byteBuffer", "Ljava/nio/ByteBuffer;",
                "zombie/mdc/PopmanBufferGuard", "updateMainBuffer");
        popmanUpdate.expectedHits = 11;   // clamp ×1 ＋ getfield swap ×10（clear、native 參數、8 讀取）
        patches.add(popman);

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

        // VehicleManager.connected 512→256：serverUpdate 每 tick 無條件掃 512 slot × 全部載具
        // （dump 5/5 停在該迴圈回跳邊 line 127），但 RakNet connectionArray 只有 256、
        // connection ID 一律 getByte()&255 解碼、UdpConnection.setIndex 全 jar 零呼叫者——
        // index 恆 <256，上半 512 slot 純空轉。<init> 的 sipush 512 改 256 直接砍半。
        // BaseVehicle.connectionState[512] 不動（同 index 界限，多餘槽位無害）。
        Patcher.ClassPatch vehMgr = new Patcher.ClassPatch("zombie/vehicles/VehicleManager");
        Patcher.MethodOps vehMgrInit = vehMgr.method("<init>", "()V");
        vehMgrInit.consts.add(new Patcher.ConstChange(512, 256));
        vehMgrInit.expectedHits = 1;
        patches.add(vehMgr);

        // ---- 效能第二波 P5（2026-08-03 解封：低谷頻率回升 >5/日、chunk 卸載重回榜首）----
        // IsoCell 三清單的 identity membership sidecar（docs/p5-chunk-unload-design-v2.md，
        // 雙審定稿＋GO-WITH-FIXES 三修正）：miss O(P)/O(S) → O(1)，removeAll O(P×R) → O(P+R)，
        // 清單保序權威不動、hit 保留 vanilla、generation bundle 生命週期、audit+kill 保險絲。
        // 15 呼叫點全部 INVOKEVIRTUAL java/util/ArrayList（javap 定案，非 12——原稿漏數雙 contains）。
        String clm = "zombie/mdc/CellListMembership";
        String arrayList = "java/util/ArrayList";
        String objBool = "(Ljava/lang/Object;)Z";

        Patcher.ClassPatch isoCell = new Patcher.ClassPatch("zombie/iso/IsoCell");
        Patcher.MethodOps cellProcess = isoCell.method("ProcessIsoObject", "()V");
        cellProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "removeAll",
                "(Ljava/util/Collection;)Z", clm, "removeAll"));
        cellProcess.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "clear",
                "()V", clm, "clear"));
        cellProcess.expectedHits = 2;
        Patcher.MethodOps cellAdd = isoCell.method("addToProcessIsoObject", "(Lzombie/iso/IsoObject;)V");
        cellAdd.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "remove", objBool, clm, "remove"));
        cellAdd.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "contains", objBool, clm, "contains"));
        cellAdd.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "add", objBool, clm, "add"));
        cellAdd.expectedHits = 3;
        Patcher.MethodOps cellAddRemove = isoCell.method("addToProcessIsoObjectRemove", "(Lzombie/iso/IsoObject;)V");
        cellAddRemove.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "contains", objBool, clm, "contains"));
        cellAddRemove.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "add", objBool, clm, "add"));
        cellAddRemove.expectedHits = 3;   // contains ×2（P 與 R 各一）＋ add ×1
        Patcher.MethodOps cellStatic = isoCell.method("addToStaticUpdaterObjectList", "(Lzombie/iso/IsoObject;)V");
        cellStatic.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "contains", objBool, clm, "contains"));
        cellStatic.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "add", objBool, clm, "add"));
        cellStatic.expectedHits = 2;
        patches.add(isoCell);

        Patcher.ClassPatch isoObjWorld = new Patcher.ClassPatch("zombie/iso/IsoObject");
        Patcher.MethodOps objRemoveWorld = isoObjWorld.method("removeFromWorld", "()V");
        objRemoveWorld.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "remove", objBool, clm, "remove"));
        objRemoveWorld.expectedHits = 1;   // staticUpdaterObjectList.remove(this) 的 O(S) miss 全掃
        patches.add(isoObjWorld);

        Patcher.ClassPatch deadBody = new Patcher.ClassPatch("zombie/iso/objects/IsoDeadBody");
        Patcher.MethodOps reanimate = deadBody.method("setReanimateTime", "(F)V");
        reanimate.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "contains", objBool, clm, "contains"));
        reanimate.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "add", objBool, clm, "add"));
        reanimate.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, arrayList, "remove", objBool, clm, "remove"));
        reanimate.expectedHits = 4;   // contains ×2（兩分支 guard）＋ add ＋ remove——經 getter 的旁路變異者，必須鏡射
        patches.add(deadBody);

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

        // ---- 受精蛋清除豁免（2026-08-04，四路獨立審查定案）----

        // 世界物品清除只比對 getFullType()（worldItemRemovalListContains＝Set.contains 精確比對），
        // 而「受精」是 Food 的 per-instance 欄位——受精蛋與一般蛋同為 Base.Egg，清單無法區分。
        // 正式服 HoursForWorldItemRemoval=24 vs 母雞 timeToHatch 504×2.5（AnimalEggHatch=5）=1260
        // 遊戲小時，地上受精蛋必定在孵化前被清掉，等於封死 Food.checkEggHatch 的地上孵化分支。
        // 改道 load 內唯一的 isIgnoreRemoveSandbox（javap：全 class 恰一處，位於 ifne→
        // GameTime.getWorldAgeHours 的清除判定鏈；且四個 listContains 都在此點之前並以 && 短路，
        // 故 helper 只在該 item 已命中清除清單時才被呼叫）——helper 先取 vanilla 值原樣放行，
        // 僅在 false 時追加「fertilized 且 animalHatch 非空且在孵化視窗內」豁免。
        // 視窗（dropTime + HATCH_WINDOW_MULTIPLIER × timeToHatch）是「不永久堆積」的唯一保證：
        // 審查證實原版三條去受精路徑（烹煮/冷凍/冷卻）對地上物品全部不可達
        // （getOutermostContainer() 對 floor container 恆回 null），詳見 helper javadoc 與 2n。
        Patcher.ClassPatch gridSquare = new Patcher.ClassPatch("zombie/iso/IsoGridSquare");
        Patcher.MethodOps squareLoad = gridSquare.method("load", "(Ljava/nio/ByteBuffer;IZ)V");
        squareLoad.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL,
                "zombie/iso/objects/IsoWorldInventoryObject", "isIgnoreRemoveSandbox", "()Z",
                "zombie/mdc/FertilizedEggGuard", "isIgnoreRemoveSandbox"));
        squareLoad.expectedHits = 1;
        patches.add(gridSquare);

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

        return patches;
    }

    private PatchConfig() {}
}
