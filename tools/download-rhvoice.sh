#!/usr/bin/env bash
# Downloads the latest RHVoice engine APK from the official release metadata and F-Droid mirror.
set -euo pipefail

DEST_PATH="${1:-rhvoice.apk}"
PACKAGE_ID="${CODEX_RHVOICE_PACKAGE_ID:-com.github.olga_yakovleva.rhvoice.android}"
FDROID_BASE_URL="${CODEX_RHVOICE_FDROID_BASE_URL:-https://f-droid.org/repo}"
RELEASE_TAG_OVERRIDE="${CODEX_RHVOICE_RELEASE_TAG:-}"
DEFAULT_PROXY_URL="${CODEX_PROXY_URL:-http://proxy:8080}"
RELEASE_API_URL="https://api.github.com/repos/RHVoice/RHVoice/releases/latest"

mkdir -p "$(dirname "$DEST_PATH")"

if [[ -n "$RELEASE_TAG_OVERRIDE" ]]; then
  RELEASE_API_URL="https://api.github.com/repos/RHVoice/RHVoice/releases/tags/${RELEASE_TAG_OVERRIDE}"
fi

declare -a CURL_PROXY_ARGS=()
if [[ -z "${https_proxy:-${HTTPS_PROXY:-}}" && -z "${http_proxy:-${HTTP_PROXY:-}}" ]]; then
  if [[ -n "$DEFAULT_PROXY_URL" ]]; then
    CURL_PROXY_ARGS+=("--proxy" "$DEFAULT_PROXY_URL")
  fi
fi

curl_with_optional_proxy() {
  local -a base_args=("$@")
  local exit_code

  if [[ ${#CURL_PROXY_ARGS[@]} -gt 0 ]]; then
    if curl "${CURL_PROXY_ARGS[@]}" "${base_args[@]}"; then
      return 0
    fi
    exit_code=$?
    if [[ $exit_code -eq 5 || $exit_code -eq 7 ]]; then
      echo "Warning: proxy ${DEFAULT_PROXY_URL} unreachable, retrying without proxy" >&2
    else
      return $exit_code
    fi
  fi

  curl "${base_args[@]}"
}

cleanup_files=()
cleanup() {
  local file
  for file in "${cleanup_files[@]:-}"; do
    [[ -n "$file" && -f "$file" ]] && rm -f "$file"
  done
}
trap cleanup EXIT

tmp_json="$(mktemp)"
cleanup_files+=("$tmp_json")

echo "Querying RHVoice release metadata from $RELEASE_API_URL" >&2
if ! curl_with_optional_proxy \
  -fsSL \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: uqureader-rhvoice-downloader" \
  "$RELEASE_API_URL" -o "$tmp_json"; then
  echo "Failed to download release metadata from $RELEASE_API_URL" >&2
  exit 1
fi

mapfile -t release_info < <(python - "$tmp_json" <<'PY'
import json
import sys
from pathlib import Path

with Path(sys.argv[1]).open('r', encoding='utf-8') as fh:
    data = json.load(fh)
assets = data.get('assets', [])
fdroid_url = None
for asset in assets:
    if asset.get('name') == 'fdroid.txt':
        fdroid_url = asset.get('browser_download_url')
        break

if not fdroid_url:
    sys.exit(1)

print(fdroid_url)
print(data.get('tag_name', ''))
PY
) || true

if [[ ${#release_info[@]} -lt 1 ]]; then
  echo "Release metadata did not contain an fdroid.txt asset. Please verify the release structure." >&2
  exit 1
fi

fdroid_manifest_url="${release_info[0]}"
release_tag="${release_info[1]:-}"

if [[ -z "$fdroid_manifest_url" ]]; then
  echo "Unable to locate fdroid.txt asset URL in release metadata." >&2
  exit 1
fi

tmp_fdroid="$(mktemp)"
cleanup_files+=("$tmp_fdroid")

echo "Downloading RHVoice F-Droid manifest $fdroid_manifest_url" >&2
if ! curl_with_optional_proxy -fL --retry 3 --retry-delay 2 "$fdroid_manifest_url" -o "$tmp_fdroid"; then
  echo "Failed to download fdroid manifest from $fdroid_manifest_url" >&2
  exit 1
fi

version_code=""
version_name=""
while IFS= read -r line; do
  case "$line" in
    VersionCode:*)
      version_code="${line#VersionCode: }"
      ;;
    VersionName:*)
      version_name="${line#VersionName: }"
      ;;
  esac
done <"$tmp_fdroid"

if [[ -z "$version_code" ]]; then
  echo "fdroid manifest did not include a VersionCode entry." >&2
  exit 1
fi

apk_filename="${PACKAGE_ID}_${version_code}.apk"
apk_url="$FDROID_BASE_URL/$apk_filename"

tmp_apk="$(mktemp)"
cleanup_files+=("$tmp_apk")

echo "Downloading RHVoice APK ($release_tag, version ${version_name:-unknown}) from $apk_url" >&2
if ! curl_with_optional_proxy -fL --retry 3 --retry-delay 2 "$apk_url" -o "$tmp_apk"; then
  echo "Failed to download RHVoice APK from $apk_url" >&2
  echo "Verify that version $version_code is available on $FDROID_BASE_URL." >&2
  exit 1
fi

mv "$tmp_apk" "$DEST_PATH"
cleanup_files=("$tmp_json" "$tmp_fdroid")

echo "Saved RHVoice APK to $DEST_PATH" >&2
