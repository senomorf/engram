#!/usr/bin/env bash
# Boot the signed release APK on a running emulator and fail if it crashes on launch.
# Kept in a file (not inline in release.yml) because the android-emulator-runner action
# runs its `script:` input line by line, which breaks multi-line shell constructs.
set -euo pipefail

APK="${1:-engram.apk}"
PKG="cam.engram.app"
LAUNCH_DEADLINE=30
STABLE_SECONDS=5

adb install -r "$APK"
adb logcat -c
# am start -W waits for the launch transaction: deterministic, unlike monkey
adb shell am start -W -n "$PKG/.MainActivity" || true

pid=""
for _ in $(seq 1 "$LAUNCH_DEADLINE"); do
  pid="$(adb shell pidof "$PKG" | tr -d '\r')" || true
  [ -n "$pid" ] && break
  sleep 1
done
if [ -z "$pid" ]; then
  echo "::error::$PKG did not start within ${LAUNCH_DEADLINE}s"
  adb logcat -d | tail -60
  exit 1
fi

# the same pid must stay alive for a stability window: passing on the first sighting
# would green-light an app that crashes moments after launch
for _ in $(seq 1 "$STABLE_SECONDS"); do
  sleep 1
  now="$(adb shell pidof "$PKG" | tr -d '\r')" || true
  if [ "$now" != "$pid" ]; then
    echo "::error::$PKG died or restarted during the ${STABLE_SECONDS}s stability window"
    adb logcat -d | tail -60
    exit 1
  fi
done

adb logcat -d > logcat.txt
# crash evidence scoped to our package: a FATAL EXCEPTION block names the process on
# the following lines, and ANR lines carry the package inline
if grep -E "FATAL EXCEPTION" -A 3 logcat.txt | grep -q "$PKG" || grep -qE "ANR in $PKG" logcat.txt; then
  echo "::error::release APK crashed on launch"
  tail -60 logcat.txt
  exit 1
fi

echo "smoke ok: $PKG launched and stayed up for ${STABLE_SECONDS}s (pid $pid)"
