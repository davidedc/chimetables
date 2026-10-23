#!/usr/bin/env bash
# Set the device clock from this Mac. The device has no network, so run this now and then
# (install.sh and provision.sh do it automatically).
set -euo pipefail
source "$(dirname "$0")/env.sh"
sync_time
