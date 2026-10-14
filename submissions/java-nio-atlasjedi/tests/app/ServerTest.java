package app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Task 3: event loop. 1,000 echo clients, short-write backpressure, no leaked connections. */
public class ServerTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void awaitOpen(int want, long ms) throws Exception {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (Server.openConnections() != want && System.nanoTime() < end) Thread.sleep(5);
        check(Server.openConnections() == want, "openConnections " + Server.openConnections() + " != " + want);
    }

    public static void main(String[] args) throws Exception {
        Server.echoMode = true;
        Server server = new Server("127.0.0.1", 0, 4096);
        int port = server.port();
        check(port > 0, "port " + port);
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();

        // 1,000 blocking clients, each echoes "hello N\n"
        int n = 1000;
        List<Socket> socks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Socket s = new Socket("127.0.0.1", port);
            s.setSoTimeout(5000);
            socks.add(s);
        }
        for (int i = 0; i < n; i++) {
            socks.get(i).getOutputStream().write(("hello " + i + "\n").getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < n; i++) {
            BufferedReader r = new BufferedReader(new InputStreamReader(socks.get(i).getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            check(("hello " + i).equals(line), "client " + i + " got " + line);
        }
        check(Server.openConnections() == n, "open " + Server.openConnections());

        // a line split across two writes is framed once complete
        Socket sp = socks.get(0);
        sp.getOutputStream().write("spl".getBytes(StandardCharsets.UTF_8));
        sp.getOutputStream().flush();
        Thread.sleep(50);
        sp.getOutputStream().write("it\n".getBytes(StandardCharsets.UTF_8));
        check("split".equals(new BufferedReader(new InputStreamReader(sp.getInputStream())).readLine()), "split line");

        for (Socket s : socks) s.close();
        awaitOpen(0, 1000);

        // short write: ~1 MB echo pending while the client does not read for 500 ms
        Server.echoRepeat = 4096;
        String line = "x".repeat(255) + "\n";
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(line.getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(500);
            check(loop.isAlive(), "loop died");
            byte[] buf = new byte[256 * 4096];
            DataInputStream in = new DataInputStream(s.getInputStream());
            in.readFully(buf);
            byte[] one = line.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 4096; i++) {
                for (int j = 0; j < 256; j++) {
                    if (buf[i * 256 + j] != one[j]) throw new AssertionError("mismatch at copy " + i + " byte " + j);
                }
            }
            // after drain the connection reads again
            Server.echoRepeat = 1;
            s.getOutputStream().write("again\n".getBytes(StandardCharsets.US_ASCII));
            check("again".equals(new BufferedReader(new InputStreamReader(in)).readLine()), "read restored");
        }
        check(loop.isAlive(), "loop died");
        awaitOpen(0, 1000);
        System.out.println("ServerTest ok");
    }
}
