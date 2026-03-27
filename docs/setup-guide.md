# ReVanced Patch Development -- Setup Guide

> Last updated: 2026-03-27

## Table of Contents

- [Overview](#overview)
- [Repositories](#repositories)
- [External Requirements](#external-requirements)
- [VSCode / Antigravity Extensions](#vscode--antigravity-extensions)
- [Setup Guide](#setup-guide)
  - [1. Install JDK 21](#1-install-jdk-21)
  - [2. Install ADB](#2-install-adb)
  - [3. Connect Device via WiFi ADB](#3-connect-device-via-wifi-adb)
  - [4. Create GitHub PAT](#4-create-github-pat)
  - [5. Download Tools](#5-download-tools)
  - [6. Set Up Patches Project](#6-set-up-patches-project)
  - [7. Verify Setup](#7-verify-setup)
- [How ReVanced Patching Works](#how-revanced-patching-works)
- [Development Workflow](#development-workflow)
- [Troubleshooting](#troubleshooting)

---

## Overview

ReVanced is an open-source framework for patching Android apps. Patches are written
in **Kotlin** and applied to APK files using the ReVanced Patcher engine. The system
supports three patch types:

- **BytecodePatch** -- modifies Dalvik bytecode (the main type)
- **ResourcePatch** -- modifies decoded APK resources (XML, drawables)
- **RawResourcePatch** -- modifies arbitrary files in the APK

Patches locate target code in obfuscated apps using a **matching/fingerprinting API**
that matches methods by access flags, return type, parameter types, string constants,
and opcode patterns -- making patches resilient to obfuscation.

---

## Repositories

### Core (required for patch development)

| Repository | URL | Purpose |
|---|---|---|
| **revanced-patcher** | https://github.com/ReVanced/revanced-patcher | Patching engine/library (Kotlin). Consumed as a Gradle dependency. |
| **revanced-patches-template** | https://github.com/ReVanced/revanced-patches-template | Template for creating your own patches project. Clone this to start. |
| **revanced-patches-gradle-plugin** | https://github.com/ReVanced/revanced-patches-gradle-plugin | Gradle plugin providing the `patches {}` and `extension {}` DSL. |
| **revanced-cli** | https://github.com/ReVanced/revanced-cli | CLI tool (v6.0.0) for applying patches to APKs. Download the JAR. |

### Reference (useful, not required to clone)

| Repository | URL | Purpose |
|---|---|---|
| **revanced-patches** | https://github.com/ReVanced/revanced-patches (or GitLab mirror: https://gitlab.com/revanced/revanced-patches) | Official patches for YouTube, Instagram, etc. Read these to learn patch patterns. |
| **revanced-library** | https://github.com/ReVanced/revanced-library | Shared utilities used by CLI and Manager. |
| **revanced-manager** | https://github.com/ReVanced/revanced-manager | Android app for applying patches (alternative to CLI). |
| **GmsCore** | https://github.com/ReVanced/GmsCore | microG fork -- required for some patched Google apps on non-rooted devices. |

### Current versions (as of 2026-03)

- Patcher: **v22.0.1** (stable)
- CLI: **v6.0.0**
- Patches Gradle Plugin: **v1.0.0-dev.11**
- Manager: **v2.5.1**

> **Note:** The official revanced-patches repo was temporarily DMCA'd on GitHub
> (March 2026). Development continues on the GitLab mirror. The counter-notice has
> been filed and the repo is expected to be restored.

---

## External Requirements

### Software (WSL / Fedora)

| Tool | Version | Purpose | Install |
|---|---|---|---|
| **JDK 21** | 17+ (21 recommended) | Build patches (Kotlin/Gradle), run CLI | `sudo dnf install java-21-openjdk-devel` |
| **ADB** | any | Install APKs on device, pull APKs | `sudo dnf install android-tools` |
| **Git** | any | Clone repos | `sudo dnf install git` (likely already installed) |
| **curl / wget** | any | Download tool JARs | Pre-installed on Fedora |
| **unzip** | any | Extract JADX | `sudo dnf install unzip` |

### Tools (downloaded by `scripts/setup-tools.sh`)

| Tool | Purpose |
|---|---|
| **Android SDK** | Compiles extensions to DEX (cmdline-tools, platform, build-tools) |
| **JADX** | Decompile APK to readable Java source (AI-friendly output) |
| **APKTool** | Decode APK resources, disassemble to Smali |
| **ReVanced CLI** | Apply patches to APKs |
| **uber-apk-signer** | Sign patched APKs (required for installation) |

### Hardware

- **Android device** with USB debugging enabled (Pixel 9 Pro XL, non-rooted)
- Device and WSL machine on the **same WiFi network** (for wireless ADB)

### Online accounts

- **GitHub account** with a Personal Access Token (PAT) with `read:packages` scope
  (required for downloading ReVanced Maven dependencies from GitHub Packages)

---

## VSCode / Antigravity Extensions

### Recommended

| Extension | ID | Purpose |
|---|---|---|
| **APKLab** | `Surendrajat.apklab` | Android RE workbench -- integrates APKTool, JADX, signing, ADB install |
| **Kotlin** | `fwcd.kotlin` | Kotlin language server (completion, go-to-definition, diagnostics) |
| **Gradle for Java** | `vscjava.vscode-gradle` | Gradle build integration |
| **GitLens** | `eamodio.gitlens` | Track modifications in decompiled code |

### Optional

| Extension | ID | Purpose |
|---|---|---|
| **Hex Editor** | `ms-vscode.hexeditor` | Binary analysis of APK contents |
| **Smali** | `LoyieKing.smalise` | Smali syntax highlighting, go-to-definition |
| **XML** | `redhat.vscode-xml` | XML formatting, validation, schema support for Android resources |

---

## Setup Guide

### 1. Install JDK 21

```bash
sudo dnf install java-21-openjdk-devel
java -version   # verify: openjdk 21.x.x
```

If you have multiple JDK versions, set the default:

```bash
sudo alternatives --config java
# Select the java-21-openjdk option
```

Set `JAVA_HOME`:

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk' >> ~/.bashrc
source ~/.bashrc
```

> **Note:** Fedora 42 does not ship JDK 17. JDK 21 works fine — ReVanced requires 17+.

### 2. Install ADB

```bash
sudo dnf install android-tools
adb version   # verify
```

### 3. Connect Device via WiFi ADB

WiFi ADB is the recommended approach for WSL2 -- it avoids all USB passthrough
complexity (WSL2 cannot directly access USB devices).

#### On the Pixel 9 Pro XL:

1. Go to **Settings > About Phone**
2. Tap **Build Number** 7 times to enable Developer Options
3. Go to **Settings > System > Developer Options**
4. Enable **USB Debugging**
5. Enable **Wireless Debugging**
6. Tap **Wireless Debugging** to enter its sub-menu
7. Tap **Pair device with pairing code** -- note the IP:port and 6-digit code

#### In WSL:

```bash
# Pair (one-time per device)
adb pair <IP>:<PAIRING_PORT>
# Enter the 6-digit code when prompted

# Connect (needed after each phone reboot)
adb connect <IP>:<CONNECTION_PORT>
# The CONNECTION_PORT is shown on the Wireless Debugging main screen
# (different from the pairing port)

# Verify
adb devices
# Should show: <IP>:<PORT>    device
```

#### Convenience alias (add to ~/.bashrc):

```bash
alias adb-connect='adb connect <YOUR_DEVICE_IP>:<YOUR_CONNECTION_PORT>'
```

#### Alternative approaches (if WiFi doesn't work):

- **Windows ADB server**: Run `adb.exe` on Windows, set
  `ADB_SERVER_SOCKET=tcp:<windows-ip>:5037` in WSL. Requires USB connection to
  Windows and matching ADB versions.
- **usbipd-win**: USB passthrough to WSL2. Install with
  `winget install dorssel.usbipd-win`. More complex but works for USB-only scenarios.

### 4. Configure GitHub Packages authentication

ReVanced libraries are published to GitHub Packages, which requires authentication
even for public packages.

If you have the `gh` CLI configured, reuse your existing token:

```bash
gh auth token
# Copy the output
```

Otherwise, create a PAT at:
https://github.com/settings/tokens/new?scopes=read:packages&description=ReVanced

Add your credentials to `patches/gradle.properties` (already gitignored):

```properties
gpr.user=your-github-username
gpr.key=your-token-here
githubPackagesUsername=your-github-username
githubPackagesPassword=your-token-here
```

Both sets are needed: `gpr.*` resolves the Gradle plugin, `githubPackages*` is used
by the plugin internally to resolve patcher dependencies.

### 5. Download Tools

```bash
cd /home/andrewg/repos/revanced-development
./scripts/setup-tools.sh
```

This downloads JADX, APKTool, ReVanced CLI, uber-apk-signer, and the Android SDK
(command-line tools, platform, build-tools) into `tools/`. It also syncs Gradle
dependency versions from `scripts/config.sh` into the patches project.

### 6. Set Up Patches Project

The patches project is already set up in the `patches/` directory (cloned from the
revanced-patches-template). To build:

```bash
cd patches
./gradlew build
```

This produces a `.rvp` (ReVanced Patches) file in `patches/patches/build/libs/`.

### 7. Verify Setup

```bash
# Check all tools
java -version
adb devices
./tools/jadx/bin/jadx --version
java -jar ./tools/revanced-cli.jar -h
java -jar ./tools/apktool.jar --version
```

---

## How ReVanced Patching Works

### Patch anatomy

A patch is a Kotlin function that uses the `bytecodePatch {}` DSL:

```kotlin
val hideElementPatch = bytecodePatch(
    name = "Hide element",
    description = "Hides a UI element from the app.",
) {
    // Declare which app/versions this patch targets
    compatibleWith("com.instagram.android")

    // Optional: include companion code (compiled to DEX, merged into app)
    extendWith("extensions/extension.rve")

    // The patch logic
    apply {
        // Find the target method using the matching API
        val targetMethod = firstMethodDeclaratively {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returnType("V")
            strings("some_unique_string_in_the_method")
        }

        // Modify the bytecode
        targetMethod.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
    }
}
```

### Matching API

The matching API finds methods in obfuscated code without relying on exact names:

- `firstMethod { ... }` -- find by exact attributes (name, class, etc.)
- `firstMethodDeclaratively { ... }` -- find by structural properties (resilient to obfuscation)
- `composingFirstMethod { ... }` -- delegate syntax for reusable matchers
- Properties: `accessFlags()`, `returnType()`, `parameterTypes()`, `strings()`, `opcodes()`

### Extensions

Extensions are companion Java/Kotlin code compiled to DEX and merged into the target
app at patch time. Use them when your patch needs runtime logic that's too complex for
inline Smali instructions:

```kotlin
// In the extension (Java/Kotlin, compiled to DEX):
public class AdBlocker {
    public static boolean shouldBlockAd() {
        return true;
    }
}

// In the patch (Kotlin):
targetMethod.addInstructions(0, """
    invoke-static {}, LAdBlocker;->shouldBlockAd()Z
    move-result v0
    if-eqz v0, :show_ad
    return-void
    :show_ad
""")
```

---

## Development Workflow

### 1. Get the APK

```bash
# Pull from device (if app is installed)
./scripts/pull-apk.sh com.instagram.android instagram

# Or manually place an APK in:
# workspace/instagram/apk/
```

### 2. Decompile

```bash
./scripts/decompile.sh workspace/instagram/apk/base.apk instagram
```

This creates:
- `workspace/instagram/decompiled/jadx/` -- readable Java source (for analysis)
- `workspace/instagram/decompiled/apktool/` -- resources + Smali (for reference)

### 3. Analyze (with AI assistance)

Open the decompiled code in your editor. Use Claude Code / Codex to search for
patterns, understand obfuscated code, and identify patch targets.

### 4. Write patches

Edit files in `patches/patches/src/main/kotlin/app/revanced/patches/<appname>/`.

### 5. Build and patch

```bash
./scripts/build.sh instagram
```

This builds your patches and applies them to the APK. Output: `output/instagram-patched.apk`

### 6. Install

```bash
./scripts/install.sh output/instagram-patched.apk
```

---

## Troubleshooting

### Gradle build fails with 401 Unauthorized
Your GitHub PAT is missing or expired. Run `gh auth refresh -s read:packages`,
then update `patches/gradle.properties` with the new token from `gh auth token`.

### SDK location not found
Run `./scripts/setup-tools.sh` to install the Android SDK. The script sets
`ANDROID_HOME` automatically. If building outside the scripts, set it manually:
`export ANDROID_HOME=/path/to/revanced-development/tools/android-sdk`

### `adb connect` fails
- Ensure phone and WSL are on the same WiFi network
- Re-enable Wireless Debugging on the phone (it can turn off after reboot)
- Check if the connection port changed (it changes on every phone reboot)

### Patch matching fails (fingerprint not found)
The target app version may have changed the method you're matching. Use JADX to
decompile the new version and update your matcher criteria.

### Patched app crashes on launch
- Check logcat: `adb logcat -s AndroidRuntime:E`
- Ensure your Smali instructions are valid (correct register count, types)
- If using extensions, verify the extension class path matches what the patch expects

### Instagram-specific notes
Instagram uses aggressive ProGuard/R8 obfuscation. Method/class names change between
versions. Always use `firstMethodDeclaratively` with structural properties (strings,
opcodes, return types) rather than relying on class/method names.
