#!/usr/bin/env bash
# Compiles src/ and tests/ and runs every tests/app/*Test class. Stops at the first failure.
# Needs bin/libsqlite3.* from build.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -x /opt/jdk-25/bin/java ]; then JBIN=/opt/jdk-25/bin/; else JBIN=; fi
rm -rf bin/test-classes
mkdir -p bin/test-classes
"${JBIN}javac" --release 25 -d bin/test-classes $(find src tests -name '*.java')
for t in tests/app/*Test.java; do
  name="app.$(basename "$t" .java)"
  echo "== $name"
  "${JBIN}java" -Djava.library.path=bin --enable-native-access=ALL-UNNAMED -cp bin/test-classes "$name"
done
echo "ALL TESTS PASSED"
