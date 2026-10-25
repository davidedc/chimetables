#!/usr/bin/env bash
# Pull the app's event log (wakes, beeps, sessions, policy changes) into logs/ and show the tail.
# Usage: scripts/logs.sh [lines]        (default 40)
#        scripts/logs.sh --follow       live logcat for the app
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$ROOT"

if [ "${1:-}" = "--follow" ]; then
    exec adb logcat -v time -s TimesTables:I
fi

mkdir -p logs
remote="/sdcard/Android/data/$PKG/files"
stamp="$(date +%Y%m%d-%H%M%S)"
adb pull "$remote/app.log" "logs/app-$stamp.log" >/dev/null
adb pull "$remote/app.log.1" "logs/app-$stamp.log.1" >/dev/null 2>&1 || true
adb pull "$remote/reviews.jsonl" "logs/reviews-$stamp.jsonl" >/dev/null 2>&1 && echo "Saved logs/reviews-$stamp.jsonl (answer history)" || true
echo "Saved logs/app-$stamp.log"
tail -n "${1:-40}" "logs/app-$stamp.log"
echo
# Things outside the app that can silently stop the wakes (see README, Known limits).
sd="$(adb_sh getprop persist.sys.auto.sd.time)"
echo "device: last boot reason=$(adb_sh getprop sys.boot.reason) up=$(adb_sh uptime | sed 's/.*up \([^,]*\),.*/\1/')" \
     "battery=$(adb_sh dumpsys battery | sed -n 's/^ *level: //p')%" \
     "vendor auto shutdown=${sd:-unset}$([ "$sd" = 0 ] || echo ' <-- WARNING: phone will power itself off; run scripts/provision.sh')"
# Her collection, and a nudge when the extra stickers are running out (see README, Stickers).
status="$(app_cmd STATUS 2>/dev/null || true)"
field() { printf '%s' "$status" | tr ' ' '\n' | sed -n "s/^$1=//p" | head -1; }
left="$(field newStickersLeft)"
echo "collection: stickers=$(field stickers) new extra stickers left=${left:-?} riddles=$(field riddles)" \
     "screenshots=$(field screenshots)$([ -n "$left" ] && [ "$left" -le 3 ] && echo ' <-- add more pictures to family-assets/stickers/more/')"
