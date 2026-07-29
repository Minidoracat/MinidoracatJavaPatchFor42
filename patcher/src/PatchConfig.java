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

        return patches;
    }

    private PatchConfig() {}
}
