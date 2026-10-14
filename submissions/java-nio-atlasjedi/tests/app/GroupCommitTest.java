package app;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Task 15: one COMMIT per tick; a 201 is never released before it; a failed commit closes deferred sockets silently. */
public class GroupCommitTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static byte[] create(String tok, int i) {
        String body = "{\"body\":\"group commit " + i + "\"}";
        return ("POST /posts HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + tok + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    static long count(Db db) {
        Sqlite.Stmt s = db.sqlite.prepare("SELECT count(*) FROM posts");
        try {
            check(s.step(), "count row");
            return s.colLong(0);
        } finally {
            s.reset();
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = RobustTest.seedCopy("feed-gc");
        Db db = Db.open(tmp.toString());
        Server server = new Server("127.0.0.1", 0, 4096, new Router(new Handlers(db), new Jwt(JwtTest.SECRET)), 1000);
        int port = server.port();
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();
        String tok = RobustTest.token();

        // 1. nothing reaches the socket while the loop thread is parked right before COMMIT; the 201 follows it
        CountDownLatch inHook = new CountDownLatch(1);
        int[] availableAtCommit = {-1};
        boolean[] committed = {false};
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            db.commitHook = () -> {
                availableAtCommit[0] = -2;
                inHook.countDown();
                try { Thread.sleep(200); } catch (InterruptedException e) { throw new RuntimeException(e); }
                try { availableAtCommit[0] = s.getInputStream().available(); } catch (IOException e) { throw new RuntimeException(e); }
                committed[0] = true;
            };
            s.getOutputStream().write(create(tok, 0));
            check(inHook.await(3, TimeUnit.SECONDS), "commit hook never ran (no tick commit)");
            String r = ServerTest.readResponse(s.getInputStream());
            check(r.startsWith("201|"), "expected 201, got " + r);
            check(committed[0], "201 released before the commit hook finished");
            check(availableAtCommit[0] == 0, "bytes were on the socket before COMMIT: " + availableAtCommit[0]);
            db.commitHook = null;
        }

        // 2. two sockets, 50 pipelined creates each, all 201, rows present after the tick
        long before = count(db);
        Socket a = new Socket("127.0.0.1", port), b = new Socket("127.0.0.1", port);
        a.setSoTimeout(5000);
        b.setSoTimeout(5000);
        ByteArrayOutputStream pa = new ByteArrayOutputStream(), pb = new ByteArrayOutputStream();
        for (int i = 0; i < 50; i++) { pa.write(create(tok, 100 + i)); pb.write(create(tok, 200 + i)); }
        a.getOutputStream().write(pa.toByteArray());
        b.getOutputStream().write(pb.toByteArray());
        for (int i = 0; i < 50; i++) {
            check(ServerTest.readResponse(a.getInputStream()).startsWith("201|"), "a #" + i);
            check(ServerTest.readResponse(b.getInputStream()).startsWith("201|"), "b #" + i);
        }
        check(count(db) == before + 100, "rows grew by " + (count(db) - before));
        a.close();
        b.close();

        // 3. commit failure: deferred socket closed without bytes, ROLLBACK done, loop alive
        long rows = count(db);
        db.commitHook = () -> { throw new IllegalStateException("injected commit failure"); };
        Socket f = new Socket("127.0.0.1", port);
        f.setSoTimeout(3000);
        f.getOutputStream().write(create(tok, 999));
        int got;
        try {
            got = f.getInputStream().read();
        } catch (IOException reset) {
            got = -1; // RST
        }
        check(got == -1, "deferred socket received a byte after a failed commit: " + got);
        f.close();
        db.commitHook = null;
        check(count(db) == rows, "rolled-back row is visible: " + (count(db) - rows));
        try (Socket n = new Socket("127.0.0.1", port)) {
            n.setSoTimeout(3000);
            n.getOutputStream().write(create(tok, 1000));
            check(ServerTest.readResponse(n.getInputStream()).startsWith("201|"), "loop dead or tx stuck after failure");
        }
        System.out.println("GroupCommitTest OK");
        System.exit(0);
    }
}
