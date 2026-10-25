# How it works

A tour of the moving parts, for anyone changing the code or porting it to another device.

## The wake-up chain

```
Alarms.scheduleNext ──setAlarmClock()──▶ WakeReceiver ──▶ KioskActivity (CALLING) ──slide──▶ session
        ▲                                     │                                                │
        └──── after every wake, screen-off, ◀─┘ schedules the next one first                   │
              boot, time change, update,                                                       │
              app resume                       ◀──────────── screen off, back to SLEEPING ◀────┘
```

- **One alarm at a time.** `Alarms.scheduleNext` asks `WakePlanner` for the next wake-up time that
  isn't in quiet hours and sets a single `AlarmManager.setAlarmClock()` alarm. That's the strongest
  alarm Android has: it leaves Doze early and is never deferred.
- **Rescheduled from everywhere**, so a missed broadcast can't break the chain: right after each wake
  fires, when the screen goes off, on boot (`BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED`), when the
  time or time zone changes, after an app update, and whenever the kiosk screen resumes (as HOME,
  it resumes after every boot).
- **`WakeReceiver`** logs the wake (how late it was, screen state, Doze, battery %), schedules the
  next one, and skips this one if `Session.nothingToDo` says nothing is due and no new card is
  allowed today. Otherwise it turns the screen on with a wake lock and starts `KioskActivity`.
- **`KioskActivity`** moves through `SLEEPING → CALLING → QUESTION ⇄ FEEDBACK → DONE → SLEEPING`.
  While calling it holds its own screen wake lock and chimes on the alarm stream every 2 minutes,
  up to 5 times. `FLAG_KEEP_SCREEN_ON` isn't used because it proved unreliable on this firmware
  after an alarm wake.

## Kiosk lock-down

The app is the **device owner** (set once over adb with `dpm set-device-owner`), which lets it:

- run in **lock task** mode with no system UI features, so Home, Recents, the notification shade
  and the power menu do nothing
- be the **permanent HOME** app, so it is what Android starts after every boot
- turn off the lock screen and the status bar, set the screen timeout and brightness, postpone
  system updates, set the time and time zone, and hide other apps
- add user restrictions: `no_safe_boot` and `no_add_managed_profile` at provisioning time, and the
  rest in `harden.sh` (factory reset from Settings, installs and uninstalls, accounts, users,
  date/time, Bluetooth, crash dialogs)

`Kiosk.java` applies these, `Maintenance.java` handles the 30-minute "exit kiosk" from parent
mode, and `AdminReceiver` is the device-admin component. The navigation bar is pushed off-screen
with `wm overscan` by `provision.sh`, because Android 10's immersive mode briefly lets it reappear.

## Talking to the app from the Mac

The scripts drive the app through `CommandReceiver`, a broadcast receiver protected by
`android.permission.DUMP`. `adb shell` holds that permission and ordinary apps can't get it, so only
someone with USB debugging access can send commands:

```sh
adb shell am broadcast -n app.timestables/.CommandReceiver -a app.timestables.cmd.STATUS
```

The commands are `STATUS`, `PROVISION`, `HARDEN`, `ALLOW_INSTALLS`, `UNHARDEN`, `SET_PIN`,
`TEST_WAKE`, `SETTINGS`, `SET_TIME`, `RESTRICT`, `SCHEDULE`, `BEEP` and `RESET_PROGRESS`.
`scripts/env.sh` wraps them as `app_cmd`.

## Data

| What | Where | Notes |
|---|---|---|
| cards, streaks, badges, stickers collected | `progress.json` in app-private storage | written atomically after every answer; a corrupt file is kept aside, not lost |
| settings | `settings.json` in app-private storage | parent mode or `scripts/settings.sh` |
| parent PIN | SharedPreferences | salted PBKDF2-HMAC-SHA256 hash (20,000 iterations); wrong PINs lock it out for a minute, doubling |
| event log | `/sdcard/Android/data/app.timestables/files/app.log` | wakes, chimes, sessions, policies; rotates at 512 KB |
| answer history | `.../files/reviews.jsonl` | one JSON line per answer: card, what was typed, right or wrong, grade, response time, interval |

App updates keep everything. Unhardening keeps everything. A factory reset loses everything.

## Code layout

- `core/` is plain Java with no Android dependencies, and has the JVM unit tests:
  - `Srs`: the scheduler
  - `Answer`: grading
  - `Session`: picking and running cards
  - `Deck`: all cards and history
  - `Badges`, `WakePlanner`, `Settings`, `Json`, `PinHasher`
- The Android side is deliberately small: plain Views built in code, no AndroidX, no XML layouts.
  `Ui` holds the colours and the button style.

## Verified on the real device

| Gate | Result | How |
|---|---|---|
| G1 sideload | PASS | `adb install` |
| G2 device owner | PASS | `dpm set-device-owner` with 0 accounts and 1 user; no reset needed |
| G3 lock task | PASS | Home, Back, Recents, shade, assistant blocked; power long-press shows no menu; no lock screen after power off and on; safe mode blocked by `DISALLOW_SAFE_BOOT` |
| G4 autonomous wake | PASS | short tests from screen-off, in forced deep Doze and with the process killed; **unplugged for 4.5 hours**, the 12:30 wake fired 1 s late after 2 hours with the screen off, chimed 5 times and went back to sleep; **unplugged for 18¾ hours overnight**, 16:00 correctly stayed asleep (nothing due), 18:00 and 08:30 fired 1 s late, silent through quiet hours, no shutdown |
| G5 reboot | PASS | boots straight into the kiosk, lock task on, alarm re-armed |
| G6 time | PASS | no network, so auto time is off and the clock is set from the Mac to within about 0.1 s (it drifted about 1 s in 18 hours); the switch from summer to winter time (25 Oct 2026) happened by itself, and the 08:30 wake set the evening before fired at 08:30 GMT |

The unplugged test is the one that matters most. The first overnight run failed because of the vendor
auto shutdown (see [device setup](device-setup.md#7-provision-device-owner-and-kiosk)), which no
cabled test could reveal.
