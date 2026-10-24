#!/usr/bin/env bash
# Configure a device that has the app installed (scripts/install.sh). Safe to re-run.
#   scripts/provision.sh              apply everything below (asks before setting device owner)
#   scripts/provision.sh --keep-radios   leave Wi-Fi/Bluetooth as they are
#
# What it does:
#   - device owner (only if missing, after confirmation; needs no accounts and a single user)
#   - battery: Doze whitelist, MediaTek DuraSpeed + background power saving off
#   - vendor "Auto Shutdown" off: this firmware powers the phone off 60 min after the screen goes
#     off when no USB cable is attached (persist.sys.auto.sd.time), which would kill every wake
#   - radios: Wi-Fi and Bluetooth off (the app never uses the network)
#   - display: navigation bar pushed off-screen (wm overscan) so the kiosk is truly full screen
#   - DND off, so the alarm-stream chime is never silenced
#   - app policies (via the app): lock task, kiosk as permanent HOME, keyguard + status bar off,
#     30 s screen timeout, fixed brightness, OTA postponed, Europe/London, hide other apps
#   - clock set from this Mac (no network on the device; auto time off)
# Does NOT add the restrictions that block adb installs: that is scripts/harden.sh.
set -euo pipefail
source "$(dirname "$0")/env.sh"

KEEP_RADIOS=0
[ "${1:-}" = "--keep-radios" ] && KEEP_RADIOS=1

# User-facing apps to hide (they stop running and disappear; unharden.sh brings them back).
# All third-party apps are hidden too, except the ones in KEEP.
HIDE_SYSTEM=(
    com.android.chrome com.android.vending com.android.fmradio com.mediatek.emcamera
    com.android.gallery3d com.android.music com.android.soundrecorder com.android.calculator2
    com.android.calendar com.android.deskclock com.google.android.apps.nbu.files
    com.teksun.factorytest com.mediatek.engineermode com.mediatek.duraspeed com.android.egg
    com.android.dreams.basic com.google.android.feedback com.android.protips
    com.android.wallpaper.livepicker com.mediatek.ygps com.mediatek.lbs.em2.ui
    com.mediatek.sensorhub.ui com.mediatek.gnssdebugreport com.android.traceur
)
# Gboard stays (keyboard for Wi-Fi passwords etc. in maintenance mode; unreachable in the kiosk).
KEEP=("$PKG" com.google.android.inputmethod.latin)

adb get-state >/dev/null 2>&1 || die "no device: check the cable and USB debugging (see README)"
adb_sh pm path "$PKG" | grep -q package: || die "$PKG is not installed: run scripts/build.sh && scripts/install.sh"

if ! adb_sh dumpsys device_policy | grep -q "package=$PKG"; then
    echo "The app is not device owner yet. This needs 0 accounts and 1 user on the device:"
    adb_sh dumpsys account | grep -E '^\s*Accounts:' || true
    adb_sh pm list users
    read -r -p "Set $ADMIN as device owner now? [y/N] " answer || answer=""
    [ "$answer" = "y" ] || die "stopped; nothing changed"
    adb_sh dpm set-device-owner "$ADMIN"
fi

echo "== battery / background killers"
adb_sh dumpsys deviceidle whitelist "+$PKG"
adb_sh settings put system setting.duraspeed.enabled 0
adb_sh settings put global setting.duraspeed.enabled 0
adb_sh settings put system background_power_saving_enable 0
# The vendor's AutoShutdownService (in Settings) reads this on every screen-off; "0" = never.
# adb can set it because SELinux is permissive on this firmware; it survives reboots.
adb_sh setprop persist.sys.auto.sd.time 0
[ "$(adb_sh getprop persist.sys.auto.sd.time)" = 0 ] \
    || die "could not turn off the vendor auto shutdown (persist.sys.auto.sd.time); see README"

if [ "$KEEP_RADIOS" = 0 ]; then
    echo "== radios off"
    adb_sh svc wifi disable
    adb_sh svc bluetooth disable || true
fi

echo "== full screen: navigation bar off-screen"
navbar_h="$(adb_sh dumpsys window windows | grep -A8 'Window{.*NavigationBar0' | sed -n 's/.*Requested w=[0-9]* h=\([0-9]*\).*/\1/p' | head -1 || true)"
[ -n "$navbar_h" ] || navbar_h=63
adb_sh wm overscan "0,0,0,-$navbar_h"

echo "== sound"
adb_sh settings put global zen_mode 0

echo "== app policies"
hide=("${HIDE_SYSTEM[@]}")
for p in $(adb_sh pm list packages -3 | sed 's/^package://'); do
    keep=0
    for k in "${KEEP[@]}"; do [ "$p" = "$k" ] && keep=1; done
    [ "$keep" = 0 ] && [ "$p" != "$PKG.devopts" ] && hide+=("$p")
done
app_cmd PROVISION --es tz Europe/London --es hide "$(IFS=,; echo "${hide[*]}")"
echo

# The Developer-options helper is only needed before adb works; remove it once we're here.
if adb_sh pm path "$PKG.devopts" | grep -q package:; then
    adb uninstall "$PKG.devopts" >/dev/null && echo "removed helper $PKG.devopts"
fi

adb_sh am start -n "$PKG/.KioskActivity" >/dev/null
sync_time
echo "== status"
app_cmd STATUS | tr ' ' '\n' | sed 's/^/  /'
