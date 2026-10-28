package zombie.network.packets;

import zombie.core.MdcTimedActionProbe;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.objects.IsoLightSwitch;
import zombie.network.IConnection;

/**
 * W82 燈具設定同步的伺服器端驗證（docs/patches.md 2cq）。
 *
 * <p>客戶端送 {@code SyncCustomLightSettings} 的原版來源只有一個：子彈打掉燈泡（{@code CombatManager.shootPlacedItems}，
 * 燈泡改成 null）。電池、燈泡、顏色等其餘設定都由伺服器端動作改完再廣播。所以從客戶端來的這個封包只接受兩種改動：
 * {@code activated} true→false、燈泡 有→null；其他欄位保留伺服器現值。
 *
 * <p>掛點都在 {@code SyncCustomLightSettingsPacket.parse}：頭部 {@link #begin} 綁定連線；唯一的 {@code activated} 欄位讀取
 * 改道 {@link #activated}，不接受的 true 直接把封包欄位改回 false（之後原版轉發的 {@code write} 與 {@code processClient}
 * 的 {@code switchLight} 都讀這個欄位）；九個 {@code IsoLightSwitch} setter 改道同名 helper，不接受的值不寫入，原版
 * {@code write} 轉發的就是伺服器現值。最後一個 setter（{@link #setPrimaryB}）把本包被擋的欄位記成一行 log。
 *
 * <p>開關：{@code -Dmdc.lightSettingsCheck=0|off} 回原版，預設開，需重啟。
 */
public final class MdcLightSettingsGuard {
    private static final String TAG = "[MinidoracatJavaPatch][LightSettingsGuard]";

    static final boolean ENABLED = flag("mdc.lightSettingsCheck");
    /** 伺服器扣電後才同步給客戶端，客戶端的電量會略落後；差在這以內不記 log。只影響要不要記 log，不影響擋不擋。 */
    private static final float POWER_TOLERANCE = 0.01F;

    private static final String[] FIELD_NAMES = {
            "activated", "canBeModified", "useBattery", "hasBattery", "bulbItem",
            "power", "delta", "primaryR", "primaryG", "primaryB"};

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;

    // 封包處理在主迴圈單執行緒；計數與時間窗容忍罕見交錯。
    private static IConnection connection;
    private static int refusedMask;
    private static long refused;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;

    private MdcLightSettingsGuard() {
    }

    /** {@code parse} 頭部：綁定送封包的連線、清掉上一包的紀錄。 */
    public static void begin(IConnection c) {
        connection = c;
        refusedMask = 0;
    }

    /** {@code parse} 內唯一 {@code GETFIELD activated} 的改道（只在燈存在時執行）：只接受 true→false。 */
    public static boolean activated(SyncCustomLightSettingsPacket packet) {
        if (ENABLED && packet.activated && !((IsoLightSwitch) packet.netObject.getObject()).isActivated()) {
            packet.activated = false;
            refusedMask |= 1;
        }
        return packet.activated;
    }

    public static void setCanBeModified(IsoLightSwitch light, boolean value) {
        if (!ENABLED) {
            light.setCanBeModified(value);
        } else if (value != light.getCanBeModified()) {
            refusedMask |= 1 << 1;
        }
    }

    public static void setUseBatteryDirect(IsoLightSwitch light, boolean value) {
        if (!ENABLED) {
            light.setUseBatteryDirect(value);
        } else if (value != light.getUseBattery()) {
            refusedMask |= 1 << 2;
        }
    }

    public static void setHasBattery(IsoLightSwitch light, boolean value) {
        if (!ENABLED) {
            light.setHasBattery(value);
        } else if (value != light.getHasBattery()) {
            refusedMask |= 1 << 3;
        }
    }

    /**
     * 兩個呼叫點（有燈泡字串／null）共用：只接受改成 null。燈泡改 null 的就是原版打燈封包：看到這發子彈的其他客戶端
     * 也會重播曳光、各自送一包，晚到的那包燈已被伺服器關掉，帶的 activated=true 照樣被擋，但不算被擋欄位（不記 log）。
     */
    public static void setBulbItemRaw(IsoLightSwitch light, String item) {
        if (!ENABLED || item == null) {
            light.setBulbItemRaw(item);
            refusedMask &= ~1;
        } else if (!item.equals(light.getBulbItem())) {
            refusedMask |= 1 << 4;
        }
    }

    public static void setPower(IsoLightSwitch light, float value) {
        if (!ENABLED) {
            light.setPower(value);
        } else if (!(Math.abs(value - light.getPower()) <= POWER_TOLERANCE)) {
            refusedMask |= 1 << 5;
        }
    }

    public static void setDelta(IsoLightSwitch light, float value) {
        if (!ENABLED) {
            light.setDelta(value);
        } else if (Float.compare(value, light.getDelta()) != 0) {
            refusedMask |= 1 << 6;
        }
    }

    public static void setPrimaryR(IsoLightSwitch light, float value) {
        if (!ENABLED) {
            light.setPrimaryR(value);
        } else if (Float.compare(value, light.getPrimaryR()) != 0) {
            refusedMask |= 1 << 7;
        }
    }

    public static void setPrimaryG(IsoLightSwitch light, float value) {
        if (!ENABLED) {
            light.setPrimaryG(value);
        } else if (Float.compare(value, light.getPrimaryG()) != 0) {
            refusedMask |= 1 << 8;
        }
    }

    /** parse 的最後一個 setter：之後記下本包被擋的欄位。 */
    public static void setPrimaryB(IsoLightSwitch light, float value) {
        if (!ENABLED) {
            light.setPrimaryB(value);
            return;
        }
        if (Float.compare(value, light.getPrimaryB()) != 0) {
            refusedMask |= 1 << 9;
        }
        if (refusedMask != 0) {
            refused++;
            report(light);
        }
        refusedMask = 0;
        connection = null;
    }

    private static void report(IsoLightSwitch light) {
        try {
            if (!allowLine()) {
                return;
            }
            StringBuilder fields = new StringBuilder();
            for (int i = 0; i < FIELD_NAMES.length; i++) {
                if ((refusedMask & (1 << i)) != 0) {
                    if (fields.length() > 0) {
                        fields.append(',');
                    }
                    fields.append(FIELD_NAMES[i]);
                }
            }
            DebugLog.log(TAG + " refused fields=" + fields
                    + " connectionPlayers=" + (connection instanceof UdpConnection u ? MdcTimedActionProbe.connectionPlayers(u) : "?")
                    + " at=" + light.getX() + "," + light.getY() + "," + light.getZ()
                    + " refused=" + refused + " suppressed=" + suppressed + " anomalies=" + anomalies + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static boolean allowLine() {
        long now = System.nanoTime();
        if (windowStartNs == 0L || now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowCount = 0;
        }
        if (windowCount >= WINDOW_CAP) {
            suppressed++;
            return false;
        }
        windowCount++;
        return true;
    }

    private static boolean flag(String name) {
        String raw = System.getProperty(name);
        if (raw == null) {
            return true;
        }
        String v = raw.trim();
        return !("0".equals(v) || "off".equalsIgnoreCase(v));
    }

    // ---- 測試存取器 ----

    static long refusedForTest() {
        return refused;
    }

    static long anomaliesForTest() {
        return anomalies;
    }
}
