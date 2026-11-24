package zombie.mdc;

import java.util.ArrayList;
import java.util.List;

import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleParts;
import zombie.vehicles.VirtualVehicle;

/**
 * W108 行為驗證（裸 JVM，全用真 class）：chunk 新建的車已照自己的零件建好 models，再從 meta 收養零件。
 *
 * <p>{@code on}：收養後每個 {@code ModelInfo.part} 指向車上現在同 id 的零件，models 原物件不增減，{@code setModelVisible}
 * 找得到既有的 info、拆門時模型消失。{@code off}（{@code -Dmdc.vehicleModelRebind=off}）：零件照樣收養，info 仍指舊零件
 * （原版負對照）。兩種模式都驗：腳本不同時不收養也不重綁；models 有 null 項時例外不外洩、收養照樣完成。
 */
public final class VehicleModelRebindBehaviorTest {

    static final String DOOR = "DoorFrontLeft";
    static final String HOOD = "EngineDoor";

    public static void main(String[] args) throws Exception {
        boolean on = !(args.length > 0 && args[0].equals("off"));
        int failed = 0;
        failed += check("kill switch 與模式一致", VehicleModelRebind.ENABLED == on);

        VehicleScript script = TestObjects.alloc(VehicleScript.class);
        Fixture f = new Fixture(script, script);
        List<BaseVehicle.ModelInfo> before = new ArrayList<>(f.car.models);
        // 和 removeFromMeta offset 12–17 同一個呼叫（改道本身由 SmokeCheck 驗；之後的車燈、音效 setter 與這刀無關）
        VehicleModelRebind.adoptParts(f.car, f.meta.getParts());
        failed += check("零件已收養（parts 換成 meta 的那一份、零件 owner 是車）",
                f.car.getParts() == f.metaParts && f.newDoor.getVehicle() == f.car);
        boolean allCurrent = true;
        boolean allStale = true;
        for (BaseVehicle.ModelInfo info : f.car.models) {
            VehiclePart current = f.car.getPartById(info.part.getId());
            allCurrent &= info.part == current && f.car.getParts().contains(info.part);
            allStale &= info.part == f.oldPart(info.part.getId());
        }
        failed += check("models 仍是同一批物件、數量不變", f.car.models.equals(before) && f.car.models.get(0) == before.get(0));
        if (on) {
            failed += check("每個 ModelInfo.part 都是車上現在同 id 的零件（playPartAnim 開頭的 contains 成立）、重綁 2 個",
                    allCurrent && VehicleModelRebind.rebound == 2);
            failed += check("setModelVisible(現在的門, 門模型, true) 回傳既有的 info（不再多加一個）",
                    f.car.setModelVisible(f.newDoor, f.doorModel, true) == f.doorInfo && f.car.models.size() == 2);
            VehicleModelRebind.adoptParts(f.car, f.car.getParts());
            failed += check("再收養一次（owner 已是車，原版提早 return）重綁 0 個", VehicleModelRebind.rebound == 2);
            f.car.setModelVisible(f.newDoor, f.doorModel, false);
            failed += check("拆門：setModelVisible(現在的門, 門模型, false) 拿掉門的模型",
                    f.car.models.size() == 1 && f.car.models.get(0) == f.hoodInfo);
        } else {
            failed += check("off：ModelInfo.part 全是收養前的舊零件（原版負對照）、不計數",
                    allStale && !allCurrent && VehicleModelRebind.rebound == 0);
        }
        int reboundBefore = VehicleModelRebind.rebound;

        Fixture other = new Fixture(script, TestObjects.alloc(VehicleScript.class));
        VehicleModelRebind.adoptParts(other.car, other.meta.getParts());
        failed += check("腳本不同：不收養，零件與 info 都不動",
                other.car.getParts() == other.ownParts && other.doorInfo.part == other.oldDoor
                        && other.hoodInfo.part == other.oldHood && VehicleModelRebind.rebound == reboundBefore);

        Fixture broken = new Fixture(script, script);
        broken.car.models.add(null);
        VehicleModelRebind.adoptParts(broken.car, broken.meta.getParts());
        failed += check("models 有 null 項：例外不外洩、收養仍完成", broken.car.getParts() == broken.metaParts);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("VehicleModelRebind 行為驗證全數通過（" + (on ? "on" : "off") + "）");
    }

    /** chunk 新建的車（自己的門與引擎蓋、已建好 models）與 meta 裡的同一台車（另一份零件）。 */
    static final class Fixture {
        final BaseVehicle car = TestObjects.alloc(BaseVehicle.class);
        final VirtualVehicle meta = TestObjects.alloc(VirtualVehicle.class);
        final VehicleParts ownParts = new VehicleParts();
        final VehicleParts metaParts = new VehicleParts();
        final VehiclePart oldDoor;
        final VehiclePart oldHood;
        final VehiclePart newDoor;
        final VehicleScript.Model doorModel = TestObjects.alloc(VehicleScript.Model.class);
        final BaseVehicle.ModelInfo doorInfo = new BaseVehicle.ModelInfo();
        final BaseVehicle.ModelInfo hoodInfo = new BaseVehicle.ModelInfo();

        Fixture(VehicleScript carScript, VehicleScript metaScript) throws Exception {
            TestObjects.set(BaseVehicle.class, car, "script", carScript);
            ownParts.setOwner(car);
            oldDoor = part(ownParts, car, DOOR);
            oldHood = part(ownParts, car, HOOD);
            TestObjects.set(BaseVehicle.class, car, "parts", ownParts);
            doorInfo.part = oldDoor;
            doorInfo.scriptModel = doorModel;
            hoodInfo.part = oldHood;
            hoodInfo.scriptModel = TestObjects.alloc(VehicleScript.Model.class);
            TestObjects.set(BaseVehicle.class, car, "models", new ArrayList<>(List.of(doorInfo, hoodInfo)));

            TestObjects.set(VirtualVehicle.class, meta, "script", metaScript);
            metaParts.setOwner(meta);
            newDoor = part(metaParts, meta, DOOR);
            part(metaParts, meta, HOOD);
            part(metaParts, meta, "WindowFrontLeft");   // 沒有模型的零件
            TestObjects.set(VirtualVehicle.class, meta, "parts", metaParts);
        }

        VehiclePart oldPart(String id) {
            return ownParts.getPartById(id);
        }

        static VehiclePart part(VehicleParts parts, zombie.vehicles.VehiclePartOwner owner, String id) {
            VehicleScript.Part scriptPart = new VehicleScript.Part();
            scriptPart.id = id;
            VehiclePart part = new VehiclePart(owner);
            part.setScriptPart(scriptPart);
            parts.add(part);
            return part;
        }
    }

    static int check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private VehicleModelRebindBehaviorTest() {}
}
