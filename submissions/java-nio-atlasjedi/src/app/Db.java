package app;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** One connection (EXCLUSIVE lock, event-loop thread only), pragmas, prepared statements, typed read methods. */
final class Db {
    private static final String POST_COLS =
        "SELECT p.id, p.body, p.created_at, u.username, (SELECT count(*) FROM likes l WHERE l.post_id = p.id)"
        + " FROM posts p JOIN users u ON u.id = p.user_id";

    private static final byte[] FEED_PRE = ascii("{\"posts\":["), FEED_POST = ascii("]}"), POST_PRE = ascii("{\"post\":"),
        K_ID = ascii("{\"id\":"), K_BODY = ascii(",\"body\":"), K_AT = ascii(",\"created_at\":\""),
        K_AUTHOR = ascii("\",\"author\":"), K_LIKES = ascii(",\"like_count\":");

    /** Writes between placeholder checkpoints (element 11 replaces the policy). */
    static final int CHECKPOINT_EVERY = 1000;

    final Sqlite sqlite;
    final Sqlite.Stmt feed, post, create, like, exists, health;
    /** Committed writes since open; Tasks 9-10 call {@link #wrote()} after each commit. */
    long writes;

    private Db(Sqlite sqlite) {
        this.sqlite = sqlite;
        feed = sqlite.prepare(POST_COLS + " ORDER BY p.created_at DESC, p.id DESC LIMIT 20");
        post = sqlite.prepare(POST_COLS + " WHERE p.id = ?");
        create = sqlite.prepare("INSERT INTO posts (user_id, body) VALUES (?, ?) RETURNING id, created_at");
        like = sqlite.prepare("INSERT INTO likes (user_id, post_id) VALUES (?, ?) ON CONFLICT (user_id, post_id) DO NOTHING");
        exists = sqlite.prepare("SELECT 1 FROM posts WHERE id = ?");
        health = sqlite.prepare("SELECT 1");
    }

    static Db open(String path) {
        Sqlite s = Sqlite.open(path);
        s.exec("PRAGMA journal_mode=WAL");
        s.exec("PRAGMA synchronous=NORMAL");
        s.exec("PRAGMA locking_mode=EXCLUSIVE");
        s.exec("PRAGMA mmap_size=1073741824");
        s.exec("PRAGMA cache_size=-16384");
        s.exec("PRAGMA foreign_keys=ON");
        s.exec("PRAGMA wal_autocheckpoint=0");
        return new Db(s);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** created_at of the last {@link #create}: ASCII bytes [0, createdLen). */
    final byte[] createdAt = new byte[32];
    int createdLen;
    private final ByteBuffer createdBuf = ByteBuffer.wrap(createdAt);

    /**
     * Inserts the post and returns its id. RETURNING hands back its row on the first step, but the autocommit
     * transaction only commits when the statement runs to DONE, so the second step is what makes this durable.
     */
    long create(long userId, byte[] body, int off, int len) {
        try {
            create.bindLong(1, userId);
            create.bindText(2, body, off, len);
            if (!create.step()) throw new IllegalStateException("INSERT RETURNING gave no row");
            long id = create.colLong(0);
            createdBuf.clear();
            create.copyText(1, createdBuf);
            createdLen = createdBuf.position();
            if (create.step()) throw new IllegalStateException("INSERT RETURNING gave a second row");
            wrote();
            return id;
        } finally {
            create.reset();
        }
    }

    /**
     * Likes a post: 1 inserted, 0 already liked, -1 no such post. changes() is 0 both for "already liked" and for
     * "no such post", so existence decides; with foreign_keys=ON the insert on a missing post fails with a FOREIGN KEY
     * constraint, which is swallowed (and only that failure).
     */
    int like(long userId, long postId) {
        boolean missing = false;
        try {
            like.bindLong(1, userId);
            like.bindLong(2, postId);
            like.step();
            if (sqlite.changes() == 1) {
                wrote();
                return 1;
            }
        } catch (IllegalStateException e) {
            String m = e.getMessage();
            if (m == null || !m.contains("FOREIGN KEY")) throw e;
            missing = true;
        } finally {
            like.reset();
        }
        if (missing) return -1;
        try {
            exists.bindLong(1, postId);
            return exists.step() ? 0 : -1;
        } finally {
            exists.reset();
        }
    }

    /** Placeholder checkpoint policy: PASSIVE every CHECKPOINT_EVERY writes. */
    void wrote() {
        if (++writes % CHECKPOINT_EVERY == 0) sqlite.walCheckpoint(0);
    }

    /** True if SELECT 1 returns a row. */
    boolean health() {
        try {
            return health.step();
        } finally {
            health.reset();
        }
    }

    /** Writes {"posts":[...20 objects...]} into body. */
    void feed(ByteBuffer body) {
        try {
            body.put(FEED_PRE);
            boolean first = true;
            while (feed.step()) {
                if (!first) body.put((byte) ',');
                first = false;
                object(feed, body);
            }
            body.put(FEED_POST);
        } finally {
            feed.reset();
        }
    }

    /** Writes {"post":{...}} into body; false (body untouched past its start) when the id does not exist. */
    boolean post(long id, ByteBuffer body) {
        try {
            post.bindLong(1, id);
            if (!post.step()) return false;
            body.put(POST_PRE);
            object(post, body);
            body.put((byte) '}');
            return true;
        } finally {
            post.reset();
        }
    }

    /** Post object straight from the current row: id, body, created_at (plain ASCII, copied raw), author, like_count. */
    private static void object(Sqlite.Stmt st, ByteBuffer b) {
        b.put(K_ID);
        Resp.num(b, st.colLong(0));
        b.put(K_BODY);
        Resp.str(b, st.colText(1), st.colBytes(1));
        b.put(K_AT);
        st.copyText(2, b);
        b.put(K_AUTHOR);
        Resp.str(b, st.colText(3), st.colBytes(3));
        b.put(K_LIKES);
        Resp.num(b, st.colLong(4));
        b.put((byte) '}');
    }
}
