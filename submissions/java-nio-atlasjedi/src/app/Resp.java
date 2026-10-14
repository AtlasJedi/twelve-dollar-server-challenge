package app;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Task 5: response writer. Everything on the hot path writes pre-encoded constants or ASCII digits
 * straight into a direct ByteBuffer; no String building, no allocation per call (one tiny
 * MemorySegment view in the segment variant of str).
 * Bodies of unknown length: fill {@link #body()}, then {@link #send} (status + headers + body).
 */
final class Resp {
    private Resp() {}

    private static byte[] a(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // Error messages (SPEC.md "Errors").
    static final byte[] E_NOT_FOUND = a("not found");
    static final byte[] E_INVALID_ID = a("invalid post id");
    static final byte[] E_POST_NOT_FOUND = a("post not found");
    static final byte[] E_MISSING_TOKEN = a("missing bearer token");
    static final byte[] E_INVALID_TOKEN = a("invalid or expired token");
    static final byte[] E_INVALID_PAYLOAD = a("invalid token payload");
    static final byte[] E_MALFORMED_JSON = a("malformed JSON body");
    static final byte[] E_BODY_REQUIRED = a("body is required");
    static final byte[] E_BODY_TOO_LONG = a("body must be at most 500 characters");
    static final byte[] E_INTERNAL = a("internal server error");

    private static final byte[][] MSGS = {E_NOT_FOUND, E_INVALID_ID, E_POST_NOT_FOUND, E_MISSING_TOKEN,
        E_INVALID_TOKEN, E_INVALID_PAYLOAD, E_MALFORMED_JSON, E_BODY_REQUIRED, E_BODY_TOO_LONG, E_INTERNAL};

    private static final byte[] H_TYPE = a("Content-Type: application/json\r\nContent-Length: ");
    private static final byte[] H_KEEP = a("\r\nConnection: keep-alive\r\n\r\n");
    private static final byte[] H_CLOSE = a("\r\nConnection: close\r\n\r\n");
    private static final byte[] ERR_PRE = a("{\"error\":\"");
    private static final byte[] ERR_POST = a("\"}");

    private static final byte[][] STATUS = new byte[600][];

    static {
        STATUS[200] = a("HTTP/1.1 200 OK\r\n");
        STATUS[201] = a("HTTP/1.1 201 Created\r\n");
        STATUS[400] = a("HTTP/1.1 400 Bad Request\r\n");
        STATUS[401] = a("HTTP/1.1 401 Unauthorized\r\n");
        STATUS[404] = a("HTTP/1.1 404 Not Found\r\n");
        STATUS[413] = a("HTTP/1.1 413 Content Too Large\r\n");
        STATUS[500] = a("HTTP/1.1 500 Internal Server Error\r\n");
        STATUS[503] = a("HTTP/1.1 503 Service Unavailable\r\n");
    }

    /** Per E_* message: [0] = headers+body for keep-alive, [1] = for close (everything after the status line). */
    private static final byte[][][] ERR_TAIL = new byte[MSGS.length][2][];

    static {
        for (int i = 0; i < MSGS.length; i++) {
            ERR_TAIL[i][0] = tail(MSGS[i], false);
            ERR_TAIL[i][1] = tail(MSGS[i], true);
        }
    }

    private static byte[] tail(byte[] msg, boolean close) {
        int len = ERR_PRE.length + msg.length + ERR_POST.length;
        byte[] hdr = close ? H_CLOSE : H_KEEP;
        byte[] cl = a(Integer.toString(len)); // class init only
        byte[] t = new byte[H_TYPE.length + cl.length + hdr.length + len];
        int p = 0;
        System.arraycopy(H_TYPE, 0, t, p, H_TYPE.length); p += H_TYPE.length;
        System.arraycopy(cl, 0, t, p, cl.length); p += cl.length;
        System.arraycopy(hdr, 0, t, p, hdr.length); p += hdr.length;
        System.arraycopy(ERR_PRE, 0, t, p, ERR_PRE.length); p += ERR_PRE.length;
        System.arraycopy(msg, 0, t, p, msg.length); p += msg.length;
        System.arraycopy(ERR_POST, 0, t, p, ERR_POST.length);
        return t;
    }

    static void status(ByteBuffer out, int code) {
        out.put(STATUS[code]);
    }

    static void jsonHeaders(ByteBuffer out, int contentLength, boolean close) {
        out.put(H_TYPE);
        num(out, contentLength);
        out.put(close ? H_CLOSE : H_KEEP);
    }

    /** Full {"error":"msg"} response. Messages that are Resp.E_* constants (identity) use precomputed tails. */
    static void error(ByteBuffer out, int code, byte[] msg, boolean close) {
        out.put(STATUS[code]);
        for (int i = 0; i < MSGS.length; i++) {
            if (MSGS[i] == msg) {
                out.put(ERR_TAIL[i][close ? 1 : 0]);
                return;
            }
        }
        jsonHeaders(out, ERR_PRE.length + msg.length + ERR_POST.length, close);
        out.put(ERR_PRE).put(msg).put(ERR_POST);
    }

    // ---- two-pass bodies ----

    private static final ByteBuffer BODY = ByteBuffer.allocateDirect(256 * 1024);

    /** The reusable body buffer, cleared. Event-loop thread only. */
    static ByteBuffer body() {
        BODY.clear();
        return BODY;
    }

    /** Writes status + jsonHeaders(Content-Length = body bytes written so far) + body into out. Consumes body. */
    static void send(ByteBuffer out, int code, ByteBuffer body, boolean close) {
        body.flip();
        out.put(STATUS[code]);
        jsonHeaders(out, body.remaining(), close);
        out.put(body);
        body.clear();
    }

    // ---- JSON helpers ----

    private static final byte[] HEX = a("0123456789abcdef");
    private static final byte[] ESC = new byte[0x20]; // short-form escape char, or 0 for the hex form

    static {
        ESC['\n'] = 'n';
        ESC['\r'] = 'r';
        ESC['\t'] = 't';
        ESC['\b'] = 'b';
        ESC['\f'] = 'f';
    }

    private static void esc(ByteBuffer b, int c) {
        if (c == '"' || c == '\\') {
            b.put((byte) '\\').put((byte) c);
        } else if (ESC[c] != 0) {
            b.put((byte) '\\').put(ESC[c]);
        } else {
            b.put((byte) '\\').put((byte) 'u').put((byte) '0').put((byte) '0')
                .put(HEX[c >> 4]).put(HEX[c & 15]);
        }
    }

    /** Quoted JSON string from UTF-8 bytes. Escapes " \ and < 0x20; everything else (incl. / and non-ASCII) is verbatim. */
    static void str(ByteBuffer b, byte[] utf8, int off, int len) {
        b.put((byte) '"');
        int end = off + len;
        int run = off;
        for (int i = off; i < end; i++) {
            int c = utf8[i];
            if (c == '"' || c == '\\' || (c >= 0 && c < 0x20)) {
                if (i > run) b.put(utf8, run, i - run);
                esc(b, c);
                run = i + 1;
            }
        }
        if (end > run) b.put(utf8, run, end - run);
        b.put((byte) '"');
    }

    /** Same as above, reading len bytes from the start of a native segment (e.g. sqlite3_column_text). */
    static void str(ByteBuffer b, MemorySegment seg, int len) {
        b.put((byte) '"');
        int run = 0;
        for (int i = 0; i < len; i++) {
            int c = seg.get(ValueLayout.JAVA_BYTE, i);
            if (c == '"' || c == '\\' || (c >= 0 && c < 0x20)) {
                if (i > run) {
                    copyRun(b, seg, run, i - run);
                }
                esc(b, c);
                run = i + 1;
            }
        }
        if (len > run) {
            copyRun(b, seg, run, len - run);
        }
        b.put((byte) '"');
    }

    private static void copyRun(ByteBuffer b, MemorySegment seg, int from, int n) {
        if (b.remaining() < n) throw new java.nio.BufferOverflowException();
        // ofBuffer views [position, limit) of the buffer, so offset 0 is the current write position
        MemorySegment.copy(seg, from, MemorySegment.ofBuffer(b), 0, n);
        b.position(b.position() + n);
    }

    /** Decimal ASCII digits, no allocation. */
    static void num(ByteBuffer b, long v) {
        if (v >= 0 && v < 10) {
            b.put((byte) ('0' + v));
            return;
        }
        long n = v > 0 ? -v : v; // accumulate negatively so Long.MIN_VALUE works
        int digits = 0;
        for (long t = n; t != 0; t /= 10) digits++;
        int neg = v < 0 ? 1 : 0;
        int total = digits + neg;
        int pos = b.position();
        if (b.remaining() < total) throw new java.nio.BufferOverflowException();
        if (neg == 1) b.put(pos, (byte) '-');
        for (int i = pos + total - 1; i >= pos + neg; i--) {
            b.put(i, (byte) ('0' - (n % 10)));
            n /= 10;
        }
        b.position(pos + total);
    }
}
