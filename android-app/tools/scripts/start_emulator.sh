#!/usr/bin/env bash
set -euo pipefail

print_usage() {
  cat <<'USAGE'
Usage: start_emulator.sh [options] [-- emulator-flags]

Options:
  --avd NAME              Use the given AVD (default: codex-avd-api28).
  --system-image PATH     Install the system image if the AVD is missing.
  --create-if-missing     Create the AVD when it is absent (requires --system-image).
  --sdk-root PATH         Override ANDROID_SDK_ROOT for this invocation.
  --with-gui              Run the emulator with a visible window under Xvfb.
  --headless              Force headless mode (adds -no-window) even if --with-gui was set earlier.
  -h, --help              Show this help message and exit.

Any arguments following "--" are passed directly to the emulator binary.
USAGE
}

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-}"
AVD_NAME="codex-avd-api28"
SYSTEM_IMAGE=""
CREATE_IF_MISSING=0
WITH_GUI=0
FORCE_HEADLESS=0
EXTRA_FLAGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd)
      [[ $# -ge 2 ]] || { echo "--avd requires a value" >&2; exit 1; }
      AVD_NAME="$2"
      shift 2
      ;;
    --system-image)
      [[ $# -ge 2 ]] || { echo "--system-image requires a value" >&2; exit 1; }
      SYSTEM_IMAGE="$2"
      shift 2
      ;;
    --create-if-missing)
      CREATE_IF_MISSING=1
      shift
      ;;
    --sdk-root)
      [[ $# -ge 2 ]] || { echo "--sdk-root requires a value" >&2; exit 1; }
      ANDROID_SDK_ROOT="$2"
      shift 2
      ;;
    --with-gui)
      WITH_GUI=1
      shift
      ;;
    --headless)
      FORCE_HEADLESS=1
      shift
      ;;
    --)
      shift
      EXTRA_FLAGS+=("$@")
      break
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    *)
      EXTRA_FLAGS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "$ANDROID_SDK_ROOT" ]]; then
  if [[ -d /usr/lib/android-sdk ]]; then
    ANDROID_SDK_ROOT=/usr/lib/android-sdk
  else
    echo "ANDROID_SDK_ROOT is not set and the default SDK path was not found" >&2
    exit 1
  fi
fi

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

EMU_BIN="$ANDROID_SDK_ROOT/emulator/emulator"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
AVD_PATH="$AVD_HOME/${AVD_NAME}.avd"

if [[ ! -x "$EMU_BIN" ]]; then
  echo "Could not find emulator binary at $EMU_BIN" >&2
  exit 1
fi

ensure_system_image() {
  local image="$1"
  if [[ -z "$image" ]]; then
    echo "No system image specified; cannot create missing AVD" >&2
    exit 1
  fi
  if ! sdkmanager --list | grep -q "^    $image"; then
    yes | sdkmanager "$image"
  fi
}

if [[ ! -d "$AVD_PATH" ]]; then
  if (( CREATE_IF_MISSING )); then
    ensure_system_image "$SYSTEM_IMAGE"
    echo "Creating AVD $AVD_NAME"
    yes | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" --force
  else
    echo "AVD $AVD_NAME not found at $AVD_PATH" >&2
    echo "Use --create-if-missing with --system-image to create it." >&2
    exit 1
  fi
fi

DEFAULT_FLAGS=(-no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect)
if (( WITH_GUI )) && (( ! FORCE_HEADLESS )); then
  HEADLESS=0
else
  HEADLESS=1
fi

if (( HEADLESS )); then
  DEFAULT_FLAGS+=(-no-window)
fi

run_emulator() {
  local -a cmd=("$EMU_BIN" -avd "$AVD_NAME" "${DEFAULT_FLAGS[@]}" "${EXTRA_FLAGS[@]}")
  if (( WITH_GUI )) && (( ! HEADLESS )); then
    if command -v xvfb-run >/dev/null 2>&1; then
      exec xvfb-run -a -s "-screen 0 1280x800x24" "${cmd[@]}"
    else
      echo "xvfb-run not available; running emulator with a real window" >&2
      exec "${cmd[@]}"
    fi
  else
    exec "${cmd[@]}"
  fi
}

run_emulator
