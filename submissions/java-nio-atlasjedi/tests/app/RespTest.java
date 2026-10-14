package app;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Task 5: response writer. Status lines, headers, error responses, JSON string/number helpers. */
public class RespTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static String drain(ByteBuffer b) {
        b.flip();
        byte[] a = new byte[b.remaining()];
        b.get(a);
        return new String(a, StandardCharsets.UTF_8);
    }

    static void eq(String expected, String actual, String msg) {
        check(expected.equals(actual), msg + ": expected [" + expected + "] got [" + actual + "]");
    }

    static String strOf(String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocateDirect(1024);
        Resp.str(b, u, 0, u.length);
        return drain(b);
    }

    public static void main(String[] args) {
        // str: test.sh special-characters case
        eq("\"test: <b>&amp;</b> \\\"quoted\\\" / café ✓\"", strOf("test: <b>&amp;</b> \"quoted\" / café ✓"), "special chars");
        eq("\"\\u0001\"", strOf("\u0001"), "control 0x01");
        eq("\"\\n\\r\\t\\b\\f\"", strOf("\n\r\t\b\f"), "short forms");
        eq("\"a\\\\b\"", strOf("a\\b"), "backslash");
        eq("\"\\u001f \"", strOf("\u001f "), "0x1f");
        eq("\"😀\"", strOf("😀"), "emoji passthrough");
        eq("\"\"", strOf(""), "empty");

        // str with offset
        {
            byte[] u = "xx\"yy".getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocateDirect(64);
            Resp.str(b, u, 2, 1);
            eq("\"\\\"\"", drain(b), "offset/len");
        }

        // str from MemorySegment
        try (Arena a = Arena.ofConfined()) {
            byte[] u = "café \"x\"\n".getBytes(StandardCharsets.UTF_8);
            MemorySegment seg = a.allocate(u.length + 1);
            MemorySegment.copy(u, 0, seg, ValueLayout.JAVA_BYTE, 0, u.length);
            ByteBuffer b = ByteBuffer.allocateDirect(64);
            Resp.str(b, seg, u.length);
            eq("\"café \\\"x\\\"\\n\"", drain(b), "segment");
        }

        // num
        long[] vs = {0, 7, -5, 10, 123456789L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long v : vs) {
            ByteBuffer b = ByteBuffer.allocateDirect(64);
            Resp.num(b, v);
            eq(Long.toString(v), drain(b), "num " + v);
        }

        // status lines
        int[] codes = {200, 201, 400, 401, 404, 413, 500, 503};
        String[] lines = {"200 OK", "201 Created", "400 Bad Request", "401 Unauthorized", "404 Not Found",
            "413 Content Too Large", "500 Internal Server Error", "503 Service Unavailable"};
        for (int i = 0; i < codes.length; i++) {
            ByteBuffer b = ByteBuffer.allocateDirect(64);
            Resp.status(b, codes[i]);
            eq("HTTP/1.1 " + lines[i] + "\r\n", drain(b), "status " + codes[i]);
        }

        // jsonHeaders
        {
            ByteBuffer b = ByteBuffer.allocateDirect(256);
            Resp.jsonHeaders(b, 1234, false);
            eq("Content-Type: application/json\r\nContent-Length: 1234\r\nConnection: keep-alive\r\n\r\n", drain(b), "headers keep-alive");
            b.clear();
            Resp.jsonHeaders(b, 0, true);
            eq("Content-Type: application/json\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", drain(b), "headers close");
        }

        // error: exact bytes
        {
            ByteBuffer b = ByteBuffer.allocateDirect(512);
            Resp.error(b, 404, Resp.E_NOT_FOUND, false);
            eq("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 21\r\nConnection: keep-alive\r\n\r\n{\"error\":\"not found\"}",
                drain(b), "error 404");
            b.clear();
            Resp.error(b, 401, Resp.E_MISSING_TOKEN, true);
            eq("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: 32\r\nConnection: close\r\n\r\n{\"error\":\"missing bearer token\"}",
                drain(b), "error 401 close");
        }

        // every E_* constant, both close variants, through the generic formula
        {
            byte[][] es = {Resp.E_NOT_FOUND, Resp.E_INVALID_ID, Resp.E_POST_NOT_FOUND, Resp.E_MISSING_TOKEN,
                Resp.E_INVALID_TOKEN, Resp.E_INVALID_PAYLOAD, Resp.E_MALFORMED_JSON, Resp.E_BODY_REQUIRED,
                Resp.E_BODY_TOO_LONG, Resp.E_INTERNAL};
            String[] ms = {"not found", "invalid post id", "post not found", "missing bearer token",
                "invalid or expired token", "invalid token payload", "malformed JSON body", "body is required",
                "body must be at most 500 characters", "internal server error"};
            for (int i = 0; i < es.length; i++) {
                eq(ms[i], new String(es[i], StandardCharsets.UTF_8), "E const " + i);
                for (boolean close : new boolean[] {false, true}) {
                    String body = "{\"error\":\"" + ms[i] + "\"}";
                    String exp = "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                        + "\r\nConnection: " + (close ? "close" : "keep-alive") + "\r\n\r\n" + body;
                    ByteBuffer b = ByteBuffer.allocateDirect(512);
                    Resp.error(b, 400, es[i], close);
                    eq(exp, drain(b), "error " + ms[i] + " close=" + close);
                }
            }
            // unknown message (not a precomputed constant) still works
            byte[] m = "custom".getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocateDirect(512);
            Resp.error(b, 503, m, false);
            eq("HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nContent-Length: 18\r\nConnection: keep-alive\r\n\r\n{\"error\":\"custom\"}",
                drain(b), "error custom");
        }

        // body() + send(): two-pass
        {
            ByteBuffer body = Resp.body();
            check(body.isDirect() && body.position() == 0 && body.capacity() >= 256 * 1024, "body buffer");
            body.put("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocateDirect(512);
            Resp.send(out, 201, body, false);
            eq("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: 7\r\nConnection: keep-alive\r\n\r\n{\"a\":1}",
                drain(out), "send");
            check(Resp.body().position() == 0 && Resp.body().limit() == Resp.body().capacity(), "body() returns cleared buffer");
        }

        System.out.println("RespTest OK");
    }
}
