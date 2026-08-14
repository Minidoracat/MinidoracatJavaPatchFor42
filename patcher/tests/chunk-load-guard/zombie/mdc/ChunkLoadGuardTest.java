package zombie.mdc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import zombie.debug.DebugLogStream;
import zombie.debug.DebugType;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;
import zombie.vehicles.BaseVehicle;

/**
 * W6 地圖格載入捕手行為測試。
 *
 * <p>核心是<b>負對照</b>：先證明測試替身真的會把例外丟出來（不然「守衛沒拋」這件事
 * 毫無意義），再證明守衛吞得住。另外兩條專門堵假綠通道：
 * <ul>
 *   <li><b>正向直通</b>——一個「什麼都不做的空 helper」能通過所有吞例外測試，
 *       但那會讓全世界的物件都不再登記進引擎。故必須驗證正常物件的
 *       {@code addToWorld()} 真的被呼叫到。</li>
 *   <li><b>Error 不吞</b>——OOM／StackOverflow／LinkageError 是 VM 級故障，
 *       吞掉遠比凍結更糟。{@code catch (RuntimeException)} 必須放它們過去。</li>
 * </ul>
 *
 * <p>物件建構：{@code IsoObject} 的公開建構子會拉起貼圖／世界依賴（測試環境無法初始化），
 * 而本測試只在乎「呼叫是否被轉發」與「診斷是否讀得到座標」，故以 serialization 建構子
 * 分配未初始化實例。{@code IsoGridSquare} 為 final class 但 x/y/z 是 public 欄位，
 * 同法分配後直接賦值。
 */
public final class ChunkLoadGuardTest {

    public static void main(String[] args) throws Exception {
        // 旋鈕是否生效必須由**呼叫端宣告的期望**驗證。原本只靠 build.ps1 傳 -D 然後看 exit code，
        // 於是 property 名稱一旦與 helper 不一致（camelCase 很容易在改名時漏掉），第二次執行
        // 只是把 enabled 版再跑一遍、照樣 exit 0，而緊急降級路徑其實從未被測過。
        boolean expectDisabled = args.length > 0 && "disabled".equals(args[0]);
        require(ChunkLoadGuard.enabledForTest() != expectDisabled,
                "旋鈕狀態與呼叫端期望不符——property 名稱與 helper 不一致？");
        if (!ChunkLoadGuard.enabledForTest()) {
            killSwitchIsPassthrough();       // -Dmdc.chunkLoadGuard.enabled=false 模式
            return;
        }
        fixtureReallyThrows();
        guardSwallowsAndCountsIt();
        normalObjectIsActuallyAddedToWorld();
        movingObjectOverloadIsGuardedToo();
        vehiclesAreNotSwallowed();
        diagnosticsPinpointTheSquare();
        diagnosticsReachTheRealLog();
        loggerFailureNeverEscapes();
        loggerLinkageErrorNeverEscapes();
        heartbeatActuallyEmits();
        budgetIsPerDistinctSquare();
        anomalyPathIsExercised();
        errorsAreNotSwallowed();
        halfInitialisedObjectDoesNotBreakDiagnostics();
        nullReceiverIsHandled();
        reportBudgetCapsWithoutLosingCount();
        System.out.println("chunk-load OK  替身必拋/守衛吞下/正向真的入世界/屍體迴圈多載/座標定位/"
                + "真實 log 落地/logger RuntimeException 與 LinkageError 皆不外逃/心跳真的印/"
                + "額度按相異方格/anomaly 路徑/Error 不吞/半初始化/null/額度封頂全數通過");
    }

    /**
     * 負對照：不經守衛直接呼叫，例外必須傳播。
     * 沒有這條，一個永遠不拋的替身會讓後面每一條「守衛沒拋」都是空話。
     */
    private static void fixtureReallyThrows() throws Exception {
        Fake obj = fake();
        obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
        try {
            obj.addToWorld();
            throw new AssertionError("替身必須拋出例外，否則整組測試是空的");
        } catch (IllegalArgumentException expected) {
            require(obj.calls == 1, "替身應被呼叫恰一次，實得 " + obj.calls);
        }
    }

