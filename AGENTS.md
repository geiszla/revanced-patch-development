# ReVanced Patch Development Project

## What this project is
A development environment for writing custom ReVanced patches for Android apps,
starting with Instagram. Patches are written in Kotlin using the ReVanced Patcher API.

## Directory structure
```
revanced-development/
  docs/setup-guide.md       # Comprehensive setup and reference guide
  patches/                  # Kotlin/Gradle patches project (from revanced-patches-template)
    patches/src/main/kotlin/app/revanced/patches/
      instagram/            # Instagram patches
      <other-app>/          # Future app patches go in new subdirectories
    extensions/extension/   # Runtime code compiled to DEX, merged into target apps
    settings.gradle.kts     # Applies revanced patches gradle plugin
    gradle.properties       # GitHub Packages auth (gpr.user, gpr.key)
    gradle/libs.versions.toml  # Patcher + plugin version catalog
  workspace/<app>/          # Per-app working directories
    apk/                    # Original APK files
    decompiled/jadx/        # JADX decompiled Java source (readable, for analysis)
    decompiled/apktool/     # APKTool output (resources + Smali)
  tools/                    # Downloaded tool JARs/binaries (gitignored)
  output/                   # Patched APK output (gitignored)
  scripts/
    config.sh               # Central version config for ALL tools and dependencies
    setup-tools.sh           # Download/update tools (reads versions from config.sh)
    decompile.sh             # Decompile APK with JADX + APKTool
    build.sh                 # Build patches and apply to APK
    install.sh               # Install patched APK on device
    pull-apk.sh              # Pull APK from device
```

## Upgrading dependencies
All tool and dependency versions are managed in `scripts/config.sh`. To upgrade:
1. Edit versions in `scripts/config.sh` (JADX, CLI, APKTool, signer)
2. Run `./scripts/setup-tools.sh` to re-download updated tools
3. For Gradle dependencies (patcher, patches plugin), edit `patches/gradle/libs.versions.toml`
4. Run `cd patches && ./gradlew build` to verify compatibility

## Key commands
- `./scripts/setup-tools.sh` -- download/update JADX, ReVanced CLI, APKTool
- `./scripts/pull-apk.sh <package> <app-name>` -- pull APK from device
- `./scripts/decompile.sh <apk-path> <app-name>` -- decompile with JADX + APKTool
- `./scripts/build.sh <app-name>` -- build patches and apply to APK
- `./scripts/install.sh <apk-path>` -- install patched APK on device

## How patches work
Patches use the `bytecodePatch {}` Kotlin DSL. They find target methods using
structural matching (strings, opcodes, types) rather than names (which are obfuscated).
See `docs/setup-guide.md` for detailed examples.

## When writing patches
- Use `firstMethodDeclaratively { }` for matching -- it's resilient to obfuscation
- Match on `strings()`, `opcodes()`, `returnType()`, `accessFlags()` -- not class/method names
- For runtime logic, add extension code in `extensions/extension/src/main/java/`
- Inline Smali instructions go in `addInstructions()` calls
- Test patches by running `./scripts/build.sh <app> && ./scripts/install.sh output/<app>-patched.apk`

## When analyzing decompiled code
- Decompiled Java source is in `workspace/<app>/decompiled/jadx/sources/`
- Resources (layouts, strings) are in `workspace/<app>/decompiled/jadx/resources/`
- Search for UI elements in layout XMLs, string resources, and Java source
- Instagram is heavily obfuscated -- look for string constants and API patterns, not names

## Environment
- WSL2 (Fedora Remix) on Windows
- JDK 17+ required (JDK 21 recommended on Fedora 42)
- ADB connected via WiFi (wireless debugging)
- Device: Pixel 9 Pro XL (non-rooted, Android 15+)
