#!/usr/bin/env bash
# Runs the server in the foreground. Config: SQLITE_PATH, JWT_SECRET, HOST, PORT.
# JVM_FLAGS optionally overrides the JVM flags (default below).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x /opt/jdk-25/bin/java ]; then JAVA=/opt/jdk-25/bin/java; else JAVA=java; fi
JVM_FLAGS="${JVM_FLAGS:--XX:+UseSerialGC -Xms256m -Xmx512m -XX:+AlwaysPreTouch -Xss256k -XX:MaxDirectMemorySize=256m}"
# shellcheck disable=SC2086
exec "$JAVA" --enable-native-access=ALL-UNNAMED $JVM_FLAGS \
  -Djava.library.path="$DIR/bin" -cp "$DIR/bin/classes" app.Main
