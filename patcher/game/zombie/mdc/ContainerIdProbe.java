package zombie.mdc;

import zombie.debug.DebugLog;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoObject;

/**
 * W20 容器封包定位觀測（(a) ContainerID square-null NPE 的分解探針；docs/patches.md 2ah）。
 *
 * <p>背景：`ContainerID.set(ItemContainer, IsoObject)` 的 ObjectContainer/IsoObject 分支直讀
 * `o.square.getObjects()`（javap offset 197/233）<b>無 null 守衛</b>——8/28 當輪 362 條
 * `INetworkPacket.send> Exception thrown` 的主體即此 NPE（per-connection 放大）。已知
 * 上游鏈：非 IsoPlayer 角色（殭屍/屍體生命週期）衣物破損 → `Clothing.setCondition` →
 * `Unwear(true)` → `GameServer.sendRemoveItemFromContainer` 走 sendToRelative 分支。
 * <b>square 矛盾的根源（codex lane 定位）</b>：`Unwear` 用 `c.getSquare()!=null` 放行
 * （`IsoMovingObject.getSquare()` 回 `current ?: square`），ContainerID 卻直讀 raw
 * `square` field——IsoGameCharacter 建構只填 current、raw square 可為 null。NPE 被
 * `INetworkPacket.send` :130 per-connection catch 吞掉後 `Unwear` 的 `inventory.Remove`
 * ＋`AddWorldInventoryItem` 照常執行 ⇒ client 未收到移除通知＝黏性 desync（可能同時
 * 保留身上副本又看到地面副本）。
 *
 * <p><b>本刀純觀測、不修</b>：修復要動封包定位語意（改用 getSquare()、或 null 時換
 * ContainerType fallback），影響所有容器封包，須本探針分解數據後另案。掛點＝雙參 set
 * 頭部 headCall（slots={1,2}，vanilla 任何一行前）。高頻點（所有容器封包定位都經此）：
 * 正常路徑只 `calls++`（零額外成本）；`o.square == null` 才收集詳情（o class、
 * getSquare() 是否有值＝current-vs-square 指紋、container parent/type、StackWalker
 * caller），rate-limited 每 10s 窗 20 行；heartbeat 每 4096 呼叫檢查、60s 一行。
 *
 * <p>例外紀律：主 try 只 catch RuntimeException（觀測不得影響封包定位），LinkageError
 * 外逃 fail-fast。kill switch：{@code -Dmdc.containerIdProbe}＝{@code 0|off}／
 * {@code 2|observe}（預設；{@code 1}＝observe-alias，本刀無 enforce 語意）。
 */
public final class ContainerIdProbe {
    private static final String TAG = "[MinidoracatJavaPatch][ContainerIdProbe]";

    static final int MODE_OFF = 0;
    static final int MODE_OBSERVE = 2;

    static final int MODE = parseMode();

    private static final long WINDOW_NS = 10_000_000_000L;
    private static final int WINDOW_CAP = 20;
    private static final long BEAT_NS = 60_000_000_000L;

    private static long calls;
    private static long squareNull;
    private static long objectNull;
    private static long logged;
    private static long suppressed;
    private static long anomalies;
    private static long windowStartNs;
    private static int windowCount;
    private static long lastBeatNs;
    private static boolean bannerShown;

    private ContainerIdProbe() {
    }

    /** headCall（slots={1,2}）目標：ContainerID.set(ItemContainer, IsoObject) 頭部。 */
    public static void onSet(ItemContainer container, IsoObject o) {
        if (MODE == MODE_OFF) {
            return;
        }
        try {
            calls++;
            if (!bannerShown) {
                showBanner();
            }
            if (o == null) {
                objectNull++;
            } else if (o.square == null) {
                squareNull++;
                if (allowLine()) {
                    DebugLog.log(TAG + " squareNull#" + squareNull
                            + " o=" + o.getClass().getName()
                            + " getSquare=" + (o.getSquare() != null ? "non-null(current)" : "null")
                            + " container=" + describeContainer(container)
                            + " caller=" + firstForeignFrame(Thread.currentThread().getStackTrace())
                            + " suppressed=" + suppressed + ".");
                }
            }
        } catch (RuntimeException e) {
            anomalies++;
        }
        maybeBeat();
    }

    private static String describeContainer(ItemContainer container) {
        try {
            if (container == null) {
                return "null";
            }
            String type = String.valueOf(container.getType());
            Object parent = container.getParent();
            return type + "/" + (parent == null ? "null" : parent.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 第一個非本刀、非 ContainerID、非 Thread 的 frame＝定位呼叫的直接上游。 */
    static String firstForeignFrame(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            String cls = f.getClassName();
            if (cls.startsWith("zombie.mdc.")
                    || cls.equals("java.lang.Thread")
                    || cls.equals("zombie.network.fields.ContainerID")) {
                continue;
            }
            return cls + "." + f.getMethodName() + ":" + f.getLineNumber();
        }
        return "?";
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
        logged++;
        return true;
    }

    private static void showBanner() {
        bannerShown = true;
        DebugLog.log(TAG + " 首次生效 mode=" + MODE
                + "（-Dmdc.containerIdProbe=0|off 停用；2|observe 預設——只在 o.square==null 記詳情）.");
    }

    /** heartbeat：每 4096 呼叫才讀時鐘、60s 一行（高頻點慣例，比照 AnimalLosGate）。 */
    private static void maybeBeat() {
        if ((calls & 0xFFFL) != 0L) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (lastBeatNs != 0L && now - lastBeatNs < BEAT_NS) {
                return;
            }
            lastBeatNs = now;
            DebugLog.log(TAG + " beat calls=" + calls + " squareNull=" + squareNull
                    + " objectNull=" + objectNull + " logged=" + logged
                    + " suppressed=" + suppressed + " anomalies=" + anomalies
                    + " mode=" + MODE + ".");
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static int parseMode() {
        String raw = System.getProperty("mdc.containerIdProbe");
        if (raw == null) {
            return MODE_OBSERVE;
        }
        switch (raw.trim()) {
            case "0":
            case "off":
                return MODE_OFF;
            default:
                return MODE_OBSERVE;
        }
    }

    // ---- 測試存取器 ----

    static long callsForTest() {
        return calls;
    }

    static long squareNullForTest() {
        return squareNull;
    }

    static long objectNullForTest() {
        return objectNull;
    }

    static long anomaliesForTest() {
        return anomalies;
    }

    static long suppressedForTest() {
        return suppressed;
    }

    static void resetWindowForTest() {
        windowStartNs = 0L;
        windowCount = 0;
    }
}
