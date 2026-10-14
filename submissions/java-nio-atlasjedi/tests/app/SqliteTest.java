package app;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Task 2: FFM bindings against a copy of the seed database. */
public class SqliteTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Throwable {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile("feed-test", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        try (Sqlite db = Sqlite.open(tmp.toString())) {
            Sqlite.Stmt c = db.prepare("SELECT count(*) FROM users");
            check(c.step(), "count: no row");
            check(c.colLong(0) == 50000, "users count " + c.colLong(0));
            check(!c.step(), "count: expected DONE");
            c.finalizeStmt();

            Sqlite.Stmt s = db.prepare("SELECT body FROM posts WHERE id=?");
            s.bindLong(1, 1);
            check(s.step(), "post 1: no row");
            int n = s.colBytes(0);
            check(n > 0, "colBytes " + n);
            String golden = "Fast tomorrow small bug big droplet. Weird cache really project rarely everything?";
            String body = s.colText(0).getString(0);
            check(golden.equals(body), "body: " + body);
            ByteBuffer bb = ByteBuffer.allocateDirect(256);
            s.copyText(0, bb, java.lang.foreign.MemorySegment.ofBuffer(bb));
            check(bb.position() == n, "copyText position " + bb.position());
            check(new String(bytesOf(bb), StandardCharsets.UTF_8).equals(golden), "copyText content");
            s.reset();
            s.bindLong(1, 999999999L);
            check(!s.step(), "missing post should be DONE");
            s.finalizeStmt();

            db.exec("PRAGMA journal_mode=WAL");

            try {
                db.exec("SELEC nonsense");
                check(false, "invalid SQL did not throw");
            } catch (RuntimeException e) {
                check(e.getMessage() != null && e.getMessage().contains("syntax error"), "msg: " + e.getMessage());
            }

            String txt = "zażółć 🙂 gęślą";
            byte[] u = ("xx" + txt + "yy").getBytes(StandardCharsets.UTF_8);
            int off = 2, len = u.length - 4;
            Sqlite.Stmt t = db.prepare("SELECT ?");
            t.bindText(1, u, off, len);
            check(t.step(), "select ? no row");
            check(t.colBytes(0) == len, "round-trip bytes " + t.colBytes(0));
            check(txt.equals(t.colText(0).getString(0)), "round-trip text");
            t.finalizeStmt();

            Sqlite.Stmt ins = db.prepare("INSERT INTO likes(user_id, post_id) VALUES(?, ?)");
            ins.bindLong(1, 7562); ins.bindLong(2, 1); // existing PK pair
            boolean threw = false;
            try { ins.step(); } catch (RuntimeException e) { threw = true; }
            check(threw, "duplicate like should throw");
            ins.reset(); // must not throw
            ins.bindLong(1, 7562); ins.bindLong(2, 2);
            Sqlite.Stmt chk = db.prepare("SELECT count(*) FROM likes WHERE user_id=7562 AND post_id=2");
            chk.step(); long before = chk.colLong(0); chk.reset();
            check(!ins.step(), "insert after reset should be DONE");
            ins.reset();
            chk.step(); check(chk.colLong(0) == before + 1 || before == 1, "row inserted"); chk.finalizeStmt();
            ins.finalizeStmt();

            db.exec("CREATE TEMP TABLE t(id INTEGER PRIMARY KEY, v TEXT)");
            db.exec("INSERT INTO t(v) VALUES ('a')");
            check(db.changes() == 1, "changes " + db.changes());
            check(db.lastInsertRowid() == 1, "rowid " + db.lastInsertRowid());
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + "-wal"));
            Files.deleteIfExists(Path.of(tmp + "-shm"));
        }
        System.out.println("SqliteTest ok");
    }

    static byte[] bytesOf(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        d.flip();
        byte[] r = new byte[d.remaining()];
        d.get(r);
        return r;
    }
}
