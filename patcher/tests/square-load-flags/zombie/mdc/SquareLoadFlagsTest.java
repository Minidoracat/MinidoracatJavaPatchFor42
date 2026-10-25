package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.util.list.PZArrayList;

/**
 * W71 SquareLoadFlags 行為驗證（獨立 JVM；argv：jar on|off，off 須帶 {@code -Dmdc.squareLoadFlags=off}）。
 * 走 dist 內手術後的真 {@code IsoGridSquare.mdcLoadFlags()}（反射呼叫：編譯期看到的是原版 class）。
 *
 * <p>鎖：原版 class 沒有 {@code mdcLoadFlags}（負對照）；patched 有且只有一個 public 非 static {@code ()I}。
 * on：無／只有地上物品／只有容器／兩者四種組合回 0／1／2／3，有次要容器的物件也算容器、容器在後面的物件也找得到，
 * 每格與原版 API（{@code getWorldObjects().isEmpty()}、逐物件 {@code getContainerCount() > 0}）逐項相同，熱迴圈不配置物件。
 * off：一律 -1。
 */
public final class SquareLoadFlagsTest {
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        boolean on = !(args.length > 1 && "off".equals(args[1]));
        expect("開關與 argv 相符（" + (on ? "on" : "off") + "）", SquareLoadFlags.ENABLED == on);

        try (URLClassLoader vanilla = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> vSquare = Class.forName("zombie.iso.IsoGridSquare", false, vanilla);
            boolean missing;
            try {
                vSquare.getMethod("mdcLoadFlags");
                missing = false;
            } catch (NoSuchMethodException e) {
                missing = true;
            }
            expect("原版負對照：jar 裡的 IsoGridSquare getMethod(\"mdcLoadFlags\") 丟 NoSuchMethodException",
                    vSquare.getClassLoader() == vanilla && missing);
        }

        Method m = IsoGridSquare.class.getMethod("mdcLoadFlags");
        expect("patched：public 非 static ()I，getMethods 恰一個 mdcLoadFlags（Kahlua 以 getMethods 曝露）",
                m.getReturnType() == int.class && Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                        && Arrays.stream(IsoGridSquare.class.getMethods()).filter(x -> x.getName().equals("mdcLoadFlags")).count() == 1);

        IsoGridSquare none = square();
        place(none, plain());
        IsoGridSquare world = square();
        place(world, plain());
        dropItem(world);
        IsoGridSquare box = square();
        place(box, withContainer());
        IsoGridSquare both = square();
        place(both, withContainer());
        dropItem(both);
        IsoGridSquare empty = square();
        IsoObject secondary = withContainer();
        secondary.addSecondaryContainer(container());
        IsoGridSquare twoBoxes = square();
        place(twoBoxes, secondary);
        IsoObject onlySecondary = plain();
        onlySecondary.addSecondaryContainer(container());
        IsoGridSquare secondaryOnly = square();
        place(secondaryOnly, onlySecondary);
        IsoGridSquare late = square();
        place(late, plain());
        place(late, plain());
        place(late, withContainer());

        Object[][] cases = {
                {"空格子（沒有物件）", empty, 0},
                {"無：只有普通物件", none, 0},
                {"只有地上物品", world, 1},
                {"只有容器", box, 2},
                {"地上物品＋容器", both, 3},
                {"物件有主容器＋次要容器", twoBoxes, 2},
                {"物件只有次要容器（getContainerCount=1）", secondaryOnly, 2},
                {"容器在第 3 個物件", late, 2},
        };
        for (Object[] c : cases) {
            IsoGridSquare sq = (IsoGridSquare) c[1];
            int got = (Integer) m.invoke(sq);
            if (on) {
                expect("on " + c[0] + "：回 " + c[2] + "（實際 " + got + "）", got == (Integer) c[2]);
                expect("on " + c[0] + "：與原版 API 逐項相同、其他 bit 為 0", got == vanillaFlags(sq));
            } else {
                expect("off " + c[0] + "：回 -1（實際 " + got + "）", got == -1);
            }
        }
        if (on) {
            expect("on：只有次要容器的物件 getObjectContainers 收不到（主容器才收），mdcLoadFlags 照上游 getContainerCount 算容器",
                    secondaryOnly.getObjectContainers(null, null, new PZArrayList<>(ItemContainer.class, 4)).isEmpty());
            expect("on：熱迴圈不配置物件（100 萬次 flags）", allocatedBytes(late) == 0L);
        }

        System.out.println("SquareLoadFlagsTest " + (on ? "on" : "off") + "：" + (failed == 0 ? "全部通過" : failed + " 項失敗"));
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 原版 API 算出的旗標：VFE 上游的兩個判斷。 */
    private static int vanillaFlags(IsoGridSquare sq) {
        int flags = sq.getWorldObjects().isEmpty() ? 0 : 1;
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i).getContainerCount() > 0) {
                flags |= 2;
            }
        }
        return flags;
    }

    private static long allocatedBytes(IsoGridSquare sq) {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        int sink = 0;
        for (int i = 0; i < 200_000; i++) {
            sink += SquareLoadFlags.flags(sq);
        }
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1_000_000; i++) {
            sink += SquareLoadFlags.flags(sq);
        }
        long after = mx.getCurrentThreadAllocatedBytes();
        expect("熱迴圈結果一致", sink == 2 * 1_200_000);
        return after - before;
    }

    private static IsoGridSquare square() throws Exception {
        IsoGridSquare sq = (IsoGridSquare) raw(IsoGridSquare.class);
        set(IsoGridSquare.class, sq, "objects", new PZArrayList<>(IsoObject.class, 4));
        set(IsoGridSquare.class, sq, "worldObjects", new ArrayList<IsoWorldInventoryObject>());
        return sq;
    }

    /** 地上物品在引擎裡同時在 objects 與 worldObjects（它本身沒有容器）。 */
    private static void dropItem(IsoGridSquare sq) throws Exception {
        IsoWorldInventoryObject item = (IsoWorldInventoryObject) raw(IsoWorldInventoryObject.class);
        place(sq, item);
        sq.getWorldObjects().add(item);
    }

    private static void place(IsoGridSquare sq, IsoObject o) {
        o.square = sq;
        sq.getObjects().add(o);
    }

    private static IsoObject plain() throws Exception {
        return (IsoObject) raw(IsoObject.class);
    }

    private static IsoObject withContainer() throws Exception {
        IsoObject o = plain();
        o.container = container();
        return o;
    }

    private static ItemContainer container() throws Exception {
        return (ItemContainer) raw(ItemContainer.class);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "slf pass  " : "slf FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private SquareLoadFlagsTest() {
    }
}
