#!/usr/bin/env bash
# Check screens on the real player without touching her progress: a throwaway copy goes on screen
# (her progress, or a test file), with her settings and a parent PIN of its own (none: the corner
# taps ask to choose one), so parent mode can be checked too. Answers and changes there are thrown
# away; the alarms keep following her real schedule.
#   scripts/sandbox.sh on [progress.json]   start (screen must be asleep or showing the sleeping screen)
#   scripts/sandbox.sh off                  back to her progress
# It also ends by itself when the screen goes off, after 20 minutes, or when a real call is due.
# Screenshots stay blocked; for a check, allow them with
#   adb shell am broadcast -n app.timestables/.CommandReceiver -a app.timestables.cmd.CAPTURE --ez allow true
# and block them again afterwards (--ez allow false).
set -euo pipefail
source "$(dirname "$0")/env.sh"

remote="/sdcard/Android/data/$PKG/files/sandbox-progress.json"
field() { app_cmd STATUS | tr ' ' '\n' | sed -n "s/^$1=//p" | head -1; }

case "${1:-}" in
    on)
        extra=()
        if [ -n "${2:-}" ]; then
            [ -f "$2" ] || die "$2 not found"
            adb push "$2" "$remote" >/dev/null 2>&1 || die "could not push $2"
            extra=(--es progress sandbox-progress.json)
        fi
        adb_sh input keyevent KEYCODE_WAKEUP
        result="$(app_cmd SANDBOX --ez on true "${extra[@]+"${extra[@]}"}")"
        case "$result" in ERROR*) die "$result" ;; esac
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            [ "$(field sandbox)" = on ] && { echo "sandbox on (mode=$(field mode))"; exit 0; }
            sleep 0.5
        done
        die "the sandbox didn't start: see scripts/logs.sh"
        ;;
    off)
        app_cmd SANDBOX --ez on false >/dev/null
        adb_sh rm -f "$remote"
        sleep 1
        echo "sandbox=$(field sandbox)"
        ;;
    *)
        die "usage: scripts/sandbox.sh on [progress.json] | off"
        ;;
esac
