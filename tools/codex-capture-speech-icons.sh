#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
OUTPUT_DIR="${CODEX_SPEECH_CAPTURE_DIR:-$PROJECT_ROOT/.codex/speech-capture}"
PACKAGE_NAME="${CODEX_SPEECH_APP_PACKAGE:-com.example.ttreader}"
MAIN_ACTIVITY="${CODEX_SPEECH_MAIN_ACTIVITY:-.MainActivity}"
ADB_BIN="${ADB_BIN:-adb}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$OUTPUT_DIR/$TIMESTAMP"
SCREENSHOT_PREFIX="${CODEX_SPEECH_SCREENSHOT_PREFIX:-speech}"
LOG_FILE="$RUN_DIR/logcat.txt"
FILTERED_LOG_FILE="$RUN_DIR/logcat_updateSpeechButtons.txt"

mkdir -p "$RUN_DIR"

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "error: adb not found (looked for $ADB_BIN). Set ADB_BIN to override." >&2
  exit 1
fi

echo "[speech-capture] Waiting for an Android device or emulator to become ready..." >&2
"$ADB_BIN" wait-for-device >/dev/null 2>&1

# Verify that the target package is installed to provide early feedback.
if ! "$ADB_BIN" shell pm path "$PACKAGE_NAME" >/dev/null 2>&1; then
  echo "error: package $PACKAGE_NAME is not installed on the connected device." >&2
  exit 1
fi

echo "[speech-capture] Clearing existing logcat buffer" >&2
"$ADB_BIN" logcat -c || true

TARGET_COMPONENT="$PACKAGE_NAME/$MAIN_ACTIVITY"
echo "[speech-capture] Launching $TARGET_COMPONENT" >&2
"$ADB_BIN" shell am start -n "$TARGET_COMPONENT" >/dev/null 2>&1 || {
  echo "warning: failed to launch $TARGET_COMPONENT automatically; launch it manually." >&2
}

echo
cat <<INSTRUCTIONS
======================================================================
Speech UI capture helper
----------------------------------------------------------------------
This helper will collect screenshots and logcat output around manual
speech toggle testing. Follow the prompts below and press Enter once
you have performed the requested action inside the emulator.
======================================================================
INSTRUCTIONS

declare -a STEPS=(
  "Position the app on the screen showing the speech controls without starting playback yet."
  "Start speech playback using the toggle control in the app."
  "Stop the speech playback using the stop control in the app."
)

step_index=0
for prompt in "${STEPS[@]}"; do
  step_index=$((step_index + 1))
  echo
  echo "Step $step_index: $prompt"
  read -r -p "Press Enter here once this step is complete to capture a screenshot..." _
  screenshot_path="$RUN_DIR/${SCREENSHOT_PREFIX}_step${step_index}.png"
  if "$ADB_BIN" exec-out screencap -p >"$screenshot_path"; then
    echo "  Saved screenshot to $screenshot_path"
  else
    echo "  warning: failed to capture screenshot for step $step_index" >&2
  fi
  sleep 1
  echo "  Capturing incremental logcat snapshot..."
  "$ADB_BIN" logcat -d -v time | sed 's/\r$//' >"$LOG_FILE"
  grep -F "updateSpeechButtons" "$LOG_FILE" >"$FILTERED_LOG_FILE" || true
  echo "  Latest logcat saved to $LOG_FILE"
  echo "  Filtered updateSpeechButtons logs saved to $FILTERED_LOG_FILE"
  echo "  ------------------------------------------------------------"
done

echo
cat <<SUMMARY
Capture complete. Review the screenshots and log files inside:
  $RUN_DIR
The filtered log file highlights updateSpeechButtons entries to confirm
that the menu icons were refreshed during your manual actions.
SUMMARY
