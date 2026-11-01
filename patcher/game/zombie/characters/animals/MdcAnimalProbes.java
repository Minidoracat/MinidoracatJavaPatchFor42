package zombie.characters.animals;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import zombie.MovingObjectUpdateScheduler;
import zombie.ai.states.animals.AnimalIdleState;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.entity.EntityBucket;
import zombie.entity.GameEntity;
import zombie.entity.components.fluids.FluidContainer;
import zombie.entity.util.ImmutableArray;
import zombie.network.GameServer;

/**
 * W95 動物與液體容器的純觀測探針（2026-11-02；docs/patches.md 2dd）。不改任何行為、不省時間，量下一輪要請服主裁定的兩個槓桿的規模。
 *
 * <ul>
 *   <li><b>a0 虛擬動物</b>：{@code IsoWorld.updateThread} offset 255 唯一的 {@code AnimalZones.updateVirtualAnimals()} 同形改道
 *       {@link #updateVirtualAnimals}：每次照原版呼叫並量 µs；心跳時在主執行緒掃一次 {@code AnimalManagerWorker.loadedCells}
 *       數 cell、chunk、虛擬動物（沒有遷徙定義＝idle，原版 {@code VirtualAnimal.update} 第一行就 return）、足跡，附 JVM uptime，
 *       看規模是否隨開服時間長大。放在本 package 才讀得到 package-private 的 {@code loadedCells}／{@code chunks}／{@code animals}。</li>
 *   <li><b>F1 液體容器</b>：{@code FluidContainerUpdateSystem.updateSimulation} offset 19 唯一的 {@code EntityBucket.getEntities()}
 *       同形改道 {@link #fluidEntities}：照原版回傳同一個陣列，記 bucket 大小；每 64 次掃一次 rainCatcher &gt; 0 的數量並量這次唯讀掃描的
 *       µs（迴圈對 rainCatcher ≤ 0 的實體什麼都不做，這個比例就是 W35 式索引能省的部分）。</li>
 *   <li><b>sim 動物降頻候選</b>：W89 {@code IdleVehicleThrottle.add} 入口對每隻 {@code IsoAnimal} 呼叫 {@link #sim}（不改 W89 對車的行為）。
 *       每 16 幀抽一幀，數「沒有任何連線收得到（原版送出半徑再加 16 格）而且沒在移動」的動物比例 {@code wouldPct}，以及其中懷孕且壓力
 *       &gt; 80 的隻數 {@code pregStress}。</li>
 * </ul>
 *
 * <p><b>模式</b>（{@code -Dmdc.animalProbes}，三個探針共用，需重啟）：{@code 2|observe}（預設，{@code on} 也當 observe）量測；
 * {@code 0|off} 原樣轉呼叫、不計數。只在伺服器生效。心跳每 5 分鐘一行（由每幀必經的 a0 驅動）。探針自己的例外（含心跳、格式化與日誌鏈）
 * 只記 anomalies、前 5 次記一行例外類別與訊息，不外拋；原版呼叫不在 try 內（例外照原版上拋）。
 */
public final class MdcAnimalProbes {

    static final int OFF = 0;
    static final int OBSERVE = 2;
    static final String RAW = System.getProperty("mdc.animalProbes");
    static final int MODE = parseMode(RAW);
    static final int FLUID_SCAN_MASK = 63;
    static final int SIM_FRAME_MASK = 15;
    private static final int SIM_TIME_MASK = 255;
    /** 「沒有任何連線收得到」的半徑：原版 {@code sendUpdateToClient} 的 {@code (getRelevantRange()-2)*10} 再加這個裕度（格）。 */
    static final int SIM_MARGIN = 16;
    static final float PREG_STRESS = 80.0F;

    private static final String TAG = "[MinidoracatJavaPatch][AnimalProbes] ";
    private static final long HEARTBEAT_MS = 300_000L;

    private static boolean started;
    private static long lastBeat;
    private static long anomalies;
    // a0
    private static long vaCalls, vaNs, vaMaxNs;
    // F1
    private static long fluidCalls, bucketSum, bucketMax, fluidScans, rcSum, fluidScanNs;
    // sim
    private static long simFrame = Long.MIN_VALUE;
    private static boolean simSampled;
    private static long simFrames, simAnimals, simUnseen, simStill, simWould, simPregStress, simSeen, simNs, simTimed;
    static String lastBeatLine;
    static String lastScan;

