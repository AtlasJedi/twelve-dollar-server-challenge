package app;

import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() { }

    /** Opens the DB and builds the full wiring; the caller runs {@code server.run()} (on a thread in tests). */
    static Server start(String dbPath, byte[] secret, String host, int port) throws java.io.IOException {
        Sqlite db = Sqlite.open(dbPath);
        Router router = new Router(new Handlers(db), new Jwt(secret));
        return new Server(host, port, 4096, router);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }

    public static void main(String[] args) throws Exception {
        String db = env("SQLITE_PATH", "seed/feed.db");
        String secret = env("JWT_SECRET", "twelve-dollar-challenge");
        String host = env("HOST", "127.0.0.1");
        int port = Integer.parseInt(env("PORT", "3000"));
        start(db, secret.getBytes(StandardCharsets.UTF_8), host, port).run();
    }
}
