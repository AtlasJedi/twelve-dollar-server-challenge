package app;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;

/** Task 1: the SQLite shared library was built and is loadable through FFM. */
public class BuildTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Throwable {
        String ext = System.getProperty("os.name").toLowerCase().contains("mac") ? "dylib" : "so";
        Path lib = Path.of(System.getProperty("java.library.path"), "libsqlite3." + ext).toAbsolutePath();
        check(Files.isRegularFile(lib), "missing " + lib + " (run build.sh)");

        System.load(lib.toString());

        SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());
        MethodHandle version = Linker.nativeLinker().downcallHandle(
            lookup.find("sqlite3_libversion").orElseThrow(() -> new AssertionError("sqlite3_libversion not found")),
            FunctionDescriptor.of(ValueLayout.ADDRESS));
        MemorySegment p = (MemorySegment) version.invokeExact();
        String v = p.reinterpret(32).getString(0);
        check(v.startsWith("3.54"), "unexpected sqlite version " + v);
        System.out.println("BuildTest ok (sqlite " + v + ")");
    }
}
