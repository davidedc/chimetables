#!/usr/bin/env bash
# Show or change the app's settings over adb (the same settings as parent mode).
#   scripts/settings.sh                                  show current settings
#   scripts/settings.sh '{"week":"holiday"}'             change only the fields given
#   scripts/settings.sh '{"school":{"mon":["07:45","17:00"],"quietStart":"21:00"}}'
# Fields: tables (list of 1-12), maxFactor (10|12), week ("school"|"holiday": the plan in use),
#         school / holiday: {"mon".."sun": ["HH:MM",...], quietStart, quietEnd} (only the days
#         given change), sessionSize, newPerDay, volumePercent (0-100), beepRepeats,
#         beepIntervalSeconds, brightness (0-255), screenTimeoutSeconds, newStickerEveryDays.
set -euo pipefail
source "$(dirname "$0")/env.sh"

if [ $# -eq 0 ]; then
    out="$(app_cmd SETTINGS)"
else
    case "$1" in *"'"*) die "no single quotes in the JSON, please" ;; esac
    # Single-quoted so the device shell passes the JSON through untouched.
    out="$(app_cmd SETTINGS --es json "'$1'")"
fi
python3 -c 'import json,sys; print(json.dumps(json.loads(sys.argv[1]), indent=1))' "$out" 2>/dev/null || echo "$out"
