#!/usr/bin/env bash
#
# Build, install and launch on the tablet. This loop runs dozens of times a
# session, so it is one command.
#
#   ./deploy.sh              build + install + launch + clear logcat
#   ./deploy.sh log          the above, then follow the app's logcat
#   ./deploy.sh log-only     just follow the logcat
#
# The build itself goes through ./build.sh, which finds the JDK. Nothing here
# assumes a toolchain path: this project is cloneable.
#
set -euo pipefail

cd "$(dirname "$0")"

ADB="${ADB:-/c/workenv/platform-tools/adb.exe}"
DEVICE="${DEVICE:-192.168.1.108:5555}"
APK="out/ipixel-clock-debug.apk"
PKG="com.example.ipixelclock"

# The driver's own instrumentation is the most useful thing in here: IPixelHub
# logs its READY line, the resolved panel type, and an fps figure every ten
# frames.
TAGS="IPixelHub:I ClockService:I WebServer:I SettingsStore:I BootReceiver:I AndroidRuntime:E"

follow_log() {
  "$ADB" -s "$DEVICE" logcat -v brief -s $TAGS
}

if [ "${1:-}" = "log-only" ]; then
  follow_log
  exit 0
fi

echo "==> connecting to $DEVICE"
"$ADB" connect "$DEVICE" >/dev/null || true

echo "==> building"
sh ./build.sh debug

echo "==> installing"
"$ADB" -s "$DEVICE" install -r "$APK"

echo "==> launching"
"$ADB" -s "$DEVICE" logcat -c || true
"$ADB" -s "$DEVICE" shell am start -n "$PKG/.MainActivity" >/dev/null

echo "==> up"
if [ "${1:-}" = "log" ]; then
  follow_log
fi
