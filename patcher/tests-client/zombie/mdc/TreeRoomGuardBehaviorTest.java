package zombie.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.RoomDef;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;
import zombie.iso.objects.IsoTree;

/**
 * 自建房間 XL 樹例外修補的行為驗證（裸 JVM；classpath 以修補後的 IsoTree 優先）。
 * 遊戲物件以 Unsafe 配置、不跑建構子，只補齊 isPlayerInsideARoom 會讀到的欄位。
 * IsoRegions 區域用真的 IsoWorldRegion（封閉且屋頂全滿），透過格子的區域快取接上；
 * 同一組狀態在原版 42.21.0 會拋出與玩家 log 相同的 NPE。
 */
public final class TreeRoomGuardBehaviorTest {

    public static void main(String[] args) throws Exception {
        int failed = 0;
        Method inside = IsoTree.class.getDeclaredMethod("isPlayerInsideARoom", IsoPlayer.class);
        inside.setAccessible(true);
        IsoTree tree = alloc(IsoTree.class);
        IsoPlayer player = alloc(IsoPlayer.class);

        IsoWorldRegion region = alloc(IsoWorldRegion.class);
        set(IsoWorldRegion.class, region, "enclosed", true);
        set(IsoWorldRegion.class, region, "squareSize", 4);
        set(IsoWorldRegion.class, region, "roofCnt", 4);
        IsoGridSquare built = square(10, 10, 1, -1L, null);
        set(IsoGridSquare.class, built, "isoWorldRegion", region);
        set(IsoGridSquare.class, built, "hasSetIsoWorldRegion", true);
        stand(player, built);
        failed += check("前提：自建房間 isInARoom 為真、getRoom 為 null",
                player.isInARoom() && player.getSquare().getRoom() == null);
        tree.square = square(12, 12, 0, -1L, null);
        failed += check("自建房間：回 false，不拋例外", Boolean.FALSE.equals(invoke(inside, tree, player)));
        failed += check("自建房間：再呼叫一次結果相同", Boolean.FALSE.equals(invoke(inside, tree, player)));

        IsoRoom room = new IsoRoom();
        room.rects.add(new RoomDef.RoomRect(100, 200, 10, 10));
        stand(player, square(105, 205, 0, 7L, room));
        tree.square = square(105, 205, 0, -1L, null);
        failed += check("預製房間：樹在房間範圍內回 true（原版行為）",
                Boolean.TRUE.equals(invoke(inside, tree, player)));
        tree.square = square(50, 50, 0, -1L, null);
        failed += check("預製房間：樹在範圍外回 false（原版行為）",
                Boolean.FALSE.equals(invoke(inside, tree, player)));

        IsoGridSquare outdoors = square(300, 300, 0, -1L, null);
        set(IsoGridSquare.class, outdoors, "hasSetIsoWorldRegion", true);
        stand(player, outdoors);
        failed += check("室外：回 false", Boolean.FALSE.equals(invoke(inside, tree, player)));

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("TreeRoomGuard 行為驗證全數通過");
    }

    /** 回傳方法結果；拋例外時印出原因並回 null（該項斷言因此失敗）。 */
    static Boolean invoke(Method inside, IsoTree tree, IsoPlayer player) throws IllegalAccessException {
        try {
            return (Boolean) inside.invoke(tree, player);
        } catch (InvocationTargetException e) {
            System.out.println("  threw " + e.getCause());
            return null;
        }
    }

    static void stand(IsoPlayer player, IsoGridSquare square) throws Exception {
        player.square = square;
        set(IsoMovingObject.class, player, "current", square);
    }

    static IsoGridSquare square(int x, int y, int z, long roomId, IsoRoom room) throws Exception {
        IsoGridSquare square = alloc(IsoGridSquare.class);
        square.x = x;
        square.y = y;
        square.z = z;
        square.roomId = roomId;
        square.room = room;
        return square;
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(type);
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "behavior OK   " : "behavior FAIL ") + what);
        return ok ? 0 : 1;
    }

    private TreeRoomGuardBehaviorTest() {}
}
