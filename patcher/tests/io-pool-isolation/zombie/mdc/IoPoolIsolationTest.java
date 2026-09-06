package zombie.mdc;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import zombie.core.utils.ByteBlock;
import zombie.util.io.BitHeader;
import zombie.util.io.BitHeaderRead;
import zombie.util.io.BitHeaderWrite;

/**
 * W25 IoPoolIsolation 行為驗證（獨立 JVM；argv 無參數＝on、{@code off}＝kill switch，
 * 測試自驗旗標與 argv 相符——property 打錯會炸在測試裡，不會默默跑 on 版假綠）。
 *
 * <p>走的是 dist/java 內<b>手術後的真 BitHeader／ByteBlock</b>（classpath 順序 dist 先於 jar）：
 * 序列化結果逐位元不變、物件回收語意不變（同執行緒 LIFO 命中同一實例）；on 時全域池零
 * 寫入且跨執行緒零共用，off 時三處全部回到 vanilla 共用池；cap／分槽溢位／null 契約各一。
 */
public final class IoPoolIsolationTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "on";
        boolean on = !"off".equals(mode);
        expect("自驗：argv=" + mode + " 與旗標相符（enabled=" + IoPoolIsolation.enabledForTest() + "）",
                IoPoolIsolation.enabledForTest() == on);

        ConcurrentLinkedDeque<?> poolByte = globalPool(BitHeader.class, "pool_byte");
        ConcurrentLinkedDeque<?> poolInt = globalPool(BitHeader.class, "pool_int");
        ConcurrentLinkedDeque<?> poolBlock = globalPool(ByteBlock.class, "pool_data_block");
        poolByte.clear();
        poolInt.clear();
        poolBlock.clear();

        // ---- BitHeader 寫→讀 round trip（手術後真類別）＋回收語意 ----
        ByteBuffer buf = ByteBuffer.allocate(64);
        BitHeaderWrite w = BitHeader.allocWrite(BitHeader.HeaderSize.Byte, buf);
        buf.put((byte) 7);
        w.addFlags(1 | 4);
        w.write();
        w.release();
        BitHeaderWrite wi = BitHeader.allocWrite(BitHeader.HeaderSize.Integer, buf);
        buf.putShort((short) 9);
        wi.addFlags(1 << 20);
        wi.write();
        wi.release();
        int end = buf.position();
        buf.position(0);
        BitHeaderRead r = BitHeader.allocRead(BitHeader.HeaderSize.Byte, buf);
        byte payload = buf.get();
        BitHeaderRead ri = BitHeader.allocRead(BitHeader.HeaderSize.Integer, buf);
        short payload2 = buf.getShort();
        expect("BitHeader round trip：flags 與 payload 逐位元正確、位置對齊",
                r.hasFlags(1) && r.hasFlags(4) && !r.hasFlags(2) && payload == 7
                && ri.hasFlags(1 << 20) && payload2 == 9 && buf.position() == end);
        Object rObj = r;
        r.release();
        ri.release();
        expect(on ? "on：全域 pool_byte／pool_int 零寫入、本執行緒私有池各 1"
                  : "off：歸還進 vanilla 全域池（pool_byte=1、pool_int=1）、私有池未建",
                on ? poolByte.isEmpty() && poolInt.isEmpty()
                        && IoPoolIsolation.localPoolSizeForTest(poolByte) == 1
                        && IoPoolIsolation.localPoolSizeForTest(poolInt) == 1
                   : poolByte.size() == 1 && poolInt.size() == 1
                        && IoPoolIsolation.localPoolSizeForTest(poolByte) == -1);
        BitHeaderRead again = BitHeader.allocRead(BitHeader.HeaderSize.Byte, buf);
        expect("回收語意：下一次 alloc 拿回剛歸還的同一實例（on 私有 LIFO／off 全域池）", again == rObj);
        again.release();

        // ---- ByteBlock Save→Load round trip（手術後真類別；End 走 contains＋offer 兩處改道） ----
        ByteBuffer bb = ByteBuffer.allocate(64);
        ByteBlock blk = ByteBlock.Start(bb, ByteBlock.Mode.Save);
        bb.putInt(42);
        bb.putLong(99L);
        ByteBlock.End(bb, blk);
        int blkEnd = bb.position();
        bb.position(0);
        ByteBlock load = ByteBlock.Start(bb, ByteBlock.Mode.Load);
        int v1 = bb.getInt();
        long v2 = bb.getLong();
        int len = load.length();
        ByteBlock.End(bb, load);
        expect("ByteBlock round trip：長度 12、值正確、End 後位置對齊、同一實例回收",
                len == 12 && v1 == 42 && v2 == 99L && bb.position() == blkEnd && load == blk
                && (on ? poolBlock.isEmpty() && IoPoolIsolation.localPoolSizeForTest(poolBlock) == 1
                       : poolBlock.size() == 1));

        // ---- 4 執行緒並行：on 時實例零跨執行緒共用、全域池恆空；off 時只要不炸 ----
        List<Set<Object>> seen = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                try {
                    Set<Object> mine = Collections.newSetFromMap(new IdentityHashMap<>());
                    ByteBuffer local = ByteBuffer.allocate(256);
                    for (int i = 0; i < 20000; i++) {
                        local.clear();
                        BitHeaderWrite a = BitHeader.allocWrite(BitHeader.HeaderSize.Byte, local);
                        BitHeaderWrite b = BitHeader.allocWrite(BitHeader.HeaderSize.Integer, local);
                        BitHeaderWrite c = BitHeader.allocWrite(BitHeader.HeaderSize.Byte, local);   // 巢狀第 2 顆 byte
                        ByteBlock blk2 = ByteBlock.Start(local, ByteBlock.Mode.Save);
                        local.putInt(i);
                        ByteBlock.End(local, blk2);
                        mine.add(a);
                        mine.add(b);
                        mine.add(c);
                        mine.add(blk2);
                        c.write();
                        c.release();
                        b.write();
                        b.release();
                        a.write();
                        a.release();
                    }
                    seen.add(mine);
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        expect("4 執行緒 ×20000 巢狀配置零例外", errors.isEmpty() && seen.size() == 4);
        boolean disjoint = true;
        for (int i = 0; i < seen.size() && disjoint; i++) {
            for (int j = i + 1; j < seen.size() && disjoint; j++) {
                for (Object o : seen.get(i)) {
                    if (seen.get(j).contains(o)) {
                        disjoint = false;
                        break;
                    }
                }
            }
        }
        if (on) {
            expect("on：跨執行緒零實例共用（IdentityHashMap 交集為空）且全域池仍空",
                    disjoint && poolByte.isEmpty() && poolInt.isEmpty() && poolBlock.isEmpty());
            expect("on：每執行緒每池駐留＝巢狀深度（byte 2／int 1／block 1，不隨迭代成長）",
                    seen.stream().allMatch(s -> s.size() == 4));
        } else {
            expect("off：全域池非空（vanilla 共用池收到歸還）", !poolByte.isEmpty() && !poolInt.isEmpty() && !poolBlock.isEmpty());
        }

        // ---- helper 直呼契約：contains、cap、分槽溢位、null ----
        ConcurrentLinkedDeque<Object> q = new ConcurrentLinkedDeque<>();
        Object x = new Object();
        IoPoolIsolation.offer(q, x);
        expect(on ? "on：contains 查本執行緒池為真、全域池未動" : "off：contains 與全域池一致",
                on ? IoPoolIsolation.contains(q, x) && !q.contains(x) && !IoPoolIsolation.contains(q, new Object())
                   : IoPoolIsolation.contains(q, x) && q.contains(x));
        expect("poll 拿回同一實例，之後為 null", IoPoolIsolation.poll(q) == x && IoPoolIsolation.poll(q) == null);
        ConcurrentLinkedDeque<Object> capQ = new ConcurrentLinkedDeque<>();
        boolean allTrue = true;
        for (int i = 0; i < IoPoolIsolation.MAX_PER_POOL + 1; i++) {
            allTrue &= IoPoolIsolation.offer(capQ, new Object());
        }
        expect("offer 回傳恆 true（vanilla 語意）", allTrue);
        expect(on ? "on：私有池 cap=" + IoPoolIsolation.MAX_PER_POOL + "、超出 1 顆計 dropped"
                  : "off：全域池無界收滿 cap+1",
                on ? IoPoolIsolation.localPoolSizeForTest(capQ) == IoPoolIsolation.MAX_PER_POOL
                        && IoPoolIsolation.droppedForTest() == 1
                   : capQ.size() == IoPoolIsolation.MAX_PER_POOL + 1);
        // 再開相異池到 SLOTS 滿，下一個溢位→委派 vanilla（slotOverflow+1、物件進該全域池）
        long overflow0 = IoPoolIsolation.slotOverflowForTest();
        int used = IoPoolIsolation.usedSlotsForTest();
        expect(on ? "on：本執行緒已分槽 5 池（byte/int/block/q/capQ）" : "off：零分槽", used == (on ? 5 : 0));
        ConcurrentLinkedDeque<Object> extra = null;
        for (int i = used; i <= IoPoolIsolation.SLOTS; i++) {
            extra = new ConcurrentLinkedDeque<>();
            IoPoolIsolation.offer(extra, x);
        }
        expect(on ? "on：第 " + (IoPoolIsolation.SLOTS + 1) + " 個相異池溢位→委派 vanilla（slotOverflow+1、物件進全域池）"
                  : "off：slotOverflow 不計數、全域池收到",
                on ? IoPoolIsolation.slotOverflowForTest() == overflow0 + 1 && extra.contains(x)
                        && IoPoolIsolation.usedSlotsForTest() == IoPoolIsolation.SLOTS
                   : IoPoolIsolation.slotOverflowForTest() == 0 && extra.contains(x));
        boolean npe = false;
        try {
            IoPoolIsolation.offer(q, null);
        } catch (NullPointerException e) {
            npe = true;
        }
        expect("offer(null) 拋 NPE（與 CLD.offer(null) 同款）", npe);

        if (failed > 0) {
            System.out.println("io-pool-isolation FAIL " + failed + " 項");
            System.exit(1);
        }
        System.out.println("io-pool-isolation OK  mode=" + mode + "：round trip／回收／並行隔離／cap／溢位／null 全數通過");
    }

    private static ConcurrentLinkedDeque<?> globalPool(Class<?> owner, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return (ConcurrentLinkedDeque<?>) f.get(null);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "iop pass  " : "iop FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    private IoPoolIsolationTest() {}
}
