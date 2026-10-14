package app;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Task 7: routing, auth-before-id, error mapping, health, through the real Main wiring. */
public class RouterTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static int port;

    /** One request on a fresh connection; returns "status|body". */
    static String req(String method, String path, String auth, String body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            StringBuilder sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n");
            if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n");
            if (body != null) sb.append("Content-Type: application/json\r\nContent-Length: ").append(body.length()).append("\r\n");
            sb.append("\r\n");
            if (body != null) sb.append(body);
            s.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
            return ServerTest.readResponse(s.getInputStream());
        }
    }

    static void eq(String name, String exp, String act) {
        check(exp.equals(act), name + ": expected <" + exp + "> got <" + act + ">");
    }

    public static void main(String[] args) throws Exception {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile("feed-router", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        Server server = Main.start(tmp.toString(), JwtTest.SECRET, "127.0.0.1", 0);
        port = server.port();
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();

        eq("404 path", "404|{\"error\":\"not found\"}", req("GET", "/nope", null, null));
        eq("404 wrong method", "404|{\"error\":\"not found\"}", req("DELETE", "/feed", null, null));
        eq("404 trailing", "404|{\"error\":\"not found\"}", req("GET", "/health/", null, null));
        eq("404 like on GET", "404|{\"error\":\"not found\"}", req("GET", "/posts/1/like", null, null));
        for (String id : new String[] {"0", "-1", "abc", "1.5", "+1", "1234567890123456789", "00"}) {
            eq("id " + id, "400|{\"error\":\"invalid post id\"}", req("GET", "/posts/" + id, null, null));
        }
        eq("empty id", "404|{\"error\":\"not found\"}", req("GET", "/posts/", null, null));

        String h = req("GET", "/health", null, null);
        check(h.matches("200\\|\\{\"status\":\"ok\",\"db\":\"ok\",\"uptime_s\":\\d+\\}"), "health: " + h);

        eq("create no token", "401|{\"error\":\"missing bearer token\"}", req("POST", "/posts", null, "{\"body\":\"x\"}"));
        eq("create bad token", "401|{\"error\":\"invalid or expired token\"}", req("POST", "/posts", "Bearer a.b.c", "{\"body\":\"x\"}"));
        eq("like no token", "401|{\"error\":\"missing bearer token\"}", req("POST", "/posts/abc/like", null, null));
        eq("like no token valid id", "401|{\"error\":\"missing bearer token\"}", req("POST", "/posts/1/like", null, null));

        long now = System.currentTimeMillis() / 1000;
        String tok = JwtTest.sign(JwtTest.HDR, JwtTest.payload("\"1\"", "\"golden_ember_1\"", now + 3600));
        eq("like valid token bad id", "400|{\"error\":\"invalid post id\"}", req("POST", "/posts/abc/like", "Bearer " + tok, null));
        String badSub = JwtTest.sign(JwtTest.HDR, JwtTest.payload("\"x\"", "\"u\"", now + 3600));
        eq("bad payload", "401|{\"error\":\"invalid token payload\"}", req("POST", "/posts", "Bearer " + badSub, "{\"body\":\"x\"}"));

        // pipelined requests on one kept-alive connection are answered in order
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream o = s.getOutputStream();
            o.write("GET /health HTTP/1.1\r\nHost: x\r\n\r\nGET /feed HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            check(ServerTest.readResponse(s.getInputStream()).startsWith("200|{\"status\""), "health first");
            check(ServerTest.readResponse(s.getInputStream()).startsWith("200|{\"posts\":["), "feed second");
        }
        System.out.println("RouterTest OK");
    }
}
