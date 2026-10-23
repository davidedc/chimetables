package app.timestables;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Self-contained numeric PIN dialog, so parent mode needs no system keyboard. */
final class PinPad {
    interface Listener { void onPin(String pin); }

    private PinPad() {}

    static void show(Activity activity, String title, Listener listener) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final StringBuilder pin = new StringBuilder();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(activity, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView heading = Ui.text(activity, 20, Ui.INK, true);
        heading.setText(title);
        root.addView(heading);

        final TextView dots = Ui.text(activity, 34, Ui.INK, true);
        dots.setText(" ");
        root.addView(dots, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 56)));

        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        for (String key : keys) {
            Button b = Ui.button(activity, key, "OK".equals(key) ? Ui.GREEN : Ui.BLUE, 24);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(activity, 84);
            lp.height = Ui.dp(activity, 64);
            lp.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                if ("⌫".equals(key)) {
                    if (pin.length() > 0) pin.setLength(pin.length() - 1);
                } else if ("OK".equals(key)) {
                    dialog.dismiss();
                    listener.onPin(pin.toString());
                    return;
                } else if (pin.length() < 8) {
                    pin.append(key);
                }
                StringBuilder shown = new StringBuilder();
                for (int i = 0; i < pin.length(); i++) shown.append("● ");
                dots.setText(pin.length() == 0 ? " " : shown.toString().trim());
            });
            grid.addView(b);
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(grid, gp);

        Button cancel = Ui.button(activity, "Cancel", Ui.SOFT_INK, 18);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 52));
        cp.topMargin = Ui.dp(activity, 8);
        cancel.setOnClickListener(v -> dialog.dismiss());
        root.addView(cancel, cp);

        dialog.setContentView(root);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
    }
}
