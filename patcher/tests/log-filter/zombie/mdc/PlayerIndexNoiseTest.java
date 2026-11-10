package zombie.mdc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipFile;

import zombie.core.network.ByteBufferReader;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.fields.character.ConnectionPlayer;

/**
 * 抑噪 #11 行為驗證：伺服器端真的 {@code ConnectionPlayer.parse}，手術後那份（classpath 上 dist 在 jar 前面）對照 jar 內原版
 * （隔離 loader）。索引 12 的封包原版印 {@code wrong player connection index (12)}，手術後不印、計數加一，兩邊
 * {@code isConsistent} 都是 false；索引在 0–3 內的不計；其他 format 的 error 照常轉發。argv：jar。
 */
public final class PlayerIndexNoiseTest {
    private static final String CP = "zombie/network/fields/character/ConnectionPlayer";
    private static final String LINE = "wrong player connection index (12)";
    /** DebugLog 初始化後會把 System.out 換成自己的 log stream：結果行寫到原本的 stdout。 */
    private static final PrintStream OUT = System.out;
    private static final ByteArrayOutputStream LOG = new ByteArrayOutputStream();

    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();   // DebugLog 第一次寫行時經 GameClient → ServerOptions 呼叫 Rand
        ByteArrayOutputStream log = LOG;
        DebugType.Multiplayer.setLogSeverity(zombie.debug.LogSeverity.All);   // 先觸發 DebugLog 初始化：init 會把輸出串流設回 System.out／err
        DebugLog.getInstance().setStdOut(log);
        DebugLog.getInstance().setStdErr(log);   // error 等級走 stdErr
        GameServer.server = true;

        long before = LogFilter.wrongPlayerIndexCount();
        ConnectionPlayer patched = new ConnectionPlayer();
        patched.parse(reader(12), null);
        patched.parse(reader(12), null);
        require(LogFilter.wrongPlayerIndexCount() == before + 2, "手術後：每個索引不合的封包計數加一");
        require(count(log, LINE) == 0, "手術後：不印原文");
        require(!patched.isConsistent(null) && patched.getPlayer() == null, "手術後：封包照原版判定不一致");

        patched.parse(reader(1), null);
        require(LogFilter.wrongPlayerIndexCount() == before + 2, "索引在 0–3 內的不計");

        byte[] vanillaBytes;
        try (ZipFile zf = new ZipFile(args[0])) {
            vanillaBytes = zf.getInputStream(zf.getEntry(CP + ".class")).readAllBytes();
        }
        Class<?> vanillaCls = ZombieReuseOwnerTest.isolated(Map.of(CP, vanillaBytes)).loadClass(CP.replace('/', '.'));
        Object vanilla = vanillaCls.getConstructor().newInstance();
        vanillaCls.getMethod("parse", ByteBufferReader.class, IConnection.class).invoke(vanilla, reader(12), null);
        require(vanillaCls != ConnectionPlayer.class && count(log, LINE) == 1, "原版負對照：同一包印一行原文");
        require(!(boolean) vanillaCls.getMethod("isConsistent", IConnection.class).invoke(vanilla, (Object) null)
                && LogFilter.wrongPlayerIndexCount() == before + 2, "原版負對照：一樣不一致，不經 LogFilter");

        LogFilter.errorFmt(DebugType.Multiplayer, LogFilter.WRONG_PLAYER_INDEX + " other", new Object[]{(short) 12});
        require(count(log, LINE + " other") == 1 && LogFilter.wrongPlayerIndexCount() == before + 2,
                "其他 format 的 error 照常轉發、不計");
        OUT.println("player-index-noise OK  手術後不印改計數／原版負對照印原文／0–3 不計／其他 format 轉發");
    }

    private static ByteBufferReader reader(int index) {
        ByteBuffer bb = ByteBuffer.allocate(2);
        bb.putShort((short) index).flip();
        return new ByteBufferReader(bb);
    }

    private static long count(ByteArrayOutputStream log, String text) {
        return log.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains(text)).count();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            OUT.println("player-index-noise FAIL  " + what + "\n---- 擷取到的 log ----\n" + LOG.toString(StandardCharsets.UTF_8));
            System.exit(1);
        }
    }

    private PlayerIndexNoiseTest() {}
}
