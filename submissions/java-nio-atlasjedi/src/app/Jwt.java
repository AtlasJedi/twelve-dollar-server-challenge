package app;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HS256 JWT verifier. Single-threaded, zero allocation per call: one reused Mac, hand-rolled base64url
 * decode into preallocated scratch, byte-level JSON scan of the top-level object only.
 */
final class Jwt {
    static final int OK = 0, MISSING = 1, INVALID = 2, BAD_PAYLOAD = 3;

    private static final int MAX_HDR = 512, MAX_PAYLOAD = 2048;
    private static final byte[] B64 = new byte[256];
    static {
        java.util.Arrays.fill(B64, (byte) -1);
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i = 0; i < 64; i++) B64[abc.charAt(i)] = (byte) i;
    }

    private static final int K_ALG = 0, K_SUB = 1, K_USER = 2, K_EXP = 3;

    private final Mac mac;
    private final byte[] hdr = new byte[MAX_HDR];
    private final byte[] pay = new byte[MAX_PAYLOAD];
    private final byte[] sig = new byte[32];
    private final byte[] calc = new byte[32];
    private final byte[] unesc = new byte[MAX_PAYLOAD];
    private final int[] vs = new int[4], ve = new int[4];

    long userId;
    int userOff, userLen;
    byte[] userBuf = pay;

    Jwt(byte[] secret) {
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** auth = raw Authorization header value bytes; fills userId and username span on OK. */
    int verify(byte[] buf, int off, int len, long nowEpochSec) {
        if (len < 7 || buf[off] != 'B' || buf[off + 1] != 'e' || buf[off + 2] != 'a' || buf[off + 3] != 'r'
                || buf[off + 4] != 'e' || buf[off + 5] != 'r' || buf[off + 6] != ' ') {
            return MISSING;
        }
        int s = off + 7, end = off + len;
        int d1 = -1, d2 = -1;
        for (int i = s; i < end; i++) {
            if (buf[i] == '.') {
                if (d1 < 0) d1 = i;
                else if (d2 < 0) d2 = i;
                else return INVALID;
            }
        }
        if (d1 < 0 || d2 < 0) return INVALID;

        int hl = b64decode(buf, s, d1, hdr, MAX_HDR);
        if (hl < 0) return INVALID;
        int pl = b64decode(buf, d1 + 1, d2, pay, MAX_PAYLOAD);
        if (pl < 0) return INVALID;
        if (b64decode(buf, d2 + 1, end, sig, 32) != 32) return INVALID;

        if (!scan(hdr, hl)) return INVALID;
        int a = vs[K_ALG];
        if (a < 0 || ve[K_ALG] - a != 7 || hdr[a] != '"' || hdr[a + 1] != 'H' || hdr[a + 2] != 'S' || hdr[a + 3] != '2'
                || hdr[a + 4] != '5' || hdr[a + 5] != '6' || hdr[a + 6] != '"') {
            return INVALID;
        }

        try {
            mac.reset();
            mac.update(buf, s, d2 - s);
            mac.doFinal(calc, 0);
        } catch (GeneralSecurityException e) {
            return INVALID;
        }
        if (!MessageDigest.isEqual(sig, calc)) return INVALID;

        if (!scan(pay, pl)) return INVALID;

        // exp: number, integer part compared with now
        int e = vs[K_EXP];
        if (e < 0) return INVALID;
        int p = e, ee = ve[K_EXP];
        if (pay[p] < '0' || pay[p] > '9') return INVALID;
        long exp = 0;
        while (p < ee && pay[p] >= '0' && pay[p] <= '9') {
            if (exp < 100_000_000_000_000_000L) exp = exp * 10 + (pay[p] - '0');
            p++;
        }
        if (p < ee) {
            if (pay[p] != '.') return INVALID;
            p++;
            if (p >= ee) return INVALID;
            while (p < ee && pay[p] >= '0' && pay[p] <= '9') p++;
            if (p != ee) return INVALID;
        }
        if (exp <= nowEpochSec) return INVALID;

        // sub: string of digits, positive
        int ss = vs[K_SUB];
        if (ss < 0 || pay[ss] != '"') return BAD_PAYLOAD;
        int se = ve[K_SUB] - 1; // closing quote
        int n = se - (ss + 1);
        if (n < 1 || n > 18) return BAD_PAYLOAD;
        long id = 0;
        for (int i = ss + 1; i < se; i++) {
            int c = pay[i] - '0';
            if (c < 0 || c > 9) return BAD_PAYLOAD;
            id = id * 10 + c;
        }
        if (id <= 0) return BAD_PAYLOAD;

        // username: string
        int us = vs[K_USER];
        if (us < 0 || pay[us] != '"') return BAD_PAYLOAD;
        int ue = ve[K_USER] - 1;
        boolean esc = false;
        for (int i = us + 1; i < ue; i++) {
            if (pay[i] == '\\') { esc = true; break; }
        }
        if (!esc) {
            userBuf = pay;
            userOff = us + 1;
            userLen = ue - us - 1;
        } else {
            int w = unescape(us + 1, ue);
            if (w < 0) return BAD_PAYLOAD;
            userBuf = unesc;
            userOff = 0;
            userLen = w;
        }
        userId = id;
        return OK;
    }

    private static int b64decode(byte[] in, int from, int to, byte[] out, int cap) {
        int n = to - from;
        if ((n & 3) == 1) return -1;
        int o = 0, i = from;
        int full = to - (n & 3);
        while (i < full) {
            int a = B64[in[i] & 0xff], b = B64[in[i + 1] & 0xff], c = B64[in[i + 2] & 0xff], d = B64[in[i + 3] & 0xff];
            if ((a | b | c | d) < 0 || o + 3 > cap) return -1;
            out[o] = (byte) (a << 2 | b >> 4);
            out[o + 1] = (byte) (b << 4 | c >> 2);
            out[o + 2] = (byte) (c << 6 | d);
            o += 3;
            i += 4;
        }
        int rem = n & 3;
        if (rem == 2) {
            int a = B64[in[i] & 0xff], b = B64[in[i + 1] & 0xff];
            if ((a | b) < 0 || o + 1 > cap) return -1;
            out[o++] = (byte) (a << 2 | b >> 4);
        } else if (rem == 3) {
            int a = B64[in[i] & 0xff], b = B64[in[i + 1] & 0xff], c = B64[in[i + 2] & 0xff];
            if ((a | b | c) < 0 || o + 2 > cap) return -1;
            out[o++] = (byte) (a << 2 | b >> 4);
            out[o++] = (byte) (b << 4 | c >> 2);
        }
        return o;
    }

    /** Scans the top-level JSON object of b[0..len), recording value spans for alg/sub/username/exp. */
    private boolean scan(byte[] b, int len) {
        vs[0] = vs[1] = vs[2] = vs[3] = -1;
        int i = ws(b, 0, len);
        if (i >= len || b[i] != '{') return false;
        i = ws(b, i + 1, len);
        if (i < len && b[i] == '}') return true;
        while (true) {
            if (i >= len || b[i] != '"') return false;
            int ks = i + 1;
            i = skipString(b, i, len);
            if (i < 0) return false;
            int kl = i - 1 - ks;
            int key = -1;
            switch (kl) {
                case 3 -> {
                    if (b[ks] == 'a' && b[ks + 1] == 'l' && b[ks + 2] == 'g') key = K_ALG;
                    else if (b[ks] == 's' && b[ks + 1] == 'u' && b[ks + 2] == 'b') key = K_SUB;
                    else if (b[ks] == 'e' && b[ks + 1] == 'x' && b[ks + 2] == 'p') key = K_EXP;
                }
                case 8 -> {
                    if (b[ks] == 'u' && b[ks + 1] == 's' && b[ks + 2] == 'e' && b[ks + 3] == 'r' && b[ks + 4] == 'n'
                            && b[ks + 5] == 'a' && b[ks + 6] == 'm' && b[ks + 7] == 'e') key = K_USER;
                }
                default -> { }
            }
            i = ws(b, i, len);
            if (i >= len || b[i] != ':') return false;
            i = ws(b, i + 1, len);
            if (i >= len) return false;
            int v = i;
            i = skipValue(b, i, len);
            if (i < 0) return false;
            if (key >= 0) { vs[key] = v; ve[key] = i; }
            i = ws(b, i, len);
            if (i >= len) return false;
            if (b[i] == ',') { i = ws(b, i + 1, len); continue; }
            return b[i] == '}';
        }
    }

    private static int ws(byte[] b, int i, int len) {
        while (i < len && (b[i] == ' ' || b[i] == '\n' || b[i] == '\r' || b[i] == '\t')) i++;
        return i;
    }

    /** i at opening quote; returns index after the closing quote or -1. */
    private static int skipString(byte[] b, int i, int len) {
        i++;
        while (i < len) {
            byte c = b[i];
            if (c == '"') return i + 1;
            if (c == '\\') i++;
            i++;
        }
        return -1;
    }

    private static int skipValue(byte[] b, int i, int len) {
        byte c = b[i];
        if (c == '"') return skipString(b, i, len);
        if (c == '{' || c == '[') {
            int depth = 0;
            while (i < len) {
                c = b[i];
                if (c == '"') {
                    i = skipString(b, i, len);
                    if (i < 0) return -1;
                    continue;
                }
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') { if (--depth == 0) return i + 1; }
                i++;
            }
            return -1;
        }
        int st = i;
        while (i < len && b[i] != ',' && b[i] != '}' && b[i] != ']' && b[i] != ' ' && b[i] != '\n'
                && b[i] != '\r' && b[i] != '\t') i++;
        return i == st ? -1 : i;
    }

    private static int hex4(byte[] b, int i) {
        int v = 0;
        for (int k = 0; k < 4; k++) {
            int c = b[i + k], d;
            if (c >= '0' && c <= '9') d = c - '0';
            else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
            else return -1;
            v = v << 4 | d;
        }
        return v;
    }

    /** JSON-unescape pay[from..to) as UTF-8 into unesc; returns length or -1. */
    private int unescape(int from, int to) {
        int w = 0, i = from;
        while (i < to) {
            byte c = pay[i++];
            if (c != '\\') { unesc[w++] = c; continue; }
            if (i >= to) return -1;
            c = pay[i++];
            switch (c) {
                case '"', '\\', '/' -> unesc[w++] = c;
                case 'b' -> unesc[w++] = '\b';
                case 'f' -> unesc[w++] = '\f';
                case 'n' -> unesc[w++] = '\n';
                case 'r' -> unesc[w++] = '\r';
                case 't' -> unesc[w++] = '\t';
                case 'u' -> {
                    if (i + 4 > to) return -1;
                    int cp = hex4(pay, i);
                    if (cp < 0) return -1;
                    i += 4;
                    if (cp >= 0xD800 && cp < 0xDC00) {
                        int lo;
                        if (i + 6 <= to && pay[i] == '\\' && pay[i + 1] == 'u' && (lo = hex4(pay, i + 2)) >= 0xDC00 && lo < 0xE000) {
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                            i += 6;
                        } else {
                            cp = 0xFFFD;
                        }
                    } else if (cp >= 0xDC00 && cp < 0xE000) {
                        cp = 0xFFFD;
                    }
                    if (cp < 0x80) unesc[w++] = (byte) cp;
                    else if (cp < 0x800) {
                        unesc[w++] = (byte) (0xC0 | cp >> 6);
                        unesc[w++] = (byte) (0x80 | cp & 0x3F);
                    } else if (cp < 0x10000) {
                        unesc[w++] = (byte) (0xE0 | cp >> 12);
                        unesc[w++] = (byte) (0x80 | cp >> 6 & 0x3F);
                        unesc[w++] = (byte) (0x80 | cp & 0x3F);
                    } else {
                        unesc[w++] = (byte) (0xF0 | cp >> 18);
                        unesc[w++] = (byte) (0x80 | cp >> 12 & 0x3F);
                        unesc[w++] = (byte) (0x80 | cp >> 6 & 0x3F);
                        unesc[w++] = (byte) (0x80 | cp & 0x3F);
                    }
                }
                default -> { return -1; }
            }
        }
        return w;
    }
}
