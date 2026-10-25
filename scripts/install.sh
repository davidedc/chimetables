#!/usr/bin/env bash
# Install (or update in place) the app on the connected device and bring it to the front.
# Usage: scripts/install.sh [debug|release] [--force]   (default: debug; run scripts/build.sh first)
# Updating keeps progress and settings. On a hardened device the install block is lifted for
# the update and put back afterwards. Also sets the device clock from this Mac.
# An update restarts the app, so it refuses while she is using it (a call, a play, the riddle
# screens, her book, or parent mode) unless --force is given.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$ROOT"

variant="debug"
force=0
for arg in "$@"; do
    case "$arg" in
        --force) force=1 ;;
        *) variant="$arg" ;;
    esac
done
apk="app/build/outputs/apk/$variant/app-$variant.apk"
[ -f "$apk" ] || die "$apk not found; run scripts/build.sh $variant"
adb get-state >/dev/null 2>&1 || die "no device: check the cable and USB debugging"

hardened=0
if adb_sh pm path "$PKG" | grep -q package:; then
    status="$(app_cmd STATUS)"
    case "$status" in
        *no_install_apps*) hardened=1 ;;
    esac
    mode="$(printf '%s' "$status" | tr ' ' '\n' | sed -n 's/^mode=//p' | head -1)"
    case "$mode" in
        ""|SLEEPING) ;;
        *) [ "$force" = 1 ] || die "the player is in use right now ($mode): try again when the screen is off, or add --force" ;;
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
    *USER_RESTRICTED*|*"User restriction"*) die "installs are blocked by a user restriction; try scripts/unharden.sh --keep-owner" ;;
    *) die "install failed" ;;
esac

adb_sh am start -n "$PKG/.KioskActivity" >/dev/null
sync_time
echo "Installed $(adb_sh dumpsys package "$PKG" | sed -n 's/^ *versionName=//p' | head -1 || true) ($variant)"
