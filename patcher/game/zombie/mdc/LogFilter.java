package zombie.mdc;

import zombie.debug.DebugLog;
import zombie.debug.DebugType;

/**
 * 噪音過濾器（隨 patch 以 loose class 出貨；由被改道的呼叫點進入）。
 * 原則：只攔「已知噪音」，其餘一律轉發原始 log 呼叫——寧漏不誤。
 * 比對策略：能拿到完整字面值的用 equals（零誤攔），動態尾綴的用 startsWith。
 * 樣式出處：docs/specs/*.json（javap 證據）；docs/patches.md 有對照表。
 */
public final class LogFilter {

    /** warn(String format, Object[]) 呼叫點——比對「格式化前」的完整 format 常數（equals）。 */
    private static final String[] FMT_EXACT = {
        "%s> Transition's target state \"%s\" not supported by parent: \"%s\"",          // ActionStateContainer
        "AnimState not found: %s",                                                       // AnimationSet
        "SkeletonBone not resolved for bone: %s, defaulting to SkeletonBone.None",       // SkinningBoneHierarchy
        "The packet %s is not consistent: %s",                                           // PacketTypes$PacketType
    };

    /** warn(Object) 呼叫點——訊息已組字串：完整訊息用 equals、動態長串用 startsWith。 */
    private static final String[] OBJ_EXACT = {
        "Invalid SpriteConfig object! scripted object = MetalBigWireFence",              // SpriteConfig（選擇性）
        "Invalid SpriteConfig object! scripted object = WoodFloorLvl3",
        "Invalid SpriteConfig object! scripted object = Wooden_Windows",
    };
    private static final String[] OBJ_PREFIX = {
        "No packet handler for type:",                                                   // PacketsCache <init>
    };

    /** DebugLog.log(String) 呼叫點。 */
    private static final String[] LOG_EXACT = {
        "moveZombie: There are no zombies in nz.zombies.",                               // NetworkZombieManager
    };
    private static final String[] LOG_PREFIX = {
        "ItemPickInfo -> cannot get ID for ",                                            // ItemPickInfo（debug 診斷前綴不同、照常轉發）
    };

    public static void warnFmt(DebugType type, String format, Object[] args) {
        if (format != null) {
            for (String p : FMT_EXACT) {
                if (format.equals(p)) {
                    return;
                }
            }
        }
        type.warn(format, args);
    }

    public static void warnObj(DebugType type, Object message) {
        String s = String.valueOf(message);
        for (String p : OBJ_EXACT) {
            if (s.equals(p)) {
                return;
            }
        }
        for (String p : OBJ_PREFIX) {
            if (s.startsWith(p)) {
                return;
            }
        }
        type.warn(message);
    }

    public static void log(String message) {
        if (message != null) {
            for (String p : LOG_EXACT) {
                if (message.equals(p)) {
                    return;
                }
            }
            for (String p : LOG_PREFIX) {
                if (message.startsWith(p)) {
                    return;
                }
            }
        }
        DebugLog.log(message);
    }

    private LogFilter() {}
}
