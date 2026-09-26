package zombie.characters.animals;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import zombie.ZomboidFileSystem;
import zombie.core.Core;

/**
 * W37 MdcAnimalCellSave 行為驗證（{@code on}／{@code off}）。用真 AnimalCell／AnimalChunk／VirtualAnimal
 * 序列化鏈：chunk 內放一隻未初始化（data null）的 IsoAnimal，原版序列化必拋。
 * on：不外拋、舊檔逐位元保留、dataChanged 標回；健康 cell 正常寫出。
 * off：重現原版事故——例外外拋且檔案被截成 0 bytes。
 */
public final class MdcAnimalCellSaveTest {

    private static int failed;

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
        } else {
            expect("on：序列化例外不外拋", thrown == null);
            expect("on：舊檔逐位元保留", java.util.Arrays.equals(Files.readAllBytes(file), old));
            expect("on：dataChanged 標回待重試", broken.dataChanged);
            expect("on：failures=1", MdcAnimalCellSave.failuresForTest() == 1);

            AnimalCell healthy = cell(3, 4);
            MdcAnimalCellSave.save(healthy);
            expect("on：健康 cell 正常覆寫（版本 int＋1024 個空 chunk）", Files.size(file) > old.length
                    && java.nio.ByteBuffer.wrap(Files.readAllBytes(file)).getInt() == 249);
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
