#!/usr/bin/env bash
set -Eeuo pipefail

# ARDA Platform persistent system app installer.
# Manifest/provider/app-label changes require a reboot because the package is
# android:persistent="true" and cannot be updated through `adb install -r`.
#
# Usage:
#   ./install-arda-platform-persistent-reboot-v3.sh
#   ./install-arda-platform-persistent-reboot-v3.sh /path/to/app-debug.apk

SERIAL="${SERIAL:-1000000028c0b59c}"
DRIVER_USER="${DRIVER_USER:-10}"
PKG="${PKG:-com.github.deadknight.ardaplatform.debug}"
AUTHORITY="${AUTHORITY:-${PKG}.provider}"

DEFAULT_APK="$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
APK="${1:-$DEFAULT_APK}"

SYSTEM_APK_DIR="/system/product/priv-app/ArdaPlatformPriv"
SYSTEM_APK="$SYSTEM_APK_DIR/ArdaPlatformPriv.apk"
DEVICE_STAGE="/data/local/tmp/ArdaPlatformPriv-${RANDOM}.apk"

die() {
  echo
  echo "ARDA_PLATFORM_PERSISTENT_INSTALL_V3=FAIL reason=$*" >&2
  exit 1
}

adb_cmd() {
  adb -s "$SERIAL" "$@"
}

wait_adb() {
  local i=0
  local state=""

  while [ "$i" -lt 180 ]; do
    state="$(adb_cmd get-state 2>/dev/null | tr -d '\r' || true)"
    if [ "$state" = "device" ]; then
      echo "ADB_STATE=device"
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done

  adb devices -l || true
  die "adb_device_not_ready:$SERIAL"
}

wait_boot() {
  wait_adb

  local i=0
  while [ "$i" -lt 240 ]; do
    if [ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "SYS_BOOT_COMPLETED=1"
      sleep 8
      return 0
    fi
    i=$((i + 1))
    sleep 2
  done

  die "boot_timeout"
}

command -v adb >/dev/null 2>&1 \
  || die "adb_not_found"

test -f "$APK" \
  || die "apk_not_found:$APK"

echo "ARDA_PLATFORM_INSTALL_SERIAL=$SERIAL"
echo "ARDA_PLATFORM_INSTALL_APK=$APK"
echo "ARDA_PLATFORM_DRIVER_USER=$DRIVER_USER"
echo "ARDA_PLATFORM_PACKAGE=$PKG"
echo "ARDA_PLATFORM_PROVIDER_AUTHORITY=$AUTHORITY"

echo
echo "=== DEVICE PRECHECK ==="

wait_adb

EXISTING_PATH="$(
  adb_cmd shell pm path "$PKG" 2>/dev/null \
    | tr -d '\r' \
    | head -n 1
)"

echo "EXISTING_PACKAGE_PATH=$EXISTING_PATH"

case "$EXISTING_PATH" in
  package:/system/product/priv-app/ArdaPlatformPriv/ArdaPlatformPriv.apk)
    ;;
  *)
    die "unexpected_existing_package_path:$EXISTING_PATH"
    ;;
esac

echo
echo "=== ROOT / REMOUNT ==="

adb_cmd root || true
sleep 2
wait_adb

adb_cmd remount
sleep 2
wait_adb

echo
echo "=== STAGE APK ==="

adb_cmd push "$APK" "$DEVICE_STAGE"

adb_cmd shell test -s "$DEVICE_STAGE" \
  || die "device_stage_missing"

echo
echo "=== STOP EXISTING PROCESSES ==="

adb_cmd shell am force-stop --user 0 "$PKG" \
  >/dev/null 2>&1 || true

adb_cmd shell am force-stop --user "$DRIVER_USER" "$PKG" \
  >/dev/null 2>&1 || true

echo
echo "=== REPLACE PERSISTENT SYSTEM APK ==="

adb_cmd shell mkdir -p "$SYSTEM_APK_DIR"

adb_cmd shell cp -fp \
  "$DEVICE_STAGE" \
  "$SYSTEM_APK"

adb_cmd shell chown root:root \
  "$SYSTEM_APK"

