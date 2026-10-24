import java.util.ArrayList;
import java.util.Random;

import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.objects.IsoDeadBody;
import zombie.vehicles.BaseVehicle;

/**
 * W70 測試替身：{@link BanditRaidLoopGuardTest} 把 {@code RDSBanditRaid.randomizeDeadSurvivor} 內碰世界與亂數的呼叫
 * 換成這裡的同形 static 方法；方法本身的迴圈、計數器與分支（含 W70 的 floor 改道）照原 bytecode 跑。
 * 由遊戲 class loader 載入（簽名用到遊戲型別），狀態由測試以反射讀寫。
 */
public final class BanditRaidProbe {
    /** 超過就判定為停不下來的迴圈。 */
    static int limit = 5000;
    /** 取房間的替身回 null 的機率（0＝大樓裡永遠找得到房間）。 */
    static double nullRoom;
    static Random rnd = new Random(1);
    static StringBuilder trace = new StringBuilder();
    static int events;
    static final RoomDef ROOM = new RoomDef(1L, "livingroom");

    private BanditRaidProbe() {
    }

    public static void reset(long seed, double nullRoomChance, int maxEvents) {
        rnd = new Random(seed);
        nullRoom = nullRoomChance;
        limit = maxEvents;
        trace = new StringBuilder();
        events = 0;
    }

    private static void event(String what) {
        if (++events > limit) {
            throw new IllegalStateException("LOOP_LIMIT");
        }
        trace.append(what).append(' ');
    }

    private static RoomDef room(String what) {
        RoomDef r = rnd.nextDouble() < nullRoom ? null : ROOM;
        event(what + (r == null ? "0" : "1"));
        return r;
    }

    public static IsoWorld world() {
        return null;
    }

    public static IsoCell cell(IsoWorld world) {
        return null;
    }

    public static IsoGridSquare square(IsoCell cell, int x, int y, int z) {
        return null;
    }

    public static int next(int max) {
        int v = rnd.nextInt(Math.max(1, max));
        event("n" + v);
        return v;
    }

    public static int next2(int min, int max) {
        int v = max <= min ? min : min + rnd.nextInt(max - min);
        event("m" + v);
        return v;
    }

    public static boolean nextBool(int invProbability) {
        boolean v = rnd.nextInt(Math.max(1, invProbability)) == 0;
        event("b" + (v ? 1 : 0));
        return v;
    }

    public static BaseVehicle spawnCarOnNearestNav(Object self, String script, BuildingDef def) {
        event("V");
        return null;
    }

    public static ArrayList addZombies(Object self, BuildingDef def, int count, String outfit, Integer female, RoomDef room) {
        event("Z" + count + (room == null ? "-" : "+"));
        ArrayList<Object> zombies = new ArrayList<>();
        zombies.add(null);
        return zombies;
    }

    public static RoomDef getLivingRoomOrKitchen(Object self, BuildingDef def) {
        return room("L");
    }

    public static RoomDef getRandomRoomNoKids(Object self, BuildingDef def, int minSize) {
        return room("K");
    }

    public static RoomDef getRandomRoom(Object self, BuildingDef def, int minSize) {
        return room("R");
    }

    public static IsoGridSquare getRandomSquareForCorpse(RoomDef room) {
        event("Q");
        return null;
    }

    public static IsoDeadBody createRandomDeadBody(IsoGridSquare sq, IsoDirections dir, int blood, int crawler, String outfit) {
        event("C");
        return null;
    }
}
