#!/usr/bin/env bash
# Central version configuration for all tools and dependencies.
# Edit versions here, then run ./scripts/setup-tools.sh to update.

# ── Project paths ──
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${PROJECT_ROOT}/tools"
WORKSPACE_DIR="${PROJECT_ROOT}/workspace"
OUTPUT_DIR="${PROJECT_ROOT}/output"
PATCHES_DIR="${PROJECT_ROOT}/patches"

# ── Tool versions (edit these to upgrade) ──
JADX_VERSION="1.5.5"
REVANCED_CLI_VERSION="6.0.0"
APKTOOL_VERSION="3.0.1"
UBER_APK_SIGNER_VERSION="1.3.0"

# ── Download URLs (derived from versions) ──
JADX_URL="https://github.com/skylot/jadx/releases/download/v${JADX_VERSION}/jadx-${JADX_VERSION}.zip"
REVANCED_CLI_URL="https://github.com/ReVanced/revanced-cli/releases/download/v${REVANCED_CLI_VERSION}/revanced-cli-${REVANCED_CLI_VERSION}-all.jar"
APKTOOL_URL="https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar"
UBER_APK_SIGNER_URL="https://github.com/patrickfav/uber-apk-signer/releases/download/v${UBER_APK_SIGNER_VERSION}/uber-apk-signer-${UBER_APK_SIGNER_VERSION}.jar"

# ── Android SDK ──
ANDROID_SDK_DIR="${PROJECT_ROOT}/tools/android-sdk"
ANDROID_COMPILE_SDK="34"
ANDROID_BUILD_TOOLS="34.0.0"
export ANDROID_HOME="${ANDROID_SDK_DIR}"

# ── Gradle/Kotlin dependency versions ──
# These are the source of truth. Run ./scripts/setup-tools.sh to sync them
# into the Gradle config files automatically.
GRADLE_VERSION="9.4.1"
PATCHER_VERSION="22.0.1"
SMALI_VERSION="3.0.9"
PATCHES_PLUGIN_VERSION="1.0.0-dev.11"

# ── Environment ──
# Load .env if it exists (contains GITHUB_USERNAME, GITHUB_TOKEN)
if [[ -f "${PROJECT_ROOT}/.env" ]]; then
    set -a
    source "${PROJECT_ROOT}/.env"
    set +a
fi

# ── Helper functions ──
log_info() { echo -e "\033[1;34m[INFO]\033[0m $*"; }
log_ok()   { echo -e "\033[1;32m[OK]\033[0m $*"; }
log_warn() { echo -e "\033[1;33m[WARN]\033[0m $*"; }
log_err()  { echo -e "\033[1;31m[ERROR]\033[0m $*"; }

check_tool() {
    local name="$1" cmd="$2"
    if command -v "$cmd" &>/dev/null; then
        log_ok "$name found: $(command -v "$cmd")"
        return 0
    else
        log_err "$name not found. Install it first (see docs/setup-guide.md)."
        return 1
    fi
}

ensure_dir() {
    mkdir -p "$1"
}