adb_cmd shell chmod 0644 \
  "$SYSTEM_APK"

adb_cmd shell restorecon -RF \
  "$SYSTEM_APK_DIR"

# Remove stale precompiled artifacts so boot compiles/uses the new APK.
adb_cmd shell rm -rf \
  "$SYSTEM_APK_DIR/oat"

adb_cmd shell rm -f \
  "$DEVICE_STAGE"

adb_cmd shell sync

adb_cmd shell ls -lZ \
  "$SYSTEM_APK"

echo "ARDA_PLATFORM_PERSISTENT_APK_COPY_GATE=PASS"

echo
echo "=== REBOOT FOR PACKAGE MANAGER REPARSE ==="

adb_cmd reboot
wait_boot

echo
echo "=== ENABLE PACKAGE FOR SYSTEM AND DRIVER USERS ==="

adb_cmd root || true
sleep 2
wait_adb

adb_cmd shell cmd package install-existing --user 0 "$PKG" \
  >/dev/null 2>&1 || true

adb_cmd shell pm enable --user 0 "$PKG" \
  >/dev/null

adb_cmd shell cmd package install-existing --user "$DRIVER_USER" "$PKG" \
  >/dev/null 2>&1 || true

adb_cmd shell pm enable --user "$DRIVER_USER" "$PKG" \
  >/dev/null

echo
echo "=== VERIFY SYSTEM PACKAGE PATH ==="

PACKAGE_PATH="$(
  adb_cmd shell pm path "$PKG" \
    | tr -d '\r' \
    | head -n 1
)"

echo "PACKAGE_PATH=$PACKAGE_PATH"

case "$PACKAGE_PATH" in
  package:$SYSTEM_APK)
    ;;
  *)
    die "package_not_loaded_from_system_apk:$PACKAGE_PATH"
    ;;
esac

echo
echo "=== VERIFY PROVIDER AUTHORITY ==="

PROVIDER_FOUND=0

for _ in $(seq 1 30); do
  if adb_cmd shell dumpsys package providers \
      | grep -F "$AUTHORITY" \
      >/dev/null; then
    PROVIDER_FOUND=1
    break
  fi
  sleep 1
done

if [ "$PROVIDER_FOUND" -ne 1 ]; then
  adb_cmd shell dumpsys package "$PKG" \
    | grep -E \
      'Provider|authority|codePath=|versionName=|User 0:|User '"$DRIVER_USER"':' \
    | head -n 120 || true

  die "provider_authority_not_registered:$AUTHORITY"
fi

adb_cmd shell dumpsys package providers \
  | grep -F -C 3 "$AUTHORITY" \
  | head -n 50 || true

echo "ARDA_PLATFORM_PROVIDER_AUTHORITY_GATE=PASS"

echo
echo "=== VERIFY USER STATES ==="

adb_cmd shell dumpsys package "$PKG" \
  | tr -d '\r' \
  | grep -E \
      "codePath=|versionName=|User 0:|User ${DRIVER_USER}:" \
  | head -n 40 || true

echo
echo "=== START DRIVER UI ==="

adb_cmd shell am force-stop --user "$DRIVER_USER" "$PKG" \
  >/dev/null 2>&1 || true

adb_cmd shell am start \
  --user "$DRIVER_USER" \
  -n "$PKG/.MainActivity"

sleep 3

FOREGROUND="$(
  adb_cmd shell dumpsys activity activities \
    | grep -m 1 'mResumedActivity' \
    | tr -d '\r' || true
)"

echo "FOREGROUND=$FOREGROUND"

printf '%s\n' "$FOREGROUND" \
  | grep -F "$PKG/.MainActivity" \
  >/dev/null \
  || die "main_activity_not_resumed"

echo
echo "ARDA_PLATFORM_PERSISTENT_INSTALL_V3=PASS"
echo "PACKAGE_PATH=$PACKAGE_PATH"
echo "AUTHORITY=$AUTHORITY"
echo "APP_NAME_EXPECTED=ARDA Platform"
echo "DRIVER_UI=RESUMED"
