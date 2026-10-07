package zombie.characters.animals;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import zombie.iso.IsoButcherHook;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.util.PZCalendar;

/**
 * W49-A3／W50 MdcAnimalSave 行為驗證（{@code on}＝出貨組態、{@code nohook}＝{@code -Dmdc.animalHookSave=0}、
 * {@code noown}＝{@code -Dmdc.animalOwnClock=0}、{@code nocellsave}＝{@code -Dmdc.animalCellSave=0}（W50 依賴 W37））。
 * 鎖：尾端改寫只在四個欄位與容量全部吻合時進行、失敗拋 IOException 且 buffer 不動；屠體參照無效＋有座標時
 * 寫成原版掛鉤格式、參照有效或非屠體不動、無座標照原版；時鐘只在 apop 上下文、不在 objectList、時鐘有效時寫
 * 動物自身時鐘，上下文只給第一次時鐘寫入、例外後還原。
 */
public final class MdcAnimalSaveTest {

    private static int failed;
    private static final long HOUR = 3_600_000L;
    /** 合成的鉤子座標（任意正值）。 */
    private static final int HX = 1200;
    private static final int HY = 3400;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean hook = mode.equals("on") || mode.equals("noown");
        boolean own = !mode.equals("noown");
        expect("旗標與 argv 相符", MdcAnimalSave.hookForTest() == hook && MdcAnimalSave.ownForTest() == own);

        tailRewrite();

        // ---- 掛鉤狀態（座標為合成值，與任何真實地點無關）----
        Carcass loaded = carcass(true, null, HX, HY, 0);
        byte[] out = write(loaded, 256);
        expect("屠體參照無效＋有座標：" + (hook ? "寫成掛鉤格式" : "照原版寫 onHook=0"),
                Arrays.equals(out, hook ? hookedRecord(loaded, HX, HY, 0) : vanillaRecord(loaded, null)));

        IsoButcherHook live = liveHook(HX, HY, 0);
        Carcass attached = carcass(true, live, 0, 0, 0);
        expect("屠體參照有效：原版已寫掛鉤格式、不改", Arrays.equals(write(attached, 256), vanillaRecord(attached, live)));

        Carcass walking = carcass(false, null, HX, HY, 0);
        expect("非屠體：不改", Arrays.equals(write(walking, 256), vanillaRecord(walking, null)));

        Carcass lost = carcass(true, null, 0, 0, 0);
        expect("屠體無座標（兩軸皆 0，原版 reattach 的哨兵）：照原版寫出",
                Arrays.equals(write(lost, 256), vanillaRecord(lost, null)));
        Carcass xAxis = carcass(true, null, 0, HY, 0);
        Carcass yAxis = carcass(true, null, HX, 0, 0);
        expect("只有一軸為 0 仍是有效座標（原版可 reattach）：" + (hook ? "寫成掛鉤格式" : "照原版寫出"),
                Arrays.equals(write(xAxis, 256), hook ? hookedRecord(xAxis, 0, HY, 0) : vanillaRecord(xAxis, null))
                        && Arrays.equals(write(yAxis, 256),
                                hook ? hookedRecord(yAxis, HX, 0, 0) : vanillaRecord(yAxis, null)));

        Carcass tight = carcass(true, null, HX, HY, 0);
        int need = vanillaRecord(tight, null).length;
        boolean io = false;
        try {
            write(tight, need + MdcAnimalSave.COORDS - 1);
        } catch (IOException e) {
            io = true;
        }
        expect("容量差 1 byte：" + (hook ? "拋 IOException（交給 W37 保留舊檔）" : "照原版寫出"), io == hook);
        expect("容量剛好：寫成掛鉤格式", !hook
                || Arrays.equals(write(carcass(true, null, HX, HY, 0), need + MdcAnimalSave.COORDS),
                        hookedRecord(tight, HX, HY, 0)));
        expect("計數：kept=" + (hook ? 4 : 0) + " noCoords=" + (hook ? 1 : 0) + " ioFail=" + (hook ? 1 : 0),
                MdcAnimalSave.keptForTest() == (hook ? 4 : 0) && MdcAnimalSave.noCoordsForTest() == (hook ? 1 : 0)
                        && MdcAnimalSave.ioFailForTest() == (hook ? 1 : 0));

        // ---- 自身時鐘 ----
        long now = 1_800_000_000_000L;
        IsoCell cell = (IsoCell) raw(IsoCell.class);
        Set<IsoMovingObject> objects = new HashSet<>();
        Field objectList = IsoCell.class.getDeclaredField("objectList");
        objectList.setAccessible(true);
        objectList.set(cell, objects);
        IsoWorld.instance.currentCell = cell;

