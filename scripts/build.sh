#!/usr/bin/env bash
# Build the app. Usage: scripts/build.sh [debug|release]   (default: debug)
#   debug   = testOnly, debuggable (device owner removable with `dpm remove-active-admin`)
#   release = what the child uses once hardened
# Also runs the JVM unit tests. Output: app/build/outputs/apk/<variant>/app-<variant>.apk
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$ROOT"

variant="${1:-debug}"
case "$variant" in debug|release) ;; *) die "variant must be debug or release";; esac
Variant="$(tr '[:lower:]' '[:upper:]' <<<"${variant:0:1}")${variant:1}"

echo "sdk.dir=$ANDROID_HOME" > local.properties

# Local signing key shared by both variants (git-ignored). Back up keystore/ — without it you
# cannot update the app in place, and a device-owner app can't simply be uninstalled.
if [ ! -f keystore/keystore.properties ]; then
    echo "Creating local signing keystore in keystore/ (back this folder up!)"
    mkdir -p keystore
    pass="$(openssl rand -hex 16)"
    keytool -genkeypair -keystore keystore/release.jks -storetype PKCS12 \
        -alias timestables -keyalg RSA -keysize 2048 -validity 36500 \
        -storepass "$pass" -keypass "$pass" -dname "CN=Times Tables Kiosk" >/dev/null
    cat > keystore/keystore.properties <<EOF
storeFile=keystore/release.jks
storePassword=$pass
keyAlias=timestables
keyPassword=$pass
EOF
    chmod 600 keystore/keystore.properties keystore/release.jks
fi

./gradlew --quiet "test${Variant}UnitTest" "assemble${Variant}"
apk="app/build/outputs/apk/$variant/app-$variant.apk"
[ -f "$apk" ] || die "build finished but $apk is missing"
echo "Built $apk"
