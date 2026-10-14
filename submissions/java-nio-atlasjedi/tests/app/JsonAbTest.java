package app;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** Task 17: JSON output path. Golden bytes through Db, escaping through str(addr,len), warm feed path allocation-free. */
public class JsonAbTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static byte[] drain(ByteBuffer b) {
        b.flip();
        byte[] a = new byte[b.remaining()];
        b.get(a);
        return a;
    }

    static byte[] golden(Path f) throws Exception {
        byte[] want = Files.readAllBytes(f);
        int n = want.length;
        while (n > 0 && want[n - 1] == '\n') n--;
        return Arrays.copyOf(want, n);
    }

    static String esc(String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(u.length + 1);
            MemorySegment.copy(u, 0, seg, ValueLayout.JAVA_BYTE, 0, u.length);
            ByteBuffer b = Resp.body();
            Resp.str(b, seg.address(), u.length);
            return new String(drain(b), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path gold = Path.of("../../test/golden").toRealPath();
        Path tmp = Files.createTempFile("feed-jsonab", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        Db db = Db.open(tmp.toString());

        ByteBuffer body = Resp.body();
        db.feed(body);
        check(Arrays.equals(golden(gold.resolve("feed.json")), drain(body)), "feed differs from golden");
        for (String id : new String[] {"1", "250000", "500000"}) {
            body = Resp.body();
            check(db.post(Long.parseLong(id), body), "post " + id + " missing");
            check(Arrays.equals(golden(gold.resolve("post-" + id + ".json")), drain(body)), "post " + id + " differs from golden");
        }
        body = Resp.body();
        check(!db.post(999999999L, body), "missing post must be false");

        // escaping through the native path
        check(esc("").equals("\"\""), "empty");
        check(esc("plain").equals("\"plain\""), "plain");
        check(esc("a\"b\\c").equals("\"a\\\"b\\\\c\""), "quote/backslash");
        check(esc("n\nr\rt\tb\bf\f").equals("\"n\\nr\\rt\\tb\\bf\\f\""), "short escapes");
        check(esc("\u0001x\u001f").equals("\"\\u0001x\\u001f\""), "hex escapes");
        check(esc("a/b").equals("\"a/b\""), "slash verbatim");
        check(esc("zażółć 🙂 \"gęślą\"\n").equals("\"zażółć 🙂 \\\"gęślą\\\"\\n\""), "utf-8 multi-byte");
        check(esc("\"").equals("\"\\\"\""), "lone quote");

        // warm feed path allocates (almost) nothing
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        for (int i = 0; i < 2000; i++) {
            body = Resp.body();
            db.feed(body);
        }
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 20000; i++) {
            body = Resp.body();
            db.feed(body);
        }
        long delta = mx.getThreadAllocatedBytes(tid) - before;
        System.out.println("feed x20000 allocated " + delta + " bytes");
        check(delta < 64 * 1024, "warm feed allocates " + delta + " bytes over 20000 calls");
        System.out.println("JsonAbTest OK");
    }
}
