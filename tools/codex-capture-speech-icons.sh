#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
ADB_BIN="${ADB_BIN:-adb}"
SERIAL="${CODEX_CAPTURE_SERIAL:-}"

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  if [[ -x "$PROJECT_ROOT/.codex/android-sdk/platform-tools/adb" ]]; then
    ADB_BIN="$PROJECT_ROOT/.codex/android-sdk/platform-tools/adb"
  elif [[ -x "$PROJECT_ROOT/.codex/android-sdk_linux/platform-tools/adb" ]]; then
    ADB_BIN="$PROJECT_ROOT/.codex/android-sdk_linux/platform-tools/adb"
  fi
fi

export ADB_BIN

wait_for_online_device() {
  local timeout="${1:-60}"
  local deadline=$((SECONDS + timeout))
  while [[ $SECONDS -lt $deadline ]]; do
    mapfile -t devices < <("$ADB_BIN" devices | awk 'NR>1 && $2 == "device" {print $1}')
    if [[ ${#devices[@]} -gt 0 ]]; then
      SERIAL="${SERIAL:-${devices[0]}}"
      return 0
    fi
    sleep 2
  done
  return 1
}

fail() {
  echo "[codex-capture] $*" >&2
  exit 1
}

run_adb() {
  if [[ -n "$SERIAL" ]]; then
    "$ADB_BIN" -s "$SERIAL" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

ensure_device_ready() {
  if ! wait_for_online_device 120; then
    fail "No online Android devices detected by adb"
  fi
  if ! run_adb wait-for-device >/dev/null 2>&1; then
    fail "Unable to wait for adb device"
  fi
  local deadline=$((SECONDS + 120))
  while [[ $SECONDS -lt $deadline ]]; do
    local boot
    boot="$(run_adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$boot" == "1" ]]; then
      return 0
    fi
    sleep 2
  done
  fail "Timed out waiting for device to finish booting"
}

screencap() {
  local label="$1"
  local output_dir="$PROJECT_ROOT/.codex/screens"
  mkdir -p "$output_dir"
  local timestamp
  timestamp="$(date +%Y%m%d-%H%M%S)"
  local dest="$output_dir/${timestamp}-${label}.png"
  echo "[codex-capture] Capturing screenshot to $dest"
  if ! run_adb exec-out screencap -p >"$dest"; then
    rm -f "$dest"
    fail "Failed to capture screenshot for $label"
  fi
  echo "$dest"
}

read_uiautomator_dump() {
  local tmpfile
  tmpfile="$(mktemp)"
  local remote="/sdcard/codex-capture-ui.xml"
  run_adb shell uiautomator dump "$remote" >/dev/null 2>&1 || true
  if ! run_adb shell cat "$remote" >"$tmpfile" 2>/dev/null; then
    rm -f "$tmpfile"
    fail "Unable to retrieve UIAutomator dump"
  fi
  cat "$tmpfile"
  rm -f "$tmpfile"
}

find_node_center() {
  local pattern="$1"
  local dump
  dump="$(read_uiautomator_dump)"
  python3 - "$pattern" <<'PY'
import re
import sys
pattern = sys.argv[1]
dump = sys.stdin.read()
# Extract nodes with content-desc or text containing pattern (case-insensitive)
regex = re.compile(r'<node[^>]+?>', re.DOTALL)
match_nodes = []
for node in regex.findall(dump):
    attrs = dict(re.findall(r'(\w+)="([^"]*)"', node))
    content = attrs.get('content-desc', '')
    text = attrs.get('text', '')
    if re.search(pattern, content, re.IGNORECASE) or re.search(pattern, text, re.IGNORECASE):
        bounds = attrs.get('bounds')
        if bounds:
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                cx = (x1 + x2) // 2
                cy = (y1 + y2) // 2
                match_nodes.append((cx, cy, content or text))
if not match_nodes:
    sys.exit(1)
# Prefer nodes with explicit content description first
match_nodes.sort(key=lambda item: (0 if item[2] else 1))
cx, cy, _ = match_nodes[0]
print(f"{cx} {cy}")
PY
}

safe_tap() {
  local pattern="$1"
  local label="$2"
  local coords
  if ! coords="$(find_node_center "$pattern")"; then
    fail "Unable to locate $label button via UIAutomator dump"
  fi
  local x="${coords%% *}"
  local y="${coords##* }"
  echo "[codex-capture] Tapping $label at $x,$y"
  run_adb shell input tap "$x" "$y"
}

launch_app() {
  echo "[codex-capture] Launching UquReader"
  run_adb shell am start -a android.intent.action.MAIN -n com.example.ttreader/.MainActivity >/dev/null
}

main() {
  ensure_device_ready
  launch_app
  echo "[codex-capture] Waiting for UI to settle"
  sleep 5
  safe_tap "озвуч" "speech toggle"
  echo "[codex-capture] Waiting for speech start"
  sleep 4
  start_shot="$(screencap "speech-start")"
  echo "[codex-capture] Speech start screenshot: $start_shot"
  safe_tap "останов" "speech stop"
  echo "[codex-capture] Waiting after stop"
  sleep 2
  stop_shot="$(screencap "speech-stop")"
  echo "[codex-capture] Speech stop screenshot: $stop_shot"
}

main "$@"
