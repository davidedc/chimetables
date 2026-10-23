package app.timestables;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/** Device-owner policy helpers. Every method is a no-op when the app is not device owner. */
public final class Kiosk {
    private Kiosk() {}

    public static DevicePolicyManager dpm(Context context) {
        return context.getSystemService(DevicePolicyManager.class);
    }

    public static boolean isDeviceOwner(Context context) {
        return dpm(context).isDeviceOwnerApp(context.getPackageName());
    }

    public static boolean isInLockTask(Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    public static String lockTaskStateName(Context context) {
        switch (context.getSystemService(ActivityManager.class).getLockTaskModeState()) {
            case ActivityManager.LOCK_TASK_MODE_LOCKED: return "LOCKED";
            case ActivityManager.LOCK_TASK_MODE_PINNED: return "PINNED";
            default: return "NONE";
        }
    }

    /** Whitelist this app for lock task with no system UI features (no home, recents, power menu, status bar). */
    public static void allowLockTask(Context context) {
        if (!isDeviceOwner(context)) return;
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        dpm.setLockTaskPackages(admin, new String[] {context.getPackageName()});
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
    }
}
