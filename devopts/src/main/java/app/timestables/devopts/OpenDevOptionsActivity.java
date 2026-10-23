package app.timestables.devopts;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Opens the Developer options screen without the Build-number taps. */
public class OpenDevOptionsActivity extends Activity {
    private static final Intent[] TARGETS = {
        new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        new Intent().setComponent(new ComponentName("com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsDashboardActivity")),
        new Intent().setComponent(new ComponentName("com.android.settings",
                "com.android.settings.DevelopmentSettings")),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setTextSize(18);
        help.setText("1. Tap a button below.\n"
                + "2. Turn ON the switch at the top (\"Use developer options\"), tap OK.\n"
                + "3. Scroll down, turn ON \"USB debugging\", tap OK.\n"
                + "4. Tap Allow on the \"Allow USB debugging?\" box.");
        root.addView(help);

        for (int i = 0; i < TARGETS.length; i++) {
            final Intent target = TARGETS[i];
            Button b = new Button(this);
            b.setTextSize(20);
            b.setText(i == 0 ? "Open Developer options" : "Try another way (" + (i + 1) + ")");
            b.setOnClickListener(v -> open(target));
            root.addView(b);
        }
        setContentView(root);
    }

    private void open(Intent target) {
        try {
            startActivity(new Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "That way didn't work: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }
}
