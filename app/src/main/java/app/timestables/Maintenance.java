package app.timestables;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Parent "exit kiosk": leaves lock task and re-enables the status bar for a limited time.
 * The navigation bar is pushed off-screen by provisioning (wm overscan), so the way back is a
 * notification in the shade, or the automatic return when the time is up.
 */
public final class Maintenance {
    public static final String ACTION_END = "app.timestables.END_MAINTENANCE";
    private static final long DURATION_MS = 30 * 60_000L;
    private static final String CHANNEL = "maintenance";
    private static final int NOTIFICATION_ID = 1;

    private Maintenance() {}

    public static void start(Context context) {
        long until = System.currentTimeMillis() + DURATION_MS;
        Store.get(context).setMaintenanceUntil(until);
        if (Kiosk.isDeviceOwner(context)) {
            Kiosk.dpm(context).setStatusBarDisabled(AdminReceiver.component(context), false);
            Kiosk.releaseLockTask(context);
        }
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Maintenance", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Kiosk paused for maintenance")
                .setContentText("Tap to return to Times Tables (automatic at " + Alarms.formatTime(until) + ")")
                .setOngoing(true)
                .setContentIntent(endIntent(context, PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        nm.notify(NOTIFICATION_ID, n);
        context.getSystemService(AlarmManager.class)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, endIntent(context, PendingIntent.FLAG_UPDATE_CURRENT));
        AppLog.log(context, "maintenance started until " + Alarms.format(until));
    }

    public static void end(Context context) {
        Store.get(context).setMaintenanceUntil(0);
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        PendingIntent pending = endIntent(context, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) context.getSystemService(AlarmManager.class).cancel(pending);
        if (Kiosk.isDeviceOwner(context)) {
            Kiosk.allowLockTask(context);
            Kiosk.dpm(context).setStatusBarDisabled(AdminReceiver.component(context), true);
        }
        AppLog.log(context, "maintenance ended");
        context.startActivity(new Intent(context, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static PendingIntent endIntent(Context context, int flags) {
        Intent i = new Intent(context, WakeReceiver.class).setAction(ACTION_END);
        return PendingIntent.getBroadcast(context, 10, i, flags);
    }
}
