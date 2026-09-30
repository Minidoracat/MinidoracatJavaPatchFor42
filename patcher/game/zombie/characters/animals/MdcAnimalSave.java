package zombie.characters.animals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;

import zombie.debug.DebugLog;
import zombie.iso.IsoButcherHook;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.mdc.AnimalAwayProbe;
import zombie.util.PZCalendar;

/**
 * apop 動物存檔的兩項修正（2026-10-01）：W49-A3 已卸載動物寫出自身時鐘（docs/patches.md 2bm）、
 * W50 掛鉤屠體保住掛鉤狀態（2bn）。
 *
 * <p><b>掛點</b>：{@code VirtualAnimal.save} 內唯一的 {@code IsoAnimal.save(ByteBuffer, boolean)} 改道 {@link #save}。
 * apop（{@code AnimalCell → AnimalChunk → VirtualAnimal → IsoAnimal}）只經這一處；網路封包、背包、雞舍呼叫的
 * {@code IsoAnimal.save} 不受影響。
 *
 * <p><b>W49-A3</b>：原版 {@code IsoAnimal.save} 的時鐘欄位寫存檔當下，而不是動物的 {@code timeSinceLastUpdate}，
 * 所以「玩家離開到下次重啟」這段離線時間在重啟後無從補算。{@link #save} 以 {@code try/finally} 記下目前寫出的
 * 動物；{@code IsoAnimal.save} 內唯一的 {@code PZCalendar.getTimeInMillis} 改道 {@link #clockToWrite}，
 * 只在這個上下文中、且動物不在 {@code currentCell.getObjectList()}（{@code saveRealAnimals} 收集世界中動物的
 * 條件正是這個集合，兩者在 {@code AnimalPopulationManager.save()} 同一執行緒依序執行）時寫動物自身時鐘；
 * 時鐘無紀錄、在未來、取不到 cell 或例外，一律照原版寫存檔當下。上下文在第一次時鐘寫入時消耗，只屬於該筆紀錄。
 * 跟隨 {@code -Dmdc.animalOwnClock}（依賴 W42）。
 *
 * <p><b>W50</b>：原版只在 {@code isOnHook() && hook != null && hook.getSquare() != null} 時寫 onHook=1＋鉤子座標；
 * {@code hook} 不存檔，從磁碟載入的屠體要等進世界後第一次 {@code update()} 的 {@code reattachBackToHook()}
 * 才補回參照。這之前 cell 被存檔就寫成 onHook=0，下次載入變活體。{@link #save} 在原版寫完後，若屠體仍掛鉤、
 * 參照無效、且帶著載入時還原的 {@code attachBackToHook} 座標，就在 buffer 內把該筆尾端的 onHook=0 改成 1、
 * 補寫三個座標 int，其後 7 bytes（petTimer、wild、onlineID）後移 12 bytes——與原版正常掛鉤時的格式相同。
 * 所有檢查都在改寫之前；尾端格式不符或容量不足時拋 {@code IOException}，由 W37 保留舊檔並下次重試
 * （絕不提交已知錯誤的 onHook=0）。W37 關閉時原版先開檔才序列化，拋例外會留下截斷的檔案，故本刀一併停用。
 * 座標缺席時照原版寫出並計數（正常流程不會發生，出現即需人工調查）。
 * {@link #afterReattach} 掛在 {@code reattachBackToHook} 每個 RETURN 前，只觀測「鉤子格已載入、格上卻沒有
 * 鉤子」的屠體（B2），不改行為。kill switch {@code -Dmdc.animalHookSave=0}。
 */
public final class MdcAnimalSave {

    private static final boolean HOOK = !"0".equals(System.getProperty("mdc.animalHookSave"))
            && MdcAnimalCellSave.enabled();
    private static final boolean OWN = AnimalAwayProbe.ownClockEnabled();
    /** 原版寫 onHook=0 時的尾端：flag(1)＋petTimer(4)＋wild(1)＋onlineID(2)。 */
    static final int TAIL = 8;
    /** 補寫的三個鉤子座標 int。 */
    static final int COORDS = 12;
    private static final long HEARTBEAT_MS = 300_000L;
    private static final String TAG = "[MinidoracatJavaPatch][AnimalSave] ";

