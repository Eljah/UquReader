#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SRC_DIR="${ROOT_DIR}/tools/base64encoder/src/main/java"
OUT_DIR="$(mktemp -d)"
trap 'rm -rf "${OUT_DIR}"' EXIT

mkdir -p "${OUT_DIR}/classes"
find "${SRC_DIR}" -name '*.java' -print0 | xargs -0 javac --release 8 -d "${OUT_DIR}/classes"
jar cf "${ROOT_DIR}/tools/base64encoder/base64encoder.jar" -C "${OUT_DIR}/classes" .
