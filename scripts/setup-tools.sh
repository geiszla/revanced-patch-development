#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Download and set up all required tools into tools/.
# Re-run this script after changing versions in config.sh to upgrade.

ensure_dir "${TOOLS_DIR}"

# ── Check system prerequisites ──
log_info "Checking system prerequisites..."
MISSING=0
check_tool "Java (JDK 17+)" java || MISSING=1
check_tool "Git" git || MISSING=1
check_tool "curl" curl || MISSING=1
check_tool "unzip" unzip || MISSING=1

if [[ $MISSING -eq 1 ]]; then
    log_err "Install missing prerequisites first. On Fedora:"
    log_err "  sudo dnf install java-21-openjdk-devel git curl unzip"
    exit 1
fi

# Check Java version is 17+
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [[ "$JAVA_MAJOR" -lt 17 ]]; then
    log_err "JDK 17+ required, found JDK ${JAVA_MAJOR}."
    log_err "Install: sudo dnf install java-21-openjdk-devel"
    exit 1
fi
log_ok "JDK ${JAVA_MAJOR} detected"

# ── Download function ──
download_if_missing() {
    local name="$1" url="$2" dest="$3"
    if [[ -f "$dest" ]]; then
        log_ok "${name} already downloaded: ${dest}"
    else
        log_info "Downloading ${name}..."
        curl -fSL --progress-bar -o "$dest" "$url"
        log_ok "${name} downloaded: ${dest}"
    fi
}

# ── JADX ──
JADX_DIR="${TOOLS_DIR}/jadx"
JADX_ZIP="${TOOLS_DIR}/jadx-${JADX_VERSION}.zip"
if [[ -f "${JADX_DIR}/bin/jadx" ]]; then
    log_ok "JADX ${JADX_VERSION} already installed"
else
    # Clean old versions
    rm -rf "${JADX_DIR}" "${TOOLS_DIR}"/jadx-*.zip
    download_if_missing "JADX ${JADX_VERSION}" "${JADX_URL}" "${JADX_ZIP}"
    log_info "Extracting JADX..."
    mkdir -p "${JADX_DIR}"
    unzip -q "${JADX_ZIP}" -d "${JADX_DIR}"
    chmod +x "${JADX_DIR}/bin/jadx" "${JADX_DIR}/bin/jadx-gui"
    rm -f "${JADX_ZIP}"
    log_ok "JADX ${JADX_VERSION} installed"
fi

# ── ReVanced CLI ──
CLI_JAR="${TOOLS_DIR}/revanced-cli.jar"
CLI_VERSIONED="${TOOLS_DIR}/revanced-cli-${REVANCED_CLI_VERSION}.jar"
if [[ -f "$CLI_VERSIONED" ]]; then
    log_ok "ReVanced CLI ${REVANCED_CLI_VERSION} already installed"
else
    rm -f "${TOOLS_DIR}"/revanced-cli*.jar
    download_if_missing "ReVanced CLI ${REVANCED_CLI_VERSION}" "${REVANCED_CLI_URL}" "${CLI_VERSIONED}"
fi
# Always symlink to unversioned name for scripts
ln -sf "$(basename "$CLI_VERSIONED")" "${CLI_JAR}"

# ── APKTool ──
APKTOOL_JAR="${TOOLS_DIR}/apktool.jar"
APKTOOL_VERSIONED="${TOOLS_DIR}/apktool-${APKTOOL_VERSION}.jar"
if [[ -f "$APKTOOL_VERSIONED" ]]; then
    log_ok "APKTool ${APKTOOL_VERSION} already installed"
else
    rm -f "${TOOLS_DIR}"/apktool*.jar
    download_if_missing "APKTool ${APKTOOL_VERSION}" "${APKTOOL_URL}" "${APKTOOL_VERSIONED}"
fi
ln -sf "$(basename "$APKTOOL_VERSIONED")" "${APKTOOL_JAR}"

# ── uber-apk-signer ──
SIGNER_JAR="${TOOLS_DIR}/uber-apk-signer.jar"
SIGNER_VERSIONED="${TOOLS_DIR}/uber-apk-signer-${UBER_APK_SIGNER_VERSION}.jar"
if [[ -f "$SIGNER_VERSIONED" ]]; then
    log_ok "uber-apk-signer ${UBER_APK_SIGNER_VERSION} already installed"
else
    rm -f "${TOOLS_DIR}"/uber-apk-signer*.jar
    download_if_missing "uber-apk-signer ${UBER_APK_SIGNER_VERSION}" "${UBER_APK_SIGNER_URL}" "${SIGNER_VERSIONED}"
