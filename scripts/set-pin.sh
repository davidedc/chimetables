#!/usr/bin/env bash
# Set (or reset a forgotten) parent PIN over adb. The PIN is typed hidden, sent to the app once,
# and stored there only as a salted PBKDF2 hash. Nothing is written on this Mac.
# Alternative with no Mac involved: tap the device's top-left corner 7 times; if no PIN is set
# yet it asks you to choose one.
set -euo pipefail
source "$(dirname "$0")/env.sh"

read -r -s -p "New parent PIN (4-8 digits): " pin; echo
read -r -s -p "Same PIN again: " again; echo
[ "$pin" = "$again" ] || die "the two PINs differ"
[[ "$pin" =~ ^[0-9]{4,8}$ ]] || die "PIN must be 4-8 digits"
app_cmd SET_PIN --es pin "$pin"
unset pin again
