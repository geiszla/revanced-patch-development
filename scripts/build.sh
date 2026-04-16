#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Build patches and apply them to an APK.
#
# Usage:
#   ./scripts/build.sh <app-name> [--patches-only] [--apply-only] [--include "Patch Name"] [--exclude "Patch Name"]
#
# Examples:
#   ./scripts/build.sh instagram                          # Build + apply all compatible patches
#   ./scripts/build.sh instagram --patches-only            # Only build the .rvp, don't apply
#   ./scripts/build.sh instagram --include "Hide element"  # Only apply specific patch(es)
#
# The .rvp bundle contains patches for every app in the project. The CLI only
# applies patches whose `compatibleWith(...)` matches the target APK's
# package, so running this against a different app is safe — it just no-ops
# the unrelated patches.

APP_NAME=""
PATCHES_ONLY=0
APPLY_ONLY=0
INCLUDE_PATCHES=()
EXCLUDE_PATCHES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --patches-only) PATCHES_ONLY=1; shift ;;
        --apply-only)   APPLY_ONLY=1; shift ;;
        --include)      INCLUDE_PATCHES+=("$2"); shift 2 ;;
        --exclude)      EXCLUDE_PATCHES+=("$2"); shift 2 ;;
        -*)             log_err "Unknown option: $1"; exit 1 ;;
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
    echo "Usage: $0 <app-name> [--patches-only] [--apply-only] [--include \"name\"] [--exclude \"name\"]"
    exit 1
fi

CLI_JAR="${TOOLS_DIR}/revanced-cli.jar"
APK_DIR="${WORKSPACE_DIR}/${APP_NAME}/apk"
RVP_FILE="${PATCHES_DIR}/patches/build/libs/patches-1.0.0-dev.1.rvp"

# ── Step 1: Build patches (.rvp) ──
if [[ $APPLY_ONLY -eq 0 ]]; then
    log_info "Building patches project..."

    if [[ ! -f "${PATCHES_DIR}/gradlew" ]]; then
        log_err "Patches project not found at ${PATCHES_DIR}"
        log_err "Run the initial setup first (clone revanced-patches-template)."
        exit 1
    fi

    cd "${PATCHES_DIR}"
    ./gradlew build -x test
    cd "${PROJECT_ROOT}"

    # Find the built .rvp file
    RVP_CANDIDATES=("${PATCHES_DIR}"/patches/build/libs/*.rvp)
    if [[ ${#RVP_CANDIDATES[@]} -eq 0 || ! -f "${RVP_CANDIDATES[0]}" ]]; then
        log_err "No .rvp file found after build. Check Gradle output for errors."
        exit 1
    fi
    RVP_FILE="${RVP_CANDIDATES[0]}"
    log_ok "Patches built: ${RVP_FILE}"

    if [[ $PATCHES_ONLY -eq 1 ]]; then
        log_ok "Done (--patches-only). RVP file: ${RVP_FILE}"
        exit 0
    fi
fi

# ── Step 2: Find the APK ──
if [[ ! -d "$APK_DIR" ]]; then
    log_err "APK directory not found: ${APK_DIR}"
    log_err "Place your APK in ${APK_DIR}/ or run: ./scripts/add-app.sh <package> ${APP_NAME}"
    exit 1
fi

# Find the APK to patch (prefer base.apk, ignore split APKs)
if [[ -f "${APK_DIR}/base.apk" ]]; then
    APK_FILE="${APK_DIR}/base.apk"
else
    # Fallback: find the first non-split APK
    APK_FILE=$(find "$APK_DIR" -maxdepth 1 -name "*.apk" ! -name "split_*" -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-)
fi
if [[ -z "$APK_FILE" || ! -f "$APK_FILE" ]]; then
    log_err "No APK found in ${APK_DIR}/"
    exit 1
fi
log_info "Using APK: ${APK_FILE}"

# Surface the target package so the user can see which patches the CLI's
# compatibleWith filter will select from the bundled .rvp.
AAPT2="${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/aapt2"
if [[ -x "$AAPT2" ]]; then
    APK_PACKAGE=$("$AAPT2" dump packagename "$APK_FILE" 2>/dev/null || true)
    if [[ -n "$APK_PACKAGE" ]]; then
        log_info "Target package: ${APK_PACKAGE} (only patches with compatibleWith(\"${APK_PACKAGE}\") apply)"
    fi
fi

# ── Step 3: Apply patches ──
if [[ ! -f "$CLI_JAR" ]]; then
    log_err "ReVanced CLI not found. Run ./scripts/setup-tools.sh first."
    exit 1
fi

ensure_dir "${OUTPUT_DIR}"
APP_VERSION=$(get_apk_version "$APK_FILE")
OUTPUT_APK="${OUTPUT_DIR}/${APP_NAME}-${APP_VERSION}-patched.apk"
log_info "App version: ${APP_VERSION}"

# Build CLI arguments
CLI_ARGS=(
    patch
    -bp "${RVP_FILE}"
)

# Add include/exclude filters
if [[ ${#INCLUDE_PATCHES[@]} -gt 0 ]]; then
    CLI_ARGS+=(--exclusive)
    for p in "${INCLUDE_PATCHES[@]}"; do
        CLI_ARGS+=(-e "$p")
    done
fi
for p in "${EXCLUDE_PATCHES[@]}"; do
    CLI_ARGS+=(-d "$p")
done

# Copy base APK to a temp location so the CLI doesn't pick up split APKs
TEMP_APK="${OUTPUT_DIR}/${APP_NAME}-input.apk"
cp "$APK_FILE" "$TEMP_APK"

CLI_ARGS+=(-o "${OUTPUT_APK}" "${TEMP_APK}")

log_info "Applying patches with ReVanced CLI..."
java -jar "${CLI_JAR}" "${CLI_ARGS[@]}"
rm -f "$TEMP_APK"

if [[ -f "$OUTPUT_APK" ]]; then
    log_ok "Patched APK: ${OUTPUT_APK}"
else
    log_err "Patched APK not found. Check CLI output above."
    exit 1
fi

echo ""
log_info "Next step: ./scripts/install.sh ${OUTPUT_APK}"
