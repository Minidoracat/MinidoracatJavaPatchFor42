package zombie.mdc;

import java.lang.reflect.Field;

/** 行為測試共用：以 Unsafe 配置遊戲物件（不跑建構子），並用反射補欄位。 */
final class TestObjects {

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

    private TestObjects() {}
}
