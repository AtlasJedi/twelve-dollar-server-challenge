package app;

import java.nio.charset.StandardCharsets;

/** Task 4: HTTP/1.1 parser. Framing, splits, pipelining, malformed, limits. */
public class HttpTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static String str(byte[] buf, int off, int len) {
        return new String(buf, off, len, StandardCharsets.ISO_8859_1);
    }

    public static void main(String[] args) {
        Http.Request r = new Http.Request();

        // simple GET
        byte[] g = b("GET /health HTTP/1.1\r\nHost: x\r\n\r\n");
        int n = Http.parse(g, 0, g.length, r);
        check(n == g.length && r.total == n, "get consumed " + n);
        check(r.method == Http.GET, "get method");
        check("/health".equals(str(g, r.pathOff, r.pathLen)), "get path");
        check(r.authLen == 0 && r.bodyLen == 0, "get no auth/body");
        check(r.keepAlive, "get keepAlive");
        check(r.buf == g, "buf set");

        // query string is dropped from the path
        g = b("GET /feed?limit=5 HTTP/1.1\r\n\r\n");
        n = Http.parse(g, 0, g.length, r);
        check(n == g.length && "/feed".equals(str(g, r.pathOff, r.pathLen)), "query path");

        // POST with body, auth, mixed-case header names
        String post = "POST /posts HTTP/1.1\r\nhOsT: x\r\nauthorization: Bearer abc.def.ghi\r\n"
                + "CONTENT-length: 15\r\nContent-Type: application/json\r\n\r\n{\"body\":\"hi!!\"}";
        byte[] p = b(post);
        n = Http.parse(p, 0, p.length, r);
        check(n == p.length, "post consumed " + n);
        check(r.method == Http.POST, "post method");
        check("/posts".equals(str(p, r.pathOff, r.pathLen)), "post path");
        check("Bearer abc.def.ghi".equals(str(p, r.authOff, r.authLen)), "auth " + str(p, r.authOff, r.authLen));
        check("{\"body\":\"hi!!\"}".equals(str(p, r.bodyOff, r.bodyLen)), "body");

        // other method
        g = b("DELETE /posts/1 HTTP/1.1\r\n\r\n");
        check(Http.parse(g, 0, g.length, r) == g.length && r.method == Http.OTHER, "other method");

        // split at every byte boundary of the POST: 0 until the last byte
        for (int cut = 1; cut < p.length; cut++) {
            check(Http.parse(p, 0, cut, r) == 0, "prefix " + cut + " must need more");
        }
        // fed one byte at a time into a growing buffer
        byte[] grow = new byte[p.length];
        int have = 0, res = 0;
        for (int i = 0; i < p.length; i++) {
            grow[have++] = p[i];
            res = Http.parse(grow, 0, have, r);
            if (i < p.length - 1) check(res == 0, "byte " + i + " gave " + res);
        }
        check(res == p.length && "{\"body\":\"hi!!\"}".equals(str(grow, r.bodyOff, r.bodyLen)), "byte-wise final");

        // two pipelined GETs in one buffer, and a non-zero start offset
        byte[] one = b("GET /posts/1 HTTP/1.1\r\n\r\n");
        byte[] two = b("GET /posts/22 HTTP/1.1\r\nConnection: keep-alive\r\n\r\n");
        byte[] both = new byte[one.length + two.length + 3];
        System.arraycopy(b("xxx"), 0, both, 0, 3);
        System.arraycopy(one, 0, both, 3, one.length);
        System.arraycopy(two, 0, both, 3 + one.length, two.length);
        int off = 3;
        n = Http.parse(both, off, both.length - off, r);
        check(n == one.length, "pipe 1 consumed " + n);
        check("/posts/1".equals(str(both, r.pathOff, r.pathLen)), "pipe 1 path");
        off += n;
        n = Http.parse(both, off, both.length - off, r);
        check(n == two.length, "pipe 2 consumed " + n);
        check("/posts/22".equals(str(both, r.pathOff, r.pathLen)) && r.keepAlive, "pipe 2 path");

        // garbage -> -1
        for (String bad : new String[] {"garbage\r\n\r\n", "GET\r\n\r\n", "GET /x\r\n\r\n", "GET /x FTP/1.1\r\n\r\n",
                "GET /x HTTP/1.1\r\nbadheader\r\n\r\n", "POST /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n",
                "\u0001\u0002\u0003 /x HTTP/1.1\r\n\r\n"}) {
            byte[] x = b(bad);
            check(Http.parse(x, 0, x.length, r) == -1, "garbage not -1: " + bad.replace("\r\n", "|"));
        }

        // Connection: close (mixed case) -> keepAlive false; HTTP/1.0 default close
        g = b("GET /health HTTP/1.1\r\nCoNnEcTiOn: Close\r\n\r\n");
        check(Http.parse(g, 0, g.length, r) == g.length && !r.keepAlive, "connection close");
        g = b("GET /health HTTP/1.0\r\n\r\n");
        check(Http.parse(g, 0, g.length, r) == g.length && !r.keepAlive, "http/1.0 closes");

        // request object is reused: stale fields do not leak
        g = b("GET /a HTTP/1.1\r\n\r\n");
        Http.parse(p, 0, p.length, r);
        Http.parse(g, 0, g.length, r);
        check(r.authLen == 0 && r.bodyLen == 0 && r.method == Http.GET, "reset between parses");

        // limits: headers over 8 KB and body over 64 KB are -2 (413)
        StringBuilder big = new StringBuilder("GET /x HTTP/1.1\r\nX-Pad: ");
        while (big.length() < 9000) big.append('a');
        byte[] bigNoEnd = b(big.toString());
        check(Http.parse(bigNoEnd, 0, bigNoEnd.length, r) == -2, "headers > 8KB without end");
        byte[] bigEnd = b(big + "\r\n\r\n");
        check(Http.parse(bigEnd, 0, bigEnd.length, r) == -2, "headers > 8KB with end");
        byte[] cl = b("POST /posts HTTP/1.1\r\nContent-Length: 65537\r\n\r\n");
        check(Http.parse(cl, 0, cl.length, r) == -2, "body > 64KB");
        cl = b("POST /posts HTTP/1.1\r\nContent-Length: 65536\r\n\r\n");
        check(Http.parse(cl, 0, cl.length, r) == 0, "body == 64KB waits for body");

        System.out.println("HttpTest ok");
    }
}
