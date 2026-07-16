#!/usr/bin/env bash
set -euo pipefail
USB_SERIAL="${USB_SERIAL:-1000000028c0b59c}"
cd "$(dirname "$0")"
APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "Build APK first." >&2; exit 1; }
adb -s "$USB_SERIAL" install -r "$APK"
adb -s "$USB_SERIAL" shell am start   -n com.github.deadknight.ardaplatform.debug/com.github.deadknight.ardaplatform.MainActivity
echo "ARDA_RN3B_AAOS_APP_INSTALL=PASS"
