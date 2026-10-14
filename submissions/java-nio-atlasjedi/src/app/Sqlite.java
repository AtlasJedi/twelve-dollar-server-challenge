package app;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/** Thin Panama FFM binding to libsqlite3. Single-threaded: one event-loop thread owns the connection. */
final class Sqlite implements AutoCloseable {
    private static final int SQLITE_ROW = 100, SQLITE_DONE = 101;
    private static final int OPEN_READWRITE = 0x2;
    private static final MemorySegment SQLITE_TRANSIENT = MemorySegment.ofAddress(-1L);

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = loadLib();

    private static final MethodHandle OPEN = h("sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE = h("sqlite3_close", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle EXEC = h("sqlite3_exec", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle ERRMSG = h("sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PREPARE = h("sqlite3_prepare_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FINALIZE = h("sqlite3_finalize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle BIND_INT64 = h("sqlite3_bind_int64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
    // critical: allows the heap segment of a byte[] to be passed directly (no copy, no re-encoding).
    private static final MethodHandle BIND_TEXT = LINKER.downcallHandle(sym("sqlite3_bind_text"),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), Linker.Option.critical(true));
    private static final MethodHandle STEP = h("sqlite3_step", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle COL_INT64 = h("sqlite3_column_int64", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle COL_TEXT = h("sqlite3_column_text", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle COL_BYTES = h("sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle RESET = h("sqlite3_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CHANGES = h("sqlite3_changes", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle LAST_ROWID = h("sqlite3_last_insert_rowid", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle WAL_CHECKPOINT = h("sqlite3_wal_checkpoint_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle LIBVERSION = h("sqlite3_libversion", FunctionDescriptor.of(ADDRESS));

    private static SymbolLookup loadLib() {
        String ext = System.getProperty("os.name").toLowerCase().contains("mac") ? "dylib" : "so";
        String dir = System.getProperty("java.library.path", "bin");
        for (String d : dir.split(java.io.File.pathSeparator)) {
            Path p = Path.of(d, "libsqlite3." + ext).toAbsolutePath();
            if (Files.isRegularFile(p)) return SymbolLookup.libraryLookup(p, Arena.global());
        }
        Path p = Path.of("bin", "libsqlite3." + ext).toAbsolutePath();
        if (Files.isRegularFile(p)) return SymbolLookup.libraryLookup(p, Arena.global());
        throw new IllegalStateException("libsqlite3." + ext + " not found (run build.sh)");
    }

    private static MemorySegment sym(String name) {
        return LOOKUP.find(name).orElseThrow(() -> new IllegalStateException("missing symbol " + name));
    }

    private static MethodHandle h(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(sym(name), fd);
    }

    static String libVersion() {
        try {
            return ((MemorySegment) LIBVERSION.invokeExact()).reinterpret(32).getString(0);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private final MemorySegment db;

    private Sqlite(MemorySegment db) {
        this.db = db;
    }

    private String errmsg() {
        try {
            return ((MemorySegment) ERRMSG.invokeExact(db)).reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    static Sqlite open(String path) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int rc = (int) OPEN.invokeExact(a.allocateFrom(path), out, OPEN_READWRITE, MemorySegment.NULL);
            MemorySegment handle = out.get(ADDRESS, 0);
            Sqlite s = new Sqlite(handle);
            if (rc != 0) {
                String m = handle.equals(MemorySegment.NULL) ? "rc=" + rc : s.errmsg();
                if (!handle.equals(MemorySegment.NULL)) { int ignored = (int) CLOSE.invokeExact(handle); }
                throw new IllegalStateException("sqlite3_open_v2 failed: " + m);
            }
            return s;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    void exec(String sql) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) EXEC.invokeExact(db, a.allocateFrom(sql), MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            if (rc != 0) throw new IllegalStateException("sqlite3_exec rc=" + rc + ": " + errmsg());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    Stmt prepare(String sql) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int rc = (int) PREPARE.invokeExact(db, a.allocateFrom(sql), -1, out, MemorySegment.NULL);
            if (rc != 0) throw new IllegalStateException("sqlite3_prepare_v2 rc=" + rc + ": " + errmsg());
            return new Stmt(out.get(ADDRESS, 0));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    int changes() {
        try {
            return (int) CHANGES.invokeExact(db);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    long lastInsertRowid() {
        try {
            return (long) LAST_ROWID.invokeExact(db);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /** PASSIVE=0, FULL=1, RESTART=2, TRUNCATE=3. */
    void walCheckpoint(int mode) {
        try {
            int rc = (int) WAL_CHECKPOINT.invokeExact(db, MemorySegment.NULL, mode, MemorySegment.NULL, MemorySegment.NULL);
            if (rc != 0) throw new IllegalStateException("sqlite3_wal_checkpoint_v2 rc=" + rc + ": " + errmsg());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    @Override
    public void close() {
        try {
            int rc = (int) CLOSE.invokeExact(db);
            if (rc != 0) throw new IllegalStateException("sqlite3_close rc=" + rc);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    final class Stmt {
        private final MemorySegment st;

        private Stmt(MemorySegment st) {
            this.st = st;
        }

        private void check(int rc, String what) {
            if (rc != 0) throw new IllegalStateException(what + " rc=" + rc + ": " + errmsg());
        }

        void bindLong(int i, long v) {
            try {
                check((int) BIND_INT64.invokeExact(st, i, v), "bind_int64");
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        /** Binds utf8[off, off+len) as text; SQLite copies it (SQLITE_TRANSIENT). */
        void bindText(int i, byte[] utf8, int off, int len) {
            try {
                MemorySegment seg = MemorySegment.ofArray(utf8);
                check((int) BIND_TEXT.invokeExact(st, i, seg.asSlice(off, len), len, SQLITE_TRANSIENT), "bind_text");
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        /** true = ROW, false = DONE; anything else throws. */
        boolean step() {
            try {
                int rc = (int) STEP.invokeExact(st);
                if (rc == SQLITE_ROW) return true;
                if (rc == SQLITE_DONE) return false;
                throw new IllegalStateException("step rc=" + rc + ": " + errmsg());
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        long colLong(int c) {
            try {
                return (long) COL_INT64.invokeExact(st, c);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        int colBytes(int c) {
            try {
                return (int) COL_BYTES.invokeExact(st, c);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        /** UTF-8 bytes of the column, valid until the next step/reset. Call before colBytes is not required: sizes via colBytes. */
        MemorySegment colText(int c) {
            try {
                MemorySegment p = (MemorySegment) COL_TEXT.invokeExact(st, c);
                int n = (int) COL_BYTES.invokeExact(st, c);
                return p.reinterpret(n + 1L);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        /** Copies the column's UTF-8 bytes into dst at its position and advances the position. */
        void copyText(int c, ByteBuffer dst) {
            try {
                MemorySegment p = (MemorySegment) COL_TEXT.invokeExact(st, c);
                int n = (int) COL_BYTES.invokeExact(st, c);
                if (n > dst.remaining()) throw new java.nio.BufferOverflowException();
                MemorySegment.copy(p.reinterpret(n), 0, MemorySegment.ofBuffer(dst), 0, n);
                dst.position(dst.position() + n);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        void reset() {
            try {
                // rc is the previous step's error, already reported by step(); ignoring keeps the statement reusable.
                int ignored = (int) RESET.invokeExact(st);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }

        void finalizeStmt() {
            try {
                int rc = (int) FINALIZE.invokeExact(st);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }
    }
}
