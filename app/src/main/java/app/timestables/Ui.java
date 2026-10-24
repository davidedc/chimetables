package app.timestables;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Small helpers for building the plain-View UI in code. */
final class Ui {
    static final int BG = Color.parseColor("#FFF8E7");
    static final int INK = Color.parseColor("#3A2E2A");
    static final int SOFT_INK = Color.parseColor("#8A7A70");
    static final int ORANGE = Color.parseColor("#FF8A3D");
    static final int GREEN = Color.parseColor("#43A047");
    static final int BLUE = Color.parseColor("#42A5F5");
    static final int GOLD = Color.parseColor("#FFB300");
    static final int FAINT = Color.parseColor("#E3D3C2");
    static final int NIGHT = Color.parseColor("#1D2340");
    static final int NIGHT_INK = Color.parseColor("#C9D1FF");

    private Ui() {}

    static int dp(Context c, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static Button button(Context c, String label, int color, float sp) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(c, 24));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null));
        // No vertical padding or font padding: otherwise a big label in a short button is taller
        // than the space inside it, and Android pins it to the top so it hangs low.
        b.setIncludeFontPadding(false);
        b.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        return b;
    }
}
