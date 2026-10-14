package app;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The five endpoints. Each handler fills Resp.body() and calls Resp.send only on success, so an exception
 * leaves the out buffer untouched and the Router can write a clean 500. Health, feed and post are real; create/like are
 * stubs until Tasks 9-10.
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

    void create(long userId, byte[] user, int uo, int ul, byte[] body, int bo, int bl, ByteBuffer out, boolean close) {
        Resp.error(out, 500, Resp.E_INTERNAL, close);
    }

    void like(long userId, long postId, ByteBuffer out, boolean close) {
        Resp.error(out, 500, Resp.E_INTERNAL, close);
    }
}
