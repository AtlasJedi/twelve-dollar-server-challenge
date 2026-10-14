package app;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** Task 18 outcome: Db.create is INSERT + SELECT created_at (RETURNING lost the A/B). Guards format, ids and speed. */
public class CreateAbTest {
    static final java.util.regex.Pattern TS = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

    static long median(Db db, int n) {
        byte[] b = "hello world, a typical post body of moderate length".getBytes(StandardCharsets.UTF_8);
        long[] t = new long[n];
        long prev = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            long id = db.create(1, b, 0, b.length);
            t[i] = System.nanoTime() - t0;
            if (id <= prev) throw new AssertionError("ids not increasing: " + prev + " -> " + id);
            prev = id;
            String ts = new String(db.createdAt, 0, db.createdLen, StandardCharsets.US_ASCII);
            if (!TS.matcher(ts).matches()) throw new AssertionError("created_at: " + ts);
            if (i % 100 == 99 && !db.commitTick()) throw new AssertionError("commit failed");
        }
        db.commitTick();
        Arrays.sort(t);
        return t[n / 2];
    }

    public static void main(String[] args) throws Exception {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile("feed-createab", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        Db db = Db.open(tmp.toString());
        median(db, 2000);
        long m = median(db, 10000);
        System.out.println("create median ns: " + m);
        if (m >= 200_000) throw new AssertionError("create median " + m + " ns >= 200 us");
        System.out.println("CreateAbTest OK");
    }
}
