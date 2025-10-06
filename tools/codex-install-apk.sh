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

ADB_BIN="adb"
if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  # Fall back to the Codex SDK installation if it exists but PATH was not updated.
  if [[ -x "$PROJECT_ROOT/.codex/android-sdk/platform-tools/adb" ]]; then
    ADB_BIN="$PROJECT_ROOT/.codex/android-sdk/platform-tools/adb"
  else
    echo "[codex-install] adb command not found. Ensure platform-tools are installed and on PATH." >&2
    exit 1
  fi
fi

# Capture the current adb device table.
mapfile -t device_rows < <("$ADB_BIN" devices | awk 'NR>1 {print $0}')

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
# Wait for Android to finish booting and the package manager to be ready. When the
# emulator is launched without hardware acceleration it may take a while.
boot_deadline=$((SECONDS + 240))
while true; do
  sys_boot="$($ADB_BIN -s "$target_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  dev_boot="$($ADB_BIN -s "$target_serial" shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r')"
  bootanim_state="$($ADB_BIN -s "$target_serial" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')"

  if [[ "$sys_boot" == "1" || "$dev_boot" == "1" || "$bootanim_state" == "stopped" ]]; then
    if "$ADB_BIN" -s "$target_serial" shell pm path android >/dev/null 2>&1; then
      break
    fi
  fi

  if [[ $SECONDS -ge $boot_deadline ]]; then
    echo "[codex-install] Emulator failed to expose the package manager service within 4 minutes." >&2
    exit 1
  fi

  echo "[codex-install] Waiting for Android package manager to be ready..."
  sleep 3
done

if ! "$ADB_BIN" -s "$target_serial" install -r "$apk_to_install"; then
  echo "[codex-install] adb install failed. Collecting log snippet for diagnosis..." >&2
  "$ADB_BIN" -s "$target_serial" logcat -d | tail -n 40 >&2 || true
  exit 1
fi

echo "[codex-install] Installation completed successfully."
