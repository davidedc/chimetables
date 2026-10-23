package app.timestables;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Device-owner receiver. Policy is applied by {@link Kiosk}, not here. */
public class AdminReceiver extends DeviceAdminReceiver {

    public static ComponentName component(Context context) {
        return new ComponentName(context.getApplicationContext(), AdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        AppLog.log(context, "admin enabled");
    }
}