    /** 守衛必須吞下 RuntimeException、計數、且確實有轉發到被包住的方法。 */
    private static void guardSwallowsAndCountsIt() throws Exception {
        ChunkLoadGuard.resetForTest();
        Fake obj = fake();
        obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
        ChunkLoadGuard.addToWorld(obj);      // 不得拋
        require(obj.calls == 1, "守衛必須轉發恰一次，實得 " + obj.calls);
        long[] s = ChunkLoadGuard.statsForTest();
        require(s[0] == 1, "caught 應為 1，實得 " + s[0]);
        require(s[2] == 0, "診斷路徑不得產生 anomaly，實得 " + s[2]);
    }

    /**
     * 假綠通道封堵：一個空 helper（連呼叫都不做）能通過所有吞例外測試，
     * 但那等同於全世界的物件都不再登記進引擎。正常物件必須真的被轉發。
     */
    private static void normalObjectIsActuallyAddedToWorld() throws Exception {
        ChunkLoadGuard.resetForTest();
        Fake obj = fake();                   // toThrow 為 null＝正常物件
        ChunkLoadGuard.addToWorld(obj);
        require(obj.calls == 1, "正常物件的 addToWorld 必須真的被呼叫，實得 " + obj.calls);
        require(ChunkLoadGuard.statsForTest()[0] == 0, "正常路徑不得計入 caught");
    }

    /**
     * 屍體迴圈（{@code getStaticMovingObjects()}）那一處的多載必須同樣被守住。
     * 初版漏掉它時，兩道獨立審查都指出：守衛對三分之二的觸發路徑失效，而所有計數斷言全綠。
     */
    private static void movingObjectOverloadIsGuardedToo() throws Exception {
        ChunkLoadGuard.resetForTest();
        FakeMoving obj = (FakeMoving) rawInstance(FakeMoving.class);
        obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
        ChunkLoadGuard.addToWorld((IsoMovingObject) obj);   // 必須解析到多載且不得拋
        require(obj.calls == 1, "多載必須轉發恰一次，實得 " + obj.calls);
        require(ChunkLoadGuard.statsForTest()[0] == 1, "多載必須計入同一組統計");
    }

    /**
     * 宣告的範圍邊界必須可執行，不能只是註解（審查抓到）。
     * {@code BaseVehicle extends IsoMovingObject}，所以若有 vehicle 進到
     * {@code getStaticMovingObjects()}，它會經由 {@code IsoMovingObject} 多載被吞掉
     * ——正是文件明文拒絕的那件事（它的方法體含 parts／engine 掛載，範圍大得多）。
     */
    private static void vehiclesAreNotSwallowed() throws Exception {
        ChunkLoadGuard.resetForTest();
        // BaseVehicle 是 final class，無法子類化——直接配置一個未初始化的真實實例。
        // 它的 addToWorld 會在半初始化狀態下爆掉，而**那正是我們要的**：例外必須傳播出來。
        BaseVehicle veh = (BaseVehicle) rawInstance(BaseVehicle.class);
        boolean threw = false;
        try {
            ChunkLoadGuard.addToWorld((IsoMovingObject) veh);
        } catch (Throwable expected) {
            threw = true;
        }
        require(threw, "vehicle 必須維持 vanilla 行為（例外照拋），不得被守衛吞下");
        // 決定性斷言：拿掉 instanceof BaseVehicle 直通後，這個例外會被吞下並計數 → caught==1
        require(ChunkLoadGuard.statsForTest()[0] == 0,
                "vehicle 不得計入 caught（＝不得被守衛吞下），實得 " + ChunkLoadGuard.statsForTest()[0]);
    }

