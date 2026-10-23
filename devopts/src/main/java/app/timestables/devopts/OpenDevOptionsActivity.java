package app.timestables.devopts;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * For firmware whose "tap Build number 7 times" is hijacked: tries to reach Developer options, and
 * writes Download/devopts-report.txt (installed packages, settings flags, any developer/debug/
 * factory/engineer activities) so it can be read back over USB file transfer.
 */
public class OpenDevOptionsActivity extends Activity {
    private static final String[] KEYWORDS = {"develop", "debug", "factory", "engineer", "usb", "adb", "test"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        StringBuilder report = new StringBuilder();
        List<ComponentName> candidates = new ArrayList<>();
        buildReport(report, candidates);
        String saved = saveReport(report.toString());

        TextView help = new TextView(this);
        help.setTextSize(16);
        help.setText("Report saved: " + saved + "\n\nDeveloper options enabled: "
                + global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
                + "\nUSB debugging enabled: " + global(Settings.Global.ADB_ENABLED)
                + "\n\nDon't press these yet unless asked. Candidate screens found: " + candidates.size());
        root.addView(help);

        addButton(root, "Open Developer options", new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        addButton(root, "Open About phone", new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        for (ComponentName c : candidates) {
            addButton(root, c.getPackageName() + "\n" + c.getShortClassName(), new Intent().setComponent(c));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void addButton(LinearLayout root, String label, Intent target) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(v -> {
            try {
                startActivity(new Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Didn't work: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(b);
    }

    private String global(String key) {
        return String.valueOf(Settings.Global.getString(getContentResolver(), key));
    }

    private void buildReport(StringBuilder r, List<ComponentName> candidates) {
        PackageManager pm = getPackageManager();
        r.append("model=").append(Build.MODEL).append(" device=").append(Build.DEVICE)
                .append(" board=").append(Build.BOARD).append(" hw=").append(Build.HARDWARE)
                .append(" release=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" display=").append(Build.DISPLAY).append(" fingerprint=").append(Build.FINGERPRINT).append('\n');
        r.append("development_settings_enabled=").append(global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
                .append(" adb_enabled=").append(global(Settings.Global.ADB_ENABLED)).append('\n');

        ResolveInfo dev = pm.resolveActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0);
        r.append("dev action resolves to: ")
                .append(dev == null ? "nothing" : dev.activityInfo.packageName + "/" + dev.activityInfo.name).append('\n');
        ResolveInfo about = pm.resolveActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), 0);
        r.append("about action resolves to: ")
                .append(about == null ? "nothing" : about.activityInfo.packageName + "/" + about.activityInfo.name).append('\n');

        List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES
                | PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        r.append("\n== packages (").append(packages.size()).append(") ==\n");
        for (PackageInfo p : packages) {
            boolean system = p.applicationInfo != null
                    && (p.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean enabled = p.applicationInfo != null && p.applicationInfo.enabled;
            r.append(system ? "sys " : "usr ").append(enabled ? "on  " : "off ")
                    .append(p.packageName).append(' ').append(p.versionName).append('\n');
        }

        r.append("\n== activities matching ").append(String.join(",", KEYWORDS)).append(" ==\n");
        for (PackageInfo p : packages) {
            if (p.activities == null) continue;
            for (ActivityInfo a : p.activities) {
                String name = a.name.toLowerCase(Locale.ROOT);
                boolean match = false;
                for (String k : KEYWORDS) match |= name.contains(k);
                if (!match) continue;
                int state = pm.getComponentEnabledSetting(new ComponentName(a.packageName, a.name));
                boolean on = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && a.enabled);
                r.append(a.exported ? "exported " : "private  ").append(on ? "on  " : "off ")
                        .append(a.packageName).append('/').append(a.name).append('\n');
                if (a.exported && on && !a.packageName.equals(getPackageName())) {
                    candidates.add(new ComponentName(a.packageName, a.name));
                }
            }
        }
    }

    private String saveReport(String text) {
        ContentResolver cr = getContentResolver();
        try {
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[] {"devopts-report.txt"});
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "devopts-report.txt");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (OutputStream out = cr.openOutputStream(uri)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return "Download/devopts-report.txt";
        } catch (Exception e) {
            return "FAILED (" + e + ")";
        }
    }
}
