package app.timestables;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.PowerManager;

import app.timestables.core.Session;
import app.timestables.core.Settings;

/**
 * Alarm target. Keeps the alarm chain going, then (if there is something to practise) wakes the
 * screen and brings the kiosk activity forward, which plays the chime.
 */
public class WakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Maintenance.ACTION_END.equals(intent.getAction())) {
            Maintenance.end(context);
            return;
        }
        if (!Alarms.ACTION_WAKE.equals(intent.getAction())) return;
        String kind = intent.getStringExtra(Alarms.EXTRA_KIND);
        long at = intent.getLongExtra(Alarms.EXTRA_AT, 0);
        long now = System.currentTimeMillis();
        PowerManager pm = context.getSystemService(PowerManager.class);
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        AppLog.log(context, "WAKE fired kind=" + kind + " scheduled=" + Alarms.format(at)
                + " late=" + (at > 0 ? (now - at) / 1000 + "s" : "?")
                + " screenOn=" + pm.isInteractive() + " doze=" + pm.isDeviceIdleMode()
                + " battery=" + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%");

        boolean session = Alarms.KIND_SESSION.equals(kind);
        if (session) Alarms.scheduleNext(context, "after wake");

        Store store = Store.get(context);
        if (store.inMaintenance(now)) {
            AppLog.log(context, "maintenance mode: not waking the screen");
            return;
        }
        if (session) {
            Settings s = store.settings();
            if (Session.nothingToDo(store.deck(), store.srs(), store.activeCards(), s.newPerDay, now)) {
                AppLog.log(context, "nothing due: staying asleep");
                return;
            }
        }

        // Turn the screen on now; the activity keeps it on while calling the child.
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "timestables:wake");
        wl.acquire(15_000);
        AppLog.log(context, "screen wake lock acquired, screenOn=" + pm.isInteractive());

        Intent show = new Intent(context, KioskActivity.class)
                .setAction(KioskActivity.ACTION_WAKE)
                .putExtra(Alarms.EXTRA_KIND, kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(show);
            AppLog.log(context, "startActivity(WAKE) ok");
        } catch (RuntimeException e) {
            AppLog.log(context, "startActivity(WAKE) failed: " + e);
        }
    }
}
