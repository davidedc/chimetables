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
