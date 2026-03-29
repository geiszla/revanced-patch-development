#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Install a patched APK on the connected device via ADB.
#
# Usage:
#   ./scripts/install.sh <apk-path>
#   ./scripts/install.sh output/instagram-patched.apk
#
# The app name is derived from the filename (<app>-patched.apk). If split APKs
# exist in workspace/<app>/apk/, they are automatically included.
#
# Options:
#   --downgrade    Uninstall existing app first (needed if signature differs)
#   --launch       Launch the app after installing

APK_PATH=""
APP_NAME=""
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
            elif [[ -z "$APP_NAME" ]]; then
                APP_NAME="$1"
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

# Derive app name from filename (e.g., instagram-patched.apk → instagram)
if [[ -z "$APP_NAME" ]]; then
    APP_NAME=$(basename "$APK_PATH" | sed -E 's/-patched.*\.apk$//')
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

# Collect split APKs if app-name is provided
SPLIT_APKS=()
if [[ -n "$APP_NAME" ]]; then
    SPLIT_DIR="${WORKSPACE_DIR}/${APP_NAME}/apk"
    if [[ -d "$SPLIT_DIR" ]]; then
        while IFS= read -r -d '' split; do
            SPLIT_APKS+=("$split")
        done < <(find "$SPLIT_DIR" -maxdepth 1 -name "split_*.apk" -print0)
    fi
fi

if [[ $DOWNGRADE -eq 1 ]]; then
    # Get package name from the APK itself
    PACKAGE=$(adb shell pm list packages 2>/dev/null | grep -oP "package:\K.*${APP_NAME}.*" | head -1 || true)
    if [[ -z "$PACKAGE" ]]; then
        # Fallback: extract from AndroidManifest via aapt2 or apktool
        PACKAGE=$(${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/aapt2 dump packagename "$APK_PATH" 2>/dev/null || true)
    fi
    if [[ -n "$PACKAGE" ]]; then
        log_info "Uninstalling ${PACKAGE} (--downgrade)..."
        adb uninstall "$PACKAGE" 2>/dev/null || log_warn "Uninstall failed or app not installed"
    else
        log_warn "Could not determine package name. Uninstall manually if needed."
    fi
fi

if [[ ${#SPLIT_APKS[@]} -gt 0 ]]; then
    # Re-sign all APKs (base + splits) with the same key so signatures match
    SIGNER_JAR="${TOOLS_DIR}/uber-apk-signer.jar"
    if [[ ! -f "$SIGNER_JAR" ]]; then
        log_err "uber-apk-signer not found. Run ./scripts/setup-tools.sh first."
        exit 1
    fi

    SIGN_DIR=$(mktemp -d)

    # Copy all APKs to a temp dir for signing
    cp "$APK_PATH" "$SIGN_DIR/"
    for s in "${SPLIT_APKS[@]}"; do
        cp "$s" "$SIGN_DIR/"
    done

    log_info "Re-signing all APKs with the same key..."
    java -jar "$SIGNER_JAR" -a "$SIGN_DIR" --allowResign --overwrite 2>&1 | grep -E "^(SIGN|VERIFY|SUCCESS|\[)" || true

    # Collect signed file paths
    SIGNED_BASE="$SIGN_DIR/$(basename "$APK_PATH")"
    SIGNED_SPLITS=()
    for s in "${SPLIT_APKS[@]}"; do
        SIGNED_SPLITS+=("$SIGN_DIR/$(basename "$s")")
    done

    log_info "Installing with ${#SIGNED_SPLITS[@]} split APK(s)..."
    log_info "  Base: $(basename "$APK_PATH")"
    for s in "${SIGNED_SPLITS[@]}"; do
        log_info "  Split: $(basename "$s")"
    done

    if adb install-multiple -r "$SIGNED_BASE" "${SIGNED_SPLITS[@]}"; then
        log_ok "Installation successful!"
    else
        log_err "Installation failed."
        log_info "Try with --downgrade (will remove existing app data)"
        exit 1
    fi

    rm -rf "$SIGN_DIR"
else
    # Single APK install
    log_info "Installing: ${APK_PATH}"
    if adb install -r "$APK_PATH"; then
        log_ok "Installation successful!"
    else
        log_err "Installation failed."
        log_info "If INSTALL_FAILED_MISSING_SPLIT: add the app name to include splits:"
        log_info "  $0 ${APK_PATH} <app-name>"
        log_info "If INSTALL_FAILED_UPDATE_INCOMPATIBLE: re-run with --downgrade"
        exit 1
    fi
fi

if [[ $LAUNCH -eq 1 && -n "$APP_NAME" ]]; then
    LAUNCH_PACKAGE=$(adb shell pm list packages 2>/dev/null | grep -oP "package:\K.*${APP_NAME}.*" | head -1 || true)
    if [[ -n "$LAUNCH_PACKAGE" ]]; then
        log_info "Launching ${LAUNCH_PACKAGE}..."
        adb shell monkey -p "$LAUNCH_PACKAGE" -c android.intent.category.LAUNCHER 1 2>/dev/null
    fi
fi
