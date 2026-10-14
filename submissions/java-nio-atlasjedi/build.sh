#!/usr/bin/env bash
# Builds the app as a normal user: SQLite from the pinned amalgamation, then the Java sources.
set -euo pipefail
cd "$(dirname "$0")"

URL=https://www.sqlite.org/2026/sqlite-amalgamation-3540000.zip
SHA3=7b670a62fdfbd672b75fef004cb703c8a3e87d3a5cc7d675b4a08337004a2d93
ZIP=build/sqlite-amalgamation-3540000.zip

case "$(uname -s)" in Darwin) EXT=dylib ;; *) EXT=so ;; esac
if [ -x /opt/jdk-25/bin/javac ]; then JAVAC=/opt/jdk-25/bin/javac; else JAVAC=javac; fi

mkdir -p build bin
# Download only if missing or the hash does not match; always verify before use.
sha3() { openssl dgst -sha3-256 "$1" | awk '{print $NF}'; }
if [ ! -f "$ZIP" ] || [ "$(sha3 "$ZIP")" != "$SHA3" ]; then
  curl -fsSL -o "$ZIP" "$URL"
  [ "$(sha3 "$ZIP")" = "$SHA3" ] || { echo "sqlite zip sha3-256 mismatch" >&2; exit 1; }
fi
unzip -qo "$ZIP" -d build

# SQLite as a single-threaded shared library (one event-loop thread owns the connection).
# Linux + gcc: profile-guided build (compile -fprofile-generate, run app.Train, recompile -fprofile-use).
# PGO=0 forces the plain -O2 build; any PGO failure falls back to it, so the build always yields a library.
SRC=build/sqlite-amalgamation-3540000/sqlite3.c
LIB="bin/libsqlite3.$EXT"
DEFS="-DSQLITE_THREADSAFE=0 -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1 -DSQLITE_DEFAULT_MEMSTATUS=0
  -DSQLITE_OMIT_DEPRECATED -DSQLITE_OMIT_SHARED_CACHE -DSQLITE_OMIT_PROGRESS_CALLBACK
  -DSQLITE_OMIT_DECLTYPE -DSQLITE_DQS=0 -DSQLITE_MAX_EXPR_DEPTH=0 -DSQLITE_USE_ALLOCA
  -DSQLITE_LIKE_DOESNT_MATCH_BLOBS -DSQLITE_OMIT_LOAD_EXTENSION"
# shellcheck disable=SC2086
cc_sqlite() { cc -O2 -fPIC -shared -o "$LIB" "$SRC" $DEFS "$@" -lm; }
compile_java() { rm -rf bin/classes; "$JAVAC" --release 25 -d bin/classes $(find src -name '*.java'); }

pgo_build() {
  local seed=../../seed/feed.db
  rm -rf build/pgo; mkdir -p build/pgo
  cc_sqlite -fprofile-generate -fprofile-dir=build/pgo || { echo "instrumented compile failed"; return 1; }
  compile_java || { echo "java compile failed"; return 1; }
  local java=java; [ -x /opt/jdk-25/bin/java ] && java=/opt/jdk-25/bin/java
  # Train copies the seed when present (CI creates it before build.sh); else it makes a tiny schema-compatible DB.
  "$java" --enable-native-access=ALL-UNNAMED -Djava.library.path=bin -cp bin/classes app.Train "$seed" \
    || { echo "training failed"; return 1; }
  find build/pgo -name '*.gcda' | grep -q . || { echo "no profile data"; return 1; }
  cc_sqlite -fprofile-use -fprofile-correction -Wno-missing-profile -fprofile-dir=build/pgo \
    || { echo "profile-use compile failed"; return 1; }
}

if [ "${PGO:-1}" = 0 ]; then
  cc_sqlite; echo "PGO: skipped (PGO=0)"
elif [ "$(uname -s)" != Linux ] || ! cc --version 2>/dev/null | grep -qi 'gcc\|free software foundation'; then
  cc_sqlite; echo "PGO: skipped (clang)"
elif reason="$(pgo_build 2>&1)"; then
  echo "PGO: on"
else
  echo "PGO: skipped (${reason##*$'\n'})"
  cc_sqlite
fi

compile_java
rm -rf build/pgo
