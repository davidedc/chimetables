package app.timestables;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;

/** Skeleton for gates G1-G3: shows device-owner / lock-task state and enters lock task when allowed. */
public class KioskActivity extends Activity {
    private static final int CORNER_TAPS = 7;
    private static final long CORNER_WINDOW_MS = 4000;

    private TextView status;
    private int cornerTaps;
    private long firstCornerTap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setTextSize(22);
        status.setTextColor(Color.BLACK);
        status.setGravity(Gravity.CENTER);
        setContentView(status);
        AppLog.log(this, "KioskActivity created");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Kiosk.allowLockTask(this);
        if (Kiosk.isDeviceOwner(this) && !Kiosk.isInLockTask(this)) {
            startLockTask();
        }
        render();
    }

    private void render() {
        status.setText("Hello from the Mac!\n\n"
                + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "\n"
                + "device owner: " + Kiosk.isDeviceOwner(this) + "\n"
                + "lock task: " + Kiosk.lockTaskStateName(this));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && isTopLeftCorner(ev)) {
            long now = SystemClock.uptimeMillis();
            if (cornerTaps == 0 || now - firstCornerTap > CORNER_WINDOW_MS) {
                cornerTaps = 0;
                firstCornerTap = now;
            }
            if (++cornerTaps >= CORNER_TAPS) {
                cornerTaps = 0;
                onCornerUnlock();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isTopLeftCorner(MotionEvent ev) {
        float size = 80 * getResources().getDisplayMetrics().density;
        return ev.getX() < size && ev.getY() < size;
    }

    /** Skeleton escape hatch (no PIN yet): leave lock task. */
    private void onCornerUnlock() {
        AppLog.log(this, "corner unlock: stopLockTask");
        if (Kiosk.isInLockTask(this)) stopLockTask();
        status.postDelayed(this::render, 300);
    }
}