    /** 目前正在 apop 寫出的動物：{@link #save} 設定並在 finally 還原，{@link #clockToWrite} 消耗一次。 */
    private static final ThreadLocal<IsoAnimal> WRITING = new ThreadLocal<>();
    /** 已記錄過的屠體（每個物件每類只記一行）；存檔執行緒與主執行緒都會觸碰，同步保護。 */
    private static final Map<IsoAnimal, Boolean> SAVE_LOGGED = new WeakHashMap<>();
    private static final Map<IsoAnimal, Boolean> MISSING_LOGGED = new WeakHashMap<>();

    private static long kept, noCoords, ioFail, hookMissing, ownClock, saveNow, anomalies;
    private static long lastBeat;
    private static boolean bannerShown;

    /** {@code VirtualAnimal.save} 內唯一的 {@code animal.save(output, false)}。 */
    public static void save(IsoAnimal animal, ByteBuffer out, boolean isDebugSave) throws IOException {
        int start = out.position();
        if (OWN) {
            IsoAnimal previous = WRITING.get();
            WRITING.set(animal);
            try {
                animal.save(out, isDebugSave);
            } finally {
                WRITING.set(previous);
            }
        } else {
            animal.save(out, isDebugSave);
        }
        if (HOOK && animal.isOnHook()) {
            keepHook(animal, out, start);
        }
        beat();
    }

    /** {@code IsoAnimal.save(ByteBuffer, boolean, boolean)} 內唯一的 {@code getTimeInMillis}（動物時鐘欄位）。 */
    public static long clockToWrite(PZCalendar calendar) {
        long now = calendar.getTimeInMillis();
        IsoAnimal animal = WRITING.get();
        if (animal == null) {
            return now;
        }
        WRITING.set(null);
        try {
            long clock = animal.timeSinceLastUpdate;
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
            if (clock <= 0L || clock > now || cell == null || cell.getObjectList().contains(animal)) {
                saveNow++;
                return now;
            }
            ownClock++;
            return clock;
        } catch (RuntimeException | LinkageError e) {
            anomalies++;
            return now;
        }
    }

    /** {@code IsoAnimal.reattachBackToHook} 每個 RETURN 前：觀測鉤子確實不存在的屠體（B2），不改行為。 */
    public static void afterReattach(IsoAnimal animal) {
        if (!HOOK || !animal.isOnHook()) {
            return;
        }
        int x = animal.attachBackToHookX;
        int y = animal.attachBackToHookY;
        if (x == 0 && y == 0) {
            return;   // 已掛回（reattach 成功會清座標）或從未記座標
        }
        try {
            IsoButcherHook hook = animal.getHook();
            if (hook != null && hook.getSquare() != null) {
                return;
            }
            IsoGridSquare square = animal.getSquare();
            if (square == null) {
                return;
            }
            IsoGridSquare hookSquare = square.getCell().getGridSquare(x, y, animal.attachBackToHookZ);
            if (hookSquare == null || hookSquare.getObjects() == null) {
                return;   // 鉤子格還沒載入：原版下一幀再試
            }
            for (int i = 0; i < hookSquare.getObjects().size(); i++) {
                IsoObject object = hookSquare.getObjects().get(i);
                if (object instanceof IsoButcherHook) {
                    return;
                }
            }
            if (firstTime(MISSING_LOGGED, animal)) {
                hookMissing++;
                DebugLog.log(TAG + "hook missing: carcass=" + animal.getAnimalType() + "#" + animal.getAnimalID()
                        + " hook=" + x + "," + y + "," + animal.attachBackToHookZ
                        + " pos=" + (int) animal.getX() + "," + (int) animal.getY() + "," + (int) animal.getZ()
                        + " hookMissing=" + hookMissing);
            }
        } catch (RuntimeException e) {
            anomalies++;
        }
    }

