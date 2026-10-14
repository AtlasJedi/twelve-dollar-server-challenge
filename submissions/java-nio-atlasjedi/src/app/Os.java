package app;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/** Thin FFM bindings for process limits. */
final class Os {
    private Os() { }

    /** Soft RLIMIT_NOFILE, or -1 when it cannot be read. Never throws. */
    static long nofileSoftLimit() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            MemorySegment sym = linker.defaultLookup().find("getrlimit").orElse(null);
            if (sym == null) return -1;
            MethodHandle h = linker.downcallHandle(sym,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            int resource = System.getProperty("os.name", "").toLowerCase().contains("mac") ? 8 : 7;
            MemorySegment rl = arena.allocate(16, 8); // struct rlimit { rlim_t cur; rlim_t max; }
            int rc = (int) h.invokeExact(resource, rl);
            if (rc != 0) return -1;
            return rl.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            return -1;
        }
    }
}
