#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
mkdir -p "$APP_HOME/gradle/wrapper"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "[Nyama+] Downloading official Gradle 9.6.0 wrapper..."
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --retry 3 -o "$WRAPPER_JAR" "$WRAPPER_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$WRAPPER_JAR" "$WRAPPER_URL"
  else
    echo "ERROR: curl or wget is required to bootstrap the Gradle wrapper." >&2
    exit 1
  fi
fi
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_SHA=$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')
  if [ "$ACTUAL_SHA" != "$WRAPPER_SHA256" ]; then
    echo "ERROR: gradle-wrapper.jar checksum mismatch." >&2
    rm -f "$WRAPPER_JAR"
    exit 1
  fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java
fi
exec "$JAVA" -Dfile.encoding=UTF-8 -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -jar "$WRAPPER_JAR" "$@"
