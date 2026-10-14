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
cc -O2 -fPIC -shared -o "bin/libsqlite3.$EXT" build/sqlite-amalgamation-3540000/sqlite3.c \
  -DSQLITE_THREADSAFE=0 -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1 -DSQLITE_DEFAULT_MEMSTATUS=0 \
  -DSQLITE_OMIT_DEPRECATED -DSQLITE_OMIT_SHARED_CACHE -DSQLITE_OMIT_PROGRESS_CALLBACK \
  -DSQLITE_OMIT_DECLTYPE -DSQLITE_DQS=0 -DSQLITE_MAX_EXPR_DEPTH=0 -DSQLITE_USE_ALLOCA \
  -DSQLITE_LIKE_DOESNT_MATCH_BLOBS -DSQLITE_OMIT_LOAD_EXTENSION -lm

rm -rf bin/classes
"$JAVAC" --release 25 -d bin/classes $(find src -name '*.java')
