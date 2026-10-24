# Chimetables

A single-purpose, Tamagotchi-style times-tables flashcard device built from a MECHEN X55
"MP3 player" (MediaTek MT6750, Android 10, 480×800). The player sleeps, wakes itself at set
times, chimes, and runs a short spaced-repetition session (default 5 cards), then switches the
screen off and schedules the next wake-up. It is locked to this one app.

Plain Android Views, Java, no AndroidX, no Play Services, no network, no ads.

## Documentation

- [Device setup](docs/device-setup.md): from the box to a locked kiosk, including Developer
  options and USB debugging on the X55 (and the menu **not** to tap)
- [How it works](docs/how-it-works.md): the wake-up chain, kiosk lock-down, data, test results
- [Learning design](docs/learning-design.md): session sizes, grading, bonus rounds, and the
  research behind them

## How it behaves

- **Wake-ups** at 07:45, 12:30, 16:00 and 18:00 by default (ours: 08:30 instead of 07:45),
  never in quiet hours (19:30–07:00). A wake is skipped if nothing is due and no new card is
  allowed today.
- **Calling:** the screen turns on and a friendly chime plays on the alarm stream, repeating every
  2 minutes up to 5 times. The child **slides** the big slider to start. Taps and pocket brushes
  do nothing, so a session can't start (or be answered) by accident.
- **Cards:** `7 × 8 = ?` in huge text; she **types the answer** on a big keypad (as in the UK
  Year 4 Multiplication Tables Check). The right number is accepted the moment it's typed; a wrong
  one shows a gentle "Not 54, let's remember 56!" and the card comes back two cards later.
  Happy sounds only. There are no self-rating buttons: the app grades her (see the scheduler below).
- **End:** "Well done!", or confetti, a fanfare and a **sticker** when at least 80% were right first
  time or a badge was earned. Then the screen goes off.
- **Sleeping screen** (if the power button is pressed): when the next wake-up is, the current
  streak and recent badges, and "Slide to play" if cards are due. Playing early uses the same due
  cards and daily new-card allowance, so a later wake-up just finds less to do (or stays silent).
- **Bonus round:** when nothing is due, the slider offers a bonus round instead: up to 5 cards she
  has already met, drawn at random from the least established ones. It is practice only: the
  schedule, streak, badges and stickers are untouched (confetti for a great round, nothing to
  collect), and `reviews.jsonl` marks those answers `"bonus":true`.
- **Quiet hours** (19:30–07:00) also block playing by hand: no slider at all, just "Zzz…".

## Status: what was verified on the real device

| Gate | Result | Evidence |
|---|---|---|
| G1 sideload | PASS | `adb install` of the hello build, screenshot |
| G2 device owner | PASS | `dpm set-device-owner` with 0 accounts / 1 user, no reset needed |
| G3 lock task | PASS | Home, Back, Recents, shade, assist blocked (adb + by hand); power long-press shows no menu (by hand); no lock screen after power off/on; safe boot: entered without the restriction, blocked with `DISALLOW_SAFE_BOOT` (Volume Down held during boot) |
| G4 autonomous wake | PASS | `setAlarmClock` and `setExactAndAllowWhileIdle` both fired on time from screen-off, in forced deep Doze, and after the process was killed; screen on, chime heard, repeat chime heard. **Unplugged for 4.5 h:** the 12:30 wake fired 1 s late after 2 h with the screen off, chimed 5 times, went back to sleep; no shutdown (after fixing the vendor auto shutdown, see Known limits) |
| G5 reboot | PASS | boots straight into the kiosk (HOME, lock task), `BOOT_COMPLETED` received, 07:45 alarm re-armed |
| G6 time | PASS (option A) | Europe/London; no network, so auto time is off and the clock is set from the Mac (≈0.1 s) |

## Setting up from scratch

The full walkthrough, with troubleshooting, is in [docs/device-setup.md](docs/device-setup.md).
The short version:

