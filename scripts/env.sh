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

die() { echo "ERROR: $*" >&2; exit 1; }
