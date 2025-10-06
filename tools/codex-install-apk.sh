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

if ! "$ADB_BIN" start-server >/dev/null 2>&1; then
  echo "[codex-install] Failed to start the adb server." >&2
  exit 1
fi

collect_devices() {
  local row serial state
  device_rows=()
  mapfile -t device_rows < <("$ADB_BIN" devices | awk 'NR>1 {print $0}')
  online_devices=()
  offline_devices=()

  for row in "${device_rows[@]}"; do
    # Rows are of the form "serial\tstate" but there may be trailing spaces.
    row="${row%%[[:space:]]}"
    if [[ -z "$row" ]]; then
      continue
    fi
    serial="${row%%$'\t'*}"
    state="${row##*$'\t'}"
    case "$state" in
      device)
        online_devices+=("$serial")
        ;;
      offline|unknown|unauthorized)
        offline_devices+=("$serial:$state")
        ;;
    esac
  done
}

find_emulator_binary() {
  local candidate
  for candidate in \
    "${ANDROID_HOME:-}/emulator/emulator" \
    "${ANDROID_SDK_ROOT:-}/emulator/emulator" \
    "$PROJECT_ROOT/.codex/android-sdk/emulator/emulator"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done

  if command -v emulator >/dev/null 2>&1; then
    command -v emulator
    return 0
  fi

  return 1
}

launch_default_emulator() {
  local emulator_bin
  if ! emulator_bin="$(find_emulator_binary)"; then
    echo "[codex-install] No emulator binary found on PATH or in ANDROID_HOME/ANDROID_SDK_ROOT." >&2
    return 1
  fi

  mapfile -t available_avds < <("$emulator_bin" -list-avds 2>/dev/null | sed 's/\r$//')
  if [[ ${#available_avds[@]} -eq 0 ]]; then
    echo "[codex-install] Emulator binary located but no AVDs are configured." >&2
    return 1
  fi

  local target_avd="${CODEX_INSTALL_AVD:-${available_avds[0]}}"
  echo "[codex-install] Launching emulator for AVD $target_avd" >&2

  # Launch the emulator in the background. Users can provide CODEX_EMULATOR_RESTART_CMD
  # when a custom startup sequence is required. The nohup/stdout redirection prevents
  # the emulator process from being killed when the script finishes.
  nohup "$emulator_bin" -avd "$target_avd" -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
  disown || true
  return 0
}

attempt_recovery() {
  local attempt="${1:-0}"
  local reason="${2:-offline}"
  local performed_action=0

  echo "[codex-install] Attempting device/emulator recovery (attempt $((attempt + 1)); reason=$reason)." >&2
  "$ADB_BIN" reconnect offline >/dev/null 2>&1 || true
  "$ADB_BIN" kill-server >/dev/null 2>&1 || true
  "$ADB_BIN" start-server >/dev/null 2>&1 || true

  local entry serial state
  for entry in "${offline_devices[@]}"; do
    performed_action=1
    serial="${entry%%:*}"
    state="${entry##*:}"
    if [[ "$serial" =~ ^emulator-[0-9]+$ ]]; then
      echo "[codex-install]  - Requesting emulator reboot for $serial (state=$state)" >&2
      "$ADB_BIN" -s "$serial" emu kill >/dev/null 2>&1 || true
    else
      echo "[codex-install]  - Requesting device reboot for $serial (state=$state)" >&2
      "$ADB_BIN" -s "$serial" reboot >/dev/null 2>&1 || true
    fi
  done

  if [[ -n "${CODEX_EMULATOR_RESTART_CMD:-}" ]]; then
    performed_action=1
    echo "[codex-install]  - Executing CODEX_EMULATOR_RESTART_CMD" >&2
    # shellcheck disable=SC2086 # Intentional word splitting for custom command sequences.
    eval ${CODEX_EMULATOR_RESTART_CMD} || true
  elif launch_default_emulator; then
    performed_action=1
  fi

  if [[ $performed_action -eq 0 ]]; then
    echo "[codex-install] No recovery actions available. Connect or configure an emulator manually." >&2
    return 1
  fi

  sleep 5
  return 0
}

collect_devices

recovery_attempt=0
while [[ ${#online_devices[@]} -eq 0 && $recovery_attempt -lt 3 ]]; do
  if ! attempt_recovery "$recovery_attempt" "no-online-devices"; then
    break
  fi
  ((recovery_attempt++))
  collect_devices
done

if [[ ${#online_devices[@]} -eq 0 ]]; then
  echo "[codex-install] No connected Android devices or emulators detected by adb." >&2
  if [[ ${#offline_devices[@]} -gt 0 ]]; then
    echo "[codex-install] Offline entries detected after recovery attempts:" >&2
    for entry in "${offline_devices[@]}"; do
      echo "[codex-install]  - $entry" >&2
    done
  fi
  echo "[codex-install] Ensure an emulator is running and reachable. Restart it manually if necessary." >&2
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
