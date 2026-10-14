package app;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** Task 8: GET /feed and /posts/:id are byte-identical to the golden files; unknown id is 404. */
public class ReadTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    /** Raw GET on a fresh connection: returns {status, headers, body bytes}. */
    static Object[] get(int port, String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int b = in.read();
                check(b >= 0, "eof in headers");
                head.append((char) b);
            }
            String h = head.toString();
            int cl = 0;
            for (String line : h.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) cl = Integer.parseInt(line.substring(15).trim());
            }
            return new Object[] {h.substring(9, 12), h, in.readNBytes(cl)};
        }
    }

    static void golden(int port, String path, Path file) throws Exception {
        byte[] want = Files.readAllBytes(file); // test.sh compares $(cat file): only trailing newlines are stripped
        int n = want.length;
        while (n > 0 && want[n - 1] == '\n') n--;
        want = Arrays.copyOf(want, n);
        Object[] r = get(port, path);
        check("200".equals(r[0]), path + " status " + r[0]);
        check(((String) r[1]).toLowerCase().contains("content-type: application/json"), path + " content-type: " + r[1]);
        byte[] got = (byte[]) r[2];
        check(Arrays.equals(want, got), path + " differs from " + file + "\nwant: " + new String(want, StandardCharsets.UTF_8)
            + "\ngot:  " + new String(got, StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path golden = Path.of("../../test/golden").toRealPath();
        Path tmp = Files.createTempFile("feed-read", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        Server server = Main.start(tmp.toString(), JwtTest.SECRET, "127.0.0.1", 0);
        int port = server.port();
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();

        golden(port, "/feed", golden.resolve("feed.json"));
        for (String id : new String[] {"1", "250000", "500000"}) {
            golden(port, "/posts/" + id, golden.resolve("post-" + id + ".json"));
        }
        Object[] r = get(port, "/posts/999999999");
        check("404".equals(r[0]), "missing status " + r[0]);
        check("{\"error\":\"post not found\"}".equals(new String((byte[]) r[2], StandardCharsets.UTF_8)), "missing body");
        check(((String) r[1]).toLowerCase().contains("content-type: application/json"), "404 content-type");

        // repeated reads reuse the prepared statements
        golden(port, "/feed", golden.resolve("feed.json"));
        golden(port, "/posts/1", golden.resolve("post-1.json"));
        System.out.println("ReadTest OK");
    }
}
