package app.timestables;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.PowerManager;

import app.timestables.core.Deck;
import app.timestables.core.Json;
import app.timestables.core.PinHasher;
import app.timestables.core.Settings;

import java.util.Arrays;
import java.util.List;

/**
 * Maintenance commands for the scripts in scripts/. Protected by android.permission.DUMP in the
 * manifest: `adb shell` holds it, ordinary apps cannot. Results come back as the broadcast's result
 * data, which `adb shell am broadcast` prints.
 *
 * <pre>
 * am broadcast -n app.timestables/.CommandReceiver -a app.timestables.cmd.STATUS
 *   PROVISION [--es hide pkg1,pkg2] [--es tz Europe/London]
 *   HARDEN | ALLOW_INSTALLS | UNHARDEN [--ez remove_owner true]
 *   SET_PIN --es pin 1234
 *   TEST_WAKE --ei seconds 60 [--es method alarmclock|exact]
 *   SETTINGS [--es json '{"tables":[2,3],"wakeTimes":["07:45"]}']   show, or merge and apply
 *   SET_TIME --el millis <epoch ms>        (auto time is switched off)
 *   RESTRICT --es add|clear no_safe_boot
 *   CAPTURE --ez allow true|false          screenshots (blocked unless allowed)
 *   SCHEDULE | BEEP | RESET_PROGRESS
 * </pre>
 */
public class CommandReceiver extends BroadcastReceiver {
    private static final String PREFIX = "app.timestables.cmd.";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = String.valueOf(intent.getAction());
        String cmd = action.startsWith(PREFIX) ? action.substring(PREFIX.length()) : action;
        String result;
        try {
            result = run(context, cmd, intent);
        } catch (RuntimeException e) {
            result = "ERROR " + e;
        }
        AppLog.log(context, "adb command " + cmd + ": " + result);
        setResultData(result);
    }

    private String run(Context context, String cmd, Intent intent) {
        switch (cmd) {
            case "STATUS":
                return status(context);
            case "PROVISION": {
                StringBuilder sb = new StringBuilder(Kiosk.applyBasePolicies(context));
                String tz = intent.getStringExtra("tz");
                if (tz != null) sb.append("; ").append(Kiosk.setTimeZone(context, tz));
                String hide = intent.getStringExtra("hide");
                if (hide != null) sb.append("; ").append(Kiosk.hidePackages(context, Arrays.asList(hide.split(","))));
                sb.append("; next wake ").append(Alarms.format(Alarms.scheduleNext(context, "provision")));
                return sb.toString();
            }
            case "HARDEN":
                return Kiosk.harden(context);
            case "ALLOW_INSTALLS":
                return Kiosk.allowInstalls(context);
            case "UNHARDEN":
                return Kiosk.unharden(context, intent.getBooleanExtra("remove_owner", false));
            case "SET_PIN": {
                String pin = intent.getStringExtra("pin");
                if (!PinHasher.isValidPin(pin)) return "ERROR pin must be 4-8 digits";
                Store.get(context).setPinHash(PinHasher.hash(pin));
                return "PIN set";
            }
            case "TEST_WAKE": {
                int seconds = intent.getIntExtra("seconds", 60);
                boolean alarmClock = !"exact".equals(intent.getStringExtra("method"));
                return "test wake at " + Alarms.format(Alarms.scheduleTest(context, seconds, alarmClock));
            }
            case "SETTINGS": {
                Store store = Store.get(context);
                String json = intent.getStringExtra("json");
                if (json != null) {
                    try {
                        Json.settingsFromJson(json, store.settings());
                    } catch (org.json.JSONException e) {
                        return "ERROR bad json: " + e.getMessage();
                    }
                    store.saveSettings();
                    Kiosk.applyDisplaySettings(context, store.settings());
                    Alarms.scheduleNext(context, "settings via adb");
                }
                return Json.settingsToJson(store.settings()).replace("\n", "");
            }
            case "SET_TIME": {
                long millis = intent.getLongExtra("millis", 0);
                if (millis < 1_600_000_000_000L) return "ERROR --el millis <epoch ms> required";
                return Kiosk.setTime(context, millis) + "; now " + Alarms.format(System.currentTimeMillis());
            }
            case "RESTRICT": {
                String add = intent.getStringExtra("add");
                String clear = intent.getStringExtra("clear");
                if (add != null) return Kiosk.restrict(context, add, true);
                if (clear != null) return Kiosk.restrict(context, clear, false);
                return "ERROR --es add|clear <restriction> required";
            }
            case "CAPTURE":
                if (!intent.hasExtra("allow")) return "ERROR --ez allow true|false required";
                return Kiosk.allowScreenCapture(context, intent.getBooleanExtra("allow", false));
            case "SCHEDULE":
                return "next wake " + Alarms.format(Alarms.scheduleNext(context, "adb"));
            case "BEEP":
                Chime.wake(context);
                return "beep played";
            case "RESET_PROGRESS":
                Store.get(context).resetProgress();
                return "progress reset";
            default:
                return "ERROR unknown command " + cmd;
        }
    }

    private static String status(Context context) {
        Store store = Store.get(context);
        Settings s = store.settings();
        long now = System.currentTimeMillis();
        List<?> active = store.activeCards();
        Deck.Stats stats = Deck.stats(store.activeCards(), now);
        PowerManager pm = context.getSystemService(PowerManager.class);
        AudioManager am = context.getSystemService(AudioManager.class);
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        ActivityManager.RunningAppProcessInfo me = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(me);
        return "deviceOwner=" + Kiosk.isDeviceOwner(context)
                + " lockTask=" + Kiosk.lockTaskStateName(context)
                + " maintenance=" + store.inMaintenance(now)
                + " pinSet=" + (store.pinHash() != null)
                + " nextWake=" + Alarms.format(store.nextWake()).replace(' ', '_')
                + " cards=" + active.size() + " due=" + stats.due + " unseen=" + stats.unseen
                + " sessionSize=" + s.sessionSize + " times=" + s.wakeTimes
                + " stickers=" + store.deck().stickers.size() + " newStickersLeft=" + store.deck().stickersLeft(Stickers.more(context))
                + " riddles=" + (store.deck().unlockedDay > 0 ? store.deck().riddles.size() + "/" + RiddleBank.get(context).size() : "locked")
                + " alarmVol=" + am.getStreamVolume(AudioManager.STREAM_ALARM) + "/" + am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                + " screenOn=" + pm.isInteractive()
                + " ignoringBatteryOpt=" + pm.isIgnoringBatteryOptimizations(context.getPackageName())
                + " battery=" + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%"
                + " importance=" + me.importance
                + " restrictions=" + Kiosk.activeRestrictions(context)
                + " screenshots=" + (Kiosk.isDeviceOwner(context)
                        && Kiosk.dpm(context).getScreenCaptureDisabled(AdminReceiver.component(context)) ? "blocked" : "allowed")
                + " hidden=" + Kiosk.hiddenPackages(context).size();
    }
}
