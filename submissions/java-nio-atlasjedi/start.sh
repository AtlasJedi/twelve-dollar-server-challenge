#!/usr/bin/env bash
# Runs the server in the foreground. Config: SQLITE_PATH, JWT_SECRET, HOST, PORT.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x /opt/jdk-25/bin/java ]; then JAVA=/opt/jdk-25/bin/java; else JAVA=java; fi
exec "$JAVA" --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms256m -Xmx512m \
  -XX:+AlwaysPreTouch -Xss256k -XX:MaxDirectMemorySize=256m \
  -Djava.library.path="$DIR/bin" -cp "$DIR/bin/classes" app.Main
