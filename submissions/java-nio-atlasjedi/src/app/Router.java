package app;

import java.nio.ByteBuffer;

/** Byte-level dispatch, auth (POST routes) before id parsing, and exception-to-500 mapping. No allocation per request. */
final class Router implements Server.Handler {
    private final Handlers h;
    private final Jwt jwt;

    Router(Handlers h, Jwt jwt) {
        this.h = h;
        this.jwt = jwt;
    }

    private static final byte[] HEALTH = lit("/health"), FEED = lit("/feed"), POSTS = lit("/posts"),
        POSTS_SLASH = lit("/posts/"), LIKE = lit("/like");

    private static byte[] lit(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static boolean eq(byte[] b, int off, int len, byte[] lit) {
        if (len != lit.length) return false;
        for (int i = 0; i < len; i++) if (b[off + i] != lit[i]) return false;
        return true;
    }

    private static boolean starts(byte[] b, int off, int len, byte[] lit) {
        if (len < lit.length) return false;
        for (int i = 0; i < lit.length; i++) if (b[off + i] != lit[i]) return false;
        return true;
    }

    /** Digits only, 1-18 of them, value >= 1; otherwise -1. */
    private static long id(byte[] b, int off, int end) {
        int n = end - off;
        if (n < 1 || n > 18) return -1;
        long v = 0;
        for (int i = off; i < end; i++) {
            int d = b[i] - '0';
            if (d < 0 || d > 9) return -1;
            v = v * 10 + d;
        }
        return v >= 1 ? v : -1;
    }

    @Override
    public boolean tick() {
        return h.db().commitTick();
    }

    @Override
    public void afterTick() {
        h.db().afterTick();
    }

    @Override
    public void handle(Server.Conn c, Http.Request r, ByteBuffer out) {
        boolean close = !r.keepAlive;
        int mark = out.position();
        long w = h.db().writesSinceCheckpoint;
        try {
            dispatch(r, out, close);
            if (h.db().writesSinceCheckpoint != w) c.deferred = true; // the 2xx waits for the tick's COMMIT
        } catch (Throwable t) {
            out.position(mark); // drop anything half-written, the body buffer is reset by Resp.body() next use
            Resp.error(out, 500, Resp.E_INTERNAL, close);
        }
    }

    private void dispatch(Http.Request r, ByteBuffer out, boolean close) {
        byte[] b = r.buf;
        int po = r.pathOff, pl = r.pathLen;
        if (r.method == Http.GET) {
            if (eq(b, po, pl, HEALTH)) { h.health(out, close); return; }
            if (eq(b, po, pl, FEED)) { h.feed(out, close); return; }
            if (starts(b, po, pl, POSTS_SLASH)) {
                long id = id(b, po + POSTS_SLASH.length, po + pl);
                if (id < 0) {
                    // a further slash makes it a different path (404), not a bad id
                    for (int i = po + POSTS_SLASH.length; i < po + pl; i++) {
                        if (b[i] == '/') { Resp.error(out, 404, Resp.E_NOT_FOUND, close); return; }
                    }
                    if (pl == POSTS_SLASH.length) { Resp.error(out, 404, Resp.E_NOT_FOUND, close); return; }
                    Resp.error(out, 400, Resp.E_INVALID_ID, close);
                    return;
                }
                h.post(id, out, close);
                return;
            }
        } else if (r.method == Http.POST) {
            if (eq(b, po, pl, POSTS)) {
                if (!auth(r, out, close)) return;
                h.create(jwt.userId, jwt.userBuf, jwt.userOff, jwt.userLen, b, r.bodyOff, r.bodyLen, out, close);
                return;
            }
            if (starts(b, po, pl, POSTS_SLASH) && pl > POSTS_SLASH.length + LIKE.length
                    && eq(b, po + pl - LIKE.length, LIKE.length, LIKE)) {
                if (!auth(r, out, close)) return;
                long id = id(b, po + POSTS_SLASH.length, po + pl - LIKE.length);
                if (id < 0) {
                    Resp.error(out, 400, Resp.E_INVALID_ID, close);
                    return;
                }
                h.like(jwt.userId, id, out, close);
                return;
            }
        }
        Resp.error(out, 404, Resp.E_NOT_FOUND, close);
    }

    /** Verifies the bearer token; on failure writes the 401 and returns false. */
    private boolean auth(Http.Request r, ByteBuffer out, boolean close) {
        int rc = jwt.verify(r.buf, r.authOff, r.authLen, System.currentTimeMillis() / 1000);
        switch (rc) {
            case Jwt.OK: return true;
            case Jwt.MISSING: Resp.error(out, 401, Resp.E_MISSING_TOKEN, close); return false;
            case Jwt.BAD_PAYLOAD: Resp.error(out, 401, Resp.E_INVALID_PAYLOAD, close); return false;
            default: Resp.error(out, 401, Resp.E_INVALID_TOKEN, close); return false;
        }
    }
}
