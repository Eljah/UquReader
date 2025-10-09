#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
MVNW_BIN="${MVNW_BIN:-$PROJECT_ROOT/mvnw}"
ANDROID_MODULE="${CODEX_INSTALL_ANDROID_MODULE:-android-app}"
APK_DIR="$PROJECT_ROOT/$ANDROID_MODULE/target"
RHVOICE_PACKAGE_NAME="com.github.olga_yakovleva.rhvoice.android"
INSTALL_RHVOICE="${CODEX_INSTALL_RHVOICE:-1}"
RHVOICE_ASSET_NAME="${CODEX_RHVOICE_ASSET_NAME:-RHVoice-release.apk}"
RHVOICE_CACHE_DIR="${CODEX_RHVOICE_CACHE_DIR:-$PROJECT_ROOT/.codex/cache}"
RHVOICE_APK_PATH_OVERRIDE="${CODEX_RHVOICE_APK_PATH:-}"
DEFAULT_LAUNCH_AVD=""
LAUNCHED_EMULATOR=0
LAUNCHED_EMULATOR_LOG=""

if [[ -d "$PROJECT_ROOT/.codex/android-sdk" ]]; then
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_ROOT/.codex/android-sdk}"
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
fi

if [[ ! -x "$MVNW_BIN" ]]; then
  echo "[codex-install] Maven wrapper not found at $MVNW_BIN" >&2
  exit 1
fi

DEFAULT_MAVEN_PROFILES="codex-android-sdk,codex-signing"
MAVEN_PROFILES="${CODEX_INSTALL_MAVEN_PROFILES:-$DEFAULT_MAVEN_PROFILES}"
MAVEN_GOALS=("${CODEX_INSTALL_MAVEN_GOAL:-package}")
if [[ -n "${CODEX_INSTALL_EXTRA_MAVEN_GOALS:-}" ]]; then
  # shellcheck disable=SC2206 # Intentional word splitting for extra goals
  EXTRA_GOALS=(${CODEX_INSTALL_EXTRA_MAVEN_GOALS})
  MAVEN_GOALS+=("${EXTRA_GOALS[@]}")
fi

if [[ "${CODEX_INSTALL_SKIP_BUILD:-0}" != "1" ]]; then
  echo "[codex-install] Building $ANDROID_MODULE with Maven profiles: $MAVEN_PROFILES" >&2
  "$MVNW_BIN" -pl "$ANDROID_MODULE" -am -P "$MAVEN_PROFILES" -DskipTests "${MAVEN_GOALS[@]}"
fi

if [[ ! -f "$PROJECT_ROOT/.codex/codex-release.keystore" ]]; then
  echo "[codex-install] Codex release keystore not found at $PROJECT_ROOT/.codex/codex-release.keystore." >&2
  echo "[codex-install] Ensure the codex-signing profile is available and that the build step succeeded." >&2
  exit 1
fi

if [[ ! -d "$APK_DIR" ]]; then
  echo "[codex-install] APK directory $APK_DIR does not exist after build." >&2
  exit 1
fi

