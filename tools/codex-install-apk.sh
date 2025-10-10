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
TALGAT_LANGUAGE_ARCHIVE_NAME="${CODEX_RHVOICE_TALGAT_LANGUAGE_ARCHIVE:-RHVoice-language-Tatar-v1.10.zip}"
TALGAT_LANGUAGE_URL="${CODEX_RHVOICE_TALGAT_LANGUAGE_URL:-https://rhvoice.org/download/RHVoice-language-Tatar-v1.10.zip}"
TALGAT_LANGUAGE_MD5_HEX="${CODEX_RHVOICE_TALGAT_LANGUAGE_MD5:-b8ac903669753b86d27187e9b7f33309}"
TALGAT_VOICE_ARCHIVE_NAME="${CODEX_RHVOICE_TALGAT_VOICE_ARCHIVE:-RHVoice-voice-Tatar-Talgat-v4.0.zip}"
TALGAT_VOICE_URL="${CODEX_RHVOICE_TALGAT_VOICE_URL:-https://rhvoice.org/download/RHVoice-voice-Tatar-Talgat-v4.0.zip}"
TALGAT_VOICE_MD5_HEX="${CODEX_RHVOICE_TALGAT_VOICE_MD5:-af0785aed7d918dfb547a689ce887b54}"
TALGAT_TARGET_ROOT="${CODEX_RHVOICE_TALGAT_TARGET_ROOT:-/sdcard/RHVoice}"
TALGAT_FORCE_DOWNLOAD="${CODEX_RHVOICE_TALGAT_FORCE_DOWNLOAD:-0}"
DEFAULT_LAUNCH_AVD=""
LAUNCHED_EMULATOR=0
LAUNCHED_EMULATOR_LOG=""

if [[ -d "$PROJECT_ROOT/.codex/android-sdk" ]]; then
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_ROOT/.codex/android-sdk}"
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
fi

ensure_sdk_component_installed() {
  local component="$1"
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$PROJECT_ROOT/.codex/android-sdk}}"
  local check_path=""
  local sdkmanager_bin=""

  if [[ -z "$sdk_root" ]]; then
    return 1
  fi

  case "$component" in
    platform-tools)
      check_path="$sdk_root/platform-tools/adb"
      ;;
    emulator)
      check_path="$sdk_root/emulator/emulator"
      ;;
    "cmdline-tools;latest")
      if [[ -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ]]; then
        return 0
      fi
      if [[ -x "$sdk_root/cmdline-tools/bin/sdkmanager" ]]; then
        return 0
      fi
      if command -v sdkmanager >/dev/null 2>&1; then
        return 0
      fi
      check_path=""
      ;;
    *)
      check_path=""
      ;;
  esac

  if [[ -n "$check_path" && -e "$check_path" ]]; then
    return 0
  fi

  if ! sdkmanager_bin="$(find_cmdline_tool sdkmanager)"; then
    if [[ "$component" == "cmdline-tools;latest" ]]; then
      local cmdline_archive="$PROJECT_ROOT/android-commandlinetools.zip"
      local temp_dir=""
      if [[ -f "$cmdline_archive" ]]; then
        echo "[codex-install] Bootstrapping Android cmdline-tools from $cmdline_archive" >&2
        if ! command -v unzip >/dev/null 2>&1; then
          echo "[codex-install] unzip command not available; cannot extract $cmdline_archive" >&2
          return 1
        fi
        temp_dir="$(mktemp -d)"
        if unzip -qo "$cmdline_archive" -d "$temp_dir" >/dev/null 2>&1; then
          mkdir -p "$sdk_root/cmdline-tools"
          rm -rf "$sdk_root/cmdline-tools/latest"
          if mv "$temp_dir/cmdline-tools" "$sdk_root/cmdline-tools/latest"; then
            rm -rf "$temp_dir"
            if sdkmanager_bin="$(find_cmdline_tool sdkmanager)"; then
              return 0
            fi
          else
            echo "[codex-install] Failed to move extracted cmdline-tools into $sdk_root" >&2
            rm -rf "$temp_dir"
            return 1
          fi
        else
          echo "[codex-install] Failed to extract $cmdline_archive" >&2
          rm -rf "$temp_dir"
          return 1
        fi
        rm -rf "$temp_dir"
        if sdkmanager_bin="$(find_cmdline_tool sdkmanager)"; then
          return 0
        fi
      else
        echo "[codex-install] Command-line tools archive not found at $cmdline_archive" >&2
      fi
    fi
    return 1
  fi

  echo "[codex-install] Installing missing Android SDK component: $component" >&2
  if yes | "$sdkmanager_bin" --install "$component" >/dev/null 2>&1; then
    return 0
  fi

  echo "[codex-install] Failed to install Android SDK component $component" >&2
  return 1
}

