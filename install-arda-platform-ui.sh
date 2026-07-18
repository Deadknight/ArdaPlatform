#!/usr/bin/env bash
set -Eeuo pipefail

# ArdaPlatform UI live installer
# Usage:
#   ./install-arda-platform-ui.sh
#   ./install-arda-platform-ui.sh /path/to/app-debug.apk
#   SERIAL=1000000028c0b59c DRIVER_USER=10 ./install-arda-platform-ui.sh

SERIAL="${SERIAL:-1000000028c0b59c}"
DRIVER_USER="${DRIVER_USER:-10}"
PKG="${PKG:-com.github.deadknight.ardaplatform.debug}"

DEFAULT_APK="$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
APK="${1:-$DEFAULT_APK}"

SYSTEM_APK_DIR="/system/product/priv-app/ArdaPlatformPriv"
SYSTEM_APK="$SYSTEM_APK_DIR/ArdaPlatformPriv.apk"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

command -v adb >/dev/null 2>&1 || die "adb bulunamadı."
test -f "$APK" || die "APK bulunamadı: $APK"

echo "ARDA_PLATFORM_INSTALL_SERIAL=$SERIAL"
echo "ARDA_PLATFORM_INSTALL_APK=$APK"
echo "ARDA_PLATFORM_DRIVER_USER=$DRIVER_USER"

adb -s "$SERIAL" get-state >/dev/null 2>&1 ||
  die "ADB cihazı bağlı değil: $SERIAL"

echo
echo "=== ROOT / REMOUNT ==="
adb -s "$SERIAL" root
sleep 2
adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" remount

echo
echo "=== STOP EXISTING PROCESSES ==="
adb -s "$SERIAL" shell "
am force-stop --user 0 '$PKG' 2>/dev/null || true
am force-stop --user '$DRIVER_USER' '$PKG' 2>/dev/null || true
"

echo
echo "=== INSTALL SYSTEM PRIV-APP APK ==="
adb -s "$SERIAL" shell "mkdir -p '$SYSTEM_APK_DIR'"
adb -s "$SERIAL" push "$APK" "$SYSTEM_APK"

adb -s "$SERIAL" shell "
chmod 0755 '$SYSTEM_APK_DIR'
chmod 0644 '$SYSTEM_APK'
restorecon -RF '$SYSTEM_APK_DIR'
sync
"

echo
echo "=== REBOOT ==="
adb -s "$SERIAL" reboot
adb -s "$SERIAL" wait-for-device

# USB adbd may restart once more after root.
adb -s "$SERIAL" root
sleep 2
adb -s "$SERIAL" wait-for-device

echo
echo "=== ENABLE UI FOR DRIVER USER ==="
adb -s "$SERIAL" shell "
pm install-existing --user '$DRIVER_USER' '$PKG' 2>/dev/null || true
pm enable --user '$DRIVER_USER' '$PKG'
"

echo
echo "=== VERIFY ==="
PACKAGE_PATH="$(
  adb -s "$SERIAL" shell pm path "$PKG" |
  tr -d '\r'
)"

echo "$PACKAGE_PATH"

case "$PACKAGE_PATH" in
  "package:$SYSTEM_APK")
    ;;
  *)
    die "Paket beklenen system yolunda değil: $PACKAGE_PATH"
    ;;
esac

adb -s "$SERIAL" shell dumpsys package "$PKG" |
  tr -d '\r' |
  grep -E "User 0:|User ${DRIVER_USER}:|versionName=" |
  head -n 20 || true

echo
echo "ARDA_PLATFORM_UI_INSTALL=PASS"
echo "Uygulamayı araç ekranındaki launcher üzerinden aç."
