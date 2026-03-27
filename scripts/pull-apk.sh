#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Pull an installed APK from the connected Android device.
#
# Usage:
#   ./scripts/pull-apk.sh <package-name> <app-name>
#   ./scripts/pull-apk.sh com.instagram.android instagram
#
# The APK is saved to workspace/<app-name>/apk/

PACKAGE=""
APP_NAME=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -*)  log_err "Unknown option: $1"; exit 1 ;;
        *)
            if [[ -z "$PACKAGE" ]]; then
                PACKAGE="$1"
            elif [[ -z "$APP_NAME" ]]; then
                APP_NAME="$1"
            else
                log_err "Unexpected argument: $1"
                exit 1
            fi
            shift ;;
    esac
done

if [[ -z "$PACKAGE" || -z "$APP_NAME" ]]; then
    echo "Usage: $0 <package-name> <app-name>"
    echo "Example: $0 com.instagram.android instagram"
    exit 1
fi

# Check ADB
if ! command -v adb &>/dev/null; then
    log_err "ADB not found. Install with: sudo dnf install android-tools"
    exit 1
fi

DEVICE_COUNT=$(adb devices | grep -c 'device$' || true)
if [[ $DEVICE_COUNT -eq 0 ]]; then
    log_err "No device connected."
    log_err "Connect via WiFi: adb pair <IP>:<PORT>, then adb connect <IP>:<PORT>"
    exit 1
fi

# Get APK paths from device
log_info "Looking up APK paths for ${PACKAGE}..."
APK_PATHS=$(adb shell pm path "$PACKAGE" 2>/dev/null | sed 's/^package://')

if [[ -z "$APK_PATHS" ]]; then
    log_err "Package not found on device: ${PACKAGE}"
    log_info "Listing similar packages:"
    adb shell pm list packages | grep -i "$(echo "$APP_NAME" | cut -c1-4)" || true
    exit 1
fi

# Create output directory
OUT_DIR="${WORKSPACE_DIR}/${APP_NAME}/apk"
ensure_dir "$OUT_DIR"

# Pull each APK (apps may have split APKs)
PULLED=0
while IFS= read -r apk_path; do
    apk_path=$(echo "$apk_path" | tr -d '\r')
    if [[ -z "$apk_path" ]]; then continue; fi

    filename=$(basename "$apk_path")
    dest="${OUT_DIR}/${filename}"

    log_info "Pulling: ${apk_path}"
    adb pull "$apk_path" "$dest"
    PULLED=$((PULLED + 1))
done <<< "$APK_PATHS"

log_ok "Pulled ${PULLED} APK file(s) to ${OUT_DIR}/"
ls -lh "${OUT_DIR}"/*.apk

if [[ $PULLED -gt 1 ]]; then
    log_info "This app uses split APKs. The base.apk is the main one."
    log_info "For patching, use the base.apk: ${OUT_DIR}/base.apk"
fi

echo ""
log_info "Next step: ./scripts/decompile.sh ${OUT_DIR}/base.apk ${APP_NAME}"
