#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_DIR="${HOME}/.android"
KEYSTORE_PATH="${KEYSTORE_DIR}/debug.keystore"

if [ -f "${KEYSTORE_PATH}" ]; then
  echo "info: reusing existing debug keystore at ${KEYSTORE_PATH}" >&2
  exit 0
fi

mkdir -p "${KEYSTORE_DIR}"

keytool -genkeypair \
  -alias androiddebugkey \
  -keypass android \
  -storepass android \
  -keystore "${KEYSTORE_PATH}" \
  -dname "CN=Android Debug,O=Android,C=US" \
  -validity 10000 \
  -keyalg RSA \
  -keysize 2048 >/dev/null