    private static void keepHook(IsoAnimal animal, ByteBuffer out, int start) throws IOException {
        IsoButcherHook hook = animal.getHook();
        if (hook != null && hook.getSquare() != null) {
            return;   // 原版已寫 onHook=1
        }
        int x = animal.attachBackToHookX;
        int y = animal.attachBackToHookY;
        int z = animal.attachBackToHookZ;
        if (x == 0 && y == 0) {   // 與原版 reattachBackToHook 相同的「無座標」哨兵（兩軸同時為 0）
            noCoords++;
            if (firstTime(SAVE_LOGGED, animal)) {
                DebugLog.log(TAG + "carcass without hook coordinates saved as vanilla: " + animal.getAnimalType()
                        + "#" + animal.getAnimalID() + " pos=" + (int) animal.getX() + "," + (int) animal.getY()
                        + "," + (int) animal.getZ() + " noCoords=" + noCoords);
            }
            return;
        }
        try {
            restoreHookTail(out, start, x, y, z, animal.getPetTimer(), animal.isWild(), animal.getOnlineID());
        } catch (IOException e) {
            ioFail++;
            DebugLog.log(TAG + "hook state not written, keeping previous file: " + animal.getAnimalType() + "#"
                    + animal.getAnimalID() + " reason=" + e.getMessage() + " ioFail=" + ioFail);
            throw e;
        }
        kept++;
        if (firstTime(SAVE_LOGGED, animal)) {
            DebugLog.log(TAG + "kept hook state: " + animal.getAnimalType() + "#" + animal.getAnimalID()
                    + " hook=" + x + "," + y + "," + z + " kept=" + kept);
        }
    }

    /**
     * 把剛寫出的尾端 [0][petTimer][wild][onlineID] 改成 [1][x][y][z][petTimer][wild][onlineID]（原版掛鉤格式）。
     * 先驗證尾端四個欄位與剩餘容量，全部通過才改寫；否則拋 IOException、buffer 不動。
     */
    static void restoreHookTail(ByteBuffer out, int start, int x, int y, int z, float petTimer, boolean wild,
            short onlineId) throws IOException {
        int end = out.position();
        int flag = end - TAIL;
        if (flag < start) {
            throw new IOException("record shorter than hook tail");
        }
        byte wildByte = (byte) (wild ? 1 : 0);
        if (out.get(flag) != 0
                || Float.floatToRawIntBits(out.getFloat(flag + 1)) != Float.floatToRawIntBits(petTimer)
                || out.get(flag + 5) != wildByte
                || out.getShort(flag + 6) != onlineId) {
            throw new IOException("unexpected record tail");
        }
        if (out.limit() - end < COORDS) {
            throw new IOException("buffer full");
        }
        out.put(flag, (byte) 1);
        out.putInt(flag + 1, x);
        out.putInt(flag + 5, y);
        out.putInt(flag + 9, z);
        out.putFloat(flag + 13, petTimer);
        out.put(flag + 17, wildByte);
        out.putShort(flag + 18, onlineId);
        out.position(end + COORDS);
    }

    private static boolean firstTime(Map<IsoAnimal, Boolean> logged, IsoAnimal animal) {
        synchronized (logged) {
            return logged.put(animal, Boolean.TRUE) == null;
        }
    }

    private static void beat() {
        long now = System.currentTimeMillis();
        if (!bannerShown) {
            bannerShown = true;
            lastBeat = now;
            DebugLog.log(TAG + "mode hookSave=" + (HOOK ? 1 : 0) + " ownClock=" + (OWN ? 1 : 0));
            return;
        }
        if (now - lastBeat >= HEARTBEAT_MS) {
            lastBeat = now;
            DebugLog.log(TAG + "kept=" + kept + " noCoords=" + noCoords + " ioFail=" + ioFail
                    + " hookMissing=" + hookMissing + " ownClock=" + ownClock + " saveNow=" + saveNow
                    + " anomalies=" + anomalies + " hookSave=" + (HOOK ? 1 : 0) + " ownClockMode=" + (OWN ? 1 : 0));
        }
    }

    // ---- 測試存取器 ----
    static boolean hookForTest() { return HOOK; }
    static boolean ownForTest() { return OWN; }
    static long keptForTest() { return kept; }
    static long noCoordsForTest() { return noCoords; }
    static long ioFailForTest() { return ioFail; }
    static long hookMissingForTest() { return hookMissing; }
    static long ownClockForTest() { return ownClock; }
    static long saveNowForTest() { return saveNow; }
    static long anomaliesForTest() { return anomalies; }
    static IsoAnimal writingForTest() { return WRITING.get(); }

    private MdcAnimalSave() {}
}
