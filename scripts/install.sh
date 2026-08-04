#!/usr/bin/env bash
#
# Install a debug APK and make the system actually use it.
#
# Two things bite when reinstalling a keyboard, and this handles both:
#
#   1. Signature mismatch. Solved at the build level by app/debug.keystore,
#      which every build shares — but `-d` is still passed here so a local
#      build (versionCode 1) can replace a CI build (versionCode = run number)
#      without complaining about a downgrade.
#
#   2. The system keeps running the old keyboard. Replacing the package kills
#      the process, but InputMethodManagerService does not reliably rebind to
#      the new service on its own, so it looks like the update did nothing.
#      Explicitly re-selecting the IME forces the rebind.
#
# Usage: scripts/install.sh [path/to.apk]

set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="de.coonabibba.bikeyboard.debug"
IME="$PKG/de.coonabibba.bikeyboard.BilingualKeyboardService"

if [ ! -f "$APK" ]; then
    echo "No APK at $APK" >&2
    echo "Build one with: ./gradlew assembleDebug" >&2
    exit 1
fi

echo "Installing $APK"
adb install -r -d "$APK"

# Kill anything still running from the previous version.
adb shell am force-stop "$PKG" || true

# Re-select the IME so the framework binds to the new service. Both commands
# need WRITE_SECURE_SETTINGS, which the adb shell user has.
adb shell ime enable "$IME"
adb shell ime set "$IME"

echo
echo "Installed and selected. Current IME:"
adb shell settings get secure default_input_method
