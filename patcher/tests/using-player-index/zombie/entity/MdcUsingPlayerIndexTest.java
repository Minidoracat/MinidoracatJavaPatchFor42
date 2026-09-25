package zombie.entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import zombie.characters.IsoPlayer;
import zombie.entity.util.Array;
import zombie.entity.util.BitSet;
import zombie.entity.util.ImmutableArray;
import zombie.iso.IsoObject;

/**
 * W35 MdcUsingPlayerIndex 行為驗證（獨立 JVM；argv[0] = observe|enforce|off，須與
 * {@code -Dmdc.usingPlayerIndex} 相符）。走 dist 內已 patch 的 GameEntity.setUsingPlayer
 * （headCall 追蹤點）與真 IsoObjectBucket.updateMembership。
 *
 * <p>鎖：enforce 只回「有 usingPlayer 且屬於該 bucket」的 entity；清成 null 後下一次移出；
 * 不屬於 bucket 的不回；繞過追蹤點寫入（模擬遺漏）會被稽核抓到，enforce 永久退回全表；
 * observe／off 一律回原版全表，off 不追蹤。
 */
public final class MdcUsingPlayerIndexTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        // setUsingPlayer 讀 GameClient.client → ServerOptions 靜態初始化需要已播種的全域 Rand
        zombie.core.random.RandStandard.INSTANCE.init();
        String want = args.length == 0 ? "observe" : args[0];
        int mode = switch (want) {
            case "enforce" -> MdcUsingPlayerIndex.MODE_ENFORCE;
            case "off" -> MdcUsingPlayerIndex.MODE_OFF;
            default -> MdcUsingPlayerIndex.MODE_OBSERVE;
        };
        expect("mode 與 argv 相符（" + want + "）", MdcUsingPlayerIndex.MODE == mode);

        EntityBucket bucket = new EntityBucket.IsoObjectBucket(3);
        IsoObject[] inBucket = new IsoObject[5];
        for (int i = 0; i < inBucket.length; i++) {
            inBucket[i] = entity();
            bucket.updateMembership(inBucket[i]);
        }
        IsoObject outside = entity();
        expect("前提：bucket 內 5 個", bucket.getEntities().size() == 5);

        IsoPlayer p = (IsoPlayer) rawInstance(IsoPlayer.class);
        inBucket[1].setUsingPlayer(p);
        inBucket[3].setUsingPlayer(p);
        outside.setUsingPlayer(p);

        ImmutableArray<GameEntity> r = MdcUsingPlayerIndex.entities(bucket);
        if (mode == MdcUsingPlayerIndex.MODE_ENFORCE) {
            expect("enforce：只回 2 個使用中且在 bucket 的 entity", r.size() == 2
                    && r.contains(inBucket[1], true) && r.contains(inBucket[3], true)
                    && !r.contains(outside, true));
            inBucket[1].setUsingPlayer(null);
            ImmutableArray<GameEntity> r2 = MdcUsingPlayerIndex.entities(bucket);
            expect("enforce：清成 null 後下一次移出", r2.size() == 1 && r2.contains(inBucket[3], true));
        } else {
            expect(want + "：回原版全表", r == bucket.getEntities());
        }
        if (mode == MdcUsingPlayerIndex.MODE_OFF) {
            expect("off：不追蹤", MdcUsingPlayerIndex.trackedForTest() == 0);
        }

        // 繞過追蹤點直接寫欄位＝模擬漏掉的寫入路徑；稽核必須抓到
        Field up = GameEntity.class.getDeclaredField("usingPlayer");
        up.setAccessible(true);
        up.set(inBucket[4], p);
        ImmutableArray<GameEntity> last = null;
        for (int i = 0; i < MdcUsingPlayerIndex.AUDIT_EVERY; i++) {
            last = MdcUsingPlayerIndex.entities(bucket);
        }
        if (mode == MdcUsingPlayerIndex.MODE_OFF) {
            expect("off：不稽核", MdcUsingPlayerIndex.missedForTest() == 0);
        } else {
            expect(want + "：稽核抓到遺漏", MdcUsingPlayerIndex.missedForTest() > 0);
        }
        if (mode == MdcUsingPlayerIndex.MODE_ENFORCE) {
            expect("enforce：遺漏後永久退回原版全表", MdcUsingPlayerIndex.fellBackForTest()
                    && MdcUsingPlayerIndex.entities(bucket) == bucket.getEntities());
        } else {
            expect(want + "：始終回原版全表", last == bucket.getEntities());
        }

        if (failed > 0) {
            System.out.println("MdcUsingPlayerIndexTest FAIL x" + failed);
            System.exit(1);
        }
        System.out.println("MdcUsingPlayerIndexTest 全數通過（" + want + "）");
    }

    /** 未初始化的 IsoObject，只補上 bucket 成員判定需要的 ComponentContainer（非空、有 bucketBits）。 */
    private static IsoObject entity() throws Exception {
        IsoObject o = (IsoObject) rawInstance(IsoObject.class);
        ComponentContainer c = (ComponentContainer) rawInstance(ComponentContainer.class);
        Array<Component> list = new Array<>(false, 1);
        list.add(null);
        set(ComponentContainer.class, c, "componentList", list);
        set(ComponentContainer.class, c, "bucketBits", new BitSet());
        set(GameEntity.class, o, "components", c);
        return o;
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "upi pass  " : "upi FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private MdcUsingPlayerIndexTest() {}
}