        Carcass unloaded = clock(now - 5 * HOUR, now);
        expect("已卸載：寫自身時鐘（noown 寫存檔當下）", clockWritten(unloaded) == (own ? now - 5 * HOUR : now));
        Carcass inWorld = clock(now - 5 * HOUR, now);
        objects.add(inWorld);
        expect("在 objectList（世界中）：寫存檔當下", clockWritten(inWorld) == now);
        Carcass awaiting = clock(now - 5 * HOUR, now);
        objects.add(awaiting);
        awaiting.fromMeta = true;   // W56 延後補算中：凍結、尚未補算
        expect("在 objectList 但延後補算中：寫自身時鐘（noown 寫存檔當下）",
                clockWritten(awaiting) == (own ? now - 5 * HOUR : now));
        expect("時鐘在未來：寫存檔當下", clockWritten(clock(now + HOUR, now)) == now);
        expect("無時鐘紀錄：寫存檔當下", clockWritten(clock(-1L, now)) == now);
        Carcass nested = clock(now - 5 * HOUR, now);
        nested.nestedClock = true;
        write(nested, 256);
        expect("上下文只給第一次時鐘寫入：第二次寫存檔當下", nested.secondClock == now);
        Carcass broken = clock(now - 5 * HOUR, now);
        broken.throwAfterClock = true;
        boolean thrown = false;
        try {
            write(broken, 256);
        } catch (IllegalStateException e) {
            thrown = true;
        }
        expect("寫出途中例外：原樣外拋且上下文還原", thrown && MdcAnimalSave.writingForTest() == null);
        expect("apop 以外的呼叫（封包、雞舍）：寫存檔當下", MdcAnimalSave.clockToWrite(calendar(now)) == now);
        IsoWorld.instance.currentCell = null;
        expect("取不到 cell：寫存檔當下", clockWritten(clock(now - 5 * HOUR, now)) == now);
        expect("anomalies=0", MdcAnimalSave.anomaliesForTest() == 0);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("MdcAnimalSaveTest 全數通過（" + mode + "）");
    }

    /** restoreHookTail 純位元組行為：非零起點、連續兩筆、容量邊界、四個欄位任一不符。 */
    private static void tailRewrite() throws Exception {
        ByteBuffer out = ByteBuffer.allocate(64);
        out.put(new byte[]{9, 9, 9});
        int first = out.position();
        out.put((byte) 7).put((byte) 0).putFloat(1.5F).put((byte) 1).putShort((short) 42);
        MdcAnimalSave.restoreHookTail(out, first, 100, 200, 1, 1.5F, true, (short) 42);
        int second = out.position();
        out.put((byte) 0).putFloat(0.25F).put((byte) 0).putShort((short) -3);
        MdcAnimalSave.restoreHookTail(out, second, 300, 400, 0, 0.25F, false, (short) -3);
        ByteBuffer want = ByteBuffer.allocate(64);
        want.put(new byte[]{9, 9, 9}).put((byte) 7).put((byte) 1).putInt(100).putInt(200).putInt(1).putFloat(1.5F)
                .put((byte) 1).putShort((short) 42);
        want.put((byte) 1).putInt(300).putInt(400).putInt(0).putFloat(0.25F).put((byte) 0).putShort((short) -3);
        expect("非零起點＋連續兩筆：各自改成掛鉤格式、前一筆不受影響",
                out.position() == want.position() && Arrays.equals(out.array(), want.array()));

        expect("容量剛好 12：成功", rewrite(12, 0, 2.0F, 0, 5, 2.0F, false, (short) 5));
        expect("容量 11：拋例外且 buffer 不動", !rewrite(11, 0, 2.0F, 0, 5, 2.0F, false, (short) 5));
        expect("onHook 已是 1：拋例外", !rewrite(40, 1, 2.0F, 0, 5, 2.0F, false, (short) 5));
        expect("petTimer 不符：拋例外", !rewrite(40, 0, 2.0F, 0, 5, 3.0F, false, (short) 5));
        expect("wild 不符：拋例外", !rewrite(40, 0, 2.0F, 1, 5, 2.0F, false, (short) 5));
        expect("onlineID 不符：拋例外", !rewrite(40, 0, 2.0F, 0, 6, 2.0F, false, (short) 5));
        ByteBuffer shortRecord = ByteBuffer.allocate(64);
        shortRecord.put(new byte[7]);
        boolean io = false;
        try {
            MdcAnimalSave.restoreHookTail(shortRecord, 0, 1, 1, 0, 0F, false, (short) 0);
        } catch (IOException e) {
            io = true;
        }
        expect("紀錄比尾端短：拋例外", io && shortRecord.position() == 7);
    }

    /** 在剩餘容量 room 的 buffer 寫一段尾端再改寫；成功回 true，失敗時檢查 buffer 完全沒動。 */
    private static boolean rewrite(int room, int flag, float pet, int wild, int id,
            float expectPet, boolean expectWild, short expectId) throws Exception {
        ByteBuffer out = ByteBuffer.allocate(MdcAnimalSave.TAIL + room);
        out.put((byte) flag).putFloat(pet).put((byte) wild).putShort((short) id);
        byte[] before = out.array().clone();
        int position = out.position();
        try {
            MdcAnimalSave.restoreHookTail(out, 0, 11, 22, 0, expectPet, expectWild, expectId);
            return true;
        } catch (IOException e) {
            if (!Arrays.equals(before, out.array()) || out.position() != position) {
                failed++;
                System.out.println("hks FAIL  失敗時 buffer 被改動");
            }
            return false;
        }
    }

    private static byte[] write(Carcass animal, int capacity) throws IOException {
        ByteBuffer out = ByteBuffer.allocate(capacity);
        MdcAnimalSave.save(animal, out, false);
        return Arrays.copyOf(out.array(), out.position());
    }

    private static long clockWritten(Carcass animal) throws IOException {
        return ByteBuffer.wrap(write(animal, 256)).getLong(1);
    }

    /** 原版 IsoAnimal.save 尾端的鏡像：只有參照有效時寫 1＋座標。 */
    private static byte[] vanillaRecord(Carcass animal, IsoButcherHook hook) {
        ByteBuffer out = ByteBuffer.allocate(64);
        out.put((byte) 0x5A).putLong(animal.fixedClock);
        if (animal.isOnHook() && hook != null && hook.getSquare() != null) {
            out.put((byte) 1).putInt(hook.getSquare().x).putInt(hook.getSquare().y).putInt(hook.getSquare().z);
        } else {
            out.put((byte) 0);
        }
        out.putFloat(animal.getPetTimer()).put((byte) 0).putShort(animal.getOnlineID());
        return Arrays.copyOf(out.array(), out.position());
    }

    private static byte[] hookedRecord(Carcass animal, int x, int y, int z) {
        ByteBuffer out = ByteBuffer.allocate(64);
        out.put((byte) 0x5A).putLong(animal.fixedClock).put((byte) 1).putInt(x).putInt(y).putInt(z)
                .putFloat(animal.getPetTimer()).put((byte) 0).putShort(animal.getOnlineID());
        return Arrays.copyOf(out.array(), out.position());
    }

    private static Carcass carcass(boolean onHook, IsoButcherHook hook, int x, int y, int z) throws Exception {
        Carcass c = (Carcass) raw(Carcass.class);
        c.setOnHook(onHook);
        c.setHook(hook);
        c.attachBackToHookX = x;
        c.attachBackToHookY = y;
        c.attachBackToHookZ = z;
        Field pet = IsoAnimal.class.getDeclaredField("petTimer");
        pet.setAccessible(true);
        pet.setFloat(c, 2.5F);
        c.fixedClock = 1234L;
        return c;
    }

    private static Carcass clock(long clock, long now) throws Exception {
        Carcass c = carcass(false, null, 0, 0, 0);
        c.timeSinceLastUpdate = clock;
        c.now = now;
        c.useClockSite = true;
        return c;
    }

    private static IsoButcherHook liveHook(int x, int y, int z) throws Exception {
        IsoButcherHook hook = (IsoButcherHook) raw(IsoButcherHook.class);
        IsoGridSquare square = (IsoGridSquare) raw(IsoGridSquare.class);
        square.x = x;
        square.y = y;
        square.z = z;
        hook.setSquare(square);
        return hook;
    }

    private static PZCalendar calendar(long millis) {
        PZCalendar cal = PZCalendar.getInstance();
        cal.setTimeInMillis(millis);
        return cal;
    }

    /** 替身：save 依原版尾端格式寫出（掛鉤區段＋petTimer＋wild＋onlineID），時鐘欄位走手術後的改道。 */
    public static class Carcass extends IsoAnimal {
        long fixedClock;
        long now;
        long secondClock;
        boolean useClockSite;
        boolean nestedClock;
        boolean throwAfterClock;

        /** 僅供編譯；實例由 serialization 分配器產生。 */
        Carcass() {
            super((IsoCell) null);
        }

        @Override
        public void save(ByteBuffer out, boolean isDebugSave) throws IOException {
            out.put((byte) 0x5A);
            out.putLong(useClockSite ? MdcAnimalSave.clockToWrite(calendar(now)) : fixedClock);
            if (nestedClock) {
                secondClock = MdcAnimalSave.clockToWrite(calendar(now));
            }
            if (throwAfterClock) {
                throw new IllegalStateException("boom");
            }
            IsoButcherHook hook = getHook();
            if (isOnHook() && hook != null && hook.getSquare() != null) {
                out.put((byte) 1);
                out.putInt(hook.getSquare().x);
                out.putInt(hook.getSquare().y);
                out.putInt(hook.getSquare().z);
            } else {
                out.put((byte) 0);
            }
            out.putFloat(getPetTimer());
            out.put((byte) (isWild() ? 1 : 0));
            out.putShort(getOnlineID());
        }

        @Override
        public boolean isWild() {
            return false;
        }

        @Override
        public short getOnlineID() {
            return 77;
        }

        @Override
        public String getAnimalType() {
            return "pig";
        }

        @Override
        public int getAnimalID() {
            return 8;
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
        System.out.println((ok ? "hks pass  " : "hks FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private MdcAnimalSaveTest() {}
}
