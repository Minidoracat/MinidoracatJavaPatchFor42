package zombie.mdc;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;

/**
 * 自建房間 XL 樹例外修補（42.21.0；docs/patches.md 2bl）。
 *
 * 42.21 新增的 IsoTree.isPlayerInsideARoom 在 isInARoom() 為真時，直接取
 * getSquare().getRoom().getRectsBounds()。但 IsoGridSquare.isInARoom() 另有 IsoRegions
 * 「封閉且屋頂全滿」這條分支：緊貼或疊在預製建築上的自建房間，client 端的 user-defined
 * building 會被丟棄，所以格子沒有 IsoRoom。結果每一幀都 NPE，FBORenderCell.renderInternal
 * 接住例外後，這一幀剩下的物件都不會畫出來。
 *
 * 本方法取代該方法內唯一的 isInARoom 呼叫。原版不拋例外時結果相同；原版會 NPE 時改回 false，
 * 這種房間裡的 XL 樹就不套用室內淡化（行為同 42.20.4）。
 */
public final class TreeRoomGuard {

    private static boolean reported;

    public static boolean isInARoom(IsoPlayer player) {
        if (!player.isInARoom()) {
            return false;
        }
        IsoGridSquare square = player.getSquare();
        if (square != null && square.getRoom() != null) {
            return true;
        }
        if (!reported) {
            reported = true;
            try {
                DebugLog.log("[MinidoracatJavaPatch][TreeRoomGuard] room without IsoRoom"
                        + (square == null ? "" : " at " + square.x + "," + square.y + "," + square.z)
                        + "; XL tree room fade skipped");
            } catch (RuntimeException ignored) {
                // 診斷行寫不出來也不能讓例外回到繪製路徑。
            }
        }
        return false;
    }

    private TreeRoomGuard() {}
}
