#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
APK_DIR="$PROJECT_ROOT/android-app/target"

if [[ ! -d "$APK_DIR" ]]; then
  echo "[codex-install] APK directory $APK_DIR does not exist. Build the project first." >&2
  exit 1
fi

shopt -s nullglob
apks=("$APK_DIR"/*.apk)
shopt -u nullglob

if [[ ${#apks[@]} -eq 0 ]]; then
  echo "[codex-install] No APKs found in $APK_DIR. Build the project before installing." >&2
  exit 1
fi

# Prefer the most recent APK by modification time.
apk_to_install="$(ls -t "${apks[@]}" | head -n1)"

echo "[codex-install] Selected APK: $apk_to_install"

if ! command -v adb >/dev/null 2>&1; then
  echo "[codex-install] adb command not found. Ensure platform-tools are installed and on PATH." >&2
  exit 1
fi

# Capture the current adb device table.
mapfile -t device_rows < <(adb devices | awk 'NR>1 {print $0}')

if [[ ${#device_rows[@]} -eq 0 ]]; then
  echo "[codex-install] No connected Android devices or emulators detected by adb." >&2
  exit 1
fi

online_devices=()
offline_devices=()
for row in "${device_rows[@]}"; do
  # Rows are of the form "serial\tstate".
  serial="${row%%$'\t'*}"
  state="${row##*$'\t'}"
  case "$state" in
    device)
      online_devices+=("$serial")
      ;;
    offline|unknown)
      offline_devices+=("$serial:$state")
      ;;
  esac
done

if [[ ${#online_devices[@]} -eq 0 ]]; then
  echo "[codex-install] adb found devices, but none are in the 'device' state."
  for entry in "${offline_devices[@]}"; do
    echo "[codex-install]  - $entry"
  done
  echo "[codex-install] Wait for the emulator/device to finish booting and report 'device'." >&2
  exit 1
fi

target_serial="${online_devices[0]}"

if [[ ${#online_devices[@]} -gt 1 ]]; then
  echo "[codex-install] Multiple online devices detected. Using $target_serial." >&2
fi

echo "[codex-install] Installing $apk_to_install to $target_serial"
if ! adb -s "$target_serial" install -r "$apk_to_install"; then
  echo "[codex-install] adb install failed. Collecting log snippet for diagnosis..." >&2
  adb -s "$target_serial" logcat -d | tail -n 40 >&2 || true
  exit 1
fi

echo "[codex-install] Installation completed successfully."
