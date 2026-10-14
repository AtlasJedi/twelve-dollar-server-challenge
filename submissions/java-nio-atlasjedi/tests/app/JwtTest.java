package app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class JwtTest {
    static final byte[] SECRET = "twelve-dollar-challenge".getBytes(StandardCharsets.UTF_8);
    static final long NOW = 1_800_000_000L;
    static final String HDR = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    static int checks;

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String b64(String s) { return b64(s.getBytes(StandardCharsets.UTF_8)); }

    static String hmac(String data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        return b64(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    static String sign(String header, String payload) throws Exception {
        String hp = b64(header) + "." + b64(payload);
        return hp + "." + hmac(hp);
    }

    static String payload(String sub, String user, long exp) {
        StringBuilder sb = new StringBuilder("{");
        if (sub != null) sb.append("\"sub\":").append(sub).append(',');
        if (user != null) sb.append("\"username\":").append(user).append(',');
        sb.append("\"iat\":").append(NOW - 10);
        if (exp >= 0) sb.append(",\"exp\":").append(exp);
        return sb.append('}').toString();
    }

    static int run(Jwt j, String auth) {
        byte[] b = ("xx" + auth + "yy").getBytes(StandardCharsets.UTF_8);
        return j.verify(b, 2, b.length - 4, NOW);
    }

    static void eq(String name, long exp, long act) {
        checks++;
        if (exp != act) throw new AssertionError(name + ": expected " + exp + " got " + act);
    }

    static String user(Jwt j) { return new String(j.userBuf, j.userOff, j.userLen, StandardCharsets.UTF_8); }

    public static void main(String[] a) throws Exception {
        Jwt j = new Jwt(SECRET);
        String good = sign(HDR, payload("\"1\"", "\"golden_ember_1\"", NOW + 3600));

        eq("valid", Jwt.OK, run(j, "Bearer " + good));
        eq("valid uid", 1, j.userId);
        eq("valid user", 0, user(j).compareTo("golden_ember_1"));
        // reuse of the same instance must still work
        eq("valid again", Jwt.OK, run(j, "Bearer " + good));

        eq("no header", Jwt.MISSING, j.verify(new byte[0], 0, 0, NOW));
        eq("Basic", Jwt.MISSING, run(j, "Basic abc"));
        eq("Bearer abc", Jwt.INVALID, run(j, "Bearer abc"));
        eq("Bearer empty", Jwt.INVALID, run(j, "Bearer "));
        eq("bearer lowercase", Jwt.MISSING, run(j, "bearer " + good));

        String hp = b64(HDR) + "." + b64(payload("\"1\"", "\"golden_ember_1\"", NOW + 3600));
        eq("wrong sig", Jwt.INVALID, run(j, "Bearer " + hp + "." + b64(new byte[32])));
        eq("truncated sig", Jwt.INVALID, run(j, "Bearer " + good.substring(0, good.length() - 3)));
        eq("padded sig", Jwt.INVALID, run(j, "Bearer " + good + "="));
        eq("four segments", Jwt.INVALID, run(j, "Bearer " + good + ".x"));
        eq("two segments", Jwt.INVALID, run(j, "Bearer " + b64(HDR) + "." + b64("{}")));

        String nh = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + b64(payload("\"1\"", "\"golden_ember_1\"", NOW + 3600)) + ".";
        eq("alg none", Jwt.INVALID, run(j, "Bearer " + nh));
        eq("alg HS512 signed", Jwt.INVALID, run(j, "Bearer " + sign("{\"alg\":\"HS512\",\"typ\":\"JWT\"}", payload("\"1\"", "\"u\"", NOW + 3600))));
        eq("alg missing signed", Jwt.INVALID, run(j, "Bearer " + sign("{\"typ\":\"JWT\"}", payload("\"1\"", "\"u\"", NOW + 3600))));

        eq("expired", Jwt.INVALID, run(j, "Bearer " + sign(HDR, payload("\"1\"", "\"u\"", NOW - 1))));
        eq("exp == now", Jwt.INVALID, run(j, "Bearer " + sign(HDR, payload("\"1\"", "\"u\"", NOW))));
        eq("exp now+1", Jwt.OK, run(j, "Bearer " + sign(HDR, payload("\"1\"", "\"u\"", NOW + 1))));
        eq("exp missing", Jwt.INVALID, run(j, "Bearer " + sign(HDR, payload("\"1\"", "\"u\"", -1))));
        eq("exp fractional ok", Jwt.OK, run(j, "Bearer " + sign(HDR,
                "{\"sub\":\"1\",\"username\":\"u\",\"exp\":" + (NOW + 100) + ".75}")));
        eq("exp fractional expired", Jwt.INVALID, run(j, "Bearer " + sign(HDR,
                "{\"sub\":\"1\",\"username\":\"u\",\"exp\":" + (NOW - 5) + ".9}")));
        eq("exp before sub order", Jwt.OK, run(j, "Bearer " + sign(HDR,
                "{\"exp\":" + (NOW + 100) + ",\"username\":\"u\",\"sub\":\"77\"}")));
        eq("exp order uid", 77, j.userId);

        // INVALID (exp) takes precedence over BAD_PAYLOAD
        eq("expired + bad sub", Jwt.INVALID, run(j, "Bearer " + sign(HDR, payload("\"abc\"", "\"u\"", NOW - 1))));

        eq("sub abc", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload("\"abc\"", "\"u\"", NOW + 3600))));
        eq("sub 0", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload("\"0\"", "\"u\"", NOW + 3600))));
        eq("sub -1", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload("\"-1\"", "\"u\"", NOW + 3600))));
        eq("sub empty", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload("\"\"", "\"u\"", NOW + 3600))));
        eq("sub number", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload("1", "\"u\"", NOW + 3600))));
        eq("sub missing", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload(null, "\"u\"", NOW + 3600))));
        eq("no username", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload("\"1\"", null, NOW + 3600))));
        eq("username number", Jwt.BAD_PAYLOAD, run(j, "Bearer " + sign(HDR, payload("\"1\"", "42", NOW + 3600))));

        eq("big sub", Jwt.OK, run(j, "Bearer " + sign(HDR, payload("\"20000\"", "\"u\"", NOW + 3600))));
        eq("big sub uid", 20000, j.userId);

        // JSON escapes in username
        eq("escaped", Jwt.OK, run(j, "Bearer " + sign(HDR, payload("\"5\"", "\"a\\\"b\\\\c\\n\\u00e9\\ud83d\\ude00z\"", NOW + 3600))));
        eq("escaped user", 0, user(j).compareTo("a\"b\\c\né😀z"));
        // after an escaped one, a plain one must report plain
        eq("plain after escaped", Jwt.OK, run(j, "Bearer " + good));
        eq("plain after escaped user", 0, user(j).compareTo("golden_ember_1"));
        // raw UTF-8 username
        eq("utf8", Jwt.OK, run(j, "Bearer " + sign(HDR, payload("\"5\"", "\"zażółć\"", NOW + 3600))));
        eq("utf8 user", 0, user(j).compareTo("zażółć"));

        // seed token for user 1 (exp far in the future relative to NOW)
        String seed = Files.readString(Path.of("../../seed/tokens.json"));
        int i = seed.indexOf("\"token\":\"") + 9;
        String tok = seed.substring(i, seed.indexOf('"', i));
        eq("seed token", Jwt.OK, run(j, "Bearer " + tok));
        eq("seed uid", 1, j.userId);
        eq("seed user", 0, user(j).compareTo("golden_ember_1"));

        System.out.println("JwtTest OK (" + checks + " checks)");
    }
}
