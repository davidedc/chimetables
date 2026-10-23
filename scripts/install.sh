#!/usr/bin/env bash
# Install (or update in place) the app on the connected device and bring it to the front.
# Usage: scripts/install.sh [debug|release]   (default: debug; run scripts/build.sh first)
# Updating keeps progress and settings. A hardened device blocks installs: run unharden.sh first.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$ROOT"

variant="${1:-debug}"
apk="app/build/outputs/apk/$variant/app-$variant.apk"
[ -f "$apk" ] || die "$apk not found; run scripts/build.sh $variant"

# -t: debug builds are testOnly. -r: replace, keeping data.
out="$(adb install -r -t "$apk" 2>&1 || true)"
echo "$out"
case "$out" in
    *Success*) ;;
    *USER_RESTRICTED*|*DISALLOW_INSTALL*) die "installs are blocked by harden.sh; run scripts/unharden.sh --keep-owner first" ;;
    *) die "install failed" ;;
esac

adb_sh am start -n "$PKG/.KioskActivity" >/dev/null
sync_time
echo "Installed $(adb_sh dumpsys package "$PKG" | sed -n 's/^ *versionName=//p' | head -1) ($variant)"
