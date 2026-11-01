package zombie.mdc;

import java.util.Locale;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;

/**
 * W91 {@code checkIsNearVehicle} 只在潛行時跑（2026-11-01；docs/patches.md 2cz）。
 *
 * <p><b>背景</b>：{@code IsoPlayer.updateInternal2} 兩處（javap offset 897、1263）{@code invokevirtual checkIsNearVehicle()Z}
 * 後面都接 {@code pop}，回傳值沒人用。{@code IsoGameCharacter.checkIsNearVehicle} 每位玩家每幀走完整個 {@code getVehicles()}
 * 算 {@code DistTo}，唯一的副作用是 {@code sneaking} 時 {@code setNearWallCrouching(true)}；不潛行時什麼都不寫（不會設成 false）。
 *
 * <p><b>改什麼</b>：兩處同形改道 {@link #checkIsNearVehicle}：潛行時照原版呼叫；不潛行時 on 直接回 false（回傳值反正被 pop）。
 *
 * <p><b>模式</b>（{@code -Dmdc.nearVehicleSneakGate}，需重啟）：{@code 2|observe}（預設）照原版呼叫，不潛行時確認呼叫前後
 * {@code nearWallCrouching} 不變；{@code 1|on} 不潛行時跳過，每 4096 次跳過抽一次照原版呼叫並做同樣檢查；變了就本次啟動永久回原版；
 * {@code 0|off} 原版；未知值當 observe。心跳每 5 分鐘一行，停用後照印。
 */
public final class NearVehicleSneakGate {

    static final int OFF = 0;
    static final int ON = 1;
    static final int OBSERVE = 2;
    static final String RAW = System.getProperty("mdc.nearVehicleSneakGate");
    static final int MODE = parseMode(RAW);
    static final int AUDIT_MASK = 4095;

    private static final String TAG = "[MinidoracatJavaPatch][NearVehicleSneakGate] ";
    private static final long HEARTBEAT_MS = 300_000L;

    private static volatile boolean disabled;
    private static boolean started;
    private static long lastBeat;
    private static long calls, sneaking, skipped, skipSeen, checked, mismatches, anomalies;
    static String lastBeatLine;

    static int parseMode(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            case "1", "on" -> ON;
            default -> OBSERVE;
        };
    }

    /** {@code IsoPlayer.updateInternal2} 的兩個 {@code checkIsNearVehicle()}（之後 pop）。 */
    public static boolean checkIsNearVehicle(IsoPlayer p) {
        if (MODE == OFF) {
            return p.checkIsNearVehicle();
        }
        if ((++calls & 1023) == 0) {
            tick();
        }
        if (disabled) {
            return p.checkIsNearVehicle();
        }
        if (p.isSneaking()) {
            sneaking++;
            return p.checkIsNearVehicle();
        }
        if (MODE == ON && (++skipSeen & AUDIT_MASK) != 0) {
            skipped++;
            return false;
        }
        checked++;
        boolean before = p.isNearWallCrouching();
        boolean r = p.checkIsNearVehicle();
        if (p.isNearWallCrouching() != before) {
            mismatches++;
            disable("nearWallCrouching " + before + "→" + !before + " while not sneaking, player=" + p.getOnlineID());
        }
        return r;
    }

    private static void tick() {
        try {
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                lastBeat = now;
                log("首次生效 mode=" + modeName() + " raw=" + RAW
                        + "（-Dmdc.nearVehicleSneakGate=0|off 原版、1|on 不潛行時跳過、2|observe 只檢查；心跳每 5 分鐘一行）");
            }
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
        }
    }

    private static void disable(String reason) {
        if (disabled) {
            return;
        }
        disabled = true;
        log("DISABLED（本次啟動永久回原版）reason=" + reason + " skipped=" + skipped + " checked=" + checked);
    }

    private static void beat() {
        String line = "beat mode=" + modeName() + " calls=" + calls + " sneaking=" + sneaking + " skipped=" + skipped
                + " checked=" + checked + " mismatches=" + mismatches + " anomalies=" + anomalies + " disabled=" + disabled;
        lastBeatLine = line;
        log(line);
        calls = sneaking = skipped = checked = 0;
    }

    static String modeName() {
        return MODE == OFF ? "off" : MODE == ON ? "on" : "observe";
    }

    private static void log(String s) {
        DebugLog.log(TAG + s);
    }

    static void forceBeatForTest() {
        started = true;
        lastBeat = System.currentTimeMillis() - HEARTBEAT_MS;
        tick();
    }

    static boolean disabledForTest() { return disabled; }
    static long skippedForTest() { return skipped; }
    static long checkedForTest() { return checked; }
    static long sneakingForTest() { return sneaking; }

    private NearVehicleSneakGate() {}
}
