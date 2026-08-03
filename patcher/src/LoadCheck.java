import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 連結驗證：以 dist/java 優先＋遊戲 jar 的 classloader 載入每個 patch class（不觸發 clinit）。 */
public final class LoadCheck {
    public static void main(String[] args) throws Exception {
        Path distJava = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        Path manifest = Path.of(args[2]);
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{ distJava.toUri().toURL(), jar.toUri().toURL() },
                ClassLoader.getPlatformClassLoader())) {
            List<String> lines = Files.readAllLines(manifest);
            for (String line : lines) {
                String cls = line.split("\t")[0].replace(".class", "").replace('/', '.');
                Class.forName(cls, false, cl);
                System.out.println("load OK  " + cls);
            }

            if (args.length == 4 && args[3].equals("client")) {
                // client 模式：只驗 client helper 簽名（server helpers 不在 dist-client）
                Class<?> guard = Class.forName("zombie.mdc.TexturePipelineGuard", false, cl);
                var observed = guard.getDeclaredMethod("bytesAllocatedObserved");
                int guardModifiers = java.lang.reflect.Modifier.PUBLIC
                        | java.lang.reflect.Modifier.STATIC;
                if (observed.getReturnType() != long.class
                        || (observed.getModifiers() & guardModifiers) != guardModifiers
                        || observed.getExceptionTypes().length != 0) {
                    throw new NoSuchMethodException("TexturePipelineGuard.bytesAllocatedObserved signature");
                }
                // 門檻常數與 PatchConfig.client() 的手術值連動（clinit 僅設 long，無遊戲副作用）
                if (guard.getDeclaredField("VANILLA_LIMIT_BYTES").getLong(null) != 52428800L
                        || guard.getDeclaredField("PATCHED_LIMIT_BYTES").getLong(null) != 4294967296L) {
                    throw new IllegalStateException("TexturePipelineGuard 門檻常數與手術值不一致");
                }
                System.out.println("client helper OK bytesAllocatedObserved 簽名與門檻常數一致");

                // v2.0 洩漏根治 helper：改道/head-call 簽名逐一比對（缺了只會在執行期 NoSuchMethodError）
                Class<?> leak = Class.forName("zombie.core.textures.MinidoracatTextureLeakGuard", false, cl);
                Class<?> imgData = Class.forName("zombie.core.textures.ImageData", false, cl);
                Class<?> texId = Class.forName("zombie.core.textures.TextureID", false, cl);
                int psf = java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC;
                for (var sig : new Object[][]{
                        { "disposeFrames", new Class<?>[]{ imgData }, void.class },
                        { "ensureData", new Class<?>[]{ imgData }, void.class },
                        { "onFreeMemory", new Class<?>[]{ texId }, void.class },
                        { "createSteamAvatarFixed", new Class<?>[]{ long.class }, imgData } }) {
                    var m = leak.getDeclaredMethod((String)sig[0], (Class<?>[])sig[1]);
                    if (m.getReturnType() != sig[2] || (m.getModifiers() & psf) != psf) {
                        throw new NoSuchMethodException("MinidoracatTextureLeakGuard." + sig[0] + " signature");
                    }
                }
                System.out.println("leak guard OK 四個手術簽名一致");
                System.out.println("全部 " + lines.size() + " 個 class 連結驗證通過");
                return;
            }

            Class<?> lf = Class.forName("zombie.mdc.LogFilter", false, cl);
            // 跨類連結斷言：redirect helper 必須以「與 PatchConfig 改道簽名一致」的形式存在
            // （Class.forName 不會解析 INVOKESTATIC 的符號參照；缺了只會在執行期 NoSuchMethodError）
            Class<?> dt = Class.forName("zombie.debug.DebugType", false, cl);
            lf.getDeclaredMethod("warnFmt", dt, String.class, Object[].class);
            lf.getDeclaredMethod("warnObj", dt, Object.class);
            lf.getDeclaredMethod("log", String.class);
            Class<?> square = Class.forName("zombie.iso.IsoGridSquare", false, cl);
            Class<?> object = Class.forName("zombie.iso.IsoObject", false, cl);
            Class<?> zone = Class.forName("zombie.iso.zones.Zone", false, cl);
            Class<?> player = Class.forName("zombie.characters.IsoPlayer", false, cl);
            if (lf.getDeclaredMethod("getLootRespawnZone", square).getReturnType() != zone) {
                throw new NoSuchMethodException("getLootRespawnZone return type");
            }
            lf.getDeclaredMethod("getLootRespawnContainerCount", object);
            lf.getDeclaredMethod("getBuilding", square);
            lf.getDeclaredMethod("canBeSafehouse", square, player);
            System.out.println("helper OK 所有改道簽名一致");

            Class<?> database = Class.forName("zombie.network.ServerWorldDatabase", false, cl);
            Class<?> metrics = Class.forName("zombie.network.MinidoracatLoginMetrics", false, cl);
            var setPassword = metrics.getDeclaredMethod(
                    "setPassword", database, String.class, String.class);
            var updateLastConnection = metrics.getDeclaredMethod(
                    "updateLastConnectionDate", database, String.class, String.class);
            var setUserSteamID = metrics.getDeclaredMethod(
                    "setUserSteamID", database, String.class, String.class);
            if (setPassword.getReturnType() != void.class
                    || setPassword.getExceptionTypes().length != 1
                    || setPassword.getExceptionTypes()[0] != java.sql.SQLException.class) {
                throw new NoSuchMethodException("LoginMetrics.setPassword signature/throws");
            }
            if (updateLastConnection.getReturnType() != void.class
                    || updateLastConnection.getExceptionTypes().length != 0) {
                throw new NoSuchMethodException("LoginMetrics.updateLastConnectionDate signature/throws");
            }
            if (setUserSteamID.getReturnType() != String.class
                    || setUserSteamID.getExceptionTypes().length != 0) {
                throw new NoSuchMethodException("LoginMetrics.setUserSteamID signature/throws");
            }
            for (var field : metrics.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        && !java.lang.reflect.Modifier.isFinal(modifiers)) {
                    throw new IllegalStateException("LoginMetrics mutable static field: " + field.getName());
                }
            }
            System.out.println("login helper OK 精確簽名、checked exception 與 stateless 契約一致");

            Class<?> array = Class.forName("zombie.entity.util.Array", false, cl);
            Class<?> fastRemoval = Class.forName("zombie.mdc.FastIdentityArrayRemoval", false, cl);
            var indexedAdd = fastRemoval.getDeclaredMethod("add", array, Object.class);
            var indexedRemove = fastRemoval.getDeclaredMethod(
                    "remove", array, Object.class, boolean.class);
            int requiredModifiers = java.lang.reflect.Modifier.PUBLIC
                    | java.lang.reflect.Modifier.STATIC;
            if (indexedAdd.getReturnType() != void.class
                    || (indexedAdd.getModifiers() & requiredModifiers) != requiredModifiers) {
                throw new NoSuchMethodException("FastIdentityArrayRemoval.add signature/modifiers");
            }
            if (indexedRemove.getReturnType() != boolean.class
                    || (indexedRemove.getModifiers() & requiredModifiers) != requiredModifiers) {
                throw new NoSuchMethodException("FastIdentityArrayRemoval.remove signature/modifiers");
            }
            System.out.println("entity helper OK add/remove 精確簽名與 public static 契約一致");

            // 受精蛋豁免：改道簽名（receiver 前置）、判定與視窗 helper、觀測 getter 逐一比對——
            // 缺了只會在執行期 NoSuchMethodError，而該路徑是 chunk 載入（ServerChunkLoader 執行緒）
            Class<?> worldItem = Class.forName("zombie.iso.objects.IsoWorldInventoryObject", false, cl);
            Class<?> inventoryItem = Class.forName("zombie.inventory.InventoryItem", false, cl);
            Class<?> food = Class.forName("zombie.inventory.types.Food", false, cl);
            Class<?> eggGuard = Class.forName("zombie.mdc.FertilizedEggGuard", false, cl);
            int publicStatic = java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC;
            for (var sig : new Object[][]{
                    { "isIgnoreRemoveSandbox", new Class<?>[]{ worldItem }, boolean.class },
                    { "isHatchableEgg", new Class<?>[]{ inventoryItem }, boolean.class },
                    { "withinHatchWindow", new Class<?>[]{ worldItem, food }, boolean.class },
                    { "keptLoadsObserved", new Class<?>[]{}, long.class },
                    { "expiredLoadsObserved", new Class<?>[]{}, long.class },
                    { "anomaliesObserved", new Class<?>[]{}, long.class } }) {
                var m = eggGuard.getDeclaredMethod((String)sig[0], (Class<?>[])sig[1]);
                if (m.getReturnType() != sig[2]
                        || (m.getModifiers() & publicStatic) != publicStatic
                        || m.getExceptionTypes().length != 0) {
                    throw new NoSuchMethodException("FertilizedEggGuard." + sig[0] + " signature");
                }
            }
            // 豁免天花板是唯一的可調旋鈕，且是「不永久堆積」的唯一保證——必須為有限正數
            double hatchWindow = eggGuard.getDeclaredField("HATCH_WINDOW_MULTIPLIER").getDouble(null);
            if (!(hatchWindow > 0.0) || !Double.isFinite(hatchWindow)) {
                throw new IllegalStateException("FertilizedEggGuard.HATCH_WINDOW_MULTIPLIER 必須是有限正數："
                        + hatchWindow);
            }
            System.out.println("egg guard OK 改道/判定/視窗簽名與天花板常數一致（" + hatchWindow + "×）");
        }
        System.out.println("全部 " + Files.readAllLines(manifest).size() + " 個 class 連結驗證通過");
    }
    private LoadCheck() {}
}
