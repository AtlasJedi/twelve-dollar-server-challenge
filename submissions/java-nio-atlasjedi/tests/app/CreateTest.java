package app;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Task 9: POST /posts validation, trimming, code-point limit, created_at format, read-back. */
public class CreateTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static int port;

    /** One POST on a fresh connection (UTF-8 aware); returns "status|body". */
    static String post(String auth, String body) throws Exception {
        return send("POST", "/posts", auth, body);
    }

    static String send(String method, String path, String auth, String body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            byte[] b = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n");
            if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n");
            if (b != null) sb.append("Content-Type: application/json\r\nContent-Length: ").append(b.length).append("\r\n");
            sb.append("\r\n");
            OutputStream o = s.getOutputStream();
            o.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            if (b != null) o.write(b);
            InputStream in = s.getInputStream();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int c = in.read();
                check(c >= 0, "eof in headers");
                head.append((char) c);
            }
            String h = head.toString();
            check(h.toLowerCase().contains("content-type: application/json"), "content-type: " + h);
            int cl = 0;
            for (String line : h.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) cl = Integer.parseInt(line.substring(15).trim());
            }
            return h.substring(9, 12) + "|" + new String(in.readNBytes(cl), StandardCharsets.UTF_8);
        }
    }

    static void eq(String name, String exp, String act) {
        check(exp.equals(act), name + ": expected <" + exp + "> got <" + act + ">");
    }

    static String auth;

    static void bad(String name, String body, String err) throws Exception {
        int code = err.equals("body is required") || err.startsWith("malformed") || err.startsWith("body must") ? 400 : 0;
        eq(name, code + "|{\"error\":\"" + err + "\"}", post(auth, body));
    }

    static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        return json.substring(i + key.length() + 3);
    }

    public static void main(String[] args) throws Exception {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile("feed-create", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        Server server = Main.start(tmp.toString(), JwtTest.SECRET, "127.0.0.1", 0);
        port = server.port();
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();

        long now = System.currentTimeMillis() / 1000;
        auth = "Bearer " + JwtTest.sign(JwtTest.HDR, JwtTest.payload("\"1\"", "\"golden_ember_1\"", now + 3600));

        // the six validation cases of test.sh
        bad("empty", "{\"body\":\"\"}", "body is required");
        bad("whitespace", "{\"body\":\"   \"}", "body is required");
        bad("numeric", "{\"body\":123}", "body is required");
        bad("missing", "{}", "body is required");
        bad("malformed", "{bad json", "malformed JSON body");
        bad("501", "{\"body\":\"" + "a".repeat(501) + "\"}", "body must be at most 500 characters");

        // more validation edges
        bad("empty request body", "", "malformed JSON body");
        bad("trailing garbage", "{\"body\":\"x\"} x", "malformed JSON body");
        bad("not an object", "[\"x\"]", "malformed JSON body");
        bad("bad escape", "{\"body\":\"a\\qb\"}", "malformed JSON body");
        bad("bad number", "{\"a\":01,\"body\":\"x\"}", "malformed JSON body");
        bad("null body", "{\"body\":null}", "body is required");
        bad("array body", "{\"body\":[\"x\"]}", "body is required");
        bad("unicode ws only", "{\"body\":\"\\u00a0\\u3000\\u2028 \\n\\t\"}", "body is required");
        bad("trimmed 500 + ws ok but 501", "{\"body\":\" " + "a".repeat(501) + " \"}", "body must be at most 500 characters");

        // special characters round trip, exactly as test.sh
        String sp = post(auth, "{\"body\":\"  test: <b>&amp;</b> \\\"quoted\\\" / café ✓  \"}");
        check(sp.startsWith("201|{\"post\":{\"id\":"), "special: " + sp);
        check(sp.contains("\"body\":\"test: <b>&amp;</b> \\\"quoted\\\" / café ✓\",\"created_at\""), "special body: " + sp);

        // 500-char body padded with whitespace -> exact object
        String b500 = "b".repeat(500);
        String r = post(auth, "{\"body\":\"  " + b500 + " \"}");
        check(r.startsWith("201|"), "500: " + r.substring(0, Math.min(80, r.length())));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^201\\|\\{\"post\":\\{\"id\":(\\d+),\"body\":\"" + b500 + "\",\"created_at\":\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)\""
            + ",\"author\":\"golden_ember_1\",\"like_count\":0\\}\\}$").matcher(r);
        check(m.matches(), "500 shape: " + r);
        String id = m.group(1);
        String created = m.group(2);
        long drift = Math.abs(java.time.Instant.parse(created).getEpochSecond() - System.currentTimeMillis() / 1000);
        check(drift < 60, "created_at drift " + drift);

        // read back
        String one = send("GET", "/posts/" + id, null, null);
        eq("get after create", "200|" + r.substring(4), one);
        String feed = send("GET", "/feed", null, null);
        check(feed.startsWith("200|{\"posts\":[" + r.substring(12, r.length() - 1) + ","), "feed first: " + feed.substring(0, 120));

        // emoji: 4-byte UTF-8 / surrogate pairs counted as code points
        String emoji = "😀";
        String e500 = emoji.repeat(500);
        String er = post(auth, "{\"body\":\"" + e500 + "\"}");
        check(er.startsWith("201|") && er.contains("\"body\":\"" + e500 + "\""), "500 emoji: " + er.length());
        bad("501 emoji", "{\"body\":\"" + emoji.repeat(501) + "\"}", "body must be at most 500 characters");
        // same via \\u escapes with a surrogate pair
        String esc = "\\ud83d\\ude00";
        String ee = post(auth, "{\"body\":\"" + esc.repeat(500) + "\"}");
        check(ee.startsWith("201|") && ee.contains("\"body\":\"" + e500 + "\""), "500 escaped emoji");
        bad("501 escaped emoji", "{\"body\":\"" + esc.repeat(501) + "\"}", "body must be at most 500 characters");

        // escapes decode and re-encode; control char survives as \\u form; key escapes; duplicate key last wins; nested skipped
        String x = post(auth, "{\"n\":{\"a\":[1,2.5e3,true,false,null,{}]},\"b\\u006fdy\":\"a\\tb\\/c\\u0001\",\"body\":\"last\\n\"}");
        check(x.startsWith("201|") && x.contains("\"body\":\"last\""), "dup/escapes: " + x);
        String y = post(auth, "{ \"body\" : \"a\\tb\\/c\\u0001\\\\\" }\r\n");
        check(y.startsWith("201|") && y.contains("\"body\":\"a\\tb/c\\u0001\\\\\""), "escape round trip: " + y);
        // Python strip(): NBSP, U+0085, U+2003, U+3000, \x1f trimmed
        String z = post(auth, "{\"body\":\"\\u00a0\\u0085\\u2003\\u3000\\u001fhi\\u202f\\u2029\"}");
        check(z.startsWith("201|") && z.contains("\"body\":\"hi\""), "unicode trim: " + z);
        // lone surrogate is not fatal
        String lone = post(auth, "{\"body\":\"a\\ud800b\"}");
        check(lone.startsWith("201|"), "lone surrogate: " + lone);
        System.out.println("CreateTest OK");
    }
}
