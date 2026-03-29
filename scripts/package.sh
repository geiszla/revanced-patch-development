#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Package a patched APK with its split APKs into a single universal APK
# for easy sharing.
#
# Usage:
#   ./scripts/package.sh <app-name>
#   ./scripts/package.sh instagram
#
# This merges the patched base APK and any split APKs into a single .apk
# file, then re-signs it. The output can be shared and installed directly
# without needing split APK installers.

APP_NAME=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -*)  log_err "Unknown option: $1"; exit 1 ;;
        *)
            if [[ -z "$APP_NAME" ]]; then
                APP_NAME="$1"
            else
                log_err "Unexpected argument: $1"
                exit 1
            fi
            shift ;;
    esac
done

if [[ -z "$APP_NAME" ]]; then
    echo "Usage: $0 <app-name>"
    echo "Example: $0 instagram"
    exit 1
fi

SPLIT_DIR="${WORKSPACE_DIR}/${APP_NAME}/apk"
APKEDITOR_JAR="${TOOLS_DIR}/APKEditor.jar"
SIGNER_JAR="${TOOLS_DIR}/uber-apk-signer.jar"

# Find the patched APK (with or without version in filename)
PATCHED_APK=$(find "$OUTPUT_DIR" -maxdepth 1 -name "${APP_NAME}-*-patched.apk" -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
if [[ -z "$PATCHED_APK" || ! -f "$PATCHED_APK" ]]; then
    PATCHED_APK="${OUTPUT_DIR}/${APP_NAME}-patched.apk"
fi

# Extract version for output filename
APP_VERSION=$(get_apk_version "$PATCHED_APK")
MERGED_APK="${OUTPUT_DIR}/${APP_NAME}-${APP_VERSION}-patched-universal.apk"

# ── Check prerequisites ──
if [[ ! -f "$PATCHED_APK" ]]; then
    log_err "Patched APK not found: ${PATCHED_APK}"
    log_err "Run ./scripts/build.sh ${APP_NAME} first."
    exit 1
fi

if [[ ! -f "$APKEDITOR_JAR" ]]; then
    log_err "APKEditor not found. Run ./scripts/setup-tools.sh first."
    exit 1
fi

if [[ ! -f "$SIGNER_JAR" ]]; then
    log_err "uber-apk-signer not found. Run ./scripts/setup-tools.sh first."
    exit 1
fi

# ── Collect APKs ──
MERGE_DIR=$(mktemp -d)
cp "$PATCHED_APK" "$MERGE_DIR/base.apk"

SPLIT_COUNT=0
if [[ -d "$SPLIT_DIR" ]]; then
    while IFS= read -r -d '' split; do
        cp "$split" "$MERGE_DIR/"
        SPLIT_COUNT=$((SPLIT_COUNT + 1))
    done < <(find "$SPLIT_DIR" -maxdepth 1 -name "split_*.apk" -print0)
fi

if [[ $SPLIT_COUNT -eq 0 ]]; then
    # No splits — just copy and sign the patched APK
    log_info "No split APKs found. Signing single APK..."
    cp "$PATCHED_APK" "$MERGED_APK"
else
    # ── Merge splits into a single APK ──
    log_info "Merging base + ${SPLIT_COUNT} split APK(s) into a single APK..."
    java -jar "$APKEDITOR_JAR" m -i "$MERGE_DIR" -o "$MERGED_APK" -f
fi

# ── Re-sign the merged APK ──
log_info "Signing merged APK..."
java -jar "$SIGNER_JAR" -a "$MERGED_APK" --allowResign --overwrite 2>&1 | grep -E "SUCCESS|error" || true

rm -rf "$MERGE_DIR"

if [[ -f "$MERGED_APK" ]]; then
    SIZE=$(du -h "$MERGED_APK" | cut -f1)
    log_ok "Universal APK: ${MERGED_APK} (${SIZE})"
    log_info "This file can be shared and installed directly with:"
    log_info "  adb install ${MERGED_APK}"
else
    log_err "Packaging failed. Check APKEditor output above."
    exit 1
fi
