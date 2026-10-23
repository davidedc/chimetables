package app.timestables;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import app.timestables.core.Settings;
import app.timestables.core.WakePlanner;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Schedules wake-ups. Real sessions use setAlarmClock(), the strongest alarm Android offers: it
 * leaves Doze early and is never deferred. Test wakes can also use setExactAndAllowWhileIdle()
 * for comparison (gate G4).
 */
public final class Alarms {
    public static final String ACTION_WAKE = "app.timestables.WAKE";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_AT = "at";
    public static final String KIND_SESSION = "session";
    public static final String KIND_TEST_ALARMCLOCK = "test-alarmclock";
    public static final String KIND_TEST_EXACT = "test-exact";

    private static final int REQ_SESSION = 1;
    private static final int REQ_TEST_ALARMCLOCK = 2;
    private static final int REQ_TEST_EXACT = 3;

    private Alarms() {}

    /** (Re)schedules the next session wake-up from the parent's schedule. Returns its time or 0. */
    public static long scheduleNext(Context context, String reason) {
        Settings s = Store.get(context).settings();
        ZonedDateTime next = WakePlanner.next(s.wakeTimes, s.quietStart, s.quietEnd, ZonedDateTime.now());
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent op = wakeIntent(context, KIND_SESSION, REQ_SESSION, next == null ? 0 : toMillis(next));
        if (next == null) {
            am.cancel(op);
            Store.get(context).setNextWake(0);
            AppLog.log(context, "schedule (" + reason + "): no wake times outside quiet hours");
            return 0;
        }
        long at = toMillis(next);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent(context)), op);
        Store.get(context).setNextWake(at);
        AppLog.log(context, "schedule (" + reason + "): next session wake " + format(at));
        return at;
    }

    /** Schedules a one-off test wake {@code seconds} from now with the given method. */
    public static long scheduleTest(Context context, int seconds, boolean alarmClock) {
        long at = System.currentTimeMillis() + seconds * 1000L;
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (alarmClock) {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent(context)),
                    wakeIntent(context, KIND_TEST_ALARMCLOCK, REQ_TEST_ALARMCLOCK, at));
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at,
                    wakeIntent(context, KIND_TEST_EXACT, REQ_TEST_EXACT, at));
        }
        AppLog.log(context, "schedule test wake (" + (alarmClock ? "setAlarmClock" : "setExactAndAllowWhileIdle")
                + ") at " + format(at));
        return at;
    }

    private static PendingIntent wakeIntent(Context context, String kind, int requestCode, long at) {
        Intent i = new Intent(context, WakeReceiver.class)
                .setAction(ACTION_WAKE)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_AT, at);
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent showIntent(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, KioskActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static long toMillis(ZonedDateTime t) {
        return t.toInstant().toEpochMilli();
    }

    public static String format(long millis) {
        if (millis <= 0) return "none";
        return DateTimeFormatter.ofPattern("EEE dd MMM HH:mm:ss z")
                .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    public static String formatTime(long millis) {
        return DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }
}
