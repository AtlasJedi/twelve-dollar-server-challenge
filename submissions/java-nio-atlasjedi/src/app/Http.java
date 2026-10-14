package app;

/**
 * Zero-allocation HTTP/1.1 request framer. All results are offsets into the caller's byte[].
 * Header names are matched ASCII case-insensitively, byte by byte.
 */
final class Http {
    static final int GET = 1, POST = 2, OTHER = 3;

    static final int NEED_MORE = 0;
    static final int MALFORMED = -1;
    /** headers over MAX_HEADERS or body over MAX_BODY: respond 413 and close. */
    static final int TOO_LARGE = -2;

    static final int MAX_HEADERS = 8 * 1024;
    static final int MAX_BODY = 64 * 1024;

    static final class Request {
        int method;
        int pathOff, pathLen;
        int authOff, authLen;
        int bodyOff, bodyLen;
        boolean keepAlive;
        int total; // bytes consumed
        byte[] buf;
    }

    private Http() { }

    /** 0 = need more bytes, >0 = bytes consumed and req filled, -1 = malformed, -2 = too large. */
    static int parse(byte[] buf, int off, int len, Request req) {
        // 1. locate the end of the header block (CRLF CRLF) within the header limit
        int scanEnd = off + Math.min(len, MAX_HEADERS);
        int hdrEnd = -1; // index just past CRLFCRLF
        for (int i = off; i + 3 < scanEnd; i++) {
            if (buf[i + 3] == '\n' && buf[i + 2] == '\r' && buf[i + 1] == '\n' && buf[i] == '\r') {
                hdrEnd = i + 4;
                break;
            }
        }
        if (hdrEnd < 0) return len >= MAX_HEADERS ? TOO_LARGE : NEED_MORE;

        req.buf = buf;
        req.method = 0;
        req.pathOff = req.pathLen = req.authOff = req.authLen = req.bodyOff = req.bodyLen = 0;
        req.total = 0;

        // 2. request line: METHOD SP /path[?query] SP HTTP/1.x CRLF
        int p = off;
        while (p < hdrEnd && buf[p] >= 'A' && buf[p] <= 'Z') p++;
        int mlen = p - off;
        if (mlen == 0 || buf[p] != ' ') return MALFORMED;
        if (mlen == 3 && buf[off] == 'G' && buf[off + 1] == 'E' && buf[off + 2] == 'T') req.method = GET;
        else if (mlen == 4 && buf[off] == 'P' && buf[off + 1] == 'O' && buf[off + 2] == 'S' && buf[off + 3] == 'T') req.method = POST;
        else req.method = OTHER;
        p++;
        if (buf[p] != '/') return MALFORMED;
        int pathStart = p;
        while (buf[p] > ' ' && buf[p] != '?' && buf[p] != 0x7f) p++;
        req.pathOff = pathStart;
        req.pathLen = p - pathStart;
        if (buf[p] == '?') {
            while (buf[p] > ' ' && buf[p] != 0x7f) p++;
        }
        if (buf[p] != ' ') return MALFORMED;
        p++;
        if (buf[p] != 'H' || buf[p + 1] != 'T' || buf[p + 2] != 'T' || buf[p + 3] != 'P' || buf[p + 4] != '/'
                || buf[p + 5] != '1' || buf[p + 6] != '.') return MALFORMED;
        byte minor = buf[p + 7];
        if (minor != '0' && minor != '1') return MALFORMED;
        if (buf[p + 8] != '\r' || buf[p + 9] != '\n') return MALFORMED;
        boolean keepAlive = minor == '1';
        p += 10;

        // 3. headers
        long contentLength = 0;
        while (p < hdrEnd - 2) {
            int nameStart = p;
            while (buf[p] != ':' && buf[p] != '\r') p++;
            if (buf[p] != ':' || p == nameStart) return MALFORMED;
            int nameLen = p - nameStart;
            p++;
            while (buf[p] == ' ' || buf[p] == '\t') p++;
            int valStart = p;
            while (buf[p] != '\r') p++;
            if (buf[p + 1] != '\n') return MALFORMED;
            int valEnd = p;
            while (valEnd > valStart && (buf[valEnd - 1] == ' ' || buf[valEnd - 1] == '\t')) valEnd--;
            p += 2;

            if (nameLen == 13 && eq(buf, nameStart, "authorization")) {
                req.authOff = valStart;
                req.authLen = valEnd - valStart;
            } else if (nameLen == 14 && eq(buf, nameStart, "content-length")) {
                if (valEnd == valStart) return MALFORMED;
                long v = 0;
                for (int i = valStart; i < valEnd; i++) {
                    int d = buf[i] - '0';
                    if (d < 0 || d > 9) return MALFORMED;
                    if (v <= MAX_BODY) v = v * 10 + d; // saturates above the cap, no overflow
                }
                contentLength = v;
            } else if (nameLen == 10 && eq(buf, nameStart, "connection")) {
                int vl = valEnd - valStart;
                if (vl == 5 && eq(buf, valStart, "close")) keepAlive = false;
                else if (vl == 10 && eq(buf, valStart, "keep-alive")) keepAlive = true;
            } else if (nameLen == 17 && eq(buf, nameStart, "transfer-encoding")) {
                return MALFORMED; // chunked request bodies are not supported
            }
        }

        // 4. body
        if (contentLength > MAX_BODY) return TOO_LARGE;
        int bodyLen = (int) contentLength;
        if (len - (hdrEnd - off) < bodyLen) return NEED_MORE;
        req.bodyOff = hdrEnd;
        req.bodyLen = bodyLen;
        req.keepAlive = keepAlive;
        req.total = hdrEnd - off + bodyLen;
        return req.total;
    }

    /** ASCII case-insensitive compare of buf[off..] against a lowercase literal. */
    private static boolean eq(byte[] buf, int off, String lower) {
        for (int i = 0, n = lower.length(); i < n; i++) {
            int c = buf[off + i];
            if (c >= 'A' && c <= 'Z') c += 32;
            if (c != lower.charAt(i)) return false;
        }
        return true;
    }
}
