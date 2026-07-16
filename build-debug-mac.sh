#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew :app:assembleDebug
echo
echo "ARDA_RN3B_AAOS_APP_BUILD=PASS"
echo "APK=$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