ensure_android_sdk_prereqs() {
  local -a required_components=("cmdline-tools;latest" "platform-tools" "emulator")
  local component=""
  local had_failure=0

  for component in "${required_components[@]}"; do
    if ! ensure_sdk_component_installed "$component"; then
      had_failure=1
    fi
  done

  if [[ $had_failure -eq 1 ]]; then
    echo "[codex-install] Unable to provision required Android SDK components automatically." >&2
    echo "[codex-install] Ensure sdkmanager is available and rerun the script." >&2
    return 1
  fi

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

compute_file_md5() {
  local file_path="$1"
  python3 - "$file_path" <<'PY'
import hashlib
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
if not path.exists():
    sys.exit(1)
print(hashlib.md5(path.read_bytes()).hexdigest())
PY
}

download_rhvoice_archive() {
  local dest_path="$1"
  local url="$2"
  local expected_md5="$3"
  local force_download="${4:-0}"
  local label="${5:-archive}"
  local temp_path

  if [[ -f "$dest_path" && "$force_download" != "1" ]]; then
    if [[ -z "$expected_md5" ]]; then
      echo "[codex-install] Reusing cached $label at $dest_path" >&2
      return 0
    fi
    local current_md5
    if current_md5="$(compute_file_md5 "$dest_path" 2>/dev/null)"; then
      if [[ "${current_md5,,}" == "${expected_md5,,}" ]]; then
        echo "[codex-install] Reusing cached $label at $dest_path (checksum verified)" >&2
        return 0
      fi
      echo "[codex-install] Cached $label at $dest_path has unexpected checksum ($current_md5, expected $expected_md5); redownloading" >&2
    else
      echo "[codex-install] Cached $label at $dest_path is unreadable; redownloading" >&2
    fi
  fi

  mkdir -p "$(dirname "$dest_path")"
  temp_path="$(mktemp "$(dirname "$dest_path")/$(basename "$dest_path").XXXXXX")"
  echo "[codex-install] Downloading $label from $url" >&2
  if ! curl --fail --silent --show-error --location --retry 3 --retry-delay 2 "$url" -o "$temp_path"; then
    echo "[codex-install] Failed to download $label from $url" >&2
    rm -f "$temp_path"
    return 1
  fi

  if [[ -n "$expected_md5" ]]; then
    local downloaded_md5
    if ! downloaded_md5="$(compute_file_md5 "$temp_path" 2>/dev/null)"; then
      echo "[codex-install] Unable to compute checksum for downloaded $label" >&2
      rm -f "$temp_path"
      return 1
    fi
    if [[ "${downloaded_md5,,}" != "${expected_md5,,}" ]]; then
      echo "[codex-install] Downloaded $label checksum mismatch (got $downloaded_md5, expected $expected_md5)" >&2
      rm -f "$temp_path"
      return 1
    fi
  fi

  if mv "$temp_path" "$dest_path"; then
    echo "[codex-install] Saved $label to $dest_path" >&2
    return 0
  fi

  echo "[codex-install] Failed to move downloaded $label into place at $dest_path" >&2
  rm -f "$temp_path"
  return 1
}

wait_for_package_service() {
  local serial="$1"
  local timeout="${2:-180}"
  local deadline=$((SECONDS + timeout))

  while [[ $SECONDS -lt $deadline ]]; do
    if "$ADB_BIN" -s "$serial" shell service check package >/dev/null 2>&1; then
      return 0
    fi
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
  TALGAT_LANGUAGE_ARCHIVE_PATH="$RHVOICE_CACHE_DIR/$TALGAT_LANGUAGE_ARCHIVE_NAME"
  TALGAT_VOICE_ARCHIVE_PATH="$RHVOICE_CACHE_DIR/$TALGAT_VOICE_ARCHIVE_NAME"
  if ! download_rhvoice_archive "$TALGAT_LANGUAGE_ARCHIVE_PATH" "$TALGAT_LANGUAGE_URL" "$TALGAT_LANGUAGE_MD5_HEX" "$TALGAT_FORCE_DOWNLOAD" "Tatar language pack"; then
    echo "[codex-install] Failed to prepare Tatar language assets for RHVoice" >&2
    exit 1
  fi
  if ! download_rhvoice_archive "$TALGAT_VOICE_ARCHIVE_PATH" "$TALGAT_VOICE_URL" "$TALGAT_VOICE_MD5_HEX" "$TALGAT_FORCE_DOWNLOAD" "Talgat voice pack"; then
    echo "[codex-install] Failed to prepare Talgat voice assets for RHVoice" >&2
    exit 1
  fi
else
  echo "[codex-install] Skipping RHVoice download and installation (CODEX_INSTALL_RHVOICE=$INSTALL_RHVOICE)" >&2
fi

if [[ -d "$PROJECT_ROOT/.codex/android-sdk" ]]; then
  if ! ensure_android_sdk_prereqs; then
    exit 1
  fi
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

export ADB_BIN

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
  local max_attempts=3
  local attempt=1
  local install_output=""

  if [[ -z "$apk_path" ]]; then
    echo "[codex-install] RHVoice APK path is empty; cannot install $package_name" >&2
    return 1
  fi
  if ! wait_for_package_service "$serial" 180; then
    echo "[codex-install] Package manager unavailable on $serial; cannot verify RHVoice installation state" >&2
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

  while (( attempt <= max_attempts )); do
    echo "[codex-install] Installing RHVoice package $package_name from $apk_path (attempt $attempt/$max_attempts)" >&2
    if install_output="$("$ADB_BIN" -s "$serial" install -r "$apk_path" 2>&1)"; then
      echo "[codex-install] RHVoice installation completed" >&2
      return 0
    fi
    echo "$install_output" >&2
    if (( attempt == max_attempts )); then
      break
    fi
    echo "[codex-install] RHVoice install failed on $serial; waiting for package manager and retrying" >&2
    sleep 3
    wait_for_package_service "$serial" 120 || true
    ((attempt+=1))
  done
 
  echo "[codex-install] Failed to install RHVoice package $package_name" >&2
  return 1
}

prepare_talgat_staging_dir() {
  local language_archive="$1"
  local voice_archive="$2"
  local staging_root=""
  local language_target=""
  local voice_target=""

  if ! command -v unzip >/dev/null 2>&1; then
    echo "[codex-install] unzip command is required to extract RHVoice language/voice archives." >&2
    return 1
  fi

  staging_root="$(mktemp -d)"
  language_target="$staging_root/RHVoice/languages/tatar"
  voice_target="$staging_root/RHVoice/voices/tatar/Talgat"

  if ! mkdir -p "$language_target" "$voice_target"; then
    echo "[codex-install] Failed to prepare staging directories for RHVoice assets" >&2
    rm -rf "$staging_root"
    return 1
  fi

  if ! unzip -qo "$language_archive" -d "$language_target"; then
    echo "[codex-install] Failed to extract RHVoice language archive $language_archive" >&2
    rm -rf "$staging_root"
    return 1
  fi

  if ! unzip -qo "$voice_archive" -d "$voice_target"; then
    echo "[codex-install] Failed to extract RHVoice Talgat voice archive $voice_archive" >&2
    rm -rf "$staging_root"
    return 1
  fi

  printf '%s' "$staging_root/RHVoice"
  return 0
}

push_directory_recursive() {
  local serial="$1"
  local src_dir="$2"
  local remote_parent="$3"
  local dest_name

  dest_name="$(basename "$src_dir")"
  "$ADB_BIN" -s "$serial" shell rm -rf "$remote_parent/$dest_name" >/dev/null 2>&1 || true
  if ! "$ADB_BIN" -s "$serial" shell mkdir -p "$remote_parent" >/dev/null 2>&1; then
    echo "[codex-install] Failed to create directory $remote_parent on $serial" >&2
    return 1
  fi

  if ! "$ADB_BIN" -s "$serial" push "$src_dir" "$remote_parent" >/dev/null; then
    echo "[codex-install] Failed to push $src_dir into $remote_parent on $serial" >&2
    return 1
  fi

  return 0
}

ensure_talgat_voice_assets() {
  local serial="$1"
  local language_archive_path="$2"
  local voice_archive_path="$3"
  local staging_root=""
  local language_dir=""
  local voice_dir=""
  local cleanup_dir=""
  local remote_language_parent="$TALGAT_TARGET_ROOT/languages"
  local remote_voice_parent="$TALGAT_TARGET_ROOT/voices/tatar"

  if is_talgat_voice_installed "$serial"; then
    local existing_path
    existing_path="$(detect_talgat_voice_path "$serial" || true)"
    if [[ -n "$existing_path" ]]; then
      echo "[codex-install] Talgat voice assets already detected at $existing_path" >&2
    else
      echo "[codex-install] Talgat voice already present on $serial" >&2
    fi
    return 0
  fi

  if ! staging_root="$(prepare_talgat_staging_dir "$language_archive_path" "$voice_archive_path")"; then
    return 1
  fi

  cleanup_dir="$(dirname "$staging_root")"
  language_dir="$staging_root/languages/tatar"
  voice_dir="$staging_root/voices/tatar/Talgat"

  if [[ ! -d "$language_dir" || ! -d "$voice_dir" ]]; then
    echo "[codex-install] Prepared staging directory missing expected subdirectories" >&2
    rm -rf "$cleanup_dir"
    return 1
  fi

  echo "[codex-install] Pushing Tatar language resources to $remote_language_parent" >&2
  if ! push_directory_recursive "$serial" "$language_dir" "$remote_language_parent"; then
    rm -rf "$cleanup_dir"
    return 1
  fi

  echo "[codex-install] Pushing Talgat voice resources to $remote_voice_parent" >&2
  if ! push_directory_recursive "$serial" "$voice_dir" "$remote_voice_parent"; then
    rm -rf "$cleanup_dir"
    return 1
  fi

  if wait_for_talgat_voice "$serial" 120; then
    echo "[codex-install] Confirmed Talgat voice assets after direct deployment" >&2
    rm -rf "$cleanup_dir"
    return 0
  fi

  echo "[codex-install] Talgat assets not detected after pushing files" >&2
  rm -rf "$cleanup_dir"
  return 1
}

find_ui_element_center() {
  local serial="$1"
  local attribute="$2"
  local expected_value="$3"
  local timeout_seconds="${4:-30}"
  local match_mode="${5:-exact}"
  python3 - "$serial" "$attribute" "$expected_value" "$timeout_seconds" "$match_mode" <<'PY'
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
import subprocess

adb = os.environ.get("ADB_BIN", "adb")
serial, attribute, expected_value, timeout_s, match_mode = sys.argv[1:6]
timeout = float(timeout_s)
deadline = time.time() + timeout
dump_path = "/sdcard/rhvoice-ui-dump.xml"
match_mode = match_mode.lower()

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

def matches(value: str) -> bool:
    if value is None:
        return False
    if match_mode == "exact":
        return value == expected_value
    lowered_value = value.lower()
    lowered_expected = expected_value.lower()
    if match_mode == "contains":
        return lowered_expected in lowered_value
    if match_mode == "prefix":
        return lowered_value.startswith(lowered_expected)
    if match_mode == "suffix":
        return lowered_value.endswith(lowered_expected)
    if match_mode == "regex":
        try:
            return re.search(expected_value, value) is not None
        except re.error:
            return False
    # Fallback to exact comparison for unknown modes.
    return value == expected_value

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
        if matches(value):
            coords = parse_bounds(node.attrib.get("bounds", ""))
            if coords:
                print(f"{coords[0]} {coords[1]}")
                sys.exit(0)
    time.sleep(1)

sys.exit(1)
PY
}

detect_talgat_voice_path() {
  local serial="$1"
  local -a candidate_roots=(
    "/sdcard/Android/data/$RHVOICE_PACKAGE_NAME/files/RHVoice"
    "/sdcard/RHVoice"
    "/storage/emulated/0/Android/data/$RHVOICE_PACKAGE_NAME/files/RHVoice"
    "/storage/emulated/0/RHVoice"
  )
  local root script output

  script="for dir in"
  for root in "${candidate_roots[@]}"; do
    script+=" '$root'"
  done
  script+="; do if [ -d \"\$dir\" ]; then match=\"\$(find \"\$dir\" -maxdepth 4 -iname '*talgat*' -print 2>/dev/null | head -n1)\"; if [ -n \"\$match\" ]; then echo \"\$match\"; exit 0; fi; fi; done; exit 1"

  if output="$($ADB_BIN -s "$serial" shell "$script" 2>/dev/null)"; then
    # Trim potential carriage returns/newlines emitted by adb shell
    output="${output%%$'\r'*}"
    output="${output%%$'\n'*}"
    if [[ -n "$output" ]]; then
      printf '%s' "$output"
      return 0
    fi
  fi

  return 1
}

is_talgat_voice_installed() {
  local serial="$1"
  if detect_talgat_voice_path "$serial" >/dev/null; then
    return 0
  fi
  return 1
}

wait_for_talgat_voice() {
  local serial="$1"
  local timeout="${2:-240}"
  local start_seconds=$SECONDS
  local detected_path=""

  while (( SECONDS - start_seconds < timeout )); do
    dismiss_system_alerts "$serial" || true
    if detected_path="$(detect_talgat_voice_path "$serial")"; then
      if [[ -n "$detected_path" ]]; then
        echo "[codex-install] Confirmed Talgat voice assets under $detected_path" >&2
      fi
      return 0
    fi
    sleep 3
  done

  return 1
}

dismiss_system_alerts() {
  local serial="$1"
  local coords=""
  local -a wait_variants=("Wait" "Подождать")

  for label in "${wait_variants[@]}"; do
    if coords="$(find_ui_element_center "$serial" text "$label" 1 contains)"; then
      echo "[codex-install] Dismissing system alert using option '$label'" >&2
      "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
      sleep 1
      return 0
    fi
  done

  return 1
}

perform_talgat_install_flow() {
  local serial="$1"
  local launch_intent="$RHVOICE_PACKAGE_NAME/.MainActivity"
  echo "[codex-install] Ensuring RHVoice Talgat voice is installed on $serial" >&2
  dismiss_system_alerts "$serial" || true
  "$ADB_BIN" -s "$serial" shell am force-stop "$RHVOICE_PACKAGE_NAME" >/dev/null 2>&1 || true
  if ! "$ADB_BIN" -s "$serial" shell am start -a android.intent.action.MAIN -n "$launch_intent" >/dev/null 2>&1; then
    echo "[codex-install] Failed to launch RHVoice UI on $serial" >&2
    return 1
  fi

  "$ADB_BIN" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$serial" shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true

  local -a language_variants=("Tatar" "Татар" "Татарский" "Татар теле")
  local -a voice_variants=("Talgat" "Талгат")
  local coords=""
  local attempt=0
  local max_scroll_attempts=12

  # Try to locate the Tatar language entry, scrolling through the list if necessary.
  while [[ $attempt -lt $max_scroll_attempts ]]; do
    dismiss_system_alerts "$serial" || true
    for entry in "${language_variants[@]}"; do
      if coords="$(find_ui_element_center "$serial" text "$entry" 5 contains)"; then
        echo "[codex-install] Located language entry '$entry' while searching for Tatar" >&2
        attempt=$max_scroll_attempts
        break
      fi
    done
    ((attempt+=1))
    if [[ -n "$coords" ]]; then
      break
    fi
    if [[ $attempt -le $((max_scroll_attempts / 2)) ]]; then
      "$ADB_BIN" -s "$serial" shell input swipe 540 1600 540 600 300 >/dev/null 2>&1 || true
    else
      "$ADB_BIN" -s "$serial" shell input swipe 540 600 540 1600 300 >/dev/null 2>&1 || true
    fi
    sleep 1
  done

  if [[ -z "$coords" ]]; then
    echo "[codex-install] Unable to locate the Tatar language entry in RHVoice UI" >&2
    return 1
  fi

  "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
  sleep 1

  coords=""
  attempt=0
  while [[ $attempt -lt $max_scroll_attempts ]]; do
    dismiss_system_alerts "$serial" || true
    for entry in "${voice_variants[@]}"; do
      if coords="$(find_ui_element_center "$serial" text "$entry" 5 contains)"; then
        echo "[codex-install] Located voice entry '$entry' in RHVoice UI" >&2
        attempt=$max_scroll_attempts
        break
      fi
    done
    ((attempt+=1))
    if [[ -n "$coords" ]]; then
      break
    fi
    if [[ $attempt -le $((max_scroll_attempts / 2)) ]]; then
      "$ADB_BIN" -s "$serial" shell input swipe 540 1600 540 600 300 >/dev/null 2>&1 || true
    else
      "$ADB_BIN" -s "$serial" shell input swipe 540 600 540 1600 300 >/dev/null 2>&1 || true
    fi
    sleep 1
  done

  if [[ -z "$coords" ]]; then
    echo "[codex-install] Unable to locate the Talgat voice entry in RHVoice UI" >&2
    return 1
  fi

  "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
  sleep 2

  # Determine if the voice is already installed by looking for an "Uninstall" button.
  if find_ui_element_center "$serial" "content-desc" "Uninstall" 5 contains >/dev/null 2>&1 ||
     find_ui_element_center "$serial" text "Uninstall" 5 contains >/dev/null 2>&1 ||
     find_ui_element_center "$serial" "content-desc" "Удалить" 5 contains >/dev/null 2>&1 ||
     find_ui_element_center "$serial" text "Удалить" 5 contains >/dev/null 2>&1; then
    echo "[codex-install] Talgat voice already installed; skipping download" >&2
  else
    coords=""
    if find_ui_element_center "$serial" "content-desc" "Install" 30 contains >/dev/null 2>&1 ||
       find_ui_element_center "$serial" text "Install" 30 contains >/dev/null 2>&1 ||
       find_ui_element_center "$serial" "content-desc" "Установить" 30 contains >/dev/null 2>&1 ||
       find_ui_element_center "$serial" text "Установить" 30 contains >/dev/null 2>&1; then
      if coords="$(find_ui_element_center "$serial" "content-desc" "Install" 30 contains)"; then
        echo "[codex-install] Triggering Talgat voice download" >&2
        "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
      elif coords="$(find_ui_element_center "$serial" text "Install" 30 contains)"; then
        echo "[codex-install] Triggering Talgat voice download" >&2
        "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
      elif coords="$(find_ui_element_center "$serial" "content-desc" "Установить" 30 contains)"; then
        echo "[codex-install] Triggering Talgat voice download" >&2
        "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
      elif coords="$(find_ui_element_center "$serial" text "Установить" 30 contains)"; then
        echo "[codex-install] Triggering Talgat voice download" >&2
        "$ADB_BIN" -s "$serial" shell input tap $coords >/dev/null 2>&1 || true
      fi
      if [[ -z "$coords" ]]; then
        echo "[codex-install] Located install action but could not determine tap coordinates" >&2
        return 1
      fi
      dismiss_system_alerts "$serial" || true
    else
      echo "[codex-install] Unable to locate the Install button for Talgat voice" >&2
      return 1
    fi

    if ! find_ui_element_center "$serial" "content-desc" "Uninstall" 240 contains >/dev/null 2>&1 &&
       ! find_ui_element_center "$serial" text "Uninstall" 240 contains >/dev/null 2>&1 &&
       ! find_ui_element_center "$serial" "content-desc" "Удалить" 240 contains >/dev/null 2>&1 &&
       ! find_ui_element_center "$serial" text "Удалить" 240 contains >/dev/null 2>&1; then
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

install_talgat_voice() {
  local serial="$1"
  local max_attempts="${CODEX_RHVOICE_TALGAT_ATTEMPTS:-3}"
  local attempt=1

  if is_talgat_voice_installed "$serial"; then
    local existing_path
    existing_path="$(detect_talgat_voice_path "$serial" || true)"
    if [[ -n "$existing_path" ]]; then
      echo "[codex-install] Talgat voice assets already present at $existing_path" >&2
    else
      echo "[codex-install] Talgat voice already present on device" >&2
    fi
    return 0
  fi

  while (( attempt <= max_attempts )); do
    echo "[codex-install] Starting Talgat voice installation attempt $attempt/$max_attempts" >&2
    if perform_talgat_install_flow "$serial"; then
      if wait_for_talgat_voice "$serial" 300; then
        return 0
      fi
      echo "[codex-install] Talgat voice assets not detected after attempt $attempt" >&2
    else
      echo "[codex-install] Attempt $attempt to drive the RHVoice UI for Talgat install failed" >&2
    fi
    ((attempt+=1))
    sleep 5
  done

  echo "[codex-install] Exhausted attempts to install Talgat voice" >&2
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

ensure_device_responsive() {
  local attempt=0
  local max_attempts=2

  while [[ $attempt -lt $max_attempts ]]; do
    local serial="$target_serial"
    local exit_code=0
    if command -v timeout >/dev/null 2>&1; then
      timeout 15s "$ADB_BIN" -s "$serial" shell echo responsiveness-check >/dev/null 2>&1 || exit_code=$?
    else
      "$ADB_BIN" -s "$serial" shell echo responsiveness-check >/dev/null 2>&1 || exit_code=$?
    fi

    if [[ $exit_code -eq 0 ]]; then
      return 0
    fi

    ((attempt+=1))
    echo "[codex-install] Device $serial did not respond to shell commands (exit=$exit_code); attempt $attempt" >&2

    if [[ "$serial" =~ ^emulator-[0-9]+$ ]]; then
      echo "[codex-install] Restarting unresponsive emulator $serial" >&2
      "$ADB_BIN" -s "$serial" emu kill >/dev/null 2>&1 || true
      LAUNCHED_EMULATOR=0
      LAUNCHED_EMULATOR_LOG=""
      sleep 5
      if ! launch_default_emulator; then
        echo "[codex-install] Unable to relaunch emulator automatically." >&2
        break
      fi
      if ! wait_for_online_device 240; then
        echo "[codex-install] Emulator did not come online after restart." >&2
        break
      fi
      collect_devices
      if [[ ${#online_devices[@]} -eq 0 ]]; then
        echo "[codex-install] No emulator detected after restart." >&2
        break
      fi
      target_serial="${online_devices[0]}"
      echo "[codex-install] Continuing with emulator ${target_serial} after restart" >&2
      continue
    fi

    echo "[codex-install] Rebooting device $serial to recover from unresponsive state" >&2
    "$ADB_BIN" -s "$serial" reboot >/dev/null 2>&1 || true
    if ! wait_for_online_device 240; then
      echo "[codex-install] Device did not reconnect after reboot attempt." >&2
      break
    fi
    collect_devices
    if [[ ${#online_devices[@]} -eq 0 ]]; then
      echo "[codex-install] No devices detected after reboot attempt." >&2
      break
    fi
    target_serial="${online_devices[0]}"
    echo "[codex-install] Device reconnected as $target_serial" >&2
  done

  echo "[codex-install] Device $target_serial remains unresponsive after recovery attempts." >&2
  return 1
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

wait_for_device_boot() {
  local serial="$1"
  local timeout="${2:-240}"

  if ! "$ADB_BIN" -s "$serial" wait-for-device >/dev/null 2>&1; then
    return 1
  fi

  local deadline=$((SECONDS + timeout))
  while [[ $SECONDS -lt $deadline ]]; do
    local sys_boot
    local dev_boot
    local bootanim_state

    sys_boot="$($ADB_BIN -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    dev_boot="$($ADB_BIN -s "$serial" shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r')"
    bootanim_state="$($ADB_BIN -s "$serial" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')"

    if [[ "$sys_boot" == "1" || "$dev_boot" == "1" || "$bootanim_state" == "stopped" ]]; then
      if wait_for_package_service "$serial" 15; then
        return 0
      fi
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

if ! ensure_device_responsive; then
  echo "[codex-install] Unable to obtain a responsive device for installation." >&2
  exit 1
fi

echo "[codex-install] Installing $apk_to_install to $target_serial"
echo "[codex-install] Waiting for Android to finish booting and expose the package manager..."
if ! wait_for_device_boot "$target_serial" 300; then
  echo "[codex-install] Device did not finish booting or package manager unavailable." >&2
  exit 1
fi

echo "[codex-install] Package manager is available; proceeding with prerequisite installations."

if [[ "$INSTALL_RHVOICE" == "1" ]]; then
  if ! ensure_rhvoice_on_device "$target_serial" "$RHVOICE_APK_PATH" "$RHVOICE_PACKAGE_NAME"; then
    echo "[codex-install] Unable to prepare RHVoice on $target_serial" >&2
    exit 1
  fi
  if ! ensure_talgat_voice_assets "$target_serial" "$TALGAT_LANGUAGE_ARCHIVE_PATH" "$TALGAT_VOICE_ARCHIVE_PATH"; then
    echo "[codex-install] Direct deployment of Talgat assets failed; attempting UI automation fallback" >&2
    if ! install_talgat_voice "$target_serial"; then
      echo "[codex-install] Failed to automate Talgat voice installation on $target_serial" >&2
      exit 1
    fi
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
