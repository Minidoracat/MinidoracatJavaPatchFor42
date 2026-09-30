package zombie.characters.animals;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import zombie.ZomboidFileSystem;
import zombie.core.Core;

/**
 * W37 MdcAnimalCellSave 行為驗證（{@code on}／{@code off}）。用真 AnimalCell／AnimalChunk／VirtualAnimal
 * 序列化鏈：chunk 內放一隻未初始化（data null）的 IsoAnimal，原版序列化必拋。
 * on：不外拋、舊檔逐位元保留、dataChanged 標回；健康 cell 正常寫出。序列化拋 IOException（W50 保不住掛鉤狀態
 * 時）同樣保留舊檔並標回重試。世界中動物的暫存快照：失敗後留著、下一輪收集前清掉，重試成功後每隻只寫一次；
 * 不清（原版）時同一隻寫兩次。
 * off：重現原版事故——例外外拋且檔案被截成 0 bytes；清快照不動作。
 */
public final class MdcAnimalCellSaveTest {

    private static int failed;
    private static final long MARK = 0x0123456789ABCDEFL;

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        boolean off = args.length > 0 && args[0].equals("off");
        expect("旗標與 argv 相符", MdcAnimalCellSave.enabledForTest() == !off);

        Path cache = Files.createTempDirectory("w37-apop");
        ZomboidFileSystem.instance.setCacheDir(cache.toString());
        Core.gameSaveWorld = "w37test";
        Path file = Path.of(ZomboidFileSystem.instance.getFileNameInCurrentSave("apop", "apop_3_4.bin"));
        Files.createDirectories(file.getParent());
        byte[] old = "previous-good-apop".getBytes();
        Files.write(file, old);

        AnimalCell broken = cell(3, 4);
        AnimalChunk chunk = AnimalChunk.alloc().init(3 * 32, 4 * 32);
        VirtualAnimal virtual = new VirtualAnimal();
        virtual.animals.add((IsoAnimal) raw(IsoAnimal.class));   // data／adef null：原版 save 必拋
        chunk.animals.add(virtual);
        broken.chunks[0] = chunk;

