package zombie.mdc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.locks.ReentrantLock;

import zombie.popman.ZombiePopulationManager;

/**
 * W28: serialize the two missing-square native fallbacks with background cell saves.
 * Uses the game's existing lock; only the native call is inside the critical section.
 * A cached private MethodHandle preserves the native name/access and exact exception.
 * -Dmdc.popmanAddLock=0 or off disables locking after restart, not native delegation.
 */
public final class PopManAddLock {

    private static final String FLAG = System.getProperty("mdc.popmanAddLock");
    private static final boolean ENABLED = !"0".equals(FLAG) && !"off".equals(FLAG);

    /** vanilla {@code private static native void n_addZombie(float,float,float,byte,int,int,int,int)}。 */
    private static final MethodHandle N_ADD_ZOMBIE = resolveNative();

    static {
        System.out.println("[MinidoracatJavaPatch][PopManAddLock] lock=" + ENABLED
                + " (missing-square native fallbacks; restart with -Dmdc.popmanAddLock=0 to disable)");
    }

    private static MethodHandle resolveNative() {
        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(ZombiePopulationManager.class, MethodHandles.lookup());
            return lookup.findStatic(ZombiePopulationManager.class, "n_addZombie",
                    MethodType.methodType(void.class, float.class, float.class, float.class, byte.class,
                            int.class, int.class, int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * {@code INVOKESTATIC ZombiePopulationManager.n_addZombie:(FFFBIIII)V} 的改道目標
     * （參數與順序逐一等同 vanilla）。
     */
    public static void addZombie(float x, float y, float z, byte dir, int descriptorID, int state,
            int pathTargetX, int pathTargetY) throws Throwable {
        ReentrantLock lock = ENABLED ? ZombiePopulationManager.saveLock : null;
        if (lock != null) {
            lock.lock();
        }
        try {
            N_ADD_ZOMBIE.invokeExact(x, y, z, dir, descriptorID, state, pathTargetX, pathTargetY);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    private PopManAddLock() {}
}
