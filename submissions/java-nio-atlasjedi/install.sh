#!/usr/bin/env bash
# Runs once as root on a clean Ubuntu 24.04: gcc (to build SQLite) and Temurin JDK 25 in /opt/jdk-25.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends build-essential curl ca-certificates unzip

BASE=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1
case "$(uname -m)" in
  x86_64)  FILE=OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz
           SHA=dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e ;;
  aarch64) FILE=OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.4.1_1.tar.gz
           SHA=69df11a02cfa3ef7d7ca645e03edce6778ec090e100f6ae2b42097865730ac52 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/jdk.tar.gz" "$BASE/$FILE"
echo "$SHA  $TMP/jdk.tar.gz" | sha256sum -c -
rm -rf /opt/jdk-25
mkdir -p /opt/jdk-25
tar -xzf "$TMP/jdk.tar.gz" -C /opt/jdk-25 --strip-components=1
/opt/jdk-25/bin/java -version