    /**
     * 這是整把刀存在的理由：出事時 vanilla 只給 25 份一模一樣的 stack 然後靜音，
     * 連是哪一格都不知道。座標與 sprite 名必須真的出現在診斷字串裡。
     */
    private static void diagnosticsPinpointTheSquare() throws Exception {
        ChunkLoadGuard.resetForTest();
        Fake obj = fake();
        obj.square = square(7130, 6077, 0);
        obj.sprite = sprite("blends_natural_01_53");
        obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
        ChunkLoadGuard.addToWorld(obj);
        String site = ChunkLoadGuard.firstSiteForTest();
        require(site != null, "必須記下案發位置");
        require(site.contains("7130,6077,0"), "診斷必須含方格座標，實得：" + site);
        require(site.contains("blends_natural_01_53"), "診斷必須含 sprite 名，實得：" + site);
        // 決定性欄位：addedToEngine 把「單純重複 add」與「真的不變量破壞」分開，
        // 兩者的後續調查方向完全不同。沒有它，凌晨三點拿到座標也還是只能猜。
        require(site.contains("addedToEngine="), "診斷必須含 addedToEngine，實得：" + site);
        require(site.contains(" id="), "診斷必須含 identity（分辨單一兇手 vs 系統性），實得：" + site);
        // 命名是 objectChunkJob 而非 job：讀的是該物件自己的 chunk，不是正在載入的那個，
        // 屬 best-effort 線索。名稱本身就是給運維的誠實標示，故一併釘住。
        require(site.contains(" objectChunkJob="),
                "診斷必須含 objectChunkJob（驗 SoftReset 假說），實得：" + site);
        require(ChunkLoadGuard.statsForTest()[2] == 0, "診斷路徑不得產生 anomaly");
    }

    /**
     * 假綠通道封堵（審查抓到）：所有鑑識斷言原本都只讀 {@code firstSiteForTest()}，
     * 那是 production 永遠不會碰的 package-private 測試欄位。把 helper 裡每一行
     * {@code DebugLog.log} 全部刪掉，整組測試照樣全綠——而那正是本刀唯一的產出通道。
     * 這條改為觀測真正的 {@code DebugLogStream}。
     */
    private static void diagnosticsReachTheRealLog() throws Exception {
        ChunkLoadGuard.resetForTest();
        Probe probe = new Probe();
        withProbe(probe, () -> {
            Fake obj = fake();
            obj.square = square(7130, 6077, 0);
            obj.sprite = sprite("blends_natural_01_53");
            obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
            ChunkLoadGuard.addToWorld(obj);
        });
        require(probe.lines.size() == 1, "應恰好輸出一行，實得 " + probe.lines.size());
        String line = probe.lines.get(0);
        require(line.contains("[MinidoracatJavaPatch][ChunkLoadGuard]"), "缺前綴：" + line);
        require(line.contains("7130,6077,0"), "真實 log 必須含方格座標：" + line);
        require(line.contains("blends_natural_01_53"), "真實 log 必須含 sprite 名：" + line);
    }

    /**
     * 審查抓到的 blocking：若拋出的正是 {@code DebugLog.log} 本身，
     * 舊版的 anomaly 處理會用「再 log 一次」回應，例外於是逃出守衛回到 doLoadGridsquare
     * ——把捕手本身變成新的凍結源。這條把那個場景直接做出來。
     */
    private static void loggerFailureNeverEscapes() throws Exception {
        ChunkLoadGuard.resetForTest();
        Probe probe = new Probe();
        probe.failure = new IllegalStateException("<test> logger down");
        withProbe(probe, () -> {
            Fake obj = fake();
            obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
            ChunkLoadGuard.addToWorld(obj);   // logger 全程爆炸，但守衛不得拋
        });
        require(probe.attempts > 0, "應真的嘗試過寫 log");
        require(ChunkLoadGuard.statsForTest()[0] == 1, "仍應計入 caught");
    }

    /**
     * anomaly 分支原本零覆蓋（{@code anomalies} 四處只斷言 == 0、無一斷言 > 0）。
     * 用一個 {@code toString()} 會爆的例外，讓字串串接在 try 內部就炸掉。
     */
    private static void anomalyPathIsExercised() throws Exception {
        ChunkLoadGuard.resetForTest();
        Fake obj = fake();
        obj.toThrow = new NastyException();
        ChunkLoadGuard.addToWorld(obj);       // 不得拋
        long[] s = ChunkLoadGuard.statsForTest();
        require(s[0] == 1, "仍應計入 caught，實得 " + s[0]);
        require(s[2] == 1, "anomaly 分支必須被走到，實得 " + s[2]);
    }