    static int parseMode(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "off" -> OFF;
            default -> OBSERVE;
        };
    }

    /** a0：{@code IsoWorld.updateThread} 唯一的 {@code AnimalZones.updateVirtualAnimals()}。 */
    public static void updateVirtualAnimals() {
        if (MODE == OFF || !GameServer.server) {
            AnimalZones.updateVirtualAnimals();
            return;
        }
        long t0 = System.nanoTime();
        AnimalZones.updateVirtualAnimals();
        long dt = System.nanoTime() - t0;
        vaCalls++;
        vaNs += dt;
        if (dt > vaMaxNs) {
            vaMaxNs = dt;
        }
        tick();
    }

    /** F1：{@code FluidContainerUpdateSystem.updateSimulation} 唯一的 {@code fluidContainerEntities.getEntities()}。 */
    public static ImmutableArray<GameEntity> fluidEntities(EntityBucket bucket) {
        ImmutableArray<GameEntity> entities = bucket.getEntities();
        if (MODE == OFF || !GameServer.server) {
            return entities;
        }
        try {
            int n = entities.size();
            fluidCalls++;
            bucketSum += n;
            if (n > bucketMax) {
                bucketMax = n;
            }
            if ((fluidCalls & FLUID_SCAN_MASK) == 0) {
                long t0 = System.nanoTime();
                int rc = 0;
                for (int i = 0; i < n; i++) {
                    GameEntity e = entities.get(i);
                    FluidContainer fc = e == null ? null : e.getFluidContainer();
                    if (fc != null && fc.getRainCatcher() > 0.0F) {
                        rc++;
                    }
                }
                fluidScanNs += System.nanoTime() - t0;
                fluidScans++;
                rcSum += rc;
            }
        } catch (Throwable t) {
            anomaly("F1", t);
        }
        return entities;
    }

    /** sim：W89 {@code IdleVehicleThrottle.add} 入口，每隻放進 bucket 的動物每幀一次；只計數，不改放進哪個 bucket。 */
    public static void sim(IsoAnimal a) {
        if (MODE == OFF || !GameServer.server || Thread.currentThread() != GameServer.mainThread) {
            return;
        }
        try {
            long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
            if (frame != simFrame) {
                simFrame = frame;
                simSampled = (frame & SIM_FRAME_MASK) == 0;
                if (simSampled) {
                    simFrames++;
                }
            }
            if (!simSampled) {
                return;
            }
            boolean timed = (simSeen++ & SIM_TIME_MASK) == 0;
            long t0 = timed ? System.nanoTime() : 0L;
            boolean unseen = unseen(a);
            boolean still = still(a);
            simAnimals++;
            if (unseen) {
                simUnseen++;
            }
            if (still) {
                simStill++;
            }
            if (unseen && still) {
                simWould++;
                AnimalData d = a.getData();
                if (d != null && d.isPregnant() && a.getStress() > PREG_STRESS) {
                    simPregStress++;
                }
            }
            if (timed) {
                simNs += System.nanoTime() - t0;
                simTimed++;
            }
        } catch (Throwable t) {
            anomaly("sim", t);
        }
    }

    /** 每條非 null 連線都 {@code !RelevantTo(x, y, (getRelevantRange()-2)*10 + SIM_MARGIN)}。 */
    static boolean unseen(IsoAnimal a) {
        float x = a.getX();
        float y = a.getY();
        List<UdpConnection> conns = GameServer.udpEngine.connections;
        for (int i = 0; i < conns.size(); i++) {
            UdpConnection c = conns.get(i);
            if (c != null && c.RelevantTo(x, y, (c.getRelevantRange() - 2) * 10 + SIM_MARGIN)) {
                return false;
            }
        }
        return true;
    }

    /** 閒置狀態、沒有路徑與行為、沒被驚動或吸引、沒被抱或牽、不在車上、沒打架、沒著火、活著、不在鉤子上、不是剛從 meta 轉回。 */
    static boolean still(IsoAnimal a) {
        BaseAnimalBehavior b = a.getBehavior();
        AnimalData d = a.getData();
        return a.getCurrentState() == AnimalIdleState.instance() && a.getPath2() == null && (b == null || !b.isDoingBehavior)
                && !a.isAlerted() && a.getLastSoundRespondedTo() == null && a.getVehicle() == null && a.heldBy == null
                && (d == null || d.getAttachedPlayer() == null) && a.luredBy == null && a.fightingOpponent == null
                && !a.isOnFire() && !a.isDead() && !a.isOnHook() && !a.fromMeta;
    }

    /** 首次生效與心跳；整段不外拋（例外只記 anomalies），狀態先更新再記錄，失敗也不會每幀重試。 */
    private static void tick() {
        try {
            long now = System.currentTimeMillis();
            if (!started) {
                started = true;
                lastBeat = now;
                log("首次生效 mode=" + modeName() + " raw=" + RAW
                        + "（-Dmdc.animalProbes=0|off 不量、2|observe 量虛擬動物、液體容器與動物降頻候選；純觀測；心跳每 5 分鐘一行）");
            }
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        } catch (Throwable t) {
            anomaly("beat", t);
        }
    }

    /** 探針自己的例外：計數，前 5 次記一行例外類別與訊息；本身不外拋。 */
    private static void anomaly(String where, Throwable t) {
        anomalies++;
        if (anomalies <= 5) {
            try {
                log("anomaly #" + anomalies + " " + where + " " + t.getClass().getName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
                anomalies++;
            }
        }
    }

    /** 主執行緒掃一次已載入的 AnimalCell。O(cell × 1024 chunk)，只在心跳時跑。 */
    static String scanVirtual() {
        int cells = 0, chunks = 0, va = 0, idle = 0, tracks = 0;
        HashMap<String, MigrationGroupDefinitions> defs = MigrationGroupDefinitions.migrationDef;
        ArrayList<AnimalCell> loaded = AnimalManagerWorker.getInstance().loadedCells;
        for (int i = 0; i < loaded.size(); i++) {
            AnimalCell cell = loaded.get(i);
            if (cell == null || cell.chunks == null) {
                continue;
            }
            cells++;
            for (AnimalChunk ch : cell.chunks) {
                if (ch == null) {
                    continue;
                }
                int n = ch.animals.size();
                tracks += ch.animalTracks.size();
                if (n + ch.animalTracks.size() > 0) {
                    chunks++;
                }
                va += n;
                for (int j = 0; j < n; j++) {
                    VirtualAnimal v = ch.animals.get(j);
                    if (defs != null && v != null && defs.get(v.migrationGroup) == null) {
                        idle++;
                    }
                }
            }
        }
        return "cells=" + cells + " chunks=" + chunks + " va=" + va + " vaIdle=" + (defs == null ? "?" : idle)
                + " vaMoving=" + (defs == null ? "?" : va - idle) + " tracks=" + tracks;
    }

    private static void beat() {
        String scan;
        try {
            scan = scanVirtual();
        } catch (Throwable t) {
            anomaly("scan", t);
            scan = "scan=" + t.getClass().getSimpleName();
        }
        lastScan = scan;
        String line = "beat mode=" + modeName() + " uptimeS=" + ManagementFactory.getRuntimeMXBean().getUptime() / 1000
                + " vaCalls=" + vaCalls + " vaUsAvg=" + avgUs(vaNs, vaCalls) + " vaUsMax=" + vaMaxNs / 1000 + " " + scan
                + " fluidCalls=" + fluidCalls + " bucketAvg=" + (fluidCalls == 0 ? 0 : bucketSum / fluidCalls) + " bucketMax=" + bucketMax
                + " rcAvg=" + (fluidScans == 0 ? 0 : rcSum / fluidScans) + " fluidScanUsAvg=" + avgUs(fluidScanNs, fluidScans)
                + " fluidScans=" + fluidScans
                + " simFrames=" + simFrames + " animals=" + (simFrames == 0 ? 0 : simAnimals / simFrames)
                + " unseenPct=" + pct(simUnseen, simAnimals) + " stillPct=" + pct(simStill, simAnimals)
                + " wouldPct=" + pct(simWould, simAnimals)
                + " pregStress=" + (simFrames == 0 ? "0" : String.format(Locale.ROOT, "%.1f", (double) simPregStress / simFrames))
                + " simUsAvg=" + avgUs(simNs, simTimed) + " anomalies=" + anomalies;
        vaCalls = vaNs = vaMaxNs = 0;
        fluidCalls = bucketSum = bucketMax = fluidScans = rcSum = fluidScanNs = 0;
        simFrames = simAnimals = simUnseen = simStill = simWould = simPregStress = simNs = simTimed = 0;
        lastBeatLine = line;
        log(line);
    }

    private static String avgUs(long ns, long n) {
        return n == 0 ? "0" : String.format(Locale.ROOT, "%.2f", ns / 1000.0 / n);
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "0" : String.format(Locale.ROOT, "%.1f", part * 100.0 / whole);
    }

    static String modeName() {
        return MODE == OFF ? "off" : "observe";
    }

    /** 記錄不外拋：日誌鏈（格式化、輸出）的例外只記 anomalies，不影響原版呼叫。 */
    private static void log(String s) {
        try {
            DebugLog.log(TAG + s);
        } catch (Throwable ignored) {
            anomalies++;
        }
    }

    static void forceBeatForTest() {
        armBeatForTest();
        tick();
    }

    /** 下一次 a0 就印心跳。 */
    static void armBeatForTest() {
        started = true;
        lastBeat = System.currentTimeMillis() - HEARTBEAT_MS;
    }

    static long anomaliesForTest() { return anomalies; }
    static long vaCallsForTest() { return vaCalls; }
    static long fluidCallsForTest() { return fluidCalls; }
    static long fluidScansForTest() { return fluidScans; }
    static long rcSumForTest() { return rcSum; }
    static long simAnimalsForTest() { return simAnimals; }
    static long simWouldForTest() { return simWould; }
    static long simPregStressForTest() { return simPregStress; }

    private MdcAnimalProbes() {}
}
