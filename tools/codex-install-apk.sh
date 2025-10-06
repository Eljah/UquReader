#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
APK_DIR="$PROJECT_ROOT/android-app/target"
DEFAULT_LAUNCH_AVD=""
LAUNCHED_EMULATOR=0
LAUNCHED_EMULATOR_LOG=""

if [[ -d "$PROJECT_ROOT/.codex/android-sdk" ]]; then
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_ROOT/.codex/android-sdk}"
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
fi

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

find_cmdline_tool() {
  local tool_name="$1"
  local candidate
  local -a sdk_roots=()

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_roots+=("$ANDROID_SDK_ROOT")
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_roots+=("$ANDROID_HOME")
  fi
  sdk_roots+=("$PROJECT_ROOT/.codex/android-sdk")

  for candidate in "${sdk_roots[@]}"; do
    if [[ -x "$candidate/cmdline-tools/latest/bin/$tool_name" ]]; then
      echo "$candidate/cmdline-tools/latest/bin/$tool_name"
      return 0
    fi
    if [[ -x "$candidate/cmdline-tools/bin/$tool_name" ]]; then
      echo "$candidate/cmdline-tools/bin/$tool_name"
      return 0
    fi
  done

  if command -v "$tool_name" >/dev/null 2>&1; then
    command -v "$tool_name"
    return 0
  fi

  return 1
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

create_default_avd() {
  local avdmanager_bin sdkmanager_bin system_image avd_name
  system_image="${CODEX_INSTALL_SYSTEM_IMAGE:-system-images;android-28;default;x86_64}"
  avd_name="${CODEX_INSTALL_AVD:-codex-default}"  # Will be overridden if AVD already exists.

  if ! avdmanager_bin="$(find_cmdline_tool avdmanager)"; then
    echo "[codex-install] avdmanager not found; cannot create an emulator profile automatically." >&2
    return 1
  fi

  echo "[codex-install] Creating default AVD $avd_name using $system_image" >&2
  if printf 'no\n' | "$avdmanager_bin" create avd -f -n "$avd_name" -k "$system_image" >/dev/null 2>&1; then
    echo "[codex-install] Created AVD $avd_name" >&2
    return 0
  fi

  if ! sdkmanager_bin="$(find_cmdline_tool sdkmanager)"; then
    echo "[codex-install] Failed to create AVD automatically and sdkmanager is unavailable." >&2
    return 1
  fi

  echo "[codex-install] Required system image missing. Attempting to install $system_image" >&2
  if ! yes | "$sdkmanager_bin" --install "$system_image" >/dev/null 2>&1; then
    echo "[codex-install] sdkmanager failed to install $system_image" >&2
    return 1
  fi

  if printf 'no\n' | "$avdmanager_bin" create avd -f -n "$avd_name" -k "$system_image" >/dev/null 2>&1; then
    echo "[codex-install] Created AVD $avd_name" >&2
    return 0
  fi

  echo "[codex-install] Unable to create a default AVD automatically." >&2
  return 1
}

ensure_launch_avd() {
  local emulator_bin="$1"
  mapfile -t available_avds < <("$emulator_bin" -list-avds 2>/dev/null | sed 's/\r$//')
  if [[ ${#available_avds[@]} -eq 0 ]]; then
    if ! create_default_avd; then
      return 1
    fi
    mapfile -t available_avds < <("$emulator_bin" -list-avds 2>/dev/null | sed 's/\r$//')
  fi

  if [[ ${#available_avds[@]} -eq 0 ]]; then
    echo "[codex-install] Emulator binary located but no AVDs are configured." >&2
    return 1
  fi

  if [[ -n "${CODEX_INSTALL_AVD:-}" ]]; then
    local avd
    for avd in "${available_avds[@]}"; do
      if [[ "$avd" == "$CODEX_INSTALL_AVD" ]]; then
        DEFAULT_LAUNCH_AVD="$avd"
        return 0
      fi
    done
    echo "[codex-install] Requested AVD $CODEX_INSTALL_AVD not found; falling back to ${available_avds[0]}" >&2
  fi

  DEFAULT_LAUNCH_AVD="${available_avds[0]}"
  return 0
}

launch_default_emulator() {
  local emulator_bin log_dir timestamp log_file
  if ! emulator_bin="$(find_emulator_binary)"; then
    echo "[codex-install] No emulator binary found on PATH or in ANDROID_HOME/ANDROID_SDK_ROOT." >&2
    return 1
  fi

  if ! ensure_launch_avd "$emulator_bin"; then
    return 1
  fi

  log_dir="$PROJECT_ROOT/.codex/logs"
  mkdir -p "$log_dir"
  timestamp="$(date +%Y%m%d-%H%M%S)"
  log_file="$log_dir/emulator-${DEFAULT_LAUNCH_AVD}-${timestamp}.log"

  echo "[codex-install] Launching emulator for AVD $DEFAULT_LAUNCH_AVD (logs: $log_file)" >&2

  # Launch the emulator in the background with headless-friendly defaults.
  # The nohup/stdout redirection prevents the emulator process from being
  # killed when the script finishes.
  nohup "$emulator_bin" \
    -avd "$DEFAULT_LAUNCH_AVD" \
    -no-snapshot-save \
    -no-boot-anim \
    -no-window \
    -no-audio \
    -gpu swiftshader_indirect \
    -accel off \
    >"$log_file" 2>&1 &
  disown || true
  LAUNCHED_EMULATOR=1
  LAUNCHED_EMULATOR_LOG="$log_file"
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
      LAUNCHED_EMULATOR=0
      LAUNCHED_EMULATOR_LOG=""
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
    LAUNCHED_EMULATOR=0
    LAUNCHED_EMULATOR_LOG=""
  elif [[ $LAUNCHED_EMULATOR -eq 0 ]]; then
    if launch_default_emulator; then
      performed_action=1
    fi
  else
    performed_action=1
    if [[ -n "$LAUNCHED_EMULATOR_LOG" ]]; then
      echo "[codex-install]  - Emulator already launched; continuing to wait (logs: $LAUNCHED_EMULATOR_LOG)" >&2
    else
      echo "[codex-install]  - Emulator already launched; continuing to wait" >&2
    fi
  fi

  if [[ $performed_action -eq 0 ]]; then
    echo "[codex-install] No recovery actions available. Connect or configure an emulator manually." >&2
    return 1
  fi

  return 0
}

wait_for_online_device() {
  local timeout="${1:-120}"
  local deadline=$((SECONDS + timeout))

  while [[ $SECONDS -lt $deadline ]]; do
    collect_devices
    if [[ ${#online_devices[@]} -gt 0 ]]; then
      return 0
    fi
    sleep 5
  done

  collect_devices
  return 1
}

collect_devices

if [[ ${#online_devices[@]} -eq 0 ]]; then
  wait_for_online_device 15 || true
fi

recovery_attempt=0
max_recovery_attempts=5
while [[ ${#online_devices[@]} -eq 0 && $recovery_attempt -lt $max_recovery_attempts ]]; do
  if ! attempt_recovery "$recovery_attempt" "no-online-devices"; then
    break
  fi
  if wait_for_online_device 150; then
    break
  fi
  ((recovery_attempt+=1))
done

collect_devices

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
