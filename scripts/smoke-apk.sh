#!/usr/bin/env bash
# Boot the signed release APK on a running emulator and fail if it crashes on launch.
# Kept in a file (not inline in release.yml) because the android-emulator-runner action
# runs its `script:` input line by line, which breaks multi-line shell constructs.
set -euo pipefail

APK="${1:-engram.apk}"
PKG="cam.engram.app"

adb install -r "$APK"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 || true
sleep 5
adb logcat -d > logcat.txt

if grep -qE "FATAL EXCEPTION|ANR in $PKG" logcat.txt; then
  echo "::error::release APK crashed on launch"
  tail -60 logcat.txt
  exit 1
fi

if ! adb shell pidof "$PKG" > /dev/null; then
  echo "::error::$PKG not running after launch"
  tail -60 logcat.txt
  exit 1
fi

echo "smoke ok: $PKG launched without crash"
