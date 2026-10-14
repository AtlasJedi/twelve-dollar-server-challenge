package app;

/**
 * Strict parser for {"body": "..."} requests, working on offsets into the request byte[]. Single-threaded, reusable.
 * After parse() == OK, [outBuf, outOff, outOff+outLen) is the decoded, Python-str.strip()-trimmed UTF-8 body and
 * codePoints its length in code points. The span points into the input when no escape was present, else into scratch.
 */
final class BodyJson {
    static final int OK = 0, MALFORMED = 1, MISSING = 2;
    private static final int MAX_DEPTH = 64;

    byte[] outBuf;
    int outOff, outLen, codePoints;

    private byte[] scratch = new byte[8192];
    private final byte[] key = new byte[4];
    private byte[] buf;
    private int p, end;
    // last "body" string value seen (raw span between the quotes) and whether it needs unescaping
    private int bodyStart, bodyEnd;
    private boolean bodyIsString, bodySeen, bodyEsc;
    // result of the last string() call
    private boolean strEsc;

    int parse(byte[] b, int off, int len) {
        buf = b;
        p = off;
        end = off + len;
        bodySeen = false;
        bodyIsString = false;
        ws();
        if (p >= end || b[p] != '{') return MALFORMED;
        p++;
        ws();
        if (p < end && b[p] == '}') {
            p++;
        } else {
            while (true) {
                ws();
                if (p >= end || b[p] != '"') return MALFORMED;
                int ks = p + 1;
                if (!string()) return MALFORMED;
                boolean isBody = keyIsBody(ks, p - 1, strEsc);
                ws();
                if (p >= end || b[p] != ':') return MALFORMED;
                p++;
                ws();
                if (p >= end) return MALFORMED;
                if (isBody) {
                    bodySeen = true;
                    if (b[p] == '"') {
                        int vs = p + 1;
                        if (!string()) return MALFORMED;
                        bodyIsString = true;
                        bodyStart = vs;
                        bodyEnd = p - 1;
                        bodyEsc = strEsc;
                    } else {
                        bodyIsString = false;
                        if (!value(0)) return MALFORMED;
                    }
                } else if (!value(0)) {
                    return MALFORMED;
                }
                ws();
                if (p >= end) return MALFORMED;
                if (b[p] == ',') { p++; continue; }
                if (b[p] == '}') { p++; break; }
                return MALFORMED;
            }
        }
        ws();
        if (p != end) return MALFORMED;
        if (!bodySeen || !bodyIsString) return MISSING;
        // produce the trimmed span
        byte[] src = buf;
        int s = bodyStart, e = bodyEnd;
        if (bodyEsc) {
            int n = e - s;
            if (scratch.length < n) scratch = new byte[Math.max(n, scratch.length * 2)];
            e = unescape(s, e, scratch);
            s = 0;
            src = scratch;
        }
        while (s < e) {
            int w = spaceAt(src, s, e);
            if (w == 0) break;
            s += w;
        }
        while (e > s) {
            int q = e - 1;
            while (q > s && (src[q] & 0xC0) == 0x80) q--;
            int w = spaceAt(src, q, e);
            if (w == 0 || q + w != e) break;
            e = q;
        }
        outBuf = src;
        outOff = s;
        outLen = e - s;
        int cp = 0;
        for (int i = s; i < e; i++) if ((src[i] & 0xC0) != 0x80) cp++;
        codePoints = cp;
        return OK;
    }

    private void ws() {
        while (p < end) {
            byte c = buf[p];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p++;
            else break;
        }
    }

    /** Length in bytes of the Python str.isspace() code point starting at i, or 0. */
    private static int spaceAt(byte[] b, int i, int e) {
        int c = b[i] & 0xFF;
        if (c == 0x20 || (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x1F)) return 1;
        if (c == 0xC2 && i + 1 < e) {
            int d = b[i + 1] & 0xFF;
            return d == 0x85 || d == 0xA0 ? 2 : 0;
        }
        if (c == 0xE1 && i + 2 < e) return (b[i + 1] & 0xFF) == 0x9A && (b[i + 2] & 0xFF) == 0x80 ? 3 : 0;
        if (c == 0xE2 && i + 2 < e) {
            int d = b[i + 1] & 0xFF, f = b[i + 2] & 0xFF;
            if (d == 0x80 && ((f >= 0x80 && f <= 0x8A) || f == 0xA8 || f == 0xA9 || f == 0xAF)) return 3;
            if (d == 0x81 && f == 0x9F) return 3;
            return 0;
        }
        if (c == 0xE3 && i + 2 < e) return (b[i + 1] & 0xFF) == 0x80 && (b[i + 2] & 0xFF) == 0x80 ? 3 : 0;
        return 0;
    }

