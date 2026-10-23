#!/usr/bin/env bash
# Install (or update in place) the app on the connected device and bring it to the front.
# Usage: scripts/install.sh [debug|release]   (default: debug; run scripts/build.sh first)
# Updating keeps progress and settings. On a hardened device the install block is lifted for
# the update and put back afterwards. Also sets the device clock from this Mac.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$ROOT"

variant="${1:-debug}"
apk="app/build/outputs/apk/$variant/app-$variant.apk"
[ -f "$apk" ] || die "$apk not found; run scripts/build.sh $variant"
adb get-state >/dev/null 2>&1 || die "no device: check the cable and USB debugging"

hardened=0
if adb_sh pm path "$PKG" | grep -q package:; then
    case "$(app_cmd STATUS)" in
        *no_install_apps*) hardened=1 ;;
    esac
fi
if [ "$hardened" = 1 ]; then
    echo "device is hardened: lifting the install block for this update"
    app_cmd ALLOW_INSTALLS >/dev/null
    trap 'echo "re-hardening"; app_cmd HARDEN >/dev/null' EXIT
fi

# -t: debug builds are testOnly. -r: replace, keeping data.
out="$(adb install -r -t "$apk" 2>&1 || true)"
echo "$out"
case "$out" in
    *Success*) ;;
    *USER_RESTRICTED*) die "installs are blocked by a user restriction; try scripts/unharden.sh --keep-owner" ;;
    *) die "install failed" ;;
esac

adb_sh am start -n "$PKG/.KioskActivity" >/dev/null
sync_time
echo "Installed $(adb_sh dumpsys package "$PKG" | sed -n 's/^ *versionName=//p' | head -1) ($variant)"
