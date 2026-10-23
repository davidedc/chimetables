# Shared environment for the scripts in this directory. Source it, don't run it.
# Paths match the Homebrew installs (openjdk@17, android-commandlinetools, android-platform-tools).

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="app.timestables"
ADMIN="$PKG/.AdminReceiver"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# adb talks to the single connected device unless ANDROID_SERIAL is set.
adb_sh() { adb shell "$@" | tr -d '\r'; }

# Send a command to the app's shell-only command receiver (protected by android.permission.DUMP,
# which adb shell holds and normal apps cannot get) and print the receiver's result line.
app_cmd() {
    local cmd="$1"; shift
    adb_sh am broadcast -n "$PKG/.CommandReceiver" -a "$PKG.cmd.$cmd" "$@" \
        | sed -n 's/.*data="\(.*\)"$/\1/p'
}

# The device has no network, so its clock is set from this Mac (auto time is off).
clock_offset_ms() {  # device minus Mac, in ms
    local dev mac
    dev="$(adb_sh date +%s%N | cut -c1-13)"
    mac="$(python3 -c 'import time; print(int(time.time()*1000))')"
    echo $((dev - mac))
}
sync_time() {
    # `am broadcast` takes a variable ~0.5-1 s to deliver, so iterate, compensating for the
    # measured error each time, until the device is within 150 ms.
    local before after correction=0 pass offset
    before="$(clock_offset_ms)"
    for pass in 1 2 3 4; do
        app_cmd SET_TIME --el millis "$(python3 -c "import time; print(int(time.time()*1000) - ($correction))")" >/dev/null
        offset="$(clock_offset_ms)"
        [ "${offset#-}" -lt 150 ] && break
        correction=$((correction + offset))
    done
    after="$(clock_offset_ms)"
    echo "clock synced from Mac: device was ${before}ms off, now ${after}ms"
}

die() { echo "ERROR: $*" >&2; exit 1; }
