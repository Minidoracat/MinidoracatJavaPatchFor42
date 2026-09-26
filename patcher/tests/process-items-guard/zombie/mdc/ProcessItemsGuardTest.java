package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.network.GameServer;

/**
 * W40／W41 ProcessItemsGuard 行為驗證（{@code on}／{@code off}／{@code nodefer}），跑 dist 內手術後的真
 * {@code IsoCell.ProcessItems}／{@code ProcessRemoveItems}／{@code addToProcessItems}：
 * off 重現原版——null 處 NPE、null 之後的物品不被處理、null 留在清單；
 * on——null 前後物品都處理、null 與已完成物品同幀移除；主執行緒寫入不計、其他執行緒寫入被記錄；
 * 其他執行緒經 AddItem 改道入口的登記排入佇列、下一次 ProcessItems 補登記（nodefer 回原版直接寫）。
 */
public final class ProcessItemsGuardTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean off = args.length > 0 && args[0].equals("off");
        boolean noDefer = args.length > 0 && args[0].equals("nodefer");
        expect("旗標與 argv 相符", ProcessItemsGuard.enabledForTest() == !off
                && ProcessItemsGuard.deferForTest() == !noDefer);
        GameServer.mainThread = Thread.currentThread();

        IsoCell cell = (IsoCell) raw(IsoCell.class);
        ArrayList<InventoryItem> items = new ArrayList<>();
        set(cell, "processItems", items);
        set(cell, "processItemsRemove", new HashSet<InventoryItem>());
        set(cell, "processWorldItems", new ArrayList<>());
        set(cell, "processWorldItemsRemove", new HashSet<>());
        Method process = IsoCell.class.getDeclaredMethod("ProcessItems", Iterator.class);
        Method remove = IsoCell.class.getDeclaredMethod("ProcessRemoveItems", Iterator.class);
        process.setAccessible(true);
        remove.setAccessible(true);

        FakeItem keeps = item(false), finishes = item(true);
        items.addAll(java.util.Arrays.asList(keeps, null, finishes));
        Throwable thrown = null;
        try {
            process.invoke(cell, (Object) null);
        } catch (InvocationTargetException e) {
            thrown = e.getCause();
        }
        remove.invoke(cell, (Object) null);
        if (off) {
            expect("off：原版在 null 處 NPE", thrown instanceof NullPointerException);
            expect("off：null 之後的物品沒被處理", finishes.updates == 0);
            expect("off：null 留在清單，下次照樣卡", items.contains(null) && items.size() == 3);
        } else {
            expect("on：不拋例外", thrown == null);
            expect("on：null 前後物品都處理", keeps.updates == 1 && finishes.updates == 1);
            expect("on：null 與已完成物品同幀移除 " + items, items.equals(List.of(keeps)));
            expect("on：nulls=1", ProcessItemsGuard.nullsForTest() == 1);

            // 主執行緒寫入不計，其他執行緒寫入計入。
            cell.addToProcessItems(item(false));
            expect("主執行緒寫入不計、addCalls 計入", ProcessItemsGuard.offThreadForTest() == 0
                    && ProcessItemsGuard.addCallsForTest() == 1);
            Thread writer = new Thread(() -> {
                cell.addToProcessItems(item(false));
                cell.addToProcessItemsRemove(keeps);
            }, "test-writer");
            writer.start();
            writer.join();
            expect("其他執行緒兩次寫入被記錄", ProcessItemsGuard.offThreadForTest() == 2);

            // W41：AddItem 的改道入口。其他執行緒排入佇列，下一次 ProcessItems 由主執行緒補登記。
            FakeItem viaMain = item(false), viaVehicleThread = item(false);
            ProcessItemsGuard.addToProcessItems(cell, viaMain);
            expect("W41 主執行緒直接登記", items.contains(viaMain));
            Thread loader = new Thread(() -> ProcessItemsGuard.addToProcessItems(cell, viaVehicleThread),
                    "ServerPlayersVehicles");
            loader.start();
            loader.join();
            if (noDefer) {
                expect("W41 off：其他執行緒照原版直接寫清單（並被記錄）",
                        items.contains(viaVehicleThread) && ProcessItemsGuard.offThreadForTest() == 3
                        && ProcessItemsGuard.deferredForTest() == 0);
            } else {
                expect("W41 on：其他執行緒不碰清單、排入佇列",
                        !items.contains(viaVehicleThread) && ProcessItemsGuard.offThreadForTest() == 2
                        && ProcessItemsGuard.deferredForTest() == 1 && ProcessItemsGuard.pendingForTest() == 1);
                process.invoke(cell, (Object) null);
                expect("W41 on：下一次 ProcessItems 補登記且當幀處理",
                        items.contains(viaVehicleThread) && viaVehicleThread.updates == 1
                        && ProcessItemsGuard.pendingForTest() == 0);
            }
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ProcessItemsGuardTest 全數通過" + (off ? "（off）" : ""));
    }

    public static class FakeItem extends InventoryItem {
        boolean done;
        int updates;

        FakeItem() {
            super(null, null, null, (String) null);
        }

        @Override
        public void update() {
            updates++;
        }

        @Override
        public boolean finishupdate() {
            return done;
        }
    }

    private static FakeItem item(boolean done) {
        try {
            FakeItem f = (FakeItem) raw(FakeItem.class);
            f.done = done;
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
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
        System.out.println((ok ? "pig pass  " : "pig FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private ProcessItemsGuardTest() {}
}