### 1. Mac toolchain (Apple Silicon, Homebrew)

```sh
brew install --cask android-platform-tools android-commandlinetools
brew install openjdk@17 gradle libmtp
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools --licenses
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-29"
```

The scripts find JDK 17 and the SDK at those Homebrew paths (see `scripts/env.sh`). The Gradle
wrapper pins Gradle 9.8.2; the Android Gradle Plugin downloads build-tools 36 on first build.

### 2. USB debugging on the X55 (quirks!)

- Use a **data** USB-C cable straight into the Mac. The player's original cable was charge-only,
  and the dock/monitor ports never passed data.
- Developer options: Settings → About phone → tap **Model** 7 times (not Build number).
  **Build number** taps open a vendor factory-test menu whose "device reset" button factory-resets
  the device. If any unexpected menu appears, press Back and touch nothing.
- Developer options → USB debugging on → Allow (tick "Always allow").
- If Developer options can't be reached, the `devopts` module builds a tiny helper APK that can be
  copied over USB file transfer (`mtp-sendfile devopts-debug.apk 2/DevOptions.apk`, 2 = Download).

### 3. Build, install, provision, lock

```sh
scripts/build.sh release        # runs the JVM unit tests too
scripts/install.sh release      # also sets the device clock from the Mac
scripts/provision.sh            # asks before making the app device owner
scripts/set-pin.sh              # or: tap the top-left corner 7 times on the device
scripts/harden.sh               # final lock-down (asks first)
```

Device owner needs **no accounts and a single user** on the device. If a Google account was added,
remove it first (or factory reset). `provision.sh` is safe to re-run.

## Parent mode

Tap the **top-left corner 7 times within 5 seconds**, then enter the PIN on the number pad
(3 wrong PINs lock it for a minute, doubling after that). Parent mode closes itself after
3 idle minutes. It shows:

- progress: mastered / learning / unseen / due / tricky cards, accuracy, streak, trickiest and
  slowest cards, every badge earned and when
- tables (1–12) and "multiply up to" 10 or 12
- wake-up times, quiet hours, cards per session, new cards per day, beeps if no answer
- beep volume (with test beep) and brightness
- test wake-up in 1 minute, change PIN, reset all progress
- **Exit kiosk for 30 min (maintenance)**: leaves lock task and opens Settings. Because the
  navigation bar is off-screen, the way back is the **"Kiosk paused" notification** in the shade
  (or wait 30 minutes; it returns by itself).

## Changing cards and schedule

On the device: parent mode (above). From the Mac:

```sh
scripts/settings.sh                                              # show
scripts/settings.sh '{"wakeTimes":["07:30","12:30","17:00"]}'
scripts/settings.sh '{"tables":[2,3,4,5,10],"maxFactor":10}'
scripts/settings.sh '{"quietStart":"20:00","quietEnd":"07:00","sessionSize":6,"newPerDay":4}'
scripts/settings.sh '{"volumePercent":60,"beepRepeats":3,"brightness":90}'
```

Only the fields given change; the next wake-up is rescheduled immediately. Cards are every `a × b`
where a or b is a selected table and both are in 2..maxFactor (plus the selected tables
themselves); `7×8` and `8×7` are separate cards. Defaults (2–12, up to 12×12 = 121 cards) live in
`app/src/main/java/app/timestables/core/Settings.java`.

## Updating the app

```sh
scripts/build.sh release && scripts/install.sh release
```

Progress and settings are kept. On a hardened device `install.sh` lifts the install block, installs
and re-hardens automatically. **Back up `keystore/`** (git-ignored): updates must be signed with the
same key, and a device-owner app can't simply be uninstalled and reinstalled.

## Scripts

