#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"

property() {
  sed -n "s/^$1=//p" "$PROPERTIES" | tail -n 1
}

WRAPPER_URL=$(property wrapperUrl)
WRAPPER_SHA256=$(property wrapperSha256Sum)
mkdir -p "$(dirname "$WRAPPER_JAR")"

if [ ! -f "$WRAPPER_JAR" ]; then
  TMP_FILE="$WRAPPER_JAR.tmp.$$"
  trap 'rm -f "$TMP_FILE"' EXIT HUP INT TERM
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$WRAPPER_URL" -o "$TMP_FILE"
  elif command -v wget >/dev/null 2>&1; then
    wget --quiet "$WRAPPER_URL" -O "$TMP_FILE"
  else
    echo "Maven Wrapper requires curl or wget to download $WRAPPER_URL" >&2
    exit 1
  fi
  if [ -n "$WRAPPER_SHA256" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      printf '%s  %s\n' "$WRAPPER_SHA256" "$TMP_FILE" | sha256sum -c - >/dev/null
    elif command -v shasum >/dev/null 2>&1; then
      printf '%s  %s\n' "$WRAPPER_SHA256" "$TMP_FILE" | shasum -a 256 -c >/dev/null
    else
      echo "Cannot verify Maven Wrapper checksum: sha256sum/shasum is unavailable" >&2
      exit 1
    fi
  fi
  mv "$TMP_FILE" "$WRAPPER_JAR"
  trap - EXIT HUP INT TERM
fi

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD=$(command -v java || true)
fi
if [ -z "$JAVA_CMD" ] || [ ! -x "$JAVA_CMD" ]; then
  echo "Java is unavailable; set JAVA_HOME to a valid JDK" >&2
  exit 1
fi

exec "$JAVA_CMD" \
  ${MAVEN_OPTS:-} \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