    /**
     * null receiver：vanilla 會在 {@code INVOKEVIRTUAL} 直接 NPE 並凍結。
     * 守衛模式下必須吞下並留 {@code object=null} 的紀錄；旋鈕模式下必須維持 vanilla 行為。
     */
    private static void nullReceiverIsHandled() throws Exception {
        ChunkLoadGuard.resetForTest();
        ChunkLoadGuard.addToWorld((IsoObject) null);   // 不得拋
        long[] s = ChunkLoadGuard.statsForTest();
        require(s[0] == 1, "null receiver 應計入 caught，實得 " + s[0]);
        // 這是與 already-registered 無關、可能更嚴重的另一種損壞，語氣必須不同，
        // 否則會被當成同一個 bug 的第 N 次
        require(ChunkLoadGuard.firstSiteForTest().contains("方格物件清單含 null 項"),
                "null 項須以專屬訊息回報，實得 " + ChunkLoadGuard.firstSiteForTest());
    }

    /**
     * logger 丟 {@code Error}（非 VM 級）時守衛仍不得外逃。
     * 這正是 helper 內層 {@code rethrowFatal} 的判斷點：舊版把 {@code LinkageError} 一併重拋，
     * 於是「診斷失敗」被升級成「回到 doLoadGridsquare 凍結全服」——把捕手變成新的凍結源。
     */
    private static void loggerLinkageErrorNeverEscapes() throws Exception {
        ChunkLoadGuard.resetForTest();
        Probe probe = new Probe();
        probe.failure = new NoClassDefFoundError("<test> linkage");
        withProbe(probe, () -> {
            Fake obj = fake();
            obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
            ChunkLoadGuard.addToWorld(obj);   // 不得拋
        });
        require(ChunkLoadGuard.statsForTest()[0] == 1, "仍應計入 caught");
    }

    /**
     * 心跳路徑原本零覆蓋——{@code lastHeartbeatNs=0} 哨兵在負原點 JVM 上會讓心跳一輩子不印，
     * 而舊斷言只看計數器，哨兵壞掉照樣全綠。
     */
    private static void heartbeatActuallyEmits() throws Exception {
        ChunkLoadGuard.resetForTest();
        Probe probe = new Probe();
        withProbe(probe, () -> {
            for (int i = 0; i < ChunkLoadGuard.maxReportsForTest() + 1; i++) {
                Fake obj = fake();
                obj.square = square(500 + i, 600, 0);
                obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
                ChunkLoadGuard.addToWorld(obj);
            }
            probe.lines.clear();
            ChunkLoadGuard.expireHeartbeatForTest();
            Fake obj = fake();
            obj.square = square(999, 999, 0);
            obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
            ChunkLoadGuard.addToWorld(obj);
        });
        require(probe.lines.size() == 1, "心跳應恰好輸出一行，實得 " + probe.lines.size());
        require(probe.lines.get(0).contains("仍在發生"), "應為心跳行：" + probe.lines.get(0));
        require(probe.lines.get(0).contains("本區間 +"), "心跳應含區間增量：" + probe.lines.get(0));
    }

    /**
     * 明細額度按**相異方格**計。同一格重複觸發不得吃掉額度——否則一格幾小時就把 20 格用光，
     * 之後其他損壞方格的座標永久遺失，而「是否集中於特定建築」正是本刀唯一想回答的問題。
     */
    private static void budgetIsPerDistinctSquare() throws Exception {
        ChunkLoadGuard.resetForTest();
        for (int i = 0; i < 50; i++) {
            Fake obj = fake();
            obj.square = square(7130, 6077, 0);      // 永遠同一格
            obj.sprite = sprite("blends_natural_01_53");
            obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
            ChunkLoadGuard.addToWorld(obj);
        }
        long[] s = ChunkLoadGuard.statsForTest();
        require(s[0] == 50, "caught 應為 50，實得 " + s[0]);
        require(s[1] == 1, "同一格只該花掉一格明細額度，實得 " + s[1]);
        require(s[4] == 1, "相異方格應為 1，實得 " + s[4]);

        // 換一格：必須還拿得到額度（證明額度沒被第一格吃光）
        Fake other = fake();
        other.square = square(2929, 9179, 0);
        other.toThrow = new IllegalArgumentException("Entity is already registered <test>");
        ChunkLoadGuard.addToWorld(other);
        s = ChunkLoadGuard.statsForTest();
        require(s[1] == 2, "新方格應取得明細額度，實得 " + s[1]);
        require(s[4] == 2, "相異方格應為 2，實得 " + s[4]);
    }