| Script | Does |
|---|---|
| `build.sh [debug\|release]` | unit tests + APK. Debug builds are `testOnly` (removable device owner) |
| `install.sh [debug\|release]` | install/update in place, handles hardened devices, syncs the clock |
| `provision.sh [--keep-radios]` | device owner (asks), Doze whitelist, DuraSpeed + vendor auto shutdown off, Wi-Fi/BT off, nav bar off-screen, DND off, kiosk policies, Europe/London, hides 41 apps, clock |
| `set-pin.sh` | set or reset the parent PIN (hidden input, stored only as a salted PBKDF2 hash) |
| `settings.sh [json]` | show or change settings |
| `harden.sh [--yes]` | restrictions: no factory reset (Settings), safe boot, users, installs/uninstalls, accounts, date/time, Bluetooth, crash dialogs. **USB debugging stays on.** |
| `unharden.sh [--keep-owner] [--yes]` | emergency undo (see Recovery) |
| `logs.sh [lines\|--follow]` | pulls `app.log` (wakes, beeps, sessions) and `reviews.jsonl` into `logs/` |
| `sync-time.sh` | sets the device clock from the Mac |

Under the hood the scripts talk to the app through `CommandReceiver`, an adb-only broadcast receiver
(protected by `android.permission.DUMP`, which `adb shell` has and apps can't get):
`adb shell am broadcast -n app.timestables/.CommandReceiver -a app.timestables.cmd.STATUS`.

## The scheduler (child-tuned SM-2)

Implemented from the Anki manual's description of its SM-2 variant (not Anki's AGPL code):

- **New cards:** a wrong answer repeats 2 cards later in the same session; a right one comes back at
  the next session the same day; right again → 1 day. Easy → 3 days.
