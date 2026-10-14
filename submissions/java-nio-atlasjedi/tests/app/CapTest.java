package app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Task 12: connection cap with LRU eviction (RST of the least recently active connection). */
public class CapTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void health(Socket s) throws IOException {
        s.getOutputStream().write("GET /health HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
    }

    public static void main(String[] args) throws Exception {
        check(Os.nofileSoftLimit() > 0, "nofileSoftLimit " + Os.nofileSoftLimit());
        check(Server.defaultMaxConns() >= 1024, "defaultMaxConns " + Server.defaultMaxConns());
        System.out.println("nofile=" + Os.nofileSoftLimit() + " defaultMaxConns=" + Server.defaultMaxConns());

        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile("feed-cap", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        Db db = Db.open(tmp.toString());
        Server server = new Server("127.0.0.1", 0, 4096, new Router(new Handlers(db), new Jwt(JwtTest.SECRET)), 20);
        int port = server.port();
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();

        Socket[] s = new Socket[26]; // 1-based
        for (int i = 1; i <= 20; i++) {
            s[i] = new Socket("127.0.0.1", port);
            s[i].setSoTimeout(3000);
            ServerTest.awaitOpen(i, 500); // fixes the accept order
        }
        for (int i = 16; i <= 20; i++) {
            health(s[i]);
            check(ServerTest.readResponse(s[i].getInputStream()).startsWith("200|"), "warm s" + i);
        }
        for (int i = 21; i <= 25; i++) {
            s[i] = new Socket("127.0.0.1", port);
            s[i].setSoTimeout(3000);
            Thread.sleep(20);
        }
        ServerTest.awaitOpen(20, 500);

        for (int i = 1; i <= 5; i++) {
            try {
                int r = s[i].getInputStream().read();
                check(r == -1, "s" + i + " should be reset, read " + r);
            } catch (IOException expected) {
                // connection reset
            }
        }
        for (int i = 6; i <= 15; i++) { // untouched between the two groups: the cap evicts exactly five
            health(s[i]);
            check(ServerTest.readResponse(s[i].getInputStream()).startsWith("200|"), "s" + i + " survives");
        }
        for (int i = 16; i <= 25; i++) {
            if (i >= 21) {
                health(s[i]);
                check(ServerTest.readResponse(s[i].getInputStream()).startsWith("200|"), "s" + i + " served");
            } else {
                health(s[i]);
                check(ServerTest.readResponse(s[i].getInputStream()).startsWith("200|"), "s" + i + " still served");
            }
        }
        ServerTest.awaitOpen(20, 500);
        System.out.println("CapTest OK");
    }
}
