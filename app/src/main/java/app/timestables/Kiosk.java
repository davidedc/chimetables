package app.timestables;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.provider.Settings.System;

import app.timestables.core.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Device-owner policy. Every method is a no-op (returning a note) when the app is not device owner.
 * Base policies are safe to re-apply at any time; {@link #harden} adds the restrictions that also
 * block `adb install`, so it is only applied by scripts/harden.sh and undone by {@link #unharden}.
 */
public final class Kiosk {
    /** Restrictions added by harden.sh. DISALLOW_DEBUGGING_FEATURES is deliberately absent: adb must keep working. */
    static final String[] HARDEN_RESTRICTIONS = {
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_REMOVE_USER,
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_BLUETOOTH,
    };

    private static final String PREFS = "kiosk";
    private static final String KEY_HIDDEN = "hidden_packages";
    private static final String KEY_CAPTURE_ALLOWED = "screen_capture_allowed";

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

    /**
     * Lock task whitelist with no system UI (no home, recents, notifications, power menu, status bar
     * info), the kiosk as persistent HOME, no keyguard, display settings and OTA postponement.
     */
    public static String applyBasePolicies(Context context) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        Settings s = Store.get(context).settings();
        List<String> notes = new ArrayList<>();

        allowLockTask(context);
        notes.add("lockTask");

        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        dpm.addPersistentPreferredActivity(admin, home, new ComponentName(context, KioskActivity.class));
        notes.add("home");

        notes.add("keyguardDisabled=" + dpm.setKeyguardDisabled(admin, true));
        notes.add("statusBarDisabled=" + dpm.setStatusBarDisabled(admin, true));
        try {
            dpm.setSystemUpdatePolicy(admin, SystemUpdatePolicy.createPostponeInstallPolicy());
            notes.add("otaPostponed");
        } catch (RuntimeException e) {
            notes.add("otaPolicy failed: " + e.getMessage());
        }
        notes.add(applyDisplaySettings(context, s));
        notes.add("screenCaptureDisabled=" + applyScreenCapture(context));
        tryGlobal(dpm, admin, Global.STAY_ON_WHILE_PLUGGED_IN, "0", notes);
        // No network on this device, so network time can never sync. Auto time stays off and the
        // scripts set the clock from the Mac instead (setTime() refuses while auto time is on).
        tryGlobal(dpm, admin, Global.AUTO_TIME, "0", notes);

        String summary = String.join(", ", notes);
        AppLog.log(context, "base policies: " + summary);
        return summary;
    }

    public static void allowLockTask(Context context) {
        if (!isDeviceOwner(context)) return;
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        dpm.setLockTaskPackages(admin, new String[] {context.getPackageName()});
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
    }

    /**
     * No screenshots or screen recording on the whole device (they would land in a photo library
     * anyone with the device could look through). `CAPTURE --ez allow true` from the Mac lifts it
     * for checking the screens; returns true when capture is now disabled.
     */
    public static boolean applyScreenCapture(Context context) {
        if (!isDeviceOwner(context)) return false;
        boolean disabled = !prefs(context).getBoolean(KEY_CAPTURE_ALLOWED, false);
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        if (dpm.getScreenCaptureDisabled(admin) != disabled) dpm.setScreenCaptureDisabled(admin, disabled);
        return disabled;
    }

    public static String allowScreenCapture(Context context, boolean allow) {
        if (!isDeviceOwner(context)) return "not device owner";
        prefs(context).edit().putBoolean(KEY_CAPTURE_ALLOWED, allow).commit();
        String summary = "screenshots " + (applyScreenCapture(context) ? "blocked" : "allowed");
        AppLog.log(context, summary);
        return summary;
    }

    /** Maintenance: emptying the whitelist ends lock task immediately. */
    public static void releaseLockTask(Context context) {
        if (!isDeviceOwner(context)) return;
        dpm(context).setLockTaskPackages(AdminReceiver.component(context), new String[0]);
    }

    public static String applyDisplaySettings(Context context, Settings s) {
        if (!isDeviceOwner(context)) return "display: not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        List<String> notes = new ArrayList<>();
        trySystem(dpm, admin, System.SCREEN_OFF_TIMEOUT, String.valueOf(s.screenTimeoutSeconds * 1000), notes);
        trySystem(dpm, admin, System.SCREEN_BRIGHTNESS_MODE,
                String.valueOf(System.SCREEN_BRIGHTNESS_MODE_MANUAL), notes);
        trySystem(dpm, admin, System.SCREEN_BRIGHTNESS, String.valueOf(s.brightness), notes);
        return String.join(", ", notes);
    }

    public static String setTimeZone(Context context, String zone) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        List<String> notes = new ArrayList<>();
        tryGlobal(dpm, admin, Global.AUTO_TIME_ZONE, "0", notes);
        notes.add("setTimeZone(" + zone + ")=" + dpm.setTimeZone(admin, zone));
        return String.join(", ", notes);
    }

    /** Sets the wall clock (device owner, auto time must be off). */
    public static String setTime(Context context, long millis) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        List<String> notes = new ArrayList<>();
        tryGlobal(dpm, admin, Global.AUTO_TIME, "0", notes);
        long before = java.lang.System.currentTimeMillis();
        boolean ok = dpm.setTime(admin, millis);
        notes.add("setTime=" + ok + " correction=" + (millis - before) + "ms");
        String summary = String.join(", ", notes);
        AppLog.log(context, "clock: " + summary);
        return summary;
    }

    /** Adds or clears one user restriction (testing, e.g. DISALLOW_SAFE_BOOT before full hardening). */
    public static String restrict(Context context, String key, boolean add) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        if (add) dpm.addUserRestriction(admin, key);
        else dpm.clearUserRestriction(admin, key);
        String summary = (add ? "added " : "cleared ") + key + "; now " + new java.util.TreeSet<>(dpm.getUserRestrictions(admin).keySet());
        AppLog.log(context, "restriction " + summary);
        return summary;
    }

    /** Hides the given packages (they stop running and vanish for the user). Remembers them for unharden. */
    public static String hidePackages(Context context, List<String> packages) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        Set<String> hidden = new HashSet<>(prefs(context).getStringSet(KEY_HIDDEN, new HashSet<String>()));
        List<String> ok = new ArrayList<>(), failed = new ArrayList<>();
        for (String pkg : packages) {
            if (pkg.isEmpty() || pkg.equals(context.getPackageName())) continue;
            boolean done = false;
            try {
                done = dpm.setApplicationHidden(admin, pkg, true);
            } catch (RuntimeException e) {
                AppLog.log(context, "hide " + pkg + " threw " + e);
            }
            if (done || dpm.isApplicationHidden(admin, pkg)) {
                ok.add(pkg);
                hidden.add(pkg);
            } else {
                failed.add(pkg);
            }
        }
        prefs(context).edit().putStringSet(KEY_HIDDEN, hidden).commit();
        String summary = "hidden " + ok.size() + (failed.isEmpty() ? "" : ", failed " + failed);
        AppLog.log(context, summary + " " + ok);
        return summary;
    }

    public static String harden(Context context) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        for (String r : HARDEN_RESTRICTIONS) dpm.addUserRestriction(admin, r);
        String summary = "restrictions " + new java.util.TreeSet<>(dpm.getUserRestrictions(admin).keySet());
        AppLog.log(context, "harden: " + summary);
        return summary;
    }

    /** Lifts only the restrictions that block `adb install`, so an update can go in. The kiosk stays. */
    public static String allowInstalls(Context context) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS);
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
        String summary = "installs allowed; restrictions " + new java.util.TreeSet<>(dpm.getUserRestrictions(admin).keySet());
        AppLog.log(context, summary);
        return summary;
    }

    /**
     * Emergency undo: clears every restriction harden.sh set, unhides apps, drops the persistent HOME,
     * re-enables keyguard/status bar, leaves lock task and optionally gives up device ownership.
     */
    public static String unharden(Context context, boolean removeOwner) {
        if (!isDeviceOwner(context)) return "not device owner";
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = AdminReceiver.component(context);
        for (String r : HARDEN_RESTRICTIONS) dpm.clearUserRestriction(admin, r);
        Set<String> hidden = prefs(context).getStringSet(KEY_HIDDEN, new HashSet<String>());
        for (String pkg : hidden) {
            try {
                dpm.setApplicationHidden(admin, pkg, false);
            } catch (RuntimeException e) {
                AppLog.log(context, "unhide " + pkg + " threw " + e);
            }
        }
        prefs(context).edit().remove(KEY_HIDDEN).commit();
        dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
        dpm.setKeyguardDisabled(admin, false);
        dpm.setStatusBarDisabled(admin, false);
        dpm.setScreenCaptureDisabled(admin, false);
        dpm.setSystemUpdatePolicy(admin, null);
        releaseLockTask(context);
        String summary = "cleared restrictions, unhid " + hidden.size() + " apps, released HOME and lock task";
        if (removeOwner) {
            dpm.clearDeviceOwnerApp(context.getPackageName());
            summary += ", device owner removed";
        }
        AppLog.log(context, "unharden: " + summary);
        return summary;
    }

    public static Set<String> hiddenPackages(Context context) {
        return prefs(context).getStringSet(KEY_HIDDEN, new HashSet<String>());
    }

    public static List<String> activeRestrictions(Context context) {
        if (!isDeviceOwner(context)) return Arrays.asList();
        return new ArrayList<>(dpm(context).getUserRestrictions(AdminReceiver.component(context)).keySet());
    }

    /** Turns the screen off right away (end of session). Falls back to the normal timeout otherwise. */
    public static void screenOff(Context context) {
        if (!isDeviceOwner(context)) return;
        try {
            dpm(context).lockNow();
        } catch (SecurityException e) {
            AppLog.log(context, "lockNow failed: " + e.getMessage());
        }
    }

    private static void tryGlobal(DevicePolicyManager dpm, ComponentName admin, String key, String value,
                                  List<String> notes) {
        try {
            dpm.setGlobalSetting(admin, key, value);
            notes.add(key + "=" + value);
        } catch (RuntimeException e) {
            notes.add(key + " failed: " + e.getMessage());
        }
    }

    private static void trySystem(DevicePolicyManager dpm, ComponentName admin, String key, String value,
                                  List<String> notes) {
        try {
            dpm.setSystemSetting(admin, key, value);
            notes.add(key + "=" + value);
        } catch (RuntimeException e) {
            notes.add(key + " failed: " + e.getMessage());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