fi
ln -sf "$(basename "$SIGNER_VERSIONED")" "${SIGNER_JAR}"

# ── Sync Gradle config files from config.sh versions ──
log_info "Syncing Gradle dependency versions from config.sh..."

if [[ -f "${PATCHES_DIR}/gradlew" ]]; then
    cd "${PATCHES_DIR}"
    ./gradlew wrapper --gradle-version "${GRADLE_VERSION}" --quiet
    cd "${PROJECT_ROOT}"
    log_ok "Gradle wrapper → ${GRADLE_VERSION}"
fi

VERSIONS_TOML="${PATCHES_DIR}/gradle/libs.versions.toml"
if [[ -f "$VERSIONS_TOML" ]]; then
    sed -i "s|revanced-patcher = \".*\"|revanced-patcher = \"${PATCHER_VERSION}\"|" "$VERSIONS_TOML"
    sed -i "s|smali = \".*\"|smali = \"${SMALI_VERSION}\"|" "$VERSIONS_TOML"
    log_ok "Patcher → ${PATCHER_VERSION}, Smali → ${SMALI_VERSION}"
fi

SETTINGS_KTS="${PATCHES_DIR}/settings.gradle.kts"
if [[ -f "$SETTINGS_KTS" ]]; then
    sed -i "s|id(\"app.revanced.patches\") version \".*\"|id(\"app.revanced.patches\") version \"${PATCHES_PLUGIN_VERSION}\"|" "$SETTINGS_KTS"
    log_ok "Patches plugin → ${PATCHES_PLUGIN_VERSION}"
fi

# ── Android SDK ──
SDKMANAGER="${ANDROID_SDK_DIR}/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -f "$SDKMANAGER" ]]; then
    log_info "Downloading Android command-line tools (bootstrap)..."
    CMDLINE_ZIP="${TOOLS_DIR}/cmdline-tools.zip"
    curl -fSL --progress-bar -o "$CMDLINE_ZIP" \
        "https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
    ensure_dir "${ANDROID_SDK_DIR}/cmdline-tools"
    unzip -q "$CMDLINE_ZIP" -d "${ANDROID_SDK_DIR}/cmdline-tools"
    mv "${ANDROID_SDK_DIR}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_DIR}/cmdline-tools/latest"
    rm -f "$CMDLINE_ZIP"
    log_ok "Command-line tools bootstrapped"
fi

# Update cmdline-tools to latest and install required SDK components
log_info "Updating Android SDK components..."
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
"$SDKMANAGER" "cmdline-tools;latest" "platforms;android-${ANDROID_COMPILE_SDK}" "build-tools;${ANDROID_BUILD_TOOLS}" | grep -v "^\[" || true

# Clean up duplicate cmdline-tools created by sdkmanager self-update
LATEST2="${ANDROID_SDK_DIR}/cmdline-tools/latest-2"
if [[ -d "$LATEST2" ]]; then
    rm -rf "${ANDROID_SDK_DIR}/cmdline-tools/latest"
    mv "$LATEST2" "${ANDROID_SDK_DIR}/cmdline-tools/latest"
    log_info "Cleaned up cmdline-tools duplicate"
fi

log_ok "Android SDK ready (compileSdk ${ANDROID_COMPILE_SDK})"

# ── ADB check (optional, only needed for device interaction) ──
if command -v adb &>/dev/null; then
    log_ok "ADB found: $(adb version | head -1)"
else
    log_warn "ADB not installed. Install with: sudo dnf install android-tools"
    log_warn "ADB is only needed for device interaction (pull-apk, install)."
fi

# ── Summary ──
echo ""
log_info "=== Tool versions ==="
echo "  JADX:             ${JADX_VERSION}"
echo "  ReVanced CLI:     ${REVANCED_CLI_VERSION}"
echo "  APKTool:          ${APKTOOL_VERSION}"
echo "  uber-apk-signer:  ${UBER_APK_SIGNER_VERSION}"
echo "  Android SDK:      compileSdk ${ANDROID_COMPILE_SDK}, build-tools ${ANDROID_BUILD_TOOLS}"
echo ""
log_info "=== Gradle dependency versions ==="
echo "  Gradle:           ${GRADLE_VERSION}"
echo "  Patcher:          ${PATCHER_VERSION}"
echo "  Smali:            ${SMALI_VERSION}"
echo "  Patches plugin:   ${PATCHES_PLUGIN_VERSION}"
echo ""
log_info "To upgrade: edit versions in scripts/config.sh, then re-run this script."
log_ok "All tools ready."