shopt -s nullglob
apks=("$APK_DIR"/*.apk)
shopt -u nullglob

if [[ ${#apks[@]} -eq 0 ]]; then
  echo "[codex-install] No APKs found in $APK_DIR after build." >&2
  exit 1
fi

# Prefer the most recent APK by modification time.
apk_to_install="$(ls -t "${apks[@]}" | head -n1)"

echo "[codex-install] Selected APK: $apk_to_install"

RHVOICE_APK_PATH=""
if [[ "$INSTALL_RHVOICE" == "1" ]]; then
  if [[ -n "$RHVOICE_APK_PATH_OVERRIDE" ]]; then
    RHVOICE_APK_PATH="$RHVOICE_APK_PATH_OVERRIDE"
  else
    RHVOICE_APK_PATH="$RHVOICE_CACHE_DIR/$RHVOICE_ASSET_NAME"
  fi
  mkdir -p "$(dirname "$RHVOICE_APK_PATH")"
  downloader_script="$PROJECT_ROOT/tools/download-rhvoice.sh"
  if [[ ! -x "$downloader_script" ]]; then
    echo "[codex-install] RHVoice download helper not found at $downloader_script" >&2
    exit 1
  fi
  if [[ -f "$RHVOICE_APK_PATH" && "${CODEX_RHVOICE_FORCE_DOWNLOAD:-0}" != "1" ]]; then
    echo "[codex-install] Reusing cached RHVoice APK at $RHVOICE_APK_PATH" >&2
  else
    echo "[codex-install] Fetching RHVoice engine APK for installation" >&2
    if ! "$downloader_script" "$RHVOICE_APK_PATH"; then
      echo "[codex-install] Failed to download RHVoice APK to $RHVOICE_APK_PATH" >&2
      exit 1
    fi
  fi
else
  echo "[codex-install] Skipping RHVoice download and installation (CODEX_INSTALL_RHVOICE=$INSTALL_RHVOICE)" >&2
fi

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

ensure_rhvoice_on_device() {
  local serial="$1"
  local apk_path="$2"
  local package_name="$3"

  if [[ -z "$apk_path" ]]; then
    echo "[codex-install] RHVoice APK path is empty; cannot install $package_name" >&2
    return 1
  fi

  if "$ADB_BIN" -s "$serial" shell pm path "$package_name" >/dev/null 2>&1; then
    echo "[codex-install] RHVoice package $package_name already present on $serial; skipping install" >&2
    return 0
  fi

  if [[ ! -f "$apk_path" ]]; then
    echo "[codex-install] RHVoice APK not found at $apk_path" >&2
    return 1
  fi

  echo "[codex-install] Installing RHVoice package $package_name from $apk_path" >&2
  if "$ADB_BIN" -s "$serial" install -r "$apk_path"; then
    echo "[codex-install] RHVoice installation completed" >&2
    return 0
  fi

  echo "[codex-install] Failed to install RHVoice package $package_name" >&2
  return 1
}

find_ui_element_center() {
  local serial="$1"
  local attribute="$2"
  local expected_value="$3"
  local timeout_seconds="${4:-30}"
  python3 - "$serial" "$attribute" "$expected_value" "$timeout_seconds" <<'PY'
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
import subprocess

adb = os.environ.get("ADB_BIN", "adb")
serial, attribute, expected_value, timeout_s = sys.argv[1:5]
timeout = float(timeout_s)
deadline = time.time() + timeout
dump_path = "/sdcard/rhvoice-ui-dump.xml"

def try_dump():
    dump = subprocess.run(
        [adb, "-s", serial, "shell", "uiautomator", "dump", "--compressed", dump_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if dump.returncode != 0:
        return None
    cat = subprocess.run(
        [adb, "-s", serial, "exec-out", "cat", dump_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if cat.returncode != 0:
        return None
    xml = cat.stdout.strip()
    if not xml.startswith("<?xml"):
        return None
    return xml

def parse_bounds(bounds):
    if not bounds:
        return None
    parts = re.findall(r"\d+", bounds)
    if len(parts) != 4:
        return None
    left, top, right, bottom = map(int, parts)
    return (left + right) // 2, (top + bottom) // 2

while time.time() < deadline:
    xml = try_dump()
    if not xml:
        time.sleep(1)
        continue
    try:
        tree = ET.fromstring(xml)
    except ET.ParseError:
        time.sleep(1)
        continue
    for node in tree.iter():
        value = node.attrib.get(attribute)
        if value == expected_value:
            coords = parse_bounds(node.attrib.get("bounds", ""))
            if coords:
                print(f"{coords[0]} {coords[1]}")
                sys.exit(0)
    time.sleep(1)

sys.exit(1)
PY
}

install_talgat_voice() {
  local serial="$1"
  local launch_intent="$RHVOICE_PACKAGE_NAME/.MainActivity"
  echo "[codex-install] Ensuring RHVoice Talgat voice is installed on $serial" >&2
  "$ADB_BIN" -s "$serial" shell am force-stop "$RHVOICE_PACKAGE_NAME" >/dev/null 2>&1 || true
  if ! "$ADB_BIN" -s "$serial" shell am start -a android.intent.action.MAIN -n "$launch_intent" >/dev/null 2>&1; then
    echo "[codex-install] Failed to launch RHVoice UI on $serial" >&2
    return 1
  fi

  local coords
  if ! coords="$(find_ui_element_center "$serial" text "Tatar" 60)"; then
    echo "[codex-install] Unable to locate the Tatar language entry in RHVoice UI" >&2
    return 1
  fi
  "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true

  if ! find_ui_element_center "$serial" text "Talgat" 60 >/dev/null 2>&1; then
    echo "[codex-install] Unable to locate the Talgat voice entry in RHVoice UI" >&2
    return 1
  fi

  if find_ui_element_center "$serial" "content-desc" "Uninstall" 5 >/dev/null 2>&1; then
    echo "[codex-install] Talgat voice already installed; skipping download" >&2
  else
    if ! coords="$(find_ui_element_center "$serial" "content-desc" "Install" 30)"; then
      echo "[codex-install] Unable to locate the Install button for Talgat voice" >&2
      return 1
    fi
    echo "[codex-install] Triggering Talgat voice download" >&2
    "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
    if ! find_ui_element_center "$serial" "content-desc" "Uninstall" 240 >/dev/null 2>&1; then
      echo "[codex-install] Talgat voice installation did not complete within expected time" >&2
      return 1
    fi
    echo "[codex-install] Talgat voice download and installation finished" >&2
  fi

  "$ADB_BIN" -s "$serial" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$serial" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$serial" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  return 0
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

wait_for_package_service() {
  local serial="$1"
  local timeout="${2:-180}"
  local deadline=$((SECONDS + timeout))

  while [[ $SECONDS -lt $deadline ]]; do
    if "$ADB_BIN" -s "$serial" shell cmd package list packages >/dev/null 2>&1; then
      return 0
    fi
    if "$ADB_BIN" -s "$serial" shell pm list packages >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done

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

echo "[codex-install] Verifying package manager service availability before installations..."
if ! wait_for_package_service "$target_serial" 240; then
  echo "[codex-install] Package manager service did not respond within 4 minutes." >&2
  exit 1
fi

if [[ "$INSTALL_RHVOICE" == "1" ]]; then
  if ! ensure_rhvoice_on_device "$target_serial" "$RHVOICE_APK_PATH" "$RHVOICE_PACKAGE_NAME"; then
    echo "[codex-install] Unable to prepare RHVoice on $target_serial" >&2
    exit 1
  fi
  if ! install_talgat_voice "$target_serial"; then
    echo "[codex-install] Failed to automate Talgat voice installation on $target_serial" >&2
    exit 1
  fi
fi

temp_remote_apk="/data/local/tmp/$(basename "$apk_to_install")"
ADB_INSTALL_TIMEOUT_SECONDS="${CODEX_INSTALL_TIMEOUT_SECONDS:-120}"

attempt_streaming_install() {
  echo "[codex-install] Attempting streaming adb install..." >&2
  local -a install_cmd=("$ADB_BIN" -s "$target_serial" install -r "$apk_to_install")
  if command -v timeout >/dev/null 2>&1; then
    timeout "${ADB_INSTALL_TIMEOUT_SECONDS}s" "${install_cmd[@]}"
    local exit_code=$?
    if [[ $exit_code -eq 0 ]]; then
      return 0
    fi
    if [[ $exit_code -eq 124 ]]; then
      echo "[codex-install] Streaming install timed out after ${ADB_INSTALL_TIMEOUT_SECONDS}s; will try fallback." >&2
    else
      echo "[codex-install] Streaming install exited with status $exit_code; will try fallback." >&2
    fi
  else
    if "${install_cmd[@]}"; then
      return 0
    fi
  fi
  return 1
}

attempt_push_install() {
  echo "[codex-install] Attempting push+pm install fallback..." >&2
  if ! "$ADB_BIN" -s "$target_serial" push "$apk_to_install" "$temp_remote_apk" >/dev/null; then
    echo "[codex-install] Failed to push APK to device." >&2
    return 1
  fi
  local -a pm_cmd=("$ADB_BIN" -s "$target_serial" shell pm install -r "$temp_remote_apk")
  if command -v timeout >/dev/null 2>&1; then
    timeout "${ADB_INSTALL_TIMEOUT_SECONDS}s" "${pm_cmd[@]}"
    local exit_code=$?
    if [[ $exit_code -eq 0 ]]; then
      return 0
    fi
    if [[ $exit_code -eq 124 ]]; then
      echo "[codex-install] pm install timed out after ${ADB_INSTALL_TIMEOUT_SECONDS}s." >&2
    else
      echo "[codex-install] pm install exited with status $exit_code." >&2
    fi
  else
    if "${pm_cmd[@]}"; then
      return 0
    fi
  fi
  echo "[codex-install] pm install fallback failed." >&2
  return 1
}

cleanup_remote_apk() {
  "$ADB_BIN" -s "$target_serial" shell rm -f "$temp_remote_apk" >/dev/null 2>&1 || true
}

if attempt_streaming_install || attempt_push_install; then
  echo "[codex-install] Installation completed successfully."
else
  echo "[codex-install] adb install failed. Collecting log snippet for diagnosis..." >&2
  "$ADB_BIN" -s "$target_serial" logcat -d | tail -n 80 >&2 || true
  cleanup_remote_apk
  exit 1
fi

cleanup_remote_apk