    /** VM 級故障必須保持致命且可見——把它們吞掉遠比凍結更糟。 */
    private static void errorsAreNotSwallowed() throws Exception {
        ChunkLoadGuard.resetForTest();
        Fake obj = fake();
        obj.errorToThrow = new StackOverflowError("<test>");
        try {
            ChunkLoadGuard.addToWorld(obj);
            throw new AssertionError("Error 不得被守衛吞掉");
        } catch (StackOverflowError expected) {
            require(ChunkLoadGuard.statsForTest()[0] == 0, "Error 不應計入 caught");
        }
    }

    /**
     * 例外發生時物件正處於半初始化狀態（super.addToWorld 是 offset 0，後面全沒跑），
     * 任何 getter 都可能再爆。診斷自己絕不能變成新的凍結源。
     */
    private static void halfInitialisedObjectDoesNotBreakDiagnostics() throws Exception {
        ChunkLoadGuard.resetForTest();
        Fake obj = fake();
        // 刻意給**有效**的 square 與 sprite，再讓 getter 自己爆。原本三者一起設成 null／true，
        // 於是「getter 不爆的替身」會產生位元相同的觀測結果，斷言無法因它宣稱的理由而失敗。
        obj.square = square(1, 2, 3);
        obj.sprite = sprite("should-never-be-read");
        obj.throwFromGetters = true;
        obj.toThrow = new IllegalStateException("<test>");
        ChunkLoadGuard.addToWorld(obj);      // 不得拋
        long[] s = ChunkLoadGuard.statsForTest();
        require(s[0] == 1, "仍應計入 caught，實得 " + s[0]);
        require(s[2] == 0, "getter 爆掉應被 safe 包裝吸收，不得成為 anomaly，實得 " + s[2]);
        String site = ChunkLoadGuard.firstSiteForTest();
        // 哨兵值必須逐種可分辨：「物件本來就沒有 square」與「getSquare() 爆了」不能同字串，
        // 否則凌晨三點看到 200 行的人無法判斷是守衛正常還是什麼都沒蒐到
        require(site.contains("方格=getter-threw"), "squareKey 須以專屬哨兵回報，實得：" + site);
        require(site.contains("sprite=getter-threw"), "safeSpriteName 須以專屬哨兵回報，實得：" + site);
        require(!site.contains("should-never-be-read"), "getter 爆掉時不該讀到值：" + site);
        require(s[3] >= 2, "診斷取值失敗須被計數（至少 square＋sprite 各一），實得 " + s[3]);
    }

    /** 損壞的方格每次玩家經過都會再觸發：明細封頂但總數不能漏。 */
    private static void reportBudgetCapsWithoutLosingCount() throws Exception {
        ChunkLoadGuard.resetForTest();
        for (int i = 0; i < 100; i++) {
            Fake obj = fake();
            obj.square = square(100 + i, 200, 0);
            obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
            ChunkLoadGuard.addToWorld(obj);
        }
        long[] s = ChunkLoadGuard.statsForTest();
        require(s[0] == 100, "caught 必須計滿 100，實得 " + s[0]);
        // 釘死實際上限而非「介於 1 與 99」——後者容許 MAX_REPORTS 被誤改成 1 仍全綠
        require(s[1] == ChunkLoadGuard.maxReportsForTest(),
                "明細應封頂於 MAX_REPORTS=" + ChunkLoadGuard.maxReportsForTest() + "，實得 " + s[1]);
        require(s[2] == 0, "不得產生 anomaly，實得 " + s[2]);
    }

    /** 旋鈕關閉時必須完全等同 vanilla：例外照拋、零計數、null receiver 照樣 NPE。 */
    private static void killSwitchIsPassthrough() throws Exception {
        ChunkLoadGuard.resetForTest();
        Fake obj = fake();
        obj.toThrow = new IllegalArgumentException("Entity is already registered <test>");
        try {
            ChunkLoadGuard.addToWorld(obj);
            throw new AssertionError("kill switch 模式必須讓例外傳播（等同 vanilla）");
        } catch (IllegalArgumentException expected) {
            require(obj.calls == 1, "仍須轉發恰一次，實得 " + obj.calls);
            require(ChunkLoadGuard.statsForTest()[0] == 0, "kill switch 模式不得計數");
        }
        try {
            ChunkLoadGuard.addToWorld((IsoObject) null);
            throw new AssertionError("kill switch 模式的 null receiver 必須照 vanilla NPE");
        } catch (NullPointerException expected) {
            require(ChunkLoadGuard.statsForTest()[0] == 0, "kill switch 模式不得計數");
        }
        System.out.println("chunk-load OK  kill switch 模式：例外照拋、null 照 NPE、零計數");
    }

