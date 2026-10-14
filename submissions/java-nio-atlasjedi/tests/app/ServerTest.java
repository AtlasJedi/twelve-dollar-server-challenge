package app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Tasks 3+4: event loop with HTTP framing. 1,000 clients, split request, pipelining, close, errors, backpressure. */
public class ServerTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void awaitOpen(int want, long ms) throws Exception {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (Server.openConnections() != want && System.nanoTime() < end) Thread.sleep(5);
        check(Server.openConnections() == want, "openConnections " + Server.openConnections() + " != " + want);
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** HTTP echo: 200 with the path as body. */
    static final Server.Handler ECHO = (c, r, out) -> {
        out.put(ascii("HTTP/1.1 200 OK\r\nContent-Length: " + r.pathLen + "\r\n\r\n"));
        out.put(r.buf, r.pathOff, r.pathLen);
    };

    static void send(Socket s, String data) throws IOException {
        s.getOutputStream().write(ascii(data));
        s.getOutputStream().flush();
    }

    /** Reads one response; returns "status|body". */
    static String readResponse(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new EOFException("eof in headers: " + head);
            head.append((char) b);
        }
        String h = head.toString();
        String status = h.substring(9, 12);
        int cl = 0;
        for (String line : h.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) cl = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = in.readNBytes(cl);
        check(body.length == cl, "short body");
        return status + "|" + new String(body, StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        Server server = new Server("127.0.0.1", 0, 4096, ECHO, 100_000);
        int port = server.port();
        check(port > 0, "port " + port);
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();

        // 1,000 blocking clients, each GETs /hello-N and gets 200 with the path
        int n = 1000;
        List<Socket> socks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Socket s = new Socket("127.0.0.1", port);
            s.setSoTimeout(5000);
            socks.add(s);
        }
        for (int i = 0; i < n; i++) send(socks.get(i), "GET /hello-" + i + " HTTP/1.1\r\nHost: x\r\n\r\n");
        for (int i = 0; i < n; i++) {
            String got = readResponse(socks.get(i).getInputStream());
            check(("200|/hello-" + i).equals(got), "client " + i + " got " + got);
        }
        check(Server.openConnections() == n, "open " + Server.openConnections());

        // review focus 1: a POST split across reads (headers, then body) is answered once complete
        Socket sp = socks.get(0);
        send(sp, "POST /split HTTP/1.1\r\nContent-Length: 5\r\n\r\n");
        Thread.sleep(50);
        send(sp, "he");
        Thread.sleep(50);
        send(sp, "llo");
        check("200|/split".equals(readResponse(sp.getInputStream())), "split post");
        // and split mid-header
        send(sp, "GET /mid HT");
        Thread.sleep(50);
        send(sp, "TP/1.1\r\n\r\n");
        check("200|/mid".equals(readResponse(sp.getInputStream())), "split header");

        // review focus 2: two pipelined requests in one write are answered in order
        Socket pp = socks.get(1);
        send(pp, "GET /one HTTP/1.1\r\n\r\nGET /two HTTP/1.1\r\n\r\nGET /thr");
        InputStream pin = pp.getInputStream();
        check("200|/one".equals(readResponse(pin)), "pipe 1");
        check("200|/two".equals(readResponse(pin)), "pipe 2");
        send(pp, "ee HTTP/1.1\r\n\r\n"); // third request completes from the stored tail
        check("200|/three".equals(readResponse(pin)), "pipe 3 from tail");

        for (Socket s : socks) s.close();
        awaitOpen(0, 1000);

        // Connection: close -> response, then EOF
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            send(s, "GET /bye HTTP/1.1\r\nConnection: close\r\n\r\n");
            check("200|/bye".equals(readResponse(s.getInputStream())), "close response");
            check(s.getInputStream().read() == -1, "closed after response");
        }
        // garbage -> 400 then EOF
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            send(s, "garbage\r\n\r\n");
            check("400|".equals(readResponse(s.getInputStream())), "400");
            check(s.getInputStream().read() == -1, "closed after 400");
        }
        // headers > 8 KB -> 413 then EOF
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            send(s, "GET /x HTTP/1.1\r\nX-Pad: " + "a".repeat(9000) + "\r\n\r\n");
            check("413|".equals(readResponse(s.getInputStream())), "413 headers");
            check(s.getInputStream().read() == -1, "closed after 413 headers");
        }
        // body > 64 KB -> 413 then EOF
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            send(s, "POST /x HTTP/1.1\r\nContent-Length: 65537\r\n\r\n");
            check("413|".equals(readResponse(s.getInputStream())), "413 body");
            check(s.getInputStream().read() == -1, "closed after 413 body");
        }
        awaitOpen(0, 1000);

        // backpressure: ~1.4 MB of pipelined responses while the client does not read for 500 ms
        int reqs = 4096;
        String path = "/" + "x".repeat(254);
        String one = "GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n";
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            Thread writer = new Thread(() -> {
                try {
                    OutputStream os = s.getOutputStream();
                    byte[] b = ascii(one);
                    for (int i = 0; i < reqs; i++) os.write(b);
                    os.flush();
                } catch (IOException ignored) { }
            });
            writer.start();
            Thread.sleep(500);
            check(loop.isAlive(), "loop died");
            InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
            for (int i = 0; i < reqs; i++) {
                String got = readResponse(in);
                if (!("200|" + path).equals(got)) throw new AssertionError("response " + i + " wrong: " + got);
            }
            writer.join();
            // after drain the connection still serves
            send(s, "GET /again HTTP/1.1\r\n\r\n");
            check("200|/again".equals(readResponse(in)), "read restored");
        }
        check(loop.isAlive(), "loop died");
        awaitOpen(0, 1000);
        System.out.println("ServerTest ok");
    }
}
