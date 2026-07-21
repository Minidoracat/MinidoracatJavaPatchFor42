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

    public static List<Patcher.ClassPatch> all() {
        List<Patcher.ClassPatch> patches = new ArrayList<>();

        // ---- 抑噪（redirect 到 zombie/mdc/LogFilter，過濾樣式在該類）----

        Patcher.ClassPatch asc = new Patcher.ClassPatch("zombie/characters/action/ActionStateContainer");
        Patcher.MethodOps ascM = asc.method("tryInsertChildState",
                "(Lzombie/characters/action/ActionContext;Lzombie/characters/action/ActionState;)Z");
        ascM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, DT, "warn", WARN_FMT, "warnFmt"));
        ascM.expectedHits = 2;
        patches.add(asc);

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

        Patcher.ClassPatch pkt = new Patcher.ClassPatch("zombie/network/PacketTypes$PacketType");
        Patcher.MethodOps pktM = pkt.method("onServerPacket",
                "(Lzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V");
        pktM.redirects.add(new Patcher.Site(Opcodes.INVOKEVIRTUAL, DT, "warn", WARN_FMT, "warnFmt"));
        pktM.expectedHits = 2;   // consistency＋anticheat 兩點都改道；LogFilter 只攔 consistency，anticheat 照常輸出
        patches.add(pkt);

        // ---- 行為（method 範圍內常數替換；ClassWriter 產新常數池條目，不動共享條目）----

        // 殭屍 culling 取樣率 1/3 -> 1/2（超額時回收更快；五道安全條件不動，伺服器專屬路徑）
        Patcher.ClassPatch cull = new Patcher.ClassPatch("zombie/popman/ZombieCountOptimiser");
        Patcher.MethodOps cullM = cull.method("incrementZombie", "(Lzombie/characters/IsoZombie;)V");
        cullM.consts.add(new Patcher.ConstChange(10, 6));
        cullM.expectedHits = 1;
        patches.add(cull);

        // 動物壓力三調：閒置衰減 x2、聲音壓力 /3、屠宰連鎖上限減半（clamp 與行為路徑全保留）
        Patcher.ClassPatch animal = new Patcher.ClassPatch("zombie/characters/animals/IsoAnimal");
        Patcher.MethodOps a1 = animal.method("updateStress", "()V");
        a1.consts.add(new Patcher.ConstChange(5500.0f, 2750.0f));
        a1.expectedHits = 1;
        Patcher.MethodOps a2 = animal.method("respondToSound", "()V");
        a2.consts.add(new Patcher.ConstChange(20.0f, 60.0f));
        a2.expectedHits = 1;
        Patcher.MethodOps a3 = animal.method("killed", "(Lzombie/characters/IsoPlayer;)V");
        a3.consts.add(new Patcher.ConstChange(30.0f, 15.0f));
        a3.expectedHits = 1;
        patches.add(animal);

        return patches;
    }

    private PatchConfig() {}
}
