#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd -P)"
BASE64_DIR="${BASE_DIR}/tools/base64encoder"
BASE64_JAR="${BASE64_DIR}/base64encoder.jar"
BASE64_SRC="${BASE64_DIR}/src/main/java"
DEBUG_KEYSTORE_SCRIPT="${BASE_DIR}/tools/debug-keystore/ensure-debug-keystore.sh"

build_base64_shim() {
  local build_script="${BASE64_DIR}/build.sh"
  if [ ! -x "${build_script}" ]; then
    echo "error: ${build_script} is missing or not executable" >&2
    exit 1
  fi
  "${build_script}"
}

ensure_base64_shim() {
  if [ ! -f "${BASE64_JAR}" ]; then
    build_base64_shim
    return
  fi

  if find "${BASE64_SRC}" -type f -name '*.java' -newer "${BASE64_JAR}" -print -quit >/dev/null; then
    build_base64_shim
  fi
}

ensure_debug_keystore() {
  if [ -x "${DEBUG_KEYSTORE_SCRIPT}" ]; then
    "${DEBUG_KEYSTORE_SCRIPT}"
  fi
}

ensure_base64_shim
ensure_debug_keystore

MAVEN_CMD=${MAVEN_CMD:-mvn}
exec "${MAVEN_CMD}" "$@"
