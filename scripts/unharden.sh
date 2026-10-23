#!/usr/bin/env bash
# EMERGENCY / maintenance undo. Gives the device back as a normal Android player:
#   - clears the harden.sh restrictions, unhides every app the kiosk hid
#   - drops the kiosk as permanent HOME, leaves lock task, re-enables status bar and lock screen
#   - puts the navigation bar back on screen, makes Launcher3 the home app again
#   - removes the app as device owner (unless --keep-owner)
# Your daughter's progress is kept (it lives in the app's private storage).
# To install an app update you do NOT need this: scripts/install.sh handles a hardened device.
# Usage: scripts/unharden.sh [--keep-owner] [--yes]
set -euo pipefail
source "$(dirname "$0")/env.sh"

remove_owner=true
yes=0
for arg in "$@"; do
    case "$arg" in
        --keep-owner) remove_owner=false ;;
        --yes) yes=1 ;;
        *) die "unknown option $arg" ;;
    esac
done

adb get-state >/dev/null 2>&1 || die "no device: check the cable and that USB debugging is still on"
if [ "$yes" = 0 ]; then
    read -r -p "Unlock the device (remove owner: $remove_owner)? [y/N] " answer || answer=""
    [ "$answer" = "y" ] || die "stopped; nothing changed"
fi

result="$(app_cmd UNHARDEN --ez remove_owner "$remove_owner" || true)"
echo "app: ${result:-<no answer>}"

if [ "$remove_owner" = true ] && adb_sh dumpsys device_policy | grep -q "package=$PKG"; then
    # The app didn't answer (crashed or too old). This works for debug (testOnly) builds only.
    echo "app did not release ownership; trying dpm remove-active-admin"
    adb_sh dpm remove-active-admin "$ADMIN" || echo "could not remove device owner: see README 'Recovery'"
fi

adb_sh wm overscan reset
adb_sh cmd package set-home-activity com.android.launcher3/.Launcher >/dev/null || true
adb_sh am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
echo "device owner now: $(adb_sh dumpsys device_policy | grep -q "package=$PKG" && echo "$PKG" || echo none)"
