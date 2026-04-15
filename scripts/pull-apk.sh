#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Pull an APK from a connected Android device, or import one from a local file.
#
# Usage:
#   From device:
#     ./scripts/pull-apk.sh <package-name> <app-name>
#     ./scripts/pull-apk.sh com.instagram.android instagram
#
#   From a file (supports .apk, .apkm [APKMirror], .xapk [APKPure], .apks):
#     ./scripts/pull-apk.sh --file <path> <app-name>
#     ./scripts/pull-apk.sh --file ~/Downloads/instagram.apkm instagram
#
# Bundle formats (.apkm/.xapk/.apks) are auto-extracted into the app's apk/
# directory and split naming is normalized to split_*.apk so the rest of the
# toolchain (install.sh, package.sh) picks them up unchanged.
#
# APKs are saved to workspace/<app-name>/apk/.

FILE=""
PACKAGE=""
APP_NAME=""
FORCE=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --file)
            if [[ $# -lt 2 ]]; then log_err "--file requires a path argument"; exit 1; fi
            FILE="$2"
            shift 2 ;;
        --force) FORCE=1; shift ;;
        -*)  log_err "Unknown option: $1"; exit 1 ;;
        *)
            if [[ -n "$FILE" ]]; then
                if [[ -z "$APP_NAME" ]]; then
                    APP_NAME="$1"
                else
                    log_err "Unexpected argument: $1"; exit 1
                fi
            else
                if [[ -z "$PACKAGE" ]]; then
                    PACKAGE="$1"
                elif [[ -z "$APP_NAME" ]]; then
                    APP_NAME="$1"
                else
                    log_err "Unexpected argument: $1"; exit 1
                fi
            fi
            shift ;;
    esac
done

if [[ -n "$FILE" ]]; then
    if [[ -z "$APP_NAME" ]]; then
        echo "Usage: $0 --file <path> <app-name>"
        echo "Example: $0 --file ~/Downloads/instagram.apkm instagram"
        exit 1
    fi
else
    if [[ -z "$PACKAGE" || -z "$APP_NAME" ]]; then
        echo "Usage:"
        echo "  $0 <package-name> <app-name>          # pull from connected device"
        echo "  $0 --file <path> <app-name>           # import local file (.apk/.apkm/.xapk/.apks)"
        echo "Options:"
        echo "  --force                               # overwrite APKs already present in the target dir"
        echo "Examples:"
        echo "  $0 com.instagram.android instagram"
        echo "  $0 --file ~/Downloads/instagram.apkm instagram"
        exit 1
    fi
fi

OUT_DIR="${WORKSPACE_DIR}/${APP_NAME}/apk"
ensure_dir "$OUT_DIR"

if [[ $FORCE -eq 0 ]]; then
    EXISTING=$(find "$OUT_DIR" -maxdepth 1 -name '*.apk' | wc -l)
    if [[ $EXISTING -gt 0 ]]; then
        log_err "Target directory already contains ${EXISTING} APK file(s): ${OUT_DIR}"
        log_err "Remove them first, or re-run with --force to overwrite."
        exit 1
    fi
fi

# ───────────────────────────────────────────────────────────────────────────
# File import mode
# ───────────────────────────────────────────────────────────────────────────
if [[ -n "$FILE" ]]; then
    if ! command -v unzip &>/dev/null; then
        log_err "unzip not found. Install with: sudo dnf install unzip"
        exit 1
    fi

    # Expand ~ and resolve to absolute
    FILE="${FILE/#\~/$HOME}"
    if [[ ! -f "$FILE" ]]; then
        log_err "File not found: ${FILE}"
        exit 1
    fi

    log_info "Importing: ${FILE}"

    # .apk, .apkm, .xapk, .apks are all ZIP. A bundle's archive listing
    # contains nested .apk entries; a plain APK's does not.
    if ! unzip -tq "$FILE" &>/dev/null; then
        log_err "File is not a valid ZIP/APK: ${FILE}"
        exit 1
    fi

    INNER_APKS=$(unzip -Z1 "$FILE" 2>/dev/null | grep -E '(^|/)[^/]+\.apk$' || true)

    if [[ -n "$INNER_APKS" ]]; then
        INNER_COUNT=$(printf '%s\n' "$INNER_APKS" | wc -l)
        log_info "Detected split-APK bundle with ${INNER_COUNT} inner APK(s). Extracting..."
        unzip -oqj "$FILE" '*.apk' -d "$OUT_DIR"

        # .xapk names splits config.*.apk; other tools look for split_*.apk.
        while IFS= read -r -d '' f; do
            new="${OUT_DIR}/split_$(basename "$f")"
            mv "$f" "$new"
        done < <(find "$OUT_DIR" -maxdepth 1 -name 'config.*.apk' -print0)

        # If the bundle didn't name the primary APK "base.apk", identify it
        # via aapt2 (the base APK is the only one without a split= attribute).
        if [[ ! -f "${OUT_DIR}/base.apk" ]]; then
            aapt2="${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/aapt2"
            BASE_CANDIDATE=""
            if [[ -x "$aapt2" ]]; then
                while IFS= read -r -d '' apk; do
                    if ! "$aapt2" dump badging "$apk" 2>/dev/null | grep -q "^split="; then
                        BASE_CANDIDATE="$apk"; break
                    fi
                done < <(find "$OUT_DIR" -maxdepth 1 -name '*.apk' -print0)
            fi
            if [[ -n "$BASE_CANDIDATE" ]]; then
                log_info "Renaming $(basename "$BASE_CANDIDATE") → base.apk (identified via aapt2)"
                mv "$BASE_CANDIDATE" "${OUT_DIR}/base.apk"
            else
                log_warn "Could not identify a base APK. Extracted:"
                find "$OUT_DIR" -maxdepth 1 -name '*.apk' -printf '  %f\n'
                log_warn "Rename the primary APK to base.apk before running decompile/build."
            fi
        fi
    else
        log_info "Detected single APK."
        cp "$FILE" "${OUT_DIR}/base.apk"
    fi

    log_ok "APK(s) saved to ${OUT_DIR}/"
    ls -lh "${OUT_DIR}"/*.apk

    SPLITS=$(find "$OUT_DIR" -maxdepth 1 -name 'split_*.apk' | wc -l)
    if [[ $SPLITS -gt 0 ]]; then
        log_info "Bundle has ${SPLITS} split APK(s). Patch base.apk; install.sh includes splits automatically."
    fi

    echo ""
    log_info "Next step: ./scripts/decompile.sh ${OUT_DIR}/base.apk ${APP_NAME}"
    exit 0
fi

# ───────────────────────────────────────────────────────────────────────────
# Device pull mode
# ───────────────────────────────────────────────────────────────────────────

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

log_info "Looking up APK paths for ${PACKAGE}..."
APK_PATHS=$(adb shell pm path "$PACKAGE" 2>/dev/null | sed 's/^package://')

if [[ -z "$APK_PATHS" ]]; then
    log_err "Package not found on device: ${PACKAGE}"
    log_info "Listing similar packages:"
    adb shell pm list packages | grep -i "$(echo "$APP_NAME" | cut -c1-4)" || true
    exit 1
fi

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
