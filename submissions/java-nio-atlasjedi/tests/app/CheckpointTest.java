package app;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Task 11: checkpoints run on the loop tick, never inside a request; the WAL stops growing afterwards. */
public class CheckpointTest {
    public static void main(String[] args) throws Exception {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile("ckpt", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        String t = tmp.toString();
        Db db = Db.open(t);
        byte[] body = "checkpoint test post body".getBytes(StandardCharsets.UTF_8);
        long base = Files.size(Path.of(t + "-wal"));
        for (int i = 0; i < 1500; i++) { db.create(1, body, 0, body.length); check(db.commitTick(), "commit"); } // one tick per write
        long grown = Files.size(Path.of(t + "-wal"));
        System.out.println("wal base=" + base + " grown=" + grown);
        check(grown > base + 1_000_000, "WAL did not grow inside the batch (checkpoint ran on the request path?)");
        check(db.writesSinceCheckpoint == 1500, "writesSinceCheckpoint=" + db.writesSinceCheckpoint);
        db.afterTick();
        check(db.writesSinceCheckpoint == 0, "counter not reset");
        for (int i = 0; i < 1500; i++) { db.create(1, body, 0, body.length); check(db.commitTick(), "commit"); }
        long after = Files.size(Path.of(t + "-wal"));
        System.out.println("wal after=" + after);
        check(after <= grown + 65536, "WAL kept growing after the tick checkpoint: " + after + " vs " + grown);
        db.afterTick();
        // pnLog is the WAL's total frame count, which only drops once a write restarts the fully checkpointed WAL
        db.create(1, body, 0, body.length);
        check(db.commitTick(), "commit");
        int frames = db.sqlite.walCheckpoint(0);
        System.out.println("frames left=" + frames);
        check(frames < 10, "WAL not restarted after a full PASSIVE checkpoint: " + frames);
        System.out.println("CheckpointTest OK");
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
