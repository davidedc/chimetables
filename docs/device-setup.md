# Setting up the player, step by step

From a MECHEN X55 in its box to a locked times-tables kiosk. Allow about an hour the first time.
Everything here was done on a real X55 (Android 10, MediaTek MT6750). Other cheap Android 10
players should work the same way; the X55-specific traps are marked **X55**.

What you need:

- the player, charged
- a USB-C cable that **carries data** (see step 3)
- a Mac with Homebrew (Apple Silicon was used; the scripts are plain bash and should also work on
  Linux with `JAVA_HOME` and `ANDROID_HOME` set)

## 1. Install the tools on the Mac

```sh
brew install --cask android-platform-tools android-commandlinetools
brew install openjdk@17 gradle libmtp
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools --licenses
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-29"
```

`scripts/env.sh` looks for JDK 17 and the SDK at those Homebrew paths; set `JAVA_HOME` /
`ANDROID_HOME` to override. `libmtp` is only needed for the fallback in step 4.

## 2. First start of the player

Go through Android's welcome screens with as little as possible:

- **Skip Wi-Fi.** The app is fully offline, and without a network there are no Google prompts and
  no system updates arriving later.
- **Don't add a Google account.** Making the app device owner (step 7) needs a device with **no
  accounts at all** and a single user. If an account is already on it, remove it in Settings →
  Accounts. If it won't go away, a factory reset is the only way; that wipes the player.
- Skip the screen lock. The kiosk turns the lock screen off anyway.

The time zone doesn't matter; provisioning sets Europe/London (change `--es tz` in
`scripts/provision.sh` for another zone).

## 3. Connect it to the Mac with a data cable

Plug the cable **straight into a port on the Mac**.

**X55:** the cable in the box only charges, and many dock and monitor USB ports pass no data either.
The player charges happily on any of them, so charging proves nothing.

To check, open  → About This Mac → More Info → System Report → USB. A MediaTek device (USB ID
`0e8d:2008`) should be listed. If it isn't, change the cable or port before going further. A cable
that does file transfer with a phone is a safe bet.

## 4. Turn on Developer options and USB debugging

> **X55 warning:** do **not** tap *Build number*. On this firmware, tapping Build number about
> 6 times opens a hidden vendor factory-test menu (`com.teksun.factorytest`). Its "device reset"
> button **factory-resets the player** without asking again; it happened to us. If any unfamiliar
> test menu appears, press Back and touch nothing.

1. Settings → About phone → tap **Model** 7 times, until "You are now a developer!" appears.
2. Settings → System → Advanced → **Developer options**. Check the switch at the top is on, then
   turn on **USB debugging** and confirm.
3. With the cable in, a box appears on the player: **"Allow USB debugging?"**. Tick **Always allow
   from this computer** and tap **Allow**. If no box appears, unplug and replug the cable.
4. On the Mac:

   ```sh
   adb devices
   ```

   should list the player as `device` (on the X55 its serial is `0123456789ABCDEF`).

If something goes wrong:

| What you see | What it means |
|---|---|
| Nothing in `adb devices`, nothing in System Report → USB | charge-only cable or a port without data |
| Listed in System Report, but `adb devices` is empty | USB debugging is off, or the player is actually **switched off** and showing its charging screen (a switched-off X55 offers file transfer only). Press power and check. |
| `unauthorized` | the "Allow USB debugging?" box is waiting on the player |
| Developer options can't be reached at all | use the helper below |

**Helper for firmware that hides Developer options.** `devopts/` is a tiny app that opens the
Developer options page directly (with fallbacks), and writes `Download/devopts-report.txt`
listing any developer, debug or factory screens it found. It can be installed without adb:

```sh
./gradlew :devopts:assembleDebug
mtp-sendfile devopts/build/outputs/apk/debug/devopts-debug.apk 2/DevOptions.apk   # 2 = Download folder
```

On the player, open the **Files** app → Downloads → **DevOptions.apk**, allow installs from this
source when asked, install it, open it and tap **Open Developer options**. `provision.sh` removes the
helper again later.

## 5. Build the app

```sh
scripts/build.sh release
```

This runs the unit tests and builds `app/build/outputs/apk/release/app-release.apk`. The first build
creates a signing key in `keystore/` (git-ignored). **Back up `keystore/`.** Every future update
must be signed with the same key, and once the app is device owner it can't simply be uninstalled
and installed again.

`scripts/build.sh debug` builds a `testOnly` debug variant, signed with the same key. While you're
experimenting it has an extra escape hatch: its device owner can be removed with
`adb shell dpm remove-active-admin app.timestables/.AdminReceiver`. Either variant installs over
the other, keeping progress.