    /** 把 DebugType.General 的 log sink 換成 probe，跑完保證還原。 */
    private static void withProbe(Probe probe, ThrowingRunnable body) throws Exception {
        Field logStream = DebugType.class.getDeclaredField("logStream");
        logStream.setAccessible(true);
        Object original = logStream.get(DebugType.General);
        logStream.set(DebugType.General, probe);
        try {
            body.run();
        } finally {
            logStream.set(DebugType.General, original);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 觀測真正的 log 通道；可注入失敗以模擬「logger 自己爆掉」。 */
    private static final class Probe extends DebugLogStream {
        int attempts;
        Throwable failure;
        final List<String> lines = new ArrayList<>();

        Probe() {
            super(System.out, System.err, System.err, DebugType.General);
        }

        @Override
        public void println(String line) {
            attempts++;
            lines.add(line);
            // 必須能注入 Error，否則 helper 內層 catch 的 rethrowFatal 對測試而言是死碼
            // （審查抓到：舊版只有 instanceof RuntimeException）
            if (failure instanceof RuntimeException re) {
                throw re;
            }
            if (failure instanceof Error err) {
                throw err;
            }
        }
    }

    /** {@code toString()} 會爆的例外——讓 report() 內的字串串接在 try 內部就炸掉。 */
    private static final class NastyException extends RuntimeException {
        @Override
        public String toString() {
            throw new IllegalStateException("<test> toString exploded");
        }
    }

    /** 測試替身：覆寫改道實際會虛擬派送到的三個方法。 */
    public static class Fake extends IsoObject {
        RuntimeException toThrow;
        Error errorToThrow;
        IsoGridSquare square;
        IsoSprite sprite;
        boolean throwFromGetters;
        int calls;

        @Override
        public void addToWorld() {
            calls++;
            if (errorToThrow != null) {
                throw errorToThrow;
            }
            if (toThrow != null) {
                throw toThrow;
            }
        }

        @Override
        public IsoGridSquare getSquare() {
            if (throwFromGetters) {
                throw new NullPointerException("<test> half-initialised");
            }
            return square;
        }

        @Override
        public IsoSprite getSprite() {
            if (throwFromGetters) {
                throw new NullPointerException("<test> half-initialised");
            }
            return sprite;
        }
    }

    /** 屍體迴圈那一處的替身：必須是 {@code IsoMovingObject}，才能解析到對應多載。 */
    public static class FakeMoving extends IsoMovingObject {
        RuntimeException toThrow;
        int calls;

        @Override
        public void addToWorld() {
            calls++;
            if (toThrow != null) {
                throw toThrow;
            }
        }

        @Override
        public IsoGridSquare getSquare() {
            return null;
        }

        @Override
        public IsoSprite getSprite() {
            return null;
        }
    }

    private static Fake fake() throws Exception {
        return (Fake) rawInstance(Fake.class);
    }

    private static IsoGridSquare square(int x, int y, int z) throws Exception {
        IsoGridSquare sq = (IsoGridSquare) rawInstance(IsoGridSquare.class);
        sq.x = x;
        sq.y = y;
        sq.z = z;
        return sq;
    }

    private static IsoSprite sprite(String name) throws Exception {
        IsoSprite s = (IsoSprite) rawInstance(IsoSprite.class);
        s.name = name;
        return s;
    }

    /**
     * 以 serialization 建構子分配未初始化實例（繞過貼圖／世界依賴）。
     * 註：{@code sun.reflect.ReflectionFactory} 屬 jdk.unsupported，JDK 升級時這裡會先壞。
     */
    private static Object rawInstance(Class<?> type) throws Exception {
        Constructor<Object> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> alloc = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, objCtor);
        alloc.setAccessible(true);
        return alloc.newInstance();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private ChunkLoadGuardTest() {}
}
