package app;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;

/**
 * PGO training workload (build.sh only, never started by start.sh): runs reads, creates and likes through {@link Db}
 * on a throwaway copy of the seed DB (arg 1; if absent, a tiny schema-compatible DB is generated), then exits 0.
 */
public final class Train {
    private Train() { }

    private static final String SCHEMA = """
        CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE,
          created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')));
        CREATE TABLE posts (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id),
          body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 500),
          created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')));
        CREATE TABLE likes (user_id INTEGER NOT NULL REFERENCES users(id), post_id INTEGER NOT NULL REFERENCES posts(id),
          created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')), PRIMARY KEY (user_id, post_id));
        CREATE INDEX posts_created_at_id_idx ON posts (created_at DESC, id DESC);
        CREATE INDEX posts_user_id_idx ON posts (user_id);
        CREATE INDEX likes_post_id_idx ON likes (post_id);
        """;

    private static void tiny(Path file) {
        Sqlite s = Sqlite.open(file.toString());
        StringBuilder sql = new StringBuilder(SCHEMA).append("BEGIN;");
        for (int i = 1; i <= 300; i++) sql.append("INSERT INTO users (username) VALUES ('user").append(i).append("');");
        for (int i = 1; i <= 500; i++) {
            sql.append("INSERT INTO posts (user_id, body) VALUES (").append(1 + i % 300).append(", 'training post ")
                .append(i).append(" with some \"quoted\" text');");
        }
        s.exec(sql.append("COMMIT;").toString());
        s.close();
    }

    private static long max(Db db, String sql) {
        Sqlite.Stmt st = db.sqlite.prepare(sql);
        try {
            return st.step() ? st.colLong(0) : 1;
        } finally {
            st.reset();
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempFile("train", ".db");
        try {
            Path seed = args.length > 0 ? Path.of(args[0]) : null;
            if (seed != null && Files.isRegularFile(seed)) {
                Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.delete(tmp);
                tiny(tmp);
            }
            Db db = Db.open(tmp.toString());
            long posts = max(db, "SELECT max(id) FROM posts"), users = max(db, "SELECT max(id) FROM users");
            Random rnd = new Random(42);
            ByteBuffer out = ByteBuffer.allocateDirect(1 << 16);
            int feeds = 20_000, reads = 20_000, creates = 2_000, likes = 2_000;
            for (int i = 0; i < feeds; i++) {
                out.clear();
                db.feed(out);
            }
            for (int i = 0; i < reads; i++) {
                out.clear();
                db.post(1 + (long) (rnd.nextDouble() * posts), out);
            }
            byte[] body = "training body with \"quotes\" and unicode é中".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int writes = 0;
            for (int i = 0; i < creates + likes; i++) {
                if (i % 2 == 0) db.create(1 + (long) (rnd.nextDouble() * users), body, 0, body.length);
                else db.like(1 + (long) (rnd.nextDouble() * users), 1 + (long) (rnd.nextDouble() * posts));
                if (++writes % 50 == 0) {
                    db.commitTick();
                    db.afterTick();
                }
            }
            db.commitTick();
            db.afterTick();
            System.out.println("Train: feeds=" + feeds + " posts=" + reads + " creates=" + creates + " likes=" + likes);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + "-wal"));
            Files.deleteIfExists(Path.of(tmp + "-shm"));
        }
    }
}