Optional: put personal sticker pictures in `family-assets/stickers/` before building (see
[Stickers](../README.md#stickers)).

## 6. Install it

```sh
scripts/install.sh release
```

The app opens, and the script sets the player's clock from the Mac. Nothing is locked yet: Home
and Back still work.

## 7. Provision: device owner and kiosk

```sh
scripts/provision.sh
```

It shows the accounts and users on the player and **asks before** making the app device owner
(`dpm set-device-owner`). Then it:

- whitelists the app from Doze, turns off MediaTek DuraSpeed (a background-app killer) and the
  **X55 vendor auto shutdown** (`persist.sys.auto.sd.time`, see below)
- turns Wi-Fi and Bluetooth off (`--keep-radios` leaves them)
- pushes the navigation bar off-screen with `wm overscan` (Android 10 only), so the kiosk is truly
  full screen
- turns Do Not Disturb off, so the chime is never silenced
- via the app: lock task, the app as permanent home screen, no lock screen, no status bar,
  30-second screen timeout, fixed brightness, system updates postponed, Europe/London, and 41 other
  apps hidden (Chrome, Play Store, camera, the factory-test app and so on)
- sets the clock again and prints the app's status

From now on the player only shows the app, including after a reboot. `provision.sh` is safe to
re-run.

**X55 vendor auto shutdown.** Out of the box this firmware powers the player **off 60 minutes after
the screen goes off**, unless a USB cable is attached or music is playing. On the cable you'd never
notice, but unplugged it would kill every wake-up after the first. `provision.sh` sets it to 0
(never), and `harden.sh` refuses to run until it is.

## 8. Choose the parent PIN

Either on the player (tap the **top-left corner 7 times** within 5 seconds, then type a 4–8 digit
PIN twice) or from the Mac:

```sh
scripts/set-pin.sh
```

The PIN is stored on the player only, as a salted PBKDF2 hash. It is never written on the Mac or in
git.

## 9. Check it really works, unplugged

```sh
scripts/logs.sh
```

pulls the app's event log into `logs/` and prints the last lines, followed by a `device:` line
(last boot reason, uptime, battery, auto shutdown setting). For a quick wake-up test use parent
mode → **Test wake-up in 1 minute**, or:

```sh
source scripts/env.sh && app_cmd TEST_WAKE --ei seconds 60
```

Then turn the screen off. A minute later it should light up and chime.

The test that matters is **unplugged for more than an hour**, because some firmware behaves
differently on battery. Unplug it across at least one scheduled wake-up time with the screen off,
then plug it back in and run `scripts/logs.sh`. You want a `WAKE fired ... late=0s` (or a few
seconds) line for each wake-up time, and a last boot reason that isn't `shutdown,...`.

## 10. Harden (last step)

```sh
scripts/harden.sh
```

asks, then adds the restrictions that make the kiosk hard to break out of: no factory reset from
Settings, no safe mode, no new users, no installing or uninstalling apps, no accounts, no date/time
changes, no Bluetooth, no crash dialogs. **USB debugging stays on**, so the player can still be
updated and unlocked from the Mac. A plain `adb install` is now refused, but `scripts/install.sh`
lifts the block for an update and puts it back afterwards.

## Living with it

- **Charging:** measured on the X55, it uses about 1% every 65–70 minutes asleep and about 2% for
  a wake-up nobody answers (10 minutes of screen and chimes). The first real day (9 sessions,
  9 bonus rounds and a night, 18¾ hours unplugged) used 18%, so a charge lasts about 4 days.
  Charging over USB is fine at any time.
- **Settings** (wake-up times, tables, new cards a day and so on): parent mode, or
  `scripts/settings.sh` from the Mac. See [Changing cards and schedule](../README.md#changing-cards-and-schedule).
- **Updates:** `scripts/build.sh release && scripts/install.sh release`. Progress is kept.
- **Clock:** with no network the clock drifts slowly (about 1 s a day). Every `install.sh`
  resyncs it, or run `scripts/sync-time.sh`. Summer and winter time switch by themselves: the time
  zone rules are on the device.
- **Progress and answer history:** `scripts/logs.sh` saves `reviews.jsonl`, one line per answer.

## Undoing it

- `scripts/unharden.sh` gives the player back as a normal Android device: removes the restrictions
  and device owner, unhides apps, brings back the status bar, navigation bar and lock screen.
  `--keep-owner` keeps device owner so `provision.sh` can lock it again later. Progress is kept.
- No Mac to hand: parent mode → **Exit kiosk for 30 min** opens Settings.
- See [Recovery and unlocking](../README.md#recovery-and-unlocking) for the rest, including what a
  hardware factory reset can and can't do.

## Using a different player

What is specific to the X55 and where it lives:

| X55 quirk | Where it's handled |
|---|---|
| Developer options via **Model**, not Build number | this guide |
| vendor auto shutdown (`persist.sys.auto.sd.time`) | `provision.sh`, `harden.sh`, `logs.sh` |
| MediaTek DuraSpeed background killer | `provision.sh` |
| list of preinstalled apps to hide | `HIDE_SYSTEM` in `provision.sh` |
| navigation bar height for `wm overscan` | measured by `provision.sh` (63 px on the X55) |
| `FLAG_KEEP_SCREEN_ON` unreliable after an alarm wake | the app holds its own screen wake lock |

The app targets Android 10 (API 29). `wm overscan` was removed in Android 11, so on newer devices the
navigation bar would stay hidden only through immersive mode, which briefly lets it reappear.
Whatever the device, test wake-ups **unplugged** before trusting it: battery-saving firmware is
where cheap devices differ most.
