#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd -P)"
BASE64_JAR="${BASE_DIR}/tools/base64encoder/base64encoder.jar"
BASE64_SRC="${BASE_DIR}/tools/base64encoder/src/main/java"
DEBUG_KEYSTORE_SCRIPT="${BASE_DIR}/tools/debug-keystore/ensure-debug-keystore.sh"

rebuild_base64() {
  "${BASE_DIR}/tools/base64encoder/build.sh"
}

ensure_debug_keystore() {
  if [ -x "${DEBUG_KEYSTORE_SCRIPT}" ]; then
    "${DEBUG_KEYSTORE_SCRIPT}"
  fi
}

if [ ! -f "${BASE64_JAR}" ]; then
  rebuild_base64
else
  if [ -n "$(find "${BASE64_SRC}" -type f -name '*.java' -newer "${BASE64_JAR}" -print -quit)" ]; then
    rebuild_base64
  fi
fi

ensure_debug_keystore

MAVEN_CMD=${MAVEN_CMD:-mvn}
exec "${MAVEN_CMD}" "$@"
