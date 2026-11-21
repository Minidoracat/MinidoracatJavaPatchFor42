package zombie.mdc;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import zombie.characters.animals.IsoAnimal;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * W107 TrailerAnimalDefer 行為驗證（argv：{@code on}／{@code off}）。BaseVehicle 是 final：車用沒跑建構子的真 BaseVehicle，
 * {@code animals} 換成記錄「哪條執行緒、哪台車」的清單；原版 addAnimalInTrailer 第一行就是 {@code animals.add}，
 * 動物傳 null，之後讀 {@code animal.mother} 拋 NPE（當成「原版中途拋例外」用）：
 * <ul>
 *   <li>on：背景執行緒呼叫只排隊、原版沒跑；{@code beforeLoadChunkMain} 在主執行緒依 FIFO 呼叫原版；背景呼叫它不排空；
 *       每筆拋的例外吞掉、下一筆照做；mainThread 為 null 或主執行緒呼叫照原版當下執行。</li>
 *   <li>off（原版負對照）：背景執行緒當下執行原版，第一筆的例外拋回背景呼叫端（原版 vehicleLoaded 會因此把整台車當讀檔失敗）。</li>
 * </ul>
 */
public final class TrailerAnimalDeferTest {

    private static int failed;
    private static Thread main;
    static final List<String> calls = new ArrayList<>();

    /** 記錄誰把動物加進這台車。 */
    public static final class Rec extends ArrayList<IsoAnimal> {
        final String name;

        Rec(String name) {
            this.name = name;
        }

        @Override
        public boolean add(IsoAnimal a) {
            synchronized (calls) {
                calls.add(name + (Thread.currentThread() == main ? "@main" : "@bg"));
            }
            return super.add(a);
        }
    }

    public static void main(String[] args) throws Exception {
        boolean on = args.length == 0 || args[0].equals("on");
        expect("開關與 argv 相符", TrailerAnimalDefer.ENABLED == on);
        main = Thread.currentThread();
        GameServer.server = true;
        BaseVehicle a = vehicle("A");
        BaseVehicle b = vehicle("B");
        BaseVehicle c = vehicle("C");

        // mainThread 為 null：照原版當下執行
        GameServer.mainThread = null;
        Throwable t0 = background(() -> TrailerAnimalDefer.addAnimalInTrailer(a, null));
        expect("mainThread 為 null：背景照原版當下執行", calls.equals(List.of("A@bg")) && t0 instanceof NullPointerException
                && TrailerAnimalDefer.pendingForTest() == 0);
        calls.clear();
        GameServer.mainThread = main;

        // 主執行緒呼叫照原版當下執行（例外照原版拋給呼叫端）
        Throwable t1 = null;
        try {
            TrailerAnimalDefer.addAnimalInTrailer(a, null);
        } catch (NullPointerException ex) {
            t1 = ex;
        }
        expect("主執行緒呼叫照原版當下執行", calls.equals(List.of("A@main")) && t1 != null);
        calls.clear();

        // 背景三筆
        Throwable bg = background(() -> {
            TrailerAnimalDefer.addAnimalInTrailer(a, null);
            TrailerAnimalDefer.addAnimalInTrailer(b, null);
            TrailerAnimalDefer.addAnimalInTrailer(c, null);
            TrailerAnimalDefer.beforeLoadChunkMain();   // 背景呼叫不排空
        });
        if (on) {
            expect("on：背景只排隊、原版沒跑、不拋", bg == null && calls.isEmpty() && TrailerAnimalDefer.pendingForTest() == 3);
            Throwable t = null;
            try {
                TrailerAnimalDefer.beforeLoadChunkMain();
            } catch (RuntimeException ex) {
                t = ex;
            }
            expect("on：processMain 頭部在主執行緒依 FIFO 呼叫原版：" + calls, calls.equals(List.of("A@main", "B@main", "C@main")));
            expect("on：每筆的例外吞掉、下一筆照做、計數相符", t == null && TrailerAnimalDefer.failedForTest() == 3
                    && TrailerAnimalDefer.pendingForTest() == 0 && TrailerAnimalDefer.deferredForTest() == 3
                    && TrailerAnimalDefer.drainedForTest() == 3);
            TrailerAnimalDefer.beatForTest();
            String beat = TrailerAnimalDefer.lastBeatLine;
            expect("on：心跳欄位齊全：" + beat, beat != null && beat.startsWith("beat deferred=3 drained=3 pending=0 ")
                    && beat.contains(" failed=3 ") && beat.contains(" overflow=0 ") && beat.contains(" anomalies=0 "));
        } else {
            expect("off（原版）：背景當下執行、第一筆的例外拋回背景呼叫端、後面沒跑", calls.equals(List.of("A@bg"))
                    && bg instanceof NullPointerException && TrailerAnimalDefer.deferredForTest() == 0);
        }

        if (failed > 0) {
            System.out.println("TrailerAnimalDeferTest FAIL x" + failed);
            System.exit(1);
        }
        System.out.println("TrailerAnimalDeferTest 全數通過（" + (on ? "on" : "off") + "）");
    }

    private static BaseVehicle vehicle(String name) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory().newConstructorForSerialization(BaseVehicle.class, objCtor);
        alloc.setAccessible(true);
        BaseVehicle v = (BaseVehicle) alloc.newInstance();
        v.animals = new Rec(name);
        return v;
    }

    private static Throwable background(Runnable r) throws InterruptedException {
        Throwable[] out = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable ex) {
                out[0] = ex;
            }
        }, "ServerPlayersVehicles");
        t.start();
        t.join();
        return out[0];
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "tad pass  " : "tad FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }
}
