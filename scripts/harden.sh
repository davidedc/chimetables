#!/usr/bin/env bash
# Final lock-down. Adds user restrictions: no factory reset from Settings, no safe boot, no new
# users, no app installs/uninstalls (including adb install), no account changes, no date/time
# changes, Bluetooth off, no crash dialogs. USB debugging deliberately stays ON so you can
# update and recover: scripts/install.sh lifts the install block around an update automatically.
# Undo: scripts/unharden.sh (emergency, everything) or scripts/unharden.sh --keep-owner.
# Usage: scripts/harden.sh [--yes]
set -euo pipefail
source "$(dirname "$0")/env.sh"

status="$(app_cmd STATUS)"
[[ "$status" == *deviceOwner=true* ]] || die "app is not device owner: run scripts/provision.sh first"
[[ "$status" == *pinSet=true* ]] || die "set the parent PIN first: scripts/set-pin.sh, or tap the top-left corner 7 times on the device"

if [ "${1:-}" != "--yes" ]; then
    read -r -p "Apply hardening restrictions now? [y/N] " answer || answer=""
    [ "$answer" = "y" ] || die "stopped; nothing changed"
fi

app_cmd HARDEN
echo
echo "adb still works: $(adb_sh echo yes)   USB debugging: $(adb_sh settings get global adb_enabled)"
