package zombie.mdc;

import java.util.Arrays;

import zombie.core.properties.PropertyContainer;
import zombie.debug.DebugType;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;
import zombie.util.list.PZArrayList;
import zombie.core.properties.IsoPropertyType;

/**
 * 假死修復（2026-08-02 事故）：IsoGridSquare.removeGlassAttachments 的無限迴圈保險絲。
 *
 * 原版迴圈在命中「玻璃附掛物／窗牆電燈開關」時 RemoveTileObject(o) 後無條件 n--，
 * 隱含假設「移除必然使清單縮短」。42.20 的 RemoveTileObject 走
 * safelyRemoveTileObjectFromSquare 安全移除路徑，特定物件狀態下移除不生效
 * （清單不縮短）→ 同一 index 反覆命中同一物件 → 主執行緒無限迴圈 → 全服假死
 * （實測：2026-08-02 17:48，SmashWindowPacket 觸發，兩份 thread dump 佐證，
 * pkill -9 才能恢復）。
 *
 * 本 helper 逐語意重刻原版迴圈，唯一差別：RemoveTileObject 後驗證清單「真的縮短」
 * 才回退 index；未縮短（原版在此死鎖）則跳過該物件並記錄座標與 sprite——正常路徑
 * 逐指令等價，病態路徑從伺服器死亡降級為一個物件未清除＋一行定位 log。
 */
public final class GlassAttachmentGuard {

    /** IsoWindow.smashWindow 內唯一 removeGlassAttachments 呼叫點的改道目標。 */
    public static void removeGlassAttachments(IsoGridSquare square, IsoWindow window) {
        IsoDirections[] windowSides = window.getNorth()
                ? new IsoDirections[]{IsoDirections.N, IsoDirections.S}
                : new IsoDirections[]{IsoDirections.W, IsoDirections.E};

        PZArrayList<IsoObject> objects = square.getObjects();
        for (int n = 0; n < objects.size(); n++) {
            IsoObject o = objects.get(n);
            if (o.getSprite() != null) {
                PropertyContainer props = o.getProperties();
                boolean isAttachedToGlass = props.has(IsoPropertyType.ATTACHED_TO_GLASS);
                boolean isWallObject = props.has(IsoPropertyType.IS_MOVE_ABLE)
                        && (props.has(IsoPropertyType.IS_HIGH)
                            || "WallObject".equals(props.get(IsoPropertyType.MOVE_TYPE)));
                boolean isAttachedToWindowWall = isWallObject
                        && Arrays.stream(windowSides).anyMatch(x -> x == o.getFacing());
                if (isAttachedToGlass || o instanceof IsoLightSwitch && isAttachedToWindowWall) {
                    int before = objects.size();
                    square.RemoveTileObject(o);
                    if (objects.size() < before) {
                        n--;   // 原版語意：移除成功、清單左移，回退 index
                    } else {
                        // 原版在此無限迴圈——跳過並定位問題物件
                        IsoSprite sprite = o.getSprite();
                        DebugType.Multiplayer.println("[MinidoracatJavaPatch][GlassGuard] "
                                + "stuck glass attachment skipped at " + square.getX() + ","
                                + square.getY() + "," + square.getZ() + " sprite="
                                + (sprite != null ? sprite.getName() : "null"));
                    }
                }
            }
        }
    }

    private GlassAttachmentGuard() {}
}
