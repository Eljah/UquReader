#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd -P)"
BASE64_DIR="${BASE_DIR}/android-app/tools/base64encoder"
BASE64_JAR="${BASE64_DIR}/base64encoder.jar"
BASE64_SRC="${BASE64_DIR}/src/main/java"
DEBUG_KEYSTORE_SCRIPT="${BASE_DIR}/android-app/tools/debug-keystore/ensure-debug-keystore.sh"
DEFAULT_ANDROID_SDK="/usr/lib/android-sdk"

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -d "${DEFAULT_ANDROID_SDK}" ]; then
  export ANDROID_SDK_ROOT="${DEFAULT_ANDROID_SDK}"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
fi

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  for tools_dir in "cmdline-tools/bin" "cmdline-tools/latest/bin" "platform-tools" "emulator"; do
    candidate="${ANDROID_SDK_ROOT}/${tools_dir}"
    case ":${PATH}:" in
      *:"${candidate}":*) ;;
      *)
        if [ -d "${candidate}" ]; then
          PATH="${candidate}:${PATH}"
        fi
        ;;
    esac
  done
  export PATH
fi

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