- **Reviews:** Good → interval × ease (start 2.5), Easy → × ease × 1.3 and ease +0.15, Again → ease
  −0.20, relearn in the session, then 1 day. Overdue cards get credit for the extra days (Anki's rule).
- **Graded automatically** from the typed answer and how long she took to **start** typing (so
  typing speed doesn't count): wrong → Again; right within 3 s → Easy; right later → Good; right but
  after **more than 10 s** → Hard (interval × 1.2, ease −0.15, and a learning card doesn't graduate
  yet). Times tables should be automatic, not worked out. Over 90 s is ignored as distraction.
  A card missed earlier in the same session can't come back as Easy.
- A wrong number is marked wrong as soon as it has as many digits as the answer, so she can't keep
  trying numbers until one is accepted.
- **Tricky cards:** forgotten 4 times → "tricky" (💪 shown, interval capped at 14 days) until
  remembered at that cap.
- Intervals capped at **60 days** so facts stay fresh, ±5% fuzz from 7 days so cards spread out,
  and **7×8 and 8×7 never in the same session** (Anki's "bury siblings").
- Up to 5 new cards a day by default (15 on ours, so all 121 are met in about 9 days and known
  facts quickly move out to 3+ days); due cards come first, so new cards slow down by themselves when reviews pile up.
- Every answer is appended to `reviews.jsonl` (time, card, what she typed, grade, response time, interval),
  enough to fit an FSRS model to her own data later if wanted.

Tests: `app/src/test/java/app/timestables/core/` (JVM, run by `build.sh`).

## Badges

🌱 First steps · 🔥 3-day streak · 📅 7 days · 🚀 14 days · 🏆 30 days · 🎯 perfect session ·
💎 10 perfect sessions · 🐦 early bird (5 mornings) · 🏠 full house (every call in a day) ·
⚡ 10 quick right answers in a row · 💪 beat a tricky card · 💯/🎖️/👑 100/500/1000 answers ·
🌓 half mastered · 🌕 everything mastered · 🏅 "7× master" for each table.
A streak counts days with at least one finished session; it isn't broken until a whole day is missed.

## Stickers

Celebration screens can show a sticker with a line under it. Stickers are personal pictures, so they
live in **`family-assets/stickers/`, which is git-ignored** and only bundled into your local build:

- put PNG/JPG/WebP files there (about 360 px, transparent background looks best)
- `family-assets/stickers/phrases.txt` maps each file to its line: `wow.png: Wow!! Amazing!`
- rebuild and install: `scripts/build.sh release && scripts/install.sh release`

One sticker appears per celebration, preferring ones she hasn't collected yet; the sleeping screen
shows the collection (🖼️ 3/9). With the folder empty or missing the app uses emoji instead.
Back this folder up along with `keystore/`.

## Recovery and unlocking

- **Forgot the PIN:** `scripts/set-pin.sh`.
- **Unlock the device completely** (back to a normal player): `scripts/unharden.sh`. Clears all
  restrictions, unhides apps, drops the kiosk as home, re-enables the status bar and lock screen,
  puts the navigation bar back, makes Launcher3 home and removes device owner. `--keep-owner` keeps
  ownership so `provision.sh` can re-lock later. Progress is kept.
- **App broken but adb works:** install a fixed build (`install.sh`), or `unharden.sh`. With a
  debug (testOnly) build, `adb shell dpm remove-active-admin app.timestables/.AdminReceiver` also
  removes device owner.
- **No adb at all:** parent mode → Exit kiosk → Settings → Developer options → USB debugging.
  Hardening never sets `DISALLOW_DEBUGGING_FEATURES`.
- **Last resort:** the hardware recovery factory reset (on MediaTek usually Power + Volume Up from
  off → recovery → Wipe data). **Software can't prevent this**; `DISALLOW_FACTORY_RESET` only blocks
  the Settings route. It wipes everything, including the app and progress.

## Known limits and device quirks

- Holding power ~10 s forces a hardware restart (it comes back into the kiosk); recovery-mode reset
  can't be blocked.
- The navigation bar is pushed off-screen with `wm overscan` (Android 10 only). Without it, Android's
  "immersive panic" re-shows it for ~2.5 s when power is pressed off and on within 5 s.
- `FLAG_KEEP_SCREEN_ON` is unreliable on this firmware after an alarm wake, so the app holds its own
  screen wake lock while calling (up to ~10.5 min) and during a session (until 3 min after the last touch).
- No network: the clock drifts slowly and loses a second or so per reboot; every `install.sh` resyncs.
- **Vendor auto shutdown**: out of the box this firmware powers the phone **off 60 minutes after the
  screen goes off** whenever no USB cable is attached (a hidden "Auto Shutdown" in Settings,
  property `persist.sys.auto.sd.time`; skipped while charging over USB or playing music, which is
  why cabled tests never showed it). That would silently kill every wake after the first. `provision.sh`
  sets it to `0` (never) and checks it; `scripts/logs.sh` prints it. If the player is ever found off
  with its charging screen, check `adb shell getprop sys.boot.reason` (`shutdown,shutdown_by_time` =
  this) and re-run `adb shell setprop persist.sys.auto.sd.time 0`.
- Vendor firmware: SELinux is **permissive**, the build fingerprint is spoofed, MediaTek DuraSpeed
  (background killer) is turned off and hidden by `provision.sh`, and the vendor factory-test app
  (`com.teksun.factorytest`) is hidden.

## Layout

```
app/src/main/java/app/timestables/
  core/            pure Java: Srs (scheduler), Answer (grading), Session, Deck, Badges, WakePlanner,
                   Settings, Json, PinHasher
  KioskActivity    the child's screen          ParentActivity  PIN-protected settings
  Alarms, WakeReceiver, SystemEventsReceiver   wake-ups and rescheduling
  Kiosk, Maintenance, AdminReceiver            device-owner policy
  CommandReceiver  adb commands for scripts/   Stickers, Chime, ConfettiView, SlideToStart, PinPad, Ui
devopts/           helper APK to open Developer options
family-assets/     (git-ignored) personal stickers bundled into local builds
scripts/           build/install/provision/harden/unharden/logs/...
```

## Licence

[MIT](LICENSE)
