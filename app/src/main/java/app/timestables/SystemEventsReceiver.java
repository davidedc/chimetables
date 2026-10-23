package app.timestables;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reschedules the next wake after boot, app update and clock/time-zone changes. */
public class SystemEventsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = String.valueOf(intent.getAction());
        AppLog.log(context, "system event " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Kiosk.applyBasePolicies(context);
        }
        Alarms.scheduleNext(context, action.substring(action.lastIndexOf('.') + 1));
    }
}
