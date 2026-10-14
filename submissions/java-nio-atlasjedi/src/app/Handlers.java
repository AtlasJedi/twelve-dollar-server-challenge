package app;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The five endpoints. Each handler fills Resp.body() and calls Resp.send only on success, so an exception
 * leaves the out buffer untouched and the Router can write a clean 500. Health, feed and post are real; create is real; like is a
 * stub until Task 10.
 */
final class Handlers {
    private static final byte[] H_OK = ascii("{\"status\":\"ok\",\"db\":\"ok\",\"uptime_s\":");
    private static final byte[] H_DEG = ascii("{\"status\":\"degraded\",\"db\":\"unreachable\",\"error\":");

    private final Db db;
    private final long startNanos = System.nanoTime();

    Handlers(Db db) {
        this.db = db;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    void health(ByteBuffer out, boolean close) {
        ByteBuffer b = Resp.body();
        boolean ok;
        String err = null;
        try {
            ok = db.health();
        } catch (RuntimeException e) {
            ok = false;
            err = String.valueOf(e.getMessage());
        }
        if (ok) {
            b.put(H_OK);
            Resp.num(b, (System.nanoTime() - startNanos) / 1_000_000_000L);
            b.put((byte) '}');
            Resp.send(out, 200, b, close);
        } else {
            byte[] m = (err == null ? "SELECT 1 returned no row" : err).getBytes(StandardCharsets.UTF_8);
            b.put(H_DEG);
            Resp.str(b, m, 0, m.length);
            b.put((byte) '}');
            Resp.send(out, 503, b, close);
        }
    }

    void feed(ByteBuffer out, boolean close) {
        ByteBuffer b = Resp.body();
        db.feed(b);
        Resp.send(out, 200, b, close);
    }

    void post(long id, ByteBuffer out, boolean close) {
        ByteBuffer b = Resp.body();
        if (db.post(id, b)) Resp.send(out, 200, b, close);
        else Resp.error(out, 404, Resp.E_POST_NOT_FOUND, close);
    }

    private final BodyJson json = new BodyJson();
    private static final byte[] C_PRE = ascii("{\"post\":{\"id\":"), C_BODY = ascii(",\"body\":"),
        C_AT = ascii(",\"created_at\":\""), C_AUTHOR = ascii("\",\"author\":"), C_POST = ascii(",\"like_count\":0}}");

    void create(long userId, byte[] user, int uo, int ul, byte[] body, int bo, int bl, ByteBuffer out, boolean close) {
        int rc = json.parse(body, bo, bl);
        if (rc == BodyJson.MALFORMED) { Resp.error(out, 400, Resp.E_MALFORMED_JSON, close); return; }
        if (rc == BodyJson.MISSING || json.outLen == 0) { Resp.error(out, 400, Resp.E_BODY_REQUIRED, close); return; }
        if (json.codePoints > 500) { Resp.error(out, 400, Resp.E_BODY_TOO_LONG, close); return; }
        long id = db.create(userId, json.outBuf, json.outOff, json.outLen); // returns after the commit
        ByteBuffer b = Resp.body();
        b.put(C_PRE);
        Resp.num(b, id);
        b.put(C_BODY);
        Resp.str(b, json.outBuf, json.outOff, json.outLen);
        b.put(C_AT);
        b.put(db.createdAt, 0, db.createdLen);
        b.put(C_AUTHOR);
        Resp.str(b, user, uo, ul);
        b.put(C_POST);
        Resp.send(out, 201, b, close);
    }

    void like(long userId, long postId, ByteBuffer out, boolean close) {
        Resp.error(out, 500, Resp.E_INTERNAL, close);
    }
}
