package app;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Task 13: slow reader, abandoned uploads, connect/close churn, kill -9 durability. */
public class RobustTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static final byte[] HEALTH = "GET /health HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    static Path seedCopy(String prefix) throws IOException {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile(prefix, ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }

    static String token() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        return JwtTest.sign(JwtTest.HDR, JwtTest.payload("\"1\"", "\"golden_ember_1\"", now + 3600));
    }

    static void awaitOpen(int want, long ms) throws Exception {
        ServerTest.awaitOpen(want, ms);
    }

    static void healthOk(Socket s) throws IOException {
        s.getOutputStream().write(HEALTH);
        check(ServerTest.readResponse(s.getInputStream()).startsWith("200|"), "health 200");
    }

    static int fds() {
        return (int) ((com.sun.management.UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getOpenFileDescriptorCount();
    }

    static void slowReader(int port) throws Exception {
        int base = Server.openConnections();
        Socket slow = new Socket("127.0.0.1", port);
        awaitOpen(base + 1, 1000);
        byte[] one = "GET /feed HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[one.length * 2000];
        for (int i = 0; i < 2000; i++) System.arraycopy(one, 0, all, i * one.length, one.length);
        slow.getOutputStream().write(all);
        slow.getOutputStream().flush();
        Thread.sleep(100);
        try (Socket f = new Socket("127.0.0.1", port)) {
            f.setSoTimeout(2000);
            f.setTcpNoDelay(true);
            for (int i = 0; i < 10; i++) {
                long t0 = System.nanoTime();
                healthOk(f);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                check(ms < 100, "health " + i + " took " + ms + " ms");
            }
        }
        slow.close();
        awaitOpen(base, 1000);
        System.out.println("RobustTest slow-reader OK");
    }

    static void abandonedUploads(int port) throws Exception {
        int base = Server.openConnections();
        byte[] head = ("POST /posts HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + token()
                + "\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] part = new byte[50];
        Arrays.fill(part, (byte) 'a');
        List<Socket> socks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Socket s = new Socket("127.0.0.1", port);
            s.getOutputStream().write(head);
            s.getOutputStream().write(part);
            socks.add(s);
        }
        awaitOpen(base + 100, 2000);
        Thread.sleep(2000);
        try (Socket f = new Socket("127.0.0.1", port)) {
            f.setSoTimeout(2000);
            healthOk(f);
        }
        for (Socket s : socks) s.close();
        awaitOpen(base, 1000);
        System.out.println("RobustTest abandoned-uploads OK");
    }

    static void connectClose(int port) throws Exception {
        Thread.sleep(500);
        int base = Server.openConnections();
        int fdBefore = fds();
        for (int i = 0; i < 10_000; i++) {
            try (Socket s = new Socket()) {
                s.setSoLinger(true, 0); // RST on close: no TIME_WAIT, spares the ~16k macOS ephemeral ports
                s.setSoTimeout(3000);
                s.connect(new InetSocketAddress("127.0.0.1", port));
                healthOk(s);
            }
        }
        awaitOpen(base, 2000);
        Thread.sleep(500);
        int fdAfter = fds();
        System.out.println("fds before=" + fdBefore + " after=" + fdAfter);
        check(Server.openConnections() == base, "open " + Server.openConnections() + " != " + base);
        check(fdAfter <= fdBefore + 16, "fd leak: " + fdBefore + " -> " + fdAfter);
        System.out.println("RobustTest connect-close OK");
    }

    static long countPosts(Path db) {
        Db d = Db.open(db.toString());
        // Db keeps its statements prepared, so sqlite3_close would return BUSY; the handle is left open (test process).
        Sqlite.Stmt st = d.sqlite.prepare("SELECT count(*) FROM posts");
        check(st.step(), "count row");
        long n = st.colLong(0);
        st.finalizeStmt();
        return n;
    }

    static void durability() throws Exception {
        Path copy = seedCopy("feed-durable");
        // Db.open leaves its handle (and a lock) behind, so the baseline is read from a second, identical seed copy.
        long before = countPosts(seedCopy("feed-baseline"));
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ProcessBuilder pb = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                "-Djava.library.path=bin", "--enable-native-access=ALL-UNNAMED", "-cp", "bin/test-classes", "app.Main");
        pb.environment().put("SQLITE_PATH", copy.toString());
        pb.environment().put("JWT_SECRET", "twelve-dollar-challenge");
        pb.environment().put("HOST", "127.0.0.1");
        pb.environment().put("PORT", String.valueOf(port));
        pb.inheritIO();
        Process child = pb.start();
        int created = 0;
        try {
            long end = System.nanoTime() + 10_000_000_000L;
            boolean up = false;
            while (!up && System.nanoTime() < end) {
                try (Socket s = new Socket("127.0.0.1", port)) {
                    s.setSoTimeout(1000);
                    s.getOutputStream().write(HEALTH);
                    up = ServerTest.readResponse(s.getInputStream()).startsWith("200|");
                } catch (IOException e) {
                    Thread.sleep(50);
                }
            }
            check(up, "child never became healthy");
            String tok = token();
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                for (int i = 1; i <= 200; i++) {
                    String body = "{\"body\":\"durable " + i + "\"}";
                    String req = "POST /posts HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + tok
                            + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                    s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
                    String r = ServerTest.readResponse(s.getInputStream());
                    if (r.startsWith("201|")) created++;
                }
            }
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
        long after = countPosts(copy);
        System.out.println("posts before=" + before + " created(201)=" + created + " after=" + after);
        check(created == 200, "201s " + created);
        check(after == before + created, "after " + after + " != " + before + " + " + created);
        System.out.println("RobustTest durability OK");
    }

    public static void main(String[] args) throws Exception {
        Path tmp = seedCopy("feed-robust");
        Server server = Main.start(tmp.toString(), JwtTest.SECRET, "127.0.0.1", 0);
        int port = server.port();
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();
        slowReader(port);
        abandonedUploads(port);
        connectClose(port);
        durability();
    }
}