        RuntimeException thrown = null;
        try {
            MdcAnimalCellSave.save(broken);
        } catch (RuntimeException e) {
            thrown = e;
        }
        if (off) {
            expect("off：原版序列化例外外拋", thrown != null);
            expect("off：原版先開檔，apop 被截成 0 bytes（事故重現）", Files.size(file) == 0);
            AnimalCell stale = cell(3, 4);
            stale.saveRealAnimalHack = new ArrayList<>();
            stale.saveRealAnimalHack.add(snapshot(marked(false)));
            MdcAnimalCellSave.clearStaleRealSnapshots(worker(stale));
            expect("off：不清世界中動物快照", stale.saveRealAnimalHack != null && stale.saveRealAnimalHack.size() == 1);
        } else {
            expect("on：序列化例外不外拋", thrown == null);
            expect("on：舊檔逐位元保留", java.util.Arrays.equals(Files.readAllBytes(file), old));
            expect("on：dataChanged 標回待重試", broken.dataChanged);
            expect("on：failures=1", MdcAnimalCellSave.failuresForTest() == 1);

            AnimalCell healthy = cell(3, 4);
            MdcAnimalCellSave.save(healthy);
            expect("on：健康 cell 正常覆寫（版本 int＋1024 個空 chunk）", Files.size(file) > old.length
                    && java.nio.ByteBuffer.wrap(Files.readAllBytes(file)).getInt() == 249);

            // IOException（W50 保不住掛鉤狀態時拋出）：同樣保留舊檔、標回重試。
            byte[] good = Files.readAllBytes(file);
            AnimalCell ioCell = cell(3, 4);
            AnimalChunk ioChunk = AnimalChunk.alloc().init(3 * 32, 4 * 32);
            VirtualAnimal ioVirtual = new VirtualAnimal();
            ioVirtual.animals.add(marked(true));
            ioChunk.animals.add(ioVirtual);
            ioCell.chunks[0] = ioChunk;
            MdcAnimalCellSave.save(ioCell);
            expect("on：IOException 保留舊檔、dataChanged 標回、failures=2",
                    java.util.Arrays.equals(Files.readAllBytes(file), good) && ioCell.dataChanged
                            && MdcAnimalCellSave.failuresForTest() == 2);

            // 世界中動物快照：第一次序列化失敗 → 下一輪收集前清掉 → 重新收集 → 成功，每隻只寫一次。
            Marked animal = marked(true);
            AnimalCell realCell = cell(3, 4);
            realCell.saveRealAnimalHack = new ArrayList<>();
            realCell.saveRealAnimalHack.add(snapshot(animal));
            MdcAnimalCellSave.save(realCell);
            expect("on：失敗後快照仍在（下一輪之前的重試不漏寫世界中動物）",
                    realCell.saveRealAnimalHack != null && realCell.saveRealAnimalHack.size() == 1);
            MdcAnimalCellSave.clearStaleRealSnapshots(worker(realCell));
            realCell.saveRealAnimalHack = new ArrayList<>();
            realCell.saveRealAnimalHack.add(snapshot(animal));
            MdcAnimalCellSave.save(realCell);
            expect("on：清掉舊快照後重試成功，這隻只寫一次", marks(Files.readAllBytes(file)) == 1
                    && MdcAnimalCellSave.staleSnapshotsForTest() == 1);

            // 對照：不清舊快照（原版）→ 同一隻寫兩次。
            Marked twice = marked(true);
            AnimalCell vanillaCell = cell(3, 4);
            vanillaCell.saveRealAnimalHack = new ArrayList<>();
            vanillaCell.saveRealAnimalHack.add(snapshot(twice));
            MdcAnimalCellSave.save(vanillaCell);
            vanillaCell.saveRealAnimalHack.add(snapshot(twice));
            MdcAnimalCellSave.save(vanillaCell);
            expect("對照：原版不清舊快照時同一隻寫兩次", marks(Files.readAllBytes(file)) == 2);
        }
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("MdcAnimalCellSaveTest 全數通過" + (off ? "（off）" : ""));
    }

    private static AnimalCell cell(int x, int y) {
        AnimalCell c = new AnimalCell().init(x, y);
        c.loaded = true;
        c.chunks = new AnimalChunk[1024];
        return c;
    }

    /** 模仿 AnimalManagerWorker.saveRealAnimals：每次收集都新建一個只包這隻動物的 VirtualAnimal。 */
    private static VirtualAnimal snapshot(IsoAnimal animal) {
        VirtualAnimal v = new VirtualAnimal();
        v.setX(3 * 256 + 1);
        v.setY(4 * 256 + 1);
        v.animals.add(animal);
        return v;
    }

    private static AnimalManagerWorker worker(AnimalCell cell) throws Exception {
        AnimalManagerWorker w = (AnimalManagerWorker) raw(AnimalManagerWorker.class);
        Field cells = AnimalManagerWorker.class.getDeclaredField("loadedCells");
        cells.setAccessible(true);
        ArrayList<AnimalCell> list = new ArrayList<>();
        list.add(cell);
        cells.set(w, list);
        return w;
    }

    private static Marked marked(boolean failFirst) throws Exception {
        Marked m = (Marked) raw(Marked.class);
        m.failNext = failFirst;
        return m;
    }

    private static int marks(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int count = 0;
        for (int i = 0; i + 8 <= bytes.length; i++) {
            if (b.getLong(i) == MARK) {
                count++;
            }
        }
        return count;
    }

    /** 替身：序列化寫一個標記；failNext 時第一次拋 IOException（模擬寫出失敗）。 */
    public static class Marked extends IsoAnimal {
        boolean failNext;

        /** 僅供編譯；實例由 serialization 分配器產生。 */
        Marked() {
            super((zombie.iso.IsoCell) null);
        }

        @Override
        public void save(ByteBuffer out, boolean isDebugSave) throws IOException {
            if (failNext) {
                failNext = false;
                throw new IOException("simulated serialize failure");
            }
            out.putLong(MARK);
        }
    }

    private static Object raw(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "acs pass  " : "acs FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private MdcAnimalCellSaveTest() {}
}
