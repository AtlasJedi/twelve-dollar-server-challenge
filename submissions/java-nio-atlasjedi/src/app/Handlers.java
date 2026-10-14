package app;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The five endpoints. Each handler fills Resp.body() and calls Resp.send only on success, so an exception
 * leaves the out buffer untouched and the Router can write a clean 500. Health is real; the rest are stubs
 * until Tasks 8-10.
 */
final class Handlers {
    private static final byte[] H_OK = ascii("{\"status\":\"ok\",\"db\":\"ok\",\"uptime_s\":");
    private static final byte[] H_DEG = ascii("{\"status\":\"degraded\",\"db\":\"unreachable\",\"error\":");

    private final Sqlite.Stmt ping;
    private final long startNanos = System.nanoTime();

    Handlers(Sqlite db) {
        ping = db.prepare("SELECT 1");
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    void health(ByteBuffer out, boolean close) {
        ByteBuffer b = Resp.body();
        boolean ok;
        String err = null;
        try {
            ping.reset();
            ok = ping.step();
            ping.reset();
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
        Resp.error(out, 500, Resp.E_INTERNAL, close);
    }

    void post(long id, ByteBuffer out, boolean close) {
        Resp.error(out, 500, Resp.E_INTERNAL, close);
    }

    void create(long userId, byte[] user, int uo, int ul, byte[] body, int bo, int bl, ByteBuffer out, boolean close) {
        Resp.error(out, 500, Resp.E_INTERNAL, close);
    }

    void like(long userId, long postId, ByteBuffer out, boolean close) {
        Resp.error(out, 500, Resp.E_INTERNAL, close);
    }
}
