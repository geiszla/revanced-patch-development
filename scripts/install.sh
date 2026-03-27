#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Install a patched APK on the connected device via ADB.
#
# Usage:
#   ./scripts/install.sh <apk-path>
#   ./scripts/install.sh output/instagram-patched.apk
#
# Options:
#   --downgrade    Uninstall existing app first (needed if signature differs)
#   --launch       Launch the app after installing

APK_PATH=""
DOWNGRADE=0
LAUNCH=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --downgrade) DOWNGRADE=1; shift ;;
        --launch)    LAUNCH=1; shift ;;
        -*)          log_err "Unknown option: $1"; exit 1 ;;
        *)
            if [[ -z "$APK_PATH" ]]; then
                APK_PATH="$1"
            else
                log_err "Unexpected argument: $1"
                exit 1
            fi
            shift ;;
    esac
done

if [[ -z "$APK_PATH" ]]; then
    echo "Usage: $0 <apk-path> [--downgrade] [--launch]"
    exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
    log_err "APK not found: ${APK_PATH}"
    exit 1
fi

# Check ADB
if ! command -v adb &>/dev/null; then
    log_err "ADB not found. Install with: sudo dnf install android-tools"
    exit 1
fi

# Check device connection
DEVICE_COUNT=$(adb devices | grep -c 'device$' || true)
if [[ $DEVICE_COUNT -eq 0 ]]; then
    log_err "No device connected."
    log_err "Connect via WiFi: adb pair <IP>:<PORT>, then adb connect <IP>:<PORT>"
    exit 1
fi

DEVICE_ID=$(adb devices | grep 'device$' | head -1 | awk '{print $1}')
log_info "Device: ${DEVICE_ID}"

# Get package name from APK
PACKAGE=$(aapt2 dump packagename "$APK_PATH" 2>/dev/null || \
    java -jar "${TOOLS_DIR}/apktool.jar" d "$APK_PATH" -o /tmp/apktool-tmp -f -s 2>/dev/null && \
    grep 'package=' /tmp/apktool-tmp/AndroidManifest.xml | sed -E 's/.*package="([^"]+)".*/\1/' || \
    echo "")

if [[ $DOWNGRADE -eq 1 && -n "$PACKAGE" ]]; then
    log_info "Uninstalling existing app (--downgrade)..."
    adb uninstall "$PACKAGE" 2>/dev/null || log_warn "App was not installed (or uninstall failed)"
fi

log_info "Installing: ${APK_PATH}"
if adb install -r "$APK_PATH"; then
    log_ok "Installation successful!"
else
    log_err "Installation failed."
    log_info "If you get INSTALL_FAILED_UPDATE_INCOMPATIBLE, re-run with --downgrade"
    log_info "  (this will remove existing app data)"
    exit 1
fi

if [[ $LAUNCH -eq 1 && -n "$PACKAGE" ]]; then
    log_info "Launching ${PACKAGE}..."
    adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 2>/dev/null
fi