    /** p is at the opening quote; validates the string, leaves p after the closing quote, sets strEsc. */
    private boolean string() {
        p++;
        boolean esc = false;
        while (p < end) {
            int c = buf[p];
            if (c == '"') { p++; strEsc = esc; return true; }
            if (c >= 0 && c < 0x20) return false;
            if (c == '\\') {
                esc = true;
                if (++p >= end) return false;
                switch (buf[p]) {
                    case '"': case '\\': case '/': case 'b': case 'f': case 'n': case 'r': case 't': break;
                    case 'u':
                        if (p + 4 >= end) return false;
                        for (int i = 1; i <= 4; i++) if (hex(buf[p + i]) < 0) return false;
                        p += 4;
                        break;
                    default: return false;
                }
            }
            p++;
        }
        return false;
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private boolean keyIsBody(int s, int e, boolean esc) {
        if (!esc) {
            return e - s == 4 && buf[s] == 'b' && buf[s + 1] == 'o' && buf[s + 2] == 'd' && buf[s + 3] == 'y';
        }
        if (e - s > 24) return false; // 4 chars need at most 4 x 6 raw bytes
        byte[] tmp = new byte[24]; // rare path (escaped key), allocation accepted
        int n = unescape(s, e, tmp);
        return n == 4 && tmp[0] == 'b' && tmp[1] == 'o' && tmp[2] == 'd' && tmp[3] == 'y';
    }

    /** Decodes the validated escaped string [s,e) as UTF-8 into dst from 0; returns the length. */
    private int unescape(int s, int e, byte[] dst) {
        int o = 0;
        int i = s;
        while (i < e) {
            byte c = buf[i];
            if (c != '\\') { dst[o++] = c; i++; continue; }
            byte n = buf[i + 1];
            i += 2;
            switch (n) {
                case 'b': dst[o++] = '\b'; break;
                case 'f': dst[o++] = '\f'; break;
                case 'n': dst[o++] = '\n'; break;
                case 'r': dst[o++] = '\r'; break;
                case 't': dst[o++] = '\t'; break;
                case 'u': {
                    int cp = hex4(i);
                    i += 4;
                    if (cp >= 0xD800 && cp <= 0xDBFF && i + 5 < e && buf[i] == '\\' && buf[i + 1] == 'u') {
                        int lo = hex4(i + 2);
                        if (lo >= 0xDC00 && lo <= 0xDFFF) {
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                            i += 6;
                        } else {
                            cp = 0xFFFD;
                        }
                    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
                        cp = 0xFFFD; // lone surrogate: not encodable as UTF-8
                    }
                    if (cp < 0x80) {
                        dst[o++] = (byte) cp;
                    } else if (cp < 0x800) {
                        dst[o++] = (byte) (0xC0 | cp >> 6);
                        dst[o++] = (byte) (0x80 | cp & 0x3F);
                    } else if (cp < 0x10000) {
                        dst[o++] = (byte) (0xE0 | cp >> 12);
                        dst[o++] = (byte) (0x80 | cp >> 6 & 0x3F);
                        dst[o++] = (byte) (0x80 | cp & 0x3F);
                    } else {
                        dst[o++] = (byte) (0xF0 | cp >> 18);
                        dst[o++] = (byte) (0x80 | cp >> 12 & 0x3F);
                        dst[o++] = (byte) (0x80 | cp >> 6 & 0x3F);
                        dst[o++] = (byte) (0x80 | cp & 0x3F);
                    }
                    break;
                }
                default: dst[o++] = n; // " \ /
            }
        }
        return o;
    }

    private int hex4(int i) {
        return hex(buf[i]) << 12 | hex(buf[i + 1]) << 8 | hex(buf[i + 2]) << 4 | hex(buf[i + 3]);
    }

    /** Skips one value structurally. */
    private boolean value(int depth) {
        if (p >= end) return false;
        byte c = buf[p];
        switch (c) {
            case '"': return string();
            case '{': return object(depth);
            case '[': return array(depth);
            case 't': return lit(TRUE);
            case 'f': return lit(FALSE);
            case 'n': return lit(NULL);
            default: return number();
        }
    }

    private static final byte[] TRUE = {'t', 'r', 'u', 'e'}, FALSE = {'f', 'a', 'l', 's', 'e'}, NULL = {'n', 'u', 'l', 'l'};

    private boolean lit(byte[] l) {
        if (p + l.length > end) return false;
        for (int i = 0; i < l.length; i++) if (buf[p + i] != l[i]) return false;
        p += l.length;
        return true;
    }

    private boolean digits() {
        int s = p;
        while (p < end && buf[p] >= '0' && buf[p] <= '9') p++;
        return p > s;
    }

    private boolean number() {
        if (p < end && buf[p] == '-') p++;
        if (p >= end) return false;
        if (buf[p] == '0') p++;
        else if (!digits()) return false;
        if (p < end && buf[p] == '.') {
            p++;
            if (!digits()) return false;
        }
        if (p < end && (buf[p] == 'e' || buf[p] == 'E')) {
            p++;
            if (p < end && (buf[p] == '+' || buf[p] == '-')) p++;
            if (!digits()) return false;
        }
        return true;
    }

    private boolean array(int depth) {
        if (depth >= MAX_DEPTH) return false;
        p++;
        ws();
        if (p < end && buf[p] == ']') { p++; return true; }
        while (true) {
            ws();
            if (!value(depth + 1)) return false;
            ws();
            if (p >= end) return false;
            if (buf[p] == ',') { p++; continue; }
            if (buf[p] == ']') { p++; return true; }
            return false;
        }
    }

    private boolean object(int depth) {
        if (depth >= MAX_DEPTH) return false;
        p++;
        ws();
        if (p < end && buf[p] == '}') { p++; return true; }
        while (true) {
            ws();
            if (p >= end || buf[p] != '"' || !string()) return false;
            ws();
            if (p >= end || buf[p] != ':') return false;
            p++;
            ws();
            if (!value(depth + 1)) return false;
            ws();
            if (p >= end) return false;
            if (buf[p] == ',') { p++; continue; }
            if (buf[p] == '}') { p++; return true; }
            return false;
        }
    }
}
